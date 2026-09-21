package net.ederus.edm.goditems;

import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

/**
 * El puente con AuraSkills, entero por reflexion.
 *
 * Por reflexion por lo mismo que WorldGuard y PlaceholderAPI en este modulo: no
 * es un jar que este garantizado delante al compilar, y si manana no esta, lo
 * unico que se pierde es el activador SUBIR_NIVEL y la condicion
 * NIVEL_HABILIDAD. Nada mas deja de arrancar.
 *
 * `net.ederus.edm.comun.Poder` ya habla con su API para el poder total; aqui
 * hace falta el nivel de UNA habilidad y el evento de subida, que no expone.
 */
public final class Auras implements Listener {

    private static final String EVENTO = "dev.aurelium.auraskills.api.event.skill.SkillLevelUpEvent";

    private final GodItemsPlugin modulo;
    private boolean enganchado;

    public Auras(GodItemsPlugin modulo) {
        this.modulo = modulo;
    }

    public boolean enganchado() {
        return this.enganchado;
    }

    /** Engancha el evento de subida de nivel si AuraSkills esta delante. */
    @SuppressWarnings("unchecked")
    public void enganchar(Plugin dueno) {
        if (Bukkit.getPluginManager().getPlugin("AuraSkills") == null) return;
        Class<?> clase;
        try {
            clase = Class.forName(EVENTO);
        } catch (ClassNotFoundException e) {
            this.modulo.getLogger().info("[GodItems] AuraSkills esta pero no expone "
                    + EVENTO + "; SUBIR_NIVEL se queda sin escucha.");
            return;
        }
        if (!Event.class.isAssignableFrom(clase)) return;
        EventExecutor ejecutor = (listener, evento) -> subida(evento);
        try {
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) clase, this,
                    EventPriority.MONITOR, ejecutor, dueno, true);
            this.enganchado = true;
            this.modulo.getLogger().info("[GodItems] AuraSkills enganchado: SUBIR_NIVEL funciona.");
        } catch (Throwable t) {
            this.modulo.getLogger().warning("[GodItems] No se pudo enganchar AuraSkills: " + t);
        }
    }

    private void subida(Event evento) {
        Player j = jugadorDe(evento);
        if (j == null) return;
        String habilidad = nombreDe(llamar(evento, "getSkill"));
        /* `habilidad:` acota el activador a una sola. Vacio = cualquiera. */
        this.modulo.dispararEnInventario(j, Activador.SUBIR_NIVEL, evento,
                b -> b.filtro().isBlank() || b.filtro().equalsIgnoreCase(habilidad));
    }

    /* ------------------------------------------------------------ consultas */

    /**
     * El nivel de una habilidad. -1 si AuraSkills no esta o no la conoce, para
     * que la condicion pueda distinguir "no hay dato" de "nivel cero".
     */
    public static int nivel(Player j, String habilidad) {
        if (j == null || habilidad == null || habilidad.isBlank()) return -1;
        try {
            Class<?> api = Class.forName("dev.aurelium.auraskills.api.AuraSkillsApi");
            Object inst = api.getMethod("get").invoke(null);
            Object user = inst.getClass().getMethod("getUser", java.util.UUID.class)
                    .invoke(inst, j.getUniqueId());
            if (user == null) return -1;
            Object skill = habilidadPorNombre(inst, habilidad);
            if (skill == null) return -1;
            for (var m : user.getClass().getMethods()) {
                if (!m.getName().equals("getSkillLevel") || m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].isInstance(skill)) continue;
                Object r = m.invoke(user, skill);
                if (r instanceof Number n) return n.intValue();
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Busca la habilidad en el registro de AuraSkills por su nombre. */
    private static Object habilidadPorNombre(Object api, String nombre) {
        String quiero = nombre.trim().toLowerCase(Locale.ROOT);
        try {
            Object registro = api.getClass().getMethod("getGlobalRegistry").invoke(api);
            Object skills = registro.getClass().getMethod("getSkills").invoke(registro);
            if (skills instanceof Iterable<?> it) {
                for (Object s : it) {
                    if (nombreDe(s).equalsIgnoreCase(quiero)) return s;
                }
            }
        } catch (Throwable ignored) {
            /* Registro distinto en otra version: se prueba por el enum de siempre. */
        }
        try {
            Class<?> skills = Class.forName("dev.aurelium.auraskills.api.skill.Skills");
            for (Object s : skills.getEnumConstants()) {
                if (nombreDe(s).equalsIgnoreCase(quiero)) return s;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /* -------------------------------------------------------------- ayudas */

    private static Player jugadorDe(Object evento) {
        Object v = llamar(evento, "getPlayer");
        if (v instanceof Player p) return p;
        Object user = llamar(evento, "getUser");
        if (user != null) {
            Object p = llamar(user, "getPlayer");
            if (p instanceof Player pp) return pp;
        }
        return null;
    }

    /** El nombre "legible" de una habilidad: `name()`, `getId()` o el toString. */
    private static String nombreDe(Object o) {
        if (o == null) return "";
        for (String m : new String[]{"name", "getId", "getName"}) {
            Object v = llamar(o, m);
            if (v == null) continue;
            String s = String.valueOf(v);
            int dos = s.lastIndexOf(':');
            return dos >= 0 ? s.substring(dos + 1) : s;
        }
        String s = String.valueOf(o);
        int dos = s.lastIndexOf(':');
        return dos >= 0 ? s.substring(dos + 1) : s;
    }

    private static Object llamar(Object o, String metodo) {
        if (o == null) return null;
        try {
            return o.getClass().getMethod(metodo).invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }
}
