package net.zakiworld.anomaly.core;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Field;

/**
 * Capa de compatibilidad. El servidor de Ederus corre Paper 26.1.x, pero el plugin
 * tiene que sobrevivir a que le cambien la version debajo: cada particula, sonido,
 * atributo y efecto se resuelve por nombre y devuelve null en vez de reventar.
 */
public final class Compat {

    public static final Particle BLOCK = particle("BLOCK", "BLOCK_CRACK");
    public static final Particle DUST = particle("DUST", "REDSTONE");
    public static final Particle DUST_COLOR_TRANSITION = particle("DUST_COLOR_TRANSITION");
    public static final Particle ITEM = particle("ITEM", "ITEM_CRACK");
    public static final Particle EXPLOSION_EMITTER = particle("EXPLOSION_EMITTER", "EXPLOSION_HUGE");
    public static final Particle EXPLOSION = particle("EXPLOSION", "EXPLOSION_LARGE");
    public static final Particle ENCHANT = particle("ENCHANT", "ENCHANTMENT_TABLE");
    public static final Particle ENCHANTED_HIT = particle("ENCHANTED_HIT", "CRIT_MAGIC");
    public static final Particle TOTEM = particle("TOTEM_OF_UNDYING", "TOTEM");
    public static final Particle LARGE_SMOKE = particle("LARGE_SMOKE", "SMOKE_LARGE");
    public static final Particle SMOKE = particle("SMOKE", "SMOKE_NORMAL");
    public static final Particle WHITE_SMOKE = particle("WHITE_SMOKE", "SMOKE_NORMAL");
    public static final Particle POOF = particle("POOF", "EXPLOSION_NORMAL");
    public static final Particle CLOUD = particle("CLOUD");
    public static final Particle FLAME = particle("FLAME");
    public static final Particle SMALL_FLAME = particle("SMALL_FLAME");
    public static final Particle SOUL = particle("SOUL");
    public static final Particle SOUL_FIRE_FLAME = particle("SOUL_FIRE_FLAME");
    public static final Particle SCULK_SOUL = particle("SCULK_SOUL");
    public static final Particle SCULK_CHARGE_POP = particle("SCULK_CHARGE_POP");
    public static final Particle SNOWFLAKE = particle("SNOWFLAKE");
    public static final Particle NOTE = particle("NOTE");
    public static final Particle HEART = particle("HEART");
    public static final Particle SPLASH = particle("SPLASH", "WATER_SPLASH");
    public static final Particle BUBBLE = particle("BUBBLE", "WATER_BUBBLE");
    public static final Particle DRIPPING_WATER = particle("DRIPPING_WATER", "WATER_DRIP");
    public static final Particle PORTAL = particle("PORTAL");
    public static final Particle REVERSE_PORTAL = particle("REVERSE_PORTAL");
    public static final Particle END_ROD = particle("END_ROD");
    public static final Particle ELECTRIC_SPARK = particle("ELECTRIC_SPARK");
    public static final Particle SWEEP_ATTACK = particle("SWEEP_ATTACK");
    public static final Particle CRIT = particle("CRIT");
    public static final Particle FLASH = particle("FLASH");
    public static final Particle DRAGON_BREATH = particle("DRAGON_BREATH");
    public static final Particle GLOW = particle("GLOW");
    public static final Particle ASH = particle("ASH");
    public static final Particle WHITE_ASH = particle("WHITE_ASH");
    public static final Particle SONIC_BOOM = particle("SONIC_BOOM");
    public static final Particle SHRIEK = particle("SHRIEK");
    public static final Particle FIREWORK = particle("FIREWORK", "FIREWORKS_SPARK");
    public static final Particle ANGRY_VILLAGER = particle("ANGRY_VILLAGER", "VILLAGER_ANGRY");
    public static final Particle DAMAGE_INDICATOR = particle("DAMAGE_INDICATOR");
    public static final Particle TRIAL_OMEN = particle("TRIAL_OMEN", "WITCH");
    public static final Particle GUST = particle("GUST", "CLOUD");
    public static final Particle DUST_PLUME = particle("DUST_PLUME", "LARGE_SMOKE");
    public static final Particle FALLING_DUST = particle("FALLING_DUST");
    public static final Particle WAX_OFF = particle("WAX_OFF");

    private static final Enchantment GLOW_ENCHANT = resolveGlow();

    private Compat() {
    }

    private static Particle particle(String... names) {
        for (String n : names) {
            try {
                return Particle.valueOf(n);
            } catch (IllegalArgumentException | NoSuchFieldError ignored) {
                // la version de turno no la trae; se prueba el siguiente alias
            }
        }
        return null;
    }

