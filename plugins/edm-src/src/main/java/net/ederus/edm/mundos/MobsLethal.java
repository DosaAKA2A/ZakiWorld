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
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import net.ederus.edm.Module;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.minions.MinionAbility;
import net.ederus.edm.anomaly.minions.MinionCategory;
import net.ederus.edm.anomaly.minions.MinionManager;
import net.ederus.edm.anomaly.minions.MinionPresence;
import net.ederus.edm.anomaly.minions.MinionRegistry;
import net.ederus.edm.anomaly.minions.MinionType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * Los mobs de Lethal World: tipos de esbirro (los mismos de /esb, en su propia carpeta) que
 * aparecen alrededor de cada jugador segun el BIOMA donde caen.
 *
 *  - Nivel = rango de rankup x A + poder de AuraSkills / B, del jugador mas cercano, con una
 *    variacion. Vida y dano escalan con el nivel como cualquier esbirro.
 *  - MobCoins: lo que ese mob paga en el Survival (tabla de UltimateMobCoins) x (1 + nivel / C)
 *    x multiplicador del mundo, y mas si es destacado. Las paga EDM al que lo mata.
 *  - Los que quedan lejos de todo jugador se retiran: no se acumulan en chunks vacios.
 *
 * Los tipos se siembran UNA vez en esbirros.yml (si no existen) y a partir de ahi se editan
 * desde /esb como los demas. La tabla bioma -> tipos vive en la config de mundos.
 */
final class MobsLethal implements Listener {

    private final MundosPlugin modulo;
    private final Random random = new Random();
    private final Set<UUID> vivos = new HashSet<>();
    private final Map<String, Double> baseMonedas = new HashMap<>();
    /** bioma -> [comun, comun, destacado?], leido de la config al arrancar. */
    private final Map<String, List<String>> tabla = new HashMap<>();
    private final NamespacedKey clave;
    private BukkitTask aparicion;
    private BukkitTask limpieza;

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
        cargarMonedas();
        modulo.getServer().getPluginManager().registerEvents(this, Module.dueno(modulo));
        if (!cfg().getBoolean("activos", true)) {
            modulo.getLogger().info("[Lethal World] Mobs de Lethal World apagados en la config.");
            return;
        }
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
    }

    // ------------------------------------------------------------------ aparicion

    private void ciclo() {
        AnomalyPlugin a = anomaly();
        if (a == null) return;
        MinionRegistry reg = a.minions();
        MinionManager mm = a.minionManager();
        int tope = cfg().getInt("tope-por-jugador", 6);
        double radioConteo = cfg().getDouble("radio-conteo", 48);
        int min = cfg().getInt("distancia-minima", 20), max = cfg().getInt("distancia-maxima", 40);

        for (World w : modulo.getServer().getWorlds()) {
            if (!MundosPlugin.esMundo(w)) continue;
            for (Player p : w.getPlayers()) {
                if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
                if (cerca(p, radioConteo) >= tope) continue;
                Location sitio = sitio(p, min, max);
                if (sitio == null) continue;
                List<String> candidatos = tiposPara(sitio.getBlock().getBiome().getKey().asString());
                if (candidatos == null) continue;
                boolean destacado = candidatos.size() > 2 && random.nextDouble() < cfg().getDouble("probabilidad-destacado", 0.05);
                String id = destacado ? candidatos.get(2) : candidatos.get(random.nextInt(Math.min(2, candidatos.size())));
                MinionType tipo = reg.type(id);
                if (tipo == null) continue;
                int nivel = nivelPara(p, destacado);
                LivingEntity mob = mm.spawnAt(tipo, nivel, sitio, null);
                if (mob == null) continue;
                mob.getPersistentDataContainer().set(clave, PersistentDataType.STRING, destacado ? "destacado" : "comun");
                if (mob instanceof Zombie z) z.setShouldBurnInDay(false);
                if (mob instanceof AbstractSkeleton s) s.setShouldBurnInDay(false);
                if (mob instanceof Phantom ph) ph.setShouldBurnInDay(false);
                vivos.add(mob.getUniqueId());
            }
        }
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

    private List<String> tiposPara(String bioma) {
        return tabla.get(bioma);
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

    // ---------------------------------------------------------------------- nivel

    int nivelPara(Player p, boolean destacado) {
        ConfigurationSection n = cfg().getConfigurationSection("nivel");
        if (n == null) n = new YamlConfiguration();
        double base = rango(p) * n.getDouble("por-rango", 2.0) + poder(p) / Math.max(1.0, n.getDouble("poder-por-nivel", 20.0));
        double variacion = n.getDouble("variacion", 0.10);
        base *= 1 + (random.nextDouble() * 2 - 1) * variacion;
        if (destacado) base += n.getInt("extra-destacado", 5);
        return (int) Math.max(1, Math.min(n.getInt("maximo", 100), Math.round(base)));
    }

    /** Rango de rankup (fork de NotRanks) por PlaceholderAPI. 0 si no se puede leer. */
    private int rango(Player p) {
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Object r = papi.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class)
                    .invoke(null, p, cfg().getString("nivel.placeholder-rango", "%notranks_rank_number%"));
            return Integer.parseInt(String.valueOf(r).replaceAll("[^0-9]", ""));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Poder de AuraSkills (suma de habilidades) por su API. 0 si no esta. */
    private int poder(Player p) {
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
        Player asesino = mob.getKiller();
        if (asesino == null) return;
        AnomalyPlugin a = anomaly();
        int nivel = a == null ? 1 : Math.max(1, a.minionManager().levelOf(mob));
        ConfigurationSection m = cfg().getConfigurationSection("mobcoins");
        if (m == null) m = new YamlConfiguration();
        double base = Math.max(m.getDouble("base-minima", 0.5),
                baseMonedas.getOrDefault(mob.getType().getKey().getKey(), 0.0));
        double monedas = base * (1 + nivel / Math.max(1.0, m.getDouble("nivel-divisor", 20)))
                * m.getDouble("multiplicador-mundo", 1.5)
                * ("destacado".equals(clase) ? m.getDouble("multiplicador-destacado", 3.0) : 1.0);
        long pago = Math.round(monedas);
        if (pago <= 0) return;
        modulo.getServer().dispatchCommand(modulo.getServer().getConsoleSender(),
                "mobcoins give " + asesino.getName() + " " + pago + " --silent");
        asesino.sendActionBar(Component.text("+" + pago + " MobCoins", TextColor.color(0xFFD35C)));
    }

    private void retirarLejanos() {
        double r = cfg().getDouble("retirar-a", 96);
        double r2 = r * r;
        for (var it = vivos.iterator(); it.hasNext(); ) {
            Entity e = modulo.getServer().getEntity(it.next());
            if (e == null || !e.isValid()) {
                it.remove();
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

    /** Crea la carpeta y los tipos que falten, y la tabla de biomas si no hay. Nunca pisa lo editado. */
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
        if (creados > 0) reg.save();
        if (escribirTabla) modulo.saveConfig();
        modulo.getLogger().info("[Lethal World] Mobs: " + PANACEA.size() * 3 + " tipos en /esb (" + creados + " nuevos).");
    }

    private static MinionType buscar(MinionRegistry reg, String nombre) {
        for (MinionType t : reg.types()) if (t.display().equals(nombre)) return t;
        return null;
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
            look.outline(NamedTextColor.RED);
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
