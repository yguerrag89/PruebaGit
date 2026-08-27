from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Mapping
import json
import re

from .strict_scan import canonical_scan, parse_strict_scan, record_signature


PDA_MANIFEST_SCHEMA = "ilubox.pda.manifest.v2"
PDA_RESULT_SCHEMA = "ilubox.pda.result.v1"


@dataclass
class PdaImportResult:
    container_id: str = ""
    events: list[dict] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    exported_at: str = ""
    engine_version: str = ""

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
    if payload.get("schema") != PDA_RESULT_SCHEMA:
        result.errors.append("El archivo no es un resultado PDA compatible con esta versión.")

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
            "Caja individual": True,
        })

    expected_total = sum(int(getattr(record, "boxes", 0)) for record in canonical_records.values())
    if result.events and len(result.events) < expected_total:
        result.warnings.append(
            f"Resultado parcial: {len(result.events)} de {expected_total} cajas esperadas."
        )
    if not result.events:
        result.errors.append("El resultado PDA no contiene cajas válidas aceptadas.")

    result.exported_at = str(payload.get("exported_at", ""))
    result.engine_version = str(payload.get("engine_version", ""))
    return result


def demo_pda_result(container_id: str, records_by_code: Mapping[str, object], events: list[dict]) -> bytes:
    """Ayudante determinista para pruebas de integración."""
    payload = {
        "schema": PDA_RESULT_SCHEMA,
        "version": 1,
        "container_id": canonical_scan(container_id),
        "record_signature": record_signature(records_by_code),
        "exported_at": datetime.now(timezone.utc).isoformat(),
        "engine_version": "test",
        "accepted_events": events,
    }
    return json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
