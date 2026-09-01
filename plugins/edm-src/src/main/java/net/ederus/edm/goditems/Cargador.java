package net.ederus.edm.goditems;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Lee los YAML de `plugins/EDM/goditems/items/` y los convierte en GodItems.
 *
 * Todo lo que no entiende se avisa por el log CON EL NOMBRE DEL FICHERO y la
 * linea que lo provoco, y el item se carga igual con lo que si se entendio. Un
 * item que desaparece en silencio porque alguien escribio mal una accion es la
 * forma mas rapida de perder una tarde.
 */
public final class Cargador {

    private final GodItemsPlugin modulo;
    private final List<String> avisos = new ArrayList<>();

    public Cargador(GodItemsPlugin modulo) {
        this.modulo = modulo;
    }

    public List<String> avisos() {
        return this.avisos;
    }

    /** Carga la carpeta entera. Devuelve cuantos items entraron. */
    public int cargarCarpeta(File carpeta, Registro registro) {
        this.avisos.clear();
        registro.limpiar();
        File[] ficheros = carpeta.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (ficheros == null) return 0;
        java.util.Arrays.sort(ficheros);
        int n = 0;
        for (File f : ficheros) {
            GodItem item;
            try {
                item = cargar(f);
            } catch (Throwable t) {
                aviso(f.getName() + ": no se pudo leer (" + t + ")");
                continue;
            }
            if (item == null) continue;
            String choca = registro.meter(item);
            if (choca != null) {
                aviso(f.getName() + ": el enlace " + item.enlace() + " ya lo usaba " + choca
                        + "; se queda el ultimo (" + item.id() + ").");
            }
            n++;
        }
        return n;
    }

