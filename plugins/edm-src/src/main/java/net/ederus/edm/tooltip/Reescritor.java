package net.ederus.edm.tooltip;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Coge el item que el servidor esta a punto de mandar al cliente y devuelve una
 * COPIA con los encantamientos escritos a nuestra manera. El item de verdad, el
 * que esta guardado en el cofre o en el inventario, no se toca nunca: esto vive
 * en el camino del paquete y solo cambia lo que se dibuja.
 *
 * Que hace, exactamente:
 *
 *  1. Apaga el bloque de encantamientos que dibuja el cliente (el componente
 *     tooltip_display), que es el que se rompe por encima del nivel 10.
 *  2. Escribe el bloque el mismo, ordenado y con los niveles en romano.
 *  3. Se lleva dentro del bloque las lineas de encantamiento que ya hubiera
 *     escrito otro plugin (las de AdvancedEnchantments), que si no se quedan
 *     sueltas por encima del encabezado.
 *
 * El nombre del encantamiento NO se traduce aqui: se manda como componente
 * traducible y lo resuelve el cliente. Asi cada jugador lo lee en su idioma sin
 * que tengamos que mantener ninguna tabla de nombres.
 *
 * Cuando NO toca nada, y es a proposito:
 *
 *  - Si el item ya trae los encantamientos ocultos, lo deja en paz. Eso es lo
 *    que hacen los items que se pintan su propio bloque: si escribieramos el
 *    nuestro encima saldria dos veces.
 *  - Si el tooltip entero esta oculto, tampoco hay nada que dibujar.
 */
final class Reescritor {

