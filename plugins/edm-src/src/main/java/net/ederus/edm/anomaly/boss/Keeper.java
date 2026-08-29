package net.ederus.edm.anomaly.boss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.core.ActiveAnomaly;
import net.ederus.edm.anomaly.core.Compat;
import net.ederus.edm.anomaly.core.Fx;
import net.ederus.edm.anomaly.core.Glow;
import net.ederus.edm.anomaly.core.Stop;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Warden;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * KEEPER, el Monarca del Silencio.
 *
 * Un warden al DOBLE de tamano que pelea a CUATRO fases: el Centinela, el Excavador,
 * la Voz del Abismo y el Monarca del Silencio. Todo su repertorio es eco, sculk y
 * oscuridad: bramidos sonicos que atraviesan, estructuras de sculk que hay que romper,
 * mareas que se expanden por el suelo y un cataclismo final que solo perdona a quien
 * se agache y no se mueva, porque a KEEPER no se le escapa nada que vibre.
 *
 * Sus cuatro costumbres (pasivas, no salen en la lista de habilidades):
 *  - Corazon Sismico: el latido se oye siempre y se acelera con menos vida.
 *  - Piel de Eco: es ciego; reparte su rabia por VIBRACIONES entre los que corren,
 *    saltan o pegan. Agacharse y quedarse quieto baja tu prioridad.
 *  - Manto Sculk: pisar a menos de seis bloques de el frena los pies.
 *  - Oscuridad Latente: el aura de oscuridad del warden de siempre, intacta.
 *
 * Nada de lo que "construye" toca un solo bloque del mundo: chirriadores, sensores,
 * catalizadores y jaulas son soportes y displays marcados que la limpieza final se
 * lleva enteros. En un coliseo no deja ni una mancha.
 */
public final class Keeper extends BossFight {

    public static final String ID = "keeper";
    /** Cian sculk: el color de marca del Monarca. */
    public static final TextColor ACCENT = TextColor.color(0x2CE0F0);

    /** Cian profundo para telegrafias. */
    private static final int DEEP = 0x0B4A52;
    /** El verdeazul de las almas del pecho. */
    private static final int SOUL_TEAL = 0x1FA8B8;

    /** Donde estaba cada jugador hace medio segundo: asi se "oyen" las vibraciones. */
    private final Map<UUID, Location> lastSeen = new HashMap<>();
    /** Posiciones congeladas para el juicio del Cataclismo: quieto o boom. */
    private final Map<UUID, Location> stillCheck = new HashMap<>();
    /** Chirriadores, sensores y catalizadores vivos; la Detonacion los cobra. */
    private final List<ArmorStand> fixtures = new ArrayList<>();

    /** El Abrazo del Abismo: mientras esta activo, el dano al jefe libera al preso. */
    private boolean embraceActive;
    private double embraceDamage;

    public Keeper(AnomalyPlugin plugin, ActiveAnomaly event, Location arena) {
        super(plugin, event, arena);
        abilities.addAll(plugin.registry().keeperAbilities());
    }

    @Override
    public String bossName() {
        return "KEEPER";
    }

    /** El unico Monarca que pelea a CUATRO fases; las barras y el menu le siguen. */
    @Override
    public int phaseCount() {
        return 4;
    }

    @Override
    public void spawn() {
        Location at = arena.clone();
        boss = world().spawn(at, Warden.class, w -> {
            w.setPersistent(false);
            // El DOBLE de warden. A 2.0 pisa el suelo sin flotar; mas alla empieza
            // el problema de la caja de golpe que ya nos costo con Aragon.
            Compat.setAttribute(w, "scale", 2.0);
            // El melee vanilla del warden es una burrada (30): se baja a algo que un
            // grupo con equipo pueda encajar. El dano de las habilidades va aparte.
            Compat.setAttribute(w, "attack_damage", 14);
            Compat.setAttribute(w, "movement_speed", 0.32);
            Compat.setAttribute(w, "knockback_resistance", 1.0);
            w.customName(Component.text("KEEPER", ACCENT, TextDecoration.BOLD));
            w.setCustomNameVisible(false);
        });
        net.ederus.edm.anomaly.core.Tags.markBoss(boss, ID);
        applyHealth(plugin.registry().scaledHealth(plugin.registry().get(ID), targets(96).size()));
        glowBody(NamedTextColor.AQUA);

        // La salida del abismo: el suelo "hierve" de sculk y el latido arranca.
        Compat.spawn(world(), Compat.SCULK_CHARGE, at.clone().add(0, 0.3, 0), 60, 2.2, 0.4, 2.2, 0.06);
        Compat.spawn(world(), Compat.SCULK_SOUL, at.clone().add(0, 1.5, 0), 40, 1.0, 1.5, 1.0, 0.05);
        Fx.ring(at.clone().add(0, 0.2, 0), 4.0, 26, p ->
                Compat.spawn(world(), Compat.DUST, p, 2, 0.1, 0.1, 0.1, 0, Compat.dust(DEEP, 1.8f)));
        soundAt(at, "entity.warden.emerge", 1.8f, 0.7f);
        soundAt(at, "entity.warden.roar", 1.6f, 0.6f);
    }

    // ------------------------------------------------------------------- pasivas

    @Override
    protected void ambient() {
        if (!alive()) return;

        // Corazon Sismico: el latido marca el ritmo y se acelera al perder vida.
        int beat = 12 + (int) (28 * healthFraction());
        if (ticks() % Math.max(6, beat) == 0) {
            soundAt(loc(), "entity.warden.heartbeat", 1.7f, (float) (0.8 + (1 - healthFraction()) * 0.5));
            Compat.spawn(world(), Compat.SCULK_SOUL, center().add(0, 0.4, 0), 3, 0.35, 0.5, 0.35, 0.02);
        }

        // Manto Sculk: el suelo alrededor frena los pies. Solo particulas y efecto:
        // ni un bloque real, para que un coliseo quede igual que estaba.
        if (ticks() % 20 == 0) {
            for (Player p : targets(6)) {
                Compat.apply(p, "slowness", 40, 0);
            }
        }
        if (ticks() % 40 == 0) {
            Fx.ring(Fx.ground(loc(), 4).add(0, 0.15, 0), 6.0, 20, ticks() * 0.05, p ->
                    Compat.spawn(world(), Compat.SCULK_CHARGE_POP, Fx.ground(p, 3).add(0, 0.15, 0), 1,
                            0.05, 0.05, 0.05, 0));
        }

        // Piel de Eco: cada segundo y medio revisa quien vibra mas fuerte y a ese va.
        if (ticks() % 30 == 0) {
            driveEcho();
        }
    }

