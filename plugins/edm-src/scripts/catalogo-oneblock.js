/*
 * Genera src/main/resources-oneblock/tienda/precios.yml, el catalogo de la
 * tienda para OneBlock, a partir del catalogo de Survival
 * (Documents/Ederus/tienda-propia/precios-ederus.yml).
 *
 * La idea, medida sobre los JSON de fases de SSBOneBlock el 2026-09-19:
 *   - La tienda solo RECOMPRA materia prima: lo que suelta el bloque (piedra,
 *     troncos, minerales, cultivos), los drops de mob y poco mas. Los bloques
 *     decorativos y crafteados solo se compran, y asi no hay bucle de
 *     comprar-craftear-vender (el ESGUI de fabrica tenia 28).
 *   - Los precios de venta salen de una curva por fase: ~550$ por cada 1.000
 *     bloques picados en Llanuras y ~2.900$ en el End (x5, no el x80 que
 *     tenia ESGUI). A 2.000 bloques/hora a mano son 1.000-6.000$/h.
 *   - Los precios de compra de Survival se bajan al 40% (Survival vende el
 *     diamante a 150, aqui a 60) y nunca quedan por debajo de 3x la venta.
 *   - Los spawners se cobran a ~40 h de lo que produce uno solo.
 *
 * Uso: node scripts/catalogo-oneblock.js
 */
const fs = require('fs');
const path = require('path');

const BASE = 'C:/Users/Dosa/Documents/Ederus/tienda-propia/precios-ederus.yml';
const OUT = path.join(__dirname, '..', 'src', 'main', 'resources-oneblock', 'tienda', 'precios.yml');
const ESCALA_COMPRA = 0.4;   // Survival -> OneBlock
const MARGEN_MIN = 3;        // compra >= venta x 3 cuando el item se compra y se vende

// Lo que la tienda recompra en OneBlock y a cuanto. Clave en minusculas.
const VENTA = {
  // lo que suelta el bloque
  cobblestone: 0.2, dirt: 0.15, sand: 0.3, red_sand: 0.3, gravel: 0.3, clay_ball: 0.25,
  snowball: 0.1, packed_ice: 0.6, blue_ice: 3, andesite: 0.3, diorite: 0.3, granite: 0.3,
  netherrack: 0.3, soul_sand: 0.5, soul_soil: 0.5, end_stone: 0.8, obsidian: 8, crying_obsidian: 15,
  magma_block: 1, basalt: 0.5, blackstone: 0.5, deepslate: 0.3, tuff: 0.3, calcite: 0.5,
  dripstone_block: 0.5, moss_block: 0.5, mud: 0.3, mycelium: 0.5, podzol: 0.5,
  crimson_nylium: 0.5, warped_nylium: 0.5, nether_wart_block: 1, warped_wart_block: 1,
  shroomlight: 3, sponge: 12, wet_sponge: 12, hay_block: 9, dried_kelp_block: 3,
  slime_block: 27, bone_block: 10,
  // bloques de fase que se venden a precio simbolico (sin bucle: sus compras van muy por encima)
  prismarine: 1, prismarine_bricks: 2, dark_prismarine: 2, end_stone_bricks: 1, purpur_block: 1,
  purpur_pillar: 1, sandstone: 0.4, red_sandstone: 0.4, terracotta: 0.4, stone_bricks: 0.3,
  mossy_stone_bricks: 0.4, mossy_cobblestone: 0.4, quartz_block: 2, nether_bricks: 0.5,
  red_nether_bricks: 0.8,
  // minerales
  coal: 3, raw_iron: 5.5, iron_ingot: 6, raw_copper: 1.4, copper_ingot: 1.5, raw_gold: 11,
  gold_ingot: 12, gold_nugget: 1.3, lapis_lazuli: 2.5, redstone: 2, diamond: 60, emerald: 25,
  quartz: 3, ancient_debris: 150, amethyst_shard: 3, glowstone_dust: 1.5,
  // cultivos
  wheat: 1, carrot: 1, potato: 1, beetroot: 1, melon_slice: 0.4, pumpkin: 2, sugar_cane: 1,
  cactus: 1, bamboo: 0.3, kelp: 0.3, dried_kelp: 0.5, cocoa_beans: 2, nether_wart: 2,
  sweet_berries: 0.5, glow_berries: 1, honeycomb: 5, honey_bottle: 8, chorus_fruit: 3,
  brown_mushroom: 1, red_mushroom: 1, sea_pickle: 1, apple: 1, egg: 1,
  // botin de mobs
  rotten_flesh: 1.5, bone: 2.5, string: 2, spider_eye: 2, gunpowder: 5, ender_pearl: 8,
  blaze_rod: 12, ghast_tear: 25, magma_cream: 4, slime_ball: 3, phantom_membrane: 6,
  leather: 3, feather: 1, beef: 2, porkchop: 2, chicken: 2, mutton: 2, rabbit: 2,
  rabbit_hide: 2, rabbit_foot: 10, cod: 2, salmon: 2.5, tropical_fish: 3, pufferfish: 4,
  ink_sac: 2, glow_ink_sac: 4, prismarine_shard: 4, prismarine_crystals: 3, nautilus_shell: 40,
  turtle_scute: 30, shulker_shell: 80, arrow: 0.5, white_wool: 1.5, wither_skeleton_skull: 300,
  poppy: 0.3, flint: 1, charcoal: 1.5, cobweb: 0.5, iron_nugget: 0.6, book: 3,
  // troncos (todos igual)
  oak_log: 1, spruce_log: 1, birch_log: 1, jungle_log: 1, acacia_log: 1, dark_oak_log: 1,
  mangrove_log: 1, cherry_log: 1, pale_oak_log: 1, crimson_stem: 1, warped_stem: 1,
};

