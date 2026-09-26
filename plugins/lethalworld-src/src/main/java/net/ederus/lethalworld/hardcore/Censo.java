package net.ederus.lethalworld.hardcore;

import net.ederus.lethalworld.LethalWorldPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MED sec. 5 · Censo del equipo: que se atreve a traer cada uno a un mundo donde lo pierde todo.
 *
 * Es la preferencia revelada por riesgo del plan de recompensas: lo que alguien tiene y no
 * trae es lo que mas valora, y el escalon medio que se arriesga dice si las recompensas de
 * Calamity estan a la altura del equipo que la gente se juega. Lo usan la telemetria
 * (entra, sale, muere), el Eco (caza valida y grado de la Lagrima), la Racha (tope 7 con
 * equipo alto) y la Forja.
 *
 * Por pieza (yelmo, pechera, grebas, botas, mano, secundaria): material, "TIPO.ID" de
 * MMOItems o "VANILLA:<material>", tier, escalon y encantamientos, mas las marcas de
 * Calamity que cambian su lectura (prestado, copia_eco, ligado, grabado).
 *
 * El escalon sale de hardcore.censo.escalones por el tier de MMOItems (la escala de
 * estrellas de produccion, escala-mmoitems-prod.md) y, si el item no tiene tier o es un
 * tier que la tabla no conoce, del material vanilla por su prefijo (hardcore.censo.vanilla).
 * Las dos tablas tienen su copia aqui por si la config del servidor no las trae.
 *
 * Todo estatico y sin estado: se puede llamar desde cualquier modulo sin pedirle nada a
 * Hardcore, y los autotest lo prueban con items de memoria.
 */
final class Censo {

