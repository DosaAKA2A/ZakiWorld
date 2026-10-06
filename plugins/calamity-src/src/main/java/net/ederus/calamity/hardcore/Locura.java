package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Locura: lo que oye quien tiene la cordura muy baja. Dosa (octubre de 2026): "mas efectos de susto con
 * la cordura baja, pasos mas fuertes, gritos, respiraciones, que se sienta como la verdadera locura".
 *
 * Por debajo de umbral-pasos (40 %):
 *   - pasos pesados que se acercan por detras, cada vez mas fuertes, mas cerca y mas seguidos. Vienen
 *     de una direccion fija del mundo: si se gira a mirar, paran en seco. Si llegan, acaban con una
 *     respiracion en la nuca (o, por debajo de umbral-gritos, a veces con un grito pegado al oido).
 *   - una respiracion pesada en la nuca, que le sigue la cabeza aunque se gire.
 * Por debajo de umbral-gritos (20 %), ademas:
 *   - gritos, unas veces lejos y otras pegados al oido;
 *   - el latido de Sentidos se acelera cuanto mas baja la cordura (desde ahi lo lleva Locura);
 *   - un lamento que resuena solo a lo lejos;
 *   - su nombre como un susurro en su chat, de tarde en tarde.
 * Por debajo de umbral-limite (5 %): todo mas seguido (las esperas y la pausa entre mas-seguido) y con
 * mas tope por minuto, hasta que llega el minijefe de la cordura a cero (Hardcore, sin cambios).
 *
 * Reglas:
 *   - todo es solo suyo (playSound y sendMessage al jugador), puesto alrededor de el (detras, a los
 *     lados, lejos), en HOSTILE o AMBIENT; ni efectos de pocion ni particulas;
 *   - nada en la zona spawn (Sentidos no llama aqui alli, y la cola no toca lo que caeria dentro), nada
 *     a quien esta exento de la Parca (el staff que vigila) ni a quien no cuenta (espectador, creativo);
 *   - ley 2: ninguna campana. "La campana siempre es real: solo la Parca y las muertes" (lo dice el menu
 *     de Calamity). El plan pedia block.bell.resonate; en su lugar suena el lamento;
 *   - ley 6: con la PARCA o un Vigilante encima, o en combate (Combate.enCombate), se calla todo menos
 *     el latido;
 *   - sonidos vanilla que tambien oye Bedrock (Geyser los traduce todos, el autotest mira que existan);
 *   - topes: una pausa minima entre dos sustos y un tope por minuto (el latido no cuenta).
 *
 * Las esperas las alarga el equipo con cordura-alucinaciones (la misma fraccion que quita de las
 * alucinaciones). Aqui se decide que suena y cuando; lo toca la cola de Alucinaciones.
 */
final class Locura {

    static final String PASOS = "pasos", RESPIRACION = "respiracion", GRITOS = "gritos", LATIDO = "latido",
            LAMENTO = "lamento", NOMBRE = "nombre";
    static final List<String> TIPOS = List.of(PASOS, RESPIRACION, GRITOS, LATIDO, LAMENTO, NOMBRE);
    /** Los que salen solos, con su espera y los topes. El latido no: suena seguido. */
    static final List<String> SUSTOS = List.of(PASOS, RESPIRACION, GRITOS, LAMENTO, NOMBRE);

