package net.ederus.lethalworld.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.menu.MenuUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * "Tu camino" (ESTUDIO sec. 5.15, DIS M32): lo que le falta a cada uno para cada pieza, de un
 * vistazo. Un icono por pieza: "Custodio · Yelmo · piedad 5/8 · Sello: no", "Marcas de Eco
 * 3/5 (Mascara)", "Fragmentos 2/7 (Guadana)", y arriba las horas activas y el proximo hito.
 * Sale del boton del Altar y de /calamity camino (informativo: se puede mirar en cualquier
 * sitio). Al entrar en Calamity, la barra de accion ensena el credito mas cercano (P-W03).
 *
 * Existe porque el Manto son semanas de juego y lo que no se ve no se persigue: la encuesta
 * "camino" de MED sec. 4.2 mide si la gente sabe cuanto le falta. La telemetria "camino"
 * apunta que piezas se consultan (demanda antes de la compra).
 *
 * No escribe nada: lee creditos, piedad (Minijefes), horas activas (Horas), forjas y
 * perdidas. Las piezas y lo que pide cada una salen de altar.trueques, asi que si Dosa
 * cambia un precio o un Sello, "Tu camino" lo dice igual.
 */
final class Camino {

    /** Una pieza del camino: la de la Forja y el credito que pide. */
    record Paso(String pieza, Altar.Trueque t) {
    }

    private final Hardcore hc;
    private final Altar altar;

    Camino(Hardcore hc, Altar altar) {
        this.hc = hc;
        this.altar = altar;
        Subcomandos.calamity().registrar("camino", "lo que te falta para el Manto, el Vestigio y la Guadaña",
                "lethalworld.calamity", this::comando, null);
    }

    private void comando(CommandSender quien, String[] args) {
        if (!(quien instanceof Player p)) {
            quien.sendMessage(ComandoCalamity.mensaje("Solo desde el juego."));
            return;
        }
        abrir(p);
    }

    /** Las piezas con credito de la Forja, en el orden de la lista. */
    List<Paso> pasos() {
        List<Paso> out = new ArrayList<>();
        for (Altar.Trueque t : altar.trueques()) {
            if (t.pieza() != null && t.credito() != null) out.add(new Paso(t.pieza(), t));
        }
        return out;
    }

    int piedadMaxima() {
        return Math.max(1, hc.cfg().getInt("reliquias.sello-minijefe.piedad", 8));
    }

    int piedad(UUID u, String minijefe) {
        Minijefes m = hc.minijefes();
        return m == null ? 0 : hc.valor("minijefes", () -> m.piedad(u, minijefe), 0);
    }

    double horas(UUID u) {
        Horas h = hc.horas();
        return h == null ? 0 : hc.valor("horas", () -> h.horasActivas(u), 0.0);
    }

    static String horasTexto(double h) {
        return String.format(Locale.ROOT, "%.1f", h).replace('.', ',');
    }

    /** El umbral del proximo hito de horas activas (hitos.<id>.estadistica: horas-activas), o -1. */
    double proximoHito(double horas) {
        ConfigurationSection s = hc.cfg().getConfigurationSection("hitos");
        if (s == null) return -1;
        double mejor = -1;
        for (String k : s.getKeys(false)) {
            ConfigurationSection h = s.getConfigurationSection(k);
            if (h == null || !h.getBoolean("activo", true)) continue;
            if (!"horas-activas".equals(h.getString("estadistica"))) continue;
            double umbral = h.getDouble("umbral", -1);
            if (umbral > horas && (mejor < 0 || umbral < mejor)) mejor = umbral;
        }
        return mejor;
    }

    /** "Custodio" de "custodio-de-las-ruinas". */
    static String minijefeCorto(String id) {
        String n = Minijefes.nombre(id);
        int esp = n.indexOf(' ');
        return esp > 0 ? n.substring(0, esp) : n;
    }

    // ------------------------------------------------------------------ destello

    /**
     * P-W03 al entrar: el credito mas cercano. Para los Sellos cuenta la piedad (con 8
     * participaciones el Sello cae seguro); para Marcas y Fragmentos, lo que falta para la
     * pieza mas barata que aun no puede pagar. Nada si ya lo tiene todo.
     */
    void destelloAlEntrar(Player p) {
        String texto = masCercano(p.getUniqueId());
        if (texto != null) hc.cordura().destello(p, Component.text(texto, Altar.AMBAR), 4);
    }

