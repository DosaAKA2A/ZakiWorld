package net.ederus.edm.anomaly.minions;

import net.ederus.edm.anomaly.AnomalyPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import net.kyori.adventure.text.format.NamedTextColor;

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
    private final Map<String, MinionCategory> categories = new LinkedHashMap<>();
    private final Map<String, MinionType> types = new LinkedHashMap<>();
    private final Map<String, MinionSpawner> spawners = new LinkedHashMap<>();
    private File file;

    public MinionRegistry(AnomalyPlugin plugin) {
        this.plugin = plugin;
    }

    // ----------------------------------------------------------------- categorias

    public List<MinionCategory> categories() {
        return new ArrayList<>(categories.values());
    }

    public MinionCategory category(String id) {
        return id == null ? null : categories.get(id);
    }

    /** La carpeta de un esbirro, y la general si la suya se perdio por el camino. */
    public MinionCategory categoryOf(MinionType type) {
        MinionCategory cat = type == null ? null : categories.get(type.categoryId());
        return cat != null ? cat : general();
    }

    /** La carpeta de los que no tienen otra. Se crea sola la primera vez. */
    public MinionCategory general() {
        MinionCategory cat = categories.get(MinionCategory.GENERAL);
        if (cat == null) {
            cat = new MinionCategory(MinionCategory.GENERAL, "Sin clasificar");
            cat.icon(org.bukkit.Material.BARREL);
            cat.colorRgb(0xA6ACB9);
            categories.put(cat.id(), cat);
        }
        return cat;
    }

    public MinionCategory createCategory(String display) {
        String id = freeId(display, "carpeta", categories.keySet());
        MinionCategory cat = new MinionCategory(id, display);
        categories.put(id, cat);
        save();
        return cat;
    }

    /**
     * Borra la carpeta. Sus esbirros NO se borran: se mudan a la general, que para
     * eso esta. Perder tropa por vaciar una carpeta seria una trampa fea.
     */
    public void deleteCategory(MinionCategory cat) {
        if (cat.isGeneral()) return;
        categories.remove(cat.id());
        String destino = general().id();
        for (MinionType t : types.values()) {
            if (t.categoryId().equals(cat.id())) t.categoryId(destino);
        }
        save();
    }

    /** Cuantos esbirros viven en esa carpeta. */
    public List<MinionType> typesOf(String categoryId) {
        List<MinionType> out = new ArrayList<>();
        for (MinionType t : types.values()) {
            if (t.categoryId().equals(categoryId)) out.add(t);
        }
        return out;
    }

    /** Un id libre a partir del nombre escrito: minusculas y sin rarezas. */
    private String freeId(String display, String fallback, java.util.Set<String> taken) {
        String base = display.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9ñ]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) base = fallback;
        String id = base;
        int n = 2;
        while (taken.contains(id)) id = base + "-" + n++;
        return id;
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
        return createType(display, MinionCategory.GENERAL);
    }

    /** Crea el tipo dentro de una carpeta concreta: la que estaba abierta. */
    public MinionType createType(String display, String categoryId) {
        String id = freeId(display, "esbirro", types.keySet());
        MinionType type = new MinionType(id, display);
        type.categoryId(categories.containsKey(categoryId) ? categoryId : general().id());
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
        // Las carpetas TAMBIEN se vacian. Sin esto, una carpeta borrada del yml
        // seguia viva en memoria despues de un /esb reload y el menu enseñaba
        // carpetas fantasma que ya no existian en disco.
        categories.clear();
        types.clear();
        spawners.clear();
        file = new File(plugin.getDataFolder(), "esbirros.yml");
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection csec = yml.getConfigurationSection("categorias");
        if (csec != null) {
            for (String id : csec.getKeys(false)) {
                ConfigurationSection c = csec.getConfigurationSection(id);
                if (c == null) continue;
                MinionCategory cat = new MinionCategory(id, c.getString("nombre", id));
                org.bukkit.Material icon = org.bukkit.Material.matchMaterial(c.getString("icono", "CHEST"));
                if (icon != null) cat.icon(icon);
                cat.colorRgb(c.getInt("color", 0xFFD966));
                categories.put(id, cat);
            }
        }

        ConfigurationSection tsec = yml.getConfigurationSection("esbirros");
        if (tsec != null) {
            for (String id : tsec.getKeys(false)) {
                ConfigurationSection s = tsec.getConfigurationSection(id);
                if (s == null) continue;
                MinionType type = new MinionType(id, s.getString("nombre", id));
                type.colorRgb(s.getInt("color", 0xFFFFFF));
                type.bold(s.getBoolean("negrita", false));
                type.categoryId(s.getString("categoria", MinionCategory.GENERAL));
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
                cargarPresencia(type, s.getConfigurationSection("presencia"));
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
        // Un esbirro cuya carpeta no existe (borrada a mano en el yml) se muda a la
        // general en vez de desaparecer del menu.
        for (MinionType t : types.values()) {
            if (!categories.containsKey(t.categoryId())) t.categoryId(general().id());
        }
        if (!types.isEmpty() || !categories.isEmpty()) general();

        plugin.getLogger().info("Esbirros cargados: " + categories.size() + " carpeta(s), "
                + types.size() + " tipo(s), " + spawners.size() + " generador(es).");
    }

    public void save() {
        if (file == null) file = new File(plugin.getDataFolder(), "esbirros.yml");
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "Esbirros de Anomaly: la tropa que puebla las mazmorras.",
                "Se edita desde el menú (/anomaly menú -> Esbirros), pero se puede tocar a mano.",
                "",
                "La vida a nivel N es  vida-base * (1 + vida-por-nivel * (N - 1)).",
                "El daño es un multiplicador sobre el golpe de fábrica del bicho:",
                "  x daño-base * (1 + daño-por-nivel * (N - 1)).",
                "",
                "Los esbirros se organizan en CARPETAS (categorias): la mazmorra o el",
                "proposito al que sirven. Cada una elige icono y color; borrarla no borra",
                "su tropa, la muda a la carpeta general.",
                "",
                "Cada generador tiene SU rango de nivel: el mismo esbirro puede ser 5-10",
                "en una sala y 20-30 en otra. El botín se configura en drops.yml, en la",
                "sección 'esbirro-<id>'.",
                "",
                "negrita: si el nombre del holograma va en negrita (por defecto, no).",
                "",
                "habilidades: rasgos que se encienden y se apagan desde el menú.",
                "  flecha-pesada  cada tercera flecha pega el doble",
                "  ágil           se mueve un 25% más rápido",
                "  flecha-helada  sus flechas dejan lentitud 3 segundos",
                "  venenoso       sus golpes dejan veneno 4 segundos",
                "  ígneo          deja ardiendo 4 segundos al que golpea",
                "  acorazado      recibe un 35% menos de daño",
                "  espinas        devuelve un 25% del daño cuerpo a cuerpo",
                "  berserk        bajo el 30% de vida pega un 50% más",
                "  curandero      cura a los esbirros de alrededor cada 3 s",
                "  alarma         al ser golpeado manda a los suyos contra el atacante",
                "  división       al morir se parte en dos crías de la mitad de nivel"));
        for (MinionCategory c : categories.values()) {
            String base = "categorias." + c.id();
            yml.set(base + ".nombre", c.display());
            yml.set(base + ".icono", c.icon().name());
            yml.set(base + ".color", c.colorRgb());
        }
        for (MinionType t : types.values()) {
            String base = "esbirros." + t.id();
            yml.set(base + ".categoria", t.categoryId());
            yml.set(base + ".nombre", t.display());
            yml.set(base + ".color", t.colorRgb());
            yml.set(base + ".negrita", t.boldFlag());
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
            guardarPresencia(yml, base + ".presencia", t.presence());
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

    /* ------------------------------------------------------------- presencia */

    /**
     * Lee el bloque `presencia` de un esbirro: equipo, aura, contorno y sonidos.
     *
     * Todo es opcional y todo cae de pie: un material que ya no existe, una
     * particula que esta version no trae o un color mal escrito se ignoran en
     * silencio en lugar de dejar el esbirro sin cargar.
     */
    private void cargarPresencia(MinionType type, ConfigurationSection s) {
        if (s == null) return;
        MinionPresence p = type.presence();
        p.featured(s.getBoolean("destacado", false));
        p.gearColor(s.getInt("color-equipo", -1));
        p.auraName(s.getString("aura", ""));
        p.auraColor(s.getInt("color-aura", -1));
        p.auraEvery(s.getInt("aura-cada", 4));
        p.spawnSound(s.getString("sonido", ""));
        p.ambientSound(s.getString("sonido-ambiente", ""));
        p.outline(NamedTextColor.NAMES.value(
                s.getString("contorno", "").trim().toLowerCase(java.util.Locale.ROOT)));

        ConfigurationSection eq = s.getConfigurationSection("equipo");
        if (eq == null) return;
        for (String key : eq.getKeys(false)) {
            MinionPresence.Slot slot = MinionPresence.Slot.byKey(key);
            if (slot == null) continue;
            Material m = Material.matchMaterial(eq.getString(key, ""));
            if (m != null && m.isItem()) p.gear().put(slot, m);
        }
    }

    /** Solo escribe lo que tiene valor: un esbirro de a pie no ensucia el fichero. */
    private void guardarPresencia(YamlConfiguration yml, String base, MinionPresence p) {
        if (!p.any()) {
            yml.set(base, null);
            return;
        }
        yml.set(base + ".destacado", p.featured());
        yml.set(base + ".aura", p.auraName().isEmpty() ? null : p.auraName());
        yml.set(base + ".color-aura", p.auraColor() < 0 ? null : p.auraColor());
        yml.set(base + ".aura-cada", p.auraName().isEmpty() ? null : p.auraEvery());
        yml.set(base + ".color-equipo", p.gearColor() < 0 ? null : p.gearColor());
        yml.set(base + ".sonido", p.spawnSound().isEmpty() ? null : p.spawnSound());
        yml.set(base + ".sonido-ambiente", p.ambientSound().isEmpty() ? null : p.ambientSound());
        yml.set(base + ".contorno", p.outline() == null ? null : p.outline().toString());
        yml.set(base + ".equipo", null);
        for (java.util.Map.Entry<MinionPresence.Slot, Material> e : p.gear().entrySet()) {
            yml.set(base + ".equipo." + e.getKey().key(), e.getValue().name());
        }
    }
}
