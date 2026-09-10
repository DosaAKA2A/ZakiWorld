package net.ederus.edm.dungeonloot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Vault;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import net.ederus.edm.Module;
import net.ederus.edm.anomaly.drops.DropEntry;
import net.ederus.edm.anomaly.drops.DropTable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Las bovedas, en el mundo: colocarlas, abrirlas y protegerlas.
 *
 * El bloque es la boveda de verdad del Trial Chamber, asi que la animacion, la
 * luz y los sonidos son los del juego. Lo que NO es del juego es la logica: cada
 * interaccion vanilla se cancela y el estado del bloque lo movemos nosotros.
 *
 * Eso resuelve de una vez las tres cosas que hacian falta:
 *   - la llave se comprueba por su marca, no por su material: una llave vanilla
 *     del Trial Chamber, o la de otra caja, no abre nada;
 *   - el botin sale de la tabla de EDM y no de una tabla de datapack;
 *   - se puede abrir tantas veces como llaves tengas, sin el "un premio por
 *     jugador y para siempre" que trae la boveda de fabrica.
 */
public final class Bovedas implements Listener {

    private final DungeonLootPlugin plugin;
    private final Random random = new Random();

    /** Bovedas a medio abrir. Sin esto, dos clics seguidos cobran dos llaves y abren una. */
    private final Set<String> abriendo = new HashSet<>();

    public Bovedas(DungeonLootPlugin plugin) {
        this.plugin = plugin;
    }

    /* ------------------------------------------------------------- colocarlas */

    @EventHandler(ignoreCancelled = true)
    public void alColocar(BlockPlaceEvent e) {
        ItemStack mano = e.getItemInHand();
        String cajaId = marca(mano, plugin.claveCaja());
        if (cajaId == null) return;

        Caja caja = plugin.registro().caja(cajaId);
        if (caja == null) {
            e.setCancelled(true);
            plugin.di(e.getPlayer(), "huerfana", "Esa bóveda apunta a una caja que ya no existe.");
            return;
        }
        if (!e.getPlayer().hasPermission("ederus.dl.admin")) {
            e.setCancelled(true);
            plugin.di(e.getPlayer(), "sin-permiso-colocar", "No puedes colocar bóvedas.");
            return;
        }

        Block b = e.getBlockPlaced();
        Boveda boveda = plugin.registro().plantar(caja, b.getWorld().getName(),
                b.getX(), b.getY(), b.getZ());
        vestir(b, caja);
        plugin.registro().guardar();

        plugin.di(e.getPlayer(), "plantada", "Bóveda plantada: %caja%  ·  %x% %y% %z%",
                "%caja%", caja.display(), "%x%", String.valueOf(boveda.x()),
                "%y%", String.valueOf(boveda.y()), "%z%", String.valueOf(boveda.z()));
    }

    /** Deja el bloque con el aspecto de la caja: ominosa o comun, y siempre activa. */
    public void vestir(Block b, Caja caja) {
        BlockData data = b.getBlockData();
        if (data instanceof Vault v) {
            v.setOminous(caja.tipo().ominous());
            v.setVaultState(Vault.State.ACTIVE);
            b.setBlockData(v, false);
        }
        mostrarPremio(b, caja);
    }

    /**
     * El objeto que gira dentro de la boveda.
     *
     * Se enseña el UNICO si la caja tiene uno; es su escaparate, y es lo que hace
     * que valga la pena gastar la llave. Si no hay unico, el primero de la lista.
     */
    private void mostrarPremio(Block b, Caja caja) {
        ItemStack premio = caja.unico() != null ? caja.unico().item()
                : (caja.tabla().entries().isEmpty() ? null : caja.tabla().entries().get(0).item());
        BlockState st = b.getState();
        if (premio != null && st instanceof org.bukkit.block.Vault tile) {
            tile.setDisplayedItem(premio.clone());
            tile.update(true, false);
        }
    }

    /* ---------------------------------------------------------------- abrirlas */

