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

    /**
     * La tabla esta partida en dos zonas y la zona la decide DONDE se coloca el
     * objeto, no un gesto escondido: la primera fila del menu son los UNICOS y las
     * de abajo los corrientes. Asi se ve de un vistazo cuales son las piezas que
     * importan, que es justo lo que no se entendia con una marca invisible.
     */
    public static final int UNICOS = 7;
    public static final int COMUNES = 14;

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
        return add(stack, false);
    }

    /** Anade a una zona u otra; devuelve false si esa zona ya esta llena. */
    public boolean add(ItemStack stack, boolean unico) {
        if (unico ? uniques().size() >= UNICOS : commons().size() >= COMUNES) return false;
        DropEntry entry = DropEntry.of(stack);
        entry.unique(unico);
        // Un unico nace raro: si sale con la misma probabilidad que el resto no es
        // una pieza especial, es un objeto mas puesto en otra fila.
        if (unico) entry.chance(1.0);
        entries.add(entry);
        return true;
    }

    /** Los de la fila de arriba, en el orden en que se pusieron. */
    public List<DropEntry> uniques() {
        List<DropEntry> out = new ArrayList<>();
        for (DropEntry e : entries) {
            if (e.unique()) out.add(e);
        }
        return out;
    }

    /** Los corrientes, los de las filas de abajo. */
    public List<DropEntry> commons() {
        List<DropEntry> out = new ArrayList<>();
        for (DropEntry e : entries) {
            if (!e.unique()) out.add(e);
        }
        return out;
    }

    /** Quita esta entrada exacta, este en la zona que este. */
    public void remove(DropEntry entry) {
        entries.remove(entry);
    }

    public DropEntry get(int index) {
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    public void remove(int index) {
        if (index >= 0 && index < entries.size()) entries.remove(index);
    }

    /** El unico mas raro de la tabla, que es el que la representa. */
    public DropEntry uniqueEntry() {
        DropEntry best = null;
        for (DropEntry e : entries) {
            if (!e.unique()) continue;
            if (best == null || e.chance() < best.chance()) best = e;
        }
        return best;
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
            return Component.text("Sin botín configurado", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false);
        }
        Component line = Component.text("", accent);
        if (star.unique()) {
            line = line.append(Component.text("✦ÚNICO ", NamedTextColor.AQUA, TextDecoration.BOLD));
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