    /**
     * El reparto de la rabia por vibraciones. Correr, saltar o pegar te sube en la
     * lista; agacharte y quedarte quieto te baja. Ademas mantiene al warden con ira:
     * un warden calmado se entierra solo, y este no tiene permiso para irse.
     */
    private void driveEcho() {
        List<Player> pool = targets();
        if (pool.isEmpty()) return;
        Player loudest = null;
        double best = -1e9;
        for (Player p : pool) {
            double score = 40 - p.getLocation().distance(loc());
            Location was = lastSeen.get(p.getUniqueId());
            if (was != null && was.getWorld() == p.getWorld()
                    && was.distanceSquared(p.getLocation()) > 0.35) {
                score += 14;
            }
            if (p.isSneaking()) score -= 18;
            score += random.nextDouble() * 6;
            if (score > best) {
                best = score;
                loudest = p;
            }
            lastSeen.put(p.getUniqueId(), p.getLocation().clone());
        }
        if (loudest == null || !(boss instanceof Warden w)) return;
        try {
            w.increaseAnger(loudest, 60);
        } catch (Throwable ignored) {
        }
        try {
            w.setTarget(loudest);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPhaseChange(int from, int to) {
        if (!alive()) return;
        Location l = center();
        switch (to) {
            case 2 -> {
                titleNear(Component.text("EL EXCAVADOR", ACCENT, TextDecoration.BOLD),
                        Component.text("El suelo ya no es tuyo", NamedTextColor.GRAY));
                soundAt(l, "entity.warden.dig", 1.7f, 0.8f);
                Compat.spawn(world(), Compat.BLOCK, Fx.ground(l, 3), 50, 1.6, 0.4, 1.6, 0.1,
                        Material.SCULK.createBlockData());
            }
            case 3 -> {
                titleNear(Component.text("LA VOZ DEL ABISMO", ACCENT, TextDecoration.BOLD),
                        Component.text("Ahora canta el", NamedTextColor.GRAY));
                soundAt(l, "entity.warden.roar", 1.8f, 0.5f);
                Compat.spawn(world(), Compat.SHRIEK, l.clone().add(0, 1.5, 0), 6, 1.2, 1.0, 1.2, 0);
            }
            case 4 -> {
                titleNear(Component.text("EL MONARCA DEL SILENCIO", ACCENT, TextDecoration.BOLD),
                        Component.text("Se acabaron los avisos por sonido", NamedTextColor.GRAY));
                soundAt(l, "entity.warden.sonic_boom", 1.8f, 0.5f);
                soundAt(l, "entity.wither.spawn", 0.8f, 0.6f);
                for (Player p : targets()) {
                    Compat.apply(p, "darkness", 80, 0);
                }
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------- FASE I: el Centinela

    /** 1. Bramido Sonico: la linea perforante que atraviesa a todos los que cruce. */
    public void sonicRoar() {
        if (!alive()) return;
        Player aim = randomTarget();
        if (aim == null) return;
        face(aim.getLocation());
        final Location from = center();
        final Vector dir = aim.getLocation().add(0, 1, 0).toVector().subtract(from.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        dir.normalize();

        soundAt(from, "entity.warden.sonic_charge", 1.7f, 0.9f);
        animate(45, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < 25) {
                // se le abre el pecho: la carga se ve venir
                Compat.spawn(world(), Compat.SCULK_CHARGE, center(), 3, 0.3, 0.5, 0.3, 0.02);
                for (double d = 2; d < 24; d += 2.5) {
                    Location g = Fx.ground(from.clone().add(dir.clone().multiply(d)), 4);
                    Compat.spawn(world(), Compat.DUST, g.clone().add(0, 0.15, 0), 1, 0.3, 0, 0.3, 0,
                            Compat.dust(DEEP, 1.5f));
                }
                return;
            }
            if (tick != 25) return;
            soundAt(from, "entity.warden.sonic_boom", 1.8f, 1.0f);
            sonicLine(center(), dir, 24, 1.8, 12);
        }, null);
    }

    /** 2. Abanico de Ecos: cinco bramidos a la vez, uno por cabeza cercana. */
    public void echoFan() {
        if (!alive()) return;
        List<Player> marks = nearestTargets(5);
        if (marks.isEmpty()) return;
        soundAt(center(), "entity.warden.sonic_charge", 1.6f, 0.7f);
        warn(Component.text("KEEPER carga un abanico de ecos.", ACCENT));

        animate(55, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < 30) {
                Compat.spawn(world(), Compat.SCULK_CHARGE, center(), 4, 0.4, 0.6, 0.4, 0.03);
                if (tick % 10 == 0) soundAt(center(), "block.sculk_sensor.clicking", 1.2f, 0.6f);
                return;
            }
            if (tick != 30) return;
            soundAt(center(), "entity.warden.sonic_boom", 1.8f, 0.8f);
            for (Player m : marks) {
                if (!Fx.isFightable(m)) continue;
                Vector dir = m.getLocation().add(0, 1, 0).toVector().subtract(center().toVector());
                if (dir.lengthSquared() < 0.01) continue;
                sonicLine(center(), dir.normalize(), Math.min(24, dir.length() + 4), 1.4, 10);
            }
        }, null);
    }

    /** 3. Pisoton Sismico: la onda radial que barre ocho bloques y empuja. */
    public void seismicStomp() {
        if (!alive()) return;
        Location c = Fx.ground(loc(), 4);
        java.util.Set<UUID> struck = new java.util.HashSet<>();
        soundAt(c, "entity.warden.attack_impact", 1.6f, 0.5f);
        warn(Component.text("KEEPER levanta la pata.", ACCENT));

        animate(60, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < 22) {
                Fx.telegraph(world(), c, 8.0, DEEP);
                return;
            }
            double radius = (tick - 22) * 0.55;
            if (radius > 8) return;
            Fx.shockwave(world(), c, radius, Compat.SCULK_CHARGE_POP, 5);
            if (tick == 22) {
                soundAt(c, "entity.warden.attack_impact", 1.8f, 0.4f);
                soundAt(c, "block.sculk_shrieker.shriek", 1.3f, 0.5f);
            }
            for (Player p : Fx.playersNear(c, radius + 1.0)) {
                if (p.getLocation().distance(c) < radius - 1.4) continue;
                if (!struck.add(p.getUniqueId())) continue;
                hit(p, 11);
                push(p, p.getLocation().toVector().subtract(c.toVector()).normalize().setY(0.5).multiply(0.9));
            }
        }, null);
    }

    /** 4. Garra Resonante: el zarpazo al que mas vibra. El unico golpe "para uno". */
    public void resonantClaw() {
        if (!alive()) return;
        Player target = Fx.nearest(loc(), 6);
        if (target == null) return;
        face(target.getLocation());
        soundAt(loc(), "entity.warden.attack_impact", 1.5f, 0.8f);
        later(10, () -> {
            if (!alive() || !Fx.isFightable(target)) return;
            if (boss.getLocation().distanceSquared(target.getLocation()) > 36) return;
            hit(target, 16);
            push(target, target.getLocation().toVector().subtract(loc().toVector())
                    .normalize().multiply(1.0).setY(0.45));
            Compat.spawn(world(), Compat.SCULK_CHARGE_POP, target.getLocation().add(0, 1, 0), 20,
                    0.4, 0.5, 0.4, 0.1);
            soundAt(target.getLocation(), "entity.player.attack.crit", 1.4f, 0.6f);
        });
    }

