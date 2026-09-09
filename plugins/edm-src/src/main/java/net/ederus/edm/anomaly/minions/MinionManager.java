package net.ederus.edm.anomaly.minions;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.core.Compat;
import net.ederus.edm.anomaly.drops.DropEntry;
import net.ederus.edm.anomaly.drops.DropTable;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * El motor de los esbirros: recorre los generadores una vez por segundo y va
 * reponiendo tropa donde toque. Un generador solo trabaja si su chunk esta
 * cargado, hay un jugador dentro de su radio de activacion y no ha llegado ya a
 * su tope de vivos: una mazmorra vacia no acumula bichos.
 *
 * Cada esbirro lleva su holograma encima (nombre, nivel y vida). El holograma NO
 * va montado como pasajero: un mob con pasajero pierde media IA de combate (lo
 * mismo que le pasaba a Herbola con el loro en la cabeza), y para tropa de
 * mazmorra la IA es justo lo que hace falta. Va suelto y se le teleporta tick a
 * tick sobre la cabeza, con interpolacion de un tick para que no de tirones.
 *
 * Los esbirros NO se guardan en disco (setPersistent false): si el chunk se
 * descarga o el servidor se reinicia, desaparecen y el generador los repone; asi
 * nunca quedan huerfanos sin holograma ni sin nivel.
 */
public final class MinionManager implements Listener {

    private final AnomalyPlugin plugin;
    private final Random random = new Random();

    /* Marcas en la entidad, para reconocer a los nuestros tras cualquier cosa. */
    private final NamespacedKey keyType;
    private final NamespacedKey keySpawner;
    private final NamespacedKey keyLevel;
    private final NamespacedKey keyHolo;
    /** La marca que lleva la tercera flecha, la que pega el doble. */
    private final NamespacedKey keyHeavy;
    /** Una cria de Division: no vuelve a dividirse, o la sala no acaba nunca. */
    private final NamespacedKey keyChild;

    /** Quien esta vivo de cada generador. Se purga en el ticker. */
    private final Map<String, Set<UUID>> alive = new HashMap<>();

    /** Un esbirro vivo y el cartel que le sigue. La lista es corta por definicion. */
    private static final class Escolta {
        final LivingEntity mob;
        final TextDisplay holo;
        int lastHealth = -1;

        Escolta(LivingEntity mob, TextDisplay holo) {
            this.mob = mob;
            this.holo = holo;
        }
    }

    private final List<Escolta> escoltas = new ArrayList<>();

    /** Cuantas flechas lleva disparadas cada arquero, para la tercera pesada. */
    private final Map<UUID, Integer> arrowCount = new HashMap<>();

    private BukkitTask ticker;
    private BukkitTask holoTicker;

    public MinionManager(AnomalyPlugin plugin) {
        this.plugin = plugin;
        this.keyType = new NamespacedKey(plugin, "esbirro_tipo");
        this.keySpawner = new NamespacedKey(plugin, "esbirro_generador");
        this.keyLevel = new NamespacedKey(plugin, "esbirro_nivel");
        this.keyHolo = new NamespacedKey(plugin, "esbirro_holo");
        this.keyHeavy = new NamespacedKey(plugin, "esbirro_flecha_pesada");
        this.keyChild = new NamespacedKey(plugin, "esbirro_cria");
    }

    // ---------------------------------------------------------------------- ciclo

    public void start() {
        stop();
        ticker = plugin.getServer().getScheduler().runTaskTimer(
                net.ederus.edm.Module.dueno(plugin), this::tick, 40L, 20L);
        // Los carteles van a parte y cada tick, que es lo que los hace ir pegados
        // a la cabeza sin montarse encima del bicho.
        holoTicker = plugin.getServer().getScheduler().runTaskTimer(
                net.ederus.edm.Module.dueno(plugin), this::tickHolos, 40L, 1L);
    }

