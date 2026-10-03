package net.ederus.edm.superbeacon;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * /superbeacon selftest: lo que tiene que cumplir el modulo, sin tocar ninguna baliza de
 * verdad ni a ningun jugador.
 *
 * Primero la logica pura, que no necesita mundo:
 *   - la fusion: de cada grupo gana el MAYOR, nunca la suma;
 *   - elegir efectos: el tope, quitar siempre, lo fijo, lo no disponible;
 *   - la caducidad y los textos de tiempo;
 *   - a quien beneficia, con y sin clan (y sin PlaceholderAPI);
 *   - el alcance y su indice por chunks;
 *   - la limpieza del clan que devuelve PlaceholderAPI.
 * Y dentro del servidor (modulo en marcha):
 *   - leer tipos de un config con errores, y que el config de serie no tenga ninguno;
 *   - la ida y vuelta del objeto: crear, leer el PDC y que salga lo mismo.
 */
final class AutotestSuperBeacon {

    private final List<String> lineas = new ArrayList<>();
    private int fallos;
    private int oks;

    List<String> lineas() {
        return lineas;
    }

    int fallos() {
        return fallos;
    }

    int oks() {
        return oks;
    }

    private void ok(String que, boolean bien) {
        lineas.add(bien ? "&aOK &7" + que : "&cFALLO &f" + que);
        if (bien) oks++;
        else fallos++;
    }

    private void igual(String que, Object esperado, Object real) {
        boolean bien = esperado == null ? real == null : esperado.equals(real);
        ok(que + (bien ? "" : " (esperaba " + esperado + ", salio " + real + ")"), bien);
    }

    static AutotestSuperBeacon correr(SuperBeaconPlugin modulo) {
        AutotestSuperBeacon t = new AutotestSuperBeacon();
        try {
            t.fusion();
            t.eleccion();
            t.tiempo();
            t.beneficio();
            t.alcance();
            t.clanes();
            if (modulo != null) {
                t.lectura(modulo);
                t.objeto(modulo);
            }
        } catch (Throwable e) {
            t.ok("el selftest no deberia reventar: " + e, false);
        }
        return t;
    }

    /* ================================================================ fusion */

    /** Un efecto de mentira: solo su grupo y su fuerza, que es lo que mira la fusion. */
    private static Efecto falso(String clave, String grupo, double fuerza) {
        return new Efecto(clave, clave, Material.STONE) {
            @Override
            ClaseEfecto clase() {
                return null;
            }

            @Override
            String grupo() {
                return grupo;
            }

            @Override
            double fuerza() {
                return fuerza;
            }
        };
    }

    private void fusion() {
        Map<String, Efecto> m = new LinkedHashMap<>();
        Efecto prisa2 = falso("prisa2", "pocion:haste", 2), prisa3 = falso("prisa3", "pocion:haste", 3);
        Efecto.fusionar(m, prisa2);
        Efecto.fusionar(m, prisa3);
        igual("Prisa II y Prisa III: gana Prisa III", prisa3, m.get("pocion:haste"));
        m.clear();
        Efecto.fusionar(m, prisa3);
        Efecto.fusionar(m, prisa2);
        igual("en el otro orden tambien Prisa III", prisa3, m.get("pocion:haste"));

        m.clear();
        for (int i = 0; i < 5; i++) Efecto.fusionar(m, falso("vida" + i, "atributo:max_health", 4));
        igual("cinco balizas de +4 de vida dan +4, no +20", 4.0, m.get("atributo:max_health").fuerza());
        Efecto.fusionar(m, falso("vida8", "atributo:max_health", 8));
        igual("y una de +8 gana a las de +4", 8.0, m.get("atributo:max_health").fuerza());

        m.clear();
        Efecto.fusionar(m, falso("mc", "boost:mobcoins", 1.25));
        Efecto.fusionar(m, falso("mc2", "boost:mobcoins", 1.5));
        Efecto.fusionar(m, falso("exp", "boost:exp", 1.5));
        igual("MobCoins x1,25 y x1,5: x1,5", 1.5, m.get("boost:mobcoins").fuerza());
        igual("grupos distintos conviven (mobcoins y exp)", 2, m.size());
    }

    /* ============================================================== eleccion */

