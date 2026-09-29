package net.ederus.calamity.hardcore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WorldBorder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M21 · Vineta de cordura: los bordes de la pantalla se tinen de rojo segun baja la cordura.
 *
 * Sin resource pack no hay shader que valga, pero el cliente ya pinta una vineta roja cuando
 * estas cerca del borde del mundo, y Paper deja darle a cada jugador un borde PROPIO (patron
 * de EDM/troll/Trolls.java). Se le pone uno enorme centrado en el (radio-borde, 10.000
 * bloques: nunca lo alcanza ni le hace dano) y se juega con la distancia de aviso: el cliente
 * tine con g = 1 - distancia/aviso, y con el jugador en el centro la distancia es el radio,
 * asi que aviso = radio / (1 - i) da exactamente la intensidad i. El mundo de verdad no se toca.
 *
 * Se recentra cada recentrar-segundos (30) y tras un teleport; se quita al salir, morir, en el
 * quit y al parar. Bedrock puede no pintarla (Geyser): el latido si le llega.
 */
final class Vineta {

    /** Por encima de esto el aviso se dispara hacia infinito: la vineta ya es casi negra. */
    static final double INTENSIDAD_MAXIMA = 0.95;

    private static final class Estado {
        int aviso = -1;
        long centrado;
        boolean recentrar;
    }

    private final Hardcore hc;
    private final Map<UUID, Estado> estados = new HashMap<>();

    Vineta(Hardcore hc) {
        this.hc = hc;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = hc.cfg().getConfigurationSection("sentidos");
        return s == null ? new YamlConfiguration() : s;
    }

    /** Cada segundo, desde Sentidos.latido. Solo toca al jugador si algo ha cambiado. */
    void segundo(Player p, int tramo) {
        ConfigurationSection s = cfg();
        /* 1.8.5: el cielo rojo de Clima suma su parte aunque la vineta de cordura este apagada. Va
         * por aqui y no con un borde propio: dos setWorldBorder por jugador se pisarian. */
        Clima clima = hc.clima();
        double deClima = clima == null ? 0.0 : hc.valor("clima", () -> clima.vinetaExtra(p), 0.0);
        boolean deCordura = s.getBoolean("vinheta", false);
        if (!deCordura && deClima <= 0) {
            quitar(p);
            return;
        }
        double radio = Math.max(16, s.getDouble("radio-borde", 10_000));
        double base = 0.0;
        if (deCordura) {
            Eclipse eclipse = hc.eclipse();
            boolean enEclipse = eclipse != null && hc.valor("eclipse", eclipse::activo, false);
            // El extra lo dice Eclipse (eclipse.vinheta-extra): una sola lectura de la clave.
            double extra = enEclipse ? hc.valor("eclipse", eclipse::vinetaExtra, 0.0) : 0.0;
            base = intensidad(porTramo(s), tramo, enEclipse, extra);
        }
        int aviso = aviso(radio, conExtra(base, deClima));
        if (aviso <= 0) {
            quitar(p);
            return;
        }
        Estado e = estados.computeIfAbsent(p.getUniqueId(), k -> new Estado());
        long ahora = System.currentTimeMillis();
        long cada = Math.max(1, s.getInt("recentrar-segundos", 30)) * 1000L;
        if (e.aviso == aviso && !e.recentrar && ahora - e.centrado < cada) return;
        poner(p, radio, aviso);
        e.aviso = aviso;
        e.centrado = ahora;
        e.recentrar = false;
    }

    /** Tras un teleport: al segundo siguiente se vuelve a centrar en el. */
    void recentrarLuego(Player p) {
        Estado e = estados.get(p.getUniqueId());
        if (e != null) e.recentrar = true;
    }

    /** Le devuelve el borde del mundo. Solo si tenia uno nuestro: no pisa el de otro plugin. */
    void quitar(Player p) {
        if (estados.remove(p.getUniqueId()) != null && p.isOnline()) p.setWorldBorder(null);
    }

    /** En el quit: el cliente se va, no hay borde que devolver. */
    void olvidar(UUID id) {
        estados.remove(id);
    }

    void parar() {
        for (UUID id : new ArrayList<>(estados.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) quitar(p);
        }
        estados.clear();
    }

    private static void poner(Player p, double radio, int aviso) {
        Location l = p.getLocation();
        // Uno nuevo cada vez: un borde virtual cambiado despues de darlo no siempre llega al cliente.
        WorldBorder b = Bukkit.createWorldBorder();
        b.setCenter(l.getX(), l.getZ());
        b.setSize(Math.min(2 * radio, b.getMaxSize()));
        b.setDamageAmount(0);
        b.setDamageBuffer(radio);
        b.setWarningDistance(aviso);
        p.setWorldBorder(b);
    }

    private static List<Double> porTramo(ConfigurationSection s) {
        List<Double> l = s.getDoubleList("vinheta-intensidad");
        return l.isEmpty() ? List.of(0.85, 0.6, 0.35, 0.0, 0.0) : l;
    }

    /** La intensidad (0-1) de su tramo, +extra en Eclipse, con tope INTENSIDAD_MAXIMA. */
    static double intensidad(List<Double> porTramo, int tramo, boolean eclipse, double extra) {
        double i = porTramo == null || tramo < 0 || tramo >= porTramo.size() ? 0 : porTramo.get(tramo);
        if (eclipse) i += Math.max(0, extra);
        return Math.max(0, Math.min(INTENSIDAD_MAXIMA, i));
    }

    /** Calamity 1.8.5: la intensidad con un extra de otro modulo (el cielo rojo de Clima), con el mismo tope. */
    static double conExtra(double base, double extra) {
        return Math.max(0, Math.min(INTENSIDAD_MAXIMA, base + Math.max(0, extra)));
    }

    /** La distancia de aviso que pinta esa intensidad con el jugador en el centro. 0 = sin vineta. */
    static int aviso(double radio, double intensidad) {
        if (intensidad <= 0 || radio <= 0) return 0;
        double i = Math.min(INTENSIDAD_MAXIMA, intensidad);
        return (int) Math.round(radio / (1 - i));
    }
}
