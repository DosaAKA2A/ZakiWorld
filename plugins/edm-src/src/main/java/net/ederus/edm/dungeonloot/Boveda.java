package net.ederus.edm.dungeonloot;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Una boveda plantada en el mundo: "aqui hay una caja de tal tipo".
 *
 * La misma caja puede estar puesta las veces que haga falta; cada copia es una
 * de estas. Se guardan por coordenada de bloque y no por UUID porque un bloque
 * no tiene UUID: la coordenada ES su identidad.
 */
public final class Boveda {

    private final String id;
    private final String cajaId;
    private final String worldName;
    private final int x, y, z;

    /** Cuantas veces se ha abierto. Solo para la ficha; no cambia nada. */
    private int aperturas;

    /** 1.78.1 · Quien la ha abierto ya, para las cajas de una apertura por jugador. */
    private final Set<UUID> abiertaPor = new LinkedHashSet<>();

    public Boveda(String id, String cajaId, String worldName, int x, int y, int z) {
        this.id = id;
        this.cajaId = cajaId;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public String id() {
        return id;
    }

    public String cajaId() {
        return cajaId;
    }

    public String worldName() {
        return worldName;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public int aperturas() {
        return aperturas;
    }

    public void aperturas(int n) {
        this.aperturas = Math.max(0, n);
    }

    public void sumarApertura() {
        this.aperturas++;
    }

    public boolean abiertaPor(UUID jugador) {
        return jugador != null && abiertaPor.contains(jugador);
    }

    public void marcarAbierta(UUID jugador) {
        if (jugador != null) abiertaPor.add(jugador);
    }

    public Set<UUID> abiertaPor() {
        return Collections.unmodifiableSet(abiertaPor);
    }

    /** La clave con la que se busca una boveda a partir de un bloque. */
    public static String clave(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    public String clave() {
        return clave(worldName, x, y, z);
    }

    /** null si ese mundo no esta cargado ahora mismo. */
    public Location sitio() {
        World w = Bukkit.getWorld(worldName);
        return w == null ? null : new Location(w, x + 0.5, y, z + 0.5);
    }

    /** El punto al que se lleva a quien pida viajar: delante del bloque, no dentro. */
    public Location destino() {
        Location l = sitio();
        return l == null ? null : l.clone().add(0, 1, 0);
    }
}
