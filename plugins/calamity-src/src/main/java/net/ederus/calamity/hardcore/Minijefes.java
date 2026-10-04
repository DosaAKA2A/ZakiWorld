package net.ederus.calamity.hardcore;

import net.ederus.calamity.CartelesMinijefe;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.MobCoins;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

/**
 * M2 punto 5 · El reparto de un minijefe (PLAN sec. 3.2), y desde la 1.10 donde vive cada uno y su
 * botin extra.
 *
 * Antes lo cobraba todo el que daba el ultimo golpe, y eso premiaba robar el remate. Ahora:
 * - MobCoins fijas (mobs.mobcoins.minijefe, 80-120) al asesino: una sola via de MC.
 * - Esencias 1 + floor(N/25) (x f) a CADA participante con >= 10 % de su vida.
 * - Reliquia III 100 % y II 30 % al asesino.
 * - Sello de <minijefe> (IV) con un 10 % al MEJOR danador. Y piedad: cada muerte de ese
 *   tipo suma 1 a todo participante; con 8, el Sello le toca aunque no sea el mejor y la
 *   piedad vuelve a 0. El Sello es el freno del Manto (PLAN sec. 1.6): sin piedad, alguien
 *   con mala suerte podia matar treinta Heraldos sin ver uno.
 * - 1.10 · El botin extra de minijefes.botin (Botin): lo comun a los cinco y lo de cada tipo (un
 *   libro LEGENDARY, el huevo de su mascota...), cada entrada a quien diga su "para", con su
 *   probabilidad y su propia piedad (piedad-botin.<uuid>.<tipo>.<id>). Lo que se da pasa por
 *   Entregas: el libro con su tope del mes, los comandos con el nombre validado. El sustituto del
 *   libro, con el tope lleno, lo paga la Aduana (entregarBotin).
 *
 * 1.10 · Cada minijefe vive en sus biomas (minijefes.por-bioma, Habitat): el de cordura cero
 * (Hardcore.minijefeSiTocaCordura) es el del bioma donde estas, y el Reclamo (Reclamo, que nace y
 * se para aqui) llama a ese mismo. Hasta la 1.9 salia uno de los cinco al azar en cualquier sitio y
 * no habia forma de ir por el Sello que faltaba.
 *
 * Los cinco tipos se reconocen por el id de su ficha de /esb (MinionManager.typeOf), que son
 * los nombres de fichero de Esbirros/lethal-world-minijefes.
 */
final class Minijefes {

    /** Los cinco de Calamity, con el nombre que se ve (el Sello lo lleva). */
    private static final Map<String, String> NOMBRES = new LinkedHashMap<>();

    static {
        NOMBRES.put("custodio-de-las-ruinas", "Custodio de las Ruinas");
        NOMBRES.put("centinela-de-toba", "Centinela de Toba");
        NOMBRES.put("matriarca-tejedora", "Matriarca Tejedora");
        NOMBRES.put("sanador-del-fango", "Sanador del Fango");
        NOMBRES.put("heraldo-carmes", "Heraldo Carmesí");
    }

    static final List<String> TIPOS = List.copyOf(NOMBRES.keySet());

    /** Calamity 1.10 · Donde se leen la tabla de biomas y el botin extra (raiz del config del plugin). */
    static final String RUTA_POR_BIOMA = "hardcore.minijefes.por-bioma", RUTA_BOTIN = "hardcore.minijefes.botin";

    /**
     * Calamity 1.10 · Donde vive un minijefe: como se le dice al jugador ("los pantanos", lo usa Tu
     * camino) y sus biomas, ya como Clima.clave ("panacea/sweltering_swamp": sin namespace ni mayusculas).
     */
    record Habitat(String donde, List<String> biomas) {
    }

    /**
     * minijefes.por-bioma de serie: los trece biomas de Panacea (bracken:panacea/<id>, comprobados en el
     * datapack de Bracken), repartidos entre los cinco. Vale si ni el config del servidor ni el del jar
     * traen la seccion: el del servidor es de antes de la 1.10 y no se actualiza solo.
     */
    static final Map<String, Habitat> POR_BIOMA_DE_SERIE;

    static {
        Map<String, Habitat> m = new LinkedHashMap<>();
        m.put("custodio-de-las-ruinas", new Habitat("las llanuras de hongos, el trópico y el valle de bambú",
                List.of("panacea/polypore_plains", "panacea/horsetail_tropics", "panacea/bamboo_valley")));
        m.put("matriarca-tejedora", new Habitat("las junglas y el colmenar",
                List.of("panacea/hungering_jungle", "panacea/ravenous_greenwood", "panacea/honeybee_biome")));
        m.put("sanador-del-fango", new Habitat("los pantanos",
                List.of("panacea/sweltering_swamp", "panacea/wildflower_bog")));
        m.put("centinela-de-toba", new Habitat("la taiga condenada, el dominio creeper y el cónclave",
                List.of("panacea/condemned_taiga", "panacea/creeper_dominion", "panacea/conure_conclave")));
        m.put("heraldo-carmes", new Habitat("el organismo carmesí y los manantiales de arena",
                List.of("panacea/crimson_organism", "panacea/quicksand_springs")));
        POR_BIOMA_DE_SERIE = Collections.unmodifiableMap(m);
    }

    /** Los "para" de una entrada de botin: el que mas dano hizo, el que lo mato o cada participante. */
    static final List<String> PARAS = List.of("mejor", "asesino", "participantes");

    /**
     * Calamity 1.10 · Una entrada de minijefes.botin.
     *
     * @param prob    0..1 (lo que se sale de ahi se recorta)
     * @param para    mejor | asesino | participantes (PARAS)
     * @param piedad  a la N-esima muerte de ese tipo sin que le caiga, le toca seguro; 0 = sin piedad
     * @param objeto  un objeto de Entregas.dar ("libro" pasa por su tope del mes); "" = ninguno
     * @param comando de consola con %jugador%; solo se usa si no hay objeto
     * @param nombre  como se le dice al jugador ("un libro LEGENDARY")
     * @param anuncio si se anuncia a todo el servidor
     */
    record Botin(String id, double prob, String para, int piedad, String objeto, String comando, String nombre,
                 boolean anuncio) {

        /** Sin objeto ni comando no hay nada que dar (las gemas, hasta que llegue su lote): ni tira ni cuenta piedad. */
        boolean entregable() {
            return !objeto.isEmpty() || !comando.isEmpty();
        }

        /** El libro y los comandos se dan por consola, al momento: a un desconectado no le llegarian. */
        boolean porConsola() {
            return objeto.isEmpty() || objeto.equals("libro");
        }
    }

    /**
     * Lo que sale de una entrada de botin para un jugador: si le cae, por que (tirada o piedad) y su
     * piedad antes y despues (sin piedad en la entrada, 0 y 0).
     */
    record Caida(UUID jugador, Botin botin, boolean participa, boolean cae, String porQue, int piedadAntes,
                 int piedadDespues) {
    }

    /** minijefes.botin de serie: lo comun ("todos") y lo de cada tipo. Vale si ni el servidor ni el jar lo traen. */
    static final Map<String, List<Botin>> BOTIN_DE_SERIE;

    static {
        Map<String, List<Botin>> m = new LinkedHashMap<>();
        m.put("todos", List.of(new Botin("libro", 0.08, "mejor", 0, "libro", "", "un libro LEGENDARY", false)));
        m.put("custodio-de-las-ruinas", List.of(mascota("iron_golem", "el huevo del Gólem de hierro"),
                gemaSinLote("la Gema del Custodio")));
        m.put("matriarca-tejedora", List.of(mascota("cave_spider", "el huevo de la Araña de cueva"),
                gemaSinLote("la Gema de la Matriarca")));
        m.put("sanador-del-fango", List.of(mascota("evoker", "el huevo del Invocador"),
                gemaSinLote("la Gema del Sanador")));
        m.put("centinela-de-toba", List.of(mascota("wither_skeleton", "el huevo del Esqueleto wither"),
                gemaSinLote("la Gema del Centinela")));
        m.put("heraldo-carmes", List.of(mascota("ravager", "el huevo del Devastador"),
                gemaSinLote("la Gema del Heraldo")));
        BOTIN_DE_SERIE = Collections.unmodifiableMap(m);
    }

    private static Botin mascota(String bicho, String nombre) {
        return new Botin("mascota", 0.03, "mejor", 30, "", "pets egg unique " + bicho + " %jugador%", nombre, true);
    }

    /** Las gemas de cada minijefe llegan en otro lote: de momento sin objeto, asi que no salen. */
    private static Botin gemaSinLote(String nombre) {
        return new Botin("gema", 0.25, "mejor", 0, "", "", nombre, false);
    }

    /** Lo que le toca a cada uno en una muerte. */
    record Parte(UUID jugador, double fraccion, boolean participa, boolean asesino, boolean mejor, int esencias,
                 long mc, List<Integer> grados, boolean sello, String porQue, int piedadAntes, int piedadDespues) {
    }

