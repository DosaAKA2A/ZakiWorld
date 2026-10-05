package net.ederus.calamity.hardcore;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.dungeonloot.Caja;
import net.ederus.edm.dungeonloot.DungeonLootPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calamity 1.11 · El puente con las bovedas de EDM (modulo dungeonloot, /dl).
 *
 * El bloque, la apertura, la llave y el cobro de la llave son de EDM; lo que sale de una boveda de
 * Calamity lo paga Calamity (la Aduana) al oir BovedaAbiertaEvent, porque una Esencia o una Reliquia
 * no puede salir de ninguna parte que no sea Aduana.pagar. Por eso las dos cajas son de "botin
 * externo": EDM las abre aunque su lista este vacia, y lo que un admin meta en su lista desde /dl
 * sale ademas.
 *
 * Las cajas las crea Calamity al arrancar si no existen (asegurar): no hay que montarlas a mano.
 *   calamity_ruinas  Boveda de Ruinas, comun, una apertura por jugador; la abre la Llave del Umbral.
 *   calamity_caida   Boveda Caida, ominosa; la abre la Llave Ominosa (solo el primero: BovedaCaida).
 *
 * Las llaves son las de EDM (marca dungeonloot:llave = id de la caja), y para Calamity son dos objetos
 * mas de Entregas: llave-umbral y llave-ominosa.
 */
final class PuenteBovedas {

    static final String CAJA_RUINAS = "calamity_ruinas";
    static final String CAJA_CAIDA = "calamity_caida";
    static final String LLAVE_UMBRAL = "llave-umbral";
    static final String LLAVE_OMINOSA = "llave-ominosa";
    static final List<String> OBJETOS = List.of(LLAVE_UMBRAL, LLAVE_OMINOSA);

    private PuenteBovedas() {
    }

