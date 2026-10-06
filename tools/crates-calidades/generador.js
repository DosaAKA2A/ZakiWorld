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
      const limpios = items.filter(x => !/Rareza:/.test(x));
      while (limpios.length && /^\{(italic:0b,)?text:""(,italic:0b)?\}$/.test(limpios[limpios.length - 1].replace(/color:"\w+",?/, ''))) limpios.pop();
      const nuevo = '[' + [...limpios, ...entradas].join(',') + ']';
      arr.splice(a, k - a + 1, "        minecraft:lore: '" + nuevo.replace(/'/g, "''") + "'");
    } else {
      const nueva = "        minecraft:lore: '[" + entradas.join(',') + "]'";
      if (iComp >= 0) arr.splice(s + iComp + 1, 0, nueva);
      else arr.splice(e, 0, '      components:', nueva);
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

// Menu de vista previa con el estilo de Ederus, a partir de default_rewards_preview.yml
function generarMenu(base, seccion) {
  const eol = base.includes('\r\n') ? '\r\n' : '\n';
  let t = base.split(/\r?\n/);
  const fija = (re, nueva) => { const i = t.findIndex(l => re.test(l)); if (i < 0) throw new Error('Falta ' + re); t[i] = nueva; return i; };
  const bloque = (ini, sangria) => { let e = ini + 1; while (e < t.length && (t[e].startsWith(sangria) || t[e] === '')) e++; return e; };
  const titulo = "  title: '&x&0&0&8&3&F&D&lEDERUS &8| &x&D&7&F&3&F&F" + seccion + "'";
  fija(/^  title:/, titulo);

  // Marco de cristal negro completo (deja libres las flechas 18/26 y el boton de cerrar 49)
  const iFill = t.findIndex(l => /^  down-filler:/.test(l));
  t[t.findIndex((l, i) => i > iFill && /^    slot:/.test(l))] = "    slot: 0-9, 17, 27, 35, 36, 44, 45-48, 50-53";

  // Cerrar: puerta de abeto en vez de barrera
  const iClose = t.findIndex(l => /^  close-menu:/.test(l));
  const eClose = bloque(iClose, '    ');
  const cerrar = [
    '  close-menu:', "    slot: '49'", '    material: SPRUCE_DOOR', '    amount: 1', '    custom-model-data: 0',
    "    item-model: ''", '    glow: false', '    hide-attributes: true', "    display-name: '&x&D&7&F&3&F&FCerrar'",
    '    lore:', "    - '&7Vuelves al juego.'", "    - ''", "    - '&eClic para cerrar'", '    actions:', "    - '[CLOSE_INVENTORY]'"];
  t.splice(iClose, eClose - iClose, ...cerrar);

  // Flechas
  const flecha = (clave, nombre, simbolo) => {
    const i = t.findIndex(l => new RegExp('^  ' + clave + ':').test(l));
    const e = bloque(i, '    ');
    const b = t.slice(i, e);
    const iN = b.findIndex(l => /^    display-name:/.test(l));
    b[iN] = "    display-name: '&x&D&7&F&3&F&F" + nombre + "'";
    const iL = b.findIndex(l => /^    lore:/.test(l));
    let k = iL + 1; while (k < b.length && /^    - /.test(b[k])) k++;
    b.splice(iL, k - iL, '    lore:', "    - '&7Página %page%'", "    - ''", "    - '&eClic para " + simbolo + "'");
    t.splice(i, e - i, ...b);
  };
  flecha('previous-item', 'Página anterior', 'retroceder');
  flecha('next-item', 'Página siguiente', 'avanzar');

  // Premios: solo el lore del objeto (las lineas de calidad ya van dentro); fuera el texto de ejemplo
  t = t.filter(l => !/Esta descripci/.test(l));
  const iRand = t.findIndex(l => /^  random-mode:/.test(l));
  const iLore = t.findIndex((l, i) => i > iRand && /^    lore:/.test(l));
  let k = iLore + 1; while (/^    - /.test(t[k])) k++;
  t.splice(iLore, k - iLore, '    lore:', "    - '%reward_lore%'");
  return t.join(eol);
}

if (typeof module !== 'undefined') module.exports = { generar, generarMenu };
