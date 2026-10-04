package net.ederus.calamity.hardcore;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 */
final class BotinCalamity {

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
        List<Tirada> out = new ArrayList<>();
        int e = Math.max(0, escalones);
        for (Map<?, ?> f : filas) {
            String objeto = String.valueOf(f.get("objeto")).trim().toLowerCase(Locale.ROOT);
            if (objeto.isEmpty() || objeto.equals("null")) continue;
            double prob = decimal(f.get("prob"), 0) * Math.pow(1 + Math.max(0, extraProb), e) * Math.max(0, factorBioma);
            if (azar.getAsDouble() >= Math.min(1, prob)) continue;
            int min = Math.max(1, entero(f.get("min"), 1));
            int max = Math.max(min, entero(f.get("max"), min)) + (int) Math.floor(Math.max(0, extraCant) * e);
            int n = min + (int) Math.floor(azar.getAsDouble() * (max - min + 1));
            out.add(new Tirada(objeto, Math.min(max, n)));
        }
        return out;
    }

    /**
     * Hace los objetos y paga: Esencias y Reliquias por Aduana.pagar(tipo) a quien abre; el resto
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
            Aduana.Pago pago = ad.pagar(p, tipoAduana, esencias, 0, reliquias, origen);
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

    static List<String> autotest() {
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
        return h.lineas();
    }
}
