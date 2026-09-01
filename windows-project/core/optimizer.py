from __future__ import annotations

from dataclasses import dataclass, field
from math import floor

from .parser import CodeRecord


EPS = 1e-9


@dataclass
class Settings:
    physical_capacity: float = 2.16
    target_capacity: float = 1.94
    max_weight: float = 1000.0
    desirable_min_weight: float = 600.0
    heavy_low_threshold: float = 900.0
    # Compatibilidad con AUTO/BUFFER y sesiones anteriores. TRASLADO V0.14
    # ya no usa límites de códigos ni el porcentaje "grande".
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
    box_numbers: tuple[int, ...] = ()

    @property
    def weight(self) -> float:
        return 0.0 if self.weight_per_box is None else self.boxes * self.weight_per_box


@dataclass
class Pallet:
    pallet_id: str
    pallet_type: str
    allocations: list[Allocation] = field(default_factory=list)
    capacity: float = 1.94
    physical_capacity: float = 2.16
    max_weight: float = 1000.0
    rack_class: str = "MIXTA · NIVEL MEDIO"
    note: str = ""

    @property
    def cbm(self):
        return sum(a.cbm for a in self.allocations)

    @property
    def weight(self):
        return sum(a.weight for a in self.allocations)

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
            "Formación": self.pallet_type,
            "Códigos": self.codes,
            "Cajas": self.boxes,
            "CBM": round(self.cbm, 3),
            "Peso kg": round(self.weight, 1) if self.weight else "Sin dato",
            "Ocupación física %": round(self.occupancy * 100, 1),
            "Rack sugerido": self.rack_class,
            "Detalle": " + ".join(f"{a.code} ({a.boxes})" for a in self.allocations),
            "Nota": self.note,
        }


@dataclass(frozen=True)
class _Group:
    code: str
    box_numbers: tuple[int, ...]
    cbm: float
    weight: float
    unit_weight: float | None
    description: str
    unitary_family: bool = False
    exceptional_pair: bool = False


@dataclass
class TransferPlan:
    pallets: list[Pallet]
    tendido_pallets: list[Pallet]
    direct_pallets: list[Pallet]
    assignments: dict[str, str]
    direct_codes: set[str]
    exceptional_pairs: set[str]


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
    """Compatibilidad con AUTO/BUFFER; TRASLADO V0.14 no llama esta regla."""
    return {"U": settings.max_codes_unit, "G": 1, "M1": settings.max_codes_medium_high,
            "M2": settings.max_codes_medium, "P": settings.max_codes_small}.get(category, settings.max_codes_small)


def _box_capacity(record: CodeRecord, settings: Settings) -> int:
    volume_cap = (floor(settings.target_capacity / record.cbm_per_box + EPS)
                  if record.cbm_per_box > EPS else record.boxes)
    weight_cap = (floor(settings.max_weight / record.weight_per_box + EPS)
                  if record.weight_per_box and record.weight_per_box > EPS else record.boxes)
    return max(1, min(volume_cap, weight_cap))


def _is_direct(record: CodeRecord, settings: Settings) -> bool:
    """Solo va al pie un código que requiere dos o más tarimas completas."""
    return record.boxes > _box_capacity(record, settings)


def _groups_for(record: CodeRecord, unitary_family: bool = False) -> list[_Group]:
    unit_weight = record.weight_per_box
    weight = 0.0 if unit_weight is None else float(unit_weight)
    return [
        _Group(record.code, (box,), max(0.0, record.cbm_per_box), weight,
               unit_weight, record.description, unitary_family=unitary_family)
        for box in range(1, record.boxes + 1)
    ]


def _feasible(groups: list[_Group], group: _Group, settings: Settings) -> bool:
    cbm = sum(x.cbm for x in groups) + group.cbm
    weight = sum(x.weight for x in groups) + group.weight
    return cbm <= settings.target_capacity + EPS and weight <= settings.max_weight + EPS


def _pack_variant(groups: list[_Group], settings: Settings, prefer_same_code: bool) -> list[list[_Group]]:
    ordered = sorted(groups, key=lambda x: (-x.cbm, -x.weight, x.code, x.box_numbers))
    pallets: list[list[_Group]] = []
    for group in ordered:
        candidates = []
        for idx, pallet in enumerate(pallets):
            if not _feasible(pallet, group, settings):
                continue
            new_cbm = sum(x.cbm for x in pallet) + group.cbm
            new_weight = sum(x.weight for x in pallet) + group.weight
            same = any(x.code == group.code for x in pallet)
            if prefer_same_code:
                score = (0 if same else 1, settings.target_capacity - new_cbm,
                         settings.max_weight - new_weight, idx)
            else:
                score = (settings.target_capacity - new_cbm,
                         settings.max_weight - new_weight, 0 if same else 1, idx)
            candidates.append((score, idx))
        if candidates:
            pallets[min(candidates)[1]].append(group)
        else:
            pallets.append([group])
    return pallets


