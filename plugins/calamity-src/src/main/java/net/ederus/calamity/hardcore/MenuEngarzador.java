package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * El Engarzador (Calamity 1.4): el NPC de la antesala que pone y quita las gemas de Calamity.
 *
 * Por que existe: la Gema se engarzaba con el metodo de MMOItems (arrastrarla sobre la pieza en
 * el inventario) y Dosa lo dijo claro, nadie lo descubre. Ahora hay un sitio y un menu. Se llama
 * como los demas de la antesala, por su oficio (el Forjador, el Tasador, el Cronista, el
 * Cazador): el Engarzador, que es lo que dicen el Altar ("se engarza") y los jugadores.
 *
 * Decisiones de Dosa (2026-09-27): solo gemas de Calamity y solo en piezas de Calamity con un
 * hueco libre de su color; engarzar es gratis; quitar una gema la rompe (no vuelve) y el hueco
 * queda libre. El NPC lo pone Dosa a mano con Citizens; aqui solo esta lo que su clic ejecuta
 * como consola, "calamity open <p> gemsetter" (Npcs), y la receta en /calamity npcs.
 *
 * El menu (54, marco negro de Calamity). Lote de gemas (1.12.3), como lo pidio Dosa: la pieza AL
 * CENTRO y sus huecos ALREDEDOR, como una piedra en su engaste:
 *  - arriba en el centro, la ayuda; abajo en el centro, Cerrar (como en todos los menus, 1.7.3);
 *  - la pieza en el centro (PIEZA) y sus huecos en las ocho casillas que la rodean (anillo),
 *    repartidos de forma simetrica segun cuantos tenga: libres (cristal) y ocupados (la gema de
 *    verdad, con su lore de MMOItems). Tocar un hueco ocupado pide confirmar en la misma ventana,
 *    avisando de que la gema se rompe;
 *  - en la fila de la pieza, la gema que se va a engarzar a la izquierda (GEMA) y Engarzar a la
 *    derecha (ENGARZAR): se lee gema, pieza, engarzar. Con una gema puesta que entra, su hueco se
 *    marca con brillo ("aqui entrara la gema").
 * Se pone una cosa TOCANDOLA EN TU INVENTARIO (la de abajo): el menu sabe si es pieza o gema y
 * la coloca en su sitio; tocarla arriba te la devuelve. Nada se arrastra ni se suelta, y todo
 * se hace con un clic izquierdo suelto: asi funciona igual en Bedrock (Geyser), que no tiene
 * clic derecho ni shift.
 *
 * Lo que se pone sale del inventario del jugador y queda en la Mesa (el holder de la ventana)
 * hasta que se devuelve: al tocarlo arriba, al cerrar (tambien si se abre otra ventana encima),
 * al desconectarse, al parar el plugin y al morir (con keepInventory, al inventario; sin el,
 * a lo que suelta al morir, como si lo llevara encima). Todos los clics se cancelan: los objetos
 * solo los mueve este codigo, uno a uno, y la ventana solo ensena copias. Numeros del teclado,
 * doble clic, soltar y arrastrar no hacen nada.
 *
 * 1.12.3 · Tambien vive aqui ActualizacionMmo: cuando MMOItems rehace una pieza de Calamity por
 * su revision-id (para darle su hueco nuevo), le devuelve sus marcas (el ligado a su dueno).
 */
final class MenuEngarzador implements Listener {

    /** Donde va cada cosa (54). La ayuda arriba y Cerrar abajo, en el centro, como en todos los menus. */
    static final int AYUDA = 4, PIEZA = 22, GEMA = 19, ENGARZAR = 25, CERRAR = 49;
    /**
     * Los huecos alrededor de la pieza segun cuantos tenga (0 a 8), en el orden de la ficha
     * (Engarce.Ficha.huecos): siempre simetricos respecto a la columna de la pieza. Uno, encima;
     * dos, encima y debajo; tres, en triangulo; cuatro, en cruz; ocho, el anillo entero.
     */
    private static final int[][] ANILLO_DE = {
            {},
            {13},
            {13, 31},
            {13, 30, 32},
            {13, 21, 23, 31},
            {13, 21, 23, 30, 32},
            {12, 14, 21, 23, 30, 32},
            {12, 13, 14, 21, 23, 30, 32},
            {12, 13, 14, 21, 23, 30, 31, 32}};
    /** Los que caben alrededor de la pieza. Una pieza con mas (no hay ninguna) ensena los primeros. */
    static final int MAX_HUECOS = 8;
    /** La pantalla de confirmar (misma ventana): la pieza en su sitio, la gema en su hueco y los dos botones. */
    static final int[] C_NO = {37, 38, 39}, C_SI = {41, 42, 43};
    /** Engarzar y quitar, un clic cada tanto (un doble toque no hace dos veces lo mismo). */
    private static final long ESPERA_MS = 400;

    /**
     * La ventana abierta de un jugador y lo que ha puesto en ella. confirmar: el UUID de la gema
     * que ha pedido quitar (pantalla de confirmar), o null.
     */
    static final class Mesa implements InventoryHolder {
        final UUID jugador;
        final Map<Integer, String> acciones = new HashMap<>();
        Inventory inv;
        ItemStack pieza;
        ItemStack gema;
        String confirmar;
        boolean cerrada;

        Mesa(UUID jugador) {
            this.jugador = jugador;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final Hardcore hc;
    private final Map<UUID, Mesa> abiertas = new HashMap<>();
    private final Map<UUID, Long> ultimoClic = new HashMap<>();
    /** Los nombres de las piezas que traen hueco (objetos-calamity.yml), para el hueco vacio de la pieza. */
    private final List<String> conHuecos;
    /** 1.12.3: el que conserva el ligado cuando MMOItems actualiza una pieza. Null sin MMOItems. */
    private final ActualizacionMmo actualizacion;

    MenuEngarzador(Hardcore hc) {
        this.hc = hc;
        this.conHuecos = piezasConHuecos(hc);
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        // Importa MMOItems: solo si esta (la clase no se carga hasta aqui). Si falla, el menu sigue.
        this.actualizacion = PuenteMmo.disponible()
                ? hc.valor("engarzador", () -> new ActualizacionMmo(hc, this::tiposCalamity), null) : null;
        Autotest.registrar("engarce", this::autotest);
    }

    void parar() {
        for (Mesa m : new ArrayList<>(abiertas.values())) {
            Player p = Bukkit.getPlayer(m.jugador);
            if (p == null) continue;
            vaciar(p, m);
            if (p.getOpenInventory().getTopInventory().getHolder(false) == m) p.closeInventory();
        }
        abiertas.clear();
        ultimoClic.clear();
        if (actualizacion != null) hc.seguro("engarzador", actualizacion::parar);
        HandlerList.unregisterAll(this);
    }

    // ================================================================= tipos

    /** Los tipos de MMOItems de las gemas de Calamity: el de entregas.mmo.gema y CALAMITY_GEMAS. */
    Set<String> tiposGema() {
        Set<String> out = new LinkedHashSet<>();
        Entregas ent = hc.entregas();
        String id = ent == null ? hc.cfg().getString("entregas.mmo.gema") : ent.idMmo("gema");
        String t = tipoDe(id);
        if (t != null) out.add(t);
        out.add("CALAMITY_GEMAS");
        return out;
    }

    /** Los tipos de las piezas de Calamity: los de forja.piezas (CALAMITY y CALAMITY_ARMAS), sin los de gema. */
    Set<String> tiposPieza() {
        Set<String> out = new LinkedHashSet<>();
        ConfigurationSection s = hc.cfg().getConfigurationSection("forja.piezas");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                String t = tipoDe(s.getString(k));
                if (t != null) out.add(t);
            }
        }
        if (out.isEmpty()) out.addAll(List.of("CALAMITY", "CALAMITY_ARMAS"));
        out.removeAll(tiposGema());
        return out;
    }

