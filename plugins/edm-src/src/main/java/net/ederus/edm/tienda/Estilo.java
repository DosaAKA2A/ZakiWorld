package net.ederus.edm.tienda;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * El aspecto de la tienda. Los colores NO son inventados: salen del
 * LanguageFiles/lang-en.yml de EconomyShopGUI en produccion, para que al
 * cambiar de tienda el jugador vea lo mismo de siempre.
 *
 *   inventory-main-shop-title: '&x&0&0&8&3&F&D&lTIENDA &8| &x&D&7&F&3&F&FEderus'
 *   left-click-buy:  '&#91F4FFPrecio de compra\n &8▸ &f$%buyPrice%'
 *   right-click-sell:'&#FDFF66Precio de venta\n &8▸ &f$%sellPrice%'
 *   buy:  '&#4FFF55▸ Click para comprar ◂'
 *   sell: '&#545454▸ &#3E72CFClick para vender'
 */
public final class Estilo {

    private Estilo() { }

    /** Azul de marca de Ederus, el mismo de los menus de DeluxeMenus. */
    public static final TextColor MARCA = TextColor.fromHexString("#0083FD");
    public static final TextColor CLARO = TextColor.fromHexString("#D7F3FF");
    public static final TextColor COMPRA = TextColor.fromHexString("#91F4FF");
    public static final TextColor VENTA = TextColor.fromHexString("#FDFF66");
    public static final TextColor ACCION_COMPRA = TextColor.fromHexString("#4FFF55");
    public static final TextColor ACCION_VENTA = TextColor.fromHexString("#3E72CF");
    public static final TextColor APAGADO = TextColor.fromHexString("#545454");

    public static final String FLECHA = "▸";      // el mismo triangulito que ya usan

    /** Sin cursiva: Minecraft la pone sola en todo lo que lleve nombre o lore. */
    public static Component texto(String t, TextColor color) {
        return Component.text(t, color).decoration(TextDecoration.ITALIC, false);
    }

    public static Component vacio() {
        return Component.empty().decoration(TextDecoration.ITALIC, false);
    }

    /** "TIENDA | Ederus" y "EDERUS | Minerales", con el corte en gris como el suyo. */
    public static Component titulo(String izquierda, String derecha) {
        Component c = Component.text(izquierda, MARCA)
                .decorate(TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false);
        if (derecha == null) return c;
        return c.append(texto(" | ", NamedTextColor.DARK_GRAY))
                .append(texto(derecha, CLARO));
    }

    /** Un dato con su etiqueta encima, que es como lo presentan ellos. */
    public static Component etiqueta(String t, TextColor color) {
        return texto(t, color);
    }

    public static Component valor(String t) {
        return texto(" " + FLECHA + " ", NamedTextColor.DARK_GRAY)
                .append(texto(t, NamedTextColor.WHITE));
    }

    public static Component accion(String t, TextColor color) {
        return texto(FLECHA + " ", APAGADO).append(texto(t, color));
    }

    /** El simbolo y el formato de moneda son los de su config: '#,##0.00'. */
    public static String dinero(double d) {
        return "$" + String.format(java.util.Locale.US, "%,.2f", d);
    }
}
