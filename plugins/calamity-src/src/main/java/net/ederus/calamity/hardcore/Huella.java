package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.ederus.edm.comun.Plataforma;
import net.kyori.adventure.text.Component;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * DIS sec. 1.2 · La Huella: el reloj anti-AFK que llama a la PARCA.
 *
 * La idea: una granja no se puede mover, y lo que te mueve la granja (agua, vagoneta,
 * burbujas, pistones) no cuenta como moverte tu. Cada 5 s se apunta la celda (2x3x2) en la
 * que estas, pero SOLO si ese rato has pulsado teclas de desplazamiento; si no, se repite
 * la celda anterior. "Quieto" = cuanto tiempo hacia atras cabe en 24 celdas distintas.
 * Chatear o girar la camara sin mas no cuenta nada: eso lo fabrica cualquier mod.
 * getIdleDuration() no se usa por lo mismo.
 *
 * 1.1.1, tras la primera prueba (a Dosa le vino la PARCA construyendo y volando, sin estar
 * AFK): cuentan tambien las interacciones VARIADAS (romper, poner o picar bloques, golpear
 * entidades y abrir contenedores en sitios distintos, con la camara girando sin patron). Una
 * muestra con al menos interacciones-minimas de esas se apunta como una "celda" nueva que no
 * se repite, asi que quien construye en un metro cuadrado no suma quietud. Un autoclicker
 * repite objetivo y angulo, y eso sigue sin contar. Y volando (creativo, /fly, elitros)
 * desplazarse cuenta como moverse aunque no llegue el evento de teclas.
 *
 * El nucleo (Rastro, Ajustes, Aparcamiento, congela) no toca Bukkit: recibe posicion,
 * montado, activo y hora. Asi el autotest "huella" prueba las secuencias de la tabla de
 * DIS sec. 1.2 sin jugadores. Aqui arriba solo queda lo que habla con el servidor: teclas,
 * avisos, la campana sobre la cabeza y la llamada a la PARCA.
 *
 * Coste: un long por PlayerInputEvent y O(120) cada 5 s por jugador dentro. Nada por tick.
 */
final class Huella implements Listener {

    /** Marca de MobsLethal en sus mobs ("minijefe" en los de cordura cero). Ya circula con edm:. */
    private static final NamespacedKey MOB_LETHAL = new NamespacedKey("edm", "lethal_world_mob");

    private final Hardcore hc;
    private final Map<UUID, Rastro> rastros = new HashMap<>();
    /*
     * La unica cache por UUID que NO se limpia en el quit (DIS sec. 1.2.5): un mod que
     * desconecta y reconecta solo reiniciaria el reloj cada vez. Vive en memoria
     * reconexion-minutos y un reinicio del servidor la pierde (eso no lo provoca un jugador).
     */
    private final Aparcamiento aparcadas = new Aparcamiento();
    /** La campana que flota sobre la cabeza de quien lleva 540 s quieto (la ven todos). */
    private final Map<UUID, ItemDisplay> campanas = new HashMap<>();
    private Ajustes ajustes;
    private long ajustesLeidos;
    /** Calamity 1.1.0: el AFK en la zona spawn (5 min y la Grieta en vez de 10 y la PARCA alli mismo). */
    private final Grieta grieta;

    Huella(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("huella", Huella::autotest);
        grieta = new Grieta(hc);
    }

    // ================================================================== nucleo

    /** Lo que se lee de hardcore.parca para la Huella (con los valores de DIS sec. 4 por defecto). */
    record Ajustes(boolean activa, boolean modoBloque, int minutos, int muestra, int celdaH, int celdaV,
                   int maxCeldas, double radio, double vehiculoPorcentaje, int vehiculoLado,
                   int vehiculoMuestrasMinimas, int congelarSegundos, double congelarDanoMinimo,
                   int pausaMaxima, int pausaVentanaMinutos, int reconexionMinutos, int graciaMinutos,
                   int[] avisos, double radioCampanaAjena, boolean interacciones, int interaccionesMinimas,
                   double giroMinimo, double vueloMinimo) {

        static Ajustes de(ConfigurationSection s) {
            if (s == null) s = new YamlConfiguration();
            List<Integer> lista = s.getIntegerList("avisos");
            // Cinco avisos o ninguno: cada posicion es un mensaje distinto (P-01 ... P-06).
            int[] avisos = lista.size() == 5 ? lista.stream().mapToInt(Integer::intValue).sorted().toArray()
                    : new int[]{300, 420, 510, 540, 570};
            return new Ajustes(
                    s.getBoolean("activa", true),
                    "bloque".equalsIgnoreCase(s.getString("modo", "huella")),
                    Math.max(1, s.getInt("minutos", 10)),
                    Math.max(1, s.getInt("muestra-segundos", 5)),
                    Math.max(1, s.getInt("celda-horizontal", 2)),
                    Math.max(1, s.getInt("celda-vertical", 3)),
                    Math.max(1, s.getInt("max-celdas", 24)),
                    s.getDouble("radio", 1),
                    s.getDouble("vehiculo-porcentaje", 0.80),
                    s.getInt("vehiculo-lado", 128),
                    s.getInt("vehiculo-muestras-minimas", 60),
                    s.getInt("congelar-segundos", 10),
                    s.getDouble("congelar-dano-minimo", 4),
                    s.getInt("pausa-maxima-segundos", 180),
                    Math.max(1, s.getInt("pausa-ventana-minutos", 10)),
                    s.getInt("reconexion-minutos", 30),
                    s.getInt("gracia-minutos", 5),
                    avisos,
                    s.getDouble("radio-campana-ajena", 48),
                    s.getBoolean("interacciones", true),
                    Math.max(1, s.getInt("interacciones-minimas", 2)),
                    Math.max(0, s.getDouble("giro-minimo", 1.5)),
                    Math.max(0.1, s.getDouble("vuelo-minimo", 1.0)));
        }

        /** Los de DIS sec. 4 tal cual: los usa el autotest para no depender de la config del servidor. */
        static Ajustes defecto() {
            return de(new YamlConfiguration());
        }

        /** Segundos quieto que la llaman. */
        int limite() {
            return minutos * 60;
        }

        /** Muestras del anillo: ventana / muestra (600 / 5 = 120). */
        int tamano() {
            return Math.max(1, limite() / muestra);
        }
    }

