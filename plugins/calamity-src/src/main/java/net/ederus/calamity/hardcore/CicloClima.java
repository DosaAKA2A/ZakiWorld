package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Calamity 1.11 · El reloj del clima de Calamity (hardcore.clima.ciclo). Encargo de Dosa: que la lluvia
 * no la decida el ciclo al azar de Minecraft, sino el plugin, con duraciones conocidas.
 *
 * En cada mundo hardcore se apaga la regla del ciclo de clima (GameRules.ADVANCE_WEATHER, la antigua
 * doWeatherCycle) y el plugin lleva su propio reloj: despejado (despejado-minutos), lluvia
 * (lluvia-minutos), despejado... cada fase con +-variacion, y una de cada tormenta-cada lluvias es
 * tormenta electrica. Clima sigue igual: mira World#hasStorm, que ahora es la lluvia que pone este reloj.
 *
 * El reloj va en tiempo real y se guarda en hardcore-datos (clima-ciclo.<mundo>: fase, hasta en millis,
 * cuantas lluvias van, si ya se aviso): un reinicio a mitad de una lluvia sigue con la misma lluvia hasta
 * su hora. Si el servidor estuvo apagado mas de lo que quedaba, la fase siguiente empieza al arrancar
 * (no se recuperan las fases perdidas). Corre aunque no haya nadie dentro: los mundos vacios siguen su
 * reloj y no se avisa a nadie. Un mundo descargado espera; al volver a cargarse se pone al dia.
 *
 * Cada segundo (Clima.tick) se mira si el mundo sigue con el clima de su fase y, si alguien lo cambio
 * (un /weather, otro plugin), se repone: el que manda es /calamidad weather.
 *
 * Aviso aviso-segundos antes de cada lluvia, una sola vez por cambio, como destello corto a quien este
 * dentro: "Se acerca la lluvia", "Se acerca una tormenta" o, segun el bioma en el que esta, "Se acerca
 * lluvia acida" o "Se acerca el cielo rojo" (alli la lluvia no se ve: el cielo arde).
 *
 * Con ciclo.activo en false se devuelve la regla vanilla (solo si la habia apagado Calamity) y no se toca
 * nada mas. Al parar el plugin tambien se devuelve; al arrancar se vuelve a apagar.
 */
final class CicloClima {

    static final String DESPEJADO = "despejado";
    static final String LLUVIA = "lluvia";
    static final String TORMENTA = "tormenta";

    /* Los valores de serie: los mismos que hardcore.clima.ciclo en el config.yml del jar (el autotest los compara). */
    static final boolean ACTIVO = true;
    static final double DESPEJADO_MINUTOS = 20;
    static final double LLUVIA_MINUTOS = 5;
    static final double VARIACION = 0.2;
    static final int TORMENTA_CADA = 4;
    static final int AVISO_SEGUNDOS = 60;

    /** Lo menos que dura una fase, para que una config rara no ponga el clima a parpadear. */
    static final long MINIMO_MS = 30_000L;

    private static final String RAIZ = "clima-ciclo";

    /** El estado de un mundo. Inmutable: el placeholder lo lee desde otro hilo. */
    record Estado(String fase, long hasta, int lluvias, boolean avisado) {
    }

    private final Hardcore hc;
    /** Por mundo (la clave corta, "calamity"). Concurrente: lo leen los placeholders. */
    private final Map<String, Estado> estados = new ConcurrentHashMap<>();
    /** Los mundos hardcore cargados en el ultimo segundo, en orden, para los placeholders (otro hilo). */
    private volatile List<String> mundos = List.of();
    /** tormenta-cada copiado en el hilo principal (tick): el placeholder no lee la config. */
    private volatile int tormentaCada = TORMENTA_CADA;

    CicloClima(Hardcore hc) {
        this.hc = hc;
        PlaceholdersLethal.registrar("clima", this::placeholder);
        Subcomandos.lw().registrar("weather",
                "weather [rain|storm|clear] [minutos]: el clima de Calamity (sin nada, como va)", "ederus.mundos",
                this::comando, args -> switch (args.length) {
                    case 2 -> List.of("rain", "storm", "clear");
                    case 3 -> List.of("5", "10", "20");
                    default -> List.of();
                });
        Autotest.registrar("ciclo-clima", () -> autotest(hc.plugin().getConfig().getDefaults()));
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = hc.cfg().getConfigurationSection("clima.ciclo");
        return s == null ? new YamlConfiguration() : s;
    }

    // ------------------------------------------------------------------ reloj

