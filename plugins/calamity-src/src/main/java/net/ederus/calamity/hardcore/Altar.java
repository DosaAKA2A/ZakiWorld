package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * M2/M32 · El Altar del Umbral (DIS M2 [alineado], PLAN sec. 4): el unico sitio donde las
 * Esencias se vuelven cosas. Un bloque fuera de Calamity, marcado con /calamity altar;
 * clic derecho abre dos paginas de 54 (Umbral y Forja, MenuAltar). Dentro de Calamity el
 * altar no escucha (P-M05).
 *
 * Cobra del SALDO de Esencias (Saldo), de los creditos (Creditos: Sellos, Errantes, Marcas,
 * Fragmentos) y de las MobCoins (Monedero; sin verificar en produccion, los trueques con MC
 * salen en gris "Proximamente"). La regla que no se negocia: se paga primero y se entrega
 * despues, y si la entrega falla se devuelve TODO. El orden esta pensado para que devolver
 * MobCoins sea lo mas raro posible (es lo unico que no se puede deshacer limpio):
 *   1. se revisa todo sin tocar nada (cupo, stock, requisito, credito, Esencias, MC);
 *   2. se cobran Esencias y creditos;
 *   3. se comprueba que el objeto se puede CREAR (sin MMOItems, una pieza del Manto no
 *      existe): si no, se devuelve y las MC ni se tocan;
 *   4. se cobran las MC (en modo real, a los 2 ticks tras releer el saldo);
 *   5. se entrega por Entregas (ligado, pendientes, Bitacora). Si falla, se devuelve todo.
 * El tope de 4 Llaves del Caos se mira en el paso 1: con el tope lleno no se cobra nada.
 * Un trueque puede pedir ademas ENTREGAR objetos que lleve encima (entregar): piezas de la Forja
 * (la Crimson Masamune pide la Masamune) y Fragmentos de Masamune, cada uno con su cantidad. Se
 * mira en el paso 1 que los lleve todos, se le quitan en el paso 2 con las Esencias y los
 * creditos, y si algo falla despues se le devuelven con todo lo demas.
 *
 * El motor (revisar/comprar) no toca Bukkit: trabaja contra una Caja, que en el juego son
 * los modulos reales y en el autotest un yml en memoria. Lo que cuenta el jugador por
 * semana va en altar.usos.<semana>.<uuid>.<id> (y altar.usos-dia, altar.stock), en la
 * semana de hardcore.zona (Calendario).
 */
final class Altar implements Listener {

    static final TextColor NARANJA = TextColor.color(0xE8903C);
    static final TextColor VERDE = TextColor.color(0x9FD6A0);
    static final TextColor AMBAR = TextColor.color(0xE8A33D);

    /** Lo que hace cada trueque al comprarlo: da frasco|cristal|dar:<objeto>|forja:<pieza>|ofrenda, o un servicio. */
    static final Set<String> SERVICIOS = Set.of("recargar", "depositar", "camino");

    /**
     * Un trueque de altar.trueques (DIS sec. 4).
     *
     * @param incremento Esencias de mas por cada compra de la semana (la Ascua: 24, 32, 40...)
     * @param credito    tipo de credito que pide (sello:<id>, marca, fragmento) o null
     * @param esperaDias la Guadana: una nueva cada tantos dias (la reposicion no espera)
     * @param entregar   lo que hay que llevar encima y se entrega al forjarla (la Masamune pide 5
     *                   Fragmentos de Masamune; la Crimson, la Masamune y 5 Fragmentos). Vacia si nada
     */
    record Trueque(String id, String pagina, Material icono, String nombre, List<String> lore, int esencias,
                   int incremento, long mobcoins, String credito, int creditos, String da, int cantidad,
                   int limiteSemana, int limiteDia, int stock, String requisito, boolean conReposicion,
                   int reposEsencias, long reposMc, int esperaDias, List<Entrega> entregar) {

        Trueque {
            entregar = entregar == null ? List.of() : List.copyOf(entregar);
        }

        /** Cuantos de ese objeto pide entregar (0 si ninguno). */
        int pide(String objeto) {
            for (Entrega e : entregar) if (e.objeto().equals(objeto)) return e.cantidad();
            return 0;
        }

        /** Lo que se le pide a Entregas ("frasco", "tintura", "forja:yelmo", "ofrenda"), o null si es un servicio. */
        String objeto() {
            if (SERVICIOS.contains(da)) return null;
            if (da.startsWith("dar:")) return da.substring(4);
            return da;
        }

        /** La pieza de la Forja (yelmo, hacha, guadana...) o null. */
        String pieza() {
            return da.startsWith("forja:") ? da.substring(6) : null;
        }

        boolean servicio() {
            return SERVICIOS.contains(da);
        }
    }

    /**
     * Algo que un trueque pide entregar: una pieza de la Forja ("masamune") o un objeto de Calamity
     * ("fragmento-masamune"), y cuantos.
     */
    record Entrega(String objeto, int cantidad) {
    }

    /** Lo que falta de una Entrega (el "faltan" del motivo "objeto"): cuantos lleva y cuantos pide. */
    record Falta(String objeto, int tiene, int pide) {

        int faltan() {
            return Math.max(0, pide - tiene);
        }

        @Override
        public String toString() {
            return objeto + " " + tiene + "/" + pide;
        }
    }

    /** Lo que cuesta ahora (con la subida de la Ascua y la reposicion ya aplicadas). */
    record Precio(int esencias, long mc, String credito, int creditos, boolean reposicion) {
    }

    /** La revision: motivo null = se puede; creditoUsado = el que se gastaria (el Sello o un Errante). */
    record Plan(Precio precio, String creditoUsado, String motivo, Object faltan) {
    }

    /**
     * Como acabo un trueque. ok: cobrado y entregado. devuelto: se cobro y se devolvio (motivo
     * creacion|mc|entrega). Ni uno ni otro: rechazado antes de cobrar (motivo esencias, mc,
     * credito, cupo, stock, requisito; MED sec. 3.3).
     */
    record Resultado(Trueque t, boolean ok, boolean devuelto, String motivo, Object faltan, Precio precio,
                     String creditoUsado) {
    }

    /** Lo que el motor necesita del resto del plugin. En el autotest, todo en memoria. */
    interface Caja {
        ConfigurationSection datos();

        Saldo saldo();

        Creditos creditos();

        String semana();

        String dia();

        long ahora();

        int reposicionDias();

        boolean salvoconductoActivo();

        boolean permiso(UUID u, String permiso);

        int llavesLibres(UUID u);

        boolean mcDisponible();

        /** MobCoins que tiene; -1 si no se puede saber. */
        long mc(UUID u);

        void cobrarMc(UUID u, long n, Consumer<Boolean> hecho);

        void devolverMc(UUID u, long n);

        /** Si ese objeto se puede crear ahora mismo (sin MMOItems, una pieza no). */
        boolean creable(String objeto);

        boolean entregar(UUID u, String objeto, int n, String origen);

        /**
         * Cuantos lleva encima (en la mano o en el inventario) de algo que se entrega: una pieza de la
         * Forja o los Fragmentos de Masamune, suyos o sin ligar.
         */
        int cuantos(UUID u, String objeto);

        /**
         * Le quita n (todos o ninguno) y devuelve lo quitado, para poder devolverselo; null si no
         * lleva tantos (y entonces no le quita nada).
         */
        Object quitar(UUID u, String objeto, int n);

        /** Le devuelve lo que se le quito con quitar, si la compra falla despues. */
        void devolver(UUID u, Object quitado);

        void guardar();
    }

    private final Hardcore hc;
    private final MenuAltar menu;
    private final Forja forja;
    private final Camino camino;
    /** Quien tiene un trueque a medias (las MC se cobran a los 2 ticks): un clic mas no paga dos veces. */
    private final Set<UUID> enCurso = new HashSet<>();
    private final Set<BukkitTask> tareas = new HashSet<>();

    /** El bloque del altar, leido de la config como mucho cada 5 s (el clic derecho es constante). */
    private Location altarCache;
    private long altarLeido;

    Altar(Hardcore hc) {
        this.hc = hc;
        this.forja = new Forja(hc, this);
        this.camino = new Camino(hc, this);
        this.menu = new MenuAltar(hc, this);
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Subcomandos.staff().registrar("altar",
                "altar [test <player> <trade> | reset <player> | open [altar|forge|path] | info]: sin argumentos marca el bloque que miras",
                Subcomandos.PERMISO, this::comando, this::tab);
        Autotest.registrar("altar", this::autotest);
        hc.seguro("altar", this::podar);
    }

    MenuAltar menu() {
        return menu;
    }

    Forja forja() {
        return forja;
    }

    Camino camino() {
        return camino;
    }

    boolean activo() {
        return hc.cfg().getBoolean("altar.activo", true);
    }

    void parar() {
        for (BukkitTask t : tareas) t.cancel();
        tareas.clear();
        HandlerList.unregisterAll(this);
        menu.parar();
        enCurso.clear();
    }

