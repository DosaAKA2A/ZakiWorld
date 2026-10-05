package net.ederus.calamity.hardcore;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.calamity.CalamityPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Los dos objetos que hacen habitable a Calamity.
 *
 * Nada de resource pack (esta prohibido en Ederus): son objetos vanilla con nombre,
 * brillo y una marca en el PersistentDataContainer, que es lo que de verdad los
 * identifica. Un jugador puede renombrar una botella como quiera; sin la marca no
 * funciona. Aqui viven tambien la Esencia, el Fragmento de Masamune y (1.10) el Reclamo.
 */
public final class ItemsCalamity {

    public static final TextColor VERDE = TextColor.color(0x8FD6A8);
    public static final TextColor MORADO = TextColor.color(0xC792EA);

    private final CalamityPlugin plugin;
    /** Marca del frasco; su valor es cuantos tragos le quedan. */
    private final NamespacedKey claveFrasco;
    /** Marca del cristal de regreso. */
    private final NamespacedKey claveCristal;
    /** Marca de la esencia, la moneda con la que se recarga el frasco. */
    private final NamespacedKey claveEsencia;

    public ItemsCalamity(CalamityPlugin plugin) {
        this.plugin = plugin;
        /* Namespace "edm" a mano: estos items ya estan repartidos por el servidor con
         * esa marca. Aunque Lethal World ya no sea un modulo de EDM, la clave no puede
         * cambiar o los frascos, cristales y esencias de los cofres dejarian de valer. */
        this.claveFrasco = new NamespacedKey("edm", "frasco_calma");
        this.claveCristal = new NamespacedKey("edm", "cristal_regreso");
        this.claveEsencia = new NamespacedKey("edm", "esencia_calamidad");
    }

    // ------------------------------------------------------------------ el frasco

    /** El Frasco de Calma con los tragos que se le digan. */
    public ItemStack frasco(int usos) {
        int max = plugin.getConfig().getInt("hardcore.frasco.usos", 3);
        int quedan = Math.max(0, Math.min(max, usos));
        ItemStack item = new ItemStack(Material.POTION);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Ficha.tono("frasco").nombre(NOMBRE_FRASCO));
            meta.lore(fichaFrasco(hardcore(), quedan).lore());
            if (meta instanceof org.bukkit.inventory.meta.PotionMeta pm) {
                pm.setColor(org.bukkit.Color.fromRGB(0x8FD6A8));
            }
            meta.getPersistentDataContainer().set(claveFrasco, PersistentDataType.INTEGER, quedan);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Ese mismo frasco (una unidad) con otros tragos: conserva todo lo que ya llevaba (ligado, prestado, sus
     * marcas) y rehace el nombre y el lore con Ficha.renovar, que guarda las lineas de ligado y de prestado.
     * Lo usan beber (Hardcore) y recargar (Altar): antes hacian uno nuevo con frasco(n) y el de un Kit dejaba de
     * ser prestado al primer trago. Si no es un frasco, uno nuevo.
     */
    public ItemStack conTragos(ItemStack frasco, int usos) {
        if (!esFrasco(frasco)) return frasco(usos);
        int max = plugin.getConfig().getInt("hardcore.frasco.usos", 3);
        int quedan = Math.max(0, Math.min(max, usos));
        ItemStack it = frasco.clone();
        it.setAmount(1);
        it.editMeta(m -> m.getPersistentDataContainer().set(claveFrasco, PersistentDataType.INTEGER, quedan));
        ItemStack r = Ficha.renovar(it, Ficha.tono("frasco").nombre(NOMBRE_FRASCO), fichaFrasco(hardcore(), quedan).lore());
        return r == null ? it : r;
    }

    /** Tragos que le quedan al frasco, o -1 si el objeto no es un frasco. */
    public int tragos(ItemStack item) {
        if (item == null || item.getItemMeta() == null) return -1;
        Integer v = item.getItemMeta().getPersistentDataContainer()
                .get(claveFrasco, PersistentDataType.INTEGER);
        return v == null ? -1 : v;
    }