    private void eleccion() {
        Set<String> el = new LinkedHashSet<>();
        igual("elige el primero", MenuBaliza.Resultado.ACTIVADO, MenuBaliza.alternar(el, "a", 2, true));
        igual("elige el segundo", MenuBaliza.Resultado.ACTIVADO, MenuBaliza.alternar(el, "b", 2, true));
        igual("el tercero choca con el tope", MenuBaliza.Resultado.TOPE, MenuBaliza.alternar(el, "c", 2, true));
        igual("y no entra", false, el.contains("c"));
        igual("quitar uno elegido", MenuBaliza.Resultado.DESACTIVADO, MenuBaliza.alternar(el, "a", 2, true));
        igual("ahora si entra el tercero", MenuBaliza.Resultado.ACTIVADO, MenuBaliza.alternar(el, "c", 2, true));
        igual("con tope 0 todo es fijo", MenuBaliza.Resultado.FIJO, MenuBaliza.alternar(el, "x", 0, true));
        igual("uno no disponible no se elige", MenuBaliza.Resultado.NO_DISPONIBLE,
                MenuBaliza.alternar(new LinkedHashSet<>(), "z", 2, false));
        Set<String> conRoto = new LinkedHashSet<>(List.of("z"));
        igual("pero si estaba elegido se puede quitar", MenuBaliza.Resultado.DESACTIVADO,
                MenuBaliza.alternar(conRoto, "z", 2, false));

        Map<String, Efecto> efectos = new LinkedHashMap<>();
        for (String k : List.of("a", "b", "c")) efectos.put(k, falso(k, "g:" + k, 1));
        TipoBaliza dos = tipo(2, efectos), uno = tipo(1, efectos), fijo = tipo(0, efectos);
        igual("activos en el orden del config, sin claves que no existen",
                List.of("a", "c"), claves(dos.activos(List.of("c", "x", "a"))));
        igual("si se baja el tope, solo los primeros", List.of("a"), claves(uno.activos(List.of("c", "a"))));
        igual("elegibles 0: todos activos", List.of("a", "b", "c"), claves(fijo.activos(List.of())));
    }

    private static TipoBaliza tipo(int elegibles, Map<String, Efecto> efectos) {
        return new TipoBaliza("prueba", "&fPrueba", Material.BEACON, 10, 0, TipoBaliza.AlCaducar.APAGAR,
                TipoBaliza.Beneficia.DUENO, true, true, elegibles, List.of(), efectos, null, 1, 4);
    }

    private static List<String> claves(List<Efecto> l) {
        List<String> out = new ArrayList<>();
        for (Efecto e : l) out.add(e.clave());
        return out;
    }

    /* ================================================================ tiempo */

    private void tiempo() {
        igual("0 ms", "0 s", Tiempo.restante(0));
        igual("negativo no existe", "0 s", Tiempo.restante(-5000));
        igual("30 s", "30 s", Tiempo.restante(30 * Tiempo.SEGUNDO));
        igual("12 min 30 s", "12 min 30 s", Tiempo.restante(12 * Tiempo.MINUTO + 30 * Tiempo.SEGUNDO));
        igual("4 h 12 min", "4 h 12 min", Tiempo.restante(4 * Tiempo.HORA + 12 * Tiempo.MINUTO + 59 * Tiempo.SEGUNDO));
        igual("12 d 4 h", "12 d 4 h", Tiempo.restante(12 * Tiempo.DIA + 4 * Tiempo.HORA + 30 * Tiempo.MINUTO));
        igual("3 d justos", "3 d", Tiempo.restante(3 * Tiempo.DIA));
        // 2026-11-03 18:00 UTC
        long instante = 1_793_728_800_000L;
        igual("fecha en UTC", "03/11/2026 18:00", Tiempo.fecha(instante, ZoneId.of("UTC")));
        igual("zona vacia: la del sistema", ZoneId.systemDefault(), Tiempo.zona(""));
        igual("zona mal escrita: null (avisa quien llama)", null, Tiempo.zona("Marte/Olympus"));

        long ahora = System.currentTimeMillis();
        Ficha permanente = new Ficha(UUID.randomUUID(), "t", null, null, null, 0, List.of());
        Ficha vencida = new Ficha(UUID.randomUUID(), "t", null, null, null, ahora - 1, List.of());
        Ficha viva = new Ficha(UUID.randomUUID(), "t", null, null, null, ahora + Tiempo.DIA, List.of());
        igual("vence 0 no caduca nunca", false, permanente.vencida(ahora + 100L * 365 * Tiempo.DIA));
        igual("vencida si ya paso la fecha", true, vencida.vencida(ahora));
        igual("viva si aun no llego", false, viva.vencida(ahora));
    }

    /* ============================================================= beneficio */

