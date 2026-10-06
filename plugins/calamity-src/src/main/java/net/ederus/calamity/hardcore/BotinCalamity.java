package net.ederus.calamity.hardcore;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.DoubleSupplier;

/**
 * Calamity 1.11 · El botin de las ruinas y de las bovedas: una tabla como la de cofres.anadir
 * ({objeto, prob, min, max}) que sube con la distancia al spawn.
 *
 * Objetos: esencia, reliquia-1..4, cristal, frasco-1, tintura, llave-umbral, llave-ominosa y
 * "material:PAN" para lo vanilla (pan, flechas...). Cada distancia-cada bloques del borde de la zona
 * spawn es un escalon (hasta extra.tope): cada escalon multiplica la probabilidad de cada fila por
 * (1 + extra.prob) acumulado y sube su maximo en extra.cantidad (redondeado hacia abajo). Por bioma,
 * un factor opcional sobre la probabilidad (biomas: {crimson_organism: 1.25}).
 *
 * El reparto es puro (tirar, con el azar que se le pase) para que lo pruebe el autotest; entregar
 * hace los objetos y paga lo que es dinero por la Aduana: las Esencias y las Reliquias nunca van a
 * un cofre ni a una boveda, van a quien abre, con Aduana.pagar (regla 7).
 *
 * Calamity 1.16.4 · Los cofres comunes (los de ruina y los de estructura: los que no piden llave) se
 * tiran con tirarComun: nunca dan Reliquias (las Reliquias son de las bovedas con llave) y lo raro
 * (Llave del Umbral, Llave Ominosa, Cristal de Regreso, Frasco de 1 trago, o lo que diga su lista
 * raros o una fila con raro: true) sale de a 1 y como mucho uno por cofre. Las bovedas siguen con
 * tirar, sin esos topes: son el premio de la llave.
 */
final class BotinCalamity {

    /** Lo raro de serie de un cofre comun (cofres.raros, ruinas.cofres.raros). */
    static final List<String> RAROS_DE_SERIE = List.of("llave-umbral", "llave-ominosa", "cristal", "frasco-1");

    /** Lo que sale de una fila: el objeto y cuantos. */
    record Tirada(String objeto, int n) {
    }

    /** Lo que se entrego: Esencias pagadas, Reliquias, objetos fisicos y el resumen para la Bitacora. */
    record Entrega(int esencias, int reliquias, List<ItemStack> objetos, Map<String, Integer> resumen) {
    }

    private BotinCalamity() {
    }

    /** Escalones de distancia: floor(bloques / cada), hasta tope. */
    static int escalones(double bloques, int cada, int tope) {
        if (cada <= 0 || bloques <= 0) return 0;
        return Math.max(0, Math.min(Math.max(0, tope), (int) Math.floor(bloques / cada)));
    }

    /** Las filas de una seccion (lista de mapas), o las de serie si no hay. */
    static List<Map<?, ?>> filas(ConfigurationSection c, String ruta, List<Map<?, ?>> deSerie) {
        return c != null && c.isList(ruta) ? c.getMapList(ruta) : deSerie;
    }

    /**
     * Tira la tabla. escalones de distancia, extraProb y extraCant por escalon, factor del bioma sobre
     * la probabilidad. Una probabilidad pasada de 1 se queda en 1.
     */
    static List<Tirada> tirar(List<Map<?, ?>> filas, int escalones, double extraProb, double extraCant, double factorBioma,
                              DoubleSupplier azar) {
        return tirar(filas, null, escalones, extraProb, extraCant, factorBioma, azar);
    }

    /**
     * 1.16.4 · Un cofre comun: como tirar, pero las Reliquias no salen nunca (las ponga o no el config) y lo
     * raro sale de a 1 (ni su max ni los escalones suben la cantidad) y como mucho uno por cofre: si entran
     * varios, se queda uno al azar. La probabilidad de lo raro si sube con la distancia, como la de todo.
     */
    static List<Tirada> tirarComun(List<Map<?, ?>> filas, Set<String> raros, int escalones, double extraProb,
                                   double extraCant, double factorBioma, DoubleSupplier azar) {
        return tirar(filas, raros == null ? Set.of() : raros, escalones, extraProb, extraCant, factorBioma, azar);
    }

