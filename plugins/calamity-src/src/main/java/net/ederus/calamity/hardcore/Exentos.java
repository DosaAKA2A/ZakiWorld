package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Exenciones de Calamity, puestas a mano y una a una (1.1.1).
 *
 *  - parca: la Huella no cuenta y la PARCA no viene (staff que vigila en vanish).
 *  - aduana: se salta la comparacion de huella de IP de la Aduana y de la Encuesta
 *    (hermanos que juegan desde la misma casa).
 *
 * Antes eran los permisos lethalworld.parca.exento y lethalworld.aduana.exento, y con el
 * comodin "*" de LuckPerms todo el staff los heredaba sin que nadie lo decidiera: inmunes a
 * la PARCA y sin la comparacion de IP (la PARCA de la primera prueba no vino a por nadie del
 * staff por eso). Ahora es un interruptor por jugador en hardcore-datos.yml, en
 * exentos.<uuid>.{nombre, parca, aduana}, que se pone con /lw hardcore exento y queda en la
 * Bitacora. Ningun permiso lo concede.
 *
 * Se lee en cada consulta (una lectura del YAML en memoria): nada que cachear ni que limpiar.
 */
final class Exentos {

    static final String PARCA = "parca";
    static final String ADUANA = "aduana";
    private static final String RAIZ = "exentos";

    private final Hardcore hc;

    Exentos(Hardcore hc) {
        this.hc = hc;
        Autotest.registrar("exentos", Exentos::autotest);
        Subcomandos.lw().registrar("exento",
                "exento [<jugador> <parca|aduana|todo> <on|off>]: exenciones a mano (sin argumentos, la lista)",
                "ederus.mundos", this::comando, this::tab);
    }

    // ------------------------------------------------------------------ nucleo

    /** Si ese jugador esta exento de "que" (parca o aduana). Sin datos, nadie lo esta. */
    static boolean es(ConfigurationSection datos, UUID id, String que) {
        return datos != null && id != null && datos.getBoolean(RAIZ + "." + id + "." + que, false);
    }

    /**
     * Pone o quita una exencion ("todo" = las dos). Quien se queda sin ninguna desaparece de
     * la lista: el fichero no acumula entradas en false.
     */
    static void poner(ConfigurationSection datos, UUID id, String nombre, String que, boolean on) {
        String base = RAIZ + "." + id;
        for (String q : cuales(que)) datos.set(base + "." + q, on ? true : null);
        if (!datos.getBoolean(base + "." + PARCA, false) && !datos.getBoolean(base + "." + ADUANA, false)) {
            datos.set(base, null);
            ConfigurationSection raiz = datos.getConfigurationSection(RAIZ);
            if (raiz != null && raiz.getKeys(false).isEmpty()) datos.set(RAIZ, null);
        } else if (nombre != null) {
            datos.set(base + ".nombre", nombre);
        }
    }

    /** "parca", "aduana" o "todo" -> las claves que toca; vacio si no es ninguna. */
    static List<String> cuales(String que) {
        return switch (que == null ? "" : que.toLowerCase(Locale.ROOT)) {
            case PARCA -> List.of(PARCA);
            case ADUANA -> List.of(ADUANA);
            case "todo", "todos", "ambas" -> List.of(PARCA, ADUANA);
            default -> List.of();
        };
    }

    /** Cada exento con lo suyo: "Nombre: parca, aduana". */
    static List<String> lista(ConfigurationSection datos) {
        List<String> out = new ArrayList<>();
        ConfigurationSection s = datos == null ? null : datos.getConfigurationSection(RAIZ);
        if (s == null) return out;
        for (String id : s.getKeys(false)) {
            List<String> de = new ArrayList<>();
            if (s.getBoolean(id + "." + PARCA, false)) de.add(PARCA);
            if (s.getBoolean(id + "." + ADUANA, false)) de.add(ADUANA);
            if (de.isEmpty()) continue;
            out.add(s.getString(id + ".nombre", id) + ": " + String.join(", ", de));
        }
        return out;
    }

    // ---------------------------------------------------------------- consultas

    /** La Huella no cuenta y la PARCA no viene. */
    boolean parca(UUID id) {
        return es(hc.datos(), id, PARCA);
    }

    /** Se salta la comparacion de huella de IP (Aduana, Encuesta). */
    boolean aduana(UUID id) {
        return es(hc.datos(), id, ADUANA);
    }