    private void beneficio() {
        UUID dueno = UUID.randomUUID(), otro = UUID.randomUUID();
        TipoBaliza.Beneficia d = TipoBaliza.Beneficia.DUENO, c = TipoBaliza.Beneficia.CLAN, t = TipoBaliza.Beneficia.TODOS;
        igual("dueno: el dueño si", true, Motor.recibe(d, dueno, dueno, null, null));
        igual("dueno: otro no", false, Motor.recibe(d, dueno, otro, "ABC", "ABC"));
        igual("todos: otro si", true, Motor.recibe(t, dueno, otro, null, null));
        igual("clan: mismo clan si (sin mirar mayusculas)", true, Motor.recibe(c, dueno, otro, "ABC", "abc"));
        igual("clan: otro clan no", false, Motor.recibe(c, dueno, otro, "ABC", "XYZ"));
        igual("clan: jugador sin clan no", false, Motor.recibe(c, dueno, otro, "ABC", null));
        igual("clan: baliza sin clan, como dueno", false, Motor.recibe(c, dueno, otro, null, null));
        igual("clan: sin clan el dueño sigue recibiendo", true, Motor.recibe(c, dueno, dueno, null, null));
    }

    /* =============================================================== alcance */

    private void alcance() {
        Ficha f = new Ficha(UUID.randomUUID(), "t", UUID.randomUUID(), "x", null, 0, List.of());
        Baliza b = new Baliza(f, "w", 0, 64, 0, Material.BEACON, 0);
        igual("a 20 y 10 de distancia, dentro de 24", true, b.dentro(20.0, 70, 10.0, 24));
        igual("a 30 en linea recta, fuera de 24", false, b.dentro(30.0, 64, 0.5, 24));
        igual("24 bloques por debajo, todavia dentro", true, b.dentro(0.5, 40, 0.5, 24));
        igual("25 por debajo, fuera", false, b.dentro(0.5, 39, 0.5, 24));
        igual("hacia arriba no hay techo", true, b.dentro(0.5, 300, 0.5, 24));

        Alcance a = new Alcance();
        a.anadir(b, 20);
        igual("el indice la apunta en su chunk", true, a.en("w", 10, 10).contains(b));
        igual("y en el chunk del borde que toca", true, a.en("w", -18, 2).contains(b));
        igual("no en una esquina que el circulo no roza", true, a.en("w", -20, -20).isEmpty());
        igual("ni en otro mundo", true, a.en("otro", 10, 10).isEmpty());
        igual("ni lejos", true, a.en("w", 200, 200).isEmpty());
    }

    /* ================================================================ clanes */

    private void clanes() {
        igual("hex de Spigot fuera", "ABC", Clanes.limpiar("&x&F&F&C&8&5&7ABC"));
        igual("seccion fuera", "ABC", Clanes.limpiar("§a§lABC"));
        igual("&#hex fuera, corchetes dentro", "[ABC]", Clanes.limpiar("&#FF0000[ABC]&r"));
        igual("minimessage fuera", "ABC", Clanes.limpiar("<#FF0000><bold>ABC</bold>"));
        Set<String> sin = Set.of("", "sin clan", "n/a", "-");
        igual("vacio es sin clan", true, Clanes.esSinClan("", sin));
        igual("el marcador sin resolver es sin clan", true, Clanes.esSinClan("%uclans_tag_color%", sin));
        igual("\"Sin clan\" es sin clan", true, Clanes.esSinClan("Sin clan", sin));
        igual("ABC es un clan", false, Clanes.esSinClan("ABC", sin));
        igual("mismo clan sin mayusculas", true, Clanes.mismoClan("Abc", "aBC"));
        igual("null nunca es el mismo clan", false, Clanes.mismoClan(null, null));
    }

    /* ======================================================= dentro del server */

    private static final String CONFIG_CON_ERRORES = String.join("\n",
            "tipos:",
            "  bueno:",
            "    nombre: '&#FFFFFFBueno'",
            "    bloque: BEACON",
            "    radio: 10",
            "    elegibles: 1",
            "    efectos:",
            "      prisa: {tipo: pocion, efecto: HASTE, nivel: 2}",
            "      vida: {tipo: atributo, atributo: max_health, valor: 4}",
            "      raro: {tipo: atributo, atributo: no_existe, valor: 2}",
            "      mal: {tipo: inventado}",
            "      minions: {tipo: boost, boost: minions, multiplicador: 2}",
            "      cero: {tipo: boost, boost: exp, multiplicador: 1}",
            "      nopocion: {tipo: pocion, efecto: NO_EXISTE}",
            "  sinbloque:",
            "    bloque: NO_EXISTE",
            "  grande:",
            "    bloque: BEACON",
            "    radio: 9999",
            "    beneficia: nadie",
            "    efectos:",
            "      v: {tipo: vuelo}",
            "  'Con Espacios':",
            "    bloque: BEACON");

