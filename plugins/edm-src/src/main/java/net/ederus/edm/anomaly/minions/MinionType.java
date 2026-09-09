package net.ederus.edm.anomaly.minions;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/**
 * Un tipo de esbirro: la criatura que puebla las mazmorras. No es un jefe, es
 * tropa: se define una vez (bicho, nombre, vida y dano base, cuanto crece por
 * nivel) y luego se planta por el mapa con generadores, cada uno con su propio
 * rango de nivel. El MISMO esbirro puede ser nivel 5-10 en la entrada de la
 * mazmorra y 20-30 en la sala del fondo: el nivel lo pone el generador, no el tipo.
 *
 * La vida a nivel N es  vida-base * (1 + crecimiento-vida * (N - 1)).
 * El dano es un MULTIPLICADOR sobre lo que el bicho pegue de fabrica (asi tambien
 * escala la flecha de un esqueleto o la explosion de un creeper, que no pasan por
 * el atributo de ataque):  x dano-base * (1 + crecimiento-dano * (N - 1)).
 */
public final class MinionType {

    /** Los bichos que se pueden ciclar desde el menu. Todos tienen huevo de spawn. */
    public static final EntityType[] BESTIARIO = {
            EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED, EntityType.ZOMBIFIED_PIGLIN,
            EntityType.SKELETON, EntityType.STRAY, EntityType.BOGGED, EntityType.WITHER_SKELETON,
            EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.SILVERFISH, EntityType.ENDERMITE,
            EntityType.CREEPER, EntityType.WITCH, EntityType.SLIME, EntityType.MAGMA_CUBE,
            EntityType.BLAZE, EntityType.BREEZE, EntityType.GHAST, EntityType.PHANTOM,
            EntityType.VEX, EntityType.PILLAGER, EntityType.VINDICATOR, EntityType.EVOKER,
            EntityType.RAVAGER, EntityType.PIGLIN, EntityType.PIGLIN_BRUTE, EntityType.HOGLIN,
            EntityType.ZOGLIN, EntityType.ENDERMAN, EntityType.SHULKER, EntityType.GUARDIAN,
            EntityType.ELDER_GUARDIAN, EntityType.WARDEN, EntityType.IRON_GOLEM, EntityType.SNOW_GOLEM,
            EntityType.WOLF, EntityType.POLAR_BEAR, EntityType.BEE, EntityType.FROG,
    };

    /** Colores para ciclar el nombre desde el menu, elegidos para leerse sobre el mundo. */
    public static final int[] PALETA = {
            0xFFFFFF, 0x9BD7E4, 0x66CC66, 0xFFD966, 0xFF8A5C, 0xFF5C5C,
            0xC792EA, 0x82AAFF, 0x89DDFF, 0xF07178, 0xA6ACB9, 0xFFCB6B,
    };

    private final String id;
    private String display;
    private int color = 0xFFFFFF;
    /** El nombre del holograma va en redonda; la negrita solo si se pide a mano. */
    private boolean bold = false;
    private EntityType entity = EntityType.ZOMBIE;
    private final java.util.Set<MinionAbility> abilities = java.util.EnumSet.noneOf(MinionAbility.class);
    private double baseHealth = 20;
    private double healthGrowth = 0.35;
    private double baseDamage = 1.0;
    private double damageGrowth = 0.10;

    /* Los valores que hereda cada vela nueva de este tipo; luego cada generador
     * puede cambiarlos por su cuenta desde la lista de generadores. */
    private int wandMinLevel = 1;
    private int wandMaxLevel = 5;
    private int wandIntervalSeconds = 30;
    private int wandMaxAlive = 3;
    private int wandActivationRadius = 32;

