package net.ederus.edm.boost;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

/**
 * El boost de minions, enganchado a LitMinions sin depender de su jar.
 *
 * LitMinions publica un evento por tipo de minion (cosechar, pescar, talar, recoger,
 * matar...) y cada uno lleva lo que el minion acaba de producir. Como no tenemos su
 * API para compilar, los eventos se registran por NOMBRE desde el config y se leen
 * por reflexion: se busca el dueno del minion y la lista de objetos, y se multiplica.
 *
 * Todo lo que falle se traga en silencio salvo el primer aviso por clase: si manana
 * LitMinions cambia un metodo, el boost de minions deja de multiplicar pero NADA mas
 * se rompe, ni el minion ni el resto de boosts.
 */
final class Miniones implements Listener {

    private final BoostPlugin modulo;
    private final Logger log;
    private final java.util.Set<String> avisadas = new java.util.HashSet<>();
    private int enganchados;

    Miniones(BoostPlugin modulo, Logger log) {
        this.modulo = modulo;
        this.log = log;
    }

    int enganchados() {
        return enganchados;
    }

    /** Registra un listener por cada evento de la lista que exista de verdad. */
    @SuppressWarnings("unchecked")
    void enganchar(Plugin dueno, List<String> clases) {
        for (String nombre : clases) {
            Class<?> clase;
            try {
                clase = Class.forName(nombre);
            } catch (ClassNotFoundException e) {
                continue;                       // ese tipo de minion no existe en esta version
            }
            if (!Event.class.isAssignableFrom(clase)) continue;
            EventExecutor ejecutor = (listener, evento) -> multiplicar(evento);
            try {
                Bukkit.getPluginManager().registerEvent((Class<? extends Event>) clase, this,
                        EventPriority.HIGH, ejecutor, dueno, true);
                enganchados++;
            } catch (Throwable t) {
                log.warning("[boost] No se pudo enganchar " + nombre + ": " + t);
            }
        }
    }

    private void multiplicar(Event evento) {
        try {
            UUID dueno = dueno(evento);
            if (dueno == null) return;
            double mult = modulo.servicio().multiplicador(dueno, Tipo.MINIONS);
            if (mult <= 1.0) return;
            if (!aplicar(evento, mult) && avisadas.add(evento.getClass().getName())) {
                log.info("[boost] " + evento.getClass().getSimpleName()
                        + " no expone objetos que multiplicar; ese minion no recibe el boost.");
            }
        } catch (Throwable t) {
            if (avisadas.add(evento.getClass().getName() + "!")) {
                log.warning("[boost] Fallo multiplicando " + evento.getClass().getSimpleName() + ": " + t);
            }
        }
    }

    /* ------------------------------------------------------------ reflexion */

    private static final String[] DEL_EVENTO = {"getPlayer", "getOwner", "getMinion"};
    private static final String[] DEL_MINION = {"getOwner", "getOwnerUUID", "getOwnerUniqueId",
            "getPlayer", "getOwnerId"};

    /** El UUID del dueno del minion, mirando primero en el evento y luego en el minion. */
    private UUID dueno(Object evento) throws Exception {
        for (String m : DEL_EVENTO) {
            Object v = llamar(evento, m);
            UUID id = comoUuid(v);
            if (id != null) return id;
            if (v != null && !(v instanceof UUID)) {
                for (String n : DEL_MINION) {
                    UUID id2 = comoUuid(llamar(v, n));
                    if (id2 != null) return id2;
                }
            }
        }
        return null;
    }

    private static UUID comoUuid(Object v) {
        if (v == null) return null;
        if (v instanceof UUID u) return u;
        if (v instanceof OfflinePlayer p) return p.getUniqueId();
        if (v instanceof Player p) return p.getUniqueId();
        if (v instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private static final String[] OBJETOS = {"getItems", "getDrops", "getLoot", "getItem", "getDrop",
            "getItemStack", "getResult", "getProduct"};

    /** Multiplica lo que el evento devuelva; true si se toco algo. */
    @SuppressWarnings("unchecked")
    private boolean aplicar(Object evento, double mult) throws Exception {
        boolean tocado = false;
        for (String m : OBJETOS) {
            Object v = llamar(evento, m);
            if (v instanceof ItemStack pila) {
                if (pila.getType().isAir()) continue;
                pila.setAmount(Math.min(pila.getMaxStackSize() * 4, Efectos.escalar(pila.getAmount(), mult)));
                devolver(evento, m, pila);
                tocado = true;
            } else if (v instanceof Collection<?> col && !col.isEmpty()) {
                for (Object o : col) {
                    if (!(o instanceof ItemStack pila) || pila.getType().isAir()) continue;
                    pila.setAmount(Math.min(pila.getMaxStackSize() * 4,
                            Efectos.escalar(pila.getAmount(), mult)));
                    tocado = true;
                }
            }
        }
        return tocado;
    }

    private static Object llamar(Object objetivo, String metodo) throws Exception {
        if (objetivo == null) return null;
        Method m = buscar(objetivo.getClass(), metodo);
        if (m == null) return null;
        m.setAccessible(true);
        return m.invoke(objetivo);
    }

    /** Devuelve la pila modificada con su setter, si el evento tiene uno. */
    private static void devolver(Object evento, String getter, ItemStack pila) {
        String setter = "set" + getter.substring(3);
        try {
            Method m = evento.getClass().getMethod(setter, ItemStack.class);
            m.setAccessible(true);
            m.invoke(evento, pila);
        } catch (Throwable ignorado) {
            // Muchos eventos devuelven la pila viva; si no hay setter, ya esta cambiada.
        }
    }

    private static Method buscar(Class<?> clase, String nombre) {
        for (Class<?> c = clase; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(nombre);
            } catch (NoSuchMethodException ignorado) {
                // sigue subiendo
            }
        }
        return null;
    }
}
