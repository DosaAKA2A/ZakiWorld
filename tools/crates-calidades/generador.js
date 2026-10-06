// Genera un crates/<caja>.yml de PhoenixCrates a partir de su copia original y de calidades.json.
// Se ejecuta en el navegador (pagina del panel) o en Node: generar(textoOriginal, spec, idCaja).
// - weight final = P(calidad) * P(premio dentro de la calidad) * 10000
// - premios reordenados por calidad (de mas rara a mas comun) y, dentro, de menor a mayor probabilidad
// - lineas de calidad añadidas al final del lore de la imagen de cada premio
// Trabaja SIEMPRE sobre la copia original (.bak), nunca sobre su propia salida.

function generar(original, spec, idCaja) {
  const eol = original.includes('\r\n') ? '\r\n' : '\n';
  const L = original.split(/\r?\n/);
  const caja = spec.cajas[idCaja];
  const cal = spec.calidades;

  const iRew = L.findIndex(l => /^rewards:\s*$/.test(l));
  let fin = iRew + 1;
  while (fin < L.length && (L[fin] === '' || /^\s/.test(L[fin]))) fin++;
  const bloques = [];
  for (let i = iRew + 1; i < fin; i++) {
    if (/^  '?\d+'?:\s*$/.test(L[i])) bloques.push({ ini: i, lineas: [] });
    if (bloques.length) bloques[bloques.length - 1].lineas.push(L[i]);
  }

  // Emparejar cada premio con su entrada de la tabla
  const usados = new Set();
  for (const b of bloques) {
    const cand = caja.premios.filter(p => b.lineas.some(l => {
      const t = l.trim().replace(/^- /, '');
      return p.match.startsWith('material: ') ? t === p.match : t.includes(p.match);
    }));
    if (cand.length !== 1) throw new Error('Premio ' + b.lineas[0].trim() + ': ' + cand.length + ' coincidencias');
    if (usados.has(cand[0].match)) throw new Error('Entrada usada dos veces: ' + cand[0].match);
    usados.add(cand[0].match);
    b.p = cand[0];
  }
  const sobran = caja.premios.filter(p => !usados.has(p.match));
  if (sobran.length) throw new Error('Sin premio en la caja: ' + sobran.map(p => p.match).join(', '));

  // Probabilidades en dos pasos
  const presentes = [...new Set(bloques.map(b => b.p.calidad))];
  const totCal = presentes.reduce((a, k) => a + cal[k].peso, 0);
  const totDentro = {};
  bloques.forEach(b => totDentro[b.p.calidad] = (totDentro[b.p.calidad] || 0) + b.p.peso);
  bloques.forEach(b => {
    b.pCal = cal[b.p.calidad].peso / totCal;
    b.pDentro = b.p.peso / totDentro[b.p.calidad];
    b.pTotal = b.pCal * b.pDentro;
  });

  const pct = v => {
    const x = v * 100;
    const d = x >= 10 ? 1 : x >= 1 ? 2 : x >= 0.1 ? 2 : 3;
    return x.toFixed(d).replace(/\.?0+$/, '').replace('.', ',') + ' %';
  };

  // Lineas de lore en SNBT (para objetos guardados) y en & (para imagenes simples)
  const comp = (texto, color) => '{color:"' + color + '",italic:0b,text:"' + texto.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"}';
  const linea = partes => '{extra:[' + partes.join(',') + '],text:""}';
  const amp = hex => '&x' + hex.slice(1).split('').map(c => '&' + c).join('');
  const loreDe = b => {
    const c = cal[b.p.calidad];
    return [
      ['', null],
      [[['◆ ', c.color], [c.nombre, c.color]], 'cal'],
      [[['Probabilidad de la calidad: ', 'gray'], [pct(b.pCal), 'white']]],
      [[['Dentro de la calidad: ', 'gray'], [pct(b.pDentro), 'white']]],
      [[['Probabilidad total: ', 'gray'], [pct(b.pTotal), c.color]]],
    ];
  };
  const snbt = b => loreDe(b).map(([p]) => p === '' ? '{text:""}' : linea(p.map(([t, col]) => comp(t, col))));
  const ampLore = b => loreDe(b).map(([p]) => p === '' ? "''" :
    "'" + p.map(([t, col]) => (col === 'gray' ? '&7' : col === 'white' ? '&f' : amp(col)) + t).join('').replace(/'/g, "''") + "'");

  // Imagen del premio: objeto guardado (custom:<clave>) o imagen simple con lore propio
  const loreGuardado = {};
  for (const b of bloques) {
    const iD = b.lineas.findIndex(l => /^    display-item:/.test(l));
    const mat = (b.lineas.slice(iD + 1).find(l => /^      material:/.test(l)) || '').replace(/^\s+material:\s*/, '').trim();
    if (mat.startsWith('custom:')) {
      const clave = mat.slice(7);
      if (loreGuardado[clave]) throw new Error('Imagen compartida: ' + clave);
      loreGuardado[clave] = snbt(b);
    } else {
      // Quitar lineas de rareza viejas y añadir las nuevas al final de la lista de lore
      let j = iD + 1;
      while (j < b.lineas.length && /^      \S/.test(b.lineas[j])) j++;
      const zona = b.lineas.slice(iD, j);
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

  // Inyectar lore en los objetos guardados de internal-storage
  const out = L.slice(0, iRew);
  const iItems = out.findIndex(l => /^  items:\s*$/.test(l));
  for (const [clave, entradas] of Object.entries(loreGuardado)) {
    const re = new RegExp("^    '?" + clave.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + "'?:\\s*$");
    const s = out.findIndex((l, i) => i > iItems && re.test(l));
    if (s < 0) throw new Error('No esta en internal-storage: ' + clave);
    let e = s + 1;
    while (e < out.length && /^      /.test(out[e])) e++;
    const iComp = out.slice(s, e).findIndex(l => /^      components:\s*$/.test(l));
    const iLore = out.slice(s, e).findIndex(l => /^        minecraft:lore: '/.test(l));
    if (iLore >= 0) {
      const a = s + iLore;
      let k = a;
      // fin del escalar entre comillas simples: linea que termina en un numero impar de '
      while (!/(^|[^'])('')*'$/.test(out[k].trimEnd()) || (k === a && /minecraft:lore: '$/.test(out[k].trimEnd()))) k++;
      // desplegar el escalar (cada salto de linea es un espacio) y quitar las comillas
      const crudo = [out[a].replace(/^\s*minecraft:lore: '/, ''), ...out.slice(a + 1, k + 1).map(l => l.trim())].join(' ');
      const snbtLore = crudo.trimEnd().slice(0, -1).replace(/''/g, "'");
      if (!snbtLore.startsWith('[') || !snbtLore.endsWith(']')) throw new Error('Lore raro en ' + clave);
      // separar las entradas de primer nivel, quitar las de rareza vieja y las vacias del final
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
      while (limpios.length && /^\{(italic:0b,)?text:""\}$/.test(limpios[limpios.length - 1])) limpios.pop();
      const nuevo = '[' + [...limpios, ...entradas].join(',') + ']';
      out.splice(a, k - a + 1, "        minecraft:lore: '" + nuevo.replace(/'/g, "''") + "'");
    } else {
      const nueva = "        minecraft:lore: '[" + entradas.join(',') + "]'";
      if (iComp >= 0) out.splice(s + iComp + 1, 0, nueva);
      else out.splice(e, 0, '      components:', nueva);
    }
  }

  // Menu de vista previa propio de la caja
  if (caja.menu) {
    const iMenus = out.findIndex(l => /^menus:\s*$/.test(l));
    const iR = out.findIndex((l, i) => i > iMenus && /^  rewards:/.test(l));
    out[iR] = '  rewards: ' + caja.menu;
  }

  // Reordenar y renumerar
  const ord = spec.orden;
  bloques.sort((a, b) => ord.indexOf(a.p.calidad) - ord.indexOf(b.p.calidad) || a.pTotal - b.pTotal);
  bloques.forEach((b, n) => {
    b.lineas[0] = "  '" + n + "':";
    const iw = b.lineas.findIndex(l => /^    weight:/.test(l));
    b.lineas[iw] = '    weight: ' + +(b.pTotal * 10000).toFixed(6);
  });
  out.push(L[iRew], ...bloques.flatMap(b => b.lineas), ...L.slice(fin));

  const informe = bloques.map(b => [b.p.calidad, pct(b.pCal), pct(b.pDentro), pct(b.pTotal), b.p.match.replace(/^(mi give \w+ |material: custom:)/, '')]);
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
