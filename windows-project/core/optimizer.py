from __future__ import annotations
from dataclasses import dataclass, field, asdict
from math import floor, ceil
from typing import Iterable

from .parser import CodeRecord


@dataclass
class Settings:
    physical_capacity: float = 2.16
    target_capacity: float = 1.94
    large_ratio: float = 0.70
    medium_high_ratio: float = 0.45
    medium_ratio: float = 0.25
    max_codes_unit: int = 20
    max_codes_small: int = 4
    max_codes_medium: int = 3
    max_codes_medium_high: int = 2
    fixed_positions: int = 20


@dataclass
class Allocation:
    code: str
    boxes: int
    cbm: float
    category: str
    weight_per_box: float | None = None
    description: str = ""


@dataclass
class Pallet:
    pallet_id: str
    pallet_type: str
    allocations: list[Allocation] = field(default_factory=list)
    capacity: float = 1.94
    physical_capacity: float = 2.16

    @property
    def cbm(self):
        return sum(a.cbm for a in self.allocations)

    @property
    def boxes(self):
        return sum(a.boxes for a in self.allocations)

    @property
    def codes(self):
        return len({a.code for a in self.allocations})

    @property
    def occupancy(self):
        return self.cbm / self.physical_capacity if self.physical_capacity else 0

    @property
    def reserved_fill(self):
        return self.cbm / self.capacity if self.capacity else 0

    def as_dict(self):
        return {
            "Tarima": self.pallet_id,
            "Tipo": self.pallet_type,
            "Códigos": self.codes,
            "Cajas": self.boxes,
            "CBM": round(self.cbm, 3),
            "Ocupación física %": round(self.occupancy * 100, 1),
            "Detalle": " + ".join(f"{a.code} ({a.boxes})" for a in self.allocations),
        }


def category_for(cbm: float, settings: Settings, unit_code=False):
    if unit_code:
        return "U"
    ratio = cbm / settings.target_capacity if settings.target_capacity else 1
    if ratio >= settings.large_ratio:
        return "G"
    if ratio >= settings.medium_high_ratio:
        return "M1"
    if ratio >= settings.medium_ratio:
        return "M2"
    return "P"


def max_codes_for(category: str, settings: Settings):
    return {"U": settings.max_codes_unit, "G": 1, "M1": settings.max_codes_medium_high,
            "M2": settings.max_codes_medium, "P": settings.max_codes_small}.get(category, settings.max_codes_small)


def _new(pid, typ, s):
    return Pallet(pid, typ, capacity=s.target_capacity, physical_capacity=s.physical_capacity)


def build_static_plan(records: list[CodeRecord], settings: Settings):
    """Plan previo: unitarios separados, tarimas completas por código y BFD para remanentes."""
    unit_items = []
    mix_items = []
    pallets = []
    counters = {"G": 0, "M": 0, "U": 0}

    for r in records:
        if r.boxes == 1:
            unit_items.append(Allocation(r.code, 1, r.cbm, "U", r.weight_per_box, r.description))
            continue

        unit_cbm = r.cbm_per_box if r.cbm_per_box > 0 else r.cbm / max(r.boxes, 1)
        if unit_cbm <= 0:
            continue
        boxes_per_pallet = max(1, floor(settings.target_capacity / unit_cbm))
        if unit_cbm > settings.physical_capacity:
            boxes_per_pallet = 1

        remaining_boxes = r.boxes
        # Para códigos mayores a una tarima, crear tarimas exclusivas llenas.
        if r.cbm > settings.target_capacity:
            while remaining_boxes > boxes_per_pallet:
                take = boxes_per_pallet
                counters["G"] += 1
                p = _new(f"G{counters['G']:02d}", "Exclusiva", settings)
                p.allocations.append(Allocation(r.code, take, take * unit_cbm, "G", r.weight_per_box, r.description))
                pallets.append(p)
                remaining_boxes -= take

        rem_cbm = remaining_boxes * unit_cbm
        if remaining_boxes > 0:
            cat = category_for(rem_cbm, settings, False)
            item = Allocation(r.code, remaining_boxes, rem_cbm, cat, r.weight_per_box, r.description)
            if cat == "G":
                counters["G"] += 1
                p = _new(f"G{counters['G']:02d}", "Exclusiva", settings)
                p.allocations.append(item)
                pallets.append(p)
            else:
                mix_items.append(item)

    # Unitarios: best fit decreasing, máximo de códigos por tarima.
    for item in sorted(unit_items, key=lambda x: x.cbm, reverse=True):
        candidates = [p for p in pallets if p.pallet_type == "Unitarios" and p.codes < settings.max_codes_unit and p.cbm + item.cbm <= settings.target_capacity + 1e-9]
        if candidates:
            p = min(candidates, key=lambda x: settings.target_capacity - (x.cbm + item.cbm))
        else:
            counters["U"] += 1
            p = _new(f"U{counters['U']:02d}", "Unitarios", settings)
            pallets.append(p)
        p.allocations.append(item)

    # Resto: BFD respetando límite de códigos definido por el componente más restrictivo.
    mix_pallets = []
    for item in sorted(mix_items, key=lambda x: x.cbm, reverse=True):
        best = None
        best_gap = None
        for p in mix_pallets:
            current_limit = min([max_codes_for(a.category, settings) for a in p.allocations] + [max_codes_for(item.category, settings)])
            if p.codes >= current_limit:
                continue
            if p.cbm + item.cbm > settings.target_capacity + 1e-9:
                continue
            gap = settings.target_capacity - (p.cbm + item.cbm)
            if best is None or gap < best_gap:
                best, best_gap = p, gap
        if best is None:
            counters["M"] += 1
            best = _new(f"M{counters['M']:02d}", "Mixta", settings)
            mix_pallets.append(best)
            pallets.append(best)
        best.allocations.append(item)

    return pallets


