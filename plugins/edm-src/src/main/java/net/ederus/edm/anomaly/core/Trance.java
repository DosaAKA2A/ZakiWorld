package net.ederus.edm.anomaly.core;

import net.ederus.edm.anomaly.AnomalyPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * EDM 1.85.2 · El TRANCE: unos segundos con la vision del creeper.
 *
 * Esa vision solo existe en espectador con la camara metida en un creeper, asi que el
 * jugador pasa a espectador, su camara se engancha a un creeper invisible que gira
 * alrededor de un punto y, al acabar, vuelve con su modo y a su sitio. Mientras dura no
 * puede hacer nada: ni moverse, ni pegar, ni usar objetos, ni comandos; y nadie le pega.
 *
 * Blindado para que nadie se quede en espectador:
 *   - shift (salir del creeper) o la teletransportacion de espectador se cancelan;
 *   - al desconectarse se le devuelve en el acto (antes de que se guarden sus datos);
 *   - si el servidor se cae, trances.yml guarda su modo y su sitio y se le devuelven
 *     al volver a entrar;
 *   - al cerrar la pelea (cleanup) o apagar el plugin, salen todos.
 * Los de Bedrock (Geyser) no entran: no verian el filtro y solo se quedarian congelados.
 */
public final class Trance implements Listener {

    /** Tras un trance, ese jugador no puede volver a entrar en otro durante esto. */
    public static final long INMUNE_MS = 20_000;
    private static final String TAG_OJO = "edm_trance_ojo";

    private final AnomalyPlugin plugin;
    private final File archivo;
    private final Map<UUID, Estado> activos = new HashMap<>();
    private final Map<UUID, Long> inmuneHasta = new HashMap<>();

    private record Estado(GameMode modo, Location vuelta, Entity ojo, BukkitTask tarea) {
    }

    public Trance(AnomalyPlugin plugin) {
        this.plugin = plugin;
        this.archivo = new File(plugin.getDataFolder(), "trances.yml");
    }

    // ----------------------------------------------------------------- consulta

    public boolean enTrance(Player p) {
        return p != null && activos.containsKey(p.getUniqueId());
    }

    /** Si este jugador puede entrar ahora: peleando, de Java, sin trance ni inmunidad. */
    public boolean puedeEntrar(Player p) {
        if (p == null || !p.isOnline() || p.isDead() || enTrance(p)) return false;
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return false;
        if (esBedrock(p)) return false;
        Long hasta = inmuneHasta.get(p.getUniqueId());
        return hasta == null || System.currentTimeMillis() >= hasta;
    }

    /** Floodgate da a los de Bedrock un UUID que empieza por 00000000-0000-0000. */
    private static boolean esBedrock(Player p) {
        return p.getUniqueId().getMostSignificantBits() == 0L;
    }

    // ------------------------------------------------------------------- entrar

