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
    const compra = numero(nodo.buy);
    const venta = numero(nodo.sell);
    if (!compra && !venta) continue;

    if (venta && compra && venta >= compra) {
      avisos.push('BUCLE ' + material + ' (' + categoria + '.' + ruta + '): venta ' + venta + ' >= compra ' + compra);
      continue;
    }

    // Un item que solo se distingue por metadatos (los spawners de Ederus son
    // todos SPAWNER y cambian en 'spawnertype') no cabe en un catalogo indexado
    // por material. Antes de tratarlo como duplicado, se dice lo que es.
    const variante = nodo.spawnertype || nodo['potion-type'] || nodo.enchantments;
    if (variante) {
      avisos.push('VARIANTE ' + material + ' (' + categoria + '.' + ruta + '): se distingue por '
        + (nodo.spawnertype ? 'spawnertype=' + nodo.spawnertype : 'metadatos')
        + '. El catalogo indexa por material y no sabe separarlas.');
      continue;
    }

    const previo = porMaterial.get(material);
    if (previo) {
      // Mismo material en dos categorias AL MISMO PRECIO no hace daño: no hay
      // arbitraje posible, solo aparece dos veces en la tienda. Se queda una vez.
      if (previo.compra === compra && previo.venta === venta) {
        repetidosInocentes.push(material + ' (' + previo.categoria + ' y ' + categoria + ')');
        continue;
      }
      // A distinto precio SI es peligroso: comprar barato en una y vender caro
      // en otra es dinero infinito. Y quedarse con "el primero" dejaria que el
      // orden alfabetico de los ficheros decidiera. Lo decide un humano.
      avisos.push('DUPLICADO ' + material + ': en ' + previo.categoria
        + ' (compra ' + previo.compra + '/venta ' + previo.venta + ') y en '
        + categoria + ' (compra ' + compra + '/venta ' + venta + ')');
      continue;
    }

    porMaterial.set(material, {
      categoria,
      compra,
      venta,
      tope: parseInt(nodo['sell-limit-player'], 10) || 0,
      ventana: String(nodo['auto-restock-player-sell'] || '24h').trim()
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
    lineas.push('      ' + material + ':');
    lineas.push('        compra: ' + d.compra);
    lineas.push('        venta: ' + d.venta);
    lineas.push('        tope: ' + d.tope);
    lineas.push('        ventana: ' + d.ventana);
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