    public GodItem cargar(File fichero) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichero);
        String nombreFichero = fichero.getName();
        String base = nombreFichero.substring(0, nombreFichero.length() - 4);
        String id = GodItem.normalizar(yml.getString("id", base));

        String enlaceTipo = null;
        String enlaceId = null;
        String enlace = yml.getString("enlace", "");
        if (enlace != null && !enlace.isBlank()) {
            int p = enlace.indexOf('.');
            if (p <= 0 || p == enlace.length() - 1) {
                aviso(nombreFichero + ": el enlace '" + enlace + "' no tiene la forma TIPO.ID; se ignora.");
            } else {
                enlaceTipo = GodItem.normalizar(enlace.substring(0, p));
                enlaceId = GodItem.normalizar(enlace.substring(p + 1));
            }
        }

        Apariencia apariencia = null;
        ConfigurationSection sec = yml.getConfigurationSection("item");
        if (enlaceTipo != null) {
            if (sec != null) {
                aviso(nombreFichero + ": es un item ENLAZADO, asi que su bloque 'item:' se ignora."
                        + " La apariencia la manda MMOItems y escribir encima se perderia"
                        + " en la primera revision de sus plantillas.");
            }
        } else if (sec == null) {
            aviso(nombreFichero + ": no tiene ni 'enlace' ni bloque 'item'; no se carga.");
            return null;
        } else {
            apariencia = apariencia(nombreFichero, sec);
            if (apariencia == null) return null;
        }

        Map<String, String> variables = new LinkedHashMap<>();
        ConfigurationSection vars = yml.getConfigurationSection("variables");
        if (vars != null) {
            for (String k : vars.getKeys(false)) {
                variables.put(k, String.valueOf(vars.get(k)));
            }
        }

        Map<Activador, GodItem.Bloque> bloques = new LinkedHashMap<>();
        ConfigurationSection acts = yml.getConfigurationSection("activadores");
        if (acts != null) {
            for (String k : acts.getKeys(false)) {
                Activador a = Activador.porNombre(k);
                if (a == null) {
                    aviso(nombreFichero + ": activador desconocido '" + k + "'. Los que hay: "
                            + listaActivadores());
                    continue;
                }
                ConfigurationSection s = acts.getConfigurationSection(k);
                if (s == null) continue;
                bloques.put(a, bloque(nombreFichero, a, s));
            }
        }
        if (bloques.isEmpty()) {
            aviso(nombreFichero + ": no tiene ningun activador. Se carga igual (sirve para /gi give),"
                    + " pero no hara nada.");
        }

        List<String> mundos = yml.getStringList("mundos");

        return new GodItem(id, enlaceTipo, enlaceId, apariencia,
                yml.getInt("usos", -1),
                yml.getInt("usos-por-dia", -1),
                yml.getBoolean("solo-dueno", false),
                yml.getBoolean("conservar-al-morir", false),
                yml.getBoolean("exclusivo", false),
                mundos == null ? List.of() : List.copyOf(mundos),
                Collections.unmodifiableMap(variables),
                Collections.unmodifiableMap(bloques));
    }

    /* ------------------------------------------------------------ apariencia */

    private Apariencia apariencia(String fichero, ConfigurationSection s) {
        String mat = s.getString("material", "STONE");
        Material material = Material.matchMaterial(mat == null ? "" : mat.trim().toUpperCase(Locale.ROOT));
        if (material == null || material.isAir()) {
            aviso(fichero + ": material desconocido '" + mat + "'; no se carga el item.");
            return null;
        }

        Map<String, Integer> encantos = new LinkedHashMap<>();
        ConfigurationSection e = s.getConfigurationSection("encantos");
        if (e != null) {
            for (String k : e.getKeys(false)) encantos.put(k, e.getInt(k, 1));
        }

        Map<String, Map<String, Double>> atributos = new LinkedHashMap<>();
        ConfigurationSection a = s.getConfigurationSection("atributos");
        if (a != null) {
            for (String hueco : a.getKeys(false)) {
                ConfigurationSection h = a.getConfigurationSection(hueco);
                if (h == null) continue;
                Map<String, Double> valores = new LinkedHashMap<>();
                for (String atr : h.getKeys(false)) valores.put(atr, h.getDouble(atr, 0));
                atributos.put(hueco, valores);
            }
        }

        Integer modelo = s.contains("modelo") ? s.getInt("modelo") : null;

        return new Apariencia(material,
                s.getString("nombre"),
                s.getStringList("lore"),
                encantos,
                atributos,
                s.getBoolean("irrompible", false),
                s.getBoolean("brillo", false),
                s.getStringList("ocultar"),
                modelo,
                s.getString("cabeza"),
                s.getString("color"),
                s.getInt("cantidad", 1));
    }

    /* --------------------------------------------------------------- bloques */

    private GodItem.Bloque bloque(String fichero, Activador a, ConfigurationSection s) {
        int cooldown = Numeros.ticks(s.getString("cooldown"), 0);
        int cada = Numeros.ticks(s.getString("cada"), 20);
        double prob = s.contains("probabilidad") ? s.getDouble("probabilidad", 100) : 100;
        int gasta = s.getInt("gasta-usos", 1);

        List<Condicion.Prueba> condiciones = new ArrayList<>();
        for (String linea : s.getStringList("condiciones")) {
            Condicion.Prueba p = Condiciones.leer(this.modulo, linea);
            if (p == null) {
                aviso(fichero + " [" + a + "]: condicion desconocida -> " + linea);
                continue;
            }
            condiciones.add(p);
        }

        List<Paso> pasos = pasos(fichero, a.name(), s.getList("acciones"));

        return new GodItem.Bloque(a, cooldown, s.getString("mensaje-cooldown"),
                s.getBoolean("cuenta-atras", true), Math.max(1, cada), prob, gasta,
                List.copyOf(condiciones), List.copyOf(pasos));
    }

    /** Traduce la lista de acciones, con sus `si:` y `repetir:` anidados. */
    @SuppressWarnings("unchecked")
    private List<Paso> pasos(String fichero, String donde, List<?> crudo) {
        List<Paso> out = new ArrayList<>();
        if (crudo == null) return out;
        for (Object o : crudo) {
            if (o instanceof String linea) {
                Paso p = simple(fichero, donde, linea);
                if (p != null) out.add(p);
                continue;
            }
            Map<?, ?> mapa = comoMapa(o);
            if (mapa == null) {
                aviso(fichero + " [" + donde + "]: no entiendo este renglon de acciones: " + o);
                continue;
            }
            Object si = valor(mapa, "si");
            Object repetir = valor(mapa, "repetir");
            if (si != null) {
                Map<?, ?> m = comoMapa(si);
                if (m == null) {
                    aviso(fichero + " [" + donde + "]: 'si' tiene que ser un bloque con"
                            + " condiciones/acciones.");
                    continue;
                }
                List<Condicion.Prueba> conds = new ArrayList<>();
                for (Object c : lista(valor(m, "condiciones"))) {
                    Condicion.Prueba pr = Condiciones.leer(this.modulo, String.valueOf(c));
                    if (pr == null) {
                        aviso(fichero + " [" + donde + "]: condicion desconocida en 'si' -> " + c);
                        continue;
                    }
                    conds.add(pr);
                }
                List<Paso> entonces = pasos(fichero, donde + "/si", lista(valor(m, "acciones")));
                List<Paso> siNo = pasos(fichero, donde + "/si-no", lista(valor(m, "si-no")));
                out.add(new Paso.Si(List.copyOf(conds), entonces, siNo));
                continue;
            }
            if (repetir != null) {
                Map<?, ?> m = comoMapa(repetir);
                if (m == null) {
                    aviso(fichero + " [" + donde + "]: 'repetir' tiene que ser un bloque con"
                            + " veces/cada/acciones.");
                    continue;
                }
                int veces = (int) Numeros.decimal(String.valueOf(valor(m, "veces")), 1);
                int cada = Numeros.ticks(String.valueOf(valor(m, "cada")), 0);
                if (veces > TOPE_REPETIR) {
                    aviso(fichero + " [" + donde + "]: repetir " + veces + " veces es demasiado;"
                            + " se recorta a " + TOPE_REPETIR + ".");
                    veces = TOPE_REPETIR;
                }
                List<Paso> dentro = pasos(fichero, donde + "/repetir", lista(valor(m, "acciones")));
                out.add(new Paso.Repetir(Math.max(0, veces), Math.max(0, cada), dentro));
                continue;
            }
            aviso(fichero + " [" + donde + "]: bloque sin 'si' ni 'repetir': " + mapa.keySet());
        }
        return out;
    }

    /**
     * El tope no es capricho: un `repetir` de cinco cifras dentro de un
     * activador de tick es un servidor parado, y lo escribe cualquiera con un
     * cero de mas.
     */
    private static final int TOPE_REPETIR = 500;

    private Paso simple(String fichero, String donde, String linea) {
        if (linea == null || linea.isBlank()) return null;
        String nombre = primeraPalabra(linea);
        if (nombre.equalsIgnoreCase("ESPERAR")) {
            Args a = Args.de(linea);
            int t = a.tiene("ticks") ? a.ticks("ticks", 20)
                    : Numeros.ticks(a.texto().isBlank() ? null : a.texto().trim(), 20);
            return new Paso.Espera(Math.max(0, t));
        }
        Accion accion = Acciones.buscar(nombre);
        if (accion == null) {
            aviso(fichero + " [" + donde + "]: accion desconocida '" + nombre + "' -> " + linea);
            return null;
        }
        Args args = accion.textoLibre() ? Args.deTextoLibre(linea) : Args.de(linea);
        return new Paso.Simple(args, accion, linea);
    }

    private static String primeraPalabra(String s) {
        String t = s.trim();
        int i = t.indexOf(' ');
        return i < 0 ? t : t.substring(0, i);
    }

    /* --------------------------------------------------------------- ayudas */

    private static Map<?, ?> comoMapa(Object o) {
        if (o instanceof Map<?, ?> m) return m;
        if (o instanceof ConfigurationSection s) return s.getValues(false);
        return null;
    }

    private static Object valor(Map<?, ?> m, String clave) {
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (String.valueOf(e.getKey()).equalsIgnoreCase(clave)) return e.getValue();
        }
        return null;
    }

    private static List<?> lista(Object o) {
        if (o instanceof List<?> l) return l;
        if (o == null) return List.of();
        return List.of(o);
    }

    private void aviso(String texto) {
        this.avisos.add(texto);
        this.modulo.getLogger().warning("[GodItems] " + texto);
    }

    private static String listaActivadores() {
        StringBuilder sb = new StringBuilder();
        for (Activador a : Activador.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(a.name());
        }
        return sb.toString();
    }
}