    /** raros null: una boveda, sin las reglas del cofre comun. */
    private static List<Tirada> tirar(List<Map<?, ?>> filas, Set<String> raros, int escalones, double extraProb,
                                      double extraCant, double factorBioma, DoubleSupplier azar) {
        boolean comun = raros != null;
        List<Tirada> out = new ArrayList<>();
        // Lo raro que entra, con el sitio que le toca entre lo demas (para que el orden siga siendo el de la tabla).
        List<Tirada> rarosDentro = new ArrayList<>();
        List<Integer> sitios = new ArrayList<>();
        int e = Math.max(0, escalones);
        for (Map<?, ?> f : filas) {
            String objeto = String.valueOf(f.get("objeto")).trim().toLowerCase(Locale.ROOT);
            if (objeto.isEmpty() || objeto.equals("null")) continue;
            if (comun && esReliquia(objeto)) continue;
            double prob = decimal(f.get("prob"), 0) * Math.pow(1 + Math.max(0, extraProb), e) * Math.max(0, factorBioma);
            if (azar.getAsDouble() >= Math.min(1, prob)) continue;
            if (comun && (raros.contains(objeto) || si(f.get("raro")))) {
                rarosDentro.add(new Tirada(objeto, 1));
                sitios.add(out.size());
                continue;
            }
            int min = Math.max(1, entero(f.get("min"), 1));
            int max = Math.max(min, entero(f.get("max"), min)) + (int) Math.floor(Math.max(0, extraCant) * e);
            int n = min + (int) Math.floor(azar.getAsDouble() * (max - min + 1));
            out.add(new Tirada(objeto, Math.min(max, n)));
        }
        if (!rarosDentro.isEmpty()) {
            int i = rarosDentro.size() == 1 ? 0
                    : Math.min(rarosDentro.size() - 1, (int) Math.floor(azar.getAsDouble() * rarosDentro.size()));
            out.add(sitios.get(i), rarosDentro.get(i));
        }
        return out;
    }

    /** Cualquier Reliquia (grados I a IV y las especiales): en un cofre comun no sale nunca. */
    static boolean esReliquia(String objeto) {
        return objeto != null && objeto.trim().toLowerCase(Locale.ROOT).startsWith("reliquia");
    }

