package net.ederus.edm.anomaly.boss;

import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.core.ActiveAnomaly;
import net.ederus.edm.anomaly.core.AnomalyRegistry;
import net.ederus.edm.anomaly.core.Disguises;
import net.ederus.edm.anomaly.core.Glow;
import net.ederus.edm.anomaly.core.Stop;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.ederus.edm.comun.Tags;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * ORION, el Arquero de las Estrellas: el tercer DIOS del catalogo.
 *
 * Un arquero con cuerpo de persona (maniqui con la skin fija FIRMADA, ver
 * Disguises#profileOfSigned) que no deja de disparar. No pega cuerpo a cuerpo: entre
 * habilidad y habilidad suelta su disparo basico cada 0,8 s y se recoloca a media
 * distancia, asi que plantarse delante de el es comerse todas las flechas.
 *
 *  - FASE I: el carcaj. Saetas con efecto, rafagas, la marca y los reflejos.
 *  - FASE II, «Alineación» (66 %): se le suman las de area, el Cinturón, la Lanza de
 *    Rigel y su firma, STAR DEPLOY.
 *  - FASE III, «Hora de Orión» (33 %): el cielo de la arena se queda en lethal:orion hasta
 *    el final y salen Supernova, la Grilla, Sagitario y la Órbita.
 *
 * El dano esta en la escala de ROTTEN (ficha de serie x20): disparo basico 8, flechas con
 * efecto 12-16, area 16-22, Rigel e Hipersónico 26 y Star Deploy 35, con el extra de fase
 * 1,0 / 1,15 / 1,3. Las flechas de verdad NO hacen su dano vanilla: las escucha esta misma
 * clase y lo cambian por un hit() con el multiplicador de la habilidad que las disparo.
 */
public final class Orion extends BossFight {

    public static final String ID = "orion";
    public static final TextColor ACCENT = TextColor.color(0x6FD3FF);
    private static final TextColor HIELO = TextColor.color(0xBFF6FF);

    public static final String STAR_DEPLOY = "star_deploy";

    /* La paleta: celeste de marca, azul de las ondas, blanco de los claros y el hielo de las nubes. */
    static final int CELESTE = 0x6FD3FF;
    static final int AZUL = 0x2E8BFF;
    static final int BLANCO = 0xFFFFFF;
    static final int NUBE = 0xBFF6FF;
    static final int VIOLETA = 0x7A5CFF;
    private static final Color GLOW = Color.fromRGB(CELESTE);

    /* La skin: la textura tal cual la firmo Mojang (cuenta ItzStormz_). Con firma el maniqui la
     * pinta; sin ella no (ver Disguises#profileOfSigned). */
    public static final String SKIN_VALUE = "ewogICJ0aW1lc3RhbXAiIDogMTc5MzQwOTE1NTg2MywKICAicHJvZmlsZUlkIiA6ICJlZTdiZmFlMjNhNGQ0ODdlYTM4MWE3ZjIzNGFkNmIwYiIsCiAgInByb2ZpbGVOYW1lIiA6ICJJdHpTdG9ybXpfIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzcyYTg5MDViOGM1MWRiM2Q3MjYyZWVmYWMzOWYxYTRiZDg5ZGI0NTEzY2Q5NzEwMDAzZGM2ZGRkNmNlMjhlYmIiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ==";
    public static final String SKIN_SIGNATURE = "I+C/dd3i7JaFgUbpmM3ESqU6/McHpkq1rDaPhcPv0JjVa0hjSTj1ze3LFER9U+M5QFYiTFuVh6riGjBO2L6UItJBGnaMWRmcOE7sKSiNEA5IwsXG7E2MHQc+6tDTY1wsqDfp+5mVjHY8yPhgjizbrFPNHyI0zeoEJhRZvitcvQrfBf80zo3MWXuYQvBuXTT2SYbG6ur3djB2EHkd/a1HsP6HwMaP/eSwovZwVCupV8EY7HgMSWgcDRTVcA3OAg2Tu0P5I9ellJvuI7Y4HNGWkkzjDk2add0O28nLtXfDLYaJKAIJPhzaD4Hco4jU/IX7kft0W7GK4i8m/d72NsUEogvXsUXgQ1WYMWhWkxyteKk0pedngi6B4dWzzHgn3wyizeEbLDnbz87b5l2tAEUc6qdxHuaMMCj05gzmcyXxhXHXUIvr9/lCsQ5UBOWrK3zbwTrMjvTH/2gsy6Fzjpt3kQl28a5QsVKYXsh5FrXjgzK7j6NCNjOYums3r4PVC+GF5a9GBSnpN2CwryyyQQNSkWqqTUzjpm5E3T1w0bBaHyIR205BiOpCt4exUrtvm/zTrmw/jbVtf5M3J16OR60LhlDOKpAxYrYUmBtg/67yhgTe3JDMDJkX0aodG1fMUiYT7/j7jt2/Asgm7Ds+kt5S+Q/KC/D0wYQ+HlP8y5JGbp0=";
    /** El hash de la textura (va dentro de SKIN_VALUE): la cabeza del menu. */
    public static final String SKIN_HASH = "72a8905b8c51db3d7262eefac39f1a4bd89db4513cd9710003dc6ddd6ce28ebb";
    private static final UUID SKIN_UUID = UUID.fromString("ee7bfae2-3a4d-487e-a381-a7f234ad6b0b");
    private static final String SKIN_NAME = "ItzStormz_";

    /** Un poco mas grande que un jugador, como los demas NPC. */
    private static final float ESCALA = 1.15f;

    /* Las bases, en la escala de ROTTEN (vida antes de multiplicadores). */
    private static final double D_BASICO = 8;
    private static final double D_EFECTO = 12;
    private static final double D_EFECTO_FUERTE = 14;
    private static final double D_EFECTO_MAX = 16;
    private static final double D_AREA = 18;
    private static final double D_AREA_MAX = 22;
    private static final double D_RIGEL = 26;
    private static final double D_RIGEL_BORDE = 10;
    private static final double D_HIPER = 26;
    private static final double D_STAR = 35;

    /** Velocidad base de sus flechas, en bloques por tick (un arco tensado del todo da 3). */
    private static final double V_BASE = 4.5;
    /** Radio util de la arena: la ficha dice 30 y se dejan dos de margen hasta la grada. */
    private static final double RADIO = 28;
    /** Tope de flechas de verdad en vuelo a la vez. */
    private static final int MAX_SAETAS = 220;
    /** Tope de llamadas de particulas por tick: cada una es un paquete por jugador cercano. */
    private static final int PRESUPUESTO = 1400;
    /** Tope de flechas de luz cayendo a la vez en Star Deploy. */
    private static final int MAX_LLUVIA = 180;

    private static final float R45 = (float) Math.toRadians(45);
    private static final float R90 = (float) Math.toRadians(90);

    // ------------------------------------------------------------------ estado

    private double damageBonus = 1.0;
    /** La pelea se cerro: las animaciones en curso se cortan solas. */
    private boolean cerrado;
    private boolean entrando;
    private final Escucha escucha = new Escucha();
    /** El perfil con la skin firmada; lo usan el cuerpo, el Reflejo, las sombras y Sagitario. */
    private io.papermc.paper.datacomponent.item.ResolvableProfile perfil;
    private double sueloY;

    /** Las flechas de verdad en vuelo, por uuid. */
    private final Map<UUID, Saeta> saetas = new HashMap<>();

    /* La pose: a quien apunta, un pitch forzado (Star Deploy apunta al cielo) y si tensa. */
    private Player apuntado;
    private Location miraFija;
    private Float pitchFijo;
    private boolean tensando;

    /* Star Deploy y el cielo. */
    private boolean cargando;
    private boolean cieloFijo;
    /** Si el cielo de Orion esta puesto ahora (para no repintar lo que ya esta). */
    private boolean cieloPuesto;
    private CieloPrestado prestado;
    private boolean avisoBioma;

    /* Flechas Marcadas: la presa, cuantas le han dado y cuando sale la siguiente. */
    private UUID marcado;
    private int impactosMarca;
    private long proximaMarca;
    private boolean marcadoBrillaba;

    /* Paralaje: el Reflejo, su vida (virtual, en vida de diseno) y hasta cuando dura. */
    private Mannequin reflejo;
    private double reflejoVida;
    private double reflejoMax;
    private long reflejoHasta;
    private double reflejoMult = 1.0;
    private long reflejoDisparo;

    /* Orbita: las seis flechas que le giran alrededor. */
    private final List<ItemDisplay> orbita = new ArrayList<>();
    private boolean orbitando;

    /* Telaranas de la Saeta Tejedora y cuando se quitan. */
    private final Map<Block, Long> telas = new LinkedHashMap<>();

    /* La red de las piezas de luz: cada display de Orion con el tick en que caduca. Lo que pase
     * de ahi se quita aunque su animacion no lo haya hecho (ver vigia()). */
    private final Map<Entity, Long> caducan = new HashMap<>();
    /** La etiqueta de todo lo que suelta Orion (displays y flechas): la usa el barrido. */
    static final String MARCA = "edm_orion";
    /** Vida maxima por defecto de un display de Orion: la animacion mas larga (Cinturon) dura ~9 s. */
    private static final int VIDA_DISPLAY = 300;
    private long avisoMotor;

    /* El cielo de Star Deploy: generacion (para que un cieloBase atrasado no apague el de un Star
     * Deploy nuevo), si el de Orion ya se ve del todo y quien espera a que se vea. */
    private int cieloGen;
    private boolean cieloListo;
    private boolean cieloPintando;
    private final List<Runnable> alPintarCielo = new ArrayList<>();

    /* El movimiento: deslizamientos con estela de sombras entre disparo y disparo. */
    private int dashGen;
    private boolean deslizando;
    private long proximoMovimiento = 90;
    private long reflejoMover;

    /* Presupuesto de particulas del tick en curso. */
    private int tickPresupuesto = Integer.MIN_VALUE;
    private int gastado;
    private int sombras;

    public Orion(AnomalyPlugin plugin, ActiveAnomaly event, Location arena) {
        super(plugin, event, arena);
        abilities.addAll(plugin.registry().get(ID).abilities());
    }

    @Override
    public String bossName() {
        return "ORION";
    }

    @Override
    public TextColor accent() {
        return ACCENT;
    }

    @Override
    public int phaseCount() {
        return 3;
    }

    /** Nunca vuelve a una fase anterior: el Reflejo lo cura, pero no le devuelve la barra. */
    @Override
    protected boolean canChangePhase(int from, int to) {
        return to > from;
    }

    /** Las de la fase II siguen en la III (fase 2 = "de la II en adelante"). */
    @Override
    protected boolean puedeLanzar(Ability a, int fase) {
        switch (a.id()) {
            case "salto_atras" -> {
                if (Fx.nearest(loc(), 6) == null) return false;
            }
            case "flechas_marcadas" -> {
                if (marcado != null) return false;
            }
            case "paralaje" -> {
                if (reflejoVivo()) return false;
            }
            default -> {
            }
        }
        if (a.phase() == 2 && fase >= 2) return true;
        return a.availableIn(fase);
    }

    /** Mientras carga Star Deploy solo le entra el 20 % de cada golpe. */
    @Override
    public double incomingDamageMultiplier() {
        return cargando ? 0.2 : 1.0;
    }

    // ================================================================ APARICION

    @Override
    public void spawn() {
        Location spot = Fx.ground(arena.clone(), 8).add(0, 0.05, 0);
        sueloY = spot.getY();
        World w = world();

        boss = w.spawn(spot, Zombie.class, z -> {
            z.setAdult();
            z.setPersistent(true);
            z.setRemoveWhenFarAway(false);
            z.setCanPickupItems(false);
            z.setShouldBurnInDay(false);
            z.setSilent(true);
            z.customName(Component.text("ORION", ACCENT));
            z.setCustomNameVisible(false);
        });
        /* Sin ninguna meta de IA: ni persigue ni pega. Solo anda a donde se le manda con el
         * pathfinder (recolocarse, la correa) y todo lo demas son flechas. */
        try {
            Bukkit.getMobGoals().removeAllGoals((Mob) boss);
        } catch (Throwable ignored) {
        }
        EntityEquipment eq = boss.getEquipment();
        if (eq != null) eq.clear();

        perfil = Disguises.profileOfSigned(plugin, SKIN_UUID, SKIN_NAME, SKIN_VALUE, SKIN_SIGNATURE);
        wearShell(perfil, nombre());
        if (shell instanceof Mannequin m) {
            Compat.setAttribute(m, "scale", ESCALA);
            dePieSiempre(m);
            try {
                m.setImmovable(true);
            } catch (Throwable ignored) {
            }
            vestir(m);
            // Escondido hasta que cae la estrella de la entrada.
            m.setInvisible(true);
            m.setCustomNameVisible(false);
        }

        Compat.setAttribute(boss, "max_health", 20);
        Compat.setAttribute(boss, "attack_damage", 1);
        Compat.setAttribute(boss, "armor", 12);
        Compat.setAttribute(boss, "armor_toughness", 6);
        Compat.setAttribute(boss, "knockback_resistance", 1.0);
        Compat.setAttribute(boss, "follow_range", 80);
        Compat.setAttribute(boss, "movement_speed", 0.32);
        Compat.setAttribute(boss, "scale", ESCALA);
        applyHealth(plugin.registry().health(plugin.registry().get(ID)));
        boss.setMaximumNoDamageTicks(6);
        Tags.markBoss(boss, ID);
        glowBody(NamedTextColor.AQUA);

        plugin.getServer().getPluginManager().registerEvents(escucha, net.ederus.edm.Module.dueno(plugin));
        // El motor de las flechas: va aparte de ambient() para que sigan volando (y se
        // limpien) aunque el jefe este muerto o volviendo a la arena.
        // Cada parte va por separado y sin dejar escapar nada: Anim corta la animacion a los cinco
        // errores (sumados en toda la pelea), y si se cortaba el motor las flechas dejaban de caducar.
        animate(Integer.MAX_VALUE - 40, t -> {
            if (cerrado) throw Stop.now();
            seguro("flechas", this::tickSaetas);
            seguro("telarañas", this::tickTelas);
            if (t % 5 == 0) seguro("vigía", this::vigia);
            if (t % 40 == 20) seguro("barrido", this::barrido);
        }, null);

        entrada(spot);
    }

    /**
     * El nombre que se ve sobre el: el mismo en el cuerpo de verdad y en todas sus copias (el
     * Reflejo, los arqueros de Sagitario), para que nadie sepa a simple vista cual es el bueno.
     */
    static Component nombre() {
        return Component.text("✦ ", ACCENT)
                .append(Component.text("ORION", ACCENT, TextDecoration.BOLD))
                .append(Component.text(" ✦", ACCENT));
    }

    /** Siempre de pie: un maniqui se agacha solo si cree que no cabe (techo, salto, teletransporte). */
    private static void dePieSiempre(LivingEntity m) {
        try {
            m.setPose(Pose.STANDING, true);
        } catch (Throwable ignored) {
        }
    }

    /** Corre una parte del motor sin que un fallo la tumbe; avisa como mucho cada diez segundos. */
    private void seguro(String que, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            long ahora = ticks();
            if (ahora - avisoMotor > 200) {
                avisoMotor = ahora;
                plugin.getLogger().warning("ORION: fallo en el motor (" + que + "): " + t);
            }
        }
    }

    /** El arco encantado en la mano y nada mas: el cuerpo ya lo pone la skin. */
    private void vestir(LivingEntity cuerpo) {
        EntityEquipment eq = cuerpo.getEquipment();
        if (eq == null) return;
        eq.setItemInMainHand(arco());
        // El maniquí no es un Mob: Paper lanza si se le pone probabilidad de soltarlo
        // (y tampoco suelta nada al morir, así que no hace falta).
        if (cuerpo instanceof org.bukkit.entity.Mob) eq.setItemInMainHandDropChance(0);
    }

    private static ItemStack arco() {
        ItemStack bow = new ItemStack(Material.BOW);
        try {
            ItemMeta meta = bow.getItemMeta();
            if (meta != null) {
                meta.setEnchantmentGlintOverride(true);
                bow.setItemMeta(meta);
            }
        } catch (Throwable ignored) {
        }
        return bow;
    }

    /**
     * La entrada: una flecha de luz cae del cielo, revienta en el centro y de la luz sale el.
     * Intocable mientras dura; el titulo sale al aterrizar.
     */
    private void entrada(Location spot) {
        entrando = true;
        boss.setInvulnerable(true);
        busyFor(70);
        World w = world();
        soundAt(spot, "block.beacon.power_select", 1.6f, 1.6f);
        soundAt(spot, "entity.illusioner.cast_spell", 1.2f, 1.4f);
        Location cielo = spot.clone().add(0, 42, 0);
        ItemDisplay[] saeta = saetaDeLuz(cielo, 3.0f, new Vector(0, -1, 0));
        later(2, () -> {
            for (ItemDisplay d : saeta) mover(d, new Vector3f(0, -40.5f, 0), 14);
        });
        animate(70, t -> {
            if (t <= 16) {
                double y = 42 - 40.5 * Math.min(1, t / 14.0);
                Location p = spot.clone().add(0, y, 0);
                fx(Compat.END_ROD, p, 3, 0.1, 0.4, 0.1, 0.01);
                polvo(p, CELESTE, 2.2f);
                if (t % 4 == 0) Fx.ring(spot.clone().add(0, 0.15, 0), 3.0 - t * 0.12, 24, q -> polvo(q, BLANCO, 1.3f));
            }
            if (t == 16) {
                for (ItemDisplay d : saeta) quitar(d);
                fx(Compat.FLASH, spot.clone().add(0, 1, 0), 1, 0, 0, 0, 0, GLOW);
                fx(Compat.END_ROD, spot.clone().add(0, 1, 0), 60, 0.4, 1.0, 0.4, 0.18);
                fx(Compat.FIREWORK, spot.clone().add(0, 1, 0), 40, 0.6, 0.6, 0.6, 0.12);
                soundAt(spot, "entity.firework_rocket.large_blast", 1.6f, 0.8f);
                soundAt(spot, "block.amethyst_block.resonate", 1.6f, 1.5f);
                soundAt(spot, "item.trident.thunder", 0.7f, 1.6f);
                if (shell instanceof Mannequin m && m.isValid()) {
                    m.setInvisible(false);
                    m.setCustomNameVisible(true);
                }
                for (int i = 0; i < 3; i++) {
                    final double r = 2 + i * 3;
                    later(i * 4, () -> Fx.ring(spot.clone().add(0, 0.2, 0), r, (int) (r * 8),
                            q -> polvo(Fx.ground(q, 3).add(0, 0.2, 0), i2c(r), 1.6f)));
                }
            }
            if (t > 16 && t < 60 && t % 3 == 0) {
                fx(Compat.END_ROD, body().getLocation().add(0, 1.2, 0), 2, 0.4, 0.8, 0.4, 0.02);
            }
        }, () -> {
            entrando = false;
            if (!alive()) return;
            boss.setInvulnerable(false);
            for (Player p : Fx.viewersNear(spot, 110)) {
                p.showTitle(Title.title(
                        Component.text("✦ ANOMALÍA DIOS ✦", ACCENT, TextDecoration.BOLD),
                        Component.text("Orión, el Arquero de las Estrellas", HIELO),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2400), Duration.ofMillis(700))));
            }
            warn(Component.text("Una estrella baja a cobrar sus flechas.", HIELO));
            soundAt(spot, "item.crossbow.loading_end", 1.4f, 1.2f);
        });
    }

    private static int i2c(double r) {
        return ((int) r) % 2 == 0 ? CELESTE : AZUL;
    }

    // =================================================================== AMBIENTE

    @Override
    protected void ambient() {
        if (!alive()) return;
        apuntar();
        tickMarca();
        tickReflejo();
        if (orbitando) pararFlechas();

        if (ticks() % 4 == 0) {
            Location pies = body().getLocation();
            fx(Compat.END_ROD, pies.clone().add(0, 1.2, 0), 1, 0.35, 0.6, 0.35, 0.005);
            polvo(pies.clone().add(rnd(0.5), 0.1, rnd(0.5)), CELESTE, 1.0f);
        }
        if (entrando || volviendo() || busy()) return;
        pasiva();
    }

    private double rnd(double r) {
        return (random.nextDouble() * 2 - 1) * r;
    }

    /**
     * El pulso del arquero: sin esperar a ninguna habilidad, tensa y suelta su disparo basico
     * cada 0,8 s y no se queda quieto: cada uno o dos segundos se desliza con su estela de sombras
     * (de lado, de un salto atras o cambiando de lado alrededor de su presa) y sigue disparando
     * mientras se mueve. Si alguien se le pega, salta atras.
     */
    private void pasiva() {
        Player t = objetivo(48);
        if (t == null) {
            if (tensando) tensar(false);
            return;
        }
        apuntado = t;
        long k = ticks() % 16;
        if (k == 7) tensar(true);
        if (k == 0) {
            disparoBasico(t);
            tensar(false);
        }
        if (deslizando) return;
        boolean encima = boss.getLocation().distanceSquared(t.getLocation()) < 20;
        if (ticks() >= proximoMovimiento || (encima && ticks() % 5 == 3)) {
            proximoMovimiento = ticks() + 26 + random.nextInt(22);
            if (!moverse(boss, t, encima)) recolocar(t);
        }
    }

    /**
     * Elige a donde deslizarse respecto a su presa y va: de lado (lo normal), cambiando de lado
     * alrededor de ella o de un salto atras si la tiene encima. Siempre a sitio de pie, dentro de
     * la arena y sin muros en medio. Devuelve si encontro hueco.
     */
    private boolean moverse(LivingEntity quien, Player t, boolean encima) {
        Location yo = quien.getLocation();
        Vector desdeEl = yo.toVector().subtract(t.getLocation().toVector()).setY(0);
        double dist = desdeEl.length();
        if (dist < 0.1) desdeEl = new Vector(1, 0, 0).rotateAroundY(random.nextDouble() * Math.PI * 2);
        desdeEl.normalize();
        for (int intento = 0; intento < 10; intento++) {
            double giro, radio, arco;
            int dura;
            int r = random.nextInt(10);
            if (encima || (dist < 7 && intento < 4)) {
                // Salto atras: se aleja de golpe, un poco en diagonal.
                giro = Math.toRadians(rnd(25));
                radio = Math.max(dist, 1) + 6 + random.nextDouble() * 2;
                dura = 7;
                arco = 1.3;
            } else if (r < 6) {
                // De lado, corrigiendo la distancia hacia la buena (diez a dieciseis).
                giro = Math.toRadians((random.nextBoolean() ? 1 : -1) * (24 + random.nextInt(26)));
                radio = Fx.clamp(dist + rnd(2.5), 10, 16);
                dura = 7;
                arco = 0.5;
            } else {
                // Cambio de lado: le da media vuelta a su presa por el aire.
                giro = Math.toRadians((random.nextBoolean() ? 1 : -1) * (85 + random.nextInt(60)));
                radio = 10 + random.nextDouble() * 5;
                dura = 11;
                arco = 1.8;
            }
            Vector d = desdeEl.clone().rotateAroundY(giro).multiply(radio);
            Location spot = Fx.ground(t.getLocation().clone().add(d).add(0, 2, 0), 8);
            if (!sitioDeDeslizar(yo, spot)) continue;
            spot.setYaw(yawDe(t.getLocation().toVector().subtract(spot.toVector())));
            deslizar(quien, spot, dura, arco, 2, null);
            return true;
        }
        return false;
    }

    /** Destino bueno para un deslizamiento: de pie, dentro de la arena, sin escalon y sin muro en medio. */
    private boolean sitioDeDeslizar(Location desde, Location hasta) {
        if (hasta == null || desde.getWorld() != hasta.getWorld()) return false;
        if (!dentroDeArena(hasta) || !dePie(hasta) || Math.abs(hasta.getY() - desde.getY()) > 3) return false;
        if (desde.distanceSquared(hasta) < 9) return false;
        Vector paso = hasta.toVector().subtract(desde.toVector());
        for (int i = 1; i <= 5; i++) {
            Location q = desde.clone().add(paso.clone().multiply(i / 6.0));
            if (!dentroDeArena(q)) return false;
            if (q.clone().add(0, 1.2, 0).getBlock().getType().isSolid()) return false;
        }
        return true;
    }

    /**
     * El deslizamiento de Paralaje, para todo el que lo use (el jefe y su Reflejo): de un sitio a
     * otro en 'dura' ticks, con arrancada y frenada suaves y un arco de 'arco' bloques, movido un
     * tick cada vez (el cliente lo interpola, sin saltos). Deja una sombra cada 'cadaSombra' ticks
     * y una estela continua de luz. Si es el jefe, el maniqui va pegado en el mismo tick.
     */
    private void deslizar(LivingEntity quien, Location hasta, int dura, double arco, int cadaSombra, Runnable alLlegar) {
        if (quien == null || !quien.isValid() || hasta == null || dura < 2) return;
        final boolean jefe = quien == boss;
        final int gen = jefe ? ++dashGen : 0;
        final Location desde = quien.getLocation().clone();
        final Location fin = hasta.clone();
        final Vector recorrido = fin.toVector().subtract(desde.toVector());
        if (jefe) {
            deslizando = true;
            try {
                if (boss instanceof Mob mob) mob.getPathfinder().stopPathfinding();
            } catch (Throwable ignored) {
            }
        }
        soundAt(desde, "entity.illusioner.mirror_move", 0.8f, 1.5f + random.nextFloat() * 0.3f);
        soundAt(desde, "entity.breeze.jump", 0.6f, 1.6f);
        fx(Compat.SMALL_GUST, desde.clone().add(0, 0.2, 0), 3, 0.3, 0.05, 0.3, 0.01);
        final Location[] antes = {desde.clone().add(0, 1.0, 0)};
        final boolean[] cortado = {false};
        animate(dura - 1, t -> {
            if (cerrado || !quien.isValid() || (jefe && (gen != dashGen || !alive()))) {
                cortado[0] = true;
                throw Stop.now();
            }
            double u = Math.min(1, (t + 1) / (double) dura);
            double e = u * u * (3 - 2 * u);
            LivingEntity cuerpo = jefe ? body() : quien;
            // La sombra se queda donde estaba ANTES de este paso, mirando a donde miraba.
            if (cadaSombra > 0 && t % cadaSombra == 0 && t < dura - 1) {
                sombra(cuerpo.getLocation(), cuerpo.getLocation().getYaw(), 9 + t / 2);
            }
            Location en = desde.clone().add(recorrido.clone().multiply(e));
            en.add(0, arco * Math.sin(Math.PI * u), 0);
            en.setYaw(cuerpo.getLocation().getYaw());
            en.setPitch(cuerpo.getLocation().getPitch());
            quien.teleport(en);
            quien.setVelocity(new Vector());
            if (jefe && shell != null && shell.isValid() && shell.getVehicle() == null) shell.teleport(en);
            Location ahora = en.clone().add(0, 1.0, 0);
            Fx.beam(antes[0], ahora, 0.3, q -> polvo(q, random.nextInt(3) == 0 ? BLANCO : CELESTE, 1.4f));
            fx(Compat.END_ROD, ahora, 1, 0.15, 0.3, 0.15, 0.01);
            antes[0] = ahora;
        }, () -> {
            if (jefe && gen == dashGen) deslizando = false;
            if (cortado[0] || !quien.isValid()) return;
            Location l = fin.clone();
            LivingEntity cuerpo = jefe ? body() : quien;
            l.setYaw(cuerpo.getLocation().getYaw());
            l.setPitch(cuerpo.getLocation().getPitch());
            quien.teleport(l);
            quien.setVelocity(new Vector());
            fx(Compat.SMALL_GUST, l.clone().add(0, 0.1, 0), 4, 0.35, 0.05, 0.35, 0.01);
            polvo(l.clone().add(0, 0.15, 0), CELESTE, 1.8f);
            if (alLlegar != null) alLlegar.run();
        });
    }

    /** Corta el deslizamiento en curso del jefe (una habilidad lo mueve a su manera). */
    private void cortarDeslizamiento() {
        dashGen++;
        deslizando = false;
    }

    /** Cada habilidad empieza con el jefe en su sitio: si iba deslizandose, se corta ahi. */
    @Override
    protected void startAbilityDamage(Ability a) {
        cortarDeslizamiento();
        super.startAbilityDamage(a);
    }

    private Player objetivo(double radio) {
        Player t = currentTarget();
        if (t != null && enLaPelea(t) && t.getLocation().distanceSquared(loc()) <= radio * radio) return t;
        Player n = Fx.nearest(loc(), radio);
        return n != null && enLaPelea(n) ? n : null;
    }

    private void disparoBasico(Player t) {
        Location boca = boca(pecho(t));
        disparar(boca, pecho(t).toVector().subtract(boca.toVector()), V_BASE, Tipo.BASICA, D_BASICO, 1.0);
        soundAt(boca, "entity.arrow.shoot", 1.0f, 1.5f);
        soundAt(boca, "block.amethyst_block.hit", 0.6f, 1.9f);
        fx(Compat.END_ROD, boca, 3, 0.05, 0.05, 0.05, 0.03);
    }

    /** Se busca un sitio a unos trece bloques de su presa, de lado, dentro de la arena. */
    private void recolocar(Player t) {
        if (!(boss instanceof Mob mob)) return;
        Vector lejos = boss.getLocation().toVector().subtract(t.getLocation().toVector()).setY(0);
        if (lejos.lengthSquared() < 0.01) lejos = new Vector(1, 0, 0);
        lejos.normalize();
        for (int i = 0; i < 10; i++) {
            double giro = Math.toRadians((random.nextBoolean() ? 1 : -1) * (15 + random.nextInt(60)));
            Vector d = lejos.clone().rotateAroundY(giro).multiply(11 + random.nextDouble() * 5);
            Location spot = Fx.ground(t.getLocation().clone().add(d).add(0, 2, 0), 8);
            if (!dentroDeArena(spot) || !dePie(spot) || Math.abs(spot.getY() - boss.getLocation().getY()) > 3) continue;
            try {
                mob.getPathfinder().moveTo(spot, 1.35);
            } catch (Throwable ignored) {
            }
            return;
        }
        // Sin hueco bueno: hacia el centro, que siempre hay sitio.
        try {
            mob.getPathfinder().moveTo(Fx.ground(arena.clone(), 8), 1.2);
        } catch (Throwable ignored) {
        }
    }

    // ============================================================= POSE Y ARCO

    private Mannequin cuerpo() {
        return shell instanceof Mannequin m && m.isValid() ? m : null;
    }

    /** Tensa el arco de verdad: el maniqui "usa" el arco y se pone en pose de apuntar. */
    private void tensar(boolean si) {
        tensarCuerpo(cuerpo(), si);
        if (si && !tensando) soundAt(loc(), "item.crossbow.loading_start", 0.45f, 1.7f);
        tensando = si;
    }

    private static void tensarCuerpo(LivingEntity m, boolean si) {
        if (m == null || !m.isValid()) return;
        try {
            if (si) {
                if (!m.hasActiveItem()) m.startUsingItem(EquipmentSlot.HAND);
            } else if (m.hasActiveItem()) {
                m.clearActiveItem();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Orienta el cuerpo hacia donde apunta. Va en ambient(), que corre DESPUES de que el padre
     * pegue el maniqui al mob invisible, asi que esta rotacion es la que le llega al cliente.
     */
    private void apuntar() {
        Mannequin m = cuerpo();
        if (m == null) return;
        Location eye = m.getEyeLocation();
        Location hacia = null;
        if (apuntado != null && apuntado.isValid() && apuntado.getWorld() == eye.getWorld()) hacia = pecho(apuntado);
        else if (miraFija != null && miraFija.getWorld() == eye.getWorld()) hacia = miraFija;
        float yaw = m.getLocation().getYaw();
        float pitch = 0f;
        if (hacia != null) {
            Vector d = hacia.toVector().subtract(eye.toVector());
            if (d.lengthSquared() > 0.04) {
                yaw = yawDe(d);
                pitch = (float) -Math.toDegrees(Math.atan2(d.getY(), Math.hypot(d.getX(), d.getZ())));
            }
        }
        if (pitchFijo != null) pitch = pitchFijo;
        try {
            m.setRotation(yaw, pitch);
            m.setBodyYaw(yaw);
        } catch (Throwable ignored) {
        }
    }

    private static float yawDe(Vector d) {
        return (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
    }

    private static Location pecho(Player p) {
        return p.getLocation().add(0, 1.15, 0);
    }

    /** De donde salen sus flechas: delante del arco, fuera del cuerpo para no chocar con el. */
    private Location boca(Location hacia) {
        LivingEntity b = body();
        Location eye = b.getEyeLocation().subtract(0, 0.25, 0);
        Vector d = hacia == null ? b.getLocation().getDirection()
                : hacia.toVector().subtract(eye.toVector());
        if (d.lengthSquared() < 1.0E-4) d = new Vector(0, 0, 1);
        return eye.add(d.normalize().multiply(1.2));
    }

    // ============================================================ PARTICULAS

    /** Cada llamada es un paquete por jugador cercano: con tope por tick para no ahogar a nadie. */
    private boolean gastar() {
        int ahora = Bukkit.getCurrentTick();
        if (ahora != tickPresupuesto) {
            tickPresupuesto = ahora;
            gastado = 0;
        }
        if (gastado >= PRESUPUESTO) return false;
        gastado++;
        return true;
    }

    private void fx(Particle p, Location l, int n, double ox, double oy, double oz, double extra) {
        if (p != null && l != null && gastar()) Compat.spawn(world(), p, l, n, ox, oy, oz, extra);
    }

    private <T> void fx(Particle p, Location l, int n, double ox, double oy, double oz, double extra, T data) {
        if (p != null && l != null && gastar()) Compat.spawn(world(), p, l, n, ox, oy, oz, extra, data);
    }

    private void polvo(Location l, int rgb, float size) {
        if (l != null && gastar()) Compat.spawn(world(), Compat.DUST, l, 1, 0, 0, 0, 0, Compat.dust(rgb, size));
    }

    // ============================================================ LAS FLECHAS

    /** Cada clase de flecha: el color de su estela, su chispa y si va brillando. */
    enum Tipo {
        BASICA(CELESTE, 1.0f, null, false, false),
        PLOMO(0x8E9AA8, 1.1f, Compat.ASH, false, false),
        QUEBRANTADA(0xB18CFF, 1.1f, Compat.WITCH, false, false),
        VENENOSA(0x58D25B, 1.1f, Compat.ITEM_SLIME, false, false),
        DANINA(0xC21F3A, 1.2f, Compat.DAMAGE_INDICATOR, true, false),
        INGRAVIDA(0xF2F0FF, 1.1f, Compat.END_ROD, false, false),
        TEJEDORA(0xE6E6E6, 1.1f, Compat.ITEM_COBWEB, false, false),
        SUPURANTE(0x8BE04E, 1.1f, Compat.ITEM_SLIME, false, false),
        INFESTADA(0x9A9A9A, 1.1f, Compat.INFESTED, false, false),
        VIENTO(0xCFF4FF, 1.1f, Compat.SMALL_GUST, false, false),
        IGNEA(0xFF7A1F, 1.2f, Compat.FLAME, false, false),
        MARCHITA(0x2E2A2A, 1.2f, Compat.SMOKE, false, false),
        ESPECTRAL(0xFFE45C, 1.1f, Compat.WAX_ON, false, true),
        MARCADA(0x9FE6FF, 1.4f, Compat.ELECTRIC_SPARK, true, true),
        HIPER(BLANCO, 1.6f, Compat.END_ROD, true, true),
        REFLEJO(0xA9E8FF, 0.9f, null, false, false),
        REBOTE(0x7FB8FF, 1.3f, Compat.ELECTRIC_SPARK, true, true),
        POLARIS(BLANCO, 1.4f, Compat.END_ROD, true, true),
        ESTELAR(AZUL, 1.1f, Compat.END_ROD, false, false);

        final int rgb;
        final float tam;
        final Particle chispa;
        final boolean critica;
        final boolean brilla;

        Tipo(int rgb, float tam, Particle chispa, boolean critica, boolean brilla) {
            this.rgb = rgb;
            this.tam = tam;
            this.chispa = chispa;
            this.critica = critica;
            this.brilla = brilla;
        }
    }

    /** Una flecha de verdad en vuelo y lo que tiene que pasar cuando de. */
    private static final class Saeta {
        final Arrow flecha;
        final Tipo tipo;
        final double base;
        final double mult;
        final Set<UUID> tocados = new HashSet<>();
        Location antes;
        int vida;
        int vidaMax = 70;
        int rebotes;
        Player guiada;
        /** Pasa a traves de los jugadores (el dano, si lo hay, lo pone otro). */
        boolean atraviesa;
        /** Solo se ve: el golpe lo reparte la habilidad (el Hipersónico). */
        boolean soloVisual;
        /** Rafagas: borra la inmunidad tras golpe para que no se pierdan. */
        boolean rapida;
        boolean resuelta;
        /** Ticks seguidos sin moverse: una flecha sin gravedad que se para en el aire no cae nunca. */
        int quieta;
        Consumer<Player> alGolpear;

        Saeta(Arrow flecha, Tipo tipo, double base, double mult, Location antes) {
            this.flecha = flecha;
            this.tipo = tipo;
            this.base = base;
            this.mult = mult;
            this.antes = antes;
        }
    }

    /**
     * Suelta una flecha de verdad. Sin gravedad (son de luz: van rectas a donde apunta), no
     * recogibles, no persistentes y marcadas como del jefe. Su dano vanilla es cero: el golpe lo
     * pone golpear() con hit() y el multiplicador de la habilidad (mult, leido al lanzarla).
     */
    private Saeta disparar(Location desde, Vector dir, double vel, Tipo tipo, double base, double mult) {
        if (cerrado || saetas.size() >= MAX_SAETAS || dir == null || dir.lengthSquared() < 1.0E-6) return null;
        Vector d = dir.clone().normalize();
        try {
            Arrow a = world().spawnArrow(desde, d, (float) vel, 0f);
            if (boss != null && boss.isValid()) a.setShooter(boss);
            a.setPersistent(false);
            a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            a.setDamage(0);
            a.setGravity(false);
            a.setSilent(true);
            a.setCritical(tipo.critica);
            try {
                a.setColor(Color.fromRGB(tipo.rgb));
            } catch (Throwable ignored) {
            }
            if (tipo == Tipo.IGNEA) {
                try {
                    a.setFireTicks(200);
                } catch (Throwable ignored) {
                }
            }
            if (tipo.brilla) a.setGlowing(true);
            Tags.markMinion(a, ID);
            a.addScoreboardTag(MARCA);
            Saeta s = new Saeta(a, tipo, base, mult, desde.clone());
            saetas.put(a.getUniqueId(), s);
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Dispara desde el arco hacia el pecho de alguien. */
    private Saeta dispararA(Player p, double vel, Tipo tipo, double base, double mult) {
        Location boca = boca(pecho(p));
        return disparar(boca, pecho(p).toVector().subtract(boca.toVector()), vel, tipo, base, mult);
    }

    private void quitar(Saeta s) {
        if (s == null) return;
        saetas.remove(s.flecha.getUniqueId());
        borrar(s.flecha);
    }

    /** Estelas, guiado y caducidad de todas las flechas en vuelo. */
    private void tickSaetas() {
        if (saetas.isEmpty()) return;
        List<Saeta> caidas = null;
        double paso = saetas.size() > 40 ? 0.9 : 0.45;
        Iterator<Saeta> it = saetas.values().iterator();
        while (it.hasNext()) {
            Saeta s = it.next();
            Arrow a = s.flecha;
            if (!a.isValid()) {
                // Sin valer (su trozo dejo de estar cargado) sigue existiendo: se borra igual, o
                // se quedaria clavada en el aire para quien vuelva a cargar ese trozo.
                it.remove();
                borrar(a);
                continue;
            }
            s.vida++;
            Location ahora = a.getLocation();
            if (s.antes != null && s.antes.getWorld() == ahora.getWorld()
                    && s.antes.distanceSquared(ahora) < 1.0E-4) s.quieta++;
            else s.quieta = 0;
            final Tipo tipo = s.tipo;
            if (s.antes != null && s.antes.getWorld() == ahora.getWorld()) {
                Fx.beam(s.antes, ahora, paso, q -> polvo(q, tipo.rgb, tipo.tam));
            }
            if (tipo.chispa != null && s.vida % 2 == 0) fx(tipo.chispa, ahora, 1, 0.04, 0.04, 0.04, 0.0);
            s.antes = ahora;
            if (s.guiada != null) guiar(s);
            if (a.isInBlock() || a.isOnGround() || s.vida > s.vidaMax || s.quieta >= 6) {
                it.remove();
                if (!s.resuelta && (a.isInBlock() || a.isOnGround())) {
                    s.resuelta = true;
                    if (caidas == null) caidas = new ArrayList<>();
                    caidas.add(s);
                }
                borrar(a);
            }
        }
        if (caidas != null) for (Saeta s : caidas) alCaer(s, s.antes);
    }

    private void guiar(Saeta s) {
        Player g = s.guiada;
        if (!enLaPelea(g)) {
            s.guiada = null;
            return;
        }
        Vector v = s.flecha.getVelocity();
        double sp = Math.max(1.4, v.length());
        Vector quiere = pecho(g).toVector().subtract(s.flecha.getLocation().toVector());
        if (quiere.lengthSquared() < 0.01) return;
        quiere.normalize().multiply(sp);
        Vector nueva = v.multiply(0.7).add(quiere.multiply(0.3));
        if (nueva.lengthSquared() < 1.0E-4) return;
        s.flecha.setVelocity(nueva.normalize().multiply(sp));
    }

    /**
     * Si el jefe sigue peleando. Tras morir pasan ~110 ticks hasta la limpieza (cerrado) y en
     * ese rato las flechas en vuelo y las areas en curso seguian pegando, con daño puro incluido.
     */
    private boolean enCombate() {
        return !cerrado && boss != null && !boss.isDead();
    }

    /** Ningun golpe de ORION entra con el jefe muerto o la pelea cerrada. */
    @Override
    public void hit(Player p, double amount, double abilityMult) {
        if (!enCombate()) return;
        super.hit(p, amount, abilityMult);
    }

    /** Una flecha alcanza a un jugador. */
    private void golpear(Saeta s, Player p) {
        if (!enCombate() || !enLaPelea(p)) return;
        if (s.tipo == Tipo.MARCADA) {
            if (!p.getUniqueId().equals(marcado)) return;
            if (!s.tocados.add(p.getUniqueId())) return;
            golpePuro(p);
            return;
        }
        if (s.soloVisual) return;
        if (!s.tocados.add(p.getUniqueId())) return;
        /* Todas, no solo las de rafaga: Orion dispara tan seguido que cualquier flecha que
         * llegara en el medio segundo de invulnerabilidad del golpe anterior no hacia nada
         * (Dosa: «varias flechas no hacen daño»). Cada flecha pega una vez por jugador. */
        p.setNoDamageTicks(0);
        if (s.base > 0) hit(p, s.base * damageBonus, s.mult);
        Location at = p.getLocation().add(0, 1.0, 0);
        fx(Compat.CRIT, at, 8, 0.25, 0.4, 0.25, 0.15);
        fx(Compat.DUST, at, 6, 0.25, 0.4, 0.25, 0, Compat.dust(s.tipo.rgb, 1.4f));
        soundAt(at, "entity.arrow.hit_player", 0.6f, 1.3f);
        soundAt(at, "block.amethyst_block.hit", 0.8f, 1.6f);
        efecto(s, p);
        if (s.alGolpear != null) {
            try {
                s.alGolpear.accept(p);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Lo que deja cada clase de flecha en quien la recibe. */
    private void efecto(Saeta s, Player p) {
        switch (s.tipo) {
            case PLOMO -> Compat.apply(p, "slowness", 80, 2);
            case QUEBRANTADA -> {
                Compat.apply(p, "weakness", 120, 1);
                fx(Compat.WITCH, p.getLocation().add(0, 1.2, 0), 10, 0.3, 0.5, 0.3, 0.05);
            }
            case VENENOSA -> nubeVenenosa(p.getLocation());
            case DANINA -> {
                Compat.apply(p, "instant_damage", 1, 1);
                if (s.rebotes > 0) {
                    int saltos = 0;
                    for (Player otro : cercanosA(p, 18, s.tocados)) {
                        if (saltos++ >= s.rebotes) break;
                        rebotar(s, p, otro, 0);
                    }
                }
            }
            case INGRAVIDA -> {
                grantAirTime(p, 140);
                Compat.apply(p, "levitation", 40, 1);
                final double m = s.mult;
                later(30, () -> rematarEnElAire(p, m));
            }
            case TEJEDORA -> {
                Compat.apply(p, "weaving", 200, 0);
                telaranas(p.getLocation());
            }
            case SUPURANTE -> Compat.apply(p, "oozing", 200, 0);
            case INFESTADA -> Compat.apply(p, "infested", 200, 0);
            case VIENTO -> estallidoDeViento(p.getLocation(), s.mult);
            case IGNEA -> {
                p.setFireTicks(Math.max(p.getFireTicks(), 100));
                llamas(p.getLocation(), s.mult);
            }
            case MARCHITA -> Compat.apply(p, "wither", 120, 1);
            case ESPECTRAL -> Compat.apply(p, "glowing", 200, 0);
            case REBOTE -> {
                if (s.rebotes > 0) {
                    List<Player> siguientes = cercanosA(p, 18, s.tocados);
                    if (!siguientes.isEmpty()) rebotar(s, p, siguientes.get(0), s.rebotes - 1);
                }
            }
            default -> {
            }
        }
    }

    /** Lo que hace una flecha al clavarse en el suelo. */
    private void alCaer(Saeta s, Location at) {
        if (at == null) return;
        switch (s.tipo) {
            case VENENOSA -> nubeVenenosa(at);
            case VIENTO -> estallidoDeViento(at, s.mult);
            case IGNEA -> llamas(at, s.mult);
            case TEJEDORA -> telaranas(at);
            default -> {
                fx(Compat.CRIT, at, 4, 0.1, 0.1, 0.1, 0.1);
                polvo(at, s.tipo.rgb, 1.3f);
            }
        }
    }

    /** Jugadores en la pelea cerca de alguien, del mas cercano al mas lejano, sin los ya tocados. */
    private List<Player> cercanosA(Player centro, double radio, Set<UUID> fuera) {
        List<Player> out = new ArrayList<>();
        for (Player o : Fx.playersNear(centro.getLocation(), radio)) {
            if (o.equals(centro) || fuera.contains(o.getUniqueId()) || !enLaPelea(o)) continue;
            out.add(o);
        }
        out.sort((a, b) -> Double.compare(a.getLocation().distanceSquared(centro.getLocation()),
                b.getLocation().distanceSquared(centro.getLocation())));
        return out;
    }

    /** Una flecha nueva sale del que acaba de recibir la anterior hacia otro. */
    private void rebotar(Saeta de, Player desde, Player hacia, int restantes) {
        Location origen = pecho(desde).add(pecho(hacia).toVector().subtract(pecho(desde).toVector())
                .normalize().multiply(1.0));
        Saeta s = disparar(origen, pecho(hacia).toVector().subtract(origen.toVector()), 3.6, de.tipo, de.base, de.mult);
        if (s == null) return;
        s.tocados.addAll(de.tocados);
        s.rebotes = restantes;
        soundAt(origen, "block.amethyst_block.chime", 1.2f, 1.4f + random.nextFloat() * 0.4f);
        fx(Compat.ELECTRIC_SPARK, origen, 8, 0.2, 0.2, 0.2, 0.08);
    }

    // ---------------------------------------------------------- efectos de area

    private void nubeVenenosa(Location at) {
        Location g = Fx.ground(at, 4).add(0, 0.1, 0);
        try {
            AreaEffectCloud c = world().spawn(g, AreaEffectCloud.class, n -> {
                n.setRadius(3.0f);
                n.setDuration(100);
                n.setRadiusPerTick(-0.006f);
                n.setWaitTime(0);
                n.setReapplicationDelay(15);
                n.setColor(Color.fromRGB(0x58D25B));
                n.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 80, 1), true);
                n.setPersistent(false);
                if (boss != null && boss.isValid()) n.setSource(boss);
            });
            markMinion(c);
            expire(c, 110);
        } catch (Throwable ignored) {
        }
        soundAt(g, "entity.splash_potion.break", 1.1f, 0.8f);
        fx(Compat.ITEM_SLIME, g.clone().add(0, 0.4, 0), 14, 0.8, 0.3, 0.8, 0.05);
    }

    private void estallidoDeViento(Location at, double m) {
        Location c = at.clone().add(0, 0.6, 0);
        fx(Compat.GUST_EMITTER_LARGE, c, 1, 0, 0, 0, 0);
        fx(Compat.SMALL_GUST, c, 14, 1.2, 0.6, 1.2, 0.05);
        soundAt(c, "entity.wind_charge.wind_burst", 1.6f, 0.9f);
        soundAt(c, "entity.breeze.wind_burst", 1.0f, 1.2f);
        for (Player p : Fx.playersNear(c, 4.5)) {
            if (!enLaPelea(p)) continue;
            Vector away = p.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (away.lengthSquared() < 0.01) away = new Vector(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5);
            push(p, away.normalize().multiply(1.1).setY(0.85));
        }
    }

    /** Llamas en el suelo unos segundos. De particulas, no de bloque: no prenden la arena. */
    private void llamas(Location at, double m) {
        Location c = Fx.ground(at, 4).add(0, 0.1, 0);
        soundAt(c, "entity.blaze.shoot", 1.0f, 0.8f);
        soundAt(c, "block.fire.ambient", 1.4f, 1.0f);
        animate(100, t -> {
            if (!enCombate()) throw Stop.now();
            if (t % 3 == 0) {
                fx(Compat.FLAME, c, 6, 1.4, 0.15, 1.4, 0.01);
                fx(Compat.SMALL_FLAME, c, 4, 1.6, 0.3, 1.6, 0.02);
            }
            if (t % 10 == 0) fx(Compat.LAVA, c, 1, 1.0, 0.1, 1.0, 0);
            if (t % 20 == 10) {
                for (Player p : Fx.playersNear(c, 2.6)) {
                    if (!enLaPelea(p)) continue;
                    p.setFireTicks(Math.max(p.getFireTicks(), 60));
                    p.setNoDamageTicks(0);
                    hit(p, 3 * damageBonus, m);
                }
            }
        }, null);
    }

    /** Telaranas de verdad alrededor del impacto, solo en aire, y se van a los cinco segundos. */
    private void telaranas(Location at) {
        Block centro = at.getBlock();
        List<Block> sitios = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) sitios.add(centro.getRelative(dx, dy, dz));
            }
        }
        Collections.shuffle(sitios, random);
        int puestas = 0;
        for (Block b : sitios) {
            if (puestas >= 6) break;
            if (!b.getType().isAir() || !dentroDeArena(b.getLocation())) continue;
            b.setType(Material.COBWEB, false);
            telas.put(b, ticks() + 100);
            puestas++;
        }
        if (puestas > 0) {
            soundAt(at, "block.cobweb.place", 1.2f, 0.7f);
            fx(Compat.ITEM_COBWEB, at.clone().add(0, 0.8, 0), 16, 0.8, 0.5, 0.8, 0.05);
        }
    }

    private void tickTelas() {
        if (telas.isEmpty()) return;
        Iterator<Map.Entry<Block, Long>> it = telas.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Block, Long> e = it.next();
            if (ticks() < e.getValue() && !cerrado) continue;
            if (e.getKey().getType() == Material.COBWEB) e.getKey().setType(Material.AIR, false);
            it.remove();
        }
    }

    private void quitarTelas() {
        for (Block b : telas.keySet()) {
            if (b.getType() == Material.COBWEB) b.setType(Material.AIR, false);
        }
        telas.clear();
    }

    @Override
    public boolean ownsBlock(Block block) {
        return telas.containsKey(block);
    }

    @Override
    public boolean onBlockBroken(Block block, Player who) {
        telas.remove(block);
        return false;
    }

    /** La rafaga que remata a quien levito con la Saeta Ingrávida. */
    private void rematarEnElAire(Player p, double m) {
        if (!alive() || !enLaPelea(p)) return;
        apuntado = p;
        tensar(true);
        for (int i = 0; i < 3; i++) {
            later(4 + i * 3, () -> {
                if (!alive() || !enLaPelea(p)) return;
                Saeta s = dispararA(p, 5.0, Tipo.BASICA, D_EFECTO * 0.5, m);
                if (s != null) s.rapida = true;
                soundAt(loc(), "entity.arrow.shoot", 1.0f, 1.8f);
            });
        }
        later(14, () -> tensar(false));
    }

    // ======================================================= MARCA (estado)

    /** 2. Flechas Marcadas: marca a uno; corre en paralelo a todo lo demas. */
    public void flechasMarcadas() {
        if (!alive()) return;
        List<Player> pool = targets();
        pool.removeIf(p -> !enLaPelea(p) || p.hasPotionEffect(PotionEffectType.INVISIBILITY));
        if (pool.isEmpty()) return;
        Player p = pool.get(random.nextInt(pool.size()));
        if (marcado != null) desmarcar(false);
        marcar(p);
    }

    private void marcar(Player p) {
        marcado = p.getUniqueId();
        impactosMarca = 0;
        proximaMarca = ticks() + 30;
        marcadoBrillaba = p.isGlowing();
        Glow.apply(p, NamedTextColor.AQUA);
        apuntado = p;
        p.showTitle(Title.title(
                Component.text("◎ MARCADO ◎", ACCENT, TextDecoration.BOLD),
                Component.text("Cada flecha te quitará más vida. Aléjate o hazte invisible.", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(500))));
        announce(Component.text("Marca a " + p.getName() + ".", HIELO));
        soundAt(loc(), "block.beacon.activate", 1.4f, 1.8f);
        soundAt(p.getLocation(), "entity.illusioner.prepare_mirror", 1.2f, 1.4f);
        Location desde = boca(pecho(p));
        Fx.beam(desde, pecho(p), 0.5, q -> polvo(q, CELESTE, 1.2f));
        fx(Compat.END_ROD, pecho(p), 30, 0.4, 0.6, 0.4, 0.08);
    }

    private void desmarcar(boolean aviso) {
        if (marcado == null) return;
        Player p = Bukkit.getPlayer(marcado);
        marcado = null;
        impactosMarca = 0;
        if (p == null) return;
        Glow.clear(p);
        try {
            p.setGlowing(marcadoBrillaba);
        } catch (Throwable ignored) {
        }
        if (aviso && p.isOnline()) {
            p.sendActionBar(Component.text("La marca de Orión se apaga.", HIELO));
            soundAt(p.getLocation(), "block.beacon.deactivate", 1.0f, 1.6f);
        }
    }

    /**
     * La marca vive hasta que la presa muere, se va a mas de 40 bloques de el o se hace
     * invisible. Mientras tanto le cae una flecha cada 1,2 s, haga lo que haga el jefe.
     */
    private void tickMarca() {
        if (marcado == null) return;
        Player p = Bukkit.getPlayer(marcado);
        if (p == null || !enLaPelea(p) || p.isDead()
                || p.hasPotionEffect(PotionEffectType.INVISIBILITY)
                || p.getLocation().distanceSquared(loc()) > 40 * 40) {
            desmarcar(true);
            return;
        }
        Location cabeza = p.getLocation().add(0, 2.35, 0);
        if (ticks() % 3 == 0) {
            double a = ticks() * 0.35;
            for (int i = 0; i < 3; i++) {
                double b = a + i * Math.PI * 2 / 3;
                polvo(cabeza.clone().add(Math.cos(b) * 0.55, 0, Math.sin(b) * 0.55), CELESTE, 1.1f);
            }
            fx(Compat.END_ROD, cabeza, 1, 0.05, 0.05, 0.05, 0.0);
        }
        if (ticks() % 20 == 0) {
            p.sendActionBar(Component.text("◎ Marcado", ACCENT, TextDecoration.BOLD)
                    .append(Component.text("  ·  " + impactosMarca + " impacto(s)  ·  la próxima quita el "
                            + (5 * (impactosMarca + 1)) + " %", NamedTextColor.GRAY)));
        }
        if (ticks() < proximaMarca || entrando || volviendo()) return;
        proximaMarca = ticks() + 24;
        Saeta s = dispararA(p, 4.8, Tipo.MARCADA, 0, 1.0);
        if (s != null) {
            s.atraviesa = true;
            s.guiada = p;
        }
        soundAt(loc(), "entity.arrow.shoot", 1.1f, 1.2f);
        soundAt(loc(), "block.amethyst_block.chime", 0.8f, 1.8f);
        if (!busy()) apuntado = p;
    }

    /** El golpe de la marca: un % de la vida MAXIMA, puro (ni armadura ni multiplicador). */
    private void golpePuro(Player p) {
        impactosMarca++;
        double max = Compat.getAttribute(p, "max_health", 20);
        double pct = 0.05 * impactosMarca;
        double queda = p.getHealth() - max * pct;
        if (queda <= 0) event.markKill(p);
        else event.markHit(p);
        try {
            p.playHurtAnimation(0f);
        } catch (Throwable ignored) {
        }
        p.setHealth(Math.max(0, Math.min(max, queda)));
        Location at = pecho(p);
        fx(Compat.ELECTRIC_SPARK, at, 16, 0.3, 0.5, 0.3, 0.12);
        fx(Compat.DUST, at, 10, 0.3, 0.5, 0.3, 0, Compat.dust(CELESTE, 1.6f));
        soundAt(at, "block.amethyst_block.break", 1.2f, 1.5f);
        soundAt(at, "entity.arrow.hit_player", 0.8f, 0.9f);
        p.sendActionBar(Component.text("−" + Math.round(pct * 100) + " % de tu vida máxima", ACCENT, TextDecoration.BOLD));
    }

    // ===================================================== REFLEJO (Paralaje)

    private boolean reflejoVivo() {
        return reflejo != null && reflejo.isValid();
    }

    /**
     * 3. Paralaje: se desliza a otro punto de la arena con su estela de sombras y deja donde
     * estaba un Reflejo que lo cura. El Reflejo se llama igual que el y tambien se mueve.
     */
    public void paralaje() {
        if (!alive()) return;
        double m = abilityDamage();
        Location desde = boss.getLocation().clone();
        Player ref = objetivo(50);
        Location destino = null;
        for (int i = 0; i < 8 && destino == null; i++) {
            Location prueba = puntoDeArena(ref != null ? ref.getLocation() : arena, 10, 18);
            if (prueba != null && sitioDeDeslizar(desde, prueba)) destino = prueba;
        }
        if (destino == null) destino = puntoDeArena(arena, 6, RADIO - 4);
        if (destino == null) return;
        announce(Component.text("Paralaje: ¿cuál de los dos es el de verdad?", HIELO));
        soundAt(desde, "entity.illusioner.mirror_move", 1.5f, 1.2f);
        soundAt(desde, "entity.enderman.teleport", 1.0f, 1.6f);
        crearReflejo(desde, m);
        final Location fin = destino.clone();
        if (ref != null) fin.setYaw(yawDe(pecho(ref).toVector().subtract(fin.toVector())));
        int dura = (int) Fx.clamp(Math.round(desde.distance(fin) / 2.2), 7, 13);
        deslizar(boss, fin, dura, 1.4, 2, () -> {
            fx(Compat.FLASH, fin.clone().add(0, 1, 0), 1, 0, 0, 0, 0, GLOW);
            fx(Compat.END_ROD, fin.clone().add(0, 1, 0), 30, 0.3, 0.8, 0.3, 0.1);
            soundAt(fin, "block.amethyst_block.resonate", 1.4f, 1.6f);
        });
    }

    /**
     * Una sombra: otro maniqui con su skin y su arco que se queda donde estuvo y se apaga. Al
     * final de su vida se vuelve invisible y solo queda el contorno de luz, que se deshace en
     * polvo: asi la estela se desvanece en vez de desaparecer de golpe. Intocable y sin barra.
     */
    private void sombra(Location at, float yaw, int vida) {
        if (sombras >= 16 || perfil == null || at == null || !cargado(at)) return;
        try {
            Location l = at.clone();
            l.setYaw(yaw);
            l.setPitch(0);
            Mannequin s = world().spawn(l, Mannequin.class, x -> {
                x.setProfile(perfil);
                x.setPersistent(false);
                x.setGravity(false);
                x.setCollidable(false);
                x.setSilent(true);
                x.setInvulnerable(true);
                x.setImmovable(true);
                try {
                    x.setDescription(Component.empty());
                } catch (Throwable ignored) {
                }
            });
            Compat.setAttribute(s, "scale", ESCALA);
            dePieSiempre(s);
            vestir(s);
            markMinion(s);
            Glow.apply(s, NamedTextColor.AQUA);
            sombras++;
            later(2, () -> {
                if (s.isValid()) s.setProfile(perfil);
            });
            final int apaga = Math.max(3, vida * 3 / 5);
            animate(vida, t -> {
                if (!s.isValid() || cerrado) throw Stop.now();
                if (t == apaga) {
                    // Solo el contorno: la sombra se queda en un trazo de luz que se apaga.
                    s.setInvisible(true);
                    EntityEquipment eq = s.getEquipment();
                    if (eq != null) eq.clear();
                    fx(Compat.END_ROD, s.getLocation().add(0, 1.0, 0), 3, 0.2, 0.5, 0.2, 0.01);
                }
                if (t % 3 == 0) {
                    fx(Compat.DUST, s.getLocation().add(0, 1.0, 0), 3, 0.25, 0.6, 0.25, 0,
                            Compat.dust(t % 6 == 0 ? CELESTE : NUBE, 1.3f));
                }
            }, () -> {
                sombras = Math.max(0, sombras - 1);
                if (!s.isValid()) return;
                Glow.clear(s);
                fx(Compat.POOF, s.getLocation().add(0, 1, 0), 8, 0.25, 0.5, 0.25, 0.02);
                fx(Compat.END_ROD, s.getLocation().add(0, 1, 0), 6, 0.2, 0.6, 0.2, 0.03);
                spawned.remove(s);
                borrar(s);
            });
        } catch (Throwable ignored) {
        }
    }

    private void crearReflejo(Location at, double m) {
        quitarReflejo(false);
        if (perfil == null) return;
        try {
            Location l = at.clone();
            Mannequin r = world().spawn(l, Mannequin.class, x -> {
                x.setProfile(perfil);
                x.setPersistent(false);
                x.setGravity(false);
                x.setCollidable(false);
                x.setSilent(true);
                x.setImmovable(true);
                // Se llama exactamente igual que el: a simple vista no se sabe cual es el bueno.
                x.customName(nombre());
                x.setCustomNameVisible(true);
                try {
                    x.setDescription(Component.empty());
                } catch (Throwable ignored) {
                }
            });
            Compat.setAttribute(r, "scale", ESCALA);
            dePieSiempre(r);
            Compat.setAttribute(r, "max_health", 1024);
            r.setHealth(1024);
            vestir(r);
            markMinion(r);
            Glow.apply(r, NamedTextColor.AQUA);
            reflejo = r;
            AnomalyRegistry reg = plugin.registry();
            reflejoMax = Math.max(60, reg.health(reg.get(ID)) * 0.04);
            reflejoVida = reflejoMax;
            reflejoHasta = ticks() + 500;
            reflejoMult = m;
            reflejoDisparo = ticks() + 30;
            reflejoMover = ticks() + 24 + random.nextInt(16);
            later(2, () -> {
                if (r.isValid()) r.setProfile(perfil);
            });
            fx(Compat.FLASH, l.clone().add(0, 1, 0), 1, 0, 0, 0, 0, GLOW);
        } catch (Throwable t) {
            plugin.getLogger().warning("No se pudo crear el Reflejo de Orión: " + t);
        }
    }

    /** El Reflejo dispara, cura al jefe un 1 % por segundo y se rompe al quedarse sin vida. */
    private void tickReflejo() {
        if (reflejo == null) return;
        if (!reflejo.isValid()) {
            reflejo = null;
            return;
        }
        if (reflejoVida <= 0) {
            quitarReflejo(true);
            return;
        }
        if (ticks() > reflejoHasta) {
            quitarReflejo(false);
            return;
        }
        Location r = reflejo.getLocation();
        if (ticks() % 20 == 0) {
            curar(0.01);
            Location a = r.clone().add(0, 1.3, 0);
            Location b = body().getLocation().add(0, 1.3, 0);
            Fx.beam(a, b, 0.6, q -> polvo(q, ticks() % 40 == 0 ? CELESTE : NUBE, 1.0f));
            fx(Compat.HAPPY_VILLAGER, b, 4, 0.3, 0.5, 0.3, 0);
            soundAt(r, "block.amethyst_block.chime", 0.7f, 0.8f);
        }
        if (ticks() % 4 == 0) fx(Compat.END_ROD, r.clone().add(0, 1.1, 0), 1, 0.3, 0.6, 0.3, 0.01);
        Player t = Fx.nearest(r, 40);
        if (t != null && enLaPelea(t)) {
            Vector d = pecho(t).toVector().subtract(reflejo.getEyeLocation().toVector());
            try {
                reflejo.setRotation(yawDe(d), (float) -Math.toDegrees(Math.atan2(d.getY(), Math.hypot(d.getX(), d.getZ()))));
                reflejo.setBodyYaw(yawDe(d));
            } catch (Throwable ignored) {
            }
            if (ticks() == reflejoDisparo - 10) tensarCuerpo(reflejo, true);
            if (ticks() >= reflejoDisparo) {
                reflejoDisparo = ticks() + 30;
                Location boca = reflejo.getEyeLocation().subtract(0, 0.25, 0).add(d.clone().normalize().multiply(1.2));
                disparar(boca, pecho(t).toVector().subtract(boca.toVector()), 4.0, Tipo.REFLEJO, 5, reflejoMult);
                soundAt(boca, "entity.arrow.shoot", 0.9f, 1.7f);
                tensarCuerpo(reflejo, false);
            }
            // Se mueve como el, con el mismo deslizamiento: quieto lo delataria enseguida.
            if (ticks() >= reflejoMover) {
                reflejoMover = ticks() + 26 + random.nextInt(22);
                moverse(reflejo, t, r.distanceSquared(t.getLocation()) < 20);
            }
        }
    }

    @Override
    public void onMinionDamaged(LivingEntity minion, Player attacker, double amount) {
        if (reflejo == null || !minion.equals(reflejo)) return;
        reflejoVida -= amount;
        // Sin porcentaje en el nombre: lo delataria. Solo la chispa del golpe.
        fx(Compat.ELECTRIC_SPARK, reflejo.getLocation().add(0, 1.2, 0), 6, 0.3, 0.5, 0.3, 0.1);
        soundAt(reflejo.getLocation(), "block.glass.hit", 1.0f, 1.4f);
    }

    /** Se cura vida "de diseno" sin pasarse del techo de su fase. */
    private void curar(double fraccion) {
        if (!alive()) return;
        double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
        double techo = max * (phaseCount() - phase() + 1) / (double) phaseCount();
        if (phase() > 1) techo -= max * 0.002;
        double nueva = Math.min(techo, boss.getHealth() + max * fraccion);
        if (nueva > boss.getHealth()) boss.setHealth(nueva);
    }

    private void quitarReflejo(boolean roto) {
        Mannequin r = reflejo;
        reflejo = null;
        if (r == null || !r.isValid()) return;
        Location l = r.getLocation().add(0, 1, 0);
        if (roto) {
            fx(Compat.FLASH, l, 1, 0, 0, 0, 0, GLOW);
            fx(Compat.END_ROD, l, 60, 0.4, 0.8, 0.4, 0.2);
            fx(Compat.FIREWORK, l, 30, 0.5, 0.6, 0.5, 0.12);
            soundAt(l, "block.glass.break", 1.4f, 0.7f);
            soundAt(l, "block.amethyst_block.break", 1.4f, 0.9f);
            soundAt(l, "entity.illusioner.mirror_move", 1.2f, 0.7f);
            for (int i = 0; i < 3; i++) {
                final double rad = 1.5 + i * 2.2;
                later(i * 3, () -> Fx.ring(l, rad, (int) (rad * 9) + 8, q -> {
                    polvo(q, i2c(rad), 1.6f);
                    fx(Compat.END_ROD, q, 1, 0, 0, 0, 0.02);
                }));
            }
            announce(Component.text("El Reflejo estalla en luz.", HIELO));
        } else {
            fx(Compat.POOF, l, 14, 0.3, 0.6, 0.3, 0.02);
        }
        Glow.clear(r);
        spawned.remove(r);
        Fx.safeRemove(r);
    }

    // ======================================================== DISPLAYS

    /** La rotacion que lleva +Y hacia dir: la punta de una flecha (ya girada con PUNTA) al vuelo. */
    static Quaternionf haciaY(Vector dir) {
        Vector3f d = new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ());
        if (d.lengthSquared() < 1.0E-8f) d.set(0, 1, 0);
        d.normalize();
        return new Quaternionf().rotationTo(new Vector3f(0, 1, 0), d);
    }

    /** right_rotation Z+45: alinea la punta del item de flecha con +Y. */
    static Quaternionf punta() {
        return new Quaternionf().rotateZ(R45);
    }

    /** La misma, girada 90 grados sobre su eje: la copia en cruz que le da volumen. */
    static Quaternionf puntaCruz() {
        return new Quaternionf().rotateY(R90).rotateZ(R45);
    }

    private ItemDisplay objeto(Location at, ItemStack item, Transformation tr, boolean brilla, Display.Billboard bb) {
        if (!cargado(at)) return null;
        try {
            ItemDisplay d = world().spawn(at, ItemDisplay.class, e -> {
                e.setItemStack(item);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                e.setBillboard(bb);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setShadowRadius(0f);
                e.setViewRange(3.0f);
                e.setPersistent(false);
                e.setTransformation(tr);
                e.setInterpolationDelay(0);
                e.setInterpolationDuration(0);
                if (brilla) {
                    e.setGlowColorOverride(GLOW);
                    e.setGlowing(true);
                }
            });
            Tags.markTemporary(d);
            d.addScoreboardTag(MARCA);
            track(d);
            caducan.put(d, ticks() + VIDA_DISPLAY);
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    private BlockDisplay bloque(Location at, Material mat, Transformation tr, boolean brilla) {
        if (!cargado(at)) return null;
        try {
            BlockDisplay d = world().spawn(at, BlockDisplay.class, e -> {
                e.setBlock(mat.createBlockData());
                e.setBrightness(new Display.Brightness(15, 15));
                e.setShadowRadius(0f);
                e.setViewRange(3.0f);
                e.setPersistent(false);
                e.setTransformation(tr);
                e.setInterpolationDelay(0);
                e.setInterpolationDuration(0);
                if (brilla) {
                    e.setGlowColorOverride(GLOW);
                    e.setGlowing(true);
                }
            });
            Tags.markTemporary(d);
            d.addScoreboardTag(MARCA);
            track(d);
            caducan.put(d, ticks() + VIDA_DISPLAY);
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    private void quitar(Entity d) {
        if (d == null) return;
        spawned.remove(d);
        caducan.remove(d);
        borrar(d);
    }

    /**
     * Borra una entidad aunque Bukkit diga que no vale. Fx.safeRemove solo borra lo que isValid(),
     * y una entidad cuyo trozo no esta cargado del todo NO vale pero sigue en el mundo: el display
     * se quedaba ahi, quieto, y aparecia colgado en cuanto alguien volvia a cargar ese trozo.
     */
    private static void borrar(Entity e) {
        if (e == null) return;
        try {
            if (!e.isDead()) e.remove();
        } catch (Throwable ignored) {
        }
    }

    /** Si el trozo de ese sitio esta cargado (no se crea nada donde luego no se podria quitar). */
    private boolean cargado(Location l) {
        if (l == null || l.getWorld() == null) return false;
        return l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4);
    }

    /** Acorta la vida maxima de un display: pasado ese plazo la vigia lo quita sí o sí. */
    private void caduca(Entity d, int ticksDeVida) {
        if (d != null && caducan.containsKey(d)) caducan.put(d, ticks() + ticksDeVida);
    }

    /**
     * La red: todo display de Orion que pase su vida maxima se quita, lo haya hecho su animacion
     * o no (una animacion cortada, una excepcion a mitad, un tope que deja fuera el final).
     */
    private void vigia() {
        if (caducan.isEmpty()) return;
        long ahora = ticks();
        Iterator<Map.Entry<Entity, Long>> it = caducan.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Entity, Long> e = it.next();
            Entity d = e.getKey();
            if (d.isDead()) {
                it.remove();
                spawned.remove(d);
                continue;
            }
            if (ahora < e.getValue()) continue;
            it.remove();
            spawned.remove(d);
            orbita.remove(d);
            borrar(d);
        }
    }

    /**
     * Y la red de la red: cada dos segundos se mira la arena entera y se borra cualquier display o
     * flecha con la etiqueta de Orion que ya no este en sus listas (se perdio su referencia).
     */
    private void barrido() {
        World w = arena.getWorld();
        if (w == null) return;
        for (Entity e : w.getNearbyEntities(arena, 110, 160, 110)) {
            if (!(e instanceof Display) && !(e instanceof AbstractArrow)) continue;
            if (!e.getScoreboardTags().contains(MARCA)) continue;
            if (caducan.containsKey(e) || saetas.containsKey(e.getUniqueId())) continue;
            spawned.remove(e);
            borrar(e);
        }
    }

    /** Lleva la traslacion de un display a 'a' en 'ticks', interpolado por el cliente. */
    private static void mover(Display d, Vector3f a, int ticks) {
        if (d == null || !d.isValid()) return;
        Transformation t = d.getTransformation();
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(new Transformation(a, t.getLeftRotation(), t.getScale(), t.getRightRotation()));
    }

    /**
     * Una flecha de luz: dos items de flecha cruzados (para que se vea de cualquier lado), con
     * la punta hacia dir, brillo celeste y luz 15.
     */
    private ItemDisplay[] saetaDeLuz(Location at, float escala, Vector dir) {
        ItemStack flecha = new ItemStack(Material.ARROW);
        Quaternionf izq = haciaY(dir);
        Vector3f s = new Vector3f(escala, escala, escala);
        ItemDisplay a = objeto(at, flecha, new Transformation(new Vector3f(), new Quaternionf(izq), s, punta()),
                true, Display.Billboard.FIXED);
        ItemDisplay b = objeto(at, flecha, new Transformation(new Vector3f(), new Quaternionf(izq), new Vector3f(s), puntaCruz()),
                true, Display.Billboard.FIXED);
        // Las dos viven menos de un segundo y medio (entrada, Saeta Mayor): con tres ya sobran.
        caduca(a, 60);
        caduca(b, 60);
        return new ItemDisplay[]{a, b};
    }

    // ===================================================== LA ARENA Y EL CIELO

    private boolean dentroDeArena(Location l) {
        if (l == null || l.getWorld() != arena.getWorld()) return false;
        double dx = l.getX() - arena.getX(), dz = l.getZ() - arena.getZ();
        return dx * dx + dz * dz <= RADIO * RADIO && dentroDeLaCorrea(l);
    }

    private static boolean dePie(Location l) {
        Block f = l.getBlock();
        return !f.getType().isSolid() && !f.isLiquid()
                && !f.getRelative(BlockFace.UP).getType().isSolid()
                && f.getRelative(BlockFace.DOWN).getType().isSolid();
    }

    /** Un sitio de pie dentro de la arena a entre min y max bloques de ref. */
    private Location puntoDeArena(Location ref, double min, double max) {
        for (int i = 0; i < 28; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = min + random.nextDouble() * Math.max(0.1, max - min);
            Location l = Fx.ground(ref.clone().add(Math.cos(a) * r, 2, Math.sin(a) * r), 10);
            if (dentroDeArena(l) && dePie(l)) return l;
        }
        return null;
    }

    /** Punto al azar del suelo de la arena (sin exigir que se pueda estar de pie). */
    private Location sueloAlAzar(double maxR) {
        double a = random.nextDouble() * Math.PI * 2;
        double r = Math.sqrt(random.nextDouble()) * maxR;
        return Fx.ground(arena.clone().add(Math.cos(a) * r, 3, Math.sin(a) * r), 10);
    }

    /** Lleva un punto al circulo de la arena si se sale. */
    private Location alCirculo(Location l, double r) {
        double dx = l.getX() - arena.getX(), dz = l.getZ() - arena.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= r) return l;
        Location out = l.clone();
        out.setX(arena.getX() + dx / d * r);
        out.setZ(arena.getZ() + dz / d * r);
        return out;
    }

    /** El cielo de Orion sobre la arena: por la zona de Lethal Biomes o, sin ella, prestado a mano. */
    private void cieloOrion() {
        cieloOrion(null);
    }

    /**
     * Igual, avisando a 'listo' cuando el cielo ya se ve azul del todo. El Pintor de Lethal Biomes
     * reparte la zona en varios ticks y reenvia los trozos al final (unos 3 s en el Coliseo); el
     * cielo prestado se pinta de golpe y se reenvia a ocho trozos por tick.
     */
    private void cieloOrion(Runnable listo) {
        if (cerrado) return;
        if (cieloPuesto) {
            if (listo != null) {
                if (cieloPintando) alPintarCielo.add(listo);
                else listo.run();
            }
            return;
        }
        cieloPuesto = true;
        cieloPintando = true;
        if (listo != null) alPintarCielo.add(listo);
        if (plugin.manager().climaDeArena(arena, "orion", () -> later(0, this::cieloPintado))) return;
        if (prestado == null) {
            prestado = CieloPrestado.pintar(this, arena.clone());
            if (prestado == null && !avisoBioma) {
                avisoBioma = true;
                plugin.getLogger().warning("ORION: el bioma lethal:orion no esta cargado (¿falta el reinicio "
                        + "tras instalar el datapack?). El cielo no cambia.");
            }
        }
        later(prestado == null ? 0 : prestado.trozos.size() / 8 + 2, this::cieloPintado);
    }

    /** El cielo de Orion ya se ve: se avisa a quien esperaba (Star Deploy, para soltar la lluvia). */
    private void cieloPintado() {
        cieloPintando = false;
        if (alPintarCielo.isEmpty()) return;
        List<Runnable> avisar = new ArrayList<>(alPintarCielo);
        alPintarCielo.clear();
        for (Runnable r : avisar) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Devuelve el cielo base al rato, salvo que otro Star Deploy lo haya vuelto a pedir antes. */
    private void cieloBaseLuego(int ticksDespues) {
        if (cieloFijo) return;
        final int gen = cieloGen;
        later(ticksDespues, () -> {
            if (gen == cieloGen) cieloBase();
        });
    }

    /** Devuelve el cielo de la ficha (salvo en la fase III, que se queda en el de Orion). */
    private void cieloBase() {
        // Cerrada la pelea, el clima ya lo devolvio el manager: repintar aqui lo dejaria puesto.
        if (cieloFijo || cerrado || !cieloPuesto) return;
        cieloPuesto = false;
        cieloPintando = false;
        alPintarCielo.clear();
        if (prestado != null) {
            prestado.devolver(this, false);
            prestado = null;
            return;
        }
        plugin.manager().climaDeArena(arena, null);
    }

    /**
     * El cielo prestado: cuando la arena no tiene zona de Lethal Biomes, se pinta a mano un
     * cuadrado de 100 x 100 alrededor y se apunta celda a celda lo que habia para devolverlo.
     */
    private static final class CieloPrestado {
        private static final int MEDIO = 50;
        private final World w;
        private final int x0, y0, z0, nx, ny, nz;
        private final Biome[] antes;
        private final List<long[]> trozos = new ArrayList<>();

        private CieloPrestado(World w, int x0, int y0, int z0, int nx, int ny, int nz) {
            this.w = w;
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.antes = new Biome[nx * ny * nz];
        }

        static Biome bioma() {
            try {
                return io.papermc.paper.registry.RegistryAccess.registryAccess()
                        .getRegistry(io.papermc.paper.registry.RegistryKey.BIOME)
                        .get(org.bukkit.NamespacedKey.fromString("lethal:orion"));
            } catch (Throwable t) {
                return null;
            }
        }

        static CieloPrestado pintar(Orion f, Location c) {
            Biome b = bioma();
            World w = c.getWorld();
            if (b == null || w == null) return null;
            int x0 = Math.floorDiv(c.getBlockX() - MEDIO, 4) * 4;
            int z0 = Math.floorDiv(c.getBlockZ() - MEDIO, 4) * 4;
            int y0 = Math.max(w.getMinHeight(), Math.floorDiv(c.getBlockY() - 16, 4) * 4);
            int yTop = Math.min(w.getMaxHeight() - 1, c.getBlockY() + 80);
            int n = (MEDIO * 2) / 4 + 1;
            int ny = Math.max(1, (yTop - y0) / 4 + 1);
            CieloPrestado cp = new CieloPrestado(w, x0, y0, z0, n, ny, n);
            Set<Long> vistos = new HashSet<>();
            int i = 0;
            for (int ix = 0; ix < n; ix++) {
                for (int iz = 0; iz < n; iz++) {
                    int x = x0 + ix * 4, z = z0 + iz * 4;
                    boolean cargado = w.isChunkLoaded(x >> 4, z >> 4);
                    if (cargado && vistos.add(((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL))) {
                        cp.trozos.add(new long[]{x >> 4, z >> 4});
                    }
                    for (int iy = 0; iy < ny; iy++, i++) {
                        if (!cargado) continue;
                        int y = y0 + iy * 4;
                        try {
                            cp.antes[i] = w.getBiome(x, y, z);
                            w.setBiome(x, y, z, b);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            cp.refrescar(f, false);
            return cp;
        }

        void devolver(Orion f, boolean ya) {
            int i = 0;
            for (int ix = 0; ix < nx; ix++) {
                for (int iz = 0; iz < nz; iz++) {
                    for (int iy = 0; iy < ny; iy++, i++) {
                        Biome b = antes[i];
                        if (b == null) continue;
                        try {
                            w.setBiome(x0 + ix * 4, y0 + iy * 4, z0 + iz * 4, b);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            refrescar(f, ya);
        }

        /** El cliente no ve el bioma nuevo hasta que se le reenvia el trozo: ocho por tick. */
        private void refrescar(Orion f, boolean ya) {
            if (ya) {
                for (long[] t : trozos) refrescarUno(t);
                return;
            }
            List<long[]> cola = new ArrayList<>(trozos);
            net.ederus.edm.anomaly.core.Anim.run(f.plugin, cola.size() / 8 + 1, t -> {
                for (int k = t * 8; k < Math.min(cola.size(), t * 8 + 8); k++) refrescarUno(cola.get(k));
            }, null);
        }

        private void refrescarUno(long[] t) {
            try {
                if (w.isChunkLoaded((int) t[0], (int) t[1])) w.refreshChunk((int) t[0], (int) t[1]);
            } catch (Throwable ignored) {
            }
        }
    }

    // ============================================================ FASES

    @Override
    protected void onPhaseChange(int from, int to) {
        if (event.bars() != null) event.bars().flash(from);
        if (to == 2) alineacion();
        if (to >= 3) horaDeOrion();
    }

    /** FASE II, «Alineación»: tres estrellas en fila sobre el y el carcaj se abre del todo. */
    private void alineacion() {
        damageBonus = 1.15;
        if (!alive()) return;
        busyFor(45);
        tensar(false);
        Location c = body().getLocation();
        titleNear(Component.text("FASE II", ACCENT, TextDecoration.BOLD),
                Component.text("Alineación", HIELO));
        soundAt(c, "block.beacon.power_select", 1.6f, 1.2f);
        soundAt(c, "block.amethyst_block.resonate", 1.6f, 0.8f);
        Vector lado = c.getDirection().setY(0);
        if (lado.lengthSquared() < 0.01) lado = new Vector(1, 0, 0);
        lado = lado.normalize().rotateAroundY(Math.PI / 2);
        for (int i = -1; i <= 1; i++) {
            final Location estrella = c.clone().add(lado.clone().multiply(i * 2.2)).add(0, 4.2 - i * 0.3, 0);
            later(8 + (i + 1) * 6, () -> {
                fx(Compat.FLASH, estrella, 1, 0, 0, 0, 0, GLOW);
                fx(Compat.END_ROD, estrella, 24, 0.2, 0.2, 0.2, 0.12);
                soundAt(estrella, "block.amethyst_block.chime", 1.6f, 1.0f + 0.3f);
            });
        }
        animate(40, t -> {
            if (t % 2 == 0) Fx.ring(c.clone().add(0, 0.2, 0), 1 + t * 0.35, 30, q -> polvo(Fx.ground(q, 4).add(0, 0.2, 0), t % 4 == 0 ? CELESTE : AZUL, 1.4f));
        }, null);
    }

    /** FASE III, «Hora de Orión»: el cielo de la arena es suyo hasta el final. */
    private void horaDeOrion() {
        damageBonus = 1.3;
        if (!alive()) return;
        busyFor(55);
        tensar(false);
        Location c = body().getLocation();
        titleNear(Component.text("FASE III", ACCENT, TextDecoration.BOLD),
                Component.text("Hora de Orión", HIELO));
        warn(Component.text("El cielo se tiñe de azul. Ahora es suyo.", HIELO, TextDecoration.BOLD));
        soundAt(c, "block.beacon.activate", 1.8f, 0.7f);
        soundAt(c, "item.trident.thunder", 1.0f, 1.2f);
        soundAt(c, "block.amethyst_block.resonate", 1.8f, 0.6f);
        cieloFijo = true;
        cieloOrion();
        pitchFijo = -70f;
        animate(50, t -> {
            Location top = c.clone().add(0, 2 + t * 0.9, 0);
            fx(Compat.END_ROD, top, 4, 0.15, 0.3, 0.15, 0.02);
            polvo(top, t % 2 == 0 ? CELESTE : BLANCO, 2.0f);
            if (t % 10 == 0) soundAt(c, "block.amethyst_block.chime", 1.4f, 0.6f + t * 0.02f);
        }, () -> {
            pitchFijo = null;
            fx(Compat.FLASH, c.clone().add(0, 30, 0), 1, 0, 0, 0, 0, GLOW);
            for (int i = 0; i < 3; i++) {
                final double r = 3 + i * 5;
                later(i * 4, () -> Fx.ring(c.clone().add(0, 0.2, 0), r, (int) (r * 6) + 10,
                        q -> polvo(Fx.ground(q, 4).add(0, 0.2, 0), i2c(r), 1.8f)));
            }
        });
    }

    // ============================================================ MUERTE

    @Override
    public void onDeath() {
        desmarcar(false);
        quitarReflejo(false);
        orbitando = false;
        for (ItemDisplay d : orbita) quitar(d);
        orbita.clear();
        tensar(false);
        pitchFijo = -50f;
        Location c = loc().clone();
        soundAt(c, "block.beacon.deactivate", 1.6f, 0.6f);
        soundAt(c, "entity.illusioner.mirror_move", 1.4f, 0.5f);
        announce(Component.text("La estrella vuelve a su sitio en el cielo.", HIELO));

        /* Su constelacion se dibuja sobre el antes de irse: Betelgeuse, Bellatrix, el
         * cinturon, Saiph y Rigel, en el plano que mira al que tiene mas cerca. */
        double[][] estrellas = {{-3, 4}, {3, 3.5}, {-1, 0}, {0, 0.3}, {1, 0.6}, {-2.5, -4}, {3, -3.5}};
        int[][] lineas = {{0, 1}, {0, 2}, {1, 4}, {2, 3}, {3, 4}, {2, 5}, {4, 6}};
        Player mira = Fx.nearest(c, 60);
        Vector hacia = mira == null ? new Vector(0, 0, 1) : mira.getLocation().toVector().subtract(c.toVector()).setY(0);
        if (hacia.lengthSquared() < 0.01) hacia = new Vector(0, 0, 1);
        Vector derecha = hacia.normalize().clone().rotateAroundY(Math.PI / 2);
        Location centro = c.clone().add(0, 11, 0);
        List<Location> puntos = new ArrayList<>();
        for (double[] e : estrellas) puntos.add(centro.clone().add(derecha.clone().multiply(e[0] * 1.6)).add(0, e[1] * 1.6, 0));
        animate(90, t -> {
            if (t < 40) {
                int cuantas = Math.min(puntos.size(), t / 5 + 1);
                for (int i = 0; i < cuantas; i++) {
                    fx(Compat.END_ROD, puntos.get(i), 1, 0.05, 0.05, 0.05, 0.0);
                    polvo(puntos.get(i), BLANCO, 2.4f);
                }
                if (t % 5 == 0 && t / 5 < puntos.size()) {
                    soundAt(puntos.get(t / 5), "block.amethyst_block.chime", 1.4f, 0.9f + t * 0.02f);
                }
                return;
            }
            if (t % 2 == 0) {
                for (int[] l : lineas) Fx.beam(puntos.get(l[0]), puntos.get(l[1]), 0.4, q -> polvo(q, CELESTE, 1.2f));
                for (Location p : puntos) polvo(p, BLANCO, 2.6f);
            }
            if (t == 70) {
                for (Location p : puntos) {
                    fx(Compat.FLASH, p, 1, 0, 0, 0, 0, GLOW);
                    fx(Compat.FIREWORK, p, 12, 0.2, 0.2, 0.2, 0.1);
                }
                soundAt(centro, "entity.firework_rocket.twinkle", 1.6f, 1.0f);
                soundAt(centro, "block.amethyst_block.resonate", 1.6f, 1.4f);
            }
        }, () -> pitchFijo = null);
    }

    // ============================================================ LIMPIEZA

    @Override
    public void cleanup() {
        cerrado = true;
        try {
            HandlerList.unregisterAll(escucha);
        } catch (Throwable ignored) {
        }
        desmarcar(false);
        for (Saeta s : new ArrayList<>(saetas.values())) borrar(s.flecha);
        saetas.clear();
        orbita.clear();
        // Los displays tambien con borrar(): el barrido del padre solo quita lo que isValid().
        for (Entity d : new ArrayList<>(caducan.keySet())) borrar(d);
        caducan.clear();
        alPintarCielo.clear();
        quitarTelas();
        if (prestado != null) {
            prestado.devolver(this, true);
            prestado = null;
        }
        reflejo = null;
        super.cleanup();
    }

    // ============================================================ ESCUCHA

    /**
     * Las flechas del jefe no hacen dano vanilla: aqui se cambian por golpear(), que pasa por
     * hit() con el multiplicador de la habilidad. Atraviesan sus propios cuerpos (el maniqui,
     * el Reflejo, las sombras) y a los mobs del mundo.
     */
    private final class Escucha implements Listener {

        @EventHandler(priority = EventPriority.LOWEST)
        public void onImpacto(ProjectileHitEvent e) {
            Saeta s = saetas.get(e.getEntity().getUniqueId());
            if (s == null) return;
            Entity tocado = e.getHitEntity();
            if (tocado != null) {
                e.setCancelled(true);
                if (!(tocado instanceof Player p) || Tags.isOurs(tocado)) return;
                if (s.tipo == Tipo.MARCADA) {
                    if (!p.getUniqueId().equals(marcado)) return;
                    golpear(s, p);
                    quitar(s);
                    return;
                }
                golpear(s, p);
                if (!s.atraviesa) quitar(s);
                return;
            }
            if (e.getHitBlock() != null && !s.resuelta) {
                s.resuelta = true;
                Location donde = e.getEntity().getLocation();
                later(1, () -> {
                    alCaer(s, donde);
                    quitar(s);
                });
            }
        }

        /** Red de seguridad: si una flecha suya llega a pegar por otra via, sin dano vanilla. */
        @EventHandler(priority = EventPriority.LOWEST)
        public void onGolpe(EntityDamageByEntityEvent e) {
            if (saetas.containsKey(e.getDamager().getUniqueId())) e.setCancelled(true);
        }

        /**
         * El Reflejo no pierde vida vanilla: su vida es la virtual (onMinionDamaged ya apunto el
         * golpe en HIGHEST). Sin esto un golpe gordo lo mataba de verdad antes de tiempo.
         */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onReflejoHerido(EntityDamageEvent e) {
            if (reflejo != null && e.getEntity().equals(reflejo)) e.setDamage(0);
        }

        /** Los lepismas y limos de Infestado y Supurante se van con la pelea. */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onCria(CreatureSpawnEvent e) {
            if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.POTION_EFFECT) return;
            LivingEntity bicho = e.getEntity();
            if (bicho.getWorld() != world() || bicho.getLocation().distanceSquared(arena) > 80 * 80) return;
            track(bicho);
            expire(bicho, 400);
        }
    }

    /** Durante la Orbita, las flechas (y tridentes) de los jugadores que se le acercan se paran. */
    private void pararFlechas() {
        Location c = body().getLocation().add(0, 1.2, 0);
        for (Entity e : world().getNearbyEntities(c, 3.4, 3.4, 3.4)) {
            if (!(e instanceof Projectile pr) || !(pr.getShooter() instanceof Player)) continue;
            /* Un tridente no se borra: es el objeto del jugador y se perderia (tambien el que
             * ya esta clavado en el suelo junto al jefe). Se le corta el vuelo y cae. */
            if (pr instanceof org.bukkit.entity.Trident tr) {
                if (tr.isInBlock() || tr.isOnGround()) continue;
                Vector v = tr.getVelocity();
                if (v.lengthSquared() < 0.05) continue;
                tr.setVelocity(v.multiply(-0.15).setY(0.1));
                fx(Compat.ELECTRIC_SPARK, tr.getLocation(), 10, 0.15, 0.15, 0.15, 0.1);
                soundAt(tr.getLocation(), "item.shield.block", 1.0f, 1.6f);
                continue;
            }
            Location l = pr.getLocation();
            fx(Compat.ELECTRIC_SPARK, l, 10, 0.15, 0.15, 0.15, 0.1);
            polvo(l, CELESTE, 1.6f);
            soundAt(l, "item.shield.block", 1.0f, 1.6f);
            soundAt(l, "block.amethyst_block.hit", 1.0f, 1.8f);
            Fx.safeRemove(pr);
        }
    }

    // ============================================================ AUTOPRUEBA

    /**
     * Comprueba sin crear entidades lo que no se ve hasta la pelea: que la skin firmada lleva la
     * textura buena, que las cuentas de los displays (estrellas del Cinturón, Lanza de Rigel,
     * flechas de luz) dan matrices finitas y apuntan donde deben, y que estan las 34 habilidades.
     */
    public static boolean autoprueba(java.util.logging.Logger log, AnomalyRegistry registry) {
        try {
            String json = new String(java.util.Base64.getDecoder().decode(SKIN_VALUE),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!json.contains(SKIN_HASH)) throw new IllegalStateException("la textura firmada no lleva el hash de la skin");
            if (java.util.Base64.getDecoder().decode(SKIN_SIGNATURE).length != 512) {
                throw new IllegalStateException("la firma no mide 512 bytes");
            }
            Vector3f arriba = punta().transform(new Vector3f(1, 1, 0));
            if (Math.abs(arriba.x) > 1.0E-4f || arriba.y < 1.4f) throw new IllegalStateException("la punta no queda en +Y");
            Vector3f abajo = haciaY(new Vector(0, -1, 0)).transform(new Vector3f(0, 1, 0));
            if (abajo.y > -0.999f) throw new IllegalStateException("haciaY no da la vuelta");
            for (int g = 0; g < 360; g += 15) {
                comprobar(estrella(true, (float) Math.toRadians(g), 1f, 0f), "estrella exterior");
                comprobar(estrella(false, (float) Math.toRadians(g), 1f, 0f), "estrella interior");
            }
            for (PiezaLanza p : LANZA) comprobar(p.tr(1f), "lanza " + p.mat);
            int n = 0;
            if (registry != null && registry.get(ID) != null) n = registry.get(ID).abilities().size();
            log.info("ORION: autoprueba superada (skin firmada, Cinturón, Lanza de Rigel y saetas de luz); "
                    + n + " de 34 habilidades activas.");
            return true;
        } catch (RuntimeException e) {
            log.warning("ORION: la autoprueba falló: " + e.getMessage());
            return false;
        }
    }

    private static void comprobar(Transformation t, String que) {
        if (!t.getTranslation().isFinite() || !t.getScale().isFinite()
                || !Float.isFinite(t.getLeftRotation().w) || !Float.isFinite(t.getRightRotation().w)) {
            throw new IllegalStateException("matriz no finita en " + que);
        }
    }

    // =========================================================================
    // ============================== HABILIDADES ==============================
    // =========================================================================

    // ---------------------------------------------------------- 1. STAR DEPLOY

    /**
     * 1. Star Deploy. Apunta al cielo y tensa tres segundos: el suelo manda ondas azules que se
     * cierran hacia el cada vez mas seguidas, todo el mundo va lento y el solo encaja el 20 %.
     * Tres circulos de luz son los unicos sitios a salvo. Suelta la Saeta Mayor, que sube a
     * 55 bloques por segundo; el cielo se vuelve el suyo y cae una lluvia de flechas sobre
     * 100 x 100 bloques. Quien no este en un circulo se lleva 35.
     */
    public void starDeploy() {
        if (!alive()) return;
        final double m = abilityDamage();
        final Location c = Fx.ground(boss.getLocation(), 6);
        final World w = world();
        try {
            if (boss instanceof Mob mob) mob.getPathfinder().stopPathfinding();
        } catch (Throwable ignored) {
        }
        cargando = true;
        apuntado = null;
        miraFija = null;
        pitchFijo = -90f;
        tensar(true);
        // El cielo empieza a cambiar YA, en el primer tick de la carga: asi, cuando suelta la Saeta
        // Mayor y caen las flechas, ya esta azul del todo. Con la generacion nueva, un cieloBase
        // atrasado de un Star Deploy anterior no lo apaga a mitad.
        final int gen = ++cieloGen;
        cieloListo = false;
        cieloOrion(() -> {
            if (gen == cieloGen) cieloListo = true;
        });
        titleNear(Component.text("✦ STAR DEPLOY ✦", ACCENT, TextDecoration.BOLD),
                Component.text("Corre a un círculo de luz", HIELO));
        soundAt(c, "block.beacon.activate", 1.8f, 1.5f);
        soundAt(c, "block.amethyst_block.resonate", 1.6f, 0.7f);
        soundAt(c, "entity.illusioner.prepare_mirror", 1.4f, 0.8f);

        // Los tres claros: lejos de el (12-30) y dentro de la arena.
        List<Location> claros = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Location cl = null;
            for (int k = 0; k < 30 && cl == null; k++) {
                Location prueba = puntoDeArena(c, 12, 30);
                if (prueba == null) continue;
                boolean junto = false;
                for (Location otro : claros) if (otro.distanceSquared(prueba) < 36) junto = true;
                if (!junto) cl = prueba;
            }
            if (cl == null) cl = puntoDeArena(arena, 4, RADIO - 3);
            if (cl != null) claros.add(cl);
        }
        // Sin columnas de cristal (Dosa: «se ven feas»): el haz de cada claro es de
        // particulas, ver hazDeClaro. La lista se queda vacia para no tocar las firmas.
        List<BlockDisplay> columnas = new ArrayList<>();

        final List<double[]> ondas = new ArrayList<>();
        final int[] siguiente = {0};
        final int carga = 60;
        // Si el cielo aun no se ve, la carga se alarga hasta segundo y medio esperandolo.
        final int espera = 30;
        animate(carga + espera, t -> {
            if (!alive() || cerrado) throw Stop.now();
            if (t >= carga && (cieloListo || t >= carga + espera)) throw Stop.now();
            double u = Math.min(1, t / (double) carga);
            if (t % 20 == 0) {
                for (Player p : targets()) if (enLaPelea(p)) Compat.apply(p, "slowness", 30, 3);
            }
            // Ondas que se cierran hacia el, cada vez mas seguidas y mas rapidas.
            if (t >= siguiente[0]) {
                ondas.add(new double[]{30.0, ondas.size()});
                siguiente[0] = t + Math.max(3, (int) Math.round(16 - 13 * u));
                soundAt(c, "block.beacon.ambient", 1.2f, 0.8f + (float) u);
            }
            double vel = 1.0 + 1.3 * u;
            Iterator<double[]> it = ondas.iterator();
            while (it.hasNext()) {
                double[] o = it.next();
                o[0] -= vel;
                if (o[0] < 0.6) {
                    it.remove();
                    continue;
                }
                int rgb = ((int) o[1]) % 2 == 0 ? AZUL : CELESTE;
                int puntos = (int) Math.max(10, Math.min(66, o[0] * 2.2));
                Fx.ring(c.clone().add(0, 0.15, 0), o[0], puntos, q -> polvo(q, rgb, 1.5f));
            }
            // Los claros, bien visibles.
            if (t % 2 == 0) {
                for (Location cl : claros) {
                    Location base = cl.clone().add(0, 0.15, 0);
                    Fx.ring(base, 2.5, 22, q -> {
                        fx(Compat.END_ROD, q, 1, 0, 0, 0, 0);
                        polvo(q, BLANCO, 1.4f);
                    });
                    Fx.ring(base, 1.4, 12, q -> polvo(q, BLANCO, 1.0f));
                    hazDeClaro(base, t);
                }
            }
            // La carga en el arco: luz que sube desde la mano.
            Location mano = body().getEyeLocation().add(0, 0.6, 0);
            fx(Compat.END_ROD, mano, 2, 0.1, 0.2, 0.1, 0.02);
            polvo(mano.clone().add(0, 0.5 + u * 1.5, 0), CELESTE, 1.8f);
            if (t % 10 == 0) soundAt(c, "block.amethyst_block.chime", 1.4f, 0.6f + (float) u * 1.2f);
            if (t % 10 == 5) {
                for (Player p : targets()) {
                    boolean salvo = enClaro(p.getLocation(), claros);
                    p.sendActionBar(salvo
                            ? Component.text("A salvo en la luz. Aguanta.", HIELO)
                            : Component.text("¡Busca un círculo de luz!", NamedTextColor.RED, TextDecoration.BOLD));
                }
            }
        }, () -> {
            cargando = false;
            if (!alive() || cerrado) {
                for (BlockDisplay col : columnas) quitar(col);
                pitchFijo = null;
                tensar(false);
                cieloBaseLuego(20);
                return;
            }
            soltarSaetaMayor(c, claros, columnas, m);
        });
    }

    /**
     * Lo que marca un claro de Star Deploy, todo a ras de suelo (Dosa: los sitios seguros
     * se marcan sin nada que suba): destellos blancos y celestes que brillan dentro del
     * circulo y un segundo anillo que gira despacio.
     */
    private void hazDeClaro(Location base, int t) {
        for (int i = 0; i < 6; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(random.nextDouble()) * 2.3;
            Location q = base.clone().add(Math.cos(a) * r, 0.05, Math.sin(a) * r);
            polvo(q, i % 2 == 0 ? BLANCO : NUBE, 1.1f);
        }
        double giro = t * 0.12;
        for (int i = 0; i < 8; i++) {
            double a = giro + i * Math.PI / 4;
            fx(Compat.END_ROD, base.clone().add(Math.cos(a) * 1.9, 0.08, Math.sin(a) * 1.9), 1, 0, 0, 0, 0);
        }
    }

    private static boolean enClaro(Location l, List<Location> claros) {
        for (Location cl : claros) {
            if (cl.getWorld() != l.getWorld()) continue;
            double dx = l.getX() - cl.getX(), dz = l.getZ() - cl.getZ();
            if (dx * dx + dz * dz <= 2.8 * 2.8 && Math.abs(l.getY() - cl.getY()) < 4) return true;
        }
        return false;
    }

    /** La Saeta Mayor sube como un rayo; arriba revienta, cambia el cielo y empieza la lluvia. */
    private void soltarSaetaMayor(Location c, List<Location> claros, List<BlockDisplay> columnas, double m) {
        tensar(false);
        Location mano = body().getEyeLocation().add(0, 0.8, 0);
        soundAt(mano, "block.beacon.power_select", 2.0f, 1.8f);
        soundAt(mano, "entity.illusioner.cast_spell", 1.6f, 1.4f);
        soundAt(mano, "block.amethyst_block.resonate", 2.0f, 2.0f);
        soundAt(mano, "entity.firework_rocket.launch", 1.6f, 0.6f);
        soundAt(mano, "item.trident.thunder", 0.6f, 1.8f);
        fx(Compat.FLASH, mano, 1, 0, 0, 0, 0, GLOW);
        fx(Compat.SONIC_BOOM, mano, 1, 0, 0, 0, 0);
        ItemDisplay[] saeta = saetaDeLuz(mano, 3.0f, new Vector(0, 1, 0));
        final int subida = 20;
        later(1, () -> {
            for (ItemDisplay d : saeta) mover(d, new Vector3f(0, 55f, 0), subida);
        });
        animate(subida, t -> {
            double y0 = 55.0 * Math.max(0, t - 2) / subida, y1 = 55.0 * Math.max(0, t - 1) / subida;
            Location a = mano.clone().add(0, y0, 0), b = mano.clone().add(0, y1, 0);
            Fx.beam(a, b, 0.6, q -> {
                fx(Compat.END_ROD, q, 1, 0.03, 0.03, 0.03, 0.0);
                polvo(q, t % 2 == 0 ? CELESTE : BLANCO, 2.0f);
            });
        }, () -> {
            for (ItemDisplay d : saeta) quitar(d);
            Location top = mano.clone().add(0, 55, 0);
            fx(Compat.FLASH, top, 1, 0, 0, 0, 0, GLOW);
            fx(Compat.FIREWORK, top, 80, 2.5, 2.5, 2.5, 0.3);
            fx(Compat.END_ROD, top, 60, 3, 3, 3, 0.2);
            soundAt(c, "entity.lightning_bolt.thunder", 1.2f, 1.6f);
            soundAt(c, "entity.firework_rocket.large_blast_far", 2.0f, 0.7f);
            pitchFijo = null;
            lluviaDeEstrellas(c, claros, columnas, m);
        });
    }

    /** Una flecha de luz cayendo: el display, de donde sale, a donde va y cuando. */
    private static final class Caida {
        final ItemDisplay d;
        final Location desde;
        final Vector recorrido;
        final int nace;
        final int dura;
        boolean lanzada;

        Caida(ItemDisplay d, Location desde, Vector recorrido, int nace, int dura) {
            this.d = d;
            this.desde = desde;
            this.recorrido = recorrido;
            this.nace = nace;
            this.dura = dura;
        }
    }

    private void lluviaDeEstrellas(Location c, List<Location> claros, List<BlockDisplay> columnas, double m) {
        final List<Caida> cayendo = new ArrayList<>();
        final Vector viento = new Vector(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5).normalize().multiply(0.18);
        final ItemStack flecha = new ItemStack(Material.ARROW);
        final int dura = 72;
        warn(Component.text("«Que caigan todas.»", ACCENT, TextDecoration.BOLD));
        animate(dura + 22, t -> {
            if (cerrado) throw Stop.now();
            // Nacen nueve por tick mientras dura la lluvia, sin pasar del tope.
            if (t < dura) {
                for (int i = 0; i < 9 && cayendo.size() < MAX_LLUVIA; i++) {
                    double x, z;
                    if (random.nextDouble() < 0.6) {
                        double a = random.nextDouble() * Math.PI * 2, r = Math.sqrt(random.nextDouble()) * 32;
                        x = c.getX() + Math.cos(a) * r;
                        z = c.getZ() + Math.sin(a) * r;
                    } else {
                        x = c.getX() + rnd(50);
                        z = c.getZ() + rnd(50);
                    }
                    int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
                    // Solo en trozos cargados: preguntar la altura de uno sin cargar lo cargaria.
                    int alto = world().isChunkLoaded(bx >> 4, bz >> 4)
                            ? world().getHighestBlockYAt(bx, bz) : (int) Math.floor(c.getY()) - 1;
                    double suelo = alto > c.getY() + 30 || alto < c.getY() - 30 ? c.getY() : alto + 1;
                    double velocidad = (42 + random.nextDouble() * 18) / 20.0;
                    Vector dir = new Vector(viento.getX(), -1, viento.getZ()).normalize();
                    double caida = 40 + random.nextDouble() * 8;
                    Vector recorrido = dir.clone().multiply(caida / -dir.getY());
                    Location desde = new Location(world(), x, suelo + 0.6, z).subtract(recorrido);
                    // Ni nace ni cae en un trozo sin cargar: ahi no valdria ni para moverla ni para
                    // quitarla y se quedaba colgada en el cielo hasta que alguien cargaba el trozo.
                    if (!cargado(desde) || !cargado(desde.clone().add(recorrido))) continue;
                    float esc = 2.0f + random.nextFloat() * 0.9f;
                    Quaternionf izq = haciaY(dir);
                    boolean brilla = random.nextInt(3) == 0;
                    ItemDisplay d = objeto(desde, flecha, new Transformation(new Vector3f(), izq,
                            new Vector3f(esc, esc, esc), punta()), brilla, Display.Billboard.FIXED);
                    if (d == null) continue;
                    int tiempo = (int) Math.max(8, Math.ceil(recorrido.length() / velocidad));
                    caduca(d, tiempo + 12);
                    cayendo.add(new Caida(d, desde, recorrido, t, tiempo));
                }
            }
            Iterator<Caida> it = cayendo.iterator();
            int n = 0;
            while (it.hasNext()) {
                Caida k = it.next();
                int edad = t - k.nace;
                if (!k.lanzada && edad >= 1) {
                    k.lanzada = true;
                    mover(k.d, new Vector3f((float) k.recorrido.getX(), (float) k.recorrido.getY(),
                            (float) k.recorrido.getZ()), k.dura);
                }
                if (edad >= 1 && (n++ % 2 == 0)) {
                    double u = Math.min(1, (edad - 1) / (double) k.dura);
                    Location p = k.desde.clone().add(k.recorrido.clone().multiply(u));
                    polvo(p, (n % 3 == 0) ? BLANCO : CELESTE, 1.2f);
                    if (n % 6 == 0) fx(Compat.END_ROD, p, 1, 0, 0, 0, 0);
                }
                if (edad >= k.dura + 1) {
                    Location g = k.desde.clone().add(k.recorrido);
                    if (random.nextInt(3) == 0) fx(Compat.CRIT, g, 3, 0.15, 0.05, 0.15, 0.1);
                    if (random.nextInt(4) == 0) polvo(g.clone().add(0, 0.2, 0), CELESTE, 1.8f);
                    if (random.nextInt(14) == 0) soundAt(g, "entity.arrow.hit", 0.7f, 0.8f + random.nextFloat() * 0.8f);
                    quitar(k.d);
                    it.remove();
                }
            }
            // El golpe, una vez: a todo el que este fuera de la luz cuando cae la primera tanda.
            if (t == 16) {
                for (Player p : targets()) {
                    if (!enLaPelea(p)) continue;
                    if (enClaro(p.getLocation(), claros)) {
                        p.sendActionBar(Component.text("La luz te ha protegido.", HIELO));
                        continue;
                    }
                    hit(p, D_STAR * damageBonus, m);
                    Location l = p.getLocation().add(0, 1, 0);
                    fx(Compat.FLASH, l, 1, 0, 0, 0, 0, GLOW);
                    fx(Compat.END_ROD, l, 20, 0.4, 0.8, 0.4, 0.12);
                }
                soundAt(c, "block.amethyst_block.break", 2.0f, 0.5f);
                soundAt(c, "block.beacon.deactivate", 1.8f, 1.5f);
                soundAt(c, "entity.illusioner.mirror_move", 1.6f, 0.6f);
                soundAt(c, "item.trident.thunder", 1.2f, 0.9f);
            }
            // Dos golpes menores: solo destello y onda, sin dano.
            if (t == 36 || t == 56) {
                fx(Compat.FLASH, c.clone().add(0, 3, 0), 1, 0, 0, 0, 0, GLOW);
                soundAt(c, "block.amethyst_block.resonate", 1.6f, t == 36 ? 1.2f : 1.5f);
                for (int i = 0; i < 2; i++) {
                    final double r = 6 + i * 8;
                    later(i * 3, () -> Fx.ring(c.clone().add(0, 0.25, 0), r, (int) (r * 3) + 10,
                            q -> polvo(q, BLANCO, 1.6f)));
                }
            }
            if (t % 5 == 0 && t < dura) {
                for (Location cl : claros) {
                    Fx.ring(cl.clone().add(0, 0.15, 0), 2.5, 20, q -> fx(Compat.END_ROD, q, 1, 0, 0, 0, 0));
                    hazDeClaro(cl.clone().add(0, 0.15, 0), t);
                }
            }
        }, () -> {
            for (Caida k : cayendo) quitar(k.d);
            cayendo.clear();
            for (BlockDisplay col : columnas) quitar(col);
            cieloBaseLuego(50);
        });
    }

    // ------------------------------------------------------------- 4. NEBULA

    /** 4. Nébula: niebla celeste en toda la arena; ocho segundos viendo a seis bloques. */
    public void nebula() {
        if (!alive()) return;
        Location c = loc();
        titleNear(Component.text("NÉBULA", ACCENT, TextDecoration.BOLD),
                Component.text("La niebla de las estrellas", HIELO));
        soundAt(c, "entity.illusioner.prepare_blindness", 1.8f, 0.8f);
        soundAt(c, "block.beacon.deactivate", 1.4f, 0.6f);
        List<Player> dentro = new ArrayList<>();
        for (Player p : targets()) {
            if (!enLaPelea(p)) continue;
            dentro.add(p);
            Compat.apply(p, "blindness", 160, 0);
            if (random.nextDouble() < 0.35) {
                Compat.apply(p, "nausea", 160, 0);
                p.sendActionBar(Component.text("Te da vueltas la cabeza.", HIELO));
            }
        }
        animate(160, t -> {
            if (cerrado) throw Stop.now();
            if (t % 4 != 0) return;
            for (Player p : dentro) {
                if (!p.isOnline()) continue;
                Location l = p.getLocation();
                for (int i = 0; i < 4; i++) {
                    polvo(l.clone().add(rnd(6), 0.3 + random.nextDouble() * 2.6, rnd(6)),
                            i % 2 == 0 ? CELESTE : NUBE, 3.0f);
                }
            }
            for (int i = 0; i < 6; i++) polvo(sueloAlAzar(RADIO).add(0, 0.5 + random.nextDouble() * 3, 0), NUBE, 3.2f);
            if (t % 40 == 0) soundAt(c, "block.amethyst_block.resonate", 0.9f, 0.6f);
        }, null);
    }

    // ------------------------------------------------------------- EL CARCAJ

    /**
     * El patron de casi todo el carcaj: tensa mirando a la presa, una linea fina de su color
     * avisa de la trayectoria y suelta. Reparte entre varios para que no sea solo de uno.
     */
    private void carcaj(Tipo tipo, String aviso, int presas, int seguidas, int cadencia, double base, double vel,
                        Consumer<Saeta> ajuste) {
        if (!alive()) return;
        final double m = abilityDamage();
        List<Player> objetivos = pickTargets(presas);
        objetivos.removeIf(p -> !enLaPelea(p));
        if (objetivos.isEmpty()) return;
        apuntado = objetivos.get(0);
        tensar(true);
        announce(Component.text(aviso, TextColor.color(tipo.rgb)));
        soundAt(loc(), "item.crossbow.loading_middle", 1.0f, 1.3f);
        final int tensa = 14;
        animate(tensa + seguidas * cadencia + 2, t -> {
            if (!alive() || cerrado) throw Stop.now();
            if (t < tensa) {
                if (t % 2 == 0) {
                    for (Player p : objetivos) {
                        if (!enLaPelea(p)) continue;
                        Fx.beam(boca(pecho(p)), pecho(p), 0.9, q -> polvo(q, tipo.rgb, 0.7f));
                    }
                }
                fx(tipo.chispa != null ? tipo.chispa : Compat.END_ROD, body().getEyeLocation(), 1, 0.2, 0.2, 0.2, 0.01);
                return;
            }
            int k = t - tensa;
            if (k % cadencia != 0 || k / cadencia >= seguidas) return;
            for (Player p : objetivos) {
                if (!enLaPelea(p)) continue;
                apuntado = p;
                Saeta s = dispararA(p, vel, tipo, base, m);
                if (s != null && ajuste != null) ajuste.accept(s);
            }
            soundAt(loc(), "entity.arrow.shoot", 1.2f, 1.2f + random.nextFloat() * 0.3f);
            soundAt(loc(), "block.amethyst_block.hit", 0.7f, 1.5f);
        }, () -> tensar(false));
    }

    /** Saeta de Plomo: abanico de cinco hacia una presa; Lentitud III cuatro segundos. */
    public void saetaPlomo() {
        if (!alive()) return;
        final double m = abilityDamage();
        Player p = objetivo(48);
        if (p == null) return;
        apuntado = p;
        tensar(true);
        announce(Component.text("Saetas de plomo.", TextColor.color(Tipo.PLOMO.rgb)));
        soundAt(loc(), "item.crossbow.loading_middle", 1.0f, 0.8f);
        animate(16, t -> {
            if (!alive() || cerrado) throw Stop.now();
            if (t % 2 == 0 && enLaPelea(p)) {
                Location b = boca(pecho(p));
                Vector base = pecho(p).toVector().subtract(b.toVector()).normalize();
                for (int i = -2; i <= 2; i++) {
                    Vector d = base.clone().rotateAroundY(Math.toRadians(i * 8));
                    Fx.beam(b, b.clone().add(d.multiply(10)), 1.0, q -> polvo(q, Tipo.PLOMO.rgb, 0.7f));
                }
            }
        }, () -> {
            tensar(false);
            if (!alive() || !enLaPelea(p)) return;
            Location b = boca(pecho(p));
            Vector base = pecho(p).toVector().subtract(b.toVector()).normalize();
            for (int i = -2; i <= 2; i++) {
                disparar(b, base.clone().rotateAroundY(Math.toRadians(i * 8)), V_BASE, Tipo.PLOMO, D_EFECTO, m);
            }
            soundAt(b, "entity.arrow.shoot", 1.3f, 0.8f);
            soundAt(b, "block.anvil.land", 0.4f, 1.8f);
        });
    }

    /** Saeta Quebrantada: tres seguidas a la misma presa; Debilidad II. */
    public void saetaQuebrantada() {
        carcaj(Tipo.QUEBRANTADA, "Saetas quebrantadas.", 1, 3, 5, D_EFECTO, V_BASE, s -> s.rapida = true);
    }

    /** Saeta Venenosa: donde cae, nube de Veneno II. */
    public void saetaVenenosa() {
        carcaj(Tipo.VENENOSA, "Saeta venenosa.", 2, 1, 1, D_EFECTO, V_BASE, null);
    }

    /** Saeta Dañina: Daño instantáneo II y rebota a otros dos. */
    public void saetaDanina() {
        carcaj(Tipo.DANINA, "Saeta dañina.", 1, 1, 1, D_EFECTO_FUERTE, V_BASE, s -> s.rebotes = 2);
    }

    /** Saeta Ingrávida: Levitación dos segundos y una ráfaga que lo remata en el aire. */
    public void saetaIngravida() {
        carcaj(Tipo.INGRAVIDA, "Saeta ingrávida.", 2, 1, 1, D_EFECTO, V_BASE, null);
    }

    /** Saeta Tejedora: efecto Tejedor y telarañas alrededor. */
    public void saetaTejedora() {
        carcaj(Tipo.TEJEDORA, "Saeta tejedora.", 2, 1, 1, D_EFECTO, V_BASE, null);
    }

    /** Saeta Supurante: efecto Supurante. */
    public void saetaSupurante() {
        carcaj(Tipo.SUPURANTE, "Saeta supurante.", 2, 1, 1, D_EFECTO, V_BASE, null);
    }

    /** Saeta Infestada: efecto Infestado. */
    public void saetaInfestada() {
        carcaj(Tipo.INFESTADA, "Saeta infestada.", 2, 1, 1, D_EFECTO, V_BASE, null);
    }

    /** Saeta del Viento: revienta como una carga de viento. */
    public void saetaViento() {
        carcaj(Tipo.VIENTO, "Saeta del viento.", 2, 1, 1, D_EFECTO, 5.0, null);
    }

    /** Saeta Ígnea: fuego y llamas en el suelo. */
    public void saetaIgnea() {
        carcaj(Tipo.IGNEA, "Saeta ígnea.", 2, 1, 1, D_EFECTO_FUERTE, V_BASE, null);
    }

    /** Saeta Marchita: Wither II. */
    public void saetaMarchita() {
        carcaj(Tipo.MARCHITA, "Saeta marchita.", 2, 1, 1, D_EFECTO_FUERTE, V_BASE, null);
    }

    /** Saeta Espectral: brillo diez segundos. */
    public void saetaEspectral() {
        carcaj(Tipo.ESPECTRAL, "Saeta espectral.", 3, 1, 1, D_EFECTO, 5.5, null);
    }

    /** Carcaj Completo: diez flechas de efectos distintos en dos segundos, a varios. */
    public void carcajCompleto() {
        if (!alive()) return;
        final double m = abilityDamage();
        List<Tipo> tipos = new ArrayList<>(List.of(Tipo.PLOMO, Tipo.QUEBRANTADA, Tipo.VENENOSA, Tipo.DANINA,
                Tipo.INGRAVIDA, Tipo.TEJEDORA, Tipo.SUPURANTE, Tipo.INFESTADA, Tipo.VIENTO, Tipo.IGNEA,
                Tipo.MARCHITA, Tipo.ESPECTRAL));
        Collections.shuffle(tipos, random);
        final List<Tipo> diez = tipos.subList(0, 10);
        titleNear(Component.text("CARCAJ COMPLETO", ACCENT, TextDecoration.BOLD),
                Component.text("Diez flechas, diez venenos", HIELO));
        soundAt(loc(), "item.crossbow.loading_end", 1.4f, 0.8f);
        tensar(true);
        animate(48, t -> {
            if (!alive() || cerrado) throw Stop.now();
            if (t < 8) {
                fx(Compat.ENCHANT, body().getEyeLocation(), 6, 0.4, 0.4, 0.4, 0.6);
                return;
            }
            int k = t - 8;
            if (k % 4 != 0 || k / 4 >= diez.size()) return;
            Tipo tipo = diez.get(k / 4);
            List<Player> pool = targets();
            pool.removeIf(p -> !enLaPelea(p));
            if (pool.isEmpty()) return;
            Player p = pool.get((k / 4) % pool.size());
            apuntado = p;
            Saeta s = dispararA(p, 5.0, tipo, D_EFECTO, m);
            if (s != null) {
                s.rapida = true;
                if (tipo == Tipo.DANINA) s.rebotes = 1;
            }
            soundAt(loc(), "entity.arrow.shoot", 1.2f, 1.0f + (k / 4) * 0.08f);
            polvo(body().getEyeLocation().add(0, 0.6, 0), tipo.rgb, 2.0f);
        }, () -> tensar(false));
    }

    // ----------------------------------------------------- CINTURON DE ORION

    /**
     * Una estrella del Cinturón. Exterior: cristal celeste a 1,2 con RY(giro)·RX(45)·RZ(45);
     * interior: farol marino a 0,6 con la rotacion inversa. Las dos centradas.
     */
    static Transformation estrella(boolean exterior, float giro, float k, float osc) {
        float s = (exterior ? 1.2f : 0.6f) * k;
        Quaternionf rot = exterior
                ? new Quaternionf().rotateY(giro).rotateX(R45).rotateZ(R45)
                : new Quaternionf().rotateY(-giro).rotateX(-R45).rotateZ(-R45);
        Vector3f centro = rot.transform(new Vector3f(s / 2f, s / 2f, s / 2f), new Vector3f());
        Vector3f tr = new Vector3f(-centro.x, -centro.y + osc, -centro.z);
        return new Transformation(tr, rot, new Vector3f(s, s, s), new Quaternionf());
    }

    /**
     * Cinturón de Orión (fase II): tres estrellas sobre el centro de la arena barren el suelo
     * con rayos de luz, tres pasadas cada una, desfasadas un cuarto de segundo.
     */
    public void cinturonDeOrion() {
        if (!alive()) return;
        final double m = abilityDamage();
        final Location c = Fx.ground(arena.clone(), 8);
        titleNear(Component.text("CINTURÓN DE ORIÓN", ACCENT, TextDecoration.BOLD),
                Component.text("Tres estrellas, tres rayos. Sal de la línea", HIELO));
        soundAt(c, "block.beacon.power_select", 1.8f, 1.0f);
        soundAt(c, "block.amethyst_block.resonate", 1.6f, 1.2f);
        final double[][] pos = {{-5, 8.6}, {0, 8.0}, {5, 7.4}};
        final Location[] sitio = new Location[3];
        final BlockDisplay[][] estrellas = new BlockDisplay[3][2];
        for (int i = 0; i < 3; i++) {
            sitio[i] = c.clone().add(pos[i][0], pos[i][1], 0);
            estrellas[i][0] = bloque(sitio[i], Material.LIGHT_BLUE_STAINED_GLASS, estrella(true, 0, 0.01f, 0), true);
            estrellas[i][1] = bloque(sitio[i], Material.SEA_LANTERN, estrella(false, 0, 0.01f, 0), false);
            fx(Compat.FLASH, sitio[i], 1, 0, 0, 0, 0, GLOW);
        }
        // Las pasadas: de A a B por el suelo, cruzando por encima de alguien.
        final int rondas = 3, intro = 20, entre = 55, barrido = 36, aviso = 14;
        final Location[][] desde = new Location[rondas][3], hasta = new Location[rondas][3];
        final List<Set<UUID>> tocados = new ArrayList<>();
        for (int r = 0; r < rondas; r++) {
            for (int i = 0; i < 3; i++) {
                tocados.add(new HashSet<>());
            }
        }
        final int total = intro + (rondas - 1) * entre + 2 * 5 + barrido + 12;
        animate(total, t -> {
            if (cerrado) throw Stop.now();
            float k = (float) Math.min(1, t / 10.0);
            if (t > total - 10) k = (float) Math.max(0.01, (total - t) / 10.0);
            float giro = (float) Math.toRadians(3 * t);
            for (int i = 0; i < 3; i++) {
                float osc = (float) (0.15 * Math.sin(t * 0.12 + i * 2.1));
                for (int j = 0; j < 2; j++) {
                    BlockDisplay d = estrellas[i][j];
                    if (d == null || !d.isValid()) continue;
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(1);
                    d.setTransformation(estrella(j == 0, giro, k, osc));
                }
                if (t % 4 == i) fx(Compat.END_ROD, sitio[i], 1, 0.3, 0.3, 0.3, 0.01);
            }
            for (int r = 0; r < rondas; r++) {
                for (int i = 0; i < 3; i++) {
                    int empieza = intro + r * entre + i * 5;
                    int idx = r * 3 + i;
                    if (t == empieza - aviso) {
                        Player p = randomTarget();
                        Location centro = p != null && enLaPelea(p) ? p.getLocation() : sueloAlAzar(RADIO - 4);
                        Vector d = new Vector(1, 0, 0).rotateAroundY(random.nextDouble() * Math.PI * 2).multiply(12);
                        desde[r][i] = Fx.ground(alCirculo(centro.clone().subtract(d), RADIO).add(0, 2, 0), 8);
                        hasta[r][i] = Fx.ground(alCirculo(centro.clone().add(d), RADIO).add(0, 2, 0), 8);
                        soundAt(sitio[i], "block.beacon.ambient", 1.2f, 1.6f);
                    }
                    Location a = desde[r][i], b = hasta[r][i];
                    if (a == null || b == null) continue;
                    if (t >= empieza - aviso && t < empieza && t % 2 == 0) {
                        Fx.beam(a.clone().add(0, 0.15, 0), b.clone().add(0, 0.15, 0), 0.7, q -> polvo(q, CELESTE, 0.8f));
                    }
                    if (t >= empieza && t < empieza + barrido) {
                        double u = (t - empieza) / (double) (barrido - 1);
                        Location punto = a.clone().add(b.toVector().subtract(a.toVector()).multiply(u)).add(0, 0.1, 0);
                        rayo(sitio[i], punto);
                        if (t == empieza) {
                            soundAt(sitio[i], "block.beacon.power_select", 1.2f, 1.8f);
                            soundAt(punto, "entity.guardian.attack", 0.8f, 1.6f);
                        }
                        Set<UUID> ya = tocados.get(idx);
                        for (Player p : Fx.playersNear(punto, 6)) {
                            if (!enLaPelea(p) || ya.contains(p.getUniqueId())) continue;
                            if (enRayo(p, sitio[i], punto)) {
                                ya.add(p.getUniqueId());
                                hit(p, D_AREA * damageBonus, m);
                                Compat.apply(p, "slowness", 30, 1);
                                fx(Compat.FLASH, p.getLocation().add(0, 1, 0), 1, 0, 0, 0, 0, GLOW);
                                soundAt(p.getLocation(), "block.amethyst_block.break", 1.2f, 1.4f);
                            }
                        }
                    }
                }
            }
        }, () -> {
            for (BlockDisplay[] par : estrellas) for (BlockDisplay d : par) quitar(d);
            for (Location s : sitio) {
                fx(Compat.FIREWORK, s, 20, 0.3, 0.3, 0.3, 0.1);
                fx(Compat.FLASH, s, 1, 0, 0, 0, 0, GLOW);
            }
            soundAt(c, "entity.firework_rocket.twinkle", 1.4f, 1.2f);
        });
    }

    /** El rayo: END_ROD cada 0,12 bloques y polvo celeste encima, de la estrella al suelo. */
    private void rayo(Location estrella, Location suelo) {
        Fx.beam(estrella, suelo, 0.12, q -> fx(Compat.END_ROD, q, 1, 0, 0, 0, 0));
        Fx.beam(estrella, suelo, 0.36, q -> polvo(q, CELESTE, 1.3f));
        fx(Compat.END_ROD, suelo, 3, 0.25, 0.05, 0.25, 0.05);
        polvo(suelo.clone().add(0, 0.1, 0), BLANCO, 2.0f);
    }

    /** Si el jugador esta en el rayo: cerca del segmento o encima del punto de impacto. */
    private static boolean enRayo(Player p, Location a, Location b) {
        Location pies = p.getLocation();
        double dx = pies.getX() - b.getX(), dz = pies.getZ() - b.getZ();
        if (dx * dx + dz * dz <= 1.3 * 1.3 && Math.abs(pies.getY() - b.getY()) < 2.5) return true;
        return distSegmento(pies.clone().add(0, 1, 0).toVector(), a.toVector(), b.toVector()) <= 0.9;
    }

    private static double distSegmento(Vector p, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double len2 = ab.lengthSquared();
        if (len2 < 1.0E-6) return p.distance(a);
        double u = Math.max(0, Math.min(1, p.clone().subtract(a).dot(ab) / len2));
        return p.distance(a.clone().add(ab.multiply(u)));
    }

    /** Distancia en horizontal de un punto a un segmento (las lineas del suelo). */
    private static double distSegmentoPlano(Location p, Location a, Location b) {
        Vector pp = new Vector(p.getX(), 0, p.getZ());
        return distSegmento(pp, new Vector(a.getX(), 0, a.getZ()), new Vector(b.getX(), 0, b.getZ()));
    }

    // ------------------------------------------------------------ CONSTELACION

    /** Constelación: une a los jugadores con lineas de luz; al segundo y medio estallan. */
    public void constelacion() {
        if (!alive()) return;
        final double m = abilityDamage();
        List<Player> pool = targets();
        pool.removeIf(p -> !enLaPelea(p));
        if (pool.isEmpty()) return;
        final Location yo = loc().clone();
        if (pool.size() > 6) {
            Collections.shuffle(pool, random);
            pool = pool.subList(0, 6);
        }
        // En orden alrededor de el, para que salga un poligono y no una maraña.
        pool.sort((a, b) -> Double.compare(angulo(yo, a.getLocation()), angulo(yo, b.getLocation())));
        final List<Location> nodos = new ArrayList<>();
        for (Player p : pool) nodos.add(p.getLocation().clone().add(0, 0.3, 0));
        final List<Location[]> lineas = new ArrayList<>();
        if (nodos.size() == 1) {
            Location solo = nodos.get(0);
            Vector d = solo.toVector().subtract(yo.toVector()).setY(0);
            if (d.lengthSquared() < 0.01) d = new Vector(1, 0, 0);
            Location fin = alCirculo(solo.clone().add(d.normalize().multiply(12)), RADIO);
            lineas.add(new Location[]{yo.clone().add(0, 0.3, 0), fin});
        } else {
            for (int i = 0; i < nodos.size(); i++) {
                if (nodos.size() == 2 && i == 1) break;
                lineas.add(new Location[]{nodos.get(i), nodos.get((i + 1) % nodos.size())});
            }
        }
        announce(Component.text("Constelación: sal de las líneas.", HIELO));
        soundAt(yo, "block.amethyst_block.resonate", 1.4f, 1.4f);
        soundAt(yo, "entity.illusioner.cast_spell", 1.0f, 1.6f);
        apuntado = null;
        miraFija = null;
        pitchFijo = -35f;
        tensar(true);
        animate(42, t -> {
            if (cerrado) throw Stop.now();
            if (t < 30) {
                if (t % 2 == 0) {
                    float tam = 0.7f + t * 0.03f;
                    for (Location[] l : lineas) Fx.beam(l[0], l[1], 0.45, q -> polvo(q, t % 4 == 0 ? CELESTE : NUBE, tam));
                    for (Location n : nodos) {
                        fx(Compat.END_ROD, n.clone().add(0, 0.4, 0), 1, 0.05, 0.05, 0.05, 0.0);
                        polvo(n.clone().add(0, 0.4, 0), BLANCO, 2.2f);
                    }
                }
                if (t % 10 == 0) soundAt(yo, "block.amethyst_block.chime", 1.2f, 1.0f + t * 0.03f);
                return;
            }
            if (t == 30) {
                pitchFijo = null;
                tensar(false);
                for (Location[] l : lineas) {
                    Fx.beam(l[0], l[1], 0.3, q -> {
                        fx(Compat.END_ROD, q, 1, 0.05, 0.1, 0.05, 0.04);
                        polvo(q, BLANCO, 1.8f);
                    });
                }
                for (Location n : nodos) {
                    fx(Compat.FLASH, n.clone().add(0, 0.6, 0), 1, 0, 0, 0, 0, GLOW);
                    fx(Compat.FIREWORK, n.clone().add(0, 0.6, 0), 14, 0.3, 0.3, 0.3, 0.1);
                }
                soundAt(yo, "entity.firework_rocket.twinkle", 1.6f, 1.0f);
                soundAt(yo, "block.amethyst_block.break", 1.6f, 0.9f);
                for (Player p : targets()) {
                    if (!enLaPelea(p)) continue;
                    for (Location[] l : lineas) {
                        if (distSegmentoPlano(p.getLocation(), l[0], l[1]) <= 1.1
                                && Math.abs(p.getLocation().getY() - l[0].getY()) < 2.5) {
                            hit(p, D_AREA * damageBonus, m);
                            fx(Compat.END_ROD, p.getLocation().add(0, 1, 0), 14, 0.3, 0.6, 0.3, 0.1);
                            break;
                        }
                    }
                }
            }
        }, () -> {
            pitchFijo = null;
            tensar(false);
        });
    }

    private static double angulo(Location centro, Location p) {
        return Math.atan2(p.getZ() - centro.getZ(), p.getX() - centro.getX());
    }

    // --------------------------------------------------------- ABANICO CELESTE

    /** Abanico Celeste: quince flechas en 120 grados a velocidad 5. */
    public void abanicoCeleste() {
        if (!alive()) return;
        final double m = abilityDamage();
        Player p = objetivo(48);
        if (p == null) return;
        apuntado = p;
        tensar(true);
        announce(Component.text("Abanico celeste.", HIELO));
        soundAt(loc(), "item.crossbow.loading_middle", 1.2f, 1.0f);
        final Vector[] eje = {null};
        animate(18, t -> {
            if (!alive() || cerrado) throw Stop.now();
            if (enLaPelea(p)) {
                Location b = boca(pecho(p));
                eje[0] = pecho(p).toVector().subtract(b.toVector()).setY(0);
            }
            if (eje[0] == null || eje[0].lengthSquared() < 0.01) return;
            if (t % 2 == 0) {
                Vector e = eje[0].clone().normalize();
                Location base = body().getLocation().add(0, 0.2, 0);
                for (int i = 0; i <= 14; i++) {
                    Vector d = e.clone().rotateAroundY(Math.toRadians(-60 + i * (120.0 / 14)));
                    for (double r = 2; r <= 6; r += 1.0) polvo(base.clone().add(d.clone().multiply(r)), i % 2 == 0 ? CELESTE : AZUL, 0.9f);
                }
            }
        }, () -> {
            tensar(false);
            if (!alive() || eje[0] == null || eje[0].lengthSquared() < 0.01) return;
            Location b = boca(pecho(p));
            Vector e = enLaPelea(p) ? pecho(p).toVector().subtract(b.toVector()).normalize() : eje[0].clone().normalize();
            for (int i = 0; i <= 14; i++) {
                disparar(b, e.clone().rotateAroundY(Math.toRadians(-60 + i * (120.0 / 14))), 5.0, Tipo.ESTELAR, D_EFECTO, m);
            }
            soundAt(b, "entity.arrow.shoot", 1.5f, 0.9f);
            soundAt(b, "entity.firework_rocket.launch", 1.0f, 1.4f);
            fx(Compat.FLASH, b, 1, 0, 0, 0, 0, GLOW);
        });
    }

    // ----------------------------------------------------------- SUPERNOVA

    /** Supernova (fase III): la luz se le mete dentro y sale en 64 flechas a todos lados. */
    public void supernova() {
        if (!alive()) return;
        final double m = abilityDamage();
        final Location c = boss.getLocation().clone().add(0, 1.2, 0);
        try {
            if (boss instanceof Mob mob) mob.getPathfinder().stopPathfinding();
        } catch (Throwable ignored) {
        }
        titleNear(Component.text("SUPERNOVA", ACCENT, TextDecoration.BOLD),
                Component.text("Busca el hueco entre los rayos", HIELO));
        soundAt(c, "block.respawn_anchor.charge", 1.6f, 0.7f);
        soundAt(c, "block.beacon.power_select", 1.4f, 0.6f);
        apuntado = null;
        pitchFijo = -20f;
        tensar(true);
        final double desfase = random.nextDouble() * Math.PI * 2;
        animate(46, t -> {
            if (!alive() || cerrado) throw Stop.now();
            double r = 9 * (1 - t / 46.0) + 0.4;
            Fx.sphere(c, r, 28, q -> polvo(q, t % 2 == 0 ? AZUL : CELESTE, 1.5f));
            if (t % 3 == 0) Fx.sphere(c, r * 0.8, 10, q -> fx(Compat.END_ROD, q, 1, 0, 0, 0, 0));
            if (t % 4 == 0) {
                // Los radios por donde saldran: el hueco esta entre dos.
                for (int i = 0; i < 32; i++) {
                    double a = desfase + i * Math.PI * 2 / 32;
                    Vector d = new Vector(Math.cos(a), 0, Math.sin(a));
                    for (double k = 2; k <= 9; k += 1.4) {
                        polvo(Fx.ground(c.clone().add(d.clone().multiply(k)), 4).add(0, 0.15, 0), CELESTE, 0.8f);
                    }
                }
            }
            if (t % 8 == 0) soundAt(c, "block.amethyst_block.chime", 1.6f, 0.5f + t * 0.03f);
        }, () -> {
            pitchFijo = null;
            tensar(false);
            if (!alive()) return;
            fx(Compat.FLASH, c, 1, 0, 0, 0, 0, GLOW);
            fx(Compat.EXPLOSION_EMITTER, c, 1, 0, 0, 0, 0);
            fx(Compat.END_ROD, c, 80, 0.5, 0.5, 0.5, 0.45);
            soundAt(c, "entity.generic.explode", 1.2f, 1.4f);
            soundAt(c, "block.amethyst_block.resonate", 2.0f, 0.6f);
            soundAt(c, "item.trident.thunder", 1.2f, 1.1f);
            for (int ring = 0; ring < 2; ring++) {
                double y = ring == 0 ? -0.2 : 0.6;
                for (int i = 0; i < 32; i++) {
                    double a = desfase + (i + ring * 0.5) * Math.PI * 2 / 32;
                    Vector d = new Vector(Math.cos(a), 0, Math.sin(a));
                    disparar(c.clone().add(0, y, 0).add(d.clone().multiply(1.2)), d, V_BASE, Tipo.ESTELAR, D_EFECTO_MAX, m);
                }
            }
        });
    }

    // ---------------------------------------------------------- LLUVIA FUGAZ

    /** Lluvia Fugaz: seis segundos de estrellas que caen sobre marcas en el suelo, r3. */
    public void lluviaFugaz() {
        if (!alive()) return;
        final double m = abilityDamage();
        announce(Component.text("Lluvia fugaz: mira al suelo.", HIELO));
        soundAt(loc(), "entity.firework_rocket.launch", 1.4f, 0.6f);
        apuntado = null;
        pitchFijo = -60f;
        tensar(true);
        animate(122, t -> {
            if (!alive() || cerrado) throw Stop.now();
            if (t % 10 != 0 || t > 100) return;
            List<Player> pool = targets();
            pool.removeIf(p -> !enLaPelea(p));
            for (int i = 0; i < 2; i++) {
                Location marca;
                if (!pool.isEmpty() && (i == 0 || random.nextBoolean())) {
                    marca = Fx.ground(pool.get(random.nextInt(pool.size())).getLocation(), 6);
                } else {
                    marca = sueloAlAzar(RADIO - 2);
                }
                estrellaFugaz(marca, m);
            }
        }, () -> {
            pitchFijo = null;
            tensar(false);
        });
    }

    private void estrellaFugaz(Location marca, double m) {
        final Location g = marca.clone();
        final int aviso = 20, cae = 6;
        final Vector desde = new Vector(rnd(1) * 10, 26, rnd(1) * 10);
        final ItemDisplay[] estrella = {null};
        animate(aviso + cae + 2, t -> {
            if (cerrado) throw Stop.now();
            if (t < aviso) {
                if (t % 2 == 0) {
                    Fx.ring(g.clone().add(0, 0.15, 0), 3.0, 24, q -> polvo(Fx.ground(q, 3).add(0, 0.15, 0), CELESTE, 1.2f));
                    Fx.ring(g.clone().add(0, 0.15, 0), 3.0 * t / aviso, 14, q -> polvo(q, BLANCO, 1.0f));
                }
                if (t == aviso - cae - 2) {
                    estrella[0] = objeto(g.clone().add(desde), new ItemStack(Material.NETHER_STAR),
                            new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.6f, 1.6f, 1.6f), new Quaternionf()),
                            true, Display.Billboard.CENTER);
                    if (estrella[0] != null) {
                        estrella[0].setTeleportDuration(cae);
                        caduca(estrella[0], 30);
                    }
                }
                if (t == aviso - cae && estrella[0] != null) estrella[0].teleport(g.clone().add(0, 0.8, 0));
                if (t >= aviso - cae) {
                    double u = (t - (aviso - cae)) / (double) cae;
                    Location p = g.clone().add(desde.clone().multiply(1 - u));
                    fx(Compat.END_ROD, p, 2, 0.1, 0.1, 0.1, 0.01);
                    fx(Compat.FIREWORK, p, 1, 0.1, 0.1, 0.1, 0.01);
                    polvo(p, CELESTE, 2.0f);
                }
                return;
            }
            if (t == aviso) {
                if (estrella[0] != null) quitar(estrella[0]);
                Location l = g.clone().add(0, 0.5, 0);
                fx(Compat.FLASH, l, 1, 0, 0, 0, 0, GLOW);
                fx(Compat.FIREWORK, l, 26, 0.6, 0.4, 0.6, 0.15);
                fx(Compat.END_ROD, l, 14, 0.8, 0.3, 0.8, 0.08);
                soundAt(l, "entity.firework_rocket.blast", 1.4f, 0.9f);
                soundAt(l, "block.amethyst_block.break", 1.2f, 1.2f);
                for (Player p : Fx.playersNear(g, 3.0)) {
                    if (!enLaPelea(p)) continue;
                    hit(p, D_EFECTO_MAX * damageBonus, m);
                }
            }
        }, () -> {
            if (estrella[0] != null) quitar(estrella[0]);
        });
    }

    // ---------------------------------------------------------- LANZA DE RIGEL

    /** Una pieza de la Lanza de Rigel con su transformacion de las muestras. */
    private record PiezaLanza(Material mat, float[] tr, float[] escala, boolean giroY) {
        Transformation tr(float k) {
            Quaternionf izq = giroY ? new Quaternionf().rotateY(R90) : new Quaternionf();
            Quaternionf der = mat == Material.DIAMOND_BLOCK ? new Quaternionf().rotateZ(R45) : new Quaternionf();
            return new Transformation(new Vector3f(tr[0] * k, tr[1] * k, tr[2] * k), izq,
                    new Vector3f(escala[0] * k, escala[1] * k, escala[2] * k), der);
        }
    }

    /** El centro aproximado de una pieza ya clavada (para el destello con el que se evapora). */
    private static Location centroDePieza(Location clavada, PiezaLanza p) {
        float[] t = p.tr(), e = p.escala();
        // Girada 90 grados en Y, la caja (x, y, z) queda como (z, y, -x).
        double cx = p.giroY() ? e[2] / 2 : e[0] / 2, cz = p.giroY() ? -e[0] / 2 : e[2] / 2;
        return clavada.clone().add(t[0] + cx, t[1] + e[1] / 2, t[2] + cz);
    }

    /** Cinco bloques, origen en la punta y apuntando abajo, todos con brillo celeste. */
    private static final List<PiezaLanza> LANZA = List.of(
            new PiezaLanza(Material.DIAMOND_BLOCK, new float[]{0f, 1.039f, -0.125f}, new float[]{0.9f, 1.5f, 0.25f}, false),
            new PiezaLanza(Material.DIAMOND_BLOCK, new float[]{-0.125f, 1.039f, 0f}, new float[]{0.9f, 1.5f, 0.25f}, true),
            new PiezaLanza(Material.LIGHT_BLUE_CONCRETE, new float[]{-0.125f, 1.6f, -0.125f}, new float[]{0.25f, 6f, 0.25f}, false),
            new PiezaLanza(Material.WHITE_WOOL, new float[]{-0.6f, 5.9f, -0.04f}, new float[]{1.2f, 1.7f, 0.08f}, false),
            new PiezaLanza(Material.LIGHT_BLUE_WOOL, new float[]{-0.04f, 5.9f, -0.6f}, new float[]{0.08f, 1.7f, 1.2f}, false));

    /**
     * Lanza de Rigel (fase II): una flecha gigante aparece sobre el que mas vida tiene. Dos
     * segundos de aviso (circulo, cruz y columna), cae de 34 bloques en un cuarto de segundo, se
     * clava y suelta tres anillos de onda: 26 en el centro (r4) y 10 hasta r9.
     */
    public void lanzaDeRigel() {
        if (!alive()) return;
        final double m = abilityDamage();
        Player victima = null;
        for (Player p : targets()) {
            if (!enLaPelea(p)) continue;
            if (victima == null || p.getHealth() > victima.getHealth()) victima = p;
        }
        if (victima == null) return;
        final Location g = Fx.ground(victima.getLocation(), 6);
        titleNear(Component.text("LANZA DE RIGEL", ACCENT, TextDecoration.BOLD),
                Component.text("Sobre " + victima.getName() + ". ¡Apartaos!", HIELO));
        soundAt(g, "block.beacon.activate", 1.6f, 0.6f);
        soundAt(g, "block.respawn_anchor.charge", 1.4f, 1.2f);
        apuntado = null;
        miraFija = g.clone().add(0, 30, 0);
        tensar(true);
        // La punta queda 0,6 por debajo del suelo: clavada.
        final Location clavada = g.clone().add(0, -1.039 - 0.6, 0);
        final int aviso = 40, cae = 5;
        final List<BlockDisplay> piezas = new ArrayList<>();
        final List<PiezaLanza> deQue = new ArrayList<>();
        for (PiezaLanza p : LANZA) {
            BlockDisplay d = bloque(clavada.clone().add(0, 34, 0), p.mat(), p.tr(1f), true);
            if (d == null) continue;
            deQue.add(p);
            // La duracion del viaje va desde ya: si viaja en el mismo tick que el salto, el
            // cliente puede recibir el salto antes y la lanza aparece clavada sin caer.
            d.setTeleportDuration(cae);
            piezas.add(d);
        }
        animate(aviso + cae + 32, t -> {
            if (cerrado) throw Stop.now();
            if (t < aviso) {
                if (t % 2 == 0) {
                    Location b = g.clone().add(0, 0.15, 0);
                    Fx.ring(b, 3.0, 28, q -> polvo(Fx.ground(q, 3).add(0, 0.15, 0), CELESTE, 1.5f));
                    Fx.ring(b, 9.0, 40, q -> polvo(Fx.ground(q, 3).add(0, 0.15, 0), AZUL, 1.0f));
                    Fx.beam(b.clone().add(-3, 0, 0), b.clone().add(3, 0, 0), 0.4, q -> polvo(q, BLANCO, 1.0f));
                    Fx.beam(b.clone().add(0, 0, -3), b.clone().add(0, 0, 3), 0.4, q -> polvo(q, BLANCO, 1.0f));
                }
                if (t % 3 == 0) {
                    for (double y = 1; y < 32; y += 2.5) fx(Compat.END_ROD, g.clone().add(0, y, 0), 1, 0.05, 0.05, 0.05, 0.0);
                }
                if (t % 10 == 0) soundAt(g, "block.amethyst_block.chime", 1.4f, 0.6f + t * 0.03f);
                if (t == aviso - 1) {
                    soundAt(g, "item.trident.riptide_3", 1.6f, 0.8f);
                    for (BlockDisplay d : piezas) {
                        if (d.isValid()) d.teleport(clavada);
                    }
                }
                return;
            }
            if (t == aviso + cae) {
                tensar(false);
                miraFija = null;
                Location l = g.clone().add(0, 0.3, 0);
                fx(Compat.FLASH, l, 1, 0, 0, 0, 0, GLOW);
                fx(Compat.EXPLOSION_EMITTER, l, 1, 0, 0, 0, 0);
                fx(Compat.BLOCK, l, 60, 1.6, 0.3, 1.6, 0.1, Material.DIAMOND_BLOCK.createBlockData());
                fx(Compat.END_ROD, l, 50, 1.0, 0.6, 1.0, 0.25);
                soundAt(l, "item.mace.smash_ground_heavy", 1.6f, 0.7f);
                soundAt(l, "block.anvil.land", 1.0f, 0.6f);
                soundAt(l, "item.trident.thunder", 1.4f, 1.0f);
                soundAt(l, "block.amethyst_block.resonate", 1.8f, 0.7f);
                for (Player p : Fx.playersNear(g, 9.0)) {
                    if (!enLaPelea(p)) continue;
                    double dx = p.getLocation().getX() - g.getX(), dz = p.getLocation().getZ() - g.getZ();
                    double d = Math.sqrt(dx * dx + dz * dz);
                    hit(p, (d <= 4 ? D_RIGEL : D_RIGEL_BORDE) * damageBonus, m);
                    Vector away = new Vector(dx, 0, dz);
                    if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
                    push(p, away.normalize().multiply(d <= 4 ? 1.1 : 0.6).setY(0.5));
                }
            }
            int k = t - (aviso + cae);
            if (k >= 0 && k <= 18) {
                for (int anillo = 0; anillo < 3; anillo++) {
                    int edad = k - anillo * 3;
                    if (edad < 0 || edad > 12) continue;
                    double r = 15.0 * edad / 12.0;
                    final int rgb = anillo == 1 ? AZUL : CELESTE;
                    Fx.ring(g.clone().add(0, 0.2, 0), Math.max(0.5, r), (int) Math.max(12, Math.min(70, r * 4.5)),
                            q -> polvo(Fx.ground(q, 3).add(0, 0.25, 0), rgb, 1.7f));
                }
            }
            // Se evapora como las espadas del Juicio de Espadas (RIP), pero la lanza ENTERA de
            // golpe (Dosa: «que todo desaparezca de golpe»): el fogonazo grande en cada pieza
            // a la vez y todas fuera en el mismo tick.
            if (k == 14) {
                for (int i = 0; i < piezas.size(); i++) {
                    BlockDisplay d = piezas.get(i);
                    net.ederus.edm.rip.EffectRunner.evaporar(world(), centroDePieza(clavada, deQue.get(i)), true);
                    quitar(d);
                }
            }
            if (k > 14 && k % 3 == 0) fx(Compat.END_ROD, g.clone().add(0, 3, 0), 3, 0.3, 1.5, 0.3, 0.03);
        }, () -> {
            miraFija = null;
            tensar(false);
            for (BlockDisplay d : piezas) {
                if (d.isValid()) fx(Compat.POOF, d.getLocation().add(0, 3, 0), 6, 0.2, 0.4, 0.2, 0.02);
                quitar(d);
            }
        });
    }

    // --------------------------------------------------------- GRILLA ESTELAR

    /**
     * Grilla Estelar (fase III): una cuadricula de lineas de luz en el suelo que se enciende por
     * turnos. Cada turno avisa un segundo y se enciende; el hueco esta entre lineas.
     */
    public void grillaEstelar() {
        if (!alive()) return;
        final double m = abilityDamage();
        final Location c = Fx.ground(arena.clone(), 8).add(0, 0.15, 0);
        titleNear(Component.text("GRILLA ESTELAR", ACCENT, TextDecoration.BOLD),
                Component.text("Pisa entre las líneas", HIELO));
        soundAt(c, "block.beacon.power_select", 1.6f, 1.4f);
        // Cuatro turnos: X pares, Z pares, X impares, Z impares (lineas a 14 bloques: hay hueco).
        final double[][] turnos = {{-21, -7, 7, 21}, {-21, -7, 7, 21}, {-14, 0, 14}, {-14, 0, 14}};
        final boolean[] enX = {true, false, true, false};
        final int aviso = 22, luz = 6, turno = aviso + luz;
        animate(turnos.length * turno + 2, t -> {
            if (cerrado) throw Stop.now();
            int n = t / turno;
            if (n >= turnos.length) return;
            int k = t % turno;
            List<Location[]> lineas = new ArrayList<>();
            for (double off : turnos[n]) {
                double largo = Math.sqrt(Math.max(0, RADIO * RADIO - off * off));
                if (largo < 2) continue;
                lineas.add(enX[n]
                        ? new Location[]{c.clone().add(off, 0, -largo), c.clone().add(off, 0, largo)}
                        : new Location[]{c.clone().add(-largo, 0, off), c.clone().add(largo, 0, off)});
            }
            if (k < aviso) {
                if (k % 3 == 0) for (Location[] l : lineas) Fx.beam(l[0], l[1], 0.8, q -> polvo(q, AZUL, 0.9f));
                if (k == 0) soundAt(c, "block.beacon.ambient", 1.4f, 1.2f + n * 0.15f);
                return;
            }
            for (Location[] l : lineas) {
                Fx.beam(l[0], l[1], 0.5, q -> {
                    fx(Compat.END_ROD, q, 1, 0, 0.05, 0, 0.0);
                    polvo(q.clone().add(0, 0.2, 0), BLANCO, 1.5f);
                });
            }
            if (k == aviso) {
                soundAt(c, "block.amethyst_block.break", 1.8f, 1.0f + n * 0.1f);
                soundAt(c, "entity.guardian.attack", 1.0f, 1.8f);
                for (Player p : targets()) {
                    if (!enLaPelea(p) || Math.abs(p.getLocation().getY() - c.getY()) > 3) continue;
                    for (Location[] l : lineas) {
                        if (distSegmentoPlano(p.getLocation(), l[0], l[1]) <= 1.0) {
                            hit(p, D_AREA * damageBonus, m);
                            fx(Compat.FLASH, p.getLocation().add(0, 1, 0), 1, 0, 0, 0, 0, GLOW);
                            break;
                        }
                    }
                }
            }
        }, null);
    }

    // ------------------------------------------------------ ANCLA GRAVITATORIA

    /** Ancla Gravitatoria (fase II): arrastra a todos a un punto y suelta una andanada encima. */
    public void anclaGravitatoria() {
        if (!alive()) return;
        final double m = abilityDamage();
        List<Player> pool = targets();
        pool.removeIf(p -> !enLaPelea(p));
        if (pool.isEmpty()) return;
        double sx = 0, sz = 0;
        for (Player p : pool) {
            sx += p.getLocation().getX();
            sz += p.getLocation().getZ();
        }
        final Location ancla = Fx.ground(alCirculo(new Location(world(), sx / pool.size(), loc().getY() + 2,
                sz / pool.size()), RADIO - 4), 8);
        titleNear(Component.text("ANCLA GRAVITATORIA", ACCENT, TextDecoration.BOLD),
                Component.text("Resiste el tirón", HIELO));
        soundAt(ancla, "entity.warden.sonic_charge", 1.6f, 1.4f);
        soundAt(ancla, "block.beacon.activate", 1.4f, 0.5f);
        miraFija = ancla.clone();
        apuntado = null;
        tensar(true);
        animate(72, t -> {
            if (!enCombate()) throw Stop.now();
            if (t < 50) {
                double giro = t * 0.4;
                for (int i = 0; i < 6; i++) {
                    double a = giro + i * Math.PI / 3, r = 8 - (t % 16) * 0.45;
                    Location q = ancla.clone().add(Math.cos(a) * r, 0.3 + (i % 2) * 0.6, Math.sin(a) * r);
                    polvo(q, i % 2 == 0 ? AZUL : CELESTE, 1.6f);
                    fx(Compat.REVERSE_PORTAL, q, 1, 0, 0, 0, 0.02);
                }
                if (t % 2 == 0) {
                    Fx.ring(ancla.clone().add(0, 0.15, 0), 4.5, 26, q -> polvo(q, BLANCO, 1.0f));
                }
                if (t >= 8 && t % 2 == 0) {
                    for (Player p : Fx.playersNear(ancla, 24)) {
                        if (!enLaPelea(p)) continue;
                        Vector hacia = ancla.toVector().subtract(p.getLocation().toVector()).setY(0);
                        double d = hacia.length();
                        if (d < 0.8) continue;
                        push(p, hacia.normalize().multiply(Math.min(0.32, 0.12 + d * 0.012)));
                    }
                }
                if (t % 10 == 0) soundAt(ancla, "block.beacon.ambient", 1.4f, 0.6f + t * 0.02f);
                return;
            }
            if (t == 50) {
                tensar(false);
                // La andanada: doce flechas desde el arco hacia el ancla.
                for (int i = 0; i < 12; i++) {
                    final int n = i;
                    later(n, () -> {
                        if (!alive()) return;
                        Location dst = ancla.clone().add(rnd(3), 0.3, rnd(3));
                        Location b = boca(dst);
                        disparar(b, dst.toVector().subtract(b.toVector()), V_BASE, Tipo.ESTELAR, D_BASICO, m);
                        if (n % 3 == 0) soundAt(b, "entity.arrow.shoot", 1.2f, 1.1f);
                    });
                }
            }
            if (t == 64) {
                Location l = ancla.clone().add(0, 0.5, 0);
                fx(Compat.FLASH, l, 1, 0, 0, 0, 0, GLOW);
                fx(Compat.END_ROD, l, 50, 1.4, 0.4, 1.4, 0.2);
                soundAt(l, "entity.generic.explode", 1.0f, 1.6f);
                soundAt(l, "block.amethyst_block.break", 1.6f, 0.8f);
                for (Player p : Fx.playersNear(ancla, 4.5)) {
                    if (enLaPelea(p)) hit(p, D_AREA_MAX * damageBonus, m);
                }
                for (int i = 0; i < 2; i++) {
                    final double r = 2.5 + i * 2.5;
                    later(i * 2, () -> Fx.ring(ancla.clone().add(0, 0.2, 0), r, (int) (r * 8) + 8,
                            q -> polvo(q, CELESTE, 1.6f)));
                }
            }
        }, () -> {
            miraFija = null;
            tensar(false);
        });
    }

    // -------------------------------------------------------------- SAGITARIO

    /** Sagitario (fase III): dos arqueros de luz disparan ocho segundos desde los bordes. */
    public void sagitario() {
        if (!alive() || perfil == null) return;
        final double m = abilityDamage();
        Location yo = loc();
        Vector eje = new Vector(1, 0, 0);
        Player p0 = objetivo(60);
        if (p0 != null) {
            Vector d = p0.getLocation().toVector().subtract(yo.toVector()).setY(0);
            if (d.lengthSquared() > 0.01) eje = d.normalize().rotateAroundY(Math.PI / 2);
        }
        titleNear(Component.text("SAGITARIO", ACCENT, TextDecoration.BOLD),
                Component.text("Dos arqueros más desde los bordes", HIELO));
        soundAt(yo, "entity.illusioner.cast_spell", 1.6f, 1.2f);
        soundAt(yo, "block.beacon.power_select", 1.4f, 1.6f);
        List<Mannequin> arqueros = new ArrayList<>();
        for (int lado = -1; lado <= 1; lado += 2) {
            Location sitio = Fx.ground(arena.clone().add(eje.clone().multiply(lado * 22)).add(0, 2, 0), 10);
            if (!dentroDeArena(sitio) || !dePie(sitio)) sitio = puntoDeArena(arena, 16, RADIO - 2);
            if (sitio == null) continue;
            Mannequin a = arqueroDeLuz(sitio);
            if (a != null) arqueros.add(a);
        }
        if (arqueros.isEmpty()) return;
        final long[] proximo = {20, 27};
        animate(170, t -> {
            if (cerrado) throw Stop.now();
            for (int i = 0; i < arqueros.size(); i++) {
                Mannequin a = arqueros.get(i);
                if (!a.isValid()) continue;
                Location l = a.getLocation();
                if (t % 4 == i * 2) {
                    double giro = t * 0.3 + i * Math.PI;
                    for (int k = 0; k < 2; k++) {
                        double b = giro + k * Math.PI;
                        fx(Compat.END_ROD, l.clone().add(Math.cos(b) * 0.7, 0.2 + (t % 20) * 0.1, Math.sin(b) * 0.7), 1, 0, 0, 0, 0);
                    }
                    polvo(l.clone().add(0, 1.0, 0), CELESTE, 1.4f);
                }
                Player p = Fx.nearest(l, 60);
                if (p == null || !enLaPelea(p)) continue;
                Vector d = pecho(p).toVector().subtract(a.getEyeLocation().toVector());
                try {
                    a.setRotation(yawDe(d), (float) -Math.toDegrees(Math.atan2(d.getY(), Math.hypot(d.getX(), d.getZ()))));
                    a.setBodyYaw(yawDe(d));
                } catch (Throwable ignored) {
                }
                if (t >= 150) continue;
                if (t == proximo[i] - 8) tensarCuerpo(a, true);
                if (t >= proximo[i]) {
                    proximo[i] = t + 14;
                    List<Player> pool = targets();
                    pool.removeIf(q -> !enLaPelea(q));
                    Player q = pool.isEmpty() ? p : pool.get(random.nextInt(pool.size()));
                    Location boca = a.getEyeLocation().subtract(0, 0.25, 0)
                            .add(pecho(q).toVector().subtract(a.getEyeLocation().toVector()).normalize().multiply(1.2));
                    disparar(boca, pecho(q).toVector().subtract(boca.toVector()), V_BASE, Tipo.ESTELAR, D_EFECTO * 0.85, m);
                    soundAt(boca, "entity.arrow.shoot", 1.1f, 1.6f);
                    tensarCuerpo(a, false);
                }
            }
        }, () -> {
            for (Mannequin a : arqueros) {
                if (a.isValid()) {
                    Location l = a.getLocation().add(0, 1, 0);
                    fx(Compat.FLASH, l, 1, 0, 0, 0, 0, GLOW);
                    fx(Compat.END_ROD, l, 30, 0.3, 0.8, 0.3, 0.12);
                    soundAt(l, "block.amethyst_block.break", 1.2f, 1.4f);
                    Glow.clear(a);
                }
                quitar(a);
            }
        });
    }

    private Mannequin arqueroDeLuz(Location sitio) {
        try {
            Location l = sitio.clone();
            Mannequin a = world().spawn(l, Mannequin.class, x -> {
                x.setProfile(perfil);
                x.setPersistent(false);
                x.setGravity(false);
                x.setCollidable(false);
                x.setSilent(true);
                x.setInvulnerable(true);
                x.setImmovable(true);
                x.customName(nombre());
                x.setCustomNameVisible(true);
                try {
                    x.setDescription(Component.empty());
                } catch (Throwable ignored) {
                }
            });
            Compat.setAttribute(a, "scale", ESCALA);
            dePieSiempre(a);
            vestir(a);
            markMinion(a);
            Glow.apply(a, NamedTextColor.AQUA);
            later(2, () -> {
                if (a.isValid()) a.setProfile(perfil);
            });
            fx(Compat.FLASH, l.clone().add(0, 1, 0), 1, 0, 0, 0, 0, GLOW);
            for (double y = 0; y < 12; y += 1.5) fx(Compat.END_ROD, l.clone().add(0, y, 0), 2, 0.1, 0.3, 0.1, 0.01);
            soundAt(l, "block.amethyst_block.resonate", 1.4f, 1.3f);
            return a;
        } catch (Throwable t) {
            return null;
        }
    }

    // --------------------------------------------------------------- NEBULOSA

    /** Nebulosa (fase II): tres nubes que persiguen, ralentizan y queman cuatro veces por segundo. */
    public void nebulosa() {
        if (!alive()) return;
        final double m = abilityDamage();
        List<Player> pool = pickTargets(3);
        List<Location> nubes = new ArrayList<>();
        for (Player p : pool) if (enLaPelea(p)) nubes.add(Fx.ground(p.getLocation(), 6).add(0, 0.2, 0));
        while (nubes.size() < 3) nubes.add(sueloAlAzar(RADIO - 4).add(0, 0.2, 0));
        announce(Component.text("Nebulosa: que no te alcance la nube.", HIELO));
        soundAt(loc(), "entity.illusioner.prepare_blindness", 1.4f, 1.3f);
        soundAt(loc(), "block.fire.extinguish", 1.0f, 0.6f);
        animate(170, t -> {
            if (!enCombate()) throw Stop.now();
            for (int i = 0; i < nubes.size(); i++) {
                Location n = nubes.get(i);
                if (t >= 10) {
                    Player cerca = Fx.nearest(n, 30);
                    if (cerca != null && enLaPelea(cerca)) {
                        Vector d = cerca.getLocation().toVector().subtract(n.toVector()).setY(0);
                        if (d.lengthSquared() > 0.04) {
                            Location sig = n.clone().add(d.normalize().multiply(0.085));
                            if (dentroDeArena(sig)) {
                                n.setX(sig.getX());
                                n.setZ(sig.getZ());
                                n.setY(Fx.ground(sig.clone().add(0, 1, 0), 4).getY() + 0.2);
                            }
                        }
                    }
                }
                if (t % 2 == 0) {
                    for (int k = 0; k < 9; k++) {
                        double a = random.nextDouble() * Math.PI * 2, r = Math.sqrt(random.nextDouble()) * 3.5;
                        int rgb = k % 3 == 0 ? VIOLETA : (k % 3 == 1 ? AZUL : CELESTE);
                        polvo(n.clone().add(Math.cos(a) * r, random.nextDouble() * 2.2, Math.sin(a) * r), rgb, 2.4f);
                    }
                    if (t % 6 == 0) fx(Compat.SOUL_FIRE_FLAME, n, 3, 1.6, 0.3, 1.6, 0.01);
                }
                if (t >= 10 && t % 5 == 0) {
                    for (Player p : Fx.playersNear(n, 3.5)) {
                        if (!enLaPelea(p)) continue;
                        Compat.apply(p, "slowness", 20, 1);
                        p.setFireTicks(Math.max(p.getFireTicks(), 30));
                        p.setNoDamageTicks(0);
                        hit(p, 1.0 * damageBonus, m);
                    }
                }
            }
            if (t % 30 == 0) soundAt(nubes.get(0), "block.fire.ambient", 1.2f, 0.7f);
        }, null);
    }

    // --------------------------------------------------------- RAFAGA GEMELA

    /** Ráfaga Gemela: doce flechas en 1,2 s al mas cercano, de dos en dos. */
    public void rafagaGemela() {
        if (!alive()) return;
        final double m = abilityDamage();
        Player p = Fx.nearest(loc(), 40);
        if (p == null || !enLaPelea(p)) return;
        apuntado = p;
        tensar(true);
        announce(Component.text("Ráfaga gemela.", HIELO));
        soundAt(loc(), "item.crossbow.quick_charge_3", 1.2f, 1.4f);
        animate(30, t -> {
            if (!alive() || cerrado) throw Stop.now();
            if (t < 6 || (t - 6) % 4 != 0 || (t - 6) / 4 >= 6) return;
            if (!enLaPelea(p)) return;
            Location b = boca(pecho(p));
            Vector d = pecho(p).toVector().subtract(b.toVector());
            Vector lado = d.clone().setY(0);
            if (lado.lengthSquared() < 0.01) lado = new Vector(1, 0, 0);
            lado = lado.normalize().rotateAroundY(Math.PI / 2).multiply(0.35);
            for (int s = -1; s <= 1; s += 2) {
                Location o = b.clone().add(lado.clone().multiply(s));
                Saeta f = disparar(o, pecho(p).toVector().subtract(o.toVector()), V_BASE, Tipo.BASICA, 4.5, m);
                if (f != null) f.rapida = true;
            }
            soundAt(b, "entity.arrow.shoot", 1.1f, 1.5f + random.nextFloat() * 0.3f);
        }, () -> tensar(false));
    }

    // ---------------------------------------------------- DISPARO HIPERSONICO

    /**
     * Disparo Hipersónico: 0,8 s de linea de aviso que sigue a la presa y se fija al final; la
     * flecha sale a velocidad 8 y atraviesa a todos los de la linea (26).
     */
    public void disparoHipersonico() {
        if (!alive()) return;
        final double m = abilityDamage();
        Player p = objetivo(56);
        if (p == null) return;
        apuntado = p;
        tensar(true);
        announce(Component.text("Disparo hipersónico.", HIELO));
        soundAt(loc(), "item.crossbow.loading_middle", 1.4f, 0.6f);
        final Vector[] dir = {null};
        final Location[] desde = {null};
        final int aviso = 16;
        animate(aviso + 8, t -> {
            if (!alive() || cerrado) throw Stop.now();
            if (t < aviso) {
                if (t < aviso - 5 && enLaPelea(p)) {
                    desde[0] = boca(pecho(p));
                    dir[0] = pecho(p).toVector().subtract(desde[0].toVector()).normalize();
                }
                if (dir[0] == null) return;
                if (t == aviso - 5) apuntado = null;
                if (t >= aviso - 5) miraFija = desde[0].clone().add(dir[0].clone().multiply(10));
                Location fin = desde[0].clone().add(dir[0].clone().multiply(45));
                int rgb = t >= aviso - 5 ? BLANCO : CELESTE;
                Fx.beam(desde[0], fin, t >= aviso - 5 ? 0.35 : 0.7, q -> polvo(q, rgb, 0.8f));
                if (t % 4 == 0) soundAt(loc(), "block.beacon.power_select", 0.8f, 1.2f + t * 0.05f);
                return;
            }
            if (t != aviso || dir[0] == null) return;
            tensar(false);
            Location b = desde[0];
            Saeta s = disparar(b, dir[0], 8.0, Tipo.HIPER, 0, m);
            if (s != null) {
                s.atraviesa = true;
                s.soloVisual = true;
            }
            fx(Compat.SONIC_BOOM, b, 1, 0, 0, 0, 0);
            fx(Compat.FLASH, b, 1, 0, 0, 0, 0, GLOW);
            soundAt(b, "entity.warden.sonic_boom", 0.9f, 1.7f);
            soundAt(b, "entity.firework_rocket.blast", 1.4f, 0.6f);
            soundAt(b, "item.trident.riptide_3", 1.2f, 1.4f);
            Location fin = b.clone().add(dir[0].clone().multiply(45));
            for (Player q : targets()) {
                if (!enLaPelea(q)) continue;
                if (distSegmento(pecho(q).toVector(), b.toVector(), fin.toVector()) > 1.3) continue;
                int retraso = (int) (q.getLocation().distance(b) / 8.0);
                later(retraso, () -> {
                    if (!enLaPelea(q)) return;
                    hit(q, D_HIPER * damageBonus, m);
                    fx(Compat.END_ROD, pecho(q), 16, 0.2, 0.4, 0.2, 0.15);
                    soundAt(q.getLocation(), "block.amethyst_block.break", 1.4f, 1.6f);
                });
            }
            later(6, () -> miraFija = null);
        }, () -> tensar(false));
    }

    // ---------------------------------------------------------- REBOTE ASTRAL

    /** Rebote Astral: una flecha que salta de jugador en jugador, hasta cinco. */
    public void reboteAstral() {
        if (!alive()) return;
        final double m = abilityDamage();
        Player p = Fx.nearest(loc(), 44);
        if (p == null || !enLaPelea(p)) return;
        apuntado = p;
        tensar(true);
        announce(Component.text("Rebote astral.", HIELO));
        soundAt(loc(), "block.amethyst_block.chime", 1.4f, 1.0f);
        later(14, () -> {
            tensar(false);
            if (!alive() || !enLaPelea(p)) return;
            Saeta s = dispararA(p, 4.2, Tipo.REBOTE, D_EFECTO, m);
            if (s != null) s.rebotes = 4;
            soundAt(loc(), "entity.arrow.shoot", 1.2f, 1.3f);
        });
    }

    // ---------------------------------------------------------------- POLARIS

    /** Polaris: tres flechas teledirigidas al que mas lejos esta. */
    public void polaris() {
        if (!alive()) return;
        final double m = abilityDamage();
        List<Player> lejos = farthestTargets(1);
        if (lejos.isEmpty() || !enLaPelea(lejos.get(0))) return;
        final Player p = lejos.get(0);
        apuntado = p;
        tensar(true);
        announce(Component.text("Polaris: ninguna se pierde.", HIELO));
        soundAt(loc(), "block.beacon.power_select", 1.2f, 1.8f);
        for (int i = 0; i < 3; i++) {
            final int n = i;
            later(10 + i * 6, () -> {
                if (!alive() || !enLaPelea(p)) return;
                Location b = boca(pecho(p));
                Vector d = pecho(p).toVector().subtract(b.toVector()).normalize();
                Vector salida = d.clone().add(new Vector(0, 0.6, 0)).rotateAroundY(Math.toRadians((n - 1) * 25));
                Saeta s = disparar(b, salida, 2.6, Tipo.POLARIS, D_EFECTO_FUERTE, m);
                if (s != null) {
                    s.guiada = p;
                    s.vidaMax = 90;
                }
                soundAt(b, "entity.firework_rocket.launch", 1.0f, 1.6f);
                fx(Compat.END_ROD, b, 6, 0.1, 0.1, 0.1, 0.05);
                if (n == 2) tensar(false);
            });
        }
    }

    // ----------------------------------------------------------------- ORBITA

    /** Órbita (fase III): seis flechas le giran alrededor y paran las vuestras; luego salen. */
    public void orbita() {
        if (!alive()) return;
        final double m = abilityDamage();
        for (ItemDisplay d : orbita) quitar(d);
        orbita.clear();
        ItemStack flecha = new ItemStack(Material.ARROW);
        for (int i = 0; i < 6; i++) {
            ItemDisplay d = objeto(body().getLocation().add(0, 1.2, 0), flecha,
                    new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.6f, 1.6f, 1.6f), punta()),
                    true, Display.Billboard.FIXED);
            if (d != null) {
                d.setTeleportDuration(1);
                orbita.add(d);
            }
        }
        orbitando = true;
        announce(Component.text("Órbita: vuestras flechas no pasan.", HIELO));
        soundAt(loc(), "block.beacon.activate", 1.4f, 1.6f);
        soundAt(loc(), "item.trident.riptide_1", 1.2f, 1.4f);
        final int gira = 100;
        animate(gira + 6 * 5 + 4, t -> {
            if (!alive() || cerrado) throw Stop.now();
            Location c = body().getLocation().add(0, 1.2, 0);
            double base = t * 0.25;
            for (int i = 0; i < orbita.size(); i++) {
                ItemDisplay d = orbita.get(i);
                if (d == null || !d.isValid()) continue;
                double a = base + i * Math.PI / 3;
                Location en = c.clone().add(Math.cos(a) * 2.2, Math.sin(a * 2) * 0.25, Math.sin(a) * 2.2);
                d.teleport(en);
                Vector tangente = new Vector(-Math.sin(a), 0, Math.cos(a));
                Transformation tr = d.getTransformation();
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(1);
                d.setTransformation(new Transformation(tr.getTranslation(), haciaY(tangente), tr.getScale(), punta()));
                if (t % 2 == 0) polvo(en, CELESTE, 1.2f);
            }
            if (t % 20 == 0 && t < gira) soundAt(c, "block.beacon.ambient", 1.0f, 1.8f);
            if (t >= gira && (t - gira) % 5 == 0) {
                if (t == gira) orbitando = false;
                int idx = (t - gira) / 5;
                if (idx < orbita.size()) {
                    ItemDisplay d = orbita.get(idx);
                    if (d != null && d.isValid()) {
                        List<Player> pool = targets();
                        pool.removeIf(q -> !enLaPelea(q));
                        if (!pool.isEmpty()) {
                            Player q = pool.get(idx % pool.size());
                            Location o = d.getLocation();
                            disparar(o, pecho(q).toVector().subtract(o.toVector()), 5.0, Tipo.ESTELAR, D_EFECTO_FUERTE, m);
                            soundAt(o, "entity.arrow.shoot", 1.2f, 1.4f + idx * 0.08f);
                            fx(Compat.FLASH, o, 1, 0, 0, 0, 0, GLOW);
                        }
                        quitar(d);
                    }
                }
            }
        }, () -> {
            orbitando = false;
            for (ItemDisplay d : orbita) quitar(d);
            orbita.clear();
        });
    }

    // ------------------------------------------------------- PASO DEL COMETA

    /** Paso del Cometa: se lanza de lado dejando sombras y dispara desde lo alto del salto. */
    public void pasoDelCometa() {
        if (!alive()) return;
        final double m = abilityDamage();
        Player p = objetivo(48);
        Location desde = boss.getLocation().clone();
        Vector hacia = p != null ? p.getLocation().toVector().subtract(desde.toVector()).setY(0) : new Vector(1, 0, 0);
        if (hacia.lengthSquared() < 0.01) hacia = new Vector(1, 0, 0);
        hacia.normalize();
        Location destino = null;
        int lado = random.nextBoolean() ? 1 : -1;
        for (int intento = 0; intento < 2 && destino == null; intento++, lado = -lado) {
            for (double dist = 10; dist >= 5 && destino == null; dist -= 2.5) {
                Vector d = hacia.clone().rotateAroundY(lado * Math.PI / 2).multiply(dist).add(hacia.clone().multiply(-2));
                Location prueba = Fx.ground(desde.clone().add(d).add(0, 2, 0), 6);
                if (dentroDeArena(prueba) && dePie(prueba) && Math.abs(prueba.getY() - desde.getY()) < 3) destino = prueba;
            }
        }
        if (destino == null) return;
        final Location fin = destino;
        try {
            if (boss instanceof Mob mob) mob.getPathfinder().stopPathfinding();
        } catch (Throwable ignored) {
        }
        soundAt(desde, "entity.breeze.jump", 1.4f, 1.2f);
        soundAt(desde, "entity.firework_rocket.launch", 1.0f, 1.8f);
        fx(Compat.SMALL_GUST, desde, 8, 0.4, 0.1, 0.4, 0.02);
        final float yaw = p != null ? yawDe(hacia) : desde.getYaw();
        sombra(desde, yaw, 20);
        final int dura = 12;
        animate(dura, t -> {
            if (!alive() || cerrado) throw Stop.now();
            double u = (t + 1) / (double) dura;
            Location en = desde.clone().add(fin.toVector().subtract(desde.toVector()).multiply(u));
            en.add(0, 4.0 * Math.sin(Math.PI * u), 0);
            en.setYaw(yaw);
            en.setPitch(0);
            boss.teleport(en);
            boss.setVelocity(new Vector());
            polvo(en.clone().add(0, 1, 0), CELESTE, 1.8f);
            fx(Compat.END_ROD, en.clone().add(0, 1, 0), 2, 0.2, 0.3, 0.2, 0.02);
            if (t == 4 || t == 8) sombra(Fx.ground(en.clone(), 6), yaw, 16);
            if (t == 6) {
                List<Player> tiro = pickTargets(3);
                tensar(false);
                for (Player q : tiro) {
                    if (!enLaPelea(q)) continue;
                    Location b = en.clone().add(0, 1.6, 0).add(pecho(q).toVector().subtract(en.toVector()).normalize().multiply(1.2));
                    disparar(b, pecho(q).toVector().subtract(b.toVector()), 4.8, Tipo.ESTELAR, D_EFECTO, m);
                }
                soundAt(en, "entity.arrow.shoot", 1.4f, 1.2f);
                fx(Compat.FLASH, en.clone().add(0, 1.4, 0), 1, 0, 0, 0, 0, GLOW);
            }
            if (t == 3) tensar(true);
        }, () -> {
            tensar(false);
            if (!alive()) return;
            Location l = fin.clone();
            l.setYaw(yaw);
            boss.teleport(l);
            fx(Compat.SMALL_GUST, l, 10, 0.5, 0.1, 0.5, 0.02);
            soundAt(l, "entity.breeze.land", 1.2f, 1.0f);
        });
    }

    // ------------------------------------------------------------ SALTO ATRAS

    /** Salto Atrás: empujón de viento a quien se le arrima, voltereta atrás y disparo doble. */
    public void saltoAtras() {
        if (!alive()) return;
        final double m = abilityDamage();
        Player p = Fx.nearest(loc(), 12);
        if (p == null || !enLaPelea(p)) p = objetivo(40);
        if (p == null) return;
        final Player presa = p;
        Location desde = boss.getLocation().clone();
        Location c = desde.clone().add(0, 0.8, 0);
        fx(Compat.GUST_EMITTER_LARGE, c, 1, 0, 0, 0, 0);
        fx(Compat.SMALL_GUST, c, 16, 1.5, 0.4, 1.5, 0.05);
        soundAt(c, "entity.wind_charge.wind_burst", 1.8f, 0.8f);
        soundAt(c, "entity.breeze.wind_burst", 1.4f, 1.0f);
        for (Player q : Fx.playersNear(c, 5.0)) {
            if (!enLaPelea(q)) continue;
            Vector away = q.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
            push(q, away.normalize().multiply(1.3).setY(0.45));
        }
        Vector atras = desde.toVector().subtract(presa.getLocation().toVector()).setY(0);
        if (atras.lengthSquared() < 0.01) atras = new Vector(1, 0, 0);
        atras.normalize();
        Location destino = null;
        for (double dist = 7; dist >= 3 && destino == null; dist -= 1.5) {
            Location prueba = Fx.ground(desde.clone().add(atras.clone().multiply(dist)).add(0, 2, 0), 6);
            if (dentroDeArena(prueba) && dePie(prueba) && Math.abs(prueba.getY() - desde.getY()) < 3) destino = prueba;
        }
        if (destino == null) destino = desde;
        final Location fin = destino;
        final float yaw = yawDe(atras.clone().multiply(-1));
        try {
            if (boss instanceof Mob mob) mob.getPathfinder().stopPathfinding();
        } catch (Throwable ignored) {
        }
        apuntado = null;
        final int dura = 10;
        animate(dura + 10, t -> {
            if (!alive() || cerrado) throw Stop.now();
            if (t < dura) {
                double u = (t + 1) / (double) dura;
                Location en = desde.clone().add(fin.toVector().subtract(desde.toVector()).multiply(u));
                en.add(0, 3.0 * Math.sin(Math.PI * u), 0);
                en.setYaw(yaw);
                en.setPitch(0);
                boss.teleport(en);
                boss.setVelocity(new Vector());
                // La voltereta: la cabeza va de mirar al frente a mirar al cielo y vuelve.
                pitchFijo = (float) (-90 * Math.sin(Math.PI * u));
                fx(Compat.CLOUD, en.clone().add(0, 0.6, 0), 1, 0.1, 0.1, 0.1, 0.0);
                polvo(en.clone().add(0, 1, 0), CELESTE, 1.6f);
                if (t == dura / 2) sombra(Fx.ground(en.clone(), 6), yaw, 12);
                return;
            }
            if (t == dura) {
                pitchFijo = null;
                apuntado = presa;
                tensar(true);
                fx(Compat.SMALL_GUST, fin, 8, 0.4, 0.1, 0.4, 0.02);
            }
            if ((t == dura + 4 || t == dura + 7) && enLaPelea(presa)) {
                Saeta s = dispararA(presa, 5.0, Tipo.ESTELAR, D_EFECTO, m);
                if (s != null) s.rapida = true;
                soundAt(loc(), "entity.arrow.shoot", 1.3f, 1.3f);
            }
        }, () -> {
            pitchFijo = null;
            tensar(false);
        });
    }
}
