package net.ederus.calamity.hardcore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.ederus.edm.comun.Bitacora;
import net.ederus.edm.comun.Compat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Calamity 1.3.3 · Que los objetos de Calamity hagan DE VERDAD lo que promete su lore (auditoria de objetos,
 * 2026-09-27: "si no, para que nos sirve ese lore"). Dos herramientas contra los items REALES de MMOItems:
 *
 *   /calamidad autotest objetos-reales  genera cada objeto con la plantilla del servidor (lo mismo que mi give) y
 *                                        mira en el item sus stats (MMOITEMS_<ID> de custom_data), tier, set,
 *                                        encantamientos, efectos, huecos y mejora; en MMOItems, cada set con sus
 *                                        bonos acumulados por nivel y la habilidad del [5] con sus parametros; y lo
 *                                        de alrededor que puede dejar un bono en nada (el permiso de habilidades de
 *                                        MMOItems, el robo de vida de MythicLib, su critico base).
 *   /calamidad objetos stats <jugador>  con el equipo puesto: lo que MythicLib le tiene apuntado de cada pieza de
 *                                        Calamity y de su set, contra lo que promete el lore-tag.
 *
 * Lo esperado es objetos-calamity.yml, que saca gen_config.py de los mismos datos que los ficheros de MMOItems (va
 * en el jar; uno en plugins/Calamity/ manda sobre el del jar, para comprobar cifras nuevas sin jar nuevo). El
 * TIPO.ID de cada objeto es el de la config (entregas.mmo, forja.piezas) si la trae: si un objeto cambia de tipo en
 * el servidor, se comprueba con su id nuevo.
 */
final class ObjetosReales {

    static final String FICHERO = "objetos-calamity.yml";
    private static final double EPS = 1e-6;
    private static final String OK = "✔ ", MAL = "✘ ";

    /** Las casillas que mira /calamidad objetos stats: su hueco en MythicLib y como se dice. */
    private static final String[][] HUECOS = {{"HEAD", "Cabeza"}, {"CHEST", "Pecho"}, {"LEGS", "Piernas"},
            {"FEET", "Pies"}, {"MAIN_HAND", "Mano"}, {"OFF_HAND", "Otra mano"}};

    /** Los atributos vanilla en los que MythicLib vuelca lo de MMOItems (lo que el jugador nota de verdad). */
    private static final String[][] ATRIBUTOS = {{"max_health", "Vida máxima"}, {"armor", "Armadura"},
            {"armor_toughness", "Dureza"}, {"knockback_resistance", "Anti-empuje"}, {"attack_damage", "Daño"},
            {"attack_speed", "Velocidad de ataque"}};

    /** Como se dice cada stat en el chat de staff. */
    private static final Map<String, String> NOMBRE_STAT = Map.ofEntries(
            Map.entry("MAX_HEALTH", "vida"), Map.entry("ARMOR", "armadura"), Map.entry("ARMOR_TOUGHNESS", "dureza"),
            Map.entry("ATTACK_DAMAGE", "daño"), Map.entry("ATTACK_SPEED", "vel. de ataque"),
            Map.entry("CRITICAL_STRIKE_CHANCE", "crítico %"), Map.entry("CRITICAL_STRIKE_POWER", "potencia de crítico %"),
            Map.entry("PVE_DAMAGE", "PvE %"), Map.entry("PVP_DAMAGE", "PvP %"), Map.entry("UNDEAD_DAMAGE", "no-muertos %"),
            Map.entry("LIFESTEAL", "robo de vida %"), Map.entry("DAMAGE_REDUCTION", "reducción de daño %"),
            Map.entry("KNOCKBACK_RESISTANCE", "anti-empuje"), Map.entry("RESTORE_HEALTH", "cura"),
            Map.entry("ITEM_COOLDOWN", "espera s"));

    private final Hardcore hc;

    ObjetosReales(Hardcore hc) {
        this.hc = hc;
        Autotest.registrar("objetos-reales", this::autotest);
        Subcomandos.lw().registrar("objetos",
                "objetos stats <jugador> | probar: lo que dan DE VERDAD las piezas y sets de Calamity",
                "ederus.mundos", this::comando, args -> switch (args.length) {
                    case 2 -> List.of("stats", "probar");
                    case 3 -> args[1].equalsIgnoreCase("stats") ? Entregas.nombresConectados() : List.<String>of();
                    default -> List.<String>of();
                });
    }

    // ================================================================= lo esperado

