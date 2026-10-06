package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Calamity 1.16.2 · El menu de Maren, el NPC de los contratos del dia (lo abre "calamity open <p> contracts", el
 * clic de Citizens; ver Npcs). Dosa: "vamos a separar los contratos de Oren a otro NPC, porque es confuso
 * llevar tienda y contratos en uno solo". Hasta la 1.16.1 esto era la vista Contratos del Mercado de Oren
 * (MenuTasador); se sacó tal cual, y Oren se queda solo con la tienda.
 *
 *   portada (36)   "CALAMITY | Contratos". Fila 1: los contratos de hoy, centrados (un clic acepta una
 *                  oferta o pide otra copia del pergamino perdido). Fila 2: la semana (21) y Cambiar uno
 *                  (23), centrados (Marco.columnas(2)). Sin Volver ni Cerrar: es la primera pantalla y
 *                  Esc cierra. La fila 3 es de cristal, de margen (1.16.1).
 *   elegir (36)    Cambiar uno, paso 1: los que se pueden cambiar en la fila 1 y Volver (22) a la portada.
 *   cambiar (27)   Confirmar: No (11) · el contrato y lo que cuesta (13) · Si (15). Los dos vuelven a la
 *                  portada; un toque en un contrato nunca lo cambia.
 *
 * Todo lo de verdad (aceptar, la copia, cambiar, la semana) sigue en Contratos; aqui solo se pinta y se
 * pulsa. Aceptar y pedir la copia solo valen en el spawn de Calamity (Contratos.lugarParaAceptar): Maren
 * tiene que estar ahi, como Oren. Reglas de Marco: marco de cristal negro, Volver abajo en el centro, la
 * ultima linea dice que hace el clic y solo clic izquierdo (Bedrock: un toque).
 */
final class MenuContratos implements Listener {

    private static final long ESPERA_MS = 500;
    /** Las confirmaciones: 3 filas. */
    static final int TAMANO = MenuTasador.TAMANO;
    /** Las vistas con botones abajo (portada y elegir): una fila mas de cristal debajo, de margen (1.16.1). */
    static final int TAMANO_MARGEN = MenuTasador.TAMANO_MARGEN;
    /** Portada, abajo: la semana y Cambiar uno, centrados (columnas 3 y 5 de Marco.columnas(2)). */
    static final int SEMANA = 21, CAMBIAR = 23;
    /** Elegir: Volver abajo en el centro (Marco.abajo). */
    static final int SALIR = 22;
    /** Confirmar: No, lo que se confirma y Si, en la fila del medio. */
    static final int NO = 11, CENTRO = 13, SI = 15;

    static final String PORTADA = "contratos", V_ELEGIR = "elegir", V_CAMBIAR = "cambiar";

    /** Lo que se dice si el modulo de contratos falla al dar el pergamino. */
    static final String FALLO_PERGAMINO = "Ahora mismo Maren no puede darte el pergamino.";
    /** Por que no se acepta una oferta lejos del spawn de Calamity (la ultima linea del contrato). */
    static final String ACEPTA_EN_SPAWN = "Se acepta con Maren, en el spawn.";

    /** Nuestra ventana. pantalla: la vista; hueco: el contrato que se cambia (en "cambiar"). */
    record Vista(String pantalla, Map<Integer, String> acciones, int hueco) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final Hardcore hc;
    private final Map<UUID, Long> ultimoClic = new HashMap<>();
    private final Set<BukkitTask> tareas = new HashSet<>();

    MenuContratos(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
    }

