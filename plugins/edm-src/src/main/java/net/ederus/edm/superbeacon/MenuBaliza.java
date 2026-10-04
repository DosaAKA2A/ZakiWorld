package net.ederus.edm.superbeacon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.Plataforma;
import net.ederus.edm.comun.menu.MenuUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * El menu de un Super Beacon: "EDERUS | Super Beacon", marco negro, tan alto como haga
 * falta y no mas:
 *
 *   - 27 casillas con hasta siete efectos, 36 con mas (hasta catorce);
 *   - arriba al centro, la ficha: de quien es, su clan, alcance, para quien, cuanto le
 *     queda y, UNA vez, la regla de que los efectos no se suman (se aplica el mayor);
 *   - debajo, un icono por efecto, centrados y sin huecos. Cada uno dice que ganas y
 *     donde, su alcance, para quien y su estado; el activo lleva el color del tipo y brilla;
 *   - en la ultima fila, Ver alcance, Cerrar en el centro y Recoger.
 *
 * El color del tipo (el de su nombre) es el unico acento; etiquetas en gris y texto en
 * blanco. TODO va con clic izquierdo suelto: Bedrock no tiene clic derecho ni shift dentro
 * de un menu. El derecho y el shift de Java hacen lo mismo que el izquierdo. El doble clic
 * se ignora, que si no activaba y desactivaba el mismo efecto de un golpe. Las acciones
 * van un tick despues del clic (cerrar un inventario dentro de su propio evento da
 * problemas) y vuelven a comprobar que la baliza sigue alli y que quien hace clic puede
 * gestionarla: entre el clic y la accion otro pudo picarla.
 */
final class MenuBaliza implements Listener {

    static final int FICHA = 4;

    /** Columnas de una fila (sin las del marco) para n iconos, centrados y simetricos. */
    private static final int[][] FILAS = {
            {}, {4}, {3, 5}, {2, 4, 6}, {1, 3, 5, 7}, {2, 3, 4, 5, 6}, {1, 2, 3, 5, 6, 7}, {1, 2, 3, 4, 5, 6, 7}};

    private static final TextColor GRIS = TextColor.color(0x8A8A8A);

    /** Lo que pasa al hacer clic en un efecto. */
    enum Resultado { ACTIVADO, DESACTIVADO, TOPE, NO_DISPONIBLE, FIJO }

    static final class Vista implements InventoryHolder {
        final UUID baliza;
        Inventory inv;
        /** Casillas de los botones de abajo; dependen del alto del menu. */
        int alcance;
        int cerrar;
        int recoger;
        /** casilla -> clave del efecto. */
        final Map<Integer, String> efectos = new HashMap<>();

        Vista(UUID baliza) {
            this.baliza = baliza;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final SuperBeaconPlugin plugin;
    private final Set<Vista> abiertas = new HashSet<>();

    MenuBaliza(SuperBeaconPlugin plugin) {
        this.plugin = plugin;
    }

    /* ================================================================== abrir */

    void abrir(Player p, Baliza b) {
        TipoBaliza t = plugin.tipo(b.tipo);
        if (t != null) plugin.normalizar(b, t);
        Vista v = new Vista(b.id);
        int tam = tamano(t == null ? 0 : t.efectos.size());
        int abajo = tam - 9;
        v.alcance = abajo + 2;
        v.cerrar = abajo + 4;
        v.recoger = abajo + 6;
        v.inv = Bukkit.createInventory(v, tam, Estilo.titulo("EDERUS",
                plugin.textos().crudo("menu-titulo", "Super Beacon"), SuperBeaconPlugin.SECCION));
        pintar(v, b, t, p);
        // null si otro plugin cancelo la apertura: esa vista no se apunta (se quedaria para siempre).
        if (p.openInventory(v.inv) == null) return;
        abiertas.add(v);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.2f);
    }

    /** 27 casillas con hasta siete efectos (una fila); 36 con mas (dos). */
    static int tamano(int efectos) {
        return efectos > 7 ? 36 : 27;
    }

