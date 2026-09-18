package net.ederus.edm.mundos;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import net.ederus.edm.Module;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.core.Glow;
import net.ederus.edm.anomaly.minions.MinionAbility;
import net.ederus.edm.anomaly.minions.MinionCategory;
import net.ederus.edm.anomaly.minions.MinionManager;
import net.ederus.edm.anomaly.minions.MinionPresence;
import net.ederus.edm.anomaly.minions.MinionRegistry;
import net.ederus.edm.anomaly.minions.MinionType;
import net.ederus.edm.comun.Compat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Los mobs de Lethal World. En estos mundos no hay hostil sin nivel:
 *
 *  - Los que aparecen alrededor de cada jugador son tipos de esbirro (los de /esb, en su
 *    carpeta) segun el BIOMA, uno cada pocos segundos hasta el tope. El spawn natural de
 *    hostiles vanilla se cancela: si se sustituyera uno a uno, vanilla intenta tantos por
 *    tick que el tope no llega a frenarlos y salen en manada.
 *  - Los que ya traen las estructuras y los que salen de spawners se ADOPTAN: nivel, cartel
 *    y dano por nivel, sin cambiar la entidad y con su nombre. Los que se llaman como un
 *    minijefe reciben nivel extra y mucha vida.
 *  - Las estructuras que venian vacias tienen guarnicion: al acercarse un jugador aparecen
 *    unos cuantos del tipo que se les asigne, y vuelven un rato despues de caer.
 *  - Nivel = rango de rankup x A + poder de AuraSkills / B, del jugador mas cercano.
 *  - MobCoins: lo que ese mob paga en el Survival (tabla de UltimateMobCoins) x (1 + nivel / C)
 *    x multiplicador del mundo, y mas si es destacado o minijefe. Las paga EDM.
 *
 * Los tipos se siembran UNA vez en esbirros.yml (si no existen) y a partir de ahi se editan
 * desde /esb como los demas. Las tablas bioma -> tipos y estructura -> guarnicion viven en la
 * config de mundos.
 */
public final class MobsLethal implements Listener {

    private final MundosPlugin modulo;
    private final Random random = new Random();
    private final Set<UUID> vivos = new HashSet<>();
    private final Map<String, Double> baseMonedas = new HashMap<>();
    /** bioma -> [comun, comun, destacado?], leido de la config al arrancar. */
    private final Map<String, List<String>> tabla = new HashMap<>();
    /** estructura -> guarnicion, leido de la config al arrancar. */
    private final Map<String, Guarnicion> guarniciones = new HashMap<>();
    /** Cada estructura con guarnicion (mundo + centro) y sus mobs vivos. */
    private final Map<String, Set<UUID>> ocupadas = new HashMap<>();
    private final Map<String, Long> proximaGuarnicion = new HashMap<>();
    private final Map<UUID, String> puestoDe = new HashMap<>();
    private final NamespacedKey clave;
    private BukkitTask aparicion;
    private BukkitTask limpieza;

    private record Guarnicion(String tipo, int minimo, int maximo) {
    }

    MobsLethal(MundosPlugin modulo) {
        this.modulo = modulo;
        this.clave = new NamespacedKey(Module.dueno(modulo), "lethal_world_mob");
    }

    private AnomalyPlugin anomaly() {
        Module m = modulo.core().modulo("anomaly");
        return m instanceof AnomalyPlugin a ? a : null;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = modulo.getConfig().getConfigurationSection("mobs");
        return s == null ? new YamlConfiguration() : s;
    }

    void arrancar() {
        AnomalyPlugin a = anomaly();
        if (a == null || a.minions() == null || a.minionManager() == null) {
            modulo.getLogger().warning("[Lethal World] El módulo anomaly (esbirros) no está activo: sin mobs de Lethal World.");
            return;
        }
        sembrar(a.minions());
        cargarTabla();
        cargarGuarniciones();
        cargarMonedas();
        a.minionManager().heredable(clave);
        if (!cfg().getBoolean("activos", true)) {
            modulo.getLogger().info("[Lethal World] Mobs de Lethal World apagados en la config.");
            return;
        }
        modulo.getServer().getPluginManager().registerEvents(this, Module.dueno(modulo));
        long cada = Math.max(10, cfg().getLong("cada-ticks", 40));
        aparicion = modulo.getServer().getScheduler().runTaskTimer(Module.dueno(modulo), this::ciclo, cada, cada);
        limpieza = modulo.getServer().getScheduler().runTaskTimer(Module.dueno(modulo), this::retirarLejanos, 100L, 100L);
    }

    void parar() {
        if (aparicion != null) aparicion.cancel();
        if (limpieza != null) limpieza.cancel();
        for (UUID id : vivos) {
            Entity e = modulo.getServer().getEntity(id);
            if (e != null) e.remove();
        }
        vivos.clear();
        ocupadas.clear();
        puestoDe.clear();
    }

    // ------------------------------------------------------------------ aparicion

