package net.ederus.edm.anomaly.drops;

import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.Type;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mete en la tabla de una anomalia las piezas de un set de MMOItems como objetos
 * de verdad: se ven en el menu de Botin y salen volando del jefe como el resto.
 *
 * Antes se daban por comando (`mi give ...`), que ni se ve en la tabla ni explota.
 * Las piezas se buscan por su etiqueta de set (MMOITEMS_ITEM_SET) construyendo cada
 * plantilla, primero en el tipo ANOMALIA y, si ahi no hay ninguna, en todos.
 */
public final class SetLoot {

    private SetLoot() {
    }

    /** El set de cada anomalia, el que se creo para ella. */
    public static final Map<String, String> DE_SERIE = Map.ofEntries(
            Map.entry("coro_abisal", "CORO_ABISAL"),
            Map.entry("aragon", "ARAGON"),
            Map.entry("bruja", "BRUJA"),
            Map.entry("darkness", "DARKNESS"),
            Map.entry("herbola", "HERBOLA"),
            Map.entry("keeper", "KEEPER"),
            Map.entry("conejo_asesino", "CONEJO_ASESINO"),
            Map.entry("mimic", "MIMIC"),
            Map.entry("quimera", "QUIMERA"),
            Map.entry("leviatan_de_sal", "LEVIATAN"),
            Map.entry("cabra_gritona", "CABRA_GRITONA"),
            Map.entry("caballero_sepulcral", "CABALLERO_SEPULCRAL"),
            Map.entry("storm_rider", "STORM_RIDER"),
            Map.entry("alba", "AUREOLA_DEL_ALBA"),
            Map.entry("raiz", "CORTEZA_DE_ROTTEN"),
            Map.entry("gemelos_cobre", "FUNDICION_DE_KEM_Y_KAM"),
            Map.entry("rabby", "BUNNY"),
            Map.entry("cazador", "CAZADOR"),
            Map.entry("piromante", "CENIZA_PIROMANTE"));

    public static boolean disponible() {
        return Bukkit.getPluginManager().isPluginEnabled("MMOItems");
    }

    /** Las piezas del set, ya construidas. Vacio si no existe o MMOItems no esta. */
    public static List<ItemStack> piezas(String setId) {
        String set = setId.toUpperCase(Locale.ROOT);
        Type anomalia = MMOItems.plugin.getTypes().get("ANOMALIA");
        List<ItemStack> out = anomalia == null ? new ArrayList<>() : buscar(anomalia, set);
        if (!out.isEmpty()) return out;
        for (Type t : MMOItems.plugin.getTypes().getAll()) {
            if (t == anomalia) continue;
            out.addAll(buscar(t, set));
        }
        return out;
    }

    private static List<ItemStack> buscar(Type tipo, String set) {
        List<ItemStack> out = new ArrayList<>();
        for (String id : MMOItems.plugin.getTemplates().getTemplateNames(tipo)) {
            ItemStack item;
            try {
                item = MMOItems.plugin.getItem(tipo, id);
            } catch (Throwable t) {
                continue;
            }
            if (item != null && set.equals(setDe(item))) out.add(item);
        }
        return out;
    }

    /** La etiqueta MMOITEMS_ITEM_SET, igual que la lee el puente de GodItems. */
    private static String setDe(ItemStack item) {
        var meta = item.getItemMeta();
        if (meta == null) return null;
        var pdc = meta.getPersistentDataContainer();
        for (var k : pdc.getKeys()) {
            if (!k.getNamespace().equalsIgnoreCase("mmoitems")) continue;
            if (!k.getKey().toLowerCase(Locale.ROOT).endsWith("item_set")) continue;
            try {
                String v = pdc.get(k, org.bukkit.persistence.PersistentDataType.STRING);
                if (v != null && !v.isBlank()) return v.toUpperCase(Locale.ROOT);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** El TIPO e ID de MMOItems de un objeto, para comparar con las lineas `mi give`. */
    public static String idDe(ItemStack item) {
        try {
            String id = MMOItems.getID(item);
            return id == null ? null : id.toUpperCase(Locale.ROOT);
        } catch (Throwable t) {
            return null;
        }
    }
}
