#!/usr/bin/env python3
"""Analizador de demanda de recompensas de Ederus (MEDICION-INTERES.md, sec. 6).

Lee una copia de SOLO LECTURA de la carpeta plugins/ del Survival (la baja el
coordinador; este script nunca toca produccion), junta tres senales por recompensa y
saca un ranking de demanda con su confianza, mas los KPIs de Calamity:

  - lo que piden     (preferencia declarada): Voto del Botin, encuestas, lista de deseos
  - lo que arriesgan (preferencia revelada por riesgo): censo del equipo en entra/muere
  - lo que compran   (preferencia revelada por gasto): trueques y clics fallidos del
                      altar, mobcoins.log, stock de las tiendas, aperturas y llaves

Uso:
  python analizar_demanda.py --prod <copia de plugins/> --desde 2026-10-01 --hasta 2026-10-31 \\
      --catalogo catalogo.yml --salida informes/2026-10 [--anterior informes/2026-09/demanda.csv]
  python analizar_demanda.py --prod <copia> --muestra openings     # lineas que el patron no reconoce

Salidas en --salida: demanda.csv, demanda.md, kpis.md, alertas.md, no-reconocido.log.

Solo biblioteca estandar (json, csv, sqlite3, re, argparse, datetime) y PyYAML. Las lineas
de telemetria con "prueba": true (autotest del plugin) no cuentan nunca.

Por que tanto cuidado con lo que no se reconoce: los formatos de openings.log
(ExcellentCrates) y mobcoins.log (UltimateMobCoins) no se han leido aun en produccion, asi
que sus patrones viven en catalogo.yml y se fijan tras leer 20 lineas reales (--muestra).
Si una fuente tiene mas de un 5 % de lineas que no casan, se deja fuera y se avisa: mejor
un hueco declarado que un numero falso.
"""
from __future__ import annotations

import argparse
import csv
import fnmatch
import json
import os
import re
import sqlite3
import statistics
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

try:
    import yaml
except ImportError:  # pragma: no cover - PyYAML es requisito declarado
    sys.exit("Falta PyYAML: pip install pyyaml")

VERSIONES_TELEMETRIA = {1}
UMBRAL_NO_RECONOCIDO = 0.05
METRICAS = ("pedido", "comprado", "frustrado", "abierto", "arriesgado")
PESOS_POR_DEFECTO = {"pedido": 0.30, "comprado": 0.30, "frustrado": 0.20, "abierto": 0.10, "arriesgado": 0.10}
FORMATOS_FECHA = ("%Y-%m-%d", "%d.%m.%Y", "%d/%m/%Y", "%Y/%m/%d", "%d-%m-%Y")


# --------------------------------------------------------------------------- utilidades

def leer_fecha(texto: str) -> date | None:
    """La fecha de un campo de log, en cualquiera de los formatos habituales."""
    texto = (texto or "").strip()
    if len(texto) >= 10 and texto[4] == "-" and texto[7] == "-":
        texto = texto[:10]
    for f in FORMATOS_FECHA:
        try:
            return datetime.strptime(texto, f).date()
        except ValueError:
            continue
    return None


def percentiles(valores: dict) -> dict:
    """Percentil 0-1 por rango medio entre las claves con dato. Con una sola, 0,5."""
    ks = sorted(k for k, v in valores.items() if v is not None)
    n = len(ks)
    out = {}
    for k in ks:
        v = valores[k]
        menos = sum(1 for j in ks if valores[j] < v)
        iguales = sum(1 for j in ks if valores[j] == v)
        out[k] = 0.5 if n == 1 else (menos + (iguales - 1) / 2) / (n - 1)
    return out


def cuantil(datos: list, q: float):
    if not datos:
        return None
    d = sorted(datos)
    i = (len(d) - 1) * q
    lo, hi = int(i), min(int(i) + 1, len(d) - 1)
    return d[lo] + (d[hi] - d[lo]) * (i - lo)


def fmt(v, dec: int = 2) -> str:
    if v is None:
        return "-"
    if isinstance(v, float):
        return f"{v:.{dec}f}"
    return str(v)


@dataclass
class Fuente:
    """Estado de lectura de una fuente: lineas vistas, no reconocidas y si se usa."""
    nombre: str
    ruta: str = ""
    existe: bool = False
    lineas: int = 0
    malas: list = field(default_factory=list)   # (fichero, numero, linea)
    notas: list = field(default_factory=list)

    @property
    def fraccion_mala(self) -> float:
        return len(self.malas) / self.lineas if self.lineas else 0.0

    @property
    def fiable(self) -> bool:
        return self.existe and self.fraccion_mala <= UMBRAL_NO_RECONOCIDO

    def mala(self, fichero: str, numero: int, linea: str):
        self.malas.append((fichero, numero, linea.rstrip("\n")))


# ------------------------------------------------------------------------------ catalogo

class Catalogo:
    """Mapea cada id bruto (por fuente) a una recompensa canonica."""

    CLAVES = ("trueque", "forja", "mmo", "deseo", "voto", "encuesta", "umc", "caja", "tienda", "recompensa")

    def __init__(self, datos: dict):
        self.recompensas = datos.get("recompensas") or {}
        self.patrones = datos.get("patrones") or {}
        self.pesos = {**PESOS_POR_DEFECTO, **(datos.get("pesos") or {})}
        self.stock_inicial = {str(k): int(v) for k, v in (datos.get("stock_inicial") or {}).items()}
        self.stock_claves = datos.get("stock_claves") or {"inicial": "stock", "restante": "remaining"}
        self.sqlite = datos.get("sqlite") or {}
        self.jugadores_survival = datos.get("jugadores_survival")
        self._indice = defaultdict(dict)   # clave -> id -> recompensa
        self._comodines = defaultdict(list)  # clave -> [(patron, recompensa)]
        for rid, r in self.recompensas.items():
            for clave, ids in ((r or {}).get("ids") or {}).items():
                for i in ids or []:
                    i = str(i)
                    if any(c in i for c in "*?["):
                        self._comodines[clave].append((i.lower(), rid))
                    else:
                        self._indice[clave][i.lower()] = rid

    @classmethod
    def cargar(cls, ruta: str) -> "Catalogo":
        with open(ruta, encoding="utf-8") as f:
            return cls(yaml.safe_load(f) or {})

    def nombre(self, rid: str) -> str:
        return str((self.recompensas.get(rid) or {}).get("nombre", rid))

    def buscar(self, clave: str, id_bruto) -> str | None:
        if id_bruto is None:
            return None
        i = str(id_bruto).lower()
        r = self._indice[clave].get(i)
        if r:
            return r
        for patron, rid in self._comodines[clave]:
            if fnmatch.fnmatch(i, patron):
                return rid
        return None

    def ids(self, rid: str, clave: str) -> list:
        return [str(x) for x in (((self.recompensas.get(rid) or {}).get("ids") or {}).get(clave) or [])]

    def cajas_de(self, rid: str) -> set:
        return {i.split(":", 1)[0].lower() for i in self.ids(rid, "caja") if ":" in i}

    def patron(self, nombre: str):
        p = self.patrones.get(nombre)
        return re.compile(p) if p else None


