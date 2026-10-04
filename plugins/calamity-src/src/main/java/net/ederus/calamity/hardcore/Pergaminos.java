package net.ederus.calamity.hardcore;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import io.papermc.paper.event.player.PlayerLoomPatternSelectEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.LoomInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Calamity 1.10 · Los pergaminos de los contratos de Oren: cada contrato que queda por cumplir es un
 * pergamino (un diseño de estandarte, uno por clase de contrato) en el inventario, con su objetivo, su progreso y su premio escritos en el lore.
 *
 * Por que: Dosa lo pidio asi, "entregar por mision o contrato un pergamino, que lleve el lore y tambien
 * los placeholders del contrato, bien ordenado y encuadrado, que al terminar el contrato se destruya y le
 * entregue las recompensas". El contrato se ve y se lleva encima; sin su pergamino no avanza (Contratos).
 *
 * El papel NO es la verdad: la verdad sigue en contratos.<uuid> de hardcore-datos.yml. La marca
 * lethal_world:pergamino (Sello) dice de quien es, de que dia, que hueco y que contrato; un papel que no
 * cuadra con la libreta (de otro jugador, de otro dia, de un contrato cambiado o ya cobrado, roto o
 * repetido) es inerte y se borra en cuanto se ve (revisar). Por eso duplicar un pergamino no da nada: el
 * progreso y el cobro son del hueco, no del papel.
 *
 * Vive solo dentro de Calamity, como lo prestado del Kit, y con las mismas barreras (Kit.meteFuera):
 *   - se borra al salir (Hardcore.sacar, el cambio a un mundo que no es hardcore, entrar al servidor fuera);
 *   - no entra en ningun inventario que no sea el del jugador (cofres, tolvas, menus de otros plugins,
 *     aldeanos, el telar...), ni en un saco, un marco, un soporte, un allay, un jarron o un estante, ni en una receta;
 *   - tirado al suelo se rompe: no llega a caer, asi nadie mas puede cogerlo, y Oren da otro;
 *   - al morir se va con el inventario, y si por lo que sea iba a caer, no cae.
 * El Eco no lo copia ni el Censo lo cuenta: es papel, y los dos lo saltan ademas por la marca.
 */
final class Pergaminos implements Listener {

    /**
     * Como se cobra lo que se cumple dentro: el premio llega en el acto y sus Esencias, como objeto
     * (Aduana.pagar con objetoSiDentro). Lo dicen el pergamino y el menu de Oren con las mismas palabras.
     */
    static final String COBRO_DENTRO = "Al cumplirlo recibes el premio en la mano.";
    /** Casillas de la barra de progreso del lore (con los mismos caracteres que la de cordura). */
    static final int CASILLAS = 10;
    /** La "casilla" del cursor en lo que devuelve revisar(). */
    static final int CURSOR = -1;
    /**
     * Revision 1.10 · Las "casillas" de la rejilla de crafteo en lo que devuelve revisar(): la i de la
     * rejilla abierta (1-4 en la 2x2 del inventario, 1-9 en una mesa) es REJILLA - i.
     */
    static final int REJILLA = -10;

    /** Lo que lleva escrito la marca: "uuid;dia;hueco;id" (el id va al final: es lo unico que viene de la config). */
    record Sello(UUID dueno, String dia, int hueco, String id) {

        String texto() {
            return dueno + ";" + dia + ";" + hueco + ";" + id;
        }

        /** Null si no se entiende (marca a mano o rota): un papel asi es inerte. */
        static Sello de(String s) {
            if (s == null) return null;
            String[] p = s.split(";", 4);
            if (p.length != 4 || p[1].isEmpty() || p[3].isEmpty()) return null;
            try {
                return new Sello(UUID.fromString(p[0]), p[1], Integer.parseInt(p[2]), p[3]);
            } catch (IllegalArgumentException malo) {
                return null;
            }
        }
    }

    private final Hardcore hc;
    private final Contratos contratos;
    /** Ultimo aviso de "es personal" por jugador: un clic repetido no llena la barra. */
    private final Map<UUID, Long> ultimoAviso = new HashMap<>();

