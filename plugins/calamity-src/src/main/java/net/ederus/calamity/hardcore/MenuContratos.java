package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
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
import org.bukkit.inventory.ItemStack;
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
 * Calamity 1.16.3 · Dosa: "La interfaz no me convence. Los contratos que salgan con el pergamino
 * correspondiente y la interfaz mas separada de los contratos, o sea los botones de informacion y tal".
 * Cada contrato se ve como su pergamino (tarjeta): el mismo diseño de estandarte (Pergaminos.material), el
 * mismo nombre, y la cabecera, el Objetivo, el Progreso y el Premio con los colores del pergamino
 * (Pergaminos.cuerpo); debajo, su estado y la ultima linea. Entre los contratos y los botones queda una fila
 * entera de cristal:
 *
 *   portada (45)   "CALAMITY | Contratos"
 *                  fila 0  cristal (marco)
 *                  fila 1  los contratos de hoy, centrados (10-16; con tres, 11, 13 y 15). Un clic acepta
 *                          una oferta o pide otra copia del pergamino perdido
 *                  fila 2  cristal entera: separa los contratos de los botones
 *                  fila 3  Esta semana (30) y Cambiar un contrato (32), centrados (Marco.columnas(2))
 *                  fila 4  cristal (margen, 1.16.1)
 *                  Sin Volver ni Cerrar: es la primera pantalla y Esc cierra.
 *   elegir (45)    "CALAMITY | Cambiar contrato", paso 1: los que se pueden cambiar en la fila 1, cristal en
 *                  la 2, Volver a la portada (31) en la 3 y cristal en la 4.
 *   cambiar (27)   Confirmar: No (11) · el contrato y lo que cuesta (13) · Si (15). Los dos vuelven a la
 *                  portada; un toque en un contrato nunca lo cambia.
 *
 * El icono de un contrato es el de su pergamino en todos sus estados (oferta, aceptado, cumplido, cobrado): el
 * estado se lee en el color y el texto, nunca en otro icono. Brilla solo lo que se puede hacer ya (aceptar una
 * oferta, pedir la copia) y lo cumplido; uno ya cobrado se ve en gris.
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
    /**
     * 1.16.3 · La portada y elegir: 5 filas. Los contratos en la 1, una fila entera de cristal (la 2) que los
     * separa de los botones (la 3) y la de margen debajo (la 4; 1.16.1).
     */
    static final int TAMANO_GRANDE = 45;
    /** Donde empieza cada fila de la portada y de elegir. */
    static final int FILA_CONTRATOS = MenuTasador.FILA, FILA_SEPARACION = 18, FILA_BOTONES = 27, FILA_MARGEN = 36;
    /** Portada, fila de botones: la semana y Cambiar uno, centrados (columnas 3 y 5 de Marco.columnas(2)). */
    static final int SEMANA = FILA_BOTONES + 3, CAMBIAR = FILA_BOTONES + 5;
    /** Elegir: Volver en el centro de la fila de botones. */
    static final int SALIR = FILA_BOTONES + 4;
    /** Confirmar: No, lo que se confirma y Si, en la fila del medio. CENTRO es tambien el aviso sin contratos. */
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

    /**
     * 1.16.3 · Un contrato tal y como se pinta: el nombre, el lore, la accion del clic (null si no hace nada) y
     * si brilla. Sin servidor: lo prueba el autotest.
     */
    record Tarjeta(Component nombre, List<Component> lore, String accion, boolean brillo) {
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
            case PORTADA, V_ELEGIR -> TAMANO_GRANDE;
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
        // (Contratos.renovar; tambien lo hace el menu de Oren al abrirlo). Sin libreta caducada es mirar una
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

    // ------------------------------------------------------------------ el contrato: su pergamino

    /**
     * 1.16.3 · El icono de un contrato: el de su pergamino (Pergaminos.material: la calavera los de mobs, el
     * globo los de cofres...), igual en todos sus estados.
     */
    static Material icono(Contratos.Def d) {
        return Pergaminos.material(d);
    }

    /**
     * 1.16.3 · El contrato como su pergamino: su diseño de estandarte, con la linea que el juego le anade al
     * diseño oculta (Pergaminos.ocultarDiseno), y el nombre, el lore y el brillo que se digan.
     */
    static ItemStack pergamino(Contratos.Def d, Component nombre, List<Component> lore, boolean brillo) {
        ItemStack it = Marco.icono(icono(d), nombre, lore, brillo);
        Pergaminos.ocultarDiseno(it);
        return it;
    }

    /** El nombre de su pergamino ("Contrato: Mobs", con el degradado de los contratos); uno ya cobrado, en gris. */
    static Component nombre(Contratos.Estado e) {
        return e.cobrado() ? Paleta.T_NEUTRO.nombre(Pergaminos.titulo(e.def())) : Pergaminos.nombre(e.def());
    }

    /**
     * Lo de arriba de su pergamino (Pergaminos.cuerpo: cabecera, Objetivo, Progreso y Premio) sin la historia.
     * Una oferta con pergaminos no lleva Progreso: hasta aceptarla no cuenta nada. Uno ya cobrado, en gris.
     */
    static Ficha ficha(Contratos.Estado e, boolean papel) {
        return Pergaminos.cuerpo(e.def(), e.progreso(), false, !e.oferta() || !papel,
                e.cobrado() ? Paleta.T_NEUTRO : Ficha.tono("contrato"));
    }

    /**
     * 1.16.3 · Un contrato de la portada: lo de su pergamino arriba (ficha) y, si aun no esta cumplido, cuanto
     * dura y como se cobra; debajo, el estado y la ultima linea, que dice que hace el clic o por que no hace
     * nada. papel: los pergaminos encendidos; lugar: esta en el spawn de Calamity; activos y max: los aceptados
     * sin cobrar que lleva y el tope; lleva: lleva su pergamino; recupera: Maren da otra copia del perdido.
     */
    static Tarjeta tarjeta(Contratos.Estado e, boolean papel, boolean lugar, int activos, int max, boolean lleva,
                           boolean recupera) {
        Contratos.Def d = e.def();
        Ficha f = ficha(e, papel);
        if (!e.cumplido() && !e.cobrado()) {
            f.hueco().nota("Dura hasta la medianoche.");
            if (d.corto()) f.nota("Es corto: basta una entrada rápida.");
            f.nota(!papel ? "Se cobra al salir vivo." : Contratos.seCobraAlSalir(d) ? Pergaminos.COBRO_VENTA
                    : Pergaminos.COBRO_DENTRO);
        }
        List<Component> lore = new ArrayList<>(f.lore());
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
        } else if (lleva) {
            lore.add(Marco.tiene("Aceptado: llevas su pergamino."));
        } else if (!recupera) {
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
        return new Tarjeta(nombre(e), lore, accion, (e.cumplido() && !e.cobrado()) || accion != null);
    }

    /** 1.16.3 · Un contrato en elegir (Cambiar uno, paso 1): su pergamino y "Clic para cambiar este". */
    static Tarjeta tarjetaCambiar(Contratos.Estado e, boolean papel) {
        List<Component> lore = new ArrayList<>(ficha(e, papel).lore());
        lore.add(Component.empty());
        lore.add(Marco.accion("Clic para cambiar este"));
        return new Tarjeta(nombre(e), lore, "c:" + e.hueco(), false);
    }

    // ------------------------------------------------------------------ portada: los contratos de hoy

    /**
     * Calamity 1.12.1 · Los contratos de hoy en la fila 1, cada uno con su pergamino (1.16.3). Una oferta se
     * acepta con un clic (y llega su pergamino); uno aceptado cuyo pergamino no llevas, con un clic Maren te da
     * otra copia; todo en el spawn de Calamity. En la fila de botones, debajo de la de cristal: la semana y
     * Cambiar uno (con su confirmacion: un toque en un contrato nunca lo cambia).
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
        boolean recupera = hc.valor("contratos", con::recuperarPerdido, false);
        int activos = hc.valor("contratos", () -> con.activosDe(p), 0), max = con.activosMax();
        Set<Integer> lleva = papel ? hc.valor("contratos", () -> con.llevados(p), Set.<Integer>of()) : Set.of();
        int[] casillas = MenuTasador.casillasFila(lista.size());
        boolean hayCambiable = false;
        for (int k = 0; k < casillas.length; k++) {
            Contratos.Estado e = lista.get(k);
            hayCambiable |= !e.cumplido() && !e.cobrado();
            Tarjeta t = tarjeta(e, papel, lugar, activos, max, lleva.contains(e.hueco()), recupera);
            inv.setItem(casillas[k], pergamino(e.def(), t.nombre(), t.lore(), t.brillo()));
            if (t.accion() != null) v.acciones().put(casillas[k], t.accion());
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

    /** Cambiar un contrato, paso 1: los que se pueden cambiar en la fila 1, con su pergamino; un clic abre la confirmacion. */
    private void vistaElegir(Inventory inv, Player p, Vista v) {
        Contratos con = hc.contratos();
        List<Contratos.Estado> lista = new ArrayList<>();
        boolean papel = false;
        if (con != null) {
            papel = hc.valor("contratos", con::pergaminoActivo, false);
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
            Tarjeta t = tarjetaCambiar(e, papel);
            inv.setItem(casillas[k], pergamino(e.def(), t.nombre(), t.lore(), t.brillo()));
            v.acciones().put(casillas[k], t.accion());
        }
    }

    /** Confirmar el cambio de un contrato: No (11), el contrato (su pergamino) con lo que cuesta (13) y Si (15). */
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
        boolean papel = hc.valor("contratos", con::pergaminoActivo, false);
        List<Component> lore = new ArrayList<>(ficha(e, papel).lore());
        lore.add(Component.empty());
        lore.add(Marco.tenue("Si lo cambias, pierdes lo que llevas."));
        lore.add(Component.empty());
        if (precio == 0) lore.add(Marco.tiene("Gratis: es tu cambio gratis de hoy."));
        else {
            lore.add(MenuTasador.fila("Cuesta", Marco.esencias(precio)));
            lore.add(MenuTasador.fila("Tienes", Altar.miles(saldo)));
            lore.add(MenuTasador.fila("Te quedarían", Altar.miles(Math.max(0, saldo - precio))));
        }
        inv.setItem(CENTRO, pergamino(e.def(), nombre(e), lore, false));
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

        // 1.16.3: el trazado. Portada y elegir en 5 filas: marco, contratos, separacion, botones y margen.
        h.igual("Maren: portada en 5 filas", 45, tamano(PORTADA));
        h.igual("Maren: elegir en 5 filas", 45, tamano(V_ELEGIR));
        h.igual("Maren: la confirmacion, en 3 filas", 27, tamano(V_CAMBIAR));
        h.igual("Maren: las filas (contratos 1, separacion 2, botones 3, margen 4)", List.of(1, 2, 3, 4),
                List.of(FILA_CONTRATOS / 9, FILA_SEPARACION / 9, FILA_BOTONES / 9, FILA_MARGEN / 9));
        h.ok("Maren: el margen es la ultima fila", FILA_MARGEN == TAMANO_GRANDE - 9);
        boolean enFila = true;
        for (int n = 1; n <= Marco.COLUMNAS; n++) {
            for (int c : MenuTasador.casillasFila(n)) enFila &= c / 9 == FILA_CONTRATOS / 9 && c % 9 >= 1 && c % 9 <= 7;
        }
        h.ok("Maren: los contratos, en la fila 1 y dentro del marco", enFila);
        h.igual("Maren: tres contratos con aire (11, 13 y 15)", List.of(11, 13, 15),
                java.util.Arrays.stream(MenuTasador.casillasFila(3)).boxed().toList());
        h.igual("Maren: siete contratos llenan la fila 1 (10 a 16)", List.of(10, 11, 12, 13, 14, 15, 16),
                java.util.Arrays.stream(MenuTasador.casillasFila(7)).boxed().toList());
        h.ok("Maren: el aviso sin contratos, en el centro de la fila 1", CENTRO / 9 == FILA_CONTRATOS / 9 && CENTRO % 9 == 4);
        h.ok("Maren: la semana y Cambiar uno en la fila de botones, centrados (Marco.columnas(2))",
                SEMANA == FILA_BOTONES + dos[0] && CAMBIAR == FILA_BOTONES + dos[1]);
        h.igual("Maren: la semana y Cambiar uno, simetricos alrededor del 31", List.of(30, 32), List.of(SEMANA, CAMBIAR));
        h.ok("Maren: Volver de elegir en el centro de la fila de botones (31)", SALIR == 31 && SALIR == FILA_BOTONES + 4);
        // Lo que se pinta en cada vista (CENTRO es el aviso sin contratos: va solo, sin contratos en la fila).
        List<Integer> portada = new ArrayList<>(List.of(SEMANA, CAMBIAR));
        List<Integer> elegir = new ArrayList<>(List.of(SALIR));
        for (int c : MenuTasador.casillasFila(7)) {
            portada.add(c);
            elegir.add(c);
        }
        boolean distintas = new HashSet<>(portada).size() == portada.size() && new HashSet<>(elegir).size() == elegir.size();
        h.ok("Maren: ninguna casilla repetida en la portada ni en elegir", distintas);
        boolean filas = true, separacion = true, marco = true;
        List<Integer> todas = new ArrayList<>(portada);
        todas.addAll(elegir);
        todas.add(CENTRO);
        for (int c : todas) {
            int fila = c / 9;
            filas &= c >= 0 && c < TAMANO_GRANDE && (fila == 1 || fila == 3) && c % 9 >= 1 && c % 9 <= 7;
            separacion &= c < FILA_SEPARACION || c >= FILA_SEPARACION + 9;
            marco &= c >= 9 && c < FILA_MARGEN;
        }
        h.ok("Maren: todo en la fila de contratos o en la de botones", filas);
        h.ok("Maren: la fila 2 queda entera de cristal (separa contratos y botones)", separacion);
        h.ok("Maren: la fila 0 y la de margen (4) quedan de cristal", marco);
        h.ok("Maren: la confirmacion, No, el contrato y Si en la fila del medio", NO == 11 && CENTRO == 13 && SI == 15
                && SI < TAMANO);
        h.igual("Maren: su portada no lleva Volver (Esc cierra)", null, volverDe(PORTADA));
        h.igual("Maren: elegir vuelve a la portada", "volver", volverDe(V_ELEGIR));
        h.igual("Maren: la confirmacion vuelve con su No", null, volverDe(V_CAMBIAR));
        h.ok("Maren: los textos dicen Maren y no Oren", FALLO_PERGAMINO.contains("Maren") && ACEPTA_EN_SPAWN.contains("Maren")
                && !FALLO_PERGAMINO.contains("Oren") && !ACEPTA_EN_SPAWN.contains("Oren"));

        // 1.16.3: cada contrato, con su pergamino: el mismo diseño de estandarte, nombre y cabecera.
        boolean materiales = true;
        for (Contratos.Def d : Contratos.POR_DEFECTO) {
            materiales &= icono(d) == Pergaminos.material(d) && icono(d).name().endsWith("_BANNER_PATTERN");
        }
        h.ok("Maren: cada contrato, con el diseño de estandarte de su pergamino", materiales);
        Map<String, Contratos.Def> base = new HashMap<>();
        for (Contratos.Def d : Contratos.POR_DEFECTO) base.put(d.id(), d);
        Contratos.Def mobs = base.get("corto-mobs"), reliquias = base.get("extraer-ii");
        h.igual("Maren: el de mobs, con la calavera", Material.SKULL_BANNER_PATTERN, icono(mobs));
        h.igual("Maren: el de minijefe, con el creeper", Material.CREEPER_BANNER_PATTERN, icono(base.get("minijefe")));
        h.igual("Maren: el de Reliquias, con el ladrillo", Material.FIELD_MASONED_BANNER_PATTERN, icono(reliquias));
        h.igual("Maren: uno de una clase que no se conoce, con el pergamino de serie (no un papel)",
                Material.MOJANG_BANNER_PATTERN, icono(new Contratos.Def("x", "Algo", "nada", 1, 1, 0, false, "", "Algo")));

        Contratos.Estado oferta = new Contratos.Estado(1, mobs, 0, false, false, false);
        Contratos.Estado aceptado = new Contratos.Estado(1, mobs, 6, false, false, true);
        Contratos.Estado cumplido = new Contratos.Estado(2, reliquias, 3, true, false, true);
        Contratos.Estado cobrado = new Contratos.Estado(1, mobs, 10, true, true, true);
        Tarjeta tOferta = tarjeta(oferta, true, true, 0, 3, false, true);
        Tarjeta tLejos = tarjeta(oferta, true, false, 0, 3, false, true);
        Tarjeta tLleno = tarjeta(oferta, true, true, 3, 3, false, true);
        Tarjeta tLleva = tarjeta(aceptado, true, true, 1, 3, true, true);
        Tarjeta tPedir = tarjeta(aceptado, true, true, 1, 3, false, true);
        Tarjeta tPedirLejos = tarjeta(aceptado, true, false, 1, 3, false, true);
        Tarjeta tSinCopia = tarjeta(aceptado, true, true, 1, 3, false, false);
        Tarjeta tCumplido = tarjeta(cumplido, true, true, 1, 3, true, true);
        Tarjeta tCobrado = tarjeta(cobrado, true, true, 0, 3, false, true);
        Tarjeta tSinPapel = tarjeta(aceptado, false, true, 0, 3, false, true);
        Tarjeta tElegir = tarjetaCambiar(aceptado, true);

        h.ok("Maren: el nombre es el de su pergamino, con sus colores", Ficha.igual(Pergaminos.nombre(mobs), tOferta.nombre())
                && Ficha.igual(Pergaminos.nombre(mobs), tLleva.nombre()) && Ficha.igual(Pergaminos.nombre(reliquias), tCumplido.nombre())
                && Ficha.igual(Pergaminos.nombre(mobs), tElegir.nombre()));
        h.ok("Maren: uno ya cobrado, el mismo nombre en gris", plano(tCobrado.nombre()).equals(Pergaminos.titulo(mobs))
                && !Ficha.igual(Pergaminos.nombre(mobs), tCobrado.nombre()));
        h.ok("Maren: la cabecera es la de su pergamino, con sus colores",
                Ficha.igual(Pergaminos.lore(mobs, 0).get(0), tOferta.lore().get(0))
                        && Ficha.igual(Pergaminos.lore(reliquias, 0).get(0), tCumplido.lore().get(0))
                        && plano(tCobrado.lore().get(0)).equals(Pergaminos.lineas(mobs, 0).get(0)));
        List<String> lOferta = planos(tOferta.lore()), lLleva = planos(tLleva.lore());
        h.ok("Maren: lleva el objetivo y el premio del pergamino", lOferta.contains("◆ Objetivo") && lOferta.contains(mobs.texto())
                && lOferta.contains("◆ Premio") && lOferta.contains(" 2 Esencias · 20 MobCoins"));
        h.ok("Maren: una oferta no lleva progreso; uno aceptado, si (x/y y la barra)",
                lOferta.stream().noneMatch(l -> l.startsWith("◆ Progreso")) && lLleva.contains("◆ Progreso 6/10")
                        && planos(tCumplido.lore()).contains("◆ Progreso 3/3") && planos(tCobrado.lore()).contains("◆ Progreso 10/10"));
        h.ok("Maren: sin la historia del pergamino", !String.join(" ", lOferta).contains(Pergaminos.historia(mobs).substring(0, 20)));
        h.ok("Maren: lo pendiente dice cuanto dura, si es corto y como se cobra", lOferta.contains("Dura hasta la medianoche.")
                && lOferta.contains("Es corto: basta una entrada rápida.") && lOferta.contains(Pergaminos.COBRO_DENTRO)
                && planos(tSinPapel.lore()).contains("Se cobra al salir vivo."));
        h.ok("Maren: lo cumplido o cobrado ya no dice cuanto dura", !planos(tCumplido.lore()).contains("Dura hasta la medianoche.")
                && !planos(tCobrado.lore()).contains("Dura hasta la medianoche."));

        h.igual("Maren: oferta en el spawn, la ultima linea", "▸ Clic para aceptarlo", ultima(tOferta));
        h.igual("Maren: oferta lejos, la ultima linea", "✘ " + ACEPTA_EN_SPAWN, ultima(tLejos));
        h.igual("Maren: oferta con el tope lleno, la ultima linea", "✘ Ya llevas 3 activos.", ultima(tLleno));
        h.igual("Maren: aceptado con su pergamino, la ultima linea", "✔ Aceptado: llevas su pergamino.", ultima(tLleva));
        h.igual("Maren: sin su pergamino en el spawn, la ultima linea", "▸ Clic para pedirle otro", ultima(tPedir));
        h.igual("Maren: sin su pergamino lejos, la ultima linea", "Maren te da otro en el spawn.", ultima(tPedirLejos));
        h.igual("Maren: perdido sin copia, la ultima linea", "Maren no da otro: puedes cambiarlo.", ultima(tSinCopia));
        h.igual("Maren: cumplido de Reliquias, la ultima linea", "Cumplido: se cobra al venderlas.", ultima(tCumplido));
        h.igual("Maren: cobrado, la ultima linea", "✔ Ya lo cobraste.", ultima(tCobrado));
        h.igual("Maren: sin pergaminos, la ultima linea", "Si mueres, vuelve a empezar.", ultima(tSinPapel));
        h.igual("Maren: elegir, la ultima linea", "▸ Clic para cambiar este", ultima(tElegir));
        h.igual("Maren: las acciones del clic", java.util.Arrays.asList("p:1", null, null, null, "p:1", null, null, null, null, null, "c:1"),
                java.util.Arrays.asList(tOferta.accion(), tLejos.accion(), tLleno.accion(), tLleva.accion(), tPedir.accion(),
                        tPedirLejos.accion(), tSinCopia.accion(), tCumplido.accion(), tCobrado.accion(), tSinPapel.accion(),
                        tElegir.accion()));
        h.igual("Maren: brilla solo lo que se puede hacer ya (aceptar, pedir la copia) y lo cumplido",
                List.of(true, false, false, false, true, false, false, true, false, false, false),
                List.of(tOferta.brillo(), tLejos.brillo(), tLleno.brillo(), tLleva.brillo(), tPedir.brillo(), tPedirLejos.brillo(),
                        tSinCopia.brillo(), tCumplido.brillo(), tCobrado.brillo(), tSinPapel.brillo(), tElegir.brillo()));

        // Reglas de los lores: sin negrita ni cursiva (Marco.icono la apaga), sin huecos de mas y lineas de 38.
        List<String> faltas = new ArrayList<>();
        List<Tarjeta> todasT = new ArrayList<>(List.of(tOferta, tLejos, tLleno, tLleva, tPedir, tPedirLejos, tSinCopia,
                tCumplido, tCobrado, tSinPapel, tElegir));
        for (Contratos.Def d : Contratos.POR_DEFECTO) {
            todasT.add(tarjeta(new Contratos.Estado(1, d, 0, false, false, false), true, true, 0, 3, false, true));
            todasT.add(tarjeta(new Contratos.Estado(1, d, 1, false, false, true), true, true, 1, 3, true, true));
            todasT.add(tarjetaCambiar(new Contratos.Estado(1, d, 1, false, false, true), true));
        }
        for (Tarjeta t : todasT) {
            List<Component> sinCursiva = new ArrayList<>();
            for (Component c : t.lore()) sinCursiva.add(c.decoration(TextDecoration.ITALIC, false));
            faltas.addAll(Ficha.faltas(t.nombre(), sinCursiva));
            faltas.addAll(Ficha.largas(planos(t.lore())));
        }
        h.igual("Maren: los contratos sin negrita, sin huecos de mas y con lineas de 38 como mucho", List.of(), faltas);
    }

    private static String plano(Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static List<String> planos(List<Component> l) {
        List<String> out = new ArrayList<>();
        for (Component c : l) out.add(plano(c));
        return out;
    }

    private static String ultima(Tarjeta t) {
        return t.lore().isEmpty() ? null : plano(t.lore().get(t.lore().size() - 1));
    }
}
