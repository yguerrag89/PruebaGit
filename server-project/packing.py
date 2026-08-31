"""Strict Packing List admission shared by production and the local laboratory."""
import io
import math
import zipfile
from pathlib import Path

from core.parser import parse_xlsx_bytes, _find_header, norm_text
from core.strict_scan import canonical_scan
from core.xlsx_reader import read_xlsx_rows


class PackingError(ValueError):
    pass


def parse_strict_packing(content: bytes, filename: str):
    if not filename.lower().endswith(".xlsx"):
        raise PackingError("Cargue un Packing List .xlsx con un solo contenedor.")
    if not content or len(content) > 16 * 1024 * 1024:
        raise PackingError("El archivo está vacío o supera 16 MiB.")
    try:
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            members = archive.infolist()
            if len(members) > 1000 or sum(x.file_size for x in members) > 64 * 1024 * 1024:
                raise PackingError("El XLSX expandido es demasiado grande.")
        found_sheet = False
        for sheet_name, rows in read_xlsx_rows(content, max_rows=None, max_cols=40):
            header = _find_header(rows)
            if not header:
                continue
            found_sheet = True
            _, start, columns = header
            if not all(k in columns for k in ("code", "boxes", "cbm")):
                raise PackingError(f"{sheet_name}: faltan código, cajas o CBM.")
            for number, row in enumerate(rows[start + 1:], start=start + 2):
                code, boxes, cbm = [norm_text(row[columns[k]]) for k in ("code", "boxes", "cbm")]
                if not code and not boxes and not cbm:
                    continue
                try:
                    count, volume = float(boxes), float(cbm)
                    if (not code or code.upper() in {"TOTAL", "TOTALES", "合计", "总计"}
                            or not math.isfinite(count) or not count.is_integer() or not 1 <= count <= 999
                            or not math.isfinite(volume) or volume <= 0):
                        raise ValueError()
                except ValueError:
                    raise PackingError(
                        f"{sheet_name}, fila {number}: corrija cajas/CBM o retire las filas de totales; no se omitió la fila."
                    )
        if not found_sheet:
            raise PackingError("No se encontró una hoja con las columnas código, cajas y CBM.")
        containers = parse_xlsx_bytes(content, Path(filename).name)
    except PackingError:
        raise
    except (ValueError, TypeError, KeyError, zipfile.BadZipFile, OSError) as exc:
        raise PackingError("El Packing List no es un XLSX válido.") from exc
    if len(containers) != 1:
        raise PackingError("Separe el archivo para dejar un solo contenedor por carga.")
    container = containers[0]
    if container.warnings:
        raise PackingError("Revise el Packing List: " + "; ".join(container.warnings[:8]))
    if not container.records or container.total_boxes > 10_000:
        raise PackingError("El laboratorio admite hasta 10 000 cajas por descarga.")
    if any(not 1 <= r.boxes <= 999 or r.code != canonical_scan(r.code)
           or not math.isfinite(r.cbm_per_box) or r.cbm_per_box <= 0
           or not math.isfinite(r.cbm) or r.cbm <= 0 for r in container.records):
        raise PackingError("Todos los códigos necesitan entre 1 y 999 cajas y un volumen positivo.")
    return container
