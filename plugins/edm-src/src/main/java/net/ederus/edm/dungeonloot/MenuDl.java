package net.ederus.edm.dungeonloot;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import net.ederus.edm.anomaly.drops.DropEntry;
import net.ederus.edm.anomaly.drops.DropTable;
import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.menu.MenuUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * El menu de /dl: crear cajas, llenarlas y ver donde estan plantadas.
 *
 * Cuatro pantallas y ninguna mas: la lista de cajas, la ficha de una caja, su
 * botin y sus bovedas. El objeto UNICO tiene su propia casilla, separada del
 * resto por una fila entera, porque no es un objeto mas de la lista.
 */
public final class MenuDl implements Listener {

    private enum Pantalla { LISTA, CAJA, BOTIN, BOVEDAS }

    /*
     * La pantalla de botin es pequena a proposito: una boveda no lleva veinte
     * objetos. Arriba, sola y en el centro, la casilla del UNICO; debajo, una
     * unica fila para los corrientes. Con eso se ve de un vistazo cual es la
     * pieza que importa, que era lo que no se entendia con todo en la misma
     * rejilla.
     */
    private static final int SLOT_UNICO = 13;
    private static final int[] CASILLAS = {19, 20, 21, 22, 23, 24, 25};

    /* Los botones bajan a la ultima fila y entre medias queda una entera de
     * cristal: sin ese respiro el boton de al lado parece parte del botin. */
    private static final int SLOT_VOLVER = 36;
    private static final int SLOT_TIRADAS = 40;
    private static final int SLOT_AYUDA = 44;

    private static final int[] MARCO = {
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 10, 11, 12, 14, 15, 16, 17,
            18, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35};

    private final DungeonLootPlugin plugin;