    /** Una vez por segundo, desde Clima.tick. */
    void tick() {
        ConfigurationSection c = cfg();
        boolean activo = c.getBoolean("activo", ACTIVO);
        tormentaCada = c.getInt("tormenta-cada", TORMENTA_CADA);
        long ahora = System.currentTimeMillis();
        List<String> vistos = new ArrayList<>();
        for (World w : hc.plugin().getServer().getWorlds()) {
            if (!hc.esHardcore(w)) continue;
            String mundo = w.getKey().getKey();
            vistos.add(mundo);
            if (!activo) {
                devolverRegla(w);
                continue;
            }
            apagarRegla(w);
            Estado e = estado(mundo);
            if (e == null) e = inicial(w, c, ahora);
            if (ahora >= e.hasta()) e = siguienteFase(e, c, ahora);
            if (tocaAviso(e.fase(), e.hasta() - ahora, c.getInt("aviso-segundos", AVISO_SEGUNDOS) * 1000L, e.avisado())) {
                avisar(w, siguiente(e.fase(), e.lluvias(), c.getInt("tormenta-cada", TORMENTA_CADA)));
                e = new Estado(e.fase(), e.hasta(), e.lluvias(), true);
            }
            guardar(mundo, e);
            aplicar(w, e.fase());
        }
        if (!activo) estados.clear();
        mundos = List.copyOf(vistos);
    }

    /** Al parar: la regla vanilla vuelve (al arrancar se apaga otra vez) y el estado ya esta en hardcore-datos. */
    void parar() {
        for (World w : hc.plugin().getServer().getWorlds()) {
            if (hc.esHardcore(w)) devolverRegla(w);
        }
        estados.clear();
        mundos = List.of();
    }

    /** Lo que hay en memoria o, si no, lo guardado en hardcore-datos (tras un reinicio). Null = nada. */
    private Estado estado(String mundo) {
        Estado e = estados.get(mundo);
        if (e != null) return e;
        ConfigurationSection s = hc.datos().getConfigurationSection(RAIZ + "." + mundo);
        if (s == null) return null;
        String fase = fase(s.getString("fase", ""));
        if (fase == null) return null;
        e = new Estado(fase, s.getLong("hasta", 0), s.getInt("lluvias", 0), s.getBoolean("avisado", false));
        estados.put(mundo, e);
        return e;
    }

    /** La primera vez en un mundo: se sigue con el clima que tenga, para que no cambie de golpe. */
    private Estado inicial(World w, ConfigurationSection c, long ahora) {
        String fase = w.hasStorm() ? (w.isThundering() ? TORMENTA : LLUVIA) : DESPEJADO;
        return new Estado(fase, ahora + duracion(fase, c), 0, false);
    }

    private Estado siguienteFase(Estado e, ConfigurationSection c, long ahora) {
        String nueva = siguiente(e.fase(), e.lluvias(), c.getInt("tormenta-cada", TORMENTA_CADA));
        int lluvias = DESPEJADO.equals(nueva) ? e.lluvias() : e.lluvias() + 1;
        return new Estado(nueva, ahora + duracion(nueva, c), lluvias, false);
    }

    private static long duracion(String fase, ConfigurationSection c) {
        double minutos = DESPEJADO.equals(fase) ? c.getDouble("despejado-minutos", DESPEJADO_MINUTOS)
                : c.getDouble("lluvia-minutos", LLUVIA_MINUTOS);
        return duracionMs(minutos, c.getDouble("variacion", VARIACION), ThreadLocalRandom.current().nextDouble());
    }

    private void guardar(String mundo, Estado e) {
        if (e.equals(estados.get(mundo))) return;
        estados.put(mundo, e);
        String r = RAIZ + "." + mundo + ".";
        hc.datos().set(r + "fase", e.fase());
        hc.datos().set(r + "hasta", e.hasta());
        hc.datos().set(r + "lluvias", e.lluvias());
        hc.datos().set(r + "avisado", e.avisado());
        hc.marcarSucio();
    }

    /** El mundo con el clima de su fase. Solo se toca si no lo tiene: casi siempre son dos lecturas. */
    private static void aplicar(World w, String fase) {
        boolean lluvia = !DESPEJADO.equals(fase);
        boolean trueno = TORMENTA.equals(fase);
        if (w.hasStorm() != lluvia) w.setStorm(lluvia);
        if (w.isThundering() != trueno) w.setThundering(trueno);
    }

    private void apagarRegla(World w) {
        if (!Boolean.TRUE.equals(w.getGameRuleValue(GameRules.ADVANCE_WEATHER))) return;
        w.setGameRule(GameRules.ADVANCE_WEATHER, false);
        hc.datos().set(RAIZ + "." + w.getKey().getKey() + ".regla-apagada", true);
        hc.marcarSucio();
    }