    /** El modulo de bovedas de EDM, o null (EDM sin el modulo, apagado o anterior a 1.78.1). */
    static DungeonLootPlugin modulo() {
        try {
            if (!(Bukkit.getPluginManager().getPlugin("EDM") instanceof EDMPlugin edm) || !edm.isEnabled()) return null;
            return edm.modulo("dungeonloot") instanceof DungeonLootPlugin d ? d : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** La caja de un objeto de Entregas (llave-umbral, llave-ominosa), o null si no es una llave de boveda. */
    static String cajaDe(String objeto) {
        String o = objeto == null ? "" : objeto.trim().toLowerCase(Locale.ROOT);
        return switch (o) {
            case LLAVE_UMBRAL -> CAJA_RUINAS;
            case LLAVE_OMINOSA -> CAJA_CAIDA;
            default -> null;
        };
    }

    static boolean esLlave(String objeto) {
        return cajaDe(objeto) != null;
    }

    /** "Llave del Umbral", "3 Llaves del Umbral", "una Llave Ominosa"... con la cantidad delante si es mas de una. */
    static String nombre(String objeto, int n) {
        boolean umbral = CAJA_RUINAS.equals(cajaDe(objeto));
        String uno = umbral ? "Llave del Umbral" : "Llave Ominosa";
        String varias = umbral ? "Llaves del Umbral" : "Llaves Ominosas";
        return n == 1 ? uno : n + " " + varias;
    }

    /** n llaves de ese objeto de Entregas, sin ligar; null sin el modulo o si no es una llave. */
    static ItemStack llave(String objeto, int n) {
        String caja = cajaDe(objeto);
        DungeonLootPlugin d = modulo();
        if (caja == null || d == null) return null;
        ItemStack k = d.llave(caja, Math.max(1, Math.min(64, n)));
        // El lore generico de /dl ("Bóveda común", "Abre", "Se gasta") se leia raro: dentro de Calamity
        // la llave lleva la plantilla de los demas objetos. Fijo y sin cifras, para que todas se apilen.
        // Rama lore-items: y el nombre con el degradado del color de su boveda.
        ItemMeta meta = k == null ? null : k.getItemMeta();
        if (meta != null) {
            meta.displayName(tono(objeto).nombre(nombre(objeto, 1)));
            meta.lore(ficha(objeto).lore());
            k.setItemMeta(meta);
        }
        return k;
    }

    /** El tono de cada llave: el color de su boveda (#9FC9D6 la de Ruinas, #C7A6E8 la Caida) hecho degradado. */
    static Paleta.Tono tono(String objeto) {
        return Ficha.tono(CAJA_RUINAS.equals(cajaDe(objeto)) ? "llave-umbral" : "llave-ominosa");
    }

    /** El lore de cada llave, con el tono de su boveda. */
    static Ficha ficha(String objeto) {
        if (CAJA_RUINAS.equals(cajaDe(objeto))) {
            return new Ficha(tono(objeto)).cabecera("Llave", "Bóveda de Ruinas", 0)
                    .historia("Solo gira en las cerraduras que dejaron las ruinas.")
                    .seccion("Abre")
                    .dato("Una <Bóveda de Ruinas>.")
                    .dato("Cada bóveda la abres una sola vez.")
                    .accion("Clic derecho sobre la bóveda.")
                    .hueco().nota("Se queda en la cerradura al usarla.");
        }
        return new Ficha(tono(objeto)).cabecera("Llave", "Bóveda Caída", 0)
                .historia("Late como algo vivo. Pesa más de lo que debería.")
                .seccion("Abre")
                .dato("La <Bóveda Caída>.")
                .dato("Lo de dentro es solo para el primero que llega.")
                .accion("Clic derecho sobre la bóveda.")
                .hueco().nota("Se queda en la cerradura al usarla.");
    }

    /**
     * Rama lore-items · Una llave de boveda que ya circula, con el nombre y el lore de hoy; null si no es una
     * llave de Calamity (o sin el modulo) o si ya los lleva.
     */
    static ItemStack renovada(ItemStack it) {
        DungeonLootPlugin d = modulo();
        if (d == null || it == null || !it.hasItemMeta()) return null;
        String caja = d.cajaDeLlave(it);
        String objeto = CAJA_RUINAS.equals(caja) ? LLAVE_UMBRAL : CAJA_CAIDA.equals(caja) ? LLAVE_OMINOSA : null;
        if (objeto == null) return null;
        return Ficha.renovar(it, tono(objeto).nombre(nombre(objeto, 1)), ficha(objeto).lore());
    }

    /** Si ese objeto es una llave de ese objeto de Entregas (por la marca de EDM, no por el material). */
    static boolean es(ItemStack it, String objeto) {
        String caja = cajaDe(objeto);
        DungeonLootPlugin d = modulo();
        return caja != null && d != null && caja.equals(d.cajaDeLlave(it));
    }

    /**
     * Crea las dos cajas si faltan y les asegura lo que Calamity necesita (tipo, una por jugador y botin
     * externo). Devuelve un resumen para el log, o null sin el modulo.
     */
    static String asegurar() {
        DungeonLootPlugin d = modulo();
        if (d == null) return null;
        Caja r = d.asegurarCaja(CAJA_RUINAS, "Bóveda de Ruinas", Caja.Tipo.COMUN, "Llave del Umbral", 0x9FC9D6, true, true);
        Caja c = d.asegurarCaja(CAJA_CAIDA, "Bóveda Caída", Caja.Tipo.OMINOSA, "Llave Ominosa", 0xC7A6E8, false, true);
        return (r == null ? "sin " + CAJA_RUINAS : CAJA_RUINAS) + ", " + (c == null ? "sin " + CAJA_CAIDA : CAJA_CAIDA);
    }

    // ----------------------------------------------------------------- llaves de mobs y de la Parca

    private static final SecureRandom AZAR = new SecureRandom();

    /**
     * Un destacado muerto por un jugador (Grifo, via normal): con llaves-boveda.destacado de cada llave
     * (Llave del Umbral 3 %), la llave cae al suelo con el resto de lo que suelta. Sin ligar: es botin
     * de dentro y se pierde al morir como todo.
     */
    static void alMorirDestacado(Hardcore hc, Player asesino, List<ItemStack> drops) {
        ConfigurationSection c = hc.cfg();
        if (!c.getBoolean("llaves-boveda.activo", true) || asesino == null || modulo() == null) return;
        Map<String, Double> probs = probs(c, "llaves-boveda.destacado", Map.of(LLAVE_UMBRAL, 0.03));
        for (Map.Entry<String, Double> e : probs.entrySet()) {
            if (AZAR.nextDouble() >= e.getValue()) continue;
            ItemStack k = llave(e.getKey(), 1);
            if (k == null) continue;
            drops.add(k);
            hc.plugin().bitacora().anotar("llave-boveda", "destacado", asesino.getName(), e.getKey());
            telemetria(hc, asesino, "destacado", e.getKey());
        }
    }

    /** Quien cobra una Parca (Parca.pagar): con llaves-boveda.parca (Llave Ominosa 25 %), una por Entregas. */
    static void alCobrarParca(Hardcore hc, OfflinePlayer quien) {
        ConfigurationSection c = hc.cfg();
        if (!c.getBoolean("llaves-boveda.activo", true) || quien == null || modulo() == null) return;
        Entregas en = hc.entregas();
        if (en == null) return;
        Map<String, Double> probs = probs(c, "llaves-boveda.parca", Map.of(LLAVE_OMINOSA, 0.25));
        for (Map.Entry<String, Double> e : probs.entrySet()) {
            if (AZAR.nextDouble() >= e.getValue()) continue;
            if (!en.dar(null, e.getKey(), quien, 1, "parca")) continue;
            telemetria(hc, quien, "parca", e.getKey());
            Player p = quien.getPlayer();
            if (p != null) {
                p.sendMessage(ComandoCalamity.mensaje(net.kyori.adventure.text.Component.text("La Parca dejó caer ")
                        .append(Paleta.detalle("una " + nombre(e.getKey(), 1)))
                        .append(net.kyori.adventure.text.Component.text("."))));
            }
        }
    }

    /** {objeto: prob} de una seccion; las que no son llaves de boveda no cuentan. Sin seccion, las de serie. */
    static Map<String, Double> probs(ConfigurationSection c, String ruta, Map<String, Double> deSerie) {
        ConfigurationSection s = c == null ? null : c.getConfigurationSection(ruta);
        if (s == null) return deSerie;
        Map<String, Double> out = new LinkedHashMap<>();
        for (String k : s.getKeys(false)) {
            if (esLlave(k)) out.put(k.toLowerCase(Locale.ROOT), Math.max(0, Math.min(1, s.getDouble(k, 0))));
        }
        return out;
    }

    private static void telemetria(Hardcore hc, OfflinePlayer quien, String origen, String objeto) {
        Telemetria te = hc.telemetria();
        if (te == null) return;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("origen", origen);
        c.put("objeto", objeto);
        hc.seguro("telemetria", () -> te.suceso("llave-boveda", quien, c));
    }

    // ----------------------------------------------------------------- autotest (puro)

    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        h.igual("llave-umbral abre la caja de ruinas", CAJA_RUINAS, cajaDe("llave-umbral"));
        h.igual("llave-ominosa abre la caida", CAJA_CAIDA, cajaDe("LLAVE-OMINOSA"));
        h.igual("la Llave del Caos no es de boveda", null, cajaDe("llave"));
        h.igual("nombre de una", "Llave del Umbral", nombre("llave-umbral", 1));
        h.igual("nombre de cinco", "5 Llaves del Umbral", nombre("llave-umbral", 5));
        h.igual("nombre ominosa", "2 Llaves Ominosas", nombre("llave-ominosa", 2));
        org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
        h.igual("sin seccion, las de serie", Map.of(LLAVE_UMBRAL, 0.03), probs(y, "llaves-boveda.destacado", Map.of(LLAVE_UMBRAL, 0.03)));
        y.set("llaves-boveda.destacado.llave-umbral", 0.05);
        y.set("llaves-boveda.destacado.libro", 0.5);
        y.set("llaves-boveda.destacado.llave-ominosa", 7);
        Map<String, Double> pr = probs(y, "llaves-boveda.destacado", Map.of());
        h.igual("lo que no es llave de boveda no cuenta", 2, pr.size());
        h.cerca("la probabilidad se queda en 0..1", 1.0, pr.getOrDefault(LLAVE_OMINOSA, -1.0), 1e-9);
        return h.lineas();
    }
}
