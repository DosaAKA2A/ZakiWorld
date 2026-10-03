package net.ederus.edm.goditems.equipo;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffectType;

import net.ederus.edm.goditems.Cargador;
import net.ederus.edm.goditems.Paso;
import net.ederus.edm.goditems.equipo.Definicion.Atributo;
import net.ederus.edm.goditems.equipo.Definicion.Carnada;
import net.ederus.edm.goditems.equipo.Definicion.Clave;
import net.ederus.edm.goditems.equipo.Definicion.Config;
import net.ederus.edm.goditems.equipo.Definicion.Conjunto;
import net.ederus.edm.goditems.equipo.Definicion.Efectos;
import net.ederus.edm.goditems.equipo.Definicion.Escalon;
import net.ederus.edm.goditems.equipo.Definicion.Formato;
import net.ederus.edm.goditems.equipo.Definicion.Pieza;
import net.ederus.edm.goditems.equipo.Definicion.Pocion;

/**
 * Lee la carpeta `plugins/EDM/goditems/equipo/` (todos sus .yml, en orden
 * alfabetico, sumados en una sola configuracion).
 *
 * OJO con el separador: se leen con '/' y no con el '.' de Bukkit. Las claves
 * de las piezas llevan punto ("CALAMITY.MAREA_CELESTE_CANA") y las de dominio
 * tambien ("pesca.cajas"); con el punto de siempre cada una se partia en una
 * seccion y no se encontraba nada. Es el mismo fallo que tuvieron el gear.yml de
 * PremioPescao y el equipo.yml de Calamity, y por eso el autotest lo vigila.
 *
 * Nunca lanza: lo que no entiende va a la lista de avisos con el fichero y la
 * ruta, y se carga el resto.
 */
public final class LectorEquipo {

    public static final char SEPARADOR = '/';

    private final Cargador cargador;
    private final List<String> avisos = new ArrayList<>();

    /** cargador puede ser null (pruebas): entonces las acciones de los escalones no se leen. */
    public LectorEquipo(Cargador cargador) {
        this.cargador = cargador;
    }

    /** Lee la carpeta entera. Una carpeta que no existe es una configuracion vacia. */
    public Config leerCarpeta(File carpeta) {
        Map<String, YamlConfiguration> ficheros = new LinkedHashMap<>();
        File[] lista = carpeta == null ? null
                : carpeta.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (lista != null) {
            java.util.Arrays.sort(lista);
            for (File f : lista) {
                try {
                    YamlConfiguration y = yaml(java.nio.file.Files.readString(f.toPath(),
                            java.nio.charset.StandardCharsets.UTF_8));
                    ficheros.put(f.getName(), y);
                } catch (Exception e) {
                    String m = e.getMessage() == null ? e.toString() : e.getMessage().replace('\n', ' ');
                    this.avisos.add(f.getName() + ": no se puede leer, se salta entero (" + m + ")");
                }
            }
        }
        return leer(ficheros);
    }

    /** Un texto YAML leido con '/' de separador. */
    public static YamlConfiguration yaml(String texto) throws InvalidConfigurationException {
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator(SEPARADOR);
        y.loadFromString(texto == null ? "" : texto);
        return y;
    }

