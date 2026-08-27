import html
from pathlib import Path
import re
import streamlit as st

from core.bundles import unpack_upload
from core.parser import parse_xlsx_bytes
from core.optimizer import Settings, build_static_plan, plan_summary
from core.live import LiveUnload
from core.exporters import rows_to_csv
from core.storage import (
    delete_live_session,
    load_live_session,
    load_wms_config,
    save_live_session,
    save_wms_config,
)
from core.pda_exchange import build_pda_manifest, parse_pda_result
from core.wms_putaway import (
    WMS_HEADERS,
    build_putaway_rows,
    export_official_putaway_xlsx,
    summarize_pallets,
)


APP_DIR = Path(__file__).resolve().parent
OFFICIAL_TEMPLATE = APP_DIR / "assets" / "templates" / "Plantilla_oficial_WMS_PutawayCrossDockImport.xlsx"


st.set_page_config(page_title="Ilubox WMS Windows V0.8", page_icon="📦", layout="wide")

st.markdown(
    """
    <style>
    .block-container {padding-top: 5rem; padding-bottom: 2rem; max-width: 1500px;}
    div[data-testid="stMetric"] {background:#f7f7f8; border:1px solid #e5e7eb; padding:12px; border-radius:12px;}
    .scan-card {border-radius:18px; padding:24px 18px; text-align:center; margin:10px 0 18px 0; border:3px solid #2563eb; background:#f8fbff;}
    .scan-card .position {font-size:76px; font-weight:900; line-height:1; letter-spacing:2px;}
    .scan-card .status {font-size:26px; font-weight:800; margin-top:10px;}
    .scan-card .detail {font-size:18px; margin-top:8px; color:#374151;}
    .ok-card {border-color:#16a34a; background:#f0fdf4;}
    .complete-card {border-color:#16a34a; background:#ecfdf5;}
    .duplicate-card {border-color:#dc2626; background:#fef2f2;}
    .error-card {border-color:#ea580c; background:#fff7ed;}
    .neutral-card {border-color:#2563eb; background:#eff6ff;}
    .big-scan-label {font-size:22px; font-weight:800; margin-bottom:4px;}
    div[data-testid="stTextInput"] input {font-size:24px; min-height:54px;}
    div[data-testid="stFormSubmitButton"] button {min-height:52px; font-size:20px; font-weight:800; width:100%;}

    .map-wrap {overflow-x:auto; padding-bottom:4px;}
    .position-grid {display:grid; grid-template-columns:repeat(10, minmax(105px, 1fr)); gap:7px; min-width:1120px;}
    .pos-card {border:2px solid #d1d5db; border-radius:12px; min-height:105px; padding:8px; text-align:center; background:#f9fafb;}
    .pos-card .pos-label {font-size:22px; font-weight:900; line-height:1.1;}
    .pos-card .pos-state {font-size:11px; font-weight:800; margin:4px 0;}
    .pos-card .pos-title {font-size:11px; font-weight:700; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;}
    .pos-card .pos-detail {font-size:10px; color:#4b5563; line-height:1.25; margin-top:4px; max-height:28px; overflow:hidden;}
    .pos-free {border-color:#9ca3af; background:#f9fafb;}
    .pos-running {border-color:#2563eb; background:#eff6ff;}
    .pos-near {border-color:#f59e0b; background:#fffbeb;}
    .pos-complete {border-color:#16a34a; background:#f0fdf4;}
    .pos-disabled {border-color:#d1d5db; background:#e5e7eb; color:#6b7280; opacity:.76;}
    .pos-highlight {box-shadow:0 0 0 4px rgba(37,99,235,.28); transform:translateY(-2px);}
    .map-side-title {font-size:18px; font-weight:900; margin:10px 0 5px;}
    .map-note {font-size:13px; color:#6b7280; margin-bottom:6px;}
    </style>
    """,
    unsafe_allow_html=True,
)


def default_settings():
    return Settings(
        physical_capacity=2.16,
        target_capacity=1.94,
        large_ratio=0.70,
        medium_high_ratio=0.45,
        medium_ratio=0.25,
        max_codes_unit=20,
        fixed_positions=20,
    )


