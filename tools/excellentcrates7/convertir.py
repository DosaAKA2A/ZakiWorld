"""PhoenixCrates (Survival) -> ExcellentCrates 7.1.0.

Uso: python -I convertir.py <PhoenixCrates del Survival> <config EDM del dev> <calidad-por-id.json> <salida>

Probabilidades: cada caja tiene un % por calidad y cada premio un peso dentro de su
calidad. ExcellentCrates 7 sortea con un peso plano por premio, asi que el peso que se
escribe es P(calidad) * P(premio dentro de la calidad) * 10000: sale exactamente lo
mismo que el sorteo en dos pasos.
"""
import json, math, os, re, sys
import yaml
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from texto import a_snbt, a_mm, plano

SRC, EDM_CFG, QJSON, OUT = sys.argv[1:5]
# Opcional: locations.yml del servidor de destino (el dev tiene las cajas en otro sitio que el Survival).
LOCS = sys.argv[5] if len(sys.argv) > 5 else None

# ---------------------------------------------------------------- calidades
CALIDADES = [  # de mas comun a mas rara
    ('comun', 'Común', '#E0E0E0'),
    ('raro', 'Raro', '#7CFF7C'),
    ('epico', 'Épico', '#FF77FF'),
    ('mitico', 'Mítico', '#C79BFF'),
    ('legendario', 'Legendario', '#FFB347'),
    ('ancestral', 'Ancestral', '#FF7A45'),
    ('inmortal', 'Inmortal', '#FF5C7A'),
    ('unico', 'Único', '#FFAA00'),
]
CAL = {k: (n, c) for k, n, c in CALIDADES}
ORDEN = [k for k, _, _ in CALIDADES]
UNA_VEZ = {'unico'}

# Los ids de llaves y cajas se conservan: nuestra 7.1.0 (rama ederus) resuelve en PlaceholderAPI
# los ids con "_" y acepta "/crates key give" como ExcellentCrates 6.
LLAVES = {}
CAJAS = {}
llave = lambda k: LLAVES.get(k, k)
cajaid = lambda k: CAJAS.get(k, k)

# Cajas que se igualan al diseño de calidades.json (en el Survival las calidades no iban en orden).
DISENO = {
    'cajaepica': {'raro': 60, 'epico': 30, 'mitico': 8, 'legendario': 2},
    'vote': {'comun': 40, 'raro': 35, 'epico': 20, 'mitico': 4, 'legendario': 1},
}
# Premios sin calidad asignada en calidades.json (añadidos despues del 06-10).
EXTRA = {
    'vote': {
        'vote_juramento_esmeralda_helmet': 'raro', 'vote_juramento_esmeralda_chestplate': 'raro',
        'vote_juramento_esmeralda_leggings': 'raro', 'vote_juramento_esmeralda_boots': 'raro',
        'vote_pico_raiz': 'raro', 'vote_hacha_verdor': 'raro', 'vote_pala_sendero': 'raro',
        'vote_azada_brote': 'raro', 'vote_filo_juramento': 'epico', 'vote_arco_bosque': 'epico',
        'vote_escudo_guardian': 'epico', 'vote_tag_alba': 'mitico', 'vote_tag_brote': 'mitico',
        'vote_tag_aura': 'mitico', 'vote_tag_jade': 'mitico',
    },
}


def calidad_comunitaria(rid):
    if '_oro_' in rid or '_coins_' in rid:
        return 'comun'
    if '_brasa' in rid:
        return 'raro'
    if '_escarcha' in rid:
        return 'epico'
    if rid.endswith(('_vigilia', '_mandato')) or 'filo_concord' in rid:
        return 'mitico'
    return 'legendario'  # tags benefactor/mecenas/arconte/patrono


