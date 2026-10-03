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
 * El menu de un Super Beacon: "EDERUS | Super Beacon", 45 casillas, marco negro.
 *
 *   - arriba al centro, la ficha: tipo, dueño, clan, alcance, a quien beneficia y, si
 *     caduca, la cuenta atras (se repinta cada segundo con el menu abierto) y la fecha;
 *   - la fila del medio, un icono por efecto, centrados y sin huecos; con mas de siete,
 *     sigue en la fila de abajo;
 *   - abajo, Ver alcance, Cerrar en el centro y Recoger.
 *
 * TODO va con clic izquierdo suelto: Bedrock no tiene clic derecho ni shift dentro de un
 * menu. El derecho y el shift de Java hacen lo mismo que el izquierdo, nada distinto. El
 * doble clic se ignora, que si no activaba y desactivaba el mismo efecto de un golpe.
 * Las acciones van un tick despues del clic (cerrar un inventario dentro de su propio
 * evento da problemas) y vuelven a comprobar que la baliza sigue alli y que quien hace clic
 * puede gestionarla: entre el clic y la accion otro pudo picarla.
 */
final class MenuBaliza implements Listener {

    static final int TAM = 45;
    static final int FICHA = 4;
    static final int ALCANCE = 38;
    static final int CERRAR = 40;
    static final int RECOGER = 42;

    /** Columnas de una fila (sin las del marco) para n iconos, centrados y simetricos. */
    private static final int[][] FILAS = {
            {}, {4}, {3, 5}, {2, 4, 6}, {1, 3, 5, 7}, {2, 3, 4, 5, 6}, {1, 2, 3, 5, 6, 7}, {1, 2, 3, 4, 5, 6, 7}};

    private static final TextColor VERDE = TextColor.color(0x5CFF7A);

    /** Lo que pasa al hacer clic en un efecto. */
    enum Resultado { ACTIVADO, DESACTIVADO, TOPE, NO_DISPONIBLE, FIJO }