if "settings" not in st.session_state:
    st.session_state.settings = default_settings()
if "containers" not in st.session_state:
    st.session_state.containers = []
if "active_container" not in st.session_state:
    st.session_state.active_container = None
if "live_sessions" not in st.session_state:
    st.session_state.live_sessions = {}
if "last_scan_result" not in st.session_state:
    st.session_state.last_scan_result = None
if "position_configs" not in st.session_state:
    st.session_state.position_configs = {}
if "wms_configs" not in st.session_state:
    st.session_state.wms_configs = {}
if "pda_results" not in st.session_state:
    st.session_state.pda_results = {}


def container_key(container):
    sheet = getattr(container, "sheet", None) or getattr(container, "source_sheet", "")
    source_file = getattr(container, "source_file", "")
    container_id = getattr(container, "container_id", "SIN_CONTENEDOR")
    return f"{container_id}|{source_file}|{sheet}"


def get_live(container, reset=False, initial_left=None, initial_right=None):
    key = container_key(container)
    saved = st.session_state.position_configs.get(key, {"left": 5, "right": 5})
    left = saved["left"] if initial_left is None else int(initial_left)
    right = saved["right"] if initial_right is None else int(initial_right)
    if reset:
        st.session_state.position_configs[key] = {"left": left, "right": right}
        delete_live_session(key)
    if reset or key not in st.session_state.live_sessions:
        restored = None if reset else load_live_session(
            key, container.records, st.session_state.settings
        )
        st.session_state.live_sessions[key] = restored or LiveUnload(
            container.records, st.session_state.settings,
            initial_left=left, initial_right=right,
        )
        save_live_session(key, st.session_state.live_sessions[key])
    return st.session_state.live_sessions[key]


def persist_live(container, live):
    save_live_session(container_key(container), live)


def get_wms_config(container):
    key = container_key(container)
    if key not in st.session_state.wms_configs:
        saved = load_wms_config(key)
        st.session_state.wms_configs[key] = {
            "putaway_order": saved.get("putaway_order", ""),
            "default_location": saved.get("default_location", ""),
            "locations": saved.get("locations", {}),
        }
    return st.session_state.wms_configs[key]


def parse_uploads(uploads):
    parsed, errors = [], []
    for up in uploads or []:
        try:
            for filename, data in unpack_upload(up.name, up.getvalue()):
                parsed.extend(parse_xlsx_bytes(data, filename))
        except Exception as exc:
            errors.append(f"{up.name}: {exc}")
    return parsed, errors


def android_manifest_json(container, settings):
    return build_pda_manifest(container, settings)


