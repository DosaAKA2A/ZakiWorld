package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.ederus.edm.comun.Compat;
import net.ederus.calamity.CalamityPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * El panel de Calamity: las reglas de dificultad, cada una con su interruptor.
 *
 * El resto del plugin va por comando a proposito (Bedrock), pero una docena larga de
 * interruptores en un YAML es justo lo que nadie quiere tocar en caliente, y equivocarse ahi
 * deja el mundo entero mal. Un cofre con una casilla por regla se entiende de un vistazo y se
 * cambia sin salir del juego.
 *
 * Las reglas que son NUMERO (hambre, caida, durabilidad...) se apagan poniendo su
 * valor neutro, no borrandolas: asi el numero que Dosa haya afinado no se pierde al
 * apagar y volver a encender. Ver Regla#apagar.
 *
 * 1.7.3: con las piezas de Marco como los demas menus (marco negro sin huecos, Cerrar abajo en
 * el centro, "Etiqueta: valor" con los colores de Paleta). Antes usaba las de MenuUtil de EDM,
 * y su gris de etiqueta (#404040) no se leia sobre el globo.
 */
public final class MenuHardcore implements Listener {

    /** Las casillas de las reglas: tres filas de siete, aireadas y centradas. */
    private static final int[] CASILLAS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};
    /** Cerrar, abajo en el centro como en todos los menus de Calamity. */
    private static final int CERRAR = 49;

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

        boolean activa(CalamityPlugin plugin) {
            Object v = plugin.getConfig().get("hardcore." + clave, apagar);
            if (encendido instanceof Boolean) return Boolean.TRUE.equals(v);
            return v instanceof Number n && n.doubleValue() > ((Number) apagar).doubleValue();
        }

        String valor(CalamityPlugin plugin) {
            Object v = plugin.getConfig().get("hardcore." + clave, apagar);
            if (v instanceof Boolean b) return b ? "sí" : "no";
            if (v instanceof Number n) {
                double d = n.doubleValue();
                return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d).replace('.', ',');
            }
            return String.valueOf(v);
        }
    }

    /** Las que aprobo Dosa, en el orden en que se leen mejor. */
    private static final List<Regla> REGLAS = List.of(
            new Regla("dificultad.sin-camas", "Sin camas", Material.RED_BED, true, false,
                    List.of("No se duerme: ni se salta la noche", "ni se pone punto de reaparición.")),
            new Regla("dificultad.sin-regeneracion", "Sin regeneración", Material.GOLDEN_APPLE, true, false,
                    List.of("La vida no se regenera sola: solo", "curan las pociones y la comida encantada.")),
            new Regla("dificultad.hambre", "Hambre x2", Material.ROTTEN_FLESH, 2.0, 1.0,
                    List.of("El hambre que gastas se multiplica.", "Comer sigue llenando lo mismo.")),
            new Regla("dificultad.veneno-comida-cruda", "Comida cruda", Material.CHICKEN, 8, 0,
                    List.of("Segundos de veneno y hambre", "que deja comer algo crudo.")),
            new Regla("dificultad.dano-caida", "Caída x2", Material.FEATHER, 2.0, 1.0,
                    List.of("El daño de caída se multiplica.")),
            new Regla("dificultad.dano-ahogo", "Ahogo x2", Material.WATER_BUCKET, 2.0, 1.0,
                    List.of("El daño por ahogarse se multiplica.")),
            new Regla("dificultad.penetracion-armadura", "Armadura penetrada", Material.NETHERITE_CHESTPLATE, 0.30, 0.0,
                    List.of("Cuánta armadura ignoran los mobs.", "0,30 = pegan un 30 % más.")),
            new Regla("dificultad.durabilidad", "Durabilidad x2", Material.DAMAGED_ANVIL, 2.0, 1.0,
                    List.of("El equipo se gasta más rápido.")),
            new Regla("dificultad.sin-totem", "Sin tótem", Material.TOTEM_OF_UNDYING, true, false,
                    List.of("El tótem se gasta y no te salva.")),
            new Regla("dificultad.mobs-recogen", "Mobs recogen", Material.HOPPER, true, false,
                    List.of("Los mobs recogen lo que se te cae", "y se lo quedan.")),
            new Regla("dificultad.nivel-cada-minutos", "Nivel por minutos", Material.EXPERIENCE_BOTTLE, 3, 0,
                    List.of("Los mobs suben un nivel por cada", "tantos minutos que lleves en Calamity.")),
            new Regla("dificultad.niebla-de-noche", "Niebla de noche", Material.GRAY_STAINED_GLASS, true, false,
                    List.of("De noche hay niebla de ceniza y", "rachas de oscuridad.", "Es un efecto por jugador, no un bioma.")),
            new Regla("dificultad.fuego-amigo", "Fuego amigo", Material.IRON_SWORD, true, false,
                    List.of("Los jugadores pueden matarse", "entre sí.")),
            new Regla("minijefes.distancia-maxima", "Minijefes marcan", Material.WITHER_SKELETON_SKULL, 60, 0,
                    List.of("El minijefe te sigue entre biomas y,", "si te alejas, reaparece a tu lado.")),
            new Regla("dificultad.oleada-de-entrada", "Oleada de entrada", Material.SPAWNER, 5, 0,
                    List.of("Mobs que te reciben al entrar.")),
            new Regla("dificultad.cofres-vacios", "Cofres vacíos", Material.CHEST, true, false,
                    List.of("Los cofres de estructura salen vacíos:", "todo el botín sale de los mobs.")),
            new Regla("muerte.cuarentena-minutos", "Cuarentena", Material.CLOCK, 30, 0,
                    List.of("Minutos de espera para volver a entrar", "después de morir en Calamity.")),
            /* M22: las seis leyes de legibilidad (DIS sec. 0.2), para que quien lleve Calamity
             * las tenga delante al tocar cualquier otra regla. Una Regla tiene que tener un
             * interruptor: el suyo es el parte de defuncion, que es lo que ensena al jugador,
             * al morir, cual de todas estas cosas le ha matado. Calavera normal y no de wither:
             * esa ya es la de "Minijefes marcan" y cada casilla tiene que leerse de un vistazo. */
            new Regla("parte-defuncion.activo", "Lo que te puede matar", Material.SKELETON_SKULL, true, false,
                    List.of("1. Lo real lleva nivel: su cartel \"Nv. X\".",
                            "   Las visiones nunca lo llevan.",
                            "2. La campana siempre es real:",
                            "   solo la Parca y las muertes.",
                            "3. Lo falso no hace daño.",
                            "4. Todo golpe fuerte se avisa antes.",
                            "5. Nada te mata de un golpe",
                            "   con la vida llena.",
                            "6. Una amenaza grande a la vez.",
                            "",
                            "El interruptor es el parte de defunción:",
                            "al morir, los últimos golpes y el porqué.")));

    /** Marca de nuestro inventario: sin esto, cualquier cofre tragaria los clics. */
    private record Marca() implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final CalamityPlugin plugin;

    public MenuHardcore(CalamityPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void abrir(Player p) {
        Inventory inv = plugin.getServer().createInventory(new Marca(), 54,
                Marco.T_DIFICULTAD.componente());
        pintar(inv);
        p.openInventory(inv);
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.creaking_heart.idle", 0.8f, 1.2f);
    }

    private void pintar(Inventory inv) {
        inv.clear();
        Hardcore hc = plugin.hardcore();
        boolean vivo = hc != null;

        // La marca "Calamity" en negrita con su degradado: es la unica negrita permitida.
        inv.setItem(4, Marco.icono(Material.PALE_OAK_LOG, Paleta.marca(), List.of(
                Component.text("Reglas: ", Paleta.TENUE).append(Component.text(vivo ? "activas" : "apagadas",
                        vivo ? Paleta.BIEN : Paleta.AVISO)),
                Marco.dato("Mundos", vivo ? String.join(", ", hc.mundos()) : "ninguno"),
                Component.empty(),
                Marco.tenue("Cada casilla es una regla."),
                Marco.tenue("Los portales y los objetos van"),
                Marco.tenue("por /calamity.")), true));

        for (int i = 0; i < REGLAS.size() && i < CASILLAS.length; i++) {
            Regla r = REGLAS.get(i);
            boolean on = r.activa(plugin);
            List<Component> lore = new ArrayList<>();
            for (String l : r.ayuda()) lore.add(Marco.tenue(l));
            lore.add(Component.empty());
            lore.add(Component.text("Ahora: ", Paleta.TENUE).append(Component.text(r.valor(plugin), on ? Paleta.BIEN : Paleta.AVISO)));
            lore.add(Component.empty());
            lore.add(Marco.accion("Clic para " + (on ? "desactivarla" : "activarla")));
            // El icono es SIEMPRE el de la regla, para poder distinguirlas de un vistazo; el
            // estado se lee por el color del nombre y por el brillo. Antes las apagadas eran
            // todas gris y no se leia nada. Sin negrita: la negrita es solo de la marca.
            Component titulo = Paleta.nombre(r.nombre(), on ? Paleta.DETALLE : Paleta.AVISO);
            inv.setItem(CASILLAS[i], Marco.icono(r.icono(), titulo, lore, on));
        }
        inv.setItem(CERRAR, Marco.icono(Material.BARRIER, Component.text("Cerrar", Marco.NO), List.of(
                Marco.tenue("Lo que cambies se guarda solo."), Component.empty(), Marco.accion("Clic para cerrar")), false));
        Marco.rellenar(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Marca)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getInventory().getSize()) return;

        if (slot == CERRAR) {
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
            p.sendMessage(Paleta.mensaje(Component.text(r.nombre(), Paleta.DETALLE)
                    .append(Component.text(on ? ": apagada." : ": encendida.", on ? Paleta.AVISO : Paleta.BIEN))));
            pintar(e.getInventory());
            return;
        }
    }
}