    Pergaminos(Hardcore hc, Contratos contratos) {
        this.hc = hc;
        this.contratos = contratos;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
    }

    void parar() {
        HandlerList.unregisterAll(this);
        ultimoAviso.clear();
    }

    // ------------------------------------------------------------------ el objeto

    /**
     * El pergamino de ese hueco, con el progreso que lleve. Uno por hueco: no se apila.
     *
     * Calamity 1.10 (lores) · Ya no es papel: es un diseño de estandarte, que en el juego se ve como un
     * pergamino con un simbolo, uno por clase de contrato (material). Se reconoce por la marca, no por el
     * material, asi que los de papel que ya circulan siguen valiendo hasta que se cobran o caducan.
     * Lo que el juego anade solo a un diseño de estandarte se oculta con TooltipDisplay (ocultarDiseno).
     */
    static ItemStack crear(Sello s, Contratos.Def d, int progreso) {
        ItemStack it = new ItemStack(material(d));
        it.editMeta(meta -> {
            meta.displayName(nombre(d));
            meta.lore(lore(d, progreso));
            // Dos pergaminos nunca hacen monton: un repetido se ve suelto y se borra.
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(Marcas.PERGAMINO, PersistentDataType.STRING, s.texto());
        });
        ocultarDiseno(it);
        return it;
    }

    /** El diseño de estandarte de cada clase de contrato (por su evento). */
    static Material material(Contratos.Def d) {
        String ev = d == null || d.evento() == null ? "" : d.evento();
        return switch (ev) {
            case "mob", "destacado" -> Material.SKULL_BANNER_PATTERN;
            case "cofre" -> Material.GLOBE_BANNER_PATTERN;
            case "minijefe" -> Material.CREEPER_BANNER_PATTERN;
            case "minutos", "minutos-limite", "minutos-sin-frasco" -> Material.FLOWER_BANNER_PATTERN;
            case "eco-valido", "redimir" -> Material.FLOW_BANNER_PATTERN;
            case "reliquia-ii", "tasa-ii" -> Material.FIELD_MASONED_BANNER_PATTERN;
            default -> Material.MOJANG_BANNER_PATTERN;
        };
    }

