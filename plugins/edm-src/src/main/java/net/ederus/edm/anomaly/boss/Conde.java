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
        applyHealth(plugin.registry().scaledHealth(plugin.registry().get(ID), targets(96).size()));
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
    }

    // -------------------------------------------------------------------- ambiente

    /**
     * Un aura sobria: un poco de ceniza a los pies y un alma de vez en cuando. No es
     * una nube; es lo justo para saber cual de los cuerpos es el.
     */
    @Override
    protected void ambient() {
        if (!alive()) return;
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

    /** 1. Colmillos de Sombra: lineas de colmillos de evoker; de cada uno sale un murcielago. */
    public void colmillosDeSombra() {
        if (!alive()) return;
        List<Player> objetivos = pickTargets(3);
        if (objetivos.isEmpty()) return;
        final Location origen = Fx.ground(boss.getLocation(), 4);
        soundAt(origen, "entity.evoker.prepare_attack", 1.8f, 0.7f);
        soundAt(origen, "entity.illusioner.prepare_mirror", 1.4f, 0.6f);
        announce(Component.text("Colmillos de sombra. Sal de la línea."));

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

            final Set<UUID> mordidos = new HashSet<>();
            final List<Bat> crias = new ArrayList<>();
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
                        if (conCria) {
                            Bat b = murcielago(p.clone().add(0, 1.0, 0));
                            if (b != null) crias.add(b);
                        }
                    });
                }
                // Las crias persiguen al de su linea. Van naciendo segun brota cada colmillo.
                bandada(crias, objetivo, null, 0, 100 + puntos.size() * 2, 5 * power(), 0.34);
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

    /** 4. Sello Carmesi: un circulo magico bajo tres jugadores que estalla. */
    public void selloCarmesi() {
        if (!alive()) return;
        List<Player> objetivos = pickTargets(3);
        if (objetivos.isEmpty()) return;
        List<Circulo> sellos = new ArrayList<>();
        for (Player p : objetivos) {
            sellos.add(circulo(Fx.ground(p.getLocation(), 6), 1.5, 36, false, false));
            p.sendActionBar(Component.text("Tienes un sello rojo debajo. Sal de él.", ACCENT, TextDecoration.BOLD));
        }
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
                Compat.spawn(world(), Compat.FLASH, c, 1, 0, 0, 0, 0, C_SANGRE);
                Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, c, 40, 0.9, 0.5, 0.9, 0, transicion(1.6f));
                Compat.spawn(world(), Compat.LARGE_SMOKE, c, 10, 0.6, 0.3, 0.6, 0.03);
                soundAt(c, "entity.generic.explode", 1.3f, 0.6f);
                for (Player v : Fx.playersNear(s.centro, 3.5)) {
                    if (plano(v.getLocation(), s.centro) > 1.7) continue;
                    if (Math.abs(v.getLocation().getY() - s.centro.getY()) > 2.5) continue;
                    hit(v, 10 * power());
                    push(v, fuera(v.getLocation(), s.centro).multiply(0.55).setY(0.3));
                }
            }
        });
    }

    /** 5. Mordisco: se lanza sobre el mas cercano, muerde y se cura con lo que le saca. */
    public void mordisco() {
        if (!alive() || formaBat != null) return;
        final Player v = Fx.nearest(boss.getLocation(), 14);
        if (v == null) return;
        soundAt(loc(), "entity.evoker.prepare_attack", 1.6f, 1.2f);
        v.sendActionBar(Component.text("El Conde va a morderte.", ACCENT, TextDecoration.BOLD));

        // Aviso: un hilo rojo de el hasta su presa y el cuerpo envuelto en rojo.
        animate(12, t -> {
            if (!alive() || !Fx.isFightable(v)) throw Stop.now();
            face(v.getEyeLocation());
            if (t % 2 == 0) {
                Fx.beam(boss.getLocation().add(0, 1.2, 0), v.getLocation().add(0, 1.0, 0), 0.7, p ->
                        Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, p, 1, 0, 0, 0, 0, transicion(0.9f)));
                Compat.spawn(world(), Compat.ENTITY_EFFECT, boss.getLocation().add(0, 1, 0), 4,
                        0.3, 0.5, 0.3, 0, rojo());
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
                boss.teleport(aqui);
                Compat.spawn(world(), Compat.LARGE_SMOKE, aqui.clone().add(0, 1, 0), 2, 0.2, 0.4, 0.2, 0.01);
            }, () -> {
                if (!alive() || !Fx.isFightable(v)) return;
                if (v.getLocation().distance(boss.getLocation()) > 2.8) return;
                double dano = 9 * power();
                hit(v, dano);
                curar(dano * 0.30);
                Location pecho = v.getLocation().add(0, 1.1, 0);
                Compat.spawn(world(), Compat.DAMAGE_INDICATOR, pecho, 5, 0.3, 0.3, 0.3, 0.1);
                Compat.spawn(world(), Compat.ENTITY_EFFECT, pecho, 10, 0.3, 0.4, 0.3, 0, rojo());
                Fx.beam(pecho, boss.getLocation().add(0, 1.3, 0), 0.4, p ->
                        Compat.spawn(world(), Compat.ENTITY_EFFECT, p, 1, 0, 0, 0, 0, rojo()));
                soundAt(pecho, "entity.phantom.bite", 1.6f, 0.8f);
                soundAt(pecho, "entity.hoglin.attack", 1.2f, 1.3f);
            });
        });
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

    /**
     * Tres pasadas de 50 ticks: sube, marca a uno, baja en picado y vuelve a subir.
     * El aviso es el circulo rojo que aparece bajo la victima antes de cada picado.
     */
    private void embestidas() {
        final Player[] victima = new Player[1];
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
                victima[0] = randomTarget();
                if (victima[0] == null) throw Stop.now();
                Vector lado = victima[0].getLocation().toVector().subtract(formaBat.getLocation().toVector()).setY(0);
                if (lado.lengthSquared() < 0.01) lado = new Vector(1, 0, 0);
                // Recortado contra paredes y techos: el coliseo es cerrado, y un apice
                // dentro de la grada dejaba al jefe, al volver a humano, encima de ella.
                apice[0] = despejado(victima[0].getEyeLocation(), victima[0].getLocation().clone()
                        .add(lado.normalize().multiply(-7)).add(0, 7, 0), 3.0);
                soundAt(formaBat.getLocation(), "entity.bat.takeoff", 2.0f, 0.5f);
            }
            if (victima[0] == null) return;
            if (s < 20) {
                fly(formaBat, apice[0], 0.8);
                if (s >= 5 && Fx.isFightable(victima[0])) {
                    Fx.ring(Fx.ground(victima[0].getLocation(), 6).add(0, 0.12, 0), 1.8, 16, p ->
                            Compat.spawn(world(), Compat.DUST_COLOR_TRANSITION, p, 1, 0, 0, 0, 0, transicion(1.3f)));
                }
            } else if (s == 20) {
                picado[0] = Fx.isFightable(victima[0])
                        ? victima[0].getLocation().add(0, 1.0, 0) : formaBat.getLocation();
                Vector r = picado[0].toVector().subtract(formaBat.getLocation().toVector());
                rumbo[0] = r.lengthSquared() < 0.01 ? new Vector(0, -1, 0) : r.normalize();
                soundAt(formaBat.getLocation(), "entity.phantom.swoop", 2.0f, 0.7f);
            } else if (s < 36) {
                // Pasa de largo tres bloques: se ve el picado entero, no un frenazo.
                fly(formaBat, picado[0].clone().add(rumbo[0].clone().multiply(3)), 1.1);
                for (Player p : Fx.playersNear(formaBat.getLocation(), 2.3)) {
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

    /** 7. Lluvia de Murcielagos: veinte murcielagos caen en picado sobre circulos marcados. */
    public void lluviaDeMurcielagos() {
        if (!alive()) return;
        List<Location> puntos = new ArrayList<>();
        for (Player p : targets()) {
            for (int i = 0; i < 3 && puntos.size() < 20; i++) {
                puntos.add(Fx.ground(p.getLocation().clone().add(
                        random.nextDouble() * 4 - 2, 0, random.nextDouble() * 4 - 2), 6));
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

    /** 8. Pentaculo: un circulo enorme; de las cinco puntas suben pilares de luz roja. */
    public void pentaculo() {
        if (!alive()) return;
        final Location c = Fx.ground(boss.getLocation(), 4);
        final Circulo k = circulo(c, 7, 84, true, false);
        soundAt(c, "block.bell.use", 1.8f, 0.5f);
        soundAt(c, "entity.illusioner.cast_spell", 1.6f, 0.7f);
        announce(Component.text("Pentáculo. No te quedes en las puntas de la estrella."));

        final List<BlockDisplay> pilares = new ArrayList<>();
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
                    BlockDisplay d = pilar(v, 2.2f, 12f);
                    if (d != null) pilares.add(d);
                    tocados.add(new HashSet<>());
                }
                soundAt(c, "block.bell.use", 1.8f, 0.5f);
                soundAt(c, "entity.wither.shoot", 1.2f, 0.5f);
            }
            if (t % 2 != 0) return;
            for (int i = 0; i < k.vertices.size(); i++) {
                Location v = k.vertices.get(i);
                Compat.spawn(world(), Compat.END_ROD, v.clone().add(0, 0.5, 0), 0, 0, 1, 0, 0.3);
                Compat.spawn(world(), Compat.FALLING_DUST, v.clone().add(0, 6, 0), 2, 0.5, 4, 0.5, 0,
                        Material.RED_NETHER_BRICKS.createBlockData());
                for (Player p : Fx.playersNear(v, 13)) {
                    if (plano(p.getLocation(), v) > 1.35) continue;
                    double dy = p.getLocation().getY() - v.getY();
                    if (dy < -1 || dy > 12) continue;
                    if (!tocados.get(i).add(p.getUniqueId())) continue;
                    hit(p, 11 * power());
                    push(p, fuera(p.getLocation(), v).multiply(0.6).setY(0.35));
                }
            }
        }, () -> {
            for (BlockDisplay d : pilares) encoger(d, 2.2f);
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
            p.sendActionBar(Component.text("Festín de sangre. Aléjate más de 12 bloques.", ACCENT, TextDecoration.BOLD));
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

        animate(40, t -> {
            if (!alive()) throw Stop.now();
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
                            .append(Component.text(fuera ? "estás fuera" : "ESTÁS DENTRO",
                                    fuera ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD)));
                }
            }
        }, () -> {
            k.quitar();
            if (!alive()) return;
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

    /** Un pilar de luz roja que sube del suelo en cinco ticks. */
    private BlockDisplay pilar(Location base, float ancho, float alto) {
        try {
            BlockDisplay d = world().spawn(base, BlockDisplay.class, e -> {
                e.setBlock(Material.RED_STAINED_GLASS.createBlockData());
                e.setBrightness(new Display.Brightness(15, 15));
                e.setViewRange(4.0f);
                e.setShadowRadius(0f);
                e.setPersistent(false);
                e.setTeleportDuration(0);
                e.setTransformation(new Transformation(new Vector3f(-ancho / 2, 0, -ancho / 2),
                        new Quaternionf(), new Vector3f(ancho, 0.05f, ancho), new Quaternionf()));
            });
            Tags.markTemporary(d);
            track(d);
            later(2, () -> mover(d, new Transformation(new Vector3f(-ancho / 2, 0, -ancho / 2),
                    new Quaternionf(), new Vector3f(ancho, alto, ancho), new Quaternionf()), 5));
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Baja un pilar hasta el suelo y lo quita. */
    private void encoger(BlockDisplay d, float ancho) {
        if (d == null) return;
        mover(d, new Transformation(new Vector3f(-ancho / 2, 0, -ancho / 2),
                new Quaternionf(), new Vector3f(ancho, 0.05f, ancho), new Quaternionf()), 5);
        later(6, () -> {
            spawned.remove(d);
            Fx.safeRemove(d);
        });
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
