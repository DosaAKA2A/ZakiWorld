package net.ederus.edm.anomaly.drops;

import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.Type;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.comun.Compat;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * EDM 1.90.0 · Fragmentos y Artefacto Anomalo.
 *
 * Quien pelea contra una anomalia y no se lleva ninguna pieza recibe de 1 a 3 Fragmentos Anomalos
 * segun su dano. Los fragmentos son de todas las anomalias (no hay uno por jefe) y se canjean en un
 * NPC (/anomaly canjear <jugador>, por consola desde Citizens) por un Artefacto Anomalo. El
 * artefacto, con clic derecho, da una armadura de anomalia al azar: comun (las piezas normales de
 * las tablas de botin) o, con fragmentos.rara % de probabilidad, rara (las de la fila de unicos).
 *
 * Los dos objetos son de MMOItems (tipo ANOMALY_ITEMS): se crean y se retocan alli; aqui solo se
 * reconocen por tipo e id. El artefacto es una lampara de cobre: no se deja colocar.
 */
public final class Fragmentos implements Listener {

    private static final TextColor BRAND = TextColor.color(0x9BD7E4);
    private static final TextColor SOFT = TextColor.color(0xAAAAAA);

    private final AnomalyPlugin plugin;
    private final Random random = new Random();

    public Fragmentos(AnomalyPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ objetos

    private ItemStack crear(String id, int cantidad) {
        if (!SetLoot.disponible()) return null;
        try {
            Type tipo = MMOItems.plugin.getTypes().get(plugin.settings().fragmentsType());
            ItemStack item = tipo == null ? null : MMOItems.plugin.getItem(tipo, id);
            if (item == null) return null;
            item.setAmount(cantidad);
            return item;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean es(ItemStack item, String id) {
        if (item == null || item.getType().isAir() || !SetLoot.disponible()) return false;
        try {
            return plugin.settings().fragmentsType().equalsIgnoreCase(MMOItems.getTypeName(item))
                    && id.equalsIgnoreCase(MMOItems.getID(item));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Lo mete en el inventario; lo que no quepa cae a sus pies, solo para el. */
    static void entregar(Player p, ItemStack item) {
        Map<Integer, ItemStack> sobra = p.getInventory().addItem(item);
        for (ItemStack resto : sobra.values()) {
            Item suelto = p.getWorld().dropItem(p.getLocation(), resto);
            suelto.setOwner(p.getUniqueId());
            suelto.setInvulnerable(true);
            suelto.setPickupDelay(0);
        }
    }

    private void decir(Player p, Component texto) {
        p.sendMessage(plugin.prefix().append(texto));
    }

    // ---------------------------------------------------------------- reparto

    /** Cuantos fragmentos le tocan con ese % del dano total: 1, 2 o 3 por tramos. */
    public int fragmentosPara(double porcentaje) {
        if (porcentaje >= plugin.settings().fragmentsHighPercent()) return 3;
        if (porcentaje >= plugin.settings().fragmentsMidPercent()) return 2;
        return 1;
    }

    /** Da los fragmentos al acabar una pelea. Devuelve false si el objeto no existe en MMOItems. */
    public boolean dar(Player p, int cantidad) {
        ItemStack item = crear(plugin.settings().fragmentId(), cantidad);
        if (item == null) return false;
        entregar(p, item);
        decir(p, Component.text("Recibes ", SOFT)
                .append(Component.text(cantidad + (cantidad == 1 ? " Fragmento Anómalo" : " Fragmentos Anómalos"), BRAND))
                .append(Component.text(" por tu aporte.", SOFT)));
        return true;
    }

    // ------------------------------------------------------------------ canje

    private int contar(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (es(it, plugin.settings().fragmentId())) n += it.getAmount();
        }
        return n;
    }

    private void quitar(Player p, int cantidad) {
        ItemStack[] cont = p.getInventory().getStorageContents();
        for (int i = 0; i < cont.length && cantidad > 0; i++) {
            ItemStack it = cont[i];
            if (!es(it, plugin.settings().fragmentId())) continue;
            int usa = Math.min(cantidad, it.getAmount());
            it.setAmount(it.getAmount() - usa);
            cantidad -= usa;
            if (it.getAmount() <= 0) cont[i] = null;
        }
        p.getInventory().setStorageContents(cont);
    }

    /** /anomaly canjear <jugador>: N fragmentos por un Artefacto Anomalo. */
    public void canjear(Player p) {
        int pide = plugin.settings().fragmentsPerArtifact();
        int tiene = contar(p);
        if (tiene < pide) {
            decir(p, Component.text("Necesitas ", SOFT)
                    .append(Component.text(pide + " Fragmentos Anómalos", BRAND))
                    .append(Component.text(". Llevas " + tiene + ".", SOFT)));
            Compat.sound(p.getWorld(), p.getLocation(), "entity.villager.no", 0.8f, 1f);
            return;
        }
        ItemStack artefacto = crear(plugin.settings().artifactId(), 1);
        if (artefacto == null) {
            decir(p, Component.text("El canje no está disponible ahora mismo.", NamedTextColor.RED));
            plugin.getLogger().warning("No existe " + plugin.settings().fragmentsType() + " "
                    + plugin.settings().artifactId() + " en MMOItems: el canje no puede dar el artefacto.");
            return;
        }
        quitar(p, pide);
        entregar(p, artefacto);
        decir(p, Component.text("Canjeas " + pide + " fragmentos por un ", SOFT)
                .append(Component.text("Artefacto Anómalo", TextColor.color(0xB39DF0)))
                .append(Component.text(".", SOFT)));
        Compat.sound(p.getWorld(), p.getLocation(), "block.amethyst_block.resonate", 0.9f, 1.2f);
    }

    // --------------------------------------------------------------- artefacto

    private static boolean esArmadura(ItemStack item) {
        String m = item.getType().name();
        return m.endsWith("_HELMET") || m.endsWith("_CHESTPLATE") || m.endsWith("_LEGGINGS")
                || m.endsWith("_BOOTS") || m.equals("PLAYER_HEAD") || m.equals("CARVED_PUMPKIN");
    }

    /** Las armaduras de MMOItems de todas las tablas: comunes (fila normal) o raras (fila de unicos). */
    private List<ItemStack> piezas(boolean raras) {
        List<ItemStack> out = new ArrayList<>();
        java.util.Set<String> vistas = new java.util.HashSet<>();
        for (DropTable t : plugin.drops().bossTables()) {
            for (DropEntry e : t.entries()) {
                if (e.unique() != raras || e.item() == null || !esArmadura(e.item())) continue;
                String id = SetLoot.idDe(e.item());
                if (id == null || !vistas.add(id)) continue;
                out.add(e.item());
            }
        }
        return out;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUsar(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack mano = e.getItem();
        if (!es(mano, plugin.settings().artifactId())) return;
        e.setCancelled(true);
        e.setUseItemInHand(Event.Result.DENY);
        e.setUseInteractedBlock(Event.Result.DENY);
        abrir(e.getPlayer(), mano);
    }

    /** Por si algo deja pasar la colocacion: la lampara del artefacto no se pone en el mundo. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onColocar(BlockPlaceEvent e) {
        if (es(e.getItemInHand(), plugin.settings().artifactId())) e.setCancelled(true);
    }

    private void abrir(Player p, ItemStack mano) {
        boolean rara = random.nextDouble() * 100.0 < plugin.settings().artifactRarePercent();
        List<ItemStack> bolsa = piezas(rara);
        if (bolsa.isEmpty()) bolsa = piezas(!rara);
        if (bolsa.isEmpty()) {
            decir(p, Component.text("El artefacto no responde ahora mismo.", NamedTextColor.RED));
            plugin.getLogger().warning("Artefacto Anomalo: no hay armaduras de MMOItems en las tablas de botin.");
            return;
        }
        ItemStack premio = SetLoot.actual(bolsa.get(random.nextInt(bolsa.size())));
        premio.setAmount(1);
        mano.setAmount(mano.getAmount() - 1);
        entregar(p, premio);
        decir(p, Component.text("Del artefacto sale ", SOFT)
                .append(DropTable.nameOf(premio))
                .append(Component.text(rara ? "  · rara" : "", TextColor.color(0xB39DF0))));
        Compat.sound(p.getWorld(), p.getLocation(), rara ? "ui.toast.challenge_complete" : "block.vault.open_shutter",
                0.9f, 1f);
        plugin.getLogger().info("Artefacto Anomalo de " + p.getName() + ": " + (rara ? "rara " : "comun ")
                + SetLoot.idDe(premio));
    }

    /** ¿Cuenta como "pieza" del botin (equipo de MMOItems que no sea un fragmento ni un artefacto)? */
    public boolean esPieza(ItemStack item) {
        if (item == null || !SetLoot.disponible()) return false;
        try {
            String tipo = MMOItems.getTypeName(item);
            return tipo != null && MMOItems.getID(item) != null
                    && !plugin.settings().fragmentsType().equalsIgnoreCase(tipo);
        } catch (Throwable t) {
            return false;
        }
    }
}
