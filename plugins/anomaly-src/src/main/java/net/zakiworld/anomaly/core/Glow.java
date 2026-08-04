package net.zakiworld.anomaly.core;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * El brillo de color de cada anomalia.
 *
 * Minecraft no deja pintar el contorno de una entidad directamente: el color sale del
 * equipo de marcador al que pertenece. Asi que hay un equipo por color, se mete a la
 * entidad dentro y se la saca al terminar.
 *
 * El brillo es ademas la forma de encontrar al jefe: se ve a traves de las paredes y
 * desde mucho mas lejos que cualquier particula.
 */
public final class Glow {

    private static final String PREFIX = "anomaly_";

    private Glow() {
    }

    public static void apply(Entity entity, NamedTextColor color) {
        if (entity == null) return;
        try {
            entity.setGlowing(true);
            Team team = teamFor(color);
            if (team != null) team.addEntry(entryOf(entity));
        } catch (Throwable ignored) {
            // sin marcador el jefe brilla igual, solo que en blanco
        }
    }

    public static void clear(Entity entity) {
        if (entity == null) return;
        try {
            String entry = entryOf(entity);
            Scoreboard board = board();
            if (board == null) return;
            for (Team team : board.getTeams()) {
                if (team.getName().startsWith(PREFIX)) team.removeEntry(entry);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Las entidades que no son jugadores entran en un equipo por su UUID en texto. */
    private static String entryOf(Entity entity) {
        return entity.getUniqueId().toString();
    }

    private static Team teamFor(NamedTextColor color) {
        Scoreboard board = board();
        if (board == null) return null;
        String name = PREFIX + color.toString();
        Team team = board.getTeam(name);
        if (team == null) {
            team = board.registerNewTeam(name);
            team.color(color);
            // Sin esto, el nombre sobre la cabeza del jefe se ve a traves de las paredes
            // desde media pantalla y ensucia la escena.
            team.setCanSeeFriendlyInvisibles(false);
        }
        return team;
    }

    private static Scoreboard board() {
        try {
            return Bukkit.getScoreboardManager().getMainScoreboard();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
