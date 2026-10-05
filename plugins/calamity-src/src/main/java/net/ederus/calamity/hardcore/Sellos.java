package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.EnderChest;
import org.bukkit.block.Hopper;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * M4 · Sellos del Umbral (DIS M4): que lo de dentro solo salga por la puerta, y que lo que sale no se
 * guarde en ningun sitio fuera de Calamity.
 *
 * Dentro de Calamity, cada agujero que deja guardar algo a salvo a mitad de expedicion rompe la regla
 * de "si mueres, lo pierdes":
 * - portales: un portal del Nether construido dentro era una salida sin Cristal (X04);
 * - cofre ender: un banco a mitad de expedicion, aunque el comando ya estaba prohibido (X05);
 * - contenedores: Reliquias y Esencias solo viven en el inventario, en el suelo y en los
 *   cofres, barriles y vagonetas del mundo, que cualquiera puede abrir y saquear. Shulker,
 *   bolsa, burro, marco, soporte o jarron serian bolsillos seguros (X06);
 * - comandos: /pv, /ec, /ah, /trade... (los de DIS M4) como bancos o grifos a mitad de camino.
 *
 * Rama venta-oren (Dosa: "esos items no se deben poner en el /pv ni en ningun otro storage fuera de
 * Calamity, ni /ec ni nada; es mas, /ec y /pv no se deberian poder usar en Calamity"). Salir ya no
 * vende: las Reliquias y las Esencias salen contigo y se le venden a Oren. Fuera de Calamity solo
 * pueden estar en tu propio inventario:
 * - ventanas: no entran en ninguna que no sea la tuya (cofres, barriles, shulkers, cofre ender, /ec,
 *   /pv, /ah, /trade, mesas, aldeanos, menus de cualquier plugin), por clic, mayusculas, arrastre,
 *   tecla numerica o la F. Con la ventana de un plugin abierta (una subasta que elige lo que vendes con
 *   un clic abajo) tampoco se pueden tocar en tu inventario;
 * - tolvas y soltadores no las mueven ni las recogen del suelo;
 * - marcos, soportes, allays, animales de carga, jarrones y estantes, no;
 * - bolsas y shulkers: nunca, en ningun mundo (la shulker sale de Calamity con lo de dentro);
 * - comandos de mercado (/ah sell, /trade...) con una en la mano, y los que venden el inventario
 *   entero (/sell) si llevas alguna;
 * - el suelo: se pueden tirar (son tuyas), pero solo las recoge quien las tiro, y ningun mob;
 * - morir fuera de Calamity no las suelta: se quedan en tu inventario (si no, matarte seria pasarlas).
 *
 * Quien tiene ederus.mundos o calamity.bypass.storage (staff) no esta sellado. Cada sello tiene su
 * interruptor en hardcore.sellos. Los mobs que recogen dentro (X14) ya los cierra Hardcore.onRecoger.
 */
final class Sellos implements Listener {

    /** El permiso del staff para saltarse los sellos de almacen (contenedores y /ec, /pv dentro). */
    static final String BYPASS = "calamity.bypass.storage";

    /*
     * Raices que DIS M4 anade a comandos-prohibidos. Van tambien aqui, con su propia clave
     * (sellos.comandos), porque un config.yml ya instalado conserva su lista vieja de
     * comandos-prohibidos: Bukkit no mezcla listas, y en produccion y en el Test esas raices
     * no llegarian nunca. Con la clave ausente vale esta lista; "sellos.comandos: []" la apaga.
     */
    static final List<String> COMANDOS_M4 = List.of("pv", "playervault", "playervaults", "vault", "ah", "auction",
            "subasta", "trade", "kit", "kits", "gkit", "sell", "pay", "shop", "auctionhouse", "pa", "playerauction",
            "pauction");

    /**
     * Rama venta-oren: el cofre ender y los vaults, con todos sus alias (sellos.comandos-almacen). Los de
     * Essentials (enderchest: echest, eechest, eenderchest, endersee, eendersee, ec, eec) y los de AxVaults
     * (axvaults y los alias de su config: vault, vaults, pv, playervault...). Con la clave ausente vale esta
     * lista; dentro de Calamity no funcionan nunca (salvo con el permiso de BYPASS).
     */
    static final List<String> COMANDOS_ALMACEN = List.of("ec", "eec", "echest", "eechest", "enderchest", "eenderchest",
            "endersee", "eendersee", "pv", "pvs", "vault", "vaults", "playervault", "playervaults", "axvaults", "axvault",
            "axv", "pvault", "bovedas", "boveda");

    /** Fuera de Calamity: comandos de mercado que no se pueden usar con una Reliquia o Esencia en la mano. */
    static final List<String> MERCADO_MANO = List.of("ah", "auction", "auctionhouse", "auctions", "pa", "playerauction",
            "playerauctions", "pauction", "subasta", "subastas", "trade", "tradear", "intercambio", "sellhand");

    /** Fuera de Calamity: comandos que venden el inventario entero, que no se usan si llevas alguna. */
    static final List<String> MERCADO_INVENTARIO = List.of("sell", "sellall", "vender");

    private static final String NO_SE_GUARDA = "Las Reliquias y las Esencias no se pueden guardar ahí.";
    private static final String NO_SE_GUARDA_FUERA =
            "Fuera de Calamity, las Reliquias y las Esencias solo pueden ir en tu inventario. Véndeselas a Oren.";

    private final Hardcore hc;
    /** Ultimo aviso por jugador: un clic repetido no llena el chat ni la Bitacora. */
    private final Map<UUID, Long> ultimoAviso = new HashMap<>();
    private final Map<String, Long> ultimaNota = new HashMap<>();

    Sellos(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("sellos", this::autotest);
    }

    void parar() {
        ultimoAviso.clear();
        ultimaNota.clear();
    }

    // ------------------------------------------------------------------ config

    private boolean sello(String cual) {
        return hc.cfg().getBoolean("sellos.activo", true) && hc.cfg().getBoolean("sellos." + cual, true);
    }

    private static boolean staff(Player p) {
        return p.hasPermission("ederus.mundos");
    }

    /** Staff para los sellos de almacen: ederus.mundos o el permiso propio de BYPASS. */
    static boolean exento(Player p) {
        return p.hasPermission("ederus.mundos") || p.hasPermission(BYPASS);
    }

    private List<String> comandos() {
        return hc.cfg().isList("sellos.comandos") ? hc.cfg().getStringList("sellos.comandos") : COMANDOS_M4;
    }

    private List<String> lista(String clave, List<String> deSerie) {
        List<String> l = hc.cfg().isList("sellos." + clave) ? hc.cfg().getStringList("sellos." + clave) : deSerie;
        List<String> out = new ArrayList<>(l.size());
        for (String s : l) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    /** Si esa raiz es del cofre ender o de los vaults (Hardcore.onComando deja pasar al staff con BYPASS). */
    boolean comandoDeAlmacen(String raiz) {
        return raiz != null && lista("comandos-almacen", COMANDOS_ALMACEN).contains(raiz.toLowerCase(Locale.ROOT));
    }

    private String mensaje(String clave, String deSerie) {
        String t = hc.cfg().getString("sellos.mensajes." + clave);
        return t == null || t.isBlank() ? deSerie : t;
    }

    private String noSeGuarda(boolean dentro) {
        return dentro ? mensaje("no-se-guarda", NO_SE_GUARDA) : mensaje("no-se-guarda-fuera", NO_SE_GUARDA_FUERA);
    }

    // ---------------------------------------------------------------- portales

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (!hc.esHardcore(e.getFrom().getWorld())) return;
        Player p = e.getPlayer();
        if (!sello("portales") || staff(p)) return;
        e.setCancelled(true);
        avisar(p, "Los portales no funcionan en Calamity.", "portal", e.getFrom());
    }

    /** Los mobs y los items tampoco: un portal no puede ser una tolva hacia fuera. */
    @EventHandler(ignoreCancelled = true)
    public void onPortalEntidad(EntityPortalEvent e) {
        if (!hc.esHardcore(e.getFrom().getWorld()) || !sello("portales")) return;
        e.setCancelled(true);
    }

    // ------------------------------------------------------- cofre ender y bloques

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBloque(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Player p = e.getPlayer();
        if (exento(p)) return;
        boolean dentro = hc.esHardcore(b.getWorld());
        Material m = b.getType();
        if (dentro && m == Material.ENDER_CHEST && sello("cofre-ender")) {
            e.setCancelled(true);
            avisar(p, mensaje("cofre-ender", "En Calamity no puedes usar el cofre de ender."), "cofre-ender", b.getLocation());
            return;
        }
        if (!(dentro ? sello("contenedores") : sello("fuera"))) return;
        if (bloqueGuarda(m) && valioso(e.getItem())) {
            e.setCancelled(true);
            avisar(p, noSeGuarda(dentro), "bloque " + m.getKey().getKey(), b.getLocation());
        }
    }

    /** Jarrones, estanterias cinceladas y estantes: bloques que se quedan un objeto a la vista. */
    static boolean bloqueGuarda(Material m) {
        return m == Material.DECORATED_POT || m == Material.CHISELED_BOOKSHELF || m.name().endsWith("_SHELF");
    }

    /** Por si otro plugin abre el cofre ender sin pasar por el bloque ni por /ec. */
    @EventHandler(ignoreCancelled = true)
    public void onAbrir(InventoryOpenEvent e) {
        if (e.getInventory().getType() != InventoryType.ENDER_CHEST) return;
        if (!(e.getPlayer() instanceof Player p) || !hc.esHardcore(p) || exento(p) || !sello("cofre-ender")) return;
        e.setCancelled(true);
        avisar(p, mensaje("cofre-ender", "En Calamity no puedes usar el cofre de ender."), "cofre-ender", p.getLocation());
    }

    // ------------------------------------------------------------ contenedores

    /** Si el sello de almacen rige para este jugador donde esta (dentro: contenedores; fuera: fuera). */
    private boolean sellado(Player p, boolean dentro) {
        if (exento(p)) return false;
        return dentro ? sello("contenedores") : sello("fuera");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClic(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        boolean dentro = hc.esHardcore(p);
        if (!sellado(p, dentro)) return;
        // Bolsa: con una Reliquia en el cursor sobre una bolsa, o al reves, sea donde sea.
        if (aBolsa(e.getAction(), e.getCursor(), e.getCurrentItem())) {
            e.setCancelled(true);
            avisar(p, mensaje("bolsa", "Las Reliquias y las Esencias no se pueden meter en una bolsa."), "bolsa", p.getLocation());
            return;
        }
        Inventory arriba = e.getView().getTopInventory();
        InventoryHolder holder = arriba.getHolder(false);
        if (permitido(arriba.getType(), holder, p, dentro)) return;
        boolean bloquea = valioso(entraArriba(e));
        // La ventana de un plugin (subasta, tienda, trade...) puede llevarse lo que tocas abajo con un clic.
        if (!bloquea && ventanaDePlugin(arriba.getType(), holder) && e.getClickedInventory() != null
                && e.getClickedInventory() != arriba && (valioso(e.getCurrentItem()) || valioso(e.getCursor()))) {
            bloquea = true;
        }
        if (!bloquea) return;
        e.setCancelled(true);
        avisar(p, noSeGuarda(dentro), "contenedor " + arriba.getType().name().toLowerCase(Locale.ROOT) + (dentro ? "" : " fuera"),
                p.getLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArrastrar(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        boolean dentro = hc.esHardcore(p);
        if (!sellado(p, dentro)) return;
        if (!valioso(e.getOldCursor()) || !tocaArriba(e)) return;
        Inventory arriba = e.getView().getTopInventory();
        if (permitido(arriba.getType(), arriba.getHolder(false), p, dentro)) return;
        e.setCancelled(true);
        avisar(p, noSeGuarda(dentro), "contenedor " + arriba.getType().name().toLowerCase(Locale.ROOT) + (dentro ? "" : " fuera"),
                p.getLocation());
    }

    /**
     * Donde puede entrar una Reliquia o una Esencia. Siempre: tu propio inventario y los menus de
     * Calamity y LethalWorld (que ya cancelan sus clics). Dentro, ademas, los cofres, barriles y vagonetas
     * del mundo (contenedorPermitido). Fuera, nada mas.
     */
    static boolean permitido(InventoryType tipo, Object holder, HumanEntity quien, boolean dentro) {
        if (tipo == InventoryType.CRAFTING || tipo == InventoryType.CREATIVE) return true;
        if (tipo == InventoryType.PLAYER) return holder == null || holder.equals(quien);
        if (holder != null && (holder.getClass().getName().startsWith("net.ederus.calamity.")
                || holder.getClass().getName().startsWith("net.ederus.lethalworld."))) return true;
        return dentro && contenedorPermitido(tipo, holder);
    }

    /** La ventana es de un plugin (sin bloque ni entidad detras): /ah, /pv, /trade, tiendas... */
    static boolean ventanaDePlugin(InventoryType tipo, Object holder) {
        if (tipo == InventoryType.CRAFTING || tipo == InventoryType.CREATIVE || tipo == InventoryType.PLAYER) return false;
        return !(holder instanceof BlockInventoryHolder || holder instanceof DoubleChest || holder instanceof Entity);
    }

    /** Un clic que mete algo en una bolsa con una Reliquia o Esencia de por medio. */
    private boolean aBolsa(InventoryAction accion, ItemStack cursor, ItemStack actual) {
        if ((esBolsa(cursor) && valioso(actual)) || (esBolsa(actual) && valioso(cursor))) return true;
        return accion != null && accion.name().contains("BUNDLE") && (valioso(cursor) || valioso(actual));
    }

    /** Tolvas y soltadores: nunca a una shulker; dentro, de un cofre valido a otro; fuera, a nada. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMover(InventoryMoveItemEvent e) {
        if (!valioso(e.getItem())) return;
        Inventory destino = e.getDestination();
        if (destino.getType() == InventoryType.SHULKER_BOX && sello("contenedores")) {
            e.setCancelled(true);
            return;
        }
        Location l = destino.getLocation() != null ? destino.getLocation() : e.getSource().getLocation();
        boolean dentro = l != null && hc.esHardcore(l.getWorld());
        if (!dentro) {
            if (sello("fuera")) e.setCancelled(true);
            return;
        }
        if (!sello("contenedores")) return;
        if (!contenedorPermitido(destino.getType(), destino.getHolder(false))) e.setCancelled(true);
    }

    /** Una tolva (o vagoneta tolva) que chupa una Reliquia del suelo. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTolva(InventoryPickupItemEvent e) {
        if (!valioso(e.getItem().getItemStack())) return;
        if (!hc.esHardcore(e.getItem().getWorld())) {
            if (sello("fuera")) e.setCancelled(true);
            return;
        }
        if (!sello("contenedores")) return;
        Inventory inv = e.getInventory();
        if (!contenedorPermitido(inv.getType(), inv.getHolder(false))) e.setCancelled(true);
    }

    /** Marcos, allays y animales de carga (burros, llamas, mulas, camellos). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntidad(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        boolean dentro = hc.esHardcore(p);
        if (!sellado(p, dentro)) return;
        Entity t = e.getRightClicked();
        if (!(t instanceof ItemFrame || t instanceof Allay || t instanceof AbstractHorse)) return;
        ItemStack mano = e.getHand() == EquipmentSlot.OFF_HAND
                ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        if (!valioso(mano)) return;
        e.setCancelled(true);
        avisar(p, noSeGuarda(dentro), "entidad " + t.getType().getKey().getKey(), t.getLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSoporte(PlayerArmorStandManipulateEvent e) {
        Player p = e.getPlayer();
        boolean dentro = hc.esHardcore(p);
        if (!sellado(p, dentro) || !valioso(e.getPlayerItem())) return;
        e.setCancelled(true);
        avisar(p, noSeGuarda(dentro), "soporte", e.getRightClicked().getLocation());
    }

    // ---------------------------------------------------------- el suelo, fuera

    /**
     * Fuera de Calamity se pueden tirar (son del jugador), pero no para darselas a otro: el objeto en el
     * suelo queda a nombre de quien lo tira (Item.setOwner: nadie mas lo recoge) y onRecoger lo remata.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTirar(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (hc.esHardcore(p) || !sello("fuera") || exento(p)) return;
        Item it = e.getItemDrop();
        if (!valioso(it.getItemStack())) return;
        it.setOwner(p.getUniqueId());
        it.setCanMobPickup(false);
    }

    /** Si ese objeto del suelo esta fuera de Calamity, con el sello de fuera, y es Reliquia o Esencia (Suelo). */
    boolean selladaFuera(Item it) {
        return it != null && !hc.esHardcore(it.getWorld()) && sello("fuera") && valioso(it.getItemStack());
    }

    /** Fuera: ningun mob, y solo quien la tiro si se sabe quien fue. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRecoger(EntityPickupItemEvent e) {
        Item it = e.getItem();
        if (hc.esHardcore(it.getWorld()) || !sello("fuera") || !valioso(it.getItemStack())) return;
        if (!(e.getEntity() instanceof Player p)) {
            e.setCancelled(true);
            return;
        }
        if (exento(p)) return;
        UUID dueno = it.getOwner() != null ? it.getOwner() : it.getThrower();
        if (dueno == null || dueno.equals(p.getUniqueId())) return;
        e.setCancelled(true);
        avisar(p, mensaje("ajena", "Esa Reliquia no es tuya: solo la puede recoger quien la tiró."), "recoger-ajena",
                it.getLocation());
    }

    /**
     * Morir fuera de Calamity no las suelta: se quedan en el inventario (si no, matar a alguien o morir a
     * proposito serian otra forma de pasarlas). Dentro manda Calamity: se pierden, o las guarda el Eco.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMorir(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (hc.esHardcore(p) || !sello("fuera") || e.getKeepInventory()) return;
        int n = 0;
        for (Iterator<ItemStack> i = e.getDrops().iterator(); i.hasNext(); ) {
            ItemStack it = i.next();
            if (!valioso(it)) continue;
            i.remove();
            e.getItemsToKeep().add(it);
            n += it.getAmount();
        }
        if (n > 0) hc.plugin().bitacora().anotar("sellos", "muerte-fuera", p.getName(), String.valueOf(n));
    }

    // ---------------------------------------------------------------- comandos

    /**
     * Dentro de Calamity: las raices de DIS M4 y las del cofre ender y los vaults (comandos-almacen). Va
     * en HIGH y con ignoreCancelled: si comandos-prohibidos (de Hardcore.onComando) ya las tiene, alli se
     * cancela antes y aqui no se repite el aviso.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onComando(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!hc.esHardcore(p) || !hc.cfg().getBoolean("sellos.activo", true)) return;
        String raiz = raiz(e.getMessage());
        if (raiz.isEmpty()) return;
        if (comandoDeAlmacen(raiz)) {
            if (exento(p)) return;
            e.setCancelled(true);
            avisar(p, mensaje("comando-almacen", "En Calamity no puedes usar el cofre de ender ni los vaults."),
                    "comando " + raiz, p.getLocation());
            return;
        }
        if (staff(p) || !comandos().contains(raiz)) return;
        e.setCancelled(true);
        avisar(p, "En Calamity no puedes usar ese comando.", "comando " + raiz, p.getLocation());
    }

    /**
     * Fuera de Calamity: los comandos de mercado no se usan con una Reliquia o Esencia en la mano (/ah sell
     * pone en venta lo de la mano), y los que venden todo el inventario, si llevas alguna.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onComandoFuera(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (hc.esHardcore(p) || !sello("fuera") || exento(p)) return;
        String raiz = raiz(e.getMessage());
        if (raiz.isEmpty()) return;
        boolean bloquea = false;
        if (lista("comandos-mercado-mano", MERCADO_MANO).contains(raiz)) {
            bloquea = valioso(p.getInventory().getItemInMainHand()) || valioso(p.getInventory().getItemInOffHand());
        } else if (lista("comandos-mercado-inventario", MERCADO_INVENTARIO).contains(raiz)) {
            for (ItemStack it : p.getInventory().getContents()) bloquea |= valioso(it);
        }
        if (!bloquea) return;
        e.setCancelled(true);
        avisar(p, mensaje("comando-mercado", "Las Reliquias y las Esencias no se venden ahí: véndeselas a Oren, en el spawn de Calamity."),
                "comando-fuera " + raiz, p.getLocation());
    }

    @EventHandler
    public void onSalir(PlayerQuitEvent e) {
        ultimoAviso.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------- utilidades

    /** Reliquia o Esencia: lo unico que los sellos vigilan (lo que Oren compra). */
    boolean valioso(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        if (Marcas.tiene(item, Marcas.RELIQUIA) || hc.items().esEsencia(item)) return true;
        Reliquias r = hc.reliquias();
        return r != null && hc.valor("reliquias", () -> r.es(item), false);
    }

    /**
     * Los tres sitios de DIS M4 (ademas del suelo): el inventario del propio jugador y los
     * cofres (normales, trampa y dobles), barriles y vagonetas con cofre del mundo. Todo lo
     * demas (shulker, cofre ender, tolva, horno, burro, crafter, menus de plugins) no.
     */
    static boolean contenedorPermitido(InventoryType tipo, Object holder) {
        if (tipo == InventoryType.CRAFTING || tipo == InventoryType.PLAYER || tipo == InventoryType.CREATIVE) return true;
        if (tipo == InventoryType.ENDER_CHEST || tipo == InventoryType.SHULKER_BOX) return false;
        return holder instanceof Chest || holder instanceof DoubleChest || holder instanceof Barrel
                || holder instanceof StorageMinecart;
    }

    /**
     * El objeto que un clic mete en el inventario de ARRIBA (el contenedor), o null si el clic
     * no mete nada arriba. Lo comparten Sellos y Ligado: los dos sellan "que no entre ahi".
     */
    static ItemStack entraArriba(InventoryClickEvent e) {
        InventoryView v = e.getView();
        int raw = e.getRawSlot();
        boolean arriba = raw >= 0 && raw < v.getTopInventory().getSize();
        return switch (e.getAction()) {
            case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR -> arriba ? e.getCursor() : null;
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                if (!arriba) yield null;
                HumanEntity h = e.getWhoClicked();
                if (e.getClick() == ClickType.SWAP_OFFHAND) yield h.getInventory().getItemInOffHand();
                int n = e.getHotbarButton();
                yield n >= 0 ? h.getInventory().getItem(n) : null;
            }
            case MOVE_TO_OTHER_INVENTORY -> arriba ? null : e.getCurrentItem();
            default -> null;
        };
    }

    /** Si un arrastre deja algo en alguna casilla del inventario de arriba. */
    static boolean tocaArriba(InventoryDragEvent e) {
        int tam = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) if (raw < tam) return true;
        return false;
    }

    /** Bolsa de cualquier color (BUNDLE, RED_BUNDLE...). */
    static boolean esBolsa(ItemStack item) {
        return item != null && item.getType().name().endsWith("BUNDLE");
    }

    /** "/Minecraft:AH sell 10" -> "ah". */
    static String raiz(String mensaje) {
        if (mensaje == null) return "";
        String cmd = mensaje.trim().toLowerCase(Locale.ROOT);
        int espacio = cmd.indexOf(' ');
        if (espacio >= 0) cmd = cmd.substring(0, espacio);
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        int dos = cmd.indexOf(':');
        if (dos >= 0) cmd = cmd.substring(dos + 1);
        return cmd;
    }

    /**
     * Aviso en el chat como mucho una vez cada dos segundos (rama venta-oren: antes era un destello en la
     * barra de la cordura, que fuera de Calamity no hay), y la nota en la Bitacora una vez por minuto y
     * jugador y tipo: intentar sacar algo por donde no se puede tambien es un dato para "me han robado" y
     * para ver por donde lo intentan.
     */
    private void avisar(Player p, String texto, String que, Location donde) {
        long ahora = System.currentTimeMillis();
        Long antes = ultimoAviso.get(p.getUniqueId());
        if (antes == null || ahora - antes >= 2000) {
            ultimoAviso.put(p.getUniqueId(), ahora);
            p.sendMessage(ComandoCalamity.mensaje(Component.text(texto, Paleta.AVISO)));
            Marco.sonidoNo(p);
        }
        String clave = p.getUniqueId() + "|" + que;
        Long nota = ultimaNota.get(clave);
        if (nota != null && ahora - nota < 60_000) return;
        if (ultimaNota.size() > 512) ultimaNota.values().removeIf(t -> ahora - t >= 60_000);
        ultimaNota.put(clave, ahora);
        hc.plugin().bitacora().anotar("sellos", que, p.getName(),
                donde == null || donde.getWorld() == null ? "?" : donde.getWorld().getKey().getKey() + " "
                        + donde.getBlockX() + " " + donde.getBlockY() + " " + donde.getBlockZ());
    }

    // --------------------------------------------------------------- autotest

    /** PLAN WP4 aceptacion 1, en memoria: los holders son proxies, no bloques del mundo. */
    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        h.ok("Chest admitido", contenedorPermitido(InventoryType.CHEST, falso(Chest.class)));
        h.ok("DoubleChest admitido", contenedorPermitido(InventoryType.CHEST, new DoubleChest(null)));
        h.ok("Barrel admitido", contenedorPermitido(InventoryType.BARREL, falso(Barrel.class)));
        h.ok("StorageMinecart admitido", contenedorPermitido(InventoryType.CHEST, falso(StorageMinecart.class)));
        h.ok("inventario propio admitido", contenedorPermitido(InventoryType.CRAFTING, falso(Player.class)));
        h.ok("ShulkerBox rechazado", !contenedorPermitido(InventoryType.SHULKER_BOX, falso(ShulkerBox.class)));
        h.ok("Hopper rechazado", !contenedorPermitido(InventoryType.HOPPER, falso(Hopper.class)));
        h.ok("EnderChest rechazado", !contenedorPermitido(InventoryType.ENDER_CHEST, falso(EnderChest.class)));
        h.ok("cofre ender (holder jugador) rechazado", !contenedorPermitido(InventoryType.ENDER_CHEST, falso(Player.class)));
        h.ok("AbstractHorse rechazado", !contenedorPermitido(InventoryType.CHEST, falso(AbstractHorse.class)));
        h.ok("menu de plugin sin holder rechazado", !contenedorPermitido(InventoryType.CHEST, null));
        h.ok("horno rechazado", !contenedorPermitido(InventoryType.FURNACE, null));

        // Rama venta-oren: fuera de Calamity, solo el inventario propio y los menus de Calamity.
        Object yo = falso(Player.class);
        HumanEntity quien = (HumanEntity) yo;
        h.ok("fuera: tu inventario si", permitido(InventoryType.CRAFTING, yo, quien, false));
        h.ok("fuera: cofre no", !permitido(InventoryType.CHEST, falso(Chest.class), quien, false));
        h.ok("fuera: cofre doble no", !permitido(InventoryType.CHEST, new DoubleChest(null), quien, false));
        h.ok("fuera: barril no", !permitido(InventoryType.BARREL, falso(Barrel.class), quien, false));
        h.ok("fuera: shulker no", !permitido(InventoryType.SHULKER_BOX, falso(ShulkerBox.class), quien, false));
        h.ok("fuera: cofre ender (/ec, holder el jugador) no", !permitido(InventoryType.ENDER_CHEST, yo, quien, false));
        h.ok("fuera: cofre ender de bloque no", !permitido(InventoryType.ENDER_CHEST, null, quien, false));
        h.ok("fuera: /pv (menu de plugin) no", !permitido(InventoryType.CHEST, falso(InventoryHolder.class), quien, false));
        h.ok("fuera: menu sin holder (/ah) no", !permitido(InventoryType.CHEST, null, quien, false));
        h.ok("fuera: tolva no", !permitido(InventoryType.HOPPER, falso(Hopper.class), quien, false));
        h.ok("fuera: mesa de crafteo no", !permitido(InventoryType.WORKBENCH, null, quien, false));
        h.ok("fuera: aldeano no", !permitido(InventoryType.MERCHANT, null, quien, false));
        h.ok("fuera: yunque no", !permitido(InventoryType.ANVIL, null, quien, false));
        h.ok("fuera: el inventario de otro (invsee) no", !permitido(InventoryType.PLAYER, falso(Player.class), quien, false));
        h.ok("fuera: un menu de Calamity si", permitido(InventoryType.CHEST, new MenuPropio(), quien, false));
        h.ok("dentro: cofre del mundo si", permitido(InventoryType.CHEST, falso(Chest.class), quien, true));
        h.ok("dentro: shulker no", !permitido(InventoryType.SHULKER_BOX, falso(ShulkerBox.class), quien, true));
        h.ok("dentro: cofre ender no", !permitido(InventoryType.ENDER_CHEST, yo, quien, true));
        h.ok("dentro: menu de otro plugin no", !permitido(InventoryType.CHEST, falso(InventoryHolder.class), quien, true));
        h.ok("ventana de plugin: /ah sin holder", ventanaDePlugin(InventoryType.CHEST, null));
        h.ok("ventana de plugin: /pv con holder propio", ventanaDePlugin(InventoryType.CHEST, falso(InventoryHolder.class)));
        h.ok("ventana de plugin: un cofre no lo es", !ventanaDePlugin(InventoryType.CHEST, falso(Chest.class)));
        h.ok("ventana de plugin: /ec (holder el jugador) no lo es", !ventanaDePlugin(InventoryType.ENDER_CHEST, yo));

        ItemStack reliquia = new ItemStack(Material.PRISMARINE_SHARD);
        ItemMeta meta = reliquia.getItemMeta();
        meta.getPersistentDataContainer().set(Marcas.RELIQUIA, PersistentDataType.INTEGER, 1);
        reliquia.setItemMeta(meta);
        h.ok("una Reliquia es valiosa", valioso(reliquia));
        h.ok("una Esencia es valiosa", valioso(hc.items().esencia(2)));
        h.ok("un bloque de piedra no", !valioso(new ItemStack(Material.STONE)));
        h.ok("null no", !valioso(null));
        h.ok("una bolsa es bolsa", esBolsa(new ItemStack(Material.BUNDLE)));
        h.ok("una bolsa de color es bolsa", esBolsa(new ItemStack(Material.RED_BUNDLE)));
        h.ok("bolsa: Reliquia en el cursor sobre una bolsa", aBolsa(InventoryAction.SWAP_WITH_CURSOR, reliquia, new ItemStack(Material.BUNDLE)));
        h.ok("bolsa: bolsa en el cursor sobre una Reliquia", aBolsa(InventoryAction.PICKUP_ALL, new ItemStack(Material.BUNDLE), reliquia));
        h.ok("bolsa: sin Reliquia de por medio no", !aBolsa(InventoryAction.SWAP_WITH_CURSOR, new ItemStack(Material.STONE),
                new ItemStack(Material.BUNDLE)));
        h.ok("jarron guarda", bloqueGuarda(Material.DECORATED_POT));
        h.ok("estanteria cincelada guarda", bloqueGuarda(Material.CHISELED_BOOKSHELF));
        h.ok("un cofre no es de los que guardan a la vista", !bloqueGuarda(Material.CHEST));

        h.igual("raiz de /ah sell 10", "ah", raiz("/ah sell 10"));
        h.igual("raiz con namespace", "pv", raiz("/PlayerVaults:PV 1"));
        h.igual("raiz sin barra", "trade", raiz("trade Dosa__"));
        List<String> lista = comandos();
        for (String c : COMANDOS_M4) h.ok("sellado /" + c, lista.contains(c));
        for (String c : List.of("ec", "eec", "echest", "eechest", "enderchest", "eenderchest", "endersee", "pv", "vault",
                "vaults", "playervaults", "axvaults")) {
            h.ok("almacen /" + c + " bloqueado en Calamity", comandoDeAlmacen(c));
        }
        h.ok("almacen con namespace (/essentials:ec)", comandoDeAlmacen(raiz("/essentials:ec")));
        h.ok("/spawn no es de almacen", !comandoDeAlmacen("spawn"));
        h.ok("fuera: /ah con una Reliquia en la mano se bloquea", lista("comandos-mercado-mano", MERCADO_MANO).contains("ah"));
        h.ok("fuera: /sell con una Reliquia encima se bloquea", lista("comandos-mercado-inventario", MERCADO_INVENTARIO).contains("sell"));
        h.ok("/spawn no es de Sellos (lo lleva comandos-prohibidos)", !COMANDOS_M4.contains("spawn"));
        h.ok("sellos de portales encendidos", sello("portales"));
        h.ok("sello del cofre ender encendido", sello("cofre-ender"));
        h.ok("sello de contenedores encendido", sello("contenedores"));
        h.ok("sello de fuera encendido", sello("fuera"));
        return h.lineas();
    }

    /** Un holder propio (su paquete empieza por net.ederus.calamity.). */
    private static final class MenuPropio implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    /** Un holder de mentira del tipo pedido, solo para que instanceof conteste. */
    private static Object falso(Class<?> tipo) {
        return Proxy.newProxyInstance(tipo.getClassLoader(), new Class<?>[]{tipo}, (proxy, metodo, args) -> {
            if (metodo.getName().equals("equals")) return proxy == args[0];
            if (metodo.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (metodo.getName().equals("toString")) return "falso " + tipo.getSimpleName();
            Class<?> r = metodo.getReturnType();
            if (r == boolean.class) return false;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            if (r == double.class) return 0.0;
            if (r == float.class) return 0f;
            if (r == short.class) return (short) 0;
            if (r == byte.class) return (byte) 0;
            if (r == char.class) return (char) 0;
            return null;
        });
    }
}
