package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
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
import org.bukkit.entity.Player;
import org.bukkit.entity.Zoglin;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Una pelea contra el Vigilante. El gestor es Vigilante.
 *
 * El cuerpo es un Zoglin de verdad a cuerpo.escala (2,6) veces su tamano, sin nada pegado ni flotando
 * alrededor, sin brillo y sin luces: una bestia de la tierra. Es una amenaza de Amenazas (vida logica,
 * tope por golpe del 8 %, dano logico para el botin, marca lethal_world:amenaza; solo le hacen dano los
 * jugadores, asi que caidas, asfixia, lava y fuego no le hacen nada) y no se transforma, no se hace bebe
 * ni desaparece por lejania.
 *
 * Se mueve con su IA de vanilla (su velocidad es un atributo, sin teletransportes cada tick) y va a por
 * su presa: el cerebro del Zoglin no lee el setTarget de Bukkit, asi que el objetivo se le escribe en
 * su memoria (Cerebro). Entre golpe y golpe muerde y cornea como un Zoglin, que ya lanza por el aire.
 * Mientras prepara una habilidad no anda (velocidad 0) pero sigue con su fisica: los saltos y las
 * embestidas son velocidades de verdad, y el cliente las ve fluidas.
 *
 * Las habilidades (Habilidad) avisan siempre en el suelo al menos un segundo (Ajustes.AVISO_MINIMO):
 * el suelo se agrieta (particulas del propio bloque) dentro de un circulo o una linea de polvo que
 * pasa del color de la tierra al de la sangre seca, y cada una tiene su sonido de aviso. Los golpes
 * fuertes quitan una fraccion de tu vida maxima entre golpes.minimo y golpes.maximo, por DanoVerdadero.
 * Quien sale volando por un golpe suyo no recibe dano de caida (CAIDA): la caida no puede matar con la
 * vida llena lo que el golpe no mato (ley 5).
 *
 * Fases por vida (Vigilante.faseDe): I Acecho, II Bajo tierra (se hunde y ruge), III Demolicion (el
 * martillazo encadena la embestida) y IV Furia (mas rapido, dos martillazos seguidos y hundimientos
 * mas frecuentes).
 *
 * Los bloques que saltan del suelo son FallingBlock efimeros: no se colocan al caer (cancelDrop), no
 * sueltan nada (dropItem), llevan la marca lethal_world:vigilante_pieza (Vigilante cancela su
 * EntityChangeBlockEvent por si acaso) y se retiran a efimeros.vida-ticks. Hay un tope por golpe y otro
 * por Vigilante. Nada toca un bloque del mundo.
 *
 * Ambiente de Halloween mientras dura: lamentos lejanos, susurros, la cueva y la respiracion de la
 * bestia, todo con sonidos de vanilla y graves. Nunca campanas: la campana es solo de la Parca (ley 2).
 *
 * Una sola tarea: la de 2 ticks de Amenazas (registrarPelea).
 */
final class PeleaVigilante implements Runnable {

    enum Estado { APARECE, PELEA, FIN }

    /**
     * Las habilidades, con lo que ensena el menu de /anomaly (VigilanteType). clave = su seccion en
     * hardcore.vigilante.habilidades; alias = el nombre en ingles del comando de staff.
     */
    enum Habilidad {
        MARTILLAZO("vi_martillazo", "slam", "martillazo", "Martillazo",
                "Salta muy alto y cae aplastando el suelo: cerca te lanza al cielo; más lejos, te barre.",
                1, 4, 140, 20, 0.30, 5, Material.MACE, 60),
        LANZAMIENTO("vi_lanzamiento", "toss", "lanzamiento", "Cabezazo",
                "Agacha la cabeza, embiste a uno solo y lo manda por los aires.",
                1, 4, 100, 20, 0.30, 4, Material.FEATHER, 40),
        EMBESTIDA("vi_embestida", "charge", "embestida", "Embestida",
                "Marca una línea en el suelo y la cruza a toda velocidad, apartando a quien alcance.",
                1, 4, 160, 20, 0.35, 4, Material.ANVIL, 50),
        HUNDIMIENTO("vi_hundimiento", "burrow", "hundimiento", "Hundimiento",
                "Se mete bajo tierra y sale debajo de uno de ustedes: si el suelo tiembla bajo tus pies, apártate.",
                2, 4, 320, 20, 0.30, 3, Material.ROOTED_DIRT, 120),
        RUGIDO("vi_rugido", "roar", "rugido", "Rugido",
                "Un grito largo y grave: te nubla la vista un instante y te quita cordura.",
                2, 4, 400, 20, 0, 2, Material.SCULK_SHRIEKER, 40);

        final String id, alias, clave, nombre, descripcion;
        final int faseDesde, faseHasta, espera, aviso, peso, duracion;
        final double fraccion;
        final Material icono;

