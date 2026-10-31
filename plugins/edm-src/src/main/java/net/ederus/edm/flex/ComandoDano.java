package net.ederus.edm.flex;

import java.util.List;
import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Estilo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * /damage (y /daño, /dano): cuanto pega el arma que llevas en la mano.
 *
 * Lo pidio un jugador para comparar armas antes de pelear. Sale el golpe normal,
 * el critico, la probabilidad de critico y el golpe medio contando los criticos.
 *
 * Los criticos son los de MythicLib y se cuentan IGUAL que en el Poder
 * (comun/Poder): la base es x2 (CRITICAL_STRIKE_POWER 200) y el
 * critical-strike-power del item es un % adicional (1.8 = +1,8 %); la
 * probabilidad se corta en el tope de MythicLib, el 80 %. Si aqui se contara
 * de otra forma, /damage y /flex power dirian cosas distintas del mismo arma.
 *
 * El daño cuerpo a cuerpo se lee del ATRIBUTO del jugador, como el Poder: asi
 * vale igual para MMOItems y vanilla y ya incluye lo que sumen otras piezas.
 * Lo unico que el atributo no trae es Afilado, que vanilla suma aparte, y se
 * anade aqui. Castigo, Perdicion de los artropodos y Empalamiento no: solo
 * cuentan contra ciertos mobs y el numero dejaria de servir para comparar.
 *
 * A distancia (EDM 1.91.0) se cuenta lo mismo que en el Poder: la flecha de un
 * arco de MMOItems con su Poder (MythicLib la multiplica), y un tridente con
 * daño de lanzamiento de GodItems (distancia.tridente-lanzado) por ese daño, con
 * el cuerpo a cuerpo debajo.
 */
public final class ComandoDano implements CommandExecutor, TabCompleter {

    /** El rojo del prefijo: daño, sin gritar. Un solo tono, como el resto de la casa. */
    private static final String PREFIJO = "&#E0614F&lDAÑO &8» &f";
    private static final TextColor GRIS = NamedTextColor.GRAY;
    private static final TextColor TOPE = TextColor.color(0x8A8A8A);

    /** El tope de probabilidad de critico de MythicLib. */
    private static final double TOPE_CRITICO = 80;

    public ComandoDano() {
    }

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        if (!(quien instanceof Player p)) {
            quien.sendMessage(Estilo.legado(PREFIJO + "Esto se mira dentro del juego, con el arma en la mano."));
            return true;
        }
        if (!p.hasPermission("ederus.damage")) {
            p.sendMessage(Estilo.legado(PREFIJO + "No puedes usar este comando."));
            return true;
        }

        ItemStack arma = p.getInventory().getItemInMainHand();
        Clase clase = clase(arma);
        if (clase == null) {
            p.sendMessage(Estilo.legado(PREFIJO
                    + "Coge un arma en la mano principal: espada, hacha, tridente, arco o ballesta."));
            return true;
        }

        double lanzado = arma.getType() == Material.TRIDENT ? net.ederus.edm.comun.Poder.tridenteLanzado(arma) : 0;
        double golpe = clase == Clase.DISTANCIA ? net.ederus.edm.comun.Poder.danoDistancia(p, arma)
                : lanzado > 0 ? lanzado : danoCuerpo(p, arma);
        double[] crit = critico(p);
        double probBruta = crit[0];
        double prob = Math.min(TOPE_CRITICO, probBruta) / 100.0;
        double mult = crit[1] / 100.0;
        double golpeCritico = golpe * mult;
        double medio = golpe * (1 + prob * (mult - 1));