    /**
     * El estado de un jugador, sin nada de Bukkit (DIS sec. 1.2 "Estado por jugador").
     * Solo hilo principal.
     */
    static final class Rastro {
        long[] celdas;
        boolean[] montado;
        /** La muestra se apunto por interacciones variadas (no es una celda de verdad). */
        boolean[] novedad;
        /** Donde se escribe la siguiente muestra. */
        int cabeza;
        int llenas;
        long ultimaTecla;
        /** Ultima muestra que conto como activa (tambien la de Bedrock, que no manda teclas). */
        long ultimaActividad;
        boolean ultimaActiva;
        long celdaAnterior;
        boolean conCelda;
        long congeladoHasta;
        /** Millis de cada segundo en pausa, podado a pausa-ventana-minutos. */
        final ArrayDeque<Long> pausados = new ArrayDeque<>();
        /** Segundos contados (sin los pausados): marcan cuando toca muestra. */
        int segundos;
        int quieto;
        int avisoDado;
        long graciaHasta;
        // modo bloque: donde empezo a contar
        double ox, oy, oz;
        boolean conOrigen;
        // Bedrock: donde estaba en la muestra anterior
        double bx, bz;
        boolean conBedrock;
        // Vuelo: donde estaba en la muestra anterior (en 3D)
        double px, py, pz;
        boolean conPrevia;
        // Interacciones variadas: los ultimos objetivos, la camara de la ultima y lo de esta muestra
        final long[] objetivos = new long[MEMORIA_OBJETIVOS];
        int nObjetivos, cabezaObjetivos;
        float yawPrevio, pitchPrevio;
        double giroPrevio = -1;
        boolean conCamara;
        int variadas;
        long firma;
        long muestrasPorInteraccion;

        Rastro(int tamano) {
            celdas = new long[tamano];
            montado = new boolean[tamano];
            novedad = new boolean[tamano];
        }

        /** Vacia los anillos (meter, sacar, morir, cambiar de mundo, fin de una PARCA). */
        void vaciar() {
            cabeza = 0;
            llenas = 0;
            conCelda = false;
            conOrigen = false;
            conBedrock = false;
            conPrevia = false;
            variadas = 0;
            firma = 0;
            segundos = 0;
            quieto = 0;
            avisoDado = 0;
            congeladoHasta = 0;
            pausados.clear();
        }

        /** Pausa el reloj congelar-segundos: un golpe de amenaza o de un rival valido. */
        void congelar(long ahora, Ajustes a) {
            congeladoHasta = Math.max(congeladoHasta, ahora + a.congelarSegundos() * 1000L);
        }

        /** Segundos de pausa gastados en la ventana (presupuesto de pausa-maxima-segundos). */
        int pausaUsada(long ahora, Ajustes a) {
            podar(ahora, a);
            return pausados.size();
        }

        private void podar(long ahora, Ajustes a) {
            long desde = ahora - a.pausaVentanaMinutos() * 60_000L;
            while (!pausados.isEmpty() && pausados.peekFirst() <= desde) pausados.pollFirst();
        }

        /**
         * Un segundo de reloj para este jugador.
         *
         * @param activo       pulso teclas de desplazamiento en el ultimo rato (lo calcula quien llama)
         * @param bedrockSuelo Bedrock, en el suelo, sin montar y fuera del agua: vale como activo si
         *                     ademas se ha desplazado 0,2 bloques desde la muestra anterior (Geyser
         *                     puede no reenviar las teclas)
         * @return true si se tomo una muestra
         */
        boolean segundo(long ahora, double x, double y, double z, boolean enVehiculo, boolean activo,
                        boolean bedrockSuelo, Ajustes a) {
            return segundo(ahora, x, y, z, enVehiculo, activo, bedrockSuelo, false, a);
        }

        /**
         * @param volando creativo, /fly o elitros: vale como activo si se ha desplazado
         *                vuelo-minimo desde la muestra anterior, llegue o no el evento de teclas
         */
        boolean segundo(long ahora, double x, double y, double z, boolean enVehiculo, boolean activo,
                        boolean bedrockSuelo, boolean volando, Ajustes a) {
            if (celdas.length != a.tamano()) {
                // Cambio la config (minutos o muestra): se empieza de cero con el anillo nuevo.
                celdas = new long[a.tamano()];
                montado = new boolean[a.tamano()];
                novedad = new boolean[a.tamano()];
                vaciar();
            }
            podar(ahora, a);
            // Congelado y con presupuesto: el reloj se para (no se reinicia). Sin presupuesto,
            // la pausa no se aplica: dos alts pegandose no paran el reloj para siempre.
            if (ahora < congeladoHasta && pausados.size() < a.pausaMaxima()) {
                pausados.addLast(ahora);
                return false;
            }
            segundos++;
            if (a.modoBloque()) {
                // La regla literal de Dosa: se reinicia al alejarse "radio" bloques de donde empezo.
                double r = a.radio();
                if (!conOrigen || (x - ox) * (x - ox) + (y - oy) * (y - oy) + (z - oz) * (z - oz) > r * r) {
                    ox = x;
                    oy = y;
                    oz = z;
                    conOrigen = true;
                    quieto = 0;
                } else {
                    quieto++;
                }
                if (activo) ultimaActividad = ahora;
                return false;
            }
            if (segundos % a.muestra() != 0) return false;

            if (bedrockSuelo && conBedrock) {
                double dx = x - bx, dz = z - bz;
                if (dx * dx + dz * dz >= 0.04) activo = true;
            }
            bx = x;
            bz = z;
            conBedrock = true;
            // Volando no hay agua ni vagoneta que te lleve: si la posicion cambia, te mueves tu.
            if (volando && conPrevia) {
                double dx = x - px, dy = y - py, dz = z - pz;
                if (dx * dx + dy * dy + dz * dz >= a.vueloMinimo() * a.vueloMinimo()) activo = true;
            }
            px = x;
            py = y;
            pz = z;
            conPrevia = true;

            // El movimiento pasivo no ensancha la huella: sin teclas se repite la celda.
            long celda = activo || !conCelda ? clave(x, y, z, a) : celdaAnterior;
            celdaAnterior = celda;
            conCelda = true;
            // Interacciones variadas en esta muestra: se apunta algo que no se repite nunca
            // (la firma de lo que ha tocado y cuando), asi que suma una "celda" nueva.
            boolean porInteraccion = a.interacciones() && variadas >= a.interaccionesMinimas();
            celdas[cabeza] = porInteraccion ? nueva(firma, segundos) : celda;
            novedad[cabeza] = porInteraccion;
            variadas = 0;
            firma = 0;
            if (porInteraccion) {
                activo = true;
                muestrasPorInteraccion++;
            }
            montado[cabeza] = enVehiculo;
            cabeza = (cabeza + 1) % celdas.length;
            llenas = Math.min(celdas.length, llenas + 1);
            ultimaActiva = activo;
            if (activo) ultimaActividad = ahora;
            quieto = calcular(a);
            return true;
        }

