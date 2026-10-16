package net.ederus.edm.anomaly.boss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.ederus.edm.anomaly.AnomalyPlugin;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.ederus.edm.anomaly.core.ActiveAnomaly;
import net.ederus.edm.anomaly.core.ArenaGuard;
import net.ederus.edm.anomaly.core.Disguises;
import net.ederus.edm.anomaly.core.Glow;
import net.ederus.edm.anomaly.core.Stop;
import net.ederus.edm.anomaly.fx.Instancia;
import net.ederus.edm.anomaly.fx.ModeloBde;
import net.ederus.edm.anomaly.fx.Opciones;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.ederus.edm.comun.Tags;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.Bat;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * CONDE, el señor de los murcielagos. La anomalia de Halloween.
 *
 * Un vampiro con cuerpo de persona (maniqui con la skin de una cuenta real, como
 * Rabby) que pelea con lo que tiene a mano: murcielagos, oscuridad y sangre. Casi todo
 * lo suyo se avisa con un CIRCULO MAGICO rojo y negro dibujado en el suelo; la regla de
 * la pelea es la misma de principio a fin: si ves un circulo rojo, sal de el.
 *
 * El circulo esta hecho con entidades de visualizacion (texto con runas y bloques
 * finos) y no con bloques reales: las anomalias abren en el Coliseo, dentro de una
 * region protegida, y ahi no se pone ni se rompe nada. Las oleadas grandes (niebla,
 * pulsos) van con particulas, que a cientos se ven mejor que los displays.
 *
 * Los murcielagos no tienen IA: vuelan por teleport tick a tick, porque setVelocity
 * no mueve a un mob con IA y un murcielago con IA vuela a donde le da la gana.
 */
public final class Conde extends BossFight {

    public static final String ID = "conde";
    public static final TextColor ACCENT = TextColor.color(0xB3122E);

    /** La cuenta que lleva la skin del Conde. Verificada en Mojang. */
    private static final String SKIN_ACCOUNT = "xXJetArtXx";

    /* La paleta: rojo sangre, un rojo mas oscuro y casi negro. Pocos colores y
     * siempre los mismos, para que todo lo del Conde se reconozca de un vistazo. */
    private static final int SANGRE = 0xB3122E;
    private static final int GRANATE = 0x5A0A14;
    private static final int NEGRO = 0x14050A;

    private static final Color C_SANGRE = Color.fromRGB(SANGRE);
    private static final Color C_NEGRO = Color.fromRGB(NEGRO);

    /** Las runas del circulo. Todas se leen bien tumbadas en el suelo. */
    private static final String[] GLIFOS = {"✦", "ᚱ", "†", "ᛉ", "✧", "ᚦ", "◆", "⛧"};
    /** Los glifos grandes de los puntos cardinales del circulo. */
    private static final String[] GLIFOS_GRANDES = {"⛧", "†", "ᛉ", "†"};

    /* Los colmillos de evoker que son nuestros: su mordisco vanilla (6 fijos, sin el
     * multiplicador del menu) se cancela y el dano lo pone hit(). Si no, pegaria doble. */
    private final Set<UUID> colmillos = new HashSet<>();
    /* A quien se le puso la noche con Medianoche, para devolverle la hora siempre. */
    private final Set<UUID> noche = new HashSet<>();
    private int nocheTurno;

    /** El murcielago gigante mientras dura la Forma de Murcielago; null en forma humana. */
    private Bat formaBat;
    private boolean cerrado;
    private double damageBonus = 1.0;

    /** Las cuatro velas rojas que le giran alrededor del pecho. Ver encenderVelas(). */
    private final List<BlockDisplay> velas = new ArrayList<>();
    private double giroVelas;

    /** Los Vastagos que siguen en pie o cayendo. Ver la seccion VASTAGOS. */
    private final List<Vastago> vastagos = new ArrayList<>();
    /** La skin de los Vastagos ya resuelta contra Mojang; null hasta que llega. */
    private ResolvableProfile perfilVastago;
    /** Mientras un mordisco de Vastago reparte su salpicadura, para no salpicar dos veces. */
    private boolean mordiendo;

    private final Escucha escucha = new Escucha();

    public Conde(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        super(plugin, event, where);
        abilities.addAll(plugin.registry().conde().abilities());
    }

    @Override
    public String bossName() {
        return "CONDE";
    }

    @Override
    public TextColor accent() {
        return ACCENT;
    }

    /** Sus barras van en rojo en las tres fases: es el color de la sangre y del Conde. */
    @Override
    public net.kyori.adventure.bossbar.BossBar.Color barColor() {
        return net.kyori.adventure.bossbar.BossBar.Color.RED;
    }

    // ------------------------------------------------------------------- aparicion

    @Override
    public void spawn() {
        Location spot = Fx.ground(arena.clone(), 4);
        spot.setYaw(0);
        spot.setPitch(0);

        /* Nace tumbado dentro del ataud de la entrada (ver entrada()): sin IA, sin
         * gravedad e intocable hasta que salta fuera. El cliente pinta la pose de
         * dormir girando el cuerpo desde los pies, con la cabeza hacia el eje largo
         * del ataud: de ahi el yaw y los pies retrasados 0,9 (como el Vastago). */
        final float giro = (float) (random.nextDouble() * Math.PI * 2);
        final Vector3f largo = new Quaternionf().rotateY(giro).transform(new Vector3f(0, 0, 1));
        final float yawTumbado = (float) Math.toDegrees(Math.atan2(largo.z, -largo.x));
        final Location encima = spot.clone().add(0, VASTAGO_ALTO_ATAUD * ENTRADA_ESCALA, 0);
        final Location pies = encima.clone().add(-largo.x * 0.9, 0, -largo.z * 0.9);
        pies.setYaw(yawTumbado);
        pies.setPitch(0);

        boss = world().spawn(pies, Zombie.class, z -> {
            z.setAdult();
            z.setPersistent(true);
            z.setRemoveWhenFarAway(false);
            z.setCanPickupItems(false);
            z.setShouldBurnInDay(false);
            z.setAI(false);
            z.setGravity(false);
            z.setInvisible(true);
            z.setInvulnerable(true);
            /* Con nombre aunque no se vea: lo leen los mensajes de muerte. */
            z.customName(Component.text("CONDE", ACCENT));
            z.setCustomNameVisible(false);
        });
        EntityEquipment eq = boss.getEquipment();
        if (eq != null) {
            eq.setHelmetDropChance(0);
            eq.setItemInMainHandDropChance(0);
        }

        // El cuerpo de persona, como Rabby: nace con el perfil sin resolver y en
        // cuanto Mojang devuelve el de la cuenta se le pone la cara buena.
        wearShell(Disguises.profileOfAccount(plugin, SKIN_ACCOUNT),
                Component.text("CONDE", ACCENT, TextDecoration.BOLD));
        Disguises.resolveAccount(plugin, SKIN_ACCOUNT, this::reskinShell);
        // La de los Vastagos se pide ya: cuando salga el primero, la cara buena estara lista.
        Disguises.resolveAccount(plugin, SKIN_VASTAGO, p -> perfilVastago = p);

        Compat.setAttribute(boss, "max_health", 20);
        Compat.setAttribute(boss, "attack_damage", 14);
        Compat.setAttribute(boss, "armor", 10);
        Compat.setAttribute(boss, "knockback_resistance", 1.0);
        Compat.setAttribute(boss, "follow_range", 64);
        Compat.setAttribute(boss, "movement_speed", 0.30);
        applyHealth(plugin.registry().health(plugin.registry().get(ID)));
        boss.setMaximumNoDamageTicks(8);
        Tags.markBoss(boss, ID);

        // Escondido y tumbado hasta que se abre la tapa: el brillo se le pone al asomar.
        if (shell instanceof Mannequin m && m.isValid()) {
            m.setInvisible(true);
            m.setCustomNameVisible(false);
            m.setInvulnerable(true);
            posar(m, tumbado());
            try {
                m.setRotation(yawTumbado, 0f);
                m.setBodyYaw(yawTumbado);
            } catch (Throwable ignored) {
            }
        }

        // Su escucha propia: colmillos sin dano vanilla y la hora de vuelta a quien se va.
        plugin.getServer().getPluginManager().registerEvents(escucha, net.ederus.edm.Module.dueno(plugin));

        // La Rafaga de Sangre es de la fase II en adelante: en la I se queda en espera.
        // El cambio de fase (reset de todas las habilidades) la deja lista.
        for (Ability a : abilities) {
            if (a.id().equals("cd_rafaga") && a.phase() == 0) a.startCooldown(Long.MAX_VALUE / 4);
        }

        busyFor(ENTRADA_FIN + 5);
        entrada(spot, giro, largo, encima);
    }

    /* La entrada: un ataud a escala 1,4 sube del suelo en 20 ticks y se abre en 15;
     * dentro esta el Conde tumbado, se incorpora en 10 y salta fuera por los pies en
     * otros 10. Al tocar el suelo el ataud se hunde y se desmonta mientras salen
     * catorce murcielagos, y la pelea empieza. Unos 60 ticks intocable. */
    private static final float ENTRADA_ESCALA = 1.4f;
    private static final int ENTRADA_SUBE = 1;
    private static final int ENTRADA_ABRE = 20;
    private static final int ENTRADA_ASOMA = 35;
    private static final int ENTRADA_SALTA = 47;
    private static final int ENTRADA_ATERRIZA = 57;
    private static final int ENTRADA_FIN = 76;

