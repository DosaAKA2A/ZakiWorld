package net.ederus.calamity.hardcore;

import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Tag;
import org.bukkit.block.Barrel;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Hopper;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * M30 · Objetos ligados (lethal_world:ligado = uuid del dueno): lo que Calamity entrega fuera
 * no se vende, no se cambia y en manos de otro no sirve (DIS M30, ESTUDIO sec. 5.0).
 *
 * Por que: sin esto, el primero del baltop compraba el Manto en /ah sin pisar Calamity y el
 * premio dejaba de ser de quien lo gana. La marca la ponen SOLO Entregas (M31), el Altar y
 * la Forja (con ligar(), abajo); aqui solo se lee y se vigila.
 *
 * Tres barreras, de la mas barata a la que lo cubre todo:
 * 1. comandos de mercado (ligado.comandos-mercado) con un ligado en la mano;
 * 2. ventanas: un ligado no entra en un inventario que no sea el suyo, un cofre, un barril,
 *    una shulker (fuera de Calamity) o un menu de LethalWorld. Cubre /ah, /trade y los menus
 *    de cualquier plugin sin conocer su API;
 * 3. uso: otro no lo recoge del suelo, no se lo puede poner y no pega con el.
 * Y la medida de la fuga: si un ligado aparece en el inventario de otro (al entrar o al
 * abrir algo), Bitacora y telemetria "ligado-ajeno" (MED sec. 3.3).
 *
 * Una bolsa o una shulker con un ligado dentro cuenta como ligado: si no, el ligado se
 * venderia dentro de la caja. Fuera de Calamity rige igual: por eso el listener no pregunta
 * esHardcore (el mercado esta fuera), pero lo primero que mira es la marca.
 *
 * No es el soulbound de MMOItems: ese conserva el objeto al morir en todo el servidor y
 * chocaria con "en Calamity lo pierdes todo".
 */
final class Ligado implements Listener {

    /*
     * Ventanas de un solo jugador que devuelven lo que tienen al cerrarse (yunque, mesa de
     * encantar, afiladora, herreria...). DIS M30 no las nombra, pero sin ellas nadie podria
     * reparar ni encantar su propio Manto; nadie mas ve esas casillas, asi que no son una via
     * de venta. Solo cuentan si la ventana es la de un bloque vanilla (sin holder de plugin).
     */
    private static final Set<InventoryType> PUESTOS = EnumSet.of(InventoryType.WORKBENCH, InventoryType.ANVIL,
            InventoryType.GRINDSTONE, InventoryType.SMITHING, InventoryType.ENCHANTING, InventoryType.CARTOGRAPHY,
            InventoryType.LOOM, InventoryType.STONECUTTER);

    private final Hardcore hc;
    /** Ultimo aviso por jugador (P-B01/P-B02): un clic repetido no llena la barra. */
    private final Map<UUID, Long> ultimoAviso = new HashMap<>();
    /** Ultima nota por jugador y via (bloqueado) o por objeto (ajeno), para no inundar. */
    private final Map<String, Long> ultimaNota = new HashMap<>();

    Ligado(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("ligado", this::autotest);
        Subcomandos.lw().registrar("ligado",
                "ligado <info|poner [jugador]|quitar>: el ligado del objeto de tu mano (pruebas)",
                "ederus.mundos", this::comando,
                args -> args.length == 2 ? List.of("info", "poner", "quitar")
                        : args.length == 3 && args[1].equalsIgnoreCase("poner") ? conectados() : List.of());
    }

    void parar() {
        ultimoAviso.clear();
        ultimaNota.clear();
    }

    private boolean activo() {
        return hc.cfg().getBoolean("ligado.activo", true);
    }

    private List<String> mercado() {
        List<String> l = hc.cfg().getStringList("ligado.comandos-mercado");
        return l.isEmpty() && !hc.cfg().isList("ligado.comandos-mercado")
                ? List.of("ah", "auctionhouse", "pa", "playerauction", "pauction", "trade", "sell", "shop") : l;
    }

    // ------------------------------------------------------------ API publica

    /** El dueno de un objeto ligado, o null si no lo esta (o la marca no es un uuid). */
    UUID dueno(ItemStack item) {
        return duenoDe(item);
    }

    boolean ligado(ItemStack item) {
        return duenoDe(item) != null;
    }

    static UUID duenoDe(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String s = pdc.get(Marcas.LIGADO, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException mala) {
            return null;
        }
    }

