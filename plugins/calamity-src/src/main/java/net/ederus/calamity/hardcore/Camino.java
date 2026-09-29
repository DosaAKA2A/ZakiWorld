package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.kyori.adventure.text.Component;
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
 * vistazo. Un icono por pieza, con su nombre de verdad ("Yelmo de Calamidad") y lo que pide:
 * el Sello de su minijefe (y cuantas muertes lleva de las 8 que lo dan seguro), las Marcas de
 * Eco o los Fragmentos que tiene, y arriba las horas activas y el proximo hito.
 * Sale del Tasador (1.3.1; antes, del Altar, que ahora es solo tienda) y de /calamity camino
 * (informativo: se puede mirar en cualquier sitio). Al entrar en Calamity, la barra de accion
 * ensena el credito mas cercano (P-W03).
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
        Subcomandos.calamity().registrar("camino", "lo que te falta para el Manto, el Vestigio, la Guadaña y las Masamune",
                "lethalworld.calamity", this::comando, null);
    }

    private void comando(CommandSender quien, String[] args) {
        if (!(quien instanceof Player p)) {
            quien.sendMessage(ComandoCalamity.mensaje("Solo se puede usar dentro del juego."));
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
                frase = "Te faltan " + falta + (falta == 1 ? " muerte " : " muertes ") + Forja.delMinijefe(id)
                        + " para tener su Sello seguro.";
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
            case "coraza", "mascara", "guadana", "masamune", "crimson" -> "la " + n;
            case "grebas", "soleretas" -> "las " + n;
            default -> "el " + n;
        };
    }

    // ------------------------------------------------------------------ menu

    /**
     * El menu de "Tu camino": 36 casillas con el marco negro de Calamity (Marco), la cabecera con
     * horas e hito, una fila para las piezas con Sello y otra para las de Marcas y Fragmentos (cada
     * una con su banda), y abajo en el centro Volver al Mercado (o Cerrar si no hay Mercado).
     */
    void abrir(Player p) {
        Map<Integer, String> acciones = new HashMap<>();
        Inventory inv = hc.plugin().getServer().createInventory(new MenuAltar.Marca(MenuAltar.CAMINO, acciones, null), 36,
                Marco.T_CAMINO.componente());
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
        cabeza.add(hito > 0 ? dato("Próximo hito", horasTexto(hito).replace(",0", "") + " h") : Marco.tenue("Ya no quedan hitos de horas."));
        cabeza.add(Marco.tenue("Solo cuenta el tiempo en que te mueves."));
        if (cr != null) {
            int errantes = cr.de(u, Creditos.ERRANTE);
            if (errantes > 0) {
                cabeza.add(dato("Sellos Errantes", errantes + (cr.canjeable(u, Creditos.ERRANTE) ? ""
                        : "  (pide " + Math.round(cr.horasPedidas()) + " h activas)")));
            }
        }
        Saldo s = hc.saldo();
        if (s != null) cabeza.add(dato("Saldo", Marco.esencias(s.de(u))));
        cabeza.add(Component.empty());
        cabeza.add(Marco.tenue("Debajo tienes lo que te falta"));
        cabeza.add(Marco.tenue("para cada pieza de la Forja."));
        inv.setItem(4, Marco.icono(Material.COMPASS, Component.text("Tu camino", Paleta.MARCA), cabeza, false));

        List<Paso> deSello = new ArrayList<>(), otros = new ArrayList<>();
        for (Paso paso : pasos()) (paso.t().credito().startsWith("sello:") ? deSello : otros).add(paso);
        fila(inv, acciones, p, 9, Marco.banda(Material.ORANGE_STAINED_GLASS_PANE, "Piezas con Sello",
                List.of("Cada una pide el Sello de su minijefe;", "a las " + piedadMaxima() + " muertes lo tienes seguro.")), deSello);
        fila(inv, acciones, p, 18, Marco.banda(Material.CYAN_STAINED_GLASS_PANE, "Marcas y Fragmentos",
                List.of("El Vestigio del Eco, la Guadaña", "de la Parca y las Masamune.")), otros);
        // Abajo en el centro: Volver al Mercado (de donde se viene) o, sin Mercado, Cerrar.
        int abajo = Marco.abajo(inv.getSize());
        if (hc.npcs() != null) {
            inv.setItem(abajo, Marco.volver("al Mercado"));
            acciones.put(abajo, "ir:" + Marco.TASADOR);
        } else {
            inv.setItem(abajo, Marco.cerrar());
            acciones.put(abajo, "cerrar");
        }
        Marco.rellenar(inv);
    }

    /** Una fila del camino: su banda a los lados y las piezas centradas (como en la Forja), hasta siete. */
    private void fila(Inventory inv, Map<Integer, String> acciones, Player p, int base, org.bukkit.inventory.ItemStack banda,
                      List<Paso> pasos) {
        if (pasos.isEmpty()) return;
        Marco.ponerBanda(inv, base, banda);
        int[] cols = Marco.columnas(Math.min(Marco.COLUMNAS, pasos.size()));
        for (int i = 0; i < cols.length; i++) {
            inv.setItem(base + cols[i], icono(p, pasos.get(i)));
            acciones.put(base + cols[i], "c:" + pasos.get(i).pieza());
        }
    }

    private static Component dato(String etiqueta, String valor) {
        return Marco.dato(etiqueta, valor);
    }

    private org.bukkit.inventory.ItemStack icono(Player p, Paso paso) {
        UUID u = p.getUniqueId();
        Altar.Trueque t = paso.t();
        String c = t.credito();
        Creditos cr = hc.creditos();
        int tiene = cr == null ? 0 : cr.de(u, c);
        List<Component> lore = new ArrayList<>();
        Component nombre = Component.text(Forja.nombrePieza(paso.pieza()), Altar.AMBAR);
        boolean listo;
        if (c.startsWith("sello:")) {
            String id = c.substring(6);
            int pied = piedad(u, id);
            listo = tiene > 0;
            lore.add(Marco.texto("Pide el Sello " + Forja.delMinijefe(id) + "."));
            lore.add(dato("Sello", tiene > 0 ? "sí" + (tiene > 1 ? " (" + tiene + ")" : "") : "no"));
            if (tiene == 0) {
                lore.add(dato("Muertes del minijefe", pied + " de " + piedadMaxima()));
                lore.add(Marco.tenue("A las " + piedadMaxima() + ", el Sello es seguro."));
                if (cr != null && cr.de(u, Creditos.ERRANTE) > 0) lore.add(Marco.tenue("También vale un Sello Errante."));
            }
        } else {
            int pide = Math.max(1, t.creditos());
            listo = tiene >= pide;
            lore.add(Marco.texto("Pide " + Forja.nombreCredito(c, pide) + "."));
            lore.add(dato("Tienes", Math.min(tiene, 999) + " de " + pide));
            if (!listo) lore.add(Marco.tenue("Te faltan " + Forja.nombreCredito(c, pide - tiene) + "."));
        }
        if (t.entregar() != null) {
            // 1.8.0: la Crimson pide ademas entregar la Masamune, que hay que llevar encima al forjarla.
            String pieza = Forja.nombrePieza(t.entregar());
            boolean la = altar.caja().lleva(u, t.entregar());
            listo &= la;
            lore.add(Marco.texto("Y entregar tu " + pieza + "."));
            lore.add(dato(pieza, la ? "la llevas encima" : "no la llevas encima"));
        }
        lore.add(Component.empty());
        lore.add(dato("Precio", Marco.esencias(t.esencias()) + (t.mobcoins() > 0 ? " y " + Altar.miles(t.mobcoins()) + " MobCoins" : "")));
        long forjada = hc.datos().getLong("forjas." + u + "." + paso.pieza(), 0);
        int dias = Forja.diasReposicion(hc.datos(), u, paso.pieza(), System.currentTimeMillis(),
                hc.cfg().getInt("forja.reposicion-dias", 14));
        if (dias > 0) {
            lore.add(Component.text("Reponerla sale más barato " + dias + (dias == 1 ? " día más." : " días más."), Altar.VERDE));
        } else if (forjada > 0) {
            lore.add(Marco.tenue("Ya la forjaste una vez."));
        }
        if (t.esperaDias() > 0 && forjada > 0) {
            long queda = forjada + t.esperaDias() * 86_400_000L - System.currentTimeMillis();
            if (queda > 0 && dias == 0) lore.add(Marco.tenue("Podrás forjar otra en " + ((queda + 86_399_999L) / 86_400_000L) + " días."));
        }
        lore.add(Component.empty());
        lore.add(Component.text(listo ? "Ya tienes lo que pide." : "Todavía no tienes lo que pide.", listo ? Altar.VERDE : Paleta.TENUE));
        return Marco.icono(t.icono(), nombre, lore, listo);
    }

    /** Clic en una pieza: se apunta en la telemetria (que piezas se miran = demanda antes de comprar). */
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