    public MenuDl(DungeonLootPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Vista implements InventoryHolder {
        private final Pantalla pantalla;
        private final String contexto;
        private Inventory inv;

        Vista(Pantalla pantalla, String contexto) {
            this.pantalla = pantalla;
            this.contexto = contexto;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    /* ------------------------------------------------------------------ abrir */

    public void abrirLista(Player p) {
        abrir(p, Pantalla.LISTA, null, "Cajas");
    }

    public void abrirCaja(Player p, String cajaId) {
        Caja c = plugin.registro().caja(cajaId);
        abrir(p, Pantalla.CAJA, cajaId, c == null ? "Caja" : c.display());
    }

    public void abrirBotin(Player p, String cajaId) {
        abrir(p, Pantalla.BOTIN, cajaId, "Botín");
    }

    public void abrirBovedas(Player p, String cajaId) {
        abrir(p, Pantalla.BOVEDAS, cajaId, "Plantadas");
    }

    /**
     * El titulo, con la misma forma que los de Anomaly: el rombo en el color de
     * la caja, el nombre del panel en blanco y la seccion detras. Es lo unico que
     * va en negrita en toda la ventana.
     */
    private void abrir(Player p, Pantalla pantalla, String contexto, String seccion) {
        Vista v = new Vista(pantalla, contexto);
        Caja c = plugin.registro().caja(contexto);
        TextColor acento = c == null ? DungeonLootPlugin.MARCA : c.color();
        Component titulo = Component.text("✦ ", acento)
                .append(Component.text("BÓVEDAS", NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text("  " + seccion, acento));
        v.inv = Bukkit.createInventory(v, pantalla == Pantalla.BOTIN ? 45 : 54, titulo);
        pintar(v);
        p.openInventory(v.inv);
    }

    private void pintar(Vista v) {
        v.inv.clear();
        // La fila de abajo entera, como en Anomaly: da suelo a la ventana y deja
        // los botones de navegacion siempre en el mismo sitio.
        int suelo = v.inv.getSize() - 9;
        for (int i = suelo; i < v.inv.getSize(); i++) v.inv.setItem(i, MenuUtil.pane());
        switch (v.pantalla) {
            case LISTA -> pintarLista(v.inv);
            case CAJA -> pintarCaja(v.inv, v.contexto);
            case BOTIN -> pintarBotin(v.inv, v.contexto);
            case BOVEDAS -> pintarBovedas(v.inv, v.contexto);
        }
    }

    /* ----------------------------------------------------------------- pintar */

    private void pintarLista(Inventory inv) {
        MenuUtil.frame(inv, new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 45, 46, 47, 51, 52, 53});

        List<Caja> cajas = plugin.registro().cajas();
        int slot = 10;
        for (Caja c : cajas) {
            if (slot > 43) break;
            if (slot % 9 == 8) slot += 2;
            inv.setItem(slot++, iconoCaja(c));
        }
        if (cajas.isEmpty()) {
            inv.setItem(22, MenuUtil.icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                    MenuUtil.title("Sin cajas todavía", MenuUtil.SOFT),
                    List.of(MenuUtil.line("Crea la primera con los botones de abajo.")), false));
        }

        inv.setItem(48, MenuUtil.icon(Material.VAULT,
                MenuUtil.title("Crear caja común", Caja.Tipo.COMUN.color()),
                List.of(
                        MenuUtil.line("La bóveda normal del Trial Chamber."),
                        MenuUtil.line("Su llave es la Llave de prueba."),
                        MenuUtil.blank(),
                        MenuUtil.action("Clic para ponerle nombre")), false));

        inv.setItem(50, MenuUtil.icon(Material.OMINOUS_TRIAL_KEY,
                MenuUtil.title("Crear caja ominosa", Caja.Tipo.OMINOSA.color()),
                List.of(
                        MenuUtil.line("La bóveda ominosa: la buena."),
                        MenuUtil.line("Su llave es la Llave de prueba ominosa."),
                        MenuUtil.blank(),
                        MenuUtil.action("Clic para ponerle nombre")), true));
    }

    private ItemStack iconoCaja(Caja c) {
        List<Boveda> plantadas = plugin.registro().bovedasDe(c.id());
        List<Component> lore = new ArrayList<>();
        lore.add(MenuUtil.field("Tipo", c.tipo().display(), c.tipo().color()));
        lore.add(MenuUtil.field("Id", c.id(), MenuUtil.SOFT));
        lore.add(MenuUtil.blank());
        lore.add(MenuUtil.field("Botín", c.tabla().entries().size() + " objeto(s)", NamedTextColor.WHITE));
        lore.add(MenuUtil.field("Único", c.unico() == null ? "sin poner"
                : DropTable.trimChance(c.unico().chance()) + "%",
                c.unico() == null ? MenuUtil.DIM : NamedTextColor.AQUA));
        lore.add(MenuUtil.field("Por apertura", c.tiradas() + " objeto(s)", NamedTextColor.WHITE));
        lore.add(MenuUtil.field("Plantadas", String.valueOf(plantadas.size()), NamedTextColor.WHITE));
        lore.add(MenuUtil.blank());
        lore.add(MenuUtil.action("Clic para abrir su ficha"));
        return MenuUtil.icon(c.tipo() == Caja.Tipo.OMINOSA ? Material.OMINOUS_TRIAL_KEY : Material.VAULT,
                MenuUtil.title(c.display(), c.color()), lore, c.tipo() == Caja.Tipo.OMINOSA);
    }

    private void pintarCaja(Inventory inv, String cajaId) {
        Caja c = plugin.registro().caja(cajaId);
        if (c == null) return;
        MenuUtil.frame(inv, new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35,
                36, 37, 38, 39, 40, 41, 42, 43, 44, 46, 47, 48, 49, 50, 51, 52});

        inv.setItem(13, iconoCaja(c));

        inv.setItem(20, MenuUtil.icon(Material.CHEST,
                MenuUtil.title("Botín", MenuUtil.LOOT),
                List.of(
                        MenuUtil.line("Lo que sale al abrirla, y la casilla"),
                        MenuUtil.line("aparte del objeto único."),
                        MenuUtil.blank(),
                        MenuUtil.field("Objetos", c.tabla().entries().size() + " / " + DropTable.CAPACITY,
                                NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.action("Clic para editarlo")), false));

        inv.setItem(21, MenuUtil.icon(c.tipo().key(),
                MenuUtil.title("Obtener llaves", c.color()),
                List.of(
                        MenuUtil.line("La llave de esta caja. Sirve para el"),
                        MenuUtil.line("botín de un mob o para una recompensa."),
                        MenuUtil.blank(),
                        MenuUtil.action("Clic izquierdo: 1 llave"),
                        Component.text("► Clic derecho: 16 llaves", NamedTextColor.YELLOW),
                        Component.text("► Shift + clic: 64 llaves", NamedTextColor.GRAY)), false));

        inv.setItem(22, MenuUtil.icon(Material.VAULT,
                MenuUtil.title("Obtener bóveda", c.color()),
                List.of(
                        MenuUtil.line("El bloque. Colócalo donde quieras y"),
                        MenuUtil.line("tantas veces como quieras."),
                        MenuUtil.blank(),
                        MenuUtil.action("Clic izquierdo: 1 bóveda"),
                        Component.text("► Clic derecho: 8 bóvedas", NamedTextColor.YELLOW)), false));

        inv.setItem(23, MenuUtil.icon(Material.LODESTONE,
                MenuUtil.title("Bóvedas plantadas", MenuUtil.GOLD),
                List.of(
                        MenuUtil.line("Dónde está cada una: mundo, coordenadas,"),
                        MenuUtil.line("región y cuántas veces se ha abierto."),
                        MenuUtil.blank(),
                        MenuUtil.field("Plantadas",
                                String.valueOf(plugin.registro().bovedasDe(c.id()).size()),
                                NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.action("Clic para ver la lista")), false));

        inv.setItem(24, MenuUtil.icon(Material.NAME_TAG,
                MenuUtil.title("Renombrar", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Ahora", c.display(), c.color()),
                        MenuUtil.blank(),
                        MenuUtil.line("Cierra el menú para escribir el nombre"),
                        MenuUtil.line("nuevo en el chat. El id no cambia."),
                        MenuUtil.blank(),
                        MenuUtil.action("Clic para renombrar")), false));

        inv.setItem(30, MenuUtil.icon(Material.BRUSH,
                MenuUtil.title("Color del nombre", c.color()),
                List.of(
                        Component.text("Vista previa  ", MenuUtil.LABEL).append(c.nombre()),
                        MenuUtil.blank(),
                        MenuUtil.action("Clic izquierdo: siguiente color"),
                        Component.text("► Clic derecho: anterior", NamedTextColor.YELLOW)), false));

        inv.setItem(32, MenuUtil.icon(Material.BARRIER,
                MenuUtil.title("Borrar la caja", NamedTextColor.RED),
                List.of(
                        MenuUtil.line("Se va la caja Y sus bóvedas plantadas."),
                        MenuUtil.line("Las llaves que ya estén repartidas dejan"),
                        MenuUtil.line("de servir para nada."),
                        MenuUtil.blank(),
                        Component.text("► Tecla de soltar (Q) dos veces: borrar",
                                NamedTextColor.RED)), false));

        inv.setItem(SLOT_VOLVER, MenuUtil.icon(Material.ARROW,
                MenuUtil.title("Volver", NamedTextColor.YELLOW),
                List.of(MenuUtil.line("A la lista de cajas.")), false));
    }

    private void pintarBotin(Inventory inv, String cajaId) {
        Caja c = plugin.registro().caja(cajaId);
        if (c == null) return;
        MenuUtil.frame(inv, MARCO);

        for (int i = 0; i < CASILLAS.length; i++) {
            DropEntry e = i < c.tabla().entries().size() ? c.tabla().entries().get(i) : null;
            inv.setItem(CASILLAS[i], e == null
                    ? MenuUtil.simple(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                            Estilo.texto("Casilla libre", Estilo.APAGADO),
                            List.of(Estilo.texto("Shift + clic en un objeto de tu", Estilo.APAGADO),
                                    Estilo.texto("inventario para copiarlo aquí.", Estilo.APAGADO)))
                    : iconoEntrada(e, false));
        }

        inv.setItem(SLOT_UNICO, c.unico() == null
                ? MenuUtil.icon(Material.NETHER_STAR,
                        MenuUtil.title("Objeto único", NamedTextColor.AQUA),
                        List.of(
                                Estilo.texto("La pieza rara de la caja. Sale aparte", Estilo.APAGADO),
                                Estilo.texto("de la fila de abajo, con su propia", Estilo.APAGADO),
                                Estilo.texto("probabilidad, y es lo que se ve girando", Estilo.APAGADO),
                                Estilo.texto("dentro de la bóveda.", Estilo.APAGADO),
                                Estilo.vacio(),
                                Estilo.accion("Shift + clic en un objeto para ponerlo",
                                        NamedTextColor.AQUA)), false)
                : iconoEntrada(c.unico(), true));

        inv.setItem(SLOT_TIRADAS, MenuUtil.icon(Material.COMPARATOR,
                MenuUtil.title("Objetos por apertura", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Ahora", c.tiradas() + " de la lista", NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.line("Cuántos objetos de la lista salen cada"),
                        MenuUtil.line("vez. Nunca se repite uno en la misma"),
                        MenuUtil.line("apertura. El único va aparte."),
                        MenuUtil.blank(),
                        MenuUtil.action("Clic izquierdo: +1"),
                        Component.text("► Clic derecho: -1", NamedTextColor.YELLOW)), false));

        inv.setItem(SLOT_VOLVER, MenuUtil.icon(Material.ARROW,
                MenuUtil.title("Volver", NamedTextColor.YELLOW),
                List.of(MenuUtil.line("A la ficha de la caja.")), false));

        inv.setItem(SLOT_AYUDA, MenuUtil.icon(Material.WRITABLE_BOOK,
                MenuUtil.title("Cómo se edita", MenuUtil.SOFT),
                List.of(
                        Estilo.texto("Shift + clic en un objeto de tu inventario", Estilo.APAGADO),
                        Estilo.texto("y se copia a la fila de abajo. Con la", Estilo.APAGADO),
                        Estilo.texto("casilla de único vacía, va ahí.", Estilo.APAGADO),
                        Estilo.vacio(),
                        Estilo.accion("Clic izquierdo: +5% de probabilidad", NamedTextColor.YELLOW),
                        Estilo.accion("Clic derecho: -5%", NamedTextColor.YELLOW),
                        Estilo.accion("Shift + clic: cantidad máxima ±1", Estilo.APAGADO),
                        Estilo.accion("Tecla de soltar (Q): quitarlo", Estilo.APAGADO)), false));
    }

    private ItemStack iconoEntrada(DropEntry e, boolean unico) {
        List<Component> lore = new ArrayList<>();
        if (unico) {
            lore.add(Estilo.texto("Objeto único", NamedTextColor.AQUA));
            lore.add(Estilo.texto("Es el que gira dentro de la bóveda.", Estilo.APAGADO));
            lore.add(Estilo.vacio());
        }
        lore.add(Estilo.linea("Probabilidad", DropTable.trimChance(e.chance()) + "%",
                unico ? NamedTextColor.AQUA : Estilo.CLARO));
        lore.add(Estilo.linea("Cantidad", e.amountLabel(), Estilo.CLARO));
        lore.add(Estilo.vacio());
        lore.add(Estilo.accion("Clic izquierdo: +5%", NamedTextColor.YELLOW));
        lore.add(Estilo.accion("Clic derecho: -5%", NamedTextColor.YELLOW));
        lore.add(Estilo.accion("Shift + clic: cantidad máxima ±1", Estilo.APAGADO));
        lore.add(Estilo.accion("Tecla de soltar (Q): quitarlo", Estilo.APAGADO));
        ItemStack visto = e.item().clone();
        visto.setAmount(Math.max(1, Math.min(64, e.max())));
        return MenuUtil.decorate(visto, null, lore, unico);
    }

    private void pintarBovedas(Inventory inv, String cajaId) {
        Caja c = plugin.registro().caja(cajaId);
        if (c == null) return;
        MenuUtil.frame(inv, new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 46, 47, 48, 49, 50, 51, 52, 53});

        List<Boveda> lista = plugin.registro().bovedasDe(cajaId);
        int slot = 9;
        for (Boveda b : lista) {
            if (slot > 44) break;
            inv.setItem(slot++, iconoBoveda(b, c));
        }
        if (lista.isEmpty()) {
            inv.setItem(22, MenuUtil.icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                    MenuUtil.title("Ninguna plantada", MenuUtil.SOFT),
                    List.of(
                            MenuUtil.line("Coge el bloque desde la ficha de la caja"),
                            MenuUtil.line("y colócalo donde quieras.")), false));
        }

        inv.setItem(SLOT_VOLVER, MenuUtil.icon(Material.ARROW,
                MenuUtil.title("Volver", NamedTextColor.YELLOW),
                List.of(MenuUtil.line("A la ficha de la caja.")), false));
    }

    private ItemStack iconoBoveda(Boveda b, Caja c) {
        List<Component> lore = new ArrayList<>();
        lore.add(MenuUtil.field("Mundo", b.worldName(), NamedTextColor.WHITE));
        lore.add(MenuUtil.field("Coordenadas", b.x() + " " + b.y() + " " + b.z(), NamedTextColor.WHITE));
        lore.add(MenuUtil.field("Región", region(b), NamedTextColor.AQUA));
        lore.add(MenuUtil.field("Aperturas", String.valueOf(b.aperturas()), NamedTextColor.WHITE));
        lore.add(MenuUtil.blank());
        lore.add(MenuUtil.action("Clic para viajar allí"));
        lore.add(Component.text("► Tecla de soltar (Q) dos veces: quitarla", NamedTextColor.GRAY));
        return MenuUtil.icon(c.tipo() == Caja.Tipo.OMINOSA ? Material.OMINOUS_TRIAL_KEY : Material.VAULT,
                MenuUtil.title(b.id(), c.color()), lore, false);
    }

    /** La region de WorldGuard que la cubre, preguntando al enganche de Anomaly. */
    private String region(Boveda b) {
        var sitio = b.sitio();
        if (sitio == null) return "mundo sin cargar";
        var anomaly = plugin.core().modulo("anomaly");
        if (!(anomaly instanceof net.ederus.edm.anomaly.AnomalyPlugin ap)) return "sin comprobar";
        List<String> regiones = ap.protection().regionNames(sitio);
        return regiones.isEmpty() ? "ninguna" : String.join(", ", regiones);
    }

    /* ------------------------------------------------------------------ clics */

    @EventHandler
    public void alHacerClic(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Vista v)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        boolean arriba = e.getRawSlot() >= 0 && e.getRawSlot() < e.getInventory().getSize();

        /*
         * En la pantalla de botin hay que DEJAR tocar el inventario propio, o el
         * jugador no puede coger nada con el cursor y el menu parece congelado.
         * Es exactamente lo que pasaba: no habia forma de anadir un objeto.
         *
         * Todo lo demas sigue cancelado, y el shift tambien, porque el shift si
         * moveria el objeto de verdad en vez de copiarlo.
         */
        if (arriba || v.pantalla != Pantalla.BOTIN || e.isShiftClick()) {
            e.setCancelled(true);
        }

        // Shift desde el inventario propio: copia ese objeto a la lista.
        if (v.pantalla == Pantalla.BOTIN && !arriba && e.isShiftClick()) {
            copiarAlBotin(p, v, e.getCurrentItem());
            return;
        }
        if (!arriba) return;

        switch (v.pantalla) {
            case LISTA -> clicLista(p, e);
            case CAJA -> clicCaja(p, v, e);
            case BOTIN -> clicBotin(p, v, e);
            case BOVEDAS -> clicBovedas(p, v, e);
        }
    }