    /** Lo que se ha reescrito ya. Un inventario manda 46 items de golpe cada
     * vez que se abre y casi siempre son los mismos; sin esto se rehace el
     * trabajo entero en cada apertura. */
    private final Map<ItemStack, ItemStack> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ItemStack, ItemStack> eldest) {
            return size() > 512;
        }
    };

    /** En la cache: "este item se miro y no habia nada que cambiar". Desde 1.77.0
     * se miran tambien items sin encantamientos (el bloque de MMOItems), y sin
     * esto un item con lore cualquiera se volveria a leer en cada paquete. */
    private static final ItemStack NADA = ItemStack.empty();

    private Ajustes ajustes;

    Reescritor(Ajustes ajustes) {
        this.ajustes = ajustes;
    }

    Ajustes ajustesActuales() {
        return this.ajustes;
    }

    /** Como se escribe el paquete: clon, directo o lectura. Ver EscuchaPaquetes. */
    String modo() {
        return this.ajustes.modo();
    }

    void ajustes(Ajustes nuevos) {
        this.ajustes = nuevos;
        synchronized (this.cache) {
            this.cache.clear();
        }
    }

    /** @return la copia reescrita, o null si este item no habia que tocarlo. */
    ItemStack reescribir(ItemStack original) {
        if (original == null || original.isEmpty()) {
            return null;
        }
        if (!interesa(original)) {
            return null;
        }
        synchronized (this.cache) {
            ItemStack hecho = this.cache.get(original);
            if (hecho == NADA) {
                return null;
            }
            if (hecho != null) {
                return hecho.clone();
            }
        }

        /*
         * OJO, esto no es una manera rebuscada de copiar un item.
         *
         * Lo que ProtocolLib entrega es un espejo del ItemStack que el servidor
         * tiene vivo en el inventario, y clone() no bastaba: la primera version
         * de esto acabo escribiendo el bloque DENTRO de los items de verdad, y
         * se vio en un libro recien creado por consola que salio del give ya
         * con el lore metido. Pasar por bytes deja una copia sin ningun hilo
         * que la una al original, y como el resultado se guarda en la cache el
         * viaje se paga una vez por item distinto, no una vez por paquete.
         */
        byte[] bytes = original.serializeAsBytes();
        ItemStack copia = construir(ItemStack.deserializeBytes(bytes));
        synchronized (this.cache) {
            this.cache.put(ItemStack.deserializeBytes(bytes), copia == null ? NADA : copia);
        }
        return copia == null ? null : copia.clone();
    }

    /**
     * El eco del creativo, al reves: si un item que llega del cliente es uno de
     * los que dibujamos, devuelve el original (con la cantidad que trae). Es lo
     * que impide que el bloque de estadisticas redibujado acabe guardado dentro
     * del item cuando un admin lo toca en creativo. null si no es nuestro.
     */
    ItemStack original(ItemStack dibujado) {
        if (dibujado == null || dibujado.isEmpty() || !dibujado.hasData(DataComponentTypes.LORE)) {
            return null;
        }
        synchronized (this.cache) {
            for (Map.Entry<ItemStack, ItemStack> e : this.cache.entrySet()) {
                ItemStack hecho = e.getValue();
                if (hecho != NADA && hecho.isSimilar(dibujado)) {
                    ItemStack o = e.getKey().clone();
                    o.setAmount(dibujado.getAmount());
                    return o;
                }
            }
        }
        return null;
    }

    /*
     * Filtro barato. Se ejecuta sobre CADA item de CADA paquete, asi que lo
     * unico que puede hacer es mirar componentes; nada de construir texto.
     */
    private boolean interesa(ItemStack item) {
        if (encantosInteresan(item)) {
            return true;
        }
        if (!this.ajustes.hayBloques() || !item.hasData(DataComponentTypes.LORE)) {
            return false;
        }
        TooltipDisplay display = item.getData(DataComponentTypes.TOOLTIP_DISPLAY);
        return display == null || !display.hideTooltip();
    }

    private boolean encantosInteresan(ItemStack item) {
        if (!item.hasData(DataComponentTypes.ENCHANTMENTS)
                && !item.hasData(DataComponentTypes.STORED_ENCHANTMENTS)) {
            return false;
        }
        TooltipDisplay display = item.getData(DataComponentTypes.TOOLTIP_DISPLAY);
        if (display != null) {
            if (display.hideTooltip()) {
                return false;
            }
            Set<DataComponentType> ocultos = display.hiddenComponents();
            /* Ya se lo pinta otro (MMOItems, un cofre, un menu). Fuera. */
            if (ocultos.contains(DataComponentTypes.ENCHANTMENTS)
                    || ocultos.contains(DataComponentTypes.STORED_ENCHANTMENTS)) {
                return false;
            }
        }
        return !niveles(item).isEmpty();
    }

    /**
     * La copia dibujada: el bloque de encantamientos (si toca) y despues el de
     * estadisticas de MMOItems (si lo trae). null si no habia nada que cambiar.
     */
    private ItemStack construir(ItemStack base) {
        Ajustes a = this.ajustes;
        List<Component> conEncantos = encantosInteresan(base) ? bloqueEncantos(base, a) : null;
        ItemLore loreViejo = base.getData(DataComponentTypes.LORE);
        List<Component> sobre = conEncantos != null ? conEncantos
                : (loreViejo == null ? List.of() : loreViejo.lines());
        List<Component> conStats = bloqueEstadisticas(sobre, a);
        List<Component> hasta = conStats != null ? conStats : conEncantos != null ? conEncantos : sobre;
        List<Component> conEspeciales = bloqueEspeciales(hasta, a);
        if (conEncantos == null && conStats == null && conEspeciales == null) {
            return null;
        }
        ItemStack copia = base.clone();
        copia.setData(DataComponentTypes.LORE, ItemLore.lore(conEspeciales != null ? conEspeciales : hasta));
        if (conEncantos != null) {
            copia.setData(DataComponentTypes.TOOLTIP_DISPLAY, ocultarEncantamientos(base));
        }
        return copia;
    }

    /**
     * El bloque de estadisticas de MMOItems con la forma de los encantamientos:
     * la cabecera "─── ⚔ Características ───" pasa a "▎ Características:" y cada
     * linea "icono Nombre: valor" a " · Nombre valor". Solo se toca desde la
     * cabecera hasta el primer renglon en blanco; lo que no encaja con el patron
     * se deja tal cual. null si el lore no trae esa cabecera.
     */
    private static List<Component> bloqueEstadisticas(List<Component> lore, Ajustes a) {
        List<Component> actual = lore;
        boolean cambio = false;
        for (Ajustes.Estadisticas e : a.bloques()) {
            List<Component> r = bloque(actual, e, a);
            if (r != null) {
                actual = r;
                cambio = true;
            }
        }
        return cambio ? actual : null;
    }

    /** Un bloque: termina en el primer renglon en blanco o en la cabecera de otro bloque. */
    private static List<Component> bloque(List<Component> lore, Ajustes.Estadisticas e, Ajustes a) {
        if (!e.activo() || lore.isEmpty()) {
            return null;
        }
        java.util.Set<String> cabeceras = new java.util.HashSet<>();
        for (Ajustes.Estadisticas otro : a.bloques()) cabeceras.add(otro.cabecera());
        var plano = PlainTextComponentSerializer.plainText();
        int cab = -1;
        for (int i = 0; i < lore.size(); i++) {
            if (plano.serialize(lore.get(i)).trim().equals(e.cabecera())) {
                cab = i;
                break;
            }
        }
        if (cab < 0) {
            return null;
        }
        List<Component> fuera = new ArrayList<>(lore.subList(0, cab));
        if (e.lineaEnBlancoAntes() && cab > 0 && !plano.serialize(lore.get(cab - 1)).isBlank()) {
            fuera.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        }
        fuera.add(e.encabezadoComponente().decoration(TextDecoration.ITALIC, false));
        int i = cab + 1;
        for (; i < lore.size(); i++) {
            Component l = lore.get(i);
            String texto = plano.serialize(l);
            if (texto.isBlank() || cabeceras.contains(texto.trim())) {
                break;
            }
            var m = e.linea().matcher(texto);
            if (!m.matches() || m.groupCount() < 2) {
                fuera.add(l);
                continue;
            }
            Component nueva = Component.empty().decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(a.vineta(), a.colorVineta()));
            if (e.conservarIcono()) {
                nueva = nueva.append(Component.text(m.group(1) + " ", a.colorNombre()));
            }
            nueva = nueva.append(Component.text(m.group(2).trim(), a.colorNombre()));
            if (m.groupCount() >= 3 && m.group(3) != null && !m.group(3).isBlank()) {
                nueva = nueva.append(Component.text(" " + m.group(3).trim(), e.colorValor()));
            }
            fuera.add(nueva);
        }
        fuera.addAll(lore.subList(i, lore.size()));
        return fuera;
    }

    /** El lore con el bloque de encantamientos delante, o null si no toca (solo-los-que-se-rompen). */
    private List<Component> bloqueEncantos(ItemStack base, Ajustes a) {
        List<Map.Entry<Enchantment, Integer>> encantos = niveles(base);
        if (encantos.isEmpty()) {
            return null;
        }

        /* Solo los que se rompen, si asi se ha pedido. Sirve para estrenar esto
         * sin cambiarle el aspecto a todo el servidor de golpe. */
        if (a.soloSiSePasa() && encantos.stream().noneMatch(e -> e.getValue() > 10)) {
            return null;
        }

        ordenar(encantos, a);

        ItemLore loreViejo = base.getData(DataComponentTypes.LORE);
        List<Component> previas = loreViejo == null ? List.of() : loreViejo.lines();

        /* Las lineas de encantamiento de otros plugins se sacan de arriba y se
         * meten en el bloque; el resto del lore se queda tal cual. */
        List<Component> absorbidas = new ArrayList<>();
        List<Component> resto = repartir(previas, absorbidas, a);

        List<Component> lineas = new ArrayList<>();
        /* Un renglon de aire entre el nombre del item y el bloque. Sin esto el
         * encabezado queda pegado al titulo y el tooltip se lee como un muro;
         * en la referencia que puso el dueno cada bloque va separado. */
        if (a.lineaEnBlancoAntes()) {
            lineas.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        }
        if (!a.encabezado().isEmpty()) {
            lineas.add(a.encabezadoComponente());
        }
        if (a.absorbidosPrimero()) {
            absorbidas.forEach(l -> lineas.add(vineta(l, a)));
        }
        for (Map.Entry<Enchantment, Integer> e : encantos) {
            lineas.add(linea(e.getKey(), e.getValue(), a));
        }
        if (!a.absorbidosPrimero()) {
            absorbidas.forEach(l -> lineas.add(vineta(l, a)));
        }
        if (!resto.isEmpty() && a.lineaEnBlanco()) {
            lineas.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        }
        lineas.addAll(resto);
        return lineas;
    }

    /*
     * Separa el lore en dos: lo que se absorbe y lo que se queda donde estaba.
     *
     * Solo se mira el bloque de arriba del todo, hasta el primer renglon en
     * blanco: ahi es donde AdvancedEnchantments escribe los suyos. Mas abajo
     * esta la descripcion del item y las estadisticas de MMOItems, y esas no se
     * tocan ni por asomo.
     */
    private static List<Component> repartir(List<Component> previas, List<Component> absorbidas, Ajustes a) {
        List<Component> resto = new ArrayList<>(previas);
        if (a.absorber().isEmpty()) {
            return resto;
        }
        var plano = PlainTextComponentSerializer.plainText();
        while (!resto.isEmpty()) {
            String texto = plano.serialize(resto.get(0)).trim();
            if (texto.isEmpty()) {
                /* El renglon en blanco cierra el bloque de arriba. Si todo lo
                 * que habia encima se ha absorbido, sobra tambien. */
                if (!absorbidas.isEmpty()) {
                    resto.remove(0);
                }
                break;
            }
            boolean casa = false;
            for (Pattern p : a.absorber()) {
                if (p.matcher(texto).find()) {
                    casa = true;
                    break;
                }
            }
            if (!casa) {
                break;
            }
            absorbidas.add(resto.remove(0));
        }
        return resto;
    }

    /** Le pone nuestra vineta a una linea ajena, respetando sus colores. */
    /**
     * EDM 1.82.0 · Las lineas especiales de AdvancedEnchantments (contadores, Bendicion de Dios) se sacan de donde esten
     * y se ponen en su propio bloque "Efectos" justo debajo de los encantamientos (o arriba del todo si no hay). null si
     * el item no trae ninguna.
     */
    private static List<Component> bloqueEspeciales(List<Component> lore, Ajustes a) {
        if (a.especiales().isEmpty() || lore == null || lore.isEmpty()) return null;
        var plano = PlainTextComponentSerializer.plainText();
        List<Component> sacadas = new ArrayList<>();
        List<Component> resto = new ArrayList<>();
        for (Component l : lore) {
            String t = plano.serialize(l);
            boolean casa = false;
            for (Pattern p : a.especiales()) {
                if (p.matcher(t).find()) {
                    casa = true;
                    break;
                }
            }
            if (casa) sacadas.add(l);
            else resto.add(l);
        }
        if (sacadas.isEmpty()) return null;
        // Un renglon en blanco que quedara colgando al final (el que separaba la linea sacada) sobra.
        while (!resto.isEmpty() && plano.serialize(resto.get(resto.size() - 1)).isBlank()) resto.remove(resto.size() - 1);
        String cabEncantos = plano.serialize(a.encabezadoComponente()).trim();
        int donde = -1;
        for (int i = 0; i < resto.size(); i++) {
            if (!cabEncantos.isEmpty() && plano.serialize(resto.get(i)).trim().equals(cabEncantos)) {
                int j = i + 1;
                while (j < resto.size() && !plano.serialize(resto.get(j)).isBlank()) j++;
                donde = j;
                break;
            }
        }
        List<Component> bloque = new ArrayList<>();
        bloque.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        bloque.add(a.encabezadoEspecialesComponente());
        for (Component s : sacadas) bloque.add(vineta(s, a));
        List<Component> out = new ArrayList<>(resto);
        if (donde < 0) {
            out.addAll(0, bloque);
        } else {
            out.addAll(donde, bloque);
        }
        return out;
    }

    private static Component vineta(Component linea, Ajustes a) {
        return Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(a.vineta(), a.colorVineta()))
                .append(linea);
    }

    /*
     * El tooltip_display que ya tuviera el item se respeta: si alguien habia
     * escondido los atributos o la durabilidad, se quedan escondidos. Solo se
     * anaden los dos componentes de encantamientos.
     */
    private static TooltipDisplay ocultarEncantamientos(ItemStack base) {
        TooltipDisplay previo = base.getData(DataComponentTypes.TOOLTIP_DISPLAY);
        TooltipDisplay.Builder b = TooltipDisplay.tooltipDisplay();
        if (previo != null) {
            b.hideTooltip(previo.hideTooltip());
            b.hiddenComponents(previo.hiddenComponents());
        }
        b.addHiddenComponents(DataComponentTypes.ENCHANTMENTS, DataComponentTypes.STORED_ENCHANTMENTS);
        return b.build();
    }

    /** " · Protección III". El nombre va traducible; el numeral, escrito por nosotros. */
    private static Component linea(Enchantment encanto, int nivel, Ajustes a) {
        boolean maldito = encanto.isCursed();
        /* Traducible a proposito: description() devuelve el nombre YA resuelto
         * en el idioma del servidor y a un jugador en espanol le salia
         * "Sharpness". Con la clave, cada cliente lo traduce al suyo. */
        Component nombre = Component.translatable(encanto.translationKey())
                .color(maldito ? a.colorMaldicion() : a.colorNombre());

        Component texto = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(a.vineta(), a.colorVineta()))
                .append(nombre);

        /* La regla de vanilla: un encantamiento que solo tiene un nivel no
         * lleva numeral. Por eso "Reparacion" va suelto y "Protección" no. */
        if (nivel != 1 || encanto.getMaxLevel() != 1) {
            texto = texto.append(Component.text(" " + Numerales.de(nivel, a.tope()),
                    maldito ? a.colorMaldicion() : a.colorDeNivel(nivel)));
        }
        return texto;
    }

    /*
     * El orden. Vanilla los saca en el orden en que estan guardados, que no es
     * ninguno: depende de como se encantara el item. Aqui se ordena siempre
     * igual, que es la mitad de lo que se pedia.
     */
    private static void ordenar(List<Map.Entry<Enchantment, Integer>> lista, Ajustes a) {
        Comparator<Map.Entry<Enchantment, Integer>> orden = switch (a.orden()) {
            case "nivel" -> Comparator
                    .comparingInt((Map.Entry<Enchantment, Integer> e) -> e.getValue()).reversed()
                    .thenComparing(e -> e.getKey().getKey().value());
            case "ninguno" -> null;
            default -> Comparator.comparing(e -> e.getKey().getKey().value());
        };
        if (orden == null) {
            return;
        }
        /* Las maldiciones al final siempre: son el aviso, no el titular. */
        lista.sort(Comparator
                .comparing((Map.Entry<Enchantment, Integer> e) -> e.getKey().isCursed())
                .thenComparing(orden));
    }

    private static List<Map.Entry<Enchantment, Integer>> niveles(ItemStack item) {
        Map<Enchantment, Integer> mapa = new LinkedHashMap<>();
        ItemEnchantments puestos = item.getData(DataComponentTypes.ENCHANTMENTS);
        if (puestos != null) {
            mapa.putAll(puestos.enchantments());
        }
        /* Los libros guardan lo suyo en otro componente y se dibujan igual. */
        ItemEnchantments guardados = item.getData(DataComponentTypes.STORED_ENCHANTMENTS);
        if (guardados != null) {
            mapa.putAll(guardados.enchantments());
        }
        return new ArrayList<>(mapa.entrySet());
    }
}