// Donde va un item que no esta en el catalogo de Survival.
const CATEGORIA_NUEVOS = {
  Minerales: ['raw_copper', 'copper_ingot', 'quartz', 'ancient_debris', 'amethyst_shard', 'gold_nugget',
    'redstone', 'lapis_lazuli', 'glowstone_dust', 'iron_nugget'],
  Mobs: ['rotten_flesh', 'bone', 'string', 'spider_eye', 'gunpowder', 'ender_pearl', 'blaze_rod',
    'ghast_tear', 'magma_cream', 'slime_ball', 'phantom_membrane', 'leather', 'feather', 'beef',
    'porkchop', 'chicken', 'mutton', 'rabbit', 'rabbit_hide', 'rabbit_foot', 'arrow',
    'wither_skeleton_skull', 'shulker_shell', 'egg'],
  Marinos: ['cod', 'salmon', 'tropical_fish', 'pufferfish', 'ink_sac', 'glow_ink_sac',
    'prismarine_shard', 'prismarine_crystals', 'nautilus_shell', 'turtle_scute'],
  Cultivos: ['wheat', 'carrot', 'potato', 'beetroot', 'melon_slice', 'pumpkin', 'sugar_cane', 'cactus',
    'bamboo', 'kelp', 'dried_kelp', 'cocoa_beans', 'nether_wart', 'sweet_berries', 'glow_berries',
    'honeycomb', 'honey_bottle', 'chorus_fruit', 'brown_mushroom', 'red_mushroom', 'sea_pickle', 'apple'],
  Madera: ['oak_log', 'spruce_log', 'birch_log', 'jungle_log', 'acacia_log', 'dark_oak_log',
    'mangrove_log', 'cherry_log', 'pale_oak_log', 'crimson_stem', 'warped_stem', 'charcoal'],
  Bloques: ['nether_wart_block', 'warped_wart_block', 'hay_block', 'dried_kelp_block', 'slime_block'],
  Variedad: ['poppy', 'flint', 'cobweb', 'book', 'white_wool', 'snowball', 'clay_ball'],
};

