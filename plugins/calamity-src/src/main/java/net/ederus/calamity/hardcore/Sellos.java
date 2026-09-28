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
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * M4 · Sellos del Umbral (DIS M4): que lo de dentro solo salga por la puerta o el Cristal.
 *
 * Todo lo que Calamity paga se cobra al salir vivo (regla 8). Cada agujero que deja sacar
 * algo sin pasar por la Tasacion o guardarlo a salvo a mitad de expedicion rompe esa regla:
 * - portales: un portal del Nether construido dentro era una salida sin Cristal (X04);
 * - cofre ender: un banco a mitad de expedicion, aunque el comando ya estaba prohibido (X05);
 * - contenedores: Reliquias y Esencias solo viven en el inventario, en el suelo y en los
 *   cofres, barriles y vagonetas del mundo, que cualquiera puede abrir y saquear. Shulker,
 *   bolsa, burro, marco, soporte o jarron serian bolsillos seguros (X06);
 * - comandos: /pv, /ah, /trade... (los de DIS M4) como bancos o grifos a mitad de camino.
 *
 * Quien tiene ederus.mundos (staff) no esta sellado. Cada sello tiene su interruptor en
 * hardcore.sellos. Los mobs que recogen (X14) ya los cierra Hardcore.onRecoger.
 */
final class Sellos implements Listener {

    /*
     * Raices que DIS M4 anade a comandos-prohibidos. Van tambien aqui, con su propia clave
     * (sellos.comandos), porque un config.yml ya instalado conserva su lista vieja de
     * comandos-prohibidos: Bukkit no mezcla listas, y en produccion y en el Test esas raices
     * no llegarian nunca. Con la clave ausente vale esta lista; "sellos.comandos: []" la apaga.
     */
    static final List<String> COMANDOS_M4 = List.of("pv", "playervault", "playervaults", "vault", "ah", "auction",
            "subasta", "trade", "kit", "kits", "gkit", "sell", "pay", "shop", "auctionhouse", "pa", "playerauction",
            "pauction");

    /** El aviso de los contenedores: lo unico que se vigila son las Reliquias y las Esencias (valioso). */
    private static final String NO_SE_GUARDA = "Las Reliquias y las Esencias no se pueden guardar ahí.";

    private final Hardcore hc;
    /** Ultimo aviso por jugador: un clic repetido no llena la barra ni la Bitacora. */
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

