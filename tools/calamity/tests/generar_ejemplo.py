#!/usr/bin/env python3
"""Genera tests/prod-ejemplo: una copia sintetica de plugins/ para probar el analizador.

Imita las fuentes de MEDICION-INTERES.md sec. 2 con datos inventados y deterministas
(septiembre de 2026, seis jugadores). Cada fuente de texto lleva UNA linea que no casa con
su patron (para ver que no rompe nada y que va a no-reconocido.log) y la telemetria lleva
lineas "prueba": true, una v desconocida y sucesos de agosto (fuera del periodo).

Los formatos de openings.log y mobcoins.log son los del patron de catalogo.yml, NO los de
produccion (que aun no se han leido): cuando se lean, se cambian aqui y alli a la vez.

    python tools/calamity/tests/generar_ejemplo.py     # reescribe prod-ejemplo
"""
import json
import os
import shutil

AQUI = os.path.dirname(os.path.abspath(__file__))
DESTINO = os.path.join(AQUI, "prod-ejemplo")

J = {
    "Sain": "8f1c0000-0000-4000-8000-000000000001",
    "Goge_x": "8f1c0000-0000-4000-8000-000000000002",
    "namkaze": "8f1c0000-0000-4000-8000-000000000003",
    "Dosa__": "8f1c0000-0000-4000-8000-000000000004",
    "Antor03": "8f1c0000-0000-4000-8000-000000000005",
    "Dvdkng": "8f1c0000-0000-4000-8000-000000000006",
}


def escribir(ruta, texto):
    ruta = os.path.join(DESTINO, ruta)
    os.makedirs(os.path.dirname(ruta), exist_ok=True)
    with open(ruta, "w", encoding="utf-8", newline="\n") as f:
        f.write(texto)


def censo(*piezas):
    ps = []
    for casilla, mmo, esc, marcas in piezas:
        mat = mmo.split(":", 1)[1] if mmo.startswith("VANILLA:") else "NETHERITE_CHESTPLATE"
        ps.append({"casilla": casilla, "material": mat, "mmo": mmo, "tier": None if mmo.startswith("VANILLA:") else "CALAMIDAD",
                   "escalon": esc, "encantamientos": 2, "marcas": marcas})
    riesgo = [p["escalon"] for p in ps if not ({"prestado", "copia_eco"} & set(p["marcas"]))]
    return {"escalon_medio": round(sum(riesgo) / len(riesgo), 2) if riesgo else 0.0,
            "escalon_max": max([p["escalon"] for p in ps] or [0]),
            "piezas_mmo": sum(1 for p in ps if not p["mmo"].startswith("VANILLA:")),
            "piezas_calamity": sum(1 for p in ps if 12 <= p["escalon"] <= 17 and p["tier"]), "piezas": ps}


def suceso(t, ev, nombre, mundo="calamity", **campos):
    return json.dumps({"t": t, "ev": ev, "uuid": J[nombre], "nombre": nombre, "mundo": mundo, "v": 1, **campos},
                      ensure_ascii=False, separators=(",", ":"))


