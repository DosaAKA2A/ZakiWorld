/*
 * Decompiled with CFR 0.152.
 */
package net.ederus.edm.rip;

import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

public final class Compat {
    public static final Particle BLOCK = Compat.particle("BLOCK", "BLOCK_CRACK");
    public static final Particle DUST = Compat.particle("DUST", "REDSTONE");
    public static final Particle ITEM = Compat.particle("ITEM", "ITEM_CRACK");
    public static final Particle EXPLOSION_EMITTER = Compat.particle("EXPLOSION_EMITTER", "EXPLOSION_HUGE");
    public static final Particle EXPLOSION = Compat.particle("EXPLOSION", "EXPLOSION_LARGE");
    public static final Particle ENCHANT = Compat.particle("ENCHANT", "ENCHANTMENT_TABLE");
    public static final Particle TOTEM = Compat.particle("TOTEM_OF_UNDYING", "TOTEM");
    public static final Particle LARGE_SMOKE = Compat.particle("LARGE_SMOKE", "SMOKE_LARGE");
    public static final Particle SMOKE = Compat.particle("SMOKE", "SMOKE_NORMAL");
    public static final Particle POOF = Compat.particle("POOF", "EXPLOSION_NORMAL");
    public static final Particle CLOUD = Compat.particle("CLOUD");
    public static final Particle FLAME = Compat.particle("FLAME");
    public static final Particle SMALL_FLAME = Compat.particle("SMALL_FLAME");
    public static final Particle LAVA = Compat.particle("LAVA");
    public static final Particle SOUL = Compat.particle("SOUL");
    public static final Particle SOUL_FIRE_FLAME = Compat.particle("SOUL_FIRE_FLAME");
    public static final Particle SCULK_SOUL = Compat.particle("SCULK_SOUL");
    public static final Particle SCULK_CHARGE_POP = Compat.particle("SCULK_CHARGE_POP");
    public static final Particle SNOWFLAKE = Compat.particle("SNOWFLAKE");
    public static final Particle HEART = Compat.particle("HEART");
    public static final Particle NOTE = Compat.particle("NOTE");
    public static final Particle PORTAL = Compat.particle("PORTAL");
    public static final Particle REVERSE_PORTAL = Compat.particle("REVERSE_PORTAL");
    public static final Particle END_ROD = Compat.particle("END_ROD");
    public static final Particle ELECTRIC_SPARK = Compat.particle("ELECTRIC_SPARK");
    public static final Particle SWEEP_ATTACK = Compat.particle("SWEEP_ATTACK");
    public static final Particle CRIT = Compat.particle("CRIT");
    public static final Particle FLASH = Compat.particle("FLASH");
    public static final Particle DRAGON_BREATH = Compat.particle("DRAGON_BREATH");
    public static final Particle SQUID_INK = Compat.particle("SQUID_INK");
    public static final Particle GLOW_SQUID_INK = Compat.particle("GLOW_SQUID_INK");
    public static final Particle GLOW = Compat.particle("GLOW");
    public static final Particle ASH = Compat.particle("ASH");
    public static final Particle WHITE_ASH = Compat.particle("WHITE_ASH");
    public static final Particle FALLING_WATER = Compat.particle("FALLING_WATER");
    public static final Particle SPLASH = Compat.particle("SPLASH", "WATER_SPLASH");
    public static final Particle BUBBLE_POP = Compat.particle("BUBBLE_POP");
    public static final Particle BUBBLE_COLUMN_UP = Compat.particle("BUBBLE_COLUMN_UP");
    public static final Particle CAMPFIRE_SIGNAL = Compat.particle("CAMPFIRE_SIGNAL_SMOKE");
    public static final Particle CHERRY_LEAVES = Compat.particle("CHERRY_LEAVES");
    public static final Particle SPORE_BLOSSOM_AIR = Compat.particle("SPORE_BLOSSOM_AIR");
    public static final Particle CRIMSON_SPORE = Compat.particle("CRIMSON_SPORE");
    public static final Particle WARPED_SPORE = Compat.particle("WARPED_SPORE");
    public static final Particle FIREWORK = Compat.particle("FIREWORK", "FIREWORKS_SPARK");
    public static final Particle HAPPY_VILLAGER = Compat.particle("HAPPY_VILLAGER", "VILLAGER_HAPPY");
    public static final Particle WAX_OFF = Compat.particle("WAX_OFF");
    private static final Enchantment GLOW_ENCHANT = Compat.resolveGlow();

    private Compat() {
    }