    @EventHandler(priority = EventPriority.HIGH)
    public void alTocar(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Boveda boveda = plugin.registro().bovedaEn(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        if (boveda == null) return;

        // Solo se corta el clic derecho, que es el que la boveda vanilla atiende.
        // Cancelar tambien el izquierdo dejaria el bloque imposible de picar y un
        // admin no podria retirarlo nunca.
        if (!e.getAction().name().startsWith("RIGHT_CLICK")) return;
        e.setCancelled(true);

        Caja caja = plugin.registro().caja(boveda.cajaId());
        if (caja == null) return;
        abrir(e.getPlayer(), b, boveda, caja);
    }

    /**
     * La boveda vanilla se enciende y se apaga sola segun quien pase cerca, y esa
     * rutina pelearia con la nuestra a mitad de una apertura. Aqui se le corta.
     */
    @EventHandler(ignoreCancelled = true)
    public void alCambiarEstado(io.papermc.paper.event.block.VaultChangeStateEvent e) {
        Block b = e.getBlock();
        if (plugin.registro().bovedaEn(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()) != null) {
            e.setCancelled(true);
        }
    }

    private void abrir(Player quien, Block b, Boveda boveda, Caja caja) {
        World w = b.getWorld();
        Location centro = b.getLocation().add(0.5, 0.5, 0.5);

        if (abriendo.contains(boveda.clave())) return;

        if (!caja.lista()) {
            w.playSound(centro, Sound.BLOCK_VAULT_INSERT_ITEM_FAIL, 1f, 1f);
            plugin.di(quien, "sin-botin", "Esta bóveda no tiene botín configurado todavía.");
            return;
        }

        ItemStack mano = quien.getInventory().getItemInMainHand();
        String suya = marca(mano, plugin.claveLlave());
        if (suya == null || !suya.equals(caja.id())) {
            w.playSound(centro, Sound.BLOCK_VAULT_INSERT_ITEM_FAIL, 1f, 1f);
            plugin.di(quien, "sin-llave", "Necesitas la llave de %caja% en la mano.",
                    "%caja%", caja.display());
            return;
        }

        // Se cobra ANTES de soltar nada. Si algo fallara despues, el jugador ha
        // perdido una llave; al reves habria una boveda que paga sin cobrar.
        mano.setAmount(mano.getAmount() - 1);
        quien.getInventory().setItemInMainHand(mano.getAmount() <= 0 ? null : mano);

        abriendo.add(boveda.clave());
        boveda.sumarApertura();
        estado(b, Vault.State.UNLOCKING);
        w.playSound(centro, Sound.BLOCK_VAULT_INSERT_ITEM, 1f, 1f);

        List<ItemStack> premio = tirar(caja);

        plugin.core().getServer().getScheduler().runTaskLater(Module.dueno(plugin), () -> {
            if (!esNuestra(b, boveda)) {
                abriendo.remove(boveda.clave());
                return;
            }
            estado(b, Vault.State.EJECTING);
            w.playSound(centro, Sound.BLOCK_VAULT_OPEN_SHUTTER, 1f, 1f);
            soltar(quien, b, caja, premio, 0);
        }, 18L);
    }

    /** Suelta el botin de uno en uno, con su sonido, como hace la boveda de verdad. */
    private void soltar(Player quien, Block b, Caja caja, List<ItemStack> premio, int i) {
        World w = b.getWorld();
        Location centro = b.getLocation().add(0.5, 1.1, 0.5);
        Boveda boveda = plugin.registro().bovedaEn(w.getName(), b.getX(), b.getY(), b.getZ());
        if (boveda == null) {
            // La quitaron a mitad de la apertura. Se libera el cerrojo o esa
            // coordenada se queda marcada como ocupada hasta el reinicio.
            abriendo.remove(Boveda.clave(w.getName(), b.getX(), b.getY(), b.getZ()));
            return;
        }

        if (i >= premio.size()) {
            plugin.core().getServer().getScheduler().runTaskLater(Module.dueno(plugin), () -> {
                if (esNuestra(b, boveda)) {
                    estado(b, Vault.State.ACTIVE);
                    w.playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_VAULT_CLOSE_SHUTTER, 1f, 1f);
                }
                abriendo.remove(boveda.clave());
                plugin.registro().guardar();
            }, 10L);
            return;
        }

        ItemStack item = premio.get(i);
        Item tirado = w.dropItem(centro, item);
        tirado.setVelocity(new Vector((random.nextDouble() - 0.5) * 0.18, 0.22,
                (random.nextDouble() - 0.5) * 0.18));
        tirado.setPickupDelay(10);
        w.playSound(centro, Sound.BLOCK_VAULT_EJECT_ITEM, 1f, 1f);

        if (caja.unico() != null && item.isSimilar(caja.unico().item())) {
            anunciarUnico(quien, caja, item);
        }

        plugin.core().getServer().getScheduler().runTaskLater(Module.dueno(plugin),
                () -> soltar(quien, b, caja, premio, i + 1), 6L);
    }

