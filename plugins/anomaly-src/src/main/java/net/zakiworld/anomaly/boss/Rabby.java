package net.zakiworld.anomaly.boss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.zakiworld.anomaly.AnomalyPlugin;
import net.zakiworld.anomaly.core.ActiveAnomaly;
import net.zakiworld.anomaly.core.Compat;
import net.zakiworld.anomaly.core.Disguises;
import net.zakiworld.anomaly.core.Fx;
import net.zakiworld.anomaly.core.Glow;
import net.zakiworld.anomaly.core.Stop;
import net.zakiworld.anomaly.core.Tags;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * RABBY, la duodecima anomalia.
 *
 * Un tipo bajito con cara de buena persona que esta ahi parado saludando. Y de verdad
 * no hace nada: se le puede rodear, mirar y hasta ignorar. PERO SI ALGUIEN LE PEGA se
 * acabo la broma, y lo que sale es una pelea de anime.
 *
 * Todo lo suyo es desplazamiento y castigo: corre mas rapido de lo que se puede huir,
 * batea a la gente al cielo, se teletransporta a rematarla arriba y la clava contra el
 * suelo. En la ultima fase entra en CONCENTRACION —un rayo, brillo blanco y cinco veces
 * el dano— y carga un ataque que se traga las estelas de media arena y revienta a
 * cualquiera que no lleve buen equipo.
 *
 * El cuerpo que se ve es un MANNEQUIN con perfil de skin propio; el zombi que pelea va
 * debajo, invisible y CALLADO —sus gruñidos delataban el truco— y los golpes que recibe
 * el maniqui se le pasan a el. La ropa blanca de cuero es aparte: si el cliente no
 * llegara a pintar la textura del perfil, la silueta se sigue pareciendo a la skin.
 */
public final class Rabby extends BossFight {

    public static final String ID = "rabby";
    public static final TextColor ACCENT = TextColor.color(0xF2C14E);

    /**
     * La cuenta que lleva puesta la skin de Rabby.
     *
     * Se apunta a una cuenta de verdad y no a la textura suelta porque es la unica via
     * que funciona: el cuerpo con forma de jugador solo pinta perfiles que Mojang pueda
     * resolver y firmar. Si algun dia se le cambia la skin a esta cuenta, cambia Rabby.
     */
    private static final String SKIN_ACCOUNT = "LeanFish_CKB";

    /** La textura, para la cabeza de repuesto si la cuenta no se pudiera resolver. */
    private static final String SKIN =
            "f523eb05428bd5a8df0bddd6213cd7ce77814084de7b84a33c1b9a8629198a05";

    private static final int SPARK = 0xFFF2A8;

    private boolean angry;
    private boolean concentrated;
    private long concentrationUntil;
    private double damageBonus = 1.0;

    public Rabby(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        super(plugin, event, where);
        abilities.addAll(plugin.registry().rabbyAbilities());
    }

