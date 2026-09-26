package net.ederus.lethalworld.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * M32 · Creditos por jugador (PLAN sec. 2): creditos.<uuid>.<tipo> en hardcore-datos.yml.
 *
 * Tipos: sello:<id-minijefe> (lo da tasar un Sello de ese minijefe), sello-errante (hitos de
 * 48 y 72 h y la Caja del Caos; vale por cualquier Sello), fragmento (Campana de la PARCA de
 * N >= 40; la Guadana pide 7) y marca (Lagrima de Eco valida; la Mascara pide 5 y el Filo 10).
 * No se pierden al morir: son la memoria de lo que ya sacaste vivo.
 *
 * Los que vienen de la Caja del Caos van ademas contados en creditos-caja.<uuid>.<tipo>, y
 * esos (y todo Sello Errante, venga de donde venga) solo se canjean con
 * creditos.horas-para-errantes (48) horas activas en Calamity. Por que: la caja se compra
 * con MobCoins del Survival, y sin la condicion de horas el Manto saldria del baltop sin
 * pisar Calamity (PLAN sec. 1, principio 6: el freno al Manto es el Sello).
 *
 * Al gastar, primero los ganados dentro y despues los de caja: el jugador no pierde la
 * opcion de canjear por culpa del orden.
 */
final class Creditos {

    static final String ERRANTE = "sello-errante";

    private final Hardcore hc;
    /** Solo en las pruebas: un yml en memoria y unas horas activas inventadas. */
    private final YamlConfiguration prueba;
    private final ToDoubleFunction<UUID> horasPrueba;

    Creditos(Hardcore hc) {
        this.hc = hc;
        this.prueba = null;
        this.horasPrueba = null;
        PlaceholdersLethal.registrar("marcas", (j, r) -> j == null ? "" : String.valueOf(de(j.getUniqueId(), "marca")));
        PlaceholdersLethal.registrar("fragmentos", (j, r) -> j == null ? "" : String.valueOf(de(j.getUniqueId(), "fragmento")));
        // %lethalworld_sello_<id>%: si/no tiene ese Sello (DIS sec. 7). La piedad es de WP2.
        PlaceholdersLethal.registrar("sello", (j, r) -> {
            if (j == null) return "";
            if (r == null || r.isEmpty()) return null;
            return de(j.getUniqueId(), "sello:" + r) > 0 ? "si" : "no";
        });
        Autotest.registrar("creditos", this::autotest);
    }

    /** Para los autotest: sin Bitacora, sin telemetria y sin tocar los datos reales. */
    Creditos(YamlConfiguration memoria, ToDoubleFunction<UUID> horas) {
        this.hc = null;
        this.prueba = memoria;
        this.horasPrueba = horas;
    }

    private YamlConfiguration datos() {
        return prueba != null ? prueba : hc.datos();
    }

    static String tipo(String t) {
        return t == null ? "" : t.trim().toLowerCase(Locale.ROOT);
    }

    private static String ruta(UUID jugador, String tipo) {
        return "creditos." + jugador + "." + tipo;
    }

    private static String rutaCaja(UUID jugador, String tipo) {
        return "creditos-caja." + jugador + "." + tipo;
    }

    int de(UUID jugador, String tipo) {
        if (jugador == null) return 0;
        return Math.max(0, datos().getInt(ruta(jugador, tipo(tipo)), 0));
    }

    /** Cuantos de ese tipo vinieron de la Caja (siempre <= de()). */
    int deCaja(UUID jugador, String tipo) {
        if (jugador == null) return 0;
        return Math.min(de(jugador, tipo), Math.max(0, datos().getInt(rutaCaja(jugador, tipo(tipo)), 0)));
    }

