// De los YAML de EconomyShopGUI-Premium al precios.yml de EderusTienda.
// uso: node migrar-esgui.js <carpeta-shops> <salida-precios.yml> [--informe]
//
// No da por sentada la estructura del fichero (paginas, subsecciones, el formato
// cambio entre la version gratuita y Premium): recorre el arbol entero y se
// queda con TODO nodo que tenga 'material' y al menos un precio. Asi da igual
// que ESGUI mueva las cosas de sitio en una version nueva.
//
// Lo que sale de aqui NO se sube sin mirarlo: el informe esta para eso.
const fs = require('fs');
const path = require('path');
const yaml = require('js-yaml');

const args = process.argv.slice(2);
const [carpeta, salida] = args.filter(a => !a.startsWith('--'));
const informe = args.includes('--informe');

if (!carpeta || !salida) {
  console.error('uso: node migrar-esgui.js <carpeta-shops> <salida-precios.yml> [--informe]');
  process.exit(2);
}

/** Recorre el arbol y devuelve los nodos que parecen un articulo de tienda. */
function articulos(nodo, ruta, encontrados) {
  if (!nodo || typeof nodo !== 'object') return encontrados;
  if (Array.isArray(nodo)) {
    nodo.forEach((n, i) => articulos(n, ruta + '[' + i + ']', encontrados));
    return encontrados;
  }
  const tieneMaterial = typeof nodo.material === 'string';
  const tienePrecio = nodo.buy !== undefined || nodo.sell !== undefined;
  // 'fill-item' y el 'item:' del icono de seccion tienen material pero no precio:
  // por eso se exige que haya al menos uno de los dos.
  if (tieneMaterial && tienePrecio) {
    encontrados.push({ ruta, nodo });
    return encontrados;
  }
  for (const [k, v] of Object.entries(nodo)) articulos(v, ruta ? ruta + '.' + k : k, encontrados);
  return encontrados;
}

const numero = v => {
  if (v === undefined || v === null) return 0;
  const n = typeof v === 'number' ? v : parseFloat(String(v).replace(',', '.'));
  return Number.isFinite(n) && n > 0 ? n : 0;
};

const ficheros = fs.readdirSync(carpeta)
  .filter(f => f.toLowerCase().endsWith('.yml'))
  .filter(f => !f.toLowerCase().includes('.bak'));   // ESGUI lee hasta los .bak; nosotros no

const porMaterial = new Map();
const avisos = [];
const repetidosInocentes = [];
const resumen = [];

for (const fichero of ficheros) {
  const categoria = path.basename(fichero, path.extname(fichero));
  let doc;
  try {
    doc = yaml.load(fs.readFileSync(path.join(carpeta, fichero), 'utf8'));
  } catch (e) {
    avisos.push('NO SE PUDO LEER ' + fichero + ': ' + e.message);
    continue;
  }

  const hallados = articulos(doc, '', []);
  let metidos = 0;
  for (const { ruta, nodo } of hallados) {
    const material = String(nodo.material).toUpperCase().split(':')[0].trim();

    /* Premium tiene DOS formas de poner el precio de compra: el 'buy:' de toda
     * la vida y un 'buy-prices:' con lista de monedas ('VAULT::300000'). Los 4
     * spawners de combate usan la segunda, y mirar solo la primera los dejaba
     * fuera del catalogo sin que nadie se enterara. */
    let compra = numero(nodo.buy);
    if (!compra && nodo['buy-prices'] && Array.isArray(nodo['buy-prices'].prices)) {
      for (const p of nodo['buy-prices'].prices) {
        const m = /^VAULT::([0-9.]+)/i.exec(String(p).trim());
        if (m) { compra = numero(m[1]); break; }
      }
      if (!compra) {
        avisos.push('MONEDA NO SOPORTADA ' + material + ' (' + categoria + '.' + ruta + '): '
          + JSON.stringify(nodo['buy-prices'].prices) + ' -- solo se entiende VAULT');
        continue;
      }
    }
    const venta = numero(nodo.sell);
    if (!compra && !venta) continue;

    /* Lo que hace que la ficha se vea como la suya y no como una lista de
     * precios: el lore escrito a mano, el tope de por vida y el permiso. */
    const loreItem = Array.isArray(nodo.lore) ? nodo.lore.slice() : null;
    const nombrePropio = typeof nodo.displayname === 'string' ? nodo.displayname : null;
    const limiteJugador = parseInt(nodo['stock-limit-player'], 10) || 0;
    let permiso = null, mensajePermiso = null;
    if (Array.isArray(nodo.requirements)) {
      for (const r of nodo.requirements) {
        const m = /^PERMISSION::([^:]+)::?(.*)$/.exec(String(r));
        if (m) { permiso = m[1]; mensajePermiso = m[2] || null; break; }
      }
    }

    if (venta && compra && venta >= compra) {
      avisos.push('BUCLE ' + material + ' (' + categoria + '.' + ruta + '): venta ' + venta + ' >= compra ' + compra);
      continue;
    }

    // Los 8 spawners de Ederus son todos SPAWNER y cambian en 'spawnertype': la
    // clave lleva el mob detras (SPAWNER:PIG) y el plugin construye el spawner
    // con su mob al entregarlo.
    let clave = material;
    if (nodo.spawnertype) {
      if (material !== 'SPAWNER') {
        avisos.push('VARIANTE en ' + material + ' (' + categoria + '.' + ruta
          + '): solo se soportan variantes de SPAWNER.');
        continue;
      }
      if (venta) {
        avisos.push('VARIANTE ' + material + ':' + nodo.spawnertype + ' tiene precio de venta '
          + venta + ', y una variante no se puede vender (el motor solo acepta items a pelo).');
        continue;
      }
      clave = 'SPAWNER:' + String(nodo.spawnertype).toUpperCase().trim();
    } else if (nodo['potion-type'] || nodo.enchantments) {
      avisos.push('VARIANTE ' + material + ' (' + categoria + '.' + ruta
        + '): se distingue por metadatos que el catalogo aun no sabe representar.');
      continue;
    }

    const previo = porMaterial.get(clave);
    if (previo) {
      // Mismo material en dos categorias AL MISMO PRECIO no hace daño: no hay
      // arbitraje posible, solo aparece dos veces en la tienda. Se queda una vez.
      if (previo.compra === compra && previo.venta === venta) {
        repetidosInocentes.push(clave + ' (' + previo.categoria + ' y ' + categoria + ')');
        continue;
      }
      // A distinto precio SI es peligroso: comprar barato en una y vender caro
      // en otra es dinero infinito. Y quedarse con "el primero" dejaria que el
      // orden alfabetico de los ficheros decidiera. Lo decide un humano.
      avisos.push('DUPLICADO ' + clave + ': en ' + previo.categoria
        + ' (compra ' + previo.compra + '/venta ' + previo.venta + ') y en '
        + categoria + ' (compra ' + compra + '/venta ' + venta + ')');
      continue;
    }

    porMaterial.set(clave, {
      categoria,
      compra,
      venta,
      tope: parseInt(nodo['sell-limit-player'], 10) || 0,
      ventana: String(nodo['auto-restock-player-sell'] || '24h').trim(),
      lore: loreItem,
      nombrePropio,
      limiteJugador,
      permiso,
      mensajePermiso
    });
    metidos++;
  }
  resumen.push({ categoria, hallados: hallados.length, metidos });
}

