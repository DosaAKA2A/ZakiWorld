package net.ederus.edm.tienda;

import net.ederus.edm.comun.Estilo;
import net.ederus.edm.minas.api.MineDropsEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /autosell: lo que suelta un bloque que pica el jugador se vende solo, al precio
 * de la tienda y con SU devaluacion, sin pasar por el inventario. Lo que la tienda
 * no compra cae como siempre.
 *
 * Las ventas se juntan y se cobran cada pocos segundos: una venta por bloque
 * llenaria el registro de lineas de una piedra y la barra de accion parpadearia.
 * Lo pendiente se cobra tambien al salir y al apagar, asi nunca se pierde nada.
 *
 * Es ventaja VIP: el permiso sale de autoventa.permiso (lo tiene el grupo aeris
 * y los de encima). Dura lo que dura la conexion: al salir se apaga y hay que
 * volver a encenderlo al entrar.
 *
 * Lo picado puede salir por cuatro sitios, y se vende salga por donde salga:
 *  - el drop normal (BlockDropItemEvent);
 *  - las minas de EDM, que lo meten directo en la mochila (MineDropsEvent);
 *  - lo que otro plugin mete en la mochila mientras se rompe el bloque, como la
 *    Telepatia de AdvancedEnchantments: se mira la mochila antes y despues;
 *  - lo que otro plugin suelta el mismo dentro del bloque (la Fundicion sin
 *    Telepatia, OreRegenerator): el bloque se apunta un par de ticks.
 * Los cofres y demas contenedores no: lo que lleven dentro no se vende solo.
 */
final class Autoventa implements Listener, CommandExecutor {

    private final TiendaPlugin modulo;
    private final Set<UUID> encendidos = new HashSet<>();
    private final Map<UUID, Map<Material, Integer>> pendiente = new HashMap<>();
    /** Bloques recien picados con la autoventa encendida: posicion -> quien y en que tick. */
    private final Map<Long, Picado> picados = new HashMap<>();
    /** Mientras se devuelve lo que no se pudo cobrar, lo que cae no se vuelve a vender. */
    private boolean devolviendo;

    private record Picado(UUID jugador, UUID mundo, int tick) {}

    /** Lo vendible que llevaba cada uno en la mochila justo antes de romper el bloque. Es
     * una pila: los encantamientos de area (explosivo, veinminer) rompen bloques dentro de
     * la rotura del primero, y cada rotura tiene que cerrar con su propia foto. */
    private final Map<UUID, Deque<Map<Material, Integer>>> antes = new HashMap<>();
    private static final Map<Material, Integer> SIN_FOTO = Map.of();

    /* Ticks que se espera a que el plugin de la mina suelte lo del bloque. */
    private static final int MARGEN_TICKS = 2;
    private BukkitTask tarea;

    private boolean activo = true;
    private String permiso = "ederus.tienda.autosell";
    private long cadaTicks = 60L;
    private List<String> mundosBloqueados = List.of();
    private String msgActivado, msgDesactivado, msgSinPermiso, msgResumen, msgApagado;

    Autoventa(TiendaPlugin modulo) {
        this.modulo = modulo;
    }

    void configurar(ConfigurationSection s) {
        activo = s == null || s.getBoolean("activo", true);
        permiso = s == null ? "ederus.tienda.autosell" : s.getString("permiso", "ederus.tienda.autosell");
        long segundos = s == null ? 3 : Math.max(1, s.getLong("resumen-segundos", 3));
        mundosBloqueados = s == null ? List.of() : s.getStringList("mundos-bloqueados");
        msgActivado = texto(s, "activado", "&#FDFF66⚡ &fAutoventa &aactivada&7: lo que piques se vende solo.");
        msgDesactivado = texto(s, "desactivado", "&#FDFF66⚡ &fAutoventa &cdesactivada&7.");
        msgSinPermiso = texto(s, "sin-permiso", "&cEl /autosell es una ventaja VIP, desde el rango Aeris.");
        msgResumen = texto(s, "resumen", "&#FDFF66⚡ &7Autoventa: &a+%total% &8• &7%cantidad% objetos");
        msgApagado = texto(s, "apagado", "&cEl /autosell está desactivado ahora mismo.");

        if (cadaTicks != segundos * 20L || tarea == null) {
            cadaTicks = segundos * 20L;
            if (tarea != null) tarea.cancel();
            tarea = modulo.core().getServer().getScheduler()
                    .runTaskTimer(modulo.core(), this::cobrarTodo, cadaTicks, cadaTicks);
        }
    }