    /** Varios ficheros ya cargados (nombre -> contenido), sumados. */
    public Config leer(Map<String, YamlConfiguration> ficheros) {
        Map<String, Clave> claves = new LinkedHashMap<>();
        Map<String, String> grupos = new LinkedHashMap<>();
        Map<String, Pieza> piezas = new LinkedHashMap<>();
        Map<String, Conjunto> sets = new LinkedHashMap<>();
        Map<String, Carnada> carnadas = new LinkedHashMap<>();

        /* Primero las claves y los grupos de TODOS los ficheros: una pieza de
         * pesca.yml puede usar una clave que se declara en otro fichero. */
        for (Map.Entry<String, YamlConfiguration> e : ficheros.entrySet()) {
            String f = e.getKey();
            YamlConfiguration y = e.getValue();
            ConfigurationSection g = y.getConfigurationSection("grupos");
            if (g != null) for (String k : g.getKeys(false)) grupos.put(k.toLowerCase(Locale.ROOT), g.getString(k, ""));
            ConfigurationSection c = y.getConfigurationSection("claves");
            if (c == null) continue;
            for (String k : c.getKeys(false)) {
                String id = k.trim().toLowerCase(Locale.ROOT);
                ConfigurationSection s = c.getConfigurationSection(k);
                if (s == null) {
                    aviso(f + " > claves > " + k + ": no tiene nada debajo");
                    continue;
                }
                if (claves.containsKey(id)) aviso(f + " > claves > " + k + ": ya estaba declarada; manda esta");
                claves.put(id, clave(f, id, s));
            }
        }
        for (Map.Entry<String, YamlConfiguration> e : ficheros.entrySet()) {
            String f = e.getKey();
            YamlConfiguration y = e.getValue();
            ConfigurationSection ps = y.getConfigurationSection("piezas");
            if (ps != null) {
                for (String k : ps.getKeys(false)) {
                    String id = k.trim().toUpperCase(Locale.ROOT);
                    if (!esTipoId(id)) aviso(f + " > piezas > " + k + ": no es TIPO.ID, no se encuentra nunca");
                    ConfigurationSection s = ps.getConfigurationSection(k);
                    if (s == null) {
                        aviso(f + " > piezas > " + k + ": no tiene nada debajo");
                        continue;
                    }
                    if (piezas.containsKey(id)) aviso(f + " > piezas > " + k + ": ya la definia "
                            + piezas.get(id).fichero() + "; manda esta");
                    Efectos ef = efectos(f + " > piezas > " + k, s, claves);
                    piezas.put(id, new Pieza(id, ef, s.getBoolean("lore", true), f));
                }
            }
            ConfigurationSection ss = y.getConfigurationSection("sets");
            if (ss != null) {
                for (String k : ss.getKeys(false)) {
                    ConfigurationSection s = ss.getConfigurationSection(k);
                    if (s == null) continue;
                    Conjunto c = conjunto(f, k, s, claves);
                    if (c == null) continue;
                    if (sets.containsKey(c.id())) aviso(f + " > sets > " + k + ": ya existia; manda este");
                    sets.put(c.id(), c);
                }
            }
            ConfigurationSection cs = y.getConfigurationSection("carnadas");
            if (cs != null) {
                for (String k : cs.getKeys(false)) {
                    String id = k.trim().toLowerCase(Locale.ROOT);
                    ConfigurationSection s = cs.getConfigurationSection(k);
                    if (s == null) {
                        aviso(f + " > carnadas > " + k + ": no tiene nada debajo");
                        continue;
                    }
                    if (carnadas.containsKey(id)) aviso(f + " > carnadas > " + k + ": ya la definia "
                            + carnadas.get(id).fichero() + "; manda esta");
                    Efectos ef = efectos(f + " > carnadas > " + k, s, claves);
                    if (ef.vacio()) aviso(f + " > carnadas > " + k + ": no tiene efectos, no da nada");
                    carnadas.put(id, new Carnada(id, s.getString("nombre", k), ef, f));
                }
            }
        }
        return new Config(Collections.unmodifiableMap(claves), Collections.unmodifiableMap(grupos),
                Collections.unmodifiableMap(piezas), List.copyOf(sets.values()),
                Collections.unmodifiableMap(carnadas), List.copyOf(this.avisos));
    }

    public List<String> avisos() {
        return this.avisos;
    }

    /* ------------------------------------------------------------ claves */

