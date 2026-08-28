"""Contrato compartido con WmsTemporaryLocation.java: formato, no catálogo XLWMS."""
import re


def normalize_wms_temporary(value: object) -> str:
    return value.strip().upper() if isinstance(value, str) else ""


def valid_wms_temporary(value: object, *, canonical: bool = True) -> bool:
    if not isinstance(value, str) or re.search(r"[\x00-\x1f\x7f]", value):
        return False
    normalized = normalize_wms_temporary(value)
    if canonical and value != normalized:
        return False
    return bool(re.fullmatch(r"[A-Z0-9][A-Z0-9._/-]{0,79}", normalized)) and not bool(
        re.fullmatch(r"(?:T-|TR-)[0-9]+|[ID](?:0[1-9]|10)", normalized)
    )
