package net.ederus.calamity.hardcore;

import net.ederus.edm.dungeonloot.BovedaAbiertaEvent;
import net.ederus.edm.dungeonloot.DungeonLootPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Calamity 1.11 · Cofres y Bovedas en las ruinas de Lethal World.
 *
 * El problema: las ruinas Valtury del datapack (lethal_world:ruinas_*) solo traen bloques y las
 * estructuras de Bracken que quedan casi no tienen cofres; un jugador cruzo 2.000 bloques sin ver
 * uno. El datapack no se toca y el mundo no se regenera (hay una construccion en el spawn), asi que
 * los cofres se ponen al pasar.
 *
 * Como:
 * - Una vez por segundo, alrededor de cada jugador que cuenta y esta fuera del spawn, se miran los
 *   chunks CARGADOS que aun no se han mirado en esta sesion (radio-chunks, chunks-por-segundo por
 *   jugador). Solo si sus vecinos a 2 chunks tambien estan cargados: Chunk.getStructures() resuelve
 *   el inicio de cada estructura que pasa por el chunk y asi nunca tiene que ir a buscarlo a uno sin
 *   cargar. Nada se carga nunca.
 * - Por cada ruina (estructura cuyo id empieza por uno de estructuras), la decision es determinista:
 *   un hash de la semilla del config, el tipo de estructura y la esquina de su caja. Da igual quien
 *   llegue primero o cuantas veces se mire: la misma ruina siempre dice lo mismo. Con cofre
 *   (cofres.probabilidad) y/o boveda (bovedas.probabilidad).
 * - El sitio: puntos de la caja en un orden que tambien sale del hash; en cada columna, el primer
 *   hueco desde abajo con suelo firme debajo, aire (o hierba, flores, nieve) en el sitio y paso libre
 *   encima. Asi cae en el suelo de la ruina y no encima de un muro, y no se pisa nada de la ruina.
 *   Si algun punto cae en un chunk sin cargar y no hay sitio todavia, se deja para otra pasada.
 * - Lo que se pone se apunta en ruinas-datos.yml (mundo -> ruina -> donde y cuando). Es lo que hace
 *   que una ruina no vuelva a dar cofre aunque el cofre se vacie o el servidor se reinicie: la
 *   decision se repite igual, pero el registro dice que ya esta. Las ruinas sin nada no se apuntan
 *   (su decision siempre sera "nada"). Se eligio este fichero y no el PDC de la estructura o del chunk
 *   porque una ruina cruza varios chunks y su inicio puede estar en uno que no es el que se mira: con
 *   el PDC, dos chunks podrian decidir a la vez sin verse.
 * - El cofre lleva su marca en el PDC del bloque (calamity:ruina) y su estado: sellado (el botin se
 *   tira al abrirlo por primera vez un jugador que cuenta, como los cofres de estructura de Cofres) y
 *   vacio-desde. Vacio, se vuelve a sellar pasados reponer-minutos si hay alguien a reponer-radio.
 *
 * El botin pasa por las mismas reglas que Cofres: el mismo tope diario (cofres.pagados-dia, el mismo
 * contador: un cofre de ruina cuenta como uno de estructura), solo con jugador que cuenta, el
 * progreso del contrato "cofre", Bitacora ("ruina | ...") y telemetria ("ruina-cofre"). Con el tope
 * lleno el cofre no se abre y se queda sellado para otro.
 *
 * La Boveda de Ruinas es la caja calamity_ruinas de EDM (una apertura por jugador, Llave del Umbral);
 * aqui solo se planta y se paga su botin (BovedaAbiertaEvent).
 *
 * Nada en la zona spawn ni en las regiones de excluir-regiones (calamity_exterior).
 */
final class Ruinas implements Listener {

    static final String ARCHIVO = "ruinas-datos.yml";
    static final List<String> ESTRUCTURAS_DE_SERIE = List.of("lethal_world:ruinas_");
    static final List<String> EXCLUIR_DE_SERIE = List.of("calamity_exterior");