    /** Las casillas de los efectos: la segunda fila y, con mas de siete, la tercera. */
    static int[] casillas(int n) {
        n = Math.max(0, Math.min(14, n));
        int arriba = n > 7 ? (n + 1) / 2 : n, abajo = n - arriba;
        int[] out = new int[n];
        for (int i = 0; i < arriba; i++) out[i] = 9 + FILAS[arriba][i];
        for (int i = 0; i < abajo; i++) out[arriba + i] = 18 + FILAS[abajo][i];
        return out;
    }

    /* ================================================================= pintar */

    private void pintar(Vista v, Baliza b, TipoBaliza t, Player p) {
        Inventory inv = v.inv;
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, MenuUtil.pane());
        v.efectos.clear();
        inv.setItem(FICHA, ficha(b, t, p));
        String ac = t == null ? "&#D7F3FF" : Presentacion.hex(t.color());
        if (t != null) {
            List<Efecto> lista = new ArrayList<>(t.efectos.values());
            // Un reload que añade efectos con el menu abierto no los saca del marco.
            int[] huecos = casillas(Math.min(lista.size(), tamano(lista.size()) == inv.getSize() ? 14 : 7));
            List<Efecto> activos = plugin.motor().activos(b, t);
            for (int i = 0; i < huecos.length; i++) {
                Efecto e = lista.get(i);
                inv.setItem(huecos[i], icono(p, b, t, e, activos.contains(e), activos.size()));
                v.efectos.put(huecos[i], e.clave());
            }
        }
        SuperBeaconPlugin.TextosBaliza tx = plugin.textos();
        inv.setItem(v.alcance, boton(p, ac, Material.SPYGLASS, tx.crudo("menu-alcance", "&fVer alcance"),
                tx.lista("menu-alcance-lore", List.of("&#C4C4C4Dibuja el borde de su alcance durante",
                        "&#C4C4C410 segundos. Solo lo ves tú.")), tx.crudo("menu-accion-ver", "verlo")));
        inv.setItem(v.cerrar, boton(p, ac, Material.SPRUCE_DOOR, tx.crudo("menu-cerrar", "&fCerrar"), List.of(),
                tx.crudo("menu-accion-salir", "salir")));
        // Al staff (no es suyo) no se le lleva: vuelve a su dueño, y el boton lo dice.
        List<String> loreRecoger = b.esDe(p.getUniqueId())
                ? tx.lista("menu-recoger-lore", List.of("&#C4C4C4Vuelve a tu inventario con todo:",
                        "&#C4C4C4efectos, dueño, clan y vencimiento."))
                : tx.lista("menu-recoger-staff-lore", List.of("&#C4C4C4No es tuyo: vuelve a su dueño, como",
                        "&#C4C4C4con /superbeacon remove."));
        inv.setItem(v.recoger, boton(p, ac, Material.BUNDLE, tx.crudo("menu-recoger", "&fRecoger"), loreRecoger,
                tx.crudo("menu-accion-recoger", "recogerlo")));
    }

