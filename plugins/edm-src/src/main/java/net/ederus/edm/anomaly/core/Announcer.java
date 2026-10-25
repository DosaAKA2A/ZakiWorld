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
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.ederus.edm.comun.Compat;

/**
 * El anuncio del chat.
 *
 * El nombre de la anomalia no es texto suelto: al pasar el raton cuenta que anomalia
 * es, de donde viene y, en otro color, que suelta. El warp del Coliseo se pulsa y
 * lleva directo a la pelea.
 */
public final class Announcer {

    private static final TextColor SOFT = TextColor.color(0x8A8A8A);
    private static final TextColor DIM = TextColor.color(0x555555);
    private static final TextColor LINE = TextColor.color(0x3A3A3A);
    /** El color reservado al botin, distinto del de la anomalia a proposito. */
    private static final TextColor LOOT = TextColor.color(0xFFC64D);
    /** El celeste del prefijo de Anomaly. */
    private static final TextColor BRAND = TextColor.color(0x9BD7E4);

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
            /* El titulo es el NOMBRE, no la palabra "anomalia": lo que la gente tiene que
             * leer de un vistazo es quien ha salido y como llegar. Con un warp puesto,
             * el subtitulo es "ve /warp Coliseo"; sin el, las coordenadas. */
            String warp = plugin.settings().announceWarp();
            Location l = event.where();
            Component abajo = warp.isEmpty()
                    ? Component.text(l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(), NamedTextColor.GRAY)
                    : Component.text("ve ", NamedTextColor.GRAY)
                            .append(Component.text(warp, NamedTextColor.WHITE));
            Title title = Title.title(
                    Component.text(event.type().display(), event.type().color(), TextDecoration.BOLD),
                    abajo,
                    Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2600), Duration.ofMillis(800)));
            for (Player p : plugin.getServer().getOnlinePlayers()) p.showTitle(title);
        }
    }

    /**
     * Una sola linea: "✦ NOMBRE · La lucha ha empezado en el /warp Coliseo".
     *
     * Todas salen en el Coliseo, asi que las coordenadas sobran: lo que la gente
     * necesita es saber quien salio y pulsar para ir. Todo lo demas (que es, de donde
     * viene, que suelta) vive en el hover del nombre. El texto va en blanco: sobre el
     * fondo oscuro el gris no se leia.
     */
    private Component openingLine(ActiveAnomaly event) {
        AnomalyType type = event.type();

        Component name = Component.text(type.display(), type.color(), TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(dossier(event)));

        Component linea = Component.text("✦ ", type.color())
                .append(name)
                .append(Component.text(" · La lucha ha empezado en el ", NamedTextColor.WHITE));

        // El atajo, pulsable: clic y te lleva. Sin warp configurado se nombra el
        // Coliseo a secas, sin nada que pulsar.
        String warp = plugin.settings().announceWarp();
        if (warp.isEmpty()) {
            return linea.append(Component.text("Coliseo", NamedTextColor.WHITE));
        }
        return linea.append(Component.text(warp, NamedTextColor.WHITE, TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(Component.text("Clic para ir", SOFT)))
                .clickEvent(ClickEvent.runCommand(warp)));
    }

    /**
     * El contenido del hover: quien es, de donde viene y que suelta.
     * El botin va en su propio color para que se lea de un vistazo.
     */
    public Component dossier(ActiveAnomaly event) {
        AnomalyType type = event.type();
        AnomalyClass clazz = plugin.registry().classOf(type);
        Component c = Component.text("✦ ", type.color())
                .append(Component.text(type.display(), type.color()))
                .append(Component.newline())
                .append(Component.text(type.tagline(), SOFT))
                .append(Component.newline())
                .append(Component.text("Clase  ", DIM))
                .append(Component.text(clazz.display().toUpperCase(java.util.Locale.ROOT),
                        clazz.color(), TextDecoration.BOLD))
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
            return Component.text("Todavía sin botín configurado.", DIM);
        }
        Component c = Component.empty();
        List<DropEntry> entries = table.entries();
        int shown = Math.min(entries.size(), 6);
        for (int i = 0; i < shown; i++) {
            DropEntry e = entries.get(i);
            c = c.append(Component.text("· ", LOOT));
            if (e.unique()) {
                c = c.append(Component.text("✦ÚNICO ", NamedTextColor.AQUA, TextDecoration.BOLD));
            }
            c = c.append(DropTable.nameOf(net.ederus.edm.anomaly.drops.SetLoot.actual(e.item())).colorIfAbsent(e.unique() ? NamedTextColor.AQUA : LOOT))
                    .append(Component.text("  x" + e.amountLabel(), TextColor.color(0xC79A3A)))
                    .append(Component.text("   " + DropTable.trimChance(e.chance()) + "%", DIM))
                    .append(Component.text("   " + e.to().display(), DIM))
                    .append(Component.newline());
        }
        if (entries.size() > shown) {
            c = c.append(Component.text("  y " + (entries.size() - shown) + " cosa(s) más", DIM))
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

    /** Oro, plata y bronce del top de dano. */
    private static final TextColor[] PODIO = {
            TextColor.color(0xFFD24A), TextColor.color(0xD7DEE6), TextColor.color(0xD08A4E)};
    private static final TextColor NICK = TextColor.color(0xF2F2F2);
    private static final TextColor NOTA = TextColor.color(0x7A7A7A);

    /**
     * EDM 1.90.0 · El cierre con el top de dano, como lo aprobo Dosa (25/10/2026):
     *
     *   ✦ ANOMALY  El Conde ha caído.
     *
     *         Mayor daño infligido
     *
     *           ✦  Fulano      ·   42%
     *           ✦  Mengano     ·   25%
     *           ✦  Zutano      ·   12%
     *
     *         9 jugadores participaron  ·  4 min 31 s
     *
     * Sin negritas salvo el prefijo, sin marcos y sin decir que se lleva cada uno. El "·" y el % van
     * alineados aunque los nicks midan distinto (ver {@link #relleno}).
     */
    public void defeated(ActiveAnomaly event, List<String> report) {
        AnomalyType type = event.type();
        Component cabecera = Component.text("✦ ", BRAND)
                .append(Component.text("ANOMALY", BRAND, TextDecoration.BOLD))
                .append(Component.text("  ", NamedTextColor.GRAY))
                .append(Component.text(type.display(), type.color()))
                .append(Component.text(" ha caído.", NamedTextColor.GRAY));

        List<Map.Entry<UUID, Double>> orden = new ArrayList<>(event.damage().entrySet());
        orden.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        double total = 0;
        for (Map.Entry<UUID, Double> en : orden) total += Math.max(0, en.getValue());

        List<String> nombres = new ArrayList<>();
        List<Integer> porcentajes = new ArrayList<>();
        for (Map.Entry<UUID, Double> en : orden) {
            if (nombres.size() == 3 || total <= 0) break;
            OfflinePlayer op = plugin.getServer().getOfflinePlayer(en.getKey());
            nombres.add(op.getName() == null ? "?" : op.getName());
            porcentajes.add((int) Math.round(en.getValue() * 100.0 / total));
        }

        List<Component> lineas = new ArrayList<>();
        lineas.add(Component.empty());
        lineas.add(cabecera);
        lineas.add(Component.empty());
        if (nombres.isEmpty()) {
            lineas.add(Component.text("        Nadie llegó a hacerle daño.", NOTA));
        } else {
            lineas.add(Component.text("        Mayor daño infligido", NOTA));
            lineas.add(Component.empty());
            int ancho = 0;
            for (String n : nombres) ancho = Math.max(ancho, ancho(n, false));
            ancho += 12;
            for (int i = 0; i < nombres.size(); i++) {
                TextColor c = PODIO[i];
                lineas.add(Component.text("          ✦  ", c)
                        .append(Component.text(nombres.get(i), NICK))
                        .append(relleno(ancho - ancho(nombres.get(i), false)))
                        .append(Component.text("·   ", DIM))
                        .append(Component.text(porcentajes.get(i) + "%", c)));
            }
            lineas.add(Component.empty());
            int n = event.participants();
            lineas.add(Component.text("        ")
                    .append(Component.text(String.valueOf(n), NamedTextColor.WHITE))
                    .append(Component.text(n == 1 ? " jugador participó" : " jugadores participaron", NOTA))
                    .append(Component.text("  ·  ", DIM))
                    .append(Component.text(duracion(event.elapsedSeconds()), NOTA)));
        }
        lineas.add(Component.empty());
        for (Component l : lineas) plugin.getServer().sendMessage(l);

        if (plugin.settings().announceSound()) {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                Compat.sound(p.getWorld(), p.getLocation(), "ui.toast.challenge_complete", 0.7f, 1.0f);
            }
        }
        if (!report.isEmpty()) {
            plugin.getLogger().info("Botín repartido (" + type.id() + "): " + String.join(", ", report));
        }
    }

    public void expired(ActiveAnomaly event) {
        plugin.getServer().sendMessage(Component.text("✦ ", event.type().color())
                .append(Component.text("La anomalía se cerro sola. ", NamedTextColor.GRAY))
                .append(Component.text(event.type().display() + " sigue del otro lado.", DIM)));
    }

    /** "4 min 31 s" / "45 s". */
    private static String duracion(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return m > 0 ? (m + " min " + s + " s") : (s + " s");
    }

    /**
     * Ancho en pixeles de un texto con la fuente por defecto de Minecraft (cada glifo + 1 de
     * separacion; la negrita suma 1 por caracter). Vale para nicks: letras, numeros y "_".
     */
    static int ancho(String s, boolean negrita) {
        int px = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int w;
            if ("i!.,:;|'".indexOf(ch) >= 0) w = 2;
            else if (ch == 'l' || ch == '`') w = 3;
            else if ("It[] ".indexOf(ch) >= 0) w = 4;
            else if ("fk<>\"*()".indexOf(ch) >= 0) w = 5;
            else if (ch == '@' || ch == '~') w = 7;
            else w = 6;
            px += w + (negrita ? 1 : 0);
        }
        return px;
    }

    /** Espacios que suman exactamente px pixeles: normales de 4 y en negrita de 5. */
    static Component relleno(int px) {
        px = Math.max(0, px);
        for (int negritas = 0; negritas <= 3; negritas++) {
            int resto = px - 5 * negritas;
            if (resto >= 0 && resto % 4 == 0) {
                return Component.text(" ".repeat(resto / 4))
                        .append(Component.text(" ".repeat(negritas)).decoration(TextDecoration.BOLD, true));
            }
        }
        return Component.text(" ".repeat(Math.round(px / 4f)));
    }

}
