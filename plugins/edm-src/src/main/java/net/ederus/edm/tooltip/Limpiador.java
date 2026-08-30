package net.ederus.edm.tooltip;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Quita de los items de verdad el bloque que la version 1.17.0 les metio por
 * dentro.
 *
 * Existe por una averia: durante una hora el modulo escribio sobre el ItemStack
 * vivo en vez de sobre el paquete, asi que todo item encantado que un jugador
 * tuvo delante se quedo con el bloque grabado y con los encantamientos
 * ocultos. Eso no se arregla solo, y borrarlo a mano por NBT es una cirugia por
 * item; esto lo hace de una pasada.
 *
 * Lo que se restaura, y es la parte delicada: las lineas que el bloque se habia
 * llevado dentro (los encantamientos de AdvancedEnchantments) NO se borran, se
 * devuelven arriba del lore. Se distinguen porque las nuestras llevan el nombre
 * como componente traducible y las suyas son texto plano.
 */
final class Limpiador {

    private Limpiador() { }

    static int limpiar(Player jugador, Ajustes a) {
        int tocados = 0;
        for (ItemStack item : jugador.getInventory().getContents()) {
            if (limpiarItem(item, a)) {
                tocados++;
            }
        }
        for (ItemStack item : jugador.getEnderChest().getContents()) {
            if (limpiarItem(item, a)) {
                tocados++;
            }
        }
        if (tocados > 0) {
            jugador.updateInventory();
        }
        return tocados;
    }

    /** @return true si este item llevaba el bloque dentro y se le ha quitado. */
    static boolean limpiarItem(ItemStack item, Ajustes a) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        ItemLore lore = item.getData(DataComponentTypes.LORE);
        if (lore == null || lore.lines().isEmpty()) {
            return false;
        }
        var plano = PlainTextComponentSerializer.plainText();
        List<Component> lineas = new ArrayList<>(lore.lines());

        /*
         * El encabezado NO tiene por que estar el primero. AdvancedEnchantments
         * escribe sus lineas al principio del lore, asi que en un item que ya
         * estaba sucio y luego recibio un encanto suyo, el bloque queda por
         * debajo. Buscarlo solo en la linea 0 fue justo lo que hizo que la
         * primera version del limpiador dijera "0 items" con el item delante.
         */
        String cabecera = plano.serialize(a.encabezadoComponente()).trim();
        if (cabecera.isEmpty()) {
            return false;
        }
        int inicio = -1;
        for (int i = 0; i < lineas.size(); i++) {
            if (plano.serialize(lineas.get(i)).trim().equals(cabecera)) {
                inicio = i;
                break;
            }
        }
        if (inicio < 0) {
            return false;
        }
        /* El renglon de aire que ponemos encima del bloque, si es que estaba. */
        if (inicio == 1 && plano.serialize(lineas.get(0)).isBlank()) {
            lineas.remove(0);
            inicio = 0;
        }
        /* Lo de encima del bloque no se toca: se guarda y se vuelve a poner. */
        List<Component> encima = new ArrayList<>(lineas.subList(0, inicio));
        lineas = new ArrayList<>(lineas.subList(inicio, lineas.size()));
        lineas.remove(0);

        /* El bloque son las lineas seguidas que empiezan por nuestra vineta. */
        List<Component> restauradas = new ArrayList<>();
        String vineta = a.vineta().trim();
        while (!lineas.isEmpty()) {
            Component linea = lineas.get(0);
            if (!plano.serialize(linea).trim().startsWith(vineta)) {
                break;
            }
            lineas.remove(0);
            if (!nuestra(linea)) {
                /* Era de otro plugin: se le quita la vineta y vuelve al lore. */
                List<Component> hijos = linea.children();
                if (hijos.size() >= 2) {
                    restauradas.add(hijos.size() == 2 ? hijos.get(1)
                            : Component.empty().children(hijos.subList(1, hijos.size())));
                }
            }
        }

        /* El renglon en blanco que separaba el bloque del resto tambien es nuestro. */
        if (!lineas.isEmpty() && plano.serialize(lineas.get(0)).isBlank()) {
            lineas.remove(0);
        }

