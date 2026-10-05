package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calamity 1.12 · El menu de Ilen, el Cronista (lo que abre su NPC).
 *
 * Antes el clic en Ilen soltaba el indice en el chat y cada capitulo se abria pulsando un enlace que
 * escribia el comando del capitulo por el jugador; desde Bedrock, que no pulsa enlaces, habia que
 * escribirlo. Desde la 1.12 /calamity es solo de staff, asi que el indice es esta ventana: un libro
 * por historia y, debajo, lo que antes se pedia por comando y no tenia NPC (tus Ecos, el proximo
 * Eclipse, la encuesta y la lista de deseos; estos tres, solo si estan encendidos en la config).
 *
 *   fila 0:       Ilen (4): cuantas historias conoce
 *   filas 1-2:    las historias, de 7 en 7, centradas (Marco.columnas); 14 como mucho
 *   fila de mas:  Tus Ecos, Proximo Eclipse, Encuesta, Lista de deseos (las que toquen), centradas
 *   ultima fila:  Cerrar en el centro, como en todos los menus de Calamity
 *
 * El clic en una historia cierra la ventana y la cuenta en el chat (Cronista.capitulo); en Java, al
 * pie, las flechas llevan a la anterior o la siguiente sin comando (ClickEvent.callback). Solo clic
 * izquierdo y un clic cada 500 ms: Bedrock manda dos a veces.
 */
final class MenuCronista implements Listener {

    /** "CALAMITY | Historias". */
    static final Marco.Titulo TITULO = new Marco.Titulo("Historias");
    /** Las historias que caben: dos filas de siete. */
    static final int MAXIMO = 2 * Marco.COLUMNAS;
    private static final int CABECERA = 4;
    private static final long ESPERA_MS = 500;

    /** Nuestra ventana: la accion de cada casilla ("c:<n>" una historia, o el id de un boton). */
    record Vista(Map<Integer, String> acciones) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final Hardcore hc;
    private final Cronista cronista;
    private final Map<UUID, Long> ultimoClic = new HashMap<>();

    MenuCronista(Hardcore hc, Cronista cronista) {
        this.hc = hc;
        this.cronista = cronista;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
    }

