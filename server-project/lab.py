"""Local-only operational laboratory. Never bind this app to a LAN address."""
import json
import os
import secrets
import sys
import uuid
from pathlib import Path
from urllib.parse import urlsplit

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse, Response
from jinja2 import Environment, FileSystemLoader, select_autoescape

ROOT = Path(__file__).resolve().parent
sys.path.append(str(ROOT / "shared" if (ROOT / "shared").exists() else ROOT.parent / "windows-project"))

from lab_engine import (add_event, change_transfer, initial_state, records, release_pallet,
                        scan, snapshot_result, validate_pallet)
from packing import PackingError, parse_strict_packing
from protocol import Rejected, accept_snapshot, claim_session, create_session, export_wms
from store import Store, digest, encode
from core.optimizer import Settings, transfer_layout_summary
from core.pda_exchange import build_pda_manifest

templates = Environment(loader=FileSystemLoader(ROOT / "templates"), autoescape=select_autoescape())


def atomic_json(path: Path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(temporary, path)


def create_lab_app(lab_root=None, origin=None):
    lab_root = Path(lab_root or os.environ.get("ILUBOX_LAB_DIR", Path.home() / "IluboxLabV012")).resolve()
    origin = (origin or os.environ.get("ILUBOX_LAB_ORIGIN", "http://127.0.0.1:8765")).rstrip("/")
    parsed_origin = urlsplit(origin)
    port = parsed_origin.port or 80
    allowed_hosts = {parsed_origin.netloc.lower(), f"127.0.0.1:{port}", f"localhost:{port}", "testserver"}
    lab_root.mkdir(parents=True, exist_ok=True)
    os.chmod(lab_root, 0o700)
    state_path = lab_root / "pda-simulada.json"
    meta_path = lab_root / "laboratorio.json"
    db_path = lab_root / "servidor-laboratorio.sqlite3"
    store = Store(db_path)
    if not db_path.exists():
        store.initialize("laboratorio-local-sin-acceso-remoto")
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    csrf_token = secrets.token_urlsafe(24)

    def load(path):
        return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None

    def save(state, meta):
        atomic_json(state_path, state)
        atomic_json(meta_path, meta)

    def require_form(request, form):
        # The laboratory is already restricted to loopback hosts and every form
        # carries an unpredictable CSRF value. Some Windows browsers omit or
        # rewrite Origin on multipart uploads, so Origin must not make a valid
        # local Packing List fail.
        if not secrets.compare_digest(str(form.get("csrf", "")), csrf_token):
            raise HTTPException(403, "Formulario inválido")

    def packet(state, meta):
        result = snapshot_result(state)
        return {"schema": "ilubox.sync.v1", "session_id": meta["session_id"],
                "manifest_hash": meta["manifest_hash"], "revision": state["revision"],
                "sealed": state["sealed"], "partial_reason": meta.get("partial_reason", ""),
                "result": result, "audit": state["events"]}

    def prepare_pending(state, meta):
        if state["revision"] <= state["acknowledged"]:
            meta.pop("pending", None)
            return None
        current = packet(state, meta)
        raw = encode(current)
        meta["pending"] = raw.decode()
        return raw

    def synchronize(state, meta, twice=False):
        raw = meta.get("pending", "").encode() or prepare_pending(state, meta)
        if not raw and twice:
            raw = meta.get("last_ack_payload", "").encode()
        if not raw:
            state["last_message"] = "Servidor al día; no había cambios pendientes."
            return
        response = accept_snapshot(store, meta["session_id"], meta["token"], meta["device"], raw)
        if twice:
            repeated = accept_snapshot(store, meta["session_id"], meta["token"], meta["device"], raw)
            if response != repeated:
                raise RuntimeError("El reintento no devolvió el mismo ACK.")
        sent = json.loads(raw)
        if response["revision"] != sent["revision"] or response["sha256"] != digest(raw):
            raise RuntimeError("El ACK no corresponde a la revisión enviada.")
        state["acknowledged"] = response["revision"]
        meta["last_ack_payload"] = raw.decode()
        meta.pop("pending", None)
        state["last_message"] = ("Reintento idempotente aprobado: no se duplicó ninguna caja."
                                 if twice else "Cambios confirmados por el servidor.")

    def after_operation(state, meta):
        prepare_pending(state, meta)
        if state["online"]:
            operational_message = state["last_message"]
            synchronize(state, meta)
            state["last_message"] = operational_message
        save(state, meta)

    def context(message=""):
        state, meta = load(state_path), load(meta_path)
        server_row = store.get(meta["session_id"]) if meta else None
        server_packet = json.loads(server_row["payload"]) if server_row and server_row["payload"] else None
        result = snapshot_result(state) if state else None
        layout = transfer_layout_summary(list(records(state).values()), Settings(), len(state["positions"])) if state else None
        return {"csrf": csrf_token, "state": state, "meta": meta, "server": server_row,
                "server_packet": server_packet, "result": result, "layout": layout,
                "message": message or (state["last_message"] if state else "")}

    @app.middleware("http")
    async def local_only(request, call_next):
        if request.client and request.client.host not in {"127.0.0.1", "::1", "testclient"}:
            return HTMLResponse("Laboratorio disponible solo desde este equipo.", 403)
        if request.headers.get("host", "").lower() not in allowed_hosts:
            return HTMLResponse("Host no permitido.", 400)
        response = await call_next(request)
        response.headers.update({"Cache-Control": "no-store", "X-Frame-Options": "DENY",
                                 "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer",
                                 "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'"})
        return response

    @app.exception_handler(Rejected)
    @app.exception_handler(PackingError)
    async def operational_error(request, exc):
        return HTMLResponse(templates.get_template("lab.html").render(**context(str(exc))), getattr(exc, "status", 422))

    @app.exception_handler(HTTPException)
    async def local_http_error(request, exc):
        message = ("La página del laboratorio había vencido. Ya se generó un formulario nuevo; "
                   "vuelva a seleccionar el Packing List."
                   if exc.status_code == 403 else str(exc.detail))
        return HTMLResponse(templates.get_template("lab.html").render(**context(message)), exc.status_code)

    @app.get("/", response_class=HTMLResponse)
    def home():
        return templates.get_template("lab.html").render(**context())

    @app.post("/start")
    async def start(request: Request):
        form = await request.form()
        require_form(request, form)
        file = form.get("packing")
        if not file:
            raise PackingError("Seleccione un Packing List XLSX.")
        content = await file.read()
        filename = Path(file.filename or "packing.xlsx").name
        left, right = int(form.get("left", 2)), int(form.get("right", 2))
        await form.close()
        if not 0 <= left <= 10 or not 0 <= right <= 10 or left + right == 0:
            raise PackingError("Habilite entre 1 y 20 posiciones al pie.")
        container = parse_strict_packing(content, filename)
        manifest = json.loads(build_pda_manifest(container, Settings()))
        session_id, pairing = create_session(store, manifest, left, right)
        token = pairing.split(".", 1)[1]
        device = str(uuid.uuid4())
        claimed = claim_session(store, session_id, token, device)
        state = initial_state(manifest, left, right)
        meta = {"session_id": session_id, "token": token, "device": device,
                "manifest_hash": claimed["manifest_hash"], "partial_reason": ""}
        save(state, meta)
        return RedirectResponse("/", 303)

    async def run_action(request, operation):
        form = await request.form()
        require_form(request, form)
        state, meta = load(state_path), load(meta_path)
        if not state or not meta:
            raise PackingError("Primero cargue un Packing List.")
        try:
            operation(state, form)
            after_operation(state, meta)
        except Exception as exc:
            state["last_message"] = str(exc)
            save(state, meta)
        finally:
            await form.close()
        return RedirectResponse("/", 303)

    @app.post("/scan")
    async def scan_route(request: Request):
        return await run_action(request, lambda state, form: scan(state, str(form.get("scan", ""))))

    @app.post("/transfer")
    async def transfer_route(request: Request):
        return await run_action(request, lambda state, form: change_transfer(state))

    @app.post("/validate")
    async def validate_route(request: Request):
        return await run_action(request, lambda state, form: validate_pallet(
            state, str(form.get("pallet", "")), str(form.get("responsible", "")), str(form.get("temporary", ""))))

    @app.post("/release")
    async def release_route(request: Request):
        return await run_action(request, lambda state, form: release_pallet(state, str(form.get("pallet", ""))))

    @app.post("/network")
    async def network_route(request: Request):
        def toggle(state, form):
            state["online"] = not state["online"]
            state["last_message"] = "Wi-Fi simulada conectada." if state["online"] else "Wi-Fi simulada desconectada: los cambios quedan en la PDA."
        return await run_action(request, toggle)

    @app.post("/sync")
    async def sync_route(request: Request):
        form = await request.form()
        require_form(request, form)
        state, meta = load(state_path), load(meta_path)
        try:
            if not state or not meta:
                raise ValueError("Primero cargue un Packing List.")
            if not state["online"]:
                raise ValueError("Conecte la Wi-Fi simulada antes de sincronizar.")
            synchronize(state, meta, twice=form.get("twice") == "yes")
            save(state, meta)
        except Exception as exc:
            state["last_message"] = str(exc); save(state, meta)
        finally:
            await form.close()
        return RedirectResponse("/", 303)

    @app.post("/close")
    async def close_route(request: Request):
        form = await request.form(); require_form(request, form)
        state, meta = load(state_path), load(meta_path)
        try:
            if not state or not meta:
                raise ValueError("Primero cargue un Packing List.")
            result = snapshot_result(state)
            accepted, eligible, expected = len(result["accepted_events"]), result["progress"]["wms_eligible"], result["progress"]["expected"]
            reason = str(form.get("reason", "")).strip()
            if not accepted or eligible != accepted:
                raise ValueError("Todas las cajas escaneadas deben estar en tarimas verificadas con temporal WMS.")
            if accepted < expected and len(reason) < 8:
                raise ValueError("Cierre parcial: escriba un motivo de al menos 8 caracteres.")
            add_event(state, accepted=True, status="DESCARGA CERRADA LAN", message="Descarga cerrada para exportación WMS.")
            state["sealed"] = True; meta["partial_reason"] = reason
            after_operation(state, meta)
        except Exception as exc:
            state["last_message"] = str(exc); save(state, meta)
        finally:
            await form.close()
        return RedirectResponse("/", 303)

    @app.post("/export")
    async def export_route(request: Request):
        form = await request.form(); require_form(request, form)
        state, meta = load(state_path), load(meta_path)
        try:
            if not state or not meta:
                raise ValueError("Primero cargue un Packing List.")
            content, hash_value = export_wms(store, meta["session_id"], str(form.get("order", "")))
            return Response(content, media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            headers={"Content-Disposition": f'attachment; filename="WMS_LAB_{state["manifest"]["container_id"]}.xlsx"',
                                     "X-Content-SHA256": hash_value})
        finally:
            await form.close()

    @app.post("/reset")
    async def reset(request: Request):
        form = await request.form(); require_form(request, form)
        if form.get("confirm") != "BORRAR PRUEBA":
            raise HTTPException(422, "Escriba BORRAR PRUEBA")
        await form.close()
        for path in (state_path, meta_path, db_path, db_path.with_name(db_path.name + "-wal"), db_path.with_name(db_path.name + "-shm")):
            if path.exists(): path.unlink()
        Store(db_path).initialize("laboratorio-local-sin-acceso-remoto")
        return RedirectResponse("/", 303)

    return app
