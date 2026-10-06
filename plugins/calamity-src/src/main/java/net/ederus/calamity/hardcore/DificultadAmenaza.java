package net.ederus.calamity.hardcore;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calamity 1.8 · Lo que endurece a la PARCA y a Ambush segun como lo tiene su presa cuando
 * aparecen (hardcore.dificultad-amenazas). Dosa: "Ambush y Parca deben tener el buff en caso de
 * que el jugador este lejos, poca cordura y tiempo dentro de Calamity. Siendo que mientras mas
 * sea, mas fuertes se deben hacer".
 *
 * Tres factores, y cada uno da un multiplicador de dano y otro de vida:
 *  - la distancia al borde de la zona spawn (la misma medida que hardcore.distancia,
 *    Hardcore.bloquesAlSpawn): +dano y +vida por cada cada-bloques, hasta tope-tramos;
 *  - la cordura: solo el tramo mas bajo que cumpla (cordura por debajo de por-debajo);
 *  - los minutos dentro en esta visita (Cordura.Estado.segundosDentro, el contador que se pone a
 *    cero al salir, al morir y al desconectarse): +dano y +vida por cada cada-minutos, hasta tope-tramos.
 * Los tres se multiplican entre si; sin ninguno, x1. Con el ejemplo de Dosa (2.000 bloques,
 * cordura por debajo de 25 y 2 h dentro): dano x1,6 x1,4 x1,6 = x3,58 y vida x1,4 x1,2 x1,4 = x2,35.
 *
 * La foto se hace al aparecer y no cambia durante la pelea: la PARCA que vuelve de lo pendiente
 * (desconexion, puerta, reinicio) trae la que tenia, porque al volver el contador de minutos y la
 * cordura ya se han puesto a cero. Los multiplicadores van despues de la formula por nivel (y de
 * las repeticiones de la PARCA): Parca.golpe/vidaLogica y Ambush.golpe/vida. El tope por golpe
 * que reciben no cambia.
 *
 * El nucleo (calcular, texto, Ajustes.de) es estatico y sin Bukkit vivo: el autotest
 * "dificultad-amenazas" lo prueba con el ejemplo de Dosa, los bordes y los topes.
 */
final class DificultadAmenaza {

    private DificultadAmenaza() {
    }

    /** Un tramo de cordura: por debajo de tanta cordura, tanto de dano y de vida de mas. */
    record Tramo(double porDebajo, double dano, double vida) {
    }

    /** Lo que se lee de hardcore.dificultad-amenazas. Un valor que falte, el de serie. */
    record Ajustes(int cadaBloques, double distanciaDano, double distanciaVida, int distanciaTope,
                   List<Tramo> cordura,
                   int cadaMinutos, double tiempoDano, double tiempoVida, int tiempoTope) {

        /** Los tramos de cordura de serie (los del config.yml del jar). */
        static final List<Tramo> CORDURA_DE_SERIE = List.of(
                new Tramo(50, 0.20, 0.10), new Tramo(25, 0.40, 0.20), new Tramo(1, 0.60, 0.30));

        static Ajustes de(ConfigurationSection c) {
            if (c == null) c = new YamlConfiguration();
            ConfigurationSection d = c.getConfigurationSection("distancia");
            if (d == null) d = new YamlConfiguration();
            ConfigurationSection t = c.getConfigurationSection("tiempo");
            if (t == null) t = new YamlConfiguration();
            List<Tramo> cordura;
            if (c.isList("cordura")) {
                cordura = new ArrayList<>();
                for (Map<?, ?> m : c.getMapList("cordura")) {
                    double por = numero(m.get("por-debajo"), -1);
                    if (por <= 0) continue;
                    cordura.add(new Tramo(por, Math.max(0, numero(m.get("dano"), 0)), Math.max(0, numero(m.get("vida"), 0))));
                }
                cordura = List.copyOf(cordura);
            } else {
                cordura = CORDURA_DE_SERIE;
            }
            return new Ajustes(
                    d.getInt("cada-bloques", 500),
                    Math.max(0, d.getDouble("dano", 0.15)),
                    Math.max(0, d.getDouble("vida", 0.10)),
                    Math.max(0, d.getInt("tope-tramos", 8)),
                    cordura,
                    t.getInt("cada-minutos", 30),
                    Math.max(0, t.getDouble("dano", 0.15)),
                    Math.max(0, t.getDouble("vida", 0.10)),
                    Math.max(0, t.getInt("tope-tramos", 6)));
        }

        /** Los de serie, los del config.yml del jar: el autotest los usa para no depender del servidor. */
        static Ajustes defecto() {
            return de(null);
        }

        private static double numero(Object o, double def) {
            if (o instanceof Number n) return n.doubleValue();
            if (o instanceof String s) {
                try {
                    return Double.parseDouble(s.trim().replace(',', '.'));
                } catch (NumberFormatException e) {
                    return def;
                }
            }
            return def;
        }
    }

