package net.ederus.edm.dungeonloot;

import java.io.File;

import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;

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
