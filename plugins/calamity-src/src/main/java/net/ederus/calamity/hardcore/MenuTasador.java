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
 * El menu de Oren, el mercader del spawn de Calamity (1.3.0; rehecho en la 1.5.0, en la rama
 * venta-oren y en la 1.12.1).
 *
 * Calamity 1.12.1 · Dosa vio la tienda de 6 filas y dijo: "Esta interfaz es terrible, todo desordenado.
 * Minimiza, es muy molesto ver tantas cosas". Ahora todas las vistas miden 3 filas, con el marco de
 * cristal negro y nada suelto:
 *
 *   portada (27)       fila 1: los tipos que llevas, centrados y en orden (grado I a IV, especiales,
 *                      Esencias); un clic vende ese tipo. Si no caben (mas de 7), los de mas valor y
 *                      "Y N tipos mas". Sin nada, un papel en el centro.
 *                      fila 2: Contratos (20) · Vender todo (22, con el resumen y los topes de hoy) ·
 *                      Tu dinero (24)
 *   Vender todo (27)   No (11) · lo que vendes y lo que recibes (13) · Si (15)
 *   Tu dinero (27)     fila 1: saldo, premios, lo vendido, primera salida, Racha y Tu camino; Volver (22)
 *   Contratos (27)     fila 1: los contratos de hoy (un clic acepta una oferta o pide otra copia del
 *                      pergamino perdido); fila 2: la semana (20) · Volver (22) · Cambiar uno (24)
 *   Cambiar (27)       elegir: los que se pueden cambiar en la fila 1 y Volver; confirmar: No (11) ·
 *                      el contrato y lo que cuesta (13) · Si (15)
 *
 * Fuera quedan el Altar, la Forja, la ayuda, Tu camino y Cerrar como botones sueltos: el Altar y la Forja
 * tienen sus NPCs, Tu camino va en Tu dinero y la ventana se cierra con Esc.
 *
 * Reglas de los menus de Calamity (Marco): titulo "CALAMITY | seccion", marco de cristal negro, Volver
 * abajo en el centro, una paleta corta, la ultima linea dice que hace el clic, y solo clic izquierdo
 * (Bedrock: un toque). Oren solo compra fuera de Calamity o en su spawn (Tasacion.puedeVender): si el
 * menu se abriera en otro sitio, los botones de vender lo dicen y no hacen nada.
 */
final class MenuTasador implements Listener {

    private static final long ESPERA_MS = 500;
    /** Todas las vistas: 3 filas. */
    static final int TAMANO = 27;
    /** La fila del medio: lo que se ensena (tipos, contratos, tu dinero). */
    static final int FILA = 9;
    /** Como mucho, tipos en la fila: las 7 columnas de dentro del marco. */
    static final int MAX_TIPOS = Marco.COLUMNAS;
    /** Portada, abajo: Contratos a la izquierda, Vender todo en el centro y Tu dinero a la derecha. */
    static final int CONTRATOS = 20, VENDER_TODO = 22, DINERO = 24;
    /** Subvistas: Volver abajo en el centro (Marco.abajo). */
    static final int SALIR = 22;
    /** Contratos, abajo: la semana y Cambiar a los lados de Volver. */
    static final int SEMANA = 20, CAMBIAR = 24;
    /** Confirmaciones: No, lo que se confirma y Si, en la fila del medio. */
    static final int NO = 11, CENTRO = 13, SI = 15;

