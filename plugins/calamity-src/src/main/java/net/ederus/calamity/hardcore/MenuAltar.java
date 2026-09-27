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
 * Los menus del Altar del Umbral (1.3.0): las paginas Umbral y Forja, la pantalla de confirmar,
 * y "Tu camino" y Grabar (que pintan Camino y Forja), todos atendidos aqui (un listener,
 * distingue por el holder).
 *
 * Como se ve (piezas de Marco): arriba lo que tiene el jugador y "¿Como funciona?"; abajo las
 * pestanas Umbral, Forja y Tasador; en medio, los trueques por secciones con su rotulo a la
 * izquierda. Umbral: Para la expedicion, Llave del Caos y ofrendas, Tu saldo y tu camino, El
 * tablon del Umbral. Forja: Mejoras (con Grabar), El Manto y el Hacha, El Vestigio del Eco, La
 * Guadana de la Parca. Si altar.trueques trae mas de lo que cabe, hay paginas (flechas en las
 * esquinas de abajo).
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

    static final String UMBRAL = Marco.UMBRAL, FORJA = Marco.FORJA, GRABAR = "grabar", CAMINO = "camino",
            CONFIRMAR = "confirmar";
    private static final long ESPERA_MS = 500;
    /** Los iconos de MMOItems se crean una vez cada tanto, no en cada repintado. */
    private static final long ICONOS_MS = 5 * 60_000L;

    /** Lo que dice cada trueque de serie en su icono (el lore de la config, si lo hay, manda). */
    private static final Map<String, List<String>> DESCRIPCION = Map.ofEntries(
            Map.entry("recargar", List.of("Llena el frasco que lleves.")),
            Map.entry("frasco", List.of("Tres tragos. Cada uno, +40 de cordura.")),
            Map.entry("cristal", List.of("Te saca de Calamity si aguantas", "quieto unos segundos.")),
            Map.entry("tintura", List.of("Cura 8 de vida y te endurece", "unos segundos. Dos por compra.")),
            Map.entry("llave", List.of("Una Llave del Caos para la caja", "del spawn. Cuenta en tu tope semanal.")),
            Map.entry("salvoconducto", List.of("Al morir dentro, conservas una pieza.")),
            Map.entry("ofrenda", List.of("Un punto en la tabla de Ofrendas", "del mes. Nada más, y nada menos.")),
            Map.entry("talisman", List.of("+3 de vida. Dentro, la cordura", "baja un 20 % más despacio.")),
            Map.entry("gema", List.of("Se engarza en el Yelmo, la Coraza", "o el Hacha del Heraldo.")),
            Map.entry("grabado", List.of("+1 nivel sobre el tope a un", "encantamiento de equipo vanilla.")),
            Map.entry("ascua", List.of("+1 nivel de mejora al Manto.")),
            Map.entry("mascara-eco", List.of("Casco del Vestigio del Eco.", "Escalón 15.")),
            Map.entry("filo-eco", List.of("Espada del Vestigio del Eco.", "Escalón 15.")),
            Map.entry("guadana", List.of("Escalón 17. La Parca no la soltó:", "se la quitaste.")));

    /**
     * Marca de nuestros inventarios. acciones: casilla -> que hace. foto: el objeto de la mano
     * (Grabar). hoja: la pagina de trueques. trueque y volver: en la pantalla de confirmar, que
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

    /** Una cosa de una seccion: un trueque, o un boton (depositar, camino, horas, grabar, contratos...). */
    record Cosa(Altar.Trueque t, String boton) {
    }

    /** Una seccion de una pagina: su rotulo (icono, nombre, dos lineas) y lo que lleva, en orden. */
    record Seccion(String id, Material icono, String nombre, List<String> texto, List<Cosa> cosas) {
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

    // ------------------------------------------------------------------ secciones

    /** De que seccion es un trueque. */
    static String seccionDe(Altar.Trueque t) {
        if (FORJA.equals(t.pagina())) {
            if (t.pieza() == null) return "mejoras";
            String c = t.credito() == null ? "" : t.credito();
            if (c.startsWith("sello:") || c.equals(Creditos.ERRANTE)) return "manto";
            if (c.equals("marca")) return "eco";
            if (c.equals("fragmento")) return "guadana";
            return "piezas";
        }
        if (t.da().equals("depositar") || t.da().equals("camino")) return "saldo";
        if (t.da().equals("recargar") || t.da().equals("frasco") || t.da().equals("cristal")) return "expedicion";
        String o = t.objeto();
        if ("tintura".equals(o) || "frasco-1".equals(o) || "cristal".equals(o)) return "expedicion";
        return "altar";
    }

    /**
     * Las secciones de una pagina, con sus trueques en el orden de la config y los botones de
     * cada una. Sin Bukkit: el autotest la usa con los trueques de serie. salvoconducto: si sale
     * (apagado no se ensena, DIS M34).
     */
    static List<Seccion> secciones(String pagina, List<Altar.Trueque> todos, boolean salvoconducto) {
        Map<String, Seccion> s = new LinkedHashMap<>();
        if (FORJA.equals(pagina)) {
            s.put("mejoras", new Seccion("mejoras", Material.SMITHING_TABLE, "Mejoras",
                    List.of("Para lo que ya llevas: vida,", "gemas, grabados y el Manto."), new ArrayList<>()));
            s.put("manto", new Seccion("manto", Material.FIRE_CHARGE, "El Manto y el Hacha",
                    List.of("Cada pieza pide el Sello", "de su minijefe."), new ArrayList<>()));
            s.put("eco", new Seccion("eco", Material.ECHO_SHARD, "El Vestigio del Eco",
                    List.of("Piden Marcas de Eco: Lágrimas", "de Eco de cazas válidas."), new ArrayList<>()));
            s.put("guadana", new Seccion("guadana", Material.BELL, "La Guadaña de la Parca",
                    List.of("Pide Fragmentos de Guadaña:", "Campanas de Parca."), new ArrayList<>()));
            s.put("piezas", new Seccion("piezas", Material.NETHERITE_INGOT, "Otras piezas",
                    List.of("Piden créditos de Calamity."), new ArrayList<>()));
        } else {
            s.put("expedicion", new Seccion("expedicion", Material.LANTERN, "Para la expedición",
                    List.of("Lo que te llevas dentro.", "Si mueres, se queda allí."), new ArrayList<>()));
            s.put("altar", new Seccion("altar", Material.VAULT, "Llave del Caos y ofrendas",
                    List.of("Lo que el altar da a cambio", "de las Esencias que ahorras."), new ArrayList<>()));
            s.put("saldo", new Seccion("saldo", Material.LODESTONE, "Tu saldo y tu camino",
                    List.of("Ingresa las Esencias que lleves", "y mira cuánto te falta."), new ArrayList<>()));
            s.put("tablon", new Seccion("tablon", Material.OAK_HANGING_SIGN, "El tablón del Umbral",
                    List.of("Encargos, rankings y lo que", "se vota para Calamity."), new ArrayList<>()));
        }
        boolean depositar = false, camino = false;
        for (Altar.Trueque t : todos) {
            if (!pagina.equals(t.pagina())) continue;
            if ("salvoconducto".equals(t.objeto()) && !salvoconducto) continue;
            Seccion sec = s.get(seccionDe(t));
            if (sec == null) continue;
            if (t.da().equals("depositar")) {
                depositar = true;
                sec.cosas().add(new Cosa(t, "depositar"));
            } else if (t.da().equals("camino")) {
                camino = true;
                sec.cosas().add(new Cosa(t, "camino"));
            } else {
                sec.cosas().add(new Cosa(t, null));
            }
        }
        if (FORJA.equals(pagina)) {
            s.get("mejoras").cosas().add(new Cosa(null, "grabar"));
        } else {
            // Depositar y Tu camino estan siempre, vengan o no en la config (como antes).
            List<Cosa> saldo = s.get("saldo").cosas();
            if (!depositar) saldo.add(0, new Cosa(null, "depositar"));
            if (!camino) saldo.add(new Cosa(null, "camino"));
            saldo.add(new Cosa(null, "horas"));
            for (String b : List.of("contratos", "rankings", "tablero", "encuesta", "deseos")) {
                s.get("tablon").cosas().add(new Cosa(null, b));
            }
        }
        List<Seccion> out = new ArrayList<>();
        for (Seccion sec : s.values()) if (!sec.cosas().isEmpty()) out.add(sec);
        return out;
    }

    static List<Marco.Sitio> reparto(List<Seccion> secciones) {
        return Marco.repartir(tamanos(secciones));
    }

    private static List<Integer> tamanos(List<Seccion> secs) {
        List<Integer> out = new ArrayList<>();
        for (Seccion s : secs) out.add(s.cosas().size());
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

    /** Abre una pagina en una hoja. conSonido: el del Altar al abrirlo (cambiar de pestana suena aparte). */
    void abrir(Player p, String pagina, int hoja, boolean conSonido) {
        if (!altar.activo()) {
            p.sendMessage(ComandoCalamity.mensaje("El altar está en silencio ahora mismo."));
            return;
        }
        String pg = FORJA.equals(pagina) ? FORJA : UMBRAL;
        List<Seccion> secs = secciones(pg, altar.trueques(), hc.cfg().getBoolean("salvoconducto.activo", false));
        int total = Marco.hojas(reparto(secs));
        int h = Math.max(0, Math.min(hoja, total - 1));
        Marca m = new Marca(pg, new HashMap<>(), null, h, null, null);
        String titulo = pg.equals(FORJA) ? "La Forja" : "Altar del Umbral";
        Inventory inv = hc.plugin().getServer().createInventory(m, 54,
                Paleta.ventanaCalamity(titulo + (total > 1 ? " (" + (h + 1) + "/" + total + ")" : "")));
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
        if (top.getHolder() instanceof Marca m && (m.pagina().equals(UMBRAL) || m.pagina().equals(FORJA))) {
            pintar(top, p, m);
        }
    }

    private void pintar(Inventory inv, Player p, Marca m) {
        inv.clear();
        m.acciones().clear();
        List<Altar.Trueque> todos = altar.trueques();
        Marco.cabecera(inv, m.acciones(), hc, p, todos);
        Marco.pestanas(inv, m.acciones(), hc, p, m.pagina());

        List<Seccion> secs = secciones(m.pagina(), todos, hc.cfg().getBoolean("salvoconducto.activo", false));
        List<Marco.Sitio> sitios = reparto(secs);
        int total = Marco.hojas(sitios);
        Altar.Caja caja = altar.caja();
        for (Marco.Sitio s : sitios) {
            if (s.hoja() != m.hoja()) continue;
            Seccion sec = secs.get(s.seccion());
            if (s.indice() < 0) {
                inv.setItem(s.casilla(), Marco.rotulo(sec.icono(), sec.nombre(), sec.texto()));
                continue;
            }
            Cosa c = sec.cosas().get(s.indice());
            if (c.boton() != null) boton(inv, p, m, s.casilla(), c);
            else ponerTrueque(inv, p, m, s.casilla(), c.t(), caja);
        }
        if (m.hoja() > 0) {
            inv.setItem(Marco.ANTERIOR, Marco.flecha(-1, m.hoja(), total));
            m.acciones().put(Marco.ANTERIOR, "hoja:" + (m.hoja() - 1));
        }
        if (m.hoja() < total - 1) {
            inv.setItem(Marco.SIGUIENTE, Marco.flecha(1, m.hoja(), total));
            m.acciones().put(Marco.SIGUIENTE, "hoja:" + (m.hoja() + 1));
        }
        Marco.rellenar(inv);
    }

    // ------------------------------------------------------------------ los botones

    private void boton(Inventory inv, Player p, Marca m, int casilla, Cosa c) {
        UUID u = p.getUniqueId();
        switch (c.boton()) {
            case "depositar" -> {
                // En la zona spawn el Altar vende, pero lo fisico se sigue ingresando al salir vivo:
                // si no, se guardarian las Esencias a mitad de expedicion sin cruzar la puerta.
                Saldo s = hc.saldo();
                int encima = s == null ? 0 : s.encima(p);
                boolean dentro = hc.esHardcore(p);
                Material icono = c.t() != null ? c.t().icono() : Material.GHAST_TEAR;
                List<Component> lore = new ArrayList<>(List.of(Marco.texto("Las Esencias físicas que lleves"),
                        Marco.texto("pasan a tu saldo."), Component.empty()));
                if (dentro) lore.add(Marco.tenue("Aquí dentro se ingresan solas al salir vivo."));
                else lore.add(encima > 0 ? Marco.accion("Clic para ingresar " + Marco.esencias(encima))
                        : Marco.tenue("No llevas ninguna encima."));
                inv.setItem(casilla, Marco.icono(new ItemStack(icono, Math.max(1, Math.min(64, encima))),
                        Component.text("Depositar Esencias", dentro ? Paleta.TENUE : Paleta.DETALLE), lore, encima > 0 && !dentro));
                m.acciones().put(casilla, "depositar");
            }
            case "camino" -> {
                Material icono = c.t() != null ? c.t().icono() : Material.COMPASS;
                inv.setItem(casilla, Marco.icono(icono, Component.text("Tu camino", Paleta.DETALLE), List.of(
                        Marco.texto("Lo que te falta para cada pieza:"), Marco.texto("Sellos, piedad, Marcas y Fragmentos."),
                        Component.empty(), Marco.accion("Clic para verlo")), false));
                m.acciones().put(casilla, "camino");
            }
            case "horas" -> {
                Camino cam = altar.camino();
                double h = cam.horas(u);
                double hito = cam.proximoHito(h);
                List<Component> lore = new ArrayList<>();
                if (hito > 0) lore.add(Marco.dato("Próximo hito", Camino.horasTexto(hito).replace(",0", "") + " h"));
                lore.add(Marco.tenue("Solo cuenta el tiempo en que te mueves."));
                Creditos cr = hc.creditos();
                if (cr != null) lore.add(Marco.tenue("Los Sellos Errantes piden " + Math.round(cr.horasPedidas()) + " h."));
                inv.setItem(casilla, Marco.icono(Material.CLOCK, Component.text("Horas activas: ", Paleta.TEXTO)
                        .append(Component.text(Camino.horasTexto(h), Paleta.CIFRA)), lore, false));
            }
            case "grabar" -> {
                ObjetosCalamity obj = hc.objetos();
                List<Component> lore = new ArrayList<>(List.of(Marco.texto("Con el objeto en la mano y un"),
                        Marco.texto("Grabado encima: +1 nivel sobre el"), Marco.texto("tope a un encantamiento."),
                        Marco.tenue("Solo equipo sin MMOItems."), Component.empty()));
                boolean tiene = false;
                if (obj == null) {
                    lore.add(Marco.tenue("Próximamente."));
                } else {
                    int hechos = hc.datos().getInt(ObjetosCalamity.rutaGrabados(altar.calendario().semana(), u), 0);
                    lore.add(Marco.dato("Esta semana", hechos + " de " + obj.porSemana()));
                    lore.add(Component.empty());
                    tiene = ObjetosCalamity.casillaGrabado(p.getInventory().getContents(), u) >= 0;
                    lore.add(tiene ? Marco.accion("Clic para grabar") : Marco.porQueNo("No llevas ningún Grabado."));
                }
                inv.setItem(casilla, Marco.icono(Material.GRINDSTONE,
                        Component.text("Grabar", obj == null ? Paleta.TENUE : Altar.AMBAR), lore, tiene));
                m.acciones().put(casilla, obj == null ? "gris" : "grabar");
            }
            case "contratos" -> {
                Contratos con = hc.contratos();
                boolean activo = con != null && hc.valor("contratos", con::activo, false) && hc.npcs() != null;
                inv.setItem(casilla, Marco.boton(Material.WRITABLE_BOOK, "Contratos de hoy",
                        List.of("Tres encargos al día.", "Se cobran al salir vivo."),
                        activo ? "Clic para verlos en el Tasador" : "Próximamente.", activo));
                m.acciones().put(casilla, activo ? "contratos" : "gris");
            }
            case "rankings" -> {
                Rankings r = hc.rankings();
                boolean activo = r != null && r.activo() && hc.npcs() != null;
                inv.setItem(casilla, Marco.boton(Material.GOLDEN_HELMET, "Rankings de la semana",
                        List.of("Quién ha sacado más.", "Se pagan el lunes."), activo ? "Clic para verlos" : "Próximamente.", activo));
                m.acciones().put(casilla, activo ? "rankings" : "gris");
            }
            case "tablero" -> {
                Tablero tab = hc.tablero();
                boolean activo = tab != null && hc.valor("tablero", tab::activo, false);
                inv.setItem(casilla, Marco.boton(Material.ITEM_FRAME, "Tablero",
                        List.of("Ecos con botín y Parcas sueltas.", "Sin coordenadas: búscalos."),
                        activo ? "Clic para abrirlo" : "Próximamente.", activo));
                m.acciones().put(casilla, activo ? "tablero" : "gris");
            }
            case "encuesta" -> {
                Encuesta enc = hc.encuesta();
                boolean activo = enc != null && enc.activo();
                List<String> texto = new ArrayList<>(List.of("Tu voto decide qué da Calamity."));
                if (activo && enc.pendiente(u)) texto.add("Tienes una pregunta pendiente.");
                inv.setItem(casilla, Marco.boton(Material.PAPER, "Encuesta y Voto del Botín", texto,
                        activo ? "Clic para votar" : "Próximamente.", activo));
                m.acciones().put(casilla, activo ? "encuesta" : "gris");
            }
            case "deseos" -> {
                Encuesta enc = hc.encuesta();
                boolean activo = enc != null && enc.deseos() != null && enc.deseos().activo();
                inv.setItem(casilla, Marco.boton(Material.NETHER_STAR, "Lista de deseos",
                        List.of("Lo que te gustaría ver", "en el altar."), activo ? "Clic para abrirla" : "Próximamente.", activo));
                m.acciones().put(casilla, activo ? "deseos" : "gris");
            }
            default -> {
            }
        }
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
            case "frasco" -> List.of(c.getInt("frasco.usos", 3) + " tragos. Cada uno, +" + c.getInt("frasco.cordura", 40) + " de cordura.");
            case "cristal" -> List.of("Te saca de Calamity si aguantas", "quieto " + c.getInt("cristal.segundos", 5) + " segundos.");
            case "talisman" -> List.of("+" + c.getInt("talisman.vida", 3) + " de vida. Dentro, la cordura",
                    "baja un " + Math.round((1 - c.getDouble("talisman.drenaje", 0.80)) * 100) + " % más despacio.");
            default -> DESCRIPCION.getOrDefault(t.id(), descripcionPieza(t));
        };
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
            lore.add(Component.text("Reposición: la perdiste dentro y", Paleta.BIEN));
            lore.add(Component.text("sale más barata " + dias + (dias == 1 ? " día más." : " días más."), Paleta.BIEN));
        }
        if (!lore.isEmpty()) lore.add(Component.empty());
        lore.addAll(costes(u, pr, caja));

        List<Component> cupo = cupos(t, u, caja);
        if (!cupo.isEmpty()) {
            lore.add(Component.empty());
            lore.addAll(cupo);
        }
        // Dentro de Calamity (la zona spawn) lo que se compra no llega a la mano: espera fuera (Entregas).
        Entregas en = hc.entregas();
        if (en != null && Entregas.esObjeto(t.objeto()) && !en.recibeYa(p)) {
            lore.add(Component.empty());
            lore.add(Component.text("Aquí dentro te espera fuera:", Paleta.CIFRA));
            lore.add(Component.text("lo recibes al salir de Calamity.", Paleta.CIFRA));
        }
        lore.add(Component.empty());
        boolean gris = "mc".equals(plan.motivo()) && "proximamente".equals(plan.faltan());
        boolean puede = plan.motivo() == null;
        if (puede) lore.add(Marco.accion(FORJA.equals(t.pagina()) ? "Clic para forjar" : "Clic para comprarlo"));
        else if (gris) lore.add(Marco.tenue("Próximamente: el altar aún no cobra MobCoins."));
        else lore.add(Marco.porQueNo(porQueNo(plan)));
        TextColor color = gris ? Paleta.TENUE : FORJA.equals(t.pagina()) ? Altar.AMBAR : Paleta.DETALLE;
        inv.setItem(casilla, Marco.icono(base(t), Component.text(Altar.nombre(t), color), lore, puede));
        m.acciones().put(casilla, "t:" + t.id());
    }

    /** Lo que se dice de una pieza sin descripcion propia: que es, de donde sale su Sello y a cuanto se repone. */
    private List<String> descripcionPieza(Altar.Trueque t) {
        if (t.pieza() == null) return List.of();
        List<String> out = new ArrayList<>();
        if (Set.of("yelmo", "coraza", "grebas", "soleretas").contains(t.pieza())) out.add("Pieza del Manto del Umbral.");
        String c = t.credito() == null ? "" : t.credito();
        if (c.startsWith("sello:")) {
            String de = Forja.delMinijefe(c.substring(6));
            out.add("Su Sello lo suelta " + (de.startsWith("de la ") ? "la " + de.substring(6) : "el " + de.substring(4)) + ".");
        }
        if (t.conReposicion()) {
            out.add("Si la pierdes: " + hc.cfg().getInt("forja.reposicion-dias", 14) + " días para reponerla a " + t.reposEsencias() + " E.");
        }
        return out;
    }

    /** El coste linea a linea: ✔ lo que tiene, ✘ lo que le falta (y cuanto). */
    private static List<Component> costes(UUID u, Altar.Precio pr, Altar.Caja caja) {
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
                out.add(Marco.tiene(que + "  (con un Sello Errante)"));
            } else if ("horas".equals(cr.motivoNoGasta(u, c, n))
                    || (c.startsWith("sello:") && "horas".equals(cr.motivoNoGasta(u, Creditos.ERRANTE, n)))) {
                out.add(Marco.falta(que, "pide " + Math.round(cr.horasPedidas()) + " h activas"));
            } else if (c.startsWith("sello:")) {
                out.add(Marco.falta(que, "no lo tienes"));
            } else {
                out.add(Marco.falta(que, "tienes " + cr.de(u, c)));
            }
        }
        if (out.isEmpty()) out.add(Marco.tiene("Gratis"));
        return out;
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
            out.add(Marco.dato("En el altar", "quedan " + Math.max(0, t.stock() - Altar.stockUsado(caja, t.id())) + " esta semana"));
        }
        if ("tope-llaves".equalsIgnoreCase(t.requisito())) {
            out.add(Marco.dato("Llaves que te caben", String.valueOf(caja.llavesLibres(u))));
        }
        if (t.incremento() > 0) out.add(Marco.tenue("Sube " + t.incremento() + " Esencias con cada una de la semana."));
        if (t.esperaDias() > 0) out.add(Marco.tenue("Una nueva cada " + t.esperaDias() + " días; reponerla no espera."));
        return out;
    }

    /** La razon corta, en el icono. El mensaje completo sale al hacer clic. */
    private static String porQueNo(Altar.Plan plan) {
        Object f = plan.faltan();
        return switch (plan.motivo()) {
            case "esencias" -> "Te faltan " + f + " Esencias.";
            case "mc" -> "Te faltan " + (f instanceof Number n ? Altar.miles(n.longValue()) : f) + " MobCoins.";
            case "credito" -> "horas".equals(f) ? "Tu crédito pide horas activas." : "Te falta el crédito que pide.";
            case "cupo" -> "tope-llaves".equals(f) ? "Tu tope de llaves está lleno."
                    : String.valueOf(f).endsWith("d") ? "Otra en " + String.valueOf(f).replace("d", "") + " días."
                    : "Ya no te queda.";
            case "stock" -> "Agotado esta semana.";
            case "requisito" -> String.valueOf(f).contains("insomne") ? "Solo para un [INSOMNE]." : "Aún no se puede.";
            default -> "Ahora no.";
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
        lore.add(Marco.tenue(Marco.esencias(porTrago) + " por trago que le falte."));
        lore.add(Component.empty());
        if (frasco == null) {
            lore.add(Marco.porQueNo("No llevas ningún frasco."));
            return false;
        }
        int max = hc.cfg().getInt("frasco.usos", 3);
        int tragos = Math.max(0, items.tragos(frasco));
        lore.add(Marco.dato("Tu frasco", tragos + " de " + max + " tragos"));
        if (tragos >= max) {
            lore.add(Component.empty());
            lore.add(Marco.tenue("Ya está lleno."));
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
        lore.add(Marco.accion("Clic para llenarlo"));
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
                Paleta.ventanaCalamity(forja ? "¿Forjarlo?" : "¿Comprarlo?"));

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
            pagos.add(pago(Material.GHAST_TEAR, "−" + Marco.esencias(pr.esencias()), s == null ? -1 : s.de(u), pr.esencias()));
            resumen.add(Marco.esencias(pr.esencias()));
        }
        if (pr.mc() > 0) {
            pagos.add(pago(Material.SUNFLOWER, "−" + Altar.miles(pr.mc()) + " MobCoins", altar.caja().mc(u), pr.mc()));
            resumen.add(Altar.miles(pr.mc()) + " MobCoins");
        }
        if (pr.credito() != null && plan.creditoUsado() != null) {
            String usado = plan.creditoUsado();
            Creditos cr = hc.creditos();
            Material icono = usado.equals("marca") ? Material.ECHO_SHARD : usado.equals("fragmento") ? Material.BELL : Material.FIRE_CHARGE;
            String que = creditoLinea(usado, pr.creditos());
            pagos.add(pago(icono, "−" + que, cr == null ? -1 : cr.de(u, usado), pr.creditos()));
            resumen.add(usado.equals(Creditos.ERRANTE) ? que + " (vale por el " + creditoLinea(pr.credito(), 1) + ")" : que);
        }
        int[] cols = Marco.columnas(pagos.size());
        for (int i = 0; i < pagos.size(); i++) inv.setItem(18 + cols[i], pagos.get(i));

        List<Component> si = new ArrayList<>();
        si.add(Marco.tenue("Pagas:"));
        for (String r : resumen) si.add(Marco.texto("  " + r));
        if (resumen.isEmpty()) si.add(Marco.texto("  nada"));
        if (pr.reposicion()) si.add(Component.text("Precio de reposición.", Paleta.BIEN));
        si.add(Component.empty());
        si.add(Marco.accion(forja ? "Clic para forjarlo" : "Clic para comprarlo"));
        ItemStack boton = Marco.icono(Material.LIME_CONCRETE,
                Component.text("✔ " + (forja ? "Forjar " : "Comprar ") + Altar.nombre(t), Marco.SI), si, false);
        ItemStack cancelar = Marco.icono(Material.RED_CONCRETE, Component.text("✘ Cancelar", Marco.NO), List.of(
                Marco.tenue("Vuelves " + (forja ? "a la Forja" : "al Umbral") + " sin gastar nada.")), false);
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

    /** Lo que se paga en la pantalla de confirmar: "−48 Esencias", lo que tienes y lo que te queda. */
    private static ItemStack pago(Material m, String nombre, long tiene, long cuesta) {
        List<Component> lore = new ArrayList<>();
        if (tiene >= 0) {
            lore.add(Marco.dato("Tienes", Altar.miles(tiene)));
            lore.add(Marco.dato("Te quedan", Altar.miles(Math.max(0, tiene - cuesta))));
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
            p.sendMessage(ComandoCalamity.mensaje("El altar no escucha desde ahí dentro."));
            Marco.sonidoNo(p);
            cerrar(p);
            return;
        }
        if (accion.startsWith("t:")) {
            clicTrueque(p, m, accion.substring(2));
            return;
        }
        if (accion.startsWith("tab:") || accion.startsWith("ir:")) {
            String a = accion.substring(accion.indexOf(':') + 1);
            altar.tarea(() -> Marco.irA(hc, p, a), 1L);
            return;
        }
        if (accion.startsWith("no-tab:")) {
            Marco.pestanaCerrada(p, accion.substring(7));
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
            case "depositar" -> {
                if (hc.esHardcore(p)) {
                    p.sendMessage(ComandoCalamity.mensaje("Aquí dentro no: las Esencias se ingresan solas al salir vivo."));
                    Marco.sonidoNo(p);
                    return;
                }
                altar.depositar(p);
                repintar(p);
            }
            case "camino" -> altar.tarea(() -> altar.camino().abrir(p), 1L);
            case "grabar" -> altar.tarea(() -> altar.forja().abrirGrabar(p), 1L);
            case "contratos" -> altar.tarea(() -> Marco.irA(hc, p, Marco.TASADOR), 1L);
            case "rankings" -> altar.tarea(() -> {
                Npcs n = hc.npcs();
                if (n != null) n.cazador().abrir(p);
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
            case "gris" -> {
                p.sendMessage(ComandoCalamity.mensaje("Eso aún no está abierto. Próximamente."));
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
        // Recargar y depositar no pasan por el motor: se repinta igual.
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

    /** El reparto de casillas de los menus nuevos (Altar, Tasador y Cazador): nada pisado, todo colocado. */
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

        // Cuatro secciones pequenas: una hoja, los rotulos en la columna 0.
        List<Marco.Sitio> r = Marco.repartir(List.of(4, 3, 3, 5));
        h.igual("4 secciones pequenas: una hoja", 1, Marco.hojas(r));
        h.igual("rotulos en 9, 18, 27 y 36", List.of(9, 18, 27, 36), rotulos(r, 0));
        h.ok("4+3+3+5: todo colocado una vez", comprobar(r, List.of(4, 3, 3, 5)) == null);

        // Una seccion de 10: dos filas y un solo rotulo.
        r = Marco.repartir(List.of(10));
        h.igual("10 cosas: dos filas, un rotulo", List.of(9), rotulos(r, 0));
        h.ok("10 cosas: todo colocado una vez", comprobar(r, List.of(10)) == null);

        // Cinco secciones de una fila: la quinta pasa a la segunda hoja.
        r = Marco.repartir(List.of(7, 7, 7, 7, 1));
        h.igual("5 filas: dos hojas", 2, Marco.hojas(r));
        h.igual("la quinta arriba en la hoja 2", List.of(9), rotulos(r, 1));
        h.ok("7+7+7+7+1: todo colocado una vez", comprobar(r, List.of(7, 7, 7, 7, 1)) == null);

        // La que no cabe en lo que queda de hoja pero si en una nueva no se parte.
        r = Marco.repartir(List.of(7, 7, 7, 10));
        h.igual("la de 10 empieza en la hoja 2", List.of(9), rotulos(r, 1));
        h.ok("7+7+7+10: todo colocado una vez", comprobar(r, List.of(7, 7, 7, 10)) == null);

        // Una seccion mas larga que una hoja se parte y repite el rotulo.
        r = Marco.repartir(List.of(3, 30));
        int rot = 0;
        for (Marco.Sitio s : r) if (s.seccion() == 1 && s.indice() < 0) rot++;
        h.igual("30 cosas: un rotulo en cada hoja", 2, rot);
        h.ok("3+30: todo colocado una vez", comprobar(r, List.of(3, 30)) == null);
        h.igual("secciones vacias no salen", 0, Marco.repartir(List.of(0, 0)).size());

        // Los trueques de serie: todos colocados, una hoja por pagina, nada repetido.
        List<Altar.Trueque> serie = Altar.leer(Altar.DEFECTO);
        for (String pg : List.of(UMBRAL, FORJA)) {
            for (boolean salvo : List.of(false, true)) {
                List<Seccion> secs = secciones(pg, serie, salvo);
                List<Marco.Sitio> sitios = reparto(secs);
                String nombre = pg + (salvo ? " con" : " sin") + " salvoconducto";
                h.igual(nombre + ": una hoja", 1, Marco.hojas(sitios));
                String fallo = comprobar(sitios, tamanos(secs));
                h.ok(nombre + ": casillas sin repetir" + (fallo == null ? "" : " (" + fallo + ")"), fallo == null);
                Set<String> puestos = new HashSet<>();
                for (Seccion s : secs) for (Cosa c : s.cosas()) if (c.t() != null) puestos.add(c.t().id());
                for (Altar.Trueque t : serie) {
                    if (!t.pagina().equals(pg)) continue;
                    boolean debe = salvo || !"salvoconducto".equals(t.objeto());
                    h.igual(nombre + ": " + t.id() + (debe ? " colocado" : " no sale"), debe, puestos.contains(t.id()));
                }
            }
        }
        List<String> ids = new ArrayList<>();
        for (Seccion s : secciones(FORJA, serie, false)) ids.add(s.id());
        h.igual("secciones de la Forja", List.of("mejoras", "manto", "eco", "guadana"), ids);
        ids = new ArrayList<>();
        for (Seccion s : secciones(UMBRAL, serie, false)) ids.add(s.id());
        h.igual("secciones del Umbral", List.of("expedicion", "altar", "saldo", "tablon"), ids);

        // Muchos trueques en la config: paginas, y cada uno una vez entre todas.
        List<Map<String, Object>> muchos = new ArrayList<>();
        for (int i = 0; i < 40; i++) muchos.add(Map.of("id", "t" + i, "pagina", "forja", "da", "dar:gema", "esencias", 1));
        List<Seccion> grandes = secciones(FORJA, Altar.leer(muchos), false);
        List<Marco.Sitio> sg = reparto(grandes);
        h.ok("40 trueques: mas de una hoja", Marco.hojas(sg) > 1);
        String fallo = comprobar(sg, tamanos(grandes));
        h.ok("40 trueques: todos colocados una vez" + (fallo == null ? "" : " (" + fallo + ")"), fallo == null);

        // Lo fijo de arriba y de abajo: cada uno en su casilla, fuera del contenido.
        List<Integer> fijas = List.of(Marco.ESENCIAS, Marco.MOBCOINS, Marco.AYUDA, Marco.SELLOS, Marco.MARCAS, Marco.CERRAR,
                Marco.ANTERIOR, Marco.TAB_UMBRAL, Marco.TAB_FORJA, Marco.TAB_TASADOR, Marco.SIGUIENTE);
        boolean fuera = new HashSet<>(fijas).size() == fijas.size();
        for (int f : fijas) fuera &= f < 9 || f >= 45;
        h.ok("cabecera y pestanas: casillas propias, fuera del contenido", fuera);

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

        MenuTasador.autotest(h);
        MenuCazador.autotest(h);
        return h.lineas();
    }

    private static List<Integer> rotulos(List<Marco.Sitio> r, int hoja) {
        List<Integer> out = new ArrayList<>();
        for (Marco.Sitio s : r) if (s.hoja() == hoja && s.indice() < 0) out.add(s.casilla());
        return out;
    }

    /**
     * Null si el reparto esta bien: ninguna casilla dos veces en una hoja, todo en las filas de
     * contenido, rotulos en la columna 0 y cosas en las 1-7, y cada cosa de cada seccion una vez.
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
