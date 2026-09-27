package net.ederus.calamity.hardcore;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * M6 · Botin de los cofres de estructura (DIS M6 [alineado], PLAN sec. 3.2).
 *
 * Dos cosas distintas:
 * - Filtro vanilla, SIEMPRE: los cofres de Calamity no dan netherita ni manzanas de notch.
 *   Calamity no puede ser la forma rapida de sacar lo que en el Survival cuesta semanas.
 * - Lo que se anade (Esencias, Reliquias, Cristal, Tintura, Frasco), solo con un jugador que
 *   abre el cofre y solo los primeros cofres-pagados-dia (10) del dia: el saqueador vive de
 *   explorar lejos, no de abrir cien cofres de una aldea.
 *
 * Las Esencias y Reliquias van por la Aduana (tipo "cofre") al inventario de quien abre, no
 * dentro del cofre: toda Esencia o Reliquia que se crea sale por Aduana.pagar (regla 7), y
 * asi queda en la Bitacora quien las saco. Cristal, Tintura y Frasco si van al cofre.
 */
final class Cofres {

    private final Hardcore hc;
    private final SecureRandom azar = new SecureRandom();

    Cofres(Hardcore hc) {
        this.hc = hc;
        Autotest.registrar("cofres", this::autotest);
    }

    void parar() {
    }

    /** Lo llama Hardcore.onBotinDeCofre (LootGenerateEvent) si cofres-vacios esta apagado. */
    void alGenerar(LootGenerateEvent e) {
        if (!hc.esHardcore(e.getWorld())) return;
        ConfigurationSection c = hc.cfg();
        if (!c.getBoolean("cofres.activo", true)) return;

        List<ItemStack> loot = new ArrayList<>(e.getLoot());
        Map<String, Integer> filtrados = filtrar(loot, filtro(c));
        if (!filtrados.isEmpty()) e.setLoot(loot);

        /* Sin jugador (una tolva, una vagoneta que pasa) no se anade nada: si no, una fila de
         * tolvas bajo una mina abandonada seria una granja de Esencias. Con solo-con-jugador
         * apagado vale cualquier jugador, tambien el que no cuenta (creativo). */
        Player p = e.getEntity() instanceof Player j ? j : null;
        if (p == null || (c.getBoolean("cofres.solo-con-jugador", true) && !hc.cuenta(p))) return;

        String dia = hc.calendario() != null ? hc.calendario().dia() : "";
        int turno = turno(hc.datos(), p.getUniqueId(), dia, c.getInt("cofres.pagados-dia", 10));
        hc.marcarSucio();
        Contratos ct = hc.contratos();
        if (ct != null) hc.seguro("contratos", () -> ct.progreso(p, "cofre", 1));

        int esencias = 0;
        List<ItemStack> reliquias = new ArrayList<>();
        List<ItemStack> alCofre = new ArrayList<>();
        if (turno > 0) {
            Reliquias rel = hc.reliquias();
            for (Map<?, ?> fila : anadir(c)) {
                String objeto = String.valueOf(fila.get("objeto")).toLowerCase(Locale.ROOT);
                double prob = decimal(fila.get("prob"), 0);
                if (azar.nextDouble() >= prob) continue;
                int min = Math.max(1, entero(fila.get("min"), 1));
                int max = Math.max(min, entero(fila.get("max"), min));
                int n = min + azar.nextInt(max - min + 1);
                switch (objeto) {
                    case "esencia" -> esencias += n;
                    case "cristal" -> {
                        ItemStack cr = hc.items().cristal();
                        cr.setAmount(n);
                        alCofre.add(cr);
                        hc.plugin().bitacora().anotar("cofre", "cristal", p.getName(), String.valueOf(n));
                    }
                    case "frasco-1" -> {
                        // Los frascos no se apilan: uno por objeto, con un trago cada uno.
                        for (int i = 0; i < n; i++) alCofre.add(hc.items().frasco(1));
                    }
                    case "tintura" -> {
                        String id = c.getString("entregas.mmo.tintura", "CALAMITY_CONSUMIBLES.TINTURA_DE_CENIZA");
                        ItemStack t = PuenteMmo.crear(id);
                        // Sin MMOItems (servidor de pruebas) esa tirada no da nada.
                        if (t != null) {
                            t.setAmount(n);
                            alCofre.add(t);
                        }
                    }
                    default -> {
                        if (!objeto.startsWith("reliquia-") || rel == null || !rel.activas()) break;
                        int g = entero(objeto.substring("reliquia-".length()), 0);
                        if (g < 1 || g > 4) break;
                        ItemStack r = rel.crear(g, "cofre", null, 0, null, false);
                        if (rel.id(r) == null) {
                            r.setAmount(n);
                            reliquias.add(r);
                        } else {
                            reliquias.add(r);
                            for (int i = 1; i < n; i++) reliquias.add(rel.crear(g, "cofre", null, 0, null, false));
                        }
                        if (g >= 3) hc.plugin().bitacora().anotar("cofre", "reliquia-" + g, p.getName(), String.valueOf(n));
                    }
                }
            }
        }
        if (!alCofre.isEmpty()) {
            loot.addAll(alCofre);
            e.setLoot(loot);
        }
        Aduana ad = hc.aduana();
        if (ad != null && (esencias > 0 || !reliquias.isEmpty())) {
            Aduana.Pago pago = ad.pagar(p, "cofre", esencias, 0, reliquias, "cofre");
            Grifo g = hc.grifo();
            if (pago != null && g != null) g.destelloEsencias(p, pago.esencias(), 0);
        }

        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("objetos", contar(loot));
        campos.put("filtrados", filtrados);
        campos.put("cofres_hoy", turno > 0 ? turno : hc.datos().getInt("cofres-dia." + p.getUniqueId() + ".n", 0) + 1);
        campos.put("pagado", turno > 0 ? "si" : "no");
        Telemetria te = hc.telemetria();
        if (te != null) hc.seguro("telemetria", () -> te.suceso("vanilla-cofre", p, campos));
    }

