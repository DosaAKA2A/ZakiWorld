#!/usr/bin/env python3
"""Convierte un proyecto de BDEngine (.bdengine, contenedor PRJ2) al JSON saneado formato 2 de EDM.

Solo sobreviven geometria, textos de un caracter, texturas de cabeza (base64 validada) y claves de
animacion. Nada de NBT, comandos, sonidos, URLs ni ajustes del editor. Ver
plugins/edm-src/docs/modelos-bde.md.

Uso:
  python bde-export.py ENTRADA.bdengine --name conde-circulo --output models/conde-circulo.json \
      --source-url https://www.block-display.com/bd/56202 --author TheDuckInBlue --license "BDL BY" \
      --title "Light Magic Circle animated" --fit floor \
      --recolor yellow_concrete_powder=RED_CONCRETE --text-color B3122E [--expect block=48,text=48] [--check]

Sin dependencias (Python 3.12).
"""

import argparse
import base64
import binascii
import gzip
import hashlib
import io
import json
import math
import re
import struct
import sys
from pathlib import Path
from urllib.parse import urlparse

IDENTITY = [1.0, 0, 0, 0, 0, 1.0, 0, 0, 0, 0, 1.0, 0, 0, 0, 0, 1.0]
MAX_BYTES = 8_000_000
MAX_DEPTH = 24
MAX_PIECES = 2048
MAX_GROUPS = 512
MAX_KEYS = 256
MAX_TIME = 72000
MAX_VALUE = 1000

BLOCK_NAME = re.compile(r"[a-z0-9_]{1,64}")
MATERIAL = re.compile(r"[A-Z0-9_]{1,64}")
TAGS_ONLY = re.compile(r'^(Tags:\[("[A-Za-z0-9_.\-]{0,64}",?)*\])?$')
SKIN_PATH = re.compile(r"/texture/[0-9a-f]{32,128}")
# Bloques tecnicos que nunca se aceptan aunque el editor los permita.
DENIED_BLOCKS = {
    "air", "cave_air", "void_air", "command_block", "chain_command_block", "repeating_command_block",
    "structure_block", "structure_void", "jigsaw", "barrier", "light", "spawner", "trial_spawner",
    "vault", "end_portal", "end_gateway", "nether_portal", "moving_piston", "test_block",
    "test_instance_block",
}
TEXT_OPTION_KEYS = {"color", "alpha", "backgroundColor", "backgroundAlpha", "bold", "italic", "underline",
                    "strikeThrough", "obfuscated", "lineLength", "align", "font"}

# Volumen local de cada tipo de display (para envolventes y centros).
#  BlockDisplay: cubo [0,1]^3.
#  ItemDisplay de player_head sin transformacion de display: cubo de 0,5 con la base en y=-0,5.
#  TextDisplay de un caracter: plano z=0, ~8 px de ancho y 13 px de alto a 0,025 bloques por pixel.
BOX = {
    "block": ((0.0, 1.0), (0.0, 1.0), (0.0, 1.0)),
    "head": ((-0.25, 0.25), (-0.5, 0.0), (-0.25, 0.25)),
    "text": ((-0.1, 0.1), (0.0, 0.325), (0.0, 0.0)),
}


class Rejected(ValueError):
    """El proyecto trae algo que el conversor no acepta."""


# ------------------------------------------------------------------ contenedor PRJ2

def read_project(compressed):
    if len(compressed) > MAX_BYTES:
        raise Rejected("el proyecto supera 8 MB")
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(compressed)) as stream:
            data = stream.read(MAX_BYTES + 1)
    except (OSError, EOFError) as fallo:
        raise Rejected(f"no es un gzip valido: {fallo}") from fallo
    if len(data) > MAX_BYTES or data[:5] != b"PRJ2\x01":
        raise Rejected("se esperaba un contenedor PRJ2 v1 acotado")
    offset = 5

    def take(size):
        nonlocal offset
        if size < 0 or offset + size > len(data):
            raise Rejected("entrada PRJ2 truncada")
        value = data[offset:offset + size]
        offset += size
        return value

    count = struct.unpack("<i", take(4))[0]
    if not 1 <= count <= 32:
        raise Rejected("numero de entradas PRJ2 invalido")
    entries = {}
    for _ in range(count):
        name = take(struct.unpack("<H", take(2))[0]).decode("utf-8")
        if name in entries:
            raise Rejected("entrada PRJ2 repetida")
        entries[name] = take(struct.unpack("<i", take(4))[0])
    if offset != len(data) or "scene.json" not in entries:
        raise Rejected("falta scene.json o sobran bytes")
    roots = json.loads(entries["scene.json"].decode("utf-8"))
    if not isinstance(roots, list) or len(roots) != 1 or not isinstance(roots[0], dict):
        raise Rejected("se esperaba una sola raiz en la escena")
    return roots[0]


