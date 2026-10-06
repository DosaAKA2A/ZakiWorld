package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * M16 · Hitos y tags (PLAN sec. 6): la generalizacion de Hardcore.entregarTag a una lista.
 *
 * Cada hito de la config (hardcore.hitos.<id>) dice que estadistica mira, desde que umbral,
 * como se llama y que comandos de consola ejecuta (%jugador% validado, como el tag de hoy).
 * Lo entregado se apunta en hitos-entregados.<uuid>.<id> y se guarda ANTES de ejecutar nada:
 * una caida a mitad puede perder un premio, nunca darlo dos veces.
 *
 * No hay tarea propia: Estadisticas.sumar/maximo llaman a revisar() con la clave que cambio y
 * Horas lo hace al cerrar cada minuto activo. Asi el hito salta en el momento, se este dentro
 * o fuera, y nadie tiene que acordarse de llamarlo.
 *
 * Estadisticas que no son una clave de stats tal cual:
 *   horas-activas              las de Horas, en horas (M33)
 *   extracciones-con-reliquia  si nadie la cuenta, vale 1 en cuanto stats.reliquias > 0 (la
 *                              Tasacion solo suma reliquias al sacarlas vivo)
 *   manto                      1 en cuanto se ve el [5] del Manto puesto dentro (tickManto)
 *   estadisticas: [a, b]       todas a la vez: vale lo que valga la menor
 *
 * Los de 100 h y 250 h vienen con activo: false hasta el visto bueno de Dosa.
 *
 * [SEGADOR] (Dosa, 2026-10-06): "el tag si a los 10" y el God Kit GrimReaper "que requiera matar a la Parca 30
 * veces". segador-2 (10 PARCAs) da solo el tag II; segador-3 sube de 25 a 30, se enciende y da el tag III, su
 * efecto de RIP y gkit.grimreaper. El kit se reclama una vez: al reclamarlo, AE le pone la espera mas larga que
 * admite y el propio kit le quita el permiso. Ningun otro hito da gkit.grimreaper (autotestSegador).
 */
final class Hitos {

    private static final String MANTO = "manto";

    private final Hardcore hc;

    Hitos(Hardcore hc) {
        this.hc = hc;
        Subcomandos.staff().registrar("milestones", "milestones <player>: hitos entregados y lo que le falta (M16)",
                Subcomandos.PERMISO, this::comando, args -> args.length == 2 ? Entregas.nombresConectados() : List.of());
        Autotest.registrar("hitos", this::autotest);
    }

    private boolean activo() {
        return hc.cfg().getBoolean("hitos.activo", true);
    }

    private ConfigurationSection lista() {
        return hc.cfg().getConfigurationSection("hitos");
    }

    // ------------------------------------------------------------------ nucleo

    /** Las estadisticas que mira un hito ("estadisticas" gana a "estadistica"). */
    static List<String> estadisticas(ConfigurationSection hito) {
        List<String> varias = hito.getStringList("estadisticas");
        if (!varias.isEmpty()) return varias;
        String una = hito.getString("estadistica", "");
        return una == null || una.isBlank() ? List.of() : List.of(una);
    }

    /** Si al cambiar esa clave hay que mirar ese hito. clave null = todos. */
    static boolean relacionado(ConfigurationSection hito, String clave) {
        if (clave == null) return true;
        for (String e : estadisticas(hito)) {
            if (e.equals(clave)) return true;
            if (e.equals("extracciones-con-reliquia") && clave.equals("reliquias")) return true;
        }
        return false;
    }