    String masCercano(UUID u) {
        Creditos cr = hc.creditos();
        if (cr == null) return null;
        int mejor = Integer.MAX_VALUE;
        String texto = null;
        for (Paso paso : pasos()) {
            String c = paso.t().credito();
            int falta;
            String frase;
            if (c.startsWith("sello:")) {
                if (cr.de(u, c) > 0) continue;
                String id = c.substring(6);
                falta = Math.max(1, piedadMaxima() - piedad(u, id));
                frase = "Te faltan " + falta + (falta == 1 ? " muerte " : " muertes ") + "de " + minijefeCorto(id)
                        + " para el Sello.";
            } else {
                int pide = Math.max(1, paso.t().creditos());
                falta = pide - cr.de(u, c);
                if (falta <= 0) continue;
                frase = "Te faltan " + Forja.nombreCredito(c, falta) + " para " + articulo(paso.pieza()) + ".";
            }
            if (falta < mejor) {
                mejor = falta;
                texto = frase;
            }
        }
        return texto;
    }

    private static String articulo(String pieza) {
        String n = Forja.nombreCorto(pieza);
        return switch (pieza) {
            case "coraza", "mascara", "guadana" -> "la " + n;
            case "grebas", "soleretas" -> "las " + n;
            default -> "el " + n;
        };
    }

    // ------------------------------------------------------------------ menu