// Spawners: precio ~ 40 h de lo que produce uno (350 mobs/h) con los drops de arriba.
const SPAWNERS = {
  ZOMBIE: 25000, HUSK: 25000, DROWNED: 35000, SKELETON: 50000, STRAY: 50000, SPIDER: 40000,
  CAVE_SPIDER: 40000, CREEPER: 70000, ENDERMAN: 60000, BLAZE: 85000, WITHER_SKELETON: 150000,
  ZOMBIFIED_PIGLIN: 40000, PIGLIN: 80000, PIGLIN_BRUTE: 90000, HOGLIN: 80000, MAGMA_CUBE: 100000,
  SLIME: 120000, WITCH: 150000, GHAST: 250000, PHANTOM: 45000, GUARDIAN: 120000, SHULKER: 500000,
  IRON_GOLEM: 350000, PIG: 40000, COW: 60000, MOOSHROOM: 60000, SHEEP: 50000, CHICKEN: 50000,
  RABBIT: 40000, HORSE: 40000, WOLF: 40000, PANDA: 40000, PARROT: 40000, SQUID: 50000,
};

// ---------------------------------------------------------------------------
// Leer el catalogo base (formato regular: categoria / items / KEY / 4 campos).
// La categoria Spawners se salta entera: se reescribe.
// ---------------------------------------------------------------------------
const base = fs.readFileSync(BASE, 'utf8').split(/\r?\n/);
const cats = new Map(); // nombre -> Map(KEY -> {compra, venta})
let cat = null, key = null;
for (const l of base) {
  let m;
  if ((m = l.match(/^  ([A-Za-z_]+):\s*$/))) { cat = m[1]; key = null; if (!cats.has(cat)) cats.set(cat, new Map()); continue; }
  if (cat === 'Spawners') continue;
  if ((m = l.match(/^      '?([A-Z_:]+)'?:\s*$/))) { key = m[1]; cats.get(cat).set(key, { compra: 0, venta: 0 }); continue; }
  if (key && (m = l.match(/^        (compra|venta):\s*([\d.]+)/))) cats.get(cat).get(key)[m[1]] = +m[2];
}
cats.delete('Spawners');

// ---------------------------------------------------------------------------
// Reescribir: compra al 40%, venta segun VENTA (0 si no esta), margen minimo.
// ---------------------------------------------------------------------------
const donde = new Map(); // KEY -> categoria
for (const [c, items] of cats) for (const k of items.keys()) donde.set(k, c);

let quitados = [];
for (const [c, items] of cats) {
  for (const [k, it] of items) {
    if (/NETHERITE_(SWORD|PICKAXE|AXE|SHOVEL|HOE)/.test(k)) { items.delete(k); quitados.push(k); continue; }
    it.compra = it.compra > 0 ? Math.max(1, Math.round(it.compra * ESCALA_COMPRA)) : 0;
    it.venta = VENTA[k.toLowerCase()] ?? 0;
  }
}
for (const [c, lista] of Object.entries(CATEGORIA_NUEVOS)) {
  for (const k of lista) {
    const K = k.toUpperCase();
    if (donde.has(K)) continue;
    if (!cats.has(c)) cats.set(c, new Map());
    cats.get(c).set(K, { compra: 0, venta: VENTA[k] ?? 0 });
    donde.set(K, c);
  }
}
const sinSitio = Object.keys(VENTA).filter(k => !donde.has(k.toUpperCase()));
let margenes = 0;
for (const [, items] of cats) for (const [k, it] of items) {
  if (it.compra > 0 && it.venta > 0 && it.compra < it.venta * MARGEN_MIN) { it.compra = Math.ceil(it.venta * MARGEN_MIN); margenes++; }
}