    /**
     * Mete una COPIA: el objeto del jugador no se mueve de su sitio.
     *
     * Si la casilla del unico esta vacia, va ahi; es lo primero que se configura
     * de una caja y no tiene sentido obligar a un segundo gesto para ponerlo.
     */
    private void copiarAlBotin(Player p, Vista v, ItemStack elegido) {
        if (elegido == null || elegido.getType().isAir()) return;
        Caja c = plugin.registro().caja(v.contexto);
        if (c == null) return;

        if (c.unico() == null) {
            DropEntry nuevo = DropEntry.of(elegido.clone());
            nuevo.chance(1.0);
            c.unico(nuevo);
            plugin.di(p, "unico-puesto", "Objeto único puesto, al 1% de probabilidad.");
        } else {
            if (c.tabla().entries().size() >= CASILLAS.length) {
                plugin.di(p, "lista-llena", "La lista está llena: %tope% objetos.",
                        "%tope%", String.valueOf(CASILLAS.length));
                return;
            }
            c.tabla().entries().add(DropEntry.of(elegido.clone()));
        }
        plugin.registro().guardar();
        plugin.bovedas().refrescarPremio(c);
        pintar(v);
    }

    private static boolean esCasilla(int slot) {
        for (int s : CASILLAS) {
            if (s == slot) return true;
        }
        return false;
    }