def porcentaje(f):
    """Igual que Sorteo.porcentaje de EDM: 66,5 · 1,02 · 0,057."""
    v = f * 100
    if v <= 0:
        return '0'
    d = 1 if v >= 10 else 2 if v >= 1 else min(5, 1 - math.floor(math.log10(v)))
    s = '%.*f' % (d, v)
    if '.' in s:
        s = s.rstrip('0').rstrip('.')
    return s.replace('.', ',')


# ---------------------------------------------------------------- items
def lista_snbt(s):
    """Parte una lista SNBT '[a,b,...]' en sus elementos de primer nivel."""
    s = s.strip()
    assert s[0] == '[' and s[-1] == ']', s[:40]
    out, prof, q, ini, i = [], 0, None, 1, 1
    while i < len(s) - 1:
        c = s[i]
        if q:
            if c == '\\':
                i += 1
            elif c == q:
                q = None
        elif c in '"\'':
            q = c
        elif c in '{[':
            prof += 1
        elif c in '}]':
            prof -= 1
        elif c == ',' and prof == 0:
            out.append(s[ini:i].strip())
            ini = i + 1
        i += 1
    if s[ini:-1].strip():
        out.append(s[ini:-1].strip())
    return out


def texto_de(el):
    return ''.join(re.findall(r'(?:"text"|text)\s*:\s*"((?:[^"\\]|\\.)*)"', el)) + \
        ''.join(re.findall(r"(?:\"text\"|text)\s*:\s*'((?:[^'\\]|\\.)*)'", el))


VIEJA = re.compile(r'◆ ?(Común|Raro|Épico|Mítico|Legendario|Ancestral|Inmortal|Único)|Probabilidad|Dentro de\s*la calidad')


def vacio(el):
    return texto_de(el).strip() == '' and el.strip() not in ()


def limpiar_lore(elems):
    """Quita las lineas de calidad/probabilidad viejas y los vacios que quedan al final."""
    out = [e for e in elems if not VIEJA.search(texto_de(e) if e.startswith(('{', '[')) else e)]
    while out and (out[-1] in ('""', "''") or (out[-1].startswith('{') and vacio(out[-1]) and 'extra' not in out[-1])):
        out.pop()
    return out


def linea_calidad(q, prob):
    nombre, color = CAL[q]
    return ('{text:"",italic:0b,extra:[{text:"◆ %s",color:"%s",italic:0b},'
            '{text:" · %s %%",color:"#8F8F8F",italic:0b}]}' % (nombre, color, porcentaje(prob)))


def snbt_de_bukkit(it, extra_lore=None):
    """ItemStack serializado por Bukkit (id, count, components) -> SNBT del codec vanilla."""
    comps = dict(it.get('components') or {})
    if extra_lore is not None:
        lore = limpiar_lore(lista_snbt(comps['minecraft:lore'])) if 'minecraft:lore' in comps else []
        lore += extra_lore
        comps['minecraft:lore'] = '[' + ','.join(lore) + ']'
    partes = ['id:"%s"' % it['id'], 'count:%d' % int(it.get('count', 1))]
    if comps:
        partes.append('components:{' + ','.join('"%s":%s' % (k, v) for k, v in comps.items()) + '}')
    return '{' + ','.join(partes) + '}'


def snbt_simple(di, extra_lore=None):
    """display-item de PhoenixCrates con material/display-name/lore."""
    comps = {}
    if di.get('display-name'):
        comps['minecraft:custom_name'] = a_snbt(di['display-name'])
    lore = [a_snbt(l) for l in (di.get('lore') or []) if not VIEJA.search(plano(l))]
    while lore and lore[-1] == '{text:"",italic:0b}':
        lore.pop()
    if extra_lore is not None:
        lore += extra_lore
    if lore:
        comps['minecraft:lore'] = '[' + ','.join(lore) + ']'
    if di.get('enchants'):
        comps['minecraft:enchantments'] = '{' + ','.join(
            '"minecraft:%s":%d' % (k.lower(), v) for k, v in di['enchants'].items()) + '}'
    if di.get('hide-tooltip'):
        comps['minecraft:tooltip_display'] = '{hide_tooltip:1b}'
    return snbt_de_bukkit({'id': 'minecraft:' + di['material'].lower(), 'count': di.get('amount', 1),
                           'components': comps})


