"""Contrato real Java → JSON v3 → Windows → filas WMS, más casos corruptos."""
from copy import deepcopy
from pathlib import Path
import json
import sys

from core.parser import CodeRecord
from core.pda_exchange import parse_pda_result
from core.wms_putaway import WMS_HEADERS, build_putaway_rows, summarize_pallets

FIXTURES = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).parent / "tests/fixtures/v010"
records = {
    "CHICOA": CodeRecord("CHICOA", 3, 0.60, 0.20),
    "CHICOB": CodeRecord("CHICOB", 3, 0.60, 0.20),
}


def read(name, catalog=records, container="CONTINUO"):
    result = parse_pda_result((FIXTURES / name).read_bytes(), catalog, container)
    assert result.ready, (name, result.errors)
    assert result.schema_version == 3
    return result


pending = read("v3-pending.json")
assert len(pending.events) == 4 and not pending.eligible_events
assert all(event["Estado físico"] == "PENDIENTE_VERIFICAR" for event in pending.events)
assert len(pending.transfers) == 3
assert not build_putaway_rows(pending.events, records, "PAS-PRUEBA", default_location="2B-F01", allow_partial=True).ready

mixed = read("v3-mixed.json")
assert len(mixed.eligible_events) == 3 and len(mixed.events) == 4
assert {event["Código"] for event in mixed.eligible_events} == {"CHICOA"}
blocked = build_putaway_rows(mixed.events, records, "PAS-PRUEBA", default_location="2B-F01", allow_partial=True)
assert not blocked.ready and any("no es elegible" in error for error in blocked.errors)
assert all(not event["Traslado distribuido"] for event in mixed.events), "No se inventa confirmación por viaje"

verified = read("v3-verified.json")
assert len(verified.eligible_events) == 6
assert len({event["Escaneo"] for event in verified.events}) == 6
assert any(event["Verificado por"] == 'OP-1 "Ana"' for event in verified.events)
assert any(event["Tarima retirada"] for event in verified.events)
wms = build_putaway_rows(
    verified.events, records, "PAS-PRUEBA",
    location_by_pallet={"T-01": "2B-F01", "T-02": "2B-F02"}, received=6, expected=6,
)
assert wms.ready and len(wms.rows) == 6, wms.errors
assert all(row[WMS_HEADERS[2]] == 1 and len(row) == 4 for row in wms.rows)
assert {row[WMS_HEADERS[3]] for row in wms.rows} == {"2B-F01", "2B-F02"}
assert all(row["Posición física"] == "" for row in summarize_pallets(verified.events)), "T-xx no es posición física"

partial_records = {
    "GRANDE": CodeRecord("GRANDE", 5, 2.0, 0.4),
    "OTRO": CodeRecord("OTRO", 5, 2.0, 0.4),
}
partial = read("v3-partial.json", partial_records, "PARCIAL")
assert len(partial.events) == len(partial.eligible_events) == 1
assert partial.pallets[0]["expected"] == 1 and partial.pallets[0]["original_expected"] == 2
assert partial.pallets[0]["closure_reason"] == "Falta de espacio al pie"
assert summarize_pallets(partial.events)[0]["Posición física"] == "I01"
assert not build_putaway_rows(partial.events, partial_records, "PAS-PRUEBA", default_location="2B-F01", received=1, expected=10).ready
assert build_putaway_rows(partial.events, partial_records, "PAS-PRUEBA", default_location="2B-F01", received=1, expected=10, allow_partial=True).ready

old_records = {"GRANDE": CodeRecord("GRANDE", 10, 4.0, 0.4), "CHICO": CodeRecord("CHICO", 2, 0.2, 0.1)}
migrated = read("v3-migrated.json", old_records, "PRUEBA-MIGRACION")
assert len(migrated.events) == 3 and len(migrated.eligible_events) == 1
assert any("V0.9" in warning for warning in migrated.warnings)
assert migrated.eligible_events[0]["Fecha verificación"] == ""
assert migrated.eligible_events[0]["Método verificación"] == "LEGADO_V09"

# Datos sintácticamente JSON pero inconsistentes nunca producen un resultado importable.
base = json.loads((FIXTURES / "v3-verified.json").read_bytes())
mutations = [
    ("responsable vacío", lambda p: p["pallets"][0].update(verified_by="")),
    ("fecha inválida", lambda p: p["pallets"][0].update(verified_at="ayer")),
    ("fecha sin zona", lambda p: p["pallets"][0].update(verified_at="2026-08-28T10:00:00")),
    ("prueba ausente", lambda p: p["pallets"][0].update(verification_method="")),
    ("conteo diferente", lambda p: p["pallets"][0].update(scanned=1)),
    ("verificación parcial", lambda p: p["pallets"][0].update(verified_boxes=1)),
    ("estado incompatible", lambda p: p["pallets"][0].update(status="EN FORMACIÓN")),
    ("resumen incompleto", lambda p: p["pallets"].pop()),
    ("avance diferente", lambda p: p["progress"].update(received=7)),
    ("conteo booleano", lambda p: p["pallets"][0].update(expected=True)),
    ("conteo fraccionario", lambda p: p["pallets"][0].update(scanned=3.5)),
    ("tipo formación inválido", lambda p: p["pallets"][0].update(formation=[])),
    ("tipo verificador inválido", lambda p: p["pallets"][0].update(verified_by={})),
    ("activo cerrado", lambda p: p["transfers"][-1].update(closed=True)),
    ("otro viaje abierto", lambda p: p["transfers"][0].update(closed=False)),
    ("viaje omitido", lambda p: p["transfers"].pop(0)),
    ("viaje duplicado", lambda p: p["transfers"].append(deepcopy(p["transfers"][0]))),
    ("activo inválido", lambda p: p.update(active_transfer=[])),
    ("modelo inválido", lambda p: p.update(verification_model="AUTO")),
    ("lectura discordante", lambda p: p["accepted_events"][0].update(raw_scan="CHICOAU002")),
    ("identificador ausente", lambda p: p["accepted_events"][0].update(code="")),
    ("número fraccionario", lambda p: p["accepted_events"][0].update(box_number=1.5)),
    ("número booleano", lambda p: p["accepted_events"][0].update(box_number=True)),
    ("estado textual", lambda p: p["accepted_events"][0].update(wms_eligible="true")),
    ("caja duplicada", lambda p: p["accepted_events"].append(deepcopy(p["accepted_events"][0]))),
    ("caja omitida", lambda p: p["accepted_events"].pop()),
    ("tarima duplicada", lambda p: p["pallets"].append(deepcopy(p["pallets"][0]))),
]
for name, mutate in mutations:
    payload = deepcopy(base)
    mutate(payload)
    invalid = parse_pda_result(json.dumps(payload).encode(), records, "CONTINUO")
    assert not invalid.ready and invalid.errors, name

fake_event = dict(pending.events[0], **{"Elegible WMS": True})
assert not build_putaway_rows([fake_event], records, "PAS-PRUEBA", default_location="2B-F01", allow_partial=True).ready
bad_barcode = dict(verified.events[0], **{"Escaneo": "CHICOAU099"})
assert not build_putaway_rows([bad_barcode], records, "PAS-PRUEBA", default_location="2B-F01", allow_partial=True).ready

print(f"OK V0.10 Java → Windows → WMS: 5 escenarios nativos, {len(mutations)} archivos inconsistentes rechazados")
