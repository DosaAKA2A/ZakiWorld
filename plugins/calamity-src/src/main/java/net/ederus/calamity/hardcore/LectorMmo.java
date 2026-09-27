package net.ederus.calamity.hardcore;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Calamity 1.4 · El "TIPO.ID" de MMOItems de un item, para equipo.yml (Equipo). Por reflexion, como
 * PuenteMmo y como el MmoItemsHook de PremioPescao, que es el modelo: el pom solo trae paper-api y el
 * mismo jar tiene que valer en un servidor sin MMOItems.
 *
 * Va aparte de PuenteMmo a proposito: aqui importa POR QUIEN se ha leido cada pieza (/calamidad equipo
 * lo ensena) y se lee en tres escalones, uno detras de otro, para cada item:
 *   1. la API de MMOItems: MMOItems.getTypeName(NBTItem) y MMOItems.getID(NBTItem), estaticos;
 *   2. el NBTItem de MythicLib a mano: getString("MMOITEMS_ITEM_TYPE") y getString("MMOITEMS_ITEM_ID");
 *   3. el PDC (mmoitems:mmoitems_item_type y _id): un item marcado a mano o sin MMOItems en el servidor.
 *
 * OJO, el fallo que tuvo PremioPescao: MMOItems (por MythicLib) escribe el tipo y el id en la RAIZ del
 * minecraft:custom_data del item, NO en el PublicBukkitValues de Bukkit. Un item real de MMOItems no
 * trae NADA suyo en el PDC: leido solo del PDC, no se encontraba ninguna pieza. Por eso el PDC va el
 * ultimo, y solo como reserva.
 *
 * Los Method se buscan una vez en detectar() (al arrancar y en cada /calamidad reload) con el cargador
 * de clases del propio MMOItems. Leer un item es envolverlo una vez en NBTItem (una copia de su
 * custom_data) y dos consultas de texto. Nada de esto revienta: lo que no encaja devuelve null y, la
 * primera vez, un aviso en consola.
 *
 * Comprobado con javap en _toolchain/libs (MMOItems 6.10.1, MythicLib 1.7.1):
 *   net.Indyuce.mmoitems.MMOItems: static String getTypeName(NBTItem), static String getID(NBTItem),
 *     static campo plugin, getTypes().get(String), getTemplates().hasTemplate(Type, String),
 *     getTemplates().collectTemplates(), ItemStack getItem(String, String)
 *   io.lumine.mythic.lib.api.item.NBTItem: static get(ItemStack), String getString(String)
 */
final class LectorMmo {

    /** Por donde se ha leido una pieza, para /calamidad equipo. */
    enum Fuente {
        API("API de MMOItems"), NBT("NBTItem de MythicLib"), PDC("PDC");

        final String texto;

        Fuente(String texto) {
            this.texto = texto;
        }
    }

    /** Una pieza leida: su "TIPO.ID" en mayusculas y por donde salio. */
    record Lectura(String id, Fuente fuente) {
    }

    private static final String NBT_ITEM = "io.lumine.mythic.lib.api.item.NBTItem";
    private static final String MMOITEMS = "net.Indyuce.mmoitems.MMOItems";
    private static final String TIPO = "MMOITEMS_ITEM_TYPE";
    private static final String ID = "MMOITEMS_ITEM_ID";
    static final NamespacedKey PDC_TIPO = new NamespacedKey("mmoitems", "mmoitems_item_type");
    static final NamespacedKey PDC_ID = new NamespacedKey("mmoitems", "mmoitems_item_id");

    /**
     * Lo resuelto en detectar(). envolver = NBTItem.get(item); tipoApi/idApi = los estaticos de MMOItems
     * (null sin MMOItems); texto = NBTItem.getString (null sin MythicLib).
     */
    private record Metodos(String origen, Plugin mmoitems, Method envolver, Method tipoApi, Method idApi, Method texto) {
    }

    private static volatile Metodos metodos;
    private static volatile Logger log = Logger.getLogger("Calamity");
    private static volatile boolean avisado;

    private LectorMmo() {
    }

