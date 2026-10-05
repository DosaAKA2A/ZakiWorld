package net.ederus.calamity.hardcore;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

/**
 * Calamity 1.9.0 · Las reglas puras de que mob sale y donde, sin mundo ni servidor, para que el
 * autotest las pueda probar fuera del juego. Las usa MobsLethal.
 *
 *  - Tabla: lo que sale en cada bioma (mobs.biomas), los comunes y el destacado por separado.
 *  - Especial: los mobs especiales (mobs.especiales), que no salen de la tabla porque necesitan
 *    un sitio propio: en el aire (el ghast), en el agua (el guardian anciano) o con mas altura
 *    que un zombi (el creaking, que mide 2,7).
 *  - Sondeo y Celda: como se busca ese sitio bloque a bloque, sobre un mundo que puede ser de
 *    mentira (el del autotest) o el de verdad (MobsLethal le pasa World#getBlockAt).
 */
public final class Apariciones {

    private Apariciones() {
    }

    // ================================================================ tabla de biomas

    /** Lo que ha salido de la tabla: el id del tipo de /esb y si es el destacado. */
    public record Eleccion(String id, boolean destacado) {
    }

    /**
     * Lo que sale en un bioma: los comunes, cuantos se quieran, y el destacado aparte.
     *
     * Hasta la 1.8.4 iba todo en una lista [comun, comun, destacado] y se sacaba por posicion: un
     * tercer comun en la config pasaba a ser el destacado sin avisar, y el destacado de verdad no
     * salia nunca.
     */
    public record Tabla(List<String> comunes, String destacado) {

        /** La tabla de una entrada de mobs.biomas, o null si no trae ningun comun (como hasta ahora). */
        public static Tabla de(List<String> comunes, String destacado) {
            List<String> limpios = new ArrayList<>();
            if (comunes != null) {
                for (String c : comunes) if (c != null && !c.isBlank()) limpios.add(c.trim());
            }
            if (limpios.isEmpty()) return null;
            String d = destacado == null || destacado.isBlank() ? null : destacado.trim();
            return new Tabla(List.copyOf(limpios), d);
        }

        /**
         * El que sale: el destacado si lo hay y la tirada (0..1) cae por debajo de su probabilidad;
         * si no, el comun de la posicion azar (cualquier entero: se reduce al numero de comunes).
         */
        public Eleccion elegir(double tirada, double probabilidadDestacado, int azar) {
            if (destacado != null && tirada < probabilidadDestacado) return new Eleccion(destacado, true);
            return new Eleccion(comunes.get(Math.floorMod(azar, comunes.size())), false);
        }
    }

    // ================================================================ mobs especiales

    /** Donde se le busca sitio a un especial. */
    public enum Entorno {
        /** En el suelo, con los bloques libres que pida encima (hueco). */
        SUELO,
        /** En el aire, entre altura-minima y altura-maxima sobre el suelo, en un cubo de aire. */
        AIRE,
        /** Dentro del agua, en una columna honda con un cubo de agua libre. */
        AGUA;

