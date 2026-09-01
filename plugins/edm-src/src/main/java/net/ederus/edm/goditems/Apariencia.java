package net.ederus.edm.goditems;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import net.ederus.edm.comun.Estilo;

/**
 * Como se ve y que lleva puesto un GodItem NATIVO.
 *
 * Un item ENLAZADO no tiene apariencia: la pone MMOItems y aqui no se toca ni
 * un byte, que es justo lo que nos hace inmunes a su Item Revision System.
 */
public final class Apariencia {

    private final Material material;
    private final String nombre;
    private final List<String> lore;
    private final Map<String, Integer> encantos;
    /** hueco -> (atributo -> cantidad). */
    private final Map<String, Map<String, Double>> atributos;
    private final boolean irrompible;
    private final boolean brillo;
    private final List<String> ocultar;
    private final Integer modelo;
    private final String cabeza;
    private final String color;
    private final int cantidad;

    public Apariencia(Material material, String nombre, List<String> lore, Map<String, Integer> encantos,
                      Map<String, Map<String, Double>> atributos, boolean irrompible, boolean brillo,
                      List<String> ocultar, Integer modelo, String cabeza, String color, int cantidad) {
        this.material = material;
        this.nombre = nombre;
        this.lore = lore;
        this.encantos = encantos;
        this.atributos = atributos;
        this.irrompible = irrompible;
        this.brillo = brillo;
        this.ocultar = ocultar;
        this.modelo = modelo;
        this.cabeza = cabeza;
        this.color = color;
        this.cantidad = cantidad;
    }

    public Material material() { return this.material; }

    public String nombre() { return this.nombre; }

    public int cantidad() { return this.cantidad; }

