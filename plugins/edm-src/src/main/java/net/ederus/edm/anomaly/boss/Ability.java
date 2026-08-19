package net.ederus.edm.anomaly.boss;

import org.bukkit.Material;

import java.util.function.Consumer;

/**
 * Una habilidad del jefe: su ficha para el menu mas la funcion que la ejecuta.
 *
 * El enfriamiento se mide en ticks y nunca deberia ser menor que la duracion de la
 * animacion, para que el jefe no lance dos cosas a la vez y se pise la escena.
 */
public final class Ability {

    private final String id;
    private final String display;
    private final String description;
    private final int phase;
    private final int cooldownTicks;
    private final int castTicks;
    private final int weight;
    private final Material icon;
    private final Consumer<BossFight> action;

    private long readyAt;

    public Ability(String id, String display, String description, int phase,
                   int cooldownTicks, int castTicks, int weight, Material icon,
                   Consumer<BossFight> action) {
        this.id = id;
        this.display = display;
        this.description = description;
        this.phase = phase;
        this.cooldownTicks = Math.max(cooldownTicks, castTicks);
        this.castTicks = castTicks;
        this.weight = Math.max(1, weight);
        this.icon = icon;
        this.action = action;
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public String description() {
        return description;
    }

    /** 1, 2 o 3. Un 0 significa que la usa en cualquier fase. */
    public int phase() {
        return phase;
    }

    public int cooldownTicks() {
        return cooldownTicks;
    }

    public int castTicks() {
        return castTicks;
    }

    public int weight() {
        return weight;
    }

    public Material icon() {
        return icon;
    }

    public boolean availableIn(int currentPhase) {
        return phase == 0 || phase == currentPhase;
    }

    public boolean ready(long now) {
        return now >= readyAt;
    }

    public void startCooldown(long now) {
        readyAt = now + cooldownTicks;
    }

    public void reset() {
        readyAt = 0;
    }

    public void cast(BossFight fight) {
        action.accept(fight);
    }
}
