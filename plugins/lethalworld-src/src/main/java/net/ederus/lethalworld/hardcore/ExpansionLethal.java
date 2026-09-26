package net.ederus.lethalworld.hardcore;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.ederus.lethalworld.LethalWorldPlugin;
import org.bukkit.OfflinePlayer;

/**
 * La expansion lethalworld de PlaceholderAPI. Es la UNICA clase que importa PAPI: solo se
 * instancia si el plugin esta (PlaceholdersLethal.activar, dentro de un try), asi que sin
 * PAPI ni se carga. Lo que responde lo decide el registro de PlaceholdersLethal.
 */
final class ExpansionLethal extends PlaceholderExpansion {

    private final LethalWorldPlugin plugin;

    ExpansionLethal(LethalWorldPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "lethalworld";
    }

    @Override
    public String getAuthor() {
        return "Iris Studio";
    }

    @Override
    public String getVersion() {
        return LethalWorldPlugin.VERSION;
    }

    /** Que sobreviva a /papi reload: la registra el plugin, no un jar de expansiones. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return plugin.isEnabled();
    }

    @Override
    public String onRequest(OfflinePlayer jugador, String params) {
        return PlaceholdersLethal.resolver(jugador, params);
    }
}