        /** Quieto a partir del anillo: de la muestra mas nueva a la mas vieja hasta pasar de max-celdas. */
        int calcular(Ajustes a) {
            Set<Long> vistas = new HashSet<>();
            int recorridas = 0;
            int n = celdas.length;
            for (int i = 0; i < llenas; i++) {
                vistas.add(celdas[(cabeza - 1 - i + n) % n]);
                if (vistas.size() > a.maxCeldas()) break;
                recorridas++;
            }
            int q = recorridas * a.muestra();
            // Senal de vehiculo: casi todo el rato montado y el recorrido cabe en una caja pequena.
            // Caza el bucle de vagoneta con un mod que pulsa W (con W las celdas si cambian).
            if (llenas >= a.vehiculoMuestrasMinimas() && fraccionMontado() >= a.vehiculoPorcentaje()) {
                int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
                for (int i = 0; i < llenas; i++) {
                    // Las de interaccion no son un sitio: no cuentan para la caja del recorrido.
                    if (novedad[(cabeza - 1 - i + n) % n]) continue;
                    long k = celdas[(cabeza - 1 - i + n) % n];
                    int cx = celdaX(k), cz = celdaZ(k);
                    minX = Math.min(minX, cx);
                    maxX = Math.max(maxX, cx);
                    minZ = Math.min(minZ, cz);
                    maxZ = Math.max(maxZ, cz);
                }
                long ladoX = (long) (maxX - minX + 1) * a.celdaH();
                long ladoZ = (long) (maxZ - minZ + 1) * a.celdaH();
                if (ladoX <= a.vehiculoLado() && ladoZ <= a.vehiculoLado()) q = Math.max(q, llenas * a.muestra());
            }
            return q;
        }

        double fraccionMontado() {
            if (llenas == 0) return 0;
            int m = 0, n = celdas.length;
            for (int i = 0; i < llenas; i++) if (montado[(cabeza - 1 - i + n) % n]) m++;
            return (double) m / llenas;
        }

        int celdasDistintas() {
            return celdasUnicas().size();
        }

        /** Las celdas distintas del anillo (para pintar la huella al jugador en el aviso de 510). */
        List<Long> celdasUnicas() {
            Set<Long> vistas = new LinkedHashSet<>();
            int n = celdas.length;
            for (int i = 0; i < llenas; i++) {
                int k = (cabeza - 1 - i + n) % n;
                if (!novedad[k]) vistas.add(celdas[k]);
            }
            return new ArrayList<>(vistas);
        }

        /**
         * Una interaccion (romper, poner o picar un bloque, golpear, abrir un contenedor) contra
         * "objetivo" con la camara en yaw/pitch. Cuenta si es VARIADA: un objetivo que no esta
         * entre los ultimos MEMORIA_OBJETIVOS, y la camara ha girado al menos giro-minimo grados
         * desde la anterior sin repetir el mismo giro. Un autoclicker repite objetivo y angulo;
         * un mod que menea la camara a golpes fijos repite el giro; en una trituradora los mobs
         * mueren en el mismo bloque (el objetivo es el bloque, no el mob). True si ha contado.
         */
        boolean interaccion(long objetivo, float yaw, float pitch, Ajustes a) {
            double giro = conCamara ? Math.abs(angulo(yaw - yawPrevio)) + Math.abs(pitch - pitchPrevio) : Double.MAX_VALUE;
            boolean regular = giro != Double.MAX_VALUE && giroPrevio >= 0 && Math.abs(giro - giroPrevio) < GIRO_REGULAR;
            yawPrevio = yaw;
            pitchPrevio = pitch;
            conCamara = true;
            if (giro != Double.MAX_VALUE) giroPrevio = giro;
            for (int i = 0; i < nObjetivos; i++) if (objetivos[i] == objetivo) return false;
            objetivos[cabezaObjetivos] = objetivo;
            cabezaObjetivos = (cabezaObjetivos + 1) % objetivos.length;
            nObjetivos = Math.min(objetivos.length, nObjetivos + 1);
            if (giro < a.giroMinimo() || regular) return false;
            variadas++;
            firma = firma * 31 + objetivo;
            return true;
        }

        /** /lw hardcore parca <jugador> [segundos]: llena el anillo con la celda actual. */
        void forzar(int segundosQuieto, double x, double y, double z, Ajustes a) {
            if (celdas.length != a.tamano()) {
                celdas = new long[a.tamano()];
                montado = new boolean[a.tamano()];
                novedad = new boolean[a.tamano()];
            }
            vaciar();
            java.util.Arrays.fill(novedad, false);
            long celda = clave(x, y, z, a);
            int muestras = Math.min(celdas.length, Math.max(0, segundosQuieto / a.muestra()));
            for (int i = 0; i < muestras; i++) {
                celdas[cabeza] = celda;
                cabeza = (cabeza + 1) % celdas.length;
            }
            llenas = muestras;
            celdaAnterior = celda;
            conCelda = true;
            ox = x;
            oy = y;
            oz = z;
            conOrigen = true;
            quieto = a.modoBloque() ? segundosQuieto : calcular(a);
        }
    }

    /**
     * Una celda en un long: 26 bits de X, 12 de Y y 26 de Z (con signo). X y Z llegan a
     * +-30 M / 2 = 15 M, que cabe en 26 bits; Y / 3 no pasa de unos cientos.
     */
    static long clave(double x, double y, double z, Ajustes a) {
        long cx = Math.floorDiv((long) Math.floor(x), a.celdaH());
        long cy = Math.floorDiv((long) Math.floor(y), a.celdaV());
        long cz = Math.floorDiv((long) Math.floor(z), a.celdaH());
        return (cx << 38) | ((cy & 0xFFFL) << 26) | (cz & 0x3FFFFFFL);
    }

    /** Objetivos recordados para decidir si una interaccion es "otra" (MEMORIA_OBJETIVOS). */
    static final int MEMORIA_OBJETIVOS = 16;
    /** Dos giros de camara que se parecen menos que esto son el mismo: un mod con paso fijo. */
    static final double GIRO_REGULAR = 0.25;
    /** Tipos de interaccion (parte de la clave del objetivo). */
    static final int ROMPER = 1, PONER = 2, GOLPEAR = 3, ABRIR = 4, PICAR = 5;

