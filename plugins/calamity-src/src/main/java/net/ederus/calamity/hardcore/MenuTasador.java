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
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * El menu de Oren, el mercader del spawn de Calamity (1.3.0; rehecho en la 1.5.0 y en la rama
 * venta-oren).
 *
 * Rama venta-oren · Dosa: "un NPC que detecte todos los items de Calamity en el inventario y los venda
 * dentro de una interfaz" y "arregla toda su interfaz, que sea como el fish shop, pero con la info
 * necesaria". Salir de Calamity ya no vende nada (Tasacion.alSalir): lo vende Oren. Su portada es su
 * tienda, con la forma de la pescaderia de PremioPescao: arriba, lo que llevas, agrupado por tipo, y un
 * clic vende ese tipo; debajo, "Vender todo" con el resumen de lo que recibes y una confirmacion. Cada
 * tipo dice que es, cuantas llevas, cuanto da cada una y todas (Esencias y MobCoins, con la Racha), lo
 * que te queda del tope de hoy, los extras que puede dar y cuando caduca.
 *
 *   portada (54)   filas 1-2: un boton por tipo (Reliquias de grado alto primero, Esencias al final)
 *                  fila 3:    Topes de hoy (29) · Vender todo (31) · ¿Como funciona? (33)
 *                  fila 4:    Tu dinero (38) · Contratos de hoy (40) · Tu camino (42)
 *                  fila 5:    Altar (47) · Cerrar (49) · Forja (51)
 *   Tu dinero (45)      fila 1: saldo (clic: ingresar) · premios pendientes (clic: recoger) ·
 *                       lo vendido esta semana;  fila 2: primera salida de hoy y Racha
 *   Contratos (45)      fila 1: los tres contratos (clic, en Calamity: recibir su pergamino);
 *                       fila 2: "Cambiar contrato" debajo de cada uno;  fila 3: los de la semana
 *   Vender todo (45)    lo que vendes (13) y lo que recibes (22); No (28-30) y Si (32-34)
 *
 * Reglas de los menus de Calamity (Marco): titulo "CALAMITY | seccion", marco de cristal negro, Cerrar
 * o Volver abajo en el centro, una paleta corta (el color del objeto en su nombre, gris para las
 * etiquetas y blanco para los valores), la ultima linea dice que hace el clic, y solo clic izquierdo
 * (Bedrock: un toque). Oren solo compra fuera de Calamity o en su spawn (Tasacion.puedeVender): si el
 * menu se abriera en otro sitio, los botones de vender lo dicen y no hacen nada.
 */
final class MenuTasador implements Listener {

    private static final long ESPERA_MS = 500;
    /** Las subvistas miden 5 filas; la portada, la tienda, 6. */
    static final int TAMANO = 45;
    static final int TAMANO_PORTADA = 54;
    /** Portada: las dos filas de lo que llevas. */
    static final int FILA_VENTA = 9, MAX_GRUPOS = 14;
    /** Portada, fila 3: los topes, vender todo y la ayuda. */
    static final int TOPES = 29, VENDER_TODO = 31, AYUDA = 33;
    /** Portada, fila 4: las otras secciones de Oren. */
    static final int DINERO = 38, CONTRATOS = 40, CAMINO = 42;
    /** Portada, abajo: el Altar y la Forja a los lados; Cerrar en el centro. */
    static final int IR_ALTAR = 47, CERRAR = 49, IR_FORJA = 51;
    /** Subvistas: Volver abajo en el centro. */
    static final int SALIR = 40;
    /**
     * Las filas de las subvistas: la de arriba (lo principal) y la de debajo (el resumen). En Contratos
     * la de debajo lleva los botones de cambiar y el resumen baja a la tercera (FILA_C).
     */
    static final int FILA_A = 9, FILA_B = 18, FILA_C = 27;

    static final String PORTADA = Marco.TASADOR, V_DINERO = "dinero", V_CONTRATOS = "contratos",
            V_CAMBIAR = "cambiar", V_TODO = "todo";

    /** Nuestra ventana. pantalla: la vista; hueco: el contrato que se cambia (en "cambiar"). */
    record Vista(String pantalla, Map<Integer, String> acciones, int hueco) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    /** Un tramo de la Aduana que aun paga algo: de donde a donde, a cuanto, y lo que queda hoy. */
    record Tramo(double factor, long desde, long hasta, long quedan) {
    }

    private final Hardcore hc;
    private final Map<UUID, Long> ultimoClic = new HashMap<>();
    private final Set<BukkitTask> tareas = new HashSet<>();

