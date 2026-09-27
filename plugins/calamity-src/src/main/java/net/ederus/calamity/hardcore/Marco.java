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
 * Las piezas de los menus de Calamity (1.3.0): el Altar (Umbral y Forja), el Tasador, los
 * rankings del Cazador, el Tablero y Tu camino se ven como una sola cosa.
 *
 * Por que existe: la primera version del Altar era un cofre gris con iconos sueltos y Dosa lo
 * dijo claro, "ni siquiera se entiende como va". Las reglas que se aplican aqui, para que cada
 * menu no tenga que acordarse:
 *  - el marco es el del spawn de Calamity: anillo de cristal rojo por fuera, negro por dentro,
 *    y los cristales sin globo (no se abre un recuadro vacio al pasar por encima);
 *  - lo vacio es marco: no quedan huecos que parezcan casillas por rellenar;
 *  - cada fila de contenido lleva a la izquierda su rotulo (que es esa fila) y las cosas
 *    centradas y separadas a su derecha (repartir), que en el movil de Bedrock se tocan mejor;
 *  - fila de arriba: lo que tiene el jugador (Esencias, MobCoins, Sellos, Marcas y Fragmentos),
 *    "Como funciona" en el centro y Cerrar arriba a la derecha; fila de abajo: las pestanas
 *    Umbral, Forja y Tasador, y las flechas de pagina en las esquinas;
 *  - el coste se lee linea a linea: ✔ verde lo que tienes, ✘ rojo lo que te falta y cuanto;
 *  - la ultima linea de un boton dice siempre que hace el clic, o por que no se puede;
 *  - solo clic izquierdo y nada que haya que descubrir pasando el raton: lo que se puede
 *    comprar ya brilla, las cantidades van en el numero de la pila y las barras son de
 *    cristales de colores (Bedrock no tiene raton y, al tocar, ya hace clic).
 *
 * Colores de Paleta y nada de cursiva ni negrita (salvo la marca en el titulo de la ventana).
 */
final class Marco {

    private Marco() {
    }

    /** Filas y columnas de contenido de un menu de 54: filas 1-4, columnas 1-7 (la 0 es el rotulo). */
    static final int FILAS = 4;
    static final int COLUMNAS = 7;

    // La fila de arriba.
    static final int ESENCIAS = 1, MOBCOINS = 2, AYUDA = 4, SELLOS = 6, MARCAS = 7, CERRAR = 8;
    // La fila de abajo.
    static final int ANTERIOR = 45, SIGUIENTE = 53, TAB_UMBRAL = 47, TAB_FORJA = 49, TAB_TASADOR = 51;

    static final String UMBRAL = "umbral", FORJA = "forja", TASADOR = "tasador";

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

