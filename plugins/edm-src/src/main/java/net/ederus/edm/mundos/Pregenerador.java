package net.ederus.edm.mundos;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import net.ederus.edm.Module;

/**
 * Pregenera un mundo de Lethal World antes de abrirlo, para que explorar no genere terreno
 * en caliente (Bracken es pesado: hasta 512 de alto y estructuras grandes).
 *
 * Como lo hace rapido sin tumbar el servidor:
 *  - Espiral desde el centro y solo el CIRCULO del radio, no el cuadrado (un 21 % menos).
 *  - Los chunks ya generados se saltan con isChunkGenerated, que no los carga.
 *  - La generacion va por los hilos de Paper (getChunkAtAsync). Lo unico que corre en el
 *    hilo principal es repartir trabajo, asi que el cuello es la CPU, no el tick.
 *  - Cuantos chunks van a la vez se ajusta solo cada segundo mirando los ms por tick: sube
 *    si hay holgura y baja en cuanto se acerca al objetivo. Con gente dentro frena solo.
 *  - Cada chunk se suelta al terminar (unloadChunkRequest): la memoria no crece con el radio.
 *  - El progreso se guarda cada 10 s y al apagar; tras un reinicio sigue donde iba.
 */
final class Pregenerador {

    private final MundosPlugin modulo;
    private final File fichero;

    private String mundo;
    private int centroX, centroZ;
    private int radioChunks;
    private long total;
    private long indice;
    private long generados, saltados, fallidos;
    private boolean pausado;

    private Espiral espiral;
    private int enCurso;
    private int limite;
    private BukkitTask tarea;
    private BossBar barra;
    private long inicioMs;
    private long hechosAlEmpezar;
    private int tick;
    private int ultimoPorcentaje = -1;

    Pregenerador(MundosPlugin modulo) {
        this.modulo = modulo;
        this.fichero = new File(modulo.getDataFolder(), "pregen.yml");
    }

    // ----------------------------------------------------------------------- ajustes

    private int msObjetivo() {
        return modulo.getConfig().getInt("pregen.ms-por-tick-objetivo", 40);
    }

    private int limiteMaximo() {
        return Math.max(1, modulo.getConfig().getInt("pregen.en-paralelo-maximo", 48));
    }

    int radioPorDefecto() {
        return modulo.getConfig().getInt("pregen.radio-por-defecto", 4000);
    }

    // ------------------------------------------------------------------------ estado

    boolean activo() {
        return mundo != null;
    }

    boolean pausado() {
        return pausado;
    }

    /**
     * El radio que tocaria si no se indica: el del borde del mundo si alguien lo puso (el de
     * serie mide 30 millones y no cuenta), y si no, el de la config.
     */
    int radioAutomatico(World w) {
        WorldBorder b = w.getWorldBorder();
        double lado = b.getSize();
        if (lado < 1_000_000) return (int) Math.ceil(lado / 2.0);
        return radioPorDefecto();
    }

    /** Arranca. Devuelve un error o null. */
    String empezar(World w, int radioBloques) {
        if (activo()) return "Ya hay una pregeneración en " + mundo + ". Usa /lw pregen cancel antes.";
        WorldBorder b = w.getWorldBorder();
        boolean conBorde = b.getSize() < 1_000_000;
        this.mundo = w.getKey().asString();
        this.centroX = conBorde ? b.getCenter().getBlockX() >> 4 : w.getSpawnLocation().getBlockX() >> 4;
        this.centroZ = conBorde ? b.getCenter().getBlockZ() >> 4 : w.getSpawnLocation().getBlockZ() >> 4;
        this.radioChunks = Math.max(1, (int) Math.ceil(radioBloques / 16.0));
        this.total = Espiral.contar(radioChunks);
        this.indice = 0;
        this.generados = this.saltados = this.fallidos = 0;
        this.pausado = false;
        guardar();
        modulo.bitacora().anotar("pregen", "empieza", mundo, "radio " + radioBloques + " bloques",
                total + " chunks", "centro " + (centroX << 4) + " " + (centroZ << 4));
        arrancarTarea();
        return null;
    }

    void pausar() {
        if (!activo() || pausado) return;
        pausado = true;
        pararTarea();
        guardar();
        modulo.bitacora().anotar("pregen", "pausa", mundo, indice + "/" + total);
    }

    void reanudar() {
        if (!activo() || !pausado) return;
        pausado = false;
        guardar();
        arrancarTarea();
        modulo.bitacora().anotar("pregen", "sigue", mundo, indice + "/" + total);
    }

    void cancelar() {
        if (!activo()) return;
        modulo.bitacora().anotar("pregen", "cancelada", mundo, indice + "/" + total);
        pararTarea();
        mundo = null;
        if (fichero.isFile()) fichero.delete();
    }

