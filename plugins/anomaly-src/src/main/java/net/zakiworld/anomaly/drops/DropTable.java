package net.zakiworld.anomaly.drops;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** La tabla de botin de una anomalia: la lista de objetos mas los comandos y la experiencia. */
public final class DropTable {

    /** Cuantos objetos distintos caben. Coincide con las casillas editables del menu. */
    public static final int CAPACITY = 21;

    private final String anomalyId;
    private final List<DropEntry> entries = new ArrayList<>();
    private final List<String> commands = new ArrayList<>();
    private int experience = 500;

    public DropTable(String anomalyId) {
        this.anomalyId = anomalyId;
    }

    public String anomalyId() {
        return anomalyId;
    }

    public List<DropEntry> entries() {
        return entries;
    }

    public List<String> commands() {
        return commands;
    }

    public int experience() {
        return experience;
    }

    public void experience(int experience) {
        this.experience = Math.max(0, Math.min(20000, experience));
    }

    public boolean isEmpty() {
        return entries.isEmpty() && commands.isEmpty();
    }

    public boolean add(ItemStack stack) {
        if (entries.size() >= CAPACITY) return false;
        entries.add(DropEntry.of(stack));
        return true;
    }

    public DropEntry get(int index) {
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    public void remove(int index) {
        if (index >= 0 && index < entries.size()) entries.remove(index);
    }

    /**
     * El objeto "estrella" que se enseña en el hover del anuncio: el mas raro de todos.
     * Si hay empate gana el primero, que es el que el admin puso arriba del todo.
     */
    public DropEntry headline() {
        DropEntry best = null;
        for (DropEntry e : entries) {
            if (best == null || e.chance() < best.chance()) best = e;
        }
        return best;
    }

    /** Nombre legible de un objeto: usa el nombre puesto a mano si lo tiene. */
    public static Component nameOf(ItemStack stack) {
        if (stack == null) return Component.text("nada", NamedTextColor.DARK_GRAY);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            Component custom = meta.displayName();
            if (custom != null) return custom;
            if (meta.hasItemName()) return meta.itemName();
        }
        return Component.translatable(stack.getType().translationKey());
    }

    /** Linea corta para el hover del anuncio y para los lores del menu. */
    public Component summaryLine(TextColor accent) {
        DropEntry star = headline();
        if (star == null) {
            return Component.text("Sin botin configurado", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false);
        }
        return Component.text("", accent)
                .append(nameOf(star.item()).colorIfAbsent(accent))
                .append(Component.text("  x" + star.amountLabel(), NamedTextColor.GRAY))
                .append(Component.text("  " + trimChance(star.chance()) + "%", NamedTextColor.DARK_GRAY))
                .decoration(TextDecoration.ITALIC, false);
    }

    public static String trimChance(double chance) {
        if (Math.abs(chance - Math.rint(chance)) < 0.05) return String.valueOf((int) Math.rint(chance));
        return String.valueOf(Math.round(chance * 10.0) / 10.0);
    }
}
