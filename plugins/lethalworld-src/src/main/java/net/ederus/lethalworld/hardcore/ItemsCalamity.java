package net.ederus.lethalworld.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.lethalworld.LethalWorldPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Los dos objetos que hacen habitable a Calamity.
 *
 * Nada de resource pack (esta prohibido en Ederus): son objetos vanilla con nombre,
 * brillo y una marca en el PersistentDataContainer, que es lo que de verdad los
 * identifica. Un jugador puede renombrar una botella como quiera; sin la marca no
 * funciona.
 */
public final class ItemsCalamity {

    public static final TextColor VERDE = TextColor.color(0x8FD6A8);
    public static final TextColor MORADO = TextColor.color(0xC792EA);

    private final LethalWorldPlugin plugin;
    /** Marca del frasco; su valor es cuantos tragos le quedan. */
    private final NamespacedKey claveFrasco;
    /** Marca del cristal de regreso. */
    private final NamespacedKey claveCristal;
    /** Marca de la esencia, la moneda con la que se recarga el frasco. */
    private final NamespacedKey claveEsencia;

    public ItemsCalamity(LethalWorldPlugin plugin) {
        this.plugin = plugin;
        /* Namespace "edm" a mano: estos items ya estan repartidos por el servidor con
         * esa marca. Aunque Lethal World ya no sea un modulo de EDM, la clave no puede
         * cambiar o los frascos, cristales y esencias de los cofres dejarian de valer. */
        this.claveFrasco = new NamespacedKey("edm", "frasco_calma");
        this.claveCristal = new NamespacedKey("edm", "cristal_regreso");
        this.claveEsencia = new NamespacedKey("edm", "esencia_calamidad");
    }

    // ------------------------------------------------------------------ el frasco

    /** El Frasco de Calma con los tragos que se le digan. */
    public ItemStack frasco(int usos) {
        int max = plugin.getConfig().getInt("hardcore.frasco.usos", 3);
        int quedan = Math.max(0, Math.min(max, usos));
        ItemStack item = new ItemStack(Material.POTION);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Frasco de Calma", VERDE)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Recupera "
                    + plugin.getConfig().getInt("hardcore.frasco.cordura", 40)
                    + " de cordura.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Tragos: " + quedan + " de " + max, VERDE)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text("Clic derecho para beber.", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Se recarga en el altar del spawn.", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            if (meta instanceof org.bukkit.inventory.meta.PotionMeta pm) {
                pm.setColor(org.bukkit.Color.fromRGB(0x8FD6A8));
            }
            meta.getPersistentDataContainer().set(claveFrasco, PersistentDataType.INTEGER, quedan);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Tragos que le quedan al frasco, o -1 si el objeto no es un frasco. */
    public int tragos(ItemStack item) {
        if (item == null || item.getItemMeta() == null) return -1;
        Integer v = item.getItemMeta().getPersistentDataContainer()
                .get(claveFrasco, PersistentDataType.INTEGER);
        return v == null ? -1 : v;
    }

    public boolean esFrasco(ItemStack item) {
        return tragos(item) >= 0;
    }

    // ----------------------------------------------------------------- el cristal

    /** El Cristal de Regreso: la unica salida que no es el portal. */
    public ItemStack cristal() {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Cristal de Regreso", MORADO)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Te devuelve al spawn.", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Clic derecho y quédate quieto "
                            + plugin.getConfig().getInt("hardcore.cristal.segundos", 5) + " s.",
                            NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Se consume al usarlo.", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(claveCristal, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean esCristal(ItemStack item) {
        return item != null && item.getItemMeta() != null
                && item.getItemMeta().getPersistentDataContainer()
                        .has(claveCristal, PersistentDataType.BYTE);
    }

    // ----------------------------------------------------------------- la esencia

    /** Esencia de Calamidad: lo que sueltan los mobs y con lo que se recarga el frasco. */
    public ItemStack esencia(int cantidad) {
        ItemStack item = new ItemStack(Material.GHAST_TEAR, Math.max(1, Math.min(64, cantidad)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Esencia de Calamidad", TextColor.color(0xE8903C))
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Lo que queda de lo que muere allí.", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Recarga el Frasco de Calma en el altar.", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(claveEsencia, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean esEsencia(ItemStack item) {
        return item != null && item.getItemMeta() != null
                && item.getItemMeta().getPersistentDataContainer()
                        .has(claveEsencia, PersistentDataType.BYTE);
    }
}
