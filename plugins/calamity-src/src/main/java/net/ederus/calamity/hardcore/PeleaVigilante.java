package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.EntityEffect;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Una pelea contra el Vigilante (Calamity 1.13.0). El gestor es Vigilante.
 *
 * El cuerpo es un golem de hierro de Amenazas (vida logica, tope por golpe del 8 %, dano logico para
 * el botin, marca lethal_world:amenaza) a escala-cuerpo (2,2) veces su tamano. Las grietas del golem
 * las pinta el cliente con la vida de la entidad, y con la vida logica de Amenazas esa fraccion es la
 * de verdad: se agrieta en el 75, el 50 y el 25 %, justo donde cambia de fase, sin forzar nada. Lleva un
 * ojo de luz en el pecho (BlockDisplay que brilla) y tres nucleos que le giran alrededor (BlockDisplay).
 * Todo lo que pone (ojo, nucleos, pilares, escombros) es no persistente, lleva la marca
 * lethal_world:vigilante_pieza y se retira en limpiar(), que es por donde pasan todos los finales.
 * Nada toca un bloque del mundo: los pilares son BlockDisplay y su cobertura se calcula aparte.
 *
 * La mirada: el ojo proyecta un cono de luz (mirada.angulo, mirada.alcance). Quien este dentro dos
 * segundos seguidos sin nada en medio (raytrace de bloques y los pilares) queda marcado: una corona de
 * luz sobre la cabeza, sus golpes fuertes van a el y le quitan golpes.marcado-extra mas.
 *
 * Fases por vida (Vigilante.faseDe): I Centinela, II Demoledor, III Ojo, IV Vigilante. Cada una abre
 * sus habilidades (Habilidad). Los golpes fuertes quitan una fraccion de tu vida maxima entre
 * golpes.minimo y golpes.maximo, por DanoVerdadero (la armadura no los para), siempre con al menos un
 * segundo de aviso en el suelo; entre golpe y golpe pega con la mano, con su IA, como un golem.
 *
 * Una sola tarea: la de 2 ticks de Amenazas (registrarPelea). Las piezas se mueven por teleport con
 * interpolacion de 2 ticks para que el cliente las vea fluidas.
 */
final class PeleaVigilante implements Runnable {

    enum Estado { APARECE, PELEA, FIN }

    /**
     * Las habilidades, con lo que ensena el menu de /anomaly (VigilanteType). clave = su seccion en
     * hardcore.vigilante.habilidades; alias = el nombre en ingles del comando de staff.
     */
    enum Habilidad {
        MACHAQUE("vi_machaque", "slam", "machaque", "Machaque",
                "Levanta los brazos y golpea el suelo delante de él: te lanza por el aire.",
                1, 4, 120, 24, 0.30, 5, Material.IRON_BLOCK, 30),
        SALTO("vi_salto", "leap", "salto", "Salto sísmico",
                "Marca dónde va a caer, salta y suelta una onda por el suelo que hay que saltar.",
                1, 4, 220, 24, 0.35, 3, Material.PISTON, 70),
        BARRIDO("vi_barrido", "sweep", "barrido", "Barrido del ojo",
                "Su ojo barre un abanico de luz: si te alcanza sin nada delante, te quema y te marca.",
                1, 4, 240, 24, 0.30, 4, Material.SPYGLASS, 44),
        EMBESTIDA("vi_embestida", "charge", "embestida", "Embestida",
                "Marca una línea en el suelo y la recorre de golpe; rompe los pilares que encuentra.",
                2, 4, 200, 24, 0.45, 4, Material.ANVIL, 50),
        ESCOMBROS("vi_escombros", "debris", "escombros", "Lluvia de escombros",
                "Caen piedras sobre los círculos marcados en el suelo.",
                2, 4, 260, 28, 0.30, 4, Material.COBBLED_DEEPSLATE, 60),
        PILARES("vi_pilares", "pillars", "pilares", "Pilares",
                "Levanta seis pilares de piedra que tapan su mirada. Se deshacen solos.",
                2, 3, 600, 0, 0, 2, Material.STONE_BRICKS, 20),
        RAYO("vi_rayo", "beam", "rayo", "Rayo del ojo",
                "Fija el ojo en un marcado y dispara: se corta si rompes la línea de visión.",
                3, 4, 240, 30, 0.45, 4, Material.END_ROD, 40),
        NUCLEOS("vi_nucleos", "cores", "nucleos", "Núcleos",
                "Suelta sus tres núcleos y no recibe daño hasta que los rompan. Si no llegan a tiempo, estallan.",
                3, 3, 900, 0, 0.50, 2, Material.RAW_GOLD_BLOCK, 300),
        TIRON("vi_tiron", "pull", "tiron", "Tirón magnético",
                "Atrae hacia él a los que pelean de lejos.",
                3, 4, 300, 24, 0, 3, Material.LODESTONE, 30),
        FARO("vi_faro", "lighthouse", "faro", "Faro",
                "Su ojo gira como un faro dos vueltas: cúbrete de la luz.",
                4, 4, 600, 20, 0.35, 2, Material.LANTERN, 180),
        ONDAS("vi_ondas", "waves", "ondas", "Tres ondas",
                "Golpea el suelo tres veces con ritmo: salta cada onda.",
                4, 4, 360, 20, 0.30, 4, Material.NOTE_BLOCK, 110),
        SENTENCIA("vi_sentencia", "sentence", "sentencia", "Sentencia",
                "Un círculo se cierra sobre su presa y él le cae encima: sal antes de que se cierre.",
                4, 4, 420, 60, 0.75, 3, Material.TARGET, 80);

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

        /** Por id (vi_rayo), alias en ingles (beam) o clave (rayo). */
        static Habilidad buscar(String nombre) {
            if (nombre == null) return null;
            String n = nombre.trim().toLowerCase(Locale.ROOT);
            for (Habilidad h : values()) if (h.id.equals(n) || h.alias.equals(n) || h.clave.equals(n)) return h;
            return null;
        }
    }

    // ------------------------------------------------------------ numeros

    /** El amarillo del ojo y de su luz. */
    static final int RGB_OJO = 0xFFD54A;
    /** Los avisos en el suelo: del amarillo claro al naranja rojizo segun se acerca el golpe. */
    static final int RGB_AVISO_DESDE = 0xFFF0A0, RGB_AVISO_HASTA = 0xFF6A1F;
    /** El rojo de los nucleos a punto de estallar. */
    static final int RGB_ESTALLIDO = 0xFF3B30;
    /** Lo que mide un pilar: ancho y alto, y el radio con el que tapa. */
    static final float PILAR_ANCHO = 1.6f, PILAR_ALTO = 4.5f;
    static final double PILAR_RADIO = 0.9;
    /** Cuanto tarda en aparecer (invulnerable, quieto) tras caer. */
    private static final long TICKS_APARECE = 40;

    private final Vigilante gestor;
    private final Hardcore hc;
    private final Vigilante.Ajustes a;
    /** Null en la de prueba (/anomaly o /calamity vigilant test). */
    final UUID presa;
    final String presaNombre;
    final boolean prueba;
    final Vigilante.Escala escala;
    private double golpe;

    IronGolem cuerpo;
    Estado estado = Estado.APARECE;
    private int fase = 1;
    private long ticks;
    private int vueltas;
    private long inicioPelea;
    private final long nacio = System.currentTimeMillis();
    private boolean pagada;

    private Tecnica actual;
    private Habilidad siguiente;
    private final EnumMap<Habilidad, Long> listo = new EnumMap<>(Habilidad.class);
    private Habilidad ultima;
    private long respiroHasta;
    private long ultimoGolpe;
    private long sinNadieDesde = -1;
    private long aturdidoHasta;
    private int jugadoresContados;
    private final Set<UUID> participantes = new HashSet<>();

    /** A donde mira el ojo este tick (lo pone la habilidad); null = a su objetivo. */
    private Float yawFijo;
    private float yawOjo;
    private final Map<UUID, Integer> visto = new HashMap<>();
    private final Map<UUID, Long> marcaHasta = new HashMap<>();
    /** Ventana de golpes fuertes por jugador: {tick de inicio, fraccion acumulada}. */
    private final Map<UUID, double[]> ventana = new HashMap<>();
    /** Permiso de vuelo prestado (para que el servidor no eche a quien lanza por el aire): hasta que tick. */
    private final Map<UUID, Long> vuelo = new HashMap<>();

