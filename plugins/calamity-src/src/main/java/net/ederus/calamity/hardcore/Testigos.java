package net.ederus.calamity.hardcore;

import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M8 · Campana de difuntos y Testigos (DIS M8, ley 2: la campana siempre es real).
 *
 * Cuando alguien muere en Calamity, todo el mundo oye una campanada en su propia posicion:
 * el PvP y las muertes se oyen en todo el mapa, y eso empuja a salir. Quien lo ve caer de
 * cerca (a radio, con linea de vision, y no siendo el asesino) pierde cordura, como mucho
 * una vez por minuto: matar delante de un rival para bajarle la cordura cuesta una muerte y
 * va limitado. Ver alzarse un Eco cerca cuesta lo mismo.
 *
 * Tambien apunta los muertos de las ultimas 24 h: las alucinaciones (M20) los usan para los
 * susurros y las figuras con cara. Solo en memoria: un reinicio los olvida y no pasa nada.
 */
final class Testigos {

    /** Un muerto reciente: nombre y cara, para susurros y maniquies (nunca alguien conectado). */
    record Muerto(UUID id, String nombre, long cuando, String skinValor, String skinFirma) {
    }

    static final long DIA_MS = 24 * 3_600_000L;
    private static final int MAX_RECIENTES = 64;

    private final Hardcore hc;
    private final ArrayDeque<Muerto> recientes = new ArrayDeque<>();
    /** Ultima vez (millis) que cada uno perdio cordura por ver una muerte, y por ver un Eco. */
    private final Map<UUID, Long> ultimoTestigo = new HashMap<>();
    private final Map<UUID, Long> ultimoEco = new HashMap<>();

    Testigos(Hardcore hc) {
        this.hc = hc;
        Autotest.registrar("testigos", this::autotest);
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = hc.cfg().getConfigurationSection("testigos");
        return s == null ? new YamlConfiguration() : s;
    }

    private boolean activo() {
        return cfg().getBoolean("activo", true);
    }

    /**
     * Desde Hardcore.onMuerte y desde Combate.cable (huir por el cable cuenta como morir). El
     * muerto sigue en el mundo y con su cuerpo: la linea de vision se mide contra el.
     */
    void alMorir(Player muerto) {
        // Lo de sus sentidos se apaga muera como muera (vineta, figuras, minutos de lucidez).
        Sentidos s = hc.sentidos();
        if (s != null) hc.seguro("sentidos", () -> s.alMorir(muerto));
        if (!hc.esHardcore(muerto)) return;
        apuntarMuerto(muerto);
        if (!activo()) return;

        World w = muerto.getWorld();
        int oyentes = 0;
        if (cfg().getBoolean("campana", true)) {
            for (Player p : w.getPlayers()) {
                // En SU posicion, solo para el: se oye igual de lejos y no delata donde fue.
                p.playSound(p.getLocation(), "block.bell.resonate", 0.4f, 0.6f);
                oyentes++;
            }
        }
        List<String> vistos = new ArrayList<>();
        // Muerte en el vacio: solo campana. Nadie ve caer a quien ya no esta en el mapa.
        if (!enVacio(muerto)) {
            double radio = cfg().getDouble("radio", 24);
            double resta = cfg().getDouble("cordura", 5);
            long cada = Math.max(0, cfg().getInt("cada-segundos", 60)) * 1000L;
            long ahora = System.currentTimeMillis();
            Player asesino = muerto.getKiller();
            ultimoTestigo.values().removeIf(t -> ahora - t > cada);
            for (Player p : w.getPlayers()) {
                boolean excluido = p.equals(muerto) || p.equals(asesino) || !hc.cuenta(p);
                if (excluido) continue;
                double d2 = p.getLocation().distanceSquared(muerto.getLocation());
                if (d2 > radio * radio) continue;
                if (!testigo(d2, radio, p.hasLineOfSight(muerto), false,
                        ultimoTestigo.get(p.getUniqueId()), ahora, cada)) continue;
                ultimoTestigo.put(p.getUniqueId(), ahora);
                if (resta > 0) hc.cordura().sumar(p, -resta);
                hc.cordura().destello(p, Component.text("Has visto caer a ", Paleta.TEXTO)
                        .append(Component.text(muerto.getName(), Paleta.DETALLE))
                        .append(Component.text(".", Paleta.TEXTO)), 3);
                vistos.add(p.getName());
                Telemetria t = hc.telemetria();
                if (t != null) {
                    Map<String, Object> campos = new LinkedHashMap<>();
                    campos.put("muerto", muerto.getName());
                    campos.put("cordura", -resta);
                    t.suceso("testigo", p, campos);
                }
            }
        }
        try {
            hc.plugin().bitacora().anotar("testigos", "muerte", muerto.getName(), "oyen " + oyentes,
                    "testigos " + vistos.size(), vistos.isEmpty() ? "-" : String.join(",", vistos));
        } catch (Throwable sinBitacora) {
            // Apagando.
        }
    }

