package com.ederus.main;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class EderusMain extends JavaPlugin {

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
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("§cEste comando solo puede ejecutarse desde la consola.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            cargar();
            if (avisoTiendas != null) avisoTiendas.iniciar();
            sender.sendMessage("§aEderusMain recargado. Encantamientos: " + nombres.size()
                    + " | rotura " + destruirMin + "-" + destruirMax + "%");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage("§eUso: /" + label + " <jugador>  |  /" + label + " reload");
            return true;
        }
        Player objetivo = Bukkit.getPlayerExact(args[0]);
        if (objetivo == null) {
            sender.sendMessage("§cJugador no conectado: " + args[0]);
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
