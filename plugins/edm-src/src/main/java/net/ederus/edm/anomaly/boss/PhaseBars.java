package net.ederus.edm.anomaly.boss;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.edm.anomaly.core.Fx;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;

/**
 * Las tres barras de vida del jefe, una por fase y un tercio de vida cada una.
 *
 * Se enseñan de una en una: cuando la de la fase en curso se agota, desaparece y sale
 * la siguiente. Es a proposito que no se vean las tres a la vez, ni siquiera para
 * "avisar" de cuanto queda; asi cada fase nueva es una sorpresa.
 */
public final class PhaseBars {

    public static final int PHASES = 3;

    private final BossBar[] bars = new BossBar[PHASES];
    /** Indice de la unica barra visible ahora mismo; -1 mientras no se ha mostrado ninguna. */
    private int shown = -1;
    private final Set<Player> viewers = new HashSet<>();
    private final String bossName;
    private final TextColor accent;

    public PhaseBars(String bossName, TextColor accent) {
        this.bossName = bossName;
        this.accent = accent;
        for (int i = 0; i < PHASES; i++) {
            bars[i] = BossBar.bossBar(title(i), 1.0f, colorFor(i), overlayFor(i));
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

    private Component title(int index) {
        TextColor phaseColor = switch (index) {
            case 0 -> NamedTextColor.WHITE;
            case 1 -> NamedTextColor.GOLD;
            default -> NamedTextColor.RED;
        };
        Component name = Component.text(bossName, accent, TextDecoration.BOLD);
        Component phase = Component.text("FASE " + roman(index), phaseColor);
        Component sep = Component.text("  ·  ", TextColor.color(0x555555));
        return Component.text("✦ ", accent).append(name).append(sep).append(phase)
                .append(Component.text(" de III", TextColor.color(0x555555)));
    }

    /**
     * @param fraction vida restante del jefe entre 0 y 1
     * @return la fase actual, de 1 a 3
     */
    public int update(double fraction) {
        double f = Fx.clamp(fraction, 0, 1);
        int current = currentPhase(f);
        int index = current - 1;

        float progress = (float) Fx.clamp((f - (PHASES - 1 - index) / (double) PHASES) * PHASES, 0, 1);
        bars[index].progress(progress);
        bars[index].name(title(index));

        // Solo se enseña la barra de la fase en curso. Cuando se agota, esa desaparece
        // y aparece la siguiente debajo: es la sensacion de "otra barra mas" que se pidio,
        // y ademas no revela de entrada que quedan tres.
        if (index != shown) {
            for (Player p : viewers) swapTo(p, index);
            shown = index;
        }
        return current;
    }

    public static int currentPhase(double fraction) {
        double f = Fx.clamp(fraction, 0, 1);
        if (f > 2.0 / 3.0) return 1;
        if (f > 1.0 / 3.0) return 2;
        return 3;
    }

    /** Quita la barra que se acaba de agotar y saca la de la fase nueva. */
    private void swapTo(Player p, int index) {
        for (int i = 0; i < PHASES; i++) {
            if (i != index) p.hideBossBar(bars[i]);
        }
        p.showBossBar(bars[index]);
    }

    /** Un destello blanco en la barra que se acaba de romper, antes de retirarla. */
    public void flash(int phase) {
        int index = phase - 1;
        if (index < 0 || index >= PHASES) return;
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
        swapTo(p, Math.max(0, shown));
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
