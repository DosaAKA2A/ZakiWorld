package net.ederus.calamity.hardcore;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Lo que el autotest real-items y /calamity items leen de MMOItems 6.10.1 y MythicLib 1.7.1 para
 * comprobar que un objeto de Calamity hace lo que dice su lore: las etiquetas del item generado, los bonos y
 * habilidades de un set tal y como los cargo MMOItems, y el StatMap de un jugador. Por reflexion, como PuenteMmo
 * (MT sec. 6.2): sin MMOItems en el pom y con el mismo jar en un servidor que no lo tenga.
 *
 * Leido en los jars de _toolchain/libs (decompilados, auditoria de objetos 2026-09-27):
 *   MMOItems.plugin.getSets().get(id) -> ItemSet; ItemSet.getBonuses(n) -> SetBonuses ya ACUMULADOS de [2] a [n]
 *     (getStats() -> Map<ItemStat, Double>, con ItemStat.getId(); getAbilities() -> List<AbilityData>, con
 *     getHandler().getId(), getTrigger().name() y getParameter(p)); ItemSet.getLoreTag().
 *   MMOItems.plugin.getTypes().get(tipo), getTemplates().hasTemplate(Type, id), getConfig().
 *   NBTItem.get(item): hasTag/getString/getBoolean de las claves MMOITEMS_* de custom_data.
 *   MMOPlayerData.getOrNull(jugador).getStatMap().getInstances() -> StatInstance (getStat, getTotal y
 *     getModifiers -> StatModifier: getKey, getValue, getSlot, getSource, getType); getPassiveSkillMap()
 *     .getModifiers() -> PassiveSkill (getKey, getSlot, getTriggeredSkill -> la AbilityData de arriba).
 *   MMOItems registra lo de cada pieza con la clave "MMOItems" y el hueco de la pieza (HEAD, MAIN_HAND...) y lo
 *   de los sets con la misma clave y el hueco OTHER (InventoryResolver.resolveItemSet).
 *
 * Nada de esto revienta: lo que no encaja devuelve null o vacio y quien pregunta lo cuenta como fallo.
 */
final class LecturaMmo {

    /** La clave con la que MMOItems registra en MythicLib lo de sus piezas y sus sets. */
    static final String CLAVE_MMOITEMS = "MMOItems";

    private LecturaMmo() {
    }

    // ------------------------------------------------------------------ reflexion

    /**
     * Llama a un metodo por su nombre y sus argumentos. Se busca declarado en una clase PUBLICA de la jerarquia:
     * las implementaciones de NBTItem o de los mapas de MythicLib no lo son, y llamar al metodo de la clase
     * publica despacha igual a la implementacion.
     */
    static Object llamar(Object o, String metodo, Object... args) throws ReflectiveOperationException {
        if (o == null) throw new NoSuchMethodException(metodo + " sobre null");
        Method m = buscar(o.getClass(), metodo, args);
        if (m == null) throw new NoSuchMethodException(o.getClass().getName() + "." + metodo);
        return m.invoke(o, args);
    }

    /** Un metodo estatico de una clase por su nombre (NBTItem.get, MMOPlayerData.getOrNull). */
    static Object estatico(String clase, String metodo, Object... args) throws ReflectiveOperationException {
        Method m = buscar(Class.forName(clase), metodo, args);
        if (m == null || !Modifier.isStatic(m.getModifiers())) throw new NoSuchMethodException(clase + "." + metodo);
        return m.invoke(null, args);
    }