    /**
     * Piezas, gemas y la Placa del Vigilante (CALAMITY_MATERIALES): los tipos cuyas marcas conserva ActualizacionMmo.
     * La placa sale ligada como las piezas; si un dia sube su revision-id, que no pierda el ligado.
     */
    Set<String> tiposCalamity() {
        Set<String> out = new LinkedHashSet<>(tiposPieza());
        out.addAll(tiposGema());
        Entregas ent = hc.entregas();
        String placa = tipoDe(ent == null ? Entregas.MMO_DEFECTO.get(Entregas.PLACA_DEL_VIGILANTE) : ent.idMmo(Entregas.PLACA_DEL_VIGILANTE));
        if (placa != null) out.add(placa);
        return out;
    }

    private static String tipoDe(String enlace) {
        if (enlace == null) return null;
        int punto = enlace.indexOf('.');
        return punto > 0 ? enlace.substring(0, punto).trim().toUpperCase(Locale.ROOT) : null;
    }

    /** Los nombres de las piezas con hueco de gema segun objetos-calamity.yml (el del servidor o el del jar). */
    private static List<String> piezasConHuecos(Hardcore hc) {
        List<String> out = new ArrayList<>();
        String texto = null;
        try {
            File f = new File(hc.plugin().getDataFolder(), ObjetosReales.FICHERO);
            if (f.isFile()) texto = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            else {
                try (InputStream in = hc.plugin().getResource(ObjetosReales.FICHERO)) {
                    if (in != null) texto = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            return out;
        }
        YamlConfiguration y = ObjetosReales.leer(texto);
        ConfigurationSection o = y == null ? null : y.getConfigurationSection("objetos");
        if (o == null) return out;
        for (String k : o.getKeys(false)) {
            ConfigurationSection x = o.getConfigurationSection(k);
            if (x != null && !x.getStringList("huecos").isEmpty()) out.add(x.getString("nombre", k));
        }
        return out;
    }

    // ================================================================= disposicion

    /** Las casillas de n huecos alrededor de la pieza (ANILLO_DE), en el orden de la ficha. */
    static int[] anillo(int n) {
        return ANILLO_DE[Math.max(0, Math.min(MAX_HUECOS, n))].clone();
    }

    /** Si una casilla toca la de la pieza (las ocho de alrededor). */
    static boolean alrededor(int casilla) {
        int df = Math.abs(casilla / 9 - PIEZA / 9), dc = Math.abs(casilla % 9 - PIEZA % 9);
        return casilla != PIEZA && df <= 1 && dc <= 1;
    }

    // ================================================================= abrir y pintar

    /** Lo que abre el NPC. False (y se le dice) si MMOItems no esta. */
    boolean abrir(Player p) {
        if (!PuenteMmo.disponible()) {
            p.sendMessage(ComandoCalamity.mensaje("Lior no puede trabajar ahora mismo."));
            Marco.sonidoNo(p);
            return false;
        }
        Mesa m = new Mesa(p.getUniqueId());
        m.inv = hc.plugin().getServer().createInventory(m, 54, Marco.T_ENGARZADOR.componente());
        pintar(m);
        p.openInventory(m.inv);
        // Si otro plugin cancela la apertura, no queda una mesa colgando.
        if (p.getOpenInventory().getTopInventory().getHolder(false) != m) return false;
        abiertas.put(p.getUniqueId(), m);
        Marco.sonar(p, "block.smithing_table.use", 0.6f, 1.2f);
        return true;
    }

    private void pintar(Mesa m) {
        Inventory inv = m.inv;
        inv.clear();
        m.acciones.clear();
        inv.setItem(AYUDA, ayuda());
        inv.setItem(CERRAR, Marco.cerrar());
        m.acciones.put(CERRAR, "cerrar");

        Engarce.Ficha fp = m.pieza == null ? null : EngarceMmo.leer(m.pieza);
        Engarce.Ficha fg = m.gema == null ? null : EngarceMmo.leer(m.gema);

        if (m.confirmar != null) {
            Engarce.Hueco h = fp == null ? null : buscar(fp, m.confirmar);
            if (h != null) {
                pintarConfirmar(m, fp, h);
                Marco.rellenar(inv);
                return;
            }
            m.confirmar = null;
        }

        // La pieza, en el centro.
        if (m.pieza == null) {
            inv.setItem(PIEZA, Marco.icono(Material.ARMOR_STAND, Component.text("Pon aquí la pieza", Paleta.DETALLE),
                    lorePiezaVacia(), false));
            m.acciones.put(PIEZA, "falta-pieza");
        } else {
            inv.setItem(PIEZA, conLineas(m.pieza, List.of(Marco.accion("Clic para devolvértela"))));
            m.acciones.put(PIEZA, "pieza");
        }
        // La gema que se va a engarzar, a la izquierda.
        if (m.gema == null) {
            inv.setItem(GEMA, Marco.icono(Material.GRAY_DYE, Component.text("Pon aquí la gema", Paleta.DETALLE), List.of(
                    Marco.texto("Toca una gema de Calamity"),
                    Marco.texto("en tu inventario."),
                    Component.empty(),
                    Marco.tenue("La Gema de Calamidad se compra"),
                    Marco.tenue("en la Forja; las demás las"),
                    Marco.tenue("sueltan los minijefes.")), false));
            m.acciones.put(GEMA, "falta-gema");
        } else {
            inv.setItem(GEMA, conLineas(m.gema, List.of(Marco.accion("Clic para devolvértela"))));
            m.acciones.put(GEMA, "gema");
        }
        // Engarzar, a la derecha.
        String motivo = m.pieza == null ? Engarce.SIN_PIEZA : m.gema == null ? Engarce.SIN_GEMA
                : Engarce.motivoEngarce(fp, fg, tiposPieza(), tiposGema(), EngarceMmo.sinColor());
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.texto("La gema ocupa un hueco libre de su"));
        lore.add(Marco.texto("color y suma sus bonos a la pieza."));
        lore.add(Component.empty());
        lore.add(Component.text("Es gratis.", Paleta.BIEN));
        lore.add(Component.empty());
        lore.add(motivo == null ? Marco.accion("Clic para engarzarla") : Marco.porQueNo(Engarce.texto(motivo, fp, fg)));
        inv.setItem(ENGARZAR, Marco.icono(Material.SMITHING_TABLE,
                Component.text("Engarzar", motivo == null ? Paleta.DETALLE : Paleta.TENUE), lore, motivo == null));
        m.acciones.put(ENGARZAR, motivo == null ? "engarzar" : "no:" + motivo);

        // Los huecos de la pieza, alrededor. El que recibira la gema puesta, con brillo.
        if (fp != null) {
            int destino = motivo == null && fg != null ? Engarce.indiceHueco(fp, fg.colorGema(), EngarceMmo.sinColor()) : -1;
            List<Engarce.Hueco> huecos = fp.huecos();
            int[] casillas = anillo(huecos.size());
            for (int i = 0; i < casillas.length; i++) {
                Engarce.Hueco h = huecos.get(i);
                inv.setItem(casillas[i], icono(h, i == destino));
                if (!h.libre()) m.acciones.put(casillas[i], "hueco:" + h.uuid());
            }
        }
        Marco.rellenar(inv);
    }

    /** El lore del hueco de la pieza vacio: como se pone y que piezas llevan hueco. */
    private List<Component> lorePiezaVacia() {
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.texto("Toca la pieza en tu inventario"));
        lore.add(Marco.texto("y se colocará aquí. Sus huecos"));
        lore.add(Marco.texto("salen alrededor."));
        if (!conHuecos.isEmpty()) {
            lore.add(Component.empty());
            lore.add(Marco.tenue("Llevan hueco de gema:"));
            for (String l : Entregas.partir(String.join(", ", conHuecos) + ".", 34)) lore.add(Marco.tenue(l));
        }
        lore.add(Component.empty());
        lore.add(Marco.tenue("Si la llevas puesta, quítatela antes."));
        return lore;
    }