    /** Los numeros del reparto, de la config con sus valores de serie. */
    record Reglas(int base, int cadaNiveles, double participacion, Map<Integer, Double> drop, double selloProb,
                  int piedad, long mcMin, long mcMax) {

        static Reglas de(ConfigurationSection hardcore, ConfigurationSection mobs) {
            return new Reglas(hardcore.getInt("esencias.minijefe.base", 1),
                    Math.max(1, hardcore.getInt("esencias.minijefe.cada-niveles", 25)),
                    hardcore.getDouble("esencias.minijefe.participacion-minima", 0.10),
                    Grifo.probs(hardcore, "reliquias.drop.minijefe", Map.of(3, 1.0, 2, 0.30)),
                    hardcore.getDouble("reliquias.sello-minijefe.prob", 0.10),
                    Math.max(1, hardcore.getInt("reliquias.sello-minijefe.piedad", 8)),
                    mobs.getLong("mobcoins.minijefe.min", 80), mobs.getLong("mobcoins.minijefe.max", 120));
        }
    }

    private final Hardcore hc;
    private final SecureRandom azar = new SecureRandom();
    /** 1.10: el Reclamo vive y se para con el reparto. Null si no ha podido nacer (el reparto sigue igual). */
    private final Reclamo reclamo;
    /** Los avisos de minijefes.botin ya escritos en la consola: uno por texto, no uno por muerte. */
    private final Set<String> avisados = new HashSet<>();

    Minijefes(Hardcore hc) {
        this.hc = hc;
        // Aparte y protegido: si el Reclamo no arranca, los minijefes se siguen repartiendo.
        this.reclamo = hc.valor("reclamo", () -> new Reclamo(hc), null);
        Autotest.registrar("minijefes", this::autotest);
        Subcomandos.lw().registrar("minijefe",
                "minijefe muerte <tipo> <N> <jugador:fracción>,...: simula un reparto (el primero es el asesino)",
                "ederus.mundos", this::comandoMuerte, this::tabMuerte);
        Subcomandos.lw().registrar("piedad", "piedad <jugador> [tipo] [n]: ver o poner la piedad de los Sellos",
                "ederus.mundos", this::comandoPiedad, args -> switch (args.length) {
                    case 2 -> Reliquias.conectados();
                    case 3 -> TIPOS;
                    default -> List.of();
                });
        PlaceholdersLethal.registrar("piedad", (jugador, tipo) -> {
            if (jugador == null || tipo == null || tipo.isBlank()) return "";
            return piedad(jugador.getUniqueId(), tipo) + "/" + Reglas.de(hc.cfg(), mobsCfg()).piedad();
        });
    }

    void parar() {
        // Los Reclamos que esten sonando se devuelven antes de que se pare Entregas (va despues).
        if (reclamo != null) hc.seguro("reclamo", reclamo::parar);
    }

