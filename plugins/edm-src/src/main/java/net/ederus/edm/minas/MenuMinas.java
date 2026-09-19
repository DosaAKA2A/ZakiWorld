package net.ederus.edm.minas;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.menu.MenuUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Los menus de las minas.
 *
 * Para el staff, tres ventanas: la lista (con el pico, "Nueva mina" y
 * "Reiniciar todas"), la ficha de una mina (donde se edita todo a golpe de
 * clic) y su mezcla de bloques (clic en un bloque de tu inventario y entra;
 * clic sobre uno de la mezcla y sube o baja). Para el jugador, una sola: las
 * minas a las que puede ir, con lo que queda por picar y cuando se reinician.
 *
 * Como en todos los menus de EDM, ningun clic mueve un objeto: se cancela todo
 * y se actua sobre lo que se pincho.
 */
public final class MenuMinas implements Listener {

    private static final int TAM = 54;

    /** El cuerpo centrado de siempre. */
    private static final int[] CASILLAS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};

    // lista
    private static final int L_PICO = 45, L_NUEVA = 47, L_AYUDA = 49, L_TODAS = 51, L_JUGADOR = 53;
    // ficha
    private static final int F_ICONO = 4, F_NOMBRE = 19, F_ZONA = 20, F_BLOQUES = 21, F_INTERVALO = 22,
            F_UMBRAL = 23, F_SPAWN = 24, F_PERMISO = 25, F_REINICIAR = 31, F_IR = 40, F_VOLVER = 49, F_BORRAR = 53;
    // bloques
    private static final int B_AYUDA = 45, B_VOLVER = 49;
    // jugador
    private static final int J_AYUDA = 49;

    private enum Tipo { LISTA, FICHA, BLOQUES, JUGADOR }

    private final MinasPlugin plugin;

    public MenuMinas(MinasPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Vista implements InventoryHolder {
        private final Tipo tipo;
        private final String mina;
        private Inventory inv;
        /** Que hay en cada casilla del cuerpo: minas (lista, jugador) o bloques (mezcla). */
        private final List<Object> cuerpo = new ArrayList<>();

        Vista(Tipo tipo, String mina) {
            this.tipo = tipo;
            this.mina = mina;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    /* ------------------------------------------------------------------ abrir */

    public void abrirLista(Player p) {
        Vista v = new Vista(Tipo.LISTA, null);
        v.inv = Bukkit.createInventory(v, TAM, plugin.titulo("Todas"));
        pintarLista(v);
        p.openInventory(v.inv);
    }

    public void abrirJugador(Player p) {
        Vista v = new Vista(Tipo.JUGADOR, null);
        v.inv = Bukkit.createInventory(v, TAM, plugin.titulo("Elige una"));
        pintarJugador(v, p);
        p.openInventory(v.inv);
    }

    public void abrirFicha(Player p, String id) {
        Mina m = plugin.minas().de(id);
        if (m == null) {
            abrirLista(p);
            return;
        }
        Vista v = new Vista(Tipo.FICHA, m.id());
        v.inv = Bukkit.createInventory(v, TAM, plugin.titulo(m.titulo()));
        pintarFicha(v, m);
        p.openInventory(v.inv);
    }

    public void abrirBloques(Player p, String id) {
        Mina m = plugin.minas().de(id);
        if (m == null) {
            abrirLista(p);
            return;
        }
        Vista v = new Vista(Tipo.BLOQUES, m.id());
        v.inv = Bukkit.createInventory(v, TAM, plugin.titulo(m.titulo().append(Estilo.texto(" · bloques", MinasPlugin.TITULO))));
        pintarBloques(v, m);
        p.openInventory(v.inv);
    }

    /* ----------------------------------------------------------------- pintar */

    private void fondo(Inventory inv) {
        for (int i = 0; i < TAM; i++) inv.setItem(i, MenuUtil.pane());
    }

    private void pintarLista(Vista v) {
        fondo(v.inv);
        v.cuerpo.clear();
        List<Mina> minas = plugin.minas().todas();
        for (int i = 0; i < CASILLAS.length && i < minas.size(); i++) {
            Mina m = minas.get(i);
            v.cuerpo.add(m);
            List<Component> lore = ficha(m);
            lore.add(Estilo.vacio());
            lore.add(Estilo.accion("Clic para editarla", MinasPlugin.MARCA));
            v.inv.setItem(CASILLAS[i], MenuUtil.icon(m.icono(), tituloIcono(m), lore, m.reiniciando()));
        }

        v.inv.setItem(L_PICO, MenuUtil.icon(Material.GOLDEN_PICKAXE, MenuUtil.title("Pico de selección", MinasPlugin.MARCA),
                List.of(
                        Estilo.texto("Marca las dos esquinas de una caja:", Estilo.APAGADO),
                        Estilo.texto("clic izquierdo una, clic derecho la otra.", Estilo.APAGADO),
                        Estilo.vacio(),
                        Estilo.accion("Clic para tenerlo en la mano", MinasPlugin.MARCA)), false));
        v.inv.setItem(L_NUEVA, MenuUtil.icon(Material.EMERALD, MenuUtil.title("Nueva mina", MinasPlugin.MARCA),
                List.of(
                        Estilo.texto("Con la caja ya marcada con el pico. Se", Estilo.APAGADO),
                        Estilo.texto("te pide el nombre por el chat y nace con", Estilo.APAGADO),
                        Estilo.texto("piedra, carbón y hierro; luego la afinas.", Estilo.APAGADO),
                        Estilo.vacio(),
                        Estilo.accion("Clic para crearla", MinasPlugin.MARCA)), true));
        v.inv.setItem(L_AYUDA, MenuUtil.icon(Material.ITEM_FRAME, MenuUtil.title("Cómo funciona", MinasPlugin.MARCA),
                List.of(
                        Estilo.texto("Una mina es una caja que se rellena sola", Estilo.APAGADO),
                        Estilo.texto("con una mezcla de bloques. Se pica aunque", Estilo.APAGADO),
                        Estilo.texto("esté dentro de una región de WorldGuard;", Estilo.APAGADO),
                        Estilo.texto("poner bloques, explotar o inundar, no.", Estilo.APAGADO),
                        Estilo.vacio(),
                        Estilo.texto("El reinicio salta por reloj, por umbral", Estilo.APAGADO),
                        Estilo.texto("de picado o a mano. Antes avisa y saca", Estilo.APAGADO),
                        Estilo.texto("a quien esté dentro.", Estilo.APAGADO),
                        Estilo.vacio(),
                        Estilo.texto("Placeholders: %edm_mina_<id>_minado%,", MenuUtil.DIM),
                        Estilo.texto("_restante, _reinicio y _nombre.", MenuUtil.DIM)), false));
        v.inv.setItem(L_TODAS, MenuUtil.icon(Material.CLOCK, MenuUtil.title("Reiniciar todas", MinasPlugin.MARCA),
                List.of(
                        Estilo.texto("Rellena todas las minas ahora mismo,", Estilo.APAGADO),
                        Estilo.texto("sacando antes a quien esté dentro.", Estilo.APAGADO),
                        Estilo.vacio(),
                        Estilo.accion("Clic para reiniciarlas", MinasPlugin.MARCA)), false));
        v.inv.setItem(L_JUGADOR, MenuUtil.icon(Material.SPYGLASS, MenuUtil.title("Como lo ven ellos", MinasPlugin.MARCA),
                List.of(
                        Estilo.texto("La lista que abre /mine a un jugador.", Estilo.APAGADO),
                        Estilo.vacio(),
                        Estilo.accion("Clic para verla", MinasPlugin.MARCA)), false));
    }

    private void pintarJugador(Vista v, Player p) {
        fondo(v.inv);
        v.cuerpo.clear();
        List<Mina> minas = plugin.minas().todas();
        int i = 0;
        for (Mina m : minas) {
            if (i >= CASILLAS.length) break;
            if (!m.conZona()) continue;
            boolean puede = plugin.puedeEntrar(p, m);
            v.cuerpo.add(m);
            List<Component> lore = new ArrayList<>();
            lore.add(Estilo.linea("Por picar", Math.round(100 - m.porcentajeMinado()) + "%", NamedTextColor.WHITE));
            lore.add(Estilo.linea("Reinicio", m.segundos() < 0 ? "a mano" : "en " + m.cuentaAtras(), MinasPlugin.CLARO));
            lore.add(Estilo.vacio());
            lore.addAll(mezcla(m, 5));
            lore.add(Estilo.vacio());
            if (puede) lore.add(Estilo.accion("Clic para ir", MinasPlugin.MARCA));
            else lore.add(Estilo.texto("Sin acceso todavía.", NamedTextColor.RED));
            ItemStack icono = puede
                    ? MenuUtil.icon(m.icono(), tituloIcono(m), lore, false)
                    : MenuUtil.icon(Material.GRAY_STAINED_GLASS_PANE, MenuUtil.title(m.nombrePlano(), Estilo.APAGADO), lore, false);
            v.inv.setItem(CASILLAS[i++], icono);
        }
        v.inv.setItem(J_AYUDA, MenuUtil.icon(Material.ITEM_FRAME, MenuUtil.title("Las minas", MinasPlugin.MARCA),
                List.of(
                        Estilo.texto("Pica lo que quieras: se rellenan solas.", Estilo.APAGADO),
                        Estilo.texto("Antes de reiniciarse avisan y te sacan.", Estilo.APAGADO),
                        Estilo.texto("Lo picado va directo a tu mochila.", Estilo.APAGADO)), false));
    }

    private void pintarFicha(Vista v, Mina m) {
        fondo(v.inv);
        List<Component> cab = ficha(m);
        v.inv.setItem(F_ICONO, MenuUtil.icon(m.icono(), tituloIcono(m), cab, true));

        v.inv.setItem(F_NOMBRE, MenuUtil.icon(Material.NAME_TAG, MenuUtil.title("Nombre", MinasPlugin.MARCA),
                List.of(
                        Estilo.texto(" " + Estilo.FLECHA + " ", Estilo.APAGADO).append(Estilo.texto("Ahora  ", Estilo.CLARO)).append(m.titulo()),
                        Estilo.linea("Id", m.id(), Estilo.APAGADO),
                        Estilo.vacio(),
                        Estilo.texto("El id no cambia: es el de los", Estilo.APAGADO),
                        Estilo.texto("placeholders y de /mine tp.", Estilo.APAGADO),
                        Estilo.texto("Admite colores con &: &cMINA PVP", Estilo.APAGADO),
                        Estilo.vacio(),
                        Estilo.accion("Clic para cambiarlo por el chat", MinasPlugin.MARCA)), false));

        List<Component> zona = new ArrayList<>();
        if (m.conZona()) {
            zona.add(Estilo.linea("Mundo", m.mundoNombre(), NamedTextColor.WHITE));
            zona.add(Estilo.linea("Desde", m.minX() + " " + m.minY() + " " + m.minZ(), NamedTextColor.WHITE));
            zona.add(Estilo.linea("Hasta", m.maxX() + " " + m.maxY() + " " + m.maxZ(), NamedTextColor.WHITE));
            zona.add(Estilo.linea("Tamaño", m.medidas() + " · " + cifra(m.volumen()) + " bloques", MinasPlugin.CLARO));
        } else {
            zona.add(Estilo.texto("Sin zona todavía.", NamedTextColor.RED));
        }
        zona.add(Estilo.vacio());
        zona.add(Estilo.accion("Clic: aplicar la caja marcada con el pico", MinasPlugin.MARCA));
        zona.add(Estilo.accion("Clic derecho: ir a la mina", MinasPlugin.MARCA));
        v.inv.setItem(F_ZONA, MenuUtil.icon(Material.GOLDEN_PICKAXE, MenuUtil.title("Zona", MinasPlugin.MARCA), zona, false));

        List<Component> bloques = new ArrayList<>(mezcla(m, 8));
        if (bloques.isEmpty()) bloques.add(Estilo.texto("Sin bloques: no se puede rellenar.", NamedTextColor.RED));
        bloques.add(Estilo.vacio());
        bloques.add(Estilo.accion("Clic para editar la mezcla", MinasPlugin.MARCA));
        v.inv.setItem(F_BLOQUES, MenuUtil.icon(Material.CHEST, MenuUtil.title("Bloques", MinasPlugin.MARCA), bloques, false));

        v.inv.setItem(F_INTERVALO, MenuUtil.icon(Material.CLOCK, MenuUtil.title("Reloj", MinasPlugin.MARCA),
                List.of(
                        Estilo.linea("Ahora", m.intervalo() <= 0 ? "sin temporizador" : "cada " + duracion(m.intervalo()), NamedTextColor.WHITE),
                        Estilo.linea("Próximo", m.segundos() < 0 ? "-" : "en " + m.cuentaAtras(), MinasPlugin.CLARO),
                        Estilo.vacio(),
                        Estilo.accion("Clic: +1 min · derecho: -1 min", MinasPlugin.MARCA),
                        Estilo.accion("Con shift: ±10 s", MinasPlugin.MARCA),
                        Estilo.accion("Q: escribir los segundos exactos", MinasPlugin.MARCA)), false));

        v.inv.setItem(F_UMBRAL, MenuUtil.icon(Material.TARGET, MenuUtil.title("Umbral de picado", MinasPlugin.MARCA),
                List.of(
                        Estilo.linea("Ahora", m.umbral() <= 0 ? "apagado" : "al " + m.umbral() + "% picado", NamedTextColor.WHITE),
                        Estilo.linea("Picado", Math.round(m.porcentajeMinado()) + "%", MinasPlugin.CLARO),
                        Estilo.vacio(),
                        Estilo.texto("Al llegar a ese porcentaje, el reinicio", Estilo.APAGADO),
                        Estilo.texto("se adelanta a unos segundos.", Estilo.APAGADO),
                        Estilo.vacio(),
                        Estilo.accion("Clic: +5 · derecho: -5 · shift: ±1", MinasPlugin.MARCA)), false));

        v.inv.setItem(F_SPAWN, MenuUtil.icon(Material.RECOVERY_COMPASS, MenuUtil.title("Salida", MinasPlugin.MARCA),
                List.of(
                        Estilo.linea("Ahora", m.spawnTexto(), NamedTextColor.WHITE),
                        Estilo.vacio(),
                        Estilo.texto("Donde aparece quien viaja a la mina y", Estilo.APAGADO),
                        Estilo.texto("a donde se saca a la gente al reiniciar.", Estilo.APAGADO),
                        Estilo.vacio(),
                        Estilo.accion("Clic: fijarla donde estás", MinasPlugin.MARCA),
                        Estilo.accion("Clic derecho: ir a ella", MinasPlugin.MARCA),
                        Estilo.accion("Q: quitarla (encima de la mina)", MinasPlugin.MARCA)), false));

        v.inv.setItem(F_PERMISO, MenuUtil.icon(Material.IRON_DOOR, MenuUtil.title("Acceso", MinasPlugin.MARCA),
                List.of(
                        Estilo.linea("Ahora", m.permiso().isEmpty() ? "todos" : m.permiso(), NamedTextColor.WHITE),
                        Estilo.vacio(),
                        Estilo.texto("Un permiso de LuckPerms: solo quien lo", Estilo.APAGADO),
                        Estilo.texto("tenga entra y pica. none: todos.", Estilo.APAGADO),
                        Estilo.vacio(),
                        Estilo.accion("Clic para escribirlo por el chat", MinasPlugin.MARCA)), false));

        v.inv.setItem(F_REINICIAR, MenuUtil.icon(Material.TNT, MenuUtil.title("Reiniciar ahora", MinasPlugin.MARCA),
                List.of(
                        Estilo.texto("Saca a quien esté dentro y la rellena.", Estilo.APAGADO),
                        Estilo.vacio(),
                        Estilo.accion("Clic para reiniciarla", MinasPlugin.MARCA)), m.reiniciando()));

        v.inv.setItem(F_IR, MenuUtil.icon(Material.ENDER_PEARL, MenuUtil.title("Ir a la mina", MinasPlugin.MARCA),
                List.of(Estilo.accion("Clic para viajar", MinasPlugin.MARCA)), false));

        v.inv.setItem(F_VOLVER, MenuUtil.icon(Material.ARROW, MenuUtil.title("Volver", Estilo.APAGADO),
                List.of(Estilo.texto("A la lista de minas.", Estilo.APAGADO)), false));

        v.inv.setItem(F_BORRAR, MenuUtil.icon(Material.BARRIER, MenuUtil.title("Borrar la mina", NamedTextColor.RED),
                List.of(
                        Estilo.texto("Se olvida la mina. Los bloques del", Estilo.APAGADO),
                        Estilo.texto("mundo se quedan como están.", Estilo.APAGADO),
                        Estilo.vacio(),
                        Estilo.accion("Pulsa Q encima para borrarla", NamedTextColor.RED)), false));
    }

    private void pintarBloques(Vista v, Mina m) {
        fondo(v.inv);
        v.cuerpo.clear();
        int i = 0;
        for (Map.Entry<Material, Integer> e : m.partes().entrySet()) {
            if (i >= CASILLAS.length) break;
            v.cuerpo.add(e.getKey());
            List<Component> lore = List.of(
                    Estilo.linea("Partes", String.valueOf(e.getValue()), NamedTextColor.WHITE),
                    Estilo.linea("Sale", Math.round(m.porcentaje(e.getKey())) + "% de la mina", MinasPlugin.CLARO),
                    Estilo.vacio(),
                    Estilo.accion("Clic: +5 · derecho: -5 · shift: ±1", MinasPlugin.MARCA),
                    Estilo.accion("Q: quitarlo de la mezcla", MinasPlugin.MARCA));
            v.inv.setItem(CASILLAS[i++], MenuUtil.icon(e.getKey(),
                    Component.translatable(e.getKey().translationKey(), MinasPlugin.MARCA), lore, false));
        }
        v.inv.setItem(B_AYUDA, MenuUtil.icon(Material.ITEM_FRAME, MenuUtil.title("Cómo se mezcla", MinasPlugin.MARCA),
                List.of(
                        Estilo.linea("Partes en total", String.valueOf(m.partesTotal()), NamedTextColor.WHITE),
                        Estilo.vacio(),
                        Estilo.texto("Clic en un bloque de TU inventario y", Estilo.APAGADO),
                        Estilo.texto("entra en la mezcla con 10 partes. El", Estilo.APAGADO),
                        Estilo.texto("porcentaje es partes entre el total, así", Estilo.APAGADO),
                        Estilo.texto("que subir uno no obliga a bajar el resto.", Estilo.APAGADO)), false));
        v.inv.setItem(B_VOLVER, MenuUtil.icon(Material.ARROW, MenuUtil.title("Volver", Estilo.APAGADO),
                List.of(Estilo.texto("A la ficha de " + m.nombrePlano() + ".", Estilo.APAGADO)), false));
    }

    /* ---------------------------------------------------------------- textos */

    /** Las lineas de la cabecera de una mina: zona, picado, reloj y mezcla. */
    private List<Component> ficha(Mina m) {
        List<Component> lore = new ArrayList<>();
        lore.add(Estilo.linea("Zona", m.conZona() ? m.medidas() + " · " + cifra(m.volumen()) + " bloques" : "sin zona", m.conZona() ? NamedTextColor.WHITE : NamedTextColor.RED));
        lore.add(Estilo.linea("Picado", Math.round(m.porcentajeMinado()) + "%", NamedTextColor.WHITE));
        String reloj = m.intervalo() <= 0 ? "a mano" : "cada " + duracion(m.intervalo());
        if (m.segundos() >= 0) reloj += " · en " + m.cuentaAtras();
        lore.add(Estilo.linea("Reinicio", reloj, MinasPlugin.CLARO));
        lore.add(Estilo.linea("Acceso", m.permiso().isEmpty() ? "todos" : m.permiso(), Estilo.APAGADO));
        if (m.reiniciando()) lore.add(Estilo.texto("   Reiniciándose ahora mismo.", MinasPlugin.MARCA));
        lore.add(Estilo.vacio());
        lore.addAll(mezcla(m, 6));
        return lore;
    }

    /** La mezcla en lineas: "> Piedra  70%". El nombre lo traduce el cliente. */
    private List<Component> mezcla(Mina m, int max) {
        List<Component> out = new ArrayList<>();
        List<Map.Entry<Material, Integer>> orden = new ArrayList<>(m.partes().entrySet());
        orden.sort((a, b) -> b.getValue() - a.getValue());
        int i = 0;
        for (Map.Entry<Material, Integer> e : orden) {
            if (i++ >= max) {
                out.add(Estilo.texto("   y " + (orden.size() - max) + " más", Estilo.APAGADO));
                break;
            }
            out.add(Estilo.texto(" " + Estilo.FLECHA + " ", Estilo.APAGADO)
                    .append(Component.translatable(e.getKey().translationKey(), MinasPlugin.CLARO))
                    .append(Estilo.texto("  " + Math.round(m.porcentaje(e.getKey())) + "%", NamedTextColor.WHITE)));
        }
        return out;
    }

    /** El nombre de la mina como titulo de icono: el rombo y sus colores. */
    private static Component tituloIcono(Mina m) {
        return Component.text("✦ ", MinasPlugin.MARCA).decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true)
                .append(m.titulo().decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true));
    }

    static String cifra(long n) {
        return String.format(Locale.US, "%,d", n).replace(',', '.');
    }

    /** "45 s", "10 min", "1 h 30 min". */
    static String duracion(int s) {
        if (s < 60) return s + " s";
        int min = s / 60, seg = s % 60;
        if (min < 60) return seg == 0 ? min + " min" : min + " min " + seg + " s";
        int h = min / 60;
        min %= 60;
        return min == 0 ? h + " h" : h + " h " + min + " min";
    }

    /* ----------------------------------------------------------------- clics */

    @EventHandler
    public void alHacerClic(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Vista v)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);

        if (v.tipo == Tipo.JUGADOR) {
            if (e.getClickedInventory() != e.getInventory()) return;
            int i = indiceDe(e.getSlot());
            if (i < 0 || i >= v.cuerpo.size()) return;
            Mina m = (Mina) v.cuerpo.get(i);
            p.closeInventory();
            plugin.viajar(p, m);
            return;
        }

        if (!plugin.esAdmin(p)) return;
        switch (v.tipo) {
            case LISTA -> clicLista(e, v, p);
            case FICHA -> clicFicha(e, v, p);
            case BLOQUES -> clicBloques(e, v, p);
            default -> { }
        }
    }

    private void clicLista(InventoryClickEvent e, Vista v, Player p) {
        if (e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getSlot();
        int i = indiceDe(slot);
        if (i >= 0) {
            if (i < v.cuerpo.size()) abrirFicha(p, ((Mina) v.cuerpo.get(i)).id());
            return;
        }
        switch (slot) {
            case L_PICO -> {
                p.getInventory().addItem(plugin.varita().crear());
                plugin.di(p, "pico-entregado", "Tienes el pico de minas. Izquierdo: esquina 1. Derecho: esquina 2.");
            }
            case L_NUEVA -> {
                Varita.Seleccion s = plugin.varita().de(p);
                if (s == null || !s.completa()) {
                    plugin.di(p, "falta-zona", "Primero marca las dos esquinas con el pico. Lo tienes en /mine o con /mine wand.");
                    return;
                }
                plugin.di(p, "pide-nombre", "Escribe en el chat el nombre de la mina nueva. (cancelar para salir)");
                plugin.core().chat().pedir(p, texto -> {
                    Mina m = new ComandoMinas(plugin).crearConSeleccion(p, texto);
                    if (m != null) abrirFicha(p, m.id());
                    else abrirLista(p);
                }, () -> abrirLista(p));
            }
            case L_TODAS -> {
                int n = 0;
                for (Mina m : plugin.minas().todas()) if (plugin.reinicio().reiniciar(m, "menu")) n++;
                p.sendMessage(Estilo.aviso(Estilo.texto("Reiniciando " + n + " mina(s).", NamedTextColor.WHITE)));
                pintarLista(v);
            }
            case L_JUGADOR -> abrirJugador(p);
            default -> { }
        }
    }

    private void clicFicha(InventoryClickEvent e, Vista v, Player p) {
        if (e.getClickedInventory() != e.getInventory()) return;
        Mina m = plugin.minas().de(v.mina);
        if (m == null) {
            abrirLista(p);
            return;
        }
        boolean q = e.getClick() == ClickType.DROP || e.getClick() == ClickType.CONTROL_DROP;
        boolean shift = e.isShiftClick();
        boolean derecho = e.isRightClick();
        switch (e.getSlot()) {
            case F_NOMBRE -> {
                plugin.di(p, "pide-nombre-nuevo", "Escribe en el chat el nombre nuevo. (cancelar para salir)");
                plugin.core().chat().pedir(p, texto -> {
                    m.nombre(texto.trim());
                    plugin.minas().guardar();
                    abrirFicha(p, m.id());
                }, () -> abrirFicha(p, m.id()));
            }
            case F_ZONA -> {
                if (derecho) {
                    p.closeInventory();
                    plugin.viajar(p, m);
                    return;
                }
                Varita.Seleccion s = plugin.varita().de(p);
                if (s == null || !s.completa()) {
                    plugin.di(p, "falta-zona", "Primero marca las dos esquinas con el pico. Lo tienes en /mine o con /mine wand.");
                    return;
                }
                m.zona(s.mundo(), s.a()[0], s.a()[1], s.a()[2], s.b()[0], s.b()[1], s.b()[2]);
                plugin.minas().guardar();
                plugin.anotar("zona", m.id(), p.getName(), s.mundo(), s.medidas());
                plugin.di(p, "zona-aplicada", "Zona aplicada a %mina%: %que%", "%mina%", m.nombre(),
                        "%que%", s.medidas() + ", " + cifra(s.volumen()) + " bloques");
                pintarFicha(v, m);
            }
            case F_BLOQUES -> abrirBloques(p, m.id());
            case F_INTERVALO -> {
                if (q) {
                    plugin.di(p, "pide-intervalo", "Escribe cada cuántos segundos se reinicia; 0 apaga el temporizador. (cancelar para salir)");
                    plugin.core().chat().pedir(p, texto -> {
                        try {
                            m.intervalo(Integer.parseInt(texto.trim()));
                            plugin.minas().guardar();
                        } catch (NumberFormatException ex) {
                            plugin.di(p, "numero-malo", "Eso no es un número.");
                        }
                        abrirFicha(p, m.id());
                    }, () -> abrirFicha(p, m.id()));
                    return;
                }
                int paso = shift ? 10 : 60;
                m.intervalo(m.intervalo() + (derecho ? -paso : paso));
                plugin.minas().guardar();
                pintarFicha(v, m);
            }
            case F_UMBRAL -> {
                int paso = shift ? 1 : 5;
                m.umbral(m.umbral() + (derecho ? -paso : paso));
                plugin.minas().guardar();
                pintarFicha(v, m);
            }
            case F_SPAWN -> {
                if (q) {
                    m.spawn(null);
                    plugin.minas().guardar();
                    plugin.di(p, "spawn-quitado", "Salida quitada: ahora se sale encima de la mina.");
                } else if (derecho) {
                    if (m.salida() != null) {
                        p.closeInventory();
                        p.teleport(m.salida());
                    }
                    return;
                } else {
                    m.spawn(p.getLocation());
                    plugin.minas().guardar();
                    plugin.di(p, "spawn-fijado", "Salida fijada donde estás.");
                }
                pintarFicha(v, m);
            }
            case F_PERMISO -> {
                plugin.di(p, "pide-permiso", "Escribe el permiso para entrar, o none. (cancelar para salir)");
                plugin.core().chat().pedir(p, texto -> {
                    String t = texto.trim();
                    m.permiso(t.equalsIgnoreCase("none") ? "" : t);
                    plugin.minas().guardar();
                    abrirFicha(p, m.id());
                }, () -> abrirFicha(p, m.id()));
            }
            case F_REINICIAR -> {
                if (m.partes().isEmpty()) {
                    plugin.di(p, "sin-bloques", "La mina no tiene bloques. Añade alguno desde su ficha antes de reiniciarla.");
                    return;
                }
                if (plugin.reinicio().reiniciar(m, "menu")) {
                    plugin.di(p, "reinicio-lanzado", "Reiniciando %mina%.", "%mina%", m.nombre());
                } else {
                    plugin.di(p, "no-se-pudo", "No se pudo reiniciar %mina%: sin zona, sin bloques o ya en marcha.", "%mina%", m.nombre());
                }
                pintarFicha(v, m);
            }
            case F_IR -> {
                p.closeInventory();
                plugin.viajar(p, m);
            }
            case F_VOLVER -> abrirLista(p);
            case F_BORRAR -> {
                if (!q) return;
                plugin.minas().borrar(m.id());
                plugin.minas().guardar();
                plugin.anotar("borrada", m.id(), p.getName());
                plugin.di(p, "borrada", "Mina borrada: %mina%. Los bloques se quedan como están.", "%mina%", m.nombre());
                abrirLista(p);
            }
            default -> { }
        }
    }

    private void clicBloques(InventoryClickEvent e, Vista v, Player p) {
        Mina m = plugin.minas().de(v.mina);
        if (m == null) {
            abrirLista(p);
            return;
        }
        // Un bloque del inventario propio entra en la mezcla.
        if (e.getClickedInventory() != null && e.getClickedInventory() == p.getInventory()) {
            ItemStack it = e.getCurrentItem();
            if (it == null || it.getType().isAir()) return;
            Material mat = it.getType();
            if (!mat.isBlock()) {
                plugin.di(p, "no-es-bloque", "Eso no se puede colocar como bloque.");
                return;
            }
            if (!m.partes().containsKey(mat)) {
                m.poner(mat, 10);
                plugin.minas().guardar();
                pintarBloques(v, m);
            }
            return;
        }
        if (e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getSlot();
        if (slot == B_VOLVER) {
            abrirFicha(p, m.id());
            return;
        }
        int i = indiceDe(slot);
        if (i < 0 || i >= v.cuerpo.size()) return;
        Material mat = (Material) v.cuerpo.get(i);
        if (e.getClick() == ClickType.DROP || e.getClick() == ClickType.CONTROL_DROP) {
            m.quitar(mat);
        } else {
            int paso = e.isShiftClick() ? 1 : 5;
            int ahora = m.partes().getOrDefault(mat, 1);
            m.poner(mat, ahora + (e.isRightClick() ? -paso : paso));
        }
        plugin.minas().guardar();
        pintarBloques(v, m);
    }

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Vista) e.setCancelled(true);
    }

    private static int indiceDe(int slot) {
        for (int i = 0; i < CASILLAS.length; i++) {
            if (CASILLAS[i] == slot) return i;
        }
        return -1;
    }
}