    /**
     * Los hitos que tocan ahora y aun no se entregaron, en el orden de la config. No apunta
     * nada: lo hace quien entrega (con la entrega, para que el orden sea marcar y luego dar).
     *
     * @param entregados la seccion hitos-entregados (o una en memoria en el autotest)
     * @param valor      estadistica -> valor actual del jugador
     */
    static List<String> pendientes(ConfigurationSection hitos, ConfigurationSection entregados, UUID u,
                                   String clave, ToDoubleFunction<String> valor) {
        List<String> out = new ArrayList<>();
        if (hitos == null) return out;
        for (String id : hitos.getKeys(false)) {
            ConfigurationSection h = hitos.getConfigurationSection(id);
            if (h == null || !h.getBoolean("activo", true)) continue;
            if (!relacionado(h, clave)) continue;
            if (entregados != null && entregados.getBoolean(u + "." + id, false)) continue;
            List<String> ests = estadisticas(h);
            if (ests.isEmpty()) continue;
            double umbral = h.getDouble("umbral", 1);
            double menor = Double.MAX_VALUE;
            for (String e : ests) menor = Math.min(menor, valor.applyAsDouble(e));
            if (menor >= umbral) out.add(id);
        }
        return out;
    }

    /** El valor de una estadistica de un hito para ese jugador. */
    private double valor(UUID u, String est) {
        if (est.equals("horas-activas")) {
            Horas h = hc.horas();
            return h == null ? 0 : h.horasActivas(u);
        }
        Estadisticas st = hc.estadisticas();
        if (st == null) return 0;
        long v = st.de(u, est);
        if (v == 0 && est.equals("extracciones-con-reliquia") && st.de(u, "reliquias") > 0) v = 1;
        return v;
    }

    // ------------------------------------------------------------------ ganchos

    /** Lo llama Estadisticas cada vez que cambia una estadistica (y Horas cada minuto activo). */
    void revisar(UUID jugador, String clave) {
        if (jugador == null) return;
        // Los contratos de Eco (cazas validas, redimir) cuelgan de las mismas estadisticas:
        // se les reenvia aqui para no pedir otro gancho en Ecos.
        Contratos ct = hc.contratos();
        if (ct != null && clave != null) hc.seguro("contratos", () -> ct.estadistica(jugador, clave));
        if (!activo()) return;
        ConfigurationSection hitos = lista();
        if (hitos == null) return;
        List<String> ids = pendientes(hitos, hc.datos().getConfigurationSection("hitos-entregados"), jugador,
                clave, e -> valor(jugador, e));
        for (String id : ids) entregar(jugador, id, hitos.getConfigurationSection(id));
    }

    /**
     * Marca, guarda y ejecuta. Sin un nombre valido no se puede ejecutar nada por consola:
     * no se marca y se intenta en el siguiente cambio.
     */
    private void entregar(UUID u, String id, ConfigurationSection h) {
        if (h == null) return;
        OfflinePlayer o = Bukkit.getOfflinePlayer(u);
        String nombre = o.getName();
        var bit = hc.plugin().bitacora();
        if (nombre == null || !Entregas.NOMBRE_VALIDO.matcher(nombre).matches()) {
            bit.anotar("hito", "fallo", u.toString(), id, "sin nombre valido");
            return;
        }
        hc.datos().set("hitos-entregados." + u + "." + id, true);
        hc.guardarYa();

        List<String> fallidos = new ArrayList<>();
        for (String plantilla : h.getStringList("comandos")) {
            if (!ejecutar(plantilla, nombre)) fallidos.add(plantilla);
        }
        String titulo = h.getString("nombre", id);
        bit.anotar("hito", nombre, id, titulo, "umbral " + net.ederus.edm.comun.Bitacora.num(h.getDouble("umbral", 1)),
                fallidos.isEmpty() ? "ok" : "fallidos " + fallidos.size());
        for (String f : fallidos) bit.anotar("hito", "comando-fallido", nombre, id, f);

        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("id", id);
        campos.put("nombre", titulo);
        campos.put("estadistica", String.join("+", estadisticas(h)));
        campos.put("umbral", h.getDouble("umbral", 1));
        Telemetria t = hc.telemetria();
        if (t != null) hc.seguro("telemetria", () -> t.suceso("hito", o, campos));

        // P-H01. Los que no son tag ([...]) son logros: no se anuncian como tag.
        boolean tag = titulo.startsWith("[");
        Player p = o.getPlayer();
        if (p != null) {
            p.sendMessage(ComandoCalamity.mensaje(Component.text(tag ? "Has ganado el tag " : "Has conseguido el logro ")
                    .append(Component.text(titulo, Paleta.MARCA)).append(Component.text("."))));
        }
        if (h.getBoolean("anuncio", false)) {
            hc.plugin().getServer().broadcast(ComandoCalamity.mensaje(Component.text(nombre, Paleta.DETALLE)
                    .append(Component.text(tag ? " ha ganado el tag " : " ha conseguido el logro "))
                    .append(Component.text(titulo, Paleta.MARCA))
                    .append(Component.text("."))));
        }
    }

