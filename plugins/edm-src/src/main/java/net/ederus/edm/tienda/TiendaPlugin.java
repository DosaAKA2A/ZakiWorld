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
    private EditorPrecio editor;
    private EditorNuevo nuevo;
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
        /* El editor de precios de /shop edit: la misma entrada por chat. */
        editor = new EditorPrecio(this, catalogo, secciones, chat);
        core.getServer().getPluginManager().registerEvents(editor, this);
        /* Y el de articulos nuevos, que cuelga del mismo modo editor. */
        nuevo = new EditorNuevo(this, catalogo, secciones, chat);
        core.getServer().getPluginManager().registerEvents(nuevo, this);
        menu.enlazar(pantalla, chat, editor, nuevo);
        aplicarAjustes();

        ComandoTienda comando = new ComandoTienda(this, catalogo, topes);
        /* Como el resto de modulos: el plugin que ve Bukkit es EDM, asi que hay
         * que registrarse como ejecutor a mano o /etienda solo imprime su uso. */
        for (String nombre : new String[]{"shop", "sellall"}) {
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
            motor.plugin(core);
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

    /**
     * Cambia el precio de UN articulo en precios.yml y recarga el catalogo.
     * Devuelve null si quedo guardado, o el motivo por el que no.
     *
     * Se edita el texto del fichero, no se vuelca el YAML entero: asi los
     * comentarios y el orden se quedan como estan y el diff es de dos lineas.
     * Si el catalogo nuevo no carga (no deberia: las reglas se miran antes),
     * se deja el fichero como estaba y la tienda sigue con el catalogo bueno.
     */
    public String cambiarPrecio(Catalogo.Articulo art, double compra, double venta) {
        if (compra < 0 || venta < 0) return "El precio no puede ser negativo.";
        if (art.esVariante() && venta > 0) return "Un spawner no se puede recomprar.";
        if (compra > 0 && venta > 0 && venta >= compra) {
            return "La venta (" + net.ederus.edm.comun.Estilo.dinero(venta) + ") tiene que quedar por debajo de la compra ("
                    + net.ederus.edm.comun.Estilo.dinero(compra) + "): si no, comprar y vender da dinero infinito.";
        }
        File fichero = new File(getDataFolder(), "precios.yml");
        String antes;
        try {
            antes = java.nio.file.Files.readString(fichero.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "No pude leer precios.yml: " + e.getMessage();
        }
        String despues = reescribirPrecio(antes, art.categoria(), art.clave(), compra, venta);
        if (despues == null) {
            return "No encontré " + art.clave() + " dentro de '" + art.categoria() + "' en precios.yml.";
        }
        try {
            java.nio.file.Files.writeString(fichero.toPath(), despues, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "No pude escribir precios.yml: " + e.getMessage();
        }
        if (!cargarCatalogo()) {
            try {
                java.nio.file.Files.writeString(fichero.toPath(), antes, java.nio.charset.StandardCharsets.UTF_8);
                cargarCatalogo();
            } catch (java.io.IOException ignored) {
                // Ya se avisa abajo; peor que esto no puede ir.
            }
            return "El catálogo no cargó con ese precio y se dejó como estaba. Mira la consola.";
        }
        getLogger().info("Precio cambiado: " + art.clave() + " (" + art.categoria() + ") compra "
                + EditorPrecio.numero(compra) + " venta " + EditorPrecio.numero(venta));
        return null;
    }

    /**
     * Localiza el bloque del articulo (la categoria y despues la clave) y
     * cambia sus lineas compra: y venta:. Devuelve null si no lo encuentra.
     * Conserva el salto de linea del fichero (CRLF o LF).
     */
    static String reescribirPrecio(String texto, String categoria, String clave, double compra, double venta) {
        String salto = texto.contains("\r\n") ? "\r\n" : "\n";
        String[] lineas = texto.split("\r?\n", -1);
        java.util.regex.Pattern cat = java.util.regex.Pattern.compile("^  " + java.util.regex.Pattern.quote(categoria) + ":\\s*$");
        java.util.regex.Pattern otraCat = java.util.regex.Pattern.compile("^  [A-Za-z_]+:\\s*$");
        java.util.regex.Pattern item = java.util.regex.Pattern.compile("^      '?" + java.util.regex.Pattern.quote(clave) + "'?:\\s*$");
        java.util.regex.Pattern otroItem = java.util.regex.Pattern.compile("^      \\S");
        int i = 0;
        while (i < lineas.length && !cat.matcher(lineas[i]).matches()) i++;
        if (i >= lineas.length) return null;
        i++;
        while (i < lineas.length && !otraCat.matcher(lineas[i]).matches() && !item.matcher(lineas[i]).matches()) i++;
        if (i >= lineas.length || !item.matcher(lineas[i]).matches()) return null;
        i++;
        boolean vistaCompra = false, vistaVenta = false;
        for (; i < lineas.length; i++) {
            String l = lineas[i];
            if (otroItem.matcher(l).find() || otraCat.matcher(l).matches()) break;
            if (l.matches("^        compra:.*")) { lineas[i] = "        compra: " + EditorPrecio.numero(compra); vistaCompra = true; }
            else if (l.matches("^        venta:.*")) { lineas[i] = "        venta: " + EditorPrecio.numero(venta); vistaVenta = true; }
        }
        if (!vistaCompra || !vistaVenta) return null;
        return String.join(salto, lineas);
    }

    /**
     * Mete en precios.yml un articulo que no estaba y recarga el catalogo.
     * Devuelve null si quedo guardado, o el motivo por el que no.
     *
     * La categoria la elige quien edita (EditorNuevo), no se adivina por el
     * material: nadie sabe mejor que el si una cubeta va en Herramientas o en
     * Varios. Las comprobaciones son las MISMAS que hace el catalogo al cargar,
     * pero hechas antes de escribir, para poder decir por que no se puede en
     * vez de dejar el fichero roto y que el modulo no recargue.
     */
    public String anadirArticulo(String categoria, org.bukkit.Material material,
                                 org.bukkit.entity.EntityType variante,
                                 double compra, double venta, int tope) {
        if (material == null) return "Elige primero el objeto.";
        if (material.isAir() || !material.isItem()) {
            return material.name() + " no es un objeto que se pueda tener en el inventario.";
        }
        if (variante != null && material != org.bukkit.Material.SPAWNER) {
            return "Solo los spawners llevan variante.";
        }
        if (categoria == null || !catalogo.categorias().containsKey(categoria)) {
            return "La categoría '" + categoria + "' no está en precios.yml.";
        }
        String clave = material.name() + (variante != null ? ":" + variante.name() : "");
        Catalogo.Articulo ya = catalogo.de(clave);
        if (ya != null) {
            return Motor.nombre(ya) + " ya está en la tienda, dentro de '" + ya.categoria() + "'.";
        }
        if (compra < 0 || venta < 0) return "El precio no puede ser negativo.";
        if (compra <= 0 && venta <= 0) {
            return "Pon al menos un precio: con los dos a 0 el artículo no hace nada.";
        }
        if (variante != null && venta > 0) return "Un spawner no se puede recomprar.";
        if (compra > 0 && venta > 0 && venta >= compra) {
            return "La venta (" + net.ederus.edm.comun.Estilo.dinero(venta) + ") tiene que quedar por debajo de la compra ("
                    + net.ederus.edm.comun.Estilo.dinero(compra) + "): si no, comprar y vender da dinero infinito.";
        }
        if (tope < 0) tope = 0;

        File fichero = new File(getDataFolder(), "precios.yml");
        String antes;
        try {
            antes = java.nio.file.Files.readString(fichero.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "No pude leer precios.yml: " + e.getMessage();
        }
        String despues = insertarArticulo(antes, categoria, clave, compra, venta, tope);
        if (despues == null) {
            return "No encontré la categoría '" + categoria + "' y sus items en precios.yml.";
        }
        try {
            java.nio.file.Files.writeString(fichero.toPath(), despues, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "No pude escribir precios.yml: " + e.getMessage();
        }
        if (!cargarCatalogo()) {
            try {
                java.nio.file.Files.writeString(fichero.toPath(), antes, java.nio.charset.StandardCharsets.UTF_8);
                cargarCatalogo();
            } catch (java.io.IOException ignored) {
                // Ya se avisa abajo; peor que esto no puede ir.
            }
            return "El catálogo no cargó con ese artículo y se dejó como estaba. Mira la consola.";
        }
        getLogger().info("Articulo nuevo: " + clave + " en " + categoria + " compra "
                + EditorPrecio.numero(compra) + " venta " + EditorPrecio.numero(venta)
                + " tope " + tope);
        return null;
    }

    /**
     * Escribe el bloque del articulo al FINAL de los items de su categoria y
     * devuelve el fichero entero. null si no encuentra donde meterlo.
     *
     * Al final y no al principio porque el menu pinta los articulos en el orden
     * del fichero: lo nuevo va detras de lo que ya habia, como si se hubiera
     * escrito a mano. Se busca la ultima linea que todavia pertenece al bloque
     * (sangrada seis espacios o mas), asi los comentarios y las lineas en
     * blanco que separan categorias se quedan donde estaban.
     */
    static String insertarArticulo(String texto, String categoria, String clave,
                                   double compra, double venta, int tope) {
        String salto = texto.contains("\r\n") ? "\r\n" : "\n";
        java.util.List<String> lineas =
                new java.util.ArrayList<>(java.util.Arrays.asList(texto.split("\r?\n", -1)));
        java.util.regex.Pattern cat = java.util.regex.Pattern.compile("^  " + java.util.regex.Pattern.quote(categoria) + ":\\s*$");
        java.util.regex.Pattern otraCat = java.util.regex.Pattern.compile("^  [A-Za-z_]+:\\s*$");
        java.util.regex.Pattern items = java.util.regex.Pattern.compile("^    items:\\s*$");

        int i = 0;
        while (i < lineas.size() && !cat.matcher(lineas.get(i)).matches()) i++;
        if (i >= lineas.size()) return null;
        i++;
        while (i < lineas.size() && !otraCat.matcher(lineas.get(i)).matches()
                && !items.matcher(lineas.get(i)).matches()) i++;
        if (i >= lineas.size() || !items.matcher(lineas.get(i)).matches()) return null;

        /* El final del bloque: la ultima linea con contenido que sigue dentro.
         * Si la categoria estuviera vacia, esa linea es el propio 'items:'. */
        int ultima = i;
        for (int j = i + 1; j < lineas.size(); j++) {
            String l = lineas.get(j);
            if (l.isBlank()) continue;
            if (!l.startsWith("      ")) break;
            ultima = j;
        }

        /* Una clave con dos puntos dentro (SPAWNER:PIG) lleva comillas o el
         * YAML la lee como un mapa. */
        String escrita = clave.indexOf(':') >= 0 ? "'" + clave + "'" : clave;
        java.util.List<String> bloque = java.util.List.of(
                "      " + escrita + ":",
                "        compra: " + EditorPrecio.numero(compra),
                "        venta: " + EditorPrecio.numero(venta),
                "        tope: " + tope,
                "        ventana: 24h");
        lineas.addAll(ultima + 1, bloque);
        return String.join(salto, lineas);
    }

    public Mercado mercado() { return mercado; }

    public Rotacion rotacion() { return rotacion; }

    public Mensajes mensajes() { return mensajes; }

    /** null hasta que engancha Vault en el primer tick. */
    public Motor motor() { return motor; }
}
