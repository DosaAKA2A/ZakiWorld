package net.ederus.edm.goditems;

import java.time.LocalDate;
import java.util.Map;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Los usos de un GodItem: los totales y los de cada dia.
 *
 * Donde se guarda cada cosa NO es un detalle de implementacion, es lo que hace
 * que funcione:
 *
 *  - Los usos de un item NATIVO van en el propio item. Es lo correcto: si le
 *    quedan dos cargas y se lo das a otro, el otro recibe dos cargas.
 *  - Los de un item ENLAZADO van en el PDC DEL JUGADOR. En el item los borraria
 *    la primera regeneracion del Item Revision System de MMOItems, y el jugador
 *    se encontraria las cargas llenas otra vez sin tocar nada.
 *  - Los usos POR DIA van siempre en el jugador, porque son suyos por
 *    definicion: dos copias del mismo item no dan dos cupos diarios.
 */
public final class Usos {

    private final GodItemsPlugin modulo;
    private final NamespacedKey claveItem;
    private final NamespacedKey claveJugador;
    private final NamespacedKey claveDia;

    public Usos(GodItemsPlugin modulo) {
        this.modulo = modulo;
        this.claveItem = new NamespacedKey(modulo, "usos");
        this.claveJugador = new NamespacedKey(modulo, "usosj");
        this.claveDia = new NamespacedKey(modulo, "dia");
    }

    /** -1 = sin limite. */
    public int restantes(Ctx ctx) {
        GodItem def = ctx.definicion();
        if (def == null || def.usos() < 0) return -1;
        Integer guardado = leer(ctx);
        return guardado == null ? def.usos() : guardado;
    }

    public int restantesHoy(Ctx ctx) {
        GodItem def = ctx.definicion();
        if (def == null || def.usosPorDia() < 0 || ctx.jugador() == null) return -1;
        return Math.max(0, def.usosPorDia() - gastadosHoy(ctx.jugador(), def.id()));
    }

    /**
     * Descuenta. Devuelve false si no quedaba y por tanto NO hay que ejecutar
     * nada: el aviso al jugador lo da quien llama, que es el que sabe si esto
     * fue un clic suyo o un tick de fondo.
     */
    public boolean gastar(Ctx ctx, int cuantos) {
        GodItem def = ctx.definicion();
        if (def == null || cuantos <= 0) return true;

        if (def.usosPorDia() >= 0 && ctx.jugador() != null) {
            int hoy = gastadosHoy(ctx.jugador(), def.id());
            if (hoy + cuantos > def.usosPorDia()) return false;
            ponerHoy(ctx.jugador(), def.id(), hoy + cuantos);
        }
        if (def.usos() >= 0) {
            int quedan = restantes(ctx);
            if (quedan < cuantos) return false;
            escribir(ctx, quedan - cuantos);
        }
        return true;
    }

    /** true si aun se puede usar, sin gastar nada. */
    public boolean hay(Ctx ctx, int cuantos) {
        GodItem def = ctx.definicion();
        if (def == null) return true;
        if (def.usos() >= 0 && restantes(ctx) < cuantos) return false;
        if (def.usosPorDia() >= 0 && ctx.jugador() != null
                && gastadosHoy(ctx.jugador(), def.id()) + cuantos > def.usosPorDia()) {
            return false;
        }
        return true;
    }

    public void reponer(Ctx ctx) {
        GodItem def = ctx.definicion();
        if (def == null) return;
        if (def.usos() >= 0) escribir(ctx, def.usos());
        if (def.usosPorDia() >= 0 && ctx.jugador() != null) ponerHoy(ctx.jugador(), def.id(), 0);
    }

    /* ------------------------------------------------------------ interiores */

    private Integer leer(Ctx ctx) {
        GodItem def = ctx.definicion();
        if (def.enlazado()) {
            if (ctx.jugador() == null) return null;
            String v = Mapita.leer(ctx.jugador(), this.claveJugador).get(def.id());
            return v == null ? null : (int) Numeros.decimal(v, def.usos());
        }
        ItemStack item = ctx.item();
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(this.claveItem, PersistentDataType.INTEGER);
    }

    private void escribir(Ctx ctx, int valor) {
        GodItem def = ctx.definicion();
        if (def.enlazado()) {
            if (ctx.jugador() == null) return;
            Map<String, String> m = Mapita.leer(ctx.jugador(), this.claveJugador);
            m.put(def.id(), String.valueOf(valor));
            Mapita.escribir(ctx.jugador().getPersistentDataContainer(), this.claveJugador, m);
            return;
        }
        ItemStack item = ctx.item();
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(this.claveItem, PersistentDataType.INTEGER, valor);
        item.setItemMeta(meta);
    }

    private int gastadosHoy(Player j, String id) {
        String v = Mapita.leer(j, this.claveDia).get(id);
        if (v == null) return 0;
        int dos = v.indexOf(':');
        if (dos < 0) return 0;
        long dia = (long) Numeros.decimal(v.substring(0, dos), -1);
        if (dia != LocalDate.now().toEpochDay()) return 0;
        return (int) Numeros.decimal(v.substring(dos + 1), 0);
    }

    private void ponerHoy(Player j, String id, int cuantos) {
        Map<String, String> m = Mapita.leer(j, this.claveDia);
        m.put(id, LocalDate.now().toEpochDay() + ":" + cuantos);
        Mapita.escribir(j.getPersistentDataContainer(), this.claveDia, m);
    }

    GodItemsPlugin modulo() {
        return this.modulo;
    }
}
