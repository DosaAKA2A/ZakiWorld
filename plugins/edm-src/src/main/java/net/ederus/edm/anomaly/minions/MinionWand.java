package net.ederus.edm.anomaly.minions;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.ederus.edm.comun.menu.MenuUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * La vela: la herramienta de plantar generadores, al estilo del hacha del FAWE.
 *
 * Desde la ficha del esbirro se ajusta nivel, intervalo, tope y radio, y el boton
 * "Obtener generador" entrega una vela que LLEVA esa configuracion grabada. Click
 * derecho sobre un bloque = generador plantado ahi mismo, y la vela sigue en la
 * mano: se pueden sembrar diez salas seguidas sin volver al menu. La vela no se
 * coloca nunca como bloque; para deshacerse de ella basta tirarla.
 */
public final class MinionWand implements Listener {

    private final AnomalyPlugin plugin;
    private final NamespacedKey keyType;
    private final NamespacedKey keyMin;
    private final NamespacedKey keyMax;
    private final NamespacedKey keyInterval;
    private final NamespacedKey keyMaxAlive;
    private final NamespacedKey keyRadius;

    public MinionWand(AnomalyPlugin plugin) {
        this.plugin = plugin;
        this.keyType = new NamespacedKey(plugin, "vela_tipo");
        this.keyMin = new NamespacedKey(plugin, "vela_nivel_min");
        this.keyMax = new NamespacedKey(plugin, "vela_nivel_max");
        this.keyInterval = new NamespacedKey(plugin, "vela_intervalo");
        this.keyMaxAlive = new NamespacedKey(plugin, "vela_tope");
        this.keyRadius = new NamespacedKey(plugin, "vela_radio");
    }

    /** Fabrica la vela con la configuracion actual de la ficha del esbirro. */
    public ItemStack create(MinionType type) {
        ItemStack wand = new ItemStack(Material.CANDLE);
        ItemMeta meta = wand.getItemMeta();
        meta.displayName(Component.text("✦ Vela de generador", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                MenuUtil.field("Esbirro", type.display(), type.color()).decoration(TextDecoration.ITALIC, false),
                MenuUtil.field("Nivel", type.wandMinLevel() == type.wandMaxLevel()
                                ? String.valueOf(type.wandMinLevel())
                                : type.wandMinLevel() + " - " + type.wandMaxLevel(),
                        NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                MenuUtil.field("Reaparece", "cada " + type.wandIntervalSeconds() + "s",
                        NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                MenuUtil.field("Tope", type.wandMaxAlive() + " vivos a la vez",
                        NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                MenuUtil.action("Clic derecho en un bloque: plantar generador")
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("La vela no se gasta: siembra todo lo que quieras.", MenuUtil.DIM)
                        .decoration(TextDecoration.ITALIC, false)));
        MenuUtil.hideAll(meta);
        if (Compat.glow() != null) meta.addEnchant(Compat.glow(), 1, true);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyType, PersistentDataType.STRING, type.id());
        pdc.set(keyMin, PersistentDataType.INTEGER, type.wandMinLevel());
        pdc.set(keyMax, PersistentDataType.INTEGER, type.wandMaxLevel());
        pdc.set(keyInterval, PersistentDataType.INTEGER, type.wandIntervalSeconds());
        pdc.set(keyMaxAlive, PersistentDataType.INTEGER, type.wandMaxAlive());
        pdc.set(keyRadius, PersistentDataType.INTEGER, type.wandActivationRadius());
        wand.setItemMeta(meta);
        return wand;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.CANDLE || !item.hasItemMeta()) return;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String typeId = pdc.get(keyType, PersistentDataType.STRING);
        if (typeId == null) return;

        // Es NUESTRA vela: pase lo que pase, nunca se coloca como bloque.
        e.setCancelled(true);
        Player player = e.getPlayer();
        if (!plugin.mayUseGui(player)) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = e.getClickedBlock();
        if (block == null) return;

        MinionType type = plugin.minions().type(typeId);
        if (type == null) {
            player.sendMessage(plugin.prefix().append(Component.text(
                    "Ese esbirro ya no existe; la vela quedo huerfana.", NamedTextColor.RED)));
            return;
        }

        int min = orDefault(pdc.get(keyMin, PersistentDataType.INTEGER), 1);
        int max = orDefault(pdc.get(keyMax, PersistentDataType.INTEGER), min);
        int interval = orDefault(pdc.get(keyInterval, PersistentDataType.INTEGER), 30);
        int tope = orDefault(pdc.get(keyMaxAlive, PersistentDataType.INTEGER), 3);
        int radius = orDefault(pdc.get(keyRadius, PersistentDataType.INTEGER), 32);

        MinionSpawner spawner = plugin.minions().createSpawner(type,
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                min, max, interval, tope, radius);

        // La siembra se ve: anillo del color del esbirro sobre el bloque elegido.
        Location spot = block.getLocation().add(0.5, 1.0, 0.5);
        var dust = Compat.dust(type.colorRgb(), 1.4f);
        Fx.ring(spot.clone().add(0, 0.2, 0), 1.0, 18, l ->
                Compat.spawn(block.getWorld(), Compat.DUST, l, 1, 0, 0, 0, 0, dust));
        Compat.spawn(block.getWorld(), Compat.END_ROD, spot.clone().add(0, 0.5, 0), 10, 0.2, 0.35, 0.2, 0.02);
        Compat.sound(block.getWorld(), spot, "block.respawn_anchor.set_spawn", 0.8f, 1.4f);

        List<String> regions = plugin.protection().regionNames(spot);
        player.sendMessage(plugin.prefix()
                .append(Component.text("Generador plantado  ", NamedTextColor.GREEN))
                .append(Component.text(type.display(), type.color(), TextDecoration.BOLD))
                .append(Component.text("  Nv. " + spawner.levelLabel(), NamedTextColor.GOLD))
                .append(Component.text("  en " + block.getX() + " " + (block.getY() + 1) + " " + block.getZ(),
                        NamedTextColor.WHITE))
                .append(Component.text("  (" + block.getWorld().getName()
                        + (regions.isEmpty() ? "" : " · " + String.join(", ", regions)) + ")", MenuUtil.SOFT)));
        player.sendMessage(plugin.prefix().append(Component.text(
                "Empieza a generar en cuanto alguien entre en su radio. Se administra desde "
                        + "el menú, en la ficha del esbirro.", MenuUtil.SOFT)));
    }

    private static int orDefault(Integer v, int def) {
        return v == null ? def : v;
    }
}
