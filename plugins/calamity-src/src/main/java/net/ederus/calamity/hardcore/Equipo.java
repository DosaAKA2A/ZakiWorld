package net.ederus.calamity.hardcore;

import net.ederus.edm.goditems.api.EquipoApi;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * Calamity 1.6 · Lo que las piezas de MMOItems hacen DENTRO de Calamity.
 *
 * Hasta la 1.5 esto se leia de un equipo.yml propio, con su lector de MMOItems (LectorMmo), su cache
 * y sus topes. Desde la 1.6 lo hace GodItems (modulo de EDM) para todos los plugins a la vez: la
 * decision de Dosa es que las armaduras se crean en MMOItems y GodItems les da los efectos por pieza y
 * por set. Aqui ya solo queda:
 *   - preguntar a GodItems (EquipoApi) cuanto suma cada jugador en las claves "calamity.*", ya
 *     sumadas (piezas + escalones de set) y TOPADAS: los topes viven en un solo sitio, el YAML de
 *     GodItems (plugins/EDM/goditems/equipo/calamity.yml);
 *   - lo que hace cada efecto (las funciones puras de abajo, las mismas de la 1.4) y los dos ganchos
 *     de dano (PARCA y Ecos).
 *
 * Donde se aplica cada uno (sin cambios desde la 1.4):
 *   cordura-drenaje        Hardcore.drenar                           el drenaje x (1 - f)
 *   cordura-alucinaciones  Alucinaciones.tirada                      la probabilidad x (1 - f)
 *   parca-dano-recibido    onDanoParca (aqui) y DanoVerdadero.aplicar el dano de la PARCA x (1 - f)
 *   eco-dano               onDanoEco (aqui)                          el dano a un Eco x (1 + f)
 *   esencias-bonus         Grifo.alMorir, Minijefes.repartir, Cofres  n x (1 + f), antes de la Aduana
 *   hambre, durabilidad    Hardcore.onHambre / onDurabilidad         el x2 pasa a 1 + (2 - 1) x (1 - f)
 *   caidas                 Hardcore.onEntorno (solo FALL)            lo mismo con dano-caida
 *   niebla                 Hardcore.nieblaDeNoche                    ceniza y oscuridad x (1 - f)
 *
 * Sin GodItems (un EDM sin la API, o GodItems apagado) Calamity no rompe: todo vale 0 y cada regla
 * hace exactamente lo de antes; se avisa una vez en consola. Sin equipo, igual: f = 0 devuelve el
 * valor de entrada, y el autotest "equipo" lo compara contra las formulas de Calamity 1.3.
 */
final class Equipo implements Listener {

    /** Lo que puede hacer una pieza en Calamity. En GodItems es la clave "calamity." + clave. */
    enum Efecto {
        CORDURA_DRENAJE("cordura-drenaje", 0.50, "drenaje de cordura"),
        CORDURA_ALUCINACIONES("cordura-alucinaciones", 0.50, "probabilidad de alucinación"),
        PARCA_DANO_RECIBIDO("parca-dano-recibido", 0.30, "daño recibido de la PARCA"),
        ECO_DANO("eco-dano", 0.50, "daño a los Ecos"),
        ESENCIAS_BONUS("esencias-bonus", 0.25, "Esencias de mobs y cofres"),
        HAMBRE("hambre", 0.50, "castigo extra de hambre"),
        DURABILIDAD("durabilidad", 0.50, "castigo extra de durabilidad"),
        CAIDAS("caidas", 0.50, "castigo extra de caídas"),
        NIEBLA("niebla", 0.50, "niebla y oscuridad de noche");

        final String clave;
        /** El tope que tenia Calamity 1.5 de serie: el calamity.yml de GodItems trae los mismos. */
        final double topeDeSerie;
        final String texto;

        Efecto(String clave, double topeDeSerie, String texto) {
            this.clave = clave;
            this.topeDeSerie = topeDeSerie;
            this.texto = texto;
        }

        /** La clave en GodItems: "calamity.cordura-drenaje". */
        String api() {
            return PREFIJO + clave;
        }
    }

    /** El prefijo de las claves de Calamity en GodItems. */
    static final String PREFIJO = "calamity.";
    /** El fichero de antes: si sigue en la carpeta con piezas, se avisa de que ya no se lee. */
    static final String FICHERO_VIEJO = "equipo.yml";
    static final String DONDE = "plugins/EDM/goditems/equipo/calamity.yml";