# ------------------------------------------------------------------------------ lectores

@dataclass
class Datos:
    sucesos: list = field(default_factory=list)
    bitacora: list = field(default_factory=list)        # (fecha, campos)
    tienda: list = field(default_factory=list)
    compras_umc: list = field(default_factory=list)
    stock: dict = field(default_factory=dict)           # articulo -> (inicial, restante)
    saldos_umc: list = field(default_factory=list)
    aperturas: list = field(default_factory=list)
    llaves: Counter = field(default_factory=Counter)   # caja -> guardadas
    datos_lw: dict = field(default_factory=dict)
    vitrinas: Counter = field(default_factory=Counter)  # id -> veces
    tenencia: Counter = field(default_factory=Counter)  # id mmo -> jugadores
    fuentes: dict = field(default_factory=dict)
    no_catalogados: dict = field(default_factory=lambda: defaultdict(Counter))


def ficheros(carpeta: str, patron: str) -> list:
    if not os.path.isdir(carpeta):
        return []
    return sorted(os.path.join(carpeta, n) for n in os.listdir(carpeta) if re.fullmatch(patron, n))


def en_periodo(d: date | None, desde: date, hasta: date) -> bool:
    return d is not None and desde <= d <= hasta


def leer_telemetria(prod, desde, hasta, datos: Datos):
    f = Fuente("telemetria", os.path.join(prod, "LethalWorld", "telemetria"))
    for ruta in ficheros(f.ruta, r"\d{4}-\d{2}\.jsonl"):
        f.existe = True
        with open(ruta, encoding="utf-8", errors="replace") as fh:
            for n, linea in enumerate(fh, 1):
                if not linea.strip():
                    continue
                f.lineas += 1
                try:
                    m = json.loads(linea)
                    if not isinstance(m, dict) or "ev" not in m or "t" not in m:
                        raise ValueError("sin ev o t")
                except ValueError:
                    f.mala(os.path.basename(ruta), n, linea)
                    continue
                if m.get("prueba") is True:
                    continue
                if m.get("v") not in VERSIONES_TELEMETRIA:
                    f.notas.append(f"{os.path.basename(ruta)}:{n} v={m.get('v')} desconocida, ignorada")
                    continue
                d = leer_fecha(str(m.get("t", ""))[:10])
                if en_periodo(d, desde, hasta):
                    m["_fecha"] = d
                    datos.sucesos.append(m)
    datos.fuentes[f.nombre] = f


def leer_bitacora(prod, desde, hasta, datos: Datos):
    f = Fuente("bitacora", os.path.join(prod, "LethalWorld", "logs"))
    for ruta in ficheros(f.ruta, r"lethal-world-\d{4}-\d{2}-\d{2}\.log"):
        f.existe = True
        d = leer_fecha(os.path.basename(ruta)[len("lethal-world-"):][:10])
        with open(ruta, encoding="utf-8", errors="replace") as fh:
            for n, linea in enumerate(fh, 1):
                if not linea.strip():
                    continue
                f.lineas += 1
                campos = [c.strip() for c in linea.rstrip("\n").split(" | ")]
                if len(campos) < 2 or not re.fullmatch(r"\d{2}:\d{2}:\d{2}", campos[0]):
                    f.mala(os.path.basename(ruta), n, linea)
                    continue
                if en_periodo(d, desde, hasta):
                    datos.bitacora.append((d, campos[1:]))
    datos.fuentes[f.nombre] = f


RE_TIENDA_CANTIDAD = re.compile(r"^(\d+)\s*x\s*(\S+)$")


def leer_tienda(prod, desde, hasta, datos: Datos):
    """EDM tienda: fecha-hora | TIPO | jugador | n x CLAVE | ud x | total x | saldo x (verificado)."""
    f = Fuente("tienda_edm", os.path.join(prod, "EDM", "tienda", "registro"))
    for ruta in ficheros(f.ruta, r"transacciones-\d{4}-\d{2}-\d{2}\.log"):
        f.existe = True
        with open(ruta, encoding="utf-8", errors="replace") as fh:
            for n, linea in enumerate(fh, 1):
                if not linea.strip():
                    continue
                f.lineas += 1
                c = [x.strip() for x in linea.rstrip("\n").split(" | ")]
                m = RE_TIENDA_CANTIDAD.match(c[3]) if len(c) == 7 else None
                d = leer_fecha(c[0][:10]) if len(c) == 7 else None
                if not m or d is None or c[1] not in ("COMPRA", "VENTA", "RECORTE") or not c[5].startswith("total "):
                    f.mala(os.path.basename(ruta), n, linea)
                    continue
                try:
                    total = float(c[5][len("total "):].replace(",", "."))
                except ValueError:
                    f.mala(os.path.basename(ruta), n, linea)
                    continue
                if en_periodo(d, desde, hasta):
                    datos.tienda.append({"fecha": d, "tipo": c[1], "jugador": c[2], "cantidad": int(m.group(1)),
                                         "material": m.group(2), "total": total})
    datos.fuentes[f.nombre] = f


