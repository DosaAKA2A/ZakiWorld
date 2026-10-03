package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.edm.comun.Compat;
import net.ederus.calamity.CalamityPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * La vara de los portales: se tocan dos bloques y esa CAJA es el portal.
 *
 * Antes un portal era un punto con radio, que para una puerta construida no vale: una
 * esfera no encaja en un marco. Con dos esquinas se marca la caja de verdad, que es
 * como se marca todo lo demas en Minecraft y como Dosa espera que funcione.
 *
 * La seleccion vive en memoria y no se guarda: es de usar y tirar, como la de
 * WorldEdit. Lo que se guarda es lo que se define con /lw define.
 */
public final class VaraPortales implements Listener {

    private static final TextColor MARCA = TextColor.color(0x9FD6A0);

    /** Las dos esquinas que lleva marcadas cada jugador. */
    public record Seleccion(Location uno, Location dos) {

        public boolean completa() {
            return uno != null && dos != null && uno.getWorld() == dos.getWorld();
        }

        public int volumen() {
            if (!completa()) return 0;
            return (Math.abs(uno.getBlockX() - dos.getBlockX()) + 1)
                    * (Math.abs(uno.getBlockY() - dos.getBlockY()) + 1)
                    * (Math.abs(uno.getBlockZ() - dos.getBlockZ()) + 1);
        }
    }

    private final CalamityPlugin plugin;
    private final NamespacedKey clave;
    private final Map<UUID, Location> uno = new HashMap<>();
    private final Map<UUID, Location> dos = new HashMap<>();

