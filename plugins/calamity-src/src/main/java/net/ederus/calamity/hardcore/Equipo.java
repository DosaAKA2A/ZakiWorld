package net.ederus.calamity.hardcore;

import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * Calamity 1.4 · equipo.yml: lo que las piezas de MMOItems hacen DENTRO de Calamity.
 *
 * El reparto que aprobo Dosa: en MMOItems vive el objeto y sus stats normales (armadura, vida, dano,
 * bonos de set de stats, habilidades de MythicLib); aqui viven las funciones propias de Calamity,
 * ligadas al "TIPO.ID" del item. Asi se cambian sin tocar el item de MMOItems y valen al momento
 * (con /calamidad reload) tambien para los items que ya tienen los jugadores. Es el mismo modelo que
 * el gear.yml de PremioPescao, con sus dos fallos ya sabidos: el tipo y el id se leen de la raiz del
 * custom_data y no del PDC (LectorMmo), y el fichero se lee con '/' de separador, porque con el '.'
 * de Bukkit la clave "CALAMITY.YELMO" se partia en una seccion CALAMITY y no se encontraba nada.
 *
 * Que cuenta: la armadura puesta y lo que lleve en las dos manos, cada pieza una vez aunque la lleve
 * repetida. Una pieza de ARMADURA en la mano no cuenta (solo puesta): si no, un yelmo en cada mano y
 * otro en la cabeza sumaban tres yelmos. Un set suma su bono con al menos "necesita" de sus piezas.
 *
 * Cada efecto es una fraccion (Efecto) y todo lo del jugador se suma y se topa por efecto (topes: en
 * equipo.yml): el equipo ayuda, pero no puede apagar el modo hardcore. Donde se aplica cada uno:
 *   cordura-drenaje        Hardcore.drenar                           el drenaje x (1 - f)
 *   cordura-alucinaciones  Alucinaciones.tirada                      la probabilidad x (1 - f)
 *   parca-dano-recibido    onDanoParca (aqui) y DanoVerdadero.aplicar el dano de la PARCA x (1 - f)
 *   eco-dano               onDanoEco (aqui)                          el dano a un Eco x (1 + f)
 *   esencias-bonus         Grifo.alMorir, Minijefes.repartir, Cofres  n x (1 + f), antes de la Aduana
 *   hambre, durabilidad    Hardcore.onHambre / onDurabilidad         el x2 pasa a 1 + (2 - 1) x (1 - f)
 *   caidas                 Hardcore.onEntorno (solo FALL)            lo mismo con dano-caida
 *   niebla                 Hardcore.nieblaDeNoche                    ceniza y oscuridad x (1 - f)
 * Las Esencias de mas NUNCA salen en la Tasacion ni se meten dentro de Aduana.pagar: se suman en el
 * origen (mob, minijefe, cofre) y la Aduana topa el total como siempre, asi que el tope diario sigue
 * mandando.
 *
 * Sin equipo (y de serie, con equipo.yml sin piezas) todo es exactamente lo de antes: cada funcion de
 * abajo con f = 0 devuelve el valor de entrada o la cuenta de siempre, y el autotest "equipo" lo
 * compara contra las formulas de Calamity 1.3.
 *
 * Lo que suma cada jugador se guarda por tick (una muerte o un golpe preguntan varias veces en el
 * mismo) y se tira en cuanto cambia algo de su equipo. Con equipo.yml vacio ni se lee el equipo.
 */
final class Equipo implements Listener {

    /** Lo que puede hacer una pieza. Todo en fraccion: 0,10 = un 10 %. */
    enum Efecto {
        CORDURA_DRENAJE("cordura-drenaje", 0.50, false, "drenaje de cordura"),
        CORDURA_ALUCINACIONES("cordura-alucinaciones", 0.50, false, "probabilidad de alucinación"),
        PARCA_DANO_RECIBIDO("parca-dano-recibido", 0.30, false, "daño recibido de la PARCA"),
        ECO_DANO("eco-dano", 0.50, true, "daño a los Ecos"),
        ESENCIAS_BONUS("esencias-bonus", 0.25, true, "Esencias de mobs y cofres"),
        HAMBRE("hambre", 0.50, false, "castigo extra de hambre"),
        DURABILIDAD("durabilidad", 0.50, false, "castigo extra de durabilidad"),
        CAIDAS("caidas", 0.50, false, "castigo extra de caídas"),
        NIEBLA("niebla", 0.50, false, "niebla y oscuridad de noche");

        final String clave;
        /** El tope si equipo.yml no dice otro. */
        final double topeDeSerie;
        /** True si suma (mas dano, mas Esencias); false si quita (menos drenaje, menos castigo). */
        final boolean suma;
        final String texto;

        Efecto(String clave, double topeDeSerie, boolean suma, String texto) {
            this.clave = clave;
            this.topeDeSerie = topeDeSerie;
            this.suma = suma;
            this.texto = texto;
        }

        /** El efecto de esa clave de equipo.yml, o null. */
        static Efecto de(String clave) {
            if (clave == null) return null;
            String c = clave.trim().toLowerCase(Locale.ROOT);
            for (Efecto e : values()) if (e.clave.equals(c)) return e;
            return null;
        }

        static List<String> claves() {
            List<String> out = new ArrayList<>();
            for (Efecto e : values()) out.add(e.clave);
            return out;
        }
    }

    /** Un set: sus piezas y el bono de llevar al menos "necesita" de ellas. */
    record Conjunto(String id, List<String> piezas, int necesita, Map<Efecto, Double> bono) {
    }

    /** Lo que dice equipo.yml: lo de cada pieza ("TIPO.ID"), los sets, los topes y lo que no se entendio. */
    record Config(Map<String, Map<Efecto, Double>> piezas, List<Conjunto> sets, Map<Efecto, Double> topes,
                  List<String> avisos) {

        static final Config VACIA = new Config(Map.of(), List.of(), topesDeSerie(), List.of());

        /** Todos los ids que pueden contar: las piezas y las que nombran los sets. */
        Set<String> conocidas() {
            Set<String> out = new LinkedHashSet<>(piezas.keySet());
            for (Conjunto s : sets) out.addAll(s.piezas());
            return out;
        }

        double tope(Efecto e) {
            Double t = topes.get(e);
            return t == null ? e.topeDeSerie : t;
        }
    }

    /**
     * Un hueco del jugador tal como se ha leido: el item (null = vacio), su lectura de MMOItems (null =
     * no es de MMOItems) y si cuenta. porQueNo dice por que no cuenta una pieza que si se lee.
     */
    record Hueco(EquipmentSlot slot, ItemStack item, LectorMmo.Lectura lectura, boolean cuenta, String porQueNo) {
    }

