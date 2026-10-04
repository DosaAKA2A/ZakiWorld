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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * El menu del Tasador, el NPC de la antesala (1.3.0; rehecho en la 1.5.0).
 *
 * Por que es asi: la version de antes lo metia todo en una ventana de 54 (saldo arriba, cuatro
 * filas de grupos con bandas de cristal rojo a los lados, una barra de cristales verdes para la
 * Aduana y unos catorce iconos), y Dosa lo dijo claro: "Esto no se entiende en absoluto". En
 * Minecraft el texto solo se ve al pasar el raton (en Bedrock ni eso), asi que un menu lleno de
 * iconos sin orden es ilegible. Ahora hay una portada con seis botones grandes en una rejilla de
 * 3 x 2 y, los que tienen mas cosas, abren su subvista con Volver:
 *
 *   portada (45)        fila 1:  Tu dinero (11) · Contratos de hoy (13) · Tus reliquias (15)
 *                       fila 2:  La Aduana de hoy (20) · Tu camino (22) · ¿Como funciona? (24)
 *                       fila 4:  Altar (38) · Cerrar (40) · Forja (42)
 *   Tu dinero (45)      fila 1:  saldo (clic: ingresar) · premios pendientes (clic: recoger) ·
 *                                lo tasado esta semana;  fila 2: primera salida de hoy y Racha
 *   Contratos (45)      fila 1:  los tres contratos (clic, en Calamity: recibir su pergamino);
 *                       fila 2:  debajo de cada uno sin cumplir, "Cambiar contrato" (clic: el
 *                                confirmar de siempre);  fila 3: los de la semana
 *   Tus reliquias (45)  fila 1:  cada Reliquia que llevas;  fila 2: lo que valdrian ahora
 *
 * Calamity 1.10: cada contrato es un pergamino (Contratos, Pergaminos). Antes el clic en el contrato
 * lo cambiaba; ahora da su pergamino a quien no lo lleva, y cambiar va en su propio boton, en la misma
 * columna y una fila mas abajo, para que un toque en Bedrock no cambie un contrato sin querer. Fuera de
 * Calamity el contrato solo dice que los pergaminos se entregan dentro.
 *
 * Cerrar (portada) y Volver (subvistas) van siempre en la misma casilla, abajo en el centro, y
 * asi en todos los menus de Calamity desde 1.7.3. El relleno es cristal negro sin nombre
 * (Marco.rellenar; en 1.5.0 fue gris y en 1.7.3 negro, como pidio Dosa): nada de filas de
 * colores que parezcan significar algo. Cada boton dice en su lore las cifras ya calculadas y, en
 * la ultima linea, lo que hace el clic. Solo clic izquierdo (Bedrock: un toque).
 *
 * No escribe nada salvo lo que ya hacian sus botones (Entregas.pendientes, Contratos.cambiar,
 * Altar.depositar y, desde la 1.10, Contratos.darDesdeMenu). Se puede abrir en cualquier sitio:
 * los enlaces al Altar y a la Forja miran la regla del Altar y el deposito y la recogida de premios
 * solo se hacen fuera de Calamity. La "ultima tasacion" no sale porque no se guarda (ver
 * Npcs.tasador).
 */
final class MenuTasador implements Listener {

    private static final long ESPERA_MS = 500;
    /** Todas las vistas del Tasador (salvo confirmar) miden lo mismo: 5 filas. */
    static final int TAMANO = 45;
    /** La portada: dos filas de tres botones, en las columnas 2, 4 y 6. */
    static final int DINERO = 11, CONTRATOS = 13, RELIQUIAS = 15, ADUANA = 20, CAMINO = 22, AYUDA = 24;
    /** Abajo: el Altar y la Forja a los lados; en el centro Cerrar (portada) o Volver (subvistas). */
    static final int IR_ALTAR = 38, SALIR = 40, IR_FORJA = 42;
    /**
     * Las filas de las subvistas: la de arriba (lo principal) y la de debajo (el resumen). 1.10: en
     * Contratos la de debajo lleva los botones de cambiar y el resumen baja a la tercera (FILA_C).
     */
    static final int FILA_A = 9, FILA_B = 18, FILA_C = 27;