    /**
     * Un hueco: libre, un cristal (con brillo si es donde entrara la gema puesta); ocupado, la gema
     * de verdad (con su lore de MMOItems) y como quitarla.
     */
    private ItemStack icono(Engarce.Hueco h, boolean destino) {
        if (h.libre()) {
            List<Component> lore = new ArrayList<>();
            lore.add(Marco.dato("Color", h.color()));
            lore.add(Component.empty());
            if (destino) {
                lore.add(Component.text("Aquí entrará la gema.", Paleta.BIEN));
                lore.add(Marco.tenue("Toca Engarzar."));
            } else {
                lore.add(Marco.tenue("Pon una gema de este color"));
                lore.add(Marco.tenue("y toca Engarzar."));
            }
            return Marco.icono(Material.GLASS, Component.text("Hueco libre", Paleta.DETALLE), lore, destino);
        }
        List<Component> extra = List.of(
                Marco.dato("Color del hueco", h.color()),
                Component.text("Si la quitas, se rompe.", Paleta.AVISO),
                Component.empty(),
                Marco.accion("Clic para quitarla"));
        ItemStack gema = h.gemaEnlace() == null ? null : EngarceMmo.crear(h.gemaEnlace());
        if (gema != null) return conLineas(gema, extra);
        List<Component> lore = new ArrayList<>(extra);
        return Marco.icono(Material.EMERALD, Component.text(h.gema() == null || h.gema().isEmpty() ? "Gema" : h.gema(),
                Paleta.DETALLE), lore, false);
    }

    /**
     * La pantalla de confirmar, en la misma ventana: la pieza sigue en el centro y la gema que se
     * rompe en su mismo hueco; el resto se apaga. Nada sale ni entra al cambiar.
     */
    private void pintarConfirmar(Mesa m, Engarce.Ficha fp, Engarce.Hueco h) {
        Inventory inv = m.inv;
        inv.setItem(PIEZA, conLineas(m.pieza, List.of()));
        int[] casillas = anillo(fp.huecos().size());
        int i = fp.huecos().indexOf(h);
        int casilla = i >= 0 && i < casillas.length ? casillas[i] : 13;
        ItemStack gema = h.gemaEnlace() == null ? null : EngarceMmo.crear(h.gemaEnlace());
        List<Component> aviso = List.of(
                Component.text("¿Quitar esta gema?", Paleta.AVISO),
                Marco.texto("Se rompe: no vuelve a tu inventario."),
                Marco.texto("El hueco queda libre para otra."));
        inv.setItem(casilla, gema != null ? conLineas(gema, aviso)
                : Marco.icono(Material.EMERALD, Component.text(h.gema() == null ? "Gema" : h.gema(), Paleta.DETALLE), aviso, false));
        ItemStack no = Marco.icono(Material.RED_CONCRETE, Component.text("✘ Cancelar", Marco.NO), List.of(
                Marco.tenue("La gema se queda donde está.")), false);
        ItemStack si = Marco.icono(Material.LIME_CONCRETE, Component.text("✔ Quitarla y romperla", Marco.SI), List.of(
                Marco.texto("La gema se pierde."),
                Marco.texto("El hueco queda libre."),
                Component.empty(),
                Marco.accion("Clic para quitarla")), false);
        for (int c : C_NO) {
            inv.setItem(c, no);
            m.acciones.put(c, "no");
        }
        for (int c : C_SI) {
            inv.setItem(c, si);
            m.acciones.put(c, "si");
        }
    }

    private static Engarce.Hueco buscar(Engarce.Ficha f, String uuid) {
        for (Engarce.Hueco h : f.huecos()) if (!h.libre() && uuid.equals(h.uuid())) return h;
        return null;
    }

    /** Copia del objeto (su nombre y su lore de MMOItems tal cual) con unas lineas nuestras debajo. */
    private static ItemStack conLineas(ItemStack base, List<Component> lineas) {
        ItemStack it = base.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta == null || lineas.isEmpty()) return it;
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        for (Component c : lineas) lore.add(c.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack ayuda() {
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.texto("1. Toca la pieza en tu inventario:"));
        lore.add(Marco.texto("   va al centro y sus huecos, alrededor."));
        lore.add(Marco.texto("2. Toca una gema de Calamity:"));
        lore.add(Marco.texto("   va a la izquierda."));
        lore.add(Marco.texto("3. Toca Engarzar. Es gratis."));
        lore.add(Component.empty());
        lore.add(Marco.tenue("Para quitar una gema, toca su hueco."));
        lore.add(Marco.tenue("Se rompe al quitarla y el hueco"));
        lore.add(Marco.tenue("queda libre para otra."));
        lore.add(Component.empty());
        lore.add(Marco.tenue("Al cerrar, lo que hayas puesto"));
        lore.add(Marco.tenue("vuelve a tu inventario."));
        return Marco.icono(Material.KNOWLEDGE_BOOK, Component.text("¿Cómo funciona?", Paleta.MARCA), lore, false);
    }

    // ================================================================= clics