    /** La clave de un objetivo: el tipo y el BLOQUE (de un mob, el bloque donde estaba). */
    static long objetivo(int tipo, int x, int y, int z) {
        long h = tipo;
        h = h * 0x100000001B3L + x;
        h = h * 0x100000001B3L + y;
        h = h * 0x100000001B3L + z;
        return h ^ (h >>> 29);
    }

    /** Una "celda" de interaccion: la firma de lo tocado mezclada con el segundo. No se repite. */
    static long nueva(long firma, int segundo) {
        long h = (firma ^ 0x9E3779B97F4A7C15L) * 0xBF58476D1CE4E5B9L + segundo;
        return h ^ (h >>> 31);
    }

    /** Diferencia de yaw en (-180, 180]. */
    static double angulo(double d) {
        double r = d % 360;
        if (r > 180) r -= 360;
        if (r <= -180) r += 360;
        return r;
    }

    static int celdaX(long k) {
        return (int) (k >> 38);
    }

    static int celdaY(long k) {
        return (int) ((k << 26) >> 52);
    }

    static int celdaZ(long k) {
        return (int) ((k << 38) >> 38);
    }

    /**
     * Si un golpe pausa el reloj (DIS sec. 1.2.3): dano final >= congelar-dano-minimo contra
     * una amenaza, un minijefe de cordura cero, u otro jugador que la Aduana da por valido.
     * Pegar a mobs normales no congela: un autoclicker en una trituradora estaria "en
     * combate" todo el rato. Dos alts de la misma huella de IP no son validos entre si.
     */
    static boolean congela(double danoFinal, double minimo, boolean amenaza, boolean minijefe,
                           boolean jugador, boolean jugadorValido) {
        if (danoFinal < minimo) return false;
        return amenaza || minijefe || (jugador && jugadorValido);
    }

    /** La huella aparcada al desconectarse, con su caducidad y el mundo donde estaba. */
    static final class Aparcamiento {
        private record Aparcada(Rastro rastro, long caduca, String mundo) {
        }

        private final Map<UUID, Aparcada> mapa = new HashMap<>();

        void guardar(UUID id, Rastro r, String mundo, long ahora, int minutos) {
            // Se poda aqui: es cuando crece, y asi no hace falta tarea propia.
            mapa.values().removeIf(x -> x.caduca() <= ahora);
            if (r == null || minutos <= 0) return;
            mapa.put(id, new Aparcada(r, ahora + minutos * 60_000L, mundo));
        }

        /** La huella si vuelve al mismo mundo antes de que caduque; null si no. Se consume. */
        Rastro sacar(UUID id, String mundo, long ahora) {
            Aparcada a = mapa.remove(id);
            if (a == null || a.caduca() <= ahora || !a.mundo().equals(mundo)) return null;
            return a.rastro();
        }

        void vaciar() {
            mapa.clear();
        }
    }

    // ============================================================= servidor

    Ajustes ajustes() {
        long ahora = System.currentTimeMillis();
        // Se relee cada 5 s: es barato, y un /lw reload se nota sin reiniciar.
        if (ajustes == null || ahora - ajustesLeidos > 5_000) {
            ajustes = Ajustes.de(hc.cfg().getConfigurationSection("parca"));
            ajustesLeidos = ahora;
        }
        return ajustes;
    }

    private Rastro rastro(Player p) {
        return rastros.computeIfAbsent(p.getUniqueId(), k -> new Rastro(ajustes().tamano()));
    }

    /**
     * Teclas de desplazamiento: lo UNICO que cuenta como moverse tu. Sneak y sprint no.
     * Tambien con parca.activa en false: las horas activas (M33) lo leen igual.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeclas(PlayerInputEvent e) {
        Player p = e.getPlayer();
        if (!hc.esHardcore(p)) return;
        if (!mueve(e.getInput())) return;
        rastro(p).ultimaTecla = System.currentTimeMillis();
    }

    private static boolean mueve(Input i) {
        return i != null && (i.isForward() || i.isBackward() || i.isLeft() || i.isRight() || i.isJump());
    }

    // ------------------------------------------------- interacciones variadas (1.1.1)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRomper(BlockBreakEvent e) {
        interaccion(e.getPlayer(), ROMPER, e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPoner(BlockPlaceEvent e) {
        interaccion(e.getPlayer(), PONER, e.getBlockPlaced());
    }

    /** Empezar a picar un bloque (golpear cosas volando, minar): cuenta igual que romperlo. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPicar(BlockDamageEvent e) {
        interaccion(e.getPlayer(), PICAR, e.getBlock());
    }

    /** Abrir un contenedor de verdad (con sitio en el mundo; los menus no cuentan). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAbrir(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Location l = e.getInventory().getLocation();
        if (l == null || l.getWorld() != p.getWorld()) return;
        interaccion(p, ABRIR, l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    private void interaccion(Player p, int tipo, Block b) {
        interaccion(p, tipo, b.getX(), b.getY(), b.getZ());
    }

    /** La lleva al Rastro con la camara de ahora. Lo mas barato primero: el mundo. */
    private void interaccion(Player p, int tipo, int x, int y, int z) {
        if (!hc.esHardcore(p)) return;
        Ajustes a = ajustes();
        if (!a.activa() || !a.interacciones()) return;
        Location l = p.getLocation();
        rastro(p).interaccion(objetivo(tipo, x, y, z), l.getYaw(), l.getPitch(), a);
    }

    /** Salir a un mundo que no es hardcore vacia la huella (DIS sec. 1.2.4). */
    @EventHandler
    public void onCambioMundo(PlayerChangedWorldEvent e) {
        if (!hc.esHardcore(e.getPlayer())) {
            reiniciar(e.getPlayer());
            rastros.remove(e.getPlayer().getUniqueId());
        }
    }

    /** Una vez por segundo por jugador que cuenta, desde Hardcore.tick. */
    void segundo(Player p) {
        Ajustes a = ajustes();
        if (!a.activa()) return;
        Rastro r = rastro(p);
        long ahora = System.currentTimeMillis();
        if (exento(p, r, ahora, a)) {
            quitarCampana(p.getUniqueId());
            return;
        }
        Entity vehiculo = p.getVehicle();
        Location pos = vehiculo != null ? vehiculo.getLocation() : p.getLocation();
        // Quien mantiene W sin soltar no genera eventos: se mira tambien la tecla de ahora.
        boolean activo = ahora - r.ultimaTecla <= a.muestra() * 1000L || mueve(p.getCurrentInput());
        boolean bedrock = vehiculo == null && p.isOnGround() && !p.isInWater() && !p.isInLava()
                && Plataforma.esBedrock(p);
        boolean volando = vehiculo == null && (p.isFlying() || p.isGliding());
        r.segundo(ahora, pos.getX(), pos.getY(), pos.getZ(), vehiculo != null, activo, bedrock, volando, a);
        avisos(p, r, a);
    }

