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
 * El menu del Tasador (1.3.0; 1.3.1), el NPC de la antesala. Antes escribia seis lineas en el
 * chat; ahora es una pagina del mismo aire que el Altar (Marco). Arriba tu saldo (clic: ingresa
 * las Esencias fisicas, lo que era el boton Depositar del Altar) y Cerrar; cada fila, un grupo
 * con su banda de color a los lados (en la 1.3.0 era un icono suelto en la columna 0 que no se
 * entendia):
 *
 *  fila 1, Tus cobros (amarilla): lo tasado esta semana, si la primera salida de hoy aun paga,
 *          los premios que te esperan (con boton para recogerlos fuera de Calamity) y la Racha
 *          si esta encendida;
 *  fila 2, La Aduana de hoy (blanca): las MobCoins cobradas hoy y una barra de cristales con lo
 *          que queda en cada tramo (verde al 100 %, amarillo desde el 50 %, naranja por debajo,
 *          gris cobrado);
 *  fila 3, Contratos de hoy (azul): los tres encargos con su barra, y el de la semana. Clic en
 *          uno para cambiarlo (pantalla de confirmar: dice si es gratis o lo que cuesta);
 *  fila 4, Reliquias que llevas (naranja): cuantas de cada una (en el numero de la pila) y lo que
 *          valdrian si sales ahora, con la cuenta de la Tasacion (Tasacion.simular: Racha y
 *          primera salida incluidas) y lo que la Aduana te pagaria hoy de sus MobCoins.
 * Abajo: "¿Como funciona?", el Altar, la Forja y Tu camino (con las horas activas y el proximo
 * hito), que en la 1.3.0 estaban en el Altar y no son comprar.
 *
 * No escribe nada salvo lo que ya hacian sus botones (Entregas.pendientes, Contratos.cambiar,
 * Altar.depositar). Se puede abrir en cualquier sitio: los enlaces al Altar y a la Forja miran
 * la regla del Altar y el deposito solo se hace fuera de Calamity.
 */
final class MenuTasador implements Listener {

    private static final long ESPERA_MS = 500;
    private static final int FILA_COBROS = 9, FILA_ADUANA = 18, FILA_CONTRATOS = 27, FILA_RELIQUIAS = 36;
    /** La fila de abajo: la ayuda, el Altar, la Forja y Tu camino, centrados y con aire. */
    static final int AYUDA = 46, IR_ALTAR = 48, IR_FORJA = 50, IR_CAMINO = 52;
    /** Trozos de la barra de la Aduana (columnas 2-7; en la 1 va el resumen). */
    private static final int TROZOS = 6;