    private void clicLista(Player p, InventoryClickEvent e) {
        ItemStack it = e.getCurrentItem();
        if (it == null) return;
        if (e.getSlot() == 48 || e.getSlot() == 50) {
            Caja.Tipo tipo = e.getSlot() == 48 ? Caja.Tipo.COMUN : Caja.Tipo.OMINOSA;
            p.closeInventory();
            plugin.di(p, "pide-nombre", "Escribe en el chat el nombre de la caja.");
            plugin.core().chat().pedir(p, texto -> {
                Caja c = plugin.registro().crear(texto.trim(), tipo);
                plugin.registro().guardar();
                plugin.di(p, "caja-creada", "Caja creada: %caja% (%id%)",
                        "%caja%", c.display(), "%id%", c.id());
                abrirCaja(p, c.id());
            }, () -> abrirLista(p));
            return;
        }
        Caja c = cajaEnSlot(e.getSlot());
        if (c != null) abrirCaja(p, c.id());
    }

    /** Qué caja hay pintada en ese hueco de la lista, en el mismo orden que se pintó. */
    private Caja cajaEnSlot(int slot) {
        List<Caja> cajas = plugin.registro().cajas();
        int s = 10;
        for (Caja c : cajas) {
            if (s > 43) break;
            if (s % 9 == 8) s += 2;
            if (s == slot) return c;
            s++;
        }
        return null;
    }

