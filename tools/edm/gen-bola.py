"""Genera plugins/edm-src/src/main/resources/models/conde-bola.json (formato 2 de EDM).

La bola de Sangre Ardiente del CONDE hecha solo con bloques: una esfera voxel de
radio 1,0 centrada en el origen (en el juego va a escala 0,55, 1,1 bloques de
diametro) y una cola de llamas hacia +Z. La esfera cuelga de un grupo animado
"giro" que da una vuelta entera en Y cada 20 ticks; la cola no gira.

  - nucleo: un cubo de REDSTONE_BLOCK de 0,5 girado 45 grados en X y en Z;
  - 26 cubos de NETHER_WART_BLOCK de 0,34 en una esfera de radio 0,62;
  - 10 cubos de RED_CONCRETE de 0,22 a radio 0,8;
  - 6 cubos de BLACK_CONCRETE de 0,18 a radio 0,85 (las manchas oscuras);
  - cola: 8 llamas de RED_CONCRETE (0,16 x 0,16 x 0,6..1,1) y 4 de REDSTONE_BLOCK mas cortas.

Todo sale de una semilla fija: dos ejecuciones dan el mismo fichero byte a byte.
Uso: python -I tools/edm/gen-bola.py [--check] [--output RUTA]
"""
import argparse
import json
import math
import random
import sys
from pathlib import Path

SEMILLA = 6660
SALIDA = Path(__file__).resolve().parents[2] / "plugins" / "edm-src" / "src" / "main" / "resources" / "models" / "conde-bola.json"
AUREO = math.pi * (3 - math.sqrt(5))


def limpio(v):
    v = round(v, 10)
    return 0 if abs(v) < 1e-10 else (int(v) if v == int(v) else v)


def mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def traslacion(x, y, z):
    return [[1, 0, 0, x], [0, 1, 0, y], [0, 0, 1, z], [0, 0, 0, 1]]


def escala(x, y, z):
    return [[x, 0, 0, 0], [0, y, 0, 0], [0, 0, z, 0], [0, 0, 0, 1]]


def giro_x(a):
    c, s = math.cos(a), math.sin(a)
    return [[1, 0, 0, 0], [0, c, -s, 0], [0, s, c, 0], [0, 0, 0, 1]]


def giro_y(a):
    c, s = math.cos(a), math.sin(a)
    return [[c, 0, s, 0], [0, 1, 0, 0], [-s, 0, c, 0], [0, 0, 0, 1]]