    /** Ecos.despertar, al alzarse por primera vez: verlo levantarse cuesta cordura. */
    void alAlzarEco(Location donde) {
        if (donde == null || donde.getWorld() == null || !hc.esHardcore(donde.getWorld()) || !activo()) return;
        double radio = cfg().getDouble("radio", 24);
        double resta = cfg().getDouble("cordura-eco", 5);
        long cada = Math.max(0, cfg().getInt("cada-segundos", 60)) * 1000L;
        long ahora = System.currentTimeMillis();
        Location pecho = donde.clone().add(0, 1, 0);
        ultimoEco.values().removeIf(t -> ahora - t > cada);
        List<String> vistos = new ArrayList<>();
        for (Player p : donde.getWorld().getPlayers()) {
            if (!hc.cuenta(p)) continue;
            double d2 = p.getLocation().distanceSquared(donde);
            if (d2 > radio * radio) continue;
            if (!testigo(d2, radio, p.hasLineOfSight(pecho), false, ultimoEco.get(p.getUniqueId()), ahora, cada)) continue;
            ultimoEco.put(p.getUniqueId(), ahora);
            if (resta > 0) hc.cordura().sumar(p, -resta);
            vistos.add(p.getName());
        }
        if (vistos.isEmpty()) return;
        try {
            hc.plugin().bitacora().anotar("testigos", "eco", donde.getBlockX() + " " + donde.getBlockY() + " "
                    + donde.getBlockZ(), "testigos " + vistos.size(), String.join(",", vistos));
        } catch (Throwable sinBitacora) {
            // Apagando.
        }
    }

    /**
     * Si cuenta como testigo: a radio, viendolo, sin estar excluido (el asesino, el propio
     * muerto, un espectador) y sin haber pagado ya en los ultimos cada-segundos.
     */
    static boolean testigo(double distancia2, double radio, boolean loVe, boolean excluido,
                           Long ultimo, long ahora, long cadaMs) {
        if (excluido || !loVe || distancia2 > radio * radio) return false;
        return ultimo == null || ahora - ultimo >= cadaMs;
    }

    private static boolean enVacio(Player p) {
        EntityDamageEvent ult = p.getLastDamageCause();
        if (ult != null && ult.getCause() == EntityDamageEvent.DamageCause.VOID) return true;
        return p.getLocation().getY() < p.getWorld().getMinHeight();
    }

    // ------------------------------------------------------------ muertos recientes

    private void apuntarMuerto(Player p) {
        String valor = null, firma = null;
        try {
            for (ProfileProperty pp : p.getPlayerProfile().getProperties()) {
                if ("textures".equals(pp.getName())) {
                    valor = pp.getValue();
                    firma = pp.getSignature();
                }
            }
        } catch (Throwable sinPerfil) {
            // Sin texturas: sirve para susurros, no para maniquies.
        }
        apuntar(recientes, new Muerto(p.getUniqueId(), p.getName(), System.currentTimeMillis(), valor, firma));
    }

    /** Uno por jugador (el ultimo), como mucho MAX_RECIENTES, y nada de mas de 24 h. */
    static void apuntar(ArrayDeque<Muerto> lista, Muerto m) {
        lista.removeIf(x -> x.id().equals(m.id()));
        lista.addLast(m);
        long corte = m.cuando() - DIA_MS;
        lista.removeIf(x -> x.cuando() < corte);
        while (lista.size() > MAX_RECIENTES) lista.removeFirst();
    }

    /** Los muertos de las ultimas 24 h, del mas viejo al mas nuevo. */
    List<Muerto> recientes() {
        long corte = System.currentTimeMillis() - DIA_MS;
        List<Muerto> out = new ArrayList<>();
        for (Iterator<Muerto> it = recientes.iterator(); it.hasNext(); ) {
            Muerto m = it.next();
            if (m.cuando() >= corte) out.add(m);
        }
        return out;
    }

    void parar() {
        ultimoTestigo.clear();
        ultimoEco.clear();
        recientes.clear();
    }

    // ------------------------------------------------------------------ autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        long ahora = 10_000_000L;
        h.ok("testigo a 10 bloques viendolo", testigo(100, 24, true, false, null, ahora, 60_000));
        h.ok("no a 25 bloques", !testigo(625, 24, true, false, null, ahora, 60_000));
        h.ok("justo a 24 bloques si", testigo(576, 24, true, false, null, ahora, 60_000));
        h.ok("no sin linea de vision", !testigo(100, 24, false, false, null, ahora, 60_000));
        h.ok("no el asesino ni el muerto", !testigo(100, 24, true, true, null, ahora, 60_000));
        h.ok("no dos veces en el mismo minuto", !testigo(100, 24, true, false, ahora - 59_000, ahora, 60_000));
        h.ok("si pasado el minuto", testigo(100, 24, true, false, ahora - 60_000, ahora, 60_000));

        ArrayDeque<Muerto> lista = new ArrayDeque<>();
        UUID a = Autotest.sintetico(1), b = Autotest.sintetico(2);
        apuntar(lista, new Muerto(a, "Ana", ahora - DIA_MS - 1, null, null));
        apuntar(lista, new Muerto(b, "Beto", ahora, null, null));
        h.igual("los de mas de 24 h se van", 1, lista.size());
        apuntar(lista, new Muerto(b, "Beto", ahora + 5, null, null));
        h.igual("uno por jugador", 1, lista.size());
        for (int i = 0; i < 100; i++) apuntar(lista, new Muerto(Autotest.sintetico(100 + i), "x" + i, ahora + i, null, null));
        h.igual("como mucho " + MAX_RECIENTES, MAX_RECIENTES, lista.size());
        h.igual("se queda el mas nuevo", "x99", lista.peekLast().nombre());
        return h.lineas();
    }
}