def leer_por_patron(nombre, ruta, patron, grupos, desde, hasta, destino: list, datos: Datos):
    f = Fuente(nombre, ruta)
    if patron is None:
        f.notas.append(f"sin patron en catalogo.yml (patrones.{nombre.split('_')[0]})")
    if os.path.isfile(ruta) and patron is not None:
        f.existe = True
        with open(ruta, encoding="utf-8", errors="replace") as fh:
            for n, linea in enumerate(fh, 1):
                if not linea.strip():
                    continue
                f.lineas += 1
                m = patron.search(linea)
                d = leer_fecha(m.group("fecha")) if m else None
                if not m or d is None:
                    f.mala(os.path.basename(ruta), n, linea)
                    continue
                if en_periodo(d, desde, hasta):
                    fila = {g: m.group(g) for g in grupos}
                    fila["fecha"] = d
                    destino.append(fila)
    datos.fuentes[f.nombre] = f


def leer_stock(prod, cat: Catalogo, datos: Datos):
    """data/*-data.yml de UltimateMobCoins: articulo -> {stock, remaining} o articulo -> restante."""
    f = Fuente("stock_umc", os.path.join(prod, "UltimateMobCoins", "data"))
    ki, kr = cat.stock_claves.get("inicial", "stock"), cat.stock_claves.get("restante", "remaining")
    for ruta in ficheros(f.ruta, r".+-data\.yml"):
        f.existe = True
        try:
            with open(ruta, encoding="utf-8") as fh:
                y = yaml.safe_load(fh) or {}
        except yaml.YAMLError as e:
            f.lineas += 1
            f.mala(os.path.basename(ruta), 0, f"YAML roto: {e}")
            continue

        def visitar(nodo, dentro_de=None):
            if not isinstance(nodo, dict):
                return
            for k, v in nodo.items():
                k = str(k)
                if isinstance(v, dict) and (kr in v or ki in v):
                    f.lineas += 1
                    ini = v.get(ki, cat.stock_inicial.get(k))
                    res = v.get(kr)
                    if ini is None or res is None:
                        f.mala(os.path.basename(ruta), 0, f"{k}: sin {ki}/{kr}")
                    else:
                        datos.stock[k] = (int(ini), int(res))
                elif isinstance(v, (int, float)) and k in cat.stock_inicial:
                    f.lineas += 1
                    datos.stock[k] = (cat.stock_inicial[k], int(v))
                elif isinstance(v, dict):
                    visitar(v, k)
        visitar(y)
    datos.fuentes[f.nombre] = f


def leer_sqlite(nombre, ruta, datos: Datos, lector):
    f = Fuente(nombre, ruta)
    if os.path.isfile(ruta):
        f.existe = True
        try:
            con = sqlite3.connect(f"file:{ruta}?mode=ro", uri=True)
            try:
                lector(con, f)
            finally:
                con.close()
        except sqlite3.Error as e:
            f.lineas += 1
            f.mala(os.path.basename(ruta), 0, f"sqlite: {e}")
    datos.fuentes[f.nombre] = f


def leer_saldos_umc(prod, cat: Catalogo, datos: Datos):
    cfg = cat.sqlite.get("umc_saldos") or {}
    tabla, col = cfg.get("tabla", "mobcoins"), cfg.get("columna", "coins")

    def lector(con, f):
        if not re.fullmatch(r"\w+", tabla) or not re.fullmatch(r"\w+", col):
            raise sqlite3.Error("tabla o columna con caracteres raros en catalogo.yml")
        for (v,) in con.execute(f"SELECT {col} FROM {tabla}"):
            f.lineas += 1
            try:
                datos.saldos_umc.append(float(v))
            except (TypeError, ValueError):
                f.mala("data.db", f.lineas, repr(v))
    leer_sqlite("saldos_umc", os.path.join(prod, "UltimateMobCoins", "data.db"), datos, lector)


def leer_llaves(prod, cat: Catalogo, datos: Datos):
    cfg = cat.sqlite.get("crates_llaves") or {}
    tabla = cfg.get("tabla", "excellentcrates_users")
    columnas = cfg.get("columnas", ["keys", "keysOnHold"])

    def lector(con, f):
        if not re.fullmatch(r"\w+", tabla) or not all(re.fullmatch(r"\w+", c) for c in columnas):
            raise sqlite3.Error("tabla o columnas con caracteres raros en catalogo.yml")
        for fila in con.execute(f"SELECT {', '.join(columnas)} FROM {tabla}"):
            f.lineas += 1
            try:
                for celda in fila:
                    for caja, n in (json.loads(celda) if celda else {}).items():
                        datos.llaves[str(caja).lower()] += int(n)
            except (ValueError, TypeError, AttributeError):
                f.mala("data.db", f.lineas, repr(fila)[:200])
    leer_sqlite("llaves", os.path.join(prod, "ExcellentCrates", "data.db"), datos, lector)


def leer_datos_lw(prod, datos: Datos):
    f = Fuente("datos_lw", os.path.join(prod, "LethalWorld", "hardcore-datos.yml"))
    if os.path.isfile(f.ruta):
        f.existe = True
        f.lineas = 1
        try:
            with open(f.ruta, encoding="utf-8") as fh:
                datos.datos_lw = yaml.safe_load(fh) or {}
        except yaml.YAMLError as e:
            f.mala("hardcore-datos.yml", 0, str(e))
    datos.fuentes[f.nombre] = f


def textos(nodo):
    """Todos los textos de un YAML, para buscar ids sin depender de su estructura."""
    if isinstance(nodo, dict):
        for k, v in nodo.items():
            yield str(k)
            yield from textos(v)
    elif isinstance(nodo, list):
        for v in nodo:
            yield from textos(v)
    elif nodo is not None:
        yield str(nodo)


