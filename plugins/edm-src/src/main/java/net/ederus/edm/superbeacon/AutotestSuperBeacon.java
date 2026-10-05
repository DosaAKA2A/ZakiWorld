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
 *   - la limpieza del clan que devuelve PlaceholderAPI;
 *   - el clan que manda: el fijado en el give frente al actual del dueño (y su cache);
 *   - el vuelo: la marca de combate y la espera cuando otro plugin lo quita;
 *   - los huecos vigilados (posiciones vaciadas) sobre un data.yml temporal;
 *   - en que mundos se puede colocar, y el nombre del dueño al dia;
 *   - la presentacion: la semana del trofeo, las fechas, los numeros, el corte de
 *     lineas a 38 caracteres y el alto del menu;
 *   - el lore del objeto (1.79): un solo color en tres tonos, bloques sin rayas, el
 *     nombre en degradado, sin negrita, y lo mismo con los respaldos que con el
 *     mensajes.yml de serie.
 * Y dentro del servidor (modulo en marcha):
 *   - leer tipos de un config con errores, y que el config de serie no tenga ninguno;
 *   - la ida y vuelta del objeto: crear, leer el PDC y que salga lo mismo (semana incluida);
 *   - que ningun lore de serie pase de 38 caracteres y que cada efecto explique que da.
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
            t.clanActual();
            t.vuelo();
            t.vaciadas();
            t.mundos();
            t.nombres();
            t.presentacion();
            t.lore();
            t.renovar();
            if (modulo != null) {
                t.lectura(modulo);
                t.objeto(modulo);
            }
        } catch (Throwable e) {
            t.ok("el selftest no deberia reventar: " + e, false);
        }
        return t;
    }

    /* ======================================================= renovar (1.78.1) */

    private void renovar() {
        long ahora = 1_000_000_000L;
        long dia = Tiempo.DIA;
        igual("renovar: vencida hace un dia, 7 dias desde ahora", ahora + 7 * dia,
                SuperBeaconPlugin.venceRenovado(ahora - dia, ahora, 7));
        igual("renovar: con 3 dias por delante, pasa a 7 desde ahora", ahora + 7 * dia,
                SuperBeaconPlugin.venceRenovado(ahora + 3 * dia, ahora, 7));
        igual("renovar: no acorta una que vence mas lejos", ahora + 20 * dia,
                SuperBeaconPlugin.venceRenovado(ahora + 20 * dia, ahora, 7));
        igual("renovar: una que no caducaba sigue sin caducar", 0L, SuperBeaconPlugin.venceRenovado(0L, ahora, 7));
        igual("renovar: 0 dias = ya no caduca", 0L, SuperBeaconPlugin.venceRenovado(ahora + dia, ahora, 0));
        ok("renovar: mismo clan sin colores ni mayusculas", SuperBeaconPlugin.delClan("ABC", "trofeo", "&#FF0000abc", "trofeo"));
        ok("renovar: cualquier tipo si no se pide", SuperBeaconPlugin.delClan("ABC", "guerra", "abc", null));
        ok("renovar: otro tipo no", !SuperBeaconPlugin.delClan("ABC", "guerra", "abc", "trofeo"));
        ok("renovar: otro clan no", !SuperBeaconPlugin.delClan("ABD", "trofeo", "abc", "trofeo"));
        ok("renovar: sin clan fijado no", !SuperBeaconPlugin.delClan(null, "trofeo", "abc", "trofeo"));
    }

    /* ================================================================ fusion */

    /** Un efecto de mentira: solo su grupo y su fuerza, que es lo que mira la fusion. */
    private static Efecto falso(String clave, String grupo, double fuerza) {
        return falso(clave, grupo, fuerza, clave);
    }

    private static Efecto falso(String clave, String grupo, double fuerza, String nombre) {
        return new Efecto(clave, nombre, Material.STONE) {
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

    /* ============================================================ clan actual */

    private void clanActual() {
        igual("clan fijado en el give: manda aunque el dueño este en otro", "ABC",
                Motor.clanBeneficiario("ABC", true, "XYZ", "XYZ"));
        igual("sin fijar y el dueño conectado: su clan de ahora", "XYZ",
                Motor.clanBeneficiario(null, true, "XYZ", "ABC"));
        igual("sin fijar y el dueño desconectado: el ultimo apuntado", "ABC",
                Motor.clanBeneficiario(null, false, null, "ABC"));
        igual("conectado y ya sin clan: ninguno, aunque antes tuviera", null,
                Motor.clanBeneficiario(null, true, null, "ABC"));
        Baliza b = new Baliza(new Ficha(UUID.randomUUID(), "t", UUID.randomUUID(), "x", null, 0, List.of()),
                "w", 0, 64, 0, Material.BEACON, 0);
        b.clanDueno = "ABC";
        igual("el clan del dueño no viaja con el objeto", null, b.ficha().clan());
        Baliza trofeo = new Baliza(new Ficha(UUID.randomUUID(), "t", UUID.randomUUID(), "x", "ABC", 0, List.of()),
                "w", 0, 64, 0, Material.BEACON, 0);
        igual("el fijado en el give si viaja", "ABC", trofeo.ficha().clan());
    }

    /* ================================================================= vuelo */

    private void vuelo() {
        long t0 = 1_000_000L;
        long combate = ClaseVuelo.marca(t0, 15);
        igual("la marca de combate dura sus 15 s", t0 + 15_000L, combate);
        igual("dentro de la marca no se vuela", true, ClaseVuelo.bloqueado(t0 + 14_999L, combate));
        igual("cumplida, se vuelve a volar", false, ClaseVuelo.bloqueado(t0 + 15_000L, combate));
        igual("con 0 segundos no hay marca", 0L, ClaseVuelo.marca(t0, 0));
        igual("y una marca apagada no bloquea", false, ClaseVuelo.bloqueado(t0, ClaseVuelo.marca(t0, 0)));
        igual("sin marca no bloquea", false, ClaseVuelo.bloqueado(t0, null));
        igual("era nuestro, ya no vuela y no fue el juego: se lo quito otro plugin", true,
                ClaseVuelo.quitadoPorOtro(true, false, false));
        igual("si fue el juego (reaparecer, cambiar de modo), no", false, ClaseVuelo.quitadoPorOtro(true, false, true));
        igual("si aun puede volar, nadie se lo quito", false, ClaseVuelo.quitadoPorOtro(true, true, false));
        igual("si no era nuestro, no es cosa nuestra", false, ClaseVuelo.quitadoPorOtro(false, false, false));
        long reintento = ClaseVuelo.marca(t0, 30);
        igual("quitado por otro plugin: 30 s sin devolverselo", true, ClaseVuelo.bloqueado(t0 + 29_000L, reintento));
        igual("pasados los 30 s, se le devuelve", false, ClaseVuelo.bloqueado(t0 + 30_001L, reintento));
    }

    /* ============================================================== vaciadas */

    private static java.util.logging.Logger silencioso() {
        java.util.logging.Logger l = java.util.logging.Logger.getAnonymousLogger();
        l.setUseParentHandlers(false);
        return l;
    }

    private void vaciadas() throws Exception {
        java.io.File tmp = java.io.File.createTempFile("superbeacon-selftest", ".yml");
        try {
            tmp.delete();
            Registro r = new Registro(tmp, silencioso());
            UUID id = UUID.randomUUID();
            Baliza b = new Baliza(new Ficha(id, "t", UUID.randomUUID(), "x", null, 0, List.of()),
                    "w", 10, 64, -5, Material.BEACON, 0);
            r.poner(b);
            igual("colocada: su sitio no es un hueco", null, r.vaciadaEn("w", 10, 64, -5));
            r.quitar(b);
            Registro.Vaciada v = r.vaciadaEn("w", 10, 64, -5);
            ok("recogida: su sitio queda vigilado, con su id", v != null && v.id().equals(id));
            igual("un faro sin baliza en el hueco es un huerfano", true, Registro.huerfano(Material.BEACON, v, false));
            igual("con una baliza registrada ahi, no", false, Registro.huerfano(Material.BEACON, v, true));
            igual("otro bloque en el hueco, no", false, Registro.huerfano(Material.STONE, v, false));
            igual("el hueco se encuentra por su chunk", 1, r.vaciadasEnChunk("w", 0, -1).size());
            igual("y no en otro chunk", 0, r.vaciadasEnChunk("w", 1, -1).size());

            r.guardar();
            Registro r2 = new Registro(tmp, silencioso());
            r2.cargar();
            ok("el hueco sobrevive a un reinicio (data.yml)", r2.vaciadaEn("w", 10, 64, -5) != null);
            igual("a los 7 dias se olvida", 1,
                    r2.podarVaciadas(System.currentTimeMillis() + Registro.VACIADA_VIDA_MS + 60_000L));
            igual("y ya no se vigila", null, r2.vaciadaEn("w", 10, 64, -5));

            ok("si un jugador coloca algo en el hueco, se olvida",
                    r.olvidarVaciada("w", 10, 64, -5) && r.vaciadaEn("w", 10, 64, -5) == null);
            r.poner(b);
            r.quitar(b);
            Baliza otra = new Baliza(new Ficha(UUID.randomUUID(), "t", UUID.randomUUID(), "y", null, 0, List.of()),
                    "w", 10, 64, -5, Material.BEACON, 0);
            r.poner(otra);
            igual("una baliza nueva en el hueco tambien lo borra", null, r.vaciadaEn("w", 10, 64, -5));
        } finally {
            tmp.delete();
            new java.io.File(tmp.getPath() + ".tmp").delete();
        }
    }

    /* ================================================================ mundos */

    private void mundos() {
        Set<String> ninguno = Set.of(), soloWorld = Set.of("world");
        igual("sin lista de permitidos: en cualquiera", true, SuperBeaconPlugin.mundoPermitido("lethal", ninguno, ninguno));
        igual("con [world]: en world si", true, SuperBeaconPlugin.mundoPermitido("world", soloWorld, ninguno));
        igual("sin mirar mayusculas", true, SuperBeaconPlugin.mundoPermitido("World", soloWorld, ninguno));
        igual("con [world]: en otro mundo no", false, SuperBeaconPlugin.mundoPermitido("calamity", soloWorld, ninguno));
        igual("los excluidos mandan sobre los permitidos", false,
                SuperBeaconPlugin.mundoPermitido("world", soloWorld, Set.of("world")));
        igual("sin permitidos, un excluido sigue sin valer", false,
                SuperBeaconPlugin.mundoPermitido("calamity", ninguno, Set.of("calamity")));
    }

    /* =============================================================== nombres */

    /** Un jugador de mentira: solo su UUID y su nombre, que es lo que mira Registro.ligar. */
    private static org.bukkit.entity.Player jugador(UUID id, String nombre) {
        return (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.entity.Player.class.getClassLoader(), new Class<?>[]{org.bukkit.entity.Player.class},
                (px, m, args) -> switch (m.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> nombre;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> px == args[0];
                    case "toString" -> "jugador de prueba " + nombre;
                    default -> m.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });
    }

    private void nombres() {
        Registro r = new Registro(new java.io.File("superbeacon-selftest-no-se-escribe.yml"), silencioso());
        UUID u = UUID.randomUUID();
        Baliza suya = new Baliza(new Ficha(UUID.randomUUID(), "t", u, "Viejo", null, 0, List.of()),
                "w", 0, 64, 0, Material.BEACON, 0);
        Baliza porNombre = new Baliza(new Ficha(UUID.randomUUID(), "t", null, "Pepe", null, 0, List.of()),
                "w", 40, 64, 40, Material.BEACON, 0);
        r.poner(suya);
        r.poner(porNombre);
        igual("al entrar con otro nombre, toca su baliza", 1, r.ligar(jugador(u, "Nuevo")).size());
        igual("y su baliza apunta el nombre nuevo", "Nuevo", suya.duenoNombre);
        UUID pepe = UUID.randomUUID();
        r.ligar(jugador(pepe, "pepe"));
        igual("la entregada por nombre queda ligada a su UUID", pepe, porNombre.dueno);
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
            "    bloque: BEACON",
            "  medio:",
            "    bloque: BEACON",
            "    duracion-dias: 0.5",
            "  textual:",
            "    bloque: BEACON",
            "    duracion-dias: 30d");

    /* =========================================================== presentacion */

    private void presentacion() {
        igual("el acento en codigo &", "&#FFC857", Presentacion.hex(0xFFC857));
        igual("romanos", "IV", Presentacion.romano(4));
        igual("x1,5 es un 50 %", "50 %", Presentacion.porcentaje(1.5));
        igual("x1,25 es un 25 %", "25 %", Presentacion.porcentaje(1.25));
        igual("12 de vida son 6 corazones", "6 corazones", Presentacion.corazones(12));
        igual("2 de vida es 1 corazón", "1 corazón", Presentacion.corazones(2));
        igual("1 de vida es medio", "0,5 corazones", Presentacion.corazones(1));

        ZoneId utc = ZoneId.of("UTC");
        long lunes = java.time.LocalDate.of(2026, 9, 28).toEpochDay();
        // domingo 04/10/2026 22:55 UTC: el ranking acaba de cerrar esa misma semana
        long domingo = java.time.ZonedDateTime.of(2026, 10, 4, 22, 55, 0, 0, utc).toInstant().toEpochMilli();
        igual("un domingo cuenta la semana de ese domingo", lunes, Presentacion.semanaCerrada(domingo, utc));
        igual("el lunes siguiente, la que acaba de cerrar", lunes, Presentacion.semanaCerrada(domingo + Tiempo.HORA * 2, utc));
        igual("y el sabado, todavia esa", lunes, Presentacion.semanaCerrada(domingo + 6 * Tiempo.DIA - Tiempo.HORA, utc));
        igual("el domingo siguiente ya es la nueva", lunes + 7, Presentacion.semanaCerrada(domingo + 7 * Tiempo.DIA, utc));
        igual("la semana se escribe de lunes a domingo", List.of("28/09", "04/10"), List.of(Presentacion.semana(lunes)));
        igual("fecha con dia de la semana", "domingo 11/10, 22:55",
                Presentacion.fecha(domingo + 7 * Tiempo.DIA, utc, domingo));
        igual("con el año si no es el de ahora", "domingo 03/01/2027, 22:55",
                Presentacion.fecha(java.time.ZonedDateTime.of(2027, 1, 3, 22, 55, 0, 0, utc).toInstant().toEpochMilli(),
                        utc, domingo));

        igual("los codigos no cuentan como letras", 4, Presentacion.largo("&#FFFFFFab&7c&x&1&2&3&4&5&6d"));
        igual("mayuscula saltando el color", "&#C4C4C4Semana", Presentacion.mayuscula("&#C4C4C4semana"));
        igual("el subtitulo va sin punto", "Para tu base", Presentacion.sinPunto("Para tu base."));
        List<String> partes = Presentacion.partir(
                "&#C4C4C4Los jefes y esbirros te pagan un 50 % más de MobCoins mientras estés en su alcance.", 38);
        boolean caben = !partes.isEmpty();
        for (String l : partes) caben &= Presentacion.largo(l) <= 38 && l.startsWith("&#C4C4C4");
        ok("partir: lineas de 38 como mucho y cada una con su color (" + partes.size() + " lineas)",
                caben && partes.size() == 3);
        igual("partir no pierde palabras",
                "Los jefes y esbirros te pagan un 50 % más de MobCoins mientras estés en su alcance.",
                String.join(" ", partes).replace("&#C4C4C4", ""));

        igual("menu de 27 con siete efectos", 27, MenuBaliza.tamano(7));
        igual("menu de 36 con ocho", 36, MenuBaliza.tamano(8));
        boolean dentro = true;
        for (int n = 0; n <= 14; n++) {
            int tam = MenuBaliza.tamano(n);
            Set<Integer> vistas = new java.util.HashSet<>();
            for (int c : MenuBaliza.casillas(n)) {
                dentro &= c >= 9 && c < tam - 9 && c % 9 != 0 && c % 9 != 8 && vistas.add(c);
            }
        }
        ok("los efectos caben entre la ficha y los botones, sin tocar el marco ni repetirse", dentro);
        igual("cinco efectos centrados en la segunda fila", List.of(11, 12, 13, 14, 15), lista(MenuBaliza.casillas(5)));
        igual("ocho: cuatro y cuatro", List.of(10, 12, 14, 16, 19, 21, 23, 25), lista(MenuBaliza.casillas(8)));

        Ficha vieja = new Ficha(UUID.randomUUID(), "t", null, null, null, 0, List.of());
        igual("una ficha sin semana (objetos viejos) queda en 0", 0L, vieja.semana());
        Ficha conSemana = new Ficha(UUID.randomUUID(), "t", UUID.randomUUID(), "x", "ABC", 1, List.of(), lunes);
        igual("la semana sobrevive a cambiar el dueño", lunes, conSemana.conDueno(UUID.randomUUID(), "y").semana());
        igual("y a elegir efectos", lunes, conSemana.conElegidos(List.of("a")).semana());
        igual("y pasa por la baliza colocada", lunes,
                new Baliza(conSemana, "w", 0, 64, 0, Material.BEACON, 0).ficha().semana());
    }

    /* ================================================================== lore */

    private static final java.util.regex.Pattern HEX = java.util.regex.Pattern.compile("&#([0-9A-Fa-f]{6})");

    private static String plano(String l) {
        return Presentacion.plano(l);
    }

    private static List<String> planas(List<String> l) {
        List<String> out = new ArrayList<>();
        for (String x : l) out.add(plano(x));
        return out;
    }

    private static TipoBaliza tipoLore(int color, TipoBaliza.Beneficia b, double dias, int elegibles, boolean semanal) {
        Map<String, Efecto> ef = new LinkedHashMap<>();
        ef.put("vida", falso("vida", "a", 1, "+6 corazones"));
        ef.put("prisa", falso("prisa", "b", 1, "Prisa IV"));
        ef.put("habilidades", falso("habilidades", "c", 1, "Experiencia de habilidades x1,5"));
        return new TipoBaliza("prueba", Presentacion.hex(color) + "&lNombre de Prueba", Material.BEACON, 48, dias,
                TipoBaliza.AlCaducar.APAGAR, b, !semanal, !semanal, elegibles,
                List.of("Una frase de prueba que es lo bastante larga para partirse en dos lineas."), ef, null, 0, 0,
                semanal);
    }

    private void lore() throws Exception {
        ZoneId utc = ZoneId.of("UTC");
        long ahora = java.time.ZonedDateTime.of(2026, 10, 4, 22, 55, 0, 0, utc).toInstant().toEpochMilli();
        long vence = java.time.ZonedDateTime.of(2026, 10, 11, 22, 0, 0, 0, utc).toInstant().toEpochMilli();
        igual("lore: la fecha", "domingo 11/10 a las 22:00", Presentacion.fechaLore(vence, utc, ahora));
        igual("lore: mezcla a blanco a la mitad", 0xFFE4AB, Presentacion.mezcla(0xFFC857, 0xFFFFFF, 0.5));

        LoreBaliza respaldo = new LoreBaliza((k, r) -> r, utc);
        int oro = 0xFFC857;
        TipoBaliza trofeo = tipoLore(oro, TipoBaliza.Beneficia.CLAN, 7, 0, true);
        Ficha ft = new Ficha(UUID.randomUUID(), "prueba", UUID.randomUUID(), "Dosa__", "TEST", vence,
                List.of(), 20_724L);
        List<String> l = respaldo.lore(ft, trofeo, ahora);
        igual("lore: el trofeo, linea a linea", List.of(
                "Campeón de la semana · Clan TEST",
                "",
                "\"Una frase de prueba que es lo",
                " bastante larga para partirse en dos",
                " lineas.\"",
                "",
                "◆ Efectos para tu clan",
                " ✦ +6 corazones",
                " ✦ Prisa IV",
                " ✦ Experiencia de habilidades x1,5",
                "A 48 bloques a la redonda.",
                "",
                "Líder: Dosa__",
                "Vence el domingo 11/10 a las 22:00.",
                "",
                "Colócalo y haz clic derecho para abrir",
                "su menú. Solo el líder lo mueve."), planas(l));

        Set<String> permitidos = Set.of(Presentacion.hex(oro), Presentacion.hex(LoreBaliza.palido(oro)),
                LoreBaliza.BLANCO, LoreBaliza.GRIS, LoreBaliza.APAGADO);
        List<String> ajenos = new ArrayList<>();
        for (String x : l) {
            java.util.regex.Matcher m = HEX.matcher(x);
            while (m.find()) {
                String c = "&#" + m.group(1).toUpperCase(java.util.Locale.ROOT);
                if (!permitidos.contains(c)) ajenos.add(c);
            }
        }
        igual("lore: un solo color en dos tonos, mas blanco y gris", List.of(), ajenos);
        ok("lore: sin rayas, sin negrita y sin la semana", l.stream().noneMatch(x -> x.contains("─")
                || x.toLowerCase(java.util.Locale.ROOT).contains("&l") || plano(x).matches(".*\\d\\d/\\d\\d al .*")));
        ok("lore: ninguna linea pasa de 38", l.stream().allMatch(x -> Presentacion.largo(x) <= Presentacion.ANCHO));

        String nombre = LoreBaliza.nombre(trofeo);
        ok("lore: el nombre sin negrita y del claro al fuerte",
                !nombre.toLowerCase(java.util.Locale.ROOT).contains("&l")
                        && nombre.startsWith(Presentacion.hex(LoreBaliza.claro(oro)) + "N")
                        && nombre.endsWith(Presentacion.hex(oro) + "a")
                        && plano(nombre).equals("Nombre de Prueba"));

        TipoBaliza hogar = tipoLore(0x7EC8FF, TipoBaliza.Beneficia.DUENO, 0, 2, false);
        String azul = Presentacion.hex(0x7EC8FF);
        Ficha fh = new Ficha(UUID.randomUUID(), "prueba", UUID.randomUUID(), "Dosa__", null, 0, List.of("prisa"));
        List<String> lh = respaldo.lore(fh, hogar, ahora);
        igual("lore: uno normal, categoria y para quien", "Super Beacon · Personal", plano(lh.get(0)));
        ok("lore: el elegido brilla y los demas, apagados",
                lh.contains(" " + azul + "✦ " + LoreBaliza.BLANCO + "Prisa IV")
                        && lh.contains(" " + LoreBaliza.APAGADO + "✦ " + LoreBaliza.GRIS + "+6 corazones"));
        ok("lore: el alcance dice cuantos se eligen", lh.contains(LoreBaliza.GRIS + "A 48 bloques a la redonda. Elige 2."));
        ok("lore: permanente", planas(lh).contains("Duración: permanente"));
        Ficha libre = new Ficha(UUID.randomUUID(), "prueba", null, null, null, 0, List.of());
        List<String> ll = respaldo.lore(libre, hogar, ahora);
        ok("lore: sin elegir nada, todos brillan", ll.contains(" " + azul + "✦ " + LoreBaliza.BLANCO + "+6 corazones"));
        ok("lore: sin dueño, quien lo coloque primero, y se vuelve tuyo",
                planas(ll).contains("Dueño: quien lo coloque primero")
                        && String.join(" ", planas(ll)).contains("Se vuelve tuyo al colocarlo."));
        Ficha vencida = new Ficha(UUID.randomUUID(), "prueba", UUID.randomUUID(), "Dosa__", "TEST", ahora - Tiempo.DIA,
                List.of(), 20_724L);
        ok("lore: vencido, Venció el...",
                planas(respaldo.lore(vencida, trofeo, ahora)).contains("Venció el sábado 03/10 a las 22:55."));
        TipoBaliza guerra = tipoLore(0xFF7B6B, TipoBaliza.Beneficia.CLAN, 0, 3, false);
        igual("lore: uno de clan sin clan fijado", "Super Beacon · De clan",
                plano(respaldo.lore(new Ficha(UUID.randomUUID(), "prueba", UUID.randomUUID(), "Dosa__", null, 0,
                        List.of()), guerra, ahora).get(0)));

        // Los mismos textos con el mensajes.yml de serie que con los respaldos del codigo.
        YamlConfiguration msg = new YamlConfiguration();
        try (InputStream in = AutotestSuperBeacon.class.getClassLoader().getResourceAsStream("superbeacon/mensajes.yml")) {
            if (in == null) {
                ok("el jar trae superbeacon/mensajes.yml", false);
                return;
            }
            msg.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        LoreBaliza serie = new LoreBaliza((k, r) -> msg.getString(k, r), utc);
        igual("lore: mensajes.yml de serie = respaldos (trofeo)", l, serie.lore(ft, trofeo, ahora));
        igual("lore: mensajes.yml de serie = respaldos (elegido)", lh, serie.lore(fh, hogar, ahora));
        igual("lore: mensajes.yml de serie = respaldos (libre)", ll, serie.lore(libre, hogar, ahora));
        List<String> malas = new ArrayList<>();
        for (String id : List.of("hogar", "granja", "guerra", "fortuna", "trofeo")) {
            String f = msg.getString("frase-" + id);
            if (f == null || f.isBlank()) malas.add(id);
        }
        igual("lore: cada tipo de serie tiene su frase", List.of(), malas);
    }

    private static List<Integer> lista(int[] a) {
        List<Integer> out = new ArrayList<>();
        for (int x : a) out.add(x);
        return out;
    }

    private void lectura(SuperBeaconPlugin modulo) throws Exception {
        YamlConfiguration yml = new YamlConfiguration();
        yml.loadFromString(CONFIG_CON_ERRORES);
        List<String> errores = new ArrayList<>();
        Map<String, TipoBaliza> tipos = LectorTipos.leer(yml.getConfigurationSection("tipos"), modulo.clasesPorId(), errores);
        igual("con errores se cargan los tipos sanos", List.of("bueno", "grande", "medio"), new ArrayList<>(tipos.keySet()));
        TipoBaliza medio = tipos.get("medio");
        igual("duracion-dias con decimales: 0.5 son 12 h, no \"no caduca\"", 0.5, medio == null ? null : medio.duracionDias);
        ok("una duracion que no es un numero deja el tipo fuera y avisa",
                !tipos.containsKey("textual") && contiene(errores, "textual.duracion-dias"));
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
        igual("y trae los cinco tipos", List.of("hogar", "granja", "guerra", "fortuna", "trofeo"),
                new ArrayList<>(deSerie.keySet()));
        TipoBaliza trofeo = deSerie.get("trofeo");
        ok("el trofeo: 7 dias, de clan, no transferible, todos activos, no cuenta en el maximo, semanal",
                trofeo != null && trofeo.duracionDias == 7 && trofeo.beneficia == TipoBaliza.Beneficia.CLAN
                        && !trofeo.transferible && trofeo.fijo() && !trofeo.cuentaEnElMaximo && trofeo.semanal);
        ok("solo el trofeo es semanal", deSerie.values().stream().filter(x -> x.semanal).count() == 1);
        if (trofeo != null) {
            List<String> orden = new ArrayList<>();
            for (Efecto e : Presentacion.agrupados(trofeo.efectos.values())) orden.add(e.clave());
            igual("el lore agrupa: vida, movimiento (prisa, vuelo), boosts",
                    List.of("vida", "prisa", "vuelo", "habilidades", "mobcoins"), orden);
        }
        List<String> sinFrase = new ArrayList<>();
        List<String> anchas = new ArrayList<>();
        for (TipoBaliza t : deSerie.values()) {
            for (Efecto e : t.efectos.values()) {
                String que = e.que();
                if (que == null || que.isBlank()) sinFrase.add(t.id + "." + e.clave());
                for (String l : Presentacion.partir(Presentacion.PROSA + que, Presentacion.ANCHO)) {
                    if (Presentacion.largo(l) > Presentacion.ANCHO) anchas.add(t.id + "." + e.clave());
                }
            }
        }
        igual("cada efecto de serie dice que da", List.of(), sinFrase);
        igual("y su frase cabe en 38 caracteres por linea", List.of(), anchas);
        // Un multiplicador permanente pegado a un sitio infla la economia: solo en los que caducan.
        List<String> permanentesConBoost = new ArrayList<>();
        for (TipoBaliza t : deSerie.values()) {
            if (t.duracionDias > 0) continue;
            for (Efecto e : t.efectos.values()) {
                if (e instanceof ClaseBoost.Multi) permanentesConBoost.add(t.id + "." + e.clave());
            }
        }
        igual("ningun tipo permanente de serie trae boosts", List.of(), permanentesConBoost);
        TipoBaliza fortuna = deSerie.get("fortuna");
        ok("la Fortuna: 7 dias, se destruye, de su dueño, elige 2 de 4 boosts",
                fortuna != null && fortuna.duracionDias == 7 && fortuna.alCaducar == TipoBaliza.AlCaducar.DESTRUIR
                        && fortuna.beneficia == TipoBaliza.Beneficia.DUENO && fortuna.elegibles == 2
                        && fortuna.efectos.size() == 4
                        && fortuna.efectos.values().stream().allMatch(e -> e instanceof ClaseBoost.Multi));
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

        Ficha trofeo = new Ficha(UUID.randomUUID(), t.id, UUID.randomUUID(), "Prueba", "ABC",
                System.currentTimeMillis() + 7 * Tiempo.DIA, List.of(), 20_724L);
        igual("la semana del trofeo viaja en el PDC", trofeo, o.leer(o.crear(trofeo, Material.BEACON)));

        List<String> anchas = new ArrayList<>();
        for (TipoBaliza x : modulo.tipos().values()) {
            for (Ficha fx : List.of(
                    new Ficha(UUID.randomUUID(), x.id, UUID.randomUUID(), "Dosa__", "ABC",
                            System.currentTimeMillis() + 7 * Tiempo.DIA, List.copyOf(x.efectos.keySet()), 20_724L),
                    new Ficha(UUID.randomUUID(), x.id, null, null, null, 0, List.of()))) {
                for (net.kyori.adventure.text.Component c : o.lore(fx, x)) {
                    String plano = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                            .plainText().serialize(c);
                    if (plano.length() > Presentacion.ANCHO) anchas.add(x.id + ": " + plano);
                }
            }
        }
        igual("ninguna linea de lore de los tipos cargados pasa de 38", List.of(), anchas);

        Ficha libre = new Ficha(UUID.randomUUID(), t.id, null, null, null, 0, List.of());
        igual("sin dueño, sin clan y permanente tambien vuelve igual", libre, o.leer(o.crear(libre, Material.BEACON)));
        igual("un objeto del mismo material sin PDC no es nuestro", null, o.leer(new ItemStack(t.bloque)));
        igual("ni un faro vanilla", null, o.leer(new ItemStack(Material.BEACON)));
    }
}