    /** "heraldo-carmes" -> "Heraldo Carmesí"; uno que no conoce, con los guiones como espacios. */
    static String nombre(String tipo) {
        if (tipo == null || tipo.isBlank()) return "minijefe";
        String n = NOMBRES.get(tipo.toLowerCase(Locale.ROOT));
        if (n != null) return n;
        String t = tipo.replace('-', ' ');
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** El nombre para la Bitacora; uno que el servidor no conoce sale por su UUID. */
    static String nombreDe(OfflinePlayer op) {
        String n = op == null ? null : op.getName();
        return n != null ? n : (op == null ? "?" : op.getUniqueId().toString());
    }

    private ConfigurationSection mobsCfg() {
        ConfigurationSection s = hc.plugin().getConfig().getConfigurationSection("mobs");
        return s == null ? new YamlConfiguration() : s;
    }

    int piedad(UUID jugador, String tipo) {
        return hc.datos().getInt("piedad." + jugador + "." + tipo.toLowerCase(Locale.ROOT), 0);
    }

    private void piedad(UUID jugador, String tipo, int n) {
        hc.datos().set("piedad." + jugador + "." + tipo.toLowerCase(Locale.ROOT), Math.max(0, n));
        hc.marcarSucio();
    }

    /** "el Custodio de las Ruinas", "la Matriarca Tejedora" (el mismo criterio que Forja.delMinijefe). */
    static String elMinijefe(String tipo) {
        String n = nombre(tipo);
        return (tipo != null && tipo.toLowerCase(Locale.ROOT).startsWith("matriarca") ? "la " : "el ") + n;
    }

    /** Con la primera en mayuscula, para empezar una frase. */
    static String mayuscula(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------ donde vive cada uno (1.10)

    /**
     * minijefes.por-bioma tal como lo ve Calamity: el del config del servidor; si no lo trae (un config
     * de antes de la 1.10, que no se actualiza solo), el del config.yml del jar; y si tampoco, el de
     * serie. Se lee sin crear la seccion (Apariciones.seccion): creada vacia en memoria, el siguiente
     * saveConfig la dejaria escrita y vacia, y ningun minijefe volveria a tener bioma. Una seccion
     * vacia a proposito (por-bioma: {}) apaga los biomas: todo vuelve a ser al azar.
     */
    static Map<String, Habitat> porBioma(Configuration config) {
        ConfigurationSection s = config == null ? null : Apariciones.seccion(config, RUTA_POR_BIOMA);
        return s == null ? POR_BIOMA_DE_SERIE : leerPorBioma(s);
    }

    /** Una seccion como minijefes.por-bioma: tipo -> {donde, biomas}. Tipos y biomas en minusculas (Clima.clave). */
    static Map<String, Habitat> leerPorBioma(ConfigurationSection s) {
        Map<String, Habitat> out = new LinkedHashMap<>();
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            Object v = s.get(k, null);
            Object donde;
            Object biomas;
            if (v instanceof ConfigurationSection c) {
                donde = c.get("donde", null);
                biomas = c.get("biomas", null);
            } else if (v instanceof Map<?, ?> m) {
                donde = m.get("donde");
                biomas = m.get("biomas");
            } else {
                continue;
            }
            List<String> claves = new ArrayList<>();
            for (Object b : biomas instanceof List<?> l ? l : biomas == null ? List.of() : List.of(biomas)) {
                if (b != null && !String.valueOf(b).isBlank()) claves.add(Clima.clave(String.valueOf(b)));
            }
            out.put(k.trim().toLowerCase(Locale.ROOT), new Habitat(donde == null ? "" : String.valueOf(donde).trim(),
                    List.copyOf(claves)));
        }
        return out;
    }

    /** El bioma de ese sitio con su namespace ("bracken:panacea/polypore_plains"); dueno() lo pasa por Clima.clave. */
    static String bioma(Location l) {
        if (l == null || l.getWorld() == null) return "";
        return l.getWorld().getBiome(l.getBlockX(), l.getBlockY(), l.getBlockZ()).getKey().toString();
    }

    /**
     * El minijefe que vive en ese bioma segun la tabla, o null si no es de nadie. Vale con namespace o
     * sin el ("bracken:panacea/x" o "panacea/x") y sin mirar mayusculas. Si dos se lo disputan, el
     * primero de la tabla.
     */
    static String dueno(String bioma, Map<String, Habitat> porBioma) {
        if (bioma == null || bioma.isBlank() || porBioma == null) return null;
        String b = Clima.clave(bioma);
        for (Map.Entry<String, Habitat> e : porBioma.entrySet()) if (e.getValue().biomas().contains(b)) return e.getKey();
        return null;
    }

    /** Los minijefes que hay: los de minijefes.tipos; con esa lista vacia, los de la tabla de biomas. */
    static List<String> conocidos(List<String> tipos, Map<String, Habitat> porBioma) {
        List<String> out = new ArrayList<>();
        if (tipos != null) for (String t : tipos) if (t != null && !t.isBlank()) out.add(t.trim());
        if (out.isEmpty() && porBioma != null) out.addAll(porBioma.keySet());
        return out;
    }

    /**
     * Revision 1.10 · Si ese tipo es un minijefe de Calamity: esta en minijefes.tipos o tiene sitio en la
     * tabla de biomas (sin mirar mayusculas). Para el cartel con el nombre de Calamity
     * (CartelesMinijefe, via Hardcore.esTipoMinijefe), que antes solo miraba minijefes.tipos. Quien puede
     * venir lo sigue diciendo conocidos().
     */
    static boolean tipoConocido(List<String> tipos, Map<String, Habitat> porBioma, String tipo) {
        if (tipo == null || tipo.isBlank()) return false;
        String t = tipo.trim();
        if (tipos != null) for (String x : tipos) if (x != null && x.trim().equalsIgnoreCase(t)) return true;
        return porBioma != null && porBioma.containsKey(t.toLowerCase(Locale.ROOT));
    }

    /**
     * El minijefe de ese bioma, solo si es uno de los que hay (conocidos): un tipo quitado de
     * minijefes.tipos queda apagado tambien en su bioma. Es el que llama el Reclamo; null = ninguno.
     * Devuelve el id tal cual esta en minijefes.tipos (es el que entiende /esb).
     */
    static String delBioma(String bioma, Map<String, Habitat> porBioma, List<String> tipos) {
        String d = dueno(bioma, porBioma);
        if (d == null) return null;
        for (String t : conocidos(tipos, porBioma)) if (t.equalsIgnoreCase(d)) return t;
        return null;
    }

    /**
     * El minijefe que viene con la cordura a cero: el del bioma y, en un bioma sin dueno (o con la
     * tabla vacia), uno al azar de los que hay, como hasta la 1.9. azar recibe cuantos hay y devuelve
     * la posicion (SecureRandom::nextInt en el juego). Null si no hay ninguno.
     */
    static String elegir(String bioma, Map<String, Habitat> porBioma, List<String> tipos, IntUnaryOperator azar) {
        String suyo = delBioma(bioma, porBioma, tipos);
        if (suyo != null) return suyo;
        List<String> hay = conocidos(tipos, porBioma);
        if (hay.isEmpty()) return null;
        return hay.get(Math.floorMod(azar.applyAsInt(hay.size()), hay.size()));
    }

    /** "los pantanos": donde vive ese minijefe segun la tabla, o null si no lo dice. */
    static String donde(Map<String, Habitat> porBioma, String tipo) {
        if (porBioma == null || tipo == null) return null;
        Habitat h = porBioma.get(tipo.toLowerCase(Locale.ROOT));
        return h == null || h.donde().isBlank() ? null : h.donde();
    }

    // ------------------------------------------------------------------ reparto

    /**
     * Muerte real (la llama el Grifo). danos = lo que le quito cada jugador; se pasa a
     * fraccion de su vida maxima. El asesino va primero aunque no aparezca en el mapa.
     */
    void alMorir(LivingEntity mob, Player asesino, int nivel, String tipo, Map<UUID, Double> danos) {
        double vida = Math.max(1, Compat.getAttribute(mob, "max_health", Math.max(1, mob.getHealth())));
        LinkedHashMap<OfflinePlayer, Double> fr = new LinkedHashMap<>();
        if (asesino != null) fr.put(asesino, danos == null ? 0 : danos.getOrDefault(asesino.getUniqueId(), 0.0) / vida);
        if (danos != null) {
            for (Map.Entry<UUID, Double> d : danos.entrySet()) {
                if (asesino != null && d.getKey().equals(asesino.getUniqueId())) continue;
                fr.put(hc.plugin().getServer().getOfflinePlayer(d.getKey()), d.getValue() / vida);
            }
        }
        if (fr.isEmpty()) return;
        repartir(tipo, nivel, asesino, fr);
    }

    /** Para /lw hardcore minijefe muerte: el primero de fracciones es el asesino. */
    void simularMuerte(String tipo, int nivel, LinkedHashMap<OfflinePlayer, Double> fracciones) {
        if (fracciones == null || fracciones.isEmpty()) return;
        repartir(tipo, nivel, fracciones.keySet().iterator().next(), fracciones);
    }

    private void repartir(String tipo, int nivel, OfflinePlayer asesino, LinkedHashMap<OfflinePlayer, Double> fracciones) {
        String t = tipo == null || tipo.isBlank() ? null : tipo.toLowerCase(Locale.ROOT);
        Reglas r = Reglas.de(hc.cfg(), mobsCfg());
        Grifo grifo = hc.grifo();
        Map<UUID, OfflinePlayer> quien = new HashMap<>();
        LinkedHashMap<UUID, Double> fr = new LinkedHashMap<>();
        Map<UUID, Integer> piedadAntes = new HashMap<>();
        Map<UUID, Double> f = new HashMap<>();
        for (Map.Entry<OfflinePlayer, Double> e : fracciones.entrySet()) {
            UUID u = e.getKey().getUniqueId();
            quien.put(u, e.getKey());
            fr.put(u, e.getValue() == null ? 0 : e.getValue());
            if (t != null) piedadAntes.put(u, piedad(u, t));
            f.put(u, grifo == null ? 1.0 : grifo.f(u));
        }
        Eclipse ec = hc.eclipse();
        double eclipse = ec == null ? 1.0 : hc.valor("eclipse", ec::factorBotin, 1.0);
        List<Parte> partes = planificar(t, nivel, asesino == null ? null : asesino.getUniqueId(), fr, piedadAntes,
                f, eclipse, azar::nextDouble, r);

        Aduana ad = hc.aduana();
        Reliquias rel = hc.reliquias();
        boolean hayReliquias = rel != null && rel.activas();
        List<String> resumen = new ArrayList<>();
        for (Parte p : partes) {
            OfflinePlayer op = quien.get(p.jugador());
            resumen.add(nombreDe(op) + ":" + Math.round(p.fraccion() * 100) + "%");
            if (t != null && p.participa()) piedad(p.jugador(), t, p.piedadDespues());

            List<ItemStack> items = new ArrayList<>();
            if (hayReliquias) {
                // 1.10: con el tipo, para que su lore diga de quien cayo ("Cayó del Heraldo Carmesí").
                for (int g : p.grados()) items.add(rel.crear(g, "minijefe", null, nivel, t, false));
                if (p.sello()) items.add(rel.crear(4, "minijefe", Reliquias.SELLO, nivel, t, false));
            }
            // 1.4: las de mas del equipo (esencias-bonus) de quien siga conectado, antes de la Aduana.
            int esencias = hc.esenciasDelEquipo(op.getPlayer(), p.esencias());
            // 1.7.6: y su boost de MobCoins de EDM (desconectado, sin boost), tambien antes de la Aduana.
            long mc = MobCoins.conBoost(op.getPlayer(), p.mc());
            if (ad != null && (esencias > 0 || mc > 0 || !items.isEmpty())) {
                Aduana.Pago pago = ad.pagar(op, "minijefe", esencias, mc, items,
                        "minijefe " + (t == null ? "?" : t) + " N" + nivel);
                if (pago != null && grifo != null) {
                    grifo.apuntarEsencias(p.jugador(), pago.esencias());
                    grifo.destelloEsencias(op.getPlayer(), pago.esencias(), pago.mc());
                }
            }
            if (p.sello()) {
                hc.plugin().bitacora().anotar("minijefe", "sello", nombreDe(op), t, p.porQue(),
                        "piedad " + p.piedadAntes());
            }
            if (t != null && p.participa()) {
                Map<String, Object> campos = new LinkedHashMap<>();
                campos.put("minijefe", t);
                campos.put("valor", p.piedadDespues());
                campos.put("sello", p.sello() ? "si" : "no");
                campos.put("mejor_danador", p.mejor() ? "si" : "no");
                Telemetria te = hc.telemetria();
                if (te != null) hc.seguro("telemetria", () -> te.suceso("piedad", op, campos));
            }
            if (p.participa()) {
                Estadisticas st = hc.estadisticas();
                if (st != null) st.sumar(p.jugador(), "minijefes", 1);
            }
            if (p.asesino() && op.getPlayer() != null) {
                Contratos ct = hc.contratos();
                if (ct != null) hc.seguro("contratos", () -> ct.progreso(op.getPlayer(), "minijefe", 1));
            }
        }
        // 1.10: el botin extra de minijefes.botin, despues de lo de siempre (tambien en la simulacion).
        UUID idAsesino = asesino == null ? null : asesino.getUniqueId();
        hc.seguro("minijefes", () -> botinExtra(t, nivel, idAsesino, quien, fr, r.participacion()));
        hc.plugin().bitacora().anotar("minijefe", "muere", t == null ? "?" : t, "N " + nivel,
                String.join(",", resumen));
        hc.marcarSucio();
    }

    /**
     * El reparto sin Bukkit ni escrituras: quien cobra que. El orden de fracciones se respeta
     * (el asesino, primero). Las tiradas salen de azar en este orden: MC del asesino, sus
     * Reliquias por grado de mayor a menor, y la del Sello del mejor danador.
     */
    static List<Parte> planificar(String tipo, int nivel, UUID asesino, LinkedHashMap<UUID, Double> fracciones,
                                  Map<UUID, Integer> piedadAntes, Map<UUID, Double> f, double eclipse,
                                  DoubleSupplier azar, Reglas r) {
        UUID mejor = mejorDe(fracciones, r.participacion());
        int esBase = r.base() + nivel / r.cadaNiveles();
        List<Parte> out = new ArrayList<>();
        for (Map.Entry<UUID, Double> e : fracciones.entrySet()) {
            UUID u = e.getKey();
            double fu = f.getOrDefault(u, 1.0);
            boolean participa = e.getValue() >= r.participacion();
            boolean esAsesino = u.equals(asesino);
            boolean esMejor = u.equals(mejor);
            int esencias = participa ? (int) Math.round(esBase * fu * eclipse) : 0;

            long mc = 0;
            List<Integer> grados = new ArrayList<>();
            if (esAsesino) {
                long min = Math.min(r.mcMin(), r.mcMax()), max = Math.max(r.mcMin(), r.mcMax());
                mc = min + (long) Math.floor(azar.getAsDouble() * (max - min + 1));
                List<Integer> orden = new ArrayList<>(r.drop().keySet());
                orden.sort((a, b) -> b - a);
                for (int g : orden) {
                    if (azar.getAsDouble() < r.drop().get(g) * fu * eclipse) grados.add(g);
                }
            }

            int antes = piedadAntes.getOrDefault(u, 0);
            int despues = antes;
            boolean sello = false;
            String porQue = null;
            if (tipo != null && participa) {
                despues = antes + 1;
                if (esMejor && azar.getAsDouble() < r.selloProb() * fu * eclipse) {
                    sello = true;
                    porQue = "tirada";
                } else if (despues >= r.piedad()) {
                    sello = true;
                    porQue = "piedad";
                }
                if (sello) despues = 0;
            }
            out.add(new Parte(u, e.getValue(), participa, esAsesino, esMejor, esencias, mc, grados, sello, porQue,
                    antes, despues));
        }
        return out;
    }

    /** El mejor danador: el que mas le quito entre los que llegan a la participacion minima (empate: el primero). */
    static UUID mejorDe(LinkedHashMap<UUID, Double> fracciones, double participacion) {
        UUID mejor = null;
        double top = -1;
        for (Map.Entry<UUID, Double> e : fracciones.entrySet()) {
            if (e.getValue() >= participacion && e.getValue() > top) {
                top = e.getValue();
                mejor = e.getKey();
            }
        }
        return mejor;
    }

    // ------------------------------------------------------------------ botin extra (1.10)

    /**
     * Las entradas de botin de un tipo: las de "todos" y despues las suyas, en el orden de la config. s es
     * minijefes.botin (null = la de serie). Si la seccion esta, manda entera: un tipo que no aparece no
     * tiene entradas propias. Una entrada que no se entiende (sin id, con un "para" que no existe, un id
     * repetido en el tipo, un comando sin %jugador%) no sale y se dice en avisos. Las que no traen objeto
     * ni comando SI salen: tirarBotin las salta en silencio (las gemas, hasta que llegue su lote).
     */
    static List<Botin> leerBotin(ConfigurationSection s, String tipo, List<String> avisos) {
        String t = tipo == null ? "" : tipo.trim().toLowerCase(Locale.ROOT);
        List<Botin> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String grupo : t.isEmpty() || t.equals("todos") ? List.of("todos") : List.of("todos", t)) {
            List<Botin> lista = new ArrayList<>();
            if (s == null) {
                lista.addAll(BOTIN_DE_SERIE.getOrDefault(grupo, List.of()));
            } else {
                // Con valor por defecto explicito: asi no se mira el jar, y la lista del servidor manda.
                List<?> crudo = s.getList(grupo, null);
                for (int i = 0; crudo != null && i < crudo.size(); i++) {
                    Object o = crudo.get(i);
                    String donde = grupo + " (entrada " + (i + 1) + ")";
                    Map<?, ?> m = o instanceof Map<?, ?> mm ? mm : o instanceof ConfigurationSection cs ? cs.getValues(false) : null;
                    if (m == null) {
                        avisos.add(donde + ": no es una entrada {id: ..., prob: ...}");
                        continue;
                    }
                    Botin b = botinDe(m, donde, avisos);
                    if (b != null) lista.add(b);
                }
            }
            for (Botin b : lista) {
                if (!ids.add(b.id())) {
                    avisos.add(grupo + ": el id '" + b.id() + "' ya está en este tipo; vale el primero");
                    continue;
                }
                out.add(b);
            }
        }
        return out;
    }

