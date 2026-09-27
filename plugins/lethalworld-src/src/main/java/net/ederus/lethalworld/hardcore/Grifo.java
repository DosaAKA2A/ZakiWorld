package net.ederus.lethalworld.hardcore;

import net.ederus.edm.anomaly.minions.MinionManager;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.MobCoins;
import net.ederus.lethalworld.MobsLethal;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * M2 · El grifo de los mobs de Calamity: lo que da cada muerte.
 *
 * Orden (DIS M2, cada paso corta los siguientes):
 * 1. Cosecha de la PARCA (o tierra segada): nada de nada, ni drops ni XP.
 * 2. Cierre de spawners: un mob de jaula, huevo o golem construido no da Esencias ni
 *    Reliquias, y sus MobCoins son las de la tabla del Survival SIN el (1 + N/20). Una granja
 *    de spawners en Calamity no puede rendir mas que la misma granja fuera.
 * 3. Cuota de jugador: si menos de la mitad de su vida se la quitaron jugadores (caida,
 *    asfixia, lava con remate), lo mismo que el punto 2.
 * 4. Si no: MobCoins, Esencias y Reliquias en UN pago de la Aduana, XP por clase (mobs.xp).
 * 5. Minijefe: reparto por dano entre los participantes (Minijefes).
 *
 * Las Esencias de mobs decaen con lo que ya has sacado en la ultima hora: f = max(0,2,
 * 1 - esencias / 30). Sin eso, una sesion de tres horas matando lo mismo daba Esencias como
 * para el Manto en una tarde (PLAN sec. 3.4, perfil "Intenso").
 *
 * Es publica porque la llama MobsLethal (otro paquete).
 */
public final class Grifo implements Listener {

    /**
     * La clase del mob (comun, destacado, minijefe, estructura). Es la marca de MobsLethal y
     * va en "edm:" porque los mobs que ya hay en Calamity la llevan asi (MobsLethal lo explica).
     * No es de Calamity, por eso no esta en Marcas.
     */
    private static final NamespacedKey CLASE = new NamespacedKey("edm", "lethal_world_mob");
    private static final TextColor NARANJA = TextColor.color(0xE8903C);
    private static final long HORA = 3_600_000L;

    /** Por donde va una muerte. */
    enum Via { NADA, CERRADO, NORMAL, MINIJEFE }

    /** Lo que da una muerte que no es de minijefe. xp -1 = la de vanilla. */
    record Plan(int esencias, List<Integer> grados, int xp, String claseMc, int nivelMc, boolean vaciar) {
    }

    private final Hardcore hc;
    private final SecureRandom azar = new SecureRandom();
    /** Esencias de mobs de la ultima hora por jugador: [millis, n]. Se poda al leer. */
    private final Map<UUID, Deque<long[]>> esenciasHora = new HashMap<>();
    /** Minijefe vivo -> dano de cada jugador. Se borra al morir o en la purga si ya no existe. */
    private final Map<UUID, Map<UUID, Double>> danoMinijefe = new HashMap<>();
    /** Ultimo destello de "La cosecha es suya" por jugador (uno cada 30 s como mucho). */
    private final Map<UUID, Long> ultimaCosecha = new HashMap<>();
    private long ultimaPurga;

    Grifo(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("grifo", this::autotest);
    }

    void parar() {
        esenciasHora.clear();
        danoMinijefe.clear();
        ultimaCosecha.clear();
    }

    // ------------------------------------------------------------ dano de jugadores

