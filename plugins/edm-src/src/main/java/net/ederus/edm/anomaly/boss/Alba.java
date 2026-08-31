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
        net.ederus.edm.anomaly.core.Glow.apply(steed, NamedTextColor.GOLD);
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
        hunt();
    }

    // ------------------------------------------------------------------ la caza

    /** El proximo tick en que el brazo descansado puede soltar otro basico. */
    private long proximoBasico = 0;

    /**
     * LA CAZA: sin esto Alba era una torreta que spameaba habilidades sin
     * moverse del sitio. Montada, el corcel no persigue a nadie (la IA de un
     * caballo domado no ataca), asi que se le lleva a mano con velocity +
     * encarar, igual que en las cargas; a pie, a la IA del zombi solo hay que
     * darle el objetivo. Y al alcance pega BASICOS de verdad, con su tajo.
     */
    private void hunt() {
        if (!alive() || busy()) return;
        Player p = nearestTargets(1).stream().findFirst().orElse(null);
        if (p == null) return;
        /* El zombi de combate siempre con objetivo: a pie camina su IA. */
        if (ticks() % 10 == 0 && boss instanceof org.bukkit.entity.Mob m) {
            var actual = m.getTarget();
            if (actual == null || !actual.isValid() || actual.isDead()) m.setTarget(p);
        }
        double dist = body().getLocation().distance(p.getLocation());
        if (mounted() && steed != null && steed.isValid() && ticks() % 4 == 0) {
            Vector dir = p.getLocation().toVector().subtract(steed.getLocation().toVector()).setY(0);
            encarar(dir);
            if (dist > 3.0 && dir.lengthSquared() > 0.04) {
                steed.setVelocity(dir.normalize().multiply(0.42).setY(steed.getVelocity().getY()));
                if (ticks() % 16 == 0) {
                    Compat.sound(world(), steed.getLocation(), "entity.horse.gallop", 0.6f, 1.05f);
                }
            }
        }
        if (dist <= 3.4 && ticks() >= proximoBasico) {
            proximoBasico = ticks() + 24;
            basico(p);
        }
    }

    /** Un golpe basico: tajo con la lanza, sonido de espada y empujon corto. */
    private void basico(Player p) {
        World w = world();
        encarar(p.getLocation().toVector().subtract(body().getLocation().toVector()));
        try {
            if (shell() != null && shell().isValid()) shell().swingMainHand();
        } catch (Throwable ignored) {
        }
        Location q = p.getLocation().add(0, 1, 0);
        Compat.spawn(w, Compat.SWEEP_ATTACK, q, 1, 0, 0, 0, 0);
        Compat.spawn(w, Compat.CRIT, q, 6, 0.25, 0.3, 0.25, 0.06);
        Compat.sound(w, q, "entity.player.attack.sweep", 1.1f, 1.2f);
        Compat.sound(w, q, "block.bell.use", 0.5f, 1.9f);
        hit(p, phase() >= 4 ? 10 : 8);
        push(p, p.getLocation().toVector().subtract(body().getLocation().toVector())
                .setY(0).normalize().multiply(0.35).setY(0.2));
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
    // La formula visual es LA MISMA que el swordfall de RIP, que ya se ve
    // perfecto en produccion: el arma es un ItemDisplay con rotateY(guinada)
    // y rotateZ(-135 grados) --el sprite de una espada apunta a 45 grados, y
    // ese -135 la deja con la PUNTA ABAJO--, y la caida no son teleports sino
    // INTERPOLACION de la traslacion: el cliente la desliza suave.

    private ItemStack goldBlade() {
        return new ItemStack(Material.GOLDEN_SWORD);
    }

    /** Todo el tesoro es de oro por decision del dueño: nada de hierro. */
    private ItemStack silverBlade() {
        return new ItemStack(Material.GOLDEN_SWORD);
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

    /**
     * Un arma quieta con la orientacion pedida. zDeg = -135 es punta abajo,
     * 45 es punta arriba, -45 punta al frente (+X local antes de la guinada).
     */
    private ItemDisplay bladeDisplay(Location at, ItemStack arma, float scale, float yawRad, float zDeg) {
        try {
            ItemDisplay d = world().spawn(at, ItemDisplay.class, e -> {
                e.setItemStack(arma);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                e.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
                e.setShadowRadius(0.0f);
                e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                e.setPersistent(false);
                org.joml.Quaternionf rot = new org.joml.Quaternionf()
                        .rotateY(yawRad)
                        .rotateZ((float) Math.toRadians(zDeg));
                e.setTransformation(new Transformation(new Vector3f(0, 0, 0), rot,
                        new Vector3f(scale, scale, scale), new org.joml.Quaternionf()));
                e.setInterpolationDelay(0);
                e.setInterpolationDuration(0);
            });
            track(d);
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Un arma que CAE del cielo punta abajo y se queda donde se le diga.
     * Identica a la de RIP: nace en el suelo con la traslacion arriba y el
     * cliente interpola la bajada. restY controla cuanto queda clavada.
     */
    private ItemDisplay verticalBlade(Location ground, ItemStack arma, float scale, float yawRad,
                                      double fromY, double restY, int fallTicks, int delayTicks) {
        ItemDisplay d = bladeDisplay(ground, arma, scale, yawRad, -135f);
        if (d == null) return null;
        try {
            Transformation t0 = d.getTransformation();
            d.setTransformation(new Transformation(new Vector3f(0, (float) fromY, 0),
                    t0.getLeftRotation(), t0.getScale(), t0.getRightRotation()));
        } catch (Throwable ignored) {
        }
        later(2 + Math.max(0, delayTicks), () -> {
            if (!d.isValid()) return;
            Transformation t = d.getTransformation();
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(fallTicks);
            d.setTransformation(new Transformation(new Vector3f(0, (float) restY, 0),
                    t.getLeftRotation(), t.getScale(), t.getRightRotation()));
        });
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
            for (int i = 0; i < 12; i++) {
                double a = Math.PI * 2 / 12 * i + t * 0.25;
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
     * Dispara UNA hoja hacia un objetivo. El vuelo tambien es interpolado (el
     * cliente la desliza recta, sin tirones); el daño lo calcula el servidor
     * siguiendo la posicion virtual.
     */
    private void shootBlade(Location from, Location to, ItemStack arma, float scale, double dmg, double speed) {
        final float escala = scale * 1.3f;
        final double dano = dmg + 2;
        World w = world();
        Vector delta = to.toVector().subtract(from.toVector());
        double dist = Math.max(0.01, delta.length());
        Vector dir = delta.clone().multiply(1.0 / dist);
        double horiz = Math.sqrt(dir.getX() * dir.getX() + dir.getZ() * dir.getZ());
        float yawRad = (float) Math.atan2(-dir.getZ(), dir.getX());
        float zDeg = (float) (-45.0 + Math.toDegrees(Math.atan2(dir.getY(), Math.max(0.0001, horiz))));
        ItemDisplay d = bladeDisplay(from.clone(), arma, escala, yawRad, zDeg);
        if (d == null) return;
        int life = (int) Math.ceil(dist / speed) + 1;
        Compat.sound(w, from, "item.trident.throw", 1.0f, 1.5f);
        later(1, () -> {
            if (!d.isValid()) return;
            Transformation t = d.getTransformation();
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(life);
            d.setTransformation(new Transformation(
                    new Vector3f((float) delta.getX(), (float) delta.getY(), (float) delta.getZ()),
                    t.getLeftRotation(), t.getScale(), t.getRightRotation()));
        });
        Vector paso = dir.clone().multiply(dist / life);
        java.util.Set<java.util.UUID> tocados = new java.util.HashSet<>();
        animate(life, t -> {
            Location virtual = from.clone().add(paso.clone().multiply(t + 1));
            Compat.spawn(w, Compat.ELECTRIC_SPARK, virtual, 1, 0.04, 0.04, 0.04, 0.0);
            if (t % 2 == 0) Compat.spawn(w, Compat.END_ROD, virtual, 1, 0.0, 0.0, 0.0, 0.0);
            for (Player p : Fx.playersNear(virtual, 1.4)) {
                if (!tocados.add(p.getUniqueId())) continue;
                hit(p, dano);
                push(p, dir.clone().multiply(0.4).setY(0.15));
                Compat.spawn(w, Compat.CRIT, p.getLocation().add(0, 1, 0), 10, 0.2, 0.3, 0.2, 0.1);
                Compat.sound(w, p.getLocation(), "block.bell.use", 0.8f, 1.9f);
            }
        }, () -> {
            if (d.isValid()) {
                Compat.spawn(w, Compat.CRIT, to, 8, 0.2, 0.2, 0.2, 0.06);
                Compat.sound(w, to, "item.trident.hit_ground", 0.9f, 1.4f);
                Fx.safeRemove(d);
            }
        });
        expire(d, life + 8);
    }

    /** Una hoja que CAE del cielo, se CLAVA, castiga el circulo y se esfuma. */
    private void fallBlade(Location groundSpot, ItemStack arma, float scale, double dmg, double radius, int fallTicks) {
        final float escala = scale * 1.5f;
        final double dano = dmg + 3;
        final double radio = radius * 1.25;
        World w = world();
        Location ground = Fx.ground(groundSpot, 10);
        Fx.ring(ground.clone().add(0, 0.15, 0), Math.max(0.8, radio * 0.7), 14, p ->
                Compat.spawn(w, Compat.GLOW, p, 1, 0.02, 0.02, 0.02, 0.0));
        float yawRad = (float) (random.nextDouble() * Math.PI * 2);
        double restY = Math.max(0.15, 0.42 * escala);
        ItemDisplay d = verticalBlade(ground, arma, escala, yawRad, 13.0, restY, fallTicks, 0);
        if (d == null) return;
        later(fallTicks + 2, () -> {
            Compat.spawn(w, Compat.CRIT, ground.clone().add(0, 0.4, 0), 14, radio * 0.4, 0.2, radio * 0.4, 0.08);
            Compat.spawn(w, Compat.BLOCK, ground.clone().add(0, 0.2, 0), 16, 0.3, 0.1, 0.3, 0.05,
                    Material.GOLD_BLOCK.createBlockData());
            Compat.sound(w, ground, "item.trident.hit_ground", 1.1f, 1.1f);
            Compat.sound(w, ground, "block.bell.use", 0.8f, 1.7f);
            for (Player p : Fx.playersNear(ground, radio)) {
                hit(p, dano);
            }
        });
        later(fallTicks + 14, () -> {
            if (d.isValid()) {
                Compat.spawn(w, Compat.POOF, d.getLocation().add(0, 0.6, 0), 6, 0.15, 0.3, 0.15, 0.01);
                Fx.safeRemove(d);
            }
        });
        expire(d, fallTicks + 20);
    }

    /** Una hoja que cae y QUEDA CLAVADA en el suelo, punta abajo, hasta que se la retire. */
    private ItemDisplay plantada(Location groundSpot, ItemStack arma, float scale) {
        World w = world();
        Location ground = Fx.ground(groundSpot, 10);
        float yawRad = (float) (random.nextDouble() * Math.PI * 2);
        double restY = Math.max(0.2, 0.45 * scale);
        ItemDisplay d = verticalBlade(ground, arma, scale, yawRad, 12.0, restY, 4, 0);
        if (d == null) return null;
        later(6, () -> {
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

    /**
     * ATADURA CELESTIAL: el apresamiento se dibuja SOLO con particulas (decision
     * del dueño: nada de cadenas de BlockDisplay). Cuatro sogas de polvo dorado
     * caen del cielo en diagonal y quedan TENSADAS sobre el jugador, clavandolo
     * al suelo, con su grillete de anillo a los pies. La raiz es la de siempre.
     */
    private void chain(Player p, int ticksHeld, double dmg) {
        if (p == null || chained.contains(p)) return;
        chained.add(p);
        World w = world();
        root(p, ticksHeld);
        Compat.sound(w, p.getLocation(), "block.chain.place", 1.6f, 0.5f);
        Compat.sound(w, p.getLocation(), "block.bell.resonate", 1.2f, 0.6f);
        Compat.sound(w, p.getLocation(), "block.anvil.place", 0.7f, 1.6f);
        if (dmg > 0) hit(p, dmg);
        Location base = p.getLocation().clone();
        /* Los cuatro anclajes altos de las sogas, en diagonal sobre la victima. */
        Location[] anclas = new Location[4];
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 2 * i + Math.PI / 4;
            anclas[i] = base.clone().add(Math.cos(a) * 3.2, 7.5, Math.sin(a) * 3.2);
        }
        var oro = Compat.dust(0xFFD700, 0.9f);
        var oroPalido = Compat.dust(0xFFF3B0, 0.7f);
        later(5, () -> Compat.sound(w, base, "block.chain.fall", 1.2f, 0.6f));
        animate(ticksHeld, t -> {
            Location pecho = base.clone().add(0, 1.0, 0);
            /* Los primeros ticks las sogas BAJAN del cielo; luego quedan tensas. */
            double avance = Math.min(1.0, (t + 1) / 6.0);
            int pasos = 10;
            for (Location ancla : anclas) {
                Vector delta = pecho.toVector().subtract(ancla.toVector());
                int hasta = (int) Math.round(pasos * avance);
                for (int k = 0; k <= hasta; k++) {
                    if ((k + t) % 2 != 0) continue; // eslabones alternos: se ve cadena, no linea
                    Location q = ancla.clone().add(delta.clone().multiply((double) k / pasos));
                    Compat.spawn(w, Compat.DUST, q, 1, 0.02, 0.02, 0.02, 0.0, oro);
                }
            }
            /* El grillete a los pies. */
            if (t % 3 == 0) {
                Fx.ring(base.clone().add(0, 0.15, 0), 0.9, 10, q ->
                        Compat.spawn(w, Compat.DUST, q, 1, 0.02, 0.02, 0.02, 0.0, oroPalido));
            }
            if (t % 12 == 0) Compat.sound(w, base, "block.chain.step", 0.9f, 0.55f);
        }, () -> {
            chained.remove(p);
            Compat.spawn(w, Compat.WAX_OFF, base.clone().add(0, 1, 0), 14, 0.5, 0.8, 0.5, 0.04);
            Compat.sound(w, base, "block.chain.break", 1.1f, 0.7f);
        });
    }


    /**
     * Encara TODO lo visible hacia una direccion. El face() de siempre no sirve
     * montada: rota al zombi invisible, y lo que se ve es la pareja corcel+maniqui,
     * que llevan su propia guiñada (era el fallo de las embestidas, el mismo que
     * el del Mimic). setRotation NO desmonta al pasajero, un teleport si.
     */
    private void encarar(Vector dir) {
        if (dir == null || dir.lengthSquared() < 1.0E-4) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
        try {
            if (steed != null && steed.isValid()) steed.setRotation(yaw, 0f);
            var cuerpo = shell();
            if (cuerpo != null && cuerpo.isValid()) cuerpo.setRotation(yaw, 0f);
            if (boss != null && boss.isValid()) boss.setRotation(yaw, 0f);
        } catch (Throwable ignored) {
        }
    }

    /** El punto del que salen las armas: la puerta mas cercana al hombro de la reina. */
    private Location shoulder() {
        Location l = body().getLocation().add(0, 2.1, 0);
        Vector side = l.getDirection().setY(0).normalize().rotateAroundY(Math.PI / 2).multiply(0.9);
        return l.add(side);
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
            encarar(dir);
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
        Player objetivo = nearestTargets(1).stream().findFirst().orElse(null);
        if (objetivo == null) return;
        Location g = shoulder();
        gate(g, 20);
        Compat.sound(w, g, "block.respawn_anchor.charge", 1.0f, 1.6f);
        /* El abanico se centra en un jugador REAL. Antes salia hacia donde
         * "miraba" el mob de combate, que con la IA mira a cualquier parte:
         * era la espada dorada disparada al vacio. */
        Vector base = objetivo.getEyeLocation().toVector().subtract(g.toVector()).setY(0);
        if (base.lengthSquared() < 0.01) base = new Vector(1, 0, 0);
        base.normalize();
        encarar(base);
        for (int i = 0; i < 8; i++) {
            double ang = -52.5 + i * 15;
            Vector dir = base.clone().rotateAroundY(Math.toRadians(ang));
            Location to = g.clone().add(dir.multiply(16)).subtract(0, 1.2, 0);
            ItemStack arma = goldBlade();
            later(6 + i, () -> shootBlade(g, to, arma, 1.0f, 5, 1.6));
        }
    }

    /** 12. Espejismo Platino: hojas plateadas orbitan a la dama y salen despedidas. */
    public void espejismoPlatino() {
        World w = world();
        List<ItemDisplay> orbit = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ItemDisplay d = bladeDisplay(body().getLocation().add(0, 1.6, 0), goldBlade(), 1.0f, (float) (Math.PI * 2 / 5 * i), 45f);
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
                if (p != null) shootBlade(from, p.getEyeLocation(), goldBlade(), 1.0f, 6, 1.7);
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
            ItemDisplay d = plantada(spot, goldBlade(), 1.6f);
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
            fallBlade(spot, goldBlade(), 1.3f, 5, 1.6, 9);
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
                    encarar(dir);
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

    /** El adios de una hoja: fogonazo y chispas. Copiado del flashAway de RIP. */
    private void hojaFugaz(ItemDisplay d) {
        if (d == null) return;
        World w = world();
        Location at = null;
        float scale = 1.0f;
        try {
            if (d.isValid()) {
                Transformation tr = d.getTransformation();
                at = d.getLocation().clone().add(0, tr.getTranslation().y, 0);
                scale = tr.getScale().x;
            }
        } catch (Throwable ignored) {
        }
        if (at != null) {
            boolean big = scale > 3.0f;
            Compat.spawn(w, Compat.FLASH, at, 1);
            Compat.spawn(w, Compat.END_ROD, at, big ? 40 : 14, big ? 0.5 : 0.18, big ? 0.7 : 0.3, big ? 0.5 : 0.18, big ? 0.12 : 0.05);
            Compat.spawn(w, Compat.ELECTRIC_SPARK, at, big ? 26 : 9, big ? 0.4 : 0.2, big ? 0.6 : 0.3, big ? 0.4 : 0.2, big ? 0.15 : 0.08);
            Compat.sound(w, at, "block.amethyst_block.chime", big ? 1.2f : 0.6f,
                    big ? 0.8f : 1.3f + (float) random.nextDouble() * 0.5f);
            if (big) Compat.sound(w, at, "entity.illusioner.mirror_move", 1.0f, 0.7f);
        }
        Fx.safeRemove(d);
    }

    /**
     * 3b. Juicio de Hojas: el swordfall de RIP COPIADO TICK A TICK (kSwordfall
     * de EffectRunner), que es la version completa: 12 hojas cayendo una a una
     * en anillo, trueno y fogonazo en lo alto, la GIGANTE bajando con su estela
     * de nubes, el coro del impacto con la onda expansiva creciendo, y las
     * hojas retirandose en destellos una por una. Solo cambia el sabor (oro en
     * vez de piedra) y que aqui las hojas HACEN daño de jefe.
     */
    public void juicioDeHojas() {
        Player objetivo = randomTarget();
        if (objetivo == null) return;
        World w = world();
        Location base = Fx.ground(objetivo.getLocation().clone(), 8);
        Location c = base.clone().add(0, 1, 0);
        warn(Component.text("El cielo dicta sobre " + objetivo.getName() + ".", ORO, TextDecoration.BOLD));
        busyFor(120);
        Fx.ring(base.clone().add(0, 0.2, 0), 2.2, 14, p ->
                Compat.spawn(w, Compat.ENCHANT, p, 2, 0.05, 0.3, 0.05, 0.4));
        Compat.sound(w, base, "block.respawn_anchor.charge", 1.0f, 0.6f);
        Compat.sound(w, base, "entity.elder_guardian.curse", 0.5f, 1.4f);
        List<ItemDisplay> hojas = new ArrayList<>();
        java.util.Set<java.util.UUID> barridos = new java.util.HashSet<>();
        animate(140, t -> {
            /* t=4..26: las doce hojas del anillo, una cada dos ticks. */
            if (t >= 4 && t <= 26 && (t - 4) % 2 == 0) {
                int i = (t - 4) / 2;
                double a = Math.PI * 2 / 12 * i;
                Location ground = Fx.ground(base.clone().add(Math.cos(a) * 2.2, 1, Math.sin(a) * 2.2), 6);
                ItemDisplay hoja = verticalBlade(ground, goldBlade(), 1.6f, (float) -a, 13.0, 0.95, 4, 0);
                if (hoja != null) {
                    hojas.add(hoja);
                    expire(hoja, 150);
                    later(6, () -> {
                        Compat.spawn(w, Compat.BLOCK, ground.clone().add(0, 0.2, 0), 12, 0.15, 0.1, 0.15, 0.05,
                                Material.GOLD_BLOCK.createBlockData());
                        Compat.spawn(w, Compat.CRIT, ground.clone().add(0, 0.5, 0), 6, 0.1, 0.2, 0.1, 0.1);
                        Compat.sound(w, ground, "entity.player.attack.sweep", 1.0f, 0.7f + (float) random.nextDouble() * 0.6f);
                        Compat.sound(w, ground, "item.trident.hit_ground", 0.8f, 0.8f + (float) random.nextDouble() * 0.4f);
                        Compat.sound(w, ground, "block.bell.use", 0.45f, 1.4f + (float) random.nextDouble() * 0.5f);
                        for (Player v : Fx.playersNear(ground, 1.5)) hit(v, 6);
                    });
                }
            }
            /* La carga sobre el centro mientras el anillo se cierra. */
            if (t < 40 && t % 4 == 0) {
                Compat.spawn(w, Compat.ENCHANT, c.clone().add(0, 0.4, 0), 4, 0.35, 0.6, 0.35, 0.3);
            }
            /* t=36: el cielo avisa. */
            if (t == 36) {
                Compat.sound(w, c, "entity.lightning_bolt.thunder", 0.6f, 0.5f);
                Compat.spawn(w, Compat.FLASH, c.clone().add(0, 12, 0), 1);
            }
            /* t=40: la GIGANTE baja con su estela de nubes. */
            if (t == 40) {
                ItemDisplay gigante = verticalBlade(base.clone(), goldBlade(), 6.0f, 0.6f, 24.0, 2.6, 8, 0);
                if (gigante != null) {
                    hojas.add(gigante);
                    expire(gigante, 150);
                }
                animate(8, tick -> {
                    double y = 24.0 - tick * (21.4 / 8.0);
                    Compat.spawn(w, Compat.CLOUD, base.clone().add(0, y, 0), 3, 0.3, 0.5, 0.3, 0.01);
                    Compat.spawn(w, Compat.CRIT, base.clone().add(0, y, 0), 4, 0.2, 0.4, 0.2, 0.05);
                }, null);
            }
            /* t=50: el impacto, con el coro entero del original. */
            if (t == 50) {
                Compat.spawn(w, Compat.EXPLOSION_EMITTER, base, 1);
                Compat.spawn(w, Compat.FLASH, c, 1);
                Compat.spawn(w, Compat.BLOCK, base.clone().add(0, 0.3, 0), 60, 1.2, 0.3, 1.2, 0.1,
                        Material.GOLD_BLOCK.createBlockData());
                Compat.sound(w, base, "block.anvil.land", 1.0f, 0.5f);
                Compat.sound(w, base, "block.anvil.use", 1.0f, 0.4f);
                Compat.sound(w, base, "entity.lightning_bolt.impact", 1.0f, 0.7f);
                Compat.sound(w, base, "item.mace.smash_ground_heavy", 1.0f, 0.6f);
                Compat.sound(w, base, "block.bell.use", 1.5f, 0.55f);
                later(6, () -> Compat.sound(w, base, "block.bell.resonate", 1.2f, 0.75f));
                later(16, () -> Compat.sound(w, base, "block.bell.resonate", 0.8f, 0.6f));
                for (Player v : Fx.playersNear(base, 3.5)) {
                    hit(v, 12);
                    lift(v, new Vector(0, 0.6, 0));
                }
            }
            /* t=50..64: la onda expansiva de nubes, que aqui ademas barre. */
            if (t > 50 && t <= 64) {
                double r = (t - 50) * 0.45;
                Fx.ring(base.clone().add(0, 0.15, 0), r, (int) (10 + r * 6), p -> {
                    Compat.spawn(w, Compat.CLOUD, p, 1);
                    if (t % 2 == 0) Compat.spawn(w, Compat.CRIT, p, 1, 0.03, 0.05, 0.03, 0.02);
                });
                for (Player v : Fx.playersNear(base, r + 0.9)) {
                    if (Math.abs(v.getLocation().distance(base) - r) < 0.9 && barridos.add(v.getUniqueId())) {
                        hit(v, 5);
                        push(v, v.getLocation().toVector().subtract(base.toVector()).setY(0).normalize()
                                .multiply(0.5).setY(0.3));
                    }
                }
            }
            /* t=96: el tesoro se retira, un destello por hoja. */
            if (t == 96) {
                List<ItemDisplay> pendientes = new ArrayList<>(hojas);
                hojas.clear();
                for (int i = 0; i < pendientes.size(); i++) {
                    ItemDisplay hoja = pendientes.get(i);
                    later(i * 2, () -> hojaFugaz(hoja));
                }
            }
        }, () -> {
            for (ItemDisplay hoja : hojas) {
                if (hoja != null && hoja.isValid()) Fx.safeRemove(hoja);
            }
            hojas.clear();
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
            ItemDisplay pl = plantada(donde, goldBlade(), 2.0f);
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
                            goldBlade(), 1.6f, 6, 1.8, 6);
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

    /**
     * Una lanza HECHA DE LUZ para la lluvia masiva: tres trazos de END_ROD
     * cayendo en vertical (particulas dirigidas, sin entidades) y el impacto
     * con su corona de chispas. A cientos por oleada, las particulas se ven
     * limpias donde los displays se veian regados.
     */
    private void lanzaFantasma(Location spot) {
        World w = world();
        Location ground = Fx.ground(spot, 8);
        for (int k = 0; k < 3; k++) {
            Location top = ground.clone().add((random.nextDouble() - 0.5) * 0.5,
                    9 + k * 1.6, (random.nextDouble() - 0.5) * 0.5);
            Compat.spawn(w, Compat.END_ROD, top, 0, 0, -1, 0, 1.1 + k * 0.2);
        }
        later(4, () -> {
            Compat.spawn(w, Compat.ELECTRIC_SPARK, ground.clone().add(0, 0.3, 0), 6, 0.25, 0.15, 0.25, 0.05);
            Compat.spawn(w, Compat.CRIT, ground.clone().add(0, 0.4, 0), 8, 0.3, 0.3, 0.3, 0.08);
            Compat.spawn(w, Compat.GLOW, ground.clone().add(0, 0.2, 0), 3, 0.2, 0.1, 0.2, 0.0);
            if (random.nextInt(4) == 0) {
                Compat.sound(w, ground, "item.trident.hit_ground", 0.55f, 1.2f + (float) random.nextDouble() * 0.4f);
            }
            for (Player p : Fx.playersNear(ground, 1.8)) {
                hit(p, 11);
            }
        });
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
                lanzaFantasma(spot);
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
            shootBlade(from, p.getEyeLocation(), goldBlade(), 1.1f, 5, 1.7);
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
        ItemDisplay giant = verticalBlade(Fx.ground(c.clone(), 8), goldBlade(), 7.0f, 0f, 16.0, 1.6, 7, 30);
        if (giant == null) return;
        Compat.sound(w, c, "block.respawn_anchor.charge", 1.3f, 0.6f);
        Compat.sound(w, c, "block.bell.resonate", 1.5f, 0.5f);
        Location top = c.clone().add(0, 16, 0);
        animate(30, t -> {
            if (!giant.isValid()) return;
            double a = t * 0.5;
            for (int i = 0; i < 2; i++) {
                Location q = top.clone().add(Math.cos(a + i * Math.PI) * 2.5, 0, Math.sin(a + i * Math.PI) * 2.5);
                Compat.spawn(w, Compat.END_ROD, q, 1, 0.05, 0.05, 0.05, 0.0);
            }
            if (t % 6 == 0) Compat.sound(w, c, "block.amethyst_block.chime", 1.2f, 0.7f + t * 0.03f);
        }, () -> {
            Compat.sound(w, c, "entity.lightning_bolt.thunder", 1.0f, 0.8f);
            animate(7, t -> Compat.spawn(w, Compat.CLOUD, c.clone().add(0, 14 - t * 2, 0), 4, 0.5, 0.6, 0.5, 0.02), () -> {
                later(10, () -> { if (giant.isValid()) Fx.safeRemove(giant); });
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
                fallBlade(ground.clone().add(Math.cos(a) * 1.2, 0, Math.sin(a) * 1.2), goldBlade(), 1.6f, 7, 2.0, 6);
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

    /**
     * 33. RAYOS DEL ALBA: columnas de luz verticales tipo beacon. Cada rayo son
     * dos BlockDisplay anidados (cristal amarillo por fuera, nucleo de glowstone
     * por dentro) estirados ~320 bloques, con la BASE ~25 bloques BAJO el suelo:
     * el rayo emerge del subsuelo y se pierde en el cielo, nunca "nace de la
     * nada". Ocho rayos se desprenden de Alba y se ABREN en espiral hacia fuera,
     * castigando a quien pillan en el camino. El deslizamiento va por
     * setTeleportDuration, no por saltos de teleport secos.
     */
    public void rayosDelAlba() {
        World w = world();
        Location c = body().getLocation().clone();
        double baseY = Math.max(w.getMinHeight() + 1, c.getY() - 25);
        warn(Component.text("«QUE LA LUZ LOS ATRAVIESE.»", ORO, TextDecoration.BOLD));
        Compat.sound(w, c, "block.beacon.activate", 1.6f, 0.7f);
        Compat.sound(w, c, "block.respawn_anchor.charge", 1.2f, 0.5f);
        busyFor(100);
        final int rayos = 8;
        List<org.bukkit.entity.BlockDisplay[]> haces = new ArrayList<>();
        for (int i = 0; i < rayos; i++) {
            double a = Math.PI * 2 / rayos * i;
            Location at = new Location(w, c.getX() + Math.cos(a) * 0.8, baseY, c.getZ() + Math.sin(a) * 0.8);
            /* El nucleo es FROGLIGHT OCRE, el bloque mas claro y amarillo que
             * existe, bien gordo; la funda de cristal solo lo tiñe. Con el
             * glowstone fino de la primera version el rayo se veia OSCURO. */
            org.bukkit.entity.BlockDisplay funda = haz(at, Material.YELLOW_STAINED_GLASS, 0.62f);
            org.bukkit.entity.BlockDisplay nucleo = haz(at, pickMat("OCHRE_FROGLIGHT", "GLOWSTONE"), 0.4f);
            if (funda != null || nucleo != null) {
                haces.add(new org.bukkit.entity.BlockDisplay[]{funda, nucleo});
            }
        }
        java.util.Map<java.util.UUID, Integer> ultimoGolpe = new java.util.HashMap<>();
        animate(90, t -> {
            double r = Math.min(16.0, 0.8 + t * 0.18);
            double giro = t * 0.022;
            if (t % 15 == 0) Compat.sound(w, c, "block.beacon.ambient", 1.8f, 1.2f);
            if (t % 30 == 0) Compat.sound(w, c, "block.amethyst_block.resonate", 1.2f, 1.6f);
            for (int i = 0; i < haces.size(); i++) {
                double a = Math.PI * 2 / rayos * i + giro;
                double x = c.getX() + Math.cos(a) * r;
                double z = c.getZ() + Math.sin(a) * r;
                Location at = new Location(w, x, baseY, z);
                for (org.bukkit.entity.BlockDisplay bd : haces.get(i)) {
                    if (bd != null && bd.isValid()) bd.teleport(at);
                }
                /* Chispas donde el rayo corta el suelo, para leer el barrido. */
                if (t % 2 == 0) {
                    Location pie = Fx.ground(new Location(w, x, c.getY() + 1, z), 12);
                    Compat.spawn(w, Compat.END_ROD, pie.clone().add(0, 0.5, 0), 2, 0.1, 0.6, 0.1, 0.02);
                    Compat.spawn(w, Compat.GLOW, pie.clone().add(0, 0.2, 0), 1, 0.06, 0.1, 0.06, 0.0);
                }
                /* El rayo es una columna infinita: el toque se mide en el plano. */
                for (Player p : targets(40)) {
                    double dx = p.getLocation().getX() - x;
                    double dz = p.getLocation().getZ() - z;
                    if (dx * dx + dz * dz > 1.6) continue;
                    int last = ultimoGolpe.getOrDefault(p.getUniqueId(), -100);
                    if (t - last < 10) continue;
                    ultimoGolpe.put(p.getUniqueId(), t);
                    hit(p, 9);
                    push(p, new Vector(Math.cos(a), 0, Math.sin(a)).multiply(0.6).setY(0.35));
                    Compat.spawn(w, Compat.FLASH, p.getLocation(), 1);
                    Compat.spawn(w, Compat.ELECTRIC_SPARK, p.getLocation().add(0, 1, 0), 12, 0.3, 0.6, 0.3, 0.1);
                    Compat.sound(w, p.getLocation(), "entity.lightning_bolt.impact", 0.9f, 1.6f);
                }
            }
        }, () -> {
            Compat.sound(w, c, "block.beacon.deactivate", 1.4f, 0.8f);
            for (org.bukkit.entity.BlockDisplay[] par : haces) {
                for (org.bukkit.entity.BlockDisplay bd : par) {
                    if (bd == null || !bd.isValid()) continue;
                    Location pie = Fx.ground(new Location(w, bd.getLocation().getX(), c.getY() + 1,
                            bd.getLocation().getZ()), 12);
                    Compat.spawn(w, Compat.FLASH, pie, 1);
                    Compat.spawn(w, Compat.END_ROD, pie.clone().add(0, 1, 0), 8, 0.15, 1.2, 0.15, 0.03);
                    Fx.safeRemove(bd);
                }
            }
        });
        haces.forEach(par -> {
            for (org.bukkit.entity.BlockDisplay bd : par) if (bd != null) expire(bd, 110);
        });
    }

    /** Una columna de luz: un BlockDisplay estirado a 320 de alto desde su base. */
    private org.bukkit.entity.BlockDisplay haz(Location at, Material mat, float grosor) {
        try {
            org.bukkit.entity.BlockDisplay bd = world().spawn(at, org.bukkit.entity.BlockDisplay.class, e -> {
                e.setBlock(mat.createBlockData());
                e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                e.setShadowRadius(0.0f);
                e.setPersistent(false);
                e.setViewRange(4.0f);
                /* El bloque local [0..1] escalado crece hacia ARRIBA de la entidad. */
                e.setTransformation(new Transformation(new Vector3f(-grosor / 2f, 0f, -grosor / 2f),
                        new org.joml.Quaternionf(), new Vector3f(grosor, 320f, grosor), new org.joml.Quaternionf()));
                try {
                    e.setTeleportDuration(2);
                } catch (Throwable ignored2) {
                }
            });
            track(bd);
            return bd;
        } catch (Throwable t) {
            return null;
        }
    }
}