// Con un solo problema sin resolver NO se escribe nada. Un precios.yml a medias
// es peor que ninguno: parece bueno y le falta media tienda.
if (avisos.length) {
  console.error('La migracion NO se ha escrito. Hay ' + avisos.length + ' cosas que decidir:\n');
  avisos.forEach(x => console.error('  ' + x));
  console.error('\nArreglalo en los YAML de origen (o decide que precio vale) y vuelve a lanzarlo.');
  process.exit(1);
}

// ---- escribir precios.yml (a mano, para controlar el orden y los comentarios)
const lineas = [
  '# Generado por scripts/migrar-esgui.js el ' + new Date().toISOString().slice(0, 10),
  '# Origen: ' + path.resolve(carpeta),
  '# ' + porMaterial.size + ' articulos en ' + resumen.length + ' categorias.',
  '#',
  '# REVISAR antes de usar. Este fichero fija cuanto dinero entra y sale del servidor.',
  'categorias:'
];

const porCategoria = new Map();
for (const [material, d] of porMaterial) {
  if (!porCategoria.has(d.categoria)) porCategoria.set(d.categoria, []);
  porCategoria.get(d.categoria).push([material, d]);
}
for (const [categoria, items] of porCategoria) {
  lineas.push('  ' + categoria + ':');
  lineas.push('    items:');
  for (const [material, d] of items) {
    lineas.push('      ' + (material.includes(':') ? "'" + material + "'" : material) + ':');
    lineas.push('        compra: ' + d.compra);
    lineas.push('        venta: ' + d.venta);
    lineas.push('        tope: ' + d.tope);
    lineas.push('        ventana: ' + d.ventana);
    if (d.nombrePropio) lineas.push('        nombre: ' + JSON.stringify(d.nombrePropio));
    if (d.limiteJugador) lineas.push('        limite-jugador: ' + d.limiteJugador);
    if (d.permiso) {
      lineas.push('        permiso: ' + d.permiso);
      if (d.mensajePermiso) lineas.push('        mensaje-permiso: ' + JSON.stringify(d.mensajePermiso));
    }
    if (d.lore && d.lore.length) {
      lineas.push('        lore:');
      d.lore.forEach(l => lineas.push('        - ' + JSON.stringify(String(l))));
    }
  }
}
fs.writeFileSync(salida, lineas.join('\n') + '\n', 'utf8');

console.log('OK ' + salida);
console.log('  ' + porMaterial.size + ' articulos en ' + resumen.length + ' categorias');
const conTope = [...porMaterial.values()].filter(d => d.tope > 0).length;
const soloCompra = [...porMaterial.values()].filter(d => d.compra && !d.venta).length;
const soloVenta = [...porMaterial.values()].filter(d => d.venta && !d.compra).length;
console.log('  ' + conTope + ' con tope de venta | ' + soloCompra + ' solo compra | ' + soloVenta + ' solo venta');
if (repetidosInocentes.length) {
  console.log('\n' + repetidosInocentes.length + ' repetidos al MISMO precio (inofensivos, se quedan una vez):');
  repetidosInocentes.forEach(x => console.log('  ' + x));
}

if (informe) {
  console.log('\npor categoria:');
  resumen.forEach(r => console.log('  ' + r.categoria.padEnd(20) + r.metidos + '/' + r.hallados));
}
