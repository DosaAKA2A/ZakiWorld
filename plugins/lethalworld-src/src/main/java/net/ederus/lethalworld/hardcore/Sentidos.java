package net.ederus.lethalworld.hardcore;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M9 · Latido, M21 · Vineta y el reloj de M20 · Alucinaciones: lo que Calamity le hace sentir
 * a cada jugador segun su cordura, sin un solo mensaje.
 *
 * El latido (entity.warden.heartbeat, solo para el) suena cada 2 s con la cordura por debajo
 * de 25 y cada segundo a 0; con una PARCA a latido-parca-radio (32) suena en cualquier tramo.
 * No cambia ningun numero y aun asi sube la demanda del Frasco (sumidero de Esencias): el
 * sonido empuja a beber o a salir.
 *
 * Tambien cuenta stats.lucidez-min: los minutos a cordura < 25 de una expedicion de la que se
 * sale vivo. Se cobran al salir del mundo (o al desconectarse, que no es morir) y se pierden
 * al morir o al huir por el cable (Testigos.alMorir llama a alMorir de aqui).
 */
final class Sentidos implements Listener {

    private final Hardcore hc;
    private final Alucinaciones alucinaciones;
    private final Vineta vineta;
    /** Segundos a cordura < 25 en la expedicion en curso. Solo memoria; se cobra al salir vivo. */
    private final Map<UUID, Integer> lucidez = new HashMap<>();

    Sentidos(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        this.alucinaciones = new Alucinaciones(hc);
        this.vineta = new Vineta(hc);
        Autotest.registrar("sentidos", this::autotest);
    }

    Alucinaciones alucinaciones() {
        return alucinaciones;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = hc.cfg().getConfigurationSection("sentidos");
        return s == null ? new YamlConfiguration() : s;
    }

    /** Una vez por segundo por jugador que cuenta, desde Hardcore.tick (ya en mundo hardcore). */
    void latido(Player p) {
        Cordura.Estado e = hc.cordura().estado(p);
        int tramo = Cordura.tramo(e.valor);
        int segundo = e.segundosDentro;
        ConfigurationSection s = cfg();

        if (s.getBoolean("latido", true)) {
            Parca parca = hc.parca();
            double distancia = parca == null ? Double.MAX_VALUE
                    : hc.valor("parca", () -> parca.distancia(p), Double.MAX_VALUE);
            float volumen = volumenLatido(tramo, parcaCerca(distancia, s.getDouble("latido-parca-radio", 32)), segundo);
            // Solo para el y en su posicion: el latido es suyo, no delata donde esta.
            if (volumen > 0) p.playSound(p, "entity.warden.heartbeat", volumen, 1f);
        }

        hc.seguro("sentidos", () -> vineta.segundo(p, tramo));
        if (e.valor < 25) lucidez.merge(p.getUniqueId(), 1, Integer::sum);
        // Cada 20 s dentro, la tirada de las alucinaciones (DIS M20).
        if (segundo > 0 && segundo % 20 == 0) hc.seguro("alucinaciones", () -> alucinaciones.tirada(p, tramo));
    }

    /**
     * Volumen del latido de este segundo; 0 = no suena. Tramo 0: cada segundo a 1,0. Tramo 1,
     * o cualquiera con la PARCA cerca: los segundos pares a 0,7 (cada 2 s). Resto: nada.
     */
    static float volumenLatido(int tramo, boolean parcaCerca, int segundo) {
        if (tramo <= 0) return 1.0f;
        if (tramo == 1 || parcaCerca) return segundo % 2 == 0 ? 0.7f : 0f;
        return 0f;
    }

    static boolean parcaCerca(double distancia, double radio) {
        return distancia <= radio;
    }

    static int minutos(int segundos) {
        return Math.max(0, segundos) / 60;
    }

    // ------------------------------------------------------------------ ganchos

    /** Desde Testigos.alMorir (muerte y cable): fuera vineta y figuras, y la lucidez no se cobra. */
    void alMorir(Player p) {
        lucidez.remove(p.getUniqueId());
        vineta.quitar(p);
        alucinaciones.olvidar(p);
    }

    /** Salir vivo: se cobran los minutos de lucidez. */
    private void cobrarLucidez(Player p) {
        Integer seg = lucidez.remove(p.getUniqueId());
        int min = seg == null ? 0 : minutos(seg);
        Estadisticas st = hc.estadisticas();
        if (min > 0 && st != null) hc.seguro("estadisticas", () -> st.sumar(p.getUniqueId(), "lucidez-min", min));
    }

