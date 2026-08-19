/*
 * Decompiled with CFR 0.152.
 */
package net.ederus.edm.rip;

import net.ederus.edm.rip.MannequinHook;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

final class Clones {
    private Clones() {
    }

    static LivingEntity spawnFrozenClone(Location loc, Player victim, ItemStack[] armor, ItemStack hand) {
        Location spot = loc.clone();
        spot.setPitch(0.0f);
        LivingEntity clone = MannequinHook.available() ? MannequinHook.spawnFrozenClone(spot, victim) : null;
        LivingEntity livingEntity = clone;
        if (clone == null) {
            clone = Clones.armorStandClone(spot, victim);
        }
        if (clone == null) {
            return null;
        }
        Clones.dress(clone, victim, armor, hand);
        return clone;
    }

    private static LivingEntity armorStandClone(Location loc, Player victim) {
        if (loc.getWorld() == null) {
            return null;
        }
        try {
            return (LivingEntity)loc.getWorld().spawn(loc, ArmorStand.class, stand -> {
                EntityEquipment eq;
                stand.setArms(true);
                stand.setBasePlate(false);
                stand.setInvulnerable(true);
                stand.setGravity(false);
                stand.setSilent(true);
                stand.setPersistent(false);
                stand.setCanMove(false);
                stand.setCanTick(false);
                stand.setDisabledSlots(EquipmentSlot.values());
                stand.customName(net.kyori.adventure.text.Component.text(victim.getName()));
                stand.setCustomNameVisible(true);
                ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
                ItemMeta patt0$temp = skull.getItemMeta();
                if (patt0$temp instanceof SkullMeta) {
                    SkullMeta meta = (SkullMeta)patt0$temp;
                    meta.setOwningPlayer((OfflinePlayer)victim);
                    skull.setItemMeta((ItemMeta)meta);
                }
                if ((eq = stand.getEquipment()) != null) {
                    eq.setHelmet(skull);
                }
            });
        }
        catch (Throwable ex) {
            return null;
        }
    }

    private static void dress(LivingEntity clone, Player victim, ItemStack[] armor, ItemStack hand) {
        EntityEquipment eq = clone.getEquipment();
        if (eq == null) {
            return;
        }
        try {
            boolean mannequin = !(clone instanceof ArmorStand);
            boolean bl = mannequin;
            if (armor != null) {
                if (armor.length > 0 && armor[0] != null) {
                    eq.setBoots(armor[0].clone());
                }
                if (armor.length > 1 && armor[1] != null) {
                    eq.setLeggings(armor[1].clone());
                }
                if (armor.length > 2 && armor[2] != null) {
                    eq.setChestplate(armor[2].clone());
                }
                if (mannequin && armor.length > 3 && armor[3] != null) {
                    eq.setHelmet(armor[3].clone());
                }
            }
            if (hand != null && hand.getType() != Material.AIR) {
                eq.setItemInMainHand(hand.clone());
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }
}

