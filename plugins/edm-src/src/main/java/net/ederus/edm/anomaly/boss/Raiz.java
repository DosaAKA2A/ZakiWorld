package net.ederus.edm.anomaly.boss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.core.ActiveAnomaly;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.ederus.edm.anomaly.core.Glow;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Creaking;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/**
 * RAIZ, el Corazon Palido.
 *
 * El segundo DIOS del catalogo, y el primero que no se mata a base de pegarle: RAIZ
 * decide cuando cambia de fase, y esa decision no es suya ni del reloj, es de los
 * jugadores. Cada vez que su vida toca el umbral se derrumba y planta una flor
 * palida; si el grupo la cura a tiempo con pociones arrojadizas, la flor le lanza un
 * rayo y pasa a la fase siguiente. Si no llegan, se levanta con un quinto de su vida
 * de vuelta y la pelea se alarga. No hay atajo: sin flor no hay fase.
 *
 * Es un creaking al DOBLE de tamano. Todo su repertorio sale del bosque palido:
 * resina, raices, eyeblossoms, robles que caen y copias que solo se mueven cuando no
 * las miras.
 *
 * Sus cuatro mecanicas de marca:
 *  - MIRADA DEL JARDIN: durante unos segundos exige que TODOS lo esten mirando. El
 *    combate se congela (nadie pega, nadie recibe, el tampoco) y al terminar, quien
 *    no lo tuviera en pantalla recibe dos mil de dano puro.
 *  - VENGANZA: se abre de brazos y acumula todo lo que le peguen; al cerrarse lo
 *    devuelve entero en area. Pegarle mientras dura es pegarse a uno mismo.
 *  - LA FLOR: el ritual de cambio de fase que se cura en vez de romperse.
 *  - ODIO: cada golpe suyo que acierta lo hace pegar mas fuerte y mas rapido, y no
 *    para hasta que falla.
 *
 * El cuerpo que se ve es el creaking; el que pelea es un zombi invisible debajo, como
 * en Rabby y Alba. Un creaking de verdad se queda clavado en cuanto alguien lo mira
 * (es su gracia en vanilla) y eso, en una pelea, seria un jefe que no se mueve.
 *
 * ROTTEN NO CAMINA NI PEGA (desde 1.62.0, a peticion de Dosa): flota clavado en su
 * sitio, sin IA, y todo lo que hace lo hace el bosque por el. Los golpes de contacto
 * son de sus crujidos, que se mueven y pegan aunque los miren.
 */
public final class Raiz extends BossFight {

    public static final String ID = "raiz";
    /** Verde palido de eyeblossom: el color de marca. */
    public static final TextColor ACCENT = TextColor.color(0x9FD6A0);

    /** El naranja enfermo de la flor abierta. */
    private static final int AMBAR = 0xE8A33D;
    /** La corteza del roble palido. */
    private static final int CORTEZA = 0xD6D2C4;
    /** El rojo de la resina. */
    private static final int RESINA = 0xD86E3C;

    /** Lo que la Mirada del Jardin cobra al que no estaba mirando. */
    private static final double DANO_MIRADA = 2000;

    // --- la mirada
    /** Mientras dura, nadie pega ni recibe: ni los jugadores ni el. */
    private boolean combateCongelado;

    // --- venganza
    private boolean vengando;
    private double vengado;

    // --- odio
    private int odio;
    private long odioHasta;
    /** El ultimo tick en que el odio subio: una habilidad en area cuenta UNA vez. */
    private long odioTick = -1;

    // --- el ritual de la flor
    /** El ritual esta en marcha: el jefe esta caido y no cambia de fase. */
    private boolean ritualActivo;
    /** El ritual termino BIEN: el siguiente cambio de fase esta permitido. */
    private boolean ritualCumplido;
    /** A que fase se iba cuando empezo el ritual. */
    private int faseDestino;
    /** Hasta que tick no se abre otro ritual tras uno fallido (o se encadenarian). */
    private long ritualNoAntesDe;
    /** Dosis de curacion que lleva la flor y las que pide. */
    private int dosis;
    private int dosisPedidas;
    /** Las piezas de la flor, para limpiarlas cuando acabe. */
    private final List<Entity> flor = new ArrayList<>();
    private Location florEn;
    /** La corola: cerrada al plantarla, abierta cuando lleva la mitad. */
    private ItemDisplay florCorola;
    /** Hasta que tick dura el ritual, para decir cuanto queda. */
    private long ritualHasta;

    /** Donde flota: el punto del suelo sobre el que oscila. */
    private Location flotaBase;
    /** Mientras se hunde (Hundirse y Salir) se queda bajo tierra hasta este tick. */
    private long hundidoHasta;
    /** Los crujidos que pelean por el (id -> su golpe): se mueven y pegan aunque los miren. */
    private final Map<UUID, Double> esbirros = new HashMap<>();
    private final Map<UUID, Long> esbirroPego = new HashMap<>();

    /** Copias del Jardin Falso: se mueven solo cuando nadie las mira. */
    private final Map<UUID, Location> copias = new HashMap<>();

    public Raiz(AnomalyPlugin plugin, ActiveAnomaly event, Location arena) {
        super(plugin, event, arena);
        abilities.addAll(plugin.registry().get(ID).abilities());
    }

    @Override
    public String bossName() {
        return "ROTTEN";
    }

    @Override
    public TextColor accent() {
        return ACCENT;
    }

    /** Cinco fases, como Alba: es un Dios, no un Monarca. */
    @Override
    public int phaseCount() {
        return 5;
    }

    /** Una sola barra continua: cinco barras seccionadas saturan la pantalla. */
    @Override
    public boolean usesOwnBars() {
        return false;
    }

    // ------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        Location at = arena.clone();

        /* El que pelea: un zombi invisible. El creaking de verdad se congela en
         * cuanto alguien lo mira, asi que no puede ser el cuerpo de combate. */
        /* A ras de suelo de verdad: el punto de apertura puede venir a media altura,
         * y como ya no hay gravedad que lo baje, flotar() lo dejaria ahi toda la pelea. */
        flotaBase = Fx.ground(at, 8);
        boss = world().spawn(flotaBase.clone().add(0, 1.4, 0), Zombie.class, z -> {
            z.setPersistent(false);
            z.setShouldBurnInDay(false);
            z.setInvisible(true);
            z.setSilent(true);
            z.setBaby(false);
            /* Sin IA y sin gravedad: no camina ni pega. Se queda flotando donde nace
             * y flotar() lo mece; todo su repertorio es magia del bosque. */
            z.setAI(false);
            z.setGravity(false);
            Compat.setAttribute(z, "scale", 2.0);
            Compat.setAttribute(z, "attack_damage", 13);
            Compat.setAttribute(z, "movement_speed", 0.27);
            Compat.setAttribute(z, "knockback_resistance", 1.0);
            Compat.setAttribute(z, "follow_range", 64);
            if (z.getEquipment() != null) z.getEquipment().clear();
            /* Con nombre aunque sea invisible: es lo que leen los mensajes de muerte
             * ("asesinado por RAIZ", no "por un Zombie"). Como Rabby. */
            z.customName(Component.text("ROTTEN", ACCENT));
            z.setCustomNameVisible(false);
        });
        net.ederus.edm.comun.Tags.markBoss(boss, ID);

        /* El que se ve: el creaking, al doble, sin IA (lo lleva tickShell del padre)
         * y sin nombre encima, como todo lo que invocan los Dioses. */
        Creaking cuerpo = world().spawn(at, Creaking.class, c -> {
            c.setPersistent(false);
            c.setAI(false);
            c.setGravity(false);
            c.setSilent(false);
            c.setInvulnerable(true);
            c.setCollidable(false);
            Compat.setAttribute(c, "scale", 2.0);
            c.customName(Component.text("ROTTEN", ACCENT));
            c.setCustomNameVisible(false);
        });
        markMinion(cuerpo);
        shell = cuerpo;

        applyHealth(plugin.registry().scaledHealth(plugin.registry().get(ID), targets(96).size()));
        glowBody(NamedTextColor.DARK_GREEN);