    /** Una entrada de la config, o null (con su aviso) si no se entiende. */
    static Botin botinDe(Map<?, ?> m, String donde, List<String> avisos) {
        String id = texto(m.get("id")).toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            avisos.add(donde + ": no tiene id");
            return null;
        }
        // El id va en la ruta de la piedad (piedad-botin.<uuid>.<tipo>.<id>): un punto la partiria.
        if (id.contains(".")) {
            avisos.add(donde + ": el id '" + id + "' no puede llevar puntos");
            return null;
        }
        String para = texto(m.get("para")).toLowerCase(Locale.ROOT);
        if (para.isEmpty()) para = "mejor";
        if (!PARAS.contains(para)) {
            avisos.add(donde + ": 'para: " + para + "' no existe (mejor, asesino o participantes)");
            return null;
        }
        String objeto = texto(m.get("objeto")).toLowerCase(Locale.ROOT);
        String comando = texto(m.get("comando"));
        if (objeto.isEmpty() && !comando.isEmpty() && !comando.contains("%jugador%")) {
            avisos.add(donde + ": el comando no lleva %jugador%, así que no se sabe a quién dárselo");
            return null;
        }
        double prob = Math.max(0, Math.min(1, numero(m.get("prob"), 0)));
        int piedad = (int) Math.max(0, numero(m.get("piedad"), 0));
        String nombre = texto(m.get("nombre"));
        return new Botin(id, prob, para, piedad, objeto, comando, nombre.isEmpty() ? id : nombre,
                Boolean.parseBoolean(texto(m.get("anuncio"))));
    }

    private static String texto(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static double numero(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? def : Double.parseDouble(String.valueOf(o).trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** La clave de la piedad de un jugador en una entrada, en el mapa que recibe tirarBotin. */
    static String clavePiedad(UUID jugador, String id) {
        return jugador + "|" + id;
    }

    /**
     * El botin extra de una muerte, sin Bukkit ni escrituras: por cada entrada que se puede dar y cada
     * jugador de fracciones (en su orden), si le cae, por que, y su piedad antes y despues. Las tiradas
     * salen de azar en ese orden, solo para quien tira.
     *  - Tira quien diga "para": mejor = el que mas dano hizo entre los que llegan a la participacion
     *    minima; asesino = el que lo mato, llegue o no (como sus MobCoins y sus Reliquias);
     *    participantes = cada uno con la participacion minima, por separado.
     *  - La piedad (si la entrada la tiene) sube a cada participante al que no le cae y vuelve a 0 al
     *    caerle; llena, le cae aunque no le toque tirar, como el Sello.
     *  - Una entrada sin objeto ni comando no sale: ni tira ni cuenta piedad.
     *  - Lo que se da por consola (el libro, los comandos) no tira ni cuenta para quien no esta conectado
     *    (conectado): no le llegaria, y perderia la piedad por algo que no pudo recibir.
     * Solo salen en la lista los jugadores con algo que apuntar (tiran o llevan la cuenta de la piedad).
     */
    static List<Caida> tirarBotin(List<Botin> entradas, UUID asesino, LinkedHashMap<UUID, Double> fracciones,
                                  double participacion, Map<String, Integer> piedadAntes, Predicate<UUID> conectado,
                                  DoubleSupplier azar) {
        List<Caida> out = new ArrayList<>();
        if (entradas == null || fracciones == null) return out;
        UUID mejor = mejorDe(fracciones, participacion);
        for (Botin b : entradas) {
            if (!b.entregable()) continue;
            for (Map.Entry<UUID, Double> e : fracciones.entrySet()) {
                UUID u = e.getKey();
                boolean participa = e.getValue() != null && e.getValue() >= participacion;
                boolean tira = switch (b.para()) {
                    case "asesino" -> u.equals(asesino);
                    case "participantes" -> participa;
                    default -> u.equals(mejor);
                };
                boolean cuenta = participa && b.piedad() > 0;
                if (!tira && !cuenta) continue;
                if (b.porConsola() && !conectado.test(u)) continue;
                int antes = b.piedad() > 0 ? Math.max(0, piedadAntes.getOrDefault(clavePiedad(u, b.id()), 0)) : 0;
                boolean cae = false;
                String porQue = null;
                if (tira && azar.getAsDouble() < b.prob()) {
                    cae = true;
                    porQue = "tirada";
                } else if (cuenta && antes + 1 >= b.piedad()) {
                    cae = true;
                    porQue = "piedad";
                }
                int despues = b.piedad() <= 0 || cae ? 0 : cuenta ? antes + 1 : antes;
                out.add(new Caida(u, b, participa, cae, porQue, antes, despues));
            }
        }
        return out;
    }

    private static String rutaPiedadBotin(UUID jugador, String tipo, String id) {
        return "piedad-botin." + jugador + "." + tipo.toLowerCase(Locale.ROOT) + "." + id;
    }

    int piedadBotin(UUID jugador, String tipo, String id) {
        return hc.datos().getInt(rutaPiedadBotin(jugador, tipo, id), 0);
    }

    /** A 0 se borra: con cinco tipos y varias entradas por jugador, los ceros solo engordarian el fichero. */
    private void piedadBotin(UUID jugador, String tipo, String id, int n) {
        hc.datos().set(rutaPiedadBotin(jugador, tipo, id), n > 0 ? n : null);
        hc.marcarSucio();
    }

    /** minijefes.botin tal como lo ve Calamity (el del servidor, si no el del jar), o null = el de serie. */
    private ConfigurationSection seccionBotin() {
        return Apariciones.seccion(hc.plugin().getConfig(), RUTA_BOTIN);
    }

    /** Cada aviso de la config una vez en la consola (se lee en cada muerte). */
    private void avisar(List<String> avisos) {
        for (String a : avisos) {
            if (avisados.size() < 256 && avisados.add(a)) hc.plugin().getLogger().warning("[Calamity] " + RUTA_BOTIN + "." + a);
        }
    }

    /**
     * El botin extra de una muerte (tirarBotin) con las escrituras: la piedad se apunta y se guarda
     * ANTES de entregar nada (una caida a mitad puede perder una entrega, nunca darla dos veces) y luego
     * se entrega lo que cae. Azar de SecureRandom. Sin tipo no hay botin extra (ni piedad que llevar).
     */
    private void botinExtra(String t, int nivel, UUID asesino, Map<UUID, OfflinePlayer> quien,
                            LinkedHashMap<UUID, Double> fr, double participacion) {
        if (t == null || fr.isEmpty()) return;
        List<String> avisos = new ArrayList<>();
        List<Botin> entradas = leerBotin(seccionBotin(), t, avisos);
        avisar(avisos);
        if (entradas.isEmpty()) return;
        Map<String, Integer> antes = new HashMap<>();
        for (Botin b : entradas) {
            if (b.piedad() <= 0) continue;
            for (UUID u : fr.keySet()) antes.put(clavePiedad(u, b.id()), piedadBotin(u, t, b.id()));
        }
        List<Caida> caidas = tirarBotin(entradas, asesino, fr, participacion, antes, u -> {
            OfflinePlayer op = quien.get(u);
            return op != null && op.getPlayer() != null;
        }, azar::nextDouble);
        boolean cae = false;
        for (Caida c : caidas) {
            if (c.botin().piedad() > 0 && c.piedadDespues() != c.piedadAntes()) piedadBotin(c.jugador(), t, c.botin().id(), c.piedadDespues());
            cae |= c.cae();
        }
        // Sin nada que entregar, la piedad va al disco en el minuto (marcarSucio); con algo, ya mismo.
        if (!cae) return;
        hc.guardarYa();
        for (Caida c : caidas) {
            OfflinePlayer op = quien.get(c.jugador());
            if (c.cae() && op != null) hc.seguro("minijefes", () -> entregarBotin(c, op, t, nivel));
        }
    }

    /**
     * Da una entrada que ha caido: un objeto por Entregas.dar (el libro con su tope del mes) o su
     * comando por Entregas.comando, que valida el nombre. Si no llega, la piedad vuelve a donde estaria
     * sin esta caida y se dice. Bitacora, telemetria (minijefe-botin), el aviso al jugador y, si la
     * entrada lo pide, el anuncio.
     *
     * Revision 1.10: con el tope de libros del mes lleno, su sustituto en Esencias
     * (caja.libro-sustituto-esencias) se paga por la Aduana como el resto del minijefe (tipo minijefe) y
     * no por Entregas.libro, que lo mete en el saldo: dentro de Calamity llega como objeto (si muere antes
     * de salir, lo pierde, como cualquier botin), cuenta en los topes y en lo que decae por hora (Grifo).
     * El libro de las cajas sigue por Entregas.
     */
    private void entregarBotin(Caida c, OfflinePlayer op, String t, int nivel) {
        Botin b = c.botin();
        Entregas ent = hc.entregas();
        boolean sustituto = false;
        int esencias = 0;
        boolean ok;
        if (ent == null) {
            ok = false;
        } else if (b.objeto().equals("libro") && !ent.libroLibre()) {
            sustituto = true;
            Aduana ad = hc.aduana();
            Aduana.Pago pago = ad == null ? null : ad.pagar(op, "minijefe", hc.cfg().getInt("caja.libro-sustituto-esencias", 20),
                    0, List.of(), "minijefe " + t + " N" + nivel + " botin:" + b.id() + " sustituto");
            ok = pago != null;
            esencias = pago == null ? 0 : pago.esencias();
            Grifo grifo = hc.grifo();
            if (grifo != null) grifo.apuntarEsencias(op.getUniqueId(), esencias);
        } else if (!b.objeto().isEmpty()) {
            ok = ent.dar(null, b.objeto(), op, 1, "minijefe:" + t + ":" + b.id());
        } else {
            ok = ent.comando(b.comando(), op.getName(), 1);
        }
        String resultado = !ok ? "fallo" : sustituto ? "sustituto" : "entregado";
        if (!ok && b.piedad() > 0) piedadBotin(c.jugador(), t, b.id(), c.participa() ? c.piedadAntes() + 1 : c.piedadAntes());
        hc.plugin().bitacora().anotar("minijefe", "botin-" + resultado, nombreDe(op), t, b.id(), c.porQue(),
                "piedad " + c.piedadAntes(), "N " + nivel);

        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("minijefe", t);
        campos.put("id", b.id());
        campos.put("para", b.para());
        campos.put("por", c.porQue());
        campos.put("resultado", resultado);
        campos.put("piedad", c.piedadAntes());
        campos.put("nivel", nivel);
        Telemetria te = hc.telemetria();
        if (te != null) hc.seguro("telemetria", () -> te.suceso("minijefe-botin", op, campos));

        Player p = op.getPlayer();
        // La raiz sin color: lo que no lo lleva sale en el normal (Paleta.mensaje) y no en el del minijefe.
        Component quienDeja = Component.text().append(Component.text(mayuscula(elMinijefe(t)), Paleta.DETALLE))
                .append(Component.text(" te ha dejado ")).build();
        if (p != null) {
            if (resultado.equals("entregado")) {
                p.sendMessage(ComandoCalamity.mensaje(quienDeja.append(Component.text(b.nombre(), Paleta.MARCA))
                        .append(Component.text("."))));
                if (b.anuncio()) Compat.soundPlayers(p.getWorld(), p.getLocation(), "ui.toast.challenge_complete", 0.8f, 1.1f);
            } else if (resultado.equals("sustituto")) {
                // Las que paga de verdad la Aduana; con su tope del dia lleno ya lo dice ella.
                if (esencias > 0) {
                    p.sendMessage(ComandoCalamity.mensaje(quienDeja.append(Paleta.cifra(Marco.esencias(esencias)))
                            .append(Component.text(" en lugar de " + b.nombre() + ": este mes ya no quedan."))));
                }
            } else {
                p.sendMessage(ComandoCalamity.mensaje(quienDeja.append(Component.text(b.nombre(), Paleta.MARCA))
                        .append(Component.text(", pero no se ha podido entregar. Avisa al staff.", Paleta.AVISO))));
            }
        }
        if (resultado.equals("entregado") && b.anuncio()) {
            hc.plugin().getServer().broadcast(ComandoCalamity.mensaje(Component.text()
                    .append(Component.text(nombreDe(op), Paleta.DETALLE))
                    .append(Component.text(" ha conseguido "))
                    .append(Component.text(b.nombre(), Paleta.MARCA))
                    .append(Component.text(" " + Forja.delMinijefe(t) + ".")).build()));
        }
    }

    // ------------------------------------------------------------------ comandos

    /** minijefe muerte <tipo> <N> Dosa__:0.6,Otro:0.3,Tercero:0.05 */
    private void comandoMuerte(CommandSender quien, String[] args) {
        if (args.length < 5 || !args[1].equalsIgnoreCase("muerte")) {
            quien.sendMessage(ComandoCalamity.mensaje(
                    "Uso: /calamidad minijefe muerte <tipo> <N> <jugador:fracción>,... (el primero es el asesino)"));
            return;
        }
        String tipo = args[2].toLowerCase(Locale.ROOT);
        int nivel;
        try {
            nivel = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            quien.sendMessage(ComandoCalamity.mensaje("El nivel tiene que ser un número."));
            return;
        }
        LinkedHashMap<OfflinePlayer, Double> fr = new LinkedHashMap<>();
        String lista = String.join(",", java.util.Arrays.copyOfRange(args, 4, args.length));
        for (String trozo : lista.split(",")) {
            if (trozo.isBlank()) continue;
            String[] kv = trozo.trim().split(":");
            if (kv.length != 2) {
                quien.sendMessage(ComandoCalamity.mensaje("No entiendo \"" + trozo + "\": se escribe jugador:fracción."));
                return;
            }
            double v;
            try {
                v = Double.parseDouble(kv[1].replace("%", "").replace(',', '.'));
            } catch (NumberFormatException e) {
                quien.sendMessage(ComandoCalamity.mensaje("La fracción de \"" + trozo + "\" no es un número."));
                return;
            }
            // 0.6 o 60: lo que pasa de 1 se lee como porcentaje.
            if (v > 1) v /= 100.0;
            OfflinePlayer op = Reliquias.jugador(kv[0]);
            if (op == null) {
                quien.sendMessage(ComandoCalamity.mensaje("No encuentro a " + kv[0] + "."));
                return;
            }
            fr.put(op, v);
        }
        if (fr.isEmpty()) {
            quien.sendMessage(ComandoCalamity.mensaje("Hace falta al menos un jugador."));
            return;
        }
        simularMuerte(tipo, nivel, fr);
        quien.sendMessage(ComandoCalamity.mensaje("Reparto de " + nombre(tipo) + " N" + nivel
                + " hecho: mira la Bitácora (pago | ... | minijefe)."));
    }

    private List<String> tabMuerte(String[] args) {
        return switch (args.length) {
            case 2 -> List.of("muerte");
            case 3 -> TIPOS;
            case 4 -> List.of("50");
            case 5 -> {
                List<String> out = new ArrayList<>();
                for (String n : Reliquias.conectados()) out.add(n + ":1.0");
                yield out;
            }
            default -> List.of();
        };
    }

    /** piedad <jugador> [tipo] [n] */
    private void comandoPiedad(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(ComandoCalamity.mensaje("Uso: /calamidad piedad <jugador> [tipo] [n]"));
            return;
        }
        OfflinePlayer op = Reliquias.jugador(args[1]);
        if (op == null) {
            quien.sendMessage(ComandoCalamity.mensaje("No encuentro a ese jugador."));
            return;
        }
        int tope = Reglas.de(hc.cfg(), mobsCfg()).piedad();
        if (args.length < 4) {
            List<String> tipos = args.length == 3 ? List.of(args[2].toLowerCase(Locale.ROOT)) : TIPOS;
            StringBuilder sb = new StringBuilder("Piedad de " + op.getName() + ":");
            for (String t : tipos) sb.append(" ").append(t).append(" ").append(piedad(op.getUniqueId(), t)).append("/").append(tope);
            quien.sendMessage(ComandoCalamity.mensaje(sb.toString()));
            return;
        }
        int n;
        try {
            n = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            quien.sendMessage(ComandoCalamity.mensaje("La piedad tiene que ser un número."));
            return;
        }
        String t = args[2].toLowerCase(Locale.ROOT);
        piedad(op.getUniqueId(), t, n);
        hc.plugin().bitacora().anotar("minijefe", "piedad", op.getName(), t, String.valueOf(Math.max(0, n)),
                "admin " + quien.getName());
        quien.sendMessage(ComandoCalamity.mensaje("Piedad de " + op.getName() + " con " + nombre(t) + ": "
                + Math.max(0, n) + "/" + tope + "."));
    }

    // ----------------------------------------------------------------- autotest

    /** Pruebas del reparto; las corre el autotest "grifo" (el modulo que espera probar.py). */
    static void probar(Autotest.Hoja h) {
        Reglas r = Reglas.de(new YamlConfiguration(), new YamlConfiguration());
        UUID dosa = Autotest.sintetico(1), otro = Autotest.sintetico(2), tercero = Autotest.sintetico(3);
        LinkedHashMap<UUID, Double> fr = new LinkedHashMap<>();
        fr.put(dosa, 0.6);
        fr.put(otro, 0.3);
        fr.put(tercero, 0.05);
        DoubleSupplier nunca = () -> 0.999_999;
        List<Parte> p = planificar("heraldo-carmes", 50, dosa, fr, Map.of(), Map.of(), 1.0, nunca, r);
        h.igual("minijefe N50: Esencias del asesino", 3, p.get(0).esencias());
        h.ok("minijefe: MC del asesino entre 80 y 120", p.get(0).mc() >= 80 && p.get(0).mc() <= 120);
        h.igual("minijefe: Esencias de Otro (30 %)", 3, p.get(1).esencias());
        h.igual("minijefe: Otro no cobra MC", 0L, p.get(1).mc());
        h.ok("minijefe: Tercero (5 %) no cobra nada", !p.get(2).participa() && p.get(2).esencias() == 0
                && p.get(2).mc() == 0 && !p.get(2).sello());
        h.ok("minijefe: la III es segura para el asesino", p.get(0).grados().contains(3));
        h.ok("minijefe: sin suerte no hay II ni Sello", !p.get(0).grados().contains(2) && !p.get(0).sello());
        h.igual("minijefe: piedad +1 a Dosa", 1, p.get(0).piedadDespues());
        h.igual("minijefe: piedad +1 a Otro", 1, p.get(1).piedadDespues());
        h.igual("minijefe: Tercero sin piedad", 0, p.get(2).piedadDespues());
        h.ok("minijefe: Dosa es el mejor danador", p.get(0).mejor() && !p.get(1).mejor());

        List<Parte> q = planificar("heraldo-carmes", 50, dosa, fr, Map.of(otro, 7), Map.of(), 1.0, nunca, r);
        h.ok("piedad 7 + esta = 8: el Sello va a Otro", q.get(1).sello() && "piedad".equals(q.get(1).porQue()));
        h.igual("y su piedad vuelve a 0", 0, q.get(1).piedadDespues());
        h.ok("el mejor danador sin suerte no lo tiene", !q.get(0).sello());

        DoubleSupplier siempre = () -> 0.0;
        List<Parte> s = planificar("heraldo-carmes", 50, dosa, fr, Map.of(), Map.of(), 1.0, siempre, r);
        h.ok("con suerte el Sello va al mejor danador", s.get(0).sello() && "tirada".equals(s.get(0).porQue()));
        h.igual("con suerte, III y II", List.of(3, 2), s.get(0).grados());
        h.igual("con suerte, MC al minimo", 80L, s.get(0).mc());

        List<Parte> sinTipo = planificar(null, 50, dosa, fr, Map.of(), Map.of(), 1.0, siempre, r);
        h.ok("sin tipo no hay Sello ni piedad", !sinTipo.get(0).sello() && sinTipo.get(0).piedadDespues() == 0);
        List<Parte> n100 = planificar("heraldo-carmes", 100, dosa, fr, Map.of(), Map.of(dosa, 0.2), 1.0, nunca, r);
        h.igual("N100 con f 0,2: 5 x 0,2 = 1 Esencia", 1, n100.get(0).esencias());
        h.igual("N100 con f 1 para Otro: 5 Esencias", 5, n100.get(1).esencias());
        h.igual("nombre del Heraldo", "Heraldo Carmesí", nombre("heraldo-carmes"));
        h.igual("nombre de uno que no conoce", "Rey de prueba", nombre("rey-de-prueba"));

        probarNombreVisible(h);
    }

    /**
     * 1.10 · /calamidad autotest minijefes: donde vive cada uno (elegir, delBioma) y el botin extra
     * (leerBotin, tirarBotin), sin jugadores ni escrituras; y que la config viva cuadre: cada minijefe de
     * la tabla de biomas tiene que estar en minijefes.tipos, o su bioma se queda sin dueno.
     */
    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        probarPorBioma(h);
        probarBotin(h);
        Map<String, Habitat> vivos = porBioma(hc.plugin().getConfig());
        List<String> tipos = hc.cfg().getStringList("minijefes.tipos");
        if (!tipos.isEmpty()) {
            for (String k : vivos.keySet()) {
                boolean esta = false;
                for (String tp : tipos) esta |= tp.equalsIgnoreCase(k);
                h.ok("config: " + k + " (por-bioma) está en minijefes.tipos", esta);
            }
        }
        Set<String> avisos = new java.util.LinkedHashSet<>();
        for (String k : vivos.isEmpty() ? TIPOS : vivos.keySet()) {
            List<String> estos = new ArrayList<>();
            leerBotin(seccionBotin(), k, estos);
            avisos.addAll(estos);
        }
        h.igual("config: minijefes.botin se entiende entero", List.of(), new ArrayList<>(avisos));
        h.ok("la prueba no toca hardcore-datos.yml", !hc.datos().isSet("piedad-botin." + Autotest.sintetico(1)));
        return h.lineas();
    }

    /** Donde vive cada uno: la tabla de serie, el azar de antes donde no hay dueno y la lectura de la config. */
    static void probarPorBioma(Autotest.Hoja h) {
        Map<String, Habitat> serie = POR_BIOMA_DE_SERIE;
        List<String> cinco = new ArrayList<>(TIPOS);
        IntUnaryOperator primero = n -> 0, ultimo = n -> n - 1;
        h.igual("bioma: las llanuras de hongos son del Custodio", "custodio-de-las-ruinas",
                elegir("bracken:panacea/polypore_plains", serie, cinco, ultimo));
        h.igual("bioma: sin el prefijo bracken: tambien", "custodio-de-las-ruinas",
                elegir("panacea/polypore_plains", serie, cinco, ultimo));
        h.igual("bioma: sin mirar mayusculas", "matriarca-tejedora", elegir("Bracken:Panacea/Honeybee_Biome", serie, cinco, ultimo));
        h.igual("bioma: la cienaga es del Sanador", "sanador-del-fango", elegir("bracken:panacea/wildflower_bog", serie, cinco, primero));
        h.igual("bioma: el conclave es del Centinela", "centinela-de-toba", elegir("bracken:panacea/conure_conclave", serie, cinco, primero));
        h.igual("bioma: los manantiales son del Heraldo", "heraldo-carmes", elegir("bracken:panacea/quicksand_springs", serie, cinco, primero));
        h.igual("bioma sin dueno: el que diga el azar de tipos", cinco.get(cinco.size() - 1),
                elegir("minecraft:plains", serie, cinco, ultimo));
        h.igual("bioma sin dueno: el Reclamo no llama a nadie", null, delBioma("minecraft:plains", serie, cinco));
        h.igual("tabla vacia: al azar, como antes de la 1.10", cinco.get(0),
                elegir("bracken:panacea/polypore_plains", Map.of(), cinco, primero));
        h.igual("tabla vacia: el Reclamo no llama a nadie", null, delBioma("bracken:panacea/polypore_plains", Map.of(), cinco));
        h.igual("tipos vacio: el azar va por los de la tabla", "heraldo-carmes", elegir("minecraft:plains", serie, List.of(), ultimo));
        h.igual("tipos vacio: el bioma sigue mandando", "centinela-de-toba",
                elegir("bracken:panacea/creeper_dominion", serie, List.of(), primero));
        h.igual("sin tipos ni tabla: no viene nadie", null, elegir("bracken:panacea/polypore_plains", Map.of(), List.of(), primero));
        h.igual("un tipo quitado de tipos no viene en su bioma", "custodio-de-las-ruinas",
                elegir("bracken:panacea/crimson_organism", serie, List.of("custodio-de-las-ruinas"), ultimo));
        h.igual("ni lo llama el Reclamo", null, delBioma("bracken:panacea/crimson_organism", serie, List.of("custodio-de-las-ruinas")));
        h.igual("el id sale tal cual esta en tipos", "Heraldo-Carmes", delBioma("panacea/crimson_organism", serie, List.of("Heraldo-Carmes")));

        // Los trece biomas de Panacea (datapack de Bracken), cada uno con un dueno y solo uno.
        List<String> trece = List.of("bamboo_valley", "condemned_taiga", "conure_conclave", "creeper_dominion",
                "crimson_organism", "honeybee_biome", "horsetail_tropics", "hungering_jungle", "polypore_plains",
                "quicksand_springs", "ravenous_greenwood", "sweltering_swamp", "wildflower_bog");
        List<String> repartidos = new ArrayList<>();
        for (Habitat hab : serie.values()) repartidos.addAll(hab.biomas());
        h.igual("serie: trece biomas repartidos", 13, repartidos.size());
        h.igual("serie: ninguno con dos duenos", 13, new HashSet<>(repartidos).size());
        for (String b : trece) h.ok("serie: panacea/" + b + " tiene dueno", dueno("bracken:panacea/" + b, serie) != null);
        h.igual("serie: los cinco tienen sitio", new HashSet<>(TIPOS), new HashSet<>(serie.keySet()));

        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(String.join("\n",
                    "por-bioma:",
                    "  Custodio-De-Las-Ruinas: {donde: \"las ruinas\", biomas: [\"Bracken:Panacea/Polypore_Plains\"]}",
                    "  sanador-del-fango: {donde: \"el fango\", biomas: \"panacea/sweltering_swamp\"}",
                    "  roto: 5"));
        } catch (InvalidConfigurationException e) {
            h.ok("yaml de prueba de por-bioma: " + e.getMessage(), false);
        }
        Map<String, Habitat> leido = leerPorBioma(y.getConfigurationSection("por-bioma"));
        h.igual("leer: tipos en minusculas y sin lo que no es una entrada", List.of("custodio-de-las-ruinas", "sanador-del-fango"),
                new ArrayList<>(leido.keySet()));
        h.igual("leer: el bioma como clave", List.of("panacea/polypore_plains"),
                leido.containsKey("custodio-de-las-ruinas") ? leido.get("custodio-de-las-ruinas").biomas() : null);
        h.igual("leer: un bioma suelto tambien vale", List.of("panacea/sweltering_swamp"),
                leido.containsKey("sanador-del-fango") ? leido.get("sanador-del-fango").biomas() : null);
        h.igual("donde vive el Sanador", "el fango", donde(leido, "sanador-del-fango"));
        h.igual("donde: uno que no esta en la tabla", null, donde(leido, "heraldo-carmes"));
        h.igual("donde vive el Heraldo (serie)", "el organismo carmesí y los manantiales de arena", donde(serie, "heraldo-carmes"));
        h.ok("sin config: la de serie", porBioma(null) == POR_BIOMA_DE_SERIE);
        YamlConfiguration vacia = new YamlConfiguration();
        h.ok("config sin la seccion: la de serie", porBioma(vacia) == POR_BIOMA_DE_SERIE);
        vacia.createSection("hardcore.minijefes.por-bioma");
        h.ok("por-bioma: {} a proposito apaga los biomas", porBioma(vacia).isEmpty());
        h.igual("con articulo: la Matriarca", "la Matriarca Tejedora", elMinijefe("matriarca-tejedora"));
        h.igual("con articulo y mayuscula: el Heraldo", "El Heraldo Carmesí", mayuscula(elMinijefe("heraldo-carmes")));

        // Revision 1.10: a que carteles pone Calamity su nombre (CartelesMinijefe): tipos y la tabla de biomas.
        h.ok("cartel: uno de tipos, sin mirar mayusculas", tipoConocido(List.of("Custodio-De-Las-Ruinas"), Map.of(),
                "custodio-de-las-ruinas"));
        h.ok("cartel: con tipos vacio, uno de la tabla de biomas", tipoConocido(List.of(), serie, "heraldo-carmes"));
        h.ok("cartel: uno de la tabla aunque tipos traiga otros", tipoConocido(List.of("otro"), serie, "sanador-del-fango"));
        h.ok("cartel: un esbirro cualquiera no", !tipoConocido(cinco, serie, "zombi-podrido"));
        h.ok("cartel: sin tipo no", !tipoConocido(cinco, serie, null) && !tipoConocido(cinco, serie, " "));
    }

    /** El botin extra: la tabla de serie, quien tira segun "para", la piedad, los desconectados y la lectura. */
    static void probarBotin(Autotest.Hoja h) {
        List<String> avisos = new ArrayList<>();
        List<Botin> custodio = leerBotin(null, "custodio-de-las-ruinas", avisos);
        h.igual("botin de serie del Custodio: libro, mascota y gema", List.of("libro", "mascota", "gema"), ids(custodio));
        h.igual("botin de serie sin avisos", List.of(), avisos);
        boolean cincoConMascota = true;
        for (String t : TIPOS) {
            Botin m = null;
            for (Botin b : leerBotin(null, t, avisos)) if (b.id().equals("mascota")) m = b;
            cincoConMascota &= m != null && m.comando().contains("%jugador%") && m.piedad() == 30 && m.anuncio();
        }
        h.ok("botin de serie: los cinco con su huevo (piedad 30, anuncio)", cincoConMascota);
        if (custodio.size() < 3) return;
        Botin libro = custodio.get(0), mascota = custodio.get(1), gema = custodio.get(2);
        h.ok("la gema aun no se entrega (sin objeto ni comando)", !gema.entregable());
        h.ok("el libro y la mascota van por consola", libro.porConsola() && mascota.porConsola());

        UUID dosa = Autotest.sintetico(1), otro = Autotest.sintetico(2), tercero = Autotest.sintetico(3);
        LinkedHashMap<UUID, Double> fr = new LinkedHashMap<>();
        fr.put(dosa, 0.6);
        fr.put(otro, 0.3);
        fr.put(tercero, 0.05);
        Predicate<UUID> todos = u -> true;
        int[] tiradas = {0};
        DoubleSupplier nunca = () -> {
            tiradas[0]++;
            return 0.999_999;
        };
        DoubleSupplier siempre = () -> 0.0;

        List<Caida> c = tirarBotin(List.of(gema), dosa, fr, 0.10, Map.of(), todos, nunca);
        h.ok("sin objeto ni comando: ni tira ni cuenta piedad", c.isEmpty() && tiradas[0] == 0);

        c = tirarBotin(List.of(mascota), dosa, fr, 0.10, Map.of(), todos, nunca);
        h.igual("mejor: solo tira Dosa (60 %)", 1, tiradas[0]);
        Caida cd = caidaDe(c, dosa), co = caidaDe(c, otro);
        h.ok("mejor sin suerte: no le cae a nadie", cd != null && co != null && !cd.cae() && !co.cae());
        h.ok("mejor: Tercero (5 %) ni tira ni cuenta piedad", caidaDe(c, tercero) == null);
        h.igual("piedad de Dosa 0 -> 1", 1, cd == null ? -1 : cd.piedadDespues());
        h.igual("piedad de Otro 0 -> 1 (participa aunque no tire)", 1, co == null ? -1 : co.piedadDespues());

        c = tirarBotin(List.of(mascota), dosa, fr, 0.10, Map.of(clavePiedad(dosa, "mascota"), 12), todos, siempre);
        cd = caidaDe(c, dosa);
        h.ok("mejor con suerte: a Dosa por tirada", cd != null && cd.cae() && "tirada".equals(cd.porQue()));
        h.igual("y su piedad vuelve a 0", 0, cd == null ? -1 : cd.piedadDespues());
        h.ok("a Otro no le cae: no tira", caidaDe(c, otro) != null && !caidaDe(c, otro).cae());

        c = tirarBotin(List.of(mascota), dosa, fr, 0.10, Map.of(clavePiedad(otro, "mascota"), 29), todos, nunca);
        co = caidaDe(c, otro);
        h.ok("piedad 29 + esta = 30: a Otro le cae aunque no sea el mejor", co != null && co.cae() && "piedad".equals(co.porQue()));
        h.igual("y su piedad vuelve a 0", 0, co == null ? -1 : co.piedadDespues());
        co = caidaDe(tirarBotin(List.of(mascota), dosa, fr, 0.10, Map.of(clavePiedad(otro, "mascota"), 28), todos, nunca), otro);
        h.ok("con 28 todavia no (y sube a 29)", co != null && !co.cae() && co.piedadDespues() == 29);

        Botin alAsesino = new Botin("trofeo", 1.0, "asesino", 0, "cristal", "", "un Cristal de Regreso", false);
        c = tirarBotin(List.of(alAsesino), tercero, fr, 0.10, Map.of(), todos, siempre);
        h.ok("asesino: tira aunque no llegue al 10 %", c.size() == 1 && c.get(0).jugador().equals(tercero) && c.get(0).cae());

        Botin cadaUno = new Botin("tintura", 0.5, "participantes", 0, "tintura", "", "una Tintura de Ceniza", false);
        tiradas[0] = 0;
        c = tirarBotin(List.of(cadaUno), dosa, fr, 0.10, Map.of(), todos, nunca);
        h.igual("participantes: tiran Dosa y Otro, cada uno la suya", 2, tiradas[0]);
        h.igual("participantes: Tercero fuera", List.of(dosa, otro), jugadoresDe(c));
        double[] serieAzar = {0.0, 0.99};
        int[] i = {0};
        c = tirarBotin(List.of(cadaUno), dosa, fr, 0.10, Map.of(), todos, () -> serieAzar[i[0]++ % 2]);
        h.ok("participantes: a Dosa le cae y a Otro no", caidaDe(c, dosa) != null && caidaDe(c, dosa).cae()
                && caidaDe(c, otro) != null && !caidaDe(c, otro).cae());

        c = tirarBotin(List.of(mascota), dosa, fr, 0.10, Map.of(clavePiedad(dosa, "mascota"), 7), u -> !u.equals(dosa), siempre);
        h.ok("comando y Dosa desconectado: ni tira ni pierde su piedad", caidaDe(c, dosa) == null);
        h.ok("y su tirada no pasa a otro", caidaDe(c, otro) != null && !caidaDe(c, otro).cae());
        Botin objeto = new Botin("tintura", 1.0, "mejor", 0, "tintura", "", "una Tintura de Ceniza", false);
        Caida pendiente = caidaDe(tirarBotin(List.of(objeto), dosa, fr, 0.10, Map.of(), u -> false, siempre), dosa);
        h.ok("un objeto de Entregas si le cae desconectado (espera en pendientes)", pendiente != null && pendiente.cae());

        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(String.join("\n",
                    "todos:",
                    "  - {id: libro, prob: 0.08, para: mejor, objeto: libro, nombre: \"un libro LEGENDARY\"}",
                    "  - {id: Raro.Id, prob: 1, objeto: cristal}",
                    "custodio-de-las-ruinas:",
                    "  - {id: mascota, prob: 3, piedad: 30, para: MEJOR, comando: \"pets egg unique iron_golem %jugador%\", nombre: \"el huevo\", anuncio: true}",
                    "  - {id: libro, prob: 1, objeto: libro}",
                    "  - {id: sin-jugador, prob: 1, comando: \"say hola\"}",
                    "  - {id: otro, prob: 0.5, para: todos, objeto: cristal}",
                    "  - {id: gema, prob: 0.25, objeto: \"\", nombre: \"la Gema del Custodio\"}"));
        } catch (InvalidConfigurationException e) {
            h.ok("yaml de prueba del botin: " + e.getMessage(), false);
        }
        avisos.clear();
        List<Botin> leidos = leerBotin(y, "custodio-de-las-ruinas", avisos);
        h.igual("leer: lo comun y lo suyo, sin los rotos", List.of("libro", "mascota", "gema"), ids(leidos));
        if (leidos.size() >= 2) {
            Botin m = leidos.get(1);
            h.cerca("leer: una probabilidad de mas de 1 se queda en 1", 1.0, m.prob(), 1e-9);
            h.ok("leer: para sin mayusculas, piedad y anuncio", "mejor".equals(m.para()) && m.piedad() == 30 && m.anuncio());
        }
        h.igual("leer: cuatro avisos (id con punto, id repetido, comando sin %jugador%, para que no existe)", 4, avisos.size());
        h.igual("leer: un tipo sin lista solo lleva lo comun", List.of("libro"), ids(leerBotin(y, "heraldo-carmes", new ArrayList<>())));
    }

    private static List<String> ids(List<Botin> l) {
        List<String> out = new ArrayList<>();
        for (Botin b : l) out.add(b.id());
        return out;
    }

    private static Caida caidaDe(List<Caida> l, UUID u) {
        for (Caida c : l) if (c.jugador().equals(u)) return c;
        return null;
    }

    private static List<UUID> jugadoresDe(List<Caida> l) {
        List<UUID> out = new ArrayList<>();
        for (Caida c : l) out.add(c.jugador());
        return out;
    }

    /**
     * 1.8.4: el nombre que se ve encima del minijefe y en "Ha venido por ti" (Paleta.minijefe) y
     * el repintado del cartel de EDM (CartelesMinijefe.repintado). Sin negrita en ningun trozo.
     */
    private static void probarNombreVisible(Autotest.Hoja h) {
        Component solo = Paleta.minijefe("Custodio de las Ruinas", 0);
        h.igual("nombre de minijefe: calaveras a los lados", "☠ Custodio de las Ruinas ☠", Hardcore.plano(solo));
        h.ok("nombre de minijefe: sin negrita en ningun trozo", sinNegrita(solo));
        h.igual("nombre de minijefe: negrita apagada a proposito", TextDecoration.State.FALSE,
                solo.decoration(TextDecoration.BOLD));
        List<Map.Entry<String, TextColor>> letras = new ArrayList<>();
        letras(solo, null, letras);
        h.igual("nombre de minijefe: calavera en hueso", Paleta.HUESO, letras.get(0).getValue());
        h.igual("nombre de minijefe: empieza en el rojo de los avisos", TextColor.color(Paleta.MINIJEFE_DESDE),
                letras.get(1).getValue());
        h.igual("nombre de minijefe: acaba en el rojo de Ambush", TextColor.color(Paleta.MINIJEFE_HASTA),
                letras.get(letras.size() - 2).getValue());
        boolean claros = true;
        for (Map.Entry<String, TextColor> l : letras) {
            TextColor c = l.getValue();
            if (c == null || Math.max(c.red(), Math.max(c.green(), c.blue())) < 0xC8) claros = false;
        }
        h.ok("nombre de minijefe: ningun rojo oscuro (#8B1A1A no pasaria)", claros);

        Component aviso = Paleta.minijefe("Custodio de las Ruinas", 45);
        h.igual("aviso: el nivel detras", "☠ Custodio de las Ruinas ☠ · Nv. 45", Hardcore.plano(aviso));
        h.ok("aviso: sin negrita", sinNegrita(aviso) && aviso.decoration(TextDecoration.BOLD) == TextDecoration.State.FALSE);
        h.igual("sin nombre de ficha: Minijefe", "☠ Minijefe ☠", Hardcore.plano(Paleta.minijefe(null, 0)));

        // El cartel tal como lo pinta EDM (MinionManager.updateHolo): la raiz es el nombre de la ficha,
        // en negrita, y de ella cuelgan el salto, el nivel y la vida.
        Component edm = Component.text("Heraldo Carmesí", TextColor.color(0xC23B3B)).decoration(TextDecoration.BOLD, true)
                .append(Component.newline())
                .append(Component.text("Nv. ", TextColor.color(0x9A9A9A)))
                .append(Component.text(45, NamedTextColor.YELLOW))
                .append(Component.text("  ❤ ", NamedTextColor.RED))
                .append(Component.text(300, TextColor.color(0xE8E8E8)));
        Component cartel = CartelesMinijefe.repintado(edm);
        h.ok("cartel de EDM: se repinta", cartel != null);
        if (cartel == null) return;
        h.igual("cartel: nombre arriba, nivel y vida debajo", "☠ Heraldo Carmesí ☠\nNv. 45  ❤ 300", Hardcore.plano(cartel));
        h.igual("cartel: la primera linea es Paleta.minijefe", Paleta.minijefe("Heraldo Carmesí", 0), cartel.children().get(0));
        h.ok("cartel: sin negrita, tampoco en la linea del nivel", sinNegrita(cartel)
                && cartel.decoration(TextDecoration.BOLD) == TextDecoration.State.FALSE);
        h.ok("cartel ya repintado: no se vuelve a tocar", CartelesMinijefe.repintado(cartel) == null);
        h.ok("cartel sin la forma de EDM: se deja como esta", CartelesMinijefe.repintado(Component.text("Heraldo Carmesí")) == null);
    }

    /** Que ningun trozo del texto pida negrita (lo que no la fija la hereda, y nadie la pone). */
    private static boolean sinNegrita(Component c) {
        if (c.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) return false;
        for (Component hijo : c.children()) if (!sinNegrita(hijo)) return false;
        return true;
    }

    /** Los trozos con texto, en orden, con el color con el que se ven (el propio o el heredado). */
    private static void letras(Component c, TextColor heredado, List<Map.Entry<String, TextColor>> out) {
        TextColor color = c.color() != null ? c.color() : heredado;
        if (c instanceof TextComponent t && !t.content().isEmpty()) {
            out.add(new java.util.AbstractMap.SimpleEntry<>(t.content(), color)); // Map.entry no admite color null
        }
        for (Component hijo : c.children()) letras(hijo, color, out);
    }
}