def plan_summary(records, pallets, settings):
    total_cbm = sum(r.cbm for r in records)
    return {
        "códigos": len(records),
        "cajas": sum(r.boxes for r in records),
        "cbm": round(total_cbm, 3),
        "códigos_unitarios": sum(1 for r in records if r.boxes == 1),
        "tarimas_estimadas": len(pallets),
        "ocupación_promedio_%": round((total_cbm / (len(pallets) * settings.physical_capacity) * 100) if pallets else 0, 1),
        "tarimas_exclusivas": sum(1 for p in pallets if p.pallet_type == "Exclusiva"),
        "tarimas_mixtas": sum(1 for p in pallets if p.pallet_type == "Mixta"),
        "tarimas_unitarios": sum(1 for p in pallets if p.pallet_type == "Unitarios"),
    }


def transfer_layout_summary(records: list[CodeRecord], settings: Settings, foot_positions: int = 6):
    """Replica el plan físico V0.9 usado por la PDA.

    Los códigos grandes reservan posiciones dinámicas al pie; sus Uxxx no se
    preasignan a una tarima. Los demás códigos se empacan secuencialmente en el
    tendido final para conocer cuántas tarimas deben colocarse antes de iniciar.
    """
    direct_codes = []
    estimated_direct_pallets = 0
    tendido_pallets = 0
    used = 0.0
    target = max(float(settings.target_capacity), 1e-9)

    for record in records:
        is_direct = record.cbm >= target * settings.large_ratio
        unit = max(float(record.cbm_per_box), 0.0)
        if is_direct:
            direct_codes.append(record.code)
            capacity = max(1, floor(target / max(unit, 1e-9)))
            estimated_direct_pallets += max(1, ceil(record.boxes / capacity))
            continue

        for _ in range(record.boxes):
            if used > 1e-9 and used + unit > target + 1e-9:
                tendido_pallets += 1
                used = 0.0
            elif used <= 1e-9:
                # Abrir una nueva tarima al colocar la primera caja planificada.
                tendido_pallets += 1
            used += unit

    foot_positions = max(0, min(20, int(foot_positions)))
    initial_direct = min(len(direct_codes), foot_positions)
    return {
        "direct_codes": len(direct_codes),
        "direct_final_estimated": estimated_direct_pallets,
        "tendido_final": tendido_pallets,
        "foot_direct_initial": initial_direct,
        "foot_transfer_initial": 1,
        "foot_total_initial": initial_direct + 1,
        "physical_initial_total": tendido_pallets + initial_direct + 1,
        "final_total_estimated": tendido_pallets + estimated_direct_pallets,
        "direct_replacements": max(0, estimated_direct_pallets - initial_direct),
    }
