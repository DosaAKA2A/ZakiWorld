package net.ederus.edm.tienda;

import net.ederus.edm.comun.EntradaChat;

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
    private PantallaCantidad pantalla;
    private EntradaChat chat;
    private final Secciones secciones = new Secciones();
    private Rotacion rotacion;
    private Compras compras;
    private final Mensajes mensajes = new Mensajes();
    private Mercado mercado;
    private Economy economia;
    private BukkitTask tareaGuardado;
    private BukkitTask tareaRotacion;

    public TiendaPlugin(EDMPlugin core) {
        super(core, "tienda", "EderusTienda");
    }

    @Override
    public void onEnable() {
        migrar("config.yml", CONFIG_VERSION);
        saveDefaultConfig();
        reloadConfig();

        if (!cargarCatalogo()) {
            // Arrancar con precios malos es peor que no arrancar: seria dinero regalado.
            throw new IllegalStateException("precios.yml invalido; el modulo no arranca");
        }

        topes = new Topes(new File(getDataFolder(), "topes.yml"));
        topes.configurar(getConfig().getConfigurationSection("topes"));
        topes.cargar();
        mercado = new Mercado(new File(getDataFolder(), "mercado.yml"));
        mercado.configurar(getConfig().getConfigurationSection("mercado"));
        mercado.cargar();
        registro = new Registro(new File(getDataFolder(), "registro"), getLogger());
        compras = new Compras(new File(getDataFolder(), "compras.yml"));
        compras.cargar();

        migrar("secciones.yml", SECCIONES_VERSION);
        migrar("mensajes.yml", MENSAJES_VERSION);
        migrar("nombres.yml", NOMBRES_VERSION);
        Nombres.cargar(new File(getDataFolder(), "nombres.yml"));
        mensajes.cargar(new File(getDataFolder(), "mensajes.yml"));
        mensajes.registro(getLogger());
        secciones.cargar(new File(getDataFolder(), "secciones.yml"));
        rotacion = new Rotacion(new File(getDataFolder(), "rotacion.yml"));
        rotacion.configurar(getConfig().getConfigurationSection("rotacion"));
        rotacion.cargar(catalogo);
        menu = new MenuTienda(this, catalogo, topes, secciones);
        core.getServer().getPluginManager().registerEvents(menu, this);

        /* La pantalla de cantidad y el buscador comparten la entrada por chat:
         * los dos necesitan una linea del jugador y no hay dos preguntas a la
         * vez para la misma persona. */
        chat = core.chat();
        pantalla = new PantallaCantidad(this, secciones, chat);
        core.getServer().getPluginManager().registerEvents(pantalla, this);
        menu.enlazar(pantalla, chat);
        aplicarAjustes();

        ComandoTienda comando = new ComandoTienda(this, catalogo, topes);
        /* Como el resto de modulos: el plugin que ve Bukkit es EDM, asi que hay
         * que registrarse como ejecutor a mano o /etienda solo imprime su uso. */
        for (String nombre : new String[]{"etienda", "shop", "sellall"}) {
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
            motor.rotacion(rotacion);
            motor.compras(compras);
            motor.mensajes(mensajes);
            getLogger().info("Economia enganchada: " + economia.getName());
        });

        long cada = Math.max(20L * 30, getConfig().getLong("guardado-segundos", 120) * 20L);
        tareaGuardado = core.getServer().getScheduler().runTaskTimerAsynchronously(core, () -> {
            /* Cada guardado va en su propio try: si el disco falla al escribir
             * los topes, los otros tres tienen que guardarse igual. Antes la
             * primera excepcion se llevaba por delante el resto de la tanda. */
            guardar("topes", () -> { topes.limpiar(catalogo); topes.guardar(); });
            guardar("mercado", mercado::guardar);
            guardar("rotacion", rotacion::guardar);
            guardar("compras", compras::guardar);
        }, cada, cada);

        /* La rotacion se comprueba en el hilo principal y no dentro del guardado
         * asincrono: al rotar se vacia y se rellena el sorteo entero y hace falta
         * recorrer el catalogo, y el menu esta leyendo las dos cosas. Se mira
         * cada minuto, no con una tarea a medianoche: si el servidor estaba
         * apagado a esa hora, igual rota al arrancar. */
        tareaRotacion = core.getServer().getScheduler().runTaskTimer(core, () -> {
            if (rotacion.alDia(catalogo)) mensajes.anunciarRotacion(rotacion, catalogo);
        }, 20L * 60, 20L * 60);

        getLogger().info("Tienda activa | " + Nombres.cuantos() + " nombres en espanol | "
                + catalogo.total() + " articulos en "
                + catalogo.categorias().size() + " categorias ("
                + catalogo.variantes() + " variantes) | " + secciones.cuantas() + " secciones"
                + " | click: " + menu.modo() + " | buscador: " + (menu.buscador() ? "si" : "no")
                + " | discord: " + (mensajes.hayWebhook() ? "si" : "no"));
    }

    @Override
    public void onDisable() {
        if (tareaGuardado != null) tareaGuardado.cancel();
        if (tareaRotacion != null) tareaRotacion.cancel();
        if (topes != null) topes.guardar();
        if (mercado != null) mercado.guardar();
        if (rotacion != null) rotacion.guardar();
        if (compras != null) compras.guardar();
        if (registro != null) registro.cerrar();
    }

    /** Un guardado que no puede llevarse por delante a los demas. */
    private void guardar(String que, Runnable accion) {
        try {
            accion.run();
        } catch (Throwable t) {
            getLogger().warning("No se pudo guardar " + que + " de la tienda: " + t.getMessage());
        }
    }

    private boolean engancharVault() {
        if (core.getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp =
                core.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economia = rsp.getProvider();
        return economia != null;
    }

    /** Version del secciones.yml que espera este codigo. Subirla cuando el
     *  formato cambie: el fichero viejo se guarda al lado y se pone el nuevo. */
    private static final int SECCIONES_VERSION = 4;
    private static final int CONFIG_VERSION = 3;
    private static final int MENSAJES_VERSION = 4;
    private static final int NOMBRES_VERSION = 1;

    /**
     * Pone al dia secciones.yml sin que nadie tenga que borrar nada a mano.
     *
     * saveResource(false) NO sobrescribe, asi que un fichero de una version
     * anterior se quedaba para siempre y los arreglos del menu no llegaban
     * nunca. Aqui se detecta por su 'version', se aparta el viejo con su fecha
     * y se escribe el nuevo.
     */
    private void migrar(String nombre, int esperada) {
        File destino = new File(getDataFolder(), nombre);
        if (!destino.exists()) { saveResource(nombre, false); return; }

        int suya = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(destino).getInt("version", 1);
        if (suya >= esperada) return;

        String base = nombre.replace(".yml", "");
        File aparte = new File(getDataFolder(),
                base + "-v" + suya + "-" + java.time.LocalDate.now() + ".yml");
        if (destino.renameTo(aparte)) {
            saveResource(nombre, false);
            getLogger().warning(nombre + " era de la version " + suya + " y se puso al dia. "
                    + "El tuyo quedo en " + aparte.getName() + " por si le habias cambiado algo.");
        } else {
            getLogger().severe("No pude apartar el " + nombre + " viejo; puede quedar desactualizado.");
        }
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

    /** Los ajustes que se pueden cambiar en caliente, en un solo sitio para que
     *  arrancar y recargar no acaben aplicando cosas distintas. */
    private void aplicarAjustes() {
        if (pantalla != null) pantalla.configurar(getConfig().getConfigurationSection("cantidad"));
        if (menu != null) {
            menu.configurar(getConfig().getConfigurationSection("cantidad"),
                    getConfig().getConfigurationSection("buscador"));
        }
    }

    @Override
    public String recargar() {
        secciones.cargar(new File(getDataFolder(), "secciones.yml"));
        mensajes.cargar(new File(getDataFolder(), "mensajes.yml"));
        mensajes.registro(getLogger());
        Nombres.cargar(new File(getDataFolder(), "nombres.yml"));
        reloadConfig();
        aplicarAjustes();
        if (rotacion != null) rotacion.configurar(getConfig().getConfigurationSection("rotacion"));
        if (mercado != null) mercado.configurar(getConfig().getConfigurationSection("mercado"));
        if (topes != null) topes.configurar(getConfig().getConfigurationSection("topes"));
        if (!cargarCatalogo()) return "el precios.yml tiene errores; se mantiene el anterior";
        return catalogo.total() + " articulos en " + catalogo.categorias().size() + " categorias";
    }

    public MenuTienda menu() { return menu; }

    public Mercado mercado() { return mercado; }

    public Rotacion rotacion() { return rotacion; }

    public Secciones secciones() { return secciones; }

    public Mensajes mensajes() { return mensajes; }

    public PantallaCantidad pantalla() { return pantalla; }

    /** null hasta que engancha Vault en el primer tick. */
    public Motor motor() { return motor; }
}