    void tarea(Runnable r, long ticks) {
        final BukkitTask[] t = new BukkitTask[1];
        t[0] = hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(), () -> {
            tareas.remove(t[0]);
            hc.seguro("altar", r);
        }, ticks);
        tareas.add(t[0]);
    }

    Calendario calendario() {
        return hc.calendario() != null ? hc.calendario() : new Calendario(hc);
    }

    // ================================================================ ganchos

    /** Al cruzar la puerta de entrada: destello con el credito mas cercano (P-W03). */
    void alEntrar(Player p) {
        if (!activo()) return;
        camino.destelloAlEntrar(p);
    }

    /** Trueque contra el saldo sin menu (/calamity altar test). True si se cobro y entrego. */
    boolean probar(OfflinePlayer p, String trueque) {
        boolean[] ok = {false};
        comprar(p, trueque, Bukkit.getConsoleSender(), r -> ok[0] = r.ok());
        return ok[0];
    }

    // ================================================================ trueques

    /** Los trueques de altar.trueques, o los de serie (DIS sec. 4) si la lista no esta. */
    List<Trueque> trueques() {
        List<Map<?, ?>> l = hc.cfg().getMapList("altar.trueques");
        List<Trueque> out = leer(l.isEmpty() ? DEFECTO : l);
        return out;
    }

    Trueque trueque(String id) {
        if (id == null) return null;
        for (Trueque t : trueques()) if (t.id().equalsIgnoreCase(id)) return t;
        return null;
    }

    static List<Trueque> leer(List<? extends Map<?, ?>> lista) {
        List<Trueque> out = new ArrayList<>();
        for (Map<?, ?> m : lista) {
            Trueque t = leer(m);
            if (t != null) out.add(t);
        }
        return out;
    }

    static Trueque leer(Map<?, ?> m) {
        String id = texto(m, "id", null);
        if (id == null || id.isBlank()) return null;
        id = id.toLowerCase(Locale.ROOT);
        String da = texto(m, "da", id).toLowerCase(Locale.ROOT);
        Material icono = Material.matchMaterial(texto(m, "icono", "PAPER"));
        List<String> lore = new ArrayList<>();
        if (m.get("lore") instanceof List<?> l) for (Object o : l) lore.add(String.valueOf(o));
        Map<?, ?> repos = m.get("reposicion") instanceof Map<?, ?> r ? r : null;
        String credito = texto(m, "credito", null);
        credito = credito == null || credito.isBlank() ? null : Creditos.tipo(credito);
        int creditos = entero(m, "creditos", 1);
        List<Entrega> entregar = entregas(m.get("entregar"));
        // El credito "masamune" ya no existe: los Fragmentos son objetos. Un trueque que aun lo pida
        // (una config sin actualizar) pide esos mismos Fragmentos, en fisico.
        if (FragmentosMasamune.CREDITO_VIEJO.equals(credito)) {
            boolean ya = false;
            for (Entrega e : entregar) ya |= e.objeto().equals(FragmentosMasamune.OBJETO);
            if (!ya) entregar.add(new Entrega(FragmentosMasamune.OBJETO, Math.max(1, creditos)));
            credito = null;
        }
        return new Trueque(id, texto(m, "pagina", "umbral").toLowerCase(Locale.ROOT),
                icono == null ? Material.PAPER : icono, texto(m, "nombre", null), lore,
                entero(m, "esencias", 0), entero(m, "esencias-incremento", 0), largo(m, "mobcoins", 0),
                credito, creditos, da,
                Math.max(1, entero(m, "cantidad", 1)), entero(m, "limite-semana", 0), entero(m, "limite-dia", 0),
                entero(m, "stock-semana", 0), texto(m, "requisito", null), repos != null,
                repos == null ? 0 : entero(repos, "esencias", 0), repos == null ? 0 : largo(repos, "mobcoins", 0),
                entero(m, "espera-dias", 0), entregar);
    }

    /**
     * Lo que pide entregar un trueque. Vale el formato de siempre (entregar: masamune, una pieza) y
     * el de ahora, una lista con cantidades: entregar: [{objeto: fragmento-masamune, cantidad: 5}].
     * Un objeto repetido suma sus cantidades.
     */
    static List<Entrega> entregas(Object v) {
        List<Entrega> out = new ArrayList<>();
        if (v == null) return out;
        List<?> lista = v instanceof List<?> l ? l : List.of(v);
        for (Object o : lista) {
            if (o instanceof Map<?, ?> m) anadir(out, texto(m, "objeto", null), entero(m, "cantidad", 1));
            else if (o instanceof ConfigurationSection s) anadir(out, s.getString("objeto"), s.getInt("cantidad", 1));
            else if (o != null) anadir(out, String.valueOf(o), 1);
        }
        return out;
    }

    private static void anadir(List<Entrega> out, String objeto, int n) {
        if (objeto == null || objeto.isBlank()) return;
        String o = objeto.trim().toLowerCase(Locale.ROOT);
        int cuantos = Math.max(1, n);
        for (int i = 0; i < out.size(); i++) {
            if (!out.get(i).objeto().equals(o)) continue;
            out.set(i, new Entrega(o, out.get(i).cantidad() + cuantos));
            return;
        }
        out.add(new Entrega(o, cuantos));
    }

    private static String texto(Map<?, ?> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : String.valueOf(v);
    }

    private static int entero(Map<?, ?> m, String k, int def) {
        return (int) largo(m, k, def);
    }

    private static long largo(Map<?, ?> m, String k, long def) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.longValue();
        if (v == null) return def;
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Como se llama un trueque en el menu y en los mensajes. */
    static String nombre(Trueque t) {
        String n = t.nombre();
        if (n == null || n.isBlank()) {
            String pieza = t.pieza();
            return pieza != null ? Forja.nombrePieza(pieza) : t.id();
        }
        // La config de serie va sin tildes (DIS sec. 4); en el juego, con ellas.
        return switch (n) {
            case "Talisman de Vigilia" -> "Talismán de Vigilia";
            case "Tintura de Ceniza x2" -> "Tintura de Ceniza ×2";
            default -> n;
        };
    }

    // ------------------------------------------------------------ el motor

    private static String rutaUsos(Caja c, UUID u, String id) {
        return "altar.usos." + c.semana() + "." + u + "." + id;
    }

    private static String rutaUsosDia(Caja c, UUID u, String id) {
        return "altar.usos-dia." + c.dia() + "." + u + "." + id;
    }

    private static String rutaStock(Caja c, String id) {
        return "altar.stock." + c.semana() + "." + id;
    }

    static int usosSemana(Caja c, UUID u, String id) {
        return c.datos().getInt(rutaUsos(c, u, id), 0);
    }

    static int usosDia(Caja c, UUID u, String id) {
        return c.datos().getInt(rutaUsosDia(c, u, id), 0);
    }

    static int stockUsado(Caja c, String id) {
        return c.datos().getInt(rutaStock(c, id), 0);
    }

    /** Lo que costaria ahora: la Ascua sube con cada compra de la semana; la reposicion baja. */
    static Precio precio(Caja c, Trueque t, UUID u) {
        String pieza = t.pieza();
        boolean repos = pieza != null && Forja.enReposicion(c.datos(), u, pieza, c.ahora(), c.reposicionDias());
        int e = t.esencias() + t.incremento() * usosSemana(c, u, t.id());
        long mc = t.mobcoins();
        if (repos && t.conReposicion()) {
            e = t.reposEsencias();
            mc = t.reposMc();
        }
        return new Precio(e, mc, t.credito(), t.credito() == null ? 0 : Math.max(1, t.creditos()), repos);
    }

    /**
     * Revisa sin tocar nada. El orden importa: primero lo que no depende de lo que tengas
     * (cupo, stock, requisitos, espera, MobCoins sin verificar), despues el credito, las
     * Esencias y las MobCoins que tienes. Asi
     * el motivo que se apunta es el que de verdad frena (MED sec. 3.3, interes frustrado).
     */
    static Plan revisar(Caja c, Trueque t, UUID u) {
        Precio pr = precio(c, t, u);
        if ("salvoconducto".equals(t.objeto()) && !c.salvoconductoActivo()) return new Plan(pr, null, "requisito", "apagado");
        if (t.limiteSemana() > 0 && usosSemana(c, u, t.id()) >= t.limiteSemana()) return new Plan(pr, null, "cupo", 0);
        if (t.limiteDia() > 0 && usosDia(c, u, t.id()) >= t.limiteDia()) return new Plan(pr, null, "cupo", 0);
        if (t.stock() > 0 && stockUsado(c, t.id()) >= t.stock()) return new Plan(pr, null, "stock", 0);
        String req = t.requisito() == null ? "" : t.requisito().trim();
        if (req.equalsIgnoreCase("tope-llaves") && c.llavesLibres(u) < t.cantidad()) {
            return new Plan(pr, null, "cupo", "tope-llaves");
        }
        if (req.toLowerCase(Locale.ROOT).startsWith("permiso:") && !c.permiso(u, req.substring(8).trim())) {
            return new Plan(pr, null, "requisito", req.substring(8).trim());
        }
        String pieza = t.pieza();
        if (t.esperaDias() > 0 && pieza != null && !pr.reposicion()) {
            long ultima = c.datos().getLong("forjas." + u + "." + pieza, 0);
            long espera = t.esperaDias() * 86_400_000L;
            if (ultima > 0 && c.ahora() - ultima < espera) {
                long dias = (ultima + espera - c.ahora() + 86_399_999L) / 86_400_000L;
                return new Plan(pr, null, "cupo", dias + "d");
            }
        }
        // MobCoins sin verificar (Monedero): el trueque esta en gris y eso es lo primero que se
        // dice, antes que "te faltan Esencias" (no es algo que el jugador pueda arreglar).
        if (pr.mc() > 0 && (!c.mcDisponible() || c.mc(u) < 0)) return new Plan(pr, null, "mc", "proximamente");
        // Lo que hay que entregar (Fragmentos de Masamune, la Masamune de la Crimson): sin todo, no se cobra nada.
        for (Entrega en : t.entregar()) {
            int tiene = c.cuantos(u, en.objeto());
            if (tiene < en.cantidad()) return new Plan(pr, null, "objeto", new Falta(en.objeto(), tiene, en.cantidad()));
        }
        String usado = null;
        if (pr.credito() != null) {
            Creditos cr = c.creditos();
            int n = pr.creditos();
            if (cr.gastables(u, pr.credito()) >= n) {
                usado = pr.credito();
            } else if (pr.credito().startsWith("sello:") && cr.gastables(u, Creditos.ERRANTE) >= n) {
                // Un Sello Errante vale por cualquier Sello de minijefe (con sus 48 h activas).
                usado = Creditos.ERRANTE;
            } else {
                boolean horas = "horas".equals(cr.motivoNoGasta(u, pr.credito(), n))
                        || (pr.credito().startsWith("sello:") && "horas".equals(cr.motivoNoGasta(u, Creditos.ERRANTE, n)));
                return new Plan(pr, null, "credito", horas ? "horas" : pr.credito());
            }
        }
        long saldo = c.saldo().de(u);
        if (saldo < pr.esencias()) return new Plan(pr, usado, "esencias", pr.esencias() - saldo);
        if (pr.mc() > 0) {
            long tiene = c.mc(u);
            if (tiene < pr.mc()) return new Plan(pr, usado, "mc", pr.mc() - tiene);
        }
        return new Plan(pr, usado, null, null);
    }

    /**
     * Compra: revisar, cobrar Esencias y credito, comprobar que se puede crear, cobrar MC y
     * entregar. El resultado llega por "fin" (en el hilo principal; con MC reales, 2 ticks
     * despues). Solo apunta el uso (cupo, Ascua, stock, Forja) si se entrego.
     */
    static void comprar(Caja c, Trueque t, UUID u, String origen, Consumer<Resultado> fin) {
        Plan pl = revisar(c, t, u);
        Precio pr = pl.precio();
        if (pl.motivo() != null) {
            fin.accept(new Resultado(t, false, false, pl.motivo(), pl.faltan(), pr, pl.creditoUsado()));
            return;
        }
        if (!c.saldo().restar(u, pr.esencias(), origen)) {
            fin.accept(new Resultado(t, false, false, "esencias", pr.esencias() - c.saldo().de(u), pr, null));
            return;
        }
        String cred = pl.creditoUsado();
        int deCaja = 0;
        if (cred != null) {
            Creditos cr = c.creditos();
            // Cuantos saldran de los de caja: al devolver, cada uno vuelve a su sitio.
            deCaja = Math.max(0, pr.creditos() - (cr.de(u, cred) - cr.deCaja(u, cred)));
            if (!cr.gastar(u, cred, pr.creditos())) {
                c.saldo().sumar(u, pr.esencias(), "devolucion:" + origen);
                fin.accept(new Resultado(t, false, false, "credito", cred, pr, null));
                return;
            }
        }
        final int deLaCaja = deCaja;
        List<Object> entregados = new ArrayList<>();
        Runnable devolver = () -> {
            c.saldo().sumar(u, pr.esencias(), "devolucion:" + origen);
            if (cred != null) {
                int normales = pr.creditos() - deLaCaja;
                if (normales > 0) c.creditos().sumar(u, cred, normales, "devolucion:" + origen, false);
                if (deLaCaja > 0) c.creditos().sumar(u, cred, deLaCaja, "devolucion:" + origen, true);
            }
            for (Object q : entregados) c.devolver(u, q);
            entregados.clear();
        };
        // Lo que se entrega se quita ya, con las Esencias y los creditos: si luego algo falla, vuelve todo.
        for (Entrega en : t.entregar()) {
            Object q = c.quitar(u, en.objeto(), en.cantidad());
            if (q == null) {
                Falta falta = new Falta(en.objeto(), c.cuantos(u, en.objeto()), en.cantidad());
                devolver.run();
                c.guardar();
                fin.accept(new Resultado(t, false, true, "objeto", falta, pr, cred));
                return;
            }
            entregados.add(q);
        }
        String objeto = t.objeto();
        if (objeto != null && !c.creable(objeto)) {
            devolver.run();
            c.guardar();
            fin.accept(new Resultado(t, false, true, "creacion", objeto, pr, cred));
            return;
        }
        c.cobrarMc(u, pr.mc(), cobrado -> {
            if (!cobrado) {
                devolver.run();
                c.guardar();
                fin.accept(new Resultado(t, false, true, "mc", pr.mc(), pr, cred));
                return;
            }
            boolean dado = objeto == null || c.entregar(u, objeto, t.cantidad(), origen);
            if (!dado) {
                devolver.run();
                if (pr.mc() > 0) c.devolverMc(u, pr.mc());
                c.guardar();
                fin.accept(new Resultado(t, false, true, "entrega", objeto, pr, cred));
                return;
            }
            apuntarUso(c, t, u, pr);
            c.guardar();
            fin.accept(new Resultado(t, true, false, null, null, pr, cred));
        });
    }

    private static void apuntarUso(Caja c, Trueque t, UUID u, Precio pr) {
        ConfigurationSection d = c.datos();
        String r = rutaUsos(c, u, t.id());
        d.set(r, d.getInt(r, 0) + 1);
        if (t.limiteDia() > 0) {
            String rd = rutaUsosDia(c, u, t.id());
            d.set(rd, d.getInt(rd, 0) + 1);
        }
        if (t.stock() > 0) {
            String rs = rutaStock(c, t.id());
            d.set(rs, d.getInt(rs, 0) + 1);
        }
        String pieza = t.pieza();
        if (pieza != null) {
            d.set("forjas." + u + "." + pieza, c.ahora());
            if (pr.reposicion()) d.set("perdidas." + u + "." + pieza, null);
        }
    }

    // ------------------------------------------------------ la caja de verdad

    /** Los modulos reales. El jugador puede estar desconectado (probar): lo que pide cuerpo, lo avisa. */
    private final class CajaReal implements Caja {

        private final Calendario cal = calendario();

        @Override
        public ConfigurationSection datos() {
            return hc.datos();
        }

        @Override
        public Saldo saldo() {
            return hc.saldo();
        }

        @Override
        public Creditos creditos() {
            return hc.creditos();
        }

        @Override
        public String semana() {
            return cal.semana();
        }

        @Override
        public String dia() {
            return cal.dia();
        }

        @Override
        public long ahora() {
            return System.currentTimeMillis();
        }

        @Override
        public int reposicionDias() {
            return hc.cfg().getInt("forja.reposicion-dias", 14);
        }

        @Override
        public boolean salvoconductoActivo() {
            return hc.cfg().getBoolean("salvoconducto.activo", false);
        }

        @Override
        public boolean permiso(UUID u, String permiso) {
            Player p = Bukkit.getPlayer(u);
            return p != null && p.hasPermission(permiso);
        }

        @Override
        public int llavesLibres(UUID u) {
            Entregas e = hc.entregas();
            return e == null ? 0 : e.llavesLibres(u);
        }

        private boolean modoPrueba() {
            return "prueba".equalsIgnoreCase(hc.cfg().getString("monedero.modo", "real"));
        }

        @Override
        public boolean mcDisponible() {
            Monedero m = hc.monedero();
            return m != null && m.disponible();
        }

        @Override
        public long mc(UUID u) {
            Monedero m = hc.monedero();
            if (m == null) return -1;
            Player p = Bukkit.getPlayer(u);
            if (p != null) return m.saldo(p);
            // Desconectado solo se sabe en modo prueba (el placeholder real pide jugador).
            return modoPrueba() ? Math.max(0, hc.datos().getLong("monedero-prueba." + u, 0)) : -1;
        }

        @Override
        public void cobrarMc(UUID u, long n, Consumer<Boolean> hecho) {
            if (n <= 0) {
                hecho.accept(true);
                return;
            }
            Monedero m = hc.monedero();
            if (m == null) {
                hecho.accept(false);
                return;
            }
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                m.cobrar(p, n, hecho);
                return;
            }
            boolean ok = modoPrueba() && m.cobrarPrueba(u, n);
            hc.plugin().bitacora().anotar("monedero", ok ? "cobro" : "fallo", Saldo.nombre(u), String.valueOf(n), "altar desconectado");
            hecho.accept(ok);
        }

        @Override
        public void devolverMc(UUID u, long n) {
            if (n <= 0) return;
            Monedero m = hc.monedero();
            if (modoPrueba() && m != null) {
                m.ponerPrueba(u, Math.max(0, hc.datos().getLong("monedero-prueba." + u, 0)) + n);
            } else {
                // MobCoins reales: solo Entregas puede pagarlas (regla 7). Van a pendientes y,
                // si esta conectado y fuera, se le pagan ya.
                Entregas e = hc.entregas();
                if (e == null) return;
                e.pendienteMc(u, n, "devolucion:altar");
                Player p = Bukkit.getPlayer(u);
                if (p != null) e.pendientes(p);
            }
            hc.plugin().bitacora().anotar("monedero", "devuelto", Saldo.nombre(u), String.valueOf(n), "altar");
        }

        @Override
        public boolean creable(String objeto) {
            Entregas e = hc.entregas();
            if (e == null) return false;
            if (objeto.equals("ofrenda") || objeto.startsWith("llave") || objeto.equals("libro")
                    || objeto.startsWith("esencia") || objeto.startsWith("credito")) return true;
            if (objeto.equals("salvoconducto") && !salvoconductoActivo()) return true;
            return e.crear(objeto) != null;
        }

        @Override
        public boolean entregar(UUID u, String objeto, int n, String origen) {
            if (objeto.equals("ofrenda")) return true;
            Entregas e = hc.entregas();
            return e != null && e.dar(null, objeto, Bukkit.getOfflinePlayer(u), n, origen);
        }

        @Override
        public int cuantos(UUID u, String objeto) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) return 0;
            int n = 0;
            for (int i : casillasDe(p, objeto)) n += p.getInventory().getItem(i).getAmount();
            return n;
        }

        @Override
        public Object quitar(UUID u, String objeto, int n) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || n <= 0) return null;
            List<Integer> casillas = casillasDe(p, objeto);
            PlayerInventory inv = p.getInventory();
            int hay = 0;
            for (int i : casillas) hay += inv.getItem(i).getAmount();
            if (hay < n) return null;
            List<ItemStack> quitado = new ArrayList<>();
            int falta = n;
            for (int i : casillas) {
                if (falta <= 0) break;
                ItemStack it = inv.getItem(i);
                int k = Math.min(falta, it.getAmount());
                ItemStack parte = it.clone();
                parte.setAmount(k);
                quitado.add(parte);
                if (it.getAmount() > k) {
                    it.setAmount(it.getAmount() - k);
                    inv.setItem(i, it);
                } else {
                    inv.setItem(i, null);
                }
                falta -= k;
            }
            hc.plugin().bitacora().anotar("trueque", p.getName(), "entrega", objeto, "x" + n);
            return quitado;
        }

        @Override
        public void devolver(UUID u, Object quitado) {
            List<ItemStack> its = new ArrayList<>();
            if (quitado instanceof ItemStack it) its.add(it);
            else if (quitado instanceof List<?> l) for (Object o : l) if (o instanceof ItemStack it) its.add(it);
            if (its.isEmpty()) return;
            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
            Entregas e = hc.entregas();
            if (e != null) {
                e.devolver(op, its, "devolucion:altar");
                return;
            }
            Player p = op.getPlayer();
            if (p != null) for (ItemStack it : its) Suelo.dar(hc.plugin(), p, it);
        }

        @Override
        public void guardar() {
            hc.guardarYa();
        }
    }

    /**
     * Las casillas donde lleva algo que se entrega, suyo o sin ligar, en el orden en que se quita: la
     * mano, luego el inventario y la otra mano. Los Fragmentos de Masamune se reconocen por su marca;
     * una pieza de la Forja, por su objeto de MMOItems (sin MMOItems, ninguna).
     */
    private List<Integer> casillasDe(Player p, String objeto) {
        java.util.function.Predicate<ItemStack> es;
        if (FragmentosMasamune.OBJETO.equals(objeto)) {
            es = ItemsCalamity::esFragmentoMasamune;
        } else if (PuenteBovedas.esLlave(objeto)) {
            // Calamity 1.11: las Llaves del Umbral que pide la Llave Ominosa, por la marca de EDM.
            es = it -> PuenteBovedas.es(it, objeto);
        } else {
            Entregas e = hc.entregas();
            String id = e == null ? null : e.idMmo("forja:" + objeto);
            if (id == null) return List.of();
            es = it -> id.equals(PuenteMmo.enlace(it));
        }
        PlayerInventory inv = p.getInventory();
        Set<Integer> orden = new java.util.LinkedHashSet<>();
        orden.add(inv.getHeldItemSlot());
        for (int i = 0; i < inv.getStorageContents().length; i++) orden.add(i);
        orden.add(40);
        List<Integer> out = new ArrayList<>();
        for (int i : orden) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir() || it.getAmount() <= 0 || !es.test(it)) continue;
            UUID dueno = Ligado.duenoDe(it);
            if (dueno == null || dueno.equals(p.getUniqueId())) out.add(i);
        }
        return out;
    }

    Caja caja() {
        return new CajaReal();
    }

    /** La revision para un jugador (la usa el menu para pintar cada boton). */
    Plan revisar(Player p, Trueque t) {
        return revisar(caja(), t, p.getUniqueId());
    }

    // ---------------------------------------------------------- comprar (juego)

    /**
     * Compra un trueque para un jugador (menu o probar). quien = a quien se le cuenta el
     * resultado ademas del jugador (el staff en probar). despues recibe el resultado.
     */
    void comprar(OfflinePlayer op, String id, CommandSender quien, Consumer<Resultado> despues) {
        Trueque t = trueque(id);
        if (t == null) {
            decir(quien, op, ComandoCalamity.mensaje("Ese trueque no existe: " + id + "."));
            return;
        }
        if (!activo()) {
            decir(quien, op, ComandoCalamity.mensaje("El Altar está cerrado ahora mismo."));
            return;
        }
        if (hc.saldo() == null || hc.creditos() == null) {
            decir(quien, op, ComandoCalamity.mensaje("El Altar no puede cobrar ahora mismo."));
            return;
        }
        Player p = op.getPlayer();
        if (t.servicio()) {
            if (p == null) {
                decir(quien, null, ComandoCalamity.mensaje("«" + nombre(t) + "» necesita que el jugador esté conectado."));
                return;
            }
            switch (t.da()) {
                case "recargar" -> recargar(p, t);
                case "depositar" -> depositar(p);
                default -> camino.abrir(p);
            }
            return;
        }
        UUID u = op.getUniqueId();
        if (!enCurso.add(u)) {
            decir(quien, op, ComandoCalamity.mensaje("Espera: el Altar todavía está terminando tu compra anterior."));
            return;
        }
        try {
            comprar(caja(), t, u, "altar:" + t.id(), r -> {
                enCurso.remove(u);
                hc.seguro("altar", () -> concluir(op, quien, r));
                if (despues != null) despues.accept(r);
            });
        } catch (Throwable e) {
            enCurso.remove(u);
            throw e;
        }
    }

    /** Al jugador (si esta) y, si es otro, a quien lo pidio. */
    private static void decir(CommandSender quien, OfflinePlayer op, Component msg) {
        Player p = op == null ? null : op.getPlayer();
        if (p != null) p.sendMessage(msg);
        if (quien != null && quien != p) quien.sendMessage(msg);
    }

    /** Bitacora, telemetria, mensajes y lo que dispara cada trueque (Forja, Ofrenda). */
    private void concluir(OfflinePlayer op, CommandSender quien, Resultado r) {
        Trueque t = r.t();
        Precio pr = r.precio();
        String nombre = Entregas.nombre(op);
        Player p = op.getPlayer();
        Telemetria tel = hc.telemetria();

        if (!r.ok() && !r.devuelto()) {
            hc.plugin().bitacora().anotar("trueque", nombre, t.id(), "rechazado", r.motivo(), "faltan " + r.faltan());
            if (tel != null) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", t.id());
                c.put("pagina", t.pagina());
                c.put("motivo", r.motivo());
                c.put("faltan", r.faltan());
                hc.seguro("telemetria", () -> tel.suceso("trueque-fallido", op, c));
            }
            Component aviso = avisoRechazo(r, op.getUniqueId());
            if (p != null) {
                p.sendMessage(aviso);
                Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.note_block.bass", 0.8f, 0.6f);
            }
            if (quien != null && quien != p) {
                quien.sendMessage(Component.text("altar probar: " + t.id() + " rechazado (" + r.motivo() + ", faltan "
                        + r.faltan() + ")", Paleta.AVISO));
            }
            return;
        }

        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", t.id());
        c.put("pagina", t.pagina());
        c.put("esencias", pr.esencias());
        c.put("mc", pr.mc());
        c.put("credito", r.creditoUsado() == null ? "" : r.creditoUsado());
        c.put("reposicion", pr.reposicion() ? "si" : "no");
        c.put("stock_restante", t.stock() > 0 ? Math.max(0, t.stock() - stockUsado(caja(), t.id())) : -1);
        c.put("entrega", r.ok() ? "ok" : "fallo");
        if (r.devuelto()) c.put("motivo", r.motivo());
        if (tel != null) hc.seguro("telemetria", () -> tel.suceso("trueque", op, c));

        if (r.devuelto()) {
            hc.plugin().bitacora().anotar("trueque", nombre, t.id(), "-" + pr.esencias(), "fallo", "devuelto", r.motivo(),
                    "mc " + pr.mc(), r.creditoUsado() == null ? "-" : r.creditoUsado());
            Component aviso = ComandoCalamity.mensaje(Component.text("El Altar no ha podido darte ")
                    .append(Component.text(nombre(t), Paleta.DETALLE))
                    .append(Component.text(". Te ha devuelto todo lo que pagaste.")));
            if (p != null) {
                p.sendMessage(aviso);
                Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.note_block.bass", 0.8f, 0.6f);
            }
            if (quien != null && quien != p) {
                quien.sendMessage(Component.text("altar probar: " + t.id() + " fallo al entregar (" + r.motivo()
                        + "): devuelto", Paleta.AVISO));
            }
            return;
        }

        hc.plugin().bitacora().anotar("trueque", nombre, t.id(), "-" + pr.esencias(), "ok", "mc " + pr.mc(),
                r.creditoUsado() == null ? "-" : r.creditoUsado(), pr.reposicion() ? "reposicion" : "-");
        if (t.pieza() != null) hc.seguro("altar", () -> forja.trasForjar(op, r));
        if ("ofrenda".equals(t.da())) hc.seguro("altar", () -> ofrenda(op, pr.esencias()));
        if (p != null) {
            p.sendMessage(avisoHecho(t, pr, r.creditoUsado(), "ofrenda".equals(t.da()) ? ofrendasMes(op.getUniqueId()) : 0));
            Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.amethyst_block.resonate", 1.0f, 1.2f);
        }
        if (quien != null && quien != p) {
            Saldo s = hc.saldo();
            quien.sendMessage(Component.text("altar probar: " + t.id() + " ok (-" + pr.esencias() + " E"
                    + (pr.mc() > 0 ? ", -" + pr.mc() + " MC" : "") + (r.creditoUsado() != null ? ", -" + pr.creditos() + " "
                    + r.creditoUsado() : "") + "; saldo " + (s == null ? "?" : s.de(op.getUniqueId())) + ")", Paleta.BIEN));
        }
    }

    /**
     * "Has comprado: <nombre> (−10 Esencias, −2.000 MobCoins)." usado: el credito que se gasto de
     * verdad (un Sello Errante puede valer por el Sello que pide la pieza). ofrendasMes (1.7.6): en
     * la Ofrenda, las que lleva este mes contando esta ("Ya llevas 3 este mes."); 0, nada.
     */
    private static Component avisoHecho(Trueque t, Precio pr, String usado, int ofrendasMes) {
        List<String> coste = new ArrayList<>();
        if (pr.esencias() > 0) coste.add("−" + miles(pr.esencias()) + (pr.esencias() == 1 ? " Esencia" : " Esencias"));
        if (pr.mc() > 0) coste.add("−" + miles(pr.mc()) + " MobCoins");
        String credito = usado != null ? usado : pr.credito();
        if (credito != null) coste.add("−" + MenuAltar.creditoLinea(credito, pr.creditos()));
        String texto = "ofrenda".equals(t.da()) ? "El Altar acepta tu Ofrenda"
                : ("forja".equals(t.pagina()) ? "Has forjado: " : "Has comprado: ") + nombre(t);
        Component cuerpo = Component.text(texto)
                .append(Component.text(coste.isEmpty() ? "" : " (" + String.join(", ", coste) + ")", Paleta.CIFRA))
                .append(Component.text("."));
        if ("ofrenda".equals(t.da()) && ofrendasMes > 0) {
            cuerpo = cuerpo.append(Component.text(" Ya llevas ")).append(Paleta.cifra(ofrendasMes))
                    .append(Component.text(" este mes."));
        }
        return ComandoCalamity.mensaje(cuerpo);
    }

    /** El mensaje de cada motivo de rechazo: P-M03, P-M04, P-W01/P-W07, P-M10/P-M11. */
    Component avisoRechazo(Resultado r, UUID u) {
        Trueque t = r.t();
        Object f = r.faltan();
        return switch (r.motivo()) {
            case "esencias" -> {
                long n = f instanceof Number x ? x.longValue() : 0;
                yield ComandoCalamity.mensaje(Component.text(n == 1 ? "Te falta " : "Te faltan ")
                        .append(Component.text(miles(n), Paleta.CIFRA))
                        .append(Component.text(n == 1 ? " Esencia." : " Esencias.")));
            }
            case "cupo" -> {
                if ("tope-llaves".equals(f)) yield ComandoCalamity.mensaje("Esta semana ya has recibido el máximo de Llaves del Caos.");
                if (f instanceof String s && s.endsWith("d")) {
                    String dias = s.substring(0, s.length() - 1);
                    yield ComandoCalamity.mensaje("Esta pieza solo se puede forjar una vez cada " + t.esperaDias() + " días. "
                            + ("1".equals(dias) ? "Te falta 1 día." : "Te faltan " + dias + " días."));
                }
                yield ComandoCalamity.mensaje(t.limiteDia() > 0 && t.limiteSemana() <= 0
                        ? "Ya has llegado al límite diario de " + nombre(t) + "."
                        : "Ya has llegado al límite semanal de " + nombre(t) + ".");
            }
            case "stock" -> ComandoCalamity.mensaje("Esta semana ya no quedan existencias de " + nombre(t) + " en el Altar.");
            case "credito" -> {
                if ("horas".equals(f) && hc.creditos() != null) yield hc.creditos().avisoHoras(u);
                yield ComandoCalamity.mensaje(Component.text("Necesitas ")
                        .append(Component.text(Forja.nombreCredito(String.valueOf(f), r.precio().creditos()), Paleta.DETALLE))
                        .append(Component.text(" para forjarlo.")));
            }
            case "mc" -> "proximamente".equals(f) ? Monedero.avisoProximamente()
                    : Monedero.avisoFaltan(f instanceof Number n ? n.longValue() : 0);
            case "objeto" -> avisoObjeto(f instanceof Falta fa ? fa : new Falta(String.valueOf(f), 0, 1));
            case "requisito" -> {
                if ("apagado".equals(f)) yield ComandoCalamity.mensaje("Esto todavía no está disponible en el Altar.");
                if (String.valueOf(f).contains("insomne")) {
                    yield ComandoCalamity.mensaje("Solo pueden comprarlo quienes tienen el tag [INSOMNE].");
                }
                yield ComandoCalamity.mensaje("Aún no cumples lo que hace falta para comprarlo.");
            }
            default -> ComandoCalamity.mensaje("El Altar no puede venderte eso ahora mismo.");
        };
    }

    /**
     * Le falta algo que se entrega: "Te faltan 2 Fragmentos de Masamune: llevas 3 de 5." o "Para
     * forjarla tienes que llevar encima tu Masamune."
     */
    static Component avisoObjeto(Falta fa) {
        if (FragmentosMasamune.OBJETO.equals(fa.objeto())) {
            int n = Math.max(1, fa.faltan());
            return ComandoCalamity.mensaje(Component.text(n == 1 ? "Te falta " : "Te faltan ")
                    .append(Component.text(FragmentosMasamune.nombre(n), Paleta.DETALLE))
                    .append(Component.text(": llevas " + fa.tiene() + " de " + fa.pide() + ".")));
        }
        if (PuenteBovedas.esLlave(fa.objeto())) {
            int n = Math.max(1, fa.faltan());
            return ComandoCalamity.mensaje(Component.text(n == 1 ? "Te falta " : "Te faltan ")
                    .append(Component.text(n == 1 ? "una " + PuenteBovedas.nombre(fa.objeto(), 1) : PuenteBovedas.nombre(fa.objeto(), n), Paleta.DETALLE))
                    .append(Component.text(": llevas " + fa.tiene() + " de " + fa.pide() + ".")));
        }
        return ComandoCalamity.mensaje(Component.text("Para forjarla tienes que llevar encima tu ")
                .append(Component.text(Forja.nombrePieza(fa.objeto()), Paleta.DETALLE))
                .append(Component.text(".")));
    }

    static String miles(long n) {
        return String.format(Locale.ROOT, "%,d", n).replace(',', '.');
    }

    /** Ofrenda: 1 punto en la tabla del mes (ofrendas-mes) y stats.ofrendas; sin objeto ni tag. */
    private void ofrenda(OfflinePlayer op, int esencias) {
        UUID u = op.getUniqueId();
        String r = rutaOfrendasMes(u);
        hc.datos().set(r, hc.datos().getInt(r, 0) + 1);
        hc.marcarSucio();
        Estadisticas st = hc.estadisticas();
        if (st != null) st.sumar(u, "ofrendas", 1);
        Telemetria tel = hc.telemetria();
        if (tel != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("cantidad", esencias);
            hc.seguro("telemetria", () -> tel.suceso("ofrenda", op, c));
        }
    }

    /** 1.7.6: las Ofrendas de ese jugador este mes (la tabla ofrendas-mes), para el menu y el aviso. */
    int ofrendasMes(UUID u) {
        return hc.datos().getInt(rutaOfrendasMes(u), 0);
    }

    private String rutaOfrendasMes(UUID u) {
        return "ofrendas-mes." + calendario().mes() + "." + u;
    }

    // ------------------------------------------------------------ servicios

    /**
     * Recargar el Frasco (DIS M2): el de la mano o, si no, el primero del inventario; si va
     * apilado se separa uno. frasco.esencias-por-trago (1) por cada trago que falte. Se paga
     * primero y despues se cambia el frasco (no puede fallar entre medias).
     */
    void recargar(Player p, Trueque t) {
        PlayerInventory inv = p.getInventory();
        ItemsCalamity items = hc.items();
        int casilla = -1;
        if (items.esFrasco(inv.getItemInMainHand())) casilla = inv.getHeldItemSlot();
        else {
            ItemStack[] cont = inv.getStorageContents();
            for (int i = 0; i < cont.length; i++) {
                if (items.esFrasco(cont[i])) {
                    casilla = i;
                    break;
                }
            }
        }
        String id = t == null ? "recargar" : t.id();
        if (casilla < 0) {
            fallidoServicio(p, id, "requisito", "frasco");
            p.sendMessage(ComandoCalamity.mensaje("No llevas ningún Frasco de Calma."));
            return;
        }
        ItemStack frasco = inv.getItem(casilla);
        int max = hc.cfg().getInt("frasco.usos", 3);
        int faltan = Math.max(0, max - Math.max(0, items.tragos(frasco)));
        if (faltan == 0) {
            p.sendMessage(ComandoCalamity.mensaje("Tu Frasco de Calma ya está lleno."));
            return;
        }
        int coste = faltan * Math.max(0, hc.cfg().getInt("frasco.esencias-por-trago", 1));
        Saldo s = hc.saldo();
        if (s == null || !s.restar(p.getUniqueId(), coste, "altar:recargar")) {
            long tiene = s == null ? 0 : s.de(p.getUniqueId());
            long le = coste - tiene;
            fallidoServicio(p, id, "esencias", le);
            p.sendMessage(ComandoCalamity.mensaje(Component.text(le == 1 ? "Te falta " : "Te faltan ")
                    .append(Component.text(miles(le), Paleta.CIFRA))
                    .append(Component.text(le == 1 ? " Esencia." : " Esencias."))));
            Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.note_block.bass", 0.8f, 0.6f);
            return;
        }
        ItemStack lleno = items.frasco(max);
        UUID dueno = Ligado.duenoDe(frasco);
        if (dueno != null) {
            Entregas e = hc.entregas();
            if (e != null) e.ligar(lleno, dueno);
            else Ligado.ligar(lleno, dueno);
        }
        if (frasco.getAmount() > 1) {
            frasco.setAmount(frasco.getAmount() - 1);
            Suelo.dar(hc.plugin(), p, lleno);
        } else {
            inv.setItem(casilla, lleno);
        }
        hc.plugin().bitacora().anotar("trueque", p.getName(), id, "-" + coste, "ok", "tragos " + faltan);
        Telemetria tel = hc.telemetria();
        if (tel != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", id);
            c.put("pagina", "umbral");
            c.put("esencias", coste);
            c.put("mc", 0);
            c.put("credito", "");
            c.put("reposicion", "no");
            c.put("stock_restante", -1);
            c.put("entrega", "ok");
            hc.seguro("telemetria", () -> tel.suceso("trueque", p, c));
        }
        p.sendMessage(ComandoCalamity.mensaje(Component.text("Tu Frasco de Calma vuelve a estar lleno ")
                .append(Component.text("(−" + miles(coste) + (coste == 1 ? " Esencia" : " Esencias") + ")", Paleta.CIFRA))
                .append(Component.text("."))));
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "item.bottle.fill", 1.0f, 1.0f);
    }

    private void fallidoServicio(Player p, String id, String motivo, Object faltan) {
        hc.plugin().bitacora().anotar("trueque", p.getName(), id, "rechazado", motivo, "faltan " + faltan);
        Telemetria tel = hc.telemetria();
        if (tel == null) return;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("pagina", "umbral");
        c.put("motivo", motivo);
        c.put("faltan", faltan);
        hc.seguro("telemetria", () -> tel.suceso("trueque-fallido", p, c));
    }

    /** Depositar Esencias fisicas (las viejas) en el saldo: P-M09. */
    void depositar(Player p) {
        Saldo s = hc.saldo();
        if (s == null) return;
        int n = s.depositarFisicas(p);
        if (n <= 0) {
            p.sendMessage(ComandoCalamity.mensaje("No llevas Esencias encima."));
            return;
        }
        p.sendMessage(s.avisoDeposito(p, n));
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.amethyst_block.chime", 1.0f, 1.0f);
    }

    // ------------------------------------------------------------ el bloque

    /** El bloque del altar (hardcore.altar.mundo/x/y/z), o null si no esta marcado. */
    Location altar() {
        long ahora = System.currentTimeMillis();
        if (ahora - altarLeido > 5000) {
            altarLeido = ahora;
            altarCache = hc.punto("altar");
        }
        return altarCache;
    }

    private boolean esAltar(Block b) {
        Location a = altar();
        return a != null && b.getWorld() == a.getWorld() && b.getX() == a.getBlockX() && b.getY() == a.getBlockY()
                && b.getZ() == a.getBlockZ();
    }

    /**
     * Clic derecho en el altar: fuera, abre la pagina Umbral; dentro de Calamity, P-M05. Sin
     * ignoreCancelled: la proteccion del spawn suele cancelar el uso de bloques y el altar
     * vive justo ahi. Se cancela siempre (si el altar es un cofre, que no se abra).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onTocar(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (!esAltar(e.getClickedBlock())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!activo()) {
            p.sendMessage(ComandoCalamity.mensaje("El Altar está cerrado ahora mismo."));
            return;
        }
        if (hc.esHardcore(p)) {
            p.sendMessage(ComandoCalamity.mensaje("El Altar solo se abre fuera de Calamity o en su spawn."));
            return;
        }
        menu.abrir(p, MenuAltar.UMBRAL);
    }

    @EventHandler
    public void onSalir(PlayerQuitEvent e) {
        enCurso.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ comandos

    private void comando(CommandSender quien, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "" -> marcar(quien);
            case "test" -> {
                if (args.length < 4) {
                    quien.sendMessage(Component.text("Uso: /calamity altar test <player> <trade>", Paleta.AVISO));
                    return;
                }
                OfflinePlayer a = Entregas.buscar(args[2]);
                if (a == null) {
                    quien.sendMessage(Component.text("No encuentro a ese jugador.", Paleta.AVISO));
                    return;
                }
                comprar(a, args[3], quien, null);
            }
            case "reset" -> {
                /* Cupos de esta semana y de hoy de un jugador (frascos, Ascua, tinturas...). Para el
                 * staff tras un fallo y para repetir las pruebas en la misma semana. No devuelve
                 * nada ni toca el saldo, los creditos, la espera de la Forja ni el stock comun. */
                OfflinePlayer a = args.length >= 3 ? Entregas.buscar(args[2]) : null;
                if (a == null) {
                    quien.sendMessage(Component.text("Uso: /calamity altar reset <player>", Paleta.AVISO));
                    return;
                }
                UUID u = a.getUniqueId();
                Calendario cal = hc.calendario();
                hc.datos().set("altar.usos." + cal.semana() + "." + u, null);
                hc.datos().set("altar.usos-dia." + cal.dia() + "." + u, null);
                hc.guardarYa();
                hc.plugin().bitacora().anotar("altar", "reset", Entregas.nombre(a), quien.getName());
                quien.sendMessage(ComandoCalamity.mensaje("Cupos del Altar de " + Entregas.nombre(a) + " puestos a cero."));
            }
            case "open" -> {
                if (!(quien instanceof Player p)) {
                    quien.sendMessage(Component.text("Solo se puede usar dentro del juego.", Paleta.AVISO));
                    return;
                }
                if (hc.esHardcore(p)) {
                    p.sendMessage(ComandoCalamity.mensaje("El Altar solo se abre fuera de Calamity o en su spawn."));
                    return;
                }
                String pagina = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "altar";
                if (pagina.equals("path")) camino.abrir(p);
                else menu.abrir(p, pagina.equals("forge") ? MenuAltar.FORJA : MenuAltar.UMBRAL);
            }
            case "info" -> {
                Location l = altar();
                quien.sendMessage(ComandoCalamity.mensaje(l == null ? "El Altar no está marcado."
                        : "Altar en " + l.getWorld().getKey() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()
                        + (activo() ? "." : " (apagado).")));
                List<String> ids = new ArrayList<>();
                for (Trueque t : trueques()) ids.add(t.id());
                quien.sendMessage(Component.text("  Trueques: " + String.join(", ", ids), Paleta.TENUE));
            }
            default -> quien.sendMessage(Component.text(
                    "Uso: /calamity altar [test <player> <trade> | reset <player> | open [altar|forge|path] | info]", Paleta.AVISO));
        }
    }

    /** Marca como altar el bloque que mira (como llegada/salida: lo escribe en config.yml). */
    private void marcar(CommandSender quien) {
        if (!(quien instanceof Player p)) {
            quien.sendMessage(Component.text("Solo se puede usar dentro del juego, mirando el bloque que quieras marcar.", Paleta.AVISO));
            return;
        }
        Block b = p.getTargetBlockExact(8);
        if (b == null || b.getType().isAir()) {
            p.sendMessage(Component.text("Mira el bloque que quieras usar como Altar (a 8 bloques como mucho).", Paleta.AVISO));
            return;
        }
        if (hc.esHardcore(b.getWorld())) {
            p.sendMessage(Component.text("El Altar tiene que estar fuera de Calamity.", Paleta.AVISO));
            return;
        }
        hc.punto("altar", b.getLocation());
        altarLeido = 0;
        hc.plugin().bitacora().anotar("altar", "marcado", p.getName(),
                b.getWorld().getKey().getKey() + " " + b.getX() + " " + b.getY() + " " + b.getZ());
        p.sendMessage(ComandoCalamity.mensaje("Altar del Umbral marcado en " + b.getX() + " " + b.getY() + " " + b.getZ()
                + " (" + b.getType().getKey().getKey() + ")."));
        Compat.soundPlayers(p.getWorld(), b.getLocation(), "block.enchantment_table.use", 1.0f, 0.8f);
    }

    private List<String> tab(String[] args) {
        if (args.length == 2) return List.of("test", "reset", "open", "info");
        if (args.length == 3 && (args[1].equalsIgnoreCase("test") || args[1].equalsIgnoreCase("reset"))) {
            return Entregas.nombresConectados();
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("open")) return List.of("altar", "forge", "path");
        if (args.length == 4 && args[1].equalsIgnoreCase("test")) {
            List<String> ids = new ArrayList<>();
            for (Trueque t : trueques()) ids.add(t.id());
            return ids;
        }
        return List.of();
    }

    // ------------------------------------------------------------ datos

    /**
     * Poda lo semanal y diario viejo del altar y los grabados: se queda la semana en curso y
     * la anterior (y el dia de hoy). Sin esto, altar.usos crece una semana cada lunes.
     */
    private void podar() {
        Calendario cal = calendario();
        long ahora = System.currentTimeMillis();
        Set<String> semanas = Set.of(cal.semana(ahora), cal.semanaAnterior(ahora));
        boolean cambio = false;
        for (String seccion : List.of("altar.usos", "altar.stock", "grabados")) {
            ConfigurationSection s = hc.datos().getConfigurationSection(seccion);
            if (s == null) continue;
            for (String k : s.getKeys(false)) {
                if (semanas.contains(k)) continue;
                s.set(k, null);
                cambio = true;
            }
        }
        ConfigurationSection dias = hc.datos().getConfigurationSection("altar.usos-dia");
        if (dias != null) {
            for (String k : dias.getKeys(false)) {
                if (k.equals(cal.dia(ahora))) continue;
                dias.set(k, null);
                cambio = true;
            }
        }
        if (cambio) hc.marcarSucio();
    }

    // ------------------------------------------------------------ de serie

    private static Map<String, Object> t(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** Los trueques de DIS sec. 4 / PLAN sec. 4, por si la config del servidor no los trae. */
    static final List<Map<String, Object>> DEFECTO = List.of(
            t("id", "recargar", "pagina", "umbral", "icono", "POTION", "nombre", "Recargar Frasco", "da", "recargar"),
            t("id", "frasco", "pagina", "umbral", "icono", "POTION", "nombre", "Frasco de Calma", "esencias", 10, "da", "frasco",
                    "limite-semana", 3),
            t("id", "cristal", "pagina", "umbral", "icono", "AMETHYST_SHARD", "nombre", "Cristal de Regreso", "esencias", 16,
                    "da", "cristal", "limite-semana", 5),
            t("id", "tintura", "pagina", "umbral", "icono", "POTION", "nombre", "Tintura de Ceniza x2", "esencias", 3,
                    "da", "dar:tintura", "cantidad", 2, "limite-dia", 5),
            // 1.10: el Reclamo, que llama al minijefe del bioma (Reclamo).
            t("id", "reclamo", "pagina", "umbral", "icono", "GOAT_HORN", "nombre", "Reclamo", "esencias", 6,
                    "da", "dar:reclamo", "limite-dia", 3),
            // 1.11: las llaves de las bovedas. La del Umbral, por Esencias con tope diario; la Ominosa, cambiando
            // cinco del Umbral y Esencias, una por semana.
            t("id", "llave-umbral", "pagina", "umbral", "icono", "TRIAL_KEY", "nombre", "Llave del Umbral", "esencias", 10,
                    "da", "dar:llave-umbral", "limite-dia", 2),
            t("id", "llave-ominosa", "pagina", "umbral", "icono", "OMINOUS_TRIAL_KEY", "nombre", "Llave Ominosa", "esencias", 40,
                    "entregar", List.of(t("objeto", "llave-umbral", "cantidad", 5)), "da", "dar:llave-ominosa", "limite-semana", 1),
            t("id", "llave", "pagina", "umbral", "icono", "TRIAL_KEY", "nombre", "Llave del Caos", "esencias", 40,
                    "da", "dar:llave", "limite-semana", 1, "requisito", "tope-llaves"),
            t("id", "salvoconducto", "pagina", "umbral", "icono", "PAPER", "nombre", "Salvoconducto del Insomne", "esencias", 64,
                    "da", "dar:salvoconducto", "limite-semana", 1, "requisito", "permiso:insomne.badge.unlocked"),
            t("id", "ofrenda", "pagina", "umbral", "icono", "BLAZE_POWDER", "nombre", "Ofrenda", "esencias", 100, "da", "ofrenda"),
            t("id", "depositar", "pagina", "umbral", "icono", "GHAST_TEAR", "nombre", "Depositar Esencias", "da", "depositar"),
            t("id", "camino", "pagina", "umbral", "icono", "COMPASS", "nombre", "Tu camino", "da", "camino"),
            t("id", "talisman", "pagina", "forja", "icono", "CLOCK", "nombre", "Talisman de Vigilia", "esencias", 40,
                    "mobcoins", 2000, "da", "dar:talisman", "limite-semana", 1),
            t("id", "gema", "pagina", "forja", "icono", "EMERALD", "nombre", "Gema de Calamidad", "esencias", 30,
                    "mobcoins", 1500, "da", "dar:gema", "limite-semana", 2),
            t("id", "grabado", "pagina", "forja", "icono", "FLINT", "nombre", "Grabado de Calamidad", "esencias", 32,
                    "mobcoins", 2500, "da", "dar:grabado", "limite-semana", 1),
            t("id", "ascua", "pagina", "forja", "icono", "BLAZE_POWDER", "nombre", "Ascua de Calamidad", "esencias", 24,
                    "esencias-incremento", 8, "mobcoins", 2000, "da", "dar:ascua"),
            t("id", "yelmo-manto", "pagina", "forja", "icono", "NETHERITE_HELMET", "esencias", 48, "mobcoins", 3000,
                    "credito", "sello:custodio-de-las-ruinas", "da", "forja:yelmo", "reposicion", t("esencias", 24, "mobcoins", 3000)),
            t("id", "coraza-manto", "pagina", "forja", "icono", "NETHERITE_CHESTPLATE", "esencias", 48, "mobcoins", 3000,
                    "credito", "sello:centinela-de-toba", "da", "forja:coraza", "reposicion", t("esencias", 24, "mobcoins", 3000)),
            t("id", "grebas-manto", "pagina", "forja", "icono", "NETHERITE_LEGGINGS", "esencias", 48, "mobcoins", 3000,
                    "credito", "sello:matriarca-tejedora", "da", "forja:grebas", "reposicion", t("esencias", 24, "mobcoins", 3000)),
            t("id", "soleretas-manto", "pagina", "forja", "icono", "NETHERITE_BOOTS", "esencias", 48, "mobcoins", 3000,
                    "credito", "sello:sanador-del-fango", "da", "forja:soleretas", "reposicion", t("esencias", 24, "mobcoins", 3000)),
            t("id", "hacha-heraldo", "pagina", "forja", "icono", "NETHERITE_AXE", "esencias", 64, "mobcoins", 5000,
                    "credito", "sello:heraldo-carmes", "da", "forja:hacha", "reposicion", t("esencias", 32, "mobcoins", 5000)),
            t("id", "mascara-eco", "pagina", "forja", "icono", "NETHERITE_HELMET", "esencias", 40, "mobcoins", 2500,
                    "credito", "marca", "creditos", 5, "da", "forja:mascara"),
            t("id", "filo-eco", "pagina", "forja", "icono", "NETHERITE_SWORD", "esencias", 48, "mobcoins", 3000,
                    "credito", "marca", "creditos", 10, "da", "forja:filo"),
            t("id", "guadana", "pagina", "forja", "icono", "NETHERITE_HOE", "esencias", 64, "mobcoins", 5000,
                    "credito", "fragmento", "creditos", 7, "da", "forja:guadana", "espera-dias", 30),
            // Las katanas de Ambush: piden Fragmentos de Masamune en fisico, y la Crimson ademas la Masamune.
            t("id", "masamune", "pagina", "forja", "icono", "NETHERITE_SWORD", "esencias", 64, "mobcoins", 5000,
                    "entregar", List.of(t("objeto", FragmentosMasamune.OBJETO, "cantidad", 5)), "da", "forja:masamune"),
            t("id", "crimson-masamune", "pagina", "forja", "icono", "COPPER_SWORD", "esencias", 96, "mobcoins", 8000,
                    "entregar", List.of(t("objeto", "masamune", "cantidad", 1), t("objeto", FragmentosMasamune.OBJETO, "cantidad", 5)),
                    "da", "forja:crimson"));

    // ================================================================ pruebas

    /** Una caja en memoria: saldo, creditos y MC falsos; se puede hacer fallar crear, cobrar o entregar. */
    static final class CajaPrueba implements Caja {
        final YamlConfiguration d = new YamlConfiguration();
        final Saldo saldo = new Saldo(d);
        final Map<UUID, Double> horas = new HashMap<>();
        final Creditos creditos = new Creditos(d, u -> horas.getOrDefault(u, 0.0));
        final Map<UUID, Long> mc = new HashMap<>();
        final List<String> entregados = new ArrayList<>();
        boolean mcDisponible = true, crear = true, cobrar = true, entregar = true, salvoconducto = false;
        int llaves = 4;
        long ahora = 1_790_000_000_000L;

        @Override public ConfigurationSection datos() { return d; }
        @Override public Saldo saldo() { return saldo; }
        @Override public Creditos creditos() { return creditos; }
        @Override public String semana() { return "2026-W39"; }
        @Override public String dia() { return "2026-09-26"; }
        @Override public long ahora() { return ahora; }
        @Override public int reposicionDias() { return 14; }
        @Override public boolean salvoconductoActivo() { return salvoconducto; }
        @Override public boolean permiso(UUID u, String permiso) { return false; }
        @Override public int llavesLibres(UUID u) { return llaves; }
        @Override public boolean mcDisponible() { return mcDisponible; }
        @Override public long mc(UUID u) { return mc.getOrDefault(u, 0L); }

        @Override
        public void cobrarMc(UUID u, long n, Consumer<Boolean> hecho) {
            if (n <= 0) {
                hecho.accept(true);
                return;
            }
            long tiene = mc(u);
            if (!cobrar || tiene < n) {
                hecho.accept(false);
                return;
            }
            mc.put(u, tiene - n);
            hecho.accept(true);
        }

        @Override public void devolverMc(UUID u, long n) { mc.put(u, mc(u) + n); }
        @Override public boolean creable(String objeto) { return crear; }

        @Override
        public boolean entregar(UUID u, String objeto, int n, String origen) {
            if (!entregar) return false;
            entregados.add(objeto + "x" + n);
            return true;
        }

        /** Lo que lleva encima cada uno: "uuid:objeto" -> cuantos (piezas de la Forja, Fragmentos...). */
        final Map<String, Integer> encima = new HashMap<>();

        /** Lo quitado por quitar, para devolverlo. */
        private record Quitado(String clave, int n) {
        }

        @Override public int cuantos(UUID u, String objeto) { return encima.getOrDefault(u + ":" + objeto, 0); }

        boolean lleva(UUID u, String objeto) {
            return cuantos(u, objeto) > 0;
        }

        @Override
        public Object quitar(UUID u, String objeto, int n) {
            String k = u + ":" + objeto;
            int hay = encima.getOrDefault(k, 0);
            if (n <= 0 || hay < n) return null;
            encima.put(k, hay - n);
            return new Quitado(k, n);
        }

        @Override public void devolver(UUID u, Object quitado) { if (quitado instanceof Quitado q) encima.merge(q.clave(), q.n(), Integer::sum); }

        @Override public void guardar() { }
    }

    /** Compra en la caja de prueba y devuelve el resultado (todo es sincrono en memoria). */
    private static Resultado probarEn(CajaPrueba c, Trueque t, UUID u) {
        Resultado[] r = new Resultado[1];
        comprar(c, t, u, "prueba", x -> r[0] = x);
        return r[0];
    }

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        Map<String, Trueque> ts = new HashMap<>();
        for (Trueque t : leer(DEFECTO)) ts.put(t.id(), t);
        h.igual("trueques de serie", 26, ts.size());
        Trueque ominosa = ts.get("llave-ominosa");
        h.ok("1.11: la Llave Ominosa pide entregar 5 Llaves del Umbral",
                ominosa != null && ominosa.pide("llave-umbral") == 5 && "llave-ominosa".equals(ominosa.objeto()));
        UUID u = Autotest.sintetico(311);
        CajaPrueba c = new CajaPrueba();

        // (1) Sin saldo: motivo esencias, faltan 10. Con saldo: cobra y entrega; 4.o frasco -> cupo.
        Resultado r = probarEn(c, ts.get("frasco"), u);
        h.igual("frasco sin saldo -> esencias", "esencias", r.motivo());
        h.igual("faltan 10", 10L, r.faltan());
        h.ok("sin saldo no se cobra ni se entrega", !r.ok() && !r.devuelto() && c.entregados.isEmpty());
        c.saldo.sumar(u, 30, "prueba");
        r = probarEn(c, ts.get("frasco"), u);
        h.ok("con 30 E el frasco sale", r.ok());
        h.igual("cobra 10: quedan 20", 20L, c.saldo.de(u));
        h.igual("y lo entrega", List.of("frascox1"), c.entregados);
        probarEn(c, ts.get("frasco"), u);
        probarEn(c, ts.get("frasco"), u);
        c.saldo.sumar(u, 10, "prueba");
        r = probarEn(c, ts.get("frasco"), u);
        h.igual("4.o frasco de la semana -> cupo", "cupo", r.motivo());
        h.igual("el cupo no cobra", 10L, c.saldo.de(u));

        // (2) Pieza del Manto: sin Sello -> credito; sin poder crearla -> todo devuelto.
        r = probarEn(c, ts.get("yelmo-manto"), u);
        h.igual("yelmo sin credito -> credito", "credito", r.motivo());
        h.igual("falta el Sello del Custodio", "sello:custodio-de-las-ruinas", r.faltan());
        c.creditos.sumar(u, "sello:custodio-de-las-ruinas", 1, "prueba", false);
        c.saldo.sumar(u, 38, "prueba");
        c.mc.put(u, 3000L);
        c.crear = false;
        r = probarEn(c, ts.get("yelmo-manto"), u);
        h.ok("sin MMOItems: fallo y devuelto", !r.ok() && r.devuelto() && "creacion".equals(r.motivo()));
        h.igual("saldo intacto", 48L, c.saldo.de(u));
        h.igual("credito intacto", 1, c.creditos.de(u, "sello:custodio-de-las-ruinas"));
        h.igual("MC intactas", 3000L, c.mc(u));
        h.igual("sin uso apuntado", 0, usosSemana(c, u, "yelmo-manto"));
        c.crear = true;
        c.entregar = false;
        r = probarEn(c, ts.get("yelmo-manto"), u);
        h.ok("entrega fallida: devuelto", r.devuelto() && "entrega".equals(r.motivo()));
        h.ok("devuelve Esencias, Sello y MC", c.saldo.de(u) == 48 && c.creditos.de(u, "sello:custodio-de-las-ruinas") == 1
                && c.mc(u) == 3000);
        c.entregar = true;
        c.cobrar = false;
        r = probarEn(c, ts.get("yelmo-manto"), u);
        h.ok("cobro de MC fallido: devuelto", r.devuelto() && "mc".equals(r.motivo()));
        h.ok("y Esencias y Sello vuelven", c.saldo.de(u) == 48 && c.creditos.de(u, "sello:custodio-de-las-ruinas") == 1);
        c.cobrar = true;
        r = probarEn(c, ts.get("yelmo-manto"), u);
        h.ok("con todo, el yelmo se forja", r.ok());
        h.ok("cobra 48 E, el Sello y 3.000 MC", c.saldo.de(u) == 0 && c.creditos.de(u, "sello:custodio-de-las-ruinas") == 0
                && c.mc(u) == 0);
        h.igual("forjas.<uuid>.yelmo apuntado", c.ahora, c.d.getLong("forjas." + u + ".yelmo"));

        // Reposicion (14 dias): 24 E + 3.000 MC, y la perdida se borra al reponer.
        c.d.set("perdidas." + u + ".yelmo", c.ahora - 86_400_000L);
        h.igual("reposicion a 24", 24, precio(c, ts.get("yelmo-manto"), u).esencias());
        c.d.set("perdidas." + u + ".yelmo", c.ahora - 15 * 86_400_000L);
        h.igual("pasados 14 dias vuelve a 48", 48, precio(c, ts.get("yelmo-manto"), u).esencias());
        c.d.set("perdidas." + u + ".yelmo", c.ahora - 86_400_000L);
        c.creditos.sumar(u, "sello:custodio-de-las-ruinas", 1, "prueba", false);
        c.saldo.sumar(u, 24, "prueba");
        c.mc.put(u, 3000L);
        r = probarEn(c, ts.get("yelmo-manto"), u);
        h.ok("reposicion hecha", r.ok() && r.precio().reposicion());
        h.ok("la perdida se borra", !c.d.isSet("perdidas." + u + ".yelmo"));

        // Sello Errante: vale por cualquier Sello con 48 h activas.
        c.creditos.sumar(u, Creditos.ERRANTE, 1, "prueba", false);
        c.saldo.sumar(u, 48, "prueba");
        c.mc.put(u, 3000L);
        r = probarEn(c, ts.get("coraza-manto"), u);
        h.igual("errante sin horas -> credito por horas", "horas", r.faltan());
        c.horas.put(u, 50.0);
        r = probarEn(c, ts.get("coraza-manto"), u);
        h.ok("errante con 50 h forja la coraza", r.ok() && Creditos.ERRANTE.equals(r.creditoUsado()));
        h.igual("y se gasta el errante", 0, c.creditos.de(u, Creditos.ERRANTE));

        // (3) Ascua: 24, 32, 40 en la misma semana.
        c.saldo.sumar(u, 96, "prueba");
        c.mc.put(u, 6000L);
        int[] precios = new int[3];
        for (int i = 0; i < 3; i++) {
            r = probarEn(c, ts.get("ascua"), u);
            precios[i] = r.ok() ? r.precio().esencias() : -1;
        }
        h.igual("ascua 24, 32, 40", "24,32,40", precios[0] + "," + precios[1] + "," + precios[2]);
        h.igual("saldo tras las tres", 0L, c.saldo.de(u));

        // (4) MobCoins sin verificar -> motivo mc, "proximamente", sin cobrar.
        c.saldo.sumar(u, 30, "prueba");
        c.mcDisponible = false;
        r = probarEn(c, ts.get("gema"), u);
        h.ok("gema con el Monedero sin verificar -> mc proximamente", "mc".equals(r.motivo()) && "proximamente".equals(r.faltan()));
        h.igual("sin cobrar Esencias", 30L, c.saldo.de(u));
        UUID pobre = Autotest.sintetico(312);
        r = probarEn(c, ts.get("gema"), pobre);
        h.ok("en gris dice mc antes que esencias", "mc".equals(r.motivo()) && "proximamente".equals(r.faltan()));
        c.mcDisponible = true;
        c.mc.put(u, 1000L);
        r = probarEn(c, ts.get("gema"), u);
        h.igual("gema con 1.000 MC -> faltan 500", 500L, r.faltan());

        // (5) Tope de llaves lleno: cupo SIN cobrar (se mira antes).
        c.saldo.sumar(u, 10, "prueba");
        c.llaves = 0;
        r = probarEn(c, ts.get("llave"), u);
        h.ok("llave con el tope lleno -> cupo", "cupo".equals(r.motivo()) && "tope-llaves".equals(r.faltan()));
        h.igual("y no cobra las 40", 40L, c.saldo.de(u));
        c.llaves = 4;
        r = probarEn(c, ts.get("llave"), u);
        h.ok("con sitio, la llave sale", r.ok());
        r = probarEn(c, ts.get("llave"), u);
        h.igual("una llave por semana en el altar", "cupo", r.motivo());

        // Limite diario (Tintura 5/dia), Salvoconducto apagado y Guadana cada 30 dias.
        c.saldo.sumar(u, 100, "prueba");
        for (int i = 0; i < 5; i++) probarEn(c, ts.get("tintura"), u);
        h.igual("6.a tintura del dia -> cupo", "cupo", probarEn(c, ts.get("tintura"), u).motivo());
        h.igual("tintura x2 por compra", "tinturax2", c.entregados.get(c.entregados.size() - 1));
        h.igual("salvoconducto apagado -> requisito", "requisito", probarEn(c, ts.get("salvoconducto"), u).motivo());
        c.d.set("forjas." + u + ".guadana", c.ahora - 86_400_000L);
        c.creditos.sumar(u, "fragmento", 7, "prueba", false);
        c.mc.put(u, 5000L);
        r = probarEn(c, ts.get("guadana"), u);
        h.igual("2.a Guadana en 30 dias -> cupo con 29 dias", "29d", r.faltan());
        c.d.set("perdidas." + u + ".guadana", c.ahora);
        r = probarEn(c, ts.get("guadana"), u);
        h.ok("reponer la Guadana no espera", r.ok() && r.precio().reposicion());
        h.igual("y gasta los 7 Fragmentos", 0, c.creditos.de(u, "fragmento"));

        // 1.10: el Reclamo, tres al dia por 6 Esencias cada uno.
        c.saldo.sumar(u, 30, "prueba");
        long antesReclamos = c.saldo.de(u);
        for (int i = 0; i < 3; i++) probarEn(c, ts.get("reclamo"), u);
        h.igual("tres Reclamos: 18 Esencias", antesReclamos - 18, c.saldo.de(u));
        h.igual("y se entregan por dar:reclamo", "reclamox1", c.entregados.get(c.entregados.size() - 1));
        h.igual("4.o Reclamo del dia -> cupo", "cupo", probarEn(c, ts.get("reclamo"), u).motivo());

        h.ok("la prueba no toca hardcore-datos.yml", !hc.datos().isSet("altar.usos.2026-W39." + u)
                && !hc.datos().isSet("esencias." + u));
        h.ok("/calamity altar registrado", Subcomandos.staff().nombres(null).contains("altar"));
        h.ok("el Camino se abre desde un NPC (open <player> path)", Subcomandos.jugador().nombres(null).contains("path"));
        h.igual("nombre con tildes", "Talismán de Vigilia", nombre(ts.get("talisman")));
        h.igual("nombre de una pieza sin nombre", "Yelmo de Calamidad", nombre(ts.get("yelmo-manto")));
        return h.lineas();
    }
}
