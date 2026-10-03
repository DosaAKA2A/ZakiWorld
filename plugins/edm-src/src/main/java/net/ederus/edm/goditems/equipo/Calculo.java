package net.ederus.edm.goditems.equipo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.ederus.edm.goditems.equipo.Definicion.Atributo;
import net.ederus.edm.goditems.equipo.Definicion.Carnada;
import net.ederus.edm.goditems.equipo.Definicion.Clave;
import net.ederus.edm.goditems.equipo.Definicion.Config;
import net.ederus.edm.goditems.equipo.Definicion.Conjunto;
import net.ederus.edm.goditems.equipo.Definicion.Efectos;
import net.ederus.edm.goditems.equipo.Definicion.Escalon;
import net.ederus.edm.goditems.equipo.Definicion.Pieza;
import net.ederus.edm.goditems.equipo.Definicion.Pocion;

/**
 * La cuenta, sin Bukkit alrededor: dado lo que lleva alguien en cada hueco,
 * que cuenta y que suma. Es una funcion pura para que el autotest la pueda
 * llamar con items de verdad sin necesitar a un jugador delante.
 *
 * Reglas (las mismas que tenia el equipo.yml de Calamity y el gear.yml de
 * PremioPescao, ahora en un solo sitio):
 *   - cuentan la armadura PUESTA y lo que se lleve en las DOS MANOS;
 *   - cada TIPO.ID cuenta una vez aunque se lleve repetido;
 *   - una pieza de ARMADURA en la mano no cuenta (si no, un yelmo en cada mano
 *     y otro en la cabeza sumaban tres yelmos);
 *   - los escalones de un set se acumulan: con 4 piezas valen el de 2 y el de 4;
 *   - las claves se suman y luego se topan con el tope y el minimo de su
 *     declaracion.
 */
public final class Calculo {

