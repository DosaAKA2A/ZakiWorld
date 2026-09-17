"""Construye el datapack de Lethal World a partir de The Bracken Pack.

Bracken es "All Rights Reserved": ni su contenido ni lo que sale de aqui se sube al
repositorio (que es publico). Este script SI es nuestro. Lee Bracken y el diccionario
desde fuera del repo y deja el resultado en src/main/resources/mundos/datapack, que
esta en .gitignore y entra en el jar al compilar.

Uso:
    python construir.py extraer   -> textos visibles que quedan (para traducir)
    python construir.py construir -> el datapack procesado

Rutas por defecto (se pueden cambiar con LW_BRACKEN y LW_DICCIONARIO):
    ProyectosGit/_externos/datapacks/bracken/          Bracken descomprimido (v129)
    ProyectosGit/_externos/datapacks/lethal-world/nombres.json   ingles -> espanol

Que se queda: terreno, biomas, estructuras, sus mobs y aldeas.
Que se va: funciones, logros, recetas, dialogos, encantamientos, botin propio, variantes
de mobs, cuadros y todo lo que necesita resource pack (prohibido en Ederus), los
comerciantes ambulantes, los carteles y nombres en ingles, y las etiquetas que tocan
bloques vanilla de todo el servidor.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import sys
from collections import Counter
from pathlib import Path

import nbtlib
from nbtlib import Compound, Double, Float, List, String

AQUI = Path(__file__).resolve().parent
EDM = AQUI.parent.parent
EXTERNOS = EDM.parents[2] / "_externos" / "datapacks"
BRACKEN = Path(os.environ.get("LW_BRACKEN", EXTERNOS / "bracken"))
DICCIONARIO = Path(os.environ.get("LW_DICCIONARIO", EXTERNOS / "lethal-world" / "nombres.json"))
SALIDA = EDM / "src" / "main" / "resources" / "mundos"

# Carpetas de data/<ns>/ que pasan al datapack
CONSERVAR = ("worldgen/", "dimension_type/", "structure/", "timeline/", "tags/worldgen/", "tags/timeline/")
# Etiquetas de bloque nuevas que usan los tipos de dimension (no tocan vanilla)
ETIQUETAS_BLOQUE = re.compile(r"^data/minecraft/tags/block/infiniburn_[a-z_]+\.json$")

# Botin: la tabla de Bracken -> la vanilla que mas se le parece. Primer patron que encaje.
BOTIN_VANILLA = [
    (r"library", "minecraft:chests/stronghold_library"),
    (r"mineshaft|railway", "minecraft:chests/abandoned_mineshaft"),
    (r"castle/(castle|troop|prison)|army|pillar|marauder|den|guard", "minecraft:chests/pillager_outpost"),
    (r"kitchen|farm|garden|greenhouse|storage|house|building|city|station|theatre|double_tree", "minecraft:chests/village/village_plains_house"),
    (r"brine|reef|sponge|pool|pearl|petal|cordyceps", "minecraft:chests/underwater_ruin_small"),
    (r"glacium|igloo|flower|cave|hotspring", "minecraft:chests/igloo_chest"),
    (r".*", "minecraft:chests/simple_dungeon"),
]


def botin_vanilla(tabla: str) -> str:
    for patron, vanilla in BOTIN_VANILLA:
        if re.search(patron, tabla):
            return vanilla
    return "minecraft:chests/simple_dungeon"


# ------------------------------------------------------------------ textos


def texto_plano(valor) -> str | None:
    """El texto legible de un nombre, sea String JSON (formato viejo) o Compound."""
    if valor is None:
        return None
    if isinstance(valor, Compound):
        partes = [str(valor.get("text", ""))] + [texto_plano(x) or "" for x in valor.get("extra", [])]
        if "translate" in valor and not str(valor.get("text", "")):
            partes.insert(0, str(valor["translate"]))
        return "".join(partes).strip() or None
    s = str(valor)
    try:
        j = json.loads(s)
    except (ValueError, TypeError):
        return s.strip() or None
    if isinstance(j, str):
        return j.strip() or None
    if isinstance(j, dict):
        t = j.get("text") or j.get("translate") or ""
        t += "".join(x.get("text", "") if isinstance(x, dict) else str(x) for x in j.get("extra", []))
        return t.strip() or None
    return s


def poner_texto(original, nuevo: str):
    """Mismo formato que tenia el nombre original, con el texto cambiado y sin extras."""
    if isinstance(original, Compound):
        c = Compound({k: v for k, v in original.items() if k not in ("text", "translate", "extra", "fallback")})
        c["text"] = String(nuevo)
        return c
    try:
        j = json.loads(str(original))
        if isinstance(j, dict):
            j = {k: v for k, v in j.items() if k not in ("text", "translate", "extra", "fallback")}
            j["text"] = nuevo
            return String(json.dumps(j, ensure_ascii=False))
    except (ValueError, TypeError):
        pass
    return String(nuevo)


class Textos:
    def __init__(self, modo: str):
        self.modo = modo
        self.vistos: Counter[str] = Counter()
        self.faltan: Counter[str] = Counter()
        self.dic: dict[str, str] = {}
        if modo == "construir":
            if not DICCIONARIO.is_file():
                sys.exit(f"Falta el diccionario {DICCIONARIO}. Corre primero 'extraer'.")
            self.dic = json.loads(DICCIONARIO.read_text(encoding="utf-8"))

    def traducir(self, original):
        """Devuelve el nombre traducido, o None si hay que quitarlo."""
        plano = texto_plano(original)
        if not plano:
            return None
        self.vistos[plano] += 1
        if self.modo == "extraer":
            return original
        es = self.dic.get(plano)
        if es is None:
            self.faltan[plano] += 1
            return original
        if es == "":
            return None
        return poner_texto(original, es)


# ------------------------------------------------------------------ entidades


def tiene_modelo_bracken(item) -> bool:
    return item is not None and "bracken:" in str(item.get("components", Compound()).get("minecraft:item_model", ""))


def limpiar_item(item) -> Compound | None:
    """Un objeto equipado sin lo que depende de Bracken. None = quitarlo entero."""
    if item is None or not isinstance(item, Compound) or "id" not in item:
        return item
    comps = item.get("components")
    if comps is None:
        return item
    for clave in ("minecraft:item_model", "minecraft:lore", "minecraft:custom_name", "minecraft:item_name",
                  "minecraft:custom_data", "minecraft:rarity", "minecraft:repair_cost", "minecraft:custom_model_data"):
        if clave in comps:
            del comps[clave]
    eq = comps.get("minecraft:equippable")
    if eq is not None and "bracken:" in str(eq):
        del comps["minecraft:equippable"]
    trim = comps.get("minecraft:trim")
    if trim is not None and "bracken:" in str(trim):
        del comps["minecraft:trim"]
    for clave in ("minecraft:enchantments", "minecraft:stored_enchantments"):
        ench = comps.get(clave)
        if ench is None:
            continue
        niveles = ench.get("levels", ench)
        for k in [k for k in niveles.keys() if str(k).startswith("bracken:")]:
            del niveles[k]
    if len(comps) == 0:
        del item["components"]
    return item


RANURAS_VIEJAS = {"ArmorItems": ["feet", "legs", "chest", "head"], "HandItems": ["mainhand", "offhand"]}


def limpiar_entidad(en: Compound, textos: Textos, cuenta: Counter) -> bool:
    """Deja la entidad sin nada de Bracken. Devuelve False si hay que borrarla."""
    eid = str(en.get("id", ""))
    todo = str(en)

    if eid == "minecraft:wandering_trader":
        cuenta["comerciantes quitados"] += 1
        return False
    if eid == "minecraft:painting" and "bracken:" in todo:
        cuenta["cuadros quitados"] += 1
        return False
    if eid in ("minecraft:item_display", "minecraft:block_display", "minecraft:text_display") and "bracken:" in todo:
        cuenta["displays quitados"] += 1
        return False
    if eid == "minecraft:item":
        item = en.get("Item")
        if tiene_modelo_bracken(item):
            cuenta["objetos sueltos quitados"] += 1
            return False
        limpiar_item(item)
    if eid in ("minecraft:item_frame", "minecraft:glow_item_frame"):
        item = en.get("Item")
        if tiene_modelo_bracken(item):
            del en["Item"]
            cuenta["marcos vaciados"] += 1
        elif item is not None:
            limpiar_item(item)
    invisible = ("Invisible" in en and int(en["Invisible"])) or "minecraft:invisibility" in str(en.get("active_effects", ""))
    if eid == "minecraft:armor_stand" and invisible and "bracken:" in todo:
        cuenta["estatuas invisibles quitadas"] += 1
        return False

    # disfraz de la cabeza y equipo con modelos
    quito_disfraz = False
    eq = en.get("equipment")
    if isinstance(eq, Compound):
        for ranura in list(eq.keys()):
            item = eq[ranura]
            if ranura == "head" and tiene_modelo_bracken(item):
                del eq[ranura]
                quito_disfraz = True
                continue
            eq[ranura] = limpiar_item(item)
    for lista, ranuras in RANURAS_VIEJAS.items():
        items = en.get(lista)
        if items is None:
            continue
        for i, item in enumerate(items):
            if ranuras[i] == "head" and tiene_modelo_bracken(item):
                items[i] = Compound()
                quito_disfraz = True
            else:
                limpiar_item(item)
    if quito_disfraz:
        cuenta["disfraces quitados"] += 1
        if invisible:
            en.pop("Invisible", None)
            efectos = en.get("active_effects")
            if efectos is not None:
                en["active_effects"] = List[Compound]([e for e in efectos if str(e.get("id")) != "minecraft:invisibility"])
            cuenta["mobs hechos visibles"] += 1

    # sin drops propios: nada del equipo cae y el botin es el vanilla del mob base
    if "DeathLootTable" in en and str(en["DeathLootTable"]).startswith("bracken:"):
        del en["DeathLootTable"]
        cuenta["botin de muerte quitado"] += 1
    if isinstance(en.get("drop_chances"), Compound):
        for k in list(en["drop_chances"].keys()):
            en["drop_chances"][k] = Float(0.0)
    for k in ("ArmorDropChances", "HandDropChances"):
        if k in en:
            en[k] = List[Float]([Float(0.0)] * len(en[k]))

    # aldeanos: sin tratos de Bracken recuperan los vanilla
    if "Offers" in en and "bracken:" in str(en["Offers"]):
        del en["Offers"]
        cuenta["tratos de aldeano quitados"] += 1

    # nada por encima de escala 2: los gigantes (el husk x5 del organo) no caben ni se ven bien
    for lista in ("attributes", "Attributes"):
        for a in en.get(lista) or []:
            ident = str(a.get("id", a.get("Name", ""))).replace("minecraft:", "").replace("generic.", "")
            base = "base" if "base" in a else "Base"
            if ident == "scale" and base in a and float(a[base]) > ESCALA_MAXIMA:
                a[base] = Double(ESCALA_MAXIMA)
                cuenta["escalas acotadas a 2"] += 1

    if "CustomName" in en:
        nuevo = textos.traducir(en["CustomName"])
        if nuevo is None:
            del en["CustomName"]
            en.pop("CustomNameVisible", None)
        else:
            en["CustomName"] = nuevo

    pasajeros = en.get("Passengers")
    if pasajeros is not None:
        en["Passengers"] = List[Compound]([p for p in pasajeros if limpiar_entidad(p, textos, cuenta)])
    return True


def limpiar_spawner(bn: Compound, textos: Textos, cuenta: Counter) -> None:
    """Spawner normal (SpawnData/SpawnPotentials) y de desafio (normal_config/ominous_config)."""
    bloques = [bn] + [bn[c] for c in ("normal_config", "ominous_config") if isinstance(bn.get(c), Compound)]
    for b in bloques:
        for clave in ("SpawnData", "spawn_data"):
            sd = b.get(clave)
            if sd is not None and "entity" in sd:
                if not limpiar_entidad(sd["entity"], textos, cuenta):
                    sd["entity"] = Compound({"id": String("minecraft:zombie")})
        for clave in ("SpawnPotentials", "spawn_potentials"):
            pot = b.get(clave)
            if pot is None:
                continue
            quedan = []
            for p in pot:
                ent = p.get("data", Compound()).get("entity")
                if ent is None or limpiar_entidad(ent, textos, cuenta):
                    quedan.append(p)
            b[clave] = List[Compound](quedan)


def limpiar_cartel(bn: Compound, textos: Textos, cuenta: Counter) -> None:
    for cara in ("front_text", "back_text"):
        t = bn.get(cara)
        if t is None or "messages" not in t:
            continue
        mensajes = t["messages"]
        for i, m in enumerate(mensajes):
            if not texto_plano(m):
                continue
            nuevo = textos.traducir(m)
            mensajes[i] = nuevo if nuevo is not None else String('""')
            cuenta["lineas de cartel"] += 1


def limpiar_estructura(f: Path, textos: Textos, cuenta: Counter) -> bool:
    nbt = nbtlib.load(f)
    cambiado = False

    ents = nbt.get("entities")
    if ents is not None:
        quedan = [e for e in ents if limpiar_entidad(e["nbt"], textos, cuenta)]
        if len(quedan) != len(ents) or textos.modo == "construir":
            nbt["entities"] = List[Compound](quedan)
            cambiado = True

    paletas = nbt.get("palettes")
    paleta = nbt.get("palette") if paletas is None else paletas[0]
    if paleta is not None:
        nombres = [str(p.get("Name", "")) for p in paleta]
        comandos = {i for i, n in enumerate(nombres) if "command_block" in n}
        aire = nombres.index("minecraft:air") if "minecraft:air" in nombres else None
        for b in nbt.get("blocks", []):
            bn = b.get("nbt")
            if int(b["state"]) in comandos:
                if aire is None:
                    paleta.append(Compound({"Name": String("minecraft:air")}))
                    aire = len(paleta) - 1
                b["state"] = nbtlib.Int(aire)
                b.pop("nbt", None)
                cuenta["bloques de comando quitados"] += 1
                cambiado = True
                continue
            if bn is None:
                continue
            if "LootTable" in bn and str(bn["LootTable"]).startswith("bracken:"):
                bn["LootTable"] = String(botin_vanilla(str(bn["LootTable"])))
                cuenta["cofres con botin vanilla"] += 1
                cambiado = True
            if "SpawnData" in bn or "SpawnPotentials" in bn or "spawn_data" in bn:
                limpiar_spawner(bn, textos, cuenta)
                cambiado = True
            if "front_text" in bn or "back_text" in bn:
                limpiar_cartel(bn, textos, cuenta)
                cambiado = True
            if "CustomName" in bn:
                nuevo = textos.traducir(bn["CustomName"])
                if nuevo is None:
                    del bn["CustomName"]
                else:
                    bn["CustomName"] = nuevo
                cambiado = True
            for clave in ("Book", "Items", "item"):
                if clave in bn and ("minecraft:written_book_content" in str(bn[clave]) or "bracken:" in str(bn[clave])):
                    del bn[clave]
                    cuenta["libros y objetos de Bracken quitados"] += 1
                    cambiado = True

    if cambiado and textos.modo == "construir":
        nbt.save(f, gzipped=True)
    return cambiado


# ------------------------------------------------------------------ json


RADIO_SEGURO = {"xz_radius": 8, "xz_spread": 7}


def acotar_radios(nodo, cuenta: Counter) -> None:
    """Parches de vegetacion y grupos de plantas que se salen del radio que el mundo deja
    escribir al generar. Paper lo corta y lo registra por CADA bloque: el trigo de Pax
    (xz_radius 15) llego a escribir 68.000 lineas de error en dos minutos."""
    if isinstance(nodo, dict):
        for clave, tope in RADIO_SEGURO.items():
            v = nodo.get(clave)
            if isinstance(v, int) and v > tope:
                nodo[clave] = tope
                cuenta[f"radios acotados ({clave})"] += 1
            elif isinstance(v, dict) and "max_inclusive" in v and isinstance(v["max_inclusive"], int) and v["max_inclusive"] > tope:
                v["max_inclusive"] = tope
                cuenta[f"radios acotados ({clave})"] += 1
        for v in nodo.values():
            acotar_radios(v, cuenta)
    elif isinstance(nodo, list):
        for v in nodo:
            acotar_radios(v, cuenta)


ESCALA_MAXIMA = 2.0

# Biomas repintados. Sin shaders: cielo del color, niebla (horizonte) mas clara y apagada,
# luz del cielo casi blanca y nubes palidas, o el cielo se ve azul con el horizonte rojo.
PINTURAS = {
    "panacea/wildflower_bog": {
        "attributes": {
            "minecraft:visual/sky_color": "#8E1010",
            "minecraft:visual/fog_color": "#B8483F",
            "minecraft:visual/water_fog_color": "#4A0808",
            "minecraft:visual/sky_light_color": "#FFD6CC",
            "minecraft:visual/cloud_color": "#FFE6BDB6",
        },
        "effects": {
            "sky_color": 0x8E1010,
            "fog_color": 0xB8483F,
            "water_color": 0xB01818,
            "water_fog_color": 0x4A0808,
            "grass_color": 0xB3201F,
            "foliage_color": 0xA51A1A,
            "dry_foliage_color": 0x7A1E1E,
        },
    },
}


def pasto_en_la_cienaga(ajustes):
    """El bloque base de Panacea es musgo y la cienaga no tenia regla de superficie: salia
    musgo arriba y el musgo no se tine con el color del bioma. Recibe la misma regla que el
    bosque voraz, con pasto tambien en la capa de debajo."""
    secuencia = ajustes["surface_rule"]["sequence"]
    bog = "bracken:panacea/wildflower_bog"
    if any(bog in json.dumps(r.get("if_true", {})) for r in secuencia):
        return ajustes
    for i, regla in enumerate(secuencia):
        if regla.get("if_true", {}).get("biome_is") == ["bracken:panacea/ravenous_greenwood"]:
            copia = json.loads(json.dumps(regla))
            copia["if_true"]["biome_is"] = [bog]
            # Nada de tierra: donde habia musgo va pasto, tambien en la capa de debajo.
            texto = json.dumps(copia).replace('"minecraft:dirt"', '"minecraft:grass_block"')
            copia = json.loads(texto)
            secuencia.insert(i + 1, copia)
            return ajustes
    raise SystemExit("No encuentro la regla de superficie del bosque voraz en Panacea")


def orillas_de_pasto(feature):
    """Las charcas de la cienaga llevaban orilla de musgo: pasa a pasto, que si se tine."""
    return json.loads(json.dumps(feature).replace('"minecraft:moss_block"', '"minecraft:grass_block"'))


PARCHES_JSON = {
    "data/bracken/worldgen/noise_settings/panacea.json": pasto_en_la_cienaga,
    "data/bracken/worldgen/configured_feature/panacea/bogwater.json": orillas_de_pasto,
    "data/bracken/worldgen/configured_feature/panacea/bogwater2.json": orillas_de_pasto,
}


def limpiar_json(ruta_rel: str, datos):
    """Biomas y tipos de dimension: fuera la musica y los sonidos de Bracken, y los
    biomas repintados reciben sus colores."""
    if not isinstance(datos, dict):
        return datos
    atributos = datos.get("attributes")
    if isinstance(atributos, dict):
        for k in [k for k, v in atributos.items() if "bracken:" in json.dumps(v) and "audio" in k]:
            del atributos[k]
    m = re.search(r"/worldgen/biome/(.+)\.json$", ruta_rel)
    pintura = PINTURAS.get(m.group(1)) if m else None
    if pintura:
        atributos = datos.setdefault("attributes", {})
        for k in list(atributos):
            if k.replace("minecraft:", "") in {a.replace("minecraft:", "") for a in pintura["attributes"]}:
                del atributos[k]
        atributos.update(pintura["attributes"])
        datos.setdefault("effects", {}).update(pintura["effects"])
    return datos


# ------------------------------------------------------------------ comprobacion


def comprobar_referencias(raiz: Path) -> list[str]:
    """Referencias bracken:x que ya no apuntan a nada de lo que queda."""
    existentes = set()
    for f in raiz.rglob("*"):
        if f.is_file() and f.suffix in (".json", ".nbt"):
            partes = f.relative_to(raiz).as_posix().split("/")
            if len(partes) < 3 or partes[0] != "data":
                continue
            ns, resto = partes[1], "/".join(partes[2:]).rsplit(".", 1)[0]
            trozos = resto.split("/")
            for n in range(1, 4):
                if len(trozos) > n:
                    existentes.add(f"{ns}:{'/'.join(trozos[n:])}")
    rotas = []
    for f in raiz.rglob("*.json"):
        texto = f.read_text(encoding="utf-8")
        for m in re.finditer(r'"#?(bracken:[a-z0-9_./-]+)"', texto):
            if m.group(1) in existentes or m.group(1) in ROTAS_DE_ORIGEN:
                continue
            if '"sound_id"' in texto[max(0, m.start() - 20):m.start()]:
                continue
            rotas.append(f"{m.group(1)} en {f.relative_to(raiz).as_posix()}")
    return rotas


# Piezas que Bracken v129 ya referencia sin incluirlas: Minecraft las salta sin romper nada.
ROTAS_DE_ORIGEN = {
    "bracken:omnidrome/omnidrome_palace_fin",
    "bracken:dormis/shechemium/dormis1",
    "bracken:dormis/shechemium/dormis2",
    "bracken:dormis/shechemium/dormis3",
    "bracken:village/fae/houses/fae_animal_pen_1",
}


# ------------------------------------------------------------------ main


def main() -> None:
    modo = sys.argv[1] if len(sys.argv) > 1 else ""
    if modo not in ("extraer", "construir"):
        sys.exit(__doc__)
    if not BRACKEN.is_dir():
        sys.exit(f"No encuentro Bracken en {BRACKEN}")

    textos = Textos(modo)
    cuenta: Counter[str] = Counter()

    if modo == "extraer":
        for f in sorted((BRACKEN / "data").rglob("*.nbt")):
            limpiar_estructura(f, textos, cuenta)
        DICCIONARIO.parent.mkdir(parents=True, exist_ok=True)
        previo = json.loads(DICCIONARIO.read_text(encoding="utf-8")) if DICCIONARIO.is_file() else {}
        nuevo = {t: previo.get(t) for t in sorted(textos.vistos)}
        faltan = [t for t, v in nuevo.items() if v is None]
        DICCIONARIO.write_text(json.dumps(nuevo, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"{len(nuevo)} textos distintos, {len(faltan)} sin traducir -> {DICCIONARIO}")
        for k, v in sorted(cuenta.items()):
            print(f"  {k}: {v}")
        return

    datapack = SALIDA / "datapack"
    generadores = SALIDA / "generadores"
    shutil.rmtree(datapack, ignore_errors=True)
    shutil.rmtree(generadores, ignore_errors=True)
    datapack.mkdir(parents=True)
    generadores.mkdir(parents=True)

    copiados = 0
    for f in sorted(BRACKEN.rglob("*")):
        if not f.is_file():
            continue
        rel = f.relative_to(BRACKEN).as_posix()
        m = re.match(r"^data/([^/]+)/(.+)$", rel)
        if m and m.group(1) == "bracken" and m.group(2).startswith("dimension/"):
            destino = generadores / Path(m.group(2)).name
            shutil.copyfile(f, destino)
            continue
        conservar = bool(m) and (
            (m.group(1) == "bracken" and m.group(2).startswith(CONSERVAR))
            or (m.group(1) == "minecraft" and m.group(2).startswith("tags/worldgen/"))
            or bool(ETIQUETAS_BLOQUE.match(rel)))
        if not conservar:
            continue
        destino = datapack / rel
        destino.parent.mkdir(parents=True, exist_ok=True)
        if f.suffix == ".json" and ("/worldgen/biome/" in rel or "/dimension_type/" in rel):
            datos = limpiar_json(rel, json.loads(f.read_text(encoding="utf-8")))
            destino.write_text(json.dumps(datos, ensure_ascii=False, indent=1), encoding="utf-8")
        elif f.suffix == ".json" and rel in PARCHES_JSON:
            datos = PARCHES_JSON[rel](json.loads(f.read_text(encoding="utf-8")))
            if "/worldgen/configured_feature/" in rel:
                acotar_radios(datos, cuenta)
            cuenta["ficheros parcheados"] += 1
            destino.write_text(json.dumps(datos, ensure_ascii=False, indent=1), encoding="utf-8")
        elif f.suffix == ".json" and "/worldgen/configured_feature/" in rel:
            datos = json.loads(f.read_text(encoding="utf-8"))
            acotar_radios(datos, cuenta)
            destino.write_text(json.dumps(datos, ensure_ascii=False, indent=1), encoding="utf-8")
        else:
            shutil.copyfile(f, destino)
        copiados += 1

    (datapack / "pack.mcmeta").write_text(json.dumps({
        "pack": {"description": "Lethal World - generadores de mundo de EDM", "min_format": [107, 1], "max_format": [107, 1]}
    }, indent=1), encoding="utf-8")

    tocadas = 0
    for f in sorted(datapack.rglob("*.nbt")):
        if limpiar_estructura(f, textos, cuenta):
            tocadas += 1

    # Indices: EDM no puede listar carpetas dentro de su propio jar, asi que sabe que copiar por aqui.
    (SALIDA / "datapack.index").write_text(
        "\n".join(sorted(f.relative_to(datapack).as_posix() for f in datapack.rglob("*") if f.is_file())) + "\n",
        encoding="utf-8")
    (SALIDA / "generadores.index").write_text(
        "\n".join(sorted(f.stem for f in generadores.glob("*.json"))) + "\n", encoding="utf-8")

    print(f"datapack: {copiados} ficheros, {tocadas} estructuras tocadas, {len(list(generadores.iterdir()))} generadores")
    for k, v in sorted(cuenta.items()):
        print(f"  {k}: {v}")
    if textos.faltan:
        print(f"SIN TRADUCIR ({len(textos.faltan)}):")
        for t, n in textos.faltan.most_common():
            print(f"  {n:4d}  {t}")
    rotas = comprobar_referencias(datapack)
    print(f"referencias rotas: {len(rotas)}")
    for r in rotas[:30]:
        print("  " + r)
    restos = Counter()
    for f in datapack.rglob("*.nbt"):
        nbt = nbtlib.load(f)
        s = str(nbt)
        if re.search(r"item_model': String\('bracken:", s):
            restos["modelos de Bracken"] += 1
        if "wandering_trader" in s:
            restos["comerciantes"] += 1
        if re.search(r"LootTable': String\('bracken:", s):
            restos["botin de Bracken"] += 1
        paletas = nbt.get("palettes")
        paleta = nbt.get("palette") if paletas is None else paletas[0]
        if paleta is not None:
            comandos = {i for i, p in enumerate(paleta) if "command_block" in str(p.get("Name", ""))}
            if any(int(b["state"]) in comandos for b in nbt.get("blocks", [])):
                restos["bloques de comando colocados"] += 1
    print(f"restos en NBT: {dict(restos) or 'ninguno'}")
    if textos.faltan or rotas:
        sys.exit(1)


if __name__ == "__main__":
    main()