def render_scan_result(result):
    if not result:
        st.markdown(
            '<div class="scan-card neutral-card"><div class="status">LISTO PARA ESCANEAR</div>'
            '<div class="detail">Escanea una caja para conocer su posición.</div></div>',
            unsafe_allow_html=True,
        )
        return

    status = result.get("status", "")
    if result.get("ok"):
        pos = html.escape(str(result.get("position", "")))
        code = html.escape(str(result.get("code", "")))
        received = result.get("received", "")
        expected = result.get("expected", "")
        if status == "TARIMA COMPLETA":
            st.markdown(
                f'<div class="scan-card complete-card"><div class="position">{pos}</div>'
                '<div class="status">🟢 TARIMA COMPLETA · RETIRAR</div>'
                f'<div class="detail">{code} · {received}/{expected} cajas. Después de retirarla usa POSICIÓN LISTA.</div></div>',
                unsafe_allow_html=True,
            )
        else:
            line = "CÓDIGO COMPLETO" if status == "CÓDIGO COMPLETO" else "CAJA REGISTRADA"
            st.markdown(
                f'<div class="scan-card ok-card"><div class="position">{pos}</div>'
                f'<div class="status">✅ {line}</div><div class="detail">{code} · {received}/{expected} cajas</div></div>',
                unsafe_allow_html=True,
            )
    elif status == "DUPLICADA":
        pos = html.escape(str(result.get("position") or "—"))
        first_time = result.get("first_scan_time", "")
        when = f" · Primera lectura {html.escape(str(first_time))}" if first_time else ""
        scan = html.escape(str(result.get("scan", "")))
        st.markdown(
            f'<div class="scan-card duplicate-card"><div class="position">{pos}</div>'
            '<div class="status">⛔ CAJA DUPLICADA</div>'
            f'<div class="detail">{scan} · Ya registrada en {pos}{when}. NO aumenta el conteo.</div></div>',
            unsafe_allow_html=True,
        )
    elif status == "POSICIÓN PENDIENTE":
        pos = html.escape(str(result.get("position") or "—"))
        st.markdown(
            f'<div class="scan-card error-card"><div class="position">{pos}</div>'
            '<div class="status">⚠️ POSICIÓN OCUPADA POR TARIMA COMPLETA</div>'
            '<div class="detail">Retira la tarima y pulsa POSICIÓN LISTA antes de continuar con ese código.</div></div>',
            unsafe_allow_html=True,
        )
    else:
        msg = html.escape(str(result.get("message", "No se pudo registrar la caja")))
        st.markdown(
            '<div class="scan-card error-card"><div class="status">⚠️ REVISAR</div>'
            f'<div class="detail">{msg}</div></div>',
            unsafe_allow_html=True,
        )


def _card_class(state, highlight=False):
    base = {
        "NO HABILITADA": "pos-disabled",
        "LIBRE": "pos-free",
        "EN PROCESO": "pos-running",
        "PRÓXIMA": "pos-near",
        "COMPLETA": "pos-complete",
    }.get(state, "pos-free")
    return base + (" pos-highlight" if highlight else "")


def render_position_map(live, highlight=None):
    cards = live.position_cards()
    highlight = (highlight or "").upper()
    st.markdown("### Mapa de descarga")
    st.markdown('<div class="map-note">01 = posición más cercana al contenedor · 10 = más lejana</div>', unsafe_allow_html=True)
    for side, side_name in (("I", "IZQUIERDA"), ("D", "DERECHA")):
        side_cards = [c for c in cards if c["Lado"] == side]
        body = []
        for c in side_cards:
            cls = _card_class(c["Estado"], c["Posición"] == highlight)
            title = html.escape(str(c["Título"]))
            detail = html.escape(str(c["Detalle"]))
            body.append(
                f'<div class="pos-card {cls}">'
                f'<div class="pos-label">{c["Posición"]}</div>'
                f'<div class="pos-state">{html.escape(c["Estado"])}</div>'
                f'<div class="pos-title" title="{title}">{title}</div>'
                f'<div class="pos-detail" title="{detail}">{detail}</div>'
                '</div>'
            )
        st.markdown(f'<div class="map-side-title">{side_name}</div><div class="map-wrap"><div class="position-grid">{"".join(body)}</div></div>', unsafe_allow_html=True)


def render_operator_ready_buttons(live, container):
    pending = live.pending_removal_positions()
    if not pending:
        return
    st.markdown("### 🟢 Tarimas completas por retirar")
    st.caption("Retira físicamente la tarima, deja una tarima vacía si corresponde y después marca la posición como lista.")
    for row_start in range(0, len(pending), 4):
        cols = st.columns(4)
        for col, p in zip(cols, pending[row_start:row_start + 4]):
            if col.button(f"✓ {p.label} · POSICIÓN LISTA", key=f"ready_{p.label}", width="stretch", type="primary"):
                result = live.mark_position_ready(p.label)
                persist_live(container, live)
                st.session_state.last_scan_result = None
                if result.get("ok"):
                    st.toast(result.get("message", "Posición lista"), icon="✅")
                else:
                    st.error(result.get("message", "No se pudo liberar la posición"))
                st.rerun()


st.markdown("### Modo de uso")
mode = st.radio(
    "Selecciona el modo",
    ["👷 Operador", "🧑‍💼 Supervisor"],
    horizontal=True,
    label_visibility="collapsed",
    key="main_mode_selector",
)
st.divider()