    /** Como estaba la presa al aparecer la amenaza: bloques al borde del spawn, cordura y segundos dentro. */
    record Foto(double bloques, double cordura, int segundosDentro) {
    }

    /** Los dos multiplicadores y la foto de la que salen (null en las de prueba, sin presa). */
    record Resultado(Foto foto, double dano, double vida) {

        /** "daño ×3,6 · vida ×2,4: 2.000 bloques, cordura 18, 2 h 05 min", para los info del staff. */
        String texto() {
            String m = "daño ×" + veces(dano) + " · vida ×" + veces(vida);
            if (foto == null) return m + ": sin presa";
            return m + ": " + Distancia.miles(Math.round(Math.max(0, foto.bloques()))) + " bloques, cordura "
                    + Math.round(Math.max(0, foto.cordura())) + ", " + tiempo(foto.segundosDentro());
        }
    }

    /** Sin presa (las de prueba) o sin nada que sumar. */
    static final Resultado NEUTRO = new Resultado(null, 1, 1);

    // ================================================================== nucleo

    static Resultado calcular(Foto f, Ajustes a) {
        if (f == null || a == null) return NEUTRO;
        int td = tramosDistancia(f.bloques(), a);
        int tt = tramosTiempo(f.segundosDentro(), a);
        Tramo tc = tramoCordura(f.cordura(), a);
        double dano = (1 + a.distanciaDano() * td) * (1 + (tc == null ? 0 : tc.dano())) * (1 + a.tiempoDano() * tt);
        double vida = (1 + a.distanciaVida() * td) * (1 + (tc == null ? 0 : tc.vida())) * (1 + a.tiempoVida() * tt);
        return new Resultado(f, dano, vida);
    }

    /** Tramos enteros de cada-bloques, hasta tope-tramos. cada-bloques 0 lo apaga. */
    static int tramosDistancia(double bloques, Ajustes a) {
        if (a.cadaBloques() <= 0 || bloques <= 0) return 0;
        return (int) Math.min(a.distanciaTope(), Math.floor(bloques / a.cadaBloques()));
    }

    /** Tramos enteros de cada-minutos dentro, hasta tope-tramos. cada-minutos 0 lo apaga. */
    static int tramosTiempo(int segundosDentro, Ajustes a) {
        if (a.cadaMinutos() <= 0 || segundosDentro <= 0) return 0;
        return Math.min(a.tiempoTope(), segundosDentro / (a.cadaMinutos() * 60));
    }

    /** El tramo mas bajo en el que esta (cordura por debajo de su por-debajo), o null. */
    static Tramo tramoCordura(double cordura, Ajustes a) {
        Tramo mejor = null;
        for (Tramo t : a.cordura()) {
            if (cordura < t.porDebajo() && (mejor == null || t.porDebajo() < mejor.porDebajo())) mejor = t;
        }
        return mejor;
    }

