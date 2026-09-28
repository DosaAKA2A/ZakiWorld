package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Los rankings de Calamity en un menu: lo que abre Rhen, el Cazador de la antesala, y el boton
 * del Tablero (1.3.0; rehecho en la 1.7.2).
 *
 * Por que es asi: la version de antes ensenaba un ranking cada vez, con un podio de bloques de
 * oro, hierro y cobre, cabezas con el puesto en el numero de la pila, un libro, un marco, un
 * cofre y pestanas abajo, y Dosa: "Este ranking aun me cuesta muchisimo entenderlo". No se
 * sabia que categoria era cada cosa ni quien iba primero. Ahora todo se lee de un vistazo:
 *
 *   ventana de 45, marco de cristal negro (Marco.rellenarNegro)
 *   fila 0:    informacion (4): semanal o historico, cuando se reinicia y que premios da
 *   fila 1:    las categorias con premio (Extraido, Cazador, Segador, Superviviente)
 *   fila 2:    Esencias ganadas, Reliquias vendidas, salidas con vida y minijefes
 *   fila 3:    contratos (y en el historico, horas activas y saldo de Esencias)
 *   fila 4:    Esta semana / Historico (38) · Cerrar (40) · Tablero (42), como el Mercader
 *
 * Una categoria es un solo objeto: su nombre dice que es ("Mas Parcas abatidas esta semana"),
 * la primera linea del lore que mide, luego el top 10 ("1. Nombre — valor") y al final tu puesto
 * y lo que llevas. Las filas van centradas y con una casilla de aire entre cada dos (Marco
 * .columnas con 4 como mucho). Nada de podios ni numeros de pila.
 *
 * Los datos son las clasificaciones de Rankings (la cache de Tops que se rehace cada minuto): la
 * semana en curso (stats-semana) o el total (stats, horas activas y saldo). Es la clasificacion
 * tal cual, sin el reparto de premios del cierre: el lunes cobra quien cumpla las salidas y no
 * pase de las tablas maximas, y eso lo explica el objeto de informacion.
 */
final class MenuCazador implements Listener {

    private static final long ESPERA_MS = 500;
    /** La ventana: 5 filas, como el Mercader. */
    static final int TAMANO = 45;
    /** Arriba en el centro: que ranking es, cuando se reinicia y que premios da. */
    static final int INFO = 4;
    /** Las filas de las categorias. */
    static final int[] FILAS = {9, 18, 27};
    /** Abajo: cambiar de periodo, Cerrar y el Tablero, en las columnas 2, 4 y 6. */
    static final int CAMBIAR = 38, CERRAR = 40, TABLERO = 42;
    /** Como mucho cuatro por fila: asi siempre queda una casilla libre entre cada dos. */
    static final int POR_FILA = 4;
    /** Cuantos salen con nombre en el lore de cada categoria. */
    static final int TOP = 10;

    /**
     * Una categoria del ranking. clave: la de Estadisticas (o horas-activas / esencias, que solo
     * tienen total). fila: 1, 2 o 3. nombreSemana es null si esa clave no tiene ranking semanal.
     */
    record Categoria(String id, String clave, int fila, Material icono, String nombreSemana, String nombreSiempre,
                     String mide) {

        boolean semanal() {
            return nombreSemana != null;
        }

        String nombre(boolean semana) {
            return semana ? nombreSemana : nombreSiempre;
        }
    }

