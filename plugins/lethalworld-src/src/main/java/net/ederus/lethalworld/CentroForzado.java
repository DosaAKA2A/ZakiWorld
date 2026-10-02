package net.ederus.lethalworld;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Un bioma elegido en el centro de un mundo (LethalWorld 2.1.0).
 *
 * Minecraft elige el bioma de cada punto con cinco senales de "clima" (temperatura,
 * humedad, continentalidad, erosion y rareza) y se queda con el bioma cuyo punto de la
 * plantilla queda mas cerca. En Panacea el bioma rojo pide justo la combinacion mas rara,
 * asi que casi nunca sale en el centro. Buscar semillas a ciegas no lo garantiza.
 *
 * Esto envuelve esas cinco senales EN ESE MUNDO: dentro de `radio` bloques del centro valen
 * lo que pide el bioma elegido; en los `transicion` bloques siguientes se mezclan poco a poco
 * con las de verdad; fuera no se tocan. El borde del bioma sale natural (lo decide el juego
 * con el clima mezclado) y la superficie, las decoraciones y las estructuras salen como en
 * cualquier bioma de ese tipo, porque es el propio generador el que lo decide.
 *
 * El TERRENO no cambia: la forma la calcula el juego con sus propias funciones, no con estas
 * copias del router. Lo que cambia es solo que bioma se pinta.
 *
 * No es persistente: se aplica en cada arranque, antes de que nadie genere un chunk. Un chunk
 * ya generado no cambia nunca.
 *
 * Va por reflexion sobre el servidor (Paper con nombres de Mojang): el plugin solo compila
 * contra la API. Si algo no encaja (otra version), lo dice en la consola y no toca nada.
 */
final class CentroForzado {

    /** router -> nombre del parametro en la plantilla del generador. depth no se toca. */
    private static final Map<String, String> ROUTER = Map.of(
            "temperature", "temperature", "vegetation", "humidity", "continents", "continentalness",
            "erosion", "erosion", "ridges", "weirdness");
    /** Climate.Sampler -> nombre del parametro. */
    private static final Map<String, String> MUESTREO = Map.of(
            "temperature", "temperature", "humidity", "humidity", "continentalness", "continentalness",
            "erosion", "erosion", "weirdness", "weirdness");

    private final LethalWorldPlugin plugin;

    CentroForzado(LethalWorldPlugin plugin) {
        this.plugin = plugin;
    }

    /** Lo aplica a todos los mundos cargados con `mundos.<nombre>.centro`. */
    void aplicarATodos() {
        for (String nombre : plugin.mundos().keySet()) {
            World w = plugin.mundo(nombre);
            if (w != null) aplicar(nombre, w);
        }
    }

    void aplicar(String nombre, World w) {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("mundos." + nombre + ".centro");
        if (c == null || !c.getBoolean("activo", true)) return;
        String bioma = c.getString("bioma", "");
        String generador = plugin.mundos().get(nombre);
        Map<String, Double> objetivo = climaDe(generador, bioma);
        if (objetivo == null) {
            plugin.getLogger().warning("[Lethal World] centro de " + nombre + ": el generador " + generador
                    + " no reparte '" + bioma + "'. No se aplica.");
            return;
        }
        double cx = c.getDouble("x", 0), cz = c.getDouble("z", 0);
        double radio = Math.max(0, c.getDouble("radio", 160));
        double transicion = Math.max(1, c.getDouble("transicion", 96));
        try {
            int n = parchear(w, objetivo, cx, cz, radio, transicion);
            plugin.getLogger().info("[Lethal World] Centro de " + nombre + ": " + bioma + " en (" + (long) cx + ", "
                    + (long) cz + "), radio " + (long) radio + " + " + (long) transicion + " de transicion ("
                    + n + " senales envueltas).");
        } catch (Throwable t) {
            plugin.getLogger().warning("[Lethal World] centro de " + nombre + ": no se pudo aplicar ("
                    + t + "). El mundo genera como siempre.");
        }
    }

