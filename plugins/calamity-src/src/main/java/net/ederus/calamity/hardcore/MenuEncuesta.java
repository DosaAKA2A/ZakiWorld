package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.menu.MenuUtil;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Los menus de la encuesta, el Voto del Botin y la lista de deseos (MED sec. 4.1).
 *
 * Pocas cosas y un clic porque sale solo, al volver de Calamity: tiene que entenderse de un
 * vistazo y cerrarse sin esfuerzo. Pensado para Bedrock (Geyser): solo clic izquierdo, sin
 * arrastrar, sin negrita ni cursiva, texto por chat solo para "Otra cosa". Un clic cada
 * 500 ms: un doble clic no puede votar dos veces ni abrir el chat dos veces.
 *
 * 1.7.3, como los demas menus de Calamity: 27 casillas con marco negro, la pregunta arriba en el
 * centro (4), las opciones centradas en la fila del medio ("Otra cosa" la ultima) y "Ahora no"
 * abajo en el centro (22). Antes era una fila de 9 con la pregunta en la esquina. Un solo
 * listener para los dos menus (los distingue el holder).
 *
 * No pregunta esHardcore: el menu sale fuera de Calamity (justo despues de salir); lo que
 * lo hace barato es mirar el holder antes que nada.
 */
final class MenuEncuesta implements Listener {

    static final TextColor VERDE = Paleta.DETALLE;
    static final TextColor AMBAR = TextColor.color(0xE8A33D);
    /** 27 casillas: la pregunta arriba en el centro, las opciones en la fila del medio y Cerrar abajo. */
    static final int TAMANO = 27, CABECERA = 4, FILA = 9, CERRAR = 22;
    private static final long ESPERA_MS = 500;

    /** Marca de nuestros inventarios. pregunta = id, o null en la lista de deseos. */
    record Marca(String pregunta, boolean deseos) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final Hardcore hc;
    private final Encuesta encuesta;
    private final Map<UUID, Long> ultimoClic = new HashMap<>();

    MenuEncuesta(Hardcore hc, Encuesta encuesta) {
        this.hc = hc;
        this.encuesta = encuesta;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
    }

    void parar() {
        HandlerList.unregisterAll(this);
        // Un menu abierto de un modulo parado se quedaria sin nadie que atienda sus clics.
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Marca) p.closeInventory();
        }
        ultimoClic.clear();
    }

    // ------------------------------------------------------------------ pintar

    /** Casillas de n cosas (hasta 7) en la fila del medio, centradas y con aire (Marco.columnas). */
    static int[] casillas(int n) {
        int[] cols = Marco.columnas(Math.min(Marco.COLUMNAS, Math.max(0, n)));
        int[] out = new int[cols.length];
        for (int i = 0; i < cols.length; i++) out[i] = FILA + cols[i];
        return out;
    }

    /** Cuantas casillas ocupa una pregunta: sus opciones y, si la admite, "Otra cosa" al final. */
    private static int cuantas(Encuesta.Pregunta q) {
        return q.opciones().size() + (q.admiteOtra() ? 1 : 0);
    }

    /** La opcion de una casilla, Encuesta.OTRA, o null. */
    static String opcionEn(Encuesta.Pregunta q, int casilla) {
        int[] cs = casillas(cuantas(q));
        for (int i = 0; i < cs.length; i++) {
            if (cs[i] != casilla) continue;
            return i < q.opciones().size() ? q.opciones().get(i).id() : Encuesta.OTRA;
        }
        return null;
    }

    void abrir(Player p, Encuesta.Pregunta q) {
        if (q == null) return;
        // El de la fija ("¿Que quieres que de Calamity?") no cabia en la ventana: la pregunta va en el libro.
        Component titulo = (q.fija() ? Marco.T_VOTO : Marco.T_PREGUNTA).componente();
        Inventory inv = hc.plugin().getServer().createInventory(new Marca(q.id(), false), TAMANO, titulo);

        int premio = Math.max(0, hc.cfg().getInt("encuesta.premio-esencias", 1));
        List<Component> cabeza = new ArrayList<>(MenuUtil.wrap(q.texto(), 30, Paleta.TEXTO));
        cabeza.add(Component.empty());
        cabeza.add(Marco.tenue(q.fija() ? "Puedes votar una vez por semana." : "Elige una respuesta con un clic."));
        if (premio > 0) cabeza.add(Marco.tenue("Responder te da " + Marco.esencias(premio) + "."));
        inv.setItem(CABECERA, Marco.icono(Material.WRITABLE_BOOK, Component.text(q.fija() ? "Voto del Botín" : "Pregunta", VERDE),
                cabeza, false));

        int[] cs = casillas(cuantas(q));
        for (int i = 0; i < cs.length; i++) {
            if (i < q.opciones().size()) {
                Encuesta.Opcion o = q.opciones().get(i);
                inv.setItem(cs[i], Marco.icono(o.icono(), Component.text(o.texto(), AMBAR),
                        List.of(Marco.accion("Clic para elegirla")), false));
            } else {
                inv.setItem(cs[i], Marco.icono(Material.NAME_TAG, Component.text("Otra cosa", AMBAR), List.of(
                        Marco.tenue("La escribes en el chat,"), Marco.tenue("con 60 letras como mucho."),
                        Component.empty(), Marco.accion("Clic para escribirla")), false));
            }
        }
        inv.setItem(CERRAR, Marco.icono(Material.BARRIER, Component.text("Ahora no", Paleta.AVISO),
                List.of(Marco.tenue("Se cierra sin votar."), Component.empty(), Marco.accion("Clic para cerrar")), false));
        Marco.rellenar(inv);
        p.openInventory(inv);
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.amethyst_block.chime", 0.8f, 1.1f);
    }

    // ------------------------------------------------------------------ clics

    @EventHandler
    public void alClic(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Marca m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClick() != ClickType.LEFT) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getInventory().getSize()) return;
        long ahora = System.currentTimeMillis();
        Long antes = ultimoClic.get(p.getUniqueId());
        if (antes != null && ahora - antes < ESPERA_MS) return;
        ultimoClic.put(p.getUniqueId(), ahora);

        if (m.deseos()) {
            encuesta.deseos().alClic(p, slot, e.getInventory());
            return;
        }
        if (slot == CERRAR) {
            cerrar(p);
            return;
        }
        Encuesta.Pregunta q = encuesta.preguntas().get(m.pregunta());
        if (q == null) {
            cerrar(p);
            return;
        }
        String op = opcionEn(q, slot);
        if (op == null) return;
        if (Encuesta.OTRA.equals(op)) {
            // EntradaChat cierra el menu un tick despues: cerrarlo aqui dejaria un item fantasma.
            encuesta.pedirOtra(p, q);
            return;
        }
        cerrar(p);
        encuesta.votar(p, q, op, null);
    }

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Marca) e.setCancelled(true);
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        ultimoClic.remove(e.getPlayer().getUniqueId());
    }

    /** Cerrar en mitad del evento de clic deja items fantasma en el cursor: un tick despues. */
    void cerrar(Player p) {
        encuesta.tarea(() -> {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Marca) p.closeInventory();
        }, 1L);
    }
}