    private void lectura(SuperBeaconPlugin modulo) throws Exception {
        YamlConfiguration yml = new YamlConfiguration();
        yml.loadFromString(CONFIG_CON_ERRORES);
        List<String> errores = new ArrayList<>();
        Map<String, TipoBaliza> tipos = LectorTipos.leer(yml.getConfigurationSection("tipos"), modulo.clasesPorId(), errores);
        igual("con errores se cargan los dos tipos sanos", List.of("bueno", "grande"), new ArrayList<>(tipos.keySet()));
        TipoBaliza bueno = tipos.get("bueno");
        igual("de \"bueno\" quedan prisa, vida y raro", List.of("prisa", "vida", "raro"),
                bueno == null ? null : new ArrayList<>(bueno.efectos.keySet()));
        ok("un atributo que no existe sale como no disponible",
                bueno != null && bueno.efectos.get("raro") != null && bueno.efectos.get("raro").falta() != null);
        ok("y el que existe, disponible", bueno != null && bueno.efectos.get("vida").falta() == null);
        TipoBaliza grande = tipos.get("grande");
        igual("un radio enorme se queda en el maximo", LectorTipos.RADIO_MAX, grande == null ? null : grande.radio);
        igual("un beneficia raro cae a dueno", TipoBaliza.Beneficia.DUENO, grande == null ? null : grande.beneficia);
        ok("se avisa del bloque que no existe", contiene(errores, "sinbloque.bloque"));
        ok("se avisa de la clase inventada", contiene(errores, "efectos.mal.tipo"));
        ok("se avisa de minions", contiene(errores, "efectos.minions"));
        ok("se avisa del multiplicador que no multiplica", contiene(errores, "efectos.cero"));
        ok("se avisa de la pocion que no existe", contiene(errores, "efectos.nopocion"));
        ok("se avisa del atributo que no existe", contiene(errores, "efectos.raro"));
        ok("se avisa del radio", contiene(errores, "grande.radio"));
        ok("se avisa del nombre con espacios", contiene(errores, "Con Espacios"));

        YamlConfiguration serie = recurso(modulo);
        if (serie == null) {
            ok("el jar trae superbeacon/config.yml", false);
            return;
        }
        List<String> erroresSerie = new ArrayList<>();
        Map<String, TipoBaliza> deSerie = LectorTipos.leer(serie.getConfigurationSection("tipos"), modulo.clasesPorId(),
                erroresSerie);
        ok("el config de serie no tiene errores" + (erroresSerie.isEmpty() ? "" : ": " + erroresSerie.get(0)),
                erroresSerie.isEmpty());
        igual("y trae los cuatro tipos", List.of("hogar", "granja", "guerra", "trofeo"), new ArrayList<>(deSerie.keySet()));
        TipoBaliza trofeo = deSerie.get("trofeo");
        ok("el trofeo: 30 dias, de clan, no transferible, todos activos, no cuenta en el maximo",
                trofeo != null && trofeo.duracionDias == 30 && trofeo.beneficia == TipoBaliza.Beneficia.CLAN
                        && !trofeo.transferible && trofeo.fijo() && !trofeo.cuentaEnElMaximo);
    }

    private static boolean contiene(List<String> errores, String trozo) {
        for (String e : errores) if (e.contains(trozo)) return true;
        return false;
    }

    private static YamlConfiguration recurso(SuperBeaconPlugin modulo) throws Exception {
        try (InputStream in = modulo.getResource("config.yml")) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private void objeto(SuperBeaconPlugin modulo) {
        TipoBaliza t = modulo.tipos().values().stream().findFirst().orElse(null);
        if (t == null) {
            ok("hay al menos un tipo cargado para probar el objeto", false);
            return;
        }
        Objeto o = modulo.objeto();
        List<String> elegidos = t.efectos.isEmpty() ? List.of() : List.of(t.efectos.keySet().iterator().next());
        Ficha f = new Ficha(UUID.randomUUID(), t.id, UUID.randomUUID(), "Prueba", "ABC",
                System.currentTimeMillis() + 3 * Tiempo.DIA, elegidos);
        ItemStack it = o.crear(f, Material.BEACON);
        igual("ida y vuelta: crear y leer da la misma ficha", f, o.leer(it));
        igual("el id se lee solo", f.id(), o.id(it));
        igual("el material es el del tipo", t.bloque, it.getType());
        igual("no se apila", 1, it.getMaxStackSize());

        Ficha libre = new Ficha(UUID.randomUUID(), t.id, null, null, null, 0, List.of());
        igual("sin dueño, sin clan y permanente tambien vuelve igual", libre, o.leer(o.crear(libre, Material.BEACON)));
        igual("un objeto del mismo material sin PDC no es nuestro", null, o.leer(new ItemStack(t.bloque)));
        igual("ni un faro vanilla", null, o.leer(new ItemStack(Material.BEACON)));
    }
}
