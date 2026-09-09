package net.ederus.edm.anomaly.minions;

import net.ederus.edm.anomaly.AnomalyPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * El almacen de esbirros: los tipos y sus generadores, todo en esbirros.yml.
 * Se edita desde el menu, pero el yml se puede tocar a mano igual que drops.yml.
 * Vive en plugins/EDM/anomaly/, que es persistente entre despliegues.
 */
public final class MinionRegistry {

    private final AnomalyPlugin plugin;
    private final Map<String, MinionType> types = new LinkedHashMap<>();
    private final Map<String, MinionSpawner> spawners = new LinkedHashMap<>();
    private File file;

    public MinionRegistry(AnomalyPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------------- tipos

    public List<MinionType> types() {
        return new ArrayList<>(types.values());
    }

    public MinionType type(String id) {
        return id == null ? null : types.get(id);
    }

    /**
     * Crea un tipo nuevo a partir del nombre que el admin escribio en el chat.
     * El id sale del nombre en minusculas; si choca, se le anade un numero.
     */
    public MinionType createType(String display) {
        String base = display.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9ñ]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) base = "esbirro";
        String id = base;
        int n = 2;
        while (types.containsKey(id)) id = base + "-" + n++;
        MinionType type = new MinionType(id, display);
        types.put(id, type);
        save();
        return type;
    }

    /** Borra el tipo y arrastra sus generadores: sin tipo no hay nada que generar. */
    public void deleteType(MinionType type) {
        types.remove(type.id());
        spawners.values().removeIf(s -> s.typeId().equals(type.id()));
        save();
    }

    // ----------------------------------------------------------------- generadores

    public List<MinionSpawner> spawners() {
        return new ArrayList<>(spawners.values());
    }

    public List<MinionSpawner> spawnersOf(String typeId) {
        List<MinionSpawner> out = new ArrayList<>();
        for (MinionSpawner s : spawners.values()) {
            if (s.typeId().equals(typeId)) out.add(s);
        }
        return out;
    }

    public MinionSpawner spawner(String id) {
        return id == null ? null : spawners.get(id);
    }

    /** Planta un generador nuevo donde la vela toco, con los valores que traia. */
    public MinionSpawner createSpawner(MinionType type, String world, int x, int y, int z,
                                       int minLevel, int maxLevel, int intervalSeconds,
                                       int maxAlive, int activationRadius) {
        int n = 1;
        String id;
        do {
            id = type.id() + "-" + n++;
        } while (spawners.containsKey(id));
        MinionSpawner s = new MinionSpawner(id, type.id(), world, x, y, z);
        s.minLevel(minLevel);
        s.maxLevel(maxLevel);
        s.intervalSeconds(intervalSeconds);
        s.maxAlive(maxAlive);
        s.activationRadius(activationRadius);
        spawners.put(id, s);
        save();
        return s;
    }

    public void deleteSpawner(MinionSpawner s) {
        spawners.remove(s.id());
        save();
    }

    // ---------------------------------------------------------------------- disco

    public void load() {
        types.clear();
        spawners.clear();
        file = new File(plugin.getDataFolder(), "esbirros.yml");
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection tsec = yml.getConfigurationSection("esbirros");
        if (tsec != null) {
            for (String id : tsec.getKeys(false)) {
                ConfigurationSection s = tsec.getConfigurationSection(id);
                if (s == null) continue;
                MinionType type = new MinionType(id, s.getString("nombre", id));
                type.colorRgb(s.getInt("color", 0xFFFFFF));
                try {
                    type.entity(EntityType.valueOf(s.getString("entidad", "ZOMBIE")));
                } catch (IllegalArgumentException ignored) {
                }
                type.baseHealth(s.getDouble("vida-base", 20));
                type.healthGrowth(s.getDouble("vida-por-nivel", 0.35));
                type.baseDamage(s.getDouble("dano-base", 1.0));
                type.damageGrowth(s.getDouble("dano-por-nivel", 0.10));
                type.wandMinLevel(s.getInt("vela.nivel-min", 1));
                type.wandMaxLevel(s.getInt("vela.nivel-max", 5));
                type.wandIntervalSeconds(s.getInt("vela.intervalo-segundos", 30));
                type.wandMaxAlive(s.getInt("vela.tope-vivos", 3));
                type.wandActivationRadius(s.getInt("vela.radio-activacion", 32));
                for (String raw : s.getStringList("habilidades")) {
                    MinionAbility ability = MinionAbility.byId(raw);
                    if (ability != null) type.abilities().add(ability);
                }
                types.put(id, type);
            }
        }

        ConfigurationSection gsec = yml.getConfigurationSection("generadores");
        if (gsec != null) {
            for (String id : gsec.getKeys(false)) {
                ConfigurationSection s = gsec.getConfigurationSection(id);
                if (s == null) continue;
                String typeId = s.getString("esbirro", "");
                if (!types.containsKey(typeId)) continue; // huerfano: su tipo ya no existe
                MinionSpawner sp = new MinionSpawner(id, typeId,
                        s.getString("mundo", "world"), s.getInt("x"), s.getInt("y"), s.getInt("z"));
                sp.minLevel(s.getInt("nivel-min", 1));
                sp.maxLevel(s.getInt("nivel-max", 5));
                sp.intervalSeconds(s.getInt("intervalo-segundos", 30));
                sp.maxAlive(s.getInt("tope-vivos", 3));
                sp.activationRadius(s.getInt("radio-activacion", 32));
                sp.enabled(s.getBoolean("activo", true));
                spawners.put(id, sp);
            }
        }
        plugin.getLogger().info("Esbirros cargados: " + types.size() + " tipo(s), "
                + spawners.size() + " generador(es).");
    }

    public void save() {
        if (file == null) file = new File(plugin.getDataFolder(), "esbirros.yml");
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "Esbirros de Anomaly: la tropa que puebla las mazmorras.",
                "Se edita desde el menu (/anomaly menu -> Esbirros), pero se puede tocar a mano.",
                "",
                "La vida a nivel N es  vida-base * (1 + vida-por-nivel * (N - 1)).",
                "El dano es un multiplicador sobre el golpe de fabrica del bicho:",
                "  x dano-base * (1 + dano-por-nivel * (N - 1)).",
                "",
                "Cada generador tiene SU rango de nivel: el mismo esbirro puede ser 5-10",
                "en una sala y 20-30 en otra. El botin se configura en drops.yml, en la",
                "seccion 'esbirro-<id>'.",
                "",
                "habilidades: rasgos que se encienden y se apagan desde el menu.",
                "  flecha-pesada  cada tercera flecha pega el doble",
                "  agil           se mueve un 25% mas rapido",
                "  flecha-helada  sus flechas dejan lentitud 3 segundos"));
        for (MinionType t : types.values()) {
            String base = "esbirros." + t.id();
            yml.set(base + ".nombre", t.display());
            yml.set(base + ".color", t.colorRgb());
            yml.set(base + ".entidad", t.entity().name());
            yml.set(base + ".vida-base", t.baseHealth());
            yml.set(base + ".vida-por-nivel", t.healthGrowth());
            yml.set(base + ".dano-base", t.baseDamage());
            yml.set(base + ".dano-por-nivel", t.damageGrowth());
            yml.set(base + ".vela.nivel-min", t.wandMinLevel());
            yml.set(base + ".vela.nivel-max", t.wandMaxLevel());
            yml.set(base + ".vela.intervalo-segundos", t.wandIntervalSeconds());
            yml.set(base + ".vela.tope-vivos", t.wandMaxAlive());
            yml.set(base + ".vela.radio-activacion", t.wandActivationRadius());
            List<String> abilities = new ArrayList<>();
            for (MinionAbility a : t.abilities()) abilities.add(a.id());
            yml.set(base + ".habilidades", abilities);
        }
        for (MinionSpawner s : spawners.values()) {
            String base = "generadores." + s.id();
            yml.set(base + ".esbirro", s.typeId());
            yml.set(base + ".mundo", s.worldName());
            yml.set(base + ".x", s.x());
            yml.set(base + ".y", s.y());
            yml.set(base + ".z", s.z());
            yml.set(base + ".nivel-min", s.minLevel());
            yml.set(base + ".nivel-max", s.maxLevel());
            yml.set(base + ".intervalo-segundos", s.intervalSeconds());
            yml.set(base + ".tope-vivos", s.maxAlive());
            yml.set(base + ".radio-activacion", s.activationRadius());
            yml.set(base + ".activo", s.enabled());
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "No se pudo guardar esbirros.yml", ex);
        }
    }
}
