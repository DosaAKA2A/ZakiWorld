package net.ederus.edm.superbeacon;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * El clan de un jugador, para los Super Beacon que benefician a un clan.
 *
 * UltimateClans no tiene API que podamos compilar, asi que se lee por PlaceholderAPI (el
 * marcador va en el config: clan.placeholder), por reflexion, igual que Poder lee el rango.
 * Sin PlaceholderAPI no hay clanes: los tipos "clan" se comportan como "dueno".
 *
 * El resultado se limpia de colores y se compara sin mayusculas. Lo que significa "sin
 * clan" (vacio, "Sin clan", "N/A"...) va en clan.sin-clan, y ademas cualquier respuesta
 * que todavia lleve un % (el marcador sin resolver: falta la expansion) cuenta como sin
 * clan, para que un placeholder roto no meta a todo el servidor en el mismo "clan".
 *
 * Se guarda ~30 s por jugador: el ciclo de efectos pregunta cada 2 s por cada jugador
 * cerca de una baliza de clan, y PlaceholderAPI no es gratis. Solo hilo principal.
 */
final class Clanes {

    static final long VIDA_MS = 30_000L;

    private record Entrada(String clan, long hasta) {
    }

    private final Map<UUID, Entrada> cache = new HashMap<>();
    private String placeholder = "%uclans_tag_color%";
    private Set<String> sinClan = Set.of("", "sin clan", "n/a", "-");

    private Method setPlaceholders;
    private boolean buscado;
    private boolean avisado;
    private final java.util.logging.Logger log;

    Clanes(java.util.logging.Logger log) {
        this.log = log;
    }

    void configurar(String placeholder, List<String> sinClan) {
        this.placeholder = placeholder == null || placeholder.isBlank() ? "%uclans_tag_color%" : placeholder.trim();
        Set<String> s = new HashSet<>();
        for (String v : sinClan) s.add(limpiar(v).toLowerCase(Locale.ROOT));
        s.add("");
        this.sinClan = s;
        cache.clear();
    }

    /** Hay PlaceholderAPI en marcha y se encontro su metodo. */
    boolean disponible() {
        return metodo() != null;
    }

    /** El clan de ese jugador (conectado o no), sin colores; null si no tiene o no se sabe. */
    String de(UUID jugador) {
        if (jugador == null) return null;
        long ahora = System.currentTimeMillis();
        Entrada e = cache.get(jugador);
        if (e != null && e.hasta > ahora) return e.clan;
        String clan = leer(jugador);
        cache.put(jugador, new Entrada(clan, ahora + VIDA_MS));
        return clan;
    }

    String de(Player p) {
        return p == null ? null : de(p.getUniqueId());
    }

    void olvidar(UUID jugador) {
        cache.remove(jugador);
    }

    void podar() {
        long ahora = System.currentTimeMillis();
        Iterator<Entrada> it = cache.values().iterator();
        while (it.hasNext()) {
            if (it.next().hasta <= ahora) it.remove();
        }
    }

    private String leer(UUID jugador) {
        Method m = metodo();
        if (m == null) return null;
        try {
            Player vivo = Bukkit.getPlayer(jugador);
            OfflinePlayer quien = vivo != null ? vivo : Bukkit.getOfflinePlayer(jugador);
            Object r = m.invoke(null, quien, placeholder);
            String limpio = limpiar(r == null ? "" : r.toString());
            return esSinClan(limpio, sinClan) ? null : limpio;
        } catch (Throwable t) {
            if (!avisado) {
                avisado = true;
                log.warning("[SuperBeacon] No se pudo leer el clan por PlaceholderAPI (" + placeholder + "): " + t
                        + ". Los Super Beacon de clan benefician solo a su dueño hasta que se arregle.");
            }
            return null;
        }
    }

    private Method metodo() {
        if (setPlaceholders != null) return setPlaceholders;
        if (buscado) return null;
        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) return null;
        buscado = true;
        try {
            Class<?> c = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, papi.getClass().getClassLoader());
            setPlaceholders = c.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
        } catch (Throwable t) {
            log.warning("[SuperBeacon] PlaceholderAPI esta, pero no se encontro setPlaceholders: " + t);
        }
        return setPlaceholders;
    }

    /* ------------------------------------------------- reglas puras (selftest) */

    /** Sin colores ni formato: &a, §a, &#RRGGBB, &x&R&R&G&G&B&B, <#RRGGBB> y <etiquetas>. */
    static String limpiar(String bruto) {
        if (bruto == null) return "";
        String t = bruto
                .replaceAll("(?i)[§&]x([§&][0-9a-f]){6}", "")
                .replaceAll("(?i)[§&]#[0-9a-f]{6}", "")
                .replaceAll("(?i)<#[0-9a-f]{6}>", "")
                .replaceAll("(?i)</?(?:[a-z_]+|#[0-9a-f]{6})(?::[^>]*)?>", "")
                .replaceAll("(?i)[§&][0-9a-fk-or]", "");
        return t.trim();
    }

    /** Vacio, en la lista de "sin clan" o con un marcador sin resolver. */
    static boolean esSinClan(String limpio, Collection<String> sinClan) {
        if (limpio == null || limpio.isBlank() || limpio.contains("%")) return true;
        return sinClan.contains(limpio.toLowerCase(Locale.ROOT));
    }

    /** Los dos tienen clan y es el mismo, sin mirar mayusculas. */
    static boolean mismoClan(String a, String b) {
        return a != null && b != null && !a.isBlank() && a.equalsIgnoreCase(b);
    }
}
