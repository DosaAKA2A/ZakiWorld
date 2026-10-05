package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.menu.MenuUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Las piezas de los menus de Calamity: el Altar (portada, categorias y Forja), el Tasador, los
 * rankings del Cazador, el Tablero y Tu camino se ven como una sola cosa.
 *
 * Por que existe: la primera version del Altar era un cofre gris con iconos sueltos y Dosa lo
 * dijo claro, "ni siquiera se entiende como va". La 1.3.0 lo ordeno por filas con un rotulo a la
 * izquierda, y tampoco: "no se de que va con tantas cosas", "las categorias de la izquierda no
 * se entienden", y los titulos se salian de la ventana. La 1.3.1 copia la forma de la tienda de
 * EDM (MenuTienda). Las reglas que se aplican aqui, para que cada menu no tenga que acordarse:
 *  - pocas cosas por pantalla y cada una evidente: una portada con pocas categorias grandes y,
 *    al pulsar, la pagina de esa categoria con sus articulos centrados y con aire (rejilla);
 *  - el marco es de cristal negro, todo igual (1.7.3: Dosa quiere paneles negros y se molesto
 *    cuando se pusieron de colores), y los cristales sin globo (no se abre un recuadro vacio al
 *    pasar por encima);
 *  - lo vacio es marco: no quedan huecos que parezcan casillas por rellenar;
 *  - si una pagina tiene grupos (la Forja, el Camino), cada fila lleva su banda: el mismo cristal
 *    negro a cada lado, en el sitio del marco, que al pasar por encima dice el nombre del grupo.
 *    Nada de rotulos-icono en la columna 0: parecian un articulo mas;
 *  - arriba en el centro la informacion (tu saldo, la ayuda o la cabecera); abajo en el centro
 *    Cerrar si el menu es el primero que se abre, o Volver si se llega desde otro (como el
 *    Mercado); a sus lados, los enlaces a otros menus; las flechas de pagina en las esquinas;
 *  - el coste se lee linea a linea: ✔ verde lo que tienes, ✘ rojo lo que te falta y cuanto;
 *  - la ultima linea de un boton dice siempre que hace el clic, o por que no se puede;
 *  - solo clic izquierdo y nada que haya que descubrir pasando el raton: lo que se puede
 *    comprar ya brilla, las cantidades van en el numero de la pila y las barras son de
 *    cristales de colores (Bedrock no tiene raton y, al tocar, ya hace clic);
 *  - el titulo de la ventana cabe en el ancho de un cofre (Titulo, ANCHO_TITULO; el autotest
 *    "menus" los mide todos).
 *
 * Colores de Paleta y nada de cursiva ni negrita (salvo "CALAMITY" en el titulo de la ventana).
 */
final class Marco {

    private Marco() {
    }

    /** Filas y columnas de contenido de un menu de 54: filas 1-4, columnas 1-7 (la 0 y la 8 son marco). */
    static final int FILAS = 4;
    static final int COLUMNAS = 7;

    // La fila de arriba: la informacion (el saldo en el Altar) en el centro.
    static final int SALDO = 4;
    // La fila de abajo (54): flechas de pagina en las esquinas y Volver en el centro, como en la tienda.
    static final int ANTERIOR = 45, VOLVER = 49, SIGUIENTE = 53;

    /**
     * La casilla de abajo en el centro de una ventana de ese tamano (27 -> 22, 36 -> 31, 45 -> 40,
     * 54 -> 49): la de Cerrar o Volver en todos los menus de Calamity.
     */
    static int abajo(int tamano) {
        return tamano - 5;
    }

    static final String UMBRAL = "umbral", FORJA = "forja", TASADOR = "tasador", CAMINO = "camino";

    /**
     * Que columnas (1-7) ocupan n cosas en una fila: centradas y, si caben, con aire entre ellas.
     * Siempre simetricas: con 4 van a 1, 3, 5 y 7; con 6 queda libre solo la del centro.
     */
    private static final int[][] COLUMNAS_DE = {
            {},
            {4},
            {3, 5},
            {2, 4, 6},
            {1, 3, 5, 7},
            {2, 3, 4, 5, 6},
            {1, 2, 3, 5, 6, 7},
            {1, 2, 3, 4, 5, 6, 7}};