    @EventHandler(priority = EventPriority.LOWEST)
    public void alClic(InventoryClickEvent e) {
        Inventory arriba = e.getView().getTopInventory();
        if (!(arriba.getHolder(false) instanceof Mesa m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || m.cerrada || !p.getUniqueId().equals(m.jugador)) return;
        ClickType c = e.getClick();
        // Solo tocar: numeros del teclado, doble clic, soltar o la tecla F no hacen nada aqui.
        if (c != ClickType.LEFT && c != ClickType.RIGHT && c != ClickType.SHIFT_LEFT && c != ClickType.SHIFT_RIGHT) return;
        int raw = e.getRawSlot();
        if (raw < 0) return;
        if (raw < arriba.getSize()) {
            hc.seguro("engarzador", () -> arriba(p, m, raw));
        } else if (e.getClickedInventory() != null && e.getClickedInventory().getType() == InventoryType.PLAYER) {
            // La de abajo es la suya (sin armadura ni mano izquierda: esas no salen en esta vista).
            int slot = e.getSlot();
            PlayerInventory pi = p.getInventory();
            hc.seguro("engarzador", () -> abajo(p, m, pi, slot));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Mesa) e.setCancelled(true);
    }

    /** Un toque en el inventario del jugador: si es gema va a la gema, si no, a la pieza. */
    private void abajo(Player p, Mesa m, PlayerInventory pi, int slot) {
        ItemStack it = pi.getItem(slot);
        if (it == null || it.getType().isAir()) return;
        m.confirmar = null;
        if (!PuenteMmo.disponible()) {
            no(p, "Lior no puede trabajar ahora mismo.");
            return;
        }
        Engarce.Ficha f = EngarceMmo.leer(it);
        if (f != null && f.esGema()) {
            String motivo = Engarce.motivoGema(f, tiposGema());
            if (motivo != null) {
                no(p, Engarce.texto(motivo, null, f));
                pintar(m);
                return;
            }
            ItemStack vieja = m.gema;
            m.gema = tomarUna(pi, slot, it);
            devolver(p, vieja);
            Marco.sonar(p, "block.amethyst_block.hit", 0.8f, 1.2f);
        } else {
            String motivo = Engarce.motivoPieza(f, tiposPieza());
            if (motivo != null) {
                no(p, Engarce.texto(motivo, f, null));
                pintar(m);
                return;
            }
            ItemStack vieja = m.pieza;
            m.pieza = tomarUna(pi, slot, it);
            devolver(p, vieja);
            Marco.sonar(p, "item.armor.equip_netherite", 0.8f, 1.0f);
        }
        pintar(m);
    }

    /** Saca una unidad de esa casilla del inventario y la devuelve (el resto se queda). */
    private static ItemStack tomarUna(PlayerInventory pi, int slot, ItemStack it) {
        ItemStack una = it.asOne();
        if (it.getAmount() > 1) {
            ItemStack resto = it.clone();
            resto.setAmount(it.getAmount() - 1);
            pi.setItem(slot, resto);
        } else {
            pi.setItem(slot, null);
        }
        return una;
    }

    private void arriba(Player p, Mesa m, int casilla) {
        String a = m.acciones.get(casilla);
        if (a == null) return;
        switch (a) {
            case "cerrar" -> tarea(p::closeInventory);
            case "pieza" -> {
                ItemStack it = m.pieza;
                m.pieza = null;
                m.confirmar = null;
                devolver(p, it);
                Marco.sonidoPestana(p);
                pintar(m);
            }
            case "gema" -> {
                ItemStack it = m.gema;
                m.gema = null;
                devolver(p, it);
                Marco.sonidoPestana(p);
                pintar(m);
            }
            case "falta-pieza" -> no(p, Engarce.texto(Engarce.SIN_PIEZA, null, null));
            case "falta-gema" -> no(p, Engarce.texto(Engarce.SIN_GEMA, null, null));
            case "engarzar" -> {
                if (espera(p)) return;
                engarzar(p, m);
            }
            case "no" -> {
                m.confirmar = null;
                Marco.sonidoPestana(p);
                pintar(m);
            }
            case "si" -> {
                if (espera(p)) return;
                quitar(p, m);
            }
            default -> {
                if (a.startsWith("no:")) {
                    no(p, Engarce.texto(a.substring(3), m.pieza == null ? null : EngarceMmo.leer(m.pieza),
                            m.gema == null ? null : EngarceMmo.leer(m.gema)));
                } else if (a.startsWith("hueco:")) {
                    String uuid = a.substring(6);
                    if (uuid.isEmpty()) {
                        no(p, "Esa gema es de una versión vieja y no se puede quitar.");
                        return;
                    }
                    m.confirmar = uuid;
                    Marco.sonar(p, "block.note_block.bass", 0.6f, 0.8f);
                    pintar(m);
                }
            }
        }
    }

    private boolean espera(Player p) {
        long ahora = System.currentTimeMillis();
        Long antes = ultimoClic.get(p.getUniqueId());
        if (antes != null && ahora - antes < ESPERA_MS) return true;
        ultimoClic.put(p.getUniqueId(), ahora);
        return false;
    }

    private void engarzar(Player p, Mesa m) {
        Engarce.Ficha fp = m.pieza == null ? null : EngarceMmo.leer(m.pieza);
        Engarce.Ficha fg = m.gema == null ? null : EngarceMmo.leer(m.gema);
        String motivo = m.pieza == null ? Engarce.SIN_PIEZA : m.gema == null ? Engarce.SIN_GEMA
                : Engarce.motivoEngarce(fp, fg, tiposPieza(), tiposGema(), EngarceMmo.sinColor());
        if (motivo != null) {
            no(p, Engarce.texto(motivo, fp, fg));
            pintar(m);
            return;
        }
        String nombrePieza = nombre(m.pieza), nombreGema = nombre(m.gema);
        Engarce.Resultado r = EngarceMmo.engarzar(p, m.pieza, m.gema);
        switch (r.estado()) {
            case HECHO -> {
                m.pieza = r.pieza();
                m.gema = null;
                p.sendMessage(ComandoCalamity.mensaje(Component.text("Listo: tu ", Paleta.TEXTO)
                        .append(Component.text(nombrePieza, Paleta.DETALLE))
                        .append(Component.text(" ya lleva la ", Paleta.TEXTO))
                        .append(Component.text(nombreGema, Paleta.DETALLE))
                        .append(Component.text(".", Paleta.TEXTO))));
                Marco.sonar(p, "block.smithing_table.use", 1.0f, 0.9f);
                Marco.sonar(p, "block.amethyst_block.chime", 1.0f, 1.2f);
                anotar("engarce", p, fp, fg);
            }
            case ROTA -> {
                m.gema = null;
                p.sendMessage(Paleta.aviso("La gema se ha roto al engarzarla. La pieza sigue igual."));
                Marco.sonar(p, "entity.item.break", 1.0f, 1.0f);
                anotar("engarce-roto", p, fp, fg);
            }
            case NADA -> no(p, "No se ha podido engarzar" + (r.detalle() == null ? "." : ": " + r.detalle() + "."));
        }
        pintar(m);
    }

    private void quitar(Player p, Mesa m) {
        String uuid = m.confirmar;
        m.confirmar = null;
        if (m.pieza == null || uuid == null) {
            pintar(m);
            return;
        }
        Engarce.Ficha fp = EngarceMmo.leer(m.pieza);
        Engarce.Hueco h = fp == null ? null : buscar(fp, uuid);
        Engarce.Resultado r = EngarceMmo.quitar(m.pieza, uuid);
        if (r.estado() == Engarce.Estado.HECHO) {
            m.pieza = r.pieza();
            p.sendMessage(ComandoCalamity.mensaje("Gema quitada. Se ha roto y el hueco queda libre para otra."));
            Marco.sonar(p, "block.glass.break", 0.9f, 1.1f);
            hc.plugin().bitacora().anotar("desengarce", p.getName(), fp == null ? "-" : fp.enlace(),
                    h == null || h.gemaEnlace() == null ? "-" : h.gemaEnlace());
        } else {
            no(p, "No se ha podido quitar" + (r.detalle() == null ? "." : ": " + r.detalle() + "."));
        }
        pintar(m);
    }

    private void anotar(String que, Player p, Engarce.Ficha fp, Engarce.Ficha fg) {
        hc.plugin().bitacora().anotar(que, p.getName(), fp == null ? "-" : fp.enlace(), fg == null ? "-" : fg.enlace());
        Telemetria tel = hc.telemetria();
        if (tel == null) return;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("pieza", fp == null ? "-" : fp.enlace());
        c.put("gema", fg == null ? "-" : fg.enlace());
        hc.seguro("telemetria", () -> tel.suceso(que, p, c));
    }

    private static String nombre(ItemStack it) {
        if (it == null) return "";
        ItemMeta meta = it.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) return Hardcore.plano(meta.displayName());
        return it.getType().getKey().getKey().replace('_', ' ');
    }

