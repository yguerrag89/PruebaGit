"""Cumulative, revisioned snapshots: an HTTP retry never adds boxes twice."""
import json
import re
import secrets
import sys
import time
import uuid
from pathlib import Path

from store import digest, encode

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "shared" if (ROOT / "shared").exists() else ROOT.parent / "windows-project"))
from core.parser import CodeRecord
from core.pda_exchange import parse_pda_result
from core.wms_putaway import build_putaway_rows, export_official_putaway_xlsx

MAX_BYTES = 16 * 1024 * 1024


class Rejected(ValueError):
    def __init__(self, message, status=409):
        super().__init__(message)
        self.status = status


def create_session(store, manifest, left, right):
    identifier = str(uuid.uuid4())
    token = secrets.token_urlsafe(24)
    data = encode(manifest)
    now = time.time()
    with store.transaction() as con:
        con.execute("INSERT INTO sessions(id,container,manifest,manifest_hash,left_positions,right_positions,pairing_hash,created,updated) VALUES(?,?,?,?,?,?,?,?,?)",
                    (identifier, manifest["container_id"], data.decode(), digest(data), left, right, digest(token.encode()), now, now))
    return identifier, identifier + "." + token


def authorize(row, token, device, claim=False):
    if not row or not secrets.compare_digest(row["pairing_hash"], digest(token.encode())):
        raise Rejected("Credencial de descarga inválida.", 401)
    if not re.fullmatch(r"[a-f0-9-]{36}", device):
        raise Rejected("Identificador de PDA inválido.", 400)
    if row["device"] != device and not (claim and row["device"] is None):
        raise Rejected("Esta descarga pertenece a otra PDA.")


def claim_session(store, identifier, token, device):
    with store.transaction() as con:
        row = con.execute("SELECT * FROM sessions WHERE id=?", (identifier,)).fetchone()
        authorize(row, token, device, claim=True)
        if row["sealed"]:
            raise Rejected("La descarga ya está cerrada.")
        con.execute("UPDATE sessions SET device=? WHERE id=?", (device, identifier))
        return {"session_id": identifier, "manifest": json.loads(row["manifest"]),
                "manifest_hash": row["manifest_hash"], "left": row["left_positions"],
                "right": row["right_positions"], "revision": row["revision"]}


def records_for(row):
    manifest = json.loads(row["manifest"])
    return {r["code"].upper(): CodeRecord(**r) for r in manifest["records"]}


def validate_snapshot(row, data):
    if data.get("schema") != "ilubox.sync.v1" or data.get("session_id") != row["id"]:
        raise Rejected("Sobre de sincronización o descarga incorrecto.", 422)
    if data.get("manifest_hash") != row["manifest_hash"]:
        raise Rejected("El manifiesto de la PDA no corresponde al servidor.", 422)
    revision = data.get("revision")
    if type(revision) is not int or not 1 <= revision <= 2**53:
        raise Rejected("Revisión inválida.", 422)
    if type(data.get("sealed")) is not bool:
        raise Rejected("El cierre debe ser explícito.", 422)
    result = data.get("result")
    if not isinstance(result, dict) or result.get("schema") != "ilubox.pda.result.v4":
        raise Rejected("Se requiere un resultado PDA v4.", 422)
    parsed = parse_pda_result(encode(result), records_for(row), row["container"])
    # An empty live snapshot is valid (including undo of the first scan), but
    # the export parser intentionally reports it as non-exportable.
    errors = [e for e in parsed.errors if not (result.get("accepted_events") == []
              and e == "El resultado PDA no contiene cajas válidas aceptadas.")]
    if errors:
        raise Rejected("; ".join(errors[:5]), 422)
    audit = data.get("audit")
    if not isinstance(audit, list) or len(audit) > 100_000:
        raise Rejected("Historial inválido o demasiado grande.", 422)
    for index, item in enumerate(audit, 1):
        if not isinstance(item, dict) or type(item.get("id")) is not int or item["id"] != index:
            raise Rejected("El historial debe conservar todos los eventos consecutivos.", 422)
    scanned = {(x.get("barcode"), x.get("scan")) for x in audit if x.get("accepted") is True}
    if any((box["barcode"], box["raw_scan"]) not in scanned for box in result["accepted_events"]):
        raise Rejected("Falta la lectura original de una caja en el historial.", 422)
    if row["payload"]:
        previous = json.loads(row["payload"])
        old_audit = previous["audit"]
        if audit[:len(old_audit)] != old_audit:
            raise Rejected("No se permite borrar o sustituir el historial confirmado.")
        # Verified composition and location are immutable, even in later snapshots.
        old_result = previous["result"]
        new_pallets = {p["id"]: p for p in result["pallets"]}
        for pallet in old_result["pallets"]:
            if not pallet["validated"]:
                continue
            current = new_pallets.get(pallet["id"], {})
            immutable = ("validated", "wms_temporary_location", "verified_by", "verified_at", "verified_boxes", "expected", "closure_reason")
            if any(current.get(k) != pallet.get(k) for k in immutable):
                raise Rejected("Una tarima verificada no puede cambiar composición o temporal.")
            old_boxes = [x for x in old_result["accepted_events"] if x["final_pallet"] == pallet["id"]]
            new_boxes = [x for x in result["accepted_events"] if x["final_pallet"] == pallet["id"]]
            if old_boxes != new_boxes:
                raise Rejected("Cambió el contenido de una tarima verificada.")
    if data["sealed"]:
        if not parsed.events or len(parsed.eligible_events) != len(parsed.events):
            raise Rejected("Para cerrar, todas las cajas deben estar verificadas y tener temporal WMS.", 422)
        expected = sum(r.boxes for r in records_for(row).values())
        if len(parsed.events) != expected and len(str(data.get("partial_reason", "")).strip()) < 8:
            raise Rejected("El cierre parcial requiere un motivo de al menos 8 caracteres.", 422)
    return parsed


