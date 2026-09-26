package net.ederus.edm.anomaly.core;

import net.ederus.edm.anomaly.boss.Ability;
import net.ederus.edm.comun.Fx;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Los numeros de las habilidades de cada anomalia, en Skills/Anomalias/<id>.yml.
 *
 * La logica de cada habilidad sigue en el plugin (AnomalyRegistry y la clase del jefe);
 * aqui solo se ajusta si esta activa, su fase, enfriamiento, duracion, peso y un
 * multiplicador de dano propio. Si el fichero no existe se genera con los valores del
 * codigo, y cada habilidad lleva encima un comentario con su nombre y que hace.
 *
 * Una habilidad que el plugin trae nueva y el fichero aun no tiene se añade sola con
 * sus valores de diseno. Las que el fichero tenga y el plugin ya no, se ignoran.
 */
public final class SkillSettings {

    public static final String CARPETA = "Skills/Anomalias";

    private final Plugin plugin;
    private final AnomalyFiles fichas;
    private final Map<String, YamlConfiguration> cache = new HashMap<>();

    public SkillSettings(Plugin plugin, AnomalyFiles fichas) {
        this.plugin = plugin;
        this.fichas = fichas;
    }

    /** Olvida lo leido: la proxima lista de habilidades se relee del disco. */
    public void clear() {
        cache.clear();
    }

    private File file(String anomalyId) {
        return new File(new File(plugin.getDataFolder(), CARPETA), anomalyId + ".yml");
    }

    /**
     * La lista de habilidades del codigo con los ajustes del fichero aplicados: las
     * apagadas fuera, y las demas con su fase, enfriamiento, duracion, peso y dano.
     */
    public List<Ability> aplicar(String anomalyId, List<Ability> code) {
        YamlConfiguration yml = cache.get(anomalyId);
        if (yml == null) {
            File f = file(anomalyId);
            yml = f.isFile() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
            cache.put(anomalyId, yml);
        }
        boolean changed = false;
        List<Ability> out = new ArrayList<>(code.size());
        for (Ability a : code) {
            String base = "habilidades." + a.id();
            if (!yml.isConfigurationSection(base)) {
                yml.set(base + ".activa", true);
                yml.set(base + ".fase", a.phase());
                yml.set(base + ".cooldown", a.cooldownTicks());
                yml.set(base + ".cast", a.castTicks());
                yml.set(base + ".peso", a.weight());
                yml.set(base + ".dano", 1.0);
                String desc = a.description() == null || a.description().isBlank()
                        ? "" : ": " + a.description();
                yml.setComments(base, List.of(a.display() + desc));
                changed = true;
            }
            if (!yml.getBoolean(base + ".activa", true)) continue;
            out.add(a.adjusted(
                    Math.max(0, yml.getInt(base + ".fase", a.phase())),
                    Math.max(1, yml.getInt(base + ".cooldown", a.cooldownTicks())),
                    Math.max(0, yml.getInt(base + ".cast", a.castTicks())),
                    Math.max(1, yml.getInt(base + ".peso", a.weight())),
                    Fx.clamp(yml.getDouble(base + ".dano", 1.0), 0, 20)));
        }
        if (changed) save(anomalyId, yml);
        return out;
    }

    private void save(String anomalyId, YamlConfiguration yml) {
        String nombre = fichas.getString(anomalyId, "nombre", anomalyId);
        yml.options().setHeader(List.of(
                "Habilidades de " + nombre + " (" + anomalyId + ").",
                "La logica esta en el plugin; aqui se ajustan sus numeros. Tras tocarlo a mano: /anomaly reload.",
                "",
                "  activa:    false = el jefe no la lanza nunca.",
                "  fase:      en que fase la usa (1, 2, 3... segun el jefe). 0 = en cualquier fase.",
                "  cooldown:  enfriamiento en ticks (20 = 1 s). Nunca baja de 'cast'.",
                "  cast:      lo que dura la animacion en ticks; mientras, el jefe no lanza otra.",
                "  peso:      probabilidad relativa frente a las demas disponibles.",
                "  dano:      multiplicador solo de esta habilidad (0 a 20), que se multiplica con",
                "             el 'dano' de la ficha (Anomalias/" + anomalyId + ".yml).",
                "             Ojo: se aplica mientras dura su 'cast'. Lo que la habilidad deje",
                "             pegando despues (charcos, marcas diferidas...) cuenta como x1.0.",
                "",
                "Un apartado nuevo que traiga el plugin se añade solo con sus valores de diseno."));
        try {
            File f = file(anomalyId);
            f.getParentFile().mkdirs();
            yml.save(f);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "No se pudo guardar " + CARPETA + "/" + anomalyId + ".yml", ex);
        }
    }
}
