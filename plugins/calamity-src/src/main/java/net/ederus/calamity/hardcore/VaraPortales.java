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
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
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
        // 1.11 · Dosa: particulas en los portales. Cada 5 ticks, solo con alguien a menos de RADIO_VISTA.
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::particulas, 40L, 5L);
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
                    Component.text("Luego: /calamity define entry|exit|spawn", Paleta.TENUE)
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
        // 1.12: la vara la da /calamity wand, asi que la usa quien tiene su permiso.
        if (!p.hasPermission(Subcomandos.PERMISO)) return;
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
        String texto = s.volumen() + " bloques en " + w.getKey().getKey();
        // 1.11 · Dosa: el portal se crea con agua dentro. Solo los dos portales (spawn es la zona, no un portal).
        if (cual.equals("entrada") || cual.equals("salida")) {
            int agua = llenarDeAgua(cual);
            if (agua > 0) texto += ", " + agua + " con agua";
        }
        return texto;
    }

    /** Lo mas grande que se llena de agua de una vez: un portal, no medio spawn por un clic de mas. */
    static final int AGUA_MAXIMO = 4096;

    /**
     * Llena de agua quieta los huecos de aire de la caja de un portal (el marco no se toca). El agua
     * no corre: onFluir para la que sale de un portal, asi que vale un marco abierto por los lados.
     */
    int llenarDeAgua(String cual) {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("hardcore.puertas." + cual);
        if (c == null || !c.isSet("mundo")) return 0;
        NamespacedKey k = NamespacedKey.fromString(c.getString("mundo", ""));
        World w = k == null ? null : plugin.getServer().getWorld(k);
        if (w == null) return 0;
        int x1 = c.getInt("x1"), y1 = c.getInt("y1"), z1 = c.getInt("z1");
        int x2 = c.getInt("x2"), y2 = c.getInt("y2"), z2 = c.getInt("z2");
        long volumen = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        if (volumen > AGUA_MAXIMO) return 0;
        int n = 0;
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (y == y2 && b.getType() == Material.WATER) {
                        cayendo(b);
                        continue;
                    }
                    if (!b.getType().isAir()) continue;
                    if (y == y2) cayendo(b);
                    else b.setType(Material.WATER, false);
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * La fila de arriba va como agua que cae (level 8): el agua quieta se dibuja a 8/9 de bloque y bajo
     * el marco quedaba una franja vacia (Dosa, 2026-10-04); la que cae llena el bloque entero.
     */
    private static void cayendo(Block b) {
        BlockData d = Material.WATER.createBlockData();
        if (d instanceof Levelled l) l.setLevel(8);
        b.setBlockData(d, false);
    }

    private static final int ROJO_PORTAL = 0xD02A26;
    private static final double RADIO_VISTA = 32;
    private int vueltas;

    /** Polvo rojo flotando dentro de cada portal y esporas carmesi. Sobrio y todo en rojo. */
    private void particulas() {
        vueltas++;
        for (String cual : List.of("entrada", "salida")) {
            try {
                ConfigurationSection c = plugin.getConfig().getConfigurationSection("hardcore.puertas." + cual);
                if (c == null || !c.isSet("mundo")) continue;
                NamespacedKey k = NamespacedKey.fromString(c.getString("mundo", ""));
                World w = k == null ? null : plugin.getServer().getWorld(k);
                if (w == null) continue;
                double x1 = c.getInt("x1"), y1 = c.getInt("y1"), z1 = c.getInt("z1");
                double x2 = c.getInt("x2") + 1, y2 = c.getInt("y2") + 1, z2 = c.getInt("z2") + 1;
                Location centro = new Location(w, (x1 + x2) / 2, (y1 + y2) / 2, (z1 + z2) / 2);
                if (!w.isChunkLoaded(centro.getBlockX() >> 4, centro.getBlockZ() >> 4)) continue;
                boolean alguien = false;
                for (Player p : w.getPlayers()) {
                    if (p.getLocation().distanceSquared(centro) <= RADIO_VISTA * RADIO_VISTA) {
                        alguien = true;
                        break;
                    }
                }
                if (!alguien) continue;
                // Dosa (2026-10-04): las dos esquinas marcadas con la vara se quedaban sin agua (habia un bloque al
                // definir que luego se rompio). Cada 2 s, con alguien cerca, se rellena el aire que quede.
                if (vueltas % 8 == 0) llenarDeAgua(cual);
                double ox = (x2 - x1) / 2 * 0.8, oy = (y2 - y1) / 2 * 0.8, oz = (z2 - z1) / 2 * 0.8;
                int n = (int) Math.min(10, Math.max(3, (x2 - x1) * (y2 - y1) * (z2 - z1)));
                Compat.spawn(w, Compat.DUST, centro, n, ox, oy, oz, 0, Compat.dust(ROJO_PORTAL, 1.1f));
                // Dosa: nada de morado (REVERSE_PORTAL lo era). Esporas carmesi, las del bioma del spawn.
                if (vueltas % 2 == 0) {
                    Compat.spawn(w, org.bukkit.Particle.CRIMSON_SPORE, centro, 4, ox, oy, oz, 0);
                }
            } catch (Throwable ignorado) {
                // Una particula que falle no puede tumbar la tarea.
            }
        }
    }

    /** El agua que cae se secaria sin una fuente encima: dentro de un portal se queda como esta. */
    @EventHandler(ignoreCancelled = true)
    public void onNivel(FluidLevelChangeEvent ev) {
        Block b = ev.getBlock();
        if (enCaja(b, "entrada") || enCaja(b, "salida")) ev.setCancelled(true);
    }

    /** El agua de un portal no se derrama: ni fuera de la caja ni dentro (se queda como se puso). */
    @EventHandler(ignoreCancelled = true)
    public void onFluir(BlockFromToEvent ev) {
        Block b = ev.getBlock();
        if (b.getType() != Material.WATER) return;
        if (enCaja(b, "entrada") || enCaja(b, "salida")) ev.setCancelled(true);
    }

    private boolean enCaja(Block b, String cual) {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("hardcore.puertas." + cual);
        if (c == null || !b.getWorld().getKey().toString().equals(c.getString("mundo"))) return false;
        return b.getX() >= c.getInt("x1") && b.getX() <= c.getInt("x2")
                && b.getY() >= c.getInt("y1") && b.getY() <= c.getInt("y2")
                && b.getZ() >= c.getInt("z1") && b.getZ() <= c.getInt("z2");
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

    /** Descripcion corta de una puerta para /calamity status. */
    public String describir(String cual) {
        ConfigurationSection c = plugin.getConfig()
                .getConfigurationSection("hardcore.puertas." + cual);
        if (c == null || !c.isSet("mundo")) return "sin marcar";
        return c.getString("mundo") + "  " + c.getInt("x1") + " " + c.getInt("y1") + " " + c.getInt("z1")
                + "  a  " + c.getInt("x2") + " " + c.getInt("y2") + " " + c.getInt("z2");
    }
}
