package net.ederus.calamity.hardcore;

import net.ederus.calamity.CalamityPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Los subcomandos de /calamity y lo que abren los NPCs, por registro.
 *
 * Existe para poder trabajar en paralelo: cada modulo de Calamity registra los suyos en su
 * constructor y nadie mas tiene que tocar ComandoRaiz (que lleva el switch de toda la vida).
 * Lo que ComandoRaiz no encuentra en su switch lo pregunta aqui.
 *
 * Dos registros:
 *  - staff(): los subcomandos de /calamity. Desde la 1.12 todo /calamity es de staff (op o el
 *    permiso calamity.admin) y va en ingles; todos piden PERMISO aunque el modulo diga otro.
 *  - jugador(): lo que un jugador consulta (su saldo, sus Ecos, el Cronista...). Ya no es un
 *    comando: lo abre un NPC con "calamity open <player> <id>" desde la consola (Citizens), y el
 *    staff, para probarlo, igual. La accion recibe al jugador como CommandSender.
 *
 * Convencion de argumentos, la misma para accion y tab: args[0] es el nombre del subcomando y lo
 * demas va detras. "/calamity give esencia Dosa__ 10" llega como [give, esencia, Dosa__, 10]. Para
 * tab, el ultimo argumento es el que se esta escribiendo; lo que devuelva se filtra aqui por lo ya
 * escrito.
 *
 * Todo en el hilo principal (comandos de Bukkit): no hace falta sincronizar.
 */
public final class Subcomandos {

    /**
     * El permiso de staff de /calamity (default: op en el plugin.yml; los grupos admin, owner y dev
     * lo llevan en LuckPerms). Sin el, /calamity no sale en el tab y contesta como un comando que no
     * existe.
     */
    public static final String PERMISO = "calamity.admin";

    /**
     * Los nodos que pedian los subcomandos antes de la 1.12. Un modulo que aun registre con uno de
     * ellos (una rama vieja) pide PERMISO igual: nadie entra por un nodo de jugador.
     */
    private static final java.util.Set<String> PERMISOS_VIEJOS = java.util.Set.of("ederus.mundos", "lethalworld.calamity");

    private record Entrada(String nombre, String ayuda, String permiso,
                           BiConsumer<CommandSender, String[]> accion,
                           Function<String[], List<String>> tab) {
    }

    // La raiz solo sale en los avisos ("/calamity give ha fallado: ...").
    private static final Subcomandos STAFF = new Subcomandos("/calamity", true);
    private static final Subcomandos JUGADOR = new Subcomandos("/calamity open <player>", false);

    private final String raiz;
    /** true: todo pide PERMISO (los de /calamity); false: lo que abren los NPCs, sin permiso propio. */
    private final boolean deStaff;
    private final Map<String, Entrada> entradas = new LinkedHashMap<>();

    private Subcomandos(String raiz, boolean deStaff) {
        this.raiz = raiz;
        this.deStaff = deStaff;
    }

    /** Los subcomandos de /calamity (staff). */
    public static Subcomandos staff() {
        return STAFF;
    }

    /** Lo que abre un NPC para un jugador: "calamity open <player> <id>". */
    public static Subcomandos jugador() {
        return JUGADOR;
    }

    /** @deprecated desde la 1.12 es staff(): se queda para las ramas que aun registran con lw(). */
    @Deprecated
    public static Subcomandos lw() {
        return STAFF;
    }

    /** @deprecated desde la 1.12 es jugador(): /calamity ya no es de jugadores. */
    @Deprecated
    public static Subcomandos calamity() {
        return JUGADOR;
    }

    /**
     * Registra un subcomando. Si ya habia uno con ese nombre, lo sustituye y lo avisa: dos
     * modulos con el mismo nombre es un fallo de reparto que tiene que verse.
     *
     * @param permiso en staff(), null o un nodo viejo = PERMISO; en jugador() no se mira (abre la consola)
     * @param tab     null = sin sugerencias
     */
    public void registrar(String nombre, String ayuda, String permiso,
                          BiConsumer<CommandSender, String[]> accion, Function<String[], List<String>> tab) {
        String clave = nombre.toLowerCase(Locale.ROOT);
        if (!deStaff) permiso = null;
        else if (permiso == null || PERMISOS_VIEJOS.contains(permiso)) permiso = PERMISO;
        if (entradas.containsKey(clave)) {
            log(Level.WARNING, raiz + " " + clave + " se registra dos veces; se queda el último.", null);
        }
        entradas.put(clave, new Entrada(clave, ayuda == null ? "" : ayuda, permiso, accion, tab));
    }

