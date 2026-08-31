"""Operational PDA simulator for the local lab.

It intentionally models only TRASLADO from V0.12. Server validation remains the
same production protocol. The physical Q9 is still required for final acceptance.
"""
from __future__ import annotations
from datetime import datetime, timezone
from math import floor

from core.strict_scan import canonical_scan, parse_strict_scan, record_signature
from core.wms_location import valid_wms_temporary


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def initial_state(manifest: dict, left: int, right: int) -> dict:
    settings = manifest["settings"]
    target = float(settings["target_capacity"])
    large_ratio = float(settings["large_ratio"])
    planned, direct, used, pallet = {}, set(), 0.0, 1
    for record in manifest["records"]:
        code, boxes, cbm, unit = record["code"], int(record["boxes"]), float(record["cbm"]), float(record["cbm_per_box"])
        if cbm >= target * large_ratio:
            direct.add(code)
            continue
        for number in range(1, boxes + 1):
            if used > 1e-9 and used + unit > target + 1e-9:
                pallet += 1
                used = 0.0
            planned[f"{code}U{number:03d}"] = f"T-{pallet:02d}"
            used += unit
    next_pallet = (max((int(x[2:]) for x in planned.values()), default=0) + 1)
    positions = [f"I{i:02d}" for i in range(1, left + 1)] + [f"D{i:02d}" for i in range(1, right + 1)]
    pallets = {}
    for pallet_id in sorted(set(planned.values())):
        expected = sum(1 for destination in planned.values() if destination == pallet_id)
        pallets[pallet_id] = {"direct": False, "code": "", "physical_position": "",
                              "expected": expected, "original_expected": expected, "scanned": 0,
                              "validated": False, "retired": False, "temporary": "",
                              "verified_by": "", "verified_at": "", "closure_reason": ""}
    return {
        "manifest": manifest, "planned": planned, "direct_codes": sorted(direct),
        "next_pallet": next_pallet, "positions": positions, "pallets": pallets,
        "boxes": {}, "active_transfer": 1, "closed_transfers": [], "events": [],
        "revision": 0, "acknowledged": 0, "online": True, "sealed": False,
        "last_message": "Packing List cargado; todavía no hay cajas escaneadas.",
    }


def records(state):
    from core.parser import CodeRecord
    return {x["code"]: CodeRecord(**x) for x in state["manifest"]["records"]}


def add_event(state, *, accepted, status, message, scan="", barcode="", code="", position=""):
    state["events"].append({"id": len(state["events"]) + 1, "time": now(), "scan": scan,
                            "barcode": barcode, "code": code, "position": position,
                            "status": status, "message": message, "accepted": accepted})
    state["revision"] += 1
    state["last_message"] = message


