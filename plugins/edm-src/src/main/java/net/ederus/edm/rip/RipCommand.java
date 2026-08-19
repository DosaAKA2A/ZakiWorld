/*
 * Decompiled with CFR 0.152.
 */
package net.ederus.edm.rip;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.edm.rip.RipEffect;
import net.ederus.edm.rip.RipPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class RipCommand
implements CommandExecutor,
TabCompleter {
    private static final TextColor GOLD = TextColor.color((int)16766822);
    private static final TextColor SOFT = TextColor.color((int)0x8A8A8A);
    private static final String[] SUBS = new String[]{"gui", "kill", "death", "list", "info", "test", "reload"};
    private final RipPlugin plugin;

    public RipCommand(RipPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player) {
                Player p = (Player)sender;
                this.plugin.menu().open(p, RipEffect.Type.KILL);
            } else {
                this.usage(sender);
            }
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "gui": {
                Player p = this.requirePlayer(sender);
                if (p == null) break;
                this.plugin.menu().open(p, this.parseType(args, 1, RipEffect.Type.KILL));
                break;
            }
            case "kill": 
            case "death": {
                Player p = this.requirePlayer(sender);
                if (p == null) {
                    return true;
                }
                RipEffect.Type type = args[0].equalsIgnoreCase("kill") ? RipEffect.Type.KILL : RipEffect.Type.DEATH;
                RipEffect.Type type2 = type;
                if (args.length < 2) {
                    p.sendMessage(this.plugin.prefix().append((Component)Component.text((String)("Uso: /rip " + type.lower() + " <id|random|none>  \u00b7  ver /rip list"), (TextColor)SOFT)));
                    return true;
                }
                if (args[1].equalsIgnoreCase("none")) {
                    this.plugin.setChoice(p.getUniqueId(), type, null);
                    p.sendMessage(this.plugin.prefix().append((Component)Component.text((String)("Efecto de " + type.lower() + " desactivado."), (TextColor)SOFT)));
                    return true;
                }
                if (args[1].equalsIgnoreCase("random")) {
                    this.plugin.setChoice(p.getUniqueId(), type, "random");
                    p.sendMessage(this.plugin.prefix().append((Component)Component.text((String)"Equipado  ", (TextColor)NamedTextColor.GREEN)).append((Component)Component.text((String)"\u2684 Aleatorio", (TextColor)TextColor.color((int)7268320), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD})));
                    return true;
                }
                RipEffect e = RipEffect.byId(type, args[1]);
                if (e == null) {
                    p.sendMessage(this.plugin.prefix().append((Component)Component.text((String)("Efecto desconocido: " + args[1]), (TextColor)NamedTextColor.RED)));
                    return true;
                }
                if (!this.plugin.allows(p, e)) {
                    Component msg = this.plugin.isAdmin(p) ? this.plugin.prefix().append((Component)Component.text((String)"Bloqueado. Requiere ", (TextColor)NamedTextColor.RED)).append((Component)Component.text((String)e.permission(), (TextColor)GOLD)) : this.plugin.prefix().append((Component)Component.text((String)"Todavia no has desbloqueado este efecto.", (TextColor)NamedTextColor.RED));
                    p.sendMessage(msg);
                    return true;
                }
                this.plugin.setChoice(p.getUniqueId(), type, e.id());
                p.sendMessage(this.plugin.prefix().append((Component)Component.text((String)"Equipado  ", (TextColor)NamedTextColor.GREEN)).append((Component)Component.text((String)e.display(), (TextColor)e.rarity().color(), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD})));
                break;
            }
            case "list": {
                boolean showPerms = !(sender instanceof Player) || this.plugin.isAdmin((Player)sender);
                sender.sendMessage((Component)Component.text((String)("\u2726 Efectos Rip v" + RipPlugin.version()), (TextColor)GOLD, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}));
                for (RipEffect.Type type : RipEffect.Type.values()) {
                    if (args.length >= 2 && !args[1].equalsIgnoreCase(type.lower())) continue;
                    sender.sendMessage((Component)Component.empty());
                    sender.sendMessage((Component)Component.text((String)(type == RipEffect.Type.KILL ? "\u2694 KILL" : "\u2620 MUERTE"), (TextColor)(type == RipEffect.Type.KILL ? TextColor.color((int)0xFF5555) : TextColor.color((int)11829247)), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}));
                    for (RipEffect e : RipEffect.of(type)) {
                        Player p2;
                        boolean has = !(sender instanceof Player) || this.plugin.allows(p2 = (Player)sender, e);
                        Component line = ((TextComponent)((TextComponent)((TextComponent)Component.text((String)(has ? " \u2714 " : " \u2718 "), (TextColor)(has ? NamedTextColor.GREEN : NamedTextColor.RED)).append(e.rarity().bar())).append((Component)Component.text((String)("  " + RipCommand.pad(e.id(), 11)), (TextColor)NamedTextColor.WHITE))).append((Component)Component.text((String)RipCommand.pad(e.display(), 22), (TextColor)e.rarity().color()))).append((Component)Component.text((String)RipCommand.pad(RipPlugin.formatSeconds(this.plugin.effectCooldownMillis(e)), 7), (TextColor)NamedTextColor.AQUA));
                        if (showPerms) {
                            line = line.append((Component)Component.text((String)e.permission(), (TextColor)TextColor.color((int)0x555555)));
                        }
                        sender.sendMessage(line);
                    }
                }
                break;
            }
            case "test": {
                RipEffect e;
                Player p = this.requirePlayer(sender);
                if (p == null) {
                    return true;
                }
                if (!p.hasPermission("rip.admin")) {
                    this.denied((CommandSender)p);
                    return true;
                }
                RipEffect.Type type = this.parseType(args, 1, RipEffect.Type.KILL);
                if (args.length >= 3) {
                    e = RipEffect.byId(type, args[2]);
                    if (e == null) {
                        p.sendMessage(this.plugin.prefix().append((Component)Component.text((String)("Efecto desconocido: " + args[2]), (TextColor)NamedTextColor.RED)));
                        return true;
                    }
                } else {
                    e = this.plugin.resolveEffect(p, type);
                    if (e == null) {
                        p.sendMessage(this.plugin.prefix().append((Component)Component.text((String)("No tienes efecto de " + type.lower() + " equipado. Usa /rip test " + type.lower() + " <id> para probar cualquiera."), (TextColor)SOFT)));
                        return true;
                    }
                }
                this.plugin.runner().play(e, p.getLocation(), p, p);
                p.sendMessage(this.plugin.prefix().append((Component)Component.text((String)"Probando  ", (TextColor)SOFT)).append((Component)Component.text((String)e.display(), (TextColor)e.rarity().color())).append((Component)Component.text((String)("  ·  enfriamiento " + RipPlugin.formatSeconds(this.plugin.effectCooldownMillis(e))), (TextColor)TextColor.color((int)0x555555))));
                break;
            }
            case "info": {
                this.info(sender);
                break;
            }
            case "reload": {
                if (!sender.hasPermission("rip.admin")) {
                    this.denied(sender);
                    return true;
                }
                this.plugin.reloadAll();
                sender.sendMessage(this.plugin.prefix().append((Component)Component.text((String)("Recargado. " + this.plugin.heads().loaded() + " cabezas."), (TextColor)NamedTextColor.GREEN)));
                break;
            }
            default: {
                this.usage(sender);
            }
        }
        return true;
    }

    /*
     * Nada de dibujos ASCII aqui: el chat de Minecraft usa fuente proporcional
     * y las diagonales se descuadran. Solo lineas sueltas, que siempre cuadran.
     */
    private void info(CommandSender sender) {
        TextColor rule = TextColor.color((int)0x8B1A1A);
        Component bar = Component.text((String)"\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac", (TextColor)rule).decoration(TextDecoration.ITALIC, false);
        sender.sendMessage((Component)Component.empty());
        sender.sendMessage(bar);
        sender.sendMessage((Component)Component.empty());
        sender.sendMessage(((TextComponent)((TextComponent)Component.text((String)"            \u2726 ", (TextColor)TextColor.color((int)0xFF5555)).append((Component)Component.text((String)"R I P", (TextColor)TextColor.color((int)0xFF3030), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}))).append((Component)Component.text((String)" \u2726  ", (TextColor)TextColor.color((int)0xFF5555)))).append((Component)Component.text((String)("v" + RipPlugin.version()), (TextColor)GOLD, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD})).decoration(TextDecoration.ITALIC, false));
        sender.sendMessage(((TextComponent)((TextComponent)Component.text((String)"        Creado por ", (TextColor)SOFT).append((Component)Component.text((String)"Dosa", (TextColor)TextColor.color((int)0xFFFFFF), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}))).append((Component)Component.text((String)" e ", (TextColor)SOFT))).append((Component)Component.text((String)"Iris Studio", (TextColor)TextColor.color((int)7268320), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD})).decoration(TextDecoration.ITALIC, false));
        sender.sendMessage((Component)Component.empty());
        sender.sendMessage((Component)Component.text((String)"  Efectos de kill y de muerte. Cada vez que matas", (TextColor)SOFT).decoration(TextDecoration.ITALIC, false));
        sender.sendMessage((Component)Component.text((String)"  o te matan se dispara la animacion que tengas", (TextColor)SOFT).decoration(TextDecoration.ITALIC, false));
        sender.sendMessage((Component)Component.text((String)"  equipada, con su propio enfriamiento.", (TextColor)SOFT).decoration(TextDecoration.ITALIC, false));
        sender.sendMessage((Component)Component.empty());
        sender.sendMessage(((TextComponent)Component.text((String)"  Elige la tuya en ", (TextColor)SOFT).append((Component)Component.text((String)"/rip gui", (TextColor)TextColor.color((int)0xFFFFFF), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}))).decoration(TextDecoration.ITALIC, false));
        sender.sendMessage((Component)Component.empty());
        String dot = "  \u00b7  ";
        Component counts = ((TextComponent)((TextComponent)((TextComponent)Component.text((String)("  " + RipEffect.count(RipEffect.Type.KILL) + " de kill"), (TextColor)TextColor.color((int)0xFF5555)).append((Component)Component.text((String)dot, (TextColor)TextColor.color((int)0x404040)))).append((Component)Component.text((String)(RipEffect.count(RipEffect.Type.DEATH) + " de muerte"), (TextColor)TextColor.color((int)11829247)))).append((Component)Component.text((String)dot, (TextColor)TextColor.color((int)0x404040)))).append((Component)Component.text((String)(Rarity.values().length + " calidades"), (TextColor)GOLD)).decoration(TextDecoration.ITALIC, false);
        sender.sendMessage(counts);
        sender.sendMessage((Component)Component.empty());
        sender.sendMessage(Component.text((String)"▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬", (TextColor)TextColor.color((int)0x8B1A1A)).decoration(TextDecoration.ITALIC, false));
        sender.sendMessage((Component)Component.empty());
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(this.plugin.prefix().append((Component)Component.text((String)"/rip <gui|kill|death|list|info|test|reload>", (TextColor)SOFT)));
    }

    private void denied(CommandSender sender) {
        sender.sendMessage(this.plugin.prefix().append((Component)Component.text((String)"Necesitas el permiso rip.admin.", (TextColor)NamedTextColor.RED)));
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player) {
            Player p = (Player)sender;
            return p;
        }
        sender.sendMessage("Solo disponible en el juego.");
        return null;
    }

    private RipEffect.Type parseType(String[] args, int index, RipEffect.Type def) {
        if (args.length > index && args[index].equalsIgnoreCase("death")) {
            return RipEffect.Type.DEATH;
        }
        if (args.length > index && args[index].equalsIgnoreCase("kill")) {
            return RipEffect.Type.KILL;
        }
        return def;
    }

    private static String pad(String s, int n) {
        return s.length() >= n ? s + " " : s + " ".repeat(n - s.length());
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        ArrayList<String> out;
        block18: {
            String last;
            block19: {
                block17: {
                    out = new ArrayList<String>();
                    last = args[args.length - 1].toLowerCase(Locale.ROOT);
                    if (args.length != 1) break block17;
                    for (String s : SUBS) {
                        if (!s.startsWith(last)) continue;
                        out.add(s);
                    }
                    break block18;
                }
                if (args.length != 2) break block19;
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "kill": 
                    case "death": {
                        RipEffect.Type type = args[0].equalsIgnoreCase("kill") ? RipEffect.Type.KILL : RipEffect.Type.DEATH;
                        RipEffect.Type type2 = type;
                        if ("none".startsWith(last)) {
                            out.add("none");
                        }
                        if ("random".startsWith(last)) {
                            out.add("random");
                        }
                        for (RipEffect e : RipEffect.of(type)) {
                            if (!e.id().startsWith(last)) continue;
                            out.add(e.id());
                        }
                        break block18;
                    }
                    case "gui": 
                    case "test": 
                    case "list": {
                        for (String s : new String[]{"kill", "death"}) {
                            if (!s.startsWith(last)) continue;
                            out.add(s);
                        }
                    }
                }
                break block18;
            }
            if (args.length != 3 || !args[0].equalsIgnoreCase("test")) break block18;
            RipEffect.Type type = args[1].equalsIgnoreCase("death") ? RipEffect.Type.DEATH : RipEffect.Type.KILL;
            for (RipEffect e : RipEffect.of(type)) {
                if (!e.id().startsWith(last)) continue;
                out.add(e.id());
            }
        }
        return out;
    }
}

