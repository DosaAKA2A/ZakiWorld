package net.ederus.edm.anomaly.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.boss.BossFight;
import net.ederus.edm.comun.Bitacora;
import net.ederus.edm.anomaly.boss.Keeper;
import net.ederus.edm.anomaly.boss.PhaseBars;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.ederus.edm.comun.Fx;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Tags;

/**
 * El director del evento: abre la anomalia, la mantiene viva, reparte el botin y
 * se asegura de que no quede nada suelto en el mundo cuando termina.
 *
 * Solo puede haber una anomalia abierta a la vez. Es a proposito: el anuncio pierde
 * fuerza si hay tres a la vez, y el servidor tambien.
 */
public final class AnomalyManager implements Listener {

    private final AnomalyPlugin plugin;
    private ActiveAnomaly current;
    private BukkitTask ticker;
    private BukkitTask autoTask;
    private final List<Chunk> forced = new ArrayList<>();
    private boolean searching;

    public AnomalyManager(AnomalyPlugin plugin) {
        this.plugin = plugin;
    }

    public ActiveAnomaly current() {
        return current;
    }

    public boolean active() {
        return current != null;
    }

    public boolean searching() {
        return searching;
    }

    // ------------------------------------------------------------------- apertura

    /**
     * Abre la anomalia donde le toque: en su punto fijo si el admin marco uno desde
     * el menu, y si no buscando un sitio aleatorio valido. La busqueda es asincrona,
     * asi que el resultado llega por el callback: true si se abrio, false si no
     * habia ningun sitio valido.
     */
    public void start(AnomalyType type, Consumer<Boolean> done) {
        if (active() || searching) {
            done.accept(false);
            return;
        }

        // El punto marcado manda: ahi no se busca ni se comprueba nada, porque el
        // admin eligio ese bloque a proposito (un coliseo, una arena construida).
        Location fixed = plugin.registry().spawnPoint(type);
        if (fixed != null) {
            open(type, fixed);
            done.accept(active());
            return;
        }

        searching = true;
        plugin.sites().find(type, loc -> {
            searching = false;
            if (loc == null) {
                done.accept(false);
                return;
            }
            open(type, loc);
            done.accept(true);
        });
    }

    /** Abre la anomalia en un punto concreto, sin buscar ni comprobar protecciones. */
    public void open(AnomalyType type, Location where) {
        if (active()) return;
        ActiveAnomaly event = new ActiveAnomaly(type, where);
        current = event;

        forceLoad(where);

        BossFight fight = type.create(plugin, event, where);
        event.fight(fight);
        // Un jefe doble pinta sus propias barras (una por gemelo): la de fases sobra.
        if (!fight.usesOwnBars()) {
            event.bars(new PhaseBars(type.display(), type.color(), fight.phaseCount()));
        }

        /* El titulo y los ajustes van ANTES de spawn(): la linea "vida" la escribe
         * spawn() y tiene que caer debajo de su titulo, no colgada de la anterior. */
        olvidarAnotados();
        plugin.bitacora().seccion("ABRE " + type.id() + " en " + describe(where));
        plugin.bitacora().anotar(
                "ajustes",
                type.id(),
                "clase " + plugin.registry().classOf(type).name(),
                "vida del menu " + Bitacora.num(plugin.registry().health(type)),
                "extra por jugador x" + Bitacora.num(plugin.settings().healthPerPlayer()),
                "cuentan para la vida " + Fx.playersNear(where, 96).size() + " jugador(es)");

        try {
            fight.spawn();
        } catch (Throwable t) {
            plugin.getLogger().severe("No se pudo crear el jefe de " + type.id() + ": " + t);
            stop(true);
            return;
        }

        event.state(ActiveAnomaly.State.ACTIVA);
        paintArena(type, where);
        plugin.announcer().opened(event);
        plugin.getLogger().info("Anomalía " + type.id() + " abierta en " + describe(where));

        ticker = plugin.getServer().getScheduler().runTaskTimer(
                net.ederus.edm.Module.dueno(plugin), this::tick, 1L, 1L);
    }

