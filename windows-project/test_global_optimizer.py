import json

from core.optimizer import Settings, build_transfer_plan, transfer_layout_summary
from core.parser import CodeRecord, ParsedContainer
from core.pda_exchange import build_pda_manifest


def record(code, boxes, unit_cbm, unit_weight):
    return CodeRecord(code, boxes, boxes * unit_cbm, unit_cbm, unit_weight)


settings = Settings(target_capacity=1.0, physical_capacity=1.2, max_weight=1000.0)
records = [
    record("GENERAL_A", 4, 0.20, 100.0),
    record("GENERAL_B", 4, 0.20, 100.0),
    record("UNIT_A", 1, 0.20, 50.0),
    record("UNIT_B", 1, 0.20, 50.0),
    record("PAIR", 2, 0.10, 25.0),
    record("MULTI", 5, 0.40, 100.0),
]

plan = build_transfer_plan(records, settings)
reverse = build_transfer_plan(list(reversed(records)), settings)
assert plan.assignments == reverse.assignments, "el orden del Packing List no debe cambiar el tendido"
assert plan.direct_codes == {"MULTI"}
assert len(plan.direct_pallets) == 3
assert plan.assignments["UNIT_AU001"] == plan.assignments["UNIT_BU001"]
assert plan.assignments["UNIT_AU001"] != plan.assignments["GENERAL_AU001"]
assert plan.assignments["PAIRU001"] == plan.assignments["PAIRU002"]
assert "PAIR" in plan.exceptional_pairs
assert all(p.cbm <= settings.target_capacity + 1e-9 for p in plan.pallets)
assert all(p.weight <= settings.max_weight + 1e-9 for p in plan.pallets)
assert max(p.codes for p in plan.tendido_pallets) >= 2, "no hay límite artificial de códigos"

layout = transfer_layout_summary(records, settings, 1)
assert layout["strategy"] == "GLOBAL_BFD_V014"
assert layout["direct_codes"] == 1 and layout["direct_final_estimated"] == 3

container = ParsedContainer("TEST0000001", "test.xlsx", "Hoja1", records, [])
manifest = json.loads(build_pda_manifest(container, settings))
assert manifest["transfer_plan"]["assignments"] == plan.assignments
assert manifest["settings"]["max_weight"] == 1000.0
assert manifest["individual_sequence"] == {"prefix": "U", "start": 1, "consecutive": True, "padding": 3}

print("OK V0.14 Windows: plan global, unitarios, pares, peso y manifiesto sellado")