def ids_mmo(cat: Catalogo) -> set:
    out = set()
    for rid in cat.recompensas:
        out.update(i for i in cat.ids(rid, "mmo")
                   if not i.upper().startswith("VANILLA:") and not any(c in i for c in "*?["))
    return out


def leer_vitrinas(prod, cat: Catalogo, datos: Datos):
    f = Fuente("vitrinas", os.path.join(prod, "EDM", "flex", "vitrinas.yml"))
    buscados = ids_mmo(cat)
    if os.path.isfile(f.ruta):
        f.existe = True
        try:
            with open(f.ruta, encoding="utf-8") as fh:
                y = yaml.safe_load(fh) or {}
            f.lineas = 1
            for t in textos(y):
                for i in buscados:
                    if i in t:
                        datos.vitrinas[i] += 1
        except yaml.YAMLError as e:
            f.lineas = 1
            f.mala("vitrinas.yml", 0, str(e))
    datos.fuentes[f.nombre] = f


def leer_tenencia(prod, cat: Catalogo, datos: Datos):
    """MMOItems/userdata: cuantos jugadores tienen cada id (busqueda de texto por fichero)."""
    f = Fuente("tenencia", os.path.join(prod, "MMOItems", "userdata"))
    buscados = ids_mmo(cat)
    for ruta in ficheros(f.ruta, r".+\.yml"):
        f.existe = True
        f.lineas += 1
        with open(ruta, encoding="utf-8", errors="replace") as fh:
            texto = fh.read()
        for i in buscados:
            if i in texto:
                datos.tenencia[i] += 1
    datos.fuentes[f.nombre] = f


def leer_todo(prod: str, cat: Catalogo, desde: date, hasta: date) -> Datos:
    datos = Datos()
    leer_telemetria(prod, desde, hasta, datos)
    leer_bitacora(prod, desde, hasta, datos)
    leer_tienda(prod, desde, hasta, datos)
    leer_por_patron("mobcoins_log", os.path.join(prod, "UltimateMobCoins", "mobcoins.log"), cat.patron("mobcoins"),
                    ("jugador", "articulo", "precio"), desde, hasta, datos.compras_umc, datos)
    leer_por_patron("openings", os.path.join(prod, "ExcellentCrates", "openings.log"), cat.patron("openings"),
                    ("jugador", "caja", "premio"), desde, hasta, datos.aperturas, datos)
    leer_stock(prod, cat, datos)
    leer_saldos_umc(prod, cat, datos)
    leer_llaves(prod, cat, datos)
    leer_datos_lw(prod, datos)
    leer_vitrinas(prod, cat, datos)
    leer_tenencia(prod, cat, datos)
    return datos


def fiable(datos: Datos, fuente: str) -> bool:
    f = datos.fuentes.get(fuente)
    return f is not None and f.fiable


# ------------------------------------------------------------------------------ metricas

def piezas(censo) -> list:
    if not isinstance(censo, dict):
        return []
    out = []
    for p in censo.get("piezas") or []:
        if isinstance(p, dict) and not ({"prestado", "copia_eco"} & set(p.get("marcas") or [])):
            out.append(str(p.get("mmo")))
    return out


