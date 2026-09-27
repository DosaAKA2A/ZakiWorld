package net.ederus.lethalworld.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.menu.MenuUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * M32 · La pagina Forja del Altar: lo que tiene de propio frente a un trueque cualquiera
 * (DIS M32, PLAN sec. 4 "Pagina Forja").
 *
 * - Nombres de piezas y creditos para el menu y los mensajes ("el Sello del Custodio de
 *   las Ruinas", "5 Marcas de Eco").
 * - Reposicion: si una pieza del Manto, el Hacha o la Guadana se perdio dentro hace menos
 *   de forja.reposicion-dias (14), vuelve a mitad de Esencias (ObjetosCalamity apunta la
 *   perdida al morir; el motor del Altar aplica el precio y borra la perdida al reponer).
 * - Lo que pasa al forjar: stats.forjas y forja-<pieza> (los hitos Vestigio y Guadana los
 *   leen), telemetria "forja", anuncio a todo el servidor (P-W02) y, con la primera pieza,
 *   el aviso de la vitrina de /flex (sin copiar nada: tocar el almacen de /flex desde otro
 *   plugin es un riesgo de duplicado, ESTUDIO sec. 5.15).
 * - El boton Grabar: menu de una fila con los encantamientos del objeto de la mano que ya
 *   estan en su tope; el nucleo (reglas y +1 nivel) es de ObjetosCalamity.
 */
final class Forja {

    private static final Map<String, String> PIEZAS = new LinkedHashMap<>();
    private static final Map<String, String> CORTAS = new HashMap<>();

    static {
        PIEZAS.put("yelmo", "Yelmo del Manto");
        PIEZAS.put("coraza", "Coraza del Manto");
        PIEZAS.put("grebas", "Grebas del Manto");
        PIEZAS.put("soleretas", "Soleretas del Manto");
        PIEZAS.put("hacha", "Hacha del Heraldo");
        PIEZAS.put("mascara", "Máscara del Eco");
        PIEZAS.put("filo", "Filo del Eco");
        PIEZAS.put("guadana", "Guadaña de la Parca");
        CORTAS.put("yelmo", "Yelmo");
        CORTAS.put("coraza", "Coraza");
        CORTAS.put("grebas", "Grebas");
        CORTAS.put("soleretas", "Soleretas");
        CORTAS.put("hacha", "Hacha");
        CORTAS.put("mascara", "Máscara");
        CORTAS.put("filo", "Filo");
        CORTAS.put("guadana", "Guadaña");
    }

    private final Hardcore hc;
    private final Altar altar;

    Forja(Hardcore hc, Altar altar) {
        this.hc = hc;
        this.altar = altar;
    }

    // ------------------------------------------------------------------ nombres

    static String nombrePieza(String pieza) {
        if (pieza == null) return "";
        String n = PIEZAS.get(pieza.toLowerCase(Locale.ROOT));
        return n != null ? n : Character.toUpperCase(pieza.charAt(0)) + pieza.substring(1);
    }

    static String nombreCorto(String pieza) {
        String n = CORTAS.get(pieza == null ? "" : pieza.toLowerCase(Locale.ROOT));
        return n != null ? n : nombrePieza(pieza);
    }

    /** "del Custodio de las Ruinas", "de la Matriarca Tejedora". */
    static String delMinijefe(String id) {
        String n = Minijefes.nombre(id);
        return id != null && id.toLowerCase(Locale.ROOT).startsWith("matriarca") ? "de la " + n : "del " + n;
    }

    /** El credito dicho para una persona: "el Sello del Custodio de las Ruinas", "5 Marcas de Eco". */
    static String nombreCredito(String tipo, int n) {
        String t = tipo == null ? "" : tipo.toLowerCase(Locale.ROOT);
        if (t.startsWith("sello:")) return "el Sello " + delMinijefe(t.substring(6));
        return switch (t) {
            case Creditos.ERRANTE -> "un Sello Errante";
            case "marca" -> n == 1 ? "1 Marca de Eco" : n + " Marcas de Eco";
            case "fragmento" -> n == 1 ? "1 Fragmento de Guadaña" : n + " Fragmentos de Guadaña";
            default -> n + " " + t;
        };
    }

    /** Si esa pieza se perdio dentro hace menos de "dias" (reposicion abierta). */
    static boolean enReposicion(ConfigurationSection datos, UUID u, String pieza, long ahora, int dias) {
        long perdida = datos.getLong("perdidas." + u + "." + pieza, 0);
        return perdida > 0 && ahora - perdida <= dias * 86_400_000L;
    }

    /** Dias que le quedan a una reposicion abierta (redondeando hacia arriba), 0 si no hay. */
    static int diasReposicion(ConfigurationSection datos, UUID u, String pieza, long ahora, int dias) {
        long perdida = datos.getLong("perdidas." + u + "." + pieza, 0);
        if (perdida <= 0) return 0;
        long queda = perdida + dias * 86_400_000L - ahora;
        return queda <= 0 ? 0 : (int) ((queda + 86_399_999L) / 86_400_000L);
    }

    /** Fila de la pagina Forja: 0 consumibles, 1 piezas con Sello, 2 el resto (Vestigio, Guadana). */
    static int grupo(Altar.Trueque t) {
        if (t.pieza() == null) return 0;
        return t.credito() != null && t.credito().startsWith("sello:") ? 1 : 2;
    }

    // ------------------------------------------------------------ al forjar

    /** Lo que pasa despues de una forja entregada (el motor ya cobro, entrego y apunto forjas.<uuid>.<pieza>). */
    void trasForjar(OfflinePlayer op, Altar.Resultado r) {
        String pieza = r.t().pieza();
        UUID u = op.getUniqueId();
        Estadisticas st = hc.estadisticas();
        boolean primera = st != null && st.de(u, "forjas") == 0;
        if (st != null) {
            st.sumar(u, "forjas", 1);
            st.sumar(u, "forja-" + pieza, 1);
        }
        boolean repos = r.precio().reposicion();
        String sello = r.creditoUsado() != null && (r.creditoUsado().startsWith("sello") || r.creditoUsado().equals(Creditos.ERRANTE))
                ? r.creditoUsado() : "";
        hc.plugin().bitacora().anotar("forja", "forjada", Entregas.nombre(op), pieza, repos ? "reposicion" : "nueva",
                sello.isEmpty() ? "-" : sello);
        Telemetria tel = hc.telemetria();
        if (tel != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("pieza", pieza);
            c.put("sello_usado", sello);
            c.put("reposicion", repos ? "si" : "no");
            hc.seguro("telemetria", () -> tel.suceso("forja", op, c));
        }
        // P-W02 a todo el servidor. La reposicion no se anuncia: no es una pieza nueva.
        if (!repos) {
            hc.plugin().getServer().broadcast(ComandoCalamity.mensaje(Component.text(Entregas.nombre(op), Paleta.DETALLE)
                    .append(Component.text(" ha forjado "))
                    .append(Component.text(nombrePieza(pieza), Altar.AMBAR))
                    .append(Component.text("."))));
        }
        Player p = op.getPlayer();
        if (p != null && primera && !repos) {
            p.sendMessage(ComandoCalamity.mensaje(Component.text("Tu primera pieza de Calamity. ")
                    .append(Component.text("Enséñala en /flex", Altar.AMBAR)
                            .clickEvent(ClickEvent.runCommand("/flex"))
                            .hoverEvent(HoverEvent.showText(Component.text("Abre tu vitrina", Paleta.TEXTO))))
                    .append(Component.text("."))));
        }
        if (p != null) Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.anvil.use", 0.8f, 0.8f);
    }

    // ---------------------------------------------------------------- Grabar

    private ObjetosCalamity objetos() {
        return hc.objetos();
    }

    /**
     * Boton Grabar: con el objeto vanilla en la mano y un Grabado en el inventario, abre el
     * menu de una fila con los encantamientos que ya estan en su tope. Todo lo que lo impide
     * se dice antes de abrir nada.
     */
    void abrirGrabar(Player p) {
        ObjetosCalamity obj = objetos();
        if (obj == null) {
            p.sendMessage(ComandoCalamity.mensaje("La Forja no puede grabar ahora mismo."));
            return;
        }
        UUID u = p.getUniqueId();
        PlayerInventory inv = p.getInventory();
        ItemStack mano = inv.getItemInMainHand();
        String motivo = ObjetosCalamity.casillaGrabado(inv.getContents(), u) < 0 ? "sin-grabado"
                : ObjetosCalamity.motivoNoGraba(mano, u, hc.datos(), altar.calendario().semana(), obj.porSemana(),
                obj.permitidos(), obj.topes());
        if (motivo != null) {
            p.sendMessage(ObjetosCalamity.avisoGrabado(motivo));
            Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.note_block.bass", 0.8f, 0.6f);
            return;
        }
        List<Enchantment> opciones = ObjetosCalamity.grabables(mano, obj.permitidos(), obj.topes());
        Map<Integer, String> acciones = new HashMap<>();
        Inventory menu = hc.plugin().getServer().createInventory(
                new MenuAltar.Marca(MenuAltar.GRABAR, acciones, mano.clone()), 9,
                Paleta.ventana("Forja · Grabar"));
        menu.setItem(0, MenuUtil.icon(Material.FLINT, Component.text("Grabado de Calamidad", Altar.AMBAR), List.of(
                MenuUtil.line("Sube un encantamiento un nivel"),
                MenuUtil.line("por encima de su tope."),
                MenuUtil.blank(),
                MenuUtil.line("Uno por objeto, uno por semana."),
                MenuUtil.line("El objeto queda ligado a ti.")), false));
        int casilla = 2;
        for (Enchantment e : opciones) {
            if (casilla > 7) break;
            int nivel = mano.getEnchantmentLevel(e);
            String n = ObjetosCalamity.nombreEncantamiento(e);
            menu.setItem(casilla, MenuUtil.icon(Material.ENCHANTED_BOOK,
                    Component.text(n + " " + ObjetosCalamity.romano(nivel) + " → " + ObjetosCalamity.romano(nivel + 1), Altar.AMBAR),
                    List.of(MenuUtil.line("Gasta un Grabado."), MenuUtil.blank(),
                            Component.text("Clic izquierdo para grabarlo.", Altar.VERDE)), true));
            acciones.put(casilla, "g:" + e.getKey().getKey());
            casilla++;
        }
        menu.setItem(8, MenuUtil.icon(Material.BARRIER, Component.text("Ahora no", Paleta.AVISO),
                List.of(MenuUtil.line("Vuelve a la Forja.")), false));
        acciones.put(8, "ir:" + MenuAltar.FORJA);
        for (int s = 0; s < 9; s++) if (menu.getItem(s) == null) menu.setItem(s, MenuUtil.pane());
        p.openInventory(menu);
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.grindstone.use", 0.7f, 1.2f);
    }

    /**
     * Clic en un encantamiento del menu Grabar. "foto" es el objeto que tenia en la mano al
     * abrirlo: si ya no es el mismo, no se graba (no se cambia el objeto a mitad).
     */
    void grabar(Player p, String clave, ItemStack foto) {
        ObjetosCalamity obj = objetos();
        if (obj == null) return;
        UUID u = p.getUniqueId();
        PlayerInventory inv = p.getInventory();
        ItemStack mano = inv.getItemInMainHand();
        if (foto == null || !mano.isSimilar(foto) || mano.getAmount() != foto.getAmount()) {
            p.sendMessage(ComandoCalamity.mensaje("Lo que tienes en la mano ha cambiado. Vuelve a empezar."));
            return;
        }
        int casilla = ObjetosCalamity.casillaGrabado(inv.getContents(), u);
        if (casilla < 0) {
            p.sendMessage(ObjetosCalamity.avisoGrabado("sin-grabado"));
            return;
        }
        ItemStack papel = inv.getItem(casilla);
        String id;
        try {
            id = papel.getItemMeta().getPersistentDataContainer().get(Marcas.GRABADO, PersistentDataType.STRING);
        } catch (Throwable otroTipo) {
            id = null;
        }
        Enchantment e = ObjetosCalamity.encantamiento(clave);
        ItemStack copia = mano.clone();
        String semana = altar.calendario().semana();
        String motivo = ObjetosCalamity.grabar(copia, e, id, u, hc.datos(), semana, obj.porSemana(), obj.permitidos(), obj.topes());
        if (motivo != null) {
            p.sendMessage(ObjetosCalamity.avisoGrabado(motivo));
            return;
        }
        // Ya grabado en la copia y contado en la semana: se gasta el Grabado y se cambia el objeto.
        if (papel.getAmount() > 1) papel.setAmount(papel.getAmount() - 1);
        else inv.setItem(casilla, null);
        Entregas ent = hc.entregas();
        if (ent != null) ent.ligar(copia, u);
        else Ligado.ligar(copia, u);
        inv.setItemInMainHand(copia);
        hc.guardarYa();

        int nivel = copia.getEnchantmentLevel(e);
        String material = copia.getType().getKey().getKey();
        hc.plugin().bitacora().anotar("grabado", p.getName(), material,
                ObjetosCalamity.nombreEncantamiento(e) + " " + nivel, id == null ? "-" : id);
        Telemetria tel = hc.telemetria();
        if (tel != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("material", material);
            c.put("encantamiento", e.getKey().getKey());
            c.put("nivel", nivel);
            hc.seguro("telemetria", () -> tel.suceso("grabado", p, c));
        }
        p.sendMessage(ObjetosCalamity.avisoGrabadoHecho(e, nivel));
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.smithing_table.use", 1.0f, 0.9f);
    }

    /** El lore del boton Grabar: lo que hace y cuantos lleva esta semana. */
    List<Component> estadoGrabar(Player p) {
        ObjetosCalamity obj = objetos();
        List<Component> lore = new ArrayList<>();
        lore.add(MenuUtil.line("Con el objeto en la mano y un"));
        lore.add(MenuUtil.line("Grabado en el inventario: +1 nivel"));
        lore.add(MenuUtil.line("sobre el tope a un encantamiento."));
        lore.add(MenuUtil.line("Solo equipo sin MMOItems."));
        lore.add(MenuUtil.blank());
        if (obj == null) {
            lore.add(Component.text("Próximamente.", Paleta.TENUE));
            return lore;
        }
        int hechos = hc.datos().getInt(ObjetosCalamity.rutaGrabados(altar.calendario().semana(), p.getUniqueId()), 0);
        lore.add(Component.text("Esta semana: ", MenuUtil.SOFT)
                .append(Component.text(hechos + " de " + obj.porSemana(), Paleta.CIFRA)));
        boolean tiene = ObjetosCalamity.casillaGrabado(p.getInventory().getContents(), p.getUniqueId()) >= 0;
        lore.add(Component.text(tiene ? "Clic izquierdo para grabar." : "No llevas ningún Grabado.",
                tiene ? Altar.VERDE : MenuUtil.SOFT));
        return lore;
    }
}