    /** El menu de "Tu camino": 36 casillas, un icono por pieza y la cabecera con horas e hito. */
    void abrir(Player p) {
        Map<Integer, String> acciones = new HashMap<>();
        Inventory inv = hc.plugin().getServer().createInventory(new MenuAltar.Marca(MenuAltar.CAMINO, acciones, null), 36,
                Component.text("Altar del Umbral · Tu camino", Altar.NARANJA));
        pintar(inv, p, acciones);
        p.openInventory(inv);
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "item.book.page_turn", 1.0f, 1.0f);
        telemetria(p, "todo");
    }

    private void pintar(Inventory inv, Player p, Map<Integer, String> acciones) {
        UUID u = p.getUniqueId();
        Creditos cr = hc.creditos();
        double h = horas(u);
        double hito = proximoHito(h);
        List<Component> cabeza = new ArrayList<>();
        cabeza.add(dato("Horas activas", horasTexto(h)));
        cabeza.add(hito > 0 ? dato("Próximo hito", horasTexto(hito).replace(",0", "") + " h") : MenuUtil.line("Ya no quedan hitos de horas."));
        if (cr != null) {
            int errantes = cr.de(u, Creditos.ERRANTE);
            if (errantes > 0) {
                cabeza.add(dato("Sellos Errantes", errantes + (cr.canjeable(u, Creditos.ERRANTE) ? ""
                        : "  (con " + Math.round(cr.horasPedidas()) + " h activas)")));
            }
        }
        Saldo s = hc.saldo();
        if (s != null) cabeza.add(dato("Saldo", s.de(u) + " Esencias"));
        cabeza.add(MenuUtil.blank());
        cabeza.add(MenuUtil.line("Cada pieza, lo que te falta."));
        inv.setItem(4, MenuUtil.icon(Material.COMPASS, Component.text("Tu camino", Altar.NARANJA), cabeza, false));

        int[] sellos = {10, 11, 12, 13, 14, 15, 16};
        int[] otros = {20, 22, 24, 19, 21, 23, 25};
        int is = 0, io = 0;
        for (Paso paso : pasos()) {
            boolean deSello = paso.t().credito().startsWith("sello:");
            int casilla;
            if (deSello && is < sellos.length) casilla = sellos[is++];
            else if (!deSello && io < otros.length) casilla = otros[io++];
            else continue;
            inv.setItem(casilla, icono(p, paso));
            acciones.put(casilla, "c:" + paso.pieza());
        }
        if (!hc.esHardcore(p) && altar.activo()) {
            inv.setItem(27, MenuUtil.icon(Material.LECTERN, Component.text("Volver al altar", Altar.NARANJA),
                    List.of(MenuUtil.line("Página Umbral.")), false));
            acciones.put(27, "ir:" + MenuAltar.UMBRAL);
        }
        inv.setItem(31, MenuUtil.icon(Material.BARRIER, Component.text("Cerrar", ComandoCalamity.ROJO), List.of(), false));
        acciones.put(31, "cerrar");
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, MenuUtil.pane());
    }

    private static Component dato(String etiqueta, String valor) {
        return Component.text(etiqueta + ": ", MenuUtil.SOFT).append(Component.text(valor, NamedTextColor.WHITE));
    }

    private org.bukkit.inventory.ItemStack icono(Player p, Paso paso) {
        UUID u = p.getUniqueId();
        Altar.Trueque t = paso.t();
        String c = t.credito();
        Creditos cr = hc.creditos();
        int tiene = cr == null ? 0 : cr.de(u, c);
        List<Component> lore = new ArrayList<>();
        Component nombre;
        boolean listo;
        if (c.startsWith("sello:")) {
            String id = c.substring(6);
            nombre = Component.text(minijefeCorto(id) + " · " + Forja.nombreCorto(paso.pieza()), Altar.AMBAR);
            int pied = piedad(u, id);
            listo = tiene > 0;
            lore.add(dato("Sello", tiene > 0 ? "sí" + (tiene > 1 ? " (" + tiene + ")" : "") : "no"));
            lore.add(dato("Piedad", pied + "/" + piedadMaxima()));
            if (tiene == 0) {
                int falta = Math.max(1, piedadMaxima() - pied);
                lore.add(MenuUtil.line("Te faltan " + falta + (falta == 1 ? " muerte" : " muertes") + " de"));
                lore.add(MenuUtil.line(Minijefes.nombre(id) + " para el Sello seguro."));
                if (cr != null && cr.de(u, Creditos.ERRANTE) > 0) lore.add(MenuUtil.line("O un Sello Errante."));
            }
        } else {
            int pide = Math.max(1, t.creditos());
            String quien = c.equals("marca") ? "Marcas de Eco" : c.equals("fragmento") ? "Fragmentos" : c;
            nombre = Component.text(quien + " · " + Forja.nombreCorto(paso.pieza()), Altar.AMBAR);
            listo = tiene >= pide;
            lore.add(dato(quien, Math.min(tiene, 999) + "/" + pide));
            if (!listo) lore.add(MenuUtil.line("Te faltan " + Forja.nombreCredito(c, pide - tiene) + "."));
        }
        lore.add(MenuUtil.blank());
        lore.add(dato("Forja", t.esencias() + " Esencias" + (t.mobcoins() > 0 ? " · " + Altar.miles(t.mobcoins()) + " MobCoins" : "")));
        long forjada = hc.datos().getLong("forjas." + u + "." + paso.pieza(), 0);
        int dias = Forja.diasReposicion(hc.datos(), u, paso.pieza(), System.currentTimeMillis(),
                hc.cfg().getInt("forja.reposicion-dias", 14));
        if (dias > 0) {
            lore.add(Component.text("Reposición abierta: " + dias + (dias == 1 ? " día" : " días") + ".", Altar.VERDE));
        } else if (forjada > 0) {
            lore.add(MenuUtil.line("Ya la forjaste una vez."));
        }
        if (t.esperaDias() > 0 && forjada > 0) {
            long queda = forjada + t.esperaDias() * 86_400_000L - System.currentTimeMillis();
            if (queda > 0 && dias == 0) lore.add(MenuUtil.line("Otra nueva en " + ((queda + 86_399_999L) / 86_400_000L) + " días."));
        }
        lore.add(MenuUtil.blank());
        lore.add(Component.text(listo ? "Ya tienes lo que pide." : "Aún no.", listo ? Altar.VERDE : MenuUtil.SOFT));
        return MenuUtil.icon(t.icono(), nombre, lore, listo);
    }

    /** Clic en una pieza: se apunta (demanda) y se dice en una linea lo que falta. */
    void clic(Player p, String pieza) {
        telemetria(p, pieza);
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "ui.button.click", 0.6f, 1.2f);
    }

    private void telemetria(Player p, String pieza) {
        Telemetria tel = hc.telemetria();
        if (tel == null) return;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("pieza", pieza);
        hc.seguro("telemetria", () -> tel.suceso("camino", p, c));
    }
}