def calcular(cat: Catalogo, datos: Datos) -> dict:
    """Metricas en bruto por recompensa (None = sin dato)."""
    m = {rid: {k: None for k in (*METRICAS, "perdido", "visto")} for rid in cat.recompensas}
    extra = {rid: Counter() for rid in cat.recompensas}
    nc = datos.no_catalogados

    def anotar(rid, clave, n=1):
        if rid is not None:
            extra[rid][clave] += n

    tel = fiable(datos, "telemetria")
    votantes = set()
    arriesgan = defaultdict(set)   # recompensa -> jugadores que la han metido en Calamity
    if tel:
        for s in datos.sucesos:
            ev = s.get("ev")
            if ev == "voto":
                votantes.add(s.get("uuid"))
                rid = cat.buscar("voto", s.get("opcion"))
                if rid:
                    anotar(rid, "pedido")
                else:
                    nc["voto"].update([str(s.get("opcion"))])
            elif ev == "encuesta":
                votantes.add(s.get("uuid"))
                anotar(cat.buscar("encuesta", f"{s.get('id')}:{s.get('opcion')}"), "pedido")
            elif ev == "trueque":
                rid = cat.buscar("trueque", s.get("id"))
                if rid is None:
                    nc["trueque"].update([str(s.get("id"))])
                elif s.get("entrega") != "fallo":
                    anotar(rid, "trueques")
                    anotar(rid, "entregado")
            elif ev == "trueque-fallido":
                rid = cat.buscar("trueque", s.get("id"))
                if rid is None:
                    nc["trueque"].update([str(s.get("id"))])
                elif s.get("motivo") in ("esencias", "mc", "credito"):
                    anotar(rid, "fallidos")
            elif ev == "forja":
                anotar(cat.buscar("forja", s.get("pieza")), "entregado")
            elif ev == "recompensa":
                rid = cat.buscar("recompensa", s.get("objeto"))
                if rid is None:
                    nc["recompensa"].update([str(s.get("objeto"))])
                anotar(rid, "entregado", int(s.get("cantidad") or 1))
            elif ev in ("entra", "muere"):
                vistos = set()
                for i in piezas(s.get("censo")):
                    rid = cat.buscar("mmo", i)
                    if rid is None and not i.startswith("VANILLA:"):
                        nc["mmo"].update([i])
                    if rid and ev == "entra":
                        vistos.add(rid)
                    elif rid and ev == "muere":
                        anotar(rid, "perdidas")
                for rid in vistos:
                    arriesgan[rid].add(s.get("uuid"))

    # Lista de deseos: lo activo ahora (datos del plugin) manda sobre la telemetria.
    deseos = (datos.datos_lw or {}).get("deseos-votos") or {}
    if fiable(datos, "datos_lw") and deseos:
        for uuid, lista in deseos.items():
            votantes.add(str(uuid))
            for d in lista or []:
                rid = cat.buscar("deseo", d)
                if rid:
                    anotar(rid, "pedido")
                else:
                    nc["deseo"].update([str(d)])
    elif tel:
        neto = Counter()
        for s in datos.sucesos:
            if s.get("ev") == "deseo":
                votantes.add(s.get("uuid"))
                neto[s.get("id")] += 1 if s.get("opcion") == "si" else -1
        for d, n in neto.items():
            if n > 0:
                anotar(cat.buscar("deseo", d), "pedido", n)

    if fiable(datos, "mobcoins_log"):
        for c in datos.compras_umc:
            rid = cat.buscar("umc", c["articulo"])
            if rid:
                anotar(rid, "compras")
            else:
                nc["umc"].update([c["articulo"]])
    if fiable(datos, "stock_umc"):
        for art, (ini, res) in datos.stock.items():
            rid = cat.buscar("umc", art)
            if rid and ini > 0:
                extra[rid]["agotado_x1000"] += int(round(1000 * (1 - max(0, res) / ini)))
                extra[rid]["con_stock"] += 1
            elif not rid:
                nc["umc"].update([art])
    if fiable(datos, "tienda_edm"):
        for t in datos.tienda:
            if t["tipo"] == "COMPRA":
                rid = cat.buscar("tienda", t["material"])
                anotar(rid, "compras", 1)

    aperturas_caja = Counter()
    if fiable(datos, "openings"):
        for a in datos.aperturas:
            caja = str(a["caja"]).lower()
            aperturas_caja[caja] += 1
            rid = cat.buscar("caja", f"{caja}:{a['premio']}")
            if rid is None:
                nc["caja"].update([f"{caja}:{a['premio']}"])
    llaves_ok = fiable(datos, "llaves")
    tenencia_ok = fiable(datos, "tenencia")

    for rid in cat.recompensas:
        e = extra[rid]
        if votantes and e["pedido"]:
            m[rid]["pedido"] = e["pedido"] / len(votantes)
        elif votantes and (cat.ids(rid, "voto") or cat.ids(rid, "deseo") or cat.ids(rid, "encuesta")):
            m[rid]["pedido"] = 0.0
        tiene_gasto = bool(cat.ids(rid, "trueque") or cat.ids(rid, "umc") or cat.ids(rid, "tienda"))
        comprado = e["trueques"] + e["compras"] + e["agotado_x1000"] / 1000
        if comprado > 0 or (tiene_gasto and (tel or fiable(datos, "mobcoins_log"))):
            m[rid]["comprado"] = round(comprado, 3)
        if e["trueques"] + e["fallidos"] > 0:
            m[rid]["frustrado"] = e["fallidos"] / (e["trueques"] + e["fallidos"])
        cajas = cat.cajas_de(rid)
        abiertas = sum(aperturas_caja[c] for c in cajas)
        guardadas = sum(datos.llaves[c] for c in cajas) if llaves_ok else 0
        if cajas and abiertas + guardadas > 0:
            m[rid]["abierto"] = abiertas / (abiertas + guardadas)
        ids = [i for i in cat.ids(rid, "mmo") if not i.upper().startswith("VANILLA:") and not any(c in i for c in "*?[")]
        tienen = sum(datos.tenencia[i] for i in ids) if tenencia_ok else 0
        if tel and tienen > 0:
            # Jugadores que la arriesgan / jugadores que la tienen: bajo = la guardan (se valora).
            m[rid]["arriesgado"] = min(1.0, len(arriesgan[rid]) / tienen)
        if e["entregado"] > 0:
            m[rid]["perdido"] = e["perdidas"] / e["entregado"]
        elif e["perdidas"] > 0:
            m[rid]["perdido"] = float(e["perdidas"])
        if fiable(datos, "vitrinas") and ids:
            m[rid]["visto"] = sum(datos.vitrinas[i] for i in ids)
        m[rid]["_extra"] = dict(e)
    return m


def indice(cat: Catalogo, metricas: dict) -> list:
    """Filas ordenadas del ranking: demanda 0-100, confianza A/B/C y sin_declarada."""
    pct = {k: percentiles({rid: v[k] for rid, v in metricas.items()}) for k in METRICAS}
    filas = []
    for rid, v in metricas.items():
        senales = [k for k in METRICAS if v[k] is not None]
        if not senales:
            continue

        def media(claves):
            num = den = 0.0
            for k in claves:
                if v[k] is None:
                    continue
                p = pct[k][rid]
                if k == "arriesgado":
                    p = 1 - p   # arriesgar poco lo que se tiene = se valora mucho
                num += cat.pesos[k] * p
                den += cat.pesos[k]
            return None if den == 0 else round(100 * num / den, 1)

        gasto = any(v[k] is not None for k in ("comprado", "frustrado", "abierto"))
        n = len(senales)
        confianza = "A" if n >= 4 and gasto else ("B" if n >= 2 else "C")
        filas.append({"id": rid, "nombre": cat.nombre(rid), "demanda": media(METRICAS),
                      "sin_declarada": media([k for k in METRICAS if k != "pedido"]),
                      "confianza": confianza, "senales": n, **{k: v[k] for k in (*METRICAS, "perdido", "visto")},
                      "pct": {k: pct[k].get(rid) for k in METRICAS}, "con_stock": bool(cat.ids(rid, "umc") or cat.ids(rid, "trueque"))})
    filas.sort(key=lambda f: (-(f["demanda"] or 0), f["id"]))
    for i, f in enumerate(filas, 1):
        f["puesto"] = i
        f["comentario"] = comentario(f)
    return filas


def comentario(f: dict) -> str:
    p = f["pct"]
    notas = []
    if f["frustrado"] is not None and f["frustrado"] >= 0.5 and (p["comprado"] is None or p["comprado"] < 0.34):
        notas.append("Precio o requisito alto")
    if p["pedido"] is not None and p["pedido"] >= 0.66 and p["comprado"] is not None and p["comprado"] < 0.34 and f["con_stock"]:
        notas.append("Se pide y no se paga: revisar precio o valor real")
    if p["comprado"] is not None and p["comprado"] >= 0.66 and p["pedido"] is not None and p["pedido"] < 0.34:
        notas.append("Subestimado")
    if p["arriesgado"] is not None and p["arriesgado"] < 0.34:
        notas.append("Lo que más se valora")
    return "; ".join(notas)


