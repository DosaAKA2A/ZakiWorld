package net.ederus.edm.anomaly.core;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import net.ederus.edm.comun.Fx;

/**
 * Acceso tipado a config.yml. Todo lo que se puede tocar desde el menu de ajustes
 * pasa por aqui, y cada cambio se guarda en el acto: si el servidor se cae despues
 * de mover un valor en la GUI, el valor sigue puesto.
 */
public final class Settings {

    private final org.bukkit.plugin.Plugin plugin;

    public Settings(org.bukkit.plugin.Plugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    // ------------------------------------------------------------------ ubicacion

    public List<String> allowedWorlds() {
        return cfg().getStringList("general.mundos");
    }

    public double minDistance() {
        return cfg().getDouble("general.distancia-minima", 200);
    }

    public double maxDistance() {
        return cfg().getDouble("general.distancia-maxima", 1200);
    }

    public double minSpawnDistance() {
        return cfg().getDouble("general.distancia-minima-spawn", 300);
    }

    public int searchAttempts() {
        return cfg().getInt("general.intentos-de-busqueda", 40);
    }

    public double protectionMargin() {
        return cfg().getDouble("general.margen-proteccion", 24);
    }

    public boolean avoidBases() {
        return cfg().getBoolean("general.evitar-bases", true);
    }

    public int baseScanRadius() {
        return cfg().getInt("general.radio-escaneo-bases", 24);
    }

    public int arenaRadius() {
        return cfg().getInt("general.radio-arena", 12);
    }

    public int maxSlope() {
        return cfg().getInt("general.desnivel-maximo", 4);
    }

    /** Profundidad minima de agua para una anomalia acuatica. Un charco no es un abismo. */
    public int minWaterDepth() {
        return cfg().getInt("general.profundidad-minima-agua", 8);
    }

    // ----------------------------------------------------------------- automatico

    public boolean autoEnabled() {
        return cfg().getBoolean("automatico.activo", false);
    }

    public int autoIntervalMinutes() {
        return Math.max(5, cfg().getInt("automatico.intervalo-minutos", 90));
    }

    public int autoMinPlayers() {
        return cfg().getInt("automatico.jugadores-minimos", 2);
    }

    /** Id de anomalia, o "aleatoria" para que elija el plugin. */
    public String autoAnomaly() {
        return cfg().getString("automatico.anomalia", "aleatoria");
    }

    // -------------------------------------------------------------------- combate

    public double participationRadius() {
        return cfg().getDouble("combate.radio-participacion", 64);
    }

    public int timeLimitMinutes() {
        return cfg().getInt("combate.minutos-limite", 15);
    }

    /** Regla heredada de Rip: el empuje se puede desactivar entero desde el menu. */
    public boolean allowKnockback() {
        return cfg().getBoolean("combate.permitir-empuje", true);
    }

    public double healthPerPlayer() {
        return cfg().getDouble("combate.vida-extra-por-jugador", 0.15);
    }

    /**
     * Si los golpes al jefe se saltan las protecciones de terreno.
     *
     * Las anomalias con cuerpo pacifico (el Piromante es un aldeano) las protege
     * WorldGuard como si fueran ganado: dentro de una region cerrada al publico no se
     * les puede pegar, y el coliseo es justo eso. Con esto puesto, el golpe de un
     * jugador al jefe, a su maniqui o a un esbirro se vuelve a permitir aunque otro
     * plugin lo haya cancelado.
     */
    public boolean bypassProtections() {
        return cfg().getBoolean("combate.saltarse-protecciones", true);
    }

    /** La zona de Lethal Biomes que es la arena. Vacio = las anomalias no tocan el clima. */
    public String arenaZone() {
        return cfg().getString("arena.zona", "");
    }

    /** Clima que pinta cada anomalia sobre la arena si el config no dice otro. */
    private static final java.util.Map<String, String> CLIMA_DE_SERIE = java.util.Map.ofEntries(
            java.util.Map.entry("alba", "celestial"),
            java.util.Map.entry("keeper", "ancient"),
            java.util.Map.entry("darkness", "penumbra"),
            java.util.Map.entry("coro_abisal", "penumbra"),
            java.util.Map.entry("aragon", "ancient"),
            java.util.Map.entry("storm_rider", "storm"),
            java.util.Map.entry("cabra_gritona", "storm"),
            java.util.Map.entry("piromante", "sandstorm"),
            java.util.Map.entry("leviatan_de_sal", "sandstorm"),
            java.util.Map.entry("caballero_sepulcral", "bloodmoon"),
            java.util.Map.entry("conejo_asesino", "bloodmoon"),
            java.util.Map.entry("cazador", "bloodmoon"),
            java.util.Map.entry("bruja", "hypnos"),
            java.util.Map.entry("mimic", "hypnos"),
            java.util.Map.entry("herbola", "aether"),
            java.util.Map.entry("rabby", "aether"),
            java.util.Map.entry("quimera", "radioactive"),
            java.util.Map.entry("gemelos_cobre", "radioactive"));

    /** El clima de una anomalia en la arena; "" = ninguno. */
    public String arenaClimate(String anomalyId) {
        String v = cfg().getString("anomalias." + anomalyId + ".bioma", null);
        if (v == null) return CLIMA_DE_SERIE.getOrDefault(anomalyId, "");
        return v.equalsIgnoreCase("none") || v.equalsIgnoreCase("ninguno") ? "" : v;
    }

    /** Lo mas cerca que cae un monton de botin respecto al cuerpo del jefe. */
    public double lootMinDistance() {
        return Math.max(0, cfg().getDouble("botin.distancia-minima", 10));
    }

    public double lootMaxDistance() {
        return Math.max(lootMinDistance(), cfg().getDouble("botin.distancia-maxima", 15));
    }

    public boolean announceEnabled() {
        return cfg().getBoolean("anuncio.activo", true);
    }

    public boolean announceSound() {
        return cfg().getBoolean("anuncio.sonido", true);
    }

    public boolean announceTitle() {
        return cfg().getBoolean("anuncio.titulo", true);
    }

    /** El pilar de luz sobre el jefe, del color de la anomalia. Se puede apagar si pesa. */
    public boolean lightPillar() {
        return cfg().getBoolean("anuncio.pilar-de-luz", true);
    }

    /** Redondea las coordenadas anunciadas a multiplos de N para dar margen de busqueda. */
    public int coordinatePrecision() {
        return Math.max(1, cfg().getInt("anuncio.precision-coordenadas", 1));
    }

    // ------------------------------------------------------------------ escritura

    public void set(String path, Object value) {
        cfg().set(path, value);
        plugin.saveConfig();
    }

    public void toggle(String path, boolean def) {
        set(path, !cfg().getBoolean(path, def));
    }

    /** Suma delta y deja el resultado dentro de [min, max]. */
    public void bump(String path, double delta, double min, double max, double def) {
        double v = cfg().getDouble(path, def) + delta;
        set(path, Math.round(Fx.clamp(v, min, max) * 100.0) / 100.0);
    }

    public void bumpInt(String path, int delta, int min, int max, int def) {
        int v = cfg().getInt(path, def) + delta;
        set(path, (int) Fx.clamp(v, min, max));
    }

    public double raw(String path, double def) {
        return cfg().getDouble(path, def);
    }

    public int rawInt(String path, int def) {
        return cfg().getInt(path, def);
    }

    public boolean rawBool(String path, boolean def) {
        return cfg().getBoolean(path, def);
    }
}
