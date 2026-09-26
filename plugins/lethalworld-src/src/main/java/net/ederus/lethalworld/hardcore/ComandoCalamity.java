// STUB de WP0: lo reescribe WP0 (0b)
package net.ederus.lethalworld.hardcore;

import net.ederus.lethalworld.LethalWorldPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.List;

/**
 * /calamity (alias /cal). Esqueleto de 0a: no hace nada.
 */
public final class ComandoCalamity implements TabExecutor {

    private final LethalWorldPlugin plugin;

    public ComandoCalamity(LethalWorldPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        return List.of();
    }
}