    private static Method buscar(Class<?> clase, String nombre, Object[] args) {
        Deque<Class<?>> cola = new ArrayDeque<>();
        Set<Class<?>> vistas = new HashSet<>();
        cola.add(clase);
        Method reserva = null;
        while (!cola.isEmpty()) {
            Class<?> c = cola.poll();
            if (!vistas.add(c)) continue;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(nombre) || !encaja(m.getParameterTypes(), args)) continue;
                if (Modifier.isPublic(m.getModifiers()) && Modifier.isPublic(c.getModifiers())) return m;
                if (reserva == null) reserva = m;
            }
            if (c.getSuperclass() != null) cola.add(c.getSuperclass());
            cola.addAll(Arrays.asList(c.getInterfaces()));
        }
        if (reserva != null) {
            try {
                reserva.setAccessible(true);
            } catch (Throwable ignorado) {
                // sin acceso: la llamada fallara y quien pregunta lo contara
            }
        }
        return reserva;
    }

    private static boolean encaja(Class<?>[] tipos, Object[] args) {
        if (tipos.length != args.length) return false;
        for (int i = 0; i < tipos.length; i++) {
            if (args[i] == null) {
                if (tipos[i].isPrimitive()) return false;
            } else if (!envoltorio(tipos[i]).isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> envoltorio(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == double.class) return Double.class;
        if (t == boolean.class) return Boolean.class;
        if (t == long.class) return Long.class;
        if (t == float.class) return Float.class;
        if (t == short.class) return Short.class;
        if (t == byte.class) return Byte.class;
        return Character.class;
    }

    private static double numero(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static Object mmoitems() throws ReflectiveOperationException {
        return Class.forName("net.Indyuce.mmoitems.MMOItems").getField("plugin").get(null);
    }

    // ------------------------------------------------------------------ plantillas y etiquetas

    /** Si MMOItems conoce el tipo (ARMOR, SWORD...). Null = no se pudo preguntar. */
    static Boolean hayTipo(String tipo) {
        try {
            return llamar(llamar(mmoitems(), "getTypes"), "get", tipo) != null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Si MMOItems tiene la plantilla TIPO/ID (lo que `mi give TIPO ID` necesita). Null = no se pudo preguntar. */
    static Boolean hayPlantilla(String tipo, String id) {
        try {
            Object m = mmoitems();
            Object t = llamar(llamar(m, "getTypes"), "get", tipo);
            if (t == null) return false;
            return Boolean.TRUE.equals(llamar(llamar(m, "getTemplates"), "hasTemplate", t, id));
        } catch (Throwable e) {
            return null;
        }
    }

    /** Como se comporta un tipo de MMOItems: su supertipo, su modifier-source y lo que hace al golpear (on-attack). */
    record Tipo(String supertipo, String fuente, String alAtacar) {
    }

    /** El tipo tal cual lo cargo MMOItems (Type.getSupertype, getModifierSource, onAttack), o null si no esta. */
    static Tipo tipo(String id) {
        try {
            Object t = llamar(llamar(mmoitems(), "getTypes"), "get", id);
            if (t == null) return null;
            Object ataque = llamar(t, "onAttack");
            return new Tipo(String.valueOf(llamar(llamar(t, "getSupertype"), "getId")), String.valueOf(llamar(t, "getModifierSource")),
                    ataque == null ? null : String.valueOf(llamar(ataque, "getId")));
        } catch (Throwable e) {
            return null;
        }
    }

    private static Object nbt(ItemStack item) throws ReflectiveOperationException {
        return estatico("io.lumine.mythic.lib.api.item.NBTItem", "get", item);
    }

    /** Una etiqueta de custom_data tal cual (MMOITEMS_ITEM_SET, MMOITEMS_UPGRADE...), o null si no la lleva. */
    static String etiqueta(ItemStack item, String clave) {
        if (item == null || item.getType().isAir()) return null;
        try {
            Object n = nbt(item);
            if (!Boolean.TRUE.equals(llamar(n, "hasTag", clave))) return null;
            Object v = llamar(n, "getString", clave);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Si el item lleva esa etiqueta en custom_data (MMOITEMS_MAX_HEALTH...), sea del tipo que sea. */
    static boolean tiene(ItemStack item, String clave) {
        if (item == null || item.getType().isAir()) return false;
        try {
            return Boolean.TRUE.equals(llamar(nbt(item), "hasTag", clave));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Una etiqueta booleana (MMOITEMS_INEDIBLE, MMOITEMS_DISABLE_CRAFTING); false si no la lleva. */
    static boolean etiquetaSi(ItemStack item, String clave) {
        if (item == null || item.getType().isAir()) return false;
        try {
            Object n = nbt(item);
            return Boolean.TRUE.equals(llamar(n, "hasTag", clave)) && Boolean.TRUE.equals(llamar(n, "getBoolean", clave));
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ sets

    /** Una habilidad de MMOItems tal cual la registra: su tipo, su activador y los parametros pedidos. */
    record Habilidad(String tipo, String modo, Map<String, Double> parametros, String clave, String hueco) {
    }

    /** Lo que da un set con n piezas: stats (ID de MMOItems -> valor) y habilidades. */
    record Bonos(Map<String, Double> stats, List<Habilidad> habilidades) {
    }

    private static Habilidad habilidad(Object skill, Collection<String> parametros, String clave, String hueco)
            throws ReflectiveOperationException {
        String tipo = String.valueOf(llamar(llamar(skill, "getHandler"), "getId"));
        Object trig = llamar(skill, "getTrigger");
        String modo = trig == null ? "?" : String.valueOf(llamar(trig, "name"));
        Map<String, Double> ps = new LinkedHashMap<>();
        for (String p : parametros) ps.put(p, numero(llamar(skill, "getParameter", p)));
        return new Habilidad(tipo, modo, ps, clave, hueco);
    }

    /** El set tal cual lo cargo MMOItems, o null si no esta (sin pegar, o rechazado al cargar). */
    private static Object set(String id) throws ReflectiveOperationException {
        return llamar(llamar(mmoitems(), "getSets"), "get", id);
    }

    /** Si MMOItems tiene cargado ese set. Null = no se pudo preguntar. */
    static Boolean haySet(String id) {
        try {
            return set(id) != null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Lo que MMOItems da con n piezas del set, ya acumulado de [2] a [n] (ItemSet.getBonuses), con los parametros
     * pedidos de cada habilidad. Null si el set no esta cargado o no se pudo leer.
     */
    static Bonos bonosSet(String id, int piezas, Collection<String> parametros) {
        try {
            Object s = set(id);
            if (s == null) return null;
            Object b = llamar(s, "getBonuses", piezas);
            Map<String, Double> stats = new TreeMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) llamar(b, "getStats")).entrySet()) {
                stats.put(String.valueOf(llamar(e.getKey(), "getId")), numero(e.getValue()));
            }
            List<Habilidad> habs = new ArrayList<>();
            for (Object a : (Collection<?>) llamar(b, "getAbilities")) habs.add(habilidad(a, parametros, null, null));
            return new Bonos(stats, habs);
        } catch (Throwable t) {
            return null;
        }
    }

    /** El lore-tag del set (las lineas que MMOItems pone en cada pieza), o vacio. */
    static List<String> loreSet(String id) {
        try {
            Object s = set(id);
            Object l = s == null ? null : llamar(s, "getLoreTag");
            List<String> out = new ArrayList<>();
            if (l instanceof Collection<?> c) for (Object x : c) out.add(String.valueOf(x));
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** La config de MMOItems (config.yml), o null. */
    static ConfigurationSection configMmoitems() {
        try {
            return (ConfigurationSection) llamar(mmoitems(), "getConfig");
        } catch (Throwable t) {
            return null;
        }
    }

    /** La carpeta de datos de un plugin cargado (MythicLib), o null. */
    static java.io.File carpeta(String plugin) {
        Plugin p = Bukkit.getPluginManager().getPlugin(plugin);
        return p == null ? null : p.getDataFolder();
    }

    // ------------------------------------------------------------------ jugador

    /** Un modificador del StatMap de MythicLib. hueco: HEAD, CHEST, LEGS, FEET, MAIN_HAND, OFF_HAND u OTHER. */
    record Modificador(String stat, String clave, double valor, String hueco, String fuente, String tipo) {
    }

    /** Lo que MythicLib lleva apuntado de un jugador: total por stat, cada modificador y cada habilidad. */
    record Estado(Map<String, Double> totales, List<Modificador> modificadores, List<Habilidad> habilidades) {
    }

    /** El StatMap y las habilidades de un jugador conectado, o null si MythicLib no lo tiene. */
    static Estado estado(Player p, Collection<String> parametros) {
        if (p == null || !Bukkit.getPluginManager().isPluginEnabled("MythicLib")) return null;
        try {
            Object datos = estatico("io.lumine.mythic.lib.api.player.MMOPlayerData", "getOrNull", p);
            if (datos == null) return null;
            Map<String, Double> totales = new TreeMap<>();
            List<Modificador> mods = new ArrayList<>();
            for (Object inst : (Collection<?>) llamar(llamar(datos, "getStatMap"), "getInstances")) {
                String stat = String.valueOf(llamar(inst, "getStat"));
                totales.put(stat, numero(llamar(inst, "getTotal")));
                for (Object m : (Collection<?>) llamar(inst, "getModifiers")) {
                    mods.add(new Modificador(stat, String.valueOf(llamar(m, "getKey")), numero(llamar(m, "getValue")),
                            String.valueOf(llamar(m, "getSlot")), String.valueOf(llamar(m, "getSource")),
                            String.valueOf(llamar(m, "getType"))));
                }
            }
            List<Habilidad> habs = new ArrayList<>();
            for (Object ps : (Collection<?>) llamar(llamar(datos, "getPassiveSkillMap"), "getModifiers")) {
                habs.add(habilidad(llamar(ps, "getTriggeredSkill"), parametros, String.valueOf(llamar(ps, "getKey")),
                        String.valueOf(llamar(ps, "getSlot"))));
            }
            return new Estado(totales, mods, habs);
        } catch (Throwable t) {
            return null;
        }
    }
}
