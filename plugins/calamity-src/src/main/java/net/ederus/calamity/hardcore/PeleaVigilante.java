package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.EntityEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.Zoglin;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Una pelea contra el Vigilante. El gestor es Vigilante.
 *
 * Calamity 1.14.1 · El Vigilante es un JINETE, "asi como con Alba": un jinete sin cabeza con una
 * calabaza tallada (apagada) en la cabeza y una maza en la mano, montado en el zoglin gigante de la
 * 1.14.0. El jinete es la anomalia: lleva el nombre, la barra, la vida logica, el merito y el botin.
 *  - Lo que se ve del jinete es un MANNEQUIN con skin de jugador (jinete.skin, la cuenta RocketOniPad
 *    de serie) a jinete.escala (1,6), como el cuerpo de la Parca y de Ambush (CuerpoNpc). Lo que lleva
 *    la vida, la IA y la caja es un esqueleto wither invisible, desnudo y callado debajo (no arde al
 *    sol), a la misma altura que el maniqui. Sin maniqui (la cuenta no vale o no sale), se ve el
 *    esqueleto con la calabaza y la maza.
 *  - La MONTURA es el zoglin de la 1.14.0 (mismo cuerpo, escala, IA y movimiento): sin nombre ni
 *    barra, y no recibe dano propio. Pero los golpes de un jugador a la montura (y al maniqui) pasan al
 *    jinete con el mismo calculo de merito (Vigilante.onDanoMontura): nadie pega a la nada.
 *  - Montados, maniqui y esqueleto van los dos de pasajeros del zoglin (el maniqui primero: la bestia
 *    no tiene "conductor"), asi que viajan pegados a el en cada tick. Para mirar no se les teleporta (un
 *    teleport baja al pasajero): se les gira con setRotation, como el encarar de Alba. El zoglin si se
 *    teleporta con ellos encima (vanilla lleva a los pasajeros con el vehiculo).
 *
 * Fases por vida (Vigilante.faseDe): I Acecho y II Bajo tierra, MONTADO, como la 1.14.0; III A pie
 * (bajo jinete.desmonte): el jinete DESMONTA de un salto y la maza golpea el suelo al caer, y pelea a
 * pie (Mazazo, Barrido, Cabeza negra y golpes basicos) mientras la bestia se retira al borde y de vez
 * en cuando cruza la pelea en Estampida; IV Furia (bajo jinete.remonte): la bestia pasa a recogerlo y
 * REMONTA de un salto; todo junto y mas rapido, y el jinete barre con la maza desde arriba a quien se
 * arrime a la bestia. Fuera de la III, el guardian lo vuelve a montar cada 2 s si algo lo baja; si la
 * montura muere o desaparece, el jinete sigue a pie hasta el final.
 *
 * Las habilidades (Habilidad) avisan siempre en el suelo: los golpes fuertes al menos un segundo
 * (Ajustes.AVISO_MINIMO), lo demas medio segundo o mas. Los golpes fuertes quitan una fraccion de tu
 * vida maxima entre golpes.minimo y golpes.maximo, por DanoVerdadero. La caida de quien sale volando
 * por un golpe suyo SI hace dano (la de Calamity, que ya es doble), aunque mate: el vuelo prestado
 * contra la expulsion por volar se devuelve en cuanto empieza a caer (devolverAlCaer).
 *
 * Los bloques que saltan del suelo son FallingBlock efimeros: no se colocan al caer, no sueltan nada,
 * llevan la marca lethal_world:vigilante_pieza y se retiran a efimeros.vida-ticks. Las cabezas negras
 * son WitherSkull negras con la misma marca: no arden, no rompen nada y su golpe lo pone la pelea.
 *
 * Ambiente de Halloween: lamentos lejanos, susurros, la cueva, la respiracion de la bestia y la risa
 * grave del jinete, todo vanilla y grave. Nunca campanas (la campana es de la Parca y las muertes).
 * Particulas sobrias: bloque, polvo, humo y ceniza.
 *
 * Dos tareas: la de 2 ticks de Amenazas (registrarPelea) para todo, y una de 1 tick (animador) que
 * solo pega el maniqui al jinete y los encara, para que el cliente lo vea fluido.
 */
final class PeleaVigilante implements Runnable {

    enum Estado { APARECE, PELEA, FIN }

    /**
     * Quien hace cada habilidad: la pareja montada (el zoglin con el jinete encima), el jinete a pie, la
     * montura suelta (fase III) o el paso de montado a pie y vuelta.
     */
    enum Actor { PAREJA, JINETE, MONTURA, PASO }

    /**
     * Las habilidades, con lo que ensena el menu de /anomaly (VigilanteType). clave = su seccion en
     * hardcore.vigilante.habilidades; alias = el nombre en ingles del comando de staff.
     */
    enum Habilidad {
        MARTILLAZO("vi_martillazo", "slam", "martillazo", "Martillazo",
                "Salta muy alto con el jinete encima y cae aplastando el suelo: cerca te lanza al cielo; más lejos, te barre.",
                1, 4, 140, 20, 0.30, 5, Material.HEAVY_CORE, 60, Actor.PAREJA),
        LANZAMIENTO("vi_lanzamiento", "toss", "lanzamiento", "Cabezazo",
                "Agacha la cabeza, embiste a uno solo y lo manda por los aires.",
                1, 4, 100, 20, 0.30, 4, Material.FEATHER, 40, Actor.PAREJA),
        EMBESTIDA("vi_embestida", "charge", "embestida", "Embestida",
                "Marca una línea en el suelo y la cruza a toda velocidad, apartando a quien alcance.",
                1, 4, 160, 20, 0.35, 4, Material.ANVIL, 50, Actor.PAREJA),
        HUNDIMIENTO("vi_hundimiento", "burrow", "hundimiento", "Hundimiento",
                "Se mete bajo tierra con su jinete y sale debajo de uno de ustedes: si el suelo tiembla bajo tus pies, apártate.",
                2, 4, 320, 20, 0.30, 3, Material.ROOTED_DIRT, 120, Actor.PAREJA),
        RUGIDO("vi_rugido", "roar", "rugido", "Rugido",
                "Un grito largo y grave: te nubla la vista un instante y te quita cordura.",
                2, 4, 400, 20, 0, 2, Material.SCULK_SHRIEKER, 40, Actor.PAREJA),
        MAZAZO("vi_mazazo", "smash", "mazazo", "Mazazo",
                "A pie, salta sobre ti con la maza: muy cerca te lanza al cielo; a media distancia, te empuja lejos.",
                1, 4, 70, 20, 0.25, 5, Material.MACE, 40, Actor.JINETE),
        BARRIDO("vi_barrido", "sweep", "barrido", "Barrido",
                "Barre con la maza en arco delante de él; en furia, desde lo alto de la bestia.",
                1, 4, 60, 20, 0.25, 4, Material.BREEZE_ROD, 30, Actor.JINETE),
        CABEZA_NEGRA("vi_cabeza_negra", "skull", "cabeza-negra", "Cabeza negra",
                "Si te alejas, te lanza una cabeza negra que estalla sin fuego.",
                1, 4, 90, 20, 0.25, 3, Material.WITHER_SKELETON_SKULL, 40, Actor.JINETE),
        ESTAMPIDA("vi_estampida", "stampede", "estampida", "Estampida",
                "Sin jinete, la bestia espera en el borde y cruza la pelea de punta a punta.",
                3, 3, 180, 20, 0.30, 0, Material.ZOGLIN_SPAWN_EGG, 50, Actor.MONTURA),
        DESMONTE("vi_desmonte", "dismount", "desmonte", "Desmonte",
                "Salta de la bestia y su maza golpea el suelo al caer: la onda te aparta.",
                3, 3, 20, 20, 0.25, 0, Material.SADDLE, 50, Actor.PASO),
        REMONTE("vi_remonte", "remount", "remonte", "Remonte",
                "La bestia vuelve a recogerlo y el jinete monta de un salto para la furia.",
                4, 4, 20, 10, 0, 0, Material.LEAD, 60, Actor.PASO);

        final String id, alias, clave, nombre, descripcion;
        final int faseDesde, faseHasta, espera, aviso, peso, duracion;
        final double fraccion;
        final Material icono;
        final Actor actor;

        Habilidad(String id, String alias, String clave, String nombre, String descripcion, int faseDesde, int faseHasta,
                  int espera, int aviso, double fraccion, int peso, Material icono, int duracion, Actor actor) {
            this.id = id;
            this.alias = alias;
            this.clave = clave;
            this.nombre = nombre;
            this.descripcion = descripcion;
            this.faseDesde = faseDesde;
            this.faseHasta = faseHasta;
            this.espera = espera;
            this.aviso = aviso;
            this.fraccion = fraccion;
            this.peso = peso;
            this.icono = icono;
            this.duracion = duracion;
            this.actor = actor;
        }

        boolean enFase(int f) {
            return f >= faseDesde && f <= faseHasta;
        }

        /** Por id (vi_rugido), alias en ingles (roar) o clave (rugido). */
        static Habilidad buscar(String nombre) {
            if (nombre == null) return null;
            String n = nombre.trim().toLowerCase(Locale.ROOT);
            for (Habilidad h : values()) if (h.id.equals(n) || h.alias.equals(n) || h.clave.equals(n)) return h;
            return null;
        }
    }

    /** Donde pilla a cada uno el martillazo (o el mazazo), por su distancia al sitio donde cae. */
    enum Zona { CERCA, MEDIO, LEJOS, FUERA }

    /** De quien es un golpe que recibe una de sus tres entidades. */
    enum Rol { JINETE, MONTURA, CASCARA }

    /** Que se hace con un golpe a una de ellas (Vigilante.onDanoMontura / onDanoCascara). */
    enum Redirige { PASA, CANCELA, AL_JINETE }

    /** Lo que hace la bestia sin jinete encima. */
    enum Suelta { NADA, RETIRADA, ESPERA, VUELVE }

    // ------------------------------------------------------------ numeros

    /** Los avisos en el suelo: del polvo de tierra a la sangre seca segun se acerca el golpe. */
    static final int RGB_AVISO_DESDE = 0xA89279, RGB_AVISO_HASTA = 0x8E1E1E;
    /** La caja de un Zoglin a escala 1 (vanilla): la suya es esto por cuerpo.escala. */
    static final double ZOGLIN_ANCHO = 1.3965, ZOGLIN_ALTO = 1.4;
    /** El asiento del zoglin a escala 1 (vanilla: passengerAttachments 1,49375, el del jinete de hoglin). */
    static final double ZOGLIN_ASIENTO = 1.49375;
    /** El maniqui: alto de un jugador y lo que baja su asiento (Avatar.DEFAULT_VEHICLE_ATTACHMENT). */
    static final double ALTO_JUGADOR = 1.8, ASIENTO_JUGADOR = 0.6;
    /** El esqueleto wither: su alto y lo que baja su asiento (ridingOffset -0,875). */
    static final double ALTO_ESQUELETO = 2.4, ASIENTO_ESQUELETO = 0.875;
    /** Lo que ocupa el cartel "Nv. X" del jinete sobre su caja (0,45 de hueco y dos lineas). */
    static final double CARTEL = 1.0;
    /** Lo que lleva el jinete: la calabaza TALLADA (apagada, nunca un jack o'lantern) y la maza. */
    static final Material CASCO = Material.CARVED_PUMPKIN, ARMA = Material.MACE;
    /** Lo que se esconde de mas bajo el suelo mientras espera o viaja bajo tierra. */
    static final double MARGEN_HONDO = 0.8;
    /** La fisica de vanilla por tick: gravedad, freno vertical, freno en el aire y en el suelo normal. */
    static final double GRAVEDAD = 0.08, ROCE_VERTICAL = 0.98, ROCE_AIRE = 0.91, ROCE_SUELO = 0.546;
    /** Lo mas que se le da a una velocidad por eje: el paquete de velocidad del cliente no pasa de aqui. */
    static final double VELOCIDAD_MAXIMA = 3.9;
    /** Lo que tarda en salir del todo de la tierra al aparecer (la pareja entera, jinete incluido). */
    static final int SUBIDA_TICKS = 40;
    /** Lo que levanta el empujon del martillazo a media distancia: lo justo para que vuele lejos sin frenar en el suelo. */
    static final double EMPUJE_MEDIO_ALTO = 0.6;
    /** El guardian de la montura: cada 2 s, como en Alba. */
    static final int GUARDIAN_TICKS = 40;
    /** Solo el jinete lleva nombre (el cartel de MinionManager) y la barra; la montura, ninguno de los dos. */
    static final boolean NOMBRE_JINETE = true, NOMBRE_MONTURA = false;
    /** Las cabezas negras: negras (no la azul), sin fuego y sin romper nada (su golpe lo pone la pelea). */
    static final boolean CABEZA_CARGADA = false, CABEZA_INCENDIARIA = false;
    static final float CABEZA_POTENCIA = 0f;

    private final Vigilante gestor;
    private final Hardcore hc;
    private final Vigilante.Ajustes a;
    /** Null en la de prueba (/anomaly o /calamity vigilant test). */
    final UUID presa;
    final String presaNombre;
    final boolean prueba;
    final Vigilante.Escala escala;
    private final double golpe;
    /** Lo que mide la caja del zoglin a cuerpo.escala. */
    private final double ancho, alto;

    /** El jinete: lleva la vida logica, la IA a pie y el nombre. Invisible si lleva maniqui. */
    WitherSkeleton jinete;
    /** La bestia: sin nombre, sin barra y sin dano propio. Null si se ha perdido. */
    Zoglin montura;
    /** Lo que se ve del jinete (Mannequin con skin, maza y calabaza). */
    private final CuerpoNpc npc;
    private boolean conNpc;
    /** Si el jinete DEBE ir montado ahora (el guardian lo repone; el animador no lo teleporta). */
    private boolean montadoDeseado;
    /** Mientras montamos o bajamos nosotros: el veto a desmontes (Vigilante.onDesmonte) no actua. */
    private boolean propio;
    /** Mientras entra un golpe de maza de la pelea (golpeMaza): el unico golpe a mano del jinete que vale. */
    private boolean golpeando;
    /** Si la pareja va escondida bajo tierra (llegada y hundimiento): invisibles, invulnerables y sin equipo a la vista. */
    private boolean oculto;
    private BukkitTask animador;
    /** El yaw que fija la habilidad en curso para lo que se ve, hasta ese tick del servidor. */
    private float yawFijo;
    private long yawFijoHasta = -1;

    Estado estado = Estado.APARECE;
    private int fase = 1;
    private boolean furia;
    private long ticks;
    private int vueltas;
    private long inicioPelea;
    private final long nacio = System.currentTimeMillis();
    private boolean pagada;

    /** Donde sale al nacer (a ras de suelo, con hueco para su caja) y hacia donde mira. */
    private Location salida;
    private float yawSalida;
    private boolean estallo;
    private long estallido;
    private List<Location> avisoSalida, avisoSalidaDentro;
    private BlockData sueloSalida;

    /** La habilidad en curso de la pareja o del jinete. */
    private Tecnica actual;
    /** La de la montura suelta (fase III): la estampida. */
    private Tecnica suelta;
    /** El barrido desde lo alto de la bestia (fase IV), a la vez que la pareja hace lo suyo. */
    private Tecnica arriba;
    private Habilidad siguiente;
    /** La que va justo despues de la que esta en curso (martillazo y embestida en furia; mazazo y barrido a pie). */
    private Habilidad encadenar;
    private final EnumMap<Habilidad, Long> listo = new EnumMap<>(Habilidad.class);
    private Habilidad ultima;
    private long respiroHasta;
    private long sinNadieDesde = -1;
    private long aturdidoHasta;
    private int jugadoresContados;
    private final Set<UUID> participantes = new HashSet<>();
    /** Los golpes basicos del jinete (con su espera) y el barrido desde arriba. */
    private long proximoBasico;
    private long proximoArriba;
    /** La bestia sin jinete: que hace, a donde va y desde cuando no avanza. */
    private Suelta modoSuelta = Suelta.NADA;
    private Location borde;
    private long bordeDesde;
    private Location anclaSuelta;
    private long anclaSueltaDesde;
    private long monturaQuietaHasta;
    /** Fase IV recien entrada: la bestia viene rapido y el guardian mira cada 2 ticks. */
    private boolean remontePendiente;

    /** El atasco: desde cuando no se aleja mas de bloque y medio de este sitio. */
    private Location anclaAtasco;
    private long anclaDesde;
    private long ultimoEscape = -10_000;

    /** Las pisadas (todo va en silencio: sus sonidos los pone la pelea, graves) y el ambiente. */
    private Location ultimaPos, ultimaPosJinete;
    private double andado, andadoJinete;
    private long proximoAmbiente;
    private long proximoGrunido;
    private long proximaRisa;
    private long ultimoDolor = -100;
    private long ultimoDolorMontura = -100;

    /**
     * La ventana de golpes fuertes por jugador, COMPARTIDA entre todas las peleas: con dos Vigilantes
     * vivos, el golpe de uno y el del otro en el mismo tick tampoco pasan de golpes.maximo. El reloj es el
     * tick del servidor, no el de cada pelea. {tick de inicio, fraccion acumulada}.
     */
    private static final Map<UUID, double[]> VENTANA = new HashMap<>();
    /**
     * Permiso de vuelo prestado (para que el servidor no eche por volar a quien lanza por el aire). Se
     * devuelve en cuanto empieza a caer: con el vuelo permitido la caida no haria dano, y la caida de un
     * golpe suyo SI lo hace.
     */
    private final Map<UUID, Prestamo> vuelo = new HashMap<>();

    /** Un vuelo prestado: hasta que tick de la pelea, desde cual y lo mas alto que ha llegado. */
    private static final class Prestamo {
        long hasta;
        final long desde;
        double cima;

        Prestamo(long hasta, long desde, double cima) {
            this.hasta = hasta;
            this.desde = desde;
            this.cima = cima;
        }
    }

    /** Un bloque del suelo que salta: se retira solo en "hasta" (tick de la pelea) si no ha caido antes. */
    private record Efimero(FallingBlock bloque, long hasta) {
    }

    private final List<Efimero> efimeros = new ArrayList<>();

    /** Una cabeza negra en vuelo: su direccion (velocidad constante) y hasta que tick vuela. */
    private record Calavera(WitherSkull cabeza, Vector vel, long hasta) {
    }

    private final List<Calavera> calaveras = new ArrayList<>();
    /** Las salpicaduras al herirle: carne de bestia y hueso del jinete (se crean al primer uso). */
    private static BlockData carne, hueso;

    private BossBar barra;
    private final Set<UUID> viendo = new HashSet<>();

    private PeleaVigilante(Vigilante gestor, UUID presa, String presaNombre, Vigilante.Escala escala, int jugadores) {
        this.gestor = gestor;
        this.hc = gestor.hc();
        this.a = gestor.ajustes();
        this.presa = presa;
        this.presaNombre = presa == null ? "prueba" : presaNombre;
        this.prueba = presa == null;
        this.escala = escala;
        this.golpe = escala.golpe();
        this.jugadoresContados = Math.max(1, Math.min(a.jugadoresTope, jugadores));
        this.ancho = anchoDe(a.escalaCuerpo);
        this.alto = altoDe(a.escalaCuerpo);
        this.npc = new CuerpoNpc(hc, a.jineteSkin, a.jineteEscala, maza(), "entity.wither_skeleton.hurt");
    }

    /**
     * Los pone enterrados bajo "sitio" (a ras de suelo, con hueco para la caja del zoglin) y arranca la
     * aparicion: el suelo se agrieta, estalla y la pareja sale escarbando. Null si el spawn lo cancela alguien.
     */
    static PeleaVigilante crear(Vigilante gestor, UUID presa, String nombre, Vigilante.Escala escala, Location sitio, int jugadores) {
        if (sitio == null || sitio.getWorld() == null || !Vigilante.cargado(sitio)) return null;
        PeleaVigilante pe = new PeleaVigilante(gestor, presa, nombre, escala, jugadores);
        return pe.nacer(sitio) ? pe : null;
    }

