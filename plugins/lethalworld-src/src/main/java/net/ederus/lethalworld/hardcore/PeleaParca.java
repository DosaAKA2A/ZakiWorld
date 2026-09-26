package net.ederus.lethalworld.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vex;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

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
 */
final class PeleaParca implements Runnable {

    enum Estado { APARECE, PELEA, ESPERA, COSECHA, FIN }

    private enum Habilidad { SIEGA, UMBRAL, TIRON, CORTEJO, CAMPANADA }

    static final TextColor ROJO = ComandoCalamity.ROJO;
    static final TextColor HUESO = TextColor.color(0xD8D2C4);
    private static final int TICKS_APARICION = 40;
    private static final int TICKS_COSECHA = 60;

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
    private double danoAlEmpezar;
    private UUID victimaTiron;
    private int toques;
    private final EnumMap<Habilidad, Long> lista = new EnumMap<>(Habilidad.class);
    private long ultimoGolpe;
    private long ultimoSalto;
    private long ultimaVision;
    private boolean siegaAlAterrizar;
    private long tironSolto;

    private final List<Vex> planideras = new ArrayList<>();
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
        WitherSkeleton ws = am.invocar(WitherSkeleton.class, bajo, "parca", nivel,
                Component.text("Parca", ROJO), e -> {
                    if (presa != null) e.getPersistentDataContainer().set(Marcas.PRESA, PersistentDataType.STRING, presa.toString());
                    e.setAI(false);
                    e.setInvulnerable(true);
                    e.setSilent(true);
                    Compat.setAttribute(e, "scale", a.escala);
                    Compat.setAttribute(e, "knockback_resistance", 1.0);
                    Compat.setAttribute(e, "movement_speed", a.velocidad);
                    Compat.setAttribute(e, "follow_range", 64);
                    Compat.setAttribute(e, "step_height", 1.5);
                    Compat.setAttribute(e, "attack_damage", pe.golpe);
                    vestir(e, a);
                });
        if (ws == null) return null;
        pe.cuerpo = ws;
        am.vidaLogica(ws, vida);
        if (fraccion < 1) am.ponerFraccion(ws, fraccion);
        am.ancla(ws, sitio);
        pe.fase = Math.max(1, Math.min(3, fase));
        // Con la fase ya avanzada (lo pendiente), sus habilidades arrancan listas.
        if (pe.fase >= 3) pe.lista.put(Habilidad.CAMPANADA, 60L);
        am.registrarPelea(pe);

