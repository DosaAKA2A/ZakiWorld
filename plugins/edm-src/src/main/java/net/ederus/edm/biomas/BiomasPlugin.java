package net.ederus.edm.biomas;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WeatherType;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;

/**
 * Lethal Biomes: climas que se pintan sobre una zona y se quitan sin reiniciar.
 *
 * El tinte (cielo, niebla, particulas de ambiente, sonidos) lo pone un bioma de
 * datapack, igual que en la modalidad Lethal. Lo que un bioma no sabe hacer —la
 * hora, la lluvia, los rayos, la ceniza que cae solo cuando llueve, las fumarolas—
 * lo manda este modulo a cada jugador que este dentro de una zona pintada.
 *
 * Otros modulos (la arena de las anomalias) lo usan por {@link #activo()}.
 */
public final class BiomasPlugin extends Module implements Listener {

    public static final String NAMESPACE = "lethal";
    private static final String PACK = "lethal_biomes";
    static final List<String> INCLUIDOS = List.of(
            "sandstorm", "aether", "hypnos", "storm", "bloodmoon", "penumbra", "celestial", "radioactive", "ancient");

    private static BiomasPlugin instancia;

    private net.ederus.edm.comun.Bitacora bitacora;

    private final Map<String, Zona> zonas = new LinkedHashMap<>();
    private final Map<String, List<Location>> fumarolas = new HashMap<>();
    /** Que clima le esta mandando el modulo a cada jugador, para deshacerlo al salir. */
    private final Map<UUID, String> dentro = new HashMap<>();
    private final Set<String> pintando = new HashSet<>();
    private final Map<String, String> pendientes = new HashMap<>();
    private final Random random = new Random();
    private BukkitTask efectos;
    private File ficheroZonas;

    public BiomasPlugin(EDMPlugin core) {
        super(core, "biomas", "LethalBiomes");
    }

    /** El modulo si esta cargado, o null. */
    public static BiomasPlugin activo() {
        return instancia;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        this.bitacora = core.bitacora("biomas");
        new File(getDataFolder(), "extra").mkdirs();
        instalarDatapack();
        cargarZonas();

        ComandoBiomas comando = new ComandoBiomas(this);
        var cmd = core.getCommand("lbiomes");
        if (cmd != null) {
            cmd.setExecutor(comando);
            cmd.setTabCompleter(comando);
        } else {
            getLogger().warning("El comando /lbiomes no esta en el plugin.yml de EDM.");
        }
        core.getServer().getPluginManager().registerEvents(this, this);
        efectos = core.getServer().getScheduler().runTaskTimer(Module.dueno(this), this::tickEfectos, 20L, 5L);
        instancia = this;

        int cargados = 0;
        for (String id : climas()) if (Pintor.bioma(id) != null) cargados++;
        getLogger().info("[Lethal Biomes] " + cargados + "/" + climas().size() + " climas cargados, "
                + zonas.size() + " zona(s).");
    }

    @Override
    public void onDisable() {
        instancia = null;
        if (efectos != null) efectos.cancel();
        for (UUID id : new ArrayList<>(dentro.keySet())) {
            Player p = core.getServer().getPlayer(id);
            if (p != null) deshacer(p);
        }
        dentro.clear();
    }

    @Override
    public String recargar() {
        reloadConfig();
        cargarZonas();
        return zonas.size() + " zona(s).";
    }

    // ------------------------------------------------------------------- datapack