    /** Lo raro de un cofre comun (la lista en ruta), en minusculas; lo de serie si el config no la trae. */
    static Set<String> raros(ConfigurationSection c, String ruta) {
        List<String> l = c != null && c.isList(ruta) ? c.getStringList(ruta) : RAROS_DE_SERIE;
        Set<String> out = new LinkedHashSet<>();
        for (String s : l) if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    private static boolean si(Object o) {
        return o instanceof Boolean b ? b : o != null && String.valueOf(o).trim().equalsIgnoreCase("true");
    }

    /**
     * Hace los objetos y paga: Esencias y Reliquias por Aduana.pagar(tipo) a quien abre (1.11: si esta dentro, como
     * objetos en la lista, para que salgan del cofre o de la boveda); el resto
     * (cristal, frasco, tintura, llaves, vanilla) vuelve en la lista para que quien llama lo meta en
     * el cofre o en la boveda. origen va a la Reliquia y a la Bitacora.
     */
    static Entrega entregar(Hardcore hc, Player p, List<Tirada> tiradas, String tipoAduana, String origen) {
        int esencias = 0;
        List<ItemStack> reliquias = new ArrayList<>();
        List<ItemStack> objetos = new ArrayList<>();
        Map<String, Integer> resumen = new LinkedHashMap<>();
        Reliquias rel = hc.reliquias();
        for (Tirada t : tiradas) {
            int n = t.n();
            String o = t.objeto();
            boolean dado = true;
            switch (o) {
                case "esencia", "esencias" -> esencias += n;
                case "cristal" -> {
                    for (int i = 0; i < n; i++) objetos.add(hc.items().cristal());
                }
                case "frasco-1" -> {
                    for (int i = 0; i < n; i++) objetos.add(hc.items().frasco(1));
                }
                case "tintura" -> {
                    String id = hc.cfg().getString("entregas.mmo.tintura", "CALAMITY_CONSUMIBLES.TINTURA_DE_CENIZA");
                    ItemStack it = PuenteMmo.crear(id);
                    if (it == null) {
                        dado = false;
                    } else {
                        it.setAmount(Math.min(64, n));
                        objetos.add(it);
                    }
                }
                case PuenteBovedas.LLAVE_UMBRAL, PuenteBovedas.LLAVE_OMINOSA -> {
                    ItemStack it = PuenteBovedas.llave(o, n);
                    if (it == null) dado = false;
                    else objetos.add(it);
                }
                default -> {
                    if (o.startsWith("reliquia-")) {
                        int g = entero(o.substring("reliquia-".length()), 0);
                        if (rel == null || !rel.activas() || g < 1 || g > 4) {
                            dado = false;
                            break;
                        }
                        ItemStack r = rel.crear(g, origen, null, 0, null, false);
                        if (rel.id(r) == null) {
                            r.setAmount(n);
                            reliquias.add(r);
                        } else {
                            reliquias.add(r);
                            for (int i = 1; i < n; i++) reliquias.add(rel.crear(g, origen, null, 0, null, false));
                        }
                    } else if (o.startsWith("material:")) {
                        Material m = Material.matchMaterial(o.substring("material:".length()));
                        if (m == null || !m.isItem() || m.isAir()) {
                            dado = false;
                        } else {
                            objetos.add(new ItemStack(m, Math.min(m.getMaxStackSize(), n)));
                        }
                    } else {
                        dado = false;
                    }
                }
            }
            if (dado) resumen.merge(o, n, Integer::sum);
        }
        esencias = hc.esenciasDelEquipo(p, esencias);
        int pagadas = 0;
        Aduana ad = hc.aduana();
        if (ad != null && (esencias > 0 || !reliquias.isEmpty())) {
            // 1.11: las Esencias y Reliquias tambien salen del cofre o de la boveda (van con los demas objetos).
            Aduana.Pago pago = ad.pagar(p, tipoAduana, esencias, 0, reliquias, origen, false, objetos);
            pagadas = pago == null ? 0 : pago.esencias();
            Grifo g = hc.grifo();
            if (pago != null && g != null) g.destelloEsencias(p, pago.esencias(), 0);
        }
        return new Entrega(pagadas, reliquias.size(), objetos, resumen);
    }

    /** "esencia x2, cristal x1": el resumen para la Bitacora. */
    static String texto(Map<String, Integer> resumen) {
        if (resumen.isEmpty()) return "nada";
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, Integer> e : resumen.entrySet()) l.add(e.getKey() + " x" + e.getValue());
        return String.join(", ", l);
    }