    /**
     * Todas, en su orden. Las cuatro de la fila 1 son las tablas de Rankings.TABLAS (mismo id),
     * las que pagan el lunes. ecos-cerrados no sale: sube a la vez que cazas-validas y seria
     * Cazador repetido.
     */
    static final List<Categoria> CATEGORIAS = List.of(
            new Categoria("extraido", "tasado-mc", 1, Material.GOLD_INGOT,
                    "Más MobCoins ganadas esta semana", "Más MobCoins ganadas en total",
                    "MobCoins que te pagan al vender lo que sacas."),
            new Categoria("cazador", "cazas-validas", 1, Material.ECHO_SHARD,
                    "Más Ecos ajenos cazados esta semana", "Más Ecos ajenos cazados en total",
                    "Ecos de otros jugadores que has cerrado."),
            new Categoria("segador", "parcas", 1, Material.NETHERITE_HOE,
                    "Más Parcas abatidas esta semana", "Más Parcas abatidas en total",
                    "Parcas que has ayudado a tumbar."),
            new Categoria("superviviente", "expedicion-max-seg", 1, Material.TOTEM_OF_UNDYING,
                    "Expedición más larga de la semana", "Expedición más larga de siempre",
                    "Lo que duró tu expedición más larga."),
            new Categoria("esencias", "tasado-esencias", 2, Material.GHAST_TEAR,
                    "Más Esencias ganadas esta semana", "Más Esencias ganadas en total",
                    "Esencias que te pagan al vender lo que sacas."),
            new Categoria("reliquias", "reliquias", 2, Material.RESIN_CLUMP,
                    "Más Reliquias vendidas esta semana", "Más Reliquias vendidas en total",
                    "Reliquias que has sacado vivo y vendido."),
            new Categoria("extracciones", "extracciones", 2, Material.IRON_DOOR,
                    "Más salidas con vida esta semana", "Más salidas con vida en total",
                    "Veces que has salido vivo de Calamity."),
            new Categoria("minijefes", "minijefes", 2, Material.WITHER_SKELETON_SKULL,
                    "Más minijefes abatidos esta semana", "Más minijefes abatidos en total",
                    "Minijefes en cuya muerte has participado."),
            new Categoria("contratos", "contratos", 3, Material.PAPER,
                    "Más contratos cobrados esta semana", "Más contratos cobrados en total",
                    "Contratos de Oren cumplidos y cobrados."),
            new Categoria("horas", "horas-activas", 3, Material.COMPASS,
                    null, "Más horas activas en Calamity",
                    "Tiempo dentro en el que te has movido."),
            new Categoria("saldo", Tops.ESENCIAS, 3, Material.ENDER_CHEST,
                    null, "Mayor saldo de Esencias",
                    "Las Esencias que tienes guardadas ahora."));

    /** Nuestra ventana: lo que hace cada casilla y si se mira la semana o el historico. */
    static final class Vista implements InventoryHolder {
        final Map<Integer, String> acciones = new HashMap<>();
        final boolean semana;

