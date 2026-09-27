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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Los rankings de la semana en un menu (1.3.0; 1.3.1): lo que abre el Cazador de la antesala
 * (antes los escribia en el chat) y el boton del Tablero.
 *
 * En la 1.3.0 era una fila por ranking con un icono suelto a la izquierda de rotulo (girasol,
 * fragmento de eco, calavera, totem) y Dosa: "las categorias de la izquierda no se entienden".
 * Ahora se ve un ranking cada vez y se entiende solo con mirar:
 *  - abajo, una pestana por ranking (Extraido, Cazador, Segador, Superviviente) con que mide,
 *    quien lo lidera y tu puesto; la que miras brilla y dice "Estas aqui";
 *  - en el medio, el podio de ese ranking con forma de podio: el 1.o arriba en el centro sobre
 *    oro, el 2.o a su izquierda sobre hierro y el 3.o a su derecha sobre cobre (el puesto va en
 *    el numero de la pila y la cifra en el lore);
 *  - arriba, el ranking que miras y que mide; a los lados del podio, Tu (tu puesto, lo que te
 *    falta para el 3.o y tus salidas), del 4.o al 10.o, los premios con lo que falta para el
 *    cierre, y el Tablero.
 * Cambiar de pestana repinta la misma ventana (sin cerrarla: el raton no salta al centro).
 *
 * Es la clasificacion tal cual (Rankings.podioSemana, la cache del minuto), sin el reparto de
 * premios del cierre: el lunes cobra quien cumpla las salidas y no pase de las tablas maximas.
 */
final class MenuCazador implements Listener {

    private static final long ESPERA_MS = 500;
    /** Arriba en el centro: el ranking que miras. */
    static final int CABECERA = 4;
    /** El podio: 1.o arriba en el centro, 2.o una fila mas abajo a su izquierda, 3.o otra mas abajo a su derecha. */
    static final int PRIMERO = 13, SEGUNDO = 21, TERCERO = 32;
    /** Los pedestales, hasta la fila 4: oro bajo el 1.o, hierro bajo el 2.o y cobre bajo el 3.o. */
    static final int[] ORO = {22, 31, 40}, PLATA = {30, 39}, BRONCE = {41};
    /** A los lados del podio: Tu y del 4.o al 10.o a la izquierda; los premios y el Tablero a la derecha. */
    static final int TU = 19, RESTO = 37, PREMIOS = 25, TABLERO = 43;
    /** Las pestanas, en la fila de abajo. */
    static final int FILA_PESTANAS = 45;

    /** Nuestra ventana: lo que hace cada casilla y el ranking que se mira (cambia sin cerrarla). */
    static final class Vista implements InventoryHolder {
        final Map<Integer, String> acciones = new HashMap<>();
        String tabla;

        Vista(String tabla) {
            this.tabla = tabla;
        }

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
        Vista v = new Vista(null);
        Inventory inv = hc.plugin().getServer().createInventory(v, 54, Marco.T_RANKINGS.componente());
        pintar(inv, p, v, r);
        p.openInventory(inv);
        Marco.sonar(p, "item.book.page_turn", 0.8f, 1.1f);
    }

