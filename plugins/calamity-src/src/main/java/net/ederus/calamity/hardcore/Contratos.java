package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M14 · Contratos del Umbral (DIS M14, EST sec. 5.9): tres encargos de Oren al dia.
 *
 * Cada dia (00:00 en hardcore.zona) se sortean contratos.por-dia (3) del pool, al menos
 * cortos-garantizados (1) de los cortos: el corto es el que hace que merezca la pena entrar
 * veinte minutos. Un contrato cuya mecanica esta apagada (clave "mecanica" del pool) no entra
 * en el sorteo: pedir "abre 3 cofres" con los cofres vacios seria un encargo imposible.
 *
 * Calamity 1.10 · Pergaminos (contratos.pergamino.activo, de serie encendido). Dosa: "podemos entregar
 * por mision o contrato un pergamino, que lleve el lore y tambien los placeholders del contrato, bien
 * ordenado y encuadrado, que al terminar el contrato se destruya y le entregue las recompensas". Asi:
 *   - cada contrato pendiente es un papel (Pergaminos) que Oren deja al entrar a Calamity (por la puerta
 *     o por cualquier otra via: Hardcore.alLlegar) o que da en su menu (darDesdeMenu). Para el jugador el
 *     pergamino ES el contrato: sin el no avanza (y se le dice, una vez por expedicion). La verdad sigue
 *     en los datos de abajo; un papel que no cuadra con ellos es inerte y se borra;
 *   - lo que se cumple DENTRO se cobra en el acto (cobrar): cumplido y cobrado, guardado ANTES de pagar,
 *     el pergamino se rompe y paga la Aduana (tipo contratos) con las Esencias A LA MANO (Aduana.pagar
 *     con objetoSiDentro; si no caben, a sus pies). Como las de un mob, si muere antes de salir las
 *     pierde: lo aprobo Dosa, "cobras al momento, pero te lo juegas hasta la puerta". Quedar cobrado es
 *     lo que impide repetirlo: morir despues no lo reinicia (reiniciarExpedicion salta lo cobrado);
 *   - lo que solo pasa en la Tasacion (reliquia-ii) se cumple y se cobra al salir vivo (cobrarEnTasacion),
 *     como antes: al saldo, porque se paga justo antes del teleport de salida; ahi se rompe su pergamino.
 * Con contratos.pergamino.activo en false, todo como antes de la 1.10: sin papel, el progreso cuenta solo
 * y lo cumplido se cobra en la Tasacion (los textos de serie ya no dicen "y sal": no hace falta).
 *
 * La barra de accion (contratos.barra, 1.10): a la derecha de la cordura va UN contrato, corto ("Mobs
 * 6/10", con la etiqueta del pool): el ultimo que avanzo y sigue pendiente o, si ninguno, el primero cuyo
 * pergamino lleva (elegirBarra). La pinta Cordura con lo que da sufijoBarra; aqui solo se elige cuando
 * algo cambia, nunca por tick. Y %lethalworld_contrato_<n>_texto|progreso|objetivo|estado|premio% para
 * scoreboards y hologramas (placeholder).
 *
 * El progreso es de la EXPEDICION: se pone a cero al entrar y al morir (lo cobrado no). La lista de una
 * expedicion se fija al entrar: pasar las 00:00 dentro no borra lo que llevas (el sorteo del dia nuevo
 * espera a la siguiente entrada o a que lo mires desde fuera).
 *
 * De donde sale cada evento (los nombres del pool):
 *   mob, destacado     Grifo, solo mobs que pasan los puntos 1-3 (via NORMAL)
 *   minijefe           Minijefes, al asesino
 *   cofre              Cofres, cofre de estructura abierto por un jugador que cuenta
 *   reliquia-ii        Tasacion ("tasa-ii" = cuantas de grado II o mas saca)
 *   minutos            1 por minuto dentro (via Horas.segundo)
 *   minutos-limite     1 por minuto con cordura < contratos.cordura-limite (25)
 *   minutos-sin-frasco 1 por minuto sin beber; beber lo pone a cero si no estaba cumplido
 *   eco-valido         stats.cazas-validas que suben durante la expedicion
 *   redimir            stats.ecos-redimidos que suben durante la expedicion
 *
 * Datos: contratos.<uuid> = {dia, lista.<1-3> = {id, progreso, cumplido, cobrado}, cambios,
 * base.<clave>, semana, cobrados-semana, premio-semana, avisado, ultimo (1.10: el hueco que avanzo
 * el ultimo en esta expedicion), sin-pergamino (1.10: ya se le dijo en esta expedicion que sin
 * pergamino no cuenta)}.
 */
final class Contratos implements Listener {

    static final TextColor VERDE_PALIDO = Paleta.DETALLE;
    static final TextColor AMBAR = TextColor.color(0xE8A33D);

    /** Lo que emiten otros modulos con otro nombre. */
    private static final Map<String, String> ALIAS = Map.of("tasa-ii", "reliquia-ii");
    /** Calamity 1.10: eventos que solo pasan en la Tasacion, al salir vivo. Sus contratos se cobran alli. */
    static final Set<String> DE_TASACION = Set.of("reliquia-ii");
    /** Eventos que salen de una estadistica que sube durante la expedicion. */
    private static final Map<String, String> POR_ESTADISTICA = Map.of(
            "eco-valido", "cazas-validas",
            "redimir", "ecos-redimidos");
    /** "lw hardcore dar llave %jugador% N" en semana-premio: la llave va por Entregas con origen contratos. */
    private static final Pattern LLAVE = Pattern.compile("^/?lw hardcore dar llave %jugador% (\\d+)$");
    /** Calamity 1.10: contratos.barra.modo. */
    static final List<String> MODOS_BARRA = List.of("siempre", "avance", "nunca");
    /** Calamity 1.10: lo que se puede pedir de un hueco en %lethalworld_contrato_<n>_<campo>%. */
    static final List<String> CAMPOS = List.of("texto", "progreso", "objetivo", "estado", "premio");

    /** etiqueta (1.10): el nombre corto con el que sale en la barra de accion. */
    record Def(String id, String texto, String evento, int objetivo, int esencias, long mobcoins,
               boolean corto, String mecanica, String etiqueta) {
    }

    record Avance(List<Integer> cambiados, List<Integer> cumplidos) {
    }

    /**
     * 1.10: un pago de contrato ya decidido (planCobro): el hueco, su contrato, lo que paga y si sus
     * Esencias van a la mano (enLaMano: cumplido dentro) o al saldo (la Tasacion).
     */
    record Cobro(int hueco, String id, int esencias, long mobcoins, boolean enLaMano) {
    }

    /** Lo que dejo Oren al entrar o al volver: los huecos cuyo pergamino dio y cuantos no cupieron. */
    private record Entrega(List<Integer> dados, int sinSitio) {
    }

    private final Hardcore hc;
    private final SecureRandom azar = new SecureRandom();
    /** Segundos de la expedicion: {dentro, con cordura baja, sin beber}. Se limpia en el quit. */
    private final Map<UUID, int[]> reloj = new HashMap<>();
    /** Ultimo destello P-O01 por jugador: con mobs cayendo de dos en dos no se pisa la barra. */
    private final Map<UUID, Long> ultimoDestello = new HashMap<>();
    /** 1.10: el hueco del contrato que va en la barra de cada uno (elegirBarra). Solo memoria. */
    private final Map<UUID, Integer> enBarra = new HashMap<>();
    /** 1.10, modo "avance": hasta cuando (millis) se ve el contrato que acaba de avanzar. */
    private final Map<UUID, Long> avanceHasta = new HashMap<>();
    private final Set<BukkitTask> tareas = new HashSet<>();
    /** 1.10: el objeto (crear, revisar, dar, redibujar, quitar) y sus barreras. */
    private final Pergaminos pergaminos;
    /* volatile: PlaceholderAPI lee el pool desde otro hilo (poolParaLeer). */
    private volatile Map<String, Def> poolLeido;
    private volatile long poolLeidoEn;

    Contratos(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        this.pergaminos = new Pergaminos(hc, this);
        // 1.10: un contrato a la derecha de la cordura. En valor(): pintar corre en el reloj de todos.
        hc.cordura().extra(p -> hc.valor("contratos", () -> sufijoBarra(p), null));
        // El pool leido ya aqui, en el hilo principal: asi el placeholder (otro hilo) nunca lee la config
        // (getConfigurationSection puede crear una seccion vacia si solo esta en el config del jar).
        pool();
        PlaceholdersLethal.registrar("contrato", this::placeholder);
        Subcomandos.lw().registrar("contratos", "contratos <jugador> [reset]: ver o volver a sortear sus contratos (M14)",
                "ederus.mundos", this::comandoAdmin,
                args -> switch (args.length) {
                    case 2 -> Entregas.nombresConectados();
                    case 3 -> List.of("reset");
                    default -> List.of();
                });
        Subcomandos.calamity().registrar("contratos", "tus contratos de hoy y cómo van",
                "lethalworld.calamity", (quien, args) -> {
                    if (quien instanceof Player p) mostrar(p, p);
                    else quien.sendMessage(ComandoCalamity.mensaje("Solo se puede usar dentro del juego."));
                }, null);
        Subcomandos.calamity().registrar("cambiar", "cambiar <1-3>: cambia un contrato (uno gratis al día)",
                "lethalworld.calamity", (quien, args) -> {
                    if (!(quien instanceof Player p)) {
                        quien.sendMessage(ComandoCalamity.mensaje("Solo se puede usar dentro del juego."));
                        return;
                    }
                    int i;
                    try {
                        i = Integer.parseInt(args.length > 1 ? args[1] : "");
                    } catch (NumberFormatException e) {
                        p.sendMessage(ComandoCalamity.mensaje("Uso: /calamity cambiar <1-3>"));
                        return;
                    }
                    cambiar(p, i);
                }, args -> args.length == 2 ? List.of("1", "2", "3") : List.of());
        Autotest.registrar("contratos", this::autotest);
    }

    boolean activo() {
        return hc.cfg().getBoolean("contratos.activo", false);
    }

    /** 1.10: cada contrato es un pergamino. False: como antes, sin papel y todo se cobra al salir vivo. */
    boolean pergaminoActivo() {
        return hc.cfg().getBoolean("contratos.pergamino.activo", true);
    }

    /** 1.10: Oren deja los pergaminos solo al entrar (false: hay que pedirselos en su menu). */
    private boolean entregarAlEntrar() {
        return hc.cfg().getBoolean("contratos.pergamino.entregar-al-entrar", true);
    }

    /** 1.10: siempre | avance | nunca. Sin pergaminos, nunca: la barra como antes. */
    String modoBarra() {
        if (!pergaminoActivo()) return "nunca";
        String m = hc.cfg().getString("contratos.barra.modo", "siempre");
        m = m == null ? "" : m.trim().toLowerCase(Locale.ROOT);
        return MODOS_BARRA.contains(m) ? m : "siempre";
    }

    /** 1.10, modo avance: los segundos que se ve el contrato tras avanzar. */
    private long segundosBarra() {
        return Math.max(1, hc.cfg().getInt("contratos.barra.segundos", 6));
    }

    // ------------------------------------------------------------------ el pool

    /**
     * El pool de la config; si no hay, el de EST sec. 5.9 escrito aqui. Se relee como mucho
     * cada 10 s: lo pide cada mob muerto dentro y un /lw reload se nota igual enseguida.
     */
    Map<String, Def> pool() {
        long ahora = System.currentTimeMillis();
        Map<String, Def> leido = poolLeido;
        if (leido != null && ahora - poolLeidoEn < 10_000L) return leido;
        leido = leerPool();
        poolLeido = leido;
        poolLeidoEn = ahora;
        return leido;
    }

    /** Para PlaceholderAPI (otro hilo): el pool ya leido, sin releer la config si lo hay. */
    private Map<String, Def> poolParaLeer() {
        Map<String, Def> leido = poolLeido;
        return leido != null ? leido : pool();
    }

    private Map<String, Def> leerPool() {
        Map<String, Def> out = new LinkedHashMap<>();
        ConfigurationSection s = hc.cfg().getConfigurationSection("contratos.pool");
        if (s != null) {
            for (String id : s.getKeys(false)) {
                ConfigurationSection d = s.getConfigurationSection(id);
                if (d == null || !d.getBoolean("activo", true)) continue;
                String texto = d.getString("texto", id);
                // 1.10: el config del servidor es de antes y no trae etiqueta: la de serie o dos palabras.
                String etiqueta = d.getString("etiqueta", "");
                if (etiqueta == null || etiqueta.isBlank()) etiqueta = etiquetaPorDefecto(id, texto);
                out.put(id, new Def(id, texto, d.getString("evento", ""),
                        Math.max(1, d.getInt("objetivo", 1)), Math.max(0, d.getInt("esencias", 0)),
                        Math.max(0, d.getLong("mobcoins", 0)), d.getBoolean("corto", false), d.getString("mecanica", ""),
                        etiqueta.trim()));
            }
        }
        if (out.isEmpty()) for (Def d : POR_DEFECTO) out.put(d.id(), d);
        return java.util.Collections.unmodifiableMap(out);
    }

    /*
     * 1.10: sin " y sal" en los que ya se cobran al cumplirlos; los dos de Reliquias conservan su texto
     * (se cumplen al sacarlas, en la Tasacion). La etiqueta es lo que sale en la barra de accion.
     */
    static final List<Def> POR_DEFECTO = List.of(
            new Def("corto-mobs", "Mata 10 mobs", "mob", 10, 2, 20, true, "", "Mobs"),
            new Def("corto-cofre", "Abre un cofre de estructura", "cofre", 1, 2, 20, true, "cofres.activo", "Cofre"),
            new Def("corto-reliquia", "Saca una Reliquia de grado II o más", "reliquia-ii", 1, 2, 20, true, "reliquias.activas",
                    "Reliquia"),
            new Def("corto-15", "Pasa 15 min en Calamity", "minutos", 15, 2, 20, true, "", "Minutos"),
            new Def("extraer-ii", "Saca 3 Reliquias de grado II o más en una sola salida", "reliquia-ii", 3, 4, 60, false,
                    "reliquias.activas", "Reliquias"),
            new Def("destacados", "Mata 5 mobs destacados", "destacado", 5, 3, 40, false, "", "Destacados"),
            new Def("eco", "Derrota un Eco ajeno válido", "eco-valido", 1, 5, 80, false, "eco.activo", "Eco"),
            new Def("al-limite", "Aguanta 15 min con la cordura por debajo de 25", "minutos-limite", 15, 5, 80, false, "",
                    "Al límite"),
            new Def("minijefe", "Mata un minijefe", "minijefe", 1, 6, 100, false, "", "Minijefe"),
            new Def("sin-frasco", "Pasa 30 min en Calamity sin beber del Frasco", "minutos-sin-frasco", 30, 4, 60, false, "",
                    "Sin frasco"),
            new Def("cofres", "Abre 3 cofres de estructura", "cofre", 3, 3, 40, false, "cofres.activo", "Cofres"),
            new Def("redimir", "Derrota a tu propio Eco", "redimir", 1, 4, 60, false, "eco.activo", "Tu Eco"));

    /**
     * 1.10: la etiqueta de un contrato del config que no la trae: la de serie con el mismo id y, si no
     * hay, las dos primeras palabras del texto.
     */
    static String etiquetaPorDefecto(String id, String texto) {
        for (Def d : POR_DEFECTO) if (d.id().equals(id)) return d.etiqueta();
        String t = texto == null ? "" : texto.trim();
        if (t.isEmpty()) return id;
        String[] palabras = t.split("\\s+");
        return palabras.length == 1 ? palabras[0] : palabras[0] + " " + palabras[1];
    }

    /** 1.10: "2 Esencias y 20 MobCoins": lo que paga un contrato, como se lee (pergamino, menu, placeholder). */
    static String premio(Def d) {
        String e = Marco.esencias(d.esencias()), mc = Altar.miles(d.mobcoins()) + " MobCoins";
        if (d.esencias() > 0 && d.mobcoins() > 0) return e + " y " + mc;
        if (d.esencias() > 0) return e;
        return d.mobcoins() > 0 ? mc : "sin premio";
    }

    /** 1.10: si ese contrato se cumple y se cobra al salir vivo (en la Tasacion) y no al cumplirlo dentro. */
    static boolean seCobraAlSalir(Def d) {
        return d != null && DE_TASACION.contains(ALIAS.getOrDefault(d.evento(), d.evento()));
    }

    /**
     * Si el contrato puede salir: su interruptor (mecanica) encendido y, para los de cofres,
     * que los cofres no esten vaciados por la regla de dificultad (entonces no llega ningun
     * evento "cofre").
     */
    boolean disponible(Def d) {
        String m = d.mecanica();
        if (m != null && !m.isBlank() && !hc.cfg().getBoolean(m, true)) return false;
        if ("cofre".equals(d.evento()) && hc.cfg().getBoolean("dificultad.cofres-vacios", true)) return false;
        return true;
    }

    /**
     * El sorteo del dia: primero los cortos garantizados, luego el resto de todo lo que quede
     * (puede salir otro corto). Sin repetir. Si el pool no llega, salen los que haya.
     */
    static List<Def> sortear(Collection<Def> pool, int porDia, int cortos, Predicate<Def> disponible, SecureRandom azar) {
        List<Def> libres = new ArrayList<>();
        for (Def d : pool) if (disponible.test(d)) libres.add(d);
        List<Def> out = new ArrayList<>();
        List<Def> soloCortos = new ArrayList<>();
        for (Def d : libres) if (d.corto()) soloCortos.add(d);
        for (int i = 0; i < cortos && out.size() < porDia && !soloCortos.isEmpty(); i++) {
            Def d = soloCortos.remove(azar.nextInt(soloCortos.size()));
            out.add(d);
            libres.remove(d);
        }
        while (out.size() < porDia && !libres.isEmpty()) out.add(libres.remove(azar.nextInt(libres.size())));
        return out;
    }

    /** Uno nuevo para un hueco: ninguno de los que ya tiene; corto si hace falta para mantener la garantia. */
    static Def sustituto(Collection<Def> pool, Set<String> yaEstan, boolean hadeSerCorto, Predicate<Def> disponible,
                         SecureRandom azar) {
        List<Def> libres = new ArrayList<>();
        for (Def d : pool) {
            if (yaEstan.contains(d.id()) || !disponible.test(d)) continue;
            if (hadeSerCorto && !d.corto()) continue;
            libres.add(d);
        }
        return libres.isEmpty() ? null : libres.get(azar.nextInt(libres.size()));
    }

    // ------------------------------------------------------------ la libreta (nucleo)

    /** Escribe la lista del dia en la seccion del jugador (y pone los cambios a cero). */
    static void escribirLista(ConfigurationSection s, String dia, List<Def> lista) {
        s.set("dia", dia);
        s.set("lista", null);
        s.set("cambios", 0);
        for (int i = 0; i < lista.size(); i++) ponerEn(s, i + 1, lista.get(i).id());
    }

    static void ponerEn(ConfigurationSection s, int i, String id) {
        String r = "lista." + i;
        s.set(r + ".id", id);
        s.set(r + ".progreso", 0);
        s.set(r + ".cumplido", false);
        s.set(r + ".cobrado", false);
    }

    static List<Integer> huecos(ConfigurationSection s) {
        List<Integer> out = new ArrayList<>();
        ConfigurationSection l = s.getConfigurationSection("lista");
        if (l == null) return out;
        for (String k : l.getKeys(false)) {
            try {
                out.add(Integer.parseInt(k));
            } catch (NumberFormatException ignorado) {
                // Una clave rara no es un hueco.
            }
        }
        out.sort(Integer::compare);
        return out;
    }

    /** Suma n al progreso de los contratos no cobrados de ese evento. No paga nada. */
    static Avance avanzar(ConfigurationSection s, Map<String, Def> pool, String evento, int n) {
        return avanzar(s, pool, evento, n, null);
    }

    /**
     * Lo mismo, solo en los huecos de "solo" (1.10: aquellos cuyo pergamino lleva); null = todos (sin
     * pergaminos, como antes). Un hueco cumplido o cobrado no avanza: es lo que impide cobrar dos veces.
     */
    static Avance avanzar(ConfigurationSection s, Map<String, Def> pool, String evento, int n, Set<Integer> solo) {
        String ev = ALIAS.getOrDefault(evento, evento);
        List<Integer> cambiados = new ArrayList<>(), cumplidos = new ArrayList<>();
        for (int i : huecos(s)) {
            if (solo != null && !solo.contains(i)) continue;
            String r = "lista." + i;
            Def d = pool.get(s.getString(r + ".id", ""));
            if (d == null || !d.evento().equals(ev) || s.getBoolean(r + ".cobrado", false)
                    || s.getBoolean(r + ".cumplido", false)) continue;
            int nuevo = Math.min(d.objetivo(), s.getInt(r + ".progreso", 0) + n);
            s.set(r + ".progreso", nuevo);
            cambiados.add(i);
            if (nuevo >= d.objetivo()) {
                s.set(r + ".cumplido", true);
                cumplidos.add(i);
            }
        }
        return new Avance(cambiados, cumplidos);
    }

    /** 1.10: los huecos sin cumplir de ese evento: lo que avanzaria si llevase todos los pergaminos. */
    static List<Integer> pendientesDe(ConfigurationSection s, Map<String, Def> pool, String evento) {
        String ev = ALIAS.getOrDefault(evento, evento);
        List<Integer> out = new ArrayList<>();
        for (int i : huecos(s)) {
            String r = "lista." + i;
            Def d = pool.get(s.getString(r + ".id", ""));
            if (d == null || !d.evento().equals(ev) || s.getBoolean(r + ".cobrado", false)
                    || s.getBoolean(r + ".cumplido", false)) continue;
            out.add(i);
        }
        return out;
    }

    /** Los huecos cumplidos y sin cobrar. */
    static List<Integer> cobrables(ConfigurationSection s) {
        List<Integer> out = new ArrayList<>();
        for (int i : huecos(s)) {
            String r = "lista." + i;
            if (s.getBoolean(r + ".cumplido", false) && !s.getBoolean(r + ".cobrado", false)) out.add(i);
        }
        return out;
    }

    /**
     * 1.10: marca cobrados esos huecos y los suma a los de la semana (sem). No paga: eso va despues de
     * guardar (cobrar). La comparten el cobro dentro y la Tasacion.
     */
    static void marcarCobrados(ConfigurationSection s, List<Integer> huecos, String sem) {
        for (int i : huecos) s.set("lista." + i + ".cobrado", true);
        if (!sem.equals(s.getString("semana", ""))) {
            s.set("semana", sem);
            s.set("cobrados-semana", 0);
        }
        s.set("cobrados-semana", s.getInt("cobrados-semana", 0) + huecos.size());
    }

    /**
     * 1.10 · El nucleo del cobro, sin Bukkit: de esos huecos, los que aun no estan cobrados se marcan
     * cobrados (y suman a la semana sem) y sale lo que hay que pagar por cada uno. alMomento: cumplido
     * dentro de Calamity, Esencias a la mano (enLaMano); si no, la Tasacion, al saldo. Lo ya cobrado no
     * sale: es lo que impide cobrar dos veces. No guarda ni paga: eso lo hace cobrar(), en ese orden.
     */
    static List<Cobro> planCobro(ConfigurationSection s, Map<String, Def> pool, List<Integer> huecos, boolean alMomento,
                                 String sem) {
        List<Integer> nuevos = new ArrayList<>();
        for (int i : huecos) if (!s.getBoolean("lista." + i + ".cobrado", false) && !nuevos.contains(i)) nuevos.add(i);
        if (nuevos.isEmpty()) return List.of();
        marcarCobrados(s, nuevos, sem);
        List<Cobro> out = new ArrayList<>();
        for (int i : nuevos) {
            String id = s.getString("lista." + i + ".id", "?");
            Def d = pool.get(id);
            out.add(new Cobro(i, id, d == null ? 0 : d.esencias(), d == null ? 0 : d.mobcoins(), alMomento));
        }
        return out;
    }

    /** Expedicion nueva o muerte: lo no cobrado vuelve a cero (el contrato sigue). */
    static void reiniciarExpedicion(ConfigurationSection s) {
        for (int i : huecos(s)) {
            String r = "lista." + i;
            if (s.getBoolean(r + ".cobrado", false)) continue;
            s.set(r + ".progreso", 0);
            s.set(r + ".cumplido", false);
        }
    }

    /**
     * Pone a cero los de un evento que aun no se cumplieron (beber rompe el "sin frasco"). Devuelve los
     * que llevaban algo (1.10: sus pergaminos se redibujan).
     */
    static List<Integer> romper(ConfigurationSection s, Map<String, Def> pool, String evento) {
        List<Integer> out = new ArrayList<>();
        for (int i : huecos(s)) {
            String r = "lista." + i;
            Def d = pool.get(s.getString(r + ".id", ""));
            if (d == null || !d.evento().equals(evento) || s.getBoolean(r + ".cumplido", false)) continue;
            if (s.getInt(r + ".progreso", 0) > 0) out.add(i);
            s.set(r + ".progreso", 0);
        }
        return out;
    }

    /** Los contratos de una libreta tal cual (sin sorteo), en el orden de los huecos. */
    static List<Estado> estadosDe(ConfigurationSection s, Map<String, Def> pool) {
        List<Estado> out = new ArrayList<>();
        if (s == null) return out;
        for (int i : huecos(s)) {
            String r = "lista." + i;
            Def d = pool.get(s.getString(r + ".id", ""));
            if (d == null) continue;
            out.add(new Estado(i, d, s.getInt(r + ".progreso", 0), s.getBoolean(r + ".cumplido", false),
                    s.getBoolean(r + ".cobrado", false)));
        }
        return out;
    }

    /**
     * 1.10 · El contrato de la barra: el ultimo que avanzo en esta expedicion, si sigue sin cumplir y
     * lleva su pergamino; si no, el primero sin cumplir cuyo pergamino lleva; si no lleva ninguno, null.
     */
    static Integer elegirBarra(List<Estado> estados, Integer ultimo, Set<Integer> lleva) {
        if (ultimo != null && lleva.contains(ultimo)) {
            for (Estado e : estados) if (e.hueco() == ultimo && !e.cumplido() && !e.cobrado()) return ultimo;
        }
        for (Estado e : estados) if (!e.cumplido() && !e.cobrado() && lleva.contains(e.hueco())) return e.hueco();
        return null;
    }

    /** 1.10: lo que se pega a la derecha de la cordura: "   ·   Mobs 6/10". */
    static Component sufijo(Def d, int progreso) {
        int objetivo = Math.max(1, d.objetivo());
        return Component.text("   ·   ", Paleta.SEPARADOR)
                .append(Component.text(d.etiqueta() + " ", VERDE_PALIDO))
                .append(Component.text(Math.max(0, Math.min(progreso, objetivo)) + "/" + objetivo, Paleta.CIFRA));
    }

    /**
     * 1.10: un campo de un hueco para los placeholders. "" si ese hueco no tiene contrato (o ya no esta
     * en el pool); el campo ya viene validado (CAMPOS). Solo lee: puede correr en otro hilo.
     */
    static String campo(ConfigurationSection s, Map<String, Def> pool, int n, String que) {
        if (s == null || pool == null) return "";
        String r = "lista." + n;
        Def d = pool.get(s.getString(r + ".id", ""));
        if (d == null) return "";
        boolean cumplido = s.getBoolean(r + ".cumplido", false), cobrado = s.getBoolean(r + ".cobrado", false);
        return switch (que) {
            case "texto" -> d.texto();
            case "progreso" -> String.valueOf(Math.max(0, Math.min(d.objetivo(), s.getInt(r + ".progreso", 0))));
            case "objetivo" -> String.valueOf(d.objetivo());
            case "estado" -> cobrado ? "cobrado" : cumplido ? "cumplido" : "pendiente";
            case "premio" -> premio(d);
            default -> "";
        };
    }

    // ------------------------------------------------------------------ datos

    private String hoy() {
        Calendario c = hc.calendario();
        return c != null ? c.dia() : new Calendario(hc).dia();
    }

    private String semana() {
        Calendario c = hc.calendario();
        return c != null ? c.semana() : new Calendario(hc).semana();
    }

    private ConfigurationSection seccion(UUID u) {
        ConfigurationSection s = hc.datos().getConfigurationSection("contratos." + u);
        return s != null ? s : hc.datos().createSection("contratos." + u);
    }

    /**
     * La libreta del jugador. Sortea si esta vacia o si es de otro dia y se puede (fuera de
     * Calamity o al entrar): dentro, la lista de la expedicion no cambia a medianoche.
     */
    private ConfigurationSection libreta(UUID u, boolean puedeSortear) {
        ConfigurationSection s = seccion(u);
        boolean vacia = huecos(s).isEmpty();
        if (vacia || (puedeSortear && !hoy().equals(s.getString("dia", "")))) {
            ConfigurationSection c = hc.cfg();
            List<Def> lista = sortear(pool().values(), Math.max(1, c.getInt("contratos.por-dia", 3)),
                    Math.max(0, c.getInt("contratos.cortos-garantizados", 1)), this::disponible, azar);
            escribirLista(s, hoy(), lista);
            s.set("base", null);
            hc.marcarSucio();
            List<String> ids = new ArrayList<>();
            for (Def d : lista) ids.add(d.id());
            hc.plugin().bitacora().anotar("contrato", "sorteo", Saldo.nombre(u), String.join(",", ids));
        }
        return s;
    }

    private static Integer ultimo(ConfigurationSection s) {
        return s != null && s.isInt("ultimo") ? s.getInt("ultimo") : null;
    }

    /** Algo dentro de "ticks", si sigue conectado. La tarea se cancela en parar(). */
    private void luego(Player p, long ticks, Runnable r) {
        final BukkitTask[] t = new BukkitTask[1];
        t[0] = hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(), () -> {
            tareas.remove(t[0]);
            if (p.isOnline()) hc.seguro("contratos", r);
        }, ticks);
        tareas.add(t[0]);
    }

    // ------------------------------------------------------------------ ganchos

    /**
     * Al llegar a Calamity (Hardcore.alLlegar: por la puerta o por cualquier otra via): expedicion
     * nueva, foto de las estadisticas y, con pergaminos, Oren deja los de hoy que no estan cobrados
     * (P-O04). Sin pergaminos, el aviso de antes, una vez al dia.
     */
    void alEntrar(Player p) {
        if (!activo() || p == null) return;
        UUID u = p.getUniqueId();
        ConfigurationSection s = libreta(u, true);
        reiniciarExpedicion(s);
        Estadisticas st = hc.estadisticas();
        s.set("base", null);
        if (st != null) for (String clave : POR_ESTADISTICA.values()) s.set("base." + clave, st.de(u, clave));
        // La barra y el aviso de "sin pergamino" son de la expedicion: empiezan de cero.
        s.set("ultimo", null);
        s.set("sin-pergamino", null);
        avanceHasta.remove(u);
        reloj.put(u, new int[4]);
        hc.marcarSucio();

        String dia = hoy();
        boolean queda = false;
        for (int i : huecos(s)) if (!s.getBoolean("lista." + i + ".cobrado", false)) queda = true;
        if (pergaminoActivo()) {
            Map<Integer, Integer> lleva = pergaminos.revisar(p, s);
            // Lo que traiga (no deberia: se borran al salir) vuelve a decir lo de esta expedicion.
            redibujarTodos(p, s, lleva);
            Set<Integer> tiene = new HashSet<>(lleva.keySet());
            if (entregarAlEntrar()) {
                Entrega r = entregar(p, s, lleva.keySet());
                tiene.addAll(r.dados());
                avisoEntrega(p, r);
            } else if (queda && !dia.equals(s.getString("avisado", ""))) {
                s.set("avisado", dia);
                luego(p, 40L, () -> p.sendMessage(ComandoCalamity.mensaje(
                        "Oren tiene contratos para ti: pídele sus pergaminos para que cuenten.")));
            }
            refrescarBarra(p, s, tiene);
            return;
        }
        if (queda && !dia.equals(s.getString("avisado", ""))) {
            s.set("avisado", dia);
            // Dos segundos despues: al entrar llueven mensajes (bienvenida, Ecos, altar).
            luego(p, 40L, () -> p.sendMessage(ComandoCalamity.mensaje(Component.text("Oren tiene contratos para ti. Míralos con ")
                    .append(Component.text("/calamity contratos", Paleta.DETALLE)).append(Component.text(".")))));
        }
    }

    /**
     * 1.10: quien se conecta estando dentro sigue su expedicion (Hardcore.onEntrar no la toca): aqui solo
     * se le reponen los pergaminos que falten de contratos pendientes, sin tocar el progreso.
     */
    void alVolver(Player p) {
        if (!activo() || !pergaminoActivo() || p == null || !hc.esHardcore(p)) return;
        ConfigurationSection s = libreta(p.getUniqueId(), false);
        Map<Integer, Integer> lleva = pergaminos.revisar(p, s);
        redibujarTodos(p, s, lleva);
        Set<Integer> tiene = new HashSet<>(lleva.keySet());
        if (entregarAlEntrar()) {
            Entrega r = entregar(p, s, lleva.keySet());
            tiene.addAll(r.dados());
            avisoEntrega(p, r);
        }
        refrescarBarra(p, s, tiene);
    }

    /** 1.10: los pergaminos de los huecos pendientes (sin cumplir ni cobrar) que no lleva. Lo que no cabe, no se da. */
    private Entrega entregar(Player p, ConfigurationSection s, Set<Integer> ya) {
        Map<String, Def> pool = pool();
        String dia = s.getString("dia", "");
        List<Integer> dados = new ArrayList<>();
        int sinSitio = 0;
        for (int i : huecos(s)) {
            String r = "lista." + i;
            Def d = pool.get(s.getString(r + ".id", ""));
            if (d == null || ya.contains(i) || s.getBoolean(r + ".cumplido", false) || s.getBoolean(r + ".cobrado", false)) continue;
            if (pergaminos.dar(p, new Pergaminos.Sello(p.getUniqueId(), dia, i, d.id()), d, s.getInt(r + ".progreso", 0))) {
                dados.add(i);
            } else {
                sinSitio++;
            }
        }
        if (!dados.isEmpty() || sinSitio > 0) {
            hc.plugin().bitacora().anotar("contrato", "pergaminos", p.getName(), "dados " + dados.size(), "sin sitio " + sinSitio);
        }
        return new Entrega(dados, sinSitio);
    }

    /** P-O04 con pergaminos, dos segundos despues: al entrar llueven mensajes (bienvenida, Ecos, altar). */
    private void avisoEntrega(Player p, Entrega r) {
        if (r.dados().isEmpty() && r.sinSitio() == 0) return;
        luego(p, 40L, () -> {
            if (!r.dados().isEmpty()) p.sendMessage(ComandoCalamity.mensaje("Oren te ha dejado tus contratos de hoy."));
            if (r.sinSitio() > 0) {
                p.sendMessage(ComandoCalamity.mensaje("No tienes espacio para todos tus contratos. Pídeselos a Oren."));
            }
        });
    }

    /** Los pergaminos que lleva (hueco -> casilla, de revisar) vuelven a decir lo que dice la libreta. */
    private void redibujarTodos(Player p, ConfigurationSection s, Map<Integer, Integer> lleva) {
        Map<String, Def> pool = pool();
        for (Map.Entry<Integer, Integer> en : lleva.entrySet()) {
            String r = "lista." + en.getKey();
            Def d = pool.get(s.getString(r + ".id", ""));
            if (d != null) pergaminos.redibujar(p, en.getValue(), en.getKey(), d, s.getInt(r + ".progreso", 0));
        }
    }

    /**
     * Lo llaman Grifo, Minijefes, Cofres, Horas (via segundo), Hitos (via estadistica) y la Tasacion.
     * Solo cuenta dentro.
     *
     * Con pergaminos (1.10) solo avanza el contrato cuyo pergamino lleva (si falta alguno que habria
     * avanzado, se le avisa una vez por expedicion), su lore se redibuja y lo que se cumple dentro se cobra en
     * el acto (cobrar). Lo de la Tasacion (reliquia-ii) se cumple aqui y lo cobra cobrarEnTasacion justo
     * despues. Sin pergaminos, como antes: todo se cobra al salir vivo.
     */
    void progreso(Player p, String evento, int n) {
        if (!activo() || p == null || evento == null || n <= 0 || !hc.esHardcore(p)) return;
        UUID u = p.getUniqueId();
        ConfigurationSection s = libreta(u, false);
        Map<String, Def> pool = pool();
        String ev = ALIAS.getOrDefault(evento, evento);
        boolean papel = pergaminoActivo();
        Map<Integer, Integer> lleva = null;
        if (papel) {
            // Antes de mirar el inventario: un mob que no le toca a ningun contrato no cuesta nada.
            List<Integer> tocan = pendientesDe(s, pool, ev);
            if (tocan.isEmpty()) return;
            lleva = pergaminos.revisar(p, s);
            if (!lleva.keySet().containsAll(tocan)) avisarSinPergamino(p, s);
        }
        Avance a = avanzar(s, pool, ev, n, lleva == null ? null : lleva.keySet());
        if (a.cambiados().isEmpty()) return;
        hc.marcarSucio();
        for (int i : a.cumplidos()) {
            Def d = pool.get(s.getString("lista." + i + ".id", ""));
            if (d == null) continue;
            hc.plugin().bitacora().anotar("contrato", "cumplido", p.getName(), d.id());
            telemetria(p, d, "cumplido", 0, 0);
            if (!papel) {
                p.sendMessage(ComandoCalamity.mensaje(Component.text("Contrato cumplido: ")
                        .append(Component.text(d.texto(), Paleta.DETALLE))
                        .append(Component.text(". Lo cobras al salir vivo."))));
            }
        }
        if (!papel) {
            if (a.cumplidos().isEmpty()) destello(p, s, pool, a.cambiados().get(0));
            return;
        }
        // Dentro, lo cumplido se cobra ya: el pergamino se rompe y paga. Lo de la Tasacion lo cobra ella.
        if (!a.cumplidos().isEmpty() && !DE_TASACION.contains(ev)) cobrar(p, s, a.cumplidos(), true);
        Integer ultimo = null;
        for (int i : a.cambiados()) {
            if (a.cumplidos().contains(i)) continue;
            ultimo = i;
            Integer casilla = lleva.get(i);
            Def d = pool.get(s.getString("lista." + i + ".id", ""));
            if (casilla != null && d != null) pergaminos.redibujar(p, casilla, i, d, s.getInt("lista." + i + ".progreso", 0));
        }
        if (ultimo != null) {
            s.set("ultimo", ultimo);
            avanceHasta.put(u, System.currentTimeMillis() + segundosBarra() * 1000L);
        }
        Set<Integer> quedan = new HashSet<>(lleva.keySet());
        quedan.removeAll(a.cumplidos());
        refrescarBarra(p, s, quedan);
        // El destello de cada avance sobra si el contrato ya va en la barra: solo en el modo "nunca".
        if ("nunca".equals(modoBarra()) && a.cumplidos().isEmpty()) destello(p, s, pool, a.cambiados().get(0));
    }

    /** 1.10: algo habria contado y le falta su pergamino. Una vez por expedicion (se apunta en la libreta). */
    private void avisarSinPergamino(Player p, ConfigurationSection s) {
        if (s.getBoolean("sin-pergamino", false)) return;
        s.set("sin-pergamino", true);
        hc.marcarSucio();
        p.sendMessage(ComandoCalamity.mensaje("Pídele tus contratos a Oren para que cuenten."));
    }

    /** P-O01, como mucho uno cada 2 s. */
    private void destello(Player p, ConfigurationSection s, Map<String, Def> pool, int i) {
        long ahora = System.currentTimeMillis();
        Long antes = ultimoDestello.get(p.getUniqueId());
        if (antes != null && ahora - antes < 2000) return;
        Def d = pool.get(s.getString("lista." + i + ".id", ""));
        if (d == null) return;
        ultimoDestello.put(p.getUniqueId(), ahora);
        hc.cordura().destello(p, Component.text("Contrato · ", VERDE_PALIDO)
                .append(Component.text(d.texto() + " ", Paleta.TEXTO))
                .append(Component.text(s.getInt("lista." + i + ".progreso", 0) + "/" + d.objetivo(), Paleta.CIFRA)), 2);
    }

    /**
     * Cada segundo dentro (desde Horas.segundo): los contratos de tiempo.
     *
     * 1.10: en la zona spawn el reloj de los tres se para. Alli no baja la cordura ni hay mobs ni PvP,
     * y desde que el contrato se cobra en la mano al cumplirlo, "pasa 15 min" esperando en la plaza era
     * un premio sin riesgo a un paso de la puerta. Los minutos ya contados no se pierden.
     *
     * Revision 1.10: en la pantalla de muerte tampoco cuenta (sigue en el mundo hasta reaparecer, y con
     * lo-pierde-todo en false el papel sobrevive a la muerte).
     */
    void segundo(Player p) {
        if (!activo() || p.isDead()) return;
        UUID u = p.getUniqueId();
        int[] c = reloj.computeIfAbsent(u, k -> new int[4]);
        // El cuarto contador es solo el reloj de la barra (cada 15 s): ese si corre en el spawn.
        boolean barra = ++c[3] % 15 == 0 && !"nunca".equals(modoBarra());
        if (hc.enSpawn(p)) {
            if (barra) refrescarBarra(p);
            return;
        }
        if (++c[0] % 60 == 0) progreso(p, "minutos", 1);
        Cordura.Estado e = hc.cordura().todos().get(u);
        if (e != null && e.valor < hc.cfg().getDouble("contratos.cordura-limite", 25) && ++c[1] % 60 == 0) {
            progreso(p, "minutos-limite", 1);
        }
        if (++c[2] % 60 == 0) progreso(p, "minutos-sin-frasco", 1);
        // 1.10: cada 15 s se miran sus pergaminos, por si alguno se fue por una via que no avisa (un /clear
        // del staff): la barra no ensena un contrato que ya no lleva. Todo lo demas la cambia al momento.
        if (barra) refrescarBarra(p);
    }

    /**
     * Una estadistica que ha cambiado (lo reenvia Hitos.revisar): las cazas validas y los Ecos
     * redimidos cuentan lo que suben desde la foto de la entrada.
     */
    void estadistica(UUID u, String clave) {
        if (u == null || clave == null || !POR_ESTADISTICA.containsValue(clave) || !activo()) return;
        Player p = Bukkit.getPlayer(u);
        Estadisticas st = hc.estadisticas();
        if (p == null || st == null || !hc.esHardcore(p)) return;
        ConfigurationSection s = seccion(u);
        long base = s.getLong("base." + clave, -1);
        if (base < 0) return;
        long ahora = st.de(u, clave);
        if (ahora <= base) return;
        s.set("base." + clave, ahora);
        for (Map.Entry<String, String> en : POR_ESTADISTICA.entrySet()) {
            if (en.getValue().equals(clave)) progreso(p, en.getKey(), (int) Math.min(Integer.MAX_VALUE, ahora - base));
        }
    }

    /**
     * Beber del Frasco rompe el "sin beber". LOWEST y sin cancelar: se mira lo mismo que mira
     * Hardcore.onUsar antes de dar el trago (mano principal, clic derecho, frasco con tragos).
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void alBeber(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !e.getAction().isRightClick()) return;
        ItemStack it = e.getItem();
        if (it == null || !activo()) return;
        Player p = e.getPlayer();
        if (!hc.esHardcore(p) || hc.items().tragos(it) <= 0) return;
        int[] c = reloj.get(p.getUniqueId());
        if (c != null) c[2] = 0;
        ConfigurationSection s = hc.datos().getConfigurationSection("contratos." + p.getUniqueId());
        if (s == null) return;
        Map<String, Def> pool = pool();
        List<Integer> rotos = romper(s, pool, "minutos-sin-frasco");
        hc.marcarSucio();
        // 1.10: su pergamino vuelve a cero con el.
        if (rotos.isEmpty() || !pergaminoActivo()) return;
        Map<Integer, Integer> lleva = pergaminos.revisar(p, s);
        for (int i : rotos) {
            Integer casilla = lleva.get(i);
            Def d = pool.get(s.getString("lista." + i + ".id", ""));
            if (casilla != null && d != null) pergaminos.redibujar(p, casilla, i, d, 0);
        }
    }

    /** Morir es perder lo de esta expedicion (MONITOR: si otro cancela la muerte, no pasa nada). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alMorir(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!activo() || !hc.esHardcore(p)) return;
        UUID u = p.getUniqueId();
        reloj.remove(u);
        enBarra.remove(u);
        avanceHasta.remove(u);
        ConfigurationSection s = hc.datos().getConfigurationSection("contratos." + u);
        if (s == null) return;
        reiniciarExpedicion(s);
        s.set("base", null);
        s.set("ultimo", null);
        hc.marcarSucio();
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        reloj.remove(u);
        ultimoDestello.remove(u);
        enBarra.remove(u);
        avanceHasta.remove(u);
    }

    /** 1.10: al conectarse dentro sigue la expedicion (alVolver); fuera, si trae algun pergamino, se borra. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void alConectar(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (hc.esHardcore(p)) hc.seguro("contratos", () -> alVolver(p));
        else hc.seguro("contratos", () -> borrarPergaminos(p));
    }

    /** 1.10: salir de Calamity por cualquier via que no sea Hardcore.sacar (que ya los borra). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void alCambiarMundo(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (hc.esHardcore(e.getFrom()) && !hc.esHardcore(p)) hc.seguro("contratos", () -> borrarPergaminos(p));
    }

    /**
     * 1.10: los pergaminos no salen de Calamity. Lo llaman Hardcore.sacar (como Kit.borrarPrestado), el
     * cambio a un mundo que no es hardcore y la conexion fuera.
     */
    void borrarPergaminos(Player p) {
        if (p == null) return;
        UUID u = p.getUniqueId();
        enBarra.remove(u);
        avanceHasta.remove(u);
        int n = pergaminos.borrarTodos(p);
        if (n > 0) hc.plugin().bitacora().anotar("contrato", "pergaminos-fuera", p.getName(), String.valueOf(n));
    }

    /**
     * Ids de los contratos cumplidos que se cobran en esta tasacion (1.10: los de Reliquias; con los
     * pergaminos apagados, todos). Los paga cobrar(), que rompe sus pergaminos, con las Esencias al
     * saldo: es justo antes del teleport de salida.
     */
    List<String> cobrarEnTasacion(Player p) {
        if (!activo() || p == null) return List.of();
        ConfigurationSection s = hc.datos().getConfigurationSection("contratos." + p.getUniqueId());
        if (s == null) return List.of();
        List<Integer> cobrar = cobrables(s);
        if (cobrar.isEmpty()) return List.of();
        return cobrar(p, s, cobrar, false);
    }

    /**
     * Cobra esos huecos ya cumplidos (planCobro): los marca cobrados (y suma la semana) y GUARDA antes de
     * pagar, rompe sus pergaminos y paga cada uno por la Aduana (tipo contratos, tope diario 3) con las
     * cifras que de verdad entrega; luego el premio de la semana. alMomento: cumplido dentro de Calamity
     * ("Contrato cumplido", 1.10), con las Esencias a la mano (Aduana.pagar con objetoSiDentro: se pierden
     * si muere antes de salir); si no, la Tasacion ("Contrato cobrado"), al saldo. Un hueco ya cobrado no
     * se paga otra vez.
     */
    private List<String> cobrar(Player p, ConfigurationSection s, List<Integer> huecos, boolean alMomento) {
        UUID u = p.getUniqueId();
        String sem = semana();
        Map<String, Def> pool = pool();
        List<Cobro> cobros = planCobro(s, pool, huecos, alMomento, sem);
        if (cobros.isEmpty()) return List.of();
        hc.guardarYa();
        // El pergamino es el contrato: cobrado, se rompe. Antes del pago: deja sitio a las Esencias.
        List<Integer> rotos = new ArrayList<>();
        for (Cobro c : cobros) rotos.add(c.hueco());
        hc.seguro("contratos", () -> pergaminos.quitar(p, rotos));

        List<String> ids = new ArrayList<>();
        Aduana ad = hc.aduana();
        Estadisticas st = hc.estadisticas();
        for (Cobro c : cobros) {
            String id = c.id();
            ids.add(id);
            Def d = pool.get(id);
            Aduana.Pago pago = ad == null ? null
                    : ad.pagar(p, "contratos", c.esencias(), c.mobcoins(), List.of(), "contrato:" + id, c.enLaMano());
            int pe = pago == null ? 0 : pago.esencias();
            long pmc = pago == null ? 0 : pago.mc();
            if (st != null) st.sumar(u, "contratos", 1);
            hc.plugin().bitacora().anotar("contrato", "cobrado", p.getName(), id, "e " + pe, "mc " + pmc);
            telemetria(p, d == null ? new Def(id, id, "", 1, 0, 0, false, "", id) : d, "cobrado", pe, pmc);
            p.sendMessage(ComandoCalamity.mensaje(Component.text(alMomento ? "Contrato cumplido: " : "Contrato cobrado: ")
                    .append(Component.text(d == null ? id : d.texto(), Paleta.DETALLE))
                    .append(Component.text(" ("))
                    .append(Component.text("+" + Altar.miles(pe), Paleta.CIFRA)).append(Component.text(pe == 1 ? " Esencia y " : " Esencias y "))
                    .append(Component.text("+" + Altar.miles(pmc), Paleta.CIFRA)).append(Component.text(" MobCoins)."))));
        }
        if (alMomento) Marco.sonar(p, "entity.experience_orb.pickup", 0.5f, 1.3f);
        premioSemana(p, s, sem);
        return ids;
    }

    /** semana-objetivo (12) cobrados en la semana: una vez por semana, la Llave del Caos. */
    private void premioSemana(Player p, ConfigurationSection s, String sem) {
        int objetivo = Math.max(1, hc.cfg().getInt("contratos.semana-objetivo", 12));
        if (s.getInt("cobrados-semana", 0) < objetivo || sem.equals(s.getString("premio-semana", ""))) return;
        s.set("premio-semana", sem);
        hc.guardarYa();
        List<String> premio = hc.cfg().getStringList("contratos.semana-premio");
        if (premio.isEmpty() && !hc.cfg().isSet("contratos.semana-premio")) premio = List.of("lw hardcore dar llave %jugador% 1");
        for (String plantilla : premio) {
            Matcher m = LLAVE.matcher(plantilla.trim());
            if (m.matches()) {
                // Por Entregas y no por el comando: asi la llave cuenta en el tope con origen "contratos".
                Entregas en = hc.entregas();
                int n = Integer.parseInt(m.group(1));
                int dadas = en == null ? -1 : en.llave(p, n, "contratos", true);
                hc.plugin().bitacora().anotar("contrato", "semana", p.getName(), "llave " + n, "dadas " + dadas);
            } else {
                boolean ok = consola(plantilla, p.getName());
                hc.plugin().bitacora().anotar("contrato", "semana", p.getName(), plantilla, ok ? "ok" : "fallo");
            }
        }
        p.sendMessage(ComandoCalamity.mensaje(Component.text("Has cobrado " + objetivo + " contratos esta semana: ")
                .append(Component.text("Oren te da una Llave del Caos.", Paleta.DETALLE))));
        telemetria(p, new Def("semana", "semana", "", objetivo, 0, 0, false, "", "semana"), "semana", 0, 0);
    }

    private boolean consola(String plantilla, String nombre) {
        if (plantilla == null || plantilla.isBlank()) return true;
        if (nombre == null || !Entregas.NOMBRE_VALIDO.matcher(nombre).matches()) return false;
        String cmd = plantilla.replace("%jugador%", nombre).trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        try {
            return hc.plugin().getServer().dispatchCommand(hc.plugin().getServer().getConsoleSender(), cmd);
        } catch (Throwable t) {
            hc.plugin().getLogger().warning("[Calamity] Falló el comando de contrato \"" + cmd + "\": " + t);
            return false;
        }
    }

    private void telemetria(OfflinePlayer p, Def d, String estado, int e, long mc) {
        Telemetria t = hc.telemetria();
        if (t == null) return;
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("id", d.id());
        campos.put("estado", estado);
        campos.put("corto", d.corto());
        if (e > 0) campos.put("esencias", e);
        if (mc > 0) campos.put("mc", mc);
        hc.seguro("telemetria", () -> t.suceso("contrato", p, campos));
    }

    // ------------------------------------------------------------------ la barra (1.10)

    /**
     * Lo que Cordura pinta a la derecha de su barra cada segundo: un contrato o null. Solo lee memoria y
     * la libreta; la eleccion (enBarra) se hace cuando algo cambia (refrescarBarra).
     */
    private Component sufijoBarra(Player p) {
        if (!activo()) return null;
        String modo = modoBarra();
        if (modo.equals("nunca")) return null;
        UUID u = p.getUniqueId();
        Integer i = enBarra.get(u);
        if (i == null) return null;
        ConfigurationSection s = hc.datos().getConfigurationSection("contratos." + u);
        if (s == null) return null;
        if (modo.equals("avance")) {
            // Solo el que acaba de avanzar, y solo unos segundos.
            Long hasta = avanceHasta.get(u);
            if (hasta == null || System.currentTimeMillis() > hasta || !i.equals(ultimo(s))) return null;
        }
        String r = "lista." + i;
        if (s.getBoolean(r + ".cumplido", false) || s.getBoolean(r + ".cobrado", false)) return null;
        Def d = pool().get(s.getString(r + ".id", ""));
        return d == null ? null : sufijo(d, s.getInt(r + ".progreso", 0));
    }

    /** Vuelve a elegir el contrato de la barra con los pergaminos que lleva (lleva: sus huecos). */
    private void refrescarBarra(Player p, ConfigurationSection s, Set<Integer> lleva) {
        Integer i = elegirBarra(estadosDe(s, pool()), ultimo(s), lleva);
        if (i == null) enBarra.remove(p.getUniqueId());
        else enBarra.put(p.getUniqueId(), i);
    }

    /** Lo mismo mirando su inventario (y borrando lo inerte que vea). */
    private void refrescarBarra(Player p) {
        UUID u = p.getUniqueId();
        ConfigurationSection s = hc.datos().getConfigurationSection("contratos." + u);
        if (!activo() || !pergaminoActivo() || !hc.esHardcore(p) || s == null) {
            enBarra.remove(u);
            return;
        }
        refrescarBarra(p, s, pergaminos.revisar(p, s).keySet());
    }

    /** Un tick despues (Pergaminos.alTirar): para entonces el pergamino ya no esta en ninguna via. */
    void refrescarBarraLuego(Player p) {
        luego(p, 1L, () -> refrescarBarra(p));
    }

    // ------------------------------------------------------------------ placeholders (1.10)

    /**
     * %lethalworld_contrato_<n>_texto|progreso|objetivo|estado|premio%, n = el hueco (1-3). Vacio si ese
     * hueco no tiene contrato o, fuera de Calamity, si la libreta aun es de otro dia (la de hoy se sortea
     * al mirarla: mejor vacio que la de ayer). PlaceholderAPI puede preguntar desde otro hilo: solo lee,
     * sin sorteo ni escrituras. Null (no es nuestro) si el campo no existe.
     */
    private String placeholder(OfflinePlayer j, String resto) {
        if (resto == null) return null;
        String[] partes = resto.toLowerCase(Locale.ROOT).split("_", 2);
        if (partes.length != 2 || !CAMPOS.contains(partes[1])) return null;
        int n;
        try {
            n = Integer.parseInt(partes[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (j == null || !activo()) return "";
        ConfigurationSection s = hc.datos().getConfigurationSection("contratos." + j.getUniqueId());
        if (s == null) return "";
        Player p = j.getPlayer();
        if ((p == null || !hc.esHardcore(p)) && !hoy().equals(s.getString("dia", ""))) return "";
        return campo(s, poolParaLeer(), n, partes[1]);
    }

    // ------------------------------------------------------------------ jugador

    /** /calamity contratos (en el menu, la fila del Tasador): los tres de hoy y como van. */
    void mostrar(CommandSender a, Player p) {
        if (!activo()) {
            a.sendMessage(ComandoCalamity.mensaje("Oren no tiene contratos ahora mismo."));
            return;
        }
        UUID u = p.getUniqueId();
        boolean dentro = hc.esHardcore(p);
        ConfigurationSection s = libreta(u, !dentro);
        Map<String, Def> pool = pool();
        boolean papel = pergaminoActivo();
        Set<Integer> lleva = papel && dentro ? pergaminos.validos(p, s) : Set.of();
        a.sendMessage(ComandoCalamity.mensaje(papel ? "Tus contratos de hoy:" : "Tus contratos de hoy (los cobras al salir vivo):"));
        for (int i : huecos(s)) {
            String r = "lista." + i;
            Def d = pool.get(s.getString(r + ".id", ""));
            if (d == null) continue;
            boolean cobrado = s.getBoolean(r + ".cobrado", false), cumplido = s.getBoolean(r + ".cumplido", false);
            Component estado = cobrado ? Component.text("cobrado", VERDE_PALIDO)
                    : cumplido ? Component.text("cumplido: lo cobras al salir", AMBAR)
                    : Component.text(s.getInt(r + ".progreso", 0) + "/" + d.objetivo(), Paleta.CIFRA);
            Component linea = Component.text("  " + i + ". ", Paleta.TENUE)
                    .append(Component.text(d.texto(), cobrado ? Paleta.TENUE : Paleta.TEXTO))
                    .append(Component.text(" · ", Paleta.SEPARADOR)).append(estado)
                    .append(Component.text(" · " + d.esencias() + " E + " + Altar.miles(d.mobcoins()) + " MC"
                            + (d.corto() ? " · corto" : ""), Paleta.TENUE));
            // 1.10: dentro, el que no avanza porque le falta el papel lo dice.
            if (papel && dentro && !cobrado && !cumplido && !lleva.contains(i)) {
                linea = linea.append(Component.text(" · sin pergamino", Paleta.AVISO));
            }
            a.sendMessage(linea);
        }
        if (papel) {
            a.sendMessage(Component.text("  Cada contrato es un pergamino de Oren: llévalo encima en Calamity para que cuente.",
                    Paleta.TENUE));
        }
        int gratis = Math.max(0, hc.cfg().getInt("contratos.cambios-gratis", 1) - s.getInt("cambios", 0));
        int precio = Math.max(0, hc.cfg().getInt("contratos.precio-cambio", 1));
        a.sendMessage(Component.text("  " + (gratis == 1 ? "Hoy te queda 1 cambio gratis"
                : gratis > 1 ? "Hoy te quedan " + gratis + " cambios gratis"
                : "Cambiar uno cuesta " + precio + (precio == 1 ? " Esencia" : " Esencias"))
                + " (/calamity cambiar <1-3>).", Paleta.TENUE));
        int objetivo = Math.max(1, hc.cfg().getInt("contratos.semana-objetivo", 12));
        int hechos = semana().equals(s.getString("semana", "")) ? s.getInt("cobrados-semana", 0) : 0;
        a.sendMessage(Component.text("  Esta semana llevas " + Math.min(hechos, objetivo) + " de " + objetivo
                + " contratos cobrados para la Llave del Caos.", Paleta.TENUE));
    }

    /** Un contrato de hoy tal y como lo pinta el menu del Tasador. */
    record Estado(int hueco, Def def, int progreso, boolean cumplido, boolean cobrado) {
    }

    /**
     * Lo mismo que mostrar() escribe en el chat, para el menu del Tasador: los contratos de hoy
     * (con el mismo sorteo: fuera de Calamity, si la libreta es de otro dia), sin pagar nada.
     */
    List<Estado> estados(Player p) {
        return estadosDe(libreta(p.getUniqueId(), !hc.esHardcore(p)), pool());
    }

    /** 1.10: los huecos cuyo pergamino lleva, para el menu de Oren (solo mira). Vacio fuera o sin pergaminos. */
    Set<Integer> llevados(Player p) {
        if (!pergaminoActivo() || !hc.esHardcore(p)) return Set.of();
        ConfigurationSection s = hc.datos().getConfigurationSection("contratos." + p.getUniqueId());
        return s == null ? Set.of() : pergaminos.validos(p, s);
    }

    /**
     * 1.10 · El clic en un contrato del menu de Oren: le da su pergamino si no lo lleva. Solo dentro de
     * Calamity. Null si se lo dio; si no, por que no (para el chat).
     */
    String darDesdeMenu(Player p, int hueco) {
        if (!activo() || !pergaminoActivo()) return "Oren no tiene contratos ahora mismo.";
        if (!hc.esHardcore(p)) return "Los pergaminos se entregan en Calamity.";
        ConfigurationSection s = libreta(p.getUniqueId(), false);
        String r = "lista." + hueco;
        Def d = pool().get(s.getString(r + ".id", ""));
        if (d == null || s.getBoolean(r + ".cumplido", false) || s.getBoolean(r + ".cobrado", false)) {
            return "Ese contrato ya no está pendiente.";
        }
        Map<Integer, Integer> lleva = pergaminos.revisar(p, s);
        if (lleva.containsKey(hueco)) return "Ya llevas ese pergamino.";
        if (!pergaminos.dar(p, new Pergaminos.Sello(p.getUniqueId(), s.getString("dia", ""), hueco, d.id()), d,
                s.getInt(r + ".progreso", 0))) {
            return "No tienes espacio en el inventario.";
        }
        hc.plugin().bitacora().anotar("contrato", "pergamino", p.getName(), d.id());
        Set<Integer> tiene = new HashSet<>(lleva.keySet());
        tiene.add(hueco);
        refrescarBarra(p, s, tiene);
        return null;
    }

    /** Cambios gratis que le quedan hoy (los de la libreta ya sorteada). */
    int cambiosGratis(UUID u) {
        return Math.max(0, hc.cfg().getInt("contratos.cambios-gratis", 1) - seccion(u).getInt("cambios", 0));
    }

    /** Lo que cuesta cambiar uno ahora mismo: 0 mientras quede cambio gratis (como en cambiar()). */
    int precioCambio(UUID u) {
        return cambiosGratis(u) > 0 ? 0 : Math.max(0, hc.cfg().getInt("contratos.precio-cambio", 1));
    }

    /** {cobrados esta semana, los que pide el premio de la semana}. */
    int[] semanaDe(UUID u) {
        ConfigurationSection s = seccion(u);
        int objetivo = Math.max(1, hc.cfg().getInt("contratos.semana-objetivo", 12));
        int hechos = semana().equals(s.getString("semana", "")) ? s.getInt("cobrados-semana", 0) : 0;
        return new int[]{hechos, objetivo};
    }

    /**
     * /calamity cambiar <1-3> (y el boton Cambiar contrato del Tasador). True si se cambio. Dentro de
     * Calamity y con pergaminos (1.10), el viejo deja de valer y, si lo llevaba, Oren le da el nuevo.
     */
    boolean cambiar(Player p, int i) {
        if (!activo()) {
            p.sendMessage(ComandoCalamity.mensaje("Oren no tiene contratos ahora mismo."));
            return false;
        }
        UUID u = p.getUniqueId();
        ConfigurationSection s = libreta(u, !hc.esHardcore(p));
        String r = "lista." + i;
        if (!s.isSet(r + ".id")) {
            p.sendMessage(ComandoCalamity.mensaje("Uso: /calamity cambiar <1-3>"));
            return false;
        }
        if (s.getBoolean(r + ".cobrado", false)) {
            p.sendMessage(ComandoCalamity.mensaje("Ese contrato ya está cobrado."));
            return false;
        }
        if (s.getBoolean(r + ".cumplido", false)) {
            p.sendMessage(ComandoCalamity.mensaje("Ese contrato ya está cumplido: lo cobras al salir vivo."));
            return false;
        }
        Map<String, Def> pool = pool();
        Set<String> ya = new HashSet<>();
        int cortosOtros = 0;
        for (int k : huecos(s)) {
            String id = s.getString("lista." + k + ".id", "");
            ya.add(id);
            Def d = pool.get(id);
            if (k != i && d != null && d.corto()) cortosOtros++;
        }
        boolean corto = cortosOtros < Math.max(0, hc.cfg().getInt("contratos.cortos-garantizados", 1));
        Def nuevo = sustituto(pool.values(), ya, corto, this::disponible, azar);
        if (nuevo == null) {
            p.sendMessage(ComandoCalamity.mensaje("Oren no tiene otro contrato que darte en su lugar."));
            return false;
        }
        int cambios = s.getInt("cambios", 0);
        int precio = cambios < Math.max(0, hc.cfg().getInt("contratos.cambios-gratis", 1))
                ? 0 : Math.max(0, hc.cfg().getInt("contratos.precio-cambio", 1));
        if (precio > 0) {
            Saldo sal = hc.saldo();
            if (sal == null || !sal.restar(u, precio, "contrato:cambio")) {
                p.sendMessage(ComandoCalamity.mensaje("Cambiarlo cuesta " + precio
                        + (precio == 1 ? " Esencia" : " Esencias") + " y no te llega el saldo."));
                return false;
            }
        }
        boolean papel = pergaminoActivo() && hc.esHardcore(p);
        boolean loLlevaba = papel && pergaminos.validos(p, s).contains(i);
        String viejo = s.getString(r + ".id", "?");
        ponerEn(s, i, nuevo.id());
        s.set("cambios", cambios + 1);
        if (Objects.equals(ultimo(s), i)) s.set("ultimo", null);
        hc.marcarSucio();
        hc.plugin().bitacora().anotar("contrato", "cambio", p.getName(), viejo + " -> " + nuevo.id(), "coste " + precio);
        telemetria(p, nuevo, "cambiado", 0, 0);
        p.sendMessage(ComandoCalamity.mensaje(Component.text("Contrato nuevo: ")
                .append(Component.text(nuevo.texto(), Paleta.DETALLE)).append(Component.text("."))));
        if (papel) {
            // El viejo ya no cuadra con la libreta (otro contrato en su hueco): revisar lo borra.
            Map<Integer, Integer> lleva = pergaminos.revisar(p, s);
            Set<Integer> tiene = new HashSet<>(lleva.keySet());
            if (loLlevaba) {
                if (pergaminos.dar(p, new Pergaminos.Sello(u, s.getString("dia", ""), i, nuevo.id()), nuevo, 0)) tiene.add(i);
                else p.sendMessage(ComandoCalamity.mensaje("No tienes espacio para su pergamino. Pídeselo a Oren."));
            }
            refrescarBarra(p, s, tiene);
        }
        return true;
    }

    // ------------------------------------------------------------------ admin

    private void comandoAdmin(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(ComandoCalamity.mensaje("Uso: /calamidad contratos <jugador> [reset]"));
            return;
        }
        OfflinePlayer o = Entregas.buscar(args[1]);
        if (o == null) {
            quien.sendMessage(ComandoCalamity.mensaje("No encuentro a ese jugador."));
            return;
        }
        UUID u = o.getUniqueId();
        Player online = o.getPlayer();
        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            // El dia vuelve a empezar (sorteo y cambios); lo de la semana se queda.
            ConfigurationSection s = seccion(u);
            s.set("lista", null);
            s.set("dia", null);
            s.set("cambios", null);
            s.set("base", null);
            s.set("avisado", null);
            s.set("ultimo", null);
            s.set("sin-pergamino", null);
            hc.marcarSucio();
            hc.plugin().bitacora().anotar("contrato", "reset", Entregas.nombre(o), "admin");
            // 1.10: dentro, sus pergaminos viejos ya no valen; se le dan los del sorteo nuevo.
            if (online != null && hc.esHardcore(online)) hc.seguro("contratos", () -> alVolver(online));
        }
        if (online != null) {
            mostrar(quien, online);
            return;
        }
        ConfigurationSection s = libreta(u, true);
        Map<String, Def> pool = pool();
        quien.sendMessage(ComandoCalamity.mensaje("Contratos de " + Entregas.nombre(o) + " (" + s.getString("dia", "?") + "):"));
        for (int i : huecos(s)) {
            String r = "lista." + i;
            Def d = pool.get(s.getString(r + ".id", ""));
            quien.sendMessage(Component.text("  " + i + ". " + s.getString(r + ".id", "?")
                    + (d == null ? "" : " · " + s.getInt(r + ".progreso", 0) + "/" + d.objetivo())
                    + (s.getBoolean(r + ".cumplido", false) ? " · cumplido" : "")
                    + (s.getBoolean(r + ".cobrado", false) ? " · cobrado" : ""), Paleta.TENUE));
        }
    }

    void parar() {
        HandlerList.unregisterAll(this);
        hc.seguro("contratos", pergaminos::parar);
        hc.cordura().extra(null);
        for (BukkitTask t : tareas) t.cancel();
        tareas.clear();
        reloj.clear();
        ultimoDestello.clear();
        enBarra.clear();
        avanceHasta.clear();
    }

    // ------------------------------------------------------------------ autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        Map<String, Def> pool = pool();
        SecureRandom r = new SecureRandom();

        boolean siempreCorto = true, tres = true, distintos = true;
        for (int k = 0; k < 300; k++) {
            List<Def> l = sortear(pool.values(), 3, 1, d -> true, r);
            if (l.size() != 3) tres = false;
            if (l.stream().noneMatch(Def::corto)) siempreCorto = false;
            if (l.stream().map(Def::id).distinct().count() != l.size()) distintos = false;
        }
        h.ok("300 sorteos: siempre 3", tres);
        h.ok("300 sorteos: siempre al menos un corto", siempreCorto);
        h.ok("300 sorteos: sin repetir", distintos);

        // Mecanica apagada: eco.activo en false -> ni "eco" ni "redimir".
        Predicate<Def> sinEco = d -> !"eco.activo".equals(d.mecanica());
        boolean salioEco = false, cortoSinEco = true;
        for (int k = 0; k < 300; k++) {
            List<Def> l = sortear(pool.values(), 3, 1, sinEco, r);
            for (Def d : l) if (d.id().equals("eco") || d.id().equals("redimir")) salioEco = true;
            if (l.stream().noneMatch(Def::corto)) cortoSinEco = false;
        }
        h.ok("con eco.activo apagado no sale ni eco ni redimir", !salioEco);
        h.ok("con eco.activo apagado sigue habiendo un corto", cortoSinEco);
        Predicate<Def> sinCofres = d -> !"cofre".equals(d.evento());
        boolean salioCofre = false;
        for (int k = 0; k < 300; k++) for (Def d : sortear(pool.values(), 3, 1, sinCofres, r)) if (d.evento().equals("cofre")) salioCofre = true;
        h.ok("con los cofres vacios no sale ningun contrato de cofres", !salioCofre);
        h.igual("pool de serie: 12 contratos", 12, POR_DEFECTO.size());
        h.igual("pool de serie: 4 cortos", 4L, POR_DEFECTO.stream().filter(Def::corto).count());

        // La libreta en memoria: el progreso no paga nada; solo cobrables() lo sabe.
        YamlConfiguration yml = new YamlConfiguration();
        ConfigurationSection s = yml.createSection("x");
        Map<String, Def> base = new LinkedHashMap<>();
        for (Def d : POR_DEFECTO) base.put(d.id(), d);
        escribirLista(s, "2026-09-26", List.of(base.get("corto-mobs"), base.get("extraer-ii"), base.get("sin-frasco")));
        avanzar(s, base, "mob", 9);
        h.igual("9 mobs: progreso 9", 9, s.getInt("lista.1.progreso"));
        h.ok("9 mobs: no cumplido", !s.getBoolean("lista.1.cumplido"));
        Avance a = avanzar(s, base, "mob", 5);
        h.igual("14 mobs: se queda en 10", 10, s.getInt("lista.1.progreso"));
        h.igual("14 mobs: cumplido el 1", List.of(1), a.cumplidos());
        h.ok("avanzar no cobra: cumplido no es cobrado", !s.getBoolean("lista.1.cobrado"));
        avanzar(s, base, "tasa-ii", 3);
        h.ok("tasa-ii cuenta como reliquia-ii", s.getBoolean("lista.2.cumplido"));
        h.igual("dos cobrables", List.of(1, 2), cobrables(s));
        reiniciarExpedicion(s);
        h.igual("morir: nada cobrable", List.of(), cobrables(s));
        h.igual("morir: progreso a cero", 0, s.getInt("lista.1.progreso"));
        avanzar(s, base, "mob", 10);
        for (int i : cobrables(s)) s.set("lista." + i + ".cobrado", true);
        reiniciarExpedicion(s);
        h.ok("lo cobrado sobrevive a la siguiente expedicion", s.getBoolean("lista.1.cobrado"));
        h.ok("lo cobrado ya no avanza", avanzar(s, base, "mob", 5).cambiados().isEmpty());
        avanzar(s, base, "minutos-sin-frasco", 20);
        h.igual("beber: romper dice cual llevaba algo", List.of(3), romper(s, base, "minutos-sin-frasco"));
        h.igual("beber pone a cero el sin frasco", 0, s.getInt("lista.3.progreso"));

        Def otro = sustituto(base.values(), Set.of("corto-mobs", "extraer-ii", "sin-frasco"), true, d -> true, r);
        h.ok("cambiar el unico corto da otro corto", otro != null && otro.corto() && !otro.id().equals("corto-mobs"));

        probarPergaminos(h, base);

        // El objeto de verdad (con el servidor): marca, nombre, lore y que no se apila.
        Def mobs = base.get("corto-mobs");
        Pergaminos.Sello sello = new Pergaminos.Sello(Autotest.sintetico(61), "2026-09-26", 1, "corto-mobs");
        ItemStack papel = Pergaminos.crear(sello, mobs, 6);
        ItemMeta meta = papel.getItemMeta();
        h.ok("pergamino: se reconoce por la marca", Pergaminos.es(papel));
        h.igual("pergamino: lleva su sello", sello, Pergaminos.sello(papel));
        h.igual("pergamino: el nombre", "Contrato · Mata 10 mobs", meta == null ? null : plano(meta.displayName()));
        h.ok("pergamino: nombre sin cursiva ni negrita", meta != null && meta.displayName() != null
                && meta.displayName().decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE
                && meta.displayName().decoration(TextDecoration.BOLD) == TextDecoration.State.FALSE);
        h.igual("pergamino: el lore del objeto es el de lineas()", Pergaminos.lineas(mobs, 6),
                meta == null || meta.lore() == null ? null : planos(meta.lore()));
        h.igual("pergamino: no se apila", 1, papel.getMaxStackSize());
        h.ok("un papel cualquiera no es un pergamino", !Pergaminos.es(new ItemStack(Material.PAPER)));
        h.ok("barra: el modo es uno de los tres", MODOS_BARRA.contains(modoBarra()));

        h.ok("autotest no toca contratos reales", !hc.datos().isSet("contratos." + Autotest.sintetico(1))
                && !hc.datos().isSet("contratos." + Autotest.sintetico(61)));
        h.ok("/calamity contratos registrado", Subcomandos.calamity().nombres(null).contains("contratos"));
        h.igual("placeholder sin jugador: vacio", "", PlaceholdersLethal.resolver(null, "contrato_1_texto"));
        h.igual("placeholder con un campo que no existe: no es nuestro", null, PlaceholdersLethal.resolver(null, "contrato_1_nada"));
        return h.lineas();
    }

    /**
     * 1.10, sin servidor: el lore del pergamino, la barra, la etiqueta, que sin pergamino no avanza, que lo
     * cumplido dentro queda cobrado, pide sus Esencias a la mano y no vuelve a pagar, que la Tasacion paga
     * al saldo, y los placeholders. Todo en un yml en memoria.
     */
    static void probarPergaminos(Autotest.Hoja h, Map<String, Def> base) {
        Def mobs = base.get("corto-mobs"), reliquia = base.get("corto-reliquia");
        String raya = "─".repeat(Pergaminos.FILETE_MINIMO);
        List<String> esperadas = List.of(raya, "Objetivo: Mata 10 mobs", "Progreso: ▮▮▮▮▮▮▯▯▯▯ 6/10",
                "Premio: 2 Esencias y 20 MobCoins", raya, "Al cumplirlo recibes el premio en la mano.",
                "El progreso es de esta expedición.");
        h.igual("pergamino: lore a 6/10", esperadas, Pergaminos.lineas(mobs, 6));
        h.igual("pergamino: a 0/10 la barra vacia", "Progreso: ▯▯▯▯▯▯▯▯▯▯ 0/10", Pergaminos.lineas(mobs, 0).get(2));
        h.igual("pergamino: el progreso no pasa del objetivo", "Progreso: ▮▮▮▮▮▮▮▮▮▮ 10/10", Pergaminos.lineas(mobs, 14).get(2));
        h.igual("pergamino: 1 de 30 ya pinta una casilla", 1, Pergaminos.llenas(1, 30));
        h.igual("pergamino: 29 de 30 aun no llena la barra", 9, Pergaminos.llenas(29, 30));
        h.igual("pergamino: lo pintado dice lo mismo que lo plano", esperadas, planos(Pergaminos.lore(mobs, 6)));
        boolean sinCursiva = true;
        for (Component c : Pergaminos.lore(mobs, 6)) sinCursiva &= c.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE;
        h.ok("pergamino: lore sin cursiva", sinCursiva);
        List<String> rl = Pergaminos.lineas(reliquia, 0);
        h.igual("pergamino de Reliquias: se cobra al salir", "Se cobra al salir vivo.", rl.get(5));
        boolean enmarca = rl.get(0).length() > Pergaminos.FILETE_MINIMO
                && Marco.ancho(rl.get(0), false) >= Marco.ancho(Pergaminos.titulo(reliquia), false);
        for (String l : rl) enmarca &= Marco.ancho(l, false) <= Marco.ancho(rl.get(0), false);
        h.ok("pergamino largo: el filete crece y nada pasa de el (ni el nombre)", enmarca);

        UUID yo = Autotest.sintetico(61), otro = Autotest.sintetico(62);
        Pergaminos.Sello sello = new Pergaminos.Sello(yo, "2026-09-26", 1, "corto-mobs");
        h.igual("sello: ida y vuelta", sello, Pergaminos.Sello.de(sello.texto()));
        h.igual("sello roto: null", null, Pergaminos.Sello.de("no;es;un;sello"));
        h.igual("sello incompleto: null", null, Pergaminos.Sello.de("abc"));

        YamlConfiguration yml = new YamlConfiguration();
        ConfigurationSection s = yml.createSection("p");
        escribirLista(s, "2026-09-26", List.of(mobs, base.get("extraer-ii"), base.get("sin-frasco")));
        h.igual("pergamino suyo y de hoy: vale", null, Pergaminos.motivoInerte(sello, yo, s));
        h.igual("pergamino de otro: inerte", "ajeno", Pergaminos.motivoInerte(sello, otro, s));
        h.igual("pergamino de otro dia: inerte", "dia",
                Pergaminos.motivoInerte(new Pergaminos.Sello(yo, "2026-09-25", 1, "corto-mobs"), yo, s));
        h.igual("pergamino de un contrato que ya no esta en su hueco: inerte", "hueco",
                Pergaminos.motivoInerte(new Pergaminos.Sello(yo, "2026-09-26", 2, "corto-mobs"), yo, s));

        h.igual("pendientes de mob: el 1", List.of(1), pendientesDe(s, base, "mob"));
        h.ok("sin su pergamino no avanza", avanzar(s, base, "mob", 5, Set.of()).cambiados().isEmpty()
                && s.getInt("lista.1.progreso") == 0);
        List<Estado> est = estadosDe(s, base);
        h.igual("barra: sin pergaminos, nada", null, elegirBarra(est, null, Set.of()));
        h.igual("barra: sin avance, el primero que lleva", 1, elegirBarra(est, null, Set.of(1, 3)));
        h.igual("barra: sin avance ni el 1, el siguiente que lleva", 3, elegirBarra(est, null, Set.of(3)));
        avanzar(s, base, "minutos-sin-frasco", 4, Set.of(3));
        h.igual("barra: el ultimo que avanzo", 3, elegirBarra(estadosDe(s, base), 3, Set.of(1, 3)));
        h.igual("barra: el ultimo que avanzo sin su pergamino: el primero que lleva", 1,
                elegirBarra(estadosDe(s, base), 3, Set.of(1)));
        h.igual("barra: lo que se pega a la cordura", "   ·   Mobs 6/10", plano(sufijo(mobs, 6)));

        Avance a = avanzar(s, base, "mob", 10, Set.of(1));
        h.igual("10 mobs con su pergamino: cumplido el 1", List.of(1), a.cumplidos());
        h.igual("barra: lo cumplido deja paso al siguiente que lleva", 3, elegirBarra(estadosDe(s, base), 1, Set.of(1, 3)));
        // El cobro dentro (lo que hace cobrar() con alMomento): un pago, con las Esencias a la mano.
        List<Cobro> dentro = planCobro(s, base, a.cumplidos(), true, "2026-W39");
        h.igual("cumplido dentro: un pago, el del hueco 1, 2 Esencias y 20 MobCoins", List.of(new Cobro(1, "corto-mobs", 2, 20, true)),
                dentro);
        h.ok("cumplido dentro: pide las Esencias como objeto y la Aduana se las da en la mano",
                !dentro.isEmpty() && dentro.get(0).enLaMano() && Aduana.comoObjeto(true, true, "contratos", dentro.get(0).enLaMano(), true));
        h.ok("cumplido dentro: cumplido y cobrado", s.getBoolean("lista.1.cumplido") && s.getBoolean("lista.1.cobrado"));
        h.igual("cumplido dentro: suma a la semana", 1, s.getInt("cobrados-semana"));
        h.igual("cumplido dentro: otra vez no paga nada", List.of(), planCobro(s, base, List.of(1, 1), true, "2026-W39"));
        h.igual("cumplido dentro: la Tasacion ya no lo cobra", List.of(), cobrables(s));
        h.igual("cumplido dentro: su pergamino queda inerte", "cobrado", Pergaminos.motivoInerte(sello, yo, s));
        reiniciarExpedicion(s);
        h.ok("tras morir sigue cobrado", s.getBoolean("lista.1.cobrado") && s.getInt("lista.1.progreso") == 10);
        h.ok("tras morir no vuelve a avanzar ni a pagar", avanzar(s, base, "mob", 20, Set.of(1)).cambiados().isEmpty()
                && cobrables(s).isEmpty() && pendientesDe(s, base, "mob").isEmpty());
        h.ok("las de Reliquias se cobran al salir; el resto, al cumplirlas",
                seCobraAlSalir(reliquia) && seCobraAlSalir(base.get("extraer-ii")) && !seCobraAlSalir(mobs) && !seCobraAlSalir(base.get("minijefe")));

        h.igual("etiqueta: la de serie por id", "Mobs", etiquetaPorDefecto("corto-mobs", "otra cosa"));
        h.igual("etiqueta: sin serie, dos palabras", "Caza tres", etiquetaPorDefecto("nuevo", "Caza tres arañas grandes"));
        h.igual("etiqueta: una palabra", "Pesca", etiquetaPorDefecto("nuevo", "Pesca"));
        boolean etiquetas = true, sinSalir = true;
        for (Def d : POR_DEFECTO) {
            etiquetas &= d.etiqueta() != null && !d.etiqueta().isBlank();
            sinSalir &= !d.texto().endsWith(" y sal");
        }
        h.ok("las de serie traen etiqueta", etiquetas);
        h.ok("ningun texto de serie pide salir", sinSalir);
        h.igual("premio de serie", "6 Esencias y 100 MobCoins", premio(base.get("minijefe")));

        h.igual("placeholder texto", "Mata 10 mobs", campo(s, base, 1, "texto"));
        h.igual("placeholder progreso", "10", campo(s, base, 1, "progreso"));
        h.igual("placeholder objetivo", "10", campo(s, base, 1, "objetivo"));
        h.igual("placeholder estado cobrado", "cobrado", campo(s, base, 1, "estado"));
        h.igual("placeholder estado pendiente", "pendiente", campo(s, base, 2, "estado"));
        h.igual("placeholder premio", "2 Esencias y 20 MobCoins", campo(s, base, 1, "premio"));
        h.igual("placeholder de un hueco vacio", "", campo(s, base, 4, "texto"));

        // La Tasacion (cobrarEnTasacion): las Reliquias se cumplen al salir y se cobran al saldo.
        avanzar(s, base, "tasa-ii", 3, Set.of(2));
        List<Cobro> fuera = planCobro(s, base, cobrables(s), false, "2026-W39");
        h.igual("Tasacion: un pago, el de las Reliquias", List.of(new Cobro(2, "extraer-ii", 4, 60, false)), fuera);
        h.ok("Tasacion: sin pedir objeto, la Aduana lo manda al saldo",
                !fuera.isEmpty() && !fuera.get(0).enLaMano() && !Aduana.comoObjeto(true, true, "contratos", fuera.get(0).enLaMano(), true));
        h.igual("Tasacion: la semana suma el segundo", 2, s.getInt("cobrados-semana"));
    }

    private static String plano(Component c) {
        return c == null ? null : PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static List<String> planos(List<Component> l) {
        List<String> out = new ArrayList<>();
        for (Component c : l) out.add(plano(c));
        return out;
    }
}
