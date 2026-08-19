package net.ederus.edm.core;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public final class EderusMain extends net.ederus.edm.Module implements TabCompleter {
    public EderusMain(net.ederus.edm.EDMPlugin core) {
        super(core, "core", "EderusMain");
    }


    /** Se compila contra la API 1.20 y se ejecuta en Paper 26: getDescription() esta
     *  en retirada, asi que la version se mantiene aqui. Cambiar tambien en pom.xml
     *  y plugin.yml al subir de version. */
    private static final String VERSION = "1.4.0";

    private final List<String> nombres = new ArrayList<>();
    private final List<Integer> niveles = new ArrayList<>();
    private int destruirMin;
    private int destruirMax;
    private String plantilla;
    private AvisoTiendas avisoTiendas;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cargar();
        getLogger().info("EderusMain activo | " + nombres.size() + " encantamientos cargados | rotura "
                + destruirMin + "-" + destruirMax + "%");
        avisoTiendas = new AvisoTiendas(this);
        avisoTiendas.iniciar();
        /* Antes de EDM esta clase ERA el plugin y Bukkit le enrutaba los comandos sola.
         * Ahora el plugin es EDM, asi que hay que registrarse como ejecutor a mano
         * o /main y /ederuslibro se quedan en su linea de uso. */
        if (getCommand("main") != null) {
            getCommand("main").setExecutor(this);
            getCommand("main").setTabCompleter(this);
        }
        if (getCommand("ederuslibro") != null) {
            getCommand("ederuslibro").setExecutor(this);
        }
    }

    @Override
    public void onDisable() {
        if (avisoTiendas != null) avisoTiendas.detener();
    }

    private void cargar() {
        reloadConfig();
        nombres.clear();
        niveles.clear();
        for (String entrada : getConfig().getStringList("encantamientos")) {
            String[] partes = entrada.split(":");
            if (partes.length != 2) {
                getLogger().warning("Entrada invalida en encantamientos: '" + entrada + "' (formato nombre:nivelmax)");
                continue;
            }
            try {
                int max = Integer.parseInt(partes[1].trim());
                if (max < 1) throw new NumberFormatException();
                nombres.add(partes[0].trim());
                niveles.add(max);
            } catch (NumberFormatException e) {
                getLogger().warning("Nivel maximo invalido en: '" + entrada + "'");
            }
        }
        destruirMin = getConfig().getInt("rotura.minimo", 75);
        destruirMax = getConfig().getInt("rotura.maximo", 90);
        if (destruirMin < 0) destruirMin = 0;
        if (destruirMax > 100) destruirMax = 100;
        if (destruirMax < destruirMin) destruirMax = destruirMin;
        plantilla = getConfig().getString("comando",
                "ae givebook %jugador% %encanto% %nivel% 1 %exito% %rotura%");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("main")) {
            return comandoPrincipal(sender, label, args);
        }
        return comandoLibro(sender, label, args);
    }

    // ------------------------------------------------------------------
    // /main - administracion del nucleo
    // ------------------------------------------------------------------

    private boolean comandoPrincipal(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            ayuda(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                cargar();
                if (avisoTiendas != null) avisoTiendas.iniciar();
                sender.sendMessage("§aNucleo recargado. §7Encantamientos: §f" + nombres.size()
                        + " §7| rotura §f" + destruirMin + "-" + destruirMax + "%");
                sender.sendMessage("§7El aviso de tiendas vuelve a tomar la referencia: "
                        + "una rotacion en los proximos segundos no se anuncia.");
                return true;
            }
            case "aviso" -> {
                if (args.length != 2) {
                    sender.sendMessage("§eUso: /" + label + " aviso <diaria|boveda>");
                    return true;
                }
                String cual = args[1].toLowerCase(Locale.ROOT);
                if (!cual.equals("diaria") && !cual.equals("boveda")) {
                    sender.sendMessage("§cSolo vale §fdiaria §co §fboveda§c.");
                    return true;
                }
                if (avisoTiendas == null || !avisoTiendas.probar(cual)) {
                    sender.sendMessage("§cNo hay mensajes configurados para esa tienda.");
                    return true;
                }
                sender.sendMessage("§aAviso de prueba enviado a los jugadores conectados.");
                return true;
            }
            case "libro" -> {
                if (args.length != 2) {
                    sender.sendMessage("§eUso: /" + label + " libro <jugador>");
                    return true;
                }
                return entregarLibro(sender, args[1]);
            }
            default -> {
                ayuda(sender, label);
                return true;
            }
        }
    }

    private void ayuda(CommandSender sender, String label) {
        sender.sendMessage("§8§m                                        ");
        sender.sendMessage("§6§lNUCLEO DE EDERUS §7v" + VERSION);
        sender.sendMessage("");
        sender.sendMessage("§6/" + label + " reload §8- §fRecarga la configuracion");
        sender.sendMessage("§6/" + label + " aviso <diaria|boveda> §8- §fLanza el aviso de prueba");
        sender.sendMessage("§6/" + label + " libro <jugador> §8- §fEntrega un libro aleatorio");
        sender.sendMessage("");
        sender.sendMessage("§7Encantamientos cargados: §f" + nombres.size()
                + " §7| rotura §f" + destruirMin + "-" + destruirMax + "%");
        sender.sendMessage("§8§m                                        ");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filtrar(List.of("reload", "aviso", "libro"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("aviso")) {
            return filtrar(List.of("diaria", "boveda"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("libro")) {
            List<String> conectados = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) conectados.add(p.getName());
            return filtrar(conectados, args[1]);
        }
        return Collections.emptyList();
    }

    private static List<String> filtrar(List<String> opciones, String prefijo) {
        String p = prefijo.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : opciones) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }

    // ------------------------------------------------------------------
    // /ederuslibro - se conserva porque lo llaman cofres, misiones y rangos
    // ------------------------------------------------------------------

    private boolean comandoLibro(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("§cEste comando solo puede ejecutarse desde la consola.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("§eEse comando se ha movido: usa §f/main reload§e.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage("§eUso: /" + label + " <jugador>");
            return true;
        }
        return entregarLibro(sender, args[0]);
    }

    private boolean entregarLibro(CommandSender sender, String nombre) {
        Player objetivo = Bukkit.getPlayerExact(nombre);
        if (objetivo == null) {
            sender.sendMessage("§cJugador no conectado: " + nombre);
            return true;
        }
        if (nombres.isEmpty()) {
            sender.sendMessage("§cNo hay encantamientos configurados en EderusMain/config.yml");
            return true;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int i = rng.nextInt(nombres.size());
        String encanto = nombres.get(i);
        int nivel = rng.nextInt(1, niveles.get(i) + 1);
        int rotura = rng.nextInt(destruirMin, destruirMax + 1);
        int exito = 100 - rotura;

        String cmd = plantilla
                .replace("%jugador%", objetivo.getName())
                .replace("%encanto%", encanto)
                .replace("%nivel%", String.valueOf(nivel))
                .replace("%exito%", String.valueOf(exito))
                .replace("%rotura%", String.valueOf(rotura));
        boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        if (!ok) {
            getLogger().warning("El comando despachado devolvio false: " + cmd);
        }
        return true;
    }
}
