package net.ederus.edm.goditems.equipo;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import net.ederus.edm.goditems.equipo.Calculo.Resultado;
import net.ederus.edm.goditems.equipo.Definicion.Config;
import net.ederus.edm.goditems.equipo.Definicion.Conjunto;
import net.ederus.edm.goditems.equipo.Definicion.Escalon;
import net.ederus.edm.goditems.equipo.Definicion.Pieza;
import net.ederus.edm.goditems.mmo.Puente;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * /gi selftest: prueba los efectos de equipo de punta a punta.
 *
 * Dos mitades. La primera, con un YAML de prueba y items marcados a mano: el
 * separador '/', las sumas, los topes y el minimo, los escalones acumulados, que
 * la armadura en la mano no cuente, que una pieza repetida cuente una vez y el
 * lore que se genera. La segunda, con items REALES generados por MMOItems a
 * partir de lo que hay cargado en equipo/: que se reconocen, que cada pieza da
 * lo suyo puesta donde va, que el set completo alcanza sus escalones y que el
 * item construido lleva ya en el lore las lineas que corresponden (la regla de
 * que el lore de un item se tiene que cumplir se comprueba con el item de
 * verdad, no con la config).
 */
public final class AutotestEquipo {

    private final Equipo equipo;
    private final List<String> lineas = new ArrayList<>();
    private int fallos;

    public AutotestEquipo(Equipo equipo) {
        this.equipo = equipo;
    }

    public List<String> lineas() {
        return this.lineas;
    }

    public int fallos() {
        return this.fallos;
    }

    private void ok(String que, boolean bien) {
        this.lineas.add(bien ? "&aOK &7" + que : "&cFALLO &f" + que);
        if (!bien) this.fallos++;
    }

    private void igual(String que, Object esperado, Object real) {
        boolean bien = Objects.equals(esperado, real);
        this.lineas.add(bien ? "&aOK &7" + que : "&cFALLO &f" + que + "&7: esperaba &f" + esperado + "&7, salió &f" + real);
        if (!bien) this.fallos++;
    }

    private void cerca(String que, double esperado, double real) {
        boolean bien = Math.abs(esperado - real) <= 1e-9;
        this.lineas.add(bien ? "&aOK &7" + que : "&cFALLO &f" + que + "&7: esperaba &f" + esperado + "&7, salió &f" + real);
        if (!bien) this.fallos++;
    }

    private void info(String que) {
        this.lineas.add("&8- " + que);
    }

    static final String TEXTO = """
            grupos:
              prueba: '&3Prueba'
            claves:
              prueba.a:
                lore: '&7· &f{valor} &7cosa A'
                tope: 0.30
              prueba.b:
                lore: '&7· &f{valor} &7peligro'
                sentido: menos
                tope: 0.50
                minimo: -1
              prueba.c:
                lore: '&7· &f{valor} &7cajas'
                formato: puntos
            piezas:
              PRUEBA.YELMO:
                efectos:
                  prueba.a: 0.20
                  prueba.b: 0.10
              prueba.hacha:
                efectos:
                  prueba.a: 0.15
              PRUEBA.CORAZA:
                efectos:
                  prueba.b: 0.30
                  prueba.c: 1.5
                  prueba.nadie: 1
              PRUEBA.MALDITA:
                efectos:
                  prueba.b: -3
            sets:
              prueba:
                nombre: Prueba
                piezas: [PRUEBA.YELMO, PRUEBA.CORAZA, prueba.hacha]
                escalones:
                  2:
                    efectos:
                      prueba.b: 0.25
                  3:
                    efectos:
                      prueba.c: 2
            carnadas:
              cebo:
                nombre: Cebo
                efectos:
                  prueba.c: 3
                  prueba.a: 0.05
            """;

    public AutotestEquipo correr() {
        sintetico();
        reales();
        return this;
    }

    /* ======================================================== sintetico */

