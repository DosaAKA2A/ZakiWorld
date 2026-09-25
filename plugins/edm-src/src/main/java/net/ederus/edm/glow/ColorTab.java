package net.ederus.edm.glow;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * EL UNICO SITIO DEL MODULO QUE HABLA CON TAB.
 *
 * El cliente pinta el contorno con el color del equipo del marcador, y esos
 * equipos son de TAB (uno por jugador). TAB saca el color del equipo del
 * ultimo codigo de color del prefijo del nametag. Asi que se le pide, con su
 * API, ese mismo prefijo con un codigo mas al final ("...&b"): el equipo pasa a
 * celeste y el brillo con el.
 *
 * El nombre sobre la cabeza lo dibuja UnlimitedNameTags, no el equipo, asi que
 * ese codigo de mas no se ve en ningun sitio: solo cambia el color del brillo.
 * El chat, la lista de TAB y los menus tampoco usan el color del equipo.
 *
 * Por reflexion: EDM no se compila contra TAB y sin el instalado esto no carga.
 */
final class ColorTab {

    private final Logger log;
    private Object api;
    private Method getPlayer;
    private Method getNameTagManager;
    private Method setPrefix;
    private Method originalPrefix;
    private boolean avisado;

    ColorTab(Logger log) {
        this.log = log;
    }

    private boolean listo() {
        if (api != null) return true;
        try {
            Class<?> tabApi = Class.forName("me.neznamy.tab.api.TabAPI");
            Class<?> tabPlayer = Class.forName("me.neznamy.tab.api.TabPlayer");
            Class<?> ntm = Class.forName("me.neznamy.tab.api.nametag.NameTagManager");
            Object instancia = tabApi.getMethod("getInstance").invoke(null);
            getPlayer = tabApi.getMethod("getPlayer", UUID.class);
            getNameTagManager = tabApi.getMethod("getNameTagManager");
            setPrefix = ntm.getMethod("setPrefix", tabPlayer, String.class);
            originalPrefix = ntm.getMethod("getOriginalRawPrefix", tabPlayer);
            api = instancia;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!avisado) {
                log.warning("[Brillo] No se pudo usar la API de TAB (" + e + "): el brillo sale del color del rango.");
                avisado = true;
            }
            return false;
        }
    }

    /**
     * Pone el color del equipo de este jugador (null = el suyo de siempre).
     * Devuelve false si TAB todavia no lo tiene cargado (acaba de entrar).
     */
    boolean fijar(Player p, Character codigo) {
        if (!listo()) return false;
        try {
            Object tp = getPlayer.invoke(api, p.getUniqueId());
            Object ntm = getNameTagManager.invoke(api);
            if (tp == null || ntm == null) return false;
            if (codigo == null) {
                setPrefix.invoke(ntm, tp, null);
            } else {
                Object base = originalPrefix.invoke(ntm, tp);
                setPrefix.invoke(ntm, tp, (base == null ? "" : base) + "&" + codigo);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!avisado) {
                log.warning("[Brillo] TAB rechazo el cambio de color: " + e);
                avisado = true;
            }
            return false;
        }
    }

    static boolean instalado() {
        return Bukkit.getPluginManager().getPlugin("TAB") != null;
    }
}