    void parar() {
        HandlerList.unregisterAll(this);
        for (BukkitTask t : tareas) t.cancel();
        tareas.clear();
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Vista) p.closeInventory();
        }
        ultimoClic.clear();
    }

    private void tarea(Runnable r) {
        final BukkitTask[] t = new BukkitTask[1];
        t[0] = hc.plugin().getServer().getScheduler().runTask(hc.plugin(), () -> {
            tareas.remove(t[0]);
            hc.seguro("contratos", r);
        });
        tareas.add(t[0]);
    }

    // ------------------------------------------------------------------ abrir y pintar

    /** conSonido: el del NPC al abrirlo. */
    void abrir(Player p, boolean conSonido) {
        abrirVista(p, PORTADA);
        if (conSonido) Marco.sonar(p, "item.book.page_turn", 0.8f, 0.8f);
    }

    static Marco.Titulo titulo(String pantalla) {
        return switch (pantalla) {
            case V_ELEGIR, V_CAMBIAR -> Marco.T_CAMBIAR;
            default -> Marco.T_CONTRATOS;
        };
    }

    static int tamano(String pantalla) {
        return switch (pantalla) {
            case PORTADA, V_ELEGIR -> TAMANO_MARGEN;
            default -> TAMANO;
        };
    }

    /**
     * La accion del Volver de abajo en el centro de esa vista, o null si no lleva: la portada es la primera
     * pantalla (Esc cierra) y la confirmacion vuelve con su No.
     */
    static String volverDe(String pantalla) {
        return V_ELEGIR.equals(pantalla) ? "volver" : null;
    }

    private void abrirVista(Player p, String pantalla) {
        // Calamity 1.11: dentro de Calamity, con la libreta de otro dia y nada a medias, Maren da la de hoy
        // (Contratos.renovar; hasta la 1.16.1 lo hacia el menu de Oren). Sin libreta caducada es mirar una
        // seccion y sus tres huecos.
        Contratos con = hc.contratos();
        if (con != null && hc.esHardcore(p)) hc.seguro("contratos", () -> con.renovar(p));
        Vista v = new Vista(pantalla, new HashMap<>(), 0);
        Inventory inv = hc.plugin().getServer().createInventory(v, tamano(pantalla), titulo(pantalla).componente());
        pintar(inv, p, v);
        p.openInventory(inv);
    }

    private void repintar(Player p) {
        if (!p.isOnline()) return;
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof Vista v && !v.pantalla().equals(V_CAMBIAR)) pintar(top, p, v);
    }

    private void pintar(Inventory inv, Player p, Vista v) {
        inv.clear();
        v.acciones().clear();
        if (v.pantalla().equals(V_ELEGIR)) vistaElegir(inv, p, v);
        else portada(inv, p, v);
        String volver = volverDe(v.pantalla());
        if (volver != null) {
            inv.setItem(SALIR, Marco.volver("a tus contratos"));
            v.acciones().put(SALIR, volver);
        }
        Marco.rellenar(inv);
    }

    /** El icono de un contrato por lo que pide (el evento del pool). */
    static Material iconoContrato(String evento) {
        return switch (evento) {
            case "mob" -> Material.IRON_SWORD;
            case "destacado" -> Material.GOLDEN_SWORD;
            case "minijefe" -> Material.WITHER_SKELETON_SKULL;
            case "cofre" -> Material.CHEST_MINECART;
            case "reliquia-ii", "tasa-ii" -> Material.MANGROVE_PROPAGULE;
            case "minutos" -> Material.CLOCK;
            case "minutos-limite" -> Material.SOUL_LANTERN;
            case "minutos-sin-frasco" -> Material.GLASS_BOTTLE;
            case "eco-valido" -> Material.ECHO_SHARD;
            case "redimir" -> Material.AMETHYST_SHARD;
            default -> Material.PAPER;
        };
    }

    // ------------------------------------------------------------------ portada: los contratos de hoy

    /**
     * Calamity 1.12.1 · Los contratos de hoy en la fila del medio. Una oferta se acepta con un clic (y llega
     * su pergamino); uno aceptado cuyo pergamino no llevas, con un clic Maren te da otra copia; todo en el
     * spawn de Calamity. Abajo: la semana y Cambiar uno (con su confirmacion: un toque en un contrato nunca
     * lo cambia).
     */
    private void portada(Inventory inv, Player p, Vista v) {
        UUID u = p.getUniqueId();
        Contratos con = hc.contratos();
        if (con == null || !hc.valor("contratos", con::activo, false)) {
            inv.setItem(CENTRO, Marco.icono(Material.PAPER, Component.text("Maren no tiene contratos ahora", Paleta.TENUE),
                    List.of(Marco.tenue("Vuelve más adelante.")), false));
            return;
        }
        List<Contratos.Estado> lista = hc.valor("contratos", () -> con.estados(p), List.of());
        int precio = con.precioCambio(u), gratis = con.cambiosGratis(u);
        boolean papel = hc.valor("contratos", con::pergaminoActivo, false);
        boolean lugar = hc.valor("contratos", () -> con.lugarParaAceptar(p), false);
        int activos = hc.valor("contratos", () -> con.activosDe(p), 0), max = con.activosMax();
        Set<Integer> lleva = papel ? hc.valor("contratos", () -> con.llevados(p), Set.<Integer>of()) : Set.of();
        int[] casillas = MenuTasador.casillasFila(lista.size());
        boolean hayCambiable = false;
        for (int k = 0; k < casillas.length; k++) {
            Contratos.Estado e = lista.get(k);
            Contratos.Def d = e.def();
            hayCambiable |= !e.cumplido() && !e.cobrado();
            List<Component> lore = new ArrayList<>();
            if (!e.oferta() || !papel) lore.add(Marco.barra(e.progreso(), d.objetivo()));
            lore.add(MenuTasador.fila("Paga", Contratos.premio(d)));
            lore.add(MenuTasador.fila("Dura", "hasta la medianoche"));
            if (d.corto()) lore.add(Marco.tenue("Es corto: se hace en una entrada rápida."));
            lore.add(Marco.tenue(!papel ? "Se cobra al salir vivo." : Contratos.seCobraAlSalir(d) ? Pergaminos.COBRO_VENTA
                    : Pergaminos.COBRO_DENTRO));
            lore.add(Component.empty());
            // La ultima linea: lo que hace el clic o por que no hace nada.
            String accion = null;
            if (e.cobrado()) {
                lore.add(Marco.tiene("Ya lo cobraste."));
            } else if (e.cumplido()) {
                // Con pergaminos solo espera el de Reliquias (los demas se cobran al cumplirlos); sin ellos, todos
                // se cobran al salir vivo.
                lore.add(Component.text(papel && Contratos.seCobraAlSalir(d) ? "Cumplido: se cobra al venderlas."
                        : "Cumplido: lo cobras al salir vivo.", Paleta.CIFRA));
            } else if (!papel) {
                lore.add(Marco.tenue("Si mueres, vuelve a empezar."));
            } else if (e.oferta()) {
                lore.add(Marco.tenue("Al aceptarlo te da su pergamino:"));
                lore.add(Marco.tenue("llévalo encima para que cuente."));
                if (!lugar) lore.add(Marco.porQueNo(ACEPTA_EN_SPAWN));
                else if (activos >= max) lore.add(Marco.porQueNo("Ya llevas " + max + " activos."));
                else {
                    lore.add(Marco.accion("Clic para aceptarlo"));
                    accion = "p:" + e.hueco();
                }
            } else if (lleva.contains(e.hueco())) {
                lore.add(Marco.tiene("Aceptado: llevas su pergamino."));
            } else if (!con.recuperarPerdido()) {
                lore.add(Component.text("Perdiste su pergamino.", Paleta.AVISO));
                lore.add(Marco.tenue("Maren no da otro: puedes cambiarlo."));
            } else if (!lugar) {
                lore.add(Component.text("No llevas su pergamino.", Paleta.AVISO));
                lore.add(Marco.tenue("Maren te da otro en el spawn."));
            } else {
                lore.add(Component.text("No llevas su pergamino.", Paleta.AVISO));
                lore.add(Marco.accion("Clic para pedirle otro"));
                accion = "p:" + e.hueco();
            }
            TextColor color = e.cobrado() ? Paleta.TENUE : e.cumplido() ? Paleta.BIEN : e.oferta() ? Paleta.TEXTO : Paleta.DETALLE;
            Material icono = e.cobrado() ? Material.MAP : iconoContrato(d.evento());
            inv.setItem(casillas[k], Marco.icono(icono, Component.text(d.texto(), color), lore,
                    (e.cumplido() && !e.cobrado()) || accion != null));
            if (accion != null) v.acciones().put(casillas[k], accion);
        }
        if (lista.isEmpty()) {
            inv.setItem(CENTRO, Marco.icono(Material.PAPER, Component.text("Hoy no tienes contratos", Paleta.TENUE),
                    List.of(Marco.tenue("Vuelve a mirar mañana.")), false));
        }

        int[] semana = con.semanaDe(u);
        List<Component> sl = new ArrayList<>();
        sl.add(Marco.barra(semana[0], semana[1]));
        sl.add(Marco.texto("Si cobras " + semana[1] + " en la semana,"));
        sl.add(Marco.texto("Maren te da la Llave del Caos."));
        if (papel) {
            sl.add(Component.empty());
            sl.add(MenuTasador.fila("Activos", activos + " de " + max));
            sl.add(Marco.tenue("Un aceptado ocupa su sitio hasta"));
            sl.add(Marco.tenue("cobrarlo, aunque pierdas el papel."));
        }
        inv.setItem(SEMANA, Marco.icono(Material.TRIAL_KEY, Component.text("Esta semana: ", Paleta.TEXTO)
                .append(Component.text(Math.min(semana[0], semana[1]) + "/" + semana[1], Paleta.CIFRA)), sl, semana[0] >= semana[1]));

        List<Component> cl = new ArrayList<>();
        cl.add(Marco.tenue("Maren te da otro encargo en lugar"));
        cl.add(Marco.tenue("de uno de estos. Pierdes lo que"));
        cl.add(Marco.tenue("llevas hecho de ese."));
        cl.add(Component.empty());
        cl.add(Marco.tenue(gratis > 0 ? "Hoy te " + (gratis == 1 ? "queda 1 cambio gratis." : "quedan " + gratis + " cambios gratis.")
                : "Cambiar uno cuesta " + Marco.esencias(precio) + "."));
        cl.add(hayCambiable ? Marco.accion("Clic para elegir cuál") : Marco.tenue("Ahora no hay ninguno que cambiar."));
        inv.setItem(CAMBIAR, Marco.icono(Material.FEATHER, Component.text("Cambiar un contrato", hayCambiable ? Paleta.DETALLE : Paleta.TENUE),
                cl, false));
        if (hayCambiable) v.acciones().put(CAMBIAR, "ver:" + V_ELEGIR);
    }

    // ------------------------------------------------------------------ cambiar un contrato

    /** Cambiar un contrato, paso 1: los que se pueden cambiar en la fila del medio; un clic abre la confirmacion. */
    private void vistaElegir(Inventory inv, Player p, Vista v) {
        Contratos con = hc.contratos();
        List<Contratos.Estado> lista = new ArrayList<>();
        if (con != null) {
            for (Contratos.Estado e : hc.valor("contratos", () -> con.estados(p), List.<Contratos.Estado>of())) {
                if (!e.cumplido() && !e.cobrado()) lista.add(e);
            }
        }
        if (lista.isEmpty()) {
            inv.setItem(CENTRO, Marco.icono(Material.PAPER, Component.text("No hay ninguno que cambiar", Paleta.TENUE),
                    List.of(Marco.tenue("Los cumplidos y cobrados no se cambian.")), false));
            return;
        }
        int[] casillas = MenuTasador.casillasFila(lista.size());
        for (int k = 0; k < casillas.length; k++) {
            Contratos.Estado e = lista.get(k);
            inv.setItem(casillas[k], Marco.icono(iconoContrato(e.def().evento()), Component.text(e.def().texto(), Paleta.TEXTO),
                    List.of(Marco.barra(e.progreso(), e.def().objetivo()), Component.empty(), Marco.accion("Clic para cambiar este")), false));
            v.acciones().put(casillas[k], "c:" + e.hueco());
        }
    }

    /** Confirmar el cambio de un contrato: No (11), el contrato con lo que cuesta (13) y Si (15). */
    private void abrirCambiar(Player p, int hueco) {
        Contratos con = hc.contratos();
        if (con == null) return;
        Contratos.Estado e = null;
        for (Contratos.Estado x : hc.valor("contratos", () -> con.estados(p), List.<Contratos.Estado>of())) {
            if (x.hueco() == hueco) e = x;
        }
        if (e == null || e.cumplido() || e.cobrado()) {
            abrirVista(p, PORTADA);
            return;
        }
        Vista v = new Vista(V_CAMBIAR, new HashMap<>(), hueco);
        Inventory inv = hc.plugin().getServer().createInventory(v, tamano(V_CAMBIAR), titulo(V_CAMBIAR).componente());
        int precio = con.precioCambio(p.getUniqueId());
        Saldo s = hc.saldo();
        long saldo = s == null ? 0 : s.de(p.getUniqueId());
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.barra(e.progreso(), e.def().objetivo()));
        lore.add(Marco.tenue("Si lo cambias, pierdes lo que llevas."));
        lore.add(Component.empty());
        if (precio == 0) lore.add(Marco.tiene("Gratis: es tu cambio gratis de hoy."));
        else {
            lore.add(MenuTasador.fila("Cuesta", Marco.esencias(precio)));
            lore.add(MenuTasador.fila("Tienes", Altar.miles(saldo)));
            lore.add(MenuTasador.fila("Te quedarían", Altar.miles(Math.max(0, saldo - precio))));
        }
        inv.setItem(CENTRO, Marco.icono(iconoContrato(e.def().evento()), Component.text(e.def().texto(), Paleta.TEXTO), lore, false));
        inv.setItem(NO, Marco.icono(Material.RED_CONCRETE, Component.text("No, déjalo", Marco.NO),
                List.of(Marco.tenue("Vuelves a tus contratos sin"), Marco.tenue("cambiar nada."), Component.empty(),
                        Marco.accion("Clic para volver")), false));
        v.acciones().put(NO, "no");
        inv.setItem(SI, Marco.icono(Material.LIME_CONCRETE, Component.text("Sí, cámbialo", Marco.SI), List.of(
                Marco.tenue("Maren te da otro encargo en su"), Marco.tenue("lugar, como oferta: si lo quieres,"),
                Marco.tenue("lo aceptas."), Component.empty(), Marco.accion("Clic para cambiarlo")), false));
        v.acciones().put(SI, "si");
        Marco.rellenar(inv);
        p.openInventory(inv);
        Marco.sonar(p, "block.note_block.hat", 0.5f, 1.3f);
    }

    // ------------------------------------------------------------------ clics

    @EventHandler
    public void alClic(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Vista v)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClick() != ClickType.LEFT) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getInventory().getSize()) return;
        String accion = v.acciones().get(slot);
        if (accion == null) return;
        long ahora = System.currentTimeMillis();
        Long antes = ultimoClic.get(p.getUniqueId());
        if (antes != null && ahora - antes < ESPERA_MS) return;
        ultimoClic.put(p.getUniqueId(), ahora);
        hc.seguro("contratos", () -> accion(p, v, accion));
    }

    private void accion(Player p, Vista v, String accion) {
        if (accion.startsWith("ver:")) {
            String vista = accion.substring(4);
            Marco.sonidoPestana(p);
            tarea(() -> abrirVista(p, vista));
            return;
        }
        if (accion.startsWith("c:")) {
            int hueco = Integer.parseInt(accion.substring(2));
            tarea(() -> abrirCambiar(p, hueco));
            return;
        }
        if (accion.startsWith("p:")) {
            // 1.12.1: aceptar la oferta (o pedir otra copia del pergamino perdido). Si no, se le dice por que.
            int hueco = Integer.parseInt(accion.substring(2));
            Contratos con = hc.contratos();
            if (con == null) return;
            // valor() cambia un null por el defecto: "" es "hecho" y el defecto, un fallo del modulo.
            String no = hc.valor("contratos", () -> {
                String r = con.pedirDesdeMenu(p, hueco);
                return r == null ? "" : r;
            }, FALLO_PERGAMINO);
            if (no.isEmpty()) {
                Marco.sonar(p, "item.book.page_turn", 0.8f, 1.0f);
            } else {
                p.sendMessage(ComandoCalamity.mensaje(no));
                Marco.sonidoNo(p);
            }
            tarea(() -> repintar(p));
            return;
        }
        switch (accion) {
            case "volver", "no" -> {
                Marco.sonar(p, "ui.button.click", 0.45f, 0.8f);
                tarea(() -> abrirVista(p, PORTADA));
            }
            case "si" -> {
                Contratos con = hc.contratos();
                if (con != null && con.cambiar(p, v.hueco())) Marco.sonar(p, "item.book.page_turn", 0.8f, 1.2f);
                else Marco.sonidoNo(p);
                tarea(() -> abrirVista(p, PORTADA));
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Vista) e.setCancelled(true);
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        ultimoClic.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ autotest (en "menus")

    static void autotest(Autotest.Hoja h) {
        int[] dos = Marco.columnas(2);
        h.igual("Maren: el titulo de su portada", "CALAMITY | Contratos", titulo(PORTADA).texto());
        h.igual("Maren: cambiar uno lleva su titulo", "CALAMITY | Cambiar contrato", titulo(V_ELEGIR).texto());
        for (String vista : List.of(PORTADA, V_ELEGIR, V_CAMBIAR)) {
            h.ok("Maren: el titulo de '" + vista + "' cabe", titulo(vista).ancho() <= Marco.ANCHO_TITULO);
        }
        h.igual("Maren: portada con una fila de cristal debajo", 36, tamano(PORTADA));
        h.igual("Maren: elegir con una fila de cristal debajo", 36, tamano(V_ELEGIR));
        h.igual("Maren: la confirmacion, en 3 filas", 27, tamano(V_CAMBIAR));
        h.ok("Maren: la semana y Cambiar uno, centrados en la fila de botones",
                SEMANA == 18 + dos[0] && CAMBIAR == 18 + dos[1] && SEMANA + CAMBIAR == 2 * Marco.abajo(TAMANO));
        h.ok("Maren: botones por encima de la fila de margen", SEMANA < TAMANO_MARGEN - 9 && CAMBIAR < TAMANO_MARGEN - 9
                && SALIR < TAMANO_MARGEN - 9);
        h.igual("Maren: su portada no lleva Volver (Esc cierra)", null, volverDe(PORTADA));
        h.igual("Maren: elegir vuelve a la portada", "volver", volverDe(V_ELEGIR));
        h.igual("Maren: la confirmacion vuelve con su No", null, volverDe(V_CAMBIAR));
        h.ok("Maren: Volver abajo en el centro", SALIR == Marco.abajo(TAMANO));
        h.ok("Maren: confirmar, No, lo confirmado y Si en la fila del medio", NO == 11 && CENTRO == 13 && SI == 15);
        List<Integer> todas = new ArrayList<>(List.of(SEMANA, CAMBIAR, SALIR));
        for (int c : MenuTasador.casillasFila(7)) todas.add(c);
        boolean bien = new HashSet<>(todas).size() == todas.size();
        for (int c : todas) bien &= c >= 9 && c < TAMANO_MARGEN - 9;
        h.ok("Maren: ninguna casilla repetida ni en el marco de arriba o abajo", bien);
        h.igual("Maren: tres contratos con aire (11, 13 y 15)", List.of(11, 13, 15),
                java.util.Arrays.stream(MenuTasador.casillasFila(3)).boxed().toList());
        h.ok("Maren: los textos dicen Maren y no Oren", FALLO_PERGAMINO.contains("Maren") && ACEPTA_EN_SPAWN.contains("Maren")
                && !FALLO_PERGAMINO.contains("Oren") && !ACEPTA_EN_SPAWN.contains("Oren"));
        h.ok("Maren: cada contrato con su icono", iconoContrato("minijefe") == Material.WITHER_SKELETON_SKULL
                && iconoContrato("nada") == Material.PAPER);
    }
}
