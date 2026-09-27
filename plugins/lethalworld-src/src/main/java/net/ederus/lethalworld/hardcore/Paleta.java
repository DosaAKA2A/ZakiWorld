package net.ederus.lethalworld.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

import java.time.Duration;

/**
 * Los colores de todo lo que Calamity le dice a un jugador: chat, titulos, barra de accion,
 * barras de jefe y nombres.
 *
 * Existe porque la primera sesion de pruebas (2026-09-27) dejo claro que el rojo de muerte
 * (#8B1A1A) y el gris medio no se leen sobre el fondo del chat: el prefijo "Calamity ·"
 * desaparecia y los avisos de la PARCA parecian texto apagado. Aqui todos los tonos estan
 * elegidos para leerse sobre el fondo negro semitransparente del chat y sobre el cielo en
 * los titulos. Un modulo que quiera un color nuevo lo anade aqui, no en su fichero: asi
 * Calamity se ve como una sola cosa y no como veinte paquetes con su gusto cada uno.
 *
 * Reglas de la casa que se aplican aqui para que nadie tenga que acordarse:
 *  - negrita solo en la marca (marca());
 *  - nada de cursiva (todo lo que sale de aqui la apaga, tambien para nombres de items);
 *  - los titulos con presencia llevan degradado, el resto un color plano.
 */
public final class Paleta {

    private Paleta() {
    }

    // ------------------------------------------------------------------ tonos

    /** Degradado de la marca "Calamity": ambar calido a coral. */
    public static final int MARCA_DESDE = 0xF2B866;
    public static final int MARCA_HASTA = 0xFF8A65;
    /** La marca en un solo color, para donde no cabe un degradado (lore, logs de color). */
    public static final TextColor MARCA = TextColor.color(0xF7A166);

    /** Texto normal de todos los mensajes: hueso claro, se lee sin cansar. */
    public static final TextColor TEXTO = TextColor.color(0xE8E2D6);
    /** Lo secundario (ayudas, "uso:", aclaraciones): mas apagado pero legible. */
    public static final TextColor TENUE = TextColor.color(0xB3AB9D);
    /** El punto que separa el prefijo del mensaje. */
    public static final TextColor SEPARADOR = TextColor.color(0x8C8478);
    /** Detalles que importan: nombres de jugadores, de sitios, de cosas. Verde palido de Calamity. */
    public static final TextColor DETALLE = TextColor.color(0x9FD6A0);
    /** Avisos, peligro, errores: rojo claro (el rojo de muerte oscuro no se leia). */
    public static final TextColor AVISO = TextColor.color(0xFF7A7A);
    /** Cifras: Esencias, MobCoins, segundos, porcentajes. */
    public static final TextColor CIFRA = TextColor.color(0xFFD27A);
    /** Lo que va bien (pagos, logros): el mismo verde de los detalles, un punto mas vivo. */
    public static final TextColor BIEN = TextColor.color(0x8EE39A);

    /** La PARCA: degradado de su nombre y de sus titulos (coral a rojo, claro para que se lea). */
    public static final int PARCA_DESDE = 0xFF9E80;
    public static final int PARCA_HASTA = 0xE8454F;
    /** La PARCA en un solo color (barra de accion, nombre de la guadana). */
    public static final TextColor PARCA = TextColor.color(0xF26A63);
    /** Titulos de ventana (inventarios): oscuros, porque van sobre el gris claro de la interfaz. */
    public static final int VENTANA_DESDE = 0x7A3E12;
    public static final int VENTANA_HASTA = 0x8E2A1F;
    /** Hueso de las planideras y de la cadena del Tiron. */
    public static final TextColor HUESO = TextColor.color(0xE3DCCE);
    /** El Eco: gris azulado claro (el de antes, #9AA7B8, se perdia en el chat). */
    public static final TextColor ECO = TextColor.color(0xB9C6D8);
    /** Almas: turquesa palido, el color de las particulas SOUL. */
    public static final TextColor ALMA = TextColor.color(0x8FE3DA);

    // --------------------------------------------------------------- piezas

    /** "Calamity" con el degradado de la marca, en negrita (la unica negrita permitida). */
    public static Component marca() {
        return degradado("Calamity", MARCA_DESDE, MARCA_HASTA).decoration(TextDecoration.BOLD, true);
    }