    /** MONITOR: despues de Combate.alDesconectar, que si huye por el cable ya le ha dado por muerto. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSalir(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        cobrarLucidez(p);
        vineta.olvidar(p.getUniqueId());
        alucinaciones.olvidar(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCambiarMundo(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        // El borde era del mundo de antes: fuera, y si el nuevo es hardcore se pone en el segundo.
        vineta.quitar(p);
        alucinaciones.olvidar(p);
        if (hc.esHardcore(e.getFrom()) && !hc.esHardcore(p)) cobrarLucidez(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo() != null && e.getTo().getWorld() == e.getFrom().getWorld()) vineta.recentrarLuego(e.getPlayer());
    }

    void parar() {
        // Al apagar no hay quit: los que siguen dentro cobran lo que llevan, que no han muerto.
        for (UUID id : new ArrayList<>(lucidez.keySet())) {
            Player p = hc.plugin().getServer().getPlayer(id);
            if (p != null) cobrarLucidez(p);
        }
        lucidez.clear();
        alucinaciones.parar();
        vineta.parar();
    }

    // ------------------------------------------------------------------ autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        // Latido: tramo 1 cada 2 s, tramo 0 cada segundo, con la PARCA cerca en cualquier tramo.
        h.cerca("tramo 1, segundo par: suena a 0,7", 0.7, volumenLatido(1, false, 10), 1e-6);
        h.cerca("tramo 1, segundo impar: calla", 0, volumenLatido(1, false, 11), 1e-6);
        h.cerca("tramo 0, segundo par: a 1,0", 1.0, volumenLatido(0, false, 10), 1e-6);
        h.cerca("tramo 0, segundo impar: tambien", 1.0, volumenLatido(0, false, 11), 1e-6);
        int sonados = 0;
        for (int s = 1; s <= 10; s++) if (volumenLatido(1, false, s) > 0) sonados++;
        h.igual("tramo 1: 5 latidos en 10 s", 5, sonados);
        sonados = 0;
        for (int s = 1; s <= 10; s++) if (volumenLatido(0, false, s) > 0) sonados++;
        h.igual("tramo 0: 10 latidos en 10 s", 10, sonados);
        for (int t = 2; t <= 4; t++) {
            h.cerca("tramo " + t + " sin PARCA: nada (par)", 0, volumenLatido(t, false, 10), 1e-6);
            h.cerca("tramo " + t + " sin PARCA: nada (impar)", 0, volumenLatido(t, false, 11), 1e-6);
            h.cerca("tramo " + t + " con PARCA cerca: late", 0.7, volumenLatido(t, true, 10), 1e-6);
        }
        h.cerca("tramo 0 con PARCA: sigue cada segundo", 1.0, volumenLatido(0, true, 11), 1e-6);
        h.ok("PARCA a 32 bloques cuenta", parcaCerca(32, 32));
        h.ok("PARCA a 32,5 no", !parcaCerca(32.5, 32));
        h.ok("sin PARCA no", !parcaCerca(Double.MAX_VALUE, 32));

        // Vineta: aviso = radio / (1 - i); el cliente tine 1 - radio/aviso = i.
        List<Double> def = List.of(0.85, 0.6, 0.35, 0.0, 0.0);
        h.cerca("intensidad tramo 0", 0.85, Vineta.intensidad(def, 0, false, 0.2), 1e-9);
        h.cerca("intensidad tramo 3 sin Eclipse", 0, Vineta.intensidad(def, 3, false, 0.2), 1e-9);
        h.cerca("intensidad tramo 3 con Eclipse", 0.2, Vineta.intensidad(def, 3, true, 0.2), 1e-9);
        h.cerca("con Eclipse en tramo 0 se topa", Vineta.INTENSIDAD_MAXIMA, Vineta.intensidad(def, 0, true, 0.2), 1e-9);
        h.cerca("tramo fuera de la lista", 0, Vineta.intensidad(def, 9, false, 0.2), 1e-9);
        h.igual("aviso para 0,85 con radio 10.000", 66_667, Vineta.aviso(10_000, 0.85));
        h.igual("aviso para 0,35", 15_385, Vineta.aviso(10_000, 0.35));
        h.igual("sin intensidad no hay borde", 0, Vineta.aviso(10_000, 0));
        for (int t = 0; t <= 2; t++) {
            double i = Vineta.intensidad(def, t, false, 0);
            h.cerca("el cliente pinta la intensidad del tramo " + t, i, 1 - 10_000.0 / Vineta.aviso(10_000, i), 1e-3);
        }
        h.ok("mas cordura, menos vineta", Vineta.aviso(10_000, Vineta.intensidad(def, 0, false, 0))
                > Vineta.aviso(10_000, Vineta.intensidad(def, 1, false, 0)));

        h.igual("lucidez: 150 s son 2 min", 2, minutos(150));
        h.igual("lucidez: 59 s no llegan", 0, minutos(59));
        return h.lineas();
    }
}
