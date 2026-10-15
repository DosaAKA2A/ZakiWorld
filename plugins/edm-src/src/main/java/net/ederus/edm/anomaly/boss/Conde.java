package net.ederus.edm.anomaly.boss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.core.ActiveAnomaly;
import net.ederus.edm.anomaly.core.Disguises;
import net.ederus.edm.anomaly.core.Glow;
import net.ederus.edm.anomaly.core.Stop;
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
import org.bukkit.entity.Player;
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
import java.util.Set;
import java.util.UUID;
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
        Location spot = arena.clone();

        boss = world().spawn(spot, Zombie.class, z -> {
            z.setAdult();
            z.setPersistent(true);
            z.setRemoveWhenFarAway(false);
            z.setCanPickupItems(false);
            z.setShouldBurnInDay(false);
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

        Compat.setAttribute(boss, "max_health", 20);
        Compat.setAttribute(boss, "attack_damage", 10);
        Compat.setAttribute(boss, "armor", 10);
        Compat.setAttribute(boss, "knockback_resistance", 1.0);
        Compat.setAttribute(boss, "follow_range", 64);
        Compat.setAttribute(boss, "movement_speed", 0.30);
        applyHealth(plugin.registry().health(plugin.registry().get(ID)));
        boss.setMaximumNoDamageTicks(8);
        Tags.markBoss(boss, ID);
        glowBody(NamedTextColor.DARK_RED);

        // Su escucha propia: colmillos sin dano vanilla y la hora de vuelta a quien se va.
        plugin.getServer().getPluginManager().registerEvents(escucha, net.ederus.edm.Module.dueno(plugin));

        for (Player p : Fx.viewersNear(spot, 90)) {
            p.showTitle(Title.title(
                    Component.text("✦ ANOMALÍA ✦", ACCENT, TextDecoration.BOLD),
                    Component.text("CONDE  ·  el señor de los murciélagos", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1800), Duration.ofMillis(600))));
        }
        Location l = spot.clone().add(0, 1, 0);
        Compat.spawn(world(), Compat.LARGE_SMOKE, l, 40, 0.6, 0.9, 0.6, 0.03);
        Compat.spawn(world(), Compat.FLASH, l, 1, 0, 0, 0, 0, C_SANGRE);
        soundAt(spot, "entity.wither.spawn", 1.2f, 1.4f);
        soundAt(spot, "entity.bat.takeoff", 2.0f, 0.6f);
        soundAt(spot, "block.bell.use", 1.6f, 0.5f);
        dispersar(l, 14, 30);
        encenderVelas();
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
        if (boss == null) return;
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
            Compat.setAttribute(boss, "attack_damage", 13);
            titleNear(Component.text("FASE II", ACCENT, TextDecoration.BOLD),
                    Component.text("Tiene sed", NamedTextColor.GRAY));
        }
        if (to == 3) {
            damageBonus = 1.2;
            Compat.setAttribute(boss, "movement_speed", 0.36);
            Compat.setAttribute(boss, "attack_damage", 16);
            titleNear(Component.text("FASE III", ACCENT, TextDecoration.BOLD),
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
        // Las velas estan en 'spawned': las quita super.cleanup(). Aqui solo la lista.
        velas.clear();
        super.cleanup();
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
        soundAt(origen, "entity.evoker.prepare_attack", 1.8f, 0.7f);
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
                soundAt(origen, "entity.evoker.cast_spell", 1.6f, 0.6f);
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
                            hit(v, 8 * power());
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
        soundAt(c, "entity.bat.takeoff", 2.0f, 0.6f);
        soundAt(c, "entity.evoker.prepare_summon", 1.6f, 0.7f);
        announce(Component.text("Enjambre. Mata los murciélagos antes de que bajen."));
        bandada(bats, null, () -> boss.getLocation(), 40, 120, 5 * power(), 0.5);
    }

    /** 3. Velo de la Noche: un pulso que deja a oscuras a todo el que este cerca. */
    public void veloDeLaNoche() {
        if (!alive()) return;
        final Location c = Fx.ground(boss.getLocation(), 4);
        soundAt(c, "entity.elder_guardian.curse", 1.6f, 0.8f);
        soundAt(c, "entity.evoker.prepare_wololo", 1.8f, 0.6f);
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
        List<Player> objetivos = targets(20);
        if (objetivos.isEmpty()) return;
        List<Circulo> sellos = new ArrayList<>();
        for (int i = 0; i < objetivos.size(); i++) {
            Player p = objetivos.get(i);
            // Hasta doce sellos con circulo completo; del trece en adelante, el ligero.
            sellos.add(circulo(Fx.ground(p.getLocation(), 6), 1.5, 36, false, i >= 12));
            p.sendActionBar(Component.text("Tienes un sello rojo debajo. Sal de él.", ACCENT));
        }
        final Set<UUID> golpeados = new HashSet<>();
        soundAt(loc(), "entity.evoker.cast_spell", 1.8f, 0.6f);
        announce(Component.text("Sello carmesí. Sal del círculo rojo."));

        animate(30, t -> {
            if (!alive()) throw Stop.now();
            if (t % 4 != 0) return;
            for (Circulo s : sellos) {
                Compat.spawn(world(), Compat.WITCH, s.centro.clone().add(0, 0.3, 0), 2, 0.6, 0.1, 0.6, 0);
            }
            if (t % 10 == 0) soundAt(loc(), "block.respawn_anchor.deplete", 1.0f, 1.4f + t * 0.01f);
        }, () -> {
            for (Circulo s : sellos) {
                s.quitar();
                if (!alive()) continue;
                Location c = s.centro.clone().add(0, 0.5, 0);
                rayoRojo(s.centro, 26f, 14, 3.0f);
                Compat.spawn(world(), Compat.FLASH, c, 1, 0, 0, 0, 0, C_SANGRE);
                Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, c, 40, 0.9, 0.5, 0.9, 0, transicion(1.6f));
                Compat.spawn(world(), Compat.LARGE_SMOKE, c, 10, 0.6, 0.3, 0.6, 0.03);
                soundAt(c, "entity.generic.explode", 1.3f, 0.6f);
                for (Player v : Fx.playersNear(s.centro, 3.5)) {
                    if (plano(v.getLocation(), s.centro) > 1.7) continue;
                    if (Math.abs(v.getLocation().getY() - s.centro.getY()) > 2.5) continue;
                    if (!golpeados.add(v.getUniqueId())) continue;
                    hit(v, 10 * power());
                    push(v, fuera(v.getLocation(), s.centro).multiply(0.55).setY(0.3));
                }
            }
        });
    }

    /* El mordisco en abanico: 120 grados delante de el, hasta 3,5 bloques. */
    private static final double MORDISCO_RADIO = 3.5;
    private static final double MORDISCO_MEDIO_ANGULO = Math.toRadians(60);
    /** Como mucho se cura por tres mordidos, por grande que sea el grupo. */
    private static final int MORDISCO_TOPE_CURA = 3;

    /**
     * 5. Mordisco: una embestida corta hacia el mas cercano y, al llegar, un mordisco
     * en abanico de 120 grados y 3,5 bloques que muerde a TODOS los que alcance. Se
     * cura con lo que le saca a cada uno, hasta un tope. El dano baja un 20 % (9 a
     * 7,2) porque ya no es a uno solo.
     *
     * El aviso es el abanico dibujado en el suelo, donde va a acabar, apuntando a la
     * presa: quien este dentro, que se aparte.
     */
    public void mordisco() {
        if (!alive() || formaBat != null) return;
        final Player v = Fx.nearest(boss.getLocation(), 14);
        if (v == null) return;
        soundAt(loc(), "entity.evoker.prepare_attack", 1.6f, 1.2f);
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
                int mordidos = 0;
                for (Player p : Fx.playersNear(yo, MORDISCO_RADIO + 0.5)) {
                    if (!enAbanico(yo, frente, p.getLocation())) continue;
                    if (Math.abs(p.getLocation().getY() - yo.getY()) > 2.5) continue;
                    double dano = 7.2 * power();
                    hit(p, dano);
                    if (mordidos++ < MORDISCO_TOPE_CURA) curar(dano * 0.30);
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
                    hit(p, 8 * power());
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
                    hit(v, 6 * power());
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
     * 8. Pentaculo: un circulo enorme; de las cinco puntas de la estrella sube un rayo
     * rojo (los de los Rayos del Alba, en carmesi) durante tres segundos. Quien este en
     * un rayo encaja 11 una vez por rayo; se mide en el plano, como una columna.
     */
    public void pentaculo() {
        if (!alive()) return;
        final Location c = Fx.ground(boss.getLocation(), 4);
        final Circulo k = circulo(c, 7, 84, true, false);
        soundAt(c, "block.bell.use", 1.8f, 0.5f);
        soundAt(c, "entity.illusioner.cast_spell", 1.6f, 0.7f);
        announce(Component.text("Pentáculo. No te quedes en las puntas de la estrella."));

        final List<Rayo> rayos = new ArrayList<>();
        final List<Set<UUID>> tocados = new ArrayList<>();
        animate(80, t -> {
            if (!alive()) throw Stop.now();
            if (t < 20) {
                if (t % 2 == 0) {
                    for (Location v : k.vertices) {
                        Fx.ring(v.clone().add(0, 0.12, 0), 1.3, 14, p ->
                                Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, p, 1, 0, 0, 0, 0, transicion(1.2f)));
                        Compat.spawn(world(), Compat.SOUL_FIRE_FLAME, v.clone().add(0, 0.2, 0), 1, 0.3, 0, 0.3, 0.02);
                    }
                }
                return;
            }
            if (t == 20) {
                for (Location v : k.vertices) {
                    // Sin plazo propio: lo apaga el final de la habilidad, a la vez que el circulo.
                    rayos.add(rayoRojo(v, 40f, 0, 2.6f));
                    tocados.add(new HashSet<>());
                    Compat.spawn(world(), Compat.FLASH, v.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0, C_SANGRE);
                }
                soundAt(c, "block.bell.use", 1.8f, 0.5f);
                soundAt(c, "entity.wither.shoot", 1.2f, 0.5f);
            }
            if (t % 2 != 0) return;
            for (int i = 0; i < k.vertices.size(); i++) {
                Location v = k.vertices.get(i);
                // Chispas donde el rayo corta el suelo, como en el Alba.
                Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, v.clone().add(0, 0.3, 0), 2,
                        0.35, 0.1, 0.35, 0, transicion(1.0f));
                for (Player p : Fx.playersNear(v, 41)) {
                    if (plano(p.getLocation(), v) > 1.35) continue;
                    double dy = p.getLocation().getY() - v.getY();
                    if (dy < -1 || dy > 40) continue;
                    if (!tocados.get(i).add(p.getUniqueId())) continue;
                    hit(p, 11 * power());
                    push(p, fuera(p.getLocation(), v).multiply(0.6).setY(0.35));
                }
            }
        }, () -> {
            for (Rayo r : rayos) r.quitar();
            k.quitar();
        });
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
        titleNear(Component.text("MEDIANOCHE", ACCENT, TextDecoration.BOLD),
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

    /** 11. Ataudes: cuatro ataudes salen del suelo, se abren y sueltan murcielagos. */
    public void ataudes() {
        if (!alive()) return;
        final Location c = boss.getLocation();
        announce(Component.text("Ataúdes. Mata lo que salga de ellos."));
        soundAt(c, "entity.evoker.prepare_summon", 1.6f, 0.6f);
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
            if (t == 120) for (Ataud at : ataudes) at.colocar(-0.9f, 1.9f, 20);
        }, () -> {
            for (Ataud at : ataudes) at.quitar();
        });
    }

    /**
     * Un ataud: caja de madera oscura, tapa negra con bisagra en un lado largo y un
     * forro rojo que se ve al abrir. Tres displays que se mueven con interpolacion.
     */
    private final class Ataud {
        final Location pos;
        final Quaternionf q;
        BlockDisplay cuerpo;
        BlockDisplay forro;
        BlockDisplay tapa;

        Ataud(Location pos, float giro) {
            this.pos = pos;
            this.q = new Quaternionf().rotateY(giro);
            this.cuerpo = parte(Material.DARK_OAK_PLANKS, cuerpoT(-0.75f));
            this.forro = parte(Material.RED_CONCRETE, forroT(-0.75f));
            this.tapa = parte(Material.BLACK_CONCRETE, tapaT(-0.75f, 0f));
        }

        private Transformation cuerpoT(float y) {
            return new Transformation(new Quaternionf(q).transform(new Vector3f(-0.45f, y, -1.0f)),
                    new Quaternionf(q), new Vector3f(0.9f, 0.6f, 2.0f), new Quaternionf());
        }

        private Transformation forroT(float y) {
            return new Transformation(new Quaternionf(q).transform(new Vector3f(-0.38f, y + 0.6f, -0.93f)),
                    new Quaternionf(q), new Vector3f(0.76f, 0.01f, 1.86f), new Quaternionf());
        }

        /* La bisagra es la arista local x=0 de la tapa: girar en Z la levanta por el
         * otro lado, como una tapa de verdad. */
        private Transformation tapaT(float y, float angulo) {
            return new Transformation(new Quaternionf(q).transform(new Vector3f(-0.475f, y + 0.6f, -1.025f)),
                    new Quaternionf(q).rotateZ(angulo), new Vector3f(0.95f, 0.1f, 2.05f), new Quaternionf());
        }

        private BlockDisplay parte(Material mat, Transformation t0) {
            try {
                BlockDisplay d = world().spawn(pos, BlockDisplay.class, e -> {
                    e.setBlock(mat.createBlockData());
                    e.setBrightness(new Display.Brightness(12, 12));
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

        void colocar(float y, float angulo, int ticks) {
            mover(cuerpo, cuerpoT(y), ticks);
            mover(forro, forroT(y), ticks);
            mover(tapa, tapaT(y, angulo), ticks);
        }

        void quitar() {
            for (BlockDisplay d : new BlockDisplay[]{cuerpo, forro, tapa}) {
                if (d == null) continue;
                spawned.remove(d);
                Fx.safeRemove(d);
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

    /** 13. Eclipse Carmesi: el gran circulo bajo todo el campo. Quien no salga, lo paga. */
    public void eclipseCarmesi() {
        if (!alive()) return;
        final Location c = Fx.ground(boss.getLocation(), 4);
        final double r = 9;
        final Circulo k = circulo(c, r, 46, true, false);
        k.girarEstrella = true;
        Compat.apply(boss, "slowness", 45, 4);
        titleNear(Component.text("ECLIPSE CARMESÍ", ACCENT, TextDecoration.BOLD),
                Component.text("Sal del círculo rojo", NamedTextColor.GRAY));
        soundAt(c, "entity.wither.ambient", 1.6f, 0.5f);
        soundAt(c, "block.trial_spawner.ominous_activate", 1.4f, 0.6f);
        soundAt(c, "block.beacon.activate", 1.4f, 0.6f);

        /* Los rayos, como en los Rayos del Alba: uno gordo en el centro y ocho que se
         * abren en espiral desde el centro hasta el borde del circulo durante la carga.
         * Llegan al borde justo al estallar: se ve hasta donde llega el eclipse. Son
         * aviso, no pegan: el golpe es el estallido. */
        final Rayo central = rayoRojo(c, 48f, 0, 4.2f);
        final int n = 8;
        final List<Rayo> espiral = new ArrayList<>();
        final double giro0 = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < n; i++) {
            double a = giro0 + Math.PI * 2 * i / n;
            espiral.add(rayoRojo(c.clone().add(Math.cos(a) * 0.8, 0, Math.sin(a) * 0.8), 40f, 0, 1.6f));
        }

        animate(40, t -> {
            if (!alive()) throw Stop.now();
            double re = 0.8 + (r - 0.8) * Math.min(1.0, t / 38.0);
            double giro = giro0 + t * 0.045;
            for (int i = 0; i < espiral.size(); i++) {
                double a = giro + Math.PI * 2 * i / n;
                Location pie = c.clone().add(Math.cos(a) * re, 0, Math.sin(a) * re);
                espiral.get(i).mover(pie);
                if (t % 3 == 0) {
                    Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, Fx.ground(pie, 4).add(0, 0.3, 0), 1,
                            0.15, 0.1, 0.15, 0, transicion(1.0f));
                }
            }
            if (t % 15 == 0) soundAt(c, "block.beacon.ambient", 1.6f, 0.6f);
            if (t % 2 == 0) {
                Fx.ring(c.clone().add(0, 0.15, 0), r, 50, p ->
                        Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, p, 1, 0, 0, 0, 0, transicion(1.4f)));
                for (int i = 0; i < 4; i++) {
                    double a = random.nextDouble() * Math.PI * 2;
                    Location borde = c.clone().add(Math.cos(a) * r, 0.3, Math.sin(a) * r);
                    // Varillas que corren hacia el centro: se ve a donde va todo.
                    Compat.spawn(world(), Compat.END_ROD, borde, 0, -Math.cos(a), 0, -Math.sin(a), 0.35);
                    Compat.spawn(world(), Compat.TRIAL_OMEN, borde, 1, 0.1, 0.1, 0.1, 0);
                }
            }
            if (t % 10 == 0) {
                soundAt(c, "block.bell.use", 1.6f, 0.5f + t * 0.005f);
                // Segundos que quedan, redondeando hacia arriba: 2, 2, 1, 1 (antes salia un 3).
                int quedan = Math.max(1, (40 - t + 19) / 20);
                for (Player p : Fx.viewersNear(c, 40)) {
                    boolean fuera = plano(p.getLocation(), c) > r;
                    p.sendActionBar(Component.text("Eclipse en " + quedan + "s   ", NamedTextColor.GRAY)
                            .append(Component.text(fuera ? "estás fuera" : "estás dentro",
                                    fuera ? NamedTextColor.GREEN : NamedTextColor.RED)));
                }
            }
        }, () -> {
            k.quitar();
            if (!alive()) {
                central.quitar();
                for (Rayo ry : espiral) ry.quitar();
                return;
            }
            // El destello: cada rayo se ensancha de golpe y se apaga, con un fogonazo al pie.
            central.destello();
            for (Rayo ry : espiral) {
                Location pie = ry.pie();
                if (pie != null) Compat.spawn(world(), Compat.FLASH, pie.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0, C_SANGRE);
                ry.destello();
            }
            soundAt(c, "block.beacon.deactivate", 1.4f, 0.7f);
            Location m = c.clone().add(0, 1, 0);
            Compat.spawn(world(), Compat.FLASH, m, 1, 0, 0, 0, 0, C_SANGRE);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, m, 2);
            Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, m, 120, r * 0.5, 0.6, r * 0.5, 0, transicion(2.0f));
            Fx.ring(c.clone().add(0, 0.5, 0), r, 60, p ->
                    Compat.spawn(world(), Compat.LARGE_SMOKE, p, 1, 0.1, 0.1, 0.1, 0.02));
            soundAt(c, "entity.wither.spawn", 1.5f, 1.0f);
            soundAt(c, "entity.generic.explode", 1.6f, 0.5f);
            for (Player p : Fx.playersNear(c, r + 3)) {
                if (plano(p.getLocation(), c) > r + 0.2) continue;
                if (Math.abs(p.getLocation().getY() - c.getY()) > 4) continue;
                hit(p, 18 * power());
                push(p, fuera(p.getLocation(), c).multiply(1.2).setY(0.55));
            }
        });
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
     * entidades. Nace creciendo desde el centro de cada pieza, las runas giran
     * despacio (la estrella en sentido contrario si se activa) y al acabar se encoge.
     *
     * @param ligero solo anillo exterior y runas: para los sellos de los pies, que
     *               pueden ser muchos a la vez
     */
    private Circulo circulo(Location suelo, double radio, int vida, boolean pentaculo, boolean ligero) {
        Location base = suelo.clone().add(0, 0.02, 0);
        base.setYaw(0);
        base.setPitch(0);
        Circulo k = new Circulo(suelo.clone(), radio);
        float ancho = radio < 2.5 ? 0.07f : 0.12f;

        // Anillo exterior, rojo.
        int lados = (int) Fx.clamp(Math.round(radio * 5), ligero ? 10 : 14, 40);
        anillo(k, base, radio, lados, ancho, Material.RED_CONCRETE);
        // Anillo interior, negro.
        double interior = radio * 0.66;
        if (!ligero) anillo(k, base, interior, Math.max(10, (int) (lados * 0.75)), ancho, Material.BLACK_CONCRETE);

        // Las runas, en medio. Escala segun el radio: a 0.7 fijo no se verian en el grande.
        int runas = (int) Fx.clamp(Math.round(radio * 3.6), ligero ? 6 : 8, 32);
        float gs = (float) Fx.clamp(0.8 + radio * 0.25, 1.0, 3.0);
        double rr = radio * 0.83 - gs * 0.12;
        for (int i = 0; i < runas; i++) {
            double a = Math.PI * 2 * i / runas;
            String glifo = GLIFOS[i % GLIFOS.length];
            int color = i % 3 == 2 ? NEGRO : (i % 3 == 1 ? GRANATE : SANGRE);
            // Tumbada (cara arriba) y con la parte de arriba de la letra mirando afuera.
            float yaw = (float) Math.atan2(-Math.cos(a), -Math.sin(a));
            Quaternionf q = new Quaternionf().rotateY(yaw).rotateX((float) (-Math.PI / 2));
            Vector3f mid = new Vector3f((float) (Math.cos(a) * rr), 0.05f, (float) (Math.sin(a) * rr));
            Vector3f esc = new Vector3f(gs, gs, gs);
            TextDisplay td = runa(base, glifo, color, Pieza.forma(mid, q, esc, false, 0.02f, 0f));
            if (td != null) k.piezas.add(new Pieza(td, mid, q, esc, false, 1));
        }

        // El pentaculo: cinco trazos que unen cada punta con la que esta a dos.
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
                segmento(k, base, a, b, ancho, Material.REDSTONE_BLOCK, 2);
            }
        }

        animate(vida, t -> {
            if (!k.vivo || cerrado) throw Stop.now();
            if (t == 2) for (Pieza p : k.piezas) p.aplicar(1f, k.giroDe(p.grupo), 6);
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
            segmento(k, base, p0, p1, ancho, mat, 0);
        }
    }

    /** Un trazo recto de a a b hecho con un bloque estirado. */
    private void segmento(Circulo k, Location base, Vector3f a, Vector3f b, float ancho, Material mat, int grupo) {
        float dx = b.x - a.x;
        float dz = b.z - a.z;
        float largo = (float) Math.sqrt(dx * dx + dz * dz) + 0.03f;
        // Con rotateY, el eje X local acaba en (cos, 0, -sin): de ahi el -dz.
        float yaw = (float) Math.atan2(-dz, dx);
        Quaternionf q = new Quaternionf().rotateY(yaw);
        Vector3f mid = new Vector3f((a.x + b.x) / 2f, 0f, (a.z + b.z) / 2f);
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

    // ================================================================= RAYOS ROJOS ==

    /* Grosor de la funda y del nucleo a grosor 1, como el haz del Alba a escala. */
    private static final float RAYO_FUNDA = 0.36f;
    private static final float RAYO_NUCLEO = 0.15f;
    /** Lo que queda bajo el suelo: el rayo sale de la tierra, no nace en el aire. */
    private static final float RAYO_BAJO_SUELO = 3f;

    /**
     * Un rayo rojo: el de los Rayos del Alba (Alba#haz) en carmesi. Dos BlockDisplay
     * anidados en el mismo punto: por fuera una funda de cristal rojo y por dentro un
     * nucleo de bloque de redstone con contorno rojo, los dos a brillo maximo. Sube
     * estirandose en cinco ticks (interpolacion) y se apaga adelgazando.
     *
     * El nucleo es REDSTONE_BLOCK y no RED_CONCRETE porque es el rojo mas vivo de los
     * dos; si en el juego se viera demasiado veteado dentro del cristal, el cambio es
     * solo el material.
     *
     * Para moverlo (la espiral del Eclipse) se teletransporta: con teleportDuration el
     * cliente lo desliza en vez de saltar.
     */
    private final class Rayo {
        final BlockDisplay funda;
        final BlockDisplay nucleo;
        final float alto;
        final float grosor;
        boolean vivo = true;

        Rayo(Location suelo, float alto, float grosor) {
            this.alto = alto + RAYO_BAJO_SUELO;
            this.grosor = grosor;
            Location base = baseDe(suelo);
            this.funda = parteRayo(base, Material.RED_STAINED_GLASS, RAYO_FUNDA * grosor, false);
            this.nucleo = parteRayo(base, Material.REDSTONE_BLOCK, RAYO_NUCLEO * grosor, true);
            // Un tick de margen: el cliente tiene que haber visto la forma corta para interpolar.
            later(1, () -> {
                if (!vivo) return;
                Conde.mover(funda, columna(RAYO_FUNDA * grosor, this.alto), 5);
                Conde.mover(nucleo, columna(RAYO_NUCLEO * grosor, this.alto), 5);
            });
        }

        /** Donde corta el suelo, o null si ya no esta. */
        Location pie() {
            BlockDisplay d = funda != null && funda.isValid() ? funda : nucleo;
            if (d == null || !d.isValid()) return null;
            return d.getLocation().add(0, RAYO_BAJO_SUELO, 0);
        }

        void mover(Location suelo) {
            if (!vivo) return;
            Location base = baseDe(suelo);
            for (BlockDisplay d : new BlockDisplay[]{funda, nucleo}) {
                if (d != null && d.isValid()) d.teleport(base);
            }
        }

        /** Se ensancha de golpe (tres ticks) y se apaga: el estallido. */
        void destello() {
            if (!vivo) return;
            Conde.mover(funda, columna(RAYO_FUNDA * grosor * 2.4f, alto), 3);
            Conde.mover(nucleo, columna(RAYO_NUCLEO * grosor * 2.4f, alto), 3);
            later(4, this::quitar);
        }

        /** Adelgaza en cinco ticks y desaparece. Se puede llamar varias veces. */
        void quitar() {
            if (!vivo) return;
            vivo = false;
            Conde.mover(funda, columna(0.02f, alto), 5);
            Conde.mover(nucleo, columna(0.02f, alto), 5);
            later(6, () -> {
                for (BlockDisplay d : new BlockDisplay[]{funda, nucleo}) {
                    if (d == null) continue;
                    spawned.remove(d);
                    Fx.safeRemove(d);
                }
            });
        }

        private Location baseDe(Location suelo) {
            Location b = new Location(world(), suelo.getX(), suelo.getY() - RAYO_BAJO_SUELO, suelo.getZ());
            b.setYaw(0);
            b.setPitch(0);
            return b;
        }
    }

    /** Un rayo rojo de 'alto' bloques sobre el suelo, que se apaga solo a los 'ticks' (0 = a mano). */
    private Rayo rayoRojo(Location base, float alto, int ticks) {
        return rayoRojo(base, alto, ticks, 1f);
    }

    /** Lo mismo con grosor: 1 es el del Alba a escala; el central del Eclipse va a 4,2. */
    private Rayo rayoRojo(Location base, float alto, int ticks, float grosor) {
        Rayo r = new Rayo(base, alto, grosor);
        if (ticks > 0) later(ticks, r::quitar);
        return r;
    }

    /** Una columna de 'ancho' x 'alto' centrada en su punto, creciendo hacia arriba. */
    private static Transformation columna(float ancho, float alto) {
        return new Transformation(new Vector3f(-ancho / 2f, 0f, -ancho / 2f),
                new Quaternionf(), new Vector3f(ancho, alto, ancho), new Quaternionf());
    }

    private BlockDisplay parteRayo(Location base, Material mat, float ancho, boolean brilla) {
        try {
            BlockDisplay d = world().spawn(base, BlockDisplay.class, e -> {
                e.setBlock(mat.createBlockData());
                e.setBrightness(new Display.Brightness(15, 15));
                e.setViewRange(4.0f);
                e.setShadowRadius(0f);
                e.setPersistent(false);
                e.setTeleportDuration(2);
                // Nace con la altura de lo enterrado: hasta que se estira no asoma del suelo.
                e.setTransformation(columna(ancho, RAYO_BAJO_SUELO));
                if (brilla) {
                    e.setGlowColorOverride(C_SANGRE);
                    e.setGlowing(true);
                }
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
