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
import org.bukkit.entity.LivingEntity;
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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Una pelea de Ambush (Calamity 1.8.0): el samurai de los contratos de la Sentencia (Ambush).
 *
 * El cuerpo es el de la Parca: un esqueleto wither invisible de Amenazas (vida logica, tope por
 * golpe, dano logico para el botin, marca lethal_world:amenaza) con un maniqui encima (CuerpoNpc)
 * con la skin de ambush.skin-fase-1, algo mayor que un jugador. Al bajar de ambush.fase-2-vida de
 * su vida se transforma (1,5 s agachado, humo) y pasa a la skin de ambush.skin-fase-2; desde ahi
 * sus ataques van un 25 % mas rapidos y el iaijutsu vuelve el doble de pronto.
 *
 * Solo persigue a su presa. A quien le pega le responde un rato (RESPUESTA_TICKS) y vuelve a
 * ella. Si la presa se aleja mucho (minijefes.distancia-maxima, como los minijefes) o lleva
 * ATASCO_TICKS sin poder golpearla, aparece a su espalda con humo. En la zona spawn no entra: se
 * queda quieto fuera y quien le pega desde dentro no le hace nada. En MINUTOS se retira.
 *
 * Tres ataques y nada mas: la acometida (una linea roja en el suelo y la recorre tumbado como una
 * estocada, dejando sombras), el tajo doble (dos cortes girando sobre si mismo) y el iaijutsu (se
 * agacha 1,5 s envainando dentro de un anillo rojo y corta: el doble de dano a quien siga a menos
 * de 4 bloques y de pie). Cada ataque es una Tecnica que se mueve tick a tick contra una Escena:
 * en el juego la pinta esta pelea con Bukkit; en el autotest, un falso que cuenta posturas, hojas
 * y sombras. Asi se comprueba sin servidor que cada ataque acaba de pie y sin dejar nada.
 *
 * Dos relojes, como pide que se vea fluido: la tarea de 2 ticks de Amenazas (registrarPelea) lleva
 * la cabeza (a quien va, que hace, la barra, si la presa sigue) y un animador de 1 tick mueve el
 * cuerpo que se ve, las hojas y los ataques, para que el cliente interpole sin saltos.
 */
final class PeleaAmbush implements Runnable {

    enum Estado { APARECE, PELEA, FIN }

    /** Los tres ataques, con lo que ensena el menu de /anomaly (AmbushType). */
    enum Ataque {
        ACOMETIDA("am_acometida", "Acometida", "Marca una línea en el suelo y la recorre de golpe, tumbado como una estocada.",
                120, 36, 4, Material.TRIDENT),
        TAJO("am_tajo", "Tajo doble", "Dos cortes seguidos girando sobre sí mismo a quien tenga cerca.",
                100, 34, 5, Material.NETHERITE_SWORD),
        IAIJUTSU("am_iaijutsu", "Iaijutsu", "Envaina agachado 1,5 s y corta: el doble de daño a quien esté a menos de 4 bloques y de pie.",
                200, 44, 3, Material.IRON_SWORD);

        final String id;
        final String nombre;
        final String descripcion;
        /** Ticks entre dos usos (en la fase 2 el iaijutsu espera la mitad). */
        final int espera;
        /** Lo que dura mas o menos, en ticks (para /anomaly test). */
        final int duracion;
        final int peso;
        final Material icono;

        Ataque(String id, String nombre, String descripcion, int espera, int duracion, int peso, Material icono) {
            this.id = id;
            this.nombre = nombre;
            this.descripcion = descripcion;
            this.espera = espera;
            this.duracion = duracion;
            this.peso = peso;
            this.icono = icono;
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
    /** Hojas (ItemDisplay de la katana) y sombras vivas a la vez, como mucho. */
    static final int MAX_HOJAS = 2, MAX_SOMBRAS = 4;
    /** Lo que vive cada sombra de la acometida. */
    static final int VIDA_SOMBRA = 9;
    /** La hoja: a que distancia de su pecho gira su centro y a que tamano. */
    private static final double RADIO_HOJA = 1.8;
    private static final float ESCALA_HOJA = 2f;
    /** El rojo de los avisos en el suelo (la linea de la acometida, el anillo del iaijutsu). */
    static final int RGB_AVISO = 0xE0333B;
    static final int RGB_CRIMSON = 0xFF6B6B;

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
        // Sin katana al nacer: aparece de rodillas y envainado, y la entrada la desenvaina.
        this.npc = new CuerpoNpc(hc, a.skin1, ESCALA, null, "entity.player.hurt");
    }