    /**
     * La ficha de arriba: de quien es, para quien, cuanto le queda y la regla de que los
     * efectos no se suman, que vale para todos y por eso va aqui y no en cada efecto.
     */
    ItemStack ficha(Baliza b, TipoBaliza t, Player p) {
        SuperBeaconPlugin.TextosBaliza tx = plugin.textos();
        List<Component> lore = new ArrayList<>();
        Material icono = b.material.isItem() ? b.material : Material.BEACON;
        if (t == null) {
            lore.add(tx.linea("objeto-tipo-perdido", "&#FF5C5CSu tipo (%tipo%) ya no existe. Avisa al staff.",
                    "%tipo%", b.tipo));
            return MenuUtil.icon(icono, Estilo.legado("&#D7F3FFSuper Beacon"), lore, false);
        }
        String ac = Presentacion.hex(t.color());
        boolean suya = b.esDe(p.getUniqueId());
        long ahora = System.currentTimeMillis();

        if (t.beneficia == TipoBaliza.Beneficia.CLAN) {
            String clan = plugin.motor().clanDe(b);
            lore.add(clan != null
                    ? tx.linea("menu-de-clan", "%acento%◆ &#C4C4C4Clan [%clan%] · líder %dueno%",
                            "%acento%", ac, "%clan%", clan, "%dueno%", b.duenoTexto())
                    : tx.linea("menu-de-lider", "%acento%◆ &#C4C4C4Líder %dueno% · sin clan",
                            "%acento%", ac, "%dueno%", b.duenoTexto()));
        } else {
            lore.add(tx.linea("menu-de-dueno", "%acento%◆ &#C4C4C4De %dueno%", "%acento%", ac, "%dueno%", b.duenoTexto()));
        }
        if (b.semana > 0) {
            String[] s = Presentacion.semana(b.semana);
            lore.add(tx.linea("menu-semana", "%acento%◆ &#C4C4C4Semana del %desde% al %hasta%",
                    "%acento%", ac, "%desde%", s[0], "%hasta%", s[1]));
        }
        lore.add(raya(tx));
        lore.add(tx.linea("menu-ficha-alcance", "&#8A8A8AAlcance · &f%radio% bloques", "%radio%", String.valueOf(t.radio)));
        lore.add(tx.linea("menu-ficha-para", "&#8A8A8APara · &f%para%", "%para%", para(t.beneficia, suya)));
        if (t.fijo()) {
            lore.add(tx.linea("menu-ficha-todos", "&#8A8A8AEfectos · &ftodos activos"));
        } else {
            lore.add(tx.linea("menu-ficha-elegidos", "&#8A8A8AEfectos · &f%elegidos% de %elegibles% elegidos",
                    "%elegidos%", String.valueOf(plugin.motor().activos(b, t).size()),
                    "%elegibles%", String.valueOf(t.elegibles)));
        }
        if (b.vence <= 0) {
            lore.add(tx.linea("menu-ficha-permanente", "&#8A8A8ADuración · &fpermanente"));
        } else {
            String fecha = Presentacion.fecha(b.vence, plugin.zona(), ahora);
            if (b.vencida(ahora)) {
                lore.add(tx.linea("menu-ficha-apagado", "&#8A8A8AEstado · &fapagado"));
                lore.add(tx.linea("menu-ficha-vencio", "&#8A8A8AVenció · &f%fecha%", "%fecha%", fecha));
            } else {
                lore.add(tx.linea("menu-ficha-quedan", "&#8A8A8AQuedan · %acento%%tiempo%",
                        "%acento%", ac, "%tiempo%", Tiempo.restante(b.vence - ahora)));
                lore.add(tx.linea("menu-ficha-vence", "&#8A8A8AVence · &f%fecha%", "%fecha%", fecha));
            }
        }
        lore.add(raya(tx));
        for (String l : Presentacion.partir(tx.crudo("menu-ficha-regla",
                "&#8A8A8AVarios Super Beacons no suman el mismo efecto: se aplica el mayor."),
                Presentacion.ANCHO)) {
            lore.add(Estilo.legado(l));
        }
        if (!suya) lore.add(tx.linea("menu-staff", "&#8A8A8ALo estás viendo como staff."));
        return MenuUtil.icon(icono, Estilo.legado(t.nombre).decoration(TextDecoration.ITALIC, false), lore, true);
    }

    /** "ti", "tu clan", "todos" (o "su dueño", "su clan" si lo mira el staff). */
    private String para(TipoBaliza.Beneficia b, boolean suya) {
        SuperBeaconPlugin.TextosBaliza tx = plugin.textos();
        return switch (b) {
            case CLAN -> suya ? tx.crudo("menu-para-clan", "tu clan") : tx.crudo("menu-para-clan-ajeno", "su clan");
            case TODOS -> tx.crudo("menu-para-todos", "todos");
            default -> suya ? tx.crudo("menu-para-dueno", "ti") : tx.crudo("menu-para-dueno-ajeno", "su dueño");
        };
    }