    /**
     * Mete al jugador en trance durante 'ticks', viendo desde un creeper invisible que
     * gira alrededor de 'centro' (radio 7, 4 bloques por encima, mirandolo). 'track' recibe
     * el creeper para que la pelea lo apunte como suyo. Devuelve false si no entro.
     */
    public boolean entrar(Player p, Location centro, int ticks, Consumer<Entity> track) {
        if (!puedeEntrar(p) || centro == null || centro.getWorld() == null) return false;
        World w = centro.getWorld();
        Location vuelta = p.getLocation().clone();
        GameMode modo = p.getGameMode();
        Creeper ojo;
        try {
            ojo = w.spawn(posicion(centro, 0), Creeper.class, c -> {
                c.setInvisible(true);
                c.setInvulnerable(true);
                c.setSilent(true);
                c.setAI(false);
                c.setGravity(false);
                c.setCollidable(false);
                c.setPersistent(false);
                c.setRemoveWhenFarAway(false);
                c.setMaxFuseTicks(Integer.MAX_VALUE);
                c.addScoreboardTag(TAG_OJO);
            });
        } catch (Throwable t) {
            return false;
        }
        if (track != null) track.accept(ojo);
        apuntar(p.getUniqueId(), modo, vuelta);

        p.setGameMode(GameMode.SPECTATOR);
        if (p.getGameMode() != GameMode.SPECTATOR) {
            // Otro plugin no le dejo cambiar de modo: no hay trance.
            ojo.remove();
            olvidar(p.getUniqueId());
            return false;
        }
        p.setSpectatorTarget(ojo);
        p.sendActionBar(Component.text("En trance...", NamedTextColor.GREEN));

        final int[] tick = {0};
        BukkitTask tarea = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            tick[0]++;
            if (!p.isOnline() || !ojo.isValid() || tick[0] > ticks) {
                salir(p.getUniqueId());
                return;
            }
            ojo.teleport(posicion(centro, tick[0]));
            // Si algo le solto la camara (o la recolocacion del creeper), se le vuelve a poner.
            if (p.getGameMode() == GameMode.SPECTATOR && p.getSpectatorTarget() != ojo) p.setSpectatorTarget(ojo);
        }, 1, 1);
        activos.put(p.getUniqueId(), new Estado(modo, vuelta, ojo, tarea));
        return true;
    }

    /** Donde va el creeper en el tick t: una vuelta lenta alrededor del centro, mirandolo. */
    private static Location posicion(Location centro, int t) {
        double ang = t * 0.035;
        Location l = centro.clone().add(Math.cos(ang) * 7, 4, Math.sin(ang) * 7);
        Vector mira = centro.toVector().subtract(l.toVector());
        l.setDirection(mira);
        return l;
    }

    // -------------------------------------------------------------------- salir

    /** Lo saca del trance: su modo, su sitio y 20 s sin poder volver a entrar. */
    public void salir(UUID id) {
        Estado e = activos.remove(id);
        if (e == null) return;
        if (e.tarea() != null) e.tarea().cancel();
        Player p = plugin.getServer().getPlayer(id);
        if (p != null) devolver(p, e.modo(), e.vuelta());
        if (e.ojo() != null && e.ojo().isValid()) e.ojo().remove();
        inmuneHasta.put(id, System.currentTimeMillis() + INMUNE_MS);
        olvidar(id);
    }

    public void salirTodos() {
        for (UUID id : new HashSet<>(activos.keySet())) salir(id);
    }

    private static void devolver(Player p, GameMode modo, Location vuelta) {
        try {
            p.setSpectatorTarget(null);
        } catch (Throwable ignored) {
        }
        p.setGameMode(modo == null ? GameMode.SURVIVAL : modo);
        if (vuelta != null && vuelta.getWorld() != null) p.teleport(vuelta, PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.setFallDistance(0);
    }

    // ------------------------------------------------------------------ blindaje

    /** Ni shift ni el menu de espectador lo sacan del creeper. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSalirDelCreeper(com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent e) {
        if (activos.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTeleportEspectador(PlayerTeleportEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE
                && activos.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onComando(PlayerCommandPreprocessEvent e) {
        if (!activos.containsKey(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(Component.text("Estás en trance: espera a despertar.", NamedTextColor.GREEN));
    }

    /** Al irse se le devuelve ya, antes de que el servidor guarde sus datos. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        salir(e.getPlayer().getUniqueId());
    }

    /** Si el servidor se cayo con el en trance, trances.yml dice como estaba. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        restaurar(e.getPlayer());
    }

    private void restaurar(Player p) {
        YamlConfiguration yml = leer();
        ConfigurationSection s = yml.getConfigurationSection(p.getUniqueId().toString());
        if (s == null) return;
        GameMode modo;
        try {
            modo = GameMode.valueOf(s.getString("modo", "SURVIVAL"));
        } catch (IllegalArgumentException ex) {
            modo = GameMode.SURVIVAL;
        }
        World w = plugin.getServer().getWorld(s.getString("mundo", ""));
        Location vuelta = w == null ? null : new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
        final GameMode m = modo;
        // Un tick despues: en el join otros plugins aun le estan colocando.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) devolver(p, m, vuelta);
        });
        olvidar(p.getUniqueId());
    }

    /** Al arrancar: los ojos que quedaron de un arranque anterior y los que ya estan dentro. */
    public void alArrancar() {
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity en : w.getEntitiesByClass(Creeper.class)) {
                if (en.getScoreboardTags().contains(TAG_OJO)) en.remove();
            }
        }
        for (Player p : plugin.getServer().getOnlinePlayers()) restaurar(p);
    }

    // ------------------------------------------------------------------- disco

    private void apuntar(UUID id, GameMode modo, Location l) {
        YamlConfiguration yml = leer();
        String k = id.toString();
        yml.set(k + ".modo", modo.name());
        yml.set(k + ".mundo", l.getWorld().getName());
        yml.set(k + ".x", l.getX());
        yml.set(k + ".y", l.getY());
        yml.set(k + ".z", l.getZ());
        yml.set(k + ".yaw", l.getYaw());
        yml.set(k + ".pitch", l.getPitch());
        guardar(yml);
    }

    private void olvidar(UUID id) {
        YamlConfiguration yml = leer();
        if (!yml.contains(id.toString())) return;
        yml.set(id.toString(), null);
        guardar(yml);
    }

    private YamlConfiguration leer() {
        return archivo.exists() ? YamlConfiguration.loadConfiguration(archivo) : new YamlConfiguration();
    }

    private void guardar(YamlConfiguration yml) {
        try {
            if (yml.getKeys(false).isEmpty()) {
                if (archivo.exists() && !archivo.delete()) yml.save(archivo);
                return;
            }
            yml.save(archivo);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "No se pudo guardar trances.yml", ex);
        }
    }
}