def telemetria():
    CORAZA = ("pechera", "ARMOR.CORAZA_DE_CALAMIDAD", 16, [])
    ESPADA = ("mano", "VANILLA:DIAMOND_SWORD", 4, [])
    BOTAS_P = ("botas", "VANILLA:IRON_BOOTS", 2, ["prestado"])
    NETH = ("mano", "VANILLA:NETHERITE_SWORD", 6, [])
    lineas = []
    # agosto: fuera del periodo
    lineas.append(suceso("2026-08-30T20:00:00+02:00", "entra", "Sain", rango=10, poder=300, N=25, censo=censo(ESPADA)))
    dias = ["2026-09-%02d" % d for d in (3, 4, 5, 10, 11, 12, 17, 18, 24, 25)]
    for i, d in enumerate(dias):
        for k, n in enumerate(["Sain", "Goge_x", "namkaze", "Dosa__"]):
            if (i + k) % 3 == 2:
                continue
            t0 = f"{d}T2{k}:00:00+02:00"
            t1 = f"{d}T2{k}:25:00+02:00"
            equipo = censo(CORAZA, NETH) if n == "Sain" else censo(ESPADA, BOTAS_P)
            lineas.append(suceso(t0, "entra", n, rango=8 + k, poder=250, N=20 + i, censo=equipo, esencias_saldo=10,
                                 esencias_encima=0, reliquias={"1": 0, "2": 0, "3": 0, "4": 0}, frascos=1, cristales=0, tributo=0))
            if (i + k) % 4 == 3:
                lineas.append(suceso(t1, "muere", n, causa="mob:zombie", minutos=25, cordura=40, N=22, censo=equipo,
                                     reliquias={"1": 2, "2": 0, "3": 0, "4": 0}, esencias_encima=1, mobs=6))
            else:
                lineas.append(suceso(t1, "pago", n, tipo="tasacion", esencias=6, mc=150, mc_no_pagadas=10, recorte=1.0,
                                     motivo="puerta"))
                lineas.append(suceso(t1, "sale", n, motivo="puerta", minutos=25, racha=1, racha_tope=5,
                                     tasado={"1": 3, "2": 1, "3": 0, "4": 0}, esencias=6, mc=150, mobs=8, destacados=1,
                                     minijefes=0, cofres=2, censo_salida=equipo))
    # altar
    lineas.append(suceso("2026-09-05T22:00:00+02:00", "trueque", "Sain", mundo="overworld", id="frasco", pagina="umbral",
                         esencias=10, mc=0, entrega="ok"))
    lineas.append(suceso("2026-09-06T22:00:00+02:00", "trueque", "Goge_x", mundo="overworld", id="cristal", pagina="umbral",
                         esencias=16, mc=0, entrega="ok"))
    for n in ("Sain", "Goge_x", "namkaze", "Dosa__"):
        lineas.append(suceso("2026-09-12T22:30:00+02:00", "trueque-fallido", n, mundo="overworld", id="coraza-manto",
                             motivo="credito", faltan="sello:centinela-de-toba"))
    lineas.append(suceso("2026-09-13T22:30:00+02:00", "trueque-fallido", "namkaze", mundo="overworld", id="llave",
                         motivo="esencias", faltan=12))
    lineas.append(suceso("2026-09-19T21:00:00+02:00", "trueque", "Sain", mundo="overworld", id="coraza-manto",
                         pagina="forja", esencias=48, mc=3000, credito="sello:centinela-de-toba", entrega="ok"))
    lineas.append(suceso("2026-09-19T21:00:01+02:00", "forja", "Sain", mundo="overworld", pieza="coraza",
                         sello_usado="centinela-de-toba", reposicion=False))
    lineas.append(suceso("2026-09-20T21:00:00+02:00", "recompensa", "Dosa__", mundo="overworld", origen="consola",
                         objeto="llave", cantidad=2, ligado="no"))
    lineas.append(suceso("2026-09-21T21:00:00+02:00", "recompensa", "Dosa__", mundo="overworld", origen="consola",
                         objeto="objeto-nuevo", cantidad=1, ligado="no"))
    # lo que piden
    votos = {"Sain": "piezas-manto", "Goge_x": "libros-legendary", "namkaze": "piezas-manto", "Dosa__": "llaves",
             "Antor03": "rip"}
    for n, op in votos.items():
        lineas.append(suceso("2026-09-12T23:00:00+02:00", "voto", n, mundo="overworld", id="botin", opcion=op))
    lineas.append(suceso("2026-09-12T23:00:05+02:00", "encuesta", "Sain", mundo="overworld", id="salvar", opcion="pechera"))
    lineas.append(suceso("2026-09-12T23:00:06+02:00", "encuesta", "Goge_x", mundo="overworld", id="camino", opcion="si"))
    # Ecos, cajas, ligado
    lineas.append(suceso("2026-09-18T22:00:00+02:00", "eco", "Goge_x", accion="muere", id="e1", dueno=J["Sain"], N=40,
                         asesino=J["Goge_x"], valida=True, escalon_medio=10.0, lagrima=3))
    lineas.append(suceso("2026-09-25T22:00:00+02:00", "caja-libro", "Dvdkng", mundo="overworld", resultado="entregado",
                         mes_total=1))
    lineas.append(suceso("2026-09-25T22:10:00+02:00", "ligado-bloqueado", "Dvdkng", mundo="overworld",
                         objeto="ARMOR.CORAZA_DE_CALAMIDAD", via="comando:ah"))
    # lo que el analizador tiene que ignorar
    for i in range(5):
        lineas.append(suceso(f"2026-09-15T10:00:0{i}+02:00", "voto", "Dvdkng", id="botin", opcion="mascotas", prueba=True, n=i))
    lineas.append('{"t":"2026-09-15T10:00:00+02:00","ev":"voto","uuid":"x","nombre":"x","mundo":"x","v":2,"id":"botin","opcion":"mascotas"}')
    lineas.append('{"t":"2026-09-16T10:00:00+02:00","ev":"sale", esto no es JSON')
    escribir("LethalWorld/telemetria/2026-09.jsonl", "\n".join(lineas[1:]) + "\n")
    escribir("LethalWorld/telemetria/2026-08.jsonl", lineas[0] + "\n")


def bitacora():
    escribir("LethalWorld/logs/lethal-world-2026-09-12.log", "\n".join([
        "21:12:30 | datapack | instalado o actualizado en world/datapacks",
        "21:30:00 | pago | Sain | tasacion | e 8 | mc 80",
        "21:31:00 | reliquia | duplicada | Goge_x | 1b2c",
        "21:32:00 | muerte | namkaze | calamity 10 64 10 | zombie | cordura 40 | dentro 900 s",
        "linea sin hora que no casa",
    ] + [f"22:{m:02d}:00 | pago | Dosa__ | mob | e 1 | mc 12" for m in range(30)]) + "\n")