        // Nace del suelo: hojas palidas, resina y el crujido.
        Compat.spawn(world(), Compat.CHERRY_LEAVES, at.clone().add(0, 1.6, 0), 60, 1.6, 1.4, 1.6, 0.04);
        Fx.ring(at.clone().add(0, 0.2, 0), 4.5, 30, p ->
                Compat.spawn(world(), Compat.DUST, p, 2, 0.1, 0.1, 0.1, 0, Compat.dust(CORTEZA, 1.9f)));
        soundAt(at, "entity.creaking.spawn", 1.8f, 0.6f);
        soundAt(at, "block.creaking_heart.spawn", 1.6f, 0.7f);
        titleNear(Component.text("ROTTEN", ACCENT),
                Component.text("El bosque os ha visto", NamedTextColor.GRAY));
    }

    @Override
    protected void onPhaseChange(int from, int to) {
        // El rayo de la flor ya hizo la animacion; aqui solo se sella el paso.
        ritualCumplido = false;
        odio = 0;
        Location c = center();
        Fx.shockwave(world(), c, 7, Compat.CHERRY_LEAVES, 3);
        soundAt(c, "block.creaking_heart.break", 1.7f, 0.6f);
        soundAt(c, "entity.creaking.activate", 1.5f, 0.6f);
        titleNear(Component.text("Fase " + to, ACCENT, TextDecoration.BOLD),
                Component.text(nombreDeFase(to), NamedTextColor.GRAY));
        busyFor(30);
    }

    private static String nombreDeFase(int fase) {
        return switch (fase) {
            case 2 -> "La savia negra";
            case 3 -> "El odio";
            case 4 -> "La marchitez";
            case 5 -> "La noche pálida";
            default -> "El bosque despierta";
        };
    }

    @Override
    public void onDeath() {
        Location c = center();
        combateCongelado = false;
        limpiarFlor();
        animate(90, tick -> {
            if (tick % 6 == 0) {
                Fx.ring(c.clone().add(0, 0.4 + tick * 0.05, 0), 1.5 + tick * 0.09, 24, p ->
                        Compat.spawn(world(), Compat.CHERRY_LEAVES, p, 2, 0.1, 0.1, 0.1, 0.02));
            }
            if (tick % 18 == 0) soundAt(c, "entity.creaking.die", 1.4f, 0.6f + tick * 0.004f);
        }, () -> {
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, c.clone().add(0, 1, 0), 1);
            soundAt(c, "block.creaking_heart.break", 2.0f, 0.5f);
        });
    }

    @Override
    public int deathAnimationTicks() {
        return 130;
    }

    // --------------------------------------------------------------------- pasivas

    @Override
    protected void ambient() {
        if (!alive()) return;
        flotar();
        if (!combateCongelado && ticks() % 4 == 0) tickEsbirros();

        // Latido del corazon palido: se acelera al perder vida.
        int pulso = 30 + (int) (30 * healthFraction());
        if (ticks() % Math.max(10, pulso) == 0) {
            soundAt(loc(), "block.creaking_heart.idle", 1.1f, 0.7f);
            Compat.spawn(world(), Compat.DUST, center().add(0, 1.4, 0), 4, 0.5, 0.6, 0.5, 0,
                    Compat.dust(AMBAR, 1.4f));
        }

        // Hojas que caen siempre a su alrededor: el bosque esta vivo y se nota.
        if (ticks() % 10 == 0) {
            Compat.spawn(world(), Compat.CHERRY_LEAVES, center().add(0, 2.2, 0), 6, 1.8, 0.8, 1.8, 0.01);
        }

        // ODIO: mientras dure, pega mas y mas rapido. Se apaga solo.
        if (odio > 0 && ticks() > odioHasta) {
            odio = 0;
            Compat.setAttribute(boss, "attack_speed", 4.0);
            announce(Component.text("El odio se le enfría."));
        }

        // El ritual manda sobre todo lo demas: mientras esta caido no hace nada mas.
        if (ritualActivo) {
            tickRitual();
            return;
        }

        // Las copias del Jardin Falso solo avanzan cuando nadie las mira.
        if (!copias.isEmpty() && ticks() % 4 == 0) moverCopias();
    }

    /**
     * BossFight le quita la invulnerabilidad a cualquier jefe que la lleve mas de 20 s,
     * como red de seguridad. El ritual de la flor dura 45 y la Mirada congela todo a
     * proposito: aqui esas dos son las excepciones, y solo ellas.
     */
    @Override
    protected boolean allowLongInvulnerability() {
        return ritualActivo || combateCongelado;
    }

    /** Mientras la Mirada congela el combate, ni el jefe recibe ni reparte. */
    @Override
    public double incomingDamageMultiplier() {
        if (combateCongelado || ritualActivo) return 0;
        return 1.0;
    }

    /** El ODIO se alimenta de aciertos: cada golpe que entra sube el contador. */
    @Override
    public void hit(Player p, double amount) {
        // Un golpe de cero sigue siendo un golpe (animacion, empujon, evento): mientras
        // la Mirada congela el combate, no se pega y punto.
        if (combateCongelado || ritualActivo) return;
        double extra = 1 + odio * 0.12;
        super.hit(p, amount * extra);
        if (odio > 0 && odio < 12 && ticks() != odioTick) {
            odioTick = ticks();
            odio++;
            odioHasta = ticks() + 120;
            Compat.setAttribute(boss, "attack_speed", Math.min(12, 4.0 + odio * 0.5));
        }
    }

    /** Lo que le peguen mientras VENGA no se pierde: se guarda para devolverlo. */
    @Override
    public void onIncomingDamage(double cantidad) {
        if (vengando) vengado += cantidad;
    }

    // =========================================================================
    //  LAS CUATRO MECANICAS DE MARCA
    // =========================================================================

    /**
     * MIRADA DEL JARDIN. Seis segundos en los que hay que tenerlo en pantalla.
     *
     * Mientras dura, el combate se para entero: los jugadores no pueden pegarse ni
     * recibir (Resistencia y Debilidad) y el jefe ni ataca ni recibe. Al cerrarse,
     * quien no lo estuviera mirando se lleva dos mil de dano puro, que es matar.
     */
    public void miradaDelJardin() {
        if (!alive() || ritualActivo) return;
        Location c = center();
        combateCongelado = true;
        boss.setAI(false);
        busyFor(150);

        titleNear(Component.text("MIRADLO", ACCENT, TextDecoration.BOLD),
                Component.text("Quien aparte la vista, muere", NamedTextColor.RED));
        soundAt(c, "entity.creaking.activate", 2.0f, 0.5f);

        /* Duran lo que la mirada (120 ticks) y un pelo mas, y se dejan caducar solos.
         * Antes se quitaban a mano al cerrar, y removePotionEffect se lleva TAMBIEN la
         * Resistencia que el jugador trajera de antes (un set, un faro): salia de la
         * mirada sin la suya. Asi la nuestra tapa la suya un momento y luego vuelve. */
        for (Player p : targets(60)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 130, 4, true, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 130, 9, true, false, false));
        }

        animate(120, tick -> {
            if (!alive()) throw net.ederus.edm.anomaly.core.Stop.now();
            // El aro se cierra sobre el: marca cuanto queda.
            double r = 9.0 * (1 - tick / 120.0) + 1.5;
            Fx.ring(c.clone().add(0, 0.3, 0), r, 30, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(AMBAR, 1.5f)));
            if (tick % 20 == 0) {
                soundAt(c, "block.creaking_heart.idle", 1.6f, 0.5f + tick * 0.004f);
                for (Player p : targets(60)) {
                    boolean mirando = mirandoAlJefe(p);
                    p.sendActionBar(mirando
                            ? Component.text("Lo estás mirando", ACCENT)
                            : Component.text("MÍRALO", NamedTextColor.RED, TextDecoration.BOLD));
                    if (!mirando) {
                        Compat.spawn(world(), Compat.SMOKE, p.getEyeLocation(), 12, 0.3, 0.3, 0.3, 0.02);
                    }
                }
            }
        }, () -> {
            combateCongelado = false;
            int caidos = 0;
            for (Player p : targets(60)) {
                if (mirandoAlJefe(p)) continue;
                caidos++;
                // Dano PURO: no lo para la armadura ni la resistencia.
                p.setHealth(Math.max(0, p.getHealth() - DANO_MIRADA));
                Compat.spawn(world(), Compat.EXPLOSION, p.getLocation().add(0, 1, 0), 3, 0.3, 0.5, 0.3, 0);
                soundAt(p.getLocation(), "entity.creaking.attack", 1.6f, 0.5f);
            }
            soundAt(c, "entity.creaking.deactivate", 1.8f, 0.6f);
            announce(caidos == 0
                    ? Component.text("Todos aguantaron la mirada.")
                    : Component.text("Apartaron la vista: " + caidos + "."));
        });
    }

    /** Si el jugador tiene al jefe delante de verdad (mas o menos en pantalla). */
    /**
     * Si el jugador lo tiene EN EL PUNTERO, no solo delante.
     *
     * Como provocar a un enderman: la mira tiene que caer sobre el, no basta con
     * tenerlo en el campo de vision. Se traza un rayo desde el ojo en la direccion en
     * que mira y se cruza con la caja del cuerpo (el creaking, o el zombi si no hay
     * cuerpo), agrandada para dar margen. Sin pared de por medio: el juicio es de
     * puntería, no de línea de vista.
     *
     * El margen CRECE con la distancia (unos tres grados de tolerancia). Con uno fijo
     * de 0.7 bloques, a cuarenta bloques el blanco era de un par de grados y fallarlo
     * son dos mil de daño: con ratón se acierta, pero en Bedrock con pantalla táctil
     * era morir casi seguro. Así apuntarle cuesta lo mismo de cerca que de lejos.
     */
    private boolean mirandoAlJefe(Player p) {
        LivingEntity mira = shell != null && shell.isValid() ? shell : boss;
        if (mira == null || !mira.isValid()) return false;
        Location ojo = p.getEyeLocation();
        if (ojo.getWorld() != mira.getWorld()) return false;
        org.bukkit.util.BoundingBox cuerpo = mira.getBoundingBox();
        double distancia = ojo.toVector().distance(cuerpo.getCenter());
        org.bukkit.util.BoundingBox caja = cuerpo.clone().expand(0.7 + distancia * 0.05);
        return caja.rayTrace(ojo.toVector(), ojo.getDirection(), 90) != null;
    }

    /**
     * VENGANZA. Se abre y aguanta; todo lo que le peguen se acumula y vuelve entero.
     *
     * No es un escudo: se le puede seguir pegando, y de hecho el grupo tiene que
     * decidir si merece la pena. Lo que devuelve se reparte entre los que estan cerca,
     * asi que apinarse mientras venga es la peor idea posible.
     */
    public void venganza() {
        if (!alive() || ritualActivo) return;
        Location c = center();
        vengando = true;
        vengado = 0;
        busyFor(180);

        announce(Component.text("Se abre. Todo lo que le des, te lo devuelve."));
        titleNear(Component.empty(), Component.text("VENGANZA", ACCENT));
        soundAt(c, "block.creaking_heart.hurt", 1.8f, 0.5f);

        animate(160, tick -> {
            if (!alive()) throw net.ederus.edm.anomaly.core.Stop.now();
            Location aqui = center();
            double r = 1.2 + (vengado / 200.0);
            Fx.sphere(aqui.clone().add(0, 1.3, 0), Math.min(6, r), 14, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0,
                            Compat.dust(RESINA, (float) Math.min(2.4, 1.0 + r * 0.2))));
            if (tick % 20 == 0) soundAt(aqui, "block.resin_bricks.hit", 1.2f, 0.6f);
        }, () -> {
            vengando = false;
            Location aqui = center();
            double total = vengado;
            vengado = 0;
            if (total <= 0) {
                announce(Component.text("Nadie le puso la mano encima."));
                return;
            }
            List<Player> dentro = targets(11);
            double porCabeza = Math.min(90, total / Math.max(1, dentro.size()) * 1.6);
            for (Player p : dentro) {
                hit(p, porCabeza);
                push(p, p.getLocation().toVector().subtract(aqui.toVector())
                        .normalize().multiply(0.9).setY(0.45));
            }
            Fx.shockwave(world(), aqui, 11, Compat.DUST, 4);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, aqui.clone().add(0, 1, 0), 1);
            soundAt(aqui, "entity.creaking.attack", 2.0f, 0.5f);
            announce(Component.text("Devuelve " + Math.round(total) + " repartidos."));
        });
    }

    /** True mientras acumula: lo consulta el manager al aplicarle dano. */
    public boolean vengando() {
        return vengando;
    }

    /**
     * ODIO. Se enciende y a partir de ahi cada golpe suyo lo hace mas rapido y mas
     * fuerte. No se apaga solo mientras siga acertando.
     */
    public void odio() {
        if (!alive() || ritualActivo) return;
        odio = 1;
        odioHasta = ticks() + 120;
        Compat.setAttribute(boss, "attack_speed", 4.5);
        Location c = center();
        announce(Component.text("Se le pone la corteza roja."));
        titleNear(Component.empty(), Component.text("ODIO", TextColor.color(RESINA)));
        Fx.ring(c.clone().add(0, 0.3, 0), 3.0, 22, p ->
                Compat.spawn(world(), Compat.DUST, p, 2, 0.1, 0.2, 0.1, 0, Compat.dust(RESINA, 2.0f)));
        soundAt(c, "entity.creaking.angry", 1.8f, 0.5f);
        boss.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 300, 1, true, false, false));
    }

    // ------------------------------------------------------------------- la flor

    /**
     * El cambio de fase no lo decide la vida: lo deciden los jugadores curando la flor.
     *
     * Cuando la vida toca el umbral, esto abre el ritual y dice que NO. Solo devuelve
     * true cuando la flor se completo, que es lo unico que sube de fase.
     */
    @Override
    protected boolean canChangePhase(int from, int to) {
        if (ritualCumplido) return true;
        // Tras un ritual fallido se cura un 20%; si un reventon lo habia dejado muy
        // por debajo del umbral, sin este descanso abriria otro ritual en el acto.
        // Tampoco en mitad de la Mirada: la flor distraeria de mirarlo y eso son 2000 de daño.
        if (!ritualActivo && !combateCongelado && ticks() >= ritualNoAntesDe) abrirRitual(to);
        return false;
    }

    /** Se derrumba y planta la flor. Mientras dura no ataca, no recibe y no cambia de fase. */
    private void abrirRitual(int destino) {
        ritualActivo = true;
        ritualCumplido = false;
        faseDestino = destino;
        dosis = 0;
        /* Lo que pide la flor depende de cuantos pelean: una pocion, o la mitad de los
         * presentes redondeando hacia arriba. La de curacion II vale por dos. */
        int peleando = Math.max(1, targets(40).size());
        dosisPedidas = Math.max(1, (int) Math.ceil(peleando / 2.0));

        boss.setAI(false);
        boss.setInvulnerable(true);
        busyFor(20 * 45);
        ritualHasta = ticks() + 20 * 45;

        Location c = center();
        /* OJO con el orden: limpiar primero y fijar florEn DESPUES de plantar. La
         * version anterior lo hacia al reves y limpiarFlor() dejaba florEn en null
         * nada mas nacer: al tick siguiente el ritual se cerraba como fallido y el
         * jefe se curaba un 20%. Era "la flor desaparece al instante". */
        limpiarFlor();
        Location sitio = Fx.ground(c.clone().add(4.0, 0, 0), 8);
        plantarFlor(sitio);
        florEn = sitio;

        titleNear(Component.text("LA FLOR", ACCENT, TextDecoration.BOLD),
                Component.text("Tirad pociones de curación sobre ella", NamedTextColor.GRAY));
        announce(Component.text("Se derrumba. La flor pide " + dosisPedidas
                + (dosisPedidas == 1 ? " poción de curación." : " pociones de curación; la de nivel II vale por dos.")));
        soundAt(c, "entity.creaking.deactivate", 2.0f, 0.5f);
        soundAt(sitio, "block.eyeblossom_open.long", 1.6f, 0.8f);
    }

    /**
     * La flor: un tallo grueso de roble palido, una corola enorme que brilla y una
     * columna de luz hasta el cielo. Tiene que verse desde cualquier punto de la
     * arena, con niebla, con oscuridad o con veinte personas encima.
     */
    private void plantarFlor(Location donde) {
        for (int i = 0; i < 6; i++) {
            ItemDisplay tallo = Fx.itemDisplay(world(), donde.clone().add(0, 0.6 + i * 0.85, 0),
                    new ItemStack(Material.PALE_OAK_LOG), 1.5f);
            if (tallo != null) {
                markMinion(tallo);
                flor.add(tallo);
                Glow.apply(tallo, NamedTextColor.GREEN);
            }
        }
        florCorola = Fx.itemDisplay(world(), donde.clone().add(0, 6.2, 0),
                new ItemStack(Material.CLOSED_EYEBLOSSOM), 6.0f);
        if (florCorola != null) {
            /* Una flor es un sprite plano: fija, de lado no se ve. Encarada siempre
             * al que mira, se ve igual desde cualquier punto de la arena. */
            florCorola.setBillboard(Display.Billboard.CENTER);
            markMinion(florCorola);
            flor.add(florCorola);
            Glow.apply(florCorola, NamedTextColor.GREEN);
        }
        BlockDisplay luz = Fx.lightColumn(world(), donde.clone(), Material.LIME_STAINED_GLASS, 0.45f, 48f);
        if (luz != null) {
            markMinion(luz);
            flor.add(luz);
        }
    }

    /**
     * Un tick del ritual: mira si cae alguna pocion de curacion sobre la flor, pinta
     * cuanto lleva y, si se acaba el tiempo, lo levanta curado.
     */
    private void tickRitual() {
        if (florEn == null) {
            cerrarRitual(false);
            return;
        }
        // Las pociones arrojadizas que caen cerca cuentan como dosis. Se miran las
        // entidades en vuelo en vez de escuchar el evento: el jefe no tiene listener
        // propio, y asi la flor funciona igual la lance quien la lance.
        for (Entity e : world().getNearbyEntities(florEn, 4.5, 8.0, 4.5)) {
            if (!(e instanceof ThrownPotion pocion)) continue;
            int valor = dosisDe(pocion.getItem());
            if (valor <= 0) continue;
            e.remove();
            dosis += valor;
            Compat.spawn(world(), Compat.HEART, florEn.clone().add(0, 6.2, 0), 24, 1.2, 0.8, 1.2, 0.03);
            Compat.spawn(world(), Compat.HAPPY_VILLAGER, florEn.clone().add(0, 3.0, 0), 30, 0.8, 2.5, 0.8, 0.02);
            soundAt(florEn, "block.eyeblossom_open.long", 1.4f, 0.9f + dosis * 0.08f);
            soundAt(florEn, "entity.player.levelup", 0.8f, 1.6f);
            announce(Component.text("La flor lleva " + Math.min(dosis, dosisPedidas) + " de " + dosisPedidas + "."));
            if (florCorola != null && florCorola.isValid() && dosis * 2 >= dosisPedidas) {
                florCorola.setItemStack(new ItemStack(Material.OPEN_EYEBLOSSOM));
            }
            if (dosis >= dosisPedidas) {
                cerrarRitual(true);
                return;
            }
        }

        // Se ve desde lejos: el aro del suelo crece con lo que lleva, y por la columna
        // suben chispas todo el rato.
        if (ticks() % 5 == 0) {
            double r = 2.0 + 3.0 * Math.min(1.0, dosis / (double) dosisPedidas);
            Fx.ring(florEn.clone().add(0, 0.2, 0), r, 26, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(AMBAR, 1.6f)));
            Compat.spawn(world(), Compat.END_ROD, florEn.clone().add(0, 1 + random.nextDouble() * 9, 0), 3,
                    0.3, 0.6, 0.3, 0.01);
            Compat.spawn(world(), Compat.SPORE_BLOSSOM_AIR, florEn.clone().add(0, 6.2, 0), 4,
                    1.2, 0.8, 1.2, 0.01);
        }
        if (ticks() % 20 == 0) {
            int quedan = (int) Math.max(0, (ritualHasta - ticks()) / 20);
            for (Player p : targets(60)) {
                p.sendActionBar(Component.text("LA FLOR  " + Math.min(dosis, dosisPedidas) + " / " + dosisPedidas, ACCENT)
                        .append(Component.text("   " + quedan + " s", NamedTextColor.GRAY)));
            }
        }
        if (ticks() % 40 == 0) soundAt(florEn, "block.creaking_heart.idle", 1.4f, 1.3f);

        // Cuarenta y cinco segundos y ni uno mas.
        if (!busy()) cerrarRitual(false);
    }

    /**
     * Cierra el ritual. Si la flor se curo, el rayo y la fase nueva; si no, se levanta
     * con un quinto de su vida de vuelta y la fase se queda donde estaba.
     */
    private void cerrarRitual(boolean cumplido) {
        ritualActivo = false;
        boss.setInvulnerable(false);
        // El plazo de 45 s del ritual se pidio con busyFor; si la flor se completo
        // antes, no hay que esperar el resto sin hacer nada.
        unbusy();
        busyFor(30);

        if (cumplido) {
            ritualCumplido = true;
            Location desde = florEn == null ? center() : florEn.clone().add(0, 6.2, 0);
            Location hasta = center().add(0, 1.4, 0);
            Fx.beam(desde, hasta, 0.4, p ->
                    Compat.spawn(world(), Compat.DUST, p, 2, 0.05, 0.05, 0.05, 0,
                            Compat.dust(0xFFFFFF, 1.6f)));
            Compat.spawn(world(), Compat.FLASH, hasta, 1);
            soundAt(hasta, "block.beacon.activate", 1.8f, 1.2f);
            announce(Component.text("La flor lo alcanza. Se levanta cambiado."));
            titleNear(Component.empty(), Component.text("LA FLOR SE ABRIÓ", ACCENT));
        } else {
            double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
            double cura = max * 0.20;
            boss.setHealth(Math.min(max, boss.getHealth() + cura));
            Compat.spawn(world(), Compat.HEART, center().add(0, 1.6, 0), 30, 1.0, 1.0, 1.0, 0.04);
            soundAt(center(), "entity.creaking.activate", 1.8f, 0.5f);
            ritualNoAntesDe = ticks() + 20 * 30;
            announce(Component.text("La flor se marchitó. Se levanta curado."));
            titleNear(Component.text("SE LEVANTA", TextColor.color(RESINA), TextDecoration.BOLD),
                    Component.text("La flor no aguantó", NamedTextColor.GRAY));
        }
        limpiarFlor();
    }

    /** Cuanto vale una pocion para la flor: curacion I una dosis, curacion II dos; lo demas nada. */
    private static int dosisDe(ItemStack item) {
        if (item == null) return 0;
        if (!(item.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta pm)) return 0;
        String base = pm.getBasePotionType() == null ? "" : pm.getBasePotionType().name();
        if (base.equals("STRONG_HEALING")) return 2;
        if (base.contains("HEALING")) return 1;
        for (PotionEffect e : pm.getCustomEffects()) {
            if (e.getType().equals(PotionEffectType.INSTANT_HEALTH)) return e.getAmplifier() >= 1 ? 2 : 1;
        }
        return 0;
    }

    private void limpiarFlor() {
        for (Entity e : flor) Fx.safeRemove(e);
        flor.clear();
        florCorola = null;
        florEn = null;
    }

    // ------------------------------------------------------------ flotar y esbirros

    /**
     * El punto del suelo bajo el jefe. Como flota a 1,4 bloques, todo lo que golpea
     * el SUELO (barridos, cercos, telegrafias, ondas) se ancla aqui y no en center():
     * playersNear() mide contra los pies, y desde el cuerpo un radio corto no alcanza
     * a nadie.
     */
    private Location suelo() {
        return flotaBase != null ? flotaBase.clone() : Fx.ground(loc(), 6);
    }

    /**
     * ROTTEN no camina ni pega: flota clavado en su sitio con un vaiven suave y de
     * cara al mas cercano. Durante el ritual se derrumba hasta el suelo. El creaking
     * de encima lo sigue solo (tickShell).
     */
    private void flotar() {
        if (flotaBase == null || boss == null || !boss.isValid()) return;
        double alto = ticks() < hundidoHasta ? -2.6
                : ritualActivo ? 0.1
                : 1.4 + Math.sin(ticks() / 14.0) * 0.25;
        Location l = flotaBase.clone().add(0, alto, 0);
        Player cerca = ticks() % 5 == 0 ? Fx.nearest(l, 40) : null;
        if (cerca != null) {
            Vector d = cerca.getLocation().toVector().subtract(l.toVector());
            l.setYaw((float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ())));
        } else {
            l.setYaw(boss.getLocation().getYaw());
        }
        l.setPitch(0);
        boss.teleport(l);
        boss.setVelocity(new Vector(0, 0, 0));
    }

    /**
     * Los crujidos pelean de verdad. Un creaking de vanilla se clava en cuanto alguien
     * lo mira, y con cinco jugadores encima no se moveria nunca: aqui se le empuja a
     * mano hacia el mas cercano y, cuando lo tiene al lado, pega. Cuando nadie lo mira,
     * su propia IA hace el resto.
     */
    private void tickEsbirros() {
        for (UUID id : new ArrayList<>(esbirros.keySet())) {
            Entity e = world().getEntity(id);
            if (!(e instanceof Creaking cr) || !cr.isValid() || cr.isDead()) {
                esbirros.remove(id);
                esbirroPego.remove(id);
                continue;
            }
            Player cerca = Fx.nearest(cr.getLocation(), 30);
            if (cerca == null || !Fx.isFightable(cerca)) continue;
            Vector paso = cerca.getLocation().toVector().subtract(cr.getLocation().toVector());
            if (paso.length() <= 2.4) {
                long ultimo = esbirroPego.getOrDefault(id, -100L);
                if (ticks() - ultimo >= 24) {
                    esbirroPego.put(id, ticks());
                    // Por el padre a proposito: el hit() de ROTTEN se calla durante el
                    // ritual y no alimenta el ODIO, y el golpe de un crujido es del crujido.
                    super.hit(cerca, esbirros.get(id));
                    try {
                        cr.swingMainHand();
                    } catch (Throwable ignored) {
                    }
                    soundAt(cr.getLocation(), "entity.creaking.attack", 1.3f, 0.8f);
                }
                continue;
            }
            Location siguiente = Fx.ground(cr.getLocation().add(paso.normalize().multiply(0.8)), 2);
            siguiente.setYaw((float) Math.toDegrees(Math.atan2(-paso.getX(), paso.getZ())));
            siguiente.setPitch(0);
            cr.teleport(siguiente);
        }
    }

    /** Apunta a un crujido como esbirro que pelea, con el golpe que da. */
    private void esbirro(Creaking cr, double golpe) {
        esbirros.put(cr.getUniqueId(), golpe);
    }

    // =========================================================================
    //  FASE I  ·  EL BOSQUE DESPIERTA
    // =========================================================================

    /** Raices que salen del suelo y amarran a varios a la vez. */
    public void raicesQueAgarran() {
        if (!alive()) return;
        List<Player> marcas = pickTargets(3);
        for (Player p : marcas) {
            Location l = Fx.ground(p.getLocation(), 4);
            Fx.telegraph(world(), l, 2.0, CORTEZA);
            later(25, () -> {
                if (!p.isOnline()) return;
                Fx.helix(l, 1.2, 2.6, 24, 2.5, q ->
                        Compat.spawn(world(), Compat.DUST, q, 1, 0, 0, 0, 0, Compat.dust(CORTEZA, 1.6f)));
                for (Player d : Fx.playersNear(l, 2.4)) {
                    hit(d, 12);
                    d.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 3, true, false, false));
                }
                soundAt(l, "block.roots.break", 1.3f, 0.7f);
            });
        }
        announce(Component.text("El suelo se abre bajo varios."));
    }

    /** Resina que pega los pies al suelo y no deja correr. */
    public void resinaPegajosa() {
        if (!alive()) return;
        Location c = suelo();
        announce(Component.text("Suelta resina."));
        animate(60, tick -> {
            if (tick % 10 != 0) return;
            double r = 3 + tick * 0.08;
            Fx.ring(c.clone().add(0, 0.2, 0), r, 24, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0.05, 0.05, 0.05, 0,
                            Compat.dust(RESINA, 1.5f)));
            for (Player p : targets(r)) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2, true, false, false));
                hit(p, 4);
            }
            soundAt(c, "block.resin_bricks.place", 1.1f, 0.7f);
        }, null);
    }

    /** Un barrido de rama que alcanza a todo el que tenga delante. */
    public void ramaBarrida() {
        if (!alive()) return;
        Location c = center();
        Vector dir = boss.getLocation().getDirection().setY(0).normalize();
        soundAt(c, "entity.creaking.attack", 1.6f, 0.7f);
        Fx.arc(c.clone().add(0, 1.2, 0), dir, 6.5, Math.PI * 0.7, 30, p ->
                Compat.spawn(world(), Compat.DUST, p, 2, 0.1, 0.1, 0.1, 0, Compat.dust(CORTEZA, 2.0f)));
        for (Player p : targets(7)) {
            Vector hacia = p.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (hacia.lengthSquared() < 0.01 || dir.dot(hacia.normalize()) < 0.2) continue;
            hit(p, 22);
            push(p, hacia.normalize().multiply(1.3).setY(0.4));
        }
        announce(Component.text("Barre con la rama."));
    }

    /** Esporas que ciegan a los que estan lejos: no vale esconderse. */
    public void esporasCiegas() {
        if (!alive()) return;
        for (Player p : farthestTargets(3)) {
            Location l = p.getLocation().add(0, 2, 0);
            Compat.spawn(world(), Compat.SPORE_BLOSSOM_AIR, l, 40, 1.2, 1.2, 1.2, 0.02);
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0, true, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 120, 0, true, false, false));
            hit(p, 8);
            soundAt(l, "block.spore_blossom.step", 1.2f, 0.8f);
        }
        announce(Component.text("Suelta esporas sobre los de atrás."));
    }

    /** Se planta y golpea el suelo: onda que empuja a todos. */
    public void pisotonDeRaiz() {
        if (!alive()) return;
        Location c = suelo();
        Fx.telegraph(world(), c, 8, CORTEZA);
        busyFor(35);
        later(30, () -> {
            if (!alive()) return;
            Fx.shockwave(world(), c, 8, Compat.CHERRY_LEAVES, 4);
            for (Player p : targets(8)) {
                hit(p, 18);
                push(p, p.getLocation().toVector().subtract(c.toVector())
                        .normalize().multiply(1.1).setY(0.6));
            }
            soundAt(c, "block.creaking_heart.break", 1.7f, 0.6f);
        });
        announce(Component.text("Va a golpear el suelo."));
    }

    /** Dos eyeblossoms que se cierran y revientan donde estan los de delante. */
    public void floresQueCierran() {
        if (!alive()) return;
        for (Player objetivo : nearestTargets(2)) {
            Location l = Fx.ground(objetivo.getLocation(), 4);
            ItemDisplay d = Fx.itemDisplay(world(), l.clone().add(0, 0.6, 0),
                    new ItemStack(Material.OPEN_EYEBLOSSOM), 1.8f);
            if (d != null) markMinion(d);
            Fx.telegraph(world(), l, 3.0, AMBAR);
            soundAt(l, "block.eyeblossom_open.long", 1.3f, 1.0f);
            later(35, () -> {
                if (d != null) Fx.safeRemove(d);
                Compat.spawn(world(), Compat.EXPLOSION, l.clone().add(0, 1, 0), 2, 0.4, 0.4, 0.4, 0);
                for (Player p : Fx.playersNear(l, 3.2)) hit(p, 20);
                soundAt(l, "block.eyeblossom_close.long", 1.5f, 0.7f);
            });
        }
        announce(Component.text("Planta flores que se cierran."));
    }

    /** Llama a dos crujidos menores que pelean por el. */
    public void llamadaDelBosque() {
        if (!alive()) return;
        Location c = suelo();
        for (int i = 0; i < 2; i++) {
            double a = Math.PI * 2 * i / 2 + random.nextDouble();
            Location sitio = Fx.ground(c.clone().add(Math.cos(a) * 5, 0, Math.sin(a) * 5), 6);
            later(i * 10, () -> {
                if (!alive()) return;
                Creaking cria = world().spawn(sitio, Creaking.class, cr -> {
                    cr.setPersistent(false);
                    Compat.setAttribute(cr, "max_health", 40);
                    Compat.setAttribute(cr, "scale", 1.0);
                    cr.setHealth(40);
                });
                cria.customName(Component.text("Crujido", ACCENT));
                markMinion(cria);
                esbirro(cria, 9);
                Glow.apply(cria, NamedTextColor.DARK_GREEN);
                Compat.spawn(world(), Compat.CHERRY_LEAVES, sitio, 30, 0.6, 1.0, 0.6, 0.03);
                soundAt(sitio, "entity.creaking.spawn", 1.3f, 0.9f);
            });
        }
        announce(Component.text("Llama al bosque."));
    }

    /** Un empujon de savia que lanza y envenena a los que tiene pegados. */
    public void saviaNegra() {
        if (!alive()) return;
        Location c = center();
        for (Player p : targets(5)) {
            hit(p, 14);
            p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 1, true, false, false));
            push(p, p.getLocation().toVector().subtract(c.toVector()).normalize().multiply(0.8).setY(0.35));
        }
        Fx.sphere(c.clone().add(0, 1.2, 0), 4.5, 40, p ->
                Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(0x2C2118, 1.7f)));
        soundAt(c, "block.creaking_heart.hurt", 1.5f, 0.6f);
        announce(Component.text("Escupe savia negra."));
    }

    // =========================================================================
    //  FASE II  ·  LA SAVIA NEGRA
    // =========================================================================

    /** Copias de si mismo que solo avanzan cuando nadie las mira. */
    public void jardinFalso() {
        if (!alive()) return;
        Location c = suelo();
        for (int i = 0; i < 3; i++) {
            double a = Math.PI * 2 * i / 3;
            Location sitio = Fx.ground(c.clone().add(Math.cos(a) * 8, 0, Math.sin(a) * 8), 6);
            Creaking copia = world().spawn(sitio, Creaking.class, cr -> {
                cr.setPersistent(false);
                cr.setAI(false);
                cr.setInvulnerable(false);
                Compat.setAttribute(cr, "max_health", 30);
                Compat.setAttribute(cr, "scale", 2.0);
                cr.setHealth(30);
                cr.setCustomNameVisible(false);
            });
            markMinion(copia);
            copias.put(copia.getUniqueId(), sitio);
        }
        titleNear(Component.empty(), Component.text("¿CUÁL ES?", ACCENT));
        announce(Component.text("Tres copias. Solo se mueven si no las miras."));
        later(20 * 25, this::borrarCopias);
    }

    /** Las copias avanzan solo cuando nadie las tiene en pantalla: su gracia vanilla. */
    private void moverCopias() {
        for (UUID id : new ArrayList<>(copias.keySet())) {
            Entity e = world().getEntity(id);
            if (!(e instanceof Creaking copia) || !copia.isValid()) {
                copias.remove(id);
                continue;
            }
            Player cerca = Fx.nearest(copia.getLocation(), 30);
            if (cerca == null) continue;
            boolean vista = false;
            for (Player p : Fx.playersNear(copia.getLocation(), 40)) {
                Vector hacia = copia.getLocation().add(0, 1, 0).toVector()
                        .subtract(p.getEyeLocation().toVector());
                if (hacia.lengthSquared() < 0.01
                        || p.getEyeLocation().getDirection().normalize().dot(hacia.normalize()) > 0.6) {
                    vista = true;
                    break;
                }
            }
            if (vista) continue;
            Vector paso = cerca.getLocation().toVector().subtract(copia.getLocation().toVector());
            if (paso.lengthSquared() < 2.5) {
                hit(cerca, 16);
                soundAt(copia.getLocation(), "entity.creaking.attack", 1.4f, 0.7f);
                continue;
            }
            copia.teleport(copia.getLocation().add(paso.normalize().multiply(1.1)));
            Compat.spawn(world(), Compat.CHERRY_LEAVES, copia.getLocation(), 3, 0.3, 0.6, 0.3, 0.01);
        }
    }

    private void borrarCopias() {
        for (UUID id : copias.keySet()) {
            Entity e = world().getEntity(id);
            if (e != null) Fx.safeRemove(e);
        }
        copias.clear();
    }

    /** Un roble pálido que cae sobre una línea y aplasta lo que pille. */
    public void robleQueCae() {
        if (!alive()) return;
        Player objetivo = nearestTargets(1).stream().findFirst().orElse(null);
        if (objetivo == null) return;
        Location base = suelo();
        Vector dir = objetivo.getLocation().toVector().subtract(base.toVector()).setY(0).normalize();
        Location punta = base.clone().add(dir.clone().multiply(11));

        Fx.beam(base.clone().add(0, 0.4, 0), punta.clone().add(0, 0.4, 0), 0.6, p ->
                Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(CORTEZA, 1.3f)));
        announce(Component.text("Un roble se inclina."));
        busyFor(45);
        later(40, () -> {
            if (!alive()) return;
            Fx.beam(base, punta, 0.5, p -> {
                Compat.spawn(world(), Compat.BLOCK, p, 6, 0.3, 0.2, 0.3, 0);
                for (Player d : Fx.playersNear(p, 2.0)) hit(d, 26);
            });
            soundAt(punta, "block.pale_oak_wood.break", 1.8f, 0.6f);
            Compat.spawn(world(), Compat.EXPLOSION, punta.clone().add(0, 1, 0), 2, 0.5, 0.3, 0.5, 0);
        });
    }

    /** Niebla de musgo: no se ve y se pierde la orientación. */
    public void nieblaDeMusgo() {
        if (!alive()) return;
        Location c = center();
        announce(Component.text("El aire se llena de musgo."));
        animate(140, tick -> {
            if (tick % 8 != 0) return;
            for (Player p : targets(16)) {
                Compat.spawn(world(), Compat.SPORE_BLOSSOM_AIR, p.getEyeLocation(), 12,
                        1.2, 1.0, 1.2, 0.01);
                p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, true, false, false));
            }
            soundAt(c, "block.moss.step", 0.9f, 0.6f);
        }, null);
    }

    /** Cuatro anillos de espinas que se cierran sobre el centro. */
    public void cercoDeEspinas() {
        if (!alive()) return;
        Location c = suelo();
        busyFor(90);
        for (int i = 0; i < 4; i++) {
            double r = 12 - i * 2.4;
            later(i * 18, () -> {
                if (!alive()) return;
                Fx.ring(c.clone().add(0, 0.3, 0), r, 34, p -> {
                    Compat.spawn(world(), Compat.DUST, p, 2, 0.1, 0.3, 0.1, 0, Compat.dust(CORTEZA, 1.7f));
                    for (Player d : Fx.playersNear(p, 1.6)) hit(d, 11);
                });
                soundAt(c, "block.roots.place", 1.2f, 0.6f + (float) r * 0.02f);
            });
        }
        announce(Component.text("Cierra el cerco."));
    }

    /** Se lleva la luz de la zona y deja solo lo que él ilumina. */
    public void apagarElBosque() {
        if (!alive()) return;
        for (Player p : targets(40)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 200, 0, true, false, false));
        }
        Location c = center();
        Fx.sphere(c.clone().add(0, 1.4, 0), 3.0, 30, p ->
                Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(AMBAR, 1.8f)));
        soundAt(c, "block.eyeblossom_close.long", 1.7f, 0.6f);
        announce(Component.text("Se apaga el bosque."));
    }

    /** Escupe resina a tres a la vez y los deja clavados donde estén. */
    public void escupitajoDeResina() {
        if (!alive()) return;
        for (Player p : pickTargets(3)) {
            Location l = p.getLocation();
            Fx.beam(center().add(0, 1.6, 0), l.clone().add(0, 1, 0), 0.5, q ->
                    Compat.spawn(world(), Compat.DUST, q, 1, 0, 0, 0, 0, Compat.dust(RESINA, 1.4f)));
            hit(p, 15);
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 120, 5, true, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 120, 2, true, false, false));
            soundAt(l, "block.resin_bricks.place", 1.3f, 0.7f);
        }
        announce(Component.text("Escupe resina."));
    }

    /** Su corazón late fuerte y empuja a todo el que esté pegado. */
    public void latidoQueEmpuja() {
        if (!alive()) return;
        Location c = suelo();
        for (int i = 0; i < 3; i++) {
            later(i * 14, () -> {
                if (!alive()) return;
                Fx.shockwave(world(), c, 6, Compat.DUST, 3);
                for (Player p : targets(6)) {
                    hit(p, 9);
                    push(p, p.getLocation().toVector().subtract(c.toVector())
                            .normalize().multiply(1.4).setY(0.5));
                }
                soundAt(c, "block.creaking_heart.idle", 1.8f, 0.5f);
            });
        }
        announce(Component.text("Late fuerte."));
    }

    // =========================================================================
    //  FASE III  ·  EL ODIO
    // =========================================================================

    /** Una raiz recta sale disparada hacia el mas cercano: lo que pille en la linea, fuera. */
    public void lanzaDeRaiz() {
        if (!alive()) return;
        Player objetivo = nearestTargets(1).stream().findFirst().orElse(null);
        if (objetivo == null) return;
        Location base = suelo();
        Vector dir = objetivo.getLocation().toVector().subtract(base.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        dir.normalize();
        Location punta = base.clone().add(dir.clone().multiply(14));
        busyFor(40);
        announce(Component.text("Apunta con una raíz."));
        soundAt(base, "entity.creaking.angry", 1.6f, 0.6f);
        Fx.beam(base.clone().add(0, 0.3, 0), punta.clone().add(0, 0.3, 0), 0.7, p ->
                Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(CORTEZA, 1.2f)));
        later(22, () -> {
            if (!alive()) return;
            Set<UUID> ya = new HashSet<>();
            Fx.beam(base.clone().add(0, 0.8, 0), punta.clone().add(0, 0.8, 0), 0.5, p -> {
                Compat.spawn(world(), Compat.DUST, p, 3, 0.2, 0.3, 0.2, 0, Compat.dust(CORTEZA, 1.8f));
                for (Player d : Fx.playersNear(p, 2.2)) {
                    if (!ya.add(d.getUniqueId())) continue;
                    hit(d, 24);
                    push(d, dir.clone().multiply(1.5).setY(0.5));
                }
            });
            soundAt(punta, "block.roots.break", 1.6f, 0.5f);
        });
    }

    /** Clava a cuatro en el sitio y les cobra mientras no se suelten. */
    public void raicesQueSujetan() {
        if (!alive()) return;
        for (Player p : pickTargets(4)) {
            Location l = p.getLocation();
            root(p, 60);
            animate(60, tick -> {
                if (!p.isOnline()) return;
                if (tick % 10 == 0) {
                    hit(p, 7);
                    Fx.ring(l.clone().add(0, 0.2, 0), 1.0, 12, q ->
                            Compat.spawn(world(), Compat.DUST, q, 1, 0, 0, 0, 0,
                                    Compat.dust(CORTEZA, 1.3f)));
                }
            }, null);
        }
        announce(Component.text("Los clava al suelo."));
    }

    /** Lluvia de ramas sobre el área entera. */
    public void lluviaDeRamas() {
        if (!alive()) return;
        Location c = suelo();
        announce(Component.text("Se sacude y caen ramas."));
        busyFor(120);
        animate(110, tick -> {
            if (tick % 6 != 0) return;
            for (int i = 0; i < 3; i++) {
                double a = random.nextDouble() * Math.PI * 2;
                double r = random.nextDouble() * 13;
                Location donde = Fx.ground(c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 8);
                Fx.beam(donde.clone().add(0, 9, 0), donde, 0.7, p ->
                        Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(CORTEZA, 1.2f)));
                for (Player p : Fx.playersNear(donde, 2.0)) hit(p, 13);
                Compat.spawn(world(), Compat.BLOCK, donde, 8, 0.3, 0.1, 0.3, 0);
            }
            soundAt(c, "block.pale_oak_wood.break", 0.9f, 0.8f);
        }, null);
    }

    /** Muerde al que tiene delante y se cura con lo que le saca. */
    public void mordiscoDeSavia() {
        if (!alive()) return;
        List<Player> marcas = nearestTargets(2);
        double robado = 0;
        for (Player p : marcas) {
            hit(p, 28);
            robado += 28;
            Fx.beam(p.getEyeLocation(), center().add(0, 1.4, 0), 0.5, q ->
                    Compat.spawn(world(), Compat.DUST, q, 1, 0, 0, 0, 0, Compat.dust(RESINA, 1.2f)));
        }
        double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
        boss.setHealth(Math.min(max, boss.getHealth() + robado * 0.4));
        Compat.spawn(world(), Compat.HEART, center().add(0, 2, 0), 12, 0.5, 0.5, 0.5, 0.02);
        soundAt(center(), "entity.creaking.attack", 1.5f, 0.6f);
        announce(Component.text("Muerde y se cura."));
    }

    /** Una tanda de eyeblossoms por todo el suelo que explotan a la vez. */
    public void campoDeOjos() {
        if (!alive()) return;
        Location c = suelo();
        List<Location> sitios = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            double a = Math.PI * 2 * i / 9;
            double r = 4 + random.nextDouble() * 7;
            Location l = Fx.ground(c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 8);
            sitios.add(l);
            Fx.telegraph(world(), l, 2.6, AMBAR);
            ItemDisplay d = Fx.itemDisplay(world(), l.clone().add(0, 0.5, 0),
                    new ItemStack(Material.OPEN_EYEBLOSSOM), 1.6f);
            if (d != null) markMinion(d);
        }
        announce(Component.text("El suelo se llena de ojos."));
        busyFor(70);
        later(60, () -> {
            for (Location l : sitios) {
                Compat.spawn(world(), Compat.EXPLOSION, l.clone().add(0, 0.8, 0), 2, 0.4, 0.3, 0.4, 0);
                for (Player p : Fx.playersNear(l, 2.8)) hit(p, 19);
            }
            soundAt(c, "block.eyeblossom_close.long", 1.8f, 0.6f);
        });
    }

    /** Se envuelve en corteza: durante unos segundos recibe la mitad. */
    public void corazaDeCorteza() {
        if (!alive()) return;
        Location c = center();
        announce(Component.text("Se envuelve en corteza."));
        boss.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 160, 1, true, false, false));
        animate(160, tick -> {
            if (tick % 10 != 0) return;
            Fx.sphere(center().add(0, 1.3, 0), 2.2, 18, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(CORTEZA, 1.5f)));
        }, () -> soundAt(c, "block.pale_oak_wood.place", 1.2f, 0.7f));
    }

    /** Tira de los que huyen y los trae de vuelta. */
    public void ramaQueTira() {
        if (!alive()) return;
        Location c = center();
        for (Player p : farthestTargets(3)) {
            Fx.beam(c.clone().add(0, 1.6, 0), p.getLocation().add(0, 1, 0), 0.5, q ->
                    Compat.spawn(world(), Compat.DUST, q, 1, 0, 0, 0, 0, Compat.dust(CORTEZA, 1.4f)));
            Vector hacia = c.toVector().subtract(p.getLocation().toVector()).normalize().multiply(1.6);
            lift(p, hacia.setY(0.55));
            hit(p, 12);
            soundAt(p.getLocation(), "block.roots.break", 1.2f, 0.8f);
        }
        announce(Component.text("Tira de los de atrás."));
    }

    /** Grita y todo el que esté cerca queda sordo y lento. */
    public void crujidoQueAturde() {
        if (!alive()) return;
        Location c = center();
        Fx.shockwave(world(), c, 10, Compat.SONIC_BOOM, 2);
        for (Player p : targets(10)) {
            hit(p, 14);
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 140, 1, true, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 2, true, false, false));
        }
        soundAt(c, "entity.creaking.angry", 2.0f, 0.5f);
        announce(Component.text("Cruje."));
    }

    // =========================================================================
    //  FASE IV  ·  LA MARCHITEZ
    // =========================================================================

    /** El suelo se seca: quien pise fuera de las manchas vivas se marchita. */
    public void suelaMarchita() {
        if (!alive()) return;
        Location c = suelo();
        List<Location> seguros = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            double a = Math.PI * 2 * i / 4 + random.nextDouble();
            seguros.add(Fx.ground(c.clone().add(Math.cos(a) * 7, 0, Math.sin(a) * 7), 8));
        }
        for (Location s : seguros) Fx.telegraph(world(), s, 2.8, 0x6ECF6E);
        titleNear(Component.empty(), Component.text("A LO VERDE", ACCENT));
        announce(Component.text("Solo las manchas verdes aguantan."));
        busyFor(140);

        animate(130, tick -> {
            for (Location s : seguros) {
                Fx.ring(s.clone().add(0, 0.2, 0), 2.8, 18, p ->
                        Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(0x6ECF6E, 1.3f)));
            }
            if (tick % 20 != 0 || tick < 40) return;
            for (Player p : targets(24)) {
                boolean salvo = false;
                for (Location s : seguros) {
                    if (p.getLocation().distanceSquared(s) <= 2.8 * 2.8) salvo = true;
                }
                if (salvo) continue;
                hit(p, 16);
                p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 1, true, false, false));
                Compat.spawn(world(), Compat.ASH, p.getLocation().add(0, 1, 0), 14, 0.4, 0.6, 0.4, 0.02);
            }
        }, null);
    }

    /** Se parte en dos mitades que pegan por separado. */
    public void dosMitades() {
        if (!alive()) return;
        Location c = suelo();
        for (int i = 0; i < 2; i++) {
            double lado = i == 0 ? -6 : 6;
            Location sitio = Fx.ground(c.clone().add(lado, 0, 0), 6);
            later(i * 12, () -> {
                if (!alive()) return;
                Creaking mitad = world().spawn(sitio, Creaking.class, cr -> {
                    cr.setPersistent(false);
                    Compat.setAttribute(cr, "max_health", 70);
                    Compat.setAttribute(cr, "scale", 1.4);
                    cr.setHealth(70);
                });
                mitad.customName(Component.text("Mitad de Rotten", ACCENT));
                markMinion(mitad);
                esbirro(mitad, 13);
                Glow.apply(mitad, NamedTextColor.DARK_GREEN);
                Compat.spawn(world(), Compat.CHERRY_LEAVES, sitio, 40, 0.8, 1.2, 0.8, 0.04);
                soundAt(sitio, "entity.creaking.spawn", 1.4f, 0.8f);
            });
        }
        announce(Component.text("Se parte en dos."));
    }

    /** Un anillo de raíces que no deja salir del área. */
    public void jaulaDeRaices() {
        if (!alive()) return;
        Location c = suelo();
        double r = 12;
        announce(Component.text("Cierra el jardín."));
        busyFor(200);
        animate(200, tick -> {
            if (tick % 6 == 0) {
                Fx.ring(c.clone().add(0, 1.0, 0), r, 44, p ->
                        Compat.spawn(world(), Compat.DUST, p, 1, 0, 1.2, 0, 0, Compat.dust(CORTEZA, 1.8f)));
            }
            if (tick % 10 != 0) return;
            for (Player p : Fx.playersNear(c, 40)) {
                if (p.getLocation().distanceSquared(c) <= r * r) continue;
                Vector dentro = c.toVector().subtract(p.getLocation().toVector()).normalize().multiply(1.3);
                lift(p, dentro.setY(0.4));
                hit(p, 10);
                soundAt(p.getLocation(), "block.roots.break", 1.1f, 0.7f);
            }
        }, null);
    }

    /** Marchita el equipo: fatiga y debilidad a todos, un rato largo. */
    public void marchitarLasManos() {
        if (!alive()) return;
        for (Player p : targets(20)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 200, 2, true, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 200, 1, true, false, false));
            Compat.spawn(world(), Compat.ASH, p.getLocation().add(0, 1.2, 0), 18, 0.4, 0.6, 0.4, 0.01);
        }
        soundAt(center(), "block.eyeblossom_close.long", 1.5f, 0.6f);
        announce(Component.text("Os marchita las manos."));
    }

    /** Explota en esporas: daño alto y veneno a todo el que esté cerca. */
    public void estallidoDeEsporas() {
        if (!alive()) return;
        Location c = suelo();
        Fx.telegraph(world(), c, 10, 0x6ECF6E);
        busyFor(60);
        later(50, () -> {
            if (!alive()) return;
            Fx.shockwave(world(), c, 10, Compat.SPORE_BLOSSOM_AIR, 5);
            for (Player p : targets(10)) {
                hit(p, 30);
                p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 160, 2, true, false, false));
            }
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, c.clone().add(0, 1, 0), 1);
            soundAt(c, "block.spore_blossom.break", 1.9f, 0.6f);
        });
    }

    /** Roba la vista: ceguera a todos y él brilla, para que sepan dónde está. */
    public void robarLaVista() {
        if (!alive()) return;
        for (Player p : targets(30)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 120, 0, true, false, false));
        }
        animate(120, tick -> {
            if (tick % 8 != 0) return;
            Fx.sphere(center().add(0, 1.4, 0), 2.6, 22, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(AMBAR, 2.0f)));
        }, null);
        soundAt(center(), "block.eyeblossom_open.long", 1.7f, 0.6f);
        announce(Component.text("Os quita la vista."));
    }

    /** Tres zarpazos seguidos, cada uno a alguien distinto. */
    public void tresZarpazos() {
        if (!alive()) return;
        List<Player> marcas = pickTargets(3);
        for (int i = 0; i < marcas.size(); i++) {
            Player p = marcas.get(i);
            later(i * 12, () -> {
                if (!alive() || !p.isOnline()) return;
                hit(p, 26);
                Fx.arc(center().add(0, 1.4, 0),
                        p.getLocation().toVector().subtract(center().toVector()).setY(0).normalize(),
                        5, Math.PI * 0.4, 18, q ->
                                Compat.spawn(world(), Compat.CRIT, q, 2, 0.1, 0.1, 0.1, 0.05));
                soundAt(p.getLocation(), "entity.creaking.attack", 1.4f, 0.8f);
            });
        }
        announce(Component.text("Tres zarpazos."));
    }

    /** Se hunde y vuelve a salir detrás del que menos se lo espera. */
    public void hundirseYSalir() {
        if (!alive()) return;
        Player objetivo = farthestTargets(1).stream().findFirst().orElse(null);
        if (objetivo == null) return;
        Location salida = objetivo.getLocation().clone();
        busyFor(70);
        hundidoHasta = ticks() + 45;
        Compat.spawn(world(), Compat.CHERRY_LEAVES, center().add(0, 1, 0), 40, 0.8, 1.2, 0.8, 0.05);
        soundAt(center(), "entity.creaking.deactivate", 1.5f, 0.7f);
        announce(Component.text("Se hunde."));

        later(45, () -> {
            if (!alive()) return;
            flotaBase = Fx.ground(salida, 6);
            boss.teleport(flotaBase.clone().add(0, 1.4, 0));
            Fx.shockwave(world(), salida, 5, Compat.CHERRY_LEAVES, 4);
            for (Player p : Fx.playersNear(salida, 4)) {
                hit(p, 24);
                push(p, p.getLocation().toVector().subtract(salida.toVector())
                        .normalize().multiply(1.0).setY(0.5));
            }
            soundAt(salida, "entity.creaking.spawn", 1.7f, 0.6f);
        });
    }

    // =========================================================================
    //  FASE V  ·  LA NOCHE PALIDA
    // =========================================================================

    /** La grande del final: oscuridad total y solo se ve lo que él ilumina. */
    public void nochePalida() {
        if (!alive()) return;
        Location c = suelo();
        titleNear(Component.text("LA NOCHE PÁLIDA", ACCENT, TextDecoration.BOLD),
                Component.text("Solo se ve lo que él quiere", NamedTextColor.GRAY));
        announce(Component.text("Se hace de noche."));
        busyFor(220);

        animate(210, tick -> {
            if (!alive()) throw net.ederus.edm.anomaly.core.Stop.now();
            if (tick % 20 == 0) {
                for (Player p : targets(40)) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, true, false, false));
                }
            }
            // Cada pocos ticks se enciende una flor en algun sitio, y ahi pega.
            if (tick % 25 == 0) {
                double a = random.nextDouble() * Math.PI * 2;
                double r = 3 + random.nextDouble() * 10;
                Location donde = Fx.ground(c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 8);
                Fx.ring(donde.clone().add(0, 0.2, 0), 3.0, 22, p ->
                        Compat.spawn(world(), Compat.DUST, p, 2, 0, 0, 0, 0, Compat.dust(AMBAR, 2.0f)));
                soundAt(donde, "block.eyeblossom_open.long", 1.4f, 0.8f);
                later(20, () -> {
                    Compat.spawn(world(), Compat.EXPLOSION, donde.clone().add(0, 1, 0), 3, 0.5, 0.4, 0.5, 0);
                    for (Player p : Fx.playersNear(donde, 3.2)) hit(p, 25);
                    soundAt(donde, "block.eyeblossom_close.long", 1.5f, 0.6f);
                });
            }
        }, () -> soundAt(c, "block.creaking_heart.idle", 1.8f, 0.5f));
    }

    /** El jardín entero se cierra sobre el centro: hay que salir del área. */
    public void elJardinSeCierra() {
        if (!alive()) return;
        Location c = suelo();
        Fx.telegraph(world(), c, 14, RESINA);
        titleNear(Component.empty(), Component.text("FUERA DEL JARDÍN", TextColor.color(RESINA)));
        announce(Component.text("El jardín se cierra. Salid."));
        busyFor(110);
        later(100, () -> {
            if (!alive()) return;
            for (Player p : targets(14)) {
                hit(p, 70);
                push(p, new Vector(0, 0.7, 0));
            }
            Fx.shockwave(world(), c, 14, Compat.EXPLOSION, 6);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, c.clone().add(0, 1, 0), 3, 2, 1, 2, 0);
            soundAt(c, "block.creaking_heart.break", 2.0f, 0.5f);
        });
    }

    /** Cinco raíces gigantes que barren el área una detrás de otra. */
    public void barridoDeRaices() {
        if (!alive()) return;
        Location c = suelo();
        announce(Component.text("Cinco raíces barren."));
        busyFor(150);
        for (int i = 0; i < 5; i++) {
            double a = Math.PI * 2 * i / 5;
            later(i * 25, () -> {
                if (!alive()) return;
                Vector dir = new Vector(Math.cos(a), 0, Math.sin(a));
                Fx.beam(c, c.clone().add(dir.clone().multiply(14)), 0.6, p -> {
                    Compat.spawn(world(), Compat.DUST, p, 2, 0.2, 0.4, 0.2, 0, Compat.dust(CORTEZA, 1.8f));
                    for (Player d : Fx.playersNear(p, 2.2)) {
                        hit(d, 22);
                        push(d, dir.clone().multiply(1.2).setY(0.45));
                    }
                });
                soundAt(c, "block.roots.break", 1.5f, 0.6f);
            });
        }
    }

    /** Se lleva la mitad de la vida de todos y se la queda. */
    public void cosechaPalida() {
        if (!alive()) return;
        Location c = suelo();
        Fx.telegraph(world(), c, 16, AMBAR);
        announce(Component.text("Va a cosechar."));
        busyFor(100);
        later(90, () -> {
            if (!alive()) return;
            double total = 0;
            for (Player p : targets(16)) {
                double mitad = p.getHealth() * 0.45;
                hit(p, mitad);
                total += mitad;
                Fx.beam(p.getEyeLocation(), center().add(0, 1.4, 0), 0.5, q ->
                        Compat.spawn(world(), Compat.DUST, q, 1, 0, 0, 0, 0, Compat.dust(AMBAR, 1.4f)));
            }
            double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
            boss.setHealth(Math.min(max, boss.getHealth() + total * 0.3));
            Compat.spawn(world(), Compat.HEART, center().add(0, 2.2, 0), 26, 0.8, 0.8, 0.8, 0.03);
            soundAt(c, "entity.creaking.activate", 1.9f, 0.5f);
        });
    }

    /** Llama a cuatro crujidos a la vez: la última guardia. */
    public void ultimaGuardia() {
        if (!alive()) return;
        Location c = suelo();
        for (int i = 0; i < 4; i++) {
            double a = Math.PI * 2 * i / 4;
            Location sitio = Fx.ground(c.clone().add(Math.cos(a) * 6, 0, Math.sin(a) * 6), 6);
            later(i * 8, () -> {
                if (!alive()) return;
                Creaking g = world().spawn(sitio, Creaking.class, cr -> {
                    cr.setPersistent(false);
                    Compat.setAttribute(cr, "max_health", 60);
                    Compat.setAttribute(cr, "scale", 1.2);
                    cr.setHealth(60);
                });
                g.customName(Component.text("Guardia Pálida", ACCENT));
                markMinion(g);
                esbirro(g, 11);
                Glow.apply(g, NamedTextColor.DARK_GREEN);
                soundAt(sitio, "entity.creaking.spawn", 1.4f, 0.7f);
            });
        }
        announce(Component.text("Llama a su guardia."));
    }

    /** Fija a uno y no lo suelta: mientras viva, va solo a por él. */
    public void marcaDeRaiz() {
        if (!alive()) return;
        Player marcado = pickTargets(1).stream().findFirst().orElse(null);
        if (marcado == null) return;
        Glow.apply(marcado, NamedTextColor.DARK_RED);
        titleNear(Component.empty(), Component.text("MARCADO: " + marcado.getName(),
                TextColor.color(RESINA)));
        announce(Component.text("Marca a " + marcado.getName() + "."));
        animate(200, tick -> {
            if (!alive() || !marcado.isOnline()) throw net.ederus.edm.anomaly.core.Stop.now();
            if (boss instanceof org.bukkit.entity.Mob m) m.setTarget(marcado);
            if (tick % 20 == 0) {
                hit(marcado, 8);
                Fx.beam(center().add(0, 1.4, 0), marcado.getLocation().add(0, 1, 0), 0.8, q ->
                        Compat.spawn(world(), Compat.DUST, q, 1, 0, 0, 0, 0, Compat.dust(RESINA, 1.2f)));
            }
        }, () -> Glow.clear(marcado));
    }

    /** Lluvia de hojas que corta: daño continuo en toda el área. */
    public void lluviaDeHojas() {
        if (!alive()) return;
        Location c = center();
        announce(Component.text("Llueven hojas."));
        animate(160, tick -> {
            if (tick % 10 != 0) return;
            for (Player p : targets(18)) {
                Compat.spawn(world(), Compat.CHERRY_LEAVES, p.getLocation().add(0, 3, 0), 16,
                        1.0, 0.5, 1.0, 0.06);
                hit(p, 6);
            }
            soundAt(c, "block.pale_hanging_moss.step", 1.0f, 0.7f);
        }, null);
    }

    /** Se cura con cada esbirro suyo que siga vivo. */
    public void raizComun() {
        if (!alive()) return;
        int vivos = 0;
        for (Entity e : world().getNearbyEntities(center(), 30, 12, 30)) {
            if (e instanceof Creaking && !e.equals(shell)) vivos++;
        }
        if (vivos == 0) {
            announce(Component.text("Busca a los suyos y no queda ninguno."));
            return;
        }
        double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
        boss.setHealth(Math.min(max, boss.getHealth() + max * 0.03 * vivos));
        for (Entity e : world().getNearbyEntities(center(), 30, 12, 30)) {
            if (!(e instanceof Creaking) || e.equals(shell)) continue;
            Fx.beam(e.getLocation().add(0, 1, 0), center().add(0, 1.4, 0), 0.6, q ->
                    Compat.spawn(world(), Compat.DUST, q, 1, 0, 0, 0, 0, Compat.dust(0x6ECF6E, 1.3f)));
        }
        Compat.spawn(world(), Compat.HEART, center().add(0, 2, 0), 10 * vivos, 0.8, 0.8, 0.8, 0.02);
        soundAt(center(), "block.moss.place", 1.4f, 0.7f);
        announce(Component.text("Se alimenta de los suyos: " + vivos + "."));
    }

    // -------------------------------------------------------------------- limpieza

    @Override
    public void cleanup() {
        borrarCopias();
        limpiarFlor();
        esbirros.clear();
        esbirroPego.clear();
        combateCongelado = false;
        vengando = false;
        super.cleanup();
    }
}
