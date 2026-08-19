package net.ederus.edm.anomaly.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.drops.DropEntry;
import net.ederus.edm.anomaly.drops.DropTable;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;

/**
 * El anuncio del chat.
 *
 * La palabra "Anomalia" no es texto suelto: al pasar el raton cuenta que anomalia es,
 * de donde viene y, en otro color, que suelta. Las coordenadas se copian con un clic.
 */
public final class Announcer {

    private static final TextColor SOFT = TextColor.color(0x8A8A8A);
    private static final TextColor DIM = TextColor.color(0x555555);
    private static final TextColor LINE = TextColor.color(0x3A3A3A);
    /** El color reservado al botin, distinto del de la anomalia a proposito. */
    private static final TextColor LOOT = TextColor.color(0xFFC64D);

    private final AnomalyPlugin plugin;

    public Announcer(AnomalyPlugin plugin) {
        this.plugin = plugin;
    }

    // --------------------------------------------------------------------- apertura

    public void opened(ActiveAnomaly event) {
        if (!plugin.settings().announceEnabled()) return;
        plugin.getServer().sendMessage(Component.empty());
        plugin.getServer().sendMessage(openingLine(event));
        plugin.getServer().sendMessage(Component.empty());

        if (plugin.settings().announceSound()) {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                Compat.sound(p.getWorld(), p.getLocation(), "block.beacon.power_select", 0.6f, 0.7f);
                Compat.sound(p.getWorld(), p.getLocation(), "entity.ender_dragon.growl", 0.35f, 1.6f);
            }
        }
        if (plugin.settings().announceTitle()) {
            Title title = Title.title(
                    Component.text("✦ ANOMALIA ✦", event.type().color(), TextDecoration.BOLD),
                    Component.text(event.type().display(), NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2000), Duration.ofMillis(800)));
            for (Player p : plugin.getServer().getOnlinePlayers()) p.showTitle(title);
        }
    }

    /**
     * Una sola linea, sin marcos ni segunda fila de texto apagado.
     *
     * Todo lo demas (que es, de donde viene, que suelta, cuanto dura) vive en el hover,
     * que es donde el usuario quiere que este. En el chat solo la frase y las coordenadas,
     * y ambas en blanco: sobre el fondo oscuro el gris no se leia.
     */
    private Component openingLine(ActiveAnomaly event) {
        AnomalyType type = event.type();
        Location l = event.where();
        int step = plugin.settings().coordinatePrecision();
        int x = round(l.getBlockX(), step);
        int y = l.getBlockY();
        int z = round(l.getBlockZ(), step);

        Component word = Component.text("Anomalia", type.color(), TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(dossier(event)));

        String coordText = x + " " + y + " " + z;
        Component coords = Component.text(coordText, NamedTextColor.WHITE, TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(Component.text("Clic para copiar", SOFT)))
                .clickEvent(ClickEvent.copyToClipboard(coordText));

        return Component.text("✦ ", type.color())
                .append(Component.text("Una ", NamedTextColor.WHITE))
                .append(word)
                .append(Component.text(" ha aparecido en ", NamedTextColor.WHITE))
                .append(coords);
    }

    private static int round(int value, int step) {
        if (step <= 1) return value;
        return Math.round(value / (float) step) * step;
    }

    /**
     * El contenido del hover: quien es, de donde viene y que suelta.
     * El botin va en su propio color para que se lea de un vistazo.
     */
    public Component dossier(ActiveAnomaly event) {
        AnomalyType type = event.type();
        Component c = Component.text("✦ ", type.color())
                .append(Component.text(type.display(), type.color(), TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text(type.tagline(), SOFT))
                .append(Component.newline())
                .append(Component.text("Elemento  ", DIM))
                .append(Component.text(type.element().display(), type.element().color(), TextDecoration.BOLD))
                .append(Component.text("   " + type.element().terrain(), DIM))
                .append(Component.newline())
                .append(separator());

        c = c.append(Component.text("DE DONDE VIENE", NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.newline());
        for (String line : plugin.registry().origin(type)) {
            c = c.append(Component.text(line, SOFT)).append(Component.newline());
        }

        c = c.append(separator())
                .append(Component.text("AMENAZA", NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.newline());
        for (String line : plugin.registry().threat(type)) {
            c = c.append(Component.text("· ", DIM)).append(Component.text(line, SOFT)).append(Component.newline());
        }

        c = c.append(separator())
                .append(Component.text("SUELTA", LOOT, TextDecoration.BOLD))
                .append(Component.newline())
                .append(lootBlock(type.id()));
        return c;
    }

    /** Las lineas de botin del hover, en el color reservado al botin. */
    public Component lootBlock(String anomalyId) {
        DropTable table = plugin.drops().table(anomalyId);
        if (table.entries().isEmpty() && table.commands().isEmpty()) {
            return Component.text("Todavia sin botin configurado.", DIM);
        }
        Component c = Component.empty();
        List<DropEntry> entries = table.entries();
        int shown = Math.min(entries.size(), 6);
        for (int i = 0; i < shown; i++) {
            DropEntry e = entries.get(i);
            c = c.append(Component.text("· ", LOOT))
                    .append(DropTable.nameOf(e.item()).colorIfAbsent(LOOT))
                    .append(Component.text("  x" + e.amountLabel(), TextColor.color(0xC79A3A)))
                    .append(Component.text("   " + DropTable.trimChance(e.chance()) + "%", DIM))
                    .append(Component.text("   " + e.to().display(), DIM))
                    .append(Component.newline());
        }
        if (entries.size() > shown) {
            c = c.append(Component.text("  y " + (entries.size() - shown) + " cosa(s) mas", DIM))
                    .append(Component.newline());
        }
        if (!table.commands().isEmpty()) {
            c = c.append(Component.text("· ", LOOT))
                    .append(Component.text("Recompensas extra del servidor", LOOT))
                    .append(Component.newline());
        }
        if (table.experience() > 0) {
            c = c.append(Component.text("· ", LOOT))
                    .append(Component.text(table.experience() + " de experiencia", LOOT));
        }
        return c;
    }

    private static Component separator() {
        return Component.text("────────────────────────", LINE).append(Component.newline());
    }

    // ----------------------------------------------------------------------- cierre

    public void defeated(ActiveAnomaly event, List<String> report) {
        AnomalyType type = event.type();
        Component top = Component.text("✦ ", type.color())
                .append(Component.text(type.display(), type.color(), TextDecoration.BOLD))
                .append(Component.text(" ha caido.", NamedTextColor.GRAY));

        Component who = Component.text("   ", DIM);
        if (event.participants() == 0) {
            who = who.append(Component.text("Nadie reclamo el botin.", DIM));
        } else {
            who = who.append(Component.text(event.participants() + " ", NamedTextColor.WHITE))
                    .append(Component.text(event.participants() == 1 ? "jugador se lo llevo" : "jugadores se lo repartieron", NamedTextColor.GRAY))
                    .append(Component.text("   ·   ", DIM))
                    .append(Component.text(format(event.elapsedSeconds()), SOFT));
        }

        plugin.getServer().sendMessage(Component.empty());
        plugin.getServer().sendMessage(top);
        plugin.getServer().sendMessage(who);
        plugin.getServer().sendMessage(Component.empty());

        if (plugin.settings().announceSound()) {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                Compat.sound(p.getWorld(), p.getLocation(), "ui.toast.challenge_complete", 0.7f, 1.0f);
            }
        }
        if (!report.isEmpty()) {
            plugin.getLogger().info("Botin repartido (" + type.id() + "): " + String.join(", ", report));
        }
    }

    public void expired(ActiveAnomaly event) {
        plugin.getServer().sendMessage(Component.text("✦ ", event.type().color())
                .append(Component.text("La anomalia se cerro sola. ", NamedTextColor.GRAY))
                .append(Component.text(event.type().display() + " sigue del otro lado.", DIM)));
    }

    private static String format(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return m > 0 ? (m + "m " + s + "s") : (s + "s");
    }
}
