package net.ederus.edm.anomaly.drops;

import org.bukkit.inventory.ItemStack;

/**
 * Una linea de la tabla de botin: que objeto cae, con que probabilidad, cuantos y a quien.
 * El objeto es un ItemStack real, no un id, para que valga cualquier cosa que el admin
 * pueda tener en la mano: un item de MMOItems, algo encantado a mano, lo que sea.
 */
public final class DropEntry {

    /** A quien le toca el objeto cuando cae. */
    public enum Recipient {
        TODOS("Todos los que pelearon", "Cada participante recibe su copia."),
        MEJOR("Quien mas dano hizo", "Solo el jugador con mas dano acumulado."),
        ALEATORIO("Uno al azar", "Un participante al azar, con peso por dano.");

        private final String display;
        private final String help;

        Recipient(String display, String help) {
            this.display = display;
            this.help = help;
        }

        public String display() {
            return display;
        }

        public String help() {
            return help;
        }

        public Recipient next() {
            Recipient[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    private ItemStack item;
    private double chance;
    private int min;
    private int max;
    private Recipient to;

    public DropEntry(ItemStack item, double chance, int min, int max, Recipient to) {
        this.item = item;
        this.chance = chance;
        this.min = min;
        this.max = max;
        this.to = to;
    }

    public static DropEntry of(ItemStack item) {
        return new DropEntry(item.clone(), 100.0, item.getAmount(), item.getAmount(), Recipient.TODOS);
    }

    public ItemStack item() {
        return item;
    }

    public void item(ItemStack item) {
        this.item = item;
    }

    public double chance() {
        return chance;
    }

    public void chance(double chance) {
        this.chance = Math.max(0.1, Math.min(100.0, Math.round(chance * 10.0) / 10.0));
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    public void amount(int min, int max) {
        this.min = Math.max(1, Math.min(64, min));
        this.max = Math.max(this.min, Math.min(64, max));
    }

    public Recipient to() {
        return to;
    }

    public void to(Recipient to) {
        this.to = to;
    }

    public void cycleRecipient() {
        this.to = this.to.next();
    }

    public String amountLabel() {
        return min == max ? String.valueOf(min) : (min + "-" + max);
    }
}