    private boolean nacer(Location sitio) {
        Amenazas am = hc.amenazas();
        if (am == null) return false;
        salida = sitio.clone();
        salida.setPitch(0);
        Player cerca = presa != null ? hc.plugin().getServer().getPlayer(presa) : Fx.nearest(sitio, 32);
        if (cerca != null && cerca.getWorld() == salida.getWorld()) salida.setYaw(PeleaAmbush.yaw(salida, cerca.getLocation()));
        yawSalida = salida.getYaw();
        montadoDeseado = true;
        Location bajo = enterradoHondo(salida);
        Zoglin z = am.invocar(Zoglin.class, bajo, Vigilante.AMENAZA, escala.nivel(), null, e -> {
            if (presa != null) e.getPersistentDataContainer().set(Marcas.PRESA, PersistentDataType.STRING, presa.toString());
            // finalizeSpawn lo hace bebe una de cada cinco veces (y corre antes que esto).
            e.setBaby(false);
            e.setAI(false);
            e.setInvulnerable(true);
            // Sus sonidos los pone la pelea, mas graves: un Zoglin a tono normal no suena a algo de este tamano.
            e.setSilent(true);
            Compat.setAttribute(e, "scale", a.escalaCuerpo);
            Compat.setAttribute(e, "knockback_resistance", 1.0);
            Compat.setAttribute(e, "movement_speed", a.velocidad);
            Compat.setAttribute(e, "follow_range", 48);
            // El escalado no toca el paso: sin esto, un bloque de desnivel le obliga a saltar.
            Compat.setAttribute(e, "step_height", a.paso);
            Compat.setAttribute(e, "attack_damage", golpe);
            // Su cornada de vanilla ya lanza por el aire; un poco mas, que se note el tamano.
            Compat.setAttribute(e, "attack_knockback", 1.6);
        }, NOMBRE_MONTURA);
        if (z == null) return false;
        montura = z;
        am.ancla(z, salida);

        conNpc = CuerpoNpc.cuentaValida(a.jineteSkin);
        final boolean npcPedido = conNpc;
        WitherSkeleton j = am.invocar(WitherSkeleton.class, bajo, Vigilante.AMENAZA, escala.nivel(), Paleta.vigilante("Vigilante"), e -> {
            if (presa != null) e.getPersistentDataContainer().set(Marcas.PRESA, PersistentDataType.STRING, presa.toString());
            e.setAI(false);
            e.setInvulnerable(true);
            // Sus huesos no suenan: los sonidos del jinete los pone la pelea, graves.
            e.setSilent(true);
            e.setCollidable(false);
            try {
                e.setShouldBurnInDay(false);
            } catch (Throwable ignorado) {
                // Un esqueleto wither no arde al sol de todos modos; y Amenazas cancela cualquier fuego.
            }
            Compat.setAttribute(e, "scale", npcPedido ? escalaEsqueleto(a.jineteEscala) : a.jineteEscala);
            Compat.setAttribute(e, "knockback_resistance", 1.0);
            Compat.setAttribute(e, "movement_speed", a.jineteVelocidad);
            Compat.setAttribute(e, "follow_range", 48);
            Compat.setAttribute(e, "step_height", 1.1);
            // Sin golpe de vanilla (ni marchitamiento): sus mazazos los da la pelea (basico).
            Compat.setAttribute(e, "attack_damage", 0);
            EntityEquipment eq = e.getEquipment();
            if (eq != null) eq.clear();
            if (npcPedido) e.setInvisible(true);
            else vestir(e);
        });
        if (j == null) {
            Fx.safeRemove(z);
            montura = null;
            return false;
        }
        jinete = j;
        am.vidaLogica(j, escala.vida());
        am.topeGolpe(j, a.topeGolpe);
        am.ancla(j, salida);
        if (conNpc && !npc.poner(j, bajo, null)) {
            conNpc = false;
            volverAEsqueleto();
        }
        if (conNpc) vestir(npc.entidad());
        montarYa();
        ocultar(true);
        am.registrarPelea(this);
        gestor.registrar(this);
        animador = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(), () -> hc.seguro("vigilante", this::animar), 1L, 1L);
        return true;
    }

    /** La maza que lleva: la de vanilla, sin nombre (lo que deja es otra cosa). */
    static ItemStack maza() {
        return new ItemStack(ARMA);
    }

    /** La calabaza tallada: apagada, nunca un jack o'lantern (sin luces). */
    static ItemStack calabaza() {
        return new ItemStack(CASCO);
    }

    /** La calabaza en la cabeza y la maza en la mano, sin que suelte nada. */
    private static void vestir(LivingEntity e) {
        if (e == null || e.getEquipment() == null) return;
        EntityEquipment eq = e.getEquipment();
        eq.setHelmet(calabaza());
        eq.setItemInMainHand(maza());
        try {
            eq.setHelmetDropChance(0f);
            eq.setItemInMainHandDropChance(0f);
        } catch (Throwable ignorado) {
            // El maniqui no tiene probabilidades: lo que suelte lo vacia Vigilante.onMuerte.
        }
    }

    private static void desvestir(LivingEntity e) {
        if (e == null || e.getEquipment() == null) return;
        e.getEquipment().setHelmet(null);
        e.getEquipment().setItemInMainHand(null);
    }

    /** Sin maniqui: el esqueleto se ve, a su tamano, con la calabaza y la maza. */
    private void volverAEsqueleto() {
        if (jinete == null || !jinete.isValid()) return;
        jinete.setInvisible(oculto);
        Compat.setAttribute(jinete, "scale", a.jineteEscala);
        if (oculto) desvestir(jinete);
        else vestir(jinete);
    }

    /** El esqueleto wither invisible, a la altura del maniqui de jinete.escala (puro). */
    static double escalaEsqueleto(double escalaJinete) {
        return escalaJinete * ALTO_JUGADOR / ALTO_ESQUELETO;
    }

    // ================================================================ montar y bajar

    boolean monturaViva() {
        return montura != null && montura.isValid() && !montura.isDead();
    }

    private boolean jineteVivo() {
        return jinete != null && jinete.isValid() && !jinete.isDead();
    }

    /** Si el jinete va de verdad encima de la bestia ahora mismo. */
    boolean montado() {
        return monturaViva() && jineteVivo() && montura.getPassengers().contains(jinete);
    }

    /** Si ira montado en esa fase (puro): todas menos la III, la del jinete a pie. */
    static boolean montadoEn(int fase) {
        return fase != 3;
    }

    /**
     * Sube el maniqui y el jinete a la bestia (el maniqui primero: con un mob de primer pasajero la
     * bestia tendria "conductor"). Sin teleports: addPassenger los pone en el asiento.
     */
    private boolean montarYa() {
        if (!monturaViva() || !jineteVivo()) return false;
        montadoDeseado = true;
        Amenazas am = hc.amenazas();
        Mannequin m = conNpc ? npc.entidad() : null;
        propio = true;
        try {
            if (m != null && !montura.getPassengers().contains(m)) {
                if (m.isInsideVehicle()) m.leaveVehicle();
                // El maniqui va delante: si el jinete ya iba, se baja y vuelve a subir detras.
                if (montura.getPassengers().contains(jinete)) jinete.leaveVehicle();
                am.montar(montura, m);
            }
            if (!montura.getPassengers().contains(jinete)) {
                if (jinete.isInsideVehicle()) jinete.leaveVehicle();
                am.montar(montura, jinete);
            }
        } finally {
            propio = false;
        }
        // Montado no anda por su cuenta: lo lleva la bestia.
        sujetarJinete();
        jinete.setAI(false);
        return montado();
    }

    /** Baja al jinete (y al maniqui) donde esta ahora, sin moverlo: desde aqui pelea a pie. */
    private void bajarYa() {
        montadoDeseado = false;
        if (!jineteVivo()) return;
        Location l = jinete.getLocation();
        propio = true;
        try {
            Mannequin m = conNpc ? npc.entidad() : null;
            if (m != null && m.isInsideVehicle()) m.leaveVehicle();
            if (jinete.isInsideVehicle()) {
                jinete.leaveVehicle();
                // Vanilla lo deja encima de la caja de la bestia: se le devuelve a su asiento.
                hc.amenazas().teleportar(jinete, l);
            }
        } finally {
            propio = false;
        }
        if (estado == Estado.PELEA && !oculto) jinete.setAI(true);
    }

    /** Si un desmonte que no hemos pedido se puede cancelar (Vigilante.onDesmonte): solo si debe ir montado. */
    boolean vetaDesmonte(Entity quien) {
        return !propio && montadoDeseado && estado != Estado.FIN && (esJinete(quien) || esCascara(quien));
    }

    /**
     * Escondidos bajo tierra o no: invisibles, invulnerables y el maniqui sin calabaza ni maza (la
     * invisibilidad no esconde el equipo: en una cueva se veria flotar).
     */
    private void ocultar(boolean si) {
        oculto = si;
        if (monturaViva()) {
            montura.setInvisible(si);
            montura.setInvulnerable(si);
        }
        if (jineteVivo()) {
            jinete.setInvulnerable(si);
            if (!conNpc) volverAEsqueleto();
        }
        Mannequin m = conNpc ? npc.entidad() : null;
        if (m != null) {
            m.setInvisible(si);
            if (muestraEquipo(si)) vestir(m);
            else desvestir(m);
        }
    }

    /** Si el equipo (calabaza y maza) se ve (puro): nunca mientras va escondido bajo tierra. */
    static boolean muestraEquipo(boolean oculto) {
        return !oculto;
    }

    // ================================================================ cada tick: lo que se ve

    /**
     * Cada tick: el maniqui pegado al jinete. Montado no se le teleporta (lo bajaria): se le gira, a el y
     * al jinete, hacia su objetivo o hacia donde diga la habilidad (el encarar de Alba). A pie se le pega
     * al esqueleto, mirando a donde mira y camina.
     */
    private void animar() {
        if (estado == Estado.FIN || !jineteVivo()) return;
        Location l = jinete.getLocation();
        float yaw, pitch = 0;
        if (Bukkit.getCurrentTick() <= yawFijoHasta) {
            yaw = yawFijo;
        } else {
            Player obj = objetivo();
            if (obj != null && obj.getWorld() == l.getWorld()) {
                Vector v = obj.getEyeLocation().toVector().subtract(jinete.getEyeLocation().toVector());
                if (v.lengthSquared() > 1e-4) {
                    Location m = l.clone();
                    m.setDirection(v);
                    yaw = m.getYaw();
                    pitch = m.getPitch();
                } else {
                    yaw = l.getYaw();
                }
            } else {
                yaw = montadoDeseado && monturaViva() ? montura.getLocation().getYaw() : l.getYaw();
            }
        }
        if (montadoDeseado) {
            try {
                jinete.setRotation(yaw, pitch);
            } catch (Throwable ignorado) {
                // Sin girar, el maniqui manda: el esqueleto es invisible.
            }
            if (conNpc && !npc.girar(yaw, pitch)) {
                conNpc = false;
                volverAEsqueleto();
            }
            return;
        }
        if (!conNpc) return;
        l.setYaw(yaw);
        l.setPitch(pitch);
        if (!npc.seguir(l)) {
            conNpc = false;
            volverAEsqueleto();
        }
    }

    /** Fija a donde mira lo que se ve durante unos ticks (la habilidad en curso lo renueva cada 2). */
    private void fijarYaw(float yaw) {
        yawFijo = yaw;
        yawFijoHasta = Bukkit.getCurrentTick() + 3;
    }

    // ================================================================ cada 2 ticks

    @Override
    public void run() {
        if (estado == Estado.FIN) {
            hc.amenazas().quitarPelea(this);
            return;
        }
        if (!jineteVivo()) {
            // Muerto lo cierra alMorir (EntityDeathEvent); aqui solo llega si alguien lo ha borrado o su chunk se ha ido.
            if (!pagada) irse("desaparece", null);
            return;
        }
        ticks += 2;
        boolean segundo = ++vueltas % 10 == 0;
        World w = jinete.getWorld();
        cortarVuelo();
        podarEfimeros();
        podarCalaveras(w);
        if (segundo) {
            refrescarBarra();
            devolverVuelo(false);
        } else if (barra != null) {
            barra.progress((float) Math.max(0, Math.min(1, Amenazas.fraccion(jinete))));
        }
        if (estado == Estado.APARECE) {
            if (!monturaViva()) {
                irse("desaparece", null);
                return;
            }
            aparecer(w);
            return;
        }
        if (ticks - inicioPelea >= a.duracionMinutos * 1200L) {
            irse("tiempo", ComandoCalamity.mensaje(Component.text("El ").append(Component.text("Vigilante", Paleta.VIGILANTE))
                    .append(Component.text(" se hunde en la tierra."))));
            return;
        }
        if (segundo) {
            if (!revisarGente()) return;
            revisarGrupo();
        }
        if (montura != null && !monturaViva()) monturaPerdida("desaparece");
        int nueva = Vigilante.faseDe(Amenazas.fraccion(jinete), fase, a.desmonteVida, a.remonteVida);
        if (nueva != fase) cambiarFase(nueva);
        // El guardian: cada 2 s; y en el acto si la furia espera montura o algo bajo al jinete sin pedirlo.
        if (vueltas % (GUARDIAN_TICKS / 2) == 0 || actual == null && (remontePendiente || montadoDeseado && !montado())) guardian();

        if (actual != null) {
            Tecnica tec = actual;
            boolean fin = pasoSeguro(tec, w);
            tec.t += 2;
            if (fin && actual == tec) acabar(tec.h);
        } else if (aturdido()) {
            if (vueltas % 3 == 0 && monturaViva()) {
                Compat.spawn(w, Compat.SMOKE, pie().add(0, alto + 0.2, 0), 4, ancho * 0.25, 0.1, ancho * 0.25, 0.01);
            }
        } else {
            if (aturdidoHasta > 0) finAturdido();
            dirigir(segundo);
        }
        if (arriba != null) {
            Tecnica tec = arriba;
            boolean fin = pasoSeguro(tec, w);
            tec.t += 2;
            if (fin && arriba == tec) arriba = null;
        }
        if (!montadoDeseado && monturaViva()) tickMontura(w, segundo);
        cazar();
        pasos(w);
        ambiente(w);
    }

    /** Un paso de una habilidad; si falla, se corta y se da por acabada. */
    private boolean pasoSeguro(Tecnica tec, World w) {
        try {
            return tec.paso(w);
        } catch (Throwable t) {
            hc.plugin().getLogger().warning("[Calamity] Fallo en " + tec.h.nombre + " del Vigilante: " + t);
            try {
                tec.cortar();
            } catch (Throwable ignorado) {
                // Lo que no se pudo cortar lo retira limpiar() al acabar.
            }
            return true;
        }
    }

    /**
     * La aparicion: emerger-aviso-ticks de suelo agrietandose donde va a salir (el aviso del estallido),
     * el estallido (bloques que saltan, golpe a quien este encima) y SUBIDA_TICKS saliendo de la tierra:
     * primero asoma la calabaza del jinete, luego el, y la bestia debajo.
     */
    private void aparecer(World w) {
        if (!estallo) {
            if (avisoSalida == null) {
                avisoSalida = anillo(salida, a.impactoRadio);
                avisoSalidaDentro = anillo(salida, a.impactoRadio * 0.5);
                sueloSalida = materialSuelo(salida);
                Compat.soundPlayers(w, salida, "entity.sniffer.digging", 2.0f, 0.5f);
                Compat.sound(w, salida, "entity.zoglin.angry", 1.8f, 0.45f);
            }
            double k = Math.min(1, ticks / (double) a.emergerAviso);
            if (ticks % 4 == 2) {
                pintar(w, avisoSalida, tono(k), sueloSalida);
                pintar(w, avisoSalidaDentro, tono(k), null);
            }
            Compat.spawn(w, Compat.BLOCK, salida.clone().add(0, 0.15, 0), 6, 0.5 + k, 0.05, 0.5 + k, 0.12, sueloSalida);
            if (ticks % 6 == 2) Compat.sound(w, salida, "block.rooted_dirt.break", 1.6f, 0.5f);
            if (ticks < a.emergerAviso) return;
            estallar(w);
            return;
        }
        long s = ticks - estallido;
        double k = Math.min(1, s / (double) SUBIDA_TICKS);
        Location bajo = enterrado(salida);
        Location l = bajo.clone();
        l.setY(bajo.getY() + (salida.getY() - bajo.getY()) * k);
        l.setYaw(yawSalida);
        l.setPitch(0);
        mover(l);
        fijarYaw(yawSalida);
        if (s % 4 == 0) {
            Compat.spawn(w, Compat.BLOCK, salida.clone().add(0, 0.2, 0), 12, ancho * 0.45, 0.1, ancho * 0.45, 0.15, sueloSalida);
            Compat.spawn(w, Compat.LARGE_SMOKE, salida.clone().add(0, 0.4, 0), 3, ancho * 0.4, 0.2, ancho * 0.4, 0.01);
        }
        if (s % 8 == 0 && s < SUBIDA_TICKS - 6) saltarAnillo(salida, ancho * 0.5, 2, 0.6);
        if (s == 8) {
            // Asoma la calabaza: la risa del jinete antes que la bestia.
            Compat.soundPlayers(w, salida, "entity.witch.celebrate", 1.6f, 0.5f);
        }
        if (s == 20) {
            Compat.soundPlayers(w, salida, "entity.ravager.roar", 2.5f, 0.5f);
            Compat.sound(w, salida, "entity.zoglin.angry", 2.5f, 0.45f);
        }
        if (k >= 1) empezarPelea();
    }

    /** El suelo revienta donde sale: bloques que saltan, polvo y un golpe fuerte a quien este encima. */
    private void estallar(World w) {
        estallo = true;
        estallido = ticks;
        Location bajo = enterrado(salida);
        bajo.setYaw(yawSalida);
        mover(bajo);
        // Sale a la vista: desde aqui sube y se le ve salir.
        ocultar(false);
        montura.setInvulnerable(true);
        jinete.setInvulnerable(true);
        Compat.soundPlayers(w, salida, "entity.generic.explode", 2.0f, 0.55f);
        Compat.soundPlayers(w, salida, "entity.warden.dig", 2.0f, 0.6f);
        Compat.sound(w, salida, "block.rooted_dirt.break", 2.0f, 0.5f);
        estallidoSuelo(w, salida, ancho * 0.5, a.efimerosPorGolpe, 1.0);
        Set<UUID> tocados = new HashSet<>();
        for (Player v : Fx.playersNear(salida, a.impactoRadio)) {
            if (!valido(v) || Math.abs(v.getLocation().getY() - salida.getY()) > 3) continue;
            tocados.add(v.getUniqueId());
            golpeFuerte(v, a.impactoFraccion, "Emergencia");
            lanzar(v, PeleaAmbush.plano(salida, v.getLocation(), PeleaAmbush.dir(yawSalida)).multiply(0.35), 7);
        }
        for (Player v : Fx.playersNear(salida, a.impactoRadio + 5)) {
            if (tocados.contains(v.getUniqueId()) || !valido(v)) continue;
            empujar(v, PeleaAmbush.plano(salida, v.getLocation(), PeleaAmbush.dir(yawSalida)).multiply(0.9).setY(0.35), true);
        }
        sacudir(salida, a.impactoRadio + 12);
    }

    private void empezarPelea() {
        estado = Estado.PELEA;
        inicioPelea = ticks;
        respiroHasta = ticks + 16;
        proximoAmbiente = ticks + 80;
        proximoGrunido = ticks + 60;
        proximaRisa = ticks + 200;
        proximoBasico = ticks + 30;
        Location l = salida.clone();
        l.setYaw(yawSalida);
        mover(l);
        // Ya fuera: la bestia no es invulnerable (sus golpes pasan al jinete), el jinete tampoco.
        montura.setInvulnerable(false);
        jinete.setInvulnerable(false);
        montura.setAI(true);
        anclaAtasco = montura.getLocation();
        anclaDesde = ticks;
        if (!montado()) montarYa();
        liberar();
        nombreBarra();
    }

    /** Cada segundo: si queda alguien con quien pelear a radio-pelea. Si no, en abandono-segundos se va. */
    private boolean revisarGente() {
        boolean hay = false;
        for (Player p : Fx.playersNear(jinete.getLocation(), a.radioPelea)) {
            if (!hc.enSpawn(p)) {
                hay = true;
                break;
            }
        }
        if (hay) {
            sinNadieDesde = -1;
            return true;
        }
        if (sinNadieDesde < 0) sinNadieDesde = ticks;
        if (ticks - sinNadieDesde >= a.abandonoSegundos * 20L) {
            irse("sin-nadie", null);
            return false;
        }
        return true;
    }

    /** Cuantos pelean: cada participante nuevo por encima de los que ya conto sube su vida, hasta jugadores-tope. */
    private void revisarGrupo() {
        Map<UUID, Double> dano = hc.amenazas().danoLogico(jinete);
        double vida = hc.amenazas().vidaLogicaMaxima(jinete);
        for (Map.Entry<UUID, Double> e : dano.entrySet()) {
            if (e.getValue() < a.participacion * vida || !participantes.add(e.getKey())) continue;
            if (participantes.size() <= jugadoresContados || jugadoresContados >= a.jugadoresTope) continue;
            jugadoresContados++;
            double antes = 1 + a.porJugador * (jugadoresContados - 2);
            double ahora = 1 + a.porJugador * (jugadoresContados - 1);
            double f = Amenazas.fraccion(jinete);
            hc.amenazas().vidaLogica(jinete, vida * ahora / antes);
            hc.amenazas().ponerFraccion(jinete, f);
            vida = hc.amenazas().vidaLogicaMaxima(jinete);
            hc.plugin().bitacora().anotar("vigilante", "grupo", presaNombre, String.valueOf(jugadoresContados));
        }
    }

    private static String nombreFase(int f) {
        return switch (f) {
            case 1 -> "Acecho";
            case 2 -> "Bajo tierra";
            case 3 -> "A pie";
            default -> "Furia";
        };
    }

    private static String consejoFase(int f) {
        return switch (f) {
            case 2 -> "Se mete bajo tierra con su jinete: si el suelo tiembla bajo tus pies, apártate.";
            case 3 -> "El jinete desmonta: su maza no da respiro y la bestia embiste suelta.";
            default -> "Vuelve a montar: más rápido, y la maza barre desde lo alto.";
        };
    }

    private void cambiarFase(int nueva) {
        fase = nueva;
        cortarTecnica();
        encadenar = null;
        siguiente = switch (nueva) {
            case 2 -> Habilidad.RUGIDO;
            case 3 -> null;
            default -> Habilidad.MARTILLAZO;
        };
        if (nueva >= 4 && !furia) {
            furia = true;
            if (actual == null && !aturdido() && montado()) Compat.setAttribute(montura, "movement_speed", velocidadActual());
        }
        respiroHasta = ticks + 10;
        Location l = jinete.getLocation();
        World w = l.getWorld();
        Compat.soundPlayers(w, l, "entity.hoglin.angry", 2.2f, 0.4f);
        Compat.sound(w, l, "entity.wither_skeleton.ambient", 1.8f, 0.5f);
        if (nueva == 3) Compat.sound(w, l, "entity.witch.celebrate", 1.8f, 0.5f);
        if (nueva >= 4) Compat.sound(w, l, "entity.ghast.scream", 2.0f, 0.5f);
        Compat.spawn(w, Compat.BLOCK, l.clone().add(0, 1.2, 0), 30, 0.6, 0.6, 0.6, 0.1, monturaViva() ? carne() : hueso());
        Compat.spawn(w, Compat.LARGE_SMOKE, l.clone().add(0, 0.4, 0), 16, ancho * 0.5, 0.2, ancho * 0.5, 0.02);
        Component c = Paleta.vigilante("Fase " + Parca.romano(nueva) + " · " + nombreFase(nueva))
                .append(Component.text(" · ", Paleta.SEPARADOR)).append(Component.text(consejoFase(nueva), Paleta.TEXTO));
        for (Player p : Fx.viewersNear(l, 48)) hc.barra().aviso(p, c, 4);
        if (nueva == 3) {
            // A pie: desmonta de un salto (si va montado; sin montura ya iba a pie).
            Player obj = objetivo();
            if (montado() && obj != null) empezar(Habilidad.DESMONTE, obj, true);
            else if (montadoDeseado) bajarYa();
            modoSuelta = monturaViva() ? Suelta.RETIRADA : Suelta.NADA;
        } else if (nueva >= 4 && !montado() && monturaViva()) {
            // La furia: la bestia deja lo que hacia y pasa a recogerlo.
            suelta = null;
            remontePendiente = true;
            modoSuelta = Suelta.VUELVE;
        }
        nombreBarra();
        hc.plugin().bitacora().anotar("vigilante", "fase", String.valueOf(nueva), presaNombre, "N " + escala.nivel());
    }

    /**
     * El guardian de la montura (como Alba, cada 2 s): fuera de la fase a pie, si el jinete esta en el
     * suelo y la bestia viva a mano, la remonta; si va montado pero algo bajo al maniqui, lo vuelve a
     * subir. En la III, si sigue montado (el staff lo subio), desmonta.
     */
    private void guardian() {
        if (estado != Estado.PELEA || oculto || !jineteVivo()) return;
        if (!monturaViva()) {
            remontePendiente = false;
            return;
        }
        boolean debe = montadoEn(fase);
        if (montado()) {
            if (debe) {
                remontePendiente = false;
                Mannequin m = conNpc ? npc.entidad() : null;
                if (m != null && !montura.getPassengers().contains(m)) montarYa();
            } else if (actual == null && !aturdido()) {
                Player obj = objetivo();
                if (obj != null) empezar(Habilidad.DESMONTE, obj, true);
                else bajarYa();
            }
            return;
        }
        // Algo lo bajo sin pedirlo (un teleport ajeno, la bestia escondida a medias): a pie hasta que lo remonte.
        if (montadoDeseado && (actual == null || actual.h.actor != Actor.PAREJA)) {
            bajarYa();
            liberar();
        }
        if (!debe || actual != null || aturdido()) return;
        double d = PeleaAmbush.distPlano(jinete.getLocation(), montura.getLocation());
        if (d <= a.remonteDistancia + 2 || remontePendiente && d <= 24) {
            Player obj = objetivo();
            empezar(Habilidad.REMONTE, obj, true);
        } else if (modoSuelta != Suelta.VUELVE) {
            modoSuelta = Suelta.VUELVE;
        }
    }

    /** La bestia ya no esta (muerta con /kill, borrada, su chunk se fue): el jinete sigue a pie. */
    private void monturaPerdida(String motivo) {
        if (pagada || estado == Estado.FIN || montura == null) return;
        montura = null;
        modoSuelta = Suelta.NADA;
        remontePendiente = false;
        if (suelta != null) suelta = null;
        if (actual != null && (actual.h.actor == Actor.PAREJA || actual.h.actor == Actor.PASO)) cortarTecnica();
        arriba = null;
        bajarYa();
        if (oculto) ocultar(false);
        liberar();
        hc.plugin().bitacora().anotar("vigilante", "sin-montura", presaNombre, motivo);
    }

    /** Muere la montura antes que el jinete (/kill): sin montura, a pie. */
    void monturaMuerta() {
        monturaPerdida("muerta");
    }

    // ================================================================ decidir

    private void dirigir(boolean segundo) {
        Player obj = objetivo();
        boolean mont = montado();
        // Bajado sin pedirlo: nada hasta que el guardian decida (lo remonta o lo deja a pie).
        if (montadoDeseado && !mont) return;
        if (obj == null) {
            if (segundo) {
                if (mont) Cerebro.soltar(montura);
                else jinete.setTarget(null);
            }
            return;
        }
        if (mont) {
            if (segundo) {
                forzarObjetivo(obj);
                if (revisarAtasco(obj)) return;
            }
            // Si la IA de la bestia no persigue (sin acceso a su cerebro), se la lleva a mano como el corcel de Alba.
            if (Cerebro.roto && vueltas % 2 == 0) conducir(obj.getLocation(), velocidadActual() * 0.9);
        } else if (vueltas % 5 == 0) {
            // A pie: la IA del esqueleto con su objetivo fijado (Alba: cada 10 ticks).
            liberarJinete(obj);
        }
        if (ticks < respiroHasta) return;
        double dist = PeleaAmbush.distPlano(pie(), obj.getLocation());
        Habilidad h = null;
        if (encadenar != null) {
            Habilidad e = encadenar;
            encadenar = null;
            if (disponible(e, fase, mont) && puede(e, obj, dist)) h = e;
            else if (mont && puede(Habilidad.LANZAMIENTO, obj, dist)) h = Habilidad.LANZAMIENTO;
        }
        if (h == null && siguiente != null && disponible(siguiente, fase, mont) && puede(siguiente, obj, dist)) {
            h = siguiente;
            siguiente = null;
        }
        if (h == null) {
            List<Habilidad> cand = new ArrayList<>();
            for (Habilidad x : Habilidad.values()) {
                if (!disponible(x, fase, mont) || ticks < listo.getOrDefault(x, 0L)) continue;
                if (puede(x, obj, dist)) cand.add(x);
            }
            h = sortear(cand, ultima, furia, ThreadLocalRandom.current().nextDouble());
        }
        if (h != null) empezar(h, obj, false);
    }

    /**
     * Si esa habilidad entra en el sorteo (puro): las de la pareja solo montado, las del jinete solo a pie;
     * la estampida, el desmonte y el remonte nunca (van por su cuenta).
     */
    static boolean disponible(Habilidad h, int fase, boolean montado) {
        if (!h.enFase(fase) || h.peso <= 0) return false;
        return switch (h.actor) {
            case PAREJA -> montado;
            case JINETE -> !montado;
            default -> false;
        };
    }

    /** Las que entran en el sorteo en esa fase y postura (puro, para el autotest y el menu). */
    static List<Habilidad> disponibles(int fase, boolean montado) {
        List<Habilidad> out = new ArrayList<>();
        for (Habilidad h : Habilidad.values()) if (disponible(h, fase, montado)) out.add(h);
        return out;
    }

    /**
     * Cada segundo: si lleva cuerpo.atasco-segundos sin alejarse bloque y medio del mismo sitio y sin
     * estar pegado a su objetivo, no cabe por donde va (su caja escalada es grande): se hunde con su
     * jinete y sale debajo de el. Como mucho una vez cada 8 s.
     */
    private boolean revisarAtasco(Player obj) {
        Location l = montura.getLocation();
        if (anclaAtasco == null || anclaAtasco.getWorld() != l.getWorld() || PeleaAmbush.distPlano(anclaAtasco, l) > 1.5
                || Math.abs(anclaAtasco.getY() - l.getY()) > 1.5) {
            anclaAtasco = l;
            anclaDesde = ticks;
            return false;
        }
        double dist = PeleaAmbush.distPlano(l, obj.getLocation());
        boolean pegado = dist <= ancho / 2 + 2.5 && Math.abs(l.getY() - obj.getLocation().getY()) < 3;
        if (pegado) {
            anclaDesde = ticks;
            return false;
        }
        if (ticks - anclaDesde < a.atascoTicks || ticks - ultimoEscape < 160 || !puedeHundirse(obj)) return false;
        ultimoEscape = ticks;
        anclaDesde = ticks;
        hc.plugin().bitacora().anotar("vigilante", "atasco", presaNombre, l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
        empezar(Habilidad.HUNDIMIENTO, obj, true);
        return true;
    }

    /** Si esa habilidad tiene sentido ahora contra obj a "dist" bloques. */
    private boolean puede(Habilidad h, Player obj, double dist) {
        return switch (h) {
            case MARTILLAZO -> dist <= a.martilloAlcance && Vigilante.cargado(obj.getLocation()) && !hc.enSpawn(obj);
            case LANZAMIENTO -> dist >= 2 && dist <= a.lanzaAlcance;
            case EMBESTIDA -> dist >= 5 && dist <= a.embestidaLargo;
            case HUNDIMIENTO -> puedeHundirse(obj);
            case RUGIDO -> !validosCerca(pie(), a.rugidoRadio).isEmpty();
            case MAZAZO -> dist >= 2.5 && dist <= a.mazoAlcance && Vigilante.cargado(obj.getLocation()) && !hc.enSpawn(obj);
            case BARRIDO -> dist <= a.barridoRadio && Math.abs(obj.getLocation().getY() - pie().getY()) < 3;
            case CABEZA_NEGRA -> blancoCabeza() != null;
            case ESTAMPIDA, DESMONTE, REMONTE -> true;
        };
    }

    private boolean puedeHundirse(Player obj) {
        return valido(obj) && Vigilante.cargado(obj.getLocation()) && !hc.enSpawn(obj)
                && obj.getLocation().distanceSquared(pie()) <= 36 * 36;
    }

    /** El peso de una habilidad en el sorteo: en furia, los hundimientos pesan el doble. */
    static double pesoEn(Habilidad h, boolean furia) {
        return h.peso * (furia && h == Habilidad.HUNDIMIENTO ? 2 : 1);
    }

    /** Sorteo por peso (puro); la ultima que uso pesa un 35 %. Null sin candidatas. */
    static Habilidad sortear(List<Habilidad> cand, Habilidad ultima, boolean furia, double azar) {
        double total = 0;
        for (Habilidad h : cand) total += pesoEn(h, furia) * (h == ultima ? 0.35 : 1);
        if (cand.isEmpty() || total <= 0) return null;
        double tirada = Math.max(0, Math.min(0.999999, azar)) * total;
        for (Habilidad h : cand) {
            tirada -= pesoEn(h, furia) * (h == ultima ? 0.35 : 1);
            if (tirada < 0) return h;
        }
        return cand.get(cand.size() - 1);
    }

    /**
     * Hasta cuando no la repite (puro): espera-ticks, y en furia por furia-espera (los hundimientos, aun
     * mas a menudo). El rugido nunca baja: su oscuridad no puede volverse continua.
     */
    static int esperaEfectiva(Habilidad h, int espera, boolean furia, double factorFuria) {
        if (!furia || h == Habilidad.RUGIDO) return espera;
        double f = h == Habilidad.HUNDIMIENTO ? factorFuria * 0.75 : factorFuria;
        return Math.max(40, (int) Math.round(espera * f));
    }

    private void empezar(Habilidad h, Player obj, boolean escape) {
        Tecnica tec = switch (h) {
            case MARTILLAZO -> new Martillazo(obj);
            case LANZAMIENTO -> new Lanzamiento(obj);
            case EMBESTIDA -> new Embestida(obj, Habilidad.EMBESTIDA, a.embestidaLargo);
            case HUNDIMIENTO -> new Hundimiento(obj, escape);
            case RUGIDO -> new Rugido();
            case MAZAZO -> new Mazazo(obj);
            case BARRIDO -> new Barrido(obj);
            case CABEZA_NEGRA -> new CabezaNegra();
            case DESMONTE -> new Desmonte(obj);
            case REMONTE -> new Remonte();
            case ESTAMPIDA -> null;
        };
        if (tec == null) return;
        actual = tec;
        if (h.actor == Actor.PAREJA) sujetar();
        else sujetarJinete();
        if (h.actor != Actor.PASO) listo.put(h, ticks + esperaEfectiva(h, a.hab(h).espera(), furia, a.furiaEspera));
        ultima = h;
        nombreBarra();
    }

    private void acabar(Habilidad h) {
        actual = null;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        respiroHasta = ticks + (furia ? 6 + r.nextInt(8) : h.actor == Actor.JINETE ? 6 + r.nextInt(8) : 14 + r.nextInt(12));
        // En furia: tras el martillazo, la embestida. A pie: tras el mazazo, el barrido si hay alguien a mano.
        if (h == Habilidad.MARTILLAZO && fase >= 4) {
            encadenar = Habilidad.EMBESTIDA;
            respiroHasta = ticks + 4;
        } else if (h == Habilidad.MAZAZO) {
            encadenar = Habilidad.BARRIDO;
            respiroHasta = ticks + 4;
        }
        if (!aturdido()) liberar();
        nombreBarra();
    }

    private void cortarTecnica() {
        if (actual == null) return;
        Tecnica tec = actual;
        actual = null;
        try {
            tec.cortar();
        } catch (Throwable ignorado) {
            // Lo que no se pudo cortar lo retira limpiar() al acabar.
        }
        if (!aturdido()) liberar();
        nombreBarra();
    }

    /** /calamity vigilant ability y /anomaly test: suelta esa habilidad ya. Devuelve por que no, o null. */
    String forzar(Habilidad h) {
        if (estado != Estado.PELEA) return "aún está saliendo de la tierra";
        Player obj = objetivo();
        if (obj == null) return "no tiene a nadie a quien ir";
        boolean mont = montado();
        switch (h.actor) {
            case PAREJA -> {
                if (!mont) return "va a pie: esa es de la pareja montada (prueba remount)";
            }
            case JINETE -> {
                if (mont && h == Habilidad.BARRIDO) {
                    arriba = new BarridoAlto();
                    proximoArriba = ticks + a.barridoArribaEspera;
                    return null;
                }
                if (mont) return "va montado: esa es del jinete a pie (prueba dismount)";
            }
            case MONTURA -> {
                if (!monturaViva()) return "no tiene montura";
                if (mont || montadoDeseado) return "la bestia lleva al jinete: la estampida es sin jinete (prueba dismount)";
                suelta = new Embestida(obj, Habilidad.ESTAMPIDA, a.estampidaLargo);
                modoSuelta = Suelta.ESPERA;
                return null;
            }
            case PASO -> {
                if (h == Habilidad.DESMONTE && !mont) return "ya va a pie";
                if (h == Habilidad.REMONTE && (mont || !monturaViva())) return mont ? "ya va montado" : "no tiene montura";
            }
        }
        if (aturdido()) aturdidoHasta = ticks;
        cortarTecnica();
        if (h == Habilidad.REMONTE) suelta = null;
        empezar(h, obj, false);
        return null;
    }

    /** Si ahora mismo no persigue con su IA (aparece, una habilidad o aturdido): nadie le cambia el objetivo. */
    boolean ocupado() {
        return estado != Estado.PELEA || actual != null || aturdido();
    }

    /**
     * Si "quien" (la bestia o el jinete) puede apuntar a ese jugador ahora (Vigilante.onObjetivo): la
     * bestia solo con el jinete encima y sin habilidad en curso; el jinete solo a pie y sin habilidad.
     */
    boolean puedeApuntar(Entity quien, Player p) {
        if (ocupado() || !valido(p)) return false;
        if (esMontura(quien)) return montadoDeseado;
        if (esJinete(quien)) return !montadoDeseado;
        return false;
    }

    // ================================================================ cuerpo, IA y objetivo

    private double velocidadActual() {
        return a.velocidad * (furia ? a.furiaVelocidad : 1);
    }

    /** La pareja quieta para una habilidad: sin velocidad de andar ni objetivo (su fisica sigue, para saltar y embestir). */
    private void sujetar() {
        if (!monturaViva()) return;
        Compat.setAttribute(montura, "movement_speed", 0);
        Cerebro.soltar(montura);
    }

    /** El jinete quieto para una habilidad a pie (su fisica sigue: el mazazo es un salto de verdad). */
    private void sujetarJinete() {
        if (!jineteVivo()) return;
        Compat.setAttribute(jinete, "movement_speed", 0);
        try {
            jinete.setTarget(null);
            jinete.getPathfinder().stopPathfinding();
        } catch (Throwable ignorado) {
            // Sin Pathfinder se queda quieto igual por la velocidad 0.
        }
    }

    /** Vuelve a perseguir: montado, la IA de la bestia; a pie, la del jinete. */
    private void liberar() {
        if (estado != Estado.PELEA) return;
        Player obj = objetivo();
        if (montadoDeseado && monturaViva()) {
            if (!montura.hasAI()) montura.setAI(true);
            Compat.setAttribute(montura, "movement_speed", velocidadActual());
            if (obj != null) forzarObjetivo(obj);
            return;
        }
        if (obj != null) liberarJinete(obj);
    }

    /** A pie: la IA del esqueleto, rapido y con su objetivo fijado. */
    private void liberarJinete(Player obj) {
        if (!jineteVivo() || montadoDeseado || oculto) return;
        if (!jinete.hasAI()) jinete.setAI(true);
        if (actual != null && actual.h.actor != Actor.PAREJA) return;
        Compat.setAttribute(jinete, "movement_speed", a.jineteVelocidad * (furia ? a.furiaVelocidad : 1));
        try {
            if (jinete.getTarget() != obj) jinete.setTarget(obj);
            if (PeleaAmbush.distPlano(jinete.getLocation(), obj.getLocation()) > 2.5) jinete.getPathfinder().moveTo(obj, 1.0);
        } catch (Throwable ignorado) {
            // Sin objetivo forzado le queda el suyo: el jugador mas cercano.
        }
    }

    private void forzarObjetivo(Player obj) {
        if (!monturaViva() || obj == null) return;
        if (Cerebro.apuntar(montura, obj)) return;
        // Sin acceso a su cerebro: lo de Bukkit, y que ande hacia el con el Pathfinder de Paper.
        try {
            montura.setTarget(obj);
            if (PeleaAmbush.distPlano(montura.getLocation(), obj.getLocation()) > 3) montura.getPathfinder().moveTo(obj, 1.0);
        } catch (Throwable ignorado) {
            // Sin objetivo forzado le queda el suyo: el jugador mas cercano.
        }
    }

    /**
     * El cerebro del Zoglin (Brain de vanilla): Mob#setTarget de Bukkit solo cambia un campo que su
     * cerebro no lee. Aqui, por reflexion y con los nombres de Mojang que usa Paper 26, lo mismo que hace
     * el propio Zoglin cuando le pegan: borra CANT_REACH_WALK_TARGET_SINCE y pone ATTACK_TARGET con
     * caducidad. Si los nombres cambian algun dia, se apaga solo y la pareja se lleva a mano (conducir).
     */
    private static final class Cerebro {
        private static boolean roto;
        private static Method handleBestia, handleJugador, cerebro, poner, borrar;
        private static Object ataque, andar, noLlega;

        private static Object cerebroDe(Zoglin z) throws Exception {
            if (handleBestia == null) {
                handleBestia = z.getClass().getMethod("getHandle");
                Object nms = handleBestia.invoke(z);
                cerebro = nms.getClass().getMethod("getBrain");
                Object brain = cerebro.invoke(nms);
                Class<?> tipo = Class.forName("net.minecraft.world.entity.ai.memory.MemoryModuleType", false,
                        nms.getClass().getClassLoader());
                poner = brain.getClass().getMethod("setMemoryWithExpiry", tipo, Object.class, long.class);
                borrar = brain.getClass().getMethod("eraseMemory", tipo);
                ataque = tipo.getField("ATTACK_TARGET").get(null);
                andar = tipo.getField("WALK_TARGET").get(null);
                noLlega = tipo.getField("CANT_REACH_WALK_TARGET_SINCE").get(null);
            }
            return cerebro.invoke(handleBestia.invoke(z));
        }

        static boolean apuntar(Zoglin z, Player p) {
            if (roto || z == null || p == null) return false;
            try {
                Object brain = cerebroDe(z);
                if (handleJugador == null) handleJugador = p.getClass().getMethod("getHandle");
                Object jugador = handleJugador.invoke(p);
                borrar.invoke(brain, noLlega);
                poner.invoke(brain, ataque, jugador, 200L);
                return true;
            } catch (Throwable t) {
                roto = true;
                return false;
            }
        }

        static void soltar(Zoglin z) {
            if (z == null) return;
            try {
                z.getPathfinder().stopPathfinding();
            } catch (Throwable ignorado) {
                // Sin Pathfinder: se queda quieto igual por la velocidad 0.
            }
            try {
                z.setTarget(null);
            } catch (Throwable ignorado) {
                // Nada que soltar.
            }
            if (roto) return;
            try {
                Object brain = cerebroDe(z);
                borrar.invoke(brain, ataque);
                borrar.invoke(brain, andar);
            } catch (Throwable t) {
                roto = true;
            }
        }
    }

    private boolean valido(Player p) {
        return p != null && Fx.isFightable(p) && jinete != null && p.getWorld() == jinete.getWorld() && !hc.enSpawn(p);
    }

    /** Si puede ser su objetivo (lo mira el EntityTargetEvent de Vigilante). */
    boolean objetivoValido(Player p) {
        return valido(p);
    }

    /** A quien va: su presa si esta a 40 bloques; si no, el mas cercano. */
    Player objetivo() {
        if (jinete == null) return null;
        Location l = jinete.getLocation();
        if (presa != null) {
            Player p = hc.plugin().getServer().getPlayer(presa);
            if (valido(p) && p.getLocation().distanceSquared(l) <= 40 * 40) return p;
        }
        Player mejor = null;
        double md = 40 * 40;
        for (Player p : Fx.playersNear(l, 40)) {
            if (!valido(p)) continue;
            double d = p.getLocation().distanceSquared(l);
            if (d <= md) {
                md = d;
                mejor = p;
            }
        }
        return mejor;
    }

    private List<Player> validosCerca(Location c, double radio) {
        List<Player> out = new ArrayList<>();
        for (Player p : Fx.playersNear(c, radio)) if (valido(p)) out.add(p);
        return out;
    }

    /** Si ese jugador esta a "radio" del jinete o de la bestia (para la ley 6: Vigilante.persigue). */
    boolean cerca(Player p, double radio) {
        if (p == null) return false;
        for (LivingEntity e : new LivingEntity[]{jinete, montura}) {
            if (e != null && e.isValid() && p.getWorld() == e.getWorld() && p.getLocation().distanceSquared(e.getLocation()) <= radio * radio) {
                return true;
            }
        }
        return false;
    }

    /** Donde esta la pelea: la bestia si lleva al jinete; si no, el jinete. */
    private Location pie() {
        return montadoDeseado && monturaViva() ? montura.getLocation() : jinete.getLocation();
    }

    private Location pieMontura() {
        return montura.getLocation();
    }

    /**
     * Encara lo que se ve hacia "yaw" (el encarar de Alba): montado, la bestia, el jinete y su maniqui a
     * la vez con setRotation (un teleport bajaria al pasajero); a pie, el jinete y su maniqui.
     */
    private void mirar(float yaw) {
        fijarYaw(yaw);
        try {
            if (montadoDeseado && monturaViva()) {
                montura.setRotation(yaw, 0);
                montura.setBodyYaw(yaw);
            }
            if (jineteVivo()) {
                jinete.setRotation(yaw, 0);
                jinete.setBodyYaw(yaw);
            }
        } catch (Throwable ignorado) {
            // Sin girar el cuerpo, el aviso del suelo dice igual a donde va el golpe.
        }
        if (montadoDeseado && conNpc) npc.girar(yaw, 0);
    }

    /** La bestia suelta hacia "yaw" (sin tocar al jinete, que va a pie). */
    private void mirarMontura(float yaw) {
        if (!monturaViva()) return;
        try {
            montura.setRotation(yaw, 0);
            montura.setBodyYaw(yaw);
        } catch (Throwable ignorado) {
            // Sin girarla, su linea en el suelo dice a donde va.
        }
    }

    /** Mueve a la bestia (y a quien lleve encima: vanilla teleporta a los pasajeros con su vehiculo). */
    private boolean mover(Location l) {
        if (l == null || !monturaViva() || hc.enSpawn(l) || !Vigilante.cargado(l)) return false;
        return hc.amenazas().teleportar(montura, l);
    }

    private void impulsar(Vector v) {
        if (!monturaViva()) return;
        try {
            montura.setVelocity(limitar(v));
        } catch (Throwable ignorado) {
            // Una velocidad rara (NaN) no se aplica: se queda donde esta.
        }
    }

    private void impulsarJinete(Vector v) {
        if (!jineteVivo()) return;
        try {
            jinete.setVelocity(limitar(v));
        } catch (Throwable ignorado) {
            // Una velocidad rara (NaN) no se aplica: se queda donde esta.
        }
    }

    /** Lleva a la bestia a mano hacia "meta" (el corcel de Alba: velocidad y encarar), a "bloquesPorTick" de media. */
    private void conducir(Location meta, double bloquesPorTick) {
        if (!monturaViva() || meta == null || meta.getWorld() != montura.getWorld()) return;
        Location l = montura.getLocation();
        if (PeleaAmbush.distPlano(l, meta) < 1.2) return;
        float yaw = PeleaAmbush.yaw(l, meta);
        if (montadoDeseado) mirar(yaw);
        else mirarMontura(yaw);
        Vector d = PeleaAmbush.plano(l, meta, PeleaAmbush.dir(yaw)).multiply(impulsoSuelo(bloquesPorTick));
        impulsar(d.setY(enSuelo(montura) ? 0 : Math.min(0, montura.getVelocity().getY())));
    }

    /** La cabeza de un Zoglin que cornea (vanilla: el mismo efecto que su ataque). */
    private void anim() {
        if (!monturaViva()) return;
        try {
            montura.playEffect(EntityEffect.ENTITY_ATTACK);
        } catch (Throwable ignorado) {
            // Sin la animacion, el golpe se ve en el suelo.
        }
    }

    /** El brazo de la maza: lo blande el maniqui (o el esqueleto, si se le ve). */
    private void blandir() {
        if (conNpc && npc.valido()) {
            npc.blandir();
            return;
        }
        if (jineteVivo()) {
            try {
                jinete.swingMainHand();
            } catch (Throwable ignorado) {
                // Sin brazo, el golpe se oye y se ve en el suelo.
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean enSuelo(Entity e) {
        return e.isOnGround();
    }

    // ================================================================ golpes

    /**
     * Un golpe fuerte: una fraccion de la vida maxima de la victima (Vigilante.fraccionGolpe), por
     * DanoVerdadero con el tope de golpes.maximo, y en la misma ventana de golpes.ventana-ticks nunca mas
     * que golpes.maximo entre todos: con la vida llena no mata ni uno ni dos juntos.
     */
    void golpeFuerte(Player v, double base, String etiqueta) {
        if (v == null || !Fx.isFightable(v) || hc.enSpawn(v) || jinete == null) return;
        double f = Vigilante.fraccionGolpe(base, escala.multFraccion(), a);
        long ahora = Bukkit.getCurrentTick();
        double[] w = VENTANA.get(v.getUniqueId());
        if (w == null || ahora - w[0] > a.ventanaTicks) {
            // De paso se olvidan las ventanas viejas de otros: el mapa no crece con quien ya no pelea.
            VENTANA.values().removeIf(x -> ahora - x[0] > a.ventanaTicks * 4L);
            w = new double[]{ahora, 0};
            VENTANA.put(v.getUniqueId(), w);
        }
        double entra = Vigilante.recorteVentana(f, w[1], a.golpeMaximo);
        if (entra <= 0) return;
        w[1] += entra;
        double vidaMax = Compat.getAttribute(v, "max_health", 20);
        DanoVerdadero.aplicar(v, entra * vidaMax, a.golpeMaximo, jinete, etiqueta);
    }

    /** Un golpe normal de su maza (con armadura): el basico y el barrido desde arriba. Suya la muerte, si mata. */
    private void golpeMaza(Player v, double dano) {
        if (v == null || !Fx.isFightable(v) || hc.enSpawn(v) || !jineteVivo() || dano <= 0) return;
        golpeando = true;
        try {
            v.damage(dano, jinete);
        } catch (Throwable ignorado) {
            // Sin golpe, el empujon y el sonido se quedan.
        } finally {
            golpeando = false;
        }
    }

    /**
     * Si el golpe del jinete que esta entrando es de la pelea (un basico o un barrido). Los de su IA de
     * vanilla no lo son: el esqueleto visible sin maniqui pegaria con la maza y marchitaria
     * (Vigilante.onGolpe los cancela).
     */
    boolean golpeDeLaPelea() {
        return golpeando;
    }

    /** Ninguna velocidad pasa del tope del paquete por eje. */
    static Vector limitar(Vector v) {
        return new Vector(Fx.clamp(v.getX(), -VELOCIDAD_MAXIMA, VELOCIDAD_MAXIMA),
                Fx.clamp(v.getY(), -VELOCIDAD_MAXIMA, VELOCIDAD_MAXIMA), Fx.clamp(v.getZ(), -VELOCIDAD_MAXIMA, VELOCIDAD_MAXIMA));
    }

    /**
     * Un empujon (o una velocidad exacta). Si levanta, presta el vuelo un momento para que el servidor no
     * lo eche por volar; el prestamo se devuelve al empezar a caer, y la caida hace su dano.
     */
    private void empujar(Player v, Vector vel, boolean exacta) {
        if (v == null || !Fx.isFightable(v) || hc.enSpawn(v)) return;
        try {
            Vector lim = limitar(vel);
            if (lim.getY() > 0.1) prestarVuelo(v, lim.getY() > 1 ? 120 : 60);
            v.setVelocity(exacta ? lim : limitar(v.getVelocity().add(lim)));
        } catch (Throwable ignorado) {
            // Sin empujon el golpe ya ha entrado.
        }
    }

    /** Lo manda hacia arriba "altura" bloques (y un poco en "plano"). La caida, la suya. */
    private void lanzar(Player v, Vector plano, double altura) {
        empujar(v, plano.clone().setY(velocidadParaAltura(altura)), true);
    }

    private void prestarVuelo(Player v, int duracion) {
        Prestamo pr = vuelo.get(v.getUniqueId());
        if (pr != null) {
            pr.hasta = Math.max(pr.hasta, ticks + duracion);
            pr.cima = v.getLocation().getY();
            return;
        }
        if (v.getAllowFlight()) return;
        vuelo.put(v.getUniqueId(), new Prestamo(ticks + duracion, ticks, v.getLocation().getY()));
        v.setAllowFlight(true);
    }

    /**
     * Si el vuelo prestado se devuelve ya (puro): en cuanto baja medio bloque de lo mas alto que llego,
     * al tocar suelo pasado el despegue o al caducar. Con el vuelo permitido vanilla no hace dano de
     * caida; asi la caida de un golpe suyo hace el suyo, aunque mate.
     */
    static boolean devolverAlCaer(double cima, double y, boolean suelo, long prestadoTicks, boolean caducado) {
        return caducado || y < cima - 0.5 || (suelo && prestadoTicks >= 6);
    }

    /** Cada 2 ticks: nadie vuela de verdad con el vuelo prestado, y se devuelve en cuanto empieza a caer. */
    private void cortarVuelo() {
        if (vuelo.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, Prestamo>> it = vuelo.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Prestamo> e = it.next();
            Player p = hc.plugin().getServer().getPlayer(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            if (p.isFlying()) p.setFlying(false);
            Prestamo pr = e.getValue();
            double y = p.getLocation().getY();
            pr.cima = Math.max(pr.cima, y);
            if (devolverAlCaer(pr.cima, y, enSuelo(p), ticks - pr.desde, ticks >= pr.hasta)) {
                quitarVuelo(p);
                it.remove();
            }
        }
    }

    /** Devuelve el vuelo prestado que ya caduco (todos, con todos = true). */
    private void devolverVuelo(boolean todos) {
        if (vuelo.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, Prestamo>> it = vuelo.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Prestamo> e = it.next();
            if (!todos && e.getValue().hasta > ticks) continue;
            Player p = hc.plugin().getServer().getPlayer(e.getKey());
            if (p != null) quitarVuelo(p);
            it.remove();
        }
    }

    private static void quitarVuelo(Player p) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        p.setFlying(false);
        p.setAllowFlight(false);
    }

    /** Se desconecta: si tenia el vuelo prestado, se le quita antes de que se guarde. */
    void soltarVuelo(Player p) {
        if (p != null && vuelo.remove(p.getUniqueId()) != null) quitarVuelo(p);
    }

    /** Un golpe suyo de vanilla ha entrado (Vigilante.onGolpe): el mordisco de la bestia suena grave. */
    void haGolpeado(Player v, Entity quien) {
        if (esMontura(quien)) Compat.sound(montura.getWorld(), montura.getLocation(), "entity.zoglin.attack", 1.8f, 0.55f);
    }

    boolean aturdido() {
        return aturdidoHasta > ticks;
    }

    double factorRecibido() {
        return aturdido() ? 1 + a.aturdidoExtra : 1.0;
    }

    /** Dano de verdad al jinete: el maniqui se estremece, polvo de hueso y su quejido grave (como mucho cada medio segundo). */
    void dolor() {
        if (!jineteVivo()) return;
        World w = jinete.getWorld();
        Compat.spawn(w, Compat.BLOCK, jinete.getLocation().add(0, jinete.getHeight() * 0.6, 0), 8, 0.3, 0.4, 0.3, 0.05, hueso());
        if (ticks - ultimoDolor >= 10) {
            ultimoDolor = ticks;
            if (conNpc && npc.valido()) npc.dolor();
            else Compat.sound(w, jinete.getLocation(), "entity.wither_skeleton.hurt", 1.4f, 0.55f);
        }
    }

    /**
     * Un golpe de un jugador a la bestia o al maniqui (Vigilante): pasa al jinete con su mismo autor y su
     * base, y ahi Amenazas lo escala, lo topa y lo apunta (el mismo merito que pegarle a el). La bestia
     * se estremece y gruñe, para que se note que ha entrado.
     */
    void golpeAjeno(Player p, double base, boolean aLaMontura) {
        if (!jineteVivo() || estado != Estado.PELEA || oculto || base <= 0) return;
        if (aLaMontura && monturaViva() && ticks - ultimoDolorMontura >= 10) {
            ultimoDolorMontura = ticks;
            try {
                montura.playHurtAnimation(0f);
            } catch (Throwable ignorado) {
                // Sin estremecerse, la barra del jinete dice que ha entrado.
            }
            Compat.sound(montura.getWorld(), montura.getLocation(), "entity.zoglin.hurt", 1.4f, 0.5f);
            Compat.spawn(montura.getWorld(), Compat.BLOCK, montura.getLocation().add(0, alto * 0.55, 0), 6, ancho * 0.3,
                    alto * 0.25, ancho * 0.3, 0.05, carne());
        }
        jinete.damage(base, p);
    }

    /**
     * Que hacer con un golpe a una de sus entidades (puro). /kill pasa siempre (la salida del staff). Al
     * jinete, lo de siempre (Amenazas). A la bestia y al maniqui no les entra nada: lo de un jugador pasa
     * al jinete; lo demas (caidas, fuego, asfixia, otros mobs) se cancela sin mas.
     */
    static Redirige redirigir(Rol rol, boolean kill, boolean deJugador) {
        if (kill || rol == Rol.JINETE) return Redirige.PASA;
        return deJugador ? Redirige.AL_JINETE : Redirige.CANCELA;
    }

    private void aturdir(long duracion) {
        if (duracion <= 0) return;
        aturdidoHasta = ticks + duracion;
        sujetar();
        Location l = pie();
        Compat.sound(l.getWorld(), l, "entity.zoglin.ambient", 1.6f, 0.4f);
        Component c = Component.text("El ", Paleta.TEXTO).append(Component.text("Vigilante", Paleta.VIGILANTE))
                .append(Component.text(" se estrelló: queda aturdido y recibe más daño.", Paleta.TEXTO));
        for (Player p : Fx.viewersNear(l, 48)) hc.barra().aviso(p, c, 3);
        hc.plugin().bitacora().anotar("vigilante", "aturdido", presaNombre);
        nombreBarra();
    }

    private void finAturdido() {
        aturdidoHasta = 0;
        if (actual == null) liberar();
        nombreBarra();
    }

    // ================================================================ el jinete: basicos y barrido desde arriba

    /**
     * La caza (el hunt de Alba): nunca torreta. Al alcance, golpes BASICOS de verdad con la maza y su
     * espera: a pie, a quien tenga delante (jinete.basico.alcance); montado, a quien se arrime a la
     * bestia (mas espaciados). En furia, montado, en vez de basicos barre desde arriba con su aviso.
     */
    private void cazar() {
        if (estado != Estado.PELEA || oculto || aturdido() || !jineteVivo()) return;
        boolean mont = montado();
        if (mont && fase >= 4) {
            if (arriba == null && ticks >= proximoArriba && (actual == null || actual.h != Habilidad.HUNDIMIENTO)
                    && monturaViva() && enSuelo(montura) && !validosCerca(montura.getLocation(), radioArriba()).isEmpty()) {
                arriba = new BarridoAlto();
                proximoArriba = ticks + a.barridoArribaEspera;
            }
            return;
        }
        if (ticks < proximoBasico) return;
        if (actual != null && (actual.h.actor == Actor.JINETE || actual.h.actor == Actor.PASO || actual.h == Habilidad.HUNDIMIENTO)) return;
        Location c = mont ? montura.getLocation() : jinete.getLocation();
        double alcance = mont ? ancho / 2 + a.basicoAlcance : a.basicoAlcance;
        Player p = null;
        double md = alcance * alcance;
        for (Player o : Fx.playersNear(c, alcance + 1)) {
            if (!valido(o) || Math.abs(o.getLocation().getY() - c.getY()) > (mont ? 4.5 : 2.5)) continue;
            double dx = o.getLocation().getX() - c.getX(), dz = o.getLocation().getZ() - c.getZ();
            double d = dx * dx + dz * dz;
            if (d <= md) {
                md = d;
                p = o;
            }
        }
        if (p == null) return;
        proximoBasico = ticks + (mont ? a.basicoEsperaMontado : a.basicoEspera);
        basico(p);
    }

    /** Un golpe basico: la maza baja sobre uno, con su sonido, polvo y un empujon corto. */
    private void basico(Player p) {
        World w = p.getWorld();
        Location q = p.getLocation().add(0, 1, 0);
        float yaw = PeleaAmbush.yaw(jinete.getLocation(), p.getLocation());
        if (!montadoDeseado) mirar(yaw);
        else fijarYaw(yaw);
        blandir();
        Compat.spawn(w, Compat.BLOCK, p.getLocation().add(0, 0.2, 0), 10, 0.4, 0.1, 0.4, 0.1, materialSuelo(p.getLocation()));
        Compat.spawn(w, Compat.SMOKE, q, 6, 0.25, 0.3, 0.25, 0.02);
        Compat.sound(w, q, "item.mace.smash_ground", 1.2f, 0.7f);
        Compat.sound(w, q, "entity.player.attack.strong", 1.0f, 0.6f);
        golpeMaza(p, golpe * a.basicoDano);
        empujar(p, PeleaAmbush.plano(jinete.getLocation(), p.getLocation(), PeleaAmbush.dir(yaw)).multiply(0.45).setY(0.25), false);
    }

    private double radioArriba() {
        return ancho / 2 + 2.5;
    }

    // ================================================================ la bestia suelta (fase III)

    /**
     * Cada 2 ticks, sin jinete encima: no espera quieta como el caballo de Alba. Se retira al borde
     * (jinete.retirada bloques del jinete, del lado contrario a los jugadores), espera mirando la pelea y
     * de vez en cuando la cruza en Estampida; si alguien se le arrima, antes. Fuera de la III (furia, o el
     * guardian la llama) vuelve a por su jinete.
     */
    private void tickMontura(World w, boolean segundo) {
        if (oculto || estado != Estado.PELEA) return;
        if (actual != null && actual.h == Habilidad.REMONTE) return;
        if (segundo) {
            Cerebro.soltar(montura);
            Compat.setAttribute(montura, "movement_speed", 0);
        }
        if (suelta != null) {
            Tecnica tec = suelta;
            boolean fin = pasoSeguro(tec, w);
            tec.t += 2;
            if (fin && suelta == tec) {
                suelta = null;
                modoSuelta = Suelta.RETIRADA;
                borde = null;
            }
            return;
        }
        if (ticks < monturaQuietaHasta) return;
        if (fase != 3 || modoSuelta == Suelta.VUELVE) {
            modoSuelta = Suelta.VUELVE;
            if (vueltas % 2 == 0) conducir(jinete.getLocation(), remontePendiente ? 0.55 : 0.35);
            return;
        }
        Player obj = objetivo();
        Location l = montura.getLocation();
        if (borde == null || ticks - bordeDesde > 160 && modoSuelta == Suelta.RETIRADA) {
            borde = elegirBorde();
            bordeDesde = ticks;
            anclaSuelta = l;
            anclaSueltaDesde = ticks;
        }
        if (modoSuelta == Suelta.RETIRADA && borde != null) {
            if (PeleaAmbush.distPlano(l, borde) <= 2.0) {
                modoSuelta = Suelta.ESPERA;
            } else {
                if (vueltas % 2 == 0) conducir(borde, 0.3);
                // Si no avanza (un muro, un agujero), otro borde.
                if (anclaSuelta == null || PeleaAmbush.distPlano(anclaSuelta, l) > 1.5) {
                    anclaSuelta = l;
                    anclaSueltaDesde = ticks;
                } else if (ticks - anclaSueltaDesde > 60) {
                    borde = null;
                }
                return;
            }
        }
        // Si la pelea se ha ido lejos de su borde, busca otro cerca del jinete.
        if (PeleaAmbush.distPlano(l, jinete.getLocation()) > a.retirada + 8) {
            modoSuelta = Suelta.RETIRADA;
            borde = null;
            return;
        }
        // Esperando en el borde: mira la pelea, escarba y gruñe.
        mirarMontura(PeleaAmbush.yaw(l, jinete.getLocation()));
        if (vueltas % 12 == 0) {
            temblor(w, l, ancho * 0.35, 6);
            Compat.sound(w, l, "entity.hoglin.step", 1.4f, 0.5f);
        }
        if (obj == null) return;
        boolean arrimado = false;
        for (Player p : Fx.playersNear(l, ancho / 2 + 2.2)) {
            if (valido(p)) {
                arrimado = true;
                break;
            }
        }
        long listaEn = listo.getOrDefault(Habilidad.ESTAMPIDA, 0L);
        int espera = a.hab(Habilidad.ESTAMPIDA).espera();
        if (ticks >= listaEn || arrimado && ticks >= listaEn - espera / 2) {
            Player blanco = blancoEstampida();
            if (blanco == null) blanco = obj;
            suelta = new Embestida(blanco, Habilidad.ESTAMPIDA, a.estampidaLargo);
            listo.put(Habilidad.ESTAMPIDA, ticks + espera);
        }
    }

    /** Un borde a jinete.retirada del jinete, del lado contrario al jugador mas cercano, donde quepa la bestia. */
    private Location elegirBorde() {
        Location c = jinete.getLocation();
        Player obj = objetivo();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Vector lejos = obj != null ? PeleaAmbush.plano(obj.getLocation(), c, PeleaAmbush.dir(r.nextFloat() * 360f))
                : PeleaAmbush.dir(r.nextFloat() * 360f);
        double[] giros = {0, 0.6, -0.6, 1.2, -1.2, 2.0, -2.0, Math.PI};
        for (double g : giros) {
            Vector d = PeleaAmbush.rotar(lejos, g + r.nextDouble(-0.2, 0.2)).multiply(a.retirada);
            Location cand = c.clone().add(d);
            if (!Vigilante.cargado(cand) || hc.enSpawn(cand)) continue;
            Location h = hueco(cand, a, 3, x -> !hc.enSpawn(x));
            if (h != null && Math.abs(h.getY() - c.getY()) <= 8) return h;
        }
        return null;
    }

    /** A quien va la estampida: alguien en la pelea (cerca del jinete), para que la cruce. */
    private Player blancoEstampida() {
        List<Player> c = validosCerca(jinete.getLocation(), 14);
        c.removeIf(p -> !Vigilante.cargado(p.getLocation()));
        return c.isEmpty() ? null : c.get(ThreadLocalRandom.current().nextInt(c.size()));
    }

    // ================================================================ suelo, avisos y bloques que saltan

    static int tono(double k) {
        return PeleaParca.mezcla(RGB_AVISO_DESDE, RGB_AVISO_HASTA, k);
    }

    private static Location suelo(Location l) {
        return Fx.ground(l, 4).add(0, 0.12, 0);
    }

    private static BlockData carne() {
        if (carne == null) carne = Material.NETHER_WART_BLOCK.createBlockData();
        return carne;
    }

    private static BlockData hueso() {
        if (hueso == null) hueso = Material.BONE_BLOCK.createBlockData();
        return hueso;
    }

    /**
     * Lo que salta del suelo (puro, por nombre): tierra, piedra, arena, madera... Nada que parezca botin
     * (menas, bloques de metal, amatista) ni bloques tecnicos.
     */
    static boolean valioso(String nombre) {
        String n = nombre == null ? "" : nombre.toUpperCase(Locale.ROOT);
        return n.contains("ORE") || n.contains("DIAMOND") || n.contains("EMERALD") || n.contains("GOLD") || n.contains("NETHERITE")
                || n.equals("IRON_BLOCK") || n.contains("ANCIENT_DEBRIS") || n.contains("LAPIS") || n.equals("REDSTONE_BLOCK")
                || n.contains("AMETHYST") || n.contains("SPAWNER") || n.contains("BEDROCK") || n.contains("BARRIER")
                || n.contains("COMMAND") || n.contains("STRUCTURE") || n.contains("JIGSAW") || n.contains("REINFORCED")
                || n.contains("BEACON") || n.contains("VAULT") || n.contains("TRIAL") || n.contains("SHULKER");
    }

    /** El bloque del suelo bajo "l" para las particulas y los bloques que saltan; tierra si no vale. */
    static BlockData materialSuelo(Location l) {
        try {
            Block b = l.getBlock();
            if (b.isPassable()) b = b.getRelative(BlockFace.DOWN);
            Material m = b.getType();
            if (m.isSolid() && m.isOccluding() && !m.isInteractable() && !valioso(m.name())) return m.createBlockData();
            World.Environment env = l.getWorld() == null ? World.Environment.NORMAL : l.getWorld().getEnvironment();
            Material f = env == World.Environment.NETHER ? Material.NETHERRACK : env == World.Environment.THE_END ? Material.END_STONE
                    : l.getY() < 0 ? Material.COBBLED_DEEPSLATE : Material.DIRT;
            return f.createBlockData();
        } catch (Throwable t) {
            return Material.DIRT.createBlockData();
        }
    }

    /** Los puntos de un circulo a ras de suelo (solo en chunks cargados), para pintarlo sin buscar el suelo cada vez. */
    private static List<Location> anillo(Location c, double r) {
        List<Location> out = new ArrayList<>();
        int puntos = Math.max(12, (int) (r * 6));
        Fx.ring(c, r, puntos, l -> {
            if (Vigilante.cargado(l)) out.add(suelo(l));
        });
        return out;
    }

    /** Una franja recta a ras de suelo: el centro y los dos bordes a "lado", cada bloque. */
    private static List<Location> franja(Location desde, Vector dir, double largo, double lado) {
        List<Location> out = new ArrayList<>();
        Vector perp = new Vector(-dir.getZ(), 0, dir.getX()).multiply(lado);
        for (double d = 1; d <= largo; d += 1.0) {
            Location c = desde.clone().add(dir.clone().multiply(d));
            for (Location l : new Location[]{c, c.clone().add(perp), c.clone().subtract(perp)}) {
                if (Vigilante.cargado(l)) out.add(suelo(l));
            }
        }
        return out;
    }

    /** Los puntos de un arco delante (yaw), a ras de suelo: el borde y una linea interior, para el barrido. */
    private static List<Location> arco(Location c, float yaw, double radio, double angulo) {
        List<Location> out = new ArrayList<>();
        Vector dir = PeleaAmbush.dir(yaw);
        double rad = Math.toRadians(angulo);
        for (double r : new double[]{radio, radio * 0.6}) {
            int puntos = Math.max(8, (int) (r * angulo / 25));
            Fx.arc(c, dir, r, rad, puntos, l -> {
                if (Vigilante.cargado(l)) out.add(suelo(l));
            });
        }
        return out;
    }

    /**
     * Si un punto (dx, dz desde el centro) cae dentro del arco del barrido (puro): a "radio" o menos y a
     * menos de la mitad de "angulo" grados del frente (yaw).
     */
    static boolean enArco(double dx, double dz, float yaw, double radio, double angulo) {
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > radio) return false;
        if (d < 0.8) return true;
        Vector f = PeleaAmbush.dir(yaw);
        double cos = (dx * f.getX() + dz * f.getZ()) / d;
        return cos >= Math.cos(Math.toRadians(angulo / 2));
    }

    /** El aviso: polvo del color del momento y, si hay bloque, el suelo agrietandose (una de cada tres). */
    private static void pintar(World w, List<Location> ps, int rgb, BlockData bd) {
        if (ps == null) return;
        Particle.DustOptions d = Compat.dust(rgb, 1.4f);
        for (int i = 0; i < ps.size(); i++) {
            Location l = ps.get(i);
            Compat.spawn(w, Compat.DUST, l, 1, 0, 0, 0, 0, d);
            if (bd != null && i % 3 == 0) Compat.spawn(w, Compat.BLOCK, l, 2, 0.15, 0.04, 0.15, 0, bd);
        }
    }

    /** El suelo que se remueve en un punto. */
    private static void temblor(World w, Location l, double ancho, int n) {
        Compat.spawn(w, Compat.BLOCK, l.clone().add(0, 0.15, 0), n, ancho, 0.05, ancho, 0.12, materialSuelo(l));
    }

    /** Un bloque del suelo que salta: un FallingBlock que no se coloca ni suelta nada, con su tope. */
    private void saltar(Location desde, Vector vel, BlockData bd) {
        if (desde == null || desde.getWorld() == null || bd == null || efimeros.size() >= a.efimerosMaximo) return;
        if (!Vigilante.cargado(desde) || hc.enSpawn(desde)) return;
        String marca = jinete == null ? "vigilante" : jinete.getUniqueId().toString();
        Vector v = limitar(vel);
        FallingBlock fb;
        try {
            fb = desde.getWorld().spawn(desde, FallingBlock.class, f -> {
                f.setBlockData(bd);
                f.setDropItem(false);
                f.setCancelDrop(true);
                f.setHurtEntities(false);
                f.setPersistent(false);
                f.getPersistentDataContainer().set(Marcas.VIGILANTE, PersistentDataType.STRING, marca);
                f.setVelocity(v);
            });
        } catch (Throwable t) {
            try {
                fb = desde.getWorld().spawnFallingBlock(desde, bd);
                fb.setDropItem(false);
                fb.setCancelDrop(true);
                fb.setHurtEntities(false);
                fb.setPersistent(false);
                fb.getPersistentDataContainer().set(Marcas.VIGILANTE, PersistentDataType.STRING, marca);
                fb.setVelocity(v);
            } catch (Throwable t2) {
                return;
            }
        }
        if (fb != null && fb.isValid()) efimeros.add(new Efimero(fb, ticks + a.efimerosVida));
    }

    /** "cuantos" bloques del suelo saltan hacia fuera desde un circulo de radio "radio" alrededor de "c". */
    private void saltarAnillo(Location c, double radio, int cuantos, double fuerza) {
        int n = Math.min(cuantos, a.efimerosPorGolpe);
        if (n <= 0) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ang0 = r.nextDouble(Math.PI * 2);
        for (int i = 0; i < n; i++) {
            double ang = ang0 + i * Math.PI * 2 / n + r.nextDouble(-0.2, 0.2);
            double d = radio + r.nextDouble(0, 1.2);
            Location l = c.clone().add(Math.cos(ang) * d, 1.5, Math.sin(ang) * d);
            if (!Vigilante.cargado(l)) continue;
            Location g = Fx.ground(l, 4);
            if (Math.abs(g.getY() - c.getY()) > 3) continue;
            Vector v = new Vector(Math.cos(ang), 0, Math.sin(ang)).multiply(r.nextDouble(0.15, 0.32) * fuerza)
                    .setY(r.nextDouble(0.45, 0.75) * fuerza);
            saltar(g.add(0, 0.1, 0), v, materialSuelo(g));
        }
    }

    /** El suelo revienta: polvo del propio bloque, una columna de polvo, humo y bloques que saltan. */
    private void estallidoSuelo(World w, Location c, double radio, int cuantos, double fuerza) {
        BlockData bd = materialSuelo(c);
        Compat.spawn(w, Compat.BLOCK, c.clone().add(0, 0.3, 0), 70, radio * 0.7, 0.3, radio * 0.7, 0.18, bd);
        Compat.spawn(w, Compat.DUST_PILLAR, c.clone().add(0, 0.2, 0), 40, radio * 0.6, 0.1, radio * 0.6, 0.3, bd);
        Compat.spawn(w, Compat.LARGE_SMOKE, c.clone().add(0, 0.5, 0), 14, radio * 0.6, 0.4, radio * 0.6, 0.02);
        saltarAnillo(c, radio, cuantos, fuerza);
    }

    /** Una onda de polvo por el suelo, en un anillo de radio "r" (lo visual tras un golpe). */
    private static void ondaSuelo(World w, Location c, double r) {
        BlockData bd = materialSuelo(c);
        int puntos = Math.max(12, (int) (r * 4));
        Fx.ring(c, r, puntos, l -> {
            if (!Vigilante.cargado(l)) return;
            Location s = suelo(l);
            Compat.spawn(w, Compat.BLOCK, s, 3, 0.25, 0.05, 0.25, 0.1, bd);
            Compat.spawn(w, Compat.DUST_PILLAR, s, 1, 0.1, 0, 0.1, 0.1, bd);
        });
    }

    /** Los bloques que ya cayeron o caducaron, fuera. */
    private void podarEfimeros() {
        if (efimeros.isEmpty()) return;
        for (Iterator<Efimero> it = efimeros.iterator(); it.hasNext(); ) {
            Efimero e = it.next();
            if (!e.bloque().isValid()) {
                it.remove();
            } else if (ticks >= e.hasta()) {
                Fx.safeRemove(e.bloque());
                it.remove();
            }
        }
    }

    private void quitarEfimeros() {
        for (Efimero e : efimeros) Fx.safeRemove(e.bloque());
        efimeros.clear();
    }

    /** Las cabezas negras: a velocidad constante, con su estela de humo; las que caducan se deshacen en el aire. */
    private void podarCalaveras(World w) {
        if (calaveras.isEmpty()) return;
        for (Iterator<Calavera> it = calaveras.iterator(); it.hasNext(); ) {
            Calavera c = it.next();
            WitherSkull s = c.cabeza();
            if (!s.isValid()) {
                it.remove();
                continue;
            }
            if (ticks >= c.hasta()) {
                Compat.spawn(w, Compat.LARGE_SMOKE, s.getLocation(), 8, 0.3, 0.3, 0.3, 0.02);
                Fx.safeRemove(s);
                it.remove();
                continue;
            }
            try {
                s.setVelocity(c.vel());
            } catch (Throwable ignorado) {
                // Sigue con la suya.
            }
            Compat.spawn(w, Compat.SMOKE, s.getLocation(), 3, 0.08, 0.08, 0.08, 0.01);
            Compat.spawn(w, Compat.ASH, s.getLocation(), 2, 0.15, 0.15, 0.15, 0.01);
        }
    }

    private void quitarCalaveras() {
        for (Calavera c : calaveras) Fx.safeRemove(c.cabeza());
        calaveras.clear();
    }

    /** Si esa cabeza negra es de esta pelea. */
    boolean esCalavera(Entity e) {
        if (e == null || calaveras.isEmpty()) return false;
        for (Calavera c : calaveras) if (c.cabeza().getUniqueId().equals(e.getUniqueId())) return true;
        return false;
    }

    /**
     * Una cabeza negra ha dado en algo (Vigilante.onProyectil): estalla sin fuego ni bloques. Golpe fuerte
     * a quien diera y a quien este a cabeza-negra.radio, con un poco de marchitamiento y un empujon. True
     * si se deshizo (en lo suyo no estalla: la atraviesa).
     */
    boolean impactoCalavera(WitherSkull s, Entity golpeado, Location donde) {
        if (golpeado != null && esNuestro(golpeado)) return false;
        calaveras.removeIf(c -> c.cabeza().getUniqueId().equals(s.getUniqueId()));
        Location c = donde != null ? donde : s.getLocation();
        World w = c.getWorld();
        Fx.safeRemove(s);
        if (w == null) return true;
        Compat.spawn(w, Compat.LARGE_SMOKE, c, 18, 0.5, 0.4, 0.5, 0.03);
        Compat.spawn(w, Compat.ASH, c, 24, 0.8, 0.6, 0.8, 0.02);
        Compat.spawn(w, Compat.BLOCK, c, 16, 0.5, 0.3, 0.5, 0.1, materialSuelo(c));
        Compat.soundPlayers(w, c, "entity.generic.explode", 1.4f, 0.7f);
        Compat.sound(w, c, "entity.wither_skeleton.hurt", 1.2f, 0.5f);
        Set<UUID> tocados = new HashSet<>();
        List<Player> quienes = new ArrayList<>(Fx.playersNear(c, a.cabezaRadio));
        if (golpeado instanceof Player p && !quienes.contains(p)) quienes.add(p);
        for (Player p : quienes) {
            if (!valido(p) || !tocados.add(p.getUniqueId())) continue;
            golpeFuerte(p, a.hab(Habilidad.CABEZA_NEGRA).fraccion(), Habilidad.CABEZA_NEGRA.nombre);
            if (a.cabezaMarchitez > 0) Compat.apply(p, "wither", a.cabezaMarchitez, 0);
            empujar(p, PeleaAmbush.plano(c, p.getLocation(), new Vector(0, 0, 1)).multiply(0.6).setY(0.3), false);
        }
        return true;
    }

    /** A quien lanza la cabeza negra (puro): el mas lejano entre minima y alcance; null si nadie se aleja tanto. */
    static Integer elegirLejano(double[] distancias, double minima, double alcance) {
        Integer mejor = null;
        for (int i = 0; i < distancias.length; i++) {
            double d = distancias[i];
            if (d < minima || d > alcance) continue;
            if (mejor == null || d > distancias[mejor]) mejor = i;
        }
        return mejor;
    }

    private Player blancoCabeza() {
        if (!jineteVivo()) return null;
        List<Player> c = validosCerca(jinete.getLocation(), a.cabezaAlcance);
        c.removeIf(p -> !Vigilante.cargado(p.getLocation()) || Math.abs(p.getLocation().getY() - jinete.getLocation().getY()) > 16);
        double[] d = new double[c.size()];
        for (int i = 0; i < d.length; i++) d[i] = PeleaAmbush.distPlano(jinete.getLocation(), c.get(i).getLocation());
        Integer i = elegirLejano(d, a.cabezaMinima, a.cabezaAlcance);
        return i == null ? null : c.get(i);
    }

    /** La vista tiembla un instante SIN dano a quien este cerca (la animacion de golpe del cliente). */
    private void sacudir(Location desde, double radio) {
        for (Player p : Fx.playersNear(desde, radio)) {
            try {
                p.playHurtAnimation(PeleaParca.ladoDe(p, desde));
            } catch (Throwable ignorado) {
                // Sin temblor el golpe se ha visto igual.
            }
        }
    }

    /**
     * Las pisadas: todo va en silencio, asi que suenan aqui, graves: las de la bestia cada 2,4 bloques
     * andados, con polvo; las del jinete a pie, cada 1,6, con sus huesos.
     */
    private void pasos(World w) {
        if (monturaViva() && !oculto) {
            Location l = montura.getLocation();
            if (ultimaPos != null && ultimaPos.getWorld() == l.getWorld()) {
                double d = PeleaAmbush.distPlano(ultimaPos, l);
                if (d < 4 && enSuelo(montura)) andado += d;
                if (andado >= 2.4) {
                    andado = 0;
                    Compat.sound(w, l, "entity.ravager.step", 1.2f, 0.55f);
                    Compat.spawn(w, Compat.BLOCK, l.clone().add(0, 0.1, 0), 5, ancho * 0.35, 0.05, ancho * 0.35, 0, materialSuelo(l));
                }
            }
            ultimaPos = l;
        }
        if (montadoDeseado || oculto) {
            ultimaPosJinete = null;
            return;
        }
        Location l = jinete.getLocation();
        if (ultimaPosJinete != null && ultimaPosJinete.getWorld() == l.getWorld()) {
            double d = PeleaAmbush.distPlano(ultimaPosJinete, l);
            if (d < 4 && enSuelo(jinete)) andadoJinete += d;
            if (andadoJinete >= 1.6) {
                andadoJinete = 0;
                Compat.sound(w, l, "entity.wither_skeleton.step", 1.0f, 0.6f);
                Compat.spawn(w, Compat.BLOCK, l.clone().add(0, 0.1, 0), 3, 0.3, 0.02, 0.3, 0, materialSuelo(l));
            }
        }
        ultimaPosJinete = l;
    }

    /**
     * El ambiente de Halloween: cada ambiente-segundos (con algo de azar) un lamento lejano, un susurro que
     * solo oye uno, la cueva o la respiracion de la bestia; su grunido cada pocos segundos, la risa grave
     * del jinete de vez en cuando y, en furia, un latido. Todo vanilla y grave. Nunca campanas.
     */
    private void ambiente(World w) {
        if (oculto) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location l = jinete.getLocation();
        if (ticks >= proximoGrunido && monturaViva()) {
            proximoGrunido = ticks + 70 + r.nextInt(60);
            Compat.sound(w, montura.getLocation(), r.nextBoolean() ? "entity.zoglin.ambient" : "entity.hoglin.ambient", 1.7f,
                    0.45f + r.nextFloat() * 0.1f);
        }
        if (ticks >= proximaRisa) {
            proximaRisa = ticks + 300 + r.nextInt(300);
            Compat.sound(w, l, r.nextInt(3) == 0 ? "entity.witch.celebrate" : "entity.wither_skeleton.ambient", 1.5f, 0.5f);
        }
        if (furia && vueltas % 15 == 0) Compat.sound(w, l, "entity.warden.heartbeat", 1.6f, 0.6f);
        if (ticks < proximoAmbiente) return;
        proximoAmbiente = ticks + a.ambienteTicks + r.nextInt(Math.max(1, a.ambienteTicks / 2));
        List<Player> cerca = validosCerca(l, a.radioPelea);
        if (cerca.isEmpty()) return;
        Player p = cerca.get(r.nextInt(cerca.size()));
        Location pl = p.getLocation();
        Vector lejos = PeleaAmbush.dir(r.nextFloat() * 360f).multiply(18 + r.nextInt(8));
        switch (r.nextInt(6)) {
            case 0 -> Compat.sound(w, pl.clone().add(lejos), "entity.ghast.ambient", 2.0f, 0.5f);
            case 1 -> p.playSound(pl.clone().add(r.nextDouble(-2, 2), 1, r.nextDouble(-2, 2)), "entity.vex.ambient",
                    SoundCategory.HOSTILE, 0.5f, 0.6f);
            case 2 -> p.playSound(pl, "ambient.cave", SoundCategory.AMBIENT, 0.8f, 0.5f);
            case 3 -> Compat.sound(w, pl.clone().add(lejos), "entity.elder_guardian.ambient", 1.6f, 0.5f);
            case 4 -> Compat.sound(w, pl.clone().add(lejos), "entity.ghast.scream", 1.4f, 0.45f);
            default -> Compat.sound(w, monturaViva() ? montura.getLocation() : l, "entity.ravager.ambient", 1.6f, 0.45f);
        }
    }

    // ================================================================ huecos para su caja

    static double anchoDe(double escala) {
        return ZOGLIN_ANCHO * escala;
    }

    static double altoDe(double escala) {
        return ZOGLIN_ALTO * escala;
    }

    /**
     * Lo mas alto de la pareja montada sobre los pies de la bestia (puro): el asiento del zoglin, y encima
     * lo mas alto entre el maniqui y el esqueleto con su cartel.
     */
    static double cimaPareja(double escalaCuerpo, double escalaJinete) {
        double asiento = ZOGLIN_ASIENTO * escalaCuerpo;
        double maniqui = (ALTO_JUGADOR - ASIENTO_JUGADOR) * escalaJinete;
        double k = escalaEsqueleto(escalaJinete);
        double esqueleto = (ALTO_ESQUELETO - ASIENTO_ESQUELETO) * k + CARTEL;
        return Math.max(altoDe(escalaCuerpo), asiento + Math.max(maniqui, esqueleto));
    }

    /** Lo que hay que esconder bajo tierra ahora: la pareja entera si lleva al jinete; si no, la bestia. */
    private double alturaOculta() {
        return montadoDeseado ? cimaPareja(a.escalaCuerpo, a.jineteEscala) : alto + 0.2;
    }

    /** Donde esta la bestia justo bajo tierra para salir en "suelo", con todo lo de encima tapado. */
    private Location enterrado(Location suelo) {
        return enterrado(suelo, 0);
    }

    /** Lo mismo con MARGEN_HONDO de mas: mientras espera o viaja bajo tierra no asoma ni el cartel. */
    private Location enterradoHondo(Location suelo) {
        return enterrado(suelo, MARGEN_HONDO);
    }

    private Location enterrado(Location suelo, double extra) {
        Location l = suelo.clone().subtract(0, alturaOculta() + 0.4 + extra, 0);
        double fondo = suelo.getWorld().getMinHeight() + 3;
        if (l.getY() < fondo) l.setY(fondo);
        return l;
    }

    /** Si su caja (ancho x alto) cabe de pie en "suelo": todo lo que ocupa es atravesable y no es liquido. */
    static boolean cabe(Location suelo, double ancho, double alto) {
        World w = suelo.getWorld();
        if (w == null) return false;
        double m = ancho / 2;
        int x0 = (int) Math.floor(suelo.getX() - m + 0.01), x1 = (int) Math.floor(suelo.getX() + m - 0.01);
        int z0 = (int) Math.floor(suelo.getZ() - m + 0.01), z1 = (int) Math.floor(suelo.getZ() + m - 0.01);
        int y0 = (int) Math.floor(suelo.getY() + 0.01), y1 = (int) Math.floor(suelo.getY() + alto - 0.01);
        // La caja (hasta 4x4) puede asomar al chunk de al lado: leer un bloque de un chunk descargado lo cargaria
        // en el hilo principal. Sin sus chunks cargados no cabe.
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) if (!w.isChunkLoaded(cx, cz)) return false;
        }
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!b.isPassable() || b.isLiquid()) return false;
                }
            }
        }
        return true;
    }

    /**
     * El sitio mas cercano a "c" (hasta "radio" bloques, por anillos) donde su caja cabe de pie, a ras de
     * suelo y a menos de 4 de altura; null si no hay. "vale" filtra (fuera de la zona spawn...).
     */
    static Location hueco(Location c, Vigilante.Ajustes a, int radio, Predicate<Location> vale) {
        if (c == null || c.getWorld() == null) return null;
        double an = anchoDe(a.escalaCuerpo), al = altoDe(a.escalaCuerpo);
        // Centrado en la esquina de un bloque: una caja de 3,6 ocupa 4x4 bloques (en el centro de uno, 5x5).
        Location centro = new Location(c.getWorld(), Math.round(c.getX()), c.getY(), Math.round(c.getZ()));
        for (int r = 0; r <= radio; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    Location l = centro.clone().add(dx, 2, dz);
                    if (!Vigilante.cargado(l)) continue;
                    Location g = Fx.ground(l, 8);
                    if (Math.abs(g.getY() - c.getY()) > 4) continue;
                    if (!cabe(g, an, al) || !vale.test(g)) continue;
                    g.setYaw(c.getYaw());
                    g.setPitch(0);
                    return g;
                }
            }
        }
        return null;
    }

    // ================================================================ fisica (pura)

    /** La altura maxima que alcanza un jugador lanzado hacia arriba con "vy" (vanilla, tick a tick). */
    static double alturaCon(double vy) {
        double y = 0, v = vy, max = 0;
        for (int i = 0; i < 400 && v > 0; i++) {
            y += v;
            max = Math.max(max, y);
            v = (v - GRAVEDAD) * ROCE_VERTICAL;
        }
        return max;
    }

    /** La velocidad hacia arriba que sube a un jugador "altura" bloques (con el tope del paquete). */
    static double velocidadParaAltura(double altura) {
        double lo = 0, hi = VELOCIDAD_MAXIMA;
        if (alturaCon(hi) <= altura) return hi;
        for (int i = 0; i < 40; i++) {
            double m = (lo + hi) / 2;
            if (alturaCon(m) < altura) lo = m;
            else hi = m;
        }
        return hi;
    }

    /** Los ticks en el aire de algo que sale del suelo con "vy" hasta volver a la misma altura. */
    static int ticksDeVuelo(double vy) {
        return ticksDeVuelo(vy, 0);
    }

    /**
     * Los ticks en el aire hasta caer a "dy" bloques sobre donde salio (mas alto, antes; si ni llega,
     * hasta lo mas alto del salto).
     */
    static int ticksDeVuelo(double vy, double dy) {
        double y = 0, v = vy;
        int n = 0;
        do {
            y += v;
            v = (v - GRAVEDAD) * ROCE_VERTICAL;
            n++;
        } while ((v > 0 || y > dy) && n < 400);
        return n;
    }

    /** Lo que avanza en horizontal en "ticks" ya en el aire con velocidad inicial v0. */
    static double avanceAire(double v0, int ticks) {
        return v0 * (1 - Math.pow(ROCE_AIRE, Math.max(0, ticks))) / (1 - ROCE_AIRE);
    }

    /**
     * Lo que avanza en horizontal en "ticks" de vuelo saliendo del suelo con v0: el primer tick avanza v0
     * y lo frena el suelo (vanilla mira el rozamiento antes de moverse), el resto el aire.
     */
    static double avanceDesdeSuelo(double v0, int ticks) {
        if (ticks <= 0) return 0;
        return v0 + ROCE_SUELO * avanceAire(v0, ticks - 1);
    }

    /** La velocidad horizontal para recorrer "distancia" en "ticks" de vuelo saliendo del suelo. */
    static double velocidadHorizontal(double distancia, int ticks) {
        double f = avanceDesdeSuelo(1, Math.max(1, ticks));
        return distancia / f;
    }

    /** Lo lejos que llega en horizontal alguien empujado desde el suelo con v0 y vy, mientras esta en el aire. */
    static double distanciaEmpuje(double v0, double vy) {
        return avanceDesdeSuelo(v0, ticksDeVuelo(vy));
    }

    /**
     * La velocidad que hay que darle en el suelo cada 2 ticks para que avance "bloquesPorTick" de media:
     * el primer tick avanza v y el suelo lo frena (x0,546) para el segundo.
     */
    static double impulsoSuelo(double bloquesPorTick) {
        return 2 * bloquesPorTick / (1 + ROCE_SUELO);
    }

    /** Donde pilla a cada uno el martillazo por su distancia al sitio donde cae (puro). */
    static Zona zona(double d, Vigilante.Ajustes a) {
        if (d <= a.martilloCerca) return Zona.CERCA;
        if (d <= a.martilloMedio) return Zona.MEDIO;
        if (d <= a.martilloLejos) return Zona.LEJOS;
        return Zona.FUERA;
    }

    /** Lo mismo para el mazazo del jinete, mas corto (puro). */
    static Zona zonaMazo(double d, Vigilante.Ajustes a) {
        if (d <= a.mazoCerca) return Zona.CERCA;
        if (d <= a.mazoMedio) return Zona.MEDIO;
        if (d <= a.mazoLejos) return Zona.LEJOS;
        return Zona.FUERA;
    }

    /** Los ticks de oscuridad del rugido (puro): siempre entre 1 y 3 s, como manda la regla de la oscuridad. */
    static int ticksOscuridad(double segundos) {
        return (int) Math.round(Fx.clamp(segundos, 1, 3) * 20);
    }

    // ================================================================ tecnicas

    /** Una habilidad en curso. paso() corre cada 2 ticks (t sube de 2 en 2 desde 0); true al acabar. */
    private abstract class Tecnica {
        final Habilidad h;
        long t;

        Tecnica(Habilidad h) {
            this.h = h;
        }

        abstract boolean paso(World w);

        void cortar() {
        }

        int aviso() {
            return a.hab(h).aviso();
        }

        double fraccion() {
            return a.hab(h).fraccion();
        }
    }

    /**
     * Martillazo: la bestia se agacha (el sitio donde va a caer se agrieta en dos circulos), salta muy
     * alto con el jinete encima hacia su objetivo y cae aplastando el suelo. Por distancia al sitio:
     * cerca (radio-cerca) sale lanzado hacia arriba altura-lanzado bloques; a media distancia
     * (radio-medio) sale empujado muy lejos; mas lejos (radio-lejos), un empujon leve. Una onda de polvo
     * y bloques corre por el suelo. En furia, dos seguidos (cada uno con su aviso).
     */
    private final class Martillazo extends Tecnica {
        final int golpes;
        final UUID blanco;
        int hechos;
        long desde;
        Location destino;
        List<Location> cerca, medio;
        BlockData bd;
        boolean enAire, despego;
        long tSalto;
        double onda = -1;
        Location centroOnda;
        /** Los bloques que aun pueden saltar en la onda de este golpe (el total por golpe no pasa de efimeros.por-golpe). */
        int quedan;

        Martillazo(Player obj) {
            super(Habilidad.MARTILLAZO);
            golpes = furia ? 2 : 1;
            blanco = obj.getUniqueId();
        }

        @Override
        boolean paso(World w) {
            if (onda >= 0) return ondaTrasGolpe(w);
            if (destino == null) elegir(w);
            long s = t - desde;
            float yaw = PeleaAmbush.yaw(pie(), destino);
            if (s < aviso()) {
                mirar(yaw);
                double k = s / (double) aviso();
                if (s % 4 == 0) {
                    pintar(w, cerca, tono(k), bd);
                    pintar(w, medio, PeleaParca.mezcla(RGB_AVISO_DESDE, tono(k), 0.5), null);
                }
                // Se agacha y escarba antes de saltar.
                if (s % 6 == 0) temblor(w, pie(), ancho * 0.4, 6);
                return false;
            }
            if (!enAire) {
                despegar(w);
                return false;
            }
            fijarYaw(yaw);
            long vuelo = t - tSalto;
            if (vuelo % 4 == 0) pintar(w, cerca, RGB_AVISO_HASTA, bd);
            if (vuelo % 8 == 0) pintar(w, medio, PeleaParca.mezcla(RGB_AVISO_DESDE, RGB_AVISO_HASTA, 0.5), null);
            boolean tierra = enSuelo(montura);
            if (!tierra) despego = true;
            // Aterriza; o no llego a despegar (un techo); o algo lo tiene en el aire demasiado.
            if ((despego && tierra && vuelo >= 6) || (!despego && tierra && vuelo >= 10) || vuelo >= 70) golpear(w);
            return false;
        }

        private void elegir(World w) {
            Player obj = hc.plugin().getServer().getPlayer(blanco);
            if (!valido(obj)) obj = objetivo();
            Location base = pie();
            Location d = null;
            if (obj != null && PeleaAmbush.distPlano(base, obj.getLocation()) <= a.martilloAlcance + 2
                    && Vigilante.cargado(obj.getLocation()) && !hc.enSpawn(obj)) {
                d = Fx.ground(obj.getLocation(), 6);
                if (hc.enSpawn(d) || Math.abs(d.getY() - base.getY()) > 12) d = null;
            }
            destino = d != null ? d : base.clone();
            cerca = anillo(destino, a.martilloCerca);
            medio = anillo(destino, a.martilloMedio);
            bd = materialSuelo(destino);
            desde = t;
            enAire = false;
            despego = false;
            Location l = pie();
            Compat.soundPlayers(w, l, "entity.ravager.attack", 1.8f, 0.5f);
            Compat.sound(w, l, "block.rooted_dirt.break", 1.6f, 0.5f);
            Compat.sound(w, l, "entity.wither_skeleton.ambient", 1.4f, 0.5f);
        }

        private void despegar(World w) {
            enAire = true;
            tSalto = t;
            Location base = pie();
            double vy = a.martilloSalto;
            int vuelo = ticksDeVuelo(vy, Fx.clamp(destino.getY() - base.getY(), -8, 6));
            double dist = PeleaAmbush.distPlano(base, destino);
            Vector dir = PeleaAmbush.plano(base, destino, PeleaAmbush.dir(base.getYaw()));
            double vh = Math.min(2.5, velocidadHorizontal(dist, vuelo));
            impulsar(dir.multiply(vh).setY(vy));
            anim();
            blandir();
            Compat.soundPlayers(w, base, "entity.zoglin.angry", 2.2f, 0.5f);
            Compat.sound(w, base, "entity.hoglin.attack", 1.8f, 0.45f);
            Compat.sound(w, base, "item.mace.smash_air", 1.6f, 0.5f);
            estallidoSuelo(w, base, ancho * 0.4, Math.min(4, a.efimerosPorGolpe / 3), 0.6);
        }

        private void golpear(World w) {
            Location aqui = pie();
            boolean honesto = PeleaAmbush.distPlano(aqui, destino) <= 3 && Math.abs(aqui.getY() - destino.getY()) <= 3;
            // El golpe va donde se aviso; si cayo lejos (un muro le corto el salto), solo empuja.
            Location c = honesto ? destino : aqui;
            anim();
            blandir();
            Compat.soundPlayers(w, c, "entity.generic.explode", 2.2f, 0.6f);
            Compat.soundPlayers(w, c, "entity.warden.attack_impact", 2.2f, 0.5f);
            Compat.sound(w, c, "item.mace.smash_ground_heavy", 2.0f, 0.55f);
            Compat.sound(w, c, "entity.zoglin.attack", 2.0f, 0.5f);
            Compat.sound(w, c, "block.rooted_dirt.break", 2.0f, 0.5f);
            int centro = (a.efimerosPorGolpe + 1) / 2;
            quedan = a.efimerosPorGolpe - centro;
            estallidoSuelo(w, c, ancho * 0.5, centro, 1.0);
            Vector otra = PeleaAmbush.dir(aqui.getYaw());
            for (Player v : Fx.playersNear(c, a.martilloLejos)) {
                if (!valido(v) || Math.abs(v.getLocation().getY() - c.getY()) > 4) continue;
                Vector fuera = PeleaAmbush.plano(c, v.getLocation(), otra);
                switch (zona(PeleaAmbush.distPlano(c, v.getLocation()), a)) {
                    case CERCA -> {
                        if (honesto) golpeFuerte(v, fraccion(), h.nombre);
                        lanzar(v, fuera.multiply(0.25), a.martilloAltura);
                    }
                    case MEDIO -> {
                        if (honesto) golpeFuerte(v, a.martilloFraccionMedio, h.nombre);
                        empujar(v, fuera.multiply(a.martilloEmpujeMedio).setY(EMPUJE_MEDIO_ALTO), true);
                    }
                    case LEJOS -> empujar(v, fuera.multiply(a.martilloEmpujeLejos).setY(0.35), true);
                    default -> {
                    }
                }
            }
            sacudir(c, a.martilloLejos + 8);
            hechos++;
            onda = ancho * 0.5;
            centroOnda = c;
        }

        /** La onda de polvo y bloques que corre por el suelo tras el golpe; luego el segundo (furia) o fin. */
        private boolean ondaTrasGolpe(World w) {
            onda += 2.0;
            ondaSuelo(w, centroOnda, onda);
            if (quedan > 0) {
                int n = Math.min(2, quedan);
                saltarAnillo(centroOnda, onda, n, 0.5);
                quedan -= n;
            }
            if (onda < a.martilloLejos) return false;
            onda = -1;
            if (hechos >= golpes) return true;
            destino = null;
            return false;
        }
    }

    /**
     * Cabezazo: agacha la cabeza, araña el suelo y una linea marca por donde va (sigue a su blanco la
     * primera mitad del aviso y luego se fija). Embiste por esa linea y al primero que pilla lo cornea y lo
     * manda por los aires (altura). Solo a uno.
     */
    private final class Lanzamiento extends Tecnica {
        final UUID blanco;
        float yaw;
        double dist;
        Vector dir;
        double largo, recorrido;
        Location antes;
        boolean carga;
        int quieto;

        Lanzamiento(Player obj) {
            super(Habilidad.LANZAMIENTO);
            blanco = obj.getUniqueId();
            yaw = PeleaAmbush.yaw(pie(), obj.getLocation());
            dist = PeleaAmbush.distPlano(pie(), obj.getLocation());
        }

        @Override
        boolean paso(World w) {
            if (t < aviso()) {
                Player v = hc.plugin().getServer().getPlayer(blanco);
                if (valido(v) && t < aviso() / 2) {
                    yaw = PeleaAmbush.yaw(pie(), v.getLocation());
                    dist = PeleaAmbush.distPlano(pie(), v.getLocation());
                }
                mirar(yaw);
                if (t == 0) {
                    Compat.soundPlayers(w, pie(), "entity.zoglin.angry", 1.8f, 0.75f);
                    Compat.sound(w, pie(), "entity.hoglin.ambient", 1.6f, 0.55f);
                }
                if (t % 6 == 0) {
                    Compat.sound(w, pie(), "entity.hoglin.step", 1.4f, 0.5f);
                    temblor(w, pie().add(PeleaAmbush.dir(yaw).multiply(ancho * 0.4)), 0.5, 5);
                }
                if (t % 4 == 0) {
                    double l = Math.min(a.lanzaAlcance + 2, dist + 2);
                    pintar(w, franja(pie(), PeleaAmbush.dir(yaw), l, 1.0), tono(t / (double) aviso()), materialSuelo(pie()));
                }
                return false;
            }
            if (!carga) {
                carga = true;
                dir = PeleaAmbush.dir(yaw);
                largo = Math.min(a.lanzaAlcance + 2, dist + 2);
                antes = pie();
                Compat.soundPlayers(w, pie(), "entity.zoglin.attack", 1.8f, 0.6f);
            }
            long s = t - aviso();
            mirar(yaw);
            Vector v0 = dir.clone().multiply(impulsoSuelo(a.lanzaVelocidad));
            impulsar(v0.setY(enSuelo(montura) ? 0 : Math.min(0, montura.getVelocity().getY())));
            Location ahora = pie();
            double paso = PeleaAmbush.distPlano(antes, ahora);
            recorrido += paso;
            quieto = s >= 4 && paso < 0.2 ? quieto + 1 : 0;
            if (s % 4 == 0) temblor(w, ahora, ancho * 0.35, 6);
            for (Player p : Fx.playersNear(ahora, ancho / 2 + 1.6)) {
                if (!valido(p) || Math.abs(p.getLocation().getY() - ahora.getY()) > 3) continue;
                cornear(w, p);
                return true;
            }
            antes = ahora;
            if (hc.enSpawn(ahora.clone().add(dir.clone().multiply(3)))) return frenar();
            return recorrido >= largo || quieto >= 2 || s > 30 ? frenar() : false;
        }

        private void cornear(World w, Player p) {
            anim();
            Location l = p.getLocation();
            Compat.soundPlayers(w, l, "entity.warden.attack_impact", 2.0f, 0.7f);
            Compat.sound(w, l, "entity.zoglin.attack", 2.0f, 0.5f);
            Compat.spawn(w, Compat.BLOCK, l.clone().add(0, 0.2, 0), 20, 0.6, 0.1, 0.6, 0.15, materialSuelo(l));
            golpeFuerte(p, fraccion(), h.nombre);
            lanzar(p, dir.clone().multiply(1.3), a.lanzaAltura);
            frenar();
        }

        private boolean frenar() {
            impulsar(dir == null ? new Vector() : dir.clone().multiply(0.15));
            return true;
        }
    }

    /**
     * Embestida: marca una franja recta en el suelo ("largo") y la cruza a toda velocidad
     * (embestida.velocidad bloques por tick). A quien pille lo aparta lejos hacia un lado y hacia delante.
     * Con el jinete encima, contra una pared queda aturdido (embestida.aturdido-segundos) y recibe mas
     * dano. Tambien es la Estampida de la bestia suelta (fase III): mas larga, desde el borde y cruzando la
     * pelea; contra una pared solo se queda quieta un momento (el jinete sigue a lo suyo).
     */
    private final class Embestida extends Tecnica {
        final boolean sola;
        final double largoMax;
        final Location origen;
        final float yaw;
        final Vector dir;
        final List<Location> marca;
        final BlockData bd;
        final Set<UUID> tocados = new HashSet<>();
        double recorrido;
        Location antes;
        boolean carga;
        int quieto;

        Embestida(Player obj, Habilidad h, double largo) {
            super(h);
            sola = h == Habilidad.ESTAMPIDA;
            origen = pieMontura();
            yaw = PeleaAmbush.yaw(origen, obj.getLocation());
            dir = PeleaAmbush.dir(yaw);
            // La estampida cruza: hasta su blanco y diez bloques mas alla, sin pasar de su largo.
            largoMax = sola ? Math.min(largo, PeleaAmbush.distPlano(origen, obj.getLocation()) + 10) : largo;
            marca = franja(origen, dir, largoMax, ancho / 2 + 0.6);
            bd = materialSuelo(origen);
        }

        private void girar() {
            if (sola) mirarMontura(yaw);
            else mirar(yaw);
        }

        @Override
        boolean paso(World w) {
            girar();
            if (t < aviso()) {
                if (t == 0) {
                    Compat.soundPlayers(w, origen, "entity.ravager.ambient", 2.0f, 0.5f);
                    Compat.sound(w, origen, "entity.hoglin.angry", 1.8f, 0.45f);
                    if (sola) Compat.sound(w, origen, "entity.ghast.scream", 1.4f, 0.45f);
                }
                if (t % 6 == 0) {
                    // Escarba con las patas, como un toro.
                    Compat.sound(w, origen, "entity.hoglin.step", 1.6f, 0.45f);
                    temblor(w, origen, ancho * 0.35, 8);
                }
                if (t % 4 == 0) pintar(w, marca, tono(t / (double) aviso()), bd);
                return false;
            }
            long s = t - aviso();
            if (!carga) {
                carga = true;
                antes = pieMontura();
                Compat.soundPlayers(w, origen, "entity.zoglin.angry", 2.2f, 0.5f);
                Compat.sound(w, origen, "entity.hoglin.attack", 2.0f, 0.5f);
            }
            Vector v0 = dir.clone().multiply(impulsoSuelo(a.embestidaVelocidad));
            impulsar(v0.setY(enSuelo(montura) ? 0 : Math.min(0, montura.getVelocity().getY())));
            Location ahora = pieMontura();
            double paso = PeleaAmbush.distPlano(antes, ahora);
            recorrido += paso;
            quieto = s >= 4 && paso < 0.3 ? quieto + 1 : 0;
            if (s % 4 == 0) {
                Compat.sound(w, ahora, "entity.ravager.step", 1.8f, 0.5f);
                temblor(w, ahora, ancho * 0.4, 10);
                Vector lado = new Vector(-dir.getZ(), 0, dir.getX()).multiply(ThreadLocalRandom.current().nextBoolean() ? 1 : -1);
                Location borde = ahora.clone().add(lado.clone().multiply(ancho * 0.6));
                if (Vigilante.cargado(borde)) {
                    Location g = Fx.ground(borde.add(0, 1.5, 0), 4);
                    if (Math.abs(g.getY() - ahora.getY()) <= 2) saltar(g.add(0, 0.1, 0), lado.multiply(0.3).setY(0.5), materialSuelo(g));
                }
            }
            double radio = ancho / 2 + 1.2;
            for (Player v : Fx.playersNear(ahora, radio + paso + 1)) {
                if (tocados.contains(v.getUniqueId()) || !valido(v)) continue;
                Location lv = v.getLocation();
                if (Math.abs(lv.getY() - ahora.getY()) > 3) continue;
                if (ParcaAnomalia.distanciaASegmento(lv.getX(), lv.getZ(), antes.getX(), antes.getZ(), ahora.getX(), ahora.getZ()) > radio) continue;
                tocados.add(v.getUniqueId());
                golpeFuerte(v, fraccion(), h.nombre);
                Vector lado = new Vector(-dir.getZ(), 0, dir.getX());
                if (lado.dot(lv.toVector().subtract(ahora.toVector())) < 0) lado.multiply(-1);
                empujar(v, lado.multiply(a.embestidaEmpuje).add(dir.clone().multiply(0.9)).setY(0.6), true);
                Compat.soundPlayers(w, lv, "entity.warden.attack_impact", 1.8f, 0.6f);
            }
            antes = ahora;
            if (hc.enSpawn(ahora.clone().add(dir.clone().multiply(ancho)))) return frenar();
            if (quieto >= 2) {
                choque(w, ahora);
                return true;
            }
            return recorrido >= largoMax || s > 70 ? frenar() : false;
        }

        private boolean frenar() {
            impulsar(dir.clone().multiply(0.2));
            return true;
        }

        /** Contra la pared: estruendo, el muro que suelta polvo y bloques, y aturdido (con jinete) o quieta (sola). */
        private void choque(World w, Location aqui) {
            Location frente = aqui.clone().add(dir.clone().multiply(ancho * 0.6));
            Compat.soundPlayers(w, frente, "entity.warden.attack_impact", 2.2f, 0.45f);
            Compat.sound(w, frente, "entity.generic.explode", 1.4f, 0.7f);
            Compat.sound(w, aqui, "entity.zoglin.hurt", 1.8f, 0.45f);
            estallidoSuelo(w, frente, ancho * 0.3, 4, 0.6);
            sacudir(aqui, 14);
            impulsar(dir.clone().multiply(-0.3).setY(0.2));
            if (sola) monturaQuietaHasta = ticks + a.aturdidoTicks;
            else aturdir(a.aturdidoTicks);
        }
    }

    /**
     * Hundimiento: la pareja se mete bajo tierra (baja entre bloques que saltan, primero la bestia y luego
     * su jinete), el suelo se mueve hacia uno de ustedes al azar (o hacia su objetivo si se hundio por
     * atasco), un circulo se agrieta bajo sus pies (aviso) y salen de golpe debajo, lanzandolo hacia
     * arriba (hundimiento.altura). Bajo tierra van juntos, invisibles, invulnerables y sin nada a la vista.
     */
    private final class Hundimiento extends Tecnica {
        static final int HUNDE = 24, SALE = 10, VIAJE_MAXIMO = 80;
        final boolean escape;
        UUID victima;
        final Location inicio;
        Location cabeza, fijo, hueco;
        List<Location> fuera, dentro;
        BlockData bd;
        int etapa;
        long desde;
        float yawSalir;

        Hundimiento(Player obj, boolean escape) {
            super(Habilidad.HUNDIMIENTO);
            this.escape = escape;
            inicio = pie();
            List<Player> c = validosCerca(inicio, 32);
            c.removeIf(p -> !Vigilante.cargado(p.getLocation()));
            victima = (escape || c.isEmpty() ? obj : c.get(ThreadLocalRandom.current().nextInt(c.size()))).getUniqueId();
            yawSalir = inicio.getYaw();
        }

        @Override
        boolean paso(World w) {
            switch (etapa) {
                case 0 -> hundirse(w);
                case 1 -> viajar(w);
                case 2 -> {
                    return avisar(w);
                }
                default -> {
                    return subir(w);
                }
            }
            return false;
        }

        private void hundirse(World w) {
            if (t == 0) {
                montura.setAI(false);
                montura.setInvulnerable(true);
                jinete.setInvulnerable(true);
                Compat.soundPlayers(w, inicio, "entity.warden.dig", 2.0f, 0.6f);
                Compat.sound(w, inicio, "entity.zoglin.angry", 1.8f, 0.45f);
                Compat.sound(w, inicio, "entity.witch.celebrate", 1.4f, 0.5f);
                estallidoSuelo(w, inicio, ancho * 0.5, (a.efimerosPorGolpe + 1) / 2, 0.7);
                if (escape) {
                    Component c = Component.text("El ", Paleta.TEXTO).append(Component.text("Vigilante", Paleta.VIGILANTE))
                            .append(Component.text(" se hunde en la tierra.", Paleta.TEXTO));
                    for (Player p : Fx.viewersNear(inicio, 40)) hc.barra().aviso(p, c, 2);
                }
            }
            double k = Math.min(1, (t + 2) / (double) HUNDE);
            // Baja la pareja entera: hasta que la calabaza del jinete queda bajo el suelo.
            Location l = inicio.clone().subtract(0, (alturaOculta() + 0.4) * k, 0);
            if (l.getY() < w.getMinHeight() + 3) l.setY(w.getMinHeight() + 3);
            mover(l);
            fijarYaw(inicio.getYaw());
            Compat.spawn(w, Compat.BLOCK, inicio.clone().add(0, 0.2, 0), 10, ancho * 0.45, 0.1, ancho * 0.45, 0.15, materialSuelo(inicio));
            if (t % 8 == 4) saltarAnillo(inicio, ancho * 0.5, 2, 0.5);
            if (k < 1) return;
            // Mas hondo e invisibles: ni una cueva los ensena ni el cartel del jinete asoma por el suelo.
            Location hondo = enterradoHondo(inicio);
            hondo.setYaw(inicio.getYaw());
            mover(hondo);
            ocultar(true);
            etapa = 1;
            desde = t;
            cabeza = Fx.ground(inicio.clone().add(0, 1, 0), 6);
        }

        private void viajar(World w) {
            Player v = hc.plugin().getServer().getPlayer(victima);
            if (!valido(v) || !Vigilante.cargado(v.getLocation())) {
                Player otro = objetivo();
                v = otro;
                if (otro != null) victima = otro.getUniqueId();
            }
            long s = t - desde;
            Location meta = v != null ? v.getLocation() : inicio;
            double d = PeleaAmbush.distPlano(cabeza, meta);
            double paso = Math.min(d, a.hundeVelocidad * 2);
            if (paso > 0.01) {
                Location sig = cabeza.clone().add(PeleaAmbush.plano(cabeza, meta, new Vector(0, 0, 1)).multiply(paso));
                if (Vigilante.cargado(sig)) cabeza = Fx.ground(sig.add(0, 2, 0), 8);
            }
            bd = materialSuelo(cabeza);
            Compat.spawn(w, Compat.BLOCK, cabeza.clone().add(0, 0.15, 0), 10, 0.8, 0.05, 0.8, 0.14, bd);
            if (s % 6 == 0) Compat.sound(w, cabeza, "block.rooted_dirt.break", 1.4f, 0.5f);
            if (s % 10 == 0) Compat.sound(w, cabeza, "entity.sniffer.digging", 1.6f, 0.5f);
            if (s % 20 == 0) Compat.sound(w, cabeza, "entity.zoglin.ambient", 1.0f, 0.4f);
            if (s % 8 == 0) saltarAnillo(cabeza, 0.8, 1, 0.4);
            if (v != null && d - paso > 1.5 && s < VIAJE_MAXIMO) return;
            // Bajo sus pies: el circulo que avisa de donde sale.
            fijo = Fx.ground((v != null ? v.getLocation() : cabeza).clone().add(0, 1, 0), 6);
            fuera = anillo(fijo, a.hundeRadio);
            dentro = anillo(fijo, a.hundeRadio * 0.5);
            bd = materialSuelo(fijo);
            etapa = 2;
            desde = t;
            Compat.soundPlayers(w, fijo, "entity.sniffer.digging", 2.0f, 0.5f);
            if (v != null) {
                hc.barra().aviso(v, Component.text("El suelo se mueve bajo tus pies: apártate.", Paleta.VIGILANTE), 2);
                try {
                    v.playHurtAnimation(0f);
                } catch (Throwable ignorado) {
                    // Sin temblor, el circulo avisa igual.
                }
            }
        }

        private boolean avisar(World w) {
            long s = t - desde;
            double k = s / (double) aviso();
            if (s % 4 == 0) {
                pintar(w, fuera, tono(k), bd);
                pintar(w, dentro, tono(k), null);
            }
            temblor(w, fijo, 0.6 + k * a.hundeRadio * 0.5, 8);
            if (s % 6 == 0) Compat.sound(w, fijo, "block.rooted_dirt.break", 1.6f, 0.5f);
            if (s % 6 == 2) saltarAnillo(fijo, a.hundeRadio * 0.7, 1, 0.35);
            if (s < aviso()) return false;
            salir(w);
            return false;
        }

        private void salir(World w) {
            Location h0 = PeleaVigilante.hueco(fijo, a, 4, l -> !hc.enSpawn(l));
            hueco = h0 != null ? h0 : fijo.clone();
            Player v = hc.plugin().getServer().getPlayer(victima);
            yawSalir = v != null && v.getWorld() == w ? PeleaAmbush.yaw(hueco, v.getLocation()) : inicio.getYaw();
            Location bajo = enterrado(hueco);
            bajo.setYaw(yawSalir);
            mover(bajo);
            ocultar(false);
            montura.setInvulnerable(true);
            jinete.setInvulnerable(true);
            fijarYaw(yawSalir);
            Compat.soundPlayers(w, fijo, "entity.generic.explode", 2.2f, 0.6f);
            Compat.soundPlayers(w, fijo, "entity.zoglin.angry", 2.2f, 0.45f);
            Compat.sound(w, fijo, "entity.warden.dig", 1.8f, 0.7f);
            Compat.sound(w, fijo, "item.mace.smash_ground_heavy", 1.8f, 0.6f);
            estallidoSuelo(w, fijo, ancho * 0.5, a.efimerosPorGolpe, 1.1);
            Set<UUID> tocados = new HashSet<>();
            Vector otra = PeleaAmbush.dir(yawSalir);
            for (Player p : Fx.playersNear(fijo, a.hundeRadio)) {
                if (!valido(p) || Math.abs(p.getLocation().getY() - fijo.getY()) > 3) continue;
                tocados.add(p.getUniqueId());
                golpeFuerte(p, fraccion(), h.nombre);
                lanzar(p, PeleaAmbush.plano(fijo, p.getLocation(), otra).multiply(0.2), a.hundeAltura);
            }
            for (Player p : Fx.playersNear(fijo, a.hundeRadio + 4)) {
                if (tocados.contains(p.getUniqueId()) || !valido(p)) continue;
                empujar(p, PeleaAmbush.plano(fijo, p.getLocation(), otra).multiply(0.9).setY(0.4), true);
            }
            sacudir(fijo, a.hundeRadio + 10);
            etapa = 3;
            desde = t;
        }

        private boolean subir(World w) {
            long s = t - desde;
            double k = Math.min(1, (s + 2) / (double) SALE);
            Location bajo = enterrado(hueco);
            Location l = bajo.clone();
            l.setY(bajo.getY() + (hueco.getY() - bajo.getY()) * k);
            l.setYaw(yawSalir);
            mover(l);
            fijarYaw(yawSalir);
            Compat.spawn(w, Compat.BLOCK, hueco.clone().add(0, 0.2, 0), 10, ancho * 0.45, 0.1, ancho * 0.45, 0.15, materialSuelo(hueco));
            if (k < 1) return false;
            restaurar();
            return true;
        }

        /** Vuelven arriba visibles y con IA (la habilidad se corta a medias: fase, staff o el fin de la pelea). */
        @Override
        void cortar() {
            if (etapa < 3 || hueco == null) {
                Location base = etapa == 0 ? inicio : fijo != null ? fijo : cabeza != null ? cabeza : inicio;
                Location h0 = PeleaVigilante.hueco(base, a, 4, l -> !hc.enSpawn(l));
                hueco = h0 != null ? h0 : inicio;
            }
            Location l = hueco.clone();
            l.setYaw(yawSalir);
            if (monturaViva()) mover(l);
            restaurar();
        }

        private void restaurar() {
            if (!monturaViva()) {
                if (oculto) ocultar(false);
                return;
            }
            ocultar(false);
            if (estado == Estado.PELEA) montura.setAI(true);
            anclaAtasco = montura.getLocation();
            anclaDesde = ticks;
        }
    }

    /**
     * Rugido: la bestia alza la cabeza y toma aire (aviso), y suelta un grito largo y grave (Zoglin,
     * Ravager y Ghast, todos bajos) que a rugido.radio da Oscuridad rugido.oscuridad-segundos (entre 1 y
     * 3: la oscuridad nunca es continua), quita rugido.cordura de cordura y empuja un poco. Sin dano.
     */
    private final class Rugido extends Tecnica {
        Location centro;
        double onda = -1;

        Rugido() {
            super(Habilidad.RUGIDO);
        }

        @Override
        boolean paso(World w) {
            if (t < aviso()) {
                if (t == 0) {
                    centro = pie();
                    Compat.soundPlayers(w, centro, "entity.breeze.inhale", 2.0f, 0.5f);
                    Compat.sound(w, centro, "entity.hoglin.ambient", 1.8f, 0.4f);
                }
                try {
                    montura.setRotation(montura.getLocation().getYaw(), -35);
                } catch (Throwable ignorado) {
                    // Sin alzar la cabeza, el sonido avisa igual.
                }
                Compat.spawn(w, Compat.ASH, centro.clone().add(0, alto * 0.8, 0), 6, ancho, alto * 0.4, ancho, 0.01);
                return false;
            }
            if (onda < 0) {
                rugir(w);
                onda = 1.5;
                return false;
            }
            onda += 2.0;
            Fx.ring(centro.clone().add(0, 0.6, 0), onda, Math.max(12, (int) (onda * 2.5)),
                    l -> Compat.spawn(w, Compat.LARGE_SMOKE, l, 1, 0.1, 0.1, 0.1, 0.01));
            ondaSuelo(w, centro, onda);
            return onda >= a.rugidoRadio;
        }

        private void rugir(World w) {
            Compat.soundPlayers(w, centro, "entity.ravager.roar", 3.0f, 0.5f);
            Compat.sound(w, centro, "entity.zoglin.angry", 3.0f, 0.4f);
            Compat.sound(w, centro, "entity.ghast.scream", 2.2f, 0.5f);
            Compat.spawn(w, Compat.LARGE_SMOKE, centro.clone().add(0, alto * 0.7, 0), 20, ancho * 0.4, 0.3, ancho * 0.4, 0.06);
            for (Player p : validosCerca(centro, a.rugidoRadio)) {
                Compat.apply(p, "darkness", a.rugidoOscuridad, 0);
                if (a.rugidoCordura > 0 && hc.esHardcore(p)) hc.cordura().sumar(p, -a.rugidoCordura);
                empujar(p, PeleaAmbush.plano(centro, p.getLocation(), new Vector(0, 0, 1)).multiply(0.6).setY(0.25), false);
                try {
                    p.playHurtAnimation(PeleaParca.ladoDe(p, centro));
                } catch (Throwable ignorado) {
                    // Sin temblor, el grito se ha oido igual.
                }
            }
        }
    }

    /**
     * Mazazo (a pie): el jinete se agacha (el sitio donde va a caer se agrieta en dos circulos), salta
     * alto sobre su blanco con la maza en alto y cae con ella. Como el martillazo, pero mas corto y mas
     * frecuente: muy cerca (mazazo.radio-cerca) sale lanzado hacia arriba altura-lanzado bloques; a
     * media distancia (radio-medio), empujado lejos; mas alla (radio-lejos), un empujon.
     */
    private final class Mazazo extends Tecnica {
        final UUID blanco;
        long desde;
        Location destino;
        List<Location> cerca, medio;
        BlockData bd;
        boolean enAire, despego;
        long tSalto;
        double onda = -1;
        Location centroOnda;
        int quedan;

        Mazazo(Player obj) {
            super(Habilidad.MAZAZO);
            blanco = obj.getUniqueId();
        }

        @Override
        boolean paso(World w) {
            if (onda >= 0) return ondaTrasGolpe(w);
            if (destino == null) elegir(w);
            long s = t - desde;
            float yaw = PeleaAmbush.yaw(jinete.getLocation(), destino);
            if (s < aviso()) {
                mirar(yaw);
                double k = s / (double) aviso();
                if (s % 4 == 0) {
                    pintar(w, cerca, tono(k), bd);
                    pintar(w, medio, PeleaParca.mezcla(RGB_AVISO_DESDE, tono(k), 0.5), null);
                }
                if (s % 6 == 0) temblor(w, jinete.getLocation(), 0.5, 4);
                return false;
            }
            if (!enAire) {
                despegar(w);
                return false;
            }
            fijarYaw(yaw);
            long vuelo = t - tSalto;
            if (vuelo % 4 == 0) pintar(w, cerca, RGB_AVISO_HASTA, bd);
            boolean tierra = enSuelo(jinete);
            if (!tierra) despego = true;
            if ((despego && tierra && vuelo >= 4) || (!despego && tierra && vuelo >= 10) || vuelo >= 60) golpear(w);
            return false;
        }

        private void elegir(World w) {
            Player obj = hc.plugin().getServer().getPlayer(blanco);
            if (!valido(obj)) obj = objetivo();
            Location base = jinete.getLocation();
            Location d = null;
            if (obj != null && PeleaAmbush.distPlano(base, obj.getLocation()) <= a.mazoAlcance + 2
                    && Vigilante.cargado(obj.getLocation()) && !hc.enSpawn(obj)) {
                d = Fx.ground(obj.getLocation(), 6);
                if (hc.enSpawn(d) || Math.abs(d.getY() - base.getY()) > 10) d = null;
            }
            destino = d != null ? d : base.clone();
            cerca = anillo(destino, a.mazoCerca);
            medio = anillo(destino, a.mazoMedio);
            bd = materialSuelo(destino);
            desde = t;
            if (conNpc) npc.postura(Pose.SNEAKING);
            Compat.soundPlayers(w, base, "entity.wither_skeleton.ambient", 1.6f, 0.5f);
            Compat.sound(w, base, "entity.evoker.prepare_attack", 1.4f, 0.5f);
        }

        private void despegar(World w) {
            enAire = true;
            tSalto = t;
            Location base = jinete.getLocation();
            double vy = a.mazoSalto;
            int vuelo = ticksDeVuelo(vy, Fx.clamp(destino.getY() - base.getY(), -6, 4));
            double dist = PeleaAmbush.distPlano(base, destino);
            Vector dir = PeleaAmbush.plano(base, destino, PeleaAmbush.dir(base.getYaw()));
            double vh = Math.min(2.0, velocidadHorizontal(dist, vuelo));
            if (conNpc) npc.postura(Pose.STANDING);
            impulsarJinete(dir.multiply(vh).setY(vy));
            blandir();
            Compat.soundPlayers(w, base, "item.mace.smash_air", 1.8f, 0.55f);
            Compat.sound(w, base, "entity.wither_skeleton.ambient", 1.4f, 0.45f);
            temblor(w, base, 0.6, 10);
        }

        private void golpear(World w) {
            Location aqui = jinete.getLocation();
            boolean honesto = PeleaAmbush.distPlano(aqui, destino) <= 2.5 && Math.abs(aqui.getY() - destino.getY()) <= 3;
            Location c = honesto ? destino : aqui;
            blandir();
            Compat.soundPlayers(w, c, "item.mace.smash_ground_heavy", 2.4f, 0.55f);
            Compat.soundPlayers(w, c, "entity.generic.explode", 1.8f, 0.6f);
            Compat.sound(w, c, "block.rooted_dirt.break", 1.8f, 0.5f);
            int centro = Math.max(1, a.efimerosPorGolpe / 3);
            quedan = Math.max(0, a.efimerosPorGolpe / 2 - centro);
            estallidoSuelo(w, c, 1.4, centro, 0.8);
            Vector otra = PeleaAmbush.dir(aqui.getYaw());
            for (Player v : Fx.playersNear(c, a.mazoLejos)) {
                if (!valido(v) || Math.abs(v.getLocation().getY() - c.getY()) > 3.5) continue;
                Vector fuera = PeleaAmbush.plano(c, v.getLocation(), otra);
                switch (zonaMazo(PeleaAmbush.distPlano(c, v.getLocation()), a)) {
                    case CERCA -> {
                        if (honesto) golpeFuerte(v, fraccion(), h.nombre);
                        lanzar(v, fuera.multiply(0.2), a.mazoAltura);
                    }
                    case MEDIO -> empujar(v, fuera.multiply(a.mazoEmpujeMedio).setY(0.5), true);
                    case LEJOS -> empujar(v, fuera.multiply(a.mazoEmpujeLejos).setY(0.3), true);
                    default -> {
                    }
                }
            }
            sacudir(c, a.mazoLejos + 6);
            onda = 1.0;
            centroOnda = c;
        }

        private boolean ondaTrasGolpe(World w) {
            onda += 1.6;
            ondaSuelo(w, centroOnda, onda);
            if (quedan > 0) {
                saltarAnillo(centroOnda, onda, 1, 0.4);
                quedan--;
            }
            return onda >= a.mazoLejos;
        }

        @Override
        void cortar() {
            if (conNpc) npc.postura(Pose.STANDING);
        }
    }

    /**
     * Barrido (a pie): el jinete echa la maza atras (el arco del suelo se oscurece delante de el) y barre
     * en arco (barrido.radio, barrido.angulo grados): a quien pille, golpe fuerte y lejos hacia fuera y
     * hacia un lado.
     */
    private final class Barrido extends Tecnica {
        final UUID blanco;
        float yaw;
        List<Location> marca;
        BlockData bd;

        Barrido(Player obj) {
            super(Habilidad.BARRIDO);
            blanco = obj.getUniqueId();
            yaw = PeleaAmbush.yaw(jinete.getLocation(), obj.getLocation());
        }

        @Override
        boolean paso(World w) {
            Location c = jinete.getLocation();
            if (t < aviso()) {
                Player v = hc.plugin().getServer().getPlayer(blanco);
                if (valido(v) && t < aviso() / 2) yaw = PeleaAmbush.yaw(c, v.getLocation());
                mirar(yaw);
                if (t == 0) {
                    Compat.soundPlayers(w, c, "entity.wither_skeleton.ambient", 1.6f, 0.55f);
                    Compat.sound(w, c, "item.mace.smash_air", 1.2f, 0.45f);
                    bd = materialSuelo(c);
                }
                if (t % 4 == 0) {
                    marca = arco(c, yaw, a.barridoRadio, a.barridoAngulo);
                    pintar(w, marca, tono(t / (double) aviso()), bd);
                }
                if (t == aviso() / 2 && conNpc && npc.valido()) npc.reves();
                return false;
            }
            if (t == aviso()) golpe(w, c);
            return t >= aviso() + 6;
        }

        private void golpe(World w, Location c) {
            blandir();
            Compat.soundPlayers(w, c, "entity.player.attack.sweep", 1.8f, 0.55f);
            Compat.sound(w, c, "item.mace.smash_air", 1.6f, 0.5f);
            Vector frente = PeleaAmbush.dir(yaw);
            Fx.arc(c.clone().add(0, 1.0, 0), frente, a.barridoRadio * 0.8, Math.toRadians(a.barridoAngulo),
                    Math.max(8, (int) (a.barridoAngulo / 12)), l -> {
                        Compat.spawn(w, Compat.SMOKE, l, 2, 0.1, 0.1, 0.1, 0.01);
                        Compat.spawn(w, Compat.DUST, l, 1, 0.05, 0.05, 0.05, 0, Compat.dust(RGB_AVISO_HASTA, 1.2f));
                    });
            if (marca != null) for (int i = 0; i < marca.size(); i += 3) Compat.spawn(w, Compat.BLOCK, marca.get(i), 3, 0.2, 0.05, 0.2, 0.1, bd);
            for (Player v : Fx.playersNear(c, a.barridoRadio + 1)) {
                if (!valido(v)) continue;
                Location lv = v.getLocation();
                if (Math.abs(lv.getY() - c.getY()) > 3) continue;
                if (!enArco(lv.getX() - c.getX(), lv.getZ() - c.getZ(), yaw, a.barridoRadio, a.barridoAngulo)) continue;
                golpeFuerte(v, fraccion(), h.nombre);
                Vector fuera = PeleaAmbush.plano(c, lv, frente);
                Vector lado = new Vector(-frente.getZ(), 0, frente.getX());
                if (lado.dot(fuera) < 0) lado.multiply(-1);
                empujar(v, fuera.multiply(a.barridoEmpuje).add(lado.multiply(0.6)).setY(0.45), true);
                Compat.sound(w, lv, "item.mace.smash_ground", 1.4f, 0.6f);
            }
        }
    }

    /**
     * El barrido desde lo alto de la bestia (fase IV, a la vez que la pareja hace lo suyo): un circulo de
     * polvo alrededor de la bestia (barrido.arriba-aviso-ticks, medio segundo o mas) mientras alza la
     * maza, y barre a quien siga arrimado: un golpe de maza normal (con armadura) y un empujon hacia fuera.
     */
    private final class BarridoAlto extends Tecnica {
        BarridoAlto() {
            super(Habilidad.BARRIDO);
        }

        @Override
        int aviso() {
            return a.barridoArribaAviso;
        }

        @Override
        boolean paso(World w) {
            if (!montado()) return true;
            Location c = montura.getLocation();
            double r = radioArriba();
            if (t < aviso()) {
                if (t == 0) {
                    Compat.soundPlayers(w, c, "item.mace.smash_air", 1.6f, 0.5f);
                    Compat.sound(w, c, "entity.wither_skeleton.ambient", 1.4f, 0.5f);
                    if (conNpc && npc.valido()) npc.reves();
                }
                if (t % 4 == 0) pintar(w, anillo(c, r), tono(t / (double) aviso()), materialSuelo(c));
                return false;
            }
            blandir();
            Compat.soundPlayers(w, c, "entity.player.attack.sweep", 1.8f, 0.5f);
            Compat.sound(w, c, "item.mace.smash_ground", 1.6f, 0.6f);
            Fx.ring(c.clone().add(0, 1.0, 0), r, Math.max(16, (int) (r * 5)), l -> Compat.spawn(w, Compat.SMOKE, l, 1, 0.1, 0.1, 0.1, 0.01));
            for (Player v : Fx.playersNear(c, r + 0.5)) {
                if (!valido(v) || Math.abs(v.getLocation().getY() - c.getY()) > 4.5) continue;
                if (PeleaAmbush.distPlano(c, v.getLocation()) > r) continue;
                golpeMaza(v, golpe * a.barridoArribaDano);
                empujar(v, PeleaAmbush.plano(c, v.getLocation(), new Vector(0, 0, 1)).multiply(0.9).setY(0.4), true);
            }
            return true;
        }
    }

    /**
     * Cabeza negra (a pie): a quien se aleje mas de cabeza-negra.distancia-minima, el jinete alza la mano
     * (humo y ceniza en ella, el aviso de un ghast muy grave) y le lanza una cabeza de wither NEGRA a
     * velocidad constante, con su estela de humo. Estalla sin fuego ni bloques (impactoCalavera).
     */
    private final class CabezaNegra extends Tecnica {
        UUID blanco;
        float yaw;
        boolean lanzada;

        CabezaNegra() {
            super(Habilidad.CABEZA_NEGRA);
        }

        @Override
        boolean paso(World w) {
            Location c = jinete.getLocation();
            if (t == 0) {
                Player p = blancoCabeza();
                if (p == null) return true;
                blanco = p.getUniqueId();
                Compat.soundPlayers(w, c, "entity.ghast.warn", 1.6f, 0.5f);
                Compat.sound(w, c, "entity.witch.celebrate", 1.2f, 0.45f);
            }
            Player p = hc.plugin().getServer().getPlayer(blanco);
            if (p != null && valido(p) && t < aviso()) yaw = PeleaAmbush.yaw(c, p.getLocation());
            if (t < aviso()) {
                mirar(yaw);
                Location mano = jinete.getEyeLocation().add(PeleaAmbush.dir(yaw).multiply(0.7));
                Compat.spawn(w, Compat.SMOKE, mano, 4, 0.12, 0.12, 0.12, 0.01);
                Compat.spawn(w, Compat.ASH, mano, 3, 0.25, 0.25, 0.25, 0.01);
                return false;
            }
            if (!lanzada) {
                lanzada = true;
                lanzar(w, p);
            }
            return t >= aviso() + 6;
        }

        private void lanzar(World w, Player p) {
            Location ojo = jinete.getEyeLocation();
            Vector dir = p != null && valido(p) ? p.getEyeLocation().toVector().subtract(ojo.toVector()) : PeleaAmbush.dir(yaw);
            if (dir.lengthSquared() < 1e-6) dir = PeleaAmbush.dir(yaw);
            dir.normalize();
            Vector vel = dir.clone().multiply(a.cabezaVelocidad);
            Location sale = ojo.clone().add(dir.clone().multiply(1.4));
            if (!Vigilante.cargado(sale) || hc.enSpawn(sale)) return;
            String marca = jinete.getUniqueId().toString();
            WitherSkull s;
            try {
                s = w.spawn(sale, WitherSkull.class, k -> {
                    k.setShooter(jinete);
                    k.setCharged(CABEZA_CARGADA);
                    k.setIsIncendiary(CABEZA_INCENDIARIA);
                    k.setYield(CABEZA_POTENCIA);
                    k.setPersistent(false);
                    k.getPersistentDataContainer().set(Marcas.VIGILANTE, PersistentDataType.STRING, marca);
                    try {
                        k.setAcceleration(new Vector());
                    } catch (Throwable ignorado) {
                        // Sin quitarle la aceleracion, la velocidad se le repone cada 2 ticks igual.
                    }
                    k.setVelocity(vel);
                });
            } catch (Throwable t) {
                return;
            }
            if (s == null || !s.isValid()) return;
            int vida = (int) Math.ceil(a.cabezaAlcance / a.cabezaVelocidad) + 20;
            calaveras.add(new Calavera(s, vel, ticks + vida));
            blandir();
            Compat.soundPlayers(w, sale, "entity.wither.shoot", 1.4f, 0.5f);
            Compat.sound(w, sale, "entity.ghast.shoot", 1.2f, 0.5f);
        }
    }

    /**
     * Desmonte (al entrar en la III): la bestia se para y el sitio donde va a caer el jinete se agrieta
     * (desmonte.radio y su onda) mientras se rie; salta de la bestia hacia su blanco y la maza golpea el
     * suelo al caer: golpe fuerte muy cerca y una onda que aparta a quien este en desmonte.onda.
     */
    private final class Desmonte extends Tecnica {
        final UUID blanco;
        Location aterrizaje;
        List<Location> cerca, onda;
        BlockData bd;
        boolean salto, despego;
        long tSalto;
        double ola = -1;

        Desmonte(Player obj) {
            super(Habilidad.DESMONTE);
            blanco = obj == null ? null : obj.getUniqueId();
        }

        @Override
        boolean paso(World w) {
            if (ola >= 0) {
                ola += 1.6;
                ondaSuelo(w, aterrizaje, ola);
                return ola >= a.desmonteOnda;
            }
            if (aterrizaje == null) {
                if (!montado()) {
                    bajarYa();
                    return true;
                }
                elegir(w);
            }
            float yaw = PeleaAmbush.yaw(pie(), aterrizaje);
            if (!salto) {
                if (t < aviso()) {
                    mirar(yaw);
                    if (t % 4 == 0) {
                        pintar(w, cerca, tono(t / (double) aviso()), bd);
                        pintar(w, onda, PeleaParca.mezcla(RGB_AVISO_DESDE, tono(t / (double) aviso()), 0.5), null);
                    }
                    if (t % 6 == 0) temblor(w, montura.getLocation(), ancho * 0.4, 6);
                    return false;
                }
                saltar(w, yaw);
                return false;
            }
            fijarYaw(yaw);
            long vuelo = t - tSalto;
            if (vuelo % 4 == 0) pintar(w, cerca, RGB_AVISO_HASTA, bd);
            boolean tierra = enSuelo(jinete);
            if (!tierra) despego = true;
            if ((despego && tierra && vuelo >= 4) || vuelo >= 50) golpear(w);
            return false;
        }

        private void elegir(World w) {
            Location base = montura.getLocation();
            Player obj = blanco == null ? null : hc.plugin().getServer().getPlayer(blanco);
            if (!valido(obj)) obj = objetivo();
            Vector dir = obj != null ? PeleaAmbush.plano(base, obj.getLocation(), PeleaAmbush.dir(base.getYaw()))
                    : PeleaAmbush.dir(base.getYaw());
            double d = obj != null ? Fx.clamp(PeleaAmbush.distPlano(base, obj.getLocation()) - 1.5, ancho / 2 + 2.5, 8) : 5;
            Location cand = base.clone().add(dir.multiply(d));
            Location g = Vigilante.cargado(cand) && !hc.enSpawn(cand) ? Fx.ground(cand.add(0, 3, 0), 8) : null;
            aterrizaje = g != null && Math.abs(g.getY() - base.getY()) <= 6 ? g : base.clone();
            cerca = anillo(aterrizaje, a.desmonteRadio);
            onda = anillo(aterrizaje, a.desmonteOnda);
            bd = materialSuelo(aterrizaje);
            sujetar();
            Compat.soundPlayers(w, base, "entity.witch.celebrate", 1.6f, 0.5f);
            Compat.sound(w, base, "entity.wither_skeleton.ambient", 1.6f, 0.5f);
        }

        private void saltar(World w, float yaw) {
            salto = true;
            tSalto = t;
            bajarYa();
            sujetarJinete();
            Location base = jinete.getLocation();
            double vy = a.desmonteSalto;
            int vuelo = ticksDeVuelo(vy, Fx.clamp(aterrizaje.getY() - base.getY(), -8, 4));
            double dist = PeleaAmbush.distPlano(base, aterrizaje);
            Vector dir = PeleaAmbush.plano(base, aterrizaje, PeleaAmbush.dir(yaw));
            impulsarJinete(dir.multiply(Math.min(2.0, velocidadHorizontal(dist, vuelo))).setY(vy));
            blandir();
            Compat.soundPlayers(w, base, "item.mace.smash_air", 1.8f, 0.5f);
            Compat.sound(w, base, "entity.zoglin.angry", 1.8f, 0.5f);
            // La bestia se va al borde: desde aqui no lleva a nadie.
            modoSuelta = Suelta.RETIRADA;
            borde = null;
            listo.put(Habilidad.ESTAMPIDA, ticks + a.hab(Habilidad.ESTAMPIDA).espera() / 2);
        }

        private void golpear(World w) {
            Location c = jinete.getLocation();
            blandir();
            Compat.soundPlayers(w, c, "item.mace.smash_ground_heavy", 2.2f, 0.6f);
            Compat.soundPlayers(w, c, "entity.generic.explode", 1.6f, 0.55f);
            Compat.sound(w, c, "block.rooted_dirt.break", 1.8f, 0.5f);
            estallidoSuelo(w, c, 1.6, Math.max(1, a.efimerosPorGolpe / 2), 0.9);
            for (Player v : Fx.playersNear(c, a.desmonteOnda)) {
                if (!valido(v) || Math.abs(v.getLocation().getY() - c.getY()) > 3.5) continue;
                double d = PeleaAmbush.distPlano(c, v.getLocation());
                Vector fuera = PeleaAmbush.plano(c, v.getLocation(), new Vector(0, 0, 1));
                if (d <= a.desmonteRadio) golpeFuerte(v, fraccion(), h.nombre);
                double k = 1 - Math.min(1, d / a.desmonteOnda) * 0.5;
                empujar(v, fuera.multiply(a.desmonteEmpuje * k).setY(0.45), true);
            }
            sacudir(c, a.desmonteOnda + 6);
            ola = 1.0;
            aterrizaje = c;
            liberar();
        }

        /** Cortado a medias (staff, fin): baja ya, que la fase III es a pie. */
        @Override
        void cortar() {
            if (!salto && fase == 3 && montado()) bajarYa();
        }
    }

    /**
     * Remonte (furia, o el guardian): la bestia viene a por su jinete (a mano, rapido) mientras el la
     * espera mirandola; a remonte.distancia salta hacia su lomo y monta. Si la bestia no llega en 5 s,
     * monta igual si esta a 16 bloques; si no, sigue a pie y el guardian lo vuelve a intentar.
     */
    private final class Remonte extends Tecnica {
        boolean salto;
        long tSalto;

        Remonte() {
            super(Habilidad.REMONTE);
        }

        @Override
        boolean paso(World w) {
            if (!monturaViva() || !jineteVivo()) return true;
            if (montado()) return true;
            Location j = jinete.getLocation(), m = montura.getLocation();
            double d = PeleaAmbush.distPlano(j, m);
            if (t == 0) {
                Compat.soundPlayers(w, m, "entity.zoglin.angry", 2.0f, 0.5f);
                Compat.sound(w, j, "entity.witch.celebrate", 1.4f, 0.5f);
            }
            if (!salto) {
                mirar(PeleaAmbush.yaw(j, m));
                if (d > a.remonteDistancia) {
                    if (t > 100) {
                        if (d <= 16) return montar(w);
                        return true;
                    }
                    conducir(j, remontePendiente ? 0.55 : 0.4);
                    return false;
                }
                // Ya esta a mano: la bestia se para y el salta a su lomo.
                salto = true;
                tSalto = t;
                Compat.setAttribute(montura, "movement_speed", 0);
                Cerebro.soltar(montura);
                double asiento = ZOGLIN_ASIENTO * a.escalaCuerpo;
                double dy = m.getY() + asiento - j.getY();
                double vy = velocidadParaAltura(Math.max(1.5, dy + 1.0));
                int vuelo = ticksDeVuelo(vy, Math.max(0, dy));
                Vector dir = PeleaAmbush.plano(j, m, new Vector(0, 0, 1));
                impulsarJinete(dir.multiply(Math.min(2.0, velocidadHorizontal(d, vuelo))).setY(vy));
                blandir();
                Compat.soundPlayers(w, j, "item.mace.smash_air", 1.6f, 0.55f);
                return false;
            }
            long vuelo = t - tSalto;
            double asiento = m.getY() + ZOGLIN_ASIENTO * a.escalaCuerpo;
            boolean llega = (vuelo >= 4 && d <= ancho / 2 + 0.6)
                    || (vuelo >= 6 && jinete.getVelocity().getY() < 0 && j.getY() <= asiento + 0.5) || vuelo >= 24;
            return llega ? montar(w) : false;
        }

        private boolean montar(World w) {
            if (!montarYa()) return true;
            remontePendiente = false;
            modoSuelta = Suelta.NADA;
            suelta = null;
            Location l = montura.getLocation();
            Compat.soundPlayers(w, l, "entity.ravager.roar", 2.2f, 0.55f);
            Compat.sound(w, l, "entity.zoglin.angry", 2.0f, 0.5f);
            Compat.spawn(w, Compat.BLOCK, l.clone().add(0, 0.2, 0), 30, ancho * 0.5, 0.1, ancho * 0.5, 0.15, materialSuelo(l));
            Compat.spawn(w, Compat.LARGE_SMOKE, l.clone().add(0, 0.5, 0), 10, ancho * 0.4, 0.3, ancho * 0.4, 0.02);
            sacudir(l, 16);
            hc.plugin().bitacora().anotar("vigilante", "remonta", presaNombre, "fase " + fase);
            return true;
        }
    }

    // ================================================================ barra

    private Component tituloBarra() {
        Component resto = estado == Estado.APARECE ? Component.text("Sale de la tierra", Paleta.AVISO)
                : actual != null ? Component.text(actual.h.nombre, Paleta.AVISO)
                : aturdido() ? Component.text("Aturdido", Paleta.BIEN)
                : Component.text("Fase " + Parca.romano(fase) + " · " + nombreFase(fase), Paleta.TEXTO);
        return Paleta.vigilante("Vigilante").append(Component.text(" · ", Paleta.SEPARADOR)).append(resto);
    }

    private void nombreBarra() {
        if (barra != null) barra.name(tituloBarra());
    }

    private void refrescarBarra() {
        if (jinete == null) return;
        if (barra == null) barra = BossBar.bossBar(tituloBarra(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_20);
        barra.progress((float) Math.max(0, Math.min(1, Amenazas.fraccion(jinete))));
        nombreBarra();
        Set<UUID> ahora = new HashSet<>();
        for (Player p : Fx.viewersNear(jinete.getLocation(), 48)) ahora.add(p.getUniqueId());
        for (UUID id : ahora) {
            if (viendo.contains(id)) continue;
            Player p = hc.plugin().getServer().getPlayer(id);
            if (p != null) p.showBossBar(barra);
        }
        for (UUID id : viendo) {
            if (ahora.contains(id)) continue;
            Player p = hc.plugin().getServer().getPlayer(id);
            if (p != null) p.hideBossBar(barra);
        }
        viendo.clear();
        viendo.addAll(ahora);
    }

    private void quitarBarra() {
        if (barra == null) return;
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) p.hideBossBar(barra);
        viendo.clear();
    }

    // ================================================================ consultas

    boolean esJinete(Entity e) {
        return e != null && jinete != null && jinete.getUniqueId().equals(e.getUniqueId());
    }

    boolean esMontura(Entity e) {
        return e != null && montura != null && montura.getUniqueId().equals(e.getUniqueId());
    }

    boolean esCascara(Entity e) {
        return e != null && npc.es(e);
    }

    /** El jinete o su montura (las dos amenazas de la pelea). */
    boolean esCuerpo(Entity e) {
        return esJinete(e) || esMontura(e);
    }

    /** Cualquier cosa suya: jinete, montura, maniqui o una cabeza negra. */
    boolean esNuestro(Entity e) {
        return esCuerpo(e) || esCascara(e) || esCalavera(e);
    }

    boolean enMundo(World w) {
        return w != null && jinete != null && jinete.getWorld() == w;
    }

    int fase() {
        return fase;
    }

    /** Montado, a pie o sin montura (para /calamity vigilant info). */
    String posturaTexto() {
        if (montado()) return "montado";
        if (!monturaViva()) return "a pie (sin montura)";
        String bestia = suelta != null ? "en estampida" : switch (modoSuelta) {
            case RETIRADA -> "se retira al borde";
            case ESPERA -> "espera en el borde";
            case VUELVE -> "vuelve a por él";
            default -> "suelta";
        };
        return "a pie (la montura " + bestia + ")";
    }

    String estadoTexto() {
        if (jinete == null) return "sin jinete";
        long s = estado == Estado.PELEA ? (ticks - inicioPelea) / 20 : 0;
        Location l = jinete.getLocation();
        return presaNombre + " | " + (estado == Estado.APARECE ? "saliendo" : actual != null ? actual.h.nombre
                : aturdido() ? "aturdido" : "pelea") + " | " + posturaTexto() + " | fase " + fase + (furia ? " (furia)" : "")
                + " | vida " + Math.round(Amenazas.fraccion(jinete) * 100) + " % | bloques en el aire " + efimeros.size()
                + " | " + s / 60 + ":" + String.format(Locale.ROOT, "%02d", s % 60) + " | " + l.getWorld().getName() + " "
                + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ() + " | " + escala.texto();
    }

    // ================================================================ fin

    /**
     * Ha caido el jinete: el botin lo reparte el gestor con el dano logico (se lee aqui, en su
     * EntityDeathEvent). La bestia cae con el: los dos (el maniqui y el zoglin) mueren a la vista, sin
     * soltar nada, y lo que quede se retira a los 2 s (Vigilante.despedida).
     */
    void alMorir() {
        if (pagada || estado == Estado.FIN) return;
        pagada = true;
        Map<UUID, Double> dano = hc.amenazas().danoLogico(jinete);
        double vida = hc.amenazas().vidaLogicaMaxima(jinete);
        long segundos = (System.currentTimeMillis() - nacio) / 1000;
        Location l = jinete.getLocation();
        World w = l.getWorld();
        cortarTecnica();
        suelta = null;
        arriba = null;
        montadoDeseado = false;
        Compat.soundPlayers(w, l, "entity.wither_skeleton.death", 2.4f, 0.5f);
        Compat.sound(w, l, "entity.ghast.scream", 2.0f, 0.45f);
        BlockData bd = materialSuelo(l);
        Compat.spawn(w, Compat.BLOCK, l.clone().add(0, 0.3, 0), 60, 1.2, 0.3, 1.2, 0.15, bd);
        Compat.spawn(w, Compat.BLOCK, l.clone().add(0, 1.2, 0), 30, 0.4, 0.6, 0.4, 0.1, hueso());
        Compat.spawn(w, Compat.ASH, l.clone().add(0, 1.5, 0), 40, 0.8, 0.8, 0.8, 0.02);
        List<Entity> caen = new ArrayList<>();
        Mannequin m = conNpc ? npc.soltar() : null;
        if (m != null && m.isValid()) {
            try {
                m.setImmovable(false);
                m.setHealth(0);
            } catch (Throwable t) {
                Fx.safeRemove(m);
            }
            caen.add(m);
        }
        if (monturaViva()) {
            Zoglin z = montura;
            Location ml = z.getLocation();
            Compat.soundPlayers(w, ml, "entity.zoglin.death", 3.0f, 0.4f);
            Compat.sound(w, ml, "entity.ravager.death", 2.2f, 0.5f);
            Compat.sound(w, ml, "block.rooted_dirt.break", 2.0f, 0.5f);
            Compat.spawn(w, Compat.BLOCK, ml.clone().add(0, 0.3, 0), 60, ancho * 0.6, 0.3, ancho * 0.6, 0.15, materialSuelo(ml));
            Compat.spawn(w, Compat.DUST_PILLAR, ml.clone().add(0, 0.2, 0), 30, ancho * 0.5, 0.1, ancho * 0.5, 0.25, materialSuelo(ml));
            Compat.spawn(w, Compat.LARGE_SMOKE, ml.clone().add(0, alto * 0.4, 0), 24, ancho * 0.5, alto * 0.3, ancho * 0.5, 0.02);
            try {
                z.setInvulnerable(false);
                z.setHealth(0);
            } catch (Throwable t) {
                Fx.safeRemove(z);
            }
            caen.add(z);
        }
        gestor.despedida(caen);
        hc.seguro("vigilante", () -> gestor.botin(this, dano, vida, segundos));
        limpiar();
    }

    /** Se va sin botin: tiempo, nadie cerca, el mundo se descarga o el staff. Se hunden en la tierra. */
    void irse(String motivo, Component aviso) {
        if (estado == Estado.FIN) return;
        cortarTecnica();
        Location l = jineteVivo() ? jinete.getLocation() : monturaViva() ? montura.getLocation() : null;
        if (l != null && l.getWorld() != null) {
            World w = l.getWorld();
            BlockData bd = materialSuelo(l);
            Compat.spawn(w, Compat.BLOCK, l.clone().add(0, 0.3, 0), 60, ancho * 0.5, 0.3, ancho * 0.5, 0.15, bd);
            Compat.spawn(w, Compat.LARGE_SMOKE, l.clone().add(0, 0.6, 0), 20, ancho * 0.5, 0.4, ancho * 0.5, 0.02);
            Compat.sound(w, l, "entity.warden.dig", 2.0f, 0.6f);
            Compat.sound(w, l, "entity.witch.celebrate", 1.4f, 0.45f);
            if (aviso != null) for (Player o : Fx.viewersNear(l, 48)) o.sendMessage(aviso);
        }
        hc.plugin().bitacora().anotar("vigilante", "se-va", presaNombre, motivo, (System.currentTimeMillis() - nacio) / 1000 + " s");
        limpiar();
    }

    /**
     * Retira todo lo suyo (idempotente): habilidades, animador, bloques que saltan, cabezas negras, barra,
     * vuelo prestado, el maniqui, el jinete y la bestia (si no estan muriendo: entonces vanilla los tumba
     * y los quita). Pasan por aqui todos los finales: muere, se va, se para el plugin (Vigilante.parar) o
     * se descarga su mundo.
     */
    void limpiar() {
        for (Tecnica tec : new Tecnica[]{actual, suelta, arriba}) {
            if (tec == null) continue;
            try {
                tec.cortar();
            } catch (Throwable ignorado) {
                // Lo suyo se retira abajo igual.
            }
        }
        actual = null;
        suelta = null;
        arriba = null;
        if (animador != null) {
            animador.cancel();
            animador = null;
        }
        quitarEfimeros();
        quitarCalaveras();
        quitarBarra();
        devolverVuelo(true);
        npc.quitar();
        if (jinete != null && jinete.isValid() && !jinete.isDead()) Fx.safeRemove(jinete);
        if (montura != null && montura.isValid() && !montura.isDead()) Fx.safeRemove(montura);
        if (hc.amenazas() != null) hc.amenazas().quitarPelea(this);
        estado = Estado.FIN;
    }

    // ================================================================ autotest

    /**
     * Lo de la pelea que no necesita servidor: habilidades, fases (montado y a pie), sorteo, golpes por
     * distancia, saltos, la caja, la pareja bajo tierra, el desvio de golpes de la montura al jinete, que
     * la caida ya no se perdona y que cada sonido existe en Paper y ninguno es una campana.
     */
    static void autotest(Autotest.Hoja h, Vigilante.Ajustes a) {
        Set<String> ids = new HashSet<>(), alias = new HashSet<>();
        boolean bien = true;
        for (Habilidad x : Habilidad.values()) {
            bien &= ids.add(x.id) && x.id.startsWith("vi_") && alias.add(x.alias) && x.alias.matches("[a-z]+");
            bien &= Habilidad.buscar(x.alias) == x && Habilidad.buscar(x.clave) == x && Habilidad.buscar(x.id) == x;
        }
        h.ok("habilidades: ids vi_ unicos, alias en ingles y se encuentran por id, alias y clave", bien);
        for (int f = 1; f <= 4; f++) {
            boolean mont = montadoEn(f);
            int n = disponibles(f, mont).size();
            h.ok("fase " + f + " (" + (mont ? "montado" : "a pie") + "): " + n + " habilidades (>= 3)", n >= 3);
        }
        h.ok("fase 1: martillazo, cabezazo y embestida; sin hundimiento ni rugido", Habilidad.MARTILLAZO.enFase(1)
                && Habilidad.LANZAMIENTO.enFase(1) && Habilidad.EMBESTIDA.enFase(1) && !Habilidad.HUNDIMIENTO.enFase(1)
                && !Habilidad.RUGIDO.enFase(1));
        boolean todas = true;
        for (Habilidad x : Habilidad.values()) if (x.actor == Actor.PAREJA) todas &= x.enFase(2) && x.enFase(3) && x.enFase(4);
        h.ok("de la fase II en adelante, las cinco de la pareja", todas);
        h.igual("montado en I, II y IV; a pie en la III", List.of(true, true, false, true),
                List.of(montadoEn(1), montadoEn(2), montadoEn(3), montadoEn(4)));
        h.igual("a pie (III): mazazo, barrido y cabeza negra", List.of(Habilidad.MAZAZO, Habilidad.BARRIDO, Habilidad.CABEZA_NEGRA),
                disponibles(3, false));
        boolean separadas = true;
        for (int f = 1; f <= 4; f++) {
            for (Habilidad x : disponibles(f, true)) separadas &= x.actor == Actor.PAREJA;
            for (Habilidad x : disponibles(f, false)) separadas &= x.actor == Actor.JINETE;
        }
        h.ok("montado solo sortea las de la pareja; a pie, solo las del jinete", separadas);
        h.ok("estampida, desmonte y remonte nunca entran en el sorteo", !disponible(Habilidad.ESTAMPIDA, 3, false)
                && !disponible(Habilidad.DESMONTE, 3, true) && !disponible(Habilidad.REMONTE, 4, false));
        h.ok("sin montura (a pie en cualquier fase) sigue teniendo habilidades", disponibles(1, false).size() >= 3
                && disponibles(4, false).size() >= 3);
        h.ok("sorteo: sin candidatas, nada", sortear(List.of(), null, false, 0.5) == null);
        h.igual("sorteo: con una sola, esa", Habilidad.RUGIDO, sortear(List.of(Habilidad.RUGIDO), Habilidad.RUGIDO, false, 0.9));
        h.igual("sorteo: por peso (5 de 9 para el martillazo)", Habilidad.MARTILLAZO,
                sortear(List.of(Habilidad.MARTILLAZO, Habilidad.EMBESTIDA), null, false, 0.5));
        h.igual("sorteo: la ultima pesa menos (1,75 de 5,75)", Habilidad.EMBESTIDA,
                sortear(List.of(Habilidad.MARTILLAZO, Habilidad.EMBESTIDA), Habilidad.MARTILLAZO, false, 0.5));
        h.cerca("furia: los hundimientos pesan el doble", 2 * Habilidad.HUNDIMIENTO.peso, pesoEn(Habilidad.HUNDIMIENTO, true), 1e-9);
        h.igual("furia: con hundimiento (6) y martillazo (5), al 0,5 sale el hundimiento", Habilidad.HUNDIMIENTO,
                sortear(List.of(Habilidad.HUNDIMIENTO, Habilidad.MARTILLAZO), null, true, 0.5));
        int hNormal = esperaEfectiva(Habilidad.HUNDIMIENTO, a.hab(Habilidad.HUNDIMIENTO).espera(), false, a.furiaEspera);
        int hFuria = esperaEfectiva(Habilidad.HUNDIMIENTO, a.hab(Habilidad.HUNDIMIENTO).espera(), true, a.furiaEspera);
        int mFuria = esperaEfectiva(Habilidad.MARTILLAZO, a.hab(Habilidad.MARTILLAZO).espera(), true, a.furiaEspera);
        h.ok("furia: hundimientos mas frecuentes (" + hNormal + " -> " + hFuria + " ticks) y mas que los demas",
                hFuria < hNormal && hFuria / (double) hNormal < mFuria / (double) a.hab(Habilidad.MARTILLAZO).espera());
        h.igual("furia: el rugido no se acelera", a.hab(Habilidad.RUGIDO).espera(),
                esperaEfectiva(Habilidad.RUGIDO, a.hab(Habilidad.RUGIDO).espera(), true, a.furiaEspera));
        boolean minimo = true;
        for (Habilidad x : Habilidad.values()) minimo &= esperaEfectiva(x, 20, true, 0.3) >= 20;
        h.ok("furia: ninguna espera baja de 1 s aunque la config lo pida", minimo);

        // El martillazo por distancias.
        h.igual("martillazo: a 2, 7, 13 y 20 bloques = cerca, medio, lejos y fuera",
                List.of(Zona.CERCA, Zona.MEDIO, Zona.LEJOS, Zona.FUERA), List.of(zona(2, a), zona(7, a), zona(13, a), zona(20, a)));
        h.ok("martillazo: radios en orden (cerca < medio < lejos)", a.martilloCerca < a.martilloMedio && a.martilloMedio < a.martilloLejos);
        double vy = velocidadParaAltura(a.martilloAltura);
        h.cerca("martillazo: lanzado hacia arriba " + Math.round(a.martilloAltura) + " bloques", a.martilloAltura, alturaCon(vy), 0.3);
        h.ok("martillazo: altura de serie entre 15 y 20 bloques", a.martilloAltura >= 15 && a.martilloAltura <= 20);
        h.ok("y su velocidad cabe en el paquete (" + Math.round(vy * 100) / 100.0 + ")", vy < VELOCIDAD_MAXIMA);
        double lejos = distanciaEmpuje(a.martilloEmpujeMedio, EMPUJE_MEDIO_ALTO);
        h.ok("martillazo: a media distancia sale empujado muy lejos (" + Math.round(lejos) + " bloques, >= 15)", lejos >= 15);
        h.ok("martillazo: mas lejos, un empujon leve (< 8 bloques)", distanciaEmpuje(a.martilloEmpujeLejos, 0.35) < 8);
        int vuelo = ticksDeVuelo(a.martilloSalto);
        double vh = velocidadHorizontal(12, vuelo);
        h.cerca("martillazo: el salto llega a 12 bloques en su vuelo (el primer tick frena el suelo)", 12,
                avanceDesdeSuelo(vh, vuelo), 1e-6);
        h.ok("y saltando a algo mas alto vuela menos", ticksDeVuelo(a.martilloSalto, 3) < vuelo);
        double ingenua = avanceDesdeSuelo(12 / avanceAire(1, vuelo), vuelo);
        h.ok("sin contar el freno del suelo se quedaria corto (" + Math.round(ingenua * 10) / 10.0 + " de 12)", ingenua < 11);
        h.ok("martillazo: el salto se ve en el aire mas de un segundo (" + vuelo + " ticks)", vuelo > 20);
        ConfigLoca.probar(h);

        // Cabezazo, embestida, hundimiento y rugido.
        h.ok("cabezazo: lo manda por los aires (>= 6 bloques)", alturaCon(velocidadParaAltura(a.lanzaAltura)) >= 6);
        h.cerca("embestida: de media avanza su velocidad por tick", a.embestidaVelocidad,
                impulsoSuelo(a.embestidaVelocidad) * (1 + ROCE_SUELO) / 2, 1e-9);
        h.ok("embestida: rapida (>= 15 bloques por segundo)", a.embestidaVelocidad * 20 >= 15);
        h.ok("hundimiento: sale lanzando hacia arriba (>= 8 bloques)", alturaCon(velocidadParaAltura(a.hundeAltura)) >= 8);
        h.ok("rugido: oscuridad de 2 a 3 s de serie (" + a.rugidoOscuridad + " ticks)", a.rugidoOscuridad >= 40 && a.rugidoOscuridad <= 60);
        h.igual("rugido: 10 s en la config se quedan en 3", 60, ticksOscuridad(10));
        h.ok("rugido: la oscuridad nunca es continua (espera >= 5 veces lo que dura)",
                a.hab(Habilidad.RUGIDO).espera() >= 5 * a.rugidoOscuridad);

        // La caja escalada.
        double an = anchoDe(a.escalaCuerpo), al = altoDe(a.escalaCuerpo);
        h.cerca("caja: escala 2,6 de serie", 2.6, a.escalaCuerpo, 1e-9);
        h.ok("caja: " + Math.round(an * 100) / 100.0 + " x " + Math.round(al * 100) / 100.0 + " cabe en un hueco de 4x4x4",
                Math.ceil(an) <= 4 && Math.ceil(al) <= 4);
        h.ok("sube un escalon de 1 bloque sin saltar (paso " + a.paso + ")", a.paso >= 1.0);
        h.ok("el atasco salta antes de 10 s", a.atascoTicks <= 200);
        h.ok("bloques que saltan: tope por golpe <= tope del Vigilante", a.efimerosPorGolpe <= a.efimerosMaximo);
        h.ok("nada de botin en lo que salta (menas, metales, amatista, bedrock)", valioso("DIAMOND_ORE") && valioso("IRON_BLOCK")
                && valioso("AMETHYST_BLOCK") && valioso("BEDROCK") && valioso("SPAWNER") && !valioso("GRASS_BLOCK")
                && !valioso("DEEPSLATE") && !valioso("OAK_PLANKS"));
        h.ok("la velocidad nunca pasa del paquete", limitar(new Vector(9, -9, 2)).getX() == VELOCIDAD_MAXIMA
                && limitar(new Vector(9, -9, 2)).getY() == -VELOCIDAD_MAXIMA);

        jinete(h, a);
    }

    /** Lo nuevo de la 1.14.1: el jinete, su montura, sus habilidades, la pareja bajo tierra y la caida. */
    private static void jinete(Autotest.Hoja h, Vigilante.Ajustes a) {
        // El jinete y la montura.
        h.cerca("jinete: escala 1,6 de serie", 1.6, a.jineteEscala, 1e-9);
        h.cerca("jinete: el esqueleto invisible mide lo mismo que el maniqui (" + Math.round(ALTO_JUGADOR * a.jineteEscala * 100) / 100.0
                + " bloques)", ALTO_JUGADOR * a.jineteEscala, ALTO_ESQUELETO * escalaEsqueleto(a.jineteEscala), 1e-9);
        h.igual("jinete: la skin de serie es la cuenta RocketOniPad", "RocketOniPad", a.jineteSkin);
        h.ok("jinete: la skin de serie es una cuenta valida (es lo unico que se manda a Mojang)", CuerpoNpc.cuentaValida(a.jineteSkin));
        h.ok("jinete: calabaza TALLADA en la cabeza (nunca jack o'lantern) y una maza en la mano",
                CASCO == Material.CARVED_PUMPKIN && ARMA == Material.MACE);
        h.ok("solo el jinete lleva nombre y barra; la montura, ninguno", NOMBRE_JINETE && !NOMBRE_MONTURA);
        h.igual("golpe de un jugador a la montura: pasa al jinete", Redirige.AL_JINETE, redirigir(Rol.MONTURA, false, true));
        h.igual("golpe de un jugador al maniqui: pasa al jinete", Redirige.AL_JINETE, redirigir(Rol.CASCARA, false, true));
        h.igual("caida, fuego o un mob a la montura: no entra nada", Redirige.CANCELA, redirigir(Rol.MONTURA, false, false));
        h.igual("al jinete: lo de siempre (Amenazas escala, topa y apunta el merito)", Redirige.PASA, redirigir(Rol.JINETE, false, true));
        h.igual("/kill pasa siempre (la salida del staff)", Redirige.PASA, redirigir(Rol.MONTURA, true, false));
        h.igual("el guardian vuelve a montarlo cada 2 s (como Alba)", 40, GUARDIAN_TICKS);

        // Las fases con montado y a pie.
        h.igual("fases 80/60/40/20 % = acecho, bajo tierra, a pie y furia", List.of(1, 2, 3, 4),
                List.of(Vigilante.faseDe(0.8, 1, a.desmonteVida, a.remonteVida), Vigilante.faseDe(0.6, 1, a.desmonteVida, a.remonteVida),
                        Vigilante.faseDe(0.4, 1, a.desmonteVida, a.remonteVida), Vigilante.faseDe(0.2, 1, a.desmonteVida, a.remonteVida)));
        h.ok("desmonta bajo el 50 % y remonta bajo el 25 % de serie", a.desmonteVida == 0.50 && a.remonteVida == 0.25
                && Vigilante.faseDe(0.49, 1, a.desmonteVida, a.remonteVida) == 3 && Vigilante.faseDe(0.24, 3, a.desmonteVida, a.remonteVida) == 4);

        // Bajo tierra no se ve nada: la pareja entera (con el cartel del jinete) queda bajo el suelo.
        double cima = cimaPareja(a.escalaCuerpo, a.jineteEscala);
        double asiento = ZOGLIN_ASIENTO * a.escalaCuerpo;
        h.ok("bajo tierra: la cima de la pareja (" + Math.round(cima * 100) / 100.0 + ") cubre el maniqui, el esqueleto y su cartel",
                cima >= asiento + (ALTO_JUGADOR - ASIENTO_JUGADOR) * a.jineteEscala
                        && cima >= asiento + (ALTO_ESQUELETO - ASIENTO_ESQUELETO) * escalaEsqueleto(a.jineteEscala) + CARTEL
                        && cima > altoDe(a.escalaCuerpo));
        h.ok("bajo tierra: esperando, todo queda al menos un bloque bajo el suelo", cima + 0.4 + MARGEN_HONDO - cima >= 1.0);
        double cimaLoca = cimaPareja(4.0, 2.5);
        h.ok("bajo tierra: tambien con la config mas grande (bestia x4, jinete x2,5: " + Math.round(cimaLoca * 10) / 10.0 + ")",
                cimaLoca >= ZOGLIN_ASIENTO * 4 + (ALTO_JUGADOR - ASIENTO_JUGADOR) * 2.5);
        h.ok("bajo tierra: sin calabaza ni maza a la vista (la invisibilidad no esconde el equipo)", !muestraEquipo(true) && muestraEquipo(false));
        h.ok("sale de la tierra en dos segundos (la calabaza asoma la primera)", SUBIDA_TICKS == 40);

        // Mazazo: como el martillazo, pero mas corto y mas frecuente.
        h.igual("mazazo: a 2, 5, 8 y 12 bloques = cerca, medio, lejos y fuera",
                List.of(Zona.CERCA, Zona.MEDIO, Zona.LEJOS, Zona.FUERA), List.of(zonaMazo(2, a), zonaMazo(5, a), zonaMazo(8, a), zonaMazo(12, a)));
        h.ok("mazazo: mas corto que el martillazo (radios y altura)", a.mazoLejos < a.martilloLejos && a.mazoMedio < a.martilloMedio
                && a.mazoAltura < a.martilloAltura);
        h.ok("mazazo: mas frecuente que el martillazo", a.hab(Habilidad.MAZAZO).espera() < a.hab(Habilidad.MARTILLAZO).espera());
        h.ok("mazazo: lanzado hacia arriba " + Math.round(a.mazoAltura) + " bloques (6 a 14)", a.mazoAltura >= 6 && a.mazoAltura <= 14
                && Math.abs(alturaCon(velocidadParaAltura(a.mazoAltura)) - a.mazoAltura) < 0.3);
        h.ok("mazazo: salta alto (" + Math.round(alturaCon(a.mazoSalto) * 10) / 10.0 + " bloques) y se le ve en el aire ("
                + ticksDeVuelo(a.mazoSalto) + " ticks)", alturaCon(a.mazoSalto) >= 5 && ticksDeVuelo(a.mazoSalto) >= 20);
        double lejosMazo = distanciaEmpuje(a.mazoEmpujeMedio, 0.5);
        h.ok("mazazo: a media distancia, empujado lejos (" + Math.round(lejosMazo) + " bloques, >= 9)", lejosMazo >= 9);

        // Barrido.
        h.ok("barrido: delante y dentro, si", enArco(0, 3, 0f, a.barridoRadio, a.barridoAngulo));
        h.ok("barrido: detras, no", !enArco(0, -3, 0f, a.barridoRadio, a.barridoAngulo));
        h.ok("barrido: fuera del radio, no", !enArco(0, a.barridoRadio + 0.5, 0f, a.barridoRadio, a.barridoAngulo));
        h.ok("barrido: al lado (90 grados) con un arco de " + Math.round(a.barridoAngulo) + ", " + (a.barridoAngulo >= 180 ? "si" : "no"),
                enArco(3, 0, 0f, a.barridoRadio, a.barridoAngulo) == (a.barridoAngulo >= 180));
        h.ok("barrido desde arriba: avisa medio segundo o mas", a.barridoArribaAviso >= 10);

        // Cabeza negra.
        h.cerca("cabeza negra: a quien se aleje mas de 12 bloques", 12, a.cabezaMinima, 1e-9);
        h.igual("cabeza negra: al mas lejano entre 12 y su alcance", 2, elegirLejano(new double[]{5, 14, 20, 60}, a.cabezaMinima, a.cabezaAlcance));
        h.ok("cabeza negra: a nadie si nadie se aleja", elegirLejano(new double[]{3, 8, 11.9}, a.cabezaMinima, a.cabezaAlcance) == null);
        h.ok("cabeza negra: negra, sin fuego y sin romper nada", !CABEZA_CARGADA && !CABEZA_INCENDIARIA && CABEZA_POTENCIA == 0f);
        h.ok("cabeza negra: se esquiva (al menos medio segundo de vuelo a 12 bloques)", a.cabezaMinima / a.cabezaVelocidad >= 10);

        // La bestia suelta, el desmonte y el remonte.
        h.ok("estampida: cruza la pelea (largo " + Math.round(a.estampidaLargo) + " >= retirada + 10)", a.estampidaLargo >= a.retirada + 10);
        h.ok("desmonte: la onda llega mas lejos que el golpe", a.desmonteOnda > a.desmonteRadio);
        h.ok("basicos: a pie mas seguidos que montado", a.basicoEspera < a.basicoEsperaMontado && a.basicoEspera >= 10);

        // Avisos legibles: de medio segundo a un segundo.
        boolean legibles = true;
        for (Habilidad x : new Habilidad[]{Habilidad.MAZAZO, Habilidad.BARRIDO, Habilidad.CABEZA_NEGRA, Habilidad.ESTAMPIDA,
                Habilidad.DESMONTE, Habilidad.REMONTE}) {
            legibles &= a.hab(x).aviso() >= 10 && a.hab(x).aviso() <= 20;
        }
        h.ok("avisos del jinete y de la bestia suelta: de medio segundo a un segundo", legibles);

        // La caida ya no se perdona.
        h.ok("caida: ningun perdon de caida en el Vigilante (sin onCaida, sin perdonaCaida)", sinPerdonDeCaida());
        h.ok("caida: el vuelo prestado se devuelve al empezar a caer", devolverAlCaer(20, 19.4, false, 10, false)
                && !devolverAlCaer(20, 19.8, false, 10, false));
        h.ok("caida: y al tocar suelo, o al caducar", devolverAlCaer(20, 20, true, 8, false) && devolverAlCaer(20, 20, false, 2, true)
                && !devolverAlCaer(20, 20, true, 2, false));

        // Sonidos: todos existen en Paper 26.1.2 y ninguno es una campana; particulas sobrias.
        List<String> malos = new ArrayList<>();
        Set<String> sonidos = sonidosUsados(malos);
        Set<String> paper = sonidosDePaper();
        List<String> faltan = new ArrayList<>();
        for (String s : sonidos) if (paper != null && !paper.contains(s)) faltan.add(s);
        h.ok("sonidos: " + sonidos.size() + " claves leidas de las clases del Vigilante", sonidos.size() >= 30 && malos.isEmpty());
        h.ok("sonidos: todos existen en Paper 26.1.2" + (faltan.isEmpty() ? "" : " (faltan " + faltan + ")"), paper != null && faltan.isEmpty());
        boolean sinCampana = true;
        for (String s : sonidos) sinCampana &= !s.contains("bell") && !s.contains("note_block") && !s.contains("chime");
        h.ok("sonidos: ninguna campana ni nota (la campana es de la Parca y las muertes)", sinCampana);
        Set<String> prohibidas = new HashSet<>(List.of("END_ROD", "NOTE", "GLOW", "FIREWORK", "ELECTRIC_SPARK", "FLASH", "ENCHANT",
                "TOTEM", "HEART", "GLOW_SQUID_INK", "WAX_ON", "WAX_OFF", "SCRAPE"));
        Set<String> usadas = constantesUsadas(malos);
        h.ok("particulas: se leen las que usa (bloque, humo, ceniza, polvo)", usadas.contains("BLOCK") && usadas.contains("LARGE_SMOKE")
                && usadas.contains("ASH") && usadas.contains("DUST"));
        usadas.retainAll(prohibidas);
        h.ok("particulas sobrias: ni END_ROD, ni notas, ni brillos" + (usadas.isEmpty() ? "" : " (usa " + usadas + ")"), usadas.isEmpty());
    }

    /** Las clases de la pelea y del gestor: la de fuera y todas sus internas. */
    private static List<Class<?>> clasesVigilante() {
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> c : new Class<?>[]{PeleaVigilante.class, Vigilante.class}) {
            out.add(c);
            for (Class<?> d : c.getDeclaredClasses()) out.add(d);
        }
        return out;
    }

    /** Si ni el gestor ni la pelea tienen ya nada que perdone la caida (por reflexion). */
    static boolean sinPerdonDeCaida() {
        for (Class<?> c : clasesVigilante()) {
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (n.equals("oncaida") || n.contains("perdonacaida") || n.contains("perdonarcaida")) return false;
            }
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getName().equals("CAIDA") || f.getName().equals("PERDON_CAIDA")) return false;
            }
        }
        return true;
    }

    private static final Pattern SONIDO = Pattern.compile("(entity|item|block|ambient|music|ui|weather|event|enchant|particle)\\.[a-z0-9_.]+");

    /** Las cadenas literales de las clases del Vigilante que son claves de sonido. */
    static Set<String> sonidosUsados(List<String> malos) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : constantes(malos, false)) if (SONIDO.matcher(s).matches()) out.add(s);
        return out;
    }

    /** Los campos que leen las clases del Vigilante (Compat.BLOCK, Compat.ASH...): para mirar las particulas. */
    static Set<String> constantesUsadas(List<String> malos) {
        return new HashSet<>(constantes(malos, true));
    }

    /**
     * Del constant pool de las clases del Vigilante (lectura minima del .class): las cadenas literales
     * (campos = false) o los nombres de los campos que leen (campos = true). Asi una lista de prohibidas
     * escrita en el propio autotest no cuenta como uso.
     */
    private static List<String> constantes(List<String> malos, boolean campos) {
        List<String> out = new ArrayList<>();
        for (Class<?> c : clasesVigilante()) {
            String recurso = "/" + c.getName().replace('.', '/') + ".class";
            try (InputStream in = PeleaVigilante.class.getResourceAsStream(recurso)) {
                if (in == null) {
                    malos.add(recurso);
                    continue;
                }
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                in.transferTo(b);
                DataInputStream d = new DataInputStream(new java.io.ByteArrayInputStream(b.toByteArray()));
                d.readInt();
                d.readUnsignedShort();
                d.readUnsignedShort();
                int n = d.readUnsignedShort();
                int[] tag = new int[n], r1 = new int[n], r2 = new int[n];
                String[] utf = new String[n];
                for (int i = 1; i < n; i++) {
                    tag[i] = d.readUnsignedByte();
                    switch (tag[i]) {
                        case 1 -> utf[i] = d.readUTF();
                        case 3, 4 -> d.readInt();
                        case 5, 6 -> {
                            d.readLong();
                            i++;
                        }
                        case 7, 8, 16, 19, 20 -> r1[i] = d.readUnsignedShort();
                        case 9, 10, 11, 12, 17, 18 -> {
                            r1[i] = d.readUnsignedShort();
                            r2[i] = d.readUnsignedShort();
                        }
                        case 15 -> {
                            d.readUnsignedByte();
                            d.readUnsignedShort();
                        }
                        default -> throw new IllegalStateException("etiqueta " + tag[i]);
                    }
                }
                for (int i = 1; i < n; i++) {
                    if (!campos && tag[i] == 8 && utf[r1[i]] != null) out.add(utf[r1[i]]);
                    if (campos && tag[i] == 9) {
                        int nat = r2[i];
                        if (nat > 0 && nat < n && tag[nat] == 12 && utf[r1[nat]] != null) out.add(utf[r1[nat]]);
                    }
                }
            } catch (Throwable t) {
                malos.add(recurso + ": " + t);
            }
        }
        return out;
    }

    /** Las claves de sonido de Paper (SoundEventKeys), sin servidor. Null si no se pueden leer. */
    static Set<String> sonidosDePaper() {
        try {
            Set<String> out = new HashSet<>();
            for (java.lang.reflect.Field f : io.papermc.paper.registry.keys.SoundEventKeys.class.getFields()) {
                Object v = f.get(null);
                if (v instanceof net.kyori.adventure.key.Keyed k) out.add(k.key().value());
            }
            return out.isEmpty() ? null : out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Una config con numeros disparatados: los topes de Ajustes la dejan jugable. */
    private static final class ConfigLoca {
        static void probar(Autotest.Hoja h) {
            org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
            y.set("habilidades.martillazo.altura-lanzado", 60);
            y.set("habilidades.rugido.espera-ticks", 40);
            y.set("habilidades.rugido.oscuridad-segundos", 30);
            y.set("cuerpo.paso", 0.4);
            y.set("cuerpo.escala", 9);
            y.set("efimeros.por-golpe", 500);
            y.set("efimeros.maximo", 900);
            y.set("jinete.escala", 9);
            y.set("jinete.desmonte", 0.05);
            y.set("jinete.remonte", 0.9);
            y.set("jinete.basico.espera-ticks", 1);
            y.set("jinete.basico.dano", 50);
            y.set("habilidades.mazazo.altura-lanzado", 80);
            y.set("habilidades.mazazo.radio-lejos", 90);
            y.set("habilidades.barrido.radio", 40);
            y.set("habilidades.barrido.angulo", 720);
            y.set("habilidades.barrido.arriba-aviso-ticks", 1);
            y.set("habilidades.cabeza-negra.velocidad", 9);
            y.set("habilidades.cabeza-negra.alcance", 400);
            y.set("habilidades.cabeza-negra.marchitez-segundos", 60);
            y.set("habilidades.estampida.largo", 400);
            y.set("habilidades.desmonte.onda", 90);
            y.set("habilidades.mazazo.aviso-ticks", 2);
            Vigilante.Ajustes loco = new Vigilante.Ajustes(y);
            h.ok("config loca: lanzado como mucho 24 bloques", loco.martilloAltura <= 24);
            h.ok("config loca: el rugido no se repite antes de 15 s", loco.hab(Habilidad.RUGIDO).espera() >= 300);
            h.ok("config loca: oscuridad como mucho 3 s", loco.rugidoOscuridad <= 60);
            h.ok("config loca: sigue subiendo escalones (paso >= 1,1)", loco.paso >= 1.1);
            h.ok("config loca: escala como mucho 4", loco.escalaCuerpo <= 4);
            h.ok("config loca: como mucho 24 bloques por golpe y 64 a la vez", loco.efimerosPorGolpe <= 24 && loco.efimerosMaximo <= 64);
            h.ok("config loca: jinete como mucho x2,5", loco.jineteEscala <= 2.5);
            h.ok("config loca: desmonta y remonta en orden (" + loco.desmonteVida + " > " + loco.remonteVida + ")",
                    loco.desmonteVida <= 0.70 && loco.remonteVida >= 0.10 && loco.remonteVida <= loco.desmonteVida - 0.10);
            h.ok("config loca: basicos cada 10 ticks o mas y su dano topado", loco.basicoEspera >= 10 && loco.basicoDano <= 2);
            h.ok("config loca: mazazo como mucho 14 de alto y 14 de radio", loco.mazoAltura <= 14 && loco.mazoLejos <= 14);
            h.ok("config loca: barrido como mucho 8 de radio y 240 grados, con aviso", loco.barridoRadio <= 8 && loco.barridoAngulo <= 240
                    && loco.barridoArribaAviso >= 10);
            h.ok("config loca: cabeza negra como mucho a 1,5 por tick, 48 de alcance y 5 s de marchitez",
                    loco.cabezaVelocidad <= 1.5 && loco.cabezaAlcance <= 48 && loco.cabezaMarchitez <= 100);
            h.ok("config loca: estampida como mucho 48 y onda del desmonte como mucho 14", loco.estampidaLargo <= 48 && loco.desmonteOnda <= 14);
            h.ok("config loca: el mazazo sigue avisando un segundo (golpe fuerte)", loco.hab(Habilidad.MAZAZO).aviso() >= 20);
        }
    }
}
