package net.ederus.calamity.hardcore;

import net.ederus.edm.anomaly.minions.MinionManager;
import net.ederus.edm.comun.Compat;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Las amenazas de Calamity (PARCA, planideras, Eco, alucinaciones): la base comun que
 * DIS sec. 0.4 pide hacer UNA vez. Es el unico Listener de la familia y el helper invocar().
 *
 * Una amenaza es cualquier entidad con lethal_world:amenaza en el PDC; esa es siempre la
 * primera comprobacion, asi que con ninguna viva cada evento cuesta una lectura del PDC.
 * Lo que hace por ellas, para que cada gestor no lo repita (y no se le olvide):
 *  - Solo le hacen dano los jugadores (a mano o con lo que disparen). Ni caidas, ni fuego,
 *    ni lava, ni otros mobs: una PARCA que muere ahogada no paga nada y deja un agujero.
 *  - Vida logica: la entidad no pasa de 1024 de vida (tope de Paper). Con mas, la entidad
 *    se queda en 1024 y el dano que recibe se multiplica por escala = 1024 / vidaLogica,
 *    como BossFight.applyHealth. La fraccion de vida de la entidad ES la real.
 *  - Tope por golpe (tope-golpe-fraccion de su seccion en la config): ningun golpe le quita
 *    mas que esa fraccion de su vida logica. Sin esto, un grupo con armas de escalon 18 la
 *    borra en tres golpes y la pelea no existe.
 *  - Apunta el dano LOGICO que le hace cada jugador: el botin se reparte con eso.
 *  - Solo apunta a jugadores, y ningun mob le apunta a ella.
 *  - Nada de transformarse (zombi ahogado), montar, vehiculos, romper bloques, arder,
 *    riendas, chapas, portales ni teletransportes que no hagamos nosotros.
 *  - Al morir no suelta nada ni experiencia: el botin lo pone su gestor.
 *  - Las copias visuales del Eco (eco_copia) no existen como item en el suelo ni en
 *    ningun inventario: si aparecen, se borran.
 *  - Si cae por debajo del mundo, vuelve junto a su presa o a su ancla.
 *
 * Y una tarea cada 2 ticks que mueve las peleas vivas (registrarPelea): una sola para
 * todas, a nombre de LethalWorld y parada en parar(). Solo corre si hay algo que mover.
 */
final class Amenazas implements Listener {

    /** Tope de vida de una entidad en Paper: por encima, el atributo no deja subir. */
    static final double VIDA_MAXIMA_ENTIDAD = 1024;

    /** Lo que sabemos de una amenaza viva. Solo hilo principal. */
    private static final class Estado {
        final LivingEntity entidad;
        final String amenaza;
        double vidaLogica;
        double escala = 1;
        double topeFraccion;
        Location ancla;
        /** Dano logico por jugador, en el orden en que empezaron a pegar. */
        final Map<UUID, Double> dano = new LinkedHashMap<>();

        Estado(LivingEntity entidad, String amenaza) {
            this.entidad = entidad;
            this.amenaza = amenaza;
        }
    }

    private final Hardcore hc;
    private final Map<UUID, Estado> vivas = new HashMap<>();
    /** Las peleas que se mueven cada 2 ticks. Lista y no Set: el orden de registro manda. */
    private final List<Runnable> peleas = new ArrayList<>();
    private BukkitTask tarea;
    /**
     * Levantada mientras teleportamos nosotros: el listener deja pasar ese teletransporte
     * y bloquea todos los demas (enderman, /tp de otro plugin, portales).
     */
    private boolean teleportPropio;

    Amenazas(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Subcomandos.lw().registrar("amenazas",
                "amenazas [contar|limpiar|prueba <x> <y> <z> [vida]]: las PARCA, Ecos y demas vivas",
                "ederus.mundos", this::comando,
                args -> args.length == 2 ? List.of("contar", "limpiar", "prueba") : List.of());
    }

    // ------------------------------------------------------------------ invocar

