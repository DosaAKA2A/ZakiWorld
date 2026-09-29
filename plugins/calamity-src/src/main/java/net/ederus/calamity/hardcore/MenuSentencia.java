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
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calamity 1.8.0 · "CALAMITY | Sentencia": el menu del NPC de los contratos de Ambush (lo abre
 * /calamidad abrir <p> sentencia, el clic de Citizens; ver Npcs).
 *
 * Como los demas menus de Calamity (Marco): 54 casillas con el marco de cristal negro, arriba en el
 * centro lo que es y lo que cuesta, en medio una cabeza por cada jugador que esta ahora en Calamity
 * fuera del spawn (su nombre y "Contrato: N Esencias"), las flechas de pagina en las esquinas de
 * abajo y Cerrar en el centro.
 *
 * Pagar pide dos clics en la misma cabeza: el primero la elige (brilla y dice "Clic otra vez para
 * pagarlo"), el segundo paga. En Bedrock tocar un icono para leerlo ya es un clic, y un contrato no
 * se paga sin querer. Lo que no se puede (las reglas de Ambush.motivo) se lee en la cabeza y, al
 * pulsarla, en el chat; no cobra nada. Como el Altar, solo se usa fuera de Calamity o en su spawn.
 */
final class MenuSentencia implements Listener {

    static final String FUERA = "La Sentencia solo se abre fuera de Calamity o en su spawn.";
    private static final long ESPERA_MS = 500;
    /** Lo que dura una eleccion: pasado esto, el segundo clic vuelve a ser el primero. */
    private static final long ELECCION_MS = 10_000;
    private static final int INFO = 4, CENTRO = 22;

    /** La ventana abierta: que hace cada casilla, en que hoja esta y a quien ha elegido con el primer clic. */
    static final class Pantalla implements InventoryHolder {
        final Map<Integer, String> acciones = new HashMap<>();
        int hoja;
        UUID elegido;
        long elegidoEn;

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final Hardcore hc;
    private final Ambush ambush;
    private final Map<UUID, Long> ultimoClic = new HashMap<>();

    MenuSentencia(Hardcore hc, Ambush ambush) {
        this.hc = hc;
        this.ambush = ambush;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
    }

    void parar() {
        HandlerList.unregisterAll(this);
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Pantalla) p.closeInventory();
        }
        ultimoClic.clear();
    }

    /** Abre la Sentencia (el NPC ya ha mirado que este fuera de Calamity o en su spawn). */
    void abrir(Player p) {
        Pantalla pa = new Pantalla();
        Inventory inv = hc.plugin().getServer().createInventory(pa, 54, Marco.T_SENTENCIA.componente());
        pintar(inv, p, pa);
        p.openInventory(inv);
        Marco.sonar(p, "block.bell.resonate", 0.5f, 0.6f);
    }

    /** Quien sale en la lista: conectado, jugando en Calamity y fuera del spawn (menos el que mira). */
    private List<Player> presas(Player quien) {
        List<Player> out = new ArrayList<>();
        for (Player o : hc.plugin().getServer().getOnlinePlayers()) {
            if (o.equals(quien) || !hc.esHardcore(o) || !hc.cuenta(o) || o.isDead() || hc.enSpawn(o)) continue;
            out.add(o);
        }
        out.sort(Comparator.comparing(o -> o.getName().toLowerCase(java.util.Locale.ROOT)));
        return out;
    }

    private void pintar(Inventory inv, Player p, Pantalla pa) {
        inv.clear();
        pa.acciones.clear();
        int precio = ambush.ajustes().precio;
        inv.setItem(INFO, cabecera(p, precio));
        List<Player> lista = presas(p);
        List<Marco.Sitio> sitios = Marco.rejilla(lista.size());
        int total = Marco.hojas(sitios);
        pa.hoja = Math.max(0, Math.min(pa.hoja, total - 1));
        if (pa.elegido != null && System.currentTimeMillis() - pa.elegidoEn > ELECCION_MS) pa.elegido = null;
        for (Marco.Sitio s : sitios) {
            if (s.hoja() != pa.hoja) continue;
            Player o = lista.get(s.indice());
            inv.setItem(s.casilla(), cabeza(p, o, pa, precio));
            pa.acciones.put(s.casilla(), "p:" + o.getUniqueId());
        }
        if (lista.isEmpty()) {
            inv.setItem(CENTRO, Marco.icono(Material.GRAY_DYE, Component.text("Nadie en Calamity ahora", Paleta.TENUE),
                    List.of(Marco.tenue("Solo salen los que están dentro,"), Marco.tenue("fuera del spawn.")), false));
        }
        if (pa.hoja > 0) {
            inv.setItem(Marco.ANTERIOR, Marco.flecha(-1, pa.hoja, total));
            pa.acciones.put(Marco.ANTERIOR, "hoja:" + (pa.hoja - 1));
        }
        if (pa.hoja < total - 1) {
            inv.setItem(Marco.SIGUIENTE, Marco.flecha(1, pa.hoja, total));
            pa.acciones.put(Marco.SIGUIENTE, "hoja:" + (pa.hoja + 1));
        }
        inv.setItem(Marco.abajo(inv.getSize()), Marco.cerrar());
        pa.acciones.put(Marco.abajo(inv.getSize()), "cerrar");
        Marco.rellenar(inv);
    }

    /** Arriba en el centro: que es la Sentencia, lo que cuesta, tu saldo y lo que te queda hoy. */
    private ItemStack cabecera(Player p, int precio) {
        Saldo s = hc.saldo();
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.texto("Paga para que Ambush vaya a por"));
        lore.add(Marco.texto("alguien que esté ahora en Calamity."));
        lore.add(Component.empty());
        lore.add(Marco.tenue("Tiene 1 minuto para irse por la puerta"));
        lore.add(Marco.tenue("o con un Cristal de Regreso. Si se"));
        lore.add(Marco.tenue("queda, Ambush aparece a su lado."));
        lore.add(Marco.tenue("Tú no cobras nada: si muere, deja su Eco."));
        lore.add(Component.empty());
        lore.add(Marco.dato("Contrato", Marco.esencias(precio)));
        lore.add(Marco.dato("Tu saldo", Marco.esencias(s == null ? 0 : s.de(p.getUniqueId()))));
        int quedan = ambush.restantesHoy(p.getUniqueId());
        lore.add(Marco.dato("Hoy", "te " + (quedan == 1 ? "queda " : "quedan ") + quedan + " de " + Ambush.CONTRATOS_DIA));
        lore.add(Marco.tenue("Contra cada jugador, uno cada " + Ambush.HORAS_PRESA + " h."));
        return Marco.icono(Material.NETHERITE_SWORD, Paleta.ambush("La Sentencia"), lore, false);
    }

    /** La cabeza de una presa: su nombre, lo que cuesta y el clic, o por que no se puede. */
    private ItemStack cabeza(Player p, Player o, Pantalla pa, int precio) {
        String no = ambush.motivo(p, o);
        boolean elegido = no == null && o.getUniqueId().equals(pa.elegido);
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.dato("Contrato", Marco.esencias(precio)));
        lore.add(Component.empty());
        if (no != null) {
            lore.add(Marco.porQueNo(ambush.texto(no, p)));
        } else if (elegido) {
            lore.add(Marco.texto("Pagas " + Marco.esencias(precio) + "."));
            lore.add(Marco.accion("Clic otra vez para pagarlo"));
        } else {
            lore.add(Marco.accion("Clic para elegirlo"));
        }
        return Marco.icono(Marco.cabeza(o.getUniqueId()), Component.text(o.getName(), Paleta.DETALLE), lore, elegido);
    }

    // ------------------------------------------------------------------ clics

    @EventHandler
    public void alClic(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Pantalla pa)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClick() != ClickType.LEFT) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getInventory().getSize()) return;
        String accion = pa.acciones.get(slot);
        if (accion == null) return;
        long ahora = System.currentTimeMillis();
        Long antes = ultimoClic.get(p.getUniqueId());
        if (antes != null && ahora - antes < ESPERA_MS) return;
        ultimoClic.put(p.getUniqueId(), ahora);
        Inventory inv = e.getInventory();
        hc.seguro("ambush", () -> accion(p, pa, inv, accion));
    }

    private void accion(Player p, Pantalla pa, Inventory inv, String accion) {
        if (accion.equals("cerrar")) {
            cerrar(p);
            return;
        }
        // Se abrio en un sitio bueno; si entretanto ha entrado en Calamity (fuera del spawn), nada.
        if (!Marco.puedeAltar(hc, p)) {
            p.sendMessage(ComandoCalamity.mensaje(FUERA));
            Marco.sonidoNo(p);
            cerrar(p);
            return;
        }
        if (accion.startsWith("hoja:")) {
            pa.hoja = Integer.parseInt(accion.substring(5));
            pa.elegido = null;
            despues(() -> pintar(inv, p, pa));
            Marco.sonidoPestana(p);
            return;
        }
        if (!accion.startsWith("p:")) return;
        UUID id = UUID.fromString(accion.substring(2));
        Player presa = hc.plugin().getServer().getPlayer(id);
        if (presa == null) {
            p.sendMessage(ComandoCalamity.mensaje("Ya no está conectado."));
            Marco.sonidoNo(p);
            despues(() -> pintar(inv, p, pa));
            return;
        }
        String no = ambush.motivo(p, presa);
        if (no != null) {
            p.sendMessage(ComandoCalamity.mensaje(ambush.texto(no, p)));
            Marco.sonidoNo(p);
            pa.elegido = null;
            despues(() -> pintar(inv, p, pa));
            return;
        }
        long ahora = System.currentTimeMillis();
        if (!id.equals(pa.elegido) || ahora - pa.elegidoEn > ELECCION_MS) {
            // El primer clic solo la elige.
            pa.elegido = id;
            pa.elegidoEn = ahora;
            Marco.sonar(p, "ui.button.click", 0.6f, 0.8f);
            despues(() -> pintar(inv, p, pa));
            return;
        }
        pa.elegido = null;
        if (ambush.pagarContrato(p, presa)) {
            Marco.sonar(p, "block.anvil.land", 0.5f, 0.6f);
            cerrar(p);
        } else {
            Marco.sonidoNo(p);
            despues(() -> pintar(inv, p, pa));
        }
    }

    /** Repintar o cerrar dentro del evento de clic deja objetos fantasma en el cursor: un tick despues. */
    private void despues(Runnable r) {
        hc.plugin().getServer().getScheduler().runTask(hc.plugin(), () -> hc.seguro("ambush", r));
    }

    private void cerrar(Player p) {
        despues(() -> {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Pantalla) p.closeInventory();
        });
    }

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Pantalla) e.setCancelled(true);
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        ultimoClic.remove(e.getPlayer().getUniqueId());
    }
}
