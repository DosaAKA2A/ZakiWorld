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
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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

    /**
     * Lo que se lee de hardcore.parca para la Huella (con los valores de DIS sec. 4 por defecto,
     * salvo los minutos y sus avisos: 5 desde la 1.8.1, como el config.yml del jar).
     */
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
                    : new int[]{150, 210, 255, 270, 285};
            return new Ajustes(
                    s.getBoolean("activa", true),
                    "bloque".equalsIgnoreCase(s.getString("modo", "huella")),
                    Math.max(1, s.getInt("minutos", 5)),
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

        /**
         * Los de DIS sec. 4 tal cual (10 minutos y sus avisos): los usa el autotest para no
         * depender de la config del servidor ni de los 5 minutos de serie.
         */
        static Ajustes defecto() {
            YamlConfiguration dis = new YamlConfiguration();
            dis.set("minutos", 10);
            dis.set("avisos", List.of(300, 420, 510, 540, 570));
            return de(dis);
        }

        /** Segundos quieto que la llaman. */
        int limite() {
            return minutos * 60;
        }

        /** Los mismos con otra ventana y sus avisos (1.8.2: la de la Grieta en la zona spawn). */
        Ajustes ventana(int otrosMinutos, int[] otrosAvisos) {
            return new Ajustes(activa, modoBloque, Math.max(1, otrosMinutos), muestra, celdaH, celdaV, maxCeldas,
                    radio, vehiculoPorcentaje, vehiculoLado, vehiculoMuestrasMinimas, congelarSegundos,
                    congelarDanoMinimo, pausaMaxima, pausaVentanaMinutos, reconexionMinutos, graciaMinutos,
                    otrosAvisos, radioCampanaAjena, interacciones, interaccionesMinimas, giroMinimo, vueloMinimo);
        }

        /** Muestras del anillo: ventana / muestra (600 / 5 = 120). */
        int tamano() {
            return Math.max(1, limite() / muestra);
        }

        /**
         * Celdas distintas que puede sumar alguien y seguir contando como quieto: max-celdas, pero
         * nunca mas de una por cada MUESTRAS_POR_CELDA muestras del anillo (la proporcion de DIS
         * sec. 4: 24 de 120). Con los 10 minutos de serie es max-celdas tal cual. Sin este tope,
         * una ventana corta (el SurvivalTest va con minutos: 1, 12 muestras) no puede tener nunca
         * mas de 24 celdas distintas, asi que "quieto" llegaba al limite a los 60 s aunque el
         * jugador fuera en linea recta: la PARCA le venia viajando.
         */
        int celdasMaximas() {
            return Math.max(1, Math.min(maxCeldas, tamano() / MUESTRAS_POR_CELDA));
        }
    }

    /** Como mucho una celda "quieta" por cada tantas muestras del anillo (24 de 120 = 1 de 5). */
    static final int MUESTRAS_POR_CELDA = 5;

    /** 1.8.2 · Donde cuenta el Rastro: aun no se sabe, fuera del spawn o en la zona spawn. */
    static final int ZONA_NINGUNA = 0, ZONA_FUERA = 1, ZONA_SPAWN = 2;

    /**
     * 1.8.2 · Los ajustes con los que cuenta la Huella en cada sitio: fuera, los de parca; en la
     * zona spawn (con la Grieta), su ventana de parca.spawn.minutos y sus avisos, aunque la PARCA
     * de fuera tenga otra (el SurvivalTest va con parca.minutos 1 y la Grieta sigue en 5).
     */
    static Ajustes ajustesZona(Ajustes a, Grieta.Umbral u) {
        if (!u.spawn() || (u.limite() == a.limite() && java.util.Arrays.equals(u.avisos(), a.avisos()))) return a;
        return a.ventana(u.limite() / 60, u.avisos());
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
        /**
         * Su cliente ha mandado alguna vez teclas de desplazamiento en esta conexion. Un cliente
         * anterior a 1.21.2 que entra por ViaVersion no manda nunca adelante/atras/lados andando
         * (solo existian montado): sin esto, quien viaja a pie con uno de esos repite celda.
         */
        boolean conTeclas;
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
        // 1.6.1 · Pesca en la zona spawn: la ultima captura y hasta cuando dura la gracia. No se
        // borran en vaciar(): no son parte del anillo.
        long ultimaCaptura;
        boolean conCaptura;
        long pescaHasta;
        /**
         * 1.8.2 · Donde contaba en el ultimo segundo (ZONA_*) y si le han teletransportado desde
         * entonces. No se borran en vaciar(): no son parte del anillo.
         */
        int zona = ZONA_NINGUNA;
        boolean salto;
        /**
         * 1.8.2 · Segundos contados (sin los pausados ni los exentos) desde que entro en su zona.
         * No se borra en vaciar(): es de la zona, no del anillo.
         */
        int enZona;
        /**
         * 1.8.2 · Muestras tomadas fuera desde que salio del spawn por su cuenta (-1 = nada de eso):
         * la PARCA solo cuenta esas, y el anillo guarda lo de dentro por si vuelve pronto.
         */
        int trasSalir = -1;
        /** Los segundos de muestra con los que se lleno el anillo (0 = aun ninguno). */
        int muestraAnillo;

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
            trasSalir = -1;
        }

        /**
         * 1.8.2 · Si esta en la zona spawn o fuera, cuando cambia por su cuenta (con teclas,
         * volando, andando en un cliente que no manda teclas o por un teletransporte):
         * - Al entrar, la Grieta cuenta desde cero solo si ha estado fuera al menos "minimoFuera"
         *   segundos contados (lo que tarda la PARCA: si hubiera estado quieto, ya le habria
         *   llegado). Si vuelve antes, sigue contando por celdas lo de antes, como siempre.
         * - Al salir (con la Grieta encendida), la PARCA cuenta desde ahi (trasSalir) y el anillo
         *   guarda lo de dentro: si vuelve pronto, la Grieta lo tiene en cuenta.
         * Asi un vaiven con teclas por el borde no vuelve a cero a cada cruce: la Grieta se lo
         * sigue contando entero. Si lo lleva el agua o una vagoneta no cambia nada: un AFK al que
         * el agua mete y saca del spawn no se libraria nunca de ninguna de las dos. True si el
         * reloj ha vuelto a cero (hay que quitarle la campana).
         */
        boolean cambiarZona(boolean spawn, boolean propio, int minimoFuera, boolean conGrieta) {
            int z = spawn ? ZONA_SPAWN : ZONA_FUERA;
            if (zona == z) return false;
            // Un Rastro sin zona aun esta vacio: se le pone la suya y ya.
            boolean conocida = zona != ZONA_NINGUNA;
            int estuvo = enZona;
            zona = z;
            enZona = 0;
            trasSalir = -1;
            if (!conocida || !propio) return false;
            if (spawn) {
                if (estuvo < minimoFuera) return false;
                vaciar();
                return true;
            }
            // Sin Grieta el spawn no cuenta: lo de fuera sigue sumando como si no hubiera entrado.
            if (!conGrieta) return false;
            trasSalir = 0;
            quieto = 0;
            avisoDado = 0;
            return true;
        }

        /**
         * 1.8.2 · El anillo pasa a tener n muestras conservando las mas nuevas que quepan: al
         * cruzar el borde del spawn sin volver a cero (llevado por el agua, o de vuelta al poco)
         * sigue contando con la ventana de alli. Si cambio la duracion de la muestra, lo apuntado
         * ya no vale: de cero.
         */
        void redimensionar(int n, int muestra) {
            if (muestraAnillo != 0 && muestraAnillo != muestra) {
                celdas = new long[n];
                montado = new boolean[n];
                novedad = new boolean[n];
                vaciar();
                muestraAnillo = muestra;
                return;
            }
            int len = celdas.length, guardar = Math.min(llenas, n);
            long[] c = new long[n];
            boolean[] m = new boolean[n], nv = new boolean[n];
            for (int i = 0; i < guardar; i++) {
                int k = ((cabeza - guardar + i) % len + len) % len;
                c[i] = celdas[k];
                m[i] = montado[k];
                nv[i] = novedad[k];
            }
            celdas = c;
            montado = m;
            novedad = nv;
            cabeza = guardar % n;
            llenas = guardar;
            muestraAnillo = muestra;
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
         * @param bedrockSuelo cliente sin teclas (Bedrock, o uno que no ha mandado ninguna en esta
         *                     conexion), en el suelo, sin montar y fuera del agua: vale como activo si
         *                     ademas se ha desplazado 0,2 bloques desde la muestra anterior (Geyser
         *                     y los clientes viejos por ViaVersion pueden no mandar las teclas)
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
            // Recien salido del spawn el anillo no encoge: guarda lo de dentro por si vuelve pronto.
            int n = trasSalir >= 0 ? Math.max(a.tamano(), celdas.length) : a.tamano();
            if (celdas.length != n || muestraAnillo != a.muestra()) {
                // Otra ventana (la del spawn o la de fuera, o cambio la config): se guarda lo mas
                // nuevo que quepa (1.8.2; antes se empezaba de cero). Otra muestra, de cero.
                redimensionar(n, a.muestra());
                if (!a.modoBloque()) quieto = calcular(a);
            }
            podar(ahora, a);
            // Congelado y con presupuesto: el reloj se para (no se reinicia). Sin presupuesto,
            // la pausa no se aplica: dos alts pegandose no paran el reloj para siempre.
            if (ahora < congeladoHasta && pausados.size() < a.pausaMaxima()) {
                pausados.addLast(ahora);
                return false;
            }
            segundos++;
            enZona++;
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
            // Cuando toda la ventana de fuera es de despues de salir, lo de dentro ya no hace falta.
            if (trasSalir >= 0 && ++trasSalir >= a.tamano()) trasSalir = -1;
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
            int maximo = a.celdasMaximas();
            // 1.8.2 · Las de su ventana; recien salido del spawn, solo las de fuera (trasSalir).
            int tope = Math.min(llenas, a.tamano());
            if (trasSalir >= 0) tope = Math.min(tope, trasSalir);
            for (int i = 0; i < tope; i++) {
                vistas.add(celdas[(cabeza - 1 - i + n) % n]);
                if (vistas.size() > maximo) break;
                recorridas++;
            }
            int q = recorridas * a.muestra();
            // Senal de vehiculo: casi todo el rato montado y el recorrido cabe en una caja pequena.
            // Caza el bucle de vagoneta con un mod que pulsa W (con W las celdas si cambian).
            if (tope >= a.vehiculoMuestrasMinimas() && fraccionMontado(tope) >= a.vehiculoPorcentaje()) {
                int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
                for (int i = 0; i < tope; i++) {
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
                if (ladoX <= a.vehiculoLado() && ladoZ <= a.vehiculoLado()) q = Math.max(q, tope * a.muestra());
            }
            return q;
        }

        double fraccionMontado() {
            return fraccionMontado(llenas);
        }

        /** La parte de las "cuantas" muestras mas nuevas que se tomo montado. */
        double fraccionMontado(int cuantas) {
            if (cuantas <= 0) return 0;
            int m = 0, n = celdas.length;
            for (int i = 0; i < cuantas; i++) if (montado[(cabeza - 1 - i + n) % n]) m++;
            return (double) m / cuantas;
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

        /**
         * 1.6.1 · Un evento de pesca en la zona spawn (lo filtra quien llama). Una captura
         * (CAUGHT_FISH, CAUGHT_ENTITY) renueva la gracia siempre. Lanzar, que pique o recoger
         * (FISHING, BITE, REEL_IN) solo la renuevan si ha habido una captura en los ultimos
         * pesca-captura-minutos: un autoclicker que lanza y recoge sin sacar nada no frena la
         * Grieta. True si ha renovado la gracia.
         */
        boolean pesca(long ahora, boolean captura, Grieta.Ajustes g) {
            if (!g.pescaCuenta()) return false;
            if (captura) {
                ultimaCaptura = ahora;
                conCaptura = true;
            } else if (!conCaptura || ahora - ultimaCaptura > g.pescaCapturaMinutos() * 60_000L) {
                return false;
            }
            pescaHasta = Math.max(pescaHasta, ahora + g.pescaGraciaSegundos() * 1000L);
            return true;
        }

        /**
         * 1.6.1 · Si dentro de la gracia de pesca y en la zona spawn (con la Grieta encendida):
         * el reloj se reinicia (se vacia el anillo) y este segundo no suma. Fuera del spawn no
         * hace nada: para la PARCA pescar sigue siendo estar quieto. Los avisos ya dados se
         * conservan para que la Huella diga "Las campanas callan" y quite la campana.
         */
        boolean pescaReinicia(long ahora, boolean spawnGrieta, Grieta.Ajustes g) {
            if (ahora >= pescaHasta || !spawnGrieta || !g.pescaCuenta()) return false;
            int dado = avisoDado;
            vaciar();
            avisoDado = dado;
            return true;
        }

        /** /calamity reaper <player> [seconds]: llena el anillo con la celda actual. */
        void forzar(int segundosQuieto, double x, double y, double z, Ajustes a) {
            if (celdas.length != a.tamano()) {
                celdas = new long[a.tamano()];
                montado = new boolean[a.tamano()];
                novedad = new boolean[a.tamano()];
            }
            muestraAnillo = a.muestra();
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
        Rastro r = rastro(p);
        r.ultimaTecla = System.currentTimeMillis();
        r.conTeclas = true;
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

    /**
     * 1.6.1 · Pescar en la zona spawn frena la Grieta (Rastro.pesca). Sin ignoreCancelled: un
     * plugin de premios (PremioPescao) puede cancelar la captura para dar la suya, y el jugador
     * ha pescado igual. Fuera del spawn no se apunta nada: alli la pesca no cuenta.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPescar(PlayerFishEvent e) {
        PlayerFishEvent.State s = e.getState();
        boolean captura = s == PlayerFishEvent.State.CAUGHT_FISH || s == PlayerFishEvent.State.CAUGHT_ENTITY;
        if (!captura && s != PlayerFishEvent.State.FISHING && s != PlayerFishEvent.State.BITE
                && s != PlayerFishEvent.State.REEL_IN) return;
        Player p = e.getPlayer();
        if (!hc.esHardcore(p)) return;
        Grieta.Ajustes g = grieta.ajustes();
        if (!g.activa() || !g.pescaCuenta() || !hc.enSpawn(p)) return;
        rastro(p).pesca(System.currentTimeMillis(), captura, g);
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
        Entity vehiculo = p.getVehicle();
        // Quien mantiene W sin soltar no genera eventos: se mira tambien la tecla de ahora.
        boolean activo = ahora - r.ultimaTecla <= a.muestra() * 1000L || mueve(p.getCurrentInput());
        if (activo) r.conTeclas = true;
        // Andar por el suelo (sin montar y fuera del agua) vale como moverse para los clientes que
        // no mandan teclas: Bedrock (Geyser puede no reenviarlas) y los que aun no han mandado
        // ninguna en esta conexion (versiones viejas por ViaVersion). En cuanto llega una tecla se
        // vuelve a la regla estricta. El agua, las vagonetas y las burbujas siguen sin contar.
        boolean bedrock = vehiculo == null && p.isOnGround() && !p.isInWater() && !p.isInLava()
                && (Plataforma.esBedrock(p) || !r.conTeclas);
        boolean volando = vehiculo == null && (p.isFlying() || p.isGliding());
        // 1.8.2 · Entrar en el spawn por su cuenta tras un rato fuera pone el reloj de la Grieta a
        // cero, y al salir la PARCA cuenta desde ahi (Rastro.cambiarZona), tambien con la gracia o
        // la llegada protegida (antes de exento). Un vaiven por el borde no vuelve a cero.
        boolean salto = r.salto;
        r.salto = false;
        if (r.cambiarZona(hc.enSpawn(p), activo || volando || bedrock || salto, a.limite(), grieta.ajustes().activa())) {
            quitarCampana(p.getUniqueId());
        }
        if (exento(p, r, ahora, a)) {
            quitarCampana(p.getUniqueId());
            return;
        }
        Grieta.Umbral u = grieta.umbral(p, a);
        Ajustes az = ajustesZona(a, u);
        // 1.6.1 · Pescando en la zona spawn: el reloj de la Grieta vuelve a cero (solo en el spawn).
        if (ahora < r.pescaHasta && r.pescaReinicia(ahora, u.spawn(), grieta.ajustes())) {
            avisos(p, r, az, u);
            return;
        }
        Location pos = vehiculo != null ? vehiculo.getLocation() : p.getLocation();
        r.segundo(ahora, pos.getX(), pos.getY(), pos.getZ(), vehiculo != null, activo, bedrock, volando, az);
        avisos(p, r, az, u);
    }

    /**
     * 1.8.2 · Un teletransporte dentro de Calamity (la puerta, /spawn, una perla...) cuenta como
     * cambiar de sitio por su cuenta, para entrar en el spawn o salir de el (Rastro.cambiarZona).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Location a = e.getTo();
        if (a == null || !hc.esHardcore(a.getWorld())) return;
        Rastro r = rastros.get(e.getPlayer().getUniqueId());
        if (r != null) r.salto = true;
    }

    /**
     * No cuenta (DIS sec. 1.2.6): exento a mano (/calamity exempt, ya no por permiso: con
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
    private void avisos(Player p, Rastro r, Ajustes a, Grieta.Umbral u) {
        int q = r.quieto;
        // Calamity 1.1.0: en la zona spawn el limite y los avisos son los de la Grieta (parca.spawn).
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
                hc.cordura().destello(p, Component.text("Te has movido: las campanas callan.", Paleta.TEXTO), 2);
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
                hc.cordura().destello(p, Component.text("Llevas mucho rato quieto. Si no te mueves, vendrá la Parca.", Paleta.TEXTO), 3);
                p.playSound(p.getLocation(), "block.bell.resonate", SoundCategory.HOSTILE, 0.3f, 0.5f);
            }
            case 2 -> {
                hc.cordura().destello(p, Component.text("Suena una campana por ti: muévete o vendrá la Parca.", Paleta.TEXTO), 3);
                p.playSound(p.getLocation(), "block.bell.resonate", SoundCategory.HOSTILE, 0.5f, 0.5f);
                p.playSound(p.getLocation(), "block.bell.resonate", SoundCategory.HOSTILE, 0.5f, 0.6f);
            }
            case 3 -> {
                p.sendMessage(ComandoCalamity.mensaje("La Parca viene por quien se queda quieto. Sal de la zona que marcan las almas."));
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
                Component ajeno = ComandoCalamity.mensaje("Una campana dobla cerca: alguien lleva mucho rato sin moverse.");
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
        if (r == null || !hc.esHardcore(p)) return;
        // Puede volver con otro cliente: lo de las teclas se vuelve a averiguar.
        r.conTeclas = false;
        rastros.put(p.getUniqueId(), r);
    }

    /** 1.8.2 · Los segundos quieto que llaman a la Grieta (en el spawn) o a la PARCA (fuera) para el. */
    int limite(Player p) {
        return grieta.umbral(p, ajustes()).limite();
    }

    /** Segundos de quietud segun la Huella. */
    int quieto(Player p) {
        Rastro r = rastros.get(p.getUniqueId());
        return r == null ? 0 : r.quieto;
    }

    /** /calamity reaper <player> [seconds]: pone su quieto (el limite = la llama en el siguiente segundo). */
    void forzar(Player p, int segundos) {
        Location l = p.getVehicle() != null ? p.getVehicle().getLocation() : p.getLocation();
        Ajustes a = ajustes();
        Grieta.Umbral u = grieta.umbral(p, a);
        Rastro r = rastro(p);
        // En el spawn, con la ventana de la Grieta; y con su zona ya puesta, para que el segundo
        // siguiente no lo tome por una entrada y lo vuelva a cero.
        r.forzar(segundos, l.getX(), l.getY(), l.getZ(), ajustesZona(a, u));
        r.zona = hc.enSpawn(p) ? ZONA_SPAWN : ZONA_FUERA;
        r.salto = false;
        r.graciaHasta = 0;
        r.pescaHasta = 0;
        // Los avisos ya pasados no se repiten: se dan por dados hasta ese punto (los del spawn, dentro).
        int[] av = u.avisos();
        int nivel = 0;
        while (nivel < av.length && r.quieto >= av[nivel]) nivel++;
        r.avisoDado = nivel;
    }

    /** /calamity reaper info: lo que sabe la Huella de ese jugador. */
    String info(Player p) {
        Rastro r = rastros.get(p.getUniqueId());
        if (r == null) return "sin huella";
        long ahora = System.currentTimeMillis();
        Ajustes a = ajustes();
        return "quieto " + r.quieto + "/" + limite(p) + " s | celdas " + r.celdasDistintas()
                + " | muestras " + r.llenas + " | última activa " + (r.ultimaActiva ? "sí" : "no")
                + " | montado " + Math.round(r.fraccionMontado() * 100) + " %"
                + " | pausa " + r.pausaUsada(ahora, a) + "/" + a.pausaMaxima() + " s"
                + " | por interaccion " + r.muestrasPorInteraccion
                + " | máx. celdas " + a.celdasMaximas() + " | teclas " + (r.conTeclas ? "sí" : "nunca")
                + (ahora < r.graciaHasta ? " | gracia " + (r.graciaHasta - ahora) / 1000 + " s" : "")
                + (ahora < r.pescaHasta ? " | pescando " + (r.pescaHasta - ahora) / 1000 + " s" : "")
                + " | modo " + (a.modoBloque() ? "bloque" : "huella")
                + (grieta.enSpawn(p) ? " | spawn: límite " + grieta.umbral(p, a).limite() + " s (Grieta)" : "");
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
     * Las 8 secuencias de DIS sec. 1.2 (PLAN WP5, aceptacion 1), las 3 de 1.1.1 (construir,
     * autoclicker, volar) y las 2 de viajar (ventana corta como la del Test, cliente sin teclas),
     * sobre Rastro en memoria con los valores de DIS sec. 4 (no la config del servidor, que en el
     * Test pone minutos: 1; la 12 la reproduce a proposito). Una linea por secuencia: probar.py
     * espera "OK 13/13" exacto.
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
        // 12. La config del SurvivalTest (minutos: 1, avisos 20-50 s): 12 muestras en el anillo.
        //     Antes cabian siempre en 24 celdas y quien viajaba en linea recta con teclas tenia la
        //     PARCA a los 60 s. Ahora viajar no da ni el primer aviso; quieto, el vaiven de 2
        //     bloques y el agua siguen llegando al minuto.
        {
            YamlConfiguration yc = new YamlConfiguration();
            yc.set("minutos", 1);
            yc.set("avisos", List.of(20, 30, 40, 45, 50));
            Ajustes t = Ajustes.de(yc);
            Rastro viaja = new Rastro(t.tamano());
            Rastro quieto = new Rastro(t.tamano());
            Rastro vaiven = new Rastro(t.tamano());
            Rastro agua = new Rastro(t.tamano());
            int max = 0;
            for (int s = 1; s <= 300; s++) {
                long ahora = t0 + s * 1000L;
                viaja.segundo(ahora, s * 4.3, 64, 0.5, false, true, false, t);
                quieto.segundo(ahora, 10.5, 64, 10.5, false, false, false, t);
                vaiven.segundo(ahora, (s % 2 == 0) ? 0.5 : 2.5, 64, 0.5, false, true, false, t);
                agua.segundo(ahora, s * 0.7, 62, 40, false, false, false, t);
                if (s > 60) max = Math.max(max, viaja.quieto);
            }
            h.ok("minutos 1 (Test): viajando -> no llega (max " + max + " de " + t.limite() + ", primer aviso "
                            + t.avisos()[0] + "); quieto " + quieto.quieto + ", vaiven " + vaiven.quieto + ", agua " + agua.quieto,
                    max < t.avisos()[0] && quieto.quieto >= t.limite() && vaiven.quieto >= t.limite()
                            && agua.quieto >= t.limite() && a.celdasMaximas() == a.maxCeldas());
        }
        // 13. Cliente que no manda teclas andando (version vieja por ViaVersion): el desplazamiento
        //     por el suelo cuenta, asi que viajar a pie no llega; sin esa senal (como antes) llegaba
        //     a los 10 min aunque recorriera kilometros. Parado en el suelo sigue llegando.
        {
            Rastro nueva = new Rastro(a.tamano());
            Rastro antes = new Rastro(a.tamano());
            Rastro parado = new Rastro(a.tamano());
            int max = 0;
            for (int s = 1; s <= 900; s++) {
                long ahora = t0 + s * 1000L;
                nueva.segundo(ahora, s * 4.3, 64, s * 0.5, false, false, true, a);
                antes.segundo(ahora, s * 4.3, 64, s * 0.5, false, false, false, a);
                parado.segundo(ahora, 10.5, 64, 10.5, false, false, true, a);
                max = Math.max(max, nueva.quieto);
            }
            h.ok("cliente sin teclas andando -> no llega (max " + max + "; sin la senal " + antes.quieto
                            + "); parado en el suelo -> llega (" + parado.quieto + ")",
                    max < a.avisos()[0] && antes.quieto >= 600 && parado.quieto >= 600);
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