    private void sintetico() {
        Config c;
        try {
            LectorEquipo l = new LectorEquipo(null);
            c = l.leer(Map.of("prueba.yml", LectorEquipo.yaml(TEXTO)));
        } catch (InvalidConfigurationException e) {
            ok("el YAML de prueba se lee: " + e.getMessage(), false);
            return;
        }
        ok("PRUEBA.YELMO entra entera, con su punto", c.piezas().containsKey("PRUEBA.YELMO"));
        ok("no se parte en una sección PRUEBA", !c.piezas().containsKey("PRUEBA"));
        ok("en minúsculas entra en mayúsculas: PRUEBA.HACHA", c.piezas().containsKey("PRUEBA.HACHA"));
        ok("la clave prueba.a entra entera", c.claves().containsKey("prueba.a"));
        igual("una clave sin declarar se avisa", 1L,
                c.avisos().stream().filter(a -> a.contains("prueba.nadie")).count());
        try {
            YamlConfiguration conPunto = new YamlConfiguration();
            conPunto.loadFromString(TEXTO);
            var ps = conPunto.getConfigurationSection("piezas");
            ok("con el '.' de Bukkit la clave SÍ se partiría (el fallo de gear.yml)",
                    ps != null && ps.getKeys(false).contains("PRUEBA"));
        } catch (InvalidConfigurationException e) {
            ok("el YAML de prueba con '.': " + e.getMessage(), false);
        }

        ItemStack yelmo = marcado(Material.NETHERITE_HELMET, "PRUEBA", "YELMO");
        ItemStack coraza = marcado(Material.NETHERITE_CHESTPLATE, "PRUEBA", "CORAZA");
        ItemStack hacha = marcado(Material.NETHERITE_AXE, "PRUEBA", "HACHA");
        ItemStack maldita = marcado(Material.STICK, "PRUEBA", "MALDITA");
        igual("un item marcado a mano se lee", "PRUEBA.YELMO", Puente.enlacePorPdc(yelmo));
        igual("una espada vanilla no es de MMOItems", null, Puente.enlacePorPdc(new ItemStack(Material.DIAMOND_SWORD)));

        Resultado una = cuenta(c, Map.of(EquipmentSlot.HEAD, yelmo));
        cerca("una pieza: su prueba.a", 0.20, una.de("prueba.a"));
        ok("una pieza sola no alcanza el escalón de 2", una.activos().isEmpty());

        Resultado dos = cuenta(c, Map.of(EquipmentSlot.HEAD, yelmo, EquipmentSlot.HAND, hacha));
        cerca("dos piezas suman prueba.a sin tope: 0,20 + 0,15", 0.35, dos.bruto().get("prueba.a"));
        cerca("y con el tope de 0,30 se queda en 0,30", 0.30, dos.de("prueba.a"));
        cerca("dos del set: escalón de 2 (0,10 + 0,25)", 0.35, dos.de("prueba.b"));
        igual("dos del set: 2/3", 2, dos.piezasPorSet().get("prueba"));

        Resultado tres = cuenta(c, Map.of(EquipmentSlot.HEAD, yelmo, EquipmentSlot.CHEST, coraza, EquipmentSlot.HAND, hacha));
        cerca("tres piezas: prueba.b 0,10 + 0,30 + 0,25 sin tope", 0.65, tres.bruto().get("prueba.b"));
        cerca("tres piezas: topado a 0,50", 0.50, tres.de("prueba.b"));
        cerca("los escalones se acumulan: prueba.c 1,5 + 2", 3.5, tres.de("prueba.c"));
        igual("tres piezas: escalones 2 y 3 activos", 2, tres.activos().get("prueba").size());
        cerca("una clave sin declarar suma sin tope", 1, tres.de("prueba.nadie"));
        cerca("una maldición (-3) no baja del mínimo -1", -1, cuenta(c, Map.of(EquipmentSlot.HAND, maldita)).de("prueba.b"));

        Resultado mano = cuenta(c, Map.of(EquipmentSlot.HEAD, yelmo, EquipmentSlot.OFF_HAND, coraza));
        ok("la coraza en la otra mano NO cuenta (armadura: solo puesta)", !mano.contadas().contains("PRUEBA.CORAZA"));
        ok("y dice por qué", mano.huecos().stream().anyMatch(h -> h.slot() == EquipmentSlot.OFF_HAND
                && h.porQueNo() != null && h.porQueNo().contains("armadura")));
        Resultado repe = cuenta(c, Map.of(EquipmentSlot.HAND, hacha, EquipmentSlot.OFF_HAND, hacha.clone()));
        igual("la misma pieza en las dos manos cuenta una vez", 1, repe.contadas().size());
        ok("una cabeza (bloque) no es armadura", !Calculo.esArmadura(new ItemStack(Material.PLAYER_HEAD)));
        ok("sin nada puesto no suma nada", cuenta(c, Map.of()).topado().isEmpty());

        /* Carnadas (EDM 1.75.0): en la caña que se usa, sea de MMOItems o no. */
        igual("se lee la carnada", "Cebo", c.carnada("CEBO") == null ? null : c.carnada("CEBO").nombre());
        ItemStack cana = conCarnada(new ItemStack(Material.FISHING_ROD), "cebo", 7);
        Resultado cebo = cuenta(c, Map.of(EquipmentSlot.HAND, cana));
        cerca("la carnada de una caña vanilla suma", 3, cebo.de("prueba.c"));
        igual("y dice cuántas pescas le quedan", 7, cebo.carnadaUsos());
        Resultado conSet = cuenta(c, Map.of(EquipmentSlot.HEAD, yelmo, EquipmentSlot.CHEST, coraza,
                EquipmentSlot.OFF_HAND, conCarnada(new ItemStack(Material.FISHING_ROD), "cebo", 1)));
        cerca("en la otra mano cuenta y se suma a las piezas (1,5 + 3)", 4.5, conSet.de("prueba.c"));
        cerca("y respeta el tope (0,20 + 0,05 < 0,30)", 0.25, conSet.de("prueba.a"));
        ok("la firma cambia con la carnada", !conSet.firma().equals(cuenta(c, Map.of(EquipmentSlot.HEAD, yelmo,
                EquipmentSlot.CHEST, coraza)).firma()));
        ok("sin pescas no cuenta", cuenta(c, Map.of(EquipmentSlot.HAND,
                conCarnada(new ItemStack(Material.FISHING_ROD), "cebo", 0))).carnada() == null);
        ok("una carnada que no está declarada no cuenta", cuenta(c, Map.of(EquipmentSlot.HAND,
                conCarnada(new ItemStack(Material.FISHING_ROD), "otra", 5))).topado().isEmpty());
        ok("solo cuenta la caña que se usa (la de la mano principal)", cuenta(c, Map.of(
                EquipmentSlot.HAND, new ItemStack(Material.FISHING_ROD),
                EquipmentSlot.OFF_HAND, conCarnada(new ItemStack(Material.FISHING_ROD), "cebo", 5))).carnada() == null);

        List<String> lore = Redaccion.DE_SERIE.lore(c, "PRUEBA.YELMO", null, s -> 0);
        igual("lore generado del yelmo", List.of("", "&3Prueba", "&7· &f+20% &7cosa A", "&7· &f-10% &7peligro",
                "", "<#5FB8FF>Set Prueba (2/3)", "&7· &f-25% &7peligro",
                "", "<#5FB8FF>Set Prueba (3/3)", "&7· &f+2% &7cajas"), lore);
        igual("en puntos: 1,5 -> +1,5%", "&7· &f+1,5% &7cajas",
                Redaccion.linea(c.claves().get("prueba.c"), 1.5));
        igual("una pieza sin efectos ni set no lleva lore", List.of(),
                Redaccion.DE_SERIE.lore(c, "PRUEBA.OTRA", null, s -> 0));
    }

