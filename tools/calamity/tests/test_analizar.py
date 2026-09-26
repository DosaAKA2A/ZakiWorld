"""Pruebas del analizador de demanda (MEDICION-INTERES.md sec. 6.8).

    python -m unittest discover tools/calamity/tests

Trabajan sobre tests/prod-ejemplo (copia sintetica de plugins/, la genera
generar_ejemplo.py) y sobre copias en carpetas temporales cuando hace falta romper algo.
"""
import csv
import io
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import date

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(AQUI))

import analizar_demanda as ad  # noqa: E402

EJEMPLO = os.path.join(AQUI, "prod-ejemplo")
CATALOGO = os.path.join(os.path.dirname(AQUI), "catalogo.yml")
DESDE, HASTA = date(2026, 9, 1), date(2026, 9, 30)


def filas_csv(carpeta):
    with open(os.path.join(carpeta, "demanda.csv"), encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh))


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="demanda-")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def copia(self):
        destino = os.path.join(self.tmp, "prod")
        shutil.copytree(EJEMPLO, destino)
        return destino

    def correr(self, prod=EJEMPLO, nombre="salida", **kw):
        salida = os.path.join(self.tmp, nombre)
        filas = ad.analizar(prod, CATALOGO, DESDE, HASTA, salida, **kw)
        return filas, salida

    def fila(self, filas, rid):
        return next((f for f in filas if f["id"] == rid), None)