def snbt_item(di, almacen, extra_lore=None):
    if '==' in di:
        return snbt_de_bukkit(di, extra_lore)
    mat = str(di.get('material', 'STONE'))
    if mat.startswith('custom:'):
        it = almacen.get(mat[7:])
        if it is None:
            raise KeyError('item guardado no encontrado: ' + mat)
        it = dict(it)
        if di.get('amount', 1) != 1:
            it['count'] = di['amount']
        return snbt_de_bukkit(it, extra_lore)
    return snbt_simple(di, extra_lore)


def adaptado(snbt):
    return {'Provider': 'vanilla', 'Data': {'Value': snbt, 'DataVersion': 4903}}


def nombre_item(di, almacen):
    """Nombre plano del premio (para el nombre del preview y los informes)."""
    it = di if '==' in di else almacen.get(str(di.get('material', ''))[7:]) if str(di.get('material', '')).startswith('custom:') else None
    if it:
        cn = (it.get('components') or {}).get('minecraft:custom_name') or (it.get('components') or {}).get('minecraft:item_name')
        if cn:
            return texto_de(cn) or cn.strip('"\'')
        return it['id'].split(':')[1].replace('_', ' ').title()
    return plano(di.get('display-name') or di.get('material', '').replace('_', ' ').title())


# ---------------------------------------------------------------- comandos
def comando(c):
    m = re.match(r'(?:/)?pcrates\s+givekey\s+(\S+)\s+(\S+)\s+(\d+)', c, re.I)
    if m:
        c = 'cratekey give %s %s %s' % (m.group(2), llave(m.group(1)), m.group(3))
    return c.replace('%player%', '%player_name%')


# ---------------------------------------------------------------- escritura
def escribir(ruta, datos):
    os.makedirs(os.path.dirname(ruta), exist_ok=True)
    with open(ruta, 'w', encoding='utf-8', newline='\n') as f:
        yaml.dump(datos, f, allow_unicode=True, sort_keys=False, width=100000, default_flow_style=False)


def color_rgb(h):
    return {'red': int(h[1:3], 16), 'green': int(h[3:5], 16), 'blue': int(h[5:7], 16), 'alpha': 255}


# Nombre Bukkit del mundo -> clave (los de LethalWorld no van en minecraft:)
MUNDOS = {'world_lethal_world_calamity': 'lethal_world:calamity', 'world_lethal_world_calamity2': 'lethal_world:calamity2'}

YAW = {'SOUTH': 0, 'SOUTH_SOUTH_WEST': 22.5, 'SOUTH_WEST': 45, 'WEST_SOUTH_WEST': 67.5, 'WEST': 90,
       'WEST_NORTH_WEST': 112.5, 'NORTH_WEST': 135, 'NORTH_NORTH_WEST': 157.5, 'NORTH': 180,
       'NORTH_NORTH_EAST': -157.5, 'NORTH_EAST': -135, 'EAST_NORTH_EAST': -112.5, 'EAST': -90,
       'EAST_SOUTH_EAST': -67.5, 'SOUTH_EAST': -45, 'SOUTH_SOUTH_EAST': -22.5}


def rango(texto):
    """'1-3, 5-7, 9' -> '1,2,3,5,6,7,9'"""
    out = []
    for t in str(texto).replace(' ', '').split(','):
        if '-' in t:
            a, b = t.split('-')
            out += range(int(a), int(b) + 1)
        elif t:
            out.append(int(t))
    return ','.join(map(str, out))


def boton(material, nombre, slots):
    return {'icon': {'Material': 'minecraft:' + material, 'Display_Name': nombre, 'Lore': [], 'Hide_Components': True},
            'slots': str(slots)}


