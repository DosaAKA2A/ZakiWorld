package net.ederus.edm.dungeonloot;

import java.io.File;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;
import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.Textos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * DungeonLoot: las cajas de mazmorra.
 *
 * Una caja se diseña una vez —tipo de boveda, botin, objeto unico— y a partir de
 * ahi da dos cosas: una llave, que se puede meter en el botin de cualquier mob, y
 * un bloque, que se planta donde haga falta y las veces que haga falta.
 *
 * Vive en el nucleo y no en un plugin aparte porque reutiliza la tabla de botin
 * de las anomalias, la deteccion de regiones de WorldGuard y la entrada por chat.
 */
public final class DungeonLootPlugin extends Module {

    /** El azul de marca de Ederus, el mismo del resto de los menus. */
    public static final TextColor MARCA = Estilo.MARCA;
    public static final TextColor CLARO = Estilo.CLARO;

    private static final int MENSAJES_VERSION = 1;

    private final Textos textos = new Textos();
    private Registro registro;
    private Bovedas bovedas;
    private MenuDl menu;

    private NamespacedKey claveCaja;
    private NamespacedKey claveLlave;

    public DungeonLootPlugin(EDMPlugin core) {
        super(core, "dungeonloot", "DungeonLoot");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        migrar("mensajes.yml", MENSAJES_VERSION);
        textos.cargar(new File(getDataFolder(), "mensajes.yml"));

        claveCaja = new NamespacedKey(this, "caja");
        claveLlave = new NamespacedKey(this, "llave");

        registro = new Registro(this);
        registro.cargar();

        bovedas = new Bovedas(this);
        menu = new MenuDl(this);
        core.getServer().getPluginManager().registerEvents(bovedas, this);
        core.getServer().getPluginManager().registerEvents(menu, this);

        ComandoDl comando = new ComandoDl(this);
        var cmd = core.getCommand("dl");
        if (cmd != null) {
            cmd.setExecutor(comando);
            cmd.setTabCompleter(comando);
        } else {
            getLogger().warning("El comando /dl no esta en el plugin.yml de EDM.");
        }
    }

    @Override
    public void onDisable() {
        if (registro != null) registro.guardar();
    }

    @Override
    public String recargar() {
        textos.cargar(new File(getDataFolder(), "mensajes.yml"));
        registro.cargar();
        return registro.cajas().size() + " caja(s), " + registro.bovedas().size() + " boveda(s).";
    }

    /* ------------------------------------------------- API para otros plugins (1.78.1) */

    /**
     * La caja con ese id; si no existe, se crea con estos datos y se guarda. Si ya existe, su diseño
     * (nombre, botin, color) se respeta, porque un admin puede haberlo tocado en /dl; solo se
     * asegura lo que el plugin que la pide necesita para funcionar: el tipo, una por jugador y el
     * botin externo. null si el id no vale (solo a-z, 0-9, _ y -).
     */
    public Caja asegurarCaja(String id, String nombre, Caja.Tipo tipo, String nombreLlave, int color,
                             boolean unaPorJugador, boolean botinExterno) {
        if (registro == null) return null;
        boolean nueva = registro.caja(id) == null;
        Caja c = registro.crearConId(id, nombre, tipo);
        if (c == null) return null;
        boolean cambia = nueva || c.tipo() != tipo || c.unaPorJugador() != unaPorJugador
                || c.botinExterno() != botinExterno;
        if (nueva) {
            c.colorRgb(color);
            c.nombreLlave(nombreLlave);
        }
        c.tipo(tipo);
        c.unaPorJugador(unaPorJugador);
        c.botinExterno(botinExterno);
        if (cambia) {
            registro.guardar();
            getLogger().info("DungeonLoot: caja " + c.id() + (nueva ? " creada" : " ajustada") + " por otro plugin.");
        }
        return c;
    }

    /**
     * Planta una boveda de esa caja en ese bloque: lo pone como boveda (ominosa o comun), la apunta
     * y guarda. El bloque tiene que estar en un chunk cargado. null si la caja no existe o el sitio
     * ya tiene una.
     */
    public Boveda plantar(String cajaId, Block bloque) {
        Caja c = registro == null ? null : registro.caja(cajaId);
        if (c == null || bloque == null) return null;
        String w = bloque.getWorld().getName();
        if (registro.bovedaEn(w, bloque.getX(), bloque.getY(), bloque.getZ()) != null) return null;
        bloque.setType(c.tipo().block(), false);
        Boveda b = registro.plantar(c, w, bloque.getX(), bloque.getY(), bloque.getZ());
        bovedas.vestir(bloque, c);
        registro.guardar();
        return b;
    }

    /**
     * Quita una boveda del mapa. Con quitarBloque, si su chunk esta cargado y el bloque sigue siendo
     * una boveda, lo deja en aire (nunca carga el chunk: si no esta, el bloque se queda y lo tiene que
     * quitar quien llama cuando se cargue). Devuelve si el bloque se quito.
     */
    public boolean retirar(Boveda b, boolean quitarBloque) {
        if (registro == null || b == null) return false;
        registro.quitar(b);
        registro.guardar();
        if (!quitarBloque) return false;
        org.bukkit.World w = getServer().getWorld(b.worldName());
        if (w == null || !w.isChunkLoaded(b.x() >> 4, b.z() >> 4)) return false;
        Block bl = w.getBlockAt(b.x(), b.y(), b.z());
        if (bl.getType() == Material.VAULT) bl.setType(Material.AIR, false);
        return true;
    }

    /** La boveda plantada en ese bloque, o null. */
    public Boveda bovedaEn(Block bloque) {
        if (registro == null || bloque == null) return null;
        return registro.bovedaEn(bloque.getWorld().getName(), bloque.getX(), bloque.getY(), bloque.getZ());
    }

    /** n llaves de esa caja, o null si no existe. */
    public ItemStack llave(String cajaId, int n) {
        Caja c = registro == null ? null : registro.caja(cajaId);
        return c == null ? null : c.llave(claveLlave, n);
    }

    /** El id de la caja que abre ese objeto si es una llave nuestra; null si no. */
    public String cajaDeLlave(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta() || claveLlave == null) return null;
        return it.getItemMeta().getPersistentDataContainer().get(claveLlave, PersistentDataType.STRING);
    }

    public Registro registro() {
        return registro;
    }

    public Bovedas bovedas() {
        return bovedas;
    }

    public MenuDl menu() {
        return menu;
    }

    public NamespacedKey claveCaja() {
        return claveCaja;
    }

    public NamespacedKey claveLlave() {
        return claveLlave;
    }

    public Textos textos() {
        return textos;
    }

    /** Un mensaje de mensajes.yml, ya con su prefijo. */
    public Component texto(String clave, String respaldo, String... pares) {
        return textos.de(clave, respaldo, pares);
    }

    public void di(CommandSender a, String clave, String respaldo, String... pares) {
        textos.manda(a, clave, respaldo, pares);
    }
}