    /** Un comando de la config por consola, con %jugador% ya validado. */
    private boolean ejecutar(String plantilla, String nombre) {
        if (plantilla == null || plantilla.isBlank()) return true;
        String cmd = plantilla.replace("%jugador%", nombre).trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        try {
            return hc.plugin().getServer().dispatchCommand(hc.plugin().getServer().getConsoleSender(), ComandosViejos.traducir(cmd));
        } catch (Throwable t) {
            hc.plugin().getLogger().warning("[Calamity] Falló el comando de hito \"" + cmd + "\": " + t);
            return false;
        }
    }

    /**
     * Cada 30 s: quien lleve dentro las cinco piezas del Manto (el [5]) gana la estadistica
     * "manto". Se cuenta con ObjetosCalamity.piezasManto, el mismo criterio que el aura: si
     * no, el hito y el aura podrian no estar de acuerdo. Solo con MMOItems.
     */
    void tickManto() {
        if (!activo() || !PuenteMmo.disponible()) return;
        ConfigurationSection hitos = lista();
        if (hitos == null || !hayHitoDe(hitos, MANTO)) return;
        ObjetosCalamity obj = hc.objetos();
        Estadisticas st = hc.estadisticas();
        if (obj == null || st == null) return;
        for (World w : hc.plugin().getServer().getWorlds()) {
            if (!hc.esHardcore(w)) continue;
            for (Player p : w.getPlayers()) {
                if (!hc.cuenta(p) || st.de(p.getUniqueId(), MANTO) > 0) continue;
                if (hc.valor("objetos", () -> obj.piezasManto(p), 0) >= 5) st.maximo(p.getUniqueId(), MANTO, 1);
            }
        }
    }

    private static boolean hayHitoDe(ConfigurationSection hitos, String est) {
        for (String id : hitos.getKeys(false)) {
            ConfigurationSection h = hitos.getConfigurationSection(id);
            if (h != null && h.getBoolean("activo", true) && estadisticas(h).contains(est)) return true;
        }
        return false;
    }

    void parar() {
    }

    // ------------------------------------------------------------------ comando