    /** Lo de serie del botin de un cofre de ruina (hardcore.ruinas.cofres.botin). */
    static final List<Map<?, ?>> COFRE_DE_SERIE = List.of(
            Map.of("objeto", "esencia", "prob", 0.45, "min", 1, "max", 2),
            Map.of("objeto", "reliquia-1", "prob", 0.55, "min", 1, "max", 3),
            Map.of("objeto", "reliquia-2", "prob", 0.25, "min", 1, "max", 1),
            Map.of("objeto", "reliquia-3", "prob", 0.05, "min", 1, "max", 1),
            Map.of("objeto", "tintura", "prob", 0.15, "min", 1, "max", 2),
            Map.of("objeto", "cristal", "prob", 0.04, "min", 1, "max", 1),
            Map.of("objeto", "frasco-1", "prob", 0.04, "min", 1, "max", 1),
            Map.of("objeto", "llave-umbral", "prob", 0.05, "min", 1, "max", 1),
            Map.of("objeto", "material:BREAD", "prob", 0.40, "min", 1, "max", 3),
            Map.of("objeto", "material:ARROW", "prob", 0.30, "min", 4, "max", 10));

    /** Lo de serie del botin de la Boveda de Ruinas (hardcore.ruinas.bovedas.botin). */
    static final List<Map<?, ?>> BOVEDA_DE_SERIE = List.of(
            Map.of("objeto", "esencia", "prob", 1.0, "min", 2, "max", 4),
            Map.of("objeto", "reliquia-2", "prob", 0.60, "min", 1, "max", 2),
            Map.of("objeto", "reliquia-3", "prob", 0.20, "min", 1, "max", 1),
            Map.of("objeto", "reliquia-4", "prob", 0.03, "min", 1, "max", 1),
            Map.of("objeto", "tintura", "prob", 0.30, "min", 1, "max", 2),
            Map.of("objeto", "cristal", "prob", 0.15, "min", 1, "max", 1),
            Map.of("objeto", "frasco-1", "prob", 0.15, "min", 1, "max", 1),
            Map.of("objeto", "llave-ominosa", "prob", 0.02, "min", 1, "max", 1));

    private final Hardcore hc;
    private final SecureRandom azar = new SecureRandom();
    private final NamespacedKey kRuina, kSellado, kVacio;
    private final File archivo;
    private YamlConfiguration registro;
    private boolean sucio;
    /** Chunks ya mirados en esta sesion, por mundo. */
    private final Map<String, Set<Long>> mirados = new HashMap<>();
    /** Los cofres puestos, por mundo y chunk: para reponer sin recorrer nada. */
    private final Map<String, Map<Long, List<int[]>>> cofres = new HashMap<>();
    private BukkitTask reloj;
    private int segundos;
    private final Map<UUID, Long> avisoTope = new HashMap<>();

