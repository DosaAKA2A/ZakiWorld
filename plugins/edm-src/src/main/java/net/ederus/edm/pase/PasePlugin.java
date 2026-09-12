package net.ederus.edm.pase;

import java.math.BigInteger;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import io.github.battlepass.BattlePlugin;
import io.github.battlepass.api.BattlePassApi;
import io.github.battlepass.entity.base.UserEntity;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;

/*
 * Puente con BattlePass (AdvancedPlugins 5.x): subir NIVELES exactos del pase.
 *
 * Por que existe: los rangos de la tienda regalan "10 niveles del pase", pero
 * BattlePass solo sabe dar PUNTOS, y cuantos puntos son 10 niveles depende de
 * donde este el jugador (cada tier tiene su required-points en passes/<pase>.yml
 * y el jugador lleva ademas puntos sueltos dentro de su tier actual).
 *
 * La cuenta se apoya en como funciona el plugin por dentro (visto en su
 * bytecode, 5.0.12, con use-improved-tier-points: true):
 *   - user.getPoints() es el progreso DENTRO del tier actual, no el total de la
 *     temporada: al subir de tier, el plugin resta el coste y repite.
 *   - api.getRequiredPoints(T, pase) devuelve el coste de pasar de T a T+1.
 *   - api.givePoints() se encarga solo de los tier-ups en cadena: dispara las
 *     tier-up-actions, deja las recompensas pendientes y convierte en moneda
 *     el exceso al llegar al tope. Por eso aqui SOLO se calcula el numero y se
 *     le entrega a el; nada de tocar el tier a mano.
 *
 * Con eso, los puntos justos para subir N niveles desde el tier T con P puntos:
 *   suma de getRequiredPoints(k) para k = T .. T+N-1, menos P.
 * El jugador cae exactamente en el limite del tier objetivo, con 0 sobrantes.
 */
public final class PasePlugin extends Module {

    private BattlePlugin battlePass;

