package net.ederus.calamity.hardcore;

import net.ederus.calamity.MobsLethal;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Pruebas sin jugadores de cada modulo: /lw hardcore autotest <modulo|todo> (consola o RCON).
 *
 * Cada modulo registra la suya con registrar("<modulo>", () -> lineas). Convencion de las
 * lineas: una por comprobacion; las que empiezan por "OK" han pasado y cualquier otra es
 * la descripcion de un fallo. La clase Hoja las escribe sola (ok, igual, cerca).
 *
 * Salida, en el que manda el comando y en la Bitacora lethal-world:
 *   autotest | <modulo> | OK n/n              todo bien
 *   autotest | <modulo> | FALLO | <texto>     una por fallo, y al final
 *   autotest | <modulo> | FALLO k/n           (k = las que pasaron)
 *   autotest | <modulo> | pendiente           el modulo aun es el esqueleto de WP0
 *
 * Las pruebas trabajan con instancias en memoria y UUID sinteticos
 * (00000000-0000-0000-0000-00000000000N) y NUNCA escriben en los datos reales.
 */
final class Autotest {

    private static final Map<String, Supplier<List<String>>> PRUEBAS = new LinkedHashMap<>();
    private static final Set<String> PENDIENTES = new LinkedHashSet<>();

    private Autotest() {
    }

    static void registrar(String modulo, Supplier<List<String>> prueba) {
        String m = modulo.toLowerCase(Locale.ROOT);
        PRUEBAS.put(m, prueba);
        PENDIENTES.remove(m);
    }

    /** Lo llaman los esqueletos de WP0: el modulo existe pero su prueba aun no. */
    static void pendiente(String modulo) {
        String m = modulo.toLowerCase(Locale.ROOT);
        if (!PRUEBAS.containsKey(m)) PENDIENTES.add(m);
    }

    static void vaciar() {
        PRUEBAS.clear();
        PENDIENTES.clear();
    }

    /** UUID sintetico numero n, para no tocar a ningun jugador real. */
    static UUID sintetico(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }

    // ------------------------------------------------------------------ el comando

    /** Registra /lw hardcore autotest y las pruebas propias de WP0 (base y mmo). */
    static void instalar(Hardcore hc) {
        Subcomandos.lw().registrar("autotest", "autotest <modulo|todo>: pruebas sin jugadores", "ederus.mundos",
                (quien, args) -> comando(hc, quien, args),
                args -> {
                    if (args.length != 2) return List.of();
                    List<String> op = new ArrayList<>(todos());
                    op.add("todo");
                    return op;
                });
        registrar("base", () -> base(hc));
        registrar("mmo", () -> mmo(hc));
    }

    private static List<String> todos() {
        Set<String> s = new LinkedHashSet<>(PRUEBAS.keySet());
        s.addAll(PENDIENTES);
        return new ArrayList<>(s);
    }

    private static void comando(Hardcore hc, CommandSender quien, String[] args) {
        if (args.length < 2) {
            decir(hc, quien, "autotest | uso | /lw hardcore autotest <" + String.join("|", todos()) + "|todo>", true);
            return;
        }
        String cual = args[1].toLowerCase(Locale.ROOT);
        if (!cual.equals("todo")) {
            correr(hc, quien, cual);
            return;
        }
        int bien = 0, mal = 0, pendientes = 0;
        for (String m : todos()) {
            int r = correr(hc, quien, m);
            if (r > 0) bien++;
            else if (r < 0) mal++;
            else pendientes++;
        }
        decir(hc, quien, "autotest | todo | " + bien + " OK, " + mal + " con fallos, " + pendientes + " pendientes", mal == 0);
    }

    /** 1 = todo bien, -1 = algun fallo, 0 = pendiente o no existe. */
    private static int correr(Hardcore hc, CommandSender quien, String modulo) {
        Supplier<List<String>> prueba = PRUEBAS.get(modulo);
        if (prueba == null) {
            if (PENDIENTES.contains(modulo)) {
                decir(hc, quien, "autotest | " + modulo + " | pendiente", true);
            } else {
                decir(hc, quien, "autotest | " + modulo + " | no existe (hay: " + String.join(", ", todos()) + ")", false);
            }
            return 0;
        }
        List<String> lineas;
        try {
            lineas = prueba.get();
        } catch (Throwable t) {
            hc.plugin().getLogger().log(Level.WARNING, "[Calamity] autotest " + modulo + " revienta", t);
            decir(hc, quien, "autotest | " + modulo + " | FALLO | excepcion: " + t, false);
            return -1;
        }
        if (lineas == null) lineas = List.of();
        int total = lineas.size(), bien = 0;
        for (String l : lineas) {
            if (l != null && l.startsWith("OK")) bien++;
            else decir(hc, quien, "autotest | " + modulo + " | FALLO | " + l, false);
        }
        if (bien == total) {
            decir(hc, quien, "autotest | " + modulo + " | OK " + bien + "/" + total, true);
            return 1;
        }
        decir(hc, quien, "autotest | " + modulo + " | FALLO " + bien + "/" + total, false);
        return -1;
    }

