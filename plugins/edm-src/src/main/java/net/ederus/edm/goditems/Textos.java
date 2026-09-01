package net.ederus.edm.goditems;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Los `%loquesea%` de las lineas de un GodItem.
 *
 * PlaceholderAPI se llama por reflexion a proposito: meterlo en el pom seria un
 * jar mas que tiene que estar presente para compilar, y aqui solo se usa para
 * pasarle una cadena. Si PAPI no esta, los suyos se quedan sin sustituir y no
 * pasa nada mas.
 */
public final class Textos {

    private Textos() { }

    private static final Pattern VAR = Pattern.compile("%(var|varj)_([A-Za-z0-9_.-]+)%");

    private static Method papi;
    private static boolean papiBuscado;

    public static String aplicar(Ctx ctx, String texto) {
        if (texto == null || texto.isEmpty()) return "";
        String s = texto;

        Player j = ctx.jugador();
        if (j != null) {
            s = s.replace("%jugador%", j.getName());
            s = s.replace("%vida%", redondo(j.getHealth()));
            s = s.replace("%vida_max%", redondo(maxVida(j)));
            s = s.replace("%comida%", String.valueOf(j.getFoodLevel()));
            s = s.replace("%mundo%", j.getWorld().getName());
            s = s.replace("%x%", String.valueOf(j.getLocation().getBlockX()));
            s = s.replace("%y%", String.valueOf(j.getLocation().getBlockY()));
            s = s.replace("%z%", String.valueOf(j.getLocation().getBlockZ()));
        }
        Entity o = ctx.objetivo();
        if (o != null) {
            s = s.replace("%objetivo%", o.getName());
            if (o instanceof LivingEntity le) {
                s = s.replace("%objetivo_vida%", redondo(le.getHealth()));
            }
        }
        if (ctx.definicion() != null) {
            s = s.replace("%item%", ctx.definicion().id());
            s = s.replace("%item_nombre%", ctx.definicion().nombreVisible());
            s = s.replace("%usos%", String.valueOf(ctx.modulo().usos().restantes(ctx)));
        }
        if (ctx.activador() != null) {
            s = s.replace("%activador%", ctx.activador().name());
        }

        s = variables(ctx, s);

        /* Lo del jugador ya esta puesto; PAPI va al final para que pueda leer
         * incluso lo que hemos escrito nosotros. */
        s = placeholderApi(j, s);
        return s;
    }

    private static String variables(Ctx ctx, String s) {
        Matcher m = VAR.matcher(s);
        if (!m.find()) return s;
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            boolean deJugador = m.group(1).equalsIgnoreCase("varj");
            String nombre = m.group(2);
            String valor = deJugador
                    ? ctx.modulo().variables().deJugador(ctx.jugador(), nombre)
                    : ctx.modulo().variables().deItem(ctx, nombre);
            m.appendReplacement(sb, Matcher.quoteReplacement(valor == null ? "0" : valor));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** El valor de un `%...%` suelto, para comparar en las condiciones. */
    public static String valor(Ctx ctx, String expresion) {
        return aplicar(ctx, expresion);
    }

    public static String placeholderApi(Player j, String s) {
        if (j == null || s.indexOf('%') < 0) return s;
        if (!papiBuscado) {
            papiBuscado = true;
            try {
                if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                    papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                            .getMethod("setPlaceholders", Player.class, String.class);
                }
            } catch (Throwable t) {
                papi = null;
            }
        }
        if (papi == null) return s;
        try {
            Object r = papi.invoke(null, j, s);
            return r instanceof String out ? out : s;
        } catch (Throwable t) {
            return s;
        }
    }

    public static double maxVida(LivingEntity e) {
        return net.ederus.edm.anomaly.core.Compat.getAttribute(e, "max_health", 20.0);
    }

    private static String redondo(double d) {
        double r = Math.round(d * 10.0) / 10.0;
        if (r == Math.rint(r)) return String.valueOf((long) r);
        return String.format(Locale.US, "%.1f", r);
    }
}