    /** Ejecuta si hay un subcomando args[0]. True si lo atendio (aunque fuera para negar el permiso). */
    public boolean ejecutar(CommandSender quien, String[] args) {
        if (args == null || args.length == 0) return false;
        Entrada e = entradas.get(args[0].toLowerCase(Locale.ROOT));
        if (e == null) return false;
        if (e.permiso() != null && !quien.hasPermission(e.permiso())) {
            quien.sendMessage(Component.text("No tienes permiso para eso.", Paleta.AVISO));
            return true;
        }
        try {
            e.accion().accept(quien, args);
        } catch (Throwable t) {
            // Un modulo que revienta no puede dejar el comando sin respuesta: se dice y se
            // deja la traza en consola para el que lo tenga que arreglar.
            // Al jugador (lo de los NPCs) no se le ensena ningun comando ni la excepcion.
            quien.sendMessage(Component.text(deStaff ? raiz + " " + e.nombre() + " ha fallado: " + t
                    : "Algo ha fallado. Avisa al staff.", Paleta.AVISO));
            log(Level.WARNING, raiz + " " + e.nombre() + " ha fallado", t);
        }
        return true;
    }

    /** Los nombres que puede usar quien pregunta, en orden de registro. */
    public List<String> nombres(CommandSender quien) {
        List<String> out = new ArrayList<>();
        for (Entrada e : entradas.values()) {
            if (e.permiso() == null || quien == null || quien.hasPermission(e.permiso())) out.add(e.nombre());
        }
        return out;
    }

    /** Sugerencias para args (args[0] = subcomando), ya filtradas por lo escrito. */
    public List<String> tab(CommandSender quien, String[] args) {
        if (args == null || args.length < 2) return List.of();
        Entrada e = entradas.get(args[0].toLowerCase(Locale.ROOT));
        if (e == null || e.tab() == null) return List.of();
        if (e.permiso() != null && quien != null && !quien.hasPermission(e.permiso())) return List.of();
        List<String> op;
        try {
            op = e.tab().apply(args);
        } catch (Throwable t) {
            return List.of();
        }
        if (op == null) return List.of();
        String ultimo = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : op) if (s != null && s.toLowerCase(Locale.ROOT).startsWith(ultimo)) out.add(s);
        return out;
    }

    /** Pares [nombre, ayuda] de lo que puede usar quien pregunta, para la ayuda del comando. */
    public List<String[]> ayuda(CommandSender quien) {
        List<String[]> out = new ArrayList<>();
        for (Entrada e : entradas.values()) {
            if (e.permiso() == null || quien == null || quien.hasPermission(e.permiso())) {
                out.add(new String[]{e.nombre(), e.ayuda()});
            }
        }
        return out;
    }

    /** El permiso que pide ese subcomando (null si no lo hay o no pide ninguno). Para el autotest. */
    public String permiso(String nombre) {
        Entrada e = nombre == null ? null : entradas.get(nombre.toLowerCase(Locale.ROOT));
        return e == null ? null : e.permiso();
    }

    /** El texto de ayuda de ese subcomando ("" si no hay). Para el autotest. */
    public String ayuda(String nombre) {
        Entrada e = nombre == null ? null : entradas.get(nombre.toLowerCase(Locale.ROOT));
        return e == null ? "" : e.ayuda();
    }

    /** Al parar Calamity: los modulos nuevos se registraran otra vez al arrancar. */
    public void vaciar() {
        entradas.clear();
    }

    private static void log(Level nivel, String texto, Throwable t) {
        try {
            JavaPlugin.getPlugin(CalamityPlugin.class).getLogger().log(nivel, "[Calamity] " + texto, t);
        } catch (Throwable ignorado) {
            // Sin plugin (pruebas fuera del servidor) no hay donde escribir.
        }
    }
}