    private void entrada(Location spot, float giro, Vector3f largo, Location encima) {
        final Ataud at = new Ataud(spot, giro, ENTRADA_ESCALA);
        // Sale por los pies del ataud, mirando hacia donde salta.
        final Vector salida = new Vector(-largo.x, 0, -largo.z);
        final Location suelo = Fx.ground(spot.clone().add(salida.clone().multiply(2.7)), 4);
        final boolean[] fuera = {false};

        animate(ENTRADA_FIN, t -> {
            if (cerrado || boss == null || !boss.isValid()) throw Stop.now();
            if (t == ENTRADA_SUBE) at.colocar(0f, 0f, 20);
            if (t < 22 && t % 4 == 0) {
                Compat.spawn(world(), Compat.BLOCK, spot.clone().add(0, 0.2, 0), 8, 0.7, 0.1, 1.2, 0,
                        Material.SOUL_SOIL.createBlockData());
                soundAt(spot, "block.gravel.break", 1.2f, 0.6f);
            }
            if (t == ENTRADA_ABRE) {
                at.colocar(0f, 1.9f, 15);
                for (Player p : Fx.viewersNear(spot, 90)) {
                    p.showTitle(Title.title(
                            Component.text("✦ ANOMALÍA ✦", ACCENT),
                            Component.text("CONDE  ·  el señor de los murciélagos", NamedTextColor.GRAY),
                            Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1800), Duration.ofMillis(600))));
                }
                Location l = spot.clone().add(0, 1.2, 0);
                Compat.spawn(world(), Compat.LARGE_SMOKE, l, 30, 0.6, 0.6, 0.9, 0.03);
                Compat.spawn(world(), Compat.FLASH, l, 1, 0, 0, 0, 0, C_SANGRE);
                soundAt(spot, "entity.wither.spawn", 1.2f, 1.4f);
                soundAt(spot, "block.chest.open", 1.6f, 0.5f);
                soundAt(spot, "block.bell.use", 1.6f, 0.5f);
            }
            if (t >= 22 && t < ENTRADA_ABRE && t % 4 == 0) at.llama();
            if (t == ENTRADA_ASOMA) {
                LivingEntity cuerpo = super.shell();
                if (cuerpo != null && cuerpo.isValid()) {
                    cuerpo.setInvisible(false);
                    cuerpo.setCustomNameVisible(true);
                }
                glowBody(NamedTextColor.DARK_RED);
                Compat.spawn(world(), Compat.LARGE_SMOKE, encima, 12, 0.3, 0.2, 0.6, 0.01);
            }
            if (t == ENTRADA_ASOMA + 5) {
                // Medio incorporado, ya en el centro del ataud y mirando a donde va a saltar.
                if (super.shell() instanceof Mannequin m) posar(m, Pose.SNEAKING);
                Location c = encima.clone();
                c.setDirection(salida);
                boss.teleport(c);
            }
            if (t == ENTRADA_ASOMA + 10) {
                if (super.shell() instanceof Mannequin m) posar(m, Pose.STANDING);
                soundAt(encima, "entity.bat.takeoff", 2.0f, 0.6f);
            }
            if (t >= ENTRADA_SALTA && t < ENTRADA_ATERRIZA) {
                double u = (t - ENTRADA_SALTA + 1) / (double) (ENTRADA_ATERRIZA - ENTRADA_SALTA);
                double h = sm(u);
                Location p = new Location(world(),
                        lerp(encima.getX(), suelo.getX(), h),
                        lerp(encima.getY(), suelo.getY(), u) + 1.15 * Math.sin(Math.PI * u),
                        lerp(encima.getZ(), suelo.getZ(), h));
                p.setDirection(salida);
                p.setPitch(0);
                boss.teleport(p);
            }
            if (t == ENTRADA_ATERRIZA) {
                fuera[0] = true;
                soltarEntrada();
                Location l = boss.getLocation().add(0, 0.2, 0);
                Compat.spawn(world(), Compat.LARGE_SMOKE, l, 16, 0.6, 0.1, 0.6, 0.02);
                Compat.spawn(world(), Compat.BLOCK, l, 12, 0.6, 0.1, 0.6, 0, Material.SOUL_SOIL.createBlockData());
                soundAt(l, "entity.wither.shoot", 1.0f, 0.5f);
                soundAt(l, "entity.bat.takeoff", 2.0f, 0.6f);
                at.colocar(-0.57f, 1.9f, 12);
                dispersar(spot.clone().add(0, 1.0, 0), 14, 30);
                encenderVelas();
            }
            if (t == ENTRADA_ATERRIZA + 2) at.desmontar();
        }, () -> {
            at.quitar();
            if (!fuera[0] && !cerrado) {
                /* Cortada a medias: si el cuerpo no estaba (referencia vieja que el
                 * manager reengancha luego), se suelta en cuanto vuelva (ambient):
                 * si no, el jefe se quedaba sin IA ni gravedad flotando sobre el ataud. */
                if (!soltarEntrada()) entradaPendiente = true;
                encenderVelas();
            }
        });
    }

    /** La entrada se corto sin que el cuerpo estuviera: falta soltarlo. */
    private boolean entradaPendiente;

    /**
     * El final de la entrada: IA, gravedad y golpeable. Tambien si la escena se corta.
     * Devuelve false si no habia cuerpo al que soltar.
     */
    private boolean soltarEntrada() {
        if (boss == null || !boss.isValid()) return false;
        boss.setAI(true);
        boss.setGravity(true);
        boss.setInvulnerable(false);
        LivingEntity cuerpo = super.shell();
        if (cuerpo != null && cuerpo.isValid()) {
            cuerpo.setInvulnerable(false);
            if (cuerpo.isInvisible()) {
                cuerpo.setInvisible(false);
                cuerpo.setCustomNameVisible(true);
                glowBody(NamedTextColor.DARK_RED);
            }
            if (cuerpo instanceof Mannequin m) posar(m, Pose.STANDING);
        }
        return true;
    }

    // -------------------------------------------------------------------- ambiente

    /**
     * Un aura sobria: un poco de ceniza a los pies, un alma de vez en cuando y sus
     * cuatro velas girando alrededor del pecho. No es una nube; es lo justo para saber
     * cual de los cuerpos es el.
     */
    @Override
    protected void ambient() {
        if (!alive()) return;
        if (entradaPendiente && soltarEntrada()) entradaPendiente = false;
        moverVelas();
        if (formaBat != null && formaBat.isValid()) {
            if (ticks() % 2 == 0) {
                Compat.spawn(world(), Compat.SQUID_INK, formaBat.getLocation().add(0, 1.2, 0), 1,
                        0.3, 0.3, 0.3, 0.005);
            }
            return;
        }
        Location pies = boss.getLocation().add(0, 0.15, 0);
        if (ticks() % 10 == 0) Compat.spawn(world(), Compat.ASH, pies, 3, 0.45, 0.05, 0.45, 0);
        if (ticks() % 25 == 0) Compat.spawn(world(), Compat.SOUL, pies, 1, 0.35, 0.05, 0.35, 0.01);
    }

    /*
     * Las velas del aura: cuatro velas rojas encendidas (BlockDisplay a media escala)
     * que dan una vuelta cada diez segundos a 1,6 bloques del cuerpo, a la altura del
     * pecho. Van por teleport con interpolacion de dos ticks, asi que se deslizan.
     * La llama es una sola chispa de fuego pequeño de vez en cuando: un BlockDisplay
     * no saca las particulas de una vela de verdad.
     */
    private static final double RADIO_VELAS = 1.6;

    private void encenderVelas() {
        // Una sola vez por pelea: la entrada las enciende al aterrizar o, si se corta, al final.
        if (boss == null || !velas.isEmpty() || cerrado) return;
        Location c = boss.getLocation();
        for (int i = 0; i < 4; i++) {
            BlockDisplay v = vela(puntoVela(c, i), true, 2);
            if (v != null) velas.add(v);
        }
    }

    private Location puntoVela(Location c, int i) {
        double a = giroVelas + Math.PI / 2 * i;
        // Un vaiven minimo en altura, desfasado por vela, para que no parezcan clavadas.
        double y = 1.2 + Math.sin(giroVelas * 3 + i * 1.7) * 0.07;
        Location p = c.clone().add(Math.cos(a) * RADIO_VELAS, y, Math.sin(a) * RADIO_VELAS);
        p.setYaw(0);
        p.setPitch(0);
        return p;
    }

    private void moverVelas() {
        if (velas.isEmpty() || ticks() % 2 != 0) return;
        giroVelas += 0.063; // una vuelta cada ~200 ticks
        Location c = boss.getLocation();
        for (int i = 0; i < velas.size(); i++) {
            BlockDisplay v = velas.get(i);
            if (v == null || !v.isValid()) continue;
            v.teleport(puntoVela(c, i));
            if ((ticks() + i * 4) % 16 == 0) {
                Compat.spawn(world(), Compat.SMALL_FLAME, v.getLocation().add(0, 0.24, 0), 1, 0, 0, 0, 0);
            }
        }
    }

    /** Al morir: las velas se apagan, sueltan un hilo de humo y caen al suelo. */
    private void apagarVelas() {
        for (BlockDisplay v : velas) {
            if (v == null || !v.isValid()) continue;
            try {
                BlockData apagada = Material.RED_CANDLE.createBlockData();
                if (apagada instanceof Lightable l) l.setLit(false);
                v.setBlock(apagada);
                Compat.spawn(world(), Compat.SMOKE, v.getLocation().add(0, 0.25, 0), 3, 0.02, 0.05, 0.02, 0.01);
                v.setTeleportDuration(12);
                Location suelo = Fx.ground(v.getLocation(), 6).add(0, 0.01, 0);
                suelo.setYaw(0);
                suelo.setPitch(0);
                later(1, () -> {
                    if (v.isValid()) v.teleport(suelo);
                });
                // Tumbada al caer: medio giro sobre su eje X en lo que dura la caida.
                // (Girada sobre la esquina del bloque: el 0.2 de altura la deja apoyada.)
                mover(v, new Transformation(new Vector3f(-0.25f, 0.2f, -0.25f),
                        new Quaternionf().rotateX((float) (Math.PI / 2)), new Vector3f(0.5f), new Quaternionf()), 12);
            } catch (Throwable ignored) {
            }
            expire(v, 50);
        }
        velas.clear();
    }

    /** Una vela roja suelta, centrada en su punto. Encendida o no. */
    private BlockDisplay vela(Location at, boolean encendida, int teleport) {
        try {
            BlockData bd = Material.RED_CANDLE.createBlockData();
            if (bd instanceof Lightable l) l.setLit(encendida);
            BlockDisplay d = world().spawn(at, BlockDisplay.class, e -> {
                e.setBlock(bd);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setViewRange(2.0f);
                e.setShadowRadius(0f);
                e.setPersistent(false);
                e.setTeleportDuration(teleport);
                e.setTransformation(new Transformation(new Vector3f(-0.25f, 0f, -0.25f),
                        new Quaternionf(), new Vector3f(0.5f), new Quaternionf()));
            });
            Tags.markTemporary(d);
            track(d);
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    // --------------------------------------------------------------- cambio de fase

    /** Las fases solo avanzan: el Conde se cura, y curarse no le devuelve una fase. */
    @Override
    protected boolean canChangePhase(int from, int to) {
        return to > from;
    }

    @Override
    protected void onPhaseChange(int from, int to) {
        if (event.bars() != null) event.bars().flash(from);
        Location l = loc();
        if (to == 2) {
            damageBonus = 1.1;
            Compat.setAttribute(boss, "movement_speed", 0.33);
            Compat.setAttribute(boss, "attack_damage", 18);
            titleNear(Component.text("FASE II", ACCENT),
                    Component.text("Tiene sed", NamedTextColor.GRAY));
        }
        if (to == 3) {
            damageBonus = 1.2;
            Compat.setAttribute(boss, "movement_speed", 0.36);
            Compat.setAttribute(boss, "attack_damage", 22);
            titleNear(Component.text("FASE III", ACCENT),
                    Component.text("Se acerca la medianoche", NamedTextColor.GRAY));
        }
        Compat.spawn(world(), Compat.FLASH, l.clone().add(0, 1, 0), 1, 0, 0, 0, 0, C_SANGRE);
        Compat.spawn(world(), Compat.LARGE_SMOKE, l.clone().add(0, 1, 0), 30, 0.6, 0.8, 0.6, 0.04);
        soundAt(l, "entity.wither.ambient", 1.6f, 0.6f);
        soundAt(l, "block.bell.use", 1.6f, 0.5f);
        dispersar(l.clone().add(0, 1.2, 0), 8, 25);
    }

    // ---------------------------------------------------------------------- muerte

    /**
     * Se deshace en murcielagos. El cuerpo se esconde en el acto y lo que queda es la
     * bandada saliendo en todas direcciones; dos segundos despues no queda nada.
     */
    @Override
    public void onDeath() {
        devolverHora();
        volverHumano(false);
        apagarVelas();
        // Sus Vastagos caen con el.
        for (Vastago v : new ArrayList<>(vastagos)) v.caer(false);
        Location l = loc().clone().add(0, 1.1, 0);
        LivingEntity cuerpo = shell;
        if (cuerpo != null && cuerpo.isValid()) {
            cuerpo.setInvisible(true);
            cuerpo.setCustomNameVisible(false);
            quitarBrillo(cuerpo);
        }
        Compat.spawn(world(), Compat.LARGE_SMOKE, l, 50, 0.5, 0.8, 0.5, 0.05);
        Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, l, 40, 0.6, 0.8, 0.6, 0, transicion(1.6f));
        soundAt(l, "entity.wither.death", 1.0f, 0.5f);
        for (int i = 0; i < 3; i++) {
            final float pitch = 0.7f + i * 0.2f;
            later(i * 6, () -> soundAt(l, "entity.bat.death", 1.8f, pitch));
        }
        dispersar(l, 30, 40);
        later(42, () -> announce(Component.text("se deshace en murciélagos y se va con la noche.", ACCENT)));
    }

    // -------------------------------------------------------------------- limpieza

    /**
     * Ademas de lo de siempre: la hora del cielo a todos, la forma de murcielago y la
     * escucha propia. Pasa al morir, al pararlo a mano y al cerrar el servidor.
     */
    @Override
    public void cleanup() {
        cerrado = true;
        devolverHora();
        if (formaBat != null) {
            Glow.clear(formaBat);
            Fx.safeRemove(formaBat);
            formaBat = null;
        }
        try {
            HandlerList.unregisterAll(escucha);
        } catch (Throwable ignored) {
        }
        colmillos.clear();
        // Las velas y los Vastagos (zombi y maniqui) estan en 'spawned': los quita
        // super.cleanup(), brillo incluido. Aqui solo las listas.
        velas.clear();
        vastagos.clear();
        super.cleanup();
    }

    /** Un Vastago se cae al morir su zombi; con el sonido de muerte si lo mato alguien. */
    @Override
    public void onMinionDeath(LivingEntity minion) {
        Vastago v = vastagoDe(minion);
        if (v == null || !minion.equals(v.mob)) return;
        v.caer(minion.getKiller() != null);
    }

    /** Quien muere por un Vastago lee su nombre, no el del Conde. */
    @Override
    public Component killerName(Entity killer) {
        return vastagoDe(killer) != null ? Component.text(VASTAGO_NOMBRE, ACCENT) : null;
    }

    /**
     * Durante la Forma de Murcielago el cuerpo que se ve —y se golpea— es el
     * murcielago gigante. El manager redirige al jefe los golpes que recibe el shell y
     * le quita todo el dano de entorno, asi que basta con devolverlo aqui.
     */
    @Override
    public LivingEntity shell() {
        if (formaBat != null && formaBat.isValid()) return formaBat;
        return super.shell();
    }

    private final class Escucha implements Listener {

        /** El mordisco vanilla de nuestros colmillos: fuera. El dano lo pone hit(). */
        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onColmillo(EntityDamageEvent e) {
            if (colmillos.isEmpty()) return;
            Entity directo = null;
            if (e instanceof EntityDamageByEntityEvent by) directo = by.getDamager();
            if (!(directo instanceof EvokerFangs)) {
                try {
                    directo = e.getDamageSource().getDirectEntity();
                } catch (Throwable ignored) {
                }
            }
            if (directo instanceof EvokerFangs f && colmillos.contains(f.getUniqueId())) e.setCancelled(true);
        }

        /*
         * Los suyos no se pegan entre ellos. El manager ya impide jefe contra esbirro y
         * esbirro contra jefe; esto cubre esbirro contra esbirro (murcielagos, Vastagos
         * y sus maniquies), que no lo cubria nadie.
         */
        @EventHandler(priority = EventPriority.LOWEST)
        public void onEntreSuyos(EntityDamageByEntityEvent e) {
            String id = event.typeId();
            if (!id.equals(Tags.minionId(e.getEntity()))) return;
            Entity origen = e.getDamager();
            if (origen instanceof Projectile p && p.getShooter() instanceof Entity tirador) origen = tirador;
            if (id.equals(Tags.minionId(origen)) || (boss != null && origen.equals(boss))) e.setCancelled(true);
        }

        /*
         * Lo que se le pega al maniqui de un Vastago se le pasa a su zombi, como hace el
         * manager con el cuerpo del jefe. Se veta para que ArenaGuard no lo descancele:
         * si no, el maniqui perderia vida propia y el golpe contaria dos veces.
         * El barrido de espada se descarta: ya le llega al zombi, que esta en el mismo sitio.
         */
        @EventHandler(priority = EventPriority.LOWEST)
        public void onGolpeAlVastago(EntityDamageByEntityEvent e) {
            if (vastagos.isEmpty()) return;
            Vastago v = vastagoDe(e.getEntity());
            if (v == null || !e.getEntity().equals(v.cuerpo)) return;
            e.setCancelled(true);
            ArenaGuard.vetar(e);
            if (!v.vivo() || Tags.isOurs(e.getDamager())) return;
            if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
            try {
                v.mob.damage(e.getDamage(), e.getDamager());
            } catch (Throwable ignored) {
            }
        }

        /*
         * El mordisco del Vastago: el golpe de su zombi a un jugador salpica con el
         * mismo dano a los que esten pegados a el y le roba vida al Conde. En MONITOR
         * para leer el dano ya final del manager (multiplicador del menu incluido); por
         * eso la salpicadura va con dealScaled, para no multiplicarlo otra vez.
         */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onMordiscoVastago(EntityDamageByEntityEvent e) {
            if (mordiendo || vastagos.isEmpty()) return;
            if (!(e.getEntity() instanceof Player victima)) return;
            Vastago v = vastagoDe(e.getDamager());
            if (v == null || !v.vivo() || !e.getDamager().equals(v.mob)) return;
            final double dano = e.getDamage();
            mordiendo = true;
            try {
                for (Player otro : Fx.playersNear(victima.getLocation(), VASTAGO_MORDISCO_RADIO)) {
                    if (otro.equals(victima) || !Fx.isFightable(otro)) continue;
                    dealScaled(() -> otro.damage(dano, v.mob));
                }
            } finally {
                mordiendo = false;
            }
            curar(VASTAGO_ROBO);
            Location pecho = victima.getLocation().add(0, 1.1, 0);
            Compat.spawn(world(), Compat.ENTITY_EFFECT, pecho, 6, 0.25, 0.3, 0.25, 0, rojo());
        }

        /*
         * El Conde y sus Vastagos son zombis: medio minuto bajo el agua y vanilla los
         * cambia por un ahogado NUEVO (otro UUID). El jefe desapareceria del mundo y la
         * pelea se cerraria "sin cuerpo". Ni el ni los suyos se transforman.
         */
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onTransformar(org.bukkit.event.entity.EntityTransformEvent e) {
            Entity quien = e.getEntity();
            if ((boss != null && quien.equals(boss)) || vastagoDe(quien) != null) e.setCancelled(true);
        }

        /** Quien se va en plena Medianoche vuelve con su hora de siempre. */
        @EventHandler
        public void onSalir(PlayerQuitEvent e) {
            if (noche.remove(e.getPlayer().getUniqueId())) {
                try {
                    e.getPlayer().resetPlayerTime();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ============================================================== HABILIDADES ==

    private double power() {
        return damageBonus;
    }

    /**
     * 1. Colmillos de Sombra: una linea de colmillos de evoker hacia CADA jugador a 24
     * bloques o menos; de algunos sale un murcielago. Cada jugador muerde una sola vez
     * por lanzamiento aunque le crucen varias lineas: si no, un grupo junto encajaba
     * una linea por cabeza.
     */
    public void colmillosDeSombra() {
        if (!alive()) return;
        List<Player> objetivos = targets(24);
        if (objetivos.isEmpty()) return;
        final Location origen = Fx.ground(boss.getLocation(), 4);
        soundAt(origen, "block.respawn_anchor.charge", 1.6f, 0.6f);
        soundAt(origen, "entity.illusioner.prepare_mirror", 1.4f, 0.6f);
        announce(Component.text("Colmillos de sombra. Sal de la línea."));

        final Set<UUID> mordidos = new HashSet<>();
        // Tres crias por linea, pero doce como mucho en total: con veinte jugadores serian sesenta.
        final int[] crias = {0};
        for (Player objetivo : objetivos) {
            Vector dir = objetivo.getLocation().toVector().subtract(origen.toVector()).setY(0);
            if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0);
            double largo = Fx.clamp(dir.length() + 2.5, 5, 14);
            final Vector paso = dir.normalize();
            List<Location> puntos = new ArrayList<>();
            for (double d = 1.5; d <= largo; d += 1.25) {
                Location p = Fx.ground(origen.clone().add(paso.clone().multiply(d)), 5);
                p.setYaw((float) Math.toDegrees(Math.atan2(-paso.getX(), paso.getZ())));
                puntos.add(p);
            }

            // Aviso: la linea por donde van a salir, en almas a ras de suelo.
            animate(15, t -> {
                if (!alive()) throw Stop.now();
                if (t % 3 != 0) return;
                for (Location p : puntos) {
                    Compat.spawn(world(), Compat.SCULK_SOUL, p.clone().add(0, 0.15, 0), 1, 0.1, 0, 0.1, 0.005);
                    Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, p.clone().add(0, 0.1, 0), 1,
                            0.15, 0, 0.15, 0, transicion(1.1f));
                }
            }, null);

            final List<Bat> linea = new ArrayList<>();
            later(15, () -> {
                if (!alive()) return;
                soundAt(origen, "entity.wither.shoot", 1.2f, 0.5f);
                for (int i = 0; i < puntos.size(); i++) {
                    final Location p = puntos.get(i);
                    final int retardo = i * 2;
                    // Una cria en los colmillos 2, 4 y 6: tres por linea, no una por colmillo.
                    final boolean conCria = i % 2 == 1 && i <= 5;
                    try {
                        EvokerFangs f = world().spawn(p, EvokerFangs.class, e -> {
                            e.setOwner(boss);
                            e.setAttackDelay(retardo);
                        });
                        colmillos.add(f.getUniqueId());
                        track(f);
                        later(retardo + 40, () -> {
                            colmillos.remove(f.getUniqueId());
                            spawned.remove(f);
                        });
                    } catch (Throwable ignored) {
                    }
                    // El colmillo muerde 8 ticks despues de su retardo: ahi va el dano.
                    later(retardo + 8, () -> {
                        if (!alive()) return;
                        Compat.spawn(world(), Compat.ENTITY_EFFECT, p.clone().add(0, 0.6, 0), 4,
                                0.25, 0.3, 0.25, 0, rojo());
                        Compat.spawn(world(), Compat.SQUID_INK, p.clone().add(0, 0.4, 0), 2, 0.2, 0.2, 0.2, 0.01);
                        for (Player v : Fx.playersNear(p, 2.4)) {
                            if (plano(v.getLocation(), p) > 1.1) continue;
                            if (!mordidos.add(v.getUniqueId())) continue;
                            hit(v, 14 * power());
                        }
                        if (conCria && crias[0] < 12) {
                            Bat b = murcielago(p.clone().add(0, 1.0, 0));
                            if (b != null) {
                                linea.add(b);
                                crias[0]++;
                            }
                        }
                    });
                }
                // Las crias persiguen al de su linea. Van naciendo segun brota cada colmillo.
                bandada(linea, objetivo, null, 0, 100 + puntos.size() * 2, 5 * power(), 0.34);
            });
        }
    }

    /** 2. Enjambre: murcielagos que le orbitan y se lanzan en picado contra todos. */
    public void enjambre() {
        if (!alive()) return;
        int n = 10 + Math.min(4, targets().size());
        List<Bat> bats = new ArrayList<>();
        Location c = boss.getLocation();
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            Bat b = murcielago(c.clone().add(Math.cos(a) * 3, 1.6 + (i % 3) * 0.5, Math.sin(a) * 3));
            if (b != null) bats.add(b);
        }
        // Un coro de aleteos: el segundo, mas grave y un poco despues.
        soundAt(c, "entity.bat.takeoff", 2.0f, 0.6f);
        later(3, () -> soundAt(c, "entity.bat.takeoff", 1.6f, 0.5f));
        announce(Component.text("Enjambre. Mata los murciélagos antes de que bajen."));
        bandada(bats, null, () -> boss.getLocation(), 40, 120, 5 * power(), 0.5);
    }

    /** 3. Velo de la Noche: un pulso que deja a oscuras a todo el que este cerca. */
    public void veloDeLaNoche() {
        if (!alive()) return;
        final Location c = Fx.ground(boss.getLocation(), 4);
        soundAt(c, "entity.elder_guardian.curse", 1.6f, 0.8f);
        soundAt(c, "entity.wither.ambient", 1.4f, 0.5f);
        announce(Component.text("Velo de la noche. Aléjate más de 10 bloques."));

        // Aviso: el borde del pulso marcado y la tinta entrando hacia el.
        animate(15, t -> {
            if (!alive()) throw Stop.now();
            if (t % 3 == 0) {
                Fx.ring(c, 10, 40, p -> Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION,
                        Fx.ground(p, 4).add(0, 0.15, 0), 1, 0, 0, 0, 0, transicion(1.3f)));
            }
            double r = 9.0 - t * 0.55;
            Fx.ring(c.clone().add(0, 1.2, 0), r, 18, t * 0.3, p ->
                    Compat.spawn(world(), Compat.SQUID_INK, p, 1, 0.05, 0.05, 0.05, 0.005));
        }, () -> {
            if (!alive()) return;
            soundAt(c, "entity.warden.sonic_charge", 1.4f, 0.6f);
            for (Player p : Fx.playersNear(c, 10)) {
                Compat.apply(p, "darkness", 120, 0);
                Compat.apply(p, "slowness", 80, 0);
                p.sendActionBar(Component.text("El velo te deja a oscuras.", ACCENT));
            }
            animate(12, t -> {
                double r = (t + 1) * 10.0 / 12.0;
                Fx.ring(c.clone().add(0, 0.4, 0), r, (int) (r * 5) + 8, p -> {
                    Compat.spawn(world(), Compat.LARGE_SMOKE, p, 1, 0.05, 0.05, 0.05, 0.01);
                    if (random.nextInt(3) == 0) Compat.spawn(world(), Compat.SQUID_INK, p, 1, 0, 0, 0, 0.01);
                });
            }, null);
            // Despues, ceniza cayendo sobre la zona un par de segundos.
            animate(60, t -> {
                if (t % 2 != 0) return;
                Compat.spawn(world(), Compat.ASH, c.clone().add(0, 4, 0), 14, 5, 0.5, 5, 0);
                Compat.spawn(world(), Compat.WHITE_ASH, c.clone().add(0, 4, 0), 6, 5, 0.5, 5, 0);
            }, null);
        });
    }

    /**
     * 4. Sello Carmesi: un circulo magico bajo CADA jugador a 20 bloques o menos; al
     * estallar sube de el un rayo rojo. Cada jugador encaja un solo estallido aunque
     * este en dos sellos a la vez (un grupo junto tiene los sellos montados).
     */
    public void selloCarmesi() {
        if (!alive()) return;
        final List<Player> objetivos = targets(20);
        if (objetivos.isEmpty()) return;
        // Del septimo en adelante, sin modelo: solo particulas (y el mismo daño).
        final Escena esc = new Escena();
        final List<Sello> sellos = new ArrayList<>();
        for (int i = 0; i < objetivos.size(); i++) {
            Location c = Fx.ground(objetivos.get(i).getLocation(), 6);
            c.setYaw(0);
            c.setPitch(0);
            sellos.add(new Sello(esc, c, i < SELLO_DIBUJADOS, i < SELLO_GRIMORIO));
        }
        final Set<UUID> golpeados = new HashSet<>();
        // El 'dano' de la habilidad se fija al lanzarla: el estallido (tick 46) cae
        // fuera del 'cast' de un fichero viejo (40) y perderia el multiplicador.
        final double mult = abilityDamage();
        soundAt(loc(), "block.bell.resonate", 1.6f, 0.6f);
        announce(Component.text("Sello carmesí. Sal del círculo rojo."));

        animate(SELLO_FIN, t -> {
            if (cerrado || !alive()) throw Stop.now();
            for (Sello s : sellos) s.tick(t);
            if (t < SELLO_ESTALLA && t % 4 == 0) {
                for (Sello s : sellos) {
                    Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, s.c.clone().add(0, 0.25, 0), 2,
                            0.5, 0.05, 0.5, 0, transicion(0.9f));
                    // Los que no llevan modelo, con su borde en particulas.
                    if (s.sinModelo()) Fx.ring(s.c.clone().add(0, 0.12, 0), 1.5, 14, p ->
                            Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, p, 1, 0, 0, 0, 0, transicion(1.1f)));
                }
            }
            if (t < SELLO_ESTALLA && t % 10 == 0) {
                soundAt(loc(), "block.respawn_anchor.charge", 1.0f, 0.8f + t * 0.015f);
                String quedan = String.format(Locale.ROOT, "%.1f", (SELLO_ESTALLA - t) / 20.0).replace('.', ',');
                for (Player p : objetivos) {
                    if (!Fx.isFightable(p)) continue;
                    p.sendActionBar(Component.text("Sello rojo bajo tus pies. Estalla en " + quedan + " s", ACCENT));
                }
            }
            if (t == SELLO_ESTALLA) {
                for (Sello s : sellos) {
                    Location c = s.c.clone().add(0, 0.5, 0);
                    Compat.spawn(world(), Compat.FLASH, c, 1, 0, 0, 0, 0, C_SANGRE);
                    Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, c, 30, 0.9, 0.5, 0.9, 0, transicion(1.6f));
                    soundAt(c, "entity.generic.explode", 1.3f, 0.6f);
                    for (Player v : Fx.playersNear(s.c, 4)) {
                        if (plano(v.getLocation(), s.c) > SELLO_RADIO) continue;
                        if (Math.abs(v.getLocation().getY() - s.c.getY()) > 2.5) continue;
                        if (!golpeados.add(v.getUniqueId())) continue;
                        hit(v, 16 * power(), mult);
                        push(v, fuera(v.getLocation(), s.c).multiply(0.55).setY(0.3));
                    }
                }
            }
        }, esc::quitar);
    }

    /* El mordisco en abanico: 120 grados delante de el, hasta 3,5 bloques. */
    private static final double MORDISCO_RADIO = 3.5;
    private static final double MORDISCO_MEDIO_ANGULO = Math.toRadians(60);
    /** Lo que se cura como mucho por mordisco (vida de diseno), por grande que sea el grupo. */
    private static final double MORDISCO_TOPE_ROBO = 5;

    /**
     * 5. Mordisco: una embestida corta hacia el mas cercano y, al llegar, un mordisco
     * en abanico de 120 grados y 3,5 bloques que muerde a TODOS los que alcance (12 a
     * cada uno). Se cura con el 30 % de lo que le saca a cada uno, 5 como mucho por
     * mordisco.
     *
     * El aviso es el abanico dibujado en el suelo, donde va a acabar, apuntando a la
     * presa: quien este dentro, que se aparte.
     */
    public void mordisco() {
        if (!alive() || formaBat != null) return;
        final Player v = Fx.nearest(boss.getLocation(), 14);
        if (v == null) return;
        soundAt(loc(), "entity.warden.heartbeat", 1.6f, 0.8f);
        announce(Component.text("Mordisco. Sal del abanico rojo."));

        // Aviso: el hilo rojo hasta su presa y el abanico donde va a morder.
        animate(12, t -> {
            if (!alive() || !Fx.isFightable(v)) throw Stop.now();
            face(v.getEyeLocation());
            if (t % 2 == 0) {
                Fx.beam(boss.getLocation().add(0, 1.2, 0), v.getLocation().add(0, 1.0, 0), 0.7, p ->
                        Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, p, 1, 0, 0, 0, 0, transicion(0.9f)));
                Compat.spawn(world(), Compat.ENTITY_EFFECT, boss.getLocation().add(0, 1, 0), 4,
                        0.3, 0.5, 0.3, 0, rojo());
                Vector hacia = v.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
                if (hacia.lengthSquared() > 0.01) {
                    Vector dir = hacia.clone().normalize();
                    Location llegada = boss.getLocation().add(dir.clone().multiply(Math.max(0, hacia.length() - 1.1)));
                    abanico(Fx.ground(llegada, 4), dir);
                }
            }
        }, () -> {
            if (!alive() || !Fx.isFightable(v)) return;
            final Location desde = boss.getLocation();
            soundAt(desde, "entity.phantom.swoop", 1.8f, 1.2f);
            animate(7, t -> {
                if (!alive()) throw Stop.now();
                Vector hacia = v.getLocation().toVector().subtract(desde.toVector());
                Vector tope = hacia.clone().setY(0);
                double llegar = Math.max(0, tope.length() - 1.1);
                Vector paso = tope.lengthSquared() < 0.01 ? new Vector() : tope.normalize().multiply(llegar);
                Location aqui = desde.clone().add(paso.multiply((t + 1) / 7.0));
                aqui.setY(v.getLocation().getY());
                aqui.setDirection(hacia.lengthSquared() < 0.01 ? desde.getDirection() : hacia);
                // Si hay una pared por medio se queda delante: el teleport la cruzaria y
                // lo dejaria metido en el muro (o al otro lado, fuera del coliseo).
                if (aqui.getBlock().getType().isSolid()
                        || aqui.clone().add(0, 1, 0).getBlock().getType().isSolid()) throw Stop.now();
                boss.teleport(aqui);
                Compat.spawn(world(), Compat.LARGE_SMOKE, aqui.clone().add(0, 1, 0), 2, 0.2, 0.4, 0.2, 0.01);
            }, () -> {
                if (!alive()) return;
                // El abanico mira a donde mira el: hacia la presa si sigue ahi, si no hacia delante.
                Location yo = boss.getLocation();
                Vector frente = Fx.isFightable(v) && v.getWorld().equals(world())
                        ? v.getLocation().toVector().subtract(yo.toVector()).setY(0)
                        : yo.getDirection().setY(0);
                if (frente.lengthSquared() < 0.01) frente = new Vector(1, 0, 0);
                frente.normalize();
                Compat.spawn(world(), Compat.SWEEP_ATTACK, yo.clone().add(frente.clone().multiply(1.6)).add(0, 1.1, 0),
                        3, 0.6, 0.2, 0.6, 0);
                soundAt(yo, "entity.phantom.bite", 1.6f, 0.8f);
                soundAt(yo, "entity.hoglin.attack", 1.2f, 1.3f);
                double robado = 0;
                for (Player p : Fx.playersNear(yo, MORDISCO_RADIO + 0.5)) {
                    if (!enAbanico(yo, frente, p.getLocation())) continue;
                    if (Math.abs(p.getLocation().getY() - yo.getY()) > 2.5) continue;
                    double dano = 12 * power();
                    hit(p, dano);
                    double cura = Math.min(dano * 0.30, MORDISCO_TOPE_ROBO - robado);
                    if (cura > 0) {
                        curar(cura);
                        robado += cura;
                    }
                    Location pecho = p.getLocation().add(0, 1.1, 0);
                    Compat.spawn(world(), Compat.DAMAGE_INDICATOR, pecho, 5, 0.3, 0.3, 0.3, 0.1);
                    Compat.spawn(world(), Compat.ENTITY_EFFECT, pecho, 10, 0.3, 0.4, 0.3, 0, rojo());
                    Fx.beam(pecho, yo.clone().add(0, 1.3, 0), 0.4, pt ->
                            Compat.spawn(world(), Compat.ENTITY_EFFECT, pt, 1, 0, 0, 0, 0, rojo()));
                }
            });
        });
    }

    /** Si un punto cae dentro del abanico del mordisco que sale de 'desde' hacia 'frente'. */
    private static boolean enAbanico(Location desde, Vector frente, Location punto) {
        Vector a = punto.toVector().subtract(desde.toVector()).setY(0);
        double d = a.length();
        if (d > MORDISCO_RADIO) return false;
        // Pegado a el muerde siempre: el angulo de alguien metido en su caja no significa nada.
        if (d < 0.8) return true;
        return a.multiply(1 / d).dot(frente) >= Math.cos(MORDISCO_MEDIO_ANGULO);
    }

    /** El aviso del mordisco: el borde del abanico y sus dos lados, a ras de suelo. */
    private void abanico(Location suelo, Vector frente) {
        double base = Math.atan2(frente.getZ(), frente.getX());
        Location c = suelo.clone().add(0, 0.12, 0);
        for (int i = 0; i <= 12; i++) {
            double a = base - MORDISCO_MEDIO_ANGULO + MORDISCO_MEDIO_ANGULO * 2 * i / 12;
            Location p = c.clone().add(Math.cos(a) * MORDISCO_RADIO, 0, Math.sin(a) * MORDISCO_RADIO);
            Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, p, 1, 0, 0, 0, 0, transicion(1.1f));
        }
        for (double s : new double[]{-1, 1}) {
            double a = base + s * MORDISCO_MEDIO_ANGULO;
            for (double d = 0.6; d < MORDISCO_RADIO; d += 0.6) {
                Location p = c.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d);
                Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, p, 1, 0, 0, 0, 0, transicion(0.9f));
            }
        }
    }

    /** 6. Forma de Murcielago: se convierte en un murcielago gigante y embiste tres veces. */
    public void formaDeMurcielago() {
        if (!alive() || formaBat != null) return;
        final Location l = boss.getLocation();
        soundAt(l, "entity.warden.roar", 1.6f, 1.3f);
        announce(Component.text("Se convierte en murciélago."));

        animate(15, t -> {
            if (!alive()) throw Stop.now();
            Fx.helix(boss.getLocation(), 0.8, 2.4, 10, 1.5, p ->
                    Compat.spawn(world(), Compat.SQUID_INK, p, 1, 0, 0, 0, 0.005));
            Compat.spawn(world(), Compat.ENTITY_EFFECT, boss.getLocation().add(0, 1, 0), 3, 0.4, 0.6, 0.4, 0, rojo());
        }, () -> {
            if (!alive()) return;
            transformar();
            if (formaBat == null) return;
            embestidas();
        });
    }

    private void transformar() {
        Location l = boss.getLocation().add(0, 1.0, 0);
        Bat b = murcielago(l);
        if (b == null) return;
        Compat.setAttribute(b, "scale", 3.2);
        Glow.apply(b, NamedTextColor.RED);
        formaBat = b;

        LivingEntity cuerpo = super.shell();
        if (cuerpo != null && cuerpo.isValid()) {
            quitarBrillo(cuerpo);
            cuerpo.setInvisible(true);
            cuerpo.setCustomNameVisible(false);
            /* Mientras shell() devuelve el murcielago, el manager ya no protege al
             * maniqui: es un esbirro mas, encaja los golpes con su vida propia (y
             * cuentan para el reparto) y puede morir. Si muere, al volver no hay
             * cuerpo y el jefe queda invisible. Intocable hasta que vuelva. */
            cuerpo.setInvulnerable(true);
        }
        // El zombi de debajo deja de andar y acompaña al murcielago: su caja tambien
        // va con el y cualquier golpe sigue llegando al jefe.
        boss.setAI(false);
        boss.setGravity(false);

        Compat.spawn(world(), Compat.FLASH, l, 1, 0, 0, 0, 0, C_SANGRE);
        Compat.spawn(world(), Compat.LARGE_SMOKE, l, 40, 0.8, 0.8, 0.8, 0.05);
        soundAt(l, "entity.bat.takeoff", 2.0f, 0.5f);
        soundAt(l, "entity.wither.shoot", 1.2f, 0.6f);
    }

    /** Radio del cuerpo del murcielago gigante al embestir: golpea a todo el que cruce. */
    private static final double EMBESTIDA_RADIO = 2.0;

    /**
     * El grupo mas denso: el jugador con mas compañeros a 4 bloques o menos, y esos
     * compañeros. Es a donde apunta cada embestida, para llevarse a cuantos pueda.
     */
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

    /** El centro (a la altura de los pies) de los que sigan en pie del grupo; null si no queda nadie. */
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

    /**
     * Tres pasadas de 50 ticks: sube, elige el grupo mas denso, baja en picado sobre el
     * y vuelve a subir. Golpea a TODOS los que cruce (radio 2), una vez por pasada. El
     * aviso es el circulo rojo que aparece bajo el grupo antes de cada picado.
     */
    private void embestidas() {
        final List<Player> grupo = new ArrayList<>();
        final Location[] blanco = new Location[1];
        final Location[] apice = new Location[1];
        final Location[] picado = new Location[1];
        final Location[] subida = new Location[1];
        final Vector[] rumbo = new Vector[1];
        final Set<UUID> golpeados = new HashSet<>();

        // 149 y no 150: Anim cuenta de 0 a la duracion INCLUIDA, y el tick 150 abria
        // una cuarta pasada (otra victima, otro aleteo) justo antes de volver a humano.
        animate(149, tick -> {
            if (!alive() || formaBat == null || !formaBat.isValid()) throw Stop.now();
            int s = tick % 50;
            if (s == 0) {
                golpeados.clear();
                grupo.clear();
                grupo.addAll(grupoDenso());
                blanco[0] = centroDe(grupo);
                if (blanco[0] == null) throw Stop.now();
                Vector lado = blanco[0].toVector().subtract(formaBat.getLocation().toVector()).setY(0);
                if (lado.lengthSquared() < 0.01) lado = new Vector(1, 0, 0);
                // Recortado contra paredes y techos: el coliseo es cerrado, y un apice
                // dentro de la grada dejaba al jefe, al volver a humano, encima de ella.
                apice[0] = despejado(blanco[0].clone().add(0, 1.6, 0), blanco[0].clone()
                        .add(lado.normalize().multiply(-7)).add(0, 7, 0), 3.0);
                soundAt(formaBat.getLocation(), "entity.bat.takeoff", 2.0f, 0.5f);
            }
            if (blanco[0] == null) return;
            // El blanco sigue al grupo mientras se prepara: si se mueven, el circulo va con ellos.
            if (s <= 20) {
                Location c = centroDe(grupo);
                if (c != null) blanco[0] = c;
            }
            if (s < 20) {
                fly(formaBat, apice[0], 0.8);
                if (s >= 5) {
                    Fx.ring(Fx.ground(blanco[0], 6).add(0, 0.12, 0), 2.4, 20, p ->
                            Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, p, 1, 0, 0, 0, 0, transicion(1.3f)));
                }
            } else if (s == 20) {
                picado[0] = blanco[0].clone().add(0, 1.0, 0);
                Vector r = picado[0].toVector().subtract(formaBat.getLocation().toVector());
                rumbo[0] = r.lengthSquared() < 0.01 ? new Vector(0, -1, 0) : r.normalize();
                soundAt(formaBat.getLocation(), "entity.phantom.swoop", 2.0f, 0.7f);
            } else if (s < 36) {
                // Pasa de largo tres bloques: se ve el picado entero, no un frenazo.
                fly(formaBat, picado[0].clone().add(rumbo[0].clone().multiply(3)), 1.1);
                for (Player p : Fx.playersNear(formaBat.getLocation(), EMBESTIDA_RADIO)) {
                    if (!golpeados.add(p.getUniqueId())) continue;
                    hit(p, 13 * power());
                    push(p, rumbo[0].clone().setY(0).multiply(1.0).setY(0.45));
                    Compat.spawn(world(), Compat.DAMAGE_INDICATOR, p.getLocation().add(0, 1, 0), 4, 0.3, 0.3, 0.3, 0.1);
                    soundAt(p.getLocation(), "entity.phantom.bite", 1.6f, 0.7f);
                }
            } else {
                if (s == 36) {
                    subida[0] = despejado(formaBat.getLocation(),
                            picado[0].clone().add(rumbo[0].clone().setY(0).multiply(6)).add(0, 5, 0), 3.0);
                }
                fly(formaBat, subida[0], 0.7);
            }
            boss.teleport(formaBat.getLocation());
        }, () -> volverHumano(true));
    }

    /** Deshace la forma de murcielago. Sin animacion si viene de la muerte o del cierre. */
    private void volverHumano(boolean conEfecto) {
        Bat b = formaBat;
        if (b == null) return;
        formaBat = null;
        Location donde = b.isValid() ? b.getLocation() : loc();
        Glow.clear(b);
        spawned.remove(b);
        Fx.safeRemove(b);
        if (boss == null || !boss.isValid()) return;
        Location suelo = Fx.ground(donde, 24);
        boss.teleport(suelo);
        boss.setAI(true);
        boss.setGravity(true);
        LivingEntity cuerpo = super.shell();
        if (cuerpo != null && cuerpo.isValid()) cuerpo.setInvulnerable(false);
        if (conEfecto && cuerpo != null && cuerpo.isValid()) {
            cuerpo.setInvisible(false);
            cuerpo.setCustomNameVisible(true);
            glowBody(NamedTextColor.DARK_RED);
        }
        if (!conEfecto) return;
        Location l = suelo.clone().add(0, 1, 0);
        Compat.spawn(world(), Compat.LARGE_SMOKE, l, 50, 0.7, 0.9, 0.7, 0.06);
        Compat.spawn(world(), Compat.SQUID_INK, l, 12, 0.5, 0.6, 0.5, 0.03);
        soundAt(l, "entity.illusioner.mirror_move", 1.6f, 0.6f);
        soundAt(l, "entity.bat.death", 1.4f, 0.6f);
    }

    /** Lo que pega cada murcielago de la Lluvia: quien se coma los dos o tres suyos encaja de 30 a 45. */
    private static final double LLUVIA_DANO = 15;

    /**
     * 7. Lluvia de Murcielagos: murcielagos que caen en picado sobre circulos marcados.
     * Los puntos se reparten por turnos entre TODOS los jugadores (uno a cada uno,
     * luego otro a cada uno, hasta tres): antes los tres primeros se llevaban casi todo.
     * Veinte como minimo; con mucha gente, hasta treinta y dos.
     */
    public void lluviaDeMurcielagos() {
        if (!alive()) return;
        List<Player> jugadores = targets();
        final int total = (int) Fx.clamp(jugadores.size() * 3, 20, 32);
        List<Location> puntos = new ArrayList<>();
        for (int vuelta = 0; vuelta < 3 && puntos.size() < total; vuelta++) {
            for (Player p : jugadores) {
                if (puntos.size() >= total) break;
                // El primero de cada uno, justo debajo; los otros, alrededor.
                double abre = vuelta == 0 ? 0.6 : 2.0;
                puntos.add(Fx.ground(p.getLocation().clone().add(
                        random.nextDouble() * abre * 2 - abre, 0, random.nextDouble() * abre * 2 - abre), 6));
            }
        }
        Location c = boss.getLocation();
        while (puntos.size() < 20) {
            double a = random.nextDouble() * Math.PI * 2;
            double d = 2 + random.nextDouble() * 10;
            puntos.add(Fx.ground(c.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d), 6));
        }
        soundAt(c, "entity.bat.takeoff", 2.0f, 0.5f);
        soundAt(c, "entity.vex.charge", 1.4f, 0.6f);
        announce(Component.text("Lluvia de murciélagos. Sal de los círculos."));

        final int n = puntos.size();
        final Bat[] bats = new Bat[n];
        final boolean[] hecho = new boolean[n];
        animate(56, t -> {
            if (!alive()) throw Stop.now();
            for (int i = 0; i < n; i++) {
                if (hecho[i]) continue;
                int sale = i;          // uno por tick: veinte en un segundo
                if (t < sale) continue;
                Location g = puntos.get(i);
                if (t % 2 == 0) {
                    Fx.ring(g.clone().add(0, 0.12, 0), 0.9, 12, p ->
                            Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, p, 1, 0, 0, 0, 0, transicion(1.0f)));
                }
                if (t == sale + 10) bats[i] = murcielago(g.clone().add(0, 12, 0));
                Bat b = bats[i];
                if (b == null) continue;
                if (!b.isValid()) {
                    hecho[i] = true;
                    continue;
                }
                Location ahora = b.getLocation();
                Location sig = ahora.clone().add(0, -1.0, 0);
                sig.setPitch(90);
                b.teleport(sig);
                if (t % 2 == 0) Compat.spawn(world(), Compat.SMOKE, ahora, 1, 0.05, 0.05, 0.05, 0);
                if (sig.getY() > g.getY() + 0.6) continue;
                hecho[i] = true;
                Location imp = g.clone().add(0, 0.4, 0);
                Compat.spawn(world(), Compat.LARGE_SMOKE, imp, 8, 0.4, 0.2, 0.4, 0.03);
                Compat.spawn(world(), Compat.SQUID_INK, imp, 4, 0.3, 0.2, 0.3, 0.02);
                Compat.spawn(world(), Compat.BLOCK, imp, 8, 0.3, 0.1, 0.3, 0, Material.RED_CONCRETE.createBlockData());
                soundAt(imp, "entity.bat.death", 0.9f, 0.7f + random.nextFloat() * 0.3f);
                for (Player v : Fx.playersNear(g, 3)) {
                    if (plano(v.getLocation(), g) > 1.4) continue;
                    double dy = v.getLocation().getY() - g.getY();
                    if (dy < -1 || dy > 2.5) continue;
                    hit(v, LLUVIA_DANO * power());
                }
                spawned.remove(b);
                Fx.safeRemove(b);
            }
        }, () -> {
            for (Bat b : bats) {
                if (b == null || !b.isValid()) continue;
                Compat.spawn(world(), Compat.LARGE_SMOKE, b.getLocation(), 4, 0.2, 0.2, 0.2, 0.02);
                spawned.remove(b);
                Fx.safeRemove(b);
            }
        });
    }

    /**
     * 8. Pentaculo: dos circulos enormes que crecen pieza a pieza y cargan tres segundos;
     * de las cinco puntas (los pilares con vela) sube un rayo rojo de 80 bloques, uno cada
     * dos ticks en sentido horario, y los circulos salen en onda hacia fuera. Cada rayo
     * pega 18 a quien este a 2,5 o menos de su punta (una vez por rayo) y la onda 8 a
     * todo el que este dentro del circulo.
     */
    public void pentaculo() {
        if (!alive()) return;
        final Location c = Fx.ground(boss.getLocation(), 4);
        c.setYaw(0);
        c.setPitch(0);
        final Escena esc = new Escena();
        final Pentaculo k = new Pentaculo(esc, c);
        soundAt(c, "block.bell.use", 1.8f, 0.5f);
        soundAt(c, "entity.illusioner.cast_spell", 1.6f, 0.7f);
        announce(Component.text("Pentáculo. No te quedes en las puntas de la estrella."));

        final List<Set<UUID>> tocados = new ArrayList<>();
        for (int i = 0; i < 5; i++) tocados.add(new HashSet<>());
        final Set<UUID> onda = new HashSet<>();
        // Los ultimos rayos (hasta el tick 79) rozan el final del 'cast' de 80.
        final double mult = abilityDamage();
        animate(PENTA_FIN, t -> {
            if (cerrado || !alive()) throw Stop.now();
            k.tick(t);
            if (t >= 7 && t < 92 && t % 6 == 0) {
                for (int i = 0; i < 5; i++) {
                    Compat.spawn(world(), Compat.SMALL_FLAME, k.pie[i].clone().add(0, 1.26, 0), 1, 0, 0, 0, 0);
                }
            }
            // La zona de cada rayo, marcada en el suelo mientras carga.
            if (t >= 26 && t < PENTA_ESTALLA && t % 4 == 0) {
                for (Location v : k.pie) {
                    Fx.ring(v.clone().add(0, 0.12, 0), PENTA_RADIO_RAYO, 16, p ->
                            Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, p, 1, 0, 0, 0, 0, transicion(1.1f)));
                }
            }
            if (t < PENTA_ESTALLA && t % 20 == 0) soundAt(c, "block.respawn_anchor.charge", 1.4f, 0.6f + t * 0.01f);
            for (int i = 0; i < 5; i++) {
                int t0 = PENTA_ESTALLA + 2 * i;
                if (t < t0 || t >= t0 + 6) continue;
                Location v = k.pie[i];
                if (t == t0) {
                    Compat.spawn(world(), Compat.FLASH, v.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0, C_SANGRE);
                    soundAt(v, "entity.wither.shoot", 1.2f, 0.5f + i * 0.08f);
                }
                for (Player p : Fx.playersNear(v, 41)) {
                    if (plano(p.getLocation(), v) > PENTA_RADIO_RAYO) continue;
                    double dy = p.getLocation().getY() - v.getY();
                    if (dy < -1 || dy > 40) continue;
                    if (!tocados.get(i).add(p.getUniqueId())) continue;
                    hit(p, 18 * power(), mult);
                    push(p, fuera(p.getLocation(), v).multiply(0.6).setY(0.35));
                }
            }
            if (t == PENTA_ESTALLA + 4) {
                soundAt(c, "entity.generic.explode", 1.4f, 0.6f);
                Fx.ring(c.clone().add(0, 0.3, 0), 6.4, 40, p ->
                        Compat.spawn(world(), Compat.LARGE_SMOKE, p, 1, 0.1, 0.05, 0.1, 0.02));
                for (Player p : Fx.playersNear(c, 9)) {
                    if (plano(p.getLocation(), c) > 7) continue;
                    if (Math.abs(p.getLocation().getY() - c.getY()) > 3) continue;
                    if (!onda.add(p.getUniqueId())) continue;
                    hit(p, 8 * power(), mult);
                    push(p, fuera(p.getLocation(), c).multiply(0.7).setY(0.3));
                }
            }
        }, esc::quitar);
    }

    /** 9. Niebla Roja: una niebla que ciega al entrar y en la que el es mas rapido. */
    public void nieblaRoja() {
        if (!alive()) return;
        final Location c = Fx.ground(boss.getLocation(), 4);
        final double r = 9;
        soundAt(c, "entity.illusioner.prepare_blindness", 1.6f, 0.7f);
        soundAt(c, "ambient.cave", 1.5f, 0.6f);
        announce(Component.text("Niebla roja. Dentro te ciega y él va más rápido."));

        final Set<UUID> dentro = new HashSet<>();
        animate(135, t -> {
            if (!alive()) throw Stop.now();
            if (t < 15) {
                if (t % 3 == 0) Fx.ring(c.clone().add(0, 0.5, 0), r, 36, p ->
                        Compat.spawn(world(), Compat.LARGE_SMOKE, p, 1, 0.1, 0.1, 0.1, 0.005));
                return;
            }
            Compat.spawn(world(), Compat.CRIMSON_SPORE, c.clone().add(0, 1.4, 0), 40, r * 0.55, 1.1, r * 0.55, 0);
            Compat.spawn(world(), Compat.ASH, c.clone().add(0, 1.6, 0), 14, r * 0.55, 1.2, r * 0.55, 0);
            if (t % 5 == 0) {
                Fx.ring(c.clone().add(0, 0.15, 0), r, 40, p ->
                        Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, p, 1, 0, 0, 0, 0, transicion(1.2f)));
                for (Player p : targets()) {
                    boolean in = plano(p.getLocation(), c) <= r;
                    if (in && dentro.add(p.getUniqueId())) {
                        Compat.apply(p, "blindness", 40, 0);
                        p.sendActionBar(Component.text("La niebla roja te ciega.", ACCENT));
                    } else if (!in) {
                        dentro.remove(p.getUniqueId());
                    }
                }
            }
            if (t % 20 == 0 && plano(boss.getLocation(), c) <= r) Compat.apply(boss, "speed", 25, 1);
        }, null);
    }

    /** 10. Medianoche: se hace de noche para todos y llegan tres enjambres. */
    public void medianoche() {
        if (!alive()) return;
        titleNear(Component.text("MEDIANOCHE", ACCENT),
                Component.text("Llegan sus murciélagos", NamedTextColor.GRAY));
        Location l = loc();
        soundAt(l, "entity.wither.ambient", 1.4f, 0.6f);
        for (int i = 0; i < 3; i++) later(i * 10, () -> soundAt(loc(), "block.bell.use", 1.8f, 0.5f));

        final int turno = ++nocheTurno;
        for (Player p : targets()) {
            try {
                p.setPlayerTime(18000, false);
                noche.add(p.getUniqueId());
            } catch (Throwable ignored) {
            }
            Compat.apply(p, "darkness", 80, 0);
        }
        // La hora vuelve a los 12 s; y si antes acaba la pelea, la devuelve cleanup().
        later(240, () -> {
            if (turno == nocheTurno) devolverHora();
        });

        for (int s = 0; s < 3; s++) {
            later(20 + s * 50, () -> {
                if (!alive()) return;
                Location c = boss.getLocation();
                List<Bat> bats = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    double a = Math.PI * 2 * i / 5;
                    Bat b = murcielago(c.clone().add(Math.cos(a) * 3, 2.0, Math.sin(a) * 3));
                    if (b != null) bats.add(b);
                }
                soundAt(c, "entity.bat.takeoff", 1.8f, 0.6f);
                bandada(bats, null, () -> boss.getLocation(), 20, 110, 5 * power(), 0.45);
            });
        }
    }

    /**
     * 11. Ataudes: cuatro ataudes salen del suelo, se abren y sueltan murcielagos. En
     * cada uno, ademas, se levanta un Vastago (ver levantarVastago), cuatro como mucho.
     */
    public void ataudes() {
        if (!alive()) return;
        final Location c = boss.getLocation();
        announce(Component.text("Ataúdes. Mata lo que salga de ellos."));
        soundAt(c, "entity.warden.heartbeat", 1.8f, 0.6f);
        soundAt(c, "block.rooted_dirt.break", 1.6f, 0.6f);

        final List<Ataud> ataudes = new ArrayList<>();
        double base = random.nextDouble() * Math.PI;
        for (int k = 0; k < 4; k++) {
            double a = base + k * Math.PI / 2;
            Location pos = Fx.ground(c.clone().add(Math.cos(a) * 5, 0, Math.sin(a) * 5), 6);
            pos.setYaw(0);
            pos.setPitch(0);
            Ataud at = new Ataud(pos, (float) a);
            if (at.cuerpo != null) ataudes.add(at);
        }

        animate(150, t -> {
            if (t == 2) for (Ataud at : ataudes) at.colocar(0f, 0f, 20);
            if (t < 22 && t % 4 == 0) {
                for (Ataud at : ataudes) {
                    Compat.spawn(world(), Compat.BLOCK, at.pos.clone().add(0, 0.2, 0), 6, 0.5, 0.1, 0.9, 0,
                            Material.SOUL_SOIL.createBlockData());
                }
                soundAt(c, "block.gravel.break", 1.2f, 0.6f);
            }
            if (t == 40) {
                for (Ataud at : ataudes) {
                    at.colocar(0f, 1.9f, 10);
                    Compat.spawn(world(), Compat.SQUID_INK, at.pos.clone().add(0, 0.8, 0), 6, 0.3, 0.2, 0.6, 0.02);
                }
                soundAt(c, "block.chest.open", 1.6f, 0.5f);
                soundAt(c, "block.wooden_trapdoor.open", 1.4f, 0.6f);
                // Dentro de cada uno, un Vastago tumbado; como mucho cuatro en pie a la vez.
                if (alive()) for (Ataud at : ataudes) levantarVastago(at.pos, at.q);
            }
            if (t == 46 && alive()) {
                List<Bat> bats = new ArrayList<>();
                for (Ataud at : ataudes) {
                    for (int i = 0; i < 4; i++) {
                        Bat b = murcielago(at.pos.clone().add(random.nextDouble() - 0.5, 0.8 + i * 0.2,
                                random.nextDouble() - 0.5));
                        if (b != null) bats.add(b);
                    }
                }
                soundAt(c, "entity.bat.takeoff", 2.0f, 0.6f);
                bandada(bats, null, null, 0, 100, 5 * power(), 0.4);
            }
            // La vela sigue encendida hasta que se abre la tapa: una chispa en la mecha.
            if (t >= 22 && t < 40 && t % 4 == 0) for (Ataud at : ataudes) at.llama();
            if (t == 120) for (Ataud at : ataudes) at.colocar(Ataud.HUNDIDO, 1.9f, 20);
        }, () -> {
            for (Ataud at : ataudes) at.quitar();
        });
    }

    /**
     * Un ataud, de abajo arriba: una base de losa de piedra negra, la caja de madera
     * oscura, el forro de ladrillo del nether rojo (se ve al abrir), la tapa negra con
     * bisagra en un lado largo, dos asas de cadena en los costados y una vela roja
     * encendida encima de la tapa que se apaga y cae al suelo al abrirse. Siete displays
     * que suben del suelo y se abren por interpolacion.
     *
     * Medidas en el espacio del ataud (q lo gira): X el ancho (0,9), Z el largo (2),
     * y la 'y' de colocar() es la cara de abajo de la base. Con y = 0 apoya en el suelo.
     */
    private final class Ataud {
        /** Tan hundido que ni la tapa asoma: asi nace y asi se va. */
        static final float HUNDIDO = -1.0f;
        private static final float BASE = 0.15f;

        final Location pos;
        final Quaternionf q;
        /** Escala del ataud entero: 1 los de la habilidad, 1,4 el de la entrada. */
        final float k;
        BlockDisplay base;
        BlockDisplay cuerpo;
        BlockDisplay forro;
        BlockDisplay tapa;
        BlockDisplay asaIzq;
        BlockDisplay asaDer;
        BlockDisplay vela;
        boolean velaEnPie = true;
        float y = HUNDIDO;

        Ataud(Location pos, float giro) {
            this(pos, giro, 1f);
        }

        Ataud(Location pos, float giro, float k) {
            this.pos = pos;
            this.q = new Quaternionf().rotateY(giro);
            this.k = k;
            this.base = parte(Material.BLACKSTONE_SLAB.createBlockData(), baseT(HUNDIDO), 12);
            this.cuerpo = parte(Material.DARK_OAK_PLANKS.createBlockData(), cuerpoT(HUNDIDO), 12);
            this.forro = parte(Material.RED_NETHER_BRICKS.createBlockData(), forroT(HUNDIDO), 12);
            this.tapa = parte(Material.BLACK_CONCRETE.createBlockData(), tapaT(HUNDIDO, 0f), 12);
            BlockData cadena = Material.IRON_CHAIN.createBlockData("[axis=z]");
            this.asaIzq = parte(cadena, asaT(HUNDIDO, -1), 12);
            this.asaDer = parte(cadena.clone(), asaT(HUNDIDO, 1), 12);
            BlockData encendida = Material.RED_CANDLE.createBlockData();
            if (encendida instanceof Lightable l) l.setLit(true);
            this.vela = parte(encendida, velaT(HUNDIDO), 15);
        }

        private Transformation t(Vector3f local, Quaternionf giro, Vector3f escala) {
            return new Transformation(new Quaternionf(q).transform(new Vector3f(local).mul(k)), giro,
                    new Vector3f(escala).mul(k), new Quaternionf());
        }

        /* La losa ocupa la mitad de abajo de su bloque: a escala 0,3 de alto mide 0,15. */
        private Transformation baseT(float y) {
            return t(new Vector3f(-0.55f, y, -1.1f), new Quaternionf(q), new Vector3f(1.1f, 0.3f, 2.2f));
        }

        private Transformation cuerpoT(float y) {
            return t(new Vector3f(-0.45f, y + BASE, -1.0f), new Quaternionf(q), new Vector3f(0.9f, 0.6f, 2.0f));
        }

        private Transformation forroT(float y) {
            return t(new Vector3f(-0.38f, y + BASE + 0.6f, -0.93f), new Quaternionf(q),
                    new Vector3f(0.76f, 0.01f, 1.86f));
        }

        /* La bisagra es la arista local x=0 de la tapa: girar en Z la levanta por el
         * otro lado, como una tapa de verdad. */
        private Transformation tapaT(float y, float angulo) {
            return t(new Vector3f(-0.475f, y + BASE + 0.6f, -1.025f), new Quaternionf(q).rotateZ(angulo),
                    new Vector3f(0.95f, 0.1f, 2.05f));
        }

        /* La cadena va a lo largo de su eje Z y centrada en su bloque: a escala 0,5
         * su centro esta a 0,25 de la esquina. Una por costado, pegada a la madera. */
        private Transformation asaT(float y, int lado) {
            return t(new Vector3f(lado * 0.48f - 0.25f, y + BASE + 0.3f - 0.25f, -0.3f), new Quaternionf(q),
                    new Vector3f(0.5f, 0.5f, 0.6f));
        }

        /* La vela, de pie en la tapa hacia los pies del ataud. Centrada en su bloque:
         * a escala 0,8 su centro esta a 0,4 de la esquina. */
        private Transformation velaT(float y) {
            return t(new Vector3f(0.1f - 0.4f, y + BASE + 0.7f, 0.5f - 0.4f), new Quaternionf(q),
                    new Vector3f(0.8f));
        }

        /* Caida y tumbada en el suelo junto al lado que se abre (giro de -90 en Z: la
         * vela queda a lo largo de X, fuera de la caja). */
        private Transformation velaCaidaT(float y) {
            return t(new Vector3f(0.62f, y + 0.45f, 0.5f - 0.4f), new Quaternionf(q).rotateZ((float) (-Math.PI / 2)),
                    new Vector3f(0.8f));
        }

        /** La chispa de la mecha, mientras la vela sigue de pie. */
        void llama() {
            if (!velaEnPie || vela == null || !vela.isValid()) return;
            Vector3f mecha = new Quaternionf(q).transform(new Vector3f(0.1f, y + BASE + 0.7f + 0.36f, 0.5f).mul(k));
            Compat.spawn(world(), Compat.SMALL_FLAME, pos.clone().add(mecha.x, mecha.y, mecha.z), 1, 0, 0, 0, 0);
        }

        private BlockDisplay parte(BlockData datos, Transformation t0, int luz) {
            try {
                BlockDisplay d = world().spawn(pos, BlockDisplay.class, e -> {
                    e.setBlock(datos);
                    e.setBrightness(new Display.Brightness(luz, luz));
                    e.setViewRange(3.0f);
                    e.setShadowRadius(0f);
                    e.setPersistent(false);
                    e.setTeleportDuration(0);
                    e.setTransformation(t0);
                });
                Tags.markTemporary(d);
                track(d);
                return d;
            } catch (Throwable t) {
                return null;
            }
        }

        /**
         * Mueve el ataud entero a la altura 'y' con la tapa abierta 'angulo' radianes.
         * En cuanto la tapa se abre, la vela se apaga y cae (en ocho ticks, mas rapida
         * que la tapa, para que se lea que la tapa la tira).
         */
        void colocar(float y, float angulo, int ticks) {
            this.y = y;
            Conde.mover(base, baseT(y), ticks);
            Conde.mover(cuerpo, cuerpoT(y), ticks);
            Conde.mover(forro, forroT(y), ticks);
            Conde.mover(tapa, tapaT(y, angulo), ticks);
            Conde.mover(asaIzq, asaT(y, -1), ticks);
            Conde.mover(asaDer, asaT(y, 1), ticks);
            if (angulo > 0.01f && velaEnPie) {
                velaEnPie = false;
                if (vela != null && vela.isValid()) {
                    try {
                        BlockData apagada = Material.RED_CANDLE.createBlockData();
                        if (apagada instanceof Lightable l) l.setLit(false);
                        vela.setBlock(apagada);
                    } catch (Throwable ignored) {
                    }
                    Compat.spawn(world(), Compat.SMOKE, vela.getLocation().add(0, y + 1.1, 0), 3,
                            0.05, 0.05, 0.05, 0.01);
                }
                Conde.mover(vela, velaCaidaT(y), 8);
                return;
            }
            Conde.mover(vela, velaEnPie ? velaT(y) : velaCaidaT(y), ticks);
        }

        void quitar() {
            for (BlockDisplay d : new BlockDisplay[]{base, cuerpo, forro, tapa, asaIzq, asaDer, vela}) {
                if (d == null) continue;
                spawned.remove(d);
                Fx.safeRemove(d);
            }
        }

        /**
         * Se desmonta: cada parte sale hacia fuera y hacia arriba girando y se encoge a
         * nada en diez ticks, con un tick de desfase entre partes. No las borra: eso
         * lo hace quitar() cuando ya no se ven.
         */
        void desmontar() {
            BlockDisplay[] partes = {base, cuerpo, forro, tapa, asaIzq, asaDer, vela};
            for (int i = 0; i < partes.length; i++) {
                final BlockDisplay d = partes[i];
                if (d == null || !d.isValid()) continue;
                final int n = i;
                later(i % 4, () -> {
                    if (!d.isValid()) return;
                    try {
                        Transformation tr = d.getTransformation();
                        Quaternionf rot = new Quaternionf(tr.getLeftRotation());
                        Vector3f centro = new Vector3f(tr.getTranslation())
                                .add(new Quaternionf(rot).transform(new Vector3f(tr.getScale()).mul(0.5f)));
                        float dx = centro.x;
                        float dz = centro.z;
                        float largo = (float) Math.sqrt(dx * dx + dz * dz);
                        if (largo < 0.05f) {
                            double a = n * 2.4;
                            dx = (float) Math.cos(a);
                            dz = (float) Math.sin(a);
                            largo = 1f;
                        }
                        Vector3f fin = new Vector3f(centro.x + dx / largo * 1.8f * k, centro.y + 1.2f * k,
                                centro.z + dz / largo * 1.8f * k);
                        Quaternionf giro = new Quaternionf(rot).rotateX(1.4f).rotateY(1.0f);
                        Conde.mover(d, new Transformation(fin, giro, new Vector3f(0.001f), new Quaternionf()), 10);
                    } catch (Throwable ignored) {
                    }
                });
            }
        }
    }

    /** 12. Festin de Sangre: les drena a todos los cercanos y se cura por cada uno. */
    public void festinDeSangre() {
        if (!alive()) return;
        final List<Player> victimas = new ArrayList<>(targets(12));
        if (victimas.isEmpty()) return;
        Compat.apply(boss, "slowness", 60, 3);
        final List<Circulo> sellos = new ArrayList<>();
        for (int i = 0; i < victimas.size(); i++) {
            Player p = victimas.get(i);
            // Hasta ocho con circulo de verdad; el resto con su anillo de particulas.
            if (i < 8) sellos.add(circulo(Fx.ground(p.getLocation(), 6), 1.0, 62, false, true));
            p.sendActionBar(Component.text("Festín de sangre. Aléjate más de 12 bloques.", ACCENT));
        }
        soundAt(loc(), "entity.illusioner.prepare_blindness", 1.6f, 0.6f);
        announce(Component.text("Festín de sangre. Aléjate más de 12 bloques."));

        animate(60, t -> {
            if (!alive()) throw Stop.now();
            if (t % 10 == 0) soundAt(loc(), "entity.warden.heartbeat", 1.6f, 0.8f);
            if (t % 3 != 0) return;
            Location pecho = boss.getLocation().add(0, 1.3, 0);
            for (int i = 0; i < victimas.size(); i++) {
                Player p = victimas.get(i);
                if (!Fx.isFightable(p)) continue;
                Fx.beam(p.getLocation().add(0, 1.0, 0), pecho, 0.6, pt ->
                        Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, pt, 1, 0, 0, 0, 0, transicion(0.8f)));
                if (i >= 8) {
                    Fx.ring(p.getLocation().add(0, 0.1, 0), 1.0, 10, pt ->
                            Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, pt, 1, 0, 0, 0, 0, transicion(1.0f)));
                }
            }
        }, () -> {
            for (Circulo s : sellos) s.quitar();
            if (!alive()) return;
            int alcanzados = 0;
            for (Player p : victimas) {
                if (!Fx.isFightable(p) || !p.getWorld().equals(world())) continue;
                if (p.getLocation().distance(boss.getLocation()) > 12) continue;
                hit(p, 7 * power());
                alcanzados++;
                Location pecho = p.getLocation().add(0, 1.1, 0);
                Compat.spawn(world(), Compat.DAMAGE_INDICATOR, pecho, 4, 0.3, 0.3, 0.3, 0.1);
                Compat.spawn(world(), Compat.ENTITY_EFFECT, pecho, 8, 0.3, 0.4, 0.3, 0, rojo());
            }
            if (alcanzados > 0) {
                double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
                boss.setHealth(Math.min(topeDeFase(), boss.getHealth() + max * 0.03 * alcanzados));
                Compat.spawn(world(), Compat.ENTITY_EFFECT, boss.getLocation().add(0, 1, 0), 30,
                        0.5, 0.8, 0.5, 0, rojo());
            }
            soundAt(loc(), "entity.witch.drink", 1.6f, 0.6f);
            soundAt(loc(), "entity.wither.shoot", 1.0f, 0.5f);
        });
    }

    /**
     * 13. Eclipse Carmesi: dos anillos bajo todo el campo, una luna roja a cinco
     * bloques que un disco negro tapa en tres segundos, y los rayos naciendo. Al
     * taparse del todo, destello: 24 a todo el que siga dentro del radio 9 y 10 mas a
     * quien este a menos de 3 del rayo central.
     */
    public void eclipseCarmesi() {
        if (!alive()) return;
        final Location c = Fx.ground(boss.getLocation(), 4);
        c.setYaw(0);
        c.setPitch(0);
        final double r = ECLIPSE_RADIO;
        // La luna mira al centro de la arena; si el Conde esta en el, al grupo.
        Vector hacia = arena.toVector().subtract(c.toVector()).setY(0);
        if (hacia.lengthSquared() < 1) {
            Location g = centroDe(targets());
            hacia = g == null ? new Vector(1, 0, 0) : g.toVector().subtract(c.toVector()).setY(0);
        }
        if (hacia.lengthSquared() < 0.01) hacia = new Vector(1, 0, 0);
        final Escena esc = new Escena();
        final Eclipse k = new Eclipse(esc, c, Math.atan2(hacia.getX(), hacia.getZ()));
        Compat.apply(boss, "slowness", ECLIPSE_ESTALLA + 10, 4);
        // El destello (tick 60) cae fuera del 'cast' de un fichero viejo (60).
        final double mult = abilityDamage();
        titleNear(Component.text("ECLIPSE CARMESÍ", ACCENT),
                Component.text("Sal del círculo rojo", NamedTextColor.GRAY));
        soundAt(c, "entity.wither.ambient", 1.6f, 0.5f);
        soundAt(c, "block.trial_spawner.ominous_activate", 1.4f, 0.6f);
        soundAt(c, "block.beacon.activate", 1.4f, 0.6f);

        animate(ECLIPSE_FIN, t -> {
            if (cerrado || !alive()) throw Stop.now();
            k.tick(t);
            if (t < ECLIPSE_ESTALLA) {
                if (t % 15 == 0) soundAt(c, "block.beacon.ambient", 1.6f, 0.6f);
                if (t % 4 == 0) {
                    Fx.ring(c.clone().add(0, 0.2, 0), r, 36, p ->
                            Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, p, 1, 0, 0, 0, 0, transicion(1.3f)));
                }
                if (t % 10 == 0) {
                    soundAt(c, "block.bell.use", 1.6f, 0.5f + t * 0.005f);
                    int quedan = Math.max(1, (ECLIPSE_ESTALLA - t + 19) / 20);
                    for (Player p : Fx.viewersNear(c, 40)) {
                        boolean fuera = plano(p.getLocation(), c) > r;
                        p.sendActionBar(Component.text("Eclipse en " + quedan + " s   ", NamedTextColor.GRAY)
                                .append(Component.text(fuera ? "estás fuera" : "estás dentro",
                                        fuera ? NamedTextColor.GREEN : NamedTextColor.RED)));
                    }
                }
                return;
            }
            if (t != ECLIPSE_ESTALLA) return;
            soundAt(c, "block.beacon.deactivate", 1.4f, 0.7f);
            Location m = c.clone().add(0, 1, 0);
            Compat.spawn(world(), Compat.FLASH, c.clone().add(0, 5, 0), 1, 0, 0, 0, 0, C_SANGRE);
            Compat.spawn(world(), Compat.FLASH, m, 1, 0, 0, 0, 0, C_SANGRE);
            Compat.spawn(world(), Compat.DUST, c.clone().add(0, 5, 0), 40, 1.6, 1.6, 1.6, 0, Compat.dust(SANGRE, 2.0f));
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, m, 2);
            Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, m, 120, r * 0.5, 0.6, r * 0.5, 0, transicion(2.0f));
            soundAt(c, "entity.wither.spawn", 1.5f, 1.0f);
            soundAt(c, "entity.generic.explode", 1.6f, 0.5f);
            for (Player p : Fx.playersNear(c, r + 3)) {
                double d = plano(p.getLocation(), c);
                if (d > r + 0.2) continue;
                if (Math.abs(p.getLocation().getY() - c.getY()) > 4) continue;
                hit(p, (d < 3 ? 34 : 24) * power(), mult);
                push(p, fuera(p.getLocation(), c).multiply(1.2).setY(0.55));
            }
        }, esc::quitar);
    }

    // ============================================================ SANGRE ARDIENTE ==

    /**
     * 14. Sangre Ardiente: carga quince ticks una bola de sangre entre las manos,
     * mirando al grupo mas denso, y la lanza hacia donde estaba el grupo al soltarla.
     * Al chocar pega 22 en 3,5 bloques y deja un charco que quema cuatro segundos (3
     * cada medio segundo y fuego a quien lo pise).
     */
    public void sangreArdiente() {
        if (!alive()) return;
        final List<Player> grupo = grupoDenso();
        if (grupo.isEmpty()) return;
        final Location[] mira = {centroDe(grupo)};
        if (mira[0] == null) return;
        Compat.apply(boss, "slowness", BOLA_CARGA + 4, 4);
        soundAt(loc(), "item.firecharge.use", 1.6f, 0.6f);
        soundAt(loc(), "entity.blaze.shoot", 1.2f, 0.5f);
        announce(Component.text("Sangre ardiente. Sal de donde vaya a caer la bola."));
        final Escena esc = new Escena();
        final Bola b = new Bola(esc, 1.0, true, 0, BOLA_CARGA, () -> {
            Location g = centroDe(grupo);
            if (g != null) mira[0] = g;
            return mira[0].clone().add(0, 0.9, 0);
        });
        animate(BOLA_CARGA + BOLA_VUELO_MAX + 102, t -> {
            if (cerrado || !alive()) throw Stop.now();
            b.tick(t);
            if (b.hecho) throw Stop.now();
        }, esc::quitar);
    }

    /**
     * 15. Rafaga de Sangre (fase II en adelante): tres bolas pequeñas seguidas, una
     * cada seis ticks, a tres jugadores distintos; si hay menos, en abanico de 25
     * grados hacia el grupo. Sin charco: 10 a quien este a 2 bloques del choque.
     */
    public void rafagaDeSangre() {
        if (!alive()) return;
        final List<Player> tres = new ArrayList<>(pickTargets(3));
        if (tres.isEmpty()) return;
        final Location centro = centroDe(tres);
        if (centro == null) return;
        Compat.apply(boss, "slowness", BOLA_CARGA + 16, 4);
        soundAt(loc(), "item.firecharge.use", 1.6f, 0.8f);
        announce(Component.text("Ráfaga de sangre. Tres bolas: muévete."));
        final Escena esc = new Escena();
        final Bola[] bolas = new Bola[3];
        final double[] abanico = {-25 * D2R, 0, 25 * D2R};
        for (int i = 0; i < 3; i++) {
            final int n = i;
            int tl = BOLA_CARGA + 6 * i;
            int tc = i == 0 ? 0 : tl - 6;
            Supplier<Location> mira;
            if (tres.size() >= 3) {
                final Player p = tres.get(i);
                final Location[] ultima = {p.getLocation().add(0, 0.9, 0)};
                mira = () -> {
                    if (Fx.isFightable(p) && p.getWorld().equals(world())) ultima[0] = p.getLocation().add(0, 0.9, 0);
                    return ultima[0].clone();
                };
            } else {
                mira = () -> {
                    Location yo = boss.getLocation();
                    Location g = centroDe(tres);
                    Location base = g != null ? g : centro;
                    Vector d = base.toVector().subtract(yo.toVector()).setY(0);
                    double dist = Fx.clamp(d.length(), 4, 22);
                    if (d.lengthSquared() < 0.01) d = yo.getDirection().setY(0);
                    if (d.lengthSquared() < 0.01) d = new Vector(1, 0, 0);
                    d.normalize();
                    double ca = Math.cos(abanico[n]), sa = Math.sin(abanico[n]);
                    Vector r = new Vector(d.getX() * ca - d.getZ() * sa, 0, d.getX() * sa + d.getZ() * ca);
                    return yo.clone().add(r.multiply(dist)).add(0, base.getY() - yo.getY() + 0.9, 0);
                };
            }
            bolas[i] = new Bola(esc, 0.55, false, tc, tl, mira);
        }
        animate(BOLA_CARGA + 12 + BOLA_VUELO_MAX + 16, t -> {
            if (cerrado || !alive()) throw Stop.now();
            boolean quedan = false;
            for (Bola b : bolas) {
                b.tick(t);
                if (!b.hecho) quedan = true;
            }
            if (!quedan) throw Stop.now();
        }, esc::quitar);
    }

    // ================================================================== VASTAGOS ==

    /*
     * Los Vastagos: los hijos del Conde que salen de los ataudes. Cada uno es como el
     * propio Conde en pequeño: un zombi invisible que pelea (IA, vida, mordisco) y
     * encima un maniqui con la skin de una cuenta real que es lo que se ve. El maniqui
     * no tiene vida propia: lo que se le pega pasa a su zombi (Escucha.onGolpeAlVastago).
     *
     * Viven veinte segundos. Al acabarse, al morir su zombi o al caer el Conde, el
     * maniqui se desploma y se deshace en humo negro. Zombi y maniqui son esbirros
     * (markMinion), asi que si la pelea se cierra de golpe los borra super.cleanup().
     */

    /** La cuenta que lleva la skin de los Vastagos. */
    private static final String SKIN_VASTAGO = "Guilletp";
    private static final String VASTAGO_NOMBRE = "VÁSTAGO";
    private static final int VASTAGOS_MAX = 4;
    /** Vida util: veinte segundos. */
    private static final int VASTAGO_VIDA = 400;
    /** Lo que tarda en incorporarse desde que se abre el ataud. */
    private static final int VASTAGO_LEVANTA = 10;
    /** Lo que tarda en desplomarse antes de deshacerse. */
    private static final int VASTAGO_CAE = 8;
    /** A cuanto de la presa salpica su mordisco. */
    private static final double VASTAGO_MORDISCO_RADIO = 1.5;
    /** Vida "de diseno" que le roba al Conde cada mordisco. */
    private static final double VASTAGO_ROBO = 2;
    /** Donde se tumba: sobre el forro rojo del ataud abierto (la caja mide 0,75). */
    private static final double VASTAGO_ALTO_ATAUD = 0.76;

    /** Un Vastago: su zombi, su maniqui y en que punto de su vida esta. */
    private final class Vastago {
        final Zombie mob;
        final Mannequin cuerpo;
        /** Tumbado en el ataud hasta que se levanta; entonces sigue a su zombi. */
        boolean dePie;
        boolean cayendo;

        Vastago(Zombie mob, Mannequin cuerpo) {
            this.mob = mob;
            this.cuerpo = cuerpo;
        }

        boolean vivo() {
            return !cayendo && mob.isValid() && !mob.isDead();
        }

        boolean esSuyo(Entity e) {
            return e != null && (e.equals(mob) || e.equals(cuerpo));
        }

        /** Un tick de su vida. Corta la animacion en cuanto deja de estar en pie. */
        void tick(int t) {
            if (cerrado || cayendo || !mob.isValid() || mob.isDead()) throw Stop.now();
            if (t == VASTAGO_LEVANTA / 2) {
                // Medio incorporado, ya en el centro del ataud.
                posar(cuerpo, Pose.SNEAKING);
                Location c = mob.getLocation();
                if (cuerpo.isValid()) cuerpo.teleport(c);
                Compat.spawn(world(), Compat.LARGE_SMOKE, c.clone().add(0, 0.4, 0), 6, 0.3, 0.2, 0.5, 0.01);
            }
            if (t == VASTAGO_LEVANTA) levantarse();
            // Pegado a su zombi cada tick, como el cuerpo del jefe (tickShell).
            if (dePie && cuerpo.isValid()) cuerpo.teleport(mob.getLocation());
            if (t % 20 == 0) {
                desnudar(mob);
                if (dePie && !(mob.getTarget() instanceof Player p && Fx.isFightable(p))) {
                    mob.setTarget(Fx.nearest(mob.getLocation(), 40));
                }
            }
        }

        private void levantarse() {
            dePie = true;
            posar(cuerpo, Pose.STANDING);
            Location c = mob.getLocation().add(0, 1.0, 0);
            Compat.spawn(world(), Compat.FLASH, c, 1, 0, 0, 0, 0, C_SANGRE);
            Compat.spawn(world(), Compat.LARGE_SMOKE, c, 14, 0.35, 0.6, 0.35, 0.02);
            soundAt(c, "entity.zombie.ambient", 1.2f, 0.5f);
            // Baja del ataud por su pie y va a por el mas cercano.
            mob.setGravity(true);
            mob.setAI(true);
            mob.setTarget(Fx.nearest(mob.getLocation(), 40));
        }

        /**
         * Se desploma (se agacha, baja y se tumba) y se deshace en humo negro y
         * esquirlas. Se puede llamar varias veces; tras el cierre de la pelea no hace
         * nada, porque ya lo ha borrado todo super.cleanup().
         */
        void caer(boolean porJugador) {
            if (cayendo) return;
            cayendo = true;
            if (cerrado) return;
            Location donde = cuerpo.isValid() ? cuerpo.getLocation() : mob.getLocation();
            if (porJugador) soundAt(donde, "entity.zombie.death", 1.2f, 0.6f);
            // El zombi se va al tick siguiente: puede venir de dentro de su propia muerte.
            later(1, () -> {
                spawned.remove(mob);
                Fx.safeRemove(mob);
            });
            if (!cuerpo.isValid()) {
                vastagos.remove(this);
                return;
            }
            posar(cuerpo, Pose.SNEAKING);
            animate(VASTAGO_CAE, s -> {
                if (cerrado || !cuerpo.isValid()) throw Stop.now();
                if (s == VASTAGO_CAE - 3) posar(cuerpo, tumbado());
                if (s < VASTAGO_CAE - 3) cuerpo.teleport(cuerpo.getLocation().add(0, -0.06, 0));
                if (s % 2 == 0) {
                    Compat.spawn(world(), Compat.SMOKE, cuerpo.getLocation().add(0, 0.3, 0), 2, 0.2, 0.1, 0.2, 0.01);
                }
            }, () -> {
                vastagos.remove(this);
                if (cerrado || !cuerpo.isValid()) return;
                Location l = cuerpo.getLocation().add(0, 0.4, 0);
                Compat.spawn(world(), Compat.LARGE_SMOKE, l, 20, 0.5, 0.3, 0.5, 0.03);
                Compat.spawn(world(), Compat.SQUID_INK, l, 10, 0.4, 0.3, 0.4, 0.02);
                Compat.spawn(world(), Compat.BLOCK, l, 6, 0.4, 0.2, 0.4, 0, Material.BLACKSTONE.createBlockData());
                Glow.clear(cuerpo);
                spawned.remove(cuerpo);
                Fx.safeRemove(cuerpo);
            });
        }
    }

    /** El Vastago al que pertenece esa entidad (su zombi o su maniqui), o null. */
    private Vastago vastagoDe(Entity e) {
        if (e == null) return null;
        for (Vastago v : vastagos) {
            if (v.esSuyo(e)) return v;
        }
        return null;
    }

    private int vastagosEnPie() {
        int n = 0;
        for (Vastago v : vastagos) {
            if (v.vivo()) n++;
        }
        return n;
    }

    /**
     * Saca un Vastago tumbado dentro de un ataud abierto. Si ya hay cuatro en pie no
     * sale nada: el ataud suelta solo sus murcielagos.
     *
     * @param centro el centro del ataud a ras de suelo
     * @param giro   el giro del ataud: su eje Z local es el largo de la caja
     */
    private void levantarVastago(Location centro, Quaternionf giro) {
        if (!alive() || vastagosEnPie() >= VASTAGOS_MAX) return;
        Location encima = centro.clone().add(0, VASTAGO_ALTO_ATAUD, 0);
        Zombie z;
        try {
            // Sin datos al azar: ni cria, ni jinete de gallina, ni equipo de serie.
            z = world().spawn(encima, Zombie.class, false, e -> {
                e.setAdult();
                e.setInvisible(true);
                e.setSilent(true);
                e.setPersistent(false);
                e.setRemoveWhenFarAway(true);
                e.setCanPickupItems(false);
                e.setShouldBurnInDay(false);
                // Quieto y en el aire hasta que se levanta: esta encima de la caja.
                e.setAI(false);
                e.setGravity(false);
                // Con nombre aunque no se vea: el de la muerte lo pone killerName().
                e.customName(Component.text(VASTAGO_NOMBRE, ACCENT));
                e.setCustomNameVisible(false);
                Compat.setAttribute(e, "max_health", 60);
                e.setHealth(60);
                Compat.setAttribute(e, "attack_damage", 6);
                Compat.setAttribute(e, "movement_speed", 0.33);
                Compat.setAttribute(e, "follow_range", 40);
                desnudar(e);
            });
        } catch (Throwable t) {
            return;
        }
        markMinion(z);

        /* Tumbado a lo largo de la caja. El cliente pinta la pose de dormir sin cama
         * girando el cuerpo desde los pies, con la cabeza hacia (-cos yaw, sin yaw):
         * de ahi el yaw, y los pies se retrasan 0,9 para que quede centrado. */
        Vector3f largo = new Quaternionf(giro).transform(new Vector3f(0, 0, 1));
        float yaw = (float) Math.toDegrees(Math.atan2(largo.z, -largo.x));
        Location pies = encima.clone().add(-largo.x * 0.9, 0, -largo.z * 0.9);
        pies.setYaw(yaw);
        pies.setPitch(0);
        Mannequin m = cuerpoVastago(pies);
        if (m == null) {
            spawned.remove(z);
            Fx.safeRemove(z);
            return;
        }
        Vastago v = new Vastago(z, m);
        vastagos.add(v);
        animate(VASTAGO_VIDA, v::tick, () -> v.caer(false));
    }

    /**
     * El maniqui de un Vastago, tumbado. Lo mismo que wearShell hace para el jefe,
     * pero suelto: wearShell es del cuerpo del Conde y no se puede usar dos veces.
     */
    private Mannequin cuerpoVastago(Location at) {
        ResolvableProfile perfil = perfilVastago != null ? perfilVastago
                : Disguises.profileOfAccount(plugin, SKIN_VASTAGO);
        if (perfil == null) return null;
        Mannequin m;
        try {
            m = world().spawn(at, Mannequin.class, e -> {
                e.setProfile(perfil);
                e.setPersistent(false);
                e.setGravity(false);
                e.setCollidable(false);
                e.setSilent(true);
                e.setImmovable(true);
                e.setRotation(at.getYaw(), 0f);
                e.setBodyYaw(at.getYaw());
                e.customName(Component.text(VASTAGO_NOMBRE, ACCENT));
                e.setCustomNameVisible(true);
                // Sin la segunda linea "NPC" que trae de serie.
                try {
                    e.setDescription(Component.empty());
                } catch (Throwable ignored) {
                }
                posar(e, tumbado());
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("No se pudo sacar el cuerpo de un Vastago: " + t);
            return null;
        }
        markMinion(m);
        Glow.apply(m, NamedTextColor.DARK_RED);
        // Si la skin aun no habia llegado de Mojang, se le pone la cara buena al llegar.
        if (perfilVastago == null) {
            Disguises.resolveAccount(plugin, SKIN_VASTAGO, p -> {
                perfilVastago = p;
                if (m.isValid()) m.setProfile(p);
            });
        }
        return m;
    }

    /** Tumbado si el maniqui lo admite; si no, agachado. */
    private static Pose tumbado() {
        try {
            if (Mannequin.validPoses().contains(Pose.SLEEPING)) return Pose.SLEEPING;
        } catch (Throwable ignored) {
        }
        return Pose.SNEAKING;
    }

    /** Pone una pose fija al maniqui; si esa version no la admite, lo deja como esta. */
    private static void posar(Mannequin m, Pose pose) {
        if (m == null) return;
        try {
            if (Mannequin.validPoses().contains(pose)) m.setPose(pose, true);
        } catch (Throwable ignored) {
        }
    }

    /** El zombi de debajo va desnudo: la invisibilidad no esconde el equipo. */
    private static void desnudar(Zombie z) {
        EntityEquipment eq = z.getEquipment();
        if (eq == null) return;
        boolean vestido = !eq.getItemInMainHand().getType().isAir() || !eq.getItemInOffHand().getType().isAir();
        for (org.bukkit.inventory.ItemStack it : eq.getArmorContents()) {
            if (it != null && !it.getType().isAir()) vestido = true;
        }
        if (!vestido) return;
        eq.clear();
    }

    // ================================================================ MURCIELAGOS ==

    /** Un murcielago nuestro: sin IA (vuela por teleport), callado y despierto. */
    private Bat murcielago(Location at) {
        try {
            Bat b = world().spawn(at, Bat.class, e -> {
                e.setAwake(true);
                e.setAI(false);
                e.setGravity(false);
                e.setSilent(true);
                e.setPersistent(false);
                e.setRemoveWhenFarAway(false);
                e.setCollidable(false);
            });
            markMinion(b);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Mueve una bandada: primero orbita alrededor de un centro (si orbita > 0) y
     * despues cada murcielago se lanza contra su objetivo, muerde una vez y se va en
     * humo. Lo que siga vivo al acabar el tiempo tambien se va en humo.
     *
     * La lista puede crecer mientras vuela (los Colmillos van soltando crias segun
     * brota cada colmillo), por eso se recorre por indice y no se copia.
     *
     * @param fijo   objetivo comun de toda la bandada; null = se reparten los objetivos
     * @param centro centro de la orbita; null si no orbitan
     */
    private void bandada(List<Bat> bats, Player fijo, Supplier<Location> centro,
                         int orbita, int vida, double dano, double velocidad) {
        final List<Player> asignado = new ArrayList<>();
        final double fase0 = random.nextDouble() * Math.PI * 2;
        animate(vida, t -> {
            if (cerrado || !alive()) throw Stop.now();
            boolean quedan = false;
            for (int i = 0; i < bats.size(); i++) {
                Bat b = bats.get(i);
                if (b == null || !b.isValid()) continue;
                quedan = true;
                while (asignado.size() <= i) asignado.add(null);

                if (t < orbita && centro != null) {
                    double a = fase0 + Math.PI * 2 * i / Math.max(1, bats.size()) + t * 0.28;
                    Location c = centro.get();
                    fly(b, c.clone().add(Math.cos(a) * 3, 1.6 + (i % 3) * 0.5, Math.sin(a) * 3), 0.9);
                    if (t % 4 == 0) Compat.spawn(world(), Compat.SMOKE, b.getLocation(), 1, 0, 0, 0, 0);
                    continue;
                }
                if (t == orbita && i == 0 && orbita > 0) soundAt(b.getLocation(), "entity.phantom.swoop", 1.8f, 1.1f);

                Player obj = fijo != null ? fijo : asignado.get(i);
                if (obj == null || !Fx.isFightable(obj) || !obj.getWorld().equals(world())) {
                    List<Player> pool = targets();
                    obj = pool.isEmpty() ? null : pool.get(i % pool.size());
                    asignado.set(i, obj);
                }
                if (obj == null) continue;
                Location pecho = obj.getLocation().add(0, 1.1, 0);
                Location punto = pecho.clone().add(Math.sin(t * 0.4 + i) * 0.4, Math.cos(t * 0.3 + i) * 0.3,
                        Math.cos(t * 0.4 + i) * 0.4);
                fly(b, punto, velocidad);
                if (t % 3 == 0) Compat.spawn(world(), Compat.SMOKE, b.getLocation(), 1, 0, 0, 0, 0);
                if (b.getLocation().distance(pecho) > 1.3) continue;

                hit(obj, dano);
                Compat.spawn(world(), Compat.DAMAGE_INDICATOR, pecho, 2, 0.2, 0.2, 0.2, 0.1);
                Compat.spawn(world(), Compat.ENTITY_EFFECT, pecho, 5, 0.2, 0.3, 0.2, 0, rojo());
                Compat.spawn(world(), Compat.LARGE_SMOKE, b.getLocation(), 4, 0.15, 0.15, 0.15, 0.02);
                soundAt(pecho, "entity.phantom.bite", 1.2f, 1.3f);
                soundAt(pecho, "entity.bat.hurt", 1.0f, 0.8f);
                spawned.remove(b);
                Fx.safeRemove(b);
            }
            // Margen para las crias de los Colmillos, que nacen hasta medio segundo tarde.
            if (!quedan && t > orbita + 40) throw Stop.now();
        }, () -> {
            for (Bat b : bats) {
                if (b == null || !b.isValid()) continue;
                Compat.spawn(world(), Compat.LARGE_SMOKE, b.getLocation(), 5, 0.2, 0.2, 0.2, 0.02);
                Compat.spawn(world(), Compat.SQUID_INK, b.getLocation(), 2, 0.1, 0.1, 0.1, 0.01);
                spawned.remove(b);
                Fx.safeRemove(b);
            }
        });
    }

    /**
     * Una bandada que solo sale volando en todas direcciones y se apaga en humo. Es la
     * entrada, los cambios de fase y la muerte. No depende de que el jefe siga vivo.
     */
    private void dispersar(Location desde, int n, int ticks) {
        List<Bat> bats = new ArrayList<>();
        List<Vector> rumbos = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Bat b = murcielago(desde.clone().add(random.nextDouble() * 0.6 - 0.3, random.nextDouble() * 0.6,
                    random.nextDouble() * 0.6 - 0.3));
            if (b == null) continue;
            bats.add(b);
            double a = random.nextDouble() * Math.PI * 2;
            rumbos.add(new Vector(Math.cos(a), 0.25 + random.nextDouble() * 0.5, Math.sin(a))
                    .normalize().multiply(0.3 + random.nextDouble() * 0.2));
        }
        animate(ticks, t -> {
            for (int i = 0; i < bats.size(); i++) {
                Bat b = bats.get(i);
                if (!b.isValid()) continue;
                Location next = b.getLocation().add(rumbos.get(i));
                next.setDirection(rumbos.get(i));
                b.teleport(next);
            }
        }, () -> {
            for (Bat b : bats) {
                if (!b.isValid()) continue;
                Compat.spawn(world(), Compat.LARGE_SMOKE, b.getLocation(), 4, 0.15, 0.15, 0.15, 0.02);
                spawned.remove(b);
                Fx.safeRemove(b);
            }
        });
    }

    /** Vuelo por teleport, como Herbola: velocidad fija y mirando a donde va. */
    private void fly(LivingEntity bird, Location to, double speed) {
        if (bird == null || !bird.isValid() || to == null) return;
        try {
            Location here = bird.getLocation();
            Vector step = to.toVector().subtract(here.toVector());
            double dist = step.length();
            if (dist < 0.02) return;
            Location next = dist <= speed ? to.clone() : here.clone().add(step.clone().multiply(speed / dist));
            // El teleport no choca con nada: sin esto atravesaban las paredes del
            // coliseo. Se frena al entrar en un bloque, no al salir de el, para que
            // uno que nazca dentro de algo pueda escaparse.
            if (next.getBlock().getType().isSolid() && !here.getBlock().getType().isSolid()) return;
            next.setDirection(step);
            bird.teleport(next);
        } catch (Throwable ignored) {
        }
    }

    /**
     * El punto mas cercano a 'hacia' al que se llega desde 'desde' en linea recta sin
     * atravesar un bloque, con margen, y con 'alto' bloques libres por encima.
     */
    private Location despejado(Location desde, Location hacia, double alto) {
        try {
            Location fin = hacia.clone();
            Vector dir = hacia.toVector().subtract(desde.toVector());
            double dist = dir.length();
            if (dist > 0.1) {
                org.bukkit.util.RayTraceResult r = world().rayTraceBlocks(desde, dir.clone().multiply(1 / dist),
                        dist, org.bukkit.FluidCollisionMode.NEVER, true);
                if (r != null) {
                    double hasta = Math.max(0, r.getHitPosition().distance(desde.toVector()) - 1.5);
                    fin = desde.clone().add(dir.clone().multiply(hasta / dist));
                }
            }
            // Que quepa entero: a escala 3.2 el murcielago mide casi tres bloques.
            org.bukkit.util.RayTraceResult techo = world().rayTraceBlocks(fin, new Vector(0, 1, 0), alto,
                    org.bukkit.FluidCollisionMode.NEVER, true);
            if (techo != null) fin.setY(Math.max(desde.getY(), techo.getHitPosition().getY() - alto));
            return fin;
        } catch (Throwable t) {
            return hacia;
        }
    }

    // ============================================================ CIRCULO MAGICO ==

    /**
     * Una pieza del circulo: un display con su transformacion "de reposo" relativa al
     * centro. Girar el circulo es rotar esa transformacion entera alrededor del eje Y
     * del centro; como todas las piezas nacen EN el centro, basta con eso y el cliente
     * interpola el giro sin que nada se teletransporte.
     */
    private static final class Pieza {
        final Display d;
        final Vector3f mid;
        final Quaternionf q;
        final Vector3f escala;
        final boolean bloque;
        /** 0 fija, 1 anillo de runas, 2 estrella. */
        final int grupo;

        Pieza(Display d, Vector3f mid, Quaternionf q, Vector3f escala, boolean bloque, int grupo) {
            this.d = d;
            this.mid = mid;
            this.q = q;
            this.escala = escala;
            this.bloque = bloque;
            this.grupo = grupo;
        }

        static Transformation forma(Vector3f mid, Quaternionf q, Vector3f escala, boolean bloque,
                                    float f, float giro) {
            Vector3f s = new Vector3f(escala).mul(f);
            Vector3f t = new Vector3f(mid);
            if (bloque) {
                // El bloque va de 0 a 1 en su espacio: se centra en X y Z sobre su punto.
                Vector3f mitad = new Vector3f(s.x / 2f, 0f, s.z / 2f);
                t.sub(new Quaternionf(q).transform(mitad));
            }
            Quaternionf r = new Quaternionf().rotateY(giro);
            r.transform(t);
            return new Transformation(t, new Quaternionf(r).mul(q), s, new Quaternionf());
        }

        void aplicar(float f, float giro, int interp) {
            if (d == null || !d.isValid()) return;
            try {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(interp);
                d.setTransformation(forma(mid, q, escala, bloque, f, giro));
            } catch (Throwable ignored) {
            }
        }
    }

    /** Un circulo magico dibujado en el suelo. Ver circulo(). */
    private final class Circulo {
        final Location centro;
        final double radio;
        final List<Pieza> piezas = new ArrayList<>();
        /** Las cinco puntas de la estrella, a ras de suelo (solo si lleva pentaculo). */
        final List<Location> vertices = new ArrayList<>();
        float giroRunas;
        float giroEstrella;
        boolean girarEstrella;
        boolean vivo = true;

        Circulo(Location centro, double radio) {
            this.centro = centro;
            this.radio = radio;
        }

        float giroDe(int grupo) {
            return grupo == 1 ? giroRunas : grupo == 2 ? giroEstrella : 0f;
        }

        /** Se encoge en cinco ticks y desaparece. Se puede llamar varias veces. */
        void quitar() {
            if (!vivo) return;
            vivo = false;
            for (Pieza p : piezas) p.aplicar(0.02f, giroDe(p.grupo), 5);
            later(6, () -> {
                for (Pieza p : piezas) {
                    spawned.remove(p.d);
                    Fx.safeRemove(p.d);
                }
            });
        }
    }

    /**
     * Dibuja un circulo magico rojo y negro en el suelo, recoloreado del "Light Magic
     * Circle" de block-display.com: un anillo de runas (TextDisplay tumbados) entre
     * dos anillos finos de bloque, y si se pide, el pentaculo dentro.
     *
     * Los anillos son poligonos de segmentos alargados y no puntos sueltos: con
     * catorce o cuarenta lados se lee como una linea continua, y cuesta la mitad de
     * entidades. Se dibuja por partes en ocho ticks (primero el borde rojo, luego el
     * fino, el interior, las runas y la estrella), como se construyen el portal de la
     * Parca y la Grieta de Calamity. Cada pieza nace creciendo desde su centro, las
     * runas giran despacio (la estrella en sentido contrario si se activa) y al acabar
     * se encoge.
     *
     * Lo que lleva, de fuera a dentro:
     *   - anillo exterior rojo y, pegado por dentro, un anillo fino negro (borde doble);
     *   - la banda de runas tumbadas mirando al cielo, con glifos GRANDES en los puntos
     *     cardinales (cuatro en los pequeños, ocho en los grandes) entre las pequeñas;
     *   - anillo interior negro (no en los ligeros);
     *   - con pentaculo: la estrella de cinco puntas en trazo fino (rojo vivo sobre un
     *     ribete negro) y un disco pequeño de ladrillo del nether rojo en el centro.
     *
     * Hoy solo lo usa el Festin (la version ligera, ~18 entidades); el Sello, el
     * Pentaculo y el Eclipse tienen sus propios efectos (ver EFECTOS v2).
     *
     * @param ligero sin anillo interior ni anillo fino: para cuando hay muchos a la vez
     *               (el Festin, o los sellos a partir del decimotercero)
     */
    private Circulo circulo(Location suelo, double radio, int vida, boolean pentaculo, boolean ligero) {
        Location base = suelo.clone().add(0, 0.02, 0);
        base.setYaw(0);
        base.setPitch(0);
        Circulo k = new Circulo(suelo.clone(), radio);
        float ancho = radio < 2.5 ? 0.07f : 0.12f;
        float fino = ancho * 0.5f;

        // Anillo exterior, rojo.
        int lados = (int) Fx.clamp(Math.round(radio * 4), 10, 36);
        anillo(k, base, radio, lados, ancho, Material.RED_CONCRETE);
        // Pegado por dentro, el anillo fino negro.
        if (!ligero) anillo(k, base, radio * 0.9, Math.max(8, (int) Math.round(lados * 0.6)), fino, Material.BLACK_CONCRETE);
        // Anillo interior, negro.
        double interior = radio * 0.66;
        if (!ligero) anillo(k, base, interior, Math.max(10, (int) (lados * 0.7)), ancho, Material.BLACK_CONCRETE);

        // Las runas, en medio. Escala segun el radio: a 0.7 fijo no se verian en el grande.
        // Tantas como quepan, redondeadas a multiplo de los glifos grandes para que estos
        // caigan justo en los puntos cardinales.
        int grandes = radio >= 4 ? 8 : 4;
        int runas = (int) Fx.clamp(Math.round(radio * 3.6), 6, 24);
        runas = Math.max(grandes * 2, Math.round(runas / (float) grandes) * grandes);
        int cada = runas / grandes;
        float gs = (float) Fx.clamp(0.8 + radio * 0.25, 1.0, 3.0);
        float gsGrande = gs * (radio >= 4 ? 1.7f : 1.5f);
        for (int i = 0; i < runas; i++) {
            double a = Math.PI * 2 * i / runas;
            boolean grande = i % cada == 0;
            String glifo = grande ? GLIFOS_GRANDES[(i / cada) % GLIFOS_GRANDES.length] : GLIFOS[i % GLIFOS.length];
            int color = grande ? SANGRE : (i % 3 == 2 ? NEGRO : (i % 3 == 1 ? GRANATE : SANGRE));
            float s = grande ? gsGrande : gs;
            // El texto crece desde su borde de abajo: se mete hacia dentro la mitad de su alto.
            double rr = radio * (grande ? 0.78 : 0.83) - s * 0.12;
            // Tumbada (cara al cielo: 90 grados en X, sin billboard) y con la parte de
            // arriba de la letra mirando afuera.
            float yaw = (float) Math.atan2(-Math.cos(a), -Math.sin(a));
            Quaternionf q = new Quaternionf().rotateY(yaw).rotateX((float) (-Math.PI / 2));
            Vector3f mid = new Vector3f((float) (Math.cos(a) * rr), grande ? 0.06f : 0.05f, (float) (Math.sin(a) * rr));
            Vector3f esc = new Vector3f(s, s, s);
            TextDisplay td = runa(base, glifo, color, Pieza.forma(mid, q, esc, false, 0.02f, 0f));
            if (td != null) k.piezas.add(new Pieza(td, mid, q, esc, false, 1));
        }

        // El pentaculo: cinco trazos que unen cada punta con la que esta a dos. Cada
        // trazo es una linea fina de rojo vivo sobre un ribete negro algo mas ancho y un
        // pelo mas bajo, para que se lea nitida sobre cualquier suelo y no parpadee.
        if (pentaculo) {
            Vector3f[] v = new Vector3f[5];
            for (int i = 0; i < 5; i++) {
                double a = -Math.PI / 2 + i * Math.PI * 2 / 5;
                v[i] = new Vector3f((float) (Math.cos(a) * interior), 0f, (float) (Math.sin(a) * interior));
                k.vertices.add(Fx.ground(suelo.clone().add(v[i].x, 0, v[i].z), 4));
            }
            for (int i = 0; i < 5; i++) {
                Vector3f a = v[i];
                Vector3f b = v[(i + 2) % 5];
                segmento(k, base, a, b, ancho * 1.3f, Material.BLACK_CONCRETE, 2, 0f);
                segmento(k, base, a, b, ancho * 0.55f, Material.REDSTONE_BLOCK, 2, 0.006f);
            }
            // El disco del centro: dos cuadrados de ladrillo rojo cruzados a 45 grados.
            float lado = (float) (interior * 0.3);
            for (int i = 0; i < 2; i++) {
                double a = Math.PI / 4 * i;
                Vector3f p0 = new Vector3f((float) (-Math.cos(a) * lado / 2), 0f, (float) (-Math.sin(a) * lado / 2));
                Vector3f p1 = new Vector3f((float) (Math.cos(a) * lado / 2), 0f, (float) (Math.sin(a) * lado / 2));
                segmento(k, base, p0, p1, lado, Material.RED_NETHER_BRICKS, 2, 0.003f * (i + 1));
            }
        }

        // Por partes: la pieza n-esima aparece en el tick 2 + n*8/total.
        final int total = Math.max(1, k.piezas.size());
        animate(vida, t -> {
            if (!k.vivo || cerrado) throw Stop.now();
            if (t >= 2 && t <= 9) {
                for (int i = 0; i < k.piezas.size(); i++) {
                    if (2 + i * 8 / total != t) continue;
                    Pieza p = k.piezas.get(i);
                    p.aplicar(1f, k.giroDe(p.grupo), 6);
                }
            }
            if (t >= 10 && t % 10 == 0) {
                k.giroRunas += 0.12f;
                if (k.girarEstrella) k.giroEstrella -= 0.10f;
                for (Pieza p : k.piezas) {
                    if (p.grupo == 1 || (p.grupo == 2 && k.girarEstrella)) p.aplicar(1f, k.giroDe(p.grupo), 10);
                }
            }
            // Pocas llamas de alma recorriendo el borde: lo justo para que se vea vivo.
            if (t % 3 == 0) {
                double a = random.nextDouble() * Math.PI * 2;
                Compat.spawn(world(), Compat.SOUL_FIRE_FLAME,
                        suelo.clone().add(Math.cos(a) * radio, 0.12, Math.sin(a) * radio), 1, 0, 0.02, 0, 0.01);
            }
        }, k::quitar);
        return k;
    }

    private void anillo(Circulo k, Location base, double r, int lados, float ancho, Material mat) {
        for (int i = 0; i < lados; i++) {
            double a0 = Math.PI * 2 * i / lados;
            double a1 = Math.PI * 2 * (i + 1) / lados;
            Vector3f p0 = new Vector3f((float) (Math.cos(a0) * r), 0f, (float) (Math.sin(a0) * r));
            Vector3f p1 = new Vector3f((float) (Math.cos(a1) * r), 0f, (float) (Math.sin(a1) * r));
            segmento(k, base, p0, p1, ancho, mat, 0, 0f);
        }
    }

    /**
     * Un trazo recto de a a b hecho con un bloque estirado. 'dy' lo sube un pelo sobre
     * lo que tenga debajo: dos caras a la misma altura parpadean.
     */
    private void segmento(Circulo k, Location base, Vector3f a, Vector3f b, float ancho, Material mat, int grupo,
                          float dy) {
        float dx = b.x - a.x;
        float dz = b.z - a.z;
        float largo = (float) Math.sqrt(dx * dx + dz * dz) + 0.03f;
        // Con rotateY, el eje X local acaba en (cos, 0, -sin): de ahi el -dz.
        float yaw = (float) Math.atan2(-dz, dx);
        Quaternionf q = new Quaternionf().rotateY(yaw);
        Vector3f mid = new Vector3f((a.x + b.x) / 2f, dy, (a.z + b.z) / 2f);
        Vector3f esc = new Vector3f(largo, 0.03f, ancho);
        try {
            BlockDisplay d = world().spawn(base, BlockDisplay.class, e -> {
                e.setBlock(mat.createBlockData());
                e.setBrightness(new Display.Brightness(15, 15));
                e.setViewRange(3.0f);
                e.setShadowRadius(0f);
                e.setPersistent(false);
                e.setTeleportDuration(0);
                e.setTransformation(Pieza.forma(mid, q, esc, true, 0.02f, 0f));
            });
            Tags.markTemporary(d);
            track(d);
            k.piezas.add(new Pieza(d, mid, q, esc, true, grupo));
        } catch (Throwable ignored) {
        }
    }

    private TextDisplay runa(Location base, String glifo, int rgb, Transformation t0) {
        try {
            TextDisplay d = world().spawn(base, TextDisplay.class, e -> {
                e.text(Component.text(glifo, TextColor.color(rgb)));
                e.setBillboard(Display.Billboard.FIXED);
                e.setAlignment(TextDisplay.TextAlignment.CENTER);
                e.setDefaultBackground(false);
                e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                e.setSeeThrough(false);
                e.setShadowed(true);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setViewRange(3.0f);
                e.setShadowRadius(0f);
                e.setPersistent(false);
                e.setTeleportDuration(0);
                e.setTransformation(t0);
            });
            Tags.markTemporary(d);
            track(d);
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================ EFECTOS v2 ==

    /*
     * Los efectos grandes (Sello, Pentaculo, Eclipse, Sangre Ardiente y la entrada) son
     * los de la vista previa en 3D que aprobo Dosa (conde-v2), con sus medidas, tiempos
     * y cantidades. Todo son BlockDisplay a brillo 15 (y TextDisplay para las runas):
     * cada efecto es una funcion del tick que dice donde esta cada pieza, y en cada
     * tick se le manda su transformacion con dos ticks de interpolacion; los giros y
     * latidos van por transformacion y los desplazamientos largos (la bola, los rayos
     * de la espiral) por teleport con dos ticks de deslizamiento.
     *
     * Las piezas nacen cuando les toca aparecer y nacen a escala cero, para que el
     * cliente las vea crecer; una pieza que ya estaba a cero no manda nada. Todas van
     * a 'spawned' y cada efecto borra las suyas al terminar o si se corta a medias.
     */

    private static final double TAU = Math.PI * 2;
    private static final double D2R = Math.PI / 180;

    private static double cl(double x) {
        return x < 0 ? 0 : (x > 1 ? 1 : x);
    }

    /** El avance de t entre a y b, recortado a [0, 1]. */
    private static double seg(double t, double a, double b) {
        return cl((t - a) / (b - a));
    }

    /** Suavizado en los dos extremos. */
    private static double sm(double x) {
        x = cl(x);
        return x * x * (3 - 2 * x);
    }

    /** Sale rapido y frena al final. */
    private static double eo(double x) {
        x = cl(x);
        return 1 - Math.pow(1 - x, 3);
    }

    private static double lerp(double a, double b, double u) {
        return a + (b - a) * u;
    }

    /** Ruido fijo en [0, 1): el mismo n da siempre lo mismo, como en la vista previa. */
    private static double h1(double n) {
        double x = Math.sin(n * 127.1 + 311.7) * 43758.5453;
        return x - Math.floor(x);
    }

    /** Giro en X, luego en Y, luego en Z (el orden de la vista previa). */
    private static Quaternionf xyz(double x, double y, double z) {
        return new Quaternionf().rotateX((float) x).rotateY((float) y).rotateZ((float) z);
    }

    private static Quaternionf giroY(double a) {
        return new Quaternionf().rotateY((float) a);
    }

    private static Location sinGiro(Location l) {
        Location c = l.clone();
        c.setYaw(0);
        c.setPitch(0);
        return c;
    }

    /** Un bloque de medida (sx, sy, sz) con su centro en (x, y, z) y girado q sobre ese centro. */
    private static Transformation centrada(double x, double y, double z, Quaternionf q,
                                           double sx, double sy, double sz) {
        float fx = (float) Math.max(sx, 1e-4);
        float fy = (float) Math.max(sy, 1e-4);
        float fz = (float) Math.max(sz, 1e-4);
        Vector3f mitad = new Quaternionf(q).transform(new Vector3f(fx / 2f, fy / 2f, fz / 2f));
        return new Transformation(new Vector3f((float) x - mitad.x, (float) y - mitad.y, (float) z - mitad.z),
                new Quaternionf(q), new Vector3f(fx, fy, fz), new Quaternionf());
    }

    private interface Parte {
        void quitar();
    }

    /** Las piezas de un efecto, para borrarlas todas de una vez. */
    private final class Escena {
        private final List<Parte> partes = new ArrayList<>();

        Pz pz(Location ancla, Material m, double w, double h, double l) {
            return pz(ancla, m.createBlockData(), w, h, l, 2);
        }

        Pz pz(Location ancla, BlockData b, double w, double h, double l, int teleporte) {
            Pz p = new Pz(ancla, b, w, h, l, teleporte);
            partes.add(p);
            return p;
        }

        Rn rn(Location ancla, String glifo, float escala) {
            Rn r = new Rn(ancla, glifo, escala);
            partes.add(r);
            return r;
        }

        Rayo rayo(Location suelo, float ancho, float nucleo) {
            Rayo r = new Rayo(suelo, ancho, nucleo);
            partes.add(r);
            return r;
        }

        /**
         * Un modelo de block-display.com (ver anomaly/fx) con brillo 15 y sus entidades en
         * 'spawned'. 'antes' recibe la instancia ya preparada y sin entidades (para darle
         * escala o giro de salida). Devuelve null si el modelo no se pudo cargar o crear:
         * el efecto sigue sin el dibujo (el daño y los avisos no dependen de el).
         */
        Instancia modelo(String nombre, Opciones.Builder opciones, Consumer<Instancia> antes) {
            ModeloBde m = modeloBde(nombre);
            if (m == null) return null;
            Instancia inst;
            try {
                inst = m.crear(opciones.brillo(15, 15).registrar(x -> {
                    Tags.markTemporary(x);
                    track(x);
                }).build());
            } catch (RuntimeException fallo) {
                plugin.getLogger().warning("CONDE: no se pudo preparar el modelo " + nombre + ": " + fallo.getMessage());
                return null;
            }
            Modelo parte = new Modelo(inst);
            partes.add(parte);
            try {
                if (antes != null) antes.accept(inst);
                inst.spawn();
            } catch (RuntimeException fallo) {
                plugin.getLogger().warning("CONDE: no se pudo crear el modelo " + nombre + ": " + fallo.getMessage());
                parte.quitar();
                return null;
            }
            return inst;
        }

        void quitar() {
            for (Parte p : partes) {
                try {
                    p.quitar();
                } catch (Throwable ignored) {
                }
            }
            partes.clear();
        }
    }

    /** Los modelos de block-display.com del Conde, dentro del jar (src/main/resources/models). */
    private static final String MODELO_CIRCULO = "conde-circulo";
    /**
     * 1.84.3 · El "Magic Circle Spell" (el mismo diseño del circulo del Cuerno de Calamity), en
     * rojo, con calaveras de wither esqueleto y velas rojas en lugar de sus 12 items (Dosa). Va
     * en el Sello y en el Pentaculo; el Eclipse y el Festin siguen con conde-circulo.
     */
    private static final String MODELO_SELLO = "conde-sello";
    /** Punto de la mecha de una vela del modelo, en el cubo local de su bloque. */
    private static final Vector3f MECHA_VELA = new Vector3f(0.5f, 0.5f, 0.5f);
    private static final String MODELO_BOLA = "conde-bola";
    /** Modelos que ya fallaron al cargar: se avisa una sola vez por pelea. */
    private final Set<String> modelosRotos = new HashSet<>();

    /** Las velas de un modelo arden: una llama chica sobre cada mecha cada cuatro ticks. */
    private void llamas(Instancia inst, int t) {
        if (inst == null || t % 4 != 0) return;
        for (Location l : inst.puntos(Material.RED_CANDLE, MECHA_VELA)) {
            Compat.spawn(world(), Compat.SMALL_FLAME, l, 1, 0, 0, 0, 0);
        }
    }

    private ModeloBde modeloBde(String nombre) {
        if (modelosRotos.contains(nombre)) return null;
        try {
            return ModeloBde.cargar(nombre);
        } catch (RuntimeException fallo) {
            modelosRotos.add(nombre);
            plugin.getLogger().warning("CONDE: el modelo " + nombre + " no carga; el efecto ira sin dibujo: "
                    + fallo.getMessage());
            return null;
        }
    }

    /** Una instancia de un modelo dentro de una Escena: al quitarla, fuera sus entidades. */
    private final class Modelo implements Parte {
        private final Instancia inst;

        Modelo(Instancia inst) {
            this.inst = inst;
        }

        @Override
        public void quitar() {
            for (Entity x : inst.entidades()) spawned.remove(x);
            inst.retirar();
        }
    }

    /** Retira una instancia ya y saca sus entidades de 'spawned'. */
    private void retirarModelo(Instancia inst) {
        if (inst == null) return;
        for (Entity x : inst.entidades()) spawned.remove(x);
        inst.retirar();
    }

    /**
     * Una pieza: un BlockDisplay con su medida base (w, h, l) que se coloca por su
     * centro respecto a un ancla. Nace la primera vez que tiene tamaño, a escala cero.
     */
    private final class Pz implements Parte {
        private final BlockData datos;
        private final double w;
        private final double h;
        private final double l;
        private final int teleporte;
        private Location ancla;
        private BlockDisplay d;
        private boolean oculto = true;
        private boolean fallo;

        Pz(Location ancla, BlockData datos, double w, double h, double l, int teleporte) {
            this.ancla = sinGiro(ancla);
            this.datos = datos;
            this.w = w;
            this.h = h;
            this.l = l;
            this.teleporte = teleporte;
        }

        /** Mueve el ancla (por teleport); las coordenadas de put son relativas a ella. */
        void ancla(Location a) {
            ancla = sinGiro(a);
            if (d == null || !d.isValid()) return;
            try {
                d.teleport(ancla);
            } catch (Throwable ignored) {
            }
        }

        void put(double x, double y, double z, Quaternionf q, double s) {
            put(x, y, z, q, s, s, s, false);
        }

        /** 'salto' = sin interpolacion: para lo que cambia de sitio de golpe (una chispa nueva). */
        void put(double x, double y, double z, Quaternionf q, double sx, double sy, double sz, boolean salto) {
            boolean nada = !(sx > 0.002 && sy > 0.002 && sz > 0.002);
            if (nada && oculto) return;
            if (d == null) {
                if (fallo) return;
                d = pieza(ancla, datos, centrada(x, y, z, q, 0, 0, 0), teleporte);
                if (d == null) fallo = true;
                else oculto = false;
                return;
            }
            if (!d.isValid()) return;
            oculto = nada;
            Transformation tr = nada ? centrada(x, y, z, q, 0, 0, 0) : centrada(x, y, z, q, w * sx, h * sy, l * sz);
            try {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(salto ? 0 : 2);
                d.setTransformation(tr);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void quitar() {
            fallo = true;
            oculto = true;
            if (d == null) return;
            spawned.remove(d);
            Fx.safeRemove(d);
            d = null;
        }
    }

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

    /** Media altura de una linea de texto a escala 1: la runa se centra en su punto. */
    private static final float RUNA_MEDIA = 0.12f;

    /**
     * Una runa: un TextDisplay rojo sobre transparente que siempre se ve de frente.
     *
     * Va colocada por TELEPORT y no por la traslacion de su transformacion: el cliente
     * gira un display con billboard antes de aplicarle la transformacion, asi que una
     * runa desplazada desde el ancla daria vueltas alrededor de ella segun mirase cada
     * jugador. En la transformacion solo queda el centrado en altura, que no gira.
     */
    private final class Rn implements Parte {
        private final String glifo;
        private final float escala;
        private final Location ancla;
        private TextDisplay d;
        private boolean oculto = true;
        private boolean fallo;
        private double ux;
        private double uy;
        private double uz;

        Rn(Location ancla, String glifo, float escala) {
            this.ancla = sinGiro(ancla);
            this.glifo = glifo;
            this.escala = escala;
        }

        void put(double x, double y, double z, double s) {
            boolean nada = !(s > 0.002);
            if (nada && oculto) return;
            float e = nada || d == null ? 1e-4f : (float) (escala * s);
            Transformation tr = new Transformation(new Vector3f(0f, -RUNA_MEDIA * e, 0f),
                    new Quaternionf(), new Vector3f(e), new Quaternionf());
            Location donde = ancla.clone().add(x, y, z);
            if (d == null) {
                if (fallo) return;
                d = runaDe(donde, glifo, tr);
                if (d == null) fallo = true;
                else oculto = false;
                ux = x;
                uy = y;
                uz = z;
                return;
            }
            if (!d.isValid()) return;
            oculto = nada;
            try {
                double dx = x - ux;
                double dy = y - uy;
                double dz = z - uz;
                if (dx * dx + dy * dy + dz * dz > 1e-6) {
                    d.teleport(donde);
                    ux = x;
                    uy = y;
                    uz = z;
                }
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(2);
                d.setTransformation(tr);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void quitar() {
            fallo = true;
            oculto = true;
            if (d == null) return;
            spawned.remove(d);
            Fx.safeRemove(d);
            d = null;
        }
    }

    private TextDisplay runaDe(Location at, String glifo, Transformation tr) {
        try {
            TextDisplay d = world().spawn(at, TextDisplay.class, e -> {
                e.text(Component.text(glifo, TextColor.color(SANGRE)));
                e.setBillboard(Display.Billboard.VERTICAL);
                e.setAlignment(TextDisplay.TextAlignment.CENTER);
                e.setDefaultBackground(false);
                e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                e.setSeeThrough(false);
                e.setShadowed(false);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setViewRange(3.0f);
                e.setShadowRadius(0f);
                e.setPersistent(false);
                e.setTeleportDuration(2);
                e.setInterpolationDelay(0);
                e.setInterpolationDuration(2);
                e.setTransformation(tr);
            });
            Tags.markTemporary(d);
            track(d);
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------- el rayo

    private static final float RAYO_ALTO = 80f;

    /**
     * Un rayo rojo de 80 bloques apoyado en el suelo: una pieza de cristal rojo y
     * dentro un nucleo de bloque de redstone. set(p, wf) le da la altura (p, de 0 a
     * 1) y el grosor (wf); nace estirandose desde el suelo y se va adelgazando.
     */
    private final class Rayo implements Parte {
        private final float ancho;
        private final float nucleo;
        private Location base;
        private BlockDisplay funda;
        private BlockDisplay alma;
        private boolean oculto = true;
        private boolean fallo;

        Rayo(Location suelo, float ancho, float nucleo) {
            this.ancho = ancho;
            this.nucleo = nucleo;
            this.base = baseRayo(suelo);
        }

        void set(double p, double wf) {
            boolean nada = !(p > 0.002 && wf > 0.002);
            if (nada && oculto) return;
            if (funda == null && alma == null) {
                if (fallo) return;
                // Nace sin altura: el cliente lo ve estirarse desde el suelo.
                alma = parteRayo(base, Material.REDSTONE_BLOCK, columna((float) (nucleo * wf), 0.001f));
                funda = parteRayo(base, Material.RED_STAINED_GLASS, columna((float) (ancho * wf), 0.001f));
                if (funda == null && alma == null) fallo = true;
                else oculto = false;
                return;
            }
            oculto = nada;
            float alto = (float) (RAYO_ALTO * Math.max(p, 0.001));
            float af = nada ? 1e-4f : (float) (ancho * wf);
            float an = nada ? 1e-4f : (float) (nucleo * wf);
            Conde.mover(funda, columna(af, alto), 2);
            // El nucleo un pelo mas bajo: dos caras de arriba a la misma altura parpadean.
            Conde.mover(alma, columna(an, Math.max(0.001f, alto - 0.05f)), 2);
        }

        /** Lo lleva a otro punto del suelo deslizandose (la espiral del Eclipse). */
        void mover(Location suelo) {
            base = baseRayo(suelo);
            for (BlockDisplay d : new BlockDisplay[]{funda, alma}) {
                if (d == null || !d.isValid()) continue;
                try {
                    d.teleport(base);
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public void quitar() {
            fallo = true;
            oculto = true;
            for (BlockDisplay d : new BlockDisplay[]{funda, alma}) {
                if (d == null) continue;
                spawned.remove(d);
                Fx.safeRemove(d);
            }
            funda = null;
            alma = null;
        }
    }

    // ----------------------------------------------------------------- el nucleo

    /**
     * El nucleo de los circulos: un diamante de bloque de redstone que gira 6 grados
     * por tick y late, una jaula de cuatro losas de piedra negra pulida que le orbita
     * y tres esquirlas de cristal rojo. Ocho piezas.
     */
    private final class Nucleo {
        private final double k;
        private final Pz dia;
        private final Pz[] losas = new Pz[4];
        private final Pz[] esquirlas = new Pz[3];

        Nucleo(Escena e, Location ancla, double k) {
            this.k = k;
            dia = e.pz(ancla, Material.REDSTONE_BLOCK, 1, 1, 1);
            for (int i = 0; i < 4; i++) {
                losas[i] = e.pz(ancla, Material.POLISHED_BLACKSTONE, 0.5 * k, 0.08 * k, 0.08 * k);
            }
            for (int i = 0; i < 3; i++) {
                esquirlas[i] = e.pz(ancla, Material.RED_STAINED_GLASS, 0.12 * k, 0.12 * k, 0.12 * k);
            }
        }

        /**
         * @param y      altura del centro sobre el ancla
         * @param crece  de 0 a 1 al montarse
         * @param muere  de 1 a 0 al estallar el diamante
         * @param st     tick en que empieza a montarse la jaula
         * @param orbita radio de las esquirlas
         * @param rompe  de 0 a 1: la jaula y las esquirlas salen disparadas
         */
        void tick(int t, double y, double crece, double muere, double periodo, double st, double orbita,
                  double rompe) {
            double eb = eo(rompe);
            double lado = (0.475 + 0.075 * Math.sin(t / periodo * TAU)) * k;
            dia.put(0, y, 0, giroY(6 * D2R * t).mul(xyz(45 * D2R, 0, 45 * D2R)), lado * crece * muere);
            for (int i = 0; i < 4; i++) {
                Quaternionf jaula = new Quaternionf().rotateX(0.28f).rotateY((float) (-2 * D2R * t))
                        .rotateY((float) (i * Math.PI / 2));
                Vector3f p = new Quaternionf(jaula).transform(
                        new Vector3f((float) (0.55 * k + 2.5 * eb), (float) (1.2 * eb), 0f));
                double cs = eo(seg(t, st + 1 + i * 0.5, st + 3 + i * 0.5));
                losas[i].put(p.x, y + p.y, p.z,
                        new Quaternionf(jaula).mul(xyz(0, Math.PI / 2, (i % 2 == 1 ? 35 : -35) * D2R)),
                        cs * (1 - rompe));
            }
            for (int i = 0; i < 3; i++) {
                double an = 15 * D2R * t + i * TAU / 3;
                double r = orbita + 3.5 * eb;
                esquirlas[i].put(Math.cos(an) * r, y + 0.12 * Math.sin(t / 6.0 + i) + 1.4 * eb, Math.sin(an) * r,
                        xyz(t * 0.2, t * 0.3, 0), eo(seg(t, st + 2, st + 4)) * (1 - rompe));
            }
        }
    }

    // --------------------------------------------------------------- el sello

    private static final int SELLO_ESTALLA = 46;
    private static final int SELLO_FIN = 57;
    /** Del septimo en adelante, el sello va solo en particulas (presupuesto de entidades). */
    private static final int SELLO_DIBUJADOS = 6;
    /** Los tres primeros con el circulo grande (400 piezas); del cuarto al sexto, el ligero (96). */
    private static final int SELLO_GRIMORIO = 3;
    private static final double SELLO_RADIO = 2.2;

    /**
     * Un sello bajo un jugador: un circulo de block-display.com a radio 1,5 (conde-sello, 400
     * piezas, en los tres primeros; conde-circulo, 96, en el resto) creciendo pieza a pieza en
     * 40 ticks y girando, el nucleo (8) y el rayo (2). Al estallar, las piezas del circulo
     * salen disparadas en 12 ticks.
     */
    private final class Sello {
        final Location c;
        final boolean dibujado;
        private final Instancia circulo;
        private final Nucleo nucleo;
        private final Rayo rayo;

        Sello(Escena e, Location c, boolean dibujado, boolean grimorio) {
            this.c = c;
            this.dibujado = dibujado;
            circulo = dibujado ? e.modelo(grimorio ? MODELO_SELLO : MODELO_CIRCULO, Opciones.en(c).escala(1.5f).ticksCrecimiento(40)
                    .giroPorTick(0.105f).alturaExtra(0.03f), null) : null;
            nucleo = dibujado ? new Nucleo(e, c, 1) : null;
            rayo = dibujado ? e.rayo(c, 0.45f, 0.2f) : null;
        }

        boolean sinModelo() {
            return circulo == null;
        }

        void tick(int t) {
            if (!dibujado) return;
            if (circulo != null) {
                circulo.tick(t);
                if (t == SELLO_ESTALLA) circulo.desmontar(12, 2.4f, 1.9f, 0);
                if (t < SELLO_ESTALLA) llamas(circulo, t);
            }
            double cy = 0.4 + 0.8 * sm(seg(t, 36, 46)) + 0.6 * seg(t, 46, 49);
            nucleo.tick(t, cy, eo(seg(t, 4, 6)), 1 - seg(t, 46, 49), 10, 3, 0.8, seg(t, 46, 56));
            if (t >= SELLO_ESTALLA && t < SELLO_FIN) rayo.set(eo(seg(t, 46, 49)), 1 - sm(seg(t, 49, 57)));
            else rayo.set(0, 0);
        }
    }

    // ------------------------------------------------------------- el pentaculo

    private static final int PENTA_ESTALLA = 66;
    private static final int PENTA_FIN = 104;
    /** Radio de las cinco puntas (pilar, vela, runa y rayo): justo por fuera del circulo de radio 7. */
    private static final double PENTA_RV = 7.6;
    private static final double PENTA_RADIO_RAYO = 2.5;
    /** El tick en que los dos circulos salen en onda hacia fuera. */
    private static final int PENTA_ONDA = PENTA_ESTALLA + 4;
    /** Ticks que la copia de radio 5 va detras de la grande. */
    private static final int PENTA_RETRASO = 6;
    private static final String[] RUNAS_PENTACULO = {"ᛉ", "ᛟ", "ᚦ", "ᛝ", "ᛗ"};

    /**
     * El pentaculo: conde-sello a radio 7 (400, con sus calaveras y velas) y conde-circulo a
     * radio 5 con las piezas al doble de alto girando al reves (96), cinco puntas con pilar,
     * vela y runa (15), el nucleo a 1,3 (8) y cinco rayos (10). 529 entidades.
     */
    private final class Pentaculo {
        final Location[] pie = new Location[5];
        private final Instancia grande;
        private final Instancia copia;
        private final Pz[] pilar = new Pz[5];
        private final Pz[] vela = new Pz[5];
        private final Rn[] runa = new Rn[5];
        private final double[][] punta = new double[5][2];
        private final Rayo[] rayo = new Rayo[5];
        private final Nucleo nucleo;

        Pentaculo(Escena e, Location c) {
            // Sin escalaY: las calaveras saldrian estiradas.
            grande = e.modelo(MODELO_SELLO, Opciones.en(c).escala(7).ticksCrecimiento(60)
                    .giroPorTick(0.02f).alturaExtra(0.03f), null);
            copia = e.modelo(MODELO_CIRCULO, Opciones.en(c).escala(5).escalaY(2).ticksCrecimiento(60)
                    .giroPorTick(-0.03f).alturaExtra(0.05f), null);
            for (int i = 0; i < 5; i++) {
                double a = -Math.PI / 2 + i * TAU / 5;
                punta[i][0] = Math.cos(a) * PENTA_RV;
                punta[i][1] = Math.sin(a) * PENTA_RV;
                pie[i] = sinGiro(Fx.ground(c.clone().add(punta[i][0], 0, punta[i][1]), 4));
            }
            BlockData encendida = Material.RED_CANDLE.createBlockData();
            if (encendida instanceof Lightable lt) lt.setLit(true);
            for (int k = 0; k < 5; k++) {
                pilar[k] = e.pz(c, Material.POLISHED_BLACKSTONE, 0.4, 0.9, 0.4);
                vela[k] = e.pz(c, encendida.clone(), 1, 1, 1, 2);
                runa[k] = e.rn(c, RUNAS_PENTACULO[k], 1.2f);
                rayo[k] = e.rayo(pie[k], 0.45f, 0.2f);
            }
            nucleo = new Nucleo(e, c, 1.3);
        }

        void tick(int t) {
            // La onda va de dentro a fuera: 8 ticks por radio del modelo, 14 cada pieza.
            if (grande != null) {
                grande.tick(t);
                if (t == PENTA_ONDA) grande.desmontar(14, 4.5f, 1.6f, 8);
                if (t < PENTA_ONDA) llamas(grande, t);
            }
            if (copia != null) {
                copia.tick(t - PENTA_RETRASO);
                if (t == PENTA_ONDA) copia.desmontar(14, 3.5f, 1.6f, 8);
            }
            for (int k = 0; k < 5; k++) {
                double u = seg(t, 92 + k * 0.5, 102 + k * 0.5);
                double e = eo(u);
                double r = 1 + 0.35 * e;
                double x = punta[k][0] * r;
                double z = punta[k][1] * r;
                int pst = 2 + k / 2;
                pilar[k].put(x, 0.45 + 0.8 * e, z, xyz(u * 3, 0, u * 2), eo(seg(t, pst, pst + 2)) * (1 - u));
                // La vela, de pie encima del pilar: su bloque se apoya por abajo.
                double sv = 0.6 * eo(seg(t, 5, 7)) * (1 - u);
                vela[k].put(x, 0.9 + 0.9 * e + sv / 2, z, new Quaternionf(), sv);
                int gst = 5 + k / 3;
                double fy = 0.08 * Math.sin(t / 24.0 * TAU + k);
                runa[k].put(x, 1.6 + fy + 1.5 * e, z, eo(seg(t, gst, gst + 2)) * (1 - u));
            }
            double abre = sm(seg(t, 46, 66));
            nucleo.tick(t, 1.0 + 1.5 * abre, eo(seg(t, 4, 6)), 1 - seg(t, 66, 70), 8, 3,
                    0.8 * 1.3 + (1.6 - 0.8 * 1.3) * abre, seg(t, 66, 76));
            // Los rayos, uno cada dos ticks en sentido horario visto desde arriba.
            for (int k = 0; k < 5; k++) {
                int t0 = PENTA_ESTALLA + 2 * k;
                if (t >= t0 && t < t0 + 11) rayo[k].set(eo(seg(t, t0, t0 + 3)), 1 - sm(seg(t, t0 + 3, t0 + 11)));
                else rayo[k].set(0, 0);
            }
        }
    }

    // --------------------------------------------------------------- el eclipse

    private static final double ECLIPSE_RADIO = 9;
    private static final int ECLIPSE_ESTALLA = 60;
    private static final int ECLIPSE_FIN = 76;
    /** Ticks que la copia de radio 7 va detras de la grande. */
    private static final int ECLIPSE_RETRASO = 4;

    /**
     * El eclipse de radio 9: el circulo de block-display.com a radio 9 (96) y una copia a
     * radio 7 girando al reves (96), con sus runas; la luna de cristal rojo (24) a cinco
     * bloques con el disco negro (12) que la tapa, el nucleo a 1,3 (8), el rayo central
     * grueso y ocho finos en espiral (18). 254 entidades.
     */
    private final class Eclipse {
        private final Location c;
        private final Instancia grande;
        private final Instancia copia;
        private final Pz[] luna = new Pz[24];
        private final double[][] lunaP = new double[24][3];
        private final Pz[] negro = new Pz[12];
        private final double[][] negroP = new double[12][3];
        private final Quaternionf giroLuna;
        private final Nucleo nucleo;
        private final Rayo central;
        private final Rayo[] finos = new Rayo[8];

        Eclipse(Escena e, Location c, double yawLuna) {
            this.c = c;
            this.giroLuna = giroY(yawLuna);
            grande = e.modelo(MODELO_CIRCULO, Opciones.en(c).escala((float) ECLIPSE_RADIO).ticksCrecimiento(40)
                    .giroPorTick((float) (1.5 * D2R)).alturaExtra(0.03f), null);
            copia = e.modelo(MODELO_CIRCULO, Opciones.en(c).escala(7).ticksCrecimiento(40)
                    .giroPorTick((float) (-2.5 * D2R)).alturaExtra(0.05f), null);
            // La luna: una losa en el centro, ocho en radio 0,85 y quince en 1,55.
            int n = 0;
            lunaP[n++] = new double[]{0, 0, 0};
            for (int i = 0; i < 8; i++) {
                double a = i * TAU / 8;
                lunaP[n++] = new double[]{Math.cos(a) * 0.85, Math.sin(a) * 0.85, a};
            }
            for (int i = 0; i < 15; i++) {
                double a = i * TAU / 15;
                lunaP[n++] = new double[]{Math.cos(a) * 1.55, Math.sin(a) * 1.55, a};
            }
            for (int i = 0; i < 24; i++) luna[i] = e.pz(c, Material.RED_STAINED_GLASS, 0.5, 0.5, 0.06);
            // El disco negro: cuatro cuadros en el centro y ocho alrededor.
            double[][] cuadros = {{-0.43, -0.43}, {0.43, -0.43}, {-0.43, 0.43}, {0.43, 0.43}};
            for (int i = 0; i < 4; i++) {
                negroP[i] = new double[]{cuadros[i][0], cuadros[i][1], 0};
                negro[i] = e.pz(c, Material.BLACKSTONE, 0.85, 0.85, 0.2);
            }
            for (int i = 0; i < 8; i++) {
                double a = (i + 0.5) * TAU / 8;
                negroP[4 + i] = new double[]{Math.cos(a) * 1.3, Math.sin(a) * 1.3, a};
                negro[4 + i] = e.pz(c, Material.BLACKSTONE, 0.95, 0.95, 0.2);
            }
            nucleo = new Nucleo(e, c, 1.3);
            central = e.rayo(c, 0.8f, 0.35f);
            for (int j = 0; j < 8; j++) finos[j] = e.rayo(c, 0.3f, 0.12f);
        }

        void tick(int t) {
            // Al destello las piezas del suelo saltan hacia fuera en 10 ticks; las de fuera, un poco despues.
            if (grande != null) {
                grande.tick(t);
                if (t == ECLIPSE_ESTALLA) grande.desmontar(10, 3f, 2.2f, 3);
            }
            if (copia != null) {
                copia.tick(t - ECLIPSE_RETRASO);
                if (t == ECLIPSE_ESTALLA) copia.desmontar(10, 2.6f, 2f, 3);
            }
            // El disco negro entra de un lado y tapa la luna justo en el tick 60.
            double fuera = -3.7 * (1 - seg(t, 0, ECLIPSE_ESTALLA));
            for (int i = 0; i < 24; i++) trozoLuna(t, luna[i], lunaP[i], i, 2 + i / 6, 0);
            for (int i = 0; i < 12; i++) trozoLuna(t, negro[i], negroP[i], i, 3 + i / 4, fuera);
            nucleo.tick(t, 1.5, eo(seg(t, 4, 6)), 1 - seg(t, 66, 72), 10, 3, 0.8 * 1.3, seg(t, 60, 70));
            if (t >= 20 && t < 74) central.set(eo(seg(t, 20, 23)), 1 - sm(seg(t, 66, 74)));
            else central.set(0, 0);
            for (int j = 0; j < 8; j++) {
                int tj = 20 + 5 * j;
                if (t < tj || t >= 74) {
                    finos[j].set(0, 0);
                    continue;
                }
                double an = j * TAU / 8 + 3 * D2R * (t - 20);
                double r = 2 + 4 * sm(seg(t, tj, ECLIPSE_ESTALLA));
                finos[j].mover(Fx.ground(c.clone().add(Math.cos(an) * r, 0, Math.sin(an) * r), 3));
                finos[j].set(eo(seg(t, tj, tj + 3)), (1 + sm(seg(t, 60, 66))) * (1 - sm(seg(t, 66, 74))));
            }
        }

        /** Una pieza de la luna (o del disco negro, desplazado 'dx'): al estallar sale hacia fuera. */
        private void trozoLuna(int t, Pz p, double[] o, int i, int st, double dx) {
            double u = seg(t, 66 + (i % 3), 76 + (i % 3));
            double e = eo(u);
            double len = Math.hypot(o[0], o[1]);
            if (len == 0) len = 1;
            double ux = o[0] / len;
            double uy = o[1] / len;
            if (ux == 0) ux = 0.3 * (h1(i) - 0.5);
            if (uy == 0) uy = 0.6;
            Vector3f v = new Quaternionf(giroLuna).transform(new Vector3f((float) (dx + o[0] + ux * 2.5 * e),
                    (float) (o[1] + uy * 2.5 * e), (float) ((h1(i + 5) - 0.5) * 3 * e)));
            p.put(v.x, 5 + v.y, v.z, new Quaternionf(giroLuna).mul(xyz(u * 4, u * 3, o[2])),
                    eo(seg(t, st, st + 2)) * (1 - u));
        }
    }

    // --------------------------------------------------------- la bola de sangre

    private static final int BOLA_CARGA = 15;
    private static final int BOLA_VUELO_MAX = 40;
    private static final double BOLA_VELOCIDAD = 0.9;
    private static final double BOLA_GRAVEDAD = 0.02;

    /**
     * Una bola de Sangre Ardiente: el modelo conde-bola (55 bloques: esfera roja y negra
     * que gira sobre si misma y una cola de llamas) y una estela de seis brasas. Nace
     * entre las manos del Conde creciendo de 0 a su tamaño, vuela recta con un poco de
     * caida, con la cola hacia atras, y choca contra un bloque o al pasar a 1,2 de un
     * jugador. La grande (modelo a 0,55, 1,1 bloques) deja charco; la pequeña (la de la
     * rafaga, a 0,3) solo estalla.
     */
    private final class Bola {
        final double k;
        final boolean grande;
        final int tc;
        final int tl;
        final Supplier<Location> mira;
        /** El 'dano' de la habilidad al lanzarla, para el golpe del choque. */
        final double mult;
        private final Escena escena;
        /** El modelo; null hasta que empieza a cargar (o si no se pudo crear). */
        private Instancia bola;
        private boolean bolaPedida;
        private final Pz[] brasa = new Pz[6];
        private final int[] brasaT = {-99, -99, -99, -99, -99, -99};
        private final Pz[] esquirla;
        private final Charco charco;
        private Location pos;
        private Vector vel;
        private Vector rumbo = new Vector(1, 0, 0);
        private int pasos;
        private int tI = -1;
        private Location P;
        private Location G;
        private double hP;
        boolean hecho;

        Bola(Escena e, double k, boolean grande, int tc, int tl, Supplier<Location> mira) {
            this.k = k;
            this.grande = grande;
            this.tc = tc;
            this.tl = tl;
            this.mira = mira;
            // Se crea al lanzar la habilidad: el choque puede caer fuera de su 'cast'.
            this.mult = abilityDamage();
            this.escena = e;
            Location h = boss.getLocation();
            // Las brasas se quedan donde caen: teleport sin deslizamiento.
            for (int i = 0; i < 6; i++) brasa[i] = e.pz(h, Material.RED_CONCRETE.createBlockData(), 1, 1, 1, 0);
            esquirla = new Pz[grande ? 12 : 6];
            for (int i = 0; i < esquirla.length; i++) {
                esquirla[i] = e.pz(h, Material.REDSTONE_BLOCK, 0.12 * k, 0.12 * k, 0.12 * k);
            }
            charco = grande ? new Charco(e, h) : null;
        }

        void tick(int t) {
            if (hecho || t < tc) return;
            if (tI < 0 && t < tl) {
                cargar(t);
                return;
            }
            if (tI < 0) {
                volar(t);
                if (tI < 0) return;
            }
            estallar(t);
        }

        /** El punto entre sus manos: 1,4 de alto y 0,6 por delante, hacia donde apunta. */
        private Location mano(Location aim) {
            Location yo = boss.getLocation();
            Vector d = aim.toVector().subtract(yo.toVector()).setY(0);
            if (d.lengthSquared() < 0.01) d = yo.getDirection().setY(0);
            if (d.lengthSquared() < 0.01) d = new Vector(1, 0, 0);
            d.normalize();
            rumbo = d.clone();
            return yo.add(d.multiply(0.6)).add(0, 1.4, 0);
        }

        private void cargar(int t) {
            Location aim = mira.get();
            face(aim);
            Location h = mano(aim);
            pos = h;
            dibujar(t, h, eo(seg(t, tc, tl)), rumbo);
            if (t == tc) soundAt(h, "block.fire.ambient", 1.6f, 0.6f);
            if (t % 2 == 0) {
                for (int i = 0; i < 3; i++) {
                    double a = random.nextDouble() * TAU;
                    double r = 0.9 * k;
                    Location p = h.clone().add(Math.cos(a) * r, (random.nextDouble() - 0.5) * r, Math.sin(a) * r);
                    Vector v = h.toVector().subtract(p.toVector()).multiply(0.18);
                    Compat.spawn(world(), Compat.SOUL_FIRE_FLAME, p, 0, v.getX(), v.getY(), v.getZ(), 1);
                }
                Compat.spawn(world(), Compat.DUST, h, 1, 0.1 * k, 0.1 * k, 0.1 * k, 0, Compat.dust(SANGRE, 1.0f));
            }
        }

        private void volar(int t) {
            if (vel == null) {
                Location aim = mira.get();
                face(aim);
                Location h = mano(aim);
                pos = h;
                Vector v = aim.toVector().subtract(h.toVector());
                if (v.lengthSquared() < 0.01) v = rumbo.clone();
                vel = v.normalize().multiply(BOLA_VELOCIDAD);
                soundAt(h, "entity.blaze.shoot", 1.4f, grande ? 0.5f : 0.8f);
            } else {
                Location sig = pos.clone().add(vel);
                vel.setY(vel.getY() - BOLA_GRAVEDAD);
                pasos++;
                boolean bloque = false;
                try {
                    bloque = sig.getBlock().getType().isSolid();
                } catch (Throwable ignored) {
                }
                boolean jugador = false;
                if (!bloque) {
                    for (Player p : Fx.playersNear(sig, 3)) {
                        if (p.getLocation().add(0, 0.9, 0).distance(sig) <= 1.2) {
                            jugador = true;
                            break;
                        }
                    }
                }
                if (bloque || jugador || pasos >= BOLA_VUELO_MAX) {
                    chocar(bloque ? pos : sig);
                    tI = t;
                    return;
                }
                pos = sig;
            }
            Vector plano = vel.clone().setY(0);
            if (plano.lengthSquared() > 1e-4) rumbo = plano.normalize();
            dibujar(t, pos, 1, vel);
            // La estela: cada tick una brasa nueva donde esta la bola; dura seis.
            int j = Math.floorMod(t - tl, 6);
            brasa[j].ancla(pos);
            brasaT[j] = t;
            brasas(t);
            Compat.spawn(world(), Compat.SOUL_FIRE_FLAME, pos, 1, 0.12 * k, 0.12 * k, 0.12 * k, 0.01);
            Compat.spawn(world(), Compat.DUST, pos, 2, 0.2 * k, 0.2 * k, 0.2 * k, 0, Compat.dust(SANGRE, 1.2f));
        }

        /**
         * La bola en 'at' (su centro), a tamaño g (0 a 1) y con la cola (el +Z del modelo)
         * hacia atras de 'hacia'. El modelo nace la primera vez que se dibuja.
         */
        private void dibujar(int t, Location at, double g, Vector hacia) {
            Location mira = new Location(null, 0, 0, 0);
            Vector atras = hacia.clone().multiply(-1);
            if (atras.lengthSquared() < 1e-6) atras = rumbo.clone().multiply(-1);
            mira.setDirection(atras);
            float yaw = mira.getYaw();
            float pitch = mira.getPitch();
            float tam = (float) Math.max(0, Math.min(1, g));
            if (!bolaPedida) {
                bolaPedida = true;
                bola = escena.modelo(MODELO_BOLA, Opciones.en(at).escala(grande ? 0.55f : 0.3f).animacion(true),
                        b -> {
                            b.girar(yaw, pitch);
                            b.escalarTodo(tam);
                        });
            }
            if (bola == null) return;
            bola.mover(at);
            bola.girar(yaw, pitch);
            bola.escalarTodo(tam);
            bola.tick(t);
        }

        private void brasas(int t) {
            for (int j = 0; j < 6; j++) {
                int s = brasaT[j];
                int edad = t - s;
                if (s < 0 || edad >= 6) brasa[j].put(0, 0, 0, new Quaternionf(), 0);
                else brasa[j].put(0, 0, 0, xyz(s, s * 0.7, 0), (0.15 - 0.1 * edad / 6.0) * k);
            }
        }

        private void chocar(Location en) {
            P = en.clone();
            G = sinGiro(Fx.ground(P, 8));
            hP = Math.max(0.06, P.getY() - G.getY());
            retirarModelo(bola);
            bola = null;
            for (Pz q : esquirla) q.ancla(G);
            if (charco != null) charco.ancla(G);

            Location l = G.clone().add(0, 0.6, 0);
            Compat.spawn(world(), Compat.FLASH, l, 1, 0, 0, 0, 0, C_SANGRE);
            Compat.spawn(world(), Compat.DUST, l, grande ? 40 : 14, 0.8 * k, 0.4 * k, 0.8 * k, 0, Compat.dust(SANGRE, 1.6f));
            Compat.spawn(world(), Compat.LARGE_SMOKE, l, grande ? 10 : 4, 0.5, 0.3, 0.5, 0.02);
            soundAt(l, "entity.generic.explode", grande ? 1.4f : 0.9f, grande ? 0.7f : 1.1f);
            if (grande) soundAt(l, "block.fire.extinguish", 1.2f, 0.6f);

            double radio = grande ? 3.5 : 2.0;
            double dano = grande ? 22 : 10;
            for (Player p : Fx.playersNear(G, radio + 2)) {
                if (plano(p.getLocation(), G) > radio) continue;
                double dy = p.getLocation().getY() - G.getY();
                if (dy < -1.5 || dy > 3) continue;
                hit(p, dano * power(), mult);
                push(p, fuera(p.getLocation(), G).multiply(grande ? 0.6 : 0.35).setY(0.25));
            }
        }

        private void estallar(int t) {
            int tau = t - tI;
            brasas(t);
            int n = esquirla.length;
            int vida = grande ? 22 : 12;
            for (int i = 0; i < n; i++) {
                double phi = i * TAU / n + 0.3 * h1(i);
                double vh = (0.14 + 0.1 * h1(i + 7)) * (grande ? 1 : 0.7);
                double vy = 0.28 + 0.14 * h1(i + 13);
                double gr = 0.05;
                double tL = (vy + Math.sqrt(vy * vy + 2 * gr * Math.max(0, hP - 0.06))) / gr;
                double te = Math.min(tau, tL);
                esquirla[i].put(Math.cos(phi) * vh * te, Math.max(0.06, hP + vy * te - 0.5 * gr * te * te),
                        Math.sin(phi) * vh * te, xyz(te * 0.6, te * 0.4, 0), 1 - seg(tau, vida - 6, vida));
            }
            if (charco != null) {
                charco.tick(tau);
                if (tau >= 10 && tau <= 80 && tau % 10 == 0) {
                    for (Player p : Fx.playersNear(G, CHARCO_RADIO + 2)) {
                        if (plano(p.getLocation(), G) > CHARCO_RADIO) continue;
                        double dy = p.getLocation().getY() - G.getY();
                        if (dy < -0.5 || dy > 2) continue;
                        hit(p, 3 * power());
                        try {
                            p.setFireTicks(Math.max(p.getFireTicks(), 40));
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            if (tau >= (grande ? 100 : 14)) hecho = true;
        }
    }

    private static final double CHARCO_RADIO = 2.4;

    /**
     * El charco de la gran bola: un manchon de verruga del nether de borde irregular
     * (14), grietas de redstone (6), placas de magma que parpadean (3), llamas de
     * cristal rojo que cambian de alto y de sitio (10) y brasas que saltan (6). Quema
     * 80 ticks y se apaga por partes: llamas, grietas y el manchon encogiendose.
     */
    private final class Charco {
        private final Pz[] mancha = new Pz[14];
        private final double[][] manchaP = new double[14][4];
        private final Pz[] grieta = new Pz[6];
        private final double[][] grietaP = new double[6][3];
        private final Pz[] magma = new Pz[3];
        private final double[][] magmaP = new double[3][3];
        private final Pz[] llama = new Pz[10];
        private final int[] llamaDur = new int[10];
        private final Pz[] chispa = new Pz[6];
        private final int[] chispaCiclo = {-1, -1, -1, -1, -1, -1};
        private Location ancla;

        Charco(Escena e, Location a) {
            ancla = sinGiro(a);
            for (int i = 0; i < 14; i++) {
                double sz = 0.5 + 0.4 * h1(i + 40);
                double r = 1.65 * Math.sqrt(h1(i + 50));
                double an = i * 2.4 + h1(i + 60);
                mancha[i] = e.pz(a, Material.NETHER_WART_BLOCK, sz, 0.04, sz * (0.8 + 0.4 * h1(i + 65)));
                manchaP[i] = new double[]{Math.cos(an) * r, Math.sin(an) * r, h1(i + 70) * Math.PI, 0.02 + 0.0012 * i};
            }
            for (int i = 0; i < 6; i++) {
                double ac = i * TAU / 6 + (h1(i + 80) - 0.5) * 0.6;
                double rc = 0.35 + 0.75 * h1(i + 90);
                grieta[i] = e.pz(a, Material.REDSTONE_BLOCK, 0.7, 0.05, 0.12);
                grietaP[i] = new double[]{Math.cos(ac) * rc, Math.sin(ac) * rc, -ac};
            }
            for (int i = 0; i < 3; i++) {
                double am = i * 2.1 + 0.5;
                double rm = 0.5 + 0.8 * h1(i + 110);
                magma[i] = e.pz(a, Material.MAGMA_BLOCK, 0.5, 0.05, 0.5);
                magmaP[i] = new double[]{Math.cos(am) * rm, Math.sin(am) * rm, h1(i + 115) * Math.PI};
            }
            for (int i = 0; i < 10; i++) {
                llama[i] = e.pz(a, Material.RED_STAINED_GLASS, 0.14, 0.45, 0.05);
                llamaDur[i] = 3 + (int) Math.floor(h1(i + 100) * 4);
            }
            for (int i = 0; i < 6; i++) chispa[i] = e.pz(a, Material.RED_CONCRETE, 0.08, 0.08, 0.08);
        }

        void ancla(Location a) {
            ancla = sinGiro(a);
            for (Pz p : mancha) p.ancla(a);
            for (Pz p : grieta) p.ancla(a);
            for (Pz p : magma) p.ancla(a);
            for (Pz p : llama) p.ancla(a);
            for (Pz p : chispa) p.ancla(a);
        }

        void tick(int tau) {
            boolean on = tau >= 0 && tau < 100;
            double q = 1 - sm(seg(tau, 88, 100));
            double finLlamas = 1 - sm(seg(tau, 80, 86));
            double finGrietas = 1 - sm(seg(tau, 84, 90));
            for (int i = 0; i < 14; i++) {
                double[] m = manchaP[i];
                int st = i / 5;
                mancha[i].put(m[0] * q, m[3], m[1] * q, giroY(m[2]), on ? eo(seg(tau, st, st + 2)) * q : 0);
            }
            for (int i = 0; i < 6; i++) {
                double[] g = grietaP[i];
                int st = 2 + i / 3;
                grieta[i].put(g[0] * q, 0.075, g[1] * q, giroY(g[2]), on ? eo(seg(tau, st, st + 2)) * finGrietas * q : 0);
            }
            for (int i = 0; i < 3; i++) {
                double[] g = magmaP[i];
                double parpadeo = ((tau + i) / 4) % 2 == 1 ? 1.1 : 0.9;
                magma[i].put(g[0] * q, 0.08, g[1] * q, giroY(g[2]), on ? eo(seg(tau, 3, 5)) * q * parpadeo : 0);
            }
            for (int i = 0; i < 10; i++) {
                int sale = 3 + i % 3;
                if (!on || tau < sale) {
                    llama[i].put(0, 0, 0, new Quaternionf(), 0);
                    continue;
                }
                int tt = tau - sale;
                int dur = llamaDur[i];
                int c = tt / dur;
                double u = (tt % dur) / (double) dur;
                double alto = lerp(valorLlama(i, c), valorLlama(i, c + 1), sm(u));
                double a = i * 2.4 + h1(i + 130) * 0.8;
                double r = 0.3 + 1.4 * Math.sqrt(h1(i + 140));
                double jx = (h1(i * 3 + c) - 0.5) * 0.25;
                double jz = (h1(i * 5 + c) - 0.5) * 0.25;
                double kk = eo(seg(tt, 0, 2)) * finLlamas;
                if (kk < 0.003) {
                    llama[i].put(0, 0, 0, new Quaternionf(), 0);
                    continue;
                }
                llama[i].put(Math.cos(a) * r + jx, 0.06 + 0.225 * alto * kk + 0.12 * u * kk, Math.sin(a) * r + jz,
                        giroY(h1(i + c * 3.3) * Math.PI), kk, alto * kk, kk, false);
            }
            for (int i = 0; i < 6; i++) {
                if (!on || tau < 5 || tau >= 80) {
                    chispa[i].put(0, 0, 0, new Quaternionf(), 0);
                    continue;
                }
                int tt = tau - 5 + i * 3;
                int per = 9 + (i % 3) * 2;
                int c = tt / per;
                double u = (tt % per) / (double) per;
                double a = h1(i * 7 + c) * TAU;
                double r = 1.6 * Math.sqrt(h1(i * 11 + c));
                double d = h1(i + c * 2) * TAU;
                boolean salto = c != chispaCiclo[i];
                chispaCiclo[i] = c;
                chispa[i].put(Math.cos(a) * r + Math.cos(d) * 0.3 * u, 0.08 + 0.6 * 4 * u * (1 - u),
                        Math.sin(a) * r + Math.sin(d) * 0.3 * u, xyz(u * 4, u * 3, 0), 1, 1, 1, salto);
            }
            if (on && tau < 80 && tau % 3 == 0) {
                double a = random.nextDouble() * TAU;
                double r = random.nextDouble() * 2.0;
                Location p = ancla.clone().add(Math.cos(a) * r, 0.15, Math.sin(a) * r);
                Compat.spawn(world(), Compat.DUST, p, 1, 0.05, 0.05, 0.05, 0, Compat.dust(SANGRE, 1.0f));
                if (tau % 9 == 0) Compat.spawn(world(), Compat.SMOKE, p, 1, 0.05, 0.05, 0.05, 0.01);
            }
        }

        private double valorLlama(int i, int n) {
            return 0.55 + 0.75 * h1(i * 13 + n * 7.1);
        }
    }

    /** Una columna de 'ancho' x 'alto' centrada en su punto, creciendo hacia arriba. */
    private static Transformation columna(float ancho, float alto) {
        return new Transformation(new Vector3f(-ancho / 2f, 0f, -ancho / 2f),
                new Quaternionf(), new Vector3f(ancho, alto, ancho), new Quaternionf());
    }

    /** La base de un rayo: un pelo bajo el suelo, para que su cara de abajo no parpadee con el. */
    private Location baseRayo(Location suelo) {
        Location b = new Location(world(), suelo.getX(), suelo.getY() - 0.05, suelo.getZ());
        b.setYaw(0);
        b.setPitch(0);
        return b;
    }

    private BlockDisplay parteRayo(Location base, Material mat, Transformation tr) {
        try {
            BlockDisplay d = world().spawn(base, BlockDisplay.class, e -> {
                e.setBlock(mat.createBlockData());
                e.setBrightness(new Display.Brightness(15, 15));
                e.setViewRange(4.0f);
                e.setShadowRadius(0f);
                e.setPersistent(false);
                e.setTeleportDuration(2);
                e.setInterpolationDelay(0);
                e.setInterpolationDuration(2);
                // Con 80 de alto, el cliente lo recortaria en cuanto la base saliera de pantalla.
                e.setDisplayHeight(90f);
                e.setDisplayWidth(3f);
                e.setTransformation(tr);
            });
            Tags.markTemporary(d);
            track(d);
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void mover(Display d, Transformation t, int ticks) {
        if (d == null || !d.isValid()) return;
        try {
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformation(t);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ utilidades

    /** Le devuelve la hora de siempre a todo el que tenga la noche del Conde puesta. */
    private void devolverHora() {
        for (UUID id : new ArrayList<>(noche)) {
            Player p = plugin.getServer().getPlayer(id);
            if (p == null) continue;
            try {
                p.resetPlayerTime();
            } catch (Throwable ignored) {
            }
        }
        noche.clear();
    }

    /** Cura vida "de diseno": se pasa a vida de la entidad con el mismo reescalado. */
    private void curar(double vida) {
        if (!alive() || vida <= 0) return;
        boss.setHealth(Math.min(topeDeFase(), boss.getHealth() + vida * damageScale()));
    }

    /**
     * Hasta donde puede curarse sin salirse de la fase en curso.
     *
     * Las barras (PhaseBars.update) se pintan solo con la fraccion de vida: si el
     * Conde se curaba por encima del umbral, en fase II volvia a enseñar la barra de
     * la fase I (y en blanco, del destello del cambio). canChangePhase impide que la
     * PELEA retroceda, pero no las barras; esto impide que retroceda la vida.
     */
    private double topeDeFase() {
        double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
        int fases = Math.max(1, phaseCount());
        double techo = (fases - phase() + 1) / (double) fases;
        if (phase() > 1) techo -= 0.002;
        return Math.max(boss.getHealth(), max * Fx.clamp(techo, 0, 1));
    }

    /** Glow.clear saca la entidad del equipo, pero el brillo blanco sigue: fuera los dos. */
    private static void quitarBrillo(LivingEntity e) {
        Glow.clear(e);
        try {
            e.setGlowing(false);
        } catch (Throwable ignored) {
        }
    }

    private static double plano(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Direccion horizontal de c hacia a, para los empujes hacia fuera. */
    private static Vector fuera(Location a, Location c) {
        Vector v = a.toVector().subtract(c.toVector()).setY(0);
        if (v.lengthSquared() < 0.01) v = new Vector(1, 0, 0);
        return v.normalize();
    }

    private static Particle.DustTransition transicion(float size) {
        return new Particle.DustTransition(C_SANGRE, C_NEGRO, size);
    }

    /** ENTITY_EFFECT pide el color con alfa. */
    private static Color rojo() {
        return Color.fromARGB(255, 0xB3, 0x12, 0x2E);
    }
}