def comparar(filas: list, anterior: str | None):
    if not anterior or not os.path.isfile(anterior):
        return
    with open(anterior, encoding="utf-8", newline="") as fh:
        antes = {r["id"]: int(r["puesto"]) for r in csv.DictReader(fh) if r.get("puesto", "").isdigit()}
    for f in filas:
        a = antes.get(f["id"])
        if a is None:
            f["comentario"] = "; ".join(x for x in (f["comentario"], "Nueva en el ranking") if x)
        elif a != f["puesto"]:
            mov = f"{'Sube' if f['puesto'] < a else 'Baja'} {abs(a - f['puesto'])} (era {a}.º)"
            f["comentario"] = "; ".join(x for x in (f["comentario"], mov) if x)


# ---------------------------------------------------------------------------------- KPIs

def kpis(cat: Catalogo, datos: Datos, desde: date, hasta: date) -> list:
    """Los KPIs de MED sec. 7 que salen de las fuentes leidas: (kpi, valor, objetivo, estado, fuente)."""
    s = datos.sucesos if fiable(datos, "telemetria") else []
    por_ev = defaultdict(list)
    for x in s:
        por_ev[x.get("ev")].append(x)
    out = []

    def fila(nombre, valor, objetivo, bien, fuente):
        estado = "sin datos" if valor is None else ("OK" if bien is True else ("revisar" if bien is False else bien))
        out.append((nombre, valor if valor is not None else "-", objetivo, estado, fuente))

    entradas = por_ev["entra"]
    jugadores = {x.get("uuid") for x in entradas}
    semanas = max(1, ((hasta - desde).days + 1) / 7)
    por_semana = len(jugadores) / semanas if jugadores else None
    if cat.jugadores_survival and jugadores:
        part = len(jugadores) / float(cat.jugadores_survival)
        fila("Participación", f"{part:.0%} ({len(jugadores)} jugadores)", "≥ 20 %", part >= 0.20, "entra")
    else:
        fila("Participación", None if not jugadores else f"{len(jugadores)} jugadores ({por_semana:.1f}/semana)",
             "≥ 20 % del Survival", "sin referencia", "entra (falta jugadores_survival en catalogo.yml)")

    dias = defaultdict(set)
    for x in entradas:
        dias[x.get("uuid")].add(x["_fecha"])
    base1 = vuelven1 = base7 = vuelven7 = 0
    for u, ds in dias.items():
        for d in ds:
            if d + timedelta(days=1) <= hasta:
                base1 += 1
                vuelven1 += (d + timedelta(days=1)) in ds
            if d + timedelta(days=7) <= hasta:
                base7 += 1
                vuelven7 += (d + timedelta(days=7)) in ds
    d1 = vuelven1 / base1 if base1 else None
    d7 = vuelven7 / base7 if base7 else None
    fila("Retorno D1", None if d1 is None else f"{d1:.0%}", "≥ 40 %", d1 is not None and d1 >= 0.40, "entra")
    fila("Retorno D7", None if d7 is None else f"{d7:.0%}", "≥ 20 %", d7 is not None and d7 >= 0.20, "entra")

    sal, mue = len(por_ev["sale"]), len(por_ev["muere"])
    abiertas = max(0, len(entradas) - sal - mue)
    cierres = sal + mue + abiertas
    ext = sal / cierres if cierres else None
    fila("Resultado de la expedición", None if ext is None else f"{ext:.0%} extracción · {mue} muertes · {abiertas} sin cerrar",
         "45-65 % extracción", ext is not None and 0.45 <= ext <= 0.65, "sale, muere")

    primeras = {}
    for x in sorted(entradas, key=lambda y: y["t"]):
        primeras.setdefault(x.get("uuid"), x)
    cortas = cobradas = 0
    cierres_ev = sorted(por_ev["sale"] + por_ev["muere"], key=lambda y: y["t"])
    for u, e in primeras.items():
        fin = next((c for c in cierres_ev if c.get("uuid") == u and c["t"] >= e["t"]), None)
        if fin is None or (fin.get("minutos") or 0) >= 30:
            continue
        cortas += 1
        cobradas += any(p.get("uuid") == u and e["t"] <= p["t"] <= fin["t"] and (p.get("esencias") or p.get("mc"))
                        for p in por_ev["pago"])
    pc = cobradas / cortas if cortas else None
    fila("Primera sesión cobrada", None if pc is None else f"{pc:.0%} de {cortas}", "≥ 60 %", pc is not None and pc >= 0.60,
         "entra, sale, pago")

    mch, eh = [], []
    for x in por_ev["sale"]:
        horas = (x.get("minutos") or 0) / 60
        if horas > 0:
            mch.append((x.get("mc") or 0) / horas)
            eh.append((x.get("esencias") or 0) / horas)
    fila("Producción MC/h (p50 · p90)", None if not mch else f"{cuantil(mch, .5):.0f} · {cuantil(mch, .9):.0f}",
         "p50 200-400, p90 ≤ 600", bool(mch) and 200 <= cuantil(mch, .5) <= 400 and cuantil(mch, .9) <= 600, "sale")
    fila("Producción E/h (p50 · p90)", None if not eh else f"{cuantil(eh, .5):.1f} · {cuantil(eh, .9):.1f}",
         "p50 6-15", bool(eh) and 6 <= cuantil(eh, .5) <= 15, "sale")

    pagadas = sum(p.get("mc") or 0 for p in por_ev["pago"])
    recortadas = sum(p.get("mc_no_pagadas") or 0 for p in por_ev["pago"])
    rec = recortadas / (pagadas + recortadas) if pagadas + recortadas else None
    fila("MC recortadas", None if rec is None else f"{rec:.1%}", "< 10 %", rec is not None and rec < 0.10, "pago")

    ok = sum(1 for t in por_ev["trueque"] if t.get("entrega") != "fallo")
    fal = len(por_ev["trueque-fallido"])
    conv = ok / (ok + fal) if ok + fal else None
    fila("Conversión por trueque", None if conv is None else f"{conv:.0%} ({ok}/{ok + fal})", "≥ 30 %",
         conv is not None and conv >= 0.30, "trueque, trueque-fallido")

    caos = sum(1 for a in datos.aperturas if str(a["caja"]).lower() == "caos") if fiable(datos, "openings") else None
    guard = datos.llaves.get("caos", 0) if fiable(datos, "llaves") else 0
    ic = caos / (caos + guard) if caos is not None and caos + guard > 0 else None
    fila("Índice de la Llave del Caos", None if ic is None else f"{ic:.2f}", "≥ 0,7", ic is not None and ic >= 0.7,
         "openings.log, data.db")

    esc = [x["censo"]["escalon_medio"] for x in entradas if isinstance(x.get("censo"), dict)
           and isinstance(x["censo"].get("escalon_medio"), (int, float))]
    em = statistics.mean(esc) if esc else None
    fila("Escalón medio arriesgado", None if em is None else f"{em:.2f}", "sube (≤ 7 estancado: revisar)",
         em is not None and em > 7, "entra (censo)")

    manto = set(cat.ids("manto-calamidad", "mmo"))
    forjas = sum(1 for f in por_ev["forja"] if cat.buscar("forja", f.get("pieza")) == "manto-calamidad")
    perdidas = sum(1 for x in por_ev["muere"] for i in piezas(x.get("censo")) if i in manto)
    pm = perdidas / forjas if forjas else None
    fila("Manto perdido / forjado", None if pm is None else f"{perdidas}/{forjas} ({pm:.0%})", "≥ 30 %",
         pm is not None and pm >= 0.30, "forja, muere")

    cazas = Counter()
    for x in por_ev["eco"]:
        if x.get("accion") == "muere" and x.get("valida") in (True, "si", "sí"):
            cazas[x.get("asesino")] += 1
    cz = (sum(cazas.values()) / len(cazas) / semanas) if cazas else None
    fila("Cazas válidas por cazador y semana", None if cz is None else f"{cz:.2f}", "≥ 1", cz is not None and cz >= 1, "eco")

    fuga = sum(1 for x in s if x.get("ev") == "ligado-ajeno")
    bloq = sum(1 for x in s if x.get("ev") == "ligado-bloqueado")
    fila("Fuga al mercado", f"{fuga} ajenos · {bloq} bloqueados" if s else None, "0 ajenos", fuga == 0, "ligado-*")

    libros = sum(1 for x in por_ev["caja-libro"] if x.get("resultado") == "entregado")
    fila("LEGENDARY por caja", f"{libros}" if s else None, "≤ 2/mes", libros <= 2 * max(1, round(semanas / 4.3)), "caja-libro")

    sal_umc = datos.saldos_umc if fiable(datos, "saldos_umc") else []
    fila("Saldos de MobCoins (mediana · p90)", None if not sal_umc else f"{cuantil(sal_umc, .5):.0f} · {cuantil(sal_umc, .9):.0f}",
         "estables", "comparar con el mes anterior", "UltimateMobCoins/data.db")

    ese = [float(v) for v in ((datos.datos_lw or {}).get("esencias") or {}).values() if isinstance(v, (int, float))]
    me = cuantil(ese, .5) if ese else None
    fila("Saldo de Esencias (mediana)", None if me is None else f"{me:.0f}", "≤ 60", me is not None and me <= 60,
         "hardcore-datos.yml")

    cam = (((datos.datos_lw or {}).get("encuestas") or {}).get("camino") or {}).get("votos") or {}
    tot = sum(v for v in cam.values() if isinstance(v, int))
    si = cam.get("si", 0) / tot if tot else None
    fila("Encuesta camino: % Sí", None if si is None else f"{si:.0%} de {tot}", "≥ 50 %", si is not None and si >= 0.5,
         "hardcore-datos.yml")
    return out