    private void ciclo() {
        AnomalyPlugin a = anomaly();
        if (a == null) return;
        MinionManager mm = a.minionManager();
        int tope = cfg().getInt("tope-por-jugador", 6);
        double radioConteo = cfg().getDouble("radio-conteo", 48);
        double radioAdopcion = cfg().getDouble("radio-adopcion", 40);
        int min = cfg().getInt("distancia-minima", 20), max = cfg().getInt("distancia-maxima", 40);

        for (World w : modulo.getServer().getWorlds()) {
            if (!MundosPlugin.esMundo(w)) continue;
            for (Player p : w.getPlayers()) {
                if (!cuenta(p)) continue;
                adoptarCerca(mm, p, radioAdopcion);
                guarnecer(p);
                int topeDelJugador = tope + (modulo.hardcore() == null ? 0 : modulo.hardcore().bonusTope(p));
                if (cerca(p, radioConteo) >= topeDelJugador) continue;
                Location sitio = sitio(p, min, max);
                if (sitio != null) invocar(p, sitio);
            }
        }
    }

    /** Un mob del bioma de ese sitio, con el nivel del jugador. Null si el bioma no tiene tabla. */
    private LivingEntity invocar(Player p, Location sitio) {
        List<String> candidatos = tabla.get(sitio.getBlock().getBiome().getKey().asString());
        if (candidatos == null) return null;
        boolean destacado = candidatos.size() > 2 && random.nextDouble() < cfg().getDouble("probabilidad-destacado", 0.05);
        String id = destacado ? candidatos.get(2) : candidatos.get(random.nextInt(Math.min(2, candidatos.size())));
        return invocarTipo(p, id, destacado, sitio);
    }

    private LivingEntity invocarTipo(Player p, String id, boolean destacado, Location sitio) {
        AnomalyPlugin a = anomaly();
        if (a == null) return null;
        MinionType tipo = a.minions().type(id);
        if (tipo == null) return null;
        LivingEntity mob = a.minionManager().spawnAt(tipo, nivelPara(p, destacado), sitio, null);
        if (mob == null) return null;
        mob.getPersistentDataContainer().set(clave, PersistentDataType.STRING, destacado ? "destacado" : "comun");
        sinQuemarse(mob);
        vivos.add(mob.getUniqueId());
        return mob;
    }

    /**
     * Un minijefe de verdad: el tipo que se diga, con la vida y el dano multiplicados.
     *
     * Lo llaman las reglas hardcore cuando a alguien se le acaba la cordura. No es un
     * esbirro grande: con los multiplicadores de serie aguanta como un jefe pequeno y
     * pega como para matar de dos golpes, que es justo lo que se busca.
     */
    public LivingEntity invocarMinijefe(Player p, String id, double distancia,
                                        double multiplicadorVida, double multiplicadorDano) {
        Location sitio = sitio(p, (int) Math.max(8, distancia - 8), (int) Math.max(12, distancia));
        if (sitio == null) sitio = p.getLocation().add(
                (random.nextDouble() - 0.5) * distancia, 0, (random.nextDouble() - 0.5) * distancia);

        LivingEntity mob = invocarTipo(p, id, true, sitio);
        if (mob == null) return null;

        double vida = Compat.getAttribute(mob, "max_health", 20) * Math.max(1, multiplicadorVida);
        Compat.setAttribute(mob, "max_health", Math.min(1024, vida));
        mob.setHealth(Math.min(1024, vida));
        Compat.setAttribute(mob, "attack_damage",
                Compat.getAttribute(mob, "attack_damage", 3) * Math.max(1, multiplicadorDano));
        mob.getPersistentDataContainer().set(clave, PersistentDataType.STRING, "minijefe");
        double escala = escalaDe(id);
        if (escala > 1) Compat.setAttribute(mob, "scale", Math.min(2.0, escala));

        Component nombre = mob.customName();
        if (nombre != null) mob.customName(nombre.color(NamedTextColor.DARK_RED));
        AnomalyPlugin a = anomaly();
        if (a != null) a.minionManager().reescoltar(mob);
        return mob;
    }

    /** La escala con la que se planta cada minijefe, por su nombre. 1 si no es de los cinco. */
    private double escalaDe(String id) {
        AnomalyPlugin a = anomaly();
        MinionType t = a == null ? null : a.minions().type(id);
        if (t == null) return 1;
        for (Minijefe m : MINIJEFES) {
            if (m.nombre().equals(t.display())) return m.escala();
        }
        return 1;
    }

    /** Una tanda de mobs del bioma alrededor de un jugador, para la oleada de entrada. */
    public void oleada(Player p, int cuantos) {
        for (int i = 0; i < cuantos; i++) {
            Location sitio = sitio(p, 12, 26);
            if (sitio != null) invocar(p, sitio);
        }
    }

    private static void sinQuemarse(LivingEntity mob) {
        if (mob instanceof Zombie z) z.setShouldBurnInDay(false);
        if (mob instanceof AbstractSkeleton s) s.setShouldBurnInDay(false);
        if (mob instanceof Phantom ph) ph.setShouldBurnInDay(false);
    }

    private int cerca(Player p, double radio) {
        int n = 0;
        double r2 = radio * radio;
        for (UUID id : vivos) {
            Entity e = modulo.getServer().getEntity(id);
            if (e != null && e.getWorld().equals(p.getWorld()) && e.getLocation().distanceSquared(p.getLocation()) <= r2) n++;
        }
        return n;
    }