    private static Particle particle(String ... names) {
        for (String n : names) {
            try {
                return Particle.valueOf((String)n);
            }
            catch (IllegalArgumentException | NoSuchFieldError throwable) {
            }
        }
        return null;
    }

    public static int missingParticles() {
        int n = 0;
        for (Field f : Compat.class.getDeclaredFields()) {
            if (f.getType() != Particle.class) continue;
            try {
                if (f.get(null) != null) continue;
                ++n;
            }
            catch (IllegalAccessException illegalAccessException) {
                // empty catch block
            }
        }
        return n;
    }

    public static void spawn(World w, Particle p, Location l, int count, double ox, double oy, double oz, double extra) {
        if (p == null || l == null) {
            return;
        }
        try {
            Class expected = p.getDataType();
            if (expected == Void.class) {
                w.spawnParticle(p, l, count, ox, oy, oz, extra);
                return;
            }
            Object data = Compat.defaultData(expected);
            if (data != null) {
                w.spawnParticle(p, l, count, ox, oy, oz, extra, data);
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private static Object defaultData(Class expected) {
        if (expected == org.bukkit.Color.class) {
            return org.bukkit.Color.WHITE;
        }
        if (expected == Float.class) {
            return Float.valueOf(1.0f);
        }
        return null;
    }

    public static void spawn(World w, Particle p, Location l, int count) {
        Compat.spawn(w, p, l, count, 0.0, 0.0, 0.0, 0.0);
    }

    public static <T> void spawn(World w, Particle p, Location l, int count, double ox, double oy, double oz, double extra, T data) {
        if (p == null || l == null) {
            return;
        }
        try {
            Class expected = p.getDataType();
            if (expected == Void.class) {
                w.spawnParticle(p, l, count, ox, oy, oz, extra);
                return;
            }
            if (!expected.isInstance(data)) {
                Object fallback = Compat.defaultData(expected);
                if (fallback == null) {
                    return;
                }
                w.spawnParticle(p, l, count, ox, oy, oz, extra, fallback);
                return;
            }
            w.spawnParticle(p, l, count, ox, oy, oz, extra, data);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    public static <T> void spawn(World w, Particle p, Location l, int count, T data) {
        Compat.spawn(w, p, l, count, 0.0, 0.0, 0.0, 0.0, data);
    }

    public static void sound(World w, Location l, String key, float volume, float pitch) {
        if (w == null || l == null) {
            return;
        }
        try {
            w.playSound(l, key, SoundCategory.PLAYERS, volume, pitch);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private static Enchantment resolveGlow() {
        NamespacedKey key = NamespacedKey.minecraft((String)"unbreaking");
        try {
            Enchantment e = (Enchantment)Registry.ENCHANTMENT.get(key);
            if (e != null) {
                return e;
            }
        }
        catch (Throwable e) {
            // empty catch block
        }
        try {
            return Enchantment.getByKey((NamespacedKey)key);
        }
        catch (Throwable ignored) {
            return null;
        }
    }

    public static Enchantment glow() {
        return GLOW_ENCHANT;
    }

    public static boolean applyHead(SkullMeta meta, String hashOrValue) {
        if (hashOrValue == null || hashOrValue.isBlank()) {
            return false;
        }
        String hash = hashOrValue.trim();
        if (hash.startsWith("ey")) {
            try {
                int j;
                int end;
                String json = new String(Base64.getDecoder().decode(hash), StandardCharsets.UTF_8);
                int i = json.indexOf("texture/");
                if (i < 0) {
                    return false;
                }
                for (end = j = i + 8; end < json.length() && Character.isLetterOrDigit(json.charAt(end)); ++end) {
                }
                hash = json.substring(j, end);
            }
            catch (IllegalArgumentException ex) {
                return false;
            }
        }
        try {
            PlayerProfile profile = Bukkit.createPlayerProfile((UUID)UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8)), null);
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(new URL("https://textures.minecraft.net/texture/" + hash));
            profile.setTextures(textures);
            meta.setOwnerProfile(profile);
            return true;
        }
        catch (Throwable ex) {
            return false;
        }
    }

    public static ItemStack head(String hashOrValue) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta itemMeta = item.getItemMeta();
        if (!(itemMeta instanceof SkullMeta)) {
            return null;
        }
        SkullMeta meta = (SkullMeta)itemMeta;
        if (!Compat.applyHead(meta, hashOrValue)) {
            return null;
        }
        item.setItemMeta((ItemMeta)meta);
        return item;
    }
}