# ------------------------------------------------------------------ algebra (matrices fila mayor)

def mul(a, b):
    return [sum(a[r * 4 + k] * b[k * 4 + c] for k in range(4)) for r in range(4) for c in range(4)]


def apply(m, p):
    return tuple(m[r * 4] * p[0] + m[r * 4 + 1] * p[1] + m[r * 4 + 2] * p[2] + m[r * 4 + 3] for r in range(3))


def finite(value, what):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) \
            or abs(value) > MAX_VALUE:
        raise Rejected(f"{what}: numero no finito o mayor que {MAX_VALUE}")
    return float(value)


def matrix(values, what):
    if not isinstance(values, list) or len(values) != 16:
        raise Rejected(f"{what}: se esperaban 16 valores")
    values = [finite(v, what) for v in values]
    if values[12:] != [0, 0, 0, 1]:
        raise Rejected(f"{what}: matriz no afin")
    return values


def vec3(obj, what):
    if not isinstance(obj, dict) or set(obj) != {"x", "y", "z"}:
        raise Rejected(f"{what}: vector x/y/z invalido")
    return [finite(obj[k], what) for k in ("x", "y", "z")]


def rot(axis, angle):
    c, s = math.cos(angle), math.sin(angle)
    if axis == 0:
        return [1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1]
    if axis == 1:
        return [c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1]
    return [c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]


def key_matrix(position, rotation, scale, pivot):
    """translation(p).rotateXYZ(r).scale(s).translate(-pivot), igual que JOML en el cargador."""
    m = [1, 0, 0, position[0], 0, 1, 0, position[1], 0, 0, 1, position[2], 0, 0, 0, 1]
    for axis in range(3):
        m = mul(m, rot(axis, rotation[axis]))
    m = mul(m, [scale[0], 0, 0, 0, 0, scale[1], 0, 0, 0, 0, scale[2], 0, 0, 0, 0, 1])
    return mul(m, [1, 0, 0, -pivot[0], 0, 1, 0, -pivot[1], 0, 0, 1, -pivot[2], 0, 0, 0, 1])


def sample_keys(keys, time):
    a = keys[0]
    b = keys[-1]
    for k in keys:
        if k["time"] <= time:
            a = k
        if k["time"] >= time:
            b = k
            break
    u = 0.0 if b["time"] <= a["time"] else (time - a["time"]) / (b["time"] - a["time"])
    return [[a[f][i] + (b[f][i] - a[f][i]) * u for i in range(3)] for f in ("position", "rotation", "scale")]


def clean(v):
    v = round(v, 10)
    return 0 if abs(v) < 1e-10 else (int(v) if v == int(v) and abs(v) < 1e6 else v)


# ------------------------------------------------------------------ recorrido y saneado

