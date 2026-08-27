from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha256
from typing import Mapping
import re
import unicodedata


@dataclass(frozen=True)
class StrictScan:
    valid: bool
    raw_canonical: str = ""
    code: str = ""
    normalized_barcode: str = ""
    box_number: int = 0
    status: str = ""
    message: str = ""


def canonical_scan(value: object) -> str:
    text = "" if value is None else str(value)
    text = unicodedata.normalize("NFKC", text).strip().upper()
    return re.sub(r"\s+", "", text)


def record_signature(records: Mapping[str, object]) -> str:
    lines = []
    for raw_code, record in sorted(records.items(), key=lambda item: str(item[0]).upper()):
        code = canonical_scan(raw_code)
        boxes = int(getattr(record, "boxes", 0))
        lines.append(f"{code}:{boxes}\n")
    return sha256("".join(lines).encode("utf-8")).hexdigest()


def parse_strict_scan(raw_scan: object, records: Mapping[str, object]) -> StrictScan:
    """Resolve un barcode individual sin inventar números de caja.

    Se toleran prefijos del lector, separadores cortos y una repetición corrupta
    del mismo barcode, pero para códigos con varias cajas siempre debe existir
    un sufijo Uxxx inequívoco. Un código base nunca cuenta como caja individual.
    """
    raw = canonical_scan(raw_scan)
    if not raw:
        return StrictScan(False, status="VACÍO", message="Escaneo vacío")

    candidates = [canonical_scan(code) for code in records if canonical_scan(code) in raw]
    if not candidates:
        return StrictScan(
            False,
            raw_canonical=raw,
            status="NO ENCONTRADA",
            message="La caja no pertenece al Packing List",
        )

    longest = max(len(code) for code in candidates)
    best = sorted({code for code in candidates if len(code) == longest})
    if len(best) != 1:
        return StrictScan(
            False,
            raw_canonical=raw,
            status="LECTURA AMBIGUA",
            message="La lectura contiene más de un código. Escanee nuevamente.",
        )

    code = best[0]
    record = records.get(code)
    if record is None:
        # Los consumidores normalmente usan claves canónicas; este respaldo
        # evita fallas si el Mapping original conserva minúsculas.
        record = next((value for key, value in records.items() if canonical_scan(key) == code), None)
    boxes = int(getattr(record, "boxes", 0)) if record is not None else 0

    numbers: set[int] = set()
    pattern = re.compile(re.escape(code) + r"[^A-Z0-9]{0,3}U(\d{1,3})(?!\d)")
    for match in pattern.finditer(raw):
        numbers.add(int(match.group(1)))

    if not numbers:
        if boxes == 1 and raw == code:
            numbers.add(1)
        else:
            return StrictScan(
                False,
                raw_canonical=raw,
                code=code,
                status="LECTURA INCOMPLETA",
                message="Falta el identificador individual Uxxx. Escanee la etiqueta de la caja.",
            )

    if len(numbers) != 1:
        return StrictScan(
            False,
            raw_canonical=raw,
            code=code,
            status="LECTURA AMBIGUA",
            message="Se detectaron varios números de caja. Escanee nuevamente.",
        )

    box_number = next(iter(numbers))
    if box_number < 1:
        return StrictScan(
            False,
            raw_canonical=raw,
            code=code,
            status="LECTURA INVÁLIDA",
            message="El número de caja debe ser mayor que cero.",
        )
    if boxes > 0 and box_number > boxes:
        return StrictScan(
            False,
            raw_canonical=raw,
            code=code,
            normalized_barcode=f"{code}U{box_number:03d}",
            box_number=box_number,
            status="FUERA DE RANGO",
            message=f"POSIBLE SOBRANTE · Packing List U001–U{boxes:03d}",
        )

    normalized = f"{code}U{box_number:03d}"
    return StrictScan(
        True,
        raw_canonical=raw,
        code=code,
        normalized_barcode=normalized,
        box_number=box_number,
    )
