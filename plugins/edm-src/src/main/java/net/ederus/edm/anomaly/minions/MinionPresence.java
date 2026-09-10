package net.ederus.edm.anomaly.minions;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import net.ederus.edm.comun.Compat;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * La presencia de un esbirro: como se ve y como suena antes de dar el primer
 * golpe. No es una habilidad, porque no cambia ni una cifra del combate; es lo
 * que separa de un vistazo a la tropa de a pie del que manda en la sala.
 *
 * Se decide al CREAR el tipo, no se enciende desde el menu: cada esbirro nace
 * con su equipo, su aura, su contorno y su sonido, o sin nada de eso. Por eso
 * todo esto vive en el tipo y no en el generador.
 *
 * Lo caro se cobra una sola vez. El equipo se pone al aparecer y no se toca mas;
 * el aura solo la pintan los destacados, y a un pulso lento.
 */
public final class MinionPresence {

    /** Las seis ranuras que se pueden vestir, con el nombre que llevan en el yml. */
    public enum Slot {
        CASCO("casco", EquipmentSlot.HEAD),
        PECHERA("pechera", EquipmentSlot.CHEST),
        PANTALON("pantalon", EquipmentSlot.LEGS),
        BOTAS("botas", EquipmentSlot.FEET),
        MANO("mano", EquipmentSlot.HAND),
        SECUNDARIA("secundaria", EquipmentSlot.OFF_HAND);

        private final String key;
        private final EquipmentSlot slot;

        Slot(String key, EquipmentSlot slot) {
            this.key = key;
            this.slot = slot;
        }

        public String key() {
            return key;
        }

        public EquipmentSlot slot() {
            return slot;
        }

        public static Slot byKey(String s) {
            if (s == null) return null;
            for (Slot v : values()) {
                if (v.key.equalsIgnoreCase(s)) return v;
            }
            return null;
        }
    }

    /** Destacado: el que manda en la sala. Solo estos pintan aura y van en negrita. */
    private boolean featured;

    private final Map<Slot, Material> gear = new EnumMap<>(Slot.class);

    /** Tinte del cuero, para vestir a un esbirro del color de su tipo. -1 = sin tenir. */
    private int gearColor = -1;

    /** El aura, por nombre de particula. Vacio = sin aura. */
    private String auraName = "";
    private int auraColor = -1;
    /** Cada cuantos ticks se pinta el anillo. Mas alto, mas barato. */
    private int auraEvery = 4;

    /** Sonido al aparecer y ambiente ocasional, por su clave de Minecraft. */
    private String spawnSound = "";
    private String ambientSound = "";

    /** Contorno de color, uno de los 16 del juego. null = sin contorno. */
    private NamedTextColor outline;

    /* --------------------------------------------------------------- consultas */

    public boolean featured() {
        return featured;
    }

    public void featured(boolean featured) {
        this.featured = featured;
    }

    public Map<Slot, Material> gear() {
        return gear;
    }

    public boolean hasGear() {
        return !gear.isEmpty();
    }

    public int gearColor() {
        return gearColor;
    }

    public void gearColor(int rgb) {
        this.gearColor = rgb;
    }

    public String auraName() {
        return auraName;
    }

    public void auraName(String name) {
        this.auraName = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
    }

    public Particle aura() {
        return auraName.isEmpty() ? null : Compat.particleByName(auraName);
    }

    public int auraColor() {
        return auraColor;
    }

    public void auraColor(int rgb) {
        this.auraColor = rgb;
    }

    public int auraEvery() {
        return auraEvery;
    }

    public void auraEvery(int ticks) {
        this.auraEvery = Math.max(1, Math.min(40, ticks));
    }

    public String spawnSound() {
        return spawnSound;
    }

    public void spawnSound(String key) {
        this.spawnSound = key == null ? "" : key.trim();
    }

    public String ambientSound() {
        return ambientSound;
    }

    public void ambientSound(String key) {
        this.ambientSound = key == null ? "" : key.trim();
    }

    public NamedTextColor outline() {
        return outline;
    }

    public void outline(NamedTextColor color) {
        this.outline = color;
    }

    /** true si este esbirro tiene algo que ensenar; los de a pie no tienen nada. */
    public boolean any() {
        return featured || hasGear() || !auraName.isEmpty() || outline != null
                || !spawnSound.isEmpty() || !ambientSound.isEmpty();
    }

    /* ---------------------------------------------------------------- aplicar */

    /**
     * Le pone el equipo al aparecer.
     *
     * Las probabilidades de soltar van a CERO a proposito: el equipo es
     * decoracion, no botin. Si cayera al suelo, media mazmorra acabaria farmeando
     * cascos de cuero y el botin de verdad —el de la tabla— dejaria de importar.
     */
    public void dress(LivingEntity mob) {
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) return;
        for (Map.Entry<Slot, Material> e : gear.entrySet()) {
            ItemStack item = build(e.getValue());
            if (item == null) continue;
            eq.setItem(e.getKey().slot(), item);
            eq.setDropChance(e.getKey().slot(), 0f);
        }
    }

    private ItemStack build(Material material) {
        if (material == null || !material.isItem()) return null;
        ItemStack item = new ItemStack(material);
        if (gearColor >= 0 && item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(Color.fromRGB(gearColor & 0xFFFFFF));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** El anillo del aura, a los pies. Un solo spawnParticle por vuelta. */
    public void tickAura(LivingEntity mob) {
        Particle p = aura();
        if (p == null) return;
        World w = mob.getWorld();
        Location at = mob.getLocation().add(0, 0.15, 0);
        if (auraColor >= 0 && p == Compat.DUST) {
            Compat.spawn(w, p, at, 6, 0.45, 0.12, 0.45, 0.0,
                    new Particle.DustOptions(Color.fromRGB(auraColor & 0xFFFFFF), 1.1f));
            return;
        }
        Compat.spawn(w, p, at, 5, 0.4, 0.12, 0.4, 0.005);
    }

    public void playSpawn(World w, Location at) {
        if (!spawnSound.isEmpty()) Compat.sound(w, at, spawnSound, 0.7f, 1.0f);
    }

    public void playAmbient(World w, Location at) {
        if (!ambientSound.isEmpty()) Compat.sound(w, at, ambientSound, 0.45f, 1.0f);
    }
}
