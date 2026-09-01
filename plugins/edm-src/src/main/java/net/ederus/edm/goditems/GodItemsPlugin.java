package net.ederus.edm.goditems;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;
import net.ederus.edm.comun.Estilo;
import net.milkbowl.vault.economy.Economy;

/**
 * GodItems: items con activadores, acciones y condiciones, al estilo de
 * ExecutableItems, pero SIN ser un segundo sistema de items.
 *
 * Esa es la decision de la que cuelga todo lo demas. ExecutableItems choca con
 * MMOItems porque los dos quieren SER el item: los dos escriben NBT propio,
 * lore, durabilidad y calculo de daño. GodItems es una CAPA DE COMPORTAMIENTO:
 *
 *   - a los items de MMOItems no les escribe nada (modo enlazado), asi que su
 *     Item Revision System puede regenerarlos las veces que quiera;
 *   - solo fabrica items propios para los trastos sueltos que no llevan stats.
 *
 * Y va sin resource pack a proposito: los sonidos "custom" se fabrican apilando
 * sonidos del juego con SECUENCIA, que es como suena Alba.
 */
public final class GodItemsPlugin extends Module {

    private final Registro registro = new Registro();
    private Identidad identidad;
    private Variables variables;
    private Usos usos;
    private Cooldowns cooldowns;
    private Vuelo vuelo;
    private Motor motor;
    private Regiones regiones;
    private Cargador cargador;
    private Economy economia;

    private NamespacedKey claveDueno;
    private BukkitTask tareaTicks;
    private BukkitTask tareaBarra;
    private int contador;

    private boolean detalle;
    private int ticksDeRevision = 10;
    private Map<String, String> mensajes = new HashMap<>();

    /** Lo que se le guarda a alguien que murio con un item de conservar-al-morir. */
    private final Map<UUID, List<ItemStack>> guardado = new HashMap<>();
    private final java.util.Set<String> avisados = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public GodItemsPlugin(EDMPlugin core) {
        super(core, "goditems", "GodItems");
    }

    /* ============================================================ arranque */

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        leerAjustes();

        this.claveDueno = new NamespacedKey(this, "dueno");
        this.identidad = new Identidad(this);
        this.variables = new Variables(this);
        this.usos = new Usos(this);
        this.cooldowns = new Cooldowns();
        this.vuelo = new Vuelo();
        this.motor = new Motor(this);
        this.regiones = new Regiones(this);
        this.cargador = new Cargador(this);

        engancharEconomia();
        File items = new File(getDataFolder(), "items");
        if (!items.isDirectory()) {
            items.mkdirs();
            /* El item de ejemplo se copia SOLO la primera vez. Si se
             * sobrescribiera en cada arranque, cualquiera que lo use de base
             * perderia sus cambios en el siguiente reinicio. */
            saveResource("items/cetro_del_alba.yml", false);
        }
        int n = this.cargador.cargarCarpeta(items, this.registro);

        core.getServer().getPluginManager().registerEvents(new Escuchas(this), this);
        ComandoGi comando = new ComandoGi(this);
        var cmd = core.getCommand("gi");
        if (cmd != null) {
            cmd.setExecutor(comando);
            cmd.setTabCompleter(comando);
        } else {
            getLogger().warning("[GodItems] El comando /gi no esta en el plugin.yml de EDM.");
        }

        arrancarTareas();

