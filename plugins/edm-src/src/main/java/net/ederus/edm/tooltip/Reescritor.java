package net.ederus.edm.tooltip;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

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
 *  2. Escribe el bloque el mismo, en el lore, con los niveles en romano.
 *
 * El nombre del encantamiento NO se traduce aqui: se manda como componente
 * traducible y lo resuelve el cliente. Asi un jugador en espanol sigue leyendo
 * "Proteccion" y uno en ingles "Protection", sin que tengamos que mantener
 * ninguna tabla de nombres.
 *
 * Cuando NO toca nada, y es a proposito:
 *
 *  - Si el item ya trae los encantamientos ocultos, lo deja en paz. Eso es lo
 *    que hacen los items de MMOItems que se pintan su propio bloque: si
 *    escribieramos el nuestro encima saldria dos veces.
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

    private Ajustes ajustes;

    Reescritor(Ajustes ajustes) {
        this.ajustes = ajustes;
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
            if (hecho != null) {
                return hecho.clone();
            }
        }
        ItemStack copia = construir(original);
        if (copia == null) {
            return null;
        }
        synchronized (this.cache) {
            this.cache.put(original.clone(), copia);
        }
        return copia.clone();
    }

    /*
     * Filtro barato. Se ejecuta sobre CADA item de CADA paquete, asi que lo
     * unico que puede hacer es mirar componentes; nada de construir texto.
     */
    private boolean interesa(ItemStack item) {
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

    private ItemStack construir(ItemStack original) {
        List<Map.Entry<Enchantment, Integer>> encantos = niveles(original);
        if (encantos.isEmpty()) {
            return null;
        }
        Ajustes a = this.ajustes;

        /* Solo los que se rompen, si asi se ha pedido. Sirve para estrenar esto
         * sin cambiarle el aspecto a todo el servidor de golpe. */
        if (a.soloSiSePasa()) {
            boolean alguno = encantos.stream().anyMatch(e -> e.getValue() > 10);
            if (!alguno) {
                return null;
            }
        }

        ordenar(encantos, a);

        List<Component> lineas = new ArrayList<>();
        ItemLore loreViejo = original.getData(DataComponentTypes.LORE);
        List<Component> previas = loreViejo == null ? List.of() : loreViejo.lines();

        if (!a.encabezado().isEmpty()) {
            lineas.add(a.encabezadoComponente());
        }
        for (Map.Entry<Enchantment, Integer> e : encantos) {
            lineas.add(linea(e.getKey(), e.getValue(), a));
        }
        if (!previas.isEmpty() && a.lineaEnBlanco()) {
            lineas.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        }
        lineas.addAll(previas);

        ItemStack copia = original.clone();
        copia.setData(DataComponentTypes.LORE, ItemLore.lore(lineas));
        copia.setData(DataComponentTypes.TOOLTIP_DISPLAY, ocultarEncantamientos(original));
        return copia;
    }

    /*
     * El tooltip_display que ya tuviera el item se respeta: si alguien habia
     * escondido los atributos o la durabilidad, se quedan escondidos. Solo se
     * anaden los dos componentes de encantamientos.
     */
    private static TooltipDisplay ocultarEncantamientos(ItemStack original) {
        TooltipDisplay previo = original.getData(DataComponentTypes.TOOLTIP_DISPLAY);
        TooltipDisplay.Builder b = TooltipDisplay.tooltipDisplay();
        if (previo != null) {
            b.hideTooltip(previo.hideTooltip());
            b.hiddenComponents(previo.hiddenComponents());
        }
        b.addHiddenComponents(DataComponentTypes.ENCHANTMENTS, DataComponentTypes.STORED_ENCHANTMENTS);
        return b.build();
    }

    /** " · Proteccion III". El nombre va traducible; el numeral, escrito por nosotros. */
    private static Component linea(Enchantment encanto, int nivel, Ajustes a) {
        Component texto = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(a.vineta(), a.colorVineta()))
                .append(encanto.description().color(encanto.isCursed() ? a.colorMaldicion() : a.colorNombre()));

        /* La regla de vanilla: un encantamiento que solo tiene un nivel no
         * lleva numeral. Por eso "Reparacion" va suelto y "Proteccion" no. */
        if (nivel != 1 || encanto.getMaxLevel() != 1) {
            texto = texto.append(Component.text(" " + Numerales.de(nivel, a.tope()),
                    encanto.isCursed() ? a.colorMaldicion() : a.colorNivel()));
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
