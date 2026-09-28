package net.ederus.calamity.hardcore;

import net.ederus.calamity.CalamityPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /calamity (alias /cal): lo que un jugador puede consultar de Calamity (DIS sec. 6).
 *
 * No tiene subcomandos propios: cada modulo registra los suyos en Subcomandos.calamity()
 * (eco, saldo, contratos, kit, tablero, encuesta, camino, cronista). Sin argumentos, o con uno
 * que no existe, sale la ayuda corta con lo que haya registrado y el jugador pueda usar.
 *
 * Todo por comando y texto: desde Bedrock se usa igual.
 */
public final class ComandoCalamity implements TabExecutor {

    /**
     * Antes el rojo de muerte (#8B1A1A) del prefijo; no se leia sobre el chat. Queda como
     * alias del rojo claro de los avisos para no romper a quien lo use: lo nuevo va por Paleta.
     */
    @Deprecated
    public static final TextColor ROJO = Paleta.AVISO;

    private final CalamityPlugin plugin;

    public ComandoCalamity(CalamityPlugin plugin) {
        this.plugin = plugin;
    }

    /** "Calamity · " con el degradado de la marca (Paleta.prefijo). */
    public static Component prefijo() {
        return Paleta.prefijo();
    }

    /** Un mensaje de sistema de Calamity: prefijo y el texto en el color normal de la Paleta. */
    public static Component mensaje(String texto) {
        return Paleta.mensaje(texto);
    }

    /** Lo mismo con un cuerpo ya montado (nombres en DETALLE, cifras en CIFRA...). */
    public static Component mensaje(Component cuerpo) {
        return Paleta.mensaje(cuerpo);
    }

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        Hardcore hc = plugin.hardcore();
        if (hc == null || !hc.activo()) {
            quien.sendMessage(mensaje("Calamity está cerrado ahora mismo."));
            return true;
        }
        if (args.length > 0 && Subcomandos.calamity().ejecutar(quien, args)) return true;
        ayuda(quien, etiqueta);
        return true;
    }

    private void ayuda(CommandSender quien, String etiqueta) {
        quien.sendMessage(mensaje("Comandos de Calamity:"));
        List<String[]> subs = Subcomandos.calamity().ayuda(quien);
        if (subs.isEmpty()) {
            quien.sendMessage(Component.text("  Ahora mismo no hay nada que consultar.", Paleta.TENUE));
            return;
        }
        String raiz = "/" + (etiqueta == null || etiqueta.isBlank() ? "calamity" : etiqueta.toLowerCase(Locale.ROOT));
        for (String[] s : subs) {
            quien.sendMessage(Component.text("  " + raiz + " " + s[0], Paleta.DETALLE)
                    .append(Component.text(s[1].isEmpty() ? "" : "  " + s[1], Paleta.TENUE)));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            String escrito = args[0].toLowerCase(Locale.ROOT);
            for (String s : Subcomandos.calamity().nombres(quien)) if (s.startsWith(escrito)) out.add(s);
            return out;
        }
        return Subcomandos.calamity().tab(quien, args);
    }
}