def cristal(material, slots):
    return {'icon': {'Material': 'minecraft:' + material, 'Lore': [], 'Hide_Components': True, 'Hide_Tooltip': True},
            'slots': slots}


def escribir_menu(obj, d, cid):
    """La vista previa de la caja como su menu de PhoenixCrates: titulo, marco, flechas y salir."""
    nombre = ((d.get('menus') or {}).get('rewards') or '')
    ruta = os.path.join(SRC, 'menus', nombre + '.yml')
    if not nombre or not os.path.exists(ruta):
        return 'default'
    m = yaml.safe_load(open(ruta, encoding='utf-8'))
    titulo = m['settings']['title'].replace('%phoenixcrates_keys_', '%excellentcrates_keys_')
    it = m.get('items') or {}
    pag = m.get('pagination') or {}
    filas = int(m['settings'].get('rows', 5))
    usados = set()
    items = {}
    for k, v in it.items():
        if v.get('material', '').endswith('STAINED_GLASS_PANE'):
            items[k.replace('-', '_')] = cristal(v['material'].lower(), rango(v['slot']))
            usados |= set(rango(v['slot']).split(','))
    botones = {}
    for k, v in it.items():
        if 'CLOSE_INVENTORY' in str(v.get('actions')):
            botones['close'] = boton(v['material'].lower(), a_mm(v.get('display-name', 'Salir')), v['slot'])
            usados.add(str(v['slot']))
    prev, nxt = pag.get('previous-item') or {}, pag.get('next-item') or {}
    if prev:
        botones['previous_page'] = boton(prev['material'].lower(), a_mm(prev['display-name']), prev['slot'])
        botones['previous_page_inactive'] = cristal('black_stained_glass_pane', prev['slot'])
        usados.add(str(prev['slot']))
    if nxt:
        botones['next_page'] = boton(nxt['material'].lower(), a_mm(nxt['display-name']), nxt['slot'])
        botones['next_page_inactive'] = cristal('black_stained_glass_pane', nxt['slot'])
        usados.add(str(nxt['slot']))
    # El marco no pisa las casillas de los botones (las flechas tienen su propio cristal cuando no hay pagina)
    ocupados = {str(b['slots']) for b in botones.values()}
    for v in items.values():
        v['slots'] = ','.join(x for x in v['slots'].split(',') if x not in ocupados)
    premios = rango(pag.get('slots', '10-16, 19-25, 28-34'))
    # La casilla de "llaves disponibles" de PhoenixCrates (el titulo ya dice cuantas): cristal negro.
    libres = [str(i) for i in range(9) if str(i) not in usados and str(i) not in premios.split(',')]
    if libres:
        items['marco_llaves'] = cristal('black_stained_glass_pane', ','.join(libres))
    perfil = {'Settings': {
        'name': nombre,
        'reward': {
            'hide_unavailable': False,
            'name': '%reward_name%',
            'lore': {
                'components': {
                    'limit': {'condition': '%reward_has_limit_marker%',
                              'format': '<br><#8F8F8F>Quedan <white>%reward_limit_remaining%</white> de '
                                        '<white>%reward_limit_capacity%</white></#8F8F8F>', 'fallback': ''},
                    'description': {'condition': '%reward_description%', 'format': '{value}', 'fallback': ''},
                },
                'groups': {},
                'text': ['%description%', '%limit%'],
            },
            'slots': premios,
        },
        'inventory': {
            'title': a_mm(titulo),
            'type': 'minecraft:generic_9x%d' % filas,
            'items': items,
            'buttons': botones,
        },
    }}
    escribir(os.path.join(obj, 'preview', 'inventory', cid + '.yml'), perfil)
    return cid


