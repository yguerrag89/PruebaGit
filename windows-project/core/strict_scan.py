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


def _mode(record: object, code: str) -> str:
    supplied = str(getattr(record, "identity_mode", "") or "").strip().upper()
    if supplied in {"U_SEQUENCE", "HYPHEN_SEQUENCE", "HYPHEN_UNIQUE", "EXPLICIT"}:
        return supplied
    if code.startswith("MOYU"):
        return "HYPHEN_SEQUENCE"
    if re.match(r"^(ZGA|ZGC|ZGD)\d*[-_/]", code) or code.startswith("FUE"):
        return "HYPHEN_UNIQUE"
    return "U_SEQUENCE"


def _expected_ids(record: object) -> tuple[str, ...]:
    return tuple(
        canonical_scan(x) for x in (getattr(record, "expected_box_ids", ()) or ())
        if canonical_scan(x)
    )


def record_signature(records: Mapping[str, object]) -> str:
    lines = []
    for raw_code, record in sorted(records.items(), key=lambda item: canonical_scan(item[0])):
        code = canonical_scan(raw_code)
        boxes = int(getattr(record, "boxes", 0))
        ids = _expected_ids(record)
        mode = "EXPLICIT" if ids else _mode(record, code)
        lines.append(f"{code}:{boxes}:{mode}:{','.join(ids)}\n")
    return sha256("".join(lines).encode("utf-8")).hexdigest()


def parse_strict_scan(raw_scan: object, records: Mapping[str, object]) -> StrictScan:
    """Resuelve la identidad física sin convertir un patrón en evidencia inexistente.

    Prioridad:
    1. IDs explícitos suministrados por el manifiesto.
    2. U_SEQUENCE: CODIGOU001..UN (hasta 6 dígitos).
    3. HYPHEN_SEQUENCE: CODIGO-1..N (MOYU).
    4. HYPHEN_UNIQUE: CODIGO-<id externo> (ZGA/ZGC/ZGD/FUE);
       el sufijo identifica la caja pero NO se interpreta como posición 1..N.
    """
    raw = canonical_scan(raw_scan)
    if not raw:
        return StrictScan(False, status="VACÍO", message="Escaneo vacío")

    canonical_records = {canonical_scan(k): v for k, v in records.items()}

    explicit_matches: list[tuple[str, str, int]] = []
    for code, record in canonical_records.items():
        for ordinal, barcode in enumerate(_expected_ids(record), 1):
            if barcode and barcode in raw:
                explicit_matches.append((barcode, code, ordinal))
    if explicit_matches:
        longest = max(len(x[0]) for x in explicit_matches)
        top = {(b, c, n) for b, c, n in explicit_matches if len(b) == longest}
        if len(top) != 1:
            return StrictScan(False, raw_canonical=raw, status="LECTURA AMBIGUA",
                              message="La lectura contiene más de una identidad esperada.")
        barcode, code, ordinal = next(iter(top))
        return StrictScan(True, raw, code, barcode, ordinal)

    candidates = [code for code in canonical_records if code and code in raw]
    if not candidates:
        return StrictScan(False, raw_canonical=raw, status="NO ENCONTRADA",
                          message="La caja no pertenece al Packing List")

    longest = max(map(len, candidates))
    best = sorted({code for code in candidates if len(code) == longest})
    if len(best) != 1:
        return StrictScan(False, raw_canonical=raw, status="LECTURA AMBIGUA",
                          message="La lectura contiene más de un código. Escanee nuevamente.")

    code = best[0]
    record = canonical_records[code]
    boxes = int(getattr(record, "boxes", 0))
    mode = _mode(record, code)

    if mode == "EXPLICIT":
        return StrictScan(False, raw_canonical=raw, code=code, status="NO ENCONTRADA",
                          message="La familia pertenece al Packing List, pero la caja individual no está en la lista esperada.")

    if mode == "U_SEQUENCE":
        numbers = {int(m.group(1)) for m in re.finditer(
            re.escape(code) + r"[^A-Z0-9]{0,3}U(\d{1,6})(?!\d)", raw
        )}
        if not numbers:
            if boxes == 1 and raw == code:
                numbers = {1}
            else:
                return StrictScan(False, raw_canonical=raw, code=code, status="LECTURA INCOMPLETA",
                                  message="Falta el identificador individual Uxxx.")
        if len(numbers) != 1:
            return StrictScan(False, raw_canonical=raw, code=code, status="LECTURA AMBIGUA",
                              message="Se detectaron varios números de caja.")
        number = next(iter(numbers))
        normalized = f"{code}U{number:03d}"
        if number < 1 or (boxes > 0 and number > boxes):
            return StrictScan(False, raw, code, normalized, number, "FUERA DE RANGO",
                              f"POSIBLE SOBRANTE · esperadas {boxes} cajas")
        return StrictScan(True, raw, code, normalized, number)

    if mode == "HYPHEN_SEQUENCE":
        numbers = {int(m.group(1)) for m in re.finditer(
            re.escape(code) + r"[-/_](\d{1,6})(?!\d)", raw
        )}
        if len(numbers) != 1:
            return StrictScan(False, raw_canonical=raw, code=code,
                              status="LECTURA INCOMPLETA" if not numbers else "LECTURA AMBIGUA",
                              message="Falta o es ambiguo el número individual después del código.")
        number = next(iter(numbers))
        normalized = f"{code}-{number}"
        if number < 1 or (boxes > 0 and number > boxes):
            return StrictScan(False, raw, code, normalized, number, "FUERA DE RANGO",
                              f"POSIBLE SOBRANTE · esperadas {boxes} cajas")
        return StrictScan(True, raw, code, normalized, number)

    matches = list(re.finditer(re.escape(code) + r"[-/_](\d{1,8})(?!\d)", raw))
    ids = {(int(m.group(1)), f"{code}-{m.group(1)}") for m in matches}
    if len(ids) != 1:
        return StrictScan(False, raw_canonical=raw, code=code,
                          status="LECTURA INCOMPLETA" if not ids else "LECTURA AMBIGUA",
                          message="Falta o es ambiguo el identificador individual externo.")
    number, normalized = next(iter(ids))
    if number < 1:
        return StrictScan(False, raw, code, normalized, number, "LECTURA INVÁLIDA",
                          "Identificador individual inválido")
    return StrictScan(True, raw, code, normalized, number)
