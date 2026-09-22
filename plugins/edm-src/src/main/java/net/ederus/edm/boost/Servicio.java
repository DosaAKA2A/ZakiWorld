package net.ederus.edm.boost;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Quien tiene que boost y hasta cuando.
 *
 * Dos capas: el boost personal de cada jugador y el boost global del servidor. El
 * multiplicador que se aplica es el MAYOR de los dos, nunca el producto: si hay un
 * x2 global y alguien abre una legendaria con otro x2, no se convierte en x4; lo que
 * gana es tiempo, no potencia. Asi un evento global nunca descuadra la economia.
 *
 * El tiempo se guarda como instante de fin en epoch ms, no como segundos restantes:
 * un reinicio o una caida no regalan ni roban minutos, el reloj sigue corriendo.
 */
public final class Servicio {

    /**
     * Un boost activo: cuanto multiplica, cuando empezo y cuando se acaba. El inicio
     * solo sirve para pintar la barra que se va vaciando; al alargar un boost se
     * conserva el inicio original, asi la barra crece en vez de reiniciarse.
     */
    public record Activo(double multiplicador, long inicio, long fin) {

        public long restanteMs() {
            return Math.max(0, fin - System.currentTimeMillis());
        }

        public boolean vivo() {
            return restanteMs() > 0;
        }

        /** Fraccion de tiempo que queda, de 1 (recien dado) a 0 (acabado). */
        public double progreso() {
            long total = fin - inicio;
            if (total <= 0) return 0;
            return Math.max(0, Math.min(1, restanteMs() / (double) total));
        }
    }

    private final File fichero;
    private final Map<UUID, Map<Tipo, Activo>> personales = new HashMap<>();
    private final Map<Tipo, Activo> globales = new EnumMap<>(Tipo.class);
    /** A quien ya le avise de que se le acaba, para no repetir el aviso cada segundo. */
    private final Map<UUID, Map<Tipo, Boolean>> avisados = new HashMap<>();

    public Servicio(File fichero) {
        this.fichero = fichero;
        cargar();
    }

    /* --------------------------------------------------------------- consultas */

    /** El multiplicador que se aplica ahora mismo a ese jugador, 1.0 si no tiene nada. */
    public double multiplicador(UUID jugador, Tipo tipo) {
        double mejor = 1.0;
        Activo g = globales.get(tipo);
        if (g != null && g.vivo()) mejor = Math.max(mejor, g.multiplicador());
        Activo p = personal(jugador, tipo);
        if (p != null && p.vivo()) mejor = Math.max(mejor, p.multiplicador());
        return mejor;
    }

    public boolean activo(UUID jugador, Tipo tipo) {
        return multiplicador(jugador, tipo) > 1.0;
    }

    public Activo personal(UUID jugador, Tipo tipo) {
        Map<Tipo, Activo> suyos = personales.get(jugador);
        if (suyos == null) return null;
        Activo a = suyos.get(tipo);
        return a != null && a.vivo() ? a : null;
    }

    public Activo global(Tipo tipo) {
        Activo a = globales.get(tipo);
        return a != null && a.vivo() ? a : null;
    }

    /** El que manda de los dos, para pintarlo en el menu. */
    public Activo efectivo(UUID jugador, Tipo tipo) {
        Activo p = personal(jugador, tipo);
        Activo g = global(tipo);
        if (p == null) return g;
        if (g == null) return p;
        return g.multiplicador() > p.multiplicador() ? g : p;
    }

    /* ------------------------------------------------------------------ altas */

    /**
     * Le da (o le alarga) un boost. Si ya tenia uno del mismo tipo:
     *   - con el mismo multiplicador o menos, se SUMA el tiempo;
     *   - con uno mayor, manda el nuevo y se conserva el tiempo que le quedaba.
     * Nunca se pierde tiempo pagado, que es lo que un jugador no perdona.
     */
    public Activo dar(UUID jugador, Tipo tipo, long milisegundos, double multiplicador) {
        Map<Tipo, Activo> suyos = personales.computeIfAbsent(jugador, k -> new EnumMap<>(Tipo.class));
        Activo previo = suyos.get(tipo);
        long ahora = System.currentTimeMillis();
        long fin;
        double mult = multiplicador;
        long inicio = ahora;
        if (previo != null && previo.vivo()) {
            fin = previo.fin() + milisegundos;
            mult = Math.max(previo.multiplicador(), multiplicador);
            inicio = previo.inicio();
        } else {
            fin = ahora + milisegundos;
        }
        Activo nuevo = new Activo(mult, inicio, fin);
        suyos.put(tipo, nuevo);
        olvidarAviso(jugador, tipo);
        guardar();
        return nuevo;
    }

    public Activo darGlobal(Tipo tipo, long milisegundos, double multiplicador) {
        Activo previo = globales.get(tipo);
        long ahora = System.currentTimeMillis();
        long fin = previo != null && previo.vivo() ? previo.fin() + milisegundos : ahora + milisegundos;
        double mult = previo != null && previo.vivo()
                ? Math.max(previo.multiplicador(), multiplicador) : multiplicador;
        long inicio = previo != null && previo.vivo() ? previo.inicio() : ahora;
        Activo nuevo = new Activo(mult, inicio, fin);
        globales.put(tipo, nuevo);
        guardar();
        return nuevo;
    }

    public void quitar(UUID jugador, Tipo tipo) {
        Map<Tipo, Activo> suyos = personales.get(jugador);
        if (suyos != null) {
            suyos.remove(tipo);
            if (suyos.isEmpty()) personales.remove(jugador);
        }
        olvidarAviso(jugador, tipo);
        guardar();
    }