# Partículas en reposo por caja: forma según lo rara que es la caja y color de su título.
# (modelo, color, tamaño, frames, intervalo, pausa)
EFECTOS = {
    'vote': ('sphere', '#2D7E39', 0.9, 8, 3, 30),
    'pets': ('pulsar', '#9A3DB5', 0.9, 38, 2, 30),
    'comunitaria': ('sphere', '#55FF55', 0.9, 8, 3, 30),
    'comunitaria_vip': ('pulsar', '#FF55FF', 0.9, 38, 2, 30),
    'rankup': ('helix', '#C04931', 1.0, 24, 1, 20),
    'spawners': ('helix', '#107599', 1.0, 24, 1, 20),
    'koth': ('helix', '#C42F2F', 1.0, 24, 1, 20),
    'dungeon': ('vortex', '#4A6BC9', 1.0, 34, 1, 20),
    'booster': ('vortex', '#5B55D6', 1.0, 34, 1, 20),
    'caos': ('vortex', '#C0318D', 1.2, 34, 1, 10),
    'cajaepica': ('spiral', '#A23BD6', 1.2, 50, 1, 10),
    'legendary': ('spiral', '#FFB347', 1.3, 50, 1, 5),
}
# Animación de apertura: la de PhoenixCrates (llave) en las básicas, ruleta en las medias y firmas propias.
ANIMACION = {
    'rankup': 'ederus_ruleta:default', 'spawners': 'ederus_ruleta:default', 'koth': 'ederus_ruleta:default',
    'dungeon': 'ederus_ruleta:default', 'cajaepica': 'ederus_revelacion:default',
    'legendary': 'ederus_eclipse:default', 'caos': 'ederus_caos:default',
}
PERFILES_ANIMACION = {
    'ederus_phoenix/llave': ('Phoenix: la llave entra', 'llave', '#404040'),
    'ederus_phoenix/fuego': ('Phoenix: fuego', 'fuego', '#404040'),
    'ederus_phoenix/grieta': ('Phoenix: grieta', 'grieta', '#404040'),
    'ederus_revelacion/default': ('Revelación por calidad', '', '#FFFFFF'),
    'ederus_ruleta/default': ('Ruleta orbital', '', '#FFFFFF'),
    'ederus_eclipse/default': ('Eclipse (Legendaria)', '', '#FFB347'),
    'ederus_caos/default': ('Caos', '', '#C0318D'),
}


def escribir_efecto(obj, cid, pid):
    if pid not in EFECTOS:
        return 'spiral:default'
    modelo, color, tam, frames, intervalo, pausa = EFECTOS[pid]
    escribir(os.path.join(obj, 'effects', modelo, cid + '.yml'), {'base-settings': {
        'name': 'Ederus: ' + cid,
        'particle': {'name': 'dust', 'data': {'color': color_rgb(color), 'size': tam}},
        'max_frames': frames, 'tick_interval': intervalo, 'pause_ticks': pausa,
    }})
    return modelo + ':' + cid


def escribir_animaciones(obj):
    for ruta, (nombre, variante, color) in PERFILES_ANIMACION.items():
        escribir(os.path.join(obj, 'animations', ruta + '.yml'),
                 {'settings': {'name': nombre, 'variant': variante, 'color': color}})