    /**
     * Lo pone en el mundo, de rodillas en sitio y mirando a "deEspaldas" (de espaldas a su presa),
     * y arranca la entrada. Null si el spawn lo cancela alguien (WorldGuard, la zona spawn).
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
     * La hoja en el angulo "yaw": tumbada, con el mango hacia Ambush y la punta hacia fuera, y su
     * centro a "radio" de el. El sprite de la espada va en diagonal (mango abajo a la izquierda):
     * se gira 45 grados para ponerlo derecho, 90 para tumbarlo y el yaw para orientarlo.
     */
    static Transformation hojaEn(float yaw, double radio, float escala) {
        float r = (float) -Math.toRadians(yaw);
        Quaternionf giro = new Quaternionf().rotationY(r).rotateX((float) (Math.PI / 2)).rotateZ((float) (Math.PI / 4));
        Vector3f t = new Quaternionf().rotationY(r).transform(new Vector3f(0, 0, (float) radio));
        return new Transformation(t, giro, new Vector3f(escala, escala, escala), new Quaternionf());
    }

    // ================================================================ la escena

    /**
     * Lo que un ataque toca del mundo. En el juego lo hace esta pelea (EscenaReal); en el autotest,
     * EscenaPrueba, que solo cuenta posturas, hojas y sombras.
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

        /** Una hoja (la katana en un ItemDisplay) girando a su alrededor. -1 si ya hay MAX_HOJAS. */
        int hoja(float yaw);

        /** Gira esa hoja hasta el yaw, interpolado en "ticks". */
        void hoja(int id, float yaw, int ticks);

        void quitarHoja(int id);

        /** Una copia visual de Ambush (misma skin, sin IA ni choque) en ese sitio. -1 si no se puede. */
        int sombra(Location l, float yaw, Pose pose);

        void quitarSombra(int id);

        /** Los puntos (cada 0,5) de una carrera en linea recta a ras de suelo, cortada en la primera pared. */
        List<Location> ruta(Location desde, Vector dir, double largo);

        /** El suelo bajo un punto (para los avisos pintados). */
        Location suelo(Location l);

        void polvo(Location l, int rgb, float tam);

        void particula(Particle p, Location l, int n, double dx, double dy, double dz, double v, Object datos);

        void sonido(Location l, String clave, float volumen, float tono);

        /** Hiere a quien este a "radio" de "centro" (sin los agachados si perdona). k: veces su golpe. */
        int herirCerca(Location centro, double radio, double k, boolean perdonaAgachados);

        /** Hiere a quien este a "ancho" del tramo a-b y no este en tocados, y le empuja de lado. */
        int herirTramo(Location a, Location b, double ancho, double k, Set<UUID> tocados, Vector dir);

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

        /** Un tick. True al acabar: para entonces Ambush esta de pie y sin hojas ni sombras de este ataque. */
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
            case IAIJUTSU -> new Iaijutsu(ritmo);
        };
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

    // ------------------------------------------------------------------ Entrada

    /**
     * La entrada: aparece de rodillas y de espaldas a su presa (1 s, campana grave), se levanta, se
     * gira hacia ella y desenvaina (la katana en la mano, un destello y el sonido de la cadena).
     */
    static final class Entrada extends Tecnica {
        static final int RODILLA = 20, GIRO = 6, DESENVAINA = 26, FIN = 30;
        private final float deEspaldas;

        Entrada(float deEspaldas) {
            super(null);
            this.deEspaldas = deEspaldas;
        }

