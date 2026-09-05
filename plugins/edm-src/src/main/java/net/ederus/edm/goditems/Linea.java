package net.ederus.edm.goditems;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * El borrador de UNA linea mientras se edita en el menu.
 *
 * El YAML sigue guardando lineas de texto (`DANO @golpeado cantidad:8`): eso no
 * cambia, y a proposito, porque es lo que permite editar un item a mano por el
 * panel sin abrir el juego. Lo que hace esta clase es el puente entre esa linea
 * y las casillas del editor: la LEE para saber que poner en cada casilla y la
 * VUELVE A ESCRIBIR cuando se cambia algo.
 *
 * Dos reglas al escribir, y las dos tienen motivo:
 *
 *  - **Una clave que vale lo mismo que su valor por defecto NO se escribe.** Si
 *    se escribieran todas, cada linea saldria con quince claves y el YAML
 *    quedaria ilegible para quien lo edite a mano.
 *  - **El operador `igual` de las condiciones tampoco se escribe.** Es el que
 *    `Condiciones` supone cuando no hay ninguno, y ademas escribirlo rompe
 *    `HORA noche`: esa condicion mira la PRIMERA palabra, y con un `igual`
 *    delante deja de verla.
 */
public final class Linea {

    private String nombre;
    private String selector;
    private final Map<String, String> claves = new LinkedHashMap<>();
    private String texto = "";

    /* solo condiciones */
    private boolean negada;
    private String mensaje = "";
    private String sujeto = "";
    private String operador = "igual";
    private String valor = "";

    private Linea(String nombre) {
        this.nombre = nombre;
    }

    /* ============================================================= acciones */

    /** Una linea de accion nueva, con los valores por defecto de su ficha. */
    public static Linea nuevaAccion(Catalogo.Accion a) {
        Linea l = new Linea(a.nombre());
        l.selector = a.selectorPorDefecto();
        return l;
    }

    /** Lee una linea de accion que ya existe. */
    public static Linea deAccion(Catalogo.Accion a, String cruda) {
        Linea l = new Linea(a.nombre());
        Args args = a.llevaTexto() ? Args.deTextoLibre(cruda) : Args.de(cruda);
        l.selector = args.selector() != null ? args.selector() : a.selectorPorDefecto();
        l.claves.putAll(args.claves());
        l.texto = args.texto();
        return l;
    }

    public String escribirAccion(Catalogo.Accion a) {
        StringBuilder sb = new StringBuilder(a.nombre());
        if (a.llevaSelector() && this.selector != null && !this.selector.isBlank()) {
            sb.append(' ').append(this.selector);
        }
        for (Catalogo.Param p : a.params()) {
            String v = this.claves.get(p.clave());
            if (sobra(v, p.pordefecto())) continue;
            sb.append(' ').append(p.clave()).append(':').append(entrecomillar(v));
        }
        if (a.llevaTexto() && !this.texto.isBlank()) sb.append(' ').append(this.texto.trim());
        return sb.toString();
    }

    /* =========================================================== condiciones */

    public static Linea nuevaCondicion(Catalogo.Cond c) {
        Linea l = new Linea(c.nombre());
        if (c.llevaSelector()) l.selector = null;
        return l;
    }

    /** Lee una condicion escrita: `!VIDA @yo menor 50% | &cTe falta vida`. */
    public static Linea deCondicion(Catalogo.Cond c, String cruda) {
        Linea l = new Linea(c.nombre());
        String cuerpo = cruda == null ? "" : cruda.trim();
        int barra = cuerpo.indexOf('|');
        if (barra >= 0) {
            l.mensaje = cuerpo.substring(barra + 1).trim();
            cuerpo = cuerpo.substring(0, barra).trim();
        }
        while (cuerpo.startsWith("!")) {
            l.negada = !l.negada;
            cuerpo = cuerpo.substring(1).trim();
        }
        Args args = Args.de(cuerpo);
        l.selector = args.selector();
        l.claves.putAll(args.claves());

        List<String> palabras = args.palabras();
        switch (c.forma()) {
            case BANDERA -> { }
            case VALOR -> l.valor = String.join(" ", palabras);
            case COMPARACION -> {
                int op = -1;
                for (int i = 0; i < palabras.size(); i++) {
                    if (normalizarOperador(palabras.get(i)) != null) { op = i; break; }
                }
                if (op >= 0) {
                    l.operador = normalizarOperador(palabras.get(op));
                    l.sujeto = String.join(" ", palabras.subList(0, op));
                    l.valor = String.join(" ", palabras.subList(op + 1, palabras.size()));
                } else if (!palabras.isEmpty()) {
                    l.operador = "igual";
                    l.valor = palabras.get(palabras.size() - 1);
                    l.sujeto = String.join(" ", palabras.subList(0, palabras.size() - 1));
                }
            }
            default -> { }
        }
        return l;
    }

