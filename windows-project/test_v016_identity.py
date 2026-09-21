import json
from core.parser import CodeRecord, ParsedContainer
from core.optimizer import Settings, build_transfer_plan
from core.pda_exchange import build_pda_manifest
from core.strict_scan import parse_strict_scan


def check(condition, message):
    if not condition:
        raise AssertionError(message)


records = {
    "THZTEST": CodeRecord("THZTEST", 1000, 10.0, 0.01),
    "MOYU10042606100005": CodeRecord("MOYU10042606100005", 8, 0.8, 0.1),
    "ZGC1-20260525": CodeRecord("ZGC1-20260525", 6, 0.6, 0.1),
}

x = parse_strict_scan("THZTESTU1000", records)
check(x.valid and x.box_number == 1000 and x.normalized_barcode == "THZTESTU1000", "U1000")
x = parse_strict_scan("MOYU10042606100005-5", records)
check(x.valid and x.normalized_barcode == "MOYU10042606100005-5", "MOYU")
x = parse_strict_scan("ZGC1-20260525-0023", records)
check(x.valid and x.normalized_barcode == "ZGC1-20260525-0023", "ZGC no consecutivo")

settings = Settings()
plan = build_transfer_plan(list(records.values()), settings)
check("ZGC1-20260525" in plan.direct_codes, "identidad externa dinámica se trata de forma homogénea/dinámica")

container = ParsedContainer(
    container_id="TEST1234567",
    source_file="test.xlsx",
    sheet="Sheet1",
    records=list(records.values()),
    warnings=[],
)
payload = json.loads(build_pda_manifest(container, settings).decode("utf-8"))
check(payload["schema"] == "ilubox.pda.manifest.v3" and payload["version"] == 3, "manifest v3")
by_code = {r["code"]: r for r in payload["records"]}
check(by_code["MOYU10042606100005"]["identity_mode"] == "HYPHEN_SEQUENCE", "modo MOYU en manifest")
check(by_code["ZGC1-20260525"]["identity_mode"] == "HYPHEN_UNIQUE", "modo ZGC en manifest")
check("ZGC1-20260525" in payload["transfer_plan"]["direct_codes"], "ZGC dinámico en direct_codes")

print("OK Windows V0.16 identity")