    /** /calamity madness <player> <...>: la palabra que se escribe (en ingles) por cada tipo. */
    static final Map<String, String> ARGUMENTOS;
    static final String TODO = "all";

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("steps", PASOS);
        m.put("breath", RESPIRACION);
        m.put("scream", GRITOS);
        m.put("heartbeat", LATIDO);
        m.put("wail", LAMENTO);
        m.put("whisper", NOMBRE);
        ARGUMENTOS = java.util.Collections.unmodifiableMap(m);
    }

    /** El mismo latido que Sentidos: desde umbral-gritos lo lleva Locura, mas deprisa. */
    static final String S_LATIDO = "entity.warden.heartbeat";
    /** Gris oscuro: el susurro de su nombre casi no se lee. */
    static final TextColor VOZ = NamedTextColor.DARK_GRAY;
    /** Lo mas grave y lo mas agudo que toca Java (y con eso, lo mismo en Bedrock). */
    static final float TONO_MIN = 0.5f, TONO_MAX = 2.0f;

    static final List<Son> PASOS_DE_SERIE = List.of(
            new Son("entity.warden.step", 1.0f, 0.6f),
            new Son("block.deepslate.step", 0.8f, 0.5f));
    static final List<Son> RESPIRACION_DE_SERIE = List.of(
            new Son("entity.horse.breathe", 0.8f, 0.5f),
            new Son("entity.warden.sniff", 0.5f, 0.6f));
    static final List<Son> GRITOS_DE_SERIE = List.of(
            new Son("entity.fox.screech", 1.0f, 0.6f),
            new Son("entity.enderman.scream", 1.0f, 0.55f),
            new Son("entity.goat.screaming.ambient", 1.0f, 0.6f),
            new Son("entity.goat.screaming.hurt", 1.0f, 0.55f),
            new Son("entity.ghast.scream", 0.9f, 0.5f));
    static final List<Son> LAMENTO_DE_SERIE = List.of(
            new Son("ambient.cave", 3.5f, 0.7f),
            new Son("entity.ghast.ambient", 3.5f, 0.5f),
            new Son("entity.elder_guardian.ambient", 3.5f, 0.5f));
    static final List<String> FRASES_DE_SERIE = List.of(
            "…{nombre}…",
            "{nombre}, detrás de ti.",
            "No mires atrás, {nombre}.",
            "{nombre}… ¿por qué corres?",
            "Te estoy viendo, {nombre}.",
            "Quédate, {nombre}.");

    // ------------------------------------------------------------------ ajustes

    /** Un sonido de la config: su clave, su volumen y su tono (entre 0,5 y 2). */
    record Son(String clave, float volumen, float tono) {

        /** Null si no tiene clave o no suena (volumen 0 o menos). */
        static Son de(Map<?, ?> m) {
            Object c = m.get("sonido");
            String clave = c == null ? "" : String.valueOf(c).trim().toLowerCase(Locale.ROOT);
            // "minecraft:" delante o no, es la misma clave (y asi se compara con la de serie).
            if (clave.startsWith("minecraft:")) clave = clave.substring("minecraft:".length());
            float vol = (float) numero(m.get("volumen"), 1.0);
            if (clave.isEmpty() || vol <= 0) return null;
            return new Son(clave, vol, Locura.tono((float) numero(m.get("tono"), 1.0)));
        }
    }

    /** De 'desde' a 'hasta' (desde puede ser el mayor: los pasos van de 12 bloques a 1,5). */
    record Rango(double desde, double hasta) {

        double en(double k) {
            return desde + (hasta - desde) * Math.max(0, Math.min(1, k));
        }

        double azar(Random r) {
            return en(r.nextDouble());
        }

        double min() {
            return Math.min(desde, hasta);
        }

        double max() {
            return Math.max(desde, hasta);
        }
    }

    record Pasos(boolean activo, Rango cada, Rango cantidad, Rango distancia, Rango ticks, double mirarGrados,
                 double finalGrito, List<Son> sonidos) {
    }

    record Respiracion(boolean activo, Rango cada, Rango veces, List<Son> sonidos) {
    }

    record Gritos(boolean activo, Rango cada, double pegado, Rango lejos, double volumenLejos, List<Son> sonidos) {
    }

    record Latido(boolean activo, Rango ticks, Rango volumen, Rango tono) {
    }

    record Lamento(boolean activo, Rango cada, Rango lejos, List<Son> sonidos) {
    }

    record Nombre(boolean activo, Rango cada, List<String> frases) {
    }

    /** hardcore.locura, con los valores de serie para lo que falte (el config del servidor no trae la seccion). */
    record Ajustes(boolean activo, double umbralPasos, double umbralGritos, double umbralLimite, double masSeguido,
                   double pausaMinima, int topePorMinuto, int topePorMinutoLimite, Pasos pasos,
                   Respiracion respiracion, Gritos gritos, Latido latido, Lamento lamento, Nombre nombre) {

        static Ajustes de(ConfigurationSection c) {
            if (c == null) c = new YamlConfiguration();
            ConfigurationSection pa = seccion(c, "pasos"), re = seccion(c, "respiracion"), gr = seccion(c, "gritos"),
                    la = seccion(c, "latido"), lm = seccion(c, "lamento"), no = seccion(c, "nombre");
            double up = Math.max(0, numero(c.get("umbral-pasos"), 40));
            double ug = Math.min(up, Math.max(0, numero(c.get("umbral-gritos"), 20)));
            double ul = Math.min(ug, Math.max(0, numero(c.get("umbral-limite"), 5)));
            List<String> frases = new ArrayList<>();
            for (String f : no.getStringList("frases")) if (f != null && !f.isBlank()) frases.add(f);
            return new Ajustes(si(c.get("activo"), true), up, ug, ul,
                    Math.max(1, numero(c.get("mas-seguido"), 2.5)),
                    Math.max(0, numero(c.get("pausa-minima"), 12)),
                    (int) Math.max(1, Math.round(numero(c.get("tope-por-minuto"), 2))),
                    (int) Math.max(1, Math.round(numero(c.get("tope-por-minuto-limite"), 5))),
                    new Pasos(si(pa.get("activo"), true), rango(pa.get("cada"), 80, 160), rango(pa.get("cantidad"), 6, 9),
                            rango(pa.get("distancia"), 12, 1.5), rango(pa.get("ticks"), 13, 7),
                            Math.max(5, Math.min(175, numero(pa.get("mirar-grados"), 60))),
                            fraccion(pa.get("final-grito"), 0.4), sones(pa, PASOS_DE_SERIE)),
                    new Respiracion(si(re.get("activo"), true), rango(re.get("cada"), 70, 150), rango(re.get("veces"), 2, 3),
                            sones(re, RESPIRACION_DE_SERIE)),
                    new Gritos(si(gr.get("activo"), true), rango(gr.get("cada"), 70, 160), fraccion(gr.get("pegado"), 0.35),
                            rango(gr.get("lejos"), 18, 30), Math.max(0.1, numero(gr.get("volumen-lejos"), 3.0)),
                            sones(gr, GRITOS_DE_SERIE)),
                    new Latido(si(la.get("activo"), true), rango(la.get("ticks"), 36, 12), rango(la.get("volumen"), 0.75, 1.0),
                            rango(la.get("tono"), 1.0, 1.2)),
                    new Lamento(si(lm.get("activo"), true), rango(lm.get("cada"), 150, 300), rango(lm.get("lejos"), 28, 40),
                            sones(lm, LAMENTO_DE_SERIE)),
                    new Nombre(si(no.get("activo"), true), rango(no.get("cada"), 240, 480),
                            frases.isEmpty() ? FRASES_DE_SERIE : List.copyOf(frases)));
        }

        boolean encendido(String tipo) {
            return switch (tipo) {
                case PASOS -> pasos.activo();
                case RESPIRACION -> respiracion.activo();
                case GRITOS -> gritos.activo();
                case LATIDO -> latido.activo();
                case LAMENTO -> lamento.activo();
                case NOMBRE -> nombre.activo();
                default -> false;
            };
        }

        /** Cada cuantos segundos puede volver (null = el latido, que no espera). */
        Rango cada(String tipo) {
            return switch (tipo) {
                case PASOS -> pasos.cada();
                case RESPIRACION -> respiracion.cada();
                case GRITOS -> gritos.cada();
                case LAMENTO -> lamento.cada();
                case NOMBRE -> nombre.cada();
                default -> null;
            };
        }

        /** Por debajo de que cordura sale: pasos y respiracion desde umbral-pasos, lo demas desde umbral-gritos. */
        double umbral(String tipo) {
            return tipo.equals(PASOS) || tipo.equals(RESPIRACION) ? umbralPasos : umbralGritos;
        }
    }

    // ------------------------------------------------------------------ estado

    /** Lo de cada jugador por debajo de umbral-pasos. Solo memoria: al salir de su umbral se olvida. */
    static final class Estado {
        /** Tipo -> cuando le toca (ms). Sin entrada: aun sin armar (se arma con una primera espera). */
        final Map<String, Long> proximo = new HashMap<>();
        /** Cuando sonaron sus ultimos sustos (ms), para el tope por minuto. */
        final ArrayDeque<Long> recientes = new ArrayDeque<>();
        /** El ultimo susto (ms); -1 = ninguno. */
        long ultimo = -1;
        /** Ticks que faltan para el siguiente latido, contados desde el principio de este segundo. */
        int faseLatido;
    }

    private final Hardcore hc;
    private final Alucinaciones alucinaciones;
    private final Random azar = new Random();
    private final Map<UUID, Estado> estados = new HashMap<>();
    /** La config de la que salieron los ajustes de cache (por identidad). */
    private Object vista;
    private Ajustes cache;

    Locura(Hardcore hc, Alucinaciones alucinaciones) {
        this.hc = hc;
        this.alucinaciones = alucinaciones;
        Autotest.registrar("locura", this::autotest);
        Subcomandos.staff().registrar("madness",
                "madness <player> <steps|breath|scream|heartbeat|wail|whisper|all>: los sustos de la cordura baja, sin bajarla",
                Subcomandos.PERMISO, this::comando, this::tab);
    }

    /**
     * Los ajustes de la config viva. Se leian enteros en cada llamada (cada segundo por jugador, y otra vez
     * en cada tirada); ahora solo cuando cambia la config (un /calamity reload la cambia entera).
     */
    Ajustes ajustes() {
        Object v = hc.plugin().getConfig();
        if (cache == null || v != vista) {
            vista = v;
            cache = Ajustes.de(hc.cfg().getConfigurationSection("locura"));
        }
        return cache;
    }

    // ---------------------------------------------------------------- el segundo

    /**
     * Una vez por segundo por jugador que cuenta, fuera de la zona spawn (Sentidos.latido). Devuelve true
     * si este segundo el latido lo lleva Locura (por debajo de umbral-gritos): entonces Sentidos no toca
     * el suyo, que seria el mismo sonido dos veces.
     */
    boolean segundo(Player p, double cordura, boolean latidoEncendido) {
        Ajustes a = ajustes();
        UUID u = p.getUniqueId();
        if (sinLocura(a, cordura, p.isDead(), exento(p), hc.enSpawn(p))) {
            estados.remove(u);
            return false;
        }
        Estado e = estados.computeIfAbsent(u, k -> new Estado());
        boolean propio = latidoEncendido && a.latido().activo() && cordura < a.umbralGritos();
        if (propio) latir(p, e, a, cordura);
        else e.faseLatido = 0;

        // Ley 6: con la PARCA encima o en plena pelea, nada que confunda. El latido es suyo y sigue.
        if (callado(p)) return propio;
        long ahora = System.currentTimeMillis();
        DoubleSupplier equipo = () -> hc.delEquipo(p, Equipo.Efecto.CORDURA_ALUCINACIONES);
        String tipo = elegir(e, a, cordura, ahora, equipo, azar);
        if (tipo == null) return propio;
        if (lanzar(p, a, tipo, cordura, 0, null)) {
            apuntar(e, a, tipo, cordura, ahora, equipo, azar);
            anotar(p, tipo, cordura);
        } else {
            aplazar(e, tipo, ahora);
        }
        return propio;
    }

    /** Si los pasos de la tirada de Alucinaciones tienen que ser los pesados de aqui. 1.16.0: con la cordura sentida. */
    boolean pesados(Player p) {
        Ajustes a = ajustes();
        return a.activo() && a.pasos().activo() && hc.cordura().sentida(p) < a.umbralPasos() && !exento(p);
    }

    /**
     * La tirada de Alucinaciones saco pasos con la cordura por debajo de umbral-pasos: salen los pesados,
     * con los mismos topes que los de aqui. False si no caben (la tirada se pierde sin avisar).
     */
    boolean pasosDeTirada(Player p) {
        Ajustes a = ajustes();
        double cordura = hc.cordura().sentida(p);
        // 1.16.0: con un Farol de Tranquilidad encendido, ni los pasos de la tirada.
        if (hc.enSpawn(p) || callado(p) || hc.cordura().tranquilo(p)) return false;
        Estado e = estados.computeIfAbsent(p.getUniqueId(), k -> new Estado());
        long ahora = System.currentTimeMillis();
        boolean limite = limite(a, cordura);
        if (!cabe(e, ahora, pausaMs(a, limite), tope(a, limite))) return false;
        if (!lanzar(p, a, PASOS, cordura, 0, null)) return false;
        apuntar(e, a, PASOS, cordura, ahora, () -> hc.delEquipo(p, Equipo.Efecto.CORDURA_ALUCINACIONES), azar);
        anotar(p, PASOS, cordura);
        return true;
    }

    void olvidar(Player p) {
        estados.remove(p.getUniqueId());
    }

    void parar() {
        estados.clear();
    }

    private boolean exento(Player p) {
        Exentos ex = hc.exentos();
        return ex != null && hc.valor("exentos", () -> ex.parca(p.getUniqueId()), false);
    }

    /**
     * Ley 6: la PARCA le persigue, un Vigilante va a por el (viene escarbando o pelea a su lado) o esta
     * en combate (PvP, PARCA, Eco, minijefe). Los golpes fuertes del Vigilante van por DanoVerdadero y
     * no etiquetan combate, y su rugido quita cordura: sin esto, los sustos sonarian en plena pelea.
     */
    private boolean callado(Player p) {
        Parca parca = hc.parca();
        if (parca != null && hc.valor("parca", () -> parca.persigue(p), false)) return true;
        Vigilante vigilante = hc.vigilante();
        if (vigilante != null && hc.valor("vigilante", () -> vigilante.persigue(p), false)) return true;
        Combate c = hc.combate();
        return c != null && hc.valor("combate", () -> c.enCombate(p), false);
    }

    private void anotar(Player p, String tipo, double cordura) {
        Telemetria t = hc.telemetria();
        if (t == null) return;
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("tipo", tipo);
        campos.put("cordura", (int) Math.round(cordura));
        t.suceso("locura", p, campos);
    }

    // ------------------------------------------------------------------ el latido

    /** Mete en la cola los latidos que caen en este segundo, mas deprisa cuanto menos cordura. */
    private void latir(Player p, Estado e, Ajustes a, double cordura) {
        Latidos l = latidos(e.faseLatido, intervaloLatido(a, cordura));
        e.faseLatido = l.siguiente();
        double k = avance(cordura, a.umbralGritos());
        float vol = (float) a.latido().volumen().en(k);
        float tono = tono((float) a.latido().tono().en(k));
        List<Alucinaciones.Sonido> out = new ArrayList<>();
        for (int t : l.retrasos()) out.add(latido(p.getUniqueId(), t, vol, tono));
        alucinaciones.encolar(out);
    }

    static Alucinaciones.Sonido latido(UUID u, long retraso, float volumen, float tono) {
        return new Alucinaciones.Sonido(u, retraso, 0, 0, null, 0, S_LATIDO, SoundCategory.HOSTILE, volumen, tono, 0, 2);
    }

    /** Los latidos de un segundo (retrasos en ticks, de 0 a 19) y la fase con la que empieza el siguiente. */
    record Latidos(List<Integer> retrasos, int siguiente) {
    }

    static Latidos latidos(int fase, int intervalo) {
        List<Integer> out = new ArrayList<>();
        int paso = Math.max(2, intervalo);
        int t = Math.max(0, fase);
        while (t < 20) {
            out.add(t);
            t += paso;
        }
        return new Latidos(out, t - 20);
    }

    /** Ticks entre latido y latido: de latido.ticks.desde en umbral-gritos a latido.ticks.hasta en 0, en pares. */
    static int intervaloLatido(Ajustes a, double cordura) {
        double t = a.latido().ticks().en(avance(cordura, a.umbralGritos()));
        return Math.max(4, (int) Math.round(t / 2.0) * 2);
    }

    /** 0 en el umbral, 1 en cordura 0. */
    static double avance(double cordura, double umbral) {
        if (umbral <= 0) return 1;
        return Math.max(0, Math.min(1, 1 - cordura / umbral));
    }

    // ---------------------------------------------------------- cuando y cuanto

    /** Si no hay nada que hacer con el: apagado, por encima de umbral-pasos, muerto, exento o en la zona spawn. */
    static boolean sinLocura(Ajustes a, double cordura, boolean muerto, boolean exento, boolean enSpawn) {
        return !a.activo() || cordura >= a.umbralPasos() || muerto || exento || enSpawn;
    }

    static boolean permitido(Ajustes a, String tipo, double cordura) {
        return a.activo() && a.encendido(tipo) && cordura < a.umbral(tipo);
    }

    /** Los tipos que pueden sonar con esa cordura, en el orden de TIPOS. */
    static List<String> permitidos(Ajustes a, double cordura) {
        List<String> out = new ArrayList<>();
        for (String t : TIPOS) if (permitido(a, t, cordura)) out.add(t);
        return out;
    }

    /** Cerca de 0: todo mas seguido. */
    static boolean limite(Ajustes a, double cordura) {
        return cordura < a.umbralLimite();
    }

    static long pausaMs(Ajustes a, boolean limite) {
        return Math.round(a.pausaMinima() * 1000 / (limite ? a.masSeguido() : 1));
    }

    static int tope(Ajustes a, boolean limite) {
        return limite ? Math.max(a.topePorMinuto(), a.topePorMinutoLimite()) : a.topePorMinuto();
    }

    /**
     * La espera hasta que pueda volver (ms): al azar dentro de 'cada' (segundos), entre 'divisor' (mas-seguido
     * cerca de 0) y alargada por el equipo (cordura-alucinaciones 0,5 = el doble). Nunca menos de un segundo.
     */
    static long espera(Rango cada, double divisor, double equipo, Random r) {
        double s = cada.azar(r) / Math.max(1, divisor);
        s /= Math.max(0.1, 1 - Math.max(0, Math.min(0.9, equipo)));
        return Math.max(1000, Math.round(s * 1000));
    }

    /** La primera vez que entra en su umbral: entre un cuarto y tres cuartos de una espera, para que no tarde. */
    static long primeraEspera(Rango cada, double divisor, double equipo, Random r) {
        return Math.max(1000, Math.round(espera(cada, divisor, equipo, r) * (0.25 + r.nextDouble() * 0.5)));
    }

    /** Si cabe otro susto: la pausa minima desde el ultimo y menos de 'tope' en el ultimo minuto. */
    static boolean cabe(Estado e, long ahora, long pausaMs, int tope) {
        while (!e.recientes.isEmpty() && ahora - e.recientes.peekFirst() >= 60_000) e.recientes.pollFirst();
        return (e.ultimo < 0 || ahora - e.ultimo >= pausaMs) && e.recientes.size() < tope;
    }

    /**
     * Que susto toca ahora (null = ninguno), sin tocar el mundo. Arma la espera de lo que acaba de entrar en
     * su umbral, desarma lo que salio y, entre lo que ya toca, elige uno al azar si caben la pausa y el
     * tope. Nunca dos a la vez: cerca de 0 se juntan porque la pausa se acorta, no porque se solapen.
     */
    static String elegir(Estado e, Ajustes a, double cordura, long ahora, DoubleSupplier equipo, Random r) {
        boolean limite = limite(a, cordura);
        double divisor = limite ? a.masSeguido() : 1;
        List<String> listos = new ArrayList<>();
        for (String t : SUSTOS) {
            if (!permitido(a, t, cordura)) {
                e.proximo.remove(t);
                continue;
            }
            Long prox = e.proximo.get(t);
            if (prox == null) {
                e.proximo.put(t, ahora + primeraEspera(a.cada(t), divisor, equipo.getAsDouble(), r));
            } else if (ahora >= prox) {
                listos.add(t);
            }
        }
        if (listos.isEmpty() || !cabe(e, ahora, pausaMs(a, limite), tope(a, limite))) return null;
        return listos.get(r.nextInt(listos.size()));
    }

    /** Sono: cuenta para los topes y su espera vuelve a empezar. */
    static void apuntar(Estado e, Ajustes a, String tipo, double cordura, long ahora, DoubleSupplier equipo, Random r) {
        e.ultimo = ahora;
        e.recientes.addLast(ahora);
        Rango cada = a.cada(tipo);
        if (cada != null) e.proximo.put(tipo, ahora + espera(cada, limite(a, cordura) ? a.masSeguido() : 1, equipo.getAsDouble(), r));
    }

    /** No pudo sonar (unos pasos aun en marcha, ningun sitio fuera del spawn): se intenta en un rato. */
    static void aplazar(Estado e, String tipo, long ahora) {
        e.proximo.put(tipo, ahora + 5_000);
    }

    // ------------------------------------------------------------------- lanzar

    /**
     * Lo pone en la cola de Alucinaciones, con 'retraso' ticks. 'fin' fuerza como acaban los pasos (null = el
     * que toque con esa cordura). False si no pudo (pasos ya en marcha, ningun sitio fuera de la zona spawn).
     */
    private boolean lanzar(Player p, Ajustes a, String tipo, double cordura, long retraso, String fin) {
        UUID u = p.getUniqueId();
        Location pie = p.getLocation();
        switch (tipo) {
            case PASOS -> {
                if (alucinaciones.enRacha(u)) return false;
                Vector rumbo = null;
                for (int i = 0; i < 4 && rumbo == null; i++) {
                    Vector v = rumboDetras(pie.getDirection(), azar);
                    // Que empiecen fuera de la zona spawn: si no, se oirian a medias.
                    if (!hc.enSpawn(pie.clone().add(v.clone().multiply(a.pasos().distancia().desde())))) rumbo = v;
                }
                if (rumbo == null) return false;
                int n = (int) Math.round(a.pasos().cantidad().azar(azar));
                String acaba = fin != null ? fin : finalDePasos(a, cordura, azar);
                alucinaciones.encolar(planPasos(u, rumbo, alucinaciones.nuevaRacha(), a, n, acaba, retraso, azar));
                return true;
            }
            case RESPIRACION -> {
                int veces = (int) Math.round(a.respiracion().veces().azar(azar));
                alucinaciones.encolar(planRespiracion(u, a, veces, azar.nextDouble() * 50 - 25, retraso, azar));
                return true;
            }
            case GRITOS -> {
                boolean pegado = azar.nextDouble() < a.gritos().pegado();
                Alucinaciones.Sonido s = grito(pie, u, a, pegado, retraso, hc::enSpawn, azar);
                if (s == null) return false;
                alucinaciones.encolar(List.of(s));
                return true;
            }
            case LAMENTO -> {
                Alucinaciones.Sonido s = lamento(pie, u, a, retraso, hc::enSpawn, azar);
                if (s == null) return false;
                alucinaciones.encolar(List.of(s));
                return true;
            }
            case NOMBRE -> {
                List<String> frases = a.nombre().frases();
                p.sendMessage(susurro(frases.get(azar.nextInt(frases.size())), p.getName()));
                return true;
            }
            case LATIDO -> {
                // Solo el comando: una tanda de 8 s como a cordura 0.
                int intervalo = intervaloLatido(a, 0);
                float vol = (float) a.latido().volumen().hasta(), tono = tono((float) a.latido().tono().hasta());
                List<Alucinaciones.Sonido> out = new ArrayList<>();
                for (long t = 0; t < 160; t += intervalo) out.add(latido(u, retraso + t, vol, tono));
                alucinaciones.encolar(out);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Detras de su mirada, hasta 35 grados a un lado o al otro (horizontal y unitario). */
    static Vector rumboDetras(Vector mirada, Random r) {
        Vector m = mirada == null ? new Vector(0, 0, 1) : mirada.clone().setY(0);
        if (m.lengthSquared() < 1e-6) m = new Vector(0, 0, 1);
        return m.normalize().rotateAroundY(Math.toRadians(180 + r.nextDouble() * 70 - 35));
    }

    /** Como acaban los pasos si llegan: un grito pegado (solo bajo umbral-gritos), la respiracion o nada. */
    static String finalDePasos(Ajustes a, double cordura, Random r) {
        if (cordura < a.umbralGritos() && a.gritos().activo() && r.nextDouble() < a.pasos().finalGrito()) return GRITOS;
        return a.respiracion().activo() ? RESPIRACION : null;
    }

    /**
     * Una racha de 'n' pasos que vienen por 'rumbo' (fijo en el mundo) de distancia.desde a distancia.hasta,
     * cada vez mas fuertes (del 35 % al volumen de la config) y mas seguidos (de ticks.desde a ticks.hasta).
     * Todo con el mismo numero de racha y el corte de mirar-grados: si se gira hacia ellos, lo que queda se
     * calla. Si llegan, 'fin': la respiracion pegada a la nuca o un grito pegado al oido (o nada).
     */
    static List<Alucinaciones.Sonido> planPasos(UUID u, Vector rumbo, int racha, Ajustes a, int n, String fin,
                                                long retraso, Random r) {
        Pasos pa = a.pasos();
        double corte = Math.cos(Math.toRadians(pa.mirarGrados()));
        List<Alucinaciones.Sonido> out = new ArrayList<>();
        int cuantos = Math.max(1, n);
        long t = Math.max(0, retraso);
        for (int i = 0; i < cuantos; i++) {
            double k = cuantos == 1 ? 1 : i / (double) (cuantos - 1);
            double d = pa.distancia().en(k);
            float fuerza = (float) (0.35 + 0.65 * k);
            for (Son s : pa.sonidos()) {
                out.add(new Alucinaciones.Sonido(u, t, d, 0, rumbo, 0, s.clave(), SoundCategory.HOSTILE,
                        s.volumen() * fuerza, tono(s.tono() * (float) (0.96 + r.nextDouble() * 0.08)), racha, corte));
            }
            if (i < cuantos - 1) t += Math.max(2, Math.round(pa.ticks().en(k)));
        }
        t += 12;
        if (GRITOS.equals(fin) && !a.gritos().sonidos().isEmpty()) {
            Son s = a.gritos().sonidos().get(r.nextInt(a.gritos().sonidos().size()));
            out.add(new Alucinaciones.Sonido(u, t, 0.8, 0, rumbo, 1.5, s.clave(), SoundCategory.HOSTILE, s.volumen(),
                    s.tono(), racha, corte));
        } else if (RESPIRACION.equals(fin) && !a.respiracion().sonidos().isEmpty()) {
            List<Son> res = a.respiracion().sonidos();
            for (int i = 0; i < 2; i++) {
                Son s = res.get(i % res.size());
                out.add(new Alucinaciones.Sonido(u, t + i * 30L, 0.5, 0, rumbo, 1.5, s.clave(), SoundCategory.HOSTILE,
                        s.volumen(), s.tono(), racha, corte));
            }
        }
        return out;
    }

    /**
     * La respiracion en la nuca: 'veces' respiraciones con los sonidos por turno (respira, olfatea, respira),
     * medio bloque detras de la cabeza y 'lado' grados a un lado. Le sigue la cabeza: no se corta al mirar.
     */
    static List<Alucinaciones.Sonido> planRespiracion(UUID u, Ajustes a, int veces, double lado, long retraso, Random r) {
        List<Son> sons = a.respiracion().sonidos();
        List<Alucinaciones.Sonido> out = new ArrayList<>();
        if (sons.isEmpty()) return out;
        long t = Math.max(0, retraso);
        for (int i = 0; i < Math.max(1, veces); i++) {
            Son s = sons.get(i % sons.size());
            out.add(new Alucinaciones.Sonido(u, t, 0.45, 180 + lado, null, 1.5, s.clave(), SoundCategory.HOSTILE,
                    s.volumen(), tono(s.tono() * (float) (0.97 + r.nextDouble() * 0.06)), 0, 2));
            t += 28 + r.nextInt(8);
        }
        return out;
    }

    /**
     * Un grito: pegado al oido (a 0,9 bloques, de un lado o de detras, a la altura de la cabeza) o lejos (en
     * gritos.lejos, con el volumen por volumen-lejos para que llegue). Null si lejos no hay sitio fuera del spawn.
     */
    static Alucinaciones.Sonido grito(Location pie, UUID u, Ajustes a, boolean pegado, long retraso,
                                      Predicate<Location> enSpawn, Random r) {
        List<Son> sons = a.gritos().sonidos();
        if (sons.isEmpty()) return null;
        Son s = sons.get(r.nextInt(sons.size()));
        if (pegado) {
            Vector m = pie.getDirection().setY(0);
            if (m.lengthSquared() < 1e-6) m = new Vector(0, 0, 1);
            Vector rumbo = m.normalize().rotateAroundY(Math.toRadians(90 + r.nextDouble() * 180));
            return new Alucinaciones.Sonido(u, retraso, 0.9, 0, rumbo, 1.5, s.clave(), SoundCategory.HOSTILE,
                    s.volumen(), s.tono(), 0, 2);
        }
        Sitio sitio = sitioLejos(pie, a.gritos().lejos(), enSpawn, r);
        if (sitio == null) return null;
        return new Alucinaciones.Sonido(u, retraso, sitio.distancia(), 0, sitio.rumbo(), 1, s.clave(), SoundCategory.HOSTILE,
                (float) (s.volumen() * a.gritos().volumenLejos()), tono(s.tono() * (float) (0.95 + r.nextDouble() * 0.1)), 0, 2);
    }

    /** El lamento: uno de lamento.sonidos a lo lejos, por donde no haya zona spawn. Null si no hay sitio. */
    static Alucinaciones.Sonido lamento(Location pie, UUID u, Ajustes a, long retraso, Predicate<Location> enSpawn, Random r) {
        List<Son> sons = a.lamento().sonidos();
        if (sons.isEmpty()) return null;
        Son s = sons.get(r.nextInt(sons.size()));
        Sitio sitio = sitioLejos(pie, a.lamento().lejos(), enSpawn, r);
        if (sitio == null) return null;
        return new Alucinaciones.Sonido(u, retraso, sitio.distancia(), 0, sitio.rumbo(), 2, s.clave(), SoundCategory.AMBIENT,
                s.volumen(), tono(s.tono() * (float) (0.95 + r.nextDouble() * 0.1)), 0, 2);
    }

    /** Un rumbo horizontal y una distancia. */
    record Sitio(Vector rumbo, double distancia) {
    }

    /** Un sitio lejos, en cualquier direccion, que no caiga en la zona spawn (seis intentos); null si no hay. */
    static Sitio sitioLejos(Location pie, Rango distancia, Predicate<Location> enSpawn, Random r) {
        for (int i = 0; i < 6; i++) {
            Vector rumbo = new Vector(0, 0, 1).rotateAroundY(r.nextDouble() * Math.PI * 2);
            double d = distancia.azar(r);
            Location l = pie.clone().add(rumbo.clone().multiply(d));
            if (enSpawn == null || !enSpawn.test(l)) return new Sitio(rumbo, d);
        }
        return null;
    }

    /**
     * Su nombre como un susurro, solo en su chat: gris oscuro y en cursiva, casi no se lee. A diferencia del
     * susurro de los muertos (Alucinaciones.susurro), este si parece un susurro de verdad: es el susto. No
     * tiene la forma "nombre: texto" de un chat, asi que no se confunde con lo que escribe nadie.
     */
    static Component susurro(String frase, String nombre) {
        String f = frase == null || frase.isBlank() ? "…{nombre}…" : frase;
        return Component.text(f.replace("{nombre}", nombre == null ? "" : nombre), VOZ)
                .decoration(TextDecoration.ITALIC, true);
    }

    // ------------------------------------------------------------------ comando

    /**
     * /calamity madness <player> <steps|breath|scream|heartbeat|wail|whisper|all>: lo suena ya, sin bajarle
     * la cordura y sin esperas ni topes. Tiene que estar en Calamity y fuera de la zona spawn (ahi no suena
     * nada, tampoco a prueba). No mira las exenciones: es el staff quien lo pide.
     */
    private void comando(CommandSender quien, String[] args) {
        if (args.length < 3) {
            quien.sendMessage(ComandoCalamity.mensaje("Uso: /calamity madness <player> <steps|breath|scream|heartbeat|wail|whisper|all>"));
            return;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) {
            quien.sendMessage(Component.text("Ese jugador no está conectado.", Paleta.AVISO));
            return;
        }
        String que = args[2].toLowerCase(Locale.ROOT);
        String tipo = ARGUMENTOS.get(que);
        if (tipo == null && !que.equals(TODO)) {
            quien.sendMessage(Component.text("Tiene que ser steps, breath, scream, heartbeat, wail, whisper o all.", Paleta.AVISO));
            return;
        }
        if (!hc.esHardcore(p)) {
            quien.sendMessage(Component.text(p.getName() + " no está en Calamity: fuera no suena nada.", Paleta.AVISO));
            return;
        }
        if (hc.enSpawn(p)) {
            quien.sendMessage(Component.text(p.getName() + " está en la zona spawn: ahí no suena nada.", Paleta.AVISO));
            return;
        }
        if (hc.cordura().tranquilo(p)) {
            quien.sendMessage(Component.text(p.getName() + " lleva un Farol de Tranquilidad encendido: no le suena nada.",
                    Paleta.AVISO));
            return;
        }
        Ajustes a = ajustes();
        boolean ok = tipo == null ? probarTodo(p, a) : probar(p, a, tipo);
        if (!ok) {
            quien.sendMessage(Component.text("No pudo sonar: hay unos pasos aún en marcha o no hay sitio fuera de la zona spawn.",
                    Paleta.AVISO));
            return;
        }
        hc.plugin().bitacora().anotar("locura", "prueba", p.getName(), que, quien.getName());
        String apagado = tipo != null && !a.encendido(tipo) ? " (en la config está apagado: así sonaría)" : "";
        quien.sendMessage(ComandoCalamity.mensaje(Component.text("Locura · ")
                .append(Component.text(que, Paleta.CIFRA))
                .append(Component.text(" para "))
                .append(Component.text(p.getName(), Paleta.DETALLE))
                .append(Component.text(apagado + "."))));
    }

    /** Un tipo suelto. Los pasos acaban en la respiracion; scream da uno lejos y, a los 3 s, uno pegado. */
    private boolean probar(Player p, Ajustes a, String tipo) {
        if (tipo.equals(PASOS)) return lanzar(p, a, PASOS, 0, 0, RESPIRACION);
        if (!tipo.equals(GRITOS)) return lanzar(p, a, tipo, 0, 0, null);
        Location pie = p.getLocation();
        Alucinaciones.Sonido lejos = grito(pie, p.getUniqueId(), a, false, 0, hc::enSpawn, azar);
        Alucinaciones.Sonido cerca = grito(pie, p.getUniqueId(), a, true, 60, hc::enSpawn, azar);
        List<Alucinaciones.Sonido> out = new ArrayList<>();
        if (lejos != null) out.add(lejos);
        if (cerca != null) out.add(cerca);
        if (out.isEmpty()) return false;
        alucinaciones.encolar(out);
        return true;
    }

    /**
     * Todo a la vez, como cerca de 0: el latido desbocado, los pasos que acaban en un grito pegado, el lamento a
     * lo lejos, un grito lejano, la respiracion y, al final, su nombre.
     */
    private boolean probarTodo(Player p, Ajustes a) {
        boolean pasos = lanzar(p, a, PASOS, 0, 0, GRITOS);
        lanzar(p, a, LATIDO, 0, 0, null);
        lanzar(p, a, LAMENTO, 0, 30, null);
        Alucinaciones.Sonido lejos = grito(p.getLocation(), p.getUniqueId(), a, false, 140, hc::enSpawn, azar);
        if (lejos != null) alucinaciones.encolar(List.of(lejos));
        lanzar(p, a, RESPIRACION, 0, 170, null);
        UUID u = p.getUniqueId();
        hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(), () -> {
            Player q = Bukkit.getPlayer(u);
            if (q == null || !hc.esHardcore(q) || hc.enSpawn(q)) return;
            List<String> frases = a.nombre().frases();
            q.sendMessage(susurro(frases.get(azar.nextInt(frases.size())), q.getName()));
        }, 230L);
        return pasos;
    }

    private List<String> tab(String[] args) {
        if (args.length == 2) return Reliquias.conectados();
        if (args.length == 3) {
            List<String> out = new ArrayList<>(ARGUMENTOS.keySet());
            out.add(TODO);
            return out;
        }
        return List.of();
    }

    // --------------------------------------------------------------- utilidades

    static float tono(float t) {
        return Math.max(TONO_MIN, Math.min(TONO_MAX, t));
    }

    private static ConfigurationSection seccion(ConfigurationSection c, String k) {
        ConfigurationSection s = c.getConfigurationSection(k);
        return s == null ? new YamlConfiguration() : s;
    }

    private static double numero(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return def;
        try {
            return Double.parseDouble(String.valueOf(v).trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean si(Object v, boolean def) {
        if (v instanceof Boolean b) return b;
        if (v == null) return def;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.equals("true")) return true;
        if (s.equals("false")) return false;
        return def;
    }

    private static double fraccion(Object v, double def) {
        return Math.max(0, Math.min(1, numero(v, def)));
    }

    /** [desde, hasta] o un numero solo (desde = hasta); lo que no se entienda, el de serie. */
    private static Rango rango(Object v, double desde, double hasta) {
        if (v instanceof List<?> l && l.size() >= 2) {
            double a = numero(l.get(0), Double.NaN), b = numero(l.get(1), Double.NaN);
            if (!Double.isNaN(a) && !Double.isNaN(b)) return new Rango(Math.max(0, a), Math.max(0, b));
        }
        if (v instanceof Number n) return new Rango(Math.max(0, n.doubleValue()), Math.max(0, n.doubleValue()));
        return new Rango(desde, hasta);
    }

    /** La lista 'sonidos' de esa seccion; vacia o ilegible, la de serie. */
    private static List<Son> sones(ConfigurationSection s, List<Son> deSerie) {
        List<Son> out = new ArrayList<>();
        for (Map<?, ?> m : s.getMapList("sonidos")) {
            Son son = Son.de(m);
            if (son != null) out.add(son);
        }
        return out.isEmpty() ? deSerie : List.copyOf(out);
    }

    /** Si esa clave existe en el registro de sonidos del servidor ("minecraft:" delante o no). */
    static boolean existe(String clave) {
        if (clave == null || clave.isBlank()) return false;
        try {
            NamespacedKey k = NamespacedKey.fromString(clave.trim().toLowerCase(Locale.ROOT));
            return k != null && Registry.SOUND_EVENT.get(k) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ autotest

    private List<String> autotest() {
        return autotest(seccionDelJar(), Locura::existe);
    }

    /**
     * Las comprobaciones, sin servidor: 'deJar' es la seccion hardcore.locura del config.yml del jar y
     * 'existe' dice si una clave de sonido existe (en el juego, el registro de sonidos).
     */
    static List<String> autotest(ConfigurationSection deJar, Predicate<String> existe) {
        Autotest.Hoja h = new Autotest.Hoja();
        Ajustes a = Ajustes.de(new YamlConfiguration());

        // --- de serie, sin la seccion en el config del servidor.
        h.ok("sin seccion: encendida", a.activo());
        h.cerca("sin seccion: umbral de pasos 40", 40, a.umbralPasos(), 1e-9);
        h.cerca("sin seccion: umbral de gritos 20", 20, a.umbralGritos(), 1e-9);
        h.cerca("sin seccion: cerca de 0 es por debajo de 5", 5, a.umbralLimite(), 1e-9);
        h.ok("el config.yml del jar trae la seccion hardcore.locura", deJar != null);
        h.ok("y dice lo mismo que los valores de serie del codigo", deJar != null && Ajustes.de(deJar).equals(a));

        // --- umbrales.
        h.igual("cordura 100: nada", List.of(), permitidos(a, 100));
        h.igual("cordura 40: aun nada (es por debajo)", List.of(), permitidos(a, 40));
        h.igual("cordura 39: pasos y respiracion", List.of(PASOS, RESPIRACION), permitidos(a, 39));
        h.igual("cordura 20: aun sin gritos", List.of(PASOS, RESPIRACION), permitidos(a, 20));
        h.igual("cordura 19: todo", TIPOS, permitidos(a, 19));
        h.igual("cordura 0: todo", TIPOS, permitidos(a, 0));
        h.ok("cordura 4: mas seguido", limite(a, 4));
        h.ok("cordura 5: aun no", !limite(a, 5));
        YamlConfiguration c = new YamlConfiguration();
        c.set("gritos.activo", false);
        c.set("umbral-gritos", 60);
        Ajustes sinGritos = Ajustes.de(c);
        h.ok("gritos apagados no salen", !permitidos(sinGritos, 0).contains(GRITOS));
        h.cerca("umbral de gritos por encima del de pasos: se queda en el de pasos", 40, sinGritos.umbralGritos(), 1e-9);
        YamlConfiguration apagada = new YamlConfiguration();
        apagada.set("activo", false);
        h.igual("locura apagada: nada", List.of(), permitidos(Ajustes.de(apagada), 0));

        // --- a quien no le toca nada.
        h.ok("en la zona spawn, nada", sinLocura(a, 0, false, false, true));
        h.ok("exento de la Parca, nada", sinLocura(a, 0, false, true, false));
        h.ok("muerto, nada", sinLocura(a, 0, true, false, false));
        h.ok("con 40 o mas, nada", sinLocura(a, 40, false, false, false));
        h.ok("con 39, fuera del spawn y sin exencion, si", !sinLocura(a, 39, false, false, false));

        // --- frecuencias.
        Random r = new Random(7);
        boolean normal = true, cercaDe0 = true, conEquipo = true, primera = true;
        for (int i = 0; i < 300; i++) {
            long ms = espera(a.cada(GRITOS), 1, 0, r);
            normal &= ms >= 70_000 && ms <= 160_000;
            ms = espera(a.cada(GRITOS), a.masSeguido(), 0, r);
            cercaDe0 &= ms >= 28_000 && ms <= 64_000;
            ms = espera(a.cada(GRITOS), 1, 0.5, r);
            conEquipo &= ms >= 140_000 && ms <= 320_000;
            ms = primeraEspera(a.cada(PASOS), 1, 0, r);
            primera &= ms >= 20_000 && ms <= 120_000;
        }
        h.ok("gritos: cada 70-160 s (300 tiradas)", normal);
        h.ok("cerca de 0: entre 2,5 (28-64 s)", cercaDe0);
        h.ok("equipo con cordura-alucinaciones 0,5: el doble de espera", conEquipo);
        h.ok("la primera vez no tarda: entre un cuarto y tres cuartos", primera);
        h.ok("el latido no espera: suena seguido", a.cada(LATIDO) == null);

        // --- topes.
        Estado e = new Estado();
        long t0 = 1_000_000;
        h.ok("sin sustos aun: cabe", cabe(e, t0, pausaMs(a, false), tope(a, false)));
        apuntar(e, a, GRITOS, 10, t0, () -> 0, r);
        h.ok("a los 5 s del ultimo no cabe (pausa 12 s)", !cabe(e, t0 + 5_000, pausaMs(a, false), tope(a, false)));
        h.ok("a los 12 s si", cabe(e, t0 + 12_000, pausaMs(a, false), tope(a, false)));
        h.ok("cerca de 0 la pausa baja a 4,8 s", cabe(e, t0 + 4_800, pausaMs(a, true), tope(a, true))
                && !cabe(e, t0 + 4_700, pausaMs(a, true), tope(a, true)));
        apuntar(e, a, PASOS, 10, t0 + 15_000, () -> 0, r);
        h.ok("dos en un minuto: el tercero no cabe", !cabe(e, t0 + 30_000, pausaMs(a, false), tope(a, false)));
        h.ok("cerca de 0 caben cinco", cabe(e, t0 + 30_000, pausaMs(a, true), tope(a, true)));
        h.ok("pasado el minuto del primero, cabe otra vez", cabe(e, t0 + 61_000, pausaMs(a, false), tope(a, false)));

        // --- media hora simulada: los topes se cumplen y salen todos los sustos.
        h.ok("cordura 50: ni un susto en media hora", simular(a, 50, 1800, r).isEmpty());
        List<long[]> diez = simular(a, 10, 1800, r);
        h.ok("cordura 10: hay sustos (" + diez.size() + " en media hora)", diez.size() >= 15);
        h.ok("cordura 10: nunca mas de 2 en un minuto", maximoPorMinuto(diez) <= 2);
        h.ok("cordura 10: nunca dos a menos de 12 s", pausaMinima(diez) >= 12_000);
        Set<Long> tipos = new java.util.HashSet<>();
        for (long[] x : diez) tipos.add(x[1]);
        h.igual("cordura 10: salen los cinco sustos", SUSTOS.size(), tipos.size());
        List<long[]> dos = simular(a, 2, 1800, r);
        h.ok("cordura 2: mas seguido que con 10 (" + dos.size() + " contra " + diez.size() + ")", dos.size() > diez.size());
        h.ok("cordura 2: nunca mas de 5 en un minuto", maximoPorMinuto(dos) <= 5);
        h.ok("cordura 2: nunca dos a menos de 4,8 s", pausaMinima(dos) >= 4_800);
        List<long[]> treinta = simular(a, 30, 1800, r);
        boolean soloPasos = true;
        for (long[] x : treinta) soloPasos &= x[1] == SUSTOS.indexOf(PASOS) || x[1] == SUSTOS.indexOf(RESPIRACION);
        h.ok("cordura 30: solo pasos y respiracion", soloPasos && !treinta.isEmpty());

        // --- el latido.
        h.igual("latido en el umbral: cada 36 ticks", 36, intervaloLatido(a, 19.999));
        h.igual("latido en 0: cada 12 ticks", 12, intervaloLatido(a, 0));
        boolean baja = true;
        for (int k = 19; k > 0; k--) baja &= intervaloLatido(a, k - 1) <= intervaloLatido(a, k);
        h.ok("menos cordura, latido mas rapido", baja);
        h.igual("10 s a cordura 0: 17 latidos", 17, latidosEn(intervaloLatido(a, 0), 10));
        h.ok("en el umbral no va mas lento que el de Sentidos (1 cada 2 s)", latidosEn(intervaloLatido(a, 19.9), 10) >= 5);
        h.ok("latido de 0,75 a 1,0", Math.abs(a.latido().volumen().en(avance(20, 20)) - 0.75) < 1e-9
                && Math.abs(a.latido().volumen().en(avance(0, 20)) - 1.0) < 1e-9);

        // --- los pasos.
        UUID yo = Autotest.sintetico(1);
        Vector rumbo = new Vector(0, 0, -1);
        List<Alucinaciones.Sonido> plan = planPasos(yo, rumbo, 5, a, 7, RESPIRACION, 0, r);
        List<Alucinaciones.Sonido> pasos = new ArrayList<>();
        for (Alucinaciones.Sonido s : plan) if (s.altura() == 0) pasos.add(s);
        h.igual("7 pasos con 2 sonidos cada uno", 14, pasos.size());
        boolean acercan = true, suben = true, apuran = true, iguales = true;
        for (int i = 2; i < pasos.size(); i += 2) {
            acercan &= pasos.get(i).distancia() < pasos.get(i - 2).distancia();
            suben &= pasos.get(i).volumen() > pasos.get(i - 2).volumen();
            if (i >= 4) apuran &= pasos.get(i).tick() - pasos.get(i - 2).tick() <= pasos.get(i - 2).tick() - pasos.get(i - 4).tick();
        }
        for (Alucinaciones.Sonido s : plan) iguales &= s.racha() == 5 && s.rumbo() == rumbo && s.cortable();
        h.ok("se acercan", acercan);
        h.ok("cada vez mas fuertes", suben);
        h.ok("cada vez mas seguidos", apuran);
        h.cerca("el primero a 12 bloques", 12, pasos.get(0).distancia(), 1e-9);
        h.cerca("el ultimo a 1,5", 1.5, pasos.get(pasos.size() - 1).distancia(), 1e-9);
        h.ok("todo con la misma racha, el mismo rumbo y cortable", iguales);
        h.igual("si llegan, dos respiraciones en la nuca", 2, plan.size() - pasos.size());
        h.ok("por encima de umbral-gritos nunca acaban en grito", !GRITOS.equals(finalDePasos(a, 30, new Random(1))));
        Vector detras = rumboDetras(new Vector(0, 0, 1), r);
        h.ok("vienen de detras (a 35 grados como mucho)", detras.angle(new Vector(0, 0, -1)) <= Math.toRadians(35) + 1e-6);

        // --- paran en seco al mirar (Alucinaciones.cortadas, lo que hace la cola cada 2 ticks).
        Function<UUID, Vector> deEspaldas = u -> new Vector(0, 0, 1), girado = u -> new Vector(0, 0, -1),
                deLado = u -> new Vector(1, 0, 0), casi = u -> new Vector(0, 0, -1).rotateAroundY(Math.toRadians(50)),
                mirandoAlSuelo = u -> new Vector(0, -0.95, -0.3);
        h.ok("de espaldas siguen", Alucinaciones.cortadas(plan, deEspaldas).isEmpty());
        h.ok("de lado (90 grados) siguen", Alucinaciones.cortadas(plan, deLado).isEmpty());
        h.ok("girado hacia ellos paran", Alucinaciones.cortadas(plan, girado).contains(5));
        h.ok("a 50 grados de ellos tambien (mirar-grados 60)", Alucinaciones.cortadas(plan, casi).contains(5));
        h.ok("girado aunque mire al suelo, paran", Alucinaciones.cortadas(plan, mirandoAlSuelo).contains(5));
        List<Alucinaciones.Sonido> cola = new ArrayList<>(plan.subList(6, plan.size()));   // ya sonaron 3 pasos
        Set<Integer> cortadas = Alucinaciones.cortadas(cola, girado);
        cola.removeIf(s -> cortadas.contains(s.racha()));
        h.ok("tras 3 pasos se gira: no suena nada mas, ni la respiracion", cola.isEmpty());
        List<Alucinaciones.Sonido> res = planRespiracion(yo, a, 3, 10, 0, r);
        h.ok("la respiracion en la nuca no se corta al mirar (le sigue la cabeza)",
                res.size() == 3 && Alucinaciones.cortadas(res, girado).isEmpty() && res.get(0).rumbo() == null);
        h.ok("respira, olfatea, respira", res.get(0).clave().equals(a.respiracion().sonidos().get(0).clave())
                && res.get(1).clave().equals(a.respiracion().sonidos().get(1).clave()));

        // --- nada en la zona spawn (la del autotest: x < 0).
        Predicate<Location> spawn = l -> l.getX() < 0;
        Location pie = new Location(null, 1, 64, 0, -90f, 0f);   // en x = 1 mirando hacia +X: detras esta el spawn
        int suenan = 0;
        for (Alucinaciones.Sonido s : planPasos(yo, new Vector(-1, 0, 0), 6, a, 7, RESPIRACION, 0, r)) {
            if (s.altura() == 0 && Alucinaciones.aSonar(pie, s, spawn) != null) suenan++;
        }
        h.igual("pasos que vendrian de la zona spawn: no suena ninguno", 0, suenan);
        boolean fuera = true;
        int sinSitio = 0;
        for (int i = 0; i < 200; i++) {
            Alucinaciones.Sonido g = grito(pie, yo, a, false, 0, spawn, r);
            Alucinaciones.Sonido l = lamento(pie, yo, a, 0, spawn, r);
            if (g == null) sinSitio++;
            if (l == null) sinSitio++;
            fuera &= (g == null || Alucinaciones.aSonar(pie, g, spawn) != null) && (l == null || Alucinaciones.aSonar(pie, l, spawn) != null);
        }
        h.ok("gritos y lamentos lejanos, siempre fuera de la zona spawn (200 tiradas)", fuera);
        h.ok("pegado al borde del spawn casi siempre encuentran sitio (" + sinSitio + " de 400 sin)", sinSitio < 40);
        h.ok("todo alrededor es spawn: ni grito lejano ni lamento", grito(pie, yo, a, false, 0, l -> true, r) == null
                && lamento(pie, yo, a, 0, l -> true, r) == null);
        Alucinaciones.Sonido pegado = grito(new Location(null, 5, 64, 5, 0f, 0f), yo, a, true, 0, spawn, r);
        Location oido = Alucinaciones.donde(new Location(null, 5, 64, 5, 0f, 0f), pegado);
        h.ok("grito pegado: a menos de un bloque, a la altura de la cabeza, de lado o detras",
                Math.abs(oido.getY() - 65.5) < 1e-6 && Math.hypot(oido.getX() - 5, oido.getZ() - 5) < 1.0
                        && oido.getZ() - 5 <= 1e-6);

        // --- los sonidos: existen, sin campana, sin notas, tono que Java toca y que se oyen de lejos.
        List<Son> todos = new ArrayList<>();
        todos.addAll(a.pasos().sonidos());
        todos.addAll(a.respiracion().sonidos());
        todos.addAll(a.gritos().sonidos());
        todos.addAll(a.lamento().sonidos());
        todos.add(new Son(S_LATIDO, 1, 1));
        for (Son s : todos) {
            h.ok("existe en el servidor: " + s.clave(), existe.test(s.clave()));
            h.ok("ley 2, sin campana: " + s.clave(), !s.clave().contains("bell"));
            h.ok("sin notas musicales: " + s.clave(), !s.clave().contains("note_block"));
            h.ok("tono entre 0,5 y 2: " + s.clave(), s.tono() >= TONO_MIN && s.tono() <= TONO_MAX);
        }
        for (Son s : a.gritos().sonidos()) {
            double vol = s.volumen() * a.gritos().volumenLejos();
            h.ok("grito lejano audible: " + s.clave(), a.gritos().lejos().max() < 16 * Math.max(1, vol) * 0.75);
        }
        for (Son s : a.lamento().sonidos()) {
            h.ok("lamento audible: " + s.clave(), a.lamento().lejos().max() < 16 * Math.max(1, s.volumen()) * 0.75);
        }
        h.ok("los pasos tiran de grave (tono de 0,6 o menos)", a.pasos().sonidos().stream().allMatch(s -> s.tono() <= 0.6f));
        h.ok("la respiracion, muy grave", a.respiracion().sonidos().get(0).tono() <= 0.5f);
        YamlConfiguration agudo = new YamlConfiguration();
        agudo.set("gritos.sonidos", List.of(Map.of("sonido", "minecraft:entity.fox.screech", "volumen", 1, "tono", 0.2)));
        h.cerca("un tono por debajo de 0,5 se queda en 0,5", 0.5, Ajustes.de(agudo).gritos().sonidos().get(0).tono(), 1e-6);

        // --- su nombre.
        Component s = susurro("{nombre}, detrás de ti.", "Dosa__");
        String plano = Hardcore.plano(s);
        h.igual("susurro: el texto", "Dosa__, detrás de ti.", plano);
        h.ok("susurro: gris oscuro", VOZ.equals(s.color()));
        h.ok("susurro: en cursiva", s.hasDecoration(TextDecoration.ITALIC));
        h.ok("susurro: no tiene la forma de un chat (\"nombre: texto\")", !plano.contains("Dosa__:"));
        h.ok("susurro: sin negrita", !s.hasDecoration(TextDecoration.BOLD));
        boolean conNombre = true, limpio = true;
        for (String f : FRASES_DE_SERIE) {
            conNombre &= f.contains("{nombre}");
            limpio &= f.codePoints().noneMatch(cp -> cp > 0xFFFF || Character.getType(cp) == Character.OTHER_SYMBOL);
        }
        h.ok("todas las frases llevan su nombre", conNombre);
        h.ok("frases sin emojis", limpio);

        // --- el comando.
        h.igual("madness: un argumento por tipo", Set.copyOf(TIPOS), Set.copyOf(ARGUMENTOS.values()));
        h.ok("madness: argumentos en ingles y en minusculas", ARGUMENTOS.keySet().stream().allMatch(k -> k.matches("[a-z]+")));
        return h.lineas();
    }

    /** La seccion hardcore.locura del config.yml que va dentro del jar (null si no esta). */
    private ConfigurationSection seccionDelJar() {
        try (java.io.InputStream in = hc.plugin().getResource("config.yml")) {
            if (in == null) return null;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            return y.getConfigurationSection("hardcore.locura");
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Segundo a segundo, 'segundos' a esa cordura, como si todo sonara: [ms, indice en SUSTOS] de cada susto. */
    private static List<long[]> simular(Ajustes a, double cordura, int segundos, Random r) {
        Estado e = new Estado();
        List<long[]> out = new ArrayList<>();
        long t0 = 5_000_000;
        for (int s = 0; s < segundos; s++) {
            long ahora = t0 + s * 1000L;
            String tipo = elegir(e, a, cordura, ahora, () -> 0, r);
            if (tipo == null) continue;
            apuntar(e, a, tipo, cordura, ahora, () -> 0, r);
            out.add(new long[]{ahora, SUSTOS.indexOf(tipo)});
        }
        return out;
    }

    private static int maximoPorMinuto(List<long[]> sustos) {
        int max = 0;
        for (int i = 0; i < sustos.size(); i++) {
            int n = 0;
            for (int j = i; j < sustos.size() && sustos.get(j)[0] - sustos.get(i)[0] < 60_000; j++) n++;
            max = Math.max(max, n);
        }
        return max;
    }

    private static long pausaMinima(List<long[]> sustos) {
        long min = Long.MAX_VALUE;
        for (int i = 1; i < sustos.size(); i++) min = Math.min(min, sustos.get(i)[0] - sustos.get(i - 1)[0]);
        return min;
    }

    /** Cuantos latidos caben en tantos segundos con ese intervalo, segundo a segundo como en el juego. */
    private static int latidosEn(int intervalo, int segundos) {
        int fase = 0, n = 0;
        for (int s = 0; s < segundos; s++) {
            Latidos l = latidos(fase, intervalo);
            n += l.retrasos().size();
            fase = l.siguiente();
        }
        return n;
    }
}