    /** Lo esperado: plugins/Calamity/objetos-calamity.yml si existe; si no, el del jar. Null si no hay ninguno. */
    YamlConfiguration esperado() {
        String texto = null;
        File f = new File(hc.plugin().getDataFolder(), FICHERO);
        try {
            if (f.isFile()) {
                texto = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            } else {
                try (InputStream in = hc.plugin().getResource(FICHERO)) {
                    if (in != null) texto = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return leer(texto);
    }

    /** Las claves llevan punto (ARMOR.YELMO_DE_CALAMIDAD): se lee con "/" como separador de rutas. */
    static YamlConfiguration leer(String texto) {
        if (texto == null) return null;
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        try {
            y.loadFromString(texto);
        } catch (InvalidConfigurationException e) {
            return null;
        }
        return y.isConfigurationSection("objetos") && y.isConfigurationSection("sets") ? y : null;
    }

    /** El TIPO.ID con el que el servidor crea el objeto: el de la config si lo trae (entregas.mmo, forja.piezas). */
    String idReal(String clave, ConfigurationSection o) {
        String pieza = o.getString("pieza");
        if (pieza != null) {
            String c = hc.cfg().getString("entregas.mmo." + pieza);
            if (c == null || c.isBlank()) c = hc.cfg().getString("forja.piezas." + pieza);
            if (c != null && !c.isBlank()) return c.trim();
        }
        return clave;
    }

    /** Los parametros de habilidad que promete algun set (heal, cooldown...). */
    private static Set<String> parametros(ConfigurationSection sets) {
        Set<String> out = new LinkedHashSet<>(List.of("cooldown"));
        for (String sid : sets.getKeys(false)) {
            ConfigurationSection habs = sets.getConfigurationSection(sid + "/habilidades");
            if (habs == null) continue;
            for (String nivel : habs.getKeys(false)) {
                ConfigurationSection porNivel = habs.getConfigurationSection(nivel);
                if (porNivel == null) continue;
                for (String hk : porNivel.getKeys(false)) {
                    ConfigurationSection h = porNivel.getConfigurationSection(hk);
                    if (h == null) continue;
                    for (String p : h.getKeys(false)) if (!p.equals("tipo") && !p.equals("modo")) out.add(p);
                }
            }
        }
        return out;
    }

    private static int nivelMaximo(ConfigurationSection s) {
        int max = 2;
        for (String rama : List.of("bonos", "habilidades")) {
            ConfigurationSection c = s.getConfigurationSection(rama);
            if (c == null) continue;
            for (String k : c.getKeys(false)) {
                try {
                    max = Math.max(max, Integer.parseInt(k));
                } catch (NumberFormatException ignorado) {
                    // un nivel que no es un numero no cuenta
                }
            }
        }
        return max;
    }

    // ================================================================= autotest

    private List<String> autotest() {
        YamlConfiguration esp = esperado();
        if (esp == null) return List.of("no hay " + FICHERO + " legible (ni en plugins/Calamity ni en el jar): no hay con que comparar");
        if (!PuenteMmo.disponible()) return List.of("OK sin MMOItems en este servidor: no hay objetos que comprobar");
        Autotest.Hoja h = new Autotest.Hoja();
        ConfigurationSection objetos = esp.getConfigurationSection("objetos");
        ConfigurationSection sets = esp.getConfigurationSection("sets");
        Map<String, ItemStack> creados = new HashMap<>();
        for (String clave : objetos.getKeys(false)) {
            ConfigurationSection o = objetos.getConfigurationSection(clave);
            ItemStack it = o == null ? null : objeto(h, idReal(clave, o), o);
            if (it != null) creados.put(clave, it);
        }
        Set<String> params = parametros(sets);
        for (String sid : sets.getKeys(false)) {
            ConfigurationSection s = sets.getConfigurationSection(sid);
            if (s != null) set(h, sid, s, params, creados, objetos);
        }
        entorno(h, sets);
        return h.lineas();
    }

    private static void si(Autotest.Hoja h, boolean bien, String textoBien, String textoMal) {
        h.ok(bien ? textoBien : textoMal, bien);
    }

    private static void cifra(Autotest.Hoja h, String donde, String que, double promete, double hay) {
        boolean bien = Math.abs(promete - hay) <= EPS;
        si(h, bien, donde + ": " + que + " " + Bitacora.num(hay),
                donde + ": " + que + " vale " + Bitacora.num(hay) + " y promete " + Bitacora.num(promete));
    }

    private static void texto(Autotest.Hoja h, String donde, String que, String promete, String hay) {
        boolean bien = promete == null ? hay == null : promete.equalsIgnoreCase(hay == null ? "" : hay);
        si(h, bien, donde + ": " + que + " " + hay, donde + ": " + que + " es " + hay + " y promete " + promete);
    }

    /** Un objeto: generado con la plantilla real y mirado etiqueta a etiqueta. Devuelve el item, o null si no sale. */
    private ItemStack objeto(Autotest.Hoja h, String id, ConfigurationSection o) {
        String n = o.getString("nombre", id) + " (" + id + ")";
        int punto = id.indexOf('.');
        String tipo = punto > 0 ? id.substring(0, punto) : id, iid = punto > 0 ? id.substring(punto + 1) : "";
        ItemStack it = PuenteMmo.crear(id);
        if (it == null) {
            String por = Boolean.FALSE.equals(LecturaMmo.hayTipo(tipo)) ? "MMOItems no tiene el tipo " + tipo
                    : Boolean.FALSE.equals(LecturaMmo.hayPlantilla(tipo, iid))
                    ? "falta en MMOItems (item/" + tipo.toLowerCase(Locale.ROOT) + ".yml sin " + iid + ")"
                    : "MMOItems no lo sabe crear (mira la consola en /mi reload)";
            h.ok(n + ": " + por + "; es de la fase " + o.getInt("fase", 0) + " y nadie puede recibirlo", false);
            return null;
        }
        h.igual(n + ": enlace", id, PuenteMmo.enlace(it));
        texto(h, n, "material", o.getString("material"), it.getType().name());
        texto(h, n, "tier", o.getString("tier"), PuenteMmo.tier(it));
        String set = LecturaMmo.etiqueta(it, "MMOITEMS_ITEM_SET");
        if (o.getString("set") != null || set != null) texto(h, n, "set", o.getString("set"), set);

        ConfigurationSection st = o.getConfigurationSection("stats");
        if (st != null) {
            for (String s : st.getKeys(false)) {
                if (!LecturaMmo.tiene(it, "MMOITEMS_" + s)) {
                    h.ok(n + ": no lleva " + s + " (promete " + Bitacora.num(st.getDouble(s)) + ")", false);
                } else {
                    cifra(h, n, s, st.getDouble(s), PuenteMmo.stat(it, s));
                }
            }
        }
        ConfigurationSection enc = o.getConfigurationSection("encantamientos");
        if (enc != null) {
            for (String k : enc.getKeys(false)) {
                Enchantment e = ObjetosCalamity.encantamiento(k);
                if (e == null) h.ok(n + ": el encantamiento " + k + " no existe en este servidor", false);
                else cifra(h, n, k, enc.getInt(k), it.getEnchantmentLevel(e));
            }
        }
        ConfigurationSection perm = o.getConfigurationSection("efectos-permanentes");
        if (perm != null) {
            Map<String, Double> hay = new HashMap<>();
            JsonElement j = json(LecturaMmo.etiqueta(it, "MMOITEMS_PERM_EFFECTS"));
            if (j != null && j.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : j.getAsJsonObject().entrySet()) hay.put(efecto(e.getKey()), e.getValue().getAsDouble());
            }
            for (String k : perm.getKeys(false)) cifra(h, n, "efecto permanente " + k, perm.getDouble(k), hay.getOrDefault(k, 0.0));
        }
        ConfigurationSection ef = o.getConfigurationSection("efectos");
        if (ef != null) {
            Map<String, double[]> hay = new HashMap<>();
            JsonElement j = json(LecturaMmo.etiqueta(it, "MMOITEMS_EFFECTS"));
            if (j != null && j.isJsonArray()) {
                for (JsonElement el : j.getAsJsonArray()) {
                    JsonObject x = el.getAsJsonObject();
                    hay.put(efecto(x.get("Type").getAsString()), new double[]{x.get("Level").getAsDouble(), x.get("Duration").getAsDouble()});
                }
            }
            for (String k : ef.getKeys(false)) {
                double[] v = hay.get(k);
                ConfigurationSection e = ef.getConfigurationSection(k);
                if (v == null || e == null) {
                    h.ok(n + ": no da " + k + " al consumirlo", false);
                    continue;
                }
                cifra(h, n, k + " nivel", e.getDouble("nivel"), v[0]);
                cifra(h, n, k + " segundos", e.getDouble("segundos"), v[1]);
            }
        }
        if (o.getBoolean("irrompible", false)) {
            ItemMeta meta = it.getItemMeta();
            si(h, meta != null && meta.isUnbreakable(), n + ": irrompible", n + ": NO es irrompible");
        }
        List<String> huecos = o.getStringList("huecos");
        if (!huecos.isEmpty()) {
            List<String> vacios = new ArrayList<>();
            JsonElement j = json(LecturaMmo.etiqueta(it, "MMOITEMS_GEM_STONES"));
            if (j != null && j.isJsonObject() && j.getAsJsonObject().has("EmptySlots")) {
                for (JsonElement el : j.getAsJsonObject().getAsJsonArray("EmptySlots")) vacios.add(el.getAsString());
            }
            si(h, vacios.equals(huecos), n + ": huecos de gema " + vacios, n + ": huecos de gema " + vacios + " y promete " + huecos);
        }
        if (o.getString("color-gema") != null) {
            texto(h, n, "color de gema", o.getString("color-gema"), LecturaMmo.etiqueta(it, "MMOITEMS_GEM_COLOR"));
        }
        ConfigurationSection mej = o.getConfigurationSection("mejora");
        if (mej != null) {
            JsonElement j = json(LecturaMmo.etiqueta(it, "MMOITEMS_UPGRADE"));
            JsonObject u = j != null && j.isJsonObject() ? j.getAsJsonObject() : null;
            if (u == null) {
                h.ok(n + ": no lleva mejora (el Ascua no le haria nada)", false);
            } else {
                if (mej.getString("plantilla") != null) texto(h, n, "plantilla de mejora", mej.getString("plantilla"), cadena(u, "Template"));
                texto(h, n, "referencia de mejora", mej.getString("referencia"), cadena(u, "Reference"));
                if (mej.isSet("max")) cifra(h, n, "mejoras como mucho", mej.getInt("max"), u.has("Max") ? u.get("Max").getAsDouble() : 0);
            }
        }
        if (o.isSet("apilable")) cifra(h, n, "se apila hasta", o.getInt("apilable"), it.getMaxStackSize());
        if (o.getBoolean("no-comestible", false)) {
            si(h, LecturaMmo.etiquetaSi(it, "MMOITEMS_INEDIBLE"), n + ": no se come (se arrastra sobre la pieza)",
                    n + ": se puede comer y gastarse sin mejorar nada");
        }
        if (o.getBoolean("sin-receta", false)) {
            si(h, LecturaMmo.etiquetaSi(it, "MMOITEMS_DISABLE_CRAFTING"), n + ": no entra en recetas",
                    n + ": entra en recetas vanilla (se gastaria como material)");
        }
        return it;
    }

    /** Un set: tal cual lo cargo MMOItems, nivel a nivel, contra lo que prometen su lore-tag y sus bonos. */
    private void set(Autotest.Hoja h, String sid, ConfigurationSection s, Set<String> params, Map<String, ItemStack> creados,
                     ConfigurationSection objetos) {
        String n = "set " + s.getString("nombre", sid) + " (" + sid + ")";
        Boolean hay = LecturaMmo.haySet(sid);
        if (!Boolean.TRUE.equals(hay)) {
            h.ok(n + ": " + (hay == null ? "no se pudo leer de MMOItems"
                    : "no está cargado en MMOItems (item-sets.yml sin pegar, o MMOItems lo rechazó al cargar: una "
                    + "habilidad que no existe tumba el set entero; mira la consola en /mi reload)")
                    + "; es de la fase " + s.getInt("fase", 0), false);
            return;
        }
        ConfigurationSection bonos = s.getConfigurationSection("bonos");
        ConfigurationSection habs = s.getConfigurationSection("habilidades");
        Map<String, Double> acum = new TreeMap<>();
        List<ConfigurationSection> habsAcum = new ArrayList<>();
        for (int k = 2; k <= nivelMaximo(s); k++) {
            String nivel = String.valueOf(k);
            ConfigurationSection b = bonos == null ? null : bonos.getConfigurationSection(nivel);
            ConfigurationSection hb = habs == null ? null : habs.getConfigurationSection(nivel);
            if (b == null && hb == null) continue;
            if (b != null) for (String stat : b.getKeys(false)) acum.merge(stat, b.getDouble(stat), Double::sum);
            if (hb != null) {
                for (String hk : hb.getKeys(false)) {
                    ConfigurationSection x = hb.getConfigurationSection(hk);
                    if (x != null) habsAcum.add(x);
                }
            }
            String donde = n + " [" + k + "]";
            LecturaMmo.Bonos real = LecturaMmo.bonosSet(sid, k, params);
            if (real == null) {
                h.ok(donde + ": no se pudieron leer sus bonos de MMOItems", false);
                continue;
            }
            for (Map.Entry<String, Double> e : acum.entrySet()) {
                cifra(h, donde, e.getKey(), e.getValue(), real.stats().getOrDefault(e.getKey(), 0.0));
            }
            for (Map.Entry<String, Double> e : real.stats().entrySet()) {
                if (!acum.containsKey(e.getKey())) {
                    h.ok(donde + ": MMOItems da " + e.getKey() + " " + Bitacora.num(e.getValue()) + " y el lore no lo promete", false);
                }
            }
            for (ConfigurationSection x : habsAcum) {
                String tipo = x.getString("tipo", "?");
                String hn = donde + " habilidad " + x.getName() + " (" + tipo + ")";
                LecturaMmo.Habilidad r = null;
                for (LecturaMmo.Habilidad c : real.habilidades()) if (c.tipo().equalsIgnoreCase(tipo)) r = c;
                if (r == null) {
                    h.ok(hn + ": MMOItems no la da", false);
                    continue;
                }
                texto(h, hn, "activador", x.getString("modo"), r.modo());
                for (String p : x.getKeys(false)) {
                    if (!p.equals("tipo") && !p.equals("modo")) cifra(h, hn, p, x.getDouble(p), r.parametros().getOrDefault(p, 0.0));
                }
            }
            if (real.habilidades().size() != habsAcum.size()) {
                h.ok(donde + ": MMOItems da " + real.habilidades().size() + " habilidades y el lore promete " + habsAcum.size(), false);
            }
        }
        si(h, !LecturaMmo.loreSet(sid).isEmpty(), n + ": lleva lore-tag", n + ": sin lore-tag (las piezas no dicen sus bonos)");

        // Y que cada pieza ENSEÑE esas promesas en su lore (MMOItems las pinta donde su lore-format dice #set#).
        List<String> dice = new ArrayList<>();
        for (Map<?, ?> m : s.getMapList("promete")) if (m.get("texto") != null) dice.add(String.valueOf(m.get("texto")));
        for (String clave : s.getStringList("piezas")) {
            ItemStack it = creados.get(clave);
            if (it == null || dice.isEmpty()) continue;
            StringBuilder lore = new StringBuilder();
            List<Component> lineas = it.lore();
            if (lineas != null) for (Component c : lineas) lore.append(Hardcore.plano(c)).append('\n');
            List<String> faltan = new ArrayList<>();
            for (String d : dice) if (!lore.toString().contains(d)) faltan.add(d);
            String pieza = objetos.getString(clave + "/nombre", clave);
            si(h, faltan.isEmpty(), n + ": " + pieza + " enseña en su lore lo que da el set",
                    n + ": " + pieza + " no enseña en su lore " + faltan + " (¿el lore-format de MMOItems sin #set#?)");
        }
    }

    /** Lo que, sin tocar los objetos, puede dejar un bono en nada. */
    private void entorno(Autotest.Hoja h, ConfigurationSection sets) {
        boolean habilidades = false, robo = false, conShift = false;
        for (String sid : sets.getKeys(false)) {
            ConfigurationSection s = sets.getConfigurationSection(sid);
            if (s == null) continue;
            ConfigurationSection hs = s.getConfigurationSection("habilidades");
            if (hs != null) {
                habilidades = true;
                for (String k : hs.getKeys(true)) if (k.endsWith("/modo") && hs.getString(k, "").toUpperCase(Locale.ROOT).startsWith("SHIFT_")) conShift = true;
            }
            ConfigurationSection b = s.getConfigurationSection("bonos");
            if (b != null) for (String k : b.getKeys(false)) if (b.isSet(k + "/LIFESTEAL")) robo = true;
        }
        ConfigurationSection mi = LecturaMmo.configMmoitems();
        if (habilidades && mi != null) {
            si(h, !mi.getBoolean("permissions.abilities", false),
                    "MMOItems no pide permiso para las habilidades de los sets (permissions.abilities: false)",
                    "MMOItems pide permiso para las habilidades (permissions.abilities: true): sin "
                            + "mmoitems.ability.greater_healings nadie tiene la Savia Viva");
        }
        org.bukkit.plugin.Plugin mythic = Bukkit.getPluginManager().getPlugin("MythicLib");
        if (conShift && mythic != null) {
            si(h, !mythic.getConfig().getBoolean("ignore_shift_triggers", false),
                    "MythicLib atiende Shift + clic (ignore_shift_triggers: false)",
                    "MythicLib ignora los activadores con Shift (ignore_shift_triggers: true): la Savia Viva no sale nunca");
        }
        File ml = LecturaMmo.carpeta("MythicLib");
        if (ml == null) return;
        File golpes = new File(ml, "on_hit_effects.yml");
        if (robo && golpes.isFile()) {
            String t;
            try {
                t = Files.readString(golpes.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                t = "";
            }
            si(h, t.contains("lifesteal"), "MythicLib aplica el robo de vida (on_hit_effects.yml)",
                    "on_hit_effects.yml de MythicLib sin lifesteal: el robo de vida del set no cura nada");
        }
        File stats = new File(ml, "stats.yml");
        if (stats.isFile()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(stats);
            double base = y.getDouble("base-stat-value.CRITICAL_STRIKE_POWER", 0);
            si(h, base >= 100, "potencia de crítico base de MythicLib " + Bitacora.num(base) + " (x" + Bitacora.num(base / 100)
                            + "): el critical-strike-power de cada arma se le SUMA en puntos",
                    "MythicLib sin potencia de crítico base (stats.yml base-stat-value.CRITICAL_STRIKE_POWER = "
                            + Bitacora.num(base) + "): un crítico multiplica por " + Bitacora.num(base / 100)
                            + " + la del arma / 100, menos que un golpe normal");
        }
    }

    private static JsonElement json(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return JsonParser.parseString(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String cadena(JsonObject o, String clave) {
        return o.has(clave) && !o.get(clave).isJsonNull() ? o.get(clave).getAsString() : null;
    }

    /** Los nombres viejos de Bukkit que PotionEffectType.getName() aun devuelve para algunos efectos. */
    private static final Map<String, String> EFECTO_VIEJO = Map.of("damage_resistance", "resistance",
            "slow", "slowness", "fast_digging", "haste", "slow_digging", "mining_fatigue", "increase_damage", "strength",
            "heal", "instant_health", "harm", "instant_damage", "jump", "jump_boost", "confusion", "nausea");

    /** "NIGHT_VISION", "DAMAGE_RESISTANCE" o "resistance" -> la clave del efecto (night_vision, resistance). */
    @SuppressWarnings("deprecation")
    static String efecto(String nombre) {
        String n = nombre == null ? "" : nombre.trim().toLowerCase(Locale.ROOT);
        n = EFECTO_VIEJO.getOrDefault(n, n);
        PotionEffectType t = null;
        NamespacedKey k = NamespacedKey.fromString(n.contains(":") ? n : "minecraft:" + n);
        try {
            if (k != null) t = Registry.EFFECT.get(k);
        } catch (Throwable ignorado) {
            // sigue con el nombre viejo
        }
        if (t == null) {
            try {
                t = PotionEffectType.getByName(nombre);
            } catch (Throwable ignorado) {
                // sin efecto con ese nombre
            }
        }
        return t == null ? n : t.getKey().getKey();
    }

    // ================================================================= /calamidad objetos

    private void comando(CommandSender quien, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "probar" -> Subcomandos.lw().ejecutar(quien, new String[]{"autotest", "objetos-reales"});
            case "stats" -> {
                Player p = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : (quien instanceof Player yo ? yo : null);
                if (p == null) {
                    quien.sendMessage(ComandoCalamity.mensaje("Dime un jugador conectado: /calamidad objetos stats <jugador>"));
                    return;
                }
                stats(quien, p);
            }
            default -> quien.sendMessage(ComandoCalamity.mensaje(
                    "Uso: /calamidad objetos stats <jugador> (con el equipo puesto) · /calamidad objetos probar"));
        }
    }

    private static String nombreStat(String stat) {
        return NOMBRE_STAT.getOrDefault(stat, stat.toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    /** Una cifra como se lee: el anti-empuje en % (0.1 -> 10 %), lo demas tal cual y con su signo. */
    private static String conSigno(String stat, double v) {
        if (stat.equals("KNOCKBACK_RESISTANCE")) return (v >= 0 ? "+" : "") + Bitacora.num(v * 100) + " %";
        return (v >= 0 ? "+" : "") + Bitacora.num(v);
    }

    private static TextComponent marca(boolean bien) {
        return Component.text(bien ? OK : MAL, bien ? Paleta.BIEN : Paleta.AVISO);
    }

    private static Component linea(String sangria, Component cuerpo) {
        return Component.text(sangria).append(cuerpo);
    }

    /**
     * Lo que MythicLib le tiene apuntado a un jugador de cada pieza de Calamity que lleva y de sus sets, contra lo
     * que promete cada objeto (objetos-calamity.yml). Es la foto del momento: lo que cuenta es donde lo lleva.
     */
    void stats(CommandSender quien, Player p) {
        YamlConfiguration esp = esperado();
        if (esp == null) {
            quien.sendMessage(ComandoCalamity.mensaje("No hay " + FICHERO + " legible: no sé qué promete cada objeto."));
            return;
        }
        if (!PuenteMmo.disponible()) {
            quien.sendMessage(ComandoCalamity.mensaje("Este servidor no tiene MMOItems."));
            return;
        }
        ConfigurationSection objetos = esp.getConfigurationSection("objetos");
        ConfigurationSection sets = esp.getConfigurationSection("sets");
        Map<String, ConfigurationSection> porId = new LinkedHashMap<>();
        for (String k : objetos.getKeys(false)) {
            ConfigurationSection o = objetos.getConfigurationSection(k);
            if (o != null) porId.put(idReal(k, o), o);
        }
        LecturaMmo.Estado est = LecturaMmo.estado(p, parametros(sets));
        if (est == null) {
            quien.sendMessage(ComandoCalamity.mensaje("MythicLib no tiene datos de " + p.getName() + " (¿acaba de entrar?)."));
            return;
        }
        quien.sendMessage(ComandoCalamity.mensaje(Component.text("Lo que le dan de verdad sus objetos de Calamity a ")
                .append(Component.text(p.getName(), Paleta.DETALLE)).append(Component.text(":"))));

        PlayerInventory inv = p.getInventory();
        ItemStack[] cosas = {inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots(),
                inv.getItemInMainHand(), inv.getItemInOffHand()};
        Map<String, Integer> piezasSet = new TreeMap<>();
        boolean alguna = false;
        for (int i = 0; i < cosas.length; i++) {
            String id = PuenteMmo.enlace(cosas[i]);
            if (id == null) continue;
            String hueco = HUECOS[i][0];
            Map<String, Double> aplicado = new TreeMap<>();
            for (LecturaMmo.Modificador m : est.modificadores()) {
                if (m.clave().equals(LecturaMmo.CLAVE_MMOITEMS) && m.hueco().equals(hueco)) aplicado.merge(m.stat(), m.valor(), Double::sum);
            }
            String set = LecturaMmo.etiqueta(cosas[i], "MMOITEMS_ITEM_SET");
            if (set != null && !set.isBlank() && !aplicado.isEmpty()) piezasSet.merge(set, 1, Integer::sum);
            ConfigurationSection o = porId.get(id);
            if (o == null) continue;
            alguna = true;
            pieza(quien, p, HUECOS[i][1], cosas[i], o, aplicado, i >= 4);
        }
        if (!alguna) {
            quien.sendMessage(Component.text("  No lleva puesta ni en las manos ninguna pieza de Calamity.", Paleta.TENUE));
        }

        // Los sets: lo que MMOItems registra en el hueco OTHER, contra lo acumulado de su lore-tag.
        Map<String, Double> deSets = new TreeMap<>();
        for (LecturaMmo.Modificador m : est.modificadores()) {
            if (m.clave().equals(LecturaMmo.CLAVE_MMOITEMS) && m.hueco().equals("OTHER")) deSets.merge(m.stat(), m.valor(), Double::sum);
        }
        long setsActivos = piezasSet.values().stream().filter(c -> c >= 2).count();
        for (Map.Entry<String, Integer> e : piezasSet.entrySet()) {
            ConfigurationSection s = sets.getConfigurationSection(e.getKey());
            if (s == null) continue;
            conjunto(quien, s, e.getValue(), deSets, est.habilidades(), setsActivos > 1);
        }

        // Lo que nota el jugador: los atributos vanilla, con el Talisman aparte.
        Component fin = Component.text("  En el jugador: ", Paleta.TENUE);
        boolean primero = true;
        for (String[] a : ATRIBUTOS) {
            Attribute at = Compat.attribute(a[0]);
            AttributeInstance ai = at == null ? null : p.getAttribute(at);
            if (ai == null) continue;
            String v = a[0].equals("knockback_resistance") ? Bitacora.num(ai.getValue() * 100) + " %" : Bitacora.num(ai.getValue());
            fin = fin.append(Component.text((primero ? "" : " · ") + a[1] + " ", Paleta.TENUE)).append(Component.text(v, Paleta.CIFRA));
            primero = false;
        }
        quien.sendMessage(fin);
        Attribute vida = Compat.attribute("max_health");
        AttributeInstance vi = vida == null ? null : p.getAttribute(vida);
        AttributeModifier tal = vi == null ? null : vi.getModifier(Marcas.TALISMAN);
        if (tal != null) {
            quien.sendMessage(linea("  ", marca(true).append(Component.text("Talismán de Vigilia: +" + Bitacora.num(tal.getAmount())
                    + " de vida máxima (lo pone Calamity, aparte de MythicLib)", Paleta.TEXTO))));
        }
    }

    /** Una pieza puesta: lo que MythicLib le aplica stat a stat, contra lo que trae el objeto y lo que promete. */
    private void pieza(CommandSender quien, Player p, String casilla, ItemStack it, ConfigurationSection o,
                       Map<String, Double> aplicado, boolean mano) {
        Component cab = Component.text("  " + casilla + " · ", Paleta.TENUE).append(Component.text(o.getString("nombre", "?"), Paleta.DETALLE));
        ConfigurationSection st = o.getConfigurationSection("stats");
        if (aplicado.isEmpty()) {
            quien.sendMessage(cab.append(Component.text("  ", Paleta.TENUE)).append(marca(false))
                    .append(Component.text("MMOItems no le aplica nada: no es su sitio, o no la puede usar", Paleta.AVISO)));
            return;
        }
        quien.sendMessage(cab);
        if (st == null) return;
        Component l = Component.text("    ");
        boolean primero = true;
        for (String s : st.getKeys(false)) {
            double promete = st.getDouble(s), trae = PuenteMmo.stat(it, s), da = aplicado.getOrDefault(s, 0.0);
            // El arma en la mano: MMOItems le resta la base del jugador (1 de dano, 4 de velocidad) para que el
            // total sea el del arma (InventoryResolver.fixWeaponBase).
            if (mano && (s.equals("ATTACK_DAMAGE") || s.equals("ATTACK_SPEED"))) {
                Attribute at = Compat.attribute(s.toLowerCase(Locale.ROOT));
                AttributeInstance ai = at == null ? null : p.getAttribute(at);
                da += ai == null ? (s.equals("ATTACK_DAMAGE") ? 1 : 4) : ai.getBaseValue();
            }
            boolean bien = Math.abs(da - trae) <= 1e-3 && trae + EPS >= promete;
            String extra = Math.abs(da - trae) > 1e-3 ? " (el objeto trae " + Bitacora.num(trae) + ")"
                    : trae + EPS < promete ? " (promete " + Bitacora.num(promete) + ": objeto viejo)"
                    : trae > promete + EPS ? " (" + Bitacora.num(promete) + " + mejoras/gemas)" : "";
            l = l.append(Component.text(primero ? "" : " · ")).append(marca(bien))
                    .append(Component.text(nombreStat(s) + " " + conSigno(s, da) + extra, bien ? Paleta.TEXTO : Paleta.AVISO));
            primero = false;
        }
        // Lo que no es del objeto de serie: las gemas engarzadas (PvE, no-muertos...) o una stat de mas.
        for (Map.Entry<String, Double> e : aplicado.entrySet()) {
            if (st.isSet(e.getKey()) || Math.abs(e.getValue()) <= EPS) continue;
            l = l.append(Component.text(" · " + nombreStat(e.getKey()) + " " + conSigno(e.getKey(), e.getValue())
                    + " (gema o añadido)", Paleta.TENUE));
        }
        quien.sendMessage(l);
    }

    /** Un set: piezas que cuentan, cada stat acumulada contra su promesa del lore-tag, y sus habilidades. */
    private void conjunto(CommandSender quien, ConfigurationSection s, int piezas, Map<String, Double> deSets,
                          List<LecturaMmo.Habilidad> habs, boolean variosSets) {
        StringBuilder niveles = new StringBuilder();
        Map<String, Double> esperado = new TreeMap<>();
        Map<String, List<String>> promesas = new TreeMap<>();
        List<ConfigurationSection> habsEsperadas = new ArrayList<>();
        for (int k = 2; k <= Math.min(piezas, nivelMaximo(s)); k++) {
            String nivel = String.valueOf(k);
            ConfigurationSection b = s.getConfigurationSection("bonos/" + nivel);
            ConfigurationSection hb = s.getConfigurationSection("habilidades/" + nivel);
            if (b == null && hb == null) continue;
            niveles.append(" [").append(k).append("]");
            if (b != null) for (String stat : b.getKeys(false)) esperado.merge(stat, b.getDouble(stat), Double::sum);
            if (hb != null) for (String hk : hb.getKeys(false)) if (hb.getConfigurationSection(hk) != null) habsEsperadas.add(hb.getConfigurationSection(hk));
        }
        for (Map<?, ?> m : s.getMapList("promete")) {
            int nivel;
            try {
                nivel = Integer.parseInt(String.valueOf(m.get("nivel")));
            } catch (NumberFormatException e) {
                continue;
            }
            if (nivel <= piezas) promesas.computeIfAbsent(String.valueOf(m.get("clave")), c -> new ArrayList<>()).add("[" + nivel + "] " + m.get("texto"));
        }
        quien.sendMessage(Component.text("  Set ", Paleta.TENUE).append(Component.text(s.getString("nombre", s.getName()), Paleta.DETALLE))
                .append(Component.text(": " + piezas + (piezas == 1 ? " pieza" : " piezas") + " que cuentan"
                        + (niveles.length() == 0 ? " (sin bonos todavía)" : " →" + niveles), Paleta.TENUE)));
        if (variosSets) {
            quien.sendMessage(Component.text("    Ojo: lleva piezas de más de un set; sus bonos se suman juntos aquí.", Paleta.TENUE));
        }
        for (Map.Entry<String, Double> e : esperado.entrySet()) {
            double da = deSets.getOrDefault(e.getKey(), 0.0);
            boolean bien = variosSets ? da + EPS >= e.getValue() : Math.abs(da - e.getValue()) <= EPS;
            List<String> dice = promesas.getOrDefault(e.getKey(), List.of());
            quien.sendMessage(linea("    ", marca(bien).append(Component.text(nombreStat(e.getKey()) + " " + conSigno(e.getKey(), da),
                            bien ? Paleta.TEXTO : Paleta.AVISO))
                    .append(Component.text((bien ? "" : " (promete " + conSigno(e.getKey(), e.getValue()) + ")")
                            + (dice.isEmpty() ? "" : "  · lore: " + String.join(", ", dice)), Paleta.TENUE))));
        }
        for (ConfigurationSection x : habsEsperadas) {
            String tipo = x.getString("tipo", "?");
            LecturaMmo.Habilidad r = null;
            for (LecturaMmo.Habilidad c : habs) {
                if (LecturaMmo.CLAVE_MMOITEMS.equals(c.clave()) && "OTHER".equals(c.hueco()) && c.tipo().equalsIgnoreCase(tipo)) r = c;
            }
            StringBuilder ps = new StringBuilder();
            boolean bien = r != null && x.getString("modo", "").equalsIgnoreCase(r.modo());
            for (String k : x.getKeys(false)) {
                if (k.equals("tipo") || k.equals("modo")) continue;
                double v = r == null ? 0 : r.parametros().getOrDefault(k, 0.0);
                bien &= Math.abs(v - x.getDouble(k)) <= EPS;
                ps.append(" · ").append(k).append(' ').append(Bitacora.num(v));
            }
            String texto = r == null ? "habilidad " + x.getName() + " (" + tipo + "): no la tiene registrada"
                    : "habilidad " + x.getName() + ": " + r.tipo() + " · " + r.modo() + ps;
            quien.sendMessage(linea("    ", marca(bien).append(Component.text(texto, bien ? Paleta.TEXTO : Paleta.AVISO))));
        }
        for (Map.Entry<String, Double> e : deSets.entrySet()) {
            if (!variosSets && !esperado.containsKey(e.getKey()) && Math.abs(e.getValue()) > EPS) {
                quien.sendMessage(linea("    ", marca(false).append(Component.text(nombreStat(e.getKey()) + " "
                        + conSigno(e.getKey(), e.getValue()) + ": de un set y el lore no lo promete", Paleta.AVISO))));
            }
        }
    }
}
