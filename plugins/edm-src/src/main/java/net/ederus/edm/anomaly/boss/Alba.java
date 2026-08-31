package net.ederus.edm.anomaly.boss;

import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.core.ActiveAnomaly;
import net.ederus.edm.anomaly.core.Compat;
import net.ederus.edm.anomaly.core.Disguises;
import net.ederus.edm.anomaly.core.Fx;
import net.ederus.edm.anomaly.core.Tags;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Horse;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * AUREON, EL CABALLERO CELESTE. La primera anomalia de clase DIOS.
 *
 * La referencia de diseño es el arquero de tesoros de las leyendas: una reina que
 * apenas se mueve porque no le hace falta. Abre puertas doradas en el aire y de
 * ellas salen las armas de su tesoro; encadena, sentencia y ejecuta. Todo en oro
 * y plata, con campanas agudas y truenos debajo.
 *
 * CINCO fases:
 *   1  El Heraldo      montado; cargas, lanzas y clarines.
 *   2  El Tesoro       montado; se abren las puertas doradas y las cadenas.
 *   3  El Duelista     DESMONTA y pelea cuerpo a cuerpo, sin dejar el area.
 *   4  La Tormenta     REMONTA; el tesoro entero cae del cielo.
 *   5  El Juicio       la Clave del Cielo, las cien puertas y el veredicto.
 *
 * Reglas de la casa que este jefe cumple a rajatabla:
 *  - NADA de nombres flotantes en lo que invoca: ni el caballo, ni las armas,
 *    ni los soportes. Solo la propia Alba lleva nombre (como Rabby).
 *  - Nada queda vivo al terminar una habilidad: toda entidad de dibujo se
 *    registra con track()/expire() y el cleanup() de BossFight la barre.
 *
 * El cuerpo es un MANNEQUIN con la skin de la cuenta AttackOnKilla (la logica
 * de Rabby): el zombi de debajo pelea invisible y callado.
 */
public final class Alba extends BossFight {

    public static final String ID = "alba";

    /** La cuenta que lleva puesta la skin del caballero. */
    private static final String SKIN_ACCOUNT = "AttackOnKilla";

    public static final TextColor ORO = TextColor.fromHexString("#FFD700");
    public static final TextColor ORO_PALIDO = TextColor.fromHexString("#FFF3B0");
    public static final TextColor PLATA = TextColor.fromHexString("#E8E8F0");
    private static final TextColor ACCENT = ORO;

    /** El corcel. Vive lo que el combate; en las fases a pie espera aparte. */
    private Horse steed;

    /** Marcas de cadena activas, para no encadenar dos veces al mismo. */
    private final List<Player> chained = new ArrayList<>();

    public Alba(AnomalyPlugin plugin, ActiveAnomaly event, Location arena) {
        super(plugin, event, arena);
        /* Sin esto la pelea no tiene NINGUNA habilidad y el jefe se queda en
         * golpes basicos: fue exactamente el fallo de la primera prueba. */
        abilities.addAll(plugin.registry().alba().abilities());
    }

    @Override
    public String bossName() {
        return "Alba";
    }

    @Override
    public int phaseCount() {
        return 5;
    }

    // ------------------------------------------------------------------ aparicion

