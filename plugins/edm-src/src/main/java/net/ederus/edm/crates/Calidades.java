package net.ederus.edm.crates;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Lo que dice crates/config.yml, ya leido y comprobado.
 *
 * No toca PhoenixCrates ni Bukkit: es solo el modelo, para que el sorteo y las
 * cuentas de /edm crates info se puedan probar sin servidor.
 */
final class Calidades {

    /** Una calidad: Raro, Epico... El orden es el de la lista del config. */
    record Calidad(String id, String nombre, String color, int orden, boolean unaVez) {
    }

    /** Un premio de una caja: el identifier de PhoenixCrates, su calidad y su peso dentro de ella. */
    record Premio(String id, String calidad, double peso) {
    }

    /** Una caja configurada: el porcentaje de cada calidad y sus premios. */
    record Caja(String id, Map<String, Double> porcentajes, Map<String, Premio> premios) {

        Premio premio(String identificador) {
            return this.premios.get(identificador);
        }
    }

    /** Los textos del lore de la vista previa. */
    record Lore(String linea, String unaVez, String yaObtenido) {
    }

    private final Map<String, Calidad> calidades;
    private final Map<String, Caja> cajas;
    private final Lore lore;
    private final List<String> avisos;

    private Calidades(Map<String, Calidad> calidades, Map<String, Caja> cajas, Lore lore, List<String> avisos) {
        this.calidades = calidades;
        this.cajas = cajas;
        this.lore = lore;
        this.avisos = avisos;
    }

    Calidad calidad(String id) {
        return this.calidades.get(id);
    }

    Collection<Calidad> calidades() {
        return this.calidades.values();
    }

    Caja caja(String id) {
        return id == null ? null : this.cajas.get(id.toLowerCase(Locale.ROOT));
    }

    Collection<Caja> cajas() {
        return this.cajas.values();
    }

    Lore lore() {
        return this.lore;
    }

    /** Lo que esta mal escrito en el config. Vacio si todo cuadra. */
    List<String> avisos() {
        return this.avisos;
    }

    static Calidades leer(ConfigurationSection raiz) {
        List<String> avisos = new ArrayList<>();
        Map<String, Calidad> calidades = new LinkedHashMap<>();

        ConfigurationSection sc = raiz.getConfigurationSection("calidades");
        if (sc == null) {
            avisos.add("No hay seccion 'calidades': el modulo no sortea ninguna caja.");
        } else {
            int orden = 0;
            for (String id : sc.getKeys(false)) {
                ConfigurationSection c = sc.getConfigurationSection(id);
                if (c == null) {
                    avisos.add("La calidad '" + id + "' no es una seccion; se ignora.");
                    continue;
                }
                String color = c.getString("color", "#FFFFFF").trim();
                if (color.startsWith("#")) color = color.substring(1);
                if (!color.matches("[0-9A-Fa-f]{6}")) {
                    avisos.add("La calidad '" + id + "' tiene un color que no es #RRGGBB: " + c.getString("color"));
                    color = "FFFFFF";
                }
                String clave = id.toLowerCase(Locale.ROOT);
                calidades.put(clave, new Calidad(clave, c.getString("nombre", id), color.toUpperCase(Locale.ROOT),
                        orden++, c.getBoolean("una-vez", false)));
            }
        }

        Lore lore = new Lore(
                raiz.getString("lore.linea", "&#%color%◆ %calidad% &#8F8F8F· %probabilidad% %"),
                raiz.getString("lore.una-vez", "&#8F8F8FSe obtiene una sola vez"),
                raiz.getString("lore.ya-obtenido", "&#FF5C5CYa lo obtuviste"));

        Map<String, Caja> cajas = new LinkedHashMap<>();
        ConfigurationSection scj = raiz.getConfigurationSection("cajas");
        if (scj != null) {
            for (String idCaja : scj.getKeys(false)) {
                ConfigurationSection cj = scj.getConfigurationSection(idCaja);
                if (cj == null) continue;
                String caja = idCaja.toLowerCase(Locale.ROOT);

                Map<String, Double> porcentajes = new LinkedHashMap<>();
                ConfigurationSection pc = cj.getConfigurationSection("calidades");
                if (pc != null) {
                    for (String q : pc.getKeys(false)) {
                        String clave = q.toLowerCase(Locale.ROOT);
                        double v = pc.getDouble(q, -1);
                        if (!calidades.containsKey(clave)) {
                            avisos.add("Caja " + caja + ": la calidad '" + q + "' no existe en 'calidades'.");
                            continue;
                        }
                        if (v < 0 || Double.isNaN(v)) {
                            avisos.add("Caja " + caja + ": el porcentaje de '" + q + "' no es un numero valido.");
                            continue;
                        }
                        porcentajes.put(clave, v);
                    }
                }

                Map<String, Premio> premios = new LinkedHashMap<>();
                ConfigurationSection pr = cj.getConfigurationSection("premios");
                if (pr != null) {
                    for (String idPremio : pr.getKeys(false)) {
                        ConfigurationSection p = pr.getConfigurationSection(idPremio);
                        if (p == null) {
                            avisos.add("Caja " + caja + ": el premio '" + idPremio + "' no tiene calidad ni peso.");
                            continue;
                        }
                        String q = p.getString("calidad", "").toLowerCase(Locale.ROOT);
                        double peso = p.getDouble("peso", 1);
                        if (!calidades.containsKey(q)) {
                            avisos.add("Caja " + caja + ": el premio " + idPremio + " tiene una calidad que no existe: '"
                                    + p.getString("calidad") + "'. No saldra.");
                            continue;
                        }
                        if (!porcentajes.containsKey(q)) {
                            avisos.add("Caja " + caja + ": el premio " + idPremio + " es " + q
                                    + ", pero la caja no da porcentaje a esa calidad. No saldra.");
                        }
                        if (peso <= 0 || Double.isNaN(peso)) {
                            avisos.add("Caja " + caja + ": el premio " + idPremio + " tiene peso " + peso + ". No saldra.");
                            continue;
                        }
                        premios.put(idPremio, new Premio(idPremio, q, peso));
                    }
                }
                if (premios.isEmpty()) {
                    avisos.add("Caja " + caja + " no tiene premios validos: sorteara PhoenixCrates como siempre.");
                    continue;
                }
                cajas.put(caja, new Caja(caja, Collections.unmodifiableMap(porcentajes),
                        Collections.unmodifiableMap(premios)));
            }
        }
        return new Calidades(Collections.unmodifiableMap(calidades), Collections.unmodifiableMap(cajas), lore,
                Collections.unmodifiableList(avisos));
    }
}
