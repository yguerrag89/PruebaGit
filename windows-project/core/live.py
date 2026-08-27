from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import datetime
from math import floor

from .optimizer import Settings, category_for, max_codes_for
from .parser import CodeRecord
from .strict_scan import canonical_scan, parse_strict_scan


@dataclass
class Position:
    side: str
    slot: int
    enabled: bool = False
    kind: str = "LIBRE"
    pallet_seq: int = 0
    reserved_cbm: float = 0.0
    actual_cbm: float = 0.0
    reserved_codes: list[str] = field(default_factory=list)
    complete_codes: set[str] = field(default_factory=set)
    dedicated_code: str | None = None
    pallet_box_capacity: int | None = None
    pallet_target_boxes: int | None = None
    boxes_on_current_pallet: int = 0
    waiting_removal: bool = False
    removal_reason: str = ""

    @property
    def label(self):
        return f"{self.side}{self.slot:02d}"

    @property
    def is_free(self):
        return self.enabled and self.kind == "LIBRE" and not self.waiting_removal


class LiveUnload:
    """Descarga dirigida con posiciones físicas fijas I01-I10 y D01-D10.

    Principios de V2.2:
    - La caja se mueve; una tarima incompleta nunca cambia de posición.
    - Una tarima completa NO libera la posición automáticamente.
    - El operador confirma "POSICIÓN LISTA" después de retirar la tarima.
    - El supervisor habilita posiciones adicionales durante la descarga.
    - Se conserva trazabilidad de barcode único para detectar duplicados.
    """

    MAX_PER_SIDE = 10

    def __init__(
        self,
        records: list[CodeRecord],
        settings: Settings,
        initial_left: int = 5,
        initial_right: int = 5,
    ):
        self.settings = settings
        self.records = {r.code.upper(): r for r in records}
        self.received = {r.code.upper(): 0 for r in records}
        self.position_for_code: dict[str, str] = {}
        self.positions: list[Position] = []
        initial_left = max(0, min(self.MAX_PER_SIDE, int(initial_left)))
        initial_right = max(0, min(self.MAX_PER_SIDE, int(initial_right)))
        for side, enabled_count in (("I", initial_left), ("D", initial_right)):
            for slot in range(1, self.MAX_PER_SIDE + 1):
                self.positions.append(Position(side=side, slot=slot, enabled=slot <= enabled_count))

        self.history: list[dict] = []          # cajas aceptadas
        self.events: list[dict] = []           # todos los intentos
        self.closed_pallets: list[dict] = []
        self.alerts: list[str] = []
        self.scanned_unique_barcodes: dict[str, dict] = {}
        self.peak_positions = 0
        self.enable_events: list[dict] = []

        # Percentil sencillo de cantidad de cajas para priorizar distancia.
        self._box_counts = sorted(max(1, r.boxes) for r in records) or [1]

    @staticmethod
    def _timestamp():
        return datetime.now().strftime("%H:%M:%S")

    @staticmethod
    def canonical_scan(scan: str) -> str:
        return canonical_scan(scan)

    def resolve_code(self, scan: str):
        parsed = parse_strict_scan(scan, self.records)
        return parsed.code or None

    def _is_unique_box_barcode(self, raw_scan: str, code: str) -> bool:
        parsed = parse_strict_scan(raw_scan, self.records)
        return parsed.valid and parsed.code == code

    def _record_event(
        self,
        raw_scan: str,
        code: str | None,
        position: str | None,
        status: str,
        message: str,
        accepted: bool,
        received: int | None = None,
        expected: int | None = None,
        pallet_id: str | None = None,
        unique_box_id: bool | None = None,
        normalized_scan: str | None = None,
    ):
        raw_canonical = self.canonical_scan(raw_scan)
        evt = {
            "N": len(self.events) + 1,
            "Hora": self._timestamp(),
            "Escaneo": normalized_scan or raw_canonical,
            "Escaneo bruto": raw_canonical,
            "Código": code or "",
            "Posición": position or "",
            "Estado": status,
            "Mensaje": message,
            "Recibidas": received if received is not None else "",
            "Esperadas": expected if expected is not None else "",
            "Tarima": pallet_id or "",
            "Caja individual": unique_box_id if unique_box_id is not None else "",
        }
        self.events.append(evt)
        if accepted:
            self.history.append(evt.copy())
        return evt

    @staticmethod
    def _current_pallet_id(position: Position) -> str:
        return f"{position.label}-{position.pallet_seq + 1:02d}"

    def _find_position(self, label: str | None) -> Position | None:
        if not label:
            return None
        label = str(label).upper().strip()
        return next((p for p in self.positions if p.label == label), None)

    def _update_peak(self):
        occupied = sum(1 for p in self.positions if p.enabled and (p.kind != "LIBRE" or p.waiting_removal))
        self.peak_positions = max(self.peak_positions, occupied)

    def enabled_count(self, side: str | None = None):
        return sum(1 for p in self.positions if p.enabled and (side is None or p.side == side))

    def enable_next(self, side: str):
        side = side.upper().strip()
        if side not in ("I", "D"):
            return None
        p = next((x for x in self.positions if x.side == side and not x.enabled), None)
        if p is None:
            return None
        p.enabled = True
        self.enable_events.append({"Hora": self._timestamp(), "Posición": p.label, "Acción": "HABILITADA"})
        return p.label

    def _movement_percentile(self, boxes: int) -> float:
        count = sum(1 for x in self._box_counts if x <= max(1, boxes))
        return count / max(1, len(self._box_counts))

    def _desired_slot(self, r: CodeRecord, force_far=False) -> int:
        enabled_slots = [p.slot for p in self.positions if p.enabled]
        max_enabled_slot = max(enabled_slots) if enabled_slots else self.MAX_PER_SIDE
        if force_far:
            return max_enabled_slot
        percentile = self._movement_percentile(r.boxes)
        # Más cajas => menor número de posición => menor recorrido.
        desired = round(max_enabled_slot - percentile * (max_enabled_slot - 1))
        return max(1, min(max_enabled_slot, desired))

    def _free_positions(self):
        return [p for p in self.positions if p.is_free]

    def _choose_free(self, r: CodeRecord, force_far=False):
        free = self._free_positions()
        if not free:
            return None
        desired = self._desired_slot(r, force_far=force_far)
        # Desempate: lado con menos posiciones ocupadas y después I/D estable.
        occupied_side = {
            side: sum(1 for p in self.positions if p.side == side and p.enabled and not p.is_free)
            for side in ("I", "D")
        }
        free.sort(key=lambda p: (abs(p.slot - desired), occupied_side[p.side], p.slot, p.side))
        return free[0]

    def _reset_position(self, p: Position):
        enabled = p.enabled
        side, slot, seq = p.side, p.slot, p.pallet_seq
        p.kind = "LIBRE"
        p.reserved_cbm = 0.0
        p.actual_cbm = 0.0
        p.reserved_codes = []
        p.complete_codes = set()
        p.dedicated_code = None
        p.pallet_box_capacity = None
        p.pallet_target_boxes = None
        p.boxes_on_current_pallet = 0
        p.waiting_removal = False
        p.removal_reason = ""
        p.enabled = enabled
        p.side, p.slot, p.pallet_seq = side, slot, seq

    def _mark_waiting_removal(self, p: Position, reason: str):
        if p.waiting_removal:
            return
        p.waiting_removal = True
        p.removal_reason = reason

    def _archive_current_pallet(self, p: Position, reason: str):
        p.pallet_seq += 1
        self.closed_pallets.append({
            "Posición": p.label,
            "Tarima": f"{p.label}-{p.pallet_seq:02d}",
            "Tipo": p.kind,
            "CBM real estimado": round(p.actual_cbm, 3),
            "CBM reservado": round(p.reserved_cbm, 3),
            "Códigos": ", ".join(p.reserved_codes) if p.reserved_codes else (p.dedicated_code or ""),
            "Motivo cierre": reason,
            "Hora retiro confirmada": self._timestamp(),
        })

    def mark_position_ready(self, label: str):
        """Operador confirma que la tarima salió y el lugar quedó listo."""
        p = self._find_position(label)
        if p is None:
            return {"ok": False, "message": "Posición no encontrada"}
        if not p.waiting_removal:
            return {"ok": False, "message": f"{p.label} no está pendiente de retiro"}

        kind_before = p.kind
        code = p.dedicated_code
        reason = p.removal_reason or "Retiro confirmado por operador"
        self._archive_current_pallet(p, reason)

        # Código dedicado con cajas pendientes: misma posición, nueva tarima.
        if kind_before == "DEDICADA" and code:
            r = self.records[code]
            remaining = max(0, r.boxes - self.received[code])
            if remaining > 0:
                p.waiting_removal = False
                p.removal_reason = ""
                p.actual_cbm = 0.0
                p.boxes_on_current_pallet = 0
                p.complete_codes.discard(code)
                cap = p.pallet_box_capacity or 1
                p.pallet_target_boxes = min(cap, remaining)
                p.reserved_cbm = p.pallet_target_boxes * r.cbm_per_box
                self.position_for_code[code] = p.label
                return {
                    "ok": True,
                    "position": p.label,
                    "message": f"{p.label} lista. Continúa {code} en una nueva tarima.",
                    "continues": True,
                }

        # Resto: la posición vuelve a estar verdaderamente libre.
        for c in list(self.position_for_code):
            if self.position_for_code.get(c) == p.label:
                self.position_for_code.pop(c, None)
        self._reset_position(p)
        return {"ok": True, "position": p.label, "message": f"{p.label} quedó LIBRE", "continues": False}

    def _position_code_limit(self, p: Position):
        if not p.reserved_codes:
            return self.settings.max_codes_small
        limits = [
            max_codes_for(category_for(self.records[c].cbm, self.settings, self.records[c].boxes == 1), self.settings)
            for c in p.reserved_codes
        ]
        return min(limits) if limits else self.settings.max_codes_small

    def _reserve_new_code(self, code: str):
        r = self.records[code]

        # Unitarios: solo con unitarios, preferentemente lejos por baja frecuencia.
        if r.boxes == 1:
            for p in self.positions:
                if not p.enabled or p.waiting_removal:
                    continue
                if (p.kind == "UNIT"
                        and len(p.reserved_codes) < self.settings.max_codes_unit
                        and p.reserved_cbm + r.cbm <= self.settings.target_capacity + 1e-9):
                    p.reserved_codes.append(code)
                    p.reserved_cbm += r.cbm
                    self.position_for_code[code] = p.label
                    return p
            p = self._choose_free(r, force_far=True)
            if p is None:
                return None
            p.kind = "UNIT"
            p.reserved_codes = [code]
            p.reserved_cbm = r.cbm
            self.position_for_code[code] = p.label
            self._update_peak()
            return p

        cat = category_for(r.cbm, self.settings, False)

        # Grande o superior a una tarima: posición dedicada.
        if r.cbm > self.settings.target_capacity or cat == "G":
            p = self._choose_free(r, force_far=False)
            if p is None:
                return None
            unit_cbm = max(r.cbm_per_box, 1e-9)
            p.kind = "DEDICADA"
            p.dedicated_code = code
            p.reserved_codes = [code]
            p.pallet_box_capacity = max(1, floor(self.settings.target_capacity / unit_cbm))
            p.pallet_target_boxes = min(r.boxes, p.pallet_box_capacity)
            p.reserved_cbm = p.pallet_target_boxes * unit_cbm
            self.position_for_code[code] = p.label
            self._update_peak()
            return p

        # Resto: reservar CBM total desde la primera caja y buscar mejor hueco existente.
        item_limit = max_codes_for(cat, self.settings)
        best = None
        best_gap = None
        for p in self.positions:
            if not p.enabled or p.waiting_removal or p.kind != "MIX":
                continue
            existing_limits = [
                max_codes_for(category_for(self.records[c].cbm, self.settings, False), self.settings)
                for c in p.reserved_codes
            ]
            limit = min(existing_limits + [item_limit]) if existing_limits else item_limit
            if len(p.reserved_codes) >= limit:
                continue
            if p.reserved_cbm + r.cbm > self.settings.target_capacity + 1e-9:
                continue
            gap = self.settings.target_capacity - (p.reserved_cbm + r.cbm)
            if best is None or gap < best_gap:
                best, best_gap = p, gap
        if best is None:
            best = self._choose_free(r, force_far=False)
            if best is None:
                return None
            best.kind = "MIX"
            self._update_peak()
        best.reserved_codes.append(code)
        best.reserved_cbm += r.cbm
        self.position_for_code[code] = best.label
        return best

    def _maybe_mark_complete(self, p: Position):
        """Marca retiro cuando la tarima ya no debe recibir más mercancía."""
        if p.waiting_removal or p.kind == "LIBRE":
            return

        if p.kind == "DEDICADA":
            code = p.dedicated_code
            if not code:
                return
            r = self.records[code]
            remaining = r.boxes - self.received[code]
            target = p.pallet_target_boxes or p.pallet_box_capacity or r.boxes
            # Tarima física llena o código totalmente terminado.
            if p.boxes_on_current_pallet >= target or remaining <= 0:
                reason = "Tarima dedicada llena" if remaining > 0 else "Código completo"
                self._mark_waiting_removal(p, reason)
            return

        all_complete = bool(p.reserved_codes) and set(p.reserved_codes).issubset(p.complete_codes)
        if not all_complete:
            return

        if p.kind == "UNIT":
            at_limit = (
                len(p.reserved_codes) >= self.settings.max_codes_unit
                or p.reserved_cbm >= self.settings.target_capacity * 0.98
            )
            if at_limit:
                self._mark_waiting_removal(p, "Tarima de unitarios completa")
            return

        if p.kind == "MIX":
            limit = self._position_code_limit(p)
            at_limit = (
                len(p.reserved_codes) >= limit
                or p.reserved_cbm >= self.settings.target_capacity * 0.98
            )
            if at_limit:
                self._mark_waiting_removal(p, "Tarima mixta completa")

    def _mark_all_remaining_when_container_complete(self):
        received, expected = self.progress()
        if received != expected:
            return
        for p in self.positions:
            if p.enabled and p.kind != "LIBRE" and not p.waiting_removal:
                self._mark_waiting_removal(p, "Contenedor completo")

    def scan(self, raw_scan: str):
        raw_scan = raw_scan or ""
        parsed = parse_strict_scan(raw_scan, self.records)
        canonical = parsed.raw_canonical
        if not parsed.valid:
            msg = parsed.message or "No se pudo identificar la caja individual"
            self.alerts.append(f"{parsed.status}: {canonical}")
            received = self.received.get(parsed.code) if parsed.code else None
            expected = self.records[parsed.code].boxes if parsed.code in self.records else None
            self._record_event(
                raw_scan, parsed.code or None, None, parsed.status or "INVÁLIDA", msg, False,
                received, expected, unique_box_id=False,
                normalized_scan=parsed.normalized_barcode or None,
            )
            return {
                "ok": False,
                "status": parsed.status or "INVÁLIDA",
                "message": msg,
                "code": parsed.code,
                "scan": parsed.normalized_barcode or canonical,
                "received": received if received is not None else 0,
                "expected": expected if expected is not None else 0,
            }

        code = parsed.code
        normalized = parsed.normalized_barcode
        r = self.records[code]
        if normalized in self.scanned_unique_barcodes:
            prior = self.scanned_unique_barcodes[normalized]
            pos = prior.get("position", "")
            msg = "DUPLICADA: esta caja ya fue escaneada"
            self.alerts.append(f"{msg}: {normalized}")
            self._record_event(raw_scan, code, pos, "DUPLICADA", msg, False,
                               self.received[code], r.boxes,
                               pallet_id=prior.get("pallet_id"), unique_box_id=True,
                               normalized_scan=normalized)
            return {
                "ok": False, "status": "DUPLICADA", "message": msg,
                "code": code, "scan": normalized, "position": pos,
                "first_scan_time": prior.get("time", ""),
                "received": self.received[code], "expected": r.boxes,
            }

        if self.received[code] >= r.boxes:
            msg = f"SOBRANTE: {code} ya recibió {r.boxes}/{r.boxes} cajas"
            self.alerts.append(msg)
            pos = self.position_for_code.get(code, "")
            self._record_event(raw_scan, code, pos, "SOBRANTE", msg, False,
                               self.received[code], r.boxes)
            return {
                "ok": False, "status": "SOBRANTE", "message": msg,
                "code": code, "scan": normalized, "position": pos,
                "received": self.received[code], "expected": r.boxes,
            }

        pos_label = self.position_for_code.get(code)
        p = self._find_position(pos_label)
        if p is None:
            p = self._reserve_new_code(code)
        if p is None:
            msg = "SIN POSICIÓN LIBRE. Solicite al supervisor habilitar otra posición si físicamente es posible."
            self.alerts.append(msg + " Código: " + code)
            self._record_event(raw_scan, code, None, "SIN POSICIÓN", msg, False,
                               self.received[code], r.boxes)
            return {"ok": False, "status": "SIN POSICIÓN", "message": msg, "code": code, "scan": normalized}

        if p.waiting_removal:
            msg = f"{p.label} está COMPLETA. Retire la tarima y marque POSICIÓN LISTA antes de continuar."
            self._record_event(raw_scan, code, p.label, "POSICIÓN PENDIENTE", msg, False,
                               self.received[code], r.boxes)
            return {
                "ok": False, "status": "POSICIÓN PENDIENTE", "message": msg,
                "code": code, "scan": normalized, "position": p.label,
                "received": self.received[code], "expected": r.boxes,
            }

        self.received[code] += 1
        p.actual_cbm += r.cbm_per_box
        p.boxes_on_current_pallet += 1
        remaining = r.boxes - self.received[code]

        self.scanned_unique_barcodes[normalized] = {
            "position": p.label,
            "pallet_id": self._current_pallet_id(p),
            "code": code,
            "box_number": parsed.box_number,
            "raw_scan": canonical,
            "time": self._timestamp(),
        }

        status = "OK"
        message = "CAJA REGISTRADA"
        if remaining == 0:
            p.complete_codes.add(code)
            status = "CÓDIGO COMPLETO"
            message = "CÓDIGO COMPLETO"

        self._maybe_mark_complete(p)
        # Si ésta fue la última caja del contenedor, todas las tarimas restantes
        # quedan listas para retiro físico.
        self._mark_all_remaining_when_container_complete()
        if p.waiting_removal:
            status = "TARIMA COMPLETA"
            message = "TARIMA COMPLETA · RETIRAR"

        self._record_event(raw_scan, code, p.label, status, message, True,
                           self.received[code], r.boxes,
                           pallet_id=self._current_pallet_id(p),
                           unique_box_id=True,
                           normalized_scan=normalized)

        self._update_peak()
        return {
            "ok": True,
            "status": status,
            "message": message,
            "code": code,
            "scan": normalized,
            "unique_box_id": True,
            "position": p.label,
            "received": self.received[code],
            "expected": r.boxes,
            "remaining": remaining,
            "waiting_removal": p.waiting_removal,
        }

    def progress(self):
        expected = sum(r.boxes for r in self.records.values())
        received = sum(self.received.values())
        return received, expected

    def position_pressure(self):
        enabled = sum(1 for p in self.positions if p.enabled)
        occupied = sum(1 for p in self.positions if p.enabled and (p.kind != "LIBRE" or p.waiting_removal))
        free = sum(1 for p in self.positions if p.is_free)
        pending = sum(1 for p in self.positions if p.enabled and p.waiting_removal)
        total = max(1, enabled)
        ratio = occupied / total
        if ratio >= 1:
            level = "SATURADA"
        elif ratio >= 0.90:
            level = "ALTA"
        elif ratio >= 0.75:
            level = "ATENCIÓN"
        else:
            level = "NORMAL"
        return {
            "occupied": occupied,
            "enabled": enabled,
            "free": free,
            "pending_removal": pending,
            "max_positions": self.MAX_PER_SIDE * 2,
            "available_to_enable": self.MAX_PER_SIDE * 2 - enabled,
            "ratio": ratio,
            "level": level,
            "peak": self.peak_positions,
            "left_enabled": self.enabled_count("I"),
            "right_enabled": self.enabled_count("D"),
        }

    def pending_removal_positions(self):
        return [p for p in self.positions if p.enabled and p.waiting_removal]

    def _position_progress(self, p: Position):
        if p.kind == "DEDICADA" and p.dedicated_code:
            target = p.pallet_target_boxes or p.pallet_box_capacity or 1
            current = p.boxes_on_current_pallet
            return current, target
        if p.reserved_codes:
            expected = sum(self.records[c].boxes for c in p.reserved_codes)
            current = sum(min(self.received[c], self.records[c].boxes) for c in p.reserved_codes)
            return current, expected
        return 0, 0

    def position_cards(self):
        rows = []
        for p in self.positions:
            current, target = self._position_progress(p)
            fill = (current / target) if target else 0
            if not p.enabled:
                state = "NO HABILITADA"
            elif p.waiting_removal:
                state = "COMPLETA"
            elif p.kind == "LIBRE":
                state = "LIBRE"
            elif fill >= 0.8:
                state = "PRÓXIMA"
            else:
                state = "EN PROCESO"

            if p.kind == "DEDICADA" and p.dedicated_code:
                title = p.dedicated_code
                detail = f"{current}/{target} cajas"
            elif p.kind == "UNIT":
                title = "UNITARIOS"
                detail = f"{len(p.reserved_codes)}/{self.settings.max_codes_unit} códigos"
            elif p.kind == "MIX":
                title = f"{len(p.reserved_codes)} código(s)"
                parts = []
                for c in p.reserved_codes[:3]:
                    parts.append(f"{c} {self.received[c]}/{self.records[c].boxes}")
                detail = " · ".join(parts)
                if len(p.reserved_codes) > 3:
                    detail += f" · +{len(p.reserved_codes)-3}"
            else:
                title = "LIBRE" if p.enabled else "NO HABILITADA"
                detail = ""

            rows.append({
                "Posición": p.label,
                "Lado": p.side,
                "Distancia": p.slot,
                "Habilitada": p.enabled,
                "Estado": state,
                "Tipo": p.kind,
                "Título": title,
                "Detalle": detail,
                "Actual": current,
                "Objetivo": target,
                "CBM reservado": round(p.reserved_cbm, 3),
                "CBM recibido": round(p.actual_cbm, 3),
            })
        return rows

    def snapshot(self):
        return [
            {
                "Posición": r["Posición"],
                "Estado": r["Estado"],
                "Tipo": r["Tipo"],
                "Contenido": r["Título"],
                "Detalle": r["Detalle"],
                "CBM reservado": r["CBM reservado"],
                "CBM recibido": r["CBM recibido"],
            }
            for r in self.position_cards()
        ]

    def recent_scans(self, n=5):
        return self.events[-n:][::-1]

    def _record_signature(self):
        return [
            {"code": code, "boxes": int(record.boxes)}
            for code, record in sorted(self.records.items())
        ]

    def to_state(self) -> dict:
        """Estado JSON seguro para continuar una descarga después de reiniciar."""
        positions = []
        for position in self.positions:
            row = asdict(position)
            row["complete_codes"] = sorted(position.complete_codes)
            positions.append(row)
        return {
            "state_version": 1,
            "record_signature": self._record_signature(),
            "received": dict(self.received),
            "position_for_code": dict(self.position_for_code),
            "positions": positions,
            "history": list(self.history),
            "events": list(self.events),
            "closed_pallets": list(self.closed_pallets),
            "alerts": list(self.alerts),
            "scanned_unique_barcodes": dict(self.scanned_unique_barcodes),
            "peak_positions": int(self.peak_positions),
            "enable_events": list(self.enable_events),
        }

    @classmethod
    def from_state(cls, records, settings, state: dict):
        """Restaura un estado solo si corresponde exactamente al Packing List."""
        if state.get("state_version") != 1:
            raise ValueError("Versión de sesión no compatible")
        live = cls(records, settings, initial_left=0, initial_right=0)
        if state.get("record_signature") != live._record_signature():
            raise ValueError("La sesión guardada no corresponde al Packing List cargado")

        live.received = {
            code: max(0, min(int((state.get("received") or {}).get(code, 0)), record.boxes))
            for code, record in live.records.items()
        }
        live.position_for_code = {
            str(code): str(label)
            for code, label in (state.get("position_for_code") or {}).items()
            if code in live.records
        }

        saved_positions = {
            f"{row.get('side', '')}{int(row.get('slot', 0)):02d}": row
            for row in state.get("positions") or []
            if row.get("side") in ("I", "D") and 1 <= int(row.get("slot", 0)) <= cls.MAX_PER_SIDE
        }
        for position in live.positions:
            row = saved_positions.get(position.label)
            if not row:
                continue
            position.enabled = bool(row.get("enabled", False))
            position.kind = str(row.get("kind", "LIBRE"))
            position.pallet_seq = max(0, int(row.get("pallet_seq", 0)))
            position.reserved_cbm = float(row.get("reserved_cbm", 0.0))
            position.actual_cbm = float(row.get("actual_cbm", 0.0))
            position.reserved_codes = [
                str(code) for code in row.get("reserved_codes") or [] if code in live.records
            ]
            position.complete_codes = {
                str(code) for code in row.get("complete_codes") or [] if code in live.records
            }
            dedicated = row.get("dedicated_code")
            position.dedicated_code = str(dedicated) if dedicated in live.records else None
            capacity = row.get("pallet_box_capacity")
            target = row.get("pallet_target_boxes")
            position.pallet_box_capacity = int(capacity) if capacity not in (None, "") else None
            position.pallet_target_boxes = int(target) if target not in (None, "") else None
            position.boxes_on_current_pallet = max(0, int(row.get("boxes_on_current_pallet", 0)))
            position.waiting_removal = bool(row.get("waiting_removal", False))
            position.removal_reason = str(row.get("removal_reason", ""))

        live.history = [dict(row) for row in state.get("history") or []]
        live.events = [dict(row) for row in state.get("events") or []]
        live.closed_pallets = [dict(row) for row in state.get("closed_pallets") or []]
        live.alerts = [str(row) for row in state.get("alerts") or []]
        live.scanned_unique_barcodes = {
            str(code): dict(value)
            for code, value in (state.get("scanned_unique_barcodes") or {}).items()
            if isinstance(value, dict)
        }
        live.peak_positions = max(0, int(state.get("peak_positions", 0)))
        live.enable_events = [dict(row) for row in state.get("enable_events") or []]
        return live