// ---------------------------------------------------------------------------
// Bucles de crafteo: comprar los ingredientes y vender el resultado no puede
// dar beneficio. Misma lista de recetas con la que se midio el ESGUI de fabrica.
// ---------------------------------------------------------------------------
const precio = k => { const c = donde.get(k.toUpperCase()); return c ? cats.get(c).get(k.toUpperCase()) : { compra: 0, venta: 0 }; };
const R = [];
const woods = ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry', 'pale_oak', 'crimson', 'warped'];
for (const w of woods) {
  const log = /crimson|warped/.test(w) ? w + '_stem' : w + '_log';
  const wood = /crimson|warped/.test(w) ? w + '_hyphae' : w + '_wood';
  R.push([log + '>4 planks', [[log, 1]], [[w + '_planks', 4]]]);
  R.push(['3 ' + log + '>3 wood', [[log, 3]], [[wood, 3]]]);
  R.push(['6 planks>4 stairs', [[w + '_planks', 6]], [[w + '_stairs', 4]]]);
  R.push(['3 planks>6 slab', [[w + '_planks', 3]], [[w + '_slab', 6]]]);
  R.push(['log>charcoal', [[log, 1]], [['charcoal', 1]]]);
}
const st = ['stone', 'cobblestone', 'stone_bricks', 'end_stone_bricks', 'sandstone', 'red_sandstone', 'nether_bricks',
  'red_nether_bricks', 'prismarine', 'prismarine_bricks', 'dark_prismarine', 'quartz_block', 'polished_blackstone_bricks',
  'polished_blackstone', 'blackstone', 'bricks', 'polished_andesite', 'polished_diorite', 'polished_granite', 'andesite',
  'diorite', 'granite', 'purpur_block', 'mossy_cobblestone', 'deepslate_bricks', 'polished_deepslate', 'cobbled_deepslate',
  'mud_bricks', 'smooth_stone', 'cut_copper', 'mossy_stone_bricks', 'tuff', 'polished_tuff'];
