package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.calamity.CalamityPlugin;
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

    private final CalamityPlugin plugin;
    /** Marca del frasco; su valor es cuantos tragos le quedan. */
    private final NamespacedKey claveFrasco;
    /** Marca del cristal de regreso. */
    private final NamespacedKey claveCristal;
    /** Marca de la esencia, la moneda con la que se recarga el frasco. */
    private final NamespacedKey claveEsencia;

    public ItemsCalamity(CalamityPlugin plugin) {
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
            lore.add(Component.text("Cada trago te devuelve "
                    + plugin.getConfig().getInt("hardcore.frasco.cordura", 40)
                    + " de cordura.", Paleta.TEXTO).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Tragos: " + quedan + " de " + max, VERDE)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text("Clic derecho para beber.", Paleta.TENUE)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Se recarga en el Altar.", Paleta.TENUE)
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
                    Component.text("Te saca vivo de Calamity sin", Paleta.TEXTO)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("pasar por la puerta de salida.", Paleta.TEXTO)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Clic derecho y quédate quieto "
                            + plugin.getConfig().getInt("hardcore.cristal.segundos", 5) + " s.",
                            Paleta.TENUE).decoration(TextDecoration.ITALIC, false),
                    Component.text("Si te mueves, se apaga.", Paleta.TENUE)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("En combate no funciona.", Paleta.TENUE)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Se gasta al usarlo.", Paleta.TENUE)
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

    /**
     * Esencia de Calamidad: lo que sueltan los mobs y con lo que se recarga el frasco.
     *
     * El material sale de hardcore.esencias.material (DIS M2, "Esencias vendibles"): la
     * lagrima de ghast se vendia en /shop y, si la tienda compra por material, una Esencia
     * seria dinero. Cambiarlo solo afecta a las NUEVAS; todas se reconocen por la marca, asi
     * que las que ya circulan siguen valiendo.
     */
    public ItemStack esencia(int cantidad) {
        ItemStack item = new ItemStack(materialEsencia(), Math.max(1, Math.min(64, cantidad)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Esencia de Calamidad", TextColor.color(0xE8903C))
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("La sueltan los mobs y los", Paleta.TEXTO)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("cofres de Calamity.", Paleta.TEXTO)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Si sales vivo, pasa a tu saldo.", Paleta.TENUE)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Con el saldo pagas en el Altar", Paleta.TENUE)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("y en la Forja.", Paleta.TENUE)
                            .decoration(TextDecoration.ITALIC, false)));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(claveEsencia, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ------------------------------------------------------- el Fragmento de Masamune

    /**
     * Fragmento de Masamune: lo que deja Ambush a su presa, en fisico (antes era un credito). La
     * Forja de Vael pide cinco para la Masamune. Chatarra de netherita con el nombre en el gris
     * acero de la Masamune y la marca lethal_world:fragmento_masamune, que es lo que lo identifica
     * aunque lo renombren. Se apila; no entra en recetas ni en hornos (ObjetosCalamity) y el
     * Mercader no lo compra (solo compra Reliquias). Sale sin ligar: lo liga quien lo entrega.
     */
    public static ItemStack fragmentoMasamune(int cantidad) {
        ItemStack item = new ItemStack(Material.NETHERITE_SCRAP, Math.max(1, Math.min(64, cantidad)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Paleta.degradado(FragmentosMasamune.NOMBRE, Paleta.ACERO_DESDE, Paleta.ACERO_HASTA));
            meta.lore(List.of(
                    Component.text("Un trozo de la katana de Ambush.", Paleta.TEXTO)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Vael forja la Masamune con cinco.", Paleta.TENUE)
                            .decoration(TextDecoration.ITALIC, false)));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(Marcas.FRAGMENTO_MASAMUNE, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Si es un Fragmento de Masamune (por la marca: da igual como se llame). */
    public static boolean esFragmentoMasamune(ItemStack item) {
        return Marcas.tiene(item, Marcas.FRAGMENTO_MASAMUNE);
    }

    /** Ultimo valor raro de esencias.material ya avisado, para no llenar la consola. */
    private String materialAvisado;

    private Material materialEsencia() {
        String nombre = plugin.getConfig().getString("hardcore.esencias.material", "GHAST_TEAR");
        Material m = nombre == null ? null : Material.matchMaterial(nombre.trim());
        if (m != null && m.isItem() && !m.isAir()) return m;
        if (nombre != null && !nombre.equals(materialAvisado)) {
            materialAvisado = nombre;
            plugin.getLogger().warning("[Calamity] hardcore.esencias.material \"" + nombre
                    + "\" no es un objeto; las Esencias salen como GHAST_TEAR.");
        }
        return Material.GHAST_TEAR;
    }

    public boolean esEsencia(ItemStack item) {
        return item != null && item.getItemMeta() != null
                && item.getItemMeta().getPersistentDataContainer()
                        .has(claveEsencia, PersistentDataType.BYTE);
    }
}
