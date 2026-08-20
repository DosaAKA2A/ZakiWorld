package net.ederus.edm.tienda;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.io.File;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Las cabezas del menu. Son las mismas que usa EconomyShopGUI hoy, sacadas de
 * sus sections/*.yml, para que al cambiar de tienda no le cambie la cara a
 * ninguna categoria.
 *
 * Las cabezas se construyen UNA vez y se guardan: montar el perfil en cada
 * repintado es caro y el menu se repinta despues de cada compra.
 */
public final class Iconos {

    private static final String TEXTURAS = "https://textures.minecraft.net/texture/";

    private final Map<String, String> configurado = new HashMap<>();
    private final Map<String, ItemStack> cache = new HashMap<>();

    public void cargar(File fichero) {
        configurado.clear();
        cache.clear();
        if (!fichero.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichero);
        ConfigurationSection sec = yml.getConfigurationSection("categorias");
        if (sec == null) return;
        for (String cat : sec.getKeys(false)) {
            String v = sec.getString(cat);
            if (v != null && !v.isBlank()) configurado.put(cat, v.trim());
        }
    }

    /** El icono de una categoria; si no hay nada configurado, devuelve null. */
    public ItemStack de(String categoria) {
        String v = configurado.get(categoria);
        if (v == null) return null;

        ItemStack guardado = cache.get(categoria);
        if (guardado != null) return guardado.clone();

        ItemStack pila;
        if (v.toUpperCase().startsWith("MAT:")) {
            Material m = Material.matchMaterial(v.substring(4).trim());
            if (m == null) return null;
            pila = new ItemStack(m);
        } else {
            pila = cabeza(v);
            if (pila == null) return null;
        }
        cache.put(categoria, pila);
        return pila.clone();
    }

    /** Una cabeza con la textura dada. El UUID sale del propio hash: asi es
     *  siempre el mismo y no se ensucia la cache de perfiles del servidor. */
    private static ItemStack cabeza(String hash) {
        try {
            ItemStack pila = new ItemStack(Material.PLAYER_HEAD);
            if (!(pila.getItemMeta() instanceof SkullMeta meta)) return null;

            PlayerProfile perfil = Bukkit.createPlayerProfile(
                    UUID.nameUUIDFromBytes(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            PlayerTextures texturas = perfil.getTextures();
            texturas.setSkin(URI.create(TEXTURAS + hash).toURL());
            perfil.setTextures(texturas);
            meta.setOwnerProfile(perfil);

            pila.setItemMeta(meta);
            return pila;
        } catch (Exception e) {
            /* Una textura mal copiada no puede tumbar el menu: se cae a que el
             * icono sea el primer articulo de la categoria. */
            return null;
        }
    }

    public int configurados() { return configurado.size(); }
}
