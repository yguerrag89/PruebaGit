"""LAN pilot. Run behind the supplied HTTPS-only Nginx virtual host."""
import io
import json
import math
import os
import secrets
import sqlite3
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, Response
from jinja2 import Environment, FileSystemLoader, select_autoescape
from starlette.concurrency import run_in_threadpool

from protocol import (MAX_BYTES, Rejected, accept_snapshot, claim_session, create_session,
                      export_wms, records_for)
from store import Store, digest, password_hash
from core.parser import parse_xlsx_bytes
from core.parser import _find_header, norm_text
from core.xlsx_reader import read_xlsx_rows
from core.strict_scan import canonical_scan
from core.optimizer import Settings, transfer_layout_summary
from core.pda_exchange import build_pda_manifest

ROOT = Path(__file__).resolve().parent
templates = Environment(loader=FileSystemLoader(ROOT / "templates"), autoescape=select_autoescape())


def create_app(db_path=None, origin=None):
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    store = Store(db_path or os.environ.get("ILUBOX_DB", "/var/lib/ilubox-putaway/putaway.sqlite3"))
    origin = (origin or os.environ.get("ILUBOX_ORIGIN", "")).rstrip("/")
    parsed_origin = urlsplit(origin)
    if parsed_origin.scheme != "https" or not parsed_origin.netloc or parsed_origin.path or parsed_origin.query or parsed_origin.fragment or parsed_origin.username:
        raise ValueError("ILUBOX_ORIGIN debe ser el origen HTTPS exacto, sin ruta ni credenciales.")
    app.state.store = store

    @app.middleware("http")
    async def security(request, call_next):
        if request.headers.get("host", "").lower() != parsed_origin.netloc.lower():
            return JSONResponse({"detail": "Host no permitido"}, 400)
        try:
            declared = int(request.headers.get("content-length", "0"))
        except ValueError:
            return JSONResponse({"detail": "Tamaño inválido"}, 400)
        if declared > MAX_BYTES:
            return JSONResponse({"detail": "Máximo 16 MiB"}, 413)
        # Enforce limit for chunked requests too; protect before parsing multipart.
        if request.method in {"POST", "PUT"}:
            body = bytearray()
            async for chunk in request.stream():
                body.extend(chunk)
                if len(body) > MAX_BYTES:
                    return JSONResponse({"detail": "Máximo 16 MiB"}, 413)
            request._body = bytes(body)
            if not request.url.path.startswith("/api/") and request.headers.get("origin") != origin:
                return JSONResponse({"detail": "Origen no permitido"}, 403)
        response = await call_next(request)
        response.headers.update({"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
                                 "Referrer-Policy": "no-referrer", "X-Frame-Options": "DENY",
                                 "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"})
        return response

    @app.exception_handler(Rejected)
    async def rejected(request, exc):
        if request.url.path.startswith("/api/"):
            return JSONResponse({"detail": str(exc)}, exc.status)
        return HTMLResponse(templates.get_template("page.html").render(error=str(exc)), exc.status)

    def web_user(request):
        token = request.cookies.get("ilubox_session", "")
        with store.transaction() as con:
            row = con.execute("SELECT * FROM web_sessions WHERE token_hash=? AND expires>?", (digest(token.encode()), time.time())).fetchone()
            if not row:
                raise HTTPException(401, "Inicie sesión en /login")
            return dict(row)

    def csrf(request, form):
        session = web_user(request)
        if not secrets.compare_digest(session["csrf"], str(form.get("csrf", ""))):
            raise HTTPException(403, "Formulario vencido")

    def page(request, **values):
        session = web_user(request)
        return HTMLResponse(templates.get_template("page.html").render(csrf=session["csrf"], **values))

    def api_auth(request):
        auth = request.headers.get("authorization", "")
        if not auth.startswith("Bearer ") or len(auth) > 200:
            raise Rejected("Falta credencial de la descarga.", 401)
        return auth[7:], request.headers.get("x-ilubox-device", "")

    @app.get("/health")
    def health():
        with store.transaction() as con:
            ok = con.execute("SELECT value FROM meta WHERE key='schema'").fetchone()
        return {"status": "ok" if ok and ok[0] == "1" else "schema-error", "version": "0.12"}

    @app.get("/login", response_class=HTMLResponse)
    def login():
        return templates.get_template("page.html").render(login=True)

    @app.post("/login")
    async def authenticate(request: Request):
        if not store.rate_limit("login:" + request.client.host):
            raise HTTPException(429, "Demasiados intentos. Espere cinco minutos.")
        form = await request.form()
        password = str(form.get("password", ""))
        with store.transaction() as con:
            saved = con.execute("SELECT value FROM meta WHERE key='password'").fetchone()[0]
        valid = len(password) <= 1024 and secrets.compare_digest(password_hash(password, saved.split(":")[0]), saved)
        if not valid:
            raise HTTPException(401, "Credenciales incorrectas")
        token = secrets.token_urlsafe(32)
        with store.transaction() as con:
            con.execute("DELETE FROM web_sessions WHERE expires < ?", (time.time(),))
            con.execute("INSERT INTO web_sessions VALUES(?,?,?)", (digest(token.encode()), secrets.token_urlsafe(24), time.time() + 8 * 3600))
        response = RedirectResponse("/", 303)
        response.set_cookie("ilubox_session", token, max_age=8 * 3600, httponly=True, secure=True, samesite="strict")
        return response

    @app.post("/logout")
    async def logout(request: Request):
        form = await request.form()
        csrf(request, form)
        with store.transaction() as con:
            con.execute("DELETE FROM web_sessions WHERE token_hash=?", (digest(request.cookies.get("ilubox_session", "").encode()),))
        response = RedirectResponse("/login", 303)
        response.delete_cookie("ilubox_session", secure=True, httponly=True, samesite="strict")
        return response

    @app.get("/")
    def home(request: Request):
        if not request.cookies.get("ilubox_session"):
            return RedirectResponse("/login", 303)
        web_user(request)
        with store.transaction() as con:
            rows = [dict(row) for row in con.execute("SELECT id,container,revision,sealed,updated,device FROM sessions ORDER BY created DESC LIMIT 100")]
        return page(request, sessions=rows)

    @app.post("/sessions")
    async def upload(request: Request):
        form = await request.form()
        csrf(request, form)
        file = form.get("packing")
        if not file or not getattr(file, "filename", "").lower().endswith(".xlsx"):
            await form.close()
            raise Rejected("En este piloto cargue un Packing List .xlsx con un solo contenedor.", 422)
        try:
            left, right = int(form.get("left", "2")), int(form.get("right", "2"))
            if not 0 <= left <= 10 or not 0 <= right <= 10 or left + right == 0:
                raise ValueError()
            content = await file.read()
            await form.close()
            # XLSX is a ZIP: bound expanded size and reject malformed structures.
            with zipfile.ZipFile(io.BytesIO(content)) as archive:
                members = archive.infolist()
                if len(members) > 1000 or sum(x.file_size for x in members) > 64 * 1024 * 1024:
                    raise ValueError()
            # The desktop parser is permissive for exploration. A server assignment
            # must not silently round fractional box counts or skip malformed rows.
            for sheet_name, rows in read_xlsx_rows(content, max_rows=None, max_cols=40):
                header = _find_header(rows)
                if not header:
                    continue
                _, start, columns = header
                if not all(k in columns for k in ("code", "boxes", "cbm")):
                    raise Rejected(f"{sheet_name}: faltan código, cajas o CBM.", 422)
                for number, row in enumerate(rows[start+1:], start+2):
                    code, boxes, cbm = [norm_text(row[columns[k]]) for k in ("code", "boxes", "cbm")]
                    if not code and not boxes and not cbm:
                        continue
                    try:
                        count, volume = float(boxes), float(cbm)
                        if not code or code.upper() in {"TOTAL", "TOTALES", "合计", "总计"} or not math.isfinite(count) or not count.is_integer() or not 1 <= count <= 999 or not math.isfinite(volume) or volume <= 0:
                            raise ValueError()
                    except ValueError:
                        raise Rejected(f"{sheet_name}, fila {number}: corrija cajas/CBM o retire las filas de totales; no se omitió la fila.", 422)
            containers = await run_in_threadpool(parse_xlsx_bytes, content, Path(file.filename).name)
            if len(containers) != 1:
                raise Rejected("Separe el archivo para dejar un solo contenedor por carga.", 422)
            container = containers[0]
            if container.warnings:
                raise Rejected("Revise el Packing List antes de asignarlo: " + "; ".join(container.warnings[:8]), 422)
            if not container.records or container.total_boxes > 10000:
                raise Rejected("Piloto limitado a 10 000 cajas por descarga.", 422)
            if any(not 1 <= r.boxes <= 999 or r.code != canonical_scan(r.code) or not math.isfinite(r.cbm_per_box) or r.cbm_per_box <= 0 or not math.isfinite(r.cbm) or r.cbm <= 0 for r in container.records):
                raise Rejected("Todas las líneas necesitan cajas y volumen positivo para planificar tarimas.", 422)
            identifier, pairing = create_session(store, json.loads(build_pda_manifest(container, Settings())), left, right)
        except Rejected:
            raise
        except sqlite3.IntegrityError:
            raise Rejected("Ya existe una descarga activa de este contenedor.")
        except (ValueError, TypeError, KeyError, zipfile.BadZipFile, OSError):
            raise Rejected("Packing List o posiciones inválidos. Revise el archivo.", 422)
        finally:
            await form.close()
        return page(request, pairing=pairing, identifier=identifier, server_origin=origin)

    @app.get("/sessions/{identifier}")
    def detail(identifier: str, request: Request):
        web_user(request)
        row = store.get(identifier)
        if not row:
            raise HTTPException(404)
        data = json.loads(row["payload"]) if row["payload"] else None
        result = data["result"] if data else None
        layout = transfer_layout_summary(list(records_for(row).values()), Settings(), row["left_positions"] + row["right_positions"])
        return page(request, session=row, result=result, snapshot=data, layout=layout,
                    updated=datetime.fromtimestamp(row["updated"], timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC"))

    @app.post("/sessions/{identifier}/assignment")
    async def assignment(identifier: str, request: Request):
        form = await request.form()
        csrf(request, form)
        with store.transaction() as con:
            row = con.execute("SELECT * FROM sessions WHERE id=?", (identifier,)).fetchone()
            if not row or row["device"] is not None or row["revision"] != 0 or row["sealed"]:
                raise Rejected("Solo puede corregirse una asignación que ninguna PDA haya reclamado.")
            if form.get("action") == "cancel":
                con.execute("UPDATE sessions SET sealed=1,updated=? WHERE id=?", (time.time(),identifier))
                return RedirectResponse("/",303)
            if form.get("action") != "reissue":
                raise Rejected("Acción inválida.",422)
            token = secrets.token_urlsafe(24)
            con.execute("UPDATE sessions SET pairing_hash=? WHERE id=?", (digest(token.encode()),identifier))
        return page(request,pairing=identifier+"."+token,identifier=identifier,server_origin=origin)

    @app.post("/sessions/{identifier}/export")
    async def export(identifier: str, request: Request):
        form = await request.form()
        csrf(request, form)
        if form.get("reviewed") != "yes":
            raise Rejected("Confirme revisión física, temporales y orden WMS.", 422)
        content, hash_value = await run_in_threadpool(export_wms, store, identifier, str(form.get("order", "")))
        return Response(content, media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        headers={"Content-Disposition": f'attachment; filename="WMS_{identifier}.xlsx"', "X-Content-SHA256": hash_value})

    @app.get("/sessions/{identifier}/audit")
    def audit(identifier: str, request: Request):
        web_user(request)
        row = store.get(identifier)
        if not row or not row["payload"]:
            raise HTTPException(404)
        return Response(row["payload"], media_type="application/json", headers={"Content-Disposition": f'attachment; filename="auditoria_{identifier}.json"'})

    @app.post("/api/sessions/{identifier}/claim")
    def claim(identifier: str, request: Request):
        if not store.rate_limit("claim:" + request.client.host):
            raise Rejected("Demasiados intentos. Espere cinco minutos.", 429)
        token, device = api_auth(request)
        return claim_session(store, identifier, token, device)

    @app.put("/api/sessions/{identifier}/snapshot")
    async def sync(identifier: str, request: Request):
        token, device = api_auth(request)
        body = await request.body()
        return await run_in_threadpool(accept_snapshot, store, identifier, token, device, body)

    return app