def _packing_score(pallets: list[list[_Group]], settings: Settings):
    code_locations: dict[str, set[int]] = {}
    diversity = 0
    underweight_deficit = 0.0
    unused_square = 0.0
    for idx, pallet in enumerate(pallets):
        codes = {x.code for x in pallet}
        diversity += max(0, len(codes) - 1)
        for code in codes:
            code_locations.setdefault(code, set()).add(idx)
        unused_square += (settings.target_capacity - sum(x.cbm for x in pallet)) ** 2
        known_weight = sum(x.weight for x in pallet)
        if known_weight > EPS:
            underweight_deficit += max(0.0, settings.desirable_min_weight - known_weight)
    splits = sum(max(0, len(locations) - 1) for locations in code_locations.values())
    return len(pallets), splits, diversity, round(underweight_deficit, 6), round(unused_square, 9)


def _pack(groups: list[_Group], settings: Settings) -> list[list[_Group]]:
    if not groups:
        return []
    variants = [_pack_variant(groups, settings, False), _pack_variant(groups, settings, True)]
    return min(variants, key=lambda x: _packing_score(x, settings))


def _rack_class(pallet: Pallet, settings: Settings) -> str:
    if pallet.weight >= settings.heavy_low_threshold - EPS:
        return "PESO ALTO · NIVEL BAJO"
    if pallet.pallet_type == "PIE · CÓDIGO MULTITARIMA" and pallet.codes == 1:
        return "RESERVA HOMOGÉNEA · NIVEL ALTO*"
    if pallet.pallet_type.startswith("TENDIDO · UNITARIOS") or pallet.codes >= 8:
        return "SURTIDO MULTICÓDIGO · NIVEL BAJO"
    if pallet.codes >= 3:
        return "SURTIDO · NIVEL BAJO/MEDIO"
    return "MIXTA · NIVEL MEDIO"


def _to_pallet(groups: list[_Group], pallet_id: str, pallet_type: str, settings: Settings) -> Pallet:
    by_code: dict[str, list[_Group]] = {}
    for group in groups:
        by_code.setdefault(group.code, []).append(group)
    allocations = []
    for code in sorted(by_code):
        items = by_code[code]
        boxes = tuple(box for item in items for box in item.box_numbers)
        allocations.append(Allocation(
            code=code, boxes=len(boxes), cbm=sum(x.cbm for x in items),
            category="U" if all(x.unitary_family for x in items) else "M",
            weight_per_box=items[0].unit_weight, description=items[0].description,
            box_numbers=boxes,
        ))
    note = ""
    if any(x.exceptional_pair for x in groups):
        note = "Incluye excepcionalmente un código completo de 2 cajas; no se dividió el par."
    pallet = Pallet(pallet_id, pallet_type, allocations, settings.target_capacity,
                    settings.physical_capacity, settings.max_weight, note=note)
    pallet.rack_class = _rack_class(pallet, settings)
    return pallet