    /** 3,584 -> "3,6"; 1 -> "1"; 2,0 -> "2". */
    static String veces(double x) {
        return BigDecimal.valueOf(x).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /** 7500 -> "2 h 05 min"; 900 -> "15 min". */
    static String tiempo(int segundos) {
        int min = Math.max(0, segundos) / 60;
        if (min < 60) return min + " min";
        return (min / 60) + " h " + String.format("%02d", min % 60) + " min";
    }

    // ================================================================== en el servidor

    static Ajustes ajustes(Hardcore hc) {
        return Ajustes.de(hc.cfg().getConfigurationSection("dificultad-amenazas"));
    }

    /** La foto de ese jugador ahora mismo, o null si no hay jugador. */
    static Foto foto(Hardcore hc, Player p) {
        if (p == null) return null;
        Cordura.Estado e = hc.cordura().estado(p);
        // 1.16.0: la cordura sentida. Con un Farol de Tranquilidad encendido la cordura baja no endurece a la amenaza
        // (la amenaza viene igual: la distancia y los minutos dentro cuentan como siempre).
        return new Foto(hc.bloquesAlSpawn(p), hc.cordura().sentida(p), e.segundosDentro);
    }

    /** Los multiplicadores de esa foto con la config de ahora; NEUTRO sin foto. */
    static Resultado para(Hardcore hc, Foto f) {
        return f == null ? NEUTRO : calcular(f, ajustes(hc));
    }

    // ================================================================== autotest

    /**
     * "dificultad-amenazas", sin servidor: el ejemplo de Dosa, sin factores x1, los bordes de cada
     * tramo, los topes, que la PARCA y Ambush aplican el multiplicador en sus formulas, y lo de
     * serie del config.yml del jar (esta seccion, la PARCA a los 5 minutos y el golpe base).
     */
    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        Ajustes a = Ajustes.defecto();

        // ---- El ejemplo de Dosa: 2.000 bloques, cordura por debajo de 25 y 2 h dentro.
        Resultado ej = calcular(new Foto(2000, 18, 2 * 3600 + 5 * 60), a);
        h.cerca("ejemplo de Dosa: daño x1,6 x1,4 x1,6 = x3,584", 3.584, ej.dano(), 1e-9);
        h.cerca("ejemplo de Dosa: vida x1,4 x1,2 x1,4 = x2,352", 2.352, ej.vida(), 1e-9);
        h.igual("ejemplo de Dosa: el texto del info", "daño ×3,6 · vida ×2,4: 2.000 bloques, cordura 18, 2 h 05 min", ej.texto());

        // ---- Sin nada, x1; y justo antes de cada tramo, tambien.
        Resultado nada = calcular(new Foto(0, 100, 0), a);
        h.ok("sin ningún factor: x1 y x1", nada.dano() == 1 && nada.vida() == 1);
        Resultado borde = calcular(new Foto(499, 50, 29 * 60 + 59), a);
        h.ok("499 bloques, cordura 50 y 29:59 min: todavía x1", borde.dano() == 1 && borde.vida() == 1);
        h.igual("sin presa: x1 y el texto lo dice", "daño ×1 · vida ×1: sin presa", NEUTRO.texto());
        h.ok("sin foto: NEUTRO", calcular(null, a) == NEUTRO);

        // ---- Cada factor por separado, en su borde.
        h.cerca("500 bloques: daño x1,15", 1.15, calcular(new Foto(500, 100, 0), a).dano(), 1e-9);
        h.cerca("500 bloques: vida x1,10", 1.10, calcular(new Foto(500, 100, 0), a).vida(), 1e-9);
        h.cerca("cordura 49: daño x1,2", 1.2, calcular(new Foto(0, 49, 0), a).dano(), 1e-9);
        h.cerca("cordura 24: daño x1,4 (solo el tramo más bajo)", 1.4, calcular(new Foto(0, 24, 0), a).dano(), 1e-9);
        h.cerca("cordura 0: daño x1,6 y vida x1,3", 1.6 + 1.3,
                calcular(new Foto(0, 0, 0), a).dano() + calcular(new Foto(0, 0, 0), a).vida(), 1e-9);
        h.cerca("30 min dentro: daño x1,15", 1.15, calcular(new Foto(0, 100, 30 * 60), a).dano(), 1e-9);

        // ---- Los topes de tramos: 8 de distancia (4.000 bloques) y 6 de tiempo (3 h).
        h.igual("tramos de distancia: 4.000 -> 8, 100.000 -> 8 (tope)", List.of(8, 8),
                List.of(tramosDistancia(4000, a), tramosDistancia(100_000, a)));
        h.igual("tramos de tiempo: 3 h -> 6, 10 h -> 6 (tope)", List.of(6, 6),
                List.of(tramosTiempo(3 * 3600, a), tramosTiempo(10 * 3600, a)));
        Resultado tope = calcular(new Foto(100_000, 0, 10 * 3600), a);
        h.cerca("todo al tope: daño x2,2 x1,6 x1,9 = x6,688", 6.688, tope.dano(), 1e-9);
        h.cerca("todo al tope: vida x1,8 x1,3 x1,6 = x3,744", 3.744, tope.vida(), 1e-9);

        // ---- La config: tramos desordenados, apagar un factor y los valores raros.
        YamlConfiguration y = new YamlConfiguration();
        y.set("cordura", List.of(Map.of("por-debajo", 10, "dano", 1.0, "vida", 0.5), Map.of("por-debajo", 90, "dano", 0.1, "vida", 0.0)));
        y.set("distancia.cada-bloques", 0);
        y.set("tiempo.dano", -2);
        Ajustes raro = Ajustes.de(y);
        Resultado r = calcular(new Foto(3000, 5, 3600), raro);
        h.cerca("tramos desordenados: cuenta el más bajo (x2); distancia apagada; daño negativo -> 0", 2.0, r.dano(), 1e-9);
        h.igual("cordura: [] -> sin tramos", 0, Ajustes.de(conCorduraVacia()).cordura().size());
        h.igual("textos de tiempo", List.of("0 min", "59 min", "1 h 00 min", "3 h 12 min"),
                List.of(tiempo(0), tiempo(59 * 60 + 59), tiempo(3600), tiempo(3 * 3600 + 12 * 60)));

        // ---- La PARCA y Ambush lo aplican despues de su formula por nivel (y de las repeticiones).
        Parca.Ajustes pa = new Parca.Ajustes(new YamlConfiguration());
        h.cerca("Parca N 14: golpe x3,584", Parca.golpe(pa, 14, 0) * 3.584, Parca.golpe(pa, 14, 0, ej.dano()), 1e-9);
        h.cerca("Parca N 14 r 1 M 1: vida x2,352", Parca.vidaLogica(pa, 14, 1, 1) * 2.352,
                Parca.vidaLogica(pa, 14, 1, 1, ej.vida()), 1e-9);
        h.cerca("Parca con NEUTRO: la de siempre", Parca.golpe(pa, 60, 2), Parca.golpe(pa, 60, 2, NEUTRO.dano()), 1e-9);
        Ambush.Ajustes am = new Ambush.Ajustes(new YamlConfiguration());
        Ambush.Ajustes amDif = am.con(ej);
        h.cerca("Ambush N 14: golpe x3,584", Ambush.golpe(am, 14) * 3.584, Ambush.golpe(amDif, 14), 1e-9);
        h.cerca("Ambush N 14: vida x2,352", Ambush.vida(am, 14) * 2.352, Ambush.vida(amDif, 14), 1e-9);
        h.ok("Ambush con NEUTRO: la de siempre", Ambush.golpe(am.con(NEUTRO), 50) == Ambush.golpe(am, 50)
                && Ambush.vida(am.con(NEUTRO), 50) == Ambush.vida(am, 50));
        h.ok("Ambush: con() no toca lo demás (tope, fase 2, skins)", amDif.topeGolpe == am.topeGolpe
                && amDif.fase2 == am.fase2 && amDif.skin1.equals(am.skin1) && amDif.skin2.equals(am.skin2));

        // ---- El golpe base de serie, igual en el codigo y en el config.yml del jar.
        h.cerca("Parca: golpe base de serie 11", 11, pa.golpeBase, 1e-9);
        h.cerca("Ambush: golpe base de serie 10", 10, am.golpeBase, 1e-9);
        YamlConfiguration jar = configDelJar();
        if (jar == null) {
            h.ok("config.yml del jar encontrado", false);
        } else {
            h.igual("config.yml: dificultad-amenazas igual que la de serie", a,
                    Ajustes.de(jar.getConfigurationSection("hardcore.dificultad-amenazas")));
            h.cerca("config.yml: parca.golpe-base 11", 11, jar.getDouble("hardcore.parca.golpe-base"), 1e-9);
            h.cerca("config.yml: ambush.golpe-base 10", 10, jar.getDouble("hardcore.ambush.golpe-base"), 1e-9);
            // ---- La PARCA a los 5 minutos quieto, con los avisos y el marcado en grupo dentro de ellos.
            int minutos = jar.getInt("hardcore.parca.minutos");
            h.igual("config.yml: parca.minutos de serie 5", 5, minutos);
            List<Integer> avisos = jar.getIntegerList("hardcore.parca.avisos");
            boolean enOrden = avisos.size() == 5;
            for (int i = 0; enOrden && i < avisos.size(); i++) {
                enOrden = avisos.get(i) < minutos * 60 && (i == 0 || avisos.get(i) > avisos.get(i - 1));
            }
            h.ok("config.yml: los cinco avisos en orden y antes de los " + minutos * 60 + " s " + avisos, enOrden);
            h.ok("config.yml: quieto-marca-grupo por debajo del límite",
                    jar.getInt("hardcore.parca.quieto-marca-grupo") < minutos * 60);
            h.ok("config.yml: la Grieta del spawn no pasa de parca.minutos",
                    jar.getInt("hardcore.parca.spawn.minutos") <= minutos);
        }
        return h.lineas();
    }

    private static YamlConfiguration conCorduraVacia() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("cordura", List.of());
        return y;
    }

    /** El config.yml del jar (el de serie), o null si no se encuentra. */
    private static YamlConfiguration configDelJar() {
        try (InputStream in = DificultadAmenaza.class.getClassLoader().getResourceAsStream("config.yml")) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }
}