def tienda():
    filas = []
    for d in range(1, 26):
        filas.append(f"2026-09-{d:02d} 11:59:35 | VENTA | Dosa__ | 512 x SUGAR_CANE | ud 14.40 | total 7374.64 | saldo 5007374.64")
    filas.append("2026-09-10 12:00:00 | COMPRA | Sain | 1 x GOLDEN_APPLE | ud 300.00 | total 300.00 | saldo 1000.00")
    filas.append("2026-09-11 12:00:00 | COMPRA | Goge_x | 1 x TOTEM_OF_UNDYING | ud 900.00 | total 900.00 | saldo 1000.00")
    filas.append("2026-09-11 12:00:00 | COMPRA | Goge_x | esto no es una cantidad")
    escribir("EDM/tienda/registro/transacciones-2026-09-01.log", "\n".join(filas) + "\n")


def mobcoins():
    compras = [("Goge_x", "star", 800)] * 3 + [("Goge_x", "book_elite", 1500), ("Goge_x", "egapple", 900),
                                                ("Sain", "book_legendary", 12000), ("namkaze", "book_legendary", 12000),
                                                ("Dosa__", "book_legendary", 12000), ("Sain", "legendary_key", 9000),
                                                ("Antor03", "elytra", 3500), ("Dvdkng", "totem", 850)]
    lineas = [f"[2026-09-{5 + i:02d} 18:22:01] {j} bought {a} from shop for {p} MobCoins" for i, (j, a, p) in enumerate(compras)]
    lineas += [f"[2026-09-{20 + i % 5:02d} 19:00:00] Dvdkng bought hopper from rotating_shop for 350 MobCoins" for i in range(12)]
    lineas.append("[2026-08-01 10:00:00] Sain bought book_fabled from shop for 25000 MobCoins")
    lineas.append("linea rara de UltimateMobCoins sin formato")
    escribir("UltimateMobCoins/mobcoins.log", "\n".join(lineas) + "\n")
    escribir("UltimateMobCoins/data/shop_with_timer-data.yml",
             "book_legendary: 1\nlegendary_key: 4\nelytra: 3\nbook_fabled: 2\nepic_key: 3\n")
    escribir("UltimateMobCoins/data/rotating_shop-data.yml",
             "items:\n  star: {stock: 3, remaining: 0}\n  egapple: {stock: 2, remaining: 1}\n  hopper: {stock: 12, remaining: 0}\n")


def openings():
    lineas = []
    premios = [("caos", "libro-legendary"), ("caos", "llave-hito"), ("legendary", "book_legendary_random"),
               ("vote", "diamonds"), ("pets", "pet_wolf")]
    for i in range(25):
        caja, premio = premios[i % len(premios)]
        jugador = list(J)[i % 6]
        lineas.append(f"[2026-09-{1 + i:02d} 18:30:00] {jugador} opened {caja} and won {premio}")
    lineas.append("Sain abrió algo sin fecha")
    escribir("ExcellentCrates/openings.log", "\n".join(lineas) + "\n")


def datos_lw():
    escribir("LethalWorld/hardcore-datos.yml", f"""esencias:
  {J['Sain']}: 40
  {J['Goge_x']}: 75
  {J['namkaze']}: 12
encuestas:
  camino:
    votos: {{si: 3, mas-o-menos: 2, 'no': 1}}
deseos-votos:
  {J['Sain']}: [manto, llave-caos]
  {J['Goge_x']}: [manto, libro-legendary, rip]
  {J['namkaze']}: [manto]
  {J['Dosa__']}: [candidata-borrada]
""")


def vitrinas_y_userdata():
    escribir("EDM/flex/vitrinas.yml", f"""vitrinas:
  {J['Sain']}:
    - {{slot: 1, item: "ARMOR.CORAZA_DE_CALAMIDAD"}}
    - {{slot: 2, item: "SWORD.ESPADA_ETERNA"}}
""")
    for n in ("Sain", "Goge_x", "Antor03"):
        cuerpo = "items:\n  - ARMOR.CORAZA_DE_CALAMIDAD\n" if n != "Antor03" else "items:\n  - SWORD.ESPADA_ETERNA\n"
        escribir(f"MMOItems/userdata/{J[n]}.yml", cuerpo)


def main():
    if os.path.isdir(DESTINO):
        shutil.rmtree(DESTINO)
    telemetria()
    bitacora()
    tienda()
    mobcoins()
    openings()
    datos_lw()
    vitrinas_y_userdata()
    print(f"prod-ejemplo generado en {DESTINO}")


if __name__ == "__main__":
    main()