        @Override
        boolean paso(Escena e) {
            Location pie = e.pie();
            if (t == 0) {
                e.postura(Pose.SNEAKING);
                e.katana(false);
                e.sonido(pie, "block.bell.use", 2f, 0.5f);
                e.particula(Compat.LARGE_SMOKE, pie.clone().add(0, 0.5, 0), 20, 0.5, 0.4, 0.5, 0.02, null);
                e.particula(Compat.ASH, pie.clone().add(0, 1, 0), 30, 0.8, 0.8, 0.8, 0.01, null);
            }
            if (t < RODILLA) {
                e.mirar(deEspaldas);
                if (t % 4 == 0) e.particula(Compat.ASH, pie.clone().add(0, 1.2, 0), 6, 0.6, 0.6, 0.6, 0.01, null);
                return false;
            }
            if (t == RODILLA) e.postura(Pose.STANDING);
            Location obj = e.objetivo();
            float haciaEl = obj == null ? deEspaldas + 180f : yaw(pie, obj);
            if (t < RODILLA + GIRO) {
                e.mirar(girar(deEspaldas, haciaEl, (t - RODILLA + 1) / (double) GIRO));
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
     * El paso a la fase 2: 1,5 s quieto y agachado entre humo, con la llamada grave del evocador;
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
                e.postura(Pose.SNEAKING);
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
     * pared) durante 0,5 s y la recorre de golpe tumbado (FALL_FLYING), a 1,5 bloques por tick (2
     * en la fase 2). Deja 3 o 4 sombras donde iba pasando, que se van a los VIDA_SOMBRA ticks.
     * Quien este en la linea se lleva el golpe y un empujon de lado.
     */
    static final class Acometida extends Tecnica {
        static final double K = 1.2, ANCHO = 1.4;
        final int aviso, porTick;
        private List<Location> ruta = List.of();
        private Vector dir = new Vector(0, 0, 1);
        private float yaw;
        private int indice, pasoCarrera, cadaSombra = 1, sombrasHechas;
        private boolean corriendo, frenada;
        private long frenoEn;
        private final Set<UUID> tocados = new HashSet<>();
        /** Las sombras vivas: id -> tick en que se van. */
        private final Map<Integer, Long> sombras = new LinkedHashMap<>();

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
                double dx = obj.getX() - pie.getX(), dz = obj.getZ() - pie.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > 1e-6) dir = new Vector(dx / dist, 0, dz / dist);
                yaw = yaw(dir.getX(), dir.getZ());
                ruta = e.ruta(pie, dir, Math.max(6, Math.min(16, dist + 3)));
                if (ruta.size() < 3) return true;
                int pasos = (ruta.size() - 1 + porTick - 1) / porTick;
                cadaSombra = Math.max(1, pasos / MAX_SOMBRAS);
                e.katana(true);
                e.sonido(pie, "item.trident.return", 1.2f, 0.6f);
            }
            e.mirar(yaw);
            if (!corriendo) {
                if (t % 2 == 0) {
                    float tam = (float) (1.0 + 0.6 * Math.min(1, t / (double) aviso));
                    for (int i = 0; i < ruta.size(); i += 2) e.polvo(ruta.get(i).clone().add(0, 0.15, 0), RGB_AVISO, tam);
                }
                if (t < aviso) return false;
                corriendo = true;
                e.postura(Pose.FALL_FLYING);
                e.blandir();
                e.sonido(e.pie(), "item.trident.riptide_1", 1.4f, 1.0f);
            }
            if (!frenada) {
                Location antes = e.pie();
                int hasta = Math.min(ruta.size() - 1, indice + porTick);
                if (hasta > indice) {
                    Location destino = ruta.get(hasta).clone();
                    destino.setYaw(yaw);
                    destino.setPitch(0);
                    if (pasoCarrera % cadaSombra == 0 && sombrasHechas < MAX_SOMBRAS) {
                        sombrasHechas++;
                        int id = e.sombra(antes, yaw, Pose.FALL_FLYING);
                        if (id >= 0) sombras.put(id, t + VIDA_SOMBRA);
                    }
                    e.mover(destino);
                    e.herirTramo(antes, destino, ANCHO, K, tocados, dir);
                    e.particula(Compat.CRIT, destino.clone().add(0, 0.8, 0), 4, 0.3, 0.2, 0.3, 0.1, null);
                    indice = hasta;
                    pasoCarrera++;
                }
                if (indice >= ruta.size() - 1) {
                    frenada = true;
                    frenoEn = t;
                    e.postura(Pose.STANDING);
                    Location fin = e.pie();
                    e.particula(Compat.SWEEP_ATTACK, fin.clone().add(0, 1, 0), 3, 0.6, 0.3, 0.6, 0, null);
                    e.particula(Compat.LARGE_SMOKE, fin.clone().add(0, 0.3, 0), 8, 0.5, 0.2, 0.5, 0.02, null);
                    e.sonido(fin, "entity.player.attack.sweep", 1.3f, 0.9f);
                }
            }
            sombras.entrySet().removeIf(s -> {
                if (t < s.getValue()) return false;
                e.quitarSombra(s.getKey());
                return true;
            });
            return frenada && sombras.isEmpty() && t - frenoEn >= 4;
        }