        Habilidad(String id, String alias, String clave, String nombre, String descripcion, int faseDesde, int faseHasta,
                  int espera, int aviso, double fraccion, int peso, Material icono, int duracion) {
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

    /** Donde pilla a cada uno el martillazo, por su distancia al sitio donde cae. */
    enum Zona { CERCA, MEDIO, LEJOS, FUERA }

    // ------------------------------------------------------------ numeros

    /** Los avisos en el suelo: del polvo de tierra a la sangre seca segun se acerca el golpe. */
    static final int RGB_AVISO_DESDE = 0xA89279, RGB_AVISO_HASTA = 0x8E1E1E;
    /** La caja de un Zoglin a escala 1 (vanilla): la suya es esto por cuerpo.escala. */
    static final double ZOGLIN_ANCHO = 1.3965, ZOGLIN_ALTO = 1.4;
    /** La fisica de vanilla por tick: gravedad, freno vertical, freno en el aire y en el suelo normal. */
    static final double GRAVEDAD = 0.08, ROCE_VERTICAL = 0.98, ROCE_AIRE = 0.91, ROCE_SUELO = 0.546;
    /** Lo mas que se le da a una velocidad por eje: el paquete de velocidad del cliente no pasa de aqui. */
    static final double VELOCIDAD_MAXIMA = 3.9;
    /** Lo que tarda en salir del todo de la tierra al aparecer, tras el estallido. */
    static final int SUBIDA_TICKS = 30;
    /** Lo que levanta el empujon del martillazo a media distancia: lo justo para que vuele lejos sin frenar en el suelo. */
    static final double EMPUJE_MEDIO_ALTO = 0.6;
    /** Lo hondo que espera bajo tierra (bajo el sitio de salida): su cartel de nombre tampoco asoma. */
    static final double HONDO = 2.6;
    /** Cuanto dura el perdon de la caida tras salir volando por un golpe suyo (ticks del servidor). */
    private static final long PERDON_CAIDA = 240;

    private final Vigilante gestor;
    private final Hardcore hc;
    private final Vigilante.Ajustes a;
    /** Null en la de prueba (/anomaly o /calamity vigilant test). */
    final UUID presa;
    final String presaNombre;
    final boolean prueba;
    final Vigilante.Escala escala;
    private final double golpe;
    /** Lo que mide su caja a cuerpo.escala. */
    private final double ancho, alto;

    Zoglin cuerpo;
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

    private Tecnica actual;
    private Habilidad siguiente;
    /** La que va justo despues de la que esta en curso (el martillazo y la embestida de la fase III). */
    private Habilidad encadenar;
    private final EnumMap<Habilidad, Long> listo = new EnumMap<>(Habilidad.class);
    private Habilidad ultima;
    private long respiroHasta;
    private long sinNadieDesde = -1;
    private long aturdidoHasta;
    private int jugadoresContados;
    private final Set<UUID> participantes = new HashSet<>();

    /** El atasco: desde cuando no se aleja mas de bloque y medio de este sitio. */
    private Location anclaAtasco;
    private long anclaDesde;
    private long ultimoEscape = -10_000;

    /** Las pisadas (va en silencio: sus sonidos los pone la pelea, graves) y el ambiente. */
    private Location ultimaPos;
    private double andado;
    private long proximoAmbiente;
    private long proximoGrunido;
    private long ultimoDolor = -100;

    /**
     * La ventana de golpes fuertes por jugador, COMPARTIDA entre todas las peleas: con dos Vigilantes
     * vivos, el golpe de uno y el del otro en el mismo tick tampoco pasan de golpes.maximo. El reloj es el
     * tick del servidor, no el de cada pelea. {tick de inicio, fraccion acumulada}.
     */
    private static final Map<UUID, double[]> VENTANA = new HashMap<>();
    /**
     * Quien ha salido volando por un golpe de un Vigilante: su siguiente caida no hace dano hasta ese tick
     * del servidor. Estatico: si el Vigilante muere con alguien en el aire, el perdon sigue valiendo.
     */
    private static final Map<UUID, Long> CAIDA = new HashMap<>();
    /** Permiso de vuelo prestado (para que el servidor no eche a quien lanza por el aire): hasta que tick. */
    private final Map<UUID, Long> vuelo = new HashMap<>();

    /** Un bloque del suelo que salta: se retira solo en "hasta" (tick de la pelea) si no ha caido antes. */
    private record Efimero(FallingBlock bloque, long hasta) {
    }

    private final List<Efimero> efimeros = new ArrayList<>();
    /** Las salpicaduras al herirle: carne de bestia (se crea al primer uso; sin servidor no existe). */
    private static BlockData carne;

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
    }

    /**
     * Lo pone enterrado bajo "sitio" (a ras de suelo, con hueco para su caja) y arranca la aparicion: el
     * suelo se agrieta, estalla y sale escarbando. Null si el spawn lo cancela alguien.
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
        Location bajo = enterrado(salida, HONDO);
        Zoglin z = am.invocar(Zoglin.class, bajo, Vigilante.AMENAZA, escala.nivel(), Paleta.vigilante("Vigilante"), e -> {
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
        });
        if (z == null) return false;
        cuerpo = z;
        am.vidaLogica(z, escala.vida());
        am.topeGolpe(z, a.topeGolpe);
        am.ancla(z, salida);
        am.registrarPelea(this);
        gestor.registrar(this);
        return true;
    }

    /** Donde esta justo bajo tierra para salir en "suelo" (nunca por debajo del fondo del mundo). */
    private Location enterrado(Location suelo) {
        return enterrado(suelo, 0);
    }

    /**
     * Lo mismo "extra" bloques mas hondo: mientras espera o viaja bajo tierra va a HONDO, para que el
     * cartel de nombre (que va encima de su caja) quede tambien bajo el suelo y no asome.
     */
    private Location enterrado(Location suelo, double extra) {
        Location l = suelo.clone().subtract(0, alto + 0.4 + extra, 0);
        double fondo = suelo.getWorld().getMinHeight() + 3;
        if (l.getY() < fondo) l.setY(fondo);
        return l;
    }

    // ================================================================ cada 2 ticks