    /**
     * Liga un objeto a su dueno (lo cambia en el sitio y lo devuelve). Para Entregas, el
     * Altar y la Forja: que la marca se escriba en un solo sitio y con el mismo formato.
     */
    static ItemStack ligar(ItemStack item, UUID dueno) {
        if (item == null || item.getType().isAir() || dueno == null) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.getPersistentDataContainer().set(Marcas.LIGADO, PersistentDataType.STRING, dueno.toString());
        item.setItemMeta(meta);
        return item;
    }

    /**
     * El primer dueno que no es "portador" en el objeto o dentro de el (bolsa, shulker), o
     * null. Con portador null devuelve el primer dueno que encuentre.
     */
    static UUID ajeno(ItemStack item, UUID portador) {
        UUID d = duenoDe(item);
        if (d != null && !d.equals(portador)) return d;
        for (ItemStack dentro : contenido(item)) {
            UUID o = duenoDe(dentro);
            if (o != null && !o.equals(portador)) return o;
        }
        return null;
    }

    /** Si el objeto esta ligado o lleva algo ligado dentro (bolsa o shulker). */
    static boolean contieneLigado(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        if (duenoDe(item) != null) return true;
        for (ItemStack dentro : contenido(item)) if (duenoDe(dentro) != null) return true;
        return false;
    }

