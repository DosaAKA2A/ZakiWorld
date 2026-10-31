package net.ederus.edm.goditems;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.Poder;
import net.ederus.edm.goditems.equipo.Calculo;
import net.ederus.edm.goditems.equipo.Definicion.Config;
import net.ederus.edm.goditems.equipo.Equipo;

/**
 * Los efectos de las armas a distancia (EDM 1.91.0): arcos y tridentes lanzados.
 *
 * No son activadores ni acciones: son CLAVES de los efectos de equipo
 * (`plugins/EDM/goditems/equipo/*.yml`), las `distancia.*`, y se dan como
 * cualquier otra clave, por pieza (un arco de caja que atraviesa) o por escalon
 * de set (el [3] de Orion: flechas mas rapidas con estela). Asi el lore lo
 * escribe GodItems con las mismas cifras que se aplican, y un set nuevo de arco
 * se monta solo con YAML.
 *
 * Que cuenta en cada disparo: la armadura puesta y el arma que DISPARA. Si en la
 * otra mano hay otro arco, ballesta o tridente, no cuenta en ese disparo: un arco
 * de caja en la mano izquierda no le presta su "atraviesa a 3" al de la derecha, y
 * el [5] de Orion (cinco piezas: las cuatro de armadura y Betelgeuse) solo sale
 * disparando con Betelgeuse.
 *
 * El daño: un arco de MMOItems ya escala con la carga (MMOItems multiplica su
 * attack-damage por la fuerza del disparo) y MythicLib le pasa sus criticos,
 * porque el tipo BOW hace daño WEAPON. Aqui se toca el daño en HIGH, despues de
 * que MythicLib haya puesto el suyo (LOWEST) y ANTES de que lo lea para tirar el
 * critico (HIGHEST): asi el critico multiplica el golpe ya con los bonos.
 */
public final class Distancia implements Listener {

    /* ------------------------------------------------------------ claves */

    public static final String DANO = "distancia.dano";
    public static final String VELOCIDAD = "distancia.velocidad";
    public static final String ESTELA = "distancia.estela";
    public static final String MARCADA_CADA = "distancia.marcada-cada";
    public static final String MARCADA_DANO = "distancia.marcada-dano";
    public static final String MARCADA_MAX = "distancia.marcada-max";
    public static final String MARCADA_SEGUNDOS = "distancia.marcada-segundos";
    public static final String LLUVIA_FLECHAS = "distancia.lluvia-flechas";
    public static final String LLUVIA_DANO = "distancia.lluvia-dano";
    public static final String LLUVIA_RADIO = "distancia.lluvia-radio";
    public static final String LLUVIA_ENFRIAMIENTO = "distancia.lluvia-enfriamiento";
    public static final String PERFORAR = "distancia.perforar";
    public static final String TRIPLE_CADA = "distancia.triple-cada";
    public static final String FURIA = "distancia.furia";
    public static final String FURIA_UMBRAL = "distancia.furia-umbral";
    public static final String TRIDENTE = "distancia.tridente-lanzado";

    /** Carga desde la que un disparo cuenta como "cargado del todo". */
    private static final double CARGA_LLENA = 0.95;
    /** Apertura del abanico del disparo triple, a cada lado. */
    private static final double ABANICO_GRADOS = 10;
    /** Lo que se sigue a una flecha con estela, como mucho. */
    private static final long ESTELA_MS = 8000L;

    /* ------------------------------------------------- marcas en las flechas */

    private final NamespacedKey kMult;
    private final NamespacedKey kMarca;
    private final NamespacedKey kLluvia;
    private final NamespacedKey kFijo;
    private final NamespacedKey kBase;
    private final NamespacedKey kPropia;
    private final NamespacedKey kEstrella;

    /* ------------------------------------------------------------ memoria */

    /** Una pila de marcas de un tirador sobre un objetivo. */
    private static final class Marca {
        int pilas;
        long hasta;
        double porPila;
    }

    private record Estela(AbstractArrow flecha, long hasta) { }