        p.sendMessage(Estilo.legado(PREFIJO).append(nombre(arma)));
        p.sendMessage(fila("Golpe normal", numero(golpe), null));
        p.sendMessage(fila("Golpe crítico", numero(golpeCritico), "x" + numero(mult)));
        p.sendMessage(fila("Prob. de crítico", numero(prob * 100) + " %",
                probBruta > TOPE_CRITICO ? "tope" : null));
        p.sendMessage(fila("Daño medio", numero(medio), null));
        if (clase == Clase.DISTANCIA) {
            p.sendMessage(Estilo.texto("   Con la cuerda tensada del todo.", TOPE));
        } else if (lanzado > 0) {
            p.sendMessage(fila("Cuerpo a cuerpo", numero(danoCuerpo(p, arma)), "sin lanzarlo"));
            p.sendMessage(Estilo.texto("   Lanzándolo.", TOPE));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        return List.of();
    }

    /* ------------------------------------------------------------------ armas */

    private enum Clase { CUERPO, DISTANCIA }

    /**
     * Que clase de arma es, o null si no lo es. Por material, y si el material no
     * lo dice, por la etiqueta de daño de MMOItems: hay armas suyas hechas sobre
     * un palo o una azada que pegan como una espada.
     */
    private static Clase clase(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        Material m = item.getType();
        if (m == Material.BOW || m == Material.CROSSBOW) return Clase.DISTANCIA;
        String n = m.name();
        if (n.endsWith("_SWORD") || n.endsWith("_AXE") || m == Material.TRIDENT || m == Material.MACE) {
            return Clase.CUERPO;
        }
        return danoMmo(item) > 0 ? Clase.CUERPO : null;
    }

    /** Cuerpo a cuerpo: el atributo de daño del jugador mas Afilado. */
    private static double danoCuerpo(Player p, ItemStack arma) {
        double dano = Compat.getAttribute(p, "attack_damage", 1);
        int afilado = nivel(arma, Enchantment.SHARPNESS);
        if (afilado > 0) dano += 0.5 * afilado + 0.5;
        return dano;
    }

    private static int nivel(ItemStack item, Enchantment e) {
        try {
            return e == null ? 0 : item.getEnchantmentLevel(e);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** La stat attack-damage de un item de MMOItems, o 0 (la misma lectura que el Poder). */
    private static double danoMmo(ItemStack item) {
        return net.ederus.edm.comun.Poder.danoMmo(item);
    }

    /**
     * {probabilidad en %, multiplicador en %} de los criticos de MythicLib, sin
     * cortar la probabilidad (el corte lo hace quien la usa, para poder decir
     * "tope"). Sin MythicLib: 0 % y x2, que es lo que habria si estuviera.
     */
    private static double[] critico(Player p) {
        try {
            io.lumine.mythic.lib.api.stat.StatMap stats =
                    io.lumine.mythic.lib.api.player.MMOPlayerData.get(p).getStatMap();
            double prob = Math.max(0, stats.getStat("CRITICAL_STRIKE_CHANCE"));
            double mult = Math.max(100, stats.getStat("CRITICAL_STRIKE_POWER"));
            return new double[]{prob, mult};
        } catch (Throwable t) {
            return new double[]{0, 200};
        }
    }

    /* ------------------------------------------------------------------ texto */

    /** El nombre del arma tal cual lo ve el jugador, con sus colores. */
    private static Component nombre(ItemStack item) {
        try {
            return item.effectiveName();
        } catch (Throwable t) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName() && meta.displayName() != null) return meta.displayName();
            return Component.translatable(item.getType().translationKey(), NamedTextColor.WHITE);
        }
    }

    /** "  ▸ Golpe normal  12,5", con la nota en gris oscuro detras si la hay. */
    private static Component fila(String etiqueta, String valor, String nota) {
        Component c = Estilo.texto("  " + Estilo.FLECHA + " ", NamedTextColor.DARK_GRAY)
                .append(Estilo.texto(etiqueta + "  ", GRIS))
                .append(Estilo.texto(valor, NamedTextColor.WHITE));
        if (nota != null) c = c.append(Estilo.texto("  " + nota, TOPE));
        return c;
    }

    /** "12", "12,5" o "12,48": con coma y sin ceros de relleno. */
    private static String numero(double d) {
        String s = String.format(Locale.US, "%.2f", d);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.replace('.', ',');
    }
}