        Vista(boolean semana) {
            this.semana = semana;
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

    // ------------------------------------------------------------------ reparto (sin Bukkit)

    /** Las categorias que salen en ese periodo, en su orden. */
    static List<Categoria> categorias(boolean semana) {
        List<Categoria> out = new ArrayList<>();
        for (Categoria c : CATEGORIAS) if (!semana || c.semanal()) out.add(c);
        return out;
    }

    /**
     * Donde va cada categoria (casilla -> categoria): cada fila centrada con Marco.columnas, con
     * una casilla libre entre cada dos. Una fila con mas de POR_FILA sigue en la siguiente.
     */
    static Map<Integer, Categoria> casillas(boolean semana) {
        Map<Integer, List<Categoria>> porFila = new LinkedHashMap<>();
        for (Categoria c : categorias(semana)) porFila.computeIfAbsent(c.fila(), k -> new ArrayList<>()).add(c);
        List<List<Categoria>> filas = new ArrayList<>();
        for (List<Categoria> l : porFila.values()) {
            for (int i = 0; i < l.size(); i += POR_FILA) filas.add(l.subList(i, Math.min(l.size(), i + POR_FILA)));
        }
        Map<Integer, Categoria> out = new LinkedHashMap<>();
        for (int f = 0; f < filas.size() && f < FILAS.length; f++) {
            List<Categoria> l = filas.get(f);
            int[] cols = Marco.columnas(l.size());
            for (int i = 0; i < cols.length; i++) out.put(FILAS[f] + cols[i], l.get(i));
        }
        return out;
    }

    // ------------------------------------------------------------------ textos (sin Bukkit)

    /** El valor como se lee en el menu, con su unidad: "1.234 MC", "3 Parcas", "1 h 05 min". */
    static String valor(String clave, long v) {
        return switch (clave) {
            case "tasado-mc" -> Altar.miles(v) + " MC";
            case "expedicion-max-seg", "horas-activas" -> Tops.texto(clave, v);
            case "tasado-esencias", Tops.ESENCIAS -> Marco.esencias(v);
            case "cazas-validas" -> Altar.miles(v) + (v == 1 ? " Eco" : " Ecos");
            case "parcas" -> Altar.miles(v) + (v == 1 ? " Parca" : " Parcas");
            case "reliquias" -> Altar.miles(v) + (v == 1 ? " Reliquia" : " Reliquias");
            case "extracciones" -> Altar.miles(v) + (v == 1 ? " salida" : " salidas");
            case "minijefes" -> Altar.miles(v) + (v == 1 ? " minijefe" : " minijefes");
            case "contratos" -> Altar.miles(v) + (v == 1 ? " contrato" : " contratos");
            default -> Altar.miles(v);
        };
    }

    /** Oro, plata y bronce en tonos claros (se leen sobre el globo); del 4.o en adelante, apagado. */
    static TextColor colorPuesto(int puesto) {
        return switch (puesto) {
            case 1 -> TextColor.color(0xFFD27A);
            case 2 -> TextColor.color(0xD8DEE6);
            case 3 -> TextColor.color(0xE0A878);
            default -> Paleta.TENUE;
        };
    }

    /**
     * El lore de una categoria: que mide, si da premio, el top 10 ("1. Nombre — valor") y tu
     * puesto con lo que llevas. conPremio: si es una de las tablas que pagan el lunes (solo en
     * la semana); puestos: cuantos puestos pagan.
     */
    static List<Component> lore(Categoria c, Tops.Clasificacion cl, UUID yo, boolean semana, boolean conPremio, int puestos) {
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.tenue(c.mide()));
        if (semana && conPremio) {
            lore.add(Component.text(puestos == 1 ? "Da premio al 1.º el lunes." : "Da premio a los " + puestos + " primeros el lunes.",
                    Paleta.CIFRA));
        }
        lore.add(Component.empty());
        List<Rankings.Fila> top = cl.top();
        if (top.isEmpty()) lore.add(Marco.tenue(semana ? "Esta semana aún no hay nadie." : "Todavía no hay nadie."));
        for (int i = 0; i < top.size() && i < TOP; i++) {
            Rankings.Fila f = top.get(i);
            boolean soyYo = f.jugador().equals(yo);
            lore.add(Component.text((i + 1) + ". ", colorPuesto(i + 1))
                    .append(Component.text(f.nombre(), soyYo ? Paleta.BIEN : Paleta.TEXTO))
                    .append(Component.text(" — ", Paleta.SEPARADOR))
                    .append(Component.text(valor(c.clave(), f.valor()), Paleta.CIFRA)));
        }
        lore.add(Component.empty());
        Integer puesto = yo == null ? null : cl.puestos().get(yo);
        if (puesto == null) {
            lore.add(Component.text("Tu puesto: ", Paleta.TENUE).append(Component.text("aún no puntúas", Paleta.TENUE)));
        } else {
            long mio = cl.valores().getOrDefault(yo, 0L);
            lore.add(Component.text("Tu puesto: ", Paleta.TENUE)
                    .append(Component.text(puesto + ".º", puesto <= 3 ? colorPuesto(puesto) : Paleta.BIEN))
                    .append(Component.text(" — ", Paleta.SEPARADOR))
                    .append(Component.text(valor(c.clave(), mio), Paleta.CIFRA)));
        }
        return lore;
    }

    /** "30 Esencias, 2 Llaves del Caos y [ÁNIMA] 7 días": lo mismo que dice el aviso del cierre. */
    static String premio(Rankings.Premio pr) {
        List<String> partes = new ArrayList<>();
        if (pr.esencias() > 0) partes.add(pr.esencias() + " Esencias");
        if (pr.llaves() > 0) partes.add(pr.llaves() + (pr.llaves() == 1 ? " Llave del Caos" : " Llaves del Caos"));
        if (!pr.comandos().isEmpty()) partes.add("[ÁNIMA] 7 días");
        if (partes.isEmpty()) return "el puesto";
        return lista(partes);
    }