    /** Lo que lleva dentro una bolsa o una shulker en forma de objeto; vacio si no es de esas. */
    private static List<ItemStack> contenido(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return List.of();
        Material m = item.getType();
        boolean bolsa = m.name().endsWith("BUNDLE");
        boolean caja = Tag.SHULKER_BOXES.isTagged(m);
        if (!bolsa && !caja) return List.of();
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BundleMeta b) return b.getItems();
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof ShulkerBox sb) {
            List<ItemStack> l = new ArrayList<>();
            for (ItemStack it : sb.getInventory().getContents()) if (it != null) l.add(it);
            return l;
        }
        return List.of();
    }

    // ------------------------------------------------------ 1. comandos de mercado

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onComando(PlayerCommandPreprocessEvent e) {
        if (!activo()) return;
        Player p = e.getPlayer();
        ItemStack mano = p.getInventory().getItemInMainHand();
        ItemStack otra = p.getInventory().getItemInOffHand();
        ItemStack cual = contieneLigado(mano) ? mano : contieneLigado(otra) ? otra : null;
        if (cual == null) return;
        String raiz = Sellos.raiz(e.getMessage());
        if (!mercado().contains(raiz)) return;
        e.setCancelled(true);
        avisar(p, Component.text("Esto es tuyo. Nadie más puede llevarlo.", Paleta.AVISO));
        bloqueado(p, cual, "comando:" + raiz);
    }

    // ------------------------------------------------------------- 2. ventanas

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClic(InventoryClickEvent e) {
        if (!activo() || !(e.getWhoClicked() instanceof Player p)) return;
        ItemStack cursor = e.getCursor();
        ItemStack actual = e.getCurrentItem();
        // Un ligado no entra en una bolsa: la bolsa no esta ligada y se venderia con el dentro.
        if ((Sellos.esBolsa(cursor) && duenoDe(actual) != null) || (Sellos.esBolsa(actual) && duenoDe(cursor) != null)) {
            e.setCancelled(true);
            avisar(p, Component.text("Esto es tuyo. Nadie más puede llevarlo.", Paleta.AVISO));
            bloqueado(p, duenoDe(cursor) != null ? cursor : actual, "gui:bolsa");
            return;
        }
        ItemStack entra = Sellos.entraArriba(e);
        if (!contieneLigado(entra)) return;
        Inventory arriba = e.getView().getTopInventory();
        InventoryHolder holder = arriba.getHolder(false);
        if (permitido(arriba.getType(), holder, p, hc.esHardcore(p))) return;
        e.setCancelled(true);
        avisar(p, Component.text("Esto es tuyo. Nadie más puede llevarlo.", Paleta.AVISO));
        bloqueado(p, entra, "gui:" + nombreHolder(arriba.getType(), holder));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArrastrar(InventoryDragEvent e) {
        if (!activo() || !(e.getWhoClicked() instanceof Player p)) return;
        if (!contieneLigado(e.getOldCursor()) || !Sellos.tocaArriba(e)) return;
        Inventory arriba = e.getView().getTopInventory();
        InventoryHolder holder = arriba.getHolder(false);
        if (permitido(arriba.getType(), holder, p, hc.esHardcore(p))) return;
        e.setCancelled(true);
        avisar(p, Component.text("Esto es tuyo. Nadie más puede llevarlo.", Paleta.AVISO));
        bloqueado(p, e.getOldCursor(), "gui:" + nombreHolder(arriba.getType(), holder));
    }

    /**
     * Donde puede entrar un ligado (DIS M30 punto 2): su propio inventario (incluido su cofre
     * ender, cuyo holder es el jugador), cofres, barriles, shulkers fuera de Calamity (dentro
     * rigen los Sellos), menus de LethalWorld y las ventanas de un solo jugador de PUESTOS.
     */
    static boolean permitido(InventoryType tipo, Object holder, HumanEntity quien, boolean dentro) {
        if (tipo == InventoryType.CRAFTING || tipo == InventoryType.PLAYER || tipo == InventoryType.CREATIVE) return true;
        if (holder != null && quien != null && holder.equals(quien)) return true;
        // El cofre ender de un bloque siempre ensena el del que mira; sin holder, es el suyo.
        if (tipo == InventoryType.ENDER_CHEST && holder == null) return true;
        if (holder instanceof Chest || holder instanceof DoubleChest || holder instanceof Barrel) return true;
        if (holder instanceof ShulkerBox) return !dentro;
        // Menus propios: los de Calamity (net.ederus.calamity, hasta LethalWorld 1.1.1 dentro de
        // net.ederus.lethalworld) y los que pudiera tener LethalWorld, que sigue en su paquete.
        if (holder != null && (holder.getClass().getName().startsWith("net.ederus.calamity.")
                || holder.getClass().getName().startsWith("net.ederus.lethalworld."))) return true;
        return PUESTOS.contains(tipo) && (holder == null || holder instanceof BlockInventoryHolder);
    }

    private static String nombreHolder(InventoryType tipo, Object holder) {
        if (holder == null) return tipo.name().toLowerCase(Locale.ROOT);
        String n = holder.getClass().getSimpleName();
        return n.isEmpty() ? holder.getClass().getName() : n;
    }

    // ----------------------------------------------------------------- 3. uso

    /** Otro no lo recoge: se queda en el suelo para su dueno. Los mobs tampoco. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRecoger(EntityPickupItemEvent e) {
        if (!activo()) return;
        ItemStack it = e.getItem().getItemStack();
        if (!contieneLigado(it)) return;
        if (!(e.getEntity() instanceof Player p)) {
            e.setCancelled(true);
            return;
        }
        UUID d = ajeno(it, p.getUniqueId());
        if (d == null) return;
        e.setCancelled(true);
        avisarDueno(p, d);
    }

    /** Si se pone una pieza ligada de otro, vuelve al inventario (un tick despues). */
    @EventHandler
    public void onEquipo(EntityEquipmentChangedEvent e) {
        if (!activo() || !(e.getEntity() instanceof Player p)) return;
        for (Map.Entry<EquipmentSlot, EntityEquipmentChangedEvent.EquipmentChange> c : e.getEquipmentChanges().entrySet()) {
            EquipmentSlot slot = c.getKey();
            if (!slot.isArmor()) continue;
            UUID d = duenoDe(c.getValue().newItem());
            if (d == null || d.equals(p.getUniqueId())) continue;
            hc.plugin().getServer().getScheduler().runTask(hc.plugin(), () -> quitarPuesto(p, slot, d));
        }
    }

    private void quitarPuesto(Player p, EquipmentSlot slot, UUID d) {
        if (!p.isOnline()) return;
        ItemStack puesto = p.getInventory().getItem(slot);
        if (!d.equals(duenoDe(puesto))) return;
        p.getInventory().setItem(slot, null);
        for (ItemStack sobra : p.getInventory().addItem(puesto).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), sobra);
        }
        avisarDueno(p, d);
        bloqueado(p, puesto, "uso");
    }

    /** Pegar con un arma ligada de otro no hace nada. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGolpe(EntityDamageByEntityEvent e) {
        if (!activo() || !(e.getDamager() instanceof Player p)) return;
        ItemStack arma = p.getInventory().getItemInMainHand();
        UUID d = duenoDe(arma);
        if (d == null || d.equals(p.getUniqueId())) return;
        e.setCancelled(true);
        avisarDueno(p, d);
        bloqueado(p, arma, "uso");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDisparo(EntityShootBowEvent e) {
        if (!activo() || !(e.getEntity() instanceof Player p)) return;
        UUID d = duenoDe(e.getBow());
        if (d == null || d.equals(p.getUniqueId())) return;
        e.setCancelled(true);
        avisarDueno(p, d);
        bloqueado(p, e.getBow(), "uso");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLanzar(ProjectileLaunchEvent e) {
        if (!activo() || !(e.getEntity() instanceof Trident t) || !(t.getShooter() instanceof Player p)) return;
        ItemStack tridente = t.getItemStack();
        UUID d = duenoDe(tridente);
        if (d == null || d.equals(p.getUniqueId())) return;
        e.setCancelled(true);
        avisarDueno(p, d);
        bloqueado(p, tridente, "uso");
    }

    // -------------------------------------------------------- 4. ligado ajeno

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntrar(PlayerJoinEvent e) {
        if (activo()) revisar(e.getPlayer(), true);
    }

    /** Al abrir algo solo se mira la marca de cada objeto, sin abrir shulkers: pasa a menudo. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAbrir(InventoryOpenEvent e) {
        if (activo() && e.getPlayer() instanceof Player p) revisar(p, false);
    }

    @EventHandler
    public void onSalir(PlayerQuitEvent e) {
        ultimoAviso.remove(e.getPlayer().getUniqueId());
    }

    /** Un recorrido del inventario (41 casillas); con "dentro", tambien bolsas y shulkers. */
    private void revisar(Player p, boolean dentro) {
        UUID yo = p.getUniqueId();
        for (ItemStack it : p.getInventory().getContents()) {
            UUID d = dentro ? ajeno(it, yo) : duenoDe(it);
            if (d != null && !d.equals(yo)) ajenoVisto(p, d, it);
        }
    }

    /** Bitacora "ligado | ajeno" y telemetria, una vez por objeto y hora. */
    private void ajenoVisto(Player portador, UUID dueno, ItemStack item) {
        String objeto = objetoId(item);
        if (!nota(portador.getUniqueId() + "|" + dueno + "|" + objeto, 3_600_000L)) return;
        String nombreDueno = nombre(dueno);
        hc.plugin().bitacora().anotar("ligado", "ajeno", portador.getName(), nombreDueno, objeto);
        Telemetria t = hc.telemetria();
        if (t == null) return;
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("objeto", objeto);
        campos.put("dueno", dueno.toString());
        campos.put("portador", portador.getUniqueId().toString());
        hc.seguro("telemetria", () -> t.suceso("ligado-ajeno", portador, campos));
    }

    // ------------------------------------------------------------- utilidades

    /** Bitacora "ligado | bloqueado" y telemetria, una vez cada 10 s por jugador y via. */
    private void bloqueado(Player p, ItemStack item, String via) {
        if (!nota(p.getUniqueId() + "|" + via, 10_000L)) return;
        String objeto = objetoId(item);
        hc.plugin().bitacora().anotar("ligado", "bloqueado", p.getName(), objeto, via);
        Telemetria t = hc.telemetria();
        if (t == null) return;
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("objeto", objeto);
        campos.put("via", via);
        hc.seguro("telemetria", () -> t.suceso("ligado-bloqueado", p, campos));
    }

    /** true si toca apuntar (no se apunto esa clave en la ventana). Poda lo viejo de paso. */
    private boolean nota(String clave, long ventana) {
        long ahora = System.currentTimeMillis();
        Long antes = ultimaNota.get(clave);
        if (antes != null && ahora - antes < ventana) return false;
        if (ultimaNota.size() > 1024) ultimaNota.values().removeIf(t -> ahora - t >= 3_600_000L);
        ultimaNota.put(clave, ahora);
        return true;
    }

    /** "TIPO.ID" si es de MMOItems; si no, el material. Es lo que se lee en la Bitacora. */
    static String objetoId(ItemStack item) {
        if (item == null) return "?";
        String mmo = null;
        try {
            mmo = PuenteMmo.enlace(item);
        } catch (Throwable sinMmo) {
            // Sin MythicLib el enlace no existe: vale el material.
        }
        return mmo != null ? mmo : item.getType().getKey().getKey();
    }

    /** P-B01 (destello) como mucho una vez por segundo. Fuera de Calamity no hay cordura que pintar. */
    private void avisar(Player p, Component texto) {
        long ahora = System.currentTimeMillis();
        Long antes = ultimoAviso.get(p.getUniqueId());
        if (antes != null && ahora - antes < 1000) return;
        ultimoAviso.put(p.getUniqueId(), ahora);
        if (hc.esHardcore(p)) hc.cordura().destello(p, texto, 2);
        else p.sendActionBar(texto);
    }

    /** P-B02 por chat, como mucho una vez cada 10 s (recoger se intenta cada tick). */
    private void avisarDueno(Player p, UUID dueno) {
        if (!nota(p.getUniqueId() + "|p-b02", 10_000L)) return;
        p.sendMessage(ComandoCalamity.mensaje(Component.text("Eso lleva el nombre de ")
                .append(Component.text(nombre(dueno), Paleta.DETALLE))
                .append(Component.text(". No te sirve."))));
    }

    private String nombre(UUID u) {
        OfflinePlayer o = hc.plugin().getServer().getOfflinePlayer(u);
        return o.getName() == null ? "otra persona" : o.getName();
    }

    private List<String> conectados() {
        List<String> l = new ArrayList<>();
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) l.add(p.getName());
        return l;
    }

    // --------------------------------------------------------------- comando

    /**
     * Para probar sin Entregas ni Altar: ligar, ver o quitar la marca del objeto de la mano.
     * La marca de verdad la ponen Entregas, el Altar y la Forja (DIS M30).
     */
    private void comando(CommandSender quien, String[] args) {
        if (!(quien instanceof Player p)) {
            quien.sendMessage(ComandoCalamity.mensaje("Solo desde el juego: mira el objeto de tu mano."));
            return;
        }
        ItemStack mano = p.getInventory().getItemInMainHand();
        if (mano.getType().isAir()) {
            p.sendMessage(ComandoCalamity.mensaje("No llevas nada en la mano."));
            return;
        }
        String accion = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "info";
        switch (accion) {
            case "poner" -> {
                OfflinePlayer a = args.length > 2 ? hc.plugin().getServer().getOfflinePlayerIfCached(args[2]) : p;
                if (a == null) {
                    p.sendMessage(ComandoCalamity.mensaje("No encuentro a ese jugador."));
                    return;
                }
                ligar(mano, a.getUniqueId());
                hc.plugin().bitacora().anotar("ligado", "admin", p.getName(), objetoId(mano), "a " + a.getName());
                p.sendMessage(ComandoCalamity.mensaje("Ligado a " + a.getName() + "."));
            }
            case "quitar" -> {
                ItemMeta meta = mano.getItemMeta();
                meta.getPersistentDataContainer().remove(Marcas.LIGADO);
                mano.setItemMeta(meta);
                hc.plugin().bitacora().anotar("ligado", "admin-quitar", p.getName(), objetoId(mano));
                p.sendMessage(ComandoCalamity.mensaje("Ya no está ligado."));
            }
            default -> {
                UUID d = duenoDe(mano);
                p.sendMessage(ComandoCalamity.mensaje(d == null ? "No está ligado."
                        : "Ligado a " + nombre(d) + " (" + d + ")."));
            }
        }
    }

    // --------------------------------------------------------------- autotest

    /** PLAN WP4 aceptacion 2, en memoria con UUID sinteticos. */
    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        UUID u = Autotest.sintetico(43), otro = Autotest.sintetico(44);

        ItemStack espada = ligar(new ItemStack(Material.NETHERITE_SWORD), u);
        h.igual("dueno() de un objeto ligado con ligar", u, dueno(espada));
        h.ok("ligado() de un objeto ligado", ligado(espada));
        h.ok("una espada sin marca no esta ligada", !ligado(new ItemStack(Material.NETHERITE_SWORD)));
        h.igual("dueno() de null", null, dueno(null));
        ItemStack mala = new ItemStack(Material.STONE);
        ItemMeta mm = mala.getItemMeta();
        mm.getPersistentDataContainer().set(Marcas.LIGADO, PersistentDataType.STRING, "no-es-un-uuid");
        mala.setItemMeta(mm);
        h.igual("una marca que no es uuid no liga", null, dueno(mala));

        Entregas en = hc.entregas();
        if (en != null) {
            ItemStack dada = hc.valor("entregas", () -> en.ligar(new ItemStack(Material.DIAMOND_CHESTPLATE), u), null);
            // Con el esqueleto de WP0, Entregas.ligar devuelve el objeto sin marca: se avisa
            // aparte y no cuenta como fallo de Ligado.
            if (dada != null && ligado(dada)) h.igual("Entregas.ligar pone el uuid del dueno", u, dueno(dada));
            else h.ok("Entregas.ligar aun no liga (esqueleto de WP1)", true);
        }

        List<String> mercado = mercado();
        for (String raiz : List.of("ah", "pa", "trade")) {
            h.ok("/" + raiz + " es de mercado", mercado.contains(raiz));
            h.ok("/" + raiz + " bloqueada con un ligado en la mano",
                    mercado.contains(Sellos.raiz("/" + raiz + " sell 100")) && contieneLigado(espada));
        }
        h.ok("/spawn no es de mercado", !mercado.contains("spawn"));
        h.ok("sin ligado en la mano no se bloquea nada", !contieneLigado(new ItemStack(Material.DIAMOND)));

        ItemStack caja = new ItemStack(Material.SHULKER_BOX);
        if (caja.getItemMeta() instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox sb) {
            sb.getInventory().addItem(ligar(new ItemStack(Material.DIAMOND_HELMET), u));
            bsm.setBlockState(sb);
            caja.setItemMeta(bsm);
            h.ok("una shulker con un ligado dentro cuenta como ligado", contieneLigado(caja));
            h.igual("la shulker es ajena para otro", u, ajeno(caja, otro));
            h.igual("y no para su dueno", null, ajeno(caja, u));
        } else {
            h.ok("no se pudo montar una shulker con contenido", false);
        }
        ItemStack bolsa = new ItemStack(Material.BUNDLE);
        if (bolsa.getItemMeta() instanceof BundleMeta bm) {
            bm.addItem(ligar(new ItemStack(Material.FEATHER), u));
            bolsa.setItemMeta(bm);
            h.ok("una bolsa con un ligado dentro cuenta como ligado", contieneLigado(bolsa));
        }

        Object jugador = falso(Player.class);
        h.ok("ShulkerBox permitido fuera", permitido(InventoryType.SHULKER_BOX, falso(ShulkerBox.class), null, false));
        h.ok("ShulkerBox rechazado dentro", !permitido(InventoryType.SHULKER_BOX, falso(ShulkerBox.class), null, true));
        h.ok("Chest permitido", permitido(InventoryType.CHEST, falso(Chest.class), null, false));
        h.ok("DoubleChest permitido", permitido(InventoryType.CHEST, new DoubleChest(null), null, true));
        h.ok("Barrel permitido", permitido(InventoryType.BARREL, falso(Barrel.class), null, false));
        h.ok("su inventario permitido", permitido(InventoryType.CRAFTING, jugador, (HumanEntity) jugador, false));
        h.ok("su cofre ender (holder: el) permitido", permitido(InventoryType.ENDER_CHEST, jugador, (HumanEntity) jugador, false));
        h.ok("el cofre ender de otro rechazado",
                !permitido(InventoryType.ENDER_CHEST, falso(Player.class), (HumanEntity) jugador, false));
        h.ok("menu de otro plugin (/ah, /trade) rechazado",
                !permitido(InventoryType.CHEST, falso(InventoryHolder.class), null, false));
        h.ok("menu de plugin sin holder rechazado", !permitido(InventoryType.CHEST, null, null, false));
        h.ok("menu de LethalWorld permitido", permitido(InventoryType.CHEST, new MenuPropio(), null, false));
        h.ok("Hopper rechazado", !permitido(InventoryType.HOPPER, falso(Hopper.class), null, false));
        h.ok("aldeano (MERCHANT) rechazado", !permitido(InventoryType.MERCHANT, null, null, false));
        h.ok("yunque vanilla permitido (reparar lo tuyo)", permitido(InventoryType.ANVIL, null, null, false));
        h.ok("yunque de un plugin rechazado", !permitido(InventoryType.ANVIL, falso(InventoryHolder.class), null, false));
        return h.lineas();
    }

    /** Un holder propio (su paquete empieza por net.ederus.calamity.). */
    private static final class MenuPropio implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    /**
     * Un holder de mentira del tipo pedido, solo para que instanceof conteste. Su clase es un
     * proxy de la JVM (fuera de net.ederus.calamity): sirve tambien de "menu de otro plugin".
     */
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
