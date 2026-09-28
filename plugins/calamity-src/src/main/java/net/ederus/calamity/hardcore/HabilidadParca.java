package net.ederus.calamity.hardcore;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * El repertorio de la PARCA de EDM (Calamity 1.1.0), con lo que el menu de /anomaly ensena de cada
 * tecnica: nombre, descripcion, fases, espera, duracion y peso. La pelea (ParcaAnomalia) no
 * deja elegir a EDM al azar: las escoge ella segun la distancia, la vista y lo que acaba de
 * hacer, y usa el peso de aqui como punto de partida.
 *
 * Una fila por tecnica y nada mas: si se anade una, sale sola en el menu, en /anomaly test y
 * en /lw hardcore parca habilidad.
 */
enum HabilidadParca {

    // id, nombre, descripcion, fase desde, fase hasta, espera (ticks), duracion (ticks), peso, iconos
    SIEGA("pa_siega", "Siega", "Un tajo en abanico que atraviesa paredes. A quien esté quieto le hace el doble.",
            1, 4, 120, 30, 4, "NETHERITE_HOE"),
    TAJOS("pa_tajos", "Tajos", "Dos o tres cortes seguidos; el último es más ancho y empuja más.",
            1, 4, 140, 50, 5, "IRON_HOE"),
    ACECHO("pa_acecho", "Acecho", "Da una vuelta rápida alrededor de su presa y, al acabarla, corta.",
            1, 4, 180, 60, 3, "SOUL_LANTERN"),
    ACOMETIDA("pa_acometida", "Acometida", "Marca una línea en el suelo y la recorre de golpe, dejando una estela de almas.",
            1, 4, 180, 36, 4, "SOUL_TORCH"),
    UMBRAL("pa_umbral", "Paso Umbral", "Aparece detrás de quien se esconde o se aleja.",
            1, 2, 160, 24, 3, "ENDER_EYE"),
    TIRON("pa_tiron", "Tirón", "Lanza una cadena a su presa: si nadie la rompe, la arrastra hasta la guadaña.",
            2, 4, 240, 24, 3, "IRON_CHAIN", "CHAIN"),
    CORTEJO("pa_cortejo", "Cortejo", "Invoca plañideras que giran a su alrededor y quitan cordura; mientras quede alguna, recibe menos daño.",
            2, 4, 600, 30, 6, "WITHER_ROSE"),
    GUADANAS("pa_guadanas", "Guadañas giratorias", "Suelta guadañas que giran a ras de suelo en línea recta.",
            2, 4, 320, 24, 3, "NETHERITE_HOE"),
    SALTO("pa_salto", "Salto de la guadaña", "Salta sobre su presa y, al caer, suelta una onda que hay que saltar.",
            2, 4, 260, 44, 3, "WIND_CHARGE", "FEATHER"),
    EMBESTIDA("pa_embestida", "Retirada y embestida", "Se aparta, marca el camino y embiste atravesándolo todo.",
            2, 4, 300, 60, 2, "SOUL_CAMPFIRE", "BLAZE_ROD"),
    UMBRALES("pa_umbrales", "Umbrales", "Encadena dos o tres Pasos Umbral, con un corte en cada uno.",
            3, 4, 240, 72, 3, "ENDER_PEARL"),
    LLUVIA("pa_lluvia", "Lluvia de almas", "Hace caer almas donde pisas, en oleadas.",
            3, 4, 360, 76, 3, "SOUL_SAND"),
    MUROS("pa_muros", "Muros de almas", "Una cruz de almas parte la arena y gira: no te quedes en su camino.",
            3, 4, 440, 130, 2, "IRON_BARS"),
    ANILLO("pa_anillo", "Anillo que se cierra", "Un anillo de almas se cierra hacia ella: sal por su único hueco.",
            4, 4, 400, 84, 3, "RECOVERY_COMPASS", "COMPASS"),
    SENTENCIA("pa_sentencia", "Sentencia", "Cinco campanadas: quien siga dentro del anillo al sonar la última recibe la Sentencia.",
            4, 4, 600, 120, 4, "BELL");

    final String id;
    final String nombre;
    final String descripcion;
    final int faseDesde;
    final int faseHasta;
    /** Ticks entre dos usos por defecto (la Siega, el Umbral, el Tiron, el Cortejo y la Sentencia leen la config). */
    final int espera;
    /** Lo que dura mas o menos, en ticks: lo usa /anomaly test all para encadenarlas. */
    final int duracion;
    final int peso;
    private final String[] iconos;

    HabilidadParca(String id, String nombre, String descripcion, int faseDesde, int faseHasta, int espera,
                   int duracion, int peso, String... iconos) {
        this.id = id;
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.faseDesde = faseDesde;
        this.faseHasta = faseHasta;
        this.espera = espera;
        this.duracion = duracion;
        this.peso = peso;
        this.iconos = iconos;
    }

    /** El id sin el prefijo: lo que se escribe en /lw hardcore parca habilidad. */
    String alias() {
        return id.substring(3);
    }

    boolean enFase(int fase) {
        return fase >= faseDesde && fase <= faseHasta;
    }

    /** Lo que ve el menu de EDM: una fase si solo sale en una, 0 ("cualquiera") si sale en varias. */
    int faseMenu() {
        return faseDesde == faseHasta ? faseDesde : 0;
    }

    /** La descripcion con las fases en las que sale, si no son todas. */
    String descripcionMenu() {
        if (faseDesde == 1 && faseHasta == 4) return descripcion;
        if (faseDesde == faseHasta) return descripcion + " (fase " + Parca.romano(faseDesde) + ")";
        return descripcion + " (fases " + Parca.romano(faseDesde) + "-" + Parca.romano(faseHasta) + ")";
    }

    /** Primer material de la lista que exista en esta version y se pueda tener en la mano. */
    Material icono() {
        for (String n : iconos) {
            Material m = Material.matchMaterial(n);
            if (m != null && m.isItem()) return m;
        }
        return Material.PAPER;
    }

    /** Por id (pa_siega), por alias (siega) o por los nombres de la Parca de 1.1.1 (paso, campanada). */
    static HabilidadParca buscar(String nombre) {
        if (nombre == null) return null;
        String n = nombre.toLowerCase(Locale.ROOT).trim();
        if (n.startsWith("pa_")) n = n.substring(3);
        switch (n) {
            case "paso" -> {
                return UMBRAL;
            }
            case "campanada" -> {
                return SENTENCIA;
            }
            case "guadañas" -> {
                return GUADANAS;
            }
            default -> {
                for (HabilidadParca h : values()) if (h.alias().equals(n)) return h;
                return null;
            }
        }
    }

    /** Los alias, para el tabulador y los mensajes de uso. */
    static List<String> nombres() {
        List<String> out = new ArrayList<>();
        for (HabilidadParca h : values()) out.add(h.alias());
        return out;
    }
}