    /** "Extraído, Cazador y Segador". */
    static String lista(List<String> cosas) {
        if (cosas.isEmpty()) return "";
        if (cosas.size() == 1) return cosas.get(0);
        return String.join(", ", cosas.subList(0, cosas.size() - 1)) + " y " + cosas.get(cosas.size() - 1);
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

    // ------------------------------------------------------------------ abrir y pintar

    void abrir(Player p) {
        abrir(p, true, true);
    }

    private void abrir(Player p, boolean semana, boolean conSonido) {
        Rankings r = hc.rankings();
        if (r == null || !r.activo()) {
            p.sendMessage(ComandoCalamity.mensaje("Los rankings no están abiertos ahora mismo."));
            return;
        }
        Vista v = new Vista(semana);
        Marco.Titulo t = semana ? Marco.T_RANKINGS : Marco.T_RANKINGS_HISTORICO;
        Inventory inv = hc.plugin().getServer().createInventory(v, TAMANO, t.componente());
        pintar(inv, p, v, r);
        p.openInventory(inv);
        if (conSonido) Marco.sonar(p, "item.book.page_turn", 0.8f, 1.1f);
    }

    private void pintar(Inventory inv, Player p, Vista v, Rankings r) {
        inv.clear();
        v.acciones.clear();
        UUID u = p.getUniqueId();
        Set<String> conPremio = new HashSet<>(r.tablas());
        int puestos = Math.max(1, r.premios().size());

        inv.setItem(INFO, v.semana ? infoSemana(p, r) : infoHistorico());

        for (Map.Entry<Integer, Categoria> e : casillas(v.semana).entrySet()) {
            Categoria c = e.getValue();
            Tops.Clasificacion cl = r.clasificacion(c.clave(), v.semana);
            boolean premio = v.semana && conPremio.contains(c.id());
            Integer puesto = cl.puestos().get(u);
            // Brilla la categoria en la que vas en puesto de premio: se ve sin pasar el raton.
            boolean brillo = premio && puesto != null && puesto <= puestos;
            inv.setItem(e.getKey(), Marco.icono(c.icono(), Component.text(c.nombre(v.semana), premio ? Paleta.MARCA : Paleta.DETALLE),
                    lore(c, cl, u, v.semana, premio, puestos), brillo));
        }

        // Abajo: el otro periodo, Cerrar y el Tablero.
        if (v.semana) {
            inv.setItem(CAMBIAR, Marco.icono(Material.BOOKSHELF, Component.text("Ver el histórico", Paleta.DETALLE), List.of(
                    Marco.dato("Ahora ves", "esta semana"),
                    Component.empty(),
                    Marco.tenue("Todo lo hecho desde que abrió"),
                    Marco.tenue("Calamity. No se reinicia."),
                    Component.empty(),
                    Marco.accion("Clic para verlo")), false));
        } else {
            inv.setItem(CAMBIAR, Marco.icono(Material.CLOCK, Component.text("Ver esta semana", Paleta.DETALLE), List.of(
                    Marco.dato("Ahora ves", "el histórico"),
                    Component.empty(),
                    Marco.tenue("Lo de esta semana, que es"),
                    Marco.tenue("lo que da premios el lunes."),
                    Component.empty(),
                    Marco.accion("Clic para verlo")), false));
        }
        v.acciones.put(CAMBIAR, v.semana ? "historico" : "semana");

        inv.setItem(CERRAR, Marco.icono(Material.BARRIER, Component.text("Cerrar", Marco.NO),
                List.of(Marco.accion("Clic para cerrar")), false));
        v.acciones.put(CERRAR, "cerrar");

        Tablero tab = hc.tablero();
        boolean tablero = tab != null && hc.valor("tablero", tab::activo, false);
        inv.setItem(TABLERO, Marco.boton(Material.ITEM_FRAME, "Tablero", List.of("Ecos con botín y Parcas sueltas.",
                "Quien caza, sube en Cazador y Segador."), tablero ? "Clic para abrirlo" : "Próximamente.", tablero));
        if (tablero) v.acciones.put(TABLERO, "tablero");

        Marco.rellenarNegro(inv);
    }

    /** Arriba, en la semana: que es, cuando se reinicia, los premios de la config y las reglas para cobrar. */
    private ItemStack infoSemana(Player p, Rankings r) {
        ConfigurationSection c = hc.cfg();
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.texto("Cuenta lo que haces en Calamity"));
        lore.add(Marco.texto("de lunes a domingo."));
        lore.add(Component.empty());
        Calendario cal = hc.calendario();
        ZoneId zona = cal != null ? cal.zona() : ZoneId.systemDefault();
        lore.add(Marco.dato("Se reinicia", "el lunes a las 00:00"));
        lore.add(Marco.dato("Quedan", hastaCierre(ZonedDateTime.now(zona))));
        lore.add(Component.empty());

        List<String> nombres = new ArrayList<>();
        for (String id : r.tablas()) {
            Rankings.Tabla t = Rankings.tabla(id);
            if (t != null) nombres.add(t.nombre());
        }
        List<Rankings.Premio> premios = r.premios();
        lore.add(Marco.texto("Premios de " + lista(nombres) + ","));
        lore.add(Marco.texto("la primera fila, en cada una:"));
        for (int i = 0; i < premios.size(); i++) {
            lore.add(Component.text((i + 1) + ".º  ", colorPuesto(i + 1)).append(Marco.texto(premio(premios.get(i)))));
        }
        lore.add(Component.empty());
        int minimo = c.getInt("ranking.minimo-extracciones", 3);
        Estadisticas st = hc.estadisticas();
        long salidas = st == null ? 0 : st.semana(p.getUniqueId(), "extracciones");
        lore.add(Marco.tenue("Para cobrar hacen falta " + minimo + " salidas"));
        lore.add(Marco.tenue("con vida en la semana."));
        lore.add(salidas >= minimo ? Marco.tiene("Llevas " + salidas + ": esta semana cobras.")
                : Marco.falta("Llevas " + salidas + " de " + minimo, "te faltan " + (minimo - salidas)));
        lore.add(Marco.tenue("Como mucho cobras en " + Math.max(1, c.getInt("ranking.maximo-tablas", 2)) + " rankings."));
        lore.add(Marco.tenue("Con menos de " + c.getInt("ranking.minimo-elegibles", 8) + " jugadores, solo cobra el 1.º."));
        lore.add(Component.empty());
        lore.add(Marco.tenue("Las demás categorías no dan premio."));
        return Marco.icono(Material.KNOWLEDGE_BOOK, Component.text("Ranking semanal", Paleta.MARCA), lore, false);
    }