        @Override
        void cortar(Escena e) {
            for (int id : sombras.keySet()) e.quitarSombra(id);
            sombras.clear();
            e.postura(Pose.STANDING);
        }
    }

    // ---------------------------------------------------------------- Tajo doble

    /**
     * Dos cortes seguidos girando sobre si mismo: la hoja aparece a su lado (el aviso), y en cada
     * corte el cuerpo y la hoja dan media vuelta (360 grados entre los dos). Cada corte hiere a quien
     * este a RADIO. El maniqui no admite la postura del giro del tridente: el giro es el del cuerpo.
     */
    static final class TajoDoble extends Tecnica {
        static final double RADIO = 3.8, K = 0.9;
        final int aviso, giro, pausa, respiro;
        private float base;
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
                // Empieza por su izquierda: el primer corte le pasa por delante de lado a lado.
                base = (obj == null ? 0f : yaw(pie, obj)) - 90f;
                e.katana(true);
                hoja = e.hoja(base);
                e.sonido(pie, "item.trident.return", 1.2f, 0.7f);
            }
            long c2 = aviso + giro + pausa, fin = c2 + giro + respiro;
            if (t < aviso) {
                e.mirar(base);
                return false;
            }
            int corte = t < c2 ? 0 : 1;
            long dt = t - (corte == 0 ? aviso : c2);
            if (dt < giro) {
                float angulo = base + 180f * corte + 180f * (dt + 1) / giro;
                e.mirar(angulo);
                if (hoja >= 0) e.hoja(hoja, angulo, 2);
                if (dt == 0) {
                    e.blandir();
                    e.sonido(pie, "entity.player.attack.sweep", 1.4f, 0.8f + 0.2f * corte);
                    barrido(e, pie, 2.2);
                }
                if (dt == giro / 2) e.herirCerca(pie, RADIO, K, false);
                return false;
            }
            e.mirar(base + 180f * (corte + 1));
            if (t < fin) return false;
            if (hoja >= 0) e.quitarHoja(hoja);
            hoja = -1;
            e.postura(Pose.STANDING);
            return true;
        }

        @Override
        void cortar(Escena e) {
            if (hoja >= 0) e.quitarHoja(hoja);
            hoja = -1;
            e.postura(Pose.STANDING);
        }
    }

    // ------------------------------------------------------------------ Iaijutsu

    /**
     * Se agacha 1,5 s envainando (la mano vacia) dentro de un anillo rojo de 4 bloques, con el
     * sonido de la vaina y un aviso en la barra. Al soltarlo se levanta, la katana vuelve a la mano
     * de golpe, destello, y un corte rapido (la hoja gira 90 grados): el doble de su golpe a quien
     * siga a menos de 4 bloques y no este agachado.
     */
    static final class Iaijutsu extends Tecnica {
        static final double RADIO = 4, K = 2.0;
        static final int CORTE = 3;
        final int envaina, respiro;
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
                e.postura(Pose.SNEAKING);
                e.katana(false);
                e.sonido(pie, "item.armor.equip_chain", 1.2f, 0.6f);
                e.avisar("Iaijutsu: agáchate o aléjate.", 16);
            }
            if (t < envaina) {
                Location obj = e.objetivo();
                if (obj != null) yaw = yaw(pie, obj);
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
                e.postura(Pose.STANDING);
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

    /** Entre ataque y ataque persigue y pega; pasado el respiro, elige segun la distancia. */
    private void dirigir(Player obj) {
        if (ticks < respiroHasta) return;
        double dx = obj.getLocation().getX() - cuerpo.getLocation().getX();
        double dz = obj.getLocation().getZ() - cuerpo.getLocation().getZ();
        Ataque x = elegir(Math.sqrt(dx * dx + dz * dz));
        if (x != null) empezar(nueva(x, ritmo()));
    }

    private Ataque elegir(double dist) {
        EnumMap<Ataque, Double> pesos = new EnumMap<>(Ataque.class);
        double total = 0;
        for (Ataque x : Ataque.values()) {
            if (ticks < lista.getOrDefault(x, 0L)) continue;
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
            }
            if (x == ultima) p *= 0.35;
            pesos.put(x, p);
            total += p;
        }
        if (pesos.isEmpty() || total <= 0) return null;
        double tirada = ThreadLocalRandom.current().nextDouble(total);
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

    /** /anomaly test (AmbushEdm): suelta ese ataque ya. False si no esta peleando. */
    boolean forzar(Ataque x) {
        if (estado != Estado.PELEA || x == null || quieto) return false;
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
     * Se va sin botin: la presa ha salido, se ha desconectado o ha muerto, o se acabo el tiempo.
     * Humo y el sonido de envainar.
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

    /** Retira todo lo suyo (idempotente): ataque, animador, barra, hojas, sombras, maniqui y cuerpo. */
    void limpiar() {
        if (actual != null) {
            Tecnica tec = actual;
            actual = null;
            try {
                tec.cortar(escena);
            } catch (Throwable ignorado) {
                // Lo que quede se quita justo debajo.
            }
        }
        if (animador != null) {
            animador.cancel();
            animador = null;
        }
        quitarBarra();
        for (ItemDisplay d : hojas.values()) Fx.safeRemove(d);
        hojas.clear();
        for (Mannequin m : sombras.values()) Fx.safeRemove(m);
        sombras.clear();
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

        private Location pecho() {
            Location c = cuerpo.getLocation().add(0, 1.0 * ESCALA, 0);
            c.setYaw(0);
            c.setPitch(0);
            return c;
        }

        @Override
        public int hoja(float yaw) {
            if (hojas.size() >= MAX_HOJAS) return -1;
            Location c = pecho();
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
                    e.setTransformation(hojaEn(yaw, RADIO_HOJA, ESCALA_HOJA));
                });
            } catch (Throwable t) {
                return -1;
            }
            int id = ++siguienteId;
            hojas.put(id, d);
            return id;
        }

        @Override
        public void hoja(int id, float yaw, int ticks) {
            ItemDisplay d = hojas.get(id);
            if (d == null || !d.isValid()) return;
            Location c = pecho();
            if (d.getLocation().distanceSquared(c) > 0.01) d.teleport(c);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(Math.max(1, ticks));
            d.setTransformation(hojaEn(yaw, RADIO_HOJA, ESCALA_HOJA));
        }

        @Override
        public void quitarHoja(int id) {
            Fx.safeRemove(hojas.remove(id));
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
                    // Con la marca del cuerpo: nadie la golpea ni la toca (Parca.onDanoCascara, onTocarCascara).
                    s.getPersistentDataContainer().set(Marcas.CASCARA, PersistentDataType.STRING, dueno);
                    s.setPersistent(false);
                    s.setGravity(false);
                    s.setCollidable(false);
                    s.setSilent(true);
                    s.setImmovable(true);
                    s.setInvulnerable(true);
                    s.setCustomNameVisible(false);
                    try {
                        s.setDescription(Component.empty());
                    } catch (Throwable ignorado) {
                        // Sin descripcion editable se ve la linea "NPC" un instante.
                    }
                    s.setProfile(npc.perfil());
                    CuerpoNpc.sinCapa(s);
                    Compat.setAttribute(s, "scale", npc.escala());
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

    /** Una escena de mentira: cuenta posturas, hojas y sombras, y no toca el mundo. */
    static final class EscenaPrueba implements Escena {
        Location pie = new Location(null, 0, 64, 0);
        Location objetivo;
        Pose postura = Pose.STANDING;
        boolean katana = true;
        final Set<Integer> hojas = new HashSet<>();
        final Set<Integer> sombras = new HashSet<>();
        int maxHojas;
        int sombrasHechas;
        private int siguiente;
        String skin;

        @Override public Location pie() { return pie.clone(); }
        @Override public Location objetivo() { return objetivo == null ? null : objetivo.clone(); }
        @Override public void mover(Location l) { pie = l.clone(); }
        @Override public void mirar(float yaw) { }

        @Override
        public void postura(Pose p) {
            // La misma regla que el maniqui: lo que no admite no cambia nada.
            if (p != null && CuerpoNpc.POSTURAS.contains(p)) postura = p;
        }

        @Override public void katana(boolean enLaMano) { katana = enLaMano; }
        @Override public void blandir() { }

        @Override
        public int hoja(float yaw) {
            if (hojas.size() >= MAX_HOJAS) return -1;
            int id = ++siguiente;
            hojas.add(id);
            maxHojas = Math.max(maxHojas, hojas.size());
            return id;
        }

        @Override public void hoja(int id, float yaw, int ticks) { }
        @Override public void quitarHoja(int id) { hojas.remove(id); }

        @Override
        public int sombra(Location l, float yaw, Pose pose) {
            int id = ++siguiente;
            sombras.add(id);
            sombrasHechas++;
            return id;
        }

        @Override public void quitarSombra(int id) { sombras.remove(id); }

        @Override
        public List<Location> ruta(Location desde, Vector dir, double largo) {
            List<Location> r = new ArrayList<>();
            for (double d = 0; d <= largo + 1e-9; d += 0.5) r.add(desde.clone().add(dir.clone().multiply(d)));
            return r;
        }

        @Override public Location suelo(Location l) { return l; }
        @Override public void polvo(Location l, int rgb, float tam) { }
        @Override public void particula(Particle p, Location l, int n, double dx, double dy, double dz, double v, Object datos) { }
        @Override public void sonido(Location l, String clave, float volumen, float tono) { }
        @Override public int herirCerca(Location centro, double radio, double k, boolean perdonaAgachados) { return 0; }
        @Override public int herirTramo(Location a, Location b, double ancho, double k, Set<UUID> tocados, Vector dir) { return 0; }
        @Override public void avisar(String texto, double radio) { }
        @Override public void avisarPresa(String texto) { }
        @Override public void cambiarSkin(String cuenta) { skin = cuenta; }

        /** Si queda algo del ataque: una postura que no es de pie, una hoja o una sombra. */
        boolean ocupada() {
            return postura != Pose.STANDING || !hojas.isEmpty() || !sombras.isEmpty();
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

    /**
     * Lo que pide Dosa de las animaciones: cada ataque, en las dos fases, acaba de pie y sin hojas
     * ni sombras (y nunca con mas de MAX_HOJAS hojas a la vez), tambien si se corta a medias.
     */
    static void autotestAnimaciones(Autotest.Hoja h) {
        for (double ritmo : new double[]{1.0, RITMO_FASE2}) {
            String f = ritmo < 1 ? " (fase 2)" : " (fase 1)";
            for (Ataque x : Ataque.values()) {
                EscenaPrueba e = new EscenaPrueba();
                e.objetivo = e.pie().add(x == Ataque.ACOMETIDA ? 8 : 2.5, 0, 0);
                boolean acabo = correr(nueva(x, ritmo), e, 600);
                h.ok(x.nombre + f + ": acaba de pie y sin hojas ni sombras (" + e.postura + ", "
                                + e.hojas.size() + " hojas, " + e.sombras.size() + " sombras)",
                        acabo && e.postura == Pose.STANDING && e.hojas.isEmpty() && e.sombras.isEmpty() && e.maxHojas <= MAX_HOJAS);

                EscenaPrueba m = new EscenaPrueba();
                m.objetivo = m.pie().add(x == Ataque.ACOMETIDA ? 8 : 2.5, 0, 0);
                Tecnica tec = nueva(x, ritmo);
                boolean acaboAntes = false, visto = false;
                for (int i = 0; i < 600 && !acaboAntes; i++) {
                    acaboAntes = tec.paso(m);
                    tec.t++;
                    // A medias: con la postura, la hoja o una sombra puestas, y un par de ticks despues.
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
                        visto && m.postura == Pose.STANDING && m.hojas.isEmpty() && m.sombras.isEmpty());
            }
        }
    }
}
