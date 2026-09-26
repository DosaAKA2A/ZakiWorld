package net.ederus.lethalworld.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * M35 · Monedero: saldo y cobro de MobCoins para la Forja (DIS M35).
 *
 * EDM solo sabe DAR MobCoins (su pago es "mobcoins give") y UltimateMobCoins no tiene
 * API: cobrar va por config. monedero.saldo-placeholder se lee por PlaceholderAPI y
 * monedero.cobrar es un comando de consola con %jugador% y %n%. Los dos se rellenan en la
 * Fase 0 de PLAN sec. 9 leyendo produccion; mientras cualquiera de los dos este vacio,
 * disponible() es false y el Altar pinta los trueques con MC en gris ("Proximamente").
 *
 * El cobro no se cree al comando: saldo >= precio -> comando -> a los 2 ticks se relee el
 * saldo y, si no ha bajado al menos el precio, el cobro cuenta como fallido (el Altar anula
 * el trueque y devuelve lo demas) y queda en la Bitacora "monedero | fallo". Un comando mal
 * escrito en la config no puede regalar el Manto.
 *
 * monedero.modo: prueba guarda saldos falsos en monedero-prueba.<uuid> (solo para el Test,
 * donde no hay UltimateMobCoins) y /lw hardcore mc <jugador> <n> los pone.
 */
final class Monedero {

    private static final Pattern NOMBRE_VALIDO = Pattern.compile("[A-Za-z0-9_.]{1,20}");

    private final Hardcore hc;
    /** Solo en las pruebas: modo prueba sobre un yml en memoria. */
    private final YamlConfiguration prueba;
    /** Relecturas pendientes, para cancelarlas al parar. */
    private final Set<BukkitTask> relecturas = new HashSet<>();

    private static boolean papiResuelto;
    private static Method setPlaceholders;

    Monedero(Hardcore hc) {
        this.hc = hc;
        this.prueba = null;
        Autotest.registrar("monedero", this::autotest);
    }

    /** Para los autotest: siempre en modo prueba, sobre memoria. */
    Monedero(YamlConfiguration memoria) {
        this.hc = null;
        this.prueba = memoria;
    }

    private boolean modoPrueba() {
        if (prueba != null) return true;
        return "prueba".equalsIgnoreCase(hc.cfg().getString("monedero.modo", "real"));
    }

    private YamlConfiguration datos() {
        return prueba != null ? prueba : hc.datos();
    }

    private String placeholder() {
        return hc == null ? "" : hc.cfg().getString("monedero.saldo-placeholder", "");
    }

    private String comandoCobrar() {
        return hc == null ? "" : hc.cfg().getString("monedero.cobrar", "");
    }

    /** Si se puede cobrar MC: modo prueba, o las dos claves rellenas y PlaceholderAPI presente. */
    boolean disponible() {
        if (modoPrueba()) return true;
        String ph = placeholder(), cmd = comandoCobrar();
        if (ph == null || ph.isBlank() || cmd == null || cmd.isBlank()) return false;
        return resolverPapi() != null;
    }

    /** MobCoins que tiene; -1 si no se puede saber (no disponible o placeholder ilegible). */
    long saldo(Player p) {
        if (p == null) return -1;
        return saldo(p.getUniqueId(), p);
    }

    private long saldo(UUID u, OfflinePlayer p) {
        if (modoPrueba()) return Math.max(0, datos().getLong("monedero-prueba." + u, 0));
        if (!disponible()) return -1;
        try {
            Object r = resolverPapi().invoke(null, p, placeholder());
            return numero(r == null ? "" : r.toString());
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Lee un numero de lo que devuelva el placeholder: "12,345", "12.345", "12345.67" o
     * "1.2k" no son lo mismo, y aqui solo se aceptan los que no dejan duda. Con "k"/"m" o
     * texto sin cifras sale -1 (no se cobra: mejor "Proximamente" que cobrar mal).
     */
    static long numero(String s) {
        if (s == null) return -1;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.endsWith("k") || t.endsWith("m")) return -1;
        // Decimales al final ("1234.56" o "1234,56"): se quitan.
        t = t.replaceAll("[.,]\\d{1,2}$", "");
        t = t.replaceAll("[^0-9]", "");
        if (t.isEmpty() || t.length() > 15) return -1;
        return Long.parseLong(t);
    }

    /** Pone el saldo de prueba (solo tiene efecto con monedero.modo: prueba). */
    void ponerPrueba(UUID u, long n) {
        datos().set("monedero-prueba." + u, Math.max(0, n));
        if (hc != null) hc.guardarYa();
    }

    /**
     * Cobra n MobCoins. El resultado llega por el consumer, en el hilo principal: en modo
     * prueba al momento; en modo real, a los 2 ticks, tras releer el saldo.
     */
    void cobrar(Player p, long n, Consumer<Boolean> hecho) {
        Consumer<Boolean> fin = hecho == null ? b -> { } : hecho;
        if (p == null || n < 0) {
            fin.accept(false);
            return;
        }
        if (n == 0) {
            fin.accept(true);
            return;
        }
        if (modoPrueba()) {
            boolean ok = cobrarPrueba(p.getUniqueId(), n);
            anotar(p.getName(), ok ? "cobro" : "sin-saldo", n, "prueba");
            fin.accept(ok);
            return;
        }
        long antes = saldo(p);
        if (!disponible() || antes < n) {
            anotar(p.getName(), antes < 0 ? "no-disponible" : "sin-saldo", n, "antes " + antes);
            fin.accept(false);
            return;
        }
        if (!NOMBRE_VALIDO.matcher(p.getName()).matches()) {
            anotar(p.getName(), "fallo", n, "nombre raro");
            fin.accept(false);
            return;
        }
        String cmd = comandoCobrar().replace("%jugador%", p.getName()).replace("%n%", String.valueOf(n));
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        try {
            hc.plugin().getServer().dispatchCommand(hc.plugin().getServer().getConsoleSender(), cmd);
        } catch (Throwable t) {
            anotar(p.getName(), "fallo", n, "comando: " + t.getMessage());
            fin.accept(false);
            return;
        }
        final BukkitTask[] tarea = new BukkitTask[1];
        tarea[0] = hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(), () -> {
            relecturas.remove(tarea[0]);
            long despues = saldo(p.getUniqueId(), p);
            boolean ok = despues >= 0 && antes - despues >= n;
            anotar(p.getName(), ok ? "cobro" : "fallo", n, "antes " + antes + " | despues " + despues);
            fin.accept(ok);
        }, 2L);
        relecturas.add(tarea[0]);
    }

