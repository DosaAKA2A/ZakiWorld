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
import net.ederus.edm.core.EderusMain;
import net.ederus.edm.rip.RipPlugin;
import net.ederus.edm.tienda.TiendaPlugin;

/*
 * EDM: el nucleo de Ederus. Reune Rip, Anomaly y EderusMain (libros + avisos de
 * las tiendas de MobCoins) en un solo plugin.
 *
 * Cada modulo arranca aislado: si uno revienta, se anota y los otros siguen.
 * Antes eran tres plugins y un fallo solo se llevaba el suyo; aqui el aislamiento
 * hay que ponerlo a mano o volveriamos atras en fiabilidad.
 */
public final class EDMPlugin extends JavaPlugin {

    public static final String VERSION = "1.1.0";

    /* id del modulo -> carpeta del plugin viejo de la que se migran los datos */
    private static final Map<String, String> CARPETAS_VIEJAS = Map.of(
            "rip", "Rip",
            "anomaly", "Anomaly",
            "core", "EderusMain");

    private final Map<String, Module> modulos = new LinkedHashMap<>();
    private final List<String> fallidos = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrarDatosAntiguos();

        arrancar(new RipPlugin(this));
        arrancar(new AnomalyPlugin(this));
        arrancar(new EderusMain(this));
        arrancar(new TiendaPlugin(this));

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
    private static final Map<String, String> ALIAS_MODULO = Map.of("shop", "tienda", "tienda", "tienda");

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        if (args.length == 0) {
            estado(quien);
            return true;
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

    private void estado(CommandSender quien) {
        quien.sendMessage("EDM v" + VERSION + " | modulos: " + String.join(", ", this.modulos.keySet()));
        if (!this.fallidos.isEmpty()) quien.sendMessage("caidos: " + String.join(", ", this.fallidos));
        quien.sendMessage("/edm reload  |  /edm <modulo> reload");
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
            getLogger().severe("El modulo " + id + " no arranco: " + t);
            t.printStackTrace();
        }
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

    private void banner() {
        var consola = Bukkit.getConsoleSender();
        String cargados = this.modulos.isEmpty() ? "ninguno" : String.join(", ", this.modulos.keySet());
        consola.sendMessage("§8[§bEDM§8] §fNucleo de Ederus §7v" + VERSION);
        consola.sendMessage("§8[§bEDM§8] §7Modulos activos: §f" + cargados);
        if (!this.fallidos.isEmpty()) {
            consola.sendMessage("§8[§bEDM§8] §cModulos caidos: §f" + String.join(", ", this.fallidos));
        }
    }
}
