package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Calamity 1.7 · Cuanto mas lejos del spawn, mas fuertes los mobs.
 *
 * Los mobs que salen para un jugador suben +1 nivel por cada hardcore.distancia.bloques-por-nivel
 * (100) bloques de distancia HORIZONTAL al borde de la zona spawn de su mundo, hasta
 * tope-niveles (40). Dentro de la zona, 0. Un mundo hardcore sin zona spawn se mide al spawn
 * del mundo (World#getSpawnLocation). La posicion que cuenta es la del jugador.
 *
 * Donde se suma: en MobsLethal.nivelPara (esbirros, adoptados, guarniciones y minijefes de
 * cordura), por Hardcore.bonusDistancia. NO en Hardcore.bonusNivel: esa suma la usan tambien
 * MobsLethal.nivelCalamity, la PARCA (Parca.nivelCalamity) y la foto de la muerte que fija el
 * nivel del Eco (FotoMuerte), y los dos tienen que quedarse como estaban.
 *
 * Cada mob apunta en su PDC los niveles de distancia con los que nacio (MobsLethal): al morir,
 * sus MobCoins suben un mobcoins-por-nivel (1 %) por cada uno, sin mirar donde esta quien lo mata.
 *
 * Tambien vive aqui lo que el tiempo dentro endurece (dificultad.nivel-cada-minutos y
 * dificultad.mobs-extra-cada-minutos): Hardcore.bonusNivel y bonusTope lo piden a las
 * funciones puras de abajo para que el autotest pruebe lo mismo que corre en el servidor.
 *
 * El aviso: cuando el bonus cruza una franja de aviso-cada-niveles (5) niveles, un destello en
 * la barra de accion (nunca un titulo) que dura aviso-segundos (5) y respeta la reserva de la barra
 * (BarraAccion). Se mira en el tick de 1 s de Hardcore, sin tarea propia,
 * y nunca dentro de la zona spawn. Para que quien se pasee por el borde de una franja no llene la
 * barra, entre dos avisos pasan al menos PAUSA_AVISO_MS; si en ese rato vuelve a la franja ya
 * avisada no se dice nada, y si sigue en otra se le dice al acabar la pausa.
 *
 * El nucleo (bloques, niveles, mobsExtraTiempo, nivelPorMinutos, factorMobcoins, mobcoins,
 * Aviso.decidir) es estatico y sin Bukkit: el autotest "distancia" lo prueba.
 */
public final class Distancia {

    /** Lo minimo entre dos avisos de franja al mismo jugador. */
    static final long PAUSA_AVISO_MS = 8_000;
    /**
     * Segundos que se queda el aviso en la barra si hardcore.distancia.aviso-segundos no dice otra
     * cosa. 1.8.2: 5 (eran 3 y "duran poquisimo", Dosa).
     */
    static final int SEGUNDOS_AVISO = 5;

    /** Lo que se lee de hardcore.distancia. */
    record Ajustes(boolean activa, int bloquesPorNivel, int tope, double mobcoinsPorNivel, int avisoCada,
                   int avisoSegundos) {

        /** Sin aviso-segundos: los de serie (el autotest y lo que ya habia). */
        Ajustes(boolean activa, int bloquesPorNivel, int tope, double mobcoinsPorNivel, int avisoCada) {
            this(activa, bloquesPorNivel, tope, mobcoinsPorNivel, avisoCada, SEGUNDOS_AVISO);
        }

        static Ajustes de(ConfigurationSection c) {
            if (c == null) return defecto();
            return new Ajustes(c.getBoolean("activa", true), c.getInt("bloques-por-nivel", 100),
                    c.getInt("tope-niveles", 40), c.getDouble("mobcoins-por-nivel", 0.01),
                    c.getInt("aviso-cada-niveles", 5), Math.max(1, c.getInt("aviso-segundos", SEGUNDOS_AVISO)));
        }

        /** Los de serie, los del config.yml del jar. El autotest los usa para no depender del servidor. */
        static Ajustes defecto() {
            return new Ajustes(true, 100, 40, 0.01, 5, SEGUNDOS_AVISO);
        }
    }

    private final Hardcore hc;
    /** Lo que se le ha dicho a cada uno de los que estan dentro. Se poda en cada tick. */
    private final Map<UUID, Aviso> avisos = new HashMap<>();

    Distancia(Hardcore hc) {
        this.hc = hc;
        Autotest.registrar("distancia", Distancia::autotest);
    }

    Ajustes ajustes() {
        return Ajustes.de(hc.cfg().getConfigurationSection("distancia"));
    }

    // ================================================================== consultas

    /**
     * Bloques en horizontal de ese sitio al borde de la zona spawn de su mundo (0 dentro), o al
     * spawn del mundo si no tiene zona. La zona sale del mapa ya leido de ZonaSpawn: no se busca
     * la region en cada llamada.
     */
    double bloques(Location l) {
        if (l == null || l.getWorld() == null) return 0;
        World w = l.getWorld();
        ZonaSpawn zs = hc.zonaSpawn();
        ZonaSpawn.Zona z = zs == null ? null : zs.de(w);
        if (z != null) return bloques(z, 0, 0, l.getX(), l.getZ());
        Location s = w.getSpawnLocation();
        return bloques(null, s.getX(), s.getZ(), l.getX(), l.getZ());
    }

    /** Los niveles de distancia de ese jugador ahora mismo (0 fuera de Calamity o con activa: false). */
    int niveles(Player p) {
        if (p == null || !hc.esHardcore(p)) return 0;
        Ajustes a = ajustes();
        if (!a.activa()) return 0;
        return niveles(bloques(p.getLocation()), a);
    }

    // ================================================================== aviso

    /** Lo del tick de 1 s de Hardcore: si ha cruzado una franja, el destello en la barra. */
    void segundo(Player p, boolean enSpawn) {
        Ajustes a = ajustes();
        int n = !a.activa() || enSpawn ? 0 : niveles(bloques(p.getLocation()), a);
        Aviso av = avisos.computeIfAbsent(p.getUniqueId(), k -> new Aviso());
        String que = av.decidir(n, enSpawn, a, System.currentTimeMillis());
        if (que == null) return;
        hc.cordura().destello(p, componente(que, n, a), a.avisoSegundos());
    }

    /** Los que ya no estan dentro se olvidan: al volver se empieza sin avisar. */
    void podar(Set<UUID> dentro) {
        avisos.keySet().retainAll(dentro);
    }

    /** "Calamity · Te alejas del spawn · mobs +10 niveles", con la Paleta. */
    private static Component componente(String que, int niveles, Ajustes a) {
        TextColor cifra = que.equals(Aviso.TOPE) ? Paleta.AVISO : Paleta.CIFRA;
        return Paleta.prefijo()
                .append(Component.text(que, que.equals(Aviso.TOPE) ? Paleta.AVISO : Paleta.TEXTO))
                .append(Component.text(" · ", Paleta.SEPARADOR))
                .append(Component.text(textoNiveles(niveles), cifra));
    }

    /** "mobs +10 niveles", "mobs +1 nivel", "mobs sin niveles de más". */
    static String textoNiveles(int n) {
        if (n <= 0) return "mobs sin niveles de más";
        return "mobs +" + n + (n == 1 ? " nivel" : " niveles");
    }

    /**
     * La franja que ya se le ha dicho a un jugador y cuando. decidir() es puro (el reloj entra
     * por parametro) para que el autotest recorra secuencias enteras.
     */
    static final class Aviso {
        /*
         * Dicen lo que se mide (la distancia al spawn), no una metafora: "Te acercas al refugio"
         * hablaba de un refugio que no existe y "Tierra sin retorno" prometia algo falso (se
         * vuelve igual). En el tope, que mas lejos ya no sube.
         */
        static final String LEJOS = "Te alejas del spawn";
        static final String CERCA = "Te acercas al spawn";
        static final String TOPE = "Has llegado al máximo";

        /** -1 = aun no se sabe (acaba de entrar): la primera medida se apunta sin avisar. */
        int anunciada = -1;
        long ultimo;

        /**
         * Lo que hay que decir con estos niveles, o null. Dentro de la zona spawn nunca se dice
         * nada, pero se apunta la franja 0: al salir no hay aviso hasta cruzar la primera.
         */
        String decidir(int niveles, boolean enSpawn, Ajustes a, long ahora) {
            if (a.avisoCada() <= 0 || !a.activa()) {
                anunciada = -1;
                return null;
            }
            int f = franja(niveles, a);
            if (anunciada < 0 || enSpawn) {
                anunciada = f;
                return null;
            }
            if (f == anunciada) return null;
            if (ultimo != 0 && ahora - ultimo < PAUSA_AVISO_MS) return null;
            boolean aleja = f > anunciada;
            anunciada = f;
            ultimo = ahora;
            if (aleja && a.tope() > 0 && niveles >= a.tope()) return TOPE;
            return aleja ? LEJOS : CERCA;
        }

        /** Franja de aviso: niveles / aviso-cada-niveles, y el tope es una franja aparte. */
        static int franja(int niveles, Ajustes a) {
            if (a.tope() > 0 && niveles >= a.tope()) return Integer.MAX_VALUE / 2;
            return Math.max(0, niveles) / Math.max(1, a.avisoCada());
        }
    }

    // ================================================================== nucleo (sin Bukkit)

    /**
     * Bloques en horizontal de (x, z) al borde de la caja de la zona (bordes de bloque incluidos:
     * la caja va de x1 a x2 + 1), 0 dentro. Con un poligono cuenta su caja. Sin zona, la distancia
     * al punto (sx, sz), el spawn del mundo.
     */
    static double bloques(ZonaSpawn.Zona zona, double sx, double sz, double x, double z) {
        if (zona == null) return Math.hypot(x - sx, z - sz);
        double dx = Math.max(0, Math.max(zona.x1() - x, x - (zona.x2() + 1)));
        double dz = Math.max(0, Math.max(zona.z1() - z, z - (zona.z2() + 1)));
        return Math.hypot(dx, dz);
    }

    /** +1 por cada bloques-por-nivel, hasta el tope. 0 con activa: false. */
    static int niveles(double bloques, Ajustes a) {
        if (!a.activa() || a.bloquesPorNivel() <= 0 || a.tope() <= 0 || bloques <= 0) return 0;
        // El 1e-9 es para que 100 bloques exactos (que pueden llegar como 99,99999...) den 1.
        int n = (int) Math.floor(bloques / a.bloquesPorNivel() + 1e-9);
        return Math.max(0, Math.min(a.tope(), n));
    }

    /** dificultad.nivel-cada-minutos: +1 nivel por cada tantos minutos de sesion. 0 lo apaga. */
    static int nivelPorMinutos(int segundosDentro, int cadaMinutos) {
        return cadaMinutos > 0 ? Math.max(0, segundosDentro) / (cadaMinutos * 60) : 0;
    }

    /** dificultad.mobs-extra-cada-minutos: +1 mob alrededor por cada tantos minutos, hasta el tope. */
    static int mobsExtraTiempo(int segundosDentro, int cadaMinutos, int tope) {
        if (cadaMinutos <= 0 || tope <= 0) return 0;
        return Math.min(tope, Math.max(0, segundosDentro) / (cadaMinutos * 60));
    }

    /** Lo que multiplica las MobCoins un mob que nacio con esos niveles de distancia. */
    public static double factorMobcoins(int nivelesDistancia, double porNivel) {
        return 1 + Math.max(0, nivelesDistancia) * Math.max(0, porNivel);
    }

    /**
     * La cuenta de MobsLethal.mobcoinsDe: base x (1 + nivel / divisor) x mundo x clase x distancia.
     */
    public static long mobcoins(double base, int nivel, double divisor, double mundo, double clase,
                                int nivelesDistancia, double porNivel) {
        double m = base * (1 + nivel / Math.max(1.0, divisor)) * mundo * clase
                * factorMobcoins(nivelesDistancia, porNivel);
        return Math.round(m);
    }

    /** Un numero del config.yml que va dentro del jar (-1 si no esta o no se puede leer). */
    static int delJar(String ruta) {
        try (java.io.InputStream in = Distancia.class.getClassLoader().getResourceAsStream("config.yml")) {
            if (in == null) return -1;
            org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            return y.isInt(ruta) ? y.getInt(ruta) : -1;
        } catch (java.io.IOException | RuntimeException e) {
            return -1;
        }
    }

    /** 1234 -> "1.234". */
    static String miles(long n) {
        String s = Long.toString(Math.abs(n));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append('.');
            b.append(s.charAt(i));
        }
        return (n < 0 ? "-" : "") + b;
    }

    // ================================================================== autotest

    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        Ajustes a = Ajustes.defecto();
        // La region del spawn de calamity2: 215 1 -278 a 318 382 -174.
        ZonaSpawn.Zona r = ZonaSpawn.Zona.caja(ZonaSpawn.REGION, "calamity", "calamity2", 318, 382, -174, 215, 1, -278);

        h.igual("dentro de la zona = 0", 0, niveles(bloques(r, 0, 0, 266.5, -226.5), a));
        h.cerca("dentro de la zona: 0 bloques", 0, bloques(r, 0, 0, 266.5, -226.5), 1e-9);
        h.cerca("justo en el borde este (x = 319): 0 bloques", 0, bloques(r, 0, 0, 319.0, -200), 1e-9);
        h.cerca("justo en el borde oeste (x = 215): 0 bloques", 0, bloques(r, 0, 0, 215.0, -200), 1e-9);
        h.igual("justo en el borde = 0 niveles", 0, niveles(bloques(r, 0, 0, 319.0, -200), a));
        h.igual("a 99 bloques del borde = 0", 0, niveles(bloques(r, 0, 0, 319 + 99, -200), a));
        h.igual("a 99,9 bloques del borde = 0", 0, niveles(bloques(r, 0, 0, 319 + 99.9, -200), a));
        h.igual("a 100 bloques del borde = 1", 1, niveles(bloques(r, 0, 0, 319 + 100, -200), a));
        h.igual("a 100 bloques por el norte = 1", 1, niveles(bloques(r, 0, 0, 266, -278 - 100), a));
        h.cerca("en diagonal desde la esquina: 300 y 400 dan 500", 500,
                bloques(r, 0, 0, 319 + 300, -173 + 400), 1e-9);
        h.igual("a 500 bloques = 5", 5, niveles(500, a));
        h.igual("a 3.999 bloques = 39", 39, niveles(3999, a));
        h.igual("a 4.000 bloques = 40 (tope)", 40, niveles(4000, a));
        h.igual("lejos del tope (20.000 bloques) = 40", 40, niveles(bloques(r, 0, 0, 20_000, -200), a));
        h.cerca("mundo sin zona: se mide al spawn del mundo", 250, bloques(null, 100, 100, 250, 300), 1e-9);
        h.igual("mundo sin zona: 250 bloques del spawn = 2", 2, niveles(bloques(null, 100, 100, 250, 300), a));
        h.igual("mundo sin zona: en el spawn = 0", 0, niveles(bloques(null, 8, 8, 8, 8), a));
        Ajustes apagada = new Ajustes(false, 100, 40, 0.01, 5);
        h.igual("activa: false da 0", 0, niveles(5000, apagada));
        h.igual("bloques-por-nivel 0 da 0", 0, niveles(5000, new Ajustes(true, 0, 40, 0.01, 5)));
        h.igual("tope 10 con 5.000 bloques = 10", 10, niveles(5000, new Ajustes(true, 100, 10, 0.01, 5)));

        // Mobs de mas por tiempo: cada 15 min, tope 4.
        h.igual("mobs extra con 0 min = 0", 0, mobsExtraTiempo(0, 15, 4));
        h.igual("mobs extra con 14:59 = 0", 0, mobsExtraTiempo(14 * 60 + 59, 15, 4));
        h.igual("mobs extra con 15:00 = 1", 1, mobsExtraTiempo(15 * 60, 15, 4));
        h.igual("mobs extra con 60 min = 4", 4, mobsExtraTiempo(60 * 60, 15, 4));
        h.igual("mobs extra con 2 h = 4 (tope)", 4, mobsExtraTiempo(120 * 60, 15, 4));
        h.igual("mobs extra con cada 0 = apagado", 0, mobsExtraTiempo(120 * 60, 0, 4));

        // Nivel por minutos con 3.
        h.igual("nivel por minutos (3): 2:59 = 0", 0, nivelPorMinutos(179, 3));
        h.igual("nivel por minutos (3): 3:00 = 1", 1, nivelPorMinutos(180, 3));
        h.igual("nivel por minutos (3): 30 min = 10", 10, nivelPorMinutos(30 * 60, 3));
        h.igual("nivel por minutos (3): 2 h = 40", 40, nivelPorMinutos(120 * 60, 3));
        h.igual("nivel por minutos 0 = apagado", 0, nivelPorMinutos(120 * 60, 0));

        // MobCoins: base 2, nivel 20, divisor 20, mundo 1, comun -> 4; con 40 de distancia, +40 %.
        h.igual("MobCoins sin bonus de distancia", 4L, mobcoins(2, 20, 20, 1.0, 1.0, 0, 0.01));
        h.igual("MobCoins con 40 niveles de distancia (+40 %)", 6L, mobcoins(2, 20, 20, 1.0, 1.0, 40, 0.01));
        h.cerca("factor con 40 niveles = 1,40", 1.40, factorMobcoins(40, 0.01), 1e-9);
        h.cerca("factor con 10 niveles = 1,10", 1.10, factorMobcoins(10, 0.01), 1e-9);
        h.cerca("factor sin distancia = 1", 1.0, factorMobcoins(0, 0.01), 1e-9);
        h.igual("MobCoins de un destacado con 25 de distancia (10 x 3 x 1,25)", 38L,
                mobcoins(5, 20, 20, 1.0, 3.0, 25, 0.01));

        // Franjas del aviso (cada 5 niveles), con el reloj a mano.
        Aviso av = new Aviso();
        long t = 1_000_000L;
        h.igual("aviso: la primera medida no avisa", null, av.decidir(3, false, a, t));
        h.igual("aviso: dentro de la misma franja (0-4) no avisa", null, av.decidir(4, false, a, t += 1000));
        h.igual("aviso: al pasar a 5 avisa al alejarse", Aviso.LEJOS, av.decidir(5, false, a, t += 1000));
        h.igual("aviso: 6 y 9 no avisan (misma franja)", null,
                av.decidir(6, false, a, t += 10_000) == null ? av.decidir(9, false, a, t += 10_000) : "avisó en 6");
        h.igual("aviso: al pasar a 10 avisa", Aviso.LEJOS, av.decidir(10, false, a, t += 10_000));
        h.igual("aviso: vaiven 10 -> 9 antes de la pausa no avisa", null, av.decidir(9, false, a, t += 1000));
        h.igual("aviso: y volver a 10 tampoco (ya estaba dicho)", null, av.decidir(10, false, a, t += 1000));
        h.igual("aviso: bajar a 9 pasada la pausa avisa al acercarse", Aviso.CERCA, av.decidir(9, false, a, t += 10_000));
        h.igual("aviso: al llegar al tope, tierra sin retorno", Aviso.TOPE, av.decidir(40, false, a, t += 10_000));
        h.igual("aviso: en el tope no se repite", null, av.decidir(40, false, a, t += 10_000));
        h.igual("aviso: al salir del tope (39) avisa al acercarse", Aviso.CERCA, av.decidir(39, false, a, t += 10_000));
        h.igual("aviso: en la zona spawn nunca avisa", null, av.decidir(0, true, a, t += 10_000));
        h.igual("aviso: al salir de la zona (0-4) no avisa", null, av.decidir(2, false, a, t += 10_000));
        h.igual("aviso: y la primera franja fuera si", Aviso.LEJOS, av.decidir(5, false, a, t += 10_000));
        Aviso apagado = new Aviso();
        Ajustes sinAviso = new Ajustes(true, 100, 40, 0.01, 0);
        apagado.decidir(0, false, sinAviso, t);
        h.igual("aviso-cada-niveles 0 no avisa nunca", null, apagado.decidir(40, false, sinAviso, t + 60_000));

        h.igual("texto de 10 niveles", "mobs +10 niveles", textoNiveles(10));
        h.igual("texto de 1 nivel", "mobs +1 nivel", textoNiveles(1));
        h.igual("miles", "4.000", miles(4000));
        h.igual("miles de un numero corto", "999", miles(999));
        h.igual("miles de un millon", "1.234.567", miles(1_234_567));

        // 1.8.2 · El aviso dura 5 s de serie y se puede cambiar (hardcore.distancia.aviso-segundos).
        h.igual("aviso: dura 5 s de serie", 5, a.avisoSegundos());
        org.bukkit.configuration.file.YamlConfiguration sinClave = new org.bukkit.configuration.file.YamlConfiguration();
        sinClave.set("activa", true);
        h.igual("aviso: sin aviso-segundos en el config, 5 s", 5, Ajustes.de(sinClave).avisoSegundos());
        org.bukkit.configuration.file.YamlConfiguration ocho = new org.bukkit.configuration.file.YamlConfiguration();
        ocho.set("aviso-segundos", 8);
        h.igual("aviso: aviso-segundos 8 lo deja 8 s", 8, Ajustes.de(ocho).avisoSegundos());
        h.igual("aviso: el config.yml del jar trae aviso-segundos 5", 5, delJar("hardcore.distancia.aviso-segundos"));
        return h.lineas();
    }
}