    void parar() {
        HandlerList.unregisterAll(this);
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Vista) p.closeInventory();
        }
        ultimoClic.clear();
    }

    // ------------------------------------------------------------------ plano

    /** Un boton de la fila de abajo: su accion, el icono, el nombre y el lore. */
    record Boton(String accion, Material icono, String nombre, List<Component> lore) {
    }

    /**
     * Donde va cada cosa, sin tocar Bukkit (lo prueba el autotest): casilla -> accion. n = historias,
     * botones = cuantos botones de abajo hay. Devuelve tambien el tamano en la clave -1.
     */
    static Map<Integer, String> plano(int n, List<String> botones) {
        Map<Integer, String> out = new LinkedHashMap<>();
        int historias = Math.max(0, Math.min(MAXIMO, n));
        int filas = Math.max(1, (historias + Marco.COLUMNAS - 1) / Marco.COLUMNAS);
        int fila = 1;
        for (int f = 0; f < filas; f++) {
            int enEsta = Math.min(Marco.COLUMNAS, historias - f * Marco.COLUMNAS);
            int[] cols = Marco.columnas(Math.max(0, enEsta));
            for (int k = 0; k < cols.length; k++) out.put(9 * fila + cols[k], "c:" + (f * Marco.COLUMNAS + k + 1));
            fila++;
        }
        if (!botones.isEmpty()) {
            int[] cols = Marco.columnas(Math.min(Marco.COLUMNAS, botones.size()));
            for (int k = 0; k < cols.length; k++) out.put(9 * fila + cols[k], botones.get(k));
            fila++;
        }
        int tamano = 9 * (fila + 1);
        out.put(Marco.abajo(tamano), "cerrar");
        out.put(-1, String.valueOf(tamano));
        return out;
    }

    /** Los botones de abajo que tocan ahora. */
    private List<Boton> botones(Player p) {
        List<Boton> out = new ArrayList<>();
        out.add(new Boton("echoes", Material.ECHO_SHARD, "Tus Ecos", List.of(
                Marco.tenue("Dónde están, su nivel, las"), Marco.tenue("Reliquias que guardan y cuánto"),
                Marco.tenue("les queda."), Component.empty(), Marco.accion("Clic para verlos en el chat"))));
        if (hc.cfg().getBoolean("eclipse.activo", false) && hc.eclipse() != null) {
            String cuando = hc.valor("eclipse", () -> hc.eclipse().resumen(System.currentTimeMillis()), "");
            List<Component> lore = new ArrayList<>(net.ederus.edm.comun.menu.MenuUtil.wrap(cuando, 30, Paleta.TEXTO));
            out.add(new Boton("eclipse", Material.CLOCK, "Próximo Eclipse", lore));
        }
        if (hc.cfg().getBoolean("encuesta.activo", false)) {
            out.add(new Boton("poll", Material.WRITABLE_BOOK, "Encuesta", List.of(
                    Marco.tenue("La pregunta de Calamity y el"), Marco.tenue("Voto del Botín de la semana."),
                    Component.empty(), Marco.accion("Clic para votar"))));
        }
        if (hc.cfg().getBoolean("deseos.activo", false)) {
            out.add(new Boton("wishes", Material.NETHER_STAR, "Lista de deseos", List.of(
                    Marco.tenue("Vota lo que quieres que"), Marco.tenue("dé Calamity."),
                    Component.empty(), Marco.accion("Clic para elegir"))));
        }
        return out;
    }

    // ------------------------------------------------------------------ abrir

    void abrir(Player p) {
        List<Cronista.Capitulo> l = cronista.capitulos();
        List<Boton> bs = botones(p);
        List<String> ids = new ArrayList<>();
        for (Boton b : bs) ids.add(b.accion());
        Map<Integer, String> plano = plano(l.size(), ids);
        int tamano = Integer.parseInt(plano.remove(-1));
        Vista v = new Vista(new HashMap<>());
        Inventory inv = hc.plugin().getServer().createInventory(v, tamano, TITULO.componente());

        List<Component> cabeza = new ArrayList<>();
        cabeza.add(Marco.texto(l.isEmpty() ? "Todavía no tiene nada que contar."
                : "Conoce " + l.size() + (l.size() == 1 ? " historia" : " historias") + " de Calamity."));
        if (!l.isEmpty()) {
            cabeza.add(Component.empty());
            cabeza.add(Marco.tenue("Elige una y te la cuenta"));
            cabeza.add(Marco.tenue("en el chat."));
        }
        inv.setItem(CABECERA, Marco.icono(Material.LECTERN, Component.text("Ilen", Paleta.DETALLE), cabeza, false));

        Map<String, Boton> porId = new HashMap<>();
        for (Boton b : bs) porId.put(b.accion(), b);
        for (Map.Entry<Integer, String> e : plano.entrySet()) {
            String a = e.getValue();
            int casilla = e.getKey();
            v.acciones().put(casilla, a);
            if (a.startsWith("c:")) {
                int i = Integer.parseInt(a.substring(2)) - 1;
                if (i >= l.size()) continue;
                Cronista.Capitulo c = l.get(i);
                inv.setItem(casilla, Marco.icono(Material.BOOK, Component.text(c.titulo(), Paleta.DETALLE), List.of(
                        Marco.tenue("Historia " + (i + 1) + " de " + l.size()), Component.empty(),
                        Marco.accion("Clic para leerla")), false));
            } else if (a.equals("cerrar")) {
                inv.setItem(casilla, Marco.icono(Material.BARRIER, Component.text("Cerrar", Paleta.AVISO),
                        List.of(Marco.accion("Clic para cerrar")), false));
            } else {
                Boton b = porId.get(a);
                if (b != null) inv.setItem(casilla, Marco.icono(b.icono(), Component.text(b.nombre(), Paleta.DETALLE), b.lore(), false));
            }
        }
        Marco.rellenar(inv);
        p.openInventory(inv);
        Marco.sonar(p, "item.book.page_turn", 0.7f, 1.0f);
    }

    // ------------------------------------------------------------------ clics

    @EventHandler
    public void alClic(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Vista v)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClick() != ClickType.LEFT) return;
        String a = v.acciones().get(e.getRawSlot());
        if (a == null || a.equals("eclipse")) return;
        long ahora = System.currentTimeMillis();
        Long antes = ultimoClic.get(p.getUniqueId());
        if (antes != null && ahora - antes < ESPERA_MS) return;
        ultimoClic.put(p.getUniqueId(), ahora);
        // Cerrar en mitad del evento de clic deja objetos fantasma en el cursor: un tick despues.
        hc.plugin().getServer().getScheduler().runTask(hc.plugin(), () -> {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Vista) p.closeInventory();
            if (a.equals("cerrar")) return;
            hc.seguro("cronista", () -> {
                if (a.startsWith("c:")) cronista.leer(p, Integer.parseInt(a.substring(2)) - 1);
                else Subcomandos.jugador().ejecutar(p, new String[]{a});
            });
        });
    }

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Vista) e.setCancelled(true);
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        ultimoClic.remove(e.getPlayer().getUniqueId());
    }
}