    /** "Calamity · " para los mensajes de sistema. */
    public static Component prefijo() {
        return Component.text().append(marca())
                .append(Component.text(" · ", SEPARADOR).decoration(TextDecoration.BOLD, false)) // sin la negrita de la marca
                .build().decoration(TextDecoration.ITALIC, false);
    }

    /** Un mensaje de sistema: prefijo y el texto en el color normal. */
    public static Component mensaje(String texto) {
        return prefijo().append(Component.text(texto, TEXTO));
    }

    /** Lo mismo con un cuerpo ya montado; lo que no tenga color sale en el normal. */
    public static Component mensaje(Component cuerpo) {
        return prefijo().append(cuerpo.colorIfAbsent(TEXTO));
    }

    /** Un aviso de sistema (algo ha ido mal o hay peligro): prefijo y el texto en rojo claro. */
    public static Component aviso(String texto) {
        return prefijo().append(Component.text(texto, AVISO));
    }

    public static Component texto(String t) {
        return Component.text(t, TEXTO);
    }

    public static Component tenue(String t) {
        return Component.text(t, TENUE);
    }

    public static Component detalle(String t) {
        return Component.text(t, DETALLE);
    }

    public static Component cifra(Object n) {
        return Component.text(String.valueOf(n), CIFRA);
    }

    public static Component rojo(String t) {
        return Component.text(t, AVISO);
    }

    /** Un nombre de item o de menu en ese color, sin cursiva (vanilla la pone en los renombrados). */
    public static Component nombre(String t, TextColor c) {
        return Component.text(t, c).decoration(TextDecoration.ITALIC, false);
    }

    // ------------------------------------------------------------ degradados

    /**
     * El texto con un degradado de color letra a letra (sin MiniMessage: un componente por
     * letra, que es lo mismo que genera MiniMessage y no depende de su parser). Sin cursiva.
     */
    public static Component degradado(String texto, int desde, int hasta) {
        if (texto == null || texto.isEmpty()) return Component.empty();
        int n = texto.codePointCount(0, texto.length());
        TextColor a = TextColor.color(desde), b = TextColor.color(hasta);
        TextComponent.Builder out = Component.text();
        int i = 0;
        for (int off = 0; off < texto.length(); ) {
            int cp = texto.codePointAt(off);
            float t = n <= 1 ? 0f : i / (float) (n - 1);
            out.append(Component.text(new String(Character.toChars(cp)), TextColor.lerp(t, a, b)));
            off += Character.charCount(cp);
            i++;
        }
        return out.build().decoration(TextDecoration.ITALIC, false);
    }

    /** Degradado de la marca sobre cualquier texto (titulos de Calamity, cabeceras de menu). */
    public static Component calido(String texto) {
        return degradado(texto, MARCA_DESDE, MARCA_HASTA);
    }

    /**
     * Titulo de una ventana (inventario). Ahi el texto se dibuja sobre el gris claro de la
     * interfaz y sin sombra: los tonos claros de arriba no se leen. Degradado oscuro calido,
     * del mismo aire que la marca.
     */
    public static Component ventana(String texto) {
        return degradado(texto, VENTANA_DESDE, VENTANA_HASTA);
    }

    /** "Calamity · seccion" como titulo de ventana: la marca en negrita, todo en el degradado oscuro. */
    public static Component ventanaCalamity(String seccion) {
        return Component.text()
                .append(ventana("Calamity").decoration(TextDecoration.BOLD, true))
                .append(Component.text(" · ", TextColor.color(VENTANA_HASTA)).decoration(TextDecoration.BOLD, false)) // sin la negrita de la marca
                .append(ventana(seccion))
                .build().decoration(TextDecoration.ITALIC, false);
    }

    /** Degradado de la PARCA (su nombre, sus titulos, la barra de jefe). */
    public static Component muerte(String texto) {
        return degradado(texto, PARCA_DESDE, PARCA_HASTA);
    }

    // --------------------------------------------------------------- titulos

    /** Un titulo con presencia: la linea grande con degradado, la pequena en el color normal. */
    public static Title titulo(Component grande, String pequena, Duration entra, Duration queda, Duration sale) {
        return Title.title(grande, pequena == null ? Component.empty() : Component.text(pequena, TEXTO),
                Title.Times.times(entra, queda, sale));
    }

    /** Titulo de Calamity (degradado calido) con tiempos normales: 0,3 s, 3 s, 0,8 s. */
    public static Title titulo(String grande, String pequena) {
        return titulo(calido(grande), pequena, Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800));
    }
}