    public PasePlugin(EDMPlugin core) {
        super(core, "bp", "EDM");
    }

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("BattlePass") instanceof BattlePlugin bp && bp.isEnabled()) {
            this.battlePass = bp;
            getLogger().info("Modulo bp enganchado a BattlePass " + bp.getPluginMeta().getVersion() + ".");
        } else {
            throw new IllegalStateException("BattlePass no esta cargado");
        }
    }

    @Override
    public void onDisable() {
        this.battlePass = null;
    }

    /* ============================================================ /edm bp */

    @Override
    public boolean subcomando(CommandSender quien, String[] args) {
        if (args.length == 0) {
            quien.sendMessage("/edm bp level <niveles> [jugador]  |  /edm bp info [jugador]");
            return true;
        }

        if (args[0].equalsIgnoreCase("level") || args[0].equalsIgnoreCase("nivel")
                || args[0].equalsIgnoreCase("niveles")) {
            if (args.length < 2) {
                quien.sendMessage("Uso: /edm bp level <niveles> [jugador]");
                return true;
            }
            int niveles;
            try {
                niveles = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                quien.sendMessage("'" + args[1] + "' no es un numero de niveles.");
                return true;
            }
            if (niveles < 1 || niveles > 1000) {
                quien.sendMessage("Los niveles van de 1 a 1000.");
                return true;
            }
            UUID uuid = resolver(quien, args.length >= 3 ? args[2] : null);
            if (uuid == null) return true;
            String nombre = args.length >= 3 ? args[2] : quien.getName();
            conUsuario(quien, uuid, nombre, user -> subirNiveles(quien, uuid, nombre, user, niveles));
            return true;
        }

        if (args[0].equalsIgnoreCase("info")) {
            UUID uuid = resolver(quien, args.length >= 2 ? args[1] : null);
            if (uuid == null) return true;
            String nombre = args.length >= 2 ? args[1] : quien.getName();
            conUsuario(quien, uuid, nombre, user -> ficha(quien, nombre, user));
            return true;
        }

        return false;
    }

    /** Nombre -> UUID. Online directo; offline solo si el servidor lo conoce. */
    private UUID resolver(CommandSender quien, String nombre) {
        if (nombre == null) {
            if (quien instanceof Player p) return p.getUniqueId();
            quien.sendMessage("Desde consola hay que decir el jugador.");
            return null;
        }
        Player online = Bukkit.getPlayerExact(nombre);
        if (online != null) return online.getUniqueId();
        OfflinePlayer visto = Bukkit.getOfflinePlayerIfCached(nombre);
        if (visto != null) return visto.getUniqueId();
        quien.sendMessage("No conozco a ningun '" + nombre + "'.");
        return null;
    }

    /*
     * BattlePass carga a sus usuarios en asincrono (los offline vienen de la
     * base de datos). Se pide alli y se vuelve al hilo principal antes de tocar
     * nada: givePoints lanza recompensas y acciones que son cosa del servidor.
     */
    private void conUsuario(CommandSender quien, UUID uuid, String nombre,
                            java.util.function.Consumer<UserEntity> luego) {
        this.battlePass.getUserCache().getAsync(uuid).whenComplete((user, error) ->
                Bukkit.getScheduler().runTask(this.core, () -> {
                    if (error != null || user == null) {
                        quien.sendMessage("BattlePass no dio los datos de " + nombre
                                + (error != null ? " (" + error.getMessage() + ")" : "") + ".");
                        return;
                    }
                    luego.accept(user);
                }));
    }

    private void subirNiveles(CommandSender quien, UUID uuid, String nombre, UserEntity user, int niveles) {
        BattlePassApi api = this.battlePass.getLocalApi();
        int tope = this.battlePass.getPassCache().getMaxTier();
        int tier = user.getTier();
        if (tier >= tope) {
            quien.sendMessage(nombre + " ya esta en el tier maximo (" + tope + "); no se dio nada.");
            return;
        }

        int objetivo = Math.min(tier + niveles, tope);
        BigInteger falta;
        try {
            falta = puntosHasta(api, user, tier, objetivo).subtract(user.getPoints());
        } catch (Throwable t) {
            quien.sendMessage("No pude leer la tabla de tiers del pase '" + user.getPassId() + "': " + t);
            return;
        }

        if (falta.signum() > 0) {
            api.givePoints(user, falta.intValueExact());
        } else {
            /* Ya le sobraban puntos para el tramo: no se le da nada, solo se
             * le pone el tier al dia por si el pase se quedo atras. */
            falta = BigInteger.ZERO;
            api.updateUserTier(user);
        }
        this.battlePass.getUserCache().saveAsync(uuid);

        String recorte = objetivo < tier + niveles ? " (recortado al tope del pase)" : "";
        quien.sendMessage(nombre + ": tier " + tier + " -> " + user.getTier()
                + " con " + falta + " puntos" + recorte + ".");
    }

    /** Puntos totales para ir del limite de 'desde' al limite de 'hasta'. */
    private BigInteger puntosHasta(BattlePassApi api, UserEntity user, int desde, int hasta) {
        BigInteger total = BigInteger.ZERO;
        for (int k = desde; k < hasta; k++) {
            total = total.add(BigInteger.valueOf(api.getRequiredPoints(k, user.getPassId())));
        }
        return total;
    }

    private void ficha(CommandSender quien, String nombre, UserEntity user) {
        BattlePassApi api = this.battlePass.getLocalApi();
        int tope = this.battlePass.getPassCache().getMaxTier();
        int tier = user.getTier();
        StringBuilder linea = new StringBuilder(nombre + ": tier " + tier + " de " + tope
                + ", pase '" + user.getPassId() + "'");
        if (tier < tope) {
            try {
                int siguiente = api.getRequiredPoints(tier, user.getPassId());
                BigInteger diez = puntosHasta(api, user, tier, Math.min(tier + 10, tope))
                        .subtract(user.getPoints());
                linea.append(", ").append(user.getPoints()).append('/').append(siguiente)
                        .append(" puntos. Subir 10 niveles: ")
                        .append(diez.max(BigInteger.ZERO)).append(" puntos");
            } catch (Throwable t) {
                linea.append(", ").append(user.getPoints())
                        .append(" puntos (sin tabla de tiers: ").append(t.getMessage()).append(')');
            }
        } else {
            linea.append(" (tier maximo)");
        }
        quien.sendMessage(linea.append('.').toString());
    }
}
