package net.ederus.edm.boost;

import java.lang.reflect.Method;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

/**
 * El boost de experiencia de habilidades, enganchado a AuraSkills sin depender de su jar.
 *
 * AuraSkills lanza XpGainEvent cada vez que un jugador va a ganar XP en una habilidad
 * (sus subclases, como EntityXpGainEvent, comparten la misma lista de oyentes). Se
 * registra por nombre con el cargador de clases del propio AuraSkills y se lee por
 * reflexion: getPlayer(), getAmount() y setAmount(double). Sin AuraSkills no se carga
 * nada suyo, enganchar() devuelve false y el tipo sale como "no disponible".
 *
 * Todo lo que falle se traga salvo el primer aviso: si manana AuraSkills cambia un
 * metodo, el boost deja de multiplicar pero la XP normal sigue llegando.
 */
final class Habilidades implements Listener {

    static final String PLUGIN = "AuraSkills";
    static final String EVENTO = "dev.aurelium.auraskills.api.event.skill.XpGainEvent";

    private final BoostPlugin.Fuente fuente;
    private final Logger log;
    private Class<?> clase;
    private Method jugador;
    private Method cantidad;
    private Method ponerCantidad;
    private boolean avisado;

    Habilidades(BoostPlugin.Fuente fuente, Logger log) {
        this.fuente = fuente;
        this.log = log;
    }

    boolean enganchado() {
        return clase != null;
    }

    /** Busca AuraSkills y registra el oyente. true si queda enganchado. */
    @SuppressWarnings("unchecked")
    boolean enganchar(Plugin dueno) {
        if (clase != null) return true;
        Plugin aura;
        try {
            aura = Bukkit.getPluginManager().getPlugin(PLUGIN);
        } catch (Throwable t) {
            return false;                       // sin servidor (autotest): no hay nada que enganchar
        }
        if (aura == null || !aura.isEnabled()) return false;
        try {
            Class<?> c = Class.forName(EVENTO, true, aura.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(c)) return false;
            Method j = c.getMethod("getPlayer");
            Method a = c.getMethod("getAmount");
            Method s = c.getMethod("setAmount", double.class);
            EventExecutor ejecutor = (listener, evento) -> alGanar(evento);
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) c, this,
                    EventPriority.HIGH, ejecutor, dueno, true);
            this.jugador = j;
            this.cantidad = a;
            this.ponerCantidad = s;
            this.clase = c;
            return true;
        } catch (Throwable t) {
            log.warning("[Boost] AuraSkills esta instalado pero no se pudo enganchar su XP (" + t
                    + "); el boost de experiencia de habilidades queda apagado.");
            return false;
        }
    }

    void alGanar(Event evento) {
        if (clase == null || !clase.isInstance(evento)) return;
        try {
            if (!(jugador.invoke(evento) instanceof Player p)) return;
            double mult = fuente.en(p, Tipo.SKILL_EXP);
            if (mult <= 1.0) return;
            double antes = ((Number) cantidad.invoke(evento)).doubleValue();
            if (antes <= 0) return;
            ponerCantidad.invoke(evento, escalar(antes, mult));
        } catch (Throwable t) {
            if (!avisado) {
                avisado = true;
                log.warning("[Boost] Fallo multiplicando la XP de AuraSkills: " + t);
            }
        }
    }

    /** La XP de AuraSkills es decimal: se multiplica tal cual, sin dados. */
    static double escalar(double xp, double mult) {
        if (xp <= 0 || mult <= 1.0) return xp;
        return xp * mult;
    }
}
