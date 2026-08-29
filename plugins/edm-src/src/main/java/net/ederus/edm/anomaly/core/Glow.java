package net.ederus.edm.anomaly.core;

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
        // color null = anomalia sin brillo, a proposito. Ni se enciende ni entra a un equipo.
        if (entity == null || color == null) return;
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

    /**
     * Vacia los equipos de brillo. Se llama AL ARRANCAR.
     *
     * Las entradas viven en el marcador principal, que Minecraft guarda en
     * scoreboard.dat y conserva entre reinicios. Si el servidor se cae en mitad
     * de una anomalia, cleanup() no llega a correr y los UUID de ese jefe y sus
     * esbirros se quedan ahi para siempre, sumando en cada caida.
     *
     * Al arrancar no hay ninguna anomalia viva, asi que TODA entrada de estos
     * equipos es basura por definicion y se puede tirar entera.
     *
     * @return cuantas entradas se tiraron
     */
    public static int purgeStale() {
        int fuera = 0;
        try {
            Scoreboard board = board();
            if (board == null) return 0;
            for (Team team : board.getTeams()) {
                if (!team.getName().startsWith(PREFIX)) continue;
                for (String entry : new java.util.ArrayList<>(team.getEntries())) {
                    team.removeEntry(entry);
                    fuera++;
                }
            }
        } catch (Throwable ignored) {
        }
        return fuera;
    }

    /**
     * La clave del equipo. Las entidades entran por su UUID en texto, pero los
     * JUGADORES entran por su NOMBRE: con el UUID el equipo se traga la entrada sin
     * quejarse y el jugador nunca llega a brillar. Hizo falta al marcar jugadores
     * (petrificados y sentenciados) con el color del gemelo que los marco.
     */
    private static String entryOf(Entity entity) {
        if (entity instanceof org.bukkit.entity.Player p) return p.getName();
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