    /**
     * Crea una amenaza. Todo lo que tiene que llevar va en el consumer del spawn, antes de
     * que nadie la vea: la marca, no persistente (salvo que extra diga otra cosa: el Eco),
     * no se aleja-y-desaparece, no recoge nada y no suelta su equipo.
     *
     * Despues del spawn: otra vez sin recoger (Hardcore.onAparecer corre despues del
     * consumer y a los mobs normales se lo enciende; a las amenazas ya no, pero el doble
     * cerrojo no cuesta nada), su estado con la vida de la entidad como vida logica y el
     * cartel "Nv. X" de MinionManager con dano 1.0 (asi onDeal de EDM no toca su dano).
     * Sin EDM o sin el modulo anomaly, un nombre visible con el mismo texto.
     *
     * Si el spawn lo cancela alguien (WorldGuard, otro plugin), devuelve null.
     *
     * @param amenaza parca|planidera|eco|alucinacion|rondador (va al PDC y elige la seccion
     *                de la config de la que se lee tope-golpe-fraccion)
     * @param extra   lo propio de cada gestor (atributos, equipo, PRESA...); puede ser null
     */
    <T extends LivingEntity> T invocar(Class<T> tipo, Location sitio, String amenaza, int nivel,
                                       Component nombre, Consumer<T> extra) {
        if (tipo == null || sitio == null || sitio.getWorld() == null || amenaza == null) return null;
        // 1.2: en la zona spawn no nace ninguna, como si la hubiera cancelado WorldGuard (la prueba
        // de /lw amenazas si: es de staff y se pone donde se diga).
        if (!"prueba".equals(amenaza) && hc.enSpawn(sitio)) return null;
        World w = sitio.getWorld();
        T mob = w.spawn(sitio, tipo, e -> {
            e.getPersistentDataContainer().set(Marcas.AMENAZA, PersistentDataType.STRING, amenaza);
            e.setPersistent(false);
            e.setRemoveWhenFarAway(false);
            e.setCanPickupItems(false);
            sinSoltarEquipo(e);
            sinModificadores(e);
            if (extra != null) extra.accept(e);
        });
        if (mob == null || !mob.isValid()) return null;
        mob.setCanPickupItems(false);
        sinMontura(mob);

        Estado s = new Estado(mob, amenaza);
        s.vidaLogica = Compat.getAttribute(mob, "max_health", mob.getHealth());
        s.topeFraccion = topeDeConfig(amenaza);
        s.ancla = sitio.clone();
        vivas.put(mob.getUniqueId(), s);
        // Si extra ya puso una vida logica en el PDC (vidaLogica() dentro del consumer no
        // puede: aun no hay estado), se respeta.
        Double guardada = mob.getPersistentDataContainer().get(Marcas.VIDA_LOGICA, PersistentDataType.DOUBLE);
        if (guardada != null && guardada > 0) aplicarVida(s, guardada, false);

        ponerCartel(mob, nivel, nombre == null ? Component.text(amenaza) : nombre);
        asegurarTarea();
        return mob;
    }

    /**
     * Fuera los modificadores que vanilla pone al nacer: World#spawn corre finalizeSpawn ANTES
     * del consumer, y ahi el "lider zombi" multiplica la vida maxima y pide refuerzos, y los
     * bonus al azar tocan el empuje y el rango. Con ellos la vida logica, la escala y el golpe
     * de una amenaza no serian los que calcula su modulo (PARCA, Eco), sino eso por un azar.
     * Va antes de extra: lo que el modulo fije despues se queda.
     */
    static void sinModificadores(LivingEntity e) {
        for (String clave : ATRIBUTOS_SPAWN) {
            org.bukkit.attribute.Attribute a = Compat.attribute(clave);
            org.bukkit.attribute.AttributeInstance ai = a == null ? null : e.getAttribute(a);
            if (ai == null) continue;
            for (org.bukkit.attribute.AttributeModifier mod : new java.util.ArrayList<>(ai.getModifiers())) {
                ai.removeModifier(mod);
            }
        }
    }

    /** Lo que finalizeSpawn puede tocar con un modificador (lider, bonus al azar, bebe). */
    private static final String[] ATRIBUTOS_SPAWN = {"max_health", "attack_damage", "armor", "armor_toughness",
            "movement_speed", "knockback_resistance", "follow_range", "spawn_reinforcements"};

    /**
     * El jinete de gallina (y cualquier montura que vanilla le ponga al nacer) sale montado
     * antes del consumer, cuando aun no lleva la marca y el veto a montar no le alcanza. Una
     * amenaza montada no tiene IA propia y la montura no es una amenaza: se baja y se borra.
     */
    private static void sinMontura(LivingEntity mob) {
        if (!mob.isInsideVehicle()) return;
        Entity montura = mob.getVehicle();
        mob.leaveVehicle();
        if (montura != null && !(montura instanceof org.bukkit.entity.Player)) montura.remove();
    }