    public boolean esFrasco(ItemStack item) {
        return tragos(item) >= 0;
    }

    // ----------------------------------------------------------------- el cristal

    /** El Cristal de Regreso: la unica salida que no es el portal. */
    public ItemStack cristal() {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Ficha.tono("cristal").nombre(NOMBRE_CRISTAL));
            meta.lore(fichaCristal(hardcore()).lore());
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(claveCristal, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean esCristal(ItemStack item) {
        return item != null && item.getItemMeta() != null
                && item.getItemMeta().getPersistentDataContainer()
                        .has(claveCristal, PersistentDataType.BYTE);
    }

    // ----------------------------------------------------------------- la esencia

    /**
     * Esencia de Calamidad: lo que sueltan los mobs y con lo que se recarga el frasco.
     *
     * El material sale de hardcore.esencias.material (DIS M2, "Esencias vendibles"): la
     * lagrima de ghast se vendia en /shop y, si la tienda compra por material, una Esencia
     * seria dinero. Cambiarlo solo afecta a las NUEVAS; todas se reconocen por la marca, asi
     * que las que ya circulan siguen valiendo.
     */
    public ItemStack esencia(int cantidad) {
        ItemStack item = new ItemStack(materialEsencia(), Math.max(1, Math.min(64, cantidad)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Ficha.tono("esencia").nombre(NOMBRE_ESENCIA));
            meta.lore(fichaEsencia().lore());
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(claveEsencia, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ------------------------------------------------------- el Fragmento de Masamune

    /**
     * Fragmento de Masamune: lo que deja Ambush a su presa, en fisico (antes era un credito). La
     * Forja de Vael pide cinco para la Masamune. Chatarra de netherita con el nombre en el gris
     * acero de la Masamune y la marca lethal_world:fragmento_masamune, que es lo que lo identifica
     * aunque lo renombren. Se apila; no entra en recetas ni en hornos (ObjetosCalamity) y el
     * Mercader no lo compra (solo compra Reliquias). Sale sin ligar: lo liga quien lo entrega.
     */
    public static ItemStack fragmentoMasamune(int cantidad) {
        ItemStack item = new ItemStack(Material.NETHERITE_SCRAP, Math.max(1, Math.min(64, cantidad)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Ficha.tono("masamune").nombre(FragmentosMasamune.NOMBRE));
            meta.lore(fichaFragmento(Ficha.cfg()).lore());
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(Marcas.FRAGMENTO_MASAMUNE, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Si es un Fragmento de Masamune (por la marca: da igual como se llame). */
    public static boolean esFragmentoMasamune(ItemStack item) {
        return Marcas.tiene(item, Marcas.FRAGMENTO_MASAMUNE);
    }

    // ------------------------------------------------------------------- el Reclamo

    /**
     * Calamity 1.10 · El Reclamo: un cuerno de cabra que llama al minijefe del bioma donde suena
     * (Reclamo, que lo atiende). Se reconoce por la marca lethal_world:reclamo, como el Cristal por
     * la suya, y el nombre lleva el degradado de los minijefes (Paleta.minijefe).
     *
     * Sale SIN instrumento a proposito. En 26.x el cuerno trae uno de serie (componente
     * minecraft:instrument), y con el el cliente lo haria sonar por su cuenta al pulsar, antes de que
     * el servidor diga nada, y le pondria el enfriamiento del cuerno de verdad (7 s). Sin instrumento
     * el uso vanilla no hace nada en ningun lado y el sonido lo pone Reclamo. Quitarlo es un parche
     * del objeto: sobrevive a ligarlo y a guardarlo en los premios pendientes (ItemMeta lo conserva).
     */
    public static ItemStack reclamo() {
        ItemStack item = new ItemStack(Material.GOAT_HORN);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Ficha.tono("reclamo").nombre(NOMBRE_RECLAMO));
            meta.lore(fichaReclamo(Ficha.cfg()).lore());
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(Marcas.RECLAMO, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        try {
            item.unsetData(DataComponentTypes.INSTRUMENT);
        } catch (Throwable sinApi) {
            // Sin la API de componentes el Reclamo sigue valiendo: Reclamo cancela el uso vanilla igual.
        }
        return item;
    }

    /** Si es un Reclamo (por la marca: da igual como se llame). */
    public static boolean esReclamo(ItemStack item) {
        return Marcas.tiene(item, Marcas.RECLAMO);
    }

    // ------------------------------------------------------- los lores (Ficha)

    /*
     * Calamity 1.10 · El lore de cada objeto, con la plantilla comun (Ficha) y las cifras leidas de la
     * config viva (la seccion hardcore). Puros: el autotest "fichas" los compara sin servidor.
     */

    private ConfigurationSection hardcore() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("hardcore");
        return s == null ? new YamlConfiguration() : s;
    }

    static Ficha fichaFrasco(ConfigurationSection c, int quedan) {
        int max = c.getInt("frasco.usos", 3);
        int q = Math.max(0, Math.min(max, quedan));
        int precio = Math.max(0, c.getInt("frasco.esencias-por-trago", 1));
        Ficha f = new Ficha(Ficha.tono("frasco")).cabecera("Consumible", "Cordura", 0)
                .historia("Agua del último manantial limpio de Bracken. Sabe a algo que ya no existe.")
                .seccion("Al beberlo")
                .dato("Cada trago devuelve {" + c.getInt("frasco.cordura", 40) + "} de cordura")
                .dato((q == 1 ? "Le queda {1} de {" : "Le quedan {" + q + "} de {") + max + "} tragos");
        if (precio > 0) f.dato("Recargar un trago: " + Ficha.cantidad(precio, "Esencia", "Esencias"));
        return f.accion("Clic derecho para beber.").hueco().nota("Se recarga en el [Altar].");
    }

    static Ficha fichaCristal(ConfigurationSection c) {
        return new Ficha(Ficha.tono("cristal")).cabecera("Consumible", "Regreso", 0)
                .historia("Vibra en el mismo tono que la puerta. Quien lo escucha quieto vuelve a casa.")
                .seccion("Al usarlo")
                .dato("Te devuelve al spawn de Calamity.")
                .dato("Quieto {" + c.getInt("cristal.segundos", 5) + "} s: si te mueves, se apaga.")
                .dato("En combate no funciona.")
                .accion("Clic derecho y quédate quieto.")
                .hueco().nota("Para salir de Calamity, cruza el portal.").nota("Se gasta al usarlo.");
    }

    /** Sin cifras de la config a proposito: todas las Esencias nuevas llevan el mismo lore y se apilan. */
    static Ficha fichaEsencia() {
        return new Ficha(Ficha.tono("esencia")).cabecera("Moneda", "Calamity", 0)
                .historia("Lo que queda de algo de Calamity cuando muere de verdad.")
                .seccion("Llévasela a Oren, en el spawn")
                .dato("Te la ingresa en tu saldo.")
                .dato("Con el saldo pagas en el [Altar] y en la [Forja].")
                .hueco().nota("La sueltan los mobs y los cofres.")
                .nota("Si mueres en Calamity, la pierdes.")
                .nota("Fuera de Calamity no se puede guardar.");
    }

    static Ficha fichaFragmento(ConfigurationSection c) {
        Ficha f = new Ficha(Ficha.tono("masamune")).cabecera("Material de forja", "Masamune", 0)
                .historia("Un trozo de la katana de Ambush. Todavía corta a quien lo aprieta.")
                .seccion("Para qué sirve");
        List<Ficha.Uso> usos = Ficha.usosDeEntrega(c, FragmentosMasamune.OBJETO);
        if (usos.isEmpty()) usos = List.of(new Ficha.Uso("Masamune", 5, ""));
        for (Ficha.Uso u : usos) {
            f.dato("<" + u.da() + ">: {" + u.cantidad() + "}" + (u.con().isEmpty()
                    ? (u.cantidad() == 1 ? " [Fragmento]" : " [Fragmentos]") : " y " + u.con()));
        }
        return f.accion("Llévalos a la Forja de Vael.")
                .hueco().nota("Si mueres en Calamity, lo pierdes.");
    }

    static Ficha fichaReclamo(ConfigurationSection c) {
        int segundos = Math.max(0, Math.min(30, c.getInt("minijefes.reclamo.segundos", 3)));
        int tope = c.getInt("minijefes.reclamo.tope-dia", 6);
        int descanso = c.getInt("minijefes.cada-minutos", 10);
        Ficha f = new Ficha(Ficha.tono("reclamo")).cabecera("Llamada", "Minijefes", 0)
                .historia("Cuerno de cabra tallado en hueso. Lo que manda en el bioma lo oye y viene.")
                .seccion("Al hacerlo sonar")
                .dato("Llama al minijefe del bioma donde estás.")
                .dato("Llega a los {" + segundos + "} s.");
        if (descanso > 0) f.dato("Descanso de {" + descanso + "} min entre minijefes.");
        if (tope > 0) f.dato("Hasta {" + tope + "} al día.");
        return f.dato("No responde con la Parca detrás.")
                .accion("Clic derecho para hacerlo sonar, lejos del spawn de Calamity.")
                .hueco().nota("Solo se gasta si el minijefe llega.");
    }

    static final String NOMBRE_FRASCO = "Frasco de Calma";
    static final String NOMBRE_CRISTAL = "Cristal de Regreso";
    static final String NOMBRE_ESENCIA = "Esencia de Calamidad";
    static final String NOMBRE_RECLAMO = "Reclamo";

    /**
     * Rama lore-items · El mismo objeto con el nombre y el lore de hoy (Frasco, Cristal, Esencia, Fragmento de
     * Masamune, Reclamo), o null si no es uno de estos o ya los lleva. Conserva todo lo demas (marcas, tragos,
     * la linea de ligado y la de prestado). Lo llama Reliquias.renovarInventario.
     */
    public ItemStack renovado(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        ConfigurationSection c = hardcore();
        if (esFrasco(it)) return Ficha.renovar(it, Ficha.tono("frasco").nombre(NOMBRE_FRASCO), fichaFrasco(c, tragos(it)).lore());
        if (esCristal(it)) return Ficha.renovar(it, Ficha.tono("cristal").nombre(NOMBRE_CRISTAL), fichaCristal(c).lore());
        if (esEsencia(it)) return Ficha.renovar(it, Ficha.tono("esencia").nombre(NOMBRE_ESENCIA), fichaEsencia().lore());
        if (esFragmentoMasamune(it)) {
            return Ficha.renovar(it, Ficha.tono("masamune").nombre(FragmentosMasamune.NOMBRE), fichaFragmento(c).lore());
        }
        if (esReclamo(it)) return Ficha.renovar(it, Ficha.tono("reclamo").nombre(NOMBRE_RECLAMO), fichaReclamo(c).lore());
        return null;
    }
    /** Ultimo valor raro de esencias.material ya avisado, para no llenar la consola. */
    private String materialAvisado;

    private Material materialEsencia() {
        String nombre = plugin.getConfig().getString("hardcore.esencias.material", "GHAST_TEAR");
        Material m = nombre == null ? null : Material.matchMaterial(nombre.trim());
        if (m != null && m.isItem() && !m.isAir()) return m;
        if (nombre != null && !nombre.equals(materialAvisado)) {
            materialAvisado = nombre;
            plugin.getLogger().warning("[Calamity] hardcore.esencias.material \"" + nombre
                    + "\" no es un objeto; las Esencias salen como GHAST_TEAR.");
        }
        return Material.GHAST_TEAR;
    }

    public boolean esEsencia(ItemStack item) {
        return item != null && item.getItemMeta() != null
                && item.getItemMeta().getPersistentDataContainer()
                        .has(claveEsencia, PersistentDataType.BYTE);
    }
}