    /** Rellena lo que haya quedado vacio: rojo en el anillo, negro dentro. */
    static void rellenar(Inventory inv) {
        ItemStack rojo = cristal(Material.RED_STAINED_GLASS_PANE), negro = cristal(Material.BLACK_STAINED_GLASS_PANE);
        int n = inv.getSize();
        for (int i = 0; i < n; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, esBorde(i, n) ? rojo : negro);
        }
    }

    // ------------------------------------------------------------------ reparto

    /**
     * Donde cae cada cosa de una pagina con secciones. indice -1 es el rotulo de la seccion (en
     * la columna 0 de su primera fila en cada hoja); el resto, la cosa n.o indice de la seccion.
     */
    record Sitio(int hoja, int casilla, int seccion, int indice) {
    }

    /** Las columnas (1-7) de una fila con n cosas. */
    static int[] columnas(int n) {
        return COLUMNAS_DE[Math.max(0, Math.min(COLUMNAS, n))];
    }

    /**
     * Reparte secciones de tamanos dados en hojas de FILAS filas. Una seccion ocupa las filas que
     * pida (de 7 en 7); si no cabe entera en lo que queda de hoja y en una hoja nueva si, empieza
     * en la siguiente (no se parte sin necesidad). Una seccion mas larga que una hoja entera se
     * parte y su rotulo se repite arriba en la hoja nueva. Las secciones vacias no salen.
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
                if (f == 0 || fila == 0) out.add(new Sitio(hoja, base, s, -1));
                int enFila = Math.min(COLUMNAS, n - i);
                for (int c : columnas(enFila)) out.add(new Sitio(hoja, base + c, s, i++));
                fila++;
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

    /** El rotulo de una seccion: el icono que la resume, su nombre en ambar y dos lineas de que es. */
    static ItemStack rotulo(Material m, String nombre, List<String> texto) {
        List<Component> lore = new ArrayList<>();
        for (String l : texto) lore.add(Component.text(l, Paleta.TENUE));
        return icono(m, Component.text(nombre, Paleta.MARCA), lore, false);
    }

    /** Un boton sencillo: nombre, lineas y la accion (o el por que no) al final. */
    static ItemStack boton(Material m, String nombre, List<String> texto, String accion, boolean activo) {
        List<Component> lore = new ArrayList<>();
        for (String l : texto) lore.add(Component.text(l, Paleta.TEXTO));
        if (!texto.isEmpty()) lore.add(Component.empty());
        lore.add(activo ? accion(accion) : Component.text(accion, Paleta.TENUE));
        return icono(m, Component.text(nombre, activo ? Paleta.DETALLE : Paleta.TENUE), lore, false);
    }

    static ItemStack cerrar() {
        return icono(Material.BARRIER, Component.text("Cerrar", NO), List.of(), false);
    }

    /** Flecha de pagina. hacia: -1 anterior, +1 siguiente; hoja y total para el "2 de 3". */
    static ItemStack flecha(int hacia, int hoja, int total) {
        String n = hacia < 0 ? "◀ Página anterior" : "Página siguiente ▶";
        return icono(hacia < 0 ? Material.ARROW : Material.SPECTRAL_ARROW, Component.text(n, Paleta.DETALLE),
                List.of(Component.text("Estás en la " + (hoja + 1) + " de " + total + ".", Paleta.TENUE)), false);
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

    // ------------------------------------------------------------------ el Altar y las pestanas

    /**
     * La regla del Altar: fuera de Calamity o en su zona spawn (terreno seguro, la puerta de
     * salida al lado). Dentro de verdad no, porque vende el Cristal de Regreso. La miran el NPC,
     * las pestanas y cada clic del Altar.
     */
    static boolean puedeAltar(Hardcore hc, Player p) {
        return !hc.esHardcore(p) || hc.enSpawn(p);
    }

    /**
     * Las tres pestanas de abajo: la activa brilla y dice "Estas aqui"; Umbral y Forja salen en
     * gris si desde donde esta el jugador el Altar no escucha. Deja en acciones "tab:<id>".
     */
    static void pestanas(Inventory inv, Map<Integer, String> acciones, Hardcore hc, Player p, String activa) {
        Altar altar = hc.altar();
        boolean altarAbierto = altar != null && altar.activo() && puedeAltar(hc, p);
        boolean tasador = hc.npcs() != null;
        pestana(inv, acciones, TAB_UMBRAL, UMBRAL, Material.ENCHANTING_TABLE, "Umbral",
                List.of("Lo que te llevas dentro:", "frascos, cristales y la Llave."), activa, altarAbierto);
        pestana(inv, acciones, TAB_FORJA, FORJA, Material.ANVIL, "Forja",
                List.of("El equipo de Calamity: el Manto,", "el Vestigio del Eco y la Guadaña."), activa, altarAbierto);
        pestana(inv, acciones, TAB_TASADOR, TASADOR, Material.SPYGLASS, "Tasador",
                List.of("Lo que traes, lo que cobras", "y tus contratos de hoy."), activa, tasador);
    }

    private static void pestana(Inventory inv, Map<Integer, String> acciones, int casilla, String id, Material m,
                                String nombre, List<String> texto, String activa, boolean abierta) {
        boolean aqui = id.equals(activa);
        List<Component> lore = new ArrayList<>();
        for (String l : texto) lore.add(tenue(l));
        lore.add(Component.empty());
        if (aqui) lore.add(Component.text("● Estás aquí", Paleta.MARCA));
        else if (abierta) lore.add(accion("Clic para ir"));
        else lore.add(porQueNo(id.equals(TASADOR) ? "Ahora mismo no está." : "Solo fuera de Calamity o en su spawn."));
        TextColor color = aqui ? Paleta.MARCA : abierta ? Paleta.TEXTO : Paleta.TENUE;
        inv.setItem(casilla, icono(m, Component.text(aqui ? "▸ " + nombre + " ◂" : nombre, color), lore, aqui));
        if (!aqui) acciones.put(casilla, abierta ? "tab:" + id : "no-tab:" + id);
    }

    /** El clic en una pestana en gris: por que no se abre. */
    static void pestanaCerrada(Player p, String id) {
        p.sendMessage(ComandoCalamity.mensaje(TASADOR.equals(id) ? "El Tasador no está ahora mismo."
                : "El altar no escucha desde ahí dentro."));
        sonidoNo(p);
    }

    /**
     * Ir a otra pestana (un tick despues del clic lo programa quien llama). Umbral y Forja vuelven
     * a mirar la regla del Altar: el menu pudo abrirse en otro sitio.
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
        if (altar == null || !altar.activo()) {
            p.sendMessage(ComandoCalamity.mensaje("El altar está en silencio ahora mismo."));
            sonidoNo(p);
            return;
        }
        if (!puedeAltar(hc, p)) {
            p.sendMessage(ComandoCalamity.mensaje("El altar no escucha desde ahí dentro."));
            sonidoNo(p);
            return;
        }
        altar.menu().abrir(p, id, 0, false);
        sonidoPestana(p);
    }

    // ------------------------------------------------------------------ la cabecera

    /**
     * La fila de arriba: Esencias, MobCoins, "Como funciona", Sellos, Marcas y Fragmentos y
     * Cerrar (acciones: "cerrar"). trueques = los del Altar, para decir que pide cada credito.
     */
    static void cabecera(Inventory inv, Map<Integer, String> acciones, Hardcore hc, Player p, List<Altar.Trueque> trueques) {
        UUID u = p.getUniqueId();
        Saldo s = hc.saldo();
        long saldo = s == null ? 0 : s.de(u);
        List<Component> es = new ArrayList<>();
        es.add(texto("Tu saldo fuera de Calamity."));
        es.add(texto("Se gasta en el Umbral y la Forja."));
        int encima = s == null ? 0 : s.encima(p);
        if (encima > 0) {
            es.add(Component.empty());
            es.add(Component.text("Llevas " + encima + " físicas encima:", Paleta.CIFRA));
            es.add(tenue(hc.esHardcore(p) ? "pasan al saldo al salir vivo." : "deposítalas en el Umbral."));
        }
        inv.setItem(ESENCIAS, icono(Material.GHAST_TEAR, Component.text("Esencias: ", Paleta.TEXTO)
                .append(Component.text(Altar.miles(saldo), Paleta.CIFRA)), es, false));

        Monedero mon = hc.monedero();
        long mc = mon != null && mon.disponible() ? mon.saldo(p) : -1;
        if (mc >= 0) {
            inv.setItem(MOBCOINS, icono(Material.SUNFLOWER, Component.text("MobCoins: ", Paleta.TEXTO)
                    .append(Component.text(Altar.miles(mc), Paleta.CIFRA)), List.of(
                    texto("Las del Survival. La Forja"), texto("las pide además de Esencias.")), false));
        } else {
            inv.setItem(MOBCOINS, icono(Material.SUNFLOWER, Component.text("MobCoins", Paleta.TENUE), List.of(
                    tenue("El altar aún no puede cobrarlas:"), tenue("lo que las pide sale en gris.")), false));
        }

        inv.setItem(AYUDA, ayuda(hc));

        Creditos cr = hc.creditos();
        List<Component> sellos = new ArrayList<>();
        int nSellos = 0;
        if (cr != null) {
            for (Map.Entry<String, Integer> e : cr.todos(u).entrySet()) {
                String k = e.getKey();
                if (!k.startsWith("sello:") && !k.equals(Creditos.ERRANTE)) continue;
                nSellos += e.getValue();
                String n = k.equals(Creditos.ERRANTE) ? "Sello Errante" : "Sello " + Forja.delMinijefe(k.substring(6));
                sellos.add(credito(n, e.getValue(), cr.canjeable(u, k), cr));
            }
        }
        if (sellos.isEmpty()) {
            sellos.add(tenue("Ninguno todavía."));
        }
        sellos.add(Component.empty());
        sellos.add(tenue("Cada pieza del Manto pide el Sello"));
        sellos.add(tenue("de su minijefe. No se pierden al morir."));
        inv.setItem(SELLOS, icono(Material.FIRE_CHARGE, Component.text("Sellos: ", Paleta.TEXTO)
                .append(Component.text(nSellos, Paleta.CIFRA)), sellos, false));

        int marcas = cr == null ? 0 : cr.de(u, "marca"), fragmentos = cr == null ? 0 : cr.de(u, "fragmento");
        List<Component> mf = new ArrayList<>();
        mf.add(credito("Marcas de Eco", marcas, cr == null || marcas == 0 || cr.canjeable(u, "marca"), cr));
        String piden = quienPide(trueques, "marca");
        if (!piden.isEmpty()) mf.add(tenue("  " + piden));
        mf.add(credito("Fragmentos de Guadaña", fragmentos, cr == null || fragmentos == 0 || cr.canjeable(u, "fragmento"), cr));
        piden = quienPide(trueques, "fragmento");
        if (!piden.isEmpty()) mf.add(tenue("  " + piden));
        mf.add(Component.empty());
        mf.add(tenue("No se pierden al morir."));
        inv.setItem(MARCAS, icono(Material.ECHO_SHARD, Component.text("Marcas ", Paleta.TEXTO)
                .append(Component.text(marcas, Paleta.CIFRA)).append(Component.text(" · Fragmentos ", Paleta.TEXTO))
                .append(Component.text(fragmentos, Paleta.CIFRA)), mf, false));

        inv.setItem(CERRAR, cerrar());
        acciones.put(CERRAR, "cerrar");
    }

    private static Component credito(String nombre, int n, boolean canjeable, Creditos cr) {
        Component c = Component.text(nombre + ": ", Paleta.TEXTO).append(Component.text(n, Paleta.CIFRA));
        if (!canjeable && cr != null) c = c.append(Component.text("  (con " + Math.round(cr.horasPedidas()) + " h activas)", Paleta.TENUE));
        return c;
    }

    /** "Máscara 5 · Filo 10": las piezas que piden ese credito y cuantos. */
    static String quienPide(List<Altar.Trueque> trueques, String credito) {
        List<String> out = new ArrayList<>();
        for (Altar.Trueque t : trueques) {
            if (credito.equals(t.credito()) && t.pieza() != null) out.add(Forja.nombreCorto(t.pieza()) + " " + Math.max(1, t.creditos()));
        }
        return String.join(" · ", out);
    }

    /**
     * "¿Como funciona?": de donde sale cada moneda y que se hace en cada pestana, con las cifras
     * de la config (si Dosa cambia la probabilidad del Sello, aqui cambia sola).
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
        String astilla = rel == null ? "Astilla" : rel.nombreDe(1, null, null);
        String mayor = rel == null ? "Ámbar Mayor" : rel.nombreDe(4, null, null);
        lore.add(dato("Esencias", "mobs de dentro y Reliquias"));
        lore.add(tenue("  " + astilla + " " + numero(v.esencias()[1]) + " · " + mayor + " " + numero(v.esencias()[4])));
        lore.add(dato("Sellos", "su minijefe, " + porcentaje(sello) + " (seguro a las " + piedad + " muertes)"));
        lore.add(dato("Marcas", "Lágrimas de Eco, hasta " + marcasDia + " al día"));
        lore.add(dato("Fragmentos", "Campanas de Parca de nivel " + nivelCampana + "+"));
        lore.add(texto("Solo cuenta lo que sacas vivo."));
        lore.add(Component.empty());
        lore.add(Component.text("Umbral", Paleta.DETALLE).append(tenue(": lo que te llevas dentro.")));
        lore.add(Component.text("Forja", Paleta.DETALLE).append(tenue(": el equipo de Calamity.")));
        lore.add(Component.text("Tasador", Paleta.DETALLE).append(tenue(": lo que traes y lo que cobras.")));
        return icono(Material.KNOWLEDGE_BOOK, Component.text("¿Cómo funciona?", Paleta.MARCA), lore, false);
    }
}
