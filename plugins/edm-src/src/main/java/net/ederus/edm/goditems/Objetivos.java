package net.ederus.edm.goditems;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

/**
 * A quien apunta una accion.
 *
 *   @yo                         el portador (es el de por defecto)
 *   @golpeado                   con quien va el evento: al que pegas, el que te
 *                               pega, el que matas, el dueño del proyectil...
 *   @mirada                     lo primero que hay en la linea de vision
 *   @cercanos{8}                todo lo vivo a 8 bloques
 *   @cercanos{8,jugadores}      solo jugadores
 *   @cercanos{8,enemigos}       todo lo vivo MENOS el portador y menos jugadores
 *   @todos                      todos los jugadores del mundo
 *
 * "enemigos" no mira banderas de equipo ni de faccion: Ederus no tiene equipos.
 * Es "todo lo vivo que no seas tu y no sea un jugador", que es lo que espera
 * quien escribe un item de daño en area.
 */
public final class Objetivos {

    private Objetivos() { }

    public static final double ALCANCE_MIRADA = 30.0;
    private static final double RADIO_MAXIMO = 64.0;

    public static List<Entity> resolver(Ctx ctx, String selector) {
        List<Entity> out = new ArrayList<>();
        if (selector == null || selector.isBlank()) {
            if (ctx.jugador() != null) out.add(ctx.jugador());
            return out;
        }
        String s = selector.trim();
        if (s.startsWith("@")) s = s.substring(1);

        String nombre = s;
        String dentro = "";
        int llave = s.indexOf('{');
        if (llave >= 0) {
            nombre = s.substring(0, llave);
            int cierre = s.lastIndexOf('}');
            dentro = s.substring(llave + 1, cierre < 0 ? s.length() : cierre);
        }
        nombre = nombre.toLowerCase(Locale.ROOT);

        switch (nombre) {
            case "yo", "portador", "self" -> {
                if (ctx.jugador() != null) out.add(ctx.jugador());
            }
            case "golpeado", "objetivo", "victima" -> {
                if (ctx.objetivo() != null) out.add(ctx.objetivo());
            }
            case "mirada", "vista" -> {
                Entity e = mirada(ctx, alcance(dentro));
                if (e != null) out.add(e);
            }
            case "cercanos", "cerca", "area" -> out.addAll(cercanos(ctx, dentro));
            case "todos" -> {
                if (ctx.jugador() != null) out.addAll(ctx.jugador().getWorld().getPlayers());
            }
            case "ninguno", "nadie" -> { }
            default -> {
                ctx.modulo().getLogger().warning("[GodItems] Objetivo desconocido '@" + nombre
                        + "' en " + ctx.definicion().id() + "; se usa @yo.");
                if (ctx.jugador() != null) out.add(ctx.jugador());
            }
        }
        return out;
    }

    /** El primero de la lista, o null. Lo que usan las acciones de uno solo. */
    public static Entity uno(Ctx ctx, String selector) {
        List<Entity> l = resolver(ctx, selector);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Solo lo que se puede dañar o curar. */
    public static List<LivingEntity> vivos(Ctx ctx, String selector) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : resolver(ctx, selector)) {
            if (e instanceof LivingEntity le && !le.isDead()) out.add(le);
        }
        return out;
    }

    public static List<Player> jugadores(Ctx ctx, String selector) {
        List<Player> out = new ArrayList<>();
        for (Entity e : resolver(ctx, selector)) {
            if (e instanceof Player p) out.add(p);
        }
        return out;
    }

    /** El sitio al que apunta la accion: el del objetivo, o el del contexto. */
    public static Location lugar(Ctx ctx, String selector) {
        if (selector == null || selector.isBlank()) return ctx.lugar();
        Entity e = uno(ctx, selector);
        return e != null ? e.getLocation() : ctx.lugar();
    }

    /* ------------------------------------------------------------- interiores */

    private static double alcance(String dentro) {
        for (String t : dentro.split(",")) {
            String v = t.trim();
            if (v.isEmpty()) continue;
            int ig = v.indexOf('=');
            String num = ig >= 0 ? v.substring(ig + 1) : v;
            double d = Numeros.decimal(num, -1);
            if (d > 0) return Math.min(RADIO_MAXIMO, d);
        }
        return ALCANCE_MIRADA;
    }

    private static Entity mirada(Ctx ctx, double alcance) {
        Player j = ctx.jugador();
        if (j == null) return null;
        RayTraceResult r = j.getWorld().rayTraceEntities(
                j.getEyeLocation(), j.getEyeLocation().getDirection(), alcance, 0.6,
                e -> e instanceof LivingEntity && !e.equals(j) && !e.isDead());
        return r == null ? null : r.getHitEntity();
    }

    private static List<Entity> cercanos(Ctx ctx, String dentro) {
        double radio = 8;
        String filtro = "todo";
        for (String t : dentro.split(",")) {
            String v = t.trim();
            if (v.isEmpty()) continue;
            int ig = v.indexOf('=');
            String clave = ig >= 0 ? v.substring(0, ig).trim().toLowerCase(Locale.ROOT) : null;
            String valor = ig >= 0 ? v.substring(ig + 1).trim() : v;
            double d = Numeros.decimal(valor, Double.NaN);
            if (!Double.isNaN(d) && (clave == null || clave.equals("r") || clave.equals("radio"))) {
                radio = Math.min(RADIO_MAXIMO, Math.max(0.5, d));
            } else {
                filtro = valor.toLowerCase(Locale.ROOT);
            }
        }
        Location centro = ctx.lugar();
        List<Entity> out = new ArrayList<>();
        if (centro == null || centro.getWorld() == null) return out;
        for (Entity e : centro.getWorld().getNearbyEntities(centro, radio, radio, radio)) {
            if (!(e instanceof LivingEntity le) || le.isDead()) continue;
            switch (filtro) {
                case "jugadores" -> { if (!(e instanceof Player)) continue; }
                case "enemigos", "mobs" -> {
                    if (e instanceof Player) continue;
                }
                default -> { }
            }
            if (!filtro.equals("todo") && !filtro.equals("jugadores") && e.equals(ctx.jugador())) continue;
            out.add(e);
        }
        return out;
    }
}