    static final String PORTADA = Marco.TASADOR, V_DINERO = "dinero", V_CONTRATOS = "contratos",
            V_RELIQUIAS = "reliquias", V_CAMBIAR = "cambiar";

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
        abrirVista(p, PORTADA);
        if (conSonido) Marco.sonar(p, "item.book.page_turn", 0.8f, 0.8f);
    }

    private static Marco.Titulo titulo(String pantalla) {
        return switch (pantalla) {
            case V_DINERO -> Marco.T_TASADOR_DINERO;
            case V_CONTRATOS -> Marco.T_TASADOR_CONTRATOS;
            case V_RELIQUIAS -> Marco.T_TASADOR_RELIQUIAS;
            default -> Marco.T_TASADOR;
        };
    }

    private void abrirVista(Player p, String pantalla) {
        // Calamity 1.11: dentro de Calamity, con la libreta de otro dia y nada a medias, Oren da la de hoy
        // (Contratos.renovar). Aqui y no en abrir(): los enlaces abren las vistas directamente. Sin libreta
        // caducada es mirar una seccion y sus tres huecos.
        Contratos con = hc.contratos();
        if (con != null && hc.esHardcore(p)) hc.seguro("contratos", () -> con.renovar(p));
        Vista v = new Vista(pantalla, new HashMap<>(), 0);
        Inventory inv = hc.plugin().getServer().createInventory(v, TAMANO, titulo(pantalla).componente());
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
            case V_RELIQUIAS -> vistaReliquias(inv, p);
            default -> portada(inv, p, v);
        }
        if (v.pantalla().equals(PORTADA)) {
            inv.setItem(SALIR, Marco.cerrar());
            v.acciones().put(SALIR, "cerrar");
        } else {
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

    /** "1 Reliquia", "3 Reliquias". */
    static String cuantas(long n, String una, String varias) {
        return Altar.miles(n) + " " + (n == 1 ? una : varias);
    }

    // ------------------------------------------------------------------ portada

    private void portada(Inventory inv, Player p, Vista v) {
        botonDinero(inv, p, v);
        botonContratos(inv, p, v);
        botonReliquias(inv, p, v);
        inv.setItem(ADUANA, aduana(p));
        botonCamino(inv, p, v);
        inv.setItem(AYUDA, Marco.ayuda(hc));
        boolean altar = Marco.altarAbierto(hc, p);
        Marco.enlace(inv, v.acciones(), IR_ALTAR, Marco.UMBRAL, Material.ENCHANTING_TABLE, "Altar del Umbral",
                List.of("Frascos, Cristales de Regreso,", "Tinturas y la Llave del Caos."), altar);
        Marco.enlace(inv, v.acciones(), IR_FORJA, Marco.FORJA, Material.ANVIL, "La Forja",
                List.of("El Manto, el Vestigio del Eco,", "la Guadaña y sus mejoras."), altar);
    }

    /** Tu dinero: el saldo, las MobCoins, los Sellos, Marcas y Fragmentos, los premios y la semana. */
    private void botonDinero(Inventory inv, Player p, Vista v) {
        UUID u = p.getUniqueId();
        List<Component> lore = new ArrayList<>();
        Saldo s = hc.saldo();
        lore.add(Marco.dato("Saldo", Marco.esencias(s == null ? 0 : s.de(u))));
        lore.add(Marco.tenue("Es tuyo: no caduca ni se reinicia."));
        Monedero mon = hc.monedero();
        if (mon != null && mon.disponible()) lore.add(Marco.dato("MobCoins", Altar.miles(mon.saldo(p))));
        Creditos cr = hc.creditos();
        if (cr != null) {
            int sellos = 0;
            for (Map.Entry<String, Integer> e : cr.todos(u).entrySet()) {
                if (e.getValue() > 0 && (e.getKey().startsWith("sello:") || e.getKey().equals(Creditos.ERRANTE))) sellos += e.getValue();
            }
            int marcas = cr.de(u, "marca"), fragmentos = cr.de(u, "fragmento");
            if (sellos > 0) lore.add(Marco.dato("Sellos", String.valueOf(sellos)));
            if (marcas > 0) lore.add(Marco.dato("Marcas de Eco", String.valueOf(marcas)));
            if (fragmentos > 0) lore.add(Marco.dato("Fragmentos de Guadaña", String.valueOf(fragmentos)));
        }
        int pend = hc.datos().getMapList("premios-pendientes." + u).size();
        boolean dentro = hc.esHardcore(p);
        lore.add(Component.empty());
        lore.add(pend == 0 ? Marco.tenue("No tienes premios pendientes.")
                : Component.text("Tienes " + cuantas(pend, "premio pendiente.", "premios pendientes."), Paleta.CIFRA));
        int encima = s == null ? 0 : s.encima(p);
        if (encima > 0) lore.add(Component.text("Llevas " + Marco.esencias(encima) + " encima.", Paleta.CIFRA));
        Estadisticas st = hc.estadisticas();
        if (st != null) {
            long e = st.semana(u, "tasado-esencias"), mc = st.semana(u, "tasado-mc");
            lore.add(Marco.dato("Vendido esta semana", Marco.esencias(e) + " y " + Altar.miles(mc) + " MobCoins"));
        }
        Boolean primera = primeraCobrada(u);
        if (primera != null) lore.add(primera ? Marco.tenue("Ya cobraste la primera salida de hoy.")
                : Marco.tiene("La primera salida de hoy aún paga."));
        lore.add(Component.empty());
        lore.add(Marco.accion("Clic para ver el detalle"));
        boolean algo = !dentro && (pend > 0 || encima > 0);
        inv.setItem(DINERO, Marco.icono(Material.GOLD_INGOT, Component.text("Tu dinero", Paleta.CIFRA), lore, algo));
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
        lore.add(Marco.dato("Esta semana", Math.min(semana[0], semana[1]) + " de " + semana[1]));
        lore.add(Component.empty());
        lore.add(Marco.accion("Clic para verlos o cambiarlos"));
        inv.setItem(CONTRATOS, Marco.icono(Material.PAPER, Component.text("Contratos de hoy", Paleta.DETALLE), lore, listo));
        v.acciones().put(CONTRATOS, "ver:" + V_CONTRATOS);
    }

    /** Tus reliquias: cuantas llevas y lo que valdrian si sales ahora. */
    private void botonReliquias(Inventory inv, Player p, Vista v) {
        List<ItemStack> encima = encima(p);
        List<Component> lore = new ArrayList<>();
        if (encima.isEmpty()) {
            lore.add(Marco.tenue("No llevas ninguna Reliquia."));
            lore.add(Component.empty());
            lore.add(Marco.tenue("Salen de los mobs, los cofres"));
            lore.add(Marco.tenue("y los minijefes de Calamity."));
            inv.setItem(RELIQUIAS, Marco.icono(Material.BUNDLE, Component.text("Tus Reliquias", Paleta.TENUE), lore, false));
            return;
        }
        Reliquias rel = hc.reliquias();
        int total = 0;
        ItemStack mejor = encima.get(0);
        for (ItemStack it : encima) {
            total += it.getAmount();
            if (rel.grado(it) > rel.grado(mejor)) mejor = it;
        }
        lore.add(Marco.texto("Llevas " + cuantas(total, "Reliquia.", "Reliquias.")));
        Tasacion.Resumen r = simular(p, encima);
        if (r != null) {
            lore.add(Component.empty());
            lore.add(Marco.texto("Si sales vivo ahora, valdrían:"));
            lore.add(Marco.dato("Esencias", Altar.miles(r.esencias())));
            lore.add(Marco.dato("MobCoins", Altar.miles(r.mobcoins())));
        }
        lore.add(Marco.tenue("Si mueres, no cobras nada por ellas."));
        lore.add(Component.empty());
        lore.add(Marco.accion("Clic para ver cuáles llevas"));
        inv.setItem(RELIQUIAS, Marco.icono(new ItemStack(mejor.getType(), Math.max(1, Math.min(64, total))),
                Component.text("Tus Reliquias", Reliquias.AMBAR), lore, false));
        v.acciones().put(RELIQUIAS, "ver:" + V_RELIQUIAS);
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
            lore.add(Marco.dato("Horas activas", Camino.horasTexto(h)));
            if (hito > 0) lore.add(Marco.dato("Próximo hito", Camino.horasTexto(hito).replace(",0", "") + " h"));
            lore.add(Marco.tenue("Solo cuenta el tiempo en que te mueves."));
        }
        lore.add(Component.empty());
        lore.add(cam != null ? Marco.accion("Clic para verlo") : Marco.tenue("Ahora mismo no se puede ver."));
        inv.setItem(CAMINO, Marco.icono(Material.COMPASS, Component.text("Tu camino", cam != null ? Paleta.DETALLE : Paleta.TENUE),
                lore, false));
        if (cam != null) v.acciones().put(CAMINO, "camino");
    }

    // ------------------------------------------------------------------ la Aduana (portada)

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

    private static TextColor colorDe(double factor) {
        return factor >= 1 ? Paleta.BIEN : factor >= 0.5 ? Paleta.CIFRA : Paleta.MARCA;
    }

    /** Un solo boton: lo cobrado hoy con su barra, lo que queda en cada tramo y cuando vuelve a cero. */
    private ItemStack aduana(Player p) {
        Aduana ad = hc.aduana();
        if (ad == null) {
            return Marco.icono(Material.SUNFLOWER, Component.text("La Aduana de hoy", Paleta.TENUE),
                    List.of(Marco.tenue("Ahora mismo no está.")), false);
        }
        long hoy = ad.mcHoy(p.getUniqueId());
        List<Tramo> tramos = tramosHoy(hoy, ad.tramos());
        long escala = escala(tramos);

        List<Component> lore = new ArrayList<>();
        lore.add(Marco.texto("Te paga las MobCoins que ganas"));
        lore.add(Marco.texto("en Calamity, hasta un tope diario."));
        lore.add(Component.empty());
        if (escala > 0) lore.add(Marco.barra(hoy, escala));
        lore.add(Marco.dato("Cobradas hoy", Altar.miles(hoy) + " MobCoins"));
        if (tramos.isEmpty()) lore.add(Marco.tenue("Hoy ya no te paga nada más."));
        for (Tramo t : tramos) {
            Component linea = Component.text("Al " + Marco.porcentaje(t.factor()) + ": ", colorDe(t.factor()));
            if (t.hasta() >= 100_000) linea = linea.append(Component.text("desde " + Altar.miles(t.desde()) + ", sin tope", Paleta.TEXTO));
            else if (t.quedan() > 0) linea = linea.append(Component.text("te quedan " + Altar.miles(t.quedan()), Paleta.TEXTO));
            else linea = linea.append(Component.text("ya lo has agotado", Paleta.TENUE));
            lore.add(linea);
        }
        if (escala > 0) lore.add(Marco.tenue("Por encima de " + Altar.miles(escala) + " ya no paga."));
        lore.add(Component.empty());
        lore.add(Marco.tenue("Las Esencias no tienen este tope."));
        lore.add(Marco.tenue("La cuenta de hoy vuelve a cero a"));
        lore.add(Marco.tenue("medianoche; tu saldo no cambia."));
        long libre = 0;
        for (Tramo t : tramos) libre += t.hasta() >= 100_000 ? 0 : t.quedan();
        boolean paga = !tramos.isEmpty() && (escala < 0 || libre > 0);
        return Marco.icono(Material.SUNFLOWER, Component.text("La Aduana de hoy", Paleta.TEXTO), lore, paga);
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
                lore.add(Marco.tiene(Marco.esencias(val.primeraBase()) + " si sales vivo"));
                if (val.primeraSiTasa() > 0) lore.add(Marco.tiene(Marco.esencias(val.primeraSiTasa()) + " más si vendes alguna Reliquia"));
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
            rl.add(Marco.dato("Lo que vendes vale", por));
            rl.add(Component.empty());
            rl.add(Marco.tenue("Sube 1 cada vez que sales con una"));
            rl.add(Marco.tenue("Reliquia de grado II o más."));
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
                lore.add(Marco.tenue("Esta semana aún no has sacado"));
                lore.add(Marco.tenue("nada que vender."));
            } else {
                lore.add(Marco.dato("Esencias", Altar.miles(e)));
                lore.add(Marco.dato("MobCoins", Altar.miles(mc)));
                lore.add(Marco.dato("Reliquias", Altar.miles(rel)));
                lore.add(Marco.dato("Salidas con vida", Altar.miles(salidas)));
            }
        }
        lore.add(Component.empty());
        lore.add(Marco.tenue("Lo que sacas se vende solo al"));
        lore.add(Marco.tenue("cruzar la puerta de salida o al"));
        lore.add(Marco.tenue("usar un Cristal de Regreso."));
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
            case "reliquia-ii", "tasa-ii" -> Material.RESIN_CLUMP;
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
            lore.add(Marco.dato("Paga", Contratos.premio(d)));
            if (d.corto()) lore.add(Marco.tenue("Es corto: se hace en una entrada rápida."));
            lore.add(Marco.tenue(!papel || Contratos.seCobraAlSalir(d) ? "Se cobra al salir vivo." : Pergaminos.COBRO_DENTRO));
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

    // ------------------------------------------------------------------ subvista: reliquias

    private List<ItemStack> encima(Player p) {
        Reliquias rel = hc.reliquias();
        List<ItemStack> out = new ArrayList<>();
        if (rel == null) return out;
        for (ItemStack it : p.getInventory().getContents()) if (rel.es(it)) out.add(it);
        if (rel.es(p.getItemOnCursor())) out.add(p.getItemOnCursor());
        return out;
    }

    private Tasacion.Resumen simular(Player p, List<ItemStack> encima) {
        Tasacion tas = hc.tasacion();
        return tas == null ? null : hc.valor("tasacion", () -> tas.simular(p, encima), null);
    }

    private void vistaReliquias(Inventory inv, Player p) {
        Reliquias rel = hc.reliquias();
        List<ItemStack> encima = encima(p);
        if (encima.isEmpty()) {
            inv.setItem(FILA_A + 4, Marco.icono(Material.BUNDLE, Component.text("No llevas Reliquias", Paleta.TENUE),
                    List.of(Marco.tenue("Salen de los mobs, los cofres"), Marco.tenue("y los minijefes de Calamity.")), false));
            return;
        }
        // Cuantas de cada una, por nombre (grado, especial y minijefe), las altas primero.
        Tasacion.Valores val = Tasacion.Valores.de(hc.cfg());
        Map<String, int[]> cuenta = new LinkedHashMap<>();
        Map<String, ItemStack> muestra = new HashMap<>();
        List<ItemStack> orden = new ArrayList<>(encima);
        orden.sort((a, b) -> Integer.compare(rel.grado(b), rel.grado(a)));
        for (ItemStack it : orden) {
            int g = Math.max(1, Math.min(4, rel.grado(it)));
            String nombre = rel.nombreDe(g, rel.especial(it), rel.minijefe(it));
            cuenta.computeIfAbsent(nombre, k -> new int[]{g, 0})[1] += it.getAmount();
            muestra.putIfAbsent(nombre, it);
        }
        List<ItemStack> cosas = new ArrayList<>();
        for (Map.Entry<String, int[]> e : cuenta.entrySet()) {
            if (cosas.size() >= Marco.COLUMNAS) break;
            int g = e.getValue()[0], n = e.getValue()[1];
            List<Component> lore = new ArrayList<>();
            lore.add(Marco.dato("Grado", Reliquias.ROMANO[g]));
            double es = val.esencias()[g];
            lore.add(Marco.dato("Cada una vale", Marco.numero(es) + (es == 1 ? " Esencia y " : " Esencias y ")
                    + Altar.miles(val.mc()[g]) + " MobCoins"));
            ItemStack icono = new ItemStack(muestra.get(e.getKey()).getType(), Math.max(1, Math.min(64, n)));
            cosas.add(Marco.icono(icono, Component.text(e.getKey() + " ×" + n, Reliquias.AMBAR), lore, false));
        }
        if (cuenta.size() > Marco.COLUMNAS) {
            cosas.set(cosas.size() - 1, Marco.icono(Material.BUNDLE, Component.text("Y más Reliquias", Reliquias.AMBAR),
                    List.of(Marco.tenue("Todas cuentan en lo que valdrían.")), false));
        }
        enFila(inv, FILA_A, cosas, null, null);
        inv.setItem(FILA_B + 4, valdrian(p, encima));
    }

    /** Lo que valdrian si sale ahora: la cuenta de la Tasacion y lo que la Aduana pagaria hoy de sus MC. */
    private ItemStack valdrian(Player p, List<ItemStack> encima) {
        Tasacion.Resumen r = simular(p, encima);
        if (r == null) {
            return Marco.icono(Material.GOLD_NUGGET, Component.text("Lo que valdrían", Paleta.TENUE),
                    List.of(Marco.tenue("Ahora mismo no se puede saber.")), false);
        }
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.dato("Esencias", Altar.miles(r.esencias())));
        lore.add(Marco.dato("MobCoins", Altar.miles(r.mobcoins())));
        Aduana ad = hc.aduana();
        if (ad != null && r.mobcoins() > 0) {
            long paga = (long) Math.floor(Aduana.Cuentas.tramos(ad.mcHoy(p.getUniqueId()), r.mobcoins(), ad.tramos()) + 1e-9);
            if (paga < r.mobcoins()) lore.add(Component.text("Hoy la Aduana solo te pagaría " + Altar.miles(paga) + " MobCoins.", Paleta.CIFRA));
        }
        for (String l : r.lineas()) {
            if (l.startsWith("primera salida")) lore.add(Marco.tenue("Incluye la primera salida de hoy."));
            else if (l.startsWith("exceso")) lore.add(Marco.tenue("Una parte pasa del tope diario y no se paga."));
            else if (l.startsWith("sin valor")) lore.add(Component.text("Alguna no vale nada (caducada o falsa).", Paleta.AVISO));
            else if (l.startsWith("creditos: ")) {
                for (String c : l.substring(10).split(", ")) lore.add(Marco.tiene("+" + MenuAltar.creditoLinea(c.trim(), 1)));
            }
        }
        lore.add(Component.empty());
        lore.add(Marco.tenue("Es lo que cobrarías si sales vivo"));
        lore.add(Marco.tenue("ahora mismo. Si mueres, no cobras."));
        return Marco.icono(Material.GOLD_NUGGET, Component.text("Si sales ahora: ", Paleta.TEXTO)
                .append(Component.text(Altar.miles(r.esencias()) + " E y " + Altar.miles(r.mobcoins()) + " MC", Paleta.CIFRA)), lore, true);
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
                List.of(Marco.dato("Tienes", Altar.miles(saldo)), Marco.dato("Te quedarían", Altar.miles(Math.max(0, saldo - precio)))), false));
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

    private void accion(Player p, Vista v, String accion) {
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
            case "volver" -> {
                Marco.sonar(p, "ui.button.click", 0.45f, 0.8f);
                tarea(() -> abrirVista(p, PORTADA));
            }
            case "depositar" -> {
                // El boton Depositar del Altar vive en el saldo: lo mismo, y solo fuera de Calamity.
                Altar altar = hc.altar();
                if (altar == null) return;
                if (hc.esHardcore(p)) {
                    p.sendMessage(ComandoCalamity.mensaje("En Calamity no se puede: las Esencias pasan a tu saldo cuando sales vivo."));
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

        // La portada: seis botones en una rejilla de 3 x 2, en las columnas 2, 4 y 6.
        List<Integer> botones = List.of(DINERO, CONTRATOS, RELIQUIAS, ADUANA, CAMINO, AYUDA);
        boolean rejilla = true;
        for (int i = 0; i < botones.size(); i++) {
            int b = botones.get(i);
            rejilla &= b / 9 == 1 + i / 3 && b % 9 == 2 + 2 * (i % 3);
        }
        h.ok("filas del mercado: portada en dos filas de tres (columnas 2, 4 y 6)", rejilla);
        h.igual("mercader: columnas de tres cosas", "2,4,6", Marco.columnas(3)[0] + "," + Marco.columnas(3)[1] + "," + Marco.columnas(3)[2]);

        // Abajo: Altar y Forja a los lados, Cerrar/Volver en el centro, alineados con la rejilla.
        h.igual("mercader: abajo Altar, Cerrar/Volver y Forja (38, 40, 42)", "38,40,42", IR_ALTAR + "," + SALIR + "," + IR_FORJA);
        List<Integer> todas = new ArrayList<>(botones);
        todas.addAll(List.of(IR_ALTAR, SALIR, IR_FORJA));
        boolean bien = new HashSet<>(todas).size() == todas.size();
        for (int c : todas) bien &= c >= 0 && c < TAMANO && c % 9 >= 1 && c % 9 <= 7;
        h.ok("mercader: botones sin repetir, dentro de la ventana y lejos de los bordes", bien);
        h.ok("mercader: Cerrar/Volver en la fila de abajo, en el centro", SALIR / 9 == TAMANO / 9 - 1 && SALIR % 9 == 4);

        // Las subvistas: su fila de arriba y la de debajo no pisan el Volver.
        h.ok("subvistas del mercado: filas 1 y 2", FILA_A == 9 && FILA_B == 18 && FILA_B + 8 < SALIR);
        // 1.10: en Contratos, Cambiar debajo de cada contrato (misma columna) y la semana en la fila 3.
        h.ok("contratos: Cambiar justo debajo y la semana en la fila 3, encima de Volver",
                FILA_B == FILA_A + 9 && FILA_C == FILA_B + 9 && FILA_C / 9 == SALIR / 9 - 1 && (FILA_C + 4) % 9 == SALIR % 9);
        h.igual("cuantas: singular", "1 Reliquia", cuantas(1, "Reliquia", "Reliquias"));
        h.igual("cuantas: plural con miles", "1.250 Reliquias", cuantas(1250, "Reliquia", "Reliquias"));
        for (String vista : List.of(PORTADA, V_DINERO, V_CONTRATOS, V_RELIQUIAS)) {
            h.ok("titulo de la vista '" + vista + "' cabe", titulo(vista).ancho() <= Marco.ANCHO_TITULO);
        }
    }
}