    private final java.util.Map<java.util.UUID, String> confirmando = new java.util.HashMap<>();

    private void clicCaja(Player p, Vista v, InventoryClickEvent e) {
        Caja c = plugin.registro().caja(v.contexto);
        if (c == null) return;

        switch (e.getSlot()) {
            case 20 -> abrirBotin(p, c.id());
            case 21 -> {
                int n = e.isShiftClick() ? 64 : (e.isRightClick() ? 16 : 1);
                dar(p, c.llave(plugin.claveLlave(), n));
                plugin.di(p, "llaves-dadas", "Tienes %cuantas% llave(s) de %caja%",
                        "%cuantas%", String.valueOf(n), "%caja%", c.display());
            }
            case 22 -> {
                int n = e.isRightClick() ? 8 : 1;
                dar(p, c.bloque(plugin.claveCaja(), n));
                plugin.di(p, "bovedas-dadas", "Tienes %cuantas% bóveda(s) de %caja%",
                        "%cuantas%", String.valueOf(n), "%caja%", c.display());
            }
            case 23 -> abrirBovedas(p, c.id());
            case 24 -> {
                p.closeInventory();
                plugin.di(p, "pide-nombre-nuevo", "Escribe en el chat el nombre nuevo.");
                plugin.core().chat().pedir(p, texto -> {
                    c.display(texto.trim());
                    plugin.registro().guardar();
                    abrirCaja(p, c.id());
                }, () -> abrirCaja(p, c.id()));
            }
            case 30 -> {
                c.colorRgb(siguienteColor(c.colorRgb(), !e.isRightClick()));
                plugin.registro().guardar();
                pintar(v);
            }
            case 32 -> {
                if (e.getClick() != ClickType.DROP && e.getClick() != ClickType.CONTROL_DROP) return;
                if (!c.id().equals(confirmando.get(p.getUniqueId()))) {
                    confirmando.put(p.getUniqueId(), c.id());
                    plugin.di(p, "caja-confirmar", "Pulsa Q otra vez para borrar %caja%",
                            "%caja%", c.display());
                    return;
                }
                confirmando.remove(p.getUniqueId());
                plugin.registro().borrar(c);
                plugin.registro().guardar();
                plugin.di(p, "caja-borrada", "Se fue la caja %caja% y sus bóvedas con ella.",
                        "%caja%", c.display());
                abrirLista(p);
            }
            case SLOT_VOLVER -> abrirLista(p);
            default -> { }
        }
    }