    /** Los cinco valores de clima del bioma en la plantilla del generador (centro del rango si es un rango). */
    private Map<String, Double> climaDe(String generador, String bioma) {
        if (generador == null || bioma.isBlank()) return null;
        byte[] plantilla = leer("generadores/" + generador + ".json");
        if (plantilla == null) return null;
        try {
            JsonObject raiz = JsonParser.parseString(new String(plantilla, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray biomas = raiz.getAsJsonObject("generator").getAsJsonObject("biome_source").getAsJsonArray("biomes");
            for (JsonElement e : biomas) {
                JsonObject o = e.getAsJsonObject();
                if (!bioma.equals(o.get("biome").getAsString())) continue;
                JsonObject p = o.getAsJsonObject("parameters");
                Map<String, Double> out = new LinkedHashMap<>();
                for (String k : new String[]{"temperature", "humidity", "continentalness", "erosion", "weirdness"}) {
                    JsonElement v = p.get(k);
                    if (v == null) return null;
                    if (v.isJsonArray()) {
                        JsonArray a = v.getAsJsonArray();
                        out.put(k, (a.get(0).getAsDouble() + a.get(a.size() - 1).getAsDouble()) / 2);
                    } else {
                        out.put(k, v.getAsDouble());
                    }
                }
                return out;
            }
        } catch (RuntimeException ignorada) {
            return null;
        }
        return null;
    }

    private byte[] leer(String ruta) {
        try (var in = plugin.getResource(ruta)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ el parche

    private int parchear(World w, Map<String, Double> objetivo, double cx, double cz, double radio, double transicion)
            throws Exception {
        Object nivel = w.getClass().getMethod("getHandle").invoke(w);
        Object cache = nivel.getClass().getMethod("getChunkSource").invoke(nivel);
        Object estado = cache.getClass().getMethod("randomState").invoke(cache);

        Field fRouter = campoPorTipo(estado.getClass(), "NoiseRouter");
        Field fMuestreo = campoPorTipo(estado.getClass(), "Sampler");
        Object router = fRouter.get(estado);
        Object muestreo = fMuestreo.get(estado);

        Class<?> funcion = Class.forName("net.minecraft.world.level.levelgen.DensityFunction", true,
                router.getClass().getClassLoader());
        Envoltorio env = new Envoltorio(funcion, cx, cz, radio, transicion);

        int n = 0;
        Object nuevoRouter = rehacerRecord(router, ROUTER, objetivo, env);
        n += ROUTER.size();
        Object nuevoMuestreo = rehacerRecord(muestreo, MUESTREO, objetivo, env);
        n += MUESTREO.size();
        fRouter.set(estado, nuevoRouter);
        fMuestreo.set(estado, nuevoMuestreo);
        return n;
    }

    private static Field campoPorTipo(Class<?> c, String nombreSimple) throws NoSuchFieldException {
        for (Field f : c.getDeclaredFields()) {
            if (f.getType().getSimpleName().equals(nombreSimple)) {
                f.setAccessible(true);
                return f;
            }
        }
        throw new NoSuchFieldException(nombreSimple + " en " + c.getName());
    }

    /** Copia del record con los componentes de `cuales` envueltos hacia su valor objetivo. */
    private static Object rehacerRecord(Object rec, Map<String, String> cuales, Map<String, Double> objetivo,
                                        Envoltorio env) throws Exception {
        RecordComponent[] comps = rec.getClass().getRecordComponents();
        Object[] args = new Object[comps.length];
        Class<?>[] tipos = new Class<?>[comps.length];
        int envueltos = 0;
        for (int i = 0; i < comps.length; i++) {
            Method acc = comps[i].getAccessor();
            acc.setAccessible(true);
            Object v = acc.invoke(rec);
            tipos[i] = comps[i].getType();
            String clima = cuales.get(comps[i].getName());
            if (clima != null && env.funcion.isInstance(v)) {
                args[i] = env.envolver(v, objetivo.get(clima));
                envueltos++;
            } else {
                args[i] = v;
            }
        }
        if (envueltos != cuales.size()) {
            throw new IllegalStateException(rec.getClass().getSimpleName() + ": solo " + envueltos + " de "
                    + cuales.size() + " senales encontradas");
        }
        Constructor<?> k = rec.getClass().getDeclaredConstructor(tipos);
        k.setAccessible(true);
        return k.newInstance(args);
    }

    /**
     * Una DensityFunction de Minecraft que devuelve la original mezclada con un valor fijo
     * segun la distancia al centro. Es un Proxy de la interfaz: no hace falta compilar
     * contra el servidor.
     */
    private static final class Envoltorio {
        final Class<?> funcion;
        final double cx, cz, radio, transicion;
        final MethodHandle compute, fillArray, mapAll, minValue, maxValue, blockX, blockZ, forIndex, aplicarVisitor;

        Envoltorio(Class<?> funcion, double cx, double cz, double radio, double transicion) throws Exception {
            this.funcion = funcion;
            this.cx = cx;
            this.cz = cz;
            this.radio = radio;
            this.transicion = transicion;
            ClassLoader cl = funcion.getClassLoader();
            Class<?> contexto = Class.forName(funcion.getName() + "$FunctionContext", true, cl);
            Class<?> proveedor = Class.forName(funcion.getName() + "$ContextProvider", true, cl);
            Class<?> visitante = Class.forName(funcion.getName() + "$Visitor", true, cl);
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            this.compute = l.unreflect(funcion.getMethod("compute", contexto));
            this.fillArray = l.unreflect(funcion.getMethod("fillArray", double[].class, proveedor));
            this.mapAll = l.unreflect(funcion.getMethod("mapAll", visitante));
            this.minValue = l.unreflect(funcion.getMethod("minValue"));
            this.maxValue = l.unreflect(funcion.getMethod("maxValue"));
            this.blockX = l.unreflect(contexto.getMethod("blockX"));
            this.blockZ = l.unreflect(contexto.getMethod("blockZ"));
            this.forIndex = l.unreflect(proveedor.getMethod("forIndex", int.class));
            this.aplicarVisitor = l.unreflect(visitante.getMethod("apply", funcion));
        }

        /** 1 dentro del radio, 0 fuera de la transicion, suave entre medias. */
        double peso(int x, int z) {
            double d = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
            if (d <= radio) return 1;
            double t = (d - radio) / transicion;
            if (t >= 1) return 0;
            return 1 - t * t * (3 - 2 * t);
        }

        Object envolver(Object original, double objetivo) {
            InvocationHandler h = (proxy, m, args) -> {
                switch (m.getName()) {
                    case "compute": {
                        Object ctx = args[0];
                        double v = (double) compute.invoke(original, ctx);
                        double p = peso((int) blockX.invoke(ctx), (int) blockZ.invoke(ctx));
                        return p == 0 ? v : v + (objetivo - v) * p;
                    }
                    case "fillArray": {
                        double[] arr = (double[]) args[0];
                        Object prov = args[1];
                        fillArray.invoke(original, arr, prov);
                        for (int i = 0; i < arr.length; i++) {
                            Object ctx = forIndex.invoke(prov, i);
                            double p = peso((int) blockX.invoke(ctx), (int) blockZ.invoke(ctx));
                            if (p != 0) arr[i] = arr[i] + (objetivo - arr[i]) * p;
                        }
                        return null;
                    }
                    case "mapAll": {
                        Object mapeado = mapAll.invoke(original, args[0]);
                        return aplicarVisitor.invoke(args[0], envolver(mapeado, objetivo));
                    }
                    case "minValue":
                        return Math.min((double) minValue.invoke(original), objetivo);
                    case "maxValue":
                        return Math.max((double) maxValue.invoke(original), objetivo);
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    case "toString":
                        return "LethalWorldCentro[" + original + " -> " + objetivo + "]";
                    default:
                        if (m.isDefault()) return InvocationHandler.invokeDefault(proxy, m, args);
                        return m.invoke(original, args);
                }
            };
            return Proxy.newProxyInstance(funcion.getClassLoader(), new Class<?>[]{funcion}, h);
        }
    }
}