    private static void decir(Hardcore hc, CommandSender quien, String linea, boolean bien) {
        quien.sendMessage(Component.text(linea, bien ? Paleta.BIEN : Paleta.AVISO));
        try {
            hc.plugin().bitacora().anotar(linea);
        } catch (Throwable ignorado) {
            // Sin bitacora (apagando) la linea ya salio por el comando.
        }
    }

    // ------------------------------------------------------------ hoja de pruebas

    /** Para escribir pruebas sin repetir el formato de las lineas. */
    static final class Hoja {

        private final List<String> lineas = new ArrayList<>();

        Hoja ok(String que, boolean bien) {
            lineas.add(bien ? "OK " + que : que);
            return this;
        }

        Hoja igual(String que, Object esperado, Object real) {
            boolean bien = Objects.equals(esperado, real);
            lineas.add(bien ? "OK " + que : que + ": esperaba " + esperado + ", salio " + real);
            return this;
        }

        Hoja cerca(String que, double esperado, double real, double tolerancia) {
            boolean bien = Math.abs(esperado - real) <= tolerancia;
            lineas.add(bien ? "OK " + que : que + ": esperaba " + esperado + " (+-" + tolerancia + "), salio " + real);
            return this;
        }

        /** Que algo no lance excepcion. */
        Hoja sinExcepcion(String que, Runnable r) {
            try {
                r.run();
                lineas.add("OK " + que);
            } catch (Throwable t) {
                lineas.add(que + ": excepcion " + t);
            }
            return this;
        }

        List<String> lineas() {
            return lineas;
        }
    }

    // ----------------------------------------------------------- pruebas de WP0