    private Clave clave(String f, String id, ConfigurationSection s) {
        String donde = f + " > claves > " + id;
        Formato formato = Formato.PORCENTAJE;
        String fm = s.getString("formato", "porcentaje");
        try {
            formato = Formato.valueOf(fm.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            aviso(donde + ": formato '" + fm + "' no existe (porcentaje, puntos o numero); va en porcentaje");
        }
        String sentido = s.getString("sentido", "mas").trim().toLowerCase(Locale.ROOT);
        if (!sentido.equals("mas") && !sentido.equals("más") && !sentido.equals("menos")) {
            aviso(donde + ": sentido '" + sentido + "' no existe (mas o menos); va en mas");
        }
        Double tope = numeroOpcional(s, "tope", donde);
        Double minimo = numeroOpcional(s, "minimo", donde);
        if (tope != null && minimo != null && minimo > tope) {
            aviso(donde + ": el minimo (" + minimo + ") pasa del tope (" + tope + ")");
        }
        String grupo = s.getString("grupo", id.contains(".") ? id.substring(0, id.indexOf('.')) : "efectos");
        return new Clave(id, s.getString("lore", ""), formato, sentido.equals("menos"), tope, minimo,
                grupo.toLowerCase(Locale.ROOT), s.getString("nombre", id));
    }

    private Double numeroOpcional(ConfigurationSection s, String k, String donde) {
        if (!s.contains(k)) return null;
        if (!s.isDouble(k) && !s.isInt(k)) {
            aviso(donde + " > " + k + ": '" + s.get(k) + "' no es un número; se ignora");
            return null;
        }
        return s.getDouble(k);
    }

    /* -------------------------------------------------------------- sets */

    private Conjunto conjunto(String f, String k, ConfigurationSection s, Map<String, Clave> claves) {
        String donde = f + " > sets > " + k;
        String id = k.trim().toLowerCase(Locale.ROOT);
        List<String> ids = new ArrayList<>();
        for (String x : s.getStringList("piezas")) {
            String p = x.trim().toUpperCase(Locale.ROOT);
            if (p.isEmpty() || ids.contains(p)) continue;
            if (!esTipoId(p)) aviso(donde + " > piezas: '" + x + "' no es TIPO.ID");
            ids.add(p);
        }
        String setMmo = s.getString("set-mmoitems");
        setMmo = setMmo == null || setMmo.isBlank() ? null : setMmo.trim().toUpperCase(Locale.ROOT);
        if (ids.isEmpty() && setMmo == null) {
            aviso(donde + ": sin 'piezas' ni 'set-mmoitems', no se completa nunca; no se carga");
            return null;
        }
        List<Escalon> escalones = new ArrayList<>();
        ConfigurationSection es = s.getConfigurationSection("escalones");
        if (es != null) {
            for (String n : es.getKeys(false)) {
                int necesita;
                try {
                    necesita = Integer.parseInt(n.trim());
                } catch (NumberFormatException ex) {
                    aviso(donde + " > escalones > " + n + ": tiene que ser un número de piezas");
                    continue;
                }
                ConfigurationSection e = es.getConfigurationSection(n);
                if (e == null || necesita < 1) continue;
                escalones.add(escalon(donde + " > escalones > " + n, necesita, e, claves));
            }
        }
        /* La forma corta, la de los ficheros viejos: `necesita:` y los efectos
         * en el propio set es un solo escalon. */
        if (s.contains("necesita") || s.contains("efectos") || s.contains("pociones") || s.contains("atributos")) {
            int necesita = Math.max(1, s.getInt("necesita", Math.max(1, ids.size())));
            escalones.add(escalon(donde, necesita, s, claves));
        }
        if (escalones.isEmpty()) aviso(donde + ": no tiene escalones, no da nada");
        escalones.sort((a, b) -> Integer.compare(a.necesita(), b.necesita()));
        int total = Math.max(0, s.getInt("total", ids.size()));
        for (Escalon e : escalones) {
            if (total > 0 && e.necesita() > total) {
                aviso(donde + ": el escalón de " + e.necesita() + " piezas no se alcanza (el set tiene " + total + ")");
            }
        }
        return new Conjunto(id, s.getString("nombre", k), List.copyOf(ids), setMmo, total,
                List.copyOf(escalones), s.getBoolean("lore", true), s.getString("cabecera"), f);
    }

    private Escalon escalon(String donde, int necesita, ConfigurationSection s, Map<String, Clave> claves) {
        Efectos ef = efectos(donde, s, claves);
        List<Paso> activar = List.of();
        List<Paso> perder = List.of();
        if (s.contains("al-activar") || s.contains("al-perder")) {
            if (this.cargador == null) {
                aviso(donde + ": las acciones solo se leen con el modulo en marcha");
            } else {
                activar = this.cargador.pasosSueltos(donde, "al-activar", s.getList("al-activar"));
                perder = this.cargador.pasosSueltos(donde, "al-perder", s.getList("al-perder"));
            }
        }
        return new Escalon(necesita, ef, activar, perder);
    }

    /* ------------------------------------------------------------ efectos */

    private Efectos efectos(String donde, ConfigurationSection s, Map<String, Clave> claves) {
        Map<String, Double> m = new LinkedHashMap<>();
        ConfigurationSection c = s.getConfigurationSection("efectos");
        if (c != null) {
            for (String k : c.getKeys(false)) {
                String id = k.trim().toLowerCase(Locale.ROOT);
                if (!c.isDouble(k) && !c.isInt(k)) {
                    aviso(donde + " > efectos > " + k + ": '" + c.get(k) + "' no es un número");
                    continue;
                }
                if (!claves.containsKey(id)) {
                    aviso(donde + " > efectos > " + k + ": la clave no está declarada en 'claves:'"
                            + " (suma sin tope y no sale en el lore)");
                }
                m.merge(id, c.getDouble(k), Double::sum);
            }
        }
        List<Pocion> pociones = new ArrayList<>();
        for (String linea : s.getStringList("pociones")) {
            Pocion p = pocion(linea);
            if (p == null) aviso(donde + " > pociones: no entiendo '" + linea + "' (ej.: NIGHT_VISION 1)");
            else pociones.add(p);
        }
        List<Atributo> atributos = new ArrayList<>();
        ConfigurationSection a = s.getConfigurationSection("atributos");
        if (a != null) {
            for (String k : a.getKeys(false)) {
                Atributo at = atributo(k, a.get(k));
                if (at == null) {
                    aviso(donde + " > atributos > " + k + ": no entiendo el atributo o el valor '" + a.get(k)
                            + "' (ej.: max_health: 4, movement_speed: 10%)");
                } else {
                    atributos.add(at);
                }
            }
        }
        if (m.isEmpty() && pociones.isEmpty() && atributos.isEmpty()) return Efectos.NADA;
        return new Efectos(Collections.unmodifiableMap(m), List.copyOf(pociones), List.copyOf(atributos));
    }

    /** "NIGHT_VISION 1" o "night_vision" (nivel I). El nivel es el que ve el jugador: 1 = I. */
    public static Pocion pocion(String linea) {
        if (linea == null || linea.isBlank()) return null;
        String[] p = linea.trim().split("\\s+");
        PotionEffectType t = tipoPocion(p[0]);
        if (t == null) return null;
        int nivel = 1;
        if (p.length > 1) {
            try {
                nivel = Integer.parseInt(p[1]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new Pocion(t, Math.max(0, Math.min(254, nivel - 1)));
    }

    static PotionEffectType tipoPocion(String nombre) {
        String k = nombre.trim().toLowerCase(Locale.ROOT);
        if (k.startsWith("minecraft:")) k = k.substring(10);
        try {
            return Registry.EFFECT.get(NamespacedKey.minecraft(k));
        } catch (Throwable t) {
            return null;
        }
    }

    /** max_health: 4 (se suma) o movement_speed: "10%" (un 10 % mas). */
    public static Atributo atributo(String clave, Object valor) {
        if (clave == null || valor == null) return null;
        String k = clave.trim().toLowerCase(Locale.ROOT);
        if (k.startsWith("minecraft:")) k = k.substring(10);
        if (k.startsWith("generic.") || k.startsWith("player.")) k = k.substring(k.indexOf('.') + 1);
        Attribute at;
        try {
            at = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(k));
        } catch (Throwable t) {
            at = null;
        }
        if (at == null) return null;
        String v = String.valueOf(valor).trim().replace(',', '.');
        boolean porciento = v.endsWith("%");
        if (porciento) v = v.substring(0, v.length() - 1).trim();
        double d;
        try {
            d = Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
        return porciento
                ? new Atributo(at, k, d / 100.0, AttributeModifier.Operation.ADD_SCALAR)
                : new Atributo(at, k, d, AttributeModifier.Operation.ADD_NUMBER);
    }

    static boolean esTipoId(String id) {
        int p = id.indexOf('.');
        return p > 0 && p < id.length() - 1;
    }

    private void aviso(String t) {
        this.avisos.add(t);
    }
}
