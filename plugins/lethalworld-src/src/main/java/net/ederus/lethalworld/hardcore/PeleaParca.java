package net.ederus.lethalworld.hardcore;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.ederus.edm.anomaly.core.Disguises;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Una PARCA viva (DIS sec. 1.4-1.9). La mueve la tarea de 2 ticks de Amenazas: un solo
 * planificador por pelea y nunca dos telegraphs a la vez. Las habilidades apuntan a donde
 * ESTABA el objetivo cuando empezo el aviso: quien se mueve esquiva, quien se queda se lo come.
 *
 * "No corre. Nunca corre. Pero no se para." IA vanilla de esqueleto wither (velocidad 0,28)
 * mas el Paso Umbral, que la mete detras de la presa aunque se encierre, y una red de atasco.
 *
 * Mientras dura un telegraph se queda quieta (velocidad 0): asi el aviso que se ve en el
 * suelo es el sitio real del golpe y no se desplaza con ella.
 *
 * Lo que se ve no es el esqueleto: es un cuerpo de persona (Mannequin con la skin de la cuenta
 * parca.cuerpo.skin), como Rabby en EDM. El esqueleto sigue debajo, invisible y mudo, con su
 * IA, su caja y su golpe; el maniqui se le pega cada 2 ticks y le pasa los golpes que recibe.
 */
final class PeleaParca implements Runnable, ParcaViva {

    // El Estado es el de ParcaViva (1.2.0): el mismo para la reserva y para la anomalia de EDM.

    private enum Habilidad { SIEGA, UMBRAL, TIRON, CORTEJO, CAMPANADA }

    /** Colores de las particulas de la pelea: claros, para que el aviso se lea en el suelo. */
    private static final int RGB_PARCA = 0xF26A63;
    private static final int RGB_HUESO = 0xE3DCCE;
    /** La Siega empieza coral y acaba en rojo vivo: el color dice cuanto queda. */
    private static final int RGB_SIEGA_DESDE = 0xFF9E80, RGB_SIEGA_HASTA = 0xFF2A2A;
    /** El anillo de la Sentencia se oscurece con cada campanada. */
    private static final int RGB_SENTENCIA_DESDE = 0xFF9E80, RGB_SENTENCIA_HASTA = 0x8E1022;
    /** Largo de cada eslabon de la cadena del Tiron (BlockDisplay de cadena de hierro). */
    private static final double ESLABON = 1.0;
    private static final int TICKS_APARICION = 40;
    private static final int TICKS_COSECHA = 60;
    /**
     * Alto de un jugador y de un esqueleto wither a escala 1. Con cuerpo de NPC el esqueleto
     * invisible se escala a cuerpo.escala x 1,8 / 2,4: su caja (la que recibe golpes, choca y
     * lleva el cartel) mide lo mismo que el maniqui que se ve, ni mas ni menos.
     */
    private static final double ALTO_JUGADOR = 1.8, ALTO_ESQUELETO = 2.4;
    /** Un nombre de cuenta de Minecraft. Lo que no case no se manda a Mojang (va en una URL). */
    private static final Pattern CUENTA = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final Parca gestor;
    private final Hardcore hc;
    private final Parca.Ajustes a;
    /** Null en la de prueba (sin presa). */
    final UUID presa;
    final String presaNombre;
    final boolean prueba;
    /** Presa y marcados extra (sec. 1.4.2). */
    final LinkedHashSet<UUID> marcados = new LinkedHashSet<>();
    final int nivel;
    final int repeticiones;
    /** M: marcados extra (suben la vida x1,5 cada uno). */
    int extra;
    private final double factorR;
    WitherSkeleton cuerpo;
    /** El cuerpo que se ve (Mannequin con skin), o null: entonces se ve el esqueleto vestido. */
    Mannequin cascara;
    /**
     * El perfil que DEBE llevar el maniqui ahora mismo. La resolucion de la skin llega por la
     * red y puede entrar antes o despues del reintento de los 2 ticks: el reintento pone
     * siempre este, el vigente, y no el que se capturo al nacer (el fallo que tuvo Rabby).
     */
    private ResolvableProfile perfil;
    private int pulsosCascara;
    private double golpe;
    int extrasGrupo;
    private final Set<UUID> participantes = new HashSet<>();

    Estado estado = Estado.APARECE;
    int fase = 1;
    private boolean furia;
    private long ticks;
    private final long nacio = System.currentTimeMillis();
    private Location sitioFinal;

    private BossBar barra;
    private final Set<UUID> viendo = new HashSet<>();

    private Habilidad actual;
    private long habDesde;
    private int habDura;
    private Location origen;
    private Vector dir;
    private Location destino;
    private final List<Location> puntos = new ArrayList<>();
    /** El cono de la Siega ya asentado en el suelo (se calcula una vez por aviso, no cada 2 ticks). */
    private final List<Location> cono = new ArrayList<>();
    private final List<Location> filo = new ArrayList<>();
    /** El anillo de la Sentencia en el suelo (exterior e interior), una vez por campanada. */
    private final List<Location> anillo = new ArrayList<>();
    private final List<Location> anilloDentro = new ArrayList<>();
    private long ultimoToque;
    /** Los eslabones de la cadena del Tiron. */
    private final List<BlockDisplay> cadena = new ArrayList<>();
    /** A quien va el Paso Umbral (se le sacude la vista cuando aparece detras). */
    private UUID victimaPaso;
    private double danoAlEmpezar;
    private UUID victimaTiron;
    private int toques;
    private final EnumMap<Habilidad, Long> lista = new EnumMap<>(Habilidad.class);
    private long ultimoGolpe;
    private long ultimoSalto;
    private long ultimaVision;
    private boolean siegaAlAterrizar;
    private long tironSolto;

    /** Mini withers del Cortejo, que orbitan a la PARCA. */
    private final List<Wither> planideras = new ArrayList<>();
    /** Angulo de salida de cada planidera en su orbita (fijo: si cae una, las otras no saltan). */
    private final Map<UUID, Double> huecos = new HashMap<>();
    private long planNacio;
    private boolean planActivas;
    private boolean planCaducaron;
    private long aturdidaHasta;
    private long esperaHasta;
    private long cosechaDesde;
    private UUID objetivoPrueba;
    private boolean pagada;
    /** Posiciones de los jugadores a <= 32, cada 2 ticks, anillo de 20 (2 s): "quieta" para la Siega. */
    private final Map<UUID, ArrayDeque<double[]>> rastro = new HashMap<>();

    private PeleaParca(Parca gestor, Parca.Ajustes a, UUID presa, String presaNombre, boolean prueba,
                       int nivel, int repeticiones, int extra) {
        this.gestor = gestor;
        this.hc = gestor.hc();
        this.a = a;
        this.presa = presa;
        this.presaNombre = presaNombre;
        this.prueba = prueba;
        this.nivel = nivel;
        this.repeticiones = repeticiones;
        this.extra = extra;
        this.factorR = Parca.factorR(a, repeticiones);
    }

    /**
     * La invoca: WITHER_SKELETON por Amenazas.invocar, sube del suelo 40 ticks invulnerable
     * y sin IA, y registra la pelea. Null si el spawn lo cancela alguien.
     *
     * @param fraccion vida con la que sale (1 = llena; lo pendiente trae la suya)
     * @param fase     fase con la que sale (lo pendiente; 1 si es nueva)
     */
    static PeleaParca crear(Parca gestor, Parca.Ajustes a, UUID presa, String presaNombre, Collection<Player> marcados,
                            int nivel, int r, int m, Location sitio, boolean prueba, double fraccion, int fase) {
        if (sitio == null || sitio.getWorld() == null) return null;
        PeleaParca pe = new PeleaParca(gestor, a, presa, presaNombre, prueba, nivel, r, m);
        for (Player p : marcados) pe.marcados.add(p.getUniqueId());
        pe.golpe = Parca.golpe(a, nivel, r);
        double vida = Parca.vidaLogica(a, nivel, r, m);
        pe.sitioFinal = sitio.clone();
        Location bajo = sitio.clone().subtract(0, 2, 0);
        Amenazas am = pe.hc.amenazas();
        if (am == null) return null;
        boolean conCuerpo = a.cuerpoActivo && a.cuerpoSkin != null && CUENTA.matcher(a.cuerpoSkin).matches();
        WitherSkeleton ws = am.invocar(WitherSkeleton.class, bajo, "parca", nivel,
                Paleta.muerte("Parca"), e -> {
                    if (presa != null) e.getPersistentDataContainer().set(Marcas.PRESA, PersistentDataType.STRING, presa.toString());
                    e.setAI(false);
                    e.setInvulnerable(true);
                    e.setSilent(true);
                    Compat.setAttribute(e, "scale", conCuerpo ? escalaEsqueleto(a) : a.escala);
                    Compat.setAttribute(e, "knockback_resistance", 1.0);
                    Compat.setAttribute(e, "movement_speed", a.velocidad);
                    Compat.setAttribute(e, "follow_range", 64);
                    Compat.setAttribute(e, "step_height", 1.5);
                    Compat.setAttribute(e, "attack_damage", pe.golpe);
                    if (conCuerpo) {
                        // Invisible y desnudo: la invisibilidad no esconde el equipo, y la
                        // espada de piedra de serie se veria flotando al lado del maniqui.
                        e.setInvisible(true);
                        EntityEquipment eq = e.getEquipment();
                        if (eq != null) eq.clear();
                    } else {
                        vestir(e, a);
                    }
                });
        if (ws == null) return null;
        pe.cuerpo = ws;
        if (conCuerpo) pe.ponerCascara(bajo);
        am.vidaLogica(ws, vida);
        if (fraccion < 1) am.ponerFraccion(ws, fraccion);
        am.ancla(ws, sitio);
        pe.fase = Math.max(1, Math.min(3, fase));
        // Con la fase ya avanzada (lo pendiente), sus habilidades arrancan listas.
        if (pe.fase >= 3) pe.lista.put(Habilidad.CAMPANADA, 60L);
        am.registrarPelea(pe);

        for (Player p : marcados) pe.alMarcar(p);
        pe.entrada();
        return pe;
    }

    /** Escala del esqueleto invisible para que su caja mida lo que el maniqui (cuerpo.escala). */
    static double escalaEsqueleto(Parca.Ajustes a) {
        return a.cuerpoEscala * ALTO_JUGADOR / ALTO_ESQUELETO;
    }

