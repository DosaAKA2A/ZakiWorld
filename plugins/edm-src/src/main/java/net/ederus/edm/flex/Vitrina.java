package net.ederus.edm.flex;

import java.util.UUID;

import org.bukkit.inventory.ItemStack;

/**
 * La vitrina de un jugador: hasta 27 objetos suyos puestos a la vista.
 *
 * Es una FOTO, no un baul. Al poner un objeto se guarda una copia y el original
 * se queda donde estaba, en el inventario de su dueño. De la vitrina no sale
 * nunca nada: ni el dueño puede sacar de aqui.
 *
 * Eso no es una limitacion, es el diseño: un baul de verdad seria duplicable con
 * un rollback del mundo —el objeto vuelve al cofre y ademas sigue guardado aqui—
 * y una foto no puede duplicar nada porque no devuelve objetos jamas.
 */
public final class Vitrina {

    public static final int CASILLAS = 27;

    private final UUID uuid;
    private String nombre;
    private final ItemStack[] objetos = new ItemStack[CASILLAS];
    private long actualizada;

    public Vitrina(UUID uuid, String nombre) {
        this.uuid = uuid;
        this.nombre = nombre;
    }

    public UUID uuid() {
        return uuid;
    }

    public String nombre() {
        return nombre;
    }

    public void nombre(String nombre) {
        if (nombre != null && !nombre.isBlank()) this.nombre = nombre;
    }

    public long actualizada() {
        return actualizada;
    }

    public void actualizada(long cuando) {
        this.actualizada = cuando;
    }

    public ItemStack objeto(int i) {
        return i < 0 || i >= CASILLAS ? null : objetos[i];
    }

    /** Guarda una COPIA. Quien la puso se queda con la suya. */
    public void poner(int i, ItemStack item) {
        if (i < 0 || i >= CASILLAS) return;
        objetos[i] = item == null || item.getType().isAir() ? null : item.clone();
        actualizada = System.currentTimeMillis();
    }

    public void quitar(int i) {
        poner(i, null);
    }

    public boolean vacia() {
        for (ItemStack it : objetos) {
            if (it != null) return false;
        }
        return true;
    }

    public int cuantos() {
        int n = 0;
        for (ItemStack it : objetos) {
            if (it != null) n++;
        }
        return n;
    }

    /** El primer hueco libre, o -1 si esta llena. */
    public int hueco() {
        for (int i = 0; i < CASILLAS; i++) {
            if (objetos[i] == null) return i;
        }
        return -1;
    }
}