        /** "suelo", "aire" o "agua" (sin mirar mayusculas); null si no es ninguno. */
        public static Entorno de(String s) {
            if (s == null) return null;
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * Un mob especial tal como viene en mobs.especiales.&lt;clave&gt;.
     *
     * @param clave        la clave de su entrada (va tambien en la marca PDC del mob)
     * @param nombre       el nombre visible de su ficha en /esb, por el que se busca
     * @param biomas       "panacea/&lt;id&gt;" (o con su namespace), o "*" = cualquier bioma con tabla
     * @param probabilidad de salir en cada intento (uno por jugador y ciclo)
     * @param tope         cuantos como mucho alrededor de cada jugador, contados en radioTope
     * @param hueco        suelo: bloques libres encima; aire y agua: lado del cubo libre
     * @param fatigaSegundos lo que dura la Fatiga minera que reparte (guardian anciano); 0 = vanilla
     * @param bolaDevuelta parte de su vida maxima que le quita cada bola de fuego que le devuelve un
     *                     jugador (solo cuenta en los ghasts; ver golpeDevuelto)
     */
    public record Especial(String clave, String nombre, EntityType entidad, Entorno entorno, List<String> biomas,
                           double probabilidad, int tope, double radioTope, int hueco,
                           int alturaMinima, int alturaMaxima, int profundidad,
                           double vidaBase, double vidaPorNivel, double danoBase, double danoPorNivel,
                           List<String> habilidades, int fatigaSegundos, double bolaDevuelta) {

        /**
         * Si puede salir en ese bioma: su clave corta ("panacea/crimson_organism", la de
         * getKey().getKey()) o la completa ("bracken:panacea/crimson_organism"). El comodin "*" vale
         * en los biomas que tienen tabla de mobs (los de Lethal World), no en cualquier sitio.
         */
        public boolean valeEn(String bioma, String biomaCompleto, boolean conTabla) {
            for (String b : biomas) {
                if (b.equals("*")) {
                    if (conTabla) return true;
                } else if (b.equals(minus(bioma)) || b.equals(minus(biomaCompleto))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Los especiales de una seccion como mobs.especiales, en su orden. Las claves que no son una
     * seccion (activos) se saltan; una entrada que no se entiende (sin nombre, sin biomas, con una
     * entidad o un entorno que no existen) tambien, y se apunta en avisos para el log.
     */
    public static List<Especial> leer(ConfigurationSection s, List<String> avisos) {
        List<Especial> out = new ArrayList<>();
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            if (!s.isConfigurationSection(k)) continue;
            ConfigurationSection e = s.getConfigurationSection(k);
            if (e == null) continue;
            String nombre = e.getString("nombre", "").trim();
            if (nombre.isEmpty()) {
                avisos.add(k + ": no tiene nombre, así que no sale");
                continue;
            }
            String textoEntidad = e.getString("entidad", "");
            EntityType tipo = entidad(textoEntidad);
            if (tipo == null) {
                avisos.add(k + ": la entidad '" + textoEntidad + "' no existe o no es un mob, así que no sale");
                continue;
            }
            String textoEntorno = e.getString("entorno", "suelo");
            Entorno entorno = Entorno.de(textoEntorno);
            if (entorno == null) {
                avisos.add(k + ": el entorno '" + textoEntorno + "' no existe (suelo, aire o agua), así que no sale");
                continue;
            }
            List<String> biomas = new ArrayList<>();
            for (String b : e.getStringList("biomas")) if (b != null && !b.isBlank()) biomas.add(minus(b.trim()));
            if (biomas.isEmpty()) {
                avisos.add(k + ": no tiene biomas, así que no sale");
                continue;
            }
            List<String> habilidades = new ArrayList<>();
            for (String h : e.getStringList("habilidades")) {
                if (h != null && !h.isBlank()) habilidades.add(h.trim().toUpperCase(Locale.ROOT));
            }
            int alturaMinima = Math.max(1, e.getInt("altura-minima", 8));
            out.add(new Especial(k, nombre, tipo, entorno, List.copyOf(biomas),
                    Math.max(0, Math.min(1, e.getDouble("probabilidad", 0.05))),
                    Math.max(1, e.getInt("tope", 1)),
                    Math.max(8, e.getDouble("radio-tope", 48)),
                    Math.max(1, Math.min(9, e.getInt("hueco", huecoDeSerie(entorno, tipo)))),
                    alturaMinima,
                    Math.max(alturaMinima, e.getInt("altura-maxima", 16)),
                    Math.max(1, e.getInt("profundidad", 3)),
                    e.getDouble("vida-base", 80), e.getDouble("vida-por-nivel", 0.10),
                    e.getDouble("dano-base", 1.3), e.getDouble("dano-por-nivel", 0.05),
                    List.copyOf(habilidades),
                    Math.max(0, e.getInt("fatiga-minera-segundos", 0)),
                    fraccionDevuelta(e.getDouble("bola-devuelta", BOLA_DEVUELTA))));
        }
        return out;
    }

    /** Los especiales que pueden salir en ese bioma (ver Especial.valeEn), en el orden de la config y en una lista nueva. */
    public static List<Especial> candidatos(List<Especial> todos, String bioma, String biomaCompleto, boolean conTabla) {
        List<Especial> out = new ArrayList<>();
        for (Especial e : todos) if (e.valeEn(bioma, biomaCompleto, conTabla)) out.add(e);
        return out;
    }

    /** Un tipo de entidad de la config (CREAKING, ghast...), o null si no existe o no es un mob. */
    static EntityType entidad(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            EntityType t = EntityType.valueOf(s.trim().toUpperCase(Locale.ROOT));
            return t.isAlive() ? t : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Bloques libres que necesita encima un bicho de suelo: tres para los que pasan de dos de alto
     * (el creaking mide 2,7 y el ravager 2,2), dos para el resto. Con dos, nacen con la cabeza
     * metida en el bloque de arriba.
     */
    public static int altoDe(EntityType t) {
        if (t == null) return 2;
        return switch (t) {
            case CREAKING, RAVAGER, ENDERMAN, IRON_GOLEM, WARDEN -> 3;
            default -> 2;
        };
    }

    /**
     * Lo que le quita a un ghast especial cada bola de fuego que un jugador le devuelve si su
     * entrada no dice otra cosa: el 15 % de su vida maxima, siete bolas para tumbarlo.
     */
    public static final double BOLA_DEVUELTA = 0.15;

    /**
     * El dano de una bola devuelta a un ghast especial con esa vida maxima.
     *
     * En vanilla Ghast.hurtServer cambia el golpe de una bola que le devuelve un jugador por 1000
     * fijos: un ghast de nivel 100 (872 de vida) caia de una sola bola y pagaba como destacado sin
     * pelea. Con esto la vida por nivel vuelve a contar: cada bola quita la misma parte de su vida,
     * sea del nivel que sea. Como poco 1, para que devolverla nunca sea inutil.
     */
    public static double golpeDevuelto(double vidaMaxima, double fraccion) {
        return Math.max(1, Math.max(0, vidaMaxima) * fraccionDevuelta(fraccion));
    }

    /** La parte de la vida de una bola devuelta, entre el 1 % y el 100 % (una bola, como en vanilla). */
    static double fraccionDevuelta(double f) {
        if (Double.isNaN(f)) return BOLA_DEVUELTA;
        return Math.max(0.01, Math.min(1, f));
    }

    /** El hueco que pide cada entorno si la config no dice otro. */
    static int huecoDeSerie(Entorno entorno, EntityType tipo) {
        return switch (entorno) {
            case SUELO -> altoDe(tipo);
            // El ghast mide 4 x 4 x 4: un cubo de 5 alrededor de su bloque lo cubre entero.
            case AIRE -> 5;
            // El guardian anciano mide casi 2 x 2 x 2.
            case AGUA -> 2;
        };
    }

    /** El color con el que nace la ficha de un especial en /esb (el de su nombre en el cartel). */
    public static int colorDe(EntityType t) {
        if (t == null) return Paleta.ESPECIAL;
        return switch (t) {
            case CREAKING -> Paleta.CRUJIDOR;
            case GHAST -> Paleta.GHAST_CARMESI;
            case ELDER_GUARDIAN -> Paleta.GUARDIAN_ANCIANO;
            default -> Paleta.ESPECIAL;
        };
    }

    /**
     * La seccion de esa ruta tal como la ve Calamity: la del config del servidor si la tiene y, si
     * no, la del config.yml del jar (los defaults que pone JavaPlugin). Sin crearla por el camino:
     * getConfigurationSection la crearia vacia en memoria cuando solo esta en el jar, y el
     * siguiente saveConfig de cualquier otro sitio (un menu de staff) la dejaria escrita, vacia, en
     * el config.yml del servidor. A partir de ahi no saldria ningun especial.
     */
    public static ConfigurationSection seccion(Configuration raiz, String ruta) {
        if (raiz == null) return null;
        ConfigurationSection propia = sinCrear(raiz, ruta);
        if (propia != null) return propia;
        return sinCrear(raiz.getDefaults(), ruta);
    }

    /** Baja por la ruta leyendo el mapa de cada seccion, sin tocar defaults ni crear nada. */
    private static ConfigurationSection sinCrear(ConfigurationSection raiz, String ruta) {
        ConfigurationSection s = raiz;
        for (String parte : ruta.split("\\.")) {
            Object o = s == null ? null : s.get(parte, null);
            if (!(o instanceof ConfigurationSection hija)) return null;
            s = hija;
        }
        return s;
    }

    private static String minus(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    // ================================================================ buscar sitio

    /** Lo que hay en un bloque, a efectos de plantar un mob. */
    public enum Celda {
        /** Aire de verdad. */
        AIRE,
        /** Se atraviesa y no es liquido: hierba alta, flores, nieve fina... */
        PASABLE,
        /** Agua (tambien las algas y las praderas marinas, que son agua con planta). */
        AGUA,
        LAVA,
        /** Todo lo demas: se pisa y no se atraviesa. */
        SOLIDO
    }

    /** Lo que hay en cada bloque de un mundo, real o de mentira. */
    @FunctionalInterface
    public interface Sondeo {
        Celda en(int x, int y, int z);
    }

    /**
     * Si en (x, ySuelo, z) hay suelo firme con 'alto' bloques libres encima, sin agua ni lava. Es la
     * regla de siempre de MobsLethal.sitio (que miraba dos bloques) con la altura como parametro.
     */
    public static boolean suelo(Sondeo s, int x, int ySuelo, int z, int alto) {
        if (s.en(x, ySuelo, z) != Celda.SOLIDO) return false;
        for (int h = 1; h <= Math.max(1, alto); h++) {
            Celda c = s.en(x, ySuelo + h, z);
            if (c != Celda.AIRE && c != Celda.PASABLE) return false;
        }
        return true;
    }

    /**
     * El hueco en el aire para un volador: un cubo de 'lado' bloques de aire (solo aire: ni hojas ni
     * lianas) centrado en x, z y con la base 'altura' bloques por encima de ySuelo. Devuelve la y de
     * la base del cubo (donde van los pies del volador), o null si algo lo corta.
     */
    public static Integer aire(Sondeo s, int x, int ySuelo, int z, int altura, int lado) {
        int l = Math.max(1, lado);
        int y = ySuelo + Math.max(1, altura);
        int desde = -(l - 1) / 2;
        for (int dx = desde; dx < desde + l; dx++) {
            for (int dz = desde; dz < desde + l; dz++) {
                for (int dy = 0; dy < l; dy++) {
                    if (s.en(x + dx, y + dy, z + dz) != Celda.AIRE) return null;
                }
            }
        }
        return y;
    }

    /**
     * El hueco en el agua para un nadador. ySuperficie es lo mas alto de la columna (el heightmap que
     * cuenta los fluidos): tiene que ser agua y seguir siendolo al menos 'profundidad' bloques hacia
     * abajo, y bajo la superficie tiene que caber un cubo de agua de 'lado' bloques (de x a x+lado-1 y
     * de z a z+lado-1). Devuelve la y de la base del cubo mas alto que cabe sumergido, con un bloque
     * de agua por encima, o null.
     */
    public static Integer agua(Sondeo s, int x, int ySuperficie, int z, int profundidad, int lado) {
        if (s.en(x, ySuperficie, z) != Celda.AGUA) return null;
        int fondo = ySuperficie;
        while (ySuperficie - fondo < 64 && s.en(x, fondo - 1, z) == Celda.AGUA) fondo--;
        if (ySuperficie - fondo + 1 < Math.max(1, profundidad)) return null;
        int l = Math.max(1, lado);
        for (int y = ySuperficie - l; y >= fondo; y--) {
            if (cuboDeAgua(s, x, y, z, l)) return y;
        }
        return null;
    }

    private static boolean cuboDeAgua(Sondeo s, int x, int y, int z, int l) {
        for (int dx = 0; dx < l; dx++) {
            for (int dz = 0; dz < l; dz++) {
                for (int dy = 0; dy < l; dy++) {
                    if (s.en(x + dx, y + dy, z + dz) != Celda.AGUA) return false;
                }
            }
        }
        return true;
    }

    // ================================================================ autotest

    /** /calamity selftest spawns: la tabla, la config de serie de los especiales y los sitios. */
    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();

        // --- la tabla de biomas (el fallo de la 1.8.4)
        Tabla t = Tabla.de(List.of("a", "b", "c"), "d");
        h.ok("tabla con tres comunes", t != null && t.comunes().size() == 3);
        h.igual("un tercer comun sale como comun", new Eleccion("c", false), t.elegir(0.9, 0.05, 2));
        h.igual("el destacado sale aparte", new Eleccion("d", true), t.elegir(0.01, 0.05, 2));
        h.igual("el azar se reduce a los comunes", new Eleccion("c", false), t.elegir(0.9, 0.05, 5));
        h.igual("azar negativo", new Eleccion("c", false), t.elegir(0.9, 0.05, -1));
        Tabla sinDestacado = Tabla.de(List.of("a"), null);
        h.igual("sin destacado siempre sale un comun", new Eleccion("a", false), sinDestacado.elegir(0.0, 0.05, 7));
        h.igual("sin comunes no hay tabla", null, Tabla.de(List.of(), "d"));
        h.igual("los huecos en blanco no cuentan", List.of("a"), Tabla.de(List.of(" a ", " "), " ").comunes());
        h.igual("destacado en blanco = sin destacado", null, Tabla.de(List.of("a"), " ").destacado());

        // --- la config de serie (config.yml del jar)
        YamlConfiguration jar = configDelJar();
        h.ok("se lee el config.yml del jar", jar != null);
        ConfigurationSection sec = jar == null ? null : seccion(jar, "mobs.especiales");
        h.ok("el jar trae mobs.especiales", sec != null);
        List<String> avisos = new ArrayList<>();
        List<Especial> todos = leer(sec, avisos);
        h.igual("la config de serie no da avisos", List.of(), avisos);
        h.igual("tres especiales de serie", 3, todos.size());
        h.ok("activos esta encendido de serie", sec != null && sec.getBoolean("activos", false));
        Map<String, Especial> por = new HashMap<>();
        for (Especial e : todos) por.put(e.clave(), e);
        Especial cru = por.get("crujidor"), gha = por.get("ghast"), gua = por.get("guardian");
        h.ok("estan crujidor, ghast y guardian", cru != null && gha != null && gua != null);
        if (cru != null && gha != null && gua != null) {
            h.igual("crujidor es un creaking", EntityType.CREAKING, cru.entidad());
            h.igual("crujidor sale en el suelo", Entorno.SUELO, cru.entorno());
            h.igual("crujidor pide tres bloques libres (mide 2,7)", 3, cru.hueco());
            h.igual("nombre del crujidor", "Crujidor Pálido", cru.nombre());
            h.ok("crujidor en el conclave, el bosque voraz y la jungla",
                    cru.valeEn("panacea/conure_conclave", "bracken:panacea/conure_conclave", true)
                            && cru.valeEn("panacea/ravenous_greenwood", "bracken:panacea/ravenous_greenwood", true)
                            && cru.valeEn("panacea/hungering_jungle", "bracken:panacea/hungering_jungle", true));
            h.ok("crujidor no sale en el organismo", !cru.valeEn("panacea/crimson_organism", "bracken:panacea/crimson_organism", true));

            h.igual("ghast es un ghast", EntityType.GHAST, gha.entidad());
            h.igual("ghast sale en el aire", Entorno.AIRE, gha.entorno());
            h.igual("ghast: uno por jugador", 1, gha.tope());
            h.ok("ghast: tope contado en un radio amplio (se aleja flotando)", gha.radioTope() >= 90);
            h.ok("ghast: entre 8 y 16 bloques sobre el suelo", gha.alturaMinima() == 8 && gha.alturaMaxima() == 16);
            h.igual("ghast: cubo de aire de 5", 5, gha.hueco());
            h.ok("ghast en el organismo carmesi y la taiga condenada",
                    gha.valeEn("panacea/crimson_organism", "x", true) && gha.valeEn("panacea/condemned_taiga", "x", true));
            h.ok("ghast no sale en el conclave", !gha.valeEn("panacea/conure_conclave", "x", true));

            h.igual("guardian es un guardian anciano", EntityType.ELDER_GUARDIAN, gua.entidad());
            h.igual("guardian sale en el agua", Entorno.AGUA, gua.entorno());
            h.igual("guardian: uno por jugador", 1, gua.tope());
            h.ok("guardian: columna de 3 y hueco de 2", gua.profundidad() == 3 && gua.hueco() == 2);
            h.ok("guardian vale en cualquier bioma con tabla", gua.valeEn("panacea/honeybee_biome", "x", true));
            h.ok("guardian no sale donde no hay mobs de Lethal World", !gua.valeEn("plains", "minecraft:plains", false));
            h.ok("guardian es raro", gua.probabilidad() > 0 && gua.probabilidad() <= 0.05);
            h.igual("la Fatiga minera del guardian dura 60 s", 60, gua.fatigaSegundos());

            // Una bola devuelta mataba al ghast de un golpe (1000 fijos de vanilla), fuera del nivel que fuera.
            h.igual("ghast: cada bola devuelta le quita el 15 % de su vida", 0.15, gha.bolaDevuelta());
            double vida100 = gha.vidaBase() * (1 + gha.vidaPorNivel() * 99);
            double golpe100 = golpeDevuelto(vida100, gha.bolaDevuelta());
            h.ok("ghast de nivel 100: una bola devuelta ya no lo mata (" + Math.round(golpe100) + " de " + Math.round(vida100) + ")",
                    golpe100 < vida100 && golpe100 < 1000);
            h.igual("ghast de nivel 1: hacen falta siete bolas", 7, (int) Math.ceil(gha.vidaBase() / golpeDevuelto(gha.vidaBase(), gha.bolaDevuelta()) - 1e-9));
            h.igual("ghast de nivel 100: tambien siete", 7, (int) Math.ceil(vida100 / golpe100 - 1e-9));

            for (Especial e : todos) {
                h.ok(e.clave() + ": vida y dano positivos", e.vidaBase() > 0 && e.danoBase() > 0
                        && e.vidaPorNivel() >= 0 && e.danoPorNivel() >= 0);
                h.ok(e.clave() + ": pega mas fuerte que un comun (1.0)", e.danoBase() >= 1.0);
                h.ok(e.clave() + ": aguanta mas que un destacado (80)", e.vidaBase() >= 80);
                for (String hab : e.habilidades()) h.ok(e.clave() + ": la habilidad " + hab + " existe en EDM", habilidadEdm(hab));
                h.ok(e.clave() + ": tiene color propio en la Paleta", colorDe(e.entidad()) != Paleta.ESPECIAL);
            }

            List<Especial> enOrganismo = candidatos(todos, "panacea/crimson_organism", "bracken:panacea/crimson_organism", true);
            h.igual("en el organismo tocan el ghast y el guardian", List.of("ghast", "guardian"), claves(enOrganismo));
            List<Especial> enConclave = candidatos(todos, "panacea/conure_conclave", "bracken:panacea/conure_conclave", true);
            h.igual("en el conclave tocan el crujidor y el guardian", List.of("crujidor", "guardian"), claves(enConclave));
        }

        // --- entradas rotas: se saltan y avisan
        YamlConfiguration rota = new YamlConfiguration();
        rota.set("activos", true);
        rota.set("uno.nombre", "Dragoncito");
        rota.set("uno.entidad", "DRAGONCITO");
        rota.set("uno.biomas", List.of("*"));
        rota.set("dos.nombre", "Pez");
        rota.set("dos.entidad", "ZOMBIE");
        rota.set("dos.entorno", "lava");
        rota.set("dos.biomas", List.of("*"));
        rota.set("tres.entidad", "ZOMBIE");
        rota.set("tres.biomas", List.of("*"));
        rota.set("cuatro.nombre", "Sin sitio");
        rota.set("cuatro.entidad", "ZOMBIE");
        rota.set("cinco.nombre", "Flecha");
        rota.set("cinco.entidad", "ARROW");
        rota.set("cinco.biomas", List.of("*"));
        rota.set("seis.nombre", "Bueno");
        rota.set("seis.entidad", "ghast");
        rota.set("seis.entorno", "AIRE");
        rota.set("seis.biomas", List.of("Panacea/Crimson_Organism"));
        List<String> avisosRota = new ArrayList<>();
        List<Especial> rotos = leer(rota, avisosRota);
        h.igual("de seis entradas solo vale una", List.of("seis"), claves(rotos));
        h.igual("cinco avisos, uno por entrada rota", 5, avisosRota.size());
        if (rotos.size() == 1) {
            Especial bueno = rotos.get(0);
            h.igual("la entidad se lee sin mirar mayusculas", EntityType.GHAST, bueno.entidad());
            h.igual("el entorno tambien", Entorno.AIRE, bueno.entorno());
            h.ok("el bioma tambien", bueno.valeEn("panacea/crimson_organism", "", false));
            h.igual("hueco de serie en el aire", 5, bueno.hueco());
            h.igual("tope de serie", 1, bueno.tope());
        }
        h.igual("entorno que no existe", null, Entorno.de("mar"));
        if (rotos.size() == 1) h.igual("sin bola-devuelta vale la de serie", BOLA_DEVUELTA, rotos.get(0).bolaDevuelta());
        h.igual("bola devuelta: el 15 % de 400", 60.0, golpeDevuelto(400, 0.15));
        h.igual("bola devuelta: por debajo del 1 % se sube al 1 %", 4.0, golpeDevuelto(400, 0));
        h.igual("bola devuelta: por encima del 100 % se queda en una bola", 400.0, golpeDevuelto(400, 3));
        h.igual("bola devuelta: con la vida rota quita 1", 1.0, golpeDevuelto(-5, 0.15));
        h.igual("bola devuelta: un NaN en la config vale la de serie", 60.0, golpeDevuelto(400, Double.NaN));
        h.igual("altura de un creaking", 3, altoDe(EntityType.CREAKING));
        h.igual("altura de un zombi", 2, altoDe(EntityType.ZOMBIE));

        // --- la seccion del jar se lee sin escribirla en la del servidor
        if (jar != null) {
            YamlConfiguration servidor = new YamlConfiguration();
            servidor.set("mobs.activos", true);
            servidor.setDefaults(jar);
            ConfigurationSection vista = seccion(servidor, "mobs.especiales");
            h.ok("sin la seccion en el servidor se usa la del jar", vista != null && vista.isConfigurationSection("ghast"));
            h.ok("y no se crea en el config del servidor", !servidor.saveToString().contains("especiales"));
            servidor.set("mobs.especiales.activos", false);
            ConfigurationSection propia = seccion(servidor, "mobs.especiales");
            h.ok("con la seccion en el servidor manda la suya",
                    propia != null && !propia.getBoolean("activos", true) && leer(propia, new ArrayList<>()).isEmpty());
        }

        // --- buscar sitio en un mundo de mentira
        Mundo m = new Mundo();
        m.suelo = 60;
        h.ok("suelo llano: cabe un creaking", suelo(m, 0, 60, 0, 3));
        h.ok("el suelo ha de ser firme", !suelo(m, 0, 61, 0, 2));
        m.poner(0, 63, 0, Celda.SOLIDO);
        h.ok("una rama a tres bloques: no cabe un creaking", !suelo(m, 0, 60, 0, 3));
        h.ok("pero si un zombi", suelo(m, 0, 60, 0, 2));
        m.poner(0, 61, 0, Celda.AGUA);
        h.ok("con agua a los pies no", !suelo(m, 0, 60, 0, 2));
        m.poner(5, 61, 5, Celda.PASABLE);
        h.ok("la hierba alta no estorba", suelo(m, 5, 60, 5, 2));

        m = new Mundo();
        m.suelo = 60;
        h.igual("aire libre: el ghast va a 8 sobre el suelo", 68, aire(m, 0, 60, 0, 8, 5));
        m.poner(2, 70, -2, Celda.SOLIDO);
        h.igual("una copa dentro del cubo lo corta", null, aire(m, 0, 60, 0, 8, 5));
        h.igual("fuera del cubo no molesta", 68, aire(m, 10, 60, 10, 8, 5));
        m.poner(12, 72, 10, Celda.PASABLE);
        h.igual("tampoco caben lianas dentro del cubo", null, aire(m, 10, 60, 10, 8, 5));
        h.igual("a 13 ya esta por encima de la liana", 73, aire(m, 10, 60, 10, 13, 5));

        m = new Mundo();
        m.suelo = 55;
        m.agua(0, 3, 0, 3, 56, 62);
        h.igual("lago de 7: el guardian va sumergido bajo la superficie", 60, agua(m, 0, 62, 0, 3, 2));
        h.igual("el cubo cabe tambien pegado al borde del lago", 60, agua(m, 2, 62, 2, 3, 2));
        h.igual("en la orilla (el cubo se sale del lago) no", null, agua(m, 3, 62, 3, 3, 2));
        h.igual("sin agua en la superficie no", null, agua(m, 10, 55, 10, 3, 2));
        m = new Mundo();
        m.suelo = 60;
        m.agua(0, 3, 0, 3, 61, 62);
        h.igual("charco de 2: no llega a la profundidad", null, agua(m, 0, 62, 0, 3, 2));
        m = new Mundo();
        m.suelo = 50;
        m.agua(0, 0, 0, 0, 51, 60);
        h.igual("pozo de uno de ancho: no cabe el guardian", null, agua(m, 0, 60, 0, 3, 2));
        h.igual("pero si un pez de un bloque", 59, agua(m, 0, 60, 0, 3, 1));
        return h.lineas();
    }

    private static List<String> claves(List<Especial> l) {
        List<String> out = new ArrayList<>();
        for (Especial e : l) out.add(e.clave());
        return out;
    }

    /** Si el nombre es una habilidad de los esbirros de EDM (MinionAbility). */
    static boolean habilidadEdm(String nombre) {
        try {
            net.ederus.edm.anomaly.minions.MinionAbility.valueOf(nombre.trim().toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    /** El config.yml que va dentro del jar, o null si no se puede leer. */
    private static YamlConfiguration configDelJar() {
        try (InputStream in = Apariciones.class.getClassLoader().getResourceAsStream("config.yml")) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** Un mundo de mentira para el autotest: todo solido hasta 'suelo', aire encima, y lo que se ponga. */
    private static final class Mundo implements Sondeo {
        int suelo = 60;
        private final Map<String, Celda> puestos = new HashMap<>();

        void poner(int x, int y, int z, Celda c) {
            puestos.put(x + "," + y + "," + z, c);
        }

        /** Agua en la caja de x0..x1, z0..z1, y0..y1. */
        void agua(int x0, int x1, int z0, int z1, int y0, int y1) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    for (int y = y0; y <= y1; y++) poner(x, y, z, Celda.AGUA);
                }
            }
        }

        @Override
        public Celda en(int x, int y, int z) {
            Celda c = puestos.get(x + "," + y + "," + z);
            if (c != null) return c;
            return y <= suelo ? Celda.SOLIDO : Celda.AIRE;
        }
    }
}
