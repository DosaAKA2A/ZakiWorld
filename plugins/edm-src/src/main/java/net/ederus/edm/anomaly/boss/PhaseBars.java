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
 * Las barras de vida del jefe, una por fase y una fraccion igual de vida cada una.
 *
 * Se enseñan de una en una: cuando la de la fase en curso se agota, desaparece y sale
 * la siguiente. Es a proposito que no se vean todas a la vez, ni siquiera para
 * "avisar" de cuanto queda; asi cada fase nueva es una sorpresa.
 *
 * Casi todos los jefes pelean a tres fases; un Monarca puede pedir cuatro
 * (BossFight#phaseCount) y aqui simplemente salen cuatro barras.
 */
public final class PhaseBars {

    /** Lo normal del catalogo. Quien no diga otra cosa pelea a tres fases. */
    public static final int DEFAULT_PHASES = 3;

    private final int phases;
    private final BossBar[] bars;
    /** Indice de la unica barra visible ahora mismo; -1 mientras no se ha mostrado ninguna. */
    private int shown = -1;
    private final Set<Player> viewers = new HashSet<>();
    private final String bossName;
    private final TextColor accent;

    public PhaseBars(String bossName, TextColor accent) {
        this(bossName, accent, DEFAULT_PHASES);
    }

    public PhaseBars(String bossName, TextColor accent, int phases) {
        this.bossName = bossName;
        this.accent = accent;
        this.phases = Math.max(1, Math.min(4, phases));
        this.bars = new BossBar[this.phases];
        for (int i = 0; i < this.phases; i++) {
            bars[i] = BossBar.bossBar(title(i), 1.0f, colorFor(i), overlayFor(i));
        }
    }

    private static BossBar.Color colorFor(int index) {
        return switch (index) {
            case 0 -> BossBar.Color.WHITE;
            case 1 -> BossBar.Color.YELLOW;
            case 2 -> BossBar.Color.RED;
            default -> BossBar.Color.PURPLE;
        };
    }

    private static BossBar.Overlay overlayFor(int index) {
        return switch (index) {
            case 0 -> BossBar.Overlay.NOTCHED_6;
            case 1 -> BossBar.Overlay.NOTCHED_10;
            case 2 -> BossBar.Overlay.NOTCHED_20;
            default -> BossBar.Overlay.PROGRESS;
        };
    }

    private static String roman(int index) {
        return switch (index) {
            case 0 -> "I";
            case 1 -> "II";
            case 2 -> "III";
            default -> "IV";
        };
    }

    private Component title(int index) {
        TextColor phaseColor = switch (index) {
            case 0 -> NamedTextColor.WHITE;
            case 1 -> NamedTextColor.GOLD;
            case 2 -> NamedTextColor.RED;
            default -> NamedTextColor.LIGHT_PURPLE;
        };
        Component name = Component.text(bossName, accent, TextDecoration.BOLD);
        Component phase = Component.text("FASE " + roman(index), phaseColor);
        Component sep = Component.text("  ·  ", TextColor.color(0x555555));
        return Component.text("✦ ", accent).append(name).append(sep).append(phase)
                .append(Component.text(" de " + roman(phases - 1), TextColor.color(0x555555)));
    }

    /**
     * @param fraction vida restante del jefe entre 0 y 1
     * @return la fase actual, de 1 al numero de fases
     */
    public int update(double fraction) {
        double f = Fx.clamp(fraction, 0, 1);
        int current = currentPhase(f, phases);
        int index = current - 1;

        float progress = (float) Fx.clamp((f - (phases - 1 - index) / (double) phases) * phases, 0, 1);
        bars[index].progress(progress);
        bars[index].name(title(index));

        // Solo se enseña la barra de la fase en curso. Cuando se agota, esa desaparece
        // y aparece la siguiente debajo: es la sensacion de "otra barra mas" que se pidio,
        // y ademas no revela de entrada cuantas quedan.
        if (index != shown) {
            for (Player p : viewers) swapTo(p, index);
            shown = index;
        }
        return current;
    }

    /** La fase que toca con esa fraccion de vida, para un jefe de N fases. */
    public static int currentPhase(double fraction, int phases) {
        double f = Fx.clamp(fraction, 0, 1);
        int idx = (int) Math.floor((1.0 - f) * phases);
        if (idx >= phases) idx = phases - 1;
        return idx + 1;
    }

    /** Version a tres fases, que es la del catalogo entero salvo excepcion. */
    public static int currentPhase(double fraction) {
        return currentPhase(fraction, DEFAULT_PHASES);
    }

    /** Quita la barra que se acaba de agotar y saca la de la fase nueva. */
    private void swapTo(Player p, int index) {
        for (int i = 0; i < bars.length; i++) {
            if (i != index) p.hideBossBar(bars[i]);
        }
        p.showBossBar(bars[index]);
    }

    /** Un destello blanco en la barra que se acaba de romper, antes de retirarla. */
    public void flash(int phase) {
        int index = phase - 1;
        if (index < 0 || index >= bars.length) return;
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
