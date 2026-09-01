from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Mapping
import json
import re

from .strict_scan import canonical_scan, parse_strict_scan, record_signature
from .wms_location import valid_wms_temporary
from .optimizer import build_transfer_plan


PDA_MANIFEST_SCHEMA = "ilubox.pda.manifest.v2"
PDA_RESULT_SCHEMA = "ilubox.pda.result.v4"
V3_PDA_RESULT_SCHEMA = "ilubox.pda.result.v3"
V2_PDA_RESULT_SCHEMA = "ilubox.pda.result.v2"
LEGACY_PDA_RESULT_SCHEMA = "ilubox.pda.result.v1"


@dataclass
class PdaImportResult:
    container_id: str = ""
    events: list[dict] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    exported_at: str = ""
    engine_version: str = ""
    schema_version: int = 0
    pallets: list[dict] = field(default_factory=list)
    transfers: list[dict] = field(default_factory=list)

    @property
    def eligible_events(self) -> list[dict]:
        return [event for event in self.events if event.get("Elegible WMS") is True]

    @property
    def ready(self) -> bool:
        return bool(self.events) and not self.errors


def build_pda_manifest(container, settings) -> bytes:
    records_by_code = {record.code.upper(): record for record in container.records}
    transfer_plan = build_transfer_plan(container.records, settings)
    payload = {
        "schema": PDA_MANIFEST_SCHEMA,
        "version": 2,
        "container_id": container.container_id,
        "source_file": container.source_file,
        "source_sheet": getattr(container, "sheet", ""),
        "record_signature": record_signature(records_by_code),
        "strict_individual_barcodes": True,
        "individual_sequence": {"prefix": "U", "start": 1, "consecutive": True, "padding": 3},
        "operation_policy": {
            "profile": "SIMPLE_Q9_V015",
            "overflow": "TRANSFER_WHEN_NO_FOOT_POSITION",
            "result_export": "ACTUAL_SCANNED_ONLY",
            "transfer_confirmation": "PHYSICAL_TR_CHANGE_ONLY",
        },
        "settings": {
            "physical_capacity": settings.physical_capacity,
            "target_capacity": settings.target_capacity,
            "max_weight": settings.max_weight,
            "desirable_min_weight": settings.desirable_min_weight,
            "heavy_low_threshold": settings.heavy_low_threshold,
            "large_ratio": settings.large_ratio,
            "medium_high_ratio": settings.medium_high_ratio,
            "medium_ratio": settings.medium_ratio,
            "max_codes_unit": settings.max_codes_unit,
            "max_codes_small": settings.max_codes_small,
            "max_codes_medium": settings.max_codes_medium,
            "max_codes_medium_high": settings.max_codes_medium_high,
        },
        "transfer_plan": {
            "strategy": "GLOBAL_BFD_V014",
            "assignments": transfer_plan.assignments,
            "direct_codes": sorted(transfer_plan.direct_codes),
            "estimated_direct_pallets": len(transfer_plan.direct_pallets),
            "unitary_pallets": [p.pallet_id for p in transfer_plan.tendido_pallets
                                if "UNITARIOS" in p.pallet_type],
            "exceptional_pair_codes": sorted(transfer_plan.exceptional_pairs),
            "rack_suggestions": {p.pallet_id: p.rack_class for p in transfer_plan.tendido_pallets},
        },
        "records": [
            {
                "code": record.code,
                "boxes": record.boxes,
                "cbm": round(record.cbm, 6),
                "cbm_per_box": round(record.cbm_per_box, 9),
                "weight_per_box": record.weight_per_box,
                "description": record.description,
                "warehouse": record.warehouse,
            }
            for record in container.records
        ],
    }
    return json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")