    private BlockDisplay ojo;
    private final List<BlockDisplay> orbita = new ArrayList<>();
    private double giro;
    private boolean nucleosFuera;
    private final List<Pilar> pilares = new ArrayList<>();
    private final Map<UUID, Nucleo> nucleos = new LinkedHashMap<>();

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
    }

    /** Lo pone en el mundo en "sitio" y arranca la aparicion. Null si el spawn lo cancela alguien. */
    static PeleaVigilante crear(Vigilante gestor, UUID presa, String nombre, Vigilante.Escala escala, Location sitio, int jugadores) {
        if (sitio == null || sitio.getWorld() == null || !Vigilante.cargado(sitio)) return null;
        PeleaVigilante pe = new PeleaVigilante(gestor, presa, nombre, escala, jugadores);
        return pe.nacer(sitio) ? pe : null;
    }

    private boolean nacer(Location sitio) {
        Amenazas am = hc.amenazas();
        if (am == null) return false;
        Location l = sitio.clone();
        l.setPitch(0);
        Player cerca = presa != null ? hc.plugin().getServer().getPlayer(presa) : Fx.nearest(sitio, 32);
        if (cerca != null && cerca.getWorld() == l.getWorld()) l.setYaw(PeleaAmbush.yaw(l, cerca.getLocation()));
        IronGolem g = am.invocar(IronGolem.class, l, Vigilante.AMENAZA, escala.nivel(), Paleta.vigilante("Vigilante"), e -> {
            if (presa != null) e.getPersistentDataContainer().set(Marcas.PRESA, PersistentDataType.STRING, presa.toString());
            e.setPlayerCreated(false);
            e.setAI(false);
            e.setInvulnerable(true);
            Compat.setAttribute(e, "scale", a.escalaCuerpo);
            Compat.setAttribute(e, "knockback_resistance", 1.0);
            Compat.setAttribute(e, "movement_speed", a.velocidad);
            Compat.setAttribute(e, "follow_range", 48);
            Compat.setAttribute(e, "attack_damage", golpe);
        });
        if (g == null) return false;
        cuerpo = g;
        am.vidaLogica(g, escala.vida());
        am.topeGolpe(g, a.topeGolpe);
        am.ancla(g, sitio);
        yawOjo = l.getYaw();
        crearOjo();
        crearOrbita();
        am.registrarPelea(this);
        gestor.registrar(this);
        Compat.soundPlayers(l.getWorld(), l, "entity.iron_golem.repair", 2.0f, 0.5f);
        Compat.soundPlayers(l.getWorld(), l, "block.beacon.activate", 1.5f, 0.6f);
        return true;
    }

    // ================================================================ piezas

    private BlockDisplay pieza(Location l, Material m, float escala, boolean brilla) {
        try {
            return l.getWorld().spawn(l, BlockDisplay.class, d -> {
                d.setBlock(m.createBlockData());
                d.setPersistent(false);
                d.setViewRange(3f);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTeleportDuration(2);
                d.setTransformation(new Transformation(new Vector3f(-escala / 2, -escala / 2, -escala / 2), new AxisAngle4f(),
                        new Vector3f(escala, escala, escala), new AxisAngle4f()));
                if (brilla) {
                    d.setGlowing(true);
                    d.setGlowColorOverride(Color.fromRGB(RGB_OJO));
                }
                d.getPersistentDataContainer().set(Marcas.VIGILANTE, PersistentDataType.STRING, cuerpo.getUniqueId().toString());
            });
        } catch (Throwable t) {
            return null;
        }
    }

    private double alto() {
        return cuerpo.getHeight();
    }

    /** El ojo: en el pecho, por delante del cuerpo. */
    private Location ojoPos() {
        Location l = cuerpo.getLocation();
        float yaw = l.getYaw();
        try {
            yaw = cuerpo.getBodyYaw();
        } catch (Throwable ignorado) {
            // Sin yaw del cuerpo, el de la cabeza.
        }
        Vector f = PeleaAmbush.dir(yaw).multiply(0.42 * a.escalaCuerpo);
        Location o = l.add(0, alto() * 0.62, 0).add(f);
        o.setYaw(0);
        o.setPitch(0);
        return o;
    }

    private void crearOjo() {
        ojo = pieza(ojoPos(), Material.OCHRE_FROGLIGHT, 0.5f * (float) a.escalaCuerpo / 2.2f * 1.0f, true);
    }

    private Location orbitaPos(int i) {
        double ang = giro + i * Math.PI * 2 / 3;
        double r = 0.75 * cuerpo.getWidth() + 0.9;
        double y = alto() * 0.45 + Math.sin(giro * 2 + i) * 0.35;
        return cuerpo.getLocation().add(Math.cos(ang) * r, y, Math.sin(ang) * r);
    }

    private void crearOrbita() {
        quitarOrbita();
        for (int i = 0; i < 3; i++) {
            BlockDisplay d = pieza(orbitaPos(i), Material.RAW_GOLD_BLOCK, 0.85f, true);
            if (d != null) orbita.add(d);
        }
        nucleosFuera = false;
    }

    private void quitarOrbita() {
        for (BlockDisplay d : orbita) Fx.safeRemove(d);
        orbita.clear();
    }

    /** Cada 2 ticks: el ojo y los nucleos con el cuerpo, los pilares que caducan y las chispas para Bedrock. */
    private void moverPiezas(World w) {
        if (ojo != null && ojo.isValid()) {
            Location o = ojoPos();
            ojo.teleport(o);
            if (vueltas % 3 == 0 && !aturdido()) Compat.spawn(w, Compat.DUST, o, 2, 0.15, 0.15, 0.15, 0, Compat.dust(RGB_OJO, 1.2f));
        } else if (ojo != null) {
            ojo = null;
        }
        if (!nucleosFuera) {
            giro += 0.22;
            for (int i = 0; i < orbita.size(); i++) {
                BlockDisplay d = orbita.get(i);
                if (d.isValid()) d.teleport(orbitaPos(i));
            }
            if (vueltas % 5 == 0) {
                for (int i = 0; i < orbita.size(); i++) Compat.spawn(w, Compat.END_ROD, orbitaPos(i), 1, 0.1, 0.1, 0.1, 0.01);
            }
        }
        for (Iterator<Pilar> it = pilares.iterator(); it.hasNext(); ) {
            Pilar p = it.next();
            if (!p.vivo) {
                it.remove();
                continue;
            }
            if (ticks >= p.hasta) {
                derrumbar(p);
                it.remove();
            } else if (vueltas % 5 == 0) {
                // Para quien juega desde Bedrock, que no ve las entidades de bloque: el contorno en polvo de piedra.
                BlockData piedra = Material.STONE_BRICKS.createBlockData();
                for (double y = 0.5; y < p.alto; y += 1.4) {
                    Compat.spawn(w, Compat.BLOCK, new Location(w, p.x, p.y0 + y, p.z), 1, 0.4, 0.2, 0.4, 0, piedra);
                }
            }
        }
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
        yawFijo = null;
        cortarVuelo();
        if (segundo) {
            refrescarBarra();
            devolverVuelo(false);
        } else if (barra != null) {
            barra.progress((float) Math.max(0, Math.min(1, Amenazas.fraccion(cuerpo))));
        }
        if (estado == Estado.APARECE) {
            moverPiezas(w);
            Compat.spawn(w, Compat.LARGE_SMOKE, cuerpo.getLocation().add(0, 0.3, 0), 6, 1.2, 0.2, 1.2, 0.01);
            if (ticks >= TICKS_APARECE) empezarPelea();
            return;
        }
        if (ticks - inicioPelea >= a.duracionMinutos * 1200L) {
            irse("tiempo", ComandoCalamity.mensaje(Component.text("El ").append(Component.text("Vigilante", Paleta.VIGILANTE))
                    .append(Component.text(" vuelve al cielo."))));
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
            if (fin && actual == tec) acabar();
        } else if (aturdido()) {
            Compat.spawn(w, Compat.CRIT, cuerpo.getLocation().add(0, alto() + 0.3, 0), 4, 0.6, 0.1, 0.6, 0.05);
        } else {
            if (aturdidoHasta > 0) finAturdido();
            dirigir(segundo);
        }
        apuntarOjo();
        moverPiezas(w);
        mirada(w);
        if (vueltas % 3 == 0) pintarMarcas(w);
    }

    private void empezarPelea() {
        estado = Estado.PELEA;
        inicioPelea = ticks;
        ultimoGolpe = ticks;
        respiroHasta = ticks + 20;
        cuerpo.setInvulnerable(false);
        cuerpo.setAI(true);
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
            case 1 -> "Centinela";
            case 2 -> "Demoledor";
            case 3 -> "Ojo";
            default -> "Vigilante";
        };
    }

    private static String consejoFase(int f) {
        return switch (f) {
            case 2 -> "Levanta pilares: úsalos para taparte de su ojo.";
            case 3 -> "Rompe sus núcleos antes de que estallen.";
            default -> "Su ojo gira como un faro: busca dónde cubrirte.";
        };
    }

    private void cambiarFase(int nueva) {
        fase = nueva;
        cortarTecnica();
        siguiente = switch (nueva) {
            case 2 -> Habilidad.PILARES;
            case 3 -> Habilidad.NUCLEOS;
            case 4 -> Habilidad.FARO;
            default -> null;
        };
        respiroHasta = ticks + 10;
        Location l = cuerpo.getLocation();
        Compat.soundPlayers(l.getWorld(), l, "entity.iron_golem.damage", 2.0f, 0.5f);
        Compat.soundPlayers(l.getWorld(), l, "block.beacon.power_select", 1.5f, 0.6f);
        Compat.spawn(l.getWorld(), Compat.BLOCK, l.clone().add(0, alto() * 0.5, 0), 40, 1.2, 1.5, 1.2, 0.1,
                Material.IRON_BLOCK.createBlockData());
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
            cuerpo.setTarget(null);
            return;
        }
        if (segundo) cuerpo.setTarget(obj);
        if (ticks < respiroHasta) return;
        double dist = PeleaAmbush.distPlano(cuerpo.getLocation(), obj.getLocation());
        Habilidad h = null;
        if (siguiente != null && siguiente.enFase(fase) && puede(siguiente, obj, dist)) {
            h = siguiente;
            siguiente = null;
        } else if ((ticks - ultimoGolpe >= a.atascoSegundos * 20L || dist > 30) && puede(Habilidad.SALTO, obj, Math.min(dist, 30))
                && ticks >= listo.getOrDefault(Habilidad.SALTO, 0L) - a.hab(Habilidad.SALTO).espera() / 2) {
            // Atascado o lejos: salta hacia su objetivo (con su aviso, como siempre).
            h = Habilidad.SALTO;
            ultimoGolpe = ticks;
        } else {
            List<Habilidad> cand = new ArrayList<>();
            for (Habilidad x : Habilidad.values()) {
                if (!x.enFase(fase) || ticks < listo.getOrDefault(x, 0L) || x.peso <= 0) continue;
                if (puede(x, obj, dist)) cand.add(x);
            }
            h = sortear(cand, ultima, ThreadLocalRandom.current().nextDouble());
        }
        if (h != null) empezar(h, obj);
    }

    /** Si esa habilidad tiene sentido ahora contra obj a "dist" bloques. */
    private boolean puede(Habilidad h, Player obj, double dist) {
        return switch (h) {
            case MACHAQUE -> dist <= a.machaqueRadio + 0.5;
            case SALTO -> dist >= 6 && dist <= 30 && !hc.enSpawn(obj) && Vigilante.cargado(obj.getLocation());
            case BARRIDO -> dist <= a.barridoRadio;
            case EMBESTIDA -> dist >= 5 && dist <= a.embestidaLargo;
            case ESCOMBROS, SENTENCIA -> dist <= 24;
            case PILARES -> pilaresVivos() <= 2;
            case RAYO -> marcadoCerca(24) != null;
            case NUCLEOS -> nucleos.isEmpty() && !nucleosFuera;
            case TIRON -> !lejanos().isEmpty();
            case FARO -> true;
            case ONDAS -> dist <= a.ondasRadio;
        };
    }

    /** Sorteo por peso (puro); la ultima que uso pesa un 35 %. Null sin candidatas. */
    static Habilidad sortear(List<Habilidad> cand, Habilidad ultima, double azar) {
        double total = 0;
        for (Habilidad h : cand) total += h.peso * (h == ultima ? 0.35 : 1);
        if (cand.isEmpty() || total <= 0) return null;
        double tirada = Math.max(0, Math.min(0.999999, azar)) * total;
        for (Habilidad h : cand) {
            tirada -= h.peso * (h == ultima ? 0.35 : 1);
            if (tirada < 0) return h;
        }
        return cand.get(cand.size() - 1);
    }

    private void empezar(Habilidad h, Player obj) {
        Tecnica tec = switch (h) {
            case MACHAQUE -> new Machaque(obj);
            case SALTO -> new Salto(obj);
            case BARRIDO -> new Barrido(obj);
            case EMBESTIDA -> new Embestida(obj);
            case ESCOMBROS -> new Escombros();
            case PILARES -> new Pilares();
            case RAYO -> new Rayo(obj);
            case NUCLEOS -> new Nucleos();
            case TIRON -> new Tiron();
            case FARO -> new Faro();
            case ONDAS -> new Ondas();
            case SENTENCIA -> new Sentencia(obj);
        };
        actual = tec;
        cuerpo.setAI(false);
        cuerpo.setTarget(null);
        listo.put(h, ticks + a.hab(h).espera());
        ultima = h;
        nombreBarra();
    }

    private void acabar() {
        actual = null;
        respiroHasta = ticks + 16 + ThreadLocalRandom.current().nextInt(14);
        if (!aturdido() && cuerpo != null && cuerpo.isValid()) cuerpo.setAI(true);
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
        if (!aturdido() && cuerpo != null && cuerpo.isValid() && estado == Estado.PELEA) cuerpo.setAI(true);
        nombreBarra();
    }

    /** /calamity vigilant ability y /anomaly test: suelta esa habilidad ya. Devuelve por que no, o null. */
    String forzar(Habilidad h) {
        if (estado != Estado.PELEA) return "aún está apareciendo";
        Player obj = objetivo();
        if (obj == null) return "no tiene a nadie a quien apuntar";
        if (aturdido()) aturdidoHasta = ticks;
        cortarTecnica();
        empezar(h, obj);
        return null;
    }

    // ================================================================ objetivo y mirada

    private boolean valido(Player p) {
        return p != null && Fx.isFightable(p) && cuerpo != null && p.getWorld() == cuerpo.getWorld() && !hc.enSpawn(p);
    }

    /** A quien va: el marcado mas cercano; si no, su presa; si no, el mas cercano. A 40 bloques como mucho. */
    Player objetivo() {
        if (cuerpo == null) return null;
        Player m = marcadoCerca(40);
        if (m != null) return m;
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

    boolean marcado(Player p) {
        Long h = p == null ? null : marcaHasta.get(p.getUniqueId());
        return h != null && h > ticks;
    }

    private Player marcadoCerca(double radio) {
        Player mejor = null;
        double md = radio * radio;
        for (Player p : Fx.playersNear(cuerpo.getLocation(), radio)) {
            if (!valido(p) || !marcado(p)) continue;
            double d = p.getLocation().distanceSquared(cuerpo.getLocation());
            if (d <= md) {
                md = d;
                mejor = p;
            }
        }
        return mejor;
    }

    private List<Player> lejanos() {
        List<Player> l = new ArrayList<>();
        for (Player p : Fx.playersNear(cuerpo.getLocation(), 36)) {
            if (valido(p) && PeleaAmbush.distPlano(cuerpo.getLocation(), p.getLocation()) >= a.tironMinima) l.add(p);
        }
        return l;
    }

    private void apuntarOjo() {
        if (yawFijo != null) {
            yawOjo = yawFijo;
            return;
        }
        Player obj = objetivo();
        if (obj != null) yawOjo = PeleaAmbush.yaw(cuerpo.getLocation(), obj.getLocation());
    }

    /** El golem mira hacia "dir" mientras no se mueve por su cuenta. */
    private void mirar(float yaw) {
        try {
            cuerpo.setRotation(yaw, 0);
            cuerpo.setBodyYaw(yaw);
        } catch (Throwable ignorado) {
            // Sin girar el cuerpo, el aviso del suelo dice igual a donde va el golpe.
        }
    }

    /** Si el cono del ojo (puro): dentro del alcance, del angulo en el plano y sin mucha diferencia de altura. */
    static boolean enMirada(double dx, double dy, double dz, float yaw, double alcance, double angulo) {
        if (dx * dx + dy * dy + dz * dz > alcance * alcance) return false;
        if (Math.abs(dy) > alcance * 0.6) return false;
        Vector d = PeleaAmbush.dir(yaw);
        return ParcaAnomalia.enArco(dx, dz, d.getX(), d.getZ(), alcance, angulo);
    }

    /**
     * Si un pilar tapa el segmento a-b (puro): el punto del segmento mas cercano al eje del pilar en el
     * plano esta a "radio" o menos y a una altura entre y0 e y1.
     */
    static boolean tapaPilar(double ax, double ay, double az, double bx, double by, double bz,
                             double cx, double cz, double y0, double y1, double radio) {
        double vx = bx - ax, vz = bz - az;
        double l2 = vx * vx + vz * vz;
        double k = l2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((cx - ax) * vx + (cz - az) * vz) / l2));
        double px = ax + vx * k, pz = az + vz * k;
        if ((px - cx) * (px - cx) + (pz - cz) * (pz - cz) > radio * radio) return false;
        double y = ay + (by - ay) * k;
        return y >= y0 && y <= y1;
    }

    /** Sin nada en medio: ni bloques (raytrace, sin cargar nada) ni sus pilares. */
    private boolean libre(Location desde, Location hasta) {
        if (desde.getWorld() != hasta.getWorld()) return false;
        Vector d = hasta.toVector().subtract(desde.toVector());
        double largo = d.length();
        if (largo < 0.5) return true;
        try {
            RayTraceResult r = desde.getWorld().rayTraceBlocks(desde, d.multiply(1 / largo), largo, FluidCollisionMode.NEVER, true);
            if (r != null && r.getHitBlock() != null) return false;
        } catch (Throwable ignorado) {
            // Sin raytrace se cuenta como visto: mejor un marcado de mas que un ojo ciego.
        }
        for (Pilar p : pilares) {
            if (p.vivo && tapaPilar(desde.getX(), desde.getY(), desde.getZ(), hasta.getX(), hasta.getY(), hasta.getZ(),
                    p.x, p.z, p.y0, p.y0 + p.alto, PILAR_RADIO)) {
                return false;
            }
        }
        return true;
    }

    /** El cono de luz y quien queda marcado tras mirada.segundos dentro de el sin nada en medio. */
    private void mirada(World w) {
        if (estado != Estado.PELEA || aturdido() || nucleosFuera) {
            visto.clear();
            return;
        }
        Location o = ojoPos();
        if (vueltas % 2 == 0) pintarCono(w, o, yawOjo, a.miradaAlcance, a.miradaAngulo);
        Set<UUID> ahora = new HashSet<>();
        for (Player p : Fx.playersNear(o, a.miradaAlcance)) {
            if (!valido(p)) continue;
            Location e = p.getEyeLocation();
            if (!enMirada(e.getX() - o.getX(), e.getY() - o.getY(), e.getZ() - o.getZ(), yawOjo, a.miradaAlcance, a.miradaAngulo)) continue;
            if (!libre(o, e)) continue;
            ahora.add(p.getUniqueId());
            int n = visto.merge(p.getUniqueId(), 2, Integer::sum);
            if (n >= a.miradaTicks) marcar(p);
        }
        visto.keySet().retainAll(ahora);
    }

    private void marcar(Player p) {
        boolean nueva = !marcado(p);
        marcaHasta.put(p.getUniqueId(), ticks + a.marcaSegundos * 20L);
        if (!nueva) return;
        hc.barra().aviso(p, Component.text("El ", Paleta.TEXTO).append(Component.text("Vigilante", Paleta.VIGILANTE))
                .append(Component.text(" te tiene en la mira: cúbrete tras el terreno o sus pilares.", Paleta.TEXTO)), 3);
        p.playSound(p.getLocation(), "block.beacon.power_select", 1.0f, 1.4f);
    }

    /** Tres rayos de luz amarilla (los bordes y el centro), un poco hacia el suelo, y un arco donde cae la luz. */
    private void pintarCono(World w, Location o, float yaw, double alcance, double angulo) {
        Particle.DustOptions luz = Compat.dust(RGB_OJO, 0.9f);
        for (double lado : new double[]{-angulo / 2, 0, angulo / 2}) {
            Vector d = PeleaAmbush.dir((float) (yaw + lado)).setY(-0.12).normalize();
            for (double s = 1.5; s <= alcance; s += 1.6) Compat.spawn(w, Compat.DUST, o.clone().add(d.clone().multiply(s)), 1, 0, 0, 0, 0, luz);
        }
        Location c = o.clone();
        c.setY(cuerpo.getLocation().getY());
        Fx.arc(c, PeleaAmbush.dir(yaw), alcance * 0.55, Math.toRadians(angulo), 7, l -> {
            if (Vigilante.cargado(l)) Compat.spawn(w, Compat.DUST, Fx.ground(l, 4).add(0, 0.15, 0), 1, 0, 0, 0, 0, luz);
        });
    }

    /** La corona de luz sobre la cabeza de cada marcado. */
    private void pintarMarcas(World w) {
        if (marcaHasta.isEmpty()) return;
        Particle.DustOptions luz = Compat.dust(RGB_OJO, 1.0f);
        for (Iterator<Map.Entry<UUID, Long>> it = marcaHasta.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            if (e.getValue() <= ticks) {
                it.remove();
                continue;
            }
            Player p = hc.plugin().getServer().getPlayer(e.getKey());
            if (p == null || p.getWorld() != w) continue;
            Location cabeza = p.getLocation().add(0, p.getHeight() + 0.45, 0);
            Fx.ring(cabeza, 0.45, 8, l -> Compat.spawn(w, Compat.DUST, l, 1, 0, 0, 0, 0, luz));
        }
    }

    // ================================================================ golpes

    /**
     * Un golpe fuerte: una fraccion de la vida maxima de la victima (Vigilante.fraccionGolpe), por
     * DanoVerdadero con el tope de golpes.maximo, y en la misma ventana de golpes.ventana-ticks nunca mas
     * que golpes.maximo entre todos: con la vida llena no mata ni uno ni dos juntos.
     */
    void golpeFuerte(Player v, double base, String etiqueta) {
        if (v == null || !Fx.isFightable(v) || hc.enSpawn(v) || cuerpo == null) return;
        double f = Vigilante.fraccionGolpe(base, escala.multFraccion(), marcado(v), a);
        double[] w = ventana.get(v.getUniqueId());
        if (w == null || ticks - w[0] > a.ventanaTicks) {
            w = new double[]{ticks, 0};
            ventana.put(v.getUniqueId(), w);
        }
        double entra = Vigilante.recorteVentana(f, w[1], a.golpeMaximo);
        if (entra <= 0) return;
        w[1] += entra;
        double vidaMax = Compat.getAttribute(v, "max_health", 20);
        DanoVerdadero.aplicar(v, entra * vidaMax, a.golpeMaximo, cuerpo, etiqueta);
        ultimoGolpe = ticks;
    }

    /** Un empujon (o una velocidad exacta). Si levanta, presta el vuelo un momento para que el servidor no lo eche. */
    private void empujar(Player v, Vector vel, boolean exacta) {
        if (v == null || !Fx.isFightable(v) || hc.enSpawn(v)) return;
        try {
            if (vel.getY() > 0.1) prestarVuelo(v);
            v.setVelocity(exacta ? vel : v.getVelocity().add(vel));
        } catch (Throwable ignorado) {
            // Sin empujon el golpe ya ha entrado.
        }
    }

    private void prestarVuelo(Player v) {
        if (vuelo.containsKey(v.getUniqueId())) {
            vuelo.put(v.getUniqueId(), ticks + 60);
            return;
        }
        if (v.getAllowFlight()) return;
        vuelo.put(v.getUniqueId(), ticks + 60);
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

    void haGolpeado() {
        ultimoGolpe = ticks;
    }

    boolean aturdido() {
        return aturdidoHasta > ticks;
    }

    double factorRecibido() {
        return aturdido() ? 1 + a.aturdidoExtra : 1.0;
    }

    void dolor() {
        if (cuerpo == null) return;
        Compat.spawn(cuerpo.getWorld(), Compat.BLOCK, cuerpo.getLocation().add(0, alto() * 0.55, 0), 6, 0.8, 0.8, 0.8, 0.05,
                Material.IRON_BLOCK.createBlockData());
    }

    private void anim() {
        try {
            cuerpo.playEffect(EntityEffect.ENTITY_ATTACK);
        } catch (Throwable ignorado) {
            // Sin la animacion de los brazos, el golpe se ve en el suelo.
        }
    }

    private void aturdir() {
        aturdidoHasta = ticks + a.aturdidoSegundos * 20L;
        cuerpo.setAI(false);
        cuerpo.setTarget(null);
        if (ojo != null && ojo.isValid()) {
            ojo.setGlowing(false);
            ojo.setBrightness(new Display.Brightness(3, 3));
        }
        Location l = cuerpo.getLocation();
        Compat.soundPlayers(l.getWorld(), l, "block.beacon.deactivate", 2.0f, 0.6f);
        Component c = Component.text("El ", Paleta.TEXTO).append(Component.text("Vigilante", Paleta.VIGILANTE))
                .append(Component.text(" queda aturdido y recibe más daño.", Paleta.TEXTO));
        for (Player p : Fx.viewersNear(l, 48)) hc.barra().aviso(p, c, 3);
        hc.plugin().bitacora().anotar("vigilante", "aturdido", presaNombre);
    }

    private void finAturdido() {
        aturdidoHasta = 0;
        if (ojo != null && ojo.isValid()) {
            ojo.setGlowing(true);
            ojo.setBrightness(new Display.Brightness(15, 15));
        }
        if (cuerpo != null && cuerpo.isValid() && actual == null) cuerpo.setAI(true);
    }

    // ================================================================ utilidades de dibujo

    static int tono(double k) {
        return PeleaParca.mezcla(RGB_AVISO_DESDE, RGB_AVISO_HASTA, k);
    }

    private static Location suelo(Location l) {
        return Fx.ground(l, 4).add(0, 0.12, 0);
    }

    /** Un circulo en el suelo (solo sobre chunks cargados). */
    static void circulo(World w, Location c, double r, int rgb, float tam) {
        Particle.DustOptions d = Compat.dust(rgb, tam);
        int puntos = Math.max(12, (int) (r * 7));
        Fx.ring(c, r, puntos, l -> {
            if (Vigilante.cargado(l)) Compat.spawn(w, Compat.DUST, suelo(l), 1, 0, 0, 0, 0, d);
        });
    }

    private static void puntos(World w, List<Location> ps, int rgb, float tam) {
        Particle.DustOptions d = Compat.dust(rgb, tam);
        for (Location l : ps) Compat.spawn(w, Compat.DUST, l, 1, 0, 0, 0, 0, d);
    }

    /** El abanico de un golpe en el suelo (tres arcos y los dos bordes). */
    private static void sector(Location origen, float yaw, double radio, double angulo, List<Location> out) {
        out.clear();
        Vector dir = PeleaAmbush.dir(yaw);
        double spread = Math.toRadians(angulo);
        for (double f : new double[]{0.4, 0.7, 1.0}) {
            double r = radio * f;
            Fx.arc(origen, dir, r, spread, Math.max(6, (int) (r * spread * 2)), l -> {
                if (Vigilante.cargado(l)) out.add(suelo(l));
            });
        }
        for (int lado = -1; lado <= 1; lado += 2) {
            Vector borde = PeleaAmbush.dir((float) (yaw + lado * angulo / 2));
            for (double d = 1; d <= radio; d += 0.8) {
                Location l = origen.clone().add(borde.clone().multiply(d));
                if (Vigilante.cargado(l)) out.add(suelo(l));
            }
        }
    }

    private static void linea(World w, Location a, Location b, double paso, int rgb, float tam) {
        Particle.DustOptions d = Compat.dust(rgb, tam);
        Fx.beam(a, b, paso, l -> Compat.spawn(w, Compat.DUST, l, 1, 0, 0, 0, 0, d));
    }

    private boolean mover(Location l) {
        if (l == null || hc.enSpawn(l) || !Vigilante.cargado(l)) return false;
        return hc.amenazas().teleportar(cuerpo, l);
    }

    private Location pie() {
        return cuerpo.getLocation();
    }

    private void sacudir(Location desde, double radio) {
        for (Player p : Fx.playersNear(desde, radio)) {
            try {
                p.playHurtAnimation(PeleaParca.ladoDe(p, desde));
            } catch (Throwable ignorado) {
                // Sin temblor el golpe se ha visto igual.
            }
        }
    }

    // ================================================================ pilares y nucleos

    /** Un pilar: un BlockDisplay de piedra que tapa la mirada y el rayo. Nunca es un bloque del mundo. */
    private static final class Pilar {
        BlockDisplay d;
        double x, z, y0, alto;
        long hasta;
        boolean vivo = true;
    }

    private int pilaresVivos() {
        int n = 0;
        for (Pilar p : pilares) if (p.vivo) n++;
        return n;
    }

    private void derrumbar(Pilar p) {
        if (!p.vivo) return;
        p.vivo = false;
        World w = p.d != null && p.d.isValid() ? p.d.getWorld() : cuerpo == null ? null : cuerpo.getWorld();
        if (w != null) {
            Location c = new Location(w, p.x, p.y0 + p.alto / 2, p.z);
            Compat.spawn(w, Compat.BLOCK, c, 40, 0.6, p.alto / 3, 0.6, 0.1, Material.STONE_BRICKS.createBlockData());
            Compat.sound(w, c, "block.stone.break", 1.5f, 0.6f);
        }
        Fx.safeRemove(p.d);
        p.d = null;
    }

    /** Los pilares que toca el tramo a-b (la embestida) se rompen. */
    private void romperPilares(Location a0, Location b0, double radio) {
        for (Pilar p : pilares) {
            if (!p.vivo) continue;
            if (ParcaAnomalia.distanciaASegmento(p.x, p.z, a0.getX(), a0.getZ(), b0.getX(), b0.getZ()) <= radio + PILAR_RADIO) {
                derrumbar(p);
                Compat.sound(b0.getWorld(), b0, "entity.zombie.break_wooden_door", 1.2f, 0.5f);
            }
        }
    }

    /** Un nucleo suelto: un shulker amarillo sin IA que aguanta unos golpes (ver Vigilante.golpesNucleo). */
    private static final class Nucleo {
        Shulker s;
        Location sitio;
        int golpes;
    }

    boolean esNucleo(Entity e) {
        return e != null && nucleos.containsKey(e.getUniqueId());
    }

    /** Un jugador ha golpeado un nucleo (Vigilante.onGolpe): uno menos; a cero se rompe. */
    void golpeNucleo(Entity e, Player j) {
        Nucleo n = nucleos.get(e.getUniqueId());
        if (n == null || n.s == null) return;
        n.golpes--;
        World w = n.sitio.getWorld();
        Compat.spawn(w, Compat.CRIT, n.sitio.clone().add(0, 0.5, 0), 10, 0.3, 0.3, 0.3, 0.2);
        Compat.sound(w, n.sitio, "block.anvil.land", 0.7f, 1.6f);
        if (n.golpes > 0) {
            n.s.customName(Component.text("Núcleo · ", Paleta.VIGILANTE).append(Component.text(n.golpes, Paleta.CIFRA)));
            return;
        }
        Compat.spawn(w, Compat.BLOCK, n.sitio.clone().add(0, 0.5, 0), 30, 0.4, 0.4, 0.4, 0.1, Material.RAW_GOLD_BLOCK.createBlockData());
        Compat.spawn(w, Compat.EXPLOSION, n.sitio.clone().add(0, 0.5, 0), 1);
        Compat.sound(w, n.sitio, "block.amethyst_block.break", 1.5f, 0.5f);
        Fx.safeRemove(n.s);
        nucleos.remove(e.getUniqueId());
        hc.plugin().bitacora().anotar("vigilante", "nucleo-roto", j.getName(), presaNombre);
    }

    private void quitarNucleos() {
        for (Nucleo n : nucleos.values()) Fx.safeRemove(n.s);
        nucleos.clear();
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

    // ------------------------------------------------------------------ Fase I

    /** Brazos arriba y al suelo: un abanico delante que te lanza por el aire. */
    private final class Machaque extends Tecnica {
        final Location origen;
        final float yaw;
        final List<Location> marca = new ArrayList<>();

        Machaque(Player obj) {
            super(Habilidad.MACHAQUE);
            origen = pie();
            yaw = PeleaAmbush.yaw(origen, obj.getLocation());
            sector(origen, yaw, a.machaqueRadio, a.machaqueAngulo, marca);
        }

        @Override
        boolean paso(World w) {
            mirar(yaw);
            yawFijo = yaw;
            if (t == 0) Compat.soundPlayers(w, origen, "entity.iron_golem.repair", 1.5f, 0.5f);
            if (t < aviso()) {
                if (t % 4 == 0) puntos(w, marca, tono(t / (double) aviso()), 1.3f);
                return false;
            }
            anim();
            Compat.soundPlayers(w, origen, "entity.iron_golem.attack", 2.0f, 0.6f);
            Compat.sound(w, origen, "entity.generic.explode", 1.0f, 1.3f);
            BlockData piedra = Material.STONE.createBlockData();
            for (int i = 0; i < marca.size(); i += 3) Compat.spawn(w, Compat.BLOCK, marca.get(i), 2, 0.2, 0.1, 0.2, 0.05, piedra);
            Vector dir = PeleaAmbush.dir(yaw);
            for (Player v : Fx.playersNear(origen, a.machaqueRadio + 0.5)) {
                Vector hv = v.getLocation().toVector().subtract(origen.toVector());
                if (Math.abs(hv.getY()) > 3) continue;
                if (!ParcaAnomalia.enArco(hv.getX(), hv.getZ(), dir.getX(), dir.getZ(), a.machaqueRadio, a.machaqueAngulo)) continue;
                golpeFuerte(v, fraccion(), h.nombre);
                Vector fuera = PeleaAmbush.plano(origen, v.getLocation(), dir).multiply(0.6).setY(1.15);
                empujar(v, fuera, true);
            }
            sacudir(origen, a.machaqueRadio + 4);
            return true;
        }
    }

    /** Marca donde va a caer, salta en parabola y suelta una onda por el suelo que hay que saltar. */
    private final class Salto extends Tecnica {
        static final int VUELO = 12;
        final Location desde;
        final Location destino;
        final Set<UUID> tocados = new HashSet<>();
        double onda = -1;
        boolean aterrizado;

        Salto(Player obj) {
            super(Habilidad.SALTO);
            desde = pie();
            Location d = Vigilante.cargado(obj.getLocation()) ? Fx.ground(obj.getLocation(), 6) : null;
            destino = d == null || hc.enSpawn(d) ? null : d;
        }

        @Override
        boolean paso(World w) {
            if (destino == null) return true;
            float yaw = PeleaAmbush.yaw(desde, destino);
            yawFijo = yaw;
            if (t < aviso()) {
                mirar(yaw);
                if (t == 0) Compat.soundPlayers(w, desde, "entity.iron_golem.step", 2.0f, 0.4f);
                if (t % 4 == 0) {
                    double k = t / (double) aviso();
                    circulo(w, destino, a.saltoRadio, tono(k), 1.5f);
                    circulo(w, destino, a.saltoRadio * 0.5, tono(k), 1.0f);
                }
                Compat.spawn(w, Compat.CLOUD, desde.clone().add(0, 0.2, 0), 3, 1, 0.1, 1, 0.01);
                return false;
            }
            long s = t - aviso();
            if (!aterrizado) {
                double k = Math.min(1, s / (double) VUELO);
                Location p = desde.clone().add(destino.toVector().subtract(desde.toVector()).multiply(k));
                p.add(0, 7 * 4 * k * (1 - k), 0);
                p.setYaw(yaw);
                mover(p);
                Compat.spawn(w, Compat.CLOUD, p, 4, 0.6, 0.6, 0.6, 0.01);
                if (k < 1) return false;
                // Aterriza.
                aterrizado = true;
                anim();
                Compat.spawn(w, Compat.EXPLOSION_EMITTER, destino.clone().add(0, 0.5, 0), 1);
                Compat.soundPlayers(w, destino, "entity.generic.explode", 1.8f, 0.7f);
                for (Player v : Fx.playersNear(destino, a.saltoRadio)) {
                    if (Math.abs(v.getLocation().getY() - destino.getY()) > 3) continue;
                    golpeFuerte(v, fraccion(), h.nombre);
                    tocados.add(v.getUniqueId());
                    empujar(v, PeleaAmbush.plano(destino, v.getLocation(), new Vector(0, 0, 1)).multiply(0.5).setY(0.9), true);
                }
                sacudir(destino, a.saltoRadio + 6);
                onda = a.saltoRadio * 0.6;
                return false;
            }
            onda += 1.2;
            circulo(w, destino, onda, RGB_AVISO_HASTA, 1.6f);
            for (Player v : Fx.playersNear(destino, onda + 1.5)) {
                if (tocados.contains(v.getUniqueId())) continue;
                double d = PeleaAmbush.distPlano(destino, v.getLocation());
                if (Math.abs(d - onda) > 0.9 || Math.abs(v.getLocation().getY() - destino.getY()) > 1.5 || !enSuelo(v)) continue;
                tocados.add(v.getUniqueId());
                golpeFuerte(v, a.saltoOndaFraccion, "Onda");
                empujar(v, PeleaAmbush.plano(destino, v.getLocation(), new Vector(0, 0, 1)).multiply(0.4).setY(0.3), false);
            }
            return onda >= a.saltoOndaRadio;
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean enSuelo(Player v) {
        return v.isOnGround();
    }

    /** El ojo barre un abanico: quien quede en la luz sin nada delante, golpe y marca. */
    private final class Barrido extends Tecnica {
        static final int DURA = 16;
        final Location origen;
        final float centro;
        final List<Location> marca = new ArrayList<>();
        final Set<UUID> tocados = new HashSet<>();

        Barrido(Player obj) {
            super(Habilidad.BARRIDO);
            origen = pie();
            centro = PeleaAmbush.yaw(origen, obj.getLocation());
            sector(origen, centro, a.barridoRadio, a.barridoAngulo, marca);
        }

        @Override
        boolean paso(World w) {
            mirar(centro);
            if (t < aviso()) {
                yawFijo = (float) (centro - a.barridoAngulo / 2);
                if (t % 4 == 0) puntos(w, marca, tono(t / (double) aviso()), 1.2f);
                if (t == 0) Compat.soundPlayers(w, origen, "block.beacon.ambient", 2.0f, 1.4f);
                return false;
            }
            long s = t - aviso();
            double k = Math.min(1, s / (double) DURA);
            float ang = (float) (centro - a.barridoAngulo / 2 + a.barridoAngulo * k);
            yawFijo = ang;
            Location o = ojoPos();
            Vector d = PeleaAmbush.dir(ang).setY(-0.15).normalize();
            linea(w, o, o.clone().add(d.clone().multiply(a.barridoRadio)), 0.7, RGB_OJO, 1.6f);
            if (s % 4 == 0) Compat.sound(w, o, "block.beacon.power_select", 0.8f, 1.8f);
            double paso = a.barridoAngulo / (DURA / 2.0);
            for (Player v : Fx.playersNear(origen, a.barridoRadio)) {
                if (tocados.contains(v.getUniqueId()) || !valido(v)) continue;
                double dy = PeleaAmbush.difYaw(PeleaAmbush.yaw(origen, v.getLocation()), ang);
                if (Math.abs(dy) > Math.max(10, paso)) continue;
                if (!libre(o, v.getEyeLocation())) continue;
                tocados.add(v.getUniqueId());
                golpeFuerte(v, fraccion(), h.nombre);
                marcar(v);
            }
            return s >= DURA;
        }
    }

    // ------------------------------------------------------------------ Fase II

    /** Marca una linea y la recorre de golpe: golpe y empujon a un lado; rompe los pilares que pisa. */
    private final class Embestida extends Tecnica {
        final Location origen;
        final float yaw;
        final Vector dir;
        final List<Location> ruta = new ArrayList<>();
        final Set<UUID> tocados = new HashSet<>();
        int i;

        Embestida(Player obj) {
            super(Habilidad.EMBESTIDA);
            origen = pie();
            yaw = PeleaAmbush.yaw(origen, obj.getLocation());
            dir = PeleaAmbush.dir(yaw);
            ParcaAnomalia.trazar(origen, dir, a.embestidaLargo, ruta);
            ruta.removeIf(l -> !Vigilante.cargado(l));
        }

        @Override
        boolean paso(World w) {
            mirar(yaw);
            yawFijo = yaw;
            if (t < aviso()) {
                if (t % 4 == 0) {
                    int rgb = tono(t / (double) aviso());
                    Vector lado = new Vector(-dir.getZ(), 0, dir.getX()).multiply(1.6);
                    List<Location> ps = new ArrayList<>();
                    for (int k = 0; k < ruta.size(); k += 2) {
                        Location l = ruta.get(k).clone().add(0, 0.15, 0);
                        ps.add(l);
                        ps.add(l.clone().add(lado));
                        ps.add(l.clone().subtract(lado));
                    }
                    puntos(w, ps, rgb, 1.2f);
                }
                if (t % 8 == 0) Compat.soundPlayers(w, origen, "entity.ravager.step", 1.6f, 0.5f);
                return false;
            }
            if (ruta.size() < 3) return true;
            int prev = i;
            i = Math.min(ruta.size() - 1, i + 4);
            Location a0 = ruta.get(prev), b0 = ruta.get(i).clone();
            b0.setYaw(yaw);
            if (hc.enSpawn(b0)) return true;
            mover(b0);
            Compat.spawn(w, Compat.BLOCK, b0.clone().add(0, 0.2, 0), 8, 1, 0.1, 1, 0.05, Material.STONE.createBlockData());
            if (i % 8 == 0) Compat.soundPlayers(w, b0, "entity.iron_golem.step", 2.0f, 0.5f);
            for (Player v : Fx.playersNear(b0, 6)) {
                if (tocados.contains(v.getUniqueId())) continue;
                Location lv = v.getLocation();
                if (Math.abs(lv.getY() - b0.getY()) > 3) continue;
                if (ParcaAnomalia.distanciaASegmento(lv.getX(), lv.getZ(), a0.getX(), a0.getZ(), b0.getX(), b0.getZ()) > 2.0) continue;
                tocados.add(v.getUniqueId());
                golpeFuerte(v, fraccion(), h.nombre);
                Vector lado = new Vector(-dir.getZ(), 0, dir.getX());
                if (lado.dot(lv.toVector().subtract(b0.toVector())) < 0) lado.multiply(-1);
                empujar(v, lado.multiply(0.9).add(dir.clone().multiply(0.4)).setY(0.45), true);
            }
            romperPilares(a0, b0, 2.0);
            return i >= ruta.size() - 1;
        }
    }

    /** Circulos en el suelo y una piedra que cae sobre cada uno, uno tras otro. */
    private final class Escombros extends Tecnica {
        final class Caida {
            Location c;
            long cae;
            BlockDisplay d;
            boolean hecha;
        }

        final List<Caida> caidas = new ArrayList<>();

        Escombros() {
            super(Habilidad.ESCOMBROS);
            Location base = pie();
            List<Location> sitios = new ArrayList<>();
            for (Player p : Fx.playersNear(base, 24)) if (valido(p) && Vigilante.cargado(p.getLocation())) sitios.add(p.getLocation());
            ThreadLocalRandom r = ThreadLocalRandom.current();
            int quiere = Math.max(a.escombrosCirculos, sitios.size());
            for (int intento = 0; sitios.size() < quiere && intento < quiere * 4; intento++) {
                double ang = r.nextDouble(Math.PI * 2), d = 4 + r.nextDouble(9);
                Location l = base.clone().add(Math.cos(ang) * d, 0, Math.sin(ang) * d);
                if (Vigilante.cargado(l) && !hc.enSpawn(l)) sitios.add(l);
            }
            for (int k = 0; k < sitios.size(); k++) {
                Caida c = new Caida();
                c.c = Fx.ground(sitios.get(k), 6);
                c.cae = aviso() + k * 4L;
                caidas.add(c);
            }
        }

        @Override
        boolean paso(World w) {
            if (t == 0) {
                anim();
                Compat.soundPlayers(w, pie(), "entity.iron_golem.attack", 2.0f, 0.4f);
            }
            boolean todas = true;
            for (Caida c : caidas) {
                if (c.hecha) continue;
                todas = false;
                if (t < c.cae) {
                    if (t % 4 == 0) circulo(w, c.c, a.escombrosRadio, tono(t / (double) c.cae), 1.3f);
                    long falta = c.cae - t;
                    if (falta <= 10) {
                        Location arriba = c.c.clone().add(0, 0.6 + 12 * falta / 10.0, 0);
                        if (c.d == null) c.d = pieza(arriba, Material.COBBLED_DEEPSLATE, 1.4f, false);
                        else if (c.d.isValid()) c.d.teleport(arriba);
                    }
                    continue;
                }
                c.hecha = true;
                Fx.safeRemove(c.d);
                c.d = null;
                Compat.spawn(w, Compat.BLOCK, c.c.clone().add(0, 0.3, 0), 25, 0.8, 0.2, 0.8, 0.1, Material.COBBLED_DEEPSLATE.createBlockData());
                Compat.spawn(w, Compat.EXPLOSION, c.c.clone().add(0, 0.5, 0), 1);
                Compat.sound(w, c.c, "block.deepslate.break", 1.6f, 0.6f);
                for (Player v : Fx.playersNear(c.c, a.escombrosRadio)) {
                    if (Math.abs(v.getLocation().getY() - c.c.getY()) > 2.5) continue;
                    golpeFuerte(v, fraccion(), h.nombre);
                }
            }
            return todas;
        }

        @Override
        void cortar() {
            for (Caida c : caidas) Fx.safeRemove(c.d);
        }
    }

    /** Seis pilares de piedra alrededor: suben del suelo y tapan su ojo hasta que caducan o los embiste. */
    private final class Pilares extends Tecnica {
        final List<Pilar> nuevos = new ArrayList<>();

        Pilares() {
            super(Habilidad.PILARES);
        }

        @Override
        boolean paso(World w) {
            if (t == 0) {
                anim();
                Location base = pie();
                Compat.soundPlayers(w, base, "entity.iron_golem.attack", 2.0f, 0.5f);
                ThreadLocalRandom r = ThreadLocalRandom.current();
                double ang0 = r.nextDouble(Math.PI * 2);
                for (int k = 0; k < a.pilaresCuantos; k++) {
                    double ang = ang0 + k * Math.PI * 2 / a.pilaresCuantos + r.nextDouble(-0.25, 0.25);
                    double d = 5.5 + r.nextDouble(4);
                    Location l = base.clone().add(Math.cos(ang) * d, 3, Math.sin(ang) * d);
                    if (!Vigilante.cargado(l) || hc.enSpawn(l)) continue;
                    Location g = Fx.ground(l, 10);
                    if (Math.abs(g.getY() - base.getY()) > 5) continue;
                    Pilar p = new Pilar();
                    p.x = g.getBlockX() + 0.5;
                    p.z = g.getBlockZ() + 0.5;
                    p.y0 = g.getY();
                    p.alto = PILAR_ALTO;
                    p.hasta = ticks + a.pilaresSegundos * 20L;
                    Location en = new Location(w, p.x, p.y0, p.z);
                    try {
                        p.d = w.spawn(en, BlockDisplay.class, e -> {
                            e.setBlock(Material.STONE_BRICKS.createBlockData());
                            e.setPersistent(false);
                            e.setViewRange(3f);
                            e.setTransformation(new Transformation(new Vector3f(-PILAR_ANCHO / 2, 0, -PILAR_ANCHO / 2),
                                    new AxisAngle4f(), new Vector3f(PILAR_ANCHO, 0.05f, PILAR_ANCHO), new AxisAngle4f()));
                            e.getPersistentDataContainer().set(Marcas.VIGILANTE, PersistentDataType.STRING,
                                    cuerpo.getUniqueId().toString());
                        });
                    } catch (Throwable ignorado) {
                        continue;
                    }
                    Compat.spawn(w, Compat.BLOCK, en.clone().add(0, 0.3, 0), 20, 0.6, 0.1, 0.6, 0.1, Material.STONE.createBlockData());
                    Compat.sound(w, en, "block.stone.place", 1.4f, 0.5f);
                    nuevos.add(p);
                    pilares.add(p);
                }
                return false;
            }
            if (t == 2) {
                // Un tick despues de nacer: asi el cliente interpola el crecimiento en vez de verlo ya alto.
                for (Pilar p : nuevos) {
                    if (p.d == null || !p.d.isValid()) continue;
                    p.d.setInterpolationDelay(0);
                    p.d.setInterpolationDuration(10);
                    p.d.setTransformation(new Transformation(new Vector3f(-PILAR_ANCHO / 2, 0, -PILAR_ANCHO / 2),
                            new AxisAngle4f(), new Vector3f(PILAR_ANCHO, PILAR_ALTO, PILAR_ANCHO), new AxisAngle4f()));
                }
            }
            return t >= 14;
        }
    }

    // ------------------------------------------------------------------ Fase III

    /** Fija el ojo en un marcado, carga y dispara; se corta si se rompe la linea de vision. */
    private final class Rayo extends Tecnica {
        final UUID blanco;

        Rayo(Player obj) {
            super(Habilidad.RAYO);
            Player m = marcadoCerca(24);
            blanco = (m != null ? m : obj).getUniqueId();
        }

        @Override
        boolean paso(World w) {
            Player v = hc.plugin().getServer().getPlayer(blanco);
            if (!valido(v)) return true;
            float yaw = PeleaAmbush.yaw(pie(), v.getLocation());
            mirar(yaw);
            yawFijo = yaw;
            Location o = ojoPos();
            Location e = v.getEyeLocation().subtract(0, 0.3, 0);
            boolean libre = libre(o, e);
            if (t < aviso()) {
                if (t == 0) {
                    hc.barra().aviso(v, Component.text("El ojo te apunta: rompe la línea de visión.", Paleta.VIGILANTE), 2);
                }
                linea(w, o, e, 0.9, RGB_OJO, 0.6f);
                if (t % 4 == 0) {
                    Location pv = v.getLocation();
                    linea(w, suelo(pie()), suelo(pv), 1.0, tono(t / (double) aviso()), 1.0f);
                    circulo(w, pv, 1.2, tono(t / (double) aviso()), 1.1f);
                }
                if (t % 6 == 0) Compat.sound(w, o, "block.beacon.ambient", 1.4f, 0.8f + t / (float) aviso());
                if (t >= 10 && !libre) return cortado(w, v, o, e);
                return false;
            }
            if (!libre) return cortado(w, v, o, e);
            linea(w, o, e, 0.4, RGB_OJO, 2.0f);
            Compat.spawn(w, Compat.END_ROD, e, 12, 0.3, 0.3, 0.3, 0.05);
            Compat.soundPlayers(w, o, "entity.guardian.attack", 2.0f, 0.6f);
            golpeFuerte(v, fraccion(), h.nombre);
            marcar(v);
            empujar(v, PeleaAmbush.plano(o, v.getLocation(), new Vector(0, 0, 1)).multiply(0.5).setY(0.2), false);
            return true;
        }

        private boolean cortado(World w, Player v, Location o, Location e) {
            Compat.sound(w, o, "block.beacon.deactivate", 1.4f, 1.2f);
            Compat.spawn(w, Compat.SMOKE, e, 10, 0.2, 0.2, 0.2, 0.02);
            hc.barra().aviso(v, Component.text("El rayo se corta.", Paleta.TEXTO), 2);
            return true;
        }
    }

    /**
     * Suelta sus tres nucleos (shulkers amarillos que aguantan unos golpes) y no recibe dano hasta que
     * caigan. Rotos a tiempo: queda aturdido. Si no: cada uno que quede estalla en su circulo, que se ve
     * todo el rato (rojo los dos ultimos segundos).
     */
    private final class Nucleos extends Tecnica {
        final List<Location> destinos = new ArrayList<>();
        final List<Location> salida = new ArrayList<>();

        Nucleos() {
            super(Habilidad.NUCLEOS);
            Location base = pie();
            double ang0 = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            for (int k = 0; k < 3; k++) {
                Location elegido = null;
                for (double d : new double[]{8, 6, 4}) {
                    double ang = ang0 + k * Math.PI * 2 / 3;
                    Location l = base.clone().add(Math.cos(ang) * d, 3, Math.sin(ang) * d);
                    if (!Vigilante.cargado(l) || hc.enSpawn(l)) continue;
                    Location g = Fx.ground(l, 10);
                    if (Math.abs(g.getY() - base.getY()) > 5 || !Parca.libre(g, 1)) continue;
                    elegido = g;
                    break;
                }
                destinos.add(elegido != null ? elegido : base.clone().add(0, 0, 0));
            }
        }

        @Override
        boolean paso(World w) {
            if (t == 0) {
                cuerpo.setInvulnerable(true);
                nucleosFuera = true;
                for (int i = 0; i < 3; i++) salida.add(orbitaPos(i));
                Compat.soundPlayers(w, pie(), "block.respawn_anchor.charge", 2.0f, 0.6f);
                Component c = Component.text("Suelta sus núcleos: rómpelos antes de que estallen.", Paleta.VIGILANTE);
                for (Player p : Fx.viewersNear(pie(), 48)) hc.barra().aviso(p, c, 3);
            }
            if (t < 10) {
                double k = (t + 2) / 10.0;
                for (int i = 0; i < orbita.size() && i < 3; i++) {
                    BlockDisplay d = orbita.get(i);
                    Location s = salida.get(i);
                    Location l = s.clone().add(destinos.get(i).clone().add(0, 0.5, 0).toVector().subtract(s.toVector()).multiply(k));
                    if (d.isValid()) d.teleport(l);
                }
                return false;
            }
            if (t == 10) soltar(w);
            long s = t - 10;
            long total = a.nucleosSegundos * 20L;
            if (nucleos.isEmpty()) {
                volver(w);
                aturdir();
                return true;
            }
            if (s % 4 == 0) {
                int rgb = s >= total - 40 ? RGB_ESTALLIDO : tono(s / (double) total);
                for (Nucleo n : nucleos.values()) {
                    circulo(w, n.sitio, a.nucleosRadio, rgb, 1.4f);
                    Compat.spawn(w, Compat.END_ROD, n.sitio.clone().add(0, 1.2, 0), 1, 0.1, 0.3, 0.1, 0.01);
                }
            }
            if (s % 20 == 0) {
                Component c = Component.text("Núcleos · ", Paleta.VIGILANTE)
                        .append(Component.text(Math.max(0, (total - s) / 20) + " s", Paleta.CIFRA));
                for (Player p : Fx.viewersNear(pie(), 40)) hc.barra().aviso(p, c, 2);
            }
            if (s < total) return false;
            // Estallan los que quedan: a cada uno le llega la suma de los que le pillan, de una vez.
            Map<UUID, Double> suma = new HashMap<>();
            Map<UUID, Player> quien = new HashMap<>();
            for (Nucleo n : nucleos.values()) {
                Compat.spawn(w, Compat.EXPLOSION_EMITTER, n.sitio.clone().add(0, 0.5, 0), 1);
                Compat.soundPlayers(w, n.sitio, "entity.generic.explode", 2.0f, 0.5f);
                for (Player v : Fx.playersNear(n.sitio, a.nucleosRadio)) {
                    suma.merge(v.getUniqueId(), fraccion(), Double::sum);
                    quien.put(v.getUniqueId(), v);
                }
            }
            for (Map.Entry<UUID, Double> e : suma.entrySet()) golpeFuerte(quien.get(e.getKey()), e.getValue(), "Estallido");
            volver(w);
            return true;
        }

        private void soltar(World w) {
            if (!nucleos.isEmpty()) return;
            int golpes = Vigilante.golpesNucleo(a, jugadoresContados);
            quitarOrbita();
            for (Location d : destinos) {
                Location en = d.clone();
                en.setYaw(0);
                en.setPitch(0);
                try {
                    Shulker s = w.spawn(en, Shulker.class, e -> {
                        e.getPersistentDataContainer().set(Marcas.AMENAZA, PersistentDataType.STRING, "vigilante-nucleo");
                        e.getPersistentDataContainer().set(Marcas.VIGILANTE, PersistentDataType.STRING, cuerpo.getUniqueId().toString());
                        e.setAI(false);
                        e.setPersistent(false);
                        e.setRemoveWhenFarAway(false);
                        e.setSilent(true);
                        e.setGravity(false);
                        e.setColor(DyeColor.YELLOW);
                        e.setGlowing(true);
                        e.customName(Component.text("Núcleo · ", Paleta.VIGILANTE).append(Component.text(golpes, Paleta.CIFRA)));
                        e.setCustomNameVisible(true);
                    });
                    if (s == null || !s.isValid()) continue;
                    Nucleo n = new Nucleo();
                    n.s = s;
                    n.sitio = en;
                    n.golpes = golpes;
                    nucleos.put(s.getUniqueId(), n);
                    Compat.spawn(w, Compat.BLOCK, en.clone().add(0, 0.5, 0), 20, 0.4, 0.2, 0.4, 0.1, Material.RAW_GOLD_BLOCK.createBlockData());
                } catch (Throwable ignorado) {
                    // Un nucleo que no pudo nacer no cuenta: si no nace ninguno, queda aturdido sin mas.
                }
            }
            Compat.soundPlayers(w, pie(), "block.anvil.land", 1.5f, 0.5f);
        }

        /** Los nucleos vuelven a girar y deja de ser invulnerable. */
        private void volver(World w) {
            quitarNucleos();
            if (cuerpo != null && cuerpo.isValid()) {
                cuerpo.setInvulnerable(false);
                crearOrbita();
            }
        }

        @Override
        void cortar() {
            volver(cuerpo == null ? null : cuerpo.getWorld());
        }
    }

    /** Atrae hacia el a los que pelean de lejos, con su aviso. */
    private final class Tiron extends Tecnica {
        final List<UUID> lejos = new ArrayList<>();

        Tiron() {
            super(Habilidad.TIRON);
            for (Player p : lejanos()) lejos.add(p.getUniqueId());
        }

        @Override
        boolean paso(World w) {
            if (t == 0) {
                Compat.soundPlayers(w, pie(), "block.respawn_anchor.set_spawn", 2.0f, 0.5f);
                for (UUID u : lejos) {
                    Player v = hc.plugin().getServer().getPlayer(u);
                    if (v != null) hc.barra().aviso(v, Component.text("El Vigilante te atrae hacia él.", Paleta.VIGILANTE), 2);
                }
            }
            if (t < Math.max(Vigilante.Ajustes.AVISO_MINIMO, aviso())) {
                Particle.DustOptions d = Compat.dust(RGB_OJO, 1.0f);
                for (UUID u : lejos) {
                    Player v = hc.plugin().getServer().getPlayer(u);
                    if (!valido(v)) continue;
                    double r = 1.6 - t / 30.0;
                    Fx.ring(v.getLocation().add(0, 0.2 + (t % 10) / 10.0, 0), Math.max(0.3, r), 8,
                            l -> Compat.spawn(w, Compat.DUST, l, 1, 0, 0, 0, 0, d));
                    if (t % 4 == 0) linea(w, ojoPos(), v.getLocation().add(0, 1, 0), 1.5, RGB_OJO, 0.7f);
                }
                return false;
            }
            Location base = pie();
            for (UUID u : lejos) {
                Player v = hc.plugin().getServer().getPlayer(u);
                if (!valido(v)) continue;
                double d = PeleaAmbush.distPlano(base, v.getLocation());
                Vector hacia = PeleaAmbush.plano(v.getLocation(), base, new Vector(0, 0, 1));
                empujar(v, hacia.multiply(a.tironFuerza * Math.min(1.2, d / 16.0)).setY(0.45), true);
                Compat.spawn(w, Compat.REVERSE_PORTAL, v.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.05);
            }
            Compat.soundPlayers(w, base, "entity.evoker.prepare_attack", 1.6f, 0.6f);
            return true;
        }
    }

    // ------------------------------------------------------------------ Fase IV

    /** El ojo gira como un faro: el abanico de adelante se pinta en el suelo antes de que llegue la luz. */
    private final class Faro extends Tecnica {
        final float ang0;
        final double grados;
        final int total;
        final Map<Integer, Set<UUID>> tocados = new HashMap<>();

        Faro() {
            super(Habilidad.FARO);
            ang0 = yawOjo;
            grados = 360.0 / a.faroTicksVuelta;
            total = a.faroVueltas * a.faroTicksVuelta;
        }

        @Override
        boolean paso(World w) {
            Location o = ojoPos();
            Location base = pie();
            if (t == 0) {
                Compat.soundPlayers(w, base, "block.beacon.activate", 2.0f, 0.5f);
                Component c = Component.text("Su ojo gira como un faro: cúbrete de la luz.", Paleta.VIGILANTE);
                for (Player p : Fx.viewersNear(base, 48)) hc.barra().aviso(p, c, 3);
            }
            long s = Math.max(0, t - aviso());
            double ang = ang0 + grados * s;
            yawFijo = (float) ang;
            mirar((float) ang);
            // Lo que va a barrer la luz en los proximos adelanto-ticks, pintado en el suelo.
            if (t % 4 == 0) {
                int rgb = tono(0.5);
                List<Location> ps = new ArrayList<>();
                for (int k = 4; k <= a.faroAdelanto; k += 4) {
                    Vector d = PeleaAmbush.dir((float) (ang + grados * k));
                    for (double r = 2; r <= a.faroAlcance; r += 2.2) {
                        Location l = base.clone().add(d.clone().multiply(r));
                        if (Vigilante.cargado(l)) ps.add(suelo(l));
                    }
                }
                puntos(w, ps, rgb, 1.1f);
            }
            if (t < aviso()) return false;
            Vector d = PeleaAmbush.dir((float) ang).setY(-0.1).normalize();
            linea(w, o, o.clone().add(d.clone().multiply(a.faroAlcance)), 0.7, RGB_OJO, 1.8f);
            if (s % 10 == 0) Compat.sound(w, o, "block.beacon.ambient", 1.6f, 1.6f);
            int vuelta = (int) (s / a.faroTicksVuelta);
            Set<UUID> ya = tocados.computeIfAbsent(vuelta, k -> new HashSet<>());
            double ancho = Math.max(9, grados * 2.2);
            for (Player v : Fx.playersNear(base, a.faroAlcance)) {
                if (ya.contains(v.getUniqueId()) || !valido(v)) continue;
                double dy = PeleaAmbush.difYaw(PeleaAmbush.yaw(base, v.getLocation()), ang);
                if (Math.abs(dy) > ancho / 2 || !libre(o, v.getEyeLocation())) continue;
                ya.add(v.getUniqueId());
                golpeFuerte(v, fraccion(), h.nombre);
                marcar(v);
            }
            return s >= total;
        }
    }

    /** Tres ondas por el suelo con ritmo; cada una se anuncia con un pulso a sus pies un segundo antes. */
    private final class Ondas extends Tecnica {
        final double[] radio = {-1, -1, -1};
        final List<Set<UUID>> tocados = List.of(new HashSet<>(), new HashSet<>(), new HashSet<>());
        final Location centro;

        Ondas() {
            super(Habilidad.ONDAS);
            centro = pie();
        }

        @Override
        boolean paso(World w) {
            boolean vivas = false;
            for (int k = 0; k < 3; k++) {
                long sale = aviso() + (long) k * a.ondasSeparacion;
                if (radio[k] < 0) {
                    if (t >= sale - Vigilante.Ajustes.AVISO_MINIMO && t < sale) {
                        double q = (t - (sale - Vigilante.Ajustes.AVISO_MINIMO)) / (double) Vigilante.Ajustes.AVISO_MINIMO;
                        if (t % 4 == 0) circulo(w, centro, 2.5 + q * 1.5, tono(q), 1.5f);
                        if (t == sale - Vigilante.Ajustes.AVISO_MINIMO || t == sale - Vigilante.Ajustes.AVISO_MINIMO + 1) {
                            Compat.soundPlayers(w, centro, "block.note_block.basedrum", 2.0f, 0.5f);
                        }
                        vivas = true;
                    } else if (t >= sale) {
                        anim();
                        Compat.soundPlayers(w, centro, "entity.iron_golem.attack", 2.0f, 0.5f);
                        Compat.sound(w, centro, "entity.generic.explode", 1.2f, 1.0f);
                        radio[k] = 1.5;
                    } else {
                        vivas = true;
                    }
                }
                if (radio[k] < 0 || radio[k] > a.ondasRadio) continue;
                vivas = true;
                radio[k] += 1.0;
                circulo(w, centro, radio[k], RGB_AVISO_HASTA, 1.7f);
                for (Player v : Fx.playersNear(centro, radio[k] + 1.5)) {
                    if (tocados.get(k).contains(v.getUniqueId())) continue;
                    double d = PeleaAmbush.distPlano(centro, v.getLocation());
                    if (Math.abs(d - radio[k]) > 0.9 || Math.abs(v.getLocation().getY() - centro.getY()) > 1.5 || !enSuelo(v)) continue;
                    tocados.get(k).add(v.getUniqueId());
                    golpeFuerte(v, fraccion(), h.nombre);
                    empujar(v, PeleaAmbush.plano(centro, v.getLocation(), new Vector(0, 0, 1)).multiply(0.4).setY(0.3), false);
                }
            }
            return !vivas;
        }
    }

    /** Un circulo se cierra sobre su presa y el le cae encima: hay que salir antes de que se cierre. */
    private final class Sentencia extends Tecnica {
        static final double FINAL = 2.0;
        final UUID blanco;
        final Location centro;

        Sentencia(Player obj) {
            super(Habilidad.SENTENCIA);
            Player m = marcadoCerca(24);
            Player b = m != null ? m : obj;
            blanco = b.getUniqueId();
            centro = Fx.ground(b.getLocation(), 6);
        }

        @Override
        boolean paso(World w) {
            float yaw = PeleaAmbush.yaw(pie(), centro);
            mirar(yaw);
            yawFijo = yaw;
            if (t < aviso()) {
                double k = t / (double) aviso();
                double r = a.sentenciaRadio - (a.sentenciaRadio - FINAL) * k;
                circulo(w, centro, r, tono(k), 1.6f);
                if (t % 8 == 0) circulo(w, centro, FINAL, RGB_ESTALLIDO, 1.0f);
                if (t % 20 == 0) {
                    Compat.soundPlayers(w, centro, "block.bell.use", 1.6f, 0.8f - (float) k * 0.3f);
                    Player v = hc.plugin().getServer().getPlayer(blanco);
                    if (v != null) hc.barra().aviso(v, Component.text("La sentencia se cierra: sal del círculo.", Paleta.VIGILANTE), 2);
                }
                return false;
            }
            Location en = centro.clone();
            en.setYaw(yaw);
            mover(en);
            anim();
            Compat.spawn(w, Compat.EXPLOSION_EMITTER, centro.clone().add(0, 0.5, 0), 1);
            Compat.soundPlayers(w, centro, "entity.generic.explode", 2.0f, 0.5f);
            Compat.soundPlayers(w, centro, "block.anvil.land", 2.0f, 0.4f);
            for (Player v : Fx.playersNear(centro, FINAL + 0.5)) {
                if (Math.abs(v.getLocation().getY() - centro.getY()) > 3) continue;
                golpeFuerte(v, fraccion(), h.nombre);
                empujar(v, PeleaAmbush.plano(centro, v.getLocation(), new Vector(0, 0, 1)).multiply(0.5).setY(1.0), true);
            }
            sacudir(centro, 10);
            return true;
        }
    }

    // ================================================================ barra

    private Component tituloBarra() {
        Component resto = actual != null ? Component.text(actual.h.nombre, Paleta.AVISO)
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
        int marcados = 0;
        for (long h : marcaHasta.values()) if (h > ticks) marcados++;
        Location l = cuerpo.getLocation();
        return presaNombre + " | " + (estado == Estado.APARECE ? "apareciendo" : actual != null ? actual.h.nombre
                : aturdido() ? "aturdido" : "pelea") + " | fase " + fase + " | vida " + Math.round(Amenazas.fraccion(cuerpo) * 100)
                + " % | marcados " + marcados + " | pilares " + pilaresVivos() + " | " + s / 60 + ":"
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
        // Sus nucleos caen y se rompen; el ojo se apaga.
        for (BlockDisplay d : orbita) {
            if (d.isValid()) Compat.spawn(w, Compat.BLOCK, d.getLocation(), 20, 0.3, 0.3, 0.3, 0.1, Material.RAW_GOLD_BLOCK.createBlockData());
        }
        Compat.spawn(w, Compat.END_ROD, l.clone().add(0, alto() * 0.6, 0), 40, 0.6, 0.8, 0.6, 0.08);
        Compat.soundPlayers(w, l, "block.beacon.deactivate", 2.0f, 0.4f);
        hc.seguro("vigilante", () -> gestor.botin(this, dano, vida, segundos));
        limpiar();
    }

    /** Se va sin botin: tiempo, nadie cerca, el mundo se descarga o el staff. Sube al cielo en luz. */
    void irse(String motivo, Component aviso) {
        if (estado == Estado.FIN) return;
        cortarTecnica();
        if (cuerpo != null && cuerpo.isValid()) {
            World w = cuerpo.getWorld();
            Location l = cuerpo.getLocation().add(0, 2, 0);
            Compat.spawn(w, Compat.END_ROD, l, 60, 1, 3, 1, 0.15);
            Compat.spawn(w, Compat.CLOUD, l, 30, 1, 1.5, 1, 0.05);
            Compat.sound(w, l, "block.beacon.deactivate", 2.0f, 0.8f);
            if (aviso != null) for (Player o : Fx.viewersNear(l, 48)) o.sendMessage(aviso);
        }
        hc.plugin().bitacora().anotar("vigilante", "se-va", presaNombre, motivo, (System.currentTimeMillis() - nacio) / 1000 + " s");
        limpiar();
    }

    /**
     * Retira todo lo suyo (idempotente): habilidad, nucleos, ojo, orbita, pilares, barra, vuelo prestado
     * y el cuerpo (si no esta muriendo: entonces vanilla lo tumba y lo quita). Pasan por aqui todos los
     * finales: muere, se va, se para el plugin (Vigilante.parar) o se descarga su mundo.
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
        quitarNucleos();
        quitarOrbita();
        Fx.safeRemove(ojo);
        ojo = null;
        for (Pilar p : pilares) Fx.safeRemove(p.d);
        pilares.clear();
        quitarBarra();
        devolverVuelo(true);
        if (cuerpo != null && cuerpo.isValid() && !cuerpo.isDead()) Fx.safeRemove(cuerpo);
        if (hc.amenazas() != null) hc.amenazas().quitarPelea(this);
        estado = Estado.FIN;
    }

    // ================================================================ autotest

    /** Lo de la pelea que no necesita servidor: habilidades, la mirada y los pilares. */
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
        h.ok("fase 1: machaque, salto y barrido", Habilidad.MACHAQUE.enFase(1) && Habilidad.SALTO.enFase(1) && Habilidad.BARRIDO.enFase(1)
                && !Habilidad.EMBESTIDA.enFase(1));
        h.ok("fase 3: rayo, nucleos y tiron; fase 4: faro, ondas y sentencia", Habilidad.RAYO.enFase(3) && Habilidad.NUCLEOS.enFase(3)
                && Habilidad.TIRON.enFase(3) && Habilidad.FARO.enFase(4) && Habilidad.ONDAS.enFase(4) && Habilidad.SENTENCIA.enFase(4)
                && !Habilidad.FARO.enFase(3));
        h.ok("sorteo: sin candidatas, nada", sortear(List.of(), null, 0.5) == null);
        h.igual("sorteo: con una sola, esa", Habilidad.RAYO, sortear(List.of(Habilidad.RAYO), Habilidad.RAYO, 0.9));
        h.igual("sorteo: por peso (5 de 8 para el machaque)", Habilidad.MACHAQUE,
                sortear(List.of(Habilidad.MACHAQUE, Habilidad.SALTO), null, 0.6));
        h.igual("sorteo: la ultima pesa menos (1,75 de 4,75)", Habilidad.SALTO,
                sortear(List.of(Habilidad.MACHAQUE, Habilidad.SALTO), Habilidad.MACHAQUE, 0.6));

        // La mirada: yaw 0 mira a +Z.
        h.ok("mirada: delante a 10 bloques, visto", enMirada(0, 0, 10, 0f, a.miradaAlcance, a.miradaAngulo));
        h.ok("mirada: detras, no", !enMirada(0, 0, -10, 0f, a.miradaAlcance, a.miradaAngulo));
        h.ok("mirada: a 40 grados de lado (cono de 70), no", !enMirada(Math.sin(Math.toRadians(40)) * 10, 0,
                Math.cos(Math.toRadians(40)) * 10, 0f, a.miradaAlcance, a.miradaAngulo));
        h.ok("mirada: a 30 grados de lado, si", enMirada(-Math.sin(Math.toRadians(30)) * 10, 0,
                Math.cos(Math.toRadians(30)) * 10, 0f, a.miradaAlcance, a.miradaAngulo));
        h.ok("mirada: mas alla del alcance, no", !enMirada(0, 0, a.miradaAlcance + 1, 0f, a.miradaAlcance, a.miradaAngulo));
        h.igual("mirada: dos segundos para marcar", 40, a.miradaTicks);

        // Los pilares tapan: el ojo a 3,7 de alto en (0, 0) y el jugador en (0, 10) a 1,6; pilar en (0, 5) de 0 a 4,5.
        h.ok("pilar en medio: tapa", tapaPilar(0, 3.7, 0, 0, 1.6, 10, 0, 5, 0, PILAR_ALTO, PILAR_RADIO));
        h.ok("pilar a 2 bloques de la linea: no tapa", !tapaPilar(0, 3.7, 0, 0, 1.6, 10, 2, 5, 0, PILAR_ALTO, PILAR_RADIO));
        h.ok("pilar detras del jugador: no tapa", !tapaPilar(0, 3.7, 0, 0, 1.6, 10, 0, 13, 0, PILAR_ALTO, PILAR_RADIO));
        h.ok("linea por encima del pilar: no tapa", !tapaPilar(0, 9, 0, 0, 8, 10, 0, 5, 0, PILAR_ALTO, PILAR_RADIO));
        h.ok("el faro no deja huecos: su luz es mas ancha que lo que gira cada 2 ticks",
                Math.max(9, 360.0 / a.faroTicksVuelta * 2.2) >= 360.0 / a.faroTicksVuelta * 2);
    }
}