    /** P-M10: "Te faltan <n> MobCoins." */
    static Component avisoFaltan(long n) {
        return ComandoCalamity.mensaje(Component.text("Te faltan ")
                .append(Component.text(String.valueOf(Math.max(0, n)), NamedTextColor.WHITE))
                .append(Component.text(" MobCoins.")));
    }

    /** P-M11: los trueques con MobCoins mientras disponible() sea false. */
    static Component avisoProximamente() {
        return ComandoCalamity.mensaje("Esto aún no se puede pagar con MobCoins. Próximamente.");
    }

    /** El cobro del modo prueba, sin Bukkit: descuenta si llega y dice si ha podido. */
    boolean cobrarPrueba(UUID u, long n) {
        String r = "monedero-prueba." + u;
        long hay = Math.max(0, datos().getLong(r, 0));
        if (hay < n) return false;
        datos().set(r, hay - n);
        if (hc != null) hc.guardarYa();
        return true;
    }

    private void anotar(String jugador, String que, long n, String detalle) {
        if (hc == null) return;
        hc.plugin().bitacora().anotar("monedero", que, jugador, String.valueOf(n), detalle);
    }

    /** PlaceholderAPI.setPlaceholders(OfflinePlayer, String) por reflexion (solo ExpansionLethal importa PAPI). */
    private static synchronized Method resolverPapi() {
        if (!papiResuelto) {
            papiResuelto = true;
            try {
                Class<?> c = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                setPlaceholders = c.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            } catch (Throwable t) {
                setPlaceholders = null;
            }
        }
        return setPlaceholders;
    }

    // ------------------------------------------------------------------ pruebas

    /**
     * WP1 aceptacion 8: 5.000 de saldo, un cobro de 3.000 pasa y el segundo de 3.000 falla sin
     * descontar. En memoria y con un UUID sintetico (el saldo real lo pone "mc <jugador> <n>").
     */
    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        probarNucleo(h);
        String modo = hc.cfg().getString("monedero.modo", "real");
        boolean vacias = placeholder().isBlank() || comandoCobrar().isBlank();
        if (!"prueba".equalsIgnoreCase(modo) && vacias) {
            h.ok("modo real con claves vacias -> no disponible", !disponible());
        } else {
            h.sinExcepcion("config actual (" + modo + "): disponible() no revienta", this::disponible);
        }
        h.ok("la prueba no toca hardcore-datos.yml", !hc.datos().isSet("monedero-prueba." + Autotest.sintetico(31)));
        return h.lineas();
    }

    static void probarNucleo(Autotest.Hoja h) {
        Monedero m = new Monedero(new YamlConfiguration());
        UUID u = Autotest.sintetico(31);
        h.ok("en modo prueba esta disponible", m.disponible());
        m.ponerPrueba(u, 5000);
        h.igual("saldo de prueba", 5000L, m.saldo(u, null));
        h.ok("cobro de 3000 con 5000", m.cobrarPrueba(u, 3000));
        h.igual("quedan 2000", 2000L, m.saldo(u, null));
        h.ok("segundo cobro de 3000 falla", !m.cobrarPrueba(u, 3000));
        h.igual("y no descuenta", 2000L, m.saldo(u, null));
        h.igual("numero con separador de miles", 12345L, numero("12,345"));
        h.igual("numero con decimales", 1234L, numero("1234.56"));
        h.igual("numero abreviado no vale", -1L, numero("1.2k"));
        h.igual("texto sin cifras no vale", -1L, numero("n/a"));
    }

    void parar() {
        for (BukkitTask t : relecturas) t.cancel();
        relecturas.clear();
    }
}