    /**
     * Un efecto: que ganas y donde, su alcance, para quien y su estado. Activo, con el color
     * del tipo y brillando; inactivo, en gris; no disponible, apagado y con el motivo.
     */
    private ItemStack icono(Player p, Baliza b, TipoBaliza t, Efecto e, boolean activo, int nActivos) {
        SuperBeaconPlugin.TextosBaliza tx = plugin.textos();
        String ac = Presentacion.hex(t.color());
        String falta = e.falta();
        boolean apagada = b.vencida(System.currentTimeMillis());
        boolean elegido = b.elegidos.contains(e.clave());
        String elegidos = String.valueOf(nActivos), elegibles = String.valueOf(t.elegibles);
        List<Component> lore = new ArrayList<>();

        for (String l : Presentacion.partir(Presentacion.PROSA + e.que(), Presentacion.ANCHO)) lore.add(Estilo.legado(l));
        lore.add(raya(tx));
        lore.add(tx.linea("menu-ficha-alcance", "&#8A8A8AAlcance · &f%radio% bloques", "%radio%", String.valueOf(t.radio)));
        String para = e.clase() != null && !e.clase().porJugador()
                ? tx.crudo("menu-para-zona", "toda la zona") : para(t.beneficia, b.esDe(p.getUniqueId()));
        lore.add(tx.linea("menu-ficha-para", "&#8A8A8APara · &f%para%", "%para%", para));

        String accion = null;
        if (apagada) {
            lore.add(tx.linea("menu-estado-apagado", "&#8A8A8AEstado · &#4E4E4E○ &fApagado, ya venció"));
        } else if (falta != null) {
            lore.add(tx.linea("menu-estado-no-disponible", "&#8A8A8AEstado · &#4E4E4E○ &fNo disponible"));
            lore.add(tx.linea("menu-motivo", "&#8A8A8AMotivo · &f%motivo%", "%motivo%", falta));
        } else if (t.fijo()) {
            lore.add(tx.linea("menu-estado-fijo", "&#8A8A8AEstado · %acento%● &fSiempre activo", "%acento%", ac));
        } else if (activo) {
            lore.add(tx.linea("menu-estado-activo",
                    "&#8A8A8AEstado · %acento%● &fActivo  &#8A8A8A(%elegidos% de %elegibles% elegidos)",
                    "%acento%", ac, "%elegidos%", elegidos, "%elegibles%", elegibles));
        } else {
            lore.add(tx.linea("menu-estado-inactivo",
                    "&#8A8A8AEstado · &#4E4E4E○ &#C4C4C4Inactivo  &#8A8A8A(%elegidos% de %elegibles% elegidos)",
                    "%acento%", ac, "%elegidos%", elegidos, "%elegibles%", elegibles));
        }
        // Lo que hace el clic. Lo fijo no se toca; lo no disponible solo se puede quitar.
        if (!t.fijo()) {
            if (elegido) {
                accion = frase(p, tx.crudo("menu-accion-desactivar", "desactivarlo"));
            } else if (falta == null) {
                accion = nActivos >= t.elegibles
                        ? tx.crudo("menu-efecto-tope", "Desactiva otro para elegir este")
                        : frase(p, tx.crudo("menu-accion-activar", "activarlo"));
            }
        }
        if (accion != null) {
            lore.add(raya(tx));
            lore.add(tx.linea("menu-accion", "%acento%▸ &f%frase%", "%acento%", ac, "%frase%", accion));
        }

        boolean encendido = falta == null && activo && !apagada;
        Material material = falta != null ? Material.GRAY_DYE : e.icono();
        Component nombre = encendido ? Estilo.legado(ac + e.nombrePlano())
                : Estilo.texto(e.nombrePlano(), falta != null ? MenuUtil.DIM : GRIS);
        return MenuUtil.icon(material, nombre.decoration(TextDecoration.ITALIC, false), lore, encendido);
    }

    private ItemStack boton(Player p, String ac, Material material, String nombre, List<String> lineas, String verbo) {
        SuperBeaconPlugin.TextosBaliza tx = plugin.textos();
        List<Component> lore = new ArrayList<>();
        for (String l : lineas) lore.add(Estilo.legado(l));
        if (!lore.isEmpty()) lore.add(raya(tx));
        lore.add(tx.linea("menu-accion", "%acento%▸ &f%frase%", "%acento%", ac, "%frase%", frase(p, verbo)));
        return MenuUtil.icon(material, Estilo.legado(nombre), lore, false);
    }