    /** Nuestra ventana. pantalla: "tasador" o "cambiar"; hueco: el contrato que se cambia. */
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
        Vista v = new Vista(Marco.TASADOR, new HashMap<>(), 0);
        Inventory inv = hc.plugin().getServer().createInventory(v, 54, Marco.T_TASADOR.componente());
        pintar(inv, p, v);
        p.openInventory(inv);
        if (conSonido) Marco.sonar(p, "item.book.page_turn", 0.8f, 0.8f);
    }

    private void repintar(Player p) {
        if (!p.isOnline()) return;
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof Vista v && v.pantalla().equals(Marco.TASADOR)) pintar(top, p, v);
    }

    private void pintar(Inventory inv, Player p, Vista v) {
        inv.clear();
        v.acciones().clear();
        Marco.saldo(inv, v.acciones(), hc, p, Marco.SALDO);
        inv.setItem(Marco.CERRAR, Marco.cerrar());
        v.acciones().put(Marco.CERRAR, "cerrar");
        cobros(inv, p, v);
        aduana(inv, p);
        contratos(inv, p, v);
        reliquias(inv, p);
        enlaces(inv, p, v);
        Marco.rellenar(inv);
    }

    /** Pone n iconos en la fila (columnas de Marco) con su banda a los lados. */
    private static void fila(Inventory inv, int base, ItemStack banda, List<ItemStack> cosas) {
        Marco.ponerBanda(inv, base, banda);
        int[] cols = Marco.columnas(Math.min(Marco.COLUMNAS, cosas.size()));
        for (int i = 0; i < cols.length; i++) inv.setItem(base + cols[i], cosas.get(i));
    }

    /**
     * La fila de abajo: "¿Como funciona?", el Altar y la Forja (en gris si desde aqui no
     * escuchan) y Tu camino, que es informativo y se abre en cualquier sitio.
     */
    private void enlaces(Inventory inv, Player p, Vista v) {
        boolean altar = Marco.altarAbierto(hc, p);
        inv.setItem(AYUDA, Marco.ayuda(hc));
        Marco.enlace(inv, v.acciones(), IR_ALTAR, Marco.UMBRAL, Material.ENCHANTING_TABLE, "Altar del Umbral",
                List.of("Frascos, cristales, la Llave", "del Caos y la Ofrenda."), altar);
        Marco.enlace(inv, v.acciones(), IR_FORJA, Marco.FORJA, Material.ANVIL, "La Forja",
                List.of("El Manto, el Vestigio del Eco,", "la Guadaña y sus mejoras."), altar);
        Altar a = hc.altar();
        Camino cam = a == null ? null : a.camino();
        List<Component> lore = new ArrayList<>();
        lore.add(Marco.texto("Lo que te falta para cada pieza:"));
        lore.add(Marco.texto("Sellos, piedad, Marcas y Fragmentos."));
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
        inv.setItem(IR_CAMINO, Marco.icono(Material.COMPASS, Component.text("Tu camino", cam != null ? Paleta.DETALLE : Paleta.TENUE),
                lore, false));
        if (cam != null) v.acciones().put(IR_CAMINO, "camino");
    }

    // ------------------------------------------------------------------ fila 1: tus cobros

    private void cobros(Inventory inv, Player p, Vista v) {
        UUID u = p.getUniqueId();
        List<ItemStack> cosas = new ArrayList<>();

        // Lo tasado esta semana (Estadisticas).
        Estadisticas st = hc.estadisticas();
        List<Component> semana = new ArrayList<>();
        if (st == null) {
            semana.add(Marco.tenue("Ahora mismo no se puede saber."));
        } else {
            long e = st.semana(u, "tasado-esencias"), mc = st.semana(u, "tasado-mc");
            long rel = st.semana(u, "reliquias"), salidas = st.semana(u, "extracciones");
            if (e + mc + rel + salidas == 0) {
                semana.add(Marco.tenue("Esta semana aún no has sacado"));
                semana.add(Marco.tenue("nada que tasar."));
            } else {
                semana.add(Marco.dato("Esencias", Altar.miles(e)));
                semana.add(Marco.dato("MobCoins", Altar.miles(mc)));
                semana.add(Marco.dato("Reliquias", Altar.miles(rel)));
                semana.add(Marco.dato("Salidas vivo", Altar.miles(salidas)));
            }
        }
        semana.add(Component.empty());
        semana.add(Marco.tenue("Se tasa solo al cruzar la puerta"));
        semana.add(Marco.tenue("o al terminar un Cristal de Regreso."));
        cosas.add(Marco.icono(Material.RAW_GOLD, Component.text("Tasado esta semana", Paleta.DETALLE), semana, false));

        // La primera salida del dia (Tasacion apunta el dia en primera-extraccion.<uuid>).
        Tasacion.Valores val = Tasacion.Valores.de(hc.cfg());
        Calendario cal = hc.calendario();
        if (val.primeraBase() > 0 && cal != null) {
            boolean cobrada = cal.dia().equals(hc.datos().getString("primera-extraccion." + u, ""));
            List<Component> lore = new ArrayList<>();
            if (cobrada) {
                lore.add(Marco.tenue("Ya la cobraste hoy."));
                lore.add(Marco.tenue("Mañana vuelve a pagar."));
            } else {
                lore.add(Marco.tiene("+" + Marco.esencias(val.primeraBase()) + " al salir vivo"));
                if (val.primeraSiTasa() > 0) lore.add(Marco.tiene("+" + val.primeraSiTasa() + " más si tasas alguna Reliquia"));
                lore.add(Component.empty());
                lore.add(Marco.tenue("Solo la primera salida de cada día."));
            }
            cosas.add(Marco.icono(Material.DAYLIGHT_DETECTOR, Component.text(cobrada ? "Primera salida: cobrada" : "Primera salida: aún paga",
                    cobrada ? Paleta.TENUE : Paleta.BIEN), lore, !cobrada));
        }

        // Premios pendientes (Entregas: premios-pendientes.<uuid>).
        List<Map<?, ?>> pend = hc.datos().getMapList("premios-pendientes." + u);
        List<Component> lore = new ArrayList<>();
        boolean dentro = hc.esHardcore(p);
        if (pend.isEmpty()) {
            lore.add(Marco.tenue("No te espera nada."));
            lore.add(Component.empty());
            lore.add(Marco.tenue("Lo que no puedes llevar (estás dentro"));
            lore.add(Marco.tenue("o desconectado) espera aquí."));
        } else {
            int n = 0;
            for (Map<?, ?> m : pend) {
                if (n++ >= 6) {
                    lore.add(Marco.tenue("y " + (pend.size() - 6) + " más"));
                    break;
                }
                lore.add(Marco.texto("· " + nombrePendiente(m)));
            }
            lore.add(Component.empty());
            lore.add(dentro ? Marco.tenue("Te llegan al salir de Calamity.") : Marco.accion("Clic para recogerlos"));
        }
        ItemStack cofre = Marco.icono(new ItemStack(Material.CHEST, Math.max(1, Math.min(64, pend.size()))),
                Component.text("Premios pendientes: ", Paleta.TEXTO).append(Component.text(pend.size(), Paleta.CIFRA)),
                lore, !pend.isEmpty() && !dentro);
        cosas.add(cofre);

        // La Racha, si esta encendida.
        Racha racha = hc.racha();
        if (racha != null && racha.activa()) {
            int r = racha.de(u);
            double porPunto = hc.cfg().getDouble("racha.por-punto", 0.10);
            List<Component> rl = new ArrayList<>();
            rl.add(Marco.dato("Al tasar", "×" + Marco.numero(Math.round(Racha.factor(r, racha.tope(null), porPunto) * 100) / 100.0)));
            rl.add(Marco.tenue("Sube 1 por salida con alguna Reliquia"));
            rl.add(Marco.tenue("de grado II o más; morir la pone a 0."));
            cosas.add(Marco.icono(Material.BLAZE_ROD, Component.text("Racha: ", Paleta.TEXTO)
                    .append(Component.text(r, Paleta.CIFRA)), rl, r > 0));
        }

        fila(inv, FILA_COBROS, Marco.banda(Material.YELLOW_STAINED_GLASS_PANE, "Tus cobros",
                List.of("Lo que has sacado esta semana", "y lo que aún te espera.")), cosas);
        int[] cols = Marco.columnas(cosas.size());
        for (int i = 0; i < cosas.size(); i++) {
            if (cosas.get(i) == cofre && !pend.isEmpty()) v.acciones().put(FILA_COBROS + cols[i], "cobrar");
        }
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

    // ------------------------------------------------------------------ fila 2: la Aduana

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

    /** Hasta donde llega la barra: el ultimo tramo que paga y no es "sin tope" (>= 100.000); -1 si no hay. */
    static long escala(List<Tramo> tramos) {
        long max = -1;
        for (Tramo t : tramos) if (t.hasta() < 100_000) max = Math.max(max, t.hasta());
        return max;
    }

    private static Material cristalDe(double factor) {
        if (factor >= 1) return Material.LIME_STAINED_GLASS_PANE;
        if (factor >= 0.5) return Material.YELLOW_STAINED_GLASS_PANE;
        if (factor > 0) return Material.ORANGE_STAINED_GLASS_PANE;
        return Material.RED_STAINED_GLASS_PANE;
    }

    private static TextColor colorDe(double factor) {
        return factor >= 1 ? Paleta.BIEN : factor >= 0.5 ? Paleta.CIFRA : Paleta.MARCA;
    }

    private void aduana(Inventory inv, Player p) {
        Aduana ad = hc.aduana();
        if (ad == null) {
            fila(inv, FILA_ADUANA, bandaAduana(), List.of(Marco.icono(Material.GRAY_DYE,
                    Component.text("La Aduana no está", Paleta.TENUE), List.of(), false)));
            return;
        }
        long hoy = ad.mcHoy(p.getUniqueId());
        List<Tramo> tramos = tramosHoy(hoy, ad.tramos());
        long escala = escala(tramos);

        List<Component> lore = new ArrayList<>();
        lore.add(Marco.dato("Cobradas hoy", Altar.miles(hoy) + " MobCoins"));
        lore.add(Component.empty());
        if (tramos.isEmpty()) {
            lore.add(Marco.tenue("Hoy las MobCoins ya no pagan nada."));
        }
        for (Tramo t : tramos) {
            String cuanto = Marco.porcentaje(t.factor());
            boolean sinTope = t.hasta() >= 100_000;
            Component linea = Component.text("Al " + cuanto + ": ", colorDe(t.factor()));
            if (sinTope) linea = linea.append(Component.text("desde " + Altar.miles(t.desde()) + ", sin tope", Paleta.TEXTO));
            else if (t.quedan() > 0) linea = linea.append(Component.text("te quedan " + Altar.miles(t.quedan()), Paleta.TEXTO));
            else linea = linea.append(Component.text("agotado", Paleta.TENUE));
            lore.add(linea);
        }
        if (escala > 0) lore.add(Marco.tenue("Por encima de " + Altar.miles(escala) + ", nada."));
        lore.add(Component.empty());
        lore.add(Marco.tenue("Solo cuenta las MobCoins: las Esencias"));
        lore.add(Marco.tenue("no tienen tope aquí. Vuelve a cero"));
        lore.add(Marco.tenue("a medianoche."));

        Marco.ponerBanda(inv, FILA_ADUANA, bandaAduana());
        long libre = 0;
        for (Tramo t : tramos) libre += t.hasta() >= 100_000 ? 0 : t.quedan();
        boolean paga = !tramos.isEmpty() && (escala < 0 || libre > 0);
        inv.setItem(FILA_ADUANA + 1, Marco.icono(Material.SUNFLOWER, Component.text("MobCoins de hoy: ", Paleta.TEXTO)
                .append(Component.text(Altar.miles(hoy) + (escala > 0 ? " / " + Altar.miles(escala) : ""), Paleta.CIFRA)), lore, paga));
        // La barra: cada trozo es un sexto de lo que paga el dia; gris lo cobrado, color lo que queda.
        for (int i = 0; i < TROZOS; i++) {
            Material m;
            String nombre;
            if (escala <= 0) {
                m = tramos.isEmpty() ? Material.RED_STAINED_GLASS_PANE : cristalDe(tramos.get(tramos.size() - 1).factor());
                nombre = tramos.isEmpty() ? "Hoy ya no pagan" : "Sin tope";
            } else {
                long fin = escala * (i + 1) / TROZOS, ini = escala * i / TROZOS;
                if (hoy >= fin) {
                    m = Material.GRAY_STAINED_GLASS_PANE;
                    nombre = "Cobrado";
                } else {
                    double f = factorEn(tramos, Math.max(ini, hoy));
                    m = cristalDe(f);
                    nombre = "Paga al " + Marco.porcentaje(f);
                }
            }
            inv.setItem(FILA_ADUANA + 2 + i, Marco.icono(m, Component.text(nombre, Paleta.TEXTO), lore, false));
        }
    }

    private static ItemStack bandaAduana() {
        return Marco.banda(Material.WHITE_STAINED_GLASS_PANE, "La Aduana de hoy", List.of("Lo que Calamity te paga", "en MobCoins cada día."));
    }

    private static double factorEn(List<Tramo> tramos, long pos) {
        for (Tramo t : tramos) if (pos >= t.desde() && pos < t.hasta()) return t.factor();
        return 0;
    }

    // ------------------------------------------------------------------ fila 3: contratos

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

    private void contratos(Inventory inv, Player p, Vista v) {
        UUID u = p.getUniqueId();
        ItemStack banda = Marco.banda(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "Contratos de hoy",
                List.of("Tres encargos al día. Se cobran", "al salir vivo; morir los pierde."));
        Contratos con = hc.contratos();
        if (con == null || !hc.valor("contratos", con::activo, false)) {
            fila(inv, FILA_CONTRATOS, banda, List.of(Marco.icono(Material.GRAY_DYE,
                    Component.text("El Tasador no tiene contratos ahora", Paleta.TENUE), List.of(Marco.tenue("Próximamente.")), false)));
            return;
        }
        List<Contratos.Estado> lista = hc.valor("contratos", () -> con.estados(p), List.of());
        int precio = con.precioCambio(u), gratis = con.cambiosGratis(u);
        List<ItemStack> cosas = new ArrayList<>();
        List<String> acciones = new ArrayList<>();
        for (Contratos.Estado e : lista) {
            if (cosas.size() >= Marco.COLUMNAS - 1) break;
            Contratos.Def d = e.def();
            List<Component> lore = new ArrayList<>();
            lore.add(Marco.barra(e.progreso(), d.objetivo()));
            lore.add(Marco.dato("Paga", Marco.esencias(d.esencias()) + " y " + Altar.miles(d.mobcoins()) + " MobCoins"));
            if (d.corto()) lore.add(Marco.tenue("Corto: para una entrada rápida."));
            lore.add(Component.empty());
            String accion = null;
            if (e.cobrado()) {
                lore.add(Marco.tiene("Cobrado."));
            } else if (e.cumplido()) {
                lore.add(Component.text("● Cumplido: se cobra al salir vivo.", Paleta.CIFRA));
            } else {
                lore.add(Marco.accion(precio == 0 ? "Clic para cambiarlo (gratis)" : "Clic para cambiarlo (" + Marco.esencias(precio) + ")"));
                accion = "c:" + e.hueco();
            }
            TextColor color = e.cobrado() ? Paleta.TENUE : e.cumplido() ? Paleta.BIEN : Paleta.TEXTO;
            Material icono = e.cobrado() ? Material.MAP : iconoContrato(d.evento());
            ItemStack it = Marco.icono(icono, Component.text(d.texto(), color), lore, e.cumplido() && !e.cobrado());
            cosas.add(it);
            acciones.add(accion);
        }
        if (lista.isEmpty()) {
            cosas.add(Marco.icono(Material.PAPER, Component.text("Sin contratos hoy", Paleta.TENUE),
                    List.of(Marco.tenue("Vuelve a mirar al salir de Calamity.")), false));
            acciones.add(null);
        }
        int[] semana = con.semanaDe(u);
        List<Component> sl = new ArrayList<>();
        sl.add(Marco.barra(semana[0], semana[1]));
        sl.add(Marco.tenue("Cobra " + semana[1] + " en la semana y el"));
        sl.add(Marco.tenue("Tasador te da la Llave del Caos."));
        sl.add(Component.empty());
        sl.add(Marco.tenue(gratis > 0 ? "Hoy te queda " + gratis + (gratis == 1 ? " cambio gratis." : " cambios gratis.")
                : "Cambiar uno cuesta " + Marco.esencias(precio) + "."));
        cosas.add(Marco.icono(Material.TRIAL_KEY, Component.text("Contratos de la semana: ", Paleta.TEXTO)
                .append(Component.text(Math.min(semana[0], semana[1]) + "/" + semana[1], Paleta.CIFRA)), sl, semana[0] >= semana[1]));
        acciones.add(null);

        fila(inv, FILA_CONTRATOS, banda, cosas);
        int[] cols = Marco.columnas(cosas.size());
        for (int i = 0; i < cosas.size(); i++) if (acciones.get(i) != null) v.acciones().put(FILA_CONTRATOS + cols[i], acciones.get(i));
    }

    // ------------------------------------------------------------------ fila 4: reliquias

    private void reliquias(Inventory inv, Player p) {
        Reliquias rel = hc.reliquias();
        ItemStack banda = Marco.banda(Material.ORANGE_STAINED_GLASS_PANE, "Reliquias que llevas",
                List.of("Se tasan solas al salir vivo.", "Si mueres dentro, no valen nada."));
        List<ItemStack> encima = new ArrayList<>();
        if (rel != null) {
            for (ItemStack it : p.getInventory().getContents()) if (rel.es(it)) encima.add(it);
            if (rel.es(p.getItemOnCursor())) encima.add(p.getItemOnCursor());
        }
        if (encima.isEmpty()) {
            fila(inv, FILA_RELIQUIAS, banda, List.of(Marco.icono(Material.GRAY_DYE, Component.text("No llevas Reliquias", Paleta.TENUE),
                    List.of(Marco.tenue("Salen de los mobs, los cofres"), Marco.tenue("y los minijefes de dentro.")), false)));
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
        int n = 0;
        for (Map.Entry<String, int[]> e : cuenta.entrySet()) {
            if (n++ >= Marco.COLUMNAS - 1) break;
            int g = e.getValue()[0], cuantas = e.getValue()[1];
            List<Component> lore = new ArrayList<>();
            lore.add(Marco.dato("Grado", Reliquias.ROMANO[g]));
            lore.add(Marco.tenue("Cada una: " + Marco.numero(val.esencias()[g]) + " Esencias y " + val.mc()[g] + " MobCoins"));
            ItemStack icono = new ItemStack(muestra.get(e.getKey()).getType(), Math.max(1, Math.min(64, cuantas)));
            cosas.add(Marco.icono(icono, Component.text(e.getKey() + " ×" + cuantas, Reliquias.AMBAR), lore, false));
        }
        if (cuenta.size() > Marco.COLUMNAS - 1) {
            cosas.set(cosas.size() - 1, Marco.icono(Material.BUNDLE, Component.text("Y más Reliquias", Reliquias.AMBAR),
                    List.of(Marco.tenue("Cuentan todas en lo que valdrían.")), false));
        }
        cosas.add(valdrian(p, encima));
        fila(inv, FILA_RELIQUIAS, banda, cosas);
    }

    /** Lo que valdrian si sale ahora: la cuenta de la Tasacion y lo que la Aduana pagaria hoy de sus MC. */
    private ItemStack valdrian(Player p, List<ItemStack> encima) {
        Tasacion tas = hc.tasacion();
        List<Component> lore = new ArrayList<>();
        if (tas == null) {
            lore.add(Marco.tenue("Ahora mismo no se puede saber."));
            return Marco.icono(Material.GOLD_NUGGET, Component.text("Lo que valdrían", Paleta.TENUE), lore, false);
        }
        Tasacion.Resumen r = hc.valor("tasacion", () -> tas.simular(p, encima), null);
        if (r == null) {
            lore.add(Marco.tenue("Ahora mismo no se puede saber."));
            return Marco.icono(Material.GOLD_NUGGET, Component.text("Lo que valdrían", Paleta.TENUE), lore, false);
        }
        lore.add(Marco.dato("Esencias", Altar.miles(r.esencias())));
        lore.add(Marco.dato("MobCoins", Altar.miles(r.mobcoins())));
        Aduana ad = hc.aduana();
        if (ad != null && r.mobcoins() > 0) {
            long paga = (long) Math.floor(Aduana.Cuentas.tramos(ad.mcHoy(p.getUniqueId()), r.mobcoins(), ad.tramos()) + 1e-9);
            if (paga < r.mobcoins()) lore.add(Component.text("La Aduana hoy te pagaría " + Altar.miles(paga) + ".", Paleta.CIFRA));
        }
        for (String l : r.lineas()) {
            if (l.startsWith("primera salida")) lore.add(Marco.tenue("Con la primera salida de hoy."));
            else if (l.startsWith("exceso")) lore.add(Marco.tenue("Parte pasa del tope del día y no paga."));
            else if (l.startsWith("sin valor")) lore.add(Component.text("Alguna no vale nada (caducada o falsa).", Paleta.AVISO));
            else if (l.startsWith("creditos: ")) {
                for (String c : l.substring(10).split(", ")) lore.add(Marco.tiene("+" + MenuAltar.creditoLinea(c.trim(), 1)));
            }
        }
        lore.add(Component.empty());
        lore.add(Marco.tenue("Si sales vivo ahora mismo."));
        return Marco.icono(Material.GOLD_NUGGET, Component.text("Valdrían: ", Paleta.TEXTO)
                .append(Component.text(Altar.miles(r.esencias()) + " E · " + Altar.miles(r.mobcoins()) + " MC", Paleta.CIFRA)), lore, true);
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
            abrir(p, false);
            return;
        }
        Vista v = new Vista("cambiar", new HashMap<>(), hueco);
        Inventory inv = hc.plugin().getServer().createInventory(v, 45, Marco.T_CAMBIAR.componente());
        int precio = con.precioCambio(p.getUniqueId());
        inv.setItem(13, Marco.icono(iconoContrato(e.def().evento()), Component.text(e.def().texto(), Paleta.TEXTO),
                List.of(Marco.barra(e.progreso(), e.def().objetivo()), Marco.tenue("Lo que llevas de este se pierde.")), false));
        Saldo s = hc.saldo();
        long saldo = s == null ? 0 : s.de(p.getUniqueId());
        inv.setItem(22, precio == 0
                ? Marco.icono(Material.LIME_DYE, Component.text("Gratis", Paleta.BIEN), List.of(Marco.tenue("Es tu cambio gratis de hoy.")), false)
                : Marco.icono(Material.GHAST_TEAR, Component.text("−" + Marco.esencias(precio), Paleta.CIFRA),
                List.of(Marco.dato("Tienes", Altar.miles(saldo)), Marco.dato("Te quedan", Altar.miles(Math.max(0, saldo - precio)))), false));
        ItemStack si = Marco.icono(Material.LIME_CONCRETE, Component.text("✔ Cambiarlo", Marco.SI), List.of(
                Marco.tenue("El Tasador te da otro encargo"), Marco.tenue("para ese hueco."), Component.empty(),
                Marco.accion("Clic para cambiarlo")), false);
        ItemStack no = Marco.icono(Material.RED_CONCRETE, Component.text("✘ Cancelar", Marco.NO),
                List.of(Marco.tenue("Vuelves al Tasador sin cambiar nada.")), false);
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
        if (accion.startsWith("c:")) {
            int hueco = Integer.parseInt(accion.substring(2));
            tarea(() -> abrirCambiar(p, hueco));
            return;
        }
        switch (accion) {
            case "cerrar" -> tarea(() -> {
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof Vista) p.closeInventory();
            });
            case "depositar" -> {
                // El boton Depositar del Altar vive ahora en el saldo: lo mismo, y solo fuera de Calamity.
                Altar altar = hc.altar();
                if (altar == null) return;
                if (hc.esHardcore(p)) {
                    p.sendMessage(ComandoCalamity.mensaje("Aquí dentro no: las Esencias se ingresan solas al salir vivo."));
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
                if (hc.esHardcore(p)) {
                    p.sendMessage(ComandoCalamity.mensaje("Aquí dentro no: te llegan al salir de Calamity."));
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
                tarea(() -> abrir(p, false));
            }
            case "no" -> {
                Marco.sonar(p, "ui.button.click", 0.45f, 0.8f);
                tarea(() -> abrir(p, false));
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
        h.cerca("a 2.500 se paga al 25 %", 0.25, factorEn(t, 2500), 1e-9);
        h.igual("sin tope: escala -1", -1L, escala(tramosHoy(0, List.of(new double[]{999999, 1.0}))));

        // La fila de contratos: tres y el de la semana caen en 1, 3, 5 y 7, entre las bandas.
        int[] c = Marco.columnas(4);
        h.igual("contratos en 1, 3, 5 y 7", "1,3,5,7", c[0] + "," + c[1] + "," + c[2] + "," + c[3]);
        h.ok("filas del Tasador: bandas en la columna 0 de las filas 1-4",
                FILA_COBROS == 9 && FILA_ADUANA == 18 && FILA_CONTRATOS == 27 && FILA_RELIQUIAS == 36);
        h.ok("barra de la Aduana: columnas 2-7", 2 + TROZOS - 1 == Marco.COLUMNAS);

        // Lo fijo del Tasador: el saldo y Cerrar arriba, los cuatro enlaces abajo, sin pisarse.
        List<Integer> fijas = List.of(Marco.SALDO, Marco.CERRAR, AYUDA, IR_ALTAR, IR_FORJA, IR_CAMINO);
        boolean bien = new HashSet<>(fijas).size() == fijas.size();
        for (int f : fijas) bien &= f < 9 || f >= 45;
        h.ok("tasador: saldo, cerrar y enlaces en el marco, sin repetir", bien);
        h.igual("tasador: enlaces de abajo centrados y con aire (1, 3, 5 y 7)", "46,48,50,52",
                AYUDA + "," + IR_ALTAR + "," + IR_FORJA + "," + IR_CAMINO);
    }
}