    private final GodItemsPlugin modulo;
    /** Disparos de cada jugador (para el triple) y disparos cargados (para la Flecha Marcada). */
    private final Map<UUID, Integer> disparos = new HashMap<>();
    private final Map<UUID, Integer> cargados = new HashMap<>();
    /** objetivo -> (tirador -> marca). */
    private final Map<UUID, Map<UUID, Marca>> marcas = new HashMap<>();
    /** jugador -> cuando vuelve a estar lista la lluvia, en ms. */
    private final Map<UUID, Long> lluvias = new HashMap<>();
    private final Set<Estela> estelas = new LinkedHashSet<>();
    private BukkitTask tarea;
    private int vueltas;
    private boolean hayMythic;

    public Distancia(GodItemsPlugin modulo) {
        this.modulo = modulo;
        this.kMult = new NamespacedKey(modulo, "dist_mult");
        this.kMarca = new NamespacedKey(modulo, "dist_marca");
        this.kLluvia = new NamespacedKey(modulo, "dist_lluvia");
        this.kFijo = new NamespacedKey(modulo, "dist_fijo");
        this.kBase = new NamespacedKey(modulo, "dist_base");
        this.kPropia = new NamespacedKey(modulo, "dist_propia");
        this.kEstrella = new NamespacedKey(modulo, "dist_estrella");
    }

    public void arrancar() {
        this.hayMythic = Bukkit.getPluginManager().getPlugin("MythicLib") != null;
        this.modulo.core().getServer().getPluginManager().registerEvents(this, this.modulo);
        this.tarea = this.modulo.core().getServer().getScheduler().runTaskTimer(this.modulo.core(),
                this::repasar, 2L, 2L);
    }

    public void parar() {
        HandlerList.unregisterAll(this);
        if (this.tarea != null) this.tarea.cancel();
        this.tarea = null;
        this.disparos.clear();
        this.cargados.clear();
        this.marcas.clear();
        this.lluvias.clear();
        this.estelas.clear();
    }

    /* ============================================================ disparo */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alDisparar(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || !(e.getProjectile() instanceof AbstractArrow flecha)) return;
        EquipmentSlot mano = e.getHand() == EquipmentSlot.OFF_HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        Map<String, Double> c = claves(p, e.getBow(), mano);
        if (c.isEmpty()) return;
        PersistentDataContainer pdc = flecha.getPersistentDataContainer();
        boolean llena = carga(e.getForce()) >= CARGA_LLENA;
        boolean mythic = this.hayMythic && MythicFlechas.tiene(flecha);

        /* El daño "del arco" de este disparo, antes de tocar la velocidad: lo que
         * pegaria la flecha sin bonos. Sirve para las flechas que pierden la ficha
         * de MythicLib (las que atraviesan) y para la lluvia. */
        double base = mythic ? MythicFlechas.dano(flecha) * Poder.factorPoder(e.getBow())
                : Math.ceil(flecha.getDamage() * flecha.getVelocity().length());

        double mult = 1 + v(c, DANO) + furia(p, v(c, FURIA), v(c, FURIA_UMBRAL));
        if (Math.abs(mult - 1) > 1e-6) pdc.set(this.kMult, PersistentDataType.DOUBLE, mult);

        double vel = v(c, VELOCIDAD);
        if (vel > 0) {
            flecha.setVelocity(flecha.getVelocity().multiply(1 + vel));
            /* En vanilla el daño de la flecha va por su velocidad: se compensa para
             * que "mas rapida" no sea tambien "mas fuerte". Con MythicLib el daño ya
             * no depende de la velocidad. */
            if (!mythic) flecha.setDamage(flecha.getDamage() / (1 + vel));
        }

        if (v(c, ESTELA) > 0) seguir(flecha);

        int perforar = (int) Math.round(v(c, PERFORAR));
        if (perforar > 0) {
            flecha.setPierceLevel(Math.min(127, Math.max(flecha.getPierceLevel(), perforar)));
            pdc.set(this.kBase, PersistentDataType.DOUBLE, base);
        }

