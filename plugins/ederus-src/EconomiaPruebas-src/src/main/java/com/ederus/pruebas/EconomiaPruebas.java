package com.ederus.pruebas;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Economia de mentira SOLO para el servidor de pruebas. Nunca va a Ederus.
 *
 * Lo que ningun plugin de economia de verdad deja hacer: con /banco romper on
 * TODA operacion falla. Es la unica forma de comprobar que la tienda devuelve
 * los items cuando el pago se cae — el fallo que regalaria dinero.
 */
public final class EconomiaPruebas extends JavaPlugin {

    private final Banco banco = new Banco();

    @Override
    public void onEnable() {
        getServer().getServicesManager().register(Economy.class, banco, this, ServicePriority.Highest);
        getLogger().warning("Economia DE PRUEBAS registrada. Esto no debe correr en produccion.");
    }

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        if (args.length == 0) {
            quien.sendMessage("/banco dar <jugador> <cantidad> | /banco ver <jugador> | /banco romper <on|off>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "dar" -> {
                if (args.length < 3) { quien.sendMessage("/banco dar <jugador> <cantidad>"); return true; }
                try { banco.dar(args[1], Double.parseDouble(args[2])); }
                catch (NumberFormatException e) { quien.sendMessage("cantidad invalida"); return true; }
                quien.sendMessage("saldo de " + args[1] + ": " + banco.ver(args[1]));
            }
            case "ver" -> {
                if (args.length < 2) { quien.sendMessage("/banco ver <jugador>"); return true; }
                quien.sendMessage("saldo de " + args[1] + ": " + banco.ver(args[1]));
            }
            case "romper" -> {
                banco.romper(args.length > 1 && args[1].equalsIgnoreCase("on"));
                quien.sendMessage("banco roto: " + banco.roto());
            }
            default -> quien.sendMessage("no");
        }
        return true;
    }
}