    /**
     * No cuenta (DIS sec. 1.2.6): exento a mano (/lw hardcore exento, ya no por permiso: con
     * el comodin de LuckPerms todo el staff quedaba inmune), quien ya tiene una
     * PARCA encima, quien esta en la llegada protegida, la gracia tras una PARCA y los
     * muertos y quien canaliza el Cristal (DIS). Espectador y creativo ya los filtra
     * Hardcore.tick (cuenta).
     */
    private boolean exento(Player p, Rastro r, long ahora, Ajustes a) {
        if (p.isDead() || ahora < r.graciaHasta) return true;
        // Canalizando el Cristal se esta quieto a proposito: son 5-10 s y luego sale.
        if (hc.canalizando(p)) return true;
        if (hc.exentos() != null && hc.exentos().parca(p.getUniqueId())) return true;
        if (hc.parca() != null && hc.valor("parca", () -> hc.parca().persigue(p), false)) return true;
        // 1.2: en la zona spawn solo cuenta la Grieta. Con ella apagada (parca.spawn.activa false) no
        // cuenta nada: la PARCA de los 10 minutos no viene nunca al spawn.
        if (!grieta.ajustes().activa() && hc.enSpawn(p)) return true;
        return hc.combate() != null && hc.valor("combate", () -> hc.combate().protegido(p), false);
    }

    // --------------------------------------------------------------- avisos

    /**
     * DIS sec. 1.3. Nunca dice "AFK": dice lo que pasa. La barra de accion va por
     * Cordura.destello. La campana sobre la cabeza es a proposito: en un mundo con PvP y
     * perdida total, el AFK se convierte en presa de los demas antes de que llegue la PARCA.
     */
    private void avisos(Player p, Rastro r, Ajustes a) {
        int q = r.quieto;
        // Calamity 1.1.0: en la zona spawn el limite y los avisos son los de la Grieta (parca.spawn).
        Grieta.Umbral u = grieta.umbral(p, a);
        if (q >= u.limite() && u.spawn()) {
            boolean abierta = hc.valor("parca", () -> grieta.abrir(p, r.celdasDistintas()), false);
            if (abierta) {
                quitarCampana(p.getUniqueId());
                r.avisoDado = 0;
            }
            return;
        }
        if (q >= u.limite()) {
            // Llega (sec. 1.4). Si el tope global esta lleno, quieto se queda arriba y se
            // vuelve a intentar cada segundo: la cita no se pierde.
            if (hc.parca() != null) {
                boolean vehiculo = r.fraccionMontado() >= a.vehiculoPorcentaje();
                boolean vino = hc.valor("parca", () -> hc.parca().invocar(p, r.celdasDistintas(), vehiculo), false);
                if (vino) {
                    quitarCampana(p.getUniqueId());
                    r.avisoDado = 0;
                }
            }
            return;
        }
        int[] av = u.avisos();
        int nivel = 0;
        while (nivel < av.length && q >= av[nivel]) nivel++;

        if (nivel < r.avisoDado) {
            // Se ha movido de verdad. Solo se le dice si ya habia oido la segunda campana.
            if (nivel == 0 && r.avisoDado >= 2) {
                hc.cordura().destello(p, Component.text("Las campanas callan.", Paleta.TEXTO), 2);
                p.playSound(p.getLocation(), "block.amethyst_block.chime", SoundCategory.HOSTILE, 1f, 1.4f);
                telemetria(p, q, true);
            }
            if (nivel < 4) quitarCampana(p.getUniqueId());
            r.avisoDado = nivel;
        } else if (nivel > r.avisoDado) {
            r.avisoDado = nivel;
            aviso(p, r, nivel, a, u.spawn());
            telemetria(p, q, false);
        }

        // Lo que se repite mientras dure: latido desde el 4.o aviso, campanadas con cuenta en el 5.o.
        if (nivel >= 4) {
            moverCampana(p);
            if (r.segundos % 5 == 0) {
                p.playSound(p.getLocation(), "entity.warden.heartbeat", SoundCategory.HOSTILE, 1f, 1f);
            }
        }
        if (nivel >= 5) {
            int queda = Math.max(1, u.limite() - q - (r.segundos % a.muestra()));
            hc.cordura().destello(p, Paleta.muerte(u.spawn() ? "La Grieta" : "La Parca").append(Component.text(" · ", Paleta.SEPARADOR))
                    .append(Component.text(queda + " s", Paleta.CIFRA)), 1);
            if (r.segundos % 5 == 0) {
                double t = Math.max(0, Math.min(1, (q - av[4]) / (double) Math.max(1, u.limite() - av[4])));
                Compat.sound(p.getWorld(), p.getLocation(), "block.bell.use", 1.2f, (float) (0.9 - 0.4 * t));
            }
        }
    }

    private void aviso(Player p, Rastro r, int nivel, Ajustes a, boolean spawn) {
        if (spawn) {
            // Calamity 1.1.0: en el spawn los mismos cinco avisos con su propio texto (Grieta.aviso).
            grieta.aviso(p, nivel, a.radioCampanaAjena());
            if (nivel == 3) pintarHuella(p, r, a);
            if (nivel == 4) ponerCampana(p);
            return;
        }
        switch (nivel) {
            case 1 -> {
                hc.cordura().destello(p, Component.text("Algo empieza a contar tus respiraciones.", Paleta.TEXTO), 3);
                p.playSound(p.getLocation(), "block.bell.resonate", SoundCategory.HOSTILE, 0.3f, 0.5f);
            }
            case 2 -> {
                hc.cordura().destello(p, Component.text("Una campana suena por ti.", Paleta.TEXTO), 3);
                p.playSound(p.getLocation(), "block.bell.resonate", SoundCategory.HOSTILE, 0.5f, 0.5f);
                p.playSound(p.getLocation(), "block.bell.resonate", SoundCategory.HOSTILE, 0.5f, 0.6f);
            }
            case 3 -> {
                p.sendMessage(ComandoCalamity.mensaje("La Parca viene a por los que se quedan. Aléjate de aquí."));
                p.playSound(p.getLocation(), "block.bell.use", SoundCategory.HOSTILE, 0.8f, 0.5f);
                pintarHuella(p, r, a);
            }
            case 4 -> {
                p.showTitle(Paleta.titulo(Paleta.muerte("Muévete"), "Viene la Parca",
                        Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(750)));
                ponerCampana(p);
                // La oyen los de alrededor (volumen 1 = 16 bloques): el AFK se vuelve presa de los demas.
                Compat.sound(p.getWorld(), p.getLocation(), "block.bell.use",
                        (float) Math.max(1, a.radioCampanaAjena() / 16.0), 0.6f);
                Component ajeno = ComandoCalamity.mensaje("Una campana dobla cerca. Alguien no se ha movido.");
                for (Player o : Fx.viewersNear(p.getLocation(), a.radioCampanaAjena())) {
                    if (!o.equals(p)) o.sendMessage(ajeno);
                }
            }
            default -> {
                // El 5.o: la cuenta y las campanadas van en avisos(), cada segundo.
            }
        }
    }