    /** Si la API de GodItems existe en el EDM instalado. Se mira una vez: un EDM viejo no la trae. */
    private static final boolean HAY_API = hayApi();

    private final Hardcore hc;
    private final SecureRandom azar = new SecureRandom();
    /** Lo que quedo por gastar de la ultima perdida de hambre y del ultimo desgaste (ver hambre()). */
    private final Map<UUID, double[]> restoHambre = new HashMap<>();
    private final Map<UUID, double[]> restoDurabilidad = new HashMap<>();
    private boolean avisadoSinApi;

    Equipo(Hardcore hc) {
        this.hc = hc;
        cargar();
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Subcomandos.lw().registrar("equipo",
                "equipo [jugador]: lo que cuenta de su equipo en Calamity (efectos de GodItems) y lo que suma",
                "ederus.mundos", this::comando, args -> args.length == 2 ? Entregas.nombresConectados() : List.of());
        Autotest.registrar("equipo", this::autotest);
    }

    void parar() {
        HandlerList.unregisterAll(this);
        restoHambre.clear();
        restoDurabilidad.clear();
    }

    private static boolean hayApi() {
        try {
            Class.forName("net.ederus.edm.goditems.api.EquipoApi", false, Equipo.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True si GodItems esta en marcha y se le puede preguntar. */
    static boolean conApi() {
        try {
            return HAY_API && Api.disponible();
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================ carga

    /**
     * Ya no hay nada que leer: solo se comprueba que GodItems esta y se avisa si queda el equipo.yml
     * viejo con piezas o sets (que ya no hacen nada). Devuelve el resumen para /calamidad reload.
     */
    String cargar() {
        if (!conApi() && !avisadoSinApi) {
            avisadoSinApi = true;
            hc.plugin().getLogger().warning("[Calamity] GodItems (EDM 1.72.2 o mas) no esta en marcha:"
                    + " el equipo no hace nada en Calamity hasta que este.");
        }
        File viejo = new File(hc.plugin().getDataFolder(), FICHERO_VIEJO);
        if (viejo.exists() && viejoConPiezas(viejo)) {
            hc.plugin().getLogger().warning("[Calamity] " + FICHERO_VIEJO + " ya no se lee (Calamity 1.6):"
                    + " pasa sus piezas y sets a " + DONDE + " con las claves calamity.*");
        }
        return resumen();
    }

    private static boolean viejoConPiezas(File f) {
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.options().pathSeparator('/');
            y.load(f);
            var p = y.getConfigurationSection("piezas");
            var s = y.getConfigurationSection("sets");
            return (p != null && !p.getKeys(false).isEmpty()) || (s != null && !s.getKeys(false).isEmpty());
        } catch (Exception e) {
            return false;
        }
    }

    String resumen() {
        if (!conApi()) return "sin GodItems: el equipo no hace nada";
        return "de GodItems (" + Api.resumen() + ")" + (Api.hayClaves() ? "" : ", ninguna pieza da claves calamity.*");
    }

    // ================================================================= cuentas

    /** Lo que el equipo de ese jugador pone en ese efecto, ya topado por GodItems; 0 si nada. */
    double valor(Player p, Efecto e) {
        if (p == null || !conApi()) return 0;
        try {
            return Api.efecto(p, e.api());
        } catch (Throwable t) {
            return 0;
        }
    }

    // ============================================ lo que hace cada efecto (puro)

    /** x menos la fraccion f (drenaje, alucinaciones, dano de la PARCA, niebla). Con f = 0, x tal cual. */
    static double menos(double x, double f) {
        return f == 0 ? x : x * (1 - f);
    }

    /** x mas la fraccion f (dano a los Ecos). Con f = 0, x tal cual. */
    static double mas(double x, double f) {
        return f == 0 ? x : x * (1 + f);
    }

    /**
     * Un castigo de Calamity (hambre x2, durabilidad x2, caidas x2) con el equipo: f quita esa fraccion
     * de lo que pasa de 1. Con 2,0 y f = 0,5 queda en 1,5. Con f = 0, o la regla apagada (<= 1), igual.
     */
    static double castigo(double factor, double f) {
        if (f == 0 || factor <= 1) return factor;
        return 1 + (factor - 1) * (1 - f);
    }

    /**
     * La comida que le queda tras perder hambre (Hardcore.onHambre). Con f = 0 es la cuenta de siempre,
     * tal cual. Con equipo, el castigo es fraccionario (1,5 puntos) y la comida va en enteros: lo que no
     * llega a un punto se guarda en resto y se cobra en la siguiente perdida.
     */
    static int hambre(int antes, int nueva, double factor, double f, double[] resto) {
        if (f == 0 || resto == null) return (int) Math.max(0, antes - (antes - nueva) * factor);
        double pierde = (antes - nueva) * castigo(factor, f) + resto[0];
        int entero = (int) Math.floor(pierde);
        resto[0] = pierde - entero;
        return Math.max(0, antes - entero);
    }

    /** El desgaste de un uso (Hardcore.onDurabilidad), con el mismo resto que hambre(). Con f = 0, el de siempre. */
    static int durabilidad(int dano, double factor, double f, double[] resto) {
        if (f == 0 || resto == null) return factor > 1 ? (int) Math.ceil(dano * factor) : dano;
        if (factor <= 1) return dano;
        double gasta = dano * castigo(factor, f) + resto[0];
        int entero = (int) Math.floor(gasta);
        resto[0] = gasta - entero;
        return Math.max(0, entero);
    }

    /**
     * Esencias con el bono: n x (1 + f). La parte que no llega a una Esencia entera sale con esa
     * probabilidad (1 Esencia con +25 % = 2 una de cada cuatro veces): de media da justo lo prometido.
     */
    static int esencias(int n, double f, DoubleSupplier azar) {
        if (n <= 0 || f == 0) return n;
        double exacto = n * (1 + f);
        int entero = (int) Math.floor(exacto + 1e-9);
        double sobra = exacto - entero;
        if (sobra > 1e-9 && azar.getAsDouble() < sobra) entero++;
        return Math.max(0, entero);
    }

    /** Particulas de ceniza de la niebla. Con f = 0, las mismas. */
    static int particulasNiebla(int n, double f) {
        return f == 0 ? n : (int) Math.max(0, Math.round(n * (1 - f)));
    }

    /** Ticks de una racha de oscuridad de tantos segundos. Con f = 0, segundos x 20. */
    static int ticksOscuridad(int segundos, double f) {
        return f == 0 ? segundos * 20 : (int) Math.max(0, Math.round(segundos * 20 * (1 - f)));
    }

    /** Si una entidad es la PARCA (su esqueleto) o una de sus planideras. */
    static boolean esDeParca(Entity e) {
        String a = Marcas.amenaza(e);
        return "parca".equals(a) || "planidera".equals(a);
    }

    /**
     * Escala el dano FINAL (el que de verdad quita, tras armadura y demas) por factor. Bukkit recalcula los
     * modificadores desde la base y la armadura no es lineal: se corrige hasta clavarlo.
     */
    static void escalarFinal(EntityDamageEvent e, double factor) {
        if (factor == 1) return;
        double fin = e.getFinalDamage();
        if (fin <= 1e-9) {
            e.setDamage(Math.max(0, e.getDamage() * factor));
            return;
        }
        double deseado = Math.max(0, fin * factor);
        for (int i = 0; i < 4; i++) {
            double ahora = e.getFinalDamage();
            if (ahora <= 1e-9 || Math.abs(ahora - deseado) <= 1e-6) break;
            e.setDamage(Math.max(0, e.getDamage() * deseado / ahora));
        }
    }

    /** El hueco de armadura de un item, o null (no una cabeza ni una calabaza). Igual que en GodItems. */
    static EquipmentSlot sitio(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        Material m = item.getType();
        if (m.isBlock()) return null;
        EquipmentSlot s;
        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        try {
            s = meta != null && meta.hasEquippable() ? meta.getEquippable().getSlot() : m.getEquipmentSlot();
        } catch (Throwable t) {
            s = m.getEquipmentSlot();
        }
        return s == EquipmentSlot.HEAD || s == EquipmentSlot.CHEST || s == EquipmentSlot.LEGS || s == EquipmentSlot.FEET
                ? s : null;
    }

    // ================================================== ganchos (desde otros modulos)

    /** Esencias de un mob, un minijefe o un cofre con el bono de su equipo (antes de la Aduana). */
    int esencias(Player p, int n) {
        if (p == null || n <= 0) return n;
        return esencias(n, valor(p, Efecto.ESENCIAS_BONUS), azar::nextDouble);
    }

    double[] restoHambre(Player p) {
        return restoHambre.computeIfAbsent(p.getUniqueId(), k -> new double[1]);
    }

    double[] restoDurabilidad(Player p) {
        return restoDurabilidad.computeIfAbsent(p.getUniqueId(), k -> new double[1]);
    }

    // ================================================================== listener

    /** Quien pega de verdad: la causa del dano, o el que dispara, o el que golpea. */
    private static Entity causa(EntityDamageEvent e) {
        try {
            Entity c = e.getDamageSource().getCausingEntity();
            if (c != null) return c;
        } catch (Throwable ignorado) {
            // Sin DamageSource: se mira el golpe a la antigua.
        }
        if (e instanceof EntityDamageByEntityEvent be) {
            Entity d = be.getDamager();
            if (d instanceof Projectile pr && pr.getShooter() instanceof Entity tirador) return tirador;
            return d;
        }
        return null;
    }

    /**
     * parca-dano-recibido: lo que pega la PARCA, en sus dos implementaciones: su golpe, sus tecnicas y lo
     * que dispare. El dano verdadero (Siega, Sentencia) lo recorta DanoVerdadero.aplicar. HIGHEST: se
     * escala lo que quede despues de armadura y de lo que hayan hecho los demas.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDanoParca(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (DanoVerdadero.enCurso.contains(p.getUniqueId()) || !esDeParca(causa(e))) return;
        double f = hc.valor("equipo", () -> valor(p, Efecto.PARCA_DANO_RECIBIDO), 0.0);
        if (f != 0) escalarFinal(e, 1 - f);
    }

    /**
     * eco-dano: lo que un jugador le hace a un Eco. HIGH: antes de que Amenazas lo escale a la vida logica
     * y lo tope por golpe en HIGHEST, asi que el tope por golpe sigue mandando.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDanoEco(EntityDamageEvent e) {
        if (!"eco".equals(Marcas.amenaza(e.getEntity()))) return;
        if (!(causa(e) instanceof Player p)) return;
        double f = hc.valor("equipo", () -> valor(p, Efecto.ECO_DANO), 0.0);
        if (f != 0) escalarFinal(e, 1 + f);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSalir(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        restoHambre.remove(u);
        restoDurabilidad.remove(u);
    }

    // ================================================================== comando

    /**
     * /calamidad equipo [jugador]: el informe de GodItems acotado a calamity.* (cada hueco, que cuenta y
     * por que no, los sets y el total con topes) y, para los castigos, en que se queda el x2.
     */
    private void comando(CommandSender quien, String[] args) {
        Player p = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : (quien instanceof Player j ? j : null);
        if (p == null) {
            quien.sendMessage(Paleta.aviso(args.length >= 2 ? "No encuentro a " + args[1] + " conectado."
                    : "Uso: /calamidad equipo <jugador>"));
            return;
        }
        quien.sendMessage(Paleta.mensaje(Component.text("Equipo de ").append(Component.text(p.getName(), Paleta.DETALLE))
                .append(Component.text(" en Calamity"))));
        if (!conApi()) {
            quien.sendMessage(Component.text("  GodItems no está en marcha (hace falta EDM 1.72.2 o más): el equipo"
                    + " no hace nada en Calamity.", Paleta.AVISO));
            return;
        }
        quien.sendMessage(Component.text("  Los efectos y los topes viven en " + DONDE + " (GodItems).", Paleta.TENUE));
        for (String l : Api.informe(p)) quien.sendMessage(net.ederus.edm.comun.Estilo.legado(l));
        for (Efecto e : new Efecto[]{Efecto.HAMBRE, Efecto.DURABILIDAD, Efecto.CAIDAS}) {
            double v = valor(p, e);
            if (v == 0) continue;
            quien.sendMessage(Component.text("  " + e.texto + ": " + efectoReal(e, v), Paleta.TENUE));
        }
        if (!hc.esHardcore(p)) {
            quien.sendMessage(Component.text("  Está fuera de Calamity: todo esto solo cuenta dentro"
                    + " (menos el daño de la PARCA, que cuenta donde le pegue).", Paleta.TENUE));
        }
    }

    /** Para los castigos, en que se queda el x2 de la config con ese valor: "x2 → x1,5". */
    private String efectoReal(Efecto e, double v) {
        String clave = switch (e) {
            case HAMBRE -> "hambre";
            case DURABILIDAD -> "durabilidad";
            case CAIDAS -> "dano-caida";
            default -> null;
        };
        if (clave == null) return "";
        double base = hc.cfg().getDouble("dificultad." + clave, 2.0);
        return "x" + numero(base) + " → x" + numero(castigo(base, v));
    }

    /** 0,125 -> "12,5 %"; 0,1 -> "10 %". */
    static String porcentaje(double f) {
        return numero(f * 100) + " %";
    }

    private static String numero(double v) {
        double r = Math.round(v * 10) / 10.0;
        return (r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r)).replace('.', ',');
    }

    // ================================================================= autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();

        // 1. Sin equipo, todo igual que en Calamity 1.3 (las formulas de entonces, copiadas aqui).
        h.ok("sin equipo el drenaje no cambia", menos(1.7, 0) == 1.7 && menos(2.0 * 2.0 * 1.3, 0) == 2.0 * 2.0 * 1.3);
        h.ok("sin equipo la probabilidad de alucinar no cambia", menos(2 / 3.0, 0) == 2 / 3.0);
        h.ok("sin equipo el dano de la PARCA no cambia (factor 1)", menos(1.0, 0) == 1.0);
        h.ok("sin equipo el dano a un Eco no cambia (factor 1)", mas(1.0, 0) == 1.0);
        h.ok("sin equipo las caidas siguen x2", castigo(2.0, 0) == 2.0 && castigo(3.5, 0) == 3.5);
        h.ok("una regla apagada (x1 o menos) no la toca ni el equipo", castigo(1.0, 0.5) == 1.0 && castigo(0.5, 0.5) == 0.5);
        boolean hambreIgual = true, durIgual = true;
        for (double factor : new double[]{2.0, 1.5, 3.0, 2.5}) {
            for (int antes = 0; antes <= 20; antes++) {
                for (int nueva = 0; nueva < antes; nueva++) {
                    int viejo = (int) Math.max(0, antes - (antes - nueva) * factor);
                    hambreIgual &= hambre(antes, nueva, factor, 0, new double[1]) == viejo
                            && hambre(antes, nueva, factor, 0, null) == viejo;
                }
            }
        }
        for (double factor : new double[]{2.0, 1.5, 1.0, 0.5, 3.0}) {
            for (int dano = 0; dano <= 12; dano++) {
                int viejo = factor > 1 ? (int) Math.ceil(dano * factor) : dano;
                durIgual &= durabilidad(dano, factor, 0, new double[1]) == viejo;
            }
        }
        h.ok("sin equipo el hambre pierde lo mismo que en 1.3 (x2, x1,5, x2,5, x3; 0-20)", hambreIgual);
        h.ok("sin equipo la durabilidad gasta lo mismo que en 1.3", durIgual);
        h.igual("sin equipo la niebla trae las 14 particulas de ceniza", 14, particulasNiebla(14, 0));
        h.igual("sin equipo la oscuridad dura lo mismo (3 s = 60 ticks)", 60, ticksOscuridad(3, 0));
        boolean esIgual = true;
        for (int n = 0; n <= 6; n++) esIgual &= esencias(n, 0, () -> 0.0) == n;
        h.ok("sin equipo las Esencias no cambian", esIgual);

        // 2. Con equipo: los castigos fraccionarios se cobran de verdad (no se redondean a x2).
        h.cerca("x2 con la mitad quitada: x1,5", 1.5, castigo(2.0, 0.5), 1e-9);
        double[] r = new double[1];
        int comida = 20;
        for (int i = 0; i < 10; i++) comida = hambre(comida, comida - 1, 2.0, 0.5, r);
        h.igual("hambre x1,5: diez perdidas de 1 quitan 15 (no 20)", 5, comida);
        r = new double[1];
        int gastado = 0;
        for (int i = 0; i < 10; i++) gastado += durabilidad(1, 2.0, 0.5, r);
        h.igual("durabilidad x1,5: diez usos gastan 15 (no 20)", 15, gastado);
        h.igual("Esencias +25 % de 4: 5 justas", 5, esencias(4, 0.25, () -> 0.99));
        h.igual("Esencias +25 % de 1 con la tirada a favor: 2", 2, esencias(1, 0.25, () -> 0.10));
        h.igual("Esencias +25 % de 1 con la tirada en contra: 1", 1, esencias(1, 0.25, () -> 0.90));
        h.igual("oscuridad de 3 s con la mitad: 30 ticks", 30, ticksOscuridad(3, 0.5));
        h.igual("ceniza con la mitad: 7 particulas", 7, particulasNiebla(14, 0.5));

        // 3. GodItems: la API y las nueve claves declaradas con sus topes (en un solo sitio, alli).
        h.ok("el EDM instalado trae la API de equipo de GodItems (EDM 1.72.2 o mas)", HAY_API);
        if (!conApi()) {
            h.ok("GodItems en marcha (sin el, el equipo no hace nada en Calamity)", false);
            return h.lineas();
        }
        h.ok("contrato de la API de GodItems en version 1 o mas", Api.version() >= 1);
        for (Efecto e : Efecto.values()) {
            Double tope = Api.tope(e.api());
            h.ok(e.api() + " declarada en GodItems con tope (" + (tope == null ? "sin tope" : porcentaje(tope)) + ")",
                    tope != null);
            if (tope != null && Math.abs(tope - e.topeDeSerie) > 1e-9) {
                h.ok("nota: " + e.api() + " va con tope " + porcentaje(tope) + " (el de serie era "
                        + porcentaje(e.topeDeSerie) + ")", true);
            }
        }
        File viejo = new File(hc.plugin().getDataFolder(), FICHERO_VIEJO);
        h.ok(viejo.exists() ? FICHERO_VIEJO + " sigue en la carpeta pero ya no se lee (se puede borrar)"
                : "no queda " + FICHERO_VIEJO + " viejo", true);

        // 4. Items reales: cada pieza que da calamity.* se genera con MMOItems y, puesta, da lo suyo.
        List<String> piezas = Api.piezas();
        if (piezas.isEmpty()) {
            h.ok("ninguna pieza da calamity.* todavia en " + DONDE + " (nada real que probar)", true);
            return h.lineas();
        }
        for (String id : piezas) {
            ItemStack it = Api.crear(id);
            h.ok("MMOItems genera " + id, it != null);
            if (it == null) continue;
            h.igual("el " + id + " real se reconoce", id, Api.identidad(it));
            EquipmentSlot donde = sitio(it) != null ? sitio(it) : EquipmentSlot.HAND;
            Map<EquipmentSlot, ItemStack> eq = new EnumMap<>(EquipmentSlot.class);
            eq.put(donde, it);
            Map<String, Double> sim = Api.simular(eq);
            for (Map.Entry<String, Double> x : Api.deLaPieza(id).entrySet()) {
                double esperado = x.getValue();
                Double tope = Api.tope(x.getKey());
                if (tope != null) esperado = Math.min(tope, esperado);
                h.cerca("el " + id + " real, en " + donde + ", da su " + x.getKey(), esperado,
                        sim.getOrDefault(x.getKey(), 0.0), 1e-9);
            }
            if (sitio(it) != null && !Api.deLaPieza(id).isEmpty()) {
                Map<EquipmentSlot, ItemStack> mano = new EnumMap<>(EquipmentSlot.class);
                mano.put(EquipmentSlot.HAND, it);
                h.ok("el " + id + " (armadura) en la mano no da nada", Api.simular(mano).isEmpty());
            }
        }
        return h.lineas();
    }

    /**
     * Las llamadas a EquipoApi, en una clase aparte: solo se carga si la API existe (HAY_API), asi que
     * un EDM viejo no tumba Calamity con un NoClassDefFoundError.
     */
    private static final class Api {

        static boolean disponible() {
            return EquipoApi.disponible();
        }

        static int version() {
            return EquipoApi.version();
        }

        static double efecto(Player p, String clave) {
            return EquipoApi.efecto(p, clave);
        }

        static Double tope(String clave) {
            return EquipoApi.tope(clave);
        }

        static boolean hayClaves() {
            return EquipoApi.hayClaves(PREFIJO);
        }

        static String resumen() {
            return EquipoApi.resumen();
        }

        static List<String> informe(Player p) {
            return new ArrayList<>(EquipoApi.informe(p, PREFIJO));
        }

        static List<String> piezas() {
            return EquipoApi.piezas(PREFIJO);
        }

        static ItemStack crear(String id) {
            return EquipoApi.crear(id.toUpperCase(Locale.ROOT));
        }

        static String identidad(ItemStack it) {
            return EquipoApi.identidad(it);
        }

        static Map<String, Double> simular(Map<EquipmentSlot, ItemStack> eq) {
            return EquipoApi.simular(eq, PREFIJO);
        }

        static Map<String, Double> deLaPieza(String id) {
            return EquipoApi.efectosDePieza(id, PREFIJO);
        }
    }
}