    private static String describe(Location l) {
        return (l.getWorld() == null ? "?" : l.getWorld().getName())
                + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    /**
     * Mantiene cargados los trozos de la arena. Sin esto, si todo el mundo muere y se
     * aleja, el jefe se congela a medias y el evento no se cierra nunca.
     */
    private void forceLoad(Location where) {
        if (where.getWorld() == null) return;
        int cx = where.getBlockX() >> 4;
        int cz = where.getBlockZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                try {
                    Chunk c = where.getWorld().getChunkAt(cx + dx, cz + dz);
                    /* Billete de plugin y NO setForceLoaded: el force-loaded se
                     * escribe en level.dat y sobrevive al servidor, asi que una
                     * caida en mitad de una anomalia dejaba nueve trozos de
                     * mundo cargados para siempre y sin nadie que los soltara.
                     * El billete se suelta solo cuando el plugin se descarga. */
                    c.addPluginChunkTicket(net.ederus.edm.Module.dueno(plugin));
                    forced.add(c);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void releaseChunks() {
        for (Chunk c : forced) {
            try {
                c.removePluginChunkTicket(net.ederus.edm.Module.dueno(plugin));
            } catch (Throwable ignored) {
            }
        }
        forced.clear();
    }

    // ----------------------------------------------------------------------- ciclo

    private void tick() {
        ActiveAnomaly event = current;
        if (event == null) return;
        BossFight fight = event.fight();
        if (fight == null) return;

        try {
            fight.tick();
        } catch (Throwable t) {
            plugin.getLogger().warning("Fallo en el tick del jefe: " + t);
        }

        PhaseBars bars = event.bars();
        if (bars != null) {
            bars.update(fight.healthFraction());
            // Recalcular quien ve las barras cuesta un barrido de jugadores;
            // una vez por segundo es de sobra y no se nota al entrar al radio.
            if (fight.ticks() % 20 == 0) {
                bars.refreshViewers(fight.loc(), plugin.settings().participationRadius() + 24);
            }
        }

        if (fight.ticks() % 10 == 0 && plugin.settings().lightPillar()) beacon(event, fight.loc());

        if (event.state() == ActiveAnomaly.State.ACTIVA
                && event.elapsedSeconds() > plugin.settings().timeLimitMinutes() * 60L) {
            plugin.announcer().expired(event);
            stop(true);
        }
    }

    /**
     * El pilar de luz sobre el jefe, del color de la anomalia.
     *
     * Las coordenadas del anuncio te dejan en la zona, pero encontrar al jefe entre
     * arboles seguia siendo cosa de suerte. Esto se ve desde lejos y de noche marca
     * el sitio como una baliza.
     */
    private void beacon(ActiveAnomaly event, Location where) {
        // Sin color de brillo no hay pilar: esa anomalia quiere pillarte por sorpresa.
        if (event.type().glowColor() == null) return;
        Location base = Fx.ground(where, 4);
        var dust = Compat.dust(event.type().glowColor().value(), 2.2f);
        // Pocas particulas y grandes: cada una forzada es un paquete por jugador a la
        // redonda, asi que el pilar se dibuja espaciado en vez de denso.
        for (double y = 0.5; y <= 40; y += 2.5) {
            Compat.spawnForced(where.getWorld(), Compat.DUST, base.clone().add(0, y, 0), 1,
                    0.14, 0.2, 0.14, 0, dust);
        }
        Fx.ring(base.clone().add(0, 0.3, 0), 3.0, 12, l ->
                Compat.spawnForced(where.getWorld(), Compat.DUST, Fx.ground(l, 3).add(0, 0.2, 0), 1,
                        0, 0, 0, 0, dust));
    }

    // ----------------------------------------------------------------------- arena

    /** La zona que se pinto al abrir, para devolverla al cerrar. */
    private String paintedArena;

    /**
     * Si la anomalia abre dentro de la zona de la arena, la arena entera toma su
     * clima (Alba -> Celestial). Fuera de la arena no se toca el mundo.
     */
    private void paintArena(AnomalyType type, Location where) {
        String zona = plugin.settings().arenaZone();
        String clima = plugin.settings().arenaClimate(type.id());
        var biomas = net.ederus.edm.biomas.BiomasPlugin.activo();
        if (zona.isBlank() || clima.isBlank() || biomas == null) return;
        var z = biomas.zona(zona);
        if (z == null) {
            plugin.getLogger().warning("La arena apunta a la zona '" + zona + "', que no existe en Lethal Biomes.");
            return;
        }
        if (!z.contiene(where)) return;
        String error = biomas.pintar(zona, clima, null);
        if (error != null) {
            plugin.getLogger().warning("No se pudo poner el clima de la arena: " + error);
            return;
        }
        paintedArena = zona;
    }

    private void restoreArena() {
        String zona = paintedArena;
        paintedArena = null;
        var biomas = net.ederus.edm.biomas.BiomasPlugin.activo();
        if (zona == null || biomas == null) return;
        String error = biomas.pintar(zona, null, null);
        if (error != null) plugin.getLogger().warning("No se pudo devolver el clima de la arena: " + error);
    }

    // ---------------------------------------------------------------------- cierre

    /** Cierra el evento y borra todo lo que haya quedado. */
    public void stop(boolean silent) {
        ActiveAnomaly event = current;
        logClose(event, "cerrada sin morir (a mano, por tiempo o al fallar)");
        olvidarAnotados();
        current = null;
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        releaseChunks();
        plugin.anchors().clear();
        restoreArena();
        if (event == null) return;
        event.state(ActiveAnomaly.State.CERRANDO);
        if (event.bars() != null) event.bars().removeAll();
        if (event.fight() != null) event.fight().cleanup();
        if (!silent) {
            plugin.getServer().sendMessage(Component.text("✦ La anomalía se cerro.", NamedTextColor.GRAY));
        }
    }

    /** Cierre limpio del plugin: no deja jefes ni decoracion en el mundo. */
    public void shutdown() {
        if (autoTask != null) {
            autoTask.cancel();
            autoTask = null;
        }
        stop(true);
        Anim.cancelAll();
    }

    // ------------------------------------------------------------------ automatico

    public void restartScheduler() {
        if (autoTask != null) {
            autoTask.cancel();
            autoTask = null;
        }
        if (!plugin.settings().autoEnabled()) return;
        long period = plugin.settings().autoIntervalMinutes() * 60L * 20L;
        autoTask = plugin.getServer().getScheduler().runTaskTimer(
                net.ederus.edm.Module.dueno(plugin), this::autoTrigger, period, period);
        plugin.getLogger().info("Anomalías automaticas cada " + plugin.settings().autoIntervalMinutes() + " min.");
    }

    private void autoTrigger() {
        if (active() || searching) return;
        int online = 0;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (Fx.isFightable(p)) online++;
        }
        if (online < plugin.settings().autoMinPlayers()) return;

        String choice = plugin.settings().autoAnomaly();
        AnomalyType type = "aleatoria".equalsIgnoreCase(choice)
                ? plugin.registry().randomEnabled()
                : plugin.registry().get(choice);
        if (type == null || !plugin.registry().isEnabled(type)) return;
        start(type, ok -> {
            if (!ok) plugin.getLogger().info("Anomalía automática descartada: no habia sitio libre.");
        });
    }

    // -------------------------------------------------------------------- escuchas

    /**
     * Con el jefe no se comercia ni se juega.
     *
     * El Piromante es un aldeano de verdad, asi que el clic derecho le abria la mesa
     * de trueque como a cualquier vendedor del pueblo. Lo mismo valdria para ponerle
     * una correa, montarlo o esquilarlo segun el cuerpo que use cada anomalia: si es
     * nuestro, el clic derecho no hace nada.
     *
     * Va en LOWEST para llegar antes que el plugin que abra la interfaz.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (isOurFighter(e.getRightClicked())) e.setCancelled(true);
    }

    /** El jefe, el maniqui que lo representa o uno de sus esbirros. */
    private boolean isOurFighter(Entity entity) {
        ActiveAnomaly event = current;
        if (event == null || event.fight() == null || entity == null) return false;
        LivingEntity boss = event.fight().entity();
        LivingEntity shell = event.fight().shell();
        return entity.equals(boss) || entity.equals(shell) || Tags.isMinion(entity);
    }

    /**
     * Va en HIGHEST y SIN ignorar lo cancelado a proposito.
     *
     * WorldGuard trata pegarle a un aldeano dentro de una region cerrada como si fuera
     * construir, y el Piromante ES un aldeano: en el coliseo se volvia intocable. Como
     * las protecciones cancelan antes (NORMAL/HIGH), aqui llegamos despues y devolvemos
     * el golpe del jugador contra el jefe, su maniqui o un esbirro. Solo eso: el resto
     * de la region sigue protegida igual, y se apaga con combate.saltarse-protecciones.
     *
     * Tiene que ser este mismo metodo, y no otro aparte, porque el merito para el botin
     * se apunta aqui: si el desbloqueo viviera en un listener posterior, el golpe
     * entraria pero no contaria para nadie.
     */
    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();

        if (e.isCancelled()) {
            if (!plugin.settings().bypassProtections()) return;
            Player desbloqueado = attacker(e.getDamager());
            if (desbloqueado == null || !isOurFighter(victim)) return;
            e.setCancelled(false);
            /* Dentro de una region cerrada esto pasa en CADA golpe: se anota solo el
             * primero de cada jugador, que es lo que dice que la region lo bloqueaba. */
            if (proteccionAnotada.add(desbloqueado.getUniqueId())) {
                plugin.bitacora().anotar("proteccion", "golpe devuelto a " + desbloqueado.getName(),
                        "sobre " + victim.getType(), "en " + describe(victim.getLocation()),
                        "solo se anota el primero");
            }
        }

        // Los objetivos destructibles no usan la vida vanilla: cada golpe cuenta uno.
        if (plugin.anchors().isAnchor(victim)) {
            e.setCancelled(true);
            if (attacker(e.getDamager()) != null) plugin.anchors().hit(victim);
            return;
        }

        ActiveAnomaly event = current;
        if (event == null || event.fight() == null) return;
        LivingEntity boss = event.fight().entity();

        // Los jefes con cuerpo de persona pelean con un mob invisible y se ven con un
        // maniqui encima. Quien golpea le pega al maniqui, que es lo que ve; el golpe
        // se le pasa al jefe para que todo lo demas (barra, merito, fases) funcione.
        LivingEntity shell = event.fight().shell();
        if (shell != null && victim.equals(shell)) {
            e.setCancelled(true);
            if (boss == null || !boss.isValid()) return;
            if (Tags.isOurs(e.getDamager())) return;
            try {
                /* Siempre CON quien pega. Sin fuente, el golpe llegaba al jefe como
                 * dano CUSTOM y se saltaba el reescalado de vida: una mascota le
                 * quitaba a Rabby o a Alba lo que no le quita ningun jugador. */
                boss.damage(e.getDamage(), e.getDamager());
            } catch (Throwable ignored) {
            }
            return;
        }

        if (boss != null && victim.equals(boss)) {
            if (Tags.isMinion(e.getDamager())) {
                e.setCancelled(true);
                return;
            }
            Player p = attacker(e.getDamager());
            if (p == null) return;
            // El merito se apunta con el dano "real" que hizo el jugador, antes de
            // reescalarlo al tope de vida de la entidad: asi el ranking no depende
            // de un detalle interno del plugin. El reescalado va en onFinalDamage.
            event.addDamage(p, e.getFinalDamage());
            try {
                event.fight().onDamaged(p, e.getFinalDamage());
            } catch (Throwable t) {
                plugin.getLogger().warning("Fallo al reaccionar al daño recibido: " + t);
            }
            return;
        }

        // Le han pegado a un esbirro: el jefe puede querer enterarse. Lo usa el jefe
        // doble para apuntar el dano hecho al segundo gemelo, que si no no contaria.
        if (Tags.isMinion(victim) && victim instanceof LivingEntity hurtMinion) {
            Player p = attacker(e.getDamager());
            if (p != null) {
                event.addDamage(p, e.getFinalDamage());
                try {
                    event.fight().onMinionDamaged(hurtMinion, p, e.getFinalDamage());
                } catch (Throwable t) {
                    plugin.getLogger().warning("Fallo al reaccionar al daño de un esbirro: " + t);
                }
            }
        }

        // El jefe o uno de los suyos le ha pegado a alguien: hay anomalias que
        // reaccionan a eso, como el Conejo, que se multiplica en cada mordisco.
        if (victim instanceof Player hurt
                && (e.getDamager().equals(boss) || Tags.isMinion(e.getDamager()))) {
            try {
                event.fight().onDealtDamage(hurt, e.getDamager());
            } catch (Throwable t) {
                plugin.getLogger().warning("Fallo al reaccionar a un golpe del jefe: " + t);
            }
        }

        // Ni el jefe pega a sus esbirros ni ellos a el.
        if (boss != null && Tags.isMinion(victim) && e.getDamager().equals(boss)) {
            e.setCancelled(true);
            return;
        }
    }

    /**
     * El reescalado de vida, para CUALQUIER dano que le llegue al jefe.
     *
     * La entidad topa en 1024 de vida y el resto de la vida configurada se cobra
     * reduciendo cada golpe. Antes eso solo se hacia con golpes de jugador: lobos,
     * golems, criaturas invocadas por cuernos, pociones, espinas o el dano "suelto"
     * de otros plugins entraban enteros y un perro le quitaba al jefe cientos de
     * veces lo que un jugador con equipo. De ahi las anomalias que "morian de la nada".
     *
     * Va en MONITOR a proposito: los encantamientos que suman dano plano lo hacen en
     * HIGHEST, y si se reescala antes, ese extra entra sin reducir.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onFinalDamage(EntityDamageEvent e) {
        ActiveAnomaly event = current;
        if (event == null || event.fight() == null) return;
        BossFight fight = event.fight();
        LivingEntity boss = fight.entity();
        Entity victim = e.getEntity();
        Entity damager = e instanceof EntityDamageByEntityEvent by ? by.getDamager() : null;

        if (boss != null && victim.equals(boss)) {
            double factor = fight.damageScale() * fight.incomingDamageMultiplier(damager);
            if (factor != 1.0) e.setDamage(e.getDamage() * factor);
            clampToSurvivalFloor(e, fight, boss);
            logHit(e, event, fight, boss, damager);
            return;
        }
        // Un segundo cuerpo del jefe (KAM) tiene la misma vida reescalada.
        if (victim instanceof LivingEntity other) {
            double factor = fight.partDamageScale(other);
            if (factor != 1.0) e.setDamage(e.getDamage() * factor);
        }
    }

    /**
     * El resumen del cierre: como acabo, cuanto duro y quien hizo que.
     *
     * Es la linea que se mira primero al abrir el fichero, asi que lleva el reparto
     * entero aunque sea larga: sin ella habria que sumar los golpes a mano.
     */
    private void logClose(ActiveAnomaly event, String como) {
        if (event == null || !plugin.bitacora().activa()) return;
        /* Una muerte se anota en defeat() y el barrido de despues puede pasar por
         * stop(): la misma pelea no se resume dos veces. */
        if (event == cierreAnotado) return;
        cierreAnotado = event;
        BossFight fight = event.fight();
        LivingEntity boss = fight == null ? null : fight.entity();
        String vida = boss == null || !boss.isValid() ? "sin cuerpo"
                : Bitacora.num(boss.getHealth()) + "/"
                        + Bitacora.num(Compat.getAttribute(boss, "max_health", boss.getHealth()));
        plugin.bitacora().anotar(
                "cierra",
                event.typeId(),
                como,
                "duro " + event.elapsedSeconds() + "s",
                "vida al final " + vida,
                "fase " + (fight == null ? "?" : String.valueOf(fight.phase())),
                event.participants() + " participante(s)");
        event.damage().entrySet().stream()
                .sorted(java.util.Map.Entry.<UUID, Double>comparingByValue().reversed())
                .forEach(en -> plugin.bitacora().anotar(
                        "  reparto", event.nameOf(en.getKey()), Bitacora.num(en.getValue()) + " de dano"));
    }

    /* Quien ya tiene su primer golpe anotado. Se vacia con cada anomalia. */
    private final java.util.Set<UUID> primerGolpeAnotado = new java.util.HashSet<>();
    /* Quien ya tiene anotado que una proteccion le tumbaba los golpes. */
    private final java.util.Set<UUID> proteccionAnotada = new java.util.HashSet<>();
    /* La ultima pelea cuyo cierre ya se escribio. */
    private ActiveAnomaly cierreAnotado;

    /** Deja las listas de "ya anotado" limpias para la pelea siguiente. */
    private void olvidarAnotados() {
        primerGolpeAnotado.clear();
        proteccionAnotada.clear();
    }

    /**
     * Anota en la bitacora los golpes que EXPLICAN algo, no todos.
     *
     * Una pelea de veinte personas son miles de impactos y un fichero ilegible. Se
     * guardan tres clases de golpe y se callan los demas:
     *   - los que NO vienen de un jugador (fuego, caida, ahogo, la mascota de
     *     alguien, otro plugin): son los que explican un jefe que pierde vida sin
     *     que nadie lo toque,
     *   - los que se llevan de una el 5% de la barra o mas: los "murio de un golpe",
     *   - el PRIMERO de cada jugador, para saber quien entro a la pelea y cuando.
     * El resto del reparto queda igualmente en el resumen del cierre.
     */
    private void logHit(EntityDamageEvent e, ActiveAnomaly event, BossFight fight,
                        LivingEntity boss, Entity damager) {
        if (!plugin.bitacora().activa()) return;
        double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
        double aplicado = e.getFinalDamage();
        double trozo = max <= 0 ? 0 : aplicado / max;

        Player p = damager == null ? null : attacker(damager);
        boolean primero = p != null && primerGolpeAnotado.add(p.getUniqueId());
        boolean gordo = trozo >= 0.05;
        if (p != null && !primero && !gordo) return;

        String quien = p != null ? p.getName()
                : damager != null ? "entidad " + damager.getType() + tagDe(damager)
                : "sin entidad";
        plugin.bitacora().anotar(
                "golpe",
                event.typeId(),
                quien,
                "causa " + e.getCause(),
                "antes de armadura " + Bitacora.num(e.getDamage()),
                "aplicado " + Bitacora.num(aplicado),
                "vida " + Bitacora.num(boss.getHealth()) + " -> "
                        + Bitacora.num(Math.max(0, boss.getHealth() - aplicado)),
                "barra -" + Math.round(trozo * 1000) / 10.0 + "%",
                p != null && primero && !gordo ? "primer golpe suyo" : (gordo ? "GOLPE GORDO" : "-"));
    }

    /** Si la entidad es nuestra, decirlo: cambia por completo como se lee la linea. */
    private static String tagDe(Entity entity) {
        if (Tags.isMinion(entity)) return " (esbirro nuestro)";
        if (Tags.isOurs(entity)) return " (de la anomalia)";
        return "";
    }

    /**
     * Recorta el golpe para que el jefe no baje del suelo que declara su fase.
     *
     * Con equipo bueno un solo mandoble se lleva media barra, y eso saltaba fases
     * guionizadas enteras: el jefe se moria sin llegar a ejecutarlas. Aqui el golpe se
     * queda justo en el umbral, la transicion salta en el tick siguiente y a partir de
     * ahi el suelo baja solo.
     */
    private void clampToSurvivalFloor(EntityDamageEvent e, BossFight fight, LivingEntity boss) {
        double floor = fight.survivalFloor();
        if (floor <= 0) return;
        double max = Compat.getAttribute(boss, "max_health", boss.getHealth());
        double limit = max * floor;
        double allowed = boss.getHealth() - limit;
        if (allowed <= 0) {
            e.setCancelled(true);
            return;
        }
        if (e.getDamage() > allowed) e.setDamage(allowed);
    }

    /**
     * Las anomalias que construyen algo de verdad deciden que se puede romper y que no.
     * Fuera de eso el plugin no toca el minado de nadie.
     */
    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent e) {
        ActiveAnomaly event = current;
        if (event == null || event.fight() == null) return;
        try {
            if (event.fight().onBlockBroken(e.getBlock(), e.getPlayer())) e.setCancelled(true);
        } catch (Throwable t) {
            plugin.getLogger().warning("Fallo al reaccionar a un bloque roto: " + t);
        }
    }

