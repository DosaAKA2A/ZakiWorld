package net.ederus.lethalworld.hardcore;

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
 * Los menus de una fila de la encuesta, el Voto del Botin y la lista de deseos (MED sec. 4.1).
 *
 * Una fila y un clic porque sale solo, al volver de Calamity: tiene que entenderse de un
 * vistazo y cerrarse sin esfuerzo. Pensado para Bedrock (Geyser): solo clic izquierdo, sin
 * arrastrar, sin negrita ni cursiva, texto por chat solo para "Otra cosa". Un clic cada
 * 500 ms: un doble clic no puede votar dos veces ni abrir el chat dos veces.
 *
 * Casilla 0 la pregunta; opciones en 2-6 (y "Otra cosa" en 7) o 2-7 o 1-7 segun cuantas
 * haya; casilla 8 "Ahora no". Un solo listener para los dos menus (los distingue el holder).
 *
 * No pregunta esHardcore: el menu sale fuera de Calamity (justo despues de salir); lo que
 * lo hace barato es mirar el holder antes que nada.
 */
final class MenuEncuesta implements Listener {

    static final TextColor VERDE = Paleta.DETALLE;
    static final TextColor AMBAR = TextColor.color(0xE8A33D);
    static final int CERRAR = 8;
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

    /** Casillas de las opciones segun cuantas son (hasta 7). */
    static int[] casillas(int n) {
        int cuantas = Math.min(7, Math.max(0, n));
        int desde = cuantas <= 6 ? 2 : 1;
        int[] out = new int[cuantas];
        for (int i = 0; i < cuantas; i++) out[i] = desde + i;
        return out;
    }

    /** La opcion de una casilla, Encuesta.OTRA, o null. */
    static String opcionEn(Encuesta.Pregunta q, int casilla) {
        int[] cs = casillas(q.opciones().size());
        for (int i = 0; i < cs.length; i++) if (cs[i] == casilla) return q.opciones().get(i).id();
        if (q.admiteOtra() && casilla == 7) return Encuesta.OTRA;
        return null;
    }

    void abrir(Player p, Encuesta.Pregunta q) {
        if (q == null) return;
        Component titulo = Paleta.calido(q.fija() ? "¿Qué quieres que dé Calamity?" : "Calamity pregunta");
        Inventory inv = hc.plugin().getServer().createInventory(new Marca(q.id(), false), 9, titulo);

        int premio = Math.max(0, hc.cfg().getInt("encuesta.premio-esencias", 1));
        List<Component> cabeza = new ArrayList<>(MenuUtil.wrap(q.texto(), 30, Paleta.TEXTO));
        cabeza.add(MenuUtil.blank());
        cabeza.add(MenuUtil.line(q.fija() ? "Un voto por semana." : "Una respuesta, un clic."));
        if (premio > 0) cabeza.add(MenuUtil.line("Vale " + premio + (premio == 1 ? " Esencia." : " Esencias.")));
        inv.setItem(0, MenuUtil.icon(Material.WRITABLE_BOOK, Component.text(q.fija() ? "Voto del Botín" : "Pregunta", VERDE),
                cabeza, false));

        int[] cs = casillas(q.opciones().size());
        for (int i = 0; i < cs.length; i++) {
            Encuesta.Opcion o = q.opciones().get(i);
            inv.setItem(cs[i], MenuUtil.icon(o.icono(), Component.text(o.texto(), AMBAR),
                    List.of(MenuUtil.line("Clic para elegirla.")), false));
        }
        if (q.admiteOtra()) {
            inv.setItem(7, MenuUtil.icon(Material.NAME_TAG, Component.text("Otra cosa", AMBAR),
                    List.of(MenuUtil.line("Lo escribes en el chat."), MenuUtil.line("60 letras como mucho.")), false));
        }
        inv.setItem(CERRAR, MenuUtil.icon(Material.BARRIER, Component.text("Ahora no", Paleta.AVISO),
                List.of(MenuUtil.line("Se cierra sin votar.")), false));
        for (int s = 0; s < 9; s++) if (inv.getItem(s) == null) inv.setItem(s, MenuUtil.pane());
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
