package net.zakiworld.anomaly.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URL;
import java.util.UUID;

/**
 * Hacer que una criatura del plugin se vea como una PERSONA.
 *
 * Hay dos niveles y se usan los dos:
 *
 *  - Con LibsDisguises (Ederus lo tiene), la entidad se ve como un jugador entero, con
 *    su skin, sus brazos y su forma. Es lo que hace que Rabby parezca un vecino y que
 *    el Mimic sea de verdad la copia de alguien.
 *  - Sin el, queda el apano de siempre: la cabeza con la textura puesta como casco. Se
 *    nota que debajo hay un zombi, pero el plugin arranca igual.
 *
 * Todo el hook va por reflexion, como el de WorldGuard: si manana quitan el plugin,
 * esto no compila menos ni revienta, solo se ve peor.
 */
public final class Disguises {

    private Disguises() {
    }

    public static boolean available(Plugin plugin) {
        return plugin.getServer().getPluginManager().getPlugin("LibsDisguises") != null;
    }

    /**
     * Disfraza a la entidad de jugador.
     *
     * @param name nombre que se vera encima
     * @param skin skin a usar: el nombre de un jugador de verdad, o el valor base64 de
     *             la propiedad de texturas. Si es null se usa el propio nombre.
     * @return true si el disfraz se puso
     */
    public static boolean asPlayer(Plugin plugin, Entity entity, String name, String skin) {
        if (entity == null || !available(plugin)) return false;
        try {
            Class<?> playerDisguise = Class.forName("me.libraryaddict.disguise.disguisetypes.PlayerDisguise");
            Object disguise = playerDisguise.getConstructor(String.class).newInstance(name);
            if (skin != null && !skin.isBlank()) {
                playerDisguise.getMethod("setSkin", String.class).invoke(disguise, skin);
            }
            Class<?> base = Class.forName("me.libraryaddict.disguise.disguisetypes.Disguise");
            // Que el disfraz no se caiga solo al alejarse el jugador que lo mira.
            try {
                base.getMethod("setKeepDisguiseOnPlayerDeath", boolean.class).invoke(disguise, true);
            } catch (Throwable ignored) {
            }
            Class<?> api = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            api.getMethod("disguiseEntity", Entity.class, base).invoke(null, entity, disguise);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().info("No se pudo disfrazar de jugador (" + t.getClass().getSimpleName()
                    + "); se usara la cabeza con skin.");
            return false;
        }
    }

    /** Le quita el disfraz, si lo llevaba. */
    public static void clear(Plugin plugin, Entity entity) {
        if (entity == null || !available(plugin)) return;
        try {
            Class<?> api = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            api.getMethod("undisguiseToAll", Entity.class).invoke(null, entity);
        } catch (Throwable ignored) {
        }
    }

    /**
     * El perfil de una skin concreta, listo para ponerselo a un MANNEQUIN.
     *
     * Esta es la via buena: el maniqui es una entidad viva con forma de jugador y
     * perfil propio, asi que la skin es EXACTAMENTE la que se le pide, sin depender
     * de que haya LibsDisguises ni de que Mojang resuelva un nombre.
     *
     * @param hash el identificador de textura de Mojang (lo que va detras de
     *             textures.minecraft.net/texture/)
     */
    public static io.papermc.paper.datacomponent.item.ResolvableProfile profileOf(
            Plugin plugin, String hash, String name) {
        try {
            com.destroystokyo.paper.profile.PlayerProfile profile =
                    Bukkit.createProfile(UUID.nameUUIDFromBytes(hash.getBytes()), name);
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/"
                    + hash + "\"}}}";
            String value = java.util.Base64.getEncoder()
                    .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", value));
            return io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile(profile);
        } catch (Throwable t) {
            plugin.getLogger().warning("No se pudo construir el perfil de la skin " + hash + ": " + t);
            return null;
        }
    }

    /** El perfil de un jugador de verdad, para copiarle la cara tal cual. */
    public static io.papermc.paper.datacomponent.item.ResolvableProfile profileOf(
            Plugin plugin, org.bukkit.entity.Player player) {
        try {
            return io.papermc.paper.datacomponent.item.ResolvableProfile
                    .resolvableProfile(player.getPlayerProfile());
        } catch (Throwable t) {
            plugin.getLogger().warning("No se pudo copiar el perfil de " + player.getName() + ": " + t);
            return null;
        }
    }

    /**
     * Una cabeza de jugador con una textura concreta puesta.
     *
     * @param hash el identificador de textura de Mojang (lo que va detras de
     *             textures.minecraft.net/texture/)
     */
    public static ItemStack head(Plugin plugin, String hash, String name) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        try {
            PlayerProfile profile = Bukkit.createPlayerProfile(
                    UUID.nameUUIDFromBytes(hash.getBytes()), name);
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(new URL("https://textures.minecraft.net/texture/" + hash));
            profile.setTextures(textures);
            if (item.getItemMeta() instanceof SkullMeta meta) {
                meta.setOwnerProfile(profile);
                item.setItemMeta(meta);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("No se pudo poner la textura " + hash + " en una cabeza: " + t);
        }
        return item;
    }
}