class Converter:
    def __init__(self, recolor, text_color):
        self.recolor = recolor
        self.used_recolor = set()
        self.text_color = text_color
        self.groups = []
        self.pieces = []
        self.ids = set()
        self.dropped = set()
        self.counts = {"block": 0, "head": 0, "text": 0}
        self.flattened = 0

    def new_group(self, name, parent, mat, pivot=None, keys=None):
        if len(self.groups) >= MAX_GROUPS:
            raise Rejected("demasiados grupos animados")
        gid = f"g{len(self.groups) + 1}"
        g = {"id": gid, "name": name, "parent": parent, "matrix": mat}
        if pivot is not None:
            g["pivot"] = pivot
        if keys:
            g["keys"] = keys
        self.groups.append(g)
        return gid

    def walk(self, node, kept, acc, depth):
        if depth > MAX_DEPTH:
            raise Rejected("jerarquia demasiado profunda")
        if not isinstance(node, dict):
            raise Rejected("nodo que no es un objeto")
        name = node.get("name")
        if not isinstance(name, str):
            raise Rejected("nodo sin nombre")
        own = matrix(node.get("transforms"), f"transforms de {name}")
        if "children" in node or node.get("isCollection"):
            self.collection(node, name, own, kept, acc, depth)
        else:
            self.leaf(node, name, mul(acc, own), kept)

    def collection(self, node, name, own, kept, acc, depth):
        for field in ("nbt", "mainNBT"):
            value = node.get(field, "")
            if not isinstance(value, str) or not TAGS_ONLY.match(value):
                raise Rejected(f"{name}: {field} con algo mas que etiquetas")
            if value:
                self.dropped.add("etiquetas NBT (Tags) de las colecciones")
        children = node.get("children", [])
        if not isinstance(children, list):
            raise Rejected(f"{name}: children invalido")
        if node.get("listSound"):
            if any(t.get("tracks") for t in node["listSound"] if isinstance(t, dict)):
                raise Rejected(f"{name}: trae pistas de sonido")
            self.dropped.add("ajustes del editor (camara, fisicas, sonido vacio)")
        animation = node.get("animation") or []
        if not isinstance(animation, list):
            raise Rejected(f"{name}: animation invalida")
        if not animation:
            self.flattened += 1
            for child in children:
                self.walk(child, kept, mul(acc, own), depth + 1)
            return
        pivot = None
        if node.get("pivotCustom") is not None:
            p = node["pivotCustom"]
            if not isinstance(p, list) or len(p) != 3:
                raise Rejected(f"{name}: pivotCustom invalido")
            pivot = [finite(v, f"pivot de {name}") for v in p]
        if len(animation) > MAX_KEYS:
            raise Rejected(f"{name}: demasiadas claves")
        keys = []
        for k in animation:
            if not isinstance(k, dict) or not {"time", "position", "rotation", "scale"} <= set(k):
                raise Rejected(f"{name}: clave incompleta")
            extra = set(k) - {"time", "position", "rotation", "scale"}
            if extra:
                raise Rejected(f"{name}: clave con campos desconocidos {sorted(extra)}")
            t = finite(k["time"], f"time de {name}")
            if t < 0 or t > MAX_TIME or (keys and t <= keys[-1]["time"]):
                raise Rejected(f"{name}: claves fuera de orden o de rango")
            keys.append({"time": t, "position": vec3(k["position"], name), "rotation": vec3(k["rotation"], name),
                         "scale": vec3(k["scale"], name)})
        parent = kept
        if any(abs(a - b) > 1e-12 for a, b in zip(acc, IDENTITY)):
            # Las claves sustituyen la matriz propia; lo aplanado por encima va en un grupo fijo aparte.
            parent = self.new_group(f"{name} (base)", kept, acc)
        gid = self.new_group(name, parent, own, pivot, keys)
        for child in children:
            self.walk(child, gid, IDENTITY, depth + 1)

    def leaf(self, node, name, mat, group):
        if len(self.pieces) >= MAX_PIECES:
            raise Rejected("demasiadas piezas")
        nbt = node.get("nbt", "")
        if nbt not in ("", None):
            raise Rejected(f"{name}: pieza con NBT")
        uid = node.get("uuid")
        if not isinstance(uid, str) or not re.fullmatch(r"[0-9a-f-]{8,64}", uid) or uid in self.ids:
            raise Rejected(f"{name}: uuid invalido o repetido")
        self.ids.add(uid)
        piece = {"id": uid, "group": group}
        if node.get("isBlockDisplay"):
            kind = "block"
            state = node.get("defaultBlockState") or {"name": name, "variant": {}}
            block = state.get("name")
            if not isinstance(block, str) or not BLOCK_NAME.fullmatch(block) or block in DENIED_BLOCKS:
                raise Rejected(f"bloque no permitido: {block!r}")
            if state.get("variant"):
                raise Rejected(f"{block}: estados de bloque no soportados {state['variant']}")
            material = self.recolor.get(block)
            if material:
                self.used_recolor.add(block)
            else:
                material = block.upper()
            if not MATERIAL.fullmatch(material):
                raise Rejected(f"material invalido: {material}")
            piece.update(kind=kind, matrix=mat)
            piece["material"] = material
        elif node.get("isItemDisplay"):
            kind = "head"
            state = node.get("itemState") or {}
            if state.get("name") != "player_head" or state.get("components") not in ({}, None) \
                    or (state.get("variant") or {}).get("display", "none") != "none" \
                    or set(state.get("variant") or {}) - {"display"}:
                raise Rejected(f"{name}: solo se aceptan player_head sin componentes y sin transformacion de display")
            value = (node.get("tagHead") or {}).get("Value")
            default = node.get("defaultTextureValue")
            if isinstance(default, dict):
                default = default.get("value")
            if value is None:
                value = default
            if default is not None and default != value:
                raise Rejected(f"{name}: tagHead y defaultTextureValue no coinciden")
            if node.get("textureValueList"):
                raise Rejected(f"{name}: lista de texturas alternativas no soportada")
            validate_texture(value)
            piece.update(kind=kind, matrix=mat)
            piece["texture"] = value
        elif node.get("isTextDisplay"):
            kind = "text"
            state = node.get("defaultTextState") or {}
            text = state.get("text", name)
            if text != name:
                raise Rejected(f"texto incoherente: {name!r} / {text!r}")
            if not isinstance(text, str) or not 1 <= len(text) <= 8 or any(
                    ord(ch) < 32 or 0x7f <= ord(ch) < 0xa0 or ch == "§" for ch in text) \
                    or text.lstrip().startswith(("{", "[")):
                raise Rejected(f"texto no permitido: {text!r}")
            options = node.get("options") or {}
            if set(options) - TEXT_OPTION_KEYS:
                raise Rejected(f"texto con opciones desconocidas {sorted(set(options) - TEXT_OPTION_KEYS)}")
            if options.get("font", "minecraft:default") != "minecraft:default":
                raise Rejected("texto con fuente propia")
            self.dropped.add("color, fondo y estilos de los textos (color forzado, fondo transparente)")
            piece.update(kind=kind, matrix=mat)
            piece.update(text=text, color=self.text_color or options.get("color", "#FFFFFF").upper(),
                         background=0, shadow=False)
        else:
            raise Rejected(f"{name}: tipo de display desconocido")
        light = node.get("brightness")
        if light is not None:
            if not isinstance(light, dict) or set(light) != {"sky", "block"} or any(
                    not isinstance(light[k], int) or not 0 <= light[k] <= 15 for k in light):
                raise Rejected(f"{name}: brillo invalido")
            piece["brightness"] = {"sky": light["sky"], "block": light["block"]}
        self.counts[kind] += 1
        self.pieces.append(piece)