    @Override
    public void run() {
        if (estado == Estado.FIN) {
            hc.amenazas().quitarPelea(this);
            return;
        }
        if (cuerpo == null || !cuerpo.isValid() || cuerpo.isDead()) {
            // Muerto lo cierra alMorir (EntityDeathEvent); aqui solo llega si alguien lo ha borrado o su chunk se ha ido.
            if (!pagada) irse("desaparece", null);
            return;
        }
        ticks += 2;
        boolean segundo = ++vueltas % 10 == 0;
        World w = cuerpo.getWorld();
        cortarVuelo();
        podarEfimeros();
        if (segundo) {
            refrescarBarra();
            devolverVuelo(false);
        } else if (barra != null) {
            barra.progress((float) Math.max(0, Math.min(1, Amenazas.fraccion(cuerpo))));
        }
        if (estado == Estado.APARECE) {
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
        int nueva = Vigilante.faseDe(Amenazas.fraccion(cuerpo), fase);
        if (nueva != fase) cambiarFase(nueva);

        if (actual != null) {
            Tecnica tec = actual;
            boolean fin;
            try {
                fin = tec.paso(w);
            } catch (Throwable t) {
                hc.plugin().getLogger().warning("[Calamity] Fallo en " + tec.h.nombre + " del Vigilante: " + t);
                try {
                    tec.cortar();
                } catch (Throwable ignorado) {
                    // Lo que no se pudo cortar lo retira limpiar() al acabar.
                }
                fin = true;
            }
            tec.t += 2;
            if (fin && actual == tec) acabar(tec.h);
        } else if (aturdido()) {
            if (vueltas % 3 == 0) {
                Compat.spawn(w, Compat.SMOKE, pie().add(0, alto + 0.2, 0), 4, ancho * 0.25, 0.1, ancho * 0.25, 0.01);
            }
        } else {
            if (aturdidoHasta > 0) finAturdido();
            dirigir(segundo);
        }
        pasos(w);
        ambiente(w);
    }

    /**
     * La aparicion: emerger-aviso-ticks de suelo agrietandose donde va a salir (el aviso del estallido),
     * el estallido (bloques que saltan, golpe a quien este encima) y SUBIDA_TICKS saliendo de la tierra.
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
        if (s % 4 == 0) {
            Compat.spawn(w, Compat.BLOCK, salida.clone().add(0, 0.2, 0), 12, ancho * 0.45, 0.1, ancho * 0.45, 0.15, sueloSalida);
            Compat.spawn(w, Compat.LARGE_SMOKE, salida.clone().add(0, 0.4, 0), 3, ancho * 0.4, 0.2, ancho * 0.4, 0.01);
        }
        if (s % 8 == 0 && s < SUBIDA_TICKS - 6) saltarAnillo(salida, ancho * 0.5, 2, 0.6);
        if (s == 12) {
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
        Location l = salida.clone();
        l.setYaw(yawSalida);
        mover(l);
        cuerpo.setInvulnerable(false);
        cuerpo.setAI(true);
        anclaAtasco = cuerpo.getLocation();
        anclaDesde = ticks;
        liberar();
        nombreBarra();
    }

    /** Cada segundo: si queda alguien con quien pelear a radio-pelea. Si no, en abandono-segundos se va. */
    private boolean revisarGente() {
        boolean hay = false;
        for (Player p : Fx.playersNear(cuerpo.getLocation(), a.radioPelea)) {
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
        Map<UUID, Double> dano = hc.amenazas().danoLogico(cuerpo);
        double vida = hc.amenazas().vidaLogicaMaxima(cuerpo);
        for (Map.Entry<UUID, Double> e : dano.entrySet()) {
            if (e.getValue() < a.participacion * vida || !participantes.add(e.getKey())) continue;
            if (participantes.size() <= jugadoresContados || jugadoresContados >= a.jugadoresTope) continue;
            jugadoresContados++;
            double antes = 1 + a.porJugador * (jugadoresContados - 2);
            double ahora = 1 + a.porJugador * (jugadoresContados - 1);
            double f = Amenazas.fraccion(cuerpo);
            hc.amenazas().vidaLogica(cuerpo, vida * ahora / antes);
            hc.amenazas().ponerFraccion(cuerpo, f);
            vida = hc.amenazas().vidaLogicaMaxima(cuerpo);
            hc.plugin().bitacora().anotar("vigilante", "grupo", presaNombre, String.valueOf(jugadoresContados));
        }
    }

    private static String nombreFase(int f) {
        return switch (f) {
            case 1 -> "Acecho";
            case 2 -> "Bajo tierra";
            case 3 -> "Demolición";
            default -> "Furia";
        };
    }

    private static String consejoFase(int f) {
        return switch (f) {
            case 2 -> "Se mete bajo tierra: si el suelo tiembla bajo tus pies, apártate.";
            case 3 -> "Encadena el martillazo con la embestida: sal de su línea.";
            default -> "Entra en furia: más rápido y dos martillazos seguidos.";
        };
    }

    private void cambiarFase(int nueva) {
        fase = nueva;
        cortarTecnica();
        siguiente = switch (nueva) {
            case 2 -> Habilidad.RUGIDO;
            case 3, 4 -> Habilidad.MARTILLAZO;
            default -> null;
        };
        if (nueva >= 4 && !furia) {
            furia = true;
            if (actual == null && !aturdido()) Compat.setAttribute(cuerpo, "movement_speed", velocidadActual());
        }
        respiroHasta = ticks + 10;
        Location l = cuerpo.getLocation();
        World w = l.getWorld();
        Compat.soundPlayers(w, l, "entity.hoglin.angry", 2.2f, 0.4f);
        Compat.sound(w, l, "entity.zoglin.hurt", 2.0f, 0.45f);
        Compat.spawn(w, Compat.BLOCK, l.clone().add(0, alto * 0.5, 0), 30, ancho * 0.35, alto * 0.3, ancho * 0.35, 0.1, carne());
        Compat.spawn(w, Compat.LARGE_SMOKE, l.clone().add(0, 0.4, 0), 16, ancho * 0.5, 0.2, ancho * 0.5, 0.02);
        Component c = Paleta.vigilante("Fase " + Parca.romano(nueva) + " · " + nombreFase(nueva))
                .append(Component.text(" · ", Paleta.SEPARADOR)).append(Component.text(consejoFase(nueva), Paleta.TEXTO));
        for (Player p : Fx.viewersNear(l, 48)) hc.barra().aviso(p, c, 4);
        nombreBarra();
        hc.plugin().bitacora().anotar("vigilante", "fase", String.valueOf(nueva), presaNombre, "N " + escala.nivel());
    }

    // ================================================================ decidir

    private void dirigir(boolean segundo) {
        Player obj = objetivo();
        if (obj == null) {
            if (segundo) Cerebro.soltar(cuerpo);
            return;
        }
        if (segundo) {
            forzarObjetivo(obj);
            if (revisarAtasco(obj)) return;
        }
        if (ticks < respiroHasta) return;
        double dist = PeleaAmbush.distPlano(cuerpo.getLocation(), obj.getLocation());
        Habilidad h = null;
        if (encadenar != null) {
            Habilidad e = encadenar;
            encadenar = null;
            if (e.enFase(fase) && puede(e, obj, dist)) h = e;
            else if (puede(Habilidad.LANZAMIENTO, obj, dist)) h = Habilidad.LANZAMIENTO;
        }
        if (h == null && siguiente != null && siguiente.enFase(fase) && puede(siguiente, obj, dist)) {
            h = siguiente;
            siguiente = null;
        }
        if (h == null) {
            List<Habilidad> cand = new ArrayList<>();
            for (Habilidad x : Habilidad.values()) {
                if (!x.enFase(fase) || ticks < listo.getOrDefault(x, 0L) || x.peso <= 0) continue;
                if (puede(x, obj, dist)) cand.add(x);
            }
            h = sortear(cand, ultima, furia, ThreadLocalRandom.current().nextDouble());
        }
        if (h != null) empezar(h, obj, false);
    }

    /**
     * Cada segundo: si lleva cuerpo.atasco-segundos sin alejarse bloque y medio del mismo sitio y sin
     * estar pegado a su objetivo, no cabe por donde va (su caja escalada es grande): se hunde y sale
     * debajo de el. Como mucho una vez cada 8 s.
     */
    private boolean revisarAtasco(Player obj) {
        Location l = cuerpo.getLocation();
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
            case EMBESTIDA -> new Embestida(obj);
            case HUNDIMIENTO -> new Hundimiento(obj, escape);
            case RUGIDO -> new Rugido();
        };
        actual = tec;
        sujetar();
        listo.put(h, ticks + esperaEfectiva(h, a.hab(h).espera(), furia, a.furiaEspera));
        ultima = h;
        nombreBarra();
    }

    private void acabar(Habilidad h) {
        actual = null;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        respiroHasta = ticks + (furia ? 6 + r.nextInt(8) : 14 + r.nextInt(12));
        // Fase III en adelante: tras el martillazo, la embestida.
        if (h == Habilidad.MARTILLAZO && fase >= 3) {
            encadenar = Habilidad.EMBESTIDA;
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
        if (aturdido()) aturdidoHasta = ticks;
        cortarTecnica();
        empezar(h, obj, false);
        return null;
    }

    /** Si ahora mismo no persigue con su IA (aparece, una habilidad o aturdido): nadie le cambia el objetivo. */
    boolean ocupado() {
        return estado != Estado.PELEA || actual != null || aturdido();
    }

    // ================================================================ cuerpo, IA y objetivo

    private double velocidadActual() {
        return a.velocidad * (furia ? a.furiaVelocidad : 1);
    }

    /** Quieto para una habilidad: sin velocidad de andar ni objetivo (su fisica sigue, para saltar y embestir). */
    private void sujetar() {
        if (cuerpo == null || !cuerpo.isValid()) return;
        Compat.setAttribute(cuerpo, "movement_speed", 0);
        Cerebro.soltar(cuerpo);
    }

    /** Vuelve a perseguir con su IA. */
    private void liberar() {
        if (cuerpo == null || !cuerpo.isValid() || estado != Estado.PELEA) return;
        if (!cuerpo.hasAI()) cuerpo.setAI(true);
        Compat.setAttribute(cuerpo, "movement_speed", velocidadActual());
        Player obj = objetivo();
        if (obj != null) forzarObjetivo(obj);
    }

    private void forzarObjetivo(Player obj) {
        if (cuerpo == null || obj == null) return;
        if (Cerebro.apuntar(cuerpo, obj)) return;
        // Sin acceso a su cerebro: lo de Bukkit, y que ande hacia el con el Pathfinder de Paper.
        try {
            cuerpo.setTarget(obj);
            if (PeleaAmbush.distPlano(cuerpo.getLocation(), obj.getLocation()) > 3) cuerpo.getPathfinder().moveTo(obj, 1.0);
        } catch (Throwable ignorado) {
            // Sin objetivo forzado le queda el suyo: el jugador mas cercano.
        }
    }

    /**
     * El cerebro del Zoglin (Brain de vanilla): Mob#setTarget de Bukkit solo cambia un campo que su
     * cerebro no lee. Aqui, por reflexion y con los nombres de Mojang que usa Paper 26, lo mismo que hace
     * el propio Zoglin cuando le pegan: borra CANT_REACH_WALK_TARGET_SINCE y pone ATTACK_TARGET con
     * caducidad. Si los nombres cambian algun dia, se apaga solo y queda setTarget mas el Pathfinder.
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
        return p != null && Fx.isFightable(p) && cuerpo != null && p.getWorld() == cuerpo.getWorld() && !hc.enSpawn(p);
    }

    /** Si puede ser su objetivo (lo mira el EntityTargetEvent de Vigilante). */
    boolean objetivoValido(Player p) {
        return valido(p);
    }

    /** A quien va: su presa si esta a 40 bloques; si no, el mas cercano. */
    Player objetivo() {
        if (cuerpo == null) return null;
        Location l = cuerpo.getLocation();
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

    /** Si ese jugador esta a "radio" de el (para la ley 6: Vigilante.persigue). */
    boolean cerca(Player p, double radio) {
        return p != null && cuerpo != null && cuerpo.isValid() && p.getWorld() == cuerpo.getWorld()
                && p.getLocation().distanceSquared(cuerpo.getLocation()) <= radio * radio;
    }

    private Location pie() {
        return cuerpo.getLocation();
    }

    /** Lo gira hacia "yaw" (cuerpo y cabeza) mientras prepara algo. */
    private void mirar(float yaw) {
        try {
            cuerpo.setRotation(yaw, 0);
            cuerpo.setBodyYaw(yaw);
        } catch (Throwable ignorado) {
            // Sin girar el cuerpo, el aviso del suelo dice igual a donde va el golpe.
        }
    }

    private boolean mover(Location l) {
        if (l == null || hc.enSpawn(l) || !Vigilante.cargado(l)) return false;
        return hc.amenazas().teleportar(cuerpo, l);
    }

    private void impulsar(Vector v) {
        try {
            cuerpo.setVelocity(limitar(v));
        } catch (Throwable ignorado) {
            // Una velocidad rara (NaN) no se aplica: se queda donde esta.
        }
    }

    /** La cabeza de un Zoglin que cornea (vanilla: el mismo efecto que su ataque). */
    private void anim() {
        try {
            cuerpo.playEffect(EntityEffect.ENTITY_ATTACK);
        } catch (Throwable ignorado) {
            // Sin la animacion, el golpe se ve en el suelo.
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
        if (v == null || !Fx.isFightable(v) || hc.enSpawn(v) || cuerpo == null) return;
        double f = Vigilante.fraccionGolpe(base, escala.multFraccion(), a);
        long ahora = org.bukkit.Bukkit.getCurrentTick();
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
        DanoVerdadero.aplicar(v, entra * vidaMax, a.golpeMaximo, cuerpo, etiqueta);
    }

    /** Ninguna velocidad pasa del tope del paquete por eje. */
    static Vector limitar(Vector v) {
        return new Vector(Fx.clamp(v.getX(), -VELOCIDAD_MAXIMA, VELOCIDAD_MAXIMA),
                Fx.clamp(v.getY(), -VELOCIDAD_MAXIMA, VELOCIDAD_MAXIMA), Fx.clamp(v.getZ(), -VELOCIDAD_MAXIMA, VELOCIDAD_MAXIMA));
    }

    /**
     * Un empujon (o una velocidad exacta). Si levanta, presta el vuelo un momento para que el servidor no
     * lo eche; si es fuerte, perdona la caida que venga.
     */
    private void empujar(Player v, Vector vel, boolean exacta) {
        if (v == null || !Fx.isFightable(v) || hc.enSpawn(v)) return;
        try {
            Vector lim = limitar(vel);
            if (lim.getY() > 0.1) prestarVuelo(v, lim.getY() > 1 ? 120 : 60);
            if (lim.getY() > 0.3 || Math.hypot(lim.getX(), lim.getZ()) > 0.9) perdonarCaida(v);
            v.setVelocity(exacta ? lim : limitar(v.getVelocity().add(lim)));
        } catch (Throwable ignorado) {
            // Sin empujon el golpe ya ha entrado.
        }
    }

    /** Lo manda hacia arriba "altura" bloques (y un poco en "plano"), con su caida perdonada. */
    private void lanzar(Player v, Vector plano, double altura) {
        empujar(v, plano.clone().setY(velocidadParaAltura(altura)), true);
    }

    private static void perdonarCaida(Player v) {
        long ahora = org.bukkit.Bukkit.getCurrentTick();
        if (CAIDA.size() > 64) CAIDA.values().removeIf(h -> h < ahora);
        CAIDA.put(v.getUniqueId(), ahora + PERDON_CAIDA);
    }

    /**
     * Si su caida no hace dano (Vigilante.onCaida): salio volando por un golpe de un Vigilante hace poco.
     * Se gasta al usarse: un perdon por vuelo.
     */
    static boolean perdonaCaida(UUID u) {
        Long h = CAIDA.remove(u);
        return h != null && h >= org.bukkit.Bukkit.getCurrentTick();
    }

    private void prestarVuelo(Player v, int duracion) {
        if (vuelo.containsKey(v.getUniqueId())) {
            vuelo.put(v.getUniqueId(), Math.max(vuelo.get(v.getUniqueId()), ticks + duracion));
            return;
        }
        if (v.getAllowFlight()) return;
        vuelo.put(v.getUniqueId(), ticks + duracion);
        v.setAllowFlight(true);
    }

    /** Con el vuelo prestado nadie vuela de verdad: si lo intenta, se le baja. */
    private void cortarVuelo() {
        if (vuelo.isEmpty()) return;
        for (UUID u : vuelo.keySet()) {
            Player p = hc.plugin().getServer().getPlayer(u);
            if (p != null && p.isFlying()) p.setFlying(false);
        }
    }

    /** Devuelve el vuelo prestado que ya caduco (todos, con todos = true). */
    private void devolverVuelo(boolean todos) {
        if (vuelo.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, Long>> it = vuelo.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            if (!todos && e.getValue() > ticks) continue;
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

    /** Su mordisco de vanilla ha entrado (Vigilante.onGolpe): su sonido, grave, y la caida perdonada. */
    void haGolpeado(Player v) {
        if (cuerpo == null) return;
        Compat.sound(cuerpo.getWorld(), cuerpo.getLocation(), "entity.zoglin.attack", 1.8f, 0.55f);
        if (v != null) perdonarCaida(v);
    }

    boolean aturdido() {
        return aturdidoHasta > ticks;
    }

    double factorRecibido() {
        return aturdido() ? 1 + a.aturdidoExtra : 1.0;
    }

    /** Dano de verdad: una salpicadura de carne y un quejido grave (como mucho cada medio segundo). */
    void dolor() {
        if (cuerpo == null) return;
        World w = cuerpo.getWorld();
        Compat.spawn(w, Compat.BLOCK, cuerpo.getLocation().add(0, alto * 0.55, 0), 8, ancho * 0.3, alto * 0.25, ancho * 0.3, 0.05,
                carne());
        if (ticks - ultimoDolor >= 10) {
            ultimoDolor = ticks;
            Compat.sound(w, cuerpo.getLocation(), "entity.zoglin.hurt", 1.6f, 0.55f);
        }
    }

    private void aturdir(long duracion) {
        if (duracion <= 0) return;
        aturdidoHasta = ticks + duracion;
        sujetar();
        Location l = cuerpo.getLocation();
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
        String marca = cuerpo == null ? "vigilante" : cuerpo.getUniqueId().toString();
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

    /** Las pisadas: va en silencio, asi que suenan aqui, graves, con polvo, cada 2,4 bloques andados. */
    private void pasos(World w) {
        Location l = cuerpo.getLocation();
        if (ultimaPos != null && ultimaPos.getWorld() == l.getWorld() && !cuerpo.isInvisible()) {
            double d = PeleaAmbush.distPlano(ultimaPos, l);
            if (d < 4 && enSuelo(cuerpo)) andado += d;
            if (andado >= 2.4) {
                andado = 0;
                Compat.sound(w, l, "entity.ravager.step", 1.2f, 0.55f);
                Compat.spawn(w, Compat.BLOCK, l.clone().add(0, 0.1, 0), 5, ancho * 0.35, 0.05, ancho * 0.35, 0, materialSuelo(l));
            }
        }
        ultimaPos = l;
    }

    /**
     * El ambiente de Halloween: cada ambiente-segundos (con algo de azar) un lamento lejano, un susurro que
     * solo oye uno, la cueva o la respiracion de la bestia; su grunido cada pocos segundos y, en furia, un
     * latido. Todo vanilla y grave. Nunca campanas (ley 2: la campana es de la Parca).
     */
    private void ambiente(World w) {
        if (cuerpo.isInvisible()) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location l = cuerpo.getLocation();
        if (ticks >= proximoGrunido) {
            proximoGrunido = ticks + 70 + r.nextInt(60);
            Compat.sound(w, l, r.nextBoolean() ? "entity.zoglin.ambient" : "entity.hoglin.ambient", 1.7f, 0.45f + r.nextFloat() * 0.1f);
        }
        if (furia && vueltas % 15 == 0) Compat.sound(w, l, "entity.warden.heartbeat", 1.6f, 0.6f);
        if (ticks < proximoAmbiente) return;
        proximoAmbiente = ticks + a.ambienteTicks + r.nextInt(Math.max(1, a.ambienteTicks / 2));
        List<Player> cerca = validosCerca(l, a.radioPelea);
        if (cerca.isEmpty()) return;
        Player p = cerca.get(r.nextInt(cerca.size()));
        Location pl = p.getLocation();
        Vector lejos = PeleaAmbush.dir(r.nextFloat() * 360f).multiply(18 + r.nextInt(8));
        switch (r.nextInt(5)) {
            case 0 -> Compat.sound(w, pl.clone().add(lejos), "entity.ghast.ambient", 2.0f, 0.5f);
            case 1 -> p.playSound(pl.clone().add(r.nextDouble(-2, 2), 1, r.nextDouble(-2, 2)), "entity.vex.ambient",
                    SoundCategory.HOSTILE, 0.5f, 0.6f);
            case 2 -> p.playSound(pl, "ambient.cave", SoundCategory.AMBIENT, 0.8f, 0.5f);
            case 3 -> Compat.sound(w, pl.clone().add(lejos), "entity.elder_guardian.ambient", 1.6f, 0.5f);
            default -> Compat.sound(w, l, "entity.ravager.ambient", 1.6f, 0.45f);
        }
    }

    // ================================================================ huecos para su caja

    static double anchoDe(double escala) {
        return ZOGLIN_ANCHO * escala;
    }

    static double altoDe(double escala) {
        return ZOGLIN_ALTO * escala;
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
     * Martillazo: se agacha (el sitio donde va a caer se agrieta en dos circulos), salta muy alto hacia
     * su objetivo y cae aplastando el suelo. Por distancia al sitio: cerca (radio-cerca) sale lanzado
     * hacia arriba altura-lanzado bloques; a media distancia (radio-medio) sale empujado muy lejos; mas
     * lejos (radio-lejos), un empujon leve. Una onda de polvo y bloques corre por el suelo. En furia,
     * dos seguidos (cada uno con su aviso).
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
            long vuelo = t - tSalto;
            if (vuelo % 4 == 0) pintar(w, cerca, RGB_AVISO_HASTA, bd);
            if (vuelo % 8 == 0) pintar(w, medio, PeleaParca.mezcla(RGB_AVISO_DESDE, RGB_AVISO_HASTA, 0.5), null);
            boolean tierra = enSuelo(cuerpo);
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
            Compat.soundPlayers(w, base, "entity.zoglin.angry", 2.2f, 0.5f);
            Compat.sound(w, base, "entity.hoglin.attack", 1.8f, 0.45f);
            estallidoSuelo(w, base, ancho * 0.4, Math.min(4, a.efimerosPorGolpe / 3), 0.6);
        }

        private void golpear(World w) {
            Location aqui = pie();
            boolean honesto = PeleaAmbush.distPlano(aqui, destino) <= 3 && Math.abs(aqui.getY() - destino.getY()) <= 3;
            // El golpe va donde se aviso; si cayo lejos (un muro le corto el salto), solo empuja.
            Location c = honesto ? destino : aqui;
            anim();
            Compat.soundPlayers(w, c, "entity.generic.explode", 2.2f, 0.6f);
            Compat.soundPlayers(w, c, "entity.warden.attack_impact", 2.2f, 0.5f);
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
            impulsar(v0.setY(enSuelo(cuerpo) ? 0 : Math.min(0, cuerpo.getVelocity().getY())));
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
     * Embestida: marca una franja recta en el suelo (embestida.largo) y la cruza a toda velocidad
     * (embestida.velocidad bloques por tick). A quien pille lo aparta lejos hacia un lado y hacia delante.
     * Si se estrella contra una pared, queda aturdido (embestida.aturdido-segundos) y recibe mas dano.
     */
    private final class Embestida extends Tecnica {
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

        Embestida(Player obj) {
            super(Habilidad.EMBESTIDA);
            origen = pie();
            yaw = PeleaAmbush.yaw(origen, obj.getLocation());
            dir = PeleaAmbush.dir(yaw);
            marca = franja(origen, dir, a.embestidaLargo, ancho / 2 + 0.6);
            bd = materialSuelo(origen);
        }

        @Override
        boolean paso(World w) {
            mirar(yaw);
            if (t < aviso()) {
                if (t == 0) {
                    Compat.soundPlayers(w, origen, "entity.ravager.ambient", 2.0f, 0.5f);
                    Compat.sound(w, origen, "entity.hoglin.angry", 1.8f, 0.45f);
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
                antes = pie();
                Compat.soundPlayers(w, origen, "entity.zoglin.angry", 2.2f, 0.5f);
                Compat.sound(w, origen, "entity.hoglin.attack", 2.0f, 0.5f);
            }
            Vector v0 = dir.clone().multiply(impulsoSuelo(a.embestidaVelocidad));
            impulsar(v0.setY(enSuelo(cuerpo) ? 0 : Math.min(0, cuerpo.getVelocity().getY())));
            Location ahora = pie();
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
            return recorrido >= a.embestidaLargo || s > 70 ? frenar() : false;
        }

        private boolean frenar() {
            impulsar(dir.clone().multiply(0.2));
            return true;
        }

        /** Contra la pared: estruendo, el muro que suelta polvo y bloques, y aturdido. */
        private void choque(World w, Location aqui) {
            Location frente = aqui.clone().add(dir.clone().multiply(ancho * 0.6));
            Compat.soundPlayers(w, frente, "entity.warden.attack_impact", 2.2f, 0.45f);
            Compat.sound(w, frente, "entity.generic.explode", 1.4f, 0.7f);
            Compat.sound(w, aqui, "entity.zoglin.hurt", 1.8f, 0.45f);
            estallidoSuelo(w, frente, ancho * 0.3, 4, 0.6);
            sacudir(aqui, 14);
            impulsar(dir.clone().multiply(-0.3).setY(0.2));
            aturdir(a.aturdidoTicks);
        }
    }

    /**
     * Hundimiento: se mete bajo tierra (baja entre bloques que saltan), el suelo se mueve hacia uno de
     * ustedes al azar (o hacia su objetivo si se hundio por atasco), un circulo se agrieta bajo sus pies
     * (aviso) y sale de golpe debajo, lanzandolo hacia arriba (hundimiento.altura). Mientras esta bajo
     * tierra no recibe dano.
     */
    private final class Hundimiento extends Tecnica {
        static final int HUNDE = 24, SALE = 8, VIAJE_MAXIMO = 80;
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
                cuerpo.setAI(false);
                cuerpo.setInvulnerable(true);
                Compat.soundPlayers(w, inicio, "entity.warden.dig", 2.0f, 0.6f);
                Compat.sound(w, inicio, "entity.zoglin.angry", 1.8f, 0.45f);
                estallidoSuelo(w, inicio, ancho * 0.5, (a.efimerosPorGolpe + 1) / 2, 0.7);
                if (escape) {
                    Component c = Component.text("El ", Paleta.TEXTO).append(Component.text("Vigilante", Paleta.VIGILANTE))
                            .append(Component.text(" se hunde en la tierra.", Paleta.TEXTO));
                    for (Player p : Fx.viewersNear(inicio, 40)) hc.barra().aviso(p, c, 2);
                }
            }
            double k = Math.min(1, (t + 2) / (double) HUNDE);
            Location l = inicio.clone().subtract(0, (alto + 0.4) * k, 0);
            if (l.getY() < w.getMinHeight() + 3) l.setY(w.getMinHeight() + 3);
            mover(l);
            Compat.spawn(w, Compat.BLOCK, inicio.clone().add(0, 0.2, 0), 10, ancho * 0.45, 0.1, ancho * 0.45, 0.15, materialSuelo(inicio));
            if (t % 8 == 4) saltarAnillo(inicio, ancho * 0.5, 2, 0.5);
            if (k < 1) return;
            // Mas hondo e invisible: ni una cueva lo ensena ni su cartel asoma por el suelo.
            Location hondo = enterrado(inicio, HONDO);
            hondo.setYaw(inicio.getYaw());
            mover(hondo);
            cuerpo.setInvisible(true);
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
            cuerpo.setInvisible(false);
            Compat.soundPlayers(w, fijo, "entity.generic.explode", 2.2f, 0.6f);
            Compat.soundPlayers(w, fijo, "entity.zoglin.angry", 2.2f, 0.45f);
            Compat.sound(w, fijo, "entity.warden.dig", 1.8f, 0.7f);
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
            Compat.spawn(w, Compat.BLOCK, hueco.clone().add(0, 0.2, 0), 10, ancho * 0.45, 0.1, ancho * 0.45, 0.15, materialSuelo(hueco));
            if (k < 1) return false;
            restaurar();
            return true;
        }

        /** Vuelve arriba visible y con IA (la habilidad se corta a medias: fase, staff o el fin de la pelea). */
        @Override
        void cortar() {
            if (etapa < 3 || hueco == null) {
                Location base = etapa == 0 ? inicio : fijo != null ? fijo : cabeza != null ? cabeza : inicio;
                Location h0 = PeleaVigilante.hueco(base, a, 4, l -> !hc.enSpawn(l));
                hueco = h0 != null ? h0 : inicio;
            }
            Location l = hueco.clone();
            l.setYaw(yawSalir);
            if (cuerpo != null && cuerpo.isValid()) mover(l);
            restaurar();
        }

        private void restaurar() {
            if (cuerpo == null || !cuerpo.isValid()) return;
            cuerpo.setInvisible(false);
            cuerpo.setInvulnerable(false);
            if (estado == Estado.PELEA) cuerpo.setAI(true);
            anclaAtasco = cuerpo.getLocation();
            anclaDesde = ticks;
        }
    }

    /**
     * Rugido: alza la cabeza y toma aire (aviso), y suelta un grito largo y grave (Zoglin, Ravager y
     * Ghast, todos bajos) que a rugido.radio da Oscuridad rugido.oscuridad-segundos (entre 1 y 3: la
     * oscuridad nunca es continua), quita rugido.cordura de cordura y empuja un poco. Sin dano.
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
                    cuerpo.setRotation(cuerpo.getLocation().getYaw(), -35);
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
        if (cuerpo == null) return;
        if (barra == null) barra = BossBar.bossBar(tituloBarra(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_20);
        barra.progress((float) Math.max(0, Math.min(1, Amenazas.fraccion(cuerpo))));
        nombreBarra();
        Set<UUID> ahora = new HashSet<>();
        for (Player p : Fx.viewersNear(cuerpo.getLocation(), 48)) ahora.add(p.getUniqueId());
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

    boolean esCuerpo(Entity e) {
        return e != null && cuerpo != null && cuerpo.getUniqueId().equals(e.getUniqueId());
    }

    boolean enMundo(World w) {
        return w != null && cuerpo != null && cuerpo.getWorld() == w;
    }

    int fase() {
        return fase;
    }

    String estadoTexto() {
        if (cuerpo == null) return "sin cuerpo";
        long s = estado == Estado.PELEA ? (ticks - inicioPelea) / 20 : 0;
        Location l = cuerpo.getLocation();
        return presaNombre + " | " + (estado == Estado.APARECE ? "saliendo" : actual != null ? actual.h.nombre
                : aturdido() ? "aturdido" : "pelea") + " | fase " + fase + (furia ? " (furia)" : "") + " | vida "
                + Math.round(Amenazas.fraccion(cuerpo) * 100) + " % | bloques en el aire " + efimeros.size() + " | " + s / 60 + ":"
                + String.format(Locale.ROOT, "%02d", s % 60) + " | " + l.getWorld().getName() + " " + l.getBlockX() + " "
                + l.getBlockY() + " " + l.getBlockZ() + " | " + escala.texto();
    }

    // ================================================================ fin

    /** Ha caido: el botin lo reparte el gestor con el dano logico (se lee aqui, en su EntityDeathEvent). */
    void alMorir() {
        if (pagada || estado == Estado.FIN) return;
        pagada = true;
        Map<UUID, Double> dano = hc.amenazas().danoLogico(cuerpo);
        double vida = hc.amenazas().vidaLogicaMaxima(cuerpo);
        long segundos = (System.currentTimeMillis() - nacio) / 1000;
        Location l = cuerpo.getLocation();
        World w = l.getWorld();
        cortarTecnica();
        // Cae como lo que es: un estruendo grave y la tierra que se lo traga a medias.
        Compat.soundPlayers(w, l, "entity.zoglin.death", 3.0f, 0.4f);
        Compat.sound(w, l, "entity.ravager.death", 2.2f, 0.5f);
        Compat.sound(w, l, "block.rooted_dirt.break", 2.0f, 0.5f);
        BlockData bd = materialSuelo(l);
        Compat.spawn(w, Compat.BLOCK, l.clone().add(0, 0.3, 0), 60, ancho * 0.6, 0.3, ancho * 0.6, 0.15, bd);
        Compat.spawn(w, Compat.DUST_PILLAR, l.clone().add(0, 0.2, 0), 30, ancho * 0.5, 0.1, ancho * 0.5, 0.25, bd);
        Compat.spawn(w, Compat.LARGE_SMOKE, l.clone().add(0, alto * 0.4, 0), 24, ancho * 0.5, alto * 0.3, ancho * 0.5, 0.02);
        hc.seguro("vigilante", () -> gestor.botin(this, dano, vida, segundos));
        limpiar();
    }

    /** Se va sin botin: tiempo, nadie cerca, el mundo se descarga o el staff. Se hunde en la tierra. */
    void irse(String motivo, Component aviso) {
        if (estado == Estado.FIN) return;
        cortarTecnica();
        if (cuerpo != null && cuerpo.isValid()) {
            World w = cuerpo.getWorld();
            Location l = cuerpo.getLocation();
            BlockData bd = materialSuelo(l);
            Compat.spawn(w, Compat.BLOCK, l.clone().add(0, 0.3, 0), 60, ancho * 0.5, 0.3, ancho * 0.5, 0.15, bd);
            Compat.spawn(w, Compat.LARGE_SMOKE, l.clone().add(0, 0.6, 0), 20, ancho * 0.5, 0.4, ancho * 0.5, 0.02);
            Compat.sound(w, l, "entity.warden.dig", 2.0f, 0.6f);
            Compat.sound(w, l, "entity.zoglin.ambient", 1.6f, 0.4f);
            if (aviso != null) for (Player o : Fx.viewersNear(l, 48)) o.sendMessage(aviso);
        }
        hc.plugin().bitacora().anotar("vigilante", "se-va", presaNombre, motivo, (System.currentTimeMillis() - nacio) / 1000 + " s");
        limpiar();
    }

    /**
     * Retira todo lo suyo (idempotente): habilidad, bloques que saltan, barra, vuelo prestado y el cuerpo
     * (si no esta muriendo: entonces vanilla lo tumba y lo quita). Pasan por aqui todos los finales: muere,
     * se va, se para el plugin (Vigilante.parar) o se descarga su mundo.
     */
    void limpiar() {
        Tecnica tec = actual;
        actual = null;
        if (tec != null) {
            try {
                tec.cortar();
            } catch (Throwable ignorado) {
                // Lo suyo se retira abajo igual.
            }
        }
        quitarEfimeros();
        quitarBarra();
        devolverVuelo(true);
        if (cuerpo != null && cuerpo.isValid() && !cuerpo.isDead()) Fx.safeRemove(cuerpo);
        if (hc.amenazas() != null) hc.amenazas().quitarPelea(this);
        estado = Estado.FIN;
    }

    // ================================================================ autotest

    /** Lo de la pelea que no necesita servidor: habilidades, fases, sorteo, golpes por distancia, saltos y la caja. */
    static void autotest(Autotest.Hoja h, Vigilante.Ajustes a) {
        Set<String> ids = new HashSet<>(), alias = new HashSet<>();
        boolean bien = true;
        for (Habilidad x : Habilidad.values()) {
            bien &= ids.add(x.id) && x.id.startsWith("vi_") && alias.add(x.alias) && x.alias.matches("[a-z]+");
            bien &= Habilidad.buscar(x.alias) == x && Habilidad.buscar(x.clave) == x && Habilidad.buscar(x.id) == x;
        }
        h.ok("habilidades: ids vi_ unicos, alias en ingles y se encuentran por id, alias y clave", bien);
        for (int f = 1; f <= 4; f++) {
            int n = 0;
            for (Habilidad x : Habilidad.values()) if (x.enFase(f) && x.peso > 0) n++;
            h.ok("fase " + f + ": " + n + " habilidades (>= 3)", n >= 3);
        }
        h.ok("fase 1: martillazo, cabezazo y embestida; sin hundimiento ni rugido", Habilidad.MARTILLAZO.enFase(1)
                && Habilidad.LANZAMIENTO.enFase(1) && Habilidad.EMBESTIDA.enFase(1) && !Habilidad.HUNDIMIENTO.enFase(1)
                && !Habilidad.RUGIDO.enFase(1));
        boolean todas = true;
        for (Habilidad x : Habilidad.values()) todas &= x.enFase(2) && x.enFase(3) && x.enFase(4);
        h.ok("de la fase II en adelante, las cinco", todas);
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
            Vigilante.Ajustes loco = new Vigilante.Ajustes(y);
            h.ok("config loca: lanzado como mucho 24 bloques", loco.martilloAltura <= 24);
            h.ok("config loca: el rugido no se repite antes de 15 s", loco.hab(Habilidad.RUGIDO).espera() >= 300);
            h.ok("config loca: oscuridad como mucho 3 s", loco.rugidoOscuridad <= 60);
            h.ok("config loca: sigue subiendo escalones (paso >= 1,1)", loco.paso >= 1.1);
            h.ok("config loca: escala como mucho 4", loco.escalaCuerpo <= 4);
            h.ok("config loca: como mucho 24 bloques por golpe y 64 a la vez", loco.efimerosPorGolpe <= 24 && loco.efimerosMaximo <= 64);
        }
    }
}
