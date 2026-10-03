package net.ederus.edm.superbeacon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import net.ederus.edm.comun.Compat;

/**
 * Lo que hace que un Super Beacon de efectos: el ciclo de cada 2 s y el de zona.
 *
 * El ciclo de efectos (cada 40 ticks, hilo principal) recorre a los jugadores
 * conectados, no las balizas: para cada uno mira en el {@link Alcance} las pocas balizas
 * apuntadas en su chunk, se queda con las activas que de verdad le alcanzan y le
 * benefician, junta sus efectos quedandose con el MAYOR de cada grupo y le aplica lo que
 * gana y le quita lo que dejo de recibir. Nada por tick, nada O(balizas x jugadores), y un
 * jugador lejos de toda baliza cuesta una busqueda en un mapa.
 *
 * El ciclo de zona (cada segundo) recorre solo las balizas con efectos de zona o con
 * particula, y solo si su chunk esta cargado.
 *
 * Lo que recibe cada jugador se guarda en mapas concurrentes con valores que no se tocan
 * (se sustituyen): los placeholders lo leen desde fuera del hilo principal.
 */
final class Motor {

    private final SuperBeaconPlugin plugin;
    private final Alcance alcance = new Alcance();
    /** Las que tienen algo que hacer cada segundo. Se sustituye entera al reindexar. */
    private volatile List<Baliza> conZona = List.of();

    private final Map<UUID, Map<String, Efecto>> recibidos = new ConcurrentHashMap<>();
    private final Map<UUID, String> buffs = new ConcurrentHashMap<>();
    private boolean avisadoFallo;

    Motor(SuperBeaconPlugin plugin) {
        this.plugin = plugin;
    }

    /* ================================================================ indices */

    /**
     * Rehace los indices. Se llama al colocar, recoger, elegir efectos, vencer y recargar:
     * cosas que pasan pocas veces. Con cientos de balizas cuesta menos de un milisegundo.
     */
    void reindexar() {
        alcance.limpiar();
        List<Baliza> zona = new ArrayList<>();
        for (Baliza b : plugin.registro().todas()) {
            TipoBaliza t = plugin.tipo(b.tipo);
            if (t == null) continue;
            alcance.anadir(b, t.radio);
            if (t.particula != null || t.conZona()) zona.add(b);
        }
        conZona = zona;
        for (ClaseEfecto c : plugin.clases()) {
            try {
                c.reindexar(plugin.registro().todas());
            } catch (Throwable t) {
                plugin.getLogger().warning("[SuperBeacon] La clase " + c.id() + " fallo al reindexar: " + t);
            }
        }
    }

    /** Los efectos en marcha de esa baliza (su eleccion dentro del tipo), cacheados en ella. */
    List<Efecto> activos(Baliza b, TipoBaliza t) {
        if (b.cacheTipo != t || b.cacheActivos == null) {
            b.cacheActivos = t.activos(b.elegidos);
            b.cacheTipo = t;
        }
        return b.cacheActivos;
    }

    /* ========================================================== ciclo de efectos */