    /** Una casilla con algo. marcas: prestado, copia_eco, ligado, grabado. */
    record Pieza(String casilla, String material, String mmo, String tier, int escalon, int encantamientos,
                 Set<String> marcas) {

        /** Si cuenta como riesgo real: lo prestado y las copias del Eco no se pierden de verdad. */
        boolean arriesgada() {
            return !marcas.contains("prestado") && !marcas.contains("copia_eco");
        }

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("casilla", casilla);
            m.put("material", material);
            m.put("mmo", mmo);
            m.put("tier", tier);
            m.put("escalon", escalon);
            m.put("encantamientos", encantamientos);
            m.put("marcas", new ArrayList<>(marcas));
            return m;
        }
    }

    record Foto(List<Pieza> piezas, double escalonMedio, int escalonMax, int piezasMmo, int piezasCalamity) {

        /** Lo que va en el suceso (entra, sale, muere, eco): agregados primero, piezas detras. */
        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("escalon_medio", escalonMedio);
            m.put("escalon_max", escalonMax);
            m.put("piezas_mmo", piezasMmo);
            m.put("piezas_calamity", piezasCalamity);
            List<Map<String, Object>> l = new ArrayList<>(piezas.size());
            for (Pieza p : piezas) l.add(p.json());
            m.put("piezas", l);
            return m;
        }

        /**
         * Piezas arriesgadas (sin prestado ni copia_eco) de escalon >= minimo. La Racha lo usa
         * para el tope con equipo (>= 3 piezas de escalon >= 12, PLAN sec. 3.1).
         */
        int piezasConEscalon(int minimo) {
            int n = 0;
            for (Pieza p : piezas) if (p.arriesgada() && p.escalon() >= minimo) n++;
            return n;
        }
    }

    static final List<String> CASILLAS = List.of("yelmo", "pechera", "grebas", "botas", "mano", "secundaria");

    /** Los tiers de la escala de estrellas (escala-mmoitems-prod.md y DIS sec. 4 censo). */
    private static final Map<String, Integer> TIERS = new LinkedHashMap<>();
    /** Escalon de los materiales vanilla por prefijo. */
    private static final Map<String, Integer> VANILLA = new LinkedHashMap<>();

    static {
        Object[][] t = {{"TRASH", 1}, {"COMMON", 2}, {"UNCOMMON", 3}, {"RARE", 4}, {"VERY_RARE", 5},
                {"MAGICAL", 6}, {"EPIC", 6}, {"LEGENDARY", 7}, {"MYTHICAL", 8}, {"UNIQUE", 9}, {"ASCUA", 10},
                {"CELESTIAL", 11}, {"UMBRAL", 12}, {"VIGILIA", 13}, {"PRIVILEGIO", 14}, {"BRASA", 14}, {"ECO", 15},
                {"CALAMIDAD", 16}, {"PARCA", 17}, {"BUNNY", 18}, {"CAZADOR", 18}, {"CORO_ABISAL", 18},
                {"ARAGON", 18}, {"BRUJA", 18}, {"DARKNESS", 18}, {"HERBOLA", 18}, {"KEEPER", 18},
                {"CONEJO_ASESINO", 18}, {"MIMIC", 18}, {"QUIMERA", 18}, {"LEVIATAN", 18}, {"CABRA_GRITONA", 18},
                {"CABALLERO_SEPULCRAL", 18}, {"STORM_RIDER", 18}, {"PRIVILEGIO_FILO", 18},
                {"AUREOLA_DEL_ALBA", 19}, {"CORTEZA_DE_ROTTEN", 19}, {"FUNDICION_DE_KEM_Y_KAM", 19},
                {"HACHA_DE_ROTTEN", 21}, {"SANGRE", 23}, {"FIRMAMENTO", 40}};
        for (Object[] f : t) TIERS.put((String) f[0], (Integer) f[1]);
        VANILLA.put("LEATHER", 0);
        VANILLA.put("GOLDEN", 1);
        VANILLA.put("CHAINMAIL", 1);
        VANILLA.put("IRON", 2);
        VANILLA.put("DIAMOND", 4);
        VANILLA.put("NETHERITE", 6);
    }

    /** Los tiers de Calamity (UMBRAL 12 ... PARCA 17): piezas_calamity. */
    static final int CALAMITY_MIN = 12, CALAMITY_MAX = 17;

    private static final Foto VACIA = new Foto(List.of(), 0, 0, 0, 0);

    private Censo() {
    }

    // ------------------------------------------------------------------- fotos

    static Foto de(Player p) {
        if (p == null) return VACIA;
        PlayerInventory inv = p.getInventory();
        return de(new ItemStack[]{inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots(),
                inv.getItemInMainHand(), inv.getItemInOffHand()});
    }

    /** Casco, pechera, grebas, botas, mano y mano secundaria (lo que guarda la foto del Eco). */
    static Foto de(ItemStack[] seisCasillas) {
        if (seisCasillas == null) return VACIA;
        List<Pieza> piezas = new ArrayList<>(6);
        for (int i = 0; i < CASILLAS.size() && i < seisCasillas.length; i++) {
            Pieza p = pieza(CASILLAS.get(i), seisCasillas[i]);
            if (p != null) piezas.add(p);
        }
        return agregar(piezas);
    }

    /** La pieza de una casilla, o null si esta vacia o no es equipo. */
    static Pieza pieza(String casilla, ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        String mmo = PuenteMmo.enlace(item);
        Material m = item.getType();
        /* Lo que se lleva en la mano no siempre es equipo: una antorcha o un filete en la
         * mano bajarian el escalon medio de alguien que entra con netherita. Solo cuenta lo
         * que es de MMOItems o es armadura, arma o escudo. */
        if (mmo == null && !esEquipo(m)) return null;
        Set<String> marcas = new LinkedHashSet<>();
        if (Marcas.tiene(item, Marcas.PRESTADO)) marcas.add("prestado");
        if (Marcas.tiene(item, Marcas.ECO_COPIA)) marcas.add("copia_eco");
        if (Marcas.tiene(item, Marcas.LIGADO)) marcas.add("ligado");
        if (Marcas.tiene(item, Marcas.GRABADO)) marcas.add("grabado");
        int encantamientos;
        try {
            encantamientos = item.getEnchantments().size();
        } catch (Throwable t) {
            encantamientos = 0;
        }
        return pieza(casilla, m.name(), mmo, PuenteMmo.tier(item), encantamientos, marcas);
    }

    /** Una pieza a partir de sus datos: el autotest la usa con tiers sinteticos. */
    static Pieza pieza(String casilla, String material, String mmo, String tier, int encantamientos,
                       Set<String> marcas) {
        String t = tier == null || tier.isBlank() ? null : tier.toUpperCase(Locale.ROOT);
        return new Pieza(casilla, material, mmo == null ? "VANILLA:" + material : mmo, t,
                escalon(material, t), encantamientos,
                marcas == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(marcas)));
    }

    /** Agregados de MED sec. 5. El medio deja fuera lo prestado y las copias del Eco. */
    static Foto agregar(List<Pieza> piezas) {
        if (piezas == null || piezas.isEmpty()) return VACIA;
        int suma = 0, cuantas = 0, max = 0, mmo = 0, calamity = 0;
        for (Pieza p : piezas) {
            if (p.arriesgada()) {
                suma += p.escalon();
                cuantas++;
            }
            max = Math.max(max, p.escalon());
            if (!p.mmo().startsWith("VANILLA:")) mmo++;
            if (p.tier() != null && p.escalon() >= CALAMITY_MIN && p.escalon() <= CALAMITY_MAX) calamity++;
        }
        double medio = cuantas == 0 ? 0 : Math.round(suma * 100.0 / cuantas) / 100.0;
        return new Foto(List.copyOf(piezas), medio, max, mmo, calamity);
    }

    // ----------------------------------------------------------------- escalon

    static int escalon(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0;
        String t = PuenteMmo.tier(item);
        return escalon(item.getType().name(), t == null ? null : t.toUpperCase(Locale.ROOT));
    }

    /**
     * El escalon por tier y, sin tier conocido, por material. Un tier nuevo que la tabla aun
     * no tenga cae al material en vez de a 0: es lo mas parecido a su poder que se sabe
     * sin leer sus stats, y no hunde el escalon medio de quien lo lleva.
     */
    static int escalon(String material, String tier) {
        ConfigurationSection c = cfg();
        if (tier != null) {
            int v = c == null ? -1 : c.getInt("escalones." + tier, -1);
            if (v < 0) v = TIERS.getOrDefault(tier, -1);
            if (v >= 0) return v;
        }
        if (material == null) return 0;
        int corte = material.indexOf('_');
        if (corte <= 0) return 0;
        String prefijo = material.substring(0, corte);
        int v = c == null ? -1 : c.getInt("vanilla." + prefijo, -1);
        if (v < 0) v = VANILLA.getOrDefault(prefijo, 0);
        return v;
    }

    /** Armadura, arma o escudo: lo que tiene sentido llamar equipo. */
    static boolean esEquipo(Material m) {
        if (m == null) return false;
        String n = m.name();
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS")
                || n.endsWith("_BOOTS") || n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_SPEAR")
                || m == Material.BOW || m == Material.CROSSBOW || m == Material.TRIDENT || m == Material.MACE
                || m == Material.SHIELD || m == Material.ELYTRA;
    }

    /** hardcore.censo, o null sin plugin (pruebas fuera del servidor): se usan las tablas de aqui. */
    private static ConfigurationSection cfg() {
        try {
            return JavaPlugin.getPlugin(LethalWorldPlugin.class).getConfig().getConfigurationSection("hardcore.censo");
        } catch (Throwable t) {
            return null;
        }
    }
}
