package net.ederus.edm.goditems;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * SENTENCIA (EDM 1.82.0, para el Epitafio de Calamity): oscuridad durante unos segundos y, al acabar, un grito
 * y un golpe que vale lo que el marcado recibio mientras tanto (todo el daño, venga de quien venga), por el
 * porcentaje y con su tope.
 *
 * Vive en memoria como Combate: son segundos. Una marca por objetivo: otra SENTENCIA sobre el mismo mientras dura
 * no hace nada (no se acumulan ni se reinician). El golpe final no se cuenta (la marca ya no esta) y no suena si el
 * marcado murio antes.
 */
public final class Sentencias implements Listener {

    private static final class Marca {
        final UUID autor;
        final double porcentaje;
        final double maximo;
        /** El GodItem que la puso y quien, para la bitacora. */
        final String item;
        final String autorNombre;
        double acumulado;

        Marca(UUID autor, String autorNombre, String item, double porcentaje, double maximo) {
            this.autor = autor;
            this.autorNombre = autorNombre;
            this.item = item;
            this.porcentaje = porcentaje;
            this.maximo = maximo;
        }
    }

    private final GodItemsPlugin modulo;
    private final Map<UUID, Marca> marcas = new ConcurrentHashMap<>();

    public Sentencias(GodItemsPlugin modulo) {
        this.modulo = modulo;
    }

    /** Pone la marca; false si ese objetivo ya tiene una en curso. */
    public boolean poner(LivingEntity objetivo, Player autor, int ticks, double porcentaje, double maximo, String grito) {
        return poner(objetivo, autor, ticks, porcentaje, maximo, grito, null);
    }

    /** Igual, apuntando que item la puso (sale en la bitacora al cerrarla). */
    public boolean poner(LivingEntity objetivo, Player autor, int ticks, double porcentaje, double maximo, String grito,
                         String item) {
        if (objetivo == null || objetivo.isDead() || this.marcas.containsKey(objetivo.getUniqueId())) return false;
        int t = Math.max(1, ticks);
        Marca m = new Marca(autor == null ? null : autor.getUniqueId(), autor == null ? "-" : autor.getName(),
                item == null ? "-" : item, Math.max(0, porcentaje), Math.max(0, maximo));
        this.marcas.put(objetivo.getUniqueId(), m);
        // Oscuridad: a un jugador le cierra la vista; a un mob no le hace nada, pero la marca cuenta igual.
        objetivo.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, t + 20, 0, false, false, true));
        Bukkit.getScheduler().runTaskLater(this.modulo.core(), () -> cerrar(objetivo, grito), t);
        return true;
    }

    private void cerrar(LivingEntity objetivo, String grito) {
        Marca m = this.marcas.remove(objetivo.getUniqueId());
        if (m == null) return;
        if (!objetivo.isValid() || objetivo.isDead()) {
            anotar(m, objetivo, 0, 0, objetivo.isDead() ? "murio antes de cerrarse" : "ya no esta");
            return;
        }
        double golpe = m.acumulado * m.porcentaje;
        if (m.maximo > 0) golpe = Math.min(golpe, m.maximo);
        Location l = objetivo.getLocation().add(0, objetivo.getHeight() * 0.6, 0);
        if (grito != null && !grito.isBlank()) {
            if (objetivo instanceof Player p) p.playSound(p.getLocation(), grito, 1.0f, 0.8f);
            objetivo.getWorld().playSound(l, grito, 0.6f, 0.8f);
        }
        objetivo.getWorld().spawnParticle(Particle.SCULK_SOUL, l, 12, 0.35, 0.5, 0.35, 0.02);
        if (golpe <= 0) {
            anotar(m, objetivo, 0, 0, "no recibio nada");
            return;
        }
        Player autor = m.autor == null ? null : Bukkit.getPlayer(m.autor);
        double antes = BitacoraGi.vida(objetivo);
        if (autor != null) objetivo.damage(golpe, autor);
        else objetivo.damage(golpe);
        anotar(m, objetivo, golpe, Math.max(0, antes - BitacoraGi.vida(objetivo)), objetivo.isDead() ? "lo mato" : null);
    }

    /** Al cerrar la marca, una linea en la bitacora de GodItems: lo acumulado, el golpe pedido y lo que quito. */
    private void anotar(Marca m, LivingEntity objetivo, double golpe, double quito, String nota) {
        BitacoraGi b = this.modulo.bitacoraGi();
        if (b == null) return;
        java.util.List<String> l = new java.util.ArrayList<>(java.util.List.of(
                "objetivo " + BitacoraGi.nombre(objetivo),
                "recibio " + net.ederus.edm.comun.Bitacora.num(m.acumulado) + " durante la marca",
                "golpe " + net.ederus.edm.comun.Bitacora.num(golpe) + " (" + Math.round(m.porcentaje * 100) + " %"
                        + (m.maximo > 0 ? ", tope " + net.ederus.edm.comun.Bitacora.num(m.maximo) : "") + ")",
                "quito " + net.ederus.edm.comun.Bitacora.num(quito)));
        if (nota != null) l.add(nota);
        b.sentencia(m.autorNombre, m.item, l);
    }

    /** Lo que recibe el marcado mientras dura, despues de armadura y demas (lo que de verdad le quita). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alRecibir(EntityDamageEvent e) {
        Marca m = this.marcas.get(e.getEntity().getUniqueId());
        if (m != null) m.acumulado += Math.max(0, e.getFinalDamage());
    }

    /** Solo para el autotest: la cuenta del golpe final. */
    static double golpe(double acumulado, double porcentaje, double maximo) {
        double g = Math.max(0, acumulado) * Math.max(0, porcentaje);
        return maximo > 0 ? Math.min(g, maximo) : g;
    }
}