        for (Player p : marcados) pe.alMarcar(p);
        Compat.sound(sitio.getWorld(), sitio, "block.respawn_anchor.deplete", 4f, 0.5f);
        Compat.sound(sitio.getWorld(), sitio, "block.bell.use", 4f, 0.5f);
        return pe;
    }

    /** Guadana de netherita con brillo, ropa de cuero casi negra y, si hay textura, su cabeza. */
    private static void vestir(WitherSkeleton e, Parca.Ajustes a) {
        EntityEquipment eq = e.getEquipment();
        if (eq == null) return;
        ItemStack guadana = new ItemStack(Material.NETHERITE_HOE);
        ItemMeta gm = guadana.getItemMeta();
        gm.displayName(Component.text("Guadaña", ROJO).decoration(TextDecoration.ITALIC, false));
        gm.setEnchantmentGlintOverride(true);
        guadana.setItemMeta(gm);
        eq.setItemInMainHand(guadana);
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

    /** Lo que ve un marcado al quedar marcado: titulo P-08; si iba montado, abajo. */
    private void alMarcar(Player p) {
        if (p.isInsideVehicle()) p.leaveVehicle();
        p.showTitle(Title.title(Component.text("PARCA", ROJO),
                Component.text("Te quedaste demasiado tiempo.", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
    }

    // ================================================================ consultas

    /** Viva a efectos de los jugadores (persigue, cosecha, presencia). */
    boolean vivaParaJugadores() {
        return estado != Estado.FIN && cuerpo != null && cuerpo.isValid() && !cuerpo.isDead();
    }

    /** Admite marcados extra (no mientras cosecha, espera o se va). */
    boolean aceptaMarcados() {
        return (estado == Estado.PELEA || estado == Estado.APARECE) && vivaParaJugadores();
    }

    boolean esCuerpo(Entity e) {
        return e != null && cuerpo != null && cuerpo.getUniqueId().equals(e.getUniqueId());
    }

    boolean esPlanidera(Entity e) {
        if (e == null || planideras.isEmpty()) return false;
        for (Vex v : planideras) if (v.getUniqueId().equals(e.getUniqueId())) return true;
        return false;
    }

    boolean hayMarcadoEnMundo() {
        for (UUID id : marcados) {
            Player m = hc.plugin().getServer().getPlayer(id);
            if (m != null && cuerpo != null && m.getWorld() == cuerpo.getWorld()) return true;
        }
        return false;
    }

    /** Vida logica maxima actual (para porcentajes). */
    double vidaFinal() {
        return cuerpo == null ? 0 : hc.amenazas().vidaLogicaMaxima(cuerpo);
    }

    /** Lo que multiplica el dano que recibe: x0,5 con planideras, +25 % aturdida o tocando. */
    double factorRecibido() {
        double f = 1;
        if (!planideras.isEmpty()) f *= a.planReduccion;
        if (ticks < aturdidaHasta) f *= 1 + a.planAturdidaExtra;
        if (actual == Habilidad.CAMPANADA) f *= 1 + a.campExtra;
        return f;
    }

    /** Un jugador le ha pegado. En la de prueba, ese pasa a ser su objetivo. */
    void golpeadaPor(Player j) {
        if (prueba && (objetivoPrueba == null || !objetivoPrueba.equals(j.getUniqueId()))) {
            objetivoPrueba = j.getUniqueId();
            velocidad();
        }
    }

    /** Ella ha golpeado a alguien: el reloj de "sin golpear" del Paso Umbral y del atasco. */
    void haGolpeado() {
        ultimoGolpe = ticks;
    }

    // ============================================================ marcados

    /** Otro que llega a 600 cerca: marcado extra, M+1, vida maxima y actual en proporcion. */
    void agregarMarcado(Player p) {
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
    void quitarMarcado(UUID id, String motivo) {
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
            if (ticks == 10 || ticks == 20) Compat.sound(w, sitioFinal, "block.bell.use", 4f, 0.5f);
            return;
        }
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
            boolean exento = !hc.cuenta(m) || (a.permisoExento != null && !a.permisoExento.isEmpty()
                    && m.hasPermission(a.permisoExento));
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
    void revisarFase() {
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
            if (m != null) hc.cordura().destello(m, Component.text(texto, NamedTextColor.GRAY), 3);
        }
        if (f == 2) {
            lista.put(Habilidad.CORTEJO, ticks);
            lista.put(Habilidad.TIRON, ticks);
        } else if (f == 3) {
            lista.put(Habilidad.CAMPANADA, ticks + 60);
        }
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
        dir = direccionA(obj, origen).normalize();
        Compat.setAttribute(cuerpo, "movement_speed", 0);
        World w = cuerpo.getWorld();
        switch (h) {
            case SIEGA -> {
                habDura = a.siegaAviso;
                cuerpo.swingMainHand();
                Compat.sound(w, origen, "block.respawn_anchor.charge", 1.5f, 0.6f);
            }
            case UMBRAL -> {
                habDura = a.umbralAviso;
                destino = detras(obj);
                obj.playSound(obj.getLocation(), "entity.enderman.teleport", SoundCategory.HOSTILE, 1f, 0.5f);
                hc.cordura().destello(obj, Component.text("Sientes frío en la nuca.", NamedTextColor.GRAY), 2);
            }
            case TIRON -> {
                habDura = a.tironAviso;
                victimaTiron = obj.getUniqueId();
                danoAlEmpezar = sumaDano();
                Compat.sound(w, origen, "entity.fishing_bobber.throw", 1.5f, 0.5f);
            }
            case CORTEJO -> {
                habDura = 30;
                cuerpo.swingMainHand();
                Compat.sound(w, origen, "entity.vex.charge", 1.5f, 0.6f);
                double base = Math.atan2(dir.getZ(), dir.getX());
                for (int i = 0; i < 3; i++) {
                    double ang = base + i * Math.PI * 2 / 3;
                    puntos.add(Fx.ground(origen.clone().add(Math.cos(ang) * 4, 0, Math.sin(ang) * 4), 4));
                }
            }
            case CAMPANADA -> habDura = a.campToques * a.campCada;
        }
        nombreBarra();
    }

    private void avanzar() {
        long t = ticks - habDesde;
        World w = cuerpo.getWorld();
        switch (actual) {
            case SIEGA -> {
                if (t % 4 == 0) pintarSiega(w);
                if (t >= habDura) {
                    soltarSiega(w);
                    acabar();
                }
            }
            case UMBRAL -> {
                Fx.telegraph(w, destino, 1.2, 0x8B1A1A);
                Compat.spawn(w, Compat.SCULK_SOUL, destino.clone().add(0, 0.3, 0), 2, 0.3, 0.1, 0.3, 0.01);
                if (t >= habDura) {
                    Compat.spawn(w, Compat.SOUL, cuerpo.getLocation().add(0, 1, 0), 20, 0.4, 0.8, 0.4, 0.02);
                    hc.amenazas().teleportar(cuerpo, destino);
                    Compat.spawn(w, Compat.SOUL, destino.clone().add(0, 1, 0), 20, 0.4, 0.8, 0.4, 0.02);
                    ultimoSalto = ticks;
                    acabar();
                }
            }
            case TIRON -> avanzarTiron(w, t);
            case CORTEJO -> {
                if (t == 2 || t == 6 || t == 10) Compat.sound(w, origen, "block.bell.use", 1.5f, 0.7f);
                for (Location p : puntos) Fx.telegraph(w, p, 0.8, 0xD8D2C4);
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
        if (cuerpo != null && cuerpo.isValid()) velocidad();
        nombreBarra();
    }

    // ------------------------------------------------------------------ Siega

    private void pintarSiega(World w) {
        Particle.DustOptions polvo = Compat.dust(0x8B1A1A, 1.4f);
        double spread = Math.toRadians(a.siegaAngulo);
        Fx.arc(origen, dir, a.siegaRadio / 2, spread, 10, l -> Compat.spawn(w, Compat.DUST,
                Fx.ground(l, 4).add(0, 0.12, 0), 1, 0, 0, 0, 0, polvo));
        Fx.arc(origen, dir, a.siegaRadio, spread, 18, l -> Compat.spawn(w, Compat.DUST,
                Fx.ground(l, 4).add(0, 0.12, 0), 1, 0, 0, 0, 0, polvo));
    }

    /** Dano verdadero a todo jugador dentro del cono (atraviesa paredes). El escudo de cara la anula. */
    private void soltarSiega(World w) {
        double spread = Math.toRadians(a.siegaAngulo);
        Fx.arc(origen, dir, a.siegaRadio * 0.7, spread, 6, l -> Compat.spawn(w, Compat.SWEEP_ATTACK,
                l.clone().add(0, 1, 0), 1));
        Compat.soundPlayers(w, origen, "entity.player.attack.sweep", 1.5f, 0.5f);
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
        Particle.DustOptions polvo = Compat.dust(0xD8D2C4, 1.0f);
        Fx.beam(cuerpo.getEyeLocation().subtract(0, 0.4, 0), v.getLocation().add(0, 1, 0), 0.4,
                l -> Compat.spawn(w, Compat.DUST, l, 1, 0, 0, 0, 0, polvo));
        if (sumaDano() - danoAlEmpezar >= a.tironRompe * vidaFinal()) {
            Component roto = Component.text("La cadena se rompe.", NamedTextColor.GRAY);
            for (Player o : Fx.viewersNear(cuerpo.getLocation(), 32)) hc.cordura().destello(o, roto, 2);
            Compat.sound(w, cuerpo.getLocation(), "block.chain.break", 1.5f, 0.6f);
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
            siegaAlAterrizar = true;
            tironSolto = ticks;
        }
        acabar();
    }

    // --------------------------------------------------------------- Cortejo

    /** base + M planideras (tope 6): mientras quede una, la PARCA recibe x0,5. */
    private void soltarCortejo() {
        int n = Math.min(a.planTope, a.planBase + extra);
        double vida = a.planVida * (1 + a.planVidaPorNivel * (nivel - 1));
        double dano = a.planDano * (1 + a.planDanoPorNivel * (nivel - 1));
        Player obj = objetivo();
        for (int i = 0; i < n && !puntos.isEmpty(); i++) {
            Location l = puntos.get(i % puntos.size()).clone().add(0, 1, 0);
            Vex vex = hc.amenazas().invocar(Vex.class, l, "planidera", nivel, Component.text("Plañidera", HUESO), e -> {
                if (presa != null) e.getPersistentDataContainer().set(Marcas.PRESA, PersistentDataType.STRING, presa.toString());
                Compat.setAttribute(e, "max_health", vida);
                e.setHealth(Math.max(1, vida));
                Compat.setAttribute(e, "attack_damage", dano);
                // Amenazas cancela el dano que no es de jugador, y la vex con vida limitada se
                // muere asi: la retira esta pelea a los vida-ticks.
                e.setLimitedLifetime(true);
                e.setLimitedLifetimeTicks(a.planVidaTicks + 40);
                e.setSummoner(cuerpo);
            });
            if (vex == null) continue;
            if (obj != null) vex.setTarget(obj);
            planideras.add(vex);
        }
        if (!planideras.isEmpty()) {
            planNacio = ticks;
            planActivas = true;
            planCaducaron = false;
        }
    }

    /**
     * Mantiene las planideras: hilo de almas a la PARCA, caducidad a los vida-ticks y, si
     * caen todas antes de 40 s, la PARCA queda aturdida 3 s y recibe +25 % (P-15).
     */
    private void planideras() {
        if (!planActivas) return;
        planideras.removeIf(v -> !v.isValid() || v.isDead());
        if (!planideras.isEmpty() && ticks - planNacio >= a.planVidaTicks) {
            for (Vex v : planideras) Fx.safeRemove(v);
            planideras.clear();
            planCaducaron = true;
        }
        if (planideras.isEmpty()) {
            planActivas = false;
            if (!planCaducaron && ticks - planNacio < 800) aturdir();
            return;
        }
        World w = cuerpo.getWorld();
        if (ticks % 10 == 0) {
            for (Vex v : planideras) {
                if (v.getWorld() != w) continue;
                Fx.beam(v.getLocation().add(0, 0.5, 0), cuerpo.getLocation().add(0, 1.4, 0), 0.5,
                        l -> Compat.spawn(w, Compat.SOUL, l, 1, 0, 0, 0, 0));
            }
        }
        if (ticks % 20 == 0) {
            Player obj = objetivo();
            if (obj != null) for (Vex v : planideras) v.setTarget(obj);
        }
    }

    private void aturdir() {
        aturdidaHasta = ticks + a.planAturdir * 20L;
        if (actual != null) acabar();
        cuerpo.setAI(false);
        Component texto = Component.text("La Parca se tambalea.", NamedTextColor.GRAY);
        for (Player o : Fx.viewersNear(cuerpo.getLocation(), 32)) hc.cordura().destello(o, texto, 2);
        Compat.sound(cuerpo.getWorld(), cuerpo.getLocation(), "entity.wither_skeleton.hurt", 1.5f, 0.5f);
        hc.plugin().bitacora().anotar("parca", "aturdida", presaNombre);
    }

    // ------------------------------------------------------------- Campanada

    /** Cinco toques cada 24 ticks; en el 3.o, aviso a quien este a <= 12; en el 5.o, el Juicio. */
    private void avanzarCampanada(World w, long t) {
        if (t % 4 == 0) Fx.telegraph(w, origen, a.campRadio, 0x8B1A1A);
        while (toques < a.campToques && t >= (long) (toques + 1) * a.campCada) {
            toques++;
            double avance = a.campToques <= 1 ? 1 : (toques - 1) / (double) (a.campToques - 1);
            Compat.sound(w, origen, "block.bell.use", 2.0f, (float) (0.8 - 0.3 * avance));
            Fx.shockwave(w, origen, a.campRadio, Compat.SOUL, 6);
            nombreBarra();
            if (toques == 3) {
                Title titulo = Title.title(Component.text("Aléjate", ROJO),
                        Component.text("Campanada 3/" + a.campToques, NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1600), Duration.ofMillis(400)));
                for (Player o : Fx.viewersNear(origen, 12)) o.showTitle(titulo);
            }
            if (toques >= a.campToques) {
                juicio();
                acabar();
                return;
            }
        }
    }

    /** El Juicio: dano verdadero (tope 90 %, ley 5) y -25 de cordura a todo jugador a <= radio. */
    private void juicio() {
        World w = cuerpo.getWorld();
        Compat.spawn(w, Compat.SOUL, origen.clone().add(0, 1, 0), 80, a.campRadio / 2, 1, a.campRadio / 2, 0.05);
        double r2 = a.campRadio * a.campRadio;
        for (Player v : Fx.playersNear(origen, a.campRadio + 1)) {
            double dx = v.getLocation().getX() - origen.getX(), dz = v.getLocation().getZ() - origen.getZ();
            if (dx * dx + dz * dz > r2) continue;
            double vidaMax = Compat.getAttribute(v, "max_health", 20);
            DanoVerdadero.aplicar(v, Parca.juicioFraccion(a, factorR) * vidaMax, a.campTope, cuerpo, "Juicio");
            if (hc.esHardcore(v)) hc.cordura().sumar(v, -a.campCordura);
        }
    }

    // =========================================================== barra y aura

    private Component tituloBarra() {
        String t;
        if (actual != null && actual != Habilidad.UMBRAL) {
            t = switch (actual) {
                case SIEGA -> "Parca · Siega";
                case TIRON -> "Parca · Tirón";
                case CORTEJO -> "Parca · Cortejo";
                case CAMPANADA -> "Parca · Campanada " + Math.max(1, toques) + "/" + a.campToques;
                default -> "Parca";
            };
        } else if (furia) {
            t = "Parca · Furia";
        } else {
            t = "Parca · Nv. " + nivel;
        }
        return Component.text(t, ROJO);
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
            Compat.spawn(w, Compat.SCULK_SOUL, cuerpo.getEyeLocation().add(cuerpo.getLocation().getDirection().multiply(0.6))
                    .subtract(0, 0.6, 0), 1, 0.05, 0.05, 0.05, 0.0);
        }
        if (estado == Estado.PELEA && ticks % 120 == 0) Compat.sound(w, l, "entity.wither_skeleton.ambient", 1f, 0.5f);
        if (ticks % 200 == 0) Compat.sound(w, l, "block.bell.use", 0.4f, 0.5f);
    }

    // =================================================================== fin

    /** La presa se desconecta sin etiqueta: se queda quieta espera-desconexion-segundos. */
    void esperar() {
        if (estado == Estado.FIN || estado == Estado.COSECHA) return;
        if (actual != null) acabar();
        estado = Estado.ESPERA;
        esperaHasta = System.currentTimeMillis() + a.esperaDesconexion * 1000L;
        cuerpo.setAI(false);
        cuerpo.setInvulnerable(false);
        for (Vex v : planideras) Fx.safeRemove(v);
        planideras.clear();
        planActivas = false;
        hc.plugin().bitacora().anotar("parca", "espera", presaNombre, a.esperaDesconexion + " s");
    }

    /** Vuelve la presa mientras ella esperaba: sigue la pelea. */
    void reanudar() {
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
    void cosecha(boolean porElla) {
        if (estado == Estado.FIN || estado == Estado.COSECHA) return;
        if (actual != null) acabar();
        estado = Estado.COSECHA;
        cosechaDesde = ticks;
        cuerpo.setAI(false);
        cuerpo.setInvulnerable(true);
        for (Vex v : planideras) Fx.safeRemove(v);
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
    void alMorir() {
        if (pagada || estado == Estado.FIN) return;
        pagada = true;
        Map<UUID, Double> dano = hc.amenazas().danoLogico(cuerpo);
        double vida = vidaFinal();
        long segundos = (System.currentTimeMillis() - nacio) / 1000;
        World w = cuerpo.getWorld();
        Location l = cuerpo.getLocation();
        Compat.sound(w, l, "block.bell.use", 4f, 0.4f);
        Compat.sound(w, l, "entity.wither_skeleton.death", 1.5f, 0.5f);
        Fx.helix(l, 1.0, 3.0, 40, 3, p -> Compat.spawn(w, Compat.SOUL, p, 1, 0, 0, 0, 0.01));
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
    void irse(String motivo, Component aviso) {
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

    /** Retira todo lo suyo (idempotente): barra, planideras, cuerpo y la pelea de la tarea de 2 ticks. */
    void limpiar() {
        quitarBarra();
        for (Vex v : planideras) Fx.safeRemove(v);
        planideras.clear();
        Fx.safeRemove(cuerpo);
        rastro.clear();
        if (hc.amenazas() != null) hc.amenazas().quitarPelea(this);
        estado = Estado.FIN;
    }
}
