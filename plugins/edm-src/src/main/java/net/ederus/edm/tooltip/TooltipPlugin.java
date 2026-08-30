package net.ederus.edm.tooltip;

import com.comphenix.protocol.ProtocolLibrary;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;

/**
 * Como se ven los encantamientos.
 *
 * El problema que resuelve: Minecraft solo sabe escribir en romano hasta el
 * nivel 10. Del 11 en adelante el cliente escupe la clave de traduccion tal
 * cual ("Unbreaking enchantment.level.11") y no hay config de MMOItems, de
 * AdvancedEnchantments ni del servidor que lo arregle, porque el texto lo monta
 * el cliente a partir del componente de encantamientos.
 *
 * La salida es dejar de mandarle ese trabajo al cliente: se oculta su bloque y
 * el servidor escribe el suyo, ordenado y con el numeral puesto por nosotros.
 *
 * Y se hace SOBRE EL PAQUETE, no sobre el item. Nada se guarda:
 *
 *  - Ningun item del servidor cambia, asi que no hay nada que migrar ni nada
 *    que revertir. Apagar el modulo devuelve el juego a como estaba.
 *  - Las tiendas, el /ah y los cofres siguen comparando los items de siempre.
 *    Si escribieramos el lore en el item de verdad, un casco encantado dejaria
 *    de parecerse al que la tienda tiene fichado.
 *  - MMOItems puede regenerar el lore cuando quiera: no hay nada nuestro dentro
 *    que se pueda llevar por delante.
 *
 * Depende de ProtocolLib, que ya estaba en el servidor. Si no esta, el modulo
 * ni arranca y el resto del nucleo no se entera.
 */
public final class TooltipPlugin extends Module {

    private Reescritor reescritor;
    private EscuchaPaquetes escucha;

    public TooltipPlugin(EDMPlugin core) {
        super(core, "tooltip", "EderusTooltip");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        this.reescritor = new Reescritor(Ajustes.de(getConfig()));
        /* El listener va a nombre del nucleo, no del modulo: un Module no es un
         * plugin registrado en Bukkit y ProtocolLib pide uno de verdad para
         * poder soltar los enganches cuando el plugin se apague. */
        this.escucha = new EscuchaPaquetes(this.core, this.reescritor);
        ProtocolLibrary.getProtocolManager().addPacketListener(this.escucha);

        getLogger().info("Modulo tooltip listo: encantamientos en romano hasta el nivel "
                + getConfig().getInt("romanos-hasta", 20) + ".");
    }

    @Override
    public void onDisable() {
        if (this.escucha != null) {
            try {
                ProtocolLibrary.getProtocolManager().removePacketListener(this.escucha);
            } catch (Throwable t) {
                getLogger().warning("No se pudo soltar el enganche de tooltip: " + t);
            }
            this.escucha = null;
        }
    }

    /**
     * /edm tooltip limpiar [jugador|todos]
     *
     * Quita el bloque que la 1.17.0 grabo dentro de los items de verdad. Se
     * deja puesto aunque aquella averia ya este arreglada: si algun dia se
     * vuelve a tocar el camino del paquete, esto es la marcha atras.
     */
    @Override
    public boolean subcomando(org.bukkit.command.CommandSender quien, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("limpiar")) {
            return false;
        }
        java.util.List<org.bukkit.entity.Player> lista = new java.util.ArrayList<>();
        if (args.length >= 2 && args[1].equalsIgnoreCase("todos")) {
            lista.addAll(getServer().getOnlinePlayers());
        } else if (args.length >= 2) {
            org.bukkit.entity.Player p = getServer().getPlayerExact(args[1]);
            if (p == null) {
                quien.sendMessage("No hay ningun jugador conectado que se llame '" + args[1] + "'.");
                return true;
            }
            lista.add(p);
        } else if (quien instanceof org.bukkit.entity.Player p) {
            lista.add(p);
        } else {
            quien.sendMessage("Desde la consola hace falta decir a quien: /edm tooltip limpiar <jugador|todos>");
            return true;
        }

        Ajustes a = Ajustes.de(getConfig());
        int total = 0;
        for (org.bukkit.entity.Player p : lista) {
            int n = Limpiador.limpiar(p, a);
            total += n;
            quien.sendMessage("  " + p.getName() + ": " + n + (n == 1 ? " item limpiado." : " items limpiados."));
        }
        quien.sendMessage("Total: " + total + " (inventario y cofre de ender).");
        return true;
    }

    @Override
    public String recargar() {
        reloadConfig();
        this.reescritor.ajustes(Ajustes.de(getConfig()));
        return "Tooltip recargado. Romanos hasta el " + getConfig().getInt("romanos-hasta", 20)
                + "; el cambio se ve al reabrir el inventario.";
    }
}