        getLogger().info("GodItems activo | " + n + " item" + (n == 1 ? "" : "s")
                + " | " + Acciones.nombres().size() + " acciones, "
                + Condiciones.nombres().size() + " condiciones, "
                + Activador.values().length + " activadores"
                + (this.cargador.avisos().isEmpty() ? "" : " | " + this.cargador.avisos().size()
                        + " avisos, mira arriba"));
    }

    private void arrancarTareas() {
        pararTareas();
        if (this.registro.hayTicks()) {
            this.tareaTicks = core.getServer().getScheduler().runTaskTimer(core,
                    this::pasarTicks, this.ticksDeRevision, this.ticksDeRevision);
        }
        this.tareaBarra = core.getServer().getScheduler().runTaskTimer(core,
                () -> this.cooldowns.repasar(this), 5L, 5L);
    }

    private void pararTareas() {
        if (this.tareaTicks != null) this.tareaTicks.cancel();
        if (this.tareaBarra != null) this.tareaBarra.cancel();
        this.tareaTicks = null;
        this.tareaBarra = null;
    }

    @Override
    public void onDisable() {
        pararTareas();
        if (this.vuelo != null) this.vuelo.devolverTodo(this);
        if (this.cooldowns != null) this.cooldowns.limpiar();
        this.guardado.clear();
    }

    @Override
    public String recargar() {
        reloadConfig();
        leerAjustes();
        int n = this.cargador.cargarCarpeta(new File(getDataFolder(), "items"), this.registro);
        arrancarTareas();
        return n + " items" + (this.cargador.avisos().isEmpty()
                ? "" : " (" + this.cargador.avisos().size() + " avisos en la consola)");
    }

    private void leerAjustes() {
        this.detalle = getConfig().getBoolean("detalle-en-el-log", false);
        this.ticksDeRevision = Math.max(1, getConfig().getInt("ticks-de-revision", 10));
        this.mensajes = new HashMap<>();
        var sec = getConfig().getConfigurationSection("mensajes");
        if (sec != null) {
            for (String k : sec.getKeys(false)) this.mensajes.put(k, sec.getString(k, ""));
        }
    }

    private void engancharEconomia() {
        try {
            var rsp = core.getServer().getServicesManager().getRegistration(Economy.class);
            this.economia = rsp == null ? null : rsp.getProvider();
        } catch (Throwable t) {
            this.economia = null;
        }
    }

    /* ============================================================== acceso */

    public Registro registro() { return this.registro; }
    public Identidad identidad() { return this.identidad; }
    public Variables variables() { return this.variables; }
    public Usos usos() { return this.usos; }
    public Cooldowns cooldowns() { return this.cooldowns; }
    public Vuelo vuelo() { return this.vuelo; }
    public Regiones regiones() { return this.regiones; }
    public Cargador cargador() { return this.cargador; }
    public Economy economia() { return this.economia; }
    public boolean detalle() { return this.detalle; }

    public String mensaje(String clave, String pordefecto) {
        String v = this.mensajes.get(clave);
        return v == null ? pordefecto : v;
    }

    /** Un aviso que solo tiene sentido dar una vez por arranque. */
    public void avisoUnaVez(String clave, String texto) {
        if (this.avisados.add(clave)) getLogger().warning("[GodItems] " + texto);
    }

    /* ============================================================ fabricar */

    /** Crea el item de un GodItem nativo, ya marcado. Null si es enlazado. */
    public ItemStack fabricar(GodItem def, int cantidad) {
        if (def.enlazado()) {
            avisoUnaVez("fab." + def.id(), def.id() + " es ENLAZADO: lo fabrica MMOItems."
                    + " Dalo con su comando (/mi give " + def.enlaceTipo() + " " + def.enlaceId()
                    + "), no con /gi give.");
            return null;
        }
        ItemStack item = def.apariencia().fabricar(this);
        item.setAmount(Math.max(1, Math.min(item.getType().getMaxStackSize(), cantidad)));
        return this.identidad.marcar(item, def);
    }

    /** Marca a quien se le dio, para `solo-dueno`. */
    public void ponerDueno(ItemStack item, Player j) {
        if (item == null || j == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(this.claveDueno, PersistentDataType.STRING,
                j.getUniqueId().toString());
        item.setItemMeta(meta);
    }

    private boolean esSuyo(ItemStack item, Player j) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return true;
        String dueno = meta.getPersistentDataContainer().get(this.claveDueno, PersistentDataType.STRING);
        return dueno == null || dueno.equals(j.getUniqueId().toString());
    }

    /* ============================================================ disparar */

    /**
     * El unico camino por el que se ejecuta un GodItem.
     *
     * Devuelve true si el comportamiento llego a lanzarse. Ese true es lo que
     * mira `exclusivo` para decidir si calla a la habilidad del set: si el item
     * no hizo nada (estaba en cooldown, o no cumplia), NO hay que callar nada,
     * o el jugador se queda sin las dos cosas.
     */
    public boolean disparar(Player j, ItemStack item, GodItem def, Activador act, Event evento,
                            EquipmentSlot mano, Entity objetivo, Location lugar) {
        if (j == null || def == null) return false;
        GodItem.Bloque bloque = def.bloque(act);
        if (bloque == null) return false;
        if (!def.valeEn(j.getWorld().getName())) return false;

        if (def.soloDueno() && !esSuyo(item, j)) {
            avisar(j, "no-es-tuyo", "&cEste objeto no responde en tus manos.");
            return false;
        }

        Ctx ctx = new Ctx(this, j, def, act, item, mano, evento)
                .objetivo(objetivo).lugar(lugar != null ? lugar : j.getLocation());

        int quedan = this.cooldowns.quedan(j, def.id(), act);
        if (quedan > 0) {
            /* En los activadores de tick no se avisa: seria un mensaje cada
             * pocos ticks. El cooldown ahi solo sirve para espaciar. */
            if (!act.esTick()) {
                String texto = bloque.mensajeCooldown() != null ? bloque.mensajeCooldown()
                        : mensaje("cooldown", "&7Aun no. Te quedan &f%tiempo%&7.");
                j.sendMessage(Estilo.legado(Textos.aplicar(ctx, texto)
                        .replace("%tiempo%", Numeros.reloj(quedan))));
            }
            return false;
        }

        if (bloque.probabilidad() < 100 && Math.random() * 100.0 >= bloque.probabilidad()) {
            return false;
        }

        if (!this.usos.hay(ctx, bloque.gastaUsos())) {
            if (!act.esTick()) {
                boolean porHoy = def.usosPorDia() >= 0 && this.usos.restantesHoy(ctx) <= 0;
                avisar(j, porHoy ? "sin-usos-hoy" : "sin-usos",
                        porHoy ? "&cPor hoy no te quedan usos de este objeto."
                               : "&cEste objeto ya no tiene usos.");
            }
            return false;
        }

        if (!Condicion.todas(ctx, bloque.condiciones())) return false;

        if (!this.usos.gastar(ctx, bloque.gastaUsos())) return false;
        if (bloque.cooldown() > 0) {
            this.cooldowns.poner(j, def.id(), act, bloque.cooldown(), bloque.cuentaAtras());
        }

        this.motor.lanzar(ctx, bloque.pasos());
        return true;
    }

    private void avisar(Player j, String clave, String pordefecto) {
        String texto = mensaje(clave, pordefecto);
        if (texto.isBlank()) return;
        j.sendMessage(Estilo.legado(texto));
    }

    /** Lo que llama `/gi trigger`. */
    public boolean disparadorManual(Player j, GodItem def) {
        ItemStack enMano = j.getInventory().getItemInMainHand();
        ItemStack item = def.id().equals(this.identidad.idDe(enMano)) ? enMano : buscarEnInventario(j, def);
        return disparar(j, item, def, Activador.DISPARADOR, null, EquipmentSlot.HAND, null, null);
    }

    private ItemStack buscarEnInventario(Player j, GodItem def) {
        for (ItemStack it : j.getInventory().getContents()) {
            if (it != null && def.id().equals(this.identidad.idDe(it))) return it;
        }
        return null;
    }

    /* =============================================================== ticks */

    /**
     * Los activadores de tick.
     *
     * Corre cada `ticks-de-revision` (10 por defecto) y no cada tick: mirar el
     * inventario entero de todos los conectados sesenta veces por segundo es
     * caro para lo poco que aporta. El `cada` de cada bloque se redondea a
     * multiplos de ese intervalo, y se dice en el config para que nadie se
     * pregunte por que su `cada: 3` va cada 10.
     */
    private void pasarTicks() {
        this.contador += this.ticksDeRevision;
        boolean hayInventario = false;
        for (GodItem def : this.registro.todos()) {
            if (def.bloque(Activador.EN_INVENTARIO) != null) { hayInventario = true; break; }
        }
        for (Player j : core.getServer().getOnlinePlayers()) {
            mirarTick(j, j.getInventory().getItemInMainHand(), Activador.EN_MANO, EquipmentSlot.HAND);
            mirarTick(j, j.getInventory().getHelmet(), Activador.PUESTO, EquipmentSlot.HEAD);
            mirarTick(j, j.getInventory().getChestplate(), Activador.PUESTO, EquipmentSlot.CHEST);
            mirarTick(j, j.getInventory().getLeggings(), Activador.PUESTO, EquipmentSlot.LEGS);
            mirarTick(j, j.getInventory().getBoots(), Activador.PUESTO, EquipmentSlot.FEET);
            if (!hayInventario) continue;
            for (ItemStack it : j.getInventory().getStorageContents()) {
                mirarTick(j, it, Activador.EN_INVENTARIO, null);
            }
        }
    }

    private void mirarTick(Player j, ItemStack item, Activador act, EquipmentSlot hueco) {
        if (item == null || item.getType().isAir()) return;
        GodItem def = this.identidad.definicionDe(item);
        if (def == null) return;
        GodItem.Bloque b = def.bloque(act);
        if (b == null) return;
        if (this.contador % Math.max(this.ticksDeRevision, b.cada()) >= this.ticksDeRevision) return;
        disparar(j, item, def, act, null, hueco, null, null);
    }

    /* ====================================================== conservar items */

    public void guardarParaRespawn(Player j, ItemStack item, PlayerDeathEvent e) {
        if (item == null) return;
        ItemStack copia = item.clone();
        e.getDrops().removeIf(d -> d != null && d.isSimilar(copia));
        this.guardado.computeIfAbsent(j.getUniqueId(), k -> new ArrayList<>()).add(copia);
    }

    public void devolverGuardados(Player j) {
        List<ItemStack> suyos = this.guardado.remove(j.getUniqueId());
        if (suyos == null || suyos.isEmpty()) return;
        core.getServer().getScheduler().runTaskLater(core, () -> {
            for (ItemStack it : suyos) {
                for (ItemStack sobra : j.getInventory().addItem(it).values()) {
                    j.getWorld().dropItemNaturally(j.getLocation(), sobra);
                }
            }
        }, 2L);
    }

    public void olvidar(Player j) {
        this.cooldowns.olvidar(j.getUniqueId());
        this.vuelo.olvidar(j.getUniqueId());
    }

    /* ============================================================ /edm goditems */

    @Override
    public boolean subcomando(CommandSender quien, String[] args) {
        if (args.length == 0) return false;
        if (args[0].equalsIgnoreCase("lista")) {
            quien.sendMessage("GodItems (" + this.registro.cuantos() + "): "
                    + String.join(", ", this.registro.ids()));
            return true;
        }
        if (args[0].equalsIgnoreCase("avisos")) {
            if (this.cargador.avisos().isEmpty()) {
                quien.sendMessage("Sin avisos: todos los YAML se leyeron enteros.");
            } else {
                for (String a : this.cargador.avisos()) quien.sendMessage(" - " + a);
            }
            return true;
        }
        return false;
    }
}
