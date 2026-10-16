"""Llaves virtuales de PhoenixCrates (Survival) -> tabla key_storage de ExcellentCrates 7.

Uso: python -I llaves.py <database.db de PhoenixCrates> <data.db de ExcellentCrates 7>
Escribe en data.db (que ya creo el plugin, con el servidor APAGADO). Suma si la fila existe.
"""
import json, sqlite3, sys, uuid
LLAVES = {}
src = sqlite3.connect(sys.argv[1])
dst = sqlite3.connect(sys.argv[2])
print(dst.execute("select sql from sqlite_master where name='key_storage'").fetchone()[0])
filas, total = 0, {}
for uid, nombre, vk in src.execute('select UniqueId, PlayerName, VirtualKeys from pc_player_table'):
    if not vk:
        continue
    d = json.loads(vk)
    u = str(uuid.UUID(uid))
    for k, n in d.items():
        n = int(n or 0)
        if n <= 0:
            continue
        k = LLAVES.get(k, k)
        dst.execute('insert into key_storage(owner_id,key_id,virtual_balance,unclaimed_amount) values(?,?,?,0) '
                    'on conflict(owner_id,key_id) do update set virtual_balance=virtual_balance+excluded.virtual_balance',
                    (u, k, n))
        filas += 1
        total[k] = total.get(k, 0) + n
dst.commit()
print('filas', filas, total)
print('jugadores', dst.execute('select count(distinct owner_id) from key_storage').fetchone()[0])
