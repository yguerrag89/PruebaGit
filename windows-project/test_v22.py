from core.optimizer import Settings
from core.parser import CodeRecord
from core.live import LiveUnload

S = Settings(target_capacity=1.94, physical_capacity=2.16, fixed_positions=20)

# 1) Posiciones iniciales por lado.
records = [
    CodeRecord(code='BIG', boxes=5, cbm=2.5, cbm_per_box=0.5),
    CodeRecord(code='SMALL', boxes=2, cbm=0.20, cbm_per_box=0.10),
    CodeRecord(code='UNITA', boxes=1, cbm=0.05, cbm_per_box=0.05),
]
live = LiveUnload(records, S, initial_left=2, initial_right=1)
p = live.position_pressure()
assert p['enabled'] == 3 and p['left_enabled'] == 2 and p['right_enabled'] == 1
assert live.enable_next('D') == 'D02'
assert live.position_pressure()['enabled'] == 4

# 2) Código grande: llena tarima, NO libera posición automáticamente.
r1 = live.scan('BIGU001')
r2 = live.scan('BIGU002')
r3 = live.scan('BIGU003')
assert r1['ok'] and r2['ok'] and r3['ok']
assert r3['status'] == 'TARIMA COMPLETA'
pos = r3['position']
pp = next(x for x in live.positions if x.label == pos)
assert pp.waiting_removal

# Un cuarto escaneo no puede colocarse hasta retirar la tarima.
r4 = live.scan('BIGU004')
assert not r4['ok'] and r4['status'] == 'POSICIÓN PENDIENTE'
assert live.received['BIG'] == 3

# Operador confirma posición lista; el mismo código continúa en la misma posición.
ready = live.mark_position_ready(pos)
assert ready['ok'] and ready['continues']
assert next(x for x in live.positions if x.label == pos).kind == 'DEDICADA'
r5 = live.scan('BIGU004')
r6 = live.scan('BIGU005')
assert r5['ok'] and r6['ok'] and r6['status'] == 'TARIMA COMPLETA'
assert r5['position'] == pos and r6['position'] == pos

# Última tarima retirada: posición sí queda libre.
ready2 = live.mark_position_ready(pos)
assert ready2['ok'] and not ready2['continues']
assert next(x for x in live.positions if x.label == pos).is_free

# 3) Duplicado no incrementa.
small1 = live.scan('SMALLU001')
dup = live.scan('SMALLU001')
assert small1['ok']
assert not dup['ok'] and dup['status'] == 'DUPLICADA'
assert live.received['SMALL'] == 1

# 4) Unitario va a posición habilitada y se registra.
unit = live.scan('UNITAU001')
assert unit['ok']

# 5) Mapa siempre tiene 20 posiciones, pero solo las habilitadas están disponibles.
cards = live.position_cards()
assert len(cards) == 20
assert sum(1 for c in cards if c['Habilitada']) == 4
assert cards[0]['Posición'] == 'I01'
assert cards[10]['Posición'] == 'D01'

print('OK V2.2 position lifecycle')

# 6) Sin posición no mueve nada; al habilitar una nueva, el código puede entrar.
records2 = [
    CodeRecord(code='A', boxes=10, cbm=3.0, cbm_per_box=0.3),
    CodeRecord(code='B', boxes=10, cbm=3.0, cbm_per_box=0.3),
]
live2 = LiveUnload(records2, S, initial_left=1, initial_right=0)
a = live2.scan('A-U001')
assert a['ok']
b = live2.scan('B-U001')
assert not b['ok'] and b['status'] == 'SIN POSICIÓN'
assert live2.enable_next('D') == 'D01'
b2 = live2.scan('B-U001')
assert b2['ok'] and b2['position'] == 'D01'
print('OK V2.2 supervisor enable flow')
