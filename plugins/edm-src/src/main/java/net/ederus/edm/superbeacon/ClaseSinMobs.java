package net.ederus.edm.superbeacon;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent;

/**
 * sin-mobs: no aparecen monstruos hostiles de forma NATURAL dentro del alcance.
 *
 * Solo la aparicion natural: los spawners, las aparecidas por plugin (Anomaly, esbirros),
 * los jefes, las patrullas y las incursiones siguen igual. Una base protegida, no un
 * escudo contra el contenido del servidor.
 *
 * Las apariciones naturales son de los eventos mas frecuentes del servidor, asi que esto
 * no recorre balizas: tiene su propio indice por mundo y chunk con SOLO las balizas que
 * tienen este efecto activo. Donde no hay ninguna, la consulta es dos busquedas en un
 * mapa que no encuentran nada. Se mira primero en PreCreatureSpawnEvent (antes de crear la
 * entidad, lo barato) y CreatureSpawnEvent queda como red.
 */
final class ClaseSinMobs extends ClaseEfecto implements Listener {

    static final class Paz extends Efecto {
        private final ClaseSinMobs clase;

        Paz(ClaseSinMobs clase, String clave, String nombre, Material icono) {
            super(clave, nombre, icono);
            this.clase = clase;
        }

        @Override
        ClaseEfecto clase() {
            return clase;
        }

        @Override
        String grupo() {
            return "sin-mobs";
        }

        @Override
        double fuerza() {
            return 1;
        }
    }

    private final Alcance zonas = new Alcance();
    /** Si cada tipo de entidad es hostil (Enemy). Se calcula una vez por tipo. */
    private final Map<EntityType, Boolean> hostiles = new EnumMap<>(EntityType.class);

    ClaseSinMobs(SuperBeaconPlugin plugin) {
        super(plugin, "sin-mobs");
    }

    @Override
    Efecto leer(String clave, String nombre, Material icono, ConfigurationSection s, Consumer<String> error) {
        return new Paz(this, clave, nombre, icono);
    }

    @Override
    Material icono() {
        return Material.SHIELD;
    }

    @Override
    boolean porJugador() {
        return false;
    }

    @Override
    String que(Efecto e) {
        return plugin.textos().crudo("detalle-sin-mobs", "No aparecen monstruos de forma natural dentro de su alcance.");
    }

    @Override
    int seccion(Efecto e) {
        return Presentacion.VIDA;
    }

    @Override
    void reindexar(Collection<Baliza> balizas) {
        zonas.limpiar();
        for (Baliza b : balizas) {
            TipoBaliza t = plugin.tipo(b.tipo);
            if (t == null) continue;
            for (Efecto e : plugin.motor().activos(b, t)) {
                if (e.clase() == this) {
                    zonas.anadir(b, t.radio);
                    break;
                }
            }
        }
    }

    boolean hostil(EntityType tipo) {
        return hostiles.computeIfAbsent(tipo, t -> {
            Class<?> c = t.getEntityClass();
            return c != null && Enemy.class.isAssignableFrom(c);
        });
    }

    /** Si en ese punto manda alguna baliza activa con sin-mobs. */
    boolean protegido(Location l) {
        if (zonas.vacio() || l.getWorld() == null) return false;
        List<Baliza> aqui = zonas.en(l.getWorld().getName(), l.getBlockX(), l.getBlockZ());
        if (aqui.isEmpty()) return false;
        long ahora = System.currentTimeMillis();
        for (Baliza b : aqui) {
            TipoBaliza t = plugin.tipo(b.tipo);
            if (t != null && !b.vencida(ahora) && b.dentro(l.getX(), l.getY(), l.getZ(), t.radio)) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void antesDeAparecer(PreCreatureSpawnEvent e) {
        if (e.getReason() != CreatureSpawnEvent.SpawnReason.NATURAL || !hostil(e.getType())) return;
        if (protegido(e.getSpawnLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alAparecer(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL || !(e.getEntity() instanceof Enemy)) return;
        if (protegido(e.getLocation())) e.setCancelled(true);
    }

    @Override
    void parar() {
        zonas.limpiar();
    }
}