    void ciclo() {
        long ahora = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                cicloDe(p, ahora);
            } catch (Throwable t) {
                if (!avisadoFallo) {
                    avisadoFallo = true;
                    plugin.getLogger().warning("[SuperBeacon] Fallo aplicando efectos a " + p.getName() + ": " + t);
                }
            }
        }
    }

    private void cicloDe(Player p, long ahora) {
        if (p.isDead()) return;
        UUID id = p.getUniqueId();
        Map<String, Efecto> antes = recibidos.get(id);
        Map<String, Efecto> ahoraRecibe = mejoresPara(p, ahora);
        if (ahoraRecibe.isEmpty() && antes == null) return;

        for (Efecto e : ahoraRecibe.values()) e.clase().aplicar(p, e);
        if (antes != null) {
            for (Map.Entry<String, Efecto> v : antes.entrySet()) {
                if (!ahoraRecibe.containsKey(v.getKey())) v.getValue().clase().quitar(p, v.getValue());
            }
        }
        if (ahoraRecibe.isEmpty()) {
            recibidos.remove(id);
            buffs.remove(id);
        } else {
            recibidos.put(id, ahoraRecibe);
            buffs.put(id, texto(ahoraRecibe));
        }
    }

    /**
     * Lo que gana ese jugador, donde esta, de todas las balizas que le alcanzan: un efecto
     * por grupo, el de mas fuerza. Vacio si ninguna.
     */
    Map<String, Efecto> mejoresPara(Player p, long ahora) {
        Location l = p.getLocation();
        World w = l.getWorld();
        if (w == null) return Map.of();
        List<Baliza> cerca = alcance.en(w.getName(), l.getBlockX(), l.getBlockZ());
        if (cerca.isEmpty()) return Map.of();
        Map<String, Efecto> mejores = null;
        for (Baliza b : cerca) {
            TipoBaliza t = plugin.tipo(b.tipo);
            if (t == null || b.vencida(ahora) || !b.dentro(l.getX(), l.getY(), l.getZ(), t.radio)) continue;
            if (!recibe(b, t, p)) continue;
            for (Efecto e : activos(b, t)) {
                if (!e.clase().porJugador() || e.falta() != null) continue;
                if (mejores == null) mejores = new LinkedHashMap<>();
                Efecto.fusionar(mejores, e);
            }
        }
        return mejores == null ? Map.of() : Collections.unmodifiableMap(mejores);
    }

    /* =========================================================== a quien beneficia */

    /** Si esa baliza le da sus efectos a ese jugador (dueño, clan o todos). */
    boolean recibe(Baliza b, TipoBaliza t, Player p) {
        UUID id = p.getUniqueId();
        if (t.beneficia != TipoBaliza.Beneficia.CLAN || b.esDe(id)) {
            return recibe(t.beneficia, b.dueno, id, null, null);
        }
        return recibe(t.beneficia, b.dueno, id, clanDe(b), plugin.clanes().de(id));
    }

    /**
     * La regla, sin estado, para el selftest. El dueño recibe siempre. "clan": los que
     * tienen el mismo clan que la baliza; sin clan (o sin PlaceholderAPI, que da null) no
     * entra nadie mas, asi que se comporta como "dueno".
     */
    static boolean recibe(TipoBaliza.Beneficia modo, UUID dueno, UUID jugador, String clanBaliza,
                          String clanJugador) {
        if (dueno != null && dueno.equals(jugador)) return true;
        return switch (modo) {
            case TODOS -> true;
            case DUENO -> false;
            case CLAN -> Clanes.mismoClan(clanBaliza, clanJugador);
        };
    }

    /**
     * El clan que manda en la baliza: el fijado en el give si lo hay; si no, el clan ACTUAL de
     * su dueño. Con el dueño conectado se pregunta (PlaceholderAPI, con la cache de 30 s de
     * Clanes) y se apunta en la baliza; desconectado se usa lo ultimo apuntado, porque el
     * placeholder de clan no siempre responde por un jugador que no esta.
     */
    String clanDe(Baliza b) {
        if (b.clan != null || b.dueno == null) return clanBeneficiario(b.clan, false, null, b.clanDueno);
        boolean conectado = Bukkit.getPlayer(b.dueno) != null;
        String clan = clanBeneficiario(null, conectado, conectado ? plugin.clanes().de(b.dueno) : null, b.clanDueno);
        if (conectado && !java.util.Objects.equals(clan, b.clanDueno)) {
            b.clanDueno = clan;
            plugin.registro().marcar();
        }
        return clan;
    }

    /** La regla, sin estado, para el selftest: el fijado manda; si no, el actual o, sin conexion, la cache. */
    static String clanBeneficiario(String fijado, boolean duenoConectado, String clanActual, String cache) {
        if (fijado != null) return fijado;
        return duenoConectado ? clanActual : cache;
    }

    /* ============================================================ ciclo de zona */

    void zona() {
        long ahora = System.currentTimeMillis();
        for (Baliza b : conZona) {
            if (plugin.registro().porId(b.id) != b) continue;          // se recogio en este mismo segundo
            TipoBaliza t = plugin.tipo(b.tipo);
            if (t == null || b.vencida(ahora)) continue;
            World w = Bukkit.getWorld(b.mundo);
            if (w == null || !w.isChunkLoaded(b.x >> 4, b.z >> 4)) continue;
            for (Efecto e : activos(b, t)) {
                if (e.clase().porJugador() || e.falta() != null) continue;
                try {
                    e.clase().enZona(b, t, e, w, ahora);
                } catch (Throwable x) {
                    if (!avisadoFallo) {
                        avisadoFallo = true;
                        plugin.getLogger().warning("[SuperBeacon] Fallo en " + e.clase().id() + " (" + b.idCorto() + "): " + x);
                    }
                }
            }
            if (t.particula != null && ahora >= b.proximaParticula) {
                b.proximaParticula = ahora + t.particulaCada * 1000L;
                Compat.spawn(w, t.particula, new Location(w, b.x + 0.5, b.y + 1.3, b.z + 0.5),
                        t.particulaCantidad, 0.25, 0.3, 0.25, 0.0);
            }
        }
    }

    /* =============================================================== jugadores */

    /** Lo que recibe ahora (grupo -> efecto). Vacio si nada. Cualquier hilo. */
    Map<String, Efecto> recibidos(UUID jugador) {
        Map<String, Efecto> m = recibidos.get(jugador);
        return m == null ? Map.of() : m;
    }

    /** Los nombres de lo que recibe, separados por coma; "" si nada. Cualquier hilo. */
    String buffs(UUID jugador) {
        return jugador == null ? "" : buffs.getOrDefault(jugador, "");
    }

    private static String texto(Map<String, Efecto> m) {
        StringBuilder sb = new StringBuilder();
        for (Efecto e : m.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.nombrePlano());
        }
        return sb.toString();
    }

    /**
     * Hace como si ya recibiera ese efecto. Lo usa el vuelo: a quien entra con vuelo nuestro
     * apuntado se le siembra, y si el ciclo ve que no esta en ningun alcance, se lo quita
     * con su aviso y su caida lenta como a cualquiera que sale.
     */
    void sembrar(Player p, Efecto e) {
        recibidos.compute(p.getUniqueId(), (k, v) -> {
            Map<String, Efecto> m = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
            m.putIfAbsent(e.grupo(), e);
            return Collections.unmodifiableMap(m);
        });
    }

    /** Se desconecto: cada clase suelta lo suyo y se le olvida. */
    void olvidar(Player p) {
        for (ClaseEfecto c : plugin.clases()) {
            try {
                c.alSalir(p);
            } catch (Throwable t) {
                plugin.getLogger().warning("[SuperBeacon] " + c.id() + " fallo al soltar a " + p.getName() + ": " + t);
            }
        }
        recibidos.remove(p.getUniqueId());
        buffs.remove(p.getUniqueId());
    }

    void parar() {
        for (ClaseEfecto c : plugin.clases()) {
            try {
                c.parar();
            } catch (Throwable t) {
                plugin.getLogger().warning("[SuperBeacon] " + c.id() + " fallo al parar: " + t);
            }
        }
        recibidos.clear();
        buffs.clear();
        alcance.limpiar();
        conZona = List.of();
    }
}
