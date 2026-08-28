from streamlit.testing.v1 import AppTest
from pathlib import Path

from core.live import LiveUnload
from core.optimizer import Settings
from core.parser import CodeRecord, ParsedContainer
from core.pda_exchange import parse_pda_result


app = AppTest.from_file("app.py", default_timeout=15)
app.run()
assert not app.exception, app.exception
assert app.radio[0].value == "👷 Operador"

app.radio[0].set_value("🧑‍💼 Supervisor").run()
assert not app.exception, app.exception
assert any("Cargar Packing List" in item.value for item in app.subheader)

records = [
    CodeRecord("MJ260510161", 19, 1.9, 0.1),
    CodeRecord("MJ260561713", 6, 0.6, 0.1),
]
container = ParsedContainer(
    container_id="CXDU2223616",
    source_file="CXDU2223616_packing.xlsx",
    sheet="Sheet1",
    records=records,
    warnings=[],
)
key = "CXDU2223616|CXDU2223616_packing.xlsx|Sheet1"
live = LiveUnload(records, Settings(), initial_left=1, initial_right=1)
assert live.scan("MJ260510161U013")["ok"]
assert live.scan("MJ260561713U002")["ok"]

app.session_state.containers = [container]
app.session_state.active_container = 0
app.session_state.live_sessions = {key: live}
app.session_state.position_configs = {key: {"left": 1, "right": 1}}
app.session_state.wms_configs = {}
app.run()
assert not app.exception, app.exception
assert [tab.label for tab in app.tabs] == [
    "Resumen", "Plan de tarimas", "Seguimiento", "Exportación WMS"
]
assert any("Generar archivo exclusivo" in item.value for item in app.subheader)

continuous_records = [CodeRecord("CHICOA", 3, 0.60, 0.20), CodeRecord("CHICOB", 3, 0.60, 0.20)]
continuous = ParsedContainer(container_id="CONTINUO", source_file="prueba.xlsx", sheet="Sheet1", records=continuous_records, warnings=[])
continuous_key = "CONTINUO|prueba.xlsx|Sheet1"
native = parse_pda_result(Path("tests/fixtures/v010/v3-verified.json").read_bytes(), {r.code: r for r in continuous_records}, "CONTINUO")
assert native.ready
app.session_state.containers = [continuous]
app.session_state.active_container = 0
app.session_state.pda_results = {continuous_key: native}
app.radio[0].set_value("👷 Operador").run()
assert not app.exception, app.exception
assert any("Tarimas PDA" in title.value for title in app.title)
assert not app.get("download_button"), "La consulta del operador no incluye exportaciones"
assert any(field.label == "Consultar tarima" for field in app.selectbox)
app.radio[0].set_value("🧑‍💼 Supervisor").run()
assert not app.exception, app.exception
assert app.get("download_button"), "Las exportaciones permanecen en Supervisor"

print("OK Streamlit V0.10: consulta de tarimas para operador, exportaciones solo en Supervisor")