    /** Marcas, Calendario, PuenteMmo, nivelBase, Estadisticas, dano verdadero, registros. */
    private static List<String> base(Hardcore hc) {
        Hoja h = new Hoja();

        for (NamespacedKey k : Marcas.todas()) {
            h.igual("marca " + k.getKey() + " en lethal_world", Marcas.NAMESPACE, k.getNamespace());
        }

        // Domingo 27/09/2026 a las 23:30 en Madrid (21:30 UTC, horario de verano) y una
        // hora despues, ya lunes: el dia y la semana cambian en Madrid y no en UTC.
        Calendario madrid = new Calendario(ZoneId.of("Europe/Madrid"));
        Calendario utc = new Calendario(ZoneOffset.UTC);
        long domingo = Instant.parse("2026-09-27T21:30:00Z").toEpochMilli();
        long lunes = Instant.parse("2026-09-27T22:30:00Z").toEpochMilli();
        h.igual("dia en Madrid", "2026-09-27", madrid.dia(domingo));
        h.igual("semana en Madrid", "2026-W39", madrid.semana(domingo));
        h.igual("mismo dia y semana en dos llamadas", madrid.dia(domingo) + madrid.semana(domingo),
                madrid.dia(domingo) + madrid.semana(domingo));
        h.igual("dia en Madrid pasada la medianoche", "2026-09-28", madrid.dia(lunes));
        h.igual("semana nueva en Madrid el lunes", "2026-W40", madrid.semana(lunes));
        h.igual("en UTC sigue siendo domingo", "2026-09-27", utc.dia(lunes));
        h.igual("en UTC sigue la semana 39", "2026-W39", utc.semana(lunes));
        h.igual("semana anterior", "2026-W39", madrid.semanaAnterior(lunes));
        h.igual("mes", "2026-09", madrid.mes(domingo));
        h.igual("inicio del dia en Madrid", Instant.parse("2026-09-26T22:00:00Z").toEpochMilli(),
                madrid.inicioDia(domingo));
        h.igual("semana ISO de fin de anio", "2026-W53", utc.semana(Instant.parse("2027-01-01T12:00:00Z").toEpochMilli()));

        boolean hayMmo = hc.plugin().getServer().getPluginManager().isPluginEnabled("MMOItems");
        h.igual("PuenteMmo.disponible() igual a si MMOItems esta", hayMmo, PuenteMmo.disponible());
        ItemStack espada = new ItemStack(Material.DIAMOND_SWORD);
        h.sinExcepcion("PuenteMmo con una espada vanilla no revienta", () -> {
            PuenteMmo.enlace(espada);
            PuenteMmo.tier(espada);
            PuenteMmo.stat(espada, "ATTACK_DAMAGE");
        });
        h.igual("enlace de una espada vanilla", null, PuenteMmo.enlace(espada));
        h.igual("tier de una espada vanilla", null, PuenteMmo.tier(espada));
        h.igual("stat de una espada vanilla", 0.0, PuenteMmo.stat(espada, "ATTACK_DAMAGE"));
        h.igual("enlace de null", null, PuenteMmo.enlace(null));
        h.igual("crear con un id sin punto", null, PuenteMmo.crear("SIN_PUNTO"));

        MobsLethal mobs = hc.plugin().mobs();
        if (mobs == null) {
            h.ok("nivelBase: los mobs de Lethal World no estan activos", false);
        } else {
            ConfigurationSection n = hc.plugin().getConfig().getConfigurationSection("mobs.nivel");
            double porRango = n == null ? 2.0 : n.getDouble("por-rango", 2.0);
            double porNivel = Math.max(1.0, n == null ? 20.0 : n.getDouble("poder-por-nivel", 20.0));
            int a = mobs.nivelBase(10, 400), b = mobs.nivelBase(10, 400);
            h.igual("nivelBase sin azar (dos llamadas)", a, b);
            h.igual("nivelBase de rango 10 y poder 400", (int) Math.round(10 * porRango + 400 / porNivel), a);
            for (Player p : hc.plugin().getServer().getOnlinePlayers()) {
                h.igual("nivelBase sin azar para " + p.getName(), mobs.nivelBase(p), mobs.nivelBase(p));
            }
        }

        YamlConfiguration memoria = new YamlConfiguration();
        Estadisticas st = new Estadisticas(memoria, madrid);
        UUID u = sintetico(1);
        st.sumar(u, "parcas", 2);
        st.sumar(u, "parcas", 3);
        st.maximo(u, "racha-max", 4);
        st.maximo(u, "racha-max", 2);
        h.igual("stats suman", 5L, st.de(u, "parcas"));
        h.igual("stats de la semana", 5L, st.semana(u, "parcas"));
        h.igual("stats maximo se queda con el mayor", 4L, st.de(u, "racha-max"));
        h.igual("stats de una clave sin nada", 0L, st.de(u, "ecos-cerrados"));
        h.ok("stats de prueba no tocan hardcore-datos.yml", !hc.datos().isSet("stats." + u));

        h.igual("dano verdadero con tope 0,9 de 20", 18.0, DanoVerdadero.recorte(50, 0.9, 20));
        h.igual("dano verdadero por debajo del tope", 5.0, DanoVerdadero.recorte(5, 0.9, 20));
        h.igual("dano verdadero sin tope", 50.0, DanoVerdadero.recorte(50, 0, 20));
        h.cerca("escala de una vida logica de 2440", 1024 / 2440.0, Amenazas.escalaPara(2440), 1e-9);
        h.igual("escala de una vida que cabe", 1.0, Amenazas.escalaPara(500));
        // PARCA de N 52 (DIS sec. 1.6): 2.440 de vida logica, tope 8 % = 195,2 por golpe.
        h.cerca("golpe de 400 a una PARCA de 2440 se topa al 8 %", 195.2, Amenazas.golpeLogico(400, 0.08, 2440), 1e-9);
        h.igual("golpe por debajo del tope entra entero", 12.0, Amenazas.golpeLogico(12, 0.08, 2440));
        h.igual("sin tope entra entero", 400.0, Amenazas.golpeLogico(400, 0, 2440));
        h.igual("golpe nulo", 0.0, Amenazas.golpeLogico(0, 0.08, 2440));
        h.cerca("a la entidad le llega el golpe topado por la escala", 195.2 * 1024 / 2440.0,
                Amenazas.golpeLogico(400, 0.08, 2440) * Amenazas.escalaPara(2440), 1e-9);

        h.ok("/lw hardcore autotest registrado", Subcomandos.lw().nombres(null).contains("autotest"));
        h.ok("/lw hardcore amenazas registrado", Subcomandos.lw().nombres(null).contains("amenazas"));
        h.igual("placeholder cordura sin jugador", "", PlaceholdersLethal.resolver(null, "cordura"));
        h.igual("placeholder que no existe", null, PlaceholdersLethal.resolver(null, "no_existe_de_verdad"));
        h.igual("hardcore-datos.yml sigue sin stats sinteticas", false, hc.datos().isSet("stats-semana." + madrid.semana() + "." + u));
        return h.lineas();
    }

    /** Que PuenteMmo.crear sepa hacer cada objeto que Calamity entrega o forja. */
    private static List<String> mmo(Hardcore hc) {
        if (!PuenteMmo.disponible()) return List.of("OK sin MMOItems: no hay nada que crear");
        Hoja h = new Hoja();
        List<String> ids = new ArrayList<>();
        for (String seccion : List.of("entregas.mmo", "forja.piezas")) {
            ConfigurationSection s = hc.cfg().getConfigurationSection(seccion);
            if (s == null) continue;
            for (String k : s.getKeys(false)) ids.add(s.getString(k));
        }
        if (ids.isEmpty()) h.ok("hay ids en entregas.mmo y forja.piezas", false);
        for (String id : ids) {
            ItemStack it = PuenteMmo.crear(id);
            h.ok("crear " + id, it != null && !it.getType().isAir());
            if (it != null) h.igual("enlace de " + id, id, PuenteMmo.enlace(it));
        }
        return h.lineas();
    }
}
