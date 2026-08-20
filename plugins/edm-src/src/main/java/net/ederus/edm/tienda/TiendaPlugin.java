package net.ederus.edm.tienda;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;

/**
 * Modulo de tienda: sustituto propio de EconomyShopGUI.
 *
 * Version 0: sin interfaz, solo el motor de compra y venta con registro y topes,
 * para poder probarlo a fondo antes de que lo vea nadie. Convive con
 * EconomyShopGUI sin tocar su configuracion ni sus datos.
 */
public final class TiendaPlugin extends Module {

    private final Catalogo catalogo = new Catalogo();
    private Topes topes;
    private Registro registro;
    private Motor motor;
    private MenuTienda menu;
    private final Iconos iconos = new Iconos();
    private Mercado mercado;
    private Economy economia;
    private BukkitTask tareaGuardado;

    public TiendaPlugin(EDMPlugin core) {
        super(core, "tienda", "EderusTienda");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!cargarCatalogo()) {
            // Arrancar con precios malos es peor que no arrancar: seria dinero regalado.
            throw new IllegalStateException("precios.yml invalido; el modulo no arranca");
        }

        topes = new Topes(new File(getDataFolder(), "topes.yml"));
        topes.cargar();
        mercado = new Mercado(new File(getDataFolder(), "mercado.yml"));
        mercado.configurar(getConfig().getConfigurationSection("mercado"));
        mercado.cargar();
        registro = new Registro(new File(getDataFolder(), "registro"));

        saveResource("iconos.yml", false);
        iconos.cargar(new File(getDataFolder(), "iconos.yml"));
        menu = new MenuTienda(this, catalogo, topes, iconos);
        core.getServer().getPluginManager().registerEvents(menu, this);

        ComandoTienda comando = new ComandoTienda(this, catalogo, topes);
        /* Como el resto de modulos: el plugin que ve Bukkit es EDM, asi que hay
         * que registrarse como ejecutor a mano o /etienda solo imprime su uso. */
        for (String nombre : new String[]{"etienda", "shop"}) {
            var cmd = core.getCommand(nombre);
            if (cmd != null) {
                cmd.setExecutor(comando);
                cmd.setTabCompleter(comando);
            } else {
                getLogger().warning("El comando /" + nombre + " no esta en el plugin.yml de EDM.");
            }
        }
        /* Con EconomyShopGUI instalado, /shop se lo queda el que Bukkit decida.
         * Mientras convivan, la forma inequivoca de abrir la nuestra es /edm:shop. */
        if (core.getServer().getPluginManager().getPlugin("EconomyShopGUI") != null
                || core.getServer().getPluginManager().getPlugin("EconomyShopGUI-Premium") != null) {
            getLogger().warning("EconomyShopGUI esta instalado: /shop puede abrir el suyo. Usa /edm:shop.");
        }

        /* Vault se engancha en el primer tick, no aqui: el proveedor de economia
           (EssentialsX) se registra en SU onEnable y el orden de carga entre
           plugins no esta garantizado. Enganchar aqui apagaba el modulo segun
           quien arrancara primero. */
        core.getServer().getScheduler().runTask(core, () -> {
            if (!engancharVault()) {
                getLogger().severe("No hay economia de Vault. La tienda se queda sin motor.");
                return;
            }
            motor = new Motor(catalogo, topes, registro, economia, mercado);
            getLogger().info("Economia enganchada: " + economia.getName());
        });

        long cada = Math.max(20L * 30, getConfig().getLong("guardado-segundos", 120) * 20L);
        tareaGuardado = core.getServer().getScheduler().runTaskTimerAsynchronously(core, () -> {
            topes.limpiar(catalogo);
            topes.guardar();
            mercado.guardar();
        }, cada, cada);

        getLogger().info("Tienda activa | " + catalogo.total() + " articulos en "
                + catalogo.categorias().size() + " categorias ("
                + catalogo.variantes() + " variantes) | " + iconos.configurados() + " iconos");
    }

    @Override
    public void onDisable() {
        if (tareaGuardado != null) tareaGuardado.cancel();
        if (topes != null) topes.guardar();
        if (mercado != null) mercado.guardar();
        if (registro != null) registro.cerrar();
    }

    private boolean engancharVault() {
        if (core.getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp =
                core.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economia = rsp.getProvider();
        return economia != null;
    }

    /** Devuelve false y explica el motivo si el catalogo no esta sano. */
    public boolean cargarCatalogo() {
        File fichero = new File(getDataFolder(), "precios.yml");
        if (!fichero.exists()) saveResource("precios.yml", false);
        try {
            catalogo.cargar(fichero);
            return true;
        } catch (IllegalStateException e) {
            getLogger().severe("precios.yml no se pudo cargar: " + e.getMessage());
            return false;
        }
    }

    @Override
    public String recargar() {
        iconos.cargar(new File(getDataFolder(), "iconos.yml"));
        reloadConfig();
        if (mercado != null) mercado.configurar(getConfig().getConfigurationSection("mercado"));
        if (!cargarCatalogo()) return "el precios.yml tiene errores; se mantiene el anterior";
        return catalogo.total() + " articulos en " + catalogo.categorias().size() + " categorias";
    }

    public MenuTienda menu() { return menu; }

    public Mercado mercado() { return mercado; }

    /** null hasta que engancha Vault en el primer tick. */
    public Motor motor() { return motor; }
}