    /** Al arrancar el modulo: si habia una pregeneracion a medias, sigue sola. */
    void cargar() {
        if (!fichero.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(fichero);
        mundo = y.getString("mundo");
        if (mundo == null) return;
        centroX = y.getInt("centro.x");
        centroZ = y.getInt("centro.z");
        radioChunks = y.getInt("radio-chunks");
        total = y.getLong("total");
        indice = y.getLong("indice");
        generados = y.getLong("generados");
        saltados = y.getLong("saltados");
        fallidos = y.getLong("fallidos");
        pausado = y.getBoolean("pausado");
        if (!pausado) {
            modulo.getServer().getScheduler().runTaskLater(Module.dueno(modulo), this::arrancarTarea, 20L * 10);
            modulo.getLogger().info("[Lethal World] Pregeneración de " + mundo + " a medias (" + porcentaje()
                    + "%): sigue en 10 s.");
        }
    }

    void apagar() {
        if (!activo()) return;
        guardar();
        pararTarea();
    }

    private void guardar() {
        if (mundo == null) return;
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(java.util.List.of("Progreso de /lw pregen. Se borra al terminar o al cancelar."));
        y.set("mundo", mundo);
        y.set("centro.x", centroX);
        y.set("centro.z", centroZ);
        y.set("radio-chunks", radioChunks);
        y.set("total", total);
        y.set("indice", indice);
        y.set("generados", generados);
        y.set("saltados", saltados);
        y.set("fallidos", fallidos);
        y.set("pausado", pausado);
        try {
            y.save(fichero);
        } catch (IOException e) {
            modulo.getLogger().warning("[Lethal World] No pude guardar pregen.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------- bucle

    private World mundoBukkit() {
        NamespacedKey key = mundo == null ? null : NamespacedKey.fromString(mundo);
        return key == null ? null : modulo.getServer().getWorld(key);
    }

    private void arrancarTarea() {
        World w = mundoBukkit();
        if (w == null) {
            modulo.getLogger().warning("[Lethal World] El mundo " + mundo + " de la pregeneración no está cargado.");
            return;
        }
        espiral = new Espiral(radioChunks);
        espiral.saltar(indice);
        enCurso = 0;
        limite = Math.min(8, limiteMaximo());
        inicioMs = System.currentTimeMillis();
        hechosAlEmpezar = indice;
        if (barra == null) barra = modulo.getServer().createBossBar("", BarColor.RED, BarStyle.SEGMENTED_20);
        tarea = modulo.getServer().getScheduler().runTaskTimer(Module.dueno(modulo), () -> paso(w), 1L, 1L);
    }

    private void pararTarea() {
        if (tarea != null) tarea.cancel();
        tarea = null;
        if (barra != null) {
            barra.removeAll();
            barra = null;
        }
    }

    private void paso(World w) {
        tick++;
        if (tick % 20 == 0) ajustarLimite();
        if (tick % 200 == 0) guardar();
        if (tick % 20 == 0) pintarBarra();

        // Saltar ya generados es barato: se permite bastante por tick sin cargar nada.
        int presupuestoSaltos = 400;
        while (enCurso < limite && espiral.quedan()) {
            long pos = espiral.siguiente();
            int x = centroX + (int) (pos >> 32), z = centroZ + (int) pos;
            indice++;
            if (w.isChunkGenerated(x, z)) {
                saltados++;
                if (--presupuestoSaltos <= 0) break;
                continue;
            }
            enCurso++;
            w.getChunkAtAsync(x, z, true).whenComplete((chunk, error) ->
                    modulo.getServer().getScheduler().runTask(Module.dueno(modulo), () -> {
                        enCurso--;
                        if (error != null || chunk == null) fallidos++;
                        else generados++;
                        w.unloadChunkRequest(x, z);
                    }));
        }

        if (!espiral.quedan() && enCurso == 0) terminar();
    }

    /** Mas chunks a la vez si sobra tick; menos en cuanto se acerca al objetivo. */
    private void ajustarLimite() {
        double ms = modulo.getServer().getAverageTickTime();
        int objetivo = msObjetivo();
        if (ms > objetivo) limite = Math.max(1, (int) (limite * 0.7));
        else if (ms < objetivo * 0.6) limite = Math.min(limiteMaximo(), limite + 2);
    }

    private void terminar() {
        long segundos = Math.max(1, (System.currentTimeMillis() - inicioMs) / 1000);
        String resumen = "Pregeneración de " + mundo + " terminada: " + generados + " chunks generados, "
                + saltados + " ya estaban, " + fallidos + " fallidos, en " + formatoTiempo(segundos) + ".";
        modulo.getLogger().info("[Lethal World] " + resumen);
        modulo.bitacora().anotar("pregen", "terminada", mundo, generados + " generados", saltados + " saltados",
                fallidos + " fallidos", formatoTiempo(segundos));
        for (Player p : modulo.getServer().getOnlinePlayers()) {
            if (p.hasPermission("ederus.mundos")) p.sendMessage("§cLethal World §8> §7" + resumen);
        }
        cancelarSinAnotar();
    }

    private void cancelarSinAnotar() {
        pararTarea();
        mundo = null;
        if (fichero.isFile()) fichero.delete();
    }

    private void pintarBarra() {
        if (barra == null) return;
        int pct = porcentaje();
        barra.setProgress(Math.max(0, Math.min(1, total == 0 ? 1 : (double) indice / total)));
        barra.setTitle("Pregenerando " + mundo + "  " + pct + "%  ·  " + velocidad() + " chunks/s  ·  quedan "
                + restante() + "  ·  " + limite + " a la vez");
        for (Player p : modulo.getServer().getOnlinePlayers()) {
            if (p.hasPermission("ederus.mundos")) barra.addPlayer(p);
            else barra.removePlayer(p);
        }
        if (pct / 5 != ultimoPorcentaje / 5) {
            ultimoPorcentaje = pct;
            modulo.getLogger().info("[Lethal World] Pregen " + mundo + ": " + pct + "% (" + indice + "/" + total
                    + "), " + velocidad() + " chunks/s, quedan " + restante() + ".");
        }
    }

    // ----------------------------------------------------------------------- informe

    int porcentaje() {
        return total == 0 ? 100 : (int) (indice * 100 / total);
    }

    long velocidad() {
        long seg = Math.max(1, (System.currentTimeMillis() - inicioMs) / 1000);
        return Math.max(0, (indice - hechosAlEmpezar) / seg);
    }

    String restante() {
        long v = velocidad();
        return v == 0 ? "?" : formatoTiempo((total - indice) / v);
    }

    String estado() {
        if (!activo()) return "No hay ninguna pregeneración en marcha.";
        return String.format(Locale.ROOT, "%s: %d%% (%d/%d chunks) · generados %d, ya estaban %d, fallidos %d · %s",
                mundo, porcentaje(), indice, total, generados, saltados, fallidos,
                pausado ? "EN PAUSA" : velocidad() + " chunks/s, " + limite + " a la vez, quedan " + restante());
    }

    static String formatoTiempo(long s) {
        if (s < 60) return s + " s";
        if (s < 3600) return (s / 60) + " min " + (s % 60) + " s";
        return (s / 3600) + " h " + (s / 60 % 60) + " min";
    }

    // ----------------------------------------------------------------------- espiral

    /**
     * Recorre anillos cuadrados desde el centro y se queda con los chunks del circulo.
     * Cada posicion sale como un long (x << 32 | z) relativo al centro.
     */
    static final class Espiral {
        private final int radio;
        private final long radioCuadrado;
        private int anillo;
        private int paso;
        private long siguiente;
        private boolean hay;

        Espiral(int radio) {
            this.radio = radio;
            this.radioCuadrado = (long) radio * radio + radio;
            avanzar();
        }

        static long contar(int radio) {
            Espiral e = new Espiral(radio);
            long n = 0;
            while (e.quedan()) {
                e.siguiente();
                n++;
            }
            return n;
        }

        boolean quedan() {
            return hay;
        }

        long siguiente() {
            long v = siguiente;
            avanzar();
            return v;
        }

        void saltar(long n) {
            for (long i = 0; i < n && hay; i++) siguiente();
        }

        private void avanzar() {
            while (anillo <= radio) {
                int lado = anillo == 0 ? 1 : 8 * anillo;
                while (paso < lado) {
                    int dx, dz;
                    if (anillo == 0) {
                        dx = 0;
                        dz = 0;
                    } else {
                        int p = paso, l = 2 * anillo;
                        if (p < l) {
                            dx = -anillo + p;
                            dz = -anillo;
                        } else if (p < 2 * l) {
                            dx = anillo;
                            dz = -anillo + (p - l);
                        } else if (p < 3 * l) {
                            dx = anillo - (p - 2 * l);
                            dz = anillo;
                        } else {
                            dx = -anillo;
                            dz = anillo - (p - 3 * l);
                        }
                    }
                    paso++;
                    if ((long) dx * dx + (long) dz * dz <= radioCuadrado) {
                        siguiente = ((long) dx << 32) | (dz & 0xffffffffL);
                        hay = true;
                        return;
                    }
                }
                anillo++;
                paso = 0;
            }
            hay = false;
        }
    }
}
