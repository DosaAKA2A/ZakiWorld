package net.ederus.edm.dungeonloot;

import org.bukkit.NamespacedKey;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

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

    public static final TextColor MARCA = TextColor.color(0x8FB8C4);

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

    /** Una linea de chat con el prefijo del modulo. */
    public Component aviso(Component texto) {
        return Component.text("BÓVEDAS ", MARCA, TextDecoration.BOLD)
                .append(Component.text("» ", NamedTextColor.DARK_GRAY))
                .append(texto);
    }

    public Component aviso(String texto) {
        return aviso(Component.text(texto, NamedTextColor.GRAY));
    }
}