        /* Si AE ya volvio a escribir su linea arriba (lo hace entero cada vez
         * que anade un encanto), la copia absorbida sobra: restaurarla la
         * duplicaria. Se compara por el texto plano. */
        java.util.Set<String> yaEstan = new java.util.HashSet<>();
        for (Component c : encima) {
            yaEstan.add(plano.serialize(c).trim());
        }
        restauradas.removeIf(c -> yaEstan.contains(plano.serialize(c).trim()));

        /* Los cadaveres de las versiones rotas: lineas planas que dicen lo
         * mismo que un encantamiento real del item ("Fire Aspect II"). No son
         * de AE (las suyas llevan su icono delante) ni de MMOItems: son
         * nuestras grabadas, y se tiran esten donde esten. */
        Set<String> viejos = textosViejos(item, a);
        restauradas.removeIf(c -> viejos.contains(plano.serialize(c).trim()));
        lineas.removeIf(c -> viejos.contains(plano.serialize(c).trim()));

        /* Si al barrer quedo un renglon en blanco pegado a otro, sobra uno. */
        for (int i = lineas.size() - 1; i > 0; i--) {
            if (plano.serialize(lineas.get(i)).isBlank()
                    && plano.serialize(lineas.get(i - 1)).isBlank()) {
                lineas.remove(i);
            }
        }
        while (!lineas.isEmpty() && plano.serialize(lineas.get(0)).isBlank()) {
            lineas.remove(0);
        }

        encima.addAll(restauradas);
        restauradas = encima;
        restauradas.addAll(lineas);
        if (restauradas.isEmpty()) {
            item.unsetData(DataComponentTypes.LORE);
        } else {
            item.setData(DataComponentTypes.LORE, ItemLore.lore(restauradas));
        }
        destapar(item);
        return true;
    }

    /*
     * Los textos que las versiones rotas pudieron dejar grabados como texto
     * plano: "Fire Aspect II", "Mending", "Sharpness 7"... Se generan desde los
     * encantamientos que el item tiene de verdad, en el idioma del servidor,
     * que es como los escribia la 1.17.0. Sirven para reconocer esas lineas
     * huerfanas, que por estructura son indistinguibles de las de AE.
     */
    private static Set<String> textosViejos(ItemStack item, Ajustes a) {
        var plano = PlainTextComponentSerializer.plainText();
        Set<String> fuera = new LinkedHashSet<>();
        var todos = new java.util.HashMap<org.bukkit.enchantments.Enchantment, Integer>();
        var e1 = item.getData(DataComponentTypes.ENCHANTMENTS);
        if (e1 != null) {
            todos.putAll(e1.enchantments());
        }
        var e2 = item.getData(DataComponentTypes.STORED_ENCHANTMENTS);
        if (e2 != null) {
            todos.putAll(e2.enchantments());
        }
        for (var e : todos.entrySet()) {
            String nombre = plano.serialize(e.getKey().description()).trim();
            fuera.add(nombre);
            fuera.add(nombre + " " + Numerales.de(e.getValue(), a.tope()));
            fuera.add(nombre + " " + e.getValue());
        }
        return fuera;
    }

    /** Nuestras lineas llevan el nombre traducible; las ajenas son texto plano. */
    private static boolean nuestra(Component linea) {
        if (linea instanceof TranslatableComponent) {
            return true;
        }
        for (Component hijo : linea.children()) {
            if (nuestra(hijo)) {
                return true;
            }
        }
        return false;
    }

    /*
     * Devolver los encantamientos a la vista. Solo se quitan los dos que ponemos
     * nosotros: si el item escondia ademas los atributos o el "irrompible",
     * eso lo puso su creador y se queda como estaba.
     */
    private static void destapar(ItemStack item) {
        TooltipDisplay display = item.getData(DataComponentTypes.TOOLTIP_DISPLAY);
        if (display == null) {
            return;
        }
        Set<DataComponentType> quedan = new LinkedHashSet<>(display.hiddenComponents());
        boolean cambio = quedan.remove(DataComponentTypes.ENCHANTMENTS);
        cambio |= quedan.remove(DataComponentTypes.STORED_ENCHANTMENTS);
        if (!cambio) {
            return;
        }
        if (quedan.isEmpty() && !display.hideTooltip()) {
            item.unsetData(DataComponentTypes.TOOLTIP_DISPLAY);
            return;
        }
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .hideTooltip(display.hideTooltip())
                .hiddenComponents(quedan)
                .build());
    }
}