    private List<String> comandos() {
        return hc.cfg().isList("sellos.comandos") ? hc.cfg().getStringList("sellos.comandos") : COMANDOS_M4;
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
        if (b == null || !hc.esHardcore(b.getWorld())) return;
        Player p = e.getPlayer();
        if (staff(p)) return;
        Material m = b.getType();
        if (m == Material.ENDER_CHEST && sello("cofre-ender")) {
            e.setCancelled(true);
            avisar(p, "En Calamity no puedes usar el cofre de ender.", "cofre-ender", b.getLocation());
            return;
        }
        if (sello("contenedores") && bloqueGuarda(m) && valioso(e.getItem())) {
            e.setCancelled(true);
            avisar(p, NO_SE_GUARDA, "bloque " + m.getKey().getKey(), b.getLocation());
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
        if (!(e.getPlayer() instanceof Player p) || !hc.esHardcore(p) || staff(p) || !sello("cofre-ender")) return;
        e.setCancelled(true);
        avisar(p, "En Calamity no puedes usar el cofre de ender.", "cofre-ender", p.getLocation());
    }

    // ------------------------------------------------------------ contenedores

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClic(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !hc.esHardcore(p) || staff(p) || !sello("contenedores")) return;
        // Bolsa: con una Reliquia en el cursor sobre una bolsa, o al reves, sea donde sea.
        if ((esBolsa(e.getCursor()) && valioso(e.getCurrentItem()))
                || (esBolsa(e.getCurrentItem()) && valioso(e.getCursor()))) {
            e.setCancelled(true);
            avisar(p, NO_SE_GUARDA, "bolsa", p.getLocation());
            return;
        }
        ItemStack entra = entraArriba(e);
        if (!valioso(entra)) return;
        Inventory arriba = e.getView().getTopInventory();
        if (contenedorPermitido(arriba.getType(), arriba.getHolder(false))) return;
        e.setCancelled(true);
        avisar(p, NO_SE_GUARDA, "contenedor " + arriba.getType().name().toLowerCase(Locale.ROOT),
                p.getLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArrastrar(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !hc.esHardcore(p) || staff(p) || !sello("contenedores")) return;
        if (!valioso(e.getOldCursor()) || !tocaArriba(e)) return;
        Inventory arriba = e.getView().getTopInventory();
        if (contenedorPermitido(arriba.getType(), arriba.getHolder(false))) return;
        e.setCancelled(true);
        avisar(p, NO_SE_GUARDA, "contenedor " + arriba.getType().name().toLowerCase(Locale.ROOT),
                p.getLocation());
    }

    /** Tolvas y soltadores: de un cofre valido a cualquier otra cosa, no. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMover(InventoryMoveItemEvent e) {
        if (!valioso(e.getItem())) return;
        Inventory destino = e.getDestination();
        Location l = destino.getLocation() != null ? destino.getLocation() : e.getSource().getLocation();
        if (l == null || !hc.esHardcore(l.getWorld()) || !sello("contenedores")) return;
        if (!contenedorPermitido(destino.getType(), destino.getHolder(false))) e.setCancelled(true);
    }

    /** Una tolva (o vagoneta tolva) que chupa una Reliquia del suelo. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTolva(InventoryPickupItemEvent e) {
        if (!valioso(e.getItem().getItemStack())) return;
        if (!hc.esHardcore(e.getItem().getWorld()) || !sello("contenedores")) return;
        Inventory inv = e.getInventory();
        if (!contenedorPermitido(inv.getType(), inv.getHolder(false))) e.setCancelled(true);
    }

    /** Marcos, allays y animales de carga (burros, llamas, mulas, camellos). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntidad(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (!hc.esHardcore(p) || staff(p) || !sello("contenedores")) return;
        Entity t = e.getRightClicked();
        if (!(t instanceof ItemFrame || t instanceof Allay || t instanceof AbstractHorse)) return;
        ItemStack mano = e.getHand() == EquipmentSlot.OFF_HAND
                ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        if (!valioso(mano)) return;
        e.setCancelled(true);
        avisar(p, NO_SE_GUARDA, "entidad " + t.getType().getKey().getKey(), t.getLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSoporte(PlayerArmorStandManipulateEvent e) {
        Player p = e.getPlayer();
        if (!hc.esHardcore(p) || staff(p) || !sello("contenedores") || !valioso(e.getPlayerItem())) return;
        e.setCancelled(true);
        avisar(p, NO_SE_GUARDA, "soporte", e.getRightClicked().getLocation());
    }

    // ---------------------------------------------------------------- comandos

    /**
     * Las raices de DIS M4. Va en HIGH y con ignoreCancelled: si comandos-prohibidos (de
     * Hardcore.onComando) ya las tiene, alli se cancela antes y aqui no se repite el aviso.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onComando(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!hc.esHardcore(p) || staff(p) || !hc.cfg().getBoolean("sellos.activo", true)) return;
        String raiz = raiz(e.getMessage());
        if (raiz.isEmpty() || !comandos().contains(raiz)) return;
        e.setCancelled(true);
        avisar(p, "En Calamity no puedes usar ese comando.", "comando " + raiz, p.getLocation());
    }

    @EventHandler
    public void onSalir(PlayerQuitEvent e) {
        ultimoAviso.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------- utilidades

    /** Reliquia o Esencia: lo unico que los sellos vigilan. */
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
     * Destello (P-S01/02/03) como mucho una vez por segundo, y la nota en la Bitacora una vez
     * por minuto y jugador y tipo: intentar sacar algo por donde no se puede tambien es un
     * dato para "me han robado" y para ver por donde lo intentan.
     */
    private void avisar(Player p, String texto, String que, Location donde) {
        long ahora = System.currentTimeMillis();
        Long antes = ultimoAviso.get(p.getUniqueId());
        if (antes == null || ahora - antes >= 1000) {
            ultimoAviso.put(p.getUniqueId(), ahora);
            hc.cordura().destello(p, Component.text(texto, Paleta.AVISO), 2);
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

        ItemStack reliquia = new ItemStack(Material.PRISMARINE_SHARD);
        ItemMeta meta = reliquia.getItemMeta();
        meta.getPersistentDataContainer().set(Marcas.RELIQUIA, PersistentDataType.INTEGER, 1);
        reliquia.setItemMeta(meta);
        h.ok("una Reliquia es valiosa", valioso(reliquia));
        h.ok("un bloque de piedra no", !valioso(new ItemStack(Material.STONE)));
        h.ok("null no", !valioso(null));
        h.ok("una bolsa es bolsa", esBolsa(new ItemStack(Material.BUNDLE)));
        h.ok("una bolsa de color es bolsa", esBolsa(new ItemStack(Material.RED_BUNDLE)));
        h.ok("jarron guarda", bloqueGuarda(Material.DECORATED_POT));
        h.ok("estanteria cincelada guarda", bloqueGuarda(Material.CHISELED_BOOKSHELF));
        h.ok("un cofre no es de los que guardan a la vista", !bloqueGuarda(Material.CHEST));

        h.igual("raiz de /ah sell 10", "ah", raiz("/ah sell 10"));
        h.igual("raiz con namespace", "pv", raiz("/PlayerVaults:PV 1"));
        h.igual("raiz sin barra", "trade", raiz("trade Dosa__"));
        List<String> lista = comandos();
        for (String c : COMANDOS_M4) h.ok("sellado /" + c, lista.contains(c));
        h.ok("/spawn no es de Sellos (lo lleva comandos-prohibidos)", !COMANDOS_M4.contains("spawn"));
        h.ok("sellos de portales encendidos", sello("portales"));
        h.ok("sello del cofre ender encendido", sello("cofre-ender"));
        h.ok("sello de contenedores encendido", sello("contenedores"));
        return h.lineas();
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