        int cadaMarca = (int) Math.round(v(c, MARCADA_CADA));
        if (cadaMarca > 0 && llena && contar(this.cargados, p) % cadaMarca == 0) {
            double porPila = v(c, MARCADA_DANO);
            int max = Math.max(1, (int) Math.round(v(c, MARCADA_MAX)));
            double seg = v(c, MARCADA_SEGUNDOS) > 0 ? v(c, MARCADA_SEGUNDOS) : 8;
            pdc.set(this.kMarca, PersistentDataType.STRING, porPila + ";" + max + ";" + seg);
            flecha.setGlowing(true);
            if (!estaSeguida(flecha)) seguir(flecha);
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.6f);
        }

        int estrellas = (int) Math.round(v(c, LLUVIA_FLECHAS));
        if (estrellas > 0 && llena) {
            long ahora = System.currentTimeMillis();
            Long lista = this.lluvias.get(p.getUniqueId());
            if (lista == null || lista <= ahora) {
                double enfr = v(c, LLUVIA_ENFRIAMIENTO) > 0 ? v(c, LLUVIA_ENFRIAMIENTO) : 30;
                this.lluvias.put(p.getUniqueId(), ahora + (long) (enfr * 1000));
                double pct = v(c, LLUVIA_DANO) > 0 ? v(c, LLUVIA_DANO) : 0.35;
                double radio = v(c, LLUVIA_RADIO) > 0 ? v(c, LLUVIA_RADIO) : 4;
                pdc.set(this.kLluvia, PersistentDataType.STRING, estrellas + ";" + (base * pct) + ";" + radio);
                if (!estaSeguida(flecha)) seguir(flecha);
                aviso(p, "lluvia", "&#6FD3FF✦ Lluvia de estrellas", Map.of());
            }
        }

        int cadaTriple = (int) Math.round(v(c, TRIPLE_CADA));
        if (cadaTriple > 0 && contar(this.disparos, p) % cadaTriple == 0) triple(p, flecha, mythic);
    }

    /** Las dos flechas de los lados del abanico, copias de la del centro. */
    private void triple(Player p, AbstractArrow centro, boolean mythic) {
        Vector v = centro.getVelocity();
        for (int lado = -1; lado <= 1; lado += 2) {
            Vector dir = v.clone().rotateAroundY(Math.toRadians(ABANICO_GRADOS * lado));
            Arrow copia = centro.getWorld().spawn(centro.getLocation(), Arrow.class, a -> {
                a.setShooter(p);
                a.setVelocity(dir);
                a.setDamage(centro.getDamage());
                a.setCritical(centro.isCritical());
                a.setFireTicks(centro.getFireTicks());
                a.setPierceLevel(centro.getPierceLevel());
                a.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY);
                a.setGlowing(false);
                PersistentDataContainer de = centro.getPersistentDataContainer();
                PersistentDataContainer a2 = a.getPersistentDataContainer();
                Double mult = de.get(this.kMult, PersistentDataType.DOUBLE);
                if (mult != null) a2.set(this.kMult, PersistentDataType.DOUBLE, mult);
                Double base = de.get(this.kBase, PersistentDataType.DOUBLE);
                if (base != null) a2.set(this.kBase, PersistentDataType.DOUBLE, base);
                a2.set(this.kPropia, PersistentDataType.BYTE, (byte) 1);
            });
            if (mythic) MythicFlechas.copiar(centro, copia);
            if (estaSeguida(centro)) seguir(copia);
        }
    }

    /* ==================================================== tridente lanzado */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alLanzar(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof Trident t) || !(t.getShooter() instanceof Player p)) return;
        ItemStack item = t.getItemStack();
        Map<String, Double> c = claves(p, item, EquipmentSlot.HAND);
        double dano = v(c, TRIDENTE);
        if (dano > 0) t.getPersistentDataContainer().set(this.kFijo, PersistentDataType.DOUBLE, dano);
    }

    /* ================================================================ daño */

    /**
     * En HIGH: despues del daño que pone MythicLib a sus proyectiles (LOWEST) y
     * antes de que lo lea para sus criticos (HIGHEST).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alGolpear(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof AbstractArrow flecha) || !(flecha.getShooter() instanceof Player p)) return;
        PersistentDataContainer pdc = flecha.getPersistentDataContainer();
        if (e.getEntity().equals(p)) {
            /* Las flechas que fabricamos (abanico, lluvia) nunca hieren al que las tira. */
            if (pdc.has(this.kPropia, PersistentDataType.BYTE)) e.setCancelled(true);
            return;
        }
        double dano = e.getDamage();
        boolean tocado = false;
        Double fijo = pdc.get(this.kFijo, PersistentDataType.DOUBLE);
        Double base = pdc.get(this.kBase, PersistentDataType.DOUBLE);
        if (fijo != null) {
            dano = fijo;
            tocado = true;
        } else if (base != null && !(this.hayMythic && MythicFlechas.tiene(flecha))) {
            /* Una flecha que atraviesa pierde la ficha de MythicLib tras el primer
             * golpe y los siguientes pegarian como una de vanilla. */
            dano = base;
            tocado = true;
        }
        Double mult = pdc.get(this.kMult, PersistentDataType.DOUBLE);
        if (mult != null) {
            dano *= mult;
            tocado = true;
        }
        Marca m = marca(e.getEntity(), p);
        if (m != null && m.pilas > 0) {
            dano *= 1 + m.pilas * m.porPila;
            tocado = true;
        }
        if (tocado) e.setDamage(Math.max(0, dano));

        String marcada = pdc.get(this.kMarca, PersistentDataType.STRING);
        if (marcada != null && e.getEntity() instanceof LivingEntity vivo) {
            pdc.remove(this.kMarca);
            marcar(p, vivo, marcada);
        }
    }

    private void marcar(Player p, LivingEntity vivo, String datos) {
        String[] d = datos.split(";");
        double porPila;
        int max;
        double seg;
        try {
            porPila = Double.parseDouble(d[0]);
            max = Integer.parseInt(d[1]);
            seg = Double.parseDouble(d[2]);
        } catch (RuntimeException ex) {
            return;
        }
        Marca m = this.marcas.computeIfAbsent(vivo.getUniqueId(), k -> new HashMap<>())
                .computeIfAbsent(p.getUniqueId(), k -> new Marca());
        if (m.hasta <= System.currentTimeMillis()) m.pilas = 0;
        m.pilas = Math.min(max, m.pilas + 1);
        m.porPila = porPila;
        m.hasta = System.currentTimeMillis() + (long) (seg * 1000);
        Location l = vivo.getLocation().add(0, vivo.getHeight() * 0.6, 0);
        vivo.getWorld().spawnParticle(Particle.DUST, l, 18, 0.35, 0.45, 0.35, 0,
                new Particle.DustOptions(colorEstela(), 1.4f));
        vivo.getWorld().spawnParticle(Particle.END_ROD, l, 6, 0.3, 0.4, 0.3, 0.02);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.4f);
        aviso(p, "marca", "&#6FD3FF✦ Flecha Marcada &8» &f%marcas%&7/&f%max% &8· &f+%extra% &7de daño",
                Map.of("%marcas%", String.valueOf(m.pilas), "%max%", String.valueOf(max),
                        "%extra%", net.ederus.edm.goditems.equipo.Redaccion.numero(m.pilas * porPila * 100) + "%"));
    }

    /** La marca viva de ese tirador sobre ese objetivo, o null. */
    private Marca marca(Entity objetivo, Player tirador) {
        Map<UUID, Marca> de = this.marcas.get(objetivo.getUniqueId());
        if (de == null) return null;
        Marca m = de.get(tirador.getUniqueId());
        if (m == null) return null;
        if (m.hasta <= System.currentTimeMillis()) {
            de.remove(tirador.getUniqueId());
            if (de.isEmpty()) this.marcas.remove(objetivo.getUniqueId());
            return null;
        }
        return m;
    }

    /* ============================================================== impacto */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alImpactar(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow flecha)) return;
        PersistentDataContainer pdc = flecha.getPersistentDataContainer();
        if (pdc.has(this.kEstrella, PersistentDataType.BYTE) && e.getHitBlock() != null) {
            /* Una estrella clavada en el suelo sobra: fuera al tick siguiente. */
            this.modulo.core().getServer().getScheduler().runTask(this.modulo.core(), flecha::remove);
            return;
        }
        String lluvia = pdc.get(this.kLluvia, PersistentDataType.STRING);
        if (lluvia == null || !(flecha.getShooter() instanceof Player p)) return;
        pdc.remove(this.kLluvia);
        Location centro = e.getHitEntity() != null ? e.getHitEntity().getLocation()
                : e.getHitBlock() != null ? e.getHitBlock().getLocation().add(0.5, 1, 0.5)
                : flecha.getLocation();
        String[] d = lluvia.split(";");
        try {
            lluvia(p, centro, Integer.parseInt(d[0]), Double.parseDouble(d[1]), Double.parseDouble(d[2]));
        } catch (RuntimeException ignorado) {
            // datos rotos en la flecha: sin lluvia
        }
    }

    /**
     * La lluvia de estrellas: unas `n` flechas (n-2 a n+2) que caen en un disco de
     * `radio` alrededor del impacto, repartidas en unos ticks. Son flechas de
     * verdad con el jugador de tirador, asi que WorldGuard (pvp) y los clanes
     * deciden si hieren como con cualquier flecha suya.
     */
    private void lluvia(Player p, Location centro, int n, double dano, double radio) {
        World w = centro.getWorld();
        if (w == null) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int total = Math.max(1, n - 2 + r.nextInt(5));
        w.playSound(centro, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1f, 1.5f);
        w.spawnParticle(Particle.END_ROD, centro.clone().add(0, 1, 0), 30, radio * 0.5, 0.5, radio * 0.5, 0.05);
        for (int i = 0; i < total; i++) {
            long retraso = 2L + i / 2;
            this.modulo.core().getServer().getScheduler().runTaskLater(this.modulo.core(), () -> {
                if (!p.isOnline()) return;
                double ang = r.nextDouble(Math.PI * 2);
                double dist = radio * Math.sqrt(r.nextDouble());
                Location suelo = centro.clone().add(Math.cos(ang) * dist, 0, Math.sin(ang) * dist);
                Location alto = cielo(suelo, 10 + r.nextInt(3));
                Vector caida = new Vector(r.nextDouble(-0.05, 0.05), -1.8, r.nextDouble(-0.05, 0.05));
                Arrow estrella = w.spawn(alto, Arrow.class, a -> {
                    a.setShooter(p);
                    a.setVelocity(caida);
                    a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                    a.setCritical(false);
                    PersistentDataContainer pdc = a.getPersistentDataContainer();
                    pdc.set(this.kFijo, PersistentDataType.DOUBLE, dano);
                    pdc.set(this.kPropia, PersistentDataType.BYTE, (byte) 1);
                    pdc.set(this.kEstrella, PersistentDataType.BYTE, (byte) 1);
                });
                seguir(estrella);
            }, retraso);
        }
    }

    /** Hasta `alto` bloques por encima de `suelo`, parando debajo del primer techo. */
    private static Location cielo(Location suelo, int alto) {
        Location l = suelo.clone();
        for (int i = 0; i < alto; i++) {
            Location sig = l.clone().add(0, 1, 0);
            Material m = sig.getBlock().getType();
            if (m.isSolid()) break;
            l = sig;
        }
        return l;
    }

    /* =============================================================== estela */

    private void seguir(AbstractArrow flecha) {
        this.estelas.add(new Estela(flecha, System.currentTimeMillis() + ESTELA_MS));
    }

    private boolean estaSeguida(AbstractArrow flecha) {
        for (Estela s : this.estelas) if (s.flecha() == flecha) return true;
        return false;
    }

    private void repasar() {
        if (!this.estelas.isEmpty()) {
            long ahora = System.currentTimeMillis();
            Particle.DustOptions polvo = new Particle.DustOptions(colorEstela(), tamanoEstela());
            Iterator<Estela> it = this.estelas.iterator();
            while (it.hasNext()) {
                Estela s = it.next();
                AbstractArrow f = s.flecha();
                if (!f.isValid() || f.isInBlock() || f.isOnGround() || s.hasta() <= ahora) {
                    it.remove();
                    continue;
                }
                Location l = f.getLocation();
                f.getWorld().spawnParticle(Particle.DUST, l, 2, 0.05, 0.05, 0.05, 0, polvo);
                f.getWorld().spawnParticle(Particle.END_ROD, l, 1, 0, 0, 0, 0);
            }
        }
        /* Cada ~5 s: marcas caducadas y lluvias que vuelven a estar listas. */
        if (++this.vueltas % 50 != 0) return;
        long ahora = System.currentTimeMillis();
        this.marcas.values().forEach(m -> m.values().removeIf(x -> x.hasta <= ahora));
        this.marcas.values().removeIf(Map::isEmpty);
        Iterator<Map.Entry<UUID, Long>> it = this.lluvias.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> en = it.next();
            if (en.getValue() > ahora) continue;
            it.remove();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null) aviso(p, "lluvia-lista", "&#6FD3FF✦ &7Tu lluvia de estrellas vuelve a estar lista", Map.of());
        }
    }

    private Color colorEstela() {
        String hex = this.modulo.getConfig().getString("distancia.estela.color", "#6FD3FF");
        try {
            return Color.fromRGB(Integer.parseInt(hex.replace("#", "").trim(), 16));
        } catch (RuntimeException e) {
            return Color.fromRGB(0x6FD3FF);
        }
    }

    private float tamanoEstela() {
        return (float) Math.max(0.3, Math.min(3, this.modulo.getConfig().getDouble("distancia.estela.tamano", 1.1)));
    }

    /* ============================================================== ayudas */

    /**
     * Las claves `distancia.*` de ese jugador para un disparo con `arma` en `mano`:
     * armadura puesta + el arma que dispara; otra arma a distancia en la otra
     * mano no cuenta.
     */
    public Map<String, Double> claves(Player p, ItemStack arma, EquipmentSlot mano) {
        Equipo eq = this.modulo.equipo();
        if (eq == null) return Map.of();
        Config c = eq.config();
        if (c.vacia()) return Map.of();
        Map<EquipmentSlot, ItemStack> equipo = Calculo.equipoDe(p);
        if (aDistancia(equipo.get(EquipmentSlot.HAND))) equipo.remove(EquipmentSlot.HAND);
        if (aDistancia(equipo.get(EquipmentSlot.OFF_HAND))) equipo.remove(EquipmentSlot.OFF_HAND);
        if (arma != null && !arma.getType().isAir()) equipo.put(mano, arma);
        Map<String, Double> out = new HashMap<>();
        eq.calcular(c, equipo).topado().forEach((k, val) -> {
            if (k.startsWith("distancia.")) out.put(k, val);
        });
        return out;
    }

    private static boolean aDistancia(ItemStack i) {
        if (i == null) return false;
        Material m = i.getType();
        return m == Material.BOW || m == Material.CROSSBOW || m == Material.TRIDENT;
    }

    private static double v(Map<String, Double> c, String k) {
        Double d = c.get(k);
        return d == null ? 0 : d;
    }

    /**
     * La fuerza del disparo en 0-1. Igual que MMOItems: en las 1.21 a 1.21.4 el
     * evento la daba en 0-3; en las demas, en 0-1.
     */
    private static double carga(float fuerza) {
        double f = fuerza > 1.0001f ? fuerza / 3.0 : fuerza;
        return Math.max(0, Math.min(1, f));
    }

    /**
     * El extra por vida baja: 0 con la vida llena y `max` al bajar del `umbral`
     * (fraccion de la vida maxima), lineal entre medias. Sin umbral, lineal hasta 0.
     */
    static double furia(Player p, double max, double umbral) {
        if (max <= 0) return 0;
        double vidaMax = Textos.maxVida(p);
        if (vidaMax <= 0) return 0;
        double h = Math.max(0, Math.min(1, p.getHealth() / vidaMax));
        double u = Math.max(0, Math.min(0.99, umbral));
        double t = (1 - h) / (1 - u);
        return max * Math.max(0, Math.min(1, t));
    }

    private int contar(Map<UUID, Integer> donde, Player p) {
        return donde.merge(p.getUniqueId(), 1, Integer::sum);
    }

    private void aviso(Player p, String clave, String pordefecto, Map<String, String> cambios) {
        String t = this.modulo.getConfig().getString("distancia.mensajes." + clave, pordefecto);
        if (t == null || t.isBlank()) return;
        for (Map.Entry<String, String> c : cambios.entrySet()) t = t.replace(c.getKey(), c.getValue());
        p.sendActionBar(Estilo.legado(t));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alSalir(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        this.disparos.remove(u);
        this.cargados.remove(u);
        this.marcas.remove(u);
    }
}
