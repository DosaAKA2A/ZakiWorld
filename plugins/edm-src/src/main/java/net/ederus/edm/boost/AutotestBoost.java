package net.ederus.edm.boost;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerExpChangeEvent;

import net.ederus.edm.comun.MobCoins;

/**
 * /boost selftest: lo que tiene que cumplir el modulo, sin tocar los boosts de nadie.
 *
 * Todo corre sobre un data.yml temporal y jugadores de mentira (un Proxy de Player con
 * su UUID y su mundo), asi que se puede lanzar en produccion y tambien fuera del
 * servidor (el arnes lo llama con modulo = null). Comprueba:
 *   - que el boost de drops no existe ni se puede volver a encender, y que uno guardado
 *     se descarta al cargar sin romper lo demas;
 *   - que EXP multiplica la experiencia de verdad (el mismo listener del servidor);
 *   - que SKILL_EXP sin AuraSkills no revienta y sale como no disponible;
 *   - que PESCA se lee por la API con el tope y los mundos excluidos;
 *   - que MOBCOINS sube lo que paga una baja y nada mas.
 */
public final class AutotestBoost {

    private final List<String> lineas = new ArrayList<>();
    private int fallos;
    private int oks;

    public List<String> lineas() {
        return lineas;
    }

    public int fallos() {
        return fallos;
    }

    public int oks() {
        return oks;
    }

    private void ok(String que, boolean bien) {
        lineas.add(bien ? "&aOK &7" + que : "&cFALLO &f" + que);
        if (bien) oks++; else fallos++;
    }

    private void igual(String que, Object esperado, Object real) {
        boolean bien = esperado == null ? real == null : esperado.equals(real);
        ok(que + (bien ? "" : " (esperaba " + esperado + ", salio " + real + ")"), bien);
    }

    /** Lanza todas las comprobaciones. modulo puede ser null (fuera del servidor). */
    public static AutotestBoost correr(BoostPlugin modulo) {
        AutotestBoost t = new AutotestBoost();
        File tmp = null;
        try {
            tmp = File.createTempFile("edm-boost-selftest", ".yml");
            t.drops(tmp, modulo);
            t.exp(tmp);
            t.skillExp(modulo);
            t.pesca(tmp, modulo);
            t.mobcoins(tmp);
        } catch (Throwable e) {
            t.ok("el selftest no deberia reventar: " + e, false);
        } finally {
            if (tmp != null) tmp.delete();
        }
        return t;
    }

    /* ------------------------------------------------------------------ drops */