def validate_texture(value):
    if not isinstance(value, str) or not 16 <= len(value) <= 4096:
        raise Rejected("textura de cabeza vacia o enorme")
    try:
        profile = json.loads(base64.b64decode(value, validate=True).decode("utf-8"))
        url = profile["textures"]["SKIN"]["url"]
    except (binascii.Error, UnicodeDecodeError, json.JSONDecodeError, KeyError, TypeError) as fallo:
        raise Rejected(f"textura de cabeza que no decodifica a textures.SKIN.url: {fallo}") from fallo
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or parsed.hostname != "textures.minecraft.net" \
            or not SKIN_PATH.fullmatch(parsed.path) or parsed.query or parsed.fragment:
        raise Rejected(f"textura fuera de textures.minecraft.net: {url}")
    return url


# ------------------------------------------------------------------ normalizacion

def group_matrices(groups, time):
    out = {}
    for g in groups:  # los padres siempre van antes que los hijos
        local = g["matrix"]
        if g.get("keys"):
            p, r, s = sample_keys(g["keys"], time)
            local = key_matrix(p, r, s, g.get("pivot") or [0, 0, 0])
        out[g["id"]] = local if g["parent"] is None else mul(out[g["parent"]], local)
    return out


def corners(kind, m):
    (x0, x1), (y0, y1), (z0, z1) = BOX[kind]
    for x in (x0, x1):
        for y in (y0, y1):
            for z in (z0, z1):
                yield apply(m, (x, y, z))