    private static String texto(ConfigurationSection s, String k, String porDefecto) {
        return s == null ? porDefecto : s.getString("mensajes." + k, porDefecto);
    }

    void apagar() {
        if (tarea != null) tarea.cancel();
        tarea = null;
        cobrarTodo();
    }

    private boolean encendida(Player p) {
        return encendidos.contains(p.getUniqueId());
    }

    // ------------------------------------------------------------------ comando

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        if (!(quien instanceof Player p)) { quien.sendMessage("Solo desde el juego."); return true; }
        if (!activo) { p.sendMessage(Estilo.legado(msgApagado)); return true; }
        if (!p.hasPermission(permiso)) { p.sendMessage(Estilo.legado(msgSinPermiso)); return true; }
        boolean ahora = !encendida(p);
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("on")) ahora = true;
            else if (args[0].equalsIgnoreCase("off")) ahora = false;
        }
        if (ahora) encendidos.add(p.getUniqueId());
        else { encendidos.remove(p.getUniqueId()); cobrar(p); }
        p.sendMessage(Estilo.legado(ahora ? msgActivado : msgDesactivado));
        return true;
    }

    // ------------------------------------------------------------------ eventos

    private boolean vende(Player p) {
        return activo && p.getGameMode() == GameMode.SURVIVAL && encendida(p)
                && !mundosBloqueados.contains(p.getWorld().getName()) && p.hasPermission(permiso);
    }

    private static long clave(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private void apuntar(UUID jugador, Map<Material, Integer> suyo, ItemStack stack) {
        Map<Material, Integer> m = suyo != null ? suyo
                : pendiente.computeIfAbsent(jugador, k -> new EnumMap<>(Material.class));
        m.merge(stack.getType(), stack.getAmount(), Integer::sum);
    }

    private static boolean esContenedor(BlockState estado) {
        return estado instanceof Container;
    }

    /** Lo vendible y limpio de la mochila, por material. */
    private static Map<Material, Integer> vendibles(Motor motor, Inventory inv) {
        Map<Material, Integer> m = new EnumMap<>(Material.class);
        for (ItemStack s : inv.getStorageContents()) {
            if (s != null && motor.seVendeSuelto(s)) m.merge(s.getType(), s.getAmount(), Integer::sum);
        }
        return m;
    }

    /* LOWEST: la foto de la mochila, antes de que nadie toque nada. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void antesDePicar(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Motor motor = modulo.motor();
        boolean mira = motor != null && vende(p) && !esContenedor(e.getBlock().getState(false));
        antes.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>())
                .push(mira ? vendibles(motor, p.getInventory()) : SIN_FOTO);
    }

    /* MONITOR: lo que haya entrado en la mochila durante la rotura (Telepatia) se vende,
     * y el bloque se apunta por si alguien suelta ahi lo picado en los ticks siguientes. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void alPicar(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Deque<Map<Material, Integer>> pila = antes.get(p.getUniqueId());
        Map<Material, Integer> foto = pila == null ? null : pila.poll();
        if (pila != null && pila.isEmpty()) antes.remove(p.getUniqueId());
        if (foto == null || foto == SIN_FOTO || e.isCancelled() || !vende(p)) return;
        Motor motor = modulo.motor();
        if (motor == null) return;

        Map<Material, Integer> ahora = vendibles(motor, p.getInventory());
        for (Map.Entry<Material, Integer> en : ahora.entrySet()) {
            int nuevas = en.getValue() - foto.getOrDefault(en.getKey(), 0);
            if (nuevas <= 0) continue;
            int quitadas = Motor.quitarLimpios(p.getInventory(), en.getKey(), nuevas);
            if (quitadas > 0) apuntar(p.getUniqueId(), null, new ItemStack(en.getKey(), quitadas));
        }

        var b = e.getBlock();
        picados.put(clave(b.getX(), b.getY(), b.getZ()),
                new Picado(p.getUniqueId(), b.getWorld().getUID(), Bukkit.getCurrentTick()));
    }

    /* Las minas de EDM entregan en la mochila. HIGHEST para vender lo que dejen los
     * eventos de mina (doble mineral...), que cambian la lista antes. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void alMinar(MineDropsEvent e) {
        Player p = e.getPlayer();
        if (!vende(p)) return;
        Motor motor = modulo.motor();
        if (motor == null) return;
        for (Iterator<ItemStack> it = e.getDrops().iterator(); it.hasNext(); ) {
            ItemStack stack = it.next();
            if (stack == null || !motor.seVendeSuelto(stack)) continue;
            apuntar(p.getUniqueId(), null, stack);
            it.remove();
        }
    }

    /* Lo que otro plugin suelta por su cuenta dentro del bloque picado. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alAparecer(ItemSpawnEvent e) {
        if (devolviendo || picados.isEmpty()) return;
        Location l = e.getLocation();
        Picado pic = picados.get(clave(l.getBlockX(), l.getBlockY(), l.getBlockZ()));
        if (pic == null || !pic.mundo().equals(l.getWorld().getUID())) return;
        if (Bukkit.getCurrentTick() - pic.tick() > MARGEN_TICKS) return;
        Player p = Bukkit.getPlayer(pic.jugador());
        if (p == null || !vende(p)) return;
        Motor motor = modulo.motor();
        ItemStack stack = e.getEntity().getItemStack();
        if (motor == null || !motor.seVendeSuelto(stack)) return;
        apuntar(p.getUniqueId(), null, stack);
        e.setCancelled(true);
    }

    /* HIGH y no MONITOR: hay que quitar los items antes de que aparezcan en el suelo. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alSoltar(BlockDropItemEvent e) {
        Player p = e.getPlayer();
        if (!vende(p) || esContenedor(e.getBlockState())) return;
        Motor motor = modulo.motor();
        if (motor == null) return;

        Map<Material, Integer> suyo = null;
        for (Iterator<Item> it = e.getItems().iterator(); it.hasNext(); ) {
            ItemStack stack = it.next().getItemStack();
            if (!motor.seVendeSuelto(stack)) continue;
            if (suyo == null) suyo = pendiente.computeIfAbsent(p.getUniqueId(), k -> new EnumMap<>(Material.class));
            apuntar(p.getUniqueId(), suyo, stack);
            it.remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alSalir(PlayerQuitEvent e) {
        cobrar(e.getPlayer());
        encendidos.remove(e.getPlayer().getUniqueId());
        picados.values().removeIf(pic -> pic.jugador().equals(e.getPlayer().getUniqueId()));
        antes.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ cobro

    private void cobrarTodo() {
        int ahora = Bukkit.getCurrentTick();
        picados.values().removeIf(pic -> ahora - pic.tick() > MARGEN_TICKS);
        for (UUID id : List.copyOf(pendiente.keySet())) {
            Player p = modulo.core().getServer().getPlayer(id);
            if (p != null) cobrar(p);
            else pendiente.remove(id);
        }
    }

    private void cobrar(Player p) {
        Map<Material, Integer> suyo = pendiente.remove(p.getUniqueId());
        if (suyo == null || suyo.isEmpty()) return;
        Motor motor = modulo.motor();
        double total = 0;
        int piezas = 0;
        for (Map.Entry<Material, Integer> en : suyo.entrySet()) {
            int cantidad = en.getValue();
            int vendidas = 0;
            if (motor != null) {
                Motor.Resultado r = motor.venderSuelto(p, en.getKey(), cantidad);
                if (r.ok()) { vendidas = r.cantidad(); total += r.total(); piezas += vendidas; }
            }
            /* Lo que no se pudo cobrar (banco caido, tope) es del jugador: al
             * inventario, y lo que no quepa a sus pies. */
            if (vendidas < cantidad) {
                devolviendo = true;
                try { devolver(p, en.getKey(), cantidad - vendidas); }
                finally { devolviendo = false; }
            }
        }
        if (piezas > 0 && !msgResumen.isEmpty()) {
            p.sendActionBar(Estilo.legado(msgResumen
                    .replace("%total%", Estilo.dinero(total))
                    .replace("%cantidad%", String.valueOf(piezas))));
        }
    }

    private static void devolver(Player p, Material material, int cantidad) {
        while (cantidad > 0) {
            int n = Math.min(cantidad, material.getMaxStackSize());
            cantidad -= n;
            for (ItemStack sobra : p.getInventory().addItem(new ItemStack(material, n)).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), sobra);
            }
        }
    }
}
