package net.ederus.edm;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.coinflip.CoinflipPlugin;
import net.ederus.edm.troll.TrollPlugin;
import net.ederus.edm.comun.EntradaChat;
import net.ederus.edm.core.EderusMain;
import net.ederus.edm.misiones.MisionesPlugin;
import net.ederus.edm.rip.RipPlugin;
import net.ederus.edm.tienda.TiendaPlugin;
import net.ederus.edm.tooltip.TooltipPlugin;

/*
 * EDM: el nucleo de Ederus. Reune Rip, Anomaly y EderusMain (libros + avisos de
 * las tiendas de MobCoins) en un solo plugin.
 *
 * Cada modulo arranca aislado: si uno revienta, se anota y los otros siguen.
 * Antes eran tres plugins y un fallo solo se llevaba el suyo; aqui el aislamiento
 * hay que ponerlo a mano o volveriamos atras en fiabilidad.
 */
public final class EDMPlugin extends JavaPlugin {

    public static final String VERSION = "1.18.0";

    /* id del modulo -> carpeta del plugin viejo de la que se migran los datos */
    private static final Map<String, String> CARPETAS_VIEJAS = Map.of(
            "rip", "Rip",
            "anomaly", "Anomaly",
            "core", "EderusMain");

    private final Map<String, Module> modulos = new LinkedHashMap<>();
    private final List<String> fallidos = new ArrayList<>();

    /* Preguntar una linea por el chat lo necesitan la tienda (la cantidad
     * exacta) y el coinflip (cuanto apuestas). Una sola instancia para todos:
     * con una por modulo, dos preguntas al mismo jugador se pisarian. */
    private EntradaChat chat;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrarDatosAntiguos();

        chat = new EntradaChat(this);
        getServer().getPluginManager().registerEvents(chat, this);

        arrancar(new RipPlugin(this));
        arrancar(new AnomalyPlugin(this));
        arrancar(new EderusMain(this));
        arrancar(new TiendaPlugin(this));
        arrancar(new CoinflipPlugin(this));
        arrancar(new TrollPlugin(this));
        /* Quests es softdepend: sin el instalado, la clase del modulo de misiones
         * ni siquiera carga (referencia TaskType de Quests) y tumbaba TODO el
         * nucleo en el arranque. En Ederus siempre esta; esto protege cualquier
         * otro entorno, como el servidor local de pruebas. */
        /* ProtocolLib es softdepend por lo mismo que Quests: la clase del
         * modulo referencia sus tipos y sin el jar delante no llega ni a
         * cargarse. En Ederus esta desde siempre; esto cubre el resto. */
        if (getServer().getPluginManager().getPlugin("ProtocolLib") != null) {
            arrancar(new TooltipPlugin(this));
        } else {
            getLogger().info("ProtocolLib no esta instalado: el modulo de tooltip queda apagado.");
        }
        if (getServer().getPluginManager().getPlugin("Quests") != null) {
            arrancar(new MisionesPlugin(this));
        } else {
            getLogger().info("Quests no esta instalado: el modulo de misiones queda apagado.");
        }

