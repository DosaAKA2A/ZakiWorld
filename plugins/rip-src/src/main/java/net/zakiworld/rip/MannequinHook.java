/*
 * Decompiled with CFR 0.152.
 */
package net.zakiworld.rip;

import com.destroystokyo.paper.ClientOption;
import com.destroystokyo.paper.SkinParts;
import com.destroystokyo.paper.profile.PlayerProfile;
import java.lang.reflect.Method;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

final class MannequinHook {
    private static final boolean AVAILABLE;
    private static final EntityType MANNEQUIN_TYPE;
    private static final Method SET_PROFILE;
    private static final Method SET_SKIN_PARTS;
    private static final Method SET_IMMOVABLE;
    private static final Method SET_DESCRIPTION;
    private static final Method RESOLVABLE_FACTORY;

    private MannequinHook() {
    }

    static boolean available() {
        return AVAILABLE;
    }

    static LivingEntity spawnFrozenClone(Location loc, Player likeness) {
        if (!AVAILABLE || loc.getWorld() == null) {
            return null;
        }
        Entity raw = null;
        try {
            raw = loc.getWorld().spawnEntity(loc, MANNEQUIN_TYPE);
            if (!(raw instanceof LivingEntity)) {
                raw.remove();
                return null;
            }
            LivingEntity living = (LivingEntity)raw;
            living.setPersistent(false);
            Object profile = RESOLVABLE_FACTORY.invoke(null, likeness.getPlayerProfile());
            SET_PROFILE.invoke(living, profile);
            try {
                SkinParts parts = (SkinParts)likeness.getClientOption(ClientOption.SKIN_PARTS);
                if (parts != null) {
                    SET_SKIN_PARTS.invoke(living, parts);
                }
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            SET_IMMOVABLE.invoke(living, true);
            SET_DESCRIPTION.invoke(living, new Object[]{null});
            living.setInvulnerable(true);
            living.setSilent(true);
            living.setCollidable(false);
            living.setGravity(false);
            return living;
        }
        catch (Throwable ex) {
            if (raw != null) {
                try {
                    raw.remove();
                }
                catch (Throwable throwable) {
                    // empty catch block
                }
            }
            return null;
        }
    }

    static {
        boolean ok = false;
        EntityType type = null;
        Method setProfile = null;
        Method setSkinParts = null;
        Method setImmovable = null;
        Method setDescription = null;
        Method factory = null;
        try {
            type = EntityType.valueOf((String)"MANNEQUIN");
            Class<?> mannequin = Class.forName("org.bukkit.entity.Mannequin");
            Class<?> resolvable = Class.forName("io.papermc.paper.datacomponent.item.ResolvableProfile");
            factory = resolvable.getMethod("resolvableProfile", PlayerProfile.class);
            setProfile = mannequin.getMethod("setProfile", resolvable);
            setSkinParts = mannequin.getMethod("setSkinParts", SkinParts.class);
            setImmovable = mannequin.getMethod("setImmovable", Boolean.TYPE);
            setDescription = mannequin.getMethod("setDescription", Class.forName("net.kyori.adventure.text.Component"));
            ok = true;
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        AVAILABLE = ok;
        MANNEQUIN_TYPE = type;
        SET_PROFILE = setProfile;
        SET_SKIN_PARTS = setSkinParts;
        SET_IMMOVABLE = setImmovable;
        SET_DESCRIPTION = setDescription;
        RESOLVABLE_FACTORY = factory;
    }
}