    /** Verde de lo que se puede (✔, "Clic para..."). */
    static final TextColor SI = Paleta.BIEN;
    /** Rojo claro de lo que falta (✘ y el por que no). */
    static final TextColor NO = Paleta.AVISO;

    // ------------------------------------------------------------------ titulos

    /**
     * Lo que cabe en el titulo de un cofre: la ventana mide 176, el texto empieza en el 8 y a
     * partir de ~160 pisa el borde. 150 deja margen (Bedrock dibuja la fuente algo mas ancha).
     */
    static final int ANCHO_TITULO = 150;

    /** Lo que va delante de cada titulo de ventana (Paleta.ventana): la marca y la barra. */
    static final String PREFIJO_TITULO = "CALAMITY | ";

    /**
     * Un titulo de ventana: "CALAMITY | seccion" (Paleta.ventana). La seccion es corta y sin
     * articulos: con la marca delante le quedan unos 86 px, unas catorce letras.
     */
    record Titulo(String seccion) {

        Component componente() {
            return Paleta.ventana(seccion);
        }

        /** Lo que ocupa en pixeles (la negrita de la marca suma uno por letra). */
        int ancho() {
            return Marco.ancho("CALAMITY", true) + Marco.ancho(" | ", false) + Marco.ancho(seccion, false);
        }

        /** El titulo en texto plano, tal cual se ve. */
        String texto() {
            return PREFIJO_TITULO + seccion;
        }
    }

    static final Titulo T_ALTAR = new Titulo("Altar del Umbral");
    static final Titulo T_FORJA = new Titulo("Forja");
    static final Titulo T_COMPRAR = new Titulo("¿Comprarlo?");
    static final Titulo T_FORJAR = new Titulo("¿Forjarlo?");
    static final Titulo T_TASADOR = new Titulo("Mercado");
    static final Titulo T_TASADOR_DINERO = new Titulo("Tu dinero");
    static final Titulo T_TASADOR_CONTRATOS = new Titulo("Contratos");
    /** Rama venta-oren: la confirmacion de "Vender todo" en la tienda de Oren. */
    static final Titulo T_VENDER_TODO = new Titulo("Vender todo");
    // "¿Cambiar contrato?" no cabe con la marca delante.
    static final Titulo T_CAMBIAR = new Titulo("Cambiar contrato");
    // "Rankings de la semana" no cabe.
    static final Titulo T_RANKINGS = new Titulo("Ranking semanal");
    // El mismo menu mirando el total: "CALAMITY | Ranking histórico" mide 149 de 150.
    static final Titulo T_RANKINGS_HISTORICO = new Titulo("Ranking histórico");
    static final Titulo T_TABLERO = new Titulo("Tablero");
    static final Titulo T_CAMINO = new Titulo("Tu camino");
    static final Titulo T_GRABAR = new Titulo("Grabar");
    static final Titulo T_DESEOS = new Titulo("Lista de deseos");
    static final Titulo T_VOTO = new Titulo("Voto del Botín");
    static final Titulo T_PREGUNTA = new Titulo("Encuesta");
    static final Titulo T_DIFICULTAD = new Titulo("Dificultad");
    // "¿Qué conservas?" no cabe: el menu es el del Salvoconducto y asi se llama el objeto.
    static final Titulo T_SALVOCONDUCTO = new Titulo("Salvoconducto");
    static final Titulo T_ENGARZADOR = new Titulo("Engarce");
    /** 1.8.0: el menu de los contratos de Ambush ("CALAMITY | Sentencia"). */
    static final Titulo T_SENTENCIA = new Titulo("Sentencia");

    /** El de una categoria del Altar: "CALAMITY | Expedición". */
    static Titulo categoria(String seccion) {
        return new Titulo(seccion);
    }