    /** El unico no se saca en silencio: lo ve el servidor entero. */
    private void anunciarUnico(Player quien, Caja caja, ItemStack item) {
        String objeto = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(DropTable.nameOf(item));
        Component linea = plugin.texto("unico",
                "%jugador% abrió %caja% y sacó %objeto%",
                "%jugador%", quien.getName(), "%caja%", caja.display(), "%objeto%", objeto);
        for (Player p : plugin.core().getServer().getOnlinePlayers()) {
            p.sendMessage(linea);
        }
    }

    /**
     * Lo que sale en una apertura.
     *
     * Salen SIEMPRE tantos objetos como diga 'tiradas', sorteados con la
     * probabilidad de cada uno como peso: una caja que no da nada no es una caja.
     * El unico va aparte y con su propia probabilidad, que es lo que lo hace unico.
     */
    private List<ItemStack> tirar(Caja caja) {
        List<ItemStack> out = new ArrayList<>();
        List<DropEntry> bolsa = new ArrayList<>(caja.tabla().entries());

        for (int n = 0; n < caja.tiradas() && !bolsa.isEmpty(); n++) {
            double total = 0;
            for (DropEntry e : bolsa) total += Math.max(0.01, e.chance());
            double corte = random.nextDouble() * total;
            DropEntry elegido = bolsa.get(bolsa.size() - 1);
            for (DropEntry e : bolsa) {
                corte -= Math.max(0.01, e.chance());
                if (corte <= 0) {
                    elegido = e;
                    break;
                }
            }
            out.add(copiaCon(elegido));
            // Fuera de la bolsa: una caja de tres tiradas que saca tres veces lo
            // mismo se siente rota aunque el sorteo sea correcto.
            if (bolsa.size() > 1) bolsa.remove(elegido);
        }

        DropEntry unico = caja.unico();
        if (unico != null && random.nextDouble() * 100 < unico.chance()) {
            out.add(copiaCon(unico));
        }
        return out;
    }

    private ItemStack copiaCon(DropEntry e) {
        ItemStack item = e.item().clone();
        int min = Math.max(1, e.min());
        int max = Math.max(min, e.max());
        item.setAmount(min + (max > min ? random.nextInt(max - min + 1) : 0));
        return item;
    }

    /* ------------------------------------------------------------- protegerlas */

    @EventHandler(ignoreCancelled = true)
    public void alRomper(BlockBreakEvent e) {
        Block b = e.getBlock();
        Boveda boveda = plugin.registro().bovedaEn(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        if (boveda == null) return;

        if (!e.getPlayer().hasPermission("ederus.dl.admin")) {
            e.setCancelled(true);
            plugin.di(e.getPlayer(), "sin-permiso-romper", "Esta bóveda no se puede romper.");
            return;
        }
        Caja caja = plugin.registro().caja(boveda.cajaId());
        plugin.registro().quitar(boveda);
        plugin.registro().guardar();
        e.setDropItems(false);
        if (caja != null) {
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5),
                    caja.bloque(plugin.claveCaja(), 1));
        }
        plugin.di(e.getPlayer(), "retirada", "Bóveda retirada. El bloque vuelve a tu inventario.");
    }

    /* Ni una explosion se lleva una boveda por delante: son parte del decorado
     * de la mazmorra, no un bloque cualquiera. */
    @EventHandler(ignoreCancelled = true)
    public void alReventar(EntityExplodeEvent e) {
        e.blockList().removeIf(this::esBoveda);
    }

    @EventHandler(ignoreCancelled = true)
    public void alReventarBloque(BlockExplodeEvent e) {
        e.blockList().removeIf(this::esBoveda);
    }

    private boolean esBoveda(Block b) {
        return plugin.registro().bovedaEn(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()) != null;
    }

    /* ------------------------------------------------------------------ utiles */

    private void estado(Block b, Vault.State estado) {
        if (b.getBlockData() instanceof Vault v) {
            v.setVaultState(estado);
            b.setBlockData(v, false);
        }
    }

    /** El bloque sigue siendo la boveda que era: alguien pudo romperla a mitad. */
    private boolean esNuestra(Block b, Boveda boveda) {
        return b.getBlockData() instanceof Vault
                && plugin.registro().bovedaEn(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()) == boveda;
    }

    /** La marca que lleva dentro un objeto nuestro, o null si no es nuestro. */
    private String marca(ItemStack item, org.bukkit.NamespacedKey clave) {
        if (item == null || item.getType().isAir()) return null;
        var meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(clave, PersistentDataType.STRING);
    }
}