    private static List<Map<?, ?>> anadir(ConfigurationSection c) {
        if (c.isList("cofres.anadir")) return c.getMapList("cofres.anadir");
        return List.of(
                Map.of("objeto", "esencia", "prob", 0.40, "min", 1, "max", 2),
                Map.of("objeto", "reliquia-1", "prob", 0.50, "min", 1, "max", 2),
                Map.of("objeto", "reliquia-2", "prob", 0.20, "min", 1, "max", 1),
                Map.of("objeto", "reliquia-3", "prob", 0.04, "min", 1, "max", 1),
                Map.of("objeto", "cristal", "prob", 0.05, "min", 1, "max", 1),
                Map.of("objeto", "tintura", "prob", 0.10, "min", 1, "max", 2),
                Map.of("objeto", "frasco-1", "prob", 0.03, "min", 1, "max", 1));
    }

    static Set<Material> filtro(ConfigurationSection c) {
        List<String> nombres = c.isList("cofres.filtrar-vanilla") ? c.getStringList("cofres.filtrar-vanilla")
                : List.of("NETHERITE_INGOT", "NETHERITE_UPGRADE_SMITHING_TEMPLATE", "ENCHANTED_GOLDEN_APPLE");
        Set<Material> s = EnumSet.noneOf(Material.class);
        for (String n : nombres) {
            Material m = Material.matchMaterial(n);
            if (m != null) s.add(m);
        }
        return s;
    }

    /** Quita del botin los materiales del filtro. Devuelve material -> cuantos se quitaron. */
    static Map<String, Integer> filtrar(List<ItemStack> loot, Set<Material> filtro) {
        Map<String, Integer> quitados = new LinkedHashMap<>();
        for (Iterator<ItemStack> it = loot.iterator(); it.hasNext(); ) {
            ItemStack i = it.next();
            if (i == null || !filtro.contains(i.getType())) continue;
            quitados.merge(i.getType().name(), i.getAmount(), Integer::sum);
            it.remove();
        }
        return quitados;
    }

    private static Map<String, Integer> contar(List<ItemStack> loot) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (ItemStack i : loot) if (i != null && !i.getType().isAir()) m.merge(i.getType().name(), i.getAmount(), Integer::sum);
        return m;
    }

    /**
     * El numero de cofre pagado de hoy (1..tope) y lo apunta; 0 si ya se paso del tope.
     * cofres-dia.<uuid> = {dia, n}: al cambiar de dia empieza de cero.
     */
    static int turno(ConfigurationSection datos, UUID jugador, String dia, int tope) {
        String base = "cofres-dia." + jugador;
        int n = dia.equals(datos.getString(base + ".dia", "")) ? datos.getInt(base + ".n", 0) : 0;
        if (n >= tope) return 0;
        datos.set(base + ".dia", dia);
        datos.set(base + ".n", n + 1);
        return n + 1;
    }

    private static int entero(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double decimal(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? def : Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ----------------------------------------------------------------- autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        Set<Material> f = filtro(new YamlConfiguration());
        List<ItemStack> loot = new ArrayList<>(List.of(new ItemStack(Material.NETHERITE_INGOT, 2),
                new ItemStack(Material.IRON_INGOT, 5), new ItemStack(Material.ENCHANTED_GOLDEN_APPLE),
                new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE)));
        Map<String, Integer> q = filtrar(loot, f);
        h.igual("NETHERITE_INGOT filtrado", 2, q.get("NETHERITE_INGOT"));
        h.igual("manzana de notch filtrada", 1, q.get("ENCHANTED_GOLDEN_APPLE"));
        h.igual("plantilla de netherita filtrada", 1, q.get("NETHERITE_UPGRADE_SMITHING_TEMPLATE"));
        h.igual("el hierro se queda", 1, loot.size());
        h.igual("y es hierro", Material.IRON_INGOT, loot.get(0).getType());

        YamlConfiguration datos = new YamlConfiguration();
        UUID u = Autotest.sintetico(1);
        boolean diezPagados = true;
        for (int i = 1; i <= 10; i++) diezPagados &= turno(datos, u, "2026-09-26", 10) == i;
        h.ok("los 10 primeros cofres del dia pagan", diezPagados);
        h.igual("el 11.o cofre del dia no anade nada", 0, turno(datos, u, "2026-09-26", 10));
        h.igual("el 12.o tampoco", 0, turno(datos, u, "2026-09-26", 10));
        h.igual("al dia siguiente vuelve a pagar", 1, turno(datos, u, "2026-09-27", 10));
        h.igual("otro jugador cuenta aparte", 1, turno(datos, Autotest.sintetico(2), "2026-09-26", 10));
        h.ok("las pruebas no tocan hardcore-datos.yml", !hc.datos().isSet("cofres-dia." + u));
        h.igual("filas de serie para anadir", 7, anadir(new YamlConfiguration()).size());
        return h.lineas();
    }
}