    /** Busca MMOItems y MythicLib y resuelve los metodos. Se puede volver a llamar (recarga). */
    static void detectar(Logger logger) {
        if (logger != null) log = logger;
        Plugin mmo = Bukkit.getPluginManager().getPlugin("MMOItems");
        Plugin lib = Bukkit.getPluginManager().getPlugin("MythicLib");
        Metodos m = null;
        if (mmo != null && mmo.isEnabled()) {
            try {
                ClassLoader cl = mmo.getClass().getClassLoader();
                Class<?> principal = Class.forName(MMOITEMS, false, cl);
                Class<?> nbt = Class.forName(NBT_ITEM, false, cl);
                m = new Metodos("MMOItems " + mmo.getPluginMeta().getVersion(), mmo, nbt.getMethod("get", ItemStack.class),
                        principal.getMethod("getTypeName", nbt), principal.getMethod("getID", nbt),
                        nbt.getMethod("getString", String.class));
            } catch (Throwable t) {
                log.warning("[Calamity] MMOItems esta pero su API no encaja (" + t + "); equipo.yml prueba con MythicLib.");
            }
        }
        if (m == null && lib != null && lib.isEnabled()) {
            try {
                Class<?> nbt = Class.forName(NBT_ITEM, false, lib.getClass().getClassLoader());
                m = new Metodos("MythicLib " + lib.getPluginMeta().getVersion(), null, nbt.getMethod("get", ItemStack.class),
                        null, null, nbt.getMethod("getString", String.class));
            } catch (Throwable t) {
                log.warning("[Calamity] MythicLib esta pero no encaja (" + t + "); equipo.yml solo se lee del PDC.");
            }
        }
        metodos = m;
        avisado = false;
    }

    /** "MMOItems 6.10.1-SNAPSHOT", "MythicLib 1.7.1-SNAPSHOT" o "solo PDC", para /calamidad equipo. */
    static String origen() {
        Metodos m = metodos;
        return m == null ? "solo PDC (ni MMOItems ni MythicLib)" : m.origen();
    }

    /** Si MMOItems en persona esta enganchado: solo entonces se pueden crear items (autotest). */
    static boolean conMmoItems() {
        Metodos m = metodos;
        return m != null && m.mmoitems() != null && m.mmoitems().isEnabled();
    }

    // ------------------------------------------------------------------ leer

    /** El "TIPO.ID" de un item y por donde salio, o null si no es de MMOItems. Hilo principal. */
    static Lectura leer(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        Metodos m = metodos;
        Object nbt = null;
        if (m != null) {
            try {
                nbt = m.envolver().invoke(null, item);
            } catch (Throwable t) {
                avisar(m, t);
            }
        }
        if (nbt != null && m.tipoApi() != null) {
            try {
                String id = juntar(m.tipoApi().invoke(null, nbt), m.idApi().invoke(null, nbt));
                if (id != null) return new Lectura(id, Fuente.API);
            } catch (Throwable t) {
                avisar(m, t);
            }
        }
        if (nbt != null && m.texto() != null) {
            try {
                String id = juntar(m.texto().invoke(nbt, TIPO), m.texto().invoke(nbt, ID));
                if (id != null) return new Lectura(id, Fuente.NBT);
            } catch (Throwable t) {
                avisar(m, t);
            }
        }
        String id = porPdc(item);
        return id == null ? null : new Lectura(id, Fuente.PDC);
    }

