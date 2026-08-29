package net.ederus.edm.anomaly.drops;

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

    /** Tope de experiencia por jefe. Da para x20 largos del valor que sea. */
    public static final int MAX_EXPERIENCE = 1000000;

    public void experience(int experience) {
        this.experience = Math.max(0, Math.min(MAX_EXPERIENCE, experience));
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
     * Marca un objeto como el UNICO de la tabla y desmarca cualquier otro: solo
     * puede haber uno. Marcarlo lo vuelve ademas super raro de serie (1%), aunque la
     * probabilidad se puede reajustar despues como la de cualquier otro.
     *
     * @return true si quedo marcado, false si quedo desmarcado (era el unico y se repitio el toque)
     */
    public boolean markUnique(int index) {
        DropEntry target = get(index);
        if (target == null) return false;
        boolean wasUnique = target.unique();
        for (DropEntry e : entries) e.unique(false);
        if (!wasUnique) {
            target.unique(true);
            if (target.chance() > 5.0) target.chance(1.0);
        }
        return !wasUnique;
    }

    /** El objeto UNICO de la tabla, si el admin marco alguno. */
    public DropEntry uniqueEntry() {
        for (DropEntry e : entries) {
            if (e.unique()) return e;
        }
        return null;
    }

    /**
     * El objeto "estrella" que se enseña en el hover del anuncio. Si hay un UNICO
     * marcado es el; si no, el mas raro de todos, y en empate gana el primero, que
     * es el que el admin puso arriba del todo.
     */
    public DropEntry headline() {
        DropEntry unique = uniqueEntry();
        if (unique != null) return unique;
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
        Component line = Component.text("", accent);
        if (star.unique()) {
            line = line.append(Component.text("✦UNICO ", NamedTextColor.AQUA, TextDecoration.BOLD));
        }
        return line
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
