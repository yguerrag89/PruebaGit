from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Mapping
import json
import re

from .strict_scan import canonical_scan, parse_strict_scan, record_signature


PDA_MANIFEST_SCHEMA = "ilubox.pda.manifest.v2"
PDA_RESULT_SCHEMA = "ilubox.pda.result.v2"
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

    @property
    def eligible_events(self) -> list[dict]:
        return [event for event in self.events if event.get("Elegible WMS") is True]

    @property
    def ready(self) -> bool:
        return bool(self.events) and not self.errors


def build_pda_manifest(container, settings) -> bytes:
    records_by_code = {record.code.upper(): record for record in container.records}
    payload = {
        "schema": PDA_MANIFEST_SCHEMA,
        "version": 2,
        "container_id": container.container_id,
        "source_file": container.source_file,
        "source_sheet": getattr(container, "sheet", ""),
        "record_signature": record_signature(records_by_code),
        "strict_individual_barcodes": True,
        "individual_sequence": {"prefix": "U", "start": 1, "consecutive": True, "padding": 3},
        "settings": {
            "physical_capacity": settings.physical_capacity,
            "target_capacity": settings.target_capacity,
            "large_ratio": settings.large_ratio,
            "medium_high_ratio": settings.medium_high_ratio,
            "medium_ratio": settings.medium_ratio,
            "max_codes_unit": settings.max_codes_unit,
            "max_codes_small": settings.max_codes_small,
            "max_codes_medium": settings.max_codes_medium,
            "max_codes_medium_high": settings.max_codes_medium_high,
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
    is_v2 = supplied_schema == PDA_RESULT_SCHEMA and payload.get("version") == 2
    is_legacy = supplied_schema == LEGACY_PDA_RESULT_SCHEMA and payload.get("version", 1) == 1
    if not is_v2 and not is_legacy:
        result.errors.append("El archivo no es un resultado PDA compatible con esta versión.")
    result.schema_version = 2 if is_v2 else (1 if is_legacy else 0)

    if is_v2:
        sequence = payload.get("individual_sequence")
        if not isinstance(sequence, dict) or not (
            str(sequence.get("prefix", "")).upper() == "U"
            and sequence.get("start") == 1
            and sequence.get("consecutive") is True
            and sequence.get("padding") == 3
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

    pallet_payload = payload.get("pallets", []) if is_v2 else []
    pallet_states: dict[str, dict] = {}
    if is_v2:
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
        raw_barcode = item.get("barcode") or item.get("normalized_barcode") or item.get("raw_scan")
        parsed = parse_strict_scan(raw_barcode, canonical_records)
        if not parsed.valid:
            result.errors.append(f"Registro PDA {index}: {parsed.message}")
            continue
        if parsed.normalized_barcode in seen:
            result.errors.append(f"Registro PDA {index}: {parsed.normalized_barcode} está duplicado.")
            continue
        seen.add(parsed.normalized_barcode)

        supplied_code = canonical_scan(item.get("code", ""))
        if supplied_code and supplied_code != parsed.code:
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
        if is_v2:
            physical_state = canonical_scan(item.get("physical_state", ""))
            if physical_state not in {"EN_TRASLADO", "EN_DEFINITIVA"}:
                result.errors.append(f"Registro PDA {index}: estado físico inválido.")
                continue
            transfer_distributed = item.get("transfer_distributed") is True
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
            if not direct and physical_state == "EN_DEFINITIVA" and not transfer_distributed:
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
            "Tarima validada": pallet_validated,
            "Elegible WMS": eligible,
            "Caja individual": True,
        })

    expected_total = sum(int(getattr(record, "boxes", 0)) for record in canonical_records.values())
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
        "schema": PDA_RESULT_SCHEMA,
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
