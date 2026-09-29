package net.ederus.calamity.hardcore;

import net.ederus.edm.anomaly.core.Disguises;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Una pelea de Ambush (Calamity 1.8.0): el samurai de los contratos de la Sentencia (Ambush).
 *
 * El cuerpo es el de la Parca: un esqueleto wither invisible de Amenazas (vida logica, tope por
 * golpe, dano logico para el botin, marca lethal_world:amenaza) con un maniqui encima (CuerpoNpc)
 * con la skin de ambush.skin-fase-1, algo mayor que un jugador. Al bajar de ambush.fase-2-vida de
 * su vida se transforma (1,5 s de pie entre humo) y pasa a la skin de ambush.skin-fase-2; desde ahi
 * sus ataques van un 25 % mas rapidos, el iaijutsu vuelve el doble de pronto, suma mil cortes y
 * cada 25 s saca las Sombras del clan.
 *
 * Solo persigue a su presa. A quien le pega le responde un rato (RESPUESTA_TICKS) y vuelve a
 * ella. Si la presa se aleja mucho (minijefes.distancia-maxima, como los minijefes) o lleva
 * ATASCO_TICKS sin poder golpearla, aparece a su espalda con humo. En la zona spawn no entra: se
 * queda quieto fuera y quien le pega desde dentro no le hace nada. En MINUTOS se retira.
 *
 * Nunca se agacha (lo pidio Dosa): aparece de pie, envaina de pie y se transforma de pie. Sus
 * ataques, todos del estilo de la carrera que deja sombras atras:
 * - la acometida: una linea roja en el suelo y la recorre de golpe tumbado como una estocada,
 *   dejando cinco sombras (cada una con su barrido y su anillo rojo);
 * - el tajo doble: plantado y mirando a su presa, su katana traza dos medias lunas rojas en cruz,
 *   una de ida y otra de vuelta;
 * - el paso sombra: se desvanece en humo, rodea a su presa dejando sombras y reaparece a su
 *   espalda con un corte;
 * - el iaijutsu: envaina 1,5 s completamente quieto dentro de un anillo rojo y corta: el doble
 *   de dano a quien siga a menos de 4 bloques y de pie;
 * - solo en la fase 2, mil cortes: tres acometidas seguidas a traves de su presa, desde tres lados,
 *   cambiando de lado con una acometida corta, y vuelve igual a donde empezo;
 * - solo en la fase 2 y cada 25 s, las Sombras del clan: tres clones a su imagen rodean a su presa
 *   y la atraviesan uno tras otro mientras el espera; un golpe disipa un clon.
 * La hoja que se ve girar en los ataques mide como la katana en la fase 1 y el doble desde la
 * transformacion.
 *
 * Cada ataque es una Tecnica que se mueve tick a tick contra una Escena: en el juego la pinta esta
 * pelea con Bukkit; en el autotest, un falso que cuenta posturas, hojas, sombras y clones. Asi se
 * comprueba sin servidor que cada ataque acaba de pie y sin dejar nada, tambien cortado a medias.
 *
 * Dos relojes, como pide que se vea fluido: la tarea de 2 ticks de Amenazas (registrarPelea) lleva
 * la cabeza (a quien va, que hace, la barra, si la presa sigue) y un animador de 1 tick mueve el
 * cuerpo que se ve, las hojas y los ataques, para que el cliente interpole sin saltos.
 *
 * 1.8.2 · Fluidez, como en la 1.8.0: en la 1.8.1 a veces "se quedaba dando vueltas super lentas y
 * cortadas" (Dosa). Eran los giros a pasitos: el balanceo del iaijutsu, Ambush y los clones de las
 * Sombras del clan siguiendo a su presa grado a grado mientras esperaban, el paso sombra despues
 * del corte y el deslizamiento de mil cortes por el borde. Ahora, mientras ataca, el cuerpo que se
 * ve o no gira o gira de golpe (GIRO_MINIMO grados por tick o mas); la entrada se da la vuelta en
 * 4 ticks y los clones vuelven en 7. Entre ataque y ataque todo sigue como en la 1.8.0: se mueve
 * su IA y el maniqui mira a los ojos de su objetivo.
 *
 * 1.8.3 · El tajo doble ya no gira el cuerpo: las dos medias vueltas del maniqui se veian a saltos
 * y pobres (Dosa). Ahora se queda plantado mirando a su presa y lo que barre es la hoja, paso a
 * paso con la interpolacion del display, en dos medias lunas que se cruzan y dejan un rastro rojo.
 */
final class PeleaAmbush implements Runnable {

    enum Estado { APARECE, PELEA, FIN }

    /** Los ataques, con lo que ensena el menu de /anomaly (AmbushType). "fase": desde cual sale. */
    enum Ataque {
        ACOMETIDA("am_acometida", "Acometida", "Marca una línea en el suelo y la recorre de golpe, tumbado como una estocada.",
                120, 36, 4, Material.TRIDENT, 1),
        TAJO("am_tajo", "Tajo doble", "Sin moverse del sitio, su katana traza dos medias lunas rojas, una de ida y otra de vuelta, y corta a quien tenga cerca.",
                100, 34, 5, Material.NETHERITE_SWORD, 1),
        PASO("am_paso", "Paso sombra", "Se desvanece en humo, rodea a su presa dejando sombras y reaparece a su espalda con un corte.",
                160, 22, 4, Material.ENDER_PEARL, 1),
        IAIJUTSU("am_iaijutsu", "Iaijutsu", "Envaina quieto 1,5 s y corta: el doble de daño a quien esté a menos de 4 bloques y de pie.",
                200, 44, 3, Material.IRON_SWORD, 1),
        MIL("am_mil", "Mil cortes", "Solo en la segunda fase: tres acometidas seguidas a través de su presa, cada una desde un lado.",
                280, 54, 3, Material.DIAMOND_SWORD, 2),
        CLAN("am_clan", "Sombras del clan", "Cada 25 s en la segunda fase: tres clones rodean a su presa y la atraviesan uno tras otro. Un golpe disipa un clon.",
                500, 72, 1, Material.WITHER_SKELETON_SKULL, 2);

        final String id;
        final String nombre;
        final String descripcion;
        /** Ticks entre dos usos (en la fase 2 el iaijutsu espera la mitad; las Sombras del clan, cada 25 s). */
        final int espera;
        /** Lo que dura mas o menos, en ticks (para /anomaly test). */
        final int duracion;
        final int peso;
        final Material icono;
        /** La fase desde la que sale (mil cortes y las Sombras del clan, solo en la 2). */
        final int fase;

        Ataque(String id, String nombre, String descripcion, int espera, int duracion, int peso, Material icono, int fase) {
            this.id = id;
            this.nombre = nombre;
            this.descripcion = descripcion;
            this.espera = espera;
            this.duracion = duracion;
            this.peso = peso;
            this.icono = icono;
            this.fase = fase;
        }

        String alias() {
            return id.substring(3);
        }

        /** Por id (am_tajo) o por alias (tajo). */
        static Ataque buscar(String nombre) {
            if (nombre == null) return null;
            String n = nombre.trim().toLowerCase(Locale.ROOT);
            for (Ataque a : values()) if (a.id.equals(n) || a.alias().equals(n)) return a;
            return null;
        }
    }

    // ------------------------------------------------------------ numeros

    /** Tamano del cuerpo que se ve: algo mayor que un jugador (la Parca es 1,68). */
    static final double ESCALA = 1.3;
    /** A que altura de sus pies va su pecho: de ahi salen las hojas y sus rastros. */
    static final double PECHO = 1.0 * ESCALA;
    private static final double ALTO_JUGADOR = 1.8, ALTO_ESQUELETO = 2.4;
    private static final double VELOCIDAD = 0.32;
    /** La fase 2 va un 25 % mas rapida: sus tiempos x0,8. */
    static final double RITMO_FASE2 = 0.8;
    /** Lo que dura como mucho la pelea: se retira. */
    static final long DURACION_TICKS = Ambush.MINUTOS * 60L * 20L;
    /** Lo que le responde a quien le pega sin ser su presa. */
    private static final long RESPUESTA_TICKS = 120;
    /** Sin golpear a su objetivo tanto tiempo: aparece a su espalda. */
    private static final long ATASCO_TICKS = 400;
    /** Las primeras Sombras del clan, a tantos ticks de transformarse; luego, cada Ataque.CLAN.espera. */
    static final long PRIMER_CLAN = 200;
    /** Hojas (ItemDisplay de la katana), sombras y clones vivos a la vez, como mucho. */
    static final int MAX_HOJAS = 2, MAX_SOMBRAS = 10, MAX_CLONES = 3;
    /** Lo que vive cada sombra. */
    static final int VIDA_SOMBRA = 9;
    /** Las sombras que deja la acometida por el camino. */
    static final int SOMBRAS_ACOMETIDA = 5;
    /** El rojo de los avisos en el suelo (la linea de la acometida, el anillo del iaijutsu). */
    static final int RGB_AVISO = 0xE0333B;
    static final int RGB_CRIMSON = 0xFF6B6B;
    /** El destello tenue con que arranca cada carrera: carmesi oscuro y medio transparente. */
    static final Color FLASH_TENUE = Color.fromARGB(0x80, 0xB0, 0x40, 0x46);

    private final Ambush gestor;
    private final Hardcore hc;
    private final Ambush.Ajustes a;
    /** El contrato que la trajo; null en la de prueba (abierta a mano desde /anomaly). */
    final Ambush.Contrato contrato;
    final UUID presa;
    final String presaNombre;
    final boolean prueba;
    final int nivel;
    private final double golpe;
    private final ItemStack katana = katana();

    WitherSkeleton cuerpo;
    private final CuerpoNpc npc;
    private boolean conNpc;

    Estado estado = Estado.APARECE;
    private int fase = 1;
    /** Ticks de verdad (los cuenta el animador, uno por tick). */
    private long ticks;
    private int vueltas;
    private long inicioPelea;
    private final long nacio = System.currentTimeMillis();
    private boolean pagada;

    private Tecnica actual;
    private BukkitTask animador;
    private final EnumMap<Ataque, Long> lista = new EnumMap<>(Ataque.class);
    private Ataque ultima;
    private long respiroHasta;
    private long ultimoGolpe;
    private UUID respuesta;
    private long respuestaHasta;
    /** La presa esta en la zona spawn: Ambush espera fuera, quieto. */
    private boolean quieto;
    private boolean avisoSpawn;
    /** A donde mira el cuerpo que se ve este tick (lo pone el ataque); null = a su objetivo. */
    private Float yawFijo;

    private BossBar barra;
    private final Set<UUID> viendo = new HashSet<>();
    private final Map<Integer, ItemDisplay> hojas = new HashMap<>();
    private final Map<Integer, Mannequin> sombras = new HashMap<>();
    /** Los clones de las Sombras del clan (MAX_CLONES como mucho). */
    private final Map<Integer, Mannequin> clones = new LinkedHashMap<>();
    private int siguienteId;
    private final EscenaReal escena = new EscenaReal();

    private PeleaAmbush(Ambush gestor, Ambush.Contrato contrato, int nivel) {
        this.gestor = gestor;
        this.hc = gestor.hc();
        this.a = gestor.ajustes();
        this.contrato = contrato;
        this.presa = contrato == null ? null : contrato.presa;
        this.presaNombre = contrato == null ? "prueba" : contrato.presaNombre;
        this.prueba = contrato == null;
        this.nivel = nivel;
        this.golpe = Ambush.golpe(a, nivel);
        // Sin katana al nacer: aparece de pie y envainado, y la entrada la desenvaina.
        this.npc = new CuerpoNpc(hc, a.skin1, ESCALA, null, "entity.player.hurt");
    }

    /**
     * Lo pone en el mundo, de pie en sitio y mirando a "deEspaldas" (de espaldas a su presa), y
     * arranca la entrada. Null si el spawn lo cancela alguien (WorldGuard, la zona spawn).
     */
    static PeleaAmbush crear(Ambush gestor, Ambush.Contrato contrato, int nivel, Location sitio, float deEspaldas) {
        if (sitio == null || sitio.getWorld() == null) return null;
        PeleaAmbush pe = new PeleaAmbush(gestor, contrato, nivel);
        return pe.nacer(sitio, deEspaldas) ? pe : null;
    }

