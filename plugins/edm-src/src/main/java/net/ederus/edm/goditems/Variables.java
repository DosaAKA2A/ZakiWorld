package net.ederus.edm.goditems;

import java.util.Map;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Las variables de un GodItem: `%var_algo%` (del item) y `%varj_algo%` (del jugador).
 *
 * Las del ITEM solo se pueden guardar en un item NATIVO. En uno enlazado
 * escribiriamos en el NBT de MMOItems y su Item Revision System nos lo borraria
 * en cuanto se tocara la plantilla; ahi las variables del item se guardan en el
 * jugador con el id delante, y se dice en el log la primera vez para que quien
 * escribe el YAML sepa por que su variable "de item" es en realidad por jugador.
 */
public final class Variables {

    private final GodItemsPlugin modulo;
    private final NamespacedKey claveItem;
    private final NamespacedKey claveJugador;
    private final java.util.Set<String> yaAvisado = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public Variables(GodItemsPlugin modulo) {
        this.modulo = modulo;
        this.claveItem = modulo.identidad().claveVariables();
        this.claveJugador = new NamespacedKey(modulo, "varsj");
    }

    /* ------------------------------------------------------------- de jugador */

    public String deJugador(Player j, String nombre) {
        if (j == null) return null;
        return Mapita.leer(j, this.claveJugador).get(nombre);
    }

    public void ponerJugador(Player j, String nombre, String valor) {
        if (j == null) return;
        Map<String, String> m = Mapita.leer(j, this.claveJugador);
        if (valor == null) m.remove(nombre);
        else m.put(nombre, valor);
        Mapita.escribir(j.getPersistentDataContainer(), this.claveJugador, m);
    }

    /* ---------------------------------------------------------------- de item */

    public String deItem(Ctx ctx, String nombre) {
        if (ctx.definicion() != null && ctx.definicion().enlazado()) {
            return deJugador(ctx.jugador(), prefijo(ctx) + nombre);
        }
        ItemStack item = ctx.item();
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String v = Mapita.leer(meta.getPersistentDataContainer(), this.claveItem).get(nombre);
        if (v != null) return v;
        return ctx.definicion() == null ? null : ctx.definicion().variablesIniciales().get(nombre);
    }

    public void ponerItem(Ctx ctx, String nombre, String valor) {
        if (ctx.definicion() != null && ctx.definicion().enlazado()) {
            avisarUnaVez(ctx);
            ponerJugador(ctx.jugador(), prefijo(ctx) + nombre, valor);
            return;
        }
        ItemStack item = ctx.item();
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        Map<String, String> m = Mapita.leer(meta.getPersistentDataContainer(), this.claveItem);
        if (valor == null) m.remove(nombre);
        else m.put(nombre, valor);
        Mapita.escribir(meta.getPersistentDataContainer(), this.claveItem, m);
        item.setItemMeta(meta);
    }

    /** El valor actual mire donde mire, para las condiciones. */
    public String valor(Ctx ctx, String ambito, String nombre) {
        if (ambito != null && ambito.toLowerCase(java.util.Locale.ROOT).startsWith("jug")) {
            return deJugador(ctx.jugador(), nombre);
        }
        return deItem(ctx, nombre);
    }

    public void poner(Ctx ctx, String ambito, String nombre, String valor) {
        if (ambito != null && ambito.toLowerCase(java.util.Locale.ROOT).startsWith("jug")) {
            ponerJugador(ctx.jugador(), nombre, valor);
        } else {
            ponerItem(ctx, nombre, valor);
        }
    }

    private static String prefijo(Ctx ctx) {
        return "gi." + (ctx.definicion() == null ? "?" : ctx.definicion().id()) + ".";
    }

    private void avisarUnaVez(Ctx ctx) {
        String id = ctx.definicion().id();
        if (!this.yaAvisado.add(id)) return;
        this.modulo.getLogger().info("[GodItems] " + id + " es un item ENLAZADO: sus variables de item"
                + " se guardan en el jugador. En el item las borraria la primera revision de MMOItems.");
    }
}