    /** Un punto de suelo firme, fuera del agua, a distancia del jugador y en un chunk ya cargado. */
    private Location sitio(Player p, int min, int max) {
        World w = p.getWorld();
        for (int intento = 0; intento < 4; intento++) {
            double ang = random.nextDouble() * Math.PI * 2;
            double d = min + random.nextDouble() * Math.max(1, max - min);
            int x = p.getLocation().getBlockX() + (int) Math.round(Math.cos(ang) * d);
            int z = p.getLocation().getBlockZ() + (int) Math.round(Math.sin(ang) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block suelo = w.getBlockAt(x, y, z);
            if (!suelo.getType().isSolid() || suelo.isLiquid() || suelo.getType() == Material.LAVA) continue;
            Block pies = w.getBlockAt(x, y + 1, z), cabeza = w.getBlockAt(x, y + 2, z);
            if (!pies.isPassable() || !cabeza.isPassable() || pies.isLiquid()) continue;
            if (Math.abs(y - p.getLocation().getBlockY()) > 24) continue;
            return new Location(w, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }

    private void cargarTabla() {
        tabla.clear();
        ConfigurationSection s = cfg().getConfigurationSection("biomas");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection b = s.getConfigurationSection(k);
            if (b == null || b.getString("bioma") == null) continue;
            List<String> out = new ArrayList<>(b.getStringList("comunes"));
            if (out.isEmpty()) continue;
            while (out.size() < 2) out.add(out.get(0));
            String dest = b.getString("destacado");
            if (dest != null) out.add(dest);
            tabla.put(b.getString("bioma"), out);
        }
    }

    // ------------------------------------------------------ spawn natural y adopcion

    /**
     * Hostiles vanilla en un mundo de Lethal World. Los del spawn natural (y las patrullas)
     * se cancelan donde el bioma tiene mobs propios; los de spawners, divisiones y demas se
     * adoptan. Lo que invoca EDM o un comando (CUSTOM, COMMAND) no se toca.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alAparecer(CreatureSpawnEvent e) {
        LivingEntity mob = e.getEntity();
        if (!(mob instanceof Enemy) || !MundosPlugin.esMundo(mob.getWorld())) return;
        switch (e.getSpawnReason()) {
            case CUSTOM, COMMAND, SPAWNER_EGG, DEFAULT, BUCKET -> {
                return;
            }
            case NATURAL, PATROL -> {
                Location donde = e.getLocation();
                if (tabla.containsKey(donde.getBlock().getBiome().getKey().asString())) {
                    e.setCancelled(true);
                    return;
                }
            }
            default -> {
            }
        }
        modulo.getServer().getScheduler().runTask(Module.dueno(modulo), () -> {
            AnomalyPlugin a = anomaly();
            Player p = masCercano(mob.getLocation(), 128);
            if (a != null && p != null && mob.isValid()) adoptar(a.minionManager(), mob, p);
        });
    }

    /**
     * Si un jugador cuenta para los mobs: los suyos aparecen, los de las estructuras se
     * adoptan con su nivel y el tope se mide contra el. El espectador nunca cuenta; el
     * creativo tampoco, salvo que se encienda en la config para probar volando.
     */
    private boolean cuenta(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR) return false;
        return p.getGameMode() != GameMode.CREATIVE || cfg().getBoolean("contar-creativo", false);
    }

    private Player masCercano(Location donde, double radio) {
        Player mejor = null;
        double d2 = radio * radio;
        for (Player p : donde.getWorld().getPlayers()) {
            if (!cuenta(p)) continue;
            double d = p.getLocation().distanceSquared(donde);
            if (d <= d2) {
                d2 = d;
                mejor = p;
            }
        }
        return mejor;
    }

    /** Los hostiles de alrededor sin nivel (los de las estructuras) se adoptan; a los ya adoptados se les repone el cartel. */
    private void adoptarCerca(MinionManager mm, Player p, double radio) {
        for (LivingEntity mob : p.getLocation().getNearbyLivingEntities(radio)) {
            if (!(mob instanceof Enemy) || mm.isMinion(mob) || mob.isDead()) continue;
            if (mm.adoptado(mob)) mm.reescoltar(mob);
            else adoptar(mm, mob, p);
        }
    }

    private void adoptar(MinionManager mm, LivingEntity mob, Player p) {
        if (mm.isMinion(mob) || mm.adoptado(mob) || mob.isInvulnerable()) return;
        ConfigurationSection s = cfg().getConfigurationSection("adoptados");
        if (s == null) s = new YamlConfiguration();
        Component nombre = mob.customName();
        String plano = nombre == null ? "" : PlainTextComponentSerializer.plainText().serialize(nombre);
        boolean minijefe = nombre != null && cfg().getStringList("minijefes.nombres").contains(plano);

        int nivel = nivelPara(p, false);
        double vida, dano;
        if (minijefe) {
            nivel = Math.min(cfg().getInt("nivel.maximo", 100), nivel + cfg().getInt("minijefes.extra-nivel", 10));
            vida = cfg().getDouble("minijefes.vida-base", 400) * (1 + cfg().getDouble("minijefes.vida-por-nivel", 0.10) * (nivel - 1));
            dano = cfg().getDouble("minijefes.dano-base", 1.5) * (1 + cfg().getDouble("minijefes.dano-por-nivel", 0.05) * (nivel - 1));
            nombre = nombre.color(NamedTextColor.RED);
        } else {
            double original = Compat.getAttribute(mob, "max_health", mob.getHealth());
            vida = Math.max(original, s.getDouble("vida-minima", 20)) * (1 + s.getDouble("vida-por-nivel", 0.08) * (nivel - 1));
            dano = 1 + s.getDouble("dano-por-nivel", 0.04) * (nivel - 1);
            if (nombre == null) nombre = Component.translatable(mob.getType().translationKey());
        }

        // El nombre pasa al cartel: dejarlo en el mob lo pintaria dos veces. Quitarlo haria
        // que vanilla lo pudiera despawnear, asi que se fija a mano.
        if (mob.customName() != null) {
            mob.customName(null);
            mob.setCustomNameVisible(false);
            mob.setRemoveWhenFarAway(false);
        }
        double escalaMax = cfg().getDouble("escala-maxima", 2.0);
        if (Compat.getAttribute(mob, "scale", 1.0) > escalaMax) Compat.setAttribute(mob, "scale", escalaMax);
        Compat.setAttribute(mob, "max_health", vida);
        mob.setHealth(Math.min(vida, Compat.getAttribute(mob, "max_health", vida)));
        sinQuemarse(mob);
        mob.getPersistentDataContainer().set(clave, PersistentDataType.STRING, minijefe ? "minijefe" : "estructura");
        mm.adoptar(mob, nivel, nombre, dano);
    }

    // ---------------------------------------------------------------- guarniciones

    private void cargarGuarniciones() {
        guarniciones.clear();
        ConfigurationSection s = cfg().getConfigurationSection("guarniciones");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection g = s.getConfigurationSection(k);
            if (g == null || g.getString("estructura") == null || g.getString("tipo") == null) continue;
            int min = Math.max(1, g.getInt("minimo", 2));
            guarniciones.put(g.getString("estructura"), new Guarnicion(g.getString("tipo"), min, Math.max(min, g.getInt("maximo", min))));
        }
    }