# ==========================
# MODO OPERADOR
# ==========================
if mode == "👷 Operador":
    if st.session_state.active_container is None or not st.session_state.containers:
        st.title("📦 Descarga de Contenedores")
        st.warning("No hay un contenedor preparado. Entra a **Supervisor**, carga el Packing List y pulsa **Activar para descarga**.")
        st.stop()

    idx = st.session_state.active_container
    if idx >= len(st.session_state.containers):
        st.session_state.active_container = None
        st.warning("El contenedor activo ya no está disponible. Prepáralo nuevamente en Supervisor.")
        st.stop()

    container = st.session_state.containers[idx]
    live = get_live(container)
    received, expected = live.progress()
    pressure = live.position_pressure()

    st.title(f"📦 {container.container_id}")
    st.caption("Escanea la caja y colócala en la posición indicada. I/D se leen mirando desde el andén hacia el interior del contenedor. La tarima incompleta no se mueve.")

    m1, m2, m3 = st.columns(3)
    m1.metric("Escaneadas", f"{received} / {expected}")
    m2.metric("Posiciones", f"{pressure['occupied']} / {pressure['enabled']}")
    m3.metric("Máximo usado", pressure["peak"])

    st.markdown('<div class="big-scan-label">ESCANEAR CAJA</div>', unsafe_allow_html=True)
    with st.form("operator_scan", clear_on_submit=True):
        raw_scan = st.text_input(
            "Código de caja",
            placeholder="Escanea el código de barras",
            label_visibility="collapsed",
            key="operator_scan_input",
        )
        submitted = st.form_submit_button("REGISTRAR", type="primary")

    if submitted and raw_scan.strip():
        st.session_state.last_scan_result = live.scan(raw_scan)
        persist_live(container, live)

    render_scan_result(st.session_state.last_scan_result)

    # El operador confirma la liberación física.
    render_operator_ready_buttons(live, container)

    highlight = ""
    if st.session_state.last_scan_result:
        highlight = st.session_state.last_scan_result.get("position", "")
    render_position_map(live, highlight=highlight)

    st.subheader("Últimos 5 escaneos")
    recent = live.recent_scans(5)
    if recent:
        compact = [
            {"Hora": r["Hora"], "Caja": r["Escaneo"], "Posición": r["Posición"], "Estado": r["Estado"]}
            for r in recent
        ]
        st.dataframe(compact, width="stretch", hide_index=True)
    else:
        st.caption("Todavía no hay escaneos.")

    if pressure["level"] in ("ALTA", "SATURADA"):
        extra = pressure["available_to_enable"]
        st.warning(
            f"Posiciones: {pressure['occupied']}/{pressure['enabled']} · presión {pressure['level']}. "
            f"Quedan {extra} posiciones posibles por habilitar. Avise al supervisor si hace falta más espacio."
        )