    public void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        if (holoTicker != null) {
            holoTicker.cancel();
            holoTicker = null;
        }
    }

    /**
     * Los carteles siguen a su esbirro. Si el bicho murio o se esfumo (chunk
     * descargado, /kill), el cartel se va con el: nunca queda un nombre flotando.
     */
    private void tickHolos() {
        for (Iterator<Escolta> it = escoltas.iterator(); it.hasNext(); ) {
            Escolta e = it.next();
            if (!e.mob.isValid() || e.mob.isDead()) {
                e.holo.remove();
                it.remove();
                continue;
            }
            if (!e.holo.isValid()) {
                it.remove();
                continue;
            }
            e.holo.teleport(e.mob.getLocation().add(0, e.mob.getHeight() + 0.45, 0));
            int hp = (int) Math.ceil(e.mob.getHealth());
            if (hp != e.lastHealth) {
                e.lastHealth = hp;
                updateHolo(e.holo, e.mob);
            }
        }
    }

    /** Apagado o recarga: fuera todos los esbirros vivos y sus hologramas. */
    public void removeAll() {
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (isMinion(e) || isHolo(e)) e.remove();
            }
        }
        alive.clear();
        escoltas.clear();
    }

    /**
     * Barrido de arranque: igual que el de los jefes, pero con las marcas de los
     * esbirros. Un reinicio en caliente no debe dejar tropa vieja sin holograma.
     */
    public int sweep() {
        int removed = 0;
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (isMinion(e) || isHolo(e)) {
                    e.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Cuantas vueltas lleva el reloj: los rasgos lentos van cada X segundos. */
    private int pulso;

    private void tick() {
        long now = System.currentTimeMillis();
        if (++pulso % 3 == 0) healers();
        for (MinionSpawner s : plugin.minions().spawners()) {
            Set<UUID> mine = alive.computeIfAbsent(s.id(), k -> new HashSet<>());

            // Purga: muertos, despawneados o en chunks descargados ya no cuentan.
            for (Iterator<UUID> it = mine.iterator(); it.hasNext(); ) {
                Entity e = Bukkit.getEntity(it.next());
                if (e == null || e.isDead()) it.remove();
            }

            if (!s.enabled()) continue;
            MinionType type = plugin.minions().type(s.typeId());
            if (type == null) continue;
            if (mine.size() >= s.maxAlive()) continue;
            if (now < s.nextSpawnAt()) continue;

            Location spot = s.spot();
            if (spot == null || spot.getWorld() == null) continue;
            if (!spot.getWorld().isChunkLoaded(spot.getBlockX() >> 4, spot.getBlockZ() >> 4)) continue;

            // Sin publico no hay funcion: el generador espera a que alguien entre.
            boolean someoneNear = false;
            for (Player p : spot.getWorld().getPlayers()) {
                if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
                if (p.getLocation().distanceSquared(spot) <= (double) s.activationRadius() * s.activationRadius()) {
                    someoneNear = true;
                    break;
                }
            }
            if (!someoneNear) continue;

            spawn(type, s, spot);
            s.nextSpawnAt(now + s.intervalSeconds() * 1000L);
        }

        // Red de seguridad: un cartel que se quedo sin escolta (por ejemplo, si el
        // chunk se recargo con el display dentro) no puede quedarse flotando.
        Set<UUID> escoltados = new HashSet<>();
        for (Escolta e : escoltas) escoltados.add(e.holo.getUniqueId());
        for (World w : plugin.getServer().getWorlds()) {
            for (TextDisplay d : w.getEntitiesByClass(TextDisplay.class)) {
                if (isHolo(d) && !escoltados.contains(d.getUniqueId())) d.remove();
            }
        }
    }

    /**
     * Los curanderos: cada 3 segundos reponen algo de vida a la tropa de alrededor
     * (a los suyos, no a si mismos, para que no sean inmortales de uno en uno).
     */
    private void healers() {
        for (Escolta e : escoltas) {
            LivingEntity medico = e.mob;
            if (!medico.isValid() || medico.isDead()) continue;
            MinionType type = typeOf(medico);
            if (type == null || !type.has(MinionAbility.CURANDERO)) continue;

            boolean curoAlguno = false;
            for (Escolta otro : escoltas) {
                LivingEntity herido = otro.mob;
                if (herido == medico || !herido.isValid() || herido.isDead()) continue;
                if (!herido.getWorld().equals(medico.getWorld())) continue;
                if (herido.getLocation().distanceSquared(medico.getLocation()) > 64) continue;
                double max = Compat.getAttribute(herido, "max_health", herido.getHealth());
                if (herido.getHealth() >= max) continue;
                herido.setHealth(Math.min(max, herido.getHealth() + max * 0.04));
                curoAlguno = true;
                Compat.spawn(herido.getWorld(), Compat.HEART,
                        herido.getLocation().add(0, herido.getHeight(), 0), 3, 0.3, 0.2, 0.3, 0.01);
            }
            if (curoAlguno) {
                Compat.spawn(medico.getWorld(), Compat.SPORE_BLOSSOM_AIR,
                        medico.getLocation().add(0, 1, 0), 10, 0.6, 0.6, 0.6, 0.01);
                Compat.sound(medico.getWorld(), medico.getLocation(), "block.amethyst_block.chime", 0.5f, 1.7f);
            }
        }
    }

    // ---------------------------------------------------------------------- spawn

    private void spawn(MinionType type, MinionSpawner spawner, Location spot) {
        int level = spawner.minLevel() + (spawner.maxLevel() > spawner.minLevel()
                ? random.nextInt(spawner.maxLevel() - spawner.minLevel() + 1) : 0);
        spawnAt(type, level, spot, spawner.id());
    }

    /**
     * Genera uno al momento, sin esperar el reloj. Respeta el tope de vivos.
     * Lo usa el boton "Generar ahora" de la ficha del generador.
     */
    public boolean forceSpawn(MinionSpawner spawner) {
        MinionType type = plugin.minions().type(spawner.typeId());
        Location spot = spawner.spot();
        if (type == null || spot == null || spot.getWorld() == null) return false;
        if (!spot.getWorld().isChunkLoaded(spot.getBlockX() >> 4, spot.getBlockZ() >> 4)) return false;
        if (aliveOf(spawner.id()) >= spawner.maxAlive()) return false;
        spawn(type, spawner, spot);
        return true;
    }

    /**
     * El spawn de verdad. spawnerId null = invocacion suelta (la prueba del menu):
     * el bicho es identico pero no cuenta para el tope de ningun generador.
     */
    public LivingEntity spawnAt(MinionType type, int level, Location spot, String spawnerId) {
        World w = spot.getWorld();
        if (w == null) return null;

        Entity raw = w.spawnEntity(spot, type.entity());
        if (!(raw instanceof LivingEntity mob)) {
            raw.remove();
            return null;
        }

        mob.getPersistentDataContainer().set(keyType, PersistentDataType.STRING, type.id());
        if (spawnerId != null) {
            mob.getPersistentDataContainer().set(keySpawner, PersistentDataType.STRING, spawnerId);
        }
        mob.getPersistentDataContainer().set(keyLevel, PersistentDataType.INTEGER, level);
        mob.setPersistent(false);
        mob.setRemoveWhenFarAway(false);
        if (mob instanceof Mob m) m.setAware(true);

        // Agil: se toca el atributo, no un efecto de pocion, para no llenarle la
        // pantalla al jugador de particulas de velocidad.
        if (type.has(MinionAbility.AGIL)) {
            double base = Compat.getAttribute(mob, "movement_speed", 0.23);
            Compat.setAttribute(mob, "movement_speed", base * 1.25);
        }

        double health = type.healthAt(level);
        Compat.setAttribute(mob, "max_health", health);
        mob.setHealth(Math.min(health, Compat.getAttribute(mob, "max_health", health)));

        // El cartel va SUELTO y se le teleporta encima cada tick (ver tickHolos):
        // montarlo como pasajero le comeria la IA al bicho.
        TextDisplay holo = w.spawn(mob.getLocation().add(0, mob.getHeight() + 0.45, 0), TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setViewRange(0.6f);
            d.setSeeThrough(false);
            d.setPersistent(false);
            d.setDefaultBackground(false);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(70, 0, 0, 0));
            d.setBrightness(new Display.Brightness(15, 15));
            // Un tick de interpolacion: el cartel sigue al bicho sin dar tirones.
            d.setTeleportDuration(1);
            d.getPersistentDataContainer().set(keyHolo, PersistentDataType.STRING, type.id());
        });
        updateHolo(holo, mob);
        escoltas.add(new Escolta(mob, holo));

        if (spawnerId != null) {
            alive.computeIfAbsent(spawnerId, k -> new HashSet<>()).add(mob.getUniqueId());
        }

        Compat.spawn(w, Compat.POOF, spot.clone().add(0, 0.4, 0), 8, 0.25, 0.3, 0.25, 0.01);
        Compat.sound(w, spot, "block.respawn_anchor.deplete", 0.4f, 1.6f);
        return mob;
    }

    // ------------------------------------------------------------------ holograma

    /* Los colores del cartel, aparte para que las dos lineas rimen. */
    private static final net.kyori.adventure.text.format.TextColor HOLO_LABEL =
            net.kyori.adventure.text.format.TextColor.color(0x9A9A9A);
    private static final net.kyori.adventure.text.format.TextColor HOLO_LEVEL =
            net.kyori.adventure.text.format.TextColor.color(0xFFD966);
    private static final net.kyori.adventure.text.format.TextColor HOLO_FULL =
            net.kyori.adventure.text.format.TextColor.color(0xE8E8E8);
    private static final net.kyori.adventure.text.format.TextColor HOLO_HURT =
            net.kyori.adventure.text.format.TextColor.color(0xFFB347);
    private static final net.kyori.adventure.text.format.TextColor HOLO_LOW =
            net.kyori.adventure.text.format.TextColor.color(0xFF6B6B);

    /**
     * El cartel: dos lineas cortas y nada mas. Arriba el nombre en su color (en
     * redonda, salvo que el tipo pida negrita) con su nivel en pequeno detras;
     * abajo SOLO la vida que le queda —nada de "250 / 250", que es el doble de
     * texto para la mitad de informacion—, y el numero se va tinendo segun baja.
     */
    private void updateHolo(TextDisplay holo, LivingEntity mob) {
        MinionType type = typeOf(mob);
        if (type == null) return;
        int level = levelOf(mob);
        int hp = (int) Math.ceil(mob.getHealth());
        double max = Compat.getAttribute(mob, "max_health", Math.max(1, hp));
        double left = max <= 0 ? 1 : Math.max(0, Math.min(1, mob.getHealth() / max));

        holo.text(type.name()
                .append(Component.text("  Nv. ", HOLO_LABEL))
                .append(Component.text(level, HOLO_LEVEL))
                .append(Component.newline())
                .append(Component.text("❤ ", NamedTextColor.RED))
                .append(Component.text(hp, left > 0.6 ? HOLO_FULL : left > 0.3 ? HOLO_HURT : HOLO_LOW)));
    }

    /** Repinta ya los carteles de un tipo: lo usa el menu al cambiar su aspecto. */
    public void refreshHolos(String typeId) {
        for (Escolta e : escoltas) {
            if (!e.mob.isValid() || !e.holo.isValid()) continue;
            MinionType type = typeOf(e.mob);
            if (type != null && (typeId == null || type.id().equals(typeId))) updateHolo(e.holo, e.mob);
        }
    }

    // -------------------------------------------------------------------- eventos

    /** El dano del esbirro escala con su nivel, venga de donde venga el golpe. */
    @EventHandler(ignoreCancelled = true)
    public void onDeal(EntityDamageByEntityEvent e) {
        LivingEntity minion = minionBehind(e.getDamager());
        if (minion == null) return;
        MinionType type = typeOf(minion);
        if (type == null) return;
        e.setDamage(e.getDamage() * type.damageAt(levelOf(minion)));

        // Berserk: acorralado pega mas fuerte.
        if (type.has(MinionAbility.BERSERK)) {
            double max = Compat.getAttribute(minion, "max_health", minion.getHealth());
            if (max > 0 && minion.getHealth() / max < 0.30) e.setDamage(e.getDamage() * 1.5);
        }

        // Venenoso e Igneo castigan el contacto, venga de garra o de flecha.
        if (e.getEntity() instanceof LivingEntity tocado) {
            if (type.has(MinionAbility.VENENOSO)) {
                var poison = Compat.effect("poison");
                if (poison != null) tocado.addPotionEffect(new PotionEffect(poison, 80, 0, true, true));
            }
            if (type.has(MinionAbility.IGNEO)) {
                tocado.setFireTicks(Math.max(tocado.getFireTicks(), 80));
                Compat.spawn(tocado.getWorld(), Compat.SMALL_FLAME,
                        tocado.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
            }
        }

        // Flecha pesada: la tercera venia marcada desde el disparo.
        if (e.getDamager().getPersistentDataContainer().has(keyHeavy, PersistentDataType.BYTE)) {
            e.setDamage(e.getDamage() * 2.0);
            if (e.getEntity().getWorld() != null) {
                Compat.spawn(e.getEntity().getWorld(), Compat.CRIT,
                        e.getEntity().getLocation().add(0, 1, 0), 14, 0.3, 0.4, 0.3, 0.15);
                Compat.sound(e.getEntity().getWorld(), e.getEntity().getLocation(),
                        "entity.player.attack.crit", 0.9f, 0.8f);
            }
        }

        // Flecha helada: no pega mas, deja clavado un momento.
        if (type.has(MinionAbility.FLECHA_HELADA) && e.getDamager() instanceof Projectile
                && e.getEntity() instanceof LivingEntity victima) {
            var slow = Compat.effect("slowness");
            if (slow != null) victima.addPotionEffect(new PotionEffect(slow, 60, 0, true, true));
            if (victima.getWorld() != null) {
                Compat.spawn(victima.getWorld(), Compat.SNOWFLAKE,
                        victima.getLocation().add(0, 1, 0), 18, 0.35, 0.5, 0.35, 0.02);
            }
        }
    }

    /**
     * Lo que pasa cuando el golpeado es el esbirro: coraza, espinas y la alarma que
     * pone a los suyos a mirar al que le pego.
     */
    @EventHandler(ignoreCancelled = true)
    public void onTake(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity victima) || !isMinion(victima)) return;
        MinionType type = typeOf(victima);
        if (type == null) return;

        if (type.has(MinionAbility.ACORAZADO)) {
            e.setDamage(e.getDamage() * 0.65);
            Compat.spawn(victima.getWorld(), Compat.CRIT,
                    victima.getLocation().add(0, 1, 0), 6, 0.25, 0.35, 0.25, 0.02);
        }

        LivingEntity agresor = attackerBehind(e.getDamager());
        if (agresor == null || agresor.equals(victima)) return;

        // Espinas: se devuelve una parte, con damage() a secas para que el golpe de
        // vuelta no vuelva a pasar por aqui y se enrede en un bucle.
        if (type.has(MinionAbility.ESPINAS) && e.getDamager() instanceof LivingEntity) {
            double vuelta = e.getFinalDamage() * 0.25;
            if (vuelta > 0.1) {
                agresor.damage(vuelta);
                Compat.spawn(agresor.getWorld(), Compat.CRIT,
                        agresor.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.05);
                Compat.sound(agresor.getWorld(), agresor.getLocation(), "block.sweet_berry_bush.break", 0.7f, 1.2f);
            }
        }

        // Alarma: los de al lado dejan lo que estaban haciendo y van a por el.
        if (type.has(MinionAbility.ALARMA)) {
            int avisados = 0;
            for (Escolta otro : escoltas) {
                LivingEntity companero = otro.mob;
                if (companero.equals(victima) || !companero.isValid() || companero.isDead()) continue;
                if (!companero.getWorld().equals(victima.getWorld())) continue;
                if (companero.getLocation().distanceSquared(victima.getLocation()) > 144) continue;
                if (companero instanceof Mob m) {
                    m.setTarget(agresor);
                    avisados++;
                }
            }
            if (avisados > 0) {
                Compat.sound(victima.getWorld(), victima.getLocation(), "block.bell.use", 0.9f, 1.5f);
                Compat.spawn(victima.getWorld(), Compat.WHITE_SMOKE,
                        victima.getLocation().add(0, 1.2, 0), 16, 0.4, 0.4, 0.4, 0.03);
            }
        }
    }

    /** Quien esta detras de un golpe recibido: el que pega o el que disparo. */
    private LivingEntity attackerBehind(Entity damager) {
        if (damager instanceof LivingEntity living) return living;
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof LivingEntity living) {
            return living;
        }
        return null;
    }

    /**
     * Flecha pesada: se lleva la cuenta de los disparos de CADA esqueleto y la
     * tercera sale marcada. La marca va en la flecha, asi que el golpe extra se
     * cobra al impactar, aunque para entonces el arquero ya haya disparado otra.
     */
    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof LivingEntity shooter) || !isMinion(shooter)) return;
        MinionType type = typeOf(shooter);
        if (type == null || !type.has(MinionAbility.FLECHA_PESADA)) return;

        int shots = arrowCount.merge(shooter.getUniqueId(), 1, Integer::sum);
        if (shots % 3 != 0) return;

        Entity arrow = e.getProjectile();
        arrow.getPersistentDataContainer().set(keyHeavy, PersistentDataType.BYTE, (byte) 1);
        arrow.setGlowing(true);
        Compat.sound(shooter.getWorld(), shooter.getLocation(), "item.trident.throw", 0.8f, 0.7f);
        Compat.spawn(shooter.getWorld(), Compat.ENCHANTED_HIT,
                shooter.getEyeLocation(), 12, 0.2, 0.2, 0.2, 0.05);
    }

    /**
     * La muerte: fuera holograma, y si su tabla de botin tiene algo, el botin de la
     * tabla SUSTITUYE a los drops de fabrica (si esta vacia, cae lo vanilla normal).
     */
    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity mob = e.getEntity();
        if (!isMinion(mob)) return;
        for (Iterator<Escolta> it = escoltas.iterator(); it.hasNext(); ) {
            Escolta esc = it.next();
            if (esc.mob.getUniqueId().equals(mob.getUniqueId())) {
                esc.holo.remove();
                it.remove();
            }
        }
        arrowCount.remove(mob.getUniqueId());
        String spawnerId = mob.getPersistentDataContainer().get(keySpawner, PersistentDataType.STRING);
        if (spawnerId != null) {
            Set<UUID> mine = alive.get(spawnerId);
            if (mine != null) mine.remove(mob.getUniqueId());
        }

        MinionType type = typeOf(mob);
        if (type == null) return;

        // Division: se parte en dos crias de la mitad de nivel. Las crias llevan
        // marca y ya no se dividen, o una sala se llenaria sola hasta reventar.
        if (type.has(MinionAbility.DIVISION)
                && !mob.getPersistentDataContainer().has(keyChild, PersistentDataType.BYTE)) {
            int cria = Math.max(1, levelOf(mob) / 2);
            for (int i = 0; i < 2; i++) {
                Location donde = mob.getLocation().add((i == 0 ? -0.6 : 0.6), 0, 0);
                LivingEntity hijo = spawnAt(type, cria, donde, spawnerId);
                if (hijo != null) {
                    hijo.getPersistentDataContainer().set(keyChild, PersistentDataType.BYTE, (byte) 1);
                    if (mob.getKiller() != null && hijo instanceof Mob m) m.setTarget(mob.getKiller());
                }
            }
            Compat.spawn(mob.getWorld(), Compat.ITEM, mob.getLocation().add(0, 0.6, 0), 20,
                    0.3, 0.3, 0.3, 0.05, new ItemStack(org.bukkit.Material.SLIME_BALL));
            Compat.sound(mob.getWorld(), mob.getLocation(), "entity.slime.squish", 0.9f, 1.3f);
        }

        DropTable table = plugin.drops().table(type.dropTableId());
        if (table.entries().isEmpty() && table.experience() <= 0 && table.commands().isEmpty()) return;

        if (!table.entries().isEmpty()) {
            e.getDrops().clear();
            List<ItemStack> drops = new ArrayList<>();
            for (DropEntry entry : table.entries()) {
                if (random.nextDouble() * 100.0 > entry.chance()) continue;
                int amount = entry.min() + (entry.max() > entry.min()
                        ? random.nextInt(entry.max() - entry.min() + 1) : 0);
                while (amount > 0) {
                    ItemStack copy = entry.item().clone();
                    int n = Math.min(amount, Math.max(1, copy.getMaxStackSize()));
                    copy.setAmount(n);
                    drops.add(copy);
                    amount -= n;
                }
            }
            e.getDrops().addAll(drops);
        }
        if (table.experience() > 0) e.setDroppedExp(table.experience());

        Player killer = mob.getKiller();
        if (killer != null) {
            for (String raw : table.commands()) {
                String cmd = raw.trim();
                if (cmd.isEmpty()) continue;
                // Los prefijos [mejor]/[35%] son de los jefes; aqui el "mejor" es el
                // killer y el dado se respeta igual.
                boolean pelando = true;
                double chance = 100.0;
                while (pelando) {
                    pelando = false;
                    if (cmd.toLowerCase(java.util.Locale.ROOT).startsWith("[mejor]")) {
                        cmd = cmd.substring("[mejor]".length()).trim();
                        pelando = true;
                        continue;
                    }
                    int cierre = cmd.indexOf("%]");
                    if (cmd.startsWith("[") && cierre > 1) {
                        try {
                            chance = Math.max(0, Math.min(100, Double.parseDouble(cmd.substring(1, cierre))));
                            cmd = cmd.substring(cierre + 2).trim();
                            pelando = true;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                if (cmd.isEmpty() || random.nextDouble() * 100.0 >= chance) continue;
                String finalCmd = cmd.replace("%jugador%", killer.getName()).replace("%player%", killer.getName());
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Comando de botin de esbirro fallido: " + finalCmd);
                }
            }
        }
    }

    // ----------------------------------------------------------------- consultas

    public boolean isMinion(Entity e) {
        return e != null && e.getPersistentDataContainer().has(keyType, PersistentDataType.STRING);
    }

    private boolean isHolo(Entity e) {
        return e != null && e.getPersistentDataContainer().has(keyHolo, PersistentDataType.STRING);
    }

    public MinionType typeOf(Entity e) {
        if (e == null) return null;
        String id = e.getPersistentDataContainer().get(keyType, PersistentDataType.STRING);
        return plugin.minions().type(id);
    }

    public int levelOf(Entity e) {
        if (e == null) return 1;
        Integer level = e.getPersistentDataContainer().get(keyLevel, PersistentDataType.INTEGER);
        return level == null ? 1 : level;
    }

    /** Cuantos vivos tiene ese generador ahora mismo, ya purgado de fantasmas. */
    public int aliveOf(String spawnerId) {
        Set<UUID> mine = alive.get(spawnerId);
        if (mine == null) return 0;
        int n = 0;
        for (UUID id : mine) {
            Entity e = Bukkit.getEntity(id);
            if (e != null && !e.isDead()) n++;
        }
        return n;
    }

    /** El esbirro detras de un golpe: el propio bicho o el que disparo el proyectil. */
    private LivingEntity minionBehind(Entity damager) {
        if (damager instanceof LivingEntity living && isMinion(living)) return living;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof LivingEntity living && isMinion(living)) return living;
        }
        return null;
    }
}