    /** Las estructuras con guarnicion que tiene cerca el jugador reciben su tropa si les toca. */
    private void guarnecer(Player p) {
        if (guarniciones.isEmpty()) return;
        double radio = cfg().getDouble("guarnicion-radio", 40);
        long ahora = System.currentTimeMillis();
        Chunk c = p.getLocation().getChunk();
        World w = p.getWorld();
        Set<String> vistos = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!w.isChunkLoaded(c.getX() + dx, c.getZ() + dz)) continue;
                for (GeneratedStructure gs : w.getChunkAt(c.getX() + dx, c.getZ() + dz).getStructures()) {
                    Guarnicion g = guarniciones.get(gs.getStructure().getKey().asString());
                    if (g == null) continue;
                    BoundingBox caja = gs.getBoundingBox();
                    String puesto = w.getName() + "@" + (int) caja.getCenterX() + "," + (int) caja.getCenterY() + "," + (int) caja.getCenterZ();
                    if (!vistos.add(puesto)) continue;
                    if (p.getLocation().toVector().distanceSquared(caja.getCenter()) > radio * radio + caja.getWidthX() * caja.getWidthZ()) continue;
                    Set<UUID> tropa = ocupadas.computeIfAbsent(puesto, k -> new HashSet<>());
                    tropa.removeIf(id -> {
                        Entity e = modulo.getServer().getEntity(id);
                        return e == null || !e.isValid();
                    });
                    if (!tropa.isEmpty() || ahora < proximaGuarnicion.getOrDefault(puesto, 0L)) continue;
                    int n = g.minimo() + random.nextInt(g.maximo() - g.minimo() + 1);
                    for (int i = 0; i < n; i++) {
                        Location sitio = sitioEn(w, caja);
                        if (sitio == null) continue;
                        LivingEntity mob = invocarTipo(p, g.tipo(), false, sitio);
                        if (mob == null) continue;
                        tropa.add(mob.getUniqueId());
                        puestoDe.put(mob.getUniqueId(), puesto);
                    }
                }
            }
        }
    }

    /** Suelo con dos de aire encima dentro de la caja de la estructura. */
    private Location sitioEn(World w, BoundingBox caja) {
        for (int intento = 0; intento < 12; intento++) {
            int x = (int) Math.floor(caja.getMinX() + 1 + random.nextDouble() * Math.max(1, caja.getWidthX() - 2));
            int z = (int) Math.floor(caja.getMinZ() + 1 + random.nextDouble() * Math.max(1, caja.getWidthZ() - 2));
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int techo = Math.min((int) caja.getMaxY(), w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES));
            for (int y = techo; y >= (int) caja.getMinY(); y--) {
                Block suelo = w.getBlockAt(x, y, z);
                if (!suelo.getType().isSolid()) continue;
                Block pies = w.getBlockAt(x, y + 1, z), cabeza = w.getBlockAt(x, y + 2, z);
                if (pies.isPassable() && cabeza.isPassable() && !pies.isLiquid() && !cabeza.isLiquid()) {
                    return new Location(w, x + 0.5, y + 1, z + 0.5);
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------- nivel

    int nivelPara(Player p, boolean destacado) {
        ConfigurationSection n = cfg().getConfigurationSection("nivel");
        if (n == null) n = new YamlConfiguration();
        double base = rango(p) * n.getDouble("por-rango", 2.0)
                + poder(p) / Math.max(1.0, n.getDouble("poder-por-nivel", 20.0));
        /* Y el equipo que lleva puesto, via Poder: sin esto un jugador de rango bajo
         * con el mejor set del servidor se paseaba por mobs de nivel 17. */
        double porPoder = n.getDouble("poder-total-por-nivel", 0);
        if (porPoder > 0) {
            base += net.ederus.edm.comun.Poder
                    .calcular(modulo, p, rango(p), poder(p)).total() / porPoder;
        }
        double variacion = n.getDouble("variacion", 0.10);
        base *= 1 + (random.nextDouble() * 2 - 1) * variacion;
        if (destacado) base += n.getInt("extra-destacado", 5);
        // En los mundos hardcore la cordura y los minutos dentro suben el nivel.
        if (modulo.hardcore() != null) base += modulo.hardcore().bonusNivel(p);
        return (int) Math.max(1, Math.min(n.getInt("maximo", 100), Math.round(base)));
    }

    /** Lo que responde PlaceholderAPI al marcador del rango, tal cual. "" si no se puede leer. */
    String rangoCrudo(Player p) {
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Object r = papi.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class)
                    .invoke(null, p, cfg().getString("nivel.placeholder-rango", "%notranks_rank_number%"));
            return String.valueOf(r);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Rango de rankup (fork de NotRanks) por PlaceholderAPI. 0 si no se puede leer. */
    public int rango(Player p) {
        try {
            return Integer.parseInt(rangoCrudo(p).replaceAll("[^0-9]", ""));
        } catch (NumberFormatException t) {
            return 0;
        }
    }

    /** Poder de AuraSkills (suma de habilidades) por su API. 0 si no esta. */
    public int poder(Player p) {
        try {
            Class<?> api = Class.forName("dev.aurelium.auraskills.api.AuraSkillsApi");
            Object inst = api.getMethod("get").invoke(null);
            Object user = inst.getClass().getMethod("getUser", UUID.class).invoke(inst, p.getUniqueId());
            if (user == null) return 0;
            return (int) user.getClass().getMethod("getPowerLevel").invoke(user);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------- mobcoins

    /** Valor esperado por muerte de cada mob en la tabla de UltimateMobCoins del Survival. */
    private void cargarMonedas() {
        baseMonedas.clear();
        File f = new File(modulo.getServer().getPluginsFolder(), "UltimateMobCoins/mobcoins.yml");
        if (!f.isFile()) {
            modulo.getLogger().warning("[Lethal World] No encuentro UltimateMobCoins/mobcoins.yml: los mobs pagan la base mínima.");
            return;
        }
        ConfigurationSection s = YamlConfiguration.loadConfiguration(f).getConfigurationSection("mobCoinDrops");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            double chance = s.getDouble(k + ".chance", 0) / 100.0;
            String cant = s.getString(k + ".amount", "0");
            double media;
            try {
                String[] p = cant.split("-");
                media = p.length == 2 ? (Double.parseDouble(p[0].trim()) + Double.parseDouble(p[1].trim())) / 2 : Double.parseDouble(cant.trim());
            } catch (NumberFormatException e) {
                media = 0;
            }
            baseMonedas.put(k.toLowerCase(Locale.ROOT), chance * media);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alMorir(EntityDeathEvent e) {
        LivingEntity mob = e.getEntity();
        String clase = mob.getPersistentDataContainer().get(clave, PersistentDataType.STRING);
        if (clase == null) return;
        vivos.remove(mob.getUniqueId());
        String puesto = puestoDe.remove(mob.getUniqueId());
        if (puesto != null) {
            proximaGuarnicion.put(puesto, System.currentTimeMillis() + cfg().getLong("guarnicion-reaparece-minutos", 20) * 60_000L);
        }
        // Los minijefes de antes de quitar el contorno aun lo llevan en el marcador.
        if ("minijefe".equals(clase)) Glow.clear(mob);
        Player asesino = mob.getKiller();
        if (asesino == null) return;
        AnomalyPlugin a = anomaly();
        int nivel = a == null ? 1 : Math.max(1, a.minionManager().levelOf(mob));
        ConfigurationSection m = cfg().getConfigurationSection("mobcoins");
        if (m == null) m = new YamlConfiguration();
        double base = Math.max(m.getDouble("base-minima", 0.5),
                baseMonedas.getOrDefault(mob.getType().getKey().getKey(), 0.0));
        double extra = switch (clase) {
            case "destacado" -> m.getDouble("multiplicador-destacado", 3.0);
            case "minijefe" -> m.getDouble("multiplicador-minijefe", 10.0);
            default -> 1.0;
        };
        double monedas = base * (1 + nivel / Math.max(1.0, m.getDouble("nivel-divisor", 20)))
                * m.getDouble("multiplicador-mundo", 1.5) * extra;
        long pago = Math.round(monedas);
        net.ederus.edm.comun.MobCoins.pagar(modulo, asesino, pago);
    }

    private void retirarLejanos() {
        double r = cfg().getDouble("retirar-a", 96);
        double r2 = r * r;
        for (var it = vivos.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            Entity e = modulo.getServer().getEntity(id);
            if (e == null || !e.isValid()) {
                it.remove();
                puestoDe.remove(id);
                continue;
            }
            boolean alguien = false;
            for (Player p : e.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(e.getLocation()) <= r2) {
                    alguien = true;
                    break;
                }
            }
            if (!alguien) {
                e.remove();
                it.remove();
                puestoDe.remove(id);
            }
        }
    }

    // ---------------------------------------------------------------------- siembra

    private record Semilla(String nombre, EntityType tipo, boolean destacado, int color, MinionAbility... habilidades) {
    }

    private record Bioma(String id, Semilla comun1, Semilla comun2, Semilla destacado) {
    }

    private static Semilla c(String n, EntityType t, int color, MinionAbility... h) {
        return new Semilla(n, t, false, color, h);
    }

    private static Semilla d(String n, EntityType t, int color, MinionAbility... h) {
        return new Semilla(n, t, true, color, h);
    }

    /**
     * Los cinco minijefes de Calamity.
     *
     * No son esbirros grandes: son la recompensa del mundo hardcore, lo que viene a
     * buscarte cuando se te acaba la cordura. La vida y el dano de verdad se los pone
     * el multiplicador de la config (hardcore.minijefes), no estos numeros: aqui solo
     * se define QUE son y como se ven, para que Dosa pueda tocarlos desde /esb.
     */
    private record Minijefe(String nombre, EntityType tipo, int color, double escala,
                            MinionAbility... habilidades) {
        /* La escala se aplica al invocarlo (Compat "scale"), no en la presencia:
         * el tamano es cosa de la entidad concreta, no del tipo guardado. */
    }

    private static final List<Minijefe> MINIJEFES = List.of(
            new Minijefe("Custodio de las Ruinas", EntityType.IRON_GOLEM, 0x9BA7A0, 1.4,
                    MinionAbility.ACORAZADO, MinionAbility.ALARMA, MinionAbility.ESPINAS),
            new Minijefe("Matriarca Tejedora", EntityType.CAVE_SPIDER, 0x6B4A6B, 1.8,
                    MinionAbility.VENENOSO, MinionAbility.DIVISION, MinionAbility.AGIL),
            new Minijefe("Heraldo Carmesí", EntityType.RAVAGER, 0xC23B3B, 1.3,
                    MinionAbility.BERSERK, MinionAbility.IGNEO),
            new Minijefe("Sanador del Fango", EntityType.EVOKER, 0x6E8C5A, 1.2,
                    MinionAbility.CURANDERO, MinionAbility.ALARMA, MinionAbility.FLECHA_HELADA),
            new Minijefe("Centinela de Toba", EntityType.WITHER_SKELETON, 0x7A7268, 1.5,
                    MinionAbility.FLECHA_PESADA, MinionAbility.ACORAZADO, MinionAbility.BERSERK));

    private static final List<Bioma> PANACEA = List.of(
            new Bioma("honeybee_biome", c("Apicultor Picado", EntityType.ZOMBIE, 0xE8B923, MinionAbility.VENENOSO),
                    c("Abejorro Asesino", EntityType.VEX, 0xF2C200, MinionAbility.AGIL),
                    d("Reina de la Colmena", EntityType.WITCH, 0xFFB000, MinionAbility.CURANDERO)),
            new Bioma("horsetail_tropics", c("Cazador Tropical", EntityType.SKELETON, 0x5E8C31, MinionAbility.FLECHA_PESADA),
                    c("Araña de Helecho", EntityType.SPIDER, 0x4F7942, MinionAbility.VENENOSO),
                    d("Tótem Selvático", EntityType.VINDICATOR, 0x8B5A2B, MinionAbility.BERSERK)),
            new Bioma("quicksand_springs", c("Ahogado de Arena", EntityType.HUSK, 0xC2A36B, MinionAbility.ACORAZADO),
                    c("Escorpión del Manantial", EntityType.CAVE_SPIDER, 0xB08D57, MinionAbility.AGIL),
                    d("Jabalí Hundido", EntityType.HOGLIN, 0x9C6B3C, MinionAbility.BERSERK)),
            new Bioma("condemned_taiga", c("Leñador Condenado", EntityType.VINDICATOR, 0x4B3621),
                    c("Espectro de Pino", EntityType.STRAY, 0x7FA3A8, MinionAbility.FLECHA_HELADA),
                    d("Profeta Condenado", EntityType.EVOKER, 0x3B4A3F)),
            new Bioma("polypore_plains", c("Hongo Andante", EntityType.SLIME, 0xA0522D, MinionAbility.DIVISION),
                    c("Recolector Micótico", EntityType.WITCH, 0x8E6E53, MinionAbility.VENENOSO),
                    d("Guardián de Esporas", EntityType.PIGLIN_BRUTE, 0x6D4C41, MinionAbility.ACORAZADO)),
            new Bioma("wildflower_bog", c("Espantapájaros", EntityType.SKELETON, 0xC9A66B, MinionAbility.FLECHA_PESADA),
                    c("Sanguijuela", EntityType.SILVERFISH, 0x556B2F, MinionAbility.AGIL),
                    d("Bruja de la Ciénaga", EntityType.WITCH, 0x7B9F35, MinionAbility.CURANDERO)),
            new Bioma("ravenous_greenwood", c("Devorador", EntityType.ZOMBIE, 0x2E5E1E, MinionAbility.BERSERK),
                    c("Acechador Verde", EntityType.SPIDER, 0x3C8D2F, MinionAbility.AGIL),
                    d("Raíz Voraz", EntityType.RAVAGER, 0x3E2B1D, MinionAbility.BERSERK)),
            new Bioma("sweltering_swamp", c("Piglin del Fango", EntityType.PIGLIN, 0x6B5B3E),
                    c("Creeper Sofocante", EntityType.CREEPER, 0x8FBC5A, MinionAbility.IGNEO),
                    d("Bruto del Pantano", EntityType.PIGLIN_BRUTE, 0x5C4A2E, MinionAbility.ACORAZADO)),
            new Bioma("creeper_dominion", c("Creeper Dominado", EntityType.CREEPER, 0x5DAA3C, MinionAbility.AGIL),
                    c("Lodo Explosivo", EntityType.SLIME, 0x6BBF3A, MinionAbility.DIVISION),
                    d("Creeper Soberano", EntityType.CREEPER, 0x2F7D32, MinionAbility.ACORAZADO, MinionAbility.ALARMA)),
            new Bioma("crimson_organism", c("Carne Carmesí", EntityType.ZOGLIN, 0xB3202A, MinionAbility.BERSERK),
                    c("Espora Roja", EntityType.MAGMA_CUBE, 0xD7263D, MinionAbility.DIVISION),
                    d("Corazón Carmesí", EntityType.RAVAGER, 0x8B0000, MinionAbility.IGNEO, MinionAbility.CURANDERO)),
            new Bioma("bamboo_valley", c("Guerrero de Bambú", EntityType.VINDICATOR, 0x7BA05B, MinionAbility.AGIL),
                    c("Arquero de Bambú", EntityType.PILLAGER, 0x9CB86F, MinionAbility.FLECHA_PESADA),
                    d("Maestro del Valle", EntityType.VINDICATOR, 0x4E7A2A, MinionAbility.BERSERK, MinionAbility.ACORAZADO)),
            new Bioma("hungering_jungle", c("Caníbal de la Jungla", EntityType.ZOMBIE, 0x6B3E26, MinionAbility.BERSERK),
                    c("Tejedora", EntityType.SPIDER, 0x4A2F1F, MinionAbility.VENENOSO),
                    d("Ídolo Hambriento", EntityType.RAVAGER, 0x5B3A1E, MinionAbility.BERSERK)),
            new Bioma("conure_conclave", c("Cotorra Fantasma", EntityType.PHANTOM, 0x3FBF7F, MinionAbility.AGIL),
                    c("Chamán del Cónclave", EntityType.WITCH, 0x2E8B57),
                    d("Gran Guacamayo", EntityType.PHANTOM, 0xFF4500, MinionAbility.ALARMA)));

    /** Estructuras vacias de Panacea y la tropa que las guarda. */
    private record Puesto(String estructura, String tipo, int minimo, int maximo) {
    }

    private static final List<Puesto> GUARNICIONES = List.of(
            new Puesto("bracken:dweller_drill", "Leñador Condenado", 3, 4),
            new Puesto("bracken:outlander_tent", "Espantapájaros", 2, 3),
            new Puesto("bracken:panacea_hut", "Caníbal de la Jungla", 2, 3));

    /** Crea la carpeta y los tipos que falten, y las tablas si no hay. Nunca pisa lo editado. */
    private void sembrar(MinionRegistry reg) {
        String nombreCarpeta = "Lethal World · Panacea";
        MinionCategory carpeta = null;
        for (MinionCategory c : reg.categories()) {
            if (c.display().equals(nombreCarpeta)) carpeta = c;
        }
        if (carpeta == null) {
            carpeta = reg.createCategory(nombreCarpeta);
            carpeta.icon(Material.MOSS_BLOCK);
            carpeta.colorRgb(0xE0664A);
        }

        ConfigurationSection biomas = modulo.getConfig().getConfigurationSection("mobs.biomas");
        boolean escribirTabla = biomas == null || biomas.getKeys(false).isEmpty();
        boolean guardar = escribirTabla;
        int creados = 0;
        for (Bioma b : PANACEA) {
            List<String> ids = new ArrayList<>();
            for (Semilla s : List.of(b.comun1(), b.comun2(), b.destacado())) {
                MinionType t = buscar(reg, s.nombre());
                if (t == null) {
                    t = reg.createType(s.nombre(), carpeta.id());
                    configurar(t, s);
                    creados++;
                }
                ids.add(t.id());
            }
            if (escribirTabla) {
                String ruta = "mobs.biomas.panacea_" + b.id();
                modulo.getConfig().set(ruta + ".bioma", "bracken:panacea/" + b.id());
                modulo.getConfig().set(ruta + ".comunes", List.of(ids.get(0), ids.get(1)));
                modulo.getConfig().set(ruta + ".destacado", ids.get(2));
            }
        }
        guardar |= sembrarMinijefes(reg);

        ConfigurationSection gs = modulo.getConfig().getConfigurationSection("mobs.guarniciones");
        if (gs == null || gs.getKeys(false).isEmpty()) {
            for (Puesto g : GUARNICIONES) {
                MinionType t = buscar(reg, g.tipo());
                if (t == null) continue;
                String ruta = "mobs.guarniciones." + g.estructura().substring(g.estructura().indexOf(':') + 1);
                modulo.getConfig().set(ruta + ".estructura", g.estructura());
                modulo.getConfig().set(ruta + ".tipo", t.id());
                modulo.getConfig().set(ruta + ".minimo", g.minimo());
                modulo.getConfig().set(ruta + ".maximo", g.maximo());
                guardar = true;
            }
        }
        // Sin contorno: con varios destacados a la vez brillaban demasiado. Se quita una sola
        // vez a los ya sembrados; si alguien se lo vuelve a poner en /esb, se respeta.
        if (!modulo.getConfig().getBoolean("mobs.migraciones.sin-contorno")) {
            boolean quitado = false;
            for (Bioma b : PANACEA) {
                MinionType t = buscar(reg, b.destacado().nombre());
                if (t != null && t.presence().outline() != null) {
                    t.presence().outline(null);
                    quitado = true;
                }
            }
            if (quitado) reg.save();
            modulo.getConfig().set("mobs.migraciones.sin-contorno", true);
            guardar = true;
        }
        if (modulo.getConfig().getStringList("mobs.minijefes.nombres").isEmpty()) {
            modulo.getConfig().set("mobs.minijefes.nombres", List.of("Creeper Gigatón", "Ventiarbusto Latente"));
            guardar = true;
        }
        if (creados > 0) reg.save();
        if (guardar) modulo.saveConfig();
        modulo.getLogger().info("[Lethal World] Mobs: " + PANACEA.size() * 3 + " tipos en /esb (" + creados + " nuevos), "
                + GUARNICIONES.size() + " estructuras con guarnición.");
    }

    private static MinionType buscar(MinionRegistry reg, String nombre) {
        for (MinionType t : reg.types()) if (t.display().equals(nombre)) return t;
        return null;
    }

    /**
     * Crea los cinco minijefes en su propia carpeta y apunta sus ids en la config.
     *
     * Los ids los genera el registro a partir del nombre, asi que no se pueden
     * escribir a mano en el config de fabrica: se escriben aqui la primera vez y a
     * partir de ahi Dosa los edita en /esb como cualquier otro.
     */
    private boolean sembrarMinijefes(MinionRegistry reg) {
        String nombreCarpeta = "Lethal World · Minijefes";
        MinionCategory carpeta = null;
        for (MinionCategory c : reg.categories()) {
            if (c.display().equals(nombreCarpeta)) carpeta = c;
        }
        if (carpeta == null) {
            carpeta = reg.createCategory(nombreCarpeta);
            carpeta.icon(Material.WITHER_SKELETON_SKULL);
            carpeta.colorRgb(0x8B1A1A);
        }

        List<String> ids = new ArrayList<>();
        for (Minijefe m : MINIJEFES) {
            MinionType t = buscar(reg, m.nombre());
            if (t == null) {
                t = reg.createType(m.nombre(), carpeta.id());
                t.entity(m.tipo());
                t.colorRgb(m.color());
                t.bold(true);
                t.baseHealth(120);
                t.healthGrowth(0.12);
                t.baseDamage(1.6);
                t.damageGrowth(0.06);
                t.mobcoins(150, 400);
                for (MinionAbility h : m.habilidades()) if (!t.has(h)) t.toggle(h);
                MinionPresence look = t.presence();
                look.featured(true);
                look.auraName("DUST");
                look.auraColor(m.color());
            }
            ids.add(t.id());
        }

        List<String> yaPuestos = modulo.getConfig().getStringList("hardcore.minijefes.tipos");
        if (yaPuestos.isEmpty() || reg.type(yaPuestos.get(0)) == null) {
            modulo.getConfig().set("hardcore.minijefes.tipos", ids);
            return true;
        }
        return false;
    }

    private static void configurar(MinionType t, Semilla s) {
        t.entity(s.tipo());
        t.colorRgb(s.color());
        for (MinionAbility h : s.habilidades()) if (!t.has(h)) t.toggle(h);
        MinionPresence look = t.presence();
        if (s.destacado()) {
            t.bold(true);
            t.baseHealth(80);
            t.healthGrowth(0.10);
            t.baseDamage(1.3);
            t.damageGrowth(0.05);
            look.featured(true);
            look.auraName("DUST");
            look.auraColor(s.color());
        } else {
            t.baseHealth(24);
            t.healthGrowth(0.08);
            t.baseDamage(1.0);
            t.damageGrowth(0.04);
        }
        boolean humanoide = switch (s.tipo()) {
            case ZOMBIE, HUSK, SKELETON, STRAY, VINDICATOR, PILLAGER, PIGLIN, PIGLIN_BRUTE, EVOKER -> true;
            default -> false;
        };
        if (humanoide) {
            look.gear().put(MinionPresence.Slot.PECHERA, Material.LEATHER_CHESTPLATE);
            look.gear().put(MinionPresence.Slot.BOTAS, Material.LEATHER_BOOTS);
            if (s.destacado()) look.gear().put(MinionPresence.Slot.CASCO, Material.LEATHER_HELMET);
            look.gearColor(s.color());
        }
    }
}
