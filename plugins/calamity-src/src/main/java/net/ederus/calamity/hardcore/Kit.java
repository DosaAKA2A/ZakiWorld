package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Allay;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * M15 · Kit de Expedicion (DIS M15, PLAN sec. 7.2): perderlo todo es la primera causa de
 * abandono en un modo asi, y el kit es lo justo para volver a entrar despues de morir.
 *
 * "calamity open <player> kit" (desde un NPC), fuera de Calamity, una vez cada kit.cada-horas (20) y solo sin armadura
 * puesta: hierro completo, espada de piedra, 8 panes y un Frasco de kit.frasco-tragos (1).
 * Todo lleva lethal_world:prestado (BYTE 1) y la linea "Prestado. Se deshace al salir de
 * Calamity."; el Censo no lo cuenta y el Eco solo lo copia.
 *
 * Lo prestado no puede convertirse en hierro de verdad (exploit X39, "grifo de hierro"):
 *   - se deshace en Hardcore.sacar (borrarPrestado), al cambiar de un mundo hardcore a uno
 *     que no lo es, y si aparece fuera en el join o al abrir un inventario;
 *   - no entra en ningun inventario que no sea el del jugador (horno, piedra de afilar,
 *     yunque, mesa, cofres, cajas, cofre de ender...), ni en un saco, ni se tira fuera, ni
 *     se cuelga en un marco, un soporte, una vasija o un allay.
 *
 * Recien pedido, el kit tiene que poder llevarse FUERA hasta la puerta: kit-fuera.<uuid>
 * (millis) dice que ese jugador lleva un kit sin estrenar. Vale hasta que entra (o hasta
 * cada-horas, por si nunca entra); mientras, el join y la apertura de inventarios no lo borran.
 */
final class Kit implements Listener {

    private static final String LINEA = "Objeto prestado: se deshace al salir de Calamity.";

    private final Hardcore hc;

    Kit(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Subcomandos.jugador().registrar("kit", "Kit de Expedición: fuera de Calamity, sin armadura, cada 20 h",
                null, (quien, args) -> {
                    if (quien instanceof Player p) pedir(p);
                    else quien.sendMessage(ComandoCalamity.mensaje("Solo se puede usar dentro del juego."));
                }, null);
        Autotest.registrar("kit", this::autotest);
    }

    boolean activo() {
        return hc.cfg().getBoolean("kit.activo", false);
    }

    double cadaHoras() {
        return Math.max(0, hc.cfg().getDouble("kit.cada-horas", 20));
    }

    // ------------------------------------------------------------------ nucleo

    /** null si puede pedirlo; si no, "armadura" (P-K05) o "espera" (P-K04). */
    static String motivo(boolean conArmadura, boolean soloSinArmadura, long ultimo, long ahora, double cadaHoras) {
        if (soloSinArmadura && conArmadura) return "armadura";
        if (ultimo > 0 && ahora - ultimo < (long) (cadaHoras * 3_600_000L)) return "espera";
        return null;
    }

    /** Horas enteras que faltan (hacia arriba: "vuelve en 1 h" con 10 minutos por delante). */
    static long horasQueFaltan(long ultimo, long ahora, double cadaHoras) {
        long queda = ultimo + (long) (cadaHoras * 3_600_000L) - ahora;
        return queda <= 0 ? 0 : (queda + 3_599_999L) / 3_600_000L;
    }

    static boolean esPrestado(ItemStack it) {
        return Marcas.tiene(it, Marcas.PRESTADO);
    }