    public VaraPortales(CalamityPlugin plugin) {
        this.plugin = plugin;
        /* Namespace "edm" a mano: la vara que ya tiene Dosa en su inventario lleva
         * esa marca y con otra dejaria de ser una vara. Ver MobsLethal. */
        this.clave = new NamespacedKey("edm", "vara_portal");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** La vara. Es un palo con marca: sin la marca, un palo cualquiera no vale. */
    public ItemStack vara() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Vara de Portales", MARCA)
                    .decoration(TextDecoration.ITALIC, false));
            // Colores de la Paleta: el gris oscuro de antes (DARK_GRAY) no se leia en el globo.
            meta.lore(List.of(
                    Component.text("Golpea un bloque: esquina 1", Paleta.TEXTO)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Clic derecho: esquina 2", Paleta.TEXTO)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Luego: /calamidad define entrada|salida|spawn", Paleta.TENUE)
                            .decoration(TextDecoration.ITALIC, false)));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(clave, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean esVara(ItemStack item) {
        return item != null && item.getItemMeta() != null
                && item.getItemMeta().getPersistentDataContainer()
                        .has(clave, PersistentDataType.BYTE);
    }

    public Seleccion seleccion(Player p) {
        return new Seleccion(uno.get(p.getUniqueId()), dos.get(p.getUniqueId()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onTocar(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !esVara(e.getItem())) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("ederus.mundos")) return;
        e.setCancelled(true);

        boolean primera = e.getAction() == Action.LEFT_CLICK_BLOCK;
        if (!primera && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        (primera ? uno : dos).put(p.getUniqueId(), b.getLocation());

        Seleccion s = seleccion(p);
        p.sendMessage(Component.text("Esquina " + (primera ? "1" : "2") + ": ", MARCA)
                .append(Component.text(b.getX() + " " + b.getY() + " " + b.getZ(), Paleta.CIFRA))
                .append(s.completa()
                        ? Component.text("   ·   " + s.volumen() + " bloques", Paleta.TENUE)
                        : Component.text("   ·   falta la otra esquina", Paleta.TENUE)));
        Compat.soundPlayers(p.getWorld(), b.getLocation(),
                "block.amethyst_block.chime", 0.8f, primera ? 0.9f : 1.3f);
        Compat.spawn(p.getWorld(), Compat.DUST, b.getLocation().add(0.5, 1.1, 0.5), 14,
                0.3, 0.3, 0.3, 0, Compat.dust(0x9FD6A0, 1.4f));
    }

    /**
     * Guarda la seleccion como una de las dos puertas.
     *
     * Devuelve el texto que se le dice al jugador, o null si no hay seleccion: el
     * comando solo tiene que enseñarlo.
     */
    public String definir(Player p, String cual) {
        Seleccion s = seleccion(p);
        if (!s.completa()) {
            return null;
        }
        String base = "hardcore.puertas." + cual + ".";
        World w = s.uno().getWorld();
        plugin.getConfig().set(base + "mundo", w.getKey().toString());
        plugin.getConfig().set(base + "x1", Math.min(s.uno().getBlockX(), s.dos().getBlockX()));
        plugin.getConfig().set(base + "y1", Math.min(s.uno().getBlockY(), s.dos().getBlockY()));
        plugin.getConfig().set(base + "z1", Math.min(s.uno().getBlockZ(), s.dos().getBlockZ()));
        plugin.getConfig().set(base + "x2", Math.max(s.uno().getBlockX(), s.dos().getBlockX()));
        plugin.getConfig().set(base + "y2", Math.max(s.uno().getBlockY(), s.dos().getBlockY()));
        plugin.getConfig().set(base + "z2", Math.max(s.uno().getBlockZ(), s.dos().getBlockZ()));
        plugin.saveConfig();
        return s.volumen() + " bloques en " + w.getKey().getKey();
    }

    /** Si un jugador esta DENTRO de la puerta que se diga. */
    public boolean dentro(Player p, String cual) {
        ConfigurationSection c = plugin.getConfig()
                .getConfigurationSection("hardcore.puertas." + cual);
        if (c == null || !c.isSet("mundo")) return false;
        NamespacedKey k = NamespacedKey.fromString(c.getString("mundo", ""));
        World w = k == null ? null : plugin.getServer().getWorld(k);
        if (w == null || p.getWorld() != w) return false;
        Location l = p.getLocation();
        return l.getBlockX() >= c.getInt("x1") && l.getBlockX() <= c.getInt("x2")
                && l.getBlockY() >= c.getInt("y1") && l.getBlockY() <= c.getInt("y2")
                && l.getBlockZ() >= c.getInt("z1") && l.getBlockZ() <= c.getInt("z2");
    }

    /**
     * Distancia en bloques de un punto a la caja de una puerta: 0 dentro, y
     * Double.MAX_VALUE si la puerta no esta marcada o esta en otro mundo.
     *
     * La usa el Eco (DIS sec. 2.3) para no nacer pegado a las puertas: nacer en la salida
     * seria una emboscada gratis al que vuelve por el.
     */
    public double distancia(Location donde, String puerta) {
        if (donde == null || donde.getWorld() == null) return Double.MAX_VALUE;
        ConfigurationSection c = plugin.getConfig()
                .getConfigurationSection("hardcore.puertas." + puerta);
        if (c == null || !c.isSet("mundo")) return Double.MAX_VALUE;
        NamespacedKey k = NamespacedKey.fromString(c.getString("mundo", ""));
        World w = k == null ? null : plugin.getServer().getWorld(k);
        if (w == null || donde.getWorld() != w) return Double.MAX_VALUE;
        // Distancia a la caja: por eje, lo que se sale de [min, max + 1] (los bloques ocupan su celda entera).
        double dx = fuera(donde.getX(), c.getInt("x1"), c.getInt("x2") + 1);
        double dy = fuera(donde.getY(), c.getInt("y1"), c.getInt("y2") + 1);
        double dz = fuera(donde.getZ(), c.getInt("z1"), c.getInt("z2") + 1);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double fuera(double v, double min, double max) {
        if (v < min) return min - v;
        if (v > max) return v - max;
        return 0;
    }

    /** Descripcion corta de una puerta para el /lw hardcore. */
    public String describir(String cual) {
        ConfigurationSection c = plugin.getConfig()
                .getConfigurationSection("hardcore.puertas." + cual);
        if (c == null || !c.isSet("mundo")) return "sin marcar";
        return c.getString("mundo") + "  " + c.getInt("x1") + " " + c.getInt("y1") + " " + c.getInt("z1")
                + "  a  " + c.getInt("x2") + " " + c.getInt("y2") + " " + c.getInt("z2");
    }
}