    private void no(Player p, String texto) {
        p.sendMessage(Paleta.aviso(texto));
        Marco.sonidoNo(p);
    }

    /** Lo que se haga despues del clic (cerrar la ventana dentro del evento deja objetos fantasma). */
    private void tarea(Runnable r) {
        hc.plugin().getServer().getScheduler().runTask(hc.plugin(), () -> hc.seguro("engarzador", r));
    }

    // ================================================================= devolver

    /** Al inventario; lo que no quepa, a sus pies (y se le dice). */
    private static void devolver(Player p, ItemStack it) {
        if (it == null || it.getType().isAir()) return;
        Map<Integer, ItemStack> sobra = p.getInventory().addItem(it);
        if (sobra.isEmpty()) return;
        for (ItemStack s : sobra.values()) p.getWorld().dropItem(p.getLocation(), s);
        p.sendMessage(ComandoCalamity.mensaje("No te cabía en el inventario: lo tienes a tus pies."));
    }

    /** Devuelve lo que hay en la mesa y la da por cerrada. Se puede llamar varias veces: solo la primera hace algo. */
    private void vaciar(Player p, Mesa m) {
        if (m.cerrada) return;
        m.cerrada = true;
        abiertas.remove(m.jugador, m);
        ItemStack a = m.pieza, b = m.gema;
        m.pieza = null;
        m.gema = null;
        devolver(p, a);
        devolver(p, b);
    }