    // ------------------------------------------------------------------ comando

    /**
     * /lw hardcore exento                                   la lista
     * /lw hardcore exento <jugador> <parca|aduana|todo> <on|off>
     */
    private void comando(CommandSender quien, String[] args) {
        if (args.length == 1) {
            List<String> l = lista(hc.datos());
            if (l.isEmpty()) {
                quien.sendMessage(Paleta.mensaje("Nadie está exento. La Parca viene a por todos."));
                return;
            }
            quien.sendMessage(Paleta.mensaje(Component.text("Exentos (").append(Paleta.cifra(l.size()))
                    .append(Component.text("):"))));
            for (String linea : l) quien.sendMessage(Component.text("  " + linea, Paleta.DETALLE));
            return;
        }
        if (args.length < 4) {
            quien.sendMessage(Component.text("Uso: /calamidad exento [<jugador> <parca|aduana|todo> <on|off>]", Paleta.AVISO));
            return;
        }
        OfflinePlayer o = Entregas.buscar(args[1]);
        if (o == null) {
            quien.sendMessage(Component.text("No encuentro a ese jugador.", Paleta.AVISO));
            return;
        }
        String que = args[2].toLowerCase(Locale.ROOT);
        if (cuales(que).isEmpty()) {
            quien.sendMessage(Component.text("Tiene que ser parca, aduana o todo.", Paleta.AVISO));
            return;
        }
        String interruptor = args[3].toLowerCase(Locale.ROOT);
        boolean on;
        if (interruptor.equals("on") || interruptor.equals("si") || interruptor.equals("true")) on = true;
        else if (interruptor.equals("off") || interruptor.equals("no") || interruptor.equals("false")) on = false;
        else {
            quien.sendMessage(Component.text("Tiene que ser on u off.", Paleta.AVISO));
            return;
        }
        String nombre = o.getName() == null ? args[1] : o.getName();
        poner(hc.datos(), o.getUniqueId(), nombre, que, on);
        hc.guardarYa();
        hc.plugin().bitacora().anotar("exento", on ? "pone" : "quita", nombre, que, quien.getName());
        quien.sendMessage(Paleta.mensaje(Component.text(nombre, Paleta.DETALLE)
                .append(Component.text(on ? " queda exento de " : " deja de estar exento de "))
                .append(Component.text(que.equals("todo") ? "la Parca y la Aduana" : que.equals(PARCA) ? "la Parca" : "la Aduana",
                        Paleta.CIFRA))
                .append(Component.text("."))));
        Player p = o.getPlayer();
        if (on && p != null && cuales(que).contains(PARCA) && hc.huella() != null) {
            // Empieza de cero: si vuelve a contar, que no herede los avisos de antes.
            hc.seguro("huella", () -> hc.huella().reiniciar(p));
        }
    }

    private List<String> tab(String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 2) {
            for (Player p : hc.plugin().getServer().getOnlinePlayers()) out.add(p.getName());
        } else if (args.length == 3) {
            out.addAll(List.of(PARCA, ADUANA, "todo"));
        } else if (args.length == 4) {
            out.addAll(List.of("on", "off"));
        }
        return out;
    }

    // ------------------------------------------------------------------ autotest

    /** Sobre un YAML en memoria: nunca toca hardcore-datos.yml. */
    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        YamlConfiguration d = new YamlConfiguration();
        UUID a = Autotest.sintetico(1), b = Autotest.sintetico(2);
        h.ok("sin datos nadie esta exento", !es(d, a, PARCA) && !es(d, a, ADUANA));
        poner(d, a, "Staff", PARCA, true);
        h.ok("parca on -> exento de la parca, no de la aduana", es(d, a, PARCA) && !es(d, a, ADUANA));
        h.ok("los demas siguen sin estarlo", !es(d, b, PARCA));
        poner(d, a, "Staff", "todo", true);
        h.igual("todo on -> la lista lo dice", List.of("Staff: parca, aduana"), lista(d));
        poner(d, a, "Staff", PARCA, false);
        h.ok("parca off -> sigue de la aduana", !es(d, a, PARCA) && es(d, a, ADUANA));
        poner(d, a, "Staff", ADUANA, false);
        h.ok("sin ninguna -> fuera del fichero", !d.isSet("exentos." + a) && !d.isSet("exentos"));
        h.ok("una palabra que no es ninguna no toca nada", cuales("permiso").isEmpty());
        return h.lineas();
    }
}
