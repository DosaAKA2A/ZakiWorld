package net.ederus.lethalworld.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.menu.MenuUtil;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Los menus del Altar del Umbral (DIS M2 [alineado], PLAN sec. 4): dos paginas de 54, Umbral
 * y Forja, mas "Tu camino" y el menu de Grabar, todos atendidos aqui (un listener, distingue
 * por el holder).
 *
 * Umbral: fila 1 informativa (saldo, creditos, horas activas); filas 2-3 los trueques;
 * fila 4 Depositar Esencias y Tu camino; fila 5 Contratos, Tablero, Encuesta y Voto del
 * Botin, Lista de deseos; fila 6 cerrar y la Forja (casilla 53). Forja: consumibles, piezas
 * con Sello y el resto, una fila cada grupo; abajo, volver, Grabar y cerrar.
 *
 * Pensado para Bedrock (Geyser): solo clic izquierdo, sin arrastrar, un clic cada 500 ms
 * (un doble clic no compra dos veces), sin negrita ni cursiva. Lo que no se puede usar se
 * pinta en gris con "Proximamente" (MobCoins sin verificar, modulos apagados o sin hacer).
 * Cada clic que no puede pagar escribe su trueque-fallido (lo hace el Altar).
 *
 * Abrir otro menu o cerrar dentro del evento de clic deja objetos fantasma en el cursor:
 * todo eso va un tick despues.
 */
final class MenuAltar implements Listener {

    static final String UMBRAL = "umbral", FORJA = "forja", GRABAR = "grabar", CAMINO = "camino";
    private static final long ESPERA_MS = 500;
    /* "Proximamente" y lo que no se puede: antes #777777 y #D06A6A, oscuros en el tooltip. */
    private static final TextColor GRIS = Paleta.TENUE;
    private static final TextColor ROJO_SUAVE = Paleta.AVISO;

    private static final int[] UMBRAL_CASILLAS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int[][] FORJA_FILAS = {{10, 11, 12, 13, 14, 15, 16}, {19, 20, 21, 22, 23, 24, 25},
            {28, 29, 30, 31, 32, 33, 34}, {37, 38, 39, 40, 41, 42, 43}};

    /** Lo que dice cada trueque de serie en su icono (el lore de la config, si lo hay, manda). */
    private static final Map<String, List<String>> DESCRIPCION = Map.ofEntries(
            Map.entry("recargar", List.of("Llena el frasco que lleves.", "Una Esencia por trago que le falte.")),
            Map.entry("frasco", List.of("Tres tragos. Cada uno, +40 de cordura.")),
            Map.entry("cristal", List.of("Te saca de Calamity si aguantas", "quieto unos segundos.")),
            Map.entry("tintura", List.of("Cura 8 de vida y te endurece", "unos segundos. Dos por compra.")),
            Map.entry("llave", List.of("Una Llave del Caos para la caja", "del spawn. Cuenta en tu tope semanal.")),
            Map.entry("salvoconducto", List.of("Al morir dentro, conservas una pieza.")),
            Map.entry("ofrenda", List.of("Un punto en la tabla de Ofrendas", "del mes. Nada más, y nada menos.")),
            Map.entry("talisman", List.of("+3 de vida. Dentro, la cordura", "baja un 20 % más despacio.")),
            Map.entry("gema", List.of("Se engarza en el Yelmo, la Coraza", "o el Hacha del Heraldo.")),
            Map.entry("grabado", List.of("+1 nivel sobre el tope a un", "encantamiento de equipo vanilla.")),
            Map.entry("ascua", List.of("+1 nivel de mejora al Manto.", "Cada una de la semana cuesta más.")),
            Map.entry("mascara-eco", List.of("Vestigio del Eco, escalón 15.")),
            Map.entry("filo-eco", List.of("Vestigio del Eco, escalón 15.")),
            Map.entry("guadana", List.of("Escalón 17. La Parca no la soltó:", "se la quitaste.")));

