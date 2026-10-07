package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Los menus del Altar del Umbral (1.3.1): la portada, sus categorias, la Forja y la pantalla de
 * confirmar, y "Tu camino" y Grabar (que pintan Camino y Forja), todos atendidos aqui (un
 * listener, distingue por el holder).
 *
 * El Altar es solo tienda y trabaja como la de EDM (MenuTienda), porque la 1.3.0 mezclaba en
 * una pagina frascos, llaves, depositar, horas, contratos, rankings y encuestas, y Dosa no sabia
 * "de que va con tantas cosas":
 *  - la portada (45) tiene tres tarjetas grandes: Para la expedicion, Llaves y ofrendas y La
 *    Forja, cada una con dos lineas de lo que hay dentro, cuantos articulos y cuantos puedes
 *    comprar ya (brilla si alguno). Arriba tu saldo; abajo "¿Como funciona?", Cerrar en el
 *    centro y el enlace al Mercado de Oren, como la portada del Mercado (1.7.3). 1.12: con el
 *    Kit de Expedicion en marcha (kit.activo), una cuarta tarjeta al final que lo pide (no se
 *    compra: es el "calamity open <p> kit" de siempre, ahora con boton en Sael);
 *  - una categoria (54) son sus articulos centrados y con aire (Marco.rejilla), con las flechas
 *    de pagina en las esquinas de abajo y Volver al Altar en el centro;
 *  - la Forja (54, la que abre Vael y la tarjeta de la portada) pone cada grupo en su fila con
 *    una banda a los lados (el cristal negro del marco con el nombre del grupo al pasar por
 *    encima): Mejoras (con Grabar), El Manto y el Hacha, El Vigilante y, en una fila, el Eco, la
 *    Parca y Ambush (Vestigio, Guadana y Masamune: con una quinta fila no cabria en una hoja).
 * Lo que no es comprar salio del Altar: depositar va en el icono del saldo (y en el Tasador);
 * Tu camino y las horas activas, los contratos, en el Tasador; los rankings y el Tablero, en el
 * Cazador; la encuesta y la lista de deseos, en sus comandos. Los trueques depositar y camino
 * de altar.trueques siguen valiendo para el motor, pero aqui no salen (FUERA).
 *
 * Cada trueque es el objeto que das (el de MMOItems con su aspecto real) y debajo, corto: que
 * es, el coste linea a linea (✔ lo tienes, ✘ te falta y cuanto), el cupo que queda y el clic;
 * si no se puede, por que. Lo que se puede comprar ya brilla. En la Forja, y en lo que llegue a
 * altar.confirmar-desde Esencias (50; 0 = solo la Forja), el clic abre una pantalla con lo que
 * te llevas tal cual y lo que pagas, y un segundo boton para hacerlo: en Bedrock tocar un icono
 * para leerlo ya es un clic, y una pieza de 48 Esencias y 3.000 MobCoins no se compra sin querer.
 *
 * La regla del Altar (Marco.puedeAltar): fuera de Calamity o en su zona spawn. Se mira al abrir
 * (NPC, bloque, comando) y otra vez en cada clic, por si entretanto ha salido de la zona.
 *
 * Pensado para Bedrock (Geyser): solo clic izquierdo, sin arrastrar, un clic cada 500 ms (un
 * doble clic no compra dos veces). Cada clic que no puede pagar pasa igual por el Altar, que
 * dice por que en el chat y escribe su trueque-fallido.
 *
 * Abrir otro menu o cerrar dentro del evento de clic deja objetos fantasma en el cursor:
 * todo eso va un tick despues.
 */
final class MenuAltar implements Listener {

    /** Las paginas: umbral es la portada; expedicion y llaves, sus categorias; forja, la Forja. */
    static final String UMBRAL = Marco.UMBRAL, FORJA = Marco.FORJA, EXPEDICION = "expedicion", LLAVES = "llaves",
            GRABAR = "grabar", CAMINO = Marco.CAMINO, CONFIRMAR = "confirmar";
    private static final long ESPERA_MS = 500;
    /** Los iconos de MMOItems se crean una vez cada tanto, no en cada repintado. */
    private static final long ICONOS_MS = 5 * 60_000L;

    /**
     * La portada: 45 casillas, las tarjetas en la fila del medio y abajo la ayuda, Cerrar y el
     * Mercado (38, 40, 42: los mismos sitios que el Altar, Cerrar y la Forja en el Mercado).
     */
    static final int PORTADA = 45, FILA_TARJETAS = 18, AYUDA = 38, CERRAR = 40, IR_TASADOR = 42;
    /** Donde va "Nada por ahora" en una categoria vacia: el centro. */
    private static final int CENTRO = 22;

    /** Los servicios de altar.trueques que no son comprar: ya no salen en el Altar (los atiende el Tasador). */
    static final Set<String> FUERA = Set.of("depositar", "camino");

    /**
     * Lo que dice cada trueque de serie en su icono (el lore de la config, si lo hay, manda). Cada
     * frase dice lo que el objeto hace de verdad (1.7.3): nada de "Escalon 15" ni de frases que
     * suenan a un efecto que no existe.
     */
    private static final Map<String, List<String>> DESCRIPCION = Map.ofEntries(
            Map.entry("recargar", List.of("Rellena el Frasco de Calma", "que lleves encima.")),
            Map.entry("frasco", List.of("Trae 3 tragos y cada uno te", "devuelve 40 de cordura.")),
            Map.entry("cristal", List.of("Te saca de Calamity si te", "quedas quieto unos segundos.")),
            Map.entry("tintura", List.of("Cura 8 de vida y te da", "Resistencia durante 6 s.")),
            // 1.10: el cuerno que llama al minijefe (Reclamo).
            Map.entry("reclamo", List.of("Hazlo sonar en Calamity y vendrá", "el minijefe del bioma donde estés.")),
            // 1.12.2: el Barometro.
            Map.entry("barometro", List.of("Dice qué clima viene a tu bioma", "y cuánto falta. No se gasta.")),
            // 1.16.0: la Brujula de la Caida (los Faroles salen con sus minutos de la config, en descripcion()).
            Map.entry("brujula-caida", List.of("Su aguja apunta a la Bóveda Caída", "mientras siga cerrada. No se gasta.")),
            Map.entry("llave", List.of("Abre la Crate Caos del spawn.", "Cuenta para tu tope semanal", "de llaves.")),
            Map.entry("salvoconducto", List.of("Si mueres en Calamity, conservas", "una pieza de tu equipo.")),
            Map.entry("ofrenda", List.of("Suma una Ofrenda a tu nombre.", "No da ningún objeto.")),
            Map.entry("talisman", List.of("+3 de vida. En Calamity, la", "cordura baja un 20 % más despacio.")),
            Map.entry("gema", List.of("Lior la engarza en cualquier", "pieza de Calamity.")),
            Map.entry("grabado", List.of("Sube de nivel un encantamiento", "que ya esté al máximo.",
                    "Solo en equipo vanilla.")),
            Map.entry("ascua", List.of("Sube un nivel de mejora a una", "pieza del Manto, del Vigilante,", "del Eco o a la Guadaña.")),
            Map.entry("mascara-eco", List.of("Casco del Vestigio del Eco.", "Con el Filo del Eco activa", "el bono del set.")),
            Map.entry("filo-eco", List.of("Espada del Vestigio del Eco.", "Con la Máscara del Eco activa", "el bono del set.")),
            Map.entry("guadana", List.of("El arma de la Parca. Se forja", "con Fragmentos de Guadaña.")),
            // 1.8.0: las katanas de Ambush.
            Map.entry("masamune", List.of("La katana de Ambush. Se forja", "con Fragmentos de Masamune.")),
            Map.entry("crimson-masamune", List.of("La Masamune, templada otra vez.", "Al forjarla entregas tu Masamune.")));

