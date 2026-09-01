package net.ederus.edm.goditems;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** El catalogo de GodItems cargados, y los dos indices para encontrarlos. */
public final class Registro {

    private final Map<String, GodItem> porId = new LinkedHashMap<>();
    /** "KATANA.MENGUANTE_CARMESI" -> id del GodItem */
    private final Map<String, String> porEnlace = new LinkedHashMap<>();
    private boolean hayTicks;

    public void limpiar() {
        this.porId.clear();
        this.porEnlace.clear();
        this.hayTicks = false;
    }

    /** Devuelve el id que ya ocupaba el enlace, si lo habia (para avisar). */
    public String meter(GodItem item) {
        this.porId.put(item.id(), item);
        String chocaCon = null;
        if (item.enlazado()) {
            String clave = clave(item.enlaceTipo(), item.enlaceId());
            chocaCon = this.porEnlace.put(clave, item.id());
        }
        for (Activador a : item.bloques().keySet()) {
            if (a.esTick()) this.hayTicks = true;
        }
        return chocaCon;
    }

    public GodItem porId(String id) {
        return id == null ? null : this.porId.get(id.toUpperCase(Locale.ROOT));
    }

    public String porEnlace(String tipo, String id) {
        return this.porEnlace.get(clave(tipo, id));
    }

    public Collection<GodItem> todos() {
        return this.porId.values();
    }

    public java.util.Set<String> ids() {
        return this.porId.keySet();
    }

    public int cuantos() {
        return this.porId.size();
    }

    /** true si algun item usa EN_MANO, PUESTO o EN_INVENTARIO. */
    public boolean hayTicks() {
        return this.hayTicks;
    }

    private static String clave(String tipo, String id) {
        return (tipo == null ? "" : tipo.toUpperCase(Locale.ROOT)) + "."
                + (id == null ? "" : id.toUpperCase(Locale.ROOT));
    }
}
