package net.ederus.edm.goditems;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;

/**
 * Un mapa de texto guardado en un PDC como una sola cadena `k=v;k2=v2`.
 *
 * Por que una cadena y no una clave por variable: en el PDC de un ITEM cada
 * clave suelta es NBT que se arrastra en cada copia, en cada stack y en cada
 * paquete que va al cliente. Con una sola clave, un item con seis variables
 * ocupa una linea; con seis claves, seis entradas por copia.
 *
 * El separador se escapa, porque una variable puede guardar un mensaje entero.
 */
public final class Mapita {

    private Mapita() { }

    public static Map<String, String> leer(PersistentDataHolder duenno, NamespacedKey clave) {
        if (duenno == null) return new LinkedHashMap<>();
        return descodificar(duenno.getPersistentDataContainer().get(clave, PersistentDataType.STRING));
    }

    public static Map<String, String> leer(PersistentDataContainer pdc, NamespacedKey clave) {
        if (pdc == null) return new LinkedHashMap<>();
        return descodificar(pdc.get(clave, PersistentDataType.STRING));
    }

    public static void escribir(PersistentDataContainer pdc, NamespacedKey clave, Map<String, String> datos) {
        if (pdc == null) return;
        if (datos == null || datos.isEmpty()) {
            pdc.remove(clave);
            return;
        }
        pdc.set(clave, PersistentDataType.STRING, codificar(datos));
    }

    public static Map<String, String> descodificar(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) return out;
        StringBuilder actual = new StringBuilder();
        String llave = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                actual.append(s.charAt(++i));
            } else if (c == '=' && llave == null) {
                llave = actual.toString();
                actual.setLength(0);
            } else if (c == ';') {
                if (llave != null) out.put(llave, actual.toString());
                llave = null;
                actual.setLength(0);
            } else {
                actual.append(c);
            }
        }
        if (llave != null) out.put(llave, actual.toString());
        return out;
    }

    public static String codificar(Map<String, String> datos) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : datos.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(escapar(e.getKey())).append('=').append(escapar(e.getValue()));
        }
        return sb.toString();
    }

    private static String escapar(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace(";", "\\;").replace("=", "\\=");
    }
}
