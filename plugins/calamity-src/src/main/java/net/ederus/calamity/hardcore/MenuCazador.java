package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Los rankings de la semana en un menu (1.3.0): lo que abre el Cazador de la antesala (antes
 * los escribia en el chat) y el boton Rankings del Altar.
 *
 * Una fila por tabla de ranking.tablas (Extraido, Cazador, Segador, Superviviente): a la
 * izquierda el rotulo con lo que mide, luego el podio con las cabezas de los tres primeros (el
 * puesto va en el numero de la pila: se lee sin pasar el raton) y a la derecha tu cabeza con tu
 * puesto y lo que te falta para el podio. Arriba las reglas del cierre; abajo el Tablero (Ecos
 * con botin y Parcas), tus salidas de la semana y los premios.
 *
 * Es la clasificacion tal cual (Rankings.podioSemana, la cache del minuto), sin el reparto de
 * premios del cierre: el lunes cobra quien cumpla las salidas y no pase de las tablas maximas.
 */
final class MenuCazador implements Listener {

    private static final long ESPERA_MS = 500;
    private static final int CABECERA = 4, TABLERO = 47, SALIDAS = 49, PREMIOS = 51;
    /** Columnas del podio (1.o, 2.o, 3.o) y la tuya, en cada fila. */
    private static final int[] PODIO = {2, 3, 4};
    private static final int TU = 6;

    record Vista(Map<Integer, String> acciones) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final Hardcore hc;
    private final Map<UUID, Long> ultimoClic = new HashMap<>();
    private final Set<BukkitTask> tareas = new HashSet<>();

    MenuCazador(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
    }

