package net.ederus.edm.goditems.mmo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import net.ederus.edm.goditems.GodItemsPlugin;
import net.ederus.edm.goditems.Numeros;

/**
 * Leer y escribir las plantillas de MMOItems, que viven en
 * `plugins/MMOItems/item/<tipo>.yml`.
 *
 * ESTA es la clase que hace que las dos mitades no puedan discrepar. Cuando se
 * edita un stat desde la interfaz de GodItems no se guarda una copia nuestra:
 * se escribe en el fichero de MMOItems y se le pide que relea esa plantilla. No
 * hay nada que sincronizar porque el dato solo existe en un sitio; los dos
 * editores leen y escriben el mismo.
 *
 * Antes de cada escritura se deja un `.bak` con el contenido anterior. Es un
 * servidor con gente y un stat mal puesto se deshace copiando el fichero de al
 * lado, sin tocar backups del panel.
 */
public final class Plantillas {

    /** Que clase de valor es un campo, que decide como se pide y como se escribe. */
    public enum Clase { TEXTO, LISTA, NUMERO }

    /**
     * Un campo editable.
     *
     * `base` marca los stats que MMOItems escribe como `{base, scale, spread}`:
     * ahi hay que tocar la clave `base` de dentro y NO el nodo entero, o el
     * stat se queda sin su dispersion y todos los ejemplares salen clavados.
     */
    public record Campo(String id, String etiqueta, String ruta, Clase clase, boolean base) { }

    /**
     * Los campos que enseña la interfaz.
     *
     * Es una seleccion, no la lista completa de stats de MMOItems: su editor
     * tiene decenas y muchos son estructuras enteras (gemas, habilidades,
     * elementos). Los que no estan aqui se siguen tocando desde /mi, y como el
     * dato es el mismo fichero, los dos lados lo ven igual.
     */
    public static final List<Campo> CAMPOS = List.of(
            new Campo("nombre", "Nombre", "name", Clase.TEXTO, false),
            new Campo("lore", "Descripcion", "lore", Clase.LISTA, false),
            new Campo("material", "Material", "material", Clase.TEXTO, false),
            new Campo("tier", "Tier", "tier", Clase.TEXTO, false),
            new Campo("set", "Conjunto", "set", Clase.TEXTO, false),
            new Campo("durabilidad", "Durabilidad maxima", "max-durability", Clase.NUMERO, false),
            new Campo("dano", "Daño de ataque", "attack-damage", Clase.NUMERO, true),
            new Campo("velocidad", "Velocidad de ataque", "attack-speed", Clase.NUMERO, true),
            new Campo("critico", "Probabilidad critica", "critical-strike-chance", Clase.NUMERO, true),
            new Campo("poder-critico", "Poder critico", "critical-strike-power", Clase.NUMERO, true),
            new Campo("armadura", "Armadura", "armor", Clase.NUMERO, true),
            new Campo("vida", "Vida maxima", "max-health", Clase.NUMERO, true),
            new Campo("nivel", "Nivel requerido", "required-level", Clase.NUMERO, false));

    public static Campo campo(String id) {
        for (Campo c : CAMPOS) {
            if (c.id().equals(id)) return c;
        }
        return null;
    }

    private final GodItemsPlugin modulo;
    private final Puente puente;

    public Plantillas(GodItemsPlugin modulo, Puente puente) {
        this.modulo = modulo;
        this.puente = puente;
    }

    /* ================================================================ leer */

    /** El valor actual de un campo, ya en texto, o null si el item no lo tiene. */
    public String leer(String tipo, String id, Campo campo) {
        ConfigurationSection base = base(tipo, id);
        if (base == null) return null;
        if (campo.clase() == Clase.LISTA) {
            List<String> l = base.getStringList(campo.ruta());
            return l.isEmpty() ? null : String.join(" | ", l);
        }
        Object v = valorCrudo(base, campo);
        return v == null ? null : String.valueOf(v);
    }

    public List<String> leerLista(String tipo, String id, Campo campo) {
        ConfigurationSection base = base(tipo, id);
        return base == null ? List.of() : base.getStringList(campo.ruta());
    }

    private static Object valorCrudo(ConfigurationSection base, Campo campo) {
        if (campo.base() && base.isConfigurationSection(campo.ruta())) {
            return base.get(campo.ruta() + ".base");
        }
        return base.get(campo.ruta());
    }

    /** La seccion `<ID>.base` de la plantilla, que es donde vive todo. */
    public ConfigurationSection base(String tipo, String id) {
        File f = this.puente.ficheroDe(tipo);
        if (f == null || !f.isFile()) return null;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        return yml.getConfigurationSection(id.toUpperCase(Locale.ROOT) + ".base");
    }

    /* ============================================================ escribir */

    /**
     * Escribe un campo en el YAML de MMOItems y le pide que relea la plantilla.
     * Devuelve null si fue bien, o el motivo del fallo.
     */
    public String escribir(String tipo, String id, Campo campo, String valor) {
        File f = this.puente.ficheroDe(tipo);
        if (f == null || !f.isFile()) {
            return "No encuentro " + (f == null ? "el fichero" : f.getName()) + " de MMOItems.";
        }
        String clave = id.toUpperCase(Locale.ROOT);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        if (!yml.isConfigurationSection(clave + ".base")) {
            return "La plantilla " + clave + " no esta en " + f.getName() + ".";
        }
        String ruta = clave + ".base." + campo.ruta();

        Object nuevo;
        switch (campo.clase()) {
            case NUMERO -> {
                double d = Numeros.decimal(valor, Double.NaN);
                if (Double.isNaN(d)) return "'" + valor + "' no es un numero.";
                /* Si el stat es una estructura, se toca SOLO su `base`: el
                 * resto (scale, spread) es lo que hace que dos ejemplares del
                 * mismo item no salgan identicos. */
                if (campo.base() && yml.isConfigurationSection(ruta)) ruta = ruta + ".base";
                nuevo = d;
            }
            case LISTA -> {
                List<String> lineas = new ArrayList<>();
                for (String trozo : valor.split("\\|")) lineas.add(trozo.trim());
                nuevo = lineas;
            }
            default -> nuevo = valor;
        }

        String fallo = copiaDeSeguridad(f);
        if (fallo != null) return fallo;

        yml.set(ruta, nuevo);
        try {
            yml.save(f);
        } catch (IOException e) {
            return "No se pudo escribir " + f.getName() + ": " + e.getMessage();
        }

        if (!this.puente.recargarPlantilla(tipo, clave)) {
            return "Guardado, pero MMOItems no reelo la plantilla. Hara falta un /mi reload.";
        }
        this.modulo.getLogger().info("[GodItems] " + tipo + "." + clave + " -> "
                + campo.ruta() + " = " + valor + " (escrito en " + f.getName() + ")");
        return null;
    }

    /**
     * Deja el contenido ANTERIOR en un `.bak` al lado. Se sobrescribe en cada
     * escritura a proposito: lo util es poder deshacer el ultimo cambio, no
     * acumular veinte copias de un fichero de 50 KB.
     */
    private String copiaDeSeguridad(File f) {
        try {
            Files.copy(f.toPath(), new File(f.getParentFile(), f.getName() + ".bak").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            return null;
        } catch (IOException e) {
            return "No se pudo hacer la copia de seguridad de " + f.getName() + "; no se toca nada.";
        }
    }
}