    MenuTasador(Hardcore hc) {
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
            hc.seguro("tasador", r);
        });
        tareas.add(t[0]);
    }

    // ------------------------------------------------------------------ abrir y pintar

    /** conSonido: el del NPC al abrirlo; desde un enlace suena el enlace. */
    void abrir(Player p, boolean conSonido) {
        // Lo que lleva con el lore de hoy: las Reliquias viejas decian "se vende sola al salir".
        Reliquias rel = hc.reliquias();
        if (rel != null) hc.seguro("reliquias", () -> rel.renovarInventario(p));
        abrirVista(p, PORTADA);
        if (conSonido) Marco.sonar(p, "item.book.page_turn", 0.8f, 0.8f);
    }

    private static Marco.Titulo titulo(String pantalla) {
        return switch (pantalla) {
            case V_DINERO -> Marco.T_TASADOR_DINERO;
            case V_CONTRATOS -> Marco.T_TASADOR_CONTRATOS;
            case V_TODO -> Marco.T_VENDER_TODO;
            default -> Marco.T_TASADOR;
        };
    }

    private static int tamano(String pantalla) {
        return PORTADA.equals(pantalla) ? TAMANO_PORTADA : TAMANO;
    }

    private void abrirVista(Player p, String pantalla) {
        // Calamity 1.11: dentro de Calamity, con la libreta de otro dia y nada a medias, Oren da la de hoy
        // (Contratos.renovar). Aqui y no en abrir(): los enlaces abren las vistas directamente. Sin libreta
        // caducada es mirar una seccion y sus tres huecos.
        Contratos con = hc.contratos();
        if (con != null && hc.esHardcore(p)) hc.seguro("contratos", () -> con.renovar(p));
        Vista v = new Vista(pantalla, new HashMap<>(), 0);
        Inventory inv = hc.plugin().getServer().createInventory(v, tamano(pantalla), titulo(pantalla).componente());
        pintar(inv, p, v);
        p.openInventory(inv);
    }

    private void repintar(Player p) {
        if (!p.isOnline()) return;
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof Vista v && !v.pantalla().equals(V_CAMBIAR)) pintar(top, p, v);
    }

    private void pintar(Inventory inv, Player p, Vista v) {
        inv.clear();
        v.acciones().clear();
        switch (v.pantalla()) {
            case V_DINERO -> vistaDinero(inv, p, v);
            case V_CONTRATOS -> vistaContratos(inv, p, v);
            case V_TODO -> vistaTodo(inv, p, v);
            default -> portada(inv, p, v);
        }
        if (v.pantalla().equals(PORTADA)) {
            inv.setItem(CERRAR, Marco.cerrar());
            v.acciones().put(CERRAR, "cerrar");
        } else if (!v.pantalla().equals(V_TODO)) {
            inv.setItem(SALIR, Marco.volver("al Mercado"));
            v.acciones().put(SALIR, "volver");
        }
        Marco.rellenar(inv);
    }

    /** Pone las cosas centradas en la fila que empieza en base (columnas de Marco). */
    private static void enFila(Inventory inv, int base, List<ItemStack> cosas, List<String> acciones, Map<Integer, String> mapa) {
        int[] cols = Marco.columnas(Math.min(Marco.COLUMNAS, cosas.size()));
        for (int i = 0; i < cols.length; i++) {
            inv.setItem(base + cols[i], cosas.get(i));
            if (acciones != null && acciones.get(i) != null) mapa.put(base + cols[i], acciones.get(i));
        }
    }

    /**
     * Las casillas de n tipos en las filas de venta, como la rejilla de la pescaderia: de 7 en 7 y la
     * ultima fila centrada (con aire si caben pocos: Marco.columnas). Hasta MAX_GRUPOS.
     */
    static int[] casillasVenta(int n) {
        int total = Math.max(0, Math.min(MAX_GRUPOS, n));
        int[] out = new int[total];
        int k = 0;
        for (int fila = 0; k < total; fila++) {
            int enFila = Math.min(Marco.COLUMNAS, total - k);
            for (int c : Marco.columnas(enFila)) out[k++] = FILA_VENTA + fila * 9 + c;
        }
        return out;
    }

    /** "1 Reliquia", "3 Reliquias". */
    static String cuantas(long n, String una, String varias) {
        return Altar.miles(n) + " " + (n == 1 ? una : varias);
    }

    /** "Etiqueta: valor": la etiqueta en gris y el valor en blanco (la paleta corta de la venta). */
    static Component fila(String etiqueta, String valor) {
        return Component.text(etiqueta + ": ", Paleta.TENUE).append(Component.text(valor, Paleta.TEXTO));
    }

    /** "12 Esencias y 300 MobCoins", "12 Esencias", "300 MobCoins" o "nada". */
    static String paga(long esencias, long mc) {
        String e = esencias > 0 ? Marco.esencias(esencias) : null;
        String m = mc > 0 ? Altar.miles(mc) + " MobCoins" : null;
        if (e != null && m != null) return e + " y " + m;
        if (e != null) return e;
        return m != null ? m : "nada";
    }

    // ------------------------------------------------------------------ portada: la tienda

    private void portada(Inventory inv, Player p, Vista v) {
        Tasacion tas = hc.tasacion();
        Tasacion.Oferta o = tas == null ? null : hc.valor("tasacion", () -> tas.oferta(p), null);
        boolean puede = tas != null && tas.puedeVender(p);
        if (o == null || o.vacia()) {
            inv.setItem(FILA_VENTA + 4, Marco.icono(Material.BUNDLE, Component.text("No llevas nada que Oren compre", Paleta.TENUE),
                    List.of(Marco.tenue("Oren compra las Reliquias y las"), Marco.tenue("Esencias que sacas de Calamity."),
                            Component.empty(), Marco.tenue("Salen de los mobs, los cofres"),
                            Marco.tenue("y los minijefes.")), false));
        } else {
            List<Tasacion.Grupo> grupos = o.grupos();
            int[] casillas = casillasVenta(grupos.size());
            for (int i = 0; i < casillas.length; i++) {
                Tasacion.Grupo g = grupos.get(i);
                boolean ultimoDeMas = i == casillas.length - 1 && grupos.size() > MAX_GRUPOS;
                if (ultimoDeMas) {
                    inv.setItem(casillas[i], Marco.icono(Material.BUNDLE, Component.text("Y " + (grupos.size() - i) + " tipos más",
                            Reliquias.AMBAR), List.of(Marco.tenue("No caben aquí: se venden"), Marco.tenue("con Vender todo.")), false));
                    continue;
                }
                inv.setItem(casillas[i], botonGrupo(g, o, puede));
                // 1.12: si hoy no se lleva ninguna (tope lleno), el clic no hace nada.
                if (puede && g.compra() > 0) v.acciones().put(casillas[i], "vender:" + g.clave());
            }
        }
        inv.setItem(TOPES, topes(p, o));
        botonTodo(inv, o, puede, v);
        inv.setItem(AYUDA, Marco.ayuda(hc));
        botonDinero(inv, p, v);
        botonContratos(inv, p, v);
        botonCamino(inv, p, v);
        boolean altar = Marco.altarAbierto(hc, p);
        Marco.enlace(inv, v.acciones(), IR_ALTAR, Marco.UMBRAL, Material.ENCHANTING_TABLE, "Altar del Umbral",
                List.of("Frascos, Cristales de Regreso,", "Tinturas y la Llave del Caos."), altar);
        Marco.enlace(inv, v.acciones(), IR_FORJA, Marco.FORJA, Material.ANVIL, "La Forja",
                List.of("El Manto, el Vestigio del Eco,", "la Guadaña y sus mejoras."), altar);
    }

    /**
     * Calamity 1.12: cuantas se lleva Oren hoy y cuantas se quedan contigo (las I-II que pasan del tope
     * de hoy no se retiran), y las que no valen nada, que si se lleva sin pagar. Nada si se lo lleva todo
     * y todo vale. Lo usan el boton de cada tipo y el resumen de Vender todo.
     */
    static List<Component> lineasTope(int compra, int quedan, int nulas) {
        List<Component> out = new ArrayList<>();
        if (quedan > 0) {
            out.add(fila("Te compra hoy", Altar.miles(compra)));
            out.add(fila("Se quedan contigo", Altar.miles(quedan)));
            out.add(Component.text(quedan == 1 ? "Pasa del tope de hoy." : "Pasan del tope de hoy.", Paleta.AVISO));
            out.add(Component.text(quedan == 1 ? "Mañana te la compra." : "Mañana te las compra.", Paleta.AVISO));
        }
        if (nulas > 0) {
            out.add(Component.text(nulas == 1 ? "1 caducada, falsa o duplicada:"
                    : Altar.miles(nulas) + " caducadas, falsas o duplicadas:", Paleta.AVISO));
            out.add(Component.text(nulas == 1 ? "Oren se la queda sin pagar." : "Oren se las queda sin pagar.", Paleta.AVISO));
        }
        return out;
    }

    /** Un tipo de lo que llevas: que es, cuantas, cuanto da cada una y todas, el tope, los extras. */
    private ItemStack botonGrupo(Tasacion.Grupo g, Tasacion.Oferta o, boolean puede) {
        List<Component> lore = new ArrayList<>();
        long daE, daMc = 0;
        if (g.esencias()) {
            daE = g.cantidad();
            lore.add(Marco.tenue("Moneda de Calamity"));
            lore.add(Component.empty());
            lore.add(fila("Llevas", Altar.miles(g.cantidad())));
            lore.add(fila("Por unidad", "1 Esencia a tu saldo"));
            lore.add(fila("A tu saldo", Marco.esencias(g.cantidad())));
            lore.add(Component.empty());
            lore.add(Marco.tenue("Con el saldo pagas en el Altar"));
            lore.add(Marco.tenue("y en la Forja. No caduca."));
        } else {
            Tasacion.Valores val = Tasacion.Valores.de(hc.cfg());
            Tasacion.Cuenta k = g.cuenta();
            int gr = g.grado();
            daE = (long) Math.floor(k.esencias * o.factor() + 1e-9);
            daMc = Math.round(k.mc * o.factor());
            lore.add(Marco.tenue((g.especial() == null ? "Reliquia" : "Reliquia especial") + " · Grado " + Reliquias.ROMANO[gr]));
            lore.add(Component.empty());
            lore.add(fila("Llevas", Altar.miles(g.cantidad())));
            lore.add(fila("Por unidad", Ficha.plano(Ficha.valor(val.esencias()[gr], val.mc()[gr]))));
            lore.add(fila("Te da", paga(daE, daMc)));
            if (o.factor() > 1) lore.add(fila("Racha de Codicia", "×" + Tasacion.num(o.factor()).replace('.', ',') + " incluida"));
            if (g.especial() == null && val.apilables().contains(gr) && gr <= 2) {
                int tope = val.topeDia()[gr];
                int ya = gr == 1 ? o.ya1() : o.ya2();
                lore.add(fila("Tope de hoy", "te quedan " + Math.max(0, tope - ya) + " de " + tope));
            }
            lore.addAll(lineasTope(g.compra(), g.quedan(), k.nulas()));
            List<String> extras = Tasacion.extrasTexto(k);
            if (!extras.isEmpty()) {
                lore.add(Component.empty());
                lore.add(Marco.tenue("Además:"));
                for (String x : extras) lore.add(Marco.texto(x));
            }
            if (g.caducaPrimero() > 0) lore.add(fila(g.cantidad() == 1 ? "Caduca" : "La primera caduca", fecha(g.caducaPrimero())));
        }
        lore.add(Component.empty());
        if (!puede) lore.add(Marco.porQueNo("Oren solo compra en el spawn."));
        else if (g.compra() == 0) lore.add(Marco.porQueNo("Hoy ya no compra más de estas."));
        else lore.add(Marco.accion(g.esencias() ? "Clic para ingresarlas" : "Clic para vendérselas a Oren"));
        ItemStack icono = new ItemStack(g.material(), Math.max(1, Math.min(64, g.cantidad())));
        return Marco.icono(icono, Component.text(g.nombre() + " ×" + Altar.miles(g.cantidad()), g.color()), lore,
                puede && (daE > 0 || daMc > 0));
    }

    private String fecha(long millis) {
        Calendario cal = hc.calendario();
        ZoneId zona = cal != null ? cal.zona() : ZoneId.systemDefault();
        return DateTimeFormatter.ofPattern("dd/MM").format(Instant.ofEpochMilli(millis).atZone(zona));
    }

    /** Vender todo: el total, lo que la Aduana pagaria hoy y los extras; el clic abre la confirmacion. */
    private void botonTodo(Inventory inv, Tasacion.Oferta o, boolean puede, Vista v) {
        List<Component> lore = new ArrayList<>();
        if (o == null || o.vacia()) {
            lore.add(Marco.tenue("No llevas nada que Oren compre."));
            inv.setItem(VENDER_TODO, Marco.icono(Material.EMERALD, Component.text("Vender todo", Paleta.TENUE), lore, false));
            return;
        }
        lore.addAll(resumen(o, null));
        lore.add(Component.empty());
        boolean compra = o.compra() > 0;
        if (!puede) lore.add(Marco.porQueNo("Oren solo compra en el spawn."));
        else if (!compra) lore.add(Marco.porQueNo("Hoy ya no te compra nada más."));
        else lore.add(Marco.accion("Clic para ver el resumen"));
        inv.setItem(VENDER_TODO, Marco.icono(Material.EMERALD, Component.text("Vender todo", puede && compra ? Marco.SI : Paleta.TENUE),
                lore, puede && compra));
        if (puede && compra) v.acciones().put(VENDER_TODO, "ver:" + V_TODO);
    }

    /**
     * Lo que recibes vendiendolo todo: Esencias (las de las Reliquias y las que ingresas), MobCoins, la
     * Aduana si recorta, la Racha, los extras y lo que no paga. p: para la Aduana (null = sin mirarla).
     */
    private List<Component> resumen(Tasacion.Oferta o, Player p) {
        List<Component> lore = new ArrayList<>();
        lore.add(fila("Llevas", cuantas(o.piezas(), "objeto que Oren compra", "objetos que Oren compra")));
        lore.addAll(lineasTope(o.compra(), o.quedan(), o.total().nulas()));
        lore.add(Component.empty());
        lore.add(Marco.tenue("Recibes:"));
        long esencias = (long) o.pagaEsencias() + o.esencias();
        lore.add(fila(" Esencias", Altar.miles(esencias) + (o.esencias() > 0 && o.pagaEsencias() > 0
                ? "  (" + Altar.miles(o.esencias()) + " que ya llevas)" : "")));
        lore.add(fila(" MobCoins", Altar.miles(o.pagaMc())));
        Aduana ad = hc.aduana();
        if (p != null && ad != null && o.pagaMc() > 0) {
            long hoy = (long) Math.floor(Aduana.Cuentas.tramos(ad.mcHoy(p.getUniqueId()), o.pagaMc(), ad.tramos()) + 1e-9);
            if (hoy < o.pagaMc()) {
                lore.add(Component.text("Hoy la Aduana solo te paga " + Altar.miles(hoy) + " MobCoins.", Paleta.AVISO));
            }
        }
        if (o.factor() > 1) lore.add(fila(" Racha de Codicia", "×" + Tasacion.num(o.factor()).replace('.', ',') + " incluida"));
        List<String> extras = Tasacion.extrasTexto(o.total());
        if (!extras.isEmpty()) {
            lore.add(Component.empty());
            lore.add(Marco.tenue("Además:"));
            for (String x : extras) lore.add(Marco.texto(x));
        }
        return lore;
    }

    /** Topes de hoy: las Astillas y Fragmentos que aun pagan y la Aduana de las MobCoins. */
    private ItemStack topes(Player p, Tasacion.Oferta o) {
        Tasacion.Valores val = Tasacion.Valores.de(hc.cfg());
        Reliquias rel = hc.reliquias();
        int ya1 = o == null ? 0 : o.ya1(), ya2 = o == null ? 0 : o.ya2();
        if (o == null) {
            Tasacion tas = hc.tasacion();
            if (tas != null) {
                int[] ya = tas.yaHoy(p.getUniqueId());
                ya1 = ya[1];
                ya2 = ya[2];
            }
        }
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.tenue("Lo que Oren aún te paga hoy."));
        lore.add(Component.empty());
        String n1 = rel == null ? "Astilla del Umbral" : rel.nombreDe(1, null, null);
        String n2 = rel == null ? "Fragmento de Nana" : rel.nombreDe(2, null, null);
        lore.add(fila(n1 + " (I)", "te quedan " + Math.max(0, val.topeDia()[1] - ya1) + " de " + val.topeDia()[1]));
        lore.add(fila(n2 + " (II)", "te quedan " + Math.max(0, val.topeDia()[2] - ya2) + " de " + val.topeDia()[2]));
        lore.add(Marco.tenue("Las de grado III y IV no tienen tope."));
        boolean queda = ya1 < val.topeDia()[1] || ya2 < val.topeDia()[2];
        Aduana ad = hc.aduana();
        if (ad != null) {
            long hoy = ad.mcHoy(p.getUniqueId());
            List<Tramo> tramos = tramosHoy(hoy, ad.tramos());
            long escala = escala(tramos);
            lore.add(Component.empty());
            lore.add(Marco.tenue("MobCoins (la Aduana):"));
            lore.add(fila(" Cobradas hoy", Altar.miles(hoy) + (escala > 0 ? " de " + Altar.miles(escala) : "")));
            if (tramos.isEmpty()) lore.add(Component.text(" Hoy ya no te paga más.", Paleta.AVISO));
            for (Tramo t : tramos) {
                String que = t.hasta() >= 100_000 ? "desde " + Altar.miles(t.desde()) + ", sin tope"
                        : t.quedan() > 0 ? "te quedan " + Altar.miles(t.quedan()) : "agotado";
                lore.add(fila(" Al " + Marco.porcentaje(t.factor()), que));
            }
        }
        lore.add(Component.empty());
        lore.add(Marco.tenue("Las Esencias no tienen tope."));
        lore.add(Marco.tenue("Todo vuelve a cero a medianoche."));
        return Marco.icono(Material.CLOCK, Component.text("Topes de hoy", Paleta.DETALLE), lore, queda);
    }

    /** Tu dinero: el saldo, las MobCoins, los Sellos, Marcas y Fragmentos, los premios y la semana. */
    private void botonDinero(Inventory inv, Player p, Vista v) {
        UUID u = p.getUniqueId();
        List<Component> lore = new ArrayList<>();
        Saldo s = hc.saldo();
        lore.add(fila("Saldo", Marco.esencias(s == null ? 0 : s.de(u))));
        Monedero mon = hc.monedero();
        if (mon != null && mon.disponible()) lore.add(fila("MobCoins", Altar.miles(mon.saldo(p))));
        Creditos cr = hc.creditos();
        if (cr != null) {
            int sellos = 0;
            for (Map.Entry<String, Integer> e : cr.todos(u).entrySet()) {
                if (e.getValue() > 0 && (e.getKey().startsWith("sello:") || e.getKey().equals(Creditos.ERRANTE))) sellos += e.getValue();
            }
            int marcas = cr.de(u, "marca"), fragmentos = cr.de(u, "fragmento");
            if (sellos > 0) lore.add(fila("Sellos", String.valueOf(sellos)));
            if (marcas > 0) lore.add(fila("Marcas de Eco", String.valueOf(marcas)));
            if (fragmentos > 0) lore.add(fila("Fragmentos de Guadaña", String.valueOf(fragmentos)));
        }
        int pend = hc.datos().getMapList("premios-pendientes." + u).size();
        lore.add(Component.empty());
        lore.add(pend == 0 ? Marco.tenue("No tienes premios pendientes.")
                : Marco.texto("Tienes " + cuantas(pend, "premio pendiente.", "premios pendientes.")));
        Estadisticas st = hc.estadisticas();
        if (st != null) {
            long e = st.semana(u, "tasado-esencias"), mc = st.semana(u, "tasado-mc");
            lore.add(fila("Vendido esta semana", paga(e, mc)));
        }
        Boolean primera = primeraCobrada(u);
        if (primera != null) lore.add(primera ? Marco.tenue("Ya cobraste la primera salida de hoy.")
                : Marco.tiene("La primera salida de hoy aún paga."));
        lore.add(Component.empty());
        lore.add(Marco.accion("Clic para ver el detalle"));
        Entregas en = hc.entregas();
        boolean recoge = pend > 0 && (en == null ? !hc.esHardcore(p) : en.recibeYa(p));
        inv.setItem(DINERO, Marco.icono(Material.GOLD_INGOT, Component.text("Tu dinero", Paleta.CIFRA), lore, recoge));
        v.acciones().put(DINERO, "ver:" + V_DINERO);
    }

    /** Contratos de hoy: cada uno en una linea con lo que llevas, y los de la semana. */
    private void botonContratos(Inventory inv, Player p, Vista v) {
        UUID u = p.getUniqueId();
        Contratos con = hc.contratos();
        List<Component> lore = new ArrayList<>();
        if (con == null || !hc.valor("contratos", con::activo, false)) {
            lore.add(Marco.tenue("Oren no tiene contratos ahora mismo."));
            inv.setItem(CONTRATOS, Marco.icono(Material.PAPER, Component.text("Contratos de hoy", Paleta.TENUE), lore, false));
            return;
        }
        boolean papel = hc.valor("contratos", con::pergaminoActivo, false);
        if (papel) {
            // 1.10: cada contrato es un pergamino que se lleva dentro y se cobra al cumplirlo.
            lore.add(Marco.texto("Encargos de Oren que cambian"));
            lore.add(Marco.texto("cada día. Lleva su pergamino en"));
            lore.add(Marco.texto("Calamity y se cobra al cumplirlo."));
            lore.add(Marco.tenue("Si mueres, vuelve a empezar."));
        } else {
            lore.add(Marco.texto("Encargos de Oren que cambian"));
            lore.add(Marco.texto("cada día. Se cobran al salir vivo;"));
            lore.add(Marco.texto("si mueres, vuelven a empezar."));
            lore.add(Marco.tenue("Lo cobrado va a tu saldo y se queda."));
        }
        lore.add(Component.empty());
        List<Contratos.Estado> lista = hc.valor("contratos", () -> con.estados(p), List.of());
        // Dentro, un contrato sin su pergamino es algo que hacer ya: el boton brilla.
        Set<Integer> lleva = papel ? hc.valor("contratos", () -> con.llevados(p), Set.<Integer>of()) : Set.of();
        boolean dentro = hc.esHardcore(p);
        boolean listo = false;
        for (Contratos.Estado e : lista) {
            Contratos.Def d = e.def();
            Component l = Component.text("· " + d.texto() + "  ", e.cobrado() ? Paleta.TENUE : Paleta.TEXTO);
            if (e.cobrado()) l = l.append(Component.text("cobrado", Paleta.TENUE));
            else if (e.cumplido()) l = l.append(Component.text("cumplido", Paleta.BIEN));
            else l = l.append(Component.text(Math.min(e.progreso(), d.objetivo()) + "/" + d.objetivo(), Paleta.CIFRA));
            lore.add(l);
            listo |= e.cumplido() && !e.cobrado();
            listo |= papel && dentro && !e.cumplido() && !e.cobrado() && !lleva.contains(e.hueco());
        }
        if (lista.isEmpty()) lore.add(Marco.tenue("Hoy no tienes ninguno."));
        int[] semana = con.semanaDe(u);
        lore.add(fila("Esta semana", Math.min(semana[0], semana[1]) + " de " + semana[1]));
        lore.add(Component.empty());
        lore.add(Marco.accion("Clic para verlos o cambiarlos"));
        inv.setItem(CONTRATOS, Marco.icono(Material.PAPER, Component.text("Contratos de hoy", Paleta.DETALLE), lore, listo));
        v.acciones().put(CONTRATOS, "ver:" + V_CONTRATOS);
    }

    /** Tu camino: las horas activas y el proximo hito; el clic abre el menu del Camino. */
    private void botonCamino(Inventory inv, Player p, Vista v) {
        Altar a = hc.altar();
        Camino cam = a == null ? null : a.camino();
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.texto("Lo que te falta para cada"));
        lore.add(Marco.texto("pieza de la Forja."));
        if (cam != null) {
            double h = cam.horas(p.getUniqueId());
            double hito = cam.proximoHito(h);
            lore.add(Component.empty());
            lore.add(fila("Horas activas", Camino.horasTexto(h)));
            if (hito > 0) lore.add(fila("Próximo hito", Camino.horasTexto(hito).replace(",0", "") + " h"));
            lore.add(Marco.tenue("Solo cuenta el tiempo en que te mueves."));
        }
        lore.add(Component.empty());
        lore.add(cam != null ? Marco.accion("Clic para verlo") : Marco.tenue("Ahora mismo no se puede ver."));
        inv.setItem(CAMINO, Marco.icono(Material.COMPASS, Component.text("Tu camino", cam != null ? Paleta.DETALLE : Paleta.TENUE),
                lore, false));
        if (cam != null) v.acciones().put(CAMINO, "camino");
    }

    // ------------------------------------------------------------------ vender todo: confirmar

    /** Lo que vendes (13) y lo que recibes (22); No a la izquierda y Si a la derecha, como Cambiar contrato. */
    private void vistaTodo(Inventory inv, Player p, Vista v) {
        Tasacion tas = hc.tasacion();
        Tasacion.Oferta o = tas == null ? null : hc.valor("tasacion", () -> tas.oferta(p), null);
        if (o == null || o.vacia()) {
            inv.setItem(13, Marco.icono(Material.BUNDLE, Component.text("Ya no llevas nada que vender", Paleta.TENUE),
                    List.of(Marco.tenue("Vuelve al Mercado.")), false));
        } else {
            List<Component> que = new ArrayList<>();
            int n = 0;
            for (Tasacion.Grupo g : o.grupos()) {
                if (n++ >= 10) {
                    que.add(Marco.tenue("y " + (o.grupos().size() - 10) + " tipos más"));
                    break;
                }
                que.add(lineaTodo(g));
            }
            inv.setItem(13, Marco.icono(Material.CHEST, Component.text("Le vendes a Oren", Paleta.TEXTO), que, false));
            inv.setItem(22, Marco.icono(Material.EMERALD, Component.text("Recibes: ", Paleta.TEXTO)
                    .append(Component.text(paga((long) o.pagaEsencias() + o.esencias(), o.pagaMc()), Marco.SI)), resumen(o, p), true));
        }
        ItemStack no = Marco.icono(Material.RED_CONCRETE, Component.text("No, déjalo", Marco.NO),
                List.of(Marco.tenue("Vuelves al Mercado sin"), Marco.tenue("vender nada."), Component.empty(),
                        Marco.accion("Clic para volver")), false);
        for (int c : new int[]{28, 29, 30}) {
            inv.setItem(c, no);
            v.acciones().put(c, "no-todo");
        }
        if (o != null && !o.vacia() && o.compra() > 0) {
            List<Component> siLore = new ArrayList<>(List.of(Marco.tenue("Oren se queda lo de arriba"),
                    Marco.tenue("y te paga al momento.")));
            if (o.quedan() > 0) {
                siLore.add(Marco.tenue("Lo que pasa del tope de hoy"));
                siLore.add(Marco.tenue("se queda en tu inventario."));
            }
            siLore.add(Component.empty());
            siLore.add(Marco.accion("Clic para vender"));
            ItemStack si = Marco.icono(Material.LIME_CONCRETE, Component.text("Sí, véndeselo todo", Marco.SI), siLore, false);
            // Lo que se ensena va en la accion: si al confirmar ya no es lo mismo, no se vende a ciegas.
            String firma = firma(o);
            for (int c : new int[]{32, 33, 34}) {
                inv.setItem(c, si);
                v.acciones().put(c, "si-todo:" + firma);
            }
        }
    }

    /** "60 × Astilla del Umbral  (te quedas 10)" o, si hoy no se lleva ninguna, "Astilla del Umbral: te quedas 10". */
    private static Component lineaTodo(Tasacion.Grupo g) {
        if (g.compra() == 0) {
            return Component.text(g.nombre(), g.color()).append(Component.text(": te quedas " + Altar.miles(g.quedan()), Paleta.TENUE));
        }
        Component c = Component.text(Altar.miles(g.compra()) + " × ", Paleta.TEXTO).append(Component.text(g.nombre(), g.color()));
        return g.quedan() > 0 ? c.append(Component.text("  (te quedas " + Altar.miles(g.quedan()) + ")", Paleta.TENUE)) : c;
    }

    /**
     * Lo que la confirmacion de "Vender todo" le ha ensenado: cuantas piezas de cada tipo y lo que
     * cobraria. Si al pulsar "Si" ya no coincide (ha recogido algo del suelo, le ha llegado un premio, ha
     * cambiado el tope o la Racha), la venta no se hace y la confirmacion se vuelve a pintar.
     */
    static String firma(Tasacion.Oferta o) {
        StringBuilder sb = new StringBuilder();
        for (Tasacion.Grupo g : o.grupos()) sb.append(g.clave()).append('=').append(g.cantidad()).append(';');
        return sb.append(o.pagaEsencias()).append(';').append(o.pagaMc()).toString();
    }

    // ------------------------------------------------------------------ la Aduana (tramos)

    /**
     * Lo que queda hoy en cada tramo que aun paga (factor > 0). yaHoy: las MC que la Aduana ya
     * le pago hoy (lo mismo que miran los tramos al pagar).
     */
    static List<Tramo> tramosHoy(long yaHoy, List<double[]> tramos) {
        List<Tramo> out = new ArrayList<>();
        double desde = 0;
        for (double[] t : tramos) {
            if (t[1] > 0) out.add(new Tramo(t[1], (long) desde, (long) t[0], (long) Math.max(0, t[0] - Math.max(desde, yaHoy))));
            desde = t[0];
        }
        return out;
    }

    /** Hasta donde paga el dia: el ultimo tramo que paga y no es "sin tope" (>= 100.000); -1 si no hay. */
    static long escala(List<Tramo> tramos) {
        long max = -1;
        for (Tramo t : tramos) if (t.hasta() < 100_000) max = Math.max(max, t.hasta());
        return max;
    }

    // ------------------------------------------------------------------ subvista: tu dinero

    /** null si no hay primera salida (apagada o sin calendario); si no, si ya se cobro hoy. */
    private Boolean primeraCobrada(UUID u) {
        Tasacion.Valores val = Tasacion.Valores.de(hc.cfg());
        Calendario cal = hc.calendario();
        if (val.primeraBase() <= 0 || cal == null) return null;
        return cal.dia().equals(hc.datos().getString("primera-extraccion." + u, ""));
    }

    private void vistaDinero(Inventory inv, Player p, Vista v) {
        UUID u = p.getUniqueId();
        // Fila de arriba: el saldo (clic: ingresar lo fisico), los premios (clic: recogerlos) y la semana.
        Marco.saldo(inv, v.acciones(), hc, p, FILA_A + 2);
        inv.setItem(FILA_A + 4, premios(p, v, FILA_A + 4));
        inv.setItem(FILA_A + 6, semana(u));

        // Fila de abajo: la primera salida de hoy y la Racha, si hay.
        List<ItemStack> abajo = new ArrayList<>();
        Tasacion.Valores val = Tasacion.Valores.de(hc.cfg());
        Boolean cobrada = primeraCobrada(u);
        if (cobrada != null) {
            List<Component> lore = new ArrayList<>();
            if (cobrada) {
                lore.add(Marco.tenue("Ya la cobraste hoy."));
                lore.add(Marco.tenue("Mañana vuelve a pagar."));
            } else {
                lore.add(Marco.tiene(Marco.esencias(val.primeraBase()) + " si sales vivo por el portal"));
                if (val.primeraSiTasa() > 0) {
                    lore.add(Marco.tiene(Marco.esencias(val.primeraSiTasa()) + " más si sales con alguna"));
                    lore.add(Marco.tenue("  Reliquia o ya le vendiste una a Oren."));
                }
                lore.add(Component.empty());
                lore.add(Marco.tenue("Solo paga la primera salida de cada día."));
            }
            abajo.add(Marco.icono(Material.CLOCK, Component.text(cobrada ? "Primera salida: ya cobrada" : "Primera salida: aún paga",
                    cobrada ? Paleta.TENUE : Paleta.BIEN), lore, !cobrada));
        }
        Racha racha = hc.racha();
        if (racha != null && racha.activa()) {
            int r = racha.de(u);
            double porPunto = hc.cfg().getDouble("racha.por-punto", 0.10);
            String por = "×" + Marco.numero(Math.round(Racha.factor(r, racha.tope(null), porPunto) * 100) / 100.0);
            List<Component> rl = new ArrayList<>();
            rl.add(fila("Lo que vendes vale", por));
            rl.add(Component.empty());
            rl.add(Marco.tenue("Sube 1 la primera vez que le vendes"));
            rl.add(Marco.tenue("a Oren una Reliquia de grado II o"));
            rl.add(Marco.tenue("más en cada entrada a Calamity."));
            rl.add(Marco.tenue("Si mueres, vuelve a 0."));
            abajo.add(Marco.icono(Material.BLAZE_POWDER, Component.text("Tu Racha de Codicia: ", Paleta.TEXTO)
                    .append(Component.text(r, Paleta.CIFRA)), rl, r > 0));
        }
        enFila(inv, FILA_B, abajo, null, v.acciones());
    }

    private ItemStack premios(Player p, Vista v, int casilla) {
        UUID u = p.getUniqueId();
        List<Map<?, ?>> pend = hc.datos().getMapList("premios-pendientes." + u);
        // Calamity 1.11: en la zona spawn tambien se recogen (Entregas.recibeYa), como fuera.
        Entregas en = hc.entregas();
        boolean dentro = en == null ? hc.esHardcore(p) : !en.recibeYa(p);
        List<Component> lore = new ArrayList<>();
        if (pend.isEmpty()) {
            lore.add(Marco.tenue("No te espera nada."));
        } else {
            int n = 0;
            for (Map<?, ?> m : pend) {
                if (n++ >= 6) {
                    lore.add(Marco.tenue("y " + (pend.size() - 6) + " más"));
                    break;
                }
                lore.add(Marco.texto("· " + nombrePendiente(m)));
            }
        }
        lore.add(Component.empty());
        lore.add(Marco.tenue("Aquí te espera lo que ganas en"));
        lore.add(Marco.tenue("Calamity o estando desconectado."));
        if (!pend.isEmpty()) {
            lore.add(Component.empty());
            lore.add(dentro ? Marco.tenue("Te llegan en el spawn o al salir de Calamity.") : Marco.accion("Clic para recogerlos"));
            v.acciones().put(casilla, "cobrar");
        }
        return Marco.icono(new ItemStack(Material.CHEST, Math.max(1, Math.min(64, pend.size()))),
                Component.text("Premios pendientes: ", Paleta.TEXTO).append(Component.text(pend.size(), Paleta.CIFRA)),
                lore, !pend.isEmpty() && !dentro);
    }

    private ItemStack semana(UUID u) {
        Estadisticas st = hc.estadisticas();
        List<Component> lore = new ArrayList<>();
        if (st == null) {
            lore.add(Marco.tenue("Ahora mismo no se puede saber."));
        } else {
            long e = st.semana(u, "tasado-esencias"), mc = st.semana(u, "tasado-mc");
            long rel = st.semana(u, "reliquias"), salidas = st.semana(u, "extracciones");
            if (e + mc + rel + salidas == 0) {
                lore.add(Marco.tenue("Esta semana aún no le has"));
                lore.add(Marco.tenue("vendido nada a Oren."));
            } else {
                lore.add(fila("Esencias", Altar.miles(e)));
                lore.add(fila("MobCoins", Altar.miles(mc)));
                lore.add(fila("Reliquias", Altar.miles(rel)));
                lore.add(fila("Salidas con vida", Altar.miles(salidas)));
            }
        }
        lore.add(Component.empty());
        lore.add(Marco.tenue("Lo que le vendes a Oren y las"));
        lore.add(Marco.tenue("veces que sales vivo por el portal."));
        return Marco.icono(Material.WRITABLE_BOOK, Component.text("Vendido esta semana", Paleta.DETALLE), lore, false);
    }

    /** "250 MobCoins", "Frasco de Calma": lo que es un premio pendiente, como se lee. */
    private static String nombrePendiente(Map<?, ?> m) {
        String tipo = String.valueOf(m.get("tipo"));
        String dato = String.valueOf(m.get("dato"));
        if (tipo.equals("mc")) {
            try {
                return Altar.miles(Long.parseLong(dato)) + " MobCoins";
            } catch (NumberFormatException e) {
                return "MobCoins";
            }
        }
        ItemStack it = Entregas.deTexto(dato);
        if (it != null) {
            ItemMeta meta = it.getItemMeta();
            String nombre = meta != null && meta.hasDisplayName()
                    ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName())
                    : String.valueOf(m.get("objeto"));
            return nombre + (it.getAmount() > 1 ? " ×" + it.getAmount() : "");
        }
        return String.valueOf(m.get("objeto"));
    }

    // ------------------------------------------------------------------ subvista: contratos

    /** El icono de un contrato por lo que pide (el evento del pool). */
    private static Material iconoContrato(String evento) {
        return switch (evento) {
            case "mob" -> Material.IRON_SWORD;
            case "destacado" -> Material.GOLDEN_SWORD;
            case "minijefe" -> Material.WITHER_SKELETON_SKULL;
            case "cofre" -> Material.CHEST_MINECART;
            case "reliquia-ii", "tasa-ii" -> Material.MANGROVE_PROPAGULE;
            case "minutos" -> Material.CLOCK;
            case "minutos-limite" -> Material.SOUL_LANTERN;
            case "minutos-sin-frasco" -> Material.GLASS_BOTTLE;
            case "eco-valido" -> Material.ECHO_SHARD;
            case "redimir" -> Material.AMETHYST_SHARD;
            default -> Material.PAPER;
        };
    }

    private void vistaContratos(Inventory inv, Player p, Vista v) {
        UUID u = p.getUniqueId();
        Contratos con = hc.contratos();
        if (con == null || !hc.valor("contratos", con::activo, false)) {
            inv.setItem(FILA_A + 4, Marco.icono(Material.PAPER, Component.text("Oren no tiene contratos ahora", Paleta.TENUE),
                    List.of(Marco.tenue("Vuelve más adelante.")), false));
            return;
        }
        List<Contratos.Estado> lista = hc.valor("contratos", () -> con.estados(p), List.of());
        int precio = con.precioCambio(u), gratis = con.cambiosGratis(u);
        boolean papel = hc.valor("contratos", con::pergaminoActivo, false);
        boolean dentro = hc.esHardcore(p);
        Set<Integer> lleva = papel ? hc.valor("contratos", () -> con.llevados(p), Set.<Integer>of()) : Set.of();
        int n = Math.min(Marco.COLUMNAS, lista.size());
        int[] cols = Marco.columnas(n);
        for (int k = 0; k < n; k++) {
            Contratos.Estado e = lista.get(k);
            Contratos.Def d = e.def();
            boolean pendiente = !e.cumplido() && !e.cobrado();
            List<Component> lore = new ArrayList<>();
            lore.add(Marco.barra(e.progreso(), d.objetivo()));
            lore.add(fila("Paga", Contratos.premio(d)));
            if (d.corto()) lore.add(Marco.tenue("Es corto: se hace en una entrada rápida."));
            lore.add(Marco.tenue(!papel ? "Se cobra al salir vivo." : Contratos.seCobraAlSalir(d) ? Pergaminos.COBRO_VENTA
                    : Pergaminos.COBRO_DENTRO));
            lore.add(Component.empty());
            // La ultima linea: lo que hace el clic o por que no hace nada.
            String accion = null;
            if (e.cobrado()) {
                lore.add(Marco.tiene("Ya lo cobraste."));
            } else if (e.cumplido()) {
                lore.add(Component.text("Cumplido: lo cobras al salir vivo.", Paleta.CIFRA));
            } else if (!papel) {
                lore.add(Marco.tenue("Si mueres, vuelve a empezar."));
            } else if (!dentro) {
                lore.add(Marco.tenue("Los pergaminos se entregan en Calamity."));
            } else if (lleva.contains(e.hueco())) {
                lore.add(Marco.tiene("Llevas su pergamino."));
            } else {
                lore.add(Marco.accion("Clic para recibir su pergamino"));
                accion = "p:" + e.hueco();
            }
            TextColor color = e.cobrado() ? Paleta.TENUE : e.cumplido() ? Paleta.BIEN : Paleta.TEXTO;
            Material icono = e.cobrado() ? Material.MAP : iconoContrato(d.evento());
            inv.setItem(FILA_A + cols[k], Marco.icono(icono, Component.text(d.texto(), color), lore,
                    (e.cumplido() && !e.cobrado()) || accion != null));
            if (accion != null) v.acciones().put(FILA_A + cols[k], accion);
            // Cambiar va aparte, justo debajo: un toque en el contrato nunca lo cambia sin querer.
            if (pendiente) {
                inv.setItem(FILA_B + cols[k], Marco.icono(Material.FEATHER, Component.text("Cambiar contrato", Paleta.DETALLE),
                        List.of(Marco.tenue("Oren te da otro encargo"), Marco.tenue("en su lugar. Pierdes lo que"),
                                Marco.tenue("llevas hecho de este."), Component.empty(),
                                Marco.accion(precio == 0 ? "Clic para cambiarlo (gratis)"
                                        : "Clic para cambiarlo (" + Marco.esencias(precio) + ")")), false));
                v.acciones().put(FILA_B + cols[k], "c:" + e.hueco());
            }
        }
        if (lista.isEmpty()) {
            inv.setItem(FILA_A + 4, Marco.icono(Material.PAPER, Component.text("Hoy no tienes contratos", Paleta.TENUE),
                    List.of(Marco.tenue("Vuelve a mirar cuando salgas de Calamity.")), false));
        }

        int[] semana = con.semanaDe(u);
        List<Component> sl = new ArrayList<>();
        sl.add(Marco.barra(semana[0], semana[1]));
        sl.add(Marco.texto("Si cobras " + semana[1] + " en la semana,"));
        sl.add(Marco.texto("Oren te da la Llave del Caos."));
        sl.add(Component.empty());
        sl.add(Marco.tenue(gratis > 0 ? "Hoy te " + (gratis == 1 ? "queda 1 cambio gratis." : "quedan " + gratis + " cambios gratis.")
                : "Cambiar uno cuesta " + Marco.esencias(precio) + "."));
        inv.setItem(FILA_C + 4, Marco.icono(Material.TRIAL_KEY, Component.text("Contratos de la semana: ", Paleta.TEXTO)
                .append(Component.text(Math.min(semana[0], semana[1]) + "/" + semana[1], Paleta.CIFRA)), sl, semana[0] >= semana[1]));
    }

    // ------------------------------------------------------------------ cambiar un contrato

    /** Confirmar el cambio de un contrato: dice que se pierde, si es gratis o lo que cuesta. */
    private void abrirCambiar(Player p, int hueco) {
        Contratos con = hc.contratos();
        if (con == null) return;
        Contratos.Estado e = null;
        for (Contratos.Estado x : hc.valor("contratos", () -> con.estados(p), List.<Contratos.Estado>of())) {
            if (x.hueco() == hueco) e = x;
        }
        if (e == null || e.cumplido() || e.cobrado()) {
            abrirVista(p, V_CONTRATOS);
            return;
        }
        Vista v = new Vista(V_CAMBIAR, new HashMap<>(), hueco);
        Inventory inv = hc.plugin().getServer().createInventory(v, TAMANO, Marco.T_CAMBIAR.componente());
        int precio = con.precioCambio(p.getUniqueId());
        inv.setItem(13, Marco.icono(iconoContrato(e.def().evento()), Component.text(e.def().texto(), Paleta.TEXTO),
                List.of(Marco.barra(e.progreso(), e.def().objetivo()), Marco.tenue("Si lo cambias, pierdes lo que llevas hecho.")), false));
        Saldo s = hc.saldo();
        long saldo = s == null ? 0 : s.de(p.getUniqueId());
        inv.setItem(22, precio == 0
                ? Marco.icono(Material.LIME_DYE, Component.text("Gratis", Paleta.BIEN), List.of(Marco.tenue("Es tu cambio gratis de hoy.")), false)
                : Marco.icono(Material.GHAST_TEAR, Component.text("Cuesta " + Marco.esencias(precio), Paleta.CIFRA),
                List.of(fila("Tienes", Altar.miles(saldo)), fila("Te quedarían", Altar.miles(Math.max(0, saldo - precio)))), false));
        ItemStack si = Marco.icono(Material.LIME_CONCRETE, Component.text("Sí, cámbialo", Marco.SI), List.of(
                Marco.tenue("Oren te da otro encargo"), Marco.tenue("en su lugar."), Component.empty(),
                Marco.accion("Clic para cambiarlo")), false);
        ItemStack no = Marco.icono(Material.RED_CONCRETE, Component.text("No, déjalo", Marco.NO),
                List.of(Marco.tenue("Vuelves a tus contratos sin"), Marco.tenue("cambiar nada."), Component.empty(),
                        Marco.accion("Clic para volver")), false);
        for (int c : new int[]{28, 29, 30}) {
            inv.setItem(c, no);
            v.acciones().put(c, "no");
        }
        for (int c : new int[]{32, 33, 34}) {
            inv.setItem(c, si);
            v.acciones().put(c, "si");
        }
        Marco.rellenar(inv);
        p.openInventory(inv);
        Marco.sonar(p, "block.note_block.hat", 0.5f, 1.3f);
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
        hc.seguro("tasador", () -> accion(p, v, accion));
    }

    /** Vende un tipo (clave) o todo (null) y vuelve a pintar la tienda con lo que queda. */
    private void vender(Player p, String clave) {
        Tasacion tas = hc.tasacion();
        if (tas == null) {
            Marco.sonidoNo(p);
            return;
        }
        Tasacion.Resumen r = hc.valor("tasacion", () -> tas.vender(p, clave), null);
        if (r == null) Marco.sonidoNo(p);
        else Marco.sonar(p, "entity.experience_orb.pickup", 0.7f, 1.1f);
        tarea(() -> abrirVista(p, PORTADA));
    }

    private void accion(Player p, Vista v, String accion) {
        if (accion.startsWith("vender:")) {
            vender(p, accion.substring(7));
            return;
        }
        if (accion.startsWith("si-todo:")) {
            Tasacion tas = hc.tasacion();
            Tasacion.Oferta o = tas == null ? null : hc.valor("tasacion", () -> tas.oferta(p), null);
            if (o == null || o.vacia() || !firma(o).equals(accion.substring(8))) {
                p.sendMessage(ComandoCalamity.mensaje("Lo que llevas ha cambiado: revisa el resumen antes de vender."));
                Marco.sonidoNo(p);
                tarea(() -> abrirVista(p, V_TODO));
                return;
            }
            vender(p, null);
            return;
        }
        if (accion.startsWith("tab:")) {
            String a = accion.substring(4);
            tarea(() -> Marco.irA(hc, p, a));
            return;
        }
        if (accion.startsWith("no-tab:")) {
            Marco.cerrado(p, accion.substring(7));
            return;
        }
        if (accion.startsWith("ver:")) {
            String vista = accion.substring(4);
            Marco.sonidoPestana(p);
            tarea(() -> abrirVista(p, vista));
            return;
        }
        if (accion.startsWith("c:")) {
            int hueco = Integer.parseInt(accion.substring(2));
            tarea(() -> abrirCambiar(p, hueco));
            return;
        }
        if (accion.startsWith("p:")) {
            // 1.10: el pergamino de ese contrato. Si no se puede (lleno, fuera...), se le dice por que.
            int hueco = Integer.parseInt(accion.substring(2));
            Contratos con = hc.contratos();
            if (con == null) return;
            // valor() cambia un null por el defecto: "" es "dado" y el defecto, un fallo del modulo.
            String no = hc.valor("contratos", () -> {
                String r = con.darDesdeMenu(p, hueco);
                return r == null ? "" : r;
            }, "Ahora mismo Oren no puede darte el pergamino.");
            if (no.isEmpty()) {
                Marco.sonar(p, "item.book.page_turn", 0.8f, 1.0f);
            } else {
                p.sendMessage(ComandoCalamity.mensaje(no));
                Marco.sonidoNo(p);
            }
            tarea(() -> repintar(p));
            return;
        }
        switch (accion) {
            case "cerrar" -> tarea(() -> {
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof Vista) p.closeInventory();
            });
            case "volver", "no-todo" -> {
                Marco.sonar(p, "ui.button.click", 0.45f, 0.8f);
                tarea(() -> abrirVista(p, PORTADA));
            }
            case "depositar" -> {
                // El boton Depositar del Altar vive en el saldo: lo mismo, y solo fuera de Calamity.
                Altar altar = hc.altar();
                if (altar == null) return;
                if (hc.esHardcore(p)) {
                    p.sendMessage(ComandoCalamity.mensaje("Aquí no: ingrésalas con el botón de las Esencias en la tienda de Oren."));
                    Marco.sonidoNo(p);
                    return;
                }
                altar.depositar(p);
                tarea(() -> repintar(p));
            }
            case "camino" -> tarea(() -> Marco.irA(hc, p, Marco.CAMINO));
            case "cobrar" -> {
                Entregas en = hc.entregas();
                if (en == null) return;
                // 1.11: en la zona spawn si (recibeYa), como el Altar.
                if (!en.recibeYa(p)) {
                    p.sendMessage(ComandoCalamity.mensaje("Aquí no se puede: te llegan en el spawn o al salir de Calamity."));
                    Marco.sonidoNo(p);
                    return;
                }
                en.pendientes(p);
                Marco.sonar(p, "entity.item.pickup", 0.8f, 1.0f);
                tarea(() -> repintar(p));
            }
            case "si" -> {
                Contratos con = hc.contratos();
                if (con != null && con.cambiar(p, v.hueco())) Marco.sonar(p, "item.book.page_turn", 0.8f, 1.2f);
                else Marco.sonidoNo(p);
                tarea(() -> abrirVista(p, V_CONTRATOS));
            }
            case "no" -> {
                Marco.sonar(p, "ui.button.click", 0.45f, 0.8f);
                tarea(() -> abrirVista(p, V_CONTRATOS));
            }
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

    private static List<String> plano(List<Component> lineas) {
        List<String> out = new ArrayList<>();
        for (Component c : lineas) out.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c));
        return out;
    }

    static void autotest(Autotest.Hoja h) {
        List<double[]> serie = List.of(new double[]{1500, 1.0}, new double[]{999999, 0.0});
        List<Tramo> t = tramosHoy(300, serie);
        h.igual("aduana de serie: un tramo que paga", 1, t.size());
        h.igual("con 300 cobradas quedan 1.200 al 100 %", 1200L, t.get(0).quedan());
        h.igual("la barra llega a 1.500", 1500L, escala(t));
        t = tramosHoy(2000, serie);
        h.igual("pasado el tope no queda nada", 0L, t.get(0).quedan());

        List<double[]> tres = List.of(new double[]{1000, 1.0}, new double[]{2000, 0.5}, new double[]{3000, 0.25},
                new double[]{999999, 0.0});
        t = tramosHoy(1500, tres);
        h.igual("tres tramos: los tres salen", 3, t.size());
        h.igual("al 100 % agotado", 0L, t.get(0).quedan());
        h.igual("al 50 % quedan 500", 500L, t.get(1).quedan());
        h.igual("al 25 % quedan 1.000 enteros", 1000L, t.get(2).quedan());
        h.igual("la barra llega a 3.000", 3000L, escala(t));
        h.igual("sin tope: escala -1", -1L, escala(tramosHoy(0, List.of(new double[]{999999, 1.0}))));

        // Rama venta-oren: la portada es la tienda (54): dos filas de tipos, la de vender y la de secciones.
        h.igual("tienda: un tipo va en el centro de la fila 1", 13, casillasVenta(1)[0]);
        h.igual("tienda: tres tipos con aire (columnas 2, 4 y 6)", "11,13,15",
                casillasVenta(3)[0] + "," + casillasVenta(3)[1] + "," + casillasVenta(3)[2]);
        int[] ocho = casillasVenta(8);
        h.ok("tienda: con 8, siete arriba y el octavo centrado debajo", ocho.length == 8 && ocho[6] == 16 && ocho[7] == 22);
        h.igual("tienda: como mucho 14 tipos", MAX_GRUPOS, casillasVenta(40).length);
        boolean dentroFilas = true;
        for (int c : casillasVenta(MAX_GRUPOS)) dentroFilas &= c / 9 >= 1 && c / 9 <= 2 && c % 9 >= 1 && c % 9 <= 7;
        h.ok("tienda: los tipos, en las filas 1 y 2 y lejos de los bordes", dentroFilas);
        h.ok("tienda: Vender todo en el centro de la fila 3", VENDER_TODO / 9 == 3 && VENDER_TODO % 9 == 4);
        h.ok("tienda: topes y ayuda a sus lados", TOPES == VENDER_TODO - 2 && AYUDA == VENDER_TODO + 2);
        h.ok("tienda: secciones en la fila 4 (columnas 2, 4 y 6)", DINERO == 38 && CONTRATOS == 40 && CAMINO == 42);
        h.ok("tienda: Cerrar abajo en el centro", CERRAR == Marco.abajo(TAMANO_PORTADA));
        h.ok("tienda: Altar y Forja a los lados de Cerrar", IR_ALTAR == CERRAR - 2 && IR_FORJA == CERRAR + 2);
        List<Integer> todas = new ArrayList<>(List.of(TOPES, VENDER_TODO, AYUDA, DINERO, CONTRATOS, CAMINO, IR_ALTAR, CERRAR, IR_FORJA));
        for (int c : casillasVenta(MAX_GRUPOS)) todas.add(c);
        boolean bien = new HashSet<>(todas).size() == todas.size();
        for (int c : todas) bien &= c >= 0 && c < TAMANO_PORTADA;
        h.ok("tienda: ninguna casilla repetida ni fuera de la ventana", bien);

        // Las subvistas: Volver abajo en el centro y sus filas no lo pisan.
        h.ok("subvistas: Volver abajo en el centro", SALIR == Marco.abajo(TAMANO));
        h.ok("subvistas del mercado: filas 1 y 2", FILA_A == 9 && FILA_B == 18 && FILA_B + 8 < SALIR);
        // 1.10: en Contratos, Cambiar debajo de cada contrato (misma columna) y la semana en la fila 3.
        h.ok("contratos: Cambiar justo debajo y la semana en la fila 3, encima de Volver",
                FILA_B == FILA_A + 9 && FILA_C == FILA_B + 9 && FILA_C / 9 == SALIR / 9 - 1 && (FILA_C + 4) % 9 == SALIR % 9);
        h.igual("cuantas: singular", "1 Reliquia", cuantas(1, "Reliquia", "Reliquias"));
        h.igual("cuantas: plural con miles", "1.250 Reliquias", cuantas(1250, "Reliquia", "Reliquias"));
        h.igual("paga: los dos", "12 Esencias y 300 MobCoins", paga(12, 300));
        h.igual("paga: una Esencia", "1 Esencia", paga(1, 0));
        h.igual("paga: nada", "nada", paga(0, 0));

        // Calamity 1.12: lo que Oren compra hoy y lo que se queda contigo.
        h.igual("tope: sin exceso ni falsas, ninguna linea", List.of(), plano(lineasTope(12, 0, 0)));
        List<String> tope = plano(lineasTope(60, 10, 0));
        h.ok("tope: dice cuantas compra hoy", tope.contains("Te compra hoy: 60"));
        h.ok("tope: dice cuantas se quedan contigo", tope.contains("Se quedan contigo: 10"));
        h.ok("tope: y que mañana las compra", tope.contains("Pasan del tope de hoy.") && tope.contains("Mañana te las compra."));
        h.ok("tope: en singular", plano(lineasTope(29, 1, 0)).contains("Mañana te la compra."));
        List<String> nulas = plano(lineasTope(3, 0, 2));
        h.ok("sin valor: dice que Oren se las queda sin pagar", nulas.contains("2 caducadas, falsas o duplicadas:")
                && nulas.contains("Oren se las queda sin pagar."));
        h.ok("sin valor: en singular", plano(lineasTope(1, 0, 1)).contains("Oren se la queda sin pagar."));
        boolean cortas = true;
        for (String l : plano(lineasTope(1_250, 1_250, 1_250))) cortas &= l.length() <= 38;
        h.ok("tope: las lineas caben en un lore", cortas);
        for (String vista : List.of(PORTADA, V_DINERO, V_CONTRATOS, V_TODO)) {
            h.ok("titulo de la vista '" + vista + "' cabe", titulo(vista).ancho() <= Marco.ANCHO_TITULO);
        }
    }
}