def parse_pda_result(
    data: bytes,
    records_by_code: Mapping[str, object],
    expected_container_id: str,
) -> PdaImportResult:
    result = PdaImportResult()
    try:
        payload = json.loads(data.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        result.errors.append(f"El resultado PDA no es un JSON válido: {exc}")
        return result

    if not isinstance(payload, dict):
        result.errors.append("El resultado PDA debe contener un objeto JSON.")
        return result
    supplied_schema = payload.get("schema")
    is_v4 = supplied_schema == PDA_RESULT_SCHEMA and type(payload.get("version")) is int and payload.get("version") == 4
    is_v3 = supplied_schema == V3_PDA_RESULT_SCHEMA and type(payload.get("version")) is int and payload.get("version") == 3
    is_v2 = supplied_schema == V2_PDA_RESULT_SCHEMA and payload.get("version") == 2
    is_legacy = supplied_schema == LEGACY_PDA_RESULT_SCHEMA and payload.get("version", 1) == 1
    is_continuous = is_v3 or is_v4
    has_physical_state = is_v2 or is_continuous
    if not has_physical_state and not is_legacy:
        result.errors.append("El archivo no es un resultado PDA compatible con esta versión.")
    result.schema_version = 4 if is_v4 else (3 if is_v3 else (2 if is_v2 else (1 if is_legacy else 0)))

    if has_physical_state:
        sequence = payload.get("individual_sequence")
        if not isinstance(sequence, dict) or not (
            str(sequence.get("prefix", "")).upper() == "U"
            and sequence.get("start") == 1
            and sequence.get("consecutive") is True
            and sequence.get("padding") == 3
            and (not is_v4 or (type(sequence.get("start")) is int and type(sequence.get("padding")) is int))
        ):
            result.errors.append("El resultado PDA no confirma la secuencia U001…UN consecutiva.")

    result.container_id = canonical_scan(payload.get("container_id", ""))
    expected_id = canonical_scan(expected_container_id)
    if not result.container_id or result.container_id != expected_id:
        result.errors.append(
            f"El resultado corresponde a {result.container_id or 'SIN CONTENEDOR'}, no a {expected_id}."
        )

    canonical_records = {canonical_scan(code): record for code, record in records_by_code.items()}
    expected_signature = record_signature(canonical_records)
    supplied_signature = str(payload.get("record_signature", "")).strip().lower()
    if not supplied_signature:
        result.errors.append("El resultado PDA no contiene la firma del Packing List.")
    elif supplied_signature != expected_signature:
        result.errors.append("El resultado PDA fue creado con otro Packing List o con cantidades diferentes.")

    raw_events = payload.get("accepted_events")
    if not isinstance(raw_events, list):
        result.errors.append("El resultado PDA no contiene la lista de cajas aceptadas.")
        return result

    pallet_payload = payload.get("pallets", []) if has_physical_state else []
    pallet_states: dict[str, dict] = {}
    if has_physical_state:
        if not isinstance(pallet_payload, list):
            result.errors.append("El resultado PDA no contiene un resumen válido de tarimas.")
            pallet_payload = []
        for pallet_index, pallet_item in enumerate(pallet_payload, start=1):
            if not isinstance(pallet_item, dict):
                result.errors.append(f"Tarima PDA {pallet_index}: formato inválido.")
                continue
            pallet_id = canonical_scan(pallet_item.get("id", ""))
            if not pallet_id or not re.fullmatch(r"T-\d{2,4}", pallet_id):
                result.errors.append(f"Tarima PDA {pallet_index}: identificador inválido.")
                continue
            if pallet_id in pallet_states:
                result.errors.append(f"Tarima PDA {pallet_index}: {pallet_id} está duplicada.")
                continue
            pallet_states[pallet_id] = pallet_item

    seen: set[str] = set()
    code_counts: dict[str, int] = {}
    for index, item in enumerate(raw_events, start=1):
        if not isinstance(item, dict):
            result.errors.append(f"Registro PDA {index}: formato inválido.")
            continue
        if is_continuous and any(type(item.get(key)) is not bool for key in (
            "direct_to_final", "transfer_closed", "final_pallet_validated", "wms_eligible"
        )):
            result.errors.append(f"Registro PDA {index}: los controles de estado deben ser booleanos explícitos.")
            continue
        raw_barcode = item.get("barcode") or item.get("normalized_barcode") or item.get("raw_scan")
        parsed = parse_strict_scan(raw_barcode, canonical_records)
        if not parsed.valid:
            result.errors.append(f"Registro PDA {index}: {parsed.message}")
            continue
        if is_continuous:
            if raw_barcode != parsed.normalized_barcode or type(item.get("box_number")) is not int:
                result.errors.append(f"Registro PDA {index}: barcode normalizado o número de caja inválido.")
                continue
            raw_identity = parse_strict_scan(item.get("raw_scan", ""), canonical_records)
            if not raw_identity.valid or raw_identity.normalized_barcode != parsed.normalized_barcode:
                result.errors.append(f"Registro PDA {index}: la lectura original no corresponde al código individual declarado.")
                continue
        if parsed.normalized_barcode in seen:
            result.errors.append(f"Registro PDA {index}: {parsed.normalized_barcode} está duplicado.")
            continue
        seen.add(parsed.normalized_barcode)

        supplied_code = canonical_scan(item.get("code", ""))
        if (supplied_code or is_continuous) and supplied_code != parsed.code:
            result.errors.append(
                f"Registro PDA {index}: el código declarado no coincide con {parsed.normalized_barcode}."
            )
            continue
        supplied_number = item.get("box_number")
        if supplied_number not in (None, ""):
            try:
                declared_number = int(supplied_number)
            except (TypeError, ValueError):
                result.errors.append(f"Registro PDA {index}: el número de caja declarado es inválido.")
                continue
            if declared_number != parsed.box_number:
                result.errors.append(
                    f"Registro PDA {index}: el número de caja no coincide con {parsed.normalized_barcode}."
                )
                continue

        pallet = canonical_scan(item.get("final_pallet") or item.get("pallet") or item.get("position"))
        if not pallet:
            result.errors.append(f"Registro PDA {index}: falta la tarima definitiva.")
            continue
        if not re.fullmatch(r"[A-Z0-9][A-Z0-9_-]{0,39}", pallet):
            result.errors.append(f"Registro PDA {index}: la tarima definitiva tiene un formato inválido.")
            continue
        transfer = canonical_scan(item.get("transfer_pallet", ""))
        if transfer and not re.fullmatch(r"TR-\d{2,4}", transfer):
            result.errors.append(f"Registro PDA {index}: la tarima de traslado tiene un formato inválido.")
            continue
        direct = item.get("direct_to_final") is True
        physical_position = canonical_scan(item.get("physical_position", ""))
        physical_state = "SIN_CONFIRMACION_V08"
        pallet_validated = False
        eligible = False
        transfer_distributed = False
        pallet_summary = {}
        transfer_closed = False
        if has_physical_state:
            physical_state = canonical_scan(item.get("physical_state", ""))
            valid_states = {"PENDIENTE_VERIFICAR", "EN_DEFINITIVA"} if is_continuous else {"EN_TRASLADO", "EN_DEFINITIVA"}
            if physical_state not in valid_states:
                result.errors.append(f"Registro PDA {index}: estado físico inválido.")
                continue
            transfer_distributed = is_v2 and item.get("transfer_distributed") is True
            pallet_validated = item.get("final_pallet_validated") is True
            declared_eligible = item.get("wms_eligible") is True
            calculated_eligible = physical_state == "EN_DEFINITIVA" and pallet_validated
            if declared_eligible != calculated_eligible:
                result.errors.append(f"Registro PDA {index}: elegibilidad WMS inconsistente.")
                continue
            eligible = calculated_eligible
            if direct and transfer:
                result.errors.append(f"Registro PDA {index}: una caja directa no puede pertenecer a {transfer}.")
                continue
            if not direct and not transfer:
                result.errors.append(f"Registro PDA {index}: falta la tarima de traslado.")
                continue
            if is_v2 and not direct and physical_state == "EN_DEFINITIVA" and not transfer_distributed:
                result.errors.append(f"Registro PDA {index}: {transfer} no fue confirmada como distribuida.")
                continue
            pallet_summary = pallet_states.get(pallet)
            if pallet_summary is None:
                result.errors.append(f"Registro PDA {index}: {pallet} no aparece en el resumen de tarimas.")
                continue
            if (pallet_summary.get("validated") is True) != pallet_validated:
                result.errors.append(f"Registro PDA {index}: validación de {pallet} inconsistente.")
                continue
            expected_formation = "PIE" if direct else "TENDIDO"
            if canonical_scan(pallet_summary.get("formation", "")) != expected_formation:
                result.errors.append(f"Registro PDA {index}: formación de {pallet} inconsistente.")
                continue
            summary_position = canonical_scan(pallet_summary.get("physical_position", ""))
            if summary_position != physical_position:
                result.errors.append(f"Registro PDA {index}: posición física de {pallet} inconsistente.")
                continue
            if is_v4:
                temporary = item.get("wms_temporary_location")
                if not isinstance(temporary, str) or temporary != pallet_summary.get("wms_temporary_location"):
                    result.errors.append(f"Registro PDA {index}: temporal WMS ausente o distinta de la tarima.")
                    continue
                if (pallet_validated and not valid_wms_temporary(temporary)) or (not pallet_validated and temporary):
                    result.errors.append(f"Registro PDA {index}: temporal WMS incompatible con el cierre de la tarima.")
                    continue
            if is_continuous:
                transfer_closed = item.get("transfer_closed") is True
                if (physical_state == "EN_DEFINITIVA") != pallet_validated:
                    result.errors.append(f"Registro PDA {index}: solo una tarima verificada confirma presencia física.")
                    continue
                if direct and transfer_closed:
                    result.errors.append(f"Registro PDA {index}: una directa no cierra un traslado.")
                    continue
                if not direct and pallet_validated and not transfer_closed:
                    result.errors.append(f"Registro PDA {index}: una caja de la TR activa no puede verificarse.")
                    continue
        code_counts[parsed.code] = code_counts.get(parsed.code, 0) + 1
        result.events.append({
            "N": len(result.events) + 1,
            "Hora": str(item.get("scanned_at", "")),
            "Escaneo": parsed.normalized_barcode,
            "Escaneo bruto": canonical_scan(item.get("raw_scan", raw_barcode)),
            "Código": parsed.code,
            "Posición": pallet,
            "Estado": "OK",
            "Mensaje": "Importado desde PDA",
            "Recibidas": code_counts[parsed.code],
            "Esperadas": int(getattr(canonical_records[parsed.code], "boxes", 0)),
            "Tarima": pallet,
            "Tarima traslado": transfer,
            "Formación": "PIE" if direct else "TENDIDO",
            "Posición física": physical_position,
            "Estado físico": physical_state,
            "Traslado distribuido": transfer_distributed,
            "Traslado cerrado": transfer_closed,
            "Tarima validada": pallet_validated,
            "Tarima retirada": pallet_summary.get("retired") is True,
            "Verificado por": str(pallet_summary.get("verified_by", "")),
            "Fecha verificación": str(pallet_summary.get("verified_at", "")),
            "Método verificación": str(pallet_summary.get("verification_method", "")),
            "Modelo verificación": "FINAL_PALLET_WMS_TEMPORARY" if is_v4 else "FINAL_PALLET" if is_v3 else "LEGACY_V2" if is_v2 else "SIN_CONFIRMACION",
            "Temporal WMS": item.get("wms_temporary_location", "") if is_v4 else "",
            "Temporal WMS obligatoria": is_v4,
            "Elegible WMS": eligible,
            "Caja individual": True,
        })

    expected_total = sum(int(getattr(record, "boxes", 0)) for record in canonical_records.values())
    result.pallets = list(pallet_states.values())
    if is_continuous:
        _validate_v3_summary(payload, pallet_states, result, expected_total, requires_temporary=is_v4)
    if is_v3 or is_v2:
        result.warnings.append("Resultado anterior a V0.11: no acredita temporal al cierre en la PDA; requiere asignación manual de ubicaciones en Windows.")
    if result.events and len(result.events) < expected_total:
        result.warnings.append(
            f"Resultado parcial: {len(result.events)} de {expected_total} cajas esperadas."
        )
    if not result.events:
        result.errors.append("El resultado PDA no contiene cajas válidas aceptadas.")
    elif is_legacy:
        result.warnings.append(
            "Resultado V0.8 importado para auditoría: no contiene confirmación física ni validación de tarimas, "
            "por lo que sus cajas no son elegibles para WMS."
        )
    elif len(result.eligible_events) < len(result.events):
        result.warnings.append(
            f"Estado operativo: {len(result.eligible_events)} de {len(result.events)} cajas cumplen todos los requisitos WMS."
        )

    result.exported_at = str(payload.get("exported_at", ""))
    result.engine_version = str(payload.get("engine_version", ""))
    return result


def _validate_v3_summary(payload: dict, pallets: dict[str, dict], result: PdaImportResult, expected_total: int,
                         *, requires_temporary: bool = False) -> None:
    """Conciliar el detalle individual con tarimas, viajes y avance; fallar sin omitir diferencias."""
    expected_model = "FINAL_PALLET_WMS_TEMPORARY" if requires_temporary else "FINAL_PALLET"
    if payload.get("verification_model") != expected_model:
        result.errors.append("El resultado no declara el modelo de verificación requerido.")
    if requires_temporary and payload.get("wms_location_validation") != "FORMAT_ONLY":
        result.errors.append("La PDA debe indicar que solo comprobó el formato, no la existencia de la temporal en WMS.")
    is_v015 = str(payload.get("engine_version", "")).startswith("0.15-")
    if is_v015 and payload.get("plan_export_policy") != "ACTUAL_SCANNED_ONLY":
        result.errors.append("V0.15 debe exportar únicamente tarimas y cajas realmente escaneadas.")
    if is_v015 and payload.get("overflow_policy") != "TRANSFER_WHEN_NO_FOOT_POSITION":
        result.errors.append("V0.15 no declara la contingencia segura cuando se agotan posiciones al pie.")

    def count(value: object) -> bool:
        return type(value) is int and value >= 0

    def clean_text(value: object, limit: int) -> bool:
        return isinstance(value, str) and len(value) <= limit and not re.search(r"[\x00-\x1f\x7f]", value)

    grouped: dict[str, list[dict]] = {}
    for event in result.events:
        grouped.setdefault(event["Tarima"], []).append(event)
    occupied: set[str] = set()
    legacy_proofs = 0
    for pallet_id, pallet in pallets.items():
        prefix = f"Tarima {pallet_id}: "
        numeric_fields = ("expected", "original_expected", "scanned", "in_final", "verified_boxes")
        if not all(count(pallet.get(key)) for key in numeric_fields):
            result.errors.append(prefix + "cantidades inválidas o ausentes.")
            continue
        if type(pallet.get("validated")) is not bool or type(pallet.get("retired")) is not bool:
            result.errors.append(prefix + "verificación y retiro deben ser booleanos explícitos.")
            continue
        expected = pallet["expected"]
        scanned = pallet["scanned"]
        original = pallet["original_expected"]
        verified = pallet["validated"]
        retired = pallet["retired"]
        if is_v015 and scanned == 0:
            result.errors.append(prefix + "V0.15 no permite exportar una tarima teórica sin cajas escaneadas.")
        if requires_temporary:
            temporary = pallet.get("wms_temporary_location")
            if not isinstance(temporary, str) or (verified and not valid_wms_temporary(temporary)) or (not verified and temporary):
                result.errors.append(prefix + "la temporal WMS es obligatoria al cerrar y debe estar vacía antes del cierre.")
            if verified and pallet.get("verification_method") != "REVISION_FISICA":
                result.errors.append(prefix + "V0.11 requiere revisión física con temporal; no acepta verificaciones heredadas sin ella.")
        items = grouped.get(pallet_id, [])
        actual_final = sum(event["Estado físico"] == "EN_DEFINITIVA" for event in items)
        if expected <= 0 or scanned > expected or original < expected:
            result.errors.append(prefix + "previsión y captura son incompatibles.")
        if scanned != len(items) or pallet["in_final"] != actual_final:
            result.errors.append(prefix + "el resumen no coincide con el detalle de cajas.")
        if verified and (scanned == 0 or scanned != expected or actual_final != scanned):
            result.errors.append(prefix + "no puede verificarse con captura incompleta o cajas sin confirmar.")
        if retired and not verified:
            result.errors.append(prefix + "no puede retirarse sin verificación.")
        expected_status = ("RETIRADA" if retired else "VERIFICADA" if verified else "PREPARAR"
                           if scanned == 0 else "REVISAR" if scanned >= expected else "EN FORMACIÓN")
        if pallet.get("status") != expected_status:
            result.errors.append(prefix + "estado de tarima inconsistente.")
        formation = pallet.get("formation")
        position = pallet.get("physical_position")
        if not isinstance(formation, str) or formation not in {"PIE", "TENDIDO"} or not isinstance(position, str):
            result.errors.append(prefix + "formación o posición inválida.")
        elif formation == "PIE":
            if not re.fullmatch(r"[ID](?:0[1-9]|10)", position):
                result.errors.append(prefix + "posición al pie inválida.")
            if scanned > 0 and not retired:
                if position in occupied:
                    result.errors.append(prefix + "la posición al pie está ocupada por otra tarima activa.")
                occupied.add(position)
        elif position:
            result.errors.append(prefix + "no debe confundir el tendido con una posición al pie.")
        reason = pallet.get("closure_reason")
        if not clean_text(reason, 160):
            result.errors.append(prefix + "motivo de cierre inválido.")
        elif original > expected and (not reason.strip() or (formation != "PIE" and not is_v015)):
            result.errors.append(prefix + "la reducción del objetivo requiere cierre parcial trazable y motivo.")
        elif reason and original == expected:
            result.errors.append(prefix + "el cierre parcial no conserva la previsión original.")

        method = pallet.get("verification_method")
        actor = pallet.get("verified_by")
        time = pallet.get("verified_at")
        if not all(clean_text(value, limit) for value, limit in ((method, 40), (actor, 80), (time, 80))):
            result.errors.append(prefix + "datos de verificación inválidos.")
            continue
        if not verified:
            if method or actor or time or pallet["verified_boxes"] != 0:
                result.errors.append(prefix + "declara datos de verificación sin estar verificada.")
        elif pallet["verified_boxes"] != scanned:
            result.errors.append(prefix + "la verificación no cubre exactamente su contenido.")
        elif method == "LEGADO_V09":
            legacy_proofs += 1
            if actor or time:
                result.errors.append(prefix + "una verificación heredada no debe inventar responsable ni fecha.")
        elif method == "REVISION_FISICA":
            if not actor.strip():
                result.errors.append(prefix + "falta el responsable de la revisión física.")
            try:
                parsed_time = datetime.fromisoformat(time.replace("Z", "+00:00"))
                if parsed_time.tzinfo is None:
                    raise ValueError("sin zona horaria")
            except ValueError:
                result.errors.append(prefix + "fecha de verificación inválida o sin zona horaria.")
        else:
            result.errors.append(prefix + "falta una prueba de verificación reconocida.")

    if legacy_proofs:
        result.warnings.append(f"{legacy_proofs} tarima(s) conservan una validación V0.9 sin responsable/fecha registrados; no es una nueva revisión física.")

    active = payload.get("active_transfer")
    if not isinstance(active, str) or not re.fullmatch(r"TR-\d{2,4}", active):
        result.errors.append("El traslado activo tiene un identificador inválido.")
    transfer_items = payload.get("transfers")
    if not isinstance(transfer_items, list):
        result.errors.append("Falta el resumen de traslados del flujo continuo.")
        transfer_items = []
    transfers: dict[str, dict] = {}
    for item in transfer_items:
        if not isinstance(item, dict):
            result.errors.append("El resumen contiene un traslado inválido.")
            continue
        transfer_id = item.get("id")
        if not isinstance(transfer_id, str) or not re.fullmatch(r"TR-\d{2,4}", transfer_id) or transfer_id in transfers:
            result.errors.append("El resumen contiene un traslado sin ID válido o duplicado.")
            continue
        transfers[transfer_id] = item
        if type(item.get("closed")) is not bool or not count(item.get("boxes")) or not count(item.get("verified_boxes")):
            result.errors.append(f"Traslado {transfer_id}: controles o cantidades inválidos.")
            continue
        events = [event for event in result.events if event["Tarima traslado"] == transfer_id]
        verified_count = sum(event["Elegible WMS"] for event in events)
        if item["boxes"] != len(events) or item["verified_boxes"] != verified_count:
            result.errors.append(f"Traslado {transfer_id}: cantidades no coinciden con sus cajas.")
        if (transfer_id == active) == item["closed"]:
            result.errors.append(f"Traslado {transfer_id}: solo el traslado activo puede permanecer abierto.")
        if any(event["Traslado cerrado"] != item["closed"] for event in events):
            result.errors.append(f"Traslado {transfer_id}: cierre inconsistente en sus cajas.")
        status = "EN_FORMACION" if not item["closed"] else (
            "VERIFICADO_POR_TARIMAS" if events and verified_count == len(events) else "PENDIENTE_VERIFICACION"
        )
        if item.get("status") != status:
            result.errors.append(f"Traslado {transfer_id}: estado inconsistente.")
    if not isinstance(active, str) or active not in transfers:
        result.errors.append("El traslado activo no aparece en el resumen.")
    if any(event["Tarima traslado"] and event["Tarima traslado"] not in transfers for event in result.events):
        result.errors.append("Hay cajas cuyo traslado no aparece en el resumen.")
    result.transfers = list(transfers.values())

    progress = payload.get("progress")
    required = {"received": len(result.events), "expected": expected_total,
                "in_final": sum(event["Estado físico"] == "EN_DEFINITIVA" for event in result.events),
                "wms_eligible": len(result.eligible_events)}
    if not isinstance(progress, dict) or any(not count(progress.get(key)) or progress.get(key) != value for key, value in required.items()):
        result.errors.append("El avance general no coincide con el Packing List y el detalle de cajas.")


def demo_pda_result(container_id: str, records_by_code: Mapping[str, object], events: list[dict]) -> bytes:
    """Ayudante determinista para pruebas de integración."""
    normalized_events = []
    pallets: dict[str, dict] = {}
    for source in events:
        item = dict(source)
        direct = item.get("direct_to_final") is True
        item.setdefault("physical_position", "I01" if direct else "")
        item.setdefault("transfer_distributed", True)
        item.setdefault("physical_state", "EN_DEFINITIVA")
        item.setdefault("final_pallet_validated", True)
        item.setdefault("wms_eligible", True)
        normalized_events.append(item)
        pallet_id = canonical_scan(item.get("final_pallet", ""))
        bucket = pallets.setdefault(pallet_id, {
            "id": pallet_id,
            "formation": "PIE" if direct else "TENDIDO",
            "physical_position": canonical_scan(item.get("physical_position", "")),
            "status": "VALIDADA" if item.get("final_pallet_validated") else "EN FORMACIÓN",
            "expected": 0,
            "scanned": 0,
            "in_final": 0,
            "validated": item.get("final_pallet_validated") is True,
        })
        bucket["expected"] += 1
        bucket["scanned"] += 1
        if item.get("physical_state") == "EN_DEFINITIVA":
            bucket["in_final"] += 1
    payload = {
        "schema": V2_PDA_RESULT_SCHEMA,
        "version": 2,
        "container_id": canonical_scan(container_id),
        "record_signature": record_signature(records_by_code),
        "exported_at": datetime.now(timezone.utc).isoformat(),
        "engine_version": "test",
        "individual_sequence": {"prefix": "U", "start": 1, "consecutive": True, "padding": 3},
        "pallets": list(pallets.values()),
        "accepted_events": normalized_events,
    }
    return json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