    /** "Clic para..." en Java, "Toca para..." en Bedrock. */
    private String frase(Player p, String verbo) {
        SuperBeaconPlugin.TextosBaliza tx = plugin.textos();
        String frase = Plataforma.esBedrock(p) ? tx.crudo("menu-toque", "Toca para %accion%")
                : tx.crudo("menu-clic", "Clic para %accion%");
        return frase.replace("%accion%", verbo);
    }

    private static Component raya(SuperBeaconPlugin.TextosBaliza tx) {
        return tx.linea("lore-raya", Presentacion.OSCURO + Presentacion.RAYA);
    }

    /* ============================================================ al dia */

    /** Repinta los menus abiertos de esa baliza (otro eligio un efecto, vencio...). */
    void refrescar(UUID baliza) {
        Baliza b = plugin.registro().porId(baliza);
        if (b == null) return;
        TipoBaliza t = plugin.tipo(b.tipo);
        for (Vista v : new ArrayList<>(abiertas)) {
            if (!v.baliza.equals(baliza)) continue;
            Player p = viendo(v);
            if (p != null) pintar(v, b, t, p);
        }
    }

    void refrescarTodos() {
        for (Vista v : new ArrayList<>(abiertas)) refrescar(v.baliza);
    }

    /** Cada segundo: la cuenta atras de la ficha corre con el menu abierto. */
    void tic() {
        if (abiertas.isEmpty()) return;
        long ahora = System.currentTimeMillis();
        for (Vista v : new ArrayList<>(abiertas)) {
            Baliza b = plugin.registro().porId(v.baliza);
            Player p = viendo(v);
            if (b == null || p == null || b.vence <= 0) continue;
            TipoBaliza t = plugin.tipo(b.tipo);
            if (b.vencida(ahora) && !b.vistaVencida) {
                pintar(v, b, t, p);     // se acaba de apagar: los efectos tambien cambian de aspecto
            } else {
                v.inv.setItem(FICHA, ficha(b, t, p));
            }
        }
    }

    /** La baliza ya no esta (se recogio, desaparecio): fuera todos sus menus. */
    void cerrar(UUID baliza) {
        for (Vista v : new ArrayList<>(abiertas)) {
            if (!v.baliza.equals(baliza)) continue;
            abiertas.remove(v);
            Player p = viendo(v);
            if (p != null) Bukkit.getScheduler().runTask(plugin.core(), () -> {
                if (p.getOpenInventory().getTopInventory() == v.inv) p.closeInventory();
            });
        }
    }

    void cerrarTodos() {
        for (Vista v : new ArrayList<>(abiertas)) {
            Player p = viendo(v);
            if (p != null) p.closeInventory();
        }
        abiertas.clear();
    }

    /** Quien tiene esa vista abierta ahora mismo, o null. */
    private static Player viendo(Vista v) {
        for (org.bukkit.entity.HumanEntity h : v.inv.getViewers()) {
            if (h instanceof Player p) return p;
        }
        return null;
    }

    /* ================================================================= clics */