    @Override
    public String bossName() {
        return "Rabby";
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
            z.customName(Component.text("Rabby", ACCENT, TextDecoration.BOLD));
            z.setCustomNameVisible(true);
        });

        EntityEquipment eq = boss.getEquipment();
        if (eq != null) {
            eq.setHelmetDropChance(0);
            eq.setItemInMainHandDropChance(0);
        }
        // El cuerpo de persona con SU skin, sacada de una cuenta de verdad: es la unica
        // forma de que el maniqui la pinte. El zombi de debajo sigue peleando, pero
        // invisible y callado; lo que se ve —y lo que se golpea— es el maniqui.
        var profile = Disguises.profileOfAccount(plugin, SKIN_ACCOUNT);
        if (profile != null) {
            wearShell(profile, Component.text("Rabby", ACCENT, TextDecoration.BOLD));
        } else if (eq != null) {
            // Sin cuenta que resolver, al menos la cara por cabeza.
            eq.setHelmet(Disguises.head(plugin, SKIN, "Rabby"));
        }

        Compat.setAttribute(boss, "max_health", 20);
        Compat.setAttribute(boss, "attack_damage", 0);
        Compat.setAttribute(boss, "armor", 6);
        Compat.setAttribute(boss, "knockback_resistance", 1.0);
        Compat.setAttribute(boss, "follow_range", 72);
        Compat.setAttribute(boss, "movement_speed", 0.26);
        applyHealth(plugin.registry().scaledHealth(plugin.registry().rabby(), targets(96).size()));
        boss.setMaximumNoDamageTicks(6);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());

        // Ni brillo ni pilar: es un vecino cualquiera hasta que deja de serlo.
        for (Player p : Fx.viewersNear(spot, 90)) {
            p.showTitle(Title.title(
                    Component.text("✦ ANOMALIA ✦", ACCENT, TextDecoration.BOLD),
                    Component.text("Rabby  ·  parece buena gente", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1800), Duration.ofMillis(600))));
        }
        soundAt(spot, "entity.player.levelup", 1.0f, 1.8f);
        Compat.spawn(world(), Compat.FIREWORK_SPARK, spot.clone().add(0, 2, 0), 14, 0.5, 0.6, 0.5, 0.05);
    }

    private static ItemStack dyed(Material piece, int rgb) {
        ItemStack item = new ItemStack(piece);
        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta meta) {
            meta.setColor(org.bukkit.Color.fromRGB(rgb));
            item.setItemMeta(meta);
        }
        return item;
    }

    // -------------------------------------------------------------------- ambiente

    @Override
    protected void ambient() {
        if (!alive()) return;

        if (!angry) {
            idle();
            return;
        }
        keepHostile();

        if (concentrated) {
            if (ticks() > concentrationUntil) {
                endConcentration();
            } else if (ticks() % 3 == 0) {
                Location l = boss.getLocation().add(0, 1.0, 0);
                Compat.spawn(world(), Compat.ELECTRIC_SPARK, l, 3, 0.4, 0.7, 0.4, 0.02);
                Fx.helix(boss.getLocation(), 0.9, 2.4, 10, 2.0, p ->
                        Compat.spawn(world(), Compat.END_ROD, p, 1, 0, 0, 0, 0));
            }
        }
    }

    /**
     * Rabby tranquilo: se queda por ahi, saluda y no le hace nada a nadie.
     * Ni siquiera devuelve el objetivo, para que ninguna IA le entre a pegar sola.
     */
    private void idle() {
        if (boss instanceof Mob m && m.getTarget() != null) m.setTarget(null);
        if (ticks() % 60 == 0) {
            Compat.spawn(world(), Compat.FIREWORK_SPARK, boss.getLocation().add(0, 2.2, 0), 1,
                    0.25, 0.15, 0.25, 0.01);
        }
        if (ticks() % 70 != 0) return;
        Player near = Fx.nearest(boss.getLocation(), 8);
        if (near == null) return;
        face(near.getEyeLocation());
        near.sendActionBar(Component.text("Rabby te saluda.", ACCENT));
        if (random.nextInt(3) == 0) soundAt(boss.getLocation(), "block.note_block.bell", 0.7f, 1.8f);
    }

    private void keepHostile() {
        if (ticks() % 10 != 0) return;
        Player t = Fx.nearest(boss.getLocation(), plugin.settings().participationRadius());
        if (t == null) return;
        if (boss instanceof Mob m) {
            LivingEntity current = m.getTarget();
            if (current == null || !current.isValid() || current.isDead()) m.setTarget(t);
        }
    }

    /**
     * El primer golpe lo cambia todo. Hasta aqui no habia jefe, habia un vecino.
     */
    @Override
    public void onDamaged(Player attacker, double amount) {
        if (angry || !alive()) return;
        angry = true;

        Location l = boss.getLocation();
        Compat.setAttribute(boss, "attack_damage", 12);
        Compat.setAttribute(boss, "movement_speed", 0.42);
        Compat.setAttribute(boss, "attack_speed", 2.4);
        Compat.setAttribute(boss, "armor", 12);
        renameBody(Component.text("✦ ", ACCENT)
                .append(Component.text("Rabby", ACCENT, TextDecoration.BOLD)));

        world().strikeLightningEffect(l);
        Compat.spawn(world(), Compat.FLASH, l.clone().add(0, 1, 0), 1);
        Compat.spawn(world(), Compat.ANGRY_VILLAGER, l.clone().add(0, 2.2, 0), 16, 0.5, 0.4, 0.5, 0);
        Compat.spawn(world(), Compat.GUST_EMITTER_LARGE, l.clone().add(0, 1, 0), 1, 0, 0, 0, 0);
        soundAt(l, "entity.player.attack.crit", 1.8f, 0.7f);
        soundAt(l, "entity.wither.spawn", 1.0f, 1.6f);

        titleNear(Component.text("MALA IDEA", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("No habia que pegarle a Rabby", NamedTextColor.GRAY));
        if (attacker != null) {
            attacker.sendActionBar(Component.text("Rabby te ha mirado a TI.",
                    NamedTextColor.RED, TextDecoration.BOLD));
            if (boss instanceof Mob m) m.setTarget(attacker);
        }
        for (Player p : Fx.playersNear(l, 6)) {
            push(p, p.getLocation().toVector().subtract(l.toVector()).normalize().multiply(0.9).setY(0.4));
        }
    }

    // --------------------------------------------------------------- cambio de fase

    @Override
    protected void onPhaseChange(int from, int to) {
        if (event.bars() != null) event.bars().flash(from);
        if (to == 2) {
            damageBonus = 1.3;
            Compat.setAttribute(boss, "movement_speed", 0.48);
            Compat.setAttribute(boss, "attack_damage", 16);
            titleNear(Component.text("FASE II", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("Ahora va en serio", NamedTextColor.GRAY));
            soundAt(loc(), "entity.player.attack.sweep", 1.6f, 0.6f);
        }
        if (to == 3) {
            damageBonus = 1.6;
            Compat.setAttribute(boss, "movement_speed", 0.55);
            Compat.setAttribute(boss, "attack_damage", 20);
            Compat.setAttribute(boss, "attack_speed", 3.4);
            titleNear(Component.text("FASE III", NamedTextColor.RED, TextDecoration.BOLD),
                    Component.text("Se le acabo la paciencia", NamedTextColor.GRAY));
            concentration();
        }
    }

    // ---------------------------------------------------------------------- muerte

    @Override
    public void onDeath() {
        Location l = loc();
        soundAt(l, "entity.player.big_fall", 1.6f, 0.6f);

        animate(80, tick -> {
            if (tick % 10 == 0) {
                Compat.spawn(world(), Compat.ELECTRIC_SPARK, l.clone().add(0, 1, 0), 20, 0.6, 0.8, 0.6, 0.05);
                soundAt(l, "entity.player.hurt", 1.0f, 0.6f + tick * 0.01f);
            }
            Compat.spawn(world(), Compat.SMALL_GUST, l.clone().add(0, 1, 0), 1, 0.5, 0.5, 0.5, 0);
        }, () -> {
            Compat.spawn(world(), Compat.FLASH, l.clone().add(0, 1, 0), 1);
            Compat.spawn(world(), Compat.FIREWORK_SPARK, l.clone().add(0, 1, 0), 60, 0.8, 0.8, 0.8, 0.25);
            soundAt(l, "entity.firework_rocket.large_blast", 1.6f, 0.8f);
            broadcastNear(Component.text("Rabby se levanta el sombrero y se apaga.", ACCENT));
        });
    }

    // ============================================================== HABILIDADES ==

    /** Cuanto pega ahora mismo: fase, mas el x5 de la concentracion. */
    private double power() {
        return damageBonus * (concentrated ? 5.0 : 1.0);
    }

    /** 1. Carrera Fantasma: cruza la arena tan rapido que deja copias detras. */
    public void ghostRun() {
        if (!alive() || !angry) return;
        Player target = randomTarget();
        if (target == null) return;
        Location from = boss.getLocation();
        Vector dir = target.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        final Vector run = dir.normalize();
        java.util.Set<UUID> hitSet = new java.util.HashSet<>();

        soundAt(from, "entity.player.attack.sweep", 1.5f, 1.4f);
        broadcastNear(Component.text("Desaparece del sitio.", ACCENT));

        animate(40, tick -> {
            if (!alive()) throw Stop.now();
            boss.setVelocity(run.clone().multiply(1.5).setY(boss.getVelocity().getY()));
            // La estela: humo blanco marcando por donde acaba de pasar.
            Compat.spawn(world(), Compat.WHITE_SMOKE, boss.getLocation().add(0, 1, 0), 4, 0.2, 0.4, 0.2, 0.01);
            Compat.spawn(world(), Compat.SMALL_GUST, boss.getLocation(), 1, 0.2, 0.1, 0.2, 0);
            for (Player p : Fx.playersNear(boss.getLocation(), 2.4)) {
                if (!hitSet.add(p.getUniqueId())) continue;
                hit(p, 12 * power());
                push(p, run.clone().multiply(1.2).setY(0.5));
                Compat.spawn(world(), Compat.CRIT, p.getLocation().add(0, 1, 0), 14, 0.3, 0.4, 0.3, 0.3);
                soundAt(p.getLocation(), "entity.player.attack.knockback", 1.3f, 1.1f);
            }
        }, null);
    }

    /** 2. Batazo: coge a uno y lo manda al cielo como quien saca de home run. */
    public void homeRun() {
        if (!alive() || !angry) return;
        Player target = Fx.nearest(loc(), 12);
        if (target == null) return;

        soundAt(loc(), "entity.player.attack.sweep", 1.6f, 0.7f);
        Vector to = target.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (to.lengthSquared() > 0.01) boss.setVelocity(to.normalize().multiply(0.9).setY(0.25));

        later(8, () -> {
            if (!alive() || !Fx.isFightable(target)) return;
            if (boss.getLocation().distanceSquared(target.getLocation()) > 25) return;
            hit(target, 14 * power());
            // lift() y no setVelocity a pelo: es lo que da permiso de vuelo y evita
            // que el servidor lo eche por "moverse muy rapido" a mitad del viaje.
            lift(target, new Vector(0, 1.75, 0));
            Compat.spawn(world(), Compat.SONIC_BOOM, target.getLocation().add(0, 1, 0), 1);
            Compat.spawn(world(), Compat.FIREWORK_SPARK, target.getLocation().add(0, 1, 0), 30,
                    0.4, 0.4, 0.4, 0.35);
            soundAt(target.getLocation(), "entity.player.attack.crit", 1.8f, 0.6f);
            soundAt(target.getLocation(), "block.anvil.land", 1.2f, 1.8f);
            target.sendActionBar(Component.text("¡BATAZO!", NamedTextColor.RED, TextDecoration.BOLD));
        });
    }

    /** 3. Pisoton Sonico: salta muy alto y revienta el suelo; la onda saca a todos. */
    public void sonicStomp() {
        if (!alive() || !angry) return;
        Location mark = Fx.ground(boss.getLocation(), 4);
        soundAt(mark, "entity.player.attack.strong", 1.5f, 0.8f);
        broadcastNear(Component.text("Salta.", ACCENT));

        animate(70, tick -> {
            if (!alive()) throw Stop.now();
            if (tick == 0) {
                boss.setVelocity(new Vector(0, 1.5, 0));
                return;
            }
            if (tick < 30) {
                Compat.spawn(world(), Compat.CLOUD, boss.getLocation(), 2, 0.2, 0.2, 0.2, 0.01);
                Fx.telegraph(world(), mark, 9.0, SPARK);
                return;
            }
            if (tick == 30) {
                boss.setVelocity(new Vector(0, -2.4, 0));
                return;
            }
            if (!boss.isOnGround() && tick < 60) return;

            Location l = Fx.ground(boss.getLocation(), 3);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l, 2);
            Compat.spawn(world(), Compat.GUST_EMITTER_LARGE, l.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0);
            Compat.spawn(world(), Compat.SONIC_BOOM, l.clone().add(0, 1, 0), 1);
            Compat.spawn(world(), Compat.DUST_PILLAR, l, 40, 2.0, 0.2, 2.0, 0,
                    Fx.ground(l, 3).getBlock().getRelative(0, -1, 0).getType().isSolid()
                            ? Fx.ground(l, 3).getBlock().getRelative(0, -1, 0).getBlockData()
                            : Material.STONE.createBlockData());
            soundAt(l, "entity.generic.explode", 1.8f, 0.5f);
            soundAt(l, "entity.warden.sonic_boom", 1.4f, 1.0f);

            for (Player p : Fx.playersNear(l, 10)) {
                double d = p.getLocation().distance(l);
                hit(p, Math.max(8, 22 - d) * power());
                Vector out = p.getLocation().toVector().subtract(l.toVector());
                if (out.lengthSquared() < 0.01) out = new Vector(1, 0, 0);
                push(p, out.normalize().multiply(1.6).setY(0.95));
            }
            throw Stop.now();
        }, null);
    }

    /** 4. Rafaga de Golpes: una tanda de puñetazos a quien tenga delante. */
    public void punchFlurry() {
        if (!alive() || !angry) return;
        int blows = concentrated ? 12 : 8;
        soundAt(loc(), "entity.player.attack.strong", 1.4f, 1.3f);
        broadcastNear(Component.text("Rafaga.", ACCENT));

        for (int i = 0; i < blows; i++) {
            later(i * 4, () -> {
                if (!alive()) return;
                Player near = Fx.nearest(boss.getLocation(), 4.5);
                if (near == null) return;
                hit(near, 4 * power());
                Compat.spawn(world(), Compat.CRIT, near.getLocation().add(0, 1.2, 0), 8, 0.25, 0.3, 0.25, 0.2);
                Compat.spawn(world(), Compat.SWEEP_ATTACK, near.getLocation().add(0, 1, 0), 1);
                soundAt(near.getLocation(), "entity.player.attack.strong", 1.0f,
                        1.0f + random.nextFloat() * 0.5f);
            });
        }
    }

    /**
     * 5. Combo Aereo: la firma de la casa.
     *
     * Batea a alguien al cielo, se teletransporta a su altura, le mete una tanda de
     * golpes ahi arriba y lo clava contra el suelo. El remate es lo que mas duele, y
     * la caida encima.
     */
    public void airCombo() {
        if (!alive() || !angry) return;
        Player victim = randomTarget();
        if (victim == null) return;

        soundAt(loc(), "entity.player.attack.crit", 1.7f, 0.6f);
        titleNear(Component.text("COMBO", ACCENT, TextDecoration.BOLD),
                Component.text(victim.getName() + " se va arriba", NamedTextColor.GRAY));

        // 1) el batazo inicial
        Vector to = victim.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (to.lengthSquared() > 0.01) boss.setVelocity(to.normalize().multiply(1.1).setY(0.3));

        later(8, () -> {
            if (!alive() || !Fx.isFightable(victim)) return;
            hit(victim, 10 * power());
            lift(victim, new Vector(0, 1.9, 0));
            Compat.spawn(world(), Compat.SONIC_BOOM, victim.getLocation().add(0, 1, 0), 1);
            soundAt(victim.getLocation(), "block.anvil.land", 1.3f, 1.9f);

            // 2) sube con el y le pega arriba
            animate(110, tick -> {
                if (!alive() || !Fx.isFightable(victim)) throw Stop.now();
                if (tick == 14) {
                    boss.teleport(victim.getLocation().clone().add(0, 1.2, 0));
                    grantAirTime(victim, 200);
                    Compat.spawn(world(), Compat.FLASH, boss.getLocation(), 1);
                    soundAt(boss.getLocation(), "entity.enderman.teleport", 1.4f, 1.2f);
                    return;
                }
                if (tick < 14) return;

                if (tick < 74) {
                    // Le sigue el paso para que no se caiga a mitad de combo.
                    boss.teleport(victim.getLocation().clone().add(
                            Math.cos(tick * 0.6) * 1.2, 0.4, Math.sin(tick * 0.6) * 1.2));
                    face(victim.getEyeLocation());
                    lift(victim, new Vector(0, 0.06, 0));
                    if (tick % 7 == 0) {
                        hit(victim, 5 * power());
                        Compat.spawn(world(), Compat.CRIT, victim.getLocation().add(0, 1, 0), 12,
                                0.3, 0.3, 0.3, 0.25);
                        Compat.spawn(world(), Compat.SWEEP_ATTACK, victim.getLocation().add(0, 1, 0), 1);
                        soundAt(victim.getLocation(), "entity.player.attack.strong", 1.2f, 1.4f);
                    }
                    return;
                }
                if (tick != 74) return;

                // 3) el remate: lo clava contra el suelo
                Location under = Fx.ground(victim.getLocation(), 40);
                boss.teleport(victim.getLocation().clone().add(0, 2.2, 0));
                victim.setVelocity(new Vector(0, -3.2, 0));
                soundAt(victim.getLocation(), "entity.player.attack.crit", 1.8f, 0.5f);
                victim.sendActionBar(Component.text("¡AL SUELO!", NamedTextColor.RED, TextDecoration.BOLD));

                later(16, () -> {
                    if (!Fx.isFightable(victim)) return;
                    Location impact = Fx.ground(victim.getLocation(), 6);
                    hit(victim, 22 * power());
                    Compat.spawn(world(), Compat.EXPLOSION_EMITTER, impact, 1);
                    Compat.spawn(world(), Compat.DUST_PILLAR, impact, 30, 1.4, 0.2, 1.4, 0,
                            Material.STONE.createBlockData());
                    soundAt(impact, "entity.generic.explode", 1.5f, 0.6f);
                    for (Player other : Fx.playersNear(impact, 5)) {
                        if (other.equals(victim)) continue;
                        hit(other, 8 * power());
                        push(other, other.getLocation().toVector().subtract(impact.toVector())
                                .normalize().multiply(1.1).setY(0.5));
                    }
                    if (under != null) boss.teleport(impact.clone().add(1.5, 0, 0));
                });
                throw Stop.now();
            }, null);
        });
    }

    /** 6. Acoso Relampago: se teletransporta a cuatro sitios pegando en cada uno. */
    public void blinkHarass() {
        if (!alive() || !angry) return;
        List<Player> pool = targets(30);
        if (pool.isEmpty()) return;
        soundAt(loc(), "entity.enderman.teleport", 1.4f, 1.0f);
        broadcastNear(Component.text("Se mueve mas rapido de lo que se ve.", ACCENT));

        int jumps = Math.min(4, Math.max(2, pool.size()));
        for (int i = 0; i < jumps; i++) {
            final int idx = i;
            later(i * 14, () -> {
                if (!alive()) return;
                Player p = pool.get(idx % pool.size());
                if (!Fx.isFightable(p)) return;
                Location behind = p.getLocation().clone().add(
                        p.getLocation().getDirection().setY(0).normalize().multiply(-1.4));
                Compat.spawn(world(), Compat.PORTAL, boss.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.1);
                boss.teleport(behind);
                face(p.getEyeLocation());
                Compat.spawn(world(), Compat.FLASH, behind.clone().add(0, 1, 0), 1);
                hit(p, 11 * power());
                push(p, p.getLocation().getDirection().setY(0).normalize().multiply(0.9).setY(0.35));
                Compat.spawn(world(), Compat.CRIT, p.getLocation().add(0, 1, 0), 16, 0.3, 0.4, 0.3, 0.3);
                soundAt(p.getLocation(), "entity.player.attack.crit", 1.4f, 1.2f);
            });
        }
    }

    /** 7. Puño Cometa: se lanza desde muy arriba sobre una marca. */
    public void cometFist() {
        if (!alive() || !angry) return;
        Player target = randomTarget();
        if (target == null) return;
        Location mark = Fx.ground(target.getLocation(), 5);
        soundAt(loc(), "item.trident.riptide_3", 1.5f, 0.8f);
        broadcastNear(Component.text("Se va para arriba.", ACCENT));

        animate(80, tick -> {
            if (!alive()) throw Stop.now();
            if (tick == 0) {
                boss.setVelocity(new Vector(0, 1.9, 0));
                return;
            }
            if (tick < 26) {
                Compat.spawn(world(), Compat.CLOUD, boss.getLocation(), 2, 0.2, 0.2, 0.2, 0.01);
                Fx.telegraph(world(), mark, 4.0, 0xFF7043);
                return;
            }
            if (tick == 26) {
                Location above = mark.clone().add(0, 14, 0);
                boss.teleport(above);
                Compat.spawn(world(), Compat.FLASH, above, 1);
                return;
            }
            if (tick < 34) {
                Fx.telegraph(world(), mark, 4.0, 0xFF7043);
                return;
            }
            if (tick == 34) {
                boss.setVelocity(new Vector(0, -3.0, 0));
                return;
            }
            Compat.spawn(world(), Compat.FLAME, boss.getLocation(), 6, 0.3, 0.4, 0.3, 0.02);
            Compat.spawn(world(), Compat.LAVA, boss.getLocation(), 1, 0.2, 0.2, 0.2, 0);
            if (!boss.isOnGround() && tick < 74) return;

            Location l = Fx.ground(boss.getLocation(), 3);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, l, 2);
            Compat.spawn(world(), Compat.GUST_EMITTER_LARGE, l.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0);
            soundAt(l, "entity.generic.explode", 1.7f, 0.5f);
            for (Player p : Fx.playersNear(l, 6)) {
                double d = p.getLocation().distance(l);
                hit(p, Math.max(10, 26 - d * 1.5) * power());
                push(p, p.getLocation().toVector().subtract(l.toVector())
                        .normalize().multiply(1.3).setY(0.8));
            }
            throw Stop.now();
        }, null);
    }

    /** 8. Patada Giratoria: gira sobre si mismo y saca de la arena a todo lo cercano. */
    public void spinKick() {
        if (!alive() || !angry) return;
        soundAt(loc(), "entity.player.attack.sweep", 1.6f, 0.9f);
        java.util.Set<UUID> kicked = new java.util.HashSet<>();

        animate(26, tick -> {
            if (!alive()) throw Stop.now();
            Location c = boss.getLocation().add(0, 1.0, 0);
            double radius = 1.5 + tick * 0.18;
            Fx.ring(c, radius, (int) (radius * 8), tick * 0.5, p -> {
                Compat.spawn(world(), Compat.SWEEP_ATTACK, p, 1, 0, 0, 0, 0);
                Compat.spawn(world(), Compat.SMALL_GUST, p, 1, 0, 0, 0, 0);
            });
            for (Player p : targets(radius + 1.0)) {
                if (!kicked.add(p.getUniqueId())) continue;
                hit(p, 13 * power());
                push(p, p.getLocation().toVector().subtract(c.toVector())
                        .normalize().multiply(1.7).setY(0.7));
                soundAt(p.getLocation(), "entity.player.attack.knockback", 1.3f, 0.9f);
            }
        }, null);
    }

    /**
     * 9. Concentracion: el rayo, el brillo blanco y el x5.
     *
     * Es el interruptor de la pelea: mientras dura, TODO lo que hace multiplica por
     * cinco. Se anuncia bien alto porque durante esos quince segundos lo sensato es
     * no estar cerca.
     */
    public void concentration() {
        if (!alive() || !angry || concentrated) return;
        concentrated = true;
        concentrationUntil = ticks() + 300;

        Location l = boss.getLocation();
        world().strikeLightningEffect(l);
        glowBody(NamedTextColor.WHITE);
        Compat.spawn(world(), Compat.FLASH, l.clone().add(0, 1, 0), 1);
        Compat.spawn(world(), Compat.ELECTRIC_SPARK, l.clone().add(0, 1, 0), 60, 0.6, 1.0, 0.6, 0.3);
        Compat.spawn(world(), Compat.END_ROD, l.clone().add(0, 1, 0), 40, 0.5, 1.0, 0.5, 0.1);
        soundAt(l, "entity.lightning_bolt.thunder", 1.8f, 1.2f);
        soundAt(l, "block.beacon.activate", 1.6f, 0.6f);

        titleNear(Component.text("CONCENTRACION", NamedTextColor.WHITE, TextDecoration.BOLD),
                Component.text("Pega cinco veces mas fuerte", NamedTextColor.GRAY));
        Compat.apply(boss, "speed", 300, 1);
        Compat.apply(boss, "resistance", 300, 0);
    }

    private void endConcentration() {
        concentrated = false;
        if (!alive()) return;
        glowBody(null);
        soundAt(loc(), "block.beacon.deactivate", 1.2f, 0.7f);
        broadcastNear(Component.text("Suelta el aire.", ACCENT));
    }

    /**
     * 10. Carga Devastadora: se traga las estelas de media arena y lo suelta todo.
     *
     * Las lineas que convergen son las mismas que salen del dragon al morir —el propio
     * juego las usa para "esto se esta concentrando aqui"—, y por eso se leen sin que
     * nadie explique nada. Ocho segundos de aviso, un circulo enorme, y quien se quede
     * dentro sin buen equipo no lo cuenta.
     */
    public void devastatingCharge() {
        if (!alive() || !angry) return;
        final Location center = boss.getLocation().clone();
        busyFor(180);

        titleNear(Component.text("SE ESTA CARGANDO", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Salgan de ahi, en serio", NamedTextColor.GRAY));
        soundAt(center, "entity.ender_dragon.growl", 1.6f, 1.4f);

        animate(170, tick -> {
            if (!alive()) throw Stop.now();
            Location core = boss.getEyeLocation();
            double t = tick / 170.0;

            // Las estelas: motas que vienen de lejos y se meten dentro de el.
            for (int i = 0; i < 3; i++) {
                double a = random.nextDouble() * Math.PI * 2;
                double d = 12 + random.nextDouble() * 10;
                Location far = core.clone().add(Math.cos(a) * d, random.nextDouble() * 6 - 2, Math.sin(a) * d);
                Fx.beam(far, core, 1.6, p ->
                        Compat.spawn(world(), Compat.REVERSE_PORTAL, p, 1, 0.02, 0.02, 0.02, 0.01));
            }
            Compat.spawn(world(), Compat.REVERSE_PORTAL, core, 6, 1.2, 1.2, 1.2, 0.35);
            Compat.spawn(world(), Compat.END_ROD, core, (int) (2 + t * 6), 0.3, 0.3, 0.3, 0.02);
            Fx.sphere(core, 0.8 + t * 1.8, (int) (10 + t * 30), p ->
                    Compat.spawn(world(), Compat.ELECTRIC_SPARK, p, 1, 0, 0, 0, 0));

            if (tick % 20 == 0) {
                int left = (170 - tick) / 20;
                soundAt(core, "block.beacon.power_select", 1.4f, 0.4f + (float) t * 1.2f);
                for (Player p : Fx.viewersNear(core, 60)) {
                    boolean safe = p.getLocation().distance(center) > 16;
                    p.sendActionBar(Component.text("Rabby se carga  ", NamedTextColor.GRAY)
                            .append(Component.text(left + "s", ACCENT, TextDecoration.BOLD))
                            .append(Component.text(safe ? "   estas lejos" : "   ESTAS DENTRO",
                                    safe ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD)));
                }
            }
        }, () -> {
            if (!alive()) return;
            Location core = boss.getLocation();
            Compat.spawn(world(), Compat.FLASH, core.clone().add(0, 1, 0), 1);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, core.clone().add(0, 1, 0), 6);
            Compat.spawn(world(), Compat.DRAGON_BREATH, core.clone().add(0, 1, 0), 120, 3.0, 1.5, 3.0, 0.2);
            Compat.spawn(world(), Compat.GUST_EMITTER_LARGE, core.clone().add(0, 1, 0), 3, 1.0, 1.0, 1.0, 0);
            soundAt(core, "entity.generic.explode", 2.0f, 0.3f);
            soundAt(core, "entity.ender_dragon.death", 1.4f, 1.5f);

            for (int r = 1; r <= 8; r++) {
                final int ring = r;
                later(r * 2, () -> Fx.ring(core, ring * 2.0, ring * 10, p ->
                        Compat.spawn(world(), Compat.END_ROD, Fx.ground(p, 4).add(0, 0.4, 0), 1,
                                0.05, 0.05, 0.05, 0.02)));
            }
            for (Player p : Fx.playersNear(core, 16)) {
                double d = p.getLocation().distance(core);
                // A bocajarro es una barbaridad; en el borde, un susto muy serio.
                hit(p, Math.max(20, 75 - d * 2.5) * power());
                push(p, p.getLocation().toVector().subtract(core.toVector())
                        .normalize().multiply(2.0).setY(1.0));
            }
        });
    }

    /** 11. Paso Relampago: aparece detras del que mas se aleja y lo devuelve al grupo. */
    public void lightningStep() {
        if (!alive() || !angry) return;
        Player target = Fx.farthest(loc(), plugin.settings().participationRadius());
        if (target == null) return;

        Compat.spawn(world(), Compat.PORTAL, boss.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.1);
        Location behind = target.getLocation().clone().add(
                target.getLocation().getDirection().setY(0).normalize().multiply(-1.5));
        boss.teleport(behind);
        face(target.getEyeLocation());
        Compat.spawn(world(), Compat.FLASH, behind.clone().add(0, 1, 0), 1);
        soundAt(behind, "entity.enderman.teleport", 1.5f, 0.9f);
        target.sendActionBar(Component.text("Huir no era una opcion.",
                NamedTextColor.RED, TextDecoration.BOLD));

        later(8, () -> {
            if (!alive() || !Fx.isFightable(target)) return;
            hit(target, 14 * power());
            Vector back = arena.toVector().subtract(target.getLocation().toVector()).setY(0);
            if (back.lengthSquared() < 0.01) back = new Vector(1, 0, 0);
            push(target, back.normalize().multiply(1.5).setY(0.5));
            Compat.spawn(world(), Compat.CRIT, target.getLocation().add(0, 1, 0), 20, 0.3, 0.4, 0.3, 0.3);
            soundAt(target.getLocation(), "entity.player.attack.crit", 1.5f, 0.9f);
        });
    }

    /** 12. Onda Expansiva: un golpe al suelo que barre la arena entera. */
    public void shockwave() {
        if (!alive() || !angry) return;
        Location c = Fx.ground(boss.getLocation(), 4);
        soundAt(c, "entity.player.attack.strong", 1.6f, 0.6f);
        broadcastNear(Component.text("Parte el suelo de un puñetazo.", ACCENT));
        java.util.Set<UUID> swept = new java.util.HashSet<>();

        animate(60, tick -> {
            if (tick < 16) {
                Fx.telegraph(world(), c, 16.0, SPARK);
                return;
            }
            double radius = (tick - 16) * 0.55;
            if (radius > 16) return;
            Fx.ring(c, radius, (int) (radius * 6) + 8, p -> {
                Location g = Fx.ground(p, 4);
                Compat.spawn(world(), Compat.SMALL_GUST, g.clone().add(0, 0.3, 0), 1, 0, 0, 0, 0);
                Compat.spawn(world(), Compat.DUST_PILLAR, g.clone().add(0, 0.1, 0), 1, 0.1, 0.05, 0.1, 0,
                        Material.STONE.createBlockData());
            });
            if (tick % 8 == 0) soundAt(c, "entity.warden.sonic_boom", 0.9f, 1.4f);
            for (Player p : targets(radius + 1.2)) {
                if (p.getLocation().distance(c) < radius - 1.6) continue;
                if (!swept.add(p.getUniqueId())) continue;
                hit(p, 15 * power());
                push(p, p.getLocation().toVector().subtract(c.toVector())
                        .normalize().multiply(1.4).setY(0.85));
            }
        }, null);
    }

    /** 13. Burla: se rie, se estira, y se pone todavia mas rapido. */
    public void taunt() {
        if (!alive() || !angry) return;
        Location l = boss.getLocation();
        soundAt(l, "entity.player.attack.nodamage", 1.4f, 1.5f);
        broadcastNear(Component.text("Se esta riendo de ustedes.", ACCENT));
        Compat.apply(boss, "speed", 160, 2);
        Compat.apply(boss, "strength", 160, 0);
        Compat.spawn(world(), Compat.FIREWORK_SPARK, l.clone().add(0, 2.2, 0), 14, 0.4, 0.3, 0.4, 0.06);
        Compat.spawn(world(), Compat.ELECTRIC_SPARK, l.clone().add(0, 2.2, 0), 10, 0.4, 0.3, 0.4, 0.02);

        for (Player p : targets(12)) {
            Compat.apply(p, "slowness", 60, 0);
            p.sendActionBar(Component.text("Rabby se rie en tu cara.", ACCENT, TextDecoration.BOLD));
        }
    }

    /** 14. Tromba Final: tres embestidas seguidas por toda la arena, sin respirar. */
    public void finalRush() {
        if (!alive() || !angry) return;
        broadcastNear(Component.text("No piensa parar.", ACCENT));
        List<Player> pool = new ArrayList<>(targets(30));
        if (pool.isEmpty()) return;

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            later(i * 26, () -> {
                if (!alive()) return;
                Player p = pool.get(idx % pool.size());
                if (!Fx.isFightable(p)) return;
                Vector run = p.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
                if (run.lengthSquared() < 0.01) return;
                final Vector dir = run.normalize();
                java.util.Set<UUID> hitSet = new java.util.HashSet<>();
                soundAt(boss.getLocation(), "entity.player.attack.sweep", 1.4f, 1.2f);
                animate(22, tick -> {
                    if (!alive()) throw Stop.now();
                    boss.setVelocity(dir.clone().multiply(1.35).setY(boss.getVelocity().getY()));
                    Compat.spawn(world(), Compat.WHITE_SMOKE, boss.getLocation().add(0, 1, 0), 3,
                            0.2, 0.3, 0.2, 0.01);
                    for (Player v : Fx.playersNear(boss.getLocation(), 2.2)) {
                        if (!hitSet.add(v.getUniqueId())) continue;
                        hit(v, 12 * power());
                        push(v, dir.clone().multiply(1.3).setY(0.6));
                        Compat.spawn(world(), Compat.CRIT, v.getLocation().add(0, 1, 0), 12, 0.3, 0.3, 0.3, 0.25);
                    }
                }, null);
            });
        }
    }

    // ------------------------------------------------------------------ mensajeria

    private void broadcastNear(Component message) {
        Component line = Component.text("✦ ", ACCENT)
                .append(Component.text("Rabby  ", ACCENT, TextDecoration.BOLD))
                .append(message.colorIfAbsent(NamedTextColor.GRAY));
        for (Player p : Fx.viewersNear(loc(), 90)) p.sendActionBar(line);
    }

    private void titleNear(Component title, Component subtitle) {
        for (Player p : Fx.viewersNear(loc(), 90)) {
            p.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1400), Duration.ofMillis(500))));
        }
    }
}
