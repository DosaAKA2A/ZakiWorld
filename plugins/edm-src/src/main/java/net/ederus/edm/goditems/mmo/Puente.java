package net.ederus.edm.goditems.mmo;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.ItemSet;
import net.Indyuce.mmoitems.api.Type;
import net.Indyuce.mmoitems.api.item.template.MMOItemTemplate;
import net.ederus.edm.goditems.GodItemsPlugin;

/**
 * El puente con MMOItems.
 *
 * GodItems es un ADDON de MMOItems, no un sistema de items paralelo. Este es el
 * unico sitio que habla con el, y toda la clase esta escrita para que EDM
 * arranque igual si MMOItems no esta instalado: si falta, {@link #hay()} da
 * false y el resto del modulo funciona en modo nativo.
 *
 * Reparto de propiedad, que es la decision de la que cuelga todo:
 *
 *   - MMOItems es el DUEÑO de material, nombre, lore, stats, tier, set,
 *     durabilidad y gemas. Vive en sus YAML de `plugins/MMOItems/item/`.
 *   - GodItems es el dueño del COMPORTAMIENTO (activadores, acciones,
 *     condiciones) y lo guarda en los suyos.
 *
 * Cuando se edita un stat desde la interfaz de GodItems, se escribe EN EL YAML
 * DE MMOITEMS y se le pide que recargue esa plantilla. No hay copia nuestra que
 * sincronizar: los dos editores leen el mismo sitio, asi que no pueden
 * discrepar. Un espejo con dos copias si podria, porque MMOItems no lanza
 * ningun evento al cambiar un stat y no habria forma de enterarse.
 */
public final class Puente {

    private final GodItemsPlugin modulo;
    private final boolean hay;

    public Puente(GodItemsPlugin modulo) {
        this.modulo = modulo;
        boolean ok = false;
        try {
            ok = Bukkit.getPluginManager().getPlugin("MMOItems") != null
                    && Bukkit.getPluginManager().isPluginEnabled("MMOItems")
                    && MMOItems.plugin != null;
        } catch (Throwable t) {
            modulo.getLogger().warning("[GodItems] MMOItems esta pero no se pudo enganchar: " + t);
        }
        this.hay = ok;
        if (ok) {
            modulo.getLogger().info("[GodItems] MMOItems enganchado: "
                    + tipos().size() + " tipos y " + contarPlantillas() + " items importables.");
        } else {
            modulo.getLogger().info("[GodItems] MMOItems no esta: solo funcionaran los items nativos.");
        }
    }

    public boolean hay() {
        return this.hay;
    }

    /* ======================================================== identidad */

    /**
     * El "TIPO.ID" de un item de MMOItems, o null.
     *
     * Con MMOItems delante se le pregunta a el, que es lo unico que no se
     * rompe cuando cambian sus etiquetas internas. Sin el, se leen a mano las
     * claves de su namespace en el PDC (`mmoitems:mmoitems_item_type` y
     * `..._id`), que es lo que permite que un servidor sin MMOItems siga
     * reconociendo items suyos que quedaran por ahi.
     */
    public String enlaceDe(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        if (this.hay) {
            try {
                Type t = MMOItems.getType(item);
                String id = MMOItems.getID(item);
                if (t != null && id != null && !id.isEmpty()) {
                    return t.getId().toUpperCase(Locale.ROOT) + "." + id.toUpperCase(Locale.ROOT);
                }
                return null;
            } catch (Throwable ignored) {
                /* Se cae al camino de abajo. */
            }
        }
        return enlacePorPdc(item);
    }

    /** El camino sin MMOItems: rebuscar en su namespace del PDC. */
    public static String enlacePorPdc(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return null;
        var pdc = meta.getPersistentDataContainer();
        String tipo = null;
        String id = null;
        for (var k : pdc.getKeys()) {
            if (!k.getNamespace().equalsIgnoreCase("mmoitems")) continue;
            String n = k.getKey().toLowerCase(Locale.ROOT);
            String v;
            try {
                v = pdc.get(k, PersistentDataType.STRING);
            } catch (Throwable e) {
                continue;
            }
            if (v == null || v.isBlank()) continue;
            if (n.endsWith("item_type") || n.equals("type")) tipo = v;
            else if (n.endsWith("item_id") || n.equals("id")) id = v;
        }
        return tipo == null || id == null
                ? null : tipo.toUpperCase(Locale.ROOT) + "." + id.toUpperCase(Locale.ROOT);
    }