    /** Arriba, en el historico: que es y que no da premios. */
    private static ItemStack infoHistorico() {
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.texto("Todo lo hecho en Calamity desde"));
        lore.add(Marco.texto("que abrió. No se reinicia nunca."));
        lore.add(Component.empty());
        lore.add(Marco.tenue("No da premios: los premios son"));
        lore.add(Marco.tenue("del ranking semanal."));
        return Marco.icono(Material.KNOWLEDGE_BOOK, Component.text("Ranking histórico", Paleta.MARCA), lore, false);
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
        switch (accion) {
            // El titulo cambia (semanal / historico): se abre otra ventana en el mismo sitio.
            case "semana", "historico" -> tarea(() -> {
                abrir(p, accion.equals("semana"), false);
                Marco.sonidoPestana(p);
            });
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
        // Cada categoria: id y clave unicos, icono, nombre en los periodos en que sale y que mide.
        Set<String> ids = new HashSet<>(), claves = new HashSet<>();
        boolean completas = true;
        for (Categoria c : CATEGORIAS) {
            completas &= ids.add(c.id()) && claves.add(c.clave()) && c.icono() != null
                    && c.nombreSiempre() != null && !c.nombreSiempre().isBlank()
                    && (c.nombreSemana() == null || !c.nombreSemana().isBlank())
                    && c.mide() != null && !c.mide().isBlank() && c.fila() >= 1 && c.fila() <= FILAS.length;
        }
        h.ok("ranking: cada categoria con id, clave, icono, nombre y que mide, sin repetir", completas);
        Set<Material> iconos = new HashSet<>();
        for (Categoria c : CATEGORIAS) iconos.add(c.icono());
        h.igual("ranking: un icono distinto por categoria", CATEGORIAS.size(), iconos.size());

        // Las tablas con premio de Rankings son categorias de la fila 1, con su mismo id.
        boolean tablas = true;
        for (Rankings.Tabla t : Rankings.TABLAS) {
            Categoria c = null;
            for (Categoria x : CATEGORIAS) if (x.id().equals(t.id())) c = x;
            tablas &= c != null && c.clave().equals(t.estadistica()) && c.fila() == 1 && c.semanal();
        }
        h.ok("ranking: las 4 tablas con premio arriba, con su estadistica", tablas);

        // Las claves semanales existen en Estadisticas (si no, nunca tendrian a nadie).
        boolean existen = true;
        for (Categoria c : categorias(true)) existen &= Estadisticas.CLAVES.contains(c.clave());
        h.ok("ranking: las claves de la semana son de Estadisticas", existen);
        h.igual("ranking: 9 categorias en la semana", 9, categorias(true).size());
        h.igual("ranking: 11 categorias en el historico", 11, categorias(false).size());

        for (boolean semana : new boolean[]{true, false}) {
            String q = semana ? "semana" : "historico";
            Map<Integer, Categoria> cas = casillas(semana);
            h.igual("ranking " + q + ": todas las categorias tienen casilla", categorias(semana).size(), cas.size());
            List<Integer> todas = new ArrayList<>(cas.keySet());
            todas.addAll(List.of(INFO, CAMBIAR, CERRAR, TABLERO));
            h.igual("ranking " + q + ": ninguna casilla usada dos veces", todas.size(), new HashSet<>(todas).size());
            boolean dentro = true;
            for (int s : cas.keySet()) dentro &= s >= 0 && s < TAMANO && !Marco.esBorde(s, TAMANO);
            h.ok("ranking " + q + ": las categorias dentro del marco", dentro);
            // Cada fila centrada (columna primera + ultima = 8) y con el mismo aire (de 2 en 2).
            Map<Integer, List<Integer>> filas = new HashMap<>();
            for (int s : cas.keySet()) filas.computeIfAbsent(s / 9, k -> new ArrayList<>()).add(s % 9);
            boolean centradas = true;
            for (List<Integer> cols : filas.values()) {
                cols.sort(Integer::compare);
                centradas &= cols.get(0) + cols.get(cols.size() - 1) == 8;
                for (int i = 1; i < cols.size(); i++) centradas &= cols.get(i) - cols.get(i - 1) == 2;
            }
            h.ok("ranking " + q + ": filas centradas y con el mismo espaciado", centradas);
        }
        h.ok("ranking: informacion arriba en el centro", INFO / 9 == 0 && INFO % 9 == 4);
        h.ok("ranking: abajo cambiar, Cerrar y Tablero (38, 40, 42) como el Mercader",
                CAMBIAR == 38 && CERRAR == 40 && TABLERO == 42 && CERRAR / 9 == TAMANO / 9 - 1);

        // El lore: que mide, el top 10 como "1. Nombre — valor", y tu puesto con lo que llevas.
        Map<UUID, Long> datos = new LinkedHashMap<>();
        for (int i = 1; i <= 12; i++) datos.put(Autotest.sintetico(100 + i), 1000L - i * 10);
        Function<UUID, String> nombres = u -> "J" + (u.getLeastSignificantBits() & 0xFFF);
        Tops.Clasificacion cl = Tops.clasificacion(datos, TOP, nombres);
        UUID yo = Autotest.sintetico(112);
        PlainTextComponentSerializer plano = PlainTextComponentSerializer.plainText();
        boolean loreBien = true;
        for (Categoria c : CATEGORIAS) {
            List<String> l = new ArrayList<>();
            for (Component x : lore(c, cl, yo, c.semanal(), c.fila() == 1, 3)) l.add(plano.serialize(x));
            loreBien &= l.get(0).equals(c.mide()) && l.stream().filter(s -> s.matches("\\d+\\. .+ — .+")).count() == TOP
                    && l.get(l.size() - 1).startsWith("Tu puesto: 12.º — ");
        }
        h.ok("ranking: cada categoria con que mide, 10 lineas de top y tu puesto", loreBien);
        Categoria extraido = CATEGORIAS.get(0);
        List<String> ej = new ArrayList<>();
        for (Component x : lore(extraido, cl, yo, true, true, 3)) ej.add(plano.serialize(x));
        h.igual("ranking: la 1.a linea del top", "1. " + nombres.apply(Autotest.sintetico(101)) + " — 990 MC", ej.get(3));
        h.igual("ranking: premio en las de arriba", "Da premio a los 3 primeros el lunes.", ej.get(1));
        h.igual("ranking: tu puesto al final", "Tu puesto: 12.º — 880 MC", ej.get(ej.size() - 1));
        List<String> vacio = new ArrayList<>();
        for (Component x : lore(extraido, Tops.Clasificacion.VACIA, yo, true, false, 3)) vacio.add(plano.serialize(x));
        h.ok("ranking: sin nadie lo dice y sin puesto", vacio.contains("Esta semana aún no hay nadie.")
                && vacio.get(vacio.size() - 1).equals("Tu puesto: aún no puntúas"));

        h.igual("valor con unidad: una Parca", "1 Parca", valor("parcas", 1));
        h.igual("valor con unidad: esencias", "1.500 Esencias", valor("tasado-esencias", 1500));
        h.igual("valor con unidad: saldo", "1 Esencia", valor(Tops.ESENCIAS, 1));
        h.igual("valor con unidad: expedicion", "1 h 05 min", valor("expedicion-max-seg", 3900));
        h.igual("lista de tablas", "Extraído, Cazador y Segador", lista(List.of("Extraído", "Cazador", "Segador")));

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
}
