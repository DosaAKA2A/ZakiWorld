package net.ederus.edm.anomaly.minions;

import net.ederus.edm.anomaly.AnomalyPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * El almacen de esbirros: carpetas, tipos y sus generadores (velas). Vive en
 * plugins/EDM/anomaly/, que es persistente entre despliegues, al estilo de
 * MythicMobs: un fichero por esbirro.
 *
 *   Esbirros/<carpeta>/_carpeta.yml   nombre, icono y color de la carpeta
 *   Esbirros/<carpeta>/<id>.yml       la ficha del esbirro y sus velas plantadas
 *   Skills/Esbirros/<id>.yml          sus rasgos: encendido/apagado y sus numeros
 *
 * La carpeta de un esbirro es el directorio en el que esta su fichero: moverlo de
 * directorio y hacer /esb reload lo cambia de carpeta. Se edita desde el menu,
 * pero todo se puede tocar a mano. El esbirros.yml de antes se migra solo.
 */
public final class MinionRegistry {

    private final AnomalyPlugin plugin;
    private final Map<String, MinionCategory> categories = new LinkedHashMap<>();
    private final Map<String, MinionType> types = new LinkedHashMap<>();
    private final Map<String, MinionSpawner> spawners = new LinkedHashMap<>();

    /** El fichero del que salio (o al que se escribio) cada tipo: si cambia de
     *  carpeta o se borra, el viejo se quita del disco en el siguiente save(). */
    private Map<String, File> archivos = new HashMap<>();
    /** Las carpetas que tienen directorio en disco, para quitar las borradas. */
    private final Set<String> carpetasEnDisco = new LinkedHashSet<>();