        registrarComando();
        banner();
    }

    private void registrarComando() {
        var cmd = getCommand("edm");
        if (cmd != null) {
            cmd.setExecutor(this);
        }
    }

    /* Alias comodos: la gente escribe el nombre del comando, no el id del modulo. */
    private static final Map<String, String> ALIAS_MODULO = Map.of(
            "shop", "tienda", "tienda", "tienda",
            "cf", "coinflip", "apuestas", "coinflip",
            "bromas", "troll");

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            ficha(quien);
            return true;
        }

        /* La lista de modulos y los caidos son tripas de mantenimiento: dejaban
         * de ser publicos el dia que /edm paso a tener una ficha. */
        if (args[0].equalsIgnoreCase("estado")) {
            if (!permitido(quien)) return true;
            estado(quien);
            return true;
        }

        /* /edm <modulo> <subcomando> -> lo atiende el modulo, si sabe */
        if (args.length >= 2 && !args[1].equalsIgnoreCase("reload")) {
            if (!permitido(quien)) return true;
            String id = ALIAS_MODULO.getOrDefault(args[0].toLowerCase(java.util.Locale.ROOT),
                    args[0].toLowerCase(java.util.Locale.ROOT));
            Module m = this.modulos.get(id);
            if (m != null && m.subcomando(quien, java.util.Arrays.copyOfRange(args, 1, args.length))) {
                return true;
            }
        }

        /* /edm <modulo> reload -> recarga solo ese */
        if (args.length >= 2 && args[1].equalsIgnoreCase("reload")) {
            if (!permitido(quien)) return true;
            String id = ALIAS_MODULO.getOrDefault(args[0].toLowerCase(java.util.Locale.ROOT),
                    args[0].toLowerCase(java.util.Locale.ROOT));
            Module m = this.modulos.get(id);
            if (m == null) {
                quien.sendMessage("No hay ningun modulo activo llamado '" + args[0] + "'.");
                quien.sendMessage("Activos: " + String.join(", ", this.modulos.keySet()));
                return true;
            }
            if (!recargarUno(quien, m)) {
                quien.sendMessage("El modulo " + m.getId() + " no sabe recargarse en caliente.");
            }
            return true;
        }

        /* /edm reload -> todos los que sepan */
        if (args[0].equalsIgnoreCase("reload")) {
            if (!permitido(quien)) return true;
            reloadConfig();
            int n = 0;
            for (Module m : this.modulos.values()) {
                if (recargarUno(quien, m)) n++;
            }
            quien.sendMessage(n == 0 ? "Ningun modulo sabe recargarse en caliente."
                    : "Recargados " + n + (n == 1 ? " modulo." : " modulos."));
            return true;
        }

        estado(quien);
        return true;
    }

    private boolean permitido(CommandSender quien) {
        if (quien.hasPermission("ederus.admin")) return true;
        quien.sendMessage("No puedes.");
        return false;
    }

    /** Devuelve true si el modulo sabia recargarse. */
    private boolean recargarUno(CommandSender quien, Module m) {
        String r;
        try {
            r = m.recargar();
        } catch (Throwable t) {
            quien.sendMessage("  " + m.getId() + ": fallo al recargar (" + t + ")");
            getLogger().warning("Fallo recargando " + m.getId() + ": " + t);
            return true;
        }
        if (r == null) return false;
        quien.sendMessage("  " + m.getId() + ": " + r);
        return true;
    }

    /*
     * LA FICHA DE /edm, la unica parte del nucleo que ve un jugador.
     *
     * El dibujo del arranque va a la consola y solo ahi, porque la consola es
     * monoespaciada. En el chat de Minecraft la fuente es de ancho variable: en
     * cuanto mezclas espacios con simbolos, cada renglon empieza en un sitio y
     * el dibujo se desmonta.
     *
     * La salida es no mezclar nunca. TODAS las celdas son el mismo caracter, el
     * bloque lleno, y lo unico que cambia es el color: tinta o fondo. Midan lo
     * que midan, miden todas igual, asi que las cinco filas cuadran solas y el
     * texto de la derecha arranca a la misma altura en todas.
     */
    private static final String[] LOGO = {
        "XXX.XX..X.X",
        "X...X.X.XXX",
        "XX..X.X.X.X",
        "X...X.X.X.X",
        "XXX.XX..X.X"
    };

    private static final net.kyori.adventure.text.format.TextColor TINTA =
            net.kyori.adventure.text.format.TextColor.fromHexString("#0083FD");
    private static final net.kyori.adventure.text.format.TextColor FONDO =
            net.kyori.adventure.text.format.TextColor.fromHexString("#12202B");

    /** Una fila del logo, con el texto que la acompaña a la derecha. */
    private static net.kyori.adventure.text.Component filaLogo(String patron,
                                                               net.kyori.adventure.text.Component detras) {
        net.kyori.adventure.text.Component out = net.kyori.adventure.text.Component.empty()
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
        int i = 0;
        while (i < patron.length()) {
            char c = patron.charAt(i);
            int j = i;
            while (j < patron.length() && patron.charAt(j) == c) j++;
            out = out.append(net.kyori.adventure.text.Component.text(
                    "█".repeat(j - i), c == 'X' ? TINTA : FONDO));
            i = j;
        }
        return detras == null ? out : out.append(detras);
    }

    private static net.kyori.adventure.text.Component alLado(String texto,
                                                             net.kyori.adventure.text.format.TextColor color,
                                                             boolean negrita) {
        return net.kyori.adventure.text.Component.text("   " + texto, color)
                .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, negrita)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }

    /**
     * Lo unico que /edm le enseña a cualquiera. Ni versiones de modulos ni que
     * esta caido: eso es mantenimiento y va en /edm estado, que pide permiso.
     */
    private void ficha(CommandSender quien) {
        quien.sendMessage(filaLogo(LOGO[0], alLado("EDM", TINTA, true)));
        quien.sendMessage(filaLogo(LOGO[1], alLado("Núcleo de Ederus", net.ederus.edm.comun.Estilo.CLARO, false)));
        quien.sendMessage(filaLogo(LOGO[2], alLado("Dosa · IRIS Studio", net.ederus.edm.comun.Estilo.CLARO, false)));
        quien.sendMessage(filaLogo(LOGO[3], null));
        quien.sendMessage(filaLogo(LOGO[4], alLado("v" + VERSION, net.ederus.edm.comun.Estilo.APAGADO, false)));
    }

    private void estado(CommandSender quien) {
        quien.sendMessage("EDM v" + VERSION + " | modulos: " + String.join(", ", this.modulos.keySet()));
        if (!this.fallidos.isEmpty()) quien.sendMessage("caidos: " + String.join(", ", this.fallidos));
        quien.sendMessage("/edm reload  |  /edm <modulo> reload  |  /edm info");
    }

    @Override
    public void onDisable() {
        List<Module> alReves = new ArrayList<>(this.modulos.values());
        java.util.Collections.reverse(alReves);
        for (Module m : alReves) {
            try {
                m.onDisable();
            } catch (Throwable t) {
                getLogger().warning("El modulo " + m.getId() + " fallo al apagarse: " + t);
            }
            /* Los listeners se registraron con el modulo, no con el nucleo:
             * Bukkit no los va a quitar solo. */
            HandlerList.unregisterAll(m);
        }
        this.modulos.clear();

        /* Lo que es estatico no muere con los modulos. Sin esto, un reload deja
         * atras el cliente HTTP con sus hilos y unos cuantos mapas, y con ellos
         * el cargador de clases del EDM viejo entero. */
        try {
            net.ederus.edm.comun.Webhook.cerrar();
            net.ederus.edm.anomaly.core.Disguises.limpiarCache();
        } catch (Throwable t) {
            getLogger().warning("Fallo al soltar el estado compartido: " + t);
        }
    }

    private void arrancar(Module modulo) {
        String id = modulo.getId();
        if (!getConfig().getBoolean("modulos." + id, true)) {
            getLogger().info("Modulo " + id + " desactivado en el config; no se carga.");
            return;
        }
        try {
            modulo.getDataFolder().mkdirs();
            modulo.onEnable();
            this.modulos.put(id, modulo);
        } catch (Throwable t) {
            this.fallidos.add(id);
            /* Si revento a mitad de onEnable puede haber registrado ya sus
             * listeners: sin esto se quedan atendiendo eventos con el modulo
             * medio construido, que da errores raros de un modulo "caido". */
            HandlerList.unregisterAll(modulo);
            getLogger().severe("El modulo " + id + " no arranco: " + t);
            t.printStackTrace();
        }
    }

    /** La entrada por chat compartida. Nunca es null despues de onEnable. */
    public EntradaChat chat() {
        return this.chat;
    }

    public Module modulo(String id) {
        return this.modulos.get(id);
    }

    public boolean activo(String id) {
        return this.modulos.containsKey(id);
    }

    /*
     * Primer arranque: copia los datos de plugins/Rip, plugins/Anomaly y
     * plugins/EderusMain a plugins/EDM/<id>. NO borra nada: si hay que volver
     * atras, basta con reponer los tres jars viejos.
     */
    private void migrarDatosAntiguos() {
        File plugins = getDataFolder().getParentFile();
        for (Map.Entry<String, String> e : CARPETAS_VIEJAS.entrySet()) {
            File destino = new File(getDataFolder(), e.getKey());
            File origen = new File(plugins, e.getValue());
            if (destino.exists() || !origen.isDirectory()) {
                continue;
            }
            try {
                copiarArbol(origen.toPath(), destino.toPath());
                getLogger().info("Migrado " + e.getValue() + " -> EDM/" + e.getKey()
                        + " (la carpeta original se conserva intacta).");
            } catch (IOException ex) {
                getLogger().severe("No se pudo migrar " + e.getValue() + ": " + ex.getMessage());
            }
        }
    }

    private static void copiarArbol(Path origen, Path destino) throws IOException {
        try (var rutas = Files.walk(origen)) {
            for (Path p : rutas.toList()) {
                Path d = destino.resolve(origen.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(d);
                } else {
                    Files.createDirectories(d.getParent());
                    Files.copy(p, d, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /*
     * El dibujo va a la CONSOLA y solo ahi. En el chat de Minecraft la fuente
     * es proporcional y esto se descuadraria entero; en una consola es
     * monoespaciada y cuadra.
     */
    private static final String[] ARTE = {
        "                       ▄▄▄                      ",
        "     ▄▄▄▄▄        ▄▄▓▓▓▓▓▓▓▓▄                   ",
        "  ▄▒▒▓▓▀▀██▄   ▄▓▓▓▓▒▀▀▀▒▒▒▒▓▓   ▄▄▓░▄▄▄▓▓▓▄▄   ",
        " ░░▒▀     ▀▀   ▓▒▒▒▒      ▀░▒▒▓ ▐▓▓▓▒▀▒▓▀▒▒▒▓▓  ",
        "▐█ ▌ ▄▄▄▄      ▐░░░░        ░░▒ ▒▒▒▓▌ ░░▐ ▀░▒▒░ ",
        "░░░█░▒▀▀        ░  ▌          ░ ░░░▒▌ ▄░▓ ▐ ░░▒▌",
        "▐░▒█      ▀█▄   ▐░░▌      ▒░░   ▐  ░█  ▀  ▐   ░▌",
        " ░▒▒▄     ▄██▀   ▒▒▌    ▄▓▒▓▀    █░░▒▌    ▒░░▒█ ",
        "  ▀▒▓▓▄▄███▀     ▐▓▓▄▄██▓▀▀      ▐▒▒▒▌    ▐▓██▌ ",
        "     ▀▀▀▀         ███▀▀           ▀▀▀      ▀▀▀  "
    };

    private void banner() {
        var consola = Bukkit.getConsoleSender();
        String cargados = this.modulos.isEmpty() ? "ninguno" : String.join(", ", this.modulos.keySet());
        consola.sendMessage("");
        for (String linea : ARTE) {
            consola.sendMessage("§b" + linea);
        }
        consola.sendMessage("");
        consola.sendMessage("   §f§lEDM §8· §7Nucleo de Ederus §8· §fv" + VERSION);
        consola.sendMessage("   §7Creado por §b§lDosa §r§7e §b§lIRIS Studio");
        consola.sendMessage("   §7Modulos activos §8· §f" + cargados);
        if (!this.fallidos.isEmpty()) {
            consola.sendMessage("   §cModulos caidos §8· §f" + String.join(", ", this.fallidos));
        }
        consola.sendMessage("");
    }
}