def scan(state, raw: str):
    if state["sealed"]:
        raise ValueError("La descarga está cerrada; el laboratorio quedó en consulta.")
    parsed = parse_strict_scan(raw, records(state))
    if not parsed.valid:
        add_event(state, accepted=False, status=parsed.status, message=parsed.message,
                  scan=parsed.raw_canonical, barcode=parsed.normalized_barcode, code=parsed.code)
        return False
    barcode, code = parsed.normalized_barcode, parsed.code
    if barcode in state["boxes"]:
        destination = state["boxes"][barcode]["final_pallet"]
        add_event(state, accepted=False, status="DUPLICADA", message=f"YA ESCANEADA · destino {destination}",
                  scan=parsed.raw_canonical, barcode=barcode, code=code, position=destination)
        return False
    direct = code in state["direct_codes"]
    if direct:
        existing = next(((p, x) for p, x in state["pallets"].items()
                         if x["direct"] and x["code"] == code and not x["retired"]), None)
        if existing and (existing[1]["validated"] or existing[1]["scanned"] >= existing[1]["expected"]):
            add_event(state, accepted=False, status="TARIMA DIRECTA LISTA",
                      message=f"Verifique y retire {existing[0]} antes de continuar.",
                      scan=parsed.raw_canonical, barcode=barcode, code=code, position=existing[0])
            return False
        pallet = existing[0] if existing else None
        if pallet is None:
            occupied = {x["physical_position"] for x in state["pallets"].values() if x["direct"] and not x["retired"]}
            free = next((p for p in state["positions"] if p not in occupied), None)
            if free is None:
                add_event(state, accepted=False, status="SIN POSICIÓN AL PIE", message="Retire una directa verificada antes de continuar.", scan=parsed.raw_canonical, barcode=barcode, code=code)
                return False
            pallet = f"T-{state['next_pallet']:02d}"
            state["next_pallet"] += 1
            record = records(state)[code]
            already = sum(1 for x in state["boxes"].values() if x["code"] == code)
            capacity = max(1, floor(float(state["manifest"]["settings"]["target_capacity"]) / max(record.cbm_per_box, 1e-9)))
            state["pallets"][pallet] = {"direct": True, "code": code, "physical_position": free,
                                         "expected": min(record.boxes - already, capacity), "original_expected": min(record.boxes - already, capacity),
                                         "scanned": 0, "validated": False, "retired": False,
                                         "temporary": "", "verified_by": "", "verified_at": "", "closure_reason": ""}
    else:
        pallet = state["planned"][barcode]
        if pallet not in state["pallets"]:
            expected = sum(1 for destination in state["planned"].values() if destination == pallet)
            state["pallets"][pallet] = {"direct": False, "code": "", "physical_position": "",
                                         "expected": expected, "original_expected": expected, "scanned": 0,
                                         "validated": False, "retired": False, "temporary": "",
                                         "verified_by": "", "verified_at": "", "closure_reason": ""}
    transfer = "" if direct else f"TR-{state['active_transfer']:02d}"
    state["boxes"][barcode] = {"raw_scan": canonical_scan(raw), "barcode": barcode, "code": code,
                                "box_number": parsed.box_number, "final_pallet": pallet,
                                "transfer_pallet": transfer, "direct": direct, "scanned_at": now()}
    state["pallets"][pallet]["scanned"] += 1
    action = f"DIRECTA · {state['pallets'][pallet]['physical_position']}" if direct else f"MARCAR {pallet[2:]} · colocar en {transfer}"
    add_event(state, accepted=True, status="OK", message=f"{barcode} → {pallet} · {action}",
              scan=canonical_scan(raw), barcode=barcode, code=code, position=pallet)
    return True


def change_transfer(state):
    transfer = f"TR-{state['active_transfer']:02d}"
    if not any(x["transfer_pallet"] == transfer for x in state["boxes"].values()):
        raise ValueError(f"{transfer} está vacía.")
    state["closed_transfers"].append(transfer)
    state["active_transfer"] += 1
    add_event(state, accepted=True, status="TRASLADO CAMBIADO",
              message=f"{transfer} cerrado · usar TR-{state['active_transfer']:02d}", position=transfer)


def validate_pallet(state, pallet_id: str, responsible: str, temporary: str):
    pallet_id = canonical_scan(pallet_id)
    pallet = state["pallets"].get(pallet_id)
    if not pallet:
        raise ValueError("La tarima todavía no existe o no tiene cajas.")
    if pallet["validated"]:
        raise ValueError("La tarima ya fue verificada.")
    if pallet["scanned"] != pallet["expected"]:
        raise ValueError(f"Captura incompleta: {pallet['scanned']}/{pallet['expected']} cajas.")
    responsible = responsible.strip()
    temporary = canonical_scan(temporary)
    if not responsible or len(responsible) > 80 or any(x in responsible for x in "\r\n\t"):
        raise ValueError("Capture responsable de 1 a 80 caracteres.")
    if not valid_wms_temporary(temporary):
        raise ValueError("Temporal WMS inválida. No use I01, T-01 ni espacios.")
    related = [x for x in state["boxes"].values() if x["final_pallet"] == pallet_id and not x["direct"]]
    opened = sorted({x["transfer_pallet"] for x in related if x["transfer_pallet"] not in state["closed_transfers"]})
    if opened:
        raise ValueError("Primero cierre el traslado activo: " + ", ".join(opened))
    pallet.update(validated=True, temporary=temporary, verified_by=responsible, verified_at=now())
    add_event(state, accepted=True, status="TARIMA VERIFICADA",
              message=f"{pallet_id} verificada · temporal {temporary}", position=pallet_id)


