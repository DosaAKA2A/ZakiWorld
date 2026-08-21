package net.ederus.edm.coinflip;

import net.ederus.edm.comun.Estilo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Locale;

/**
 * La moneda girando.
 *
 * Es TEATRO, y hay que tenerlo claro: cuando esto arranca la jugada ya esta
 * sorteada y pagada. Si se animara primero y se pagara al final, un reinicio a
 * mitad de la moneda dejaria el bote sin dueño y a dos jugadores discutiendo.
 *
 * Los dos jugadores miran EL MISMO inventario, asi que ven exactamente lo mismo
 * a la vez. Abrir uno por cabeza los dejaria descoordinados por un tick y el
 * perdedor juraria que a el le salio otra cosa.
 */
public final class Animacion implements Listener {

    private static final int TAM = 27;
    private static final int IZQUIERDA = 11;
    private static final int MONEDA = 13;
    private static final int DERECHA = 15;

    /** Marca la ventana como nuestra para poder cancelar los clics. */
    private static final class Vista implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final Plugin plugin;

    private boolean activa = true;
    private int vueltas = 16;
    private int ticksPorVuelta = 3;
    private int ticksAntesDeCerrar = 45;

    public Animacion(Plugin plugin) {
        this.plugin = plugin;
    }

    public void configurar(ConfigurationSection sec) {
        if (sec == null) return;
        activa = sec.getBoolean("activa", activa);
        vueltas = Math.max(2, Math.min(60, sec.getInt("vueltas", vueltas)));
        ticksPorVuelta = Math.max(1, Math.min(20, sec.getInt("ticks-por-vuelta", ticksPorVuelta)));
        ticksAntesDeCerrar = Math.max(0, Math.min(200, sec.getInt("ticks-antes-de-cerrar", ticksAntesDeCerrar)));
    }

    public boolean activa() { return activa; }

    /** Cuando cae la moneda. El mensaje del resultado va justo aqui: antes
     *  seria destriparlo, y despues el jugador ya se pregunta que ha pasado. */
    public int ticksHastaResultado() {
        return activa ? vueltas * ticksPorVuelta : 0;
    }

    /** Cuanto dura entera, contando lo que se queda enseñando al ganador. */
    public int duracionTicks() {
        return activa ? ticksHastaResultado() + ticksAntesDeCerrar : 0;
    }

    public void jugar(Mesa.Jugada j, Player creador, Player aceptante) {
        if (!activa) return;

        Vista vista = new Vista();
        Inventory inv = Bukkit.createInventory(vista, TAM,
                Estilo.legado("&x&0&0&8&3&F&D&lCARA O CRUZ &8| &x&D&7&F&3&F&F"
                        + Estilo.dinero(j.bote())));
        vista.inv = inv;

        ItemStack panel = pieza(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < TAM; i++) inv.setItem(i, panel.clone());

        inv.setItem(IZQUIERDA, cabeza(creador, j.apuesta().cantidad()));
        inv.setItem(DERECHA, cabeza(aceptante, j.apuesta().cantidad()));

        if (creador.isOnline()) creador.openInventory(inv);
        if (aceptante.isOnline()) aceptante.openInventory(inv);

        ItemStack caraCreador = cabezaSimple(creador);
        ItemStack caraRival = cabezaSimple(aceptante);
        boolean ganaCreador = j.ganador().equals(creador.getUniqueId());

        new BukkitRunnable() {
            int vuelta = 0;

            @Override
            public void run() {
                if (vuelta < vueltas) {
                    /* Mientras gira solo alterna las dos caras. La que vale es
                     * la de despues del bucle, que es la del ganador de verdad:
                     * aqui no se sortea nada, eso ya paso. */
                    boolean mostrandoCreador = (vuelta % 2 == 0);
                    inv.setItem(MONEDA, marcar(mostrandoCreador ? caraCreador.clone() : caraRival.clone(),
                            Estilo.texto("...", Estilo.CLARO), List.of()));
                    sonar(creador, aceptante, "ui.button.click", 1.4f);
                    vuelta++;
                    return;
                }

                ItemStack ganadora = marcar(
                        (ganaCreador ? caraCreador : caraRival).clone(),
                        Estilo.legado("&#4FFF55&lGANA " + j.nombreGanador()),
                        List.of(Estilo.texto("Se lleva " + Estilo.dinero(j.premio()), Estilo.VENTA)));
                inv.setItem(MONEDA, ganadora);

                ItemStack verde = pieza(Material.LIME_STAINED_GLASS_PANE, Component.text(" "), List.of());
                for (int i = 0; i < TAM; i++) {
                    if (i != IZQUIERDA && i != DERECHA && i != MONEDA) inv.setItem(i, verde.clone());
                }
                sonar(creador, aceptante, "entity.player.levelup", 1f);

                cancel();
                if (ticksAntesDeCerrar > 0) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        cerrarSiEsNuestra(creador, vista);
                        cerrarSiEsNuestra(aceptante, vista);
                    }, ticksAntesDeCerrar);
                }
            }
        }.runTaskTimer(plugin, ticksPorVuelta, ticksPorVuelta);
    }

    /** Solo se cierra si sigue mirando ESTA ventana: si ya abrio otra cosa, no
     *  se le cierra en las narices. */
    private static void cerrarSiEsNuestra(Player p, Vista vista) {
        if (p == null || !p.isOnline()) return;
        if (p.getOpenInventory().getTopInventory().getHolder() == vista) p.closeInventory();
    }

    private static void sonar(Player a, Player b, String clave, float tono) {
        try {
            Sound s = Registry.SOUNDS.get(NamespacedKey.minecraft(clave.toLowerCase(Locale.ROOT)));
            if (s == null) return;
            if (a != null && a.isOnline()) a.playSound(a.getLocation(), s, 1f, tono);
            if (b != null && b.isOnline()) b.playSound(b.getLocation(), s, 1f, tono);
        } catch (Exception e) {
            /* Un sonido que no existe en esta version no puede tumbar la jugada. */
        }
    }

    // --------------------------------------------------------------- piezas

    private static ItemStack cabeza(Player quien, double puesto) {
        return marcar(cabezaSimple(quien),
                Estilo.texto(quien.getName(), Estilo.CLARO),
                List.of(Estilo.texto("Apuesta " + Estilo.dinero(puesto), Estilo.COMPRA)));
    }

    private static ItemStack cabezaSimple(OfflinePlayer quien) {
        ItemStack pila = new ItemStack(Material.PLAYER_HEAD);
        if (pila.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(quien);
            pila.setItemMeta(meta);
        }
        return pila;
    }

    private static ItemStack pieza(Material material, Component titulo, List<Component> lore) {
        return marcar(new ItemStack(material), titulo, lore);
    }

    private static ItemStack marcar(ItemStack pila, Component titulo, List<Component> lore) {
        ItemMeta meta = pila.getItemMeta();
        if (meta != null) {
            meta.displayName(titulo.decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            pila.setItemMeta(meta);
        }
        return pila;
    }

    // ---------------------------------------------------------------- clics

    @EventHandler
    public void alPulsar(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof Vista) e.setCancelled(true);
    }

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Vista) e.setCancelled(true);
    }
}