    /** Cuantas particulas de las declaradas arriba no existen en esta version. Lo usa /anomaly info. */
    public static int missingParticles() {
        int n = 0;
        for (Field f : Compat.class.getDeclaredFields()) {
            if (f.getType() != Particle.class) continue;
            try {
                if (f.get(null) == null) n++;
            } catch (IllegalAccessException ignored) {
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- particulas

    public static void spawn(World w, Particle p, Location l, int count, double ox, double oy, double oz, double extra) {
        if (p == null || l == null || w == null) return;
        try {
            Class<?> expected = p.getDataType();
            if (expected == Void.class) {
                w.spawnParticle(p, l, count, ox, oy, oz, extra);
                return;
            }
            Object data = defaultData(expected);
            if (data != null) w.spawnParticle(p, l, count, ox, oy, oz, extra, data);
        } catch (Throwable ignored) {
        }
    }

    public static void spawn(World w, Particle p, Location l, int count) {
        spawn(w, p, l, count, 0, 0, 0, 0);
    }

    public static <T> void spawn(World w, Particle p, Location l, int count, double ox, double oy, double oz, double extra, T data) {
        if (p == null || l == null || w == null) return;
        try {
            Class<?> expected = p.getDataType();
            if (expected == Void.class) {
                w.spawnParticle(p, l, count, ox, oy, oz, extra);
                return;
            }
            if (!expected.isInstance(data)) {
                Object fallback = defaultData(expected);
                if (fallback == null) return;
                w.spawnParticle(p, l, count, ox, oy, oz, extra, fallback);
                return;
            }
            w.spawnParticle(p, l, count, ox, oy, oz, extra, data);
        } catch (Throwable ignored) {
        }
    }

    public static <T> void spawn(World w, Particle p, Location l, int count, T data) {
        spawn(w, p, l, count, 0, 0, 0, 0, data);
    }

    /**
     * En 1.21.9 FLASH paso a exigir un Color y DRAGON_BREATH un Float; llamarlas sin el
     * dato lanza IllegalArgumentException y aborta la animacion entera. Ver la bitacora de Rip.
     */
    private static Object defaultData(Class<?> expected) {
        if (expected == Color.class) return Color.WHITE;
        if (expected == Float.class) return 1.0f;
        return null;
    }

    public static Particle.DustOptions dust(int rgb, float size) {
        return new Particle.DustOptions(Color.fromRGB(rgb), size);
    }

    /**
     * Particula "forzada": el servidor la manda a todo el que este a menos de 512
     * bloques en vez de a los 32 de siempre.
     *
     * Es lo unico que hace visible un pilar de luz desde lejos; con una particula normal
     * hay que estar ya encima para verlo, que es justo cuando ya no hace falta.
     */
    public static <T> void spawnForced(World w, Particle p, Location l, int count,
                                       double ox, double oy, double oz, double extra, T data) {
        if (p == null || l == null || w == null) return;
        try {
            Class<?> expected = p.getDataType();
            if (expected == Void.class) {
                w.spawnParticle(p, l, count, ox, oy, oz, extra, null, true);
                return;
            }
            Object payload = expected.isInstance(data) ? data : defaultData(expected);
            if (payload != null) w.spawnParticle(p, l, count, ox, oy, oz, extra, payload, true);
        } catch (Throwable ignored) {
            spawn(w, p, l, count, ox, oy, oz, extra, data);
        }
    }

    // ------------------------------------------------------------------- sonidos

    public static void sound(World w, Location l, String key, float volume, float pitch) {
        if (w == null || l == null) return;
        try {
            w.playSound(l, key, SoundCategory.HOSTILE, volume, pitch);
        } catch (Throwable ignored) {
        }
    }

    // ----------------------------------------------------------------- atributos

    /**
     * Los atributos perdieron el prefijo GENERIC_ en 1.21.2 y ademas dejaron de ser un enum.
     * Se resuelven por clave del registro, que es lo unico estable entre versiones.
     */
    public static Attribute attribute(String key) {
        try {
            Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
            if (a != null) return a;
        } catch (Throwable ignored) {
        }
        for (String name : new String[]{key.toUpperCase(java.util.Locale.ROOT), "GENERIC_" + key.toUpperCase(java.util.Locale.ROOT)}) {
            try {
                return Attribute.valueOf(name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static void setAttribute(LivingEntity e, String key, double value) {
        Attribute a = attribute(key);
        if (a == null) return;
        try {
            AttributeInstance inst = e.getAttribute(a);
            if (inst != null) inst.setBaseValue(value);
        } catch (Throwable ignored) {
        }
    }

    public static double getAttribute(LivingEntity e, String key, double def) {
        Attribute a = attribute(key);
        if (a == null) return def;
        try {
            AttributeInstance inst = e.getAttribute(a);
            return inst == null ? def : inst.getValue();
        } catch (Throwable ignored) {
            return def;
        }
    }

    // ------------------------------------------------------------------- efectos

    public static PotionEffectType effect(String key) {
        try {
            PotionEffectType t = Registry.POTION_EFFECT_TYPE.get(NamespacedKey.minecraft(key));
            if (t != null) return t;
        } catch (Throwable ignored) {
        }
        try {
            return PotionEffectType.getByName(key.toUpperCase(java.util.Locale.ROOT));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void apply(LivingEntity e, String key, int ticks, int amplifier) {
        PotionEffectType t = effect(key);
        if (t == null) return;
        try {
            e.addPotionEffect(new PotionEffect(t, ticks, amplifier, false, true, true));
        } catch (Throwable ignored) {
        }
    }

    // -------------------------------------------------------------------- brillo

    private static Enchantment resolveGlow() {
        NamespacedKey key = NamespacedKey.minecraft("unbreaking");
        try {
            Enchantment e = Registry.ENCHANTMENT.get(key);
            if (e != null) return e;
        } catch (Throwable ignored) {
        }
        try {
            return Enchantment.getByKey(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Enchantment glow() {
        return GLOW_ENCHANT;
    }
}