    private static Resultado cuenta(Config c, Map<EquipmentSlot, ItemStack> eq) {
        return Calculo.calcular(c, eq, Puente::enlacePorPdc, it -> null);
    }

    private static ItemStack conCarnada(ItemStack cana, String id, int usos) {
        cana.editMeta(meta -> {
            meta.getPersistentDataContainer().set(Calculo.CARNADA, PersistentDataType.STRING, id);
            meta.getPersistentDataContainer().set(Calculo.CARNADA_USOS, PersistentDataType.INTEGER, usos);
        });
        return cana;
    }

    private static ItemStack marcado(Material m, String tipo, String id) {
        ItemStack i = new ItemStack(m);
        i.editMeta(meta -> {
            meta.getPersistentDataContainer().set(new NamespacedKey("mmoitems", "mmoitems_item_type"),
                    PersistentDataType.STRING, tipo);
            meta.getPersistentDataContainer().set(new NamespacedKey("mmoitems", "mmoitems_item_id"),
                    PersistentDataType.STRING, id);
        });
        return i;
    }

    /* ============================================================ reales */

    private void reales() {
        Config c = this.equipo.config();
        info("cargado: " + this.equipo.resumen());
        for (String a : c.avisos()) ok("equipo/: " + a, false);
        if (this.equipo.modulo().puente() == null || !this.equipo.modulo().puente().hay()) {
            info("sin MMOItems no hay items reales que generar");
            return;
        }
        if (c.vacia()) {
            info("equipo/ no tiene piezas: nada real que probar");
            return;
        }
        PlainTextComponentSerializer plano = PlainTextComponentSerializer.plainText();
        for (String id : c.conocidas()) {
            int p = id.indexOf('.');
            ItemStack it = this.equipo.crear(id.substring(0, p), id.substring(p + 1));
            ok("MMOItems genera " + id, it != null);
            if (it == null) continue;
            igual(id + " real se reconoce", id, this.equipo.identidad(it));
            EquipmentSlot donde = Calculo.sitio(it) != null ? Calculo.sitio(it) : EquipmentSlot.HAND;
            Resultado r = this.equipo.calcular(c, Map.of(donde, it));
            ok(id + " real cuenta puesto en " + donde, r.contadas().contains(id));
            Pieza pz = c.piezas().get(id);
            boolean escalonDeUno = false;
            for (Conjunto s : c.sets()) {
                if (!s.tiene(id, null)) continue;
                for (Escalon e : s.escalones()) escalonDeUno |= e.necesita() <= 1;
            }
            if (pz != null && !escalonDeUno) {
                for (Map.Entry<String, Double> e : pz.efectos().claves().entrySet()) {
                    var k = c.claves().get(e.getKey());
                    cerca(id + " real da su " + e.getKey(), k == null ? e.getValue() : k.topar(e.getValue()), r.de(e.getKey()));
                }
            }
            if (Calculo.esArmadura(it)) {
                Resultado enMano = this.equipo.calcular(c, Map.of(EquipmentSlot.HAND, it));
                ok(id + " (armadura) en la mano no cuenta", !enMano.contadas().contains(id));
            }
            List<String> esperadas = this.equipo.loreDe(it);
            List<String> reales = new ArrayList<>();
            List<Component> l = it.getItemMeta() == null ? null : it.getItemMeta().lore();
            if (l != null) for (Component x : l) reales.add(plano.serialize(x));
            /* GodItems pone sus lineas AL FINAL del lore: se comprueba que el lore
             * del item real termina justo con ellas. Mirar solo si "estan" no
             * valdria: el lore escrito a mano en MMOItems puede tener las mismas
             * frases y daria bien aunque GodItems no hubiera escrito nada. */
            List<String> gen = new ArrayList<>();
            for (String e : esperadas) {
                String pl = plano.serialize(net.ederus.edm.comun.Estilo.legado(Redaccion.colores(e)));
                if (!pl.isBlank()) gen.add(pl);
            }
            reales.removeIf(String::isBlank);
            boolean alFinal = reales.size() >= gen.size()
                    && reales.subList(reales.size() - gen.size(), reales.size()).equals(gen);
            ok(id + " real termina su lore con las " + gen.size() + " líneas generadas", alFinal);
            if (alFinal && !gen.isEmpty()) {
                List<String> antes = reales.subList(0, reales.size() - gen.size());
                long repetidas = gen.stream().filter(antes::contains).count();
                if (repetidas > 0) {
                    info(id + ": " + repetidas + " de esas líneas también están escritas a mano en MMOItems"
                            + " (quítalas de su lore para que no salgan dos veces)");
                }
            }
        }
        for (Conjunto s : c.sets()) {
            if (s.piezas().isEmpty()) {
                info("set " + s.id() + ": va por set-mmoitems, se prueba con /gi equipo puesto");
                continue;
            }
            Map<EquipmentSlot, ItemStack> eq = new EnumMap<>(EquipmentSlot.class);
            int puestas = 0;
            for (String id : s.piezas()) {
                int p = id.indexOf('.');
                ItemStack it = this.equipo.crear(id.substring(0, p), id.substring(p + 1));
                if (it == null) continue;
                EquipmentSlot donde = Calculo.sitio(it);
                if (donde == null) donde = eq.containsKey(EquipmentSlot.HAND) ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
                if (eq.containsKey(donde)) continue;
                eq.put(donde, it);
                puestas++;
            }
            Resultado r = this.equipo.calcular(c, eq);
            igual("set " + s.id() + ": piezas reales que cuentan", puestas, r.piezasPorSet().getOrDefault(s.id(), 0));
            long esperados = s.escalones().stream().filter(e -> e.necesita() <= Math.max(0, r.piezasPorSet()
                    .getOrDefault(s.id(), 0))).count();
            igual("set " + s.id() + ": escalones alcanzados con " + puestas + " piezas", (int) esperados,
                    r.activos().getOrDefault(s.id(), List.of()).size());
            if (puestas == s.piezas().size()) {
                for (Map.Entry<String, Double> e : r.topado().entrySet()) {
                    info("set " + s.id() + " completo: " + Redaccion.efecto(c, e.getKey(), e.getValue()));
                }
            }
        }
    }
}