    /**
     * Oculta lo que el juego anade solo a un diseño de estandarte (el componente provides_banner_patterns),
     * para que el globo diga solo lo nuestro. Sin la API de componentes, el pergamino vale igual.
     */
    static void ocultarDiseno(ItemStack it) {
        try {
            it.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                    .addHiddenComponents(DataComponentTypes.PROVIDES_BANNER_PATTERNS).build());
        } catch (Throwable sinApi) {
            // Se veria la linea del diseño: nada mas.
        }
    }

    /** "Contrato · Mobs", en el ambar de los contratos, sin cursiva ni negrita. El objetivo va en el lore. */
    static Component nombre(Contratos.Def d) {
        return Component.text(titulo(d), Contratos.AMBAR)
                .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, false);
    }

    /** "Contrato · " y la etiqueta (la misma de la barra de accion): corto, cabe siempre en una linea. */
    static String titulo(Contratos.Def d) {
        String e = d.etiqueta() == null || d.etiqueta().isBlank() ? d.texto() : d.etiqueta();
        return "Contrato · " + e;
    }

    /** Si es un pergamino de contrato (por la marca: da igual el material y como se llame). */
    static boolean es(ItemStack it) {
        return it != null && !it.getType().isAir() && it.getPersistentDataContainer().has(Marcas.PERGAMINO);
    }

    /** Su marca, o null si no es un pergamino o la marca no se entiende. */
    static Sello sello(ItemStack it) {
        if (!es(it)) return null;
        try {
            return Sello.de(it.getPersistentDataContainer().get(Marcas.PERGAMINO, PersistentDataType.STRING));
        } catch (IllegalArgumentException otroTipo) {
            return null;
        }
    }

    /**
     * Por que un pergamino no vale ante la libreta de ese jugador, o null si vale: "roto" (sin marca
     * legible), "ajeno" (de otro), "dia" (de otra libreta), "hueco" (ese hueco tiene otro contrato: lo
     * cambio) o "cobrado". Puro: lo prueba el autotest de contratos.
     */
    static String motivoInerte(Sello s, UUID jugador, ConfigurationSection libreta) {
        if (s == null) return "roto";
        if (!s.dueno().equals(jugador)) return "ajeno";
        if (libreta == null || !s.dia().equals(libreta.getString("dia", ""))) return "dia";
        String r = "lista." + s.hueco();
        if (!s.id().equals(libreta.getString(r + ".id", ""))) return "hueco";
        if (libreta.getBoolean(r + ".cobrado", false)) return "cobrado";
        return null;
    }

    // ------------------------------------------------------------------ el lore

    /** Cuantas de las 10 casillas van llenas: hacia abajo, al menos una con algo hecho y las diez solo cumplido. */
    static int llenas(int hecho, int objetivo) {
        if (hecho <= 0) return 0;
        if (hecho >= objetivo) return CASILLAS;
        return Math.max(1, Math.min(CASILLAS - 1, hecho * CASILLAS / objetivo));
    }

    /** La linea de historia de cada clase de contrato (por su evento). */
    static String historia(Contratos.Def d) {
        String ev = d == null || d.evento() == null ? "" : d.evento();
        return switch (ev) {
            case "mob" -> "Oren paga por cada criatura que no vuelva a levantarse.";
            case "destacado" -> "Las marcadas valen más. Oren las quiere muertas.";
            case "cofre" -> "Lo que guardaron los que no volvieron todavía espera dueño.";
            case "minijefe" -> "Algunos de aquí tienen nombre. Oren quiere que dejen de tenerlo.";
            case "minutos" -> "Quedarse ya es una hazaña. Oren lo sabe.";
            case "minutos-limite" -> "Al borde de la locura, Calamity habla más claro.";
            case "minutos-sin-frasco" -> "Sin el Frasco, solo te sostiene tu cabeza.";
            case "eco-valido" -> "Los Ecos ajenos no descansan hasta que alguien los calla.";
            case "redimir" -> "Tu Eco te espera donde caíste. Dale descanso.";
            case "reliquia-ii", "tasa-ii" -> "Encontrarla no vale nada: lo que vale es salir vivo con ella.";
            default -> "Oren paga, y Oren no olvida.";
        };
    }

    /** "{2} Esencias · {20} MobCoins": el premio con las cifras marcadas para el acento. */
    static String premioMarcado(Contratos.Def d) {
        if (d.esencias() <= 0 && d.mobcoins() <= 0) return "Sin premio";
        return Ficha.valor(d.esencias(), d.mobcoins());
    }

    /**
     * Calamity 1.10 (lores) · El lore con la plantilla comun (Ficha): la clase de contrato, su historia,
     * Objetivo, Progreso (la barra con las casillas llenas en el acento) y Premio, como se cobra y que el
     * avance es de esta expedicion. Lineas de 38 como mucho y filete fijo.
     */
    static Ficha ficha(Contratos.Def d, int progreso) {
        int objetivo = Math.max(1, d.objetivo());
        int hecho = Math.max(0, Math.min(progreso, objetivo));
        int llenas = llenas(hecho, objetivo);
        return new Ficha(Contratos.AMBAR).tipo(d.corto() ? "Contrato de Oren · Corto" : "Contrato de Oren").filete()
                .historia(historia(d)).filete()
                .etiqueta("Objetivo").dato(d.texto())
                .etiqueta("Progreso").dato(barra(llenas) + " {" + hecho + "/" + objetivo + "}")
                .etiqueta("Premio").dato(premioMarcado(d)).filete()
                .accion(Contratos.seCobraAlSalir(d) ? "Se cobra al salir vivo de Calamity." : COBRO_DENTRO)
                .nota("Si mueres, el avance vuelve a cero.");
    }

    /** Las llenas entre llaves (acento); las vacias se repintan en gris oscuro en lore(). */
    private static String barra(int llenas) {
        return (llenas > 0 ? "{" + "▮".repeat(llenas) + "}" : "") + "▯".repeat(CASILLAS - llenas);
    }

    /** El lore en texto plano, linea a linea (sin Bukkit: el autotest lo compara tal cual). */
    static List<String> lineas(Contratos.Def d, int progreso) {
        return ficha(d, progreso).lineas();
    }

    /** El lore pintado, sin cursiva; las casillas vacias de la barra en el gris de la de cordura. */
    static List<Component> lore(Contratos.Def d, int progreso) {
        List<Component> out = new ArrayList<>();
        for (Component c : ficha(d, progreso).lore()) {
            out.add(c.replaceText(b -> b.matchLiteral("▯").replacement(m -> m.color(Paleta.CASILLA_VACIA))));
        }
        return out;
    }

    // ------------------------------------------------------------------ en el inventario

    /**
     * Los pergaminos que lleva (inventario entero, cursor y, desde la revision de la 1.10, la rejilla de
     * crafteo que tenga abierta) mirados contra su libreta: hueco -> casilla (0-40, CURSOR o REJILLA - i)
     * de los que valen. Los inertes se borran aqui mismo: "se borra en cuanto se ve".
     */
    Map<Integer, Integer> revisar(Player p, ConfigurationSection libreta) {
        return mirar(p, libreta, true);
    }

    /** Lo mismo sin borrar nada: los huecos cuyo pergamino lleva (el menu de Oren solo mira). */
    Set<Integer> validos(Player p, ConfigurationSection libreta) {
        return mirar(p, libreta, false).keySet();
    }

    private Map<Integer, Integer> mirar(Player p, ConfigurationSection libreta, boolean borrar) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            int casilla = i;
            mirarUno(p, inv.getItem(i), casilla, libreta, borrar, out, nuevo -> inv.setItem(casilla, nuevo));
        }
        mirarUno(p, p.getItemOnCursor(), CURSOR, libreta, borrar, out, p::setItemOnCursor);
        // Revision 1.10: la rejilla de crafteo (la 2x2 de su inventario, o una mesa). Un papel aparcado ahi
        // sigue siendo suyo: cuenta para el contrato, y Oren no le da otro. La casilla 0 es el resultado.
        Inventory rej = rejilla(p);
        if (rej != null) {
            for (int i = 1; i < rej.getSize(); i++) {
                int casilla = i;
                mirarUno(p, rej.getItem(i), REJILLA - i, libreta, borrar, out, nuevo -> rej.setItem(casilla, nuevo));
            }
        }
        return out;
    }

    /**
     * Un papel de mirar(): si vale, se apunta con su casilla; si es inerte o repetido y toca borrar, se quita
     * con "poner" (null). Un monton de dos (de antes del tope de 1, o de creativo) es un repetido: se queda uno.
     */
    private void mirarUno(Player p, ItemStack it, int casilla, ConfigurationSection libreta, boolean borrar,
                          Map<Integer, Integer> out, java.util.function.Consumer<ItemStack> poner) {
        if (!es(it)) return;
        Sello s = sello(it);
        String no = motivoInerte(s, p.getUniqueId(), libreta);
        if (no == null && out.containsKey(s.hueco())) no = "repetido";
        if (no == null) {
            out.put(s.hueco(), casilla);
            if (borrar && it.getAmount() > 1) {
                poner.accept(it.asOne());
                anotar(p, "repetido", s);
            }
        } else if (borrar) {
            poner.accept(null);
            anotar(p, no, s);
        }
    }

    /** La rejilla de crafteo que tiene abierta (la 2x2 de su inventario, o una mesa), o null. */
    private static Inventory rejilla(Player p) {
        Inventory arriba = p.getOpenInventory().getTopInventory();
        InventoryType t = arriba.getType();
        return t == InventoryType.CRAFTING || t == InventoryType.WORKBENCH ? arriba : null;
    }

    private void anotar(Player p, String motivo, Sello s) {
        hc.plugin().bitacora().anotar("contrato", "pergamino-inerte", p.getName(), motivo, s == null ? "?" : s.id());
    }

    /** Le mete el pergamino en el inventario. False si no cabe: no se tira al suelo (quien llama se lo dice). */
    boolean dar(Player p, Sello s, Contratos.Def d, int progreso) {
        return p.getInventory().addItem(crear(s, d, progreso)).isEmpty();
    }

    /**
     * Vuelve a escribir nombre y lore del pergamino de ese hueco en esa casilla (la de revisar). Solo
     * cuando cambia el progreso, nunca por tick. False si ya no esta ahi.
     */
    boolean redibujar(Player p, int casilla, int hueco, Contratos.Def d, int progreso) {
        Inventory rej = casilla <= REJILLA ? rejilla(p) : null;
        int enRejilla = REJILLA - casilla;
        if (casilla <= REJILLA && (rej == null || enRejilla >= rej.getSize())) return false;
        ItemStack it = casilla == CURSOR ? p.getItemOnCursor() : rej != null ? rej.getItem(enRejilla)
                : p.getInventory().getItem(casilla);
        Sello s = sello(it);
        if (s == null || s.hueco() != hueco || !s.dueno().equals(p.getUniqueId())) return false;
        ItemStack nuevo = it.clone();
        nuevo.editMeta(m -> {
            m.displayName(nombre(d));
            m.lore(lore(d, progreso));
        });
        if (casilla == CURSOR) p.setItemOnCursor(nuevo);
        else if (rej != null) rej.setItem(enRejilla, nuevo);
        else p.getInventory().setItem(casilla, nuevo);
        return true;
    }

    /** Quita sus pergaminos de esos huecos (al cobrarlos: el papel es el contrato). Cuantos. */
    int quitar(Player p, Collection<Integer> huecos) {
        UUID u = p.getUniqueId();
        return borrarSi(p, s -> s != null && s.dueno().equals(u) && huecos.contains(s.hueco()));
    }

    /** Todos, de quien sean y aunque esten rotos: al salir de Calamity. Cuantos. */
    int borrarTodos(Player p) {
        return borrarSi(p, s -> true);
    }

    private int borrarSi(Player p, Predicate<Sello> cual) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (es(it) && cual.test(sello(it))) {
                inv.setItem(i, null);
                n++;
            }
        }
        ItemStack cursor = p.getItemOnCursor();
        if (es(cursor) && cual.test(sello(cursor))) {
            p.setItemOnCursor(null);
            n++;
        }
        // La rejilla 2x2 de su inventario (o la mesa que tenga abierta): volveria al inventario al cerrar.
        Inventory arriba = p.getOpenInventory().getTopInventory();
        if (arriba.getType() == InventoryType.CRAFTING || arriba.getType() == InventoryType.WORKBENCH) {
            for (int i = 0; i < arriba.getSize(); i++) {
                ItemStack it = arriba.getItem(i);
                if (es(it) && cual.test(sello(it))) {
                    arriba.setItem(i, null);
                    n++;
                }
            }
        }
        if (n > 0) p.updateInventory();
        return n;
    }

    // ------------------------------------------------------------------ barreras

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void alClic(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !Kit.meteFuera(e, Pergaminos::es)) return;
        e.setCancelled(true);
        avisar(p);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void alArrastrar(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !Kit.arrastraFuera(e, Pergaminos::es)) return;
        e.setCancelled(true);
        avisar(p);
    }

    /**
     * Tirado, se rompe: el objeto no llega a caer (nadie mas lo coge) y Oren da otro. Al final de la
     * cadena (HIGHEST) y sin cancelados: si otro plugin no deja tirar, el pergamino se queda donde estaba.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void alTirar(PlayerDropItemEvent e) {
        if (!es(e.getItemDrop().getItemStack())) return;
        Player p = e.getPlayer();
        Sello s = sello(e.getItemDrop().getItemStack());
        e.getItemDrop().remove();
        p.sendMessage(ComandoCalamity.mensaje("Rompiste el pergamino. Oren puede darte otro."));
        Marco.sonar(p, "item.book.page_turn", 0.6f, 0.6f);
        hc.plugin().bitacora().anotar("contrato", "pergamino-roto", p.getName(), s == null ? "?" : s.id());
        // Un tick despues el inventario ya no lo tiene en ninguna via (Q, cursor fuera de la ventana).
        contratos.refrescarBarraLuego(p);
    }

    /** Cualquier otra via por la que un pergamino iba a quedar suelto (muerte sin borrar, inventario lleno...). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void alAparecer(ItemSpawnEvent e) {
        if (es(e.getEntity().getItemStack())) e.setCancelled(true);
    }

    /** Si aun asi hay uno en el suelo, nadie lo recoge: se borra al intentarlo (jugador, mob o tolva). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void alRecoger(EntityPickupItemEvent e) {
        if (!es(e.getItem().getItemStack())) return;
        e.setCancelled(true);
        e.getItem().remove();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void alTolva(InventoryPickupItemEvent e) {
        if (!es(e.getItem().getItemStack())) return;
        e.setCancelled(true);
        e.getItem().remove();
    }

    /** Al morir no cae: con lo-pierde-todo apagado el inventario si cae, y el papel no tiene que salir de ahi. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alMorir(PlayerDeathEvent e) {
        e.getDrops().removeIf(Pergaminos::es);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void alSoporte(PlayerArmorStandManipulateEvent e) {
        if (!es(e.getPlayerItem())) return;
        e.setCancelled(true);
        avisar(e.getPlayer());
    }

    /** Marcos (tambien los luminosos) y allays: los dos se quedan con lo que tengas en la mano. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void alEntidad(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if (!(t instanceof ItemFrame) && !(t instanceof Allay)) return;
        if (!es(e.getPlayer().getInventory().getItem(e.getHand()))) return;
        e.setCancelled(true);
        avisar(e.getPlayer());
    }

    /**
     * Jarrones, estanterias cinceladas y estantes (Sellos.bloqueGuarda): se quedan el objeto a la vista. Un
     * estante con corriente cambia la barra rapida entera, asi que ahi basta con llevar uno en ella.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void alBloque(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || !Sellos.bloqueGuarda(b.getType())) return;
        Player p = e.getPlayer();
        boolean lleva = es(e.getItem());
        if (!lleva && b.getType().name().endsWith("_SHELF")) {
            for (int i = 0; i < 9 && !lleva; i++) lleva = es(p.getInventory().getItem(i));
        }
        if (!lleva) return;
        e.setCancelled(true);
        avisar(p);
    }

    /** Ni en la rejilla 2x2: el papel da libros y cohetes, y Oren lo regala. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void alReceta(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (es(it)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    /**
     * Calamity 1.10 (lores) · El telar: el pergamino es un diseño de estandarte y el telar los acepta. Meterlo
     * ya lo cierra alClic (el telar es un inventario ajeno); esto es la segunda llave: si aun asi hay uno en
     * el telar, no sale estandarte ni se puede elegir el diseño.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void alTelar(PrepareResultEvent e) {
        if (e.getInventory() instanceof LoomInventory telar && hay(telar)) e.setResult(null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void alElegirDiseno(PlayerLoomPatternSelectEvent e) {
        if (!hay(e.getLoomInventory())) return;
        e.setCancelled(true);
        avisar(e.getPlayer());
    }

    private static boolean hay(Inventory inv) {
        for (ItemStack it : inv.getContents()) if (es(it)) return true;
        return false;
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        ultimoAviso.remove(e.getPlayer().getUniqueId());
    }

    /** "Es personal", en la barra y como mucho una vez por segundo (como los Sellos). */
    private void avisar(Player p) {
        long ahora = System.currentTimeMillis();
        Long antes = ultimoAviso.get(p.getUniqueId());
        if (antes != null && ahora - antes < 1000) return;
        ultimoAviso.put(p.getUniqueId(), ahora);
        Component t = Component.text("Tu pergamino es personal: no se puede guardar ni dar.", Paleta.AVISO);
        if (hc.esHardcore(p)) hc.cordura().destello(p, t, 2);
        else p.sendMessage(ComandoCalamity.mensaje(t));
    }
}