    /** Lo que suma el equipo por efecto: tal cual (bruto) y con su tope (lo que se aplica). */
    record Total(Map<Efecto, Double> bruto, Map<Efecto, Double> topado) {

        static final Total VACIO = new Total(Map.of(), Map.of());

        /** Lo que se aplica de ese efecto; 0 si nada lo toca. */
        double de(Efecto e) {
            Double v = topado.get(e);
            return v == null ? 0 : v;
        }

        boolean vacio() {
            for (double v : topado.values()) if (v != 0) return false;
            return true;
        }
    }

    /** Lo leido de un jugador en un tick. */
    private record Guardado(int tick, Total total) {
    }

    /** Se lee con '/' entre partes de la ruta: las claves de las piezas llevan punto (TIPO.ID). */
    static final char SEPARADOR = '/';
    static final String FICHERO = "equipo.yml";

    /** Los huecos que cuentan, en el orden en que los ensena /calamidad equipo. */
    static final EquipmentSlot[] HUECOS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND};

    /**
     * Los ids de Calamity que trae comentados el equipo.yml del jar (tipos nuevos de MMOItems: CALAMITY,
     * CALAMITY_ARMAS...). El autotest prueba con uno de ellos si equipo.yml aun no tiene piezas.
     */
    static final List<String> EJEMPLOS = List.of("CALAMITY.YELMO_DE_CALAMIDAD", "CALAMITY.CORAZA_DE_CALAMIDAD",
            "CALAMITY.GREBAS_DE_CALAMIDAD", "CALAMITY.SOLERETAS_DE_CALAMIDAD", "CALAMITY_ARMAS.HACHA_DEL_HERALDO",
            "CALAMITY.MASCARA_DEL_ECO", "CALAMITY_ARMAS.FILO_DEL_ECO", "CALAMITY_ARMAS.GUADANA_DE_LA_PARCA");

    private final Hardcore hc;
    private final SecureRandom azar = new SecureRandom();
    private Config config = Config.VACIA;
    /** Config.conocidas() de lo cargado, hecho una vez: cada golpe y cada segundo lo preguntan. */
    private Set<String> conocidas = Set.of();
    private final Map<UUID, Guardado> guardado = new HashMap<>();
    /** Lo que quedo por gastar de la ultima perdida de hambre y del ultimo desgaste (ver hambre()). */
    private final Map<UUID, double[]> restoHambre = new HashMap<>();
    private final Map<UUID, double[]> restoDurabilidad = new HashMap<>();

    Equipo(Hardcore hc) {
        this.hc = hc;
        cargar();
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Subcomandos.lw().registrar("equipo",
                "equipo [jugador]: lo que cuenta de su equipo en Calamity (equipo.yml) y lo que suma",
                "ederus.mundos", this::comando, args -> args.length == 2 ? Entregas.nombresConectados() : List.of());
        Autotest.registrar("equipo", this::autotest);
    }

    void parar() {
        HandlerList.unregisterAll(this);
        guardado.clear();
        restoHambre.clear();
        restoDurabilidad.clear();
    }

    // ================================================================ equipo.yml

    /**
     * Lee equipo.yml (de la carpeta del plugin; la primera vez se escribe el del jar). Nunca lanza: un
     * fichero roto es "sin efectos" y se dice en consola. Devuelve el resumen para /calamidad reload.
     */
    String cargar() {
        LectorMmo.detectar(hc.plugin().getLogger());
        File f = new File(hc.plugin().getDataFolder(), FICHERO);
        if (!f.exists()) {
            try {
                hc.plugin().saveResource(FICHERO, false);
            } catch (IllegalArgumentException sinRecurso) {
                // Un jar sin equipo.yml: se sigue sin efectos.
            }
        }
        Config c;
        try {
            c = f.exists() ? leer(yaml(Files.readString(f.toPath(), StandardCharsets.UTF_8))) : Config.VACIA;
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            String motivo = e.getMessage() == null ? e.toString() : e.getMessage().replace('\n', ' ').replaceAll(" {2,}", " ");
            hc.plugin().getLogger().warning("[Calamity] " + FICHERO + " no se puede leer, el equipo no hace nada en Calamity: "
                    + motivo);
            c = new Config(Map.of(), List.of(), topesDeSerie(), List.of("no se puede leer: " + motivo));
        }
        // Lo que solo se sabe con el servidor delante: ids mal escritos y plantillas que MMOItems no tiene
        // (los items de Calamity se estan moviendo a los tipos CALAMITY, CALAMITY_ARMAS...).
        List<String> avisos = new ArrayList<>(c.avisos());
        boolean mmo = LectorMmo.conMmoItems();
        for (String id : c.conocidas()) {
            if (LectorMmo.partir(id) == null) avisos.add("'" + id + "' no es TIPO.ID: esa pieza no se encuentra nunca");
            else if (mmo && !LectorMmo.existe(id)) avisos.add("'" + id + "' no existe en MMOItems (¿ha cambiado de tipo?)");
        }
        config = new Config(c.piezas(), c.sets(), c.topes(), List.copyOf(avisos));
        conocidas = Set.copyOf(config.conocidas());
        guardado.clear();
        for (String a : avisos) hc.plugin().getLogger().warning("[Calamity] " + FICHERO + ": " + a);
        return resumen();
    }

    /** "3 piezas y 1 set" (y los avisos, si hay). */
    String resumen() {
        Config c = config;
        String s = c.piezas().size() + (c.piezas().size() == 1 ? " pieza" : " piezas") + " y " + c.sets().size()
                + (c.sets().size() == 1 ? " set" : " sets");
        return c.avisos().isEmpty() ? s : s + ", " + c.avisos().size() + " aviso(s) en consola";
    }

    /** Un texto YAML leido con '/' de separador (el fichero y las pruebas del autotest). */
    static YamlConfiguration yaml(String texto) throws InvalidConfigurationException {
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator(SEPARADOR);
        y.loadFromString(texto == null ? "" : texto);
        return y;
    }

    /** equipo.yml ya cargado con '/' a Config. Sin Bukkit alrededor: el autotest le da textos propios. */
    static Config leer(YamlConfiguration y) {
        List<String> avisos = new ArrayList<>();
        Map<Efecto, Double> topes = topesDeSerie();
        ConfigurationSection t = y.getConfigurationSection("topes");
        if (t != null) {
            for (String k : t.getKeys(false)) {
                Efecto e = Efecto.de(k);
                if (e == null) {
                    avisos.add("topes: '" + k + "' no es un efecto (hay: " + String.join(", ", Efecto.claves()) + ")");
                } else if (!t.isDouble(k) && !t.isInt(k)) {
                    avisos.add("topes > " + k + ": '" + t.get(k) + "' no es un número");
                } else {
                    // Lo que quita no puede pasar de 1 (quitar mas del 100 % no existe); lo que suma, si.
                    double v = Math.max(0, t.getDouble(k));
                    topes.put(e, e.suma ? v : Math.min(1, v));
                }
            }
        }
        Map<String, Map<Efecto, Double>> piezas = new LinkedHashMap<>();
        ConfigurationSection ps = y.getConfigurationSection("piezas");
        if (ps != null) {
            for (String k : ps.getKeys(false)) {
                String id = k.trim().toUpperCase(Locale.ROOT);
                ConfigurationSection s = ps.getConfigurationSection(k);
                if (s == null) {
                    avisos.add("piezas > " + k + ": no tiene efectos debajo");
                    continue;
                }
                piezas.put(id, efectos(s, "piezas > " + k, avisos));
            }
        }
        List<Conjunto> sets = new ArrayList<>();
        ConfigurationSection ss = y.getConfigurationSection("sets");
        if (ss != null) {
            for (String k : ss.getKeys(false)) {
                ConfigurationSection s = ss.getConfigurationSection(k);
                if (s == null) continue;
                List<String> ids = new ArrayList<>();
                for (String x : s.getStringList("piezas")) {
                    String id = x.trim().toUpperCase(Locale.ROOT);
                    if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
                }
                if (ids.isEmpty()) avisos.add("sets > " + k + ": sin piezas, no se completa nunca");
                int necesita = Math.max(1, s.getInt("necesita", Math.max(1, ids.size())));
                if (necesita > ids.size() && !ids.isEmpty()) {
                    avisos.add("sets > " + k + ": necesita " + necesita + " y solo tiene " + ids.size() + " piezas");
                }
                ConfigurationSection b = s.getConfigurationSection("bono");
                sets.add(new Conjunto(k, List.copyOf(ids), necesita,
                        b == null ? Map.of() : efectos(b, "sets > " + k + " > bono", avisos)));
            }
        }
        return new Config(Collections.unmodifiableMap(piezas), List.copyOf(sets), Collections.unmodifiableMap(topes),
                List.copyOf(avisos));
    }

    private static Map<Efecto, Double> efectos(ConfigurationSection s, String donde, List<String> avisos) {
        Map<Efecto, Double> out = new EnumMap<>(Efecto.class);
        for (String k : s.getKeys(false)) {
            Efecto e = Efecto.de(k);
            if (e == null) {
                avisos.add(donde + ": '" + k + "' no es un efecto (hay: " + String.join(", ", Efecto.claves()) + ")");
                continue;
            }
            if (!s.isDouble(k) && !s.isInt(k)) {
                avisos.add(donde + " > " + k + ": '" + s.get(k) + "' no es un número");
                continue;
            }
            out.put(e, s.getDouble(k));
        }
        return Collections.unmodifiableMap(out);
    }

    static Map<Efecto, Double> topesDeSerie() {
        Map<Efecto, Double> m = new EnumMap<>(Efecto.class);
        for (Efecto e : Efecto.values()) m.put(e, e.topeDeSerie);
        return m;
    }

    Config config() {
        return config;
    }

    // ================================================================= cuentas

    /** Cuantas piezas de ese set hay entre las puestas. */
    static int tiene(Conjunto s, Collection<String> puestas) {
        int n = 0;
        for (String id : s.piezas()) if (puestas.contains(id)) n++;
        return n;
    }

    /**
     * Lo que suman estas piezas con este equipo.yml: cada pieza, mas el bono de cada set que completan;
     * y por efecto, con su tope. Lo que baja de -1 se queda en -1 (una maldicion no pasa del 100 %).
     */
    static Total total(Config c, Collection<String> puestas) {
        if (puestas == null || puestas.isEmpty()) return Total.VACIO;
        Map<Efecto, Double> bruto = new EnumMap<>(Efecto.class);
        for (String id : puestas) {
            Map<Efecto, Double> m = c.piezas().get(id);
            if (m != null) m.forEach((e, v) -> bruto.merge(e, v, Double::sum));
        }
        for (Conjunto s : c.sets()) {
            if (tiene(s, puestas) >= s.necesita()) s.bono().forEach((e, v) -> bruto.merge(e, v, Double::sum));
        }
        if (bruto.isEmpty()) return Total.VACIO;
        Map<Efecto, Double> topado = new EnumMap<>(Efecto.class);
        bruto.forEach((e, v) -> topado.put(e, Math.max(-1, Math.min(c.tope(e), v))));
        return new Total(Collections.unmodifiableMap(bruto), Collections.unmodifiableMap(topado));
    }

    /** Si el item es una pieza de armadura (casco, peto, grebas, botas; no una cabeza ni una calabaza). */
    static boolean esArmadura(ItemStack item) {
        return sitio(item) != null;
    }

    /**
     * El hueco de armadura de un item, o null si no es armadura. Manda su componente equippable si lo
     * trae; si no, el material. Los bloques (cabezas, calabazas) no cuentan como armadura: un talisman
     * con forma de cabeza se lleva en la mano.
     */
    static EquipmentSlot sitio(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        Material m = item.getType();
        if (m.isBlock()) return null;
        EquipmentSlot s;
        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        try {
            s = meta != null && meta.hasEquippable() ? meta.getEquippable().getSlot() : m.getEquipmentSlot();
        } catch (Throwable t) {
            s = m.getEquipmentSlot();
        }
        return s == EquipmentSlot.HEAD || s == EquipmentSlot.CHEST || s == EquipmentSlot.LEGS || s == EquipmentSlot.FEET
                ? s : null;
    }

    /** Lo que lleva en cada hueco que cuenta (armadura y manos). */
    static Map<EquipmentSlot, ItemStack> equipoDe(Player p) {
        Map<EquipmentSlot, ItemStack> out = new EnumMap<>(EquipmentSlot.class);
        EntityEquipment eq = p == null ? null : p.getEquipment();
        if (eq == null) return out;
        for (EquipmentSlot s : HUECOS) {
            ItemStack i = eq.getItem(s);
            if (i != null && !i.getType().isAir()) out.put(s, i);
        }
        return out;
    }

    /** Cada hueco leido, tambien los vacios (para /calamidad equipo). */
    static List<Hueco> huecos(Map<EquipmentSlot, ItemStack> equipo) {
        List<Hueco> out = new ArrayList<>();
        for (EquipmentSlot s : HUECOS) {
            ItemStack i = equipo.get(s);
            if (i == null || i.getType().isAir()) {
                out.add(new Hueco(s, null, null, false, null));
                continue;
            }
            LectorMmo.Lectura l = LectorMmo.leer(i);
            if (l == null) {
                out.add(new Hueco(s, i, null, false, "no es de MMOItems"));
            } else if (s.isHand() && esArmadura(i)) {
                out.add(new Hueco(s, i, l, false, "armadura en la mano: solo cuenta puesta"));
            } else {
                out.add(new Hueco(s, i, l, true, null));
            }
        }
        return out;
    }

    /** Los ids que cuentan, cada uno una vez. */
    static Set<String> puestas(List<Hueco> huecos) {
        Set<String> out = new LinkedHashSet<>();
        for (Hueco h : huecos) if (h.cuenta()) out.add(h.lectura().id());
        return out;
    }

    /** Lo que suma el equipo de este jugador ahora mismo (guardado por tick). */
    Total total(Player p) {
        Config c = config;
        Set<String> conocidas = this.conocidas;
        if (p == null || conocidas.isEmpty()) return Total.VACIO;
        int tick = Bukkit.getCurrentTick();
        Guardado g = guardado.get(p.getUniqueId());
        if (g != null && g.tick() == tick) return g.total();
        Set<String> puestas = new LinkedHashSet<>();
        for (String id : puestas(huecos(equipoDe(p)))) if (conocidas.contains(id)) puestas.add(id);
        Total t = total(c, puestas);
        guardado.put(p.getUniqueId(), new Guardado(tick, t));
        return t;
    }

    /** Lo que el equipo de ese jugador pone en ese efecto, ya topado; 0 si nada. */
    double valor(Player p, Efecto e) {
        return total(p).de(e);
    }

    // ============================================ lo que hace cada efecto (puro)

    /** x menos la fraccion f (drenaje, alucinaciones, dano de la PARCA, niebla). Con f = 0, x tal cual. */
    static double menos(double x, double f) {
        return f == 0 ? x : x * (1 - f);
    }

    /** x mas la fraccion f (dano a los Ecos). Con f = 0, x tal cual. */
    static double mas(double x, double f) {
        return f == 0 ? x : x * (1 + f);
    }

    /**
     * Un castigo de Calamity (hambre x2, durabilidad x2, caidas x2) con el equipo: f quita esa fraccion
     * de lo que pasa de 1. Con 2,0 y f = 0,5 queda en 1,5. Con f = 0, o la regla apagada (<= 1), igual.
     */
    static double castigo(double factor, double f) {
        if (f == 0 || factor <= 1) return factor;
        return 1 + (factor - 1) * (1 - f);
    }

    /**
     * La comida que le queda tras perder hambre (Hardcore.onHambre). Con f = 0 es la cuenta de siempre,
     * tal cual. Con equipo, el castigo es fraccionario (1,5 puntos) y la comida va en enteros: lo que no
     * llega a un punto se guarda en resto y se cobra en la siguiente perdida. Sin eso, 1,5 se redondeaba
     * a 2 y el efecto no hacia nada.
     */
    static int hambre(int antes, int nueva, double factor, double f, double[] resto) {
        if (f == 0 || resto == null) return (int) Math.max(0, antes - (antes - nueva) * factor);
        double pierde = (antes - nueva) * castigo(factor, f) + resto[0];
        int entero = (int) Math.floor(pierde);
        resto[0] = pierde - entero;
        return Math.max(0, antes - entero);
    }

    /** El desgaste de un uso (Hardcore.onDurabilidad), con el mismo resto que hambre(). Con f = 0, el de siempre. */
    static int durabilidad(int dano, double factor, double f, double[] resto) {
        if (f == 0 || resto == null) return factor > 1 ? (int) Math.ceil(dano * factor) : dano;
        if (factor <= 1) return dano;
        double gasta = dano * castigo(factor, f) + resto[0];
        int entero = (int) Math.floor(gasta);
        resto[0] = gasta - entero;
        return Math.max(0, entero);
    }

    /**
     * Esencias con el bono: n x (1 + f). La parte que no llega a una Esencia entera sale con esa
     * probabilidad (1 Esencia con +25 % = 2 una de cada cuatro veces): de media da justo lo prometido.
     */
    static int esencias(int n, double f, DoubleSupplier azar) {
        if (n <= 0 || f == 0) return n;
        double exacto = n * (1 + f);
        int entero = (int) Math.floor(exacto + 1e-9);
        double sobra = exacto - entero;
        if (sobra > 1e-9 && azar.getAsDouble() < sobra) entero++;
        return Math.max(0, entero);
    }

    /** Particulas de ceniza de la niebla. Con f = 0, las mismas. */
    static int particulasNiebla(int n, double f) {
        return f == 0 ? n : (int) Math.max(0, Math.round(n * (1 - f)));
    }

    /** Ticks de una racha de oscuridad de tantos segundos. Con f = 0, segundos x 20. */
    static int ticksOscuridad(int segundos, double f) {
        return f == 0 ? segundos * 20 : (int) Math.max(0, Math.round(segundos * 20 * (1 - f)));
    }

    /** Si una entidad es la PARCA (su esqueleto) o una de sus planideras. */
    static boolean esDeParca(Entity e) {
        String a = Marcas.amenaza(e);
        return "parca".equals(a) || "planidera".equals(a);
    }

    /**
     * Escala el dano FINAL (el que de verdad quita, tras armadura y demas) por factor. Bukkit recalcula los
     * modificadores desde la base y la armadura no es lineal: se corrige hasta clavarlo (dos pasadas
     * suelen bastar). Sin nada que escalar despues de la absorcion, se escala la base.
     */
    static void escalarFinal(EntityDamageEvent e, double factor) {
        if (factor == 1) return;
        double fin = e.getFinalDamage();
        if (fin <= 1e-9) {
            e.setDamage(Math.max(0, e.getDamage() * factor));
            return;
        }
        double deseado = Math.max(0, fin * factor);
        for (int i = 0; i < 4; i++) {
            double ahora = e.getFinalDamage();
            if (ahora <= 1e-9 || Math.abs(ahora - deseado) <= 1e-6) break;
            e.setDamage(Math.max(0, e.getDamage() * deseado / ahora));
        }
    }

    // ================================================== ganchos (desde otros modulos)

    /** Esencias de un mob, un minijefe o un cofre con el bono de su equipo (antes de la Aduana). */
    int esencias(Player p, int n) {
        if (p == null || n <= 0) return n;
        return esencias(n, valor(p, Efecto.ESENCIAS_BONUS), azar::nextDouble);
    }

    double[] restoHambre(Player p) {
        return restoHambre.computeIfAbsent(p.getUniqueId(), k -> new double[1]);
    }

    double[] restoDurabilidad(Player p) {
        return restoDurabilidad.computeIfAbsent(p.getUniqueId(), k -> new double[1]);
    }

    // ================================================================== listener

    /** Quien pega de verdad: la causa del dano, o el que dispara, o el que golpea. */
    private static Entity causa(EntityDamageEvent e) {
        try {
            Entity c = e.getDamageSource().getCausingEntity();
            if (c != null) return c;
        } catch (Throwable ignorado) {
            // Sin DamageSource: se mira el golpe a la antigua.
        }
        if (e instanceof EntityDamageByEntityEvent be) {
            Entity d = be.getDamager();
            if (d instanceof Projectile pr && pr.getShooter() instanceof Entity tirador) return tirador;
            return d;
        }
        return null;
    }

    /**
     * parca-dano-recibido: lo que pega la PARCA, en sus dos implementaciones (PeleaParca y ParcaAnomalia):
     * su golpe cuerpo a cuerpo, sus tecnicas (golpear() con ella de fuente) y lo que dispare. El dano
     * verdadero (Siega, Sentencia) no pasa por aqui: lo recorta DanoVerdadero.aplicar. HIGHEST: se escala
     * lo que quede despues de armadura y de lo que hayan hecho los demas.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDanoParca(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || conocidas.isEmpty()) return;
        // El golpe letal del dano verdadero ya viene recortado: no se toca dos veces.
        if (DanoVerdadero.enCurso.contains(p.getUniqueId()) || !esDeParca(causa(e))) return;
        double f = hc.valor("equipo", () -> valor(p, Efecto.PARCA_DANO_RECIBIDO), 0.0);
        if (f != 0) escalarFinal(e, 1 - f);
    }

    /**
     * eco-dano: lo que un jugador le hace a un Eco (a mano, con flechas; el golpe al maniqui llega aqui
     * pasado al cuerpo). HIGH: antes de que Amenazas lo escale a la vida logica y lo tope por golpe en
     * HIGHEST, asi que el tope por golpe sigue mandando.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDanoEco(EntityDamageEvent e) {
        if (conocidas.isEmpty() || !"eco".equals(Marcas.amenaza(e.getEntity()))) return;
        if (!(causa(e) instanceof Player p)) return;
        double f = hc.valor("equipo", () -> valor(p, Efecto.ECO_DANO), 0.0);
        if (f != 0) escalarFinal(e, 1 + f);
    }

    // Lo guardado del tick se tira en cuanto cambia algo del equipo (el cambio puede llegar a mitad de tick).

    private void olvidarTick(Entity e) {
        if (e instanceof Player p) guardado.remove(p.getUniqueId());
    }

    /** El de Paper: cualquier hueco (armadura y manos) que cambia, lo cambie quien lo cambie. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCambioEquipo(EntityEquipmentChangedEvent e) {
        olvidarTick(e.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMano(PlayerItemHeldEvent e) {
        olvidarTick(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCambiarManos(PlayerSwapHandItemsEvent e) {
        olvidarTick(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClic(InventoryClickEvent e) {
        olvidarTick(e.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArrastrar(InventoryDragEvent e) {
        olvidarTick(e.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSoltar(PlayerDropItemEvent e) {
        olvidarTick(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRecoger(EntityPickupItemEvent e) {
        olvidarTick(e.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRomper(PlayerItemBreakEvent e) {
        olvidarTick(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReaparecer(PlayerRespawnEvent e) {
        olvidarTick(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCambiarMundo(PlayerChangedWorldEvent e) {
        olvidarTick(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSalir(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        guardado.remove(u);
        restoHambre.remove(u);
        restoDurabilidad.remove(u);
    }

    // ================================================================== comando

    /** /calamidad equipo [jugador]: cada hueco, por quien se lee, lo que suma cada pieza y el set, y el total. */
    private void comando(CommandSender quien, String[] args) {
        Player p = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : (quien instanceof Player j ? j : null);
        if (p == null) {
            quien.sendMessage(Paleta.aviso(args.length >= 2 ? "No encuentro a " + args[1] + " conectado."
                    : "Uso: /calamidad equipo <jugador>"));
            return;
        }
        Config c = config;
        Set<String> conocidas = this.conocidas;
        quien.sendMessage(Paleta.mensaje(Component.text("Equipo de ").append(Component.text(p.getName(), Paleta.DETALLE))
                .append(Component.text(" en Calamity"))));
        quien.sendMessage(Component.text("  " + FICHERO + ": " + resumen() + "  ·  se lee con " + LectorMmo.origen(),
                Paleta.TENUE));
        List<Hueco> hs = huecos(equipoDe(p));
        for (Hueco h : hs) quien.sendMessage(lineaHueco(h, c, conocidas));
        Set<String> puestas = new LinkedHashSet<>();
        for (String id : puestas(hs)) if (conocidas.contains(id)) puestas.add(id);
        for (Conjunto s : c.sets()) {
            int n = tiene(s, puestas);
            if (n == 0) continue;
            boolean activo = n >= s.necesita();
            Component l = Component.text("  Set " + s.id() + "  ", Paleta.TENUE)
                    .append(Component.text(n + "/" + s.piezas().size(), Paleta.CIFRA))
                    .append(Component.text(" (necesita " + s.necesita() + ")  ·  ", Paleta.TENUE));
            l = activo ? l.append(efectos(s.bono(), "bono sin efectos")) : l.append(Component.text("sin bono aún", Paleta.TENUE));
            quien.sendMessage(l);
        }
        Total t = total(c, puestas);
        if (t.bruto().isEmpty()) {
            quien.sendMessage(Component.text("  Total: nada. Su equipo no hace nada propio de Calamity.", Paleta.TEXTO));
        } else {
            quien.sendMessage(Component.text("  Total, con los topes:", Paleta.TEXTO));
            for (Efecto e : Efecto.values()) {
                Double bruto = t.bruto().get(e);
                if (bruto == null) continue;
                double v = t.de(e);
                Component l = Component.text("    ").append(efecto(e, v))
                        .append(Component.text("  (tope " + porcentaje(c.tope(e))
                                + (Math.abs(bruto - v) > 1e-9 ? ", sin tope sería " + porcentaje(bruto) : "") + ")"
                                + efectoReal(e, v), Paleta.TENUE));
                quien.sendMessage(l);
            }
        }
        if (!hc.esHardcore(p)) {
            quien.sendMessage(Component.text("  Está fuera de Calamity: todo esto solo cuenta dentro"
                    + " (menos el daño de la PARCA, que cuenta donde le pegue).", Paleta.TENUE));
        }
        for (String a : c.avisos()) quien.sendMessage(Component.text("  " + FICHERO + ": " + a, Paleta.AVISO));
    }

    private static Component lineaHueco(Hueco h, Config c, Set<String> conocidas) {
        Component l = Component.text("  " + nombreHueco(h.slot()) + "  ", Paleta.TENUE);
        if (h.item() == null) return l.append(Component.text("vacío", Paleta.SEPARADOR));
        if (h.lectura() == null) {
            return l.append(Component.text(h.item().getType().getKey().getKey(), Paleta.TEXTO))
                    .append(Component.text("  ·  " + h.porQueNo(), Paleta.SEPARADOR));
        }
        String id = h.lectura().id();
        l = l.append(Component.text(id, Paleta.DETALLE))
                .append(Component.text("  ·  " + h.lectura().fuente().texto + "  ·  ", Paleta.TENUE));
        if (!h.cuenta()) return l.append(Component.text("no cuenta: " + h.porQueNo(), Paleta.AVISO));
        if (!conocidas.contains(id)) return l.append(Component.text("no está en " + FICHERO, Paleta.SEPARADOR));
        Map<Efecto, Double> propios = c.piezas().get(id);
        List<String> sets = new ArrayList<>();
        for (Conjunto s : c.sets()) if (s.piezas().contains(id)) sets.add(s.id());
        Component e = propios == null || propios.isEmpty()
                ? Component.text("sin efectos propios", Paleta.TENUE) : efectos(propios, "sin efectos propios");
        return l.append(e).append(sets.isEmpty() ? Component.empty()
                : Component.text("  ·  set " + String.join(", ", sets), Paleta.TENUE));
    }

    private static String nombreHueco(EquipmentSlot s) {
        return switch (s) {
            case HEAD -> "Cabeza   ";
            case CHEST -> "Pecho    ";
            case LEGS -> "Piernas  ";
            case FEET -> "Pies     ";
            case HAND -> "Mano     ";
            case OFF_HAND -> "Otra mano";
            default -> s.name();
        };
    }

    /** "−10 % drenaje de cordura, +20 % daño a los Ecos". */
    private static Component efectos(Map<Efecto, Double> m, String siVacio) {
        if (m.isEmpty()) return Component.text(siVacio, Paleta.TENUE);
        Component out = Component.empty();
        boolean primero = true;
        for (Map.Entry<Efecto, Double> x : m.entrySet()) {
            if (!primero) out = out.append(Component.text(", ", Paleta.SEPARADOR));
            out = out.append(efecto(x.getKey(), x.getValue()));
            primero = false;
        }
        return out;
    }

    /** "−10 % drenaje de cordura" (lo que quita) o "+20 % daño a los Ecos" (lo que suma); al reves si es negativo. */
    private static Component efecto(Efecto e, double v) {
        boolean baja = e.suma ? v < 0 : v >= 0;
        return Component.text((baja ? "−" : "+") + porcentaje(Math.abs(v)), Paleta.CIFRA)
                .append(Component.text(" " + e.texto, Paleta.TEXTO));
    }

    /** Para los castigos, en que se queda el x2 de la config con ese valor: " · hambre x2 → x1,5". */
    private String efectoReal(Efecto e, double v) {
        String clave = switch (e) {
            case HAMBRE -> "hambre";
            case DURABILIDAD -> "durabilidad";
            case CAIDAS -> "dano-caida";
            default -> null;
        };
        if (clave == null) return "";
        double base = hc.cfg().getDouble("dificultad." + clave, 2.0);
        return "  ·  x" + numero(base) + " → x" + numero(castigo(base, v));
    }

    /** 0,125 -> "12,5 %"; 0,1 -> "10 %". */
    static String porcentaje(double f) {
        return numero(f * 100) + " %";
    }

    private static String numero(double v) {
        double r = Math.round(v * 10) / 10.0;
        return (r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r)).replace('.', ',');
    }

    // ================================================================= autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();

        // 1. Las claves con punto: TIPO.ID entra entero, y en minusculas tambien.
        String texto = """
                topes:
                  eco-dano: 0.30
                  cordura-drenaje: 0.50
                piezas:
                  CALAMITY.YELMO_PRUEBA:
                    cordura-drenaje: 0.10
                    eco-dano: 0.20
                  calamity_armas.hacha_prueba:
                    eco-dano: 0.15
                  CALAMITY.CORAZA_PRUEBA:
                    cordura-drenaje: 0.30
                    clave-que-no-existe: 1
                  CALAMITY.MALDITA_PRUEBA:
                    hambre: -3
                sets:
                  prueba:
                    piezas: [CALAMITY.YELMO_PRUEBA, CALAMITY.CORAZA_PRUEBA, calamity_armas.hacha_prueba]
                    necesita: 2
                    bono:
                      cordura-drenaje: 0.25
                      parca-dano-recibido: 0.10
                """;
        Config c;
        try {
            c = leer(yaml(texto));
        } catch (InvalidConfigurationException e) {
            return List.of("el texto de prueba no se lee: " + e.getMessage());
        }
        h.ok("CALAMITY.YELMO_PRUEBA entra entera, con su punto", c.piezas().containsKey("CALAMITY.YELMO_PRUEBA"));
        h.ok("no se parte en una seccion CALAMITY", !c.piezas().containsKey("CALAMITY"));
        h.ok("en minusculas entra en mayusculas: CALAMITY_ARMAS.HACHA_PRUEBA", c.piezas().containsKey("CALAMITY_ARMAS.HACHA_PRUEBA"));
        h.igual("cuatro piezas", 4, c.piezas().size());
        h.igual("las piezas del set, en mayusculas", List.of("CALAMITY.YELMO_PRUEBA", "CALAMITY.CORAZA_PRUEBA",
                "CALAMITY_ARMAS.HACHA_PRUEBA"), c.sets().isEmpty() ? null : c.sets().get(0).piezas());
        try {
            YamlConfiguration conPunto = new YamlConfiguration();
            conPunto.loadFromString(texto);
            ConfigurationSection ps = conPunto.getConfigurationSection("piezas");
            h.ok("con el '.' de Bukkit la clave SI se partiria (el fallo de PremioPescao)",
                    ps != null && ps.getKeys(false).contains("CALAMITY") && !ps.getKeys(false).contains("CALAMITY.YELMO_PRUEBA"));
        } catch (InvalidConfigurationException e) {
            h.ok("el texto de prueba con '.': " + e.getMessage(), false);
        }
        h.igual("una clave que no es un efecto se avisa", 1L,
                c.avisos().stream().filter(a -> a.contains("clave-que-no-existe")).count());
        h.cerca("tope de la config (eco-dano 0,30)", 0.30, c.tope(Efecto.ECO_DANO), 1e-9);
        h.cerca("tope de serie si la config no lo trae (PARCA 0,30)", Efecto.PARCA_DANO_RECIBIDO.topeDeSerie,
                c.tope(Efecto.PARCA_DANO_RECIBIDO), 1e-9);

        // 2. Suma de piezas y set, y topes.
        Total una = total(c, List.of("CALAMITY.YELMO_PRUEBA"));
        h.cerca("una pieza: su drenaje", 0.10, una.de(Efecto.CORDURA_DRENAJE), 1e-9);
        h.cerca("una pieza no completa el set: nada de PARCA", 0, una.de(Efecto.PARCA_DANO_RECIBIDO), 1e-9);
        Total dos = total(c, List.of("CALAMITY.YELMO_PRUEBA", "CALAMITY_ARMAS.HACHA_PRUEBA"));
        h.cerca("dos piezas suman: eco-dano 0,20 + 0,15 sin tope", 0.35, dos.bruto().get(Efecto.ECO_DANO), 1e-9);
        h.cerca("y con el tope de 0,30 se queda en 0,30", 0.30, dos.de(Efecto.ECO_DANO), 1e-9);
        h.cerca("dos del set: su bono de drenaje (0,10 + 0,25)", 0.35, dos.de(Efecto.CORDURA_DRENAJE), 1e-9);
        h.cerca("dos del set: su bono de PARCA", 0.10, dos.de(Efecto.PARCA_DANO_RECIBIDO), 1e-9);
        Total tres = total(c, List.of("CALAMITY.YELMO_PRUEBA", "CALAMITY_ARMAS.HACHA_PRUEBA", "CALAMITY.CORAZA_PRUEBA"));
        h.cerca("tres piezas: drenaje 0,10 + 0,30 + 0,25 sin tope", 0.65, tres.bruto().get(Efecto.CORDURA_DRENAJE), 1e-9);
        h.cerca("tres piezas: topado a 0,50", 0.50, tres.de(Efecto.CORDURA_DRENAJE), 1e-9);
        h.cerca("una maldicion (-3) no baja de -1", -1, total(c, List.of("CALAMITY.MALDITA_PRUEBA")).de(Efecto.HAMBRE), 1e-9);
        h.ok("un id que no esta en equipo.yml no suma nada", total(c, List.of("CALAMITY.OTRA_COSA")).vacio());

        // 3. Sin equipo, todo igual que en Calamity 1.3 (las formulas de entonces, copiadas aqui).
        Total nada = total(c, List.of());
        boolean ceros = true;
        for (Efecto e : Efecto.values()) ceros &= nada.de(e) == 0;
        h.ok("sin piezas, los nueve efectos a 0", ceros);
        h.ok("sin equipo el drenaje no cambia", menos(1.7, 0) == 1.7 && menos(2.0 * 2.0 * 1.3, 0) == 2.0 * 2.0 * 1.3);
        h.ok("sin equipo la probabilidad de alucinar no cambia", menos(2 / 3.0, 0) == 2 / 3.0);
        h.ok("sin equipo el dano de la PARCA no cambia (factor 1)", menos(1.0, 0) == 1.0);
        h.ok("sin equipo el dano a un Eco no cambia (factor 1)", mas(1.0, 0) == 1.0);
        h.ok("sin equipo las caidas siguen x2", castigo(2.0, 0) == 2.0 && castigo(3.5, 0) == 3.5);
        h.ok("una regla apagada (x1 o menos) no la toca ni el equipo", castigo(1.0, 0.5) == 1.0 && castigo(0.5, 0.5) == 0.5);
        boolean hambreIgual = true, durIgual = true;
        for (double factor : new double[]{2.0, 1.5, 3.0, 2.5}) {
            for (int antes = 0; antes <= 20; antes++) {
                for (int nueva = 0; nueva < antes; nueva++) {
                    int viejo = (int) Math.max(0, antes - (antes - nueva) * factor);
                    hambreIgual &= hambre(antes, nueva, factor, 0, new double[1]) == viejo
                            && hambre(antes, nueva, factor, 0, null) == viejo;
                }
            }
        }
        for (double factor : new double[]{2.0, 1.5, 1.0, 0.5, 3.0}) {
            for (int dano = 0; dano <= 12; dano++) {
                int viejo = factor > 1 ? (int) Math.ceil(dano * factor) : dano;
                durIgual &= durabilidad(dano, factor, 0, new double[1]) == viejo;
            }
        }
        h.ok("sin equipo el hambre pierde lo mismo que en 1.3 (x2, x1,5, x2,5, x3; 0-20)", hambreIgual);
        h.ok("sin equipo la durabilidad gasta lo mismo que en 1.3", durIgual);
        h.igual("sin equipo la niebla trae las 14 particulas de ceniza", 14, particulasNiebla(14, 0));
        h.igual("sin equipo la oscuridad dura lo mismo (3 s = 60 ticks)", 60, ticksOscuridad(3, 0));
        boolean esIgual = true;
        for (int n = 0; n <= 6; n++) esIgual &= esencias(n, 0, () -> 0.0) == n;
        h.ok("sin equipo las Esencias no cambian", esIgual);
        h.ok("un jugador sin nada puesto no suma nada", total(c, puestas(huecos(Map.of()))).vacio());

        // 4. Con equipo: los castigos fraccionarios se cobran de verdad (no se redondean a x2).
        h.cerca("x2 con la mitad quitada: x1,5", 1.5, castigo(2.0, 0.5), 1e-9);
        double[] r = new double[1];
        int comida = 20;
        for (int i = 0; i < 10; i++) comida = hambre(comida, comida - 1, 2.0, 0.5, r);
        h.igual("hambre x1,5: diez perdidas de 1 quitan 15 (no 20)", 5, comida);
        r = new double[1];
        int gastado = 0;
        for (int i = 0; i < 10; i++) gastado += durabilidad(1, 2.0, 0.5, r);
        h.igual("durabilidad x1,5: diez usos gastan 15 (no 20)", 15, gastado);
        h.igual("Esencias +25 % de 4: 5 justas", 5, esencias(4, 0.25, () -> 0.99));
        h.igual("Esencias +25 % de 1 con la tirada a favor: 2", 2, esencias(1, 0.25, () -> 0.10));
        h.igual("Esencias +25 % de 1 con la tirada en contra: 1", 1, esencias(1, 0.25, () -> 0.90));
        h.igual("oscuridad de 3 s con la mitad: 30 ticks", 30, ticksOscuridad(3, 0.5));
        h.igual("ceniza con la mitad: 7 particulas", 7, particulasNiebla(14, 0.5));

        // 5. Que cuenta: armadura puesta y las dos manos, cada pieza una vez; armadura en la mano, no.
        ItemStack yelmo = marcado(Material.NETHERITE_HELMET, "CALAMITY", "YELMO_PRUEBA");
        ItemStack hacha = marcado(Material.NETHERITE_AXE, "CALAMITY_ARMAS", "HACHA_PRUEBA");
        ItemStack coraza = marcado(Material.NETHERITE_CHESTPLATE, "CALAMITY", "CORAZA_PRUEBA");
        LectorMmo.Lectura ly = LectorMmo.leer(yelmo);
        h.igual("un item marcado a mano se lee del PDC", "CALAMITY.YELMO_PRUEBA", ly == null ? null : ly.id());
        h.igual("y dice que ha sido por el PDC", LectorMmo.Fuente.PDC, ly == null ? null : ly.fuente());
        h.igual("una espada vanilla no es de MMOItems", null, LectorMmo.leer(new ItemStack(Material.DIAMOND_SWORD)));
        Map<EquipmentSlot, ItemStack> eq = new EnumMap<>(EquipmentSlot.class);
        eq.put(EquipmentSlot.HEAD, yelmo);
        eq.put(EquipmentSlot.HAND, hacha);
        eq.put(EquipmentSlot.OFF_HAND, coraza);
        Set<String> p = puestas(huecos(eq));
        h.ok("el yelmo puesto cuenta", p.contains("CALAMITY.YELMO_PRUEBA"));
        h.ok("el hacha en la mano cuenta", p.contains("CALAMITY_ARMAS.HACHA_PRUEBA"));
        h.ok("la coraza en la otra mano NO cuenta (armadura: solo puesta)", !p.contains("CALAMITY.CORAZA_PRUEBA"));
        eq.clear();
        eq.put(EquipmentSlot.HAND, hacha);
        eq.put(EquipmentSlot.OFF_HAND, hacha.clone());
        h.igual("la misma pieza en las dos manos cuenta una vez", 1, puestas(huecos(eq)).size());
        h.ok("una cabeza (bloque) no es armadura: cuenta en la mano", !esArmadura(new ItemStack(Material.PLAYER_HEAD)));
        h.igual("el hueco de un casco", EquipmentSlot.HEAD, sitio(new ItemStack(Material.IRON_HELMET)));

        // 6. El equipo.yml de ahora, tal cual lo tiene el servidor.
        Config ahora = config;
        if (ahora.avisos().isEmpty()) {
            h.ok(FICHERO + " sin avisos (" + resumen() + ")", true);
        } else {
            for (String a : ahora.avisos()) h.ok(FICHERO + ": " + a, false);
        }

        // 7. Items reales: con MMOItems, cada pieza de equipo.yml se genera y se lee (y si no hay
        //    ninguna, un item de Calamity de los de ejemplo o, en ultimo caso, cualquier plantilla).
        if (!LectorMmo.conMmoItems()) {
            h.ok("sin MMOItems no hay items reales que generar (" + LectorMmo.origen() + ")", true);
            return h.lineas();
        }
        Set<String> reales = new LinkedHashSet<>(ahora.conocidas());
        if (reales.isEmpty()) {
            List<String> candidatos = new ArrayList<>(EJEMPLOS);
            ConfigurationSection forja = hc.cfg().getConfigurationSection("forja.piezas");
            if (forja != null) for (String k : forja.getKeys(false)) candidatos.add(String.valueOf(forja.getString(k)));
            String elegido = null;
            for (String id : candidatos) {
                if (LectorMmo.existe(id)) {
                    elegido = id.toUpperCase(Locale.ROOT);
                    break;
                }
            }
            if (elegido == null) elegido = LectorMmo.algunaPlantilla();
            if (elegido == null) {
                h.ok("MMOItems no tiene ninguna plantilla con la que probar", false);
                return h.lineas();
            }
            reales.add(elegido);
        }
        for (String id : reales) {
            ItemStack it = LectorMmo.crear(id);
            h.ok("MMOItems genera " + id, it != null);
            if (it == null) continue;
            LectorMmo.Lectura l = LectorMmo.leer(it);
            h.igual("el " + id + " real se lee", id, l == null ? null : l.id());
            h.igual("el " + id + " real se lee por la API de MMOItems", LectorMmo.Fuente.API, l == null ? null : l.fuente());
            h.igual("el " + id + " real se lee tambien por NBTItem a mano", id, LectorMmo.porNbt(it));
            // De punta a punta: con esa pieza en un equipo.yml y puesta donde va, da su efecto.
            try {
                Config sola = leer(yaml("piezas:\n  " + id + ":\n    cordura-drenaje: 0.1\n"));
                EquipmentSlot donde = sitio(it) != null ? sitio(it) : EquipmentSlot.HAND;
                Map<EquipmentSlot, ItemStack> puesto = new EnumMap<>(EquipmentSlot.class);
                puesto.put(donde, it);
                h.cerca("el " + id + " real, en " + donde + ", da su efecto", 0.1,
                        total(sola, puestas(huecos(puesto))).de(Efecto.CORDURA_DRENAJE), 1e-9);
            } catch (InvalidConfigurationException e) {
                h.ok("equipo.yml de prueba con " + id + ": " + e.getMessage(), false);
            }
        }
        return h.lineas();
    }

    /** Un item vanilla con el tipo y el id de MMOItems en el PDC (como uno marcado a mano). */
    private static ItemStack marcado(Material m, String tipo, String id) {
        ItemStack i = new ItemStack(m);
        i.editMeta(meta -> {
            meta.getPersistentDataContainer().set(LectorMmo.PDC_TIPO, PersistentDataType.STRING, tipo);
            meta.getPersistentDataContainer().set(LectorMmo.PDC_ID, PersistentDataType.STRING, id);
        });
        return i;
    }
}
