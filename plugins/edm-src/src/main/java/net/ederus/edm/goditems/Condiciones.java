package net.ederus.edm.goditems;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.ederus.edm.anomaly.core.Compat;

/**
 * El catalogo de condiciones.
 *
 * Se escriben en una linea, con negacion delante y mensaje detras:
 *
 *   - 'AGACHADO'
 *   - '!ARDIENDO | &cNo puedes usarlo ardiendo'
 *   - 'VIDA menor 50% | &cSolo por debajo de media vida'
 *   - 'PLACEHOLDER %player_level% mayor 30 | &cTe falta nivel'
 *
 * Las comparaciones aceptan palabra (`menor`, `mayor`, `igual`, `distinto`) y
 * simbolo (`<`, `>`, `=`, `!=`). Palabra por delante: en un YAML, un `>` al
 * principio de un valor es un bloque de texto y hay que acordarse de entrecomillar.
 */
public final class Condiciones {

    private Condiciones() { }

    private static final Map<String, Condicion> CATALOGO = new LinkedHashMap<>();

    public static java.util.Set<String> nombres() {
        return CATALOGO.keySet();
    }

    /** Traduce una linea del YAML. Devuelve null si la condicion no existe. */
    public static Condicion.Prueba leer(GodItemsPlugin modulo, String linea) {
        if (linea == null || linea.isBlank()) return null;
        String cuerpo = linea.trim();
        String mensaje = null;
        int barra = cuerpo.indexOf('|');
        if (barra >= 0) {
            mensaje = cuerpo.substring(barra + 1).trim();
            cuerpo = cuerpo.substring(0, barra).trim();
        }
        boolean negada = false;
        while (cuerpo.startsWith("!")) {
            negada = !negada;
            cuerpo = cuerpo.substring(1).trim();
        }
        Args args = Args.de(cuerpo);
        Condicion c = CATALOGO.get(args.nombre());
        if (c == null) return null;
        return new Condicion.Prueba(c, args, negada, mensaje, linea.trim());
    }