    @Override
    public void spawn() {
        Location spot = Fx.ground(arena.clone(), 6).add(0, 0.1, 0);

        // El cielo anuncia: columna de luz, campanas y un anillo de destellos.
        World w = world();
        Compat.sound(w, spot, "block.bell.resonate", 1.4f, 1.7f);
        Compat.sound(w, spot, "entity.lightning_bolt.thunder", 0.8f, 1.4f);
        for (int y = 0; y < 18; y += 2) {
            Compat.spawn(w, Compat.END_ROD, spot.clone().add(0, y, 0), 6, 0.3, 0.6, 0.3, 0.02);
        }
        Fx.ring(spot.clone().add(0, 0.2, 0), 5.0, 40, p ->
                Compat.spawn(w, Compat.ELECTRIC_SPARK, p, 2, 0.05, 0.3, 0.05, 0.04));

        boss = w.spawn(spot, Zombie.class, z -> {
            z.setAdult();
            z.setPersistent(true);
            z.setRemoveWhenFarAway(false);
            z.setCanPickupItems(false);
            z.setShouldBurnInDay(false);
        });
        EntityEquipment eq = boss.getEquipment();
        if (eq != null) {
            eq.clear();
            eq.setItemInMainHandDropChance(0);
        }

        wearShell(Disguises.profileOfAccount(plugin, SKIN_ACCOUNT),
                Component.text("✦ Alba ✦", ORO, TextDecoration.BOLD));
        Disguises.resolveAccount(plugin, SKIN_ACCOUNT, this::reskinShell);
        later(3, this::armarLanza);

        Compat.setAttribute(boss, "max_health", 20);
        Compat.setAttribute(boss, "attack_damage", 11);
        Compat.setAttribute(boss, "armor", 14);
        Compat.setAttribute(boss, "armor_toughness", 8);
        Compat.setAttribute(boss, "knockback_resistance", 1.0);
        Compat.setAttribute(boss, "follow_range", 80);
        Compat.setAttribute(boss, "movement_speed", 0.3);
        applyHealth(plugin.registry().scaledHealth(plugin.registry().alba(), targets(96).size()));
        boss.setMaximumNoDamageTicks(6);

        Tags.markBoss(boss, ID);
        Tags.markEvent(boss, event.id());
        glowBody(NamedTextColor.GOLD);

        later(2, () -> mount(boss.getLocation()));

        for (Player p : Fx.viewersNear(spot, 110)) {
            p.showTitle(Title.title(
                    Component.text("✦ ANOMALIA DIOS ✦", ORO, TextDecoration.BOLD),
                    Component.text("Alba, la Primera Luz", PLATA),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(2400), Duration.ofMillis(800))));
        }
        warn(Component.text("El cielo se abre. Una reina baja a juzgar.", ORO_PALIDO));
        Compat.sound(w, spot, "item.trident.thunder", 1.2f, 1.6f);
        Compat.sound(w, spot, "block.amethyst_block.resonate", 1.4f, 0.9f);
    }

    /** El corcel dorado. Sin nombre: se presenta solo. */
    /** Sube el CUERPO VISIBLE al caballo. El que se ve cabalgando es el maniqui. */
    private void montarCuerpo() {
        if (steed == null || !steed.isValid()) return;
        var cuerpo = shell() != null && shell().isValid() ? shell() : boss;
        if (cuerpo != null && cuerpo.isValid() && !steed.getPassengers().contains(cuerpo)) {
            steed.addPassenger(cuerpo);
        }
    }

    private void mount(Location spot) {
        steed = world().spawn(spot, Horse.class, h -> {
            h.setAdult();
            h.setTamed(true);
            h.setColor(Horse.Color.WHITE);
            h.setStyle(Horse.Style.WHITE_DOTS);
            h.setPersistent(false);
            h.setRemoveWhenFarAway(false);
            h.setInvulnerable(true);
            h.getInventory().setArmor(new ItemStack(Material.GOLDEN_HORSE_ARMOR));
        });
        markMinion(steed);
        Compat.setAttribute(steed, "movement_speed", 0.32);
        montarCuerpo();
        Compat.spawn(world(), Compat.FIREWORK_SPARK, spot.clone().add(0, 1.2, 0), 20, 0.8, 0.6, 0.8, 0.06);
        soundAt(spot, "entity.horse.armor", 1.2f, 0.8f);
        soundAt(spot, "entity.horse.angry", 1.0f, 0.9f);
    }

    /** Una pieza de la Regalia para el cuerpo: oro con trim de diamante. */
    private static ItemStack piezaRegalia(Material mat, org.bukkit.inventory.meta.trim.TrimPattern patron) {
        ItemStack pieza = new ItemStack(mat);
        try {
            if (pieza.getItemMeta() instanceof org.bukkit.inventory.meta.ArmorMeta am) {
                am.setTrim(new org.bukkit.inventory.meta.trim.ArmorTrim(
                        org.bukkit.inventory.meta.trim.TrimMaterial.DIAMOND, patron));
                pieza.setItemMeta(am);
            }
        } catch (Throwable ignored) {
        }
        return pieza;
    }

    /** La Regalia del Alba puesta: la dama pelea con su propia armadura. */
    private void armarLanza() {
        try {
            if (shell() == null || !shell().isValid() || shell().getEquipment() == null) return;
            var eq = shell().getEquipment();
            eq.setItemInMainHand(lance());
            eq.setHelmet(piezaRegalia(Material.GOLDEN_HELMET, org.bukkit.inventory.meta.trim.TrimPattern.FLOW));
            eq.setChestplate(piezaRegalia(Material.GOLDEN_CHESTPLATE, org.bukkit.inventory.meta.trim.TrimPattern.RAISER));
            eq.setLeggings(piezaRegalia(Material.GOLDEN_LEGGINGS, org.bukkit.inventory.meta.trim.TrimPattern.SPIRE));
            eq.setBoots(piezaRegalia(Material.GOLDEN_BOOTS, org.bukkit.inventory.meta.trim.TrimPattern.FLOW));
            eq.setItemInMainHandDropChance(0);
            /* La montura se engancha cuando el cuerpo ya existe. */
            montarCuerpo();
        } catch (Throwable ignored) {
        }
    }

    private boolean mounted() {
        if (steed == null || !steed.isValid()) return false;
        var cuerpo = shell() != null && shell().isValid() ? shell() : boss;
        return steed.getPassengers().contains(cuerpo);
    }

    private void dismount() {
        if (steed == null || !steed.isValid()) return;
        steed.eject();
        Location aside = Fx.ground(loc().clone().add(6, 1, 6), 8);
        steed.teleport(aside);
        soundAt(loc(), "entity.horse.gallop", 1.2f, 1.1f);
        Compat.spawn(world(), Compat.CLOUD, loc(), 14, 0.6, 0.2, 0.6, 0.02);
    }

    private void remount() {
        if (steed == null || !steed.isValid()) {
            mount(loc());
            return;
        }
        steed.teleport(boss.getLocation());
        montarCuerpo();
        soundAt(loc(), "entity.horse.armor", 1.2f, 0.7f);
        Compat.spawn(world(), Compat.FIREWORK_SPARK, loc().add(0, 1, 0), 16, 0.6, 0.5, 0.6, 0.05);
    }

    // ------------------------------------------------------------------ barra propia

    /** Una sola barra, dorada y continua. Las 5 barras seccionadas saturaban. */
    private net.kyori.adventure.bossbar.BossBar barra;
    private final java.util.Set<Player> viendoBarra = new java.util.HashSet<>();

    @Override
    public boolean usesOwnBars() {
        return true;
    }

    private void barra() {
        if (barra == null) {
            barra = net.kyori.adventure.bossbar.BossBar.bossBar(
                    Component.text("✦ ALBA ✦ ", ORO, TextDecoration.BOLD)
                            .append(Component.text("La Primera Luz", ORO_PALIDO)
                                    .decoration(TextDecoration.BOLD, false)),
                    1.0f,
                    net.kyori.adventure.bossbar.BossBar.Color.YELLOW,
                    net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
        }
        barra.progress((float) Math.max(0.0, Math.min(1.0, healthFraction())));
        if (ticks() % 20 == 0) {
            java.util.Set<Player> cerca = new java.util.HashSet<>(Fx.viewersNear(loc(), 90));
            for (Player p : new java.util.ArrayList<>(viendoBarra)) {
                if (!cerca.contains(p) || !p.isOnline()) {
                    p.hideBossBar(barra);
                    viendoBarra.remove(p);
                }
            }
            for (Player p : cerca) {
                if (viendoBarra.add(p)) p.showBossBar(barra);
            }
        }
    }

    @Override
    protected void ambient() {
        super.ambient();
        barra();
        /* Mientras cabalga, el mob de combate (invisible) viaja pegado al
         * caballo: el maniqui es el pasajero y el zombi lo sigue por debajo. */
        if (mounted() && boss != null && boss.isValid()) {
            boss.teleport(steed.getLocation());
        }
        /* El guardian de la montura: en toda fase menos la del duelo a pie,
         * Alba pelea A CABALLO. Si algo la baja (o el enganche del spawn
         * fallo), se la vuelve a subir. */
        if (phase() != 3 && ticks() % 40 == 0 && alive() && !mounted()) {
            remount();
        }
        if (ticks() % 14 == 0) {
            Compat.spawn(world(), Compat.END_ROD, body().getLocation().add(0, 2.2, 0), 1, 0.25, 0.15, 0.25, 0.0);
        }
    }

    @Override
    public void cleanup() {
        if (barra != null) {
            for (Player p : viendoBarra) p.hideBossBar(barra);
            viendoBarra.clear();
        }
        super.cleanup();
    }

    // ------------------------------------------------------------------ fases

    @Override
    protected void onPhaseChange(int from, int to) {
        World w = world();
        Location c = center();
        Compat.spawn(w, Compat.FLASH, c, 1);
        Compat.sound(w, loc(), "block.bell.use", 1.5f, 1.8f);
        Compat.sound(w, loc(), "entity.lightning_bolt.impact", 0.9f, 1.3f);
        switch (to) {
            case 2 -> {
                warn(Component.text("«Contemplen mi tesoro.»", ORO, TextDecoration.BOLD));
                ringOfGates(c, 7.5, 6, 60);
            }
            case 3 -> {
                warn(Component.text("«Merecen que baje a mirarlos de cerca.»", ORO, TextDecoration.BOLD));
                dismount();
                Compat.setAttribute(boss, "movement_speed", 0.34);
            }
            case 4 -> {
                warn(Component.text("«Se acabo la cortesia.»", ORO, TextDecoration.BOLD));
                remount();
                Compat.setAttribute(boss, "attack_damage", 13);
            }
            case 5 -> {
                warn(Component.text("«QUE EL CIELO DICTE SENTENCIA.»", PLATA, TextDecoration.BOLD));
                for (Player p : targets(90)) {
                    p.showTitle(Title.title(
                            Component.text("✦ EL JUICIO ✦", ORO, TextDecoration.BOLD),
                            Component.text("Fase final", PLATA),
                            Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(1500), Duration.ofMillis(500))));
                }
                Compat.sound(w, loc(), "block.amethyst_cluster.break", 1.6f, 0.6f);
            }
            default -> { }
        }
    }

    @Override
    public void onDeath() {
        World w = world();
        Location c = center();
        if (steed != null && steed.isValid()) {
            Compat.spawn(w, Compat.POOF, steed.getLocation().add(0, 1, 0), 18, 0.5, 0.6, 0.5, 0.03);
            Fx.safeRemove(steed);
        }
        // El tesoro se retira: espadas subiendo al cielo en espiral.
        animate(50, t -> {
            double a = t * 0.5;
            Location p = c.clone().add(Math.cos(a) * 2.2, t * 0.35, Math.sin(a) * 2.2);
            Compat.spawn(w, Compat.END_ROD, p, 2, 0.05, 0.05, 0.05, 0.01);
            Compat.spawn(w, Compat.ELECTRIC_SPARK, p, 1, 0.1, 0.1, 0.1, 0.02);
            if (t % 10 == 0) Compat.sound(w, c, "block.bell.resonate", 0.9f, 1.4f + t * 0.01f);
        }, () -> {
            Compat.spawn(w, Compat.FLASH, c, 1);
            Compat.sound(w, c, "entity.player.levelup", 1.2f, 0.6f);
            Compat.sound(w, c, "item.trident.thunder", 1.0f, 1.8f);
        });
        warn(Component.text("Alba devuelve su tesoro al cielo.", ORO_PALIDO));
    }

    // ================================================================== ARSENAL
    // Helpers compartidos: puertas, hojas, cadenas. Toda entidad pasa por
    // track()/expire(); nada lleva nombre.

    private ItemStack goldBlade() {
        return new ItemStack(Material.GOLDEN_SWORD);
    }

    private ItemStack silverBlade() {
        return new ItemStack(Material.IRON_SWORD);
    }

    /** La lanza nueva de esta version; si algun dia falta, el tridente de siempre. */
    private static final Material MAT_LANZA = pickMat("GOLDEN_SPEAR", "TRIDENT");

    private static Material pickMat(String... nombres) {
        for (String n : nombres) {
            Material m = Material.matchMaterial(n);
            if (m != null) return m;
        }
        return Material.TRIDENT;
    }

    private ItemStack lance() {
        return new ItemStack(MAT_LANZA);
    }

    /** Un arma de dibujo con la punta hacia donde viaja. */
    private ItemDisplay bladeDisplay(Location at, ItemStack arma, float scale, float yawDeg, float pitchDeg) {
        ItemDisplay d = Fx.itemDisplay(world(), at, arma, scale);
        if (d == null) return null;
        try {
            Transformation tr = d.getTransformation();
            org.joml.Quaternionf rot = new org.joml.Quaternionf()
                    .rotateY((float) Math.toRadians(-yawDeg))
                    .rotateX((float) Math.toRadians(pitchDeg + 135));
            d.setTransformation(new Transformation(tr.getTranslation(), rot,
                    new Vector3f(scale, scale, scale), tr.getRightRotation()));
        } catch (Throwable ignored) {
        }
        track(d);
        return d;
    }

    /**
     * Una PUERTA DORADA: el anillo del que sale un arma. Puro dibujo, dura poco
     * y suena a campana y a piedra de portal.
     */
    private void gate(Location at, int lifeTicks) {
        World w = world();
        Compat.sound(w, at, "block.end_portal_frame.fill", 1.1f, 1.6f);
        Compat.sound(w, at, "block.bell.use", 0.7f, 1.9f);
        animate(lifeTicks, t -> {
            double r = 1.3;
            for (int i = 0; i < 10; i++) {
                double a = Math.PI * 2 / 10 * i + t * 0.25;
                Location p = at.clone().add(Math.cos(a) * r, Math.sin(a) * r, 0);
                p = rotateAroundY(at, p, at.getYaw());
                Compat.spawn(w, Compat.END_ROD, p, 1, 0.0, 0.0, 0.0, 0.0);
                if (t % 3 == 0) Compat.spawn(w, Compat.GLOW, p, 1, 0.02, 0.02, 0.02, 0.0);
            }
            if (t % 6 == 0) Compat.spawn(w, Compat.ENCHANT, at, 4, 0.3, 0.3, 0.3, 0.4);
        }, null);
    }

    private static Location rotateAroundY(Location pivot, Location point, float yawDeg) {
        double rad = Math.toRadians(yawDeg);
        double dx = point.getX() - pivot.getX();
        double dz = point.getZ() - pivot.getZ();
        double nx = dx * Math.cos(rad) - dz * Math.sin(rad);
        double nz = dx * Math.sin(rad) + dz * Math.cos(rad);
        return new Location(pivot.getWorld(), pivot.getX() + nx, point.getY(), pivot.getZ() + nz,
                point.getYaw(), point.getPitch());
    }

    /** Un anillo de puertas alrededor de un centro, mirando hacia dentro. */
    private List<Location> ringOfGates(Location c, double radius, int count, int lifeTicks) {
        List<Location> spots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 / count * i;
            Location at = c.clone().add(Math.cos(a) * radius, 2.2 + (i % 2) * 0.8, Math.sin(a) * radius);
            at.setYaw((float) Math.toDegrees(a + Math.PI / 2));
            gate(at, lifeTicks);
            spots.add(at);
        }
        return spots;
    }

    /**
     * Dispara UNA hoja desde un punto hacia un objetivo. Viaja recta, silba, y al
     * tocar hace daño en un radio corto. Se recoge sola.
     */
    private void shootBlade(Location from, Location to, ItemStack arma, float scale, double dmg, double speed) {
        /* La escala de "majestuoso": todo proyectil sale mas grande y pega mas. */
        final float escala = scale * 1.3f;
        final double dano = dmg + 2;
        World w = world();
        Vector dir = to.toVector().subtract(from.toVector());
        double dist = Math.max(0.01, dir.length());
        dir.multiply(1.0 / dist);
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
        float pitch = (float) Math.toDegrees(Math.asin(-dir.getY()));
        ItemDisplay d = bladeDisplay(from.clone(), arma, escala, yaw, pitch);
        if (d == null) return;
        int life = (int) Math.ceil(dist / speed) + 2;
        Compat.sound(w, from, "item.trident.throw", 1.0f, 1.5f);
        Vector step = dir.clone().multiply(speed);
        animate(life, t -> {
            if (!d.isValid()) return;
            Location next = d.getLocation().add(step);
            d.teleport(next);
            Compat.spawn(w, Compat.ELECTRIC_SPARK, next, 1, 0.03, 0.03, 0.03, 0.0);
            if (t % 2 == 0) Compat.spawn(w, Compat.END_ROD, next, 1, 0.0, 0.0, 0.0, 0.0);
            for (Player p : Fx.playersNear(next, 1.3)) {
                hit(p, dano);
                push(p, step.clone().normalize().multiply(0.35).setY(0.12));
                Compat.spawn(w, Compat.CRIT, p.getLocation().add(0, 1, 0), 8, 0.2, 0.3, 0.2, 0.1);
                Compat.sound(w, p.getLocation(), "block.bell.use", 0.8f, 1.9f);
            }
        }, () -> {
            if (d.isValid()) {
                Location end = d.getLocation();
                Compat.spawn(w, Compat.CRIT, end, 6, 0.2, 0.2, 0.2, 0.05);
                Compat.sound(w, end, "item.trident.hit_ground", 0.9f, 1.4f);
                Fx.safeRemove(d);
            }
        });
        expire(d, life + 10);
    }

    /** Una hoja que CAE del cielo sobre un punto y castiga el circulo. */
    private void fallBlade(Location groundSpot, ItemStack arma, float scale, double dmg, double radius, int fallTicks) {
        final float escala = scale * 1.5f;
        final double dano = dmg + 3;
        final double radio = radius * 1.25;
        World w = world();
        Location ground = Fx.ground(groundSpot, 10);
        Location top = ground.clone().add(0, 14, 0);
        ItemDisplay d = bladeDisplay(top, arma, escala, (float) (random.nextDouble() * 360), 90);
        if (d == null) return;
        Fx.ring(ground.clone().add(0, 0.15, 0), Math.max(0.8, radio * 0.7), 14, p ->
                Compat.spawn(w, Compat.GLOW, p, 1, 0.02, 0.02, 0.02, 0.0));
        double per = 14.0 / fallTicks;
        animate(fallTicks, t -> {
            if (d.isValid()) d.teleport(d.getLocation().subtract(0, per, 0));
            Compat.spawn(w, Compat.END_ROD, d.getLocation(), 1, 0.05, 0.1, 0.05, 0.0);
        }, () -> {
            Compat.spawn(w, Compat.CRIT, ground.clone().add(0, 0.4, 0), 14, radio * 0.4, 0.2, radio * 0.4, 0.08);
            Compat.spawn(w, Compat.BLOCK, ground.clone().add(0, 0.2, 0), 16, 0.3, 0.1, 0.3, 0.05,
                    Material.GOLD_BLOCK.createBlockData());
            Compat.sound(w, ground, "item.trident.hit_ground", 1.1f, 1.1f);
            Compat.sound(w, ground, "block.bell.use", 0.8f, 1.7f);
            for (Player p : Fx.playersNear(ground, radio)) {
                hit(p, dano);
            }
            if (d.isValid()) Fx.safeRemove(d);
        });
        expire(d, fallTicks + 8);
    }

    /** Encadena a un jugador: raiz, arcos de cadena y un tañido grave. */
    private void chain(Player p, int ticksHeld, double dmg) {
        if (p == null || chained.contains(p)) return;
        chained.add(p);
        World w = world();
        root(p, ticksHeld);
        Compat.sound(w, p.getLocation(), "block.chain.place", 1.4f, 0.6f);
        Compat.sound(w, p.getLocation(), "block.bell.resonate", 1.0f, 0.7f);
        if (dmg > 0) hit(p, dmg);
        animate(ticksHeld, t -> {
            Location base = p.getLocation();
            for (int i = 0; i < 3; i++) {
                double a = Math.PI * 2 / 3 * i + t * 0.2;
                Location q = base.clone().add(Math.cos(a) * 0.8, 0.2 + (t % 10) * 0.12, Math.sin(a) * 0.8);
                Compat.spawn(w, Compat.CRIT, q, 1, 0.0, 0.0, 0.0, 0.0);
                if (t % 4 == 0) Compat.spawn(w, Compat.WAX_OFF, q, 1, 0.02, 0.02, 0.02, 0.0);
            }
            if (t % 10 == 0) Compat.sound(w, base, "block.chain.step", 0.8f, 0.6f);
        }, () -> chained.remove(p));
    }

    /** El punto del que salen las armas: la puerta mas cercana al hombro de la reina. */
    private Location shoulder() {
        Location l = body().getLocation().add(0, 2.1, 0);
        Vector side = l.getDirection().setY(0).normalize().rotateAroundY(Math.PI / 2).multiply(0.9);
        return l.add(side);
    }

    /** Una hoja que cae y QUEDA CLAVADA en el suelo, punta abajo. */
    private ItemDisplay plantada(Location groundSpot, ItemStack arma, float scale) {
        World w = world();
        Location ground = Fx.ground(groundSpot, 10);
        ItemDisplay d = bladeDisplay(ground.clone().add(0, 12, 0), arma, scale, (float) (random.nextDouble() * 360), 90);
        if (d == null) return null;
        animate(4, t -> {
            if (d.isValid()) d.teleport(d.getLocation().subtract(0, 12.0 / 4, 0).add(0, 0, 0));
        }, () -> {
            if (!d.isValid()) return;
            d.teleport(ground.clone().add(0, 0.9 * scale * 0.5, 0));
            Compat.spawn(w, Compat.BLOCK, ground.clone().add(0, 0.2, 0), 10, 0.2, 0.1, 0.2, 0.04,
                    Material.GOLD_BLOCK.createBlockData());
            Compat.sound(w, ground, "item.trident.hit_ground", 1.0f, 1.0f);
            Compat.sound(w, ground, "block.bell.use", 0.5f, 1.9f);
        });
        return d;
    }

    /** El estallido de una hoja clavada: fogonazo, metralla y daño en circulo. */
    private void estallidoHoja(Location at, double dmg, double radius) {
        World w = world();
        Compat.spawn(w, Compat.EXPLOSION, at, 1);
        Compat.spawn(w, Compat.FLASH, at, 1);
        Compat.spawn(w, Compat.CRIT, at.clone().add(0, 0.5, 0), 18, radius * 0.4, 0.4, radius * 0.4, 0.12);
        Compat.spawn(w, Compat.BLOCK, at.clone().add(0, 0.3, 0), 20, 0.4, 0.2, 0.4, 0.06,
                Material.GOLD_BLOCK.createBlockData());
        Compat.sound(w, at, "item.mace.smash_ground", 1.0f, 1.4f);
        Compat.sound(w, at, "block.bell.use", 0.9f, 1.7f);
        for (Player p : Fx.playersNear(at, radius)) {
            hit(p, dmg);
            lift(p, p.getLocation().toVector().subtract(at.toVector()).normalize().multiply(0.5).setY(0.45));
        }
    }

    // ================================================================== FASE 0
    // Presencia: pasivas cortas que mantienen a la reina "viva" entre habilidades.

    /** 1. Aura de la Reina: destello dorado que empuja a quien se arrima demasiado. */
    public void auraDeLaReina() {
        World w = world();
        Location c = body().getLocation().add(0, 1, 0);
        Compat.sound(w, c, "block.amethyst_block.chime", 1.4f, 1.8f);
        Fx.ring(c.clone().subtract(0, 0.8, 0), 2.6, 22, p ->
                Compat.spawn(w, Compat.GLOW, p, 1, 0.02, 0.05, 0.02, 0.0));
        for (Player p : targets(2.8)) {
            hit(p, 4);
            push(p, p.getLocation().toVector().subtract(c.toVector()).setY(0.4).normalize().multiply(0.8));
        }
    }

    /** 2. Desden: señala al mas lejano y le manda UNA hoja certera. */
    public void desden() {
        Player p = farthestTargets(1).stream().findFirst().orElse(null);
        if (p == null) return;
        face(p.getLocation());
        warn(Component.text("Alba te ha mirado, " + p.getName() + ".", ORO_PALIDO));
        gate(shoulder(), 14);
        later(8, () -> shootBlade(shoulder(), p.getEyeLocation(), goldBlade(), 1.1f, 6, 1.4));
    }

    // ================================================================== FASE 1
    // El Heraldo, montado: cargas y lanzas.

    /** 3. Carga Celeste: el corcel arrolla en linea recta. */
    public void cargaCeleste() {
        Player p = randomTarget();
        if (p == null) return;
        World w = world();
        var monturaViva = mounted();
        Location from = loc().clone();
        Vector dir = p.getLocation().toVector().subtract(from.toVector()).setY(0).normalize();
        warn(Component.text("¡Carga celeste!", ORO));
        Compat.sound(w, from, "entity.horse.angry", 1.3f, 1.1f);
        busyFor(24);
        animate(20, t -> {
            var quien = monturaViva && steed.isValid() ? steed : boss;
            quien.setVelocity(dir.clone().multiply(0.85).setY(-0.05));
            Location at = quien.getLocation();
            Compat.spawn(w, Compat.CLOUD, at, 3, 0.3, 0.1, 0.3, 0.02);
            Compat.spawn(w, Compat.FIREWORK_SPARK, at.clone().add(0, 1, 0), 2, 0.2, 0.3, 0.2, 0.03);
            if (t % 4 == 0) Compat.sound(w, at, "entity.horse.gallop", 1.1f, 1.0f);
            for (Player v : Fx.playersNear(at, 1.8)) {
                hit(v, 8);
                lift(v, dir.clone().multiply(0.9).setY(0.55));
                Compat.sound(w, v.getLocation(), "entity.player.attack.knockback", 1.2f, 0.8f);
            }
        }, null);
    }

    /** 4. Lanzas del Alba: tres lanzas plateadas a los tres mas cercanos. */
    public void lanzasDelAlba() {
        List<Player> ps = nearestTargets(3);
        if (ps.isEmpty()) return;
        warn(Component.text("Lanzas del alba.", PLATA));
        int i = 0;
        for (Player p : ps) {
            int delay = i++ * 6;
            later(delay, () -> {
                Location g = shoulder();
                gate(g, 12);
                later(6, () -> shootBlade(g, p.getEyeLocation(), lance(), 1.2f, 7, 1.5));
            });
        }
    }

    /** 5. Lluvia Menor: seis hojas caen alrededor del objetivo. */
    public void lluviaMenor() {
        Player p = randomTarget();
        if (p == null) return;
        Location c = p.getLocation();
        warn(Component.text("El tesoro gotea.", ORO_PALIDO));
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2 / 6 * i;
            Location spot = c.clone().add(Math.cos(a) * 2.2, 0, Math.sin(a) * 2.2);
            later(i * 3, () -> fallBlade(spot, goldBlade(), 1.3f, 5, 1.6, 10));
        }
    }

    /** 6. Pisoteo Solar: el corcel se alza y la onda tira al circulo entero. */
    public void pisoteoSolar() {
        World w = world();
        Location c = loc().clone();
        Compat.sound(w, c, "entity.horse.angry", 1.4f, 0.7f);
        busyFor(18);
        later(10, () -> {
            Compat.sound(w, c, "item.mace.smash_ground_heavy", 1.2f, 1.1f);
            Compat.sound(w, c, "block.bell.use", 1.0f, 1.5f);
            Compat.spawn(w, Compat.BLOCK, c.clone().add(0, 0.2, 0), 40, 1.6, 0.2, 1.6, 0.06,
                    Material.GOLD_BLOCK.createBlockData());
            for (int r = 1; r <= 5; r++) {
                double rr = r;
                later(r * 2, () -> Fx.ring(c.clone().add(0, 0.15, 0), rr, (int) (8 + rr * 5), q ->
                        Compat.spawn(w, Compat.CRIT, q, 1, 0.02, 0.05, 0.02, 0.01)));
            }
            for (Player p : targets(5.5)) {
                hit(p, 7);
                lift(p, new Vector(0, 0.75, 0));
            }
        });
    }

    /** 7. Clarin del Heraldo: campanas agudas; la reina se blinda unos segundos. */
    public void clarin() {
        World w = world();
        Location c = body().getLocation().add(0, 1.5, 0);
        warn(Component.text("El clarin suena: la reina se blinda.", ORO_PALIDO));
        Compat.sound(w, c, "block.bell.use", 1.6f, 1.9f);
        later(4, () -> Compat.sound(w, c, "block.bell.resonate", 1.3f, 1.6f));
        later(10, () -> Compat.sound(w, c, "block.amethyst_cluster.break", 1.2f, 1.9f));
        boss.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.RESISTANCE, 80, 1, false, false));
        animate(80, t -> {
            if (t % 4 == 0) {
                Compat.spawn(w, Compat.END_ROD, body().getLocation().add(0, 1.2, 0), 2, 0.5, 0.7, 0.5, 0.01);
            }
        }, null);
    }

    /** 8. Latigo Dorado: un arco de chispas barre delante del corcel. */
    public void latigoDorado() {
        World w = world();
        Location c = body().getLocation();
        Vector fwd = c.getDirection().setY(0).normalize();
        warn(Component.text("El latigo del alba.", ORO));
        Compat.sound(w, c, "entity.player.attack.sweep", 1.4f, 1.5f);
        animate(10, t -> {
            double ang = -60 + t * 12;
            Vector v = fwd.clone().rotateAroundY(Math.toRadians(ang)).multiply(2.6);
            Location q = c.clone().add(v).add(0, 1, 0);
            Compat.spawn(w, Compat.SWEEP_ATTACK, q, 1, 0, 0, 0, 0);
            Compat.spawn(w, Compat.GLOW, q, 3, 0.15, 0.2, 0.15, 0.01);
            for (Player p : Fx.playersNear(q, 1.4)) {
                hit(p, 6);
                push(p, v.clone().normalize().multiply(0.5).setY(0.25));
            }
        }, null);
    }

    // ================================================================== FASE 2
    // El Tesoro: las puertas se abren.

    /** 9. Puerta Dorada: un anillo de puertas dispara al centro. */
    public void puertaDorada() {
        Player p = randomTarget();
        if (p == null) return;
        Location c = p.getLocation().add(0, 1, 0);
        warn(Component.text("«Abrete.»", ORO, TextDecoration.BOLD));
        List<Location> gates = ringOfGates(c, 6.0, 6, 26);
        int i = 0;
        for (Location g : gates) {
            later(10 + (i++ % 3) * 4, () -> shootBlade(g, c.clone(), goldBlade(), 1.1f, 6, 1.5));
        }
    }

    /** 10. Cadenas del Cielo: encadena a los dos mas cercanos. */
    public void cadenasDelCielo() {
        List<Player> ps = nearestTargets(2);
        if (ps.isEmpty()) return;
        warn(Component.text("Cadenas del cielo: ¡suelta o rompe!", PLATA));
        for (Player p : ps) chain(p, 50, 3);
    }

    /** 11. Salva Real: ocho hojas en abanico desde el hombro. */
    public void salvaReal() {
        World w = world();
        Location g = shoulder();
        gate(g, 20);
        Compat.sound(w, g, "block.respawn_anchor.charge", 1.0f, 1.6f);
        Vector base = body().getLocation().getDirection().setY(0).normalize();
        for (int i = 0; i < 8; i++) {
            double ang = -52.5 + i * 15;
            Vector dir = base.clone().rotateAroundY(Math.toRadians(ang));
            Location to = g.clone().add(dir.multiply(16)).subtract(0, 1.2, 0);
            ItemStack arma = i % 2 == 0 ? goldBlade() : silverBlade();
            later(6 + i, () -> shootBlade(g, to, arma, 1.0f, 5, 1.6));
        }
    }

    /** 12. Espejismo Platino: hojas plateadas orbitan a la dama y salen despedidas. */
    public void espejismoPlatino() {
        World w = world();
        List<ItemDisplay> orbit = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ItemDisplay d = bladeDisplay(body().getLocation().add(0, 1.6, 0), silverBlade(), 1.0f, i * 72, 0);
            if (d != null) orbit.add(d);
        }
        Compat.sound(w, loc(), "block.amethyst_block.chime", 1.2f, 1.4f);
        animate(40, t -> {
            for (int i = 0; i < orbit.size(); i++) {
                ItemDisplay d = orbit.get(i);
                if (!d.isValid()) continue;
                double a = Math.PI * 2 / orbit.size() * i + t * 0.22;
                d.teleport(body().getLocation().add(Math.cos(a) * 2.0, 1.4 + Math.sin(t * 0.3 + i) * 0.3, Math.sin(a) * 2.0));
                if (t % 3 == 0) Compat.spawn(w, Compat.ELECTRIC_SPARK, d.getLocation(), 1, 0.02, 0.02, 0.02, 0.0);
            }
        }, () -> {
            List<Player> ps = targets(24);
            int i = 0;
            for (ItemDisplay d : orbit) {
                if (!d.isValid()) continue;
                Player p = ps.isEmpty() ? null : ps.get(i++ % ps.size());
                Location from = d.getLocation();
                Fx.safeRemove(d);
                if (p != null) shootBlade(from, p.getEyeLocation(), silverBlade(), 1.0f, 6, 1.7);
            }
        });
        orbit.forEach(d -> expire(d, 60));
    }

    /** 13. Juicio Menor: una hoja cae sobre CADA jugador presente. */
    public void juicioMenor() {
        warn(Component.text("Juicio menor: no se queden quietos.", ORO_PALIDO));
        for (Player p : targets(40)) {
            fallBlade(p.getLocation(), goldBlade(), 1.5f, 6, 1.8, 12);
        }
    }

    /** 14. Tesoro Giratorio: un disco de hojas gira y se expande. */
    public void tesoroGiratorio() {
        World w = world();
        Location c = loc().clone().add(0, 1, 0);
        warn(Component.text("El tesoro gira.", ORO));
        Compat.sound(w, c, "block.bell.use", 1.1f, 1.6f);
        animate(46, t -> {
            double r = 1.5 + t * 0.16;
            for (int i = 0; i < 3; i++) {
                double a = t * 0.3 + Math.PI * 2 / 3 * i;
                Location q = c.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r);
                Compat.spawn(w, Compat.END_ROD, q, 1, 0.0, 0.0, 0.0, 0.0);
                Compat.spawn(w, Compat.CRIT, q, 1, 0.05, 0.05, 0.05, 0.01);
                for (Player p : Fx.playersNear(q, 1.1)) {
                    hit(p, 5);
                    push(p, q.toVector().subtract(c.toVector()).normalize().multiply(0.45).setY(0.2));
                }
            }
            if (t % 8 == 0) Compat.sound(w, c, "entity.player.attack.sweep", 0.9f, 1.5f);
        }, null);
    }

    // ================================================================== FASE 3
    // El Duelista, a pie. El area no descansa.

    /** 15. Tajo Aureo: combo de tres cortes pegado al objetivo. */
    public void tajoAureo() {
        Player p = nearestTargets(1).stream().findFirst().orElse(null);
        if (p == null) return;
        World w = world();
        busyFor(22);
        for (int hitN = 0; hitN < 3; hitN++) {
            int n = hitN;
            later(hitN * 7, () -> {
                if (!alive() || !p.isOnline()) return;
                face(p.getLocation());
                Location q = p.getLocation().add(0, 1, 0);
                Compat.spawn(w, Compat.SWEEP_ATTACK, q, 1, 0, 0, 0, 0);
                Compat.spawn(w, Compat.GLOW, q, 6, 0.3, 0.4, 0.3, 0.02);
                Compat.sound(w, q, "entity.player.attack.sweep", 1.3f, 1.2f + n * 0.2f);
                Compat.sound(w, q, "block.bell.use", 0.6f, 1.9f);
                if (p.getLocation().distance(body().getLocation()) <= 3.6) {
                    hit(p, n == 2 ? 9 : 6);
                    if (n == 2) lift(p, new Vector(0, 0.5, 0));
                }
            });
        }
    }

    /** 16. Estocada del Sol: cierra la distancia en un destello. */
    public void estocadaDelSol() {
        Player p = randomTarget();
        if (p == null) return;
        World w = world();
        Location from = body().getLocation();
        Location to = p.getLocation();
        warn(Component.text("¡Estocada!", ORO));
        Compat.sound(w, from, "entity.player.attack.crit", 1.4f, 1.6f);
        Vector paso = to.toVector().subtract(from.toVector());
        int puntos = (int) Math.max(2, paso.length() * 2);
        paso.multiply(1.0 / puntos);
        Location cursor = from.clone().add(0, 1, 0);
        for (int i = 0; i < puntos; i++) {
            cursor.add(paso);
            Compat.spawn(w, Compat.END_ROD, cursor, 1, 0.02, 0.02, 0.02, 0.0);
        }
        boss.teleport(to.clone().add(to.getDirection().setY(0).normalize().multiply(-1.2)));
        later(2, () -> {
            Compat.spawn(w, Compat.FLASH, boss.getLocation(), 1);
            Compat.sound(w, boss.getLocation(), "item.trident.hit", 1.2f, 1.3f);
            for (Player v : targets(2.6)) {
                hit(v, 8);
                push(v, boss.getLocation().getDirection().multiply(0.6).setY(0.3));
            }
        });
    }

    /** 17. Torbellino Dorado: gira con el tesoro alrededor, tres vueltas. */
    public void torbellinoDorado() {
        World w = world();
        busyFor(30);
        Compat.sound(w, loc(), "block.respawn_anchor.charge", 1.1f, 1.8f);
        animate(30, t -> {
            Location c = body().getLocation();
            double a = t * 0.55;
            for (int i = 0; i < 2; i++) {
                Location q = c.clone().add(Math.cos(a + i * Math.PI) * 2.4, 1.1, Math.sin(a + i * Math.PI) * 2.4);
                Compat.spawn(w, Compat.SWEEP_ATTACK, q, 1, 0, 0, 0, 0);
                Compat.spawn(w, Compat.ELECTRIC_SPARK, q, 2, 0.1, 0.15, 0.1, 0.01);
            }
            if (t % 6 == 0) Compat.sound(w, c, "entity.player.attack.sweep", 1.1f, 1.0f + t * 0.02f);
            for (Player p : targets(2.9)) {
                hit(p, 4);
                push(p, p.getLocation().toVector().subtract(c.toVector()).normalize().multiply(0.5).setY(0.25));
            }
        }, null);
    }

    /** 18. Muro de Espadas: una empalizada de hojas corta la retirada. */
    public void muroDeEspadas() {
        Player p = randomTarget();
        if (p == null) return;
        World w = world();
        Location c = p.getLocation();
        warn(Component.text("Muro de espadas: la salida se cierra.", PLATA));
        List<ItemDisplay> wall = new ArrayList<>();
        int postes = 10;
        for (int i = 0; i < postes; i++) {
            double a = Math.PI * 2 / postes * i;
            Location spot = Fx.ground(c.clone().add(Math.cos(a) * 4.2, 1, Math.sin(a) * 4.2), 8);
            ItemDisplay d = bladeDisplay(spot.clone().add(0, 0.9, 0), goldBlade(), 1.6f,
                    (float) Math.toDegrees(a), 90);
            if (d != null) wall.add(d);
            Compat.sound(w, spot, "item.trident.hit_ground", 0.7f, 1.2f);
        }
        animate(70, t -> {
            for (ItemDisplay d : wall) {
                if (!d.isValid()) continue;
                if (t % 4 == 0) Compat.spawn(w, Compat.GLOW, d.getLocation(), 1, 0.05, 0.3, 0.05, 0.0);
                for (Player v : Fx.playersNear(d.getLocation(), 1.0)) {
                    hit(v, 3);
                    push(v, v.getLocation().toVector().subtract(c.toVector()).normalize().multiply(-0.5).setY(0.15));
                }
            }
        }, () -> wall.forEach(d -> {
            if (d.isValid()) {
                Compat.spawn(w, Compat.POOF, d.getLocation(), 4, 0.1, 0.2, 0.1, 0.01);
                Fx.safeRemove(d);
            }
        }));
        wall.forEach(d -> expire(d, 80));
    }

    /** 19. Paso Fulgor: tres parpadeos con corte en cada aparicion. */
    public void pasoFulgor() {
        World w = world();
        busyFor(26);
        for (int i = 0; i < 3; i++) {
            later(i * 8, () -> {
                Player p = randomTarget();
                if (p == null || !alive()) return;
                Location from = body().getLocation();
                Compat.spawn(w, Compat.FLASH, from, 1);
                Compat.sound(w, from, "entity.enderman.teleport", 1.0f, 1.7f);
                boss.teleport(p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(-1.4)));
                Compat.spawn(w, Compat.END_ROD, boss.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.03);
                Compat.sound(w, boss.getLocation(), "entity.player.attack.crit", 1.3f, 1.4f);
                if (p.getLocation().distance(boss.getLocation()) <= 3.2) hit(p, 6);
            });
        }
    }

    /** 20. Quiebra Guardia: golpe seco que rompe la postura y debilita. */
    public void quiebraGuardia() {
        Player p = nearestTargets(1).stream().findFirst().orElse(null);
        if (p == null || p.getLocation().distance(body().getLocation()) > 4.5) return;
        World w = world();
        face(p.getLocation());
        Compat.sound(w, p.getLocation(), "item.mace.smash_ground", 1.2f, 1.3f);
        Compat.spawn(w, Compat.CRIT, p.getLocation().add(0, 1, 0), 16, 0.3, 0.4, 0.3, 0.1);
        hit(p, 7);
        lift(p, new Vector(0, 0.65, 0));
        p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.WEAKNESS, 100, 0, false, true));
        warn(Component.text(p.getName() + " pierde la guardia.", ORO_PALIDO));
    }

    // ================================================================== FASE 4
    // La Tormenta, remontado: el cielo entero es arsenal.

    /** 21. Tormenta de Tesoros: bombardeo largo sobre toda el area. */
    public void tormentaDeTesoros() {
        World w = world();
        Location c = center();
        warn(Component.text("«LLUEVE ORO.»", ORO, TextDecoration.BOLD));
        Compat.sound(w, c, "entity.lightning_bolt.thunder", 1.0f, 1.5f);
        animate(60, t -> {
            if (t % 4 != 0) return;
            double a = random.nextDouble() * Math.PI * 2;
            double r = random.nextDouble() * 9;
            Location spot = c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
            fallBlade(spot, random.nextBoolean() ? goldBlade() : silverBlade(), 1.3f, 5, 1.6, 9);
        }, null);
    }

    /** 22. Lanza del Ocaso: UNA lanza enorme sobre el mas dañino. */
    public void lanzaDelOcaso() {
        Player p = targets().stream().findFirst().orElse(null);
        if (p == null) return;
        World w = world();
        Location ground = Fx.ground(p.getLocation(), 8);
        warn(Component.text("La Lanza del Ocaso busca a " + p.getName() + ".", PLATA));
        Compat.sound(w, ground, "block.respawn_anchor.charge", 1.2f, 0.8f);
        Fx.ring(ground.clone().add(0, 0.15, 0), 2.6, 26, q ->
                Compat.spawn(w, Compat.GLOW, q, 1, 0.02, 0.02, 0.02, 0.0));
        later(18, () -> {
            fallBlade(ground, lance(), 4.5f, 12, 3.2, 7);
            later(8, () -> {
                Compat.spawn(w, Compat.EXPLOSION, ground, 1);
                Compat.sound(w, ground, "item.trident.thunder", 1.3f, 0.9f);
            });
        });
    }

    /** 23. Cometas Gemelos: dos hojas cruzan el area en arco. */
    public void cometasGemelos() {
        World w = world();
        Location c = center().clone().add(0, 6, 0);
        List<Player> ps = pickTargets(2);
        if (ps.isEmpty()) return;
        warn(Component.text("Cometas gemelos.", ORO_PALIDO));
        int i = 0;
        for (Player p : ps) {
            Location from = c.clone().add((i == 0 ? -10 : 10), 2, (i == 0 ? -10 : 10));
            i++;
            gate(from, 12);
            later(6, () -> shootBlade(from, p.getEyeLocation(), goldBlade(), 1.8f, 9, 1.2));
        }
    }

    /** 24. Vinculo Dorado: ata a dos jugadores entre si; alejarse duele. */
    public void vinculoDorado() {
        List<Player> ps = pickTargets(2);
        if (ps.size() < 2) return;
        Player a = ps.get(0);
        Player b = ps.get(1);
        World w = world();
        warn(Component.text(a.getName() + " y " + b.getName() + " quedan vinculados: no se separen.", ORO_PALIDO));
        Compat.sound(w, a.getLocation(), "block.chain.place", 1.2f, 1.4f);
        animate(80, t -> {
            if (!a.isOnline() || !b.isOnline()) return;
            if (t % 3 == 0) {
                Location pa = a.getLocation().add(0, 1, 0);
                Vector delta = b.getLocation().add(0, 1, 0).toVector().subtract(pa.toVector());
                int pasos = (int) Math.max(2, delta.length() * 1.5);
                delta.multiply(1.0 / pasos);
                Location cur = pa.clone();
                for (int k = 0; k < pasos; k++) {
                    cur.add(delta);
                    Compat.spawn(w, Compat.WAX_ON, cur, 1, 0.02, 0.02, 0.02, 0.0);
                }
            }
            if (t % 20 == 0 && a.getLocation().distance(b.getLocation()) > 9) {
                hit(a, 3);
                hit(b, 3);
                Compat.sound(w, a.getLocation(), "block.chain.break", 1.0f, 0.7f);
            }
        }, null);
    }

    /** 25. Halo Reflector: escudo que devuelve una fraccion de cada golpe. */
    public void haloReflector() {
        World w = world();
        warn(Component.text("Un halo cubre a la dama: golpearlo cuesta sangre.", PLATA));
        Compat.sound(w, loc(), "block.amethyst_block.resonate", 1.3f, 1.5f);
        reflectUntil = ticks() + 70;
        animate(70, t -> {
            if (t % 3 == 0) {
                double a = t * 0.4;
                Location q = body().getLocation().add(Math.cos(a) * 1.4, 1.1 + Math.sin(a * 0.7) * 0.6, Math.sin(a) * 1.4);
                Compat.spawn(w, Compat.END_ROD, q, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }, null);
    }

    private long reflectUntil = -1;

    @Override
    public void onDamaged(Player attacker, double amount) {
        if (reflectUntil > 0 && ticks() < reflectUntil && attacker != null && amount > 0) {
            hit(attacker, Math.min(4, amount * 0.4));
            Compat.spawn(world(), Compat.GLOW, attacker.getLocation().add(0, 1, 0), 6, 0.2, 0.3, 0.2, 0.02);
            Compat.sound(world(), attacker.getLocation(), "block.amethyst_cluster.break", 0.8f, 1.8f);
        }
    }

    /** 26. Galope Solar: cruza el area en cruz, dos pasadas. */
    public void galopeSolar() {
        World w = world();
        Location c = center();
        busyFor(40);
        warn(Component.text("¡Galope solar!", ORO));
        for (int pass = 0; pass < 2; pass++) {
            int d = pass * 20;
            later(d, () -> {
                Player p = randomTarget();
                if (p == null || !alive()) return;
                Vector dir = p.getLocation().toVector().subtract(loc().toVector()).setY(0).normalize();
                Compat.sound(w, loc(), "entity.horse.gallop", 1.3f, 0.9f);
                animate(16, t -> {
                    var quien = mounted() && steed.isValid() ? steed : boss;
                    quien.setVelocity(dir.clone().multiply(0.95).setY(-0.05));
                    Location at = quien.getLocation();
                    Compat.spawn(w, Compat.FLAME, at, 2, 0.2, 0.1, 0.2, 0.01);
                    Compat.spawn(w, Compat.FIREWORK_SPARK, at.clone().add(0, 1.2, 0), 2, 0.2, 0.3, 0.2, 0.02);
                    for (Player v : Fx.playersNear(at, 1.8)) {
                        hit(v, 7);
                        lift(v, dir.clone().multiply(0.8).setY(0.5));
                    }
                }, null);
            });
        }
    }

    /** 3b. Juicio de Hojas: el swordfall completo sobre un jugador. */
    public void juicioDeHojas() {
        Player p = randomTarget();
        if (p == null) return;
        World w = world();
        Location c = p.getLocation().clone();
        warn(Component.text("El cielo dicta sobre " + p.getName() + ".", ORO, TextDecoration.BOLD));
        Compat.sound(w, c, "block.respawn_anchor.charge", 1.0f, 0.7f);
        busyFor(56);
        for (int i = 0; i < 12; i++) {
            double a = Math.PI * 2 / 12 * i;
            Location spot = c.clone().add(Math.cos(a) * 3.0, 0, Math.sin(a) * 3.0);
            later(4 + i * 2, () -> fallBlade(spot, goldBlade(), 1.6f, 6, 1.6, 5));
        }
        later(34, () -> {
            Compat.sound(w, c, "entity.lightning_bolt.thunder", 0.8f, 0.6f);
            fallBlade(c, goldBlade(), 4.2f, 12, 3.0, 8);
        });
    }

    /** 6. Siembra de Acero: un pasillo de espadas clavadas que estalla en cadena. */
    public void siembraDeAcero() {
        Player p = randomTarget();
        if (p == null) return;
        World w = world();
        Location from = body().getLocation().clone();
        org.bukkit.util.Vector dir = p.getLocation().toVector().subtract(from.toVector()).setY(0).normalize();
        warn(Component.text("Siembra de acero: no pisen la hilera.", ORO_PALIDO));
        List<ItemDisplay> hilera = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            Location spot = from.clone().add(dir.clone().multiply(1.8 * i));
            int d = i * 3;
            later(d, () -> {
                ItemDisplay pl = plantada(spot, goldBlade(), 1.7f);
                if (pl != null) hilera.add(pl);
            });
        }
        later(80, () -> {
            for (int i = 0; i < hilera.size(); i++) {
                ItemDisplay pl = hilera.get(i);
                later(i * 4, () -> {
                    if (pl == null || !pl.isValid()) return;
                    Location at = pl.getLocation().clone();
                    Fx.safeRemove(pl);
                    estallidoHoja(at, 9, 2.4);
                });
            }
        });
        hilera.forEach(d -> { if (d != null) expire(d, 130); });
    }

    /** 11. Cosecha Clavada: una espada plantada donde pisaba CADA jugador; estallan en cruz. */
    public void cosechaClavada() {
        World w = world();
        List<Player> ps = targets(36);
        if (ps.isEmpty()) return;
        warn(Component.text("Cosecha clavada: MUEVANSE de donde estan.", ORO, TextDecoration.BOLD));
        for (Player p : ps) {
            Location donde = p.getLocation().clone();
            ItemDisplay pl = plantada(donde, silverBlade(), 2.0f);
            if (pl == null) continue;
            expire(pl, 70);
            animate(46, t -> {
                if (t % 6 == 0 && pl.isValid()) {
                    Fx.ring(pl.getLocation().clone().subtract(0, 0.5, 0).add(0, 0.2, 0), 1.6, 12, q ->
                            Compat.spawn(w, Compat.GLOW, q, 1, 0.02, 0.02, 0.02, 0.0));
                }
            }, () -> {
                if (!pl.isValid()) return;
                Location at = pl.getLocation().clone();
                Fx.safeRemove(pl);
                estallidoHoja(at, 8, 1.6);
                org.bukkit.util.Vector[] brazos = {new org.bukkit.util.Vector(1, 0, 0), new org.bukkit.util.Vector(-1, 0, 0),
                        new org.bukkit.util.Vector(0, 0, 1), new org.bukkit.util.Vector(0, 0, -1)};
                for (org.bukkit.util.Vector b : brazos) {
                    for (int d = 1; d <= 3; d++) {
                        Location q = at.clone().add(b.clone().multiply(d));
                        Compat.spawn(w, Compat.CRIT, q, 6, 0.15, 0.3, 0.15, 0.05);
                        Compat.spawn(w, Compat.END_ROD, q, 2, 0.05, 0.2, 0.05, 0.01);
                        for (Player v : Fx.playersNear(q, 1.1)) hit(v, 7);
                    }
                }
                Compat.sound(w, at, "entity.player.attack.sweep", 1.2f, 0.7f);
            });
        }
    }

    /** 13. Diluvio Menor: cuatro olas de espadas sobre el area entera. */
    public void diluvioMenor() {
        World w = world();
        Location c = center();
        warn(Component.text("Diluvio de acero.", PLATA, TextDecoration.BOLD));
        Compat.sound(w, c, "entity.lightning_bolt.thunder", 0.9f, 1.2f);
        busyFor(60);
        for (int ola = 0; ola < 4; ola++) {
            int d = ola * 14;
            later(d, () -> {
                Compat.sound(w, c, "block.bell.resonate", 1.1f, 1.5f);
                for (int i = 0; i < 8; i++) {
                    double a = random.nextDouble() * Math.PI * 2;
                    double r = random.nextDouble() * 9;
                    fallBlade(c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r),
                            random.nextBoolean() ? goldBlade() : silverBlade(), 1.6f, 6, 1.8, 6);
                }
            });
        }
    }

    /** 20. Replica Enterrada: planta 5 espadas alrededor mientras duela; detonan hacia fuera. */
    public void replicaEnterrada() {
        World w = world();
        Location c = body().getLocation().clone();
        warn(Component.text("La duelista siembra sus replicas.", ORO_PALIDO));
        List<ItemDisplay> replicas = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            double a = Math.PI * 2 / 5 * i;
            Location spot = c.clone().add(Math.cos(a) * 2.6, 0, Math.sin(a) * 2.6);
            later(i * 3, () -> {
                ItemDisplay pl = plantada(spot, goldBlade(), 1.5f);
                if (pl != null) replicas.add(pl);
            });
        }
        later(66, () -> {
            Compat.sound(w, body().getLocation(), "block.amethyst_cluster.break", 1.4f, 0.8f);
            for (ItemDisplay pl : replicas) {
                if (pl == null || !pl.isValid()) continue;
                Location at = pl.getLocation().clone();
                org.bukkit.util.Vector fuera = at.toVector().subtract(c.toVector()).setY(0).normalize();
                Fx.safeRemove(pl);
                estallidoHoja(at, 8, 2.2);
                shootBlade(at.clone().add(0, 1, 0), at.clone().add(fuera.multiply(14)).add(0, 1, 0),
                        goldBlade(), 1.0f, 6, 1.6);
            }
        });
        replicas.forEach(d -> { if (d != null) expire(d, 110); });
    }

    /** 27. Campo de Agujas: una jaula de 24 lanzas clavadas que implota. */
    public void campoDeAgujas() {
        Player objetivo = randomTarget();
        if (objetivo == null) return;
        World w = world();
        Location c = objetivo.getLocation().clone();
        warn(Component.text("Campo de agujas: la jaula se cierra.", ORO, TextDecoration.BOLD));
        Compat.sound(w, c, "block.respawn_anchor.charge", 1.2f, 0.6f);
        busyFor(50);
        List<ItemDisplay> jaula = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2 / 24 * i;
            Location spot = c.clone().add(Math.cos(a) * 7.0, 0, Math.sin(a) * 7.0);
            later(i, () -> {
                ItemDisplay pl = plantada(spot, lance(), 1.8f);
                if (pl != null) jaula.add(pl);
            });
        }
        animate(70, t -> {
            if (t < 26 || t % 5 != 0) return;
            Fx.ring(c.clone().add(0, 0.2, 0), 7.0, 40, q -> Compat.spawn(w, Compat.GLOW, q, 1, 0.02, 0.05, 0.02, 0.0));
            for (Player p : targets(9)) {
                double d = p.getLocation().distance(c);
                if (d > 6.2 && d < 7.8) {
                    hit(p, 4);
                    push(p, p.getLocation().toVector().subtract(c.toVector()).normalize().multiply(-0.5).setY(0.2));
                }
            }
        }, () -> {
            Compat.sound(w, c, "entity.lightning_bolt.impact", 1.0f, 0.9f);
            for (ItemDisplay pl : jaula) {
                if (pl == null || !pl.isValid()) continue;
                Location at = pl.getLocation().clone();
                Fx.safeRemove(pl);
                Compat.spawn(w, Compat.END_ROD, at, 4, 0.1, 0.4, 0.1, 0.02);
                shootBlade(at.clone().add(0, 1.2, 0), c.clone().add(0, 1.0, 0), lance(), 1.1f, 6, 1.8);
            }
            later(6, () -> estallidoHoja(c.clone(), 12, 3.2));
        });
        jaula.forEach(d -> { if (d != null) expire(d, 110); });
    }

    /** 30. LLUVIA DE MIL LANZAS: diez segundos de cielo lleno, con claros moviles. */
    public void lluviaDeMilLanzas() {
        World w = world();
        Location c = center();
        for (Player p : targets(60)) {
            p.showTitle(net.kyori.adventure.title.Title.title(
                    Component.text("✦ MIL LANZAS ✦", ORO, TextDecoration.BOLD),
                    Component.text("Busquen los claros de luz", ORO_PALIDO),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(300),
                            java.time.Duration.ofMillis(1600), java.time.Duration.ofMillis(500))));
        }
        warn(Component.text("«QUE NO QUEDE UN PALMO SIN ACERO.»", PLATA, TextDecoration.BOLD));
        Compat.sound(w, c, "entity.lightning_bolt.thunder", 1.2f, 0.5f);
        Compat.sound(w, c, "block.bell.resonate", 1.6f, 0.5f);
        busyFor(210);
        /* Dos claros de luz que se van moviendo: ahi no cae nada. */
        Location[] claros = {c.clone().add(5, 0, 5), c.clone().add(-5, 0, -5)};
        org.bukkit.util.Vector[] deriva = {
                new org.bukkit.util.Vector(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5).normalize().multiply(0.12),
                new org.bukkit.util.Vector(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5).normalize().multiply(0.12)};
        animate(200, t -> {
            for (int k = 0; k < claros.length; k++) {
                claros[k].add(deriva[k]);
                if (claros[k].distance(c) > 9) deriva[k].multiply(-1);
                if (t % 4 == 0) {
                    Fx.ring(Fx.ground(claros[k], 6).add(0, 0.2, 0), 2.6, 18, q ->
                            Compat.spawn(w, Compat.WAX_ON, q, 1, 0.02, 0.02, 0.02, 0.0));
                }
            }
            if (t % 2 != 0) return;
            if (t % 20 == 0) Compat.sound(w, c, "block.bell.use", 1.0f, 1.6f + (t % 60) * 0.005f);
            for (int i = 0; i < 5; i++) {
                double a = random.nextDouble() * Math.PI * 2;
                double r = random.nextDouble() * 11;
                Location spot = c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
                boolean aSalvo = false;
                for (Location claro : claros) {
                    if (spot.distance(claro) < 3.0) { aSalvo = true; break; }
                }
                if (aSalvo) continue;
                fallBlade(spot, random.nextInt(3) == 0 ? silverBlade() : lance(), 1.5f, 8, 1.5, 4);
            }
        }, () -> {
            Compat.spawn(w, Compat.FLASH, c, 1);
            Compat.sound(w, c, "item.trident.thunder", 1.4f, 0.8f);
            Compat.sound(w, c, "block.bell.resonate", 1.2f, 0.6f);
        });
    }

    // ================================================================== FASE 5
    // El Juicio.

    /** 27. Cien Puertas: el cielo entero se abre en anillos concentricos. */
    public void cienPuertas() {
        World w = world();
        Location c = center();
        warn(Component.text("«CIEN PUERTAS.»", ORO, TextDecoration.BOLD));
        Compat.sound(w, c, "block.end_portal.spawn", 0.9f, 1.4f);
        busyFor(50);
        ringOfGates(c, 6, 6, 60);
        later(8, () -> ringOfGates(c, 10, 8, 52));
        later(16, () -> ringOfGates(c, 14, 10, 44));
        animate(44, t -> {
            if (t % 3 != 0 || t < 10) return;
            List<Player> ps = targets(40);
            if (ps.isEmpty()) return;
            Player p = ps.get(random.nextInt(ps.size()));
            double a = random.nextDouble() * Math.PI * 2;
            double r = 6 + random.nextDouble() * 8;
            Location from = c.clone().add(Math.cos(a) * r, 2.5 + random.nextDouble() * 2, Math.sin(a) * r);
            shootBlade(from, p.getEyeLocation(), random.nextBoolean() ? goldBlade() : silverBlade(), 1.1f, 5, 1.7);
        }, null);
    }

    /** 28. Cadenas del Juicio: TODOS encadenados; la lluvia decide. */
    public void cadenasDelJuicio() {
        warn(Component.text("Cadenas del juicio: NADIE se mueve.", PLATA, TextDecoration.BOLD));
        Compat.sound(world(), loc(), "block.chain.place", 1.5f, 0.5f);
        for (Player p : targets(36)) {
            chain(p, 40, 2);
            later(20, () -> fallBlade(p.getLocation(), goldBlade(), 1.4f, 6, 1.8, 12));
        }
    }

    /**
     * 29. La Clave del Cielo: la espada enorme del final del swordfall, elevada a
     * rito: carga, giro y caida vertical con onda expansiva en tres anillos.
     */
    public void claveDelCielo() {
        World w = world();
        Location c = center();
        warn(Component.text("«CONOZCAN LA CLAVE DEL CIELO.»", ORO, TextDecoration.BOLD));
        busyFor(70);
        Location top = c.clone().add(0, 16, 0);
        ItemDisplay giant = bladeDisplay(top, goldBlade(), 7.0f, 0, 90);
        if (giant == null) return;
        Compat.sound(w, c, "block.respawn_anchor.charge", 1.3f, 0.6f);
        Compat.sound(w, c, "block.bell.resonate", 1.5f, 0.5f);
        animate(30, t -> {
            if (!giant.isValid()) return;
            giant.teleport(top.clone().add(0, Math.sin(t * 0.3) * 0.4, 0));
            double a = t * 0.5;
            for (int i = 0; i < 2; i++) {
                Location q = top.clone().add(Math.cos(a + i * Math.PI) * 2.5, 0, Math.sin(a + i * Math.PI) * 2.5);
                Compat.spawn(w, Compat.END_ROD, q, 1, 0.05, 0.05, 0.05, 0.0);
            }
            if (t % 6 == 0) Compat.sound(w, c, "block.amethyst_block.chime", 1.2f, 0.7f + t * 0.03f);
        }, () -> {
            int fall = 7;
            Compat.sound(w, c, "entity.lightning_bolt.thunder", 1.0f, 0.8f);
            animate(fall, t -> {
                if (!giant.isValid()) return;
                giant.teleport(giant.getLocation().subtract(0, 16.0 / fall, 0));
                Compat.spawn(w, Compat.CLOUD, giant.getLocation(), 4, 0.4, 0.6, 0.4, 0.02);
            }, () -> {
                if (giant.isValid()) Fx.safeRemove(giant);
                Compat.spawn(w, Compat.EXPLOSION_EMITTER, c, 1);
                Compat.spawn(w, Compat.FLASH, c, 1);
                Compat.spawn(w, Compat.BLOCK, c.clone().add(0, 0.3, 0), 70, 1.5, 0.3, 1.5, 0.1,
                        Material.GOLD_BLOCK.createBlockData());
                Compat.sound(w, c, "block.anvil.land", 1.2f, 0.5f);
                Compat.sound(w, c, "item.mace.smash_ground_heavy", 1.2f, 0.6f);
                Compat.sound(w, c, "block.bell.use", 1.6f, 0.5f);
                later(6, () -> Compat.sound(w, c, "block.bell.resonate", 1.2f, 0.75f));
                for (int ringN = 1; ringN <= 3; ringN++) {
                    int rn = ringN;
                    later(ringN * 4, () -> {
                        double radius = rn * 3.2;
                        Fx.ring(c.clone().add(0, 0.2, 0), radius, (int) (12 + radius * 4), q -> {
                            Compat.spawn(w, Compat.CRIT, q, 1, 0.05, 0.1, 0.05, 0.02);
                            Compat.spawn(w, Compat.GLOW, q, 1, 0.02, 0.05, 0.02, 0.0);
                        });
                        for (Player p : targets(radius + 1.2)) {
                            if (p.getLocation().distance(c) >= radius - 1.6) {
                                hit(p, 13 - rn * 2);
                                lift(p, new Vector(0, 0.6, 0));
                            }
                        }
                    });
                }
            });
        });
        expire(giant, 90);
    }

    /** 30. Veredicto: marca a un jugador; tres segundos despues, sentencia. */
    public void veredicto() {
        Player p = randomTarget();
        if (p == null) return;
        World w = world();
        warn(Component.text("VEREDICTO sobre " + p.getName() + ": corre.", ORO, TextDecoration.BOLD));
        Compat.sound(w, p.getLocation(), "block.bell.use", 1.4f, 0.5f);
        animate(60, t -> {
            if (!p.isOnline()) return;
            Location over = p.getLocation().add(0, 2.6, 0);
            Compat.spawn(w, Compat.GLOW, over, 2, 0.15, 0.1, 0.15, 0.0);
            if (t % 10 == 0) Compat.sound(w, p.getLocation(), "block.note_block.bell", 1.0f, 1.6f + t * 0.005f);
        }, () -> {
            if (!p.isOnline()) return;
            Location ground = Fx.ground(p.getLocation(), 8);
            Compat.spawn(w, Compat.FLASH, ground, 1);
            for (int i = 0; i < 4; i++) {
                double a = Math.PI / 2 * i;
                fallBlade(ground.clone().add(Math.cos(a) * 1.2, 0, Math.sin(a) * 1.2), silverBlade(), 1.6f, 7, 2.0, 6);
            }
        });
    }

    /** 31. Alba Final: anillos de luz expansivos que no dejan hueco donde esconderse. */
    public void albaFinal() {
        World w = world();
        Location c = body().getLocation();
        warn(Component.text("El alba se cierra sobre ustedes.", ORO_PALIDO));
        Compat.sound(w, c, "block.amethyst_block.resonate", 1.4f, 0.6f);
        animate(36, t -> {
            double r = 0.5 + t * 0.45;
            Fx.ring(c.clone().add(0, 0.2, 0), r, (int) (10 + r * 4), q -> {
                Compat.spawn(w, Compat.END_ROD, q, 1, 0.0, 0.02, 0.0, 0.0);
                if (t % 2 == 0) Compat.spawn(w, Compat.WAX_ON, q, 1, 0.02, 0.02, 0.02, 0.0);
            });
            for (Player p : targets(r + 0.8)) {
                double d = p.getLocation().distance(c);
                if (Math.abs(d - r) < 0.9) {
                    hit(p, 5);
                    push(p, p.getLocation().toVector().subtract(c.toVector()).normalize().multiply(0.6).setY(0.3));
                }
            }
        }, null);
    }

    /** 32. Corona de la Diosa: el estallido final; cuanto mas cerca, mas duele. */
    public void coronaDelDios() {
        World w = world();
        Location c = body().getLocation().add(0, 1.2, 0);
        warn(Component.text("«INCLINENSE.»", PLATA, TextDecoration.BOLD));
        busyFor(34);
        Compat.sound(w, c, "block.respawn_anchor.charge", 1.4f, 0.5f);
        animate(24, t -> {
            double r = 3.0 - t * 0.1;
            for (int i = 0; i < 6; i++) {
                double a = Math.PI * 2 / 6 * i + t * 0.3;
                Compat.spawn(w, Compat.END_ROD, c.clone().add(Math.cos(a) * r, Math.sin(t * 0.2) * 0.8, Math.sin(a) * r),
                        1, 0.0, 0.0, 0.0, 0.0);
            }
            if (t % 5 == 0) Compat.sound(w, c, "block.note_block.chime", 1.2f, 0.8f + t * 0.05f);
        }, () -> {
            Compat.spawn(w, Compat.FLASH, c, 1);
            Compat.spawn(w, Compat.EXPLOSION_EMITTER, c, 1);
            Compat.sound(w, c, "entity.lightning_bolt.impact", 1.2f, 0.7f);
            Compat.sound(w, c, "item.trident.thunder", 1.4f, 1.2f);
            for (Player p : targets(9)) {
                double d = Math.max(1.0, p.getLocation().distance(c));
                hit(p, Math.min(14, 16.0 / d * 2.2));
                push(p, p.getLocation().toVector().subtract(c.toVector()).normalize().multiply(1.1).setY(0.55));
            }
        });
    }
}