    static int entero(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static double decimal(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? def : Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Factor del bioma (seccion biomas: {id: factor}); 1 si no esta. Vale con o sin namespace. */
    static double factorBioma(ConfigurationSection biomas, String bioma) {
        if (biomas == null || bioma == null) return 1;
        String k = Clima.clave(bioma);
        int barra = k.lastIndexOf('/');
        String corto = barra >= 0 ? k.substring(barra + 1) : k;
        for (String s : biomas.getKeys(false)) {
            String sk = Clima.clave(s);
            if (sk.equals(k) || sk.equals(corto)) return Math.max(0, biomas.getDouble(s, 1));
        }
        return 1;
    }

    // ----------------------------------------------------------------- autotest (puro)

    /** "esencia x2, cristal x1": las tiradas en su orden. */
    static String lista(List<Tirada> t) {
        List<String> l = new ArrayList<>();
        for (Tirada x : t) l.add(x.objeto() + " x" + x.n());
        return String.join(", ", l);
    }

    /** "reliquia-1 0.7 1-3, reliquia-2 0.5 1-2": las filas de Reliquia de una tabla, como estan. */
    static String reliquias(List<Map<?, ?>> filas) {
        List<String> l = new ArrayList<>();
        for (Map<?, ?> f : filas) {
            String o = String.valueOf(f.get("objeto")).trim().toLowerCase(Locale.ROOT);
            if (!esReliquia(o)) continue;
            int min = Math.max(1, entero(f.get("min"), 1));
            l.add(o + " " + decimal(f.get("prob"), 0) + " " + min + "-" + Math.max(min, entero(f.get("max"), min)));
        }
        return String.join(", ", l);
    }

    /** Toda la tabla como texto (objeto prob min-max; raro), para ver que el jar y el codigo dicen lo mismo. */
    static String tabla(List<Map<?, ?>> filas) {
        List<String> l = new ArrayList<>();
        for (Map<?, ?> f : filas) {
            int min = Math.max(1, entero(f.get("min"), 1));
            l.add(String.valueOf(f.get("objeto")).trim().toLowerCase(Locale.ROOT) + " " + decimal(f.get("prob"), 0) + " " + min
                    + "-" + Math.max(min, entero(f.get("max"), min)) + (si(f.get("raro")) ? " raro" : ""));
        }
        return String.join("; ", l);
    }

    /** "1 2 3": los grados de Reliquia que pueden salir de una tabla (los de prob > 0). */
    static String grados(List<Map<?, ?>> filas) {
        Set<Integer> g = new TreeSet<>();
        for (Map<?, ?> f : filas) {
            String o = String.valueOf(f.get("objeto")).trim().toLowerCase(Locale.ROOT);
            if (o.startsWith("reliquia-") && decimal(f.get("prob"), 0) > 0) g.add(entero(o.substring("reliquia-".length()), 0));
        }
        List<String> l = new ArrayList<>();
        for (int x : g) l.add(String.valueOf(x));
        return String.join(" ", l);
    }

    /**
     * Abre cofres cofres comunes de esa tabla, con factor de bioma 1,25 y un azar de semilla fija. Devuelve
     * {Reliquias que salieron, cofres con mas de un raro, raros de mas de 1, cofres con un raro}.
     */
    static int[] simular(List<Map<?, ?>> filas, Set<String> raros, int escalones, double extraProb, double extraCant,
                         int cofres, long semilla) {
        Set<String> todos = new LinkedHashSet<>(raros);
        for (Map<?, ?> f : filas) if (si(f.get("raro"))) todos.add(String.valueOf(f.get("objeto")).trim().toLowerCase(Locale.ROOT));
        Random r = new Random(semilla);
        int[] s = new int[4];
        for (int i = 0; i < cofres; i++) {
            int enEste = 0;
            for (Tirada t : tirarComun(filas, raros, escalones, extraProb, extraCant, 1.25, r::nextDouble)) {
                if (esReliquia(t.objeto())) s[0]++;
                if (todos.contains(t.objeto())) {
                    enEste++;
                    if (t.n() != 1) s[2]++;
                }
            }
            if (enEste > 1) s[1]++;
            if (enEste > 0) s[3]++;
        }
        return s;
    }

    /**
     * Los dos cofres comunes y las dos bovedas de una seccion hardcore (la de serie, la del jar o la del servidor).
     * exactas: ademas, las probabilidades de las Reliquias de las bovedas y la lista de raros, tal cual.
     */
    private static void tablas(Autotest.Hoja h, String de, ConfigurationSection hc, boolean exactas) {
        ConfigurationSection ru = hc.getConfigurationSection("ruinas");
        ConfigurationSection bc = hc.getConfigurationSection("boveda-caida");
        List<Map<?, ?>> estructura = Cofres.anadir(hc);
        List<Map<?, ?>> ruina = filas(ru, "cofres.botin", Ruinas.COFRE_DE_SERIE);
        List<Map<?, ?>> bovRuinas = filas(ru, "bovedas.botin", Ruinas.BOVEDA_DE_SERIE);
        List<Map<?, ?>> caida = filas(bc, "botin", BovedaCaida.BOTIN_DE_SERIE);
        Set<String> rEst = raros(hc, "cofres.raros"), rRuina = raros(ru, "cofres.raros");
        int tope = ru == null ? 6 : ru.getInt("cofres.extra.tope", 6);
        double xp = ru == null ? 0.15 : ru.getDouble("cofres.extra.prob", 0.15);
        double xc = ru == null ? 0.5 : ru.getDouble("cofres.extra.cantidad", 0.5);
        int[] e = simular(estructura, rEst, 0, 0, 0, 4000, 6660);
        h.ok(de + ": cofre de estructura, 4.000 abiertos: ninguna Reliquia, nunca dos raros, ningún raro de más de 1 ("
                + e[0] + "/" + e[1] + "/" + e[2] + ")", e[0] == 0 && e[1] == 0 && e[2] == 0);
        int[] r = simular(ruina, rRuina, tope, xp, xc, 4000, 6661);
        h.ok(de + ": cofre de ruina a escalón " + tope + ", 4.000 abiertos: ninguna Reliquia, nunca dos raros, ningún raro de "
                + "más de 1 (" + r[0] + "/" + r[1] + "/" + r[2] + ")", r[0] == 0 && r[1] == 0 && r[2] == 0);
        h.igual(de + ": Bóveda de Ruinas, Reliquias de grado I a III y sin IV", "1 2 3", grados(bovRuinas));
        h.igual(de + ": Bóveda Caída, Reliquias de grado II, III y IV", "2 3 4", grados(caida));
        if (!exactas) return;
        h.ok(de + ": en los cofres comunes lo raro sigue saliendo (" + e[3] + " y " + r[3] + " de 4.000)", e[3] > 0 && r[3] > 0);
        h.igual(de + ": Bóveda de Ruinas, sus Reliquias", "reliquia-1 0.7 1-3, reliquia-2 0.5 1-2, reliquia-3 0.1 1-1",
                reliquias(bovRuinas));
        h.igual(de + ": Bóveda Caída, sus Reliquias", "reliquia-2 0.6 1-2, reliquia-3 0.8 1-2, reliquia-4 0.25 1-1",
                reliquias(caida));
        h.igual(de + ": raros del cofre de estructura", RAROS_DE_SERIE, new ArrayList<>(rEst));
        h.igual(de + ": raros del cofre de ruina", RAROS_DE_SERIE, new ArrayList<>(rRuina));
    }

    static List<String> autotest() {
        return autotest(null);
    }

    /** servidor: la seccion hardcore del config del servidor (en el juego), o null sin servidor. */
    static List<String> autotest(ConfigurationSection servidor) {
        Autotest.Hoja h = new Autotest.Hoja();
        h.igual("escalones: 0 bloques", 0, escalones(0, 500, 6));
        h.igual("escalones: 1.499 bloques son 2", 2, escalones(1499, 500, 6));
        h.igual("escalones: con tope", 6, escalones(9000, 500, 6));
        h.igual("escalones: cada 0 lo apaga", 0, escalones(9000, 0, 6));
        List<Map<?, ?>> filas = List.of(Map.of("objeto", "esencia", "prob", 0.5, "min", 1, "max", 2),
                Map.of("objeto", "cristal", "prob", 0.05, "min", 1, "max", 1));
        // azar 0.4: la esencia entra (0.4 < 0.5), el cristal no (0.4 >= 0.05); cantidad 1 + floor(0.4*2) = 1.
        List<Tirada> t = tirar(filas, 0, 0.15, 0.5, 1, () -> 0.4);
        h.igual("sin distancia: solo la esencia", 1, t.size());
        h.igual("sin distancia: una", 1, t.isEmpty() ? -1 : t.get(0).n());
        // A 2 escalones: el cristal pasa a 0.05*1.15^2 = 0.066; el maximo de la esencia, 2 + floor(0.5*2) = 3.
        List<Tirada> lejos = tirar(filas, 2, 0.15, 0.5, 1, () -> 0.06);
        h.igual("lejos: entran las dos", 2, lejos.size());
        List<Tirada> mucho = tirar(filas, 2, 0.15, 0.5, 1, () -> 0.99);
        h.igual("lejos con azar alto: nada", 0, mucho.size());
        List<Tirada> tope = tirar(List.of(Map.of("objeto", "esencia", "prob", 0.9, "min", 1, "max", 2)), 4, 0.5, 0.5, 1, () -> 0.999);
        h.igual("probabilidad pasada de 1 se queda en 1 (siempre entra)", 1, tope.size());
        h.igual("y su cantidad no pasa del maximo con escalones (2 + 2)", 4, tope.isEmpty() ? -1 : tope.get(0).n());
        h.igual("factor de bioma 0 la apaga", 0, tirar(filas, 0, 0, 0, 0, () -> 0.0).size());
        YamlConfiguration b = new YamlConfiguration();
        b.set("crimson_organism", 1.25);
        h.cerca("factor del bioma con namespace", 1.25, factorBioma(b, "bracken:panacea/crimson_organism"), 1e-9);
        h.cerca("bioma sin factor = 1", 1.0, factorBioma(b, "bracken:panacea/bamboo_valley"), 1e-9);
        Map<String, Integer> r = new LinkedHashMap<>();
        r.put("esencia", 2);
        r.put("cristal", 1);
        h.igual("resumen", "esencia x2, cristal x1", texto(r));

        // ---- 1.16.4 · Cofres comunes: ninguna Reliquia, lo raro de a 1 y como mucho un raro por cofre.
        Set<String> rs = raros(null, "cofres.raros");
        h.igual("raros de serie", RAROS_DE_SERIE, new ArrayList<>(rs));
        YamlConfiguration rc = new YamlConfiguration();
        rc.set("cofres.raros", List.of(" Cristal ", "material:DIAMOND", ""));
        h.igual("raros del config, en minúsculas y sin vacíos", List.of("cristal", "material:diamond"),
                new ArrayList<>(raros(rc, "cofres.raros")));
        h.ok("son Reliquias: de la I a la IV y las especiales", esReliquia("reliquia-1") && esReliquia(" Reliquia-4")
                && esReliquia("reliquia-eclipsada"));
        h.ok("no son Reliquias: esencia, llave, cristal, frasco", !esReliquia("esencia") && !esReliquia("llave-umbral")
                && !esReliquia("cristal") && !esReliquia("frasco-1"));
        List<Map<?, ?>> todo = List.of(
                Map.of("objeto", "esencia", "prob", 1.0, "min", 1, "max", 2),
                Map.of("objeto", "reliquia-1", "prob", 1.0, "min", 1, "max", 3),
                Map.of("objeto", "reliquia-4", "prob", 1.0, "min", 1, "max", 1),
                Map.of("objeto", "reliquia-eclipsada", "prob", 1.0, "min", 1, "max", 1),
                Map.of("objeto", "cristal", "prob", 1.0, "min", 1, "max", 1),
                Map.of("objeto", "llave-umbral", "prob", 1.0, "min", 2, "max", 3),
                Map.of("objeto", "frasco-1", "prob", 1.0, "min", 1, "max", 1),
                Map.of("objeto", "material:BREAD", "prob", 1.0, "min", 1, "max", 3));
        // Todo entra. Azar 0,999: lo que no es raro, al maximo con 3 escalones (esencia 2 + 1, pan 3 + 1); de los tres
        // raros que entran se queda el de floor(0,999 * 3) = el tercero, el frasco, de a 1 y en su sitio.
        h.igual("cofre común a escalón 3: sin Reliquias, un solo raro de 1 y lo demás sube",
                "esencia x3, frasco-1 x1, material:bread x4", lista(tirarComun(todo, rs, 3, 0.15, 0.5, 1, () -> 0.999)));
        h.igual("con azar 0 se queda el primer raro (el cristal)", "esencia x1, cristal x1, material:bread x1",
                lista(tirarComun(todo, rs, 3, 0.15, 0.5, 1, () -> 0.0)));
        h.igual("con azar 0,5, el segundo (la llave), de a 1 aunque su fila diga 2-3",
                "esencia x2, llave-umbral x1, material:bread x3", lista(tirarComun(todo, rs, 3, 0.15, 0.5, 1, () -> 0.5)));
        h.igual("la Llave del Umbral de una fila 2-3, a escalón 6 y con el azar más alto: de a 1", "llave-umbral x1",
                lista(tirarComun(List.of(Map.of("objeto", "llave-umbral", "prob", 1.0, "min", 2, "max", 3)), rs, 6, 0.15,
                        0.5, 1, () -> 0.999)));
        List<Map<?, ?>> marcada = List.of(
                Map.of("objeto", "material:DIAMOND", "prob", 1.0, "min", 2, "max", 5, "raro", true),
                Map.of("objeto", "material:EMERALD", "prob", 1.0, "min", 2, "max", 5, "raro", false),
                Map.of("objeto", "frasco-1", "prob", 1.0, "min", 1, "max", 2));
        h.igual("una fila con raro: true es rara y comparte el tope con los de la lista",
                "material:diamond x1, material:emerald x2", lista(tirarComun(marcada, rs, 0, 0, 0, 1, () -> 0.0)));
        h.igual("con la lista de raros vacía no se topa nada", "cristal x1, frasco-1 x1",
                lista(tirarComun(List.of(todo.get(4), todo.get(6)), Set.of(), 0, 0, 0, 1, () -> 0.0)));
        h.igual("lo raro que no entra no cuenta: el otro sale solo", "esencia x1, llave-umbral x1",
                lista(tirarComun(List.of(todo.get(0), Map.of("objeto", "cristal", "prob", 0.01, "min", 1, "max", 1),
                        todo.get(5)), rs, 0, 0, 0, 1, () -> 0.3)));
        // Una boveda (tirar) no lleva esos topes: es el premio de la llave.
        List<Tirada> bov = tirar(todo, 3, 0.15, 0.5, 1, () -> 0.999);
        h.igual("bóveda: salen las 8 filas, Reliquias y raros incluidos", 8, bov.size());
        h.igual("bóveda: la llave sube con el escalón (3 + 1)", "llave-umbral x4", lista(bov.subList(5, 6)));
        // La tabla vieja del cofre de ruina (la de antes de la 1.16.4, con Reliquias): si el config del servidor aun la
        // trae, a escalon 6 no sale ni una Reliquia y lo raro sigue de a 1 y de uno en uno.
        List<Map<?, ?>> vieja = List.of(
                Map.of("objeto", "esencia", "prob", 0.45, "min", 1, "max", 2),
                Map.of("objeto", "reliquia-1", "prob", 0.55, "min", 1, "max", 3),
                Map.of("objeto", "reliquia-2", "prob", 0.25, "min", 1, "max", 1),
                Map.of("objeto", "reliquia-3", "prob", 0.05, "min", 1, "max", 1),
                Map.of("objeto", "tintura", "prob", 0.15, "min", 1, "max", 2),
                Map.of("objeto", "cristal", "prob", 0.04, "min", 1, "max", 1),
                Map.of("objeto", "frasco-1", "prob", 0.04, "min", 1, "max", 1),
                Map.of("objeto", "llave-umbral", "prob", 0.05, "min", 1, "max", 1),
                Map.of("objeto", "material:BREAD", "prob", 0.40, "min", 1, "max", 3),
                Map.of("objeto", "material:ARROW", "prob", 0.30, "min", 4, "max", 10));
        int[] v = simular(vieja, rs, 6, 0.15, 0.5, 5000, 3);
        h.igual("tabla vieja a escalón 6, 5.000 cofres: ninguna Reliquia", 0, v[0]);
        h.igual("tabla vieja: ningún cofre con dos raros", 0, v[1]);
        h.igual("tabla vieja: ningún raro de más de 1", 0, v[2]);
        h.ok("tabla vieja: lo raro sale en algunos (" + v[3] + " de 5.000)", v[3] > 500 && v[3] < 2500);

        // Lo de serie del codigo, el config del jar (y que digan lo mismo) y, en el juego, el config del servidor.
        YamlConfiguration vacio = new YamlConfiguration();
        tablas(h, "de serie", vacio, true);
        ConfigurationSection jar = Ficha.deSerie();
        tablas(h, "jar", jar, true);
        h.igual("jar = código: cofre de estructura", tabla(Cofres.anadir(vacio)), tabla(Cofres.anadir(jar)));
        h.igual("jar = código: cofre de ruina", tabla(Ruinas.COFRE_DE_SERIE),
                tabla(filas(jar.getConfigurationSection("ruinas"), "cofres.botin", List.of())));
        h.igual("jar = código: Bóveda de Ruinas", tabla(Ruinas.BOVEDA_DE_SERIE),
                tabla(filas(jar.getConfigurationSection("ruinas"), "bovedas.botin", List.of())));
        h.igual("jar = código: Bóveda Caída", tabla(BovedaCaida.BOTIN_DE_SERIE),
                tabla(filas(jar.getConfigurationSection("boveda-caida"), "botin", List.of())));
        if (servidor != null) tablas(h, "config del servidor", servidor, false);
        return h.lineas();
    }
}