    private void repintar(Player p) {
        Rankings r = hc.rankings();
        if (!p.isOnline() || r == null) return;
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof Vista v) pintar(top, p, v, r);
    }

    private void pintar(Inventory inv, Player p, Vista v, Rankings r) {
        inv.clear();
        v.acciones.clear();
        UUID u = p.getUniqueId();
        ConfigurationSection c = hc.cfg();
        int minimo = c.getInt("ranking.minimo-extracciones", 3);
        Estadisticas st = hc.estadisticas();
        long salidas = st == null ? 0 : st.semana(u, "extracciones");

        Map<Rankings.Tabla, List<Rankings.Fila>> podios = r.podioSemana(10);
        // La que se pide; si no esta (o es la primera vez), la primera de ranking.tablas.
        Rankings.Tabla t = null;
        for (Rankings.Tabla x : podios.keySet()) {
            if (t == null) t = x;
            if (x.id().equals(v.tabla)) t = x;
        }
        inv.setItem(Marco.CERRAR, Marco.cerrar());
        v.acciones.put(Marco.CERRAR, "cerrar");
        if (t == null) {
            Marco.rellenar(inv);
            return;
        }
        v.tabla = t.id();
        List<Rankings.Fila> top = podios.get(t);

        inv.setItem(CABECERA, Marco.icono(iconoTabla(t.id()), Component.text("Ranking: " + t.nombre(), Paleta.MARCA), List.of(
                Marco.texto(queMide(t.id())),
                Component.empty(),
                Marco.tenue("Los tres primeros cobran el lunes."),
                Marco.tenue("Abajo, los otros rankings.")), false));

        int[] podio = {PRIMERO, SEGUNDO, TERCERO};
        for (int i = 0; i < podio.length; i++) inv.setItem(podio[i], i < top.size() ? podio(top.get(i), i + 1, t, u) : libre(i + 1));
        for (int s : ORO) inv.setItem(s, pedestal(Material.GOLD_BLOCK));
        for (int s : PLATA) inv.setItem(s, pedestal(Material.IRON_BLOCK));
        for (int s : BRONCE) inv.setItem(s, pedestal(Material.COPPER_BLOCK));

        inv.setItem(TU, tu(p, t, top, salidas, minimo));
        inv.setItem(RESTO, resto(t, top, u));
        inv.setItem(PREMIOS, premios(r, c));

        Tablero tab = hc.tablero();
        boolean tablero = tab != null && hc.valor("tablero", tab::activo, false);
        inv.setItem(TABLERO, Marco.boton(Material.ITEM_FRAME, "Tablero", List.of("Ecos con botín y Parcas sueltas.",
                "Quien caza, sube en Cazador y Segador."), tablero ? "Clic para abrirlo" : "Próximamente.", tablero));
        if (tablero) v.acciones.put(TABLERO, "tablero");

        List<Rankings.Tabla> tablas = new ArrayList<>(podios.keySet());
        int[] cols = Marco.columnas(tablas.size());
        for (int i = 0; i < cols.length; i++) {
            Rankings.Tabla x = tablas.get(i);
            int casilla = FILA_PESTANAS + cols[i];
            inv.setItem(casilla, pestana(x, podios.get(x), x.id().equals(t.id()), u));
            if (!x.id().equals(t.id())) v.acciones.put(casilla, "tabla:" + x.id());
        }
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

    static String queMide(String id) {
        return switch (id) {
            case "extraido" -> "MobCoins ganadas vendiendo esta semana.";
            case "cazador" -> "Ecos de otros jugadores cazados esta semana.";
            case "segador" -> "Parcas abatidas esta semana.";
            case "superviviente" -> "La expedición más larga de la semana.";
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

    /** Un bloque del pedestal: solo forma, sin globo (no es algo que mirar ni que pulsar). */
    private static ItemStack pedestal(Material m) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setHideTooltip(true);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** Una pestana de abajo: el ranking, que mide, quien lo lidera y tu puesto; la que miras brilla. */
    private static ItemStack pestana(Rankings.Tabla t, List<Rankings.Fila> top, boolean aqui, UUID yo) {
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.tenue(queMide(t.id())));
        lore.add(Component.empty());
        if (top.isEmpty()) lore.add(Marco.tenue("Todavía no hay nadie en el ranking."));
        else lore.add(Marco.dato("Líder", top.get(0).nombre() + " · " + Npcs.valorRanking(t.estadistica(), top.get(0).valor())));
        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).jugador().equals(yo)) lore.add(Component.text("Tu puesto: " + (i + 1) + ".º", Paleta.BIEN));
        }
        lore.add(Component.empty());
        lore.add(aqui ? Component.text("● Estás aquí", Paleta.MARCA) : Marco.accion("Clic para verlo"));
        TextColor color = aqui ? Paleta.MARCA : Paleta.TEXTO;
        return Marco.icono(iconoTabla(t.id()), Component.text(aqui ? "▸ " + t.nombre() + " ◂" : t.nombre(), color), lore, aqui);
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
                List.of(Marco.tenue("Este puesto está libre. Puede ser tuyo.")), false);
    }

    /** Tu cabeza en ese ranking: tu puesto (si estas en el top 10), tu cifra, lo que te falta para el 3.o y tus salidas. */
    private ItemStack tu(Player p, Rankings.Tabla t, List<Rankings.Fila> top, long salidas, int minimo) {
        UUID u = p.getUniqueId();
        int puesto = 0;
        for (int i = 0; i < top.size(); i++) if (top.get(i).jugador().equals(u)) puesto = i + 1;
        Estadisticas st = hc.estadisticas();
        long mio = st == null ? 0 : st.semana(u, t.estadistica());
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.dato("Llevas", Npcs.valorRanking(t.estadistica(), mio)));
        if (puesto == 0 || puesto > 3) {
            if (top.size() >= 3) {
                long falta = top.get(2).valor() - mio + 1;
                lore.add(Marco.tenue("Para el 3.º te faltan " + Npcs.valorRanking(t.estadistica(), Math.max(1, falta)) + "."));
            } else {
                lore.add(Marco.tenue("Con cualquier cantidad entras en el podio."));
            }
        }
        lore.add(Component.empty());
        lore.add(Marco.dato("Salidas con vida", salidas + " de " + minimo));
        lore.add(Marco.barra(salidas, minimo));
        lore.add(salidas >= minimo ? Marco.tiene("Esta semana entras en el ranking.")
                : Marco.falta("Todavía no entras en el ranking", "te faltan " + (minimo - salidas) + " salidas"));
        ItemStack cabeza = Marco.cabeza(u);
        cabeza.setAmount(Math.max(1, Math.min(64, puesto)));
        Component nombre = Component.text("Tú: ", Paleta.TEXTO).append(puesto > 0
                ? Component.text(puesto + ".º", colorPuesto(puesto))
                : Component.text(mio > 0 ? "fuera del top 10" : "sin puesto", Paleta.TENUE));
        return Marco.icono(cabeza, nombre, lore, puesto > 0 && puesto <= 3);
    }

    /** Del 4.o al 10.o, una linea cada uno (tu nombre en verde). */
    private static ItemStack resto(Rankings.Tabla t, List<Rankings.Fila> top, UUID yo) {
        List<Component> lore = new ArrayList<>();
        for (int i = 3; i < top.size(); i++) {
            Rankings.Fila f = top.get(i);
            boolean soyYo = f.jugador().equals(yo);
            lore.add(Component.text((i + 1) + ".º  ", Paleta.TENUE).append(Component.text(f.nombre(), soyYo ? Paleta.BIEN : Paleta.TEXTO))
                    .append(Component.text(" · " + Npcs.valorRanking(t.estadistica(), f.valor()), Paleta.CIFRA)));
        }
        if (lore.isEmpty()) lore.add(Marco.tenue("Todavía no hay nadie más."));
        return Marco.icono(Material.BOOK, Component.text("Del 4.º al 10.º", Paleta.DETALLE), lore, false);
    }

    /** Los premios de cada puesto, cuando se cierra y las reglas para cobrar. */
    private ItemStack premios(Rankings r, ConfigurationSection c) {
        List<Rankings.Premio> premios = r.premios();
        List<Component> lore = new ArrayList<>();
        for (int i = 0; i < premios.size(); i++) {
            lore.add(Component.text((i + 1) + ".º  ", colorPuesto(i + 1)).append(Marco.texto(premio(premios.get(i)))));
        }
        lore.add(Marco.tenue("Lo mismo en cada ranking."));
        lore.add(Component.empty());
        Calendario cal = hc.calendario();
        ZoneId zona = cal != null ? cal.zona() : ZoneId.systemDefault();
        lore.add(Marco.texto("Se cierra el lunes a las 00:00."));
        lore.add(Marco.dato("Quedan", hastaCierre(ZonedDateTime.now(zona))));
        lore.add(Component.empty());
        lore.add(Marco.dato("Para cobrar", c.getInt("ranking.minimo-extracciones", 3) + " salidas con vida en la semana"));
        lore.add(Marco.tenue("Como mucho cobras en " + Math.max(1, c.getInt("ranking.maximo-tablas", 2)) + " rankings."));
        lore.add(Marco.tenue("Si en un ranking hay menos de " + c.getInt("ranking.minimo-elegibles", 8)));
        lore.add(Marco.tenue("jugadores, solo cobra el 1.º."));
        return Marco.icono(Material.CHEST, Component.text("Premios de la semana", Paleta.MARCA), lore, false);
    }

    /** Lo que falta para el cierre (el proximo lunes a las 00:00 en esa zona): "1 d 5 h", "5 h 12 min", "30 min". */
    static String hastaCierre(ZonedDateTime ahora) {
        ZonedDateTime lunes = ahora.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(ahora.getZone());
        long min = Math.max(0, Duration.between(ahora, lunes).toMinutes());
        long d = min / 1440, h = (min % 1440) / 60, m = min % 60;
        if (d > 0) return d + " d " + h + " h";
        if (h > 0) return h + " h " + m + " min";
        return Math.max(1, m) + " min";
    }

    // ------------------------------------------------------------------ clics

    @EventHandler
    public void alClic(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Vista v)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClick() != ClickType.LEFT) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getInventory().getSize()) return;
        String accion = v.acciones.get(slot);
        if (accion == null) return;
        long ahora = System.currentTimeMillis();
        Long antes = ultimoClic.get(p.getUniqueId());
        if (antes != null && ahora - antes < ESPERA_MS) return;
        ultimoClic.put(p.getUniqueId(), ahora);
        if (accion.startsWith("tabla:")) {
            v.tabla = accion.substring(6);
            tarea(() -> {
                repintar(p);
                Marco.sonidoPestana(p);
            });
            return;
        }
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
        // Nada pisado: cabecera, cerrar, podio, pedestales, lados y pestanas, cada uno en su casilla.
        List<Integer> todas = new ArrayList<>(List.of(CABECERA, Marco.CERRAR, PRIMERO, SEGUNDO, TERCERO, TU, RESTO, PREMIOS, TABLERO));
        for (int s : ORO) todas.add(s);
        for (int s : PLATA) todas.add(s);
        for (int s : BRONCE) todas.add(s);
        for (int c : Marco.columnas(Rankings.TABLAS.size())) todas.add(FILA_PESTANAS + c);
        h.igual("cazador: ninguna casilla usada dos veces", todas.size(), new HashSet<>(todas).size());
        boolean dentro = true;
        for (int s : List.of(PRIMERO, SEGUNDO, TERCERO, TU, RESTO, PREMIOS, TABLERO)) dentro &= !Marco.esBorde(s, 54);
        h.ok("cazador: podio y lados dentro del marco", dentro);
        boolean abajo = true;
        for (int c : Marco.columnas(Rankings.TABLAS.size())) abajo &= (FILA_PESTANAS + c) / 9 == 5;
        h.ok("cazador: las pestanas en la fila de abajo", abajo);

        // La forma de podio: el 1.o mas alto y en el centro, el 2.o a su izquierda, el 3.o a su derecha y mas bajo.
        h.ok("podio: 1.o en el centro, 2.o a la izquierda y 3.o a la derecha",
                PRIMERO % 9 == 4 && SEGUNDO % 9 == 3 && TERCERO % 9 == 5);
        h.ok("podio: 1.o mas alto que el 2.o, y el 2.o que el 3.o", PRIMERO / 9 < SEGUNDO / 9 && SEGUNDO / 9 < TERCERO / 9);
        h.ok("podio: cada pedestal justo debajo de su cabeza hasta la fila 4",
                pedestalBien(PRIMERO, ORO) && pedestalBien(SEGUNDO, PLATA) && pedestalBien(TERCERO, BRONCE));

        h.igual("cuatro rankings de serie", 4, Rankings.TABLAS.size());
        h.igual("premio completo", "30 Esencias, 2 Llaves del Caos y [ÁNIMA] 7 días",
                premio(new Rankings.Premio(30, 2, List.of("lp user %jugador% ..."))));
        h.igual("premio de una llave", "10 Esencias y 1 Llave del Caos", premio(new Rankings.Premio(10, 1, List.of())));
        h.igual("premio vacio", "el puesto", premio(new Rankings.Premio(0, 0, List.of())));

        // Lo que falta para el cierre (2026-09-26 es sabado).
        ZoneId madrid = ZoneId.of("Europe/Madrid");
        h.igual("cierre desde el sabado a las 19:00", "1 d 5 h", hastaCierre(ZonedDateTime.of(2026, 9, 26, 19, 0, 0, 0, madrid)));
        h.igual("cierre desde el domingo a las 23:30", "30 min", hastaCierre(ZonedDateTime.of(2026, 9, 27, 23, 30, 0, 0, madrid)));
        h.igual("cierre desde el domingo a las 18:48", "5 h 12 min", hastaCierre(ZonedDateTime.of(2026, 9, 27, 18, 48, 0, 0, madrid)));
        h.igual("el lunes a las 00:00 empieza otra semana", "7 d 0 h", hastaCierre(ZonedDateTime.of(2026, 9, 28, 0, 0, 0, 0, madrid)));
    }

    /** Que las casillas del pedestal esten en la columna de la cabeza, seguidas, desde la fila de debajo hasta la 4. */
    private static boolean pedestalBien(int cabeza, int[] bloques) {
        int fila = cabeza / 9 + 1;
        for (int b : bloques) {
            if (b % 9 != cabeza % 9 || b / 9 != fila) return false;
            fila++;
        }
        return fila == Marco.FILAS + 1;
    }
}