def normalize(conv, fit):
    times = sorted({k["time"] for g in conv.groups for k in g.get("keys", [])})
    pose = times[-1] if times else 0.0
    world = group_matrices(conv.groups, pose)
    points = []
    for p in conv.pieces:
        m = p["matrix"] if p["group"] is None else mul(world[p["group"]], p["matrix"])
        points.extend(corners(p["kind"], m))
    lo = [min(q[i] for q in points) for i in range(3)]
    hi = [max(q[i] for q in points) for i in range(3)]
    if fit == "floor":
        cx, cz = (lo[0] + hi[0]) / 2, (lo[2] + hi[2]) / 2
        radius = max(math.hypot(q[0] - cx, q[2] - cz) for q in points)
        k = 1.0 / radius
        t = [-cx * k, -lo[1] * k, -cz * k]
    else:  # center: centro de la caja en el origen y media arista mayor = 1
        c = [(lo[i] + hi[i]) / 2 for i in range(3)]
        radius = max((hi[i] - lo[i]) / 2 for i in range(3))
        k = 1.0 / radius
        t = [-c[i] * k for i in range(3)]
    n = [k, 0, 0, t[0], 0, k, 0, t[1], 0, 0, k, t[2], 0, 0, 0, 1]
    for p in conv.pieces:
        if p["group"] is None:
            p["matrix"] = mul(n, p["matrix"])
    for g in conv.groups:
        if g["parent"] is None:
            g["matrix"] = mul(n, g["matrix"])
            for key in g.get("keys", []):
                key["position"] = [k * key["position"][i] + t[i] for i in range(3)]
                key["scale"] = [k * v for v in key["scale"]]
    # Comprobacion: tras normalizar, la pose usada mide lo prometido.
    world = group_matrices(conv.groups, pose)
    pts = []
    for p in conv.pieces:
        m = p["matrix"] if p["group"] is None else mul(world[p["group"]], p["matrix"])
        pts.extend(corners(p["kind"], m))
    min_y, max_y = min(q[1] for q in pts), max(q[1] for q in pts)
    if fit == "floor":
        r = max(math.hypot(q[0], q[2]) for q in pts)
        assert abs(r - 1) < 1e-6 and abs(min_y) < 1e-6, (r, min_y)
    else:
        r = max(max(abs(q[0]), abs(q[1]), abs(q[2])) for q in pts)
        assert abs(r - 1) < 1e-6, r
    return {"fit": fit, "pose": pose, "scale": k, "sourceRadius": radius, "minY": min_y, "maxY": max_y}


# ------------------------------------------------------------------ salida

def build_asset(args, root, data):
    fingerprint = hashlib.sha256(data).hexdigest()
    if args.sha256 and fingerprint != args.sha256.lower():
        raise Rejected(f"sha256 distinto del esperado: {fingerprint}")
    recolor = {}
    for item in args.recolor or []:
        src, _, dst = item.partition("=")
        if not BLOCK_NAME.fullmatch(src) or not MATERIAL.fullmatch(dst):
            raise SystemExit(f"--recolor mal escrito: {item}")
        recolor[src] = dst
    color = None
    if args.text_color:
        if not re.fullmatch(r"#?[0-9A-Fa-f]{6}", args.text_color):
            raise SystemExit("--text-color debe ser RRGGBB")
        color = "#" + args.text_color.lstrip("#").upper()
    conv = Converter(recolor, color)
    conv.walk(root, None, IDENTITY, 0)
    if not conv.pieces:
        raise Rejected("el modelo no tiene piezas")
    unused = set(recolor) - conv.used_recolor
    if unused:
        raise Rejected(f"recolor sin uso: {sorted(unused)}")
    if args.expect:
        want = dict(part.split("=") for part in args.expect.split(","))
        got = {k: v for k, v in conv.counts.items() if v}
        if {k: int(v) for k, v in want.items()} != got:
            raise Rejected(f"estructura inesperada: {got}, se esperaba {want}")
    info = normalize(conv, args.fit)
    for g in conv.groups:
        g["matrix"] = [clean(v) for v in g["matrix"]]
        if "pivot" in g:
            g["pivot"] = [clean(v) for v in g["pivot"]]
        for key in g.get("keys", []):
            key["time"] = clean(key["time"])
            for f in ("position", "rotation", "scale"):
                key[f] = [clean(v) for v in key[f]]
    pieces = []
    for p in conv.pieces:
        out = {"id": p["id"], "group": p["group"], "kind": p["kind"], "matrix": [clean(v) for v in p["matrix"]]}
        for f in ("brightness", "material", "texture", "text", "color", "background", "shadow"):
            if f in p:
                out[f] = p[f]
        pieces.append(out)
    asset = {
        "format": 2,
        "name": args.name,
        "source": args.source_url,
        "author": args.author,
        "license": args.license,
        "sourceSha256": fingerprint,
        "bounds": {"radius": 1.0, "minY": clean(info["minY"]), "maxY": clean(info["maxY"]), "fit": args.fit},
        "groups": conv.groups,
        "pieces": pieces,
    }
    return asset, conv, info, fingerprint, recolor, color


def serialize(asset):
    return json.dumps(asset, ensure_ascii=True, allow_nan=False, separators=(",", ":")) + "\n"


