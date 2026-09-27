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
 * Los subcomandos de /lw hardcore y de /calamity, por registro.
 *
 * Existe para poder trabajar en paralelo: cada modulo de Calamity registra los suyos en
 * su constructor y nadie mas tiene que tocar ComandoMundos (que lleva el switch de toda
 * la vida) ni ComandoCalamity. Los subcomandos que ya existian en ComandoMundos siguen
 * alli; lo que no encuentra en su switch lo pregunta aqui.
 *
 * Convencion de argumentos, la misma para accion y tab: args[0] es el nombre del
 * subcomando y lo demas va detras. "/lw hardcore dar esencia Dosa__ 10" llega como
 * [dar, esencia, Dosa__, 10]. Para tab, el ultimo argumento es el que se esta escribiendo;
 * lo que devuelva se filtra aqui por lo ya escrito.
 *
 * Todo en el hilo principal (comandos de Bukkit): no hace falta sincronizar.
 */
public final class Subcomandos {

    private record Entrada(String nombre, String ayuda, String permiso,
                           BiConsumer<CommandSender, String[]> accion,
                           Function<String[], List<String>> tab) {
    }

    private static final Subcomandos LW = new Subcomandos("/lw hardcore");
    private static final Subcomandos CALAMITY = new Subcomandos("/calamity");

    private final String raiz;
    private final Map<String, Entrada> entradas = new LinkedHashMap<>();

    private Subcomandos(String raiz) {
        this.raiz = raiz;
    }

    /** Los de /lw hardcore (staff; /lw ya pide ederus.mundos). */
    public static Subcomandos lw() {
        return LW;
    }

    /** Los de /calamity (jugadores). */
    public static Subcomandos calamity() {
        return CALAMITY;
    }

    /**
     * Registra un subcomando. Si ya habia uno con ese nombre, lo sustituye y lo avisa: dos
     * modulos con el mismo nombre es un fallo de reparto que tiene que verse.
     *
     * @param permiso null = sin permiso propio (vale el del comando)
     * @param tab     null = sin sugerencias
     */
    public void registrar(String nombre, String ayuda, String permiso,
                          BiConsumer<CommandSender, String[]> accion, Function<String[], List<String>> tab) {
        String clave = nombre.toLowerCase(Locale.ROOT);
        if (entradas.containsKey(clave)) {
            log(Level.WARNING, raiz + " " + clave + " se registra dos veces; se queda el ultimo.", null);
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
            quien.sendMessage(Component.text(raiz + " " + e.nombre() + " ha fallado: " + t, Paleta.AVISO));
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