def main():
    obj = os.path.join(OUT, 'objects')
    qmap = json.load(open(QJSON, encoding='utf-8'))
    edm = yaml.safe_load(open(EDM_CFG, encoding='utf-8'))['cajas']
    locs = (yaml.safe_load(open(os.path.join(SRC, 'locations.yml'), encoding='utf-8')) or {}).get('locations', {})
    if LOCS:  # las del destino mandan; las cajas que no tiene se quedan con las del Survival
        locs.update((yaml.safe_load(open(LOCS, encoding='utf-8')) or {}).get('locations', {}))
    informe = {}

    escribir_animaciones(obj)

    # Calidades
    for i, (k, n, c) in enumerate(CALIDADES):
        escribir(os.path.join(obj, 'rarity', k + '.yml'),
                 {'base': {'name': '<%s>%s' % (c.lower(), n), 'weight': float(len(CALIDADES) - i), 'color': color_rgb(c)}})

    # Llaves
    for f in sorted(os.listdir(os.path.join(SRC, 'keys'))):
        k = yaml.safe_load(open(os.path.join(SRC, 'keys', f), encoding='utf-8'))
        alm = (k.get('internal-storage') or {}).get('items') or {}
        item = k.get('item') or {}
        snbt = snbt_item(item, alm)
        nombre = nombre_item(item, alm) or k['identifier']
        escribir(os.path.join(obj, 'key', llave(k['identifier']) + '.yml'), {
            'base': {'virtual': bool(k.get('virtual', True))},
            'display': {'name': nombre, 'lore': []},
            'item': {'item': adaptado(snbt), 'stackable': True, 'inherit_display_settings': False},
        })

    # Cajas
    for f in sorted(os.listdir(os.path.join(SRC, 'crates'))):
        if not f.endswith('.yml'):
            continue
        d = yaml.safe_load(open(os.path.join(SRC, 'crates', f), encoding='utf-8'))
        pid = d['identifier']
        if not d.get('enabled', True):
            informe[pid] = 'apagada en el Survival: no se migra'
            continue
        cid = cajaid(pid)
        alm = (d.get('internal-storage') or {}).get('items') or {}
        premios = [r for r in d['rewards'].values() if r.get('enabled') and r.get('weight', 0) > 0]
        apagados = [r['identifier'] for r in d['rewards'].values() if r not in premios]

        # calidad y peso interno de cada premio
        if pid == 'legendary':
            cfg = edm['legendary']
            pct = {q: v for q, v in cfg['calidades'].items()}
            cal = {i: (p['calidad'], p['peso']) for i, p in cfg['premios'].items()}
        else:
            base = qmap.get(pid, {})
            cal = {}
            for r in premios:
                rid = r['identifier']
                q = base.get(rid) or EXTRA.get(pid, {}).get(rid)
                if pid.startswith('comunitaria'):
                    q = calidad_comunitaria(rid)
                if q is None or q.startswith('?') or q == 'apagado':
                    raise SystemExit('%s: %s sin calidad (%s)' % (pid, rid, q))
                cal[rid] = (q, r['weight'])
            if pid in DISENO:
                pct = DISENO[pid]
            else:  # el Survival ya iba en orden: se conserva su probabilidad exacta
                tot = sum(r['weight'] for r in premios)
                pct = {}
                for r in premios:
                    q = cal[r['identifier']][0]
                    pct[q] = pct.get(q, 0) + r['weight'] / tot * 100
        faltan = [r['identifier'] for r in premios if r['identifier'] not in cal]
        if faltan:
            raise SystemExit('%s: sin calidad %s' % (pid, faltan))

        presentes = {cal[r['identifier']][0] for r in premios}
        tot_q = sum(v for q, v in pct.items() if q in presentes)
        peso_q = {}
        for r in premios:
            q, w = cal[r['identifier']]
            peso_q[q] = peso_q.get(q, 0) + w
        prob = {r['identifier']: pct[cal[r['identifier']][0]] / tot_q * cal[r['identifier']][1] / peso_q[cal[r['identifier']][0]]
                for r in premios}

        lista = {}
        filas = []
        for n, r in enumerate(premios):
            rid = r['identifier']
            q = cal[rid][0]
            p = prob[rid]
            di = r.get('display-item') or {'material': 'CHEST'}
            icono = snbt_item(di, alm, [linea_calidad(q, p)])
            items = {}
            wi = r.get('win-items') or {}
            for j, it in enumerate(wi.values() if isinstance(wi, dict) else wi):
                items['i%d' % j] = adaptado(snbt_item(it, alm))
            cmds = [comando(c) for c in (r.get('win-commands') or [])]
            ind = 1 if q in UNA_VEZ else (r.get('win-limits', -1) if r.get('win-limits', -1) > 0 else 0)
            glob = r.get('global-win-limit', -1) if r.get('global-win-limit', -1) > 0 else 0
            premio = {
                'base': {'weight': round(p * 10000, 6)},
                'preview': {'name': nombre_item(di, alm), 'lore': [], 'icon': adaptado(icono), 'use_item_data': True},
                'Rarity': {'enabled': True, 'rarity_id': q},
                'items': {'item_list': items},
                'commands': {'enabled': bool(cmds), 'iterations': 1, 'give_mode': 'NORMAL',
                             'bundles': {'b1': {'commands': cmds, 'weight': 100.0}} if cmds else {}},
                'limit': {'enabled': bool(ind or glob),
                          'global': {'Enabled': bool(glob), 'Amount': glob or -1},
                          'individual': {'Enabled': bool(ind), 'Amount': ind or -1}},
                'broadcast': {'enabled': bool(r.get('broadcast'))},
            }
            escribir(os.path.join(obj, 'reward', cid, rid.lower() + '.yml'), premio)
            lista['r%02d' % n] = {'reward_id': rid.lower()}
            filas.append((q, p, nombre_item(di, alm), rid))

        item = d.get('item') or {'material': 'CHEST'}
        holo = [l.replace('%excellentcrates_keys_', '%excellentcrates_key_balance_') for l in
                ((d.get('hologram') or {}).get('lines') or [])]
        for viejo, nuevo in LLAVES.items():
            holo = [l.replace('key_balance_' + viejo + '%', 'key_balance_' + nuevo + '%') for l in holo]
        pos = {}
        for s in locs.get(pid, []):
            w, x, y, z, cara = s.split(';')
            # La 7 compara la posicion con la del bloque (pitch y yaw 0): con otro yaw no reconoce la caja.
            pos.setdefault(MUNDOS.get(w, 'minecraft:' + w.lower()), []).append('%s,%s,%s,0.0,0.0' % (
                float(x), float(y), float(z)))
        escribir(os.path.join(obj, 'crate', cid + '.yml'), {
            'base': {'permission_required': False},
            'display': {'name': a_mm(d.get('display-name') or pid), 'lore': [a_mm(l) for l in item.get('lore') or []]},
            'item': {'item': adaptado(snbt_item(item, alm)), 'item_stackable': True, 'use_display': True},
            'rewards': {'reward_list': lista, 'required_rewards': 1},
            'key_requirements': {'enabled': bool(d.get('key-required', True)),
                                 'keys': {llave(k): {'amount': 1} for k in d.get('linked-keys-ids') or []}},
            'animation': {'enabled': True, 'profile': ANIMACION.get(pid, 'ederus_phoenix:llave')},
            'preview': {'enabled': True, 'config': 'inventory:' + escribir_menu(obj, d, cid)},
            'hologram': {'enabled': bool(holo), 'text': [a_mm(l) for l in holo],
                         'offset': {'x': 0.0, 'y': 1.0, 'z': 0.0}},
            'blocks': {'positions': pos},
            'effect': {'enabled': True, 'profile': escribir_efecto(obj, cid, pid)},
            'opening': {'enabled': True, 'opening_commands': []},
            'selective_rewards': {'enabled': False},
        })
        informe[pid] = {'id': cid, 'premios': filas, 'apagados': apagados,
                        'calidades': {q: sum(p for qq, p, _, _ in filas if qq == q) for q in ORDEN if q in presentes}}

    json.dump(informe, open(os.path.join(OUT, 'informe.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    for c, v in informe.items():
        if isinstance(v, str):
            print(c, v)
            continue
        print('%-16s %2d premios  %s%s' % (c, len(v['premios']), '  '.join(
            '%s %s' % (CAL[q][0], porcentaje(p)) for q, p in v['calidades'].items()),
            ('  (apagados: %d)' % len(v['apagados'])) if v['apagados'] else ''))


main()
