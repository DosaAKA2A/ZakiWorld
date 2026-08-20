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

        banner();
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
