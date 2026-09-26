package net.ederus.edm.anomaly.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Las fichas de las anomalias: un fichero por anomalia en Anomalias/<id>.yml.
 *
 * Antes todo vivia en config.yml bajo `anomalias.<id>`, y con veinte jefes el fichero
 * se hacia eterno. Ahora cada ficha va suelta, se carga entera en memoria y al tocar un
 * valor desde el menu se guarda SOLO el fichero de esa anomalia, al momento.
 *
 * Si una anomalia registrada no tiene fichero se le crea con los valores de diseno,
 * asi el admin ve todas las fichas desde el primer arranque.
 */
public final class AnomalyFiles {

    public static final String CARPETA = "Anomalias";

    private final Plugin plugin;
    private final Map<String, YamlConfiguration> fichas = new LinkedHashMap<>();

    public AnomalyFiles(Plugin plugin) {
        this.plugin = plugin;
    }

    private File dir() {
        return new File(plugin.getDataFolder(), CARPETA);
    }

    private File file(String id) {
        return new File(dir(), id + ".yml");
    }

    /**
     * Lee todas las fichas. Antes migra el formato viejo si hace falta, y despues crea
     * la de cada anomalia registrada que no tenga fichero.
     */
    public void load(Collection<AnomalyType> types) {
        migrate(types);
        fichas.clear();
        File[] files = dir().listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                String id = f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT);
                fichas.put(id, YamlConfiguration.loadConfiguration(f));
            }
        }
        int nuevas = 0;
        for (AnomalyType t : types) {
            if (fichas.containsKey(t.id())) continue;
            YamlConfiguration yml = new YamlConfiguration();
            fillDefaults(yml, t);
            fichas.put(t.id(), yml);
            save(t.id());
            nuevas++;
        }
        if (nuevas > 0) {
            plugin.getLogger().info("Creadas " + nuevas + " ficha(s) de anomalía nuevas en " + CARPETA + "/.");
        }
    }

    /** Pone lo que falte con los valores de diseno de la anomalia; lo que ya haya no se toca. */
    private void fillDefaults(YamlConfiguration yml, AnomalyType t) {
        def(yml, "activa", true);
        def(yml, "clase", t.defaultClass().name());
        def(yml, "nombre", t.display());
        def(yml, "vida", Math.round(t.baseHealth()));
        def(yml, "dano", 1.0);
        def(yml, "descripcion", List.of());
        def(yml, "amenaza", List.of());
        if (!yml.isSet("spawn")) yml.createSection("spawn");
        // Sin bioma escrito valia el clima de serie: se escribe ese para no perderlo.
        def(yml, "bioma", Settings.climaDeSerie(t.id()));
    }

    private static void def(YamlConfiguration yml, String key, Object value) {
        if (!yml.isSet(key)) yml.set(key, value);
    }

    private static void comments(YamlConfiguration yml) {
        yml.setInlineComments("activa", List.of("false = el automatico no la elige (a mano si se puede abrir)"));
        yml.setInlineComments("clase", List.of("ESBIRRO, GENERAL, MONARCA o DIOS"));
        yml.setInlineComments("vida", List.of("vida base antes de escalar por jugadores (100 a 400000)"));
        yml.setInlineComments("dano", List.of("multiplicador de TODAS sus habilidades (0.1 a 20)"));
        yml.setInlineComments("descripcion", List.of("hover del anuncio; vacio = texto del plugin"));
        yml.setInlineComments("amenaza", List.of("aviso de peligro del hover; vacio = texto del plugin"));
        yml.setInlineComments("spawn", List.of("mundo/x/y/z si tiene punto fijo; vacio = sitio aleatorio"));
        yml.setInlineComments("bioma", List.of("clima de la arena (/anomaly biome); vacio = ninguno"));
    }

    // ------------------------------------------------------------------ lectura

    /** La ficha en memoria, o null si esa anomalia no tiene. */
    public YamlConfiguration get(String id) {
        return fichas.get(id);
    }

    public boolean has(String id, String key) {
        YamlConfiguration yml = fichas.get(id);
        return yml != null && yml.isSet(key);
    }

    public boolean getBoolean(String id, String key, boolean def) {
        YamlConfiguration yml = fichas.get(id);
        return yml == null ? def : yml.getBoolean(key, def);
    }

    public String getString(String id, String key, String def) {
        YamlConfiguration yml = fichas.get(id);
        return yml == null ? def : yml.getString(key, def);
    }

    public double getDouble(String id, String key, double def) {
        YamlConfiguration yml = fichas.get(id);
        return yml == null ? def : yml.getDouble(key, def);
    }

    public int getInt(String id, String key, int def) {
        YamlConfiguration yml = fichas.get(id);
        return yml == null ? def : yml.getInt(key, def);
    }

    public List<String> getStringList(String id, String key) {
        YamlConfiguration yml = fichas.get(id);
        return yml == null ? List.of() : yml.getStringList(key);
    }

    // ---------------------------------------------------------------- escritura

    /** Cambia un valor y guarda al momento el fichero de esa anomalia (y solo ese). */
    public void set(String id, String key, Object value) {
        YamlConfiguration yml = fichas.computeIfAbsent(id, k -> new YamlConfiguration());
        yml.set(key, value);
        save(id);
    }

    /**
     * Cambia un apartado entero (el spawn) y guarda. Va aparte de set() porque un Map
     * metido con set() se queda como valor suelto hasta releer el fichero, y entonces
     * "spawn.mundo" no se encontraria.
     */
    public void setSection(String id, String key, Map<String, ?> values) {
        YamlConfiguration yml = fichas.computeIfAbsent(id, k -> new YamlConfiguration());
        yml.createSection(key, values);
        save(id);
    }

    public void save(String id) {
        YamlConfiguration yml = fichas.get(id);
        if (yml == null) return;
        // Los comentarios de linea se ponen al guardar: un apartado reescrito (el
        // spawn) pierde el suyo y asi lo recupera.
        comments(yml);
        String nombre = yml.getString("nombre", id);
        yml.options().setHeader(List.of(
                "Ficha de la anomalia " + nombre + " (" + id + ").",
                "Se edita desde /anomaly (menu) o a mano + /anomaly reload.",
                "",
                "  activa:       false = el automatico no la elige; a mano (/anomaly start) si.",
                "  clase:        ESBIRRO, GENERAL, MONARCA o DIOS. Ordena el catalogo.",
                "  nombre:       el que sale en la barra de jefe y en el anuncio.",
                "  vida:         hasta 400000 (por encima de 1024 el exceso se cobra en",
                "                reduccion de dano recibido; la pelea dura lo configurado).",
                "  dano:         multiplicador de TODAS sus habilidades, de 0.1 a 20.0.",
                "                Cada habilidad tiene ademas el suyo en Skills/Anomalias/" + id + ".yml.",
                "  descripcion:  lineas del hover del anuncio (de donde viene). Vacio = las del plugin.",
                "  amenaza:      el aviso de peligro del hover. Vacio = el del plugin.",
                "  spawn:        punto fijo de aparicion (mundo/x/y/z), se marca desde el menu.",
                "  bioma:        clima que pinta en la arena (/anomaly biome). Vacio = ninguno.",
                "  mobcoins:     (opcional) bote que reparte al caer; sin el vale",
                "                combate.mobcoins-por-defecto del config.yml."));
        try {
            if (!dir().isDirectory()) dir().mkdirs();
            yml.save(file(id));
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "No se pudo guardar " + CARPETA + "/" + id + ".yml", ex);
        }
    }

    // ---------------------------------------------------------------- migracion

    /**
     * Del formato viejo (config.yml, seccion `anomalias`) al de carpetas. Solo si la
     * carpeta Anomalias/ aun no existe: con ella creada, lo que haya en el config ya
     * no manda. Antes de tocar nada deja una copia config.yml.migrado-<fecha>.
     */
    private void migrate(Collection<AnomalyType> types) {
        ConfigurationSection viejo = plugin.getConfig().getConfigurationSection("anomalias");
        if (viejo == null) return;
        if (dir().exists()) {
            plugin.getLogger().warning("config.yml aun tiene la seccion 'anomalias', pero ya existe "
                    + CARPETA + "/: se ignora. Las fichas buenas son las de la carpeta.");
            return;
        }
        File config = new File(plugin.getDataFolder(), "config.yml");
        try {
            if (config.isFile()) {
                Files.copy(config.toPath(),
                        new File(plugin.getDataFolder(), "config.yml.migrado-" + LocalDate.now()).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "No se pudo copiar config.yml antes de migrar; "
                    + "no se migra nada.", ex);
            return;
        }
        Map<String, AnomalyType> porId = new LinkedHashMap<>();
        for (AnomalyType t : types) porId.put(t.id(), t);

        int n = 0;
        for (String id : viejo.getKeys(false)) {
            ConfigurationSection sec = viejo.getConfigurationSection(id);
            if (sec == null) continue;
            YamlConfiguration yml = new YamlConfiguration();
            // Hoja a hoja: asi viajan tambien las claves que no conocemos (mobcoins...).
            for (String key : sec.getKeys(true)) {
                if (sec.isConfigurationSection(key)) continue;
                yml.set(key, sec.get(key));
            }
            AnomalyType t = porId.get(id);
            if (t != null) fillDefaults(yml, t);
            fichas.put(id, yml);
            save(id);
            n++;
        }
        fichas.clear();
        plugin.getConfig().set("anomalias", null);
        plugin.saveConfig();
        plugin.getLogger().info("Migradas " + n + " fichas de anomalía a " + CARPETA + "/ "
                + "(copia del config viejo en config.yml.migrado-" + LocalDate.now() + ").");
    }
}