for (const s of st) {
  R.push(['6 ' + s + '>4 stairs', [[s, 6]], [[s + '_stairs', 4]]]);
  R.push(['3 ' + s + '>6 slab', [[s, 3]], [[s + '_slab', 6]]]);
  R.push(['6 ' + s + '>6 wall', [[s, 6]], [[s + '_wall', 6]]]);
}
R.push(
  ['4 stone>4 stone_bricks', [['stone', 4]], [['stone_bricks', 4]]],
  ['cobble>stone', [['cobblestone', 1]], [['stone', 1]]],
  ['4 end_stone>4 bricks', [['end_stone', 4]], [['end_stone_bricks', 4]]],
  ['4 andesite>4 polished', [['andesite', 4]], [['polished_andesite', 4]]],
  ['4 diorite>4 polished', [['diorite', 4]], [['polished_diorite', 4]]],
  ['4 granite>4 polished', [['granite', 4]], [['polished_granite', 4]]],
  ['4 blackstone>4 polished', [['blackstone', 4]], [['polished_blackstone', 4]]],
  ['4 pol_blackstone>4 bricks', [['polished_blackstone', 4]], [['polished_blackstone_bricks', 4]]],
  ['4 quartz_block>4 bricks', [['quartz_block', 4]], [['quartz_bricks', 4]]],
  ['4 quartz>block', [['quartz', 4]], [['quartz_block', 1]]],
  ['4 sand>sandstone', [['sand', 4]], [['sandstone', 1]]],
  ['4 red_sand>red_sandstone', [['red_sand', 4]], [['red_sandstone', 1]]],
  ['concrete', [['sand', 4], ['gravel', 4], ['red_dye', 1]], [['red_concrete', 8]]],
  ['cookie', [['wheat', 2], ['cocoa_beans', 1]], [['cookie', 8]]],
  ['baked', [['potato', 1]], [['baked_potato', 1]]],
  ['cooked_beef', [['beef', 1]], [['cooked_beef', 1]]],
  ['cooked_chicken', [['chicken', 1]], [['cooked_chicken', 1]]],
  ['cooked_mutton', [['mutton', 1]], [['cooked_mutton', 1]]],
  ['cooked_porkchop', [['porkchop', 1]], [['cooked_porkchop', 1]]],
  ['cooked_rabbit', [['rabbit', 1]], [['cooked_rabbit', 1]]],
  ['cooked_cod', [['cod', 1]], [['cooked_cod', 1]]],
  ['cooked_salmon', [['salmon', 1]], [['cooked_salmon', 1]]],
  ['bread', [['wheat', 3]], [['bread', 1]]],
  ['jack', [['pumpkin', 1]], [['jack_o_lantern', 1]]],
  ['snow_block', [['snowball', 4]], [['snow_block', 1]]],
  ['hay', [['wheat', 9]], [['hay_block', 1]]],
  ['hay>9 wheat', [['hay_block', 1]], [['wheat', 9]]],
  ['slime_block', [['slime_ball', 9]], [['slime_block', 1]]],
  ['slime_block>9', [['slime_block', 1]], [['slime_ball', 9]]],
  ['honey_block', [['honey_bottle', 4]], [['honey_block', 1]]],
  ['bone_block', [['bone_meal', 9]], [['bone_block', 1]]],
  ['bone>3 meal', [['bone', 1]], [['bone_meal', 3]]],
  ['bone_block>9 meal', [['bone_block', 1]], [['bone_meal', 9]]],
  ['dried_kelp_block', [['dried_kelp', 9]], [['dried_kelp_block', 1]]],
  ['dried_kelp_block>9', [['dried_kelp_block', 1]], [['dried_kelp', 9]]],
  ['kelp>dried', [['kelp', 1]], [['dried_kelp', 1]]],
  ['9 iron>block', [['iron_ingot', 9]], [['iron_block', 1]]],
  ['iron_block>9', [['iron_block', 1]], [['iron_ingot', 9]]],
  ['raw_iron>ingot', [['raw_iron', 1]], [['iron_ingot', 1]]],
  ['raw_gold>ingot', [['raw_gold', 1]], [['gold_ingot', 1]]],
  ['raw_copper>ingot', [['raw_copper', 1]], [['copper_ingot', 1]]],
  ['9 gold>block', [['gold_ingot', 9]], [['gold_block', 1]]],
  ['gold_block>9', [['gold_block', 1]], [['gold_ingot', 9]]],
  ['9 nugget>gold', [['gold_nugget', 9]], [['gold_ingot', 1]]],
  ['gold>9 nugget', [['gold_ingot', 1]], [['gold_nugget', 9]]],
  ['9 iron_nugget>iron', [['iron_nugget', 9]], [['iron_ingot', 1]]],
  ['iron>9 nugget', [['iron_ingot', 1]], [['iron_nugget', 9]]],
  ['9 diamond>block', [['diamond', 9]], [['diamond_block', 1]]],
  ['diamond_block>9', [['diamond_block', 1]], [['diamond', 9]]],
  ['9 emerald>block', [['emerald', 9]], [['emerald_block', 1]]],
  ['emerald_block>9', [['emerald_block', 1]], [['emerald', 9]]],
  ['9 lapis>block', [['lapis_lazuli', 9]], [['lapis_block', 1]]],
  ['lapis_block>9', [['lapis_block', 1]], [['lapis_lazuli', 9]]],
  ['9 redstone>block', [['redstone', 9]], [['redstone_block', 1]]],
  ['redstone_block>9', [['redstone_block', 1]], [['redstone', 9]]],
  ['9 coal>block', [['coal', 9]], [['coal_block', 1]]],
  ['coal_block>9', [['coal_block', 1]], [['coal', 9]]],
  ['9 copper>block', [['copper_ingot', 9]], [['copper_block', 1]]],
  ['copper_block>9', [['copper_block', 1]], [['copper_ingot', 9]]],
  ['4 amethyst>block', [['amethyst_shard', 4]], [['amethyst_block', 1]]],
  ['4 dust>glowstone', [['glowstone_dust', 4]], [['glowstone', 1]]],
  ['glowstone>4 dust', [['glowstone', 1]], [['glowstone_dust', 4]]],
  ['bookshelf', [['oak_planks', 6], ['book', 3]], [['bookshelf', 1]]],
  ['bookshelf>3 book', [['bookshelf', 1]], [['book', 3], ['oak_planks', 6]]],
  ['4 string>wool', [['string', 4]], [['white_wool', 1]]],
  ['wool+dye', [['white_wool', 1], ['red_dye', 1]], [['red_wool', 1]]],
  ['2 wool>3 carpet', [['white_wool', 2]], [['white_carpet', 3]]],
  ['4 clay_ball>clay', [['clay_ball', 4]], [['clay', 1]]],
  ['clay>4 balls', [['clay', 1]], [['clay_ball', 4]]],
  ['clay>terracotta', [['clay', 1]], [['terracotta', 1]]],
  ['clay_ball>brick', [['clay_ball', 1]], [['brick', 1]]],
  ['4 brick>bricks', [['brick', 4]], [['bricks', 1]]],
  ['sand>glass', [['sand', 1]], [['glass', 1]]],
  ['netherrack>nether_brick', [['netherrack', 1]], [['nether_brick', 1]]],
  ['4 nether_brick>bricks', [['nether_brick', 4]], [['nether_bricks', 1]]],
  ['red_nether_bricks', [['nether_wart', 2], ['nether_brick', 2]], [['red_nether_bricks', 1]]],
  ['9 wart>block', [['nether_wart', 9]], [['nether_wart_block', 1]]],
  ['4 shard>prismarine', [['prismarine_shard', 4]], [['prismarine', 1]]],
  ['9 shard>bricks', [['prismarine_shard', 9]], [['prismarine_bricks', 1]]],
  ['dark_prismarine', [['prismarine_shard', 8], ['ink_sac', 1]], [['dark_prismarine', 1]]],
  ['sea_lantern', [['prismarine_crystals', 4], ['prismarine_shard', 5]], [['sea_lantern', 1]]],
  ['purpur', [['popped_chorus_fruit', 4]], [['purpur_block', 4]]],
  ['chorus>popped', [['chorus_fruit', 1]], [['popped_chorus_fruit', 1]]],
  ['cactus>green', [['cactus', 1]], [['green_dye', 1]]],
  ['wet_sponge>sponge', [['wet_sponge', 1]], [['sponge', 1]]],
  ['9 ice>packed', [['ice', 9]], [['packed_ice', 1]]],
  ['9 packed>blue', [['packed_ice', 9]], [['blue_ice', 1]]],
  ['sugar', [['sugar_cane', 1]], [['sugar', 1]]],
  ['paper', [['sugar_cane', 3]], [['paper', 3]]],
  ['book', [['paper', 3], ['leather', 1]], [['book', 1]]],
  ['stew', [['red_mushroom', 1], ['brown_mushroom', 1], ['bowl', 1]], [['mushroom_stew', 1]]],
  ['soup', [['beetroot', 6], ['bowl', 1]], [['beetroot_soup', 1]]],
  ['pie', [['pumpkin', 1], ['sugar', 1], ['egg', 1]], [['pumpkin_pie', 1]]],
  ['melon', [['melon_slice', 9]], [['melon', 1]]],
  ['melon>9', [['melon', 1]], [['melon_slice', 9]]],
  ['pumpkin_seeds', [['pumpkin', 1]], [['pumpkin_seeds', 4]]],
  ['melon_seeds', [['melon_slice', 1]], [['melon_seeds', 1]]],
  ['bucket', [['iron_ingot', 3]], [['bucket', 1]]],
  ['minecart', [['iron_ingot', 5]], [['minecart', 1]]],
  ['iron_door', [['iron_ingot', 6]], [['iron_door', 1]]],
  ['hopper', [['iron_ingot', 5], ['chest', 1]], [['hopper', 1]]],
  ['chest', [['oak_planks', 8]], [['chest', 1]]],
  ['bowl', [['oak_planks', 3]], [['bowl', 4]]],
  ['stick', [['oak_planks', 2]], [['stick', 4]]],
  ['arrow', [['flint', 1], ['stick', 1], ['feather', 1]], [['arrow', 4]]],
  ['ladder', [['stick', 7]], [['ladder', 3]]],
  ['bed', [['white_wool', 3], ['oak_planks', 3]], [['red_bed', 1]]],
  ['golden_apple', [['gold_ingot', 8], ['apple', 1]], [['golden_apple', 1]]],
  ['golden_carrot', [['gold_nugget', 8], ['carrot', 1]], [['golden_carrot', 1]]],
  ['glistering', [['gold_nugget', 8], ['melon_slice', 1]], [['glistering_melon_slice', 1]]],
  ['tnt', [['gunpowder', 5], ['sand', 4]], [['tnt', 1]]],
  ['fire_charge', [['blaze_powder', 1], ['coal', 1], ['gunpowder', 1]], [['fire_charge', 3]]],
  ['blaze_powder', [['blaze_rod', 1]], [['blaze_powder', 2]]],
  ['magma_cream', [['blaze_powder', 1], ['slime_ball', 1]], [['magma_cream', 1]]],
  ['eye', [['blaze_powder', 1], ['ender_pearl', 1]], [['ender_eye', 1]]],
  ['leather_armor', [['leather', 8]], [['leather_chestplate', 1]]],
  ['honeycomb_block', [['honeycomb', 4]], [['honeycomb_block', 1]]],
  ['honeycomb_block>4', [['honeycomb_block', 1]], [['honeycomb', 4]]],
  ['scaffolding', [['bamboo', 6], ['string', 1]], [['scaffolding', 6]]],
  ['bamboo_planks', [['bamboo', 9]], [['bamboo_planks', 2]]],
  ['moss_carpet', [['moss_block', 1]], [['moss_carpet', 2]]],
  ['mossy_cobble', [['cobblestone', 1], ['vine', 1]], [['mossy_cobblestone', 1]]],
  ['mossy_cobble_moss', [['cobblestone', 1], ['moss_block', 1]], [['mossy_cobblestone', 1]]],
  ['mossy_bricks', [['stone_bricks', 1], ['moss_block', 1]], [['mossy_stone_bricks', 1]]],
  ['smooth_stone', [['stone', 1]], [['smooth_stone', 1]]],
  ['smooth_sandstone', [['sandstone', 1]], [['smooth_sandstone', 1]]],
  ['cut_sandstone', [['sandstone', 4]], [['cut_sandstone', 4]]],
  ['chiseled_sandstone', [['sandstone_slab', 2]], [['chiseled_sandstone', 1]]],
  ['deepslate', [['cobbled_deepslate', 1]], [['deepslate', 1]]],
  ['deepslate_bricks', [['polished_deepslate', 4]], [['deepslate_bricks', 4]]],
  ['polished_deepslate', [['cobbled_deepslate', 4]], [['polished_deepslate', 4]]],
  ['cracked_stone_bricks', [['stone_bricks', 1]], [['cracked_stone_bricks', 1]]],
  ['chiseled_stone_bricks', [['stone_brick_slab', 2]], [['chiseled_stone_bricks', 1]]],
  ['polished_basalt', [['basalt', 4]], [['polished_basalt', 4]]],
  ['smooth_basalt', [['basalt', 1]], [['smooth_basalt', 1]]],
  ['tuff_bricks', [['polished_tuff', 4]], [['tuff_bricks', 4]]],
  ['polished_tuff', [['tuff', 4]], [['polished_tuff', 4]]],
  ['mud_bricks', [['packed_mud', 4]], [['mud_bricks', 4]]],
  ['packed_mud', [['mud', 1], ['wheat', 1]], [['packed_mud', 1]]],
  ['mud', [['dirt', 1]], [['mud', 1]]],
  ['coarse', [['dirt', 2], ['gravel', 2]], [['coarse_dirt', 4]]],
  ['obsidian? no', [['obsidian', 1]], [['obsidian', 1]]],
);
const bucles = [];
for (const [nm, ins, outs] of R) {
  let coste = 0, ok = true;
  for (const [k, q] of ins) { const p = precio(k); if (p.compra <= 0) { ok = false; break; } coste += p.compra * q; }
  if (!ok) continue;
  let ingreso = 0;
  for (const [k, q] of outs) { const p = precio(k); if (p.venta > 0) ingreso += p.venta * q; }
  if (ingreso > coste) bucles.push(`${nm} ${coste.toFixed(2)} -> ${ingreso.toFixed(2)}`);
}