    /** Le pone la marca y la linea de lore (sin cursiva). Devuelve el mismo objeto. */
    static ItemStack prestar(ItemStack it) {
        if (it == null || it.getType().isAir()) return it;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        meta.getPersistentDataContainer().set(Marcas.PRESTADO, PersistentDataType.BYTE, (byte) 1);
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.text(LINEA, Paleta.TENUE).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /** Lo que se presta, ya marcado. frasco null = sin frasco (autotest sin ItemsCalamity). */
    List<ItemStack> piezas() {
        List<ItemStack> out = new ArrayList<>();
        for (Material m : List.of(Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS,
                Material.IRON_BOOTS, Material.STONE_SWORD)) {
            out.add(prestar(new ItemStack(m)));
        }
        out.add(prestar(new ItemStack(Material.BREAD, 8)));
        int tragos = tragos();
        if (tragos > 0) out.add(prestar(hc.items().frasco(tragos)));
        return out;
    }

    /** Vacia los prestados de un array (lo cambia en el sitio). Cuantas unidades quito. */
    static int quitar(ItemStack[] contenido) {
        int n = 0;
        for (int i = 0; i < contenido.length; i++) {
            if (esPrestado(contenido[i])) {
                n += contenido[i].getAmount();
                contenido[i] = null;
            }
        }
        return n;
    }

    private static boolean conArmadura(PlayerInventory inv) {
        for (ItemStack it : inv.getArmorContents()) if (it != null && !it.getType().isAir()) return true;
        return false;
    }

    private static boolean esSaco(ItemStack it) {
        return it != null && it.getType().name().endsWith("BUNDLE");
    }

    // ------------------------------------------------------------------ pedir

    /** Como esta el kit para p ahora: el motivo de motivo() o "dentro" (en Calamity); y las horas si espera. */
    record Estado(String motivo, long horas) {
    }

    /** Lo que mira el boton del Altar (MenuAltar.tarjetaKit): lo mismo que pedir(), sin dar nada. */
    Estado estado(Player p) {
        if (hc.esHardcore(p)) return new Estado("dentro", 0);
        long ahora = System.currentTimeMillis();
        long ultimo = hc.datos().getLong("kit." + p.getUniqueId(), 0);
        String no = motivo(conArmadura(p.getInventory()), hc.cfg().getBoolean("kit.solo-sin-armadura", true),
                ultimo, ahora, cadaHoras());
        return new Estado(no, "espera".equals(no) ? horasQueFaltan(ultimo, ahora, cadaHoras()) : 0);
    }

    /** Los tragos del Frasco que se presta (0: sin Frasco), para el boton del Altar. */
    int tragos() {
        return Math.max(0, hc.cfg().getInt("kit.frasco-tragos", 1));
    }

    void pedir(Player p) {
        if (!activo()) {
            p.sendMessage(ComandoCalamity.mensaje("El Kit de Expedición no está disponible ahora mismo."));
            return;
        }
        if (hc.esHardcore(p)) {
            p.sendMessage(ComandoCalamity.mensaje("El kit se pide fuera de Calamity, antes de entrar."));
            return;
        }
        UUID u = p.getUniqueId();
        long ahora = System.currentTimeMillis();
        long ultimo = hc.datos().getLong("kit." + u, 0);
        String no = motivo(conArmadura(p.getInventory()), hc.cfg().getBoolean("kit.solo-sin-armadura", true),
                ultimo, ahora, cadaHoras());
        if ("armadura".equals(no)) {
            p.sendMessage(ComandoCalamity.mensaje("El kit solo se da si no llevas ninguna armadura puesta."));
            return;
        }
        if ("espera".equals(no)) {
            p.sendMessage(ComandoCalamity.mensaje(Component.text("Aún no puedes pedir otro kit. Vuelve en ")
                    .append(Component.text(horasQueFaltan(ultimo, ahora, cadaHoras()) + " h", Paleta.CIFRA))
                    .append(Component.text("."))));
            return;
        }
        // Primero se apunta y se guarda: un fallo a mitad no puede dejar pedirlo otra vez.
        hc.datos().set("kit." + u, ahora);
        hc.datos().set("kit-fuera." + u, ahora);
        hc.guardarYa();

        List<ItemStack> piezas = piezas();
        PlayerInventory inv = p.getInventory();
        boolean suelo = false;
        for (ItemStack it : piezas) {
            // La armadura va puesta (no lleva nada: si no, no habria kit).
            EquipmentSlot hueco = switch (it.getType()) {
                case IRON_HELMET -> EquipmentSlot.HEAD;
                case IRON_CHESTPLATE -> EquipmentSlot.CHEST;
                case IRON_LEGGINGS -> EquipmentSlot.LEGS;
                case IRON_BOOTS -> EquipmentSlot.FEET;
                default -> null;
            };
            ItemStack puesto = hueco == null ? null : inv.getItem(hueco);
            if (hueco != null && (puesto == null || puesto.getType().isAir())) inv.setItem(hueco, it);
            else suelo |= Suelo.dar(hc.plugin(), p, it);
        }
        hc.plugin().bitacora().anotar("kit", "presta", p.getName(), piezas.size() + " piezas");
        p.sendMessage(ComandoCalamity.mensaje("Te prestan un kit para volver a entrar. Se deshace al salir de Calamity."));
        if (suelo) p.sendMessage(ComandoCalamity.mensaje("No te cabía en el inventario: lo tienes a tus pies."));
    }

    // ------------------------------------------------------------------ deshacer

    /** Hardcore.sacar, cambio de mundo hacia fuera y lo que aparezca fuera sin kit estrenado. */
    void borrarPrestado(Player p) {
        if (p == null) return;
        int n = 0;
        PlayerInventory inv = p.getInventory();
        ItemStack[] todo = inv.getContents();
        int q = quitar(todo);
        if (q > 0) {
            inv.setContents(todo);
            n += q;
        }
        if (esPrestado(p.getItemOnCursor())) {
            n += p.getItemOnCursor().getAmount();
            p.setItemOnCursor(null);
        }
        // La rejilla 2x2 de su inventario (o la mesa que tenga abierta): lo que haya dentro
        // volveria al inventario al cerrar.
        Inventory arriba = p.getOpenInventory().getTopInventory();
        if (arriba.getType() == InventoryType.CRAFTING || arriba.getType() == InventoryType.WORKBENCH) {
            ItemStack[] rejilla = arriba.getContents();
            int r = quitar(rejilla);
            if (r > 0) {
                arriba.setContents(rejilla);
                n += r;
            }
        }
        hc.datos().set("kit-fuera." + p.getUniqueId(), null);
        hc.marcarSucio();
        if (n > 0) {
            hc.plugin().bitacora().anotar("kit", "deshace", p.getName(), String.valueOf(n));
            p.updateInventory();
        }
    }

    /** Si lleva un kit recien pedido que aun no ha estrenado (puede estar fuera). */
    private boolean valeFuera(UUID u) {
        long t = hc.datos().getLong("kit-fuera." + u, 0);
        return t > 0 && System.currentTimeMillis() - t < (long) (Math.max(1, cadaHoras()) * 3_600_000L);
    }

    private boolean tienePrestado(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (esPrestado(it)) return true;
        return esPrestado(p.getItemOnCursor());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alCambiarMundo(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        World de = e.getFrom();
        boolean dentroAntes = hc.esHardcore(de), dentroAhora = hc.esHardcore(p);
        if (dentroAntes && !dentroAhora) {
            hc.seguro("kit", () -> borrarPrestado(p));
        } else if (!dentroAntes && dentroAhora && hc.datos().isSet("kit-fuera." + p.getUniqueId())) {
            // Estrenado: a partir de aqui, si sale, se deshace.
            hc.datos().set("kit-fuera." + p.getUniqueId(), null);
            hc.marcarSucio();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alEntrar(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (hc.esHardcore(p) || valeFuera(p.getUniqueId()) || !tienePrestado(p)) return;
        hc.seguro("kit", () -> borrarPrestado(p));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alAbrir(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || hc.esHardcore(p)) return;
        if (valeFuera(p.getUniqueId()) || !tienePrestado(p)) return;
        hc.seguro("kit", () -> borrarPrestado(p));
    }

    // ------------------------------------------------------------------ bloqueos

    /** Si el inventario de arriba es de otro (no la rejilla 2x2 del propio jugador). */
    private static boolean ajeno(Inventory arriba) {
        InventoryType t = arriba.getType();
        return t != InventoryType.CRAFTING && t != InventoryType.PLAYER && t != InventoryType.CREATIVE;
    }

    /**
     * Si ese clic mete en otro sitio algo que cumple "es": en un saco (con ello en el cursor sobre el
     * saco, o el saco sobre ello: saldria dentro) o en un inventario que no es el del jugador (ajeno),
     * con el cursor, con una tecla de numero o la F, o con mayusculas desde abajo.
     *
     * Calamity 1.10: lo comparten lo prestado (alClic) y los pergaminos de los contratos (Pergaminos):
     * las mismas vias cerradas, cada uno con su aviso.
     */
    static boolean meteFuera(InventoryClickEvent e, Predicate<ItemStack> es) {
        ItemStack cursor = e.getCursor();
        ItemStack actual = e.getCurrentItem();
        if ((es.test(cursor) && esSaco(actual)) || (es.test(actual) && esSaco(cursor))) return true;
        Inventory arriba = e.getView().getTopInventory();
        if (!ajeno(arriba)) return false;
        boolean enArriba = e.getRawSlot() >= 0 && e.getRawSlot() < arriba.getSize();
        if (enArriba) {
            // Tecla de numero o F (mano secundaria, boton 40): lo que se trae de la barra.
            ItemStack atajo = e.getHotbarButton() >= 0 ? e.getWhoClicked().getInventory().getItem(e.getHotbarButton()) : null;
            return es.test(cursor) || es.test(atajo);
        }
        return e.isShiftClick() && es.test(actual);
    }

    /** Lo mismo para un arrastre: si deja algo que cumple "es" en alguna casilla de un inventario ajeno. */
    static boolean arrastraFuera(InventoryDragEvent e, Predicate<ItemStack> es) {
        if (!es.test(e.getOldCursor())) return false;
        Inventory arriba = e.getView().getTopInventory();
        if (!ajeno(arriba)) return false;
        for (int s : e.getRawSlots()) if (s < arriba.getSize()) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void alClic(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && meteFuera(e, Kit::esPrestado)) bloquear(e, p);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void alArrastrar(InventoryDragEvent e) {
        if (arrastraFuera(e, Kit::esPrestado)) e.setCancelled(true);
    }

    private void bloquear(InventoryClickEvent e, HumanEntity p) {
        e.setCancelled(true);
        p.sendMessage(ComandoCalamity.mensaje("Los objetos prestados no se pueden guardar ni fundir."));
    }

    /** Fuera no se tira: lo cogeria otro. Dentro si (se deshace al salir con quien lo lleve). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void alTirar(PlayerDropItemEvent e) {
        if (!esPrestado(e.getItemDrop().getItemStack()) || hc.esHardcore(e.getPlayer())) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void alSoporte(PlayerArmorStandManipulateEvent e) {
        if (esPrestado(e.getPlayerItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void alColgar(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof ItemFrame) && !(e.getRightClicked() instanceof Allay)) return;
        ItemStack mano = e.getPlayer().getInventory().getItem(e.getHand());
        if (esPrestado(mano)) e.setCancelled(true);
    }

    /**
     * Vasijas decoradas (guardan un objeto con clic derecho) y, en MONITOR, el Frasco
     * prestado: Hardcore.beber lo cambia por uno nuevo sin la marca; aqui se la vuelve a poner
     * (si no, el frasco vacio del kit seria un Frasco de verdad que se recarga en el Altar).
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void alVasija(PlayerInteractEvent e) {
        if (!e.getAction().isRightClick()) return;
        Block b = e.getClickedBlock();
        if (b != null && b.getType() == Material.DECORATED_POT && esPrestado(e.getItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alBeber(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !e.getAction().isRightClick()) return;
        ItemStack antes = e.getItem();
        if (!esPrestado(antes) || !hc.items().esFrasco(antes)) return;
        PlayerInventory inv = e.getPlayer().getInventory();
        ItemStack ahora = inv.getItemInMainHand();
        // Si nadie bebio (cancelado antes de Hardcore), en la mano sigue el prestado y no se toca.
        if (hc.items().esFrasco(ahora) && !esPrestado(ahora)) {
            inv.setItemInMainHand(prestar(ahora.clone()));
        }
    }

    void parar() {
        HandlerList.unregisterAll(this);
    }

    // ------------------------------------------------------------------ autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        long ahora = 1_800_000_000_000L;
        long hora = 3_600_000L;
        h.igual("con armadura puesta no", "armadura", motivo(true, true, 0, ahora, 20));
        h.igual("sin armadura y sin kit antes si", null, motivo(false, true, 0, ahora, 20));
        h.igual("con armadura y solo-sin-armadura apagado si", null, motivo(true, false, 0, ahora, 20));
        h.igual("a las 19 h del anterior no", "espera", motivo(false, true, ahora - 19 * hora, ahora, 20));
        h.igual("a las 20 h del anterior si", null, motivo(false, true, ahora - 20 * hora, ahora, 20));
        h.igual("a las 19 h faltan 1 h", 1L, horasQueFaltan(ahora - 19 * hora, ahora, 20));
        h.igual("a los 10 min faltan 20 h (hacia arriba)", 20L, horasQueFaltan(ahora - 10 * 60_000L, ahora, 20));

        List<ItemStack> kit = piezas();
        int tragos = Math.max(0, hc.cfg().getInt("kit.frasco-tragos", 1));
        h.igual("piezas del kit", tragos > 0 ? 7 : 6, kit.size());
        boolean todas = true, lore = true, sinCursiva = true;
        for (ItemStack it : kit) {
            if (!esPrestado(it)) todas = false;
            ItemMeta m = it.getItemMeta();
            List<Component> l = m == null ? null : m.lore();
            if (l == null || l.isEmpty()) {
                lore = false;
                continue;
            }
            var plano = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
            if (!LINEA.equals(plano.serialize(l.get(l.size() - 1)))) lore = false;
            // Las lineas en blanco (el hueco del lore del Frasco) no se ven: da igual su cursiva.
            for (Component c : l) {
                if (!plano.serialize(c).isEmpty() && c.decoration(TextDecoration.ITALIC) != TextDecoration.State.FALSE) sinCursiva = false;
            }
        }
        h.ok("todo el kit lleva lethal_world:prestado", todas);
        h.ok("todo el kit lleva la linea de prestado", lore);
        h.ok("lore sin cursiva", sinCursiva);
        h.igual("8 panes", 8, kit.stream().filter(i -> i.getType() == Material.BREAD).mapToInt(ItemStack::getAmount).sum());
        if (tragos > 0) {
            ItemStack frasco = kit.get(kit.size() - 1);
            h.igual("frasco con " + tragos + " trago(s)", tragos, hc.items().tragos(frasco));
        }

        ItemStack[] inv = {new ItemStack(Material.IRON_INGOT, 5), kit.get(0).clone(), null, kit.get(5).clone()};
        h.igual("quitar se lleva lo prestado (1 casco + 8 panes)", 9, quitar(inv));
        h.ok("quitar deja lo que no es prestado", inv[0] != null && inv[0].getAmount() == 5 && inv[1] == null && inv[3] == null);
        h.ok("un saco se reconoce", esSaco(new ItemStack(Material.BUNDLE)));
        h.ok("el kit se abre desde un NPC (open <player> kit)", Subcomandos.jugador().nombres(null).contains("kit"));
        return h.lineas();
    }
}