    Ruinas(Hardcore hc) {
        this.hc = hc;
        this.kRuina = new NamespacedKey(hc.plugin(), "ruina");
        this.kSellado = new NamespacedKey(hc.plugin(), "ruina_sellado");
        this.kVacio = new NamespacedKey(hc.plugin(), "ruina_vacio");
        this.archivo = new File(hc.plugin().getDataFolder(), ARCHIVO);
        cargar();
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        reloj = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(), () -> hc.seguro("ruinas", this::segundo), 40L, 20L);
        Autotest.registrar("ruinas", Ruinas::autotest);
        Autotest.registrar("botin-calamity", BotinCalamity::autotest);
        Autotest.registrar("puente-bovedas", PuenteBovedas::autotest);
    }

    void parar() {
        HandlerList.unregisterAll(this);
        if (reloj != null) reloj.cancel();
        reloj = null;
        guardar(true);
        mirados.clear();
        RegionesWg.olvidar();
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = hc.cfg().getConfigurationSection("ruinas");
        return s == null ? new YamlConfiguration() : s;
    }

    private boolean cofresActivos() {
        return cfg().getBoolean("cofres.activo", true);
    }

    private boolean bovedasActivas() {
        return cfg().getBoolean("bovedas.activo", true);
    }

    // ------------------------------------------------------------------ registro en disco

    private void cargar() {
        registro = YamlConfiguration.loadConfiguration(archivo);
        cofres.clear();
        for (String mundo : registro.getKeys(false)) {
            ConfigurationSection m = registro.getConfigurationSection(mundo);
            if (m == null) continue;
            for (String id : m.getKeys(false)) {
                int[] c = xyz(m.getString(id + ".c", ""));
                if (c != null) indexar(mundo, c);
            }
        }
    }

    void guardar(boolean ya) {
        if (!sucio) return;
        try {
            registro.save(archivo);
            sucio = false;
        } catch (IOException e) {
            hc.plugin().getLogger().warning("[Calamity] No se pudo guardar " + ARCHIVO + ": " + e.getMessage());
        }
    }

    private void indexar(String mundo, int[] c) {
        cofres.computeIfAbsent(mundo, k -> new HashMap<>())
                .computeIfAbsent(clave(c[0] >> 4, c[2] >> 4), k -> new ArrayList<>()).add(c);
    }

    private void desindexar(String mundo, int[] c) {
        Map<Long, List<int[]>> m = cofres.get(mundo);
        if (m == null) return;
        List<int[]> l = m.get(clave(c[0] >> 4, c[2] >> 4));
        if (l == null) return;
        l.removeIf(o -> o[0] == c[0] && o[1] == c[1] && o[2] == c[2]);
        if (l.isEmpty()) m.remove(clave(c[0] >> 4, c[2] >> 4));
    }

    /** Ruinas apuntadas, cofres y bovedas puestos, para /calamidad vault info. */
    int[] cuentas() {
        int ruinas = 0, c = 0, b = 0;
        for (String mundo : registro.getKeys(false)) {
            ConfigurationSection m = registro.getConfigurationSection(mundo);
            if (m == null) continue;
            for (String id : m.getKeys(false)) {
                ruinas++;
                if (m.isSet(id + ".c")) c++;
                if (m.isSet(id + ".b")) b++;
            }
        }
        return new int[]{ruinas, c, b};
    }

    // ------------------------------------------------------------------ cada segundo

    private void segundo() {
        segundos++;
        boolean activo = cofresActivos() || bovedasActivas();
        long ahora = System.currentTimeMillis();
        for (World w : hc.plugin().getServer().getWorlds()) {
            if (!hc.esHardcore(w)) continue;
            for (Player p : w.getPlayers()) {
                if (!hc.cuenta(p) || hc.enSpawn(p)) continue;
                if (activo) mirarAlrededor(p);
                if (segundos % 5 == 0 && cofresActivos()) reponerCerca(p, ahora);
            }
        }
        if (segundos % 60 == 0) guardar(false);
    }

    private void mirarAlrededor(Player p) {
        ConfigurationSection c = cfg();
        int radio = Math.max(0, Math.min(8, c.getInt("radio-chunks", 5)));
        int presupuesto = Math.max(1, c.getInt("chunks-por-segundo", 4));
        World w = p.getWorld();
        Set<Long> vistos = mirados.computeIfAbsent(w.getName(), k -> new HashSet<>());
        int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
        for (int r = 0; r <= radio && presupuesto > 0; r++) {
            for (int dx = -r; dx <= r && presupuesto > 0; dx++) {
                for (int dz = -r; dz <= r && presupuesto > 0; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = cx + dx, z = cz + dz;
                    long k = clave(x, z);
                    if (vistos.contains(k) || !w.isChunkLoaded(x, z) || !vecinosCargados(w, x, z, 2)) continue;
                    presupuesto--;
                    if (mirar(w.getChunkAt(x, z))) vistos.add(k);
                }
            }
        }
    }

    private static boolean vecinosCargados(World w, int x, int z, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) if (!w.isChunkLoaded(x + dx, z + dz)) return false;
        }
        return true;
    }

    /** Mira las estructuras de un chunk cargado. False si alguna ruina se quedo a medias (otra pasada). */
    private boolean mirar(Chunk ch) {
        List<String> prefijos = cfg().isList("estructuras") ? cfg().getStringList("estructuras") : ESTRUCTURAS_DE_SERIE;
        boolean todo = true;
        for (GeneratedStructure g : ch.getStructures()) {
            String tipo;
            try {
                tipo = g.getStructure().getKey().toString();
            } catch (Throwable t) {
                continue;
            }
            if (!esRuina(tipo, prefijos)) continue;
            todo &= procesar(ch.getWorld(), tipo, g.getBoundingBox());
        }
        return todo;
    }

    /**
     * Una ruina: si ya esta apuntada, nada; si no, la decision y, si toca, el cofre y la boveda.
     * False si hace falta otra pasada (un punto de la caja en un chunk sin cargar y aun sin sitio).
     */
    private boolean procesar(World w, String tipo, BoundingBox caja) {
        String id = id(tipo, caja);
        String base = w.getName() + "." + id;
        if (registro.isSet(base)) return true;
        ConfigurationSection c = cfg();
        long h = hash(c.getLong("semilla", 6660L), id);
        boolean quiereCofre = cofresActivos() && uniforme(h, 1) < c.getDouble("cofres.probabilidad", 0.35);
        boolean quiereBoveda = bovedasActivas() && uniforme(h, 2) < c.getDouble("bovedas.probabilidad", 0.12)
                && PuenteBovedas.modulo() != null;
        if (!quiereCofre && !quiereBoveda) return true;
        Location centro = new Location(w, caja.getCenterX(), caja.getCenterY(), caja.getCenterZ());
        if (vetado(centro)) return true;

        Block sitioCofre = null, sitioBoveda = null;
        boolean faltan = false;
        for (int[] xz : candidatos(caja, h, 28)) {
            if (!w.isChunkLoaded(xz[0] >> 4, xz[1] >> 4)) {
                faltan = true;
                continue;
            }
            Block b = suelo(w, caja, xz[0], xz[1]);
            if (b == null || vetado(b.getLocation()) || alguienEncima(b)) continue;
            if (quiereCofre && sitioCofre == null) {
                sitioCofre = b;
            } else if (quiereBoveda && sitioBoveda == null
                    && (sitioCofre == null || separados(sitioCofre, b))) {
                sitioBoveda = b;
            }
            if ((!quiereCofre || sitioCofre != null) && (!quiereBoveda || sitioBoveda != null)) break;
        }
        boolean completo = (!quiereCofre || sitioCofre != null) && (!quiereBoveda || sitioBoveda != null);
        if (!completo && faltan) return false;

        registro.set(base + ".t", System.currentTimeMillis());
        registro.set(base + ".tipo", tipo);
        if (sitioCofre != null && ponerCofre(sitioCofre, id, h)) {
            int[] p = {sitioCofre.getX(), sitioCofre.getY(), sitioCofre.getZ()};
            registro.set(base + ".c", texto(p));
            indexar(w.getName(), p);
            hc.plugin().bitacora().anotar("ruina", "cofre", w.getName(), texto(p), tipo);
        }
        if (sitioBoveda != null) {
            DungeonLootPlugin d = PuenteBovedas.modulo();
            if (d != null && d.plantar(PuenteBovedas.CAJA_RUINAS, sitioBoveda) != null) {
                int[] p = {sitioBoveda.getX(), sitioBoveda.getY(), sitioBoveda.getZ()};
                registro.set(base + ".b", texto(p));
                hc.plugin().bitacora().anotar("ruina", "boveda", w.getName(), texto(p), tipo);
            }
        }
        sucio = true;
        Telemetria te = hc.telemetria();
        if (te != null) {
            Map<String, Object> campos = new LinkedHashMap<>();
            campos.put("ruina", id);
            campos.put("cofre", registro.getString(base + ".c", ""));
            campos.put("boveda", registro.getString(base + ".b", ""));
            hc.seguro("telemetria", () -> te.suceso("ruina-colocada", null, campos));
        }
        return true;
    }

    /** Spawn o una de las regiones vetadas (calamity_exterior). */
    private boolean vetado(Location l) {
        if (hc.enSpawn(l)) return true;
        List<String> regiones = cfg().isList("excluir-regiones") ? cfg().getStringList("excluir-regiones") : EXCLUIR_DE_SERIE;
        for (String r : regiones) {
            if (RegionesWg.dentro(l.getWorld(), r, l.getBlockX(), l.getBlockY(), l.getBlockZ())) return true;
        }
        return false;
    }

    /** Un jugador de pie en ese bloque o pegado: no se le planta nada encima. */
    private static boolean alguienEncima(Block b) {
        Location c = b.getLocation().add(0.5, 0, 0.5);
        for (Player p : b.getWorld().getPlayers()) if (p.getLocation().distanceSquared(c) < 2.25) return true;
        return false;
    }

    private static boolean separados(Block a, Block b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ()) >= 3 || Math.abs(a.getY() - b.getY()) >= 2;
    }

    /**
     * El primer hueco desde abajo de la columna (x, z) dentro de la caja: suelo firme debajo (no
     * hojas, no liquido), el sitio libre (aire, o hierba/flores/nieve de un bloque) y paso encima.
     */
    private static Block suelo(World w, BoundingBox caja, int x, int z) {
        int desde = Math.max(w.getMinHeight() + 1, (int) Math.floor(caja.getMinY()));
        int hasta = Math.min(w.getMaxHeight() - 2, (int) Math.floor(caja.getMaxY()) + 1);
        for (int y = desde; y <= hasta; y++) {
            Block b = w.getBlockAt(x, y, z);
            if (!libre(b)) continue;
            Block abajo = b.getRelative(BlockFace.DOWN), arriba = b.getRelative(BlockFace.UP);
            if (!abajo.getType().isSolid() || abajo.isLiquid() || Tag.LEAVES.isTagged(abajo.getType())) continue;
            if (!arriba.isPassable() || arriba.isLiquid()) continue;
            return b;
        }
        return null;
    }

    private static boolean libre(Block b) {
        Material t = b.getType();
        if (t.isAir()) return true;
        if (b.isLiquid() || !b.isReplaceable()) return false;
        BlockData d = b.getBlockData();
        if (d instanceof Bisected) return false;
        return !(d instanceof Waterlogged wl) || !wl.isWaterlogged();
    }

    private boolean ponerCofre(Block b, String id, long h) {
        b.setType(Material.CHEST, false);
        BlockData d = b.getBlockData();
        if (d instanceof Directional dir) {
            BlockFace[] caras = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
            dir.setFacing(caras[(int) Math.floorMod(h >>> 7, 4L)]);
            b.setBlockData(dir, false);
        }
        if (!(b.getState() instanceof Chest ch)) return false;
        PersistentDataContainer pdc = ch.getPersistentDataContainer();
        pdc.set(kRuina, PersistentDataType.STRING, id);
        pdc.set(kSellado, PersistentDataType.BYTE, (byte) 1);
        pdc.set(kVacio, PersistentDataType.LONG, 0L);
        ch.update(true, false);
        return true;
    }

    // ------------------------------------------------------------------ abrir el cofre

    /** El cofre de ruina detras de una ventana (simple o la mitad de uno doble), o null. */
    private Chest nuestro(InventoryHolder h) {
        if (h instanceof Chest c) return c.getPersistentDataContainer().has(kRuina, PersistentDataType.STRING) ? c : null;
        if (h instanceof DoubleChest dc) {
            Chest a = nuestro(dc.getLeftSide(false));
            return a != null ? a : nuestro(dc.getRightSide(false));
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alAbrir(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !hc.esHardcore(p)) return;
        Chest ch = nuestro(e.getInventory().getHolder(false));
        if (ch == null) return;
        Byte sellado = ch.getPersistentDataContainer().get(kSellado, PersistentDataType.BYTE);
        if (sellado == null || sellado == 0) return;
        // Quien no cuenta (creativo, espectador) lo ve vacio y lo deja sellado, como Cofres.
        if (!hc.cuenta(p) && hc.cfg().getBoolean("cofres.solo-con-jugador", true)) return;
        String dia = hc.calendario() != null ? hc.calendario().dia() : "";
        int turno = Cofres.turno(hc.datos(), p.getUniqueId(), dia, hc.cfg().getInt("cofres.pagados-dia", 10));
        if (turno <= 0) {
            e.setCancelled(true);
            Long antes = avisoTope.get(p.getUniqueId());
            long ahora = System.currentTimeMillis();
            if (antes == null || ahora - antes > 15_000) {
                avisoTope.put(p.getUniqueId(), ahora);
                hc.cordura().destello(p, Component.text("Hoy ya abriste todos los cofres que dan botín. Este queda para otro.",
                        Paleta.TEXTO), 3);
            }
            return;
        }
        hc.marcarSucio();
        Block bloque = ch.getBlock();
        if (bloque.getState() instanceof Chest vivo) {
            vivo.getPersistentDataContainer().set(kSellado, PersistentDataType.BYTE, (byte) 0);
            vivo.getPersistentDataContainer().set(kVacio, PersistentDataType.LONG, 0L);
            vivo.update(true, false);
        }
        Contratos ct = hc.contratos();
        if (ct != null) hc.seguro("contratos", () -> ct.progreso(p, "cofre", 1));

        ConfigurationSection c = cfg();
        double bloques = hc.distancia() == null ? 0 : hc.valor("distancia", () -> hc.distancia().bloques(bloque.getLocation()), 0.0);
        int esc = BotinCalamity.escalones(bloques, c.getInt("cofres.distancia-cada", 500), c.getInt("cofres.extra.tope", 6));
        double factor = BotinCalamity.factorBioma(c.getConfigurationSection("cofres.biomas"), Minijefes.bioma(bloque.getLocation()));
        List<BotinCalamity.Tirada> t = BotinCalamity.tirar(BotinCalamity.filas(c, "cofres.botin", COFRE_DE_SERIE), esc,
                c.getDouble("cofres.extra.prob", 0.15), c.getDouble("cofres.extra.cantidad", 0.5), factor, azar::nextDouble);
        // Dosa abrio un cofre vacio (fallaron todas las tiradas): como poco, una Esencia.
        if (t.isEmpty()) t = List.of(new BotinCalamity.Tirada("esencia", 1));
        BotinCalamity.Entrega en = BotinCalamity.entregar(hc, p, t, "cofre", "ruina");
        Inventory inv = bloque.getState(false) instanceof Chest vivo ? vivo.getBlockInventory() : null;
        for (ItemStack it : en.objetos()) {
            Map<Integer, ItemStack> sobra = inv == null ? Map.of(0, it) : inv.addItem(it);
            for (ItemStack s : sobra.values()) bloque.getWorld().dropItemNaturally(bloque.getLocation().add(0.5, 1, 0.5), s);
        }
        String resumen = BotinCalamity.texto(en.resumen());
        hc.plugin().bitacora().anotar("ruina", "abre", p.getName(), "cofre " + turno, resumen, "escalon " + esc,
                bloque.getX() + " " + bloque.getY() + " " + bloque.getZ());
        Telemetria te = hc.telemetria();
        if (te != null) {
            Map<String, Object> campos = new LinkedHashMap<>();
            campos.put("objetos", en.resumen());
            campos.put("esencias", en.esencias());
            campos.put("reliquias", en.reliquias());
            campos.put("cofres_hoy", turno);
            campos.put("distancia", Math.round(bloques));
            campos.put("escalon", esc);
            hc.seguro("telemetria", () -> te.suceso("ruina-cofre", p, campos));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alCerrar(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !hc.esHardcore(p)) return;
        Chest ch = nuestro(e.getInventory().getHolder(false));
        if (ch == null) return;
        marcarSiVacio(ch.getBlock(), System.currentTimeMillis());
    }

    /** Si el cofre esta abierto (sin sellar) y vacio, apunta desde cuando. Devuelve el vacio-desde (0 = no). */
    private long marcarSiVacio(Block b, long ahora) {
        if (!(b.getState(false) instanceof Chest vivo)) return 0;
        PersistentDataContainer pdc = vivo.getPersistentDataContainer();
        Byte s = pdc.get(kSellado, PersistentDataType.BYTE);
        if (s == null || s != 0 || !vivo.getBlockInventory().isEmpty()) return 0;
        Long desde = pdc.get(kVacio, PersistentDataType.LONG);
        if (desde != null && desde > 0) return desde;
        if (b.getState() instanceof Chest foto) {
            foto.getPersistentDataContainer().set(kVacio, PersistentDataType.LONG, ahora);
            foto.update(true, false);
        }
        return ahora;
    }

    /** Cada 5 s: los cofres vacios a reponer-radio de un jugador, si ya toca, se vuelven a sellar. */
    private void reponerCerca(Player p, long ahora) {
        Map<Long, List<int[]>> m = cofres.get(p.getWorld().getName());
        if (m == null || m.isEmpty()) return;
        ConfigurationSection c = cfg();
        double radio = Math.max(4, c.getDouble("cofres.reponer-radio", 32));
        long espera = Math.max(1, c.getLong("cofres.reponer-minutos", 90)) * 60_000L;
        World w = p.getWorld();
        int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
        int rc = (int) Math.ceil(radio / 16.0);
        for (int dx = -rc; dx <= rc; dx++) {
            for (int dz = -rc; dz <= rc; dz++) {
                List<int[]> l = m.get(clave(cx + dx, cz + dz));
                if (l == null || !w.isChunkLoaded(cx + dx, cz + dz)) continue;
                for (int[] pos : new ArrayList<>(l)) {
                    if (p.getLocation().distanceSquared(new Location(w, pos[0] + 0.5, pos[1], pos[2] + 0.5)) > radio * radio) continue;
                    Block b = w.getBlockAt(pos[0], pos[1], pos[2]);
                    if (!(b.getState(false) instanceof Chest vivo)
                            || !vivo.getPersistentDataContainer().has(kRuina, PersistentDataType.STRING)) {
                        // Ya no esta (lo quito el staff, o una explosion que no vimos): fuera del indice.
                        desindexar(w.getName(), pos);
                        continue;
                    }
                    long desde = marcarSiVacio(b, ahora);
                    if (desde <= 0 || ahora - desde < espera) continue;
                    if (b.getState() instanceof Chest foto) {
                        foto.getPersistentDataContainer().set(kSellado, PersistentDataType.BYTE, (byte) 1);
                        foto.getPersistentDataContainer().set(kVacio, PersistentDataType.LONG, 0L);
                        foto.update(true, false);
                        hc.plugin().bitacora().anotar("ruina", "repone", w.getName(), texto(pos), p.getName());
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ protegerlos

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alRomper(BlockBreakEvent e) {
        if (e.getBlock().getType() != Material.CHEST || !hc.esHardcore(e.getBlock().getWorld())) return;
        if (!(e.getBlock().getState(false) instanceof Chest ch) || !ch.getPersistentDataContainer().has(kRuina, PersistentDataType.STRING)) return;
        if (e.getPlayer().hasPermission("ederus.mundos")) {
            desindexar(e.getBlock().getWorld().getName(), new int[]{e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ()});
            return;
        }
        e.setCancelled(true);
        hc.cordura().destello(e.getPlayer(), Component.text("Este cofre es parte de la ruina: no se puede romper.", Paleta.TEXTO), 2);
    }

    @EventHandler(ignoreCancelled = true)
    public void alReventar(EntityExplodeEvent e) {
        if (hc.esHardcore(e.getLocation().getWorld())) e.blockList().removeIf(this::esCofreDeRuina);
    }

    @EventHandler(ignoreCancelled = true)
    public void alReventarBloque(BlockExplodeEvent e) {
        if (hc.esHardcore(e.getBlock().getWorld())) e.blockList().removeIf(this::esCofreDeRuina);
    }

    private boolean esCofreDeRuina(Block b) {
        return b.getType() == Material.CHEST && b.getState(false) instanceof Chest ch
                && ch.getPersistentDataContainer().has(kRuina, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------ la Boveda de Ruinas

    /** Su botin: lo llama BovedaCaida, que escucha los eventos de EDM de las dos cajas. */
    void alAbrirBoveda(BovedaAbiertaEvent e) {
        Player p = e.jugador();
        ConfigurationSection c = cfg();
        Location l = e.bloque().getLocation();
        double bloques = hc.distancia() == null ? 0 : hc.valor("distancia", () -> hc.distancia().bloques(l), 0.0);
        int esc = BotinCalamity.escalones(bloques, c.getInt("bovedas.distancia-cada", 500), c.getInt("bovedas.extra.tope", 6));
        double factor = BotinCalamity.factorBioma(c.getConfigurationSection("bovedas.biomas"), Minijefes.bioma(l));
        List<BotinCalamity.Tirada> t = BotinCalamity.tirar(BotinCalamity.filas(c, "bovedas.botin", BOVEDA_DE_SERIE), esc,
                c.getDouble("bovedas.extra.prob", 0.10), c.getDouble("bovedas.extra.cantidad", 0.5), factor, azar::nextDouble);
        BotinCalamity.Entrega en = BotinCalamity.entregar(hc, p, t, "boveda", "boveda-ruinas");
        e.premio().addAll(en.objetos());
        hc.guardarYa();
        hc.plugin().bitacora().anotar("ruina", "boveda-abre", p.getName(), BotinCalamity.texto(en.resumen()), "escalon " + esc,
                l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
        Telemetria te = hc.telemetria();
        if (te != null) {
            Map<String, Object> campos = new LinkedHashMap<>();
            campos.put("objetos", en.resumen());
            campos.put("esencias", en.esencias());
            campos.put("reliquias", en.reliquias());
            campos.put("distancia", Math.round(bloques));
            campos.put("escalon", esc);
            hc.seguro("telemetria", () -> te.suceso("ruina-boveda", p, campos));
        }
    }

    // ------------------------------------------------------------------ puro (autotest)

    static long clave(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    static boolean esRuina(String tipo, List<String> prefijos) {
        if (tipo == null) return false;
        String t = tipo.toLowerCase(Locale.ROOT);
        for (String p : prefijos) if (p != null && !p.isBlank() && t.startsWith(p.trim().toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** "lethal_world:ruinas_piedra@12,64,-30": el tipo y la esquina de la caja; sin puntos (rutas de YAML). */
    static String id(String tipo, BoundingBox caja) {
        return (tipo + "@" + (int) Math.floor(caja.getMinX()) + "," + (int) Math.floor(caja.getMinY()) + ","
                + (int) Math.floor(caja.getMinZ())).replace('.', '_');
    }

    /** Un hash estable (no el de String.hashCode, que no mezcla bien) de la semilla y el id. */
    static long hash(long semilla, String id) {
        long h = semilla ^ 0x9E3779B97F4A7C15L;
        for (int i = 0; i < id.length(); i++) {
            h ^= id.charAt(i);
            h *= 0x100000001B3L;
            h = Long.rotateLeft(h, 13);
        }
        return mezclar(h);
    }

    private static long mezclar(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Un numero en [0, 1) del hash, distinto por "canal" (1 = cofre, 2 = boveda). */
    static double uniforme(long h, int canal) {
        long z = mezclar(h + canal * 0x9E3779B97F4A7C15L);
        return (z >>> 11) * 0x1.0p-53;
    }

    /** n puntos {x, z} de la caja (sin el borde), en un orden que sale del hash; primero los del centro. */
    static List<int[]> candidatos(BoundingBox caja, long h, int n) {
        int x0 = (int) Math.floor(caja.getMinX()) + 1, x1 = (int) Math.floor(caja.getMaxX()) - 1;
        int z0 = (int) Math.floor(caja.getMinZ()) + 1, z1 = (int) Math.floor(caja.getMaxZ()) - 1;
        if (x1 < x0) x1 = x0;
        if (z1 < z0) z1 = z0;
        Random r = new Random(h);
        List<int[]> out = new ArrayList<>();
        int cx = (x0 + x1) / 2, cz = (z0 + z1) / 2;
        out.add(new int[]{cx, cz});
        for (int i = 1; i < n; i++) {
            // La mitad de los intentos, en el tercio central (dentro de la ruina); el resto, en toda la caja.
            boolean centro = i % 2 == 1;
            int ax = centro ? cx - (x1 - x0) / 6 : x0, bx = centro ? cx + (x1 - x0) / 6 : x1;
            int az = centro ? cz - (z1 - z0) / 6 : z0, bz = centro ? cz + (z1 - z0) / 6 : z1;
            out.add(new int[]{ax + r.nextInt(Math.max(1, bx - ax + 1)), az + r.nextInt(Math.max(1, bz - az + 1))});
        }
        return out;
    }

    static String texto(int[] p) {
        return p[0] + "," + p[1] + "," + p[2];
    }

    static int[] xyz(String s) {
        if (s == null || s.isBlank()) return null;
        String[] t = s.split(",");
        if (t.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(t[0].trim()), Integer.parseInt(t[1].trim()), Integer.parseInt(t[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        h.ok("ruina de Lethal World", esRuina("lethal_world:ruinas_piedra", ESTRUCTURAS_DE_SERIE));
        h.ok("una aldea no es ruina", !esRuina("minecraft:village_plains", ESTRUCTURAS_DE_SERIE));
        h.ok("sin mirar mayusculas", esRuina("Lethal_World:Ruinas_Pizarra", ESTRUCTURAS_DE_SERIE));
        BoundingBox caja = new BoundingBox(100, 60, -40, 124, 75, -18);
        String id = id("lethal_world:ruinas_piedra", caja);
        h.igual("id de la ruina", "lethal_world:ruinas_piedra@100,60,-40", id);
        h.ok("id sin puntos", !id("bracken:a.b", caja).contains("."));
        long a = hash(6660, id), b = hash(6660, id);
        h.igual("la decision es la misma cada vez", a, b);
        h.ok("otra semilla, otra decision", hash(6661, id) != a);
        h.ok("otra ruina, otra decision", hash(6660, id("lethal_world:ruinas_piedra", new BoundingBox(101, 60, -40, 125, 75, -18))) != a);
        boolean rango = true;
        int cofres = 0;
        for (int i = 0; i < 4000; i++) {
            long hi = hash(6660, "r" + i);
            double u = uniforme(hi, 1);
            rango &= u >= 0 && u < 1;
            if (u < 0.35) cofres++;
        }
        h.ok("uniforme en [0, 1)", rango);
        h.ok("con 0,35 salen cofres en ~35 % de 4.000 ruinas (" + cofres + ")", cofres > 1250 && cofres < 1550);
        h.ok("los canales de cofre y boveda no son el mismo numero", uniforme(a, 1) != uniforme(a, 2));
        List<int[]> cs = candidatos(caja, a, 28);
        boolean dentro = true;
        for (int[] c : cs) dentro &= c[0] > 100 && c[0] < 124 && c[1] > -40 && c[1] < -18;
        h.igual("28 candidatos", 28, cs.size());
        h.ok("todos dentro de la caja y sin el borde", dentro);
        h.igual("el primero, el centro", "112,-29", cs.get(0)[0] + "," + cs.get(0)[1]);
        h.igual("los mismos candidatos cada vez", texto(new int[]{cs.get(5)[0], 0, cs.get(5)[1]}),
                texto(new int[]{candidatos(caja, a, 28).get(5)[0], 0, candidatos(caja, a, 28).get(5)[1]}));
        h.igual("coordenadas ida y vuelta", "12,-3,40", texto(xyz("12,-3,40")));
        h.igual("coordenadas rotas", null, xyz("12,x,40"));
        h.ok("clave de chunk distinta", clave(1, 2) != clave(2, 1) && clave(-1, 0) != clave(0, -1));
        h.igual("filas de serie del cofre", 10, COFRE_DE_SERIE.size());
        return h.lineas();
    }
}
