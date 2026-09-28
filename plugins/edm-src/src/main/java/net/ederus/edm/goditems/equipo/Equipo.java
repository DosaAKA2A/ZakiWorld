package net.ederus.edm.goditems.equipo;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import net.ederus.edm.goditems.Activador;
import net.ederus.edm.goditems.Ctx;
import net.ederus.edm.goditems.GodItem;
import net.ederus.edm.goditems.GodItemsPlugin;
import net.ederus.edm.goditems.api.EquipoCambiadoEvent;
import net.ederus.edm.goditems.equipo.Calculo.AtributoTotal;
import net.ederus.edm.goditems.equipo.Calculo.Resultado;
import net.ederus.edm.goditems.equipo.Definicion.Config;
import net.ederus.edm.goditems.equipo.Definicion.Conjunto;
import net.ederus.edm.goditems.equipo.Definicion.Escalon;
import net.ederus.edm.goditems.equipo.Definicion.Pocion;

/**
 * Los efectos de equipo de GodItems: lo que hace cada pieza de MMOItems y cada
 * set mientras se lleva, declarado en `plugins/EDM/goditems/equipo/*.yml`.
 *
 * La decision de Dosa (2026-09-28): las armaduras se crean en MMOItems y GodItems
 * les da los efectos por pieza y por set. Los plugins de modo (Calamity,
 * PremioPescao) ya no tienen su propio fichero de equipo: leen sus claves
 * (`calamity.*`, `pesca.*`) de aqui, por EquipoApi.
 *
 * Dos clases de efecto:
 *   - GENERICOS, que aplica GodItems solo: pociones permanentes (renovadas cada
 *     segundo), modificadores de atributo (transitorios: no se guardan en el
 *     jugador, asi que un reinicio a mitad nunca deja uno colgado) y acciones
 *     del motor al ganar o perder un escalon de set;
 *   - CLAVES DE DOMINIO: numeros que GodItems suma y topa y que otro plugin lee.
 *
 * La cuenta de un jugador se guarda por tick (una muerte o un golpe preguntan
 * varias veces en el mismo) y se tira en cuanto cambia algo de su equipo; un
 * tick despues se reaplica lo generico. Una tarea cada segundo hace de red de
 * seguridad para lo que no avise con un evento (un /give, un cofre, un /mi
 * que regenera el item).
 */
public final class Equipo implements Listener {

    public static final String CARPETA = "equipo";
    /** Cuanto dura cada renovacion de una pocion. Por encima de 10 s: la vision nocturna no parpadea. */
    private static final int DURACION_POCION = 20 * 16;
    private static final int RENOVAR_POR_DEBAJO = 20 * 12;
    private static final String PREFIJO_MODIFICADOR = "equipo_";

    private record Guardado(int tick, Resultado r) { }

    /** Lo que se le ha aplicado de verdad a un jugador, para poder quitarlo. */
    private static final class Aplicado {
        String firma = "";
        Map<String, Double> claves = Map.of();
        final Map<PotionEffectType, Integer> pociones = new HashMap<>();
        final Map<NamespacedKey, org.bukkit.attribute.Attribute> modificadores = new HashMap<>();
        final Set<String> escalones = new HashSet<>();
    }

    private final GodItemsPlugin modulo;
    private volatile Config config = Config.VACIA;
    private volatile Redaccion redaccion = Redaccion.DE_SERIE;
    private final Map<UUID, Guardado> guardado = new HashMap<>();
    private final Map<UUID, Aplicado> aplicado = new HashMap<>();
    private final Set<UUID> pendientes = new HashSet<>();
    /** Un GodItem de mentira por set, para que las acciones de sus escalones tengan contexto. */
    private final Map<String, GodItem> sinteticos = new HashMap<>();
    private BukkitTask tarea;

    public Equipo(GodItemsPlugin modulo) {
        this.modulo = modulo;
    }

    /* =========================================================== arranque */

    public void arrancar() {
        cargar();
        this.modulo.core().getServer().getPluginManager().registerEvents(this, this.modulo);
        this.tarea = this.modulo.core().getServer().getScheduler().runTaskTimer(this.modulo.core(),
                this::repasarTodos, 40L, 20L);
    }

    public void parar() {
        HandlerList.unregisterAll(this);
        if (this.tarea != null) this.tarea.cancel();
        this.tarea = null;
        for (Player p : Bukkit.getOnlinePlayers()) quitarTodo(p);
        this.aplicado.clear();
        this.guardado.clear();
        this.pendientes.clear();
    }