def build_transfer_plan(records: list[CodeRecord], settings: Settings) -> TransferPlan:
    """Plan global determinista para Windows y la PDA V0.14.

    Objetivo lexicográfico de la heurística: menos tarimas, menos divisiones de
    código, menos diversidad. Los unitarios se aíslan y un código de dos cajas
    solo entra como par indivisible si no incrementa la cantidad total.
    """
    direct_records = [r for r in records if _is_direct(r, settings)]
    non_direct = [r for r in records if not _is_direct(r, settings)]
    unit_records = [r for r in non_direct if r.boxes == 1]
    general_records = [r for r in non_direct if r.boxes != 1]

    unit_groups = [g for r in unit_records for g in _groups_for(r, True)]
    general_groups = [g for r in general_records for g in _groups_for(r, False)]
    unit_packed = _pack(unit_groups, settings)
    general_packed = _pack(general_groups, settings)
    exceptional_pairs: set[str] = set()

    pair_records = sorted((r for r in general_records if r.boxes == 2),
                          key=lambda r: (r.cbm, r.code))
    current_total = len(unit_packed) + len(general_packed)
    for record in pair_records:
        remaining_general = [g for g in general_groups if g.code != record.code]
        pair = _Group(record.code, (1, 2), record.cbm, (record.weight_per_box or 0.0) * 2,
                      record.weight_per_box, record.description,
                      unitary_family=True, exceptional_pair=True)
        if pair.cbm > settings.target_capacity + EPS or pair.weight > settings.max_weight + EPS:
            continue
        candidate_unit = unit_groups + [pair]
        packed_unit = _pack(candidate_unit, settings)
        packed_general = _pack(remaining_general, settings)
        candidate_total = len(packed_unit) + len(packed_general)
        if candidate_total < current_total or (
                candidate_total == current_total and len(packed_unit) == len(unit_packed)):
            exceptional_pairs.add(record.code)
            unit_groups = candidate_unit
            general_groups = remaining_general
            unit_packed, general_packed = packed_unit, packed_general
            current_total = candidate_total

    tendido: list[Pallet] = []
    assignments: dict[str, str] = {}
    seq = 1
    for packed_collection, typ in [
        (general_packed, "TENDIDO · OPTIMIZADA"),
        (unit_packed, "TENDIDO · UNITARIOS"),
    ]:
        for packed in packed_collection:
            label = f"T-{seq:02d}"
            pallet = _to_pallet(packed, label, typ, settings)
            tendido.append(pallet)
            for allocation in pallet.allocations:
                for box in allocation.box_numbers:
                    assignments[f"{allocation.code}U{box:03d}"] = label
            seq += 1

    direct: list[Pallet] = []
    direct_seq = 1
    for record in sorted(direct_records, key=lambda r: (-r.cbm, r.code)):
        capacity = _box_capacity(record, settings)
        for start in range(1, record.boxes + 1, capacity):
            boxes = tuple(range(start, min(record.boxes, start + capacity - 1) + 1))
            groups = [
                _Group(record.code, (box,), record.cbm_per_box,
                       record.weight_per_box or 0.0, record.weight_per_box, record.description)
                for box in boxes
            ]
            # La identidad T real se asigna en la PDA cuando aparece el código;
            # este rótulo evita prometer un T-xx que dependerá del orden de llegada.
            label = f"PIE-EST-{direct_seq:02d}"
            direct.append(_to_pallet(groups, label, "PIE · CÓDIGO MULTITARIMA", settings))
            direct_seq += 1

    return TransferPlan(
        pallets=tendido + direct,
        tendido_pallets=tendido,
        direct_pallets=direct,
        assignments=assignments,
        direct_codes={r.code for r in direct_records},
        exceptional_pairs=exceptional_pairs,
    )


def build_static_plan(records: list[CodeRecord], settings: Settings):
    return build_transfer_plan(records, settings).pallets


def plan_summary(records, pallets, settings):
    total_cbm = sum(r.cbm for r in records)
    plan = build_transfer_plan(records, settings)
    weights = [p.weight for p in pallets if p.weight > 0]
    return {
        "códigos": len(records),
        "cajas": sum(r.boxes for r in records),
        "cbm": round(total_cbm, 3),
        "códigos_unitarios": sum(1 for r in records if r.boxes == 1),
        "tarimas_estimadas": len(pallets),
        "ocupación_promedio_%": round((total_cbm / (len(pallets) * settings.physical_capacity) * 100) if pallets else 0, 1),
        "tarimas_exclusivas": len(plan.direct_pallets),
        "tarimas_mixtas": sum(1 for p in plan.tendido_pallets if "UNITARIOS" not in p.pallet_type),
        "tarimas_unitarios": sum(1 for p in plan.tendido_pallets if "UNITARIOS" in p.pallet_type),
        "peso_máximo_kg": round(max(weights), 1) if weights else None,
        "pares_excepcionales": len(plan.exceptional_pairs),
    }


def transfer_layout_summary(records: list[CodeRecord], settings: Settings, foot_positions: int = 6):
    plan = build_transfer_plan(records, settings)
    foot_positions = max(0, min(20, int(foot_positions)))
    initial_direct = min(len(plan.direct_codes), foot_positions)
    estimated_direct = len(plan.direct_pallets)
    tendido = len(plan.tendido_pallets)
    return {
        "direct_codes": len(plan.direct_codes),
        "direct_final_estimated": estimated_direct,
        "tendido_final": tendido,
        "foot_direct_initial": initial_direct,
        "foot_transfer_initial": 1,
        "foot_total_initial": initial_direct + 1,
        "physical_initial_total": tendido + initial_direct + 1,
        "final_total_estimated": tendido + estimated_direct,
        "direct_replacements": max(0, estimated_direct - initial_direct),
        "exceptional_pairs": len(plan.exceptional_pairs),
        "strategy": "GLOBAL_BFD_V014",
    }
