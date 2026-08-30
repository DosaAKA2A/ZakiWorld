package net.ederus.edm.tooltip;

/**
 * Numeros romanos.
 *
 * Minecraft solo trae traduccion para enchantment.level.1 hasta el 10. Del 11
 * en adelante el cliente no encuentra la clave y escribe la clave entera, que
 * es de donde sale el "Unbreaking enchantment.level.11" que se ve en el juego.
 * Aqui el numeral lo escribimos nosotros, asi que ese tope desaparece.
 *
 * Pasado cierto punto el romano deja de leerse (un 47 es XLVII y nadie lo lee
 * de un vistazo), asi que hay un tope configurable: por encima, numero normal.
 */
final class Numerales {

    private static final String[] UNIDADES = { "", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX" };
    private static final String[] DECENAS = { "", "X", "XX", "XXX", "XL", "L", "LX", "LXX", "LXXX", "XC" };
    private static final String[] CENTENAS = { "", "C", "CC", "CCC", "CD", "D", "DC", "DCC", "DCCC", "CM" };

    private Numerales() { }

    /**
     * @param nivel el nivel del encantamiento
     * @param tope  hasta que nivel se escribe en romano; por encima, en cifra
     */
    static String de(int nivel, int tope) {
        if (nivel < 1 || nivel > tope || nivel > 3999) {
            return String.valueOf(nivel);
        }
        StringBuilder sb = new StringBuilder(8);
        sb.append("M".repeat(nivel / 1000));
        sb.append(CENTENAS[(nivel / 100) % 10]);
        sb.append(DECENAS[(nivel / 10) % 10]);
        sb.append(UNIDADES[nivel % 10]);
        return sb.toString();
    }
}