    /** 5. Chirrido Convocador: chirriadores que gritan oscuridad hasta que los rompan. */
    public void shriekerCall() {
        if (!alive()) return;
        int count = 2 + random.nextInt(3);
        warn(Component.text("Planta chirriadores. ROMPANLOS.", ACCENT));
        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count + random.nextDouble() * 0.5;
            double d = 5 + random.nextDouble() * 6;
            Location spot = Fx.ground(loc().clone().add(Math.cos(a) * d, 1, Math.sin(a) * d), 5);
            plantShrieker(spot);
        }
    }

    /** Un chirriador: soporte golpeable que grita Oscuridad cada tres segundos. */
    private void plantShrieker(Location spot) {
        ArmorStand stand = fixture(spot, Material.SCULK_SHRIEKER, "Chirriador", 3);
        soundAt(spot, "block.sculk_shrieker.place", 1.3f, 0.8f);

        animate(400, tick -> {
            if (!stand.isValid() || !plugin.anchors().isAnchor(stand)) throw Stop.now();
            if (tick % 60 != 0) return;
            Location l = stand.getLocation().add(0, 1.6, 0);
            Compat.spawn(world(), Compat.SHRIEK, l, 1, 0, 0, 0, 0);
            soundAt(l, "block.sculk_shrieker.shriek", 1.4f, 1.0f);
            for (Player p : Fx.playersNear(l, 8)) {
                Compat.apply(p, "darkness", 70, 0);
            }
        }, () -> removeFixture(stand));
    }

    /** 6. Sensores Trampa: pisar cerca de uno sin agacharse lo detona. */
    public void sensorTraps() {
        if (!alive()) return;
        int count = 4 + random.nextInt(3);
        warn(Component.text("Siembra sensores: pasen AGACHADOS.", ACCENT));
        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count + random.nextDouble() * 0.6;
            double d = 4 + random.nextDouble() * 9;
            Location spot = Fx.ground(loc().clone().add(Math.cos(a) * d, 1, Math.sin(a) * d), 5);
            plantSensor(spot);
        }
    }

    /** Un sensor: vibra con los pasos y revienta con quien no vaya agachado. */
    private void plantSensor(Location spot) {
        ArmorStand stand = fixture(spot, Material.SCULK_SENSOR, "Sensor", 2);
        soundAt(spot, "block.sculk_sensor.place", 1.2f, 0.9f);

        animate(500, tick -> {
            if (!stand.isValid() || !plugin.anchors().isAnchor(stand)) throw Stop.now();
            Location l = stand.getLocation();
            if (tick % 25 == 0) {
                Compat.spawn(world(), Compat.SCULK_CHARGE, l.clone().add(0, 1.4, 0), 2, 0.2, 0.2, 0.2, 0.01);
            }
            if (tick % 4 != 0) return;
            for (Player p : Fx.playersNear(l, 2.6)) {
                if (p.isSneaking()) continue;
                removeFixture(stand);
                Compat.spawn(world(), Compat.SHRIEK, l.clone().add(0, 1.2, 0), 2, 0.2, 0.2, 0.2, 0);
                Compat.spawn(world(), Compat.EXPLOSION, l.clone().add(0, 1, 0), 2, 0.3, 0.3, 0.3, 0);
                soundAt(l, "block.sculk_sensor.clicking_stop", 1.6f, 0.5f);
                soundAt(l, "entity.generic.explode", 1.2f, 1.3f);
                for (Player v : Fx.playersNear(l, 3.0)) {
                    hit(v, 10);
                    Compat.apply(v, "darkness", 60, 0);
                }
                throw Stop.now();
            }
        }, () -> removeFixture(stand));
    }

    // ------------------------------------------------------ FASE II: el Excavador

    /** 7. Inmersion: se entierra y emerge bajo un jugador con erupcion. */
    public void submerge() {
        if (!alive()) return;
        Player target = randomTarget();
        if (target == null) return;
        digAndEmerge(target, 0);
    }

    /** 8. Tuneles del Abismo: tres emersiones seguidas bajo jugadores DISTINTOS. */
    public void abyssTunnels() {
        if (!alive()) return;
        List<Player> marks = pickTargets(3);
        if (marks.isEmpty()) return;
        warn(Component.text("KEEPER se mueve por debajo.", ACCENT));
        for (int i = 0; i < marks.size(); i++) {
            Player m = marks.get(i);
            later(i * 55, () -> digAndEmerge(m, 1));
        }
    }

    /**
     * Un viaje bajo tierra: se hunde con su animacion de excavar, la marca aparece
     * bajo el objetivo y KEEPER revienta el suelo al salir. La invulnerabilidad dura
     * lo que el viaje, que es lo que el vigilante de BossFight tolera de sobra.
     *
     * @param mode 0 = entierro largo con aviso; 1 = tunel rapido (para la cadena)
     */
    private void digAndEmerge(Player target, int mode) {
        if (!alive() || target == null || !Fx.isFightable(target)) return;
        final int digTime = mode == 0 ? 22 : 12;
        final Location downAt = loc().clone();
        soundAt(downAt, "entity.warden.dig", 1.7f, 0.9f);
        Compat.spawn(world(), Compat.BLOCK, Fx.ground(downAt, 3), 40, 1.0, 0.4, 1.0, 0.1,
                Material.SCULK.createBlockData());
        boss.setInvisible(true);
        boss.setInvulnerable(true);
        boss.setSilent(true);

        animate(digTime + 24, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < digTime) {
                Compat.spawn(world(), Compat.BLOCK, Fx.ground(boss.getLocation(), 3), 8, 0.8, 0.2, 0.8, 0.05,
                        Material.SCULK.createBlockData());
                return;
            }
            Location mark = Fx.ground(target.getLocation(), 5);
            if (tick < digTime + 22) {
                Fx.telegraph(world(), mark, 3.6, DEEP);
                if (tick % 6 == 0) {
                    soundAt(mark, "block.sculk.spread", 1.2f, 0.6f);
                    Compat.spawn(world(), Compat.BLOCK, mark.clone().add(0, 0.2, 0), 10, 1.2, 0.15, 1.2, 0.04,
                            Material.SCULK.createBlockData());
                }
                return;
            }
            // La salida: el suelo revienta y KEEPER esta donde estabas tu.
            boss.teleport(mark);
            boss.setInvisible(false);
            boss.setInvulnerable(false);
            boss.setSilent(false);
            soundAt(mark, "entity.warden.emerge", 1.8f, 0.8f);
            Compat.spawn(world(), Compat.BLOCK, mark.clone().add(0, 0.6, 0), 60, 1.4, 0.8, 1.4, 0.15,
                    Material.SCULK.createBlockData());
            Compat.spawn(world(), Compat.SCULK_CHARGE, mark.clone().add(0, 0.5, 0), 30, 1.2, 0.6, 1.2, 0.05);
            for (Player p : Fx.playersNear(mark, 4.0)) {
                hit(p, 15);
                push(p, p.getLocation().toVector().subtract(mark.toVector()).normalize()
                        .multiply(0.9).setY(0.65));
            }
            throw Stop.now();
        }, () -> {
            // Red de seguridad: pase lo que pase, nunca se queda enterrado ni intocable.
            if (alive()) {
                boss.setInvisible(false);
                boss.setInvulnerable(false);
                boss.setSilent(false);
            }
        });
    }

    /** 9. Tentaculos de Sculk: brotan bajo varios a la vez y los anclan al suelo. */
    public void sculkTendrils() {
        if (!alive()) return;
        List<Player> marks = pickTargets(4);
        if (marks.isEmpty()) return;
        soundAt(loc(), "block.sculk_vein.place", 1.5f, 0.5f);
        warn(Component.text("El suelo agarra por los tobillos.", ACCENT));

        for (Player m : marks) {
            Location at = Fx.ground(m.getLocation(), 4);
            animate(60, tick -> {
                if (tick < 14) {
                    Fx.telegraph(world(), at, 1.6, DEEP);
                    return;
                }
                if (tick == 14) {
                    if (!Fx.isFightable(m) || m.getLocation().distanceSquared(at) > 9) throw Stop.now();
                    hit(m, 6);
                    root(m, 50);
                    soundAt(at, "block.sculk_vein.break", 1.4f, 0.6f);
                    m.sendActionBar(Component.text("El sculk te tiene agarrado.",
                            NamedTextColor.RED, TextDecoration.BOLD));
                }
                Location feet = m.getLocation();
                Fx.ring(feet.clone().add(0, 0.15, 0), 0.7, 8, tick * 0.4, p ->
                        Compat.spawn(world(), Compat.SCULK_CHARGE_POP, p, 1, 0, 0.1, 0, 0));
            }, null);
        }
    }

    /** 10. Catalizador Voraz: drena a los cercanos y CURA al jefe. Prioridad romperlo. */
    public void voraciousCatalyst() {
        if (!alive()) return;
        double a = random.nextDouble() * Math.PI * 2;
        Location spot = Fx.ground(loc().clone().add(Math.cos(a) * 6, 1, Math.sin(a) * 6), 5);
        ArmorStand stand = fixture(spot, Material.SCULK_CATALYST, "Catalizador Voraz", 4);
        soundAt(spot, "block.sculk_catalyst.bloom", 1.5f, 0.6f);
        warn(Component.text("Planta un catalizador: LE CURA. Rompanlo.", ACCENT));

        animate(300, tick -> {
            if (!stand.isValid() || !plugin.anchors().isAnchor(stand)) throw Stop.now();
            Location l = stand.getLocation().add(0, 1.4, 0);
            Fx.ring(l, 0.8, 8, tick * 0.3, p ->
                    Compat.spawn(world(), Compat.SCULK_SOUL, p, 1, 0, 0, 0, 0.01));
            if (tick % 20 != 0) return;
            boolean fed = false;
            for (Player p : Fx.playersNear(l, 6)) {
                hit(p, 2);
                fed = true;
                Fx.beam(p.getLocation().add(0, 1, 0), l, 0.7, b ->
                        Compat.spawn(world(), Compat.SCULK_SOUL, b, 1, 0.02, 0.02, 0.02, 0.01));
            }
            if (fed && alive()) {
                double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
                boss.setHealth(Math.min(max, boss.getHealth() + 3));
                Compat.spawn(world(), Compat.SCULK_CHARGE, center(), 6, 0.4, 0.6, 0.4, 0.03);
            }
        }, () -> removeFixture(stand));
    }

    /** 11. Marea Sculk: la ola que se expande por el suelo y pega en el frente. */
    public void sculkTide() {
        if (!alive()) return;
        Location c = Fx.ground(loc(), 4);
        java.util.Set<UUID> soaked = new java.util.HashSet<>();
        soundAt(c, "block.sculk.spread", 1.7f, 0.5f);
        warn(Component.text("La marea sale de el.", ACCENT));

        animate(80, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < 16) {
                Compat.spawn(world(), Compat.SCULK_CHARGE, c.clone().add(0, 0.3, 0), 6, 1.0, 0.2, 1.0, 0.02);
                return;
            }
            double radius = 2 + (tick - 16) * 0.18;
            if (radius > 12) return;
            int points = Math.max(14, (int) (radius * 6));
            Fx.ring(c, radius, points, p -> {
                Location g = Fx.ground(p, 3);
                Compat.spawn(world(), Compat.SCULK_CHARGE_POP, g.clone().add(0, 0.2, 0), 1, 0.1, 0.05, 0.1, 0);
                if (random.nextInt(4) == 0) {
                    Compat.spawn(world(), Compat.DUST, g.clone().add(0, 0.3, 0), 1, 0.1, 0.1, 0.1, 0,
                            Compat.dust(SOUL_TEAL, 1.6f));
                }
            });
            for (Player p : Fx.playersNear(c, radius + 1.0)) {
                if (p.getLocation().distance(c) < radius - 1.4) continue;
                if (!soaked.add(p.getUniqueId())) continue;
                hit(p, 12);
                Compat.apply(p, "slowness", 60, 1);
            }
        }, null);
    }

    /** 12. Latigazo del Abismo: el barrido en cono, apuntado al objetivo y no al aire. */
    public void abyssLash() {
        if (!alive()) return;
        Player aim = Fx.nearest(loc(), 10);
        if (aim == null) return;
        face(aim.getLocation());
        final Vector dir = aim.getLocation().toVector().subtract(loc().toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        dir.normalize();
        soundAt(loc(), "entity.warden.attack_impact", 1.5f, 0.6f);

        animate(45, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < 20) {
                Fx.arc(loc().clone().add(0, 0.2, 0), dir, 4.0, Math.toRadians(120), 14, p ->
                        Compat.spawn(world(), Compat.DUST, Fx.ground(p, 3).add(0, 0.15, 0), 1, 0.1, 0, 0.1, 0,
                                Compat.dust(DEEP, 1.5f)));
                return;
            }
            if (tick != 20) return;
            soundAt(loc(), "entity.warden.attack_impact", 1.7f, 0.45f);
            for (double r = 1.5; r <= 5.0; r += 1.1) {
                Fx.arc(loc().clone().add(0, 1.0, 0), dir, r, Math.toRadians(120), 10, p ->
                        Compat.spawn(world(), Compat.SCULK_CHARGE_POP, p, 2, 0.15, 0.2, 0.15, 0.02));
            }
            for (Player p : targets(5.5)) {
                Vector to = p.getLocation().toVector().subtract(loc().toVector()).setY(0);
                if (to.lengthSquared() > 0.01 && to.normalize().dot(dir) < 0.5) continue;
                hit(p, 13);
                push(p, to.normalize().multiply(0.8).setY(0.35));
            }
        }, null);
    }

    // -------------------------------------------------- FASE III: la Voz del Abismo

    /** 13. Coro de Alaridos: el grito global que oscurece y empuja a toda la arena. */
    public void shriekChorus() {
        if (!alive()) return;
        soundAt(loc(), "entity.warden.roar", 1.8f, 0.6f);
        warn(Component.text("KEEPER toma aire.", ACCENT));

        animate(70, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < 34) {
                Compat.spawn(world(), Compat.SHRIEK, center().add(0, 1.0, 0), 1, 0.4, 0.4, 0.4, 0);
                if (tick % 8 == 0) soundAt(loc(), "block.sculk_shrieker.shriek", 1.2f, 0.5f + tick / 60f);
                return;
            }
            if (tick != 34) return;
            soundAt(loc(), "entity.warden.sonic_boom", 1.8f, 0.4f);
            for (Player p : targets()) {
                hit(p, 8);
                Compat.apply(p, "darkness", 120, 1);
                push(p, p.getLocation().toVector().subtract(loc().toVector()).normalize()
                        .multiply(1.1).setY(0.4));
                Compat.spawn(world(), Compat.SHRIEK, p.getLocation().add(0, 2.4, 0), 1, 0, 0, 0, 0);
            }
        }, null);
    }

    /** 14. Bramido Orbital: cuatro brazos sonicos girando dos vueltas completas. */
    public void orbitalRoar() {
        if (!alive()) return;
        soundAt(loc(), "entity.warden.sonic_charge", 1.7f, 0.6f);
        warn(Component.text("Los ecos empiezan a girar.", ACCENT));
        Map<UUID, Integer> lastHit = new HashMap<>();

        animate(110, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < 20) {
                Compat.spawn(world(), Compat.SCULK_CHARGE, center(), 4, 0.5, 0.7, 0.5, 0.03);
                return;
            }
            Location c = center();
            double spin = (tick - 20) * (Math.PI * 2 * 2 / 90.0);
            for (int arm = 0; arm < 4; arm++) {
                double angle = spin + arm * Math.PI / 2;
                Vector dir = new Vector(Math.cos(angle), 0, Math.sin(angle));
                for (double d = 2; d <= 11; d += 1.4) {
                    Location p = c.clone().add(dir.clone().multiply(d));
                    Compat.spawn(world(), Compat.SCULK_CHARGE_POP, p, 1, 0.08, 0.15, 0.08, 0);
                    if (d > 9.5) Compat.spawn(world(), Compat.DUST, p, 1, 0.1, 0.1, 0.1, 0,
                            Compat.dust(SOUL_TEAL, 1.4f));
                }
                for (Player p : Fx.playersNear(c, 12)) {
                    Vector to = p.getLocation().toVector().subtract(c.toVector()).setY(0);
                    double dist = to.length();
                    if (dist < 2 || dist > 11.5) continue;
                    double diff = Math.abs(Math.atan2(to.getZ(), to.getX()) - angle) % (Math.PI * 2);
                    if (diff > Math.PI) diff = Math.PI * 2 - diff;
                    if (diff * dist > 1.1) continue;
                    Integer last = lastHit.get(p.getUniqueId());
                    if (last != null && tick - last < 30) continue;
                    lastHit.put(p.getUniqueId(), tick);
                    hit(p, 10);
                    push(p, to.normalize().multiply(0.7).setY(0.35));
                    soundAt(p.getLocation(), "entity.warden.sonic_boom", 0.9f, 1.4f);
                }
            }
            if (tick % 18 == 0) soundAt(c, "entity.warden.heartbeat", 1.4f, 1.3f);
        }, null);
    }

    /** 15. Ecolocalizacion: los cinco que mas dano le han hecho reciben su eco. */
    public void echolocation() {
        if (!alive()) return;
        List<Player> ranked = new ArrayList<>();
        event.damage().entrySet().stream()
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .limit(5)
                .forEach(e -> {
                    Player p = plugin.getServer().getPlayer(e.getKey());
                    if (p != null && Fx.isFightable(p)) ranked.add(p);
                });
        if (ranked.isEmpty()) return;
        soundAt(loc(), "block.sculk_sensor.clicking", 1.6f, 0.5f);
        warn(Component.text("Ha memorizado a los que mas le duelen.", ACCENT));

        for (Player m : ranked) {
            m.sendActionBar(Component.text("KEEPER te tiene ubicado.", NamedTextColor.RED, TextDecoration.BOLD));
            animate(50, tick -> {
                if (!alive() || !Fx.isFightable(m)) throw Stop.now();
                if (tick < 40) {
                    Fx.ring(m.getLocation().add(0, 2.5, 0), 0.6, 8, tick * 0.4, p ->
                            Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(SOUL_TEAL, 1.3f)));
                    return;
                }
                if (tick != 40) return;
                Vector dir = m.getLocation().add(0, 1, 0).toVector().subtract(center().toVector());
                if (dir.lengthSquared() < 0.01) return;
                soundAt(center(), "entity.warden.sonic_boom", 1.5f, 1.1f);
                sonicLine(center(), dir.normalize(), Math.min(26, dir.length() + 3), 1.2, 14);
            }, null);
        }
    }

    /** 16. Prision de Vibracion: enjaula a uno; o la rompen o estalla con el dentro. */
    public void vibrationPrison() {
        if (!alive()) return;
        Player prisoner = randomTarget();
        if (prisoner == null) return;
        Location cell = Fx.ground(prisoner.getLocation(), 4);
        soundAt(cell, "block.sculk_shrieker.shriek", 1.6f, 0.4f);
        titleNear(Component.text("PRISION DE VIBRACION", ACCENT, TextDecoration.BOLD),
                Component.text("Rompan la jaula o estalla", NamedTextColor.GRAY));
        prisoner.sendActionBar(Component.text("Estas enjaulado. Que te saquen.",
                NamedTextColor.RED, TextDecoration.BOLD));

        // La jaula visible: barrotes de display alrededor del preso. Cero bloques reales.
        List<Entity> bars = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            Location b = cell.clone().add(Math.cos(a) * 1.7, 0, Math.sin(a) * 1.7);
            for (int h = 0; h < 2; h++) {
                Entity bar = Fx.blockDisplay(world(), b.clone().add(0, 0.5 + h, 0), Material.SCULK, 0.9f);
                markMinion(bar);
                bars.add(bar);
            }
        }
        root(prisoner, 100);

        ArmorStand lock = fixture(cell.clone().add(0, 0, 0), Material.SCULK_SHRIEKER, "Cerrojo de la Jaula", 6);
        final boolean[] freed = {false};
        plugin.anchors().register(lock, 6,
                () -> soundAt(lock.getLocation(), "block.sculk.break", 1.2f, 0.9f),
                () -> {
                    freed[0] = true;
                    soundAt(cell, "block.sculk.break", 1.6f, 0.6f);
                    warn(Component.text("Jaula rota a tiempo.", NamedTextColor.GREEN));
                });

        animate(100, tick -> {
            if (!alive() || freed[0] || !Fx.isFightable(prisoner)) throw Stop.now();
            if (tick % 20 == 0) {
                soundAt(cell, "entity.warden.heartbeat", 1.2f, 0.8f + tick / 100f);
                Compat.spawn(world(), Compat.SCULK_CHARGE, cell.clone().add(0, 1.2, 0), 8, 1.2, 1.0, 1.2, 0.02);
            }
            if (tick == 99) {
                soundAt(cell, "entity.warden.sonic_boom", 1.7f, 0.5f);
                Compat.spawn(world(), Compat.EXPLOSION_EMITTER, cell.clone().add(0, 1, 0), 1);
                hit(prisoner, 20);
                for (Player p : Fx.playersNear(cell, 4.5)) {
                    if (p.equals(prisoner)) continue;
                    hit(p, 8);
                }
            }
        }, () -> {
            for (Entity bar : bars) {
                spawned.remove(bar);
                Fx.safeRemove(bar);
            }
            removeFixture(lock);
        });
    }

    /** 17. Pulso Devorador: tras el rugido, moverse es la unica forma de fallar. */
    public void devouringPulse() {
        if (!alive()) return;
        soundAt(loc(), "entity.warden.roar", 1.8f, 0.4f);
        titleNear(Component.text("QUIETOS", ACCENT, TextDecoration.BOLD),
                Component.text("Va a leer las vibraciones", NamedTextColor.GRAY));
        Map<UUID, Location> frozen = new HashMap<>();
        java.util.Set<UUID> punished = new java.util.HashSet<>();

        animate(130, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < 60) {
                if (tick % 12 == 0) soundAt(loc(), "block.sculk_sensor.clicking", 1.3f, 0.5f + tick / 60f);
                Compat.spawn(world(), Compat.SCULK_CHARGE, center(), 3, 0.4, 0.6, 0.4, 0.02);
                return;
            }
            if (tick == 60) {
                for (Player p : targets()) {
                    frozen.put(p.getUniqueId(), p.getLocation().clone());
                }
                soundAt(loc(), "entity.warden.listening_angry", 1.8f, 0.6f);
                return;
            }
            if (tick % 4 != 0) return;
            for (Player p : targets()) {
                Location was = frozen.get(p.getUniqueId());
                if (was == null || punished.contains(p.getUniqueId())) continue;
                if (was.getWorld() != p.getWorld()) continue;
                if (was.distanceSquared(p.getLocation()) < 0.10) continue;
                punished.add(p.getUniqueId());
                Vector dir = p.getLocation().add(0, 1, 0).toVector().subtract(center().toVector());
                if (dir.lengthSquared() > 0.01) {
                    sonicLine(center(), dir.normalize(), Math.min(26, dir.length() + 2), 1.0, 12);
                }
                soundAt(p.getLocation(), "entity.warden.sonic_boom", 1.3f, 1.2f);
            }
        }, null);
    }

    /** 18. Almas en Pena: espiritus que persiguen cada uno a su jugador y estallan. */
    public void lostSouls() {
        if (!alive()) return;
        int count = 8 + random.nextInt(5);
        List<Player> marks = pickTargets(count);
        if (marks.isEmpty()) return;
        soundAt(loc(), "particle.soul_escape", 1.8f, 0.6f);
        warn(Component.text("Suelta a sus almas. Un golpe las disipa.", ACCENT));

        for (int i = 0; i < count; i++) {
            Player prey = marks.get(i % marks.size());
            double a = Math.PI * 2 * i / count;
            Location sl = center().clone().add(Math.cos(a) * 1.5, 0.5, Math.sin(a) * 1.5);
            spawnSoul(sl, prey);
        }
    }

    /** Un alma: una brasa de sculk que flota hacia su presa y revienta al tocarla. */
    private void spawnSoul(Location from, Player prey) {
        try {
            Silverfish soul = world().spawn(from, Silverfish.class, s -> {
                s.setPersistent(false);
                s.setInvisible(true);
                s.setSilent(true);
                Compat.setAttribute(s, "scale", 0.5);
                Compat.setAttribute(s, "max_health", 1);
                s.setHealth(1);
            });
            markMinion(soul);
            animate(240, tick -> {
                if (soul == null || !soul.isValid()) throw Stop.now();
                Location l = soul.getLocation();
                Compat.spawn(world(), Compat.SCULK_SOUL, l.clone().add(0, 0.4, 0), 2, 0.1, 0.1, 0.1, 0.01);
                if (tick % 3 == 0 && Fx.isFightable(prey)) {
                    Vector to = prey.getLocation().add(0, 0.8, 0).toVector().subtract(l.toVector());
                    if (to.lengthSquared() > 0.04) {
                        soul.setVelocity(to.normalize().multiply(0.34).setY(
                                Math.max(-0.2, Math.min(0.3, to.getY() * 0.1))));
                    }
                    if (l.distanceSquared(prey.getLocation().add(0, 0.5, 0)) < 2.0) {
                        hit(prey, 8);
                        Compat.apply(prey, "darkness", 60, 0);
                        Compat.spawn(world(), Compat.SCULK_CHARGE_POP, l, 14, 0.3, 0.3, 0.3, 0.06);
                        soundAt(l, "entity.warden.sonic_boom", 0.9f, 1.6f);
                        spawned.remove(soul);
                        Fx.safeRemove(soul);
                        throw Stop.now();
                    }
                }
            }, () -> {
                spawned.remove(soul);
                Fx.safeRemove(soul);
            });
        } catch (Throwable ignored) {
        }
    }

    /** 19. Terremoto del Abismo: grietas que corren hacia varios y erupcionan. */
    public void abyssQuake() {
        if (!alive()) return;
        List<Player> marks = pickTargets(4);
        if (marks.isEmpty()) return;
        soundAt(loc(), "entity.warden.attack_impact", 1.7f, 0.4f);
        warn(Component.text("El suelo se agrieta hacia ustedes.", ACCENT));

        for (Player m : marks) {
            final Location from = Fx.ground(loc(), 3);
            animate(46, tick -> {
                if (!alive()) throw Stop.now();
                if (tick < 26) {
                    if (!Fx.isFightable(m)) throw Stop.now();
                    Location to = m.getLocation();
                    Vector dir = to.toVector().subtract(from.toVector()).setY(0);
                    double len = Math.min(dir.length(), 2 + tick * 0.9);
                    if (dir.lengthSquared() < 0.01) return;
                    dir.normalize();
                    Location tip = Fx.ground(from.clone().add(dir.multiply(len)), 4);
                    Compat.spawn(world(), Compat.DUST, tip.clone().add(0, 0.2, 0), 3, 0.3, 0.05, 0.3, 0,
                            Compat.dust(SOUL_TEAL, 1.7f));
                    Compat.spawn(world(), Compat.BLOCK, tip.clone().add(0, 0.2, 0), 4, 0.3, 0.1, 0.3, 0.03,
                            Material.SCULK.createBlockData());
                    return;
                }
                if (tick != 26) return;
                Location burst = Fx.ground(m.getLocation(), 4);
                Fx.telegraph(world(), burst, 2.6, DEEP);
                later(14, () -> {
                    soundAt(burst, "entity.warden.emerge", 1.4f, 1.2f);
                    Compat.spawn(world(), Compat.SCULK_CHARGE, burst.clone().add(0, 0.5, 0), 24, 1.0, 0.7, 1.0, 0.06);
                    for (Player p : Fx.playersNear(burst, 3.0)) {
                        hit(p, 12);
                        lift(p, new Vector(0, 0.95, 0));
                    }
                });
            }, null);
        }
    }

    // -------------------------------------------- FASE IV: el Monarca del Silencio

    /** 20. Silencio Absoluto: la arena entera a oscuras; los avisos, solo visuales. */
    public void absoluteSilence() {
        if (!alive()) return;
        soundAt(loc(), "entity.warden.sonic_boom", 1.6f, 0.3f);
        titleNear(Component.text("SILENCIO ABSOLUTO", ACCENT, TextDecoration.BOLD),
                Component.text("Miren las luces, el sonido ya no ayuda", NamedTextColor.GRAY));
        for (Player p : targets()) {
            Compat.apply(p, "darkness", 160, 2);
        }
        Compat.apply(boss, "speed", 160, 0);
        animate(160, tick -> {
            if (!alive()) throw Stop.now();
            if (tick % 6 == 0) {
                Compat.spawn(world(), Compat.SCULK_SOUL, center().add(0, 1.0, 0), 4, 1.6, 1.2, 1.6, 0.02);
            }
        }, null);
    }

    /** 21. Detonacion en Cadena: todo lo plantado revienta, pieza a pieza. */
    public void chainDetonation() {
        if (!alive()) return;
        fixtures.removeIf(f -> f == null || !f.isValid() || !plugin.anchors().isAnchor(f));
        if (fixtures.isEmpty()) {
            // Sin estructuras vivas no hay cadena: planta dos chirriadores de mecha corta.
            shriekerCall();
        }
        List<ArmorStand> chain = new ArrayList<>(fixtures);
        if (chain.isEmpty()) return;
        warn(Component.text("Todo lo plantado va a estallar.", ACCENT));
        soundAt(loc(), "block.sculk_shrieker.shriek", 1.7f, 0.4f);

        for (int i = 0; i < chain.size(); i++) {
            ArmorStand f = chain.get(i);
            later(20 + i * 10, () -> {
                if (f == null || !f.isValid()) return;
                Location l = f.getLocation().add(0, 1.2, 0);
                removeFixture(f);
                Compat.spawn(world(), Compat.EXPLOSION, l, 2, 0.3, 0.3, 0.3, 0);
                Compat.spawn(world(), Compat.SHRIEK, l, 2, 0.3, 0.3, 0.3, 0);
                soundAt(l, "entity.generic.explode", 1.3f, 1.1f);
                for (Player p : Fx.playersNear(l, 3.5)) {
                    hit(p, 12);
                    Compat.apply(p, "darkness", 60, 0);
                }
            });
        }
    }

    /** 22. Bramido en Cruz: cuatro lineas a la vez, y la cruz gira 45 grados y repite. */
    public void crossRoar() {
        if (!alive()) return;
        soundAt(loc(), "entity.warden.sonic_charge", 1.8f, 0.6f);
        warn(Component.text("La cruz de ecos. Buscen el hueco.", ACCENT));

        for (int round = 0; round < 2; round++) {
            final double offset = round * Math.PI / 4;
            later(28 + round * 34, () -> {
                if (!alive()) return;
                soundAt(loc(), "entity.warden.sonic_boom", 1.8f, 0.7f);
                for (int arm = 0; arm < 4; arm++) {
                    double angle = offset + arm * Math.PI / 2;
                    sonicLine(center(), new Vector(Math.cos(angle), 0, Math.sin(angle)), 16, 1.5, 11);
                }
            });
        }
        // El aviso: las cuatro lineas de la primera tanda pintadas en el suelo.
        animate(26, tick -> {
            if (!alive()) throw Stop.now();
            for (int arm = 0; arm < 4; arm++) {
                double angle = arm * Math.PI / 2;
                Vector dir = new Vector(Math.cos(angle), 0, Math.sin(angle));
                for (double d = 2; d < 16; d += 2.2) {
                    Location g = Fx.ground(loc().clone().add(dir.clone().multiply(d)), 4);
                    Compat.spawn(world(), Compat.DUST, g.clone().add(0, 0.15, 0), 1, 0.2, 0, 0.2, 0,
                            Compat.dust(DEEP, 1.5f));
                }
            }
        }, null);
    }

    /** 23. Cataclismo Sonico: la onda global. Agachado y quieto se sobrevive. */
    public void sonicCataclysm() {
        if (!alive()) return;
        titleNear(Component.text("CATACLISMO SONICO", ACCENT, TextDecoration.BOLD),
                Component.text("AGACHATE Y NO TE MUEVAS", NamedTextColor.RED));
        soundAt(loc(), "entity.warden.sonic_charge", 1.8f, 0.4f);
        stillCheck.clear();

        animate(160, tick -> {
            if (!alive()) throw Stop.now();
            if (tick < 100) {
                int every = Math.max(4, 16 - tick / 8);
                if (tick % every == 0) {
                    soundAt(loc(), "entity.warden.heartbeat", 1.8f, 0.9f + tick / 120f);
                    Compat.spawn(world(), Compat.SCULK_CHARGE, center(), 6, 0.6, 0.9, 0.6, 0.04);
                }
                if (tick % 20 == 0) {
                    Fx.sphere(center(), 2.2 + tick / 40.0, 26, p ->
                            Compat.spawn(world(), Compat.DUST, p, 1, 0, 0, 0, 0, Compat.dust(SOUL_TEAL, 1.5f)));
                }
                return;
            }
            if (tick == 100) {
                // Se congelan las posiciones: lo que se mueva desde AQUI, vibra.
                for (Player p : targets()) {
                    stillCheck.put(p.getUniqueId(), p.getLocation().clone());
                }
                titleNear(Component.text("...", NamedTextColor.GRAY),
                        Component.text("esta leyendo la arena", NamedTextColor.DARK_GRAY));
                soundAt(loc(), "entity.warden.listening_angry", 1.8f, 0.5f);
                return;
            }
            if (tick != 158) return;
            soundAt(loc(), "entity.warden.sonic_boom", 2.0f, 0.35f);
            Compat.spawn(world(), Compat.EXPLOSION_EMITTER, center(), 3, 1.0, 1.0, 1.0, 0);
            Fx.sphere(center(), 6, 60, p ->
                    Compat.spawn(world(), Compat.SCULK_CHARGE_POP, p, 1, 0.1, 0.1, 0.1, 0.05));
            for (Player p : targets()) {
                Location was = stillCheck.get(p.getUniqueId());
                boolean still = p.isSneaking() && was != null && was.getWorld() == p.getWorld()
                        && was.distanceSquared(p.getLocation()) < 0.36;
                if (still) {
                    hit(p, 6.5);
                    Compat.spawn(world(), Compat.SCULK_SOUL, p.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.02);
                    p.sendActionBar(Component.text("No te ha oido.", NamedTextColor.GREEN, TextDecoration.BOLD));
                } else {
                    hit(p, 26);
                    push(p, p.getLocation().toVector().subtract(loc().toVector()).normalize()
                            .multiply(1.4).setY(0.6));
                    Compat.spawn(world(), Compat.SHRIEK, p.getLocation().add(0, 2, 0), 2, 0.2, 0.2, 0.2, 0);
                }
            }
        }, null);
    }

    /** 24. Vastagos del Sculk: crias del abismo, cada una a por un jugador distinto. */
    public void sculkSpawn() {
        if (!alive()) return;
        int count = 4 + random.nextInt(3);
        List<Player> marks = pickTargets(count);
        if (marks.isEmpty()) return;
        soundAt(loc(), "entity.warden.agitated", 1.6f, 0.6f);
        warn(Component.text("Suelta a sus vastagos.", ACCENT));

        for (int i = 0; i < count; i++) {
            Player prey = marks.get(i % marks.size());
            double a = Math.PI * 2 * i / count;
            Location sl = Fx.ground(loc().clone().add(Math.cos(a) * 2.5, 1, Math.sin(a) * 2.5), 4);
            spawnSpawnling(sl, prey);
        }
    }

    /** Un vastago: corre brillando hacia su presa y revienta al alcanzarla. */
    private void spawnSpawnling(Location from, Player prey) {
        try {
            Silverfish spawnling = world().spawn(from, Silverfish.class, s -> {
                s.setPersistent(false);
                Compat.setAttribute(s, "scale", 0.85);
                Compat.setAttribute(s, "max_health", 10);
                Compat.setAttribute(s, "movement_speed", 0.38);
                s.setHealth(10);
            });
            markMinion(spawnling);
            Glow.apply(spawnling, NamedTextColor.AQUA);
            try {
                spawnling.setTarget(prey);
            } catch (Throwable ignored) {
            }
            animate(200, tick -> {
                if (spawnling == null || !spawnling.isValid()) throw Stop.now();
                Location l = spawnling.getLocation();
                if (tick % 4 == 0) {
                    Compat.spawn(world(), Compat.SCULK_CHARGE_POP, l.clone().add(0, 0.3, 0), 1, 0.1, 0.1, 0.1, 0);
                }
                if (!Fx.isFightable(prey)) return;
                if (tick % 10 == 0) {
                    try {
                        spawnling.setTarget(prey);
                    } catch (Throwable ignored) {
                    }
                }
                if (l.distanceSquared(prey.getLocation()) < 2.6) {
                    Compat.spawn(world(), Compat.EXPLOSION, l.clone().add(0, 0.5, 0), 2, 0.3, 0.3, 0.3, 0);
                    Compat.spawn(world(), Compat.SCULK_CHARGE, l.clone().add(0, 0.5, 0), 16, 0.6, 0.5, 0.6, 0.05);
                    soundAt(l, "entity.generic.explode", 1.2f, 1.2f);
                    for (Player v : Fx.playersNear(l, 2.5)) {
                        hit(v, 10);
                    }
                    spawned.remove(spawnling);
                    Fx.safeRemove(spawnling);
                    throw Stop.now();
                }
            }, () -> {
                spawned.remove(spawnling);
                Fx.safeRemove(spawnling);
            });
        } catch (Throwable ignored) {
        }
    }

    /** 25. Abrazo del Abismo: agarra al mas cercano; el grupo lo suelta A GOLPES. */
    public void abyssEmbrace() {
        if (!alive()) return;
        Player caught = Fx.nearest(loc(), 7);
        if (caught == null) return;
        face(caught.getLocation());
        embraceActive = true;
        embraceDamage = 0;
        root(caught, 90);
        soundAt(caught.getLocation(), "entity.warden.attack_impact", 1.6f, 0.5f);
        titleNear(Component.text("ABRAZO DEL ABISMO", ACCENT, TextDecoration.BOLD),
                Component.text("Peguenle a KEEPER para que lo suelte", NamedTextColor.GRAY));
        caught.sendActionBar(Component.text("Te esta apretando. Que le peguen.",
                NamedTextColor.RED, TextDecoration.BOLD));

        animate(90, tick -> {
            if (!alive() || !Fx.isFightable(caught)) throw Stop.now();
            if (embraceDamage >= 60) {
                warn(Component.text("KEEPER suelta la presa.", NamedTextColor.GREEN));
                Compat.apply(boss, "slowness", 50, 1);
                soundAt(loc(), "entity.warden.hurt", 1.6f, 0.6f);
                throw Stop.now();
            }
            Fx.beam(center(), caught.getLocation().add(0, 1, 0), 0.5, p ->
                    Compat.spawn(world(), Compat.SCULK_SOUL, p, 1, 0.04, 0.04, 0.04, 0.01));
            if (tick % 20 == 10) {
                hit(caught, 3);
                soundAt(caught.getLocation(), "entity.warden.heartbeat", 1.2f, 1.4f);
            }
            if (tick % 20 == 0) {
                warn(Component.text("Soltarlo: " + (int) Math.max(0, 60 - embraceDamage)
                        + " de dano al jefe", ACCENT));
            }
            if (tick == 89) {
                hit(caught, 18);
                Compat.spawn(world(), Compat.SHRIEK, caught.getLocation().add(0, 2, 0), 2, 0.2, 0.2, 0.2, 0);
                soundAt(caught.getLocation(), "entity.warden.sonic_boom", 1.5f, 0.8f);
            }
        }, () -> embraceActive = false);
    }

    @Override
    public void onDamaged(Player attacker, double amount) {
        if (embraceActive) {
            embraceDamage += amount;
        }
    }

    // --------------------------------------------------------------------- muerte

    /** 26. El Ultimo Latido: el corazon queda solo, late cinco segundos y se apaga. */
    @Override
    public void onDeath() {
        Location l = center();
        soundAt(l, "entity.warden.death", 1.8f, 0.8f);
        Compat.spawn(world(), Compat.SCULK_CHARGE, l, 40, 1.5, 1.2, 1.5, 0.08);

        // El corazon: un catalizador flotando donde estaba el pecho.
        Entity heart = Fx.blockDisplay(world(), l.clone().add(0, 0.6, 0), Material.SCULK_CATALYST, 0.8f);
        markMinion(heart);

        animate(100, tick -> {
            if (heart == null || !heart.isValid()) throw Stop.now();
            int every = Math.max(5, 20 - tick / 6);
            if (tick % every == 0) {
                soundAt(heart.getLocation(), "entity.warden.heartbeat", 1.6f, 0.9f + tick / 90f);
                Compat.spawn(world(), Compat.SCULK_SOUL, heart.getLocation().add(0, 0.5, 0), 5,
                        0.3, 0.3, 0.3, 0.02);
            }
        }, () -> {
            Location hl = heart != null && heart.isValid() ? heart.getLocation() : l;
            // La nova inofensiva: tres anillos de luz cian y la lluvia de almas.
            soundAt(hl, "entity.warden.sonic_boom", 1.8f, 0.4f);
            soundAt(hl, "block.amethyst_block.resonate", 1.5f, 0.6f);
            for (int ring = 0; ring < 3; ring++) {
                final double radius = 3 + ring * 3.5;
                later(ring * 8, () -> Fx.ring(hl, radius, (int) (radius * 7), p ->
                        Compat.spawn(world(), Compat.DUST, p, 2, 0.15, 0.4, 0.15, 0,
                                Compat.dust(SOUL_TEAL, 2.0f))));
            }
            Compat.spawn(world(), Compat.SCULK_SOUL, hl, 60, 2.0, 2.5, 2.0, 0.08);
            if (heart != null) {
                spawned.remove(heart);
                Fx.safeRemove(heart);
            }
        });
    }

    @Override
    public int deathAnimationTicks() {
        return 130;
    }

    @Override
    public void cleanup() {
        for (ArmorStand f : new ArrayList<>(fixtures)) {
            removeFixture(f);
        }
        fixtures.clear();
        super.cleanup();
    }

    // ------------------------------------------------------------------ utilidades

    /**
     * La linea sonica: el idioma de KEEPER. Recorre un corredor recto pintando el
     * bramido y golpea UNA VEZ a cada jugador que pille dentro del pasillo.
     */
    private void sonicLine(Location from, Vector dir, double length, double halfWidth, double damage) {
        for (double d = 1.5; d <= length; d += 2.5) {
            Location p = from.clone().add(dir.clone().multiply(d));
            Compat.spawn(world(), Compat.SONIC_BOOM, p, 1, 0, 0, 0, 0);
            Compat.spawn(world(), Compat.SCULK_CHARGE_POP, p, 2, 0.3, 0.3, 0.3, 0.02);
        }
        for (Player p : targets(length + 3)) {
            Vector to = p.getLocation().add(0, 1, 0).toVector().subtract(from.toVector());
            double along = to.dot(dir);
            if (along < 0 || along > length) continue;
            double off = to.subtract(dir.clone().multiply(along)).setY(0).length();
            if (off > halfWidth) continue;
            hit(p, damage);
            push(p, dir.clone().multiply(0.55).setY(0.3));
            Compat.spawn(world(), Compat.SHRIEK, p.getLocation().add(0, 2.2, 0), 1, 0, 0, 0, 0);
        }
    }

    /**
     * Una pieza de sculk plantada: soporte golpeable con el bloque de casco, anclado
     * al sistema de objetivos rompibles y apuntado en la lista para la Detonacion.
     */
    private ArmorStand fixture(Location spot, Material block, String name, int hits) {
        ArmorStand stand = world().spawn(spot, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setGravity(false);
            s.setPersistent(false);
            s.setBasePlate(false);
            s.setSmall(true);
            s.customName(Component.text(name, ACCENT, TextDecoration.BOLD));
            s.setCustomNameVisible(true);
            EntityEquipment eq = s.getEquipment();
            if (eq != null) eq.setHelmet(new ItemStack(block));
        });
        markMinion(stand);
        fixtures.add(stand);
        plugin.anchors().register(stand, hits,
                () -> soundAt(stand.getLocation(), "block.sculk.hit", 1.1f, 0.8f),
                () -> {
                    fixtures.remove(stand);
                    Compat.spawn(world(), Compat.SCULK_CHARGE_POP, stand.getLocation().add(0, 1, 0), 16,
                            0.4, 0.4, 0.4, 0.05);
                    soundAt(stand.getLocation(), "block.sculk.break", 1.3f, 0.7f);
                });
        return stand;
    }

    /** Retira una pieza plantada: del ancla, de la lista y del mundo. */
    private void removeFixture(ArmorStand stand) {
        if (stand == null) return;
        plugin.anchors().forget(stand);
        fixtures.remove(stand);
        spawned.remove(stand);
        Fx.safeRemove(stand);
    }

    private void titleNear(Component title, Component subtitle) {
        for (Player p : Fx.viewersNear(loc(), 90)) {
            p.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1400), Duration.ofMillis(500))));
        }
    }
}
