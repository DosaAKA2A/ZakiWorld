package com.ederus.tienda;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;

/**
 * Tienda propia de Ederus. Version 0: sin interfaz, solo el motor de compra y
 * venta con log y topes, para poder probarlo a fondo antes de que lo vea nadie.
 *
 * Convive con EconomyShopGUI: no toca su configuracion ni sus datos.
 */
public final class EderusTienda extends JavaPlugin {

    /** Se compila contra la API de Paper 26 pero la version se mantiene aqui,
     *  como en EderusMain. Cambiar tambien en pom.xml y plugin.yml. */
    private static final String VERSION = "0.1.0";

    private final Catalogo catalogo = new Catalogo();
    private Topes topes;
    private Registro registro;
    private Motor motor;
    private Economy economia;
    private BukkitTask tareaGuardado;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!cargarCatalogo()) {
            // Arrancar con precios malos es peor que no arrancar: seria dinero regalado.
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        topes = new Topes(new File(getDataFolder(), "topes.yml"));
        topes.cargar();
        registro = new Registro(new File(getDataFolder(), "registro"));

        ComandoTienda comando = new ComandoTienda(this, catalogo, topes);
        if (getCommand("etienda") != null) {
            getCommand("etienda").setExecutor(comando);
            getCommand("etienda").setTabCompleter(comando);
        }

        /* Vault se engancha en el primer tick, no aqui: el proveedor de economia
           (EssentialsX y compania) se registra en SU onEnable, y el orden de
           carga entre plugins no esta garantizado. Enganchar aqui hacia que la
           tienda se apagara sola segun quien arrancara primero. */
        getServer().getScheduler().runTask(this, () -> {
            if (!engancharVault()) {
                getLogger().severe("No hay economia de Vault. La tienda se queda apagada.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            motor = new Motor(catalogo, topes, registro, economia);
            getLogger().info("Economia enganchada: " + economia.getName());
        });

        long cada = Math.max(20L * 30, getConfig().getLong("guardado-segundos", 120) * 20L);
        tareaGuardado = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            topes.limpiar(catalogo);
            topes.guardar();
        }, cada, cada);

        getLogger().info("EderusTienda " + VERSION + " activa | " + catalogo.total()
                + " articulos en " + catalogo.categorias().size() + " categorias");
    }

    @Override
    public void onDisable() {
        if (tareaGuardado != null) tareaGuardado.cancel();
        if (topes != null) topes.guardar();
        if (registro != null) registro.cerrar();
    }

    private boolean engancharVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economia = rsp.getProvider();
        return economia != null;
    }

    /** Devuelve false y explica el motivo si el catalogo no esta sano. */
    public boolean cargarCatalogo() {
        File fichero = new File(getDataFolder(), "precios.yml");
        if (!fichero.exists()) {
            saveResource("precios.yml", false);
        }
        try {
            catalogo.cargar(fichero);
            return true;
        } catch (IllegalStateException e) {
            getLogger().severe("precios.yml no se pudo cargar: " + e.getMessage());
            return false;
        }
    }

    public String version() { return VERSION; }

    /** null hasta que engancha Vault en el primer tick. */
    public Motor motor() { return motor; }
}