    /** El nombre de la ficha de cada carpeta; ningun esbirro puede llamarse asi. */
    private static final String FICHA_CARPETA = "_carpeta.yml";

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
        // El id es tambien nombre de fichero: "con" o "nul" no valen en Windows.
        while (taken.contains(id) || !nombreValido(id)) id = base + "-" + n++;
        return id;
    }

    private static final Set<String> RESERVADOS_WINDOWS = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    /** Si el id sirve tal cual como nombre de fichero o directorio, en Windows y en Linux. */
    static boolean nombreValido(String id) {
        if (id == null || id.isBlank() || id.startsWith("_") || id.startsWith(".")) return false;
        if (id.endsWith(".") || id.endsWith(" ")) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < 32 || "\\/:*?\"<>|".indexOf(c) >= 0) return false;
        }
        return !RESERVADOS_WINDOWS.contains(id.toLowerCase(Locale.ROOT));
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

    private File carpetaEsbirros() {
        return new File(plugin.getDataFolder(), "Esbirros");
    }

    private File carpetaSkills() {
        return new File(new File(plugin.getDataFolder(), "Skills"), "Esbirros");
    }

    private File ficheroSkills(String typeId) {
        return new File(carpetaSkills(), typeId + ".yml");
    }

    public void load() {
        // Las carpetas TAMBIEN se vacian. Sin esto, una carpeta borrada del disco
        // seguia viva en memoria despues de un /esb reload y el menu enseñaba
        // carpetas fantasma que ya no existian.
        categories.clear();
        types.clear();
        spawners.clear();
        archivos = new HashMap<>();
        carpetasEnDisco.clear();

        File dir = carpetaEsbirros();
        File viejo = new File(plugin.getDataFolder(), "esbirros.yml");
        boolean migrar = viejo.isFile() && !dir.exists();
        if (migrar) {
            cargarViejo(viejo);
        } else {
            if (viejo.isFile()) {
                plugin.getLogger().warning("Hay un esbirros.yml viejo junto a la carpeta Esbirros/: manda la "
                        + "carpeta y el fichero viejo se ignora. Borralo o renombralo cuando lo hayas revisado.");
            }
            if (dir.isDirectory()) cargarCarpetas(dir);
        }

        // Un esbirro cuya carpeta no existe se muda a la general en vez de
        // desaparecer del menu.
        for (MinionType t : types.values()) {
            if (!categories.containsKey(t.categoryId())) t.categoryId(general().id());
        }
        if (!types.isEmpty() || !categories.isEmpty()) general();

        if (migrar) {
            if (escribir()) {
                String base = "esbirros.yml.migrado-" + LocalDate.now();
                File destino = new File(plugin.getDataFolder(), base);
                for (int n = 2; destino.exists(); n++) destino = new File(plugin.getDataFolder(), base + "-" + n);
                if (viejo.renameTo(destino)) {
                    plugin.getLogger().info("Migrados " + types.size() + " esbirros en " + categories.size()
                            + " carpetas a Esbirros/ (" + spawners.size() + " vela(s)); el fichero viejo queda como "
                            + destino.getName() + ".");
                } else {
                    plugin.getLogger().warning("Migrados " + types.size() + " esbirros en " + categories.size()
                            + " carpetas a Esbirros/, pero no se pudo renombrar esbirros.yml: renombralo a mano"
                            + " (ya manda la carpeta).");
                }
            } else {
                plugin.getLogger().severe("La migracion de esbirros.yml a Esbirros/ no se completo; revisa los"
                        + " errores de arriba. El fichero viejo se deja como esta.");
            }
        } else {
            // Un esbirro sin fichero de rasgos (creado a mano) lo recibe ya, con
            // los numeros de serie, para que se vea todo lo que se puede tocar.
            for (MinionType t : types.values()) {
                if (!ficheroSkills(t.id()).exists()) escribirSkills(t);
            }
        }

        plugin.getLogger().info("Esbirros cargados: " + categories.size() + " carpeta(s), "
                + types.size() + " tipo(s), " + spawners.size() + " generador(es).");
    }

    /** Lee Esbirros/: cada directorio es una carpeta y cada .yml de dentro un esbirro. */
    private void cargarCarpetas(File dir) {
        File[] subs = dir.listFiles(File::isDirectory);
        if (subs != null) {
            Arrays.sort(subs, Comparator.comparing(File::getName));
            for (File sub : subs) {
                String catId = sub.getName();
                MinionCategory cat = new MinionCategory(catId, catId);
                File ficha = new File(sub, FICHA_CARPETA);
                if (ficha.isFile()) {
                    YamlConfiguration c = YamlConfiguration.loadConfiguration(ficha);
                    cat.display(c.getString("nombre", catId));
                    Material icon = Material.matchMaterial(c.getString("icono", "CHEST"));
                    if (icon != null) cat.icon(icon);
                    cat.colorRgb(c.getInt("color", 0xFFD966));
                } else if (cat.isGeneral()) {
                    cat.display("Sin clasificar");
                    cat.icon(Material.BARREL);
                    cat.colorRgb(0xA6ACB9);
                }
                categories.put(catId, cat);
                carpetasEnDisco.add(catId);
                cargarFichas(sub, catId);
            }
        }
        // Una ficha suelta en Esbirros/, fuera de toda carpeta: va a la general y
        // el siguiente guardado la mete en Esbirros/general/.
        cargarFichas(dir, MinionCategory.GENERAL);
    }

    private void cargarFichas(File dir, String catId) {
        File[] fichas = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".yml")
                && !f.getName().equals(FICHA_CARPETA));
        if (fichas == null) return;
        Arrays.sort(fichas, Comparator.comparing(File::getName));
        for (File f : fichas) {
            String id = f.getName().substring(0, f.getName().length() - ".yml".length());
            if (types.containsKey(id)) {
                plugin.getLogger().warning("Esbirro repetido: " + id + " esta en " + archivos.get(id).getPath()
                        + " y en " + f.getPath() + ". Se usa el primero; quita uno de los dos.");
                continue;
            }
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
            MinionType type = leerTipo(id, yml);
            type.categoryId(catId); // el directorio manda, traiga lo que traiga el fichero
            if (!cargarSkills(type)) {
                // Sin fichero de rasgos vale la lista de antes escrita a mano en la ficha.
                for (String raw : yml.getStringList("habilidades")) {
                    MinionAbility ability = MinionAbility.byId(raw);
                    if (ability != null) type.abilities().add(ability);
                }
            }
            types.put(id, type);
            archivos.put(id, f);

            ConfigurationSection velas = yml.getConfigurationSection("velas");
            if (velas == null) continue;
            for (String sid : velas.getKeys(false)) {
                ConfigurationSection v = velas.getConfigurationSection(sid);
                if (v == null) continue;
                if (spawners.containsKey(sid)) {
                    plugin.getLogger().warning("Vela repetida: " + sid + " (en " + f.getName() + "). Se ignora.");
                    continue;
                }
                spawners.put(sid, leerVela(sid, id, v));
            }
        }
    }

    /** Los rasgos de Skills/Esbirros/<id>.yml. False si ese fichero no existe. */
    private boolean cargarSkills(MinionType type) {
        File f = ficheroSkills(type.id());
        if (!f.isFile()) return false;
        ConfigurationSection rasgos = YamlConfiguration.loadConfiguration(f).getConfigurationSection("rasgos");
        if (rasgos == null) return true;
        for (String key : rasgos.getKeys(false)) {
            MinionAbility a = MinionAbility.byId(key);
            ConfigurationSection r = rasgos.getConfigurationSection(key);
            if (a == null || r == null) {
                plugin.getLogger().warning("Rasgo desconocido '" + key + "' en Skills/Esbirros/"
                        + f.getName() + ": se ignora.");
                continue;
            }
            if (r.getBoolean("activa", false)) type.abilities().add(a);
            for (MinionAbility.Param p : a.params()) {
                if (r.isInt(p.key()) || r.isDouble(p.key()) || r.isLong(p.key())) {
                    type.setParam(a, p.key(), r.getDouble(p.key()));
                }
            }
        }
        return true;
    }

    /**
     * El formato de antes, todo en un esbirros.yml. Solo se lee para migrarlo; un
     * id que no sirva como nombre de fichero se arregla (y se avisa).
     */
    private void cargarViejo(File file) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);

        Map<String, String> carpetasRenombradas = new HashMap<>();
        ConfigurationSection csec = yml.getConfigurationSection("categorias");
        if (csec != null) {
            for (String raw : csec.getKeys(false)) {
                ConfigurationSection c = csec.getConfigurationSection(raw);
                if (c == null) continue;
                String id = idSeguro(raw, "carpeta", categories.keySet());
                carpetasRenombradas.put(raw, id);
                MinionCategory cat = new MinionCategory(id, c.getString("nombre", raw));
                Material icon = Material.matchMaterial(c.getString("icono", "CHEST"));
                if (icon != null) cat.icon(icon);
                cat.colorRgb(c.getInt("color", 0xFFD966));
                categories.put(id, cat);
            }
        }

        Map<String, String> tiposRenombrados = new HashMap<>();
        ConfigurationSection tsec = yml.getConfigurationSection("esbirros");
        if (tsec != null) {
            for (String raw : tsec.getKeys(false)) {
                ConfigurationSection s = tsec.getConfigurationSection(raw);
                if (s == null) continue;
                String id = idSeguro(raw, "esbirro", types.keySet());
                tiposRenombrados.put(raw, id);
                MinionType type = leerTipo(id, s);
                String cat = s.getString("categoria", MinionCategory.GENERAL);
                type.categoryId(carpetasRenombradas.getOrDefault(cat, cat));
                for (String h : s.getStringList("habilidades")) {
                    MinionAbility ability = MinionAbility.byId(h);
                    if (ability != null) type.abilities().add(ability);
                }
                types.put(id, type);
            }
        }

        ConfigurationSection gsec = yml.getConfigurationSection("generadores");
        if (gsec != null) {
            for (String sid : gsec.getKeys(false)) {
                ConfigurationSection s = gsec.getConfigurationSection(sid);
                if (s == null) continue;
                String typeId = s.getString("esbirro", "");
                typeId = tiposRenombrados.getOrDefault(typeId, typeId);
                if (!types.containsKey(typeId)) continue; // huerfano: su tipo ya no existe
                spawners.put(sid, leerVela(sid, typeId, s));
            }
        }
    }

    /** El id tal cual si vale como nombre de fichero; si no, uno limpio y un aviso. */
    private String idSeguro(String raw, String fallback, Set<String> taken) {
        if (nombreValido(raw) && !taken.contains(raw)) return raw;
        String id = freeId(raw, fallback, taken);
        plugin.getLogger().warning("El id '" + raw + "' no vale como nombre de fichero: pasa a ser '" + id
                + "'. Si es un esbirro, su tabla de botin esbirro-" + raw + " hay que renombrarla a mano.");
        return id;
    }

    /** La ficha de un esbirro, venga del fichero nuevo o de la seccion del viejo. */
    private MinionType leerTipo(String id, ConfigurationSection s) {
        MinionType type = new MinionType(id, s.getString("nombre", id));
        type.colorRgb(s.getInt("color", 0xFFFFFF));
        type.bold(s.getBoolean("negrita", false));
        type.tier(s.getInt("tier", 0));
        try {
            type.entity(EntityType.valueOf(s.getString("entidad", "ZOMBIE")));
        } catch (IllegalArgumentException ignored) {
        }
        type.baseHealth(s.getDouble("vida-base", 20));
        type.healthGrowth(s.getDouble("vida-por-nivel", 0.35));
        type.baseDamage(s.getDouble("dano-base", 1.0));
        type.damageGrowth(s.getDouble("dano-por-nivel", 0.10));
        type.mobcoins(s.getInt("mobcoins-min", 0), s.getInt("mobcoins-max", 0));
        type.wandMinLevel(s.getInt("vela.nivel-min", 1));
        type.wandMaxLevel(s.getInt("vela.nivel-max", 5));
        type.wandIntervalSeconds(s.getInt("vela.intervalo-segundos", 30));
        type.wandMaxAlive(s.getInt("vela.tope-vivos", 3));
        type.wandActivationRadius(s.getInt("vela.radio-activacion", 32));
        cargarPresencia(type, s.getConfigurationSection("presencia"));
        return type;
    }

    private MinionSpawner leerVela(String id, String typeId, ConfigurationSection s) {
        MinionSpawner sp = new MinionSpawner(id, typeId,
                s.getString("mundo", "world"), s.getInt("x"), s.getInt("y"), s.getInt("z"));
        sp.minLevel(s.getInt("nivel-min", 1));
        sp.maxLevel(s.getInt("nivel-max", 5));
        sp.intervalSeconds(s.getInt("intervalo-segundos", 30));
        sp.maxAlive(s.getInt("tope-vivos", 3));
        sp.activationRadius(s.getInt("radio-activacion", 32));
        sp.enabled(s.getBoolean("activa", s.getBoolean("activo", true)));
        return sp;
    }

    /**
     * Escribe todas las fichas (son pocas) y quita del disco lo que ya no toca:
     * el fichero de un esbirro que cambio de carpeta, el de uno borrado (y sus
     * rasgos) y la ficha de una carpeta borrada.
     */
    public void save() {
        escribir();
    }

    private boolean escribir() {
        boolean ok = true;
        File dir = carpetaEsbirros();
        if (!types.isEmpty()) general();

        for (MinionCategory c : categories.values()) {
            File cdir = new File(dir, c.id());
            cdir.mkdirs();
            YamlConfiguration yml = new YamlConfiguration();
            yml.options().setHeader(List.of(
                    "Carpeta de esbirros: " + c.display() + ".",
                    "Cada .yml de este directorio es un esbirro de esta carpeta: moverlo a otro",
                    "directorio (y /esb reload) lo cambia de carpeta. Borrar la carpeta desde el",
                    "menú no borra su tropa: la muda a general/.",
                    "",
                    "nombre: como se ve en /esb.  icono: cualquier objeto (GOLD_ORE...).",
                    "color: RGB en decimal."));
            yml.set("nombre", c.display());
            yml.set("icono", c.icon().name());
            yml.set("color", c.colorRgb());
            ok &= guardar(yml, new File(cdir, FICHA_CARPETA));
            carpetasEnDisco.add(c.id());
        }

        Map<String, File> nuevos = new HashMap<>();
        for (MinionType t : types.values()) {
            File destino = new File(new File(dir, categoryOf(t).id()), t.id() + ".yml");
            ok &= guardar(fichaDe(t), destino);
            ok &= escribirSkills(t);
            nuevos.put(t.id(), destino);
            File antes = archivos.get(t.id());
            if (antes != null && !antes.getAbsoluteFile().equals(destino.getAbsoluteFile())) borrar(antes);
        }
        // Los esbirros borrados: fuera su ficha y sus rasgos. El botin es cosa de Drops/.
        for (Map.Entry<String, File> e : archivos.entrySet()) {
            if (types.containsKey(e.getKey())) continue;
            borrar(e.getValue());
            borrar(ficheroSkills(e.getKey()));
        }
        archivos = nuevos;

        // Las carpetas borradas: fuera su ficha, y el directorio si se quedo vacio.
        for (Iterator<String> it = carpetasEnDisco.iterator(); it.hasNext(); ) {
            String catId = it.next();
            if (categories.containsKey(catId)) continue;
            File cdir = new File(dir, catId);
            borrar(new File(cdir, FICHA_CARPETA));
            String[] resto = cdir.list();
            if (resto != null && resto.length == 0) cdir.delete();
            it.remove();
        }
        return ok;
    }

    private YamlConfiguration fichaDe(MinionType t) {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "Esbirro " + t.display() + " (id " + t.id() + "). Se edita desde /esb, pero se puede tocar",
                "a mano y releer con /esb reload. Su carpeta es el directorio en el que está.",
                "",
                "La vida a nivel N es  vida-base * (1 + vida-por-nivel * (N - 1)).",
                "El daño es un multiplicador sobre el golpe de fábrica del bicho:",
                "  x dano-base * (1 + dano-por-nivel * (N - 1)).",
                "negrita: si el nombre del holograma va en negrita (por defecto, no).",
                "tier: el piso de la mina, del 1 al 5 (0 = no es de la mina). Cada baja sube",
                "  el contador esbirros_tierN de ServerVariables (camino PvE del rankup).",
                "mobcoins-min / mobcoins-max: lo que paga al morir (0 = nada por aquí).",
                "vela: lo que hereda cada vela nueva que se saque desde el menú.",
                "velas: los generadores ya plantados de este esbirro, cada uno con SU nivel.",
                "",
                "Sus rasgos están en Skills/Esbirros/" + t.id() + ".yml y su botín en",
                "Drops/Esbirros/" + t.id() + ".yml."));
        yml.set("nombre", t.display());
        yml.set("color", t.colorRgb());
        yml.set("negrita", t.boldFlag());
        yml.set("entidad", t.entity().name());
        yml.set("tier", t.tier());
        yml.set("vida-base", t.baseHealth());
        yml.set("vida-por-nivel", t.healthGrowth());
        yml.set("dano-base", t.baseDamage());
        yml.set("dano-por-nivel", t.damageGrowth());
        yml.set("mobcoins-min", t.mobcoinsMin());
        yml.set("mobcoins-max", t.mobcoinsMax());
        yml.set("vela.nivel-min", t.wandMinLevel());
        yml.set("vela.nivel-max", t.wandMaxLevel());
        yml.set("vela.intervalo-segundos", t.wandIntervalSeconds());
        yml.set("vela.tope-vivos", t.wandMaxAlive());
        yml.set("vela.radio-activacion", t.wandActivationRadius());
        guardarPresencia(yml, "presencia", t.presence());
        for (MinionSpawner s : spawnersOf(t.id())) {
            String base = "velas." + s.id();
            yml.set(base + ".mundo", s.worldName());
            yml.set(base + ".x", s.x());
            yml.set(base + ".y", s.y());
            yml.set(base + ".z", s.z());
            yml.set(base + ".nivel-min", s.minLevel());
            yml.set(base + ".nivel-max", s.maxLevel());
            yml.set(base + ".intervalo-segundos", s.intervalSeconds());
            yml.set(base + ".tope-vivos", s.maxAlive());
            yml.set(base + ".radio-activacion", s.activationRadius());
            yml.set(base + ".activa", s.enabled());
        }
        return yml;
    }

    /** Skills/Esbirros/<id>.yml: todos los rasgos, encendidos o no, con sus numeros. */
    private boolean escribirSkills(MinionType t) {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "Rasgos del esbirro " + t.display() + ". Se encienden desde /esb o aquí; los números se editan aquí.",
                "Tras tocarlo a mano, /esb reload. Los esbirros que ya están vivos no cambian."));
        for (MinionAbility a : MinionAbility.values()) {
            String base = "rasgos." + a.id();
            yml.set(base + ".activa", t.has(a));
            yml.setComments(base, List.of(a.display() + ": " + a.what()));
            for (MinionAbility.Param p : a.params()) {
                double v = t.param(a, p.key());
                // Los enteros se escriben sin ".0": "segundos: 4", no "segundos: 4.0".
                Object valor = v == Math.rint(v) && Math.abs(v) < 1e9 ? (Object) (long) v : (Object) v;
                yml.set(base + "." + p.key(), valor);
                yml.setInlineComments(base + "." + p.key(), List.of(p.desc()));
            }
        }
        return guardar(yml, ficheroSkills(t.id()));
    }

    private boolean guardar(YamlConfiguration yml, File f) {
        try {
            yml.save(f);
            return true;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "No se pudo guardar " + f.getPath(), ex);
            return false;
        }
    }

    private void borrar(File f) {
        if (f.exists() && !f.delete()) {
            plugin.getLogger().warning("No se pudo borrar " + f.getPath() + ": quitalo a mano.");
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
    private void guardarPresencia(ConfigurationSection yml, String base, MinionPresence p) {
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