    /** Particulas SOUL sobre las celdas de su huella, solo para el: el territorio que tiene que dejar. */
    private void pintarHuella(Player p, Rastro r, Ajustes a) {
        if (Compat.SOUL == null) return;
        for (long k : r.celdasUnicas()) {
            double x = celdaX(k) * (double) a.celdaH() + a.celdaH() / 2.0;
            double y = celdaY(k) * (double) a.celdaV() + 0.3;
            double z = celdaZ(k) * (double) a.celdaH() + a.celdaH() / 2.0;
            try {
                p.spawnParticle(Compat.SOUL, new Location(p.getWorld(), x, y, z), 6, 0.6, 0.2, 0.6, 0.01);
            } catch (Throwable ignorado) {
                // Una particula que el cliente no conoce no para el aviso.
            }
        }
    }

    private void ponerCampana(Player p) {
        if (campanas.containsKey(p.getUniqueId())) return;
        try {
            ItemDisplay d = Fx.itemDisplay(p.getWorld(), p.getLocation().add(0, 2.6, 0), new ItemStack(Material.BELL), 0.6f);
            d.setTeleportDuration(20);
            d.setViewRange(1.0f);
            campanas.put(p.getUniqueId(), d);
        } catch (Throwable ignorado) {
            // Sin campana visible el aviso sigue: titulo, sonido y chat.
        }
    }

    private void moverCampana(Player p) {
        ItemDisplay d = campanas.get(p.getUniqueId());
        if (d == null || !d.isValid() || d.getWorld() != p.getWorld()) {
            quitarCampana(p.getUniqueId());
            ponerCampana(p);
            return;
        }
        Location l = p.getLocation().add(0, 2.6, 0);
        l.setYaw(d.getLocation().getYaw() + 24f);
        l.setPitch(0);
        d.teleport(l);
    }

    private void quitarCampana(UUID id) {
        ItemDisplay d = campanas.remove(id);
        if (d != null) Fx.safeRemove(d);
    }

    private void telemetria(Player p, int quieto, boolean seMovio) {
        Telemetria t = hc.telemetria();
        if (t == null) return;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("segundos_quieto", quieto);
        c.put("se_movio", seMovio);
        hc.seguro("telemetria", () -> t.suceso("aviso-parca", p, c));
    }

    // ------------------------------------------------------------ ganchos

    /** Vacia la huella: meter, sacar, morir, cambiar de mundo (DIS sec. 1.2.4). */
    void reiniciar(Player p) {
        Rastro r = rastros.get(p.getUniqueId());
        if (r != null) r.vaciar();
        quitarCampana(p.getUniqueId());
    }

    /** Fin de una PARCA: vacia y deja gracia-minutos sin contar. */
    void gracia(Player p) {
        Rastro r = rastro(p);
        r.vaciar();
        r.graciaHasta = System.currentTimeMillis() + ajustes().graciaMinutos() * 60_000L;
        quitarCampana(p.getUniqueId());
    }

    /** Pausa el reloj congelar-segundos (dano verdadero de una amenaza, golpe de rival valido). */
    void congelar(Player p) {
        if (!hc.esHardcore(p)) return;
        rastro(p).congelar(System.currentTimeMillis(), ajustes());
    }

    /** Desde Hardcore.onGolpe: congela a los jugadores que dan o reciben un golpe que cuenta. */
    void alGolpe(EntityDamageByEntityEvent e) {
        if (!hc.esHardcore(e.getEntity().getWorld())) return;
        // Golpear algo a mano es una interaccion (el objetivo es el bloque donde esta: los mobs
        // de una trituradora mueren todos en el mismo sitio y no cuentan como distintos).
        if (e.getDamager() instanceof Player g) {
            Location v = e.getEntity().getLocation();
            interaccion(g, GOLPEAR, v.getBlockX(), v.getBlockY(), v.getBlockZ());
        }
        Ajustes a = ajustes();
        double fin = e.getFinalDamage();
        if (fin < a.congelarDanoMinimo()) return;
        Entity victima = e.getEntity();
        Entity autor = e.getDamager();
        if (autor instanceof Projectile pr && pr.getShooter() instanceof Entity tirador) autor = tirador;
        if (victima instanceof Player v && congelaContra(v, autor, fin, a)) congelar(v);
        if (autor instanceof Player g && !g.equals(victima) && congelaContra(g, victima, fin, a)) congelar(g);
    }

    private boolean congelaContra(Player p, Entity otro, double fin, Ajustes a) {
        if (otro == null) return false;
        boolean jugador = otro instanceof Player o && !o.equals(p);
        boolean valido = jugador && hc.aduana() != null
                && hc.valor("aduana", () -> hc.aduana().valida(p, (OfflinePlayer) otro), false);
        return congela(fin, a.congelarDanoMinimo(), Marcas.esAmenaza(otro), esMinijefe(otro), jugador, valido);
    }

    /** Si es uno de los minijefes de cordura cero de MobsLethal. */
    static boolean esMinijefe(Entity e) {
        if (e == null) return false;
        return "minijefe".equals(e.getPersistentDataContainer().get(MOB_LETHAL, PersistentDataType.STRING));
    }

    /** Si pulso teclas de desplazamiento en el ultimo minuto (horas activas, M33). */
    boolean activoEnUltimoMinuto(Player p) {
        Rastro r = rastros.get(p.getUniqueId());
        if (r == null) return false;
        long ahora = System.currentTimeMillis();
        return ahora - Math.max(r.ultimaTecla, r.ultimaActividad) <= 60_000L || mueve(p.getCurrentInput());
    }

    /** Al desconectarse: la huella se aparca en memoria reconexion-minutos. */
    void aparcar(Player p) {
        quitarCampana(p.getUniqueId());
        Rastro r = rastros.remove(p.getUniqueId());
        if (r == null || !hc.esHardcore(p)) return;
        aparcadas.guardar(p.getUniqueId(), r, p.getWorld().getKey().toString(), System.currentTimeMillis(),
                ajustes().reconexionMinutos());
    }