    /** Marca de nuestros inventarios. acciones: casilla -> que hace. foto: el objeto de la mano (Grabar). */
    record Marca(String pagina, Map<Integer, String> acciones, ItemStack foto) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final Hardcore hc;
    private final Altar altar;
    private final Map<UUID, Long> ultimoClic = new HashMap<>();

    MenuAltar(Hardcore hc, Altar altar) {
        this.hc = hc;
        this.altar = altar;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
    }

    void parar() {
        HandlerList.unregisterAll(this);
        // Un menu abierto de un modulo parado se quedaria sin nadie que atienda sus clics.
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Marca) p.closeInventory();
        }
        ultimoClic.clear();
    }

    // ------------------------------------------------------------------ abrir y pintar

    void abrir(Player p, String pagina) {
        if (!altar.activo()) {
            p.sendMessage(ComandoCalamity.mensaje("El altar está en silencio ahora mismo."));
            return;
        }
        String pg = FORJA.equals(pagina) ? FORJA : UMBRAL;
        Marca m = new Marca(pg, new HashMap<>(), null);
        Inventory inv = hc.plugin().getServer().createInventory(m, 54,
                Paleta.ventana("Altar del Umbral · " + (pg.equals(FORJA) ? "Forja" : "Umbral")));
        pintar(inv, p, m);
        p.openInventory(inv);
        Compat.soundPlayers(p.getWorld(), p.getLocation(), pg.equals(FORJA) ? "block.anvil.land" : "block.enchantment_table.use",
                pg.equals(FORJA) ? 0.4f : 0.8f, 0.9f);
    }

    /** Vuelve a pintar la pagina que tenga abierta (tras comprar: saldo, cupos y precios cambian). */
    void repintar(Player p) {
        if (!p.isOnline()) return;
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof Marca m && (m.pagina().equals(UMBRAL) || m.pagina().equals(FORJA))) {
            pintar(top, p, m);
        }
    }

    private void pintar(Inventory inv, Player p, Marca m) {
        inv.clear();
        m.acciones().clear();
        cabecera(inv, p);
        List<Altar.Trueque> todos = altar.trueques();
        if (m.pagina().equals(FORJA)) pintarForja(inv, p, m, todos);
        else pintarUmbral(inv, p, m, todos);
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, MenuUtil.pane());
    }

    /** Fila 1: saldo (y MobCoins), creditos y horas activas. */
    private void cabecera(Inventory inv, Player p) {
        UUID u = p.getUniqueId();
        Saldo s = hc.saldo();
        List<Component> saldo = new ArrayList<>();
        saldo.add(dato("Esencias", s == null ? "?" : String.valueOf(s.de(u))));
        Monedero mon = hc.monedero();
        if (mon != null && mon.disponible()) {
            long mc = mon.saldo(p);
            if (mc >= 0) saldo.add(dato("MobCoins", Altar.miles(mc)));
        }
        int encima = s == null ? 0 : s.encima(p);
        if (encima > 0) {
            saldo.add(MenuUtil.blank());
            saldo.add(MenuUtil.line("Llevas " + encima + " Esencias físicas:"));
            saldo.add(MenuUtil.line("deposítalas abajo."));
        }
        inv.setItem(2, MenuUtil.icon(Material.GHAST_TEAR, Component.text("Tu saldo", Altar.VERDE), saldo, false));

        List<Component> cred = new ArrayList<>();
        Creditos cr = hc.creditos();
        if (cr != null) {
            for (Map.Entry<String, Integer> e : cr.todos(u).entrySet()) {
                String k = e.getKey();
                String etiqueta = k.startsWith("sello:") ? "Sello " + Forja.delMinijefe(k.substring(6))
                        : k.equals(Creditos.ERRANTE) ? "Sello Errante"
                        : k.equals("marca") ? "Marcas de Eco" : k.equals("fragmento") ? "Fragmentos de Guadaña" : k;
                cred.add(dato(etiqueta, e.getValue() + (cr.canjeable(u, k) ? "" : " (aún no)")));
            }
        }
        if (cred.isEmpty()) cred.add(MenuUtil.line("Ninguno todavía."));
        cred.add(MenuUtil.blank());
        cred.add(MenuUtil.line("No se pierden al morir."));
        inv.setItem(4, MenuUtil.icon(Material.NAME_TAG, Component.text("Tus créditos", Altar.VERDE), cred, false));

        Camino cam = altar.camino();
        double h = cam.horas(u);
        double hito = cam.proximoHito(h);
        List<Component> horas = new ArrayList<>();
        horas.add(dato("Horas activas", Camino.horasTexto(h)));
        if (hito > 0) horas.add(dato("Próximo hito", Camino.horasTexto(hito).replace(",0", "") + " h"));
        horas.add(MenuUtil.blank());
        horas.add(MenuUtil.line("Solo cuenta el tiempo en que te mueves."));
        inv.setItem(6, MenuUtil.icon(Material.CLOCK, Component.text("Horas activas", Altar.VERDE), horas, false));
    }

    private void pintarUmbral(Inventory inv, Player p, Marca m, List<Altar.Trueque> todos) {
        boolean salvo = hc.cfg().getBoolean("salvoconducto.activo", false);
        int i = 0;
        Altar.Trueque depositar = null, camino = null;
        for (Altar.Trueque t : todos) {
            if (!t.pagina().equals(UMBRAL)) continue;
            if (t.da().equals("depositar")) {
                depositar = t;
                continue;
            }
            if (t.da().equals("camino")) {
                camino = t;
                continue;
            }
            // Apagado, el Salvoconducto no sale en el altar (DIS M34).
            if ("salvoconducto".equals(t.objeto()) && !salvo) continue;
            if (i >= UMBRAL_CASILLAS.length) break;
            ponerTrueque(inv, p, m, UMBRAL_CASILLAS[i++], t);
        }
        // Fila 4: Depositar y Tu camino.
        inv.setItem(30, MenuUtil.icon(depositar == null ? Material.GHAST_TEAR : depositar.icono(),
                Component.text("Depositar Esencias", Altar.VERDE), List.of(
                        MenuUtil.line("Las Esencias físicas que lleves"), MenuUtil.line("pasan a tu saldo."), MenuUtil.blank(),
                        Component.text("Clic izquierdo para depositarlas.", Altar.VERDE)), false));
        m.acciones().put(30, "depositar");
        inv.setItem(32, MenuUtil.icon(camino == null ? Material.COMPASS : camino.icono(),
                Component.text("Tu camino", Altar.AMBAR), List.of(
                        MenuUtil.line("Lo que te falta para cada pieza:"), MenuUtil.line("Sellos, piedad, Marcas y Fragmentos."),
                        MenuUtil.blank(), Component.text("Clic izquierdo para verlo.", Altar.VERDE)), false));
        m.acciones().put(32, "camino");

        // Fila 5: Contratos, Tablero, Encuesta y Voto del Botin, Lista de deseos.
        // Encendido = el modulo existe y su interruptor esta en true; si no, sale en gris y no
        // se abre un boton que solo contestaria "ahora mismo no".
        Contratos con = hc.contratos();
        Tablero tab = hc.tablero();
        boolean contratos = con != null && hc.valor("contratos", con::activo, false);
        boolean tablero = tab != null && hc.valor("tablero", tab::activo, false);
        Encuesta enc = hc.encuesta();
        boolean encuesta = enc != null && enc.activo();
        boolean deseos = enc != null && enc.deseos() != null && enc.deseos().activo();
        boton(inv, m, 37, Material.WRITABLE_BOOK, "Contratos", List.of("Tres encargos al día.", "Se cobran al salir vivo."),
                contratos, "contratos");
        boton(inv, m, 39, Material.ITEM_FRAME, "Tablero", List.of("Quién ha sacado más esta semana."), tablero, "tablero");
        List<String> encTexto = new ArrayList<>(List.of("Tu voto decide qué da Calamity."));
        if (encuesta && enc.pendiente(p.getUniqueId())) encTexto.add("Tienes una pregunta pendiente.");
        boton(inv, m, 41, Material.PAPER, "Encuesta y Voto del Botín", encTexto, encuesta, "encuesta");
        boton(inv, m, 43, Material.NETHER_STAR, "Lista de deseos", List.of("Lo que te gustaría ver en el altar."), deseos, "deseos");

        // Fila 6: cerrar y la Forja.
        inv.setItem(49, MenuUtil.icon(Material.BARRIER, Component.text("Cerrar", Paleta.AVISO), List.of(), false));
        m.acciones().put(49, "cerrar");
        inv.setItem(53, MenuUtil.icon(Material.ANVIL, Component.text("Forja", Altar.AMBAR), List.of(
                MenuUtil.line("El Manto, el Vestigio, la Guadaña"), MenuUtil.line("y lo que los mejora.")), false));
        m.acciones().put(53, "ir:" + FORJA);
    }

    private void pintarForja(Inventory inv, Player p, Marca m, List<Altar.Trueque> todos) {
        int fila = 0, col = 0, grupoAnterior = -1;
        for (Altar.Trueque t : todos) {
            if (!t.pagina().equals(FORJA)) continue;
            int g = Forja.grupo(t);
            // Un grupo nuevo empieza fila (consumibles, piezas con Sello, el resto).
            if (grupoAnterior >= 0 && g != grupoAnterior && col > 0) {
                fila++;
                col = 0;
            }
            grupoAnterior = g;
            if (col >= 7) {
                fila++;
                col = 0;
            }
            if (fila >= FORJA_FILAS.length) break;
            ponerTrueque(inv, p, m, FORJA_FILAS[fila][col++], t);
        }
        inv.setItem(45, MenuUtil.icon(Material.LECTERN, Component.text("Umbral", Altar.VERDE),
                List.of(MenuUtil.line("Volver a la primera página.")), false));
        m.acciones().put(45, "ir:" + UMBRAL);
        inv.setItem(47, MenuUtil.icon(Material.FLINT, Component.text("Grabar", Altar.AMBAR), altar.forja().estadoGrabar(p), false));
        m.acciones().put(47, "grabar");
        inv.setItem(49, MenuUtil.icon(Material.BARRIER, Component.text("Cerrar", Paleta.AVISO), List.of(), false));
        m.acciones().put(49, "cerrar");
    }

    private void boton(Inventory inv, Marca m, int casilla, Material icono, String nombre, List<String> texto, boolean activo,
                       String accion) {
        List<Component> lore = new ArrayList<>();
        for (String l : texto) lore.add(MenuUtil.line(l));
        lore.add(MenuUtil.blank());
        lore.add(activo ? Component.text("Clic izquierdo para abrirlo.", Altar.VERDE) : Component.text("Próximamente.", GRIS));
        inv.setItem(casilla, MenuUtil.icon(icono, Component.text(nombre, activo ? Altar.VERDE : GRIS), lore, false));
        m.acciones().put(casilla, activo ? accion : "gris");
    }

    private static Component dato(String etiqueta, String valor) {
        return Component.text(etiqueta + ": ", MenuUtil.SOFT).append(Component.text(valor, Paleta.TEXTO));
    }

    /** Un trueque en su casilla: precio, lo que pide, cupo y si se puede comprar ya (o por que no). */
    private void ponerTrueque(Inventory inv, Player p, Marca m, int casilla, Altar.Trueque t) {
        UUID u = p.getUniqueId();
        List<Component> lore = new ArrayList<>();
        List<String> desc = t.lore().isEmpty() ? DESCRIPCION.getOrDefault(t.id(),
                t.pieza() != null ? List.of(Forja.nombrePieza(t.pieza()) + ".") : List.of()) : t.lore();
        for (String l : desc) lore.add(Component.text(l, TextColor.color(0xE0E0E0)));
        if (!desc.isEmpty()) lore.add(MenuUtil.blank());

        if (t.da().equals("recargar")) {
            lore.addAll(estadoRecarga(p));
            inv.setItem(casilla, MenuUtil.icon(t.icono(), Component.text(Altar.nombre(t), Altar.VERDE), lore, false));
            m.acciones().put(casilla, "t:" + t.id());
            return;
        }

        Altar.Caja caja = altar.caja();
        Altar.Plan plan = Altar.revisar(caja, t, u);
        Altar.Precio pr = plan.precio();
        List<String> coste = new ArrayList<>();
        if (pr.esencias() > 0) coste.add(pr.esencias() + " Esencias");
        if (pr.mc() > 0) coste.add(Altar.miles(pr.mc()) + " MobCoins");
        lore.add(dato(pr.reposicion() ? "Reposición" : "Precio", coste.isEmpty() ? "gratis" : String.join(" · ", coste)));
        if (t.incremento() > 0) lore.add(MenuUtil.line("Sube " + t.incremento() + " con cada una esta semana."));
        if (pr.credito() != null) {
            Creditos cr = hc.creditos();
            int tiene = cr == null ? 0 : cr.de(u, pr.credito());
            lore.add(dato("Pide", Forja.nombreCredito(pr.credito(), pr.creditos())));
            lore.add(dato("Tienes", String.valueOf(tiene)
                    + (pr.credito().startsWith("sello:") && cr != null && cr.de(u, Creditos.ERRANTE) > 0
                    ? " · Errantes: " + cr.de(u, Creditos.ERRANTE) : "")));
        }
        if (t.limiteSemana() > 0) lore.add(dato("Esta semana", Altar.usosSemana(caja, u, t.id()) + " de " + t.limiteSemana()));
        if (t.limiteDia() > 0) lore.add(dato("Hoy", Altar.usosDia(caja, u, t.id()) + " de " + t.limiteDia()));
        if (t.stock() > 0) lore.add(dato("Quedan en el altar", String.valueOf(Math.max(0, t.stock() - Altar.stockUsado(caja, t.id())))));
        if ("tope-llaves".equalsIgnoreCase(t.requisito())) {
            lore.add(dato("Llaves que te caben", String.valueOf(caja.llavesLibres(u))));
        }
        if (t.esperaDias() > 0) lore.add(MenuUtil.line("Una nueva cada " + t.esperaDias() + " días; reponerla no espera."));
        lore.add(MenuUtil.blank());

        boolean gris = "mc".equals(plan.motivo()) && "proximamente".equals(plan.faltan());
        boolean puede = plan.motivo() == null;
        lore.add(puede ? Component.text("Clic izquierdo para comprarlo.", Altar.VERDE)
                : Component.text(gris ? "Próximamente." : porQueNo(plan), gris ? GRIS : ROJO_SUAVE));
        TextColor color = gris ? GRIS : t.pagina().equals(FORJA) ? Altar.AMBAR : Altar.VERDE;
        inv.setItem(casilla, MenuUtil.icon(t.icono(), Component.text(Altar.nombre(t), color), lore, puede));
        m.acciones().put(casilla, "t:" + t.id());
    }

    /** La razon corta, en el icono. El mensaje completo sale al hacer clic. */
    private static String porQueNo(Altar.Plan plan) {
        Object f = plan.faltan();
        return switch (plan.motivo()) {
            case "esencias" -> "Te faltan " + f + " Esencias.";
            case "mc" -> "Te faltan " + (f instanceof Number n ? Altar.miles(n.longValue()) : f) + " MobCoins.";
            case "credito" -> "horas".equals(f) ? "Tu crédito pide 48 h activas." : "Te falta el crédito que pide.";
            case "cupo" -> "tope-llaves".equals(f) ? "Tu tope de llaves está lleno."
                    : String.valueOf(f).endsWith("d") ? "Otra en " + String.valueOf(f).replace("d", "") + " días."
                    : "Ya no te queda.";
            case "stock" -> "Agotado esta semana.";
            case "requisito" -> String.valueOf(f).contains("insomne") ? "Solo para un [INSOMNE]." : "Aún no se puede.";
            default -> "Ahora no.";
        };
    }

    /** El frasco que se recargaria y lo que costaria. */
    private List<Component> estadoRecarga(Player p) {
        List<Component> lore = new ArrayList<>();
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
        lore.add(dato("Precio", porTrago + (porTrago == 1 ? " Esencia" : " Esencias") + " por trago"));
        if (frasco == null) {
            lore.add(MenuUtil.blank());
            lore.add(Component.text("No llevas ningún frasco.", ROJO_SUAVE));
            return lore;
        }
        int max = hc.cfg().getInt("frasco.usos", 3);
        int tragos = Math.max(0, items.tragos(frasco));
        lore.add(dato("Tu frasco", tragos + " de " + max + " tragos"));
        lore.add(MenuUtil.blank());
        int coste = (max - tragos) * porTrago;
        lore.add(tragos >= max ? Component.text("Ya está lleno.", GRIS)
                : Component.text("Clic izquierdo: llenarlo por " + coste + (coste == 1 ? " Esencia." : " Esencias."), Altar.VERDE));
        return lore;
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
        // El menu se abrio fuera; si entretanto ha entrado en Calamity (una puerta a su lado), nada.
        if (hc.esHardcore(p) && !m.pagina().equals(CAMINO)) {
            p.sendMessage(ComandoCalamity.mensaje("El altar no escucha desde ahí dentro."));
            cerrar(p);
            return;
        }
        if (accion.startsWith("t:")) {
            altar.comprar(p, accion.substring(2), null, r -> repintar(p));
            // Recargar y depositar no pasan por el motor: se repinta igual.
            altar.tarea(() -> repintar(p), 1L);
            return;
        }
        if (accion.startsWith("ir:")) {
            String a = accion.substring(3);
            altar.tarea(() -> abrir(p, a), 1L);
            return;
        }
        if (accion.startsWith("g:")) {
            altar.forja().grabar(p, accion.substring(2), m.foto());
            altar.tarea(() -> abrir(p, FORJA), 1L);
            return;
        }
        if (accion.startsWith("c:")) {
            altar.camino().clic(p, accion.substring(2));
            return;
        }
        switch (accion) {
            case "cerrar" -> cerrar(p);
            case "depositar" -> {
                altar.depositar(p);
                repintar(p);
            }
            case "camino" -> altar.tarea(() -> altar.camino().abrir(p), 1L);
            case "grabar" -> altar.tarea(() -> altar.forja().abrirGrabar(p), 1L);
            case "contratos" -> altar.tarea(() -> {
                // Los contratos salen por chat: se cierra el altar para que se lean.
                p.closeInventory();
                Contratos con = hc.contratos();
                if (con != null) hc.seguro("contratos", () -> con.mostrar(p, p));
            }, 1L);
            case "tablero" -> altar.tarea(() -> {
                Tablero tab = hc.tablero();
                if (tab != null) hc.seguro("tablero", () -> tab.abrir(p));
            }, 1L);
            case "encuesta" -> altar.tarea(() -> {
                Encuesta enc = hc.encuesta();
                if (enc != null) enc.abrirPendiente(p);
            }, 1L);
            case "deseos" -> altar.tarea(() -> {
                Encuesta enc = hc.encuesta();
                if (enc != null && enc.deseos() != null) enc.deseos().abrir(p);
            }, 1L);
            case "gris" -> p.sendMessage(ComandoCalamity.mensaje("Eso aún no está abierto. Próximamente."));
            default -> {
            }
        }
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
}
