"""V0.15 separa el plan teórico de la evidencia real importada desde la PDA."""
from copy import deepcopy
from pathlib import Path
import json

from core.parser import CodeRecord
from core.pda_exchange import parse_pda_result


fixture = Path("tests/fixtures/v011/v4-verified.json")
base = json.loads(fixture.read_text(encoding="utf-8"))
records = {"CHICOA": CodeRecord("CHICOA", 3, .6, .2), "CHICOB": CodeRecord("CHICOB", 3, .6, .2)}

valid = deepcopy(base)
valid["engine_version"] = "0.15-operacion-simplificada-q9"
valid["plan_export_policy"] = "ACTUAL_SCANNED_ONLY"
valid["overflow_policy"] = "TRANSFER_WHEN_NO_FOOT_POSITION"
result = parse_pda_result(json.dumps(valid).encode(), records, "CONTINUO")
assert result.ready, result.errors

missing_policy = deepcopy(valid)
missing_policy.pop("plan_export_policy")
assert not parse_pda_result(json.dumps(missing_policy).encode(), records, "CONTINUO").ready

theoretical = deepcopy(valid)
theoretical["pallets"].append({
    "id": "T-99", "formation": "TENDIDO", "physical_position": "", "wms_temporary_location": "",
    "status": "PREPARAR", "expected": 4, "original_expected": 4, "scanned": 0, "in_final": 0,
    "validated": False, "retired": False, "closure_reason": "", "verification_method": "",
    "verified_by": "", "verified_at": "", "verified_boxes": 0,
})
invalid = parse_pda_result(json.dumps(theoretical).encode(), records, "CONTINUO")
assert not invalid.ready and any("teórica" in error for error in invalid.errors), invalid.errors

print("OK V0.15 Windows: política real, campos obligatorios y tarimas teóricas rechazadas")
