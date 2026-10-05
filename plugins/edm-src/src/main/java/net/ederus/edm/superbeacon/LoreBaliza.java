package net.ederus.edm.superbeacon;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BinaryOperator;

/**
 * El nombre y el lore del Super Beacon como objeto, en texto con codigos &, sin Bukkit (el
 * selftest y la muestra los generan fuera del servidor).
 *
 * Un solo color por objeto, el de su tipo, en tres tonos: el fuerte (el color del nombre en
 * el config), el claro (un 45 % hacia blanco: el inicio del degradado del nombre) y el palido
 * (un 35 % hacia blanco: la frase y las etiquetas). Lo demas en blanco o gris.
 *
 *   Trofeo de Temporada                 degradado claro -> fuerte, sin negrita
 *   Campeón de la semana · Clan TEST    categoria en el fuerte, el resto en palido
 *
 *   "Un solo clan lo gana cada semana.   la frase, entre comillas, en palido
 *    Esta vez, el tuyo."
 *
 *   ◆ Efectos para tu clan              en el fuerte
 *    ✦ +6 corazones                      simbolo en el fuerte, texto en blanco
 *   A 48 bloques a la redonda.          gris
 *
 *   Líder: Dosa__                       etiqueta en palido, valor en blanco
 *   Vence el domingo 11/10 a las 22:00.
 *
 *   Colócalo y haz clic derecho para     gris
 *   abrir su menú. Solo el líder lo mueve.
 *
 * Bloques separados por una linea en blanco, sin rayas. Ninguna linea pasa de
 * {@link Presentacion#ANCHO} caracteres: las largas se parten por palabras.
 */
final class LoreBaliza {

    static final String BLANCO = "&#F4F4F4";
    static final String GRIS = "&#7A7A7A";
    static final String APAGADO = "&#4E4E4E";

    /** Lo que se le pide a mensajes.yml: (clave, respaldo) -> texto. */
    private final BinaryOperator<String> tx;
    private final ZoneId zona;

    LoreBaliza(BinaryOperator<String> textos, ZoneId zona) {
        this.tx = textos;
        this.zona = zona;
    }

    /* ================================================================ tonos */

    static int claro(int fuerte) {
        return Presentacion.mezcla(fuerte, 0xFFFFFF, 0.45);
    }

    static int palido(int fuerte) {
        return Presentacion.mezcla(fuerte, 0xFFFFFF, 0.35);
    }

    /* =============================================================== nombre */

    /** El nombre del tipo, sin sus codigos, en degradado del claro al fuerte. */
    static String nombre(TipoBaliza t) {
        if (t == null) return "&#D7F3FFSuper Beacon";
        int fuerte = t.color();
        return Presentacion.degradado(t.nombrePlano().strip(), claro(fuerte), fuerte);
    }

    /* ================================================================= lore */

