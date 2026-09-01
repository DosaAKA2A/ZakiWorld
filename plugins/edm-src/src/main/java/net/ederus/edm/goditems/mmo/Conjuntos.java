package net.ederus.edm.goditems.mmo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import net.Indyuce.mmoitems.api.ItemSet;
import net.ederus.edm.goditems.Activador;
import net.ederus.edm.goditems.GodItem;
import net.ederus.edm.goditems.GodItemsPlugin;

/**
 * Quien lleva puesto que conjunto de MMOItems.
 *
 * El set no se define aqui: se lee de la etiqueta que MMOItems ya pone en cada
 * pieza. Contamos las piezas equipadas de cada set y avisamos cuando se
 * completa o se rompe.
 *
 * Se cuenta a mano en vez de preguntarselo a MMOItems porque su calculo de
 * bonus de set es interno y no expone "cuantas piezas lleva este jugador"; y
 * porque asi la cuenta es la misma con o sin el, que es lo que espera quien
 * escribe el YAML.
 */
public final class Conjuntos {

    /** Los huecos que cuentan como "llevar puesto". Las manos NO son armadura. */
    private static final EquipmentSlot[] HUECOS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
        EquipmentSlot.HAND, EquipmentSlot.OFF_HAND
    };

    private final GodItemsPlugin modulo;
    private final Puente puente;

    /** jugador -> sets que ya estaban completos en el ultimo repaso. */
    private final Map<UUID, Set<String>> completos = new HashMap<>();
    /** set -> cuantas piezas lo completan (cache; se vacia en cada recarga). */
    private final Map<String, Integer> tamano = new HashMap<>();

    public Conjuntos(GodItemsPlugin modulo, Puente puente) {
        this.modulo = modulo;
        this.puente = puente;
    }

    public void olvidar(UUID uuid) {
        this.completos.remove(uuid);
    }

    public void limpiar() {
        this.completos.clear();
        this.tamano.clear();
    }

    /** Cuantas piezas de ese set lleva puestas ahora mismo. */
    public int piezasPuestas(Player j, String set) {
        if (j == null || set == null) return 0;
        int n = 0;
        for (EquipmentSlot h : HUECOS) {
            ItemStack it = j.getInventory().getItem(h);
            if (it == null || it.getType().isAir()) continue;
            if (set.equalsIgnoreCase(this.puente.setDe(it))) n++;
        }
        return n;
    }

    /**
     * Cuantas piezas hacen falta para darlo por completo.
     *
     * Por omision, el numero de piezas mas alto para el que MMOItems tiene
     * bonus declarado en `item-sets.yml`. Si un item quiere otro numero, lo
     * pone con `piezas:` en su activador y manda ese.
     */
    public int tamanoDe(String set) {
        Integer cache = this.tamano.get(set);
        if (cache != null) return cache;
        int max = 0;
        ItemSet s = this.puente.set(set);
        if (s != null) {
            for (int i = 1; i <= 12; i++) {
                try {
                    if (s.getBonuses(i) != null) max = i;
                } catch (Throwable ignored) {
                }
            }
        }
        if (max == 0) max = 4;
        this.tamano.put(set, max);
        return max;
    }

    /**
     * Repasa a un jugador y dispara SET_COMPLETO / SET_ROTO segun lo que haya
     * cambiado desde el ultimo repaso.
     *
     * Se guarda el estado anterior a proposito: sin el, SET_COMPLETO saltaria
     * en cada repaso mientras el set siguiera puesto, que es justo lo que no se
     * quiere de un activador de "se ha completado".
     */
    public void repasar(Player j) {
        if (j == null || !j.isOnline()) return;

        /* set -> una pieza cualquiera de las que lleva, para tener contexto */
        Map<String, ItemStack> puestas = new LinkedHashMap<>();
        Map<String, Integer> cuenta = new LinkedHashMap<>();
        for (EquipmentSlot h : HUECOS) {
            ItemStack it = j.getInventory().getItem(h);
            if (it == null || it.getType().isAir()) continue;
            String set = this.puente.setDe(it);
            if (set == null) continue;
            puestas.putIfAbsent(set, it);
            cuenta.merge(set, 1, Integer::sum);
        }

        Set<String> antes = this.completos.getOrDefault(j.getUniqueId(), Set.of());
        Set<String> ahora = new HashSet<>();

        for (Map.Entry<String, Integer> e : cuenta.entrySet()) {
            String set = e.getKey();
            if (e.getValue() >= piezasQuePide(j, set, puestas.get(set))) ahora.add(set);
        }

        for (String set : ahora) {
            if (!antes.contains(set)) avisar(j, set, Activador.SET_COMPLETO);
        }
        for (String set : antes) {
            if (!ahora.contains(set)) avisar(j, set, Activador.SET_ROTO);
        }

        if (ahora.isEmpty()) this.completos.remove(j.getUniqueId());
        else this.completos.put(j.getUniqueId(), ahora);
    }

    /** El `piezas:` del item manda sobre el tamaño que declare MMOItems. */
    private int piezasQuePide(Player j, String set, ItemStack muestra) {
        GodItem def = this.modulo.identidad().definicionDe(muestra);
        if (def != null) {
            GodItem.Bloque b = def.bloque(Activador.SET_COMPLETO);
            if (b != null && b.piezas() > 0) return b.piezas();
        }
        return tamanoDe(set);
    }

    /**
     * Dispara el activador en TODAS las piezas del set que sean GodItems y lo
     * tengan. Que sean varias no es un error: una pieza puede poner el aura y
     * otra el aviso por chat, y asi no hay que concentrarlo todo en una.
     */
    private void avisar(Player j, String set, Activador act) {
        Set<String> yaHechos = new HashSet<>();
        for (EquipmentSlot h : HUECOS) {
            ItemStack it = j.getInventory().getItem(h);
            if (it == null || it.getType().isAir()) continue;
            if (!set.equalsIgnoreCase(this.puente.setDe(it))) continue;
            GodItem def = this.modulo.identidad().definicionDe(it);
            if (def == null || def.bloque(act) == null) continue;
            if (!yaHechos.add(def.id())) continue;
            this.modulo.disparar(j, it, def, act, null, h, null, null);
        }
    }
}