    /**
     * Apunta el dano que le hacen jugadores a cada mob de Calamity: la cuota (PDC
     * lethal_world:dano_jugador, que viaja con el mob) y, en los minijefes, quien pego cuanto.
     * En MONITOR y sin los cancelados: solo cuenta lo que de verdad le quito vida.
     *
     * Ademas del golpe y del proyectil, cuenta el dano que sigue a un arma de jugador (fuego
     * de Aspecto igneo o de Llama, veneno, wither) si un jugador le pego en los ultimos 5 s
     * (getKiller de vanilla). Sin esto, matar con una espada de fuego salia "cerrado". La
     * caida, la lava o la asfixia NO cuentan aunque le haya pegado alguien: son justo las
     * granjas con remate que la cuota tiene que cerrar.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDano(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity mob) || mob instanceof Player) return;
        if (!hc.esHardcore(mob.getWorld())) return;
        if (Marcas.esAmenaza(mob)) return;
        Player p;
        if (e instanceof EntityDamageByEntityEvent be) {
            p = jugadorDe(be.getDamager());
        } else {
            p = switch (e.getCause()) {
                case FIRE_TICK, POISON, WITHER -> mob.getKiller();
                default -> null;
            };
        }
        if (p == null) return;
        // Lo que pasa de su vida no cuenta: un golpe de 40 a un mob con 3 no es "40 de 20".
        double dano = Math.min(e.getFinalDamage(), mob.getHealth());
        if (dano <= 0) return;
        PersistentDataContainer pdc = mob.getPersistentDataContainer();
        Double antes = pdc.get(Marcas.DANO_JUGADOR, PersistentDataType.DOUBLE);
        pdc.set(Marcas.DANO_JUGADOR, PersistentDataType.DOUBLE, (antes == null ? 0 : antes) + dano);
        if ("minijefe".equals(pdc.get(CLASE, PersistentDataType.STRING))) {
            danoMinijefe.computeIfAbsent(mob.getUniqueId(), k -> new LinkedHashMap<>())
                    .merge(p.getUniqueId(), dano, Double::sum);
        }
        purgar();
    }

    /** El jugador detras de un golpe: el mismo, o quien disparo el proyectil. */
    private static Player jugadorDe(Entity danador) {
        if (danador instanceof Player p) return p;
        if (danador instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    /** Una vez por minuto: minijefes que ya no existen, horas pasadas y destellos viejos. */
    private void purgar() {
        long ahora = System.currentTimeMillis();
        if (ahora - ultimaPurga < 60_000) return;
        ultimaPurga = ahora;
        danoMinijefe.keySet().removeIf(id -> {
            Entity en = hc.plugin().getServer().getEntity(id);
            return en == null || !en.isValid();
        });
        esenciasHora.values().forEach(d -> d.removeIf(x -> ahora - x[0] > HORA));
        esenciasHora.values().removeIf(Deque::isEmpty);
        ultimaCosecha.values().removeIf(t -> ahora - t > 30_000);
    }

    // --------------------------------------------------------------- la muerte

    /** Muerte de un mob con la marca edm:lethal_world_mob en un mundo hardcore. killer puede ser null. */
    public void alMorir(EntityDeathEvent e, Player killer, String marca) {
        LivingEntity mob = e.getEntity();
        Map<UUID, Double> danos = danoMinijefe.remove(mob.getUniqueId());
        if (!hc.esHardcore(mob.getWorld())) return;
        ConfigurationSection c = hc.cfg();
        boolean cosecha = hc.parca() != null
                && hc.valor("parca", () -> hc.parca().cosechando(mob.getLocation()), false);
        Double dj = mob.getPersistentDataContainer().get(Marcas.DANO_JUGADOR, PersistentDataType.DOUBLE);
        Via via = via(cosecha, deSpawner(mob), razon(mob), dj == null ? 0 : dj,
                Compat.getAttribute(mob, "max_health", Math.max(1, mob.getHealth())),
                c.getDouble("esencias.cuota-jugador", 0.5), marca);

        if (via == Via.NADA) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            if (killer != null) cosechaSuya(killer);
            return;
        }
        MobsLethal mobs = hc.plugin().mobs();
        MinionManager mm = mobs == null ? null : mobs.minionManager();
        int nivel = mm == null ? 1 : Math.max(1, mm.levelOf(mob));

        if (via == Via.MINIJEFE) {
            // Sin asesino jugador vanilla no suelta XP, y 1.500 en el suelo serian de cualquiera.
            if (killer != null) e.setDroppedExp(xp(mobsCfg(), "minijefe"));
            Minijefes mj = hc.minijefes();
            if (mj != null) {
                String tipo = null;
                try {
                    if (mm != null && mm.typeOf(mob) != null) tipo = mm.typeOf(mob).id();
                } catch (Throwable ignorado) {
                    // Sin tipo no hay Sello ni piedad; el resto del reparto sigue.
                }
                String t = tipo;
                hc.seguro("minijefes", () -> mj.alMorir(mob, killer, nivel, t, danos));
            }
            return;
        }
        // Sin Aduana no se paga nada: mejor eso que pagar sin topes (regla 7).
        if (killer == null || hc.aduana() == null) return;

        Plan plan = planificar(via, marca, nivel, f(killer.getUniqueId()), eclipse(), azar::nextDouble,
                c, mobsCfg());
        long mc = mobs == null ? 0 : mobs.mobcoinsDe(mob, plan.claseMc(), plan.nivelMc());
        List<ItemStack> reliquias = new ArrayList<>();
        Reliquias r = hc.reliquias();
        if (r != null && r.activas()) {
            String origen = "destacado".equals(marca) ? "destacado" : "mob";
            for (int g : plan.grados()) reliquias.add(r.crear(g, origen, null, nivel, null, false));
        }
        if (plan.xp() >= 0) e.setDroppedExp(plan.xp());
        if (plan.esencias() == 0 && mc <= 0 && reliquias.isEmpty()) return;
        Aduana.Pago pago = hc.aduana().pagar(killer, "mob", plan.esencias(), mc, reliquias,
                via == Via.CERRADO ? "cerrado" : (marca == null ? "comun" : marca));
        if (pago != null && pago.esencias() > 0) {
            apuntarEsencias(killer.getUniqueId(), pago.esencias());
            destelloEsencias(killer, pago.esencias(), pago.mc());
        }
        if (via == Via.NORMAL) {
            Contratos ct = hc.contratos();
            if (ct != null) {
                hc.seguro("contratos", () -> {
                    ct.progreso(killer, "mob", 1);
                    if ("destacado".equals(marca)) ct.progreso(killer, "destacado", 1);
                });
            }
        }
    }

    /** P-18, como mucho cada 30 s por jugador. */
    private void cosechaSuya(Player p) {
        long ahora = System.currentTimeMillis();
        Long antes = ultimaCosecha.get(p.getUniqueId());
        if (antes != null && ahora - antes < 30_000) return;
        ultimaCosecha.put(p.getUniqueId(), ahora);
        hc.cordura().destello(p, Component.text("La cosecha es suya.", Paleta.PARCA), 2);
    }

    /** P-M01, con las MobCoins del mismo pago: si no, un destello pisaria al otro. */
    void destelloEsencias(Player p, int esencias, long mc) {
        if (p == null || !p.isOnline() || !hc.esHardcore(p) || esencias <= 0) return;
        Component t = Component.text("+" + esencias + (esencias == 1 ? " Esencia" : " Esencias")
                + " de Calamidad", NARANJA);
        if (mc > 0) t = t.append(Component.text("  ·  +" + mc + " MobCoins", MobCoins.ORO));
        hc.cordura().destello(p, t, 2);
    }

    private ConfigurationSection mobsCfg() {
        ConfigurationSection s = hc.plugin().getConfig().getConfigurationSection("mobs");
        return s == null ? new YamlConfiguration() : s;
    }

    private double eclipse() {
        Eclipse ec = hc.eclipse();
        return ec == null ? 1.0 : hc.valor("eclipse", ec::factorBotin, 1.0);
    }

    private static boolean deSpawner(LivingEntity mob) {
        try {
            return mob.fromMobSpawner();
        } catch (Throwable t) {
            return false;
        }
    }

    /** El motivo del spawn por su nombre: asi no depende de que la constante exista en esta version. */
    private static String razon(LivingEntity mob) {
        try {
            return mob.getEntitySpawnReason().name();
        } catch (Throwable t) {
            return "";
        }
    }

    // -------------------------------------------------------- decisiones (puras)

    /** Por donde va una muerte. Sin Bukkit: la prueba la llama con numeros. */
    static Via via(boolean cosechando, boolean deSpawner, String razon, double danoJugador, double vidaMax,
                   double cuota, String marca) {
        if (cosechando) return Via.NADA;
        String r = razon == null ? "" : razon.toUpperCase(Locale.ROOT);
        boolean jaula = deSpawner || r.equals("SPAWNER") || r.equals("SPAWNER_EGG") || r.equals("DISPENSE_EGG")
                || r.equals("TRIAL_SPAWNER") || r.startsWith("BUILD_");
        if (jaula) return Via.CERRADO;
        if (vidaMax > 0 && danoJugador < cuota * vidaMax) return Via.CERRADO;
        return "minijefe".equals(marca) ? Via.MINIJEFE : Via.NORMAL;
    }

    /**
     * Lo que da una muerte por la via NORMAL o CERRADO. CERRADO: MobCoins de la tabla sin
     * nivel (clase comun, nivel 0: mobcoinsDe deja base x mundo, y mundo es 1,0) y nada mas;
     * la XP la de vanilla (5 de un zombi de jaula, no 20).
     */
    static Plan planificar(Via via, String marca, int nivel, double f, double eclipse, DoubleSupplier azar,
                           ConfigurationSection hardcore, ConfigurationSection mobs) {
        if (via == Via.NADA) return new Plan(0, List.of(), 0, "comun", 0, true);
        if (via == Via.CERRADO) return new Plan(0, List.of(), -1, "comun", 0, false);
        if (via == Via.MINIJEFE) return new Plan(0, List.of(), xp(mobs, "minijefe"), "minijefe", nivel, false);
        String clase = "destacado".equals(marca) ? "destacado" : "comun";

        int esencias = 0;
        ConfigurationSection es = hardcore.getConfigurationSection("esencias." + clase);
        double prob = es != null ? es.getDouble("prob", clase.equals("destacado") ? 0.25 : 0.03)
                : (clase.equals("destacado") ? 0.25 : 0.03);
        int min = es != null ? es.getInt("min", 1) : 1;
        int max = es != null ? es.getInt("max", clase.equals("destacado") ? 2 : 1) : (clase.equals("destacado") ? 2 : 1);
        if (hardcore.getBoolean("esencias.activo", true) && azar.getAsDouble() < prob * f * eclipse) {
            esencias = min + (int) Math.floor(azar.getAsDouble() * (Math.max(min, max) - min + 1));
        }

        List<Integer> grados = new ArrayList<>();
        if (hardcore.getBoolean("reliquias.activas", true)) {
            Map<Integer, Double> drop = probs(hardcore, "reliquias.drop." + clase,
                    clase.equals("destacado") ? Map.of(1, 0.20, 2, 0.05) : Map.of(1, 0.02));
            for (Map.Entry<Integer, Double> d : drop.entrySet()) {
                if (azar.getAsDouble() < d.getValue() * f * eclipse) {
                    grados.add(subirGrado(d.getKey(), nivel, hardcore, azar));
                }
            }
        }
        String claseMc = marca == null || marca.equals("estructura") ? "comun" : marca;
        return new Plan(esencias, grados, xp(mobs, clase), claseMc, nivel, false);
    }

    /**
     * Subida de grado por el nivel del mob (DIS M2 punto 6): la primera regla que encaje tira
     * una vez. Una I de un mob de N 80 puede quedarse en I o subir a II, no saltar a III.
     */
    static int subirGrado(int grado, int nivel, ConfigurationSection hardcore, DoubleSupplier azar) {
        List<Map<?, ?>> reglas = hardcore.getMapList("reliquias.subir-grado");
        if (reglas.isEmpty() && !hardcore.isList("reliquias.subir-grado")) {
            reglas = List.of(Map.of("nivel", 50, "de", 1, "a", 2, "prob", 0.20),
                    Map.of("nivel", 75, "de", 2, "a", 3, "prob", 0.20),
                    Map.of("nivel", 75, "de", 3, "a", 4, "prob", 0.05));
        }
        for (Map<?, ?> r : reglas) {
            if (entero(r.get("de"), 0) != grado || nivel < entero(r.get("nivel"), Integer.MAX_VALUE)) continue;
            return azar.getAsDouble() < decimal(r.get("prob"), 0) ? Math.min(4, entero(r.get("a"), grado)) : grado;
        }
        return grado;
    }

    /** Un mapa grado -> probabilidad de la config, o el de serie si la seccion no esta. */
    static Map<Integer, Double> probs(ConfigurationSection c, String ruta, Map<Integer, Double> def) {
        ConfigurationSection s = c.getConfigurationSection(ruta);
        if (s == null) return new java.util.TreeMap<>(def);
        Map<Integer, Double> out = new java.util.TreeMap<>();
        for (String k : s.getKeys(false)) {
            try {
                out.put(Integer.parseInt(k.trim()), s.getDouble(k));
            } catch (NumberFormatException ignorado) {
                // Una clave que no es un grado no se tira.
            }
        }
        return out;
    }

    static int xp(ConfigurationSection mobs, String clase) {
        int def = switch (clase) {
            case "destacado" -> 60;
            case "minijefe" -> 1500;
            default -> 20;
        };
        return mobs.getInt("xp." + clase, def);
    }

    private static int entero(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double decimal(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? def : Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ------------------------------------------------------------- decaimiento

    /** f = max(decae-minimo, 1 - esencias de mobs en la ultima hora / decae-por-hora). */
    double f(UUID jugador) {
        return factorDecae(esenciasUltimaHora(jugador), hc.cfg().getDouble("esencias.decae-por-hora", 30),
                hc.cfg().getDouble("esencias.decae-minimo", 0.2));
    }

    static double factorDecae(int esencias, double porHora, double minimo) {
        if (porHora <= 0) return 1.0;
        return Math.max(minimo, 1.0 - esencias / porHora);
    }

    int esenciasUltimaHora(UUID jugador) {
        Deque<long[]> d = esenciasHora.get(jugador);
        if (d == null) return 0;
        long ahora = System.currentTimeMillis();
        int n = 0;
        for (long[] x : d) if (ahora - x[0] <= HORA) n += (int) x[1];
        return n;
    }

    /** Las Esencias de mobs y minijefes que ya se entregaron (cuentan para el decaimiento). */
    void apuntarEsencias(UUID jugador, int n) {
        if (jugador == null || n <= 0) return;
        esenciasHora.computeIfAbsent(jugador, k -> new ArrayDeque<>()).add(new long[]{System.currentTimeMillis(), n});
    }

    // ----------------------------------------------------------------- autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        YamlConfiguration vacia = new YamlConfiguration();
        DoubleSupplier siempre = () -> 0.0;
        DoubleSupplier nunca = () -> 0.999_999;

        h.igual("SPAWNER cierra", Via.CERRADO, via(false, false, "SPAWNER", 100, 20, 0.5, "comun"));
        h.igual("TRIAL_SPAWNER cierra", Via.CERRADO, via(false, false, "TRIAL_SPAWNER", 100, 20, 0.5, "comun"));
        h.igual("fromMobSpawner cierra", Via.CERRADO, via(false, true, "NATURAL", 100, 20, 0.5, "comun"));
        h.igual("golem construido cierra", Via.CERRADO, via(false, false, "BUILD_IRONGOLEM", 100, 20, 0.5, "comun"));
        h.igual("huevo dispensado cierra", Via.CERRADO, via(false, false, "DISPENSE_EGG", 100, 20, 0.5, "comun"));
        h.igual("cuota 0,4 cierra", Via.CERRADO, via(false, false, "NATURAL", 8, 20, 0.5, "comun"));
        h.igual("cuota 0,5 paga", Via.NORMAL, via(false, false, "NATURAL", 10, 20, 0.5, "comun"));
        h.igual("cosechando: nada", Via.NADA, via(true, false, "NATURAL", 20, 20, 0.5, "comun"));
        h.igual("cosechando gana al spawner", Via.NADA, via(true, true, "SPAWNER", 20, 20, 0.5, "comun"));
        h.igual("minijefe a su reparto", Via.MINIJEFE, via(false, false, "CUSTOM", 400, 400, 0.5, "minijefe"));
        h.igual("minijefe de caida cierra", Via.CERRADO, via(false, false, "CUSTOM", 100, 400, 0.5, "minijefe"));

        // Cerrado: aunque el azar diga que si a todo, ni Esencias ni Reliquias, MC sin nivel.
        Plan cerrado = planificar(Via.CERRADO, "destacado", 80, 1, 1, siempre, vacia, vacia);
        h.igual("cerrado sin Esencias", 0, cerrado.esencias());
        h.ok("cerrado sin Reliquias", cerrado.grados().isEmpty());
        h.igual("cerrado MC de nivel 0 (tabla sin (1 + N/20))", 0, cerrado.nivelMc());
        h.igual("cerrado MC de clase comun (sin x3 del destacado)", "comun", cerrado.claseMc());
        h.igual("cerrado XP de vanilla", -1, cerrado.xp());
        Plan nada = planificar(Via.NADA, "comun", 10, 1, 1, siempre, vacia, vacia);
        h.ok("cosecha: vacia drops, XP 0 y sin nada", nada.vaciar() && nada.xp() == 0 && nada.esencias() == 0
                && nada.grados().isEmpty());

        Plan comun = planificar(Via.NORMAL, "comun", 30, 1, 1, siempre, vacia, vacia);
        h.igual("XP comun", 20, comun.xp());
        h.igual("comun con suerte: 1 Esencia", 1, comun.esencias());
        h.igual("comun con suerte: una I", List.of(1), comun.grados());
        h.igual("MC comun con su nivel", 30, comun.nivelMc());
        h.igual("XP destacado", 60, planificar(Via.NORMAL, "destacado", 1, 1, 1, nunca, vacia, vacia).xp());
        h.igual("XP minijefe", 1500, planificar(Via.MINIJEFE, "minijefe", 1, 1, 1, nunca, vacia, vacia).xp());
        h.igual("estructura cobra como comun", "comun", planificar(Via.NORMAL, "estructura", 5, 1, 1, nunca, vacia, vacia).claseMc());
        Plan sinSuerte = planificar(Via.NORMAL, "destacado", 30, 1, 1, nunca, vacia, vacia);
        h.ok("sin suerte no cae nada", sinSuerte.esencias() == 0 && sinSuerte.grados().isEmpty());
        Plan destacado = planificar(Via.NORMAL, "destacado", 30, 1, 1, siempre, vacia, vacia);
        h.igual("destacado con suerte: una I y una II", List.of(1, 2), destacado.grados());

        // El azar 0,1 entra en el 0,25 del destacado con f 1 y no con f 0,2 (0,05).
        DoubleSupplier diez = () -> 0.10;
        h.igual("f 1: el 25 % entra con 0,10", 1, planificar(Via.NORMAL, "destacado", 1, 1, 1, diez, vacia, vacia).esencias());
        h.igual("f 0,2: el 25 % se queda en 5 % y no entra", 0, planificar(Via.NORMAL, "destacado", 1, 0.2, 1, diez, vacia, vacia).esencias());
        h.cerca("f sin Esencias en la hora", 1.0, factorDecae(0, 30, 0.2), 1e-9);
        h.cerca("f con 15 Esencias", 0.5, factorDecae(15, 30, 0.2), 1e-9);
        h.cerca("f con 40 Esencias se queda en el minimo", 0.2, factorDecae(40, 30, 0.2), 1e-9);

        h.igual("N 49: la I no sube", 1, subirGrado(1, 49, vacia, siempre));
        h.igual("N 50: la I sube a II con suerte", 2, subirGrado(1, 50, vacia, siempre));
        h.igual("N 50: la II no sube", 2, subirGrado(2, 50, vacia, siempre));
        h.igual("N 75: la III sube a IV con suerte", 4, subirGrado(3, 75, vacia, siempre));
        h.igual("N 75: la III se queda sin suerte", 3, subirGrado(3, 75, vacia, nunca));
        h.igual("N 80: la I solo sube un escalon", 2, subirGrado(1, 80, vacia, siempre));

        Minijefes.probar(h);
        return h.lineas();
    }
}
