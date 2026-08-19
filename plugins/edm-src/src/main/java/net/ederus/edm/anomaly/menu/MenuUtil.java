package net.ederus.edm.anomaly.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.edm.anomaly.core.Compat;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Los ladrillos de los menus. Mismo lenguaje visual que el menu de Rip: marco de
 * cristal negro, titulos en negrita con el simbolo de la anomalia y una linea final
 * en amarillo que dice siempre que hace el clic.
 */
public final class MenuUtil {

    public static final TextColor GOLD = TextColor.color(0xFFD966);
    public static final TextColor SOFT = TextColor.color(0x8A8A8A);
    public static final TextColor DIM = TextColor.color(0x555555);
    public static final TextColor LABEL = TextColor.color(0x404040);
    public static final TextColor LOOT = TextColor.color(0xFFC64D);

    private MenuUtil() {
    }

    public static ItemStack pane() {
        return simple(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
    }

    public static ItemStack pane(Material material) {
        return simple(material, Component.empty(), List.of());
    }

    public static void frame(Inventory inv, int[] slots) {
        ItemStack filler = pane();
        for (int s : slots) inv.setItem(s, filler);
    }

    public static ItemStack simple(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(clean(lore));
        hideAll(meta);
        item.setItemMeta(meta);
        return item;
    }

    /** Copia el objeto tal cual y solo le cambia el lore, para no perder su NBT. */
    public static ItemStack decorate(ItemStack original, Component name, List<Component> lore, boolean glow) {
        ItemStack item = original.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        if (name != null) meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(clean(lore));
        if (glow && Compat.glow() != null) meta.addEnchant(Compat.glow(), 1, true);
        hideAll(meta);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack icon(Material material, Component name, List<Component> lore, boolean glow) {
        return icon(new ItemStack(material), name, lore, glow);
    }

    /**
     * Igual, pero partiendo de un objeto ya hecho. Lo necesitan las anomalias cuyo
     * icono no se puede describir con un material a secas, como una cabeza con skin.
     */
    public static ItemStack icon(ItemStack base, Component name, List<Component> lore, boolean glow) {
        ItemStack item = base.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(clean(lore));
        if (glow && Compat.glow() != null) meta.addEnchant(Compat.glow(), 1, true);
        hideAll(meta);
        item.setItemMeta(meta);
        return item;
    }

    private static List<Component> clean(List<Component> lore) {
        List<Component> out = new ArrayList<>(lore.size());
        for (Component c : lore) out.add(c.decoration(TextDecoration.ITALIC, false));
        return out;
    }

    public static void hideAll(ItemMeta meta) {
        meta.addItemFlags(ItemFlag.values());
    }

    // ------------------------------------------------------------------ textos

    public static Component title(String text, TextColor color) {
        return Component.text("✦ " + text, color, TextDecoration.BOLD);
    }

    public static Component line(String text) {
        return Component.text(text, SOFT);
    }

    public static Component field(String label, String value, TextColor valueColor) {
        return Component.text(label + "  ", LABEL).append(Component.text(value, valueColor));
    }

    public static Component action(String text) {
        return Component.text("► " + text, NamedTextColor.YELLOW);
    }

    public static Component actionSecondary(String text) {
        return Component.text("► " + text, NamedTextColor.AQUA);
    }

    public static Component on() {
        return Component.text("ACTIVADO", NamedTextColor.GREEN, TextDecoration.BOLD);
    }

    public static Component off() {
        return Component.text("DESACTIVADO", NamedTextColor.RED, TextDecoration.BOLD);
    }

    public static Component state(boolean value) {
        return value ? on() : off();
    }

    public static Component blank() {
        return Component.empty();
    }

    /** Corta un texto largo en lineas de lore de ancho comodo. */
    public static List<Component> wrap(String text, int width, TextColor color) {
        List<Component> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (current.length() + word.length() + 1 > width && current.length() > 0) {
                out.add(Component.text(current.toString(), color));
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) out.add(Component.text(current.toString(), color));
        return out;
    }

    public static String romanPhase(int phase) {
        return switch (phase) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            default -> "todas";
        };
    }

    public static TextColor phaseColor(int phase) {
        return switch (phase) {
            case 1 -> NamedTextColor.WHITE;
            case 2 -> GOLD;
            case 3 -> NamedTextColor.RED;
            default -> NamedTextColor.AQUA;
        };
    }

    public static String seconds(int ticks) {
        double s = ticks / 20.0;
        if (Math.abs(s - Math.rint(s)) < 0.05) return ((int) Math.rint(s)) + "s";
        return (Math.round(s * 10) / 10.0) + "s";
    }
}
