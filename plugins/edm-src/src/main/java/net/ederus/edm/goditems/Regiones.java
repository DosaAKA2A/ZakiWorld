package net.ederus.edm.goditems;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Location;

/**
 * Las regiones de WorldGuard que hay en un punto, por reflexion.
 *
 * Por reflexion y no como dependencia por lo mismo que en el resto de EDM: si
 * manana WorldGuard no esta, el modulo tiene que arrancar igual y perder solo
 * esta condicion. `anomaly.core.Protection` ya hace este puente, pero solo sabe
 * decir SI HAY o NO HAY region; aqui hace falta el nombre.
 */
public final class Regiones {

    private final GodItemsPlugin modulo;

    private boolean listo;
    private Object consulta;
    private Method adaptar;
    private Method aplicables;
    private Method getRegions;
    private Method getId;

    public Regiones(GodItemsPlugin modulo) {
        this.modulo = modulo;
        enganchar();
    }

    public boolean listo() {
        return this.listo;
    }

    private void enganchar() {
        if (this.modulo.core().getServer().getPluginManager().getPlugin("WorldGuard") == null) {
            this.modulo.getLogger().info("[GodItems] WorldGuard no esta: la condicion REGION siempre dara falso.");
            return;
        }
        try {
            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object wg = wgClass.getMethod("getInstance").invoke(null);
            Object plataforma = wgClass.getMethod("getPlatform").invoke(wg);
            Object contenedor = plataforma.getClass().getMethod("getRegionContainer").invoke(plataforma);
            this.consulta = contenedor.getClass().getMethod("createQuery").invoke(contenedor);

            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            for (Method m : adapter.getMethods()) {
                if (m.getName().equals("adapt") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == Location.class) {
                    this.adaptar = m;
                    break;
                }
            }
            if (this.adaptar == null) throw new NoSuchMethodException("BukkitAdapter.adapt(Location)");

            this.aplicables = this.consulta.getClass()
                    .getMethod("getApplicableRegions", this.adaptar.getReturnType());
            this.getRegions = this.aplicables.getReturnType().getMethod("getRegions");
            this.getId = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion")
                    .getMethod("getId");
            this.listo = true;
            this.modulo.getLogger().info("[GodItems] WorldGuard enganchado: la condicion REGION funciona.");
        } catch (Throwable t) {
            this.modulo.getLogger().warning("[GodItems] WorldGuard esta pero no se pudo enganchar ("
                    + t + "): la condicion REGION dara siempre falso.");
        }
    }

    /** true si en ese punto manda una region con ese id (o cualquiera, si id es null). */
    @SuppressWarnings("unchecked")
    public boolean en(Location donde, String id) {
        if (!this.listo || donde == null) return false;
        try {
            Object weLoc = this.adaptar.invoke(null, donde);
            Object set = this.aplicables.invoke(this.consulta, weLoc);
            Object regiones = this.getRegions.invoke(set);
            if (!(regiones instanceof Set<?> s)) return false;
            if (id == null || id.isBlank()) return !s.isEmpty();
            for (Object r : s) {
                String nombre = String.valueOf(this.getId.invoke(r));
                if (nombre.equalsIgnoreCase(id.trim())) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    public String nombres(Location donde) {
        if (!this.listo || donde == null) return "";
        try {
            Object weLoc = this.adaptar.invoke(null, donde);
            Object set = this.aplicables.invoke(this.consulta, weLoc);
            Object regiones = this.getRegions.invoke(set);
            if (!(regiones instanceof Set<?> s) || s.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (Object r : s) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(String.valueOf(this.getId.invoke(r)).toLowerCase(Locale.ROOT));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