    private boolean nacer(Location sitio, float deEspaldas) {
        Amenazas am = hc.amenazas();
        if (am == null) return false;
        conNpc = CuerpoNpc.cuentaValida(a.skin1);
        final boolean npcPedido = conNpc;
        Location l = sitio.clone();
        l.setYaw(deEspaldas);
        l.setPitch(0);
        WitherSkeleton ws = am.invocar(WitherSkeleton.class, l, "ambush", nivel, Paleta.ambush("Ambush"), e -> {
            if (presa != null) e.getPersistentDataContainer().set(Marcas.PRESA, PersistentDataType.STRING, presa.toString());
            e.setAI(false);
            e.setInvulnerable(true);
            e.setSilent(true);
            Compat.setAttribute(e, "scale", npcPedido ? ESCALA * ALTO_JUGADOR / ALTO_ESQUELETO : ESCALA);
            Compat.setAttribute(e, "knockback_resistance", 1.0);
            Compat.setAttribute(e, "movement_speed", VELOCIDAD);
            Compat.setAttribute(e, "follow_range", 64);
            Compat.setAttribute(e, "step_height", 1.5);
            Compat.setAttribute(e, "attack_damage", golpe);
            EntityEquipment eq = e.getEquipment();
            if (npcPedido) {
                // Invisible y desnudo: la invisibilidad no esconde el equipo.
                e.setInvisible(true);
                if (eq != null) eq.clear();
            } else if (eq != null) {
                eq.clear();
                eq.setItemInMainHand(katana.clone());
            }
        });
        if (ws == null) return false;
        cuerpo = ws;
        if (conNpc && !npc.poner(ws, l, null)) {
            conNpc = false;
            volverAEsqueleto();
        }
        am.vidaLogica(ws, Ambush.vida(a, nivel));
        am.topeGolpe(ws, a.topeGolpe);
        am.ancla(ws, sitio);
        // La skin de la fase 2 se pide ya: al transformarse sale al momento, sin la de serie entre medias.
        if (CuerpoNpc.cuentaValida(a.skin2)) Disguises.resolveAccount(hc.plugin(), a.skin2, r -> { });
        am.registrarPelea(this);
        gestor.registrar(this);
        animador = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(), () -> hc.seguro("ambush", this::animar), 1L, 1L);
        empezar(new Entrada(deEspaldas));
        return true;
    }

    /** Sin maniqui (no se pudo, o alguien lo borro): el esqueleto se ve, con la katana. */
    private void volverAEsqueleto() {
        if (cuerpo == null || !cuerpo.isValid()) return;
        cuerpo.setInvisible(false);
        Compat.setAttribute(cuerpo, "scale", ESCALA);
        EntityEquipment eq = cuerpo.getEquipment();
        if (eq != null) eq.setItemInMainHand(katana.clone());
    }

    /** La katana que lleva: una espada de netherita con el nombre de la Masamune, que es lo que deja. */
    static ItemStack katana() {
        ItemStack it = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(Paleta.nombre("Masamune", Paleta.ACERO));
            it.setItemMeta(m);
        }
        return it;
    }

    // ================================================================ numeros

    /** La fase 2 salta al bajar de "umbral" de su vida (fraccion <= umbral) y solo una vez. */
    static boolean tocaFase2(double fraccion, double umbral, int fase) {
        return fase == 1 && fraccion <= umbral;
    }

    /** La hoja de los efectos: como la katana (1,0) hasta que se transforma; desde ahi, el doble. */
    static float escalaHoja(int fase) {
        return fase >= 2 ? 2f : 1f;
    }

    /** A que distancia de su pecho gira el centro de la hoja: con el mango siempre junto a el. */
    static double radioHoja(float escala) {
        return 0.6 + 0.6 * escala;
    }

    /** El yaw de Minecraft que mira de "desde" hacia "hacia", en el plano. */
    static float yaw(Location desde, Location hacia) {
        return yaw(hacia.getX() - desde.getX(), hacia.getZ() - desde.getZ());
    }

    static float yaw(double dx, double dz) {
        if (dx * dx + dz * dz < 1e-8) return 0f;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** La direccion (en el plano, unitaria) de un yaw. */
    static Vector dir(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    /** De "desde" a "hasta" por el camino corto, en t (0-1). */
    static float girar(float desde, float hasta, double t) {
        double d = ((hasta - desde) % 360 + 540) % 360 - 180;
        return (float) (desde + d * Math.max(0, Math.min(1, t)));
    }

    /**
     * 1.8.2 · Lo menos que gira el cuerpo en un tick cuando gira: por debajo, en el cliente se ve
     * dar vueltas despacio y a trompicones (lo que vio Dosa en la 1.8.1).
     */
    static final double GIRO_MINIMO = 30;

    /**
     * En cuantos ticks se da un giro de "grados": los mas posibles hasta "tope" sin bajar de
     * GIRO_MINIMO por tick; uno solo (de golpe) si no llega ni a eso.
     */
    static int pasosDeGiro(double grados, int tope) {
        return (int) Math.max(1, Math.min(tope, Math.floor(Math.abs(grados) / GIRO_MINIMO)));
    }

    /** La direccion en el plano (unitaria) de "desde" a "hacia"; si estan encima, "otra". */
    static Vector plano(Location desde, Location hacia, Vector otra) {
        double dx = hacia.getX() - desde.getX(), dz = hacia.getZ() - desde.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        return d < 1e-6 ? otra.clone() : new Vector(dx / d, 0, dz / d);
    }

    /** La distancia en el plano. */
    static double distPlano(Location a, Location b) {
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** v girado "ang" radianes en el plano (de X hacia Z). */
    static Vector rotar(Vector v, double ang) {
        double c = Math.cos(ang), s = Math.sin(ang);
        return new Vector(v.getX() * c - v.getZ() * s, 0, v.getX() * s + v.getZ() * c);
    }

    /**
     * La hoja en el angulo "yaw": tumbada, con el mango hacia Ambush y la punta hacia fuera, y su
     * centro a "radio" de el. El sprite de la espada va en diagonal (mango abajo a la izquierda):
     * se gira 45 grados para ponerlo derecho, 90 para tumbarlo y el yaw para orientarlo.
     */
    static Transformation hojaEn(float yaw, double radio, float escala) {
        return hojaEn(yaw, 0f, 0f, radio, escala);
    }

    /**
     * 1.8.3 · La hoja en el plano de un corte (planoCorte), igual de tumbada dentro de el: con el
     * mango hacia Ambush, la punta hacia fuera y su centro a "radio" de su pecho.
     */
    static Transformation hojaEn(float frente, float angulo, float inclina, double radio, float escala) {
        Quaternionf plano = planoCorte(frente, angulo, inclina);
        Quaternionf giro = new Quaternionf(plano).rotateX((float) (Math.PI / 2)).rotateZ((float) (Math.PI / 4));
        Vector3f t = plano.transform(new Vector3f(0, 0, (float) radio));
        return new Transformation(t, giro, new Vector3f(escala, escala, escala), new Quaternionf());
    }

    /**
     * 1.8.3 · El giro del plano de un corte: el del yaw "frente", ladeado "inclina" grados alrededor
     * de ese frente (positivo: su izquierda arriba y su derecha abajo) y, dentro de ese plano,
     * "angulo" grados desde el frente (negativo: hacia su izquierda). Con inclina y angulo 0 es el
     * yaw sin mas, como la hoja de siempre.
     */
    static Quaternionf planoCorte(float frente, float angulo, float inclina) {
        return new Quaternionf().rotationY((float) -Math.toRadians(frente)).rotateZ((float) Math.toRadians(inclina))
                .rotateY((float) -Math.toRadians(angulo));
    }

    /** El punto a "radio" de su pecho en el plano de un corte: por donde pasa la hoja (o su punta). */
    static Vector enCorte(float frente, float angulo, float inclina, double radio) {
        Vector3f p = planoCorte(frente, angulo, inclina).transform(new Vector3f(0, 0, (float) radio));
        return new Vector(p.x, p.y, p.z);
    }

    // ================================================================ la escena

    /**
     * Lo que un ataque toca del mundo. En el juego lo hace esta pelea (EscenaReal); en el autotest,
     * EscenaPrueba, que solo cuenta posturas, hojas, sombras y clones.
     */
    interface Escena {
        /** Donde esta Ambush (sus pies). */
        Location pie();

        /** Donde esta a quien va el ataque, o null. */
        Location objetivo();

        /** Mueve el cuerpo que pelea; el que se ve le sigue en el mismo tick. */
        void mover(Location l);

        /** Hacia donde mira el cuerpo que se ve este tick (yaw de Minecraft). */
        void mirar(float yaw);

        void postura(Pose p);

        /** La katana en la mano (true) o la mano vacia (envainando). */
        void katana(boolean enLaMano);

        void blandir();

        /** El cuerpo que se ve, visible o no (el paso sombra lo esconde entre humo). */
        void visible(boolean si);

        /** Una hoja (la katana en un ItemDisplay) girando a su alrededor. -1 si ya hay MAX_HOJAS. */
        default int hoja(float yaw) {
            return hojaLadeada(yaw, 0f, 0f);
        }

        /** Gira esa hoja hasta el yaw, interpolado en "ticks". */
        default void hoja(int id, float yaw, int ticks) {
            hojaLadeada(id, yaw, 0f, 0f, ticks);
        }

        /**
         * 1.8.3 · Una hoja en el plano de un corte (planoCorte): a "angulo" grados de "frente", en
         * un plano ladeado "inclina" grados. -1 si ya hay MAX_HOJAS.
         */
        int hojaLadeada(float frente, float angulo, float inclina);

        /** 1.8.3 · Lleva esa hoja a ese punto del plano de un corte, interpolado en "ticks". */
        void hojaLadeada(int id, float frente, float angulo, float inclina, int ticks);

        /** Lo que mide ahora la hoja (escalaHoja de su fase): los rastros siguen su punta. */
        float tamanoHoja();

        void quitarHoja(int id);

        /** Una copia visual de Ambush (misma skin, sin IA ni choque) en ese sitio. -1 si no se puede. */
        int sombra(Location l, float yaw, Pose pose);

        void quitarSombra(int id);

        /**
         * Un clon de las Sombras del clan: la misma skin, de pie y con la mano vacia, sin IA ni
         * choque; nada le hace dano, pero un golpe de cualquier jugador lo disipa. -1 si no se puede.
         */
        int clon(Location l, float yaw);

        /** Si ese clon sigue ahi (no lo han disipado de un golpe ni se ha ido). */
        boolean clonVivo(int id);

        /** Donde esta ese clon, o null. */
        Location clonPie(int id);

        void moverClon(int id, Location l);

        void clonPostura(int id, Pose p);

        void clonKatana(int id, boolean enLaMano);

        void clonBlandir(int id);

        void quitarClon(int id);

        /** Retira todo lo que haya puesto cualquier ataque (hojas, sombras y clones) y lo deja visible. */
        void retirarTodo();

        /** Los puntos (cada 0,5) de una carrera en linea recta a ras de suelo, cortada en la primera pared. */
        List<Location> ruta(Location desde, Vector dir, double largo);

        /** El suelo bajo un punto (para los avisos pintados). */
        Location suelo(Location l);

        /** Un sitio libre con suelo cerca de l donde puede estar de pie, o null si no hay. */
        Location hueco(Location l);

        void polvo(Location l, int rgb, float tam);

        void particula(Particle p, Location l, int n, double dx, double dy, double dz, double v, Object datos);

        void sonido(Location l, String clave, float volumen, float tono);

        /** Hiere a quien este a "radio" de "centro" (sin los agachados si perdona). k: veces su golpe. */
        int herirCerca(Location centro, double radio, double k, boolean perdonaAgachados);

        /** Hiere a quien este a "ancho" del tramo a-b y no este en tocados, y le empuja de lado. */
        int herirTramo(Location a, Location b, double ancho, double k, Set<UUID> tocados, Vector dir);

        /** A su objetivo le tiembla la vista un instante, sin dano (como con la Parca), desde "desde". */
        void sacudir(Location desde);

        /** Un aviso en la barra de accion a quien este a "radio". */
        void avisar(String texto, double radio);

        /** Un aviso en la barra de accion solo a su presa. */
        void avisarPresa(String texto);

        /** Cambia la skin del cuerpo que se ve (la fase 2). */
        void cambiarSkin(String cuenta);
    }

    /** Un ataque (o la entrada, o la transformacion): un tick cada vez contra la Escena. */
    abstract static class Tecnica {
        final Ataque ataque;
        long t;

        Tecnica(Ataque ataque) {
            this.ataque = ataque;
        }

        /** Un tick. True al acabar: para entonces Ambush esta de pie, visible y sin hojas, sombras ni clones de este ataque. */
        abstract boolean paso(Escena e);

        /** Cortado a medias (muere, se va, cambia de fase): lo deja todo como al acabar. */
        abstract void cortar(Escena e);

        String rotulo() {
            return ataque == null ? "" : ataque.nombre;
        }
    }

    static Tecnica nueva(Ataque x, double ritmo) {
        return switch (x) {
            case ACOMETIDA -> new Acometida(ritmo);
            case TAJO -> new TajoDoble(ritmo);
            case PASO -> new PasoSombra(ritmo);
            case IAIJUTSU -> new Iaijutsu(ritmo);
            case MIL -> new MilCortes(ritmo);
            case CLAN -> new Clan(ritmo);
        };
    }

    /**
     * Lo que hace limpiar() con los ataques, sea cual sea el motivo (muere, se retira, se para el
     * plugin o se descarga el mundo): corta el que haya y retira del mundo todo lo que quede.
     */
    static void retirar(Tecnica tec, Escena e) {
        if (tec != null) {
            try {
                tec.cortar(e);
            } catch (Throwable ignorado) {
                // Lo que no se pudo cortar lo retira retirarTodo justo debajo.
            }
        }
        e.retirarTodo();
    }

    /** Un anillo de "puntos" puntos a "radio" de "centro", asentado en el suelo. */
    static List<Location> anillo(Escena e, Location centro, double radio, int puntos) {
        List<Location> out = new ArrayList<>();
        for (int i = 0; i < puntos; i++) {
            double ang = Math.PI * 2 * i / puntos;
            out.add(e.suelo(centro.clone().add(Math.cos(ang) * radio, 0, Math.sin(ang) * radio)).add(0, 0.12, 0));
        }
        return out;
    }

    /** SWEEP_ATTACK a su alrededor, a la altura del pecho: el corte que se ve. */
    static void barrido(Escena e, Location pie, double radio) {
        for (int i = 0; i < 8; i++) {
            Vector d = dir(i * 45f).multiply(radio);
            e.particula(Compat.SWEEP_ATTACK, pie.clone().add(d).add(0, 1.1, 0), 1, 0, 0, 0, 0, null);
        }
    }

    /** La linea roja de aviso de una carrera (un punto por bloque), que crece hasta que arranca. */
    static void pintarLinea(Escena e, List<Location> ruta, long t, int aviso) {
        float tam = (float) (1.0 + 0.6 * Math.min(1, t / (double) Math.max(1, aviso)));
        for (int i = 0; i < ruta.size(); i += 2) e.polvo(ruta.get(i).clone().add(0, 0.15, 0), RGB_AVISO, tam);
    }

    // ------------------------------------------------------------------ Sombras

    /** Las sombras vivas de un ataque: id -> tick en que se van. */
    static final class Sombras {
        private final Map<Integer, Long> vivas = new LinkedHashMap<>();

        /**
         * Una sombra en l mirando a yaw, que se va a los VIDA_SOMBRA ticks. Con "marca" sale con
         * un barrido y un pequeno anillo rojo a sus pies (las de las carreras).
         */
        void poner(Escena e, Location l, float yaw, Pose pose, long t, boolean marca) {
            int id = e.sombra(l, yaw, pose);
            if (id >= 0) vivas.put(id, t + VIDA_SOMBRA);
            if (!marca) return;
            e.particula(Compat.SWEEP_ATTACK, l.clone().add(0, 1.0, 0), 1, 0, 0, 0, 0, null);
            for (int i = 0; i < 10; i++) {
                double a = Math.PI * 2 * i / 10;
                e.polvo(l.clone().add(Math.cos(a) * 0.7, 0.12, Math.sin(a) * 0.7), RGB_AVISO, 0.9f);
            }
        }

        /** Quita las que ya han cumplido. */
        void repasar(Escena e, long t) {
            vivas.entrySet().removeIf(s -> {
                if (t < s.getValue()) return false;
                e.quitarSombra(s.getKey());
                return true;
            });
        }

        void quitarTodas(Escena e) {
            for (int id : vivas.keySet()) e.quitarSombra(id);
            vivas.clear();
        }

        boolean vacia() {
            return vivas.isEmpty();
        }
    }

    // ------------------------------------------------------------------ Carrera

    /**
     * Una carrera en linea recta dejando sombras atras: la de la acometida, las de mil cortes y las
     * de los clones. Arranca tumbado (FALL_FLYING) con un destello tenue y el sonido del riptide,
     * avanza porTick puntos de la ruta (0,5 bloques cada uno) por tick con chispas de critico por
     * donde pasa, deja "sombras" sombras repartidas por el camino y quien este en la linea se lleva
     * k veces su golpe. Al pasar junto a su objetivo suena el tajo y le tiembla la vista. Al final
     * se pone de pie. clon = -1 es el propio Ambush; si no, el clon que corre (si lo disipan a
     * medias, la carrera se acaba ahi y su corte ya no llega). Con k 0 (1.8.2, el cambio de lado de
     * mil cortes) no hiere, no hay tajo ni temblor: solo la carrera y sus sombras. Encadenadas (mil
     * cortes, 1.8.2) ni se pone de pie entre una y otra ni repite el arranque: seguida y sigue.
     */
    static final class Carrera {
        static final double ANCHO = 1.4;
        /** Hasta donde de su linea cuenta que ha pasado junto a su objetivo (el tajo y el temblor). */
        static final double CRUCE = ANCHO + 1.6;
        final List<Location> ruta;
        final Vector dir;
        final float yaw;
        final int porTick;
        final double k;
        final int clon;
        private final int[] marcas;
        private final Set<UUID> tocados = new HashSet<>();
        private int indice, marca;
        private boolean arrancada, cruzada, acabada;
        /** 1.8.2 · Viene justo detras de otra y ya va tumbado: sin postura, destello, riptide ni blandir. */
        boolean seguida;
        /** 1.8.2 · Detras viene otra: al acabar se queda tumbado en vez de ponerse de pie. */
        boolean sigue;

        Carrera(List<Location> ruta, Vector dir, int porTick, double k, int sombras, int clon) {
            this.ruta = ruta;
            this.dir = dir.clone().setY(0);
            this.yaw = yaw(dir.getX(), dir.getZ());
            this.porTick = Math.max(1, porTick);
            this.k = k;
            this.clon = clon;
            int ultimo = Math.max(0, ruta.size() - 1);
            int n = ultimo == 0 ? 0 : Math.max(0, sombras);
            marcas = new int[n];
            for (int j = 0; j < n; j++) marcas[j] = (int) ((long) j * ultimo / n);
        }

        boolean acabada() {
            return acabada;
        }

        /** Un tick. True al llegar al final (ya de pie, salvo que siga otra) o si el clon que corria ya no esta. */
        boolean paso(Escena e, Sombras sombras, long t) {
            if (acabada) return true;
            Location antes = clon < 0 ? e.pie() : e.clonVivo(clon) ? e.clonPie(clon) : null;
            if (antes == null || ruta.size() < 2) {
                acabada = true;
                return true;
            }
            if (!arrancada) {
                arrancada = true;
                if (!seguida) {
                    postura(e, Pose.FALL_FLYING);
                    blandir(e);
                    e.sonido(antes, "item.trident.riptide_3", 1.4f, 1.0f);
                    e.particula(Compat.FLASH, antes.clone().add(0, 1.0, 0), 1, 0, 0, 0, 0, FLASH_TENUE);
                }
            }
            int hasta = Math.min(ruta.size() - 1, indice + porTick);
            if (hasta > indice) {
                // Las sombras que caen en este tramo, cada una en su punto del camino.
                while (marca < marcas.length && marcas[marca] < hasta) {
                    sombras.poner(e, ruta.get(marcas[marca]), yaw, Pose.FALL_FLYING, t, true);
                    marca++;
                }
                Location destino = ruta.get(hasta).clone();
                destino.setYaw(yaw);
                destino.setPitch(0);
                mover(e, destino);
                // k 0: la acometida corta de mil cortes para cambiar de lado, que no hiere ni corta.
                if (k > 0) e.herirTramo(antes, destino, ANCHO, k, tocados, dir);
                for (int i = indice + 1; i <= hasta; i++) {
                    e.particula(Compat.CRIT, ruta.get(i).clone().add(0, 0.9, 0), 2, 0.15, 0.2, 0.15, 0.05, null);
                }
                if (k > 0) cruce(e, antes, destino);
                indice = hasta;
            }
            if (indice < ruta.size() - 1) return false;
            acabada = true;
            if (!sigue) postura(e, Pose.STANDING);
            Location fin = ruta.get(ruta.size() - 1);
            if (k > 0) {
                e.particula(Compat.SWEEP_ATTACK, fin.clone().add(0, 1, 0), 3, 0.6, 0.3, 0.6, 0, null);
                e.sonido(fin, "entity.player.attack.sweep", 1.3f, 0.9f);
            }
            e.particula(Compat.LARGE_SMOKE, fin.clone().add(0, 0.3, 0), 8, 0.5, 0.2, 0.5, 0.02, null);
            return true;
        }

        /** Si en este tramo pasa junto a su objetivo: el tajo suena en el y le tiembla la vista (una vez). */
        private void cruce(Escena e, Location antes, Location destino) {
            if (cruzada) return;
            Location obj = e.objetivo();
            if (obj == null || Math.abs(obj.getY() - destino.getY()) > 3) return;
            double delante = (obj.getX() - antes.getX()) * dir.getX() + (obj.getZ() - antes.getZ()) * dir.getZ();
            double detras = (obj.getX() - destino.getX()) * dir.getX() + (obj.getZ() - destino.getZ()) * dir.getZ();
            if (delante < 0 || detras > 0) return;
            if (ParcaAnomalia.distanciaASegmento(obj.getX(), obj.getZ(), antes.getX(), antes.getZ(),
                    destino.getX(), destino.getZ()) > CRUCE) return;
            cruzada = true;
            e.sonido(obj, "entity.player.attack.sweep", 1.4f, 1.1f);
            e.sacudir(destino);
        }

        void cortar(Escena e) {
            acabada = true;
            postura(e, Pose.STANDING);
        }

        private void mover(Escena e, Location l) {
            if (clon < 0) e.mover(l);
            else e.moverClon(clon, l);
        }

        private void postura(Escena e, Pose p) {
            if (clon < 0) e.postura(p);
            else e.clonPostura(clon, p);
        }

        private void blandir(Escena e) {
            if (clon < 0) e.blandir();
            else e.clonBlandir(clon);
        }
    }

    // ------------------------------------------------------------------ Entrada

    /**
     * La entrada: aparece de pie, de espaldas a su presa y con la mano vacia (1 s, campana grave),
     * se gira hacia ella de golpe (1.8.2: en GIRO ticks como mucho y nunca a menos de GIRO_MINIMO
     * grados por tick; antes 6 ticks) y desenvaina (la katana en la mano, un destello y el sonido
     * de la cadena). Ya girado no sigue a su presa a pasitos: se queda mirando a donde se giro.
     */
    static final class Entrada extends Tecnica {
        static final int QUIETO = 20, GIRO = 4, DESENVAINA = QUIETO + GIRO, FIN = DESENVAINA + 4;
        private final float deEspaldas;
        /** A donde se gira (se fija al empezar el giro) y en cuantos ticks. */
        private float haciaEl;
        private int pasos = 1;

        Entrada(float deEspaldas) {
            super(null);
            this.deEspaldas = deEspaldas;
            this.haciaEl = deEspaldas + 180f;
        }

        @Override
        boolean paso(Escena e) {
            Location pie = e.pie();
            if (t == 0) {
                e.postura(Pose.STANDING);
                e.katana(false);
                e.sonido(pie, "block.bell.use", 2f, 0.5f);
                e.particula(Compat.LARGE_SMOKE, pie.clone().add(0, 0.5, 0), 20, 0.5, 0.4, 0.5, 0.02, null);
                e.particula(Compat.ASH, pie.clone().add(0, 1, 0), 30, 0.8, 0.8, 0.8, 0.01, null);
            }
            if (t < QUIETO) {
                e.mirar(deEspaldas);
                if (t % 4 == 0) e.particula(Compat.ASH, pie.clone().add(0, 1.2, 0), 6, 0.6, 0.6, 0.6, 0.01, null);
                return false;
            }
            if (t == QUIETO) {
                Location obj = e.objetivo();
                if (obj != null) haciaEl = yaw(pie, obj);
                pasos = pasosDeGiro(difYaw(haciaEl, deEspaldas), GIRO);
            }
            if (t < QUIETO + pasos) {
                e.mirar(girar(deEspaldas, haciaEl, (t - QUIETO + 1) / (double) pasos));
                return false;
            }
            e.mirar(haciaEl);
            if (t == DESENVAINA) {
                e.katana(true);
                e.blandir();
                e.particula(Compat.FLASH, pie.clone().add(0, 1.2, 0), 1, 0, 0, 0, 0, Color.fromRGB(RGB_CRIMSON));
                e.sonido(pie, "block.chain.break", 1.2f, 0.8f);
            }
            return t >= FIN;
        }

        @Override
        void cortar(Escena e) {
            e.postura(Pose.STANDING);
            e.katana(true);
        }
    }

    // ---------------------------------------------------------- Transformacion

    /**
     * El paso a la fase 2: 1,5 s quieto y de pie entre humo, con la llamada grave del evocador;
     * cambia de skin y, con un destello, sigue.
     */
    static final class Transformacion extends Tecnica {
        static final int CAMBIO = 16, FIN = 30;
        private final String skin;

        Transformacion(String skin) {
            super(null);
            this.skin = skin;
        }

        @Override
        boolean paso(Escena e) {
            Location pie = e.pie();
            if (t == 0) {
                e.postura(Pose.STANDING);
                e.sonido(pie, "entity.evoker.prepare_summon", 1.5f, 0.6f);
                e.avisarPresa("Ambush se transforma.");
            }
            if (t % 2 == 0) e.particula(Compat.LARGE_SMOKE, pie.clone().add(0, 1, 0), 6, 0.5, 0.8, 0.5, 0.02, null);
            if (t == CAMBIO) e.cambiarSkin(skin);
            if (t < FIN) return false;
            e.postura(Pose.STANDING);
            e.particula(Compat.FLASH, pie.clone().add(0, 1.2, 0), 1, 0, 0, 0, 0, Color.fromRGB(RGB_CRIMSON));
            return true;
        }

        @Override
        void cortar(Escena e) {
            e.cambiarSkin(skin);
            e.postura(Pose.STANDING);
        }

        @Override
        String rotulo() {
            return "Se transforma";
        }
    }

    // ---------------------------------------------------------------- Acometida

    /**
     * Marca en el suelo una linea roja hasta su presa (y 3 bloques mas alla, cortada en la primera
     * pared) durante 0,5 s y la recorre de golpe tumbado (una Carrera), a 1,5 bloques por tick (2
     * en la fase 2), dejando SOMBRAS_ACOMETIDA sombras por el camino, cada una con su barrido y su
     * anillo rojo. Quien este en la linea se lleva el golpe y un empujon de lado.
     */
    static final class Acometida extends Tecnica {
        static final double K = 1.2, ANCHO = Carrera.ANCHO;
        final int aviso, porTick;
        private List<Location> ruta = List.of();
        private Carrera carrera;
        private long frenoEn = -1;
        private final Sombras sombras = new Sombras();

        Acometida(double ritmo) {
            super(Ataque.ACOMETIDA);
            aviso = Math.max(6, (int) Math.round(10 * ritmo));
            porTick = ritmo < 1 ? 4 : 3;
        }

        @Override
        boolean paso(Escena e) {
            if (t == 0) {
                Location pie = e.pie(), obj = e.objetivo();
                if (obj == null) return true;
                Vector dir = plano(pie, obj, new Vector(0, 0, 1));
                ruta = e.ruta(pie, dir, Math.max(6, Math.min(16, distPlano(pie, obj) + 3)));
                if (ruta.size() < 3) return true;
                carrera = new Carrera(ruta, dir, porTick, K, SOMBRAS_ACOMETIDA, -1);
                e.katana(true);
                e.sonido(pie, "item.trident.return", 1.2f, 0.6f);
            }
            e.mirar(carrera.yaw);
            if (t < aviso) {
                if (t % 2 == 0) pintarLinea(e, ruta, t, aviso);
                return false;
            }
            if (!carrera.acabada() && carrera.paso(e, sombras, t)) frenoEn = t;
            sombras.repasar(e, t);
            return carrera.acabada() && frenoEn >= 0 && sombras.vacia() && t - frenoEn >= 4;
        }

        @Override
        void cortar(Escena e) {
            if (carrera != null) carrera.cortar(e);
            sombras.quitarTodas(e);
            e.postura(Pose.STANDING);
        }
    }

    // ---------------------------------------------------------------- Tajo doble

    /**
     * Dos cortes en cruz sin moverse del sitio (1.8.3). Al empezar se queda de pie mirando a su
     * presa y ya no se gira en todo el tajo: lo que se mueve es la hoja. Sale a su izquierda, algo
     * alta, y durante el aviso se echa hacia atras (ARMADO grados). En el primer corte barre de un
     * tiron una media luna de ARCO grados por delante de el, de su izquierda (arriba) a su derecha
     * (abajo); en la pausa sube por la derecha y en el segundo corte vuelve al reves, de su derecha
     * (arriba) a su izquierda (abajo), asi que las dos medias lunas se cruzan delante de su pecho.
     *
     * La hoja avanza ARCO / giro grados cada tick (33 en la fase 1, 40 en la 2) y el display
     * interpola cada paso en MOVIMIENTO ticks: en el cliente se ve un barrido seguido, sin saltos.
     * Detras deja un rastro de polvo con forma de media luna (el filo carmesi por fuera, ancho en
     * medio y fino en las puntas) y chispas de critico en la punta. En cada corte, el brazo y el
     * sonido del barrido al arrancar y, a mitad, el barrido de siempre y el golpe: K de su golpe a
     * quien este a RADIO, alrededor de el. Los tiempos y el dano son los de antes.
     *
     * Hasta la 1.8.2 el cuerpo daba media vuelta en cada corte junto con la hoja y en el cliente se
     * veia a saltos. El maniqui no admite la postura del giro del tridente.
     */
    static final class TajoDoble extends Tecnica {
        static final double RADIO = 3.8, K = 0.9;
        /** Lo que barre cada corte, repartido a partes iguales a los dos lados de su frente. */
        static final float ARCO = 200f;
        /** Lo ladeado del plano de cada corte (el primero, izquierda arriba; el segundo, al reves). */
        static final float INCLINA = 20f;
        /** Lo que se echa atras la hoja durante el aviso, antes del primer corte. */
        static final float ARMADO = 30f;
        /** En cuantos ticks interpola el display cada paso de la hoja. */
        static final int MOVIMIENTO = 2;
        /** Cada cuanto (en bloques, a lo largo de la punta) cae un punto del rastro. */
        static final double PASO_RASTRO = 0.25;
        final int aviso, giro, pausa, respiro;
        /** A donde mira todo el tajo: a su presa al empezar. */
        private float frente;
        private float escala = 1f;
        private int hoja = -1;

        TajoDoble(double ritmo) {
            super(Ataque.TAJO);
            aviso = Math.max(6, (int) Math.round(10 * ritmo));
            giro = Math.max(3, (int) Math.round(6 * ritmo));
            pausa = Math.max(2, (int) Math.round(4 * ritmo));
            respiro = Math.max(3, (int) Math.round(6 * ritmo));
        }

        @Override
        boolean paso(Escena e) {
            Location pie = e.pie();
            if (t == 0) {
                Location obj = e.objetivo();
                frente = obj == null ? pie.getYaw() : yaw(pie, obj);
                escala = e.tamanoHoja();
                e.postura(Pose.STANDING);
                e.katana(true);
                hoja = e.hojaLadeada(frente, -ARCO / 2 + ARMADO, INCLINA);
                e.sonido(pie, "item.trident.return", 1.2f, 0.7f);
            }
            // El cuerpo no gira: todo el tajo mirando a donde estaba su presa al empezar.
            e.mirar(frente);
            long c2 = aviso + giro + pausa, fin = c2 + giro + respiro;
            if (t < aviso) {
                // Un display recien puesto no anima su primer movimiento: se echa atras desde el tick 2.
                if (t == 2 && hoja >= 0) e.hojaLadeada(hoja, frente, -ARCO / 2, INCLINA, aviso - 2);
                return false;
            }
            int corte = t < c2 ? 0 : 1;
            long dt = t - (corte == 0 ? aviso : c2);
            float inclina = corte == 0 ? INCLINA : -INCLINA;
            if (dt < giro) {
                if (hoja >= 0) e.hojaLadeada(hoja, frente, angulo(corte, dt + 1), inclina, MOVIMIENTO);
                if (dt == 0) {
                    e.blandir();
                    e.sonido(pie, "entity.player.attack.sweep", 1.4f, 0.8f + 0.2f * corte);
                }
                if (dt == giro / 2) {
                    barrido(e, pie, 2.2);
                    e.herirCerca(pie, RADIO, K, false);
                }
            }
            // Interpolado en MOVIMIENTO ticks, en el cliente la hoja va un paso por detras del que se
            // le pide: el rastro pinta el tramo que esta cruzando ahora (y el ultimo, al tick siguiente).
            if (dt >= 1 && dt <= giro) rastro(e, pie, angulo(corte, dt - 1), angulo(corte, dt), inclina);
            // En la pausa, ya al final del primer corte, sube por su derecha para el segundo.
            if (corte == 0 && dt == giro + 1 && hoja >= 0) {
                e.hojaLadeada(hoja, frente, ARCO / 2, -INCLINA, Math.max(1, pausa - 1));
            }
            if (t < fin) return false;
            if (hoja >= 0) e.quitarHoja(hoja);
            hoja = -1;
            e.postura(Pose.STANDING);
            return true;
        }

        /**
         * Hacia donde apunta la hoja tras "k" de los "giro" pasos de ese corte (0: donde empieza), en
         * grados desde su frente: el primero va de su izquierda a su derecha y el segundo al reves.
         */
        float angulo(int corte, long k) {
            float a = -ARCO / 2 + ARCO * k / giro;
            return corte == 0 ? a : -a;
        }

        /**
         * El rastro de la hoja entre dos puntos del corte: polvo rojo a lo largo de la punta, con el
         * filo carmesi por fuera y mas ancho hacia dentro cuanto mas cerca del centro de la media
         * luna, y unas chispas de critico donde llega la punta.
         */
        private void rastro(Escena e, Location pie, float desde, float hasta, float inclina) {
            Location pecho = pie.clone().add(0, PECHO, 0);
            double punta = radioHoja(escala) + 0.55 * escala;
            double grosor = 0.3 + 0.5 * escala;
            int puntos = Math.max(3, (int) Math.ceil(Math.toRadians(Math.abs(hasta - desde)) * punta / PASO_RASTRO));
            for (int i = 1; i <= puntos; i++) {
                float a = desde + (hasta - desde) * i / puntos;
                // 0 en las puntas de la media luna y 1 delante de el.
                double medio = Math.sin(Math.PI * Math.max(0, Math.min(1, (a + ARCO / 2) / ARCO)));
                double ancho = grosor * medio;
                int capas = 1 + (int) Math.round(ancho / 0.3);
                for (int c = 0; c < capas; c++) {
                    double r = punta - (capas == 1 ? 0 : ancho * c / (capas - 1));
                    e.polvo(pecho.clone().add(enCorte(frente, a, inclina, r)), c == 0 ? RGB_CRIMSON : RGB_AVISO,
                            c == 0 ? 1.4f : 1.1f);
                }
            }
            e.particula(Compat.CRIT, pecho.clone().add(enCorte(frente, hasta, inclina, punta)), 2, 0.1, 0.1, 0.1, 0.05, null);
        }

        @Override
        void cortar(Escena e) {
            if (hoja >= 0) e.quitarHoja(hoja);
            hoja = -1;
            e.postura(Pose.STANDING);
        }
    }

    // ---------------------------------------------------------------- Paso sombra

    /**
     * Se desvanece con humo (el cuerpo que se ve se esconde y suelta la katana), deja SOMBRAS
     * sombras siguiendo un arco a ARCO bloques alrededor de su presa, una cada "cada" ticks, y
     * reaparece a su espalda con un corte inmediato: su golpe a quien este a RADIO. Si ya estaba casi
     * a su espalda, la rodea por el otro lado para que el arco se vea.
     */
    static final class PasoSombra extends Tecnica {
        static final double MIN = 3, MAX = 14, ARCO = 3.5, DETRAS = 2, RADIO = 3.2, K = 1.0;
        static final int SOMBRAS = 3;
        final int cada, respiro;
        private Location centro;
        private double desde, giro;
        /** A donde mira desde que reaparece (a su presa en ese momento). */
        private float yawCorte;
        private boolean oculto;
        private long reaparece = Long.MAX_VALUE;
        private final Sombras sombras = new Sombras();

        PasoSombra(double ritmo) {
            super(Ataque.PASO);
            cada = Math.max(2, (int) Math.round(3 * ritmo));
            respiro = Math.max(4, (int) Math.round(8 * ritmo));
        }

        @Override
        boolean paso(Escena e) {
            if (t == 0) {
                Location pie = e.pie(), obj = e.objetivo();
                if (obj == null) return true;
                centro = obj.clone();
                Vector espalda = dir(obj.getYaw()).multiply(-1);
                desde = Math.atan2(pie.getZ() - obj.getZ(), pie.getX() - obj.getX());
                double d = ParcaAnomalia.normalizar(Math.atan2(espalda.getZ(), espalda.getX()) - desde);
                if (Math.abs(d) < Math.PI / 2) d -= (d < 0 ? -1 : 1) * 2 * Math.PI;
                giro = d;
                oculto = true;
                reaparece = (long) cada * (SOMBRAS + 1);
                e.katana(false);
                e.visible(false);
                e.particula(Compat.LARGE_SMOKE, pie.clone().add(0, 1, 0), 24, 0.4, 0.8, 0.4, 0.02, null);
                e.sonido(pie, "entity.enderman.teleport", 1.2f, 0.5f);
                return false;
            }
            if (oculto && t < reaparece) {
                if (t % cada == 0) rodear(e, (int) (t / cada));
            } else if (oculto) {
                oculto = false;
                reaparecer(e);
            } else {
                // 1.8.2: tras el corte se queda mirando a donde corto; no sigue a su presa a pasitos.
                e.mirar(yawCorte);
            }
            sombras.repasar(e, t);
            return !oculto && t >= reaparece + respiro && sombras.vacia();
        }

        /** La sombra "j" (1..SOMBRAS) del arco, mirando hacia donde va; el cuerpo, escondido, la sigue. */
        private void rodear(Escena e, int j) {
            double a = desde + giro * j / (SOMBRAS + 1);
            Location p = e.suelo(centro.clone().add(Math.cos(a) * ARCO, 0, Math.sin(a) * ARCO));
            double s = giro < 0 ? -1 : 1;
            float hacia = yaw(-Math.sin(a) * s, Math.cos(a) * s);
            sombras.poner(e, p, hacia, Pose.STANDING, t, false);
            e.particula(Compat.LARGE_SMOKE, p.clone().add(0, 1, 0), 8, 0.25, 0.6, 0.25, 0.01, null);
            Location h = e.hueco(p);
            if (h != null) {
                h.setYaw(hacia);
                h.setPitch(0);
                e.mover(h);
            }
        }

        /** Sale a DETRAS bloques de la espalda de su presa, a la vista, y corta. */
        private void reaparecer(Escena e) {
            Location obj = e.objetivo();
            if (obj == null) obj = centro;
            Location sitio = e.hueco(obj.clone().add(dir(obj.getYaw()).multiply(-DETRAS)));
            if (sitio != null) {
                sitio.setYaw(yaw(sitio, obj));
                sitio.setPitch(0);
                e.mover(sitio);
            }
            Location aqui = e.pie();
            yawCorte = yaw(aqui, obj);
            e.mirar(yawCorte);
            e.visible(true);
            e.katana(true);
            e.blandir();
            e.particula(Compat.LARGE_SMOKE, aqui.clone().add(0, 1, 0), 24, 0.4, 0.8, 0.4, 0.02, null);
            e.sonido(aqui, "entity.enderman.teleport", 1.2f, 0.5f);
            e.sonido(aqui, "entity.player.attack.sweep", 1.4f, 0.9f);
            barrido(e, aqui, 2.0);
            e.herirCerca(aqui, RADIO, K, false);
        }

        @Override
        void cortar(Escena e) {
            sombras.quitarTodas(e);
            oculto = false;
            e.visible(true);
            e.katana(true);
            e.postura(Pose.STANDING);
        }
    }

    // ------------------------------------------------------------------ Iaijutsu

    /**
     * Envaina 1,5 s de pie y completamente quieto (la mano vacia; 1.8.2: sin el balanceo de la
     * 1.8.1 ni seguir a su presa girando, que se veia dar vueltas despacio y a trompicones) dentro
     * de un anillo rojo de 4 bloques, con el sonido de la vaina y un aviso en la barra. Al soltarlo
     * la katana vuelve a la mano de golpe, destello, y un corte rapido (la hoja gira 90 grados): el
     * doble de su golpe a quien siga a menos de 4 bloques y no este agachado.
     */
    static final class Iaijutsu extends Tecnica {
        static final double RADIO = 4, K = 2.0;
        static final int CORTE = 3;
        final int envaina, respiro;
        /** A donde mira todo el ataque: a su presa al empezar. */
        private float yaw;
        private int hoja = -1;
        private long quitarEn = -1;
        private List<Location> anillo = List.of();

        Iaijutsu(double ritmo) {
            super(Ataque.IAIJUTSU);
            envaina = Math.max(12, (int) Math.round(30 * ritmo));
            respiro = Math.max(6, (int) Math.round(10 * ritmo));
        }

        @Override
        boolean paso(Escena e) {
            Location pie = e.pie();
            if (t == 0) {
                Location obj = e.objetivo();
                yaw = obj == null ? 0f : yaw(pie, obj);
                anillo = anillo(e, pie, RADIO, 36);
                e.postura(Pose.STANDING);
                e.katana(false);
                e.sonido(pie, "item.armor.equip_chain", 1.2f, 0.6f);
                e.avisar("Iaijutsu: agáchate o aléjate.", 16);
            }
            if (t < envaina) {
                e.mirar(yaw);
                if (t % 2 == 0) {
                    float tam = (float) (1.0 + 0.8 * t / envaina);
                    for (Location l : anillo) e.polvo(l, RGB_AVISO, tam);
                }
                if (t == envaina / 2) e.sonido(pie, "block.respawn_anchor.charge", 1.0f, 1.3f);
                // La hoja sale 2 ticks antes del corte: un display recien puesto no anima su primer giro.
                if (t == envaina - 2 && hoja < 0) hoja = e.hoja(yaw - 45f);
                return false;
            }
            e.mirar(yaw);
            if (t == envaina) {
                e.katana(true);
                e.blandir();
                e.sonido(pie, "item.armor.equip_netherite", 1.3f, 0.5f);
                e.sonido(pie, "block.chain.break", 1.2f, 0.8f);
                Location pecho = pie.clone().add(0, 1.2, 0);
                e.particula(Compat.FLASH, pecho, 1, 0, 0, 0, 0, Color.fromRGB(RGB_CRIMSON));
                e.particula(Compat.SONIC_BOOM, pecho, 1, 0, 0, 0, 0, null);
                barrido(e, pie, 2.4);
                if (hoja >= 0) e.hoja(hoja, yaw + 45f, CORTE);
                e.herirCerca(pie, RADIO, K, true);
                quitarEn = t + CORTE + 3;
            }
            if (hoja >= 0 && t >= quitarEn) {
                e.quitarHoja(hoja);
                hoja = -1;
            }
            return t >= envaina + CORTE + respiro && hoja < 0;
        }

        @Override
        void cortar(Escena e) {
            if (hoja >= 0) e.quitarHoja(hoja);
            hoja = -1;
            e.postura(Pose.STANDING);
            e.katana(true);
        }
    }

    // ---------------------------------------------------------------- Mil cortes

    /**
     * Solo en la fase 2. Pinta 0,5 s tres lineas rojas que se cruzan en su presa (a 120 grados una
     * de otra) y las recorre una tras otra: tres carreras a traves de ella, cada una con sus sombras
     * y su corte a K de su golpe. Entre carrera y carrera cruza al principio de la siguiente con una
     * acometida corta (tumbado, con SOMBRAS_CAMBIO sombras y sin herir a nadie), y tras la tercera
     * vuelve igual a donde empezo.
     *
     * 1.8.2: esos cambios de lado eran un desplazamiento de 6 ticks por el borde, de pie y girando
     * hacia su presa a cada paso: se veia lento y a saltos. Ahora es una carrera como las otras, y
     * todas van seguidas: tumbado de la primera a la ultima, sin ponerse de pie entre una y otra ni
     * repetir el arranque (el destello y el riptide salen una vez, al empezar).
     */
    static final class MilCortes extends Tecnica {
        static final double MIN = 3, MAX = 10, K = 0.6;
        static final int AVISO = 10, CORTES = 3, SOMBRAS = 3, SOMBRAS_CAMBIO = 2;
        final int porTick;
        private Location centro, inicio;
        private double radio;
        private final Vector[] dirs = new Vector[CORTES];
        private final List<List<Location>> lineas = new ArrayList<>();
        private int corte = -1;
        /** La carrera del corte en curso, o la acometida corta del cambio de lado (solo una a la vez). */
        private Carrera carrera, cambio;
        /** Sigue tumbado de la carrera anterior: la siguiente no vuelve a arrancar. */
        private boolean tumbado;
        private float yawAviso, yawFinal;
        private long finEn = -1;
        private final Sombras sombras = new Sombras();

        MilCortes(double ritmo) {
            super(Ataque.MIL);
            porTick = ritmo < 1 ? 4 : 3;
        }

        @Override
        boolean paso(Escena e) {
            if (t == 0) {
                Location pie = e.pie(), obj = e.objetivo();
                if (obj == null) return true;
                centro = obj.clone();
                inicio = pie.clone();
                yawAviso = yaw(pie, centro);
                radio = Math.max(4, Math.min(8, distPlano(pie, obj)));
                Vector d1 = plano(pie, obj, new Vector(0, 0, 1));
                for (int i = 0; i < CORTES; i++) {
                    dirs[i] = rotar(d1, 2 * Math.PI * i / CORTES);
                    List<Location> l = new ArrayList<>();
                    for (double s = -radio; s <= radio + 1e-9; s += 1) l.add(e.suelo(centro.clone().add(dirs[i].clone().multiply(s))));
                    lineas.add(l);
                }
                e.katana(true);
                e.sonido(pie, "item.trident.return", 1.2f, 0.5f);
            }
            if (t < AVISO) {
                e.mirar(yawAviso);
                if (t % 2 == 0) {
                    float tam = (float) (1.0 + 0.6 * t / AVISO);
                    for (List<Location> l : lineas) for (Location p : l) e.polvo(p.clone().add(0, 0.15, 0), RGB_AVISO, tam);
                }
                return false;
            }
            if (t == AVISO) empezarCorte(e, 0);
            if (carrera != null) {
                e.mirar(carrera.yaw);
                if (carrera.paso(e, sombras, t)) {
                    tumbado = carrera.sigue;
                    carrera = null;
                    empezarCambio(e);
                }
            } else if (cambio != null) {
                e.mirar(cambio.yaw);
                if (cambio.paso(e, sombras, t)) {
                    tumbado = cambio.sigue;
                    cambio = null;
                    llegar(e);
                }
            } else if (finEn >= 0) {
                e.mirar(yawFinal);
            }
            sombras.repasar(e, t);
            return finEn >= 0 && sombras.vacia() && t - finEn >= 2;
        }

        /** La carrera "i", desde donde este hasta el otro lado de su linea. */
        private void empezarCorte(Escena e, int i) {
            corte = i;
            Location pie = e.pie();
            Location fin = centro.clone().add(dirs[i].clone().multiply(radio));
            Vector d = plano(pie, fin, dirs[i]);
            List<Location> ruta = e.ruta(pie, d, Math.max(2, distPlano(pie, fin)));
            if (ruta.size() < 3) {
                empezarCambio(e);
                return;
            }
            carrera = new Carrera(ruta, d, porTick, K, SOMBRAS, -1);
            // Detras siempre va un cambio de lado: se queda tumbado.
            carrera.seguida = tumbado;
            carrera.sigue = true;
        }

        /**
         * Tras la carrera: una acometida corta hasta el principio de la siguiente, o de vuelta a
         * donde empezo. Si no hay sitio para correr (una pared), aparece alli directamente.
         */
        private void empezarCambio(Escena e) {
            Location pie = e.pie();
            Location destino = vuelta() ? inicio.clone() : centro.clone().add(dirs[corte + 1].clone().multiply(-radio));
            Vector d = plano(pie, destino, dirs[Math.max(0, corte)]);
            List<Location> ruta = e.ruta(pie, d, distPlano(pie, destino));
            if (ruta.size() < 3) {
                Location h = vuelta() ? inicio.clone() : e.hueco(destino);
                if (h != null) {
                    h.setYaw(yaw(h, centro));
                    h.setPitch(0);
                    e.mover(h);
                }
                llegar(e);
                return;
            }
            cambio = new Carrera(ruta, d, porTick, 0, SOMBRAS_CAMBIO, -1);
            // De pie solo al volver a donde empezo.
            cambio.seguida = tumbado;
            cambio.sigue = !vuelta();
        }

        /** Al acabar un cambio de lado: el corte siguiente o, tras el ultimo, quieto donde empezo. */
        private void llegar(Escena e) {
            if (!vuelta()) {
                empezarCorte(e, corte + 1);
                return;
            }
            // Si el ultimo cambio no pudo correr (una pared), se pone de pie aqui.
            if (tumbado) {
                e.postura(Pose.STANDING);
                tumbado = false;
            }
            // La carrera se queda a menos de medio bloque del sitio (va de 0,5 en 0,5): se ajusta.
            if (distPlano(e.pie(), inicio) > 1e-3) {
                Location l = inicio.clone();
                l.setYaw(yaw(l, centro));
                l.setPitch(0);
                e.mover(l);
            }
            yawFinal = yaw(inicio, centro);
            e.mirar(yawFinal);
            finEn = t;
        }

        private boolean vuelta() {
            return corte + 1 >= CORTES;
        }

        @Override
        void cortar(Escena e) {
            if (carrera != null) carrera.cortar(e);
            if (cambio != null) cambio.cortar(e);
            carrera = null;
            cambio = null;
            sombras.quitarTodas(e);
            e.postura(Pose.STANDING);
        }
    }

    // ------------------------------------------------------------ Sombras del clan

    /**
     * Solo en la fase 2, cada 25 s. Ambush se queda quieto, de pie y con la katana en la mano, y
     * aparecen CLONES clones a su imagen (su skin de la fase 2) a RADIO bloques de su presa, a 120
     * grados uno de otro, de pie y con la mano vacia; suenan a la vez. Uno cada ENTRE ticks, cada clon
     * desenvaina, marca su linea AVISO ticks y hace la acometida con sombras a traves de ella (K de
     * su golpe). Un golpe de cualquiera disipa un clon: humo, y su corte ya no llega. Al acabar, los
     * que queden vuelven hacia Ambush dejando una estela de sombras y se funden en el con un destello.
     *
     * 1.8.2: Ambush mira todo el rato a donde estaba su presa al empezar y los clones que esperan su
     * turno no se giran (antes la seguian a pasitos, girando despacio); la vuelta de los clones dura
     * CONVERGE = 7 ticks (antes 10).
     */
    static final class Clan extends Tecnica {
        static final int CLONES = 3, PREPARA = 16, ENTRE = 12, AVISO = 8, CONVERGE = 7, SOMBRAS = 3;
        static final double RADIO = 6, K = 0.6, ALCANCE = 20;
        static final String AVISO_TEXTO = "Sombras del clan: golpea a los clones para disiparlos.";
        final int porTick;
        private Location centro;
        private final List<Integer> ids = new ArrayList<>();
        private final Carrera[] carreras = new Carrera[CLONES];
        private final boolean[] hecho = new boolean[CLONES];
        private final Map<Integer, Location> origen = new LinkedHashMap<>();
        private long convergeDesde = -1, finEn = -1;
        /** A donde mira Ambush mientras espera: a su presa al empezar. */
        private float yawAmbush;
        private final Sombras sombras = new Sombras();

        Clan(double ritmo) {
            super(Ataque.CLAN);
            porTick = ritmo < 1 ? 4 : 3;
        }

        @Override
        boolean paso(Escena e) {
            Location pie = e.pie();
            if (t == 0) {
                Location obj = e.objetivo();
                if (obj == null) return true;
                centro = obj.clone();
                yawAmbush = yaw(pie, obj);
                e.postura(Pose.STANDING);
                e.katana(true);
                // Ninguno encima de el: el primero a 60 grados de su lado, los otros a 120 de ese.
                double base = Math.atan2(pie.getZ() - obj.getZ(), pie.getX() - obj.getX()) + Math.PI / CLONES;
                for (int i = 0; i < CLONES; i++) {
                    double a = base + 2 * Math.PI * i / CLONES;
                    Location p = e.hueco(obj.clone().add(Math.cos(a) * RADIO, 0, Math.sin(a) * RADIO));
                    if (p == null) continue;
                    int id = e.clon(p, yaw(p, obj));
                    if (id < 0) continue;
                    ids.add(id);
                    e.particula(Compat.LARGE_SMOKE, p.clone().add(0, 1, 0), 16, 0.3, 0.8, 0.3, 0.02, null);
                    e.sonido(p, "block.chain.break", 1.2f, 0.7f);
                }
                if (ids.isEmpty()) return true;
                e.avisar(AVISO_TEXTO, 24);
            }
            Location obj = e.objetivo();
            if (obj == null) obj = centro;
            e.mirar(yawAmbush);
            boolean todos = true;
            for (int j = 0; j < ids.size(); j++) todos &= turno(e, j, obj);
            if (todos && convergeDesde < 0) convergeDesde = t + 4;
            if (convergeDesde >= 0 && finEn < 0 && t >= convergeDesde) converger(e);
            sombras.repasar(e, t);
            return finEn >= 0 && sombras.vacia();
        }

        /** Lo que hace este tick el clon "j". True si ya ha hecho lo suyo (o lo han disipado). */
        private boolean turno(Escena e, int j, Location obj) {
            if (hecho[j]) return true;
            int id = ids.get(j);
            Location aqui = e.clonVivo(id) ? e.clonPie(id) : null;
            if (aqui == null) {
                // Disipado de un golpe: su corte ya no llega.
                hecho[j] = true;
                return true;
            }
            long s = PREPARA + (long) ENTRE * j;
            // Esperando su turno, de pie y quieto, mirando a donde estaba la presa al salir.
            if (t < s) return false;
            if (t == s) {
                e.clonKatana(id, true);
                e.clonBlandir(id);
                e.sonido(aqui, "item.trident.return", 1.2f, 0.6f);
                Vector d = plano(aqui, obj, plano(aqui, centro, new Vector(0, 0, 1)));
                List<Location> ruta = e.ruta(aqui, d, Math.max(6, Math.min(16, distPlano(aqui, obj) + 4)));
                if (ruta.size() < 3) {
                    hecho[j] = true;
                    return true;
                }
                carreras[j] = new Carrera(ruta, d, porTick, K, SOMBRAS, id);
                aqui.setYaw(carreras[j].yaw);
                aqui.setPitch(0);
                e.moverClon(id, aqui);
            }
            if (t < s + AVISO) {
                if ((t - s) % 2 == 0) pintarLinea(e, carreras[j].ruta, t - s, AVISO);
                return false;
            }
            if (carreras[j].paso(e, sombras, t)) hecho[j] = true;
            return hecho[j];
        }

        /** Los que quedan vuelven hacia Ambush en CONVERGE ticks, con estela, y se funden en el. */
        private void converger(Escena e) {
            long k = t - convergeDesde;
            Location destino = e.pie();
            if (k == 0) {
                for (int id : ids) {
                    if (!e.clonVivo(id)) continue;
                    Location l = e.clonPie(id);
                    if (l == null) continue;
                    origen.put(id, l);
                    e.clonPostura(id, Pose.STANDING);
                }
            }
            double f = Math.min(1, (k + 1) / (double) CONVERGE);
            for (Map.Entry<Integer, Location> o : origen.entrySet()) {
                int id = o.getKey();
                if (!e.clonVivo(id)) continue;
                Location desde = o.getValue();
                Location l = desde.clone().add(destino.toVector().subtract(desde.toVector()).multiply(f));
                l.setYaw(yaw(desde, destino));
                l.setPitch(0);
                e.moverClon(id, l);
                if (k == 2 || k == 4) sombras.poner(e, l, l.getYaw(), Pose.STANDING, t, false);
            }
            if (f < 1) return;
            boolean alguno = false;
            for (int id : ids) {
                alguno |= e.clonVivo(id);
                e.quitarClon(id);
            }
            if (alguno) {
                e.particula(Compat.FLASH, destino.clone().add(0, 1.2, 0), 1, 0, 0, 0, 0, Color.fromRGB(RGB_CRIMSON));
                e.sonido(destino, "entity.illusioner.mirror_move", 1.2f, 0.6f);
            }
            finEn = t;
        }

        @Override
        void cortar(Escena e) {
            for (int id : ids) e.quitarClon(id);
            sombras.quitarTodas(e);
            e.postura(Pose.STANDING);
            e.katana(true);
        }
    }

    // ================================================================ los dos relojes

    /**
     * Cada tick: el ataque en curso, el cuerpo que se ve (pegado al que pelea) y las hojas. Asi el
     * cliente interpola cada posicion y nada salta, salvo "aparece a tu espalda", que va con humo.
     */
    private void animar() {
        if (estado == Estado.FIN || cuerpo == null || !cuerpo.isValid() || cuerpo.isDead()) return;
        ticks++;
        yawFijo = null;
        if (actual != null) {
            Tecnica tec = actual;
            boolean fin;
            try {
                fin = tec.paso(escena);
            } catch (Throwable t) {
                hc.plugin().getLogger().warning("[Calamity] Fallo en " + (tec.ataque == null ? "la entrada" : tec.rotulo())
                        + " de Ambush: " + t);
                tec.cortar(escena);
                fin = true;
            }
            tec.t++;
            if (fin && actual == tec) acabar(tec);
        }
        seguirCascara();
    }

    /** Cada 2 ticks, desde Amenazas: a quien va, que hace, la barra, si su presa sigue y el tiempo. */
    @Override
    public void run() {
        if (estado == Estado.FIN) {
            hc.amenazas().quitarPelea(this);
            return;
        }
        if (cuerpo == null || !cuerpo.isValid() || cuerpo.isDead()) {
            // Muerto lo cierra alMorir (EntityDeathEvent); aqui solo llega si alguien lo ha borrado.
            if (!pagada) irse("desaparece", null);
            return;
        }
        boolean segundo = ++vueltas % 10 == 0;
        if (segundo) refrescarBarra();
        else if (barra != null) barra.progress((float) Math.max(0, Math.min(1, Amenazas.fraccion(cuerpo))));
        if (estado != Estado.PELEA) return;

        if (ticks - inicioPelea >= DURACION_TICKS) {
            irse("tiempo", ComandoCalamity.mensaje("Ambush se retira."));
            return;
        }
        if (segundo && !revisarPresa()) return;
        if (fase == 1 && tocaFase2(Amenazas.fraccion(cuerpo), a.fase2, fase)) {
            fase = 2;
            cortarTecnica();
            empezar(new Transformacion(a.skin2));
            // Las Sombras del clan: las primeras a PRIMER_CLAN de transformarse y luego cada 25 s.
            lista.put(Ataque.CLAN, ticks + PRIMER_CLAN);
            hc.plugin().bitacora().anotar("ambush", "fase", "2", presaNombre, "N " + nivel);
            return;
        }
        Player obj = objetivo();
        // Su presa en la zona spawn: la espera fuera, quieto. A quien le pegue mientras, le responde.
        boolean espera = obj != null && presa != null && obj.getUniqueId().equals(presa) && hc.enSpawn(obj);
        if (espera != quieto) esperar(espera);
        if (quieto || actual != null || obj == null) return;
        if (segundo) {
            cuerpo.setTarget(obj);
            double lejos = hc.cfg().getDouble("minijefes.distancia-maxima", 60);
            boolean otroMundo = obj.getWorld() != cuerpo.getWorld();
            if (otroMundo || obj.getLocation().distanceSquared(cuerpo.getLocation()) > lejos * lejos
                    || ticks - ultimoGolpe >= ATASCO_TICKS) {
                aparecerDetras(obj);
                return;
            }
        }
        dirigir(obj);
    }

    /**
     * Cada segundo: la presa sigue siendo presa. Si se desconecta, sale de Calamity, muere o deja de
     * contar, se va sin botin (el contrato queda consumido). En la zona spawn espera fuera, quieto.
     */
    private boolean revisarPresa() {
        if (prueba) return true;
        Player p = hc.plugin().getServer().getPlayer(presa);
        if (p == null || !p.isOnline()) {
            irse("desconexion", null);
            return false;
        }
        if (p.isDead()) {
            irse("cumplido", null);
            return false;
        }
        if (!hc.esHardcore(p) || p.getWorld() != cuerpo.getWorld() || !hc.cuenta(p)) {
            irse("salio", null);
            return false;
        }
        if (!avisoSpawn && hc.enSpawn(p)) {
            avisoSpawn = true;
            p.sendMessage(ComandoCalamity.mensaje("Ambush no entra en el spawn: te espera fuera."));
        }
        return true;
    }

    /** Se queda quieto fuera del spawn (su presa esta dentro) o vuelve a por ella cuando sale. */
    private void esperar(boolean si) {
        quieto = si;
        if (si) {
            cortarTecnica();
            cuerpo.setAI(false);
            cuerpo.setTarget(null);
        } else {
            cuerpo.setAI(true);
            velocidad();
            ultimoGolpe = ticks;
        }
    }

    // ================================================================ decidir

    /**
     * Entre ataque y ataque persigue y pega; pasado el respiro, en la fase 2 tocan antes que nada
     * las Sombras del clan si ya es la hora, y si no elige segun la distancia.
     */
    private void dirigir(Player obj) {
        if (ticks < respiroHasta) return;
        double dx = obj.getLocation().getX() - cuerpo.getLocation().getX();
        double dz = obj.getLocation().getZ() - cuerpo.getLocation().getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (tocaClan(fase, ticks, lista.getOrDefault(Ataque.CLAN, Long.MAX_VALUE)) && dist <= Clan.ALCANCE) {
            empezar(nueva(Ataque.CLAN, ritmo()));
            return;
        }
        Ataque x = elegir(dist, fase, ticks, lista, ultima, ThreadLocalRandom.current().nextDouble());
        if (x != null) empezar(nueva(x, ritmo()));
    }

    /** Si tocan las Sombras del clan: solo en la fase 2 y pasada su hora ("listo"). */
    static boolean tocaClan(int fase, long ticks, long listo) {
        return fase >= 2 && ticks >= listo;
    }

    /**
     * El ataque que toca a "dist" bloques de su objetivo, sorteado por peso con "azar" (0-1), o null
     * si no hay ninguno listo. En la fase 1: acometida, tajo doble, paso sombra e iaijutsu; en la 2,
     * ademas mil cortes. Las Sombras del clan no se sortean: van por reloj (tocaClan).
     */
    static Ataque elegir(double dist, int fase, long ticks, Map<Ataque, Long> lista, Ataque ultima, double azar) {
        EnumMap<Ataque, Double> pesos = new EnumMap<>(Ataque.class);
        double total = 0;
        for (Ataque x : Ataque.values()) {
            if (x == Ataque.CLAN || fase < x.fase || ticks < lista.getOrDefault(x, 0L)) continue;
            double p = x.peso;
            switch (x) {
                case TAJO -> {
                    if (dist > TajoDoble.RADIO - 0.3) continue;
                    if (dist < 2.5) p *= 2;
                }
                case IAIJUTSU -> {
                    if (dist > Iaijutsu.RADIO + 0.5) continue;
                }
                case ACOMETIDA -> {
                    if (dist < 5 || dist > 16) continue;
                    if (dist >= 8) p *= 2;
                }
                case PASO -> {
                    if (dist < PasoSombra.MIN || dist > PasoSombra.MAX) continue;
                }
                case MIL -> {
                    if (dist < MilCortes.MIN || dist > MilCortes.MAX) continue;
                }
                default -> {
                }
            }
            if (x == ultima) p *= 0.35;
            if (p <= 0) continue;
            pesos.put(x, p);
            total += p;
        }
        if (pesos.isEmpty() || total <= 0) return null;
        double tirada = Math.max(0, Math.min(0.999999, azar)) * total;
        for (Map.Entry<Ataque, Double> e : pesos.entrySet()) {
            tirada -= e.getValue();
            if (tirada < 0) return e.getKey();
        }
        return pesos.keySet().iterator().next();
    }

    private double ritmo() {
        return fase >= 2 ? RITMO_FASE2 : 1.0;
    }

    /** Ticks hasta que vuelve a usarlo: en la fase 2 el iaijutsu espera la mitad. */
    private int espera(Ataque x) {
        return fase >= 2 && x == Ataque.IAIJUTSU ? x.espera / 2 : x.espera;
    }

    private void empezar(Tecnica tec) {
        actual = tec;
        if (cuerpo != null && cuerpo.isValid()) {
            cuerpo.setAI(false);
            cuerpo.setTarget(null);
        }
        if (tec.ataque != null) {
            lista.put(tec.ataque, ticks + espera(tec.ataque));
            ultima = tec.ataque;
        }
        nombreBarra();
    }

    private void acabar(Tecnica tec) {
        actual = null;
        if (cuerpo == null || !cuerpo.isValid()) return;
        if (tec instanceof Entrada) {
            // Acaba la entrada: ya se puede pelear.
            estado = Estado.PELEA;
            inicioPelea = ticks;
            ultimoGolpe = ticks;
            respiroHasta = ticks + 16;
            cuerpo.setInvulnerable(false);
        } else {
            respiroHasta = ticks + (long) Math.round((14 + ThreadLocalRandom.current().nextInt(11)) * ritmo());
        }
        if (!quieto) {
            cuerpo.setAI(true);
            velocidad();
        }
        nombreBarra();
    }

    /** Corta el ataque en curso sin respiro (cambio de fase, la presa en el spawn, el fin). */
    private void cortarTecnica() {
        if (actual == null) return;
        Tecnica tec = actual;
        actual = null;
        try {
            tec.cortar(escena);
        } catch (Throwable ignorado) {
            // Lo que no se pudo cortar lo retira limpiar() al acabar.
        }
        if (tec instanceof Entrada && estado == Estado.APARECE && cuerpo != null && cuerpo.isValid()) {
            estado = Estado.PELEA;
            inicioPelea = ticks;
            ultimoGolpe = ticks;
            cuerpo.setInvulnerable(false);
        }
        nombreBarra();
    }

    /** /anomaly test (AmbushEdm): suelta ese ataque ya. False si no esta peleando o no es de su fase. */
    boolean forzar(Ataque x) {
        if (estado != Estado.PELEA || x == null || quieto || fase < x.fase) return false;
        cortarTecnica();
        empezar(nueva(x, ritmo()));
        return true;
    }

    /**
     * "Aparece a tu espalda": la unica vez que salta. Humo y un zumbido donde estaba y donde
     * aparece, a 3 bloques por detras de donde mira su objetivo (fuera de la zona spawn).
     */
    private void aparecerDetras(Player obj) {
        Location destino = Parca.sitioDetras(obj, 3);
        if (destino.getWorld() != obj.getWorld() || hc.enSpawn(destino)) return;
        World w = cuerpo.getWorld();
        Location desde = cuerpo.getLocation();
        Compat.spawn(w, Compat.LARGE_SMOKE, desde.clone().add(0, 1, 0), 20, 0.4, 0.8, 0.4, 0.02);
        Compat.sound(w, desde, "entity.illusioner.mirror_move", 1.2f, 0.8f);
        hc.amenazas().teleportar(cuerpo, destino);
        seguirCascara();
        World w2 = destino.getWorld();
        Compat.spawn(w2, Compat.LARGE_SMOKE, destino.clone().add(0, 1, 0), 20, 0.4, 0.8, 0.4, 0.02);
        Compat.sound(w2, destino, "entity.illusioner.mirror_move", 1.2f, 0.7f);
        ultimoGolpe = ticks;
        hc.plugin().bitacora().anotar("ambush", "a-la-espalda", presaNombre);
    }

    private void velocidad() {
        if (cuerpo != null) Compat.setAttribute(cuerpo, "movement_speed", VELOCIDAD);
    }

    // ================================================================ objetivo

    /** A quien va: quien le ha pegado hace poco (si sigue cerca) o su presa. En la de prueba, el mas cercano. */
    Player objetivo() {
        if (cuerpo == null) return null;
        World w = cuerpo.getWorld();
        Player r = respuesta == null ? null : hc.plugin().getServer().getPlayer(respuesta);
        if (r != null && ticks < respuestaHasta && r.getWorld() == w && Fx.isFightable(r) && !hc.enSpawn(r)
                && r.getLocation().distanceSquared(cuerpo.getLocation()) <= 24 * 24) {
            return r;
        }
        if (prueba) return Fx.nearest(cuerpo.getLocation(), 32);
        Player p = hc.plugin().getServer().getPlayer(presa);
        return p != null && p.getWorld() == w && Fx.isFightable(p) ? p : null;
    }

    /** Si puede apuntar a esa entidad (EntityTargetEvent): solo a su objetivo de ahora. */
    boolean puedeApuntar(Entity e) {
        Player obj = objetivo();
        return obj != null && e != null && obj.getUniqueId().equals(e.getUniqueId()) && !quieto;
    }

    /** Un jugador le ha pegado: si no es su presa, le responde un rato. */
    void golpeadaPor(Player j) {
        if (j == null || j.getUniqueId().equals(presa)) return;
        respuesta = j.getUniqueId();
        respuestaHasta = ticks + RESPUESTA_TICKS;
    }

    /** Ha golpeado a alguien (a mano o con un ataque): el reloj del atasco y, a mano, el brazo. */
    void haGolpeado() {
        ultimoGolpe = ticks;
        if (actual == null) blandir();
    }

    private void blandir() {
        if (cuerpo != null && cuerpo.isValid()) cuerpo.swingMainHand();
        npc.blandir();
    }

    /** Le han hecho dano de verdad: el cuerpo que se ve se estremece. */
    void dolor() {
        npc.dolor();
    }

    boolean esCuerpo(Entity e) {
        return e != null && cuerpo != null && cuerpo.getUniqueId().equals(e.getUniqueId());
    }

    boolean esCascara(Entity e) {
        return npc.es(e);
    }

    boolean esSombra(Entity e) {
        if (e == null || sombras.isEmpty()) return false;
        for (Mannequin m : sombras.values()) if (m.getUniqueId().equals(e.getUniqueId())) return true;
        return false;
    }

    /** Si es uno de sus clones de las Sombras del clan. */
    boolean esClon(Entity e) {
        if (e == null || clones.isEmpty()) return false;
        for (Mannequin m : clones.values()) if (m.getUniqueId().equals(e.getUniqueId())) return true;
        return false;
    }

    /**
     * Un jugador ha golpeado a uno de sus clones (Ambush.onDanoCascara): se disipa en humo y el
     * ataque ya no lo encuentra, asi que su corte no llega.
     */
    void disiparClon(Entity e) {
        if (e == null) return;
        Integer id = null;
        for (Map.Entry<Integer, Mannequin> c : clones.entrySet()) {
            if (c.getValue().getUniqueId().equals(e.getUniqueId())) {
                id = c.getKey();
                break;
            }
        }
        if (id == null) return;
        Mannequin m = clones.remove(id);
        if (m != null && m.isValid()) {
            Location l = m.getLocation();
            Compat.spawn(l.getWorld(), Compat.LARGE_SMOKE, l.clone().add(0, 1, 0), 24, 0.35, 0.8, 0.35, 0.02);
            Compat.sound(l.getWorld(), l, "entity.illusioner.mirror_move", 1.0f, 1.3f);
        }
        Fx.safeRemove(m);
    }

    /** Si esta pelea es de ese mundo (para retirarla al descargarlo). */
    boolean enMundo(World w) {
        return w != null && cuerpo != null && cuerpo.getWorld() == w;
    }

    int fase() {
        return fase;
    }

    long segundosDePelea() {
        return estado == Estado.PELEA ? (ticks - inicioPelea) / 20 : 0;
    }

    /** Para /calamidad ambush info: fase, vida y tiempo. */
    String estadoTexto() {
        if (cuerpo == null) return "sin cuerpo";
        long s = segundosDePelea();
        return (estado == Estado.APARECE ? "apareciendo" : quieto ? "espera fuera del spawn" : actual != null ? actual.rotulo() : "pelea")
                + " | fase " + fase + " | vida " + Math.round(Amenazas.fraccion(cuerpo) * 100) + " % | N " + nivel
                + " | " + s / 60 + ":" + String.format(Locale.ROOT, "%02d", s % 60) + " de " + Ambush.MINUTOS + ":00";
    }

    // ================================================================ cuerpo y barra

    /** El cuerpo que se ve se pega al que pelea: mirando a donde diga el ataque o a los ojos de su objetivo. */
    private void seguirCascara() {
        if (!conNpc || cuerpo == null) return;
        Location l = cuerpo.getLocation();
        if (yawFijo != null) {
            l.setYaw(yawFijo);
            l.setPitch(0);
        } else {
            Player obj = objetivo();
            if (obj != null && obj.getWorld() == l.getWorld()) {
                Vector v = obj.getEyeLocation().toVector().subtract(cuerpo.getEyeLocation().toVector());
                if (v.lengthSquared() > 1e-4) l.setDirection(v);
            }
        }
        if (!npc.seguir(l)) {
            conNpc = false;
            volverAEsqueleto();
        }
    }

    private Component tituloBarra() {
        Component resto = actual != null && actual.ataque != null ? Component.text(actual.rotulo(), Paleta.AVISO)
                : Component.text("Nv. " + nivel, Paleta.TEXTO);
        return Paleta.ambush("Ambush").append(Component.text(" · ", Paleta.SEPARADOR)).append(resto);
    }

    private void nombreBarra() {
        if (barra != null) barra.name(tituloBarra());
    }

    /** Para su presa y quien este a <= 48; se recalcula cada segundo. */
    private void refrescarBarra() {
        if (cuerpo == null) return;
        if (barra == null) {
            barra = BossBar.bossBar(tituloBarra(), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        }
        barra.progress((float) Math.max(0, Math.min(1, Amenazas.fraccion(cuerpo))));
        Set<UUID> ahora = new HashSet<>();
        for (Player p : Fx.viewersNear(cuerpo.getLocation(), 48)) ahora.add(p.getUniqueId());
        Player p0 = presa == null ? null : hc.plugin().getServer().getPlayer(presa);
        if (p0 != null && p0.getWorld() == cuerpo.getWorld()) ahora.add(p0.getUniqueId());
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

    // ================================================================ fin

    /**
     * Ha caido: el botin lo reparte el gestor con el dano logico (se lee aqui, en su
     * EntityDeathEvent). El cuerpo que se ve muere como cualquier otro y cae ceniza 2 s.
     */
    void alMorir() {
        if (pagada || estado == Estado.FIN) return;
        pagada = true;
        Map<UUID, Double> dano = hc.amenazas().danoLogico(cuerpo);
        double vida = hc.amenazas().vidaLogicaMaxima(cuerpo);
        long segundos = (System.currentTimeMillis() - nacio) / 1000;
        Location l = cuerpo.getLocation();
        cortarTecnica();
        Mannequin caido = conNpc ? npc.soltar() : null;
        gestor.caer(caido, l);
        hc.seguro("ambush", () -> gestor.botin(this, dano, vida, segundos));
        limpiar();
    }

    /**
     * Se va sin botin: la presa ha salido, se ha desconectado o ha muerto, se acabo el tiempo o se
     * descarga su mundo. Humo y el sonido de envainar.
     *
     * @param aviso lo que leen su presa y quien este a <= 48 (solo al retirarse por tiempo), o null
     */
    void irse(String motivo, Component aviso) {
        if (estado == Estado.FIN) return;
        cortarTecnica();
        if (cuerpo != null && cuerpo.isValid()) {
            World w = cuerpo.getWorld();
            Location l = cuerpo.getLocation().add(0, 1, 0);
            Compat.spawn(w, Compat.LARGE_SMOKE, l, 40, 0.5, 1, 0.5, 0.02);
            Compat.spawn(w, Compat.CLOUD, l, 16, 0.4, 0.8, 0.4, 0.02);
            Compat.sound(w, l, "item.armor.equip_chain", 1.2f, 0.6f);
            if (aviso != null) {
                Set<Player> lectores = new HashSet<>(Fx.viewersNear(l, 48));
                Player p = presa == null ? null : hc.plugin().getServer().getPlayer(presa);
                if (p != null) lectores.add(p);
                for (Player o : lectores) o.sendMessage(aviso);
            }
        }
        hc.plugin().bitacora().anotar("ambush", "se-va", presaNombre, motivo, (System.currentTimeMillis() - nacio) / 1000 + " s");
        limpiar();
    }

    /**
     * Retira todo lo suyo (idempotente): ataque, animador, barra, hojas, sombras, clones, maniqui y
     * cuerpo. Pasan por aqui todos los finales: muere, se retira, se para el plugin (Ambush.parar)
     * o se descarga su mundo (Ambush.onDescargaMundo).
     */
    void limpiar() {
        Tecnica tec = actual;
        actual = null;
        retirar(tec, escena);
        if (animador != null) {
            animador.cancel();
            animador = null;
        }
        quitarBarra();
        npc.quitar();
        Fx.safeRemove(cuerpo);
        if (hc.amenazas() != null) hc.amenazas().quitarPelea(this);
        estado = Estado.FIN;
    }

    // ================================================================ la escena de verdad

    private final class EscenaReal implements Escena {

        @Override
        public Location pie() {
            return cuerpo.getLocation();
        }

        @Override
        public Location objetivo() {
            Player o = PeleaAmbush.this.objetivo();
            return o == null || o.getWorld() != cuerpo.getWorld() ? null : o.getLocation();
        }

        @Override
        public void mover(Location l) {
            // Ambush no entra en el spawn: ningun ataque (paso sombra, mil cortes...) lo mete dentro.
            if (hc.enSpawn(l)) return;
            hc.amenazas().teleportar(cuerpo, l);
        }

        @Override
        public void mirar(float yaw) {
            yawFijo = yaw;
        }

        @Override
        public void postura(Pose p) {
            npc.postura(p);
        }

        @Override
        public void katana(boolean enLaMano) {
            if (conNpc) {
                npc.empunar(enLaMano ? katana : null);
                return;
            }
            EntityEquipment eq = cuerpo.getEquipment();
            if (eq != null) eq.setItemInMainHand(enLaMano ? katana.clone() : null);
        }

        @Override
        public void blandir() {
            PeleaAmbush.this.blandir();
        }

        @Override
        public void visible(boolean si) {
            // Con maniqui se esconde el maniqui (el esqueleto ya es invisible siempre); sin el, el esqueleto.
            Mannequin m = conNpc ? npc.entidad() : null;
            if (m != null) m.setInvisible(!si);
            else if (!conNpc && cuerpo != null && cuerpo.isValid()) cuerpo.setInvisible(!si);
        }

        private Location pecho() {
            Location c = cuerpo.getLocation().add(0, PECHO, 0);
            c.setYaw(0);
            c.setPitch(0);
            return c;
        }

        @Override
        public float tamanoHoja() {
            return escalaHoja(fase);
        }

        @Override
        public int hojaLadeada(float frente, float angulo, float inclina) {
            if (hojas.size() >= MAX_HOJAS) return -1;
            Location c = pecho();
            float esc = escalaHoja(fase);
            ItemDisplay d;
            try {
                d = c.getWorld().spawn(c, ItemDisplay.class, e -> {
                    e.setItemStack(katana.clone());
                    e.setBillboard(Display.Billboard.FIXED);
                    e.setBrightness(new Display.Brightness(15, 15));
                    e.setPersistent(false);
                    e.setViewRange(1.5f);
                    e.setTeleportDuration(2);
                    e.setInterpolationDelay(0);
                    e.setInterpolationDuration(0);
                    e.setTransformation(hojaEn(frente, angulo, inclina, radioHoja(esc), esc));
                });
            } catch (Throwable t) {
                return -1;
            }
            int id = ++siguienteId;
            hojas.put(id, d);
            return id;
        }

        @Override
        public void hojaLadeada(int id, float frente, float angulo, float inclina, int ticks) {
            ItemDisplay d = hojas.get(id);
            if (d == null || !d.isValid()) return;
            Location c = pecho();
            if (d.getLocation().distanceSquared(c) > 0.01) d.teleport(c);
            float esc = escalaHoja(fase);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(Math.max(1, ticks));
            d.setTransformation(hojaEn(frente, angulo, inclina, radioHoja(esc), esc));
        }

        @Override
        public void quitarHoja(int id) {
            Fx.safeRemove(hojas.remove(id));
        }

        /** Lo comun de una sombra y un clon: la marca del cuerpo, que no se guarde, sin gravedad ni choque, su skin y su tamano. */
        private void vestir(Mannequin s, String dueno) {
            // Con la marca del cuerpo: nadie la toca y su dano se cancela (Parca y Ambush.onDanoCascara, onTocarCascara).
            s.getPersistentDataContainer().set(Marcas.CASCARA, PersistentDataType.STRING, dueno);
            s.setPersistent(false);
            s.setGravity(false);
            s.setCollidable(false);
            s.setSilent(true);
            s.setImmovable(true);
            s.setCustomNameVisible(false);
            try {
                s.setDescription(Component.empty());
            } catch (Throwable ignorado) {
                // Sin descripcion editable se ve la linea "NPC" un instante.
            }
            s.setProfile(npc.perfil());
            CuerpoNpc.sinCapa(s);
            Compat.setAttribute(s, "scale", npc.escala());
        }

        @Override
        public int sombra(Location l, float yaw, Pose pose) {
            if (!conNpc || npc.perfil() == null || sombras.size() >= MAX_SOMBRAS || cuerpo == null) return -1;
            Location en = l.clone();
            en.setYaw(yaw);
            en.setPitch(0);
            String dueno = cuerpo.getUniqueId().toString();
            Mannequin m;
            try {
                m = en.getWorld().spawn(en, Mannequin.class, s -> {
                    vestir(s, dueno);
                    s.setInvulnerable(true);
                    s.getEquipment().setItemInMainHand(katana.clone());
                    if (CuerpoNpc.POSTURAS.contains(pose)) {
                        try {
                            s.setPose(pose, true);
                        } catch (Throwable ignorado) {
                            // De pie tambien se lee la estela.
                        }
                    }
                });
            } catch (Throwable t) {
                return -1;
            }
            int id = ++siguienteId;
            sombras.put(id, m);
            return id;
        }

        @Override
        public void quitarSombra(int id) {
            Fx.safeRemove(sombras.remove(id));
        }

        @Override
        public int clon(Location l, float yaw) {
            if (!conNpc || npc.perfil() == null || clones.size() >= MAX_CLONES || cuerpo == null || l.getWorld() == null) return -1;
            Location en = l.clone();
            en.setYaw(yaw);
            en.setPitch(0);
            String dueno = cuerpo.getUniqueId().toString();
            Mannequin m;
            try {
                // No invulnerable: el golpe tiene que llegar al evento para disiparlo (el dano se cancela siempre).
                m = en.getWorld().spawn(en, Mannequin.class, s -> {
                    vestir(s, dueno);
                    s.getEquipment().setItemInMainHand(null);
                });
            } catch (Throwable t) {
                return -1;
            }
            int id = ++siguienteId;
            clones.put(id, m);
            return id;
        }

        private Mannequin clonDe(int id) {
            Mannequin m = clones.get(id);
            return m != null && m.isValid() && !m.isDead() ? m : null;
        }

        @Override
        public boolean clonVivo(int id) {
            return clonDe(id) != null;
        }

        @Override
        public Location clonPie(int id) {
            Mannequin m = clonDe(id);
            return m == null ? null : m.getLocation();
        }

        @Override
        public void moverClon(int id, Location l) {
            Mannequin m = clonDe(id);
            if (m != null && l != null && l.getWorld() == m.getWorld()) m.teleport(l);
        }

        @Override
        public void clonPostura(int id, Pose p) {
            Mannequin m = clonDe(id);
            if (m == null || p == null || !CuerpoNpc.POSTURAS.contains(p)) return;
            try {
                m.setPose(p, p != Pose.STANDING);
            } catch (Throwable ignorado) {
                // Una postura que el maniqui no admite: se queda como estaba.
            }
        }

        @Override
        public void clonKatana(int id, boolean enLaMano) {
            Mannequin m = clonDe(id);
            if (m != null) m.getEquipment().setItemInMainHand(enLaMano ? katana.clone() : null);
        }

        @Override
        public void clonBlandir(int id) {
            Mannequin m = clonDe(id);
            if (m != null) m.swingMainHand();
        }

        @Override
        public void quitarClon(int id) {
            Fx.safeRemove(clones.remove(id));
        }

        @Override
        public void retirarTodo() {
            for (ItemDisplay d : hojas.values()) Fx.safeRemove(d);
            hojas.clear();
            for (Mannequin m : sombras.values()) Fx.safeRemove(m);
            sombras.clear();
            for (Mannequin m : clones.values()) Fx.safeRemove(m);
            clones.clear();
            visible(true);
        }

        @Override
        public List<Location> ruta(Location desde, Vector dir, double largo) {
            List<Location> r = new ArrayList<>();
            ParcaAnomalia.trazar(desde, dir, largo, r);
            return r;
        }

        @Override
        public Location suelo(Location l) {
            return Fx.ground(l, 4);
        }

        @Override
        public Location hueco(Location l) {
            if (l == null || l.getWorld() == null) return null;
            Location s = Fx.ground(l.clone(), 4);
            if (s != null && Math.abs(s.getY() - l.getY()) <= 3 && Parca.libre(s, 2)) return s;
            return Parca.huecoCerca(l, 2);
        }

        @Override
        public void polvo(Location l, int rgb, float tam) {
            Compat.spawn(l.getWorld(), Compat.DUST, l, 1, 0, 0, 0, 0, Compat.dust(rgb, tam));
        }

        @Override
        public void particula(Particle p, Location l, int n, double dx, double dy, double dz, double v, Object datos) {
            if (datos == null) Compat.spawn(l.getWorld(), p, l, n, dx, dy, dz, v);
            else Compat.spawn(l.getWorld(), p, l, n, dx, dy, dz, v, datos);
        }

        @Override
        public void sonido(Location l, String clave, float volumen, float tono) {
            Compat.sound(l.getWorld(), l, clave, volumen, tono);
        }

        @Override
        public int herirCerca(Location centro, double radio, double k, boolean perdonaAgachados) {
            int n = 0;
            for (Player v : Fx.playersNear(centro, radio + 1)) {
                Location lv = v.getLocation();
                if (Math.abs(lv.getY() - centro.getY()) > 2.5) continue;
                double dx = lv.getX() - centro.getX(), dz = lv.getZ() - centro.getZ();
                if (dx * dx + dz * dz > radio * radio) continue;
                if (perdonaAgachados && v.isSneaking()) continue;
                if (hc.enSpawn(v)) continue;
                golpear(v, k);
                n++;
            }
            return n;
        }

        @Override
        public int herirTramo(Location a, Location b, double ancho, double k, Set<UUID> tocados, Vector dir) {
            int n = 0;
            Location medio = a.clone().add(b.toVector().subtract(a.toVector()).multiply(0.5));
            double alcance = a.toVector().distance(b.toVector()) / 2 + ancho + 1;
            for (Player v : Fx.playersNear(medio, alcance)) {
                if (tocados.contains(v.getUniqueId())) continue;
                Location lv = v.getLocation();
                if (Math.abs(lv.getY() - b.getY()) > 2.5) continue;
                if (ParcaAnomalia.distanciaASegmento(lv.getX(), lv.getZ(), a.getX(), a.getZ(), b.getX(), b.getZ()) > ancho) continue;
                if (hc.enSpawn(v)) continue;
                tocados.add(v.getUniqueId());
                golpear(v, k);
                Vector lado = new Vector(-dir.getZ(), 0, dir.getX());
                if (lado.dot(lv.toVector().subtract(b.toVector())) < 0) lado.multiply(-1);
                try {
                    v.setVelocity(v.getVelocity().add(lado.multiply(0.5).add(dir.clone().multiply(0.25)).setY(0.1)));
                } catch (Throwable ignorado) {
                    // Sin empujon el golpe ya ha entrado.
                }
                n++;
            }
            return n;
        }

        @Override
        public void sacudir(Location desde) {
            Player o = PeleaAmbush.this.objetivo();
            if (o == null || o.getWorld() != desde.getWorld() || !Fx.isFightable(o) || hc.enSpawn(o)) return;
            if (o.getLocation().distanceSquared(desde) > 6 * 6) return;
            try {
                o.playHurtAnimation(PeleaParca.ladoDe(o, desde));
            } catch (Throwable ignorado) {
                // Sin temblor el corte se ha visto igual.
            }
        }

        @Override
        public void avisar(String texto, double radio) {
            Component c = Component.text(texto, Paleta.AVISO);
            for (Player p : Fx.viewersNear(cuerpo.getLocation(), radio)) hc.barra().aviso(p, c, 2);
        }

        @Override
        public void avisarPresa(String texto) {
            Player p = presa == null ? null : hc.plugin().getServer().getPlayer(presa);
            if (p != null) hc.barra().aviso(p, Component.text(texto, Paleta.AMBUSH), 3);
        }

        @Override
        public void cambiarSkin(String cuenta) {
            if (conNpc) npc.cambiarSkin(cuenta);
        }
    }

    /** Un golpe de un ataque (la armadura cuenta), atribuido al cuerpo: si mata, lo ha matado Ambush. */
    private void golpear(Player v, double k) {
        if (v == null || !Fx.isFightable(v) || cuerpo == null) return;
        try {
            v.damage(golpe * k, cuerpo);
        } catch (Throwable ignorado) {
            // Un plugin que revienta en el evento de dano no para la pelea.
        }
        ultimoGolpe = ticks;
    }

    // ================================================================ autotest

    /**
     * Una escena de mentira: cuenta posturas, hojas, sombras y clones, y no toca el mundo. Su
     * objetivo no se mueve; herirCerca y herirTramo apuntan un golpe (su k) si le alcanzan.
     */
    static final class EscenaPrueba implements Escena {
        static final UUID PRESA = new UUID(0, 42);
        Location pie = new Location(null, 0, 64, 0);
        Location objetivo;
        Pose postura = Pose.STANDING;
        boolean katana = true, visible = true;
        final Set<Integer> hojas = new HashSet<>();
        final Set<Integer> sombras = new HashSet<>();
        final List<Location> sombrasEn = new ArrayList<>();
        final Map<Integer, Location> clones = new LinkedHashMap<>();
        /** Cuantas carreras (veces tumbado) ha hecho cada clon. */
        final Map<Integer, Integer> carrerasClon = new HashMap<>();
        /** Las posturas pedidas, de Ambush y de los clones. */
        final List<Pose> posturas = new ArrayList<>();
        final List<Pose> posturasAmbush = new ArrayList<>();
        final List<Float> miradas = new ArrayList<>();
        final List<String> sonidos = new ArrayList<>();
        final List<Particle> particulas = new ArrayList<>();
        /** El color de cada destello (FLASH). */
        final List<Object> destellos = new ArrayList<>();
        final List<Double> golpes = new ArrayList<>();
        final List<String> avisos = new ArrayList<>();
        /** Donde ha caido cada punto de polvo. */
        final List<Location> polvosEn = new ArrayList<>();
        /** Cada vez que se mueve una hoja: {frente, angulo, inclina, ticks}. */
        final List<float[]> hojaMovida = new ArrayList<>();
        /** La fase de la hoja que se ve (1: como la katana; 2: el doble). */
        int fase = 1;
        int maxHojas, maxSombras, maxClones, sombrasHechas, sombrasRechazadas, clonesHechos;
        int sacudidas, polvos, movimientos, soltadas;
        /** Veces que se ha movido de pie (no tumbado en una carrera): lo que antes era deslizarse. */
        int movimientosDePie;
        private int siguiente;
        String skin;

        @Override public Location pie() { return pie.clone(); }
        @Override public Location objetivo() { return objetivo == null ? null : objetivo.clone(); }

        @Override
        public void mover(Location l) {
            pie = l.clone();
            movimientos++;
            if (postura == Pose.STANDING) movimientosDePie++;
        }

        @Override public void mirar(float yaw) { miradas.add(yaw); }

        @Override
        public void postura(Pose p) {
            posturas.add(p);
            posturasAmbush.add(p);
            // La misma regla que el maniqui: lo que no admite no cambia nada.
            if (p != null && CuerpoNpc.POSTURAS.contains(p)) postura = p;
        }

        @Override
        public void katana(boolean enLaMano) {
            katana = enLaMano;
            if (!enLaMano) soltadas++;
        }

        @Override public void blandir() { }
        @Override public void visible(boolean si) { visible = si; }

        @Override
        public int hojaLadeada(float frente, float angulo, float inclina) {
            if (hojas.size() >= MAX_HOJAS) return -1;
            int id = ++siguiente;
            hojas.add(id);
            maxHojas = Math.max(maxHojas, hojas.size());
            return id;
        }

        @Override
        public void hojaLadeada(int id, float frente, float angulo, float inclina, int ticks) {
            if (hojas.contains(id)) hojaMovida.add(new float[]{frente, angulo, inclina, ticks});
        }

        @Override public void quitarHoja(int id) { hojas.remove(id); }
        @Override public float tamanoHoja() { return escalaHoja(fase); }

        @Override
        public int sombra(Location l, float yaw, Pose pose) {
            if (pose != null) posturas.add(pose);
            if (sombras.size() >= MAX_SOMBRAS) {
                sombrasRechazadas++;
                return -1;
            }
            int id = ++siguiente;
            sombras.add(id);
            sombrasEn.add(l.clone());
            sombrasHechas++;
            maxSombras = Math.max(maxSombras, sombras.size());
            return id;
        }

        @Override public void quitarSombra(int id) { sombras.remove(id); }

        @Override
        public int clon(Location l, float yaw) {
            if (clones.size() >= MAX_CLONES) return -1;
            int id = ++siguiente;
            Location en = l.clone();
            en.setYaw(yaw);
            clones.put(id, en);
            clonesHechos++;
            maxClones = Math.max(maxClones, clones.size());
            return id;
        }

        @Override public boolean clonVivo(int id) { return clones.containsKey(id); }

        @Override
        public Location clonPie(int id) {
            Location l = clones.get(id);
            return l == null ? null : l.clone();
        }

        @Override
        public void moverClon(int id, Location l) {
            if (clones.containsKey(id)) clones.put(id, l.clone());
        }

        @Override
        public void clonPostura(int id, Pose p) {
            posturas.add(p);
            if (clones.containsKey(id) && p == Pose.FALL_FLYING) carrerasClon.merge(id, 1, Integer::sum);
        }

        @Override public void clonKatana(int id, boolean enLaMano) { }
        @Override public void clonBlandir(int id) { }
        @Override public void quitarClon(int id) { clones.remove(id); }

        /** Alguien golpea ese clon: se disipa, como PeleaAmbush.disiparClon. */
        void golpearClon(int id) {
            clones.remove(id);
        }

        @Override
        public void retirarTodo() {
            hojas.clear();
            sombras.clear();
            clones.clear();
            visible = true;
        }

        @Override
        public List<Location> ruta(Location desde, Vector dir, double largo) {
            List<Location> r = new ArrayList<>();
            for (double d = 0; d <= largo + 1e-9; d += 0.5) r.add(desde.clone().add(dir.clone().multiply(d)));
            return r;
        }

        @Override public Location suelo(Location l) { return l; }
        @Override public Location hueco(Location l) { return l == null ? null : l.clone(); }
        @Override
        public void polvo(Location l, int rgb, float tam) {
            polvos++;
            polvosEn.add(l.clone());
        }

        @Override
        public void particula(Particle p, Location l, int n, double dx, double dy, double dz, double v, Object datos) {
            particulas.add(p);
            if (p == Compat.FLASH) destellos.add(datos);
        }

        @Override public void sonido(Location l, String clave, float volumen, float tono) { sonidos.add(clave); }

        @Override
        public int herirCerca(Location centro, double radio, double k, boolean perdonaAgachados) {
            if (objetivo == null || distPlano(centro, objetivo) > radio) return 0;
            golpes.add(k);
            return 1;
        }

        @Override
        public int herirTramo(Location a, Location b, double ancho, double k, Set<UUID> tocados, Vector dir) {
            if (objetivo == null || tocados.contains(PRESA)) return 0;
            if (ParcaAnomalia.distanciaASegmento(objetivo.getX(), objetivo.getZ(), a.getX(), a.getZ(), b.getX(), b.getZ()) > ancho) {
                return 0;
            }
            tocados.add(PRESA);
            golpes.add(k);
            return 1;
        }

        @Override public void sacudir(Location desde) { sacudidas++; }
        @Override public void avisar(String texto, double radio) { avisos.add(texto); }
        @Override public void avisarPresa(String texto) { avisos.add(texto); }
        @Override public void cambiarSkin(String cuenta) { skin = cuenta; }

        /** Si queda algo del ataque: una postura que no es de pie, escondido, una hoja, una sombra o un clon. */
        boolean ocupada() {
            return postura != Pose.STANDING || !visible || !hojas.isEmpty() || !sombras.isEmpty() || !clones.isEmpty();
        }

        int cuantas(Particle p) {
            int n = 0;
            for (Particle x : particulas) if (x == p) n++;
            return n;
        }

        int cuantos(String sonido) {
            int n = 0;
            for (String s : sonidos) if (s.equals(sonido)) n++;
            return n;
        }
    }

    /** Corre una tecnica hasta que acabe (como mucho "tope" ticks). True si ha acabado. */
    static boolean correr(Tecnica tec, Escena e, int tope) {
        for (int i = 0; i < tope; i++) {
            boolean fin = tec.paso(e);
            tec.t++;
            if (fin) return true;
        }
        return false;
    }

    /** A cuantos bloques (en X) se pone su objetivo en el autotest de cada ataque. */
    static double distanciaPrueba(Ataque x) {
        return switch (x) {
            case ACOMETIDA, CLAN -> 8;
            case PASO, MIL -> 6;
            case TAJO, IAIJUTSU -> 2.5;
        };
    }

    static EscenaPrueba escenaA(double dist) {
        EscenaPrueba e = new EscenaPrueba();
        e.objetivo = e.pie().add(dist, 0, 0);
        return e;
    }

    /**
     * Cortada en cada uno de sus ticks (de 0 al ultimo): cuantas veces NO queda de pie, visible,
     * con la katana y sin hojas, sombras ni clones. n[0] recibe cuantos ticks dura entera.
     */
    static int cortesSucios(Supplier<Tecnica> hacer, double dist, int[] n) {
        EscenaPrueba base = escenaA(dist);
        Tecnica entera = hacer.get();
        int dura = 0;
        while (dura < 600 && !entera.paso(base)) {
            entera.t++;
            dura++;
        }
        n[0] = dura + 1;
        int sucios = 0;
        for (int c = 0; c <= dura; c++) {
            EscenaPrueba e = escenaA(dist);
            Tecnica tec = hacer.get();
            boolean acabo = false;
            for (int i = 0; i < c && !acabo; i++) {
                acabo = tec.paso(e);
                tec.t++;
            }
            if (!acabo) tec.cortar(e);
            if (e.ocupada() || !e.katana) sucios++;
        }
        return sucios;
    }

    /** La diferencia entre dos yaw, en (-180, 180]. */
    static double difYaw(double a, double b) {
        return ((a - b) % 360 + 540) % 360 - 180;
    }

    /**
     * Si alguna clase de PeleaAmbush (su .class y los de sus clases de dentro) nombra la postura de
     * agachado. Sin poder leer ninguno no se da por bueno.
     */
    static boolean nombraAgachado() {
        // El nombre se arma al reves: asi esta comprobacion no lo mete en el .class que revisa.
        byte[] buscado = new StringBuilder("GNIKAENS").reverse().toString().getBytes(StandardCharsets.ISO_8859_1);
        List<Class<?>> clases = new ArrayList<>(List.of(PeleaAmbush.class));
        for (int i = 0; i < clases.size(); i++) clases.addAll(List.of(clases.get(i).getDeclaredClasses()));
        Set<String> recursos = new LinkedHashSet<>();
        for (Class<?> c : clases) recursos.add(c.getName().replace('.', '/') + ".class");
        String propio = PeleaAmbush.class.getName().replace('.', '/');
        for (int i = 1; i <= 20; i++) recursos.add(propio + "$" + i + ".class");
        ClassLoader cl = PeleaAmbush.class.getClassLoader();
        int leidas = 0;
        for (String r : recursos) {
            try (InputStream in = cl.getResourceAsStream(r)) {
                if (in == null) continue;
                leidas++;
                if (contiene(in.readAllBytes(), buscado)) return true;
            } catch (IOException ex) {
                return true;
            }
        }
        return leidas == 0;
    }

    private static boolean contiene(byte[] pajar, byte[] aguja) {
        fuera:
        for (int i = 0; i + aguja.length <= pajar.length; i++) {
            for (int j = 0; j < aguja.length; j++) if (pajar[i + j] != aguja[j]) continue fuera;
            return true;
        }
        return false;
    }

    /**
     * Lo que pide Dosa de las animaciones: nunca se agacha; cada ataque, en las dos fases, acaba de
     * pie, visible y sin hojas, sombras ni clones (y nunca con mas de MAX_HOJAS hojas ni MAX_CLONES
     * clones a la vez), tambien si se corta en cualquier tick; la acometida con sus cinco sombras y
     * sus efectos; el paso sombra, mil cortes y las Sombras del clan; la hoja pequena hasta que se
     * transforma; y que en la fase 1 no salen ni mil cortes ni clones.
     */
    static void autotestAnimaciones(Autotest.Hoja h) {
        for (double ritmo : new double[]{1.0, RITMO_FASE2}) {
            int fase = ritmo < 1 ? 2 : 1;
            String f = " (fase " + fase + ")";
            for (Ataque x : Ataque.values()) {
                if (fase < x.fase) continue;
                double dist = distanciaPrueba(x);
                EscenaPrueba e = escenaA(dist);
                boolean acabo = correr(nueva(x, ritmo), e, 600);
                h.ok(x.nombre + f + ": acaba de pie y sin hojas ni sombras (" + e.postura + ", "
                                + e.hojas.size() + " hojas, " + e.sombras.size() + " sombras, " + e.clones.size() + " clones)",
                        acabo && !e.ocupada() && e.katana && e.maxHojas <= MAX_HOJAS && e.maxClones <= MAX_CLONES
                                && e.sombrasRechazadas == 0);

                EscenaPrueba m = escenaA(dist);
                Tecnica tec = nueva(x, ritmo);
                boolean acaboAntes = false, visto = false;
                for (int i = 0; i < 600 && !acaboAntes; i++) {
                    acaboAntes = tec.paso(m);
                    tec.t++;
                    // A medias: con la postura, escondido, la hoja, una sombra o un clon puestos, y un par de ticks despues.
                    if (m.ocupada() && !visto) {
                        visto = true;
                        for (int j = 0; j < 2 && !acaboAntes; j++) {
                            acaboAntes = tec.paso(m);
                            tec.t++;
                        }
                        break;
                    }
                }
                if (!acaboAntes) tec.cortar(m);
                h.ok(x.nombre + f + ": cortado a medias, de pie y sin hojas ni sombras",
                        visto && !m.ocupada() && m.katana);

                int[] n = new int[1];
                int sucios = cortesSucios(() -> nueva(x, ritmo), dist, n);
                h.ok(x.nombre + f + ": cortado en cualquiera de sus " + n[0] + " ticks, de pie, visible y sin hojas,"
                        + " sombras ni clones (" + sucios + " mal)", sucios == 0);
            }
        }
        int[] n = new int[1];
        int sucios = cortesSucios(() -> new Entrada(180f), 8, n);
        h.ok("la entrada, cortada en cualquiera de sus " + n[0] + " ticks, de pie y con la katana (" + sucios + " mal)", sucios == 0);
        sucios = cortesSucios(() -> new Transformacion("Nagazaki_Yakuza"), 8, n);
        h.ok("la transformación, cortada en cualquiera de sus " + n[0] + " ticks, de pie (" + sucios + " mal)", sucios == 0);

        // ---- Nada de agacharse: ni en la entrada, ni al envainar, ni al transformarse, ni en ningun ataque.
        Set<Pose> pedidas = new LinkedHashSet<>();
        List<Supplier<Tecnica>> todas = new ArrayList<>(List.of(() -> new Entrada(180f), () -> new Transformacion("x")));
        for (Ataque x : Ataque.values()) {
            todas.add(() -> nueva(x, 1.0));
            todas.add(() -> nueva(x, RITMO_FASE2));
        }
        for (Supplier<Tecnica> s : todas) {
            Tecnica tec = s.get();
            EscenaPrueba e = escenaA(tec.ataque == null ? 8 : distanciaPrueba(tec.ataque));
            correr(tec, e, 600);
            pedidas.addAll(e.posturas);
        }
        h.ok("Ambush nunca se agacha: solo de pie o tumbado en las carreras " + pedidas,
                Set.of(Pose.STANDING, Pose.FALL_FLYING).containsAll(pedidas));
        h.ok("PeleaAmbush no nombra la postura de agachado en ninguna de sus clases", !nombraAgachado());

        // ---- La entrada: de pie, de espaldas y con la mano vacia hasta que desenvaina.
        EscenaPrueba en = escenaA(8);
        Entrada ent = new Entrada(180f);
        boolean vacia = true;
        for (int i = 0; i < Entrada.DESENVAINA; i++) {
            ent.paso(en);
            ent.t++;
            vacia &= !en.katana && en.postura == Pose.STANDING;
        }
        correr(ent, en, 100);
        h.ok("la entrada: aparece de pie con la mano vacía y desenvaina al girarse", vacia && en.katana
                && en.postura == Pose.STANDING && en.cuantas(Compat.FLASH) == 1);

        // ---- El iaijutsu: de pie, completamente quieto y con la mano vacia (1.8.2: sin balanceo).
        EscenaPrueba ia = escenaA(2.5);
        Iaijutsu iai = new Iaijutsu(1.0);
        float base = yaw(ia.pie(), ia.objetivo);
        double desvio = 0;
        boolean quieto = true;
        for (int i = 0; i < iai.envaina; i++) {
            int antes = ia.miradas.size();
            iai.paso(ia);
            iai.t++;
            for (float y : ia.miradas.subList(antes, ia.miradas.size())) desvio = Math.max(desvio, Math.abs(difYaw(y, base)));
            quieto &= !ia.katana && ia.postura == Pose.STANDING && ia.movimientos == 0;
            // La presa se mueve mientras envaina: el no la sigue.
            ia.objetivo = ia.objetivo.clone().add(0, 0, 0.2);
        }
        h.ok("iaijutsu: envaina de pie, quieto y con la mano vacía", quieto);
        h.ok("iaijutsu: no se balancea ni sigue a su presa girando (desvío " + Math.round(desvio * 10) / 10.0 + "°)",
                desvio < 1e-3);
        h.ok("iaijutsu: el anillo rojo y el aviso siguen", ia.polvos > 0 && ia.avisos.contains("Iaijutsu: agáchate o aléjate."));

        // ---- La hoja de los efectos: como la katana hasta la transformacion.
        h.cerca("la hoja de los efectos mide 1,0 en la fase 1", 1.0, escalaHoja(1), 1e-9);
        h.cerca("y 2,0 desde la transformación", 2.0, escalaHoja(2), 1e-9);
        h.cerca("la hoja grande gira donde siempre (a 1,8 del pecho)", 1.8, radioHoja(escalaHoja(2)), 1e-9);

        // ---- La acometida: cinco sombras con su barrido y su anillo, criticos, destello, sonidos y temblor.
        for (double ritmo : new double[]{1.0, RITMO_FASE2}) {
            String f = ritmo < 1 ? " (fase 2)" : " (fase 1)";
            EscenaPrueba ac = escenaA(8);
            correr(new Acometida(ritmo), ac, 600);
            h.igual("acometida" + f + ": deja 5 sombras", 5, ac.sombrasHechas);
            h.ok("acometida" + f + ": barrido y anillo rojo en cada sombra, críticos por el camino y un destello al arrancar",
                    ac.cuantas(Compat.SWEEP_ATTACK) >= SOMBRAS_ACOMETIDA + 1 && ac.cuantas(Compat.CRIT) >= 10 && ac.cuantas(Compat.FLASH) == 1
                            && ac.polvos >= 5 * 10);
            h.ok("acometida" + f + ": el riptide al arrancar y el tajo al cruzar a su presa, que siente el corte",
                    ac.cuantos("item.trident.riptide_3") == 1 && ac.cuantos("entity.player.attack.sweep") >= 2 && ac.sacudidas == 1);
            h.igual("acometida" + f + ": el mismo daño (1,2 veces su golpe)", List.of(1.2), ac.golpes);
        }

        // ---- El paso sombra.
        for (double ritmo : new double[]{1.0, RITMO_FASE2}) {
            String f = ritmo < 1 ? " (fase 2)" : " (fase 1)";
            EscenaPrueba ps = escenaA(6);
            PasoSombra paso = new PasoSombra(ritmo);
            paso.paso(ps);
            paso.t++;
            boolean seVa = !ps.visible && !ps.katana && ps.cuantos("entity.enderman.teleport") == 1
                    && ps.cuantas(Compat.LARGE_SMOKE) >= 1;
            correr(paso, ps, 600);
            h.ok("paso sombra" + f + ": se desvanece en humo con la mano vacía", seVa);
            boolean enArco = ps.sombrasHechas == PasoSombra.SOMBRAS;
            for (Location s : ps.sombrasEn) enArco &= distPlano(s, ps.objetivo) >= 3 && distPlano(s, ps.objetivo) <= 4;
            h.ok("paso sombra" + f + ": deja 3 sombras en arco a 3-4 bloques de su presa", enArco);
            Location pie = ps.pie();
            double detras = (pie.getX() - ps.objetivo.getX()) * dir(ps.objetivo.getYaw()).getX()
                    + (pie.getZ() - ps.objetivo.getZ()) * dir(ps.objetivo.getYaw()).getZ();
            h.ok("paso sombra" + f + ": reaparece a su espalda, visible, y la corta con su golpe", ps.visible && ps.katana
                    && detras < -1 && ps.golpes.equals(List.of(1.0)) && ps.cuantos("entity.enderman.teleport") == 2
                    && ps.cuantos("entity.player.attack.sweep") >= 1);
        }

        // ---- Mil cortes (fase 2).
        EscenaPrueba mc = escenaA(6);
        MilCortes mil = new MilCortes(RITMO_FASE2);
        Location inicio = mc.pie();
        for (int i = 0; i < MilCortes.AVISO; i++) {
            mil.paso(mc);
            mil.t++;
        }
        boolean aviso = mc.movimientos == 0 && mc.polvos >= 3 * 13;
        correr(mil, mc, 600);
        int carreras = mc.cuantas(Compat.LARGE_SMOKE);
        h.ok("mil cortes: avisa 0,5 s con una línea roja en cada una de sus tres direcciones", aviso);
        h.igual("mil cortes: tres acometidas a través de su presa", 3, mc.sacudidas);
        h.ok("mil cortes: cambia de lado y vuelve con acometidas cortas, tumbado y sin deslizarse de pie ("
                + carreras + " carreras, " + mc.movimientosDePie + " pasos de pie)", carreras == 3 + MilCortes.CORTES
                && mc.movimientosDePie <= 1);
        h.ok("mil cortes: de la primera acometida a la última sin ponerse de pie ni volver a arrancar (posturas "
                        + mc.posturasAmbush + ", " + mc.cuantos("item.trident.riptide_3") + " riptide, "
                        + mc.cuantas(Compat.FLASH) + " destello)",
                mc.posturasAmbush.equals(List.of(Pose.FALL_FLYING, Pose.STANDING))
                        && mc.cuantos("item.trident.riptide_3") == 1 && mc.cuantas(Compat.FLASH) == 1);
        h.igual("mil cortes: cada corte al 60 % de su golpe", List.of(0.6, 0.6, 0.6), mc.golpes);
        h.ok("mil cortes: cada una con sus sombras", mc.sombrasHechas >= 3 * MilCortes.SOMBRAS);
        h.ok("mil cortes: acaba donde empezó", distPlano(mc.pie(), inicio) < 0.01);

        // ---- Las Sombras del clan (fase 2).
        EscenaPrueba cl = escenaA(8);
        Clan clan = new Clan(RITMO_FASE2);
        clan.paso(cl);
        clan.t++;
        List<Location> puestos = new ArrayList<>(cl.clones.values());
        boolean corona = puestos.size() == 3;
        for (Location p : puestos) corona &= Math.abs(distPlano(p, cl.objetivo) - Clan.RADIO) < 0.01;
        for (int i = 0; corona && i < puestos.size(); i++) {
            Location p = puestos.get(i), q = puestos.get((i + 1) % puestos.size());
            double ap = Math.atan2(p.getZ() - cl.objetivo.getZ(), p.getX() - cl.objetivo.getX());
            double aq = Math.atan2(q.getZ() - cl.objetivo.getZ(), q.getX() - cl.objetivo.getX());
            corona = Math.abs(Math.abs(Math.toDegrees(ParcaAnomalia.normalizar(aq - ap))) - 120) < 0.01;
        }
        h.ok("sombras del clan: 3 clones a 120° alrededor de su presa y a 6 bloques, que suenan a la vez", corona
                && cl.cuantos("block.chain.break") == 3);
        h.igual("sombras del clan: su aviso en la barra de acción", List.of(Clan.AVISO_TEXTO), cl.avisos);
        correr(clan, cl, 600);
        boolean cadaUno = cl.carrerasClon.size() == 3;
        for (int v : cl.carrerasClon.values()) cadaUno &= v == 1;
        h.ok("sombras del clan: cada clon atraviesa a su presa, uno tras otro, al 60 % de su golpe", cadaUno
                && cl.golpes.equals(List.of(0.6, 0.6, 0.6)));
        h.ok("sombras del clan: Ambush espera quieto, de pie y con la katana en la mano", cl.movimientos == 0 && cl.soltadas == 0
                && cl.katana && !cl.posturasAmbush.contains(Pose.FALL_FLYING));
        int fundidos = 0;
        for (Object d : cl.destellos) if (Color.fromRGB(RGB_CRIMSON).equals(d)) fundidos++;
        h.ok("sombras del clan: al acabar se funden en él con un destello y no queda ninguno", cl.clones.isEmpty()
                && cl.sombras.isEmpty() && fundidos == 1 && cl.maxClones == 3);

        EscenaPrueba gp = escenaA(8);
        Clan golpeado = new Clan(RITMO_FASE2);
        for (int i = 0; i < Clan.PREPARA; i++) {
            golpeado.paso(gp);
            golpeado.t++;
        }
        int segundo = new ArrayList<>(gp.clones.keySet()).get(1);
        gp.golpearClon(segundo);
        correr(golpeado, gp, 600);
        h.ok("sombras del clan: un golpe disipa un clon y su corte no llega", !gp.carrerasClon.containsKey(segundo)
                && gp.golpes.size() == 2 && gp.clones.isEmpty());

        // ---- Nunca mas de 3 clones, y todos fuera al acabar la pelea sea como sea.
        EscenaPrueba tope = new EscenaPrueba();
        for (int i = 0; i < 5; i++) tope.clon(tope.pie(), 0f);
        h.ok("nunca más de 3 clones vivos", MAX_CLONES == 3 && Clan.CLONES <= MAX_CLONES && tope.clones.size() == 3);
        for (String como : List.of("muere Ambush", "se retira", "se para el plugin", "se descarga el mundo")) {
            EscenaPrueba r = escenaA(8);
            Clan enCurso = new Clan(RITMO_FASE2);
            for (int i = 0; i < 30; i++) {
                enCurso.paso(r);
                enCurso.t++;
            }
            boolean habia = !r.clones.isEmpty();
            r.hoja(0f);
            retirar(enCurso, r);
            h.ok("si " + como + " a mitad de las Sombras del clan, no queda ningún clon, sombra ni hoja", habia
                    && !r.ocupada() && r.katana);
        }

        // ---- La rotacion: en la fase 1 no salen ni mil cortes ni clones.
        Set<Ataque> f1 = EnumSet.noneOf(Ataque.class), f2 = EnumSet.noneOf(Ataque.class);
        for (double d = 0; d <= 20; d += 0.25) {
            for (double az = 0; az < 1; az += 0.02) {
                Ataque a1 = elegir(d, 1, 0, Map.of(), null, az), a2 = elegir(d, 2, 0, Map.of(), null, az);
                if (a1 != null) f1.add(a1);
                if (a2 != null) f2.add(a2);
            }
        }
        h.igual("fase 1: acometida, tajo doble, paso sombra e iaijutsu",
                EnumSet.of(Ataque.ACOMETIDA, Ataque.TAJO, Ataque.PASO, Ataque.IAIJUTSU), f1);
        h.igual("fase 2: lo mismo y mil cortes (las Sombras del clan van por reloj)",
                EnumSet.of(Ataque.ACOMETIDA, Ataque.TAJO, Ataque.PASO, Ataque.IAIJUTSU, Ataque.MIL), f2);
        h.ok("en la fase 1 no salen ni mil cortes ni clones", !f1.contains(Ataque.MIL) && !f1.contains(Ataque.CLAN)
                && !tocaClan(1, 1_000_000, 0) && !tocaClan(1, 1_000_000, Long.MAX_VALUE));
        h.ok("en la fase 2, las Sombras del clan cada 25 s", Ataque.CLAN.espera == 25 * 20 && tocaClan(2, 700, 700)
                && !tocaClan(2, 699, 700) && Ataque.MIL.fase == 2 && Ataque.CLAN.fase == 2);
        h.ok("las esperas de antes, igual (acometida 6 s, tajo 5 s, iaijutsu 10 s)", Ataque.ACOMETIDA.espera == 120
                && Ataque.TAJO.espera == 100 && Ataque.IAIJUTSU.espera == 200);

        // ---- 1.8.2 · Fluidez: nada de dar vueltas despacio. Con la presa andando de lado, en cada
        // ataque la mirada del cuerpo que se ve o no cambia o cambia 30 grados o mas de un tick al
        // siguiente, y ningun giro dura mas de 12 ticks.
        for (double ritmo : new double[]{1.0, RITMO_FASE2}) {
            int fase = ritmo < 1 ? 2 : 1;
            String f = " (fase " + fase + ")";
            for (Ataque x : Ataque.values()) {
                if (fase < x.fase) continue;
                double[] g = giros(nueva(x, ritmo), distanciaPrueba(x));
                String como = g[1] == 0 ? "no gira nunca" : "paso más corto " + Math.round(g[0]) + "°/tick, giro más largo "
                        + Math.round(g[1]) + " ticks";
                h.ok(x.nombre + f + ": no gira a pasitos (" + como + ")", g[0] >= GIRO_MINIMO - 1e-3 && g[1] <= 12);
            }
        }
        // ---- 1.8.3 · El tajo doble: el cuerpo quieto y la hoja barriendo, de ida y de vuelta y en cruz.
        autotestTajo(h, 1.0);
        autotestTajo(h, RITMO_FASE2);

        // ---- La entrada: de espaldas a mirarle en 4-5 ticks, a 30°/tick o mas, y luego quieto.
        EscenaPrueba ge = escenaA(8);
        float haciaEl = yaw(ge.pie(), ge.objetivo);
        Entrada giro = new Entrada(haciaEl + 180f);
        List<Double> pasosGiro = new ArrayList<>();
        Float previa = null;
        for (int i = 0; i < 100; i++) {
            int m0 = ge.miradas.size();
            boolean fin = giro.paso(ge);
            giro.t++;
            float y = ge.miradas.size() > m0 ? ge.miradas.get(ge.miradas.size() - 1) : yaw(ge.pie(), ge.objetivo);
            if (previa != null && Math.abs(difYaw(y, previa)) > 1e-3) pasosGiro.add(Math.abs(difYaw(y, previa)));
            previa = y;
            if (fin) break;
        }
        double pasoMin = pasosGiro.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        h.ok("la entrada: se da la vuelta en 4-5 ticks a 30°/tick o más (" + pasosGiro.size() + " ticks, "
                        + Math.round(pasoMin) + "°/tick) y acaba mirándole",
                pasosGiro.size() >= 4 && pasosGiro.size() <= 5 && pasoMin >= GIRO_MINIMO - 1e-3
                        && previa != null && Math.abs(difYaw(previa, haciaEl)) < 1e-3);

        // ---- Los clones vuelven deprisa (6-8 ticks) y sin girar mientras esperan su turno.
        EscenaPrueba cv = escenaA(8);
        Clan cvc = new Clan(RITMO_FASE2);
        cvc.paso(cv);
        cvc.t++;
        Map<Integer, Float> yawClon = new HashMap<>();
        for (Map.Entry<Integer, Location> c : cv.clones.entrySet()) yawClon.put(c.getKey(), c.getValue().getYaw());
        boolean quietos = true;
        int moviendose = 0;
        for (int i = 1; i < 600; i++) {
            Map<Integer, Location> antes = new HashMap<>();
            for (Map.Entry<Integer, Location> c : cv.clones.entrySet()) antes.put(c.getKey(), c.getValue().clone());
            cv.objetivo = cv.objetivo.clone().add(0, 0, 0.15);
            boolean fin = cvc.paso(cv);
            cvc.t++;
            for (int j = 0; j < cvc.ids.size(); j++) {
                int id = cvc.ids.get(j);
                Location l = cv.clones.get(id);
                if (l != null && cvc.t <= Clan.PREPARA + (long) Clan.ENTRE * j) quietos &= l.getYaw() == yawClon.get(id);
            }
            if (cvc.convergeDesde >= 0 && cvc.t > cvc.convergeDesde && !antes.isEmpty()) {
                boolean alguno = false;
                for (Map.Entry<Integer, Location> c : antes.entrySet()) {
                    Location l = cv.clones.get(c.getKey());
                    alguno |= l == null || distPlano(l, c.getValue()) > 1e-6;
                }
                if (alguno) moviendose++;
            }
            if (fin) break;
        }
        h.ok("sombras del clan: los clones vuelven en 6-8 ticks (" + moviendose + ") y no se giran mientras esperan",
                moviendose >= 6 && moviendose <= 8 && Clan.CONVERGE >= 6 && Clan.CONVERGE <= 8 && quietos);
    }

    /**
     * 1.8.3 · El tajo doble en la fase de ese ritmo, con su presa andando de lado: el cuerpo no gira
     * ni se mueve (mira todo el rato a donde estaba ella al empezar); en cada corte la hoja barre ARCO
     * grados a pasos de 30 a 45 por tick, el primero de su izquierda a su derecha y el segundo al
     * reves, en planos ladeados al contrario; cada corte deja delante de el su media luna de polvo, y
     * las dos se cruzan; y el golpe, los tiempos y el sonido siguen como antes.
     */
    static void autotestTajo(Autotest.Hoja h, double ritmo) {
        int fase = ritmo < 1 ? 2 : 1;
        String f = " (fase " + fase + ")";
        EscenaPrueba e = escenaA(distanciaPrueba(Ataque.TAJO));
        e.fase = fase;
        TajoDoble tajo = new TajoDoble(ritmo);
        float frente = yaw(e.pie(), e.objetivo);
        Location base = e.objetivo.clone();
        long[] empieza = {tajo.aviso, tajo.aviso + tajo.giro + tajo.pausa};
        long fin = empieza[1] + tajo.giro + tajo.respiro;
        // Por corte: los pasos que se le piden a la hoja y el polvo que deja.
        List<List<float[]>> pasos = List.of(new ArrayList<>(), new ArrayList<>());
        List<List<Location>> polvo = List.of(new ArrayList<>(), new ArrayList<>());
        List<Long> golpesEn = new ArrayList<>();
        boolean acabo = false;
        int dura = 0;
        while (dura < 200 && !acabo) {
            // Anda de lado lo bastante despacio para seguir a su alcance en los dos golpes.
            e.objetivo = base.clone().add(0, 0, 0.05 * dura);
            long t = tajo.t;
            int g0 = e.golpes.size(), m0 = e.hojaMovida.size(), p0 = e.polvosEn.size();
            acabo = tajo.paso(e);
            tajo.t++;
            dura++;
            if (e.golpes.size() > g0) golpesEn.add(t);
            for (int c = 0; c < 2; c++) {
                long dt = t - empieza[c];
                if (dt >= 0 && dt < tajo.giro) pasos.get(c).addAll(e.hojaMovida.subList(m0, e.hojaMovida.size()));
                if (dt >= 0 && dt <= tajo.giro) polvo.get(c).addAll(e.polvosEn.subList(p0, e.polvosEn.size()));
            }
        }

        double desvio = 0;
        for (float y : e.miradas) desvio = Math.max(desvio, Math.abs(difYaw(y, frente)));
        h.ok("tajo doble" + f + ": el cuerpo no gira ni se mueve, mira fijo a su presa aunque ella ande (desvío "
                        + Math.round(desvio * 10) / 10.0 + "°, mirada fija " + e.miradas.size() + " de " + dura + " ticks)",
                acabo && desvio < 1e-3 && e.miradas.size() == dura && e.movimientos == 0);

        // Los pasos de cada corte, desde donde espera la hoja (tras echarse atras, o tras subir en la pausa).
        boolean barre = e.hojaMovida.size() == 2 * tajo.giro + 2 && TajoDoble.INCLINA > 0;
        StringBuilder como = new StringBuilder();
        for (int c = 0; c < 2; c++) {
            float sentido = c == 0 ? 1f : -1f;
            float previo = -sentido * TajoDoble.ARCO / 2;
            double menor = 360, mayor = 0;
            for (float[] p : pasos.get(c)) {
                double paso = (p[1] - previo) * sentido;
                menor = Math.min(menor, paso);
                mayor = Math.max(mayor, paso);
                barre &= Math.abs(difYaw(p[0], frente)) < 1e-3 && p[2] == sentido * TajoDoble.INCLINA && p[3] >= 1;
                previo = p[1];
            }
            barre &= pasos.get(c).size() == tajo.giro && Math.abs(previo - sentido * TajoDoble.ARCO / 2) < 1e-3
                    && menor >= GIRO_MINIMO - 1e-3 && mayor <= 45 + 1e-3;
            como.append(c == 0 ? "" : "; ").append(pasos.get(c).size()).append(" pasos de ").append(Math.round(menor))
                    .append(" a ").append(Math.round(mayor)).append("°");
        }
        h.ok("tajo doble" + f + ": la hoja barre " + Math.round(TajoDoble.ARCO) + "° de su izquierda a su derecha y luego"
                + " al revés, a 30-45° por tick y en planos ladeados al contrario (" + como + ")", barre);

        // Cada media luna, alrededor de su pecho y de lado a lado por delante de el: el primer corte,
        // alto por su izquierda y bajo por su derecha; el segundo, al reves (se cruzan delante).
        Location pecho = e.pie().add(0, PECHO, 0);
        double alcance = radioHoja(escalaHoja(fase)) + escalaHoja(fase);
        boolean lunas = e.cuantas(Compat.CRIT) >= 2 * tajo.giro;
        StringBuilder puntos = new StringBuilder();
        for (int c = 0; c < 2; c++) {
            double izquierda = 0, derecha = 0;
            for (Location p : polvo.get(c)) {
                double d = p.toVector().distance(pecho.toVector());
                double rel = difYaw(yaw(pecho, p), frente);
                double sube = p.getY() - pecho.getY();
                lunas &= d >= 0.5 && d <= alcance;
                izquierda = Math.min(izquierda, rel);
                derecha = Math.max(derecha, rel);
                if (Math.abs(rel) > 45) lunas &= (rel < 0) == (c == 0) ? sube > 0 : sube < 0;
            }
            lunas &= polvo.get(c).size() >= 10 * tajo.giro && izquierda < -80 && derecha > 80;
            puntos.append(c == 0 ? "" : " y ").append(polvo.get(c).size());
        }
        h.ok("tajo doble" + f + ": cada corte deja su media luna de polvo rojo por delante, y las dos se cruzan ("
                + puntos + " puntos)", lunas);

        List<Integer> tiempos = fase == 1 ? List.of(10, 6, 4, 6) : List.of(8, 5, 3, 5);
        h.ok("tajo doble" + f + ": el golpe y los tiempos de antes (dos golpes al " + Math.round(TajoDoble.K * 100)
                        + " %, a mitad de cada corte; " + dura + " ticks)",
                e.golpes.equals(List.of(TajoDoble.K, TajoDoble.K)) && TajoDoble.RADIO == 3.8
                        && golpesEn.equals(List.of(empieza[0] + tajo.giro / 2, empieza[1] + tajo.giro / 2))
                        && dura == fin + 1 && tiempos.equals(List.of(tajo.aviso, tajo.giro, tajo.pausa, tajo.respiro))
                        && e.cuantos("entity.player.attack.sweep") == 2 && e.maxHojas == 1);
    }

    /**
     * Corre un ataque con su presa andando de lado (0,15 bloques por tick) y mide como gira el
     * cuerpo que se ve: {el paso mas corto de un tick al siguiente cuando gira, el giro mas largo
     * en ticks seguidos}. Un tick sin mirada fija cuenta como en el juego (mira a su objetivo); los
     * ticks escondido (paso sombra) no cuentan. Sin ningun giro, {360, 0}.
     */
    static double[] giros(Tecnica tec, double dist) {
        EscenaPrueba e = escenaA(dist);
        Location base = e.objetivo.clone();
        Float antes = null;
        double minimo = 360;
        int racha = 0, larga = 0;
        for (int i = 0; i < 600; i++) {
            e.objetivo = base.clone().add(0, 0, 0.15 * i);
            int m0 = e.miradas.size();
            boolean fin = tec.paso(e);
            tec.t++;
            Float ahora = !e.visible ? null
                    : e.miradas.size() > m0 ? e.miradas.get(e.miradas.size() - 1) : yaw(e.pie(), e.objetivo);
            double d = ahora == null || antes == null ? 0 : Math.abs(difYaw(ahora, antes));
            if (d > 1e-3) {
                minimo = Math.min(minimo, d);
                larga = Math.max(larga, ++racha);
            } else {
                racha = 0;
            }
            antes = ahora;
            if (fin) break;
        }
        return new double[]{minimo, larga};
    }
}