// ---------------------------------------------------------------------------
// Escribir
// ---------------------------------------------------------------------------
const orden = ['Bloques', 'Minerales', 'Alimentos', 'Mobs', 'Decoracion', 'Bloques_Modernos', 'Variedad',
  'Herramientas', 'Madera', 'Cultivos', 'Marinos', 'Flora', 'Herreria', 'Spawners'];
const num = n => (Number.isInteger(n) ? String(n) : String(+n.toFixed(2)));
let out = [];
out.push('# Catalogo de la tienda de OneBlock. Generado por scripts/catalogo-oneblock.js.');
out.push('#');
out.push('# La tienda solo RECOMPRA materia prima: lo que suelta el bloque, los drops de');
out.push('# mob y los cultivos. Los bloques decorativos y crafteados solo se compran, y');
out.push('# asi no hay bucle de comprar-craftear-vender. Los precios de venta siguen la');
out.push('# curva por fase medida en los JSON de SSBOneBlock (Llanuras ~550$ por cada');
out.push('# 1.000 bloques picados, End ~2.900$).');
out.push('#');
out.push('#   compra:  lo que paga el jugador por una unidad. 0 = la tienda no lo vende.');
out.push('#   venta:   lo que cobra el jugador por una unidad. 0 = la tienda no lo compra.');
out.push('#   tope:    0 = sin tope (los topes por jugador estan apagados en config.yml).');
out.push('categorias:');
let total = 0, vendibles = 0;
for (const c of orden) {
  out.push(`  ${c}:`);
  out.push('    items:');
  if (c === 'Spawners') {
    for (const [mob, p] of Object.entries(SPAWNERS)) {
      out.push(`      'SPAWNER:${mob}':`);
      out.push(`        compra: ${p}`);
      out.push('        venta: 0');
      out.push('        tope: 0');
      out.push('        ventana: 24h');
      total++;
    }
    continue;
  }
  const items = cats.get(c) || new Map();
  for (const [k, it] of items) {
    out.push(`      ${k}:`);
    out.push(`        compra: ${num(it.compra)}`);
    out.push(`        venta: ${num(it.venta)}`);
    out.push('        tope: 0');
    out.push('        ventana: 24h');
    total++;
    if (it.venta > 0) vendibles++;
  }
}
fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, out.join('\n') + '\n');
console.log(`escrito ${OUT}`);
console.log(`articulos ${total}, se recompran ${vendibles}, spawners ${Object.keys(SPAWNERS).length}`);
console.log(`compras subidas por margen: ${margenes}; quitados: ${quitados.join(', ') || 'ninguno'}`);
console.log(`sin categoria (no se vendieron): ${sinSitio.join(', ') || 'ninguno'}`);
console.log(`bucles: ${bucles.length}${bucles.length ? '\n  ' + bucles.join('\n  ') : ''}`);