    private void drops(File tmp, BoostPlugin modulo) throws Exception {
        boolean hay = false;
        for (Tipo x : Tipo.values()) {
            if (x.name().equals("DROPS") || x.id().equals("drops")) hay = true;
        }
        ok("no existe ningun tipo DROPS", !hay);
        igual("\"drops\" no es un tipo", null, Tipo.de("drops"));
        igual("\"DROPS\" tampoco", null, Tipo.de("DROPS"));

        YamlConfiguration jar = recurso("boost/config.yml");
        ok("el config.yml del jar no trae seccion drops", jar != null && !jar.contains("drops"));

        // Un data.yml de antes con un drops vivo, uno caducado y un exp vivo.
        UUID a = UUID.randomUUID();
        long fin = System.currentTimeMillis() + 600_000L;
        YamlConfiguration viejo = new YamlConfiguration();
        viejo.set("jugadores." + a + ".drops.multiplicador", 2.0);
        viejo.set("jugadores." + a + ".drops.inicio", System.currentTimeMillis());
        viejo.set("jugadores." + a + ".drops.fin", fin);
        viejo.set("jugadores." + a + ".exp.multiplicador", 2.0);
        viejo.set("jugadores." + a + ".exp.inicio", System.currentTimeMillis());
        viejo.set("jugadores." + a + ".exp.fin", fin);
        viejo.set("globales.drops.multiplicador", 3.0);
        viejo.set("globales.drops.inicio", System.currentTimeMillis());
        viejo.set("globales.drops.fin", fin);
        viejo.save(tmp);

        List<String> avisos = new ArrayList<>();
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { avisos.add(r.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        Servicio s = new Servicio(tmp, log);
        igual("se descartan los 2 boosts de drops guardados", 2, s.descartados());
        ok("y se avisa en consola", avisos.size() == 2 && avisos.get(0).contains("drops"));
        igual("el exp del mismo jugador sigue vivo", 2.0, s.multiplicador(a, Tipo.EXP));
        ok("data.yml se reescribe sin drops", !YamlConfiguration.loadConfiguration(tmp).contains("jugadores." + a + ".drops")
                && !YamlConfiguration.loadConfiguration(tmp).contains("globales.drops"));

        if (modulo != null) {
            boolean enAll = false;
            for (Tipo x : modulo.tipos()) if (x.id().equals("drops")) enAll = true;
            ok("\"all\" no incluye drops", !enAll);
        }
    }

    /* -------------------------------------------------------------------- exp */

    private void exp(File tmp) throws Exception {
        tmp.delete();
        Servicio s = new Servicio(tmp);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        s.dar(a, Tipo.EXP, 60_000L, 2.0);
        Player pa = jugador(a, "world"), pb = jugador(b, "world");

        Efectos efectos = new Efectos((p, tipo) -> BoostPlugin.calcular(s, p.getUniqueId(), tipo, true,
                false, 3.0));
        PlayerExpChangeEvent e = new PlayerExpChangeEvent(pa, 10);
        efectos.alGanarExp(e);
        igual("EXP x2: 10 de experiencia pasan a 20", 20, e.getAmount());
        PlayerExpChangeEvent sin = new PlayerExpChangeEvent(pb, 10);
        efectos.alGanarExp(sin);
        igual("sin boost se queda en 10", 10, sin.getAmount());

        s.dar(b, Tipo.EXP, 60_000L, 5.0);
        igual("un x5 se queda en el tope de x3", 3.0, BoostPlugin.calcular(s, b, Tipo.EXP, true, false, 3.0));
        s.darGlobal(Tipo.EXP, 60_000L, 1.5);
        igual("personal x2 y global x1.5: manda el mayor, no el producto", 2.0,
                BoostPlugin.calcular(s, a, Tipo.EXP, true, false, 3.0));
        igual("en un mundo excluido no multiplica", 1.0, BoostPlugin.calcular(s, a, Tipo.EXP, true, true, 3.0));
        int x15 = Efectos.escalar(1, 1.5);
        ok("x1.5 sobre 1 da 1 o 2, nunca menos", x15 == 1 || x15 == 2);
    }

    /* -------------------------------------------------------------- skill exp */

    private void skillExp(BoostPlugin modulo) {
        boolean hayAura;
        try {
            hayAura = Bukkit.getPluginManager().isPluginEnabled(Habilidades.PLUGIN);
        } catch (Throwable t) {
            hayAura = false;
        }
        if (!hayAura) {
            Habilidades h = new Habilidades((p, tipo) -> 2.0, Logger.getAnonymousLogger());
            boolean enganchado;
            try {
                enganchado = h.enganchar(null);
            } catch (Throwable t) {
                ok("SKILL_EXP sin AuraSkills no revienta al engancharse: " + t, false);
                return;
            }
            ok("SKILL_EXP sin AuraSkills: no se engancha y no revienta", !enganchado && !h.enganchado());
            try {
                h.alGanar(new Event() {
                    @Override public HandlerList getHandlers() { return new HandlerList(); }
                });
                ok("un evento cualquiera sin AuraSkills no hace nada", true);
            } catch (Throwable t) {
                ok("un evento cualquiera sin AuraSkills no hace nada: " + t, false);
            }
            if (modulo != null) ok("sin AuraSkills el tipo sale como no disponible", !modulo.disponible(Tipo.SKILL_EXP));
        } else if (modulo != null) {
            ok("con AuraSkills el tipo esta disponible si esta activado",
                    modulo.disponible(Tipo.SKILL_EXP) == modulo.habilitado(Tipo.SKILL_EXP));
        }
        igual("XP de habilidad 12.5 con x2 es 25", 25.0, Habilidades.escalar(12.5, 2.0));
        igual("XP de habilidad sin boost no cambia", 12.5, Habilidades.escalar(12.5, 1.0));
        igual("\"skill-exp\" y \"habilidades\" son SKILL_EXP", Tipo.SKILL_EXP,
                Tipo.de("skill-exp") == Tipo.de("habilidades") ? Tipo.de("skill-exp") : null);
    }

    /* ------------------------------------------------------------------ pesca */

    private void pesca(File tmp, BoostPlugin modulo) throws Exception {
        tmp.delete();
        Servicio s = new Servicio(tmp);
        UUID a = UUID.randomUUID();
        s.dar(a, Tipo.PESCA, 60_000L, 1.5);
        igual("PESCA x1.5 se lee como 1.5", 1.5, BoostPlugin.calcular(s, a, Tipo.PESCA, true, false, 3.0));
        igual("sin PremioPescao (no disponible) es 1.0", 1.0, BoostPlugin.calcular(s, a, Tipo.PESCA, false, false, 3.0));
        igual("PESCA no toca otros tipos", 1.0, BoostPlugin.calcular(s, a, Tipo.EXP, true, false, 3.0));
        if (modulo == null) {
            igual("la API sin el modulo en marcha devuelve 1.0", 1.0, BoostApi.multiplicador(a, "pesca"));
        }
        igual("la API no conoce drops", 1.0, BoostApi.multiplicador(a, "drops"));
    }

    /* --------------------------------------------------------------- mobcoins */

    private void mobcoins(File tmp) throws Exception {
        tmp.delete();
        Servicio s = new Servicio(tmp);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        s.dar(a, Tipo.MOBCOINS, 60_000L, 2.0);
        MobCoins.Boost gancho = BoostPlugin.ganchoMobcoins((p, tipo) ->
                BoostPlugin.calcular(s, p.getUniqueId(), tipo, true, false, 3.0));
        igual("MOBCOINS x2: una baja de 15 paga 30", 30L, gancho.aplicar(jugador(a, "world"), 15));
        igual("sin boost paga 15", 15L, gancho.aplicar(jugador(b, "world"), 15));
        igual("una baja de 0 sigue sin pagar", 0L, gancho.aplicar(jugador(a, "world"), 0));
        igual("sin jugador, conBoost no cambia la cantidad", 15L, MobCoins.conBoost(null, 15));
        s.dar(a, Tipo.EXP, 60_000L, 3.0);
        igual("el boost de EXP no sube las MobCoins", 2.0,
                BoostPlugin.calcular(s, a, Tipo.MOBCOINS, true, false, 3.0));
    }

    /* ---------------------------------------------------------------- ayudas */

    private static YamlConfiguration recurso(String ruta) throws Exception {
        try (InputStream in = AutotestBoost.class.getClassLoader().getResourceAsStream(ruta)) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /** Un jugador de mentira: solo contesta a su UUID, su nombre y su mundo. */
    static Player jugador(UUID id, String mundo) {
        World w = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (px, m, args) -> switch (m.getName()) {
                    case "getName" -> mundo;
                    case "hashCode" -> mundo.hashCode();
                    case "equals" -> px == args[0];
                    case "toString" -> "mundo " + mundo;
                    default -> defecto(m.getReturnType());
                });
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (px, m, args) -> switch (m.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "selftest";
                    case "getWorld" -> w;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> px == args[0];
                    case "toString" -> "jugador de prueba " + id;
                    default -> defecto(m.getReturnType());
                });
    }

    private static Object defecto(Class<?> c) {
        if (c == boolean.class) return false;
        if (c == int.class) return 0;
        if (c == long.class) return 0L;
        if (c == double.class) return 0.0;
        if (c == float.class) return 0f;
        return null;
    }
}