    public MinionType(String id, String display) {
        this.id = id;
        this.display = display;
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public void display(String display) {
        this.display = display;
    }

    public TextColor color() {
        return TextColor.color(color);
    }

    public int colorRgb() {
        return color;
    }

    public void colorRgb(int rgb) {
        this.color = rgb;
    }

    /** Avanza (o retrocede) por la paleta de colores del menu. */
    public void cycleColor(boolean forward) {
        int at = 0;
        for (int i = 0; i < PALETA.length; i++) {
            if (PALETA[i] == color) {
                at = i;
                break;
            }
        }
        this.color = PALETA[Math.floorMod(at + (forward ? 1 : -1), PALETA.length)];
    }

    public boolean bold() {
        return bold;
    }

    public void bold(boolean bold) {
        this.bold = bold;
    }

    /** El nombre tal cual se pinta: su color y, solo si se pidio, en negrita. */
    public Component name() {
        Component c = Component.text(display, color());
        return bold ? c.decoration(TextDecoration.BOLD, true) : c;
    }

    public EntityType entity() {
        return entity;
    }

    public void entity(EntityType entity) {
        this.entity = entity;
    }

    /** Avanza (o retrocede) por el bestiario del menu. */
    public void cycleEntity(boolean forward) {
        int at = 0;
        for (int i = 0; i < BESTIARIO.length; i++) {
            if (BESTIARIO[i] == entity) {
                at = i;
                break;
            }
        }
        this.entity = BESTIARIO[Math.floorMod(at + (forward ? 1 : -1), BESTIARIO.length)];
    }

    // ------------------------------------------------------------------ habilidades

    public java.util.Set<MinionAbility> abilities() {
        return abilities;
    }

    public boolean has(MinionAbility ability) {
        return abilities.contains(ability);
    }

    /** Enciende o apaga una habilidad; devuelve como queda. */
    public boolean toggle(MinionAbility ability) {
        if (abilities.contains(ability)) {
            abilities.remove(ability);
            return false;
        }
        abilities.add(ability);
        return true;
    }

    /** El huevo de spawn del bicho, que es el icono natural del catalogo. */
    public Material icon() {
        Material egg = Material.matchMaterial(entity.name() + "_SPAWN_EGG");
        return egg != null ? egg : Material.ZOMBIE_SPAWN_EGG;
    }

    public double baseHealth() {
        return baseHealth;
    }

    public void baseHealth(double value) {
        this.baseHealth = Math.max(1, Math.min(10000, value));
    }

    public double healthGrowth() {
        return healthGrowth;
    }

    public void healthGrowth(double value) {
        this.healthGrowth = Math.max(0, Math.min(5, value));
    }

    public double baseDamage() {
        return baseDamage;
    }

    public void baseDamage(double value) {
        this.baseDamage = Math.max(0.1, Math.min(20, value));
    }

    public double damageGrowth() {
        return damageGrowth;
    }

    public void damageGrowth(double value) {
        this.damageGrowth = Math.max(0, Math.min(5, value));
    }

    /** La vida que le toca a un esbirro de ese nivel. */
    public double healthAt(int level) {
        return Math.max(1, baseHealth * (1 + healthGrowth * (Math.max(1, level) - 1)));
    }

    /** El multiplicador de dano que le toca a un esbirro de ese nivel. */
    public double damageAt(int level) {
        return Math.max(0.05, baseDamage * (1 + damageGrowth * (Math.max(1, level) - 1)));
    }

    public int wandMinLevel() {
        return wandMinLevel;
    }

    public void wandMinLevel(int v) {
        this.wandMinLevel = Math.max(1, Math.min(1000, v));
        if (wandMaxLevel < wandMinLevel) wandMaxLevel = wandMinLevel;
    }

    public int wandMaxLevel() {
        return wandMaxLevel;
    }

    public void wandMaxLevel(int v) {
        this.wandMaxLevel = Math.max(1, Math.min(1000, v));
        if (wandMinLevel > wandMaxLevel) wandMinLevel = wandMaxLevel;
    }

    /** Fija el rango de la vela de una vez; se ordena solo si vienen del reves. */
    public void wandLevels(int min, int max) {
        int lo = Math.max(1, Math.min(1000, Math.min(min, max)));
        int hi = Math.max(1, Math.min(1000, Math.max(min, max)));
        this.wandMinLevel = lo;
        this.wandMaxLevel = hi;
    }

    public int wandIntervalSeconds() {
        return wandIntervalSeconds;
    }

    public void wandIntervalSeconds(int v) {
        this.wandIntervalSeconds = Math.max(3, Math.min(3600, v));
    }

    public int wandMaxAlive() {
        return wandMaxAlive;
    }

    public void wandMaxAlive(int v) {
        this.wandMaxAlive = Math.max(1, Math.min(30, v));
    }

    public int wandActivationRadius() {
        return wandActivationRadius;
    }

    public void wandActivationRadius(int v) {
        this.wandActivationRadius = Math.max(8, Math.min(128, v));
    }

    /** El id de su tabla de botin en drops.yml, separado del espacio de los jefes. */
    public String dropTableId() {
        return "esbirro-" + id;
    }
}
