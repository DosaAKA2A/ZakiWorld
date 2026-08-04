package net.zakiworld.anomaly.boss;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.zakiworld.anomaly.core.Fx;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;

/**
 * Las tres barras apiladas del jefe. Cada una cubre un tercio de la vida y se vacian
 * de arriba abajo: cuando la de arriba llega a cero desaparece y la siguiente pasa a
 * ser la principal, que es el efecto que se buscaba.
 *
 * Minecraft apila las barras de jefe en el orden en que se le muestran al cliente,
 * asi que basta con mostrarlas siempre en el mismo orden y quitarlas al agotarse.
 */
public final class PhaseBars {

    public static final int PHASES = 3;

    private final BossBar[] bars = new BossBar[PHASES];
    private final boolean[] retired = new boolean[PHASES];
    private final Set<Player> viewers = new HashSet<>();
    private final String bossName;
    private final TextColor accent;

    public PhaseBars(String bossName, TextColor accent) {
        this.bossName = bossName;
        this.accent = accent;
        for (int i = 0; i < PHASES; i++) {
            bars[i] = BossBar.bossBar(title(i, false), 1.0f, colorFor(i), overlayFor(i));
        }
    }

    private static BossBar.Color colorFor(int index) {
        return switch (index) {
            case 0 -> BossBar.Color.WHITE;
            case 1 -> BossBar.Color.YELLOW;
            default -> BossBar.Color.RED;
        };
    }

    private static BossBar.Overlay overlayFor(int index) {
        return switch (index) {
            case 0 -> BossBar.Overlay.NOTCHED_6;
            case 1 -> BossBar.Overlay.NOTCHED_10;
            default -> BossBar.Overlay.NOTCHED_20;
        };
    }

    private static String roman(int index) {
        return switch (index) {
            case 0 -> "I";
            case 1 -> "II";
            default -> "III";
        };
    }

    private Component title(int index, boolean active) {
        TextColor phaseColor = switch (index) {
            case 0 -> NamedTextColor.WHITE;
            case 1 -> NamedTextColor.GOLD;
            default -> NamedTextColor.RED;
        };
        Component name = Component.text(bossName, accent, TextDecoration.BOLD);
        Component phase = Component.text("FASE " + roman(index), phaseColor);
        Component sep = Component.text("  ·  ", TextColor.color(0x555555));
        Component base = Component.text("✦ ", accent).append(name).append(sep).append(phase);
        if (active) {
            base = base.append(Component.text("  ◀", phaseColor));
        }
        return base;
    }

    /**
     * @param fraction vida restante del jefe entre 0 y 1
     * @return la fase actual, de 1 a 3
     */
    public int update(double fraction) {
        double f = Fx.clamp(fraction, 0, 1);
        int current = currentPhase(f);
        for (int i = 0; i < PHASES; i++) {
            if (retired[i]) continue;
            float progress = (float) Fx.clamp((f - (PHASES - 1 - i) / (double) PHASES) * PHASES, 0, 1);
            bars[i].progress(progress);
            bars[i].name(title(i, (i + 1) == current));
            if (progress <= 0.0f && (i + 1) < current) {
                retire(i);
            }
        }
        return current;
    }

    public static int currentPhase(double fraction) {
        double f = Fx.clamp(fraction, 0, 1);
        if (f > 2.0 / 3.0) return 1;
        if (f > 1.0 / 3.0) return 2;
        return 3;
    }

    private void retire(int index) {
        retired[index] = true;
        for (Player p : viewers) p.hideBossBar(bars[index]);
    }

    /** Un destello blanco en la barra que se acaba de romper, antes de retirarla. */
    public void flash(int phase) {
        int index = phase - 1;
        if (index < 0 || index >= PHASES || retired[index]) return;
        bars[index].color(BossBar.Color.WHITE);
        bars[index].name(Component.text("✦ ", NamedTextColor.WHITE)
                .append(Component.text("ARMADURA ROTA", NamedTextColor.WHITE, TextDecoration.BOLD)));
    }

    /**
     * Recalcula quien ve las barras. Se llama cada segundo: quien entra en el radio
     * las recibe y quien se aleja o se desconecta deja de verlas.
     */
    public void refreshViewers(Location center, double radius) {
        // A proposito viewersNear y no playersNear: la barra la ve todo el que este
        // cerca, tambien un operador en creativo. Ver es una cosa y pelear es otra.
        Set<Player> should = new HashSet<>(Fx.viewersNear(center, radius));

        for (Player p : new HashSet<>(viewers)) {
            if (should.contains(p) && p.isOnline()) continue;
            hideFrom(p);
            viewers.remove(p);
        }
        for (Player p : should) {
            if (viewers.add(p)) showTo(p);
        }
    }

    private void showTo(Player p) {
        for (int i = 0; i < PHASES; i++) {
            if (!retired[i]) p.showBossBar(bars[i]);
        }
    }

    private void hideFrom(Player p) {
        for (BossBar bar : bars) p.hideBossBar(bar);
    }

    public Set<Player> viewers() {
        return viewers;
    }

    public void removeAll() {
        for (Player p : new HashSet<>(viewers)) hideFrom(p);
        viewers.clear();
    }
}
