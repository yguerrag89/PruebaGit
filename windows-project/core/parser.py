from __future__ import annotations
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Iterable
import re
import unicodedata

from .xlsx_reader import read_xlsx_rows


def norm_text(v) -> str:
    s = "" if v is None else str(v).strip()
    s = unicodedata.normalize("NFKC", s)
    return " ".join(s.split())


def norm_header(v) -> str:
    s = norm_text(v).lower().replace("³", "3")
    s = s.replace("（", "(").replace("）", ")")
    return re.sub(r"[\s_\-./]+", "", s)


def to_float(v):
    s = norm_text(v).replace(",", "")
    if not s:
        return None
    try:
        return float(s)
    except Exception:
        m = re.search(r"-?\d+(?:\.\d+)?", s)
        return float(m.group()) if m else None


def to_int(v):
    x = to_float(v)
    return int(round(x)) if x is not None else None


HEADER_CANDIDATES = {
    "code": ["头程号", "运单号", "唛头", "codigo", "código", "codigo origen", "código origen"],
    "boxes": ["箱数", "件数", "cajas", "cantidad", "carton count", "总箱数"],
    "cbm": ["体积", "体积(m³)", "体积(m3)", "cbm", "volumen", "volumen(m³)"],
    "container": ["柜号", "contenedor", "container"],
    "weight": ["单箱重量", "重量", "peso", "peso unitario"],
    "description": ["产品名称", "descripcion", "descripción", "producto", "备注"],
    "warehouse": ["平台仓仓库", "仓库代码", "bodega", "warehouse"],
}


def _candidate_set(items):
    return {norm_header(x) for x in items}

CANDS = {k: _candidate_set(v) for k, v in HEADER_CANDIDATES.items()}


@dataclass
class CodeRecord:
    code: str
    boxes: int
    cbm: float
    cbm_per_box: float
    weight_per_box: float | None = None
    description: str = ""
    warehouse: str = ""
    container: str = ""
    source_file: str = ""
    source_sheet: str = ""
    identity_mode: str = ""
    expected_box_ids: tuple[str, ...] = ()

    def __post_init__(self):
        self.code = norm_text(self.code).replace(" ", "").upper()
        supplied = str(self.identity_mode or "").strip().upper()
        ids = tuple(dict.fromkeys(
            norm_text(x).replace(" ", "").upper()
            for x in (self.expected_box_ids or ())
            if norm_text(x)
        ))
        self.expected_box_ids = ids
        if ids:
            self.identity_mode = "EXPLICIT"
        elif supplied in {"U_SEQUENCE", "HYPHEN_SEQUENCE", "HYPHEN_UNIQUE"}:
            self.identity_mode = supplied
        else:
            self.identity_mode = infer_identity_mode(self.code)

    @property
    def dynamic_identity(self) -> bool:
        return self.identity_mode == "HYPHEN_UNIQUE" and not self.expected_box_ids

    def box_id(self, ordinal: int) -> str:
        if ordinal < 1 or ordinal > self.boxes:
            return ""
        if self.identity_mode == "EXPLICIT":
            return self.expected_box_ids[ordinal - 1]
        if self.identity_mode == "HYPHEN_SEQUENCE":
            return f"{self.code}-{ordinal}"
        if self.identity_mode == "U_SEQUENCE":
            return f"{self.code}U{ordinal:03d}"
        return ""

    def as_dict(self):
        return asdict(self)


def infer_identity_mode(code: str) -> str:
    value = norm_text(code).replace(" ", "").upper()
    if value.startswith("MOYU"):
        return "HYPHEN_SEQUENCE"
    if re.match(r"^(ZGA|ZGC|ZGD)\d*[-_/]", value) or value.startswith("FUE"):
        return "HYPHEN_UNIQUE"
    return "U_SEQUENCE"


@dataclass
class ParsedContainer:
    container_id: str
    source_file: str
    sheet: str
    records: list[CodeRecord]
    warnings: list[str]

    @property
    def total_boxes(self):
        return sum(r.boxes for r in self.records)

    @property
    def total_cbm(self):
        return sum(r.cbm for r in self.records)


def _find_header(rows, scan_rows=30):
    best = None
    for i, row in enumerate(rows[:scan_rows]):
        normalized = [norm_header(x) for x in row]
        found = {}
        for field, candidates in CANDS.items():
            for j, h in enumerate(normalized):
                if h in candidates:
                    found[field] = j
                    break
        score = sum(k in found for k in ("code", "boxes", "cbm"))
        if score >= 2 and (best is None or score > best[0]):
            best = (score, i, found)
    return best


def _container_from_filename(filename: str) -> str:
    m = re.search(r"\b[A-Z]{4}\d{7}\b", filename.upper())
    return m.group() if m else Path(filename).stem[:40]


def _looks_container(v: str) -> bool:
    return bool(re.fullmatch(r"[A-Z]{4}\d{7}", norm_text(v).upper()))


def parse_xlsx_bytes(data: bytes, filename: str) -> list[ParsedContainer]:
    outputs = []
    for sheet_name, rows in read_xlsx_rows(data, max_rows=None, max_cols=40):
        header = _find_header(rows)
        if not header:
            continue
        _, header_idx, cols = header
        missing = [x for x in ("code", "boxes", "cbm") if x not in cols]
        if missing:
            continue

        warnings = []
        raw_records = []
        detected_containers = []
        for row in rows[header_idx + 1:]:
            code = norm_text(row[cols["code"]]) if cols["code"] < len(row) else ""
            boxes = to_int(row[cols["boxes"]]) if cols["boxes"] < len(row) else None
            cbm = to_float(row[cols["cbm"]]) if cols["cbm"] < len(row) else None
            if not code or boxes is None or cbm is None or boxes <= 0 or cbm < 0:
                continue
            container = ""
            if "container" in cols and cols["container"] < len(row):
                container = norm_text(row[cols["container"]]).upper()
                if _looks_container(container):
                    detected_containers.append(container)
            weight = to_float(row[cols["weight"]]) if "weight" in cols and cols["weight"] < len(row) else None
            desc = norm_text(row[cols["description"]]) if "description" in cols and cols["description"] < len(row) else ""
            wh = norm_text(row[cols["warehouse"]]) if "warehouse" in cols and cols["warehouse"] < len(row) else ""
            raw_records.append(CodeRecord(
                code=code.upper(), boxes=boxes, cbm=cbm,
                cbm_per_box=(cbm / boxes if boxes else 0),
                weight_per_box=weight, description=desc, warehouse=wh,
                container=container, source_file=filename, source_sheet=sheet_name,
            ))

        if not raw_records:
            continue

        # Agrupar duplicados del mismo código dentro de una hoja.
        grouped = {}
        for r in raw_records:
            key = r.code
            if key not in grouped:
                grouped[key] = r
            else:
                g = grouped[key]
                g.boxes += r.boxes
                g.cbm += r.cbm
                g.cbm_per_box = g.cbm / g.boxes
                if r.description and r.description not in g.description:
                    g.description = (g.description + " | " + r.description).strip(" |")
                if r.warehouse and r.warehouse not in g.warehouse:
                    g.warehouse = (g.warehouse + " | " + r.warehouse).strip(" |")

        container_id = detected_containers[0] if detected_containers else _container_from_filename(filename)
        if len(set(detected_containers)) > 1:
            warnings.append("Se detectaron varios números de contenedor en la misma hoja.")
        outputs.append(ParsedContainer(container_id, filename, sheet_name, list(grouped.values()), warnings))
    return outputs