    private static void sinSoltarEquipo(LivingEntity e) {
        EntityEquipment eq = e.getEquipment();
        if (eq == null) return;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            try {
                eq.setDropChance(slot, 0f);
            } catch (Throwable ignorado) {
                // Ranuras que esa entidad no tiene (BODY en un zombi, SADDLE...): nada que soltar.
            }
        }
    }

    private void ponerCartel(LivingEntity mob, int nivel, Component nombre) {
        MinionManager mm = minionManager();
        if (mm != null) {
            try {
                mm.adoptar(mob, Math.max(1, nivel), nombre, 1.0);
                return;
            } catch (Throwable t) {
                // Si EDM cambia adoptar, la amenaza sale igual, con el nombre a pelo.
            }
        }
        mob.customName(nombre.append(Component.text(" Nv. " + Math.max(1, nivel), Paleta.TENUE)));
        mob.setCustomNameVisible(true);
    }

    /** El gestor de esbirros de EDM (carteles "Nv. X"), o null sin EDM o sin su modulo anomaly. */
    private MinionManager minionManager() {
        // Se pide a MobsLethal, que es quien va a buscar EDM; sin mobs de Lethal World
        // (EDM sin anomaly) no hay cartel y sale el nombre a pelo.
        return hc.plugin().mobs() == null ? null : hc.plugin().mobs().minionManager();
    }

    private double topeDeConfig(String amenaza) {
        return Math.max(0, hc.cfg().getDouble(amenaza + ".tope-golpe-fraccion", 0));
    }

    // ------------------------------------------------------------------- estado

    /**
     * El estado de una amenaza; si no lo hay (un Eco persistente que vuelve a cargar tras
     * un reinicio, una amenaza que no nacio por invocar) se crea con lo que diga la
     * entidad: vida logica del PDC, o su vida maxima. Null si no es una amenaza.
     */
    private Estado estado(Entity e) {
        if (!(e instanceof LivingEntity le)) return null;
        Estado s = vivas.get(le.getUniqueId());
        if (s != null) return s;
        String amenaza = Marcas.amenaza(le);
        if (amenaza == null) return null;
        s = new Estado(le, amenaza);
        s.vidaLogica = Compat.getAttribute(le, "max_health", le.getHealth());
        s.topeFraccion = topeDeConfig(amenaza);
        s.ancla = le.getLocation();
        vivas.put(le.getUniqueId(), s);
        Double guardada = le.getPersistentDataContainer().get(Marcas.VIDA_LOGICA, PersistentDataType.DOUBLE);
        if (guardada != null && guardada > 0) {
            s.vidaLogica = guardada;
            s.escala = escalaPara(guardada);
        }
        asegurarTarea();
        return s;
    }

    /**
     * Pone la vida logica y la llena. Con mas de 1024, la entidad se queda en 1024 y lo
     * que recibe se escala (DIS sec. 0.4). Se guarda tambien en el PDC: un Eco que se
     * descarga y vuelve sigue sabiendo cuanta vida tiene de verdad.
     */
    void vidaLogica(LivingEntity e, double vidaLogica) {
        Estado s = estado(e);
        if (s == null || vidaLogica <= 0) return;
        aplicarVida(s, vidaLogica, true);
    }

    private void aplicarVida(Estado s, double vidaLogica, boolean llenar) {
        LivingEntity e = s.entidad;
        double real = Math.min(VIDA_MAXIMA_ENTIDAD, vidaLogica);
        double fraccion = fraccion(e);
        Compat.setAttribute(e, "max_health", real);
        e.setHealth(Math.max(0.5, llenar ? real : Math.min(real, real * fraccion)));
        s.vidaLogica = vidaLogica;
        s.escala = escalaPara(vidaLogica);
        e.getPersistentDataContainer().set(Marcas.VIDA_LOGICA, PersistentDataType.DOUBLE, vidaLogica);
    }

    /** La escala de dano para una vida logica: 1 si cabe en la entidad, 1024 / vida si no. */
    static double escalaPara(double vidaLogica) {
        return vidaLogica > VIDA_MAXIMA_ENTIDAD ? VIDA_MAXIMA_ENTIDAD / vidaLogica : 1.0;
    }

    /** Lo que multiplica el dano que recibe (1 con vida de 1024 o menos). 1 si no es amenaza. */
    double escala(LivingEntity e) {
        Estado s = estado(e);
        return s == null ? 1.0 : s.escala;
    }

    /** Su vida logica maxima (la de la config, no la de la entidad). 0 si no es amenaza. */
    double vidaLogicaMaxima(LivingEntity e) {
        Estado s = estado(e);
        return s == null ? 0 : s.vidaLogica;
    }

    /** La vida logica que le queda. */
    double vidaLogicaActual(LivingEntity e) {
        Estado s = estado(e);
        return s == null ? 0 : e.getHealth() / s.escala;
    }

    /** Fraccion de vida que le queda (0-1): la de la entidad, que con la escala es la real. */
    static double fraccion(LivingEntity e) {
        double max = Compat.getAttribute(e, "max_health", 0);
        return max <= 0 ? 0 : Math.max(0, Math.min(1, e.getHealth() / max));
    }

    /** Le deja esa fraccion de vida (0-1). Para probar fases: /lw hardcore parca vida 0.5. */
    void ponerFraccion(LivingEntity e, double fraccion) {
        double max = Compat.getAttribute(e, "max_health", 0);
        if (max <= 0) return;
        e.setHealth(Math.max(0.5, Math.min(max, max * Math.max(0, Math.min(1, fraccion)))));
    }

    /** Cambia el tope por golpe de una amenaza concreta (fraccion de su vida logica; 0 = sin tope). */
    void topeGolpe(LivingEntity e, double fraccion) {
        Estado s = estado(e);
        if (s != null) s.topeFraccion = Math.max(0, fraccion);
    }

    /** A donde vuelve si se cae del mundo y no hay presa cerca. invocar pone el sitio del spawn. */
    void ancla(LivingEntity e, Location donde) {
        Estado s = estado(e);
        if (s != null && donde != null) s.ancla = donde.clone();
    }

    /**
     * Dano logico por jugador (UUID -> puntos de vida logica), en el orden en que
     * empezaron a pegar. Copia: se puede leer en su EntityDeathEvent (el estado se borra un
     * par de ticks despues de morir, no en el acto).
     */
    Map<UUID, Double> danoLogico(LivingEntity e) {
        Estado s = e == null ? null : vivas.get(e.getUniqueId());
        return s == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(s.dano));
    }

    /**
     * El dano logico que se apunta por un golpe: lo que haria (dano final entre la escala)
     * con el tope por golpe. Estatica para el autotest.
     *
     * El golpe de un jugador se mide en vida LOGICA (lo que le quitaria a un mob con esa
     * vida de verdad); a la entidad le llega eso por la escala.
     *
     * @param finalEntidad dano final del golpe, sin escalar
     * @param topeFraccion fraccion de la vida logica (0 = sin tope)
     * @param vidaLogica   la vida logica maxima
     */
    static double golpeLogico(double finalEntidad, double topeFraccion, double vidaLogica) {
        if (finalEntidad <= 0) return 0;
        double logico = finalEntidad;
        if (topeFraccion > 0) logico = Math.min(logico, topeFraccion * vidaLogica);
        return logico;
    }

    /** Teletransporta una amenaza sin que el listener se lo impida. Usarlo SIEMPRE para moverlas. */
    boolean teleportar(Entity e, Location donde) {
        if (e == null || donde == null) return false;
        teleportPropio = true;
        try {
            return e.teleport(donde);
        } finally {
            teleportPropio = false;
        }
    }

    // ------------------------------------------------------------------- peleas

    /** Una pelea viva que tiene que moverse cada 2 ticks (PARCA, planideras, telegraph del Eco). */
    void registrarPelea(Runnable tickCada2) {
        if (tickCada2 == null || peleas.contains(tickCada2)) return;
        peleas.add(tickCada2);
        asegurarTarea();
    }

    /** La quita (la pelea acabo). Se puede llamar desde dentro de su propio tick. */
    void quitarPelea(Runnable tickCada2) {
        peleas.remove(tickCada2);
    }

    /** Las amenazas vivas que conoce (invocadas o ya vistas por el listener). */
    int contarVivas() {
        int n = 0;
        for (Estado s : vivas.values()) if (s.entidad.isValid() && !s.entidad.isDead()) n++;
        return n;
    }

    private void asegurarTarea() {
        if (tarea != null) return;
        tarea = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(), this::cadaDosTicks, 2L, 2L);
    }

    private void cadaDosTicks() {
        // Copia: una pelea que acaba se quita a si misma desde dentro.
        for (Runnable r : new ArrayList<>(peleas)) hc.seguro("amenazas", r);

        for (Iterator<Estado> it = vivas.values().iterator(); it.hasNext(); ) {
            Estado s = it.next();
            LivingEntity e = s.entidad;
            // El estado de un muerto dura hasta aqui, no hasta su EntityDeathEvent: los
            // gestores leen danoLogico() en ese evento, en cualquier prioridad.
            if (!e.isValid() || e.isDead()) {
                it.remove();
                continue;
            }
            if (e.getLocation().getY() < e.getWorld().getMinHeight() + 2) rescatar(s);
        }

        // Sin peleas ni amenazas, la tarea no gira en vacio.
        if (peleas.isEmpty() && vivas.isEmpty() && tarea != null) {
            tarea.cancel();
            tarea = null;
        }
    }

    /** Bajo el mundo: a 3 bloques de su presa si esta en ese mundo, o a su ancla. */
    private void rescatar(Estado s) {
        LivingEntity e = s.entidad;
        Location destino = null;
        String presa = e.getPersistentDataContainer().get(Marcas.PRESA, PersistentDataType.STRING);
        if (presa != null) {
            try {
                Player p = hc.plugin().getServer().getPlayer(UUID.fromString(presa));
                if (p != null && p.isOnline() && p.getWorld().equals(e.getWorld())) {
                    Location suelo = hc.ultimoSuelo(p);
                    destino = suelo != null && suelo.getWorld() == p.getWorld()
                            && suelo.distanceSquared(p.getLocation()) < 16 * 16 ? suelo : p.getLocation();
                    // A 3 bloques por detras de donde mira, si ahi se puede estar.
                    org.bukkit.util.Vector mira = p.getLocation().getDirection().setY(0);
                    Location atras = mira.lengthSquared() < 1e-4 ? destino
                            : destino.clone().subtract(mira.normalize().multiply(3));
                    if (atras != destino && atras.getBlock().isPassable() && atras.clone().add(0, 1, 0).getBlock().isPassable()
                            && !atras.clone().subtract(0, 1, 0).getBlock().isPassable()) {
                        destino = atras;
                    }
                }
            } catch (IllegalArgumentException ignorado) {
                // PRESA mal escrita: se usa el ancla.
            }
        }
        if (destino == null && s.ancla != null && s.ancla.getWorld() == e.getWorld()) destino = s.ancla;
        if (destino == null) {
            // Ni presa ni ancla en su mundo: fuera, que una amenaza cayendo al vacio para
            // siempre es un cartel "Nv. X" flotando bajo el mapa.
            e.remove();
            return;
        }
        e.setFallDistance(0);
        e.setVelocity(new org.bukkit.util.Vector());
        teleportar(e, destino);
    }

    // ------------------------------------------------------------------ listener

    /**
     * Solo los jugadores le hacen dano (a mano, con flechas, tridentes, TNT que encendieron:
     * todo lo que tenga a un jugador como causa). Lo demas se cancela. Lo que pasa se escala
     * a la vida logica y se topa por golpe. /kill (KILL) pasa siempre: es la salida del staff.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDano(EntityDamageEvent e) {
        if (!Marcas.esAmenaza(e.getEntity())) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.KILL) return;
        Entity causa;
        try {
            causa = e.getDamageSource().getCausingEntity();
        } catch (Throwable t) {
            causa = null;
        }
        if (!(causa instanceof Player)) {
            e.setCancelled(true);
            return;
        }
        Estado s = estado(e.getEntity());
        if (s == null) return;
        double fin = e.getFinalDamage();
        if (fin <= 0) return;
        double logico = golpeLogico(fin, s.topeFraccion, s.vidaLogica);
        double deseado = logico * s.escala;
        // setDamage mueve la base y Bukkit recalcula armadura y demas a partir de ella: se
        // escala la base en la proporcion final deseada / final actual.
        if (Math.abs(deseado - fin) > 1e-9) e.setDamage(e.getDamage() * deseado / fin);
    }

    /**
     * Apunta el dano logico del golpe que de verdad entro (MONITOR: ya nadie lo cancela).
     * Topado por la vida que le quedaba: el golpe final no cuenta de mas.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDanoHecho(EntityDamageEvent e) {
        if (!Marcas.esAmenaza(e.getEntity())) return;
        if (!(e.getDamageSource().getCausingEntity() instanceof Player p)) return;
        Estado s = estado(e.getEntity());
        if (s == null) return;
        double entra = Math.min(e.getFinalDamage(), s.entidad.getHealth());
        if (entra <= 0) return;
        s.dano.merge(p.getUniqueId(), entra / s.escala, Double::sum);
    }

    /** Solo apunta a jugadores; y a una amenaza no la apunta nadie que no sea jugador. */
    @EventHandler(ignoreCancelled = true)
    public void onObjetivo(EntityTargetEvent e) {
        Entity objetivo = e.getTarget();
        if (objetivo == null) return;
        if (Marcas.esAmenaza(e.getEntity()) && !(objetivo instanceof Player)) {
            e.setCancelled(true);
            return;
        }
        if (Marcas.esAmenaza(objetivo) && !(e.getEntity() instanceof Player)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransformar(EntityTransformEvent e) {
        if (Marcas.esAmenaza(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMontar(EntityMountEvent e) {
        if (Marcas.esAmenaza(e.getEntity()) || Marcas.esAmenaza(e.getMount())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehiculo(VehicleEnterEvent e) {
        if (Marcas.esAmenaza(e.getEntered())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBloque(EntityChangeBlockEvent e) {
        if (Marcas.esAmenaza(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onArder(EntityCombustEvent e) {
        if (Marcas.esAmenaza(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRienda(PlayerLeashEntityEvent e) {
        if (Marcas.esAmenaza(e.getEntity())) e.setCancelled(true);
    }

    /** Ni chapas (le cambiarian el nombre y la volverian persistente) ni nada con clic derecho. */
    @EventHandler(ignoreCancelled = true)
    public void onInteractuar(PlayerInteractEntityEvent e) {
        if (!Marcas.esAmenaza(e.getRightClicked())) return;
        ItemStack mano = e.getPlayer().getInventory().getItem(e.getHand());
        if (mano != null && mano.getType() == Material.NAME_TAG) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(EntityTeleportEvent e) {
        if (!teleportPropio && Marcas.esAmenaza(e.getEntity())) e.setCancelled(true);
    }

    /** Portales: tienen su propia lista de escuchas, no la de EntityTeleportEvent. */
    @EventHandler(ignoreCancelled = true)
    public void onPortal(EntityPortalEvent e) {
        if (Marcas.esAmenaza(e.getEntity())) e.setCancelled(true);
    }

    /** Sin botin ni experiencia: lo que den lo decide su gestor, no vanilla ni otro plugin. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMuerte(EntityDeathEvent e) {
        if (!Marcas.esAmenaza(e.getEntity())) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
    }

    /** Una copia del Eco no puede existir como item suelto: se borra antes de caer. */
    @EventHandler(ignoreCancelled = true)
    public void onItem(ItemSpawnEvent e) {
        if (Marcas.tiene(e.getEntity().getItemStack(), Marcas.ECO_COPIA)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRecoger(EntityPickupItemEvent e) {
        borrarSiCopia(e.getItem(), () -> e.setCancelled(true));
    }

    @EventHandler(ignoreCancelled = true)
    public void onTolva(InventoryPickupItemEvent e) {
        borrarSiCopia(e.getItem(), () -> e.setCancelled(true));
    }

    private static void borrarSiCopia(Item item, Runnable cancelar) {
        if (item == null || !Marcas.tiene(item.getItemStack(), Marcas.ECO_COPIA)) return;
        cancelar.run();
        item.remove();
    }

    // ------------------------------------------------------------------- comando

    /** /lw hardcore amenazas [contar|limpiar]. Mira las entidades cargadas: es la verdad, no el registro. */
    private void comando(CommandSender quien, String[] args) {
        String que = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "contar";
        List<LivingEntity> todas = new ArrayList<>();
        for (World w : hc.plugin().getServer().getWorlds()) {
            for (LivingEntity e : w.getLivingEntities()) if (Marcas.esAmenaza(e)) todas.add(e);
        }
        switch (que) {
            case "contar" -> {
                // Solo el total lleva cifras: el banco de pruebas busca el numero suelto.
                StringBuilder tipos = new StringBuilder();
                for (int i = 0; i < todas.size() && i < 10; i++) {
                    if (i > 0) tipos.append(", ");
                    tipos.append(Marcas.amenaza(todas.get(i)));
                }
                if (todas.size() > 10) tipos.append(", ...");
                quien.sendMessage(Component.text("amenazas | " + todas.size()
                        + (todas.isEmpty() ? "" : " | " + tipos), Paleta.TENUE));
            }
            case "limpiar" -> {
                // Los gestores ven su entidad invalida en su siguiente tick y cierran la pelea.
                // Los Ecos siguen en hardcore-datos.yml: vuelven a despertar.
                for (LivingEntity e : todas) e.remove();
                vivas.clear();
                quien.sendMessage(Component.text("amenazas | retiradas " + todas.size(), Paleta.TENUE));
                try {
                    hc.plugin().bitacora().anotar("amenazas", "limpiar", quien.getName(), String.valueOf(todas.size()));
                } catch (Throwable ignorado) {
                    // Sin bitacora no se pierde nada: ya se dijo por el comando.
                }
            }
            case "prueba" -> prueba(quien, args);
            default -> quien.sendMessage(Component.text(
                    "Uso: /lw hardcore amenazas [contar|limpiar|prueba <x> <y> <z> [vida]]", Paleta.AVISO));
        }
    }

    /**
     * Una amenaza de prueba (un zombi quieto, tipo "prueba") en el primer mundo hardcore,
     * para ver la base sin PARCA ni Eco: cartel, vida logica, que /damage sin jugador no
     * le hace nada, que no persiste. Sin IA: no se mueve ni apunta a nadie.
     */
    private void prueba(CommandSender quien, String[] args) {
        if (args.length < 5) {
            quien.sendMessage(Component.text("Uso: /lw hardcore amenazas prueba <x> <y> <z> [vida]", Paleta.AVISO));
            return;
        }
        World w = null;
        for (String m : hc.mundos()) {
            for (World x : hc.plugin().getServer().getWorlds()) {
                if (hc.esHardcore(x) && x.getKey().getKey().equals(m)) w = x;
            }
            if (w != null) break;
        }
        if (w == null) {
            quien.sendMessage(Component.text("No hay ningun mundo hardcore cargado.", Paleta.AVISO));
            return;
        }
        double x, y, z, vida;
        try {
            x = Double.parseDouble(args[2]);
            y = Double.parseDouble(args[3]);
            z = Double.parseDouble(args[4]);
            vida = args.length > 5 ? Double.parseDouble(args[5]) : 40;
        } catch (NumberFormatException e) {
            quien.sendMessage(Component.text("Coordenadas o vida no validas.", Paleta.AVISO));
            return;
        }
        Location sitio = new Location(w, x, y, z);
        org.bukkit.entity.Zombie z0 = invocar(org.bukkit.entity.Zombie.class, sitio, "prueba", 1,
                Component.text("Amenaza de prueba", Paleta.AVISO), e -> {
                    e.setAI(false);
                    e.setShouldBurnInDay(false);
                    e.setAdult();
                });
        if (z0 == null) {
            quien.sendMessage(Component.text("amenazas | prueba | no ha salido (spawn cancelado)", Paleta.AVISO));
            return;
        }
        vidaLogica(z0, vida);
        quien.sendMessage(Component.text("amenazas | prueba | " + z0.getUniqueId() + " | vida logica "
                + vidaLogicaMaxima(z0) + " | entidad " + z0.getHealth() + " | escala " + escala(z0), Paleta.TENUE));
    }

    // --------------------------------------------------------------------- parar

    /**
     * Para la tarea y retira lo que no es persistente (PARCA, planideras): una amenaza sin
     * su gestor detras es un mob con marca que nadie cierra. El Eco (persistente) se queda:
     * lo gestiona Ecos, que lo guarda antes.
     */
    void parar() {
        if (tarea != null) tarea.cancel();
        tarea = null;
        peleas.clear();
        for (Estado s : vivas.values()) {
            if (s.entidad.isValid() && !s.entidad.isPersistent()) s.entidad.remove();
        }
        vivas.clear();
        HandlerList.unregisterAll(this);
    }
}
