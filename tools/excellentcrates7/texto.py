"""Textos con codigos de color (& / § legado, &x hex, <#hex>, <bold>...) a componentes.

Salidas: SNBT de componente de texto (para nombres y lore de items) y MiniMessage
(para nombres de cajas, llaves, calidades y hologramas de ExcellentCrates 7).
"""
import re

COLORES = {
    '0': '#000000', '1': '#0000AA', '2': '#00AA00', '3': '#00AAAA', '4': '#AA0000', '5': '#AA00AA',
    '6': '#FFAA00', '7': '#AAAAAA', '8': '#555555', '9': '#5555FF', 'a': '#55FF55', 'b': '#55FFFF',
    'c': '#FF5555', 'd': '#FF55FF', 'e': '#FFFF55', 'f': '#FFFFFF',
}
ESTILOS = {'l': 'bold', 'o': 'italic', 'n': 'underlined', 'm': 'strikethrough', 'k': 'obfuscated'}
MM_ESTILO = {'bold': 'bold', 'b': 'bold', 'italic': 'italic', 'i': 'italic', 'underlined': 'underlined',
             'u': 'underlined', 'strikethrough': 'strikethrough', 'st': 'strikethrough', 'obfuscated': 'obfuscated'}

TOKEN = re.compile(
    r'(?P<hexl>[&§]x(?:[&§][0-9a-fA-F]){6})'
    r'|(?P<leg>[&§][0-9a-fk-orA-FK-OR])'
    r'|<(?P<cierre>/)?(?P<tag>#[0-9a-fA-F]{6}|bold|b|italic|i|underlined|u|strikethrough|st|obfuscated|reset)>'
)


def segmentos(texto):
    """Lista de (texto, estilo) con estilo = dict(color, bold, italic, ...)."""
    out = []
    est = {}
    pos = 0
    for m in TOKEN.finditer(texto):
        if m.start() > pos:
            out.append((texto[pos:m.start()], dict(est)))
        pos = m.end()
        if m.group('hexl'):
            est = {'color': '#' + ''.join(m.group('hexl')[3::2]).upper()}
        elif m.group('leg'):
            c = m.group('leg')[1].lower()
            if c in COLORES:
                est = {'color': COLORES[c]}
            elif c == 'r':
                est = {}
            else:
                est[ESTILOS[c]] = True
        else:
            tag = m.group('tag')
            if tag.startswith('#'):
                # <#hex> en estos textos convive con &l: cambia el color y conserva los estilos
                est = {k: v for k, v in est.items() if k != 'color'}
                est['color'] = tag.upper()
            elif tag == 'reset':
                est = {}
            elif m.group('cierre'):
                est.pop(MM_ESTILO[tag], None)
            else:
                est[MM_ESTILO[tag]] = True
    if pos < len(texto):
        out.append((texto[pos:], dict(est)))
    return [(t, e) for t, e in out if t]


def _esc_snbt(s):
    return s.replace('\\', '\\\\').replace('"', '\\"')


def snbt_parte(texto, est, italic_por_defecto=False):
    campos = ['text:"%s"' % _esc_snbt(texto)]
    if est.get('color'):
        campos.append('color:"%s"' % est['color'])
    for k in ('bold', 'underlined', 'strikethrough', 'obfuscated'):
        if est.get(k):
            campos.append('%s:1b' % k)
    campos.append('italic:%s' % ('1b' if est.get('italic', italic_por_defecto) else '0b'))
    return '{' + ','.join(campos) + '}'


def a_snbt(texto):
    """Componente de texto en SNBT, sin cursiva (nombres y lore de items)."""
    segs = segmentos(texto)
    if not segs:
        return '{text:"",italic:0b}'
    return '{text:"",italic:0b,extra:[' + ','.join(snbt_parte(t, e) for t, e in segs) + ']}'


def a_mm(texto):
    """MiniMessage equivalente. Cada tramo lleva sus etiquetas cerradas."""
    out = []
    for t, e in segmentos(texto):
        t = t.replace('<', '\\<')
        abre, cierra = '', ''
        if e.get('color'):
            abre += '<%s>' % e['color'].lower()
            cierra = '</%s>' % e['color'].lower() + cierra
        for k in ('bold', 'italic', 'underlined', 'strikethrough', 'obfuscated'):
            if e.get(k):
                abre += '<%s>' % k
                cierra = '</%s>' % k + cierra
        out.append(abre + t + cierra)
    return ''.join(out)


def plano(texto):
    return ''.join(t for t, _ in segmentos(texto))
