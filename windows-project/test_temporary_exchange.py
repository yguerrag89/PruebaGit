"""Regresión Java v4 → Windows → WMS: temporal tomada del cierre, sin sustituciones."""
from copy import deepcopy
from pathlib import Path
import json
import sys

from core.parser import CodeRecord
from core.pda_exchange import parse_pda_result
from core.wms_location import valid_wms_temporary
from core.wms_putaway import build_putaway_rows, WMS_HEADERS

FIXTURES = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).parent / "tests/fixtures/v011"
records = {"CHICOA": CodeRecord("CHICOA", 3, .6, .2), "CHICOB": CodeRecord("CHICOB", 3, .6, .2)}


def load(name, catalog=records, container="CONTINUO"):
    result = parse_pda_result((FIXTURES / name).read_bytes(), catalog, container)
    assert result.ready and result.schema_version == 4, (name, result.errors)
    return result


pending = load("v4-pending.json")
assert not pending.eligible_events and all(not e["Temporal WMS"] for e in pending.events)
assert not build_putaway_rows(pending.events, records, "PAS-PRUEBA", default_location="2B-TMP-XX", allow_partial=True).ready
mixed = load("v4-mixed.json")
assert len(mixed.eligible_events) == 3
assert not build_putaway_rows(mixed.events, records, "PAS-PRUEBA", allow_partial=True).ready
verified = load("v4-verified.json")
assert len(verified.eligible_events) == 6
automatic = build_putaway_rows(verified.events, records, "PAS-PRUEBA", received=6, expected=6)
assert automatic.ready and len(automatic.rows) == 6, automatic.errors
assert {r[WMS_HEADERS[3]] for r in automatic.rows} == {"2B-TMP-01", "2B-TMP-02"}
assert all(len(r) == 4 and type(r[WMS_HEADERS[2]]) is int and r[WMS_HEADERS[2]] == 1 for r in automatic.rows)
for override in ({"default_location": "2B-OTRA"}, {"location_by_pallet": {"T-01": "2B-OTRA"}}):
    result = build_putaway_rows(verified.events, records, "PAS-PRUEBA", **override)
    assert not result.ready and any("sustituir" in e for e in result.errors), result.errors
assert not build_putaway_rows(verified.events, records, "").ready, "La temporal no sustituye la orden Putaway"

partial_records = {"GRANDE": CodeRecord("GRANDE", 5, 2.0, .4), "OTRO": CodeRecord("OTRO", 5, 2.0, .4)}
partial = load("v4-partial.json", partial_records, "PARCIAL")
assert not build_putaway_rows(partial.events, partial_records, "PAS-PRUEBA", received=1, expected=10).ready
assert build_putaway_rows(partial.events, partial_records, "PAS-PRUEBA", received=1, expected=10, allow_partial=True).ready
direct_records = {"GRANDE": CodeRecord("GRANDE", 4, 2.0, .5)}
direct = load("v4-direct.json", direct_records, "DIRECTAS-TEMPORAL")
direct_rows = build_putaway_rows(direct.events, direct_records, "PAS-PRUEBA")
assert direct_rows.ready and len(direct_rows.rows) == 4, direct_rows.errors
assert {e["Posición física"] for e in direct.events} == {"I01"}
assert {e["Temporal WMS"] for e in direct.events} == {"2B-TMP-01", "2B-TMP-02"}
assert all(e["Temporal WMS obligatoria"] is True for e in direct.events)
assert not build_putaway_rows(direct.events, direct_records, "PAS-PRUEBA", location_by_position={"I01": "2B-OTRA"}).ready

base = json.loads((FIXTURES / "v4-verified.json").read_bytes())
shared = deepcopy(base)
for pallet in shared["pallets"]:
    pallet["wms_temporary_location"] = "2B-TMP-COMUN"
for event in shared["accepted_events"]:
    event["wms_temporary_location"] = "2B-TMP-COMUN"
shared_result = parse_pda_result(json.dumps(shared).encode(), records, "CONTINUO")
assert shared_result.ready and build_putaway_rows(shared_result.events, records, "PAS-PRUEBA").ready
mutations = [
    ("secuencia booleana", lambda p: p["individual_sequence"].update(start=True)),
    ("padding decimal", lambda p: p["individual_sequence"].update(padding=3.0)),
    ("temporal ausente tarima", lambda p: p["pallets"][0].pop("wms_temporary_location")),
    ("temporal vacía tarima", lambda p: p["pallets"][0].update(wms_temporary_location="")),
    ("temporal ausente caja", lambda p: p["accepted_events"][0].pop("wms_temporary_location")),
    ("temporal distinta caja", lambda p: p["accepted_events"][0].update(wms_temporary_location="2B-OTRA")),
    ("temporales intercambiadas", lambda p: p["pallets"][0].update(wms_temporary_location="2B-TMP-02")),
    ("temporal no texto", lambda p: p["pallets"][0].update(wms_temporary_location=123)),
    ("modelo viejo", lambda p: p.update(verification_model="FINAL_PALLET")),
    ("catálogo no consultado", lambda p: p.update(wms_location_validation="WMS_VERIFIED")),
    ("estado no explícito", lambda p: p["accepted_events"][0].update(wms_eligible="true")),
    ("responsable vacío", lambda p: p["pallets"][0].update(verified_by="")),
    ("prueba heredada", lambda p: p["pallets"][0].update(verification_method="LEGADO_V09", verified_by="", verified_at="")),
    ("cantidad falsa", lambda p: p["pallets"][0].update(scanned=2)),
    ("avance falso", lambda p: p["progress"].update(received=7)),
    ("caja duplicada", lambda p: p["accepted_events"].append(deepcopy(p["accepted_events"][0]))),
]
for name, mutate in mutations:
    payload = deepcopy(base)
    mutate(payload)
    invalid = parse_pda_result(json.dumps(payload).encode(), records, "CONTINUO")
    assert not invalid.ready and invalid.errors, name

invalid_locations = [None, "", " ", "I01", "D10", "T-01", "TR-04", "=A1", "+A1", "-A1", "@A1", "2B TMP 01",
                     "2B\nTMP", "2B\tTMP", "2B\x7fTMP", "\x002B-TMP", "2B-TMP\r", '{"location":"2B"}', "X" * 81,
                     " 2B-TMP-01 ", "2b-tmp-01"]
for value in invalid_locations:
    assert not valid_wms_temporary(value)
    payload = deepcopy(base)
    pallet_id = payload["pallets"][0]["id"]
    payload["pallets"][0]["wms_temporary_location"] = value
    for event in payload["accepted_events"]:
        if event["final_pallet"] == pallet_id:
            event["wms_temporary_location"] = value
    assert not parse_pda_result(json.dumps(payload).encode(), records, "CONTINUO").ready, value

pending_payload = json.loads((FIXTURES / "v4-pending.json").read_bytes())
pending_payload["pallets"][0]["wms_temporary_location"] = "2B-TMP-01"
assert not parse_pda_result(json.dumps(pending_payload).encode(), records, "CONTINUO").ready
forged = deepcopy(verified.events)
forged[0]["Temporal WMS"] = ""
assert not build_putaway_rows(forged, records, "PAS-PRUEBA", default_location="2B-TMP-01").ready
conflict = deepcopy(verified.events)
conflict[1]["Temporal WMS"] = "2B-TMP-DISTINTA"
assert not build_putaway_rows(conflict, records, "PAS-PRUEBA").ready
print(f"OK V0.11 Java → Windows: 5 escenarios nativos, {len(mutations) + len(invalid_locations) + 1} archivos inconsistentes rechazados; temporales sin recaptura")
