package net.ederus.edm;

import java.util.List;
import java.util.Locale;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.ederus.edm.flex.FlexPlugin;
import net.ederus.edm.flex.MenuPoder;
import net.ederus.edm.flex.RegistroPoder;
import net.ederus.edm.minas.Mina;
import net.ederus.edm.minas.MinasPlugin;

/**
 * Los marcadores de EDM para PlaceholderAPI, todos bajo %edm_...%.
 *
 * Poder (modulo flex):
 *   %edm_poder%                 el Poder actual de quien mira (o el ultimo visto)
 *   %edm_poder_mejor%           lo mas que se le ha visto
 *   %edm_poder_pos%             su puesto en el top, o "-" si no esta apuntado
 *   %edm_poder_top_N_nombre%    el nombre del puesto N (1..100)
 *   %edm_poder_top_N_poder%     su Poder, con puntos de miles
 *
 * Minas (modulo minas):
 *   %edm_mina_ID_nombre%        el nombre de la mina
 *   %edm_mina_ID_minado%        cuanto se ha picado, en %
 *   %edm_mina_ID_restante%      cuanto queda, en %
 *   %edm_mina_ID_reinicio%      lo que falta para el reinicio, "m:ss" o "-"
 *
 * Solo se instancia si PlaceholderAPI esta cargado: la clase base viene de su jar.
 */
public final class Placeholders extends PlaceholderExpansion {

    private final EDMPlugin core;

    public Placeholders(EDMPlugin core) {
        this.core = core;
    }

    @Override
    public String getIdentifier() {
        return core.nombre().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public String getAuthor() {
        return "Ederus";
    }

    @Override
    public String getVersion() {
        return EDMPlugin.VERSION;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer quien, String params) {
        String p = params.toLowerCase(Locale.ROOT);
        if (p.startsWith("poder")) return poder(quien, p);
        if (p.startsWith("mina_")) return mina(p.substring(5));
        return null;
    }

    /* ----------------------------------------------------------------- poder */

    private String poder(OfflinePlayer quien, String p) {
        if (!(core.modulo("flex") instanceof FlexPlugin flex)) return "";
        RegistroPoder registro = flex.registroPoder();

        if (p.startsWith("poder_top_")) {
            String[] partes = p.substring("poder_top_".length()).split("_", 2);
            if (partes.length != 2) return null;
            int n;
            try {
                n = Integer.parseInt(partes[0]);
            } catch (NumberFormatException e) {
                return null;
            }
            List<RegistroPoder.Marca> top = registro.top(Math.max(1, n));
            if (n < 1 || n > top.size()) return partes[1].equals("nombre") ? "-" : "0";
            RegistroPoder.Marca m = top.get(n - 1);
            return switch (partes[1]) {
                case "nombre" -> m.nombre();
                case "poder" -> MenuPoder.cifra(m.mejor());
                default -> null;
            };
        }

        if (quien == null) return "";
        RegistroPoder.Marca m = registro.de(quien.getUniqueId());
        return switch (p) {
            case "poder" -> {
                if (quien instanceof Player vivo && vivo.isOnline()) {
                    yield MenuPoder.cifra(registro.anotar(vivo));
                }
                yield MenuPoder.cifra(m == null ? 0 : m.actual());
            }
            case "poder_mejor" -> MenuPoder.cifra(m == null ? 0 : m.mejor());
            case "poder_pos" -> {
                int pos = registro.posicion(quien.getUniqueId());
                yield pos == 0 ? "-" : String.valueOf(pos);
            }
            default -> null;
        };
    }

    /* ----------------------------------------------------------------- minas */

    private String mina(String resto) {
        if (!(core.modulo("minas") instanceof MinasPlugin minas)) return "";
        int corte = resto.lastIndexOf('_');
        if (corte <= 0) return null;
        String id = resto.substring(0, corte);
        String que = resto.substring(corte + 1);
        Mina m = minas.minas().de(id);
        if (m == null) return "-";
        return switch (que) {
            case "nombre" -> m.nombrePlano();
            case "minado" -> String.valueOf(Math.round(m.porcentajeMinado()));
            case "restante" -> String.valueOf(Math.round(100 - m.porcentajeMinado()));
            case "reinicio" -> m.cuentaAtras();
            default -> null;
        };
    }
}