    public String escribirCondicion(Catalogo.Cond c) {
        StringBuilder sb = new StringBuilder();
        if (this.negada) sb.append('!');
        sb.append(c.nombre());
        if (c.llevaSelector() && this.selector != null && !this.selector.isBlank()) {
            sb.append(' ').append(this.selector);
        }
        for (Catalogo.Param p : c.params()) {
            String v = this.claves.get(p.clave());
            if (sobra(v, p.pordefecto())) continue;
            sb.append(' ').append(p.clave()).append(':').append(entrecomillar(v));
        }
        switch (c.forma()) {
            case BANDERA -> { }
            case VALOR -> {
                if (!this.valor.isBlank()) sb.append(' ').append(this.valor.trim());
            }
            case COMPARACION -> {
                if (Catalogo.llevaSujeto(c) && !this.sujeto.isBlank()) {
                    sb.append(' ').append(this.sujeto.trim());
                }
                /* `igual` es el que se supone: escribirlo solo estorba, y en
                 * HORA ademas tapa el `dia` / `noche`. */
                if (!this.operador.equals("igual")) sb.append(' ').append(this.operador);
                if (!this.valor.isBlank()) sb.append(' ').append(this.valor.trim());
            }
            default -> { }
        }
        if (!this.mensaje.isBlank()) sb.append(" | ").append(this.mensaje.trim());
        return sb.toString();
    }

    /* ============================================================== lectura */

    public String nombre() { return this.nombre; }

    public String selector() { return this.selector; }

    public void selector(String s) { this.selector = s; }

    /** El valor puesto, o el de la ficha si no se ha tocado. */
    public String valorDe(Catalogo.Param p) {
        String v = this.claves.get(p.clave());
        return v == null ? p.pordefecto() : v;
    }

    /** Si esa clave se ha tocado a mano (para pintarla distinta). */
    public boolean puesto(Catalogo.Param p) {
        String v = this.claves.get(p.clave());
        return v != null && !v.equals(p.pordefecto());
    }

    public void poner(String clave, String valor) {
        if (valor == null || valor.isBlank()) this.claves.remove(clave);
        else this.claves.put(clave, valor.trim());
    }

    public String texto() { return this.texto; }

    public void texto(String t) { this.texto = t == null ? "" : t; }

    public boolean negada() { return this.negada; }

    public void girarNegada() { this.negada = !this.negada; }

    public String mensaje() { return this.mensaje; }

    public void mensaje(String m) { this.mensaje = m == null ? "" : m; }

    public String sujeto() { return this.sujeto; }

    public void sujeto(String s) { this.sujeto = s == null ? "" : s; }

    public String operador() { return this.operador; }

    public void girarOperador() {
        int i = Catalogo.OPERADORES.indexOf(this.operador);
        this.operador = Catalogo.OPERADORES.get((i + 1) % Catalogo.OPERADORES.size());
    }

    public String valor() { return this.valor; }

    public void valor(String v) { this.valor = v == null ? "" : v; }

    /** Gira el `@objetivo` a la siguiente opcion de la lista. */
    public void girarSelector() {
        int i = Catalogo.SELECTORES.indexOf(this.selector);
        this.selector = Catalogo.SELECTORES.get((i + 1) % Catalogo.SELECTORES.size());
    }

    /* =============================================================== ayudas */

    /** El nombre de la accion o condicion de una linea cruda, sin mas. */
    public static String nombreDe(String cruda) {
        if (cruda == null) return "";
        String s = cruda.trim();
        while (s.startsWith("!")) s = s.substring(1).trim();
        int esp = s.indexOf(' ');
        if (esp > 0) s = s.substring(0, esp);
        return s.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static boolean sobra(String valor, String pordefecto) {
        if (valor == null || valor.isBlank()) return true;
        return valor.trim().equalsIgnoreCase(pordefecto == null ? "" : pordefecto.trim());
    }

    /**
     * Entrecomilla solo lo que lo necesita. `Args` trocea respetando comillas,
     * asi que un valor con espacios sin comillas se partiria en dos.
     */
    private static String entrecomillar(String v) {
        String s = v.trim();
        boolean hayEspacio = s.indexOf(' ') >= 0;
        if (!hayEspacio) return s;
        return s.indexOf('\'') >= 0 ? '"' + s + '"' : "'" + s + "'";
    }

    /** Traduce cualquier forma de operador a la palabra canonica, o null. */
    private static String normalizarOperador(String p) {
        return switch (p.trim().toLowerCase(Locale.ROOT)) {
            case "<", "menor", "menos" -> "menor";
            case ">", "mayor", "mas", "más" -> "mayor";
            case "<=", "menor_igual" -> "menor_igual";
            case ">=", "mayor_igual" -> "mayor_igual";
            case "=", "==", "igual", "es" -> "igual";
            case "!=", "distinto", "no" -> "distinto";
            default -> null;
        };
    }
}