    public void quitarTodo(UUID jugador) {
        personales.remove(jugador);
        avisados.remove(jugador);
        guardar();
    }

    public void quitarGlobal(Tipo tipo) {
        globales.remove(tipo);
        guardar();
    }

    /* ------------------------------------------------------------- caducados */

    /** Barre lo caducado y devuelve que tipos se le acaban de terminar a ese jugador. */
    public java.util.List<Tipo> caducados(UUID jugador) {
        java.util.List<Tipo> fuera = new java.util.ArrayList<>();
        Map<Tipo, Activo> suyos = personales.get(jugador);
        if (suyos == null) return fuera;
        for (Tipo t : Tipo.values()) {
            Activo a = suyos.get(t);
            if (a != null && !a.vivo()) {
                suyos.remove(t);
                fuera.add(t);
            }
        }
        if (suyos.isEmpty()) personales.remove(jugador);
        if (!fuera.isEmpty()) guardar();
        return fuera;
    }

    public java.util.List<Tipo> globalesCaducados() {
        java.util.List<Tipo> fuera = new java.util.ArrayList<>();
        for (Tipo t : Tipo.values()) {
            Activo a = globales.get(t);
            if (a != null && !a.vivo()) {
                globales.remove(t);
                fuera.add(t);
            }
        }
        if (!fuera.isEmpty()) guardar();
        return fuera;
    }

    /** true la primera vez que se pregunta por ese aviso; despues, false. */
    public boolean marcarAviso(UUID jugador, Tipo tipo) {
        Map<Tipo, Boolean> suyos = avisados.computeIfAbsent(jugador, k -> new EnumMap<>(Tipo.class));
        if (Boolean.TRUE.equals(suyos.get(tipo))) return false;
        suyos.put(tipo, Boolean.TRUE);
        return true;
    }

    private void olvidarAviso(UUID jugador, Tipo tipo) {
        Map<Tipo, Boolean> suyos = avisados.get(jugador);
        if (suyos != null) suyos.remove(tipo);
    }

    public java.util.Set<UUID> conBoost() {
        return new java.util.HashSet<>(personales.keySet());
    }

    public boolean algoActivo(Player p) {
        for (Tipo t : Tipo.values()) if (activo(p.getUniqueId(), t)) return true;
        return false;
    }

    /* -------------------------------------------------------------- guardado */

    private void cargar() {
        if (!fichero.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichero);
        ConfigurationSection jug = yml.getConfigurationSection("jugadores");
        if (jug != null) {
            for (String id : jug.getKeys(false)) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(id);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                ConfigurationSection s = jug.getConfigurationSection(id);
                if (s == null) continue;
                Map<Tipo, Activo> suyos = new EnumMap<>(Tipo.class);
                for (String k : s.getKeys(false)) {
                    Tipo t = Tipo.de(k);
                    if (t == null) continue;
                    long fin = s.getLong(k + ".fin", 0);
                    double mult = s.getDouble(k + ".multiplicador", 2.0);
                    long inicio = s.getLong(k + ".inicio", System.currentTimeMillis());
                    if (fin > System.currentTimeMillis()) suyos.put(t, new Activo(mult, inicio, fin));
                }
                if (!suyos.isEmpty()) personales.put(uuid, suyos);
            }
        }
        ConfigurationSection glob = yml.getConfigurationSection("globales");
        if (glob != null) {
            for (String k : glob.getKeys(false)) {
                Tipo t = Tipo.de(k);
                if (t == null) continue;
                long fin = glob.getLong(k + ".fin", 0);
                double mult = glob.getDouble(k + ".multiplicador", 2.0);
                long inicio = glob.getLong(k + ".inicio", System.currentTimeMillis());
                if (fin > System.currentTimeMillis()) globales.put(t, new Activo(mult, inicio, fin));
            }
        }
    }

    /**
     * Vuelca todo a disco. Es un fichero pequeno (solo quien tiene boost AHORA) y se
     * escribe en cada alta o baja, que son pocas: no hace falta nada mas listo.
     */
    public void guardar() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(java.util.List.of(
                "Boosts activos. Lo escribe el modulo solo; no hace falta tocarlo a mano.",
                "inicio y fin: instantes en epoch ms; el inicio solo pinta la barra."));
        for (Map.Entry<UUID, Map<Tipo, Activo>> e : personales.entrySet()) {
            for (Map.Entry<Tipo, Activo> b : e.getValue().entrySet()) {
                if (!b.getValue().vivo()) continue;
                String base = "jugadores." + e.getKey() + "." + b.getKey().id();
                yml.set(base + ".multiplicador", b.getValue().multiplicador());
                yml.set(base + ".inicio", b.getValue().inicio());
                yml.set(base + ".fin", b.getValue().fin());
            }
        }
        for (Map.Entry<Tipo, Activo> b : globales.entrySet()) {
            if (!b.getValue().vivo()) continue;
            yml.set("globales." + b.getKey().id() + ".multiplicador", b.getValue().multiplicador());
            yml.set("globales." + b.getKey().id() + ".inicio", b.getValue().inicio());
            yml.set("globales." + b.getKey().id() + ".fin", b.getValue().fin());
        }
        try {
            File padre = fichero.getParentFile();
            if (padre != null) padre.mkdirs();
            yml.save(fichero);
        } catch (IOException e) {
            // Sin disco no hay nada que hacer; el boost sigue vivo en memoria.
        }
    }

    /** "1h 05m", "12m 30s" o "8s", que es como lo lee un jugador de un vistazo. */
    public static String reloj(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, seg = s % 60;
        if (h > 0) return h + "h " + String.format("%02dm", m);
        if (m > 0) return m + "m " + String.format("%02ds", seg);
        return seg + "s";
    }
}