# ------------------------------------------------------------------------------- alertas

def alertas(cat: Catalogo, datos: Datos) -> list:
    out = []
    for f in datos.fuentes.values():
        if not f.existe:
            out.append(f"Fuente sin datos: {f.nombre} ({f.ruta}). Sus métricas bajan la confianza, no cuentan como cero.")
        elif f.fraccion_mala > UMBRAL_NO_RECONOCIDO:
            out.append(f"Fuente descartada: {f.nombre} tiene {len(f.malas)} de {f.lineas} líneas sin reconocer "
                       f"({f.fraccion_mala:.0%} > 5 %). Revisa su patrón con --muestra {f.nombre}.")
        elif f.malas:
            out.append(f"{f.nombre}: {len(f.malas)} líneas sin reconocer de {f.lineas} (en no-reconocido.log).")
        for n in f.notas[:5]:
            out.append(f"{f.nombre}: {n}")
    if fiable(datos, "bitacora"):
        dup = [c for _, c in datos.bitacora if len(c) >= 2 and c[0] == "reliquia" and c[1] in ("duplicada", "falsa")]
        if dup:
            out.append(f"Reliquias duplicadas o falsas en la Bitácora: {len(dup)} (exploit: revisar ya).")
    if fiable(datos, "telemetria"):
        ajenos = [s for s in datos.sucesos if s.get("ev") == "ligado-ajeno"]
        if ajenos:
            out.append(f"Objetos ligados en manos ajenas: {len(ajenos)} (ligado-ajeno).")
        fallos = [s for s in datos.sucesos if s.get("ev") == "trueque" and s.get("entrega") == "fallo"]
        if fallos:
            out.append(f"Trueques cobrados con entrega fallida: {len(fallos)}.")
    for fuente, c in sorted(datos.no_catalogados.items()):
        if c:
            ids = ", ".join(f"{k} ({n})" for k, n in c.most_common(20))
            out.append(f"Ids de {fuente} que no están en catalogo.yml: {ids}")
    return out


# -------------------------------------------------------------------------------- salida