    static final String PORTADA = Marco.TASADOR, V_DINERO = "dinero", V_CONTRATOS = "contratos",
            V_ELEGIR = "elegir", V_CAMBIAR = "cambiar", V_TODO = "todo";

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
            case V_ELEGIR, V_CAMBIAR -> Marco.T_CAMBIAR;
            case V_TODO -> Marco.T_VENDER_TODO;
            default -> Marco.T_TASADOR;
        };
    }

    private static int tamano(String pantalla) {
        return TAMANO;
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
            case V_ELEGIR -> vistaElegir(inv, p, v);
            case V_TODO -> vistaTodo(inv, p, v);
            default -> portada(inv, p, v);
        }
        // 1.12.1: sin Cerrar en la portada (Esc cierra); Volver en las subvistas que no confirman nada.
        if (v.pantalla().equals(V_DINERO) || v.pantalla().equals(V_CONTRATOS)) {
            inv.setItem(SALIR, Marco.volver("al Mercado"));
            v.acciones().put(SALIR, "volver");
        } else if (v.pantalla().equals(V_ELEGIR)) {
            inv.setItem(SALIR, Marco.volver("a tus contratos"));
            v.acciones().put(SALIR, "volver-contratos");
        }
        Marco.rellenar(inv);
    }

    /** Las casillas de n cosas en la fila del medio, centradas (columnas de Marco). */
    static int[] casillasFila(int n) {
        int[] cols = Marco.columnas(Math.min(Marco.COLUMNAS, Math.max(0, n)));
        int[] out = new int[cols.length];
        for (int i = 0; i < cols.length; i++) out[i] = FILA + cols[i];
        return out;
    }

    /**
     * Calamity 1.12.1 · Un tipo de lo que llevas, reducido a lo que decide donde va: su grado, su especial,
     * si son las Esencias y lo que vale (en Esencias, con las MobCoins como desempate).
     */
    record Tipo(int grado, String especial, boolean esencias, double valor, long mc) {
    }

    /** El orden de la fila: grado I a IV, luego las especiales (Campana, Lagrima, Sello, Eclipsada), luego las Esencias. */
    static int orden(Tipo t) {
        if (t.esencias()) return 100;
        if (t.especial() != null) {
            int i = List.of(Reliquias.CAMPANA, Reliquias.LAGRIMA, Reliquias.SELLO, Reliquias.ECLIPSADA).indexOf(t.especial());
            return 10 + (i < 0 ? 9 : i) * 5 + t.grado();
        }
        return t.grado();
    }

    /**
     * Que tipos salen en la fila y en que orden (indices de "tipos"). Si caben todos (7), todos, en orden. Si
     * no, los de mas valor (los 6 primeros: la septima casilla dice cuantos faltan), en orden. Puro.
     */
    static List<Integer> visibles(List<Tipo> tipos) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < tipos.size(); i++) idx.add(i);
        if (tipos.size() > MAX_TIPOS) {
            idx.sort((a, b) -> {
                int c = Double.compare(tipos.get(b).valor(), tipos.get(a).valor());
                return c != 0 ? c : Long.compare(tipos.get(b).mc(), tipos.get(a).mc());
            });
            idx = new ArrayList<>(idx.subList(0, MAX_TIPOS - 1));
        }
        idx.sort((a, b) -> {
            int c = Integer.compare(orden(tipos.get(a)), orden(tipos.get(b)));
            return c != 0 ? c : Integer.compare(a, b);
        });
        return idx;
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
        Tasacion.Oferta o = null;
        boolean fallo = false;
        if (tas != null) {
            try {
                o = tas.oferta(p);
            } catch (Throwable t) {
                // 1.12.1: antes un fallo aqui se veia como "No llevas nada que Oren compre" (Dosa, con seis
                // Reliquias encima). Ahora se dice y queda en el log.
                fallo = true;
                hc.plugin().getLogger().log(java.util.logging.Level.WARNING,
                        "[Calamity] Oren no pudo mirar el inventario de " + p.getName(), t);
            }
        }
        boolean puede = tas != null && tas.puedeVender(p);
        if (fallo || tas == null) {
            inv.setItem(CENTRO, Marco.icono(Material.BARRIER, Component.text("Oren no puede tasar ahora", Paleta.AVISO),
                    List.of(Marco.tenue("No ha podido mirar lo que llevas."), Marco.tenue("Avisa al staff.")), false));
        } else if (o == null || o.vacia()) {
            boolean sinCompra = tas.reliquiasEncima(p).size() > 0;
            inv.setItem(CENTRO, Marco.icono(Material.PAPER, Component.text(sinCompra ? "Oren no compra Reliquias ahora"
                            : "No llevas nada que Oren compre", Paleta.TENUE),
                    sinCompra ? List.of(Marco.tenue("Las Reliquias están apagadas."), Marco.tenue("Guárdalas para más tarde."))
                            : List.of(Marco.tenue("Oren compra las Reliquias y las"), Marco.tenue("Esencias que sacas de Calamity.")),
                    false));
        } else {
            List<Tasacion.Grupo> grupos = o.grupos();
            List<Tipo> tipos = new ArrayList<>();
            for (Tasacion.Grupo g : grupos) {
                Tasacion.Cuenta k = g.cuenta();
                tipos.add(new Tipo(g.grado(), g.especial(), g.esencias(), k == null ? g.cantidad() : k.esencias, k == null ? 0 : k.mc));
            }
            List<Integer> ver = visibles(tipos);
            boolean faltan = ver.size() < grupos.size();
            int[] casillas = casillasFila(ver.size() + (faltan ? 1 : 0));
            for (int i = 0; i < ver.size(); i++) {
                Tasacion.Grupo g = grupos.get(ver.get(i));
                inv.setItem(casillas[i], botonGrupo(g, o, puede));
                // 1.12: si hoy no se lleva ninguna (tope lleno), el clic no hace nada.
                if (puede && g.compra() > 0) v.acciones().put(casillas[i], "vender:" + g.clave());
            }
            if (faltan) {
                inv.setItem(casillas[casillas.length - 1], Marco.icono(Material.BUNDLE, Component.text("Y "
                                + (grupos.size() - ver.size()) + " tipos más", Paleta.TEXTO),
                        List.of(Marco.tenue("No caben aquí: se venden"), Marco.tenue("con Vender todo.")), false));
            }
        }
        botonContratos(inv, p, v);
        botonTodo(inv, p, o, puede, v);
        botonDinero(inv, p, v);
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
        ItemStack boton = Marco.icono(icono, Component.text(g.nombre() + " ×" + Altar.miles(g.cantidad()), g.color()), lore,
                puede && (daE > 0 || daMc > 0));
        // 1.12.1: el Ambar Mayor es papel con el modelo de la plantilla; sin el modelo, aqui se veria papel.
        if (!g.esencias()) Reliquias.ponerModelo(boton, Reliquias.modelo(hc.cfg(), g.grado(), g.especial()));
        return boton;
    }

    private String fecha(long millis) {
        Calendario cal = hc.calendario();
        ZoneId zona = cal != null ? cal.zona() : ZoneId.systemDefault();
        return DateTimeFormatter.ofPattern("dd/MM").format(Instant.ofEpochMilli(millis).atZone(zona));
    }

    /**
     * Vender todo: el total, lo que la Aduana pagaria hoy y los extras, y debajo los topes de hoy (1.12.1:
     * antes eran un boton aparte); el clic abre la confirmacion.
     */
    private void botonTodo(Inventory inv, Player p, Tasacion.Oferta o, boolean puede, Vista v) {
        List<Component> lore = new ArrayList<>();
        boolean compra = o != null && !o.vacia() && o.compra() > 0;
        if (o == null || o.vacia()) lore.add(Marco.tenue("No llevas nada que Oren compre."));
        else lore.addAll(resumen(o, null));
        lore.add(Component.empty());
        lore.addAll(topes(p, o));
        lore.add(Component.empty());
        if (o == null || o.vacia()) lore.add(Marco.tenue("Tráele Reliquias o Esencias."));
        else if (!puede) lore.add(Marco.porQueNo("Oren solo compra en el spawn."));
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

    /** Topes de hoy, en lineas para el lore de Vender todo: las Astillas y Fragmentos que aun paga y la Aduana. */
    private List<Component> topes(Player p, Tasacion.Oferta o) {
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
        lore.add(Marco.tenue("Topes de hoy:"));
        String n1 = rel == null ? "Astilla del Umbral" : rel.nombreDe(1, null, null);
        String n2 = rel == null ? "Fragmento de Nana" : rel.nombreDe(2, null, null);
        lore.add(fila(" " + n1, "te quedan " + Math.max(0, val.topeDia()[1] - ya1) + " de " + val.topeDia()[1]));
        lore.add(fila(" " + n2, "te quedan " + Math.max(0, val.topeDia()[2] - ya2) + " de " + val.topeDia()[2]));
        Aduana ad = hc.aduana();
        if (ad != null) {
            long hoy = ad.mcHoy(p.getUniqueId());
            List<Tramo> tramos = tramosHoy(hoy, ad.tramos());
            long escala = escala(tramos);
            lore.add(fila(" MobCoins cobradas", Altar.miles(hoy) + (escala > 0 ? " de " + Altar.miles(escala) : "")));
            if (tramos.isEmpty()) lore.add(Component.text(" Hoy la Aduana ya no paga más.", Paleta.AVISO));
        }
        lore.add(Marco.tenue("Grado III y IV y Esencias, sin tope."));
        lore.add(Marco.tenue("Todo vuelve a cero a medianoche."));
        return lore;
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

    /** Contratos de hoy: cada uno en una linea con como va, los activos y los de la semana. */
    private void botonContratos(Inventory inv, Player p, Vista v) {
        UUID u = p.getUniqueId();
        Contratos con = hc.contratos();
        List<Component> lore = new ArrayList<>();
        if (con == null || !hc.valor("contratos", con::activo, false)) {
            lore.add(Marco.tenue("Oren no tiene contratos ahora mismo."));
            inv.setItem(CONTRATOS, iconoContratos(Component.text("Contratos", Paleta.TENUE), lore, false));
            return;
        }
        boolean papel = hc.valor("contratos", con::pergaminoActivo, false);
        lore.add(Marco.texto("Encargos de Oren que cambian"));
        lore.add(Marco.texto(papel ? "cada día. Acepta los que quieras." : "cada día. Se cobran al salir vivo."));
        lore.add(Component.empty());
        List<Contratos.Estado> lista = hc.valor("contratos", () -> con.estados(p), List.of());
        boolean listo = false;
        for (Contratos.Estado e : lista) {
            Contratos.Def d = e.def();
            Component l = Component.text("· " + d.texto() + "  ", e.cobrado() ? Paleta.TENUE : Paleta.TEXTO);
            if (e.cobrado()) l = l.append(Component.text("cobrado", Paleta.TENUE));
            else if (e.cumplido()) l = l.append(Component.text("cumplido", Paleta.BIEN));
            else if (papel && e.oferta()) l = l.append(Component.text("oferta", Paleta.DETALLE));
            else l = l.append(Component.text(Math.min(e.progreso(), d.objetivo()) + "/" + d.objetivo(), Paleta.CIFRA));
            lore.add(l);
            listo |= e.cumplido() && !e.cobrado();
            listo |= papel && e.oferta();
        }
        if (lista.isEmpty()) lore.add(Marco.tenue("Hoy no tienes ninguno."));
        if (papel) lore.add(fila("Activos", hc.valor("contratos", () -> con.activosDe(p), 0) + " de " + con.activosMax()));
        int[] semana = con.semanaDe(u);
        lore.add(fila("Esta semana", Math.min(semana[0], semana[1]) + " de " + semana[1]));
        lore.add(Component.empty());
        lore.add(Marco.accion(papel ? "Clic para verlos y aceptarlos" : "Clic para verlos o cambiarlos"));
        inv.setItem(CONTRATOS, iconoContratos(Component.text("Contratos", Paleta.DETALLE), lore, listo));
        v.acciones().put(CONTRATOS, "ver:" + V_CONTRATOS);
    }

    /** Tu camino (en Tu dinero desde la 1.12.1): las horas activas y el proximo hito; el clic abre el Camino. */
    private ItemStack camino(Player p, Vista v, int casilla) {
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
        if (cam != null) v.acciones().put(casilla, "camino");
        return Marco.icono(Material.COMPASS, Component.text("Tu camino", cam != null ? Paleta.DETALLE : Paleta.TENUE), lore, false);
    }

    // ------------------------------------------------------------------ vender todo: confirmar

    /** Lo que vendes y lo que recibes en el centro (13); No a la izquierda (11) y Si a la derecha (15). */
    private void vistaTodo(Inventory inv, Player p, Vista v) {
        Tasacion tas = hc.tasacion();
        Tasacion.Oferta o = tas == null ? null : hc.valor("tasacion", () -> tas.oferta(p), null);
        if (o == null || o.vacia()) {
            inv.setItem(CENTRO, Marco.icono(Material.PAPER, Component.text("Ya no llevas nada que vender", Paleta.TENUE),
                    List.of(Marco.tenue("Vuelve al Mercado.")), false));
        } else {
            List<Component> que = new ArrayList<>();
            que.add(Marco.tenue("Le vendes a Oren:"));
            int n = 0;
            for (Tasacion.Grupo g : o.grupos()) {
                if (n++ >= 8) {
                    que.add(Marco.tenue("y " + (o.grupos().size() - 8) + " tipos más"));
                    break;
                }
                que.add(lineaTodo(g));
            }
            que.add(Component.empty());
            que.addAll(resumen(o, p));
            inv.setItem(CENTRO, Marco.icono(Material.CHEST, Component.text("Recibes: ", Paleta.TEXTO)
                    .append(Component.text(paga((long) o.pagaEsencias() + o.esencias(), o.pagaMc()), Marco.SI)), que, false));
        }
        inv.setItem(NO, Marco.icono(Material.RED_CONCRETE, Component.text("No, déjalo", Marco.NO),
                List.of(Marco.tenue("Vuelves al Mercado sin"), Marco.tenue("vender nada."), Component.empty(),
                        Marco.accion("Clic para volver")), false));
        v.acciones().put(NO, "no-todo");
        if (o != null && !o.vacia() && o.compra() > 0) {
            List<Component> siLore = new ArrayList<>(List.of(Marco.tenue("Oren se queda lo del centro"),
                    Marco.tenue("y te paga al momento.")));
            if (o.quedan() > 0) {
                siLore.add(Marco.tenue("Lo que pasa del tope de hoy"));
                siLore.add(Marco.tenue("se queda en tu inventario."));
            }
            siLore.add(Component.empty());
            siLore.add(Marco.accion("Clic para vender"));
            // Lo que se ensena va en la accion: si al confirmar ya no es lo mismo, no se vende a ciegas.
            inv.setItem(SI, Marco.icono(Material.LIME_CONCRETE, Component.text("Sí, véndeselo todo", Marco.SI), siLore, false));
            v.acciones().put(SI, "si-todo:" + firma(o));
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

    /** 1.12.1: una sola fila, centrada: saldo, premios, lo vendido, primera salida, Racha y Tu camino. */
    private void vistaDinero(Inventory inv, Player p, Vista v) {
        UUID u = p.getUniqueId();
        Tasacion.Valores val = Tasacion.Valores.de(hc.cfg());
        Boolean cobrada = primeraCobrada(u);
        Racha racha = hc.racha();
        boolean conRacha = racha != null && racha.activa();
        int n = 4 + (cobrada != null ? 1 : 0) + (conRacha ? 1 : 0);
        int[] c = casillasFila(n);
        int k = 0;
        Marco.saldo(inv, v.acciones(), hc, p, c[k++]);
        int cp = c[k++];
        inv.setItem(cp, premios(p, v, cp));
        inv.setItem(c[k++], semana(u));
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
            inv.setItem(c[k++], Marco.icono(Material.CLOCK, Component.text(cobrada ? "Primera salida: ya cobrada" : "Primera salida: aún paga",
                    cobrada ? Paleta.TENUE : Paleta.BIEN), lore, !cobrada));
        }
        if (conRacha) {
            int r = racha.de(u);
            double porPunto = hc.cfg().getDouble("racha.por-punto", 0.10);
            String por = "×" + Marco.numero(Math.round(Racha.factor(r, racha.tope(null), porPunto) * 100) / 100.0);
            List<Component> rl = new ArrayList<>();
            rl.add(fila("Lo que vendes vale", por));
            rl.add(Component.empty());
            rl.add(Marco.tenue("Sube 1 la primera vez que le vendes"));
            rl.add(Marco.tenue("a Oren una Reliquia de grado II o"));
            rl.add(Marco.tenue("más conseguida en esa misma"));
            rl.add(Marco.tenue("entrada a Calamity."));
            rl.add(Marco.tenue("Si mueres, vuelve a 0."));
            inv.setItem(c[k++], Marco.icono(Material.BLAZE_POWDER, Component.text("Tu Racha de Codicia: ", Paleta.TEXTO)
                    .append(Component.text(r, Paleta.CIFRA)), rl, r > 0));
        }
        int cc = c[k];
        inv.setItem(cc, camino(p, v, cc));
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

    /** 1.16.1 · El boton de Contratos con un pergamino (diseño de estandarte), como los de los contratos. */
    private static ItemStack iconoContratos(Component nombre, List<Component> lore, boolean brillo) {
        ItemStack it = Marco.icono(Material.GLOBE_BANNER_PATTERN, nombre, lore, brillo);
        Pergaminos.ocultarDiseno(it);
        return it;
    }

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

    /**
     * Calamity 1.12.1 · Los contratos de hoy en la fila del medio. Una oferta se acepta con un clic (y llega
     * su pergamino); uno aceptado cuyo pergamino no llevas, con un clic Oren te da otra copia; todo en el
     * spawn de Calamity. Abajo: la semana, Volver y Cambiar uno (con su confirmacion: un toque en un contrato
     * nunca lo cambia).
     */
    private void vistaContratos(Inventory inv, Player p, Vista v) {
        UUID u = p.getUniqueId();
        Contratos con = hc.contratos();
        if (con == null || !hc.valor("contratos", con::activo, false)) {
            inv.setItem(CENTRO, Marco.icono(Material.PAPER, Component.text("Oren no tiene contratos ahora", Paleta.TENUE),
                    List.of(Marco.tenue("Vuelve más adelante.")), false));
            return;
        }
        List<Contratos.Estado> lista = hc.valor("contratos", () -> con.estados(p), List.of());
        int precio = con.precioCambio(u), gratis = con.cambiosGratis(u);
        boolean papel = hc.valor("contratos", con::pergaminoActivo, false);
        boolean lugar = hc.valor("contratos", () -> con.lugarParaAceptar(p), false);
        int activos = hc.valor("contratos", () -> con.activosDe(p), 0), max = con.activosMax();
        Set<Integer> lleva = papel ? hc.valor("contratos", () -> con.llevados(p), Set.<Integer>of()) : Set.of();
        int[] casillas = casillasFila(lista.size());
        boolean hayCambiable = false;
        for (int k = 0; k < casillas.length; k++) {
            Contratos.Estado e = lista.get(k);
            Contratos.Def d = e.def();
            hayCambiable |= !e.cumplido() && !e.cobrado();
            List<Component> lore = new ArrayList<>();
            if (!e.oferta() || !papel) lore.add(Marco.barra(e.progreso(), d.objetivo()));
            lore.add(fila("Paga", Contratos.premio(d)));
            lore.add(fila("Dura", "hasta la medianoche"));
            if (d.corto()) lore.add(Marco.tenue("Es corto: se hace en una entrada rápida."));
            lore.add(Marco.tenue(!papel ? "Se cobra al salir vivo." : Contratos.seCobraAlSalir(d) ? Pergaminos.COBRO_VENTA
                    : Pergaminos.COBRO_DENTRO));
            lore.add(Component.empty());
            // La ultima linea: lo que hace el clic o por que no hace nada.
            String accion = null;
            if (e.cobrado()) {
                lore.add(Marco.tiene("Ya lo cobraste."));
            } else if (e.cumplido()) {
                // Con pergaminos solo espera el de Reliquias (los demas se cobran al cumplirlos); sin ellos, todos
                // se cobran al salir vivo.
                lore.add(Component.text(papel && Contratos.seCobraAlSalir(d) ? "Cumplido: se cobra al venderlas."
                        : "Cumplido: lo cobras al salir vivo.", Paleta.CIFRA));
            } else if (!papel) {
                lore.add(Marco.tenue("Si mueres, vuelve a empezar."));
            } else if (e.oferta()) {
                lore.add(Marco.tenue("Al aceptarlo te da su pergamino:"));
                lore.add(Marco.tenue("llévalo encima para que cuente."));
                if (!lugar) lore.add(Marco.porQueNo("Se acepta con Oren, en el spawn."));
                else if (activos >= max) lore.add(Marco.porQueNo("Ya llevas " + max + " activos."));
                else {
                    lore.add(Marco.accion("Clic para aceptarlo"));
                    accion = "p:" + e.hueco();
                }
            } else if (lleva.contains(e.hueco())) {
                lore.add(Marco.tiene("Aceptado: llevas su pergamino."));
            } else if (!con.recuperarPerdido()) {
                lore.add(Component.text("Perdiste su pergamino.", Paleta.AVISO));
                lore.add(Marco.tenue("Oren no da otro: puedes cambiarlo."));
            } else if (!lugar) {
                lore.add(Component.text("No llevas su pergamino.", Paleta.AVISO));
                lore.add(Marco.tenue("Oren te da otro en el spawn."));
            } else {
                lore.add(Component.text("No llevas su pergamino.", Paleta.AVISO));
                lore.add(Marco.accion("Clic para pedirle otro"));
                accion = "p:" + e.hueco();
            }
            TextColor color = e.cobrado() ? Paleta.TENUE : e.cumplido() ? Paleta.BIEN : e.oferta() ? Paleta.TEXTO : Paleta.DETALLE;
            Material icono = e.cobrado() ? Material.MAP : iconoContrato(d.evento());
            inv.setItem(casillas[k], Marco.icono(icono, Component.text(d.texto(), color), lore,
                    (e.cumplido() && !e.cobrado()) || accion != null));
            if (accion != null) v.acciones().put(casillas[k], accion);
        }
        if (lista.isEmpty()) {
            inv.setItem(CENTRO, Marco.icono(Material.PAPER, Component.text("Hoy no tienes contratos", Paleta.TENUE),
                    List.of(Marco.tenue("Vuelve a mirar mañana.")), false));
        }

        int[] semana = con.semanaDe(u);
        List<Component> sl = new ArrayList<>();
        sl.add(Marco.barra(semana[0], semana[1]));
        sl.add(Marco.texto("Si cobras " + semana[1] + " en la semana,"));
        sl.add(Marco.texto("Oren te da la Llave del Caos."));
        if (papel) {
            sl.add(Component.empty());
            sl.add(fila("Activos", activos + " de " + max));
            sl.add(Marco.tenue("Un aceptado ocupa su sitio hasta"));
            sl.add(Marco.tenue("cobrarlo, aunque pierdas el papel."));
        }
        inv.setItem(SEMANA, Marco.icono(Material.TRIAL_KEY, Component.text("Esta semana: ", Paleta.TEXTO)
                .append(Component.text(Math.min(semana[0], semana[1]) + "/" + semana[1], Paleta.CIFRA)), sl, semana[0] >= semana[1]));

        List<Component> cl = new ArrayList<>();
        cl.add(Marco.tenue("Oren te da otro encargo en lugar"));
        cl.add(Marco.tenue("de uno de estos. Pierdes lo que"));
        cl.add(Marco.tenue("llevas hecho de ese."));
        cl.add(Component.empty());
        cl.add(Marco.tenue(gratis > 0 ? "Hoy te " + (gratis == 1 ? "queda 1 cambio gratis." : "quedan " + gratis + " cambios gratis.")
                : "Cambiar uno cuesta " + Marco.esencias(precio) + "."));
        cl.add(hayCambiable ? Marco.accion("Clic para elegir cuál") : Marco.tenue("Ahora no hay ninguno que cambiar."));
        inv.setItem(CAMBIAR, Marco.icono(Material.FEATHER, Component.text("Cambiar un contrato", hayCambiable ? Paleta.DETALLE : Paleta.TENUE),
                cl, false));
        if (hayCambiable) v.acciones().put(CAMBIAR, "ver:" + V_ELEGIR);
    }

    /** Cambiar un contrato, paso 1: los que se pueden cambiar en la fila del medio; un clic abre la confirmacion. */
    private void vistaElegir(Inventory inv, Player p, Vista v) {
        Contratos con = hc.contratos();
        List<Contratos.Estado> lista = new ArrayList<>();
        if (con != null) {
            for (Contratos.Estado e : hc.valor("contratos", () -> con.estados(p), List.<Contratos.Estado>of())) {
                if (!e.cumplido() && !e.cobrado()) lista.add(e);
            }
        }
        if (lista.isEmpty()) {
            inv.setItem(CENTRO, Marco.icono(Material.PAPER, Component.text("No hay ninguno que cambiar", Paleta.TENUE),
                    List.of(Marco.tenue("Los cumplidos y cobrados no se cambian.")), false));
            return;
        }
        int[] casillas = casillasFila(lista.size());
        for (int k = 0; k < casillas.length; k++) {
            Contratos.Estado e = lista.get(k);
            inv.setItem(casillas[k], Marco.icono(iconoContrato(e.def().evento()), Component.text(e.def().texto(), Paleta.TEXTO),
                    List.of(Marco.barra(e.progreso(), e.def().objetivo()), Component.empty(), Marco.accion("Clic para cambiar este")), false));
            v.acciones().put(casillas[k], "c:" + e.hueco());
        }
    }

    // ------------------------------------------------------------------ cambiar un contrato

    /** Confirmar el cambio de un contrato: No (11), el contrato con lo que cuesta (13) y Si (15). */
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
        Saldo s = hc.saldo();
        long saldo = s == null ? 0 : s.de(p.getUniqueId());
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.barra(e.progreso(), e.def().objetivo()));
        lore.add(Marco.tenue("Si lo cambias, pierdes lo que llevas."));
        lore.add(Component.empty());
        if (precio == 0) lore.add(Marco.tiene("Gratis: es tu cambio gratis de hoy."));
        else {
            lore.add(fila("Cuesta", Marco.esencias(precio)));
            lore.add(fila("Tienes", Altar.miles(saldo)));
            lore.add(fila("Te quedarían", Altar.miles(Math.max(0, saldo - precio))));
        }
        inv.setItem(CENTRO, Marco.icono(iconoContrato(e.def().evento()), Component.text(e.def().texto(), Paleta.TEXTO), lore, false));
        inv.setItem(NO, Marco.icono(Material.RED_CONCRETE, Component.text("No, déjalo", Marco.NO),
                List.of(Marco.tenue("Vuelves a tus contratos sin"), Marco.tenue("cambiar nada."), Component.empty(),
                        Marco.accion("Clic para volver")), false));
        v.acciones().put(NO, "no");
        inv.setItem(SI, Marco.icono(Material.LIME_CONCRETE, Component.text("Sí, cámbialo", Marco.SI), List.of(
                Marco.tenue("Oren te da otro encargo en su"), Marco.tenue("lugar, como oferta: si lo quieres,"),
                Marco.tenue("lo aceptas."), Component.empty(), Marco.accion("Clic para cambiarlo")), false));
        v.acciones().put(SI, "si");
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
            // 1.12.1: aceptar la oferta (o pedir otra copia del pergamino perdido). Si no, se le dice por que.
            int hueco = Integer.parseInt(accion.substring(2));
            Contratos con = hc.contratos();
            if (con == null) return;
            // valor() cambia un null por el defecto: "" es "hecho" y el defecto, un fallo del modulo.
            String no = hc.valor("contratos", () -> {
                String r = con.pedirDesdeMenu(p, hueco);
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
            case "volver-contratos" -> {
                Marco.sonar(p, "ui.button.click", 0.45f, 0.8f);
                tarea(() -> abrirVista(p, V_CONTRATOS));
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

    private static List<Integer> lista(int[] a) {
        List<Integer> out = new ArrayList<>();
        for (int x : a) out.add(x);
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

        // 1.12.1: todo en 3 filas; la fila del medio para lo que se ensena y tres botones abajo.
        h.igual("tienda: 27 casillas", 27, TAMANO);
        h.igual("tienda: un tipo va en el centro", List.of(13), lista(casillasFila(1)));
        h.igual("tienda: tres tipos con aire (11, 13 y 15)", List.of(11, 13, 15), lista(casillasFila(3)));
        h.igual("tienda: siete tipos llenan la fila", List.of(10, 11, 12, 13, 14, 15, 16), lista(casillasFila(7)));
        h.igual("tienda: nunca mas de 7", 7, casillasFila(20).length);
        boolean enFila = true;
        for (int n = 1; n <= 7; n++) for (int c : casillasFila(n)) enFila &= c / 9 == 1 && c % 9 >= 1 && c % 9 <= 7;
        h.ok("tienda: los tipos, en la fila del medio y lejos de los bordes", enFila);
        h.ok("tienda: Vender todo abajo en el centro", VENDER_TODO == Marco.abajo(TAMANO));
        h.ok("tienda: Contratos a la izquierda y Tu dinero a la derecha", CONTRATOS == VENDER_TODO - 2 && DINERO == VENDER_TODO + 2
                && CONTRATOS / 9 == 2 && DINERO / 9 == 2);
        h.ok("subvistas: Volver abajo en el centro", SALIR == Marco.abajo(TAMANO));
        h.ok("contratos: la semana y Cambiar a los lados de Volver", SEMANA == SALIR - 2 && CAMBIAR == SALIR + 2);
        h.ok("confirmar: No, lo confirmado y Si en la fila del medio", NO == 11 && CENTRO == 13 && SI == 15);
        List<Integer> todas = new ArrayList<>(List.of(CONTRATOS, VENDER_TODO, DINERO));
        for (int c : casillasFila(7)) todas.add(c);
        boolean bien = new HashSet<>(todas).size() == todas.size();
        for (int c : todas) bien &= c >= 0 && c < TAMANO && c / 9 >= 1;
        h.ok("tienda: ninguna casilla repetida, fuera de la ventana ni en la fila de arriba", bien);

        // Que tipos salen y en que orden: I a IV, especiales, Esencias; con mas de 7, los de mas valor.
        List<Tipo> tipos = List.of(new Tipo(4, null, false, 6, 100), new Tipo(0, null, true, 12, 0),
                new Tipo(1, null, false, 2, 50), new Tipo(4, Reliquias.CAMPANA, false, 6, 100), new Tipo(3, null, false, 3, 40),
                new Tipo(2, null, false, 1, 15));
        h.igual("tipos: I, II, III, IV, Campana y Esencias", List.of(2, 5, 4, 0, 3, 1), visibles(tipos));
        List<Tipo> muchos = new ArrayList<>();
        for (int i = 0; i < 9; i++) muchos.add(new Tipo(1 + i % 4, i >= 4 ? Reliquias.SELLO : null, false, i, 0));
        List<Integer> vis = visibles(muchos);
        h.igual("tipos: con 9, salen 6 y la septima casilla dice cuantos faltan", 6, vis.size());
        h.ok("tipos: con 9, se quedan fuera los de menos valor", !vis.contains(0) && !vis.contains(1) && !vis.contains(2));
        boolean ordenados = true;
        for (int i = 1; i < vis.size(); i++) ordenados &= orden(muchos.get(vis.get(i - 1))) <= orden(muchos.get(vis.get(i)));
        h.ok("tipos: con 9, los que salen van en orden", ordenados);
        h.ok("tipos: la Eclipsada va detras del Sello", orden(new Tipo(3, Reliquias.ECLIPSADA, false, 0, 0))
                > orden(new Tipo(4, Reliquias.SELLO, false, 0, 0)));
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
        for (String vista : List.of(PORTADA, V_DINERO, V_CONTRATOS, V_ELEGIR, V_TODO)) {
            h.ok("titulo de la vista '" + vista + "' cabe", titulo(vista).ancho() <= Marco.ANCHO_TITULO);
        }
    }
}
