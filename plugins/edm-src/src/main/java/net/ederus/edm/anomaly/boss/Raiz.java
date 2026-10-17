package net.ederus.edm.anomaly.boss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.core.ActiveAnomaly;
import net.ederus.edm.anomaly.core.Glow;
import net.ederus.edm.anomaly.core.Stop;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.ederus.edm.comun.Tags;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Creaking;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ROTTEN (id interno raiz), el Corazon Palido.
 *
 * Un DIOS que no camina ni pega: es un arbol, un corazon de creaking clavado en el
 * centro de su jardin. Todo lo que hace son HECHIZOS, y como no se mueve, cada uno
 * alcanza la arena entera: o apunta a cada jugador, o cubre el area. Ninguno depende
 * de que el jugador se le acerque. Todos avisan durante toda la carga (marca repintada
 * cada 4 ticks y cuenta atras en la barra de accion), pegan UNA vez por jugador y por
 * lanzamiento, y se esquivan leyendo el aviso.
 *
 * Tres fases, siempre de una en una:
 *  - I   Brote: Raices del Jardin, Anillo de Espinas, Lluvia de Resina, Lanzas Palidas.
 *  - II  El Jardin se cierra: las de la I (con mas dano) y El Jardin se Cierra, Cruz de
 *        Raices y Esporas.
 *  - III Corazon Palido: Latido, Bosque de Lanzas, Cosecha y Estallido del Corazon.
 * La Mirada del Jardin sale una vez en cada fase.
 *
 * LA FLOR. Entre fase y fase hay un ritual: cuando la vida llega al umbral, la vida no
 * baja mas (suelo, survivalFloor) y se planta una flor en el pilar. Pide 3 + 2 pociones
 * de curacion ARROJADIZAS por jugador que pelea, todas en el vidrio. Si llegan, pasa a la
 * fase siguiente; si no, se cura un 10 % (sin salirse de su fase) y la flor vuelve a
 * abrirse a los 20 s. No hay forma de saltarse una fase.
 *
 * El cuerpo que se ve es el creaking; el que pelea es un zombi invisible debajo, como
 * en Rabby y Alba. Un creaking de verdad se queda clavado en cuanto alguien lo mira.
 */
public final class Raiz extends BossFight {

    public static final String ID = "raiz";
    /** Verde palido de eyeblossom: el color de marca. */
    public static final TextColor ACCENT = TextColor.color(0x9FD6A0);

    /** El naranja de la flor abierta y del corazon. */
    private static final int AMBAR = 0xE8A33D;
    /** La corteza del roble palido. */
    private static final int CORTEZA = 0xD6D2C4;
    /** El naranja de la resina. */
    private static final int RESINA = 0xE07A2E;
    /** El verde de lo que es seguro pisar. */
    private static final int SEGURO = 0x9FD6A0;

    /** Lo que la Mirada del Jardin cobra al que no estaba mirando: parte de su vida maxima. */
    private static final double MIRADA_FRACCION = 0.60;
    /** Ticks de margen de la Mirada: vale haberlo mirado hace tan poco (lag). */
    private static final int MIRADA_GRACIA = 10;

    // --- ids de los hechizos (AnomalyRegistry.RaizType)
    public static final String MIRADA = "rz_mirada_jardin";
    public static final String ESTALLIDO = "rz_estallido_corazon";

    private static final double TAU = Math.PI * 2;
    /** Tolerancia vertical de todos los golpes de area, como el Conde. */
    private static final double ALTO = 2.5;

    // --- la mirada
    /** Mientras dura (3 s como mucho), el jefe no recibe dano y los jugadores tampoco. */
    private boolean combateCongelado;
    /** La Mirada esta en marcha (aviso incluido): la flor espera a que acabe. */
    private boolean miradaEnCurso;
    /** La Mirada ya salio en esta fase. */
    private boolean miradaHecha;
    /** Tick en que empezo la fase en curso. */
    private long faseDesde;

    // --- el ritual de la flor
    /** El ritual esta en marcha. */
    private boolean ritualActivo;
    /** El ritual termino BIEN: el siguiente cambio de fase (y solo ese) esta permitido. */
    private boolean ritualCumplido;
    /** La fase a la que lleva el ritual en curso: siempre la siguiente. */
    private int faseDestino;
    /** Hasta que tick no se abre otro ritual tras uno fallido. */
    private long ritualNoAntesDe;
    /** Hasta que tick no recibe dano tras abrirse la flor (20 s como mucho). */
    private long ritualInvulnerableHasta;
    /** Dosis que lleva la flor y las que pide. */
    private int dosis;
    private int dosisPedidas;
    /** Las piezas de la flor, para limpiarlas cuando acabe. */
    private final List<Entity> flor = new ArrayList<>();
    private Location florEn;
    /** Lo que habia donde se levanto un pilar provisional, para devolverlo al cerrar. */
    private final Map<Location, BlockData> pilarPrestado = new java.util.LinkedHashMap<>();
    /** Hasta que tick dura el ritual, para decir cuanto queda. */
    private long ritualHasta;
    /** Ultimo tick en que un hechizo escribio su cuenta atras: la flor no la pisa. */
    private long ultimaCuenta = -100;

    /** Donde flota: el punto del suelo sobre el que oscila. */
    private Location flotaBase;
    /** Multiplicador de dano de la fase en curso. */
    private double damageBonus = 1.0;
    /** La pelea se cerro: las animaciones en curso se cortan. */
    private boolean cerrado;

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

    /** Tres fases: Brote, El Jardin se cierra y Corazon Palido. */
    @Override
    public int phaseCount() {
        return 3;
    }

    /** Las barras de fase de siempre (PhaseBars), una por fase. */
    @Override
    public boolean usesOwnBars() {
        return false;
    }

    // ------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        Location at = arena.clone();

        /* El que pelea: un zombi invisible. A ras de suelo de verdad: el punto de
         * apertura puede venir a media altura y flotar() lo dejaria ahi. */
        flotaBase = Fx.ground(at, 8);
        boss = world().spawn(flotaBase.clone().add(0, 1.4, 0), Zombie.class, z -> {
            z.setPersistent(false);
            z.setShouldBurnInDay(false);
            z.setInvisible(true);
            z.setSilent(true);
            z.setBaby(false);
            // Sin IA y sin gravedad: no camina ni pega. Todo su repertorio son hechizos.
            z.setAI(false);
            z.setGravity(false);
            Compat.setAttribute(z, "scale", 2.0);
            Compat.setAttribute(z, "knockback_resistance", 1.0);
            Compat.setAttribute(z, "follow_range", 64);
            if (z.getEquipment() != null) z.getEquipment().clear();
            // Con nombre aunque sea invisible: es lo que leen los mensajes de muerte.
            z.customName(Component.text("ROTTEN", ACCENT));
            z.setCustomNameVisible(false);
        });
        net.ederus.edm.comun.Tags.markBoss(boss, ID);
        /* Como el resto del catalogo (el Conde, 8). Con los 20 de serie, el golpe de un
         * segundo jugador dentro del medio segundo siguiente se perdia casi entero: con
         * varios pegando, ROTTEN parecia no recibir dano. */
        boss.setMaximumNoDamageTicks(6);

        /* El que se ve: el creaking, al doble, sin IA (lo lleva tickShell del padre).
         * NUNCA invulnerable: vanilla no deja pegar a una entidad invulnerable a nadie
         * en supervivencia. Lo protegen onDamage y onEnvironmentalDamage del manager. */
        Creaking cuerpo = world().spawn(at, Creaking.class, c -> {
            c.setPersistent(false);
            c.setAI(false);
            c.setGravity(false);
            c.setSilent(false);
            c.setCollidable(false);
            Compat.setAttribute(c, "scale", 2.0);
            c.customName(Component.text("ROTTEN", ACCENT));
            c.setCustomNameVisible(false);
        });
        markMinion(cuerpo);
        shell = cuerpo;

        applyHealth(plugin.registry().health(plugin.registry().get(ID)));
        glowBody(NamedTextColor.DARK_GREEN);
        faseDesde = 0;