def accept_snapshot(store, identifier, token, device, body):
    if len(body) > MAX_BYTES:
        raise Rejected("La descarga excede el límite de sincronización de 16 MiB.", 413)
    try:
        data = json.loads(body)
        if not isinstance(data, dict):
            raise ValueError()
        revision = data.get("revision")
        hash_value = digest(body)
        with store.transaction() as con:
            row = con.execute("SELECT * FROM sessions WHERE id=?", (identifier,)).fetchone()
            authorize(row, token, device)
            if type(revision) is not int:
                raise Rejected("Revisión inválida.", 422)
            if revision == row["revision"] and hash_value == row["payload_hash"]:
                return {"revision": revision, "sha256": hash_value, "sealed": bool(row["sealed"])}
            if row["sealed"] or revision <= row["revision"]:
                raise Rejected("Descarga cerrada o revisión anterior/conflictiva. No se reemplazó ningún dato.")
            validate_snapshot(row, data)
            con.execute("UPDATE sessions SET revision=?,payload=?,payload_hash=?,sealed=?,updated=? WHERE id=?",
                        (revision, body.decode(), hash_value, int(data["sealed"]), time.time(), identifier))
            return {"revision": revision, "sha256": hash_value, "sealed": data["sealed"]}
    except Rejected:
        raise
    except (ValueError, TypeError, KeyError, AttributeError, OverflowError) as exc:
        raise Rejected("JSON o estructura de sincronización inválida.", 422) from exc


def export_wms(store, identifier, order):
    with store.transaction() as con:
        row = con.execute("SELECT * FROM sessions WHERE id=?", (identifier,)).fetchone()
        if not row or not row["sealed"]:
            raise Rejected("La PDA debe cerrar y sincronizar la descarga antes de exportar.")
        previous = con.execute("SELECT * FROM exports WHERE session_id=? AND revision=?", (identifier, row["revision"])).fetchone()
        order = re.sub(r"\s+", "", order).upper()
        if previous:
            if previous["order_id"] != order:
                raise Rejected("Ya se generó esta revisión con otra orden. Revise el historial; no se creó un segundo movimiento.")
            return bytes(previous["content"]), previous["hash"]
        snapshot = json.loads(row["payload"])
        parsed = validate_snapshot(row, snapshot)
        built = build_putaway_rows(parsed.events, records_for(row), order,
                                  received=len(parsed.events), expected=sum(r.boxes for r in records_for(row).values()),
                                  allow_partial=bool(snapshot.get("partial_reason")))
        if not built.ready:
            raise Rejected("; ".join(built.errors), 422)
        template = ROOT / "shared/assets/templates/Plantilla_oficial_WMS_PutawayCrossDockImport.xlsx"
        if not template.exists():
            template = ROOT.parent / "windows-project/assets/templates/Plantilla_oficial_WMS_PutawayCrossDockImport.xlsx"
        content = export_official_putaway_xlsx(built.rows, template.read_bytes())
        hash_value = digest(content)
        con.execute("INSERT INTO exports(session_id,revision,order_id,content,hash,created) VALUES(?,?,?,?,?,?)",
                    (identifier, row["revision"], order, content, hash_value, time.time()))
        return content, hash_value