    /**
     * Marca de nuestros inventarios. acciones: casilla -> que hace. foto: el objeto de la mano
     * (Grabar). hoja: la pagina de articulos. trueque y volver: en la pantalla de confirmar, que
     * se confirma y a que pagina se vuelve.
     */
    record Marca(String pagina, Map<Integer, String> acciones, ItemStack foto, int hoja, String trueque, String volver)
            implements InventoryHolder {

        Marca(String pagina, Map<Integer, String> acciones, ItemStack foto) {
            this(pagina, acciones, foto, 0, null, null);
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    /**
     * Una tarjeta de la portada: a que pagina lleva, su icono, su nombre, dos lineas de lo que hay
     * dentro y la seccion del titulo de su ventana (corta, para que quepa tras "CALAMITY | ").
     */
    record Categoria(String id, Material icono, String nombre, List<String> texto, String seccion) {
    }

    static final List<Categoria> CATEGORIAS = List.of(
            new Categoria(EXPEDICION, Material.LANTERN, "Para la expedición",
                    List.of("Frascos, Cristales, Tinturas,", "Reclamos, Faroles e instrumentos."), "Expedición"),
            new Categoria(LLAVES, Material.VAULT, "Llaves y ofrendas",
                    List.of("La Llave del Caos y la Ofrenda,", "a cambio de Esencias."), "Llaves"),
            new Categoria(FORJA, Material.ANVIL, "La Forja",
                    List.of("El Manto, el Vigilante, el Eco,", "la Guadaña, la Masamune y mejoras."), "Forja"));

    /** La tarjeta del Kit de Expedicion en la portada: nombre, lore y si brilla (se puede pedir ya). */
    record Tarjeta(Component nombre, List<Component> lore, boolean brillo) {
    }

    /**
     * La tarjeta del Kit, sin Bukkit: lo que presta, cada cuanto y si se puede pedir ya (o por
     * que no, con las mismas razones que Kit.pedir). tragos 0: sin Frasco.
     */
    static Tarjeta tarjetaKit(Kit.Estado e, int tragos, double cadaHoras) {
        boolean puede = e.motivo() == null;
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.texto(tragos > 0 ? "Hierro completo, espada de piedra," : "Hierro completo, espada de piedra"));
        lore.add(Marco.texto(tragos > 0 ? "8 panes y un Frasco de Calma." : "y 8 panes."));
        lore.add(Marco.tenue("Es prestado: se deshace al"));
        lore.add(Marco.tenue("salir de Calamity."));
        lore.add(Component.empty());
        String cada = cadaHoras == Math.rint(cadaHoras) ? String.valueOf((long) cadaHoras)
                : String.valueOf(cadaHoras).replace('.', ',');
        lore.add(Marco.dato("Uno cada", cada + " h"));
        lore.add(Component.empty());
        lore.add(switch (e.motivo() == null ? "" : e.motivo()) {
            case "" -> Marco.accion("Clic para pedirlo");
            case "dentro" -> Marco.porQueNo("Se pide fuera de Calamity.");
            case "armadura" -> Marco.porQueNo("Quítate la armadura para pedirlo.");
            case "espera" -> Marco.porQueNo("Vuelve en " + Math.max(1, e.horas()) + " h.");
            default -> Marco.porQueNo("Ahora no se puede pedir.");
        });
        return new Tarjeta(Component.text("Kit de Expedición", puede ? Paleta.DETALLE : Paleta.TENUE), lore, puede);
    }

    /** Una cosa de la Forja: un trueque, o el boton Grabar. */
    record Cosa(Altar.Trueque t, String boton) {
    }

    /** Un grupo de la Forja: el color de su banda, su nombre, dos lineas de que pide y lo que lleva. */
    record Seccion(String id, Material banda, String nombre, List<String> texto, List<Cosa> cosas) {
    }

    private final Hardcore hc;
    private final Altar altar;
    private final Map<UUID, Long> ultimoClic = new HashMap<>();
    private final Map<String, ItemStack> iconos = new HashMap<>();
    private long iconosDesde;

    MenuAltar(Hardcore hc, Altar altar) {
        this.hc = hc;
        this.altar = altar;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("menus", MenuAltar::autotest);
    }

    void parar() {
        HandlerList.unregisterAll(this);
        // Un menu abierto de un modulo parado se quedaria sin nadie que atienda sus clics.
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Marca) p.closeInventory();
        }
        ultimoClic.clear();
        iconos.clear();
    }

    // ------------------------------------------------------------------ que va en cada pagina

    /** La pagina que se pide, o la portada si no es ninguna (umbral o cualquier otra cosa). */
    static String pagina(String p) {
        if (FORJA.equals(p) || EXPEDICION.equals(p) || LLAVES.equals(p)) return p;
        return UMBRAL;
    }

    /** El titulo de cada pagina: "CALAMITY | Altar del Umbral", "CALAMITY | Forja" o "CALAMITY | <categoria>". */
    static Marco.Titulo titulo(String pagina) {
        String pg = pagina(pagina);
        if (pg.equals(UMBRAL)) return Marco.T_ALTAR;
        if (pg.equals(FORJA)) return Marco.T_FORJA;
        for (Categoria c : CATEGORIAS) if (c.id().equals(pg)) return Marco.categoria(c.seccion());
        return Marco.T_ALTAR;
    }

    /**
     * En que pagina sale un trueque: expedicion, llaves o forja. Null si no sale en el Altar
     * (depositar y camino, o una pagina que no es ni umbral ni forja).
     */
    static String categoriaDe(Altar.Trueque t) {
        if (FUERA.contains(t.da())) return null;
        if (FORJA.equals(t.pagina())) return FORJA;
        if (!UMBRAL.equals(t.pagina())) return null;
        if (t.da().equals("recargar") || t.da().equals("frasco") || t.da().equals("cristal")) return EXPEDICION;
        String o = t.objeto();
        // 1.10: el Reclamo es para usarlo dentro, como el Cristal: va con lo de la expedicion.
        if ("tintura".equals(o) || "frasco-1".equals(o) || "cristal".equals(o) || "reclamo".equals(o)
                || Barometro.OBJETO.equals(o) || Faroles.OBJETO_I.equals(o) || Faroles.OBJETO_II.equals(o)
                || BrujulaCaida.OBJETO.equals(o)) return EXPEDICION;
        return LLAVES;
    }

    /**
     * Los trueques de una pagina en el orden de la config. Sin Bukkit: el autotest la usa con
     * los de serie. salvoconducto: si sale (apagado no se ensena, DIS M34).
     */
    static List<Altar.Trueque> trueques(String pagina, List<Altar.Trueque> todos, boolean salvoconducto) {
        List<Altar.Trueque> out = new ArrayList<>();
        for (Altar.Trueque t : todos) {
            if ("salvoconducto".equals(t.objeto()) && !salvoconducto) continue;
            if (pagina.equals(categoriaDe(t))) out.add(t);
        }
        return out;
    }

    /** De que grupo de la Forja es un trueque. */
    static String grupoDe(Altar.Trueque t) {
        if (t.pieza() == null) return "mejoras";
        String c = t.credito() == null ? "" : t.credito();
        if (c.startsWith("sello:") || c.equals(Creditos.ERRANTE)) return "manto";
        // El set del Vigilante: piden Placas del Vigilante.
        if (t.pide(Entregas.PLACA_DEL_VIGILANTE) > 0) return "vigilante";
        // El Vestigio (Marcas de Eco), la Guadana (Fragmentos de Guadana) y las Masamune (Fragmentos de
        // Masamune) comparten fila: con una quinta fila la Forja no cabria en una hoja.
        if (c.equals("marca") || c.equals("fragmento") || t.pide(FragmentosMasamune.OBJETO) > 0) return "amenazas";
        return "piezas";
    }

    /** Los grupos de la Forja con sus trueques y Grabar al final de las mejoras. Los vacios no salen. */
    static List<Seccion> forja(List<Altar.Trueque> todos, boolean salvoconducto) {
        Map<String, Seccion> s = new LinkedHashMap<>();
        s.put("mejoras", new Seccion("mejoras", Material.YELLOW_STAINED_GLASS_PANE, "Mejoras",
                List.of("Para el equipo que ya tienes:", "talismán, gemas, grabados y ascuas."), new ArrayList<>()));
        s.put("manto", new Seccion("manto", Material.ORANGE_STAINED_GLASS_PANE, "El Manto y el Hacha",
                List.of("Cada pieza pide el Sello", "de su minijefe."), new ArrayList<>()));
        s.put("vigilante", new Seccion("vigilante", Material.BROWN_STAINED_GLASS_PANE, "El Vigilante",
                List.of("Cada pieza pide Placas del Vigilante,", "que suelta al ser vencido."), new ArrayList<>()));
        s.put("amenazas", new Seccion("amenazas", Material.PURPLE_STAINED_GLASS_PANE, "El Eco, la Parca y Ambush",
                List.of("El Vestigio pide Marcas de Eco;", "la Guadaña, Fragmentos de Guadaña;",
                        "las Masamune, Fragmentos de Masamune."), new ArrayList<>()));
        s.put("piezas", new Seccion("piezas", Material.LIGHT_GRAY_STAINED_GLASS_PANE, "Otras piezas",
                List.of("Piden créditos de Calamity."), new ArrayList<>()));
        for (Altar.Trueque t : trueques(FORJA, todos, salvoconducto)) s.get(grupoDe(t)).cosas().add(new Cosa(t, null));
        s.get("mejoras").cosas().add(new Cosa(null, "grabar"));
        List<Seccion> out = new ArrayList<>();
        for (Seccion sec : s.values()) if (!sec.cosas().isEmpty()) out.add(sec);
        return out;
    }

    static List<Integer> tamanos(List<Seccion> secs) {
        List<Integer> out = new ArrayList<>();
        for (Seccion s : secs) out.add(s.cosas().size());
        return out;
    }

    /** Donde cae cada cosa de una pagina: la Forja por grupos con banda, una categoria en rejilla, la portada nada. */
    static List<Marco.Sitio> sitios(String pagina, List<Altar.Trueque> todos, boolean salvoconducto) {
        String pg = pagina(pagina);
        if (pg.equals(UMBRAL)) return List.of();
        if (pg.equals(FORJA)) return Marco.repartir(tamanos(forja(todos, salvoconducto)));
        return Marco.rejilla(trueques(pg, todos, salvoconducto).size());
    }

    /** Las casillas de n tarjetas en la portada: en la fila del medio, centradas y con aire. */
    static int[] tarjetas(int n) {
        int[] cols = Marco.columnas(n);
        int[] out = new int[cols.length];
        for (int i = 0; i < cols.length; i++) out[i] = FILA_TARJETAS + cols[i];
        return out;
    }

    /**
     * Si el trueque pide confirmar antes de gastar: todo lo de la Forja, lo que pide MobCoins o
     * un credito, y lo que llegue a "desde" Esencias (desde <= 0: solo lo anterior).
     */
    static boolean pideConfirmar(Altar.Trueque t, Altar.Precio pr, int desde) {
        if (t.servicio()) return false;
        if (FORJA.equals(t.pagina()) || pr.mc() > 0 || pr.credito() != null) return true;
        return desde > 0 && pr.esencias() >= desde;
    }

    // ------------------------------------------------------------------ abrir y pintar

    void abrir(Player p, String pagina) {
        abrir(p, pagina, 0, true);
    }

    /**
     * Abre una pagina (umbral = la portada, expedicion, llaves o forja) en una hoja. conSonido:
     * el del Altar al abrirlo desde el NPC o el bloque (cambiar de pagina suena aparte).
     */
    void abrir(Player p, String pagina, int hoja, boolean conSonido) {
        if (!altar.activo()) {
            p.sendMessage(ComandoCalamity.mensaje("El Altar está cerrado ahora mismo."));
            return;
        }
        String pg = pagina(pagina);
        boolean salvo = hc.cfg().getBoolean("salvoconducto.activo", false);
        int total = Marco.hojas(sitios(pg, altar.trueques(), salvo));
        int h = Math.max(0, Math.min(hoja, total - 1));
        Marca m = new Marca(pg, new HashMap<>(), null, h, null, null);
        Inventory inv = hc.plugin().getServer().createInventory(m, pg.equals(UMBRAL) ? PORTADA : 54, titulo(pg).componente());
        pintar(inv, p, m);
        p.openInventory(inv);
        if (conSonido) {
            if (pg.equals(FORJA)) Marco.sonar(p, "block.smithing_table.use", 0.6f, 0.9f);
            else Marco.sonar(p, "block.enchantment_table.use", 0.8f, 1.0f);
        }
    }

    /** Vuelve a pintar la pagina que tenga abierta (tras comprar: saldo, cupos y precios cambian). */
    void repintar(Player p) {
        if (!p.isOnline()) return;
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof Marca m && (m.pagina().equals(UMBRAL) || m.pagina().equals(FORJA)
                || m.pagina().equals(EXPEDICION) || m.pagina().equals(LLAVES))) {
            pintar(top, p, m);
        }
    }

    private void pintar(Inventory inv, Player p, Marca m) {
        inv.clear();
        m.acciones().clear();
        List<Altar.Trueque> todos = altar.trueques();
        boolean salvo = hc.cfg().getBoolean("salvoconducto.activo", false);
        Marco.saldo(inv, m.acciones(), hc, p, Marco.SALDO);
        if (m.pagina().equals(UMBRAL)) portada(inv, p, m, todos, salvo);
        else if (m.pagina().equals(FORJA)) paginaForja(inv, p, m, todos, salvo);
        else paginaCategoria(inv, p, m, todos, salvo);
        Marco.rellenar(inv);
    }

    /** La portada: las tarjetas de las categorias que tengan algo, la ayuda y el Tasador. */
    private void portada(Inventory inv, Player p, Marca m, List<Altar.Trueque> todos, boolean salvo) {
        UUID u = p.getUniqueId();
        Altar.Caja caja = altar.caja();
        List<Categoria> hay = new ArrayList<>();
        for (Categoria c : CATEGORIAS) if (!trueques(c.id(), todos, salvo).isEmpty()) hay.add(c);
        // 1.12: el Kit de Expedicion, al final, solo si esta en marcha.
        Kit kit = hc.kit();
        boolean conKit = kit != null && hc.valor("kit", kit::activo, false);
        int[] casillas = tarjetas(hay.size() + (conKit ? 1 : 0));
        // Las categorias van primero; la ultima casilla, con el Kit, es la suya (no hay categoria para ella).
        for (int i = 0; i < hay.size() && i < casillas.length; i++) {
            Categoria c = hay.get(i);
            List<Altar.Trueque> ts = trueques(c.id(), todos, salvo);
            int ya = 0;
            for (Altar.Trueque t : ts) if (!t.servicio() && Altar.revisar(caja, t, u).motivo() == null) ya++;
            boolean forja = c.id().equals(FORJA);
            List<Component> lore = new ArrayList<>();
            for (String l : c.texto()) lore.add(Marco.texto(l));
            lore.add(Component.empty());
            lore.add(Marco.dato("Artículos", String.valueOf(ts.size())));
            if (ya > 0) lore.add(Marco.tiene("Ya puedes " + (forja ? "forjar " : "comprar ") + ya + "."));
            lore.add(Component.empty());
            lore.add(Marco.accion("Clic para entrar"));
            inv.setItem(casillas[i], Marco.icono(c.icono(), Component.text(c.nombre(), forja ? Altar.AMBAR : Paleta.DETALLE), lore, ya > 0));
            m.acciones().put(casillas[i], "cat:" + c.id());
        }
        if (conKit) {
            int c = casillas[casillas.length - 1];
            Tarjeta t = tarjetaKit(kit.estado(p), kit.tragos(), kit.cadaHoras());
            inv.setItem(c, Marco.icono(Material.IRON_CHESTPLATE, t.nombre(), t.lore(), t.brillo()));
            m.acciones().put(c, "kit");
        }
        if (hay.isEmpty() && !conKit) {
            inv.setItem(FILA_TARJETAS + 4, Marco.icono(Material.GRAY_DYE, Component.text("El Altar no tiene nada ahora", Paleta.TENUE),
                    List.of(Marco.tenue("Vuelve más tarde.")), false));
        }
        inv.setItem(AYUDA, Marco.ayuda(hc));
        inv.setItem(CERRAR, Marco.cerrar());
        m.acciones().put(CERRAR, "cerrar");
        Marco.enlace(inv, m.acciones(), IR_TASADOR, Marco.TASADOR, Material.EMERALD, "Mercado de Oren",
                List.of("Te compra Reliquias y Esencias", "y te muestra tu dinero."), hc.npcs() != null);
    }

    /** Una categoria: sus articulos en rejilla y abajo las flechas y Volver. */
    private void paginaCategoria(Inventory inv, Player p, Marca m, List<Altar.Trueque> todos, boolean salvo) {
        List<Altar.Trueque> ts = trueques(m.pagina(), todos, salvo);
        List<Marco.Sitio> sitios = Marco.rejilla(ts.size());
        Altar.Caja caja = altar.caja();
        for (Marco.Sitio s : sitios) if (s.hoja() == m.hoja()) ponerTrueque(inv, p, m, s.casilla(), ts.get(s.indice()), caja);
        if (ts.isEmpty()) {
            inv.setItem(CENTRO, Marco.icono(Material.GRAY_DYE, Component.text("Nada por ahora", Paleta.TENUE),
                    List.of(Marco.tenue("Aquí no hay nada a la venta ahora.")), false));
        }
        pie(inv, m, Marco.hojas(sitios));
    }

    /** La Forja: cada grupo en su fila con su banda a los lados. */
    private void paginaForja(Inventory inv, Player p, Marca m, List<Altar.Trueque> todos, boolean salvo) {
        List<Seccion> secs = forja(todos, salvo);
        List<Marco.Sitio> sitios = Marco.repartir(tamanos(secs));
        Altar.Caja caja = altar.caja();
        for (Marco.Sitio s : sitios) {
            if (s.hoja() != m.hoja()) continue;
            Seccion sec = secs.get(s.seccion());
            if (s.indice() < 0) {
                Marco.ponerBanda(inv, s.casilla(), Marco.banda(sec.banda(), sec.nombre(), sec.texto()));
                continue;
            }
            Cosa c = sec.cosas().get(s.indice());
            if (c.boton() != null) grabar(inv, p, m, s.casilla());
            else ponerTrueque(inv, p, m, s.casilla(), c.t(), caja);
        }
        pie(inv, m, Marco.hojas(sitios));
    }

    /** La fila de abajo de una pagina de articulos: flechas en las esquinas y Volver al Altar en el centro. */
    private static void pie(Inventory inv, Marca m, int total) {
        if (m.hoja() > 0) {
            inv.setItem(Marco.ANTERIOR, Marco.flecha(-1, m.hoja(), total));
            m.acciones().put(Marco.ANTERIOR, "hoja:" + (m.hoja() - 1));
        }
        if (m.hoja() < total - 1) {
            inv.setItem(Marco.SIGUIENTE, Marco.flecha(1, m.hoja(), total));
            m.acciones().put(Marco.SIGUIENTE, "hoja:" + (m.hoja() + 1));
        }
        inv.setItem(Marco.VOLVER, Marco.volver("al Altar"));
        m.acciones().put(Marco.VOLVER, "volver");
    }

    /** El boton Grabar de la Forja: lo que hace, cuantos llevas esta semana y si llevas un Grabado. */
    private void grabar(Inventory inv, Player p, Marca m, int casilla) {
        ObjetosCalamity obj = hc.objetos();
        List<Component> lore = new ArrayList<>(List.of(Marco.texto("Gasta un Grabado de Calamidad"),
                Marco.texto("para subir de nivel un encantamiento"), Marco.texto("que ya esté al máximo."),
                Marco.tenue("Lleva el objeto en la mano. Solo"), Marco.tenue("sirve en equipo vanilla."), Component.empty()));
        boolean tiene = false;
        if (obj == null) {
            lore.add(Marco.tenue("Próximamente."));
        } else {
            int hechos = hc.datos().getInt(ObjetosCalamity.rutaGrabados(altar.calendario().semana(), p.getUniqueId()), 0);
            lore.add(Marco.dato("Esta semana", hechos + " de " + obj.porSemana()));
            lore.add(Component.empty());
            tiene = ObjetosCalamity.casillaGrabado(p.getInventory().getContents(), p.getUniqueId()) >= 0;
            lore.add(tiene ? Marco.accion("Clic para grabar") : Marco.porQueNo("No llevas ningún Grabado."));
        }
        inv.setItem(casilla, Marco.icono(Material.GRINDSTONE,
                Component.text("Grabar", obj == null ? Paleta.TENUE : Altar.AMBAR), lore, tiene));
        m.acciones().put(casilla, obj == null ? "gris" : "grabar");
    }

    // ------------------------------------------------------------------ los trueques

    /** El objeto que se da, tal cual se crea (MMOItems incluido), o el icono de la config si no se puede. */
    private ItemStack base(Altar.Trueque t) {
        long ahora = System.currentTimeMillis();
        if (ahora - iconosDesde > ICONOS_MS) {
            iconos.clear();
            iconosDesde = ahora;
        }
        String objeto = t.objeto();
        ItemStack it = null;
        if (objeto != null) {
            it = iconos.get(objeto);
            if (it == null && !iconos.containsKey(objeto)) {
                Entregas e = hc.entregas();
                it = e == null ? null : hc.valor("entregas", () -> e.crear(objeto), null);
                iconos.put(objeto, it);
            }
        }
        ItemStack out = it != null ? it.clone() : new ItemStack(t.icono());
        out.setAmount(Math.max(1, Math.min(64, t.cantidad())));
        return out;
    }

    /** Que es, en una o dos lineas: el lore de la config si lo trae; si no, el de serie con las cifras de la config. */
    private List<String> descripcion(Altar.Trueque t) {
        if (!t.lore().isEmpty()) return t.lore();
        org.bukkit.configuration.ConfigurationSection c = hc.cfg();
        return switch (t.id()) {
            case "frasco" -> List.of("Trae " + c.getInt("frasco.usos", 3) + " tragos y cada uno te",
                    "devuelve " + c.getInt("frasco.cordura", 40) + " de cordura.");
            case "cristal" -> List.of("Te saca de Calamity si te", "quedas quieto " + c.getInt("cristal.segundos", 5) + " segundos.");
            case "talisman" -> List.of("+" + c.getInt("talisman.vida", 3) + " de vida. En Calamity, la",
                    "cordura baja un " + Math.round((1 - c.getDouble("talisman.drenaje", 0.80)) * 100) + " % más despacio.");
            // 1.16.0: lo que arde cada farol, de la config.
            case "farol-1" -> descripcionFarol(Faroles.duracion(Faroles.seccion(c), 1));
            case "farol-2" -> descripcionFarol(Faroles.duracion(Faroles.seccion(c), 2));
            default -> DESCRIPCION.getOrDefault(t.id(), descripcionPieza(t));
        };
    }

    /** 1.16.0 · "Arde 5 min en la mano: tu cordura" / "no se mueve y la locura calla." */
    static List<String> descripcionFarol(int segundos) {
        String t = segundos % 60 == 0 ? (segundos / 60) + " min" : Faroles.reloj(segundos);
        return List.of("Arde " + t + " en la mano: tu cordura", "no se mueve y la locura calla.");
    }

    /** Un trueque en su casilla: que es, el coste linea a linea, el cupo y el clic (o por que no). */
    private void ponerTrueque(Inventory inv, Player p, Marca m, int casilla, Altar.Trueque t, Altar.Caja caja) {
        UUID u = p.getUniqueId();
        List<Component> lore = new ArrayList<>();
        List<String> desc = descripcion(t);
        for (String l : desc) lore.add(Marco.texto(l));

        if (t.da().equals("recargar")) {
            boolean puede = recarga(p, lore);
            inv.setItem(casilla, Marco.icono(base(t), Component.text(Altar.nombre(t), Paleta.DETALLE), lore, puede));
            m.acciones().put(casilla, "t:" + t.id());
            return;
        }

        Altar.Plan plan = Altar.revisar(caja, t, u);
        Altar.Precio pr = plan.precio();
        if (pr.reposicion()) {
            int dias = Forja.diasReposicion(hc.datos(), u, t.pieza(), System.currentTimeMillis(),
                    hc.cfg().getInt("forja.reposicion-dias", 14));
            lore.add(Component.text("La perdiste en Calamity: reponerla", Paleta.BIEN));
            lore.add(Component.text("sale más barato " + dias + (dias == 1 ? " día más." : " días más."), Paleta.BIEN));
        }
        if (!lore.isEmpty()) lore.add(Component.empty());
        lore.addAll(costes(u, t, pr, caja));

        List<Component> cupo = cupos(t, u, caja);
        // 1.7.6: la Ofrenda dice cuantas llevas este mes (ofrendas-mes, la tabla que ya las cuenta).
        if ("ofrenda".equals(t.da())) cupo.add(ofrendasLinea(altar.ofrendasMes(u)));
        if (!cupo.isEmpty()) {
            lore.add(Component.empty());
            lore.addAll(cupo);
        }
        // Dentro de Calamity (la zona spawn) lo que se compra no llega a la mano: espera fuera (Entregas).
        Entregas en = hc.entregas();
        if (en != null && Entregas.esObjeto(t.objeto()) && !en.recibeYa(p)) {
            lore.add(Component.empty());
            lore.add(Component.text("Estás en Calamity: lo recibirás", Paleta.CIFRA));
            lore.add(Component.text("cuando salgas.", Paleta.CIFRA));
        }
        lore.add(Component.empty());
        boolean gris = "mc".equals(plan.motivo()) && "proximamente".equals(plan.faltan());
        boolean puede = plan.motivo() == null;
        if (puede) lore.add(Marco.accion(FORJA.equals(t.pagina()) ? "Clic para forjarlo" : "Clic para comprarlo"));
        else if (gris) lore.add(Marco.tenue("Próximamente: el Altar aún no cobra MobCoins."));
        else lore.add(Marco.porQueNo(porQueNo(plan)));
        TextColor color = gris ? Paleta.TENUE : FORJA.equals(t.pagina()) ? Altar.AMBAR : Paleta.DETALLE;
        inv.setItem(casilla, Marco.icono(base(t), Component.text(Altar.nombre(t), color), lore, puede));
        m.acciones().put(casilla, "t:" + t.id());
    }

    /** Lo que se dice de una pieza sin descripcion propia: que es, de donde sale su Sello y a cuanto se repone. */
    private List<String> descripcionPieza(Altar.Trueque t) {
        if (t.pieza() == null) return List.of();
        List<String> out = new ArrayList<>();
        if (Set.of("yelmo", "coraza", "grebas", "soleretas").contains(t.pieza())) out.add("Pieza del Manto de Calamidad.");
        if (Forja.PIEZAS_VIGILANTE.contains(t.pieza())) {
            out.add(t.pieza().equals("vig-mazo") ? "El arma del set del Vigilante." : "Pieza del set del Vigilante.");
            out.add("Las Placas las suelta el Vigilante.");
        }
        String c = t.credito() == null ? "" : t.credito();
        if (c.startsWith("sello:")) {
            String de = Forja.delMinijefe(c.substring(6));
            out.add("Su Sello lo suelta " + (de.startsWith("de la ") ? "la " + de.substring(6) : "el " + de.substring(4)) + ".");
        }
        if (t.conReposicion()) {
            out.add("Si la pierdes, durante " + hc.cfg().getInt("forja.reposicion-dias", 14) + " días");
            out.add("la repones con " + t.reposEsencias() + " Esencias en vez de " + t.esencias() + ".");
        }
        return out;
    }

    /** El coste linea a linea: ✔ lo que tiene, ✘ lo que le falta (y cuanto). */
    private static List<Component> costes(UUID u, Altar.Trueque t, Altar.Precio pr, Altar.Caja caja) {
        List<Component> out = new ArrayList<>();
        if (pr.esencias() > 0) {
            long saldo = caja.saldo() == null ? 0 : caja.saldo().de(u);
            out.add(saldo >= pr.esencias() ? Marco.tiene(Marco.esencias(pr.esencias()))
                    : Marco.falta(Marco.esencias(pr.esencias()), "te faltan " + (pr.esencias() - saldo)));
        }
        if (pr.mc() > 0) {
            String que = Altar.miles(pr.mc()) + " MobCoins";
            long tiene = caja.mcDisponible() ? caja.mc(u) : -1;
            if (tiene < 0) out.add(Marco.apagado(que, "aún no se cobran"));
            else out.add(tiene >= pr.mc() ? Marco.tiene(que) : Marco.falta(que, "te faltan " + Altar.miles(pr.mc() - tiene)));
        }
        if (pr.credito() != null) {
            Creditos cr = caja.creditos();
            String c = pr.credito();
            int n = pr.creditos();
            String que = creditoLinea(c, n);
            if (cr == null) out.add(Marco.falta(que, null));
            else if (cr.gastables(u, c) >= n) out.add(Marco.tiene(que));
            else if (c.startsWith("sello:") && cr.gastables(u, Creditos.ERRANTE) >= n) {
                out.add(Marco.tiene(que + "  (pagas con un Sello Errante)"));
            } else if ("horas".equals(cr.motivoNoGasta(u, c, n))
                    || (c.startsWith("sello:") && "horas".equals(cr.motivoNoGasta(u, Creditos.ERRANTE, n)))) {
                out.add(Marco.falta(que, "pide " + Math.round(cr.horasPedidas()) + " h activas"));
            } else if (c.startsWith("sello:")) {
                out.add(Marco.falta(que, "no lo tienes"));
            } else {
                out.add(Marco.falta(que, "tienes " + cr.de(u, c)));
            }
        }
        for (Altar.Entrega en : t.entregar()) out.add(entregaLinea(en, caja.cuantos(u, en.objeto())));
        if (out.isEmpty()) out.add(Marco.tiene("Gratis"));
        return out;
    }

    /**
     * Lo que se entrega, como linea de coste: "✔ 5 Fragmentos de Masamune  (los entregas)" o "✘ Fragmentos
     * de Masamune: llevas 3 de 5"; una pieza, "✔ Tu Masamune  (la entregas)" o "✘ Tu Masamune  (no la
     * llevas encima)". tiene: cuantos lleva encima.
     */
    static Component entregaLinea(Altar.Entrega en, int tiene) {
        if (FragmentosMasamune.OBJETO.equals(en.objeto())) {
            return tiene >= en.cantidad()
                    ? Marco.tiene(FragmentosMasamune.nombre(en.cantidad()) + (en.cantidad() == 1 ? "  (lo entregas)" : "  (los entregas)"))
                    : Marco.falta("Fragmentos de Masamune: llevas " + tiene + " de " + en.cantidad(), null);
        }
        if (PuenteBovedas.esLlave(en.objeto())) {
            // Calamity 1.11: las Llaves del Umbral que pide la Llave Ominosa.
            String llaves = PuenteBovedas.nombre(en.objeto(), en.cantidad());
            return tiene >= en.cantidad()
                    ? Marco.tiene(llaves + (en.cantidad() == 1 ? "  (la entregas)" : "  (las entregas)"))
                    : Marco.falta(PuenteBovedas.nombre(en.objeto(), 2).replaceFirst("^2 ", "") + ": llevas " + tiene + " de " + en.cantidad(), null);
        }
        if (Forja.esPlaca(en.objeto())) {
            // El set del Vigilante: "✔ 3 Placas del Vigilante  (las entregas)" o "✘ Placas del Vigilante: llevas 1 de 3".
            return tiene >= en.cantidad()
                    ? Marco.tiene(Forja.placas(en.cantidad()) + (en.cantidad() == 1 ? "  (la entregas)" : "  (las entregas)"))
                    : Marco.falta("Placas del Vigilante: llevas " + tiene + " de " + en.cantidad(), null);
        }
        String que = "Tu " + Forja.nombrePieza(en.objeto());
        return tiene >= en.cantidad() ? Marco.tiene(que + "  (la entregas)") : Marco.falta(que, "no la llevas encima");
    }

    /** "Sello del Custodio de las Ruinas", "5 Marcas de Eco": el credito como linea de coste. */
    static String creditoLinea(String tipo, int n) {
        String t = tipo == null ? "" : tipo;
        if (t.startsWith("sello:")) return "Sello " + Forja.delMinijefe(t.substring(6));
        return switch (t) {
            case Creditos.ERRANTE -> "Sello Errante";
            case "marca" -> n == 1 ? "1 Marca de Eco" : n + " Marcas de Eco";
            case "fragmento" -> n == 1 ? "1 Fragmento de Guadaña" : n + " Fragmentos de Guadaña";
            default -> n + " " + t;
        };
    }

    /** Lo que queda de cada limite: semana, dia, stock, llaves, la subida de la Ascua y la espera. */
    private static List<Component> cupos(Altar.Trueque t, UUID u, Altar.Caja caja) {
        List<Component> out = new ArrayList<>();
        if (t.limiteSemana() > 0) {
            int q = Math.max(0, t.limiteSemana() - Altar.usosSemana(caja, u, t.id()));
            out.add(Marco.dato("Esta semana", "te " + (q == 1 ? "queda " : "quedan ") + q + " de " + t.limiteSemana()));
        }
        if (t.limiteDia() > 0) {
            int q = Math.max(0, t.limiteDia() - Altar.usosDia(caja, u, t.id()));
            out.add(Marco.dato("Hoy", "te " + (q == 1 ? "queda " : "quedan ") + q + " de " + t.limiteDia()));
        }
        if (t.stock() > 0) {
            out.add(Marco.dato("En el Altar", "quedan " + Math.max(0, t.stock() - Altar.stockUsado(caja, t.id())) + " esta semana"));
        }
        if ("tope-llaves".equalsIgnoreCase(t.requisito())) {
            out.add(Marco.dato("Llaves que te caben", String.valueOf(caja.llavesLibres(u))));
        }
        if (t.incremento() > 0) {
            out.add(Marco.tenue("Cada una de la semana cuesta"));
            out.add(Marco.tenue(t.incremento() + " Esencias más que la anterior."));
        }
        if (t.esperaDias() > 0) {
            out.add(Marco.tenue("Puedes forjar una nueva cada " + t.esperaDias() + " días."));
            out.add(Marco.tenue("Reponerla no tiene espera."));
        }
        return out;
    }

    /** 1.7.6: "Llevas 3 Ofrendas este mes.", con la cifra resaltada como en los cupos. */
    static Component ofrendasLinea(int n) {
        return Component.text("Llevas ", Paleta.TENUE).append(Component.text(String.valueOf(n), Paleta.CIFRA))
                .append(Component.text(n == 1 ? " Ofrenda este mes." : " Ofrendas este mes.", Paleta.TENUE));
    }

    /** La razon corta, en el icono. El mensaje completo sale al hacer clic. */
    private static String porQueNo(Altar.Plan plan) {
        Object f = plan.faltan();
        return switch (plan.motivo()) {
            case "esencias" -> "Te faltan " + f + " Esencias.";
            case "mc" -> "Te faltan " + (f instanceof Number n ? Altar.miles(n.longValue()) : f) + " MobCoins.";
            case "credito" -> "horas".equals(f) ? "Te faltan horas activas para usarlo." : "Aún no tienes lo que pide.";
            case "cupo" -> "tope-llaves".equals(f) ? "Tu tope de llaves está lleno."
                    : String.valueOf(f).endsWith("d") ? "Podrás forjar otra en " + String.valueOf(f).replace("d", "") + " días."
                    : "Ya has agotado tu cupo.";
            case "stock" -> "Agotado hasta la semana que viene.";
            case "requisito" -> String.valueOf(f).contains("insomne") ? "Solo si tienes el tag [INSOMNE]." : "Aún no se puede.";
            case "objeto" -> {
                Altar.Falta fa = f instanceof Altar.Falta x ? x : new Altar.Falta(String.valueOf(f), 0, 1);
                int n = Math.max(1, fa.faltan());
                if (PuenteBovedas.esLlave(fa.objeto())) {
                    yield (n == 1 ? "Te falta una " : "Te faltan ") + PuenteBovedas.nombre(fa.objeto(), n) + ".";
                }
                if (Forja.esPlaca(fa.objeto())) {
                    yield n == 1 ? "Te falta una Placa del Vigilante." : "Te faltan " + Forja.placas(n) + ".";
                }
                yield FragmentosMasamune.OBJETO.equals(fa.objeto())
                        ? (n == 1 ? "Te falta " : "Te faltan ") + FragmentosMasamune.nombre(n) + "."
                        : "No llevas encima tu " + Forja.nombrePieza(fa.objeto()) + ".";
            }
            default -> "Ahora no se puede.";
        };
    }

    /** El frasco que se recargaria y lo que costaria. True si se puede llenar ya. */
    private boolean recarga(Player p, List<Component> lore) {
        ItemsCalamity items = hc.items();
        ItemStack frasco = items.esFrasco(p.getInventory().getItemInMainHand()) ? p.getInventory().getItemInMainHand() : null;
        if (frasco == null) {
            for (ItemStack it : p.getInventory().getStorageContents()) {
                if (items.esFrasco(it)) {
                    frasco = it;
                    break;
                }
            }
        }
        int porTrago = Math.max(0, hc.cfg().getInt("frasco.esencias-por-trago", 1));
        lore.add(Marco.tenue("Cuesta " + Marco.esencias(porTrago) + " por cada trago vacío."));
        lore.add(Component.empty());
        if (frasco == null) {
            lore.add(Marco.porQueNo("No llevas ningún Frasco de Calma."));
            return false;
        }
        int max = hc.cfg().getInt("frasco.usos", 3);
        int tragos = Math.max(0, items.tragos(frasco));
        lore.add(Marco.dato("Tu frasco", tragos + " de " + max + " tragos"));
        if (tragos >= max) {
            lore.add(Component.empty());
            lore.add(Marco.tenue("Tu frasco ya está lleno."));
            return false;
        }
        int coste = (max - tragos) * porTrago;
        Saldo s = hc.saldo();
        long saldo = s == null ? 0 : s.de(p.getUniqueId());
        lore.add(saldo >= coste ? Marco.tiene(Marco.esencias(coste)) : Marco.falta(Marco.esencias(coste), "te faltan " + (coste - saldo)));
        lore.add(Component.empty());
        if (saldo < coste) {
            lore.add(Marco.porQueNo("Te faltan " + (coste - saldo) + " Esencias."));
            return false;
        }
        lore.add(Marco.accion("Clic para rellenarlo"));
        return true;
    }

    // ------------------------------------------------------------------ confirmar

    /**
     * La pantalla de confirmar (45): arriba lo que te llevas tal cual (el objeto de verdad, con
     * todo su lore de MMOItems), en medio lo que pagas y lo que te queda, abajo Cancelar y el
     * boton que lo hace.
     */
    private void abrirConfirmar(Player p, Marca desde, Altar.Trueque t, Altar.Plan plan) {
        UUID u = p.getUniqueId();
        Map<Integer, String> acciones = new HashMap<>();
        Marca m = new Marca(CONFIRMAR, acciones, null, desde.hoja(), t.id(), desde.pagina());
        boolean forja = FORJA.equals(t.pagina());
        Inventory inv = hc.plugin().getServer().createInventory(m, 45,
                (forja ? Marco.T_FORJAR : Marco.T_COMPRAR).componente());

        // Lo de MMOItems (y los objetos de Calamity) traen su lore: se ensenan tal cual se entregan.
        ItemStack centro = base(t);
        ItemMeta meta = centro.getItemMeta();
        if (meta == null || meta.lore() == null || meta.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String l : descripcion(t)) lore.add(Marco.texto(l));
            centro = Marco.icono(centro, Component.text(Altar.nombre(t), forja ? Altar.AMBAR : Paleta.DETALLE), lore, false);
        }
        inv.setItem(13, centro);

        Altar.Precio pr = plan.precio();
        List<ItemStack> pagos = new ArrayList<>();
        List<String> resumen = new ArrayList<>();
        if (pr.esencias() > 0) {
            Saldo s = hc.saldo();
            pagos.add(pago(hc.items().materialEsencia(), "−" + Marco.esencias(pr.esencias()), s == null ? -1 : s.de(u), pr.esencias()));
            resumen.add(Marco.esencias(pr.esencias()));
        }
        if (pr.mc() > 0) {
            pagos.add(pago(Material.SUNFLOWER, "−" + Altar.miles(pr.mc()) + " MobCoins", altar.caja().mc(u), pr.mc()));
            resumen.add(Altar.miles(pr.mc()) + " MobCoins");
        }
        if (pr.credito() != null && plan.creditoUsado() != null) {
            String usado = plan.creditoUsado();
            Creditos cr = hc.creditos();
            Material icono = usado.equals("marca") ? Material.ECHO_SHARD : usado.equals("fragmento") ? Material.BELL
                    : Material.FIRE_CHARGE;
            String que = creditoLinea(usado, pr.creditos());
            pagos.add(pago(icono, "−" + que, cr == null ? -1 : cr.de(u, usado), pr.creditos()));
            resumen.add(usado.equals(Creditos.ERRANTE) ? que + " (vale por el " + creditoLinea(pr.credito(), 1) + ")" : que);
        }
        for (Altar.Entrega en : t.entregar()) {
            if (FragmentosMasamune.OBJETO.equals(en.objeto())) {
                // Los Fragmentos, con los que llevas y los que te quedarian.
                String que = FragmentosMasamune.nombre(en.cantidad());
                pagos.add(pago(Material.NETHERITE_SCRAP, "−" + que, altar.caja().cuantos(u, en.objeto()), en.cantidad()));
                resumen.add(que);
                continue;
            }
            if (PuenteBovedas.esLlave(en.objeto())) {
                // Calamity 1.11: las Llaves del Umbral, como los Fragmentos.
                String llaves = PuenteBovedas.nombre(en.objeto(), en.cantidad());
                pagos.add(pago(Material.TRIAL_KEY, "−" + llaves, altar.caja().cuantos(u, en.objeto()), en.cantidad()));
                resumen.add(llaves);
                continue;
            }
            if (Forja.esPlaca(en.objeto())) {
                // El set del Vigilante: las Placas, con las que llevas y las que te quedarian, con su aspecto real.
                String placas = Forja.placas(en.cantidad());
                pagos.add(pago(materialPlaca(), "−" + placas, altar.caja().cuantos(u, en.objeto()), en.cantidad()));
                resumen.add(placas);
                continue;
            }
            // Una pieza, sin "tienes/te quedarian": es una y se va.
            String que = "Tu " + Forja.nombrePieza(en.objeto());
            pagos.add(pago(Material.NETHERITE_SWORD, "−" + que, -1, 0));
            resumen.add(que);
        }
        int[] cols = Marco.columnas(pagos.size());
        for (int i = 0; i < pagos.size(); i++) inv.setItem(18 + cols[i], pagos.get(i));

        List<Component> si = new ArrayList<>();
        si.add(Marco.tenue("Pagas:"));
        for (String r : resumen) si.add(Marco.texto("  " + r));
        if (resumen.isEmpty()) si.add(Marco.texto("  Nada."));
        if (pr.reposicion()) si.add(Component.text("Es el precio de reposición.", Paleta.BIEN));
        si.add(Component.empty());
        si.add(Marco.accion(forja ? "Clic para forjarlo" : "Clic para comprarlo"));
        ItemStack boton = Marco.icono(Material.LIME_CONCRETE,
                Component.text("✔ " + (forja ? "Forjar " : "Comprar ") + Altar.nombre(t), Marco.SI), si, false);
        ItemStack cancelar = Marco.icono(Material.RED_CONCRETE, Component.text("✘ Cancelar", Marco.NO), List.of(
                Marco.tenue("Vuelves " + (forja ? "a la Forja" : "al Altar") + " sin gastar nada.")), false);
        for (int c : new int[]{28, 29, 30}) {
            inv.setItem(c, cancelar);
            acciones.put(c, "no");
        }
        for (int c : new int[]{32, 33, 34}) {
            inv.setItem(c, boton);
            acciones.put(c, "si");
        }
        Marco.rellenar(inv);
        p.openInventory(inv);
        Marco.sonar(p, forja ? "block.anvil.place" : "block.note_block.hat", 0.5f, 1.3f);
    }

    /**
     * El material de la Placa del Vigilante tal cual la crea MMOItems (el de su plantilla); sin MMOItems, el de
     * docs/set-vigilante (la escama de armadillo: una placa, sin lineas vanilla en el tooltip).
     */
    private Material materialPlaca() {
        Entregas e = hc.entregas();
        ItemStack it = e == null ? null : hc.valor("entregas", () -> e.crear(Entregas.PLACA_DEL_VIGILANTE), null);
        return it != null ? it.getType() : Material.ARMADILLO_SCUTE;
    }

    /** Lo que se paga en la pantalla de confirmar: "−48 Esencias", lo que tienes y lo que te queda. */
    private static ItemStack pago(Material m, String nombre, long tiene, long cuesta) {
        List<Component> lore = new ArrayList<>();
        if (tiene >= 0) {
            lore.add(Marco.dato("Tienes", Altar.miles(tiene)));
            lore.add(Marco.dato("Te quedarían", Altar.miles(Math.max(0, tiene - cuesta))));
        }
        return Marco.icono(m, Component.text(nombre, Paleta.CIFRA), lore, false);
    }

    // ------------------------------------------------------------------ clics

    @EventHandler
    public void alClic(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Marca m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClick() != ClickType.LEFT) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getInventory().getSize()) return;
        String accion = m.acciones().get(slot);
        if (accion == null) return;
        long ahora = System.currentTimeMillis();
        Long antes = ultimoClic.get(p.getUniqueId());
        if (antes != null && ahora - antes < ESPERA_MS) return;
        ultimoClic.put(p.getUniqueId(), ahora);
        hc.seguro("altar", () -> accion(p, m, accion));
    }

    private void accion(Player p, Marca m, String accion) {
        if (accion.equals("cerrar")) {
            cerrar(p);
            return;
        }
        // Se abrio en un sitio bueno; si entretanto ha salido de la zona spawn a Calamity, nada.
        if (!Marco.puedeAltar(hc, p) && !m.pagina().equals(CAMINO)) {
            p.sendMessage(ComandoCalamity.mensaje(Marco.ALTAR_FUERA));
            Marco.sonidoNo(p);
            cerrar(p);
            return;
        }
        if (accion.startsWith("t:")) {
            clicTrueque(p, m, accion.substring(2));
            return;
        }
        if (accion.startsWith("cat:")) {
            String c = accion.substring(4);
            altar.tarea(() -> {
                abrir(p, c, 0, false);
                Marco.sonidoPestana(p);
            }, 1L);
            return;
        }
        if (accion.startsWith("tab:") || accion.startsWith("ir:")) {
            String a = accion.substring(accion.indexOf(':') + 1);
            altar.tarea(() -> Marco.irA(hc, p, a), 1L);
            return;
        }
        if (accion.startsWith("no-tab:")) {
            Marco.cerrado(p, accion.substring(7));
            return;
        }
        if (accion.startsWith("hoja:")) {
            int h = Integer.parseInt(accion.substring(5));
            altar.tarea(() -> {
                abrir(p, m.pagina(), h, false);
                Marco.sonidoPestana(p);
            }, 1L);
            return;
        }
        if (accion.startsWith("g:")) {
            altar.forja().grabar(p, accion.substring(2), m.foto());
            altar.tarea(() -> abrir(p, FORJA, 0, false), 1L);
            return;
        }
        if (accion.startsWith("c:")) {
            altar.camino().clic(p, accion.substring(2));
            return;
        }
        switch (accion) {
            case "si" -> confirmar(p, m);
            case "no" -> {
                Marco.sonar(p, "ui.button.click", 0.45f, 0.8f);
                altar.tarea(() -> abrir(p, m.volver(), m.hoja(), false), 1L);
            }
            case "volver" -> altar.tarea(() -> {
                abrir(p, UMBRAL, 0, false);
                Marco.sonidoPestana(p);
            }, 1L);
            case "depositar" -> {
                if (hc.esHardcore(p)) {
                    p.sendMessage(ComandoCalamity.mensaje("Aquí no: ingrésalas con el botón de las Esencias en la tienda de Oren."));
                    Marco.sonidoNo(p);
                    return;
                }
                altar.depositar(p);
                repintar(p);
            }
            case "grabar" -> altar.tarea(() -> altar.forja().abrirGrabar(p), 1L);
            // El Kit: lo mismo que "calamity open <p> kit" (Kit.pedir dice en el chat si no se puede).
            // Un tick despues: pone la armadura y repinta la tarjeta, que pasa a "Vuelve en 20 h".
            case "kit" -> altar.tarea(() -> {
                // En ese tick puede haberse ido o haber cerrado el menu: sin el menu del Altar abierto, nada.
                if (!p.isOnline() || !(p.getOpenInventory().getTopInventory().getHolder() instanceof Marca)) return;
                Kit kit = hc.kit();
                if (kit == null || !kit.activo()) {
                    p.sendMessage(ComandoCalamity.mensaje("El Kit de Expedición no está disponible ahora mismo."));
                    Marco.sonidoNo(p);
                    repintar(p);
                    return;
                }
                boolean puede = kit.estado(p).motivo() == null;
                kit.pedir(p);
                if (puede) Marco.sonar(p, "item.armor.equip_iron", 0.8f, 1.0f);
                else Marco.sonidoNo(p);
                repintar(p);
            }, 1L);
            case "gris" -> {
                p.sendMessage(ComandoCalamity.mensaje("Esto aún no está disponible."));
                Marco.sonidoNo(p);
            }
            default -> {
            }
        }
    }

    /**
     * Clic en un trueque. Si no se puede, pasa igual por el Altar (el mensaje, el sonido y el
     * trueque-fallido son suyos). Si se puede y pide confirmar, la pantalla; si no, se compra.
     */
    private void clicTrueque(Player p, Marca m, String id) {
        Altar.Trueque t = altar.trueque(id);
        if (t == null) return;
        if (!t.servicio()) {
            Altar.Plan plan = altar.revisar(p, t);
            if (plan.motivo() == null && pideConfirmar(t, plan.precio(), hc.cfg().getInt("altar.confirmar-desde", 50))) {
                altar.tarea(() -> {
                    Altar.Plan ahora = altar.revisar(p, t);
                    if (ahora.motivo() == null) abrirConfirmar(p, m, t, ahora);
                    else altar.comprar(p, id, null, r -> repintar(p));
                }, 1L);
                return;
            }
        }
        altar.comprar(p, id, null, r -> repintar(p));
        // Recargar no pasa por el motor: se repinta igual.
        altar.tarea(() -> repintar(p), 1L);
    }

    /** El boton de confirmar: se compra (el Altar vuelve a revisarlo todo) y se vuelve a la pagina. */
    private void confirmar(Player p, Marca m) {
        String id = m.trueque();
        String volver = m.volver();
        int hoja = m.hoja();
        if (id == null || volver == null) return;
        altar.comprar(p, id, null, r -> repintar(p));
        altar.tarea(() -> abrir(p, volver, hoja, false), 1L);
    }

    private void cerrar(Player p) {
        altar.tarea(() -> {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Marca) p.closeInventory();
        }, 1L);
    }

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Marca) e.setCancelled(true);
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        ultimoClic.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ autotest

    /**
     * El reparto de los menus (Altar, Tasador y Cazador): nada pisado, todo colocado, cada
     * trueque en su pagina, las hojas y que todos los titulos quepan en la ventana.
     */
    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();

        // Las columnas de una fila: dentro de 1-7, sin repetir y simetricas (c y 8-c).
        for (int n = 1; n <= Marco.COLUMNAS; n++) {
            int[] c = Marco.columnas(n);
            Set<Integer> vistas = new HashSet<>();
            boolean bien = c.length == n;
            for (int x : c) bien &= x >= 1 && x <= Marco.COLUMNAS && vistas.add(x);
            for (int x : c) bien &= vistas.contains(8 - x);
            h.ok("fila de " + n + ": columnas simetricas y sin repetir", bien);
        }

        // Grupos con banda: una banda en la columna 0 de cada fila.
        List<Marco.Sitio> r = Marco.repartir(List.of(4, 3, 3, 5));
        h.igual("4 grupos pequenos: una hoja", 1, Marco.hojas(r));
        h.igual("bandas en 9, 18, 27 y 36", List.of(9, 18, 27, 36), bandas(r, 0));
        h.ok("4+3+3+5: todo colocado una vez", comprobar(r, List.of(4, 3, 3, 5)) == null);
        r = Marco.repartir(List.of(10));
        h.igual("10 cosas: dos filas, una banda en cada una", List.of(9, 18), bandas(r, 0));
        h.ok("10 cosas: todo colocado una vez", comprobar(r, List.of(10)) == null);
        r = Marco.repartir(List.of(7, 7, 7, 7, 1));
        h.igual("5 filas: dos hojas", 2, Marco.hojas(r));
        h.igual("la quinta arriba en la hoja 2", List.of(9), bandas(r, 1));
        h.ok("7+7+7+7+1: todo colocado una vez", comprobar(r, List.of(7, 7, 7, 7, 1)) == null);
        r = Marco.repartir(List.of(7, 7, 7, 10));
        h.igual("la de 10 empieza en la hoja 2, con sus dos bandas", List.of(9, 18), bandas(r, 1));
        h.ok("7+7+7+10: todo colocado una vez", comprobar(r, List.of(7, 7, 7, 10)) == null);
        r = Marco.repartir(List.of(3, 30));
        int nb = 0;
        for (Marco.Sitio s : r) if (s.seccion() == 1 && s.indice() < 0) nb++;
        h.igual("30 cosas: una banda por fila (5)", 5, nb);
        h.ok("3+30: todo colocado una vez", comprobar(r, List.of(3, 30)) == null);
        h.igual("grupos vacios no salen", 0, Marco.repartir(List.of(0, 0)).size());

        // La rejilla de una categoria: cada articulo una vez, dentro, sin pisarse y con aire.
        boolean rejillas = true, aire = true;
        String fallo = null;
        for (int n = 1; n <= 60; n++) {
            List<Marco.Sitio> sr = Marco.rejilla(n);
            String f = comprobar(sr, List.of(n));
            if (f != null && fallo == null) fallo = n + ": " + f;
            rejillas &= f == null;
            if (n <= Marco.FILAS * 4) {
                Set<Integer> puestas = new HashSet<>();
                for (Marco.Sitio s : sr) puestas.add(s.casilla());
                for (int c : puestas) aire &= !puestas.contains(c + 1) || c % 9 == 8;
            }
        }
        h.ok("rejillas de 1 a 60: todo colocado una vez" + (fallo == null ? "" : " (" + fallo + ")"), rejillas);
        h.ok("hasta 16 articulos: ninguno pegado a otro en su fila", aire);
        h.igual("rejilla de 4: una fila en la del medio, con aire", List.of(19, 21, 23, 25), casillas(Marco.rejilla(4)));
        h.igual("rejilla de 3: 20, 22 y 24", List.of(20, 22, 24), casillas(Marco.rejilla(3)));
        h.igual("rejilla de 7: 4 arriba y 3 debajo", List.of(19, 21, 23, 25, 29, 31, 33), casillas(Marco.rejilla(7)));
        h.igual("rejilla de 16: una hoja", 1, Marco.hojas(Marco.rejilla(16)));
        h.igual("rejilla de 40: dos hojas", 2, Marco.hojas(Marco.rejilla(40)));
        h.igual("rejilla de 57: tres hojas", 3, Marco.hojas(Marco.rejilla(57)));

        // Los trueques de serie: cada uno en una sola pagina; depositar y camino, fuera del Altar.
        List<Altar.Trueque> serie = Altar.leer(Altar.DEFECTO);
        for (boolean salvo : List.of(false, true)) {
            String nombre = salvo ? "con salvoconducto" : "sin salvoconducto";
            Map<String, Integer> veces = new HashMap<>();
            for (Categoria c : CATEGORIAS) {
                if (c.id().equals(FORJA)) {
                    for (Seccion s : forja(serie, salvo)) for (Cosa x : s.cosas()) if (x.t() != null) veces.merge(x.t().id(), 1, Integer::sum);
                } else {
                    for (Altar.Trueque t : trueques(c.id(), serie, salvo)) veces.merge(t.id(), 1, Integer::sum);
                }
                List<Marco.Sitio> ss = sitios(c.id(), serie, salvo);
                h.igual(nombre + ": " + c.id() + " en una hoja", 1, Marco.hojas(ss));
                String f = c.id().equals(FORJA) ? comprobar(ss, tamanos(forja(serie, salvo)))
                        : comprobar(ss, List.of(trueques(c.id(), serie, salvo).size()));
                h.ok(nombre + ": " + c.id() + " sin casillas repetidas" + (f == null ? "" : " (" + f + ")"), f == null);
            }
            for (Altar.Trueque t : serie) {
                if (FUERA.contains(t.da())) {
                    h.igual(nombre + ": " + t.id() + " fuera del Altar", 0, veces.getOrDefault(t.id(), 0));
                    continue;
                }
                int debe = salvo || !"salvoconducto".equals(t.objeto()) ? 1 : 0;
                h.igual(nombre + ": " + t.id() + (debe == 1 ? " colocado una vez" : " no sale"), debe, veces.getOrDefault(t.id(), 0));
            }
        }
        List<String> ids = new ArrayList<>();
        for (Categoria c : CATEGORIAS) ids.add(c.id());
        h.igual("tarjetas de la portada", List.of(EXPEDICION, LLAVES, FORJA), ids);
        h.igual("para la expedicion", List.of("recargar", "frasco", "cristal", "tintura", "reclamo", "barometro",
                        "farol-1", "farol-2", "brujula-caida"),
                idsDe(trueques(EXPEDICION, serie, false)));
        // 1.16.0: con los dos Faroles y la Brujula de la Caida son nueve: tres filas de tres, con aire.
        h.igual("expedicion con los Faroles y la Brujula: 11, 13, 15, 20, 22, 24, 29, 31 y 33",
                List.of(11, 13, 15, 20, 22, 24, 29, 31, 33), casillas(sitios(EXPEDICION, serie, false)));
        h.igual("descripcion del Farol I", List.of("Arde 5 min en la mano: tu cordura", "no se mueve y la locura calla."),
                descripcionFarol(300));
        h.igual("descripcion del Farol II", "Arde 12 min en la mano: tu cordura", descripcionFarol(720).get(0));
        // 1.11: con las llaves de las bovedas delante de la del Caos.
        h.igual("llaves y ofrendas", List.of("llave-umbral", "llave-ominosa", "llave", "ofrenda"), idsDe(trueques(LLAVES, serie, false)));
        h.igual("llaves y ofrendas con salvoconducto", List.of("llave-umbral", "llave-ominosa", "llave", "salvoconducto", "ofrenda"),
                idsDe(trueques(LLAVES, serie, true)));
        ids = new ArrayList<>();
        for (Seccion s : forja(serie, false)) ids.add(s.id());
        h.igual("grupos de la Forja", List.of("mejoras", "manto", "vigilante", "amenazas"), ids);
        List<Cosa> mejoras = forja(serie, false).get(0).cosas();
        h.igual("Grabar al final de las mejoras", "grabar", mejoras.get(mejoras.size() - 1).boton());
        // El set del Vigilante: su fila, debajo del Manto; el Vestigio, la Guadana y las Masamune comparten la ultima.
        Map<String, List<String>> filas = new LinkedHashMap<>();
        for (Seccion s : forja(serie, false)) {
            List<String> es = new ArrayList<>();
            for (Cosa x : s.cosas()) if (x.t() != null) es.add(x.t().id());
            filas.put(s.id(), es);
        }
        h.igual("la fila del Vigilante", List.of("yelmo-vigilante", "coraza-vigilante", "grebas-vigilante", "botas-vigilante",
                "mazo-vigilante"), filas.get("vigilante"));
        h.igual("la fila del Eco, la Parca y Ambush", List.of("mascara-eco", "filo-eco", "guadana", "masamune", "crimson-masamune"),
                filas.get("amenazas"));
        List<Marco.Sitio> sf = sitios(FORJA, serie, false);
        h.igual("la Forja con el Vigilante: una hoja, cuatro filas con su banda", List.of(9, 18, 27, 36), bandas(sf, 0));
        var planoLinea = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        h.igual("placas que lleva", "✔ 3 Placas del Vigilante  (las entregas)",
                planoLinea.serialize(entregaLinea(new Altar.Entrega(Entregas.PLACA_DEL_VIGILANTE, 3), 4)));
        h.ok("placas que le faltan", planoLinea.serialize(entregaLinea(new Altar.Entrega(Entregas.PLACA_DEL_VIGILANTE, 3), 1))
                .contains("Placas del Vigilante: llevas 1 de 3"));
        h.igual("por que no, en el icono", "Te faltan 2 Placas del Vigilante.",
                porQueNo(new Altar.Plan(null, null, "objeto", new Altar.Falta(Entregas.PLACA_DEL_VIGILANTE, 1, 3))));
        h.igual("por que no, con una", "Te falta una Placa del Vigilante.",
                porQueNo(new Altar.Plan(null, null, "objeto", new Altar.Falta(Entregas.PLACA_DEL_VIGILANTE, 2, 3))));
        h.igual("pagina que no existe: la portada", UMBRAL, pagina("camino"));

        // Muchos trueques en la config: hojas, y cada uno una vez entre todas.
        List<Map<String, Object>> muchos = new ArrayList<>();
        for (int i = 0; i < 40; i++) muchos.add(Map.of("id", "t" + i, "pagina", "forja", "da", "dar:gema", "esencias", 1));
        List<Seccion> grandes = forja(Altar.leer(muchos), false);
        List<Marco.Sitio> sg = Marco.repartir(tamanos(grandes));
        h.ok("40 trueques en la Forja: mas de una hoja", Marco.hojas(sg) > 1);
        fallo = comprobar(sg, tamanos(grandes));
        h.ok("40 trueques en la Forja: todos colocados una vez" + (fallo == null ? "" : " (" + fallo + ")"), fallo == null);
        muchos = new ArrayList<>();
        for (int i = 0; i < 40; i++) muchos.add(Map.of("id", "u" + i, "pagina", "umbral", "da", "dar:llave", "esencias", 1));
        List<Altar.Trueque> llaves = trueques(LLAVES, Altar.leer(muchos), false);
        h.igual("40 trueques de llaves: en su categoria", 40, llaves.size());
        h.igual("40 trueques de llaves: dos hojas", 2, Marco.hojas(sitios(LLAVES, Altar.leer(muchos), false)));

        // Lo fijo: cada uno en su casilla, fuera del contenido; las tarjetas, en la fila del medio.
        List<Integer> fijas = List.of(Marco.SALDO, Marco.ANTERIOR, Marco.VOLVER, Marco.SIGUIENTE);
        boolean fuera = new HashSet<>(fijas).size() == fijas.size();
        for (int f : fijas) fuera &= f < 9 || f >= 45;
        h.ok("saldo, flechas y volver: casillas propias, fuera del contenido", fuera);
        // 1.7.3: Cerrar y Volver, abajo en el centro en todos los menus.
        h.igual("paginas de 54: Volver abajo en el centro", Marco.abajo(54), Marco.VOLVER);
        h.igual("portada: Cerrar abajo en el centro", Marco.abajo(PORTADA), CERRAR);
        List<Integer> fijasPortada = List.of(Marco.SALDO, CERRAR, AYUDA, IR_TASADOR);
        boolean portada = new HashSet<>(fijasPortada).size() == fijasPortada.size();
        for (int f : fijasPortada) portada &= f < PORTADA && Marco.esBorde(f, PORTADA);
        // 1.12: con el Kit de Expedicion, una tarjeta mas.
        for (int n = 1; n <= CATEGORIAS.size() + 1; n++) {
            for (int c : tarjetas(n)) portada &= c / 9 == 2 && c % 9 >= 1 && c % 9 <= 7 && !fijasPortada.contains(c);
        }
        h.ok("portada: tarjetas en la fila del medio, lo fijo en el marco", portada);
        h.igual("portada: tres tarjetas en 20, 22 y 24", "20,22,24", tarjetas(3)[0] + "," + tarjetas(3)[1] + "," + tarjetas(3)[2]);
        h.igual("portada: con el Kit, cuatro en 19, 21, 23 y 25", List.of(19, 21, 23, 25),
                java.util.Arrays.stream(tarjetas(4)).boxed().toList());

        // 1.12: la tarjeta del Kit de Expedicion, con cada estado.
        var plano = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        Tarjeta kitSi = tarjetaKit(new Kit.Estado(null, 0), 1, 20);
        List<String> lKit = new ArrayList<>();
        for (Component c : kitSi.lore()) lKit.add(plano.serialize(c));
        h.igual("kit: el nombre", "Kit de Expedición", plano.serialize(kitSi.nombre()));
        h.ok("kit: se puede pedir, brilla y lo dice", kitSi.brillo() && "▸ Clic para pedirlo".equals(lKit.get(lKit.size() - 1)));
        h.ok("kit: dice lo que presta y cada cuanto", lKit.contains("8 panes y un Frasco de Calma.")
                && lKit.contains("Uno cada: 20 h") && lKit.contains("salir de Calamity."));
        Tarjeta sinFrasco = tarjetaKit(new Kit.Estado(null, 0), 0, 12.5);
        List<String> lSin = new ArrayList<>();
        for (Component c : sinFrasco.lore()) lSin.add(plano.serialize(c));
        h.ok("kit: sin Frasco y con media hora", lSin.contains("y 8 panes.") && lSin.contains("Uno cada: 12,5 h"));
        Map<String, String> motivos = new LinkedHashMap<>();
        motivos.put("dentro", "✘ Se pide fuera de Calamity.");
        motivos.put("armadura", "✘ Quítate la armadura para pedirlo.");
        motivos.put("espera", "✘ Vuelve en 7 h.");
        for (Map.Entry<String, String> e : motivos.entrySet()) {
            Tarjeta t = tarjetaKit(new Kit.Estado(e.getKey(), 7), 1, 20);
            String ultima = plano.serialize(t.lore().get(t.lore().size() - 1));
            h.ok("kit: " + e.getKey() + " no brilla y dice por que (" + ultima + ")", !t.brillo() && e.getValue().equals(ultima));
        }
        boolean kitCorto = true;
        for (String l : lKit) kitCorto &= l.length() <= 36;
        h.ok("kit: lineas cortas", kitCorto);

        // Confirmar: la Forja siempre; en el Umbral, desde 50 Esencias; con 0, solo la Forja.
        Map<String, Altar.Trueque> ts = new HashMap<>();
        for (Altar.Trueque t : serie) ts.put(t.id(), t);
        Altar.Precio cien = new Altar.Precio(100, 0, null, 0, false);
        h.ok("frasco (10 E) no pide confirmar", !pideConfirmar(ts.get("frasco"), new Altar.Precio(10, 0, null, 0, false), 50));
        h.ok("ofrenda (100 E) pide confirmar", pideConfirmar(ts.get("ofrenda"), cien, 50));
        h.ok("con confirmar-desde 0 la ofrenda no", !pideConfirmar(ts.get("ofrenda"), cien, 0));
        h.ok("la Forja siempre", pideConfirmar(ts.get("gema"), new Altar.Precio(30, 1500, null, 0, false), 0));
        h.ok("recargar nunca", !pideConfirmar(ts.get("recargar"), cien, 1));
        h.igual("credito como linea", "5 Marcas de Eco", creditoLinea("marca", 5));

        // Los titulos de las ventanas: todos caben en el ancho de un cofre.
        h.igual("ancho de 'Altar' en negrita", 30, Marco.ancho("Altar", true));
        h.igual("ancho de 'il.'", 7, Marco.ancho("il.", false));
        h.igual("ancho de la marca 'CALAMITY | '", 64, new Marco.Titulo("").ancho());
        h.igual("texto plano del titulo", "CALAMITY | Mercado", Marco.T_TASADOR.texto());
        h.ok("'Rankings de la semana' no cabe con la marca (la medida lo ve)",
                new Marco.Titulo("Rankings de la semana").ancho() > Marco.ANCHO_TITULO);
        for (Marco.Titulo t : Marco.titulos()) {
            h.ok("titulo '" + t.texto() + "' cabe (" + t.ancho() + " de " + Marco.ANCHO_TITULO + " px)", t.ancho() <= Marco.ANCHO_TITULO);
        }

        MenuTasador.autotest(h);
        MenuContratos.autotest(h);
        MenuCazador.autotest(h);
        return h.lineas();
    }

    private static List<Integer> bandas(List<Marco.Sitio> r, int hoja) {
        List<Integer> out = new ArrayList<>();
        for (Marco.Sitio s : r) if (s.hoja() == hoja && s.indice() < 0) out.add(s.casilla());
        return out;
    }

    private static List<Integer> casillas(List<Marco.Sitio> r) {
        List<Integer> out = new ArrayList<>();
        for (Marco.Sitio s : r) out.add(s.casilla());
        return out;
    }

    private static List<String> idsDe(List<Altar.Trueque> ts) {
        List<String> out = new ArrayList<>();
        for (Altar.Trueque t : ts) out.add(t.id());
        return out;
    }

    /**
     * Null si el reparto esta bien: ninguna casilla dos veces en una hoja, todo en las filas de
     * contenido, bandas en la columna 0 y cosas en las 1-7, y cada cosa de cada seccion una vez.
     */
    static String comprobar(List<Marco.Sitio> r, List<Integer> tamanos) {
        Set<String> casillas = new HashSet<>();
        Map<Integer, Set<Integer>> vistas = new HashMap<>();
        for (Marco.Sitio s : r) {
            if (!casillas.add(s.hoja() + ":" + s.casilla())) return "casilla " + s.casilla() + " repetida en la hoja " + s.hoja();
            int fila = s.casilla() / 9, col = s.casilla() % 9;
            if (fila < 1 || fila > Marco.FILAS) return "casilla " + s.casilla() + " fuera de las filas de contenido";
            if (s.indice() < 0 ? col != 0 : col < 1 || col > Marco.COLUMNAS) return "casilla " + s.casilla() + " en otra columna";
            if (s.indice() >= 0 && !vistas.computeIfAbsent(s.seccion(), k -> new HashSet<>()).add(s.indice())) {
                return "cosa " + s.indice() + " de la seccion " + s.seccion() + " dos veces";
            }
        }
        for (int i = 0; i < tamanos.size(); i++) {
            int n = vistas.getOrDefault(i, Set.of()).size();
            if (n != Math.max(0, tamanos.get(i))) return "seccion " + i + ": " + n + " de " + tamanos.get(i);
        }
        return null;
    }
}
