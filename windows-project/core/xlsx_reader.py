from __future__ import annotations
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile
import re
import xml.etree.ElementTree as ET

NS = {
    "a": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
    "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
}
REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"


def _col_index(ref: str) -> int:
    m = re.match(r"([A-Z]+)", ref or "")
    if not m:
        return 0
    n = 0
    for ch in m.group(1):
        n = n * 26 + ord(ch) - 64
    return n - 1


def read_xlsx_rows(source: bytes | str | Path, max_rows: int | None = None, max_cols: int = 40):
    """Lee XLSX usando solo ZIP/XML. Retorna [(sheet_name, rows)]."""
    fileobj = BytesIO(source) if isinstance(source, (bytes, bytearray)) else source
    with ZipFile(fileobj) as z:
        shared = []
        if "xl/sharedStrings.xml" in z.namelist():
            root = ET.fromstring(z.read("xl/sharedStrings.xml"))
            for si in root.findall("a:si", NS):
                shared.append("".join((t.text or "") for t in si.iterfind(".//a:t", NS)))

        wb = ET.fromstring(z.read("xl/workbook.xml"))
        rels = ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))
        relmap = {r.attrib["Id"]: r.attrib["Target"] for r in rels.findall(f"{{{REL_NS}}}Relationship")}

        sheets = []
        for s in wb.find("a:sheets", NS):
            rid = s.attrib[f"{{{NS['r']}}}id"]
            target = relmap[rid]
            member = target.lstrip("/") if target.startswith("/") else "xl/" + target.lstrip("/")
            member = str(Path(member)).replace("\\", "/")
            sheets.append((s.attrib.get("name", "Sheet"), member))

        result = []
        for sheet_name, member in sheets:
            if member not in z.namelist():
                continue
            root = ET.fromstring(z.read(member))
            data = root.find("a:sheetData", NS)
            if data is None:
                continue
            rows = []
            row_nodes = list(data)
            if max_rows is not None:
                row_nodes = row_nodes[:max_rows]
            for r in row_nodes:
                vals = [""] * max_cols
                for c in r.findall("a:c", NS):
                    j = _col_index(c.attrib.get("r", ""))
                    if j >= max_cols:
                        continue
                    typ = c.attrib.get("t")
                    value = ""
                    if typ == "inlineStr":
                        node = c.find("a:is", NS)
                        if node is not None:
                            value = "".join((t.text or "") for t in node.iterfind(".//a:t", NS))
                    else:
                        v = c.find("a:v", NS)
                        if v is not None:
                            raw = v.text or ""
                            if typ == "s":
                                try:
                                    value = shared[int(raw)]
                                except Exception:
                                    value = raw
                            elif typ == "b":
                                value = "TRUE" if raw == "1" else "FALSE"
                            else:
                                value = raw
                    vals[j] = value
                rows.append(vals)
            result.append((sheet_name, rows))
        return result
