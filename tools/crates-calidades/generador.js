// Genera un crates/<caja>.yml de PhoenixCrates a partir de su copia original y de calidades.json.
// Se ejecuta en el navegador (pagina del panel) o en Node: generar(textoOriginal, spec, idCaja).
// - weight final = P(calidad) * P(premio dentro de la calidad) * 10000
// - premios reordenados por calidad (de mas rara a mas comun) y, dentro, de menor a mayor probabilidad
// - lineas de calidad añadidas al final del lore de la imagen de cada premio
// Trabaja SIEMPRE sobre la copia original (.bak), nunca sobre su propia salida: los premios se
// emparejan por texto ("match") o por su posicion en esa copia ("n").

function generar(original, spec, idCaja) {
  const eol = original.includes('\r\n') ? '\r\n' : '\n';
  const L = original.split(/\r?\n/);
  const caja = spec.cajas[idCaja];
  const cal = spec.calidades;
  const pesoCal = k => (caja.calidades && caja.calidades[k] !== undefined) ? caja.calidades[k] : cal[k].peso;

  const iRew = L.findIndex(l => /^rewards:\s*$/.test(l));
  let fin = iRew + 1;
  while (fin < L.length && (L[fin] === '' || /^\s/.test(L[fin]))) fin++;
  const bloques = [];
  for (let i = iRew + 1; i < fin; i++) {
    const m = L[i].match(/^  '?(\d+)'?:\s*$/);
    if (m) bloques.push({ n: +m[1], lineas: [] });
    if (bloques.length) bloques[bloques.length - 1].lineas.push(L[i]);
  }

  // Emparejar cada premio con su entrada de la tabla
  const usados = new Set();
  for (const b of bloques) {
    const cand = caja.premios.filter(p => p.n !== undefined ? p.n === b.n : b.lineas.some(l => {
      const t = l.trim().replace(/^- /, '');
      return p.match.startsWith('material: ') ? t === p.match : t.includes(p.match);
    }));
    if (cand.length !== 1) throw new Error(idCaja + ' premio ' + b.n + ': ' + cand.length + ' coincidencias');
    if (usados.has(cand[0])) throw new Error(idCaja + ': entrada usada dos veces');
    usados.add(cand[0]);
    b.p = cand[0];
  }
  const sobran = caja.premios.filter(p => !usados.has(p));
  if (sobran.length) throw new Error(idCaja + ': sin premio en la caja: ' + sobran.map(p => p.n ?? p.match).join(', '));

  // Probabilidades en dos pasos (calidad null = premio apagado, peso 0)
  const activos = bloques.filter(b => b.p.calidad);
  const presentes = [...new Set(activos.map(b => b.p.calidad))];
  presentes.forEach(k => { if (!cal[k]) throw new Error(idCaja + ': calidad desconocida ' + k); });
  const totCal = presentes.reduce((a, k) => a + pesoCal(k), 0);
  const totDentro = {};
  activos.forEach(b => totDentro[b.p.calidad] = (totDentro[b.p.calidad] || 0) + b.p.peso);
  bloques.forEach(b => {
    if (!b.p.calidad) { b.pCal = b.pDentro = b.pTotal = 0; return; }
    b.pCal = pesoCal(b.p.calidad) / totCal;
    b.pDentro = b.p.peso / totDentro[b.p.calidad];
    b.pTotal = b.pCal * b.pDentro;
  });

  const pct = v => {
    const x = v * 100;
    const d = x >= 10 ? 1 : x >= 0.1 ? 2 : 3;
    return x.toFixed(d).replace(/\.?0+$/, '').replace('.', ',') + ' %';
  };

  // Lineas de lore en SNBT (para objetos serializados) y en & (para imagenes simples)
  const comp = (texto, color) => '{color:"' + color + '",italic:0b,text:"' + texto.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"}';
  const linea = partes => '{extra:[' + partes.join(',') + '],text:""}';
  const amp = hex => '&x' + hex.slice(1).split('').map(c => '&' + c).join('');
  const loreDe = b => {
    const c = cal[b.p.calidad];
    return [
      '',
      [['◆ ', c.color], [c.nombre, c.color]],
      [['Probabilidad de la calidad: ', 'gray'], [pct(b.pCal), 'white']],
      [['Dentro de la calidad: ', 'gray'], [pct(b.pDentro), 'white']],
      [['Probabilidad total: ', 'gray'], [pct(b.pTotal), c.color]],
    ];
  };
  const snbt = b => loreDe(b).map(p => p === '' ? '{text:""}' : linea(p.map(([t, col]) => comp(t, col))));
  const ampLore = b => loreDe(b).map(p => p === '' ? "''" :
    "'" + p.map(([t, col]) => (col === 'gray' ? '&7' : col === 'white' ? '&f' : amp(col)) + t).join('').replace(/'/g, "''") + "'");

  // Objeto serializado (cabecera con sangria 4, campos 6, componentes 8): cambia su lore en el sitio.
  // Quita las entradas "Rareza:" que dejo la migracion y las vacias del final antes de añadir las nuevas.
  const ponerLore = (arr, s, entradas, donde) => {
    let e = s + 1;
    while (e < arr.length && /^      /.test(arr[e])) e++;
    const iComp = arr.slice(s, e).findIndex(l => /^      components:\s*$/.test(l));
    const iLore = arr.slice(s, e).findIndex(l => /^        minecraft:lore: '/.test(l));
    if (iLore >= 0) {
      const a = s + iLore;
      let k = a;
      while (!/(^|[^'])('')*'$/.test(arr[k].trimEnd()) || (k === a && /minecraft:lore: '$/.test(arr[k].trimEnd()))) k++;
      const crudo = [arr[a].replace(/^\s*minecraft:lore: '/, ''), ...arr.slice(a + 1, k + 1).map(l => l.trim())].join(' ');
      const snbtLore = crudo.trimEnd().slice(0, -1).replace(/''/g, "'");
      if (!snbtLore.startsWith('[') || !snbtLore.endsWith(']')) throw new Error('Lore raro en ' + donde);
      const items = []; let prof = 0, enTexto = false, ini = 1;
      for (let i = 1; i < snbtLore.length - 1; i++) {
        const ch = snbtLore[i];
        if (enTexto) { if (ch === '\\') i++; else if (ch === '"') enTexto = false; continue; }
        if (ch === '"') enTexto = true;
        else if (ch === '{' || ch === '[') prof++;
        else if (ch === '}' || ch === ']') prof--;
        else if (ch === ',' && prof === 0) { items.push(snbtLore.slice(ini, i)); ini = i + 1; }
      }
      if (snbtLore.length > 2) items.push(snbtLore.slice(ini, snbtLore.length - 1));
      // Fuera la rareza vieja y el bloque "Encantamientos" del lore guardado: el modulo tooltip de EDM
      // ya dibuja los encantamientos encima y se veian dos veces
      const texto = x => [...x.matchAll(/text:"((?:[^"\\]|\\.)*)"/g)].map(m => m[1]).join('').trim();
      const limpios = [];
      let enEnc = false;
      for (const x of items) {
        const t = texto(x);
        if (/^Encantamientos:?$/.test(t)) { enEnc = true; continue; }
        if (enEnc && /^[\p{L} ']+:\s*\d+$/u.test(t)) continue;
        enEnc = false;
        if (/Rareza:/.test(x)) continue;
        limpios.push(x);
      }
      while (limpios.length && texto(limpios[limpios.length - 1]) === '') limpios.pop();
      const nuevo = '[' + [...limpios, ...entradas].join(',') + ']';
      arr.splice(a, k - a + 1, "        minecraft:lore: '" + nuevo.replace(/'/g, "''") + "'");
    } else {
      const nueva = "        minecraft:lore: '[" + entradas.join(',') + "]'";
      if (iComp >= 0) arr.splice(s + iComp + 1, 0, nueva);
      else arr.splice(e, 0, '      components:', nueva);
    }
    // Ocultar el bloque vanilla de atributos ("When in Main Hand"), que muestra un daño que no es el real
    let f = s + 1;
    while (f < arr.length && /^      /.test(arr[f])) f++;
    const iTd = arr.slice(s, f).findIndex(l => /^        minecraft:tooltip_display: '/.test(l));
    if (iTd >= 0) {
      const l = arr[s + iTd];
      if (!/attribute_modifiers/.test(l)) {
        arr[s + iTd] = /hidden_components:\[/.test(l)
          ? l.replace('hidden_components:[', 'hidden_components:["minecraft:attribute_modifiers",')
          : l.replace(/\{/, '{hidden_components:["minecraft:attribute_modifiers"],');
      }
    } else {
      const iC = arr.slice(s, f).findIndex(l => /^      components:\s*$/.test(l));
      arr.splice(s + iC + 1, 0, `        minecraft:tooltip_display: '{hidden_components:["minecraft:attribute_modifiers"]}'`);
    }
  };

  // Imagen del premio: objeto guardado (custom:<clave>), serializada en el propio premio, o simple
  const loreGuardado = {};
  for (const b of bloques) {
    if (!b.p.calidad) continue;
    const iD = b.lineas.findIndex(l => /^    display-item:/.test(l));
    let j = iD + 1;
    while (j < b.lineas.length && /^      /.test(b.lineas[j])) j++;
    const zona = b.lineas.slice(iD, j);
    const mat = (zona.find(l => /^      material:/.test(l)) || '').replace(/^\s+material:\s*/, '').trim();
    if (zona.some(l => /^      ==: org\.bukkit\.inventory\.ItemStack/.test(l))) {
      ponerLore(b.lineas, iD, snbt(b), idCaja + ' premio ' + b.n);
    } else if (mat.startsWith('custom:')) {
      const clave = mat.slice(7);
      // dos premios pueden compartir imagen solo si su lore de calidad sale identico
      const nuevo = snbt(b);
      if (loreGuardado[clave] && loreGuardado[clave].join() !== nuevo.join()) throw new Error(idCaja + ': imagen compartida con lore distinto ' + clave);
      loreGuardado[clave] = nuevo;
    } else {
      const iLore = zona.findIndex(l => /^      lore:/.test(l));
      const nuevas = ampLore(b).map(x => '      - ' + x);
      if (iLore < 0) {
        zona.push('      lore:', ...nuevas);
      } else {
        let k = iLore + 1;
        while (k < zona.length && /^      - /.test(zona[k])) k++;
        const viejas = zona.slice(iLore + 1, k).filter(l => !/Rareza|Objeto personalizado/.test(l));
        while (viejas.length && /^      - ''$/.test(viejas[viejas.length - 1])) viejas.pop();
        zona.splice(iLore, k - iLore, '      lore:', ...viejas, ...nuevas);
      }
      b.lineas.splice(iD, j - iD, ...zona);
    }
  }

  const out = L.slice(0, iRew);
  const iItems = out.findIndex(l => /^  items:\s*$/.test(l));
  for (const [clave, entradas] of Object.entries(loreGuardado)) {
    const re = new RegExp("^    '?" + clave.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + "'?:\\s*$");
    const s = out.findIndex((l, i) => i > iItems && re.test(l));
    if (s < 0) throw new Error(idCaja + ': no esta en internal-storage ' + clave);
    ponerLore(out, s, entradas, idCaja + ' ' + clave);
  }

  // Nombre de la caja (el mismo que tenia en ExcellentCrates); tambien es el titulo de su menu
  if (caja.nombre) {
    const iN = out.findIndex(l => /^display-name:/.test(l));
    out[iN] = "display-name: '" + legado(caja.nombre).replace(/'/g, "''") + "'";
  }

  // Un solo aviso al ganar: mi give ya manda "Recibiste ..." de MMOItems, asi que PhoenixCrates calla
  for (const b of bloques) {
    if (!b.lineas.some(l => /^    - mi give /.test(l))) continue;
    const iw = b.lineas.findIndex(l => /^    allow-win-message:/.test(l));
    if (iw >= 0) b.lineas[iw] = '    allow-win-message: false';
  }

  // Menu de vista previa propio de la caja
  {
    const iMenus = out.findIndex(l => /^menus:\s*$/.test(l));
    const iR = out.findIndex((l, i) => i > iMenus && /^  rewards:/.test(l));
    out[iR] = '  rewards: ' + (caja.menu || idCaja + '_preview');
  }

  // Reordenar y renumerar (los apagados al final)
  const ord = spec.orden;
  const pos = b => b.p.calidad ? ord.indexOf(b.p.calidad) : ord.length;
  bloques.sort((a, b) => pos(a) - pos(b) || a.pTotal - b.pTotal || a.n - b.n);
  bloques.forEach((b, n) => {
    b.lineas[0] = "  '" + n + "':";
    const iw = b.lineas.findIndex(l => /^    weight:/.test(l));
    b.lineas[iw] = '    weight: ' + +(b.pTotal * 10000).toFixed(6);
  });
  out.push(L[iRew], ...bloques.flatMap(b => b.lineas), ...L.slice(fin));

  const informe = bloques.map(b => [b.p.calidad || 'apagado', pct(b.pCal), pct(b.pDentro), pct(b.pTotal), b.p._ || b.p.match || ('#' + b.n)]);
  return { texto: out.join(eol), informe };
}


// Pasa un texto de ExcellentCrates (MiniMessage con <bold>, <#hex>, <c:#hex>, <gradient:#a:#b> y codigos &)
// al formato & que lee PhoenixCrates, con el hex como &x&R&R&G&G&B&B letra a letra
function legado(mm) {
  const hex = h => '&x' + h.replace('#', '').toUpperCase().split('').map(c => '&' + c).join('');
  const mezcla = (a, b, t) => {
    const p = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
    const A = p(a), B = p(b);
    return '#' + A.map((v, i) => Math.round(v + (B[i] - v) * t).toString(16).padStart(2, '0')).join('');
  };
  // 1) trocear en letras con su estado (color, negrita); los & de color/estilo cambian el estado
  const letras = [];
  let color = null, negrita = false;
  const pila = [];
  let grad = null;
  const re = /<(\/?)([^>]+)>|&([0-9a-fk-or])|([\s\S])/gi;
  const nombres = { black: '0', dark_blue: '1', dark_green: '2', dark_aqua: '3', dark_red: '4', dark_purple: '5', gold: '6', gray: '7', dark_gray: '8', blue: '9', green: 'a', aqua: 'b', red: 'c', light_purple: 'd', yellow: 'e', white: 'f' };
  let m;
  while ((m = re.exec(mm))) {
    if (m[2] !== undefined) {
      const cierre = m[1] === '/', tag = m[2].toLowerCase();
      if (tag === 'bold' || tag === 'b') { negrita = !cierre; continue; }
      if (tag.startsWith('gradient')) {
        if (cierre) { grad.fin = letras.length; grad = null; }
        else { const c = m[2].split(':').filter(x => x.startsWith('#')); grad = { ini: letras.length, de: c[0], a: c[c.length - 1] }; letras.grads = letras.grads || []; letras.grads.push(grad); }
        continue;
      }
      let c = null;
      if (/^#[0-9a-f]{6}$/i.test(tag)) c = tag;
      else if (/^(c|color):#[0-9a-f]{6}$/i.test(tag)) c = tag.split(':')[1];
      else if (nombres[tag]) c = '&' + nombres[tag];
      if (cierre) { color = pila.pop() || null; continue; }
      if (c) { pila.push(color); color = c; }
      continue;
    }
    if (m[3] !== undefined) {
      const k = m[3].toLowerCase();
      if (k === 'l') negrita = true;
      else if (k === 'r') { negrita = false; color = null; }
      else if (/[0-9a-f]/.test(k)) { color = '&' + k; negrita = false; }
      continue;
    }
    letras.push({ ch: m[4], color, negrita });
  }
  (letras.grads || []).forEach(g => {
    const n = (g.fin ?? letras.length) - g.ini;
    for (let i = 0; i < n; i++) letras[g.ini + i].color = mezcla(g.de, g.a, n > 1 ? i / (n - 1) : 0);
  });
  // 2) escribir: color y negrita delante de cada letra que cambia
  let out = '', prev = '';
  for (const l of letras) {
    const pref = (l.color ? (l.color.startsWith('#') ? hex(l.color) : l.color) : '&f') + (l.negrita ? '&l' : '');
    if (pref !== prev) { out += pref; prev = pref; }
    out += l.ch;
  }
  return out;
}

// Titulo del menu: "CRATE X" en mayusculas, negrita y degradado (legible sobre el gris del cofre, #C6C6C6)
// y detras "✦ N llaves" con las llaves del jugador. Si no cabe en los 160 px de la barra, solo "✦ N".
function tituloMenu({ texto, de, a, llave }) {
  const hex = h => '&x' + h.replace('#', '').toUpperCase().split('').map(c => '&' + c).join('');
  const p = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
  const A = p(de), B = p(a), letras = [...texto];
  const nombre = letras.map((c, i) => {
    if (c === ' ') return ' ';
    const f = letras.length > 1 ? i / (letras.length - 1) : 0;
    return hex('#' + A.map((v, k) => Math.round(v + (B[k] - v) * f).toString(16).padStart(2, '0')).join('')) + '&l' + c;
  }).join('');
  // ancho en pixeles con la fuente de Minecraft (avance = ancho + 1; negrita +1)
  const ancho = (s, negrita) => [...s].reduce((n, c) => n + ({ ' ': 3, i: 1, l: 2, I: 3, t: 3, '!': 1, '.': 1, ':': 1, '|': 1, f: 4, k: 4, '✦': 7 }[c] ?? 5) + 1 + (negrita ? 1 : 0), 0);
  const largo = (sufijo) => ancho(texto, true) + ancho('   ' + sufijo, false);
  if (!llave) return nombre;
  const marca = hex(a) + '✦ &8%phoenixcrates_keys_' + llave + '%';
  return nombre + '   ' + marca + (largo('✦ 00 llaves') <= 160 ? ' llaves' : '');
}

// Menu de vista previa con la misma forma que tenia ExcellentCrates: 5 filas, titulo = nombre de la caja,
// marco de cristal negro con esquinas grises, llaves arriba en el centro, Volver (18), Siguiente (26), Salir (40)
function generarMenu(base, titulo) {
  const eol = base.includes('\r\n') ? '\r\n' : '\n';
  let t = base.split(/\r?\n/);
  const seccion = re => { const i = t.findIndex(l => re.test(l)); let e = i + 1; while (e < t.length && (/^\s/.test(t[e]) || t[e] === '')) e++; return [i, e]; };
  const item = (clave, slot, mat, nombre, lore, acciones, sinTooltip) => [
    '  ' + clave + ':', "    slot: '" + slot + "'", '    material: ' + mat, '    amount: 1', '    custom-model-data: 0',
    "    item-model: ''", '    glow: false', '    hide-attributes: true', "    display-name: '" + nombre + "'",
    lore.length ? '    lore:' : '    lore: []', ...lore.map(x => "    - '" + x + "'"),
    acciones.length ? '    actions:' : '    actions: []', ...acciones.map(x => "    - '" + x + "'"),
    ...(sinTooltip ? ['    hide-tooltip: true'] : [])];

  // items: marco y boton de salir
  let [i, e] = seccion(/^items:/);
  t.splice(i, e - i, 'items:',
    ...item('marco-negro', '1-3, 5-7, 9, 17, 18, 26, 27, 35, 37-39, 41-43', 'BLACK_STAINED_GLASS_PANE', '', [], [], true),
    ...item('marco-gris', '0, 8, 36, 44', 'GRAY_STAINED_GLASS_PANE', '', [], [], true),
    ...item('close-menu', 40, 'SPRUCE_DOOR', '&c&lSalir', [], ['[CLOSE_INVENTORY]'], false));

  // titulo y filas
  t[t.findIndex(l => /^  title:/.test(l))] = "  title: '" + (typeof titulo === 'string' ? legado(titulo) : tituloMenu(titulo)).replace(/'/g, "''") + "'";
  t[t.findIndex(l => /^  rows:/.test(l))] = '  rows: 5';

  // flechas de pagina
  for (const [clave, slot, nombre] of [['previous-item', 18, '&e&l« Volver'], ['next-item', 26, '&e&lSiguiente »']]) {
    i = t.findIndex(l => new RegExp('^  ' + clave + ':').test(l));
    e = i + 1; while (e < t.length && (/^    /.test(t[e]) || t[e] === '')) e++;
    t.splice(i, e - i, ...item(clave, slot, 'ARROW', nombre, [], [], false));
  }

  // llaves disponibles donde ExcellentCrates tenia "Hitos"
  [i, e] = seccion(/^available-keys:/);
  t[t.findIndex((l, k) => k > i && k < e && /^  slot:/.test(l))] = "  slot: '4'";

  // premios: solo el lore del objeto (la calidad y las probabilidades ya van dentro); fuera el texto de ejemplo
  t = t.filter(l => !/Esta descripci/.test(l));
  const iRand = t.findIndex(l => /^  random-mode:/.test(l));
  const iLore = t.findIndex((l, k) => k > iRand && /^    lore:/.test(l));
  let k = iLore + 1; while (/^    - /.test(t[k])) k++;
  t.splice(iLore, k - iLore, '    lore:', "    - '%reward_lore%'");
  return t.join(eol);
}

if (typeof module !== 'undefined') module.exports = { generar, generarMenu, legado, tituloMenu };