    /** La regla vanilla vuelve, pero solo si la apago Calamity (no se pisa una puesta a mano). */
    private void devolverRegla(World w) {
        String ruta = RAIZ + "." + w.getKey().getKey() + ".regla-apagada";
        if (!hc.datos().getBoolean(ruta, false)) return;
        w.setGameRule(GameRules.ADVANCE_WEATHER, true);
        hc.datos().set(ruta, null);
        hc.marcarSucio();
    }

    private void avisar(World w, String fase) {
        for (Player p : w.getPlayers()) {
            if (!hc.cuenta(p)) continue;
            Clima clima = hc.clima();
            Clima.Tipo tipo = clima == null || hc.enSpawn(p) ? Clima.Tipo.NINGUNO
                    : hc.valor("clima", () -> clima.tipoAhora(p), Clima.Tipo.NINGUNO);
            hc.cordura().destello(p, aviso(fase, tipo), 4);
        }
    }

    // ------------------------------------------------------------------ comando y placeholders

    private void comando(CommandSender quien, String[] args) {
        List<World> objetivo = new ArrayList<>();
        if (quien instanceof Player p && hc.esHardcore(p)) objetivo.add(p.getWorld());
        else for (World w : hc.plugin().getServer().getWorlds()) if (hc.esHardcore(w)) objetivo.add(w);
        if (objetivo.isEmpty()) {
            quien.sendMessage(ComandoCalamity.mensaje("No hay ningún mundo de Calamity cargado."));
            return;
        }
        ConfigurationSection c = cfg();
        if (!c.getBoolean("activo", ACTIVO)) {
            quien.sendMessage(ComandoCalamity.mensaje("El ciclo de clima de Calamity está apagado (hardcore.clima.ciclo.activo): manda el de Minecraft."));
            return;
        }
        long ahora = System.currentTimeMillis();
        if (args.length < 2) {
            for (World w : objetivo) quien.sendMessage(ComandoCalamity.mensaje(resumen(w.getKey().getKey(), ahora)));
            return;
        }
        String fase = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "rain" -> LLUVIA;
            case "storm" -> TORMENTA;
            case "clear" -> DESPEJADO;
            default -> null;
        };
        if (fase == null) {
            quien.sendMessage(ComandoCalamity.mensaje("Uso: /calamidad weather [rain|storm|clear] [minutos]"));
            return;
        }
        Double minutos = null;
        if (args.length > 2) {
            minutos = minutos(args[2]);
            if (minutos == null) {
                quien.sendMessage(ComandoCalamity.mensaje("Los minutos tienen que ser un número entre 0.5 y 1440."));
                return;
            }
        }
        for (World w : objetivo) {
            String mundo = w.getKey().getKey();
            apagarRegla(w);
            Estado antes = estado(mundo);
            int lluvias = antes == null ? 0 : antes.lluvias();
            long dura = minutos == null ? duracion(fase, c) : Math.round(minutos * 60_000L);
            // Forzada: no cuenta para "una de cada tormenta-cada", y si es despejado se avisa de la siguiente.
            guardar(mundo, new Estado(fase, ahora + dura, lluvias, false));
            aplicar(w, fase);
            quien.sendMessage(ComandoCalamity.mensaje(resumen(mundo, ahora)));
        }
    }

    private String resumen(String mundo, long ahora) {
        Estado e = estados.get(mundo);
        if (e == null) return mundo + ": el reloj del clima aún no ha empezado.";
        String proximo = siguiente(e.fase(), e.lluvias(), cfg().getInt("tormenta-cada", TORMENTA_CADA));
        return mundo + ": " + e.fase() + ", quedan " + restante(e.hasta() - ahora) + ". Después: " + proximo + ".";
    }

    /** %lethalworld_clima%, %lethalworld_clima_restante% y %lethalworld_clima_proximo%. Solo lee: otro hilo. */
    private String placeholder(org.bukkit.OfflinePlayer jugador, String resto) {
        Estado e = null;
        Player p = jugador == null ? null : jugador.getPlayer();
        if (p != null) e = estados.get(p.getWorld().getKey().getKey());
        if (e == null) {
            for (String m : mundos) {
                e = estados.get(m);
                if (e != null) break;
            }
        }
        if (e == null) return "";
        return switch (resto == null ? "" : resto.toLowerCase(Locale.ROOT)) {
            case "" -> e.fase();
            case "restante" -> restante(e.hasta() - System.currentTimeMillis());
            case "proximo" -> siguiente(e.fase(), e.lluvias(), tormentaCada);
            default -> null;
        };
    }

    // ------------------------------------------------------------------ el nucleo

    /** "despejado" | "lluvia" | "tormenta" tal cual (sin mayusculas ni espacios), o null si no es una fase. */
    static String fase(String texto) {
        if (texto == null) return null;
        String t = texto.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case DESPEJADO, LLUVIA, TORMENTA -> t;
            default -> null;
        };
    }

    /** Si la lluvia numero n (la primera es la 1) es tormenta: una de cada 'cada'. Con cada <= 0, nunca. */
    static boolean esTormenta(int n, int cada) {
        return cada > 0 && n > 0 && n % cada == 0;
    }

    /** La fase que viene detras de esta, con 'lluvias' lluvias ya empezadas. */
    static String siguiente(String fase, int lluvias, int cada) {
        if (!DESPEJADO.equals(fase)) return DESPEJADO;
        return esTormenta(lluvias + 1, cada) ? TORMENTA : LLUVIA;
    }

    /**
     * Lo que dura una fase: minutos con +-variacion (0.2 = +-20 %), segun azar (0 a 1: 0 el minimo,
     * 1 el maximo). La variacion se queda entre 0 y 0.9 y nada baja de MINIMO_MS.
     */
    static long duracionMs(double minutos, double variacion, double azar) {
        double v = Math.max(0, Math.min(0.9, variacion));
        double a = Math.max(0, Math.min(1, azar));
        double ms = Math.max(0, minutos) * 60_000.0 * (1 + v * (2 * a - 1));
        return Math.max(MINIMO_MS, Math.round(ms));
    }

    /** Si toca avisar: en despejado, una vez, cuando falta aviso o menos. Aviso 0 = nunca. */
    static boolean tocaAviso(String fase, long faltaMs, long avisoMs, boolean avisado) {
        return DESPEJADO.equals(fase) && !avisado && avisoMs > 0 && faltaMs <= avisoMs;
    }

    /** "12 min" (redondeado hacia arriba) desde un minuto; por debajo, "45 s". Nunca negativo. */
    static String restante(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        if (s >= 60) return ((s + 59) / 60) + " min";
        return s + " s";
    }

    /** Los minutos de /calamidad weather, o null si no valen. */
    static Double minutos(String texto) {
        try {
            double m = Double.parseDouble(texto.trim().replace(',', '.'));
            return m >= 0.5 && m <= 1440 && !Double.isNaN(m) ? m : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** El destello del aviso, segun lo que viene y el bioma en el que esta. */
    static Component aviso(String fase, Clima.Tipo tipo) {
        TextColor color;
        String texto;
        if (tipo == Clima.Tipo.ACIDA) {
            texto = "Se acerca lluvia ácida";
            color = Paleta.ACIDO;
        } else if (tipo == Clima.Tipo.ROJO) {
            texto = "Se acerca el cielo rojo";
            color = Paleta.FUEGO;
        } else if (TORMENTA.equals(fase)) {
            texto = "Se acerca una tormenta";
            color = Paleta.CIFRA;
        } else {
            texto = "Se acerca la lluvia";
            color = Paleta.TEXTO;
        }
        return Component.text(texto, color);
    }

    // ------------------------------------------------------------------ autotest

    static List<String> autotest(ConfigurationSection jar) {
        Autotest.Hoja h = new Autotest.Hoja();
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer plano =
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();

        h.igual("fase: lluvia", LLUVIA, fase(" Lluvia "));
        h.igual("fase: rara", null, fase("granizo"));
        h.igual("fase: null", null, fase(null));

        // Una de cada cuatro lluvias es tormenta.
        List<String> fases = new ArrayList<>();
        String f = DESPEJADO;
        int lluvias = 0;
        for (int i = 0; i < 16; i++) {
            f = siguiente(f, lluvias, TORMENTA_CADA);
            if (!DESPEJADO.equals(f)) lluvias++;
            fases.add(f);
        }
        h.igual("ciclo de serie", List.of(LLUVIA, DESPEJADO, LLUVIA, DESPEJADO, LLUVIA, DESPEJADO, TORMENTA, DESPEJADO,
                LLUVIA, DESPEJADO, LLUVIA, DESPEJADO, LLUVIA, DESPEJADO, TORMENTA, DESPEJADO), fases);
        h.ok("tormenta-cada 1: todas tormenta", esTormenta(1, 1) && esTormenta(2, 1));
        h.ok("tormenta-cada 0: ninguna", !esTormenta(4, 0) && !esTormenta(4, -2));
        h.igual("despues de una lluvia, despejado", DESPEJADO, siguiente(LLUVIA, 3, 4));
        h.igual("despues de una tormenta, despejado", DESPEJADO, siguiente(TORMENTA, 4, 4));
        h.igual("la proxima es la cuarta: tormenta", TORMENTA, siguiente(DESPEJADO, 3, 4));

        // Duraciones con variacion.
        h.igual("20 min sin variacion", 1_200_000L, duracionMs(20, 0, 0.7));
        h.igual("20 min, azar al minimo: -20 %", 960_000L, duracionMs(20, 0.2, 0));
        h.igual("20 min, azar al maximo: +20 %", 1_440_000L, duracionMs(20, 0.2, 1));
        h.igual("5 min, azar al medio: 5 min", 300_000L, duracionMs(5, 0.2, 0.5));
        h.igual("variacion de mas: se queda en 90 % (y el minimo)", 30_000L, duracionMs(5, 5, 0));
        h.igual("variacion de mas, al maximo: +90 %", 570_000L, duracionMs(5, 5, 1));
        h.igual("0 minutos: el minimo", MINIMO_MS, duracionMs(0, 0.2, 0.5));
        boolean dentro = true;
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 1000; i++) {
            long d = duracionMs(LLUVIA_MINUTOS, VARIACION, r.nextDouble());
            dentro &= d >= 240_000L && d <= 360_000L;
        }
        h.ok("1000 lluvias de serie entre 4 y 6 min", dentro);

        // Aviso: una sola vez, solo antes de llover.
        h.ok("a 61 s no avisa", !tocaAviso(DESPEJADO, 61_000, 60_000, false));
        h.ok("a 60 s avisa", tocaAviso(DESPEJADO, 60_000, 60_000, false));
        h.ok("ya avisado: no repite", !tocaAviso(DESPEJADO, 30_000, 60_000, true));
        h.ok("lloviendo no se avisa", !tocaAviso(LLUVIA, 10_000, 60_000, false));
        h.ok("aviso 0: nunca", !tocaAviso(DESPEJADO, 0, 0, false));
        h.igual("aviso normal", "Se acerca la lluvia", plano.serialize(aviso(LLUVIA, Clima.Tipo.NINGUNO)));
        h.igual("aviso de tormenta", "Se acerca una tormenta", plano.serialize(aviso(TORMENTA, Clima.Tipo.NINGUNO)));
        h.igual("aviso en bioma verde", "Se acerca lluvia ácida", plano.serialize(aviso(TORMENTA, Clima.Tipo.ACIDA)));
        h.igual("aviso en el carmesi", "Se acerca el cielo rojo", plano.serialize(aviso(LLUVIA, Clima.Tipo.ROJO)));

        // Tiempo restante.
        h.igual("12 min", "12 min", restante(11 * 60_000L + 1));
        h.igual("justo 12 min", "12 min", restante(12 * 60_000L));
        h.igual("45 s", "45 s", restante(44_200));
        h.igual("60 s es 1 min", "1 min", restante(60_000));
        h.igual("negativo: 0 s", "0 s", restante(-5_000));
        h.igual("minutos: 7", 7.0, minutos("7"));
        h.igual("minutos: 2,5", 2.5, minutos("2,5"));
        h.igual("minutos: 0", null, minutos("0"));
        h.igual("minutos: texto", null, minutos("mucho"));
        h.igual("minutos: NaN", null, minutos("NaN"));

        ConfigurationSection c = jar == null ? null : jar.getConfigurationSection("hardcore.clima.ciclo");
        if (jar == null) {
            h.ok("sin la config del jar a mano: no se compara", true);
        } else if (c == null) {
            h.ok("el config.yml del jar trae hardcore.clima.ciclo", false);
        } else {
            h.igual("jar: activo", ACTIVO, c.getBoolean("activo", !ACTIVO));
            h.cerca("jar: despejado-minutos", DESPEJADO_MINUTOS, c.getDouble("despejado-minutos", -1), 1e-9);
            h.cerca("jar: lluvia-minutos", LLUVIA_MINUTOS, c.getDouble("lluvia-minutos", -1), 1e-9);
            h.cerca("jar: variacion", VARIACION, c.getDouble("variacion", -1), 1e-9);
            h.igual("jar: tormenta-cada", TORMENTA_CADA, c.getInt("tormenta-cada", -1));
            h.igual("jar: aviso-segundos", AVISO_SEGUNDOS, c.getInt("aviso-segundos", -1));
            h.igual("jar: cordura.pantalla", "bossbar", jar.getString("hardcore.cordura.pantalla"));
        }
        return h.lineas();
    }
}
