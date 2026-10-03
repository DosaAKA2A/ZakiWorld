package net.ederus.edm.tienda;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * El minion Vendedor de AxMinions vende con los precios de esta tienda (EDM 1.76.0).
 *
 * AxMinions deja poner un proveedor de precios propio: con `hooks.prices:
 * custom` en su config.yml no engancha ninguno y usa el que se le registre por
 * su API (Integrations.register). Su PricesIntegration tiene un solo metodo,
 * getPrice(ItemStack), que devuelve lo que vale la PILA entera; el minion lo
 * multiplica por el porcentaje de su nivel y lo guarda en su caja, que el dueño
 * cobra despues. Se habla con el por reflexion (un Proxy de su interfaz) para
 * que EDM compile y arranque igual sin AxMinions.
 *
 * Decision de Dosa (2026-10-02): el minion vende al precio NORMAL de la tienda
 * (el `venta` de precios.yml), sin fluctuacion y sin tocar el mercado de nadie.
 * Ya cobra menos por el porcentaje de su nivel. Las Ofertas y Demandas del dia
 * tampoco le cuentan. Solo lo que la tienda compra (venta > 0) y "a pelo".
 */
final class PrecioMinions {

    private static final String API = "com.artillexstudios.axminions.api.AxMinionsAPI";
    private static final String INTEGRATIONS = "com.artillexstudios.axminions.api.integrations.Integrations";
    private static final String INTEGRATION = "com.artillexstudios.axminions.api.integrations.Integration";
    private static final String PRICES = "com.artillexstudios.axminions.api.integrations.types.PricesIntegration";

    private final TiendaPlugin modulo;
    private final Logger log;
    private volatile boolean activo = true;
    private boolean enganchado;

    PrecioMinions(TiendaPlugin modulo, Logger log) {
        this.modulo = modulo;
        this.log = log;
    }

    /** config.yml > minions.activo. Sin la seccion: encendido. */
    void configurar(ConfigurationSection sec) {
        this.activo = sec == null || sec.getBoolean("activo", true);
    }

    boolean enganchado() {
        return enganchado;
    }

    /**
     * Se registra en AxMinions. Se puede llamar otra vez (recarga): el registro
     * nuevo sustituye al anterior. Devuelve por que no, o null si quedo hecho.
     */
    String enganchar() {
        if (!activo) return "apagado en config.yml (minions.activo)";
        Plugin ax = Bukkit.getPluginManager().getPlugin("AxMinions");
        if (ax == null || !ax.isEnabled()) return "AxMinions no esta";
        try {
            ClassLoader cl = ax.getClass().getClassLoader();
            Class<?> api = Class.forName(API, true, cl);
            Class<?> integraciones = Class.forName(INTEGRATIONS, true, cl);
            Class<?> integracion = Class.forName(INTEGRATION, true, cl);
            Class<?> precios = Class.forName(PRICES, true, cl);
            Object instancia = api.getMethod("getINSTANCE").invoke(null);
            Object registro = api.getMethod("getIntegrations").invoke(instancia);
            InvocationHandler h = (proxy, metodo, args) -> switch (metodo.getName()) {
                case "getPrice" -> precio(args != null && args.length > 0 && args[0] instanceof ItemStack s ? s : null);
                case "register" -> null;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> args != null && args.length == 1 && proxy == args[0];
                case "toString" -> "EDM tienda (precios de /shop para los minions)";
                default -> null;
            };
            Object proxy = Proxy.newProxyInstance(cl, new Class<?>[]{precios}, h);
            integraciones.getMethod("register", integracion).invoke(registro, proxy);
            enganchado = true;
            return null;
        } catch (Throwable t) {
            enganchado = false;
            Throwable c = t.getCause() != null ? t.getCause() : t;
            return "la API de AxMinions no respondio (" + c + ")";
        }
    }

    /** Lo que vale una pila para un minion: precio normal de venta x cantidad. 0 = no se vende (se queda en el cofre). */
    double precio(ItemStack pila) {
        try {
            if (!activo || pila == null || pila.getType().isAir() || pila.getAmount() <= 0) return 0;
            Catalogo catalogo = modulo.catalogo();
            if (catalogo == null) return 0;
            Catalogo.Articulo art = catalogo.de(pila.getType());
            if (art == null || !art.seVende() || !Motor.esLimpio(pila, pila.getType())) return 0;
            int n = pila.getAmount();
            double total = art.venta() * n;
            if (!(total > 0) || Double.isInfinite(total)) return 0;
            modulo.anotarMinion(n, art.clave(), total / n, total);
            return total;
        } catch (Throwable t) {
            // Un precio roto nunca tumba el minion: esa pila se queda en el cofre.
            log.warning("[Tienda] precio para un minion fallido: " + t);
            return 0;
        }
    }
}
