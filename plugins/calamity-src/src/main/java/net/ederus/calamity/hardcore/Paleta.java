package net.ederus.calamity.hardcore;

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
    /** Calamity 1.10 · Las lineas finas que enmarcan el lore de un pergamino de contrato: gris oscuro. */
    public static final TextColor FILETE = TextColor.color(0x555555);
    /** Las casillas vacias de una barra de progreso: el gris de las de la barra de cordura. */
    public static final TextColor CASILLA_VACIA = TextColor.color(0x3A3A3A);

    /** La PARCA: degradado de su nombre y de sus titulos (coral a rojo, claro para que se lea). */
    public static final int PARCA_DESDE = 0xFF9E80;
    public static final int PARCA_HASTA = 0xE8454F;
    /** La PARCA en un solo color (barra de accion, nombre de la guadana). */
    public static final TextColor PARCA = TextColor.color(0xF26A63);
    /**
     * Calamity 1.8.0 · Ambush: de gris acero a rojo claro (su nombre, la Sentencia y su barra). El
     * rojo es el de la Crimson Masamune (#FF6B6B), que es lo que acaba saliendo de el.
     */
    public static final int AMBUSH_DESDE = 0xDDE3EA;
    public static final int AMBUSH_HASTA = 0xFF6B6B;
    /** Ambush en un solo color (el menu de la Sentencia, la barra de accion). */
    public static final TextColor AMBUSH = TextColor.color(0xFF6B6B);
    /** El gris acero de la Masamune (el nombre de la katana que lleva Ambush). */
    public static final TextColor ACERO = TextColor.color(0xC9D1D9);
    /**
     * El degradado gris acero del nombre de la Masamune en MMOItems (de #E6EBF0 hacia #7B8894), que
     * llevan tambien sus Fragmentos. Acaba un poco mas claro para que se lea sobre el fondo oscuro.
     */
    public static final int ACERO_DESDE = 0xE6EBF0;
    public static final int ACERO_HASTA = 0x8A96A1;
    /**
     * Calamity 1.8.4 · Los cinco minijefes: el nombre va del rojo claro de los avisos (#FF7A7A) al
     * rojo de Ambush (#E63946), el mas hondo que se sigue leyendo sobre el fondo oscuro del cartel y
     * del chat. Nada de #8B1A1A ni parecidos: no se leen.
     */
    public static final int MINIJEFE_DESDE = 0xFF7A7A;
    public static final int MINIJEFE_HASTA = 0xE63946;
    /*
     * Calamity 1.9.0 · Los mobs especiales de Lethal World (Apariciones): el color con el que nace
     * su ficha en /esb, que es el de su nombre en el cartel. Tonos claros, cada uno con el color del
     * bicho, para que se lean sobre el mundo (el del ghast, tambien contra el cielo del organismo).
     */
    /** Crujidor Palido (creaking): el blanco hueso del roble palido. */
    public static final int CRUJIDOR = 0xE3DCCB;
    /** Ghast Carmesi: coral carmesi, lo bastante claro para leerse contra un cielo rojo. */
    public static final int GHAST_CARMESI = 0xFF7468;
    /** Guardian Anciano: verde prismarina. */
    public static final int GUARDIAN_ANCIANO = 0x7FD1C0;
    /** Un especial que el staff anada en la config con otra entidad: el ambar de las cifras. */
    public static final int ESPECIAL = 0xFFD27A;
    /** La carpeta "Lethal World · Especiales" de /esb: lavanda claro. */
    public static final int CARPETA_ESPECIALES = 0xC7B8E8;
    /*
     * Titulos de ventana (inventarios): "CALAMITY | Seccion". Colores planos y oscuros, porque van
     * sobre el gris claro (~#C6C6C6) de la interfaz y sin sombra: el degradado marron rojizo de
     * antes se leia mal. La marca en rojo intenso, la barra en gris oscuro y la seccion en carbon.
     */
    public static final TextColor VENTANA_MARCA = TextColor.color(0xB3261E);
    public static final TextColor VENTANA_BARRA = TextColor.color(0x555555);
    public static final TextColor VENTANA_SECCION = TextColor.color(0x3A3A3A);
    /** Hueso de las planideras y de la cadena del Tiron. */
    public static final TextColor HUESO = TextColor.color(0xE3DCCE);
    /** El Eco: gris azulado claro (el de antes, #9AA7B8, se perdia en el chat). */
    public static final TextColor ECO = TextColor.color(0xB9C6D8);
    /** Almas: turquesa palido, el color de las particulas SOUL. */
    public static final TextColor ALMA = TextColor.color(0x8FE3DA);
    /**
     * Calamity 1.9.0 · La lluvia acida de los biomas verdes: verde lima claro, para el aviso de la
     * barra y las gotas que le caen alrededor. Mas amarillento que DETALLE para que no se confunda
     * con un aviso bueno.
     */
    public static final TextColor ACIDO = TextColor.color(0xB6E35A);
    /** Calamity 1.9.0 · El cielo que arde en el bioma rojo: naranja fuego claro (su aviso). */
    public static final TextColor FUEGO = TextColor.color(0xFFA15C);
    /**
     * Calamity 1.9.0 · La ceniza del cielo rojo (particulas): el color del cielo de crimson_organism
     * en su propio bioma (#DA5955), que se ve de dia y de noche sin llegar al rojo oscuro ilegible.
     */
    public static final int CIELO_ROJO = 0xDA5955;
    /** Calamity 1.12 · Las esporas: verde salvia apagado (el de la niebla de esporas). */
    public static final TextColor ESPORAS = TextColor.color(0xA9C79A);
    /** Calamity 1.12 · La polinizacion: amarillo miel claro. */
    public static final TextColor POLEN = TextColor.color(0xF2D36B);
    /** Calamity 1.12 · La ceniza: gris calido claro, el de la ceniza que cae. */
    public static final TextColor CENIZA = TextColor.color(0xD3CBBE);
    /** Calamity 1.12 · El temporal de los biomas sin clima propio: gris pizarra claro, el de las nubes de tormenta. */
    public static final TextColor TEMPORAL = TextColor.color(0xAEB8C4);

    // ------------------------------------------------------- tonos de los objetos

    /*
     * Rama lore-items · Cada familia de objeto tiene SU color. Dosa vio los lores de la 1.10 y le
     * parecieron "deprimentes, sin armonia": todo en el mismo gris y ambar apagado, y cualquier objeto
     * parecia de categoria baja. Ahora el nombre lleva el degradado de su tono (claro -> fuerte, sin
     * negrita) y el lore usa ese mismo tono para la categoria, los titulos de seccion y los nombres
     * destacados; lo demas es blanco (cifras), gris (lo secundario) y el dorado de las estrellas. Un solo
     * color por objeto: nada de arcoiris. Los tonos se pueden cambiar en hardcore.lores.tonos (Ficha.tono).
     */

    /** El color de una familia de objetos: el degradado de su nombre, de "desde" (claro) a "hasta" (fuerte). */
    public record Tono(int desde, int hasta) {

        /** La categoria, los titulos de seccion (◆) y lo destacado: el final del degradado. */
        public TextColor fuerte() {
            return TextColor.color(hasta);
        }

        /** La historia y los nombres de monedas y objetos dentro del texto: el inicio, un 35 % hacia blanco. */
        public TextColor palido() {
            return TextColor.lerp(0.35f, TextColor.color(desde), TextColor.color(0xFFFFFF));
        }

        /** El nombre del objeto con el degradado, sin cursiva ni negrita. */
        public Component nombre(String texto) {
            return degradado(texto, desde, hasta).decoration(TextDecoration.BOLD, false);
        }
    }

    /** Pergaminos de contrato de Oren: dorado a naranja. */
    public static final Tono T_CONTRATO = new Tono(0xFFE27A, 0xFF8A2B);
    /** Reliquias por grado: I turquesa, II azul, III violeta, IV ambar. */
    public static final Tono T_GRADO_I = new Tono(0xB8FFF4, 0x2FD3C8);
    public static final Tono T_GRADO_II = new Tono(0xBFD9FF, 0x4A8DFF);
    public static final Tono T_GRADO_III = new Tono(0xE3C8FF, 0x9B5CFF);
    public static final Tono T_GRADO_IV = new Tono(0xFFE3A8, 0xFFAA2B);
    /** Campana de la Parca: carmesi. */
    public static final Tono T_CAMPANA = new Tono(0xFF9DB4, 0xFF3D6E);
    /** Lagrima de Eco: celeste. */
    public static final Tono T_LAGRIMA = new Tono(0xD6F6FF, 0x5CC8FF);
    /** Sello de minijefe: el degradado de los minijefes (su nombre en el cartel). */
    public static final Tono T_SELLO = new Tono(MINIJEFE_DESDE, MINIJEFE_HASTA);
    /** Reliquia Eclipsada: el borde del Eclipse, de rosa palido a magenta. */
    public static final Tono T_ECLIPSADA = new Tono(0xFFC2F0, 0xE03CC4);
    /** Esencia de Calamidad: morado. */
    public static final Tono T_ESENCIA = new Tono(0xE7C8FF, 0xB266FF);
    /** Frasco de Calma: verde de manantial (la cordura). */
    public static final Tono T_FRASCO = new Tono(0xCFFFE0, 0x3DDC84);
    /** Cristal de Regreso: indigo lavanda, el tono de la puerta. */
    public static final Tono T_CRISTAL = new Tono(0xDCDFFF, 0x7C83FF);
    /** Reclamo: coral, pariente del rojo de los minijefes a los que llama. */
    public static final Tono T_RECLAMO = new Tono(0xFFD3C4, 0xFF6B47);
    /** Talisman de Vigilia: lima, la mente despierta. */
    public static final Tono T_TALISMAN = new Tono(0xF0FFC4, 0xA8DC3A);
    /** Grabado de Calamidad: laton, las runas de la Forja. */
    public static final Tono T_GRABADO = new Tono(0xFFF0C2, 0xE0B23A);
    /** Salvoconducto del Insomne: rosa lacre, el de la firma. */
    public static final Tono T_SALVOCONDUCTO = new Tono(0xFFD6E2, 0xF06A93);
    /** Fragmento de Masamune: el acero de la katana (ACERO_DESDE a ACERO_HASTA, un punto mas vivo). */
    public static final Tono T_MASAMUNE = new Tono(0xEEF3F8, 0x8FA6BA);
    /** Lo prestado del Kit de Expedicion: arena, la lona de la expedicion. */
    public static final Tono T_KIT = new Tono(0xFFEBC7, 0xE6A955);
    /** Cabeza de un Eco derrotado: el gris azulado del Eco, mas vivo. */
    public static final Tono T_TROFEO = new Tono(0xE3EBFA, 0x8EA6D6);
    /** Llave del Umbral: azul hielo, el color de la Boveda de Ruinas (#9FC9D6). */
    public static final Tono T_LLAVE_UMBRAL = new Tono(0xE2F4FA, 0x7FB8CC);
    /** Llave Ominosa: lila, el color de la Boveda Caida (#C7A6E8). */
    public static final Tono T_LLAVE_OMINOSA = new Tono(0xEEDFFF, 0xA97FDD);

    /** Los tonos por su nombre en hardcore.lores.tonos (Ficha.tono). */
    public static final java.util.Map<String, Tono> TONOS = java.util.Map.ofEntries(
            java.util.Map.entry("contrato", T_CONTRATO), java.util.Map.entry("grado-1", T_GRADO_I),
            java.util.Map.entry("grado-2", T_GRADO_II), java.util.Map.entry("grado-3", T_GRADO_III),
            java.util.Map.entry("grado-4", T_GRADO_IV), java.util.Map.entry("campana", T_CAMPANA),
            java.util.Map.entry("lagrima", T_LAGRIMA), java.util.Map.entry("sello", T_SELLO),
            java.util.Map.entry("eclipsada", T_ECLIPSADA), java.util.Map.entry("esencia", T_ESENCIA),
            java.util.Map.entry("frasco", T_FRASCO), java.util.Map.entry("cristal", T_CRISTAL),
            java.util.Map.entry("reclamo", T_RECLAMO), java.util.Map.entry("talisman", T_TALISMAN),
            java.util.Map.entry("grabado", T_GRABADO), java.util.Map.entry("salvoconducto", T_SALVOCONDUCTO),
            java.util.Map.entry("masamune", T_MASAMUNE), java.util.Map.entry("kit", T_KIT),
            java.util.Map.entry("trofeo", T_TROFEO), java.util.Map.entry("llave-umbral", T_LLAVE_UMBRAL),
            java.util.Map.entry("llave-ominosa", T_LLAVE_OMINOSA));

    /** Las cifras de los lores: blanco. */
    public static final TextColor LORE_BLANCO = TextColor.color(0xF4F4F4);
    /** Lo secundario de los lores y las notas del final: gris. */
    public static final TextColor LORE_GRIS = TextColor.color(0x7A7A7A);
    /** Las estrellas de rareza conseguidas: dorado. Las que faltan van en LORE_GRIS. */
    public static final TextColor ESTRELLA = TextColor.color(0xFFD54A);

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
     * Titulo de una ventana (inventario): "CALAMITY" en negrita roja, " | " en gris oscuro y la
     * seccion en gris carbon, sin negrita. Todos los menus de Calamity lo abren asi (Marco.Titulo).
     */
    public static Component ventana(String seccion) {
        return Component.text()
                .append(Component.text("CALAMITY", VENTANA_MARCA).decoration(TextDecoration.BOLD, true))
                .append(Component.text(" | ", VENTANA_BARRA).decoration(TextDecoration.BOLD, false))
                .append(Component.text(seccion, VENTANA_SECCION).decoration(TextDecoration.BOLD, false))
                .build().decoration(TextDecoration.ITALIC, false);
    }

    /** Degradado de la PARCA (su nombre, sus titulos, la barra de jefe). */
    public static Component muerte(String texto) {
        return degradado(texto, PARCA_DESDE, PARCA_HASTA);
    }

    /** Calamity 1.8.0 · Degradado de Ambush (su nombre, el titulo de la Sentencia, su barra de jefe). */
    public static Component ambush(String texto) {
        return degradado(texto, AMBUSH_DESDE, AMBUSH_HASTA);
    }

    /**
     * Calamity 1.8.4 · El nombre de un minijefe (Custodio, Matriarca, Heraldo, Sanador, Centinela):
     * "☠ Custodio de las Ruinas", con la calavera en hueso y el nombre en el degradado de
     * MINIJEFE_DESDE a MINIJEFE_HASTA. Es el mismo en su cartel (CartelesMinijefe) y en el aviso
     * "Ha venido por ti".
     *
     * Sin negrita, y apagada a proposito en cada trozo: la ficha de /esb de los cinco la traia
     * puesta y a Dosa no le gustaba (2026-09-29). Asi tampoco la hereda si se engancha a un texto
     * que si la lleve.
     *
     * Con nivel > 0 le sigue " · Nv. 45" en tenue (el aviso del chat). El cartel lo pide con 0,
     * porque el nivel ya va en su segunda linea.
     *
     * Calamity 1.11 · Una sola calavera, delante: "☠ Custodio de las Ruinas" (Dosa, 2026-10-04). Sigue
     * sin negrita en ningun trozo: si se ve en negrita es la ficha de /esb (negrita: true) en algun sitio
     * que no pasa por aqui.
     */
    public static Component minijefe(String nombre, int nivel) {
        String n = nombre == null || nombre.isBlank() ? "Minijefe" : nombre;
        TextComponent.Builder out = Component.text()
                .append(Component.text("☠ ", HUESO).decoration(TextDecoration.BOLD, false))
                .append(degradado(n, MINIJEFE_DESDE, MINIJEFE_HASTA).decoration(TextDecoration.BOLD, false));
        if (nivel > 0) {
            out.append(Component.text(" · ", SEPARADOR)).append(Component.text("Nv. " + nivel, TENUE));
        }
        return out.build().decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false);
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