def giro_z(a):
    c, s = math.cos(a), math.sin(a)
    return [[c, -s, 0, 0], [s, c, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]


def giro_xyz(x, y, z):
    return mul(mul(giro_x(x), giro_y(y)), giro_z(z))


def filas(m):
    return [limpio(m[i][j]) for i in range(4) for j in range(4)]


def normal(v):
    n = math.sqrt(sum(c * c for c in v))
    return [c / n for c in v]


def cruz(a, b):
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


def fibonacci(n, radio, desfase):
    """n puntos repartidos por igual en una esfera."""
    puntos = []
    for i in range(n):
        y = 1 - 2 * (i + 0.5) / n
        r = math.sqrt(max(0.0, 1 - y * y))
        a = i * AUREO + desfase
        puntos.append((math.cos(a) * r * radio, y * radio, math.sin(a) * r * radio))
    return puntos


def cubo(centro, lado, giro):
    """Bloque de 'lado' con su centro en 'centro' y girado sobre ese centro."""
    m = mul(traslacion(*centro), giro)
    m = mul(m, escala(lado, lado, lado))
    return mul(m, traslacion(-0.5, -0.5, -0.5))


def llama(inicio, direccion, grueso, largo, alabeo):
    """Bloque alargado que nace en 'inicio' y se estira 'largo' hacia 'direccion'."""
    d = normal(direccion)
    x = normal(cruz([0, 1, 0], d))
    y = cruz(d, x)
    c, s = math.cos(alabeo), math.sin(alabeo)
    x, y = [c * x[i] + s * y[i] for i in range(3)], [-s * x[i] + c * y[i] for i in range(3)]
    r = [[x[0], y[0], d[0], 0], [x[1], y[1], d[1], 0], [x[2], y[2], d[2], 0], [0, 0, 0, 1]]
    m = mul(traslacion(*inicio), r)
    m = mul(m, escala(grueso, grueso, largo))
    return mul(m, traslacion(-0.5, -0.5, 0))


def esquinas(m):
    for i in (0, 1):
        for j in (0, 1):
            for k in (0, 1):
                yield [sum(m[f][c] * v for c, v in enumerate((i, j, k, 1))) for f in range(3)]


def generar():
    rnd = random.Random(SEMILLA)
    piezas = []

    def bloque(ident, material, matriz, grupo):
        piezas.append({"id": ident, "group": grupo, "kind": "block", "matrix": filas(matriz), "material": material})
        return matriz

    esfera = []
    esfera.append(bloque("nucleo", "REDSTONE_BLOCK", cubo((0, 0, 0), 0.5, giro_xyz(math.pi / 4, 0, math.pi / 4)), "giro"))

    def capa(nombre, material, n, radio, lado, desfase):
        for i, p in enumerate(fibonacci(n, radio, desfase)):
            g = giro_xyz(rnd.uniform(0, 2 * math.pi), rnd.uniform(0, 2 * math.pi), rnd.uniform(0, 2 * math.pi))
            esfera.append(bloque(f"{nombre}-{i + 1:02d}", material, cubo(p, lado, g), "giro"))

    capa("verruga", "NETHER_WART_BLOCK", 26, 0.62, 0.34, 0.0)
    capa("rojo", "RED_CONCRETE", 10, 0.8, 0.22, 0.7)
    capa("mancha", "BLACK_CONCRETE", 6, 0.85, 0.18, 1.9)

    cola = []
    for i in range(8):
        a = i * 2 * math.pi / 8 + rnd.uniform(-0.2, 0.2)
        abre = rnd.uniform(0.22, 0.42)
        inicio = (math.cos(a) * 0.3, math.sin(a) * 0.3, 0.42)
        largo = 0.6 + 0.5 * (i % 4) / 3 + rnd.uniform(-0.05, 0.05)
        largo = min(1.1, max(0.6, largo))
        cola.append(bloque(f"llama-{i + 1:02d}", "RED_CONCRETE",
                           llama(inicio, (math.cos(a) * abre, math.sin(a) * abre, 1), 0.16, largo, rnd.uniform(0, math.pi)),
                           None))
    for i in range(4):
        a = (i + 0.5) * 2 * math.pi / 4
        abre = rnd.uniform(0.1, 0.2)
        inicio = (math.cos(a) * 0.18, math.sin(a) * 0.18, 0.5)
        cola.append(bloque(f"brasa-{i + 1:02d}", "REDSTONE_BLOCK",
                           llama(inicio, (math.cos(a) * abre, math.sin(a) * abre, 1), 0.12, rnd.uniform(0.35, 0.55),
                                 rnd.uniform(0, math.pi)),
                           None))

    puntos = [p for m in esfera for p in esquinas(m)]
    alcance = max(math.sqrt(sum(c * c for c in p)) for p in puntos)
    fondo = max(p[2] for m in cola for p in esquinas(m))

    modelo = {
        "format": 2,
        "name": "conde-bola",
        "source": "generado por tools/edm/gen-bola.py",
        "bounds": {"radius": 1, "minY": -1, "maxY": 1, "fit": "center"},
        "groups": [{
            "id": "giro", "name": "giro", "parent": None,
            "matrix": filas(escala(1, 1, 1)),
            "keys": [
                {"time": 0, "position": [0, 0, 0], "rotation": [0, 0, 0], "scale": [1, 1, 1]},
                {"time": 20, "position": [0, 0, 0], "rotation": [0, limpio(2 * math.pi), 0], "scale": [1, 1, 1]},
            ],
        }],
        "pieces": piezas,
    }
    texto = json.dumps(modelo, ensure_ascii=True, allow_nan=False, separators=(",", ":")) + "\n"
    return texto, len(esfera), len(cola), alcance, fondo


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--output", type=Path, default=SALIDA)
    ap.add_argument("--check", action="store_true", help="compara con el fichero existente sin escribir")
    args = ap.parse_args()
    texto, n_esfera, n_cola, alcance, fondo = generar()
    datos = texto.encode("utf-8")
    total = n_esfera + n_cola
    if not 50 <= total <= 60:
        print(f"RECHAZADO: {total} piezas, se esperaban de 50 a 60", file=sys.stderr)
        raise SystemExit(2)
    if args.check:
        igual = args.output.exists() and args.output.read_bytes() == datos
        print(("IGUAL " if igual else "DISTINTO ") + str(args.output))
        raise SystemExit(0 if igual else 1)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(datos)
    print(f"conde-bola: {total} piezas ({n_esfera} de la esfera en el grupo 'giro', {n_cola} de la cola)")
    print(f"  esfera hasta radio {alcance:.3f}, cola hasta z {fondo:.3f}; giro de 360 grados en 20 ticks")
    print(f"  {args.output} ({len(datos)} bytes)")


if __name__ == "__main__":
    main()