    /** El jugador detras del golpe: el que pega, el que disparo o el dueno de la mascota. */
    private Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
            if (src instanceof Entity shooter) damager = shooter;
        }
        if (damager instanceof org.bukkit.entity.Tameable pet && pet.getOwner() instanceof Player owner) {
            return owner;
        }
        return null;
    }

    /**
     * Un jefe no se muere de lluvia, de una caida ni de asfixia.
     *
     * Sin esto el Enderman de Darkness se derrite en cuanto llueve y cualquiera de
     * ellos puede morirse solo por caerse de una animacion, dejando el evento a medias.
     */
    @EventHandler(ignoreCancelled = true)
    public void onEnvironmentalDamage(EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent) return;
        ActiveAnomaly event = current;
        if (event == null || event.fight() == null) return;
        LivingEntity boss = event.fight().entity();
        if (boss == null || !e.getEntity().equals(boss)) {
            if (!Tags.isOurs(e.getEntity())) return;
        }
        switch (e.getCause()) {
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE, MAGIC, THORNS, CUSTOM -> {
            }
            default -> e.setCancelled(true);
        }
    }

    /**
     * Ninguna criatura del plugin toca un solo bloque del mundo.
     *
     * Los endermans recogen y colocan bloques por su cuenta, y Darkness es un enderman:
     * sin esto se dedicaria a desmontar el mapa mientras pelea. Vale para cualquier
     * entidad nuestra, asi que tambien cubre lo que venga despues.
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (Tags.isOurs(e.getEntity())) e.setCancelled(true);
    }

    /** Los esbirros no se pelean entre ellos ni con el jefe. */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (!Tags.isOurs(e.getEntity())) return;
        Entity target = e.getTarget();
        if (target != null && Tags.isOurs(target)) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        ActiveAnomaly event = current;
        if (event == null || event.fight() == null) return;
        LivingEntity boss = event.fight().entity();

        if (Tags.isMinion(e.getEntity())) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            try {
                event.fight().onMinionDeath(e.getEntity());
            } catch (Throwable t) {
                plugin.getLogger().warning("Fallo al reaccionar a la muerte de un esbirro: " + t);
            }
            return;
        }
        if (boss == null || !e.getEntity().equals(boss)) return;

        // El botin del jefe lo decide la tabla, nunca la tabla vanilla del esqueleto.
        e.getDrops().clear();
        e.setDroppedExp(0);
        logDeath(event, boss);
        defeat(event);
    }

    /** Que lo mato de verdad: la ultima causa de dano que vio el servidor. */
    private void logDeath(ActiveAnomaly event, LivingEntity boss) {
        if (!plugin.bitacora().activa()) return;
        EntityDamageEvent last = boss.getLastDamageCause();
        Entity killer = last instanceof EntityDamageByEntityEvent by ? by.getDamager() : null;
        Player p = killer == null ? null : attacker(killer);
        plugin.bitacora().anotar(
                "muerte",
                event.typeId(),
                "ultima causa " + (last == null ? "desconocida" : last.getCause()),
                "de " + (p != null ? p.getName()
                        : killer != null ? "entidad " + killer.getType() + tagDe(killer) : "nadie"),
                "ultimo golpe " + (last == null ? "?" : Bitacora.num(last.getFinalDamage())),
                "segundo " + event.elapsedSeconds());
    }

    private void defeat(ActiveAnomaly event) {
        logClose(event, "jefe muerto");
        olvidarAnotados();
        event.state(ActiveAnomaly.State.CERRANDO);
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        Location where = event.fight().loc();

        try {
            event.fight().onDeath();
        } catch (Throwable t) {
            plugin.getLogger().warning("Fallo en la animación de muerte: " + t);
        }

        // El destello final va SIEMPRE, tenga el jefe animacion propia o no: es lo
        // que garantiza que ninguna anomalia se apague sin que se note.
        try {
            event.fight().deathFlash();
        } catch (Throwable t) {
            plugin.getLogger().warning("Fallo en el destello de muerte: " + t);
        }

        pagarMobcoins(event);
        List<String> report = plugin.drops().award(event.typeId(), event.damage(), where);
        plugin.announcer().defeated(event, report);
        contarParaElRankup(event);
        if (event.bars() != null) event.bars().removeAll();

        // Se deja respirar la animacion de muerte antes de barrer la escena.
        Anim.later(plugin, event.fight().deathAnimationTicks(), () -> {
            if (current == event) {
                current = null;
                releaseChunks();
                plugin.anchors().clear();
                restoreArena();
                event.fight().cleanup();
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ActiveAnomaly event = current;
        if (event != null && event.bars() != null) event.bars().viewers().remove(e.getPlayer());
    }


    /**
     * Reparte las MobCoins que pague esta anomalia entre los que le hicieron dano.
     *
     * El bote va en la config de cada anomalia (anomalias.<id>.mobcoins) y se reparte
     * EN PROPORCION al dano, con un suelo para que el que llego tarde cobre algo. En 0
     * no paga nada por aqui y el botin sigue pudiendo dar monedas por comando, como
     * hasta ahora.
     */
    private void pagarMobcoins(ActiveAnomaly event) {
        int bote = plugin.settings().mobcoins(event.typeId());
        if (bote <= 0 || event.damage().isEmpty()) return;

        double total = event.damage().values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) return;
        double minimo = plugin.settings().mobcoinsMinimoPorJugador();

        for (Map.Entry<UUID, Double> en : event.damage().entrySet()) {
            Player p = plugin.getServer().getPlayer(en.getKey());
            if (p == null || !p.isOnline()) continue;
            long pago = Math.round(Math.max(minimo, bote * (en.getValue() / total)));
            net.ederus.edm.comun.MobCoins.pagar(net.ederus.edm.Module.dueno(plugin), p, pago);
        }
    }

    /**
     * Sube los contadores de ServerVariables que pide el rankup 11-20 (camino PvE):
     * anomalias_derrotadas para todo participante que hizo dano, y anomalias_keeper
     * ademas si el jefe era el Keeper. Van por comando de consola con silent:true
     * para no ensuciar el chat; si algo falla queda en el log sin tocar el botin.
     */
    private void contarParaElRankup(ActiveAnomaly event) {
        boolean keeper = Keeper.ID.equals(event.typeId());
        for (UUID uuid : event.damage().keySet()) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;
            svarAdd("anomalias_derrotadas", p.getName());
            if (keeper) svarAdd("anomalias_keeper", p.getName());
        }
    }

    private void svarAdd(String variable, String jugador) {
        try {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                    "svar add " + variable + " 1 " + jugador + " silent:true");
        } catch (Throwable t) {
            plugin.getLogger().warning("No se pudo subir " + variable + " de " + jugador + ": " + t);
        }
    }

}