    private void clicBotin(Player p, Vista v, InventoryClickEvent e) {
        Caja c = plugin.registro().caja(v.contexto);
        if (c == null) return;

        if (e.getSlot() == SLOT_VOLVER) {
            abrirCaja(p, c.id());
            return;
        }
        if (e.getSlot() == SLOT_TIRADAS) {
            c.tiradas(c.tiradas() + (e.isRightClick() ? -1 : 1));
            plugin.registro().guardar();
            pintar(v);
            return;
        }

        ItemStack cursor = e.getCursor();
        boolean traeAlgo = cursor != null && !cursor.getType().isAir();

        if (e.getSlot() == SLOT_UNICO) {
            if (traeAlgo) {
                DropEntry nuevo = DropEntry.of(cursor.clone());
                nuevo.chance(1.0);
                c.unico(nuevo);
                plugin.registro().guardar();
                plugin.bovedas().refrescarPremio(c);
                pintar(v);
                plugin.di(p, "unico-puesto", "Objeto único puesto, al 1% de probabilidad.");
                return;
            }
            if (c.unico() != null) ajustar(p, v, c, c.unico(), e, true);
            return;
        }

        if (!esCasilla(e.getSlot())) return;
        int indice = indiceDe(e.getSlot());
        List<DropEntry> lista = c.tabla().entries();

        if (traeAlgo) {
            if (lista.size() >= CASILLAS.length) {
                plugin.di(p, "lista-llena", "La lista está llena: %tope% objetos.",
                        "%tope%", String.valueOf(CASILLAS.length));
                return;
            }
            lista.add(DropEntry.of(cursor.clone()));
            plugin.registro().guardar();
            pintar(v);
            return;
        }
        if (indice < lista.size()) ajustar(p, v, c, lista.get(indice), e, false);
    }