    /** Al volver: si es al mismo mundo y antes de que caduque, sigue contando donde lo dejo. */
    void restaurar(Player p) {
        Rastro r = aparcadas.sacar(p.getUniqueId(), p.getWorld().getKey().toString(), System.currentTimeMillis());
        if (r != null && hc.esHardcore(p)) rastros.put(p.getUniqueId(), r);
    }

    /** Segundos de quietud segun la Huella. */
    int quieto(Player p) {
        Rastro r = rastros.get(p.getUniqueId());
        return r == null ? 0 : r.quieto;
    }

    /** /lw hardcore parca <jugador> [segundos]: pone su quieto (el limite = la llama en el siguiente segundo). */
    void forzar(Player p, int segundos) {
        Location l = p.getVehicle() != null ? p.getVehicle().getLocation() : p.getLocation();
        Ajustes a = ajustes();
        Rastro r = rastro(p);
        r.forzar(segundos, l.getX(), l.getY(), l.getZ(), a);
        r.graciaHasta = 0;
        // Los avisos ya pasados no se repiten: se dan por dados hasta ese punto (los del spawn, dentro).
        int[] av = grieta.umbral(p, a).avisos();
        int nivel = 0;
        while (nivel < av.length && r.quieto >= av[nivel]) nivel++;
        r.avisoDado = nivel;
    }

    /** /lw hardcore parca info: lo que sabe la Huella de ese jugador. */
    String info(Player p) {
        Rastro r = rastros.get(p.getUniqueId());
        if (r == null) return "sin huella";
        long ahora = System.currentTimeMillis();
        Ajustes a = ajustes();
        return "quieto " + r.quieto + "/" + a.limite() + " s | celdas " + r.celdasDistintas()
                + " | muestras " + r.llenas + " | ultima activa " + (r.ultimaActiva ? "si" : "no")
                + " | montado " + Math.round(r.fraccionMontado() * 100) + " %"
                + " | pausa " + r.pausaUsada(ahora, a) + "/" + a.pausaMaxima() + " s"
                + " | por interaccion " + r.muestrasPorInteraccion
                + (ahora < r.graciaHasta ? " | gracia " + (r.graciaHasta - ahora) / 1000 + " s" : "")
                + " | modo " + (a.modoBloque() ? "bloque" : "huella")
                + (grieta.enSpawn(p) ? " | spawn: limite " + grieta.umbral(p, a).limite() + " s (Grieta)" : "");
    }

    void parar() {
        grieta.parar();
        for (UUID id : new ArrayList<>(campanas.keySet())) quitarCampana(id);
        rastros.clear();
        aparcadas.vaciar();
        HandlerList.unregisterAll(this);
    }

    // ============================================================= autotest