    /** Todos los titulos de ventana de Calamity, para que el autotest mida que caben. */
    static List<Titulo> titulos() {
        List<Titulo> out = new ArrayList<>(List.of(T_ALTAR, T_FORJA, T_COMPRAR, T_FORJAR, T_TASADOR, T_TASADOR_DINERO,
                T_TASADOR_CONTRATOS, T_VENDER_TODO, T_CAMBIAR, T_RANKINGS, T_RANKINGS_HISTORICO,
                T_TABLERO, T_CAMINO, T_GRABAR, T_DESEOS, T_VOTO, T_PREGUNTA, T_DIFICULTAD, T_SALVOCONDUCTO, T_ENGARZADOR,
                T_SENTENCIA));
        for (MenuAltar.Categoria c : MenuAltar.CATEGORIAS) {
            Titulo t = MenuAltar.titulo(c.id());
            if (!out.contains(t)) out.add(t);
        }
        return out;
    }

    /**
     * Ancho en pixeles de un texto con la fuente de Minecraft (la de la interfaz, sin escalar):
     * la letra normal 6 (5 y el hueco), el espacio 4, las estrechas menos. La negrita suma 1 a
     * cada letra. Lo que no es latino sale de otra fuente, mas ancha: se cuenta 9 por si acaso.
     */
    static int ancho(String texto, boolean negrita) {
        if (texto == null) return 0;
        int px = 0;
        for (int i = 0; i < texto.length(); ) {
            int c = texto.codePointAt(i);
            i += Character.charCount(c);
            px += anchoLetra(c) + (negrita ? 1 : 0);
        }
        return px;
    }

    private static int anchoLetra(int c) {
        if (c == ' ') return 4;
        if ("!',.:;|i¡·".indexOf(c) >= 0) return 2;
        if ("`lí".indexOf(c) >= 0) return 3;
        if ("\"()*I[]t{}ìîï".indexOf(c) >= 0) return 4;
        if ("<>fkªº".indexOf(c) >= 0) return 5;
        if ("@~".indexOf(c) >= 0) return 7;
        return c < 0x250 ? 6 : 9;
    }

    // ------------------------------------------------------------------ marco

