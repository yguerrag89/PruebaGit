from streamlit.testing.v1 import AppTest

from core.live import LiveUnload
from core.optimizer import Settings
from core.parser import CodeRecord, ParsedContainer


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
    "Resumen", "Plan de tarimas", "Seguimiento", "Plantilla WMS"
]
assert any("Preparar plantilla oficial" in item.value for item in app.subheader)

print("OK Streamlit: operador, supervisor y pestaña Plantilla WMS")
