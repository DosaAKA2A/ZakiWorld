package net.ederus.edm.goditems;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Escribe los YAML de GodItems desde la interfaz.
 *
 * Todo lo que la interfaz cambia acaba aqui, y de aqui al disco. No se guarda
 * un modelo en memoria y se vuelca al salir: cada cambio se escribe y se
 * recarga en el acto. Con un modelo intermedio, un servidor que se cae a mitad
 * de una sesion de edicion se lleva el trabajo, y en un servidor abierto eso
 * pasa.
 */
public final class Ficha {

    private final GodItemsPlugin modulo;

    public Ficha(GodItemsPlugin modulo) {
        this.modulo = modulo;
    }

    /* ============================================================ importar */

    /**
     * Trae un item de MMOItems a GodItems: crea su YAML con el enlace puesto y
     * nada mas. NO se copian sus stats ni su nombre: los sigue teniendo
     * MMOItems y duplicarlos aqui seria crear la segunda copia que este diseño
     * evita a proposito.
     */
    public GodItem importar(String tipo, String id) {
        String t = tipo.toUpperCase(Locale.ROOT);
        String i = id.toUpperCase(Locale.ROOT);

        GodItem ya = porEnlace(t, i);
        if (ya != null) return ya;

        File carpeta = new File(this.modulo.getDataFolder(), "items");
        carpeta.mkdirs();
        File f = libre(carpeta, (t + "_" + i).toLowerCase(Locale.ROOT));

        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "Importado de MMOItems.",
                "",
                "El item lo sigue fabricando MMOItems: material, nombre, lore, stats,",
                "tier y set viven en plugins/MMOItems/item/" + t.toLowerCase(Locale.ROOT) + ".yml",
                "y se editan desde /gi o desde /mi indistintamente, porque es el mismo sitio.",
                "",
                "Aqui solo va el COMPORTAMIENTO."));
        yml.set("id", t + "_" + i);
        yml.set("enlace", t + "." + i);
        yml.set("lore-extra", List.of());
        guardar(yml, f);

        this.modulo.recargar();
        return porEnlace(t, i);
    }

    /**
     * Crea un GodItem NATIVO desde cero: uno que fabricamos nosotros, con su
     * apariencia propia. Es lo que hay que usar para cetros, llaves y
     * consumibles; para cualquier cosa con stats, tier o set hay que importar de
     * MMOItems, porque un nativo no puede pertenecer a un set.
     */
    public GodItem crearNativo(String idPedido, org.bukkit.Material material) {
        String id = GodItem.normalizar(idPedido).replaceAll("[^A-Z0-9_]", "_");
        if (id.isBlank()) return null;
        if (this.modulo.registro().porId(id) != null) return null;

        File carpeta = new File(this.modulo.getDataFolder(), "items");
        carpeta.mkdirs();
        File f = libre(carpeta, id.toLowerCase(Locale.ROOT));

        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "GodItem NATIVO: lo fabrica GodItems, no MMOItems.",
                "",
                "Un nativo NO puede pertenecer a un set de MMOItems: esa pertenencia",
                "se lee de las etiquetas que solo MMOItems pone. Si este item necesita",
                "set, tier o stats, borralo e importa el de MMOItems desde /gi."));
        yml.set("id", id);
        yml.set("item.material", material == null ? "STICK" : material.name());
        yml.set("item.nombre", "&f" + id.charAt(0) + id.substring(1).toLowerCase(Locale.ROOT).replace('_', ' '));
        yml.set("item.lore", List.of("&8GodItem nativo"));
        yml.set("item.brillo", false);
        guardar(yml, f);

        this.modulo.recargar();
        return this.modulo.registro().porId(id);
    }

    /**
     * Borra un GodItem. El YAML no se pierde: se renombra a `.borrado`, que es
     * lo unico que hace reversible un clic en un menu.
     */
    public boolean borrar(GodItem def) {
        File f = def.fichero();
        if (f == null || !f.isFile()) return false;
        File destino = new File(f.getParentFile(), f.getName() + ".borrado");
        if (destino.exists()) destino.delete();
        boolean ok = f.renameTo(destino);
        if (ok) this.modulo.recargar();
        return ok;
    }

    /** Copia entera con otro id. Util para variantes de un mismo cetro. */
    public GodItem duplicar(GodItem def, String idPedido) {
        File f = def.fichero();
        if (f == null || !f.isFile()) return null;
        String id = GodItem.normalizar(idPedido).replaceAll("[^A-Z0-9_]", "_");
        if (id.isBlank() || this.modulo.registro().porId(id) != null) return null;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        yml.set("id", id);
        /* El enlace NO se copia: dos GodItems apuntando al mismo item de
         * MMOItems es justo el choque que avisa el Cargador, y el segundo
         * ganaria en silencio. La copia nace nativa y ya se enlazara. */
        if (def.enlazado()) {
            yml.set("enlace", null);
            yml.set("item.material", "STICK");
            yml.set("item.nombre", "&f" + id.toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        File nuevo = libre(f.getParentFile(), id.toLowerCase(Locale.ROOT));
        if (!guardar(yml, nuevo)) return null;
        this.modulo.recargar();
        return this.modulo.registro().porId(id);
    }

    private GodItem porEnlace(String tipo, String id) {
        String existente = this.modulo.registro().porEnlace(tipo, id);
        return existente == null ? null : this.modulo.registro().porId(existente);
    }

    private static File libre(File carpeta, String base) {
        File f = new File(carpeta, base + ".yml");
        int n = 2;
        while (f.exists()) f = new File(carpeta, base + "_" + (n++) + ".yml");
        return f;
    }

    /* ============================================================ escribir */

    /** Cambia una clave suelta del YAML del item y recarga. */
    public boolean poner(GodItem def, String ruta, Object valor) {
        File f = def.fichero();
        if (f == null || !f.isFile()) {
            this.modulo.getLogger().warning("[GodItems] " + def.id() + " no tiene fichero; no se guarda.");
            return false;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        yml.set(ruta, valor);
        if (!guardar(yml, f)) return false;
        this.modulo.recargar();
        return true;
    }

    /** Anade un activador vacio si no lo tenia. */
    public boolean anadirActivador(GodItem def, Activador a) {
        if (def.bloque(a) != null) return false;
        File f = def.fichero();
        if (f == null) return false;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        String base = "activadores." + a.name();
        yml.set(base + ".acciones", List.of("MENSAJE @yo &7" + a.name() + " sin configurar."));
        if (!guardar(yml, f)) return false;
        this.modulo.recargar();
        return true;
    }

    public boolean borrarActivador(GodItem def, Activador a) {
        File f = def.fichero();
        if (f == null) return false;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        yml.set("activadores." + a.name(), null);
        if (!guardar(yml, f)) return false;
        this.modulo.recargar();
        return true;
    }

    /**
     * Cambia una linea de la lista de acciones o de condiciones.
     *
     * Solo toca las lineas de TEXTO. Los bloques anidados (`si:`, `repetir:`)
     * se dejan como estan: editarlos a ciegas por indice es la forma de
     * cargarse un bloque entero, y para eso esta el YAML.
     */
    public boolean lineaPonerOAnadir(GodItem def, Activador a, String lista, int indice, String linea) {
        File f = def.fichero();
        if (f == null) return false;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        String ruta = "activadores." + a.name() + "." + lista;
        List<Object> actual = new java.util.ArrayList<>(yml.getList(ruta, List.of()));
        if (indice < 0 || indice >= actual.size()) actual.add(linea);
        else actual.set(indice, linea);
        yml.set(ruta, actual);
        if (!guardar(yml, f)) return false;
        this.modulo.recargar();
        return true;
    }

    public boolean lineaBorrar(GodItem def, Activador a, String lista, int indice) {
        File f = def.fichero();
        if (f == null) return false;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        String ruta = "activadores." + a.name() + "." + lista;
        List<Object> actual = new java.util.ArrayList<>(yml.getList(ruta, List.of()));
        if (indice < 0 || indice >= actual.size()) return false;
        actual.remove(indice);
        yml.set(ruta, actual);
        if (!guardar(yml, f)) return false;
        this.modulo.recargar();
        return true;
    }

    /** Sube o baja una linea. `salto` es -1 o +1. */
    public boolean lineaMover(GodItem def, Activador a, String lista, int indice, int salto) {
        File f = def.fichero();
        if (f == null) return false;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        String ruta = "activadores." + a.name() + "." + lista;
        List<Object> actual = new java.util.ArrayList<>(yml.getList(ruta, List.of()));
        int destino = indice + salto;
        if (indice < 0 || indice >= actual.size() || destino < 0 || destino >= actual.size()) return false;
        Object x = actual.remove(indice);
        actual.add(destino, x);
        yml.set(ruta, actual);
        if (!guardar(yml, f)) return false;
        this.modulo.recargar();
        return true;
    }

    /** Las lineas crudas de una lista, para pintarlas en la interfaz. */
    public List<?> lineas(GodItem def, Activador a, String lista) {
        File f = def.fichero();
        if (f == null) return List.of();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        return yml.getList("activadores." + a.name() + "." + lista, List.of());
    }

    private boolean guardar(YamlConfiguration yml, File f) {
        try {
            yml.save(f);
            return true;
        } catch (IOException e) {
            this.modulo.getLogger().severe("[GodItems] No se pudo guardar " + f.getName() + ": " + e);
            return false;
        }
    }
}
