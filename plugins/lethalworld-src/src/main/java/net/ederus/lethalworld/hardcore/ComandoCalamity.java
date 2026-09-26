package net.ederus.lethalworld.hardcore;

import net.ederus.lethalworld.LethalWorldPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
 * (eco, saldo, contratos, kit, tablero, encuesta, camino). Sin argumentos, o con uno que no
 * existe, sale la ayuda corta con lo que haya registrado y el jugador pueda usar.
 *
 * Todo por comando y texto: desde Bedrock se usa igual.
 */
public final class ComandoCalamity implements TabExecutor {

    /** El rojo de muerte de Calamity: prefijo de todos los mensajes de sistema. */
    public static final TextColor ROJO = TextColor.color(0x8B1A1A);
    private static final TextColor CUERPO = TextColor.color(0xC9C9C9);

    private final LethalWorldPlugin plugin;

    public ComandoCalamity(LethalWorldPlugin plugin) {
        this.plugin = plugin;
    }

    /** "Calamity · " en rojo de muerte, sin negrita. */
    public static Component prefijo() {
        return Component.text("Calamity · ", ROJO);
    }

    /** Un mensaje de sistema de Calamity: prefijo y el texto en gris claro. */
    public static Component mensaje(String texto) {
        return prefijo().append(Component.text(texto, CUERPO));
    }

    /** Lo mismo con un cuerpo ya montado (nombres y numeros en blanco, por ejemplo). */
    public static Component mensaje(Component cuerpo) {
        return prefijo().append(cuerpo.colorIfAbsent(CUERPO));
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
        quien.sendMessage(mensaje("Lo que traigas, lo pierdes al morir."));
        List<String[]> subs = Subcomandos.calamity().ayuda(quien);
        if (subs.isEmpty()) {
            quien.sendMessage(Component.text("  Aún no hay nada que consultar aquí.", NamedTextColor.GRAY));
            return;
        }
        String raiz = "/" + (etiqueta == null || etiqueta.isBlank() ? "calamity" : etiqueta.toLowerCase(Locale.ROOT));
        for (String[] s : subs) {
            quien.sendMessage(Component.text("  " + raiz + " " + s[0], NamedTextColor.WHITE)
                    .append(Component.text(s[1].isEmpty() ? "" : "  " + s[1], NamedTextColor.GRAY)));
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