        Compat.spawn(world(), Compat.CHERRY_LEAVES, at.clone().add(0, 1.6, 0), 40, 1.6, 1.4, 1.6, 0.04);
        Fx.ring(at.clone().add(0, 0.2, 0), 4.5, 30, p ->
                Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(CORTEZA, 1.6f)));
        soundAt(at, "entity.creaking.spawn", 1.8f, 0.6f);
        soundAt(at, "block.creaking_heart.spawn", 1.6f, 0.7f);
        titleNear(Component.text("ROTTEN", ACCENT),
                Component.text("El jardín despierta", NamedTextColor.GRAY));
    }

    @Override
    protected void onPhaseChange(int from, int to) {
        ritualCumplido = false;
        miradaHecha = false;
        faseDesde = ticks();
        damageBonus = to >= 3 ? 1.3 : to == 2 ? 1.15 : 1.0;
        Location c = suelo();
        Fx.shockwave(world(), c, 7, Compat.CHERRY_LEAVES, 2);
        soundAt(c, "block.creaking_heart.break", 1.7f, 0.6f);
        soundAt(c, "entity.creaking.activate", 1.5f, 0.6f);
        titleNear(Component.text("FASE " + romano(to), ACCENT),
                Component.text(nombreDeFase(to), NamedTextColor.GRAY));
        busyFor(30);
    }

    private static String romano(int fase) {
        return fase == 2 ? "II" : fase >= 3 ? "III" : "I";
    }

    private static String nombreDeFase(int fase) {
        return switch (fase) {
            case 2 -> "El jardín se cierra";
            case 3 -> "Corazón pálido";
            default -> "Brote";
        };
    }

    @Override
    public void onDeath() {
        Location c = center();
        combateCongelado = false;
        limpiarFlor();
        animate(90, tick -> {
            if (tick % 6 == 0) {
                Fx.ring(c.clone().add(0, 0.4 + tick * 0.05, 0), 1.5 + tick * 0.09, 20, p ->
                        Compat.spawn(world(), Compat.CHERRY_LEAVES, p, 1, 0.1, 0.1, 0.1, 0.02));
            }
            if (tick % 18 == 0) soundAt(c, "entity.creaking.death", 1.4f, 0.6f + tick * 0.004f);
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
        sostenerSuelo();

        // Latido del corazon palido: se acelera al perder vida.
        int pulso = 30 + (int) (30 * healthFraction());
        if (ticks() % Math.max(10, pulso) == 0) {
            soundAt(loc(), "block.creaking_heart.idle", 1.1f, 0.7f);
            Compat.spawn(world(), Compat.DUST, center().add(0, 1.4, 0), 3, 0.5, 0.6, 0.5, 0,
                    Compat.dust(AMBAR, 1.3f));
        }
        if (ticks() % 14 == 0) {
            Compat.spawn(world(), Compat.CHERRY_LEAVES, center().add(0, 2.2, 0), 3, 1.8, 0.8, 1.8, 0.01);
        }

        if (ritualActivo) {
            tickRitual();
            return;
        }
        // La flor se abre en cuanto la vida toca el umbral de la fase (y no en mitad de la Mirada).
        if (phase() < phaseCount() && !ritualCumplido && !combateCongelado && !miradaEnCurso
                && ticks() >= ritualNoAntesDe && healthFraction() <= umbral(phase()) + 0.0035) {
            abrirRitual();
        }
    }

    /** El cambio de fase de 1 en 1, y solo cuando la flor se cumplio. */
    @Override
    protected boolean canChangePhase(int from, int to) {
        return ritualCumplido && to == from + 1 && to == faseDestino;
    }

    /**
     * La fraccion de vida por debajo de la cual no puede bajar: el umbral de su fase
     * mientras la flor no se cumpla, y el de la fase siguiente cuando ya se cumplio
     * (el cambio salta en el tick siguiente). En la ultima fase no hay suelo.
     */
    @Override
    public double survivalFloor() {
        if (!alive()) return 0;
        if (phase() >= phaseCount()) return 0;
        double u = umbral(phase() + (ritualCumplido ? 1 : 0));
        /* Flor cumplida en la II: el cambio a la III salta en el tick siguiente, y sin
         * este suelo un golpe gordo en ese hueco lo mataba en la II. */
        if (u <= 0) return 0.02;
        return u + 0.001;
    }

    /** Ultimo tick en que se le marco el golpe al cuerpo visible. */
    private long ultimoGolpeVisto = -100;

    /**
     * El golpe se le pasa al zombi invisible y el creaking no se inmutaba: parecia que
     * no recibia dano. Ahora el cuerpo que se ve acusa cada golpe que de verdad baja vida.
     */
    @Override
    public void onDamaged(Player attacker, double amount) {
        if (!alive() || amount <= 0 || shell == null || !shell.isValid()) return;
        if (incomingDamageMultiplier() <= 0) return;
        if (healthFraction() <= survivalFloor() + 0.0005) return;
        if (ticks() - ultimoGolpeVisto < 4) return;
        ultimoGolpeVisto = ticks();
        try {
            shell.playHurtAnimation(0);
        } catch (Throwable ignored) {
        }
        soundAt(shell.getLocation(), "entity.creaking.sway", 1.0f, 0.8f + random.nextFloat() * 0.2f);
    }

    /** El umbral de vida que cierra una fase: 2/3 la I, 1/3 la II, nada la III. */
    private double umbral(int fase) {
        int fases = phaseCount();
        if (fase >= fases) return 0;
        return (fases - fase) / (double) fases;
    }

    /**
     * El suelo tambien para la vida que no pasa por un golpe (un setHealth de otro
     * plugin o de /anomaly hurt): si cae por debajo del umbral sin flor, vuelve a el.
     */
    private void sostenerSuelo() {
        double suelo = survivalFloor();
        if (suelo <= 0) return;
        double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
        if (boss.getHealth() < max * suelo - 0.01) boss.setHealth(Math.min(max, max * suelo));
    }

    /** El jefe no recibe dano durante la Mirada (3 s) ni los primeros 20 s de la flor. */
    @Override
    public double incomingDamageMultiplier() {
        if (combateCongelado) return 0;
        if (ritualActivo && ticks() < ritualInvulnerableHasta) return 0;
        return 1.0;
    }

    /** Mientras la Mirada congela el combate, sus hechizos no pegan. */
    @Override
    public void hit(Player p, double amount, double abilityMult) {
        if (combateCongelado) return;
        super.hit(p, amount, abilityMult);
    }

    /**
     * Que hechizos puede elegir: los de la fase I siguen en la II; la Mirada sale una
     * vez por fase y no antes de 15 s dentro de ella; durante la flor no hay Mirada ni
     * Estallido (la flor ya es bastante).
     */
    @Override
    protected boolean puedeLanzar(Ability a, int fase) {
        if (a.id().equals(MIRADA)) {
            return a.availableIn(fase) && !miradaHecha && !ritualActivo && ticks() - faseDesde >= 300;
        }
        if (ritualActivo && a.id().equals(ESTALLIDO)) return false;
        if (a.phase() == 1 && fase == 2) return true;
        return a.availableIn(fase);
    }

    /** El dano de la fase: 1,0 / 1,15 / 1,3. */
    private double power() {
        return damageBonus;
    }

    /** Cura vida "de diseno", con el reescalado del tope de 1024 y sin salirse de la fase. */
    private void curar(double vida) {
        if (!alive() || vida <= 0) return;
        boss.setHealth(Math.min(topeDeFase(), boss.getHealth() + vida * damageScale()));
    }

    /** Hasta donde puede curarse sin volver a la barra de la fase anterior. */
    private double topeDeFase() {
        double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
        int fases = Math.max(1, phaseCount());
        double techo = (fases - phase() + 1) / (double) fases;
        if (phase() > 1) techo -= 0.002;
        return Math.max(boss.getHealth(), max * Fx.clamp(techo, 0, 1));
    }

    // =========================================================================
    //  LA MIRADA DEL JARDIN
    // =========================================================================

    /**
     * Una vez por fase. Un segundo de aviso y tres de mirada: quien no lo haya tenido en
     * el puntero en el ultimo medio segundo pierde el 60 % de su vida maxima, sin armadura
     * (solo mata a quien ya estaba por debajo de eso). Durante esos tres segundos ni el
     * jefe recibe dano ni sus hechizos pegan; los jugadores llevan Resistencia.
     */
    public void miradaDelJardin() {
        if (!alive() || ritualActivo) return;
        miradaHecha = true;
        miradaEnCurso = true;
        Location c = center();
        final int aviso = 20;
        final int fin = 80;
        /* Ultimo tick en que cada jugador lo tenia en el puntero. Con lag, la direccion
         * que ve el servidor llega tarde: se da por bueno haberlo mirado hace poco. */
        final Map<UUID, Long> ultimaVez = new java.util.HashMap<>();
        titleNear(Component.text("MÍRENLO", ACCENT),
                Component.text("Quien aparte la vista pierde el 60 % de su vida", NamedTextColor.RED));
        soundAt(c, "entity.creaking.activate", 2.0f, 0.5f);

        animate(fin, t -> {
            if (cerrado || !alive()) throw Stop.now();
            if (t == aviso) {
                combateCongelado = true;
                for (Player p : targets()) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, fin - aviso + 4, 4, true, false, false));
                }
            }
            if (t >= fin - MIRADA_GRACIA - 2) {
                for (Player p : targets()) if (mirandoAlJefe(p)) ultimaVez.put(p.getUniqueId(), ticks());
            }
            if (t % 4 == 0) {
                double r = 9.0 * (1 - t / (double) fin) + 1.5;
                Fx.ring(c.clone().add(0, 0.3, 0), r, 24, p ->
                        Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(AMBAR, 1.4f)));
                String quedan = segundos(fin - t);
                marcarCuenta();
                for (Player p : targets()) {
                    boolean mirando = mirandoAlJefe(p);
                    p.sendActionBar(mirando
                            ? Component.text("Lo estás mirando. " + quedan + " s", ACCENT)
                            : Component.text("MÍRALO. " + quedan + " s", NamedTextColor.RED));
                }
            }
            if (t % 20 == 0) soundAt(c, "block.creaking_heart.idle", 1.6f, 0.5f + t * 0.006f);
        }, () -> {
            boolean juzgar = combateCongelado && alive() && !cerrado;
            combateCongelado = false;
            miradaEnCurso = false;
            if (!juzgar) return;
            int caidos = 0;
            for (Player p : targets()) {
                if (mirandoAlJefe(p)) continue;
                Long visto = ultimaVez.get(p.getUniqueId());
                if (visto != null && ticks() - visto <= MIRADA_GRACIA) continue;
                caidos++;
                // Dano puro: es una sentencia y no pasa por el multiplicador ni la armadura.
                double max = Compat.getAttribute(p, "max_health", 20);
                double queda = p.getHealth() - max * MIRADA_FRACCION;
                if (queda <= 0) event.markKill(p);
                else event.markHit(p);
                p.setHealth(Math.max(0, Math.min(max, queda)));
                Compat.spawn(world(), Compat.EXPLOSION, p.getLocation().add(0, 1, 0), 2, 0.3, 0.5, 0.3, 0);
                soundAt(p.getLocation(), "entity.creaking.attack", 1.6f, 0.5f);
            }
            soundAt(c, "entity.creaking.deactivate", 1.8f, 0.6f);
            announce(caidos == 0
                    ? Component.text("Todos aguantaron la mirada.")
                    : Component.text("Apartaron la vista: " + caidos + "."));
        });
    }

    /**
     * Si el jugador lo tiene EN EL PUNTERO: un rayo desde el ojo en la direccion en que
     * mira contra la caja del cuerpo, agrandada con la distancia (unos tres grados).
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

    // =========================================================================
    //  LA FLOR
    // =========================================================================

    /** Se derrumba y planta la flor. Los primeros 20 s no recibe dano. */
    private void abrirRitual() {
        ritualActivo = true;
        ritualCumplido = false;
        faseDestino = phase() + 1;
        dosis = 0;
        int peleando = Math.max(1, targets().size());
        dosisPedidas = 3 + 2 * peleando;
        ritualInvulnerableHasta = ticks() + 20 * 20;
        ritualHasta = ticks() + 20 * 45;

        Location c = suelo();
        /* Limpiar primero y fijar florEn DESPUES de plantar: al reves, limpiarFlor()
         * dejaba florEn en null y el ritual se cerraba como fallido al tick siguiente. */
        limpiarFlor();
        Location vidrio = pilar(c);
        plantarFlor(vidrio);
        florEn = vidrio.clone().add(0.5, 0.5, 0.5);

        titleNear(Component.text("LA FLOR", ACCENT),
                Component.text("Tiren " + dosisPedidas + " pociones de curación arrojadizas al pilar",
                        NamedTextColor.GRAY));
        announce(Component.text("La flor pide " + dosisPedidas + " pociones de curación arrojadizas. Tienen 45 s."));
        soundAt(c, "entity.creaking.deactivate", 2.0f, 0.5f);
        soundAt(florEn, "block.eyeblossom.open_long", 1.6f, 0.8f);
    }

    /**
     * El pilar de la flor: el que marco el admin con /anomaly pillar (el bloque de
     * vidrio) o, si no hay, uno provisional junto a ROTTEN que se devuelve al cerrar.
     * Tiene que ser un bloque de verdad: la pocion revienta contra el vidrio.
     */
    private Location pilar(Location c) {
        Location marcado = plugin.registry().flowerPillar(event.type());
        if (marcado != null && marcado.getWorld() == world() && marcado.distanceSquared(c) < 120 * 120) {
            return marcado.getBlock().getLocation();
        }
        Location suelo = Fx.ground(c.clone().add(5.0, 0, 0), 8);
        org.bukkit.block.Block base = suelo.getBlock();
        if (base.getType().isSolid()) base = base.getRelative(org.bukkit.block.BlockFace.UP);
        org.bukkit.block.Block vidrio = base.getRelative(org.bukkit.block.BlockFace.UP);
        prestar(base, Material.CHISELED_STONE_BRICKS);
        prestar(vidrio, Material.GLASS);
        return vidrio.getLocation();
    }

    private void prestar(org.bukkit.block.Block b, Material m) {
        pilarPrestado.putIfAbsent(b.getLocation(), b.getBlockData().clone());
        b.setType(m, false);
    }

    /** Una eyeblossom abierta dentro del vidrio y una columna de luz para encontrarla. */
    private void plantarFlor(Location vidrio) {
        Location centro = vidrio.clone().add(0.5, 0.5, 0.5);
        BlockDisplay corola = Fx.blockDisplay(world(), centro, Material.OPEN_EYEBLOSSOM, 0.85f);
        if (corola != null) {
            markMinion(corola);
            flor.add(corola);
            Glow.apply(corola, NamedTextColor.GREEN);
        }
        BlockDisplay luz = Fx.lightColumn(world(), vidrio.clone().add(0.5, 1.0, 0.5),
                Material.LIME_STAINED_GLASS, 0.45f, 40f);
        if (luz != null) {
            markMinion(luz);
            flor.add(luz);
        }
    }

    /**
     * Una pocion revento en la arena. Cuenta si es de curacion, ARROJADIZA y cayo a 2,5
     * bloques o menos del centro del vidrio. Cada una vale una dosis, sea I o II.
     */
    @Override
    public void onPotionSplash(ThrownPotion pocion, Location donde) {
        if (!ritualActivo || florEn == null || donde.getWorld() != world()) return;
        if (donde.distanceSquared(florEn) > 2.5 * 2.5) return;
        if (!esCuracionArrojadiza(pocion.getItem())) return;
        dosis++;
        Compat.spawn(world(), Compat.HEART, florEn.clone().add(0, 0.8, 0), 10, 0.6, 0.5, 0.6, 0.03);
        Compat.spawn(world(), Compat.HAPPY_VILLAGER, florEn, 12, 0.6, 0.8, 0.6, 0.02);
        soundAt(florEn, "block.eyeblossom.open_long", 1.4f, 0.9f + Math.min(dosis, 12) * 0.05f);
        soundAt(florEn, "entity.player.levelup", 0.8f, 1.6f);
        announce(Component.text("La flor lleva " + Math.min(dosis, dosisPedidas) + " de " + dosisPedidas + "."));
        if (dosis >= dosisPedidas) cerrarRitual(true);
    }

    /** Un tick del ritual: pinta cuanto lleva y lo cierra si se acaba el plazo. */
    private void tickRitual() {
        if (florEn == null) {
            cerrarRitual(false);
            return;
        }
        if (ticks() % 5 == 0) {
            double r = 2.0 + 3.0 * Math.min(1.0, dosis / (double) dosisPedidas);
            Fx.ring(florEn.clone().add(0, -1.3, 0), r, 22, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(AMBAR, 1.4f)));
            Compat.spawn(world(), Compat.END_ROD, florEn.clone().add(0, 1 + random.nextDouble() * 9, 0), 2,
                    0.3, 0.6, 0.3, 0.01);
        }
        if (ticks() % 20 == 0 && ticks() - ultimaCuenta > 12) {
            int quedan = (int) Math.max(0, (ritualHasta - ticks()) / 20);
            Component linea = Component.text("LA FLOR  " + Math.min(dosis, dosisPedidas) + " / " + dosisPedidas, ACCENT)
                    .append(Component.text("   " + quedan + " s", NamedTextColor.GRAY));
            for (Player p : targets()) p.sendActionBar(linea);
        }
        if (ticks() % 40 == 0) soundAt(florEn, "block.creaking_heart.idle", 1.4f, 1.3f);
        if (ticks() >= ritualHasta) cerrarRitual(false);
    }

    /**
     * Cierra el ritual. Cumplido: el rayo de la flor y la fase siguiente en el tick que
     * viene. Fallido: se cura un 10 % sin salirse de su fase y la flor vuelve en 20 s.
     */
    private void cerrarRitual(boolean cumplido) {
        ritualActivo = false;
        ritualInvulnerableHasta = 0;
        if (cumplido) {
            ritualCumplido = true;
            /* La vida se deja justo por debajo del umbral: con la fraccion exacta en el
             * umbral, PhaseBars.currentPhase puede seguir diciendo la fase vieja. */
            double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
            double bajo = max * (umbral(phase()) - 0.004);
            if (bajo > 0 && boss.getHealth() > bajo) boss.setHealth(bajo);
            Location desde = florEn == null ? center() : florEn.clone();
            Location hasta = center().add(0, 1.4, 0);
            Fx.beam(desde, hasta, 0.4, p ->
                    Compat.spawn(world(), Compat.DUST, p, 1, 0.05, 0.05, 0.05, 0,
                            Compat.dust(0xFFFFFF, 1.5f)));
            Compat.spawn(world(), Compat.FLASH, hasta, 1);
            soundAt(hasta, "block.beacon.activate", 1.8f, 1.2f);
            announce(Component.text("La flor se abrió. ROTTEN cambia."));
            titleNear(Component.empty(), Component.text("LA FLOR SE ABRIÓ", ACCENT));
        } else {
            double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
            double vidaDiseno = damageScale() > 0 ? max / damageScale() : max;
            curar(vidaDiseno * 0.10);
            Compat.spawn(world(), Compat.HEART, center().add(0, 1.6, 0), 16, 1.0, 1.0, 1.0, 0.04);
            soundAt(center(), "entity.creaking.activate", 1.8f, 0.5f);
            ritualNoAntesDe = ticks() + 20 * 20;
            announce(Component.text("La flor se marchitó. Se cura y la flor vuelve en 20 s."));
            titleNear(Component.text("SE CURA", TextColor.color(RESINA)),
                    Component.text("La flor no aguantó", NamedTextColor.GRAY));
        }
        limpiarFlor();
    }

    /** Solo la pocion de curacion arrojadiza (I o II). La persistente y la bebible no. */
    private static boolean esCuracionArrojadiza(ItemStack item) {
        if (item == null || item.getType() != Material.SPLASH_POTION) return false;
        if (!(item.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta pm)) return false;
        String base = pm.getBasePotionType() == null ? "" : pm.getBasePotionType().name();
        if (base.contains("HEALING")) return true;
        for (PotionEffect e : pm.getCustomEffects()) {
            if (e.getType().equals(PotionEffectType.INSTANT_HEALTH)) return true;
        }
        return false;
    }

    private void limpiarFlor() {
        for (Entity e : flor) {
            // Fuera del equipo de brillo ANTES de quitarla: si no, su UUID se queda en el marcador.
            Glow.clear(e);
            spawned.remove(e);
            Fx.safeRemove(e);
        }
        flor.clear();
        florEn = null;
        // El pilar provisional se devuelve tal cual estaba; el marcado no se toca.
        pilarPrestado.forEach((l, data) -> l.getBlock().setBlockData(data, false));
        pilarPrestado.clear();
    }

    // ------------------------------------------------------------------- flotar

    /** El punto del suelo bajo el jefe: todos los hechizos de suelo salen de aqui. */
    private Location suelo() {
        return flotaBase != null ? flotaBase.clone() : Fx.ground(loc(), 6);
    }

    /** Flota clavado en su sitio con un vaiven suave y de cara al mas cercano. */
    private void flotar() {
        if (flotaBase == null || boss == null || !boss.isValid()) return;
        double alto = ritualActivo ? 0.1 : 1.4 + Math.sin(ticks() / 14.0) * 0.25;
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

    // =========================================================================
    //  FASE I  ·  BROTE
    // =========================================================================

    /**
     * Raices del Jardin: un sello de radio 3 bajo CADA jugador; a 1,5 s brotan raices
     * que pegan y lanzan hacia arriba. Esquiva: salir del circulo.
     */
    public void raicesDelJardin() {
        if (!alive()) return;
        final List<Player> objetivos = targets();
        if (objetivos.isEmpty()) return;
        final double radio = 3.0;
        final int estalla = 30;
        final List<Location> sellos = new ArrayList<>();
        for (Player p : objetivos) sellos.add(sinGiro(Fx.ground(p.getLocation(), 6)));
        final Set<UUID> golpeados = new HashSet<>();
        final double mult = abilityDamage();
        final Escena esc = new Escena();
        announce(Component.text("Raíces del Jardín. Salgan del círculo."));
        soundAt(suelo(), "block.roots.place", 1.6f, 0.6f);

        animate(estalla + 26, t -> {
            if (cerrado || !alive()) throw Stop.now();
            if (t < estalla && t % 4 == 0) {
                for (Location s : sellos) aviso(s, radio, CORTEZA, t, estalla);
                cuenta(objetivos, "Raíces bajo tus pies. Sal del círculo:", estalla - t);
            }
            if (t == estalla) {
                for (Location s : sellos) {
                    for (int i = 0; i < 6; i++) {
                        double a = TAU * i / 6 + random.nextDouble() * 0.6;
                        double r = i == 0 ? 0 : 0.8 + random.nextDouble() * (radio - 1.2);
                        Location b = s.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
                        brote(esc, b, Material.PALE_OAK_LOG, 0.45 + random.nextDouble() * 0.2,
                                2.2 + random.nextDouble() * 1.2, (random.nextDouble() - 0.5) * 0.5, 18);
                    }
                    Compat.spawn(world(), Compat.BLOCK, s.clone().add(0, 0.3, 0), 14, 1.2, 0.2, 1.2, 0,
                            Material.PALE_OAK_LOG.createBlockData());
                    soundAt(s, "block.roots.break", 1.5f, 0.6f);
                    for (Player v : Fx.playersNear(s, radio + 2)) {
                        if (!dentro(v, s, radio)) continue;
                        if (!golpeados.add(v.getUniqueId())) continue;
                        hit(v, 18 * power(), mult);
                        push(v, new Vector(0, 0.85, 0));
                    }
                }
            }
        }, esc::quitar);
    }

    /**
     * Anillo de Espinas: una onda de espinas sale de ROTTEN por el suelo hasta el borde
     * de la arena. Pega a quien este en el suelo cuando pasa el frente. Esquiva: saltar.
     */
    public void anilloDeEspinas() {
        if (!alive()) return;
        final Location c = sinGiro(suelo());
        final double R = radioArena();
        final int carga = 30;
        final double v = Math.max(0.55, R / 55.0);
        final int dura = carga + (int) Math.ceil(R / v) + 2;
        final Set<UUID> golpeados = new HashSet<>();
        final double mult = abilityDamage();
        announce(Component.text("Anillo de Espinas. Salten cuando pase la onda."));
        soundAt(c, "block.creaking_heart.hurt", 1.6f, 0.5f);

        animate(dura, t -> {
            if (cerrado || !alive()) throw Stop.now();
            if (t < carga) {
                if (t % 4 == 0) {
                    aviso(c, 3.0 + 2.0 * t / carga, CORTEZA, t, carga);
                    cuenta(targets(), "Onda de espinas. Salta cuando llegue:", carga - t);
                }
                return;
            }
            double r = (t - carga) * v;
            if (r > R) return;
            frente(c, r, CORTEZA, t % 2 == 0);
            if (t % 6 == 0) soundAt(c, "block.pale_hanging_moss.idle", 1.0f, 0.6f);
            double banda = Math.max(0.7, v * 0.8);
            for (Player p : targets()) {
                if (golpeados.contains(p.getUniqueId())) continue;
                double d = plano(p.getLocation(), c);
                if (Math.abs(d - r) > banda) continue;
                if (Math.abs(p.getLocation().getY() - c.getY()) > ALTO + 1) continue;
                if (enElAire(p)) continue;
                golpeados.add(p.getUniqueId());
                hit(p, 16 * power(), mult);
                push(p, fuera(p.getLocation(), c).multiply(0.5).setY(0.25));
            }
            if (t % 4 == 0) {
                for (Player p : targets()) {
                    if (golpeados.contains(p.getUniqueId())) continue;
                    double falta = (plano(p.getLocation(), c) - r) / v;
                    if (falta > 0 && falta < 30) {
                        p.sendActionBar(Component.text("Onda de espinas. Salta en " + segundos((int) falta) + " s", ACCENT));
                    }
                }
                marcarCuenta();
            }
        }, null);
    }

    /**
     * Lluvia de Resina: 8 a 12 marcas alrededor de los jugadores; a 1,5 s cae un bloque
     * de resina en cada una y deja un charco 3 s que ralentiza. Esquiva: salir de la marca.
     */
    public void lluviaDeResina() {
        if (!alive()) return;
        final List<Player> objetivos = targets();
        if (objetivos.isEmpty()) return;
        final double radio = 2.2;
        final int cae = 30;
        final int caida = 20;
        final int charco = 60;
        int total = Math.max(objetivos.size(), (int) Fx.clamp(4 + 2 * objetivos.size(), 8, 12));
        final List<Location> marcas = new ArrayList<>();
        for (Player p : objetivos) marcas.add(sinGiro(Fx.ground(p.getLocation(), 6)));
        while (marcas.size() < total) {
            Player p = objetivos.get(random.nextInt(objetivos.size()));
            double a = random.nextDouble() * TAU;
            double r = 2.5 + random.nextDouble() * 3.5;
            marcas.add(sinGiro(Fx.ground(p.getLocation().add(Math.cos(a) * r, 0, Math.sin(a) * r), 6)));
        }
        final Set<UUID> golpeados = new HashSet<>();
        final double mult = abilityDamage();
        final Escena esc = new Escena();
        final List<BlockDisplay> gotas = new ArrayList<>();
        final List<BlockDisplay> charcos = new ArrayList<>();
        announce(Component.text("Lluvia de Resina. Salgan de las marcas naranjas."));
        soundAt(suelo(), "block.resin.place", 1.6f, 0.6f);

        animate(cae + charco, t -> {
            if (cerrado || !alive()) throw Stop.now();
            if (t < cae && t % 4 == 0) {
                for (Location m : marcas) aviso(m, radio, RESINA, t, cae);
                cuenta(objetivos, "Cae resina. Sal de la marca naranja:", cae - t);
            }
            if (t == cae - caida) {
                for (Location m : marcas) {
                    BlockDisplay g = esc.add(pieza(m.clone().add(0, 14, 0), Material.RESIN_BLOCK.createBlockData(),
                            caja(new Quaternionf().rotateY((float) (random.nextDouble() * TAU)),
                                    1.2, 1.2, 1.2, -0.6, 0, -0.6), 1));
                    gotas.add(g);
                }
            }
            if (t > cae - caida && t < cae) {
                double u = (t - (cae - caida)) / (double) caida;
                for (int i = 0; i < gotas.size(); i++) {
                    BlockDisplay g = gotas.get(i);
                    if (g == null || !g.isValid()) continue;
                    try {
                        g.teleport(marcas.get(i).clone().add(0, 14 * (1 - u * u), 0));
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (t == cae) {
                for (BlockDisplay g : gotas) {
                    if (g == null) continue;
                    spawned.remove(g);
                    Fx.safeRemove(g);
                }
                for (Location m : marcas) {
                    Compat.spawn(world(), Compat.BLOCK, m.clone().add(0, 0.4, 0), 12, 0.8, 0.2, 0.8, 0,
                            Material.RESIN_BLOCK.createBlockData());
                    charcos.add(esc.add(pieza(m.clone().add(0, 0.02, 0), Material.RESIN_BLOCK.createBlockData(),
                            caja(new Quaternionf(), radio * 1.6, 0.06, radio * 1.6, -radio * 0.8, 0, -radio * 0.8), 0)));
                    for (Player v : Fx.playersNear(m, radio + 2)) {
                        if (!dentro(v, m, radio)) continue;
                        if (!golpeados.add(v.getUniqueId())) continue;
                        hit(v, 14 * power(), mult);
                    }
                }
                soundAt(suelo(), "block.resin.break", 1.8f, 0.6f);
            }
            if (t > cae && t % 10 == 0) {
                for (Location m : marcas) {
                    for (Player v : Fx.playersNear(m, radio + 2)) {
                        if (!dentro(v, m, radio)) continue;
                        v.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 25, 2, true, false, false));
                    }
                    Compat.spawn(world(), Compat.DUST, m.clone().add(0, 0.15, 0), 2, radio * 0.5, 0.02, radio * 0.5, 0,
                            Compat.dust(RESINA, 1.2f));
                }
            }
        }, esc::quitar);
    }

    /**
     * Lanzas Palidas: una linea desde ROTTEN hacia cada jugador; a 1,2 s una lanza de
     * raiz la recorre entera. Esquiva: salir de la linea.
     */
    public void lanzasPalidas() {
        if (!alive()) return;
        final List<Player> objetivos = targets();
        if (objetivos.isEmpty()) return;
        final Location c = sinGiro(suelo());
        final double R = radioArena();
        final int sale = 24;
        final double v = 1.5;
        final double ancho = 1.4;
        final List<Vector> dirs = new ArrayList<>();
        final List<Double> largos = new ArrayList<>();
        for (Player p : objetivos) {
            Vector d = p.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (d.lengthSquared() < 0.25) d = new Vector(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5);
            double largo = Fx.clamp(d.length() + 8, 12, R + 4);
            dirs.add(d.normalize());
            largos.add(largo);
        }
        double maxLargo = 0;
        for (double l : largos) maxLargo = Math.max(maxLargo, l);
        final int dura = sale + (int) Math.ceil(maxLargo / v) + 4;
        final Set<UUID> golpeados = new HashSet<>();
        final double mult = abilityDamage();
        final Escena esc = new Escena();
        final List<BlockDisplay> lanzas = new ArrayList<>();
        announce(Component.text("Lanzas Pálidas. Salgan de la línea."));
        soundAt(c, "entity.creaking.twitch", 1.6f, 0.6f);

        animate(dura, t -> {
            if (cerrado || !alive()) throw Stop.now();
            if (t < sale && t % 4 == 0) {
                for (int i = 0; i < dirs.size(); i++) linea(c, dirs.get(i), largos.get(i), ancho, CORTEZA);
                cuenta(objetivos, "Una lanza va por tu línea. Muévete a un lado:", sale - t);
            }
            if (t == sale) {
                for (Vector d : dirs) {
                    float yaw = (float) Math.atan2(-d.getZ(), d.getX());
                    Quaternionf q = new Quaternionf().rotateY(yaw);
                    lanzas.add(esc.add(pieza(c.clone().add(0, 0.9, 0), Material.PALE_OAK_LOG.createBlockData(),
                            caja(q, 3.4, 0.7, 0.7, -3.4, -0.35, -0.35), 1)));
                }
                soundAt(c, "block.roots.break", 1.8f, 0.5f);
            }
            if (t >= sale) {
                double recorrido = (t - sale) * v;
                for (int i = 0; i < dirs.size(); i++) {
                    if (recorrido > largos.get(i) + 1) continue;
                    Vector d = dirs.get(i);
                    Location punta = c.clone().add(d.clone().multiply(Math.min(recorrido, largos.get(i))));
                    BlockDisplay l = lanzas.size() > i ? lanzas.get(i) : null;
                    if (l != null && l.isValid()) {
                        try {
                            l.teleport(punta.clone().add(0, 0.9, 0));
                        } catch (Throwable ignored) {
                        }
                    }
                    if (t % 2 == 0) Compat.spawn(world(), Compat.BLOCK, punta.clone().add(0, 0.3, 0), 3, 0.3, 0.1, 0.3, 0,
                            Material.PALE_OAK_LOG.createBlockData());
                    Location cola = c.clone().add(d.clone().multiply(Math.max(0, recorrido - 3.4)));
                    for (Player p : Fx.playersNear(punta, 6)) {
                        if (golpeados.contains(p.getUniqueId())) continue;
                        if (Math.abs(p.getLocation().getY() - c.getY()) > ALTO + 1) continue;
                        if (distSegmento(p.getLocation(), cola, punta) > ancho) continue;
                        golpeados.add(p.getUniqueId());
                        hit(p, 20 * power(), mult);
                        push(p, d.clone().multiply(0.9).setY(0.35));
                    }
                }
            }
        }, esc::quitar);
    }

    // =========================================================================
    //  FASE II  ·  EL JARDIN SE CIERRA
    // =========================================================================

    /**
     * El Jardin se Cierra: dos anillos de raices que se contraen desde el borde hacia
     * ROTTEN, cada uno con un hueco marcado en verde. Esquiva: estar en el hueco cuando
     * pase el anillo.
     */
    public void elJardinSeCierra() {
        if (!alive()) return;
        final Location c = sinGiro(suelo());
        final double R = radioArena();
        final int carga = 30;
        final int[] inicio = {carga, carga + 40};
        final int viaje = 70;
        final double[] hueco = {random.nextDouble() * TAU, 0};
        hueco[1] = hueco[0] + Math.PI * (0.6 + random.nextDouble() * 0.8);
        final double medio = Math.toRadians(26);
        final int dura = inicio[1] + viaje + 2;
        final List<Set<UUID>> golpeados = List.of(new HashSet<>(), new HashSet<>());
        final double mult = abilityDamage();
        announce(Component.text("El Jardín se Cierra. Busquen el hueco verde de cada anillo."));
        titleNear(Component.empty(), Component.text("Quédense en el hueco verde", ACCENT));
        soundAt(c, "block.creaking_heart.hurt", 1.8f, 0.5f);

        animate(dura, t -> {
            if (cerrado || !alive()) throw Stop.now();
            for (int k = 0; k < 2; k++) {
                int t0 = inicio[k];
                // El hueco de cada anillo se marca desde el principio hasta que pasa.
                if (t < t0 + viaje && t % 4 == 0) huecoMarcado(c, hueco[k], medio, R, k == 0 ? 1.0f : 0.7f);
                if (t < t0 || t > t0 + viaje) continue;
                double r = R - (R - 1.5) * (t - t0) / (double) viaje;
                if (t % 2 == 0) anilloConHueco(c, r, hueco[k], medio);
                double banda = Math.max(0.8, (R - 1.5) / viaje * 0.9);
                for (Player p : targets()) {
                    if (golpeados.get(k).contains(p.getUniqueId())) continue;
                    double d = plano(p.getLocation(), c);
                    if (Math.abs(d - r) > banda) continue;
                    if (Math.abs(p.getLocation().getY() - c.getY()) > ALTO + 1) continue;
                    if (enAngulo(p.getLocation(), c, hueco[k], medio)) continue;
                    golpeados.get(k).add(p.getUniqueId());
                    hit(p, 22 * power(), mult);
                    push(p, fuera(c, p.getLocation()).multiply(0.4).setY(0.25));
                }
            }
            if (t % 4 == 0) {
                if (t < inicio[0]) cuenta(targets(), "Dos anillos se cierran. Ponte en el hueco verde:", inicio[0] - t);
                else marcarCuenta();
            }
            if (t % 10 == 0 && t >= inicio[0]) soundAt(c, "block.roots.place", 1.2f, 0.6f);
        }, null);
    }

    /**
     * Cruz de Raices: cuatro brazos de raiz salen de ROTTEN hasta el borde y giran media
     * vuelta en 6 s. Pega a quien pise un brazo (una vez por brazo y vuelta). Esquiva:
     * moverse con el giro, entre dos brazos, o saltar el brazo.
     */
    public void cruzDeRaices() {
        if (!alive()) return;
        final Location c = sinGiro(suelo());
        final double R = radioArena();
        final int carga = 30;
        final int gira = 120;
        final int fin = carga + gira + 10;
        final double base = random.nextDouble() * TAU;
        final double sentido = random.nextBoolean() ? 1 : -1;
        final double w = Math.PI / gira * sentido;
        final Set<String> golpeados = new HashSet<>();
        final double mult = abilityDamage();
        final Escena esc = new Escena();
        final BlockDisplay[] brazos = new BlockDisplay[4];
        announce(Component.text("Cruz de Raíces. No pisen los brazos que giran."));
        soundAt(c, "block.roots.place", 1.8f, 0.5f);

        animate(fin, t -> {
            if (cerrado || !alive()) throw Stop.now();
            double giro = t < carga ? 0 : Math.min(gira, t - carga) * w;
            if (t < carga) {
                if (t % 4 == 0) {
                    for (int i = 0; i < 4; i++) {
                        double a = base + i * Math.PI / 2;
                        linea(c, new Vector(Math.cos(a), 0, -Math.sin(a)), R, 0, CORTEZA);
                    }
                    cuenta(targets(), "Cruz de raíces. No pises los brazos cuando giren:", carga - t);
                }
                return;
            }
            if (t == carga) {
                for (int i = 0; i < 4; i++) {
                    float a = (float) (base + i * Math.PI / 2);
                    brazos[i] = esc.add(pieza(c.clone().add(0, 0.0, 0), Material.PALE_OAK_LOG.createBlockData(),
                            caja(new Quaternionf().rotateY(a), R - 1.5, 0.01, 0.9, 1.5, 0, -0.45), 0));
                }
                soundAt(c, "block.roots.break", 1.8f, 0.5f);
            }
            if (t <= carga + gira && t % 2 == 0) {
                for (int i = 0; i < 4; i++) {
                    float a = (float) (base + i * Math.PI / 2 + giro);
                    forma(brazos[i], caja(new Quaternionf().rotateY(a), R - 1.5, 0.75, 0.9, 1.5, 0, -0.45), 2);
                }
            }
            if (t == carga + gira) {
                for (BlockDisplay b : brazos) forma(b, caja(new Quaternionf(), 0.01, 0.01, 0.01, 0, 0, 0), 8);
            }
            if (t > carga + gira) return;
            if (t % 3 == 0) {
                for (int i = 0; i < 4; i++) {
                    double a = base + i * Math.PI / 2 + giro;
                    Location punta = c.clone().add(Math.cos(a) * R, 0.3, -Math.sin(a) * R);
                    Compat.spawn(world(), Compat.BLOCK, punta, 2, 0.3, 0.1, 0.3, 0, Material.PALE_OAK_LOG.createBlockData());
                }
            }
            if (t % 10 == 0) soundAt(c, "block.pale_hanging_moss.idle", 1.2f, 0.5f);
            for (Player p : targets()) {
                double d = plano(p.getLocation(), c);
                if (d < 1.5 || d > R + 0.5) continue;
                if (Math.abs(p.getLocation().getY() - c.getY()) > ALTO + 1) continue;
                if (enElAire(p, 0.8)) continue;
                double ang = Math.atan2(-(p.getLocation().getZ() - c.getZ()), p.getLocation().getX() - c.getX());
                for (int i = 0; i < 4; i++) {
                    double a = base + i * Math.PI / 2 + giro;
                    double dif = Math.abs(normal(ang - a));
                    if (dif * d > 0.95) continue;
                    String clave = p.getUniqueId() + ":" + i + ":" + (int) Math.floor(Math.abs(giro) / TAU);
                    if (!golpeados.add(clave)) continue;
                    hit(p, 16 * power(), mult);
                    double tang = a + Math.PI / 2 * sentido;
                    push(p, new Vector(Math.cos(tang), 0, -Math.sin(tang)).multiply(0.6).setY(0.3));
                }
            }
        }, esc::quitar);
    }

    /**
     * Esporas: una nube palida aparece sobre el grupo mas apiñado, lo sigue 3 s y
     * estalla en 4,5 bloques. Cuantos mas haya dentro, mas pega a cada uno. Esquiva:
     * separarse (la nube va mas lenta que alguien corriendo).
     */
    public void esporas() {
        if (!alive()) return;
        List<Player> grupo = grupoDenso();
        Location inicio = centroDe(grupo);
        if (inicio == null) return;
        final double radio = 4.5;
        final int estalla = 60;
        final Location nube = sinGiro(Fx.ground(inicio, 6));
        final double mult = abilityDamage();
        final Escena esc = new Escena();
        final BlockDisplay nucleo = esc.add(pieza(nube.clone().add(0, 2.4, 0), Material.PALE_MOSS_BLOCK.createBlockData(),
                caja(new Quaternionf(), 0.01, 0.01, 0.01, 0, 0, 0), 2));
        later(1, () -> forma(nucleo, caja(new Quaternionf(), 1.4, 1.4, 1.4, -0.7, -0.7, -0.7), 6));
        announce(Component.text("Esporas sobre el grupo. Sepárense."));
        soundAt(nube, "block.spore_blossom.place", 1.6f, 0.6f);

        animate(estalla + 2, t -> {
            if (cerrado || !alive()) throw Stop.now();
            if (t < estalla) {
                if (t % 10 == 0) {
                    Location meta = centroDe(grupoDenso());
                    if (meta != null) {
                        Vector paso = meta.toVector().subtract(nube.toVector()).setY(0);
                        if (paso.length() > 1.6) paso.normalize().multiply(1.6);
                        nube.add(paso);
                        Location g = Fx.ground(nube, 6);
                        nube.setY(g.getY());
                    }
                    if (nucleo != null && nucleo.isValid()) {
                        try {
                            nucleo.teleport(nube.clone().add(0, 2.4, 0));
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (t % 4 == 0) {
                    aviso(nube, radio, SEGURO, t, estalla);
                    Compat.spawn(world(), Compat.SPORE_BLOSSOM_AIR, nube.clone().add(0, 2.4, 0), 6, 1.2, 0.6, 1.2, 0);
                    Compat.spawn(world(), Compat.WHITE_SMOKE, nube.clone().add(0, 2.4, 0), 3, 0.8, 0.4, 0.8, 0.01);
                    cuenta(Fx.playersNear(nube, radio + 6), "Esporas sobre ustedes. Sepárense:", estalla - t);
                }
                return;
            }
            if (t == estalla) {
                List<Player> dentro = new ArrayList<>();
                for (Player p : Fx.playersNear(nube, radio + 2)) if (dentro(p, nube, radio)) dentro.add(p);
                double dano = (18 + 6 * Math.max(0, dentro.size() - 1)) * power();
                for (Player p : dentro) {
                    hit(p, dano, mult);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0, true, false, false));
                }
                Location boom = nube.clone().add(0, 1.2, 0);
                Compat.spawn(world(), Compat.EXPLOSION, boom, 2, 1.5, 0.5, 1.5, 0);
                Compat.spawn(world(), Compat.SPORE_BLOSSOM_AIR, boom, 30, radio * 0.6, 1.0, radio * 0.6, 0.02);
                Fx.shockwave(world(), nube, radio, Compat.WHITE_SMOKE, 2);
                soundAt(boom, "block.spore_blossom.break", 1.9f, 0.6f);
                soundAt(boom, "entity.generic.explode", 1.0f, 1.2f);
            }
        }, esc::quitar);
    }

    // =========================================================================
    //  FASE III  ·  CORAZON PALIDO
    // =========================================================================

    /**
     * Latido: tres pulsos que salen del corazon por el suelo hasta el borde, cada uno
     * mas rapido. Pega a quien este en el suelo cuando pasa cada frente. Esquiva: saltar.
     */
    public void latido() {
        if (!alive()) return;
        final Location c = sinGiro(suelo());
        final double R = radioArena();
        final double[] vel = {Math.max(0.5, R / 55.0), Math.max(0.7, R / 40.0), Math.max(0.9, R / 32.0)};
        final int[] sale = {24, 0, 0};
        sale[1] = sale[0] + 26;
        sale[2] = sale[1] + 22;
        final int dura = sale[2] + (int) Math.ceil(R / vel[2]) + 2;
        final List<Set<UUID>> golpeados = List.of(new HashSet<>(), new HashSet<>(), new HashSet<>());
        final double mult = abilityDamage();
        announce(Component.text("Latido. Tres pulsos, cada uno más rápido: salten cada uno."));
        soundAt(c, "block.creaking_heart.hurt", 1.8f, 0.5f);

        animate(dura, t -> {
            if (cerrado || !alive()) throw Stop.now();
            if (t < sale[0] && t % 4 == 0) {
                aviso(c, 2.5 + 2.5 * t / sale[0], AMBAR, t, sale[0]);
                cuenta(targets(), "El corazón va a latir. Salta cuando llegue cada pulso:", sale[0] - t);
            }
            boolean pinta = t % 2 == 0;
            for (int k = 0; k < 3; k++) {
                if (t == sale[k]) {
                    Compat.spawn(world(), Compat.FLASH, center().add(0, 1.4, 0), 1);
                    soundAt(c, "block.creaking_heart.break", 1.8f, 0.6f + k * 0.15f);
                }
                if (t < sale[k]) continue;
                double r = (t - sale[k]) * vel[k];
                if (r > R) continue;
                frente(c, r, k == 2 ? RESINA : AMBAR, pinta);
                double banda = Math.max(0.7, vel[k] * 0.8);
                for (Player p : targets()) {
                    if (golpeados.get(k).contains(p.getUniqueId())) continue;
                    double d = plano(p.getLocation(), c);
                    if (Math.abs(d - r) > banda) continue;
                    if (Math.abs(p.getLocation().getY() - c.getY()) > ALTO + 1) continue;
                    if (enElAire(p)) continue;
                    golpeados.get(k).add(p.getUniqueId());
                    hit(p, 16 * power(), mult);
                    push(p, fuera(p.getLocation(), c).multiply(0.45).setY(0.2));
                }
            }
            if (t >= sale[0]) marcarCuenta();
        }, null);
    }

    /**
     * Bosque de Lanzas: toda la arena se llena de raices salvo 3 o 4 circulos verdes.
     * A los 3 s, dano alto a todo el que este fuera. Esquiva: entrar en un circulo.
     */
    public void bosqueDeLanzas() {
        if (!alive()) return;
        final Location c = sinGiro(suelo());
        final double R = radioArena();
        final int carga = 60;
        final double radio = 3.2;
        int n = targets().size() <= 3 ? 3 : 4;
        final List<Location> seguros = new ArrayList<>();
        double giro = random.nextDouble() * TAU;
        for (int i = 0; i < n; i++) {
            double a = giro + TAU * i / n + (random.nextDouble() - 0.5) * 0.6;
            double r = Fx.clamp(R * (0.35 + random.nextDouble() * 0.4), 6, R - radio);
            seguros.add(sinGiro(Fx.ground(c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 8)));
        }
        final double mult = abilityDamage();
        final Escena esc = new Escena();
        titleNear(Component.empty(), Component.text("Entren en un círculo verde", ACCENT));
        announce(Component.text("Bosque de Lanzas. Solo los círculos verdes son seguros."));
        soundAt(c, "block.creaking_heart.hurt", 1.9f, 0.5f);

        animate(carga + 30, t -> {
            if (cerrado || !alive()) throw Stop.now();
            if (t < carga && t % 4 == 0) {
                for (Location s : seguros) {
                    aviso(s, radio, SEGURO, t, carga);
                    Compat.spawn(world(), Compat.END_ROD, s.clone().add(0, 0.6, 0), 1, 0.1, 0.6, 0.1, 0);
                }
                // El resto de la arena, salpicado de brotes que avisan de lo que viene.
                for (int i = 0; i < 18; i++) {
                    Location l = puntoDeArena(c, R);
                    if (enAlguno(l, seguros, radio)) continue;
                    Compat.spawn(world(), Compat.DUST, l.clone().add(0, 0.15, 0), 1, 0, 0, 0, 0, Compat.dust(RESINA, 1.1f));
                }
                Fx.ring(c.clone().add(0, 0.2, 0), R, (int) Fx.clamp(R * 4, 40, 160), p ->
                        Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(RESINA, 1.2f)));
                cuenta(targets(), "El bosque brota. Entra en un círculo verde:", carga - t);
            }
            if (t % 20 == 0 && t < carga) soundAt(c, "block.roots.place", 1.4f, 0.5f + t * 0.01f);
            if (t == carga) {
                int brotes = 0;
                for (int i = 0; i < 80 && brotes < 40; i++) {
                    Location l = puntoDeArena(c, R);
                    if (enAlguno(l, seguros, radio + 0.4)) continue;
                    brote(esc, l, Material.PALE_OAK_LOG, 0.4 + random.nextDouble() * 0.25,
                            1.8 + random.nextDouble() * 1.6, (random.nextDouble() - 0.5) * 0.6, 20);
                    brotes++;
                }
                for (Player p : targets()) {
                    boolean salvo = false;
                    for (Location s : seguros) {
                        if (dentro(p, s, radio)) {
                            salvo = true;
                            break;
                        }
                    }
                    if (salvo) continue;
                    brote(esc, Fx.ground(p.getLocation(), 4), Material.PALE_OAK_LOG, 0.5, 2.8, 0.15, 20);
                    hit(p, 26 * power(), mult);
                    push(p, new Vector(0, 0.6, 0));
                }
                Compat.spawn(world(), Compat.BLOCK, c.clone().add(0, 0.4, 0), 30, R * 0.4, 0.2, R * 0.4, 0,
                        Material.PALE_OAK_LOG.createBlockData());
                soundAt(c, "block.roots.break", 2.0f, 0.5f);
                soundAt(c, "block.wood.break", 1.8f, 0.6f);
            }
        }, esc::quitar);
    }

    /**
     * Cosecha: marca al que mas dano le hizo y le pone encima un circulo grande que lo
     * sigue 3 s. El golpe se REPARTE entre todos los que esten dentro: solo, pega muy
     * fuerte; entre varios, poco a cada uno. Esquiva: juntarse en el circulo.
     */
    public void cosecha() {
        if (!alive()) return;
        final Player marcado = masDano();
        if (marcado == null) return;
        final double radio = 4.0;
        final int estalla = 60;
        final double mult = abilityDamage();
        final Location centro = sinGiro(Fx.ground(marcado.getLocation(), 6));
        marcarCosecha(marcado);
        titleNear(Component.empty(), Component.text("Cosecha sobre " + marcado.getName() + ". Júntense con él",
                TextColor.color(AMBAR)));
        announce(Component.text("Cosecha sobre " + marcado.getName() + ". El golpe se reparte entre los que estén dentro."));
        soundAt(centro, "block.eyeblossom.open_long", 1.8f, 0.6f);

        animate(estalla + 2, t -> {
            if (cerrado || !alive()) throw Stop.now();
            if (t < estalla) {
                if (Fx.isFightable(marcado)) {
                    Location g = Fx.ground(marcado.getLocation(), 6);
                    centro.setX(g.getX());
                    centro.setY(g.getY());
                    centro.setZ(g.getZ());
                }
                if (t % 4 == 0) {
                    aviso(centro, radio, AMBAR, t, estalla);
                    Compat.spawn(world(), Compat.END_ROD, centro.clone().add(0, 2.6, 0), 2, 0.2, 0.8, 0.2, 0);
                    int dentro = 0;
                    for (Player p : Fx.playersNear(centro, radio + 2)) if (dentro(p, centro, radio)) dentro++;
                    String quedan = segundos(estalla - t);
                    marcarCuenta();
                    for (Player p : targets()) {
                        p.sendActionBar(Component.text("Cosecha sobre " + marcado.getName() + ": " + dentro
                                + " dentro. Entra al círculo. " + quedan + " s", TextColor.color(AMBAR)));
                    }
                }
                return;
            }
            if (t == estalla) {
                List<Player> dentro = new ArrayList<>();
                for (Player p : Fx.playersNear(centro, radio + 2)) if (dentro(p, centro, radio)) dentro.add(p);
                double total = 46 * power();
                double cada = total / Math.max(1, dentro.size());
                for (Player p : dentro) hit(p, cada, mult);
                Location boom = centro.clone().add(0, 0.6, 0);
                Compat.spawn(world(), Compat.FLASH, boom, 1);
                Compat.spawn(world(), Compat.DUST, boom, 24, radio * 0.5, 0.4, radio * 0.5, 0, Compat.dust(AMBAR, 1.6f));
                soundAt(boom, "entity.creaking.attack", 1.9f, 0.5f);
                soundAt(boom, "block.eyeblossom.close_long", 1.6f, 0.6f);
                announce(Component.text(dentro.size() <= 1 ? "La cosecha cayó sobre uno solo."
                        : "La cosecha se repartió entre " + dentro.size() + "."));
            }
        }, () -> soltarCosecha(marcado));
    }

    /** El jugador que lleva la Cosecha encima y si ya brillaba antes de marcarlo. */
    private Player cosechado;
    private boolean cosechadoBrillaba;

    /**
     * Glow.clear solo saca del equipo de color: el jugador se quedaba BRILLANDO para
     * siempre tras una Cosecha. Se guarda como estaba y se le devuelve.
     */
    private void marcarCosecha(Player p) {
        if (cosechado != null) soltarCosecha(cosechado);
        cosechado = p;
        cosechadoBrillaba = p.isGlowing();
        Glow.apply(p, NamedTextColor.GOLD);
    }

    private void soltarCosecha(Player p) {
        if (p == null || p != cosechado) return;
        Glow.clear(p);
        try {
            p.setGlowing(cosechadoBrillaba);
        } catch (Throwable ignored) {
        }
        cosechado = null;
    }

    /**
     * Estallido del Corazon: brotan cuatro pilares de raiz y el corazon carga 5 s.
     * Al soltarse, un rayo del corazon a cada jugador; solo se salva quien tenga un
     * pilar entre el y ROTTEN. Esquiva: ponerse detras de un pilar.
     */
    public void estallidoDelCorazon() {
        if (!alive()) return;
        final Location c = sinGiro(suelo());
        final double R = radioArena();
        final int carga = 100;
        final double rp = 0.9;
        final List<Location> pilares = new ArrayList<>();
        double giro = random.nextDouble() * TAU;
        double dist = Fx.clamp(R * 0.42, 6, 14);
        for (int i = 0; i < 4; i++) {
            double a = giro + TAU * i / 4;
            pilares.add(sinGiro(Fx.ground(c.clone().add(Math.cos(a) * dist, 0, Math.sin(a) * dist), 8)));
        }
        final double mult = abilityDamage();
        final Escena esc = new Escena();
        for (Location p : pilares) {
            BlockDisplay tronco = esc.add(pieza(p, Material.PALE_OAK_LOG.createBlockData(),
                    caja(new Quaternionf(), rp * 2, 0.01, rp * 2, -rp, -0.2, -rp), 0));
            BlockDisplay musgo = esc.add(pieza(p, Material.PALE_MOSS_BLOCK.createBlockData(),
                    caja(new Quaternionf(), rp * 2.2, 0.01, rp * 2.2, -rp * 1.1, 4.8, -rp * 1.1), 0));
            later(1, () -> {
                forma(tronco, caja(new Quaternionf(), rp * 2, 5.0, rp * 2, -rp, -0.2, -rp), 10);
                forma(musgo, caja(new Quaternionf(), rp * 2.2, 0.45, rp * 2.2, -rp * 1.1, 4.8, -rp * 1.1), 10);
            });
            Compat.spawn(world(), Compat.BLOCK, p.clone().add(0, 0.3, 0), 16, 0.8, 0.2, 0.8, 0,
                    Material.PALE_OAK_LOG.createBlockData());
        }
        titleNear(Component.text("ESTALLIDO", TextColor.color(RESINA)),
                Component.text("Escóndanse detrás de un pilar", NamedTextColor.GRAY));
        announce(Component.text("Estallido del Corazón. Pongan un pilar entre ustedes y ROTTEN."));
        soundAt(c, "block.creaking_heart.spawn", 2.0f, 0.5f);

        animate(carga + 30, t -> {
            if (cerrado || !alive()) throw Stop.now();
            if (t < carga) {
                if (t % 4 == 0) {
                    double u = t / (double) carga;
                    Fx.sphere(center().add(0, 1.2, 0), 1.0 + 1.6 * u, 14 + (int) (u * 14), p ->
                            Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(RESINA, 1.3f)));
                    Fx.ring(c.clone().add(0, 0.2, 0), R, (int) Fx.clamp(R * 3, 40, 140), p ->
                            Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(RESINA, 1.2f)));
                    // La sombra de cada pilar, en verde: ahi es seguro.
                    for (Location p : pilares) {
                        Vector d = p.toVector().subtract(c.toVector()).setY(0);
                        if (d.lengthSquared() < 0.01) continue;
                        d.normalize();
                        for (double s = rp + 0.6; s <= rp + 6; s += 0.9) {
                            Compat.spawn(world(), Compat.DUST, p.clone().add(d.clone().multiply(s)).add(0, 0.15, 0),
                                    1, 0, 0, 0, 0, Compat.dust(SEGURO, 1.2f));
                        }
                    }
                    marcarCuenta();
                    String quedan = segundos(carga - t);
                    for (Player p : targets()) {
                        boolean cubierto = cubierto(p, c, pilares, rp);
                        p.sendActionBar(cubierto
                                ? Component.text("Cubierto por un pilar. " + quedan + " s", ACCENT)
                                : Component.text("Ponte detrás de un pilar. " + quedan + " s", TextColor.color(RESINA)));
                    }
                }
                if (t % 20 == 0) soundAt(c, "block.creaking_heart.idle", 2.0f, 0.5f + t * 0.008f);
                return;
            }
            if (t == carga) {
                Location corazon = center().add(0, 1.2, 0);
                Compat.spawn(world(), Compat.FLASH, corazon, 1);
                Compat.spawn(world(), Compat.EXPLOSION_EMITTER, corazon, 1);
                soundAt(c, "block.creaking_heart.break", 2.0f, 0.5f);
                soundAt(c, "entity.generic.explode", 1.6f, 0.6f);
                for (Player p : targets()) {
                    if (cubierto(p, c, pilares, rp)) continue;
                    Fx.beam(corazon, p.getLocation().add(0, 1, 0), 0.5, q ->
                            Compat.spawn(world(), Compat.DUST, q, 1, 0, 0, 0, 0, Compat.dust(RESINA, 1.5f)));
                    hit(p, 35 * power(), mult);
                    push(p, fuera(p.getLocation(), c).multiply(0.7).setY(0.35));
                }
            }
            if (t == carga + 20) {
                for (Entity e : esc.cosas) {
                    if (e instanceof BlockDisplay b) forma(b, caja(new Quaternionf(), 0.01, 0.01, 0.01, 0, 0, 0), 8);
                }
            }
        }, esc::quitar);
    }

    // =========================================================================
    //  AYUDAS
    // =========================================================================

    /** Hasta donde llegan los hechizos de area: la arena, o mas si alguien esta mas lejos. */
    private double radioArena() {
        double r = 26;
        try {
            r = Math.max(12, event.type().arenaRadius());
        } catch (Throwable ignored) {
        }
        Location h = suelo();
        for (Player p : targets()) r = Math.max(r, plano(p.getLocation(), h) + 3);
        return Math.min(r, 48);
    }

    /** El jugador que mas dano le ha hecho de los que pelean; al azar si nadie le pego. */
    private Player masDano() {
        List<Player> ps = targets();
        if (ps.isEmpty()) return null;
        Player mejor = null;
        double max = 0;
        for (Player p : ps) {
            double d = event.damage().getOrDefault(p.getUniqueId(), 0.0);
            if (d > max) {
                max = d;
                mejor = p;
            }
        }
        return mejor != null ? mejor : ps.get(random.nextInt(ps.size()));
    }

    /** El grupo mas denso: el jugador con mas compañeros a 4 bloques o menos, y ellos. */
    private List<Player> grupoDenso() {
        List<Player> todos = targets();
        List<Player> mejor = new ArrayList<>();
        for (Player p : todos) {
            List<Player> junto = new ArrayList<>();
            for (Player q : todos) {
                if (q.getWorld().equals(p.getWorld()) && q.getLocation().distanceSquared(p.getLocation()) <= 16) {
                    junto.add(q);
                }
            }
            if (junto.size() > mejor.size()) mejor = junto;
        }
        return mejor;
    }

    /** El centro (a la altura de los pies) de los que sigan en pie del grupo. */
    private static Location centroDe(List<Player> grupo) {
        double x = 0, y = 0, z = 0;
        int n = 0;
        org.bukkit.World w = null;
        for (Player p : grupo) {
            if (!Fx.isFightable(p)) continue;
            if (w == null) w = p.getWorld();
            if (!p.getWorld().equals(w)) continue;
            Location l = p.getLocation();
            x += l.getX();
            y += l.getY();
            z += l.getZ();
            n++;
        }
        return n == 0 ? null : new Location(w, x / n, y / n, z / n);
    }

    /** Si hay un pilar entre el corazon y el jugador (en planta). */
    private static boolean cubierto(Player p, Location c, List<Location> pilares, double rp) {
        Location l = p.getLocation();
        double px = l.getX() - c.getX(), pz = l.getZ() - c.getZ();
        double len2 = px * px + pz * pz;
        if (len2 < 0.01) return false;
        for (Location pi : pilares) {
            double cx = pi.getX() - c.getX(), cz = pi.getZ() - c.getZ();
            double u = (cx * px + cz * pz) / len2;
            if (u <= 0 || u >= 1) continue;
            double dx = cx - px * u, dz = cz - pz * u;
            if (Math.sqrt(dx * dx + dz * dz) <= rp + 0.35) return true;
        }
        return false;
    }

    /** Dentro de un circulo del suelo: distancia en planta y tolerancia vertical 2,5. */
    private static boolean dentro(Player p, Location c, double r) {
        if (!Fx.isFightable(p) || p.getWorld() != c.getWorld()) return false;
        if (Math.abs(p.getLocation().getY() - c.getY()) > ALTO) return false;
        return plano(p.getLocation(), c) <= r;
    }

    private static boolean enAlguno(Location l, List<Location> circulos, double r) {
        for (Location s : circulos) if (plano(l, s) <= r) return true;
        return false;
    }

    /** Si el jugador esta dentro del sector [centro - medio, centro + medio] visto desde c. */
    private static boolean enAngulo(Location l, Location c, double centro, double medio) {
        double a = Math.atan2(l.getZ() - c.getZ(), l.getX() - c.getX());
        return Math.abs(normal(a - centro)) <= medio;
    }

    /** Un angulo llevado a (-pi, pi]. */
    private static double normal(double a) {
        a = a % TAU;
        if (a > Math.PI) a -= TAU;
        if (a <= -Math.PI) a += TAU;
        return a;
    }

    private static double plano(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Distancia en planta de l al segmento a-b. */
    private static double distSegmento(Location l, Location a, Location b) {
        double abx = b.getX() - a.getX(), abz = b.getZ() - a.getZ();
        double apx = l.getX() - a.getX(), apz = l.getZ() - a.getZ();
        double len2 = abx * abx + abz * abz;
        double u = len2 < 1e-6 ? 0 : Fx.clamp((apx * abx + apz * abz) / len2, 0, 1);
        double dx = apx - abx * u, dz = apz - abz * u;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Direccion horizontal de c hacia a, para los empujes hacia fuera. */
    private static Vector fuera(Location a, Location c) {
        Vector v = a.toVector().subtract(c.toVector()).setY(0);
        if (v.lengthSquared() < 0.01) v = new Vector(1, 0, 0);
        return v.normalize();
    }

    /**
     * Cuanto esta el jugador por encima de lo que tiene debajo, medido contra la CAJA DE
     * COLISION de cada bloque (una valla o un muro miden 1,5; una losa, 0,5). Fx.ground
     * redondea al bloque entero y daba por "en el aire" a quien estaba de pie en una
     * valla, un muro o media escalera: las ondas no le pegaban nunca.
     */
    private static double alturaSobreSuelo(Player p) {
        Location l = p.getLocation();
        org.bukkit.World w = l.getWorld();
        if (w == null) return 0;
        double pies = l.getY();
        int x = l.getBlockX(), z = l.getBlockZ();
        int y0 = (int) Math.floor(pies + 0.01);
        for (int y = y0; y >= y0 - 5; y--) {
            org.bukkit.block.Block b = w.getBlockAt(x, y, z);
            if (b.isPassable()) continue;
            double techo = -1;
            try {
                for (org.bukkit.util.BoundingBox bb : b.getCollisionShape().getBoundingBoxes()) {
                    techo = Math.max(techo, bb.getMaxY());
                }
            } catch (Throwable t) {
                techo = 1;
            }
            if (techo < 0) continue;
            double top = y + techo;
            if (top <= pies + 0.05) return Math.max(0, pies - top);
        }
        return 6;
    }

    /**
     * En el aire de verdad: el servidor no lo tiene apoyado (isOnGround) Y sus pies
     * van al menos 'minimo' por encima de la colision de debajo. Lo primero cubre al
     * que esta al borde de un bloque (sin nada justo bajo el centro); lo segundo, el
     * instante de despegar. Nadar no cuenta: el agua no esquiva una onda.
     */
    private static boolean enElAire(Player p, double minimo) {
        if (p.isOnGround() || p.isInWater() || p.isClimbing()) return false;
        return alturaSobreSuelo(p) > minimo;
    }

    /** Saltando (o en el aire): lo que esquiva las ondas por el suelo. */
    private static boolean enElAire(Player p) {
        return enElAire(p, 0.3);
    }

    private Location puntoDeArena(Location c, double R) {
        double a = random.nextDouble() * TAU;
        double r = 2 + Math.sqrt(random.nextDouble()) * (R - 2);
        return Fx.ground(c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 8);
    }

    private static Location sinGiro(Location l) {
        Location c = l.clone();
        c.setYaw(0);
        c.setPitch(0);
        return c;
    }

    /** "1,5" a partir de ticks. */
    private static String segundos(int ticks) {
        return String.format(Locale.ROOT, "%.1f", Math.max(0, ticks) / 20.0).replace('.', ',');
    }

    private void marcarCuenta() {
        ultimaCuenta = ticks();
    }

    /** La cuenta atras de un hechizo en la barra de accion de los afectados. */
    private void cuenta(Collection<Player> ps, String texto, int quedan) {
        marcarCuenta();
        Component c = Component.text(texto + " ", ACCENT)
                .append(Component.text(segundos(quedan) + " s", NamedTextColor.WHITE));
        for (Player p : ps) if (Fx.isFightable(p)) p.sendActionBar(c);
    }

    /**
     * El aviso de un circulo: el borde fijo y un aro interior que crece hasta llenarlo
     * cuando toca el golpe. Se repinta cada 4 ticks mientras dura la carga.
     */
    private void aviso(Location c, double r, int rgb, int t, int total) {
        int puntos = (int) Fx.clamp(r * 6, 14, 90);
        Fx.ring(c.clone().add(0, 0.12, 0), r, puntos, p ->
                Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(rgb, 1.2f)));
        double u = Fx.clamp(t / (double) Math.max(1, total), 0, 1);
        double ri = Math.max(0.3, r * u);
        Fx.ring(c.clone().add(0, 0.12, 0), ri, Math.max(8, (int) (puntos * u)), p ->
                Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(rgb, 0.9f)));
    }

    /** El frente de una onda por el suelo: polvo y, a saltos, astillas de corteza. */
    private void frente(Location c, double r, int rgb, boolean pinta) {
        if (!pinta || r < 0.5) return;
        int puntos = (int) Fx.clamp(r * 3.2, 12, 150);
        Fx.ring(c.clone().add(0, 0.25, 0), r, puntos, p -> {
            Compat.spawn(world(), Compat.DUST, p, 1, 0.05, 0.1, 0.05, 0, Compat.dust(rgb, 1.3f));
            if (random.nextInt(8) == 0) Compat.spawn(world(), Compat.BLOCK, p, 1, 0.1, 0.1, 0.1, 0,
                    Material.PALE_OAK_LOG.createBlockData());
        });
    }

    /** Una linea del suelo desde c en la direccion d, con sus bordes si tiene ancho. */
    private void linea(Location c, Vector d, double largo, double ancho, int rgb) {
        Vector n = new Vector(-d.getZ(), 0, d.getX());
        for (double s = 1.5; s <= largo; s += 1.0) {
            Location p = c.clone().add(d.clone().multiply(s)).add(0, 0.15, 0);
            Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(rgb, 1.2f));
            if (ancho > 0 && ((int) s) % 2 == 0) {
                Compat.spawn(world(), Compat.DUST, p.clone().add(n.clone().multiply(ancho)), 1, 0, 0, 0, 0,
                        Compat.dust(rgb, 0.8f));
                Compat.spawn(world(), Compat.DUST, p.clone().add(n.clone().multiply(-ancho)), 1, 0, 0, 0, 0,
                        Compat.dust(rgb, 0.8f));
            }
        }
    }

    /** Un anillo de raices con su hueco (sector sin pintar). */
    private void anilloConHueco(Location c, double r, double hueco, double medio) {
        int puntos = (int) Fx.clamp(r * 3.2, 12, 150);
        for (int i = 0; i < puntos; i++) {
            double a = TAU * i / puntos;
            if (Math.abs(normal(a - hueco)) <= medio) continue;
            Location p = c.clone().add(Math.cos(a) * r, 0.3, Math.sin(a) * r);
            Compat.spawn(world(), Compat.DUST, p, 1, 0.05, 0.2, 0.05, 0, Compat.dust(CORTEZA, 1.4f));
            if (random.nextInt(6) == 0) Compat.spawn(world(), Compat.BLOCK, p, 1, 0.1, 0.2, 0.1, 0,
                    Material.PALE_OAK_LOG.createBlockData());
        }
    }

    /** Las dos rayas verdes que limitan el hueco de un anillo, del centro al borde. */
    private void huecoMarcado(Location c, double hueco, double medio, double R, float tam) {
        for (double borde : new double[]{hueco - medio, hueco + medio}) {
            for (double s = 3; s <= R; s += 1.5) {
                Location p = c.clone().add(Math.cos(borde) * s, 0.15, Math.sin(borde) * s);
                Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(SEGURO, 1.1f * tam));
            }
        }
        Location p = c.clone().add(Math.cos(hueco) * R * 0.8, 0.4, Math.sin(hueco) * R * 0.8);
        Compat.spawn(world(), Compat.END_ROD, p, 1, 0.1, 0.4, 0.1, 0);
    }

    /**
     * Una raiz que brota del suelo: crece en 3 ticks, aguanta 'vida' ticks, se encoge
     * y desaparece. Inclinada un poco ('incl' radianes) y con giro al azar.
     */
    private void brote(Escena esc, Location base, Material m, double w, double h, double incl, int vida) {
        Quaternionf q = new Quaternionf().rotateY((float) (random.nextDouble() * TAU)).rotateX((float) incl);
        BlockDisplay d = esc.add(pieza(sinGiro(base), m.createBlockData(), caja(q, w, 0.01, w, -w / 2, -0.3, -w / 2), 0));
        if (d == null) return;
        later(1, () -> forma(d, caja(q, w, h, w, -w / 2, -0.3, -w / 2), 3));
        later(vida, () -> forma(d, caja(q, w * 0.6, 0.01, w * 0.6, -w * 0.3, -0.3, -w * 0.3), 5));
        later(vida + 6, () -> {
            spawned.remove(d);
            Fx.safeRemove(d);
        });
    }

    /**
     * Una caja de medida (sx, sy, sz) girada q, con su esquina local (ox, oy, oz)
     * respecto al ancla: (-sx/2, 0, -sz/2) la centra en planta y la apoya en el suelo.
     */
    private static Transformation caja(Quaternionf q, double sx, double sy, double sz,
                                       double ox, double oy, double oz) {
        Vector3f t = new Quaternionf(q).transform(new Vector3f((float) ox, (float) oy, (float) oz));
        return new Transformation(t, new Quaternionf(q),
                new Vector3f((float) Math.max(sx, 1e-4), (float) Math.max(sy, 1e-4), (float) Math.max(sz, 1e-4)),
                new Quaternionf());
    }

    /** Cambia la forma de una pieza con interpolacion. */
    private static void forma(BlockDisplay d, Transformation tr, int interp) {
        if (d == null || !d.isValid()) return;
        try {
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(interp);
            d.setTransformation(tr);
        } catch (Throwable ignored) {
        }
    }

    /** Un BlockDisplay a brillo 15, marcado como temporal y en 'spawned'. */
    private BlockDisplay pieza(Location at, BlockData datos, Transformation tr, int teleporte) {
        try {
            BlockDisplay e = world().spawn(at, BlockDisplay.class, x -> {
                x.setBlock(datos);
                x.setBrightness(new Display.Brightness(15, 15));
                x.setViewRange(3.0f);
                x.setShadowRadius(0f);
                x.setPersistent(false);
                x.setTeleportDuration(teleporte);
                x.setInterpolationDelay(0);
                x.setInterpolationDuration(2);
                x.setTransformation(tr);
            });
            Tags.markTemporary(e);
            track(e);
            return e;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Las piezas de un hechizo, para quitarlas todas al terminar o si se corta. */
    private final class Escena {
        final List<Entity> cosas = new ArrayList<>();

        <T extends Entity> T add(T e) {
            if (e != null) cosas.add(e);
            return e;
        }

        void quitar() {
            for (Entity e : cosas) {
                spawned.remove(e);
                Fx.safeRemove(e);
            }
            cosas.clear();
        }
    }

    // -------------------------------------------------------------------- limpieza

    @Override
    public void cleanup() {
        cerrado = true;
        limpiarFlor();
        if (cosechado != null) soltarCosecha(cosechado);
        combateCongelado = false;
        miradaEnCurso = false;
        ritualActivo = false;
        super.cleanup();
    }
}