    /** Los tres gestos de una entrada: probabilidad, cantidad y quitarla. */
    private void ajustar(Player p, Vista v, Caja c, DropEntry entry, InventoryClickEvent e, boolean unico) {
        if (e.getClick() == ClickType.DROP || e.getClick() == ClickType.CONTROL_DROP) {
            if (unico) {
                c.unico(null);
            } else {
                c.tabla().entries().remove(entry);
            }
        } else if (e.isShiftClick()) {
            int max = Math.max(entry.min(), entry.max() + (e.isRightClick() ? -1 : 1));
            entry.amount(entry.min(), Math.max(1, Math.min(64, max)));
        } else {
            double paso = e.isRightClick() ? -5 : 5;
            entry.chance(Math.max(0.1, Math.min(100, entry.chance() + paso)));
        }
        plugin.registro().guardar();
        plugin.bovedas().refrescarPremio(c);
        pintar(v);
    }

    private int indiceDe(int slot) {
        for (int i = 0; i < CASILLAS.length; i++) {
            if (CASILLAS[i] == slot) return i;
        }
        return -1;
    }

    private void clicBovedas(Player p, Vista v, InventoryClickEvent e) {
        if (e.getSlot() == SLOT_VOLVER) {
            abrirCaja(p, v.contexto);
            return;
        }
        List<Boveda> lista = plugin.registro().bovedasDe(v.contexto);
        int i = e.getSlot() - 9;
        if (i < 0 || i >= lista.size()) return;
        Boveda b = lista.get(i);

        if (e.getClick() == ClickType.DROP || e.getClick() == ClickType.CONTROL_DROP) {
            if (!b.id().equals(confirmando.get(p.getUniqueId()))) {
                confirmando.put(p.getUniqueId(), b.id());
                plugin.di(p, "quitar-confirmar", "Pulsa Q otra vez para quitar esa bóveda.");
                return;
            }
            confirmando.remove(p.getUniqueId());
            var sitio = b.sitio();
            if (sitio != null && sitio.getWorld().isChunkLoaded(b.x() >> 4, b.z() >> 4)) {
                sitio.getBlock().setType(Material.AIR);
            }
            plugin.registro().quitar(b);
            plugin.registro().guardar();
            plugin.di(p, "quitada", "Bóveda quitada del mapa.");
            pintar(v);
            return;
        }

        var destino = b.destino();
        if (destino == null) {
            plugin.di(p, "mundo-descargado", "El mundo %mundo% no está cargado.",
                    "%mundo%", b.worldName());
            return;
        }
        p.closeInventory();
        p.teleport(destino);
        plugin.di(p, "viaje", "Ahí está: %x% %y% %z% (%mundo%)",
                "%x%", String.valueOf(b.x()), "%y%", String.valueOf(b.y()),
                "%z%", String.valueOf(b.z()), "%mundo%", b.worldName());
    }

    private static final int[] PALETA = {
            0x8FB8C4, 0xC792EA, 0xFFD966, 0x66CC66, 0xFF8A5C, 0xFF5C5C, 0x82AAFF, 0xFFFFFF};

    private int siguienteColor(int actual, boolean adelante) {
        int at = 0;
        for (int i = 0; i < PALETA.length; i++) {
            if (PALETA[i] == actual) {
                at = i;
                break;
            }
        }
        return PALETA[Math.floorMod(at + (adelante ? 1 : -1), PALETA.length)];
    }

    private void dar(Player p, ItemStack item) {
        for (ItemStack sobra : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), sobra);
        }
    }
}