    static final class Vista implements InventoryHolder {
        final UUID baliza;
        Inventory inv;
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
        v.inv = Bukkit.createInventory(v, TAM, Estilo.titulo("EDERUS",
                plugin.textos().crudo("menu-titulo", "Super Beacon"), SuperBeaconPlugin.SECCION));
        pintar(v, b, t, p);
        // null si otro plugin cancelo la apertura: esa vista no se apunta (se quedaria para siempre).
        if (p.openInventory(v.inv) == null) return;
        abiertas.add(v);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.2f);
    }

    /** Las casillas de los efectos: la fila del medio y, con mas de siete, la siguiente. */
    static int[] casillas(int n) {
        n = Math.max(0, Math.min(14, n));
        int arriba = Math.min(7, n), abajo = n - arriba;
        int[] out = new int[n];
        for (int i = 0; i < arriba; i++) out[i] = 18 + FILAS[arriba][i];
        for (int i = 0; i < abajo; i++) out[arriba + i] = 27 + FILAS[abajo][i];
        return out;
    }

    /* ================================================================= pintar */

    private void pintar(Vista v, Baliza b, TipoBaliza t, Player p) {
        Inventory inv = v.inv;
        for (int i = 0; i < TAM; i++) inv.setItem(i, MenuUtil.pane());
        v.efectos.clear();
        inv.setItem(FICHA, ficha(b, t, p));
        if (t != null) {
            List<Efecto> lista = new ArrayList<>(t.efectos.values());
            int[] huecos = casillas(lista.size());
            List<Efecto> activos = plugin.motor().activos(b, t);
            for (int i = 0; i < huecos.length; i++) {
                Efecto e = lista.get(i);
                inv.setItem(huecos[i], icono(p, b, t, e, activos.contains(e)));
                v.efectos.put(huecos[i], e.clave());
            }
        }
        SuperBeaconPlugin.TextosBaliza tx = plugin.textos();
        inv.setItem(ALCANCE, boton(p, Material.SPYGLASS, tx.crudo("menu-alcance", "&fVer alcance"),
                tx.lista("menu-alcance-lore", List.of("&#8A8A8ADibuja el borde de su alcance durante",
                        "&#8A8A8A10 segundos. Solo lo ves tú.")), tx.crudo("menu-accion-ver", "verlo")));
        inv.setItem(CERRAR, boton(p, Material.SPRUCE_DOOR, tx.crudo("menu-cerrar", "&fCerrar"), List.of(),
                tx.crudo("menu-accion-salir", "salir")));
        // Al staff (no es suyo) no se le lleva: vuelve a su dueño, y el boton lo dice.
        List<String> loreRecoger = b.esDe(p.getUniqueId())
                ? tx.lista("menu-recoger-lore", List.of("&#8A8A8AVuelve a tu inventario con todo su estado:",
                        "&#8A8A8Aefectos, dueño, clan y vencimiento."))
                : tx.lista("menu-recoger-staff-lore", List.of("&#8A8A8ANo es tuyo: vuelve a su dueño, como con",
                        "&#8A8A8A/superbeacon remove."));
        inv.setItem(RECOGER, boton(p, Material.BUNDLE, tx.crudo("menu-recoger", "&fRecoger"), loreRecoger,
                tx.crudo("menu-accion-recoger", "recogerlo")));
    }

    /** La ficha de arriba: lo que es la baliza y cuanto le queda. */
    ItemStack ficha(Baliza b, TipoBaliza t, Player p) {
        SuperBeaconPlugin.TextosBaliza tx = plugin.textos();
        List<Component> lore = new ArrayList<>();
        Material icono = b.material.isItem() ? b.material : Material.BEACON;
        if (t == null) {
            lore.add(tx.linea("objeto-tipo-perdido", "&#FF5C5CSu tipo (%tipo%) ya no existe. Avisa al staff.",
                    "%tipo%", b.tipo));
            return MenuUtil.icon(icono, Estilo.legado("&#D7F3FFSuper Beacon"), lore, false);
        }
        for (String d : t.descripcion) lore.add(Estilo.legado(d.contains("&") ? d : "&#8A8A8A" + d));
        if (!t.descripcion.isEmpty()) lore.add(Estilo.vacio());
        lore.add(tx.linea("objeto-dueno", "&#545454▸ &#D7F3FFDueño  &f%dueno%", "%dueno%", b.duenoTexto()));
        if (t.beneficia == TipoBaliza.Beneficia.CLAN || b.clan != null) {
            String clan = plugin.motor().clanDe(b);
            lore.add(tx.linea("objeto-clan", "&#545454▸ &#D7F3FFClan  &f%clan%", "%clan%",
                    clan == null ? tx.crudo("sin-clan", "sin clan") : clan));
        }
        lore.add(tx.linea("objeto-alcance", "&#545454▸ &#D7F3FFAlcance  &f%radio% bloques",
                "%radio%", String.valueOf(t.radio)));
        lore.add(tx.linea("objeto-beneficia", "&#545454▸ &#D7F3FFBeneficia  &f%beneficia%",
                "%beneficia%", plugin.beneficiaTexto(t.beneficia)));
        if (t.fijo()) {
            lore.add(tx.linea("menu-todos", "&#545454▸ &#D7F3FFEfectos  &ftodos activos"));
        } else {
            lore.add(tx.linea("menu-elegidos", "&#545454▸ &#D7F3FFEfectos  &f%elegidos% de %elegibles% elegidos",
                    "%elegidos%", String.valueOf(plugin.motor().activos(b, t).size()),
                    "%elegibles%", String.valueOf(t.elegibles)));
        }
        lore.add(Estilo.vacio());
        long ahora = System.currentTimeMillis();
        if (b.vence <= 0) {
            lore.add(tx.linea("objeto-permanente", "&#545454▸ &#D7F3FFDuración  &fpermanente"));
        } else if (b.vencida(ahora)) {
            lore.add(tx.linea("menu-apagado", "&#FF5C5CApagado &#8A8A8A· venció el %fecha%",
                    "%fecha%", Tiempo.fecha(b.vence, plugin.zona())));
        } else {
            lore.add(tx.linea("menu-quedan", "&#D7F3FFQuedan &f%tiempo%", "%tiempo%", Tiempo.restante(b.vence - ahora)));
            lore.add(tx.linea("objeto-vence", "&#545454▸ &#D7F3FFVence  &f%fecha%",
                    "%fecha%", Tiempo.fecha(b.vence, plugin.zona())));
        }
        if (!b.esDe(p.getUniqueId())) {
            lore.add(Estilo.vacio());
            lore.add(tx.linea("menu-staff", "&#FFB627Lo estás viendo como staff."));
        }
        return MenuUtil.icon(icono, Estilo.legado(t.nombre).decoration(TextDecoration.ITALIC, false), lore, true);
    }

    /** Un efecto: verde y brillando si esta activo, gris si no, apagado y con el motivo si no se puede usar. */
    private ItemStack icono(Player p, Baliza b, TipoBaliza t, Efecto e, boolean activo) {
        SuperBeaconPlugin.TextosBaliza tx = plugin.textos();
        String falta = e.falta();
        List<Component> lore = new ArrayList<>();
        for (String d : e.detalle()) lore.add(Estilo.legado(d));
        lore.add(Estilo.vacio());
        boolean elegido = b.elegidos.contains(e.clave());
        if (falta != null) {
            lore.add(tx.linea("menu-efecto-no-disponible", "&#FF5C5CNo disponible (%motivo%)", "%motivo%", falta));
            if (elegido && !t.fijo()) {
                lore.add(Estilo.vacio());
                lore.add(accion(p, tx.crudo("menu-accion-desactivar", "desactivarlo")));
            }
        } else if (t.fijo()) {
            lore.add(tx.linea("menu-efecto-activo", "&#5CFF7AActivo"));
            lore.add(Estilo.vacio());
            lore.add(tx.linea("menu-efecto-fijo", "&#8A8A8ASiempre activo en este Super Beacon."));
        } else if (activo) {
            lore.add(tx.linea("menu-efecto-activo", "&#5CFF7AActivo"));
            lore.add(Estilo.vacio());
            lore.add(accion(p, tx.crudo("menu-accion-desactivar", "desactivarlo")));
        } else {
            lore.add(tx.linea("menu-efecto-inactivo", "&#8A8A8AInactivo"));
            if (plugin.motor().activos(b, t).size() >= t.elegibles) {
                lore.add(tx.linea("menu-efecto-tope", "&#8A8A8AYa elegiste %elegibles%: desactiva uno para cambiarlo.",
                        "%elegibles%", String.valueOf(t.elegibles)));
            }
            lore.add(Estilo.vacio());
            lore.add(accion(p, tx.crudo("menu-accion-activar", "activarlo")));
        }
        // Vencida y apagada: la eleccion se conserva (viaja con el objeto), pero nada brilla.
        boolean apagada = b.vencida(System.currentTimeMillis());
        if (apagada) {
            lore.add(0, tx.linea("menu-efecto-apagado", "&#FF5C5CApagado: este Super Beacon ya venció."));
        }
        boolean encendido = falta == null && activo && !apagada;
        Material material = falta != null ? Material.GRAY_DYE : e.icono();
        TextColor color = encendido ? VERDE : falta != null ? MenuUtil.DIM : MenuUtil.SOFT;
        return MenuUtil.icon(material, Estilo.texto(e.nombrePlano(), color), lore, encendido);
    }

    private ItemStack boton(Player p, Material material, String nombre, List<String> lineas, String verbo) {
        List<Component> lore = new ArrayList<>();
        for (String l : lineas) lore.add(Estilo.legado(l));
        if (!lore.isEmpty()) lore.add(Estilo.vacio());
        lore.add(accion(p, verbo));
        return MenuUtil.icon(material, Estilo.legado(nombre), lore, false);
    }

    /** La ultima linea, en amarillo: "Clic para..." en Java, "Toca para..." en Bedrock. */
    private Component accion(Player p, String verbo) {
        SuperBeaconPlugin.TextosBaliza tx = plugin.textos();
        String frase = Plataforma.esBedrock(p) ? tx.crudo("menu-toque", "Toca para %accion%")
                : tx.crudo("menu-clic", "Clic para %accion%");
        return MenuUtil.action(frase.replace("%accion%", verbo));
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
        switch (slot) {
            case CERRAR -> p.closeInventory();
            case ALCANCE -> {
                if (t == null) return;
                p.closeInventory();
                plugin.contorno().mostrar(p, b, t);
                plugin.textos().manda(p, "alcance",
                        "&7Este es su alcance: &f%radio% &7bloques. Lo verás durante 10 segundos.",
                        "%radio%", String.valueOf(t.radio));
            }
            case RECOGER -> {
                // El hueco solo hace falta si es suyo: al staff no se le da (va a su dueño).
                if (b.esDe(p.getUniqueId()) && p.getInventory().firstEmpty() == -1) {
                    plugin.textos().manda(p, "sin-espacio",
                            "&#FF5C5CNo tienes espacio en el inventario. &7Libera una casilla e inténtalo de nuevo.");
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                    return;
                }
                p.closeInventory();
                plugin.entregas().recoger(p, b);
            }
            default -> {
                String clave = v.efectos.get(slot);
                if (clave != null && t != null) elegir(p, b, t, clave);
            }
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