    /**
     * Copia el datapack del jar (y los .json de extra/) al mundo principal. Si algo
     * cambia hace falta UN reinicio: la lista de biomas se congela al arrancar.
     */
    private void instalarDatapack() {
        File destino = new File(new File(new File(core.getServer().getWorldContainer(), nivel()), "datapacks"), PACK);
        File carpetaBiomas = new File(destino, "data/" + NAMESPACE + "/worldgen/biome");
        boolean cambio = false;
        Set<String> esperados = new HashSet<>();
        try {
            cambio |= escribir(new File(destino, "pack.mcmeta"), recurso("datapack/pack.mcmeta"));
            for (String id : INCLUIDOS) {
                byte[] datos = recurso("datapack/data/" + NAMESPACE + "/worldgen/biome/" + id + ".json");
                if (datos == null) continue;
                esperados.add(id + ".json");
                cambio |= escribir(new File(carpetaBiomas, id + ".json"), datos);
            }
            File[] extra = new File(getDataFolder(), "extra").listFiles((d, n) -> n.endsWith(".json"));
            if (extra != null) {
                for (File f : extra) {
                    String nombre = f.getName().toLowerCase(Locale.ROOT);
                    esperados.add(nombre);
                    cambio |= escribir(new File(carpetaBiomas, nombre), Files.readAllBytes(f.toPath()));
                }
            }
            File[] sobran = carpetaBiomas.listFiles((d, n) -> n.endsWith(".json"));
            if (sobran != null) {
                for (File f : sobran) {
                    if (!esperados.contains(f.getName()) && f.delete()) cambio = true;
                }
            }
        } catch (IOException e) {
            getLogger().warning("[Lethal Biomes] No se pudo instalar el datapack: " + e.getMessage());
            return;
        }
        if (cambio) {
            getLogger().warning("[Lethal Biomes] Datapack instalado o actualizado en " + destino.getPath()
                    + ". Hace falta reiniciar para que los climas nuevos existan.");
        }
    }

    private byte[] recurso(String ruta) throws IOException {
        try (InputStream in = getResource(ruta)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static boolean escribir(File f, byte[] datos) throws IOException {
        if (datos == null) return false;
        if (f.isFile() && Arrays.equals(Files.readAllBytes(f.toPath()), datos)) return false;
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), datos);
        return true;
    }

    private String nivel() {
        Properties props = new Properties();
        File file = new File(core.getServer().getWorldContainer(), "server.properties");
        try (Reader r = Files.newBufferedReader(file.toPath())) {
            props.load(r);
        } catch (IOException ignored) {
        }
        return props.getProperty("level-name", "world");
    }

    // ---------------------------------------------------------------------- climas

    /** Los climas conocidos: los del jar mas los extra instalados. */
    public List<String> climas() {
        List<String> out = new ArrayList<>(INCLUIDOS);
        File[] extra = new File(getDataFolder(), "extra").listFiles((d, n) -> n.endsWith(".json"));
        if (extra != null) {
            for (File f : extra) {
                String id = f.getName().substring(0, f.getName().length() - 5).toLowerCase(Locale.ROOT);
                if (!out.contains(id)) out.add(id);
            }
        }
        return out;
    }

    public String nombreClima(String id) {
        return getConfig().getString("biomas." + id + ".nombre", id);
    }

    private ConfigurationSection ajustes(String clima) {
        ConfigurationSection s = getConfig().getConfigurationSection("biomas." + clima);
        return s == null ? new YamlConfiguration() : s;
    }

    // ----------------------------------------------------------------------- zonas