    private void comando(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(ComandoCalamity.mensaje("Uso: /calamity milestones <player>"));
            return;
        }
        OfflinePlayer o = Entregas.buscar(args[1]);
        if (o == null) {
            quien.sendMessage(ComandoCalamity.mensaje("No encuentro a ese jugador."));
            return;
        }
        UUID u = o.getUniqueId();
        ConfigurationSection hitos = lista();
        if (hitos == null) {
            quien.sendMessage(ComandoCalamity.mensaje("No hay hitos en la config (hardcore.hitos)."));
            return;
        }
        // Por si alguno quedo a medias (sin nombre, apagado y encendido despues): se revisa todo.
        revisar(u, null);
        quien.sendMessage(ComandoCalamity.mensaje("Hitos de " + Entregas.nombre(o)
                + (activo() ? "" : " (hitos apagados)") + ":"));
        for (String id : hitos.getKeys(false)) {
            ConfigurationSection h = hitos.getConfigurationSection(id);
            if (h == null) continue;
            List<String> ests = estadisticas(h);
            double menor = Double.MAX_VALUE;
            for (String e : ests) menor = Math.min(menor, valor(u, e));
            if (ests.isEmpty()) menor = 0;
            boolean dado = hc.datos().getBoolean("hitos-entregados." + u + "." + id, false);
            String estado = dado ? "entregado" : !h.getBoolean("activo", true) ? "apagado"
                    : net.ederus.edm.comun.Bitacora.num(menor) + " / " + net.ederus.edm.comun.Bitacora.num(h.getDouble("umbral", 1));
            quien.sendMessage(Component.text("  " + id + " ", Paleta.DETALLE)
                    .append(Component.text(h.getString("nombre", id) + " · " + String.join("+", ests) + " · " + estado,
                            dado ? Paleta.BIEN : Paleta.TENUE)));
        }
    }

    // ------------------------------------------------------------------ autotest

    /**
     * Los hitos del Segador de una config (la del jar o la del servidor), sin servidor: el tag II a las 10
     * PARCAs sin el God Kit, el III a las 30 encendido y con gkit.grimreaper, y nadie mas que lo de.
     */
    static List<String> autotestSegador(ConfigurationSection hitos, String origen) {
        Autotest.Hoja h = new Autotest.Hoja();
        if (hitos == null) {
            h.ok(origen + ": sin hitos", false);
            return h.lineas();
        }
        String gkit = "permission set gkit.grimreaper";
        ConfigurationSection s2 = hitos.getConfigurationSection("segador-2"), s3 = hitos.getConfigurationSection("segador-3");
        h.ok(origen + ": segador-2 y segador-3 existen", s2 != null && s3 != null);
        if (s2 == null || s3 == null) return h.lineas();
        h.cerca(origen + ": segador-2 a las 10 PARCAs", 10, s2.getDouble("umbral", 0), 1e-9);
        h.ok(origen + ": segador-2 da el tag II", s2.getStringList("comandos").stream().anyMatch(c -> c.contains("segador2.badge.unlocked")));
        h.ok(origen + ": segador-2 ya no da el God Kit", s2.getStringList("comandos").stream().noneMatch(c -> c.contains(gkit)));
        h.cerca(origen + ": segador-3 a las 30 PARCAs", 30, s3.getDouble("umbral", 0), 1e-9);
        h.ok(origen + ": segador-3 encendido", s3.getBoolean("activo", true));
        h.ok(origen + ": segador-3 da el tag III", s3.getStringList("comandos").stream().anyMatch(c -> c.contains("segador3.badge.unlocked")));
        h.ok(origen + ": segador-3 da el God Kit", s3.getStringList("comandos").stream().anyMatch(c -> c.contains(gkit)));
        int conGkit = 0;
        for (String id : hitos.getKeys(false)) {
            ConfigurationSection x = hitos.getConfigurationSection(id);
            if (x != null && x.getStringList("comandos").stream().anyMatch(c -> c.contains(gkit))) conGkit++;
        }
        h.igual(origen + ": un solo hito da gkit.grimreaper", 1, conGkit);

        YamlConfiguration entregados = new YamlConfiguration();
        UUID u = Autotest.sintetico(2);
        entregados.set(u + ".segador-1", true);
        entregados.set(u + ".segador-2", true);
        Map<String, Double> v = new LinkedHashMap<>();
        ToDoubleFunction<String> val = e -> v.getOrDefault(e, 0.0);
        v.put("parcas", 10.0);
        h.ok(origen + ": 10 PARCAs no dan el III", !pendientes(hitos, new YamlConfiguration(), u, "parcas", val).contains("segador-3"));
        v.put("parcas", 29.0);
        h.ok(origen + ": 29 PARCAs tampoco", !pendientes(hitos, entregados, u, "parcas", val).contains("segador-3"));
        v.put("parcas", 30.0);
        h.ok(origen + ": 30 PARCAs dan el III", pendientes(hitos, entregados, u, "parcas", val).contains("segador-3"));
        entregados.set(u + ".segador-3", true);
        h.ok(origen + ": entregado, no se repite (el permiso no vuelve tras reclamar el kit)",
                !pendientes(hitos, entregados, u, null, val).contains("segador-3"));
        return h.lineas();
    }

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        YamlConfiguration conf = new YamlConfiguration();
        conf.set("desvelado.estadistica", "horas-activas");
        conf.set("desvelado.umbral", 6);
        conf.set("insomne-1.estadistica", "horas-activas");
        conf.set("insomne-1.umbral", 24);
        conf.set("vigia.estadistica", "horas-activas");
        conf.set("vigia.umbral", 100);
        conf.set("vigia.activo", false);
        conf.set("segador-1.estadistica", "parcas");
        conf.set("segador-1.umbral", 1);
        conf.set("umbral.estadistica", "extracciones-con-reliquia");
        conf.set("umbral.umbral", 1);
        conf.set("vestigio.estadisticas", List.of("forja-mascara", "forja-filo"));
        conf.set("vestigio.umbral", 1);
        conf.set("manto-ids", List.of("CALAMITY.YELMO_DE_CALAMIDAD"));

        YamlConfiguration entregados = new YamlConfiguration();
        UUID u = Autotest.sintetico(1);
        Map<String, Double> v = new LinkedHashMap<>();
        ToDoubleFunction<String> val = e -> {
            double x = v.getOrDefault(e, 0.0);
            if (x == 0 && e.equals("extracciones-con-reliquia") && v.getOrDefault("reliquias", 0.0) > 0) x = 1;
            return x;
        };

        v.put("horas-activas", 23.9);
        List<String> a = pendientes(conf, entregados, u, "horas-activas", val);
        h.ok("23,9 h: no toca insomne-1", !a.contains("insomne-1"));
        h.ok("23,9 h: toca desvelado (6 h)", a.contains("desvelado"));

        v.put("horas-activas", 24.0);
        List<String> b = pendientes(conf, entregados, u, "horas-activas", val);
        h.ok("24 h: toca insomne-1", b.contains("insomne-1"));
        for (String id : b) entregados.set(u + "." + id, true);
        List<String> c = pendientes(conf, entregados, u, "horas-activas", val);
        h.ok("24 h otra vez: insomne-1 no se repite", !c.contains("insomne-1"));
        h.igual("24 h otra vez: nada pendiente", List.of(), c);

        v.put("horas-activas", 250.0);
        h.ok("vigia apagado no sale a las 250 h", !pendientes(conf, entregados, u, "horas-activas", val).contains("vigia"));
        h.ok("un cambio de parcas no mira los de horas", pendientes(conf, entregados, u, "parcas", val).isEmpty());
        v.put("parcas", 1.0);
        h.igual("1 PARCA cobrada: segador-1", List.of("segador-1"), pendientes(conf, entregados, u, "parcas", val));

        v.put("reliquias", 2.0);
        h.igual("primera extraccion con reliquia salta con stats.reliquias", List.of("umbral"),
                pendientes(conf, entregados, u, "reliquias", val));
        v.put("forja-mascara", 1.0);
        h.ok("vestigio pide las dos forjas", !pendientes(conf, entregados, u, "forja-mascara", val).contains("vestigio"));
        v.put("forja-filo", 1.0);
        h.ok("vestigio con las dos forjas", pendientes(conf, entregados, u, "forja-filo", val).contains("vestigio"));
        h.ok("manto-ids no es un hito", !pendientes(conf, entregados, u, null, val).contains("manto-ids"));

        YamlConfiguration jar = Ambush.configDelJar();
        if (jar != null) {
            for (String l : autotestSegador(jar.getConfigurationSection("hardcore.hitos"), "jar")) {
                h.ok(l.startsWith("OK ") ? l.substring(3) : l, l.startsWith("OK "));
            }
        }
        ConfigurationSection real = lista();
        if (real != null && real.isConfigurationSection("insomne-1")) {
            h.cerca("config: insomne-1 a las 24 h", 24, real.getDouble("insomne-1.umbral", 0), 1e-9);
            h.ok("config: vigia (100 h) apagado", !real.getBoolean("vigia.activo", true));
            h.ok("config: sin-alba (250 h) apagado", !real.getBoolean("sin-alba.activo", true));
            for (String l : autotestSegador(real, "config")) h.ok(l.startsWith("OK ") ? l.substring(3) : l, l.startsWith("OK "));
        }
        h.ok("autotest no toca hitos-entregados reales", !hc.datos().isSet("hitos-entregados." + u));
        h.ok("/calamity milestones registrado", Subcomandos.staff().nombres(null).contains("milestones"));
        return h.lineas();
    }
}