# ==========================
# MODO SUPERVISOR
# ==========================
else:
    st.title("🧑‍💼 Supervisor · Ilubox WMS V0.8")
    st.caption(
        "La aplicación organiza la descarga, conserva el avance localmente y prepara una copia validada "
        "de la plantilla oficial. No se conecta ni ejecuta movimientos dentro del WMS."
    )

    with st.expander("⚙️ Configuración de planificación", expanded=False):
        s = st.session_state.settings
        c1, c2 = st.columns(2)
        physical = c1.number_input("Capacidad física tarima (m³)", 1.0, 3.0, float(s.physical_capacity), 0.01)
        target = c2.number_input("CBM objetivo", 1.0, float(physical), min(float(s.target_capacity), float(physical)), 0.01)
        c4, c5, c6, c7 = st.columns(4)
        max_unit = c4.number_input("Máx. unitarios/tarima", 5, 50, int(s.max_codes_unit), 1)
        large = c5.slider("Grande ≥ %", 50, 95, int(s.large_ratio * 100)) / 100
        medhi = c6.slider("Medio grande ≥ %", 30, 70, int(s.medium_high_ratio * 100)) / 100
        med = c7.slider("Medio ≥ %", 10, 50, int(s.medium_ratio * 100)) / 100
        if st.button("Guardar configuración"):
            st.session_state.settings = Settings(
                physical_capacity=float(physical), target_capacity=float(target),
                large_ratio=large, medium_high_ratio=medhi, medium_ratio=med,
                max_codes_unit=int(max_unit), fixed_positions=20,
            )
            st.success("Configuración guardada. El máximo operativo permanece en 20 posiciones: 10 por lado.")

    st.subheader("1. Cargar Packing List")
    uploads = st.file_uploader(
        "XLSX, ZIP o RAR",
        type=["xlsx", "zip", "rar"],
        accept_multiple_files=True,
        label_visibility="collapsed",
    )
    if st.button("Procesar archivos", type="primary", disabled=not bool(uploads)):
        parsed, errors = parse_uploads(uploads)
        for error in errors:
            st.error(error)
        if parsed:
            st.session_state.containers = parsed
            st.session_state.active_container = None
            st.session_state.live_sessions = {}
            st.session_state.last_scan_result = None
            st.success(f"{len(parsed)} Packing List/hojas reconocidos.")

    if not st.session_state.containers:
        st.info("Carga un Packing List para preparar una descarga.")
        st.stop()

    options = [f"{i+1:02d} · {p.container_id} · {p.source_file}" for i, p in enumerate(st.session_state.containers)]
    selected = st.selectbox("Contenedor", options)
    idx = options.index(selected)
    container = st.session_state.containers[idx]
    records = container.records
    pallets = build_static_plan(records, st.session_state.settings)
    summary = plan_summary(records, pallets, st.session_state.settings)

    st.subheader("2. Preparar posiciones físicas")
    st.caption("I01/D01 son las más cercanas al contenedor. I/D se definen mirando desde el andén hacia el interior del contenedor. Habilita solo el espacio que realmente esté disponible.")
    st.download_button(
        "📱 Descargar archivo para PDA (.json)",
        android_manifest_json(container, st.session_state.settings),
        file_name=f"PDA_{container.container_id}.json",
        mime="application/json",
        width="stretch",
        help="Este archivo se importa en la aplicación Android de la PDA para realizar la prueba offline.",
    )
    k = container_key(container).replace("|", "_")
    p1, p2, p3 = st.columns([1, 1, 1.2])
    initial_left = p1.number_input("Lado izquierdo", min_value=0, max_value=10, value=5, step=1, key=f"left_{k}")
    initial_right = p2.number_input("Lado derecho", min_value=0, max_value=10, value=5, step=1, key=f"right_{k}")
    p3.metric("Total inicial", f"{int(initial_left) + int(initial_right)} / 20")

    a1, a2 = st.columns([2, 1])
    with a1:
        if st.button(
            "▶️ Activar o reanudar descarga",
            type="primary",
            width="stretch",
            disabled=(int(initial_left) + int(initial_right) == 0),
        ):
            st.session_state.active_container = idx
            live = get_live(container, reset=False, initial_left=int(initial_left), initial_right=int(initial_right))
            persist_live(container, live)
            st.session_state.last_scan_result = None
            resumed, total = live.progress()
            st.success(
                f"{container.container_id} activo con {int(initial_left)} posiciones a la izquierda y "
                f"{int(initial_right)} a la derecha. Avance recuperado: {resumed}/{total}."
            )
    with a2:
        confirm_reset = st.checkbox("Confirmar reinicio total", key=f"confirm_reset_{k}")
        if st.button("Reiniciar seguimiento", width="stretch", disabled=not confirm_reset):
            live = get_live(container, reset=True, initial_left=int(initial_left), initial_right=int(initial_right))
            persist_live(container, live)
            st.session_state.last_scan_result = None
            st.session_state.pda_results.pop(container_key(container), None)
            st.success("Seguimiento reiniciado con la configuración indicada.")

    active_this = st.session_state.active_container == idx
    if active_this:
        st.success("🟢 Este es el contenedor activo para el operador.")

    for warning in container.warnings:
        st.warning(warning)

    tab1, tab2, tab3, tab4 = st.tabs(
        ["Resumen", "Plan de tarimas", "Seguimiento", "Plantilla WMS"]
    )

    with tab1:
        cols = st.columns(6)
        metrics = [
            ("Códigos", summary["códigos"]),
            ("Cajas", summary["cajas"]),
            ("CBM", summary["cbm"]),
            ("Unitarios", summary["códigos_unitarios"]),
            ("Tarimas estimadas", summary["tarimas_estimadas"]),
            ("Ocupación promedio", f"{summary['ocupación_promedio_%']}%"),
        ]
        for col, (label, value) in zip(cols, metrics):
            col.metric(label, value)
        st.dataframe([
            {
                "Código": r.code,
                "Cajas": r.boxes,
                "CBM": round(r.cbm, 3),
                "CBM/caja": round(r.cbm_per_box, 4),
                "Peso/caja": r.weight_per_box,
                "Bodega": r.warehouse,
                "Descripción": r.description,
            }
            for r in records
        ], width="stretch", hide_index=True)

    with tab2:
        st.caption("G = exclusiva/grande · M = mixta controlada · U = solo códigos de una caja.")
        plan_rows = [p.as_dict() for p in pallets]
        st.dataframe(plan_rows, width="stretch", hide_index=True)
        st.download_button(
            "Descargar plan CSV",
            rows_to_csv(plan_rows),
            file_name=f"plan_tarimas_{container.container_id}.csv",
            mime="text/csv",
        )

    with tab3:
        live = get_live(container, initial_left=int(initial_left), initial_right=int(initial_right))
        received, expected = live.progress()
        pressure = live.position_pressure()
        c1, c2, c3, c4 = st.columns(4)
        c1.metric("Cajas", f"{received}/{expected}")
        c2.metric("Posiciones", f"{pressure['occupied']}/{pressure['enabled']}")
        c3.metric("Máximo simultáneo", pressure["peak"])
        c4.metric("Presión", pressure["level"])

        st.markdown("#### Habilitar más espacio")
        h1, h2, h3 = st.columns([1, 1, 2])
        if h1.button(
            f"+ Izquierda ({pressure['left_enabled']}/10)",
            width="stretch",
            disabled=pressure["left_enabled"] >= 10,
        ):
            label = live.enable_next("I")
            if label:
                persist_live(container, live)
                st.toast(f"{label} habilitada", icon="✅")
            st.rerun()
        if h2.button(
            f"+ Derecha ({pressure['right_enabled']}/10)",
            width="stretch",
            disabled=pressure["right_enabled"] >= 10,
        ):
            label = live.enable_next("D")
            if label:
                persist_live(container, live)
                st.toast(f"{label} habilitada", icon="✅")
            st.rerun()
        h3.info(
            f"Habilitadas: {pressure['enabled']}/20 · Libres: {pressure['free']} · "
            f"Completas pendientes de retiro: {pressure['pending_removal']}"
        )

        render_position_map(live)

        st.subheader("Detalle de posiciones")
        st.dataframe(live.snapshot(), width="stretch", hide_index=True)

        left, right = st.columns(2)
        with left:
            st.subheader("Últimos eventos")
            st.dataframe(live.events[-30:][::-1], width="stretch", hide_index=True)
        with right:
            st.subheader("Tarimas retiradas")
            st.dataframe(live.closed_pallets[-30:][::-1], width="stretch", hide_index=True)

        dups = sum(1 for e in live.events if e["Estado"] == "DUPLICADA")
        not_found = sum(1 for e in live.events if e["Estado"] == "NO ENCONTRADA")
        surplus = sum(1 for e in live.events if e["Estado"] == "SOBRANTE")
        e1, e2, e3 = st.columns(3)
        e1.metric("Duplicados", dups)
        e2.metric("No encontrados", not_found)
        e3.metric("Sobrantes", surplus)

        dl1, dl2 = st.columns(2)
        dl1.download_button(
            "Descargar cajas aceptadas",
            rows_to_csv(live.history),
            file_name=f"descarga_{container.container_id}.csv",
            mime="text/csv",
            width="stretch",
        )
        dl2.download_button(
            "Descargar auditoría completa",
            rows_to_csv(live.events),
            file_name=f"auditoria_descarga_{container.container_id}.csv",
            mime="text/csv",
            width="stretch",
        )

    with tab4:
        live = get_live(container, initial_left=int(initial_left), initial_right=int(initial_right))
        local_received, expected = live.progress()
        config = get_wms_config(container)
        result_key = container_key(container)

        st.subheader("4. Preparar plantilla oficial de almacenamiento")
        st.info(
            "Aquí se genera el archivo para **Importación inteligente**. La aplicación no carga el "
            "archivo ni confirma movimientos dentro del WMS. I01/D01 son posiciones físicas; la "
            "ubicación WMS se captura por separado."
        )

        st.markdown("#### Resultado de la PDA")
        st.caption(
            "La PDA valida cada código individual y exporta un JSON al terminar. Al importarlo, "
            "Windows verifica contenedor, Packing List, rangos y duplicados antes de usarlo."
        )
        pda_upload = st.file_uploader(
            "Resultado PDA (.json)",
            type=["json"],
            key=f"pda_result_upload_{k}",
            label_visibility="collapsed",
        )
        if st.button(
            "Importar resultado PDA",
            disabled=pda_upload is None,
            key=f"import_pda_result_{k}",
        ):
            parsed_result = parse_pda_result(
                pda_upload.getvalue(),
                {record.code.upper(): record for record in container.records},
                container.container_id,
            )
            for warning in parsed_result.warnings:
                st.warning(warning)
            for error in parsed_result.errors:
                st.error(error)
            if parsed_result.ready:
                st.session_state.pda_results[result_key] = parsed_result
                st.success(f"Resultado PDA validado: {len(parsed_result.events)} cajas únicas.")

        pda_result = st.session_state.pda_results.get(result_key)
        source_events = live.history
        source_received = local_received
        source_label = "Escaneo local Windows"
        if pda_result and pda_result.ready:
            src1, src2 = st.columns([3, 1])
            src1.success(
                f"PDA: {len(pda_result.events)}/{expected} cajas · motor "
                f"{pda_result.engine_version or 'sin versión'}"
            )
            if src2.button("Quitar resultado PDA", key=f"remove_pda_{k}", width="stretch"):
                st.session_state.pda_results.pop(result_key, None)
                st.rerun()
            source_label = st.radio(
                "Fuente para la plantilla WMS",
                ["Resultado validado de PDA", "Escaneo local Windows"],
                horizontal=True,
                key=f"wms_source_{k}",
            )
            if source_label == "Resultado validado de PDA":
                source_events = pda_result.events
                source_received = len(source_events)

        st.caption(f"Fuente activa: **{source_label}** · {source_received}/{expected} cajas")

        f1, f2 = st.columns(2)
        putaway_order = f1.text_input(
            "Orden Putaway / 上架单号",
            value=config.get("putaway_order", ""),
            placeholder="Ejemplo: PAS3902608080RT",
            key=f"putaway_order_{k}",
        )
        default_location = f2.text_input(
            "Ubicación WMS predeterminada",
            value=config.get("default_location", ""),
            placeholder="Ejemplo: 1-1-01",
            help="Se aplicará a las tarimas que no tengan una ubicación específica.",
            key=f"default_wms_location_{k}",
        )

        pallets_for_wms = summarize_pallets(source_events)
        location_by_pallet = {}
        if pallets_for_wms:
            st.markdown("#### Asignación por tarima")
            st.caption(
                "Cada identificador de tarima es estable aunque una posición física se reutilice. "
                "Deja la última columna vacía para usar la ubicación predeterminada; complétala "
                "solamente cuando una tarima vaya a otra ubicación."
            )
            mapping_rows = []
            saved_locations = config.get("locations", {})
            for pallet in pallets_for_wms:
                mapping_rows.append({
                    **pallet,
                    "Ubicación WMS específica": saved_locations.get(pallet["Tarima"], ""),
                })
            edited_mapping = st.data_editor(
                mapping_rows,
                width="stretch",
                hide_index=True,
                disabled=["Tarima", "Posición física", "Cajas", "Primera caja"],
                column_config={
                    "Tarima": st.column_config.TextColumn("ID de tarima"),
                    "Posición física": st.column_config.TextColumn("Posición física"),
                    "Cajas": st.column_config.NumberColumn("Cajas", format="%d"),
                    "Primera caja": st.column_config.TextColumn("Primera caja"),
                    "Ubicación WMS específica": st.column_config.TextColumn(
                        "Ubicación específica (opcional)"
                    ),
                },
                key=f"wms_mapping_{k}_{source_label}_{len(source_events)}_{len(pallets_for_wms)}",
            )
            edited_rows = (
                edited_mapping.to_dict("records")
                if hasattr(edited_mapping, "to_dict")
                else list(edited_mapping)
            )
            location_by_pallet = {
                str(row.get("Tarima", "")): str(
                    row.get("Ubicación WMS específica", "")
                ).strip()
                for row in edited_rows
                if row.get("Tarima") and str(
                    row.get("Ubicación WMS específica", "")
                ).strip()
            }
        else:
            st.warning("Todavía no hay cajas aceptadas. Escanea las cajas antes de generar la plantilla.")

        current_config = {
            "putaway_order": putaway_order.strip(),
            "default_location": default_location.strip(),
            "locations": location_by_pallet,
        }
        st.session_state.wms_configs[container_key(container)] = current_config
        save_wms_config(container_key(container), current_config)

        is_partial = source_received < expected
        partial_ack = True
        if is_partial:
            partial_ack = st.checkbox(
                f"Confirmo que esta es una carga parcial ({source_received} de {expected} cajas).",
                key=f"partial_ack_{k}",
            )

        records_by_code = {record.code.upper(): record for record in container.records}
        build_result = build_putaway_rows(
            source_events,
            records_by_code,
            putaway_order,
            default_location=default_location,
            location_by_pallet=location_by_pallet,
            received=source_received,
            expected=expected,
            allow_partial=partial_ack,
        )

        for warning in build_result.warnings:
            st.warning(warning)
        for error in build_result.errors:
            st.error(error)

        if build_result.rows:
            st.markdown("#### Vista previa exacta")
            st.dataframe(
                build_result.rows,
                width="stretch",
                hide_index=True,
                column_order=list(WMS_HEADERS),
            )
            v1, v2, v3 = st.columns(3)
            v1.metric("Filas exportables", len(build_result.rows))
            v2.metric("Tarimas", len(pallets_for_wms))
            v3.metric("Cantidad por fila", 1)

        review_ack = st.checkbox(
            "Revisé la orden Putaway, los códigos individuales y las ubicaciones WMS.",
            key=f"review_ack_{k}",
        )
        workbook_bytes = None
        if build_result.ready and review_ack:
            try:
                workbook_bytes = export_official_putaway_xlsx(
                    build_result.rows, OFFICIAL_TEMPLATE.read_bytes()
                )
            except Exception as exc:
                st.error(f"No se pudo generar la copia de la plantilla oficial: {exc}")

        safe_order = re.sub(r"[^A-Za-z0-9_-]+", "_", putaway_order.strip()) or "SIN_ORDEN"
        st.download_button(
            "⬇️ Descargar plantilla oficial WMS",
            data=workbook_bytes or b"",
            file_name=f"WMS_Putaway_{container.container_id}_{safe_order}.xlsx",
            mime="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            disabled=workbook_bytes is None,
            type="primary",
            width="stretch",
        )
        st.caption(
            "Cuando el permiso esté activo: cargue este archivo, ejecute primero **Comenzar a analizar**, "
            "revise todas las filas y confirme el movimiento solamente si el WMS no reporta errores."
        )
