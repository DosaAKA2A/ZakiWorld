package net.ederus.edm.comun;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Lo que un modulo le dice al jugador por el chat, sacado de un yml.
 *
 * Con un prefijo delante siguiendo el convenio del servidor (EDERUS », LAG »,
 * MARKET »...) y con la posibilidad de saltarselo linea a linea, que hace falta
 * para los bloques de chat centrados.
 *
 * Vive aqui y no dentro de un modulo porque la tienda y el coinflip necesitan
 * exactamente lo mismo, y dos copias de esto acaban divergiendo en el detalle
 * tonto: una entiende los hex y la otra no.
 */
public class Textos {

    /** Marca una linea que debe salir SIN el prefijo. */
    private static final String SIN_PREFIJO = "{sin-prefijo}";

    private final Map<String, String> textos = new HashMap<>();
    private String prefijo = "";

    public void cargar(File fichero) {
        textos.clear();
        prefijo = "";
        if (!fichero.exists()) return;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichero);
        prefijo = yml.getString("prefijo", "");
        for (String k : yml.getKeys(false)) {
            if (yml.isString(k)) textos.put(k, yml.getString(k, ""));
        }
        alCargar(yml);
    }

    /** Gancho para el modulo que ademas necesita secciones enteras del fichero. */
    protected void alCargar(YamlConfiguration yml) { }

    /**
     * El texto con sus marcadores puestos, ya con prefijo.
     *
     * Devuelve Component y no String a proposito: serializarlo a texto plano se
     * come el prefijo, los hex y las negritas, que es justo lo que se configura.
     */
    public Component de(String clave, String respaldo, String... pares) {
        String s = textos.getOrDefault(clave, respaldo);
        if (s == null || s.isEmpty()) return Component.empty();
        for (int i = 0; i + 1 < pares.length; i += 2) {
            s = s.replace(pares[i], pares[i + 1] == null ? "" : pares[i + 1]);
        }
        boolean sinPrefijo = s.startsWith(SIN_PREFIJO);
        if (sinPrefijo) s = s.substring(SIN_PREFIJO.length());
        return Estilo.legado((sinPrefijo ? "" : prefijo) + s);
    }

    public void manda(CommandSender a, String clave, String respaldo, String... pares) {
        Component c = de(clave, respaldo, pares);
        if (!Component.empty().equals(c)) a.sendMessage(c);
    }

    /** Texto plano, para los sitios que aun devuelven String. */
    public String plano(String clave, String respaldo, String... pares) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(de(clave, respaldo, pares));
    }

    protected String prefijo() { return prefijo; }
}