def notice_text(args, conv, info, fingerprint, recolor, color):
    animated = sum(1 for g in conv.groups if g.get("keys"))
    kept = [what for what, on in (("textos de un caracter", conv.counts["text"]),
                                  ("texturas de cabeza", conv.counts["head"]),
                                  ("claves de animacion", animated)) if on]
    lines = [
        f"Modelo: {args.title}",
        f"Recurso: {args.name}.json",
        f"Autor: {args.author}",
        f"Fuente: {args.source_url}",
        f"Licencia: {args.license} (permite uso comercial y obras derivadas con atribucion)",
        f"SHA-256 del .bdengine original: {fingerprint}",
        "",
        "Cambios respecto al original:",
        "- Convertido con tools/edm/bde-export.py a JSON formato 2: solo geometria"
        + "".join(", " + what for what in kept) + ".",
        f"- Colecciones sin animacion aplanadas en la matriz de sus hijos: {conv.flattened}. "
        f"Grupos animados conservados: {animated}.",
    ]
    if info["fit"] == "floor":
        lines.append(f"- Escalado x{info['scale']:.6f}: radio horizontal 1,0, centrado y con el suelo en y=0 "
                     f"(pose de la ultima clave, tick {clean(info['pose'])}).")
    else:
        lines.append(f"- Escalado x{info['scale']:.6f}: centro de la caja en el origen y media arista mayor 1,0.")
    for src, dst in sorted(recolor.items()):
        lines.append(f"- Recolor: {src} -> {dst}.")
    if color:
        lines.append(f"- Textos en {color}, fondo transparente, sin sombra ni estilos.")
    for item in sorted(conv.dropped):
        lines.append(f"- Descartado: {item}.")
    lines.append("- El .bdengine original no va en el jar; se guarda en tools/edm/fuentes/.")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source", type=Path)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--title", required=True)
    ap.add_argument("--source-url", required=True)
    ap.add_argument("--author", required=True)
    ap.add_argument("--license", required=True)
    ap.add_argument("--fit", choices=("floor", "center"), default="floor")
    ap.add_argument("--recolor", action="append", metavar="bloque=MATERIAL")
    ap.add_argument("--text-color", metavar="RRGGBB")
    ap.add_argument("--expect", help="conteo exacto por tipo, por ejemplo block=48,text=48")
    ap.add_argument("--sha256", help="huella esperada del .bdengine")
    ap.add_argument("--notice", type=Path, help="por omision <salida sin .json>.NOTICE.txt")
    ap.add_argument("--check", action="store_true", help="compara con la salida existente sin escribir")
    args = ap.parse_args()
    if not re.fullmatch(r"[a-z0-9_-]{1,64}", args.name):
        raise SystemExit("--name solo admite a-z, 0-9, _ y -")

    data = args.source.read_bytes()
    try:
        root = read_project(data)
        asset, conv, info, fingerprint, recolor, color = build_asset(args, root, data)
    except Rejected as fallo:
        print(f"RECHAZADO {args.source.name}: {fallo}", file=sys.stderr)
        raise SystemExit(2)
    payload = serialize(asset).encode("utf-8")
    notice_path = args.notice or args.output.with_name(args.output.stem + ".NOTICE.txt")
    notice = notice_text(args, conv, info, fingerprint, recolor, color).encode("utf-8")
    if args.check:
        same = args.output.exists() and args.output.read_bytes() == payload \
            and notice_path.exists() and notice_path.read_bytes() == notice
        print(("IGUAL " if same else "DISTINTO ") + str(args.output))
        raise SystemExit(0 if same else 1)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(payload)
    notice_path.write_bytes(notice)

    keys = {g["name"]: [k["time"] for k in g["keys"]] for g in conv.groups if g.get("keys")}
    print(f"{args.name}: {len(asset['pieces'])} piezas "
          + ", ".join(f"{k} {v}" for k, v in conv.counts.items() if v)
          + f" | grupos {len(conv.groups)} (animados {len(keys)}) | aplanadas {conv.flattened} colecciones")
    for name, times in keys.items():
        print(f"  grupo animado '{name}': {len(times)} claves en ticks {times}")
    b = asset["bounds"]
    print(f"  escala x{info['scale']:.6f} (radio fuente {info['sourceRadius']:.4f}) -> radio 1,0, "
          f"y {b['minY']}..{b['maxY']}, ajuste {b['fit']}")
    print(f"  {args.output} ({len(payload)} bytes) y {notice_path.name}")


if __name__ == "__main__":
    main()
