package net.ederus.calamity;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;

import net.ederus.calamity.hardcore.Hardcore;
import net.ederus.calamity.hardcore.Paleta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Calamity 1.8.4 · El cartel de los minijefes con el formato de Calamity (Paleta.minijefe).
 *
 * Lo que se ve encima de un minijefe no es su customName: EDM no le pone ninguno a sus esbirros.
 * Es el cartel de MinionManager, un TextDisplay suelto (marca anomaly:esbirro_holo = id del tipo)
 * con dos lineas: el nombre de la ficha de /esb (MinionType.name(), que en los cinco minijefes se
 * sembro en negrita) y "Nv. X  ❤ vida". EDM lo reescribe entero cada vez que cambia la vida, asi
 * que no basta con cambiarlo una vez: aqui se siguen los carteles de los minijefes de Calamity (los
 * de hardcore.minijefes.tipos y, desde la 1.10, tambien los de hardcore.minijefes.por-bioma:
 * Hardcore.esTipoMinijefe) y, cada vez que EDM los reescribe, se cambia la primera linea por
 * Paleta.minijefe y la segunda se deja como la pinta EDM.
 *
 * Por que no se llega a ver el cartel de EDM: el planificador de Paper corre por orden de creacion
 * las tareas que tocan en el mismo tick, y la de aqui se crea al aparecer un cartel de minijefe,
 * mucho despues de la de carteles de EDM (MinionManager.start). Corre justo detras de ella y el
 * cliente solo recibe el cartel ya repintado. Al invocar (MobsLethal.invocarMinijefe) y al nacer
 * las crias de la Matriarca (en la muerte, dentro del evento de EDM) se repinta en el acto.
 *
 * La ficha no se toca: si Dosa cambia en /esb el nombre, el nombre nuevo sale con este formato.
 */
public final class CartelesMinijefe implements Listener {

    private final CalamityPlugin plugin;
    private final NamespacedKey claveCartel;
    private final Set<UUID> carteles = new HashSet<>();
    private BukkitTask tarea;

    CartelesMinijefe(CalamityPlugin plugin, Plugin anomaly) {
        this.plugin = plugin;
        // La misma clave que MinionManager: new NamespacedKey(<modulo anomaly>, "esbirro_holo").
        this.claveCartel = new NamespacedKey(anomaly, "esbirro_holo");
    }

    /** Un cartel de EDM entra al mundo: la marca con el tipo ya la lleva (se pone en el spawn). */
    @EventHandler
    public void alEntrar(EntityAddToWorldEvent e) {
        if (!(e.getEntity() instanceof TextDisplay d)) return;
        String tipo = d.getPersistentDataContainer().get(claveCartel, PersistentDataType.STRING);
        // 1.10: los minijefes que conoce Calamity (tipos y la tabla de biomas), no solo minijefes.tipos.
        if (tipo == null || !Hardcore.esTipoMinijefe(plugin.getConfig(), tipo)) return;
        carteles.add(d.getUniqueId());
        if (tarea == null) tarea = plugin.getServer().getScheduler().runTaskTimer(plugin, this::repintar, 1L, 1L);
    }

    /** Las crias de la Matriarca nacen dentro del EntityDeathEvent de EDM: se repintan en ese tick. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void alMorir(EntityDeathEvent e) {
        if (!carteles.isEmpty()) repintar();
    }

    /** Repinta los carteles que EDM haya reescrito desde la ultima vez; sin carteles, se para. */
    void repintar() {
        for (Iterator<UUID> it = carteles.iterator(); it.hasNext(); ) {
            Entity e = plugin.getServer().getEntity(it.next());
            if (!(e instanceof TextDisplay d) || !d.isValid()) {
                it.remove();
                continue;
            }
            Component nuevo = repintado(d.text());
            if (nuevo != null) d.text(nuevo);
        }
        if (carteles.isEmpty()) parar();
    }

    void parar() {
        if (tarea != null) tarea.cancel();
        tarea = null;
    }

    /**
     * El cartel de EDM con la primera linea en el formato de los minijefes, o null si no hay que
     * tocarlo: ya esta repintado (la raiz queda vacia), o no tiene la forma que le da EDM (el nombre
     * en la raiz y, colgando de ella, el salto de linea, el nivel y la vida). Esa segunda linea se
     * queda tal cual; solo deja de heredar la negrita de la raiz.
     */
    public static Component repintado(Component deEdm) {
        if (!(deEdm instanceof TextComponent raiz) || raiz.content().isEmpty()) return null;
        List<Component> resto = raiz.children();
        if (resto.isEmpty() || !(resto.get(0) instanceof TextComponent salto) || !"\n".equals(salto.content())) {
            return null;
        }
        return Component.text()
                .append(Paleta.minijefe(raiz.content(), 0))
                .append(resto)
                .build().decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false);
    }
}