    void parar() {
        HandlerList.unregisterAll(this);
        for (BukkitTask t : tareas) t.cancel();
        tareas.clear();
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Vista) p.closeInventory();
        }
        ultimoClic.clear();
    }

    private void tarea(Runnable r) {
        final BukkitTask[] t = new BukkitTask[1];
        t[0] = hc.plugin().getServer().getScheduler().runTask(hc.plugin(), () -> {
            tareas.remove(t[0]);
            hc.seguro("cazador", r);
        });
        tareas.add(t[0]);
    }

    /** Si hay rankings que ensenar (modulo en marcha y ranking.activo). */
    boolean hay() {
        Rankings r = hc.rankings();
        return r != null && r.activo();
    }

    // ------------------------------------------------------------------ abrir y pintar

    void abrir(Player p) {
        Rankings r = hc.rankings();
        if (r == null || !r.activo()) {
            p.sendMessage(ComandoCalamity.mensaje("Los rankings no están abiertos ahora mismo."));
            return;
        }
        Vista v = new Vista(new HashMap<>());
        Inventory inv = hc.plugin().getServer().createInventory(v, 54, Paleta.ventanaCalamity("Rankings de la semana"));
        pintar(inv, p, v, r);
        p.openInventory(inv);
        Marco.sonar(p, "item.book.page_turn", 0.8f, 1.1f);
    }

    private void pintar(Inventory inv, Player p, Vista v, Rankings r) {
        UUID u = p.getUniqueId();
        ConfigurationSection c = hc.cfg();
        int minimo = c.getInt("ranking.minimo-extracciones", 3);
        Estadisticas st = hc.estadisticas();
        long salidas = st == null ? 0 : st.semana(u, "extracciones");
        List<Rankings.Premio> premios = r.premios();

        inv.setItem(CABECERA, Marco.icono(Material.GOLDEN_HELMET, Component.text("Rankings de la semana", Paleta.MARCA), List.of(
                Marco.texto("Se cierran el lunes a las 00:00"),
                Marco.texto("y se pagan en el acto."),
                Component.empty(),
                Marco.dato("Para cobrar", minimo + " salidas vivo en la semana"),
                Marco.dato("Tablas por jugador", "como mucho " + Math.max(1, c.getInt("ranking.maximo-tablas", 2))),
                Marco.tenue("Con menos de " + c.getInt("ranking.minimo-elegibles", 8) + " que puntúen en una"),
                Marco.tenue("tabla, solo cobra el 1.º.")), false));
        inv.setItem(Marco.CERRAR, Marco.cerrar());
        v.acciones().put(Marco.CERRAR, "cerrar");

        int fila = 0;
        for (Map.Entry<Rankings.Tabla, List<Rankings.Fila>> e : r.podioSemana(10).entrySet()) {
            if (fila >= Marco.FILAS) break;
            int base = (fila + 1) * 9;
            Rankings.Tabla t = e.getKey();
            List<Rankings.Fila> top = e.getValue();
            inv.setItem(base, Marco.rotulo(iconoTabla(t.id()), t.nombre(), List.of(queMide(t.id()), "Pagan del 1.º al " + premios.size() + ".º.")));
            for (int i = 0; i < PODIO.length; i++) {
                inv.setItem(base + PODIO[i], i < top.size() ? podio(top.get(i), i + 1, t, u) : libre(i + 1));
            }
            inv.setItem(base + TU, tu(p, t, top, salidas, minimo));
            fila++;
        }

        Tablero tab = hc.tablero();
        boolean tablero = tab != null && hc.valor("tablero", tab::activo, false);
        inv.setItem(TABLERO, Marco.boton(Material.ITEM_FRAME, "Tablero", List.of("Ecos con botín y Parcas sueltas.",
                "Quien caza, sube en Cazador y Segador."), tablero ? "Clic para abrirlo" : "Próximamente.", tablero));
        if (tablero) v.acciones().put(TABLERO, "tablero");

        boolean puntua = salidas >= minimo;
        inv.setItem(SALIDAS, Marco.icono(new ItemStack(Material.OAK_DOOR, (int) Math.max(1, Math.min(64, salidas))),
                Component.text("Tus salidas: ", Paleta.TEXTO).append(Component.text(salidas + " de " + minimo, Paleta.CIFRA)),
                List.of(Marco.barra(salidas, minimo), puntua ? Marco.tiene("Esta semana puntúas.")
                        : Marco.falta("Aún no puntúas", "te faltan " + (minimo - salidas))), puntua));

        List<Component> pl = new ArrayList<>();
        for (int i = 0; i < premios.size(); i++) pl.add(Component.text((i + 1) + ".º  ", colorPuesto(i + 1)).append(Marco.texto(premio(premios.get(i)))));
        pl.add(Component.empty());
        pl.add(Marco.tenue("Lo mismo en cada tabla."));
        inv.setItem(PREMIOS, Marco.icono(Material.CHEST, Component.text("Premios", Paleta.MARCA), pl, false));
        Marco.rellenar(inv);
    }

    /** "30 Esencias, 2 Llaves del Caos y [ÁNIMA] 7 días": lo mismo que dice el aviso del cierre. */
    static String premio(Rankings.Premio pr) {
        List<String> partes = new ArrayList<>();
        if (pr.esencias() > 0) partes.add(pr.esencias() + " Esencias");
        if (pr.llaves() > 0) partes.add(pr.llaves() + (pr.llaves() == 1 ? " Llave del Caos" : " Llaves del Caos"));
        if (!pr.comandos().isEmpty()) partes.add("[ÁNIMA] 7 días");
        if (partes.isEmpty()) return "el puesto";
        if (partes.size() == 1) return partes.get(0);
        return String.join(", ", partes.subList(0, partes.size() - 1)) + " y " + partes.get(partes.size() - 1);
    }

    private static Material iconoTabla(String id) {
        return switch (id) {
            case "extraido" -> Material.SUNFLOWER;
            case "cazador" -> Material.ECHO_SHARD;
            case "segador" -> Material.WITHER_SKELETON_SKULL;
            case "superviviente" -> Material.TOTEM_OF_UNDYING;
            default -> Material.GOLD_INGOT;
        };
    }

    private static String queMide(String id) {
        return switch (id) {
            case "extraido" -> "MobCoins tasadas en la semana.";
            case "cazador" -> "Ecos ajenos cazados (válidos).";
            case "segador" -> "Parcas abatidas.";
            case "superviviente" -> "Tu expedición más larga.";
            default -> "Lo que llevas esta semana.";
        };
    }

    /** Oro, plata y bronce en tonos claros (se leen sobre el globo). */
    static TextColor colorPuesto(int puesto) {
        return switch (puesto) {
            case 1 -> TextColor.color(0xFFD27A);
            case 2 -> TextColor.color(0xD8DEE6);
            case 3 -> TextColor.color(0xE0A878);
            default -> Paleta.TEXTO;
        };
    }

    /** Una cabeza del podio: el puesto en la pila, el nombre y la cifra. */
    private static ItemStack podio(Rankings.Fila f, int puesto, Rankings.Tabla t, UUID yo) {
        ItemStack cabeza = Marco.cabeza(f.jugador());
        cabeza.setAmount(puesto);
        boolean soyYo = f.jugador().equals(yo);
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.dato(t.nombre(), Npcs.valorRanking(t.estadistica(), f.valor())));
        if (soyYo) lore.add(Component.text("● Eres tú.", Paleta.BIEN));
        return Marco.icono(cabeza, Component.text(puesto + ".º  ", colorPuesto(puesto))
                .append(Component.text(f.nombre(), soyYo ? Paleta.BIEN : Paleta.TEXTO)), lore, soyYo);
    }

    private static ItemStack libre(int puesto) {
        return Marco.icono(new ItemStack(Material.SKELETON_SKULL, puesto), Component.text(puesto + ".º  libre", Paleta.TENUE),
                List.of(Marco.tenue("Nadie todavía: es tuyo si lo quieres.")), false);
    }

    /** Tu cabeza en esa tabla: tu puesto (si estas en el top 10), tu cifra y lo que te falta para el 3.o. */
    private ItemStack tu(Player p, Rankings.Tabla t, List<Rankings.Fila> top, long salidas, int minimo) {
        UUID u = p.getUniqueId();
        int puesto = 0;
        for (int i = 0; i < top.size(); i++) if (top.get(i).jugador().equals(u)) puesto = i + 1;
        Estadisticas st = hc.estadisticas();
        long mio = st == null ? 0 : st.semana(u, t.estadistica());
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.dato("Llevas", Npcs.valorRanking(t.estadistica(), mio)));
        if (puesto == 0 || puesto > PODIO.length) {
            if (top.size() >= PODIO.length) {
                long falta = top.get(PODIO.length - 1).valor() - mio + 1;
                lore.add(Marco.tenue("Para el " + PODIO.length + ".º te faltan " + Npcs.valorRanking(t.estadistica(), Math.max(1, falta)) + "."));
            } else {
                lore.add(Marco.tenue("Con cualquier cifra entras en el podio."));
            }
        }
        if (salidas < minimo) lore.add(Marco.falta("Aún no puntúas", "te faltan " + (minimo - salidas) + " salidas"));
        ItemStack cabeza = Marco.cabeza(u);
        cabeza.setAmount(Math.max(1, Math.min(64, puesto)));
        Component nombre = Component.text("Tú: ", Paleta.TEXTO).append(puesto > 0
                ? Component.text(puesto + ".º", colorPuesto(puesto))
                : Component.text(mio > 0 ? "fuera del top 10" : "sin puesto", Paleta.TENUE));
        return Marco.icono(cabeza, nombre, lore, puesto > 0 && puesto <= PODIO.length);
    }

    // ------------------------------------------------------------------ clics

    @EventHandler
    public void alClic(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Vista v)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClick() != ClickType.LEFT) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getInventory().getSize()) return;
        String accion = v.acciones().get(slot);
        if (accion == null) return;
        long ahora = System.currentTimeMillis();
        Long antes = ultimoClic.get(p.getUniqueId());
        if (antes != null && ahora - antes < ESPERA_MS) return;
        ultimoClic.put(p.getUniqueId(), ahora);
        switch (accion) {
            case "cerrar" -> tarea(() -> {
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof Vista) p.closeInventory();
            });
            case "tablero" -> tarea(() -> {
                Tablero tab = hc.tablero();
                if (tab != null) tab.abrir(p);
                Marco.sonidoPestana(p);
            });
            default -> {
            }
        }
    }

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Vista) e.setCancelled(true);
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        ultimoClic.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ autotest (en "menus")

    static void autotest(Autotest.Hoja h) {
        Set<Integer> fijas = new HashSet<>(List.of(CABECERA, Marco.CERRAR, TABLERO, SALIDAS, PREMIOS));
        h.igual("cazador: casillas fijas sin repetir", 5, fijas.size());
        Set<Integer> filas = new HashSet<>();
        boolean bien = true;
        for (int f = 0; f < Marco.FILAS; f++) {
            int base = (f + 1) * 9;
            bien &= filas.add(base);
            for (int c : PODIO) bien &= filas.add(base + c) && !fijas.contains(base + c);
            bien &= filas.add(base + TU) && !fijas.contains(base + TU);
        }
        h.ok("cazador: cuatro tablas sin pisarse (rotulo, podio y tu cabeza)", bien);
        h.igual("cuatro tablas de serie", 4, Rankings.TABLAS.size());
        h.igual("premio completo", "30 Esencias, 2 Llaves del Caos y [ÁNIMA] 7 días",
                premio(new Rankings.Premio(30, 2, List.of("lp user %jugador% ..."))));
        h.igual("premio de una llave", "10 Esencias y 1 Llave del Caos", premio(new Rankings.Premio(10, 1, List.of())));
        h.igual("premio vacio", "el puesto", premio(new Rankings.Premio(0, 0, List.of())));
    }
}
