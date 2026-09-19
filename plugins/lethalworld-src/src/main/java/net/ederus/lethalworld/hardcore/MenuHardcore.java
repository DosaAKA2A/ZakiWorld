package net.ederus.lethalworld.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.menu.MenuUtil;
import net.ederus.lethalworld.LethalWorldPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * El panel de Calamity: las doce reglas de dificultad, cada una con su interruptor.
 *
 * El resto del plugin va por comando a proposito (Bedrock), pero doce interruptores en
 * un YAML es justo lo que nadie quiere tocar en caliente, y equivocarse ahi deja el
 * mundo entero mal. Un cofre con doce casillas se entiende de un vistazo y se cambia
 * sin salir del juego.
 *
 * Las reglas que son NUMERO (hambre, caida, durabilidad...) se apagan poniendo su
 * valor neutro, no borrandolas: asi el numero que Dosa haya afinado no se pierde al
 * apagar y volver a encender. Ver Regla#apagar.
 */
public final class MenuHardcore implements Listener {

    private static final TextColor VERDE = TextColor.color(0x9FD6A0);

    /** Las casillas de las reglas: tres filas de siete, aireadas y centradas. */
    private static final int[] CASILLAS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};
    /** Todo lo que no es regla, cabecera (4) ni cerrar (49): cristal negro. */
    private static final int[] MARCO = {
            0, 1, 2, 3, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 50, 51, 52, 53};

    /**
     * Una regla del panel.
     *
     * @param clave    la ruta dentro de hardcore, sin el prefijo
     * @param nombre   como se llama en el menu
     * @param icono    material del boton
     * @param encendido valor con el que la regla esta activa
     * @param apagar   valor con el que queda apagada
     * @param ayuda    lo que hace, en una o dos lineas
     */
    private record Regla(String clave, String nombre, Material icono,
                         Object encendido, Object apagar, List<String> ayuda) {

        boolean activa(LethalWorldPlugin plugin) {
            Object v = plugin.getConfig().get("hardcore." + clave, apagar);
            if (encendido instanceof Boolean) return Boolean.TRUE.equals(v);
            return v instanceof Number n && n.doubleValue() > ((Number) apagar).doubleValue();
        }

        String valor(LethalWorldPlugin plugin) {
            Object v = plugin.getConfig().get("hardcore." + clave, apagar);
            if (v instanceof Boolean b) return b ? "sí" : "no";
            if (v instanceof Number n) {
                double d = n.doubleValue();
                return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            return String.valueOf(v);
        }
    }

    /** Las doce que aprobo Dosa, en el orden en que se leen mejor. */
    private static final List<Regla> REGLAS = List.of(
            new Regla("dificultad.sin-camas", "Sin camas", Material.RED_BED, true, false,
                    List.of("No se duerme: ni se salta la noche", "ni se pone punto de reaparición.")),
            new Regla("dificultad.sin-regeneracion", "Sin regeneración", Material.GOLDEN_APPLE, true, false,
                    List.of("La vida no vuelve sola:", "pociones y comida encantada.")),
            new Regla("dificultad.hambre", "Hambre x2", Material.ROTTEN_FLESH, 2.0, 1.0,
                    List.of("Lo que se gasta de hambre se multiplica.", "Comer sigue dando lo mismo.")),
            new Regla("dificultad.veneno-comida-cruda", "Comida cruda", Material.CHICKEN, 8, 0,
                    List.of("Segundos de veneno y hambre", "que deja comer algo crudo.")),
            new Regla("dificultad.dano-caida", "Caída x2", Material.FEATHER, 2.0, 1.0,
                    List.of("El daño de caída se multiplica.")),
            new Regla("dificultad.dano-ahogo", "Ahogo x2", Material.WATER_BUCKET, 2.0, 1.0,
                    List.of("El daño por ahogarse se multiplica.")),
            new Regla("dificultad.penetracion-armadura", "Armadura penetrada", Material.NETHERITE_CHESTPLATE, 0.30, 0.0,
                    List.of("Cuánta armadura ignoran los mobs.", "0.30 = pegan un 30% más.")),
            new Regla("dificultad.durabilidad", "Durabilidad x2", Material.DAMAGED_ANVIL, 2.0, 1.0,
                    List.of("El equipo se gasta más rápido.", "Allí nada dura.")),
            new Regla("dificultad.sin-totem", "Sin tótem", Material.TOTEM_OF_UNDYING, true, false,
                    List.of("El tótem se consume", "y NO te salva.")),
            new Regla("dificultad.mobs-recogen", "Mobs recogen", Material.HOPPER, true, false,
                    List.of("Los mobs recogen lo que se te cae", "y se lo quedan.")),
            new Regla("dificultad.nivel-cada-minutos", "Nivel por minutos", Material.EXPERIENCE_BOTTLE, 5, 0,
                    List.of("Los mobs suben un nivel por cada", "tantos minutos que lleves dentro.")),
            new Regla("dificultad.niebla-de-noche", "Niebla de noche", Material.GRAY_STAINED_GLASS, true, false,
                    List.of("De noche se cierra la vista.", "Es un efecto por jugador, no un bioma.")),
            new Regla("dificultad.fuego-amigo", "Fuego amigo", Material.IRON_SWORD, true, false,
                    List.of("Os podéis matar entre vosotros.")),
            new Regla("minijefes.distancia-maxima", "Minijefes marcan", Material.WITHER_SKELETON_SKULL, 60, 0,
                    List.of("Te siguen aunque cambies de bioma.", "Si te alejas más, reaparecen al lado.")),
            new Regla("dificultad.oleada-de-entrada", "Oleada de entrada", Material.SPAWNER, 5, 0,
                    List.of("Mobs que te reciben al entrar.")),
            new Regla("dificultad.cofres-vacios", "Cofres vacíos", Material.CHEST, true, false,
                    List.of("Los cofres de estructura salen vacíos:", "todo el botín se mata.")),
            new Regla("muerte.cuarentena-minutos", "Cuarentena", Material.CLOCK, 30, 0,
                    List.of("Minutos de espera para volver a entrar", "después de morir dentro.")));

    /** Marca de nuestro inventario: sin esto, cualquier cofre tragaria los clics. */
    private record Marca() implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final LethalWorldPlugin plugin;

    public MenuHardcore(LethalWorldPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void abrir(Player p) {
        Inventory inv = plugin.getServer().createInventory(new Marca(), 54,
                Component.text("Calamity · dificultad", VERDE, TextDecoration.BOLD));
        pintar(inv);
        p.openInventory(inv);
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.creaking_heart.idle", 0.8f, 1.2f);
    }

    private void pintar(Inventory inv) {
        Hardcore hc = plugin.hardcore();
        boolean vivo = hc != null;

        inv.setItem(4, MenuUtil.icon(Material.PALE_OAK_LOG,
                MenuUtil.title("Calamity", VERDE),
                List.of(
                        MenuUtil.field("Reglas", vivo ? "activas" : "apagadas",
                                vivo ? NamedTextColor.GREEN : NamedTextColor.RED),
                        MenuUtil.field("Mundos", vivo ? String.join(", ", hc.mundos()) : "ninguno",
                                MenuUtil.SOFT),
                        MenuUtil.blank(),
                        MenuUtil.line("Cada casilla es una regla."),
                        MenuUtil.line("Los portales y los objetos van"),
                        MenuUtil.line("por /lw hardcore.")), true));

        for (int i = 0; i < REGLAS.size() && i < CASILLAS.length; i++) {
            Regla r = REGLAS.get(i);
            boolean on = r.activa(plugin);
            List<Component> lore = new ArrayList<>();
            for (String l : r.ayuda()) lore.add(MenuUtil.line(l));
            lore.add(MenuUtil.blank());
            lore.add(MenuUtil.field("Ahora", r.valor(plugin), on ? NamedTextColor.GREEN : NamedTextColor.RED));
            lore.add(MenuUtil.blank());
            lore.add(MenuUtil.action("Clic para " + (on ? "desactivarla" : "activarla")));
            // El icono es SIEMPRE el de la regla, para poder distinguirlas de un
            // vistazo; el estado se lee por el color del nombre y por el cristal de
            // fondo, verde o rojo. Antes las apagadas eran todas gris y no se leia nada.
            Component titulo = Component.text(r.nombre(), on ? VERDE : NamedTextColor.RED)
                    .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false);
            inv.setItem(CASILLAS[i], MenuUtil.icon(r.icono(), titulo, lore, on));
        }
        inv.setItem(49, MenuUtil.icon(Material.BARRIER,
                MenuUtil.title("Cerrar", NamedTextColor.RED),
                List.of(MenuUtil.line("Lo que cambies se guarda solo.")), false));
        MenuUtil.frame(inv, MARCO);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Marca)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getInventory().getSize()) return;

        if (slot == 49) {
            p.closeInventory();
            return;
        }
        for (int i = 0; i < REGLAS.size() && i < CASILLAS.length; i++) {
            if (CASILLAS[i] != slot) continue;
            Regla r = REGLAS.get(i);
            boolean on = r.activa(plugin);
            plugin.getConfig().set("hardcore." + r.clave(), on ? r.apagar() : r.encendido());
            plugin.saveConfig();
            Compat.soundPlayers(p.getWorld(), p.getLocation(),
                    "block.amethyst_block.resonate", 0.8f, on ? 0.7f : 1.4f);
            p.sendMessage(Component.text(r.nombre(), VERDE)
                    .append(Component.text(on ? "  apagada." : "  encendida.", MenuUtil.SOFT)));
            pintar(e.getInventory());
            return;
        }
    }
}