    /**
     * Lee la carpeta (la primera vez escribe las de serie). Devuelve el resumen
     * para /gi reload. Nunca lanza.
     */
    public String cargar() {
        File carpeta = new File(this.modulo.getDataFolder(), CARPETA);
        if (!carpeta.isDirectory()) {
            carpeta.mkdirs();
            /* Solo la primera vez, como los items de ejemplo: si se escribieran
             * en cada arranque, se perderian los cambios hechos en el panel. */
            for (String f : List.of("pesca.yml", "calamity.yml")) {
                try {
                    this.modulo.saveResource(CARPETA + "/" + f, false);
                } catch (IllegalArgumentException sinRecurso) {
                    /* Un jar sin ese fichero: se sigue. */
                }
            }
        }
        this.redaccion = new Redaccion(this.modulo.getConfig().getConfigurationSection("equipo"));
        LectorEquipo lector = new LectorEquipo(this.modulo.cargador());
        Config c = lector.leerCarpeta(carpeta);
        List<String> avisos = new ArrayList<>(c.avisos());
        /* Lo que solo se sabe con MMOItems delante: ids que no existen (o que
         * han cambiado de tipo, que pasa al mover items entre ficheros). */
        if (this.modulo.puente() != null && this.modulo.puente().hay()) {
            for (String id : c.conocidas()) {
                int p = id.indexOf('.');
                if (p > 0 && !this.modulo.puente().existe(id.substring(0, p), id.substring(p + 1))) {
                    avisos.add("'" + id + "' no existe en MMOItems (¿ha cambiado de tipo?)");
                }
            }
        }
        this.config = new Config(c.claves(), c.grupos(), c.piezas(), c.sets(), List.copyOf(avisos));
        this.sinteticos.clear();
        for (Conjunto s : c.sets()) {
            this.sinteticos.put(s.id(), new GodItem(null, "equipo:" + s.id(), null, null, null, -1, -1,
                    false, false, false, List.of(), List.of(), Map.of(), Map.of()));
        }
        for (String a : avisos) this.modulo.getLogger().warning("[GodItems] equipo/: " + a);
        this.guardado.clear();
        /* Lo aplicado se rehace entero con la configuracion nueva. */
        for (Player p : Bukkit.getOnlinePlayers()) programar(p);
        return resumen();
    }

    public String resumen() {
        Config c = this.config;
        String s = c.piezas().size() + (c.piezas().size() == 1 ? " pieza" : " piezas") + ", "
                + c.sets().size() + (c.sets().size() == 1 ? " set" : " sets") + " y "
                + c.claves().size() + (c.claves().size() == 1 ? " clave" : " claves");
        return c.avisos().isEmpty() ? s : s + ", " + c.avisos().size() + " aviso(s) en consola";
    }

    public Config config() {
        return this.config;
    }

    public Redaccion redaccion() {
        return this.redaccion;
    }

    /* ============================================================= cuenta */

    /** La cuenta de un jugador ahora mismo (guardada por tick). Hilo principal. */
    public Resultado resultado(Player p) {
        Config c = this.config;
        if (p == null || c.vacia()) return Resultado.VACIO;
        int tick = Bukkit.getCurrentTick();
        Guardado g = this.guardado.get(p.getUniqueId());
        if (g != null && g.tick() == tick) return g.r();
        Resultado r = calcular(c, Calculo.equipoDe(p));
        this.guardado.put(p.getUniqueId(), new Guardado(tick, r));
        return r;
    }

    /** La cuenta para un equipo cualquiera (autotest, simulaciones de otros plugins). */
    public Resultado calcular(Config c, Map<EquipmentSlot, ItemStack> equipo) {
        return Calculo.calcular(c, equipo, this.modulo.identidad()::enlaceDe,
                it -> this.modulo.puente() == null ? null : this.modulo.puente().setDe(it));
    }

    /** Un item nuevo de MMOItems, o null. Pasa por su ItemBuildEvent, asi que sale con nuestro lore. */
    public ItemStack crear(String tipo, String id) {
        return this.modulo.puente() == null || !this.modulo.puente().hay() ? null
                : this.modulo.puente().construir(tipo, id);
    }

    /** El TIPO.ID de MMOItems de un item, o null. */
    public String identidad(ItemStack item) {
        return this.modulo.identidad().enlaceDe(item);
    }

    public GodItemsPlugin modulo() {
        return this.modulo;
    }

    /** Cuantas piezas tiene un set de MMOItems (para el "(2/5)" del lore). */
    public int tamanoMmo(String set) {
        return this.modulo.conjuntos() == null ? 0 : this.modulo.conjuntos().tamanoDe(set);
    }

