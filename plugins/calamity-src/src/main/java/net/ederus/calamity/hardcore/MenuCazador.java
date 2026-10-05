package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * El ranking de Calamity en un menu: lo que abre Rhen, el Cazador de la antesala, y el boton del
 * Tablero (1.3.0; rehecho en la 1.7.2 y otra vez en la 1.7.4).
 *
 * Por que es asi: la 1.7.2 ensenaba todas las categorias a la vez, cada una con su top 10 en el
 * lore, y Dosa: "Todavia no lo entiendo. Me gustaria ver un menu seleccionable como en otros
 * menus, abajo para poner que tipo de rank es, con cabezas, del mismo tono pero diferentes para
 * entender que son de Calamity, y finalmente las cabezas de los tops por cada categoria". Ahora se
 * mira una categoria cada vez:
 *
 *   ventana de 54; lo vacio, cristal negro (Marco.rellenar)
 *   fila 0:    el libro de la categoria que miras (4): que mide, el premio del lunes, cuanto
 *              falta para el reinicio y las reglas para cobrar
 *   filas 1-3: su top 10 en piramide con la cabeza de cada jugador: el 1.o en 13, el 2.o y el 3.o
 *              en 21 y 23, del 4.o al 10.o en 28-34. Un puesto sin nadie, una cabeza gris con "?"
 *   fila 4:    el Tablero (40) en el centro, si el Tablero esta en marcha (1.12). Dosa retiro la
 *              fila entera en la 1.7.5 (Esta semana/Historico, tu cabeza, Cerrar y Tablero) y en la
 *              1.12 aprobo que el Tablero vuelva: sin el, Rhen solo lo abria con los rankings apagados
 *   fila 5:    el selector (45-53): una cabeza por categoria, todas del rojo de Calamity y cada una
 *              con su dibujo. La que miras brilla. Con mas de 9, la ultima casilla es "Mas ->" y
 *              lleva a la pagina siguiente, que empieza con "<- Anteriores".
 *
 * Elegir otra categoria repinta la misma ventana. El titulo dice cual es ("CALAMITY | Top ·
 * MobCoins"; "Ranking · MobCoins" no cabe en los 150 px de Marco) y, para cambiar un titulo,
 * Minecraft tiene que mandar la ventana otra vez: se abre encima de la que hay, sin cerrarla ni
 * mover el raton. Con ranking.menu.titulo-por-categoria en false, el titulo es "Ranking semanal" o
 * "Ranking historico" y cambiar de categoria solo repinta (en Bedrock no parpadea).
 *
 * Las categorias salen de ranking.menu.categorias (orden, estadistica, nombre, textura, vistas y
 * que mide); si el servidor no tiene ranking.menu, las del jar. Las cabezas las hace Cabezas.
 *
 * Los datos son las clasificaciones de Rankings (la cache de Tops que se rehace cada minuto): la
 * semana en curso (stats-semana) o el total (stats, horas activas y saldo). Es la clasificacion
 * tal cual, sin el reparto del cierre: el lunes cobra quien cumpla las salidas y no pase de las
 * tablas maximas, y eso lo explica el libro.
 *
 * Se pinta en dos pasos: plano() decide que va en cada casilla sin tocar Bukkit (lo prueba el
 * autotest) y poner() hace los objetos.
 */
final class MenuCazador implements Listener {

    private static final long ESPERA_MS = 300;
    /** La ventana: 6 filas. */
    static final int TAMANO = 54;
    /** Arriba en el centro: el libro de la categoria que miras. */
    static final int INFO = 4;
    /** El top 10 en piramide: el 1.o arriba, el 2.o y el 3.o debajo a los lados, del 4.o al 10.o en fila. */
    static final int[] PIRAMIDE = {13, 21, 23, 28, 29, 30, 31, 32, 33, 34};
    /** Cuantos salen con cabeza. */
    static final int TOP = PIRAMIDE.length;
    /** La fila 4: solo el Tablero, en el centro (1.12). */
    static final int TABLERO = 40;
    /** La fila 5, el selector de categorias: de la 45 a la 53. */
    static final int SELECTOR = 45, ANCHO_SELECTOR = 9;
    /** Largo de las lineas del lore (en letras), para que el globo no ocupe media pantalla. */
    static final int ANCHO_LORE = 32;
    /** Las estadisticas que no se apuntan por semanas: solo salen en el historico. */
    static final Set<String> SOLO_TOTAL = Set.of("horas-activas", Tops.ESENCIAS);
    /** Donde va el menu dentro de hardcore:. */
    static final String RUTA = "ranking.menu";
    /** Las cabezas de fuera del selector si la config no dice otras (las mismas que el config.yml). */
    static final String MAS = "fcfe8845a8d5e635fb87728ccc93895d42b4fc2e6a53f1ba78c845225822",
            ANTERIOR = "f84f597131bbe25dc058af888cb29831f79599bc67c95c802925ce4afba332fc",
            LIBRE = "89a995928090d842d4afdb2296ffe24f2e944272205ceba848ee4046e01f3168";

    /**
     * Una categoria de ranking.menu.categorias. clave: la estadistica (las de Estadisticas,
     * horas-activas o esencias, el saldo). titulo: lo que sale en el titulo de la ventana si no es
     * el nombre. semana / historico: en que vista sale. material: el respaldo de la textura.
     */
    record Categoria(String id, String clave, String nombre, String titulo, String mide, boolean semana,
                     boolean historico, String textura, Material material) {

        boolean en(boolean semanal) {
            return semanal ? semana : historico;
        }
    }

    /**
     * Calamity 1.11 · La categoria de los clanes (ClanesCalamity): va siempre al final y no esta en
     * ranking.menu.categorias, asi que sale aunque el servidor tenga su propia lista. Solo semanal.
     */
    static final String CLAVE_CLANES = "clanes";
    static final Categoria CLANES = new Categoria("clanes", CLAVE_CLANES, "Clanes", "Clanes",
            "Puntos de cada clan esta semana: lo que sus miembros sacan vivos, minijefes, Parcas y Bóvedas Caídas.",
            true, false, "", Material.RED_BANNER);

    /** Que es cada objeto del menu: uno normal, una cabeza de dibujo o la cabeza de un jugador. */
    enum Tipo { OBJETO, TEXTURA, JUGADOR }

    /**
     * Lo que va en una casilla, sin Bukkit: el plano del menu. material es el objeto (OBJETO) o el
     * respaldo de la textura (TEXTURA).
     */
    record Pieza(Tipo tipo, Material material, String textura, UUID jugador, Component nombre, List<Component> lore,
                 boolean brillo) {

        static Pieza objeto(Material m, Component nombre, List<Component> lore) {
            return new Pieza(Tipo.OBJETO, m, null, null, nombre, lore, false);
        }

        static Pieza textura(String textura, Material respaldo, Component nombre, List<Component> lore, boolean brillo) {
            return new Pieza(Tipo.TEXTURA, respaldo, textura, null, nombre, lore, brillo);
        }

        static Pieza jugador(UUID u, Component nombre, List<Component> lore) {
            return new Pieza(Tipo.JUGADOR, Material.PLAYER_HEAD, null, u, nombre, lore, false);
        }
    }

    /** Una casilla del selector: una categoria ("cat:<id>") o las flechas ("mas", "menos"). */
    record Hueco(int casilla, Categoria categoria, String accion) {
    }

    /** Lo que pide el cobro del lunes y cuantas salidas llevas tu. */
    record Reglas(int salidas, int maximoTablas, int elegibles, long llevas) {
    }

    /**
     * Todo lo que hace falta para pintar el menu. conPremio: las estadisticas de las tablas que
     * pagan el lunes (ranking.tablas). mas, anterior y libre: las texturas de esas cabezas.
     */
    record Datos(boolean semana, List<Categoria> visibles, Categoria actual, int pagina, Tops.Clasificacion cl, UUID yo,
                 Set<String> conPremio, List<Rankings.Premio> premios, Reglas reglas, String quedan, boolean tablero,
                 boolean tituloPorCategoria, String mas, String anterior, String libre) {
    }

    /** El menu entero: titulo, que va en cada casilla y que hace cada clic. */
    record Plano(Marco.Titulo titulo, Map<Integer, Pieza> piezas, Map<Integer, String> acciones) {
    }

    /** Nuestra ventana: la vista (periodo, categoria, pagina del selector) y lo que hace cada casilla. */
    static final class Vista implements InventoryHolder {
        final Map<Integer, String> acciones = new HashMap<>();
        /** Las cabezas de jugadores puestas (casilla -> pieza), para cambiarlas cuando llega su perfil. */
        final Map<Integer, Pieza> jugadores = new HashMap<>();
        boolean semana;
        String categoria;
        int pagina;
        Marco.Titulo titulo;
        Inventory inventario;

        @Override
        public Inventory getInventory() {
            return inventario;
        }
    }

    private final Hardcore hc;
    private final Cabezas cabezas;
    private final Map<UUID, Long> ultimoClic = new HashMap<>();
    /** La ultima categoria que miro cada jugador: al volver a abrir, sale esa. Solo en memoria. */
    private final Map<UUID, String> ultima = new HashMap<>();
    private final Set<BukkitTask> tareas = new HashSet<>();

    MenuCazador(Hardcore hc) {
        this.hc = hc;
        this.cabezas = new Cabezas(hc.plugin(), this::alLlegarPerfil);
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
    }

    void parar() {
        HandlerList.unregisterAll(this);
        cabezas.parar();
        for (BukkitTask t : tareas) t.cancel();
        tareas.clear();
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Vista) p.closeInventory();
        }
        ultimoClic.clear();
        ultima.clear();
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

    // ------------------------------------------------------------------ categorias (sin Bukkit)

    /**
     * Las categorias de una seccion como ranking.menu.categorias, en su orden. Una sin clave o sin
     * ninguna vista no sale. horas-activas y esencias (el saldo) no tienen semana: solo historico.
     * Lo que falte de una categoria del servidor con el mismo id que una del jar se coge del jar
     * (los defaults de Bukkit), y si no, lo de aqui.
     */
    static List<Categoria> leer(ConfigurationSection s) {
        List<Categoria> out = new ArrayList<>();
        if (s == null) return out;
        for (String id : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(id);
            if (c == null) continue;
            String clave = texto(c, "clave", "").toLowerCase(Locale.ROOT);
            if (clave.isEmpty()) continue;
            String nombre = texto(c, "nombre", id);
            String titulo = texto(c, "titulo", nombre);
            List<String> vistas = new ArrayList<>();
            Object v = c.get("vistas");
            if (v instanceof List<?> l) {
                for (Object o : l) vistas.add(String.valueOf(o).trim().toLowerCase(Locale.ROOT));
            } else if (v != null) {
                vistas.add(String.valueOf(v).trim().toLowerCase(Locale.ROOT));
            }
            boolean semana = vistas.isEmpty() || vistas.contains("semana");
            boolean historico = vistas.isEmpty() || vistas.contains("historico") || vistas.contains("histórico");
            if (SOLO_TOTAL.contains(clave)) semana = false;
            if (!semana && !historico) continue;
            Material m = Material.matchMaterial(texto(c, "material", ""));
            out.add(new Categoria(id.toLowerCase(Locale.ROOT), clave, nombre.isEmpty() ? id : nombre,
                    titulo.isEmpty() ? nombre : titulo, texto(c, "mide", ""), semana, historico,
                    texto(c, "textura", ""), m == null ? Material.PAPER : m));
        }
        return out;
    }

    /** Un texto de la config, sin espacios de mas; get() sin defecto mira tambien los del jar. */
    private static String texto(ConfigurationSection c, String clave, String def) {
        Object o = c.get(clave);
        return o == null ? def : String.valueOf(o).trim();
    }

    /** Las que salen en esa vista, en su orden. */
    static List<Categoria> visibles(List<Categoria> todas, boolean semana) {
        List<Categoria> out = new ArrayList<>();
        for (Categoria c : todas) if (c.en(semana)) out.add(c);
        return out;
    }

    /**
     * La categoria al abrir: la que se pide (o la ultima que miraste) si sale en esta vista; si no,
     * la primera que da premio; si no, la primera. Null si no hay ninguna.
     */
    static Categoria inicial(List<Categoria> visibles, Set<String> conPremio, String pedida) {
        if (visibles.isEmpty()) return null;
        if (pedida != null) for (Categoria c : visibles) if (c.id().equals(pedida)) return c;
        for (Categoria c : visibles) if (conPremio.contains(c.clave())) return c;
        return visibles.get(0);
    }

    // ------------------------------------------------------------------ reparto (sin Bukkit)

    /**
     * Las paginas del selector, como [desde, hasta) de la lista. Con 9 o menos, una. Con mas, 8 en
     * la primera (la 9.a casilla es "Mas ->"), 7 en las de en medio (flecha a cada lado) y hasta 8
     * en la ultima (la 1.a casilla es "<- Anteriores").
     */
    static List<int[]> paginas(int n) {
        List<int[]> out = new ArrayList<>();
        if (n <= ANCHO_SELECTOR) {
            out.add(new int[]{0, Math.max(0, n)});
            return out;
        }
        int i = ANCHO_SELECTOR - 1;
        out.add(new int[]{0, i});
        while (i < n) {
            int caben = n - i <= ANCHO_SELECTOR - 1 ? n - i : ANCHO_SELECTOR - 2;
            out.add(new int[]{i, i + caben});
            i += caben;
        }
        return out;
    }

    /** La pagina del selector en la que esta la categoria numero indice. */
    static int paginaDe(int n, int indice) {
        List<int[]> ps = paginas(n);
        for (int p = 0; p < ps.size(); p++) if (indice >= ps.get(p)[0] && indice < ps.get(p)[1]) return p;
        return 0;
    }

    /**
     * Las casillas del selector en esa pagina. Con una sola pagina, las cabezas van seguidas y
     * centradas; con varias, a partir de la 45 (o de la 46 detras de "<- Anteriores").
     */
    static List<Hueco> selector(List<Categoria> cats, int pagina) {
        List<Hueco> out = new ArrayList<>();
        if (cats.isEmpty()) return out;
        List<int[]> ps = paginas(cats.size());
        int p = Math.max(0, Math.min(pagina, ps.size() - 1));
        int[] r = ps.get(p);
        int primera = ps.size() == 1 ? SELECTOR + (ANCHO_SELECTOR - (r[1] - r[0])) / 2 : p == 0 ? SELECTOR : SELECTOR + 1;
        if (p > 0) out.add(new Hueco(SELECTOR, null, "menos"));
        for (int i = r[0]; i < r[1]; i++) out.add(new Hueco(primera + i - r[0], cats.get(i), "cat:" + cats.get(i).id()));
        if (p < ps.size() - 1) out.add(new Hueco(SELECTOR + ANCHO_SELECTOR - 1, null, "mas"));
        return out;
    }

    /**
     * El titulo de la ventana: "CALAMITY | Top · MobCoins" si cabe (Marco.ANCHO_TITULO) y se quiere;
     * si no, el del periodo.
     */
    static Marco.Titulo titulo(Categoria c, boolean semana, boolean porCategoria) {
        if (porCategoria && c != null) {
            Marco.Titulo t = new Marco.Titulo("Top · " + c.titulo());
            if (t.ancho() <= Marco.ANCHO_TITULO) return t;
        }
        return semana ? Marco.T_RANKINGS : Marco.T_RANKINGS_HISTORICO;
    }

    // ------------------------------------------------------------------ textos (sin Bukkit)

    /** El valor como se lee en el menu, con su unidad: "1.234 MC", "3 Parcas", "3 h 12 min". */
    static String valor(String clave, long v) {
        return switch (clave) {
            case "tasado-mc" -> Altar.miles(v) + " MC";
            case "expedicion-max-seg", "horas-activas" -> v <= 0 ? "0 min" : Tops.texto(clave, v);
            case "tasado-esencias", Tops.ESENCIAS -> Marco.esencias(v);
            case "cazas-validas" -> Altar.miles(v) + (v == 1 ? " Eco" : " Ecos");
            case "parcas" -> Altar.miles(v) + (v == 1 ? " Parca" : " Parcas");
            case "reliquias" -> Altar.miles(v) + (v == 1 ? " Reliquia" : " Reliquias");
            case "extracciones" -> Altar.miles(v) + (v == 1 ? " salida" : " salidas");
            case "minijefes" -> Altar.miles(v) + (v == 1 ? " minijefe" : " minijefes");
            case "contratos" -> Altar.miles(v) + (v == 1 ? " contrato" : " contratos");
            case CLAVE_CLANES -> Altar.miles(v) + (v == 1 ? " punto" : " puntos");
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

    /** Las partes de un premio: "30 Esencias", "2 Llaves del Caos", "[ÁNIMA] 7 días". */
    static List<String> partes(Rankings.Premio pr) {
        List<String> partes = new ArrayList<>();
        if (pr.esencias() > 0) partes.add(pr.esencias() + (pr.esencias() == 1 ? " Esencia" : " Esencias"));
        if (pr.llaves() > 0) partes.add(pr.llaves() + (pr.llaves() == 1 ? " Llave del Caos" : " Llaves del Caos"));
        if (!pr.comandos().isEmpty()) partes.add("[ÁNIMA] 7 días");
        for (String o : pr.objetos()) if (PuenteBovedas.esLlave(o)) partes.add("una " + PuenteBovedas.nombre(o, 1));
        return partes;
    }

    /** "30 Esencias, 2 Llaves del Caos y [ÁNIMA] 7 días": lo mismo que dice el aviso del cierre. */
    static String premio(Rankings.Premio pr) {
        List<String> partes = partes(pr);
        return partes.isEmpty() ? "el puesto" : lista(partes);
    }

    /** El premio en lineas de ancho letras, cortado entre partes: "30 Esencias, 2 Llaves del Caos" / "y [ÁNIMA] 7 días". */
    static List<String> lineasPremio(Rankings.Premio pr, int ancho) {
        List<String> partes = partes(pr);
        if (partes.isEmpty()) return List.of("el puesto");
        List<String> out = new ArrayList<>();
        StringBuilder linea = new StringBuilder(partes.get(0));
        for (int i = 1; i < partes.size(); i++) {
            boolean ultima = i == partes.size() - 1;
            String sep = ultima ? " y " : ", ";
            if (linea.length() + sep.length() + partes.get(i).length() <= ancho) {
                linea.append(sep).append(partes.get(i));
            } else {
                out.add(linea + (ultima ? "" : ","));
                linea = new StringBuilder(ultima ? "y " + partes.get(i) : partes.get(i));
            }
        }
        out.add(linea.toString());
        return out;
    }

    /** "Extraído, Cazador y Segador". */
    static String lista(List<String> cosas) {
        if (cosas.isEmpty()) return "";
        if (cosas.size() == 1) return cosas.get(0);
        return String.join(", ", cosas.subList(0, cosas.size() - 1)) + " y " + cosas.get(cosas.size() - 1);
    }

    /**
     * Un texto partido en lineas de ancho letras como mucho, por palabras y con las lineas
     * parecidas: "Parcas que has" / "ayudado a derrotar." y no una palabra suelta en la segunda.
     */
    static List<String> partir(String texto, int ancho) {
        List<String> out = cortar(texto, ancho);
        for (int a = 1; a < ancho && out.size() > 1; a++) {
            List<String> parejas = cortar(texto, a);
            if (parejas.size() == out.size()) return parejas;
        }
        return out;
    }

    /** Lineas de ancho letras como mucho, cortando por palabras (una palabra mas larga va sola). */
    private static List<String> cortar(String texto, int ancho) {
        List<String> out = new ArrayList<>();
        if (texto == null || texto.isBlank()) return out;
        StringBuilder linea = new StringBuilder();
        for (String palabra : texto.trim().split("\\s+")) {
            if (linea.length() > 0 && linea.length() + 1 + palabra.length() > ancho) {
                out.add(linea.toString());
                linea.setLength(0);
            }
            if (linea.length() > 0) linea.append(' ');
            linea.append(palabra);
        }
        if (linea.length() > 0) out.add(linea.toString());
        return out;
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

    /** Lo que lleva el del puesto n de la clasificacion (null si no hay nadie). */
    static Long valorDelPuesto(Tops.Clasificacion cl, int n) {
        if (n < 1) return null;
        if (n <= cl.top().size()) return cl.top().get(n - 1).valor();
        for (Map.Entry<UUID, Integer> e : cl.puestos().entrySet()) {
            if (e.getValue() == n) return cl.valores().get(e.getKey());
        }
        return null;
    }

    // ------------------------------------------------------------------ el plano (sin Bukkit)

    /** Que va en cada casilla y que hace cada clic, con los datos ya reunidos. */
    static Plano plano(Datos d) {
        Map<Integer, Pieza> piezas = new LinkedHashMap<>();
        Map<Integer, String> acciones = new HashMap<>();
        Categoria c = d.actual();
        boolean premio = c != null && d.semana() && d.conPremio().contains(c.clave());
        String periodo = d.semana() ? "Esta semana" : "Total";

        piezas.put(INFO, info(d, premio));

        if (c != null) {
            List<Rankings.Fila> top = d.cl().top();
            for (int i = 0; i < PIRAMIDE.length; i++) {
                piezas.put(PIRAMIDE[i], i < top.size() ? delTop(d, top.get(i), i + 1, premio, periodo)
                        : libre(d, i + 1, premio));
            }
        }

        // 1.7.5: Dosa retiro la fila 4 entera (Esta semana/Historico, tu cabeza, Cerrar y Tablero);
        // el menu se cierra con Escape. 1.12: vuelve el Tablero, solo, en el centro, si esta en marcha.
        if (d.tablero()) {
            piezas.put(TABLERO, Pieza.objeto(Material.ITEM_FRAME, Component.text("Tablero", Paleta.DETALLE), List.of(
                    Marco.texto("Los Ecos con botín y las Parcas"), Marco.texto("que hay ahora en Calamity."),
                    Component.empty(), Marco.accion("Clic para abrirlo"))));
            acciones.put(TABLERO, "tablero");
        }

        List<int[]> ps = paginas(d.visibles().size());
        int pagina = Math.max(0, Math.min(d.pagina(), ps.size() - 1));
        for (Hueco h : selector(d.visibles(), pagina)) {
            acciones.put(h.casilla(), h.accion());
            if (h.categoria() != null) {
                piezas.put(h.casilla(), enSelector(d, h.categoria()));
            } else if (h.accion().equals("mas")) {
                int[] sig = ps.get(pagina + 1);
                List<String> nombres = new ArrayList<>();
                for (Categoria x : d.visibles().subList(sig[0], sig[1])) nombres.add(x.nombre());
                List<Component> lore = new ArrayList<>();
                for (String l : partir(lista(nombres) + ".", ANCHO_LORE)) lore.add(Marco.tenue(l));
                lore.add(Component.empty());
                lore.add(Marco.accion("Clic para verlas"));
                piezas.put(h.casilla(), Pieza.textura(d.mas(), Material.PAPER, Component.text("Más →", Paleta.DETALLE),
                        lore, false));
            } else {
                piezas.put(h.casilla(), Pieza.textura(d.anterior(), Material.PAPER,
                        Component.text("← Anteriores", Paleta.DETALLE), List.of(
                                Marco.tenue("Estás en la página " + (pagina + 1) + " de " + ps.size() + "."),
                                Component.empty(), Marco.accion("Clic para volver")), false));
            }
        }
        return new Plano(titulo(c, d.semana(), d.tituloPorCategoria()), piezas, acciones);
    }

    /** El libro de arriba: que mide, el premio del lunes, el reinicio y las reglas para cobrar. */
    private static Pieza info(Datos d, boolean premio) {
        Categoria c = d.actual();
        if (c == null) {
            return Pieza.objeto(Material.KNOWLEDGE_BOOK, Component.text("Ranking", Paleta.MARCA),
                    List.of(Marco.tenue("No hay categorías que enseñar."), Marco.tenue("Revisa ranking.menu.categorias.")));
        }
        List<Component> lore = new ArrayList<>();
        for (String l : partir(c.mide(), ANCHO_LORE)) lore.add(Marco.texto(l));
        if (!lore.isEmpty()) lore.add(Component.empty());
        if (!d.semana()) {
            lore.add(Marco.texto("El histórico no se reinicia"));
            lore.add(Marco.texto("y no da premios."));
            lore.add(Marco.tenue("Los premios son los del"));
            lore.add(Marco.tenue("ranking semanal."));
        } else if (CLAVE_CLANES.equals(c.clave())) {
            lore.add(Marco.texto("Premio del lunes:"));
            lore.add(Component.text("1.º  ", colorPuesto(1)).append(Marco.texto("Trofeo de Temporada")));
            lore.add(Marco.tenue("      para el líder del clan"));
            lore.add(Component.empty());
            lore.add(Marco.dato("Se reinicia", "el lunes a las 00:00"));
            lore.add(Marco.dato("Quedan", d.quedan()));
            lore.add(Component.empty());
            lore.add(Marco.tenue("Cada miembro suma como mucho"));
            lore.add(Marco.tenue("unos puntos al día."));
        } else {
            if (premio) {
                lore.add(Marco.texto("Premios del lunes:"));
                List<Rankings.Premio> ps = d.premios();
                for (int i = 0; i < ps.size(); i++) {
                    List<String> ls = lineasPremio(ps.get(i), ANCHO_LORE);
                    for (int j = 0; j < ls.size(); j++) {
                        lore.add(j == 0 ? Component.text((i + 1) + ".º  ", colorPuesto(i + 1)).append(Marco.texto(ls.get(j)))
                                : Marco.texto("      " + ls.get(j)));
                    }
                }
            } else {
                lore.add(Marco.tenue("Esta categoría no da premio."));
            }
            lore.add(Component.empty());
            lore.add(Marco.dato("Se reinicia", "el lunes a las 00:00"));
            lore.add(Marco.dato("Quedan", d.quedan()));
            if (premio) {
                Reglas r = d.reglas();
                lore.add(Component.empty());
                lore.add(Marco.texto("Para cobrar:"));
                String salidas = r.salidas() + (r.salidas() == 1 ? " salida" : " salidas") + " con vida";
                lore.add(r.llevas() >= r.salidas()
                        ? Marco.tiene(salidas).append(Component.text("  (llevas " + r.llevas() + ")", Paleta.TENUE))
                        : Marco.falta(salidas, "llevas " + r.llevas()));
                lore.add(Marco.tenue("Como mucho cobras en " + r.maximoTablas()
                        + (r.maximoTablas() == 1 ? " ranking." : " rankings.")));
                lore.add(Marco.tenue("Con menos de " + r.elegibles() + " jugadores,"));
                lore.add(Marco.tenue("solo cobra el 1.º."));
            }
        }
        Component nombre = Component.text(c.nombre(), Paleta.MARCA)
                .append(Component.text(d.semana() ? " · esta semana" : " · histórico", Paleta.TENUE));
        return Pieza.objeto(Material.KNOWLEDGE_BOOK, nombre, lore);
    }

    /** "Premio del 2.º el lunes:" y el premio, si la categoria paga y ese puesto cobra. */
    private static List<Component> premioDelPuesto(Datos d, int puesto, boolean premio) {
        if (d.actual() != null && CLAVE_CLANES.equals(d.actual().clave())) {
            if (!d.semana() || puesto != 1) return List.of();
            return List.of(Component.empty(), Marco.tenue("Premio del 1.º el lunes:"),
                    Component.text("Trofeo de Temporada", Paleta.CIFRA));
        }
        if (!premio || puesto > d.premios().size()) return List.of();
        List<Component> out = new ArrayList<>();
        out.add(Component.empty());
        out.add(Marco.tenue("Premio del " + puesto + ".º el lunes:"));
        for (String l : lineasPremio(d.premios().get(puesto - 1), ANCHO_LORE)) out.add(Component.text(l, Paleta.CIFRA));
        return out;
    }

    /** Un puesto del top con su jugador: "1.º Nombre" y lo que lleva. */
    private static Pieza delTop(Datos d, Rankings.Fila f, int puesto, boolean premio, String periodo) {
        boolean soyYo = f.jugador().equals(d.yo());
        Component nombre = Component.text(puesto + ".º ", colorPuesto(puesto))
                .append(Component.text(f.nombre(), soyYo ? Paleta.BIEN : Paleta.TEXTO));
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.dato(periodo, valor(d.actual().clave(), f.valor())));
        lore.addAll(premioDelPuesto(d, puesto, premio));
        if (soyYo) {
            lore.add(Component.empty());
            lore.add(Component.text(CLAVE_CLANES.equals(d.actual().clave()) ? "Es tu clan." : "Eres tú.", Paleta.BIEN));
        }
        return Pieza.jugador(f.jugador(), nombre, lore);
    }

    /** Un puesto del top que aun no tiene a nadie. */
    private static Pieza libre(Datos d, int puesto, boolean premio) {
        Component nombre = Component.text(puesto + ".º ", colorPuesto(puesto)).append(Component.text("Puesto libre", Paleta.TENUE));
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.tenue("Nadie ha llegado aquí todavía."));
        lore.addAll(premioDelPuesto(d, puesto, premio));
        return Pieza.textura(d.libre(), Material.GRAY_STAINED_GLASS_PANE, nombre, lore, false);
    }

    /** Tu cabeza: tu puesto, lo que llevas y lo que te falta para subir uno. */
    private static Pieza tu(Datos d, String periodo) {
        Tops.Clasificacion cl = d.cl();
        String clave = d.actual().clave();
        Integer puesto = cl.puestos().get(d.yo());
        long mio = cl.valores().getOrDefault(d.yo(), 0L);
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.dato(periodo, valor(clave, mio)));
        Component nombre;
        if (puesto == null) {
            nombre = Component.text("Aún no tienes puesto", Paleta.TEXTO);
        } else {
            nombre = Component.text("Tu puesto: ", Paleta.TEXTO)
                    .append(Component.text(puesto + ".º", puesto <= 3 ? colorPuesto(puesto) : Paleta.BIEN));
            Long delante = valorDelPuesto(cl, puesto - 1);
            if (puesto == 1) {
                lore.add(Component.text("Vas el primero.", Paleta.BIEN));
            } else if (delante != null) {
                long falta = Math.max(1, delante - mio + 1);
                lore.add(Marco.tenue("Para pasar al " + (puesto - 1) + ".º te " + (falta == 1 ? "falta " : "faltan ")
                        + valor(clave, falta) + "."));
            }
        }
        return Pieza.jugador(d.yo(), nombre, lore);
    }

    /** Una cabeza del selector: el nombre, que mide, si da premio y si es la que miras. */
    private static Pieza enSelector(Datos d, Categoria c) {
        boolean vista = d.actual() != null && c.id().equals(d.actual().id());
        boolean daPremio = d.semana() && (d.conPremio().contains(c.clave()) || CLAVE_CLANES.equals(c.clave()));
        List<Component> lore = new ArrayList<>();
        for (String l : partir(c.mide(), ANCHO_LORE)) lore.add(Marco.tenue(l));
        lore.add(daPremio ? Component.text("Da premio el lunes.", Paleta.CIFRA) : Marco.tenue("No da premio."));
        lore.add(Component.empty());
        lore.add(vista ? Component.text("▶ Estás viendo esta", Paleta.MARCA) : Marco.accion("Clic para ver"));
        return Pieza.textura(c.textura(), c.material(), Component.text(c.nombre(), vista ? Paleta.MARCA : Paleta.DETALLE),
                lore, vista);
    }

    // ------------------------------------------------------------------ config

    /**
     * ranking.menu del servidor; si no la tiene, la del jar. Cronista.seccion no la crea vacia:
     * getConfigurationSection la crearia en memoria y un saveConfig la dejaria escrita, vacia.
     */
    private ConfigurationSection menu() {
        ConfigurationSection s = Cronista.seccion(hc.cfg(), RUTA);
        if (s != null) return s;
        ConfigurationSection f = Cronista.seccion(Cronista.fabrica(hc.plugin()), "hardcore." + RUTA);
        return f != null ? f : new YamlConfiguration();
    }

    /** Las categorias del servidor; si alli no hay ninguna valida, las del jar. */
    private List<Categoria> categorias() {
        List<Categoria> l = new ArrayList<>(leer(Cronista.seccion(menu(), "categorias")));
        if (l.isEmpty()) l.addAll(leer(Cronista.seccion(Cronista.fabrica(hc.plugin()), "hardcore." + RUTA + ".categorias")));
        // 1.11: los clanes, al final, si el ranking de clanes esta en marcha y la lista no los trae ya.
        ClanesCalamity cl = hc.clanes();
        boolean ya = false;
        for (Categoria c : l) ya |= CLAVE_CLANES.equals(c.clave());
        if (!ya && cl != null && cl.activo() && hc.cfg().getBoolean("clanes.menu", true)) l.add(CLANES);
        return l;
    }

    /**
     * 1.11 · La clasificacion de los clanes como la de los jugadores: cada clan con la cabeza del miembro
     * que mas puntos le dio esta semana y su tag entre corchetes. Y quien es "yo": la cara de tu clan.
     */
    private Object[] clasificacionClanes(Player p) {
        ClanesCalamity cl = hc.clanes();
        List<ClanesCalamity.Fila> filas = cl == null ? List.of() : cl.top();
        List<Rankings.Fila> top = new ArrayList<>();
        Map<UUID, Integer> puestos = new LinkedHashMap<>();
        Map<UUID, Long> valores = new LinkedHashMap<>();
        String mio = cl == null ? null : cl.claveDe(p.getUniqueId());
        UUID yo = p.getUniqueId();
        for (int i = 0; i < filas.size(); i++) {
            ClanesCalamity.Fila f = filas.get(i);
            UUID cara = f.cara() != null ? f.cara()
                    : UUID.nameUUIDFromBytes(("clan:" + f.clave()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (i < TOP) top.add(new Rankings.Fila(cara, "[" + f.tag() + "]", f.puntos()));
            puestos.put(cara, i + 1);
            valores.put(cara, f.puntos());
            if (f.clave().equals(mio)) yo = cara;
        }
        return new Object[]{new Tops.Clasificacion(top, puestos, valores), yo};
    }

    /** Las estadisticas de las tablas que pagan el lunes (ranking.tablas). */
    private static Set<String> conPremio(Rankings r) {
        Set<String> out = new HashSet<>();
        for (String id : r.tablas()) {
            Rankings.Tabla t = Rankings.tabla(id);
            if (t != null) out.add(t.estadistica());
        }
        return out;
    }

    // ------------------------------------------------------------------ abrir y pintar

    void abrir(Player p) {
        if (mostrar(p, null, true, null, null)) Marco.sonar(p, "item.book.page_turn", 0.8f, 1.1f);
    }

    /**
     * Abre el menu o repinta el que tiene abierto (vista) con ese periodo, categoria (null: la
     * ultima que miro o la primera con premio) y pagina del selector (null: la de la categoria). Si
     * el titulo no cambia, repinta la misma ventana; si cambia, abre otra encima. False si no abrio.
     */
    private boolean mostrar(Player p, Vista vista, boolean semana, String categoria, Integer pagina) {
        Rankings r = hc.rankings();
        if (r == null || !r.activo()) {
            p.sendMessage(ComandoCalamity.mensaje("Los rankings no están abiertos ahora mismo."));
            if (vista != null) p.closeInventory();
            return false;
        }
        List<Categoria> visibles = visibles(categorias(), semana);
        Set<String> pagan = conPremio(r);
        Categoria actual = inicial(visibles, pagan, categoria != null ? categoria : ultima.get(p.getUniqueId()));
        int total = paginas(visibles.size()).size();
        int pag = pagina != null ? Math.max(0, Math.min(pagina, total - 1))
                : paginaDe(visibles.size(), actual == null ? 0 : visibles.indexOf(actual));
        if (actual != null) ultima.put(p.getUniqueId(), actual.id());

        Plano pl = plano(datos(p, r, semana, visibles, actual, pag, pagan));
        if (vista != null && pl.titulo().equals(vista.titulo) && p.getOpenInventory().getTopInventory() == vista.inventario) {
            vista.semana = semana;
            vista.categoria = actual == null ? null : actual.id();
            vista.pagina = pag;
            poner(vista, pl);
            return true;
        }
        Vista v = new Vista();
        v.semana = semana;
        v.categoria = actual == null ? null : actual.id();
        v.pagina = pag;
        v.titulo = pl.titulo();
        v.inventario = hc.plugin().getServer().createInventory(v, TAMANO, pl.titulo().componente());
        poner(v, pl);
        p.openInventory(v.inventario);
        return true;
    }

    /** Reune lo que pide el plano: la clasificacion, los premios, las reglas y tus salidas. */
    private Datos datos(Player p, Rankings r, boolean semana, List<Categoria> visibles, Categoria actual, int pagina,
                        Set<String> pagan) {
        ConfigurationSection c = hc.cfg();
        ConfigurationSection m = menu();
        Tops.Clasificacion cl = actual == null ? Tops.Clasificacion.VACIA : r.clasificacion(actual.clave(), semana);
        UUID yo = p.getUniqueId();
        if (actual != null && CLAVE_CLANES.equals(actual.clave())) {
            Object[] cc = clasificacionClanes(p);
            cl = (Tops.Clasificacion) cc[0];
            yo = (UUID) cc[1];
        }
        Estadisticas st = hc.estadisticas();
        Reglas reglas = new Reglas(c.getInt("ranking.minimo-extracciones", 3), Math.max(1, c.getInt("ranking.maximo-tablas", 2)),
                c.getInt("ranking.minimo-elegibles", 8), st == null ? 0 : st.semana(p.getUniqueId(), "extracciones"));
        Calendario cal = hc.calendario();
        ZoneId zona = cal != null ? cal.zona() : ZoneId.systemDefault();
        Tablero tab = hc.tablero();
        boolean tablero = tab != null && hc.valor("tablero", tab::activo, false);
        return new Datos(semana, visibles, actual, pagina, cl, yo, pagan, r.premios(), reglas,
                hastaCierre(ZonedDateTime.now(zona)), tablero, m.getBoolean("titulo-por-categoria", true),
                cabeza(m, "mas", MAS), cabeza(m, "anterior", ANTERIOR), cabeza(m, "libre", LIBRE));
    }

    private static String cabeza(ConfigurationSection menu, String cual, String def) {
        String s = menu.getString("cabezas." + cual);
        return s == null || s.isBlank() ? def : s;
    }

    /** Los objetos del plano en la ventana, y lo vacio de cristal negro. */
    private void poner(Vista v, Plano pl) {
        Inventory inv = v.inventario;
        inv.clear();
        v.acciones.clear();
        v.acciones.putAll(pl.acciones());
        v.jugadores.clear();
        for (Map.Entry<Integer, Pieza> e : pl.piezas().entrySet()) {
            inv.setItem(e.getKey(), objeto(e.getValue()));
            if (e.getValue().tipo() == Tipo.JUGADOR && e.getValue().jugador() != null) v.jugadores.put(e.getKey(), e.getValue());
        }
        Marco.rellenar(inv);
    }

    private ItemStack objeto(Pieza pz) {
        ItemStack base = switch (pz.tipo()) {
            case OBJETO -> new ItemStack(pz.material());
            case TEXTURA -> cabezas.conTextura(pz.textura(), pz.material());
            case JUGADOR -> cabezas.jugador(pz.jugador());
        };
        return Marco.icono(base, pz.nombre(), pz.lore(), pz.brillo());
    }

    /** Ha llegado el perfil de u (hilo principal): sus cabezas en los menus abiertos, con skin. */
    private void alLlegarPerfil(UUID u) {
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) {
            if (!(p.getOpenInventory().getTopInventory().getHolder() instanceof Vista v)) continue;
            for (Map.Entry<Integer, Pieza> e : v.jugadores.entrySet()) {
                if (u.equals(e.getValue().jugador())) v.inventario.setItem(e.getKey(), objeto(e.getValue()));
            }
        }
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
        // Cerrar o abrir otra ventana dentro del evento deja objetos fantasma en el cursor: un tick despues.
        tarea(() -> {
            if (p.getOpenInventory().getTopInventory().getHolder() != v) return;
            clic(p, v, accion);
        });
    }

    private void clic(Player p, Vista v, String accion) {
        switch (accion) {
            case "cerrar" -> p.closeInventory();
            case "tablero" -> {
                Tablero tab = hc.tablero();
                if (tab != null) tab.abrir(p);
                Marco.sonidoPestana(p);
            }
            // Cambiar de periodo: la misma categoria si sale en el otro (si no, la primera con premio).
            case "semana", "historico" -> {
                if (mostrar(p, v, accion.equals("semana"), v.categoria, null)) Marco.sonidoPestana(p);
            }
            case "mas", "menos" -> {
                if (mostrar(p, v, v.semana, v.categoria, v.pagina + (accion.equals("mas") ? 1 : -1))) suave(p);
            }
            default -> {
                if (!accion.startsWith("cat:")) return;
                String id = accion.substring(4);
                if (id.equals(v.categoria)) return;
                if (mostrar(p, v, v.semana, id, null)) suave(p);
            }
        }
    }

    /** El sonido de cambiar de categoria o de pagina: una pagina que pasa, flojo. */
    private static void suave(Player p) {
        Marco.sonar(p, "item.book.page_turn", 0.45f, 1.35f);
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

    /** ranking.menu del config.yml del jar, leido del classpath: el autotest no tiene plugin. */
    static ConfigurationSection deSerie() {
        try (InputStream in = MenuCazador.class.getClassLoader().getResourceAsStream("config.yml")) {
            if (in == null) return null;
            YamlConfiguration y = new YamlConfiguration();
            y.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return Cronista.seccion(y, "hardcore." + RUTA);
        } catch (IOException | InvalidConfigurationException e) {
            return null;
        }
    }

    static void autotest(Autotest.Hoja h) {
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        Function<Component, String> txt = plain::serialize;

        // ---- Las casillas: la piramide donde dice el diseno y nada repetido.
        h.igual("ranking: la piramide en 13, 21, 23 y 28-34", List.of(13, 21, 23, 28, 29, 30, 31, 32, 33, 34),
                java.util.Arrays.stream(PIRAMIDE).boxed().toList());
        List<Integer> fijas = new ArrayList<>(List.of(INFO, TABLERO));
        for (int s : PIRAMIDE) fijas.add(s);
        for (int s = SELECTOR; s < SELECTOR + ANCHO_SELECTOR; s++) fijas.add(s);
        h.igual("ranking: ninguna casilla usada dos veces", fijas.size(), new HashSet<>(fijas).size());
        h.ok("ranking: todo dentro de la ventana de 54", fijas.stream().allMatch(s -> s >= 0 && s < TAMANO));
        h.ok("ranking: el libro arriba en el centro y el selector en la ultima fila",
                INFO == 4 && SELECTOR / 9 == TAMANO / 9 - 1);
        h.igual("ranking: el Tablero en la fila 4, en el centro", 40, TABLERO);

        // ---- Las categorias de serie (el config.yml del jar).
        ConfigurationSection serie = deSerie();
        h.ok("ranking: el config.yml del jar trae hardcore.ranking.menu", serie != null);
        List<Categoria> todas = leer(serie == null ? null : Cronista.seccion(serie, "categorias"));
        List<String> ids = new ArrayList<>();
        for (Categoria c : todas) ids.add(c.id());
        h.igual("ranking: las categorias de serie, en su orden", List.of("mobcoins", "ecos", "parcas", "expedicion",
                "esencias", "reliquias", "salidas", "minijefes", "contratos", "horas", "saldo"), ids);
        List<Categoria> semana = visibles(todas, true), historico = visibles(todas, false);
        h.igual("ranking: 9 categorias en la semana", 9, semana.size());
        h.igual("ranking: 11 en el historico", 11, historico.size());
        List<String> tablas = new ArrayList<>(), primeras = new ArrayList<>();
        for (Rankings.Tabla t : Rankings.TABLAS) tablas.add(t.estadistica());
        for (int i = 0; i < Math.min(4, semana.size()); i++) primeras.add(semana.get(i).clave());
        h.igual("ranking: las 4 con premio primero, en el orden de las tablas", tablas, primeras);
        h.ok("ranking: horas y saldo solo en el historico", todas.stream()
                .filter(c -> c.id().equals("horas") || c.id().equals("saldo")).allMatch(c -> !c.semana() && c.historico()));
        boolean claves = true;
        for (Categoria c : semana) claves &= Estadisticas.CLAVES.contains(c.clave());
        h.ok("ranking: las claves de la semana son de Estadisticas", claves);
        Set<String> hashes = new HashSet<>();
        boolean texturas = true, completas = true;
        for (Categoria c : todas) {
            String hs = Cabezas.hash(c.textura());
            texturas &= hs != null;
            if (hs != null) hashes.add(hs);
            completas &= !c.nombre().isBlank() && !c.mide().isBlank()
                    && Material.matchMaterial(serie.getString("categorias." + c.id() + ".material", "")) != null;
        }
        h.ok("ranking: todas las texturas de serie tienen formato valido", texturas);
        h.igual("ranking: una textura distinta por categoria", todas.size(), hashes.size());
        h.ok("ranking: cada categoria con nombre, que mide y material de respaldo", completas);
        boolean flechas = serie != null;
        for (String k : List.of("mas", "anterior", "libre")) flechas &= serie != null && Cabezas.hash(serie.getString("cabezas." + k)) != null;
        h.ok("ranking: las cabezas de Mas, Anteriores y Puesto libre con formato valido", flechas);
        h.igual("ranking: las de la config son las de serie del codigo", List.of(MAS, ANTERIOR, LIBRE),
                serie == null ? null : List.of(serie.getString("cabezas.mas", ""), serie.getString("cabezas.anterior", ""),
                        serie.getString("cabezas.libre", "")));
        boolean caben = true;
        for (Categoria c : todas) {
            Marco.Titulo t = titulo(c, c.semana(), true);
            caben &= t.texto().equals("CALAMITY | Top · " + c.titulo()) && t.ancho() <= Marco.ANCHO_TITULO;
        }
        h.ok("ranking: el titulo de cada categoria de serie cabe (Top · nombre)", caben);
        h.ok("ranking: 'Ranking · MobCoins' no cabe (por eso Top)",
                new Marco.Titulo("Ranking · MobCoins").ancho() > Marco.ANCHO_TITULO);
        Categoria larga = new Categoria("x", "parcas", "Parcas derrotadas esta semana", "Parcas derrotadas esta semana", "",
                true, true, "", Material.PAPER);
        h.igual("ranking: un titulo que no cabe deja el del periodo", Marco.T_RANKINGS, titulo(larga, true, true));
        h.igual("ranking: sin titulo por categoria, el del periodo", Marco.T_RANKINGS_HISTORICO, titulo(todas.get(0), false, false));

        // ---- Leer categorias a mano: sin clave no sale, vistas, solo total y material malo.
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(String.join("\n",
                    "a: {clave: parcas, nombre: A, vistas: [historico], textura: nada, material: NO_EXISTE}",
                    "b: {nombre: B}",
                    "c: {clave: horas-activas, vistas: [semana]}",
                    "d: {clave: Tasado-MC, vistas: semana}",
                    "e: {clave: esencias}"));
        } catch (InvalidConfigurationException ex) {
            h.ok("yaml de prueba: " + ex.getMessage(), false);
        }
        List<Categoria> aMano = leer(y);
        List<String> idsAMano = new ArrayList<>();
        for (Categoria c : aMano) idsAMano.add(c.id() + ":" + c.semana() + "/" + c.historico());
        h.igual("ranking: sin clave o sin vistas no sale; el saldo solo en el historico",
                List.of("a:false/true", "d:true/false", "e:false/true"), idsAMano);
        h.igual("ranking: material que no existe, papel", Material.PAPER, aMano.get(0).material());
        h.igual("ranking: la clave en minusculas", "tasado-mc", aMano.get(1).clave());
        h.igual("ranking: sin nombre, el id", "e", aMano.get(2).nombre());

        // ---- El selector: una cabeza por categoria y "Mas ->" con mas de 9.
        List<Hueco> nueve = selector(semana, 0);
        List<Integer> casNueve = new ArrayList<>();
        for (Hueco x : nueve) casNueve.add(x.casilla());
        h.igual("selector: 9 categorias ocupan 45-53", List.of(45, 46, 47, 48, 49, 50, 51, 52, 53), casNueve);
        h.ok("selector: con 9 no hay flechas", nueve.stream().allMatch(x -> x.categoria() != null));
        List<Hueco> once0 = selector(historico, 0), once1 = selector(historico, 1);
        h.igual("selector: con 11, 2 paginas", 2, paginas(11).size());
        h.ok("selector: con 11, la ultima casilla es Mas", once0.stream().anyMatch(x -> x.casilla() == 53 && "mas".equals(x.accion())));
        h.igual("selector: con 11, 8 categorias en la primera", 8, (int) once0.stream().filter(x -> x.categoria() != null).count());
        h.ok("selector: la segunda empieza con Anteriores", once1.get(0).casilla() == 45 && "menos".equals(once1.get(0).accion()));
        List<String> segunda = new ArrayList<>();
        for (Hueco x : once1) if (x.categoria() != null) segunda.add(x.casilla() + ":" + x.categoria().id());
        h.igual("selector: en la segunda, contratos, horas y saldo", List.of("46:contratos", "47:horas", "48:saldo"), segunda);
        boolean todasUnaVez = true;
        for (int n = 1; n <= 24; n++) {
            List<Categoria> cs = new ArrayList<>();
            for (int i = 0; i < n; i++) cs.add(new Categoria("c" + i, "parcas", "C" + i, "C" + i, "", true, true, "", Material.PAPER));
            List<String> vistas = new ArrayList<>();
            for (int p = 0; p < paginas(n).size(); p++) {
                Set<Integer> usadas = new HashSet<>();
                for (Hueco x : selector(cs, p)) {
                    todasUnaVez &= x.casilla() >= SELECTOR && x.casilla() < SELECTOR + ANCHO_SELECTOR && usadas.add(x.casilla());
                    if (x.categoria() != null) {
                        vistas.add(x.categoria().id());
                        todasUnaVez &= paginaDe(n, cs.indexOf(x.categoria())) == p;
                    }
                }
            }
            todasUnaVez &= vistas.size() == n && new HashSet<>(vistas).size() == n;
        }
        h.ok("selector: de 1 a 24 categorias, cada una una vez y en su pagina, sin pisarse", todasUnaVez);
        List<Integer> cinco = new ArrayList<>();
        for (Hueco x : selector(semana.subList(0, 5), 0)) cinco.add(x.casilla());
        h.igual("selector: con 5, seguidas y centradas", List.of(47, 48, 49, 50, 51), cinco);

        // ---- La categoria al abrir.
        Set<String> conPremio = new HashSet<>(tablas);
        h.igual("al abrir: la primera con premio", "mobcoins", idDe(inicial(semana, conPremio, null)));
        h.igual("al abrir: la ultima que miraste", "horas", idDe(inicial(historico, conPremio, "horas")));
        h.igual("al abrir: la ultima si no sale en esta vista, no", "mobcoins", idDe(inicial(semana, conPremio, "saldo")));
        h.igual("al abrir: sin categorias, ninguna", null, inicial(List.of(), conPremio, null));

        // ---- El plano: una categoria, luego otra (el cambio redibuja), y el historico.
        Map<UUID, Long> valores = new LinkedHashMap<>();
        for (int i = 1; i <= 12; i++) valores.put(Autotest.sintetico(100 + i), 1000L - i * 10);
        Function<UUID, String> nombres = u -> "J" + (u.getLeastSignificantBits() & 0xFFF);
        Tops.Clasificacion doce = Tops.clasificacion(valores, TOP, nombres);
        Tops.Clasificacion tres = Tops.clasificacion(Map.of(Autotest.sintetico(201), 5L, Autotest.sintetico(202), 3L,
                Autotest.sintetico(203), 1L), TOP, nombres);
        UUID yo = Autotest.sintetico(112);
        List<Rankings.Premio> premios = List.of(new Rankings.Premio(30, 2, List.of("lp user %jugador% ...")),
                new Rankings.Premio(20, 1, List.of()), new Rankings.Premio(10, 1, List.of()));
        Reglas reglas = new Reglas(3, 2, 8, 1);
        Categoria mc = semana.get(0), parcas = semana.get(2);
        Plano a = plano(new Datos(true, semana, mc, 0, doce, yo, conPremio, premios, reglas, "1 d 5 h", true, true, MAS, ANTERIOR, LIBRE));
        Plano b = plano(new Datos(true, semana, parcas, 0, tres, yo, conPremio, premios, reglas, "1 d 5 h", false, true, MAS, ANTERIOR, LIBRE));

        h.igual("plano: titulo con la categoria", "CALAMITY | Top · MobCoins", a.titulo().texto());
        h.igual("plano: al cambiar, el titulo tambien", "CALAMITY | Top · Parcas", b.titulo().texto());
        h.igual("plano: el libro dice la categoria", "MobCoins · esta semana", txt.apply(a.piezas().get(INFO).nombre()));
        h.igual("plano: y cambia con ella", "Parcas · esta semana", txt.apply(b.piezas().get(INFO).nombre()));
        List<String> libro = lineas(a.piezas().get(INFO), txt);
        h.ok("plano: el libro dice que mide, el premio, el reinicio y las reglas",
                libro.get(0).startsWith("MobCoins que te paga Oren") && libro.contains("Premios del lunes:")
                        && libro.contains("1.º  30 Esencias, 2 Llaves del Caos") && libro.contains("      y [ÁNIMA] 7 días")
                        && libro.contains("2.º  20 Esencias y 1 Llave del Caos") && libro.contains("Quedan: 1 d 5 h")
                        && libro.contains("✘ 3 salidas con vida  (llevas 1)") && libro.contains("Como mucho cobras en 2 rankings.")
                        && libro.contains("solo cobra el 1.º."));

        Pieza primero = a.piezas().get(PIRAMIDE[0]);
        h.igual("plano: el 1.o es la cabeza de su jugador", Autotest.sintetico(101), primero.jugador());
        h.igual("plano: y se llama '1.º Nombre'", "1.º " + nombres.apply(Autotest.sintetico(101)), txt.apply(primero.nombre()));
        List<String> lPrimero = lineas(primero, txt);
        h.igual("plano: su cifra con unidad y periodo", "Esta semana: 990 MC", lPrimero.get(0));
        h.ok("plano: y lo que gana ese puesto", lPrimero.contains("Premio del 1.º el lunes:")
                && lPrimero.contains("30 Esencias, 2 Llaves del Caos") && lPrimero.contains("y [ÁNIMA] 7 días"));
        List<String> lDecimo = lineas(a.piezas().get(PIRAMIDE[9]), txt);
        h.ok("plano: el 10.o sin premio", lDecimo.size() == 1 && lDecimo.get(0).equals("Esta semana: 900 MC"));
        h.ok("plano: los 10 puestos con jugador", java.util.Arrays.stream(PIRAMIDE).allMatch(s -> a.piezas().get(s).tipo() == Tipo.JUGADOR));
        Pieza cuarto = b.piezas().get(PIRAMIDE[3]);
        h.ok("plano: con 3 jugadores, del 4.o al 10.o 'Puesto libre'", java.util.Arrays.stream(PIRAMIDE).skip(3)
                .allMatch(s -> b.piezas().get(s).tipo() == Tipo.TEXTURA && txt.apply(b.piezas().get(s).nombre()).endsWith(" Puesto libre")));
        h.igual("plano: el puesto libre con la cabeza gris", LIBRE, cuarto.textura());
        Plano dos = plano(new Datos(true, semana, parcas, 0, Tops.clasificacion(Map.of(Autotest.sintetico(201), 5L,
                Autotest.sintetico(202), 3L), TOP, nombres), yo, conPremio, premios, reglas, "1 d 5 h", false, true, MAS, ANTERIOR, LIBRE));
        h.ok("plano: un puesto libre que paga lo dice", dos.piezas().get(PIRAMIDE[2]).tipo() == Tipo.TEXTURA
                && lineas(dos.piezas().get(PIRAMIDE[2]), txt).contains("Premio del 3.º el lunes:")
                && lineas(dos.piezas().get(PIRAMIDE[3]), txt).equals(List.of("Nadie ha llegado aquí todavía.")));

        h.igual("plano: el Tablero en la fila 4 con su accion", "tablero", a.acciones().get(TABLERO));
        h.igual("plano: el Tablero se llama asi", "Tablero", txt.apply(a.piezas().get(TABLERO).nombre()));
        h.igual("plano: el Tablero, clic para abrirlo", "▸ Clic para abrirlo",
                lineas(a.piezas().get(TABLERO), txt).get(lineas(a.piezas().get(TABLERO), txt).size() - 1));
        h.ok("plano: con el Tablero apagado no sale", b.piezas().get(TABLERO) == null && b.acciones().get(TABLERO) == null);
        boolean filaSola = true;
        for (int s = 36; s < 45; s++) if (s != TABLERO) filaSola &= a.piezas().get(s) == null && a.acciones().get(s) == null;
        h.ok("plano: en la fila 4 solo el Tablero", filaSola);

        List<Integer> brillanA = new ArrayList<>(), brillanB = new ArrayList<>();
        for (int s = SELECTOR; s < SELECTOR + ANCHO_SELECTOR; s++) {
            if (a.piezas().get(s).brillo()) brillanA.add(s);
            if (b.piezas().get(s).brillo()) brillanB.add(s);
        }
        h.igual("selector: brilla la categoria que miras", List.of(45), brillanA);
        h.igual("selector: al cambiar, brilla la nueva", List.of(47), brillanB);
        List<String> lMc = lineas(a.piezas().get(45), txt), lEco = lineas(a.piezas().get(46), txt);
        h.igual("selector: la que miras lo dice", "▶ Estás viendo esta", lMc.get(lMc.size() - 1));
        h.igual("selector: las demas, clic para ver", "▸ Clic para ver", lEco.get(lEco.size() - 1));
        h.ok("selector: dice si da premio", lMc.contains("Da premio el lunes.")
                && lineas(a.piezas().get(49), txt).contains("No da premio."));
        h.igual("selector: la cabeza con la textura de la config", mc.textura(), a.piezas().get(45).textura());
        h.igual("selector: su nombre corto", "Ecos cazados", txt.apply(a.piezas().get(46).nombre()));
        boolean acc = true;
        for (int i = 0; i < 9; i++) acc &= ("cat:" + semana.get(i).id()).equals(a.acciones().get(SELECTOR + i));
        h.ok("selector: cada cabeza cambia a su categoria", acc);

        Plano hist = plano(new Datos(false, historico, historico.get(0), 0, doce, yo, conPremio, premios, reglas, "1 d 5 h",
                true, true, MAS, ANTERIOR, LIBRE));
        h.igual("historico: Mas en la ultima casilla", "mas", hist.acciones().get(53));
        h.igual("historico: Mas lleva su cabeza", MAS, hist.piezas().get(53).textura());
        h.igual("historico: Mas dice cuales vienen", "Contratos, Horas y Saldo.", txt.apply(hist.piezas().get(53).lore().get(0)));
        h.ok("historico: sin premios en el top", lineas(hist.piezas().get(PIRAMIDE[0]), txt).equals(List.of("Total: 990 MC")));
        h.ok("historico: el libro dice que no da premios", lineas(hist.piezas().get(INFO), txt).contains("y no da premios."));
        h.ok("historico: ninguna cabeza del selector da premio", java.util.stream.IntStream.range(45, 53)
                .allMatch(s -> lineas(hist.piezas().get(s), txt).contains("No da premio.")));
        Plano hist2 = plano(new Datos(false, historico, historico.get(10), 1, doce, yo, conPremio, premios, reglas, "1 d 5 h",
                true, true, MAS, ANTERIOR, LIBRE));
        h.igual("historico: pagina 2 con Anteriores", "menos", hist2.acciones().get(45));
        h.igual("historico: y el saldo brillando en la 48", "Saldo", txt.apply(hist2.piezas().get(48).nombre()));
        h.ok("historico: el saldo brilla", hist2.piezas().get(48).brillo());
        h.igual("historico: el titulo del saldo", "CALAMITY | Top · Saldo", hist2.titulo().texto());
        Plano vacio = plano(new Datos(true, List.of(), null, 0, Tops.Clasificacion.VACIA, yo, conPremio, premios, reglas, "1 d",
                false, true, MAS, ANTERIOR, LIBRE));
        h.ok("plano: sin categorias no revienta y lo dice", txt.apply(vacio.piezas().get(INFO).nombre()).equals("Ranking")
                && !vacio.piezas().containsKey(PIRAMIDE[0]) && vacio.titulo().equals(Marco.T_RANKINGS));

        // ---- Las texturas: hash, URL o base64.
        String hs = "b856ccab6f72b6f37332840a2779bb42758bbb2c015f54ce53740cb0f1cb4ca1";
        h.igual("textura: el hash solo", hs, Cabezas.hash(hs));
        h.igual("textura: la URL", hs, Cabezas.hash("http://textures.minecraft.net/texture/" + hs));
        h.igual("textura: la URL con https y mayusculas", hs, Cabezas.hash("https://textures.minecraft.net/texture/" + hs.toUpperCase(Locale.ROOT)));
        h.igual("textura: el Value en base64", hs, Cabezas.hash(Cabezas.valor(hs)));
        h.igual("textura: base64 sin el relleno", hs, Cabezas.hash(Cabezas.valor(hs).replace("=", "")));
        h.igual("textura: un hash corto (Mojang quita los ceros)", MAS, Cabezas.hash(MAS));
        h.igual("textura: otra web no vale", null, Cabezas.hash("http://example.com/texture/" + hs + ".png"));
        h.igual("textura: texto cualquiera no vale", null, Cabezas.hash("una calavera roja"));
        h.igual("textura: vacia no vale", null, Cabezas.hash(""));
        h.igual("textura: null no vale", null, Cabezas.hash(null));
        h.ok("textura: un UUID fijo por textura", Cabezas.uuid(hs).equals(Cabezas.uuid(hs)) && !Cabezas.uuid(hs).equals(Cabezas.uuid(MAS)));

        // ---- Las cifras.
        h.igual("valor: MobCoins", "1.234 MC", valor("tasado-mc", 1234));
        h.igual("valor: tiempo", "3 h 12 min", valor("expedicion-max-seg", 3 * 3600 + 12 * 60));
        h.igual("valor: expedicion con minutos de una cifra", "1 h 05 min", valor("expedicion-max-seg", 3900));
        h.igual("valor: horas activas", "12 h 05 min", valor("horas-activas", 12 * 3600 + 5 * 60));
        h.igual("valor: sin tiempo, 0 min", "0 min", valor("expedicion-max-seg", 0));
        h.igual("valor: una Parca", "1 Parca", valor("parcas", 1));
        h.igual("valor: esencias", "1.500 Esencias", valor("tasado-esencias", 1500));
        h.igual("valor: saldo", "1 Esencia", valor(Tops.ESENCIAS, 1));
        h.igual("valor: salidas", "2 salidas", valor("extracciones", 2));
        h.igual("valor: una clave sin unidad, con miles", "12.345", valor("forjas", 12345));
        h.igual("lista de tablas", "Extraído, Cazador y Segador", lista(List.of("Extraído", "Cazador", "Segador")));
        h.igual("partir por palabras y parejo", List.of("MobCoins que te pagan", "al vender lo que sacas."),
                partir("MobCoins que te pagan al vender lo que sacas.", ANCHO_LORE));
        h.igual("partir: sin palabra suelta al final", List.of("Parcas que has", "ayudado a derrotar."),
                partir("Parcas que has ayudado a derrotar.", ANCHO_LORE));
        h.igual("partir: lo corto en una linea", List.of("No da premio."), partir("No da premio.", ANCHO_LORE));
        h.igual("partir: una palabra mas larga que la linea va sola", List.of("Supercalifragilistico", "y ya"),
                partir("Supercalifragilistico y ya", 10));

        h.igual("premio completo", "30 Esencias, 2 Llaves del Caos y [ÁNIMA] 7 días",
                premio(new Rankings.Premio(30, 2, List.of("lp user %jugador% ..."))));
        h.igual("premio de una llave", "10 Esencias y 1 Llave del Caos", premio(new Rankings.Premio(10, 1, List.of())));
        h.igual("premio vacio", "el puesto", premio(new Rankings.Premio(0, 0, List.of())));
        h.igual("premio en lineas", List.of("30 Esencias, 2 Llaves del Caos", "y [ÁNIMA] 7 días"),
                lineasPremio(new Rankings.Premio(30, 2, List.of("x")), ANCHO_LORE));

        // Lo que falta para el cierre (2026-09-26 es sabado).
        ZoneId madrid = ZoneId.of("Europe/Madrid");
        h.igual("cierre desde el sabado a las 19:00", "1 d 5 h", hastaCierre(ZonedDateTime.of(2026, 9, 26, 19, 0, 0, 0, madrid)));
        h.igual("cierre desde el domingo a las 23:30", "30 min", hastaCierre(ZonedDateTime.of(2026, 9, 27, 23, 30, 0, 0, madrid)));
        h.igual("cierre desde el domingo a las 18:48", "5 h 12 min", hastaCierre(ZonedDateTime.of(2026, 9, 27, 18, 48, 0, 0, madrid)));
        h.igual("el lunes a las 00:00 empieza otra semana", "7 d 0 h", hastaCierre(ZonedDateTime.of(2026, 9, 28, 0, 0, 0, 0, madrid)));
    }

    private static String idDe(Categoria c) {
        return c == null ? null : c.id();
    }

    private static List<String> lineas(Pieza p, Function<Component, String> txt) {
        List<String> out = new ArrayList<>();
        for (Component c : p.lore()) out.add(txt.apply(c));
        return out;
    }
}
