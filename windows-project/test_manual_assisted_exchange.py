"""Contrato real Java V0.13 MANUAL ASISTIDA -> validador y XLSX Windows."""
from pathlib import Path
import sys

from core.parser import CodeRecord
from core.pda_exchange import parse_pda_result
from core.wms_putaway import WMS_HEADERS, build_putaway_rows


fixture = Path(sys.argv[1]) if len(sys.argv) == 2 else Path("tests/fixtures/v013/manual-v4.json")

records = {"CAJA": CodeRecord("CAJA", 2, 0.20, 0.10)}
imported = parse_pda_result(fixture.read_bytes(), records, "MANUAL-WMS")
assert imported.ready, imported.errors
assert imported.schema_version == 4
assert len(imported.events) == len(imported.eligible_events) == 2
assert {row["Escaneo"] for row in imported.events} == {"CAJAU001", "CAJAU002"}
assert {row["Tarima"] for row in imported.events} == {"T-01"}
assert {row["Posición física"] for row in imported.events} == {"I01"}
assert {row["Temporal WMS"] for row in imported.events} == {"2B-TMP-M1"}

wms = build_putaway_rows(
    imported.events,
    records,
    "PAS-MANUAL-001",
    location_by_pallet={"T-01": "2B-TMP-M1"},
    received=2,
    expected=2,
    require_final_validation=True,
)
assert wms.ready, wms.errors
assert len(wms.rows) == 2
assert {row[WMS_HEADERS[1]] for row in wms.rows} == {"CAJAU001", "CAJAU002"}
assert {row[WMS_HEADERS[3]] for row in wms.rows} == {"2B-TMP-M1"}

print("OK V0.13 Java MANUAL ASISTIDA -> Windows -> plantilla WMS")
