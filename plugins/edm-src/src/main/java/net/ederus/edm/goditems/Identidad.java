package net.ederus.edm.goditems;

import java.util.Locale;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Saber si un item es un GodItem, y cual.
 *
 * Es el UNICO sitio del modulo que escribe en el PDC de un item, y solo lo hace
 * con los NATIVOS. A un item enlazado no se le toca nada: se le leen las
 * etiquetas que MMOItems ya mantiene y se busca el comportamiento por ellas.
 *
 * Por que las etiquetas de MMOItems se buscan a tientas y no por una clave
 * fija: MMOItems guarda su NBT en el PDC con la ruta en minusculas (un
 * NamespacedKey no admite mayusculas), asi que "MMOITEMS_ITEM_TYPE" acaba
 * siendo "mmoitems:mmoitems_item_type". Ese nombre exacto ha cambiado entre
 * versiones suyas. Recorrer las claves de su namespace y quedarse con la que
 * acaba en _type y en _id aguanta el cambio; una constante, no.
 */
public final class Identidad {

    private final GodItemsPlugin modulo;
    private final NamespacedKey clave;
    private final NamespacedKey claveVars;

    public Identidad(GodItemsPlugin modulo) {
        this.modulo = modulo;
        this.clave = new NamespacedKey(modulo, "item");
        this.claveVars = new NamespacedKey(modulo, "vars");
    }

    public NamespacedKey clave() { return this.clave; }

    public NamespacedKey claveVariables() { return this.claveVars; }

    /** Marca un item recien fabricado como nativo de ese GodItem. */
    public ItemStack marcar(ItemStack item, GodItem def) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.getPersistentDataContainer().set(this.clave, PersistentDataType.STRING, def.id());
        item.setItemMeta(meta);
        return item;
    }

    /** El id del GodItem que lleva ese item, sea nativo o enlazado. Null si ninguno. */
    public String idDe(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        String propio = pdc.get(this.clave, PersistentDataType.STRING);
        if (propio != null) return propio;

        /* Con MMOItems delante se le pregunta a el; sin el, se rebusca en su
         * namespace del PDC. El puente decide cual de los dos toca. */
        String enlace = this.modulo.puente() != null
                ? this.modulo.puente().enlaceDe(item)
                : net.ederus.edm.goditems.mmo.Puente.enlacePorPdc(item);
        if (enlace == null) return null;
        int punto = enlace.indexOf('.');
        if (punto <= 0 || punto == enlace.length() - 1) return null;
        return this.modulo.registro().porEnlace(
                enlace.substring(0, punto), enlace.substring(punto + 1));
    }

    public GodItem definicionDe(ItemStack item) {
        String id = idDe(item);
        return id == null ? null : this.modulo.registro().porId(id);
    }

    /** El "TIPO.ID" de MMOItems que lleva un item, o null. */
    public String enlaceDe(ItemStack item) {
        if (this.modulo.puente() != null) return this.modulo.puente().enlaceDe(item);
        return net.ederus.edm.goditems.mmo.Puente.enlacePorPdc(item);
    }
}