    /** Todos los creditos de un jugador, ordenados, sin los que estan a cero. */
    Map<String, Integer> todos(UUID jugador) {
        Map<String, Integer> out = new TreeMap<>();
        ConfigurationSection s = datos().getConfigurationSection("creditos." + jugador);
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            int n = s.getInt(k, 0);
            if (n > 0) out.put(k, n);
        }
        return out;
    }

    double horasPedidas() {
        return hc == null ? 48 : hc.cfg().getDouble("creditos.horas-para-errantes", 48);
    }

    double horasActivas(UUID jugador) {
        if (horasPrueba != null) return horasPrueba.applyAsDouble(jugador);
        Horas h = hc.horas();
        return h == null ? 0 : hc.valor("horas", () -> h.horasActivas(jugador), 0.0);
    }

    private boolean horasOk(UUID jugador) {
        return horasActivas(jugador) >= horasPedidas();
    }

    /** Cuantos puede gastar ahora mismo (sin los de caja si no llega a las horas). */
    int gastables(UUID jugador, String tipo) {
        String t = tipo(tipo);
        int total = de(jugador, t);
        if (total <= 0) return 0;
        if (horasOk(jugador)) return total;
        if (t.equals(ERRANTE)) return 0;
        return total - deCaja(jugador, t);
    }

    /**
     * Suma n creditos (n negativo = ajuste de admin, nunca por debajo de 0). deCaja marca
     * los que exigen las horas activas para canjearse.
     */
    void sumar(UUID jugador, String tipo, int n, String origen, boolean deCaja) {
        String t = tipo(tipo);
        if (jugador == null || t.isEmpty() || n == 0) return;
        YamlConfiguration d = datos();
        int antes = de(jugador, t);
        int nuevo = Math.max(0, antes + n);
        d.set(ruta(jugador, t), nuevo == 0 ? null : nuevo);
        if (n > 0 && deCaja) {
            d.set(rutaCaja(jugador, t), deCaja(jugador, t) + n);
        } else if (n < 0) {
            // Un ajuste a la baja se come primero los de caja: son los que el admin corrige.
            int caja = Math.max(0, datos().getInt(rutaCaja(jugador, t), 0));
            int quedaCaja = Math.min(nuevo, Math.max(0, caja + n));
            d.set(rutaCaja(jugador, t), quedaCaja == 0 ? null : quedaCaja);
        }
        if (hc == null) return;
        hc.guardarYa();
        hc.plugin().bitacora().anotar("credito", Saldo.nombre(jugador), t, (n > 0 ? "+" : "") + n,
                origen == null ? "-" : origen, deCaja ? "caja" : "dentro", String.valueOf(nuevo));
        if (n > 0) {
            Map<String, Object> campos = new LinkedHashMap<>();
            campos.put("tipo", t);
            campos.put("origen", origen == null ? "" : origen);
            campos.put("n", n);
            campos.put("canjeable", canjeable(jugador, t));
            Telemetria tel = hc.telemetria();
            if (tel != null) hc.seguro("telemetria", () -> tel.suceso("credito", Bukkit.getOfflinePlayer(jugador), campos));
        }
    }

    /** Gasta n si puede gastarlos ya (ver gastables). False = no toca nada. */
    boolean gastar(UUID jugador, String tipo, int n) {
        String t = tipo(tipo);
        if (jugador == null || t.isEmpty() || n < 0) return false;
        if (n == 0) return true;
        if (gastables(jugador, t) < n) return false;
        int total = de(jugador, t);
        int caja = deCaja(jugador, t);
        int normales = total - caja;
        int deNormales = Math.min(normales, n);
        int deLaCaja = n - deNormales;
        YamlConfiguration d = datos();
        d.set(ruta(jugador, t), total - n == 0 ? null : total - n);
        d.set(rutaCaja(jugador, t), caja - deLaCaja == 0 ? null : caja - deLaCaja);
        if (hc != null) {
            hc.guardarYa();
            hc.plugin().bitacora().anotar("credito", Saldo.nombre(jugador), t, "-" + n, "gasto",
                    deLaCaja > 0 ? "caja " + deLaCaja : "dentro", String.valueOf(total - n));
        }
        return true;
    }

    /** Si tiene al menos uno de ese tipo que pueda gastar ahora. */
    boolean canjeable(UUID jugador, String tipo) {
        return gastables(jugador, tipo) > 0;
    }

    /**
     * Por que no puede gastar n: null si puede, "credito" si no los tiene, "horas" si los
     * tiene pero son de caja (o errantes) y le faltan horas activas. Para el Altar (P-W01 / P-W07).
     */
    String motivoNoGasta(UUID jugador, String tipo, int n) {
        if (gastables(jugador, tipo) >= n) return null;
        return de(jugador, tipo) >= n ? "horas" : "credito";
    }

    /** P-W07: "Ese credito se canjea con 48 h activas en Calamity. Llevas <h>." */
    Component avisoHoras(UUID jugador) {
        return ComandoCalamity.mensaje(Component.text("Ese crédito se canjea con "
                        + Math.round(horasPedidas()) + " h activas en Calamity. Llevas ")
                .append(Component.text(String.format(Locale.ROOT, "%.1f", horasActivas(jugador)), NamedTextColor.WHITE))
                .append(Component.text(".")));
    }

    // ------------------------------------------------------------------ pruebas

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        probarNucleo(h);
        h.ok("la prueba no toca hardcore-datos.yml", !hc.datos().isSet("creditos." + Autotest.sintetico(21)));
        h.igual("placeholder sello sin jugador", "", PlaceholdersLethal.resolver(null, "sello_heraldo-carmes"));
        return h.lineas();
    }

    static void probarNucleo(Autotest.Hoja h) {
        Map<UUID, Double> horas = new java.util.HashMap<>();
        YamlConfiguration memoria = new YamlConfiguration();
        Creditos c = new Creditos(memoria, u -> horas.getOrDefault(u, 0.0));
        UUID u = Autotest.sintetico(21);
        h.igual("nadie tiene nada", 0, c.de(u, "marca"));
        c.sumar(u, "sello:heraldo-carmes", 1, "prueba", false);
        h.igual("sello ganado dentro", 1, c.de(u, "SELLO:heraldo-carmes"));
        h.ok("se canjea sin horas", c.canjeable(u, "sello:heraldo-carmes"));
        c.sumar(u, "fragmento", 2, "caja", true);
        h.igual("dos fragmentos de caja", 2, c.de(u, "fragmento"));
        h.ok("los de caja no se canjean sin 48 h", !c.canjeable(u, "fragmento"));
        h.igual("motivo: horas", "horas", c.motivoNoGasta(u, "fragmento", 1));
        h.ok("gastar uno de caja sin horas falla", !c.gastar(u, "fragmento", 1));
        h.igual("y no toca nada", 2, c.de(u, "fragmento"));
        c.sumar(u, "fragmento", 1, "tasacion", false);
        h.igual("uno de dentro se gasta aunque haya de caja", true, c.gastar(u, "fragmento", 1));
        h.igual("quedan los dos de caja", 2, c.deCaja(u, "fragmento"));
        horas.put(u, 48.0);
        h.ok("con 48 h ya se canjean", c.gastar(u, "fragmento", 2));
        h.igual("sin fragmentos", 0, c.de(u, "fragmento"));
        h.igual("motivo sin creditos: credito", "credito", c.motivoNoGasta(u, "fragmento", 1));
        horas.put(u, 10.0);
        c.sumar(u, ERRANTE, 1, "hito", false);
        h.ok("el Sello Errante pide 48 h aunque no venga de caja", !c.canjeable(u, ERRANTE));
        horas.put(u, 50.0);
        h.ok("con 50 h se canjea", c.canjeable(u, ERRANTE));
        c.sumar(u, "marca", 3, "prueba", false);
        c.sumar(u, "marca", -5, "admin", false);
        h.igual("ajuste negativo se queda en 0", 0, c.de(u, "marca"));
        h.ok("todos lista el sello", c.todos(u).containsKey("sello:heraldo-carmes"));
    }

    void parar() {
    }
}