    static {
        /* -------------------------------------------------------- el cuerpo */

        reg("VIDA", (ctx, a) -> {
            LivingEntity e = vivo(ctx, a);
            if (e == null) return false;
            List<String> p = a.palabras();
            String valor = p.isEmpty() ? "0" : p.get(p.size() - 1);
            double referencia = Numeros.esPorcentaje(valor)
                    ? Textos.maxVida(e) * Numeros.porcentaje(valor, 1)
                    : Numeros.decimal(valor, 0);
            return comparar(e.getHealth(), operador(p), referencia);
        });

        reg("COMIDA", (ctx, a) -> {
            Player j = ctx.jugador();
            if (j == null) return false;
            List<String> p = a.palabras();
            double valor = Numeros.decimal(p.isEmpty() ? "0" : p.get(p.size() - 1), 0);
            return comparar(j.getFoodLevel(), operador(p), valor);
        });

        reg("AGACHADO", (ctx, a) -> ctx.jugador() != null && ctx.jugador().isSneaking());
        reg("CORRIENDO", (ctx, a) -> ctx.jugador() != null && ctx.jugador().isSprinting());
        reg("VOLANDO", (ctx, a) -> ctx.jugador() != null && ctx.jugador().isFlying());
        reg("PLANEANDO", (ctx, a) -> ctx.jugador() != null && ctx.jugador().isGliding());
        reg("EN_EL_AIRE", (ctx, a) -> {
            Player j = ctx.jugador();
            return j != null && !j.isOnGround() && !j.isFlying();
        });
        reg("ARDIENDO", (ctx, a) -> {
            LivingEntity e = vivo(ctx, a);
            return e != null && e.getFireTicks() > 0;
        });
        reg("MONTADO", (ctx, a) -> ctx.jugador() != null && ctx.jugador().getVehicle() != null);

        reg("EFECTO", (ctx, a) -> {
            LivingEntity e = vivo(ctx, a);
            if (e == null) return false;
            String tipo = a.texto().isBlank() ? a.s("tipo", "") : a.texto().trim();
            var t = Compat.effect(tipo.toLowerCase(Locale.ROOT));
            return t != null && e.hasPotionEffect(t);
        });

        reg("GAMEMODE", (ctx, a) -> {
            Player j = ctx.jugador();
            if (j == null) return false;
            try {
                return j.getGameMode() == GameMode.valueOf(a.texto().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return false;
            }
        });

        reg("PERMISO", (ctx, a) ->
                ctx.jugador() != null && ctx.jugador().hasPermission(a.texto().trim()));

        /* --------------------------------------------------------- el sitio */

        reg("MUNDO", (ctx, a) -> {
            Location l = ctx.lugar();
            if (l == null || l.getWorld() == null) return false;
            String quiero = a.texto().trim();
            for (String m : quiero.split("[,\\s]+")) {
                if (m.equalsIgnoreCase(l.getWorld().getName())) return true;
            }
            return false;
        });

        reg("REGION", (ctx, a) ->
                ctx.modulo().regiones().en(ctx.lugar(), a.texto().trim()));

        reg("BIOMA", (ctx, a) -> {
            Location l = ctx.lugar();
            if (l == null) return false;
            String actual;
            try {
                actual = l.getBlock().getBiome().getKey().getKey();
            } catch (Throwable t) {
                return false;
            }
            for (String b : a.texto().trim().split("[,\\s]+")) {
                if (b.equalsIgnoreCase(actual)) return true;
            }
            return false;
        });

        reg("LUZ", (ctx, a) -> {
            Location l = ctx.lugar();
            if (l == null) return false;
            List<String> p = a.palabras();
            double valor = Numeros.decimal(p.isEmpty() ? "0" : p.get(p.size() - 1), 0);
            return comparar(l.getBlock().getLightLevel(), operador(p), valor);
        });

        reg("ALTURA", (ctx, a) -> {
            Location l = ctx.lugar();
            if (l == null) return false;
            List<String> p = a.palabras();
            double valor = Numeros.decimal(p.isEmpty() ? "0" : p.get(p.size() - 1), 0);
            return comparar(l.getY(), operador(p), valor);
        });

        /*
         * HORA acepta `dia` y `noche` ademas de un numero, porque es lo que
         * quiere escribir cualquiera. La franja es la del juego: la noche
         * empieza a 13000 y acaba a 23000.
         */
        reg("HORA", (ctx, a) -> {
            Location l = ctx.lugar();
            if (l == null || l.getWorld() == null) return false;
            long hora = l.getWorld().getTime();
            List<String> p = a.palabras();
            String primera = p.isEmpty() ? "" : p.get(0).toLowerCase(Locale.ROOT);
            if (primera.equals("noche")) return hora >= 13000 && hora < 23000;
            if (primera.equals("dia") || primera.equals("día")) return hora < 13000 || hora >= 23000;
            double valor = Numeros.decimal(p.isEmpty() ? "0" : p.get(p.size() - 1), 0);
            return comparar(hora, operador(p), valor);
        });

        reg("LLUVIA", (ctx, a) -> {
            Location l = ctx.lugar();
            if (l == null || l.getWorld() == null) return false;
            /* hasStorm es "esta lloviendo"; isThundering, tormenta. */
            if (a.texto().trim().equalsIgnoreCase("tormenta")) return l.getWorld().isThundering();
            return l.getWorld().hasStorm();
        });

        /* ------------------------------------------------------ el inventario */

        reg("TIENE_ITEM", (ctx, a) -> {
            Player j = ctx.jugador();
            if (j == null) return false;
            Material m = Material.matchMaterial(a.s("material", a.texto().trim())
                    .toUpperCase(Locale.ROOT));
            if (m == null) return false;
            int quiero = Math.max(1, a.i("cantidad", 1));
            int hay = 0;
            for (ItemStack it : j.getInventory().getContents()) {
                if (it != null && it.getType() == m) hay += it.getAmount();
                if (hay >= quiero) return true;
            }
            return false;
        });

        reg("TIENE_GODITEM", (ctx, a) -> {
            Player j = ctx.jugador();
            if (j == null) return false;
            String id = GodItem.normalizar(a.s("id", a.texto().trim()));
            int quiero = Math.max(1, a.i("cantidad", 1));
            int hay = 0;
            for (ItemStack it : j.getInventory().getContents()) {
                if (it == null) continue;
                if (!id.equals(ctx.modulo().identidad().idDe(it))) continue;
                hay += it.getAmount();
                if (hay >= quiero) return true;
            }
            return false;
        });

        /* ----------------------------------------------------------- valores */

        reg("VARIABLE", (ctx, a) -> {
            List<String> p = a.palabras();
            if (p.isEmpty()) return false;
            String nombre = p.get(0);
            String actual = ctx.modulo().variables().valor(ctx, a.s("ambito", "item"), nombre);
            List<String> resto = p.subList(1, p.size());
            String esperado = resto.isEmpty() ? "" : resto.get(resto.size() - 1);
            return compararTexto(actual, operador(resto), esperado);
        });

        reg("PLACEHOLDER", (ctx, a) -> {
            List<String> p = a.palabras();
            if (p.isEmpty()) return false;
            String actual = Textos.aplicar(ctx, p.get(0));
            List<String> resto = p.subList(1, p.size());
            String esperado = resto.isEmpty() ? "" : Textos.aplicar(ctx, resto.get(resto.size() - 1));
            return compararTexto(actual, operador(resto), esperado);
        });

        reg("PROBABILIDAD", (ctx, a) -> {
            List<String> p = a.palabras();
            double pct = Numeros.decimal(p.isEmpty() ? a.s("valor", "100") : p.get(p.size() - 1), 100);
            return Math.random() * 100.0 < pct;
        });

        /*
         * Cuantas piezas del conjunto lleva puestas. El set se saca del propio
         * item si no se dice otro, que es lo que se quiere el 90 % de las veces:
         * "solo si llevas 3 o mas de MI set".
         */
        reg("PIEZAS_DEL_SET", (ctx, a) -> {
            if (ctx.jugador() == null) return false;
            String set = a.s("set", null);
            if (set == null) set = ctx.modulo().puente().setDe(ctx.item());
            if (set == null) return false;
            List<String> p = a.palabras();
            double valor = Numeros.decimal(p.isEmpty() ? "0" : p.get(p.size() - 1), 0);
            return comparar(ctx.modulo().conjuntos().piezasPuestas(ctx.jugador(), set),
                    operador(p), valor);
        });

        /** El item pertenece a ese conjunto de MMOItems. */
        reg("SET", (ctx, a) -> {
            String set = ctx.modulo().puente().setDe(ctx.item());
            if (set == null) return false;
            String quiero = a.texto().trim();
            if (quiero.isEmpty()) return true;
            for (String s2 : quiero.split("[,\s]+")) {
                if (s2.equalsIgnoreCase(set)) return true;
            }
            return false;
        });

        reg("MANO", (ctx, a) -> {
            if (ctx.mano() == null) return false;
            String quiero = a.texto().trim().toLowerCase(Locale.ROOT);
            boolean principal = ctx.mano() == org.bukkit.inventory.EquipmentSlot.HAND;
            if (quiero.startsWith("secun") || quiero.startsWith("off")) return !principal;
            return principal;
        });
    }

    private static void reg(String nombre, Condicion c) {
        CATALOGO.put(nombre, c);
    }

    /* ---------------------------------------------------------------- ayudas */

    private static LivingEntity vivo(Ctx ctx, Args a) {
        if (a.selector() != null) {
            List<LivingEntity> l = Objetivos.vivos(ctx, a.selector());
            return l.isEmpty() ? null : l.get(0);
        }
        return ctx.jugador();
    }

    /** El operador de la lista de palabras; por omision, "igual". */
    private static String operador(List<String> palabras) {
        for (String p : palabras) {
            String v = p.trim().toLowerCase(Locale.ROOT);
            switch (v) {
                case "<", "menor", "menos" -> { return "<"; }
                case ">", "mayor", "mas", "más" -> { return ">"; }
                case "<=", "menor_igual" -> { return "<="; }
                case ">=", "mayor_igual" -> { return ">="; }
                case "=", "==", "igual", "es" -> { return "="; }
                case "!=", "distinto", "no" -> { return "!="; }
                default -> { }
            }
        }
        return "=";
    }

    private static boolean comparar(double actual, String op, double referencia) {
        return switch (op) {
            case "<" -> actual < referencia;
            case ">" -> actual > referencia;
            case "<=" -> actual <= referencia;
            case ">=" -> actual >= referencia;
            case "!=" -> actual != referencia;
            default -> actual == referencia;
        };
    }

    /**
     * Compara como numero si los dos lados lo son, y si no, como texto. Asi la
     * misma condicion sirve para `VARIABLE cargas mayor 0` y para
     * `VARIABLE modo igual furia` sin tener que declarar el tipo en el YAML.
     */
    private static boolean compararTexto(String actual, String op, String esperado) {
        double a = Numeros.decimal(actual, Double.NaN);
        double b = Numeros.decimal(esperado, Double.NaN);
        if (!Double.isNaN(a) && !Double.isNaN(b)) return comparar(a, op, b);
        String x = actual == null ? "" : actual.trim();
        String y = esperado == null ? "" : esperado.trim();
        return op.equals("!=") != x.equalsIgnoreCase(y);
    }
}