    @EventHandler
    public void alCerrar(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof Mesa m && e.getPlayer() instanceof Player p) {
            hc.seguro("engarzador", () -> vaciar(p, m));
        }
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        ultimoClic.remove(e.getPlayer().getUniqueId());
        Mesa m = abiertas.get(e.getPlayer().getUniqueId());
        if (m != null) hc.seguro("engarzador", () -> vaciar(e.getPlayer(), m));
    }

    /**
     * Morir con la ventana abierta: lo de la mesa cuenta como si lo llevara encima. Con
     * keepInventory vuelve al inventario; sin el, va a lo que suelta (y ahi mandan las reglas de
     * Calamity como con todo lo demas). LOWEST: antes que nadie reparta lo que suelta.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void alMorir(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        Mesa m = abiertas.get(p.getUniqueId());
        if (m == null || m.cerrada) return;
        m.cerrada = true;
        abiertas.remove(p.getUniqueId(), m);
        for (ItemStack it : new ItemStack[]{m.pieza, m.gema}) {
            if (it == null || it.getType().isAir()) continue;
            if (e.getKeepInventory()) {
                for (ItemStack s : p.getInventory().addItem(it).values()) e.getDrops().add(s);
            } else {
                e.getDrops().add(it);
            }
        }
        m.pieza = null;
        m.gema = null;
    }

    // ================================================================= comando

    /** La receta de Citizens de Lior (la de todos los NPCs la dice /calamity npcs). */
    static List<String> receta() {
        return List.of(
                "/npc create Lior",
                Npcs.clic(Npcs.Tipo.ENGARZADOR));
    }

    // ================================================================= autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        Set<String> tp = tiposPieza(), tg = tiposGema();
        h.ok("tipos de pieza: CALAMITY y CALAMITY_ARMAS " + tp, tp.contains("CALAMITY") && tp.contains("CALAMITY_ARMAS"));
        h.ok("las gemas no cuentan como pieza", !tp.contains("CALAMITY_GEMAS"));
        h.ok("tipos de gema: CALAMITY_GEMAS " + tg, tg.contains("CALAMITY_GEMAS"));
        h.ok("al actualizar se conservan las marcas de piezas y gemas", tiposCalamity().containsAll(tp) && tiposCalamity().containsAll(tg));
        h.ok("y las de la Placa del Vigilante (CALAMITY_MATERIALES), que no es pieza ni gema",
                tiposCalamity().contains("CALAMITY_MATERIALES") && !tp.contains("CALAMITY_MATERIALES") && !tg.contains("CALAMITY_MATERIALES"));

        // Las reglas con fichas inventadas (sin MMOItems).
        String sc = "Sin color";
        Engarce.Hueco libre = new Engarce.Hueco("Calamidad", null, null, null);
        Engarce.Hueco ocupado = new Engarce.Hueco("Calamidad", "Gema de Calamidad", "CALAMITY_GEMAS.GEMA", "u-1");
        Engarce.Ficha yelmo = new Engarce.Ficha("CALAMITY", "YELMO", false, null, List.of(libre));
        Engarce.Ficha lleno = new Engarce.Ficha("CALAMITY", "YELMO", false, null, List.of(ocupado));
        Engarce.Ficha grebas = new Engarce.Ficha("CALAMITY", "GREBAS", false, null, List.of());
        Engarce.Ficha ajena = new Engarce.Ficha("SWORD", "KATANA", false, null, List.of(libre));
        Engarce.Ficha incolora = new Engarce.Ficha("CALAMITY_ARMAS", "HACHA", false, null, List.of(new Engarce.Hueco(sc, null, null, null)));
        Engarce.Ficha gema = new Engarce.Ficha("CALAMITY_GEMAS", "GEMA", true, "Calamidad", List.of());
        Engarce.Ficha roja = new Engarce.Ficha("CALAMITY_GEMAS", "ROJA", true, "Rojo", List.of());
        Engarce.Ficha rubi = new Engarce.Ficha("GEM_STONE", "RUBI", true, "Calamidad", List.of());
        Set<String> fp = Set.of("CALAMITY", "CALAMITY_ARMAS"), fg = Set.of("CALAMITY_GEMAS");
        h.igual("gema de Calamity en hueco libre de su color", null, Engarce.motivoEngarce(yelmo, gema, fp, fg, sc));
        h.igual("pieza sin huecos", Engarce.SIN_HUECOS, Engarce.motivoEngarce(grebas, gema, fp, fg, sc));
        h.igual("pieza con el hueco lleno", Engarce.LLENA, Engarce.motivoEngarce(lleno, gema, fp, fg, sc));
        h.igual("gema que no es de Calamity", Engarce.GEMA_AJENA, Engarce.motivoEngarce(yelmo, rubi, fp, fg, sc));
        h.igual("gema de otro color", Engarce.COLOR, Engarce.motivoEngarce(yelmo, roja, fp, fg, sc));
        h.igual("hueco sin color admite cualquiera", null, Engarce.motivoEngarce(incolora, roja, fp, fg, sc));
        h.igual("pieza que no es de Calamity", Engarce.PIEZA_AJENA, Engarce.motivoEngarce(ajena, gema, fp, fg, sc));
        h.igual("algo que no es de MMOItems como pieza", Engarce.NO_MMO, Engarce.motivoPieza(null, fp));
        h.igual("una gema donde va la pieza", Engarce.ES_GEMA, Engarce.motivoPieza(gema, fp));
        h.igual("algo que no es gema donde va la gema", Engarce.NO_ES_GEMA, Engarce.motivoGema(yelmo, fg));
        h.igual("una pieza llena se acepta (para quitarle la gema)", null, Engarce.motivoPieza(lleno, fp));
        h.igual("el hueco donde entra", "Calamidad", Engarce.hueco(yelmo, "Calamidad", sc));
        Engarce.Ficha mixta = new Engarce.Ficha("CALAMITY", "MIXTA", false, null,
                List.of(ocupado, new Engarce.Hueco("Rojo", null, null, null), libre));
        h.igual("el hueco donde entra: el primero libre de su color", 2, Engarce.indiceHueco(mixta, "Calamidad", sc));
        h.igual("sin hueco de su color, ninguno", -1, Engarce.indiceHueco(lleno, "Calamidad", sc));
        String color = Engarce.texto(Engarce.COLOR, yelmo, roja);
        h.ok("el aviso de color dice los dos colores (" + color + ")", color.contains("Rojo") && color.contains("Calamidad"));
        for (String mot : List.of(Engarce.SIN_PIEZA, Engarce.SIN_GEMA, Engarce.NO_MMO, Engarce.ES_GEMA, Engarce.PIEZA_AJENA,
                Engarce.SIN_HUECOS, Engarce.LLENA, Engarce.NO_ES_GEMA, Engarce.GEMA_AJENA, Engarce.COLOR)) {
            String t = Engarce.texto(mot, yelmo, roja);
            h.ok("aviso de " + mot + " con frase propia", !t.isBlank() && !t.contains("null") && !t.equals("Ahora mismo no se puede."));
        }
        h.ok("la receta abre a Lior", receta().get(receta().size() - 1).endsWith("calamity open <p> gemsetter"));
        h.igual("la receta crea a Lior, por su nombre", "/npc create Lior", receta().get(0));
        h.igual("el titulo es el del lugar", "CALAMITY | Engarce", Marco.T_ENGARZADOR.texto());
        h.ok("el titulo cabe en la ventana", Marco.T_ENGARZADOR.ancho() <= Marco.ANCHO_TITULO);

        probarDisposicion(h);
        probarPintado(h);
        probarLineaLigado(h);

        if (!PuenteMmo.disponible()) {
            h.ok("sin MMOItems en este servidor: las pruebas con objetos reales se hacen donde esté", true);
            return h.lineas();
        }
        Player p = null;
        for (Player x : Bukkit.getOnlinePlayers()) {
            p = x;
            break;
        }
        if (p == null) {
            h.ok("con objetos reales: hace falta un jugador conectado (MMOItems engarza a nombre de alguien);"
                    + " entra al servidor y repite", false);
            return h.lineas();
        }
        conObjetosReales(h, p, tp, tg);
        return h.lineas();
    }

    /** 1.12.3 · La disposicion pedida por Dosa: la pieza al centro y sus huecos alrededor, sin pisar nada. */
    static void probarDisposicion(Autotest.Hoja h) {
        h.igual("la ayuda, arriba en el centro", 4, AYUDA);
        h.igual("Cerrar, abajo en el centro", Marco.abajo(54), CERRAR);
        h.igual("la pieza, en el centro de las filas de contenido", 4, PIEZA % 9);
        h.ok("la pieza no es marco", !Marco.esBorde(PIEZA, 54));
        h.ok("gema y Engarzar en la fila de la pieza, a la misma distancia",
                GEMA / 9 == PIEZA / 9 && ENGARZAR / 9 == PIEZA / 9 && PIEZA - GEMA == ENGARZAR - PIEZA);
        h.ok("gema y Engarzar no tocan la pieza (entre medias van los huecos)", !alrededor(GEMA) && !alrededor(ENGARZAR));
        Set<Integer> fijas = new LinkedHashSet<>(List.of(AYUDA, CERRAR, PIEZA, GEMA, ENGARZAR));
        for (int c : C_NO) fijas.add(c);
        for (int c : C_SI) fijas.add(c);
        h.igual("las casillas fijas no se pisan entre si", 5 + C_NO.length + C_SI.length, fijas.size());
        boolean todos = true, simetricos = true, distintos = true, libres = true, enOrden = true;
        for (int n = 0; n <= MAX_HUECOS; n++) {
            int[] a = anillo(n);
            todos &= a.length == n;
            Set<Integer> vistas = new LinkedHashSet<>();
            int antes = -1;
            for (int c : a) {
                distintos &= vistas.add(c);
                todos &= alrededor(c);
                libres &= !fijas.contains(c) && !Marco.esBorde(c, 54);
                enOrden &= c > antes;
                antes = c;
            }
            // Simetrico respecto a la columna de la pieza: cada hueco tiene su espejo.
            for (int c : a) simetricos &= vistas.contains(c - (c % 9) + 2 * (PIEZA % 9) - c % 9);
        }
        h.ok("de 0 a 8 huecos: cada uno en una de las ocho casillas alrededor de la pieza", todos);
        h.ok("los huecos no se repiten", distintos);
        h.ok("los huecos no pisan la ayuda, Cerrar, la gema, Engarzar ni los botones de confirmar", libres);
        h.ok("los huecos van en orden de lectura (como en la ficha)", enOrden);
        h.ok("los huecos, simetricos a los dos lados de la pieza", simetricos);
        h.ok("uno solo, encima de la pieza", anillo(1).length == 1 && anillo(1)[0] == PIEZA - 9);
        h.igual("con mas de 8 se ensenan 8", MAX_HUECOS, anillo(12).length);
        h.igual("el anillo entero son las ocho de alrededor", 8, anillo(MAX_HUECOS).length);
    }

    /**
     * 1.12.3 · El menu de verdad (Bukkit), sin MMOItems ni jugador: una mesa vacia pinta la ayuda, Cerrar,
     * la pieza y la gema por poner, Engarzar apagado y el marco negro en todo lo demas; ningun hueco.
     */
    private void probarPintado(Autotest.Hoja h) {
        Mesa m = new Mesa(Autotest.sintetico(8));
        m.inv = hc.plugin().getServer().createInventory(m, 54, Marco.T_ENGARZADOR.componente());
        pintar(m);
        Inventory inv = m.inv;
        h.igual("vacio: la ayuda", Material.KNOWLEDGE_BOOK, tipo(inv, AYUDA));
        h.igual("vacio: Cerrar", Material.BARRIER, tipo(inv, CERRAR));
        h.igual("vacio: la pieza por poner, en el centro", Material.ARMOR_STAND, tipo(inv, PIEZA));
        h.igual("vacio: la gema por poner", Material.GRAY_DYE, tipo(inv, GEMA));
        h.igual("vacio: Engarzar", Material.SMITHING_TABLE, tipo(inv, ENGARZAR));
        boolean negro = true;
        for (int i = 0; i < inv.getSize(); i++) {
            if (i == AYUDA || i == CERRAR || i == PIEZA || i == GEMA || i == ENGARZAR) continue;
            negro &= tipo(inv, i) == Material.BLACK_STAINED_GLASS_PANE;
        }
        h.ok("vacio: todo lo demas, marco negro (sin huecos)", negro);
        h.igual("vacio: Cerrar cierra", "cerrar", m.acciones.get(CERRAR));
        h.igual("vacio: tocar la pieza pide ponerla", "falta-pieza", m.acciones.get(PIEZA));
        h.igual("vacio: Engarzar dice por que no", "no:" + Engarce.SIN_PIEZA, m.acciones.get(ENGARZAR));
        h.igual("vacio: solo responden la pieza, la gema, Engarzar y Cerrar", 4, m.acciones.size());
    }

    /** 1.12.3 · Lo que MMOItems rehace pierde la linea "Ligado a X" del lore: se le devuelve, una sola vez. */
    static void probarLineaLigado(Autotest.Hoja h) {
        ItemStack vieja = new ItemStack(Material.PAPER), nueva = new ItemStack(Material.PAPER);
        ItemMeta mv = vieja.getItemMeta();
        List<Component> lore = new ArrayList<>(List.of(Marco.texto("Una pieza.")));
        lore.addAll(Ficha.lineasLigado("Dosa"));
        mv.lore(lore);
        vieja.setItemMeta(mv);
        ItemMeta mn = nueva.getItemMeta();
        mn.lore(List.of(Marco.texto("Una pieza rehecha.")));
        nueva.setItemMeta(mn);
        h.ok("rehecha sin la linea de ligado: se le pone", Ligado.copiarLineaLigado(vieja, nueva));
        h.igual("y dice de quien es", "Dosa", Ficha.ligadoDe(nueva.getItemMeta().lore()));
        h.ok("una segunda vez no la repite", !Ligado.copiarLineaLigado(vieja, nueva));
        h.ok("sin ligado en la vieja no se inventa", !Ligado.copiarLineaLigado(new ItemStack(Material.PAPER), new ItemStack(Material.PAPER)));
    }

    private static Material tipo(Inventory inv, int casilla) {
        ItemStack it = inv.getItem(casilla);
        return it == null ? Material.AIR : it.getType();
    }

    /**
     * Con objetos creados por MMOItems (las plantillas de verdad), a nombre de un jugador
     * conectado. No toca su inventario: engarzar y quitar trabajan sobre copias.
     */
    private void conObjetosReales(Autotest.Hoja h, Player p, Set<String> tp, Set<String> tg) {
        Entregas ent = hc.entregas();
        String idGema = ent == null ? "CALAMITY_GEMAS.GEMA_DE_CALAMIDAD" : ent.idMmo("gema");
        String idYelmo = ent == null ? "CALAMITY.YELMO_DE_CALAMIDAD" : ent.idMmo("yelmo");
        ItemStack gema = PuenteMmo.crear(idGema), yelmo = PuenteMmo.crear(idYelmo);
        String sc = EngarceMmo.sinColor();
        if (gema == null || yelmo == null) {
            h.ok("MMOItems crea la gema (" + idGema + ") y el yelmo (" + idYelmo + ")", false);
            return;
        }
        Engarce.Ficha fg = EngarceMmo.leer(gema);
        h.ok("la Gema es una gema de Calamity para Lior", fg != null && Engarce.motivoGema(fg, tg) == null);
        Engarce.Ficha fy = EngarceMmo.leer(yelmo);
        h.ok("el Yelmo tiene un hueco libre", fy != null && fy.libres().size() >= 1 && fy.ocupados().isEmpty());
        if (fg == null || fy == null) return;
        h.igual("la Gema entra en el Yelmo", null, Engarce.motivoEngarce(fy, fg, tp, tg, sc));

        // Lo que suma la gema: sus stats reales (las que trae la plantilla).
        List<String> stats = statsDe(gema);
        h.ok("la Gema trae stats que sumar " + stats, !stats.isEmpty());

        UUID dueno = Autotest.sintetico(7);
        Ligado.ligar(yelmo, dueno);
        Enchantment prot = ObjetosCalamity.encantamiento("protection");
        int protAntes = prot == null ? 0 : yelmo.getEnchantmentLevel(prot);
        Map<String, Double> antes = new HashMap<>();
        for (String s : stats) antes.put(s, PuenteMmo.stat(yelmo, s));
        int gemasEnInventario = contar(p, idGema);

        Engarce.Resultado r = EngarceMmo.engarzar(p, yelmo, gema);
        h.igual("engarzar en el Yelmo" + (r.detalle() == null ? "" : " (" + r.detalle() + ")"), Engarce.Estado.HECHO, r.estado());
        if (r.estado() != Engarce.Estado.HECHO) return;
        ItemStack con = r.pieza();
        for (String s : stats) {
            h.cerca("engarzar suma " + s + " de la gema", antes.get(s) + PuenteMmo.stat(gema, s), PuenteMmo.stat(con, s), 1e-6);
        }
        Engarce.Ficha fc = EngarceMmo.leer(con);
        h.ok("el hueco queda ocupado", fc != null && fc.libres().isEmpty() && fc.ocupados().size() == 1);
        h.igual("la gema engarzada es la Gema de Calamidad", idGema,
                fc == null || fc.ocupados().isEmpty() ? null : fc.ocupados().get(0).gemaEnlace());
        h.igual("sigue siendo el Yelmo", idYelmo, PuenteMmo.enlace(con));
        h.igual("sigue ligado a su dueño", dueno, Ligado.duenoDe(con));
        if (prot != null) h.igual("conserva sus encantamientos", protAntes, con.getEnchantmentLevel(prot));
        h.igual("una segunda gema: no quedan huecos", Engarce.LLENA, Engarce.motivoEngarce(fc, fg, tp, tg, sc));
        h.igual("MMOItems tampoco la mete a la fuerza", Engarce.Estado.NADA, EngarceMmo.engarzar(p, con, gema).estado());

        // Quitar: vacia el hueco, resta lo que daba y la gema no vuelve.
        String uuid = fc == null || fc.ocupados().isEmpty() ? "" : fc.ocupados().get(0).uuid();
        Engarce.Resultado q = EngarceMmo.quitar(con, uuid);
        h.igual("quitar la gema" + (q.detalle() == null ? "" : " (" + q.detalle() + ")"), Engarce.Estado.HECHO, q.estado());
        if (q.estado() == Engarce.Estado.HECHO) {
            ItemStack sin = q.pieza();
            for (String s : stats) h.cerca("quitar resta " + s, antes.get(s), PuenteMmo.stat(sin, s), 1e-6);
            Engarce.Ficha fs = EngarceMmo.leer(sin);
            h.ok("el hueco queda libre", fs != null && fs.ocupados().isEmpty() && fs.libres().size() == fy.libres().size());
            h.igual("sigue ligado tras quitarla", dueno, Ligado.duenoDe(sin));
            h.igual("la gema no vuelve al inventario", gemasEnInventario, contar(p, idGema));
            Engarce.Resultado otra = EngarceMmo.engarzar(p, sin, gema);
            h.igual("en el hueco libre entra otra", Engarce.Estado.HECHO, otra.estado());
            if (otra.estado() == Engarce.Estado.HECHO) {
                for (String s : stats) {
                    h.cerca("la otra vuelve a sumar " + s, antes.get(s) + PuenteMmo.stat(gema, s), PuenteMmo.stat(otra.pieza(), s), 1e-6);
                }
            }
        }
        h.igual("quitar una gema que no está", Engarce.Estado.NADA, EngarceMmo.quitar(yelmo, UUID.randomUUID().toString()).estado());

        // 1.12.3 · El menu con la pieza y la gema puestas: el hueco, encima de la pieza y marcado.
        Mesa m = new Mesa(Autotest.sintetico(9));
        m.inv = hc.plugin().getServer().createInventory(m, 54, Marco.T_ENGARZADOR.componente());
        m.pieza = PuenteMmo.crear(idYelmo);
        m.gema = gema.clone();
        pintar(m);
        int[] a = anillo(fy.huecos().size());
        ItemStack hueco = a.length == 0 ? null : m.inv.getItem(a[0]);
        h.ok("menu: el hueco libre del Yelmo, alrededor de la pieza", hueco != null && hueco.getType() == Material.GLASS);
        ItemMeta mh = hueco == null ? null : hueco.getItemMeta();
        h.ok("menu: marcado donde entrara la gema", mh != null && mh.hasEnchantmentGlintOverride()
                && mh.getEnchantmentGlintOverride());
        h.igual("menu: Engarzar listo", "engarzar", m.acciones.get(ENGARZAR));
        h.igual("menu: la pieza en el centro", idYelmo, PuenteMmo.enlace(m.inv.getItem(PIEZA)));
        h.igual("menu: la gema a la izquierda", idGema, PuenteMmo.enlace(m.inv.getItem(GEMA)));
        m.pieza = con;
        m.gema = null;
        pintar(m);
        String accion = a.length == 0 ? null : m.acciones.get(a[0]);
        h.ok("menu: tocar la gema engarzada pide quitarla", accion != null && accion.equals("hueco:" + uuid));
        m.confirmar = uuid;
        pintar(m);
        h.ok("menu: confirmar en la misma ventana, la gema en su hueco",
                a.length > 0 && idGema.equals(PuenteMmo.enlace(m.inv.getItem(a[0]))));
        h.ok("menu: confirmar tiene Cancelar y Quitar", "no".equals(m.acciones.get(C_NO[0])) && "si".equals(m.acciones.get(C_SI[0])));
        h.ok("menu: confirmar no deja engarzar", !m.acciones.containsKey(ENGARZAR) && !m.acciones.containsKey(GEMA));

        // 1.12.3 · Las diez piezas de la Forja llevan su hueco Calamidad (docs/lote-gemas/02).
        ConfigurationSection piezas = hc.cfg().getConfigurationSection("forja.piezas");
        List<String> sinHueco = new ArrayList<>(), noSalen = new ArrayList<>();
        int vistas = 0;
        for (String k : piezas == null ? List.<String>of() : piezas.getKeys(false)) {
            String id = ent == null ? piezas.getString(k) : ent.idMmo("forja:" + k);
            ItemStack it = id == null ? null : PuenteMmo.crear(id);
            if (it == null) {
                noSalen.add(k);
                continue;
            }
            vistas++;
            Engarce.Ficha f = EngarceMmo.leer(it);
            if (f == null || Engarce.motivoEngarce(f, fg, tp, tg, sc) != null) sinHueco.add(k);
        }
        h.igual("MMOItems crea todas las piezas de la Forja", List.of(), noSalen);
        h.ok("las " + vistas + " piezas de la Forja admiten la Gema de Calamidad" + (sinHueco.isEmpty() ? ""
                : "; sin hueco: " + sinHueco + " (aplica docs/lote-gemas/02-huecos-piezas.yml)"), sinHueco.isEmpty() && vistas > 0);
        // Algo de Calamity sin hueco (la pesca de Marea Celeste), si el servidor lo tiene: se rechaza.
        ItemStack capucha = PuenteMmo.crear("CALAMITY.MAREA_CELESTE_CAPUCHA");
        if (capucha != null) {
            h.igual("una pieza sin hueco no admite gemas", Engarce.SIN_HUECOS, Engarce.motivoPieza(EngarceMmo.leer(capucha), tp));
            h.igual("MMOItems tampoco engarza en ella", Engarce.Estado.NADA, EngarceMmo.engarzar(p, capucha, gema).estado());
        }

        // 1.12.3 · Las seis gemas del lote: de Calamity, color Calamidad, entran y suman lo suyo.
        for (String g : Entregas.GEMAS) {
            if (g.equals("gema")) continue;
            String id = ent == null ? null : ent.idMmo(g);
            ItemStack it = id == null ? null : PuenteMmo.crear(id);
            if (it == null) {
                h.ok("MMOItems crea " + g + " (" + id + "); aplica docs/lote-gemas/01-calamity_gemas.yml", false);
                continue;
            }
            Engarce.Ficha f = EngarceMmo.leer(it);
            h.ok(g + ": gema de Calamity de color Calamidad", f != null && Engarce.motivoGema(f, tg) == null
                    && "Calamidad".equals(f.colorGema()));
            List<String> suyas = statsDe(it);
            h.ok(g + ": trae stats " + suyas, !suyas.isEmpty());
            ItemStack base = PuenteMmo.crear(idYelmo);
            Map<String, Double> previo = new HashMap<>();
            for (String s : suyas) previo.put(s, base == null ? 0 : PuenteMmo.stat(base, s));
            Engarce.Resultado rg = base == null ? null : EngarceMmo.engarzar(p, base, it);
            h.igual(g + ": entra en el Yelmo", Engarce.Estado.HECHO, rg == null ? null : rg.estado());
            if (rg != null && rg.estado() == Engarce.Estado.HECHO) {
                for (String s : suyas) {
                    h.cerca(g + ": suma " + s, previo.get(s) + PuenteMmo.stat(it, s), PuenteMmo.stat(rg.pieza(), s), 1e-6);
                }
            }
        }

        // Algo vanilla no es ni pieza ni gema.
        ItemStack esmeralda = new ItemStack(Material.EMERALD);
        h.igual("una esmeralda no es una gema", Engarce.NO_ES_GEMA, Engarce.motivoGema(EngarceMmo.leer(esmeralda), tg));
        h.igual("una espada vanilla no es una pieza de Calamity", Engarce.NO_MMO,
                Engarce.motivoPieza(EngarceMmo.leer(new ItemStack(Material.DIAMOND_SWORD)), tp));
        // Una gema de MMOItems que no es de Calamity, si el servidor tiene alguna.
        String ajena = EngarceMmo.gemaAjena(tg);
        if (ajena == null) {
            h.ok("no hay otras gemas en MMOItems: la gema ajena se prueba solo con fichas", true);
        } else {
            h.igual("una gema de MMOItems que no es de Calamity (" + ajena + ")", Engarce.GEMA_AJENA,
                    Engarce.motivoGema(EngarceMmo.leer(PuenteMmo.crear(ajena)), tg));
        }
        h.ok("conserva las marcas al actualizar (engarzador.conservar-marcas-al-actualizar)",
                actualizacion != null && actualizacion.activo());
    }

    /** Las stats de MMOItems que trae una gema (las que puede dar una de Calamity). */
    private static List<String> statsDe(ItemStack gema) {
        List<String> out = new ArrayList<>();
        for (String s : List.of("PVE_DAMAGE", "UNDEAD_DAMAGE", "MAX_HEALTH", "ATTACK_DAMAGE", "ARMOR", "DEFENSE",
                "DAMAGE_REDUCTION", "CRITICAL_STRIKE_CHANCE", "MOVEMENT_SPEED", "PVP_DAMAGE")) {
            if (PuenteMmo.stat(gema, s) != 0) out.add(s);
        }
        return out;
    }

    private static int contar(Player p, String enlace) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && enlace.equals(PuenteMmo.enlace(it))) n += it.getAmount();
        }
        return n;
    }
}