    private void cargarZonas() {
        zonas.clear();
        ficheroZonas = new File(getDataFolder(), "zonas.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(ficheroZonas);
        ConfigurationSection raiz = yml.getConfigurationSection("zonas");
        if (raiz == null) return;
        for (String nombre : raiz.getKeys(false)) {
            ConfigurationSection s = raiz.getConfigurationSection(nombre);
            if (s == null) continue;
            zonas.put(nombre.toLowerCase(Locale.ROOT), new Zona(nombre.toLowerCase(Locale.ROOT),
                    s.getString("mundo", "world"),
                    s.getInt("min.x"), s.getInt("min.y"), s.getInt("min.z"),
                    s.getInt("max.x"), s.getInt("max.y"), s.getInt("max.z"),
                    s.getString("base", "minecraft:plains"), s.getString("actual", null)));
        }
    }

    private void guardarZonas() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "Zonas de Lethal Biomes. Se crean y se pintan con /lbiomes.",
                "base: el bioma que tenia la zona al crearla; /lbiomes limpiar lo devuelve.",
                "actual: el clima pintado ahora (vacio = en su base)."));
        for (Zona z : zonas.values()) {
            String p = "zonas." + z.nombre();
            yml.set(p + ".mundo", z.mundo());
            yml.set(p + ".min.x", z.minX());
            yml.set(p + ".min.y", z.minY());
            yml.set(p + ".min.z", z.minZ());
            yml.set(p + ".max.x", z.maxX());
            yml.set(p + ".max.y", z.maxY());
            yml.set(p + ".max.z", z.maxZ());
            yml.set(p + ".base", z.base());
            yml.set(p + ".actual", z.actual());
        }
        try {
            yml.save(ficheroZonas);
        } catch (IOException e) {
            getLogger().warning("[Lethal Biomes] No se pudo guardar zonas.yml: " + e.getMessage());
        }
    }

    public Zona zona(String nombre) {
        return nombre == null ? null : zonas.get(nombre.toLowerCase(Locale.ROOT));
    }

    public List<Zona> zonas() {
        return new ArrayList<>(zonas.values());
    }

    /** Crea (o redefine) una zona. El bioma base se toma del centro en este momento. */
    public Zona crearZona(String nombre, World w, int x1, int y1, int z1, int x2, int y2, int z2) {
        String id = nombre.toLowerCase(Locale.ROOT);
        Zona previa = zonas.get(id);
        Location centro = new Location(w, (x1 + x2) / 2.0, (y1 + y2) / 2.0, (z1 + z2) / 2.0);
        String base = previa != null && previa.actual() != null ? previa.base()
                : w.getBiome(centro).getKey().toString();
        Zona z = new Zona(id, w.getName(), x1, y1, z1, x2, y2, z2, base, null);
        zonas.put(id, z);
        guardarZonas();
        /* El bioma base se MUESTREA aqui y es al que vuelve "limpiar" para siempre.
         * Crear la zona con el sitio ya pintado por una anomalia deja el base
         * equivocado y nadie se entera hasta semanas despues: que quede escrito. */
        bitacora.anotar("zona creada", id, "mundo " + w.getName(), z.medidas(),
                "bioma base muestreado: " + base,
                base.startsWith("lethal:") ? "OJO: el base es un clima nuestro, no el del mundo" : "-");
        return z;
    }

    public boolean borrarZona(String nombre) {
        Zona z = zonas.remove(nombre.toLowerCase(Locale.ROOT));
        if (z == null) return false;
        fumarolas.remove(z.nombre());
        guardarZonas();
        return true;
    }

    /** La primera zona que contiene el punto, o null. */
    public Zona zonaEn(Location l) {
        for (Zona z : zonas.values()) if (z.contiene(l)) return z;
        return null;
    }

    /**
     * Lee la caja de una region de WorldGuard de su fichero. Sin la API de WG como
     * dependencia: si no esta instalado, el modulo arranca igual.
     *
     * @return {minX,minY,minZ,maxX,maxY,maxZ} o null si no existe o no es un cuboide
     */
    public int[] regionWorldGuard(String mundo, String region) {
        File f = new File(core.getServer().getPluginsFolder(), "WorldGuard/worlds/" + mundo + "/regions.yml");
        if (!f.isFile()) return null;
        ConfigurationSection s = YamlConfiguration.loadConfiguration(f).getConfigurationSection("regions");
        if (s == null) return null;
        ConfigurationSection r = null;
        for (String k : s.getKeys(false)) {
            if (k.equalsIgnoreCase(region)) r = s.getConfigurationSection(k);
        }
        if (r == null || !r.contains("min") || !r.contains("max")) return null;
        return new int[]{r.getInt("min.x"), r.getInt("min.y"), r.getInt("min.z"),
                r.getInt("max.x"), r.getInt("max.y"), r.getInt("max.z")};
    }

    // ---------------------------------------------------------------------- pintar

    /**
     * Pinta un clima en una zona. clima null o vacio = devolverle su bioma base.
     *
     * @return null si arranco; si no, el motivo en una frase
     */
    public String pintar(String nombreZona, String clima, Runnable alTerminar) {
        Zona z = zona(nombreZona);
        if (z == null) return "No existe la zona '" + nombreZona + "'.";
        World w = core.getServer().getWorld(z.mundo());
        if (w == null) return "El mundo " + z.mundo() + " de la zona no esta cargado.";
        boolean limpiar = clima == null || clima.isBlank();
        Biome b = Pintor.bioma(limpiar ? z.base() : clima);
        if (b == null) {
            return limpiar ? "El bioma base " + z.base() + " no existe."
                    : "El clima '" + clima + "' no esta cargado (¿falta el reinicio tras instalarlo?).";
        }
        if (!pintando.add(z.nombre())) {
            // Una anomalia que cierra y otra que abre en el mismo minuto: gana la ultima.
            pendientes.put(z.nombre(), limpiar ? "" : clima);
            return null;
        }

        String nuevo = limpiar ? null : clima.toLowerCase(Locale.ROOT);
        // El estado cambia YA: los efectos por jugador no esperan a que acabe la pintura.
        zonas.put(z.nombre(), z.conActual(nuevo));
        fumarolas.remove(z.nombre());
        guardarZonas();
        long inicio = System.currentTimeMillis();
        Pintor.pintar(this, w, z, b, () -> {
            pintando.remove(z.nombre());
            String siguiente = pendientes.remove(z.nombre());
            getLogger().info("[Lethal Biomes] Zona " + z.nombre() + " -> " + (nuevo == null ? z.base() : nuevo)
                    + " (" + z.celdas() + " celdas, " + (System.currentTimeMillis() - inicio) + " ms)");
            bitacora.anotar("pintada", z.nombre(),
                    (nuevo == null ? "vuelve a su base " + z.base() : "clima " + nuevo),
                    z.celdas() + " celdas", (System.currentTimeMillis() - inicio) + " ms");
            if (alTerminar != null) alTerminar.run();
            if (siguiente != null) pintar(z.nombre(), siguiente, null);
        });
        return null;
    }

    // --------------------------------------------------------------------- efectos

    private void tickEfectos() {
        Map<String, List<Player>> porZona = new HashMap<>();
        for (Player p : core.getServer().getOnlinePlayers()) {
            Zona z = zonaPintadaEn(p.getLocation());
            String clima = z == null ? null : z.actual();
            String antes = dentro.get(p.getUniqueId());
            if (antes != null && !antes.equals(clima)) {
                deshacer(p);
                dentro.remove(p.getUniqueId());
            }
            if (clima == null) continue;
            if (!clima.equals(dentro.get(p.getUniqueId()))) {
                aplicar(p, clima);
                dentro.put(p.getUniqueId(), clima);
            }
            porZona.computeIfAbsent(z.nombre(), k -> new ArrayList<>()).add(p);
        }
        for (Map.Entry<String, List<Player>> e : porZona.entrySet()) {
            Zona z = zonas.get(e.getKey());
            if (z == null || z.actual() == null) continue;
            World w = core.getServer().getWorld(z.mundo());
            if (w == null) continue;
            ConfigurationSection a = ajustes(z.actual());
            cenizas(w, a, e.getValue());
            rayos(w, z, a, e.getValue());
            gas(w, z, a, e.getValue());
            ecos(w, a, e.getValue());
        }
    }

    private static final Sound[] ECOS = {
            Sound.ENTITY_WARDEN_HEARTBEAT, Sound.ENTITY_WARDEN_NEARBY_CLOSE, Sound.ENTITY_WARDEN_LISTENING,
            Sound.ENTITY_WARDEN_AMBIENT, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, Sound.BLOCK_SCULK_SENSOR_CLICKING};

    /**
     * El mundo antiguo: algo grande respira lejos. Cada jugador oye de vez en cuando un
     * eco del warden desde un punto a 12-20 bloques (solo el, no los de al lado) y el
     * sculk suelta almas a su alrededor. Nada de esto hace dano ni da oscuridad.
     */
    private void ecos(World w, ConfigurationSection a, List<Player> jugadores) {
        double cada = a.getDouble("ecos", 0);
        if (cada <= 0) return;
        for (Player p : jugadores) {
            Location base = p.getLocation();
            p.spawnParticle(Particle.SCULK_SOUL, base.clone().add(0, 0.2, 0), 2, 7, 0.3, 7, 0.02);
            if (random.nextDouble() > 5.0 / (cada * 20.0)) continue;
            double ang = random.nextDouble() * Math.PI * 2;
            double d = 12 + random.nextDouble() * 8;
            Location eco = base.clone().add(Math.cos(ang) * d, -2 + random.nextDouble() * 4, Math.sin(ang) * d);
            p.playSound(eco, ECOS[random.nextInt(ECOS.length)], 0.9f, 0.75f + random.nextFloat() * 0.2f);
            p.spawnParticle(Particle.SCULK_CHARGE_POP, eco, 12, 1.2, 0.4, 1.2, 0.01);
        }
    }

    private Zona zonaPintadaEn(Location l) {
        for (Zona z : zonas.values()) if (z.actual() != null && z.contiene(l)) return z;
        return null;
    }

    private void aplicar(Player p, String clima) {
        ConfigurationSection a = ajustes(clima);
        long hora = a.getLong("hora", -1);
        if (hora >= 0) p.setPlayerTime(hora, false);
        if (a.getBoolean("lluvia", false)) p.setPlayerWeather(WeatherType.DOWNFALL);
        if (!"NUNCA".equalsIgnoreCase(a.getString("cenizas", "NUNCA"))) p.setPlayerWeather(WeatherType.CLEAR);
    }

    private void deshacer(Player p) {
        p.resetPlayerTime();
        p.resetPlayerWeather();
    }

    /** Ceniza que cae alrededor de cada jugador; con LLUVIA, solo si el mundo llueve. */
    private void cenizas(World w, ConfigurationSection a, List<Player> jugadores) {
        String modo = a.getString("cenizas", "NUNCA").toUpperCase(Locale.ROOT);
        if (modo.equals("NUNCA")) return;
        if (modo.equals("LLUVIA") && !w.hasStorm()) return;
        for (Player p : jugadores) {
            Location l = p.getLocation().add(0, 7, 0);
            p.spawnParticle(Particle.ASH, l, 70, 12, 6, 12, 0);
            p.spawnParticle(Particle.WHITE_ASH, l, 30, 12, 6, 12, 0);
        }
    }

    /** Rayos solo visuales cerca de alguien de la zona, cada 'rayos' segundos de media. */
    private void rayos(World w, Zona z, ConfigurationSection a, List<Player> jugadores) {
        double cada = a.getDouble("rayos", 0);
        if (cada <= 0 || random.nextDouble() > 5.0 / (cada * 20.0)) return;
        Player p = jugadores.get(random.nextInt(jugadores.size()));
        double ang = random.nextDouble() * Math.PI * 2;
        double d = 8 + random.nextDouble() * 24;
        int x = (int) Math.floor(p.getLocation().getX() + Math.cos(ang) * d);
        int zz = (int) Math.floor(p.getLocation().getZ() + Math.sin(ang) * d);
        x = Math.max(z.minX(), Math.min(z.maxX(), x));
        zz = Math.max(z.minZ(), Math.min(z.maxZ(), zz));
        int y = w.getHighestBlockYAt(x, zz);
        if (y < z.minY() || y > z.maxY()) y = p.getLocation().getBlockY();
        w.strikeLightningEffect(new Location(w, x + 0.5, y + 1, zz + 0.5));
    }

    /** Fumarolas: columnas de gas verde que salen de puntos fijos del suelo de la zona. */
    private void gas(World w, Zona z, ConfigurationSection a, List<Player> jugadores) {
        int cuantas = a.getInt("gas", 0);
        if (cuantas <= 0) return;
        List<Location> puntos = fumarolas.computeIfAbsent(z.nombre(), k -> buscarFumarolas(w, z, cuantas));
        Color verde = Color.fromARGB(200, 138, 226, 52);
        for (Location v : puntos) {
            for (Player p : jugadores) {
                if (p.getLocation().distanceSquared(v) > 48 * 48) continue;
                p.spawnParticle(Particle.ENTITY_EFFECT, v, 6, 0.25, 0.9, 0.25, 0, verde);
                if (random.nextInt(3) == 0) {
                    p.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, v, 1, 0.15, 0.2, 0.15, 0.015);
                }
                if (random.nextInt(40) == 0) {
                    p.playSound(v, Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.6f, 1.8f);
                }
            }
        }
    }

    private List<Location> buscarFumarolas(World w, Zona z, int cuantas) {
        List<Location> out = new ArrayList<>();
        for (int intento = 0; intento < cuantas * 8 && out.size() < cuantas; intento++) {
            int x = z.minX() + random.nextInt(z.maxX() - z.minX() + 1);
            int zz = z.minZ() + random.nextInt(z.maxZ() - z.minZ() + 1);
            int y = w.getHighestBlockYAt(x, zz);
            if (y < z.minY() || y > z.maxY() || !w.getBlockAt(x, y, zz).getType().isSolid()) continue;
            out.add(new Location(w, x + 0.5, y + 1.1, zz + 0.5));
        }
        return out;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        dentro.remove(e.getPlayer().getUniqueId());
    }
}