    @EventHandler
    public void alClic(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Vista v)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        ClickType c = e.getClick();
        if (c != ClickType.LEFT && c != ClickType.RIGHT && c != ClickType.SHIFT_LEFT && c != ClickType.SHIFT_RIGHT) return;
        int slot = e.getSlot();
        Bukkit.getScheduler().runTask(plugin.core(), () -> accion(p, v, slot));
    }

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Vista) e.setCancelled(true);
    }

    @EventHandler
    public void alCerrar(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof Vista v) abiertas.remove(v);
    }

    private void accion(Player p, Vista v, int slot) {
        if (plugin.detenido() || !p.isOnline() || p.getOpenInventory().getTopInventory() != v.inv) return;
        Baliza b = plugin.registro().porId(v.baliza);
        if (b == null) {
            p.closeInventory();
            plugin.textos().manda(p, "ya-no-esta", "&#FF5C5CEse Super Beacon ya no está ahí.");
            return;
        }
        if (!plugin.puedeGestionar(p, b)) {
            p.closeInventory();
            return;
        }
        TipoBaliza t = plugin.tipo(b.tipo);
        if (slot == v.cerrar) {
            p.closeInventory();
        } else if (slot == v.alcance) {
            if (t == null) return;
            p.closeInventory();
            plugin.contorno().mostrar(p, b, t);
            plugin.textos().manda(p, "alcance",
                    "&7Este es su alcance: &f%radio% &7bloques. Lo verás durante 10 segundos.",
                    "%radio%", String.valueOf(t.radio));
        } else if (slot == v.recoger) {
            // El hueco solo hace falta si es suyo: al staff no se le da (va a su dueño).
            if (b.esDe(p.getUniqueId()) && p.getInventory().firstEmpty() == -1) {
                plugin.textos().manda(p, "sin-espacio",
                        "&#FF5C5CNo tienes espacio en el inventario. &7Libera una casilla e inténtalo de nuevo.");
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                return;
            }
            p.closeInventory();
            plugin.entregas().recoger(p, b);
        } else {
            String clave = v.efectos.get(slot);
            if (clave != null && t != null) elegir(p, b, t, clave);
        }
    }

    private void elegir(Player p, Baliza b, TipoBaliza t, String clave) {
        Efecto e = t.efectos.get(clave);
        if (e == null) return;
        plugin.normalizar(b, t);
        String falta = e.falta();
        Resultado r = alternar(b.elegidos, clave, t.elegibles, falta == null);
        SuperBeaconPlugin.TextosBaliza tx = plugin.textos();
        String elegidos = String.valueOf(b.elegidos.size()), elegibles = String.valueOf(t.elegibles);
        switch (r) {
            case FIJO -> tx.manda(p, "efecto-fijo", "&7Este Super Beacon lleva todos sus efectos activos siempre.");
            case TOPE -> {
                tx.manda(p, "efecto-tope",
                        "&#FFB627Ya tienes &f%elegibles% &#FFB627efectos activos. &7Desactiva uno para elegir otro.",
                        "%elegibles%", elegibles);
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            }
            case NO_DISPONIBLE -> {
                tx.manda(p, "efecto-no-disponible", "&#FF5C5C%efecto% no está disponible: &7%motivo%.",
                        "%efecto%", e.nombrePlano(), "%motivo%", falta);
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            }
            case ACTIVADO -> {
                tx.manda(p, "efecto-activado", "&fActivaste &#5CFF7A%efecto%&f. &7(%elegidos% de %elegibles%)",
                        "%efecto%", e.nombrePlano(), "%elegidos%", elegidos, "%elegibles%", elegibles);
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 1.2f);
            }
            case DESACTIVADO -> {
                tx.manda(p, "efecto-desactivado", "&7Desactivaste &f%efecto%&7. (%elegidos% de %elegibles%)",
                        "%efecto%", e.nombrePlano(), "%elegidos%", elegidos, "%elegibles%", elegibles);
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 1.2f);
            }
        }
        if (r == Resultado.ACTIVADO || r == Resultado.DESACTIVADO) {
            b.olvidarCache();
            plugin.registro().marcar();
            plugin.motor().reindexar();
            plugin.hologramas().refrescar(b);
            plugin.anotar("efectos", b.id.toString(), b.tipo, p.getName(), String.join(",", b.elegidos));
            refrescar(b.id);
        }
    }

    /**
     * La regla de elegir, sin estado, para el selftest. Quitar uno elegido se puede
     * siempre (aunque ya no este disponible); poner uno nuevo, si esta disponible y no se
     * llego al tope. Con tope 0 todo va fijo y el clic no cambia nada.
     */
    static Resultado alternar(Set<String> elegidos, String clave, int tope, boolean disponible) {
        if (tope <= 0) return Resultado.FIJO;
        if (elegidos.remove(clave)) return Resultado.DESACTIVADO;
        if (!disponible) return Resultado.NO_DISPONIBLE;
        if (elegidos.size() >= tope) return Resultado.TOPE;
        elegidos.add(clave);
        return Resultado.ACTIVADO;
    }
}