    List<String> lore(Ficha f, TipoBaliza t, long ahora) {
        List<String> out = new ArrayList<>();
        if (t == null) {
            out.add(tx.apply("objeto-tipo-perdido", "&#FF5C5CSu tipo (%tipo%) ya no existe. Avisa al staff.")
                    .replace("%tipo%", f.tipo()));
            return out;
        }
        int c = t.color();
        String fuerte = Presentacion.hex(c);
        String palido = Presentacion.hex(palido(c));
        boolean deClan = t.beneficia == TipoBaliza.Beneficia.CLAN;
        boolean trofeo = t.semanal || f.semana() > 0;

        // 1. Que es: categoria y a quien.
        String categoria = trofeo
                ? tx.apply("lore-categoria-trofeo", "Campeón de la semana")
                : tx.apply("lore-categoria", "Super Beacon");
        String para;
        if (f.clan() != null && deClan) {
            para = tx.apply("lore-de-clan-fijo", "Clan %clan%").replace("%clan%", f.clan());
        } else if (trofeo) {
            para = "";
        } else {
            para = switch (t.beneficia) {
                case CLAN -> tx.apply("lore-de-clan", "De clan");
                case TODOS -> tx.apply("lore-de-todos", "Para todos");
                default -> tx.apply("lore-de-dueno", "Personal");
            };
        }
        String linea2 = para.isEmpty()
                ? tx.apply("lore-cabecera-sola", "%fuerte%%categoria%")
                : tx.apply("lore-cabecera", "%fuerte%%categoria%" + GRIS + " · %palido%%para%");
        agregar(out, colores(linea2, fuerte, palido).replace("%categoria%", categoria).replace("%para%", para));

        // 2. La frase.
        String frase = frase(t);
        if (!frase.isEmpty()) {
            out.add("");
            List<String> partes = Presentacion.partir("\"" + frase + "\"", Presentacion.ANCHO - 1);
            for (int i = 0; i < partes.size(); i++) {
                out.add(palido + (i == 0 ? "" : " ") + partes.get(i));
            }
        }

        // 3. Que da y donde.
        out.add("");
        String cabecera = switch (t.beneficia) {
            case CLAN -> tx.apply("lore-efectos-clan", "%fuerte%◆ Efectos para tu clan");
            case TODOS -> tx.apply("lore-efectos-todos", "%fuerte%◆ Efectos para todos");
            default -> tx.apply("lore-efectos-dueno", "%fuerte%◆ Efectos para ti");
        };
        agregar(out, colores(cabecera, fuerte, palido));
        // Si ya eligio alguno, lo elegido brilla y lo demas queda en gris; sin elegir nada, todo igual.
        List<Efecto> activos = t.activos(f.elegidos());
        boolean marcar = !t.fijo() && !activos.isEmpty();
        for (Efecto e : Presentacion.agrupados(t.efectos.values())) {
            boolean vivo = !marcar || activos.contains(e);
            String plantilla = vivo
                    ? tx.apply("lore-efecto", " %fuerte%✦ " + BLANCO + "%efecto%")
                    : tx.apply("lore-efecto-libre", " " + APAGADO + "✦ " + GRIS + "%efecto%");
            agregar(out, colores(plantilla, fuerte, palido).replace("%efecto%", e.nombrePlano()));
        }
        String elige = t.fijo() ? "" : tx.apply("lore-elige", " Elige %elegibles%.")
                .replace("%elegibles%", String.valueOf(t.elegibles));
        agregar(out, colores(tx.apply("lore-alcance", GRIS + "A %radio% bloques a la redonda.%elige%"), fuerte, palido)
                .replace("%radio%", String.valueOf(t.radio)).replace("%elige%", elige));

        // 4. De quien y hasta cuando.
        out.add("");
        String quien;
        if (f.ligada()) {
            quien = (deClan ? tx.apply("lore-lider", "%palido%Líder: " + BLANCO + "%dueno%")
                    : tx.apply("lore-dueno", "%palido%Dueño: " + BLANCO + "%dueno%")).replace("%dueno%", f.duenoTexto());
        } else {
            quien = deClan ? tx.apply("lore-lider-libre", "%palido%Líder: " + BLANCO + "quien lo coloque primero")
                    : tx.apply("lore-dueno-libre", "%palido%Dueño: " + BLANCO + "quien lo coloque primero");
        }
        agregar(out, colores(quien, fuerte, palido));
        String cuando;
        if (!f.caduca()) {
            cuando = tx.apply("lore-permanente", "%palido%Duración: " + BLANCO + "permanente");
        } else {
            String fecha = Presentacion.fechaLore(f.vence(), zona, ahora);
            cuando = (f.vencida(ahora)
                    ? tx.apply("lore-vencio", "%palido%Venció el " + BLANCO + "%fecha%%palido%.")
                    : tx.apply("lore-vence", "%palido%Vence el " + BLANCO + "%fecha%%palido%."))
                    .replace("%fecha%", fecha);
        }
        agregar(out, colores(cuando, fuerte, palido));

        // 5. Como se usa.
        out.add("");
        String regla;
        if (!f.ligada()) {
            regla = tx.apply("lore-regla-libre", "Se vuelve tuyo al colocarlo.");
        } else if (deClan) {
            regla = tx.apply("lore-regla-lider", "Solo el líder lo mueve.");
        } else {
            regla = tx.apply("lore-regla-dueno", "Solo su dueño lo mueve.");
        }
        agregar(out, colores(tx.apply("lore-uso", GRIS + "Colócalo y haz clic derecho para abrir su menú. %regla%"),
                fuerte, palido).replace("%regla%", regla));
        return out;
    }

    /**
     * La frase del tipo: frase-&lt;tipo&gt; de mensajes.yml o, si no hay, su descripcion del
     * config entera. Sin las comillas (las pone el lore) y sin colores.
     */
    String frase(TipoBaliza t) {
        String s = tx.apply("frase-" + t.id, null);
        if (s == null) s = String.join(" ", t.descripcion);
        s = Presentacion.plano(s).strip();
        while (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1).strip();
        return s;
    }

    private static String colores(String s, String fuerte, String palido) {
        return s.replace("%fuerte%", fuerte).replace("%acento%", fuerte).replace("%palido%", palido)
                .replace("%blanco%", BLANCO).replace("%gris%", GRIS)
                // %simbolo% era del lore v2: un mensajes.yml viejo que no se pudo apartar no lo deja suelto.
                .replace("%simbolo%", "✦");
    }

    /**
     * Una plantilla ya rellena, partida en lineas de ANCHO; vacia, no sale. Los espacios con
     * que empieza (la sangria de los efectos) se respetan en cada linea.
     */
    private static void agregar(List<String> out, String s) {
        if (s == null || Presentacion.largo(s) == 0) return;
        int sangria = Presentacion.plano(s).length() - Presentacion.plano(s).stripLeading().length();
        String pre = " ".repeat(sangria);
        for (String l : Presentacion.partir(s, Presentacion.ANCHO - sangria)) out.add(pre + l);
    }
}