    /** Solo el escalon 2 (NBTItem a mano), para que el autotest lo pruebe aparte de la API. */
    static String porNbt(ItemStack item) {
        Metodos m = metodos;
        if (m == null || m.texto() == null || item == null || item.getType().isAir()) return null;
        try {
            Object nbt = m.envolver().invoke(null, item);
            return nbt == null ? null : juntar(m.texto().invoke(nbt, TIPO), m.texto().invoke(nbt, ID));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Solo el escalon 3: el PDC (mmoitems:mmoitems_item_type y _id), o null. */
    static String porPdc(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return juntar(pdc.get(PDC_TIPO, PersistentDataType.STRING), pdc.get(PDC_ID, PersistentDataType.STRING));
    }

    /** "tipo" y "id" -> "TIPO.ID", o null si falta alguno. */
    private static String juntar(Object tipo, Object id) {
        if (!(tipo instanceof String t) || t.isBlank() || !(id instanceof String i) || i.isBlank()) return null;
        return (t.trim() + "." + i.trim()).toUpperCase(Locale.ROOT);
    }

    /** Un cambio de version de su lado no puede tumbar nada: se dice una vez y sigue el siguiente escalon. */
    private static void avisar(Metodos m, Throwable t) {
        if (avisado) return;
        avisado = true;
        log.warning("[Calamity] Leer un item por " + m.origen() + " ha fallado (" + t
                + "); equipo.yml sigue con el siguiente escalon (NBTItem o PDC).");
    }

    // ------------------------------------------------------------ plantillas

    /** Si MMOItems tiene ahora la plantilla "TIPO.ID". False sin MMOItems. */
    static boolean existe(String tipoPuntoId) {
        Object mmo = mmoitems();
        String[] partes = partir(tipoPuntoId);
        if (mmo == null || partes == null) return false;
        try {
            Object types = mmo.getClass().getMethod("getTypes").invoke(mmo);
            Object tipo = types.getClass().getMethod("get", String.class).invoke(types, partes[0]);
            if (tipo == null) return false;
            Object plantillas = mmo.getClass().getMethod("getTemplates").invoke(mmo);
            // hasTemplate(Type, String) buscado por su parametro declarado: el tipo real puede ser una subclase.
            for (Method m : plantillas.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!m.getName().equals("hasTemplate") || p.length != 2 || p[1] != String.class
                        || !p[0].isInstance(tipo)) continue;
                return Boolean.TRUE.equals(m.invoke(plantillas, tipo, partes[1]));
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Un item nuevo de la plantilla "TIPO.ID", o null. Hilo principal: MMOItems lanza su evento de creacion. */
    static ItemStack crear(String tipoPuntoId) {
        Object mmo = mmoitems();
        String[] partes = partir(tipoPuntoId);
        if (mmo == null || partes == null) return null;
        try {
            Object it = mmo.getClass().getMethod("getItem", String.class, String.class).invoke(mmo, partes[0], partes[1]);
            return it instanceof ItemStack s && !s.getType().isAir() ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** "TIPO.ID" de la primera plantilla que tenga MMOItems, o null. Para el autotest sin piezas propias. */
    static String algunaPlantilla() {
        Object mmo = mmoitems();
        if (mmo == null) return null;
        try {
            Object plantillas = mmo.getClass().getMethod("getTemplates").invoke(mmo);
            Collection<?> todas = (Collection<?>) plantillas.getClass().getMethod("collectTemplates").invoke(plantillas);
            for (Object t : todas) {
                Object tipo = t.getClass().getMethod("getType").invoke(t);
                Object idTipo = tipo.getClass().getMethod("getId").invoke(tipo);
                Object id = t.getClass().getMethod("getId").invoke(t);
                if (idTipo != null && id != null) return (idTipo + "." + id).toUpperCase(Locale.ROOT);
            }
        } catch (Throwable ignorado) {
            // Sin plantilla que ofrecer.
        }
        return null;
    }

    private static Object mmoitems() {
        Metodos m = metodos;
        return m == null || m.mmoitems() == null || !m.mmoitems().isEnabled() ? null : m.mmoitems();
    }

    /** "tipo.id" -> {"TIPO", "ID"}, o null. Parte por el PRIMER punto: los ids de MMOItems no llevan punto. */
    static String[] partir(String tipoPuntoId) {
        if (tipoPuntoId == null) return null;
        int punto = tipoPuntoId.indexOf('.');
        if (punto <= 0 || punto == tipoPuntoId.length() - 1) return null;
        return new String[]{tipoPuntoId.substring(0, punto).trim().toUpperCase(Locale.ROOT),
                tipoPuntoId.substring(punto + 1).trim().toUpperCase(Locale.ROOT)};
    }
}