    /** El set al que pertenece un item, o null. La etiqueta es MMOITEMS_ITEM_SET. */
    public String setDe(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return null;
        var pdc = meta.getPersistentDataContainer();
        for (var k : pdc.getKeys()) {
            if (!k.getNamespace().equalsIgnoreCase("mmoitems")) continue;
            if (!k.getKey().toLowerCase(Locale.ROOT).endsWith("item_set")) continue;
            try {
                String v = pdc.get(k, PersistentDataType.STRING);
                if (v != null && !v.isBlank()) return v.toUpperCase(Locale.ROOT);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /* ======================================================== catalogo */

    /** Los tipos de MMOItems que de verdad tienen items dentro. */
    public List<Type> tipos() {
        if (!this.hay) return List.of();
        try {
            List<Type> out = new ArrayList<>(MMOItems.plugin.getTypes().getAll());
            out.sort((a, b) -> a.getId().compareToIgnoreCase(b.getId()));
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    public Type tipo(String id) {
        if (!this.hay || id == null) return null;
        try {
            return MMOItems.plugin.getTypes().get(id.toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Los ids de los items de un tipo, ordenados. */
    public List<String> itemsDe(Type tipo) {
        if (!this.hay || tipo == null) return List.of();
        try {
            List<String> out = new ArrayList<>(MMOItems.plugin.getTemplates().getTemplateNames(tipo));
            Collections.sort(out);
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    public MMOItemTemplate plantilla(Type tipo, String id) {
        if (!this.hay || tipo == null || id == null) return null;
        try {
            return MMOItems.plugin.getTemplates().getTemplate(tipo, id.toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean existe(String tipoId, String id) {
        Type t = tipo(tipoId);
        return t != null && plantilla(t, id) != null;
    }

    private int contarPlantillas() {
        int n = 0;
        for (Type t : tipos()) n += itemsDe(t).size();
        return n;
    }

    /** Un item construido por MMOItems, para la vista previa de la interfaz. */
    public ItemStack construir(String tipoId, String id) {
        Type t = tipo(tipoId);
        if (t == null) return null;
        try {
            return MMOItems.plugin.getItem(t, id.toUpperCase(Locale.ROOT));
        } catch (Throwable e) {
            return null;
        }
    }

    /* ============================================================ sets */

    public ItemSet set(String id) {
        if (!this.hay || id == null) return null;
        try {
            return MMOItems.plugin.getSets().get(id.toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            return null;
        }
    }

    public List<String> sets() {
        if (!this.hay) return List.of();
        try {
            List<String> out = new ArrayList<>();
            for (ItemSet s : MMOItems.plugin.getSets().getAll()) out.add(s.getId());
            Collections.sort(out);
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /* ======================================================= ficheros */

    /**
     * El YAML donde MMOItems guarda las plantillas de un tipo.
     * El nombre del fichero es el id del tipo en minusculas: KATANA -> katana.yml.
     */
    public File ficheroDe(String tipoId) {
        var mmo = Bukkit.getPluginManager().getPlugin("MMOItems");
        if (mmo == null) return null;
        return new File(new File(mmo.getDataFolder(), "item"),
                tipoId.toLowerCase(Locale.ROOT) + ".yml");
    }

    /**
     * Relee UNA plantilla despues de que le hayamos escrito el YAML. Es lo que
     * hace que el cambio se vea al instante en /mi browse sin un reload entero
     * del plugin, que en Ederus tarda y toca a todo el mundo.
     */
    public boolean recargarPlantilla(String tipoId, String id) {
        Type t = tipo(tipoId);
        if (t == null) return false;
        try {
            MMOItems.plugin.getTemplates().requestTemplateUpdate(t, id.toUpperCase(Locale.ROOT));
            return true;
        } catch (Throwable e) {
            this.modulo.getLogger().warning("[GodItems] No se pudo recargar la plantilla "
                    + tipoId + "." + id + " (" + e + "). Hara falta un /mi reload.");
            return false;
        }
    }
}
