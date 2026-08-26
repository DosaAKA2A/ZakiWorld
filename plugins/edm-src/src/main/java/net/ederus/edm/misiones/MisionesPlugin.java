package net.ederus.edm.misiones;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import com.leonardobishop.quests.bukkit.BukkitQuestsPlugin;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;

/*
 * Puente entre EDM y Quests: registra los tipos de tarea propios de Ederus.
 *
 * Por que existe: Quests 3.16.1 no entiende la numeracion de Minecraft 26.x (lee
 * "1.1" del 26.1.2) y carga su capa de compatibilidad de 1.8, donde media docena
 * de comprobaciones son metodos vacios que devuelven falso. La primera victima
 * fueron las 30 misiones de planeador, que no contaban a nadie. Lo que aqui se
 * registra pregunta directo a la API de Paper y se salta esa capa.
 *
 * Quests no tiene evento de registro: sus tipos se registran en su onEnable y
 * despues carga las misiones. Como EDM arranca DESPUES (softdepend), al llegar
 * aqui las misiones que usan un tipo nuestro ya se descartaron por "tipo
 * desconocido"; por eso, si se registra algo, se le pide a Quests que recargue.
 * La recarga va un tick mas tarde y por el scheduler de Quests, que es el unico
 * que sabe si el servidor es normal o regionalizado.
 */
public final class MisionesPlugin extends Module {

    private BukkitQuestsPlugin quests;

    public MisionesPlugin(EDMPlugin core) {
        super(core, "misiones", "EDM");
    }

    @Override
    public void onEnable() {
        Plugin encontrado = Bukkit.getPluginManager().getPlugin("Quests");
        if (!(encontrado instanceof BukkitQuestsPlugin questsPlugin) || !encontrado.isEnabled()) {
            getLogger().info("Quests no esta cargado; no se registran tipos de tarea propios.");
            return;
        }
        this.quests = questsPlugin;

        boolean registrado = this.quests.getTaskTypeManager().registerTaskType(new TareaElytra(this.quests));
        if (!registrado) {
            getLogger().warning("Quests rechazo el tipo de tarea ederus_elytra; se queda como estaba.");
            return;
        }

        getLogger().info("Tipo de tarea ederus_elytra registrado en Quests.");
        this.quests.getScheduler().runTaskLater(() -> {
            try {
                this.quests.reloadQuests();
                getLogger().info("Misiones de Quests recargadas para que vean los tipos de Ederus.");
            } catch (Throwable t) {
                getLogger().severe("No se pudo recargar Quests: " + t);
            }
        }, 1L);
    }

    @Override
    public void onDisable() {
        /* Los listeners del tipo de tarea los registro Quests al aceptarlo, y es
         * Quests quien los suelta al apagarse. Aqui no hay nada que deshacer. */
        this.quests = null;
    }
}