    /**
     * Fabrica el item. La marca (goditems:id) NO se pone aqui: la pone
     * Identidad, para que exista un unico sitio en todo el modulo que escriba en
     * el PDC de un item.
     */
    public ItemStack fabricar(GodItemsPlugin modulo) {
        ItemStack item = null;
        if (this.material == Material.PLAYER_HEAD && this.cabeza != null && !this.cabeza.isBlank()) {
            item = net.ederus.edm.rip.Compat.head(this.cabeza);
            if (item == null) {
                modulo.getLogger().warning("Textura de cabeza invalida; se usa una cabeza lisa.");
            }
        }
        if (item == null) item = new ItemStack(this.material);
        item.setAmount(Math.max(1, Math.min(this.material.getMaxStackSize(), this.cantidad)));

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (this.nombre != null) meta.displayName(Estilo.legado(this.nombre));
        if (!this.lore.isEmpty()) {
            List<net.kyori.adventure.text.Component> lineas = new ArrayList<>();
            for (String l : this.lore) lineas.add(Estilo.legado(l));
            meta.lore(lineas);
        }
        for (Map.Entry<String, Integer> e : this.encantos.entrySet()) {
            Enchantment ench = encantamiento(e.getKey());
            if (ench == null) {
                modulo.getLogger().warning("Encantamiento desconocido: " + e.getKey());
                continue;
            }
            meta.addEnchant(ench, Math.max(1, e.getValue()), true);
        }
        if (this.brillo && this.encantos.isEmpty()) {
            /* Brillo sin encantamientos: en Paper moderno hay bandera propia y
             * ya no hace falta el truco del encantamiento invisible. */
            try {
                meta.setEnchantmentGlintOverride(Boolean.TRUE);
            } catch (Throwable t) {
                Enchantment g = net.ederus.edm.anomaly.core.Compat.glow();
                if (g != null) {
                    meta.addEnchant(g, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                }
            }
        }
        aplicarAtributos(modulo, meta);
        if (this.irrompible) meta.setUnbreakable(true);
        for (String f : this.ocultar) {
            if (f.trim().equalsIgnoreCase("TODO")) {
                meta.addItemFlags(ItemFlag.values());
                continue;
            }
            ItemFlag flag = bandera(f);
            if (flag == null) {
                modulo.getLogger().warning("Bandera de ocultar desconocida: " + f);
                continue;
            }
            meta.addItemFlags(flag);
        }
        if (this.modelo != null) {
            try {
                meta.setCustomModelData(this.modelo);
            } catch (Throwable ignored) {
            }
        }
        if (this.color != null && meta instanceof LeatherArmorMeta cuero) {
            Color c = color(this.color);
            if (c != null) cuero.setColor(c);
        }
        item.setItemMeta(meta);
        return item;
    }

    private void aplicarAtributos(GodItemsPlugin modulo, ItemMeta meta) {
        if (this.atributos.isEmpty()) return;
        for (Map.Entry<String, Map<String, Double>> hueco : this.atributos.entrySet()) {
            EquipmentSlotGroup grupo = grupo(hueco.getKey());
            if (grupo == null) {
                modulo.getLogger().warning("Hueco de atributo desconocido: " + hueco.getKey());
                continue;
            }
            for (Map.Entry<String, Double> a : hueco.getValue().entrySet()) {
                Attribute atr = net.ederus.edm.anomaly.core.Compat.attribute(
                        a.getKey().toLowerCase(Locale.ROOT));
                if (atr == null) {
                    modulo.getLogger().warning("Atributo desconocido: " + a.getKey());
                    continue;
                }
                /* La clave del modificador tiene que ser unica por atributo y
                 * hueco: si dos comparten clave, el segundo pisa al primero y el
                 * item se queda sin la mitad de sus stats. */
                NamespacedKey clave = new NamespacedKey(net.ederus.edm.Module.dueno(modulo),
                        "gi_" + a.getKey().toLowerCase(Locale.ROOT) + "_"
                                + hueco.getKey().toLowerCase(Locale.ROOT));
                meta.addAttributeModifier(atr, new AttributeModifier(
                        clave, a.getValue(), AttributeModifier.Operation.ADD_NUMBER, grupo));
            }
        }
    }

    /* --------------------------------------------------- traducciones sueltas */

    private static final Map<String, String> HUECOS = new LinkedHashMap<>();
    static {
        HUECOS.put("MANO_PRINCIPAL", "mainhand");
        HUECOS.put("MANO_SECUNDARIA", "offhand");
        HUECOS.put("MANO", "hand");
        HUECOS.put("CABEZA", "head");
        HUECOS.put("PECHO", "chest");
        HUECOS.put("PIERNAS", "legs");
        HUECOS.put("PIES", "feet");
        HUECOS.put("ARMADURA", "armor");
        HUECOS.put("SIEMPRE", "any");
    }

    public static EquipmentSlotGroup grupo(String s) {
        if (s == null) return null;
        String clave = s.trim().toUpperCase(Locale.ROOT);
        String bukkit = HUECOS.getOrDefault(clave, s.trim().toLowerCase(Locale.ROOT));
        try {
            return EquipmentSlotGroup.getByName(bukkit);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Enchantment encantamiento(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase(Locale.ROOT);
        try {
            Enchantment e = org.bukkit.Registry.ENCHANTMENT.get(NamespacedKey.minecraft(v));
            if (e != null) return e;
        } catch (Throwable ignored) {
        }
        try {
            return Enchantment.getByName(s.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final Map<String, ItemFlag> BANDERAS = new LinkedHashMap<>();
    static {
        BANDERAS.put("ENCANTOS", ItemFlag.HIDE_ENCHANTS);
        BANDERAS.put("ATRIBUTOS", ItemFlag.HIDE_ATTRIBUTES);
        BANDERAS.put("IRROMPIBLE", ItemFlag.HIDE_UNBREAKABLE);
        BANDERAS.put("TINTE", ItemFlag.HIDE_DYE);
    }

    public static ItemFlag bandera(String s) {
        if (s == null) return null;
        String clave = s.trim().toUpperCase(Locale.ROOT);
        ItemFlag propia = BANDERAS.get(clave);
        if (propia != null) return propia;
        try {
            return ItemFlag.valueOf(clave.startsWith("HIDE_") ? clave : "HIDE_" + clave);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Color color(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.startsWith("#")) v = v.substring(1);
        try {
            int rgb = Integer.parseInt(v, 16);
            return Color.fromRGB(rgb & 0xFFFFFF);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
