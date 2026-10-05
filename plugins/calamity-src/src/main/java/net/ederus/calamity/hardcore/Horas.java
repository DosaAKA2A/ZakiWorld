package net.ederus.calamity.hardcore;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M33 · Horas activas: el "playtime" de Calamity que premian los hitos (M16, PLAN sec. 6).
 *
 * El contador de siempre (tiempo.<uuid>, el del tag [INSOMNE] de hoy) suma cada segundo
 * dentro, AFK incluido: un jugador aparcado en un rincon con un autoclick llegaba a las 24 h
 * sin jugar. Este otro, horas-activas.<uuid> (segundos), va por minutos enteros y solo suma
 * el minuto si la Huella vio una tecla o actividad en ese minuto (Huella.activoEnUltimoMinuto).
 * No toca tiempo.<uuid> ni el playtime.yml del Survival.
 *
 * Sin Huella (modulo caido) se cuenta como hoy: mejor pagar de mas un rato que dejar a
 * todos sin horas por un fallo de otro modulo. Con horas.solo-activas: false, igual.
 *
 * El minuto se decide al cerrarse (a los 60 segundos contados dentro), no al empezar: la
 * pregunta a la Huella es "se ha movido en el ultimo minuto", que es justo el que se cierra.
 */
final class Horas implements Listener {

    private final Hardcore hc;
    /** Segundos contados dentro del minuto en curso. Se pierde al salir (menos de un minuto). */
    private final Map<UUID, Integer> segundos = new HashMap<>();
    /**
     * Copia de horas-activas para el placeholder, que puede llegar desde otro hilo y no
     * puede leer hardcore-datos.yml mientras el reloj escribe. Se rellena al arrancar y se
     * actualiza en cada escritura.
     */
    private final Map<UUID, Long> espejo = new ConcurrentHashMap<>();

    Horas(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        ConfigurationSection s = hc.datos().getConfigurationSection("horas-activas");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                try {
                    espejo.put(UUID.fromString(k), s.getLong(k, 0));
                } catch (IllegalArgumentException ignorado) {
                    // Una clave rara en el yml no es un jugador.
                }
            }
        }
        Subcomandos.staff().registrar("hours", "hours <player> [+h|-h]: sus horas activas; +h las sube para probar hitos",
                Subcomandos.PERMISO, this::comando,
                args -> switch (args.length) {
                    case 2 -> Entregas.nombresConectados();
                    case 3 -> List.of("+1", "+6", "+24");
                    default -> List.of();
                });
        PlaceholdersLethal.registrar("horas_activas", (jugador, resto) -> {
            if (jugador == null) return "";
            Long seg = espejo.get(jugador.getUniqueId());
            return String.valueOf(seg == null ? 0 : seg / 3600);
        });
        Autotest.registrar("horas", this::autotest);
    }

    /** Una vez por segundo por jugador que cuenta dentro, desde Hardcore.contarTiempo. */
    void segundo(Player p) {
        UUID u = p.getUniqueId();
        int n = segundos.merge(u, 1, Integer::sum);
        // Los contratos por tiempo (15 min dentro, al limite, sin frasco) cuelgan del mismo
        // reloj: asi no hace falta otro gancho en Hardcore.
        Contratos ct = hc.contratos();
        if (ct != null) hc.seguro("contratos", () -> ct.segundo(p));
        if (n < 60) return;
        segundos.put(u, 0);
        if (!cuentaMinuto(activo(p), hc.cfg().getBoolean("horas.solo-activas", true))) return;
        sumar(u, 60);
    }

    /** Si el minuto que se cierra suma: con solo-activas, solo si hubo actividad. */
    static boolean cuentaMinuto(boolean activo, boolean soloActivas) {
        return !soloActivas || activo;
    }

    private boolean activo(Player p) {
        Huella h = hc.huella();
        if (h == null) return true;
        return hc.valor("huella", () -> h.activoEnUltimoMinuto(p), true);
    }

    /** Suma (o resta) segundos y avisa a los hitos. A disco en el minuto, como tiempo.<uuid>. */
    void sumar(UUID u, long seg) {
        String ruta = "horas-activas." + u;
        long nuevo = Math.max(0, hc.datos().getLong(ruta, 0) + seg);
        hc.datos().set(ruta, nuevo);
        espejo.put(u, nuevo);
        hc.marcarSucio();
        Hitos h = hc.hitos();
        if (h != null) hc.seguro("hitos", () -> h.revisar(u, "horas-activas"));
    }

    double horasActivas(UUID jugador) {
        if (jugador == null) return 0;
        if (Bukkit.isPrimaryThread()) return hc.datos().getLong("horas-activas." + jugador, 0) / 3600.0;
        Long seg = espejo.get(jugador);
        return seg == null ? 0 : seg / 3600.0;
    }

    /** Segundos activos de todos (copia), para los tops de Rankings. */
    Map<UUID, Long> todos() {
        return new HashMap<>(espejo);
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        segundos.remove(e.getPlayer().getUniqueId());
    }

    void parar() {
        HandlerList.unregisterAll(this);
        segundos.clear();
    }

    // ------------------------------------------------------------------ comando

    private void comando(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(ComandoCalamity.mensaje("Uso: /calamity hours <player> [+h|-h]"));
            return;
        }
        OfflinePlayer o = Entregas.buscar(args[1]);
        if (o == null) {
            quien.sendMessage(ComandoCalamity.mensaje("No encuentro a ese jugador."));
            return;
        }
        UUID u = o.getUniqueId();
        String nombre = Entregas.nombre(o);
        if (args.length >= 3) {
            double h;
            try {
                h = Double.parseDouble(args[2].replace(',', '.'));
            } catch (NumberFormatException ex) {
                quien.sendMessage(ComandoCalamity.mensaje("Horas no válidas: " + args[2]));
                return;
            }
            long seg = Math.round(h * 3600);
            // La Bitacora primero: si un hito salta con esta suma, que se lea en orden.
            hc.plugin().bitacora().anotar("horas", nombre, (seg >= 0 ? "+" : "") + seg + " s",
                    "total " + Math.max(0, hc.datos().getLong("horas-activas." + u, 0) + seg) + " s", "admin");
            sumar(u, seg);
        }
        long seg = hc.datos().getLong("horas-activas." + u, 0);
        long tiempo = hc.datos().getLong("tiempo." + u, 0);
        quien.sendMessage(ComandoCalamity.mensaje(nombre + ": " + horasTexto(seg) + " activas, "
                + horasTexto(tiempo) + " en Calamity en total."));
    }

    static String horasTexto(long segundos) {
        return String.format(Locale.ROOT, "%.1f h", segundos / 3600.0).replace('.', ',');
    }

    // ------------------------------------------------------------------ autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        h.ok("minuto activo suma con solo-activas", cuentaMinuto(true, true));
        h.ok("minuto quieto no suma con solo-activas", !cuentaMinuto(false, true));
        h.ok("minuto quieto suma con solo-activas apagado", cuentaMinuto(false, false));
        h.igual("texto de horas", "24,0 h", horasTexto(24 * 3600));
        h.igual("placeholder sin jugador", "", PlaceholdersLethal.resolver(null, "horas_activas"));
        h.ok("/calamity hours registrado", Subcomandos.staff().nombres(null).contains("hours"));
        return h.lineas();
    }
}