class TestSalidas(Base):
    def test_cli_escribe_los_informes(self):
        salida = os.path.join(self.tmp, "cli")
        with redirect_stdout(io.StringIO()):
            codigo = ad.main(["--prod", EJEMPLO, "--desde", "2026-09-01", "--hasta", "2026-09-30",
                              "--catalogo", CATALOGO, "--salida", salida])
        self.assertEqual(codigo, 0)
        for f in ("demanda.md", "kpis.md", "alertas.md", "demanda.csv", "no-reconocido.log"):
            self.assertTrue(os.path.isfile(os.path.join(salida, f)), f)
        with open(os.path.join(salida, "demanda.md"), encoding="utf-8") as fh:
            md = fh.read()
        self.assertIn("| # | Recompensa | Demanda |", md)
        self.assertIn("Manto de Calamidad", md)

    def test_ranking_estable(self):
        _, a = self.correr(nombre="a")
        _, b = self.correr(nombre="b")
        with open(os.path.join(a, "demanda.csv"), encoding="utf-8") as fa, open(os.path.join(b, "demanda.csv"), encoding="utf-8") as fb:
            self.assertEqual(fa.read(), fb.read())

    def test_lineas_de_prueba_no_cuentan(self):
        filas, _ = self.correr()
        mascota = self.fila(filas, "mascota-rara")
        # Los 5 votos "mascotas" del ejemplo llevan "prueba": true y el sexto tiene v=2.
        self.assertEqual(mascota["pedido"], 0.0)
        datos = ad.leer_todo(EJEMPLO, ad.Catalogo.cargar(CATALOGO), DESDE, HASTA)
        self.assertFalse(any(s.get("prueba") for s in datos.sucesos))
        self.assertFalse(any(s.get("v") != 1 for s in datos.sucesos))

    def test_periodo(self):
        datos = ad.leer_todo(EJEMPLO, ad.Catalogo.cargar(CATALOGO), DESDE, HASTA)
        self.assertTrue(all(DESDE <= s["_fecha"] <= HASTA for s in datos.sucesos))
        self.assertFalse(any(c["articulo"] == "book_fabled" for c in datos.compras_umc))   # la de agosto

    def test_tres_senales_del_manto(self):
        filas, _ = self.correr()
        manto = self.fila(filas, "manto-calamidad")
        self.assertIsNotNone(manto["pedido"])
        self.assertIsNotNone(manto["comprado"])
        self.assertAlmostEqual(manto["frustrado"], 4 / 5)
        self.assertEqual(manto["confianza"], "A")
        # Sain y Goge_x la tienen (userdata); solo Sain la mete en Calamity.
        self.assertAlmostEqual(manto["arriesgado"], 0.5)

    def test_kpis_y_alertas(self):
        _, salida = self.correr()
        with open(os.path.join(salida, "kpis.md"), encoding="utf-8") as fh:
            kpis = fh.read()
        self.assertIn("MC recortadas", kpis)
        self.assertIn("Índice de la Llave del Caos", kpis)
        with open(os.path.join(salida, "alertas.md"), encoding="utf-8") as fh:
            alertas = fh.read()
        self.assertIn("Reliquias duplicadas", alertas)
        self.assertIn("objeto-nuevo", alertas)            # id sin catalogo
        self.assertIn("saldos_umc", alertas)              # fuente vacia declarada

    def test_anterior_sube_baja(self):
        _, a = self.correr(nombre="a")
        filas = filas_csv(a)
        # Se falsea el puesto del primero para que el siguiente informe diga que sube.
        filas[0]["puesto"] = "9"
        anterior = os.path.join(self.tmp, "anterior.csv")
        with open(anterior, "w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(filas[0]))
            w.writeheader()
            w.writerows(filas)
        nuevas, _ = self.correr(nombre="b", anterior=anterior)
        self.assertIn("Sube 8", nuevas[0]["comentario"])


class TestRobustez(Base):
    def test_linea_no_reconocida_no_rompe(self):
        _, salida = self.correr()
        with open(os.path.join(salida, "no-reconocido.log"), encoding="utf-8") as fh:
            log = fh.read()
        for fuente in ("telemetria", "bitacora", "tienda_edm", "mobcoins_log", "openings"):
            self.assertIn(fuente + "\t", log)

    def test_fuente_vacia_baja_confianza_y_no_da_cero(self):
        _, _ = self.correr(nombre="con")
        con = {f["id"]: f for f in ad.analizar(EJEMPLO, CATALOGO, DESDE, HASTA, os.path.join(self.tmp, "con2"))}
        prod = self.copia()
        os.remove(os.path.join(prod, "UltimateMobCoins", "mobcoins.log"))
        shutil.rmtree(os.path.join(prod, "UltimateMobCoins", "data"))
        shutil.rmtree(os.path.join(prod, "ExcellentCrates"))
        sin = {f["id"]: f for f in ad.analizar(prod, CATALOGO, DESDE, HASTA, os.path.join(self.tmp, "sin"))}
        antes, despues = con["libro-legendary"], sin["libro-legendary"]
        self.assertGreater(antes["senales"], despues["senales"])
        self.assertGreater(despues["demanda"], 0)
        self.assertLessEqual("ABC".index(antes["confianza"]), "ABC".index(despues["confianza"]))

    def test_fuente_con_mas_del_5_por_ciento_se_descarta(self):
        prod = self.copia()
        ruta = os.path.join(prod, "ExcellentCrates", "openings.log")
        with open(ruta, "a", encoding="utf-8") as fh:
            fh.write("\n".join(f"basura {i}" for i in range(10)) + "\n")
        filas, salida = self.correr(prod=prod, nombre="basura")
        with open(os.path.join(salida, "alertas.md"), encoding="utf-8") as fh:
            self.assertIn("Fuente descartada: openings", fh.read())
        self.assertIsNone(self.fila(filas, "libro-legendary")["abierto"])

    def test_telemetria_version_desconocida(self):
        prod = self.copia()
        with open(os.path.join(prod, "LethalWorld", "telemetria", "2026-09.jsonl"), "a", encoding="utf-8") as fh:
            fh.write(json.dumps({"t": "2026-09-20T10:00:00+02:00", "ev": "voto", "uuid": "z", "nombre": "z", "mundo": "",
                                 "v": 9, "id": "botin", "opcion": "rip"}) + "\n")
        datos = ad.leer_todo(prod, ad.Catalogo.cargar(CATALOGO), DESDE, HASTA)
        self.assertFalse(any(s.get("uuid") == "z" for s in datos.sucesos))
        self.assertTrue(any("v=9" in n for n in datos.fuentes["telemetria"].notas))

    def test_muestra(self):
        out = io.StringIO()
        with redirect_stdout(out):
            codigo = ad.main(["--prod", EJEMPLO, "--catalogo", CATALOGO, "--muestra", "openings"])
        self.assertEqual(codigo, 0)
        self.assertIn("Sain abrió algo sin fecha", out.getvalue())

    def test_prod_vacio(self):
        vacio = os.path.join(self.tmp, "vacio")
        os.makedirs(vacio)
        filas, salida = self.correr(prod=vacio, nombre="vacio")
        self.assertEqual(filas, [])
        with open(os.path.join(salida, "alertas.md"), encoding="utf-8") as fh:
            self.assertIn("Fuente sin datos: telemetria", fh.read())


class TestLectores(Base):
    def test_tienda_formato_verificado(self):
        prod = os.path.join(self.tmp, "p")
        os.makedirs(os.path.join(prod, "EDM", "tienda", "registro"))
        with open(os.path.join(prod, "EDM", "tienda", "registro", "transacciones-2026-08-24.log"), "w", encoding="utf-8") as fh:
            fh.write("2026-08-24 11:59:35 | VENTA | Dosa__ | 512 x SUGAR_CANE | ud 14.40 | total 7374.64 | saldo 5007374.64\n")
        datos = ad.Datos()
        ad.leer_tienda(prod, date(2026, 8, 1), date(2026, 8, 31), datos)
        self.assertEqual(datos.tienda[0]["cantidad"], 512)
        self.assertEqual(datos.tienda[0]["material"], "SUGAR_CANE")
        self.assertAlmostEqual(datos.tienda[0]["total"], 7374.64)
        self.assertEqual(datos.fuentes["tienda_edm"].malas, [])

    def test_sqlite_saldos_y_llaves(self):
        prod = self.copia()
        con = sqlite3.connect(os.path.join(prod, "UltimateMobCoins", "data.db"))
        con.execute("CREATE TABLE mobcoins (uuid TEXT, coins REAL)")
        con.executemany("INSERT INTO mobcoins VALUES (?, ?)", [("a", 100), ("b", 300), ("c", 5000)])
        con.commit()
        con.close()
        con = sqlite3.connect(os.path.join(prod, "ExcellentCrates", "data.db"))
        con.execute("CREATE TABLE excellentcrates_users (uuid TEXT, keys TEXT, keysOnHold TEXT)")
        con.execute("INSERT INTO excellentcrates_users VALUES ('a', ?, ?)", (json.dumps({"caos": 3}), json.dumps({"caos": 1})))
        con.commit()
        con.close()
        cat = ad.Catalogo.cargar(CATALOGO)
        datos = ad.leer_todo(prod, cat, DESDE, HASTA)
        self.assertEqual(sorted(datos.saldos_umc), [100, 300, 5000])
        self.assertEqual(datos.llaves["caos"], 4)
        filas, _ = self.correr(prod=prod, nombre="sql")
        caos = self.fila(filas, "llave-caos")
        # 10 aperturas de "caos" en openings.log y 4 llaves guardadas.
        self.assertAlmostEqual(caos["abierto"], 10 / 14)

    def test_percentiles(self):
        p = ad.percentiles({"a": 1, "b": 2, "c": 2, "d": 5, "e": None})
        self.assertEqual(p["a"], 0.0)
        self.assertEqual(p["d"], 1.0)
        self.assertAlmostEqual(p["b"], p["c"])
        self.assertNotIn("e", p)
        self.assertEqual(ad.percentiles({"x": 3}), {"x": 0.5})

    def test_catalogo_comodines(self):
        cat = ad.Catalogo.cargar(CATALOGO)
        self.assertEqual(cat.buscar("caja", "caos:lo-que-sea"), "llave-caos")
        self.assertEqual(cat.buscar("umc", "BOOK_LEGENDARY"), "libro-legendary")
        self.assertIsNone(cat.buscar("umc", "no_existe"))

    def test_leer_fecha(self):
        self.assertEqual(ad.leer_fecha("2026-09-12T10:00:00+02:00"), date(2026, 9, 12))
        self.assertEqual(ad.leer_fecha("12.09.2026"), date(2026, 9, 12))
        self.assertIsNone(ad.leer_fecha("ayer"))


if __name__ == "__main__":
    unittest.main()