COLUMNAS = ("puesto", "id", "nombre", "demanda", "sin_declarada", "confianza", "senales", "pedido", "comprado",
            "frustrado", "abierto", "arriesgado", "perdido", "visto", "comentario")


def escribir(salida: str, cat: Catalogo, datos: Datos, filas: list, tabla_kpis: list, avisos: list,
             desde: date, hasta: date):
    os.makedirs(salida, exist_ok=True)
    with open(os.path.join(salida, "demanda.csv"), "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(COLUMNAS)
        for f in filas:
            w.writerow([fmt(f[c], 3) if isinstance(f[c], float) else ("" if f[c] is None else f[c]) for c in COLUMNAS])

    periodo = f"{desde.isoformat()} a {hasta.isoformat()}"
    lin = [f"# Demanda de recompensas · {periodo}", "",
           "Índice = 0,30·pedido + 0,30·comprado + 0,20·frustrado + 0,10·abierto + 0,10·(1 − arriesgado), "
           "con cada métrica en percentil entre las recompensas con dato y sin contar las que faltan "
           "(una fuente vacía baja la confianza, no el índice). Confianza A: ≥ 4 señales y alguna de gasto; "
           "B: 2-3; C: 1. «Sin declarada» es el índice sin lo que piden (para ver lo que se pide y no se paga).", "",
           "| # | Recompensa | Demanda | Sin declarada | Conf. | Pedido | Comprado | Frustrado | Abierto | Arriesgado | Perdido | Visto | Comentario |",
           "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for f in filas:
        lin.append(f"| {f['puesto']} | {f['nombre']} | {fmt(f['demanda'], 1)} | {fmt(f['sin_declarada'], 1)} | {f['confianza']} | "
                   f"{fmt(f['pedido'])} | {fmt(f['comprado'])} | {fmt(f['frustrado'])} | {fmt(f['abierto'])} | "
                   f"{fmt(f['arriesgado'])} | {fmt(f['perdido'])} | {fmt(f['visto'])} | {f['comentario']} |")
    sin = [cat.nombre(r) for r in cat.recompensas if r not in {f["id"] for f in filas}]
    if sin:
        lin += ["", f"Sin ninguna señal en el periodo ({len(sin)}): " + ", ".join(sorted(sin)) + "."]
    with open(os.path.join(salida, "demanda.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lin) + "\n")

    lk = [f"# KPIs de Calamity · {periodo}", "", "| KPI | Valor | Objetivo | Estado | Fuente |", "|---|---|---|---|---|"]
    for k in tabla_kpis:
        lk.append("| " + " | ".join(str(x) for x in k) + " |")
    with open(os.path.join(salida, "kpis.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lk) + "\n")

    la = [f"# Alertas · {periodo}", ""]
    la += [f"- {a}" for a in avisos] if avisos else ["Sin alertas."]
    la += ["", "## Fuentes", "", "| Fuente | Existe | Líneas | Sin reconocer | Usada |", "|---|---|---|---|---|"]
    for f in datos.fuentes.values():
        la.append(f"| {f.nombre} | {'sí' if f.existe else 'no'} | {f.lineas} | {len(f.malas)} | {'sí' if f.fiable else 'no'} |")
    with open(os.path.join(salida, "alertas.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(la) + "\n")

    with open(os.path.join(salida, "no-reconocido.log"), "w", encoding="utf-8") as fh:
        for f in datos.fuentes.values():
            for fichero, n, linea in f.malas:
                fh.write(f"{f.nombre}\t{fichero}:{n}\t{linea}\n")


def analizar(prod: str, catalogo: str, desde: date, hasta: date, salida: str, anterior: str | None = None) -> list:
    cat = Catalogo.cargar(catalogo)
    datos = leer_todo(prod, cat, desde, hasta)
    filas = indice(cat, calcular(cat, datos))
    comparar(filas, anterior)
    escribir(salida, cat, datos, filas, kpis(cat, datos, desde, hasta), alertas(cat, datos), desde, hasta)
    return filas


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Ranking de demanda de recompensas y KPIs de Calamity (MED sec. 6).")
    ap.add_argument("--prod", required=True, help="copia local de solo lectura de plugins/")
    ap.add_argument("--catalogo", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "catalogo.yml"))
    ap.add_argument("--desde", help="AAAA-MM-DD (incluido)")
    ap.add_argument("--hasta", help="AAAA-MM-DD (incluido)")
    ap.add_argument("--salida", help="carpeta de los informes")
    ap.add_argument("--anterior", help="demanda.csv del periodo anterior (sube/baja)")
    ap.add_argument("--muestra", metavar="FUENTE", help="imprime hasta 20 líneas que esa fuente no reconoce")
    a = ap.parse_args(argv)
    # Las lineas de los logs traen de todo: una consola de Windows en cp1252 no puede tumbar el informe.
    for flujo in (sys.stdout, sys.stderr):
        try:
            flujo.reconfigure(errors="replace")
        except (AttributeError, ValueError):
            pass

    if a.muestra:
        cat = Catalogo.cargar(a.catalogo)
        datos = leer_todo(a.prod, cat, date(1970, 1, 1), date(2999, 12, 31))
        f = datos.fuentes.get(a.muestra)
        if f is None:
            print(f"Fuentes: {', '.join(datos.fuentes)}", file=sys.stderr)
            return 2
        print(f"{f.nombre}: {f.lineas} líneas, {len(f.malas)} sin reconocer ({f.ruta})")
        for fichero, n, linea in f.malas[:20]:
            print(f"{fichero}:{n}\t{linea}")
        return 0

    if not (a.desde and a.hasta and a.salida):
        ap.error("--desde, --hasta y --salida son obligatorios (salvo con --muestra)")
    desde, hasta = leer_fecha(a.desde), leer_fecha(a.hasta)
    if desde is None or hasta is None or desde > hasta:
        ap.error("--desde y --hasta: AAAA-MM-DD y desde <= hasta")
    filas = analizar(a.prod, a.catalogo, desde, hasta, a.salida, a.anterior)
    print(f"{len(filas)} recompensas con señal · informes en {a.salida}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