    /** Los huecos que cuentan, en el orden en que los ensena /gi equipo. */
    public static final EquipmentSlot[] HUECOS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND};

    private Calculo() { }

    /** Un hueco leido: el item (null = vacio), su TIPO.ID (null = no es de MMOItems), su set y si cuenta. */
    public record Hueco(EquipmentSlot slot, ItemStack item, String id, String setMmo, boolean cuenta, String porQueNo) { }

    /** Un atributo ya sumado: todos los del mismo atributo y operacion van juntos. */
    public record AtributoTotal(org.bukkit.attribute.Attribute atributo, String clave,
                                AttributeModifier.Operation operacion, double valor) { }

    /**
     * El resultado de la cuenta.
     *
     * @param piezasPorSet id del set -> cuantas de sus piezas cuentan (solo los que tienen alguna)
     * @param activos      id del set -> escalones alcanzados
     * @param carnada      la carnada que cuenta (la de la caña que se usa), o null
     * @param carnadaUsos  las pescas que le quedan a esa carnada (0 sin carnada)
     * @param firma        cambia si y solo si cambia lo que cuenta: sirve para saber si hay que reaplicar
     */
    public record Resultado(List<Hueco> huecos, Set<String> contadas, Map<String, Integer> piezasPorSet,
                            Map<String, List<Escalon>> activos, Map<String, Double> bruto,
                            Map<String, Double> topado, List<Pocion> pociones, List<AtributoTotal> atributos,
                            Carnada carnada, int carnadaUsos, String firma) {

        public static final Resultado VACIO = new Resultado(List.of(), Set.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), List.of(), List.of(), null, 0, "");

        public double de(String clave) {
            Double v = this.topado.get(clave);
            return v == null ? 0 : v;
        }
    }

    /** Lo que lleva en cada hueco que cuenta (armadura y manos). */
    public static Map<EquipmentSlot, ItemStack> equipoDe(Player p) {
        Map<EquipmentSlot, ItemStack> out = new EnumMap<>(EquipmentSlot.class);
        EntityEquipment eq = p == null ? null : p.getEquipment();
        if (eq == null) return out;
        for (EquipmentSlot s : HUECOS) {
            ItemStack i = eq.getItem(s);
            if (i != null && !i.getType().isAir()) out.put(s, i);
        }
        return out;
    }

    /**
     * El hueco de armadura de un item, o null si no es armadura. Manda su
     * componente equippable si lo trae; si no, el material. Los bloques
     * (cabezas, calabazas) no cuentan como armadura: un talisman con forma de
     * cabeza se lleva en la mano.
     */
    public static EquipmentSlot sitio(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        Material m = item.getType();
        if (m.isBlock()) return null;
        EquipmentSlot s;
        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        try {
            s = meta != null && meta.hasEquippable() ? meta.getEquippable().getSlot() : m.getEquipmentSlot();
        } catch (Throwable t) {
            s = m.getEquipmentSlot();
        }
        return s == EquipmentSlot.HEAD || s == EquipmentSlot.CHEST || s == EquipmentSlot.LEGS || s == EquipmentSlot.FEET
                ? s : null;
    }

    public static boolean esArmadura(ItemStack item) {
        return sitio(item) != null;
    }

    /**
     * La cuenta entera.
     *
     * @param identidad el TIPO.ID de un item (null si no es de MMOItems)
     * @param setDe     la etiqueta de set de MMOItems de un item (null si no tiene)
     */
    public static Resultado calcular(Config c, Map<EquipmentSlot, ItemStack> equipo,
                                     Function<ItemStack, String> identidad, Function<ItemStack, String> setDe) {
        if (c == null || c.vacia() || equipo == null || equipo.isEmpty()) return Resultado.VACIO;
        boolean conSetsMmo = c.haySetsMmo();
        List<Hueco> huecos = new ArrayList<>();
        Map<String, String> setDeCada = new LinkedHashMap<>();
        for (EquipmentSlot s : HUECOS) {
            ItemStack it = equipo.get(s);
            if (it == null || it.getType().isAir()) {
                huecos.add(new Hueco(s, null, null, null, false, null));
                continue;
            }
            String id = identidad.apply(it);
            if (id == null) {
                huecos.add(new Hueco(s, it, null, null, false, "no es de MMOItems"));
                continue;
            }
            String set = conSetsMmo ? setDe.apply(it) : null;
            if (s.isHand() && esArmadura(it)) {
                huecos.add(new Hueco(s, it, id, set, false, "armadura en la mano: solo cuenta puesta"));
            } else if (setDeCada.containsKey(id)) {
                huecos.add(new Hueco(s, it, id, set, false, "repetida: cada pieza cuenta una vez"));
            } else {
                setDeCada.put(id, set);
                huecos.add(new Hueco(s, it, id, set, true, null));
            }
        }
        Set<String> contadas = Collections.unmodifiableSet(new LinkedHashSet<>(setDeCada.keySet()));

        Map<String, Double> bruto = new LinkedHashMap<>();
        Map<org.bukkit.potion.PotionEffectType, Integer> pociones = new LinkedHashMap<>();
        Map<String, AtributoTotal> atributos = new LinkedHashMap<>();
        for (String id : contadas) {
            Pieza p = c.piezas().get(id);
            if (p != null) sumar(p.efectos(), bruto, pociones, atributos);
        }
        Map<String, Integer> porSet = new LinkedHashMap<>();
        Map<String, List<Escalon>> activos = new LinkedHashMap<>();
        TreeSet<String> firma = new TreeSet<>(contadas);
        for (Conjunto s : c.sets()) {
            int n = 0;
            for (Map.Entry<String, String> e : setDeCada.entrySet()) if (s.tiene(e.getKey(), e.getValue())) n++;
            if (n == 0) continue;
            porSet.put(s.id(), n);
            List<Escalon> alcanzados = new ArrayList<>();
            for (Escalon e : s.escalones()) {
                if (n < e.necesita()) continue;
                alcanzados.add(e);
                sumar(e.efectos(), bruto, pociones, atributos);
                firma.add("#" + s.id() + ":" + e.necesita());
            }
            if (!alcanzados.isEmpty()) activos.put(s.id(), List.copyOf(alcanzados));
        }
        /* La carnada de la caña que se usa: suma como una pieza mas, sea la caña
         * de MMOItems o no. Los usos no entran en la firma (cambian en cada
         * pesca y no cambian lo que se aplica), el id si. */
        Carnada carnada = null;
        int usos = 0;
        Map.Entry<String, Integer> marca = carnadaDe(canaEnUso(equipo));
        if (marca != null) {
            carnada = c.carnada(marca.getKey());
            if (carnada != null) {
                usos = marca.getValue();
                sumar(carnada.efectos(), bruto, pociones, atributos);
                firma.add("~carnada:" + carnada.id());
            }
        }
        Map<String, Double> topado = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : bruto.entrySet()) {
            Clave k = c.claves().get(e.getKey());
            topado.put(e.getKey(), k == null ? e.getValue() : k.topar(e.getValue()));
        }
        List<Pocion> ps = new ArrayList<>();
        pociones.forEach((t, n) -> ps.add(new Pocion(t, n)));
        return new Resultado(List.copyOf(huecos), contadas, Collections.unmodifiableMap(porSet),
                Collections.unmodifiableMap(activos), Collections.unmodifiableMap(bruto),
                Collections.unmodifiableMap(topado), List.copyOf(ps), List.copyOf(atributos.values()),
                carnada, carnada == null ? 0 : usos, String.join(",", firma));
    }

    /* ------------------------------------------------------------ carnadas */

    /**
     * Las marcas que PremioPescao deja en una caña con carnada. Namespace fijo
     * ("pescao", no el del plugin) para que se lean igual desde aqui.
     */
    public static final NamespacedKey CARNADA = new NamespacedKey("pescao", "carnada");
    public static final NamespacedKey CARNADA_USOS = new NamespacedKey("pescao", "carnada_usos");

    /**
     * La caña que se usa para pescar: la de la mano principal y, si ahi no hay
     * caña, la de la otra mano (es lo mismo que decide el juego al lanzar).
     */
    public static ItemStack canaEnUso(Map<EquipmentSlot, ItemStack> equipo) {
        ItemStack main = equipo.get(EquipmentSlot.HAND);
        if (main != null && main.getType() == Material.FISHING_ROD) return main;
        ItemStack off = equipo.get(EquipmentSlot.OFF_HAND);
        return off != null && off.getType() == Material.FISHING_ROD ? off : null;
    }

    /** El id de carnada de una caña y sus usos, o null si no lleva (o no le quedan). */
    public static Map.Entry<String, Integer> carnadaDe(ItemStack cana) {
        if (cana == null || !cana.hasItemMeta()) return null;
        PersistentDataContainer pdc = cana.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(CARNADA, PersistentDataType.STRING);
        Integer usos = pdc.get(CARNADA_USOS, PersistentDataType.INTEGER);
        if (id == null || id.isBlank() || usos == null || usos <= 0) return null;
        return Map.entry(id.toLowerCase(java.util.Locale.ROOT), usos);
    }

    private static void sumar(Efectos ef, Map<String, Double> bruto,
                              Map<org.bukkit.potion.PotionEffectType, Integer> pociones,
                              Map<String, AtributoTotal> atributos) {
        if (ef == null || ef.vacio()) return;
        ef.claves().forEach((k, v) -> bruto.merge(k, v, Double::sum));
        /* Dos piezas con la misma pocion no suben de nivel: manda la mas alta,
         * como con las perm-effects de MMOItems. */
        for (Pocion p : ef.pociones()) pociones.merge(p.tipo(), p.nivel(), Math::max);
        for (Atributo a : ef.atributos()) {
            String k = a.clave() + "|" + a.operacion().name();
            AtributoTotal antes = atributos.get(k);
            atributos.put(k, new AtributoTotal(a.atributo(), a.clave(), a.operacion(),
                    (antes == null ? 0 : antes.valor()) + a.valor()));
        }
    }
}