    /**
     * Las 8 secuencias de DIS sec. 1.2 (PLAN WP5, aceptacion 1) y las 3 de 1.1.1 (construir,
     * autoclicker, volar), sobre Rastro en memoria con los valores de DIS sec. 4 (no la config
     * del servidor, que en el Test pone minutos: 1). Una linea por secuencia: probar.py espera
     * "OK 11/11" exacto.
     */
    static List<String> autotest() {
        Ajustes a = Ajustes.defecto();
        Autotest.Hoja h = new Autotest.Hoja();
        final long t0 = 1_000_000_000L;

        // 1. Quieto en un bloque, sin teclas: 600 a los 600 s (y aun no a los 595).
        {
            Rastro r = new Rastro(a.tamano());
            int a595 = -1;
            for (int s = 1; s <= 600; s++) {
                r.segundo(t0 + s * 1000L, 10.5, 64, 10.5, false, false, false, a);
                if (s == 595) a595 = r.quieto;
            }
            h.ok("quieto -> 600 (595 s: " + a595 + ", 600 s: " + r.quieto + ")", r.quieto >= 600 && a595 < 600);
        }
        // 2. Vaiven W/S de 2 bloques con teclas: dos celdas, llega igual.
        {
            Rastro r = new Rastro(a.tamano());
            for (int s = 1; s <= 600; s++) {
                double x = (s % 2 == 0) ? 0.5 : 2.5;
                r.segundo(t0 + s * 1000L, x, 64, 0.5, false, true, false, a);
            }
            h.ok("vaiven W/S 2 bloques -> " + r.quieto, r.quieto >= 600);
        }
        // 3. Corriente de agua: se mueve sin tocar teclas; lo pasivo repite celda.
        {
            Rastro r = new Rastro(a.tamano());
            for (int s = 1; s <= 600; s++) r.segundo(t0 + s * 1000L, s * 0.7, 62, 40 + (s % 20), false, false, false, a);
            h.ok("agua pasiva -> " + r.quieto, r.quieto >= 600);
        }
        // 4. Vagoneta en bucle de 100x100 con un mod que pulsa W: las celdas cambian (sin la
        //    senal de vehiculo no llegaria: el mismo recorrido a pie no pasa del primer aviso),
        //    pero va montado y el recorrido cabe en 128 -> llega.
        {
            Rastro montada = new Rastro(a.tamano());
            Rastro andando = new Rastro(a.tamano());
            int maxAndando = 0;
            for (int s = 1; s <= 600; s++) {
                double[] pos = bucle(s * 7.3, 100);
                montada.segundo(t0 + s * 1000L, pos[0], 64, pos[1], true, true, false, a);
                andando.segundo(t0 + s * 1000L, pos[0], 64, pos[1], false, true, false, a);
                maxAndando = Math.max(maxAndando, andando.quieto);
            }
            h.ok("vagoneta con W en caja <= 128 -> senal de vehiculo (" + montada.quieto + "; a pie "
                    + maxAndando + ")", montada.quieto >= 600 && maxAndando < a.avisos()[0]);
        }
        // 5. Linea recta a 1 bloque/s con teclas: nunca (ni el primer aviso en 20 min).
        {
            Rastro r = new Rastro(a.tamano());
            int max = 0;
            for (int s = 1; s <= 1200; s++) {
                r.segundo(t0 + s * 1000L, s, 64, 0.5, false, true, false, a);
                max = Math.max(max, r.quieto);
            }
            h.ok("linea recta 1 b/s -> nunca (max " + max + ")", max < a.avisos()[0]);
        }
        // 6. Congelado por un rival valido todo el rato: la pausa se queda en 180 s por
        //    ventana de 10 min, asi que a los 600 s de reloj lleva 420 quieto, no 0.
        {
            Rastro r = new Rastro(a.tamano());
            for (int s = 1; s <= 600; s++) {
                long ahora = t0 + s * 1000L;
                if (congela(6, a.congelarDanoMinimo(), false, false, true, true)) r.congelar(ahora, a);
                r.segundo(ahora, 10.5, 64, 10.5, false, false, false, a);
            }
            int pausa = r.pausaUsada(t0 + 600_000L, a);
            h.ok("congelar con rival valido -> pausa " + pausa + " <= " + a.pausaMaxima() + ", quieto " + r.quieto,
                    pausa == a.pausaMaxima() && r.quieto == 600 - a.pausaMaxima());
        }
        // 7. Sin rival valido (misma huella de IP) ni golpe gordo: no pausa, llega a 600. Y un
        //    minijefe si congelaria.
        {
            Rastro r = new Rastro(a.tamano());
            boolean alguna = false;
            for (int s = 1; s <= 600; s++) {
                long ahora = t0 + s * 1000L;
                boolean c = congela(6, a.congelarDanoMinimo(), false, false, true, false)
                        || congela(3, a.congelarDanoMinimo(), true, false, false, false);
                if (c) {
                    alguna = true;
                    r.congelar(ahora, a);
                }
                r.segundo(ahora, 10.5, 64, 10.5, false, false, false, a);
            }
            h.ok("sin rival valido -> no pausa (" + r.quieto + ")", !alguna && r.quieto >= 600
                    && congela(6, a.congelarDanoMinimo(), false, true, false, false));
        }
        // 8. Desconexion de 40 s y vuelta al mismo mundo: sigue contando; a los 31 min, no.
        {
            Aparcamiento ap = new Aparcamiento();
            UUID u = Autotest.sintetico(1);
            Rastro r = new Rastro(a.tamano());
            for (int s = 1; s <= 300; s++) r.segundo(t0 + s * 1000L, 10.5, 64, 10.5, false, false, false, a);
            ap.guardar(u, r, "lethal_world:calamity", t0 + 300_000L, a.reconexionMinutos());
            Rastro vuelta = ap.sacar(u, "lethal_world:calamity", t0 + 340_000L);
            int q = -1;
            if (vuelta != null) {
                for (int k = 1; k <= 300; k++) {
                    vuelta.segundo(t0 + 340_000L + k * 1000L, 10.5, 64, 10.5, false, false, false, a);
                }
                q = vuelta.quieto;
            }
            ap.guardar(u, new Rastro(a.tamano()), "lethal_world:calamity", t0, a.reconexionMinutos());
            boolean caduca = ap.sacar(u, "lethal_world:calamity", t0 + 31 * 60_000L) == null;
            h.ok("desconexion 40 s y vuelta -> sigue contando (" + q + ")", vuelta == r && q >= 600 && caduca);
        }
        // 9. Humano que construye 10 min sin salir de dos celdas: pone y rompe un bloque por
        //    segundo en sitios distintos, con la camara yendo de un lado a otro sin patron, y
        //    solo pisa teclas cada medio minuto. Era el falso positivo de la primera prueba.
        {
            Rastro r = new Rastro(a.tamano());
            int max = 0;
            long semilla = 7;
            for (int s = 1; s <= 600; s++) {
                semilla = semilla * 6364136223846793005L + 1442695040888963407L;
                int bx = (int) ((semilla >>> 33) % 7) - 3, by = 64 + (int) ((semilla >>> 20) % 4);
                int bz = (int) ((semilla >>> 45) % 7) - 3;
                float yaw = (float) (((semilla >>> 13) % 3600) / 10.0);
                float pitch = (float) (((semilla >>> 7) % 900) / 10.0 - 45);
                r.interaccion(objetivo(s % 2 == 0 ? ROMPER : PONER, bx, by, bz), yaw, pitch, a);
                double x = (s / 30) % 2 == 0 ? 0.5 : 2.5;
                r.segundo(t0 + s * 1000L, x, 64, 0.5, false, s % 30 == 0, false, a);
                max = Math.max(max, r.quieto);
            }
            h.ok("humano construyendo en 2 celdas -> no llega (max " + max + ")", max < a.limite());
        }
        // 10. Autoclicker contra el mismo bloque: con la camara fija, y con un mod que ademas
        //     menea la camara al azar. Mismo objetivo = no cuenta: llega igual.
        {
            Rastro fijo = new Rastro(a.tamano());
            Rastro meneo = new Rastro(a.tamano());
            long semilla = 11;
            for (int s = 1; s <= 600; s++) {
                for (int k = 0; k < 5; k++) {
                    semilla = semilla * 6364136223846793005L + 1442695040888963407L;
                    fijo.interaccion(objetivo(PICAR, 10, 64, 10), 90f, 30f, a);
                    meneo.interaccion(objetivo(GOLPEAR, 10, 64, 10), (float) ((semilla >>> 40) % 360), 20f, a);
                }
                fijo.segundo(t0 + s * 1000L, 10.5, 64, 10.5, false, false, false, a);
                meneo.segundo(t0 + s * 1000L, 10.5, 64, 10.5, false, false, false, a);
            }
            h.ok("autoclicker en el mismo bloque -> llega (" + fijo.quieto + "; meneando la camara " + meneo.quieto + ")",
                    fijo.quieto >= 600 && meneo.quieto >= 600);
        }
        // 11. Volando sin eventos de teclas: desplazandose por un recorrido grande no llega;
        //     parado en el aire, si.
        {
            Rastro vuela = new Rastro(a.tamano());
            Rastro flota = new Rastro(a.tamano());
            int max = 0;
            for (int s = 1; s <= 600; s++) {
                double[] pos = bucle(s * 1.5, 150);
                vuela.segundo(t0 + s * 1000L, pos[0], 90, pos[1], false, false, false, true, a);
                flota.segundo(t0 + s * 1000L, 10.5, 90, 10.5, false, false, false, true, a);
                max = Math.max(max, vuela.quieto);
            }
            h.ok("volando desplazandose -> no llega (max " + max + "); quieto en el aire -> llega (" + flota.quieto + ")",
                    max < a.limite() && flota.quieto >= 600);
        }
        return h.lineas();
    }

    /** Un punto a "d" bloques por el borde de un cuadrado de lado "lado" (bucle de vagoneta). */
    private static double[] bucle(double d, double lado) {
        double t = d % (lado * 4);
        if (t < lado) return new double[]{t, 0};
        if (t < 2 * lado) return new double[]{lado, t - lado};
        if (t < 3 * lado) return new double[]{lado - (t - 2 * lado), lado};
        return new double[]{0, lado - (t - 3 * lado)};
    }
}