    /** La guadana: azada de netherita con brillo. La lleva el maniqui o, sin el, el esqueleto. */
    static ItemStack guadana() {
        ItemStack guadana = new ItemStack(Material.NETHERITE_HOE);
        ItemMeta gm = guadana.getItemMeta();
        gm.displayName(Paleta.nombre("Guadaña", Paleta.PARCA));
        gm.setEnchantmentGlintOverride(true);
        guadana.setItemMeta(gm);
        return guadana;
    }

    /** Sin cuerpo de NPC: guadana, ropa de cuero casi negra y, si hay textura, su cabeza. */
    private static void vestir(WitherSkeleton e, Parca.Ajustes a) {
        EntityEquipment eq = e.getEquipment();
        if (eq == null) return;
        eq.setItemInMainHand(guadana());
        eq.setChestplate(cuero(Material.LEATHER_CHESTPLATE));
        eq.setLeggings(cuero(Material.LEATHER_LEGGINGS));
        eq.setBoots(cuero(Material.LEATHER_BOOTS));
        if (a.cabezaTextura != null && !a.cabezaTextura.isBlank()) {
            ItemStack cabeza = Compat.head(a.cabezaTextura);
            if (cabeza != null) eq.setHelmet(cabeza);
        }
    }

    private static ItemStack cuero(Material m) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof LeatherArmorMeta lm) {
            lm.setColor(Color.fromRGB(0x121212));
            it.setItemMeta(lm);
        }
        return it;
    }

    /**
     * El cuerpo de NPC (como Rabby en EDM, BossFight.wearShell): un Mannequin con la skin de la
     * cuenta cuerpo.skin encima del esqueleto invisible, que es quien pelea; los golpes que
     * recibe el maniqui se los pasa Parca.onDanoCascara. Sale con el perfil sin resolver (un
     * instante con la skin de serie) y en cuanto Mojang contesta, fuera del hilo principal, se
     * le cambia la cara (reskin). Si algo falla, el esqueleto se vuelve visible y se viste: la
     * PARCA nunca se queda sin cuerpo.
     */
    private void ponerCascara(Location l) {
        perfil = Disguises.profileOfAccount(hc.plugin(), a.cuerpoSkin);
        if (perfil != null) {
            try {
                cascara = l.getWorld().spawn(l, Mannequin.class, mq -> {
                    mq.getPersistentDataContainer().set(Marcas.CASCARA, PersistentDataType.STRING,
                            cuerpo.getUniqueId().toString());
                    // Nada de esto se guarda: un reinicio no deja cuerpos sueltos por el mundo.
                    mq.setPersistent(false);
                    mq.setGravity(false);
                    mq.setCollidable(false);
                    mq.setSilent(true);
                    mq.setImmovable(true);
                    // Sin nombre propio: el cartel "Nv. X" lo pone MinionManager sobre el esqueleto.
                    mq.setCustomNameVisible(false);
                    try {
                        // El maniqui trae de serie una segunda linea "NPC" bajo el nombre. Fuera.
                        mq.setDescription(Component.empty());
                    } catch (Throwable ignorado) {
                        // Sin descripcion editable se ve la linea: feo, pero la pelea sigue.
                    }
                    mq.setProfile(perfil);
                    try {
                        // La cuenta de la skin trae capa y a la Parca no le pega: todas las capas
                        // de la skin (chaqueta, mangas, sombrero) menos esa.
                        com.destroystokyo.paper.SkinParts.Mutable partes = com.destroystokyo.paper.SkinParts.allParts();
                        partes.setCapeEnabled(false);
                        mq.setSkinParts(partes);
                    } catch (Throwable ignorado) {
                        // Sin la API de capas se ve la capa: feo, pero la pelea sigue.
                    }
                    Compat.setAttribute(mq, "scale", a.cuerpoEscala);
                    // Sin probabilidad de soltarla: eso solo existe en los Mob (el maniqui no lo
                    // es). Si alguien lo mata con /kill, Parca.onMuerte le vacia lo que suelte.
                    mq.getEquipment().setItemInMainHand(guadana());
                });
            } catch (Throwable t) {
                cascara = null;
                hc.plugin().getLogger().warning("[Calamity] No se pudo poner el cuerpo de la Parca: " + t);
            }
        }
        if (cascara == null || !cascara.isValid()) {
            cascara = null;
            volverAEsqueleto();
            return;
        }
        pulsosCascara = 0;
        // La skin de verdad sale de Mojang por la red: fuera del hilo principal y cacheada en
        // EDM (la segunda PARCA desde el arranque ya la tiene al momento).
        Disguises.resolveAccount(hc.plugin(), a.cuerpoSkin, this::reskin);
    }

    /** Llega la skin resuelta (hilo principal). La pelea puede haber acabado ya: entonces nada. */
    private void reskin(ResolvableProfile resuelto) {
        if (resuelto == null) return;
        perfil = resuelto;
        if (cascara != null && cascara.isValid()) cascara.setProfile(resuelto);
    }

    /** Sin maniqui (no se pudo, o alguien lo borro): el esqueleto se ve, a su escala y vestido. */
    private void volverAEsqueleto() {
        if (cuerpo == null || !cuerpo.isValid()) return;
        cuerpo.setInvisible(false);
        Compat.setAttribute(cuerpo, "scale", a.escala);
        vestir(cuerpo, a);
    }

    /**
     * Cada 2 ticks: el maniqui se pega al esqueleto. Mira a donde va el golpe durante un aviso
     * (el cono de la Siega, la cadena) y, si no, a los ojos de su objetivo: que se sienta que
     * te esta mirando. Si el maniqui ha desaparecido, vuelve el esqueleto.
     */
    private void seguirCascara() {
        if (cascara == null) return;
        if (!cascara.isValid()) {
            cascara = null;
            volverAEsqueleto();
            return;
        }
        Location l = cuerpo.getLocation();
        Player obj = objetivo();
        if (actual != null && dir != null && dir.lengthSquared() > 1e-4) {
            l.setDirection(dir);
        } else if (obj != null && obj.getWorld() == l.getWorld()) {
            Vector v = obj.getEyeLocation().toVector().subtract(cuerpo.getEyeLocation().toVector());
            if (v.lengthSquared() > 1e-4) l.setDirection(v);
        }
        l.setPitch(Math.max(-30f, Math.min(30f, l.getPitch())));
        cascara.teleport(l);
        // El primer paquete a veces llega sin la skin (BossFight.wearShell): otra vez a los 2 ticks.
        if (++pulsosCascara == 1 && perfil != null) cascara.setProfile(perfil);
    }

    /** Blande la guadana: el esqueleto (invisible) y el cuerpo que se ve. */
    public void blandir() {
        if (cuerpo != null && cuerpo.isValid()) cuerpo.swingMainHand();
        if (cascara != null && cascara.isValid()) cascara.swingMainHand();
    }

    /**
     * Le han hecho dano de verdad (Parca.onDolor, MONITOR): el esqueleto es invisible y mudo,
     * asi que el estremecimiento y el quejido los pone el cuerpo que se ve.
     */
    public void dolor() {
        if (cascara == null || !cascara.isValid()) return;
        cascara.playHurtAnimation(0f);
        Compat.sound(cascara.getWorld(), cascara.getLocation(), "entity.wither_skeleton.hurt", 0.9f, 0.55f);
    }

    public boolean esCascara(Entity e) {
        return e != null && cascara != null && cascara.getUniqueId().equals(e.getUniqueId());
    }

    /** Lo que ve un marcado al quedar marcado: titulo P-08; si iba montado, abajo. */
    private void alMarcar(Player p) {
        if (p.isInsideVehicle()) p.leaveVehicle();
        p.showTitle(Paleta.titulo(Paleta.muerte("PARCA"), "Te quedaste demasiado tiempo.",
                Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(1000)));
    }

    /**
     * La entrada (DIS sec. 1.4, con mas peso desde 1.1.1): quien este a 32 ve apagarse el
     * cielo (Oscuridad 2 s, que acaba justo cuando ella puede moverse: no roba tiempo de
     * reaccion), oye campanas graves y algo que sale de la tierra, y ve abrirse una niebla de
     * almas (aparecer). Los marcados leen P-08; los demas, a que ha venido. Nunca
     * entity.wither.spawn: es la firma del minijefe.
     */
    private void entrada() {
        World w = sitioFinal.getWorld();
        Compat.sound(w, sitioFinal, "block.respawn_anchor.deplete", 4f, 0.5f);
        Compat.sound(w, sitioFinal, "block.bell.use", 4f, 0.5f);
        Compat.sound(w, sitioFinal, "entity.warden.emerge", 3f, 0.8f);
        Title ajeno = Paleta.titulo(Paleta.muerte("PARCA"), "Ha venido a buscar a alguien.",
                Duration.ofMillis(500), Duration.ofMillis(2500), Duration.ofMillis(1000));
        for (Player p : Fx.viewersNear(sitioFinal, 32)) {
            if (Fx.isFightable(p)) Compat.apply(p, "darkness", TICKS_APARICION, 0);
            if (!marcados.contains(p.getUniqueId())) p.showTitle(ajeno);
        }
    }

    // ================================================================= miedo

    /** Mezcla dos colores RGB (t de 0 a 1). */
    static int mezcla(int desde, int hasta, double t) {
        double k = Math.max(0, Math.min(1, t));
        int r = (int) Math.round(((desde >> 16) & 0xFF) + (((hasta >> 16) & 0xFF) - ((desde >> 16) & 0xFF)) * k);
        int g = (int) Math.round(((desde >> 8) & 0xFF) + (((hasta >> 8) & 0xFF) - ((desde >> 8) & 0xFF)) * k);
        int b = (int) Math.round((desde & 0xFF) + ((hasta & 0xFF) - (desde & 0xFF)) * k);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Angulo desde el que le llega algo que esta en "desde", como lo pide playHurtAnimation
     * (0 = de frente, 90 = por la derecha, 180 = por la espalda).
     */
    static float ladoDe(Player p, Location desde) {
        Vector v = desde.toVector().subtract(p.getLocation().toVector());
        if (v.lengthSquared() < 1e-6) return 0f;
        double yaw = Math.toDegrees(Math.atan2(-v.getX(), v.getZ()));
        return (float) Math.floorMod(Math.round(yaw - p.getLocation().getYaw()), 360L);
    }

    /**
     * La vista tiembla un instante SIN dano (la animacion de golpe del cliente, desde el lado
     * del impacto): el golpe se siente aunque lo hayas esquivado. Solo a quien juega.
     */
    private static void sacudir(Location desde, double radio) {
        for (Player p : Fx.playersNear(desde, radio)) p.playHurtAnimation(ladoDe(p, desde));
    }

    /** Pone la postura del maniqui (agachado aturdida, tumbado al caer). Sin maniqui, nada. */
    private void postura(Pose pose) {
        if (cascara == null || !cascara.isValid()) return;
        try {
            cascara.setPose(pose, pose != Pose.STANDING);
        } catch (Throwable ignorado) {
            // Una postura que el maniqui no admite: se queda de pie, sin mas.
        }
    }

    // ================================================================ consultas

    /** Viva a efectos de los jugadores (persigue, cosecha, presencia). */
    public boolean vivaParaJugadores() {
        return estado != Estado.FIN && cuerpo != null && cuerpo.isValid() && !cuerpo.isDead();
    }

    /** Admite marcados extra (no mientras cosecha, espera o se va). */
    public boolean aceptaMarcados() {
        return (estado == Estado.PELEA || estado == Estado.APARECE) && vivaParaJugadores();
    }

    public boolean esCuerpo(Entity e) {
        return e != null && cuerpo != null && cuerpo.getUniqueId().equals(e.getUniqueId());
    }

    public boolean esPlanidera(Entity e) {
        if (e == null || planideras.isEmpty()) return false;
        for (Wither v : planideras) if (v.getUniqueId().equals(e.getUniqueId())) return true;
        return false;
    }

    public boolean hayMarcadoEnMundo() {
        for (UUID id : marcados) {
            Player m = hc.plugin().getServer().getPlayer(id);
            if (m != null && cuerpo != null && m.getWorld() == cuerpo.getWorld()) return true;
        }
        return false;
    }

    /** Vida logica maxima actual (para porcentajes). */
    public double vidaFinal() {
        return cuerpo == null ? 0 : hc.amenazas().vidaLogicaMaxima(cuerpo);
    }

    /** Lo que multiplica el dano que recibe: x0,5 con planideras, +25 % aturdida o tocando. */
    public double factorRecibido() {
        double f = 1;
        if (!planideras.isEmpty()) f *= a.planReduccion;
        if (ticks < aturdidaHasta) f *= 1 + a.planAturdidaExtra;
        if (actual == Habilidad.CAMPANADA) f *= 1 + a.campExtra;
        return f;
    }

    /** Un jugador le ha pegado. En la de prueba, ese pasa a ser su objetivo. */
    public void golpeadaPor(Player j) {
        if (prueba && (objetivoPrueba == null || !objetivoPrueba.equals(j.getUniqueId()))) {
            objetivoPrueba = j.getUniqueId();
            velocidad();
        }
    }

    /** Ella ha golpeado a alguien: el reloj de "sin golpear" del Paso Umbral y del atasco. */
    public void haGolpeado() {
        ultimoGolpe = ticks;
    }

    // ============================================================ marcados

    /** Otro que llega a 600 cerca: marcado extra, M+1, vida maxima y actual en proporcion. */
    public void agregarMarcado(Player p) {
        if (!marcados.add(p.getUniqueId())) return;
        double f = Amenazas.fraccion(cuerpo);
        extra++;
        hc.amenazas().vidaLogica(cuerpo, hc.amenazas().vidaLogicaMaxima(cuerpo)
                * (1 + a.vidaPorMarcado * extra) / (1 + a.vidaPorMarcado * (extra - 1)));
        hc.amenazas().ponerFraccion(cuerpo, f);
        alMarcar(p);
        gestor.retirarMinijefes(p);
        hc.plugin().bitacora().anotar("parca", "marcado", presaNombre, p.getName(), "M " + extra);
    }

    /** Un marcado extra muere o se va: sale de la lista. Sin nadie en el mundo, se va sin botin. */
    public void quitarMarcado(UUID id, String motivo) {
        if (!marcados.remove(id)) return;
        if (!prueba && marcados.isEmpty()) irse("sin-presas:" + motivo, null);
    }

    // ================================================================ tick

    /** Cada 2 ticks, desde Amenazas (dentro de seguro). */
    @Override
    public void run() {
        ticks += 2;
        if (estado == Estado.FIN) {
            hc.amenazas().quitarPelea(this);
            gestor.olvidar(this);
            return;
        }
        if (cuerpo == null || !cuerpo.isValid() || cuerpo.isDead()) {
            // Muerta la cierra alMorir; aqui solo llega si alguien la ha borrado (amenazas limpiar).
            if (!pagada) irse("desaparece", null);
            return;
        }
        switch (estado) {
            case APARECE -> aparecer();
            case PELEA -> pelear();
            case ESPERA -> {
                if (System.currentTimeMillis() >= esperaHasta) {
                    gestor.guardarPendiente(this, System.currentTimeMillis() + a.pendienteHoras * 3_600_000L, "desconexion");
                    irse("desconexion", null);
                    return;
                }
            }
            case COSECHA -> cosechar();
            default -> {
            }
        }
        if (estado == Estado.FIN) return;
        seguirCascara();
        if (estado != Estado.COSECHA) revisarFase();
        presencia();
        if (ticks % 20 == 0) refrescarBarra();
        else if (barra != null) barra.progress((float) Amenazas.fraccion(cuerpo));
    }

    /** Sube del suelo 0,05 bloques por tick con almas; invulnerable y sin IA hasta el final. */
    private void aparecer() {
        World w = cuerpo.getWorld();
        Location l = cuerpo.getLocation();
        if (ticks < TICKS_APARICION) {
            Location sube = l.clone().add(0, 0.1, 0);
            sube.setDirection(direccionA(objetivo(), sube));
            hc.amenazas().teleportar(cuerpo, sube);
            Compat.spawn(w, Compat.SOUL, sitioFinal, 4, 0.5, 0.2, 0.5, 0.02);
            Compat.spawn(w, Compat.SCULK_SOUL, sitioFinal, 2, 0.4, 0.2, 0.4, 0.02);
            Compat.spawn(w, Compat.SOUL_FIRE_FLAME, sitioFinal.clone().add(0, 0.2, 0), 2, 0.25, 0.1, 0.25, 0.03);
            // La niebla de almas: un anillo de almas y humo que se abre a ras de suelo.
            if (ticks % 4 == 0) {
                double r = 0.6 + 3.4 * ticks / (double) TICKS_APARICION;
                Fx.ring(sitioFinal, r, (int) (r * 8) + 8, ticks * 0.1, p -> {
                    Compat.spawn(w, Compat.SOUL, p.clone().add(0, 0.15, 0), 1, 0.05, 0.05, 0.05, 0.01);
                    Compat.spawn(w, Compat.LARGE_SMOKE, p.clone().add(0, 0.1, 0), 1, 0.1, 0.05, 0.1, 0.005);
                });
            }
            if (ticks == 10 || ticks == 20) Compat.sound(w, sitioFinal, "block.bell.use", 4f, 0.5f);
            if (ticks == 30) Compat.sound(w, sitioFinal, "block.bell.use", 4f, 0.45f);
            return;
        }
        // Fuera del todo: un grito de sculk y un estallido de almas.
        Compat.sound(w, sitioFinal, "block.sculk_shrieker.shriek", 2f, 0.6f);
        Compat.spawn(w, Compat.SOUL, sitioFinal.clone().add(0, 1, 0), 30, 0.6, 1.0, 0.6, 0.04);
        Compat.spawn(w, Compat.SOUL_FIRE_FLAME, sitioFinal.clone().add(0, 0.5, 0), 20, 0.6, 0.4, 0.6, 0.03);
        Location fin = sitioFinal.clone();
        fin.setDirection(direccionA(objetivo(), fin));
        hc.amenazas().teleportar(cuerpo, fin);
        cuerpo.setInvulnerable(false);
        cuerpo.setAI(true);
        estado = Estado.PELEA;
        ultimoGolpe = ticks;
        ultimoSalto = ticks;
        ultimaVision = ticks;
        velocidad();
        // La de prueba "se queda quieta y pelea con quien le pegue".
        if (prueba && objetivoPrueba == null) Compat.setAttribute(cuerpo, "movement_speed", 0);
    }

    private static Vector direccionA(Player p, Location desde) {
        if (p == null || p.getWorld() != desde.getWorld()) return desde.getDirection();
        Vector v = p.getLocation().toVector().subtract(desde.toVector()).setY(0);
        return v.lengthSquared() < 1e-4 ? desde.getDirection() : v;
    }

    private void velocidad() {
        if (cuerpo == null) return;
        Compat.setAttribute(cuerpo, "movement_speed", a.velocidad * (furia ? 1.2 : 1.0));
    }

    /** A quien va: la presa si esta a tiro; si no, el marcado mas cercano; en prueba, quien le pego. */
    private Player objetivo() {
        if (cuerpo == null) return null;
        World w = cuerpo.getWorld();
        if (prueba) {
            if (objetivoPrueba == null) return null;
            Player p = hc.plugin().getServer().getPlayer(objetivoPrueba);
            if (p == null || p.getWorld() != w || !Fx.isFightable(p)
                    || p.getLocation().distanceSquared(cuerpo.getLocation()) > 48 * 48) return null;
            return p;
        }
        Player p = presa == null ? null : hc.plugin().getServer().getPlayer(presa);
        if (p != null && p.getWorld() == w && Fx.isFightable(p)) return p;
        Player mejor = null;
        double mejorD = Double.MAX_VALUE;
        for (UUID id : marcados) {
            Player m = hc.plugin().getServer().getPlayer(id);
            if (m == null || m.getWorld() != w || !Fx.isFightable(m)) continue;
            double d = m.getLocation().distanceSquared(cuerpo.getLocation());
            if (d < mejorD) {
                mejor = m;
                mejorD = d;
            }
        }
        return mejor;
    }

    private void pelear() {
        if (ticks % 20 == 0 && !revisarMarcados()) return;
        Player obj = objetivo();
        apuntarRastros();
        if (ticks % 20 == 0) {
            if (obj != null) cuerpo.setTarget(obj);
            revisarGrupo();
        }
        if (obj != null && ticks % 10 == 0 && cuerpo.hasLineOfSight(obj)) ultimaVision = ticks;

        long vivo = ticks;
        if (!furia && a.furiaMinutos > 0 && vivo >= a.furiaMinutos * 1200L) {
            furia = true;
            velocidad();
            hc.plugin().bitacora().anotar("parca", "furia", presaNombre);
        }
        if (a.duracionMaxima > 0 && vivo >= a.duracionMaxima * 1200L) {
            // Se cansa: se va sin botin y vuelve si la presa entra antes de media hora (P-25).
            gestor.guardarPendiente(this, System.currentTimeMillis() + 30 * 60_000L, "cansada");
            irse("cansada", ComandoCalamity.mensaje("Se cansa de esperar. Volverá."));
            return;
        }

        planideras();
        if (ticks < aturdidaHasta) {
            Compat.spawn(cuerpo.getWorld(), Compat.CRIT, cuerpo.getLocation().add(0, 1.6, 0), 6, 0.5, 0.4, 0.5, 0.1);
            return;
        } else if (aturdidaHasta > 0 && ticks >= aturdidaHasta) {
            aturdidaHasta = 0;
            cuerpo.setAI(true);
            postura(Pose.STANDING);
        }

        if (actual != null) {
            avanzar();
            return;
        }
        if (obj == null) return;
        elegir(obj);
    }

    /**
     * Cada segundo: la presa sigue siendo presa. Fuera del mundo por otra via (tp de admin) =
     * como la puerta; creativo, espectador o exento = se va sin pendiente (sec. 1.9).
     * False si la pelea se ha acabado.
     */
    private boolean revisarMarcados() {
        if (prueba) return true;
        for (Iterator<UUID> it = marcados.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            Player m = hc.plugin().getServer().getPlayer(id);
            if (m == null) continue;     // desconectado: lo resuelve alDesconectar
            boolean esPresa = id.equals(presa);
            boolean exento = !hc.cuenta(m) || gestor.exento(m);
            if (exento) {
                if (esPresa) {
                    irse("exento", null);
                    return false;
                }
                it.remove();
                continue;
            }
            if (!hc.esHardcore(m) || m.getWorld() != cuerpo.getWorld()) {
                if (esPresa) {
                    gestor.alSalir(m, "otra-via");
                    return false;
                }
                it.remove();
            }
        }
        if (marcados.isEmpty()) {
            irse("sin-presas", null);
            return false;
        }
        return true;
    }

    /** Dureza en grupo: un participante nuevo (no la presa) desde el segundo sube vida y golpe x1,25. */
    private void revisarGrupo() {
        Map<UUID, Double> dano = hc.amenazas().danoLogico(cuerpo);
        double vida = vidaFinal();
        for (Map.Entry<UUID, Double> e : dano.entrySet()) {
            UUID id = e.getKey();
            if (participantes.contains(id) || e.getValue() < a.participacionMinima * vida) continue;
            participantes.add(id);
            if (prueba || !Parca.subeGrupo(participantes.size(), id.equals(presa), extrasGrupo, a)) continue;
            double f = Amenazas.fraccion(cuerpo);
            extrasGrupo++;
            hc.amenazas().vidaLogica(cuerpo, vida * (1 + a.grupoExtra));
            hc.amenazas().ponerFraccion(cuerpo, f);
            golpe *= 1 + a.grupoExtra;
            Compat.setAttribute(cuerpo, "attack_damage", golpe);
            vida = vidaFinal();
            hc.plugin().bitacora().anotar("parca", "grupo", presaNombre, String.valueOf(participantes.size()),
                    "extra " + extrasGrupo);
        }
    }

    /** Fases por fraccion de vida (sec. 1.7): I > 66 %, II > 33 %, III el resto. Solo avanzan. */
    public void revisarFase() {
        if (cuerpo == null || estado == Estado.FIN) return;
        double f = Amenazas.fraccion(cuerpo);
        int nueva = f > 0.66 ? 1 : f > 0.33 ? 2 : 3;
        while (fase < nueva) {
            fase++;
            entrarFase(fase);
        }
    }

    private void entrarFase(int f) {
        hc.plugin().bitacora().anotar("parca", "fase", String.valueOf(f), presaNombre);
        String texto = f == 2 ? "Las plañideras lloran por ti." : "Cuenta las campanadas.";
        for (UUID id : marcados) {
            Player m = hc.plugin().getServer().getPlayer(id);
            if (m != null) hc.cordura().destello(m, Component.text(texto, Paleta.TEXTO), 3);
        }
        if (f == 2) {
            lista.put(Habilidad.CORTEJO, ticks);
            lista.put(Habilidad.TIRON, ticks);
        } else if (f == 3) {
            lista.put(Habilidad.CAMPANADA, ticks + 60);
        }
        // La de prueba sin nadie que le pegue no elige habilidades (no tiene objetivo): al
        // entrar en fase suelta la suya, para poder ver el cortejo y la Sentencia por RCON.
        if (prueba && objetivoPrueba == null && actual == null && estado != Estado.COSECHA) {
            empezar(f == 2 ? Habilidad.CORTEJO : Habilidad.CAMPANADA, null);
        }
    }

    /**
     * /lw hardcore parca habilidad <nombre>: suelta esa habilidad ya (para ver los avisos y
     * los golpes con un cliente). Tiron y Paso Umbral necesitan un jugador (quien lo pide).
     * Devuelve por que no, o null si ha empezado.
     */
    public String forzar(String nombre, Player quien) {
        if (estado != Estado.PELEA) return "no esta peleando (si acaba de salir, espera 2 s)";
        Habilidad h = switch (nombre) {
            case "siega" -> Habilidad.SIEGA;
            case "umbral", "paso" -> Habilidad.UMBRAL;
            case "tiron" -> Habilidad.TIRON;
            case "cortejo" -> Habilidad.CORTEJO;
            case "sentencia", "campanada" -> Habilidad.CAMPANADA;
            default -> null;
        };
        if (h == null) return "habilidades: siega, umbral, tiron, cortejo, sentencia";
        Player obj = quien != null && quien.getWorld() == cuerpo.getWorld() ? quien : objetivo();
        if ((h == Habilidad.TIRON || h == Habilidad.UMBRAL) && obj == null) return "necesita un jugador en el mundo";
        if (h == Habilidad.CORTEJO && !planideras.isEmpty()) return "ya hay planideras";
        if (actual != null) acabar();
        empezar(h, obj);
        return null;
    }

    // ========================================================== habilidades

    private boolean listo(Habilidad h) {
        return ticks >= lista.getOrDefault(h, 0L);
    }

    private int espera(Habilidad h) {
        int s = switch (h) {
            case SIEGA -> fase >= 3 ? a.siegaEsperaF3 : a.siegaEspera;
            case UMBRAL -> a.umbralEspera;
            case TIRON -> a.tironEspera;
            case CORTEJO -> a.planEspera;
            case CAMPANADA -> a.campEspera;
        };
        return (int) Math.round(s * 20 * (furia ? 0.7 : 1.0));
    }

    private void elegir(Player obj) {
        Location yo = cuerpo.getLocation();
        double dist = obj.getWorld() == yo.getWorld() ? obj.getLocation().distance(yo) : Double.MAX_VALUE;

        // Red de atasco (sec. 1.9): sin golpear ni saltar atasco-segundos -> encima de la presa, sin aviso.
        if (ticks - Math.max(ultimoGolpe, ultimoSalto) >= a.atasco * 20L) {
            Location sobre = obj.getLocation().clone();
            sobre.setDirection(obj.getLocation().getDirection().multiply(-1));
            hc.amenazas().teleportar(cuerpo, sobre);
            ultimoSalto = ticks;
            hc.plugin().bitacora().anotar("parca", "atasco", presaNombre);
            return;
        }
        if (siegaAlAterrizar && ticks - tironSolto >= 6 && obj.isOnGround()) {
            siegaAlAterrizar = false;
            empezar(Habilidad.SIEGA, obj);
            return;
        }
        if (listo(Habilidad.UMBRAL) && (dist > a.umbralDistancia
                || ticks - ultimaVision >= a.umbralSinVision * 20L
                || ticks - ultimoGolpe >= a.umbralSinGolpear * 20L)) {
            empezar(Habilidad.UMBRAL, obj);
            return;
        }
        if (fase >= 3 && listo(Habilidad.CAMPANADA)) {
            empezar(Habilidad.CAMPANADA, obj);
            return;
        }
        if (fase >= 2 && listo(Habilidad.CORTEJO) && planideras.isEmpty()) {
            empezar(Habilidad.CORTEJO, obj);
            return;
        }
        if (fase >= 2 && listo(Habilidad.TIRON) && dist >= a.tironMin && dist <= a.tironMax && cuerpo.hasLineOfSight(obj)) {
            empezar(Habilidad.TIRON, obj);
            return;
        }
        if (listo(Habilidad.SIEGA) && dist <= a.siegaRadio + 1) empezar(Habilidad.SIEGA, obj);
    }

    private void empezar(Habilidad h, Player obj) {
        actual = h;
        habDesde = ticks;
        toques = 0;
        puntos.clear();
        origen = cuerpo.getLocation().clone();
        // Siempre horizontal: sin objetivo sale de donde mira, y con la cabeza inclinada el
        // cono de la Siega se torcia.
        Vector d = direccionA(obj, origen).clone().setY(0);
        dir = d.lengthSquared() < 1e-4 ? new Vector(0, 0, 1) : d.normalize();
        Compat.setAttribute(cuerpo, "movement_speed", 0);
        World w = cuerpo.getWorld();
        switch (h) {
            case SIEGA -> {
                habDura = a.siegaAviso;
                blandir();
                prepararCono();
                Compat.sound(w, origen, "block.respawn_anchor.charge", 1.5f, 0.6f);
                Compat.sound(w, origen, "entity.warden.sonic_charge", 1.2f, 1.2f);
            }
            case UMBRAL -> {
                habDura = a.umbralAviso;
                destino = detras(obj);
                victimaPaso = obj.getUniqueId();
                obj.playSound(obj.getLocation(), "entity.enderman.teleport", SoundCategory.HOSTILE, 1f, 0.5f);
                Compat.sound(w, destino, "block.sculk_catalyst.bloom", 1.5f, 0.6f);
                hc.cordura().destello(obj, Component.text("Sientes frío en la nuca.", Paleta.TEXTO), 2);
            }
            case TIRON -> {
                habDura = a.tironAviso;
                victimaTiron = obj.getUniqueId();
                danoAlEmpezar = sumaDano();
                Compat.sound(w, origen, "entity.fishing_bobber.throw", 1.5f, 0.5f);
                Compat.sound(w, origen, "block.chain.place", 1.5f, 0.5f);
            }
            case CORTEJO -> {
                habDura = 30;
                blandir();
                Compat.sound(w, origen, "entity.wither.ambient", 1.2f, 1.6f);
                // Un anillo por planidera, donde va a salir cada una (a 4 bloques, repartidas).
                int n = cuantasPlanideras();
                double base = Math.atan2(dir.getZ(), dir.getX());
                for (int i = 0; i < n; i++) {
                    double ang = base + i * Math.PI * 2 / n;
                    puntos.add(Fx.ground(origen.clone().add(Math.cos(ang) * 4, 0, Math.sin(ang) * 4), 4));
                }
            }
            case CAMPANADA -> {
                habDura = a.campToques * a.campCada;
                prepararAnillo();
                ultimoToque = ticks;
            }
        }
        nombreBarra();
    }

    private void avanzar() {
        long t = ticks - habDesde;
        World w = cuerpo.getWorld();
        switch (actual) {
            case SIEGA -> {
                pintarSiega(w, t);
                if (t >= habDura) {
                    soltarSiega(w);
                    acabar();
                }
            }
            case UMBRAL -> {
                Fx.telegraph(w, destino, 1.2, RGB_PARCA);
                Compat.spawn(w, Compat.SCULK_SOUL, destino.clone().add(0, 0.3, 0), 2, 0.3, 0.1, 0.3, 0.01);
                Compat.spawn(w, Compat.SOUL_FIRE_FLAME, destino.clone().add(0, 0.2, 0), 2, 0.2, 0.6, 0.2, 0.01);
                if (t >= habDura) {
                    Compat.spawn(w, Compat.SOUL, cuerpo.getLocation().add(0, 1, 0), 20, 0.4, 0.8, 0.4, 0.02);
                    Compat.spawn(w, Compat.LARGE_SMOKE, cuerpo.getLocation().add(0, 1, 0), 12, 0.3, 0.8, 0.3, 0.01);
                    hc.amenazas().teleportar(cuerpo, destino);
                    seguirCascara();
                    Compat.spawn(w, Compat.SOUL, destino.clone().add(0, 1, 0), 20, 0.4, 0.8, 0.4, 0.02);
                    Compat.spawn(w, Compat.LARGE_SMOKE, destino.clone().add(0, 1, 0), 12, 0.3, 0.8, 0.3, 0.01);
                    Compat.sound(w, destino, "block.respawn_anchor.deplete", 1.4f, 0.6f);
                    // Aparece detras: a quien iba, la vista le tiembla desde la espalda.
                    Player v = victimaPaso == null ? null : hc.plugin().getServer().getPlayer(victimaPaso);
                    if (v != null && v.getWorld() == w && Fx.isFightable(v)) v.playHurtAnimation(ladoDe(v, destino));
                    ultimoSalto = ticks;
                    acabar();
                }
            }
            case TIRON -> avanzarTiron(w, t);
            case CORTEJO -> {
                if (t == 2 || t == 6 || t == 10) Compat.sound(w, origen, "block.bell.use", 1.5f, 0.7f);
                for (Location p : puntos) {
                    Fx.telegraph(w, p, 0.8, RGB_HUESO);
                    if (t % 4 == 0) Compat.spawn(w, Compat.SOUL, p.clone().add(0, 0.3, 0), 1, 0.2, 0.3, 0.2, 0.02);
                }
                if (t >= habDura) {
                    soltarCortejo();
                    acabar();
                }
            }
            case CAMPANADA -> avanzarCampanada(w, t);
        }
    }

    private void acabar() {
        if (actual != null) lista.put(actual, ticks + espera(actual));
        actual = null;
        destino = null;
        quitarCadena();
        if (cuerpo != null && cuerpo.isValid()) velocidad();
        nombreBarra();
    }

    // ------------------------------------------------------------------ Siega

    /**
     * El cono de la Siega asentado en el suelo, una vez por aviso: cuatro arcos que lo rellenan,
     * los dos bordes (para que se lea donde acaba) y el filo exterior. El cono no se mueve
     * durante el aviso (ella se queda quieta), asi que no hace falta buscar el suelo cada 2 ticks.
     */
    private void prepararCono() {
        cono.clear();
        filo.clear();
        double spread = Math.toRadians(a.siegaAngulo);
        for (double f : new double[]{0.35, 0.6, 0.85}) {
            double r = a.siegaRadio * f;
            Fx.arc(origen, dir, r, spread, Math.max(6, (int) (r * spread * 2.2)), l -> cono.add(Fx.ground(l, 4).add(0, 0.12, 0)));
        }
        Fx.arc(origen, dir, a.siegaRadio, spread, Math.max(8, (int) (a.siegaRadio * spread * 2.6)),
                l -> filo.add(Fx.ground(l, 4).add(0, 0.12, 0)));
        double base = Math.atan2(dir.getZ(), dir.getX());
        for (int lado = -1; lado <= 1; lado += 2) {
            double ang = base + lado * spread / 2;
            for (double d = 0.8; d <= a.siegaRadio; d += 0.5) {
                cono.add(Fx.ground(origen.clone().add(Math.cos(ang) * d, 0, Math.sin(ang) * d), 4).add(0, 0.12, 0));
            }
        }
    }

    /**
     * El aviso, cada 2 ticks: el cono entero en polvo que pasa del coral al rojo vivo segun se
     * acerca el tajo, el filo mas grueso y, en el ultimo tercio, llamas de alma en el filo: ya
     * no da tiempo a pensar, solo a salir.
     */
    private void pintarSiega(World w, long t) {
        double avance = Math.min(1, t / (double) Math.max(1, habDura));
        Particle.DustOptions polvo = Compat.dust(mezcla(RGB_SIEGA_DESDE, RGB_SIEGA_HASTA, avance), 1.5f);
        Particle.DustOptions grueso = Compat.dust(mezcla(RGB_SIEGA_DESDE, RGB_SIEGA_HASTA, avance), 2.2f);
        for (Location l : cono) Compat.spawn(w, Compat.DUST, l, 1, 0, 0, 0, 0, polvo);
        for (Location l : filo) Compat.spawn(w, Compat.DUST, l, 1, 0, 0, 0, 0, grueso);
        if (avance > 0.66 && t % 4 == 0) {
            for (int i = 0; i < filo.size(); i += 2) {
                Compat.spawn(w, Compat.SOUL_FIRE_FLAME, filo.get(i).clone().add(0, 0.1, 0), 1, 0.05, 0.1, 0.05, 0.01);
            }
        }
    }

    /** Dano verdadero a todo jugador dentro del cono (atraviesa paredes). El escudo de cara la anula. */
    private void soltarSiega(World w) {
        double spread = Math.toRadians(a.siegaAngulo);
        Fx.arc(origen, dir, a.siegaRadio * 0.7, spread, 6, l -> Compat.spawn(w, Compat.SWEEP_ATTACK,
                l.clone().add(0, 1, 0), 1));
        // El tajo pesa: llamas de alma y humo por todo el filo, almas de sculk en medio.
        for (Location l : filo) {
            Compat.spawn(w, Compat.SOUL_FIRE_FLAME, l.clone().add(0, 0.3, 0), 2, 0.1, 0.3, 0.1, 0.04);
            Compat.spawn(w, Compat.SMOKE, l.clone().add(0, 0.3, 0), 1, 0.1, 0.2, 0.1, 0.02);
        }
        Fx.arc(origen, dir, a.siegaRadio * 0.5, spread, 8, l -> Compat.spawn(w, Compat.SCULK_SOUL,
                l.clone().add(0, 0.6, 0), 1, 0.1, 0.2, 0.1, 0.02));
        Compat.soundPlayers(w, origen, "entity.player.attack.sweep", 1.5f, 0.5f);
        Compat.sound(w, origen, "entity.warden.attack_impact", 1.6f, 0.6f);
        Compat.sound(w, origen, "block.respawn_anchor.deplete", 1.2f, 0.8f);
        sacudir(origen, a.siegaRadio + 4);
        double coseno = Math.cos(spread / 2);
        for (Player v : Fx.playersNear(origen, a.siegaRadio + 0.5)) {
            Vector hacia = v.getLocation().toVector().subtract(origen.toVector());
            if (Math.abs(hacia.getY()) > 3) continue;
            hacia.setY(0);
            double d = hacia.length();
            if (d > a.siegaRadio) continue;
            if (d > 0.8 && hacia.normalize().dot(dir) < coseno) continue;
            if (escudo(v)) continue;
            double vidaMax = Compat.getAttribute(v, "max_health", 20);
            boolean quieta = quieta(v);
            double cantidad = Parca.siegaFraccion(a, factorR, quieta) * vidaMax;
            // El parte lee las marcas detras de " · ": asi sabe que el x2 fue por quedarse quieto.
            DanoVerdadero.aplicar(v, cantidad, a.siegaTope, cuerpo, quieta ? "Siega · quieto" : "Siega");
            if (hc.esHardcore(v)) hc.cordura().sumar(v, -a.siegaCordura);
        }
    }

    /** Escudo levantado y mirando a la PARCA: se anula el golpe y el escudo se enfria 5 s. */
    private boolean escudo(Player v) {
        if (!v.isBlocking()) return false;
        Vector mira = v.getLocation().getDirection().setY(0);
        Vector aElla = cuerpo.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
        if (mira.lengthSquared() < 1e-4 || aElla.lengthSquared() < 1e-4) return false;
        if (mira.normalize().dot(aElla.normalize()) <= 0.3) return false;
        v.setCooldown(Material.SHIELD, 100);
        Compat.soundPlayers(v.getWorld(), v.getLocation(), "item.shield.block", 1f, 0.8f);
        return true;
    }

    /** Cada 2 ticks, las posiciones de los jugadores a <= 32 (anillo de 20 = 2 s). */
    private void apuntarRastros() {
        Set<UUID> vistos = new HashSet<>();
        for (Player p : Fx.playersNear(cuerpo.getLocation(), 32)) {
            vistos.add(p.getUniqueId());
            ArrayDeque<double[]> q = rastro.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
            Location l = p.getLocation();
            q.addLast(new double[]{l.getX(), l.getY(), l.getZ()});
            while (q.size() > 20) q.pollFirst();
        }
        rastro.keySet().retainAll(vistos);
    }

    /** "Quieta" = no se ha desplazado mas de 0,5 bloques en los ultimos 2 s. */
    private boolean quieta(Player v) {
        ArrayDeque<double[]> q = rastro.get(v.getUniqueId());
        if (q == null || q.size() < 20) return false;
        Location l = v.getLocation();
        for (double[] p : q) {
            double dx = p[0] - l.getX(), dy = p[1] - l.getY(), dz = p[2] - l.getZ();
            if (dx * dx + dy * dy + dz * dz > 0.25) return false;
        }
        return true;
    }

    // ----------------------------------------------------------- Paso Umbral

    /**
     * A 3 bloques DETRAS de la presa, en el hueco 1x1x2 libre mas cercano a <= 3; si no hay,
     * en el bloque de la presa. Asi atraviesa cajas, pilares y pozos (sec. 1.7).
     */
    private Location detras(Player p) {
        Location l = p.getLocation();
        Vector atras = l.getDirection().setY(0);
        if (atras.lengthSquared() < 1e-4) atras = new Vector(0, 0, 1);
        atras.normalize().multiply(-3);
        Location c = l.clone().add(atras);
        Location hueco = Parca.huecoCerca(c, 3);
        Location fin = hueco != null ? hueco : l.clone();
        fin.setDirection(l.toVector().subtract(fin.toVector()).setY(0).lengthSquared() < 1e-4
                ? l.getDirection() : l.toVector().subtract(fin.toVector()));
        return fin;
    }

    // ----------------------------------------------------------------- Tiron

    private double sumaDano() {
        double s = 0;
        for (double d : hc.amenazas().danoLogico(cuerpo).values()) s += d;
        return s;
    }

    /**
     * Una cadena de la guadana a la presa. Si al final hay linea de vision y nadie la ha roto
     * (rompe = 3 % de su vida logica durante el aviso, P-16), la arrastra hacia ella con
     * Lentitud I 2 s y la siguiente Siega empieza al aterrizar.
     */
    private void avanzarTiron(World w, long t) {
        Player v = victimaTiron == null ? null : hc.plugin().getServer().getPlayer(victimaTiron);
        if (v == null || v.getWorld() != w || !Fx.isFightable(v)) {
            acabar();
            return;
        }
        Location desde = mano();
        Location hasta = v.getLocation().add(0, 1.0, 0);
        pintarCadena(desde, hasta);
        if (t % 4 == 0) {
            Compat.spawn(w, Compat.SOUL_FIRE_FLAME, hasta, 2, 0.15, 0.25, 0.15, 0.0);
            Particle.DustOptions polvo = Compat.dust(RGB_HUESO, 0.8f);
            Fx.beam(desde, hasta, 0.9, l -> Compat.spawn(w, Compat.DUST, l, 1, 0.03, 0.03, 0.03, 0, polvo));
        }
        if (sumaDano() - danoAlEmpezar >= a.tironRompe * vidaFinal()) {
            Component roto = Component.text("La cadena se rompe.", Paleta.DETALLE);
            for (Player o : Fx.viewersNear(cuerpo.getLocation(), 32)) hc.cordura().destello(o, roto, 2);
            Compat.sound(w, cuerpo.getLocation(), "block.chain.break", 1.5f, 0.6f);
            Location medio = desde.clone().add(hasta.toVector().subtract(desde.toVector()).multiply(0.5));
            Compat.spawn(w, Compat.CRIT, medio, 20, 0.4, 0.4, 0.4, 0.2);
            acabar();
            return;
        }
        if (t < habDura) return;
        if (cuerpo.hasLineOfSight(v)) {
            Vector hacia = cuerpo.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
            if (hacia.lengthSquared() > 1e-4) {
                hacia.normalize().multiply(a.tironFuerza).setY(0.12);
                v.setVelocity(hacia);
            }
            Compat.apply(v, "slowness", 40, 0);
            Compat.sound(w, v.getLocation(), "block.chain.hit", 1.5f, 0.5f);
            Compat.sound(w, v.getLocation(), "entity.warden.attack_impact", 1.0f, 0.8f);
            v.playHurtAnimation(ladoDe(v, cuerpo.getLocation()));
            siegaAlAterrizar = true;
            tironSolto = ticks;
        }
        acabar();
    }

    /**
     * La mano de la guadana, mas o menos: de ahi sale la cadena. Se calcula con el alto del
     * cuerpo que se ve (el maniqui escalado o el esqueleto) para que salga de la mano y no
     * del pecho ni de los pies.
     */
    private Location mano() {
        LivingEntity c = cascara != null && cascara.isValid() ? cascara : cuerpo;
        Location l = c.getLocation();
        double alto = c.getHeight();
        Vector frente = l.getDirection().setY(0);
        if (frente.lengthSquared() < 1e-4) frente = dir == null ? new Vector(0, 0, 1) : dir.clone();
        frente.normalize();
        Vector derecha = new Vector(-frente.getZ(), 0, frente.getX());
        return l.add(0, 0.62 * alto, 0).add(derecha.multiply(0.2 * alto)).add(frente.multiply(0.15 * alto));
    }

    /**
     * La cadena del Tiron, de verdad: bloques de cadena de hierro (BlockDisplay) puestos en fila
     * de la mano a la presa y girados en su direccion, a brillo maximo para que se vea de noche.
     * Se recoloca cada 2 ticks (interpolado, sin tirones) y se quita al acabar el aviso.
     */
    private void pintarCadena(Location desde, Location hasta) {
        World w = desde.getWorld();
        Vector d = hasta.toVector().subtract(desde.toVector());
        double largo = d.length();
        if (w == null || largo < 0.3) {
            quitarCadena();
            return;
        }
        int n = (int) Math.max(1, Math.min(24, Math.ceil(largo / ESLABON)));
        double tramo = largo / n;
        Vector u = d.multiply(1 / largo);
        Quaternionf giro = new Quaternionf().rotationTo(new Vector3f(0, 1, 0),
                new Vector3f((float) u.getX(), (float) u.getY(), (float) u.getZ()));
        float ancho = 0.9f;
        // El modelo de la cadena va de 0 a 1 en cada eje: se centra en X y Z antes de girar.
        Vector3f centrado = giro.transform(new Vector3f(-ancho / 2, 0, -ancho / 2));
        Transformation tr = new Transformation(centrado, giro, new Vector3f(ancho, (float) tramo, ancho), new Quaternionf());
        while (cadena.size() > n) Fx.safeRemove(cadena.remove(cadena.size() - 1));
        for (int i = 0; i < n; i++) {
            Location en = desde.clone().add(u.clone().multiply(i * tramo));
            // Sin giro propio: la rotacion va entera en la transformacion.
            en.setYaw(0);
            en.setPitch(0);
            BlockDisplay b = i < cadena.size() ? cadena.get(i) : null;
            if (b != null && b.isValid()) {
                b.teleport(en);
                b.setInterpolationDelay(0);
                b.setTransformation(tr);
                continue;
            }
            BlockDisplay nuevo = w.spawn(en, BlockDisplay.class, e -> {
                e.setBlock(Material.IRON_CHAIN.createBlockData());
                e.setPersistent(false);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setTeleportDuration(2);
                e.setInterpolationDuration(2);
                e.setTransformation(tr);
            });
            if (i < cadena.size()) cadena.set(i, nuevo);
            else cadena.add(nuevo);
        }
    }

    private void quitarCadena() {
        for (BlockDisplay b : cadena) Fx.safeRemove(b);
        cadena.clear();
    }

    // --------------------------------------------------------------- Cortejo

    /** base + M planideras, con tope. */
    private int cuantasPlanideras() {
        return Math.max(1, Math.min(a.planTope, a.planBase + extra));
    }

    /**
     * base + M planideras (tope 6): mini withers (scale planideras.escala) que salen de los
     * anillos del aviso y se ponen a orbitar a la PARCA como parte de su cortejo. Sin IA (ni
     * calaveras, ni bloques rotos, ni regenerarse), sin gravedad, mudas y SIN la barra de jefe
     * del wither: la unica barra de la pelea es la de la PARCA. No pegan: lloran (lamento, que
     * quita cordura). Mientras quede una, la PARCA recibe x0,5; se matan a golpes (Amenazas
     * solo deja que les peguen jugadores). Parca.onDisparo y onExplotar son el doble cerrojo.
     */
    private void soltarCortejo() {
        int n = Math.min(cuantasPlanideras(), puntos.size());
        double vida = a.planVida * (1 + a.planVidaPorNivel * (nivel - 1));
        World w = cuerpo.getWorld();
        Location centro = cuerpo.getLocation();
        huecos.clear();
        for (int i = 0; i < n; i++) {
            Location l = puntos.get(i).clone().add(0, 1, 0);
            Wither wi = hc.amenazas().invocar(Wither.class, l, "planidera", nivel,
                    Paleta.nombre("Plañidera", Paleta.HUESO), e -> {
                        if (presa != null) e.getPersistentDataContainer().set(Marcas.PRESA, PersistentDataType.STRING, presa.toString());
                        e.setAI(false);
                        e.setGravity(false);
                        e.setSilent(true);
                        // Antes de que nadie la vea: sin esto el cliente pinta la barra morada del wither.
                        ocultarBarra(e);
                        Compat.setAttribute(e, "scale", a.planEscala);
                        Compat.setAttribute(e, "max_health", vida);
                        e.setHealth(Math.max(1, vida));
                        Compat.setAttribute(e, "knockback_resistance", 1.0);
                    });
            if (wi == null) continue;
            planideras.add(wi);
            huecos.put(wi.getUniqueId(), Math.atan2(l.getZ() - centro.getZ(), l.getX() - centro.getX()));
            Compat.spawn(w, Compat.SOUL, l, 16, 0.3, 0.4, 0.3, 0.04);
            Compat.spawn(w, Compat.LARGE_SMOKE, l, 8, 0.3, 0.3, 0.3, 0.01);
        }
        if (!planideras.isEmpty()) {
            planNacio = ticks;
            planActivas = true;
            planCaducaron = false;
            Compat.sound(w, centro, "entity.wither.ambient", 1.4f, 1.8f);
        }
    }

    /**
     * La barra de jefe del wither fuera: invisible y sin nadie. Con visible en false, el
     * servidor no la manda ni a los que empiecen a verla despues (ServerBossEvent.addPlayer).
     */
    private static void ocultarBarra(Wither w) {
        try {
            org.bukkit.boss.BossBar b = w.getBossBar();
            if (b == null) return;
            b.setVisible(false);
            b.removeAll();
        } catch (Throwable ignorado) {
            // Si Paper cambia la API, la barra se veria: feo, pero la pelea sigue.
        }
    }

    /**
     * Donde va la planidera "id" ahora: en circulo alrededor de la PARCA, a la altura del
     * pecho, subiendo y bajando. El primer segundo se cierra desde los 4 bloques del anillo
     * en el que salio hasta planideras.radio, sin saltos.
     */
    private Location orbita(Wither wi, Location centro, long vida) {
        double hueco = huecos.getOrDefault(wi.getUniqueId(), 0.0);
        double ang = hueco + vida * VELOCIDAD_ORBITA;
        double entra = Math.max(0, 1 - vida / 20.0);
        double radio = a.planRadio + (4 - a.planRadio) * entra;
        double alto = a.planAltura * (1 - entra) + entra + 0.3 * Math.sin(vida * 0.12 + hueco * 3);
        Location l = centro.clone().add(Math.cos(ang) * radio, alto, Math.sin(ang) * radio);
        // Mirando hacia donde vuela (la tangente del circulo): parece que vuelen solas.
        l.setDirection(new Vector(-Math.sin(ang), 0, Math.cos(ang)));
        return l;
    }

    /** Radianes por tick de la orbita: una vuelta cada 5 s. */
    private static final double VELOCIDAD_ORBITA = Math.PI * 2 / 100;

    /**
     * Mantiene las planideras: orbita (cada 2 ticks), hilo de almas a la PARCA, lamento,
     * caducidad a los vida-ticks y, si caen todas antes de 40 s, la PARCA queda aturdida 3 s
     * y recibe +25 % (P-15).
     */
    private void planideras() {
        if (!planActivas) return;
        planideras.removeIf(v -> !v.isValid() || v.isDead());
        if (!planideras.isEmpty() && ticks - planNacio >= a.planVidaTicks) {
            for (Wither v : planideras) {
                Compat.spawn(v.getWorld(), Compat.SOUL, v.getLocation().add(0, 0.5, 0), 10, 0.2, 0.3, 0.2, 0.03);
                Fx.safeRemove(v);
            }
            planideras.clear();
            planCaducaron = true;
        }
        if (planideras.isEmpty()) {
            planActivas = false;
            if (!planCaducaron && ticks - planNacio < 800) aturdir();
            return;
        }
        World w = cuerpo.getWorld();
        Location centro = cuerpo.getLocation();
        long vida = ticks - planNacio;
        for (Wither v : planideras) {
            if (v.getWorld() == w) hc.amenazas().teleportar(v, orbita(v, centro, vida));
        }
        if (ticks % 10 == 0) {
            for (Wither v : planideras) {
                if (v.getWorld() != w) continue;
                Fx.beam(v.getLocation().add(0, 0.4, 0), cuerpo.getLocation().add(0, 1.4, 0), 0.5,
                        l -> Compat.spawn(w, Compat.SOUL, l, 1, 0, 0, 0, 0));
            }
        }
        // Por si algo le devuelve la barra (otro plugin, un reinicio de la entidad): cada segundo.
        if (ticks % 20 == 0) for (Wither v : planideras) ocultarBarra(v);
        lamentos(w, vida);
    }

    /**
     * El lamento: cada planidera, cada lamento-segundos (escalonadas para que no suenen a la
     * vez), llora hacia el objetivo si esta a lamento-radio: un hilo de almas hasta el y
     * -cordura. No quita vida: las planideras son presion, no dano. La respuesta es matarlas.
     */
    private void lamentos(World w, long vida) {
        long periodo = Math.max(20, a.planLamento * 20L);
        Player obj = objetivo();
        if (obj == null || obj.getWorld() != w) return;
        for (int i = 0; i < planideras.size(); i++) {
            if (Math.floorMod(vida - 10 - 12L * i, periodo) != 0) continue;
            Wither v = planideras.get(i);
            Location desde = v.getLocation().add(0, 0.5, 0);
            if (desde.distanceSquared(obj.getLocation()) > a.planLamentoRadio * a.planLamentoRadio) continue;
            Fx.beam(desde, obj.getLocation().add(0, 1.1, 0), 0.35,
                    l -> Compat.spawn(w, Compat.SOUL, l, 1, 0.02, 0.02, 0.02, 0));
            Compat.sound(w, desde, "entity.wither.ambient", 0.8f, 1.9f);
            obj.playSound(obj.getLocation(), "entity.allay.death", SoundCategory.HOSTILE, 0.5f, 0.5f);
            if (hc.esHardcore(obj)) hc.cordura().sumar(obj, -a.planCordura);
        }
    }

    private void aturdir() {
        aturdidaHasta = ticks + a.planAturdir * 20L;
        if (actual != null) acabar();
        cuerpo.setAI(false);
        huecos.clear();
        // Se dobla: el maniqui se agacha mientras dura (vuelve de pie al acabar, en pelear()).
        postura(Pose.SNEAKING);
        Component texto = Component.text("La Parca se tambalea.", Paleta.DETALLE);
        for (Player o : Fx.viewersNear(cuerpo.getLocation(), 32)) hc.cordura().destello(o, texto, 2);
        Compat.sound(cuerpo.getWorld(), cuerpo.getLocation(), "entity.wither_skeleton.hurt", 1.5f, 0.5f);
        Compat.sound(cuerpo.getWorld(), cuerpo.getLocation(), "block.bell.resonate", 1.2f, 0.7f);
        hc.plugin().bitacora().anotar("parca", "aturdida", presaNombre);
    }

    // ------------------------------------------------------------- Campanada

    /** El anillo de la Sentencia asentado en el suelo, una vez por campanada (ella no se mueve). */
    private void prepararAnillo() {
        anillo.clear();
        anilloDentro.clear();
        int pts = Math.max(24, (int) (a.campRadio * 10));
        Fx.ring(origen, a.campRadio, pts, l -> anillo.add(Fx.ground(l, 4).add(0, 0.12, 0)));
        Fx.ring(origen, a.campRadio * 0.55, Math.max(12, pts / 2), l -> anilloDentro.add(Fx.ground(l, 4).add(0, 0.12, 0)));
    }

    /**
     * Cinco toques cada 24 ticks. El anillo late (mas grueso justo despues de cada toque) y se
     * oscurece del coral al granate con cada campanada; en el 3.o, titulo a quien este a <= 12;
     * en el 4.o, aviso en la barra a quien siga dentro; en el 5.o, la Sentencia.
     */
    private void avanzarCampanada(World w, long t) {
        double oscuro = a.campToques <= 1 ? 1 : toques / (double) a.campToques;
        double pulso = Math.max(0, 1 - (ticks - ultimoToque) / 12.0);
        Particle.DustOptions polvo = Compat.dust(mezcla(RGB_SENTENCIA_DESDE, RGB_SENTENCIA_HASTA, oscuro),
                (float) (1.3 + 1.2 * pulso));
        for (Location l : anillo) Compat.spawn(w, Compat.DUST, l, 1, 0, 0, 0, 0, polvo);
        if (t % 4 == 0) for (Location l : anilloDentro) Compat.spawn(w, Compat.DUST, l, 1, 0, 0, 0, 0, polvo);
        // Humo y almas que suben de dentro del anillo: cuanto mas cerca del final, mas.
        if (t % 4 == 0 && !anillo.isEmpty()) {
            ThreadLocalRandom azar = ThreadLocalRandom.current();
            for (int i = 0; i < 2 + toques * 2; i++) {
                Location l = anillo.get(azar.nextInt(anillo.size()));
                Location dentro = origen.clone().add(l.toVector().subtract(origen.toVector()).multiply(azar.nextDouble()));
                Compat.spawn(w, i % 2 == 0 ? Compat.SMOKE : Compat.SCULK_SOUL, dentro.add(0, 0.3, 0), 1, 0.1, 0.3, 0.1, 0.01);
            }
        }
        while (toques < a.campToques && t >= (long) (toques + 1) * a.campCada) {
            toques++;
            ultimoToque = ticks;
            double avance = a.campToques <= 1 ? 1 : (toques - 1) / (double) (a.campToques - 1);
            Compat.sound(w, origen, "block.bell.use", 2.0f, (float) (0.8 - 0.3 * avance));
            Compat.sound(w, origen, "entity.warden.heartbeat", 2.0f, (float) (0.7 + 0.3 * avance));
            Fx.shockwave(w, origen, a.campRadio, Compat.SOUL, 6);
            nombreBarra();
            if (toques == 3) {
                Title titulo = Paleta.titulo(Paleta.muerte("Aléjate"), "Sentencia · 3/" + a.campToques,
                        Duration.ofMillis(100), Duration.ofMillis(1600), Duration.ofMillis(400));
                for (Player o : Fx.viewersNear(origen, 12)) o.showTitle(titulo);
            }
            if (toques == 4) {
                Component sal = Component.text("Sal del anillo.", Paleta.AVISO);
                for (Player o : Fx.playersNear(origen, a.campRadio)) hc.cordura().destello(o, sal, 2);
            }
            if (toques >= a.campToques) {
                sentencia();
                acabar();
                return;
            }
        }
    }

    /**
     * La Sentencia (la 5.a campanada; no "Juicio": el servidor ya tiene otro sistema con ese
     * nombre): dano verdadero (tope 90 %, ley 5), -25 de cordura y Oscuridad 1,5 s a todo
     * jugador a <= radio. Suena y se ve lejos, y a todos los de alrededor les tiembla la vista.
     */
    private void sentencia() {
        World w = cuerpo.getWorld();
        Compat.spawn(w, Compat.SOUL, origen.clone().add(0, 1, 0), 80, a.campRadio / 2, 1, a.campRadio / 2, 0.05);
        Compat.spawn(w, Compat.SCULK_SOUL, origen.clone().add(0, 1, 0), 40, a.campRadio / 2, 0.8, a.campRadio / 2, 0.03);
        Compat.spawn(w, Compat.LARGE_SMOKE, origen.clone().add(0, 0.5, 0), 50, a.campRadio / 2, 0.5, a.campRadio / 2, 0.02);
        Compat.spawn(w, Compat.SONIC_BOOM, origen.clone().add(0, 1.2, 0), 1);
        for (int i = 0; i < anillo.size(); i += 2) {
            Compat.spawn(w, Compat.SOUL_FIRE_FLAME, anillo.get(i).clone().add(0, 0.2, 0), 2, 0.1, 0.4, 0.1, 0.03);
        }
        Compat.sound(w, origen, "entity.warden.sonic_boom", 3f, 0.6f);
        Compat.sound(w, origen, "block.sculk_shrieker.shriek", 2f, 0.5f);
        Compat.sound(w, origen, "block.respawn_anchor.deplete", 2f, 0.5f);
        double r2 = a.campRadio * a.campRadio;
        for (Player v : Fx.playersNear(origen, a.campRadio + 1)) {
            double dx = v.getLocation().getX() - origen.getX(), dz = v.getLocation().getZ() - origen.getZ();
            if (dx * dx + dz * dz > r2) continue;
            double vidaMax = Compat.getAttribute(v, "max_health", 20);
            DanoVerdadero.aplicar(v, Parca.sentenciaFraccion(a, factorR) * vidaMax, a.campTope, cuerpo, "Sentencia");
            Compat.apply(v, "darkness", 30, 0);
            if (hc.esHardcore(v)) hc.cordura().sumar(v, -a.campCordura);
        }
        sacudir(origen, a.campRadio + 8);
    }

    // =========================================================== barra y aura

    /**
     * "Parca" con su degradado y, detras, lo que esta haciendo: la barra es tambien un aviso
     * (Siega, Tiron, Cortejo, Sentencia k/5), en rojo claro mientras dura el golpe que viene.
     */
    private Component tituloBarra() {
        Component resto;
        if (actual != null && actual != Habilidad.UMBRAL) {
            String t = switch (actual) {
                case SIEGA -> "Siega";
                case TIRON -> "Tirón";
                case CORTEJO -> "Cortejo";
                case CAMPANADA -> "Sentencia " + Math.max(1, toques) + "/" + a.campToques;
                default -> "";
            };
            resto = Component.text(t, Paleta.AVISO);
        } else if (furia) {
            resto = Component.text("Furia", Paleta.AVISO);
        } else {
            resto = Component.text("Nv. " + nivel, Paleta.TEXTO);
        }
        return Paleta.muerte("Parca").append(Component.text(" · ", Paleta.SEPARADOR)).append(resto);
    }

    private void nombreBarra() {
        if (barra != null) barra.name(tituloBarra());
    }

    /** Para los marcados y quien este a <= 48; se recalcula cada segundo. */
    private void refrescarBarra() {
        if (barra == null) {
            barra = BossBar.bossBar(tituloBarra(), (float) Amenazas.fraccion(cuerpo), BossBar.Color.RED,
                    BossBar.Overlay.NOTCHED_10);
        }
        barra.progress((float) Math.max(0, Math.min(1, Amenazas.fraccion(cuerpo))));
        Set<UUID> ahora = new HashSet<>();
        for (Player p : Fx.viewersNear(cuerpo.getLocation(), 48)) ahora.add(p.getUniqueId());
        for (UUID id : marcados) {
            Player m = hc.plugin().getServer().getPlayer(id);
            if (m != null && m.getWorld() == cuerpo.getWorld()) ahora.add(id);
        }
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

    /**
     * Presencia pasiva (sec. 1.5): almas y ceniza a los pies cada 10 ticks, el ambiente cada 6 s
     * y una campanada suave cada 10 s en su sitio (si oyes campanas sin avisos tuyos, hay una
     * PARCA cerca de otro).
     */
    private void presencia() {
        World w = cuerpo.getWorld();
        Location l = cuerpo.getLocation();
        if (ticks % 10 == 0) {
            Compat.spawn(w, Compat.SOUL, l.clone().add(0, 0.2, 0), 2, 0.4, 0.1, 0.4, 0.01);
            Compat.spawn(w, Compat.ASH, l.clone().add(0, 0.2, 0), 4, 0.6, 0.2, 0.6, 0.01);
            Compat.spawn(w, Compat.SMOKE, l.clone().add(0, 0.1, 0), 1, 0.3, 0.05, 0.3, 0.005);
            Compat.spawn(w, Compat.SCULK_SOUL, cuerpo.getEyeLocation().add(cuerpo.getLocation().getDirection().multiply(0.6))
                    .subtract(0, 0.6, 0), 1, 0.05, 0.05, 0.05, 0.0);
        }
        if (estado == Estado.PELEA && ticks % 120 == 0) Compat.sound(w, l, "entity.wither_skeleton.ambient", 1f, 0.5f);
        if (ticks % 200 == 0) Compat.sound(w, l, "block.bell.use", 0.4f, 0.5f);
    }

    // =================================================================== fin

    /** La presa se desconecta sin etiqueta: se queda quieta espera-desconexion-segundos. */
    public void esperar() {
        if (estado == Estado.FIN || estado == Estado.COSECHA) return;
        if (actual != null) acabar();
        estado = Estado.ESPERA;
        esperaHasta = System.currentTimeMillis() + a.esperaDesconexion * 1000L;
        cuerpo.setAI(false);
        cuerpo.setInvulnerable(false);
        for (Wither v : planideras) Fx.safeRemove(v);
        planideras.clear();
        planActivas = false;
        hc.plugin().bitacora().anotar("parca", "espera", presaNombre, a.esperaDesconexion + " s");
    }

    /** Vuelve la presa mientras ella esperaba: sigue la pelea. */
    public void reanudar() {
        if (estado != Estado.ESPERA) return;
        estado = Estado.PELEA;
        cuerpo.setAI(true);
        velocidad();
        ultimoGolpe = ticks;
        ultimoSalto = ticks;
        hc.plugin().bitacora().anotar("parca", "reanuda", presaNombre);
    }

    /**
     * La presa ha muerto (por ella o por lo que sea): 3 s quieta con una helice de almas y una
     * campanada, y se va sin botin (sec. 1.9, "Cosecha").
     */
    public void cosecha(boolean porElla) {
        if (estado == Estado.FIN || estado == Estado.COSECHA) return;
        if (actual != null) acabar();
        estado = Estado.COSECHA;
        cosechaDesde = ticks;
        cuerpo.setAI(false);
        cuerpo.setInvulnerable(true);
        for (Wither v : planideras) Fx.safeRemove(v);
        planideras.clear();
        Compat.sound(cuerpo.getWorld(), cuerpo.getLocation(), "block.bell.use", 3f, 0.5f);
        hc.plugin().bitacora().anotar("parca", "cosecha", presaNombre, porElla ? "por ella" : "por otra cosa",
                (System.currentTimeMillis() - nacio) / 1000 + " s");
        gestor.telemetria("cosecha", this, null, null);
    }

    private void cosechar() {
        World w = cuerpo.getWorld();
        long t = ticks - cosechaDesde;
        if (t % 4 == 0) {
            double h = Math.min(3, t / 20.0);
            Fx.helix(cuerpo.getLocation(), 0.8, h, 12, 2, l -> Compat.spawn(w, Compat.SOUL, l, 1, 0, 0, 0, 0));
        }
        if (t >= TICKS_COSECHA) {
            limpiar();
            graciaMarcados();
            estado = Estado.FIN;
        }
    }

    /** Ha caido. El botin lo reparte el gestor con el dano logico (se lee aqui, en su EntityDeathEvent). */
    public void alMorir() {
        if (pagada || estado == Estado.FIN) return;
        pagada = true;
        Map<UUID, Double> dano = hc.amenazas().danoLogico(cuerpo);
        double vida = vidaFinal();
        long segundos = (System.currentTimeMillis() - nacio) / 1000;
        World w = cuerpo.getWorld();
        Location l = cuerpo.getLocation();
        Compat.sound(w, l, "block.bell.use", 4f, 0.4f);
        Compat.sound(w, l, "entity.wither_skeleton.death", 1.5f, 0.5f);
        Compat.sound(w, l, "entity.warden.death", 1.5f, 0.7f);
        Compat.sound(w, l, "block.respawn_anchor.deplete", 2f, 0.4f);
        Fx.helix(l, 1.0, 3.0, 40, 3, p -> Compat.spawn(w, Compat.SOUL, p, 1, 0, 0, 0, 0.01));
        Compat.spawn(w, Compat.SOUL_FIRE_FLAME, l.clone().add(0, 1, 0), 40, 0.6, 1.0, 0.6, 0.05);
        sacudir(l, 16);
        // El cuerpo que se ve cae y se deshace en almas (Parca.despedida); la limpieza ya no lo toca.
        if (cascara != null && cascara.isValid()) {
            Mannequin caido = cascara;
            cascara = null;
            gestor.despedida(caido);
        }
        hc.seguro("parca", () -> gestor.pagar(this, dano, vida, segundos));
        limpiar();
        graciaMarcados();
        if (presa != null) gestor.borrarPendiente(presa);
        estado = Estado.FIN;
    }

    /**
     * Se va sin botin (retirada, puerta, desconexion, cansada...). Humo, almas y una campanada.
     *
     * @param aviso mensaje a quien este a <= 48 (P-25), o null
     */
    public void irse(String motivo, Component aviso) {
        if (estado == Estado.FIN) return;
        if (cuerpo != null && cuerpo.isValid()) {
            World w = cuerpo.getWorld();
            Location l = cuerpo.getLocation();
            Compat.spawn(w, Compat.LARGE_SMOKE, l.clone().add(0, 1, 0), 40, 0.5, 1, 0.5, 0.02);
            Compat.spawn(w, Compat.SOUL, l.clone().add(0, 1, 0), 30, 0.5, 1, 0.5, 0.02);
            Compat.sound(w, l, "block.bell.use", 2f, 0.5f);
            if (aviso != null) for (Player o : Fx.viewersNear(l, 48)) o.sendMessage(aviso);
        }
        hc.plugin().bitacora().anotar("parca", "se-va", "sin botin", presaNombre, motivo);
        gestor.telemetria("se-va", this, null, null);
        limpiar();
        graciaMarcados();
        estado = Estado.FIN;
    }

    /** Fin de la PARCA para sus marcados: gracia-minutos sin contar en la Huella. */
    private void graciaMarcados() {
        if (hc.huella() == null) return;
        for (UUID id : marcados) {
            Player m = hc.plugin().getServer().getPlayer(id);
            if (m != null && hc.esHardcore(m)) hc.seguro("huella", () -> hc.huella().gracia(m));
        }
    }

    /** Retira todo lo suyo (idempotente): barra, planideras, maniqui, cuerpo y la pelea de la tarea de 2 ticks. */
    public void limpiar() {
        quitarBarra();
        for (Wither v : planideras) Fx.safeRemove(v);
        planideras.clear();
        Fx.safeRemove(cascara);
        cascara = null;
        quitarCadena();
        Fx.safeRemove(cuerpo);
        rastro.clear();
        if (hc.amenazas() != null) hc.amenazas().quitarPelea(this);
        estado = Estado.FIN;
    }

    // ============================================================ ParcaViva (1.2.0)
    // Lo que el gestor lee de la pelea. Los campos siguen ahi (la pelea los usa por dentro);
    // desde fuera se pasa por estos, que son los mismos que da la anomalia de EDM.

    @Override
    public Estado estado() {
        return estado;
    }

    @Override
    public boolean prueba() {
        return prueba;
    }

    @Override
    public UUID presa() {
        return presa;
    }

    @Override
    public String presaNombre() {
        return presaNombre;
    }

    @Override
    public Set<UUID> marcados() {
        return marcados;
    }

    @Override
    public LivingEntity cuerpo() {
        return cuerpo;
    }

    @Override
    public int nivel() {
        return nivel;
    }

    @Override
    public int repeticiones() {
        return repeticiones;
    }

    @Override
    public int extra() {
        return extra;
    }

    @Override
    public int fase() {
        return fase;
    }

    @Override
    public int extrasGrupo() {
        return extrasGrupo;
    }

    @Override
    public String tipo() {
        return "reserva";
    }
}