def release_pallet(state, pallet_id: str):
    pallet_id = canonical_scan(pallet_id)
    pallet = state["pallets"].get(pallet_id)
    if not pallet or not pallet["validated"]:
        raise ValueError("Primero verifique la tarima.")
    if pallet["retired"]:
        raise ValueError("La tarima ya fue retirada.")
    pallet["retired"] = True
    add_event(state, accepted=True, status="TARIMA RETIRADA", message=f"{pallet_id} retirada; posición liberada.", position=pallet_id)


def snapshot_result(state):
    pallet_rows = []
    for pallet_id, pallet in sorted(state["pallets"].items()):
        validated, scanned, expected = pallet["validated"], pallet["scanned"], pallet["expected"]
        status = ("RETIRADA" if pallet["retired"] else "VERIFICADA" if validated
                  else "PREPARAR" if scanned == 0 else "REVISAR" if scanned >= expected
                  else "EN FORMACIÓN")
        pallet_rows.append({"id": pallet_id, "formation": "PIE" if pallet["direct"] else "TENDIDO",
                            "physical_position": pallet["physical_position"], "wms_temporary_location": pallet["temporary"],
                            "status": status, "expected": expected, "original_expected": pallet["original_expected"],
                            "scanned": scanned, "in_final": scanned if validated else 0, "validated": validated,
                            "retired": pallet["retired"], "closure_reason": pallet["closure_reason"],
                            "verification_method": "REVISION_FISICA" if validated else "",
                            "verified_by": pallet["verified_by"], "verified_at": pallet["verified_at"],
                            "verified_boxes": scanned if validated else 0})
    events = []
    for box in state["boxes"].values():
        pallet = state["pallets"][box["final_pallet"]]
        validated = pallet["validated"]
        events.append({**box, "physical_position": pallet["physical_position"],
                       "wms_temporary_location": pallet["temporary"],
                       "direct_to_final": box["direct"],
                       "transfer_closed": False if box["direct"] else box["transfer_pallet"] in state["closed_transfers"],
                       "physical_state": "EN_DEFINITIVA" if validated else "PENDIENTE_VERIFICAR",
                       "final_pallet_validated": validated, "wms_eligible": validated})
        events[-1].pop("direct", None)
    transfer_ids = sorted({x["transfer_pallet"] for x in state["boxes"].values() if x["transfer_pallet"]} | {f"TR-{state['active_transfer']:02d}"})
    transfers = []
    for transfer in transfer_ids:
        boxes = [x for x in events if x["transfer_pallet"] == transfer]
        closed = transfer in state["closed_transfers"]
        verified = sum(x["wms_eligible"] for x in boxes)
        transfers.append({"id": transfer, "closed": closed, "boxes": len(boxes), "verified_boxes": verified,
                          "status": "EN_FORMACION" if not closed else "VERIFICADO_POR_TARIMAS" if boxes and verified == len(boxes) else "PENDIENTE_VERIFICACION"})
    expected_total = sum(int(x["boxes"]) for x in state["manifest"]["records"])
    eligible = sum(x["wms_eligible"] for x in events)
    return {"schema": "ilubox.pda.result.v4", "version": 4,
            "container_id": state["manifest"]["container_id"],
            "record_signature": record_signature(records(state)), "exported_at": now(),
            "engine_version": "0.12-laboratorio-simulador", "verification_model": "FINAL_PALLET_WMS_TEMPORARY",
            "wms_location_validation": "FORMAT_ONLY",
            "individual_sequence": {"prefix": "U", "start": 1, "consecutive": True, "padding": 3},
            "progress": {"received": len(events), "expected": expected_total,
                         "in_final": eligible, "wms_eligible": eligible},
            "active_transfer": f"TR-{state['active_transfer']:02d}",
            "transfers": transfers, "pallets": pallet_rows, "accepted_events": events}
