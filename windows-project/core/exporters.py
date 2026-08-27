from __future__ import annotations
import csv
from io import StringIO


def rows_to_csv(rows):
    if not rows:
        return b''
    buf=StringIO(newline='')
    w=csv.DictWriter(buf, fieldnames=list(rows[0].keys()))
    w.writeheader(); w.writerows(rows)
    return buf.getvalue().encode('utf-8-sig')