    /** Un cristal del marco, sin nombre ni globo. */
    static ItemStack cristal(Material m) {
        ItemStack it = MenuUtil.pane(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setHideTooltip(true);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** Si la casilla es del anillo de fuera (primera y ultima fila, primera y ultima columna). */
    static boolean esBorde(int casilla, int tamano) {
        int fila = casilla / 9, col = casilla % 9;
        return fila == 0 || fila == tamano / 9 - 1 || col == 0 || col == 8;
    }

    /**
     * Rellena lo que haya quedado vacio con cristal negro sin nombre, igual en toda la ventana.
     * Todos los menus de Calamity (1.7.3): Dosa quiere los menus con paneles negros y no de
     * colores. El anillo rojo del Altar y el gris del Mercado (1.5.0, porque con el rojo Dosa no
     * distinguia el marco de los botones) se quedan en esto, que ya estrenaba el ranking de Rhen
     * (1.7.2): un marco de un solo color no se confunde con nada.
     */
    static void rellenar(Inventory inv) {
        ItemStack negro = cristal(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, negro);
    }

    // ------------------------------------------------------------------ reparto

    /**
     * Donde cae cada cosa de una pagina. indice -1 es la banda de la seccion (en la columna 0 de
     * cada fila suya; la de la 8 la pone ponerBanda); el resto, la cosa n.o indice de la seccion.
     */
    record Sitio(int hoja, int casilla, int seccion, int indice) {
    }

    /** Las columnas (1-7) de una fila con n cosas. */
    static int[] columnas(int n) {
        return COLUMNAS_DE[Math.max(0, Math.min(COLUMNAS, n))];
    }

    /**
     * Reparte secciones de tamanos dados en hojas de FILAS filas, una banda por fila. Una seccion
     * ocupa las filas que pida (de 7 en 7); si no cabe entera en lo que queda de hoja y en una
     * hoja nueva si, empieza en la siguiente (no se parte sin necesidad). Una seccion mas larga
     * que una hoja entera se parte y sigue arriba en la hoja nueva, con su banda. Las secciones
     * vacias no salen.
     */
    static List<Sitio> repartir(List<Integer> tamanos) {
        List<Sitio> out = new ArrayList<>();
        int hoja = 0, fila = 0;
        for (int s = 0; s < tamanos.size(); s++) {
            int n = Math.max(0, tamanos.get(s));
            if (n == 0) continue;
            int filas = (n + COLUMNAS - 1) / COLUMNAS;
            if (fila > 0 && filas <= FILAS && fila + filas > FILAS) {
                hoja++;
                fila = 0;
            }
            int i = 0;
            for (int f = 0; f < filas; f++) {
                if (fila >= FILAS) {
                    hoja++;
                    fila = 0;
                }
                int base = (fila + 1) * 9;
                out.add(new Sitio(hoja, base, s, -1));
                int enFila = Math.min(COLUMNAS, n - i);
                for (int c : columnas(enFila)) out.add(new Sitio(hoja, base + c, s, i++));
                fila++;
            }
        }
        return out;
    }

    /**
     * Donde van n articulos de una categoria, como en la tienda de EDM: centrados en las filas
     * de contenido y con aire. Hasta 16, filas de 4 como mucho (una columna libre entre cada
     * dos), repartidas por igual (7 = 4 + 3) y centradas en vertical; con mas, filas de 7 y 28
     * por hoja, la ultima fila centrada. Sin bandas: seccion 0 y el indice del articulo.
     */
    static List<Sitio> rejilla(int n) {
        List<Sitio> out = new ArrayList<>();
        if (n <= 0) return out;
        if (n <= FILAS * 4) {
            int filas = (n + 3) / 4;
            int arriba = 1 + (FILAS - filas) / 2;
            int i = 0;
            for (int f = 0; f < filas; f++) {
                int enFila = n / filas + (f < n % filas ? 1 : 0);
                for (int c : columnas(enFila)) out.add(new Sitio(0, (arriba + f) * 9 + c, 0, i++));
            }
            return out;
        }
        int porHoja = FILAS * COLUMNAS, i = 0;
        for (int hoja = 0; i < n; hoja++) {
            int enHoja = Math.min(porHoja, n - i);
            int filas = (enHoja + COLUMNAS - 1) / COLUMNAS;
            int arriba = 1 + (FILAS - filas) / 2;
            for (int f = 0; f < filas; f++) {
                int enFila = Math.min(COLUMNAS, enHoja - f * COLUMNAS);
                for (int c : columnas(enFila)) out.add(new Sitio(hoja, (arriba + f) * 9 + c, 0, i++));
            }
        }
        return out;
    }

    /** Cuantas hojas salen de un reparto (al menos una). */
    static int hojas(List<Sitio> sitios) {
        int max = 0;
        for (Sitio s : sitios) max = Math.max(max, s.hoja());
        return max + 1;
    }

    // ------------------------------------------------------------------ piezas

    /**
     * Un icono: el objeto base (su aspecto real, de MMOItems si lo es), el nombre y el lore
     * nuestros sin cursiva, sin las lineas de atributos ni encantamientos, y el brillo que se
     * diga (el brillo es "esto se puede ya": se quita el que traiga el objeto de serie).
     */
    static ItemStack icono(ItemStack base, Component nombre, List<Component> lore, boolean brillo) {
        ItemStack it = base.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        meta.displayName(nombre.decoration(TextDecoration.ITALIC, false));
        List<Component> limpio = new ArrayList<>(lore.size());
        for (Component c : lore) limpio.add(c.decoration(TextDecoration.ITALIC, false));
        meta.lore(limpio);
        meta.setEnchantmentGlintOverride(brillo);
        MenuUtil.hideAll(meta);
        it.setItemMeta(meta);
        return it;
    }

    static ItemStack icono(Material m, Component nombre, List<Component> lore, boolean brillo) {
        return icono(new ItemStack(m), nombre, lore, brillo);
    }

    /**
     * La banda de una seccion: el mismo cristal negro del marco, pero con el nombre del grupo en
     * ambar y dos lineas de que es al pasar por encima. Va a los dos lados de su fila, en el sitio
     * del marco (ponerBanda). El color que pida cada menu se ignora a proposito: Dosa quiere el
     * marco negro (1.7.3) y ninguna fila tiene que volver a salir de colores.
     */
    static ItemStack banda(Material cristal, String nombre, List<String> texto) {
        List<Component> lore = new ArrayList<>();
        for (String l : texto) lore.add(Component.text(l, Paleta.TENUE));
        return icono(Material.BLACK_STAINED_GLASS_PANE, Component.text(nombre, Paleta.MARCA), lore, false);
    }

    /** La banda en la columna 0 y la 8 de la fila que empieza en base. */
    static void ponerBanda(Inventory inv, int base, ItemStack banda) {
        inv.setItem(base, banda);
        inv.setItem(base + 8, banda);
    }

    /** Un boton sencillo: nombre, lineas y la accion (o el por que no) al final. */
    static ItemStack boton(Material m, String nombre, List<String> texto, String accion, boolean activo) {
        List<Component> lore = new ArrayList<>();
        for (String l : texto) lore.add(Component.text(l, Paleta.TEXTO));
        if (!texto.isEmpty()) lore.add(Component.empty());
        lore.add(activo ? accion(accion) : Component.text(accion, Paleta.TENUE));
        return icono(m, Component.text(nombre, activo ? Paleta.DETALLE : Paleta.TENUE), lore, false);
    }

    /** Cerrar: la barrera, abajo en el centro (abajo(tamano)) de los menus que se abren primero. */
    static ItemStack cerrar() {
        return icono(Material.BARRIER, Component.text("Cerrar", NO), List.of(accion("Clic para cerrar")), false);
    }

    /**
     * Flecha de pagina, en las esquinas de abajo. Es un papel (una pagina) y no una flecha para
     * que no se confunda con Volver. hacia: -1 anterior, +1 siguiente; hoja y total para el "2 de 3".
     */
    static ItemStack flecha(int hacia, int hoja, int total) {
        String n = hacia < 0 ? "◀ Página anterior" : "Página siguiente ▶";
        return icono(Material.PAPER, Component.text(n, Paleta.DETALLE),
                List.of(tenue("Estás en la página " + (hoja + 1) + " de " + total + "."), Component.empty(),
                        accion("Clic para pasar de página")), false);
    }

    /**
     * Volver al menu del que se viene, abajo en el centro (donde otros menus tienen Cerrar). Una
     * flecha y el sitio: "◀ Volver al Altar", "◀ Volver al Mercado", "◀ Volver a la Forja".
     */
    static ItemStack volver(String adonde) {
        return icono(Material.ARROW, Component.text("◀ Volver " + adonde, Paleta.DETALLE),
                List.of(accion("Clic para volver")), false);
    }

    /** La cabeza de un jugador. Conectado, con su perfil (lleva la skin); si no, por su UUID. */
    static ItemStack cabeza(UUID u) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        if (u == null || !(it.getItemMeta() instanceof SkullMeta meta)) return it;
        try {
            Player p = Bukkit.getPlayer(u);
            if (p != null) meta.setPlayerProfile(p.getPlayerProfile());
            else {
                OfflinePlayer o = Bukkit.getOfflinePlayer(u);
                meta.setOwningPlayer(o);
            }
            it.setItemMeta(meta);
        } catch (Throwable ignorado) {
            // Sin perfil sale la cabeza de Steve: mejor eso que un menu que no abre.
        }
        return it;
    }

    // ------------------------------------------------------------------ textos

    static Component texto(String t) {
        return Component.text(t, Paleta.TEXTO);
    }

    static Component tenue(String t) {
        return Component.text(t, Paleta.TENUE);
    }

    /** "Etiqueta: valor", la etiqueta apagada y el valor en el color de las cifras. */
    static Component dato(String etiqueta, String valor) {
        return Component.text(etiqueta + ": ", Paleta.TENUE).append(Component.text(valor, Paleta.CIFRA));
    }

    /** "▸ Clic para ..." en verde: lo que hace el clic. */
    static Component accion(String t) {
        return Component.text("▸ ", Paleta.SEPARADOR).append(Component.text(t, SI));
    }

    /** Por que no se puede, en una linea roja. */
    static Component porQueNo(String t) {
        return Component.text("✘ " + t, NO);
    }

    /** Una linea de coste que se tiene: "✔ 48 Esencias". */
    static Component tiene(String que) {
        return Component.text("✔ ", SI).append(Component.text(que, Paleta.TEXTO));
    }

    /** Una linea de coste que falta: "✘ 48 Esencias  (te faltan 12)". */
    static Component falta(String que, String cuanto) {
        TextComponent.Builder b = Component.text().append(Component.text("✘ ", NO)).append(Component.text(que, Paleta.TEXTO));
        if (cuanto != null && !cuanto.isEmpty()) b.append(Component.text("  (" + cuanto + ")", NO));
        return b.build();
    }

    /** Una linea de coste que ahora no se puede pagar por algo que no depende del jugador. */
    static Component apagado(String que, String porque) {
        return Component.text("✘ " + que + "  (" + porque + ")", Paleta.TENUE);
    }

    /** Barra de progreso de texto: 10 cuadros, llenos en verde, y "12/30" detras. */
    static Component barra(long hecho, long total) {
        int trozos = 10;
        long t = Math.max(1, total);
        int llenos = (int) Math.max(0, Math.min(trozos, hecho * trozos / t));
        if (hecho > 0 && llenos == 0) llenos = 1;
        return Component.text().append(Component.text("■".repeat(llenos), SI))
                .append(Component.text("■".repeat(trozos - llenos), Paleta.SEPARADOR))
                .append(Component.text("  " + Math.min(hecho, total) + "/" + total, Paleta.CIFRA)).build();
    }

    static String esencias(long n) {
        return Altar.miles(n) + (n == 1 ? " Esencia" : " Esencias");
    }

    /** "0,2" o "6": sin decimales cuando no hacen falta, con coma. */
    static String numero(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.format(Locale.ROOT, "%.1f", d).replace('.', ',');
    }

    static String porcentaje(double d) {
        return numero(Math.round(d * 1000) / 10.0) + " %";
    }

    // ------------------------------------------------------------------ sonidos

    /** Solo lo oye el que mira el menu (no la gente de alrededor, como un sonido del mundo). */
    static void sonar(Player p, String clave, float volumen, float tono) {
        try {
            p.playSound(p, clave, SoundCategory.MASTER, volumen, tono);
        } catch (Throwable ignorado) {
            // Un sonido que no existe en esta version no rompe el menu.
        }
    }

    static void sonidoPestana(Player p) {
        sonar(p, "item.book.page_turn", 0.8f, 1.1f);
    }

    static void sonidoNo(Player p) {
        sonar(p, "entity.villager.no", 0.7f, 1.0f);
    }

    // ------------------------------------------------------------------ el Altar y los enlaces

    /**
     * La regla del Altar: fuera de Calamity o en su zona spawn (terreno seguro, la puerta de
     * salida al lado). Dentro de verdad no, porque vende el Cristal de Regreso. La miran el NPC,
     * los enlaces y cada clic del Altar.
     */
    static boolean puedeAltar(Hardcore hc, Player p) {
        return !hc.esHardcore(p) || hc.enSpawn(p);
    }

    /** Si desde donde esta el jugador el Altar (y la Forja) se abre ahora. */
    static boolean altarAbierto(Hardcore hc, Player p) {
        Altar altar = hc.altar();
        return altar != null && altar.activo() && puedeAltar(hc, p);
    }

    /** Por que el Altar (y la Forja) no se abre desde donde esta el jugador. */
    static final String ALTAR_FUERA = "El Altar solo se abre fuera de Calamity o en su spawn.";

    /**
     * Un enlace a otro menu (el Altar, la Forja, el Mercado): icono, nombre, dos lineas y "Clic
     * para ir"; en gris si ahora no se abre, con el por que (el Altar desde dentro de Calamity).
     * Deja en acciones "tab:<id>" o "no-tab:<id>".
     */
    static void enlace(Inventory inv, Map<Integer, String> acciones, int casilla, String id, Material m, String nombre,
                       List<String> texto, boolean abierto) {
        List<Component> lore = new ArrayList<>();
        for (String l : texto) lore.add(tenue(l));
        lore.add(Component.empty());
        if (abierto) lore.add(accion("Clic para ir"));
        else lore.add(porQueNo(id.equals(TASADOR) ? "Oren no está disponible ahora." : "Solo fuera de Calamity o en su spawn."));
        inv.setItem(casilla, icono(m, Component.text(nombre, abierto ? Paleta.DETALLE : Paleta.TENUE), lore, false));
        acciones.put(casilla, abierto ? "tab:" + id : "no-tab:" + id);
    }

    /** El clic en un enlace en gris: por que no se abre. */
    static void cerrado(Player p, String id) {
        p.sendMessage(ComandoCalamity.mensaje(TASADOR.equals(id) ? "Oren no está disponible ahora mismo." : ALTAR_FUERA));
        sonidoNo(p);
    }

    /**
     * Ir a otro menu (un tick despues del clic lo programa quien llama). El Altar y la Forja
     * vuelven a mirar la regla del Altar: el menu pudo abrirse en otro sitio. Tu camino es
     * informativo y se abre en cualquier sitio.
     */
    static void irA(Hardcore hc, Player p, String id) {
        if (TASADOR.equals(id)) {
            Npcs n = hc.npcs();
            if (n == null) {
                sonidoNo(p);
                return;
            }
            n.tasador().abrir(p, false);
            sonidoPestana(p);
            return;
        }
        Altar altar = hc.altar();
        if (CAMINO.equals(id)) {
            if (altar == null) {
                sonidoNo(p);
                return;
            }
            altar.camino().abrir(p);
            return;
        }
        if (altar == null || !altar.activo()) {
            p.sendMessage(ComandoCalamity.mensaje("El Altar está cerrado ahora mismo."));
            sonidoNo(p);
            return;
        }
        if (!puedeAltar(hc, p)) {
            p.sendMessage(ComandoCalamity.mensaje(ALTAR_FUERA));
            sonidoNo(p);
            return;
        }
        altar.menu().abrir(p, id, 0, false);
        sonidoPestana(p);
    }

    // ------------------------------------------------------------------ el saldo

    /**
     * Tu saldo en un solo icono (en la 1.3.0 eran cinco arriba y no se sabia cual mirar): las
     * Esencias en el nombre y en el lore las MobCoins (si el altar las puede cobrar) y los
     * creditos que tengas. Si llevas Esencias fisicas y estas fuera de Calamity brilla y el clic
     * las ingresa (accion "depositar"): el boton Depositar de antes vive aqui.
     */
    static void saldo(Inventory inv, Map<Integer, String> acciones, Hardcore hc, Player p, int casilla) {
        UUID u = p.getUniqueId();
        Saldo s = hc.saldo();
        long saldo = s == null ? 0 : s.de(u);
        List<Component> lore = new ArrayList<>();
        lore.add(tenue("Lo gastas en el Altar y en la Forja."));
        lore.add(tenue("Es tuyo: no caduca ni se reinicia."));
        Monedero mon = hc.monedero();
        if (mon != null && mon.disponible()) lore.add(dato("MobCoins", Altar.miles(mon.saldo(p))));

        Creditos cr = hc.creditos();
        if (cr != null) {
            List<Component> creditos = new ArrayList<>();
            for (Map.Entry<String, Integer> e : cr.todos(u).entrySet()) {
                String k = e.getKey();
                if (e.getValue() <= 0 || (!k.startsWith("sello:") && !k.equals(Creditos.ERRANTE))) continue;
                String n = k.equals(Creditos.ERRANTE) ? "Sello Errante" : "Sello " + Forja.delMinijefe(k.substring(6));
                creditos.add(credito(n, e.getValue(), cr.canjeable(u, k), cr));
            }
            int marcas = cr.de(u, "marca"), fragmentos = cr.de(u, "fragmento");
            if (marcas > 0) creditos.add(credito("Marcas de Eco", marcas, cr.canjeable(u, "marca"), cr));
            if (fragmentos > 0) creditos.add(credito("Fragmentos de Guadaña", fragmentos, cr.canjeable(u, "fragmento"), cr));
            lore.add(Component.empty());
            if (creditos.isEmpty()) lore.add(tenue("No tienes Sellos, Marcas ni Fragmentos."));
            else lore.addAll(creditos);
        }

        int encima = s == null ? 0 : s.encima(p);
        boolean dentro = hc.esHardcore(p);
        boolean ingresa = encima > 0 && !dentro;
        if (encima > 0) {
            lore.add(Component.empty());
            lore.add(Component.text("Llevas " + esencias(encima) + " encima.", Paleta.CIFRA));
            // Rama venta-oren: dentro (en la zona spawn) las ingresa Oren, en su tienda; fuera, tambien
            // este boton, como siempre.
            lore.add(dentro ? tenue("Oren te las ingresa en su tienda.") : accion("Clic para ingresarlas"));
        }
        inv.setItem(casilla, icono(Material.GHAST_TEAR, Component.text("Tu saldo: ", Paleta.TEXTO)
                .append(Component.text(esencias(saldo), Paleta.CIFRA)), lore, ingresa));
        if (ingresa) acciones.put(casilla, "depositar");
    }

    private static Component credito(String nombre, int n, boolean canjeable, Creditos cr) {
        Component c = Component.text(nombre + ": ", Paleta.TEXTO).append(Component.text(n, Paleta.CIFRA));
        if (!canjeable && cr != null) c = c.append(Component.text("  (pide " + Math.round(cr.horasPedidas()) + " h activas)", Paleta.TENUE));
        return c;
    }

    /**
     * "¿Como funciona?": de donde sale cada moneda y donde se hace cada cosa, con las cifras de
     * la config (si Dosa cambia la probabilidad del Sello, aqui cambia sola).
     */
    static ItemStack ayuda(Hardcore hc) {
        ConfigurationSection c = hc.cfg();
        Tasacion.Valores v = Tasacion.Valores.de(c);
        double sello = c.getDouble("reliquias.sello-minijefe.prob", 0.10);
        int piedad = Math.max(1, c.getInt("reliquias.sello-minijefe.piedad", 8));
        int marcasDia = c.getInt("eco.marcas.dia", 2);
        int nivelCampana = v.fragmentoNivel();
        List<Component> lore = new ArrayList<>();
        Reliquias rel = hc.reliquias();
        String astilla = rel == null ? "Astilla del Umbral" : rel.nombreDe(1, null, null);
        String mayor = rel == null ? "Ámbar Mayor" : rel.nombreDe(4, null, null);
        lore.add(texto("De dónde sale cada cosa:"));
        lore.add(linea("Esencias", "de los mobs de Calamity"));
        lore.add(tenue("  y de las Reliquias que vendes a Oren."));
        lore.add(tenue("  Cada " + astilla + " vale " + numero(v.esencias()[1]) + ";"));
        lore.add(tenue("  cada " + mayor + ", " + numero(v.esencias()[4]) + "."));
        lore.add(linea("Sellos", "los suelta su minijefe"));
        lore.add(tenue("  (" + porcentaje(sello) + ", seguro a las " + piedad + " muertes)."));
        lore.add(linea("Marcas de Eco", "al vender Lágrimas"));
        lore.add(tenue("  de Eco, hasta " + marcasDia + " al día."));
        lore.add(linea("Fragmentos", "al vender Campanas de la"));
        lore.add(tenue("  Parca de nivel " + nivelCampana + " o más."));
        lore.add(texto("Si mueres en Calamity, lo pierdes."));
        lore.add(texto("Tu saldo de Esencias no caduca."));
        lore.add(Component.empty());
        lore.add(texto("Quién es quién:"));
        lore.add(linea("Sael", "frascos, cristales, tinturas y llaves."));
        lore.add(linea("Vael", "el equipo de la Forja."));
        lore.add(linea("Oren", "compra lo que sacas, y contratos."));
        lore.add(linea("Rhen", "el ranking y el Tablero."));
        lore.add(linea("Lior", "pone y quita las gemas."));
        lore.add(linea("Ilen", "las historias de Calamity y tus Ecos."));
        // La encuesta y la lista de deseos van apagadas de serie: solo se anuncian si estan abiertas.
        // 1.12: sin comandos; las tiene Ilen en su menu (MenuCronista).
        boolean encuesta = c.getBoolean("encuesta.activo", false), deseos = c.getBoolean("deseos.activo", false);
        if (encuesta || deseos) {
            lore.add(Component.empty());
            lore.add(texto("Para dar tu opinión, habla con Ilen:"));
            if (encuesta) lore.add(tenue("  la encuesta y el Voto del Botín."));
            if (deseos) lore.add(tenue("  la lista de deseos."));
        }
        return icono(Material.KNOWLEDGE_BOOK, Component.text("¿Cómo funciona?", Paleta.MARCA), lore, false);
    }

    /** "Etiqueta: texto", la etiqueta en el verde de los nombres y el texto apagado (la ayuda). */
    private static Component linea(String etiqueta, String texto) {
        return Component.text(etiqueta + ": ", Paleta.DETALLE).append(tenue(texto));
    }
}