    /** Las lineas de lore que le tocan a un item de MMOItems ya construido. */
    public List<String> loreDe(ItemStack item) {
        String id = this.modulo.identidad().enlaceDe(item);
        if (id == null) return List.of();
        String set = this.modulo.puente() == null ? null : this.modulo.puente().setDe(item);
        return this.redaccion.lore(this.config, id, set, this::tamanoMmo);
    }

    /* ==================================================== aplicar genericos */

    private void programar(Player p) {
        if (p == null || !this.pendientes.add(p.getUniqueId())) return;
        this.modulo.core().getServer().getScheduler().runTask(this.modulo.core(), () -> {
            this.pendientes.remove(p.getUniqueId());
            if (p.isOnline()) repasar(p, true);
        });
    }

    private void repasarTodos() {
        Config c = this.config;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (c.vacia() && !this.aplicado.containsKey(p.getUniqueId())) continue;
            repasar(p, false);
        }
    }

    /**
     * Reaplica lo generico si ha cambiado lo que cuenta, renueva las pociones y
     * avisa del cambio. forzar = rehacerlo aunque la firma sea la misma (recarga).
     */
    public void repasar(Player p, boolean forzar) {
        this.guardado.remove(p.getUniqueId());
        Resultado r = resultado(p);
        Aplicado a = this.aplicado.computeIfAbsent(p.getUniqueId(), k -> new Aplicado());
        boolean cambia = !r.firma().equals(a.firma);
        if (cambia || forzar) {
            atributos(p, r, a);
            escalones(p, r, a);
            Map<String, Double> antes = a.claves;
            a.claves = r.topado();
            a.firma = r.firma();
            if (cambia) Bukkit.getPluginManager().callEvent(new EquipoCambiadoEvent(p, antes, r.topado(), r.contadas()));
        }
        pociones(p, r, a);
        if (r.firma().isEmpty() && a.pociones.isEmpty() && a.modificadores.isEmpty()) {
            this.aplicado.remove(p.getUniqueId());
        }
    }

    private void atributos(Player p, Resultado r, Aplicado a) {
        for (Map.Entry<NamespacedKey, org.bukkit.attribute.Attribute> e : a.modificadores.entrySet()) {
            AttributeInstance inst = p.getAttribute(e.getValue());
            if (inst != null) inst.removeModifier(e.getKey());
        }
        a.modificadores.clear();
        for (AtributoTotal t : r.atributos()) {
            if (t.valor() == 0) continue;
            AttributeInstance inst = p.getAttribute(t.atributo());
            if (inst == null) continue;
            NamespacedKey k = new NamespacedKey(this.modulo, PREFIJO_MODIFICADOR + t.clave().replace('.', '_')
                    + "_" + t.operacion().name().toLowerCase(java.util.Locale.ROOT));
            inst.removeModifier(k);
            inst.addTransientModifier(new AttributeModifier(k, t.valor(), t.operacion(), EquipmentSlotGroup.ANY));
            a.modificadores.put(k, t.atributo());
        }
        /* Si baja la vida maxima, que la actual no se quede por encima. */
        AttributeInstance vida = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (vida != null && p.getHealth() > vida.getValue()) p.setHealth(Math.max(0.5, vida.getValue()));
    }

    private void pociones(Player p, Resultado r, Aplicado a) {
        Map<PotionEffectType, Integer> ahora = new HashMap<>();
        for (Pocion x : r.pociones()) ahora.put(x.tipo(), x.nivel());
        /* Las que ya no tocan: se quitan solo si son las nuestras (mismo nivel y
         * duracion corta). Una pocion bebida o de otro plugin no se toca. */
        for (Map.Entry<PotionEffectType, Integer> e : new ArrayList<>(a.pociones.entrySet())) {
            Integer nuevo = ahora.get(e.getKey());
            if (nuevo != null && nuevo.equals(e.getValue())) continue;
            PotionEffect actual = p.getPotionEffect(e.getKey());
            if (actual != null && actual.getAmplifier() == e.getValue() && !actual.isInfinite()
                    && actual.getDuration() <= DURACION_POCION) {
                p.removePotionEffect(e.getKey());
            }
            a.pociones.remove(e.getKey());
        }
        for (Map.Entry<PotionEffectType, Integer> e : ahora.entrySet()) {
            PotionEffect actual = p.getPotionEffect(e.getKey());
            boolean poner = actual == null
                    || (actual.getAmplifier() < e.getValue() && !actual.isInfinite())
                    || (actual.getAmplifier() == e.getValue() && !actual.isInfinite()
                        && actual.getDuration() < RENOVAR_POR_DEBAJO);
            if (poner) p.addPotionEffect(new PotionEffect(e.getKey(), DURACION_POCION, e.getValue(), true, false, true));
            a.pociones.put(e.getKey(), e.getValue());
        }
    }

    /** Las acciones de los escalones que se ganan y se pierden. */
    private void escalones(Player p, Resultado r, Aplicado a) {
        Set<String> ahora = new HashSet<>();
        Map<String, Escalon> porClave = new LinkedHashMap<>();
        for (Map.Entry<String, List<Escalon>> e : r.activos().entrySet()) {
            for (Escalon x : e.getValue()) {
                String k = e.getKey() + ":" + x.necesita();
                ahora.add(k);
                porClave.put(k, x);
            }
        }
        for (String k : new ArrayList<>(a.escalones)) {
            if (ahora.contains(k)) continue;
            a.escalones.remove(k);
            Escalon viejo = escalonDe(k);
            if (viejo != null) lanzar(p, k, viejo.alPerder(), Activador.SET_ROTO);
        }
        for (String k : ahora) {
            if (!a.escalones.add(k)) continue;
            lanzar(p, k, porClave.get(k).alActivar(), Activador.SET_COMPLETO);
        }
    }

    private Escalon escalonDe(String k) {
        int dos = k.lastIndexOf(':');
        Conjunto s = this.config.set(k.substring(0, dos));
        if (s == null) return null;
        int n = Integer.parseInt(k.substring(dos + 1));
        for (Escalon e : s.escalones()) if (e.necesita() == n) return e;
        return null;
    }

    private void lanzar(Player p, String k, List<net.ederus.edm.goditems.Paso> pasos, Activador act) {
        if (pasos == null || pasos.isEmpty()) return;
        GodItem def = this.sinteticos.get(k.substring(0, k.lastIndexOf(':')));
        if (def == null) return;
        this.modulo.motor().lanzar(new Ctx(this.modulo, p, def, act, null, null, null), pasos);
    }

    private void quitarTodo(Player p) {
        Aplicado a = this.aplicado.get(p.getUniqueId());
        if (a == null) return;
        for (Map.Entry<NamespacedKey, org.bukkit.attribute.Attribute> e : a.modificadores.entrySet()) {
            AttributeInstance inst = p.getAttribute(e.getValue());
            if (inst != null) inst.removeModifier(e.getKey());
        }
        for (Map.Entry<PotionEffectType, Integer> e : a.pociones.entrySet()) {
            PotionEffect actual = p.getPotionEffect(e.getKey());
            if (actual != null && actual.getAmplifier() == e.getValue() && !actual.isInfinite()
                    && actual.getDuration() <= DURACION_POCION) {
                p.removePotionEffect(e.getKey());
            }
        }
    }

    /* ========================================================== escuchas */

    /*
     * Los mismos que usaba el Equipo de Calamity: cualquier cosa que pueda
     * cambiar un hueco tira la cuenta guardada y pide un repaso al tick
     * siguiente (en el momento del evento el hueco aun no ha cambiado).
     */

    private void tocar(Entity e) {
        if (!(e instanceof Player p)) return;
        this.guardado.remove(p.getUniqueId());
        if (!this.config.vacia() || this.aplicado.containsKey(p.getUniqueId())) programar(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alCambiarEquipo(EntityEquipmentChangedEvent e) { tocar(e.getEntity()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alCambiarMano(PlayerItemHeldEvent e) { tocar(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alCruzarManos(PlayerSwapHandItemsEvent e) { tocar(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alClic(InventoryClickEvent e) { tocar(e.getWhoClicked()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alArrastrar(InventoryDragEvent e) { tocar(e.getWhoClicked()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alCerrar(InventoryCloseEvent e) { tocar(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alSoltar(PlayerDropItemEvent e) { tocar(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alRecoger(EntityPickupItemEvent e) { tocar(e.getEntity()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alRomper(PlayerItemBreakEvent e) { tocar(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alMorir(PlayerDeathEvent e) { tocar(e.getEntity()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alReaparecer(PlayerRespawnEvent e) { tocar(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alCambiarMundo(PlayerChangedWorldEvent e) { tocar(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alEntrar(PlayerJoinEvent e) { tocar(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alSalir(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        /* Los modificadores son transitorios y no se guardan; las pociones
         * caducan solas en unos segundos. Solo se olvida. */
        this.guardado.remove(u);
        this.aplicado.remove(u);
        this.pendientes.remove(u);
    }
}
