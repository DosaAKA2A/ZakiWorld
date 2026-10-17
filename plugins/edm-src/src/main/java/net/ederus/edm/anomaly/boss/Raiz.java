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
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Creaking;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

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

    /*
     * ROTTEN se pinta SOLO con particulas (nada de BlockDisplay/ItemDisplay): cada
     * hechizo con su firma, sacada del jardin palido. Las astillas y el polvo de bloque
     * (BLOCK, FALLING_DUST) son lo que Bedrock ve bien; TRAIL, BLOCK_CRUMBLE,
     * BLOCK_MARKER y DUST_PILLAR son adorno que Geyser puede no traducir.
     */
    private final BlockData roble = Material.PALE_OAK_LOG.createBlockData();
    private final BlockData musgo = Material.PALE_MOSS_BLOCK.createBlockData();
    private final BlockData resina = Material.RESIN_BLOCK.createBlockData();
    private final BlockData corazonCreaking = Material.CREAKING_HEART.createBlockData();
    private final BlockData raigambre = Material.ROOTED_DIRT.createBlockData();
    private final BlockData ojo = Material.OPEN_EYEBLOSSOM.createBlockData();
    private final ItemStack grumo = new ItemStack(Material.RESIN_CLUMP);

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

        fx(Compat.PALE_OAK_LEAVES, at.clone().add(0, 1.6, 0), 40, 1.6, 1.4, 1.6, 0.04, null);
        fx(Compat.DUST_PILLAR, at.clone().add(0, 0.1, 0), 24, 1.2, 0, 1.2, 0, raigambre);
        Fx.ring(at.clone().add(0, 0.3, 0), 4.5, 30, p -> fx(Compat.BLOCK, p, 2, 0.1, 0.1, 0.1, 0, roble));
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
        Fx.shockwave(world(), c, 7, Compat.PALE_OAK_LEAVES, 2);
        fx(Compat.BLOCK_CRUMBLE, center().add(0, 1.6, 0), 30, 0.8, 1.2, 0.8, 0, corazonCreaking);
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
                        fx(Compat.PALE_OAK_LEAVES, p, 1, 0.1, 0.1, 0.1, 0.02, null));
                fx(Compat.BLOCK_CRUMBLE, c.clone().add(0, 1.6, 0), 6, 0.6, 1.0, 0.6, 0, roble);
            }
            if (tick % 18 == 0) soundAt(c, "entity.creaking.death", 1.4f, 0.6f + tick * 0.004f);
        }, () -> {
            fx(Compat.BLOCK_CRUMBLE, c.clone().add(0, 1.6, 0), 60, 1.0, 1.4, 1.0, 0, corazonCreaking);
            fx(Compat.BLOCK, c.clone().add(0, 1.0, 0), 40, 1.2, 1.0, 1.2, 0, roble);
            fx(Compat.POOF, c.clone().add(0, 1.0, 0), 20, 1.0, 1.0, 1.0, 0.03, null);
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
            // La resina que rezuma el corazon con cada latido.
            fx(Compat.DRIPPING_HONEY, center().add(0, 1.4, 0), 2, 0.4, 0.5, 0.4, 0, null);
        }
        if (ticks() % 14 == 0) {
            fx(Compat.PALE_OAK_LEAVES, center().add(0, 2.2, 0), 2, 1.8, 0.8, 1.8, 0.01, null);
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
        /* El cerco de la mirada: hilos ambar que se cierran solos sobre ROTTEN en los
         * 4 s (los anima el cliente). Bedrock ve las astillas del corazon de abajo. */
        Location cerco = c.clone().add(0, 0.3, 0);
        for (int i = 0; i < 32; i++) {
            double a = TAU * i / 32;
            Vector u = new Vector(Math.cos(a), 0, Math.sin(a));
            hilo(cerco.clone().add(u.clone().multiply(10.5)), cerco.clone().add(u.multiply(1.5)), AMBAR, fin, true);
        }

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
                Fx.ring(c.clone().add(0, 0.3, 0), r, 14, t * 0.05, p -> fx(Compat.BLOCK, p, 1, 0.05, 0.1, 0.05, 0, corazonCreaking));
                // Los ojos del corazon, encendidos mientras mira.
                if (t >= aviso) fx(Compat.WAX_ON, center().add(0, 2.4, 0), 2, 0.3, 0.2, 0.3, 0.02, null);
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
                impacto(p, corazonCreaking);
                fx(Compat.BLOCK_CRUMBLE, p.getLocation().add(0, 1, 0), 14, 0.3, 0.6, 0.3, 0, corazonCreaking);
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

    /**
     * Una eyeblossom abierta en maceta, bloque de verdad, encima del vidrio (si ahi hay
     * aire): se presta como el pilar provisional y se devuelve al cerrar. La maceta no
     * se cae con las actualizaciones de bloque y la pocion revienta contra ella o contra
     * el vidrio, las dos dentro del radio. La columna de luz la pinta tickRitual.
     */
    private void plantarFlor(Location vidrio) {
        org.bukkit.block.Block encima = vidrio.getBlock().getRelative(org.bukkit.block.BlockFace.UP);
        if (encima.getType().isAir()) prestar(encima, Material.POTTED_OPEN_EYEBLOSSOM);
        Location centro = vidrio.clone().add(0.5, 1.2, 0.5);
        fx(Compat.BLOCK, centro, 16, 0.3, 0.3, 0.3, 0, ojo);
        fx(Compat.FALLING_SPORE_BLOSSOM, centro.clone().add(0, 2, 0), 12, 0.6, 0.4, 0.6, 0, null);
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
            // La columna de luz que se ve desde toda la arena (forzada: llega mas alla de 32 bloques).
            Compat.spawnForced(world(), Compat.END_ROD, florEn.clone().add(0, 1.5 + random.nextDouble() * 12, 0), 3,
                    0.15, 1.5, 0.15, 0.005, null);
            fx(Compat.FIREFLY, florEn.clone().add(0, 1.2, 0), 1, 0.8, 0.6, 0.8, 0, null);
        }
        if (ticks() % 10 == 0) {
            // El aro de lo que lleva: crece con cada dosis, en verde de crecimiento.
            double r = 2.0 + 3.0 * Math.min(1.0, dosis / (double) dosisPedidas);
            Fx.ring(florEn.clone().add(0, -1.2, 0), r, 18, p -> fx(Compat.HAPPY_VILLAGER, p, 1, 0, 0, 0, 0, null));
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
            Fx.beam(desde, hasta, 0.6, p -> fx(Compat.END_ROD, p, 1, 0.03, 0.03, 0.03, 0, null));
            for (int i = 0; i < 8; i++) hilo(desde, hasta, SEGURO, 6 + i * 2, true);
            fx(Compat.FALLING_SPORE_BLOSSOM, hasta.clone().add(0, 1.5, 0), 30, 1.2, 0.5, 1.2, 0, null);
            Compat.spawn(world(), Compat.FLASH, hasta, 1);
            soundAt(hasta, "block.beacon.activate", 1.8f, 1.2f);
            announce(Component.text("La flor se abrió. ROTTEN cambia."));
            titleNear(Component.empty(), Component.text("LA FLOR SE ABRIÓ", ACCENT));
        } else {
            double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
            double vidaDiseno = damageScale() > 0 ? max / damageScale() : max;
            curar(vidaDiseno * 0.10);
            fx(Compat.HEART, center().add(0, 1.6, 0), 8, 1.0, 1.0, 1.0, 0.04, null);
            fx(Compat.PALE_OAK_LEAVES, center().add(0, 2.0, 0), 16, 1.2, 1.0, 1.2, 0.02, null);
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

    /**
     * Lo prestado (la maceta y el pilar provisional) es de la pelea: ArenaGuard no deja
     * sacar la flor de la maceta y la rotura se cancela sin soltar nada. Se devuelve
     * en limpiarFlor.
     */
    @Override
    public boolean ownsBlock(org.bukkit.block.Block block) {
        return block != null && pilarPrestado.containsKey(block.getLocation());
    }

    @Override
    public boolean onBlockBroken(org.bukkit.block.Block block, Player who) {
        return ownsBlock(block);
    }

    private void limpiarFlor() {
        florEn = null;
        /* Lo prestado (el pilar provisional y la maceta de encima) se devuelve tal cual
         * estaba; el vidrio marcado no se toca. */
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
        announce(Component.text("Raíces del Jardín. Salgan del círculo."));
        soundAt(suelo(), "block.roots.place", 1.6f, 0.6f);
        final Location pie = suelo().add(0, 0.2, 0);

        animate(estalla + 2, t -> {
            if (cerrado || !alive()) throw Stop.now();
            if (t < estalla && t % 4 == 0) {
                for (Location s : sellos) {
                    aviso(s, radio, BORDE_RAIZ, CORTEZA, t, estalla);
                    // Las raices viajan bajo tierra desde el corazon hasta cada sello.
                    hilo(pie, s.clone().add(0, 0.2, 0), CORTEZA, 14, true);
                    fx(Compat.BLOCK, s.clone().add(0, 0.2, 0), 2, radio * 0.4, 0.05, radio * 0.4, 0, raigambre);
                }
                cuenta(objetivos, "Raíces bajo tus pies. Sal del círculo:", estalla - t);
            }
            if (t == estalla) {
                List<Brote> brotes = new ArrayList<>();
                for (Location s : sellos) {
                    for (int i = 0; i < 6; i++) {
                        double a = TAU * i / 6 + random.nextDouble() * 0.6;
                        double r = i == 0 ? 0 : 0.8 + random.nextDouble() * (radio - 1.2);
                        Location b = s.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
                        brotes.add(brote(b, 2.2 + random.nextDouble() * 1.2, (random.nextDouble() - 0.5) * 0.5));
                    }
                    fx(Compat.BLOCK, s.clone().add(0, 0.3, 0), 14, 1.2, 0.2, 1.2, 0, raigambre);
                    soundAt(s, "block.roots.break", 1.5f, 0.6f);
                    for (Player v : Fx.playersNear(s, radio + 2)) {
                        if (!dentro(v, s, radio)) continue;
                        if (!golpeados.add(v.getUniqueId())) continue;
                        hit(v, 18 * power(), mult);
                        impacto(v, roble);
                        push(v, new Vector(0, 0.85, 0));
                    }
                }
                brotar(brotes, 18);
            }
        }, null);
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
                    aviso(c, 3.0 + 2.0 * t / carga, BORDE_ESPINAS, CORTEZA, t, carga);
                    cuenta(targets(), "Onda de espinas. Salta cuando llegue:", carga - t);
                }
                return;
            }
            if (t == carga) onda(c, R, v, CORTEZA);
            double r = (t - carga) * v;
            if (r > R) return;
            if (t % 2 == 0) frente(c, r, roble, true);
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
                impacto(p, roble);
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
        announce(Component.text("Lluvia de Resina. Salgan de las marcas naranjas."));
        soundAt(suelo(), "block.resin.place", 1.6f, 0.6f);

        animate(cae + charco, t -> {
            if (cerrado || !alive()) throw Stop.now();
            if (t < cae && t % 4 == 0) {
                for (Location m : marcas) {
                    aviso(m, radio, BORDE_RESINA, RESINA, t, cae);
                    // Gotean del cielo justo encima: se ve DONDE va a caer antes de que caiga.
                    fx(Compat.FALLING_HONEY, m.clone().add(0, 6, 0), 1, 0.3, 0.2, 0.3, 0, null);
                }
                cuenta(objetivos, "Cae resina. Sal de la marca naranja:", cae - t);
            }
            if (t > cae - caida && t < cae) {
                // La gota: un grumo de resina que cae 14 bloques acelerando.
                double u = (t - (cae - caida)) / (double) caida;
                for (Location m : marcas) {
                    Location g = m.clone().add(0, 0.6 + 14 * (1 - u * u), 0);
                    fx(Compat.ITEM, g, 2, 0.2, 0.2, 0.2, 0.01, grumo);
                    fx(Compat.BLOCK, g, 1, 0.25, 0.25, 0.25, 0, resina);
                    if (t % 2 == 0) fx(Compat.FALLING_HONEY, g, 1, 0.15, 0.1, 0.15, 0, null);
                }
            }
            if (t == cae) {
                for (Location m : marcas) {
                    fx(Compat.BLOCK, m.clone().add(0, 0.4, 0), 14, 0.8, 0.2, 0.8, 0, resina);
                    fx(Compat.ITEM, m.clone().add(0, 0.5, 0), 8, 0.4, 0.2, 0.4, 0.12, grumo);
                    fx(Compat.LANDING_HONEY, m.clone().add(0, 0.1, 0), 10, radio * 0.45, 0, radio * 0.45, 0, null);
                    for (Player v : Fx.playersNear(m, radio + 2)) {
                        if (!dentro(v, m, radio)) continue;
                        if (!golpeados.add(v.getUniqueId())) continue;
                        hit(v, 14 * power(), mult);
                        impacto(v, resina);
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
                    /* El charco: miel que se queda pegada al suelo (dura sola unos segundos)
                     * y polvo de resina por encima, que es lo que pinta Bedrock. Se deja
                     * de reponer a medio charco para que no sobreviva mucho al efecto. */
                    if (t < cae + charco - 20) {
                        fx(Compat.LANDING_HONEY, m.clone().add(0, 0.1, 0), 4, radio * 0.45, 0, radio * 0.45, 0, null);
                    }
                    fx(Compat.FALLING_DUST, m.clone().add(0, 0.5, 0), 3, radio * 0.45, 0.05, radio * 0.45, 0, resina);
                }
            }
        }, null);
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
        announce(Component.text("Lanzas Pálidas. Salgan de la línea."));
        soundAt(c, "entity.creaking.twitch", 1.6f, 0.6f);

        animate(dura, t -> {
            if (cerrado || !alive()) throw Stop.now();
            if (t < sale && t % 4 == 0) {
                for (int i = 0; i < dirs.size(); i++) linea(c, dirs.get(i), largos.get(i), ancho, CORTEZA, t);
                cuenta(objetivos, "Una lanza va por tu línea. Muévete a un lado:", sale - t);
            }
            if (t == sale) soundAt(c, "block.roots.break", 1.8f, 0.5f);
            if (t >= sale) {
                double recorrido = (t - sale) * v;
                for (int i = 0; i < dirs.size(); i++) {
                    if (recorrido > largos.get(i) + 1) continue;
                    Vector d = dirs.get(i);
                    Location punta = c.clone().add(d.clone().multiply(Math.min(recorrido, largos.get(i))));
                    Location cola = c.clone().add(d.clone().multiply(Math.max(0, recorrido - 3.4)));
                    lanza(cola, punta);
                    if (t % 2 == 0) fx(Compat.BLOCK, punta.clone().add(0, 0.3, 0), 3, 0.3, 0.1, 0.3, 0, roble);
                    for (Player p : Fx.playersNear(punta, 6)) {
                        if (golpeados.contains(p.getUniqueId())) continue;
                        if (Math.abs(p.getLocation().getY() - c.getY()) > ALTO + 1) continue;
                        if (distSegmento(p.getLocation(), cola, punta) > ancho) continue;
                        golpeados.add(p.getUniqueId());
                        hit(p, 20 * power(), mult);
                        impacto(p, roble);
                        push(p, d.clone().multiply(0.9).setY(0.35));
                    }
                }
            }
        }, null);
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
                if (t < t0 + viaje && t % 4 == 0) huecoMarcado(c, hueco[k], medio, R, k == 0 ? 1.5 : 3.0, t);
                if (t < t0 || t > t0 + viaje) continue;
                if (t == t0) cierre(c, R, hueco[k], medio, viaje);
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
                    impacto(p, musgo);
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
        announce(Component.text("Cruz de Raíces. No pisen los brazos que giran."));
        soundAt(c, "block.roots.place", 1.8f, 0.5f);

        animate(fin, t -> {
            if (cerrado || !alive()) throw Stop.now();
            double giro = t < carga ? 0 : Math.min(gira, t - carga) * w;
            if (t < carga) {
                if (t % 4 == 0) {
                    for (int i = 0; i < 4; i++) {
                        double a = base + i * Math.PI / 2;
                        linea(c, new Vector(Math.cos(a), 0, -Math.sin(a)), R, 0, CORTEZA, t);
                    }
                    cuenta(targets(), "Cruz de raíces. No pises los brazos cuando giren:", carga - t);
                }
                return;
            }
            if (t == carga) soundAt(c, "block.roots.break", 1.8f, 0.5f);
            if (t <= carga + gira && t % 2 == 0) {
                for (int i = 0; i < 4; i++) brazo(c, base + i * Math.PI / 2 + giro, R);
            }
            if (t == carga + gira) {
                // Se hunden: un ultimo reguero de astillas a lo largo de cada brazo.
                for (int i = 0; i < 4; i++) {
                    double a = base + i * Math.PI / 2 + giro;
                    for (double s = 1.5; s <= R; s += 2.5) {
                        fx(Compat.BLOCK, c.clone().add(Math.cos(a) * s, 0.3, -Math.sin(a) * s), 2, 0.2, 0.1, 0.2, 0, raigambre);
                    }
                }
            }
            if (t > carga + gira) return;
            if (t % 3 == 0) {
                for (int i = 0; i < 4; i++) {
                    double a = base + i * Math.PI / 2 + giro;
                    Location punta = c.clone().add(Math.cos(a) * R, 0.3, -Math.sin(a) * R);
                    fx(Compat.BLOCK, punta, 2, 0.3, 0.1, 0.3, 0, roble);
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
                    impacto(p, roble);
                    double tang = a + Math.PI / 2 * sentido;
                    push(p, new Vector(Math.cos(tang), 0, -Math.sin(tang)).multiply(0.6).setY(0.3));
                }
            }
        }, null);
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
                }
                if (t % 4 == 0) {
                    aviso(nube, radio, BORDE_ESPORAS, 0xE6E6DC, t, estalla);
                    /* La nube: un nucleo de musgo palido deshaciendose, esporas que flotan
                     * y otras que llueven sobre toda la zona que va a estallar. */
                    Location alto = nube.clone().add(0, 2.4, 0);
                    fx(Compat.FALLING_DUST, alto, 4, 0.5, 0.4, 0.5, 0, musgo);
                    fx(Compat.SPORE_BLOSSOM_AIR, alto, 6, 1.2, 0.6, 1.2, 0, null);
                    fx(Compat.WHITE_SMOKE, alto, 3, 0.8, 0.4, 0.8, 0.01, null);
                    fx(Compat.FALLING_SPORE_BLOSSOM, alto, 5, radio * 0.6, 0.3, radio * 0.6, 0, null);
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
                    impacto(p, musgo);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0, true, false, false));
                }
                // Revienta como una bola de polen: nube blanca, rafaga y esporas por todo el radio.
                Location boom = nube.clone().add(0, 1.2, 0);
                fx(Compat.CLOUD, boom, 30, radio * 0.45, 0.6, radio * 0.45, 0.04, null);
                fx(Compat.SMALL_GUST, boom, 3, 1.0, 0.4, 1.0, 0, null);
                fx(Compat.SPORE_BLOSSOM_AIR, boom, 30, radio * 0.6, 1.0, radio * 0.6, 0.02, null);
                fx(Compat.FALLING_SPORE_BLOSSOM, boom.clone().add(0, 1.5, 0), 30, radio * 0.6, 0.5, radio * 0.6, 0, null);
                fx(Compat.FALLING_DUST, boom, 16, radio * 0.5, 0.6, radio * 0.5, 0, musgo);
                Fx.shockwave(world(), nube, radio, Compat.WHITE_SMOKE, 2);
                soundAt(boom, "block.spore_blossom.break", 1.9f, 0.6f);
                soundAt(boom, "entity.generic.explode", 1.0f, 1.2f);
            }
        }, null);
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
                aviso(c, 2.5 + 2.5 * t / sale[0], BORDE_LATIDO, AMBAR, t, sale[0]);
                cuenta(targets(), "El corazón va a latir. Salta cuando llegue cada pulso:", sale[0] - t);
            }
            boolean pinta = t % 2 == 0;
            for (int k = 0; k < 3; k++) {
                if (t == sale[k]) {
                    Compat.spawn(world(), Compat.FLASH, center().add(0, 1.4, 0), 1);
                    fx(Compat.SMALL_GUST, c.clone().add(0, 0.6, 0), 2, 0.8, 0.1, 0.8, 0, null);
                    onda(c, R, vel[k], k == 2 ? RESINA : AMBAR);
                    soundAt(c, "block.creaking_heart.break", 1.8f, 0.6f + k * 0.15f);
                }
                if (t < sale[k]) continue;
                double r = (t - sale[k]) * vel[k];
                if (r > R) continue;
                if (pinta) frente(c, r, k == 2 ? resina : corazonCreaking, false);
                double banda = Math.max(0.7, vel[k] * 0.8);
                for (Player p : targets()) {
                    if (golpeados.get(k).contains(p.getUniqueId())) continue;
                    double d = plano(p.getLocation(), c);
                    if (Math.abs(d - r) > banda) continue;
                    if (Math.abs(p.getLocation().getY() - c.getY()) > ALTO + 1) continue;
                    if (enElAire(p)) continue;
                    golpeados.get(k).add(p.getUniqueId());
                    hit(p, 16 * power(), mult);
                    impacto(p, corazonCreaking);
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
        titleNear(Component.empty(), Component.text("Entren en un círculo verde", ACCENT));
        announce(Component.text("Bosque de Lanzas. Solo los círculos verdes son seguros."));
        soundAt(c, "block.creaking_heart.hurt", 1.9f, 0.5f);

        animate(carga + 30, t -> {
            if (cerrado || !alive()) throw Stop.now();
            if (t < carga && t % 4 == 0) {
                for (Location s : seguros) {
                    aviso(s, radio, BORDE_SEGURO, SEGURO, t, carga);
                    Compat.spawn(world(), Compat.END_ROD, s.clone().add(0, 0.6, 0), 1, 0.1, 0.6, 0.1, 0);
                    // Las luciernagas del jardin: solo vuelan donde no va a brotar nada.
                    if (t % 8 == 0) fx(Compat.FIREFLY, s.clone().add(0, 1.0, 0), 2, radio * 0.5, 0.5, radio * 0.5, 0, null);
                }
                // El resto de la arena: la tierra se abre a saltos, donde van a salir las raices.
                for (int i = 0; i < 18; i++) {
                    Location l = puntoDeArena(c, R);
                    if (enAlguno(l, seguros, radio)) continue;
                    fx(Compat.BLOCK, l.clone().add(0, 0.2, 0), 2, 0.2, 0.05, 0.2, 0, raigambre);
                }
                // El borde de la arena, en polvo de resina, a lo que llega el bosque.
                if (t % 8 == 0) {
                    Fx.ring(c.clone().add(0, 0.6, 0), R, (int) Fx.clamp(R * 1.5, 30, 72), p ->
                            fx(Compat.FALLING_DUST, p, 1, 0, 0, 0, 0, resina));
                }
                cuenta(targets(), "El bosque brota. Entra en un círculo verde:", carga - t);
            }
            if (t % 20 == 0 && t < carga) soundAt(c, "block.roots.place", 1.4f, 0.5f + t * 0.01f);
            if (t == carga) {
                List<Brote> brotes = new ArrayList<>();
                for (int i = 0; i < 80 && brotes.size() < 40; i++) {
                    Location l = puntoDeArena(c, R);
                    if (enAlguno(l, seguros, radio + 0.4)) continue;
                    brotes.add(brote(l, 1.8 + random.nextDouble() * 1.6, (random.nextDouble() - 0.5) * 0.6));
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
                    brotes.add(brote(Fx.ground(p.getLocation(), 4), 2.8, 0.15));
                    hit(p, 26 * power(), mult);
                    impacto(p, roble);
                    push(p, new Vector(0, 0.6, 0));
                }
                brotar(brotes, 20);
                fx(Compat.BLOCK, c.clone().add(0, 0.4, 0), 30, R * 0.4, 0.2, R * 0.4, 0, roble);
                soundAt(c, "block.roots.break", 2.0f, 0.5f);
                soundAt(c, "block.wood.break", 1.8f, 0.6f);
            }
        }, null);
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
                    aviso(centro, radio, BORDE_COSECHA, AMBAR, t, estalla);
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
                for (Player p : dentro) {
                    hit(p, cada, mult);
                    impacto(p, null);
                }
                // La siega: petalos de eyeblossom y grumos de resina por todo el circulo.
                Location boom = centro.clone().add(0, 0.6, 0);
                Compat.spawn(world(), Compat.FLASH, boom, 1);
                fx(Compat.BLOCK, boom, 30, radio * 0.5, 0.4, radio * 0.5, 0, ojo);
                fx(Compat.ITEM, boom, 12, radio * 0.4, 0.3, radio * 0.4, 0.08, grumo);
                Fx.ring(boom, radio, 10, p -> fx(Compat.SWEEP_ATTACK, p, 1, 0, 0, 0, 0, null));
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
        for (Location p : pilares) {
            fx(Compat.DUST_PILLAR, p.clone().add(0, 0.1, 0), 20, 0.5, 0, 0.5, 0, roble);
            fx(Compat.BLOCK, p.clone().add(0, 0.3, 0), 16, 0.8, 0.2, 0.8, 0, raigambre);
        }
        titleNear(Component.text("ESTALLIDO", TextColor.color(RESINA)),
                Component.text("Escóndanse detrás de un pilar", NamedTextColor.GRAY));
        announce(Component.text("Estallido del Corazón. Pongan un pilar entre ustedes y ROTTEN."));
        soundAt(c, "block.creaking_heart.spawn", 2.0f, 0.5f);

        animate(carga + 30, t -> {
            if (cerrado || !alive()) throw Stop.now();
            if (t <= carga + 20) for (Location p : pilares) pilar(p, rp, t);
            if (t < carga) {
                if (t % 4 == 0) {
                    double u = t / (double) carga;
                    // El corazon se carga: hilos de resina que vienen de fuera hacia el.
                    Location nucleo = center().add(0, 1.2, 0);
                    for (int i = 0; i < 4 + (int) (u * 6); i++) {
                        Vector d = alAzar().multiply(3.5 + random.nextDouble());
                        hilo(nucleo.clone().add(d), nucleo, i % 2 == 0 ? RESINA : AMBAR, 10, false);
                    }
                    fx(Compat.FALLING_DUST, nucleo, 3 + (int) (u * 6), 0.6 + u, 0.6 + u, 0.6 + u, 0, resina);
                    if (t % 8 == 0) fx(Compat.DRIPPING_HONEY, nucleo, 3, 0.8, 0.6, 0.8, 0, null);
                    // El borde de la arena, hasta donde llega el estallido.
                    if (t % 8 == 0) {
                        Fx.ring(c.clone().add(0, 0.6, 0), R, (int) Fx.clamp(R * 1.5, 30, 72), p ->
                                fx(Compat.FALLING_DUST, p, 1, 0, 0, 0, 0, resina));
                    }
                    // La sombra de cada pilar, en verde: ahi es seguro.
                    if (t % 8 == 0) {
                        for (Location p : pilares) {
                            Vector d = p.toVector().subtract(c.toVector()).setY(0);
                            if (d.lengthSquared() < 0.01) continue;
                            d.normalize();
                            for (double s = rp + 0.6; s <= rp + 6; s += 0.9) {
                                fx(Compat.HAPPY_VILLAGER, p.clone().add(d.clone().multiply(s)).add(0, 0.3, 0),
                                        1, 0.05, 0, 0.05, 0, null);
                            }
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
                    // El rayo: astillas de resina por el camino y hilos que llegan al jugador.
                    Location blanco = p.getLocation().add(0, 1, 0);
                    Fx.beam(corazon, blanco, 0.7, q -> fx(Compat.BLOCK_CRUMBLE, q, 1, 0.05, 0.05, 0.05, 0, resina));
                    Fx.beam(corazon, blanco, 1.4, q -> fx(Compat.FALLING_HONEY, q, 1, 0.05, 0.05, 0.05, 0, null));
                    for (int i = 0; i < 6; i++) hilo(corazon, blanco, RESINA, 3 + i, true);
                    hit(p, 35 * power(), mult);
                    impacto(p, resina);
                    push(p, fuera(p.getLocation(), c).multiply(0.7).setY(0.35));
                }
            }
            if (t == carga + 20) {
                // Los pilares se deshacen.
                for (Location p : pilares) {
                    fx(Compat.BLOCK_CRUMBLE, p.clone().add(0, 2.5, 0), 30, rp * 0.6, 1.8, rp * 0.6, 0, roble);
                    fx(Compat.BLOCK, p.clone().add(0, 1.5, 0), 20, rp * 0.6, 1.2, rp * 0.6, 0, roble);
                }
                soundAt(c, "block.wood.break", 1.6f, 0.6f);
            }
        }, null);
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

    // ------------------------------------------------------------- particulas

    /**
     * Como se pinta el borde de un aviso. 'perdura' marca las particulas que viven
     * segundos (FALLING_DUST, LANDING_HONEY, HAPPY_VILLAGER): esas se reponen cada
     * 8 ticks y no cada 4, o se amontonan.
     */
    private record Borde(Particle p, String dato, double alto, double paso, boolean perdura) {
    }

    /** Raices del Jardin: polvo de corteza palida que se posa en el borde del sello. */
    private static final Borde BORDE_RAIZ = new Borde(Compat.FALLING_DUST, "roble", 0.5, 0.7, true);
    /** Anillo de Espinas: chispas de golpe critico, las espinas. */
    private static final Borde BORDE_ESPINAS = new Borde(Compat.CRIT, null, 0.25, 0.8, false);
    /** Lluvia de Resina: polvo de bloque de resina, naranja. */
    private static final Borde BORDE_RESINA = new Borde(Compat.FALLING_DUST, "resina", 0.5, 0.7, true);
    /** Esporas: bocanadas de nube blanca; la marca se mueve con la nube, asi que no puede durar. */
    private static final Borde BORDE_ESPORAS = new Borde(Compat.CLOUD, null, 0.2, 0.9, false);
    /** Latido: miel ambar que se queda en el suelo, el charco del corazon. */
    private static final Borde BORDE_LATIDO = new Borde(Compat.LANDING_HONEY, null, 0.1, 0.8, true);
    /** Los circulos seguros del Bosque: el verde del crecimiento. */
    private static final Borde BORDE_SEGURO = new Borde(Compat.HAPPY_VILLAGER, null, 0.3, 0.8, true);
    /**
     * Cosecha: el unico borde en polvo de color. Sigue a un jugador que corre, y es lo
     * unico que se borra lo bastante rapido y se lee igual en Java y en Bedrock.
     */
    private static final Borde BORDE_COSECHA = new Borde(Compat.DUST, "ambar", 0.12, 0.6, false);

    private Object dato(String clave) {
        if (clave == null) return null;
        return switch (clave) {
            case "roble" -> roble;
            case "resina" -> resina;
            case "ambar" -> Compat.dust(AMBAR, 1.3f);
            default -> null;
        };
    }

    /** Una particula con o sin dato. */
    private void fx(Particle p, Location l, int n, double ox, double oy, double oz, double v, Object dato) {
        if (dato == null) Compat.spawn(world(), p, l, n, ox, oy, oz, v);
        else Compat.spawn(world(), p, l, n, ox, oy, oz, v, dato);
    }

    /**
     * Un hilo (TRAIL), como el que une el corazon de creaking a su creaking: una mota
     * que viaja de 'desde' a 'hasta' en 'ticks' y que anima el cliente, sin mas
     * paquetes. Con desde == hasta es una mota quieta que dura justo 'ticks'.
     * 'lejos' la manda a toda la arena (las que salen del centro y viajan lejos).
     * Solo Java: Geyser no la traduce, por eso nunca es lo unico que avisa.
     */
    private void hilo(Location desde, Location hasta, int rgb, int ticks, boolean lejos) {
        if (Compat.TRAIL == null || desde.getWorld() != hasta.getWorld()) return;
        Particle.Trail tr = new Particle.Trail(hasta, Color.fromRGB(rgb), Math.max(1, ticks));
        if (lejos) Compat.spawnForced(world(), Compat.TRAIL, desde, 1, 0, 0, 0, 0, tr);
        else Compat.spawn(world(), Compat.TRAIL, desde, 1, 0, 0, 0, 0, tr);
    }

    /** Un golpe que entra: el tajo y unas astillas del material del hechizo. */
    private void impacto(Player v, BlockData astilla) {
        Location l = v.getLocation().add(0, 1.0, 0);
        fx(Compat.SWEEP_ATTACK, l, 1, 0, 0, 0, 0, null);
        if (astilla != null) fx(Compat.BLOCK, l, 8, 0.3, 0.4, 0.3, 0, astilla);
    }

    /**
     * El aviso de un circulo: el borde con la firma del hechizo y un aro interior de
     * motas que crece hasta llenarlo cuando toca el golpe. Se llama cada 4 ticks.
     */
    private void aviso(Location c, double r, Borde b, int rgb, int t, int total) {
        if (!b.perdura() || t % 8 == 0) {
            int puntos = (int) Fx.clamp(TAU * r / b.paso(), 10, 90);
            Object d = dato(b.dato());
            Fx.ring(c.clone().add(0, b.alto(), 0), r, puntos, p -> fx(b.p(), p, 1, 0, 0, 0, 0, d));
        }
        double u = Fx.clamp(t / (double) Math.max(1, total), 0, 1);
        double ri = Math.max(0.3, r * u);
        int n = (int) Fx.clamp(TAU * ri / 0.9, 6, 60);
        Fx.ring(c.clone().add(0, 0.15, 0), ri, n, p -> hilo(p, p, rgb, 5, false));
    }

    /**
     * El frente de una onda que sale del centro, para Java: un aro de hilos que el
     * cliente lleva solo del centro al borde a la velocidad del golpe. Se lanza UNA vez.
     */
    private void onda(Location c, double R, double v, int rgb) {
        int puntos = (int) Fx.clamp(R * 3.2, 24, 150);
        int dura = (int) Math.ceil(R / v);
        Location o = c.clone().add(0, 0.35, 0);
        for (int i = 0; i < puntos; i++) {
            double a = TAU * i / puntos;
            hilo(o, o.clone().add(Math.cos(a) * R, 0, Math.sin(a) * R), rgb, dura, true);
        }
    }

    /** Por donde pasa el frente ahora: astillas del material (lo que ve Bedrock) y, si son espinas, chispas. */
    private void frente(Location c, double r, BlockData astilla, boolean espinas) {
        if (r < 0.5) return;
        int puntos = (int) Fx.clamp(r * 1.2, 8, 60);
        Fx.ring(c.clone().add(0, 0.3, 0), r, puntos, random.nextDouble() * TAU, p -> {
            fx(Compat.BLOCK, p, 1, 0.15, 0.1, 0.15, 0, astilla);
            if (espinas && random.nextInt(3) == 0) {
                fx(Compat.CRIT, p.clone().add(0, 0.2, 0), 1, 0.05, 0.15, 0.05, 0.05, null);
            }
        });
    }

    /**
     * Una linea del suelo desde c en la direccion d: polvo de corteza en el eje y en
     * los bordes (si tiene ancho), y un hilo que corre por ella hacia fuera.
     */
    private void linea(Location c, Vector d, double largo, double ancho, int rgb, int t) {
        if (t % 8 == 0) {
            Vector n = new Vector(-d.getZ(), 0, d.getX());
            int i = 0;
            for (double s = 1.5; s <= largo; s += 1.0, i++) {
                Location p = c.clone().add(d.clone().multiply(s)).add(0, 0.5, 0);
                if (ancho <= 0 || i % 3 == 0) fx(Compat.FALLING_DUST, p, 1, 0, 0, 0, 0, roble);
                if (ancho > 0 && i % 2 == 0) {
                    fx(Compat.FALLING_DUST, p.clone().add(n.clone().multiply(ancho)), 1, 0, 0, 0, 0, roble);
                    fx(Compat.FALLING_DUST, p.clone().add(n.clone().multiply(-ancho)), 1, 0, 0, 0, 0, roble);
                }
            }
        }
        Location o = c.clone().add(0, 0.25, 0);
        hilo(o.clone().add(d.clone().multiply(1.5)), o.clone().add(d.clone().multiply(largo)), rgb, 12, true);
    }

    /** La lanza que corre: un tronco de 3,4 bloques de astillas de roble palido a media altura. */
    private void lanza(Location cola, Location punta) {
        Fx.beam(cola.clone().add(0, 0.9, 0), punta.clone().add(0, 0.9, 0), 0.45, p ->
                fx(Compat.BLOCK_CRUMBLE, p, 1, 0.08, 0.08, 0.08, 0, roble));
        fx(Compat.BLOCK, punta.clone().add(0, 0.9, 0), 2, 0.15, 0.15, 0.15, 0, roble);
        fx(Compat.CRIT, punta.clone().add(0, 0.9, 0), 1, 0.05, 0.05, 0.05, 0.1, null);
    }

    /** Un brazo de la Cruz en el angulo a: astillas de roble que se deshacen enseguida, para que se vea girar. */
    private void brazo(Location c, double a, double R) {
        int i = 0;
        for (double s = 1.5; s <= R; s += 1.2, i++) {
            Location p = c.clone().add(Math.cos(a) * s, 0.35, -Math.sin(a) * s);
            if (i % 2 == 0) fx(Compat.BLOCK_CRUMBLE, p, 1, 0.1, 0.05, 0.1, 0, roble);
            else fx(Compat.BLOCK, p, 1, 0.1, 0.05, 0.1, 0, roble);
        }
    }

    /** Un anillo de musgo palido con su hueco (sector sin pintar): por donde pasa ahora. */
    private void anilloConHueco(Location c, double r, double hueco, double medio) {
        int puntos = (int) Fx.clamp(r * 1.6, 10, 80);
        for (int i = 0; i < puntos; i++) {
            double a = TAU * i / puntos;
            if (Math.abs(normal(a - hueco)) <= medio) continue;
            Location p = c.clone().add(Math.cos(a) * r, 0.4, Math.sin(a) * r);
            fx(Compat.BLOCK, p, 1, 0.1, 0.25, 0.1, 0, musgo);
            if (random.nextInt(5) == 0) {
                fx(Compat.PALE_OAK_LEAVES, p.clone().add(0, 0.8, 0), 1, 0.1, 0.2, 0.1, 0, null);
            }
        }
    }

    /** El anillo que se cierra, para Java: hilos que van solos del borde al centro, sin el hueco. */
    private void cierre(Location c, double R, double hueco, double medio, int viaje) {
        int puntos = (int) Fx.clamp(R * 3.2, 24, 150);
        Location o = c.clone().add(0, 0.35, 0);
        for (int i = 0; i < puntos; i++) {
            double a = TAU * i / puntos;
            if (Math.abs(normal(a - hueco)) <= medio) continue;
            Vector u = new Vector(Math.cos(a), 0, Math.sin(a));
            hilo(o.clone().add(u.clone().multiply(R)), o.clone().add(u.multiply(1.5)), CORTEZA, viaje, true);
        }
    }

    /** Las dos rayas verdes que limitan el hueco de un anillo, del centro al borde. */
    private void huecoMarcado(Location c, double hueco, double medio, double R, double paso, int t) {
        if (t % 8 == 0) {
            for (double borde : new double[]{hueco - medio, hueco + medio}) {
                for (double s = 3; s <= R; s += paso) {
                    Location p = c.clone().add(Math.cos(borde) * s, 0.3, Math.sin(borde) * s);
                    fx(Compat.HAPPY_VILLAGER, p, 1, 0, 0, 0, 0, null);
                }
            }
        }
        Location p = c.clone().add(Math.cos(hueco) * R * 0.8, 0.4, Math.sin(hueco) * R * 0.8);
        Compat.spawn(world(), Compat.END_ROD, p, 1, 0.1, 0.4, 0.1, 0);
    }

    /**
     * Un pilar del Estallido, solo particulas: el icono del tronco de roble palido
     * apilado (BLOCK_MARKER, quieto 4 s: se repone a los 0 y 40 ticks para durar los
     * 6 s), polvo de corteza alrededor (lo que ve Bedrock) y musgo colgando de la copa.
     * La cobertura sigue saliendo de su posicion (cubierto), no de lo que se pinta.
     */
    private void pilar(Location p, double rp, int t) {
        if (t == 0 || t == 40) {
            for (double y = 0.5; y <= 4.6; y += 1.0) {
                Compat.spawnForced(world(), Compat.BLOCK_MARKER, p.clone().add(0, y, 0), 1, 0, 0, 0, 0, roble);
            }
        }
        if (t % 8 != 0) return;
        for (double y = 0.6; y <= 4.6; y += 1.0) {
            Fx.ring(p.clone().add(0, y, 0), rp * 0.8, 4, y, q -> fx(Compat.FALLING_DUST, q, 1, 0, 0, 0, 0, roble));
        }
        Fx.ring(p.clone().add(0, 5.0, 0), rp * 1.1, 6, q -> fx(Compat.FALLING_DUST, q, 1, 0, 0, 0, 0, musgo));
        fx(Compat.PALE_OAK_LEAVES, p.clone().add(0, 5.2, 0), 1, rp * 0.6, 0.2, rp * 0.6, 0, null);
    }

    /** Una direccion al azar en el espacio, de largo 1. */
    private Vector alAzar() {
        Vector d = new Vector(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
        return d.lengthSquared() < 1e-6 ? new Vector(0, 1, 0) : d.normalize();
    }

    /** Una raiz que va a brotar: su pie y su inclinacion (desplazamiento en planta por bloque de alto). */
    private record Brote(Location base, double alto, double dx, double dz) {
    }

    private Brote brote(Location base, double alto, double incl) {
        double a = random.nextDouble() * TAU;
        return new Brote(sinGiro(base), alto, Math.cos(a) * incl, Math.sin(a) * incl);
    }

    /**
     * Brotan raices de particulas: un estallido de tierra al pie y una columna de
     * astillas de roble que sube en 3 ticks, aguanta 'vida' y se hunde. Una sola
     * animacion para todas, repintada cada 3 ticks.
     */
    private void brotar(List<Brote> brotes, int vida) {
        if (brotes.isEmpty()) return;
        for (Brote b : brotes) {
            fx(Compat.DUST_PILLAR, b.base().clone().add(0, 0.1, 0), 4, 0.2, 0, 0.2, 0, raigambre);
            fx(Compat.BLOCK, b.base().clone().add(0, 0.3, 0), 3, 0.3, 0.1, 0.3, 0, raigambre);
        }
        /* Con muchas raices a la vez (Bosque de Lanzas, Raices con 10 jugadores) la
         * columna se aclara para no pasar de ~150 particulas por tick. */
        final double paso = Math.min(0.7, 0.4 * Math.max(1.0, brotes.size() / 24.0));
        animate(vida + 6, t -> {
            if (cerrado) throw Stop.now();
            if (t % 3 != 0) return;
            double k = t < 3 ? (t + 1) / 3.0 : t > vida ? Math.max(0, 1 - (t - vida) / 6.0) : 1;
            for (Brote b : brotes) {
                double h = b.alto() * k;
                int i = 0;
                for (double y = 0.2; y <= h; y += paso, i++) {
                    Location p = b.base().clone().add(b.dx() * y, y, b.dz() * y);
                    fx(Compat.BLOCK_CRUMBLE, p, 1, 0.08, 0.05, 0.08, 0, roble);
                    if (i % 3 == 0 && t < vida) fx(Compat.FALLING_DUST, p, 1, 0.1, 0, 0.1, 0, roble);
                }
            }
        }, null);
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
