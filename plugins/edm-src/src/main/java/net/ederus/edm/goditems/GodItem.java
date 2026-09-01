package net.ederus.edm.goditems;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Un GodItem: la definicion que sale de un YAML de `plugins/EDM/goditems/items/`.
 *
 * Hay dos maneras de que un item sea un GodItem, y la diferencia es la decision
 * de diseño de todo el modulo:
 *
 *  - ENLAZADO (`enlace: KATANA.MENGUANTE_CARMESI`): el item lo fabrica MMOItems
 *    y nosotros NO le escribimos absolutamente nada encima. Lo reconocemos por
 *    las etiquetas que MMOItems ya mantiene. Es obligatorio para todo lo que
 *    lleve stats, tier o pertenezca a un set, porque el Item Revision System de
 *    MMOItems REGENERA las copias que ya estan en los inventarios cuando cambia
 *    la plantilla: cualquier marca nuestra estampada encima se perderia en la
 *    primera revision. Sin marca, somos inmunes.
 *
 *  - NATIVO: lo fabricamos nosotros con etiqueta propia. Para trastos sueltos
 *    (cetros, llaves, consumibles). Un nativo NO puede pertenecer a un set de
 *    MMOItems, porque esa pertenencia se lee de sus etiquetas y solo MMOItems
 *    las pone.
 *
 * La regla, en una linea: set / tier / stats / mejoras -> enlazado.
 * Artefacto suelto -> nativo.
 */
public final class GodItem {

    /** Un activador con todo lo que lleva colgando. */
    public record Bloque(Activador activador,
                         int cooldown,
                         String mensajeCooldown,
                         boolean cuentaAtras,
                         int cada,
                         double probabilidad,
                         int gastaUsos,
                         /* Solo para SET_COMPLETO: cuantas piezas hacen falta.
                          * 0 = las que MMOItems declare para ese set. */
                         int piezas,
                         List<Condicion.Prueba> condiciones,
                         List<Paso> pasos) { }

    private final java.io.File fichero;
    private final String id;
    private final String enlaceTipo;
    private final String enlaceId;
    private final Apariencia apariencia;
    private final int usos;
    private final int usosPorDia;
    private final boolean soloDueno;
    private final boolean conservarAlMorir;
    private final boolean exclusivo;
    private final List<String> mundos;
    private final List<String> loreExtra;
    private final Map<String, String> variables;
    private final Map<Activador, Bloque> bloques;

    public GodItem(java.io.File fichero, String id, String enlaceTipo, String enlaceId, Apariencia apariencia,
                   int usos, int usosPorDia, boolean soloDueno, boolean conservarAlMorir,
                   boolean exclusivo, List<String> mundos, List<String> loreExtra,
                   Map<String, String> variables, Map<Activador, Bloque> bloques) {
        this.fichero = fichero;
        this.id = id;
        this.enlaceTipo = enlaceTipo;
        this.enlaceId = enlaceId;
        this.apariencia = apariencia;
        this.usos = usos;
        this.usosPorDia = usosPorDia;
        this.soloDueno = soloDueno;
        this.conservarAlMorir = conservarAlMorir;
        this.exclusivo = exclusivo;
        this.mundos = mundos;
        this.loreExtra = loreExtra;
        this.variables = variables;
        this.bloques = bloques;
    }

    public String id() { return this.id; }

    /** El YAML del que salio. Lo necesita la interfaz para escribir encima. */
    public java.io.File fichero() { return this.fichero; }

    public boolean enlazado() { return this.enlaceTipo != null; }

    public String enlaceTipo() { return this.enlaceTipo; }

    public String enlaceId() { return this.enlaceId; }

    /** "KATANA.MENGUANTE_CARMESI", tal cual se escribe en el YAML. */
    public String enlace() {
        return this.enlaceTipo == null ? null : this.enlaceTipo + "." + this.enlaceId;
    }

    public Apariencia apariencia() { return this.apariencia; }

    /** -1 = sin limite. */
    public int usos() { return this.usos; }

    public int usosPorDia() { return this.usosPorDia; }

    public boolean soloDueno() { return this.soloDueno; }

    public boolean conservarAlMorir() { return this.conservarAlMorir; }

    /**
     * Silencia la habilidad que el item pueda tener por MythicLib / MMOItems en
     * ese mismo gesto. Existe porque una katana con habilidad de set Y un
     * CLIC_DERECHO nuestro disparan LAS DOS con el mismo clic.
     */
    public boolean exclusivo() { return this.exclusivo; }

    /** Vacia = vale en todos. */
    public List<String> mundos() { return this.mundos; }

    /**
     * Lineas que GodItems añade al lore de un item ENLAZADO, y que se estampan
     * en el ItemBuildEvent de MMOItems.
     *
     * Van aqui y no en el YAML de MMOItems porque describen lo que hace NUESTRO
     * comportamiento: si un dia se le quita el activador al item, la linea se va
     * con el en vez de quedarse mintiendo en la plantilla de MMOItems.
     */
    public List<String> loreExtra() { return this.loreExtra; }

    public Map<String, String> variablesIniciales() { return this.variables; }

    public Map<Activador, Bloque> bloques() { return this.bloques; }

    public Bloque bloque(Activador a) { return this.bloques.get(a); }

    public String nombreVisible() {
        if (this.apariencia != null && this.apariencia.nombre() != null) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(net.ederus.edm.comun.Estilo.legado(this.apariencia.nombre()));
        }
        return this.id;
    }

    public boolean valeEn(String mundo) {
        if (this.mundos.isEmpty()) return true;
        for (String m : this.mundos) {
            if (m.equalsIgnoreCase(mundo)) return true;
        }
        return false;
    }

    public static String normalizar(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
