package net.ederus.edm.superbeacon;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * vuelo: poder volar dentro del alcance, en supervivencia y aventura.
 *
 * La regla de oro es no quitarle a nadie un vuelo que no le dimos. Por eso:
 *   - solo se apunta como "nuestro" a quien NO podia volar cuando entro; el que ya volaba
 *     (creativo, espectador, /fly de otro plugin) no se apunta y nunca se le toca;
 *   - la lista de a quien se lo dimos se guarda en data.yml (vuelos). Si el servidor cae
 *     con alguien volando, al volver a entrar se le reconoce y el ciclo decide: si sigue
 *     en el alcance, sigue volando; si no, lo pierde con su aviso y su caida lenta.
 *
 * Al salir del alcance hay 5 s de gracia (volver a entrar la cancela). Despues se quita y,
 * si esta en el aire, cae despacio 10 s para que no se mate. Lo mismo cuando la baliza se
 * recoge o vence (el jugador deja de recibir el efecto). Al desconectarse no se le quita:
 * se le quitaria en pleno vuelo y al volver caeria; se queda apuntado y lo resuelve el
 * ciclo cuando entre. Al parar el modulo se quita a todos al momento, con caida lenta.
 */
final class ClaseVuelo extends ClaseEfecto {

    static final long GRACIA_TICKS = 100L;
    static final int CAIDA_LENTA_TICKS = 200;
    static final String GRUPO = "vuelo";

    static final class Alas extends Efecto {
        private final ClaseVuelo clase;

        Alas(ClaseVuelo clase, String clave, String nombre, Material icono) {
            super(clave, nombre, icono);
            this.clase = clase;
        }

        @Override
        ClaseEfecto clase() {
            return clase;
        }

        @Override
        String grupo() {
            return GRUPO;
        }

        @Override
        double fuerza() {
            return 1;
        }
    }

    /** Un vuelo sin baliza: lo que se siembra al que entra con vuelo nuestro apuntado. */
    final Alas marcador = new Alas(this, "vuelo", "Vuelo", Material.ELYTRA);

    /** jugador -> turno de su gracia en marcha. Otro turno (o ninguno) la anula. */
    private final Map<UUID, Integer> gracia = new HashMap<>();
    private int turno;

    ClaseVuelo(SuperBeaconPlugin plugin) {
        super(plugin, "vuelo");
    }

    @Override
    Efecto leer(String clave, String nombre, Material icono, ConfigurationSection s, Consumer<String> error) {
        return new Alas(this, clave, nombre, icono);
    }

    @Override
    Material icono() {
        return Material.ELYTRA;
    }

    @Override
    boolean porJugador() {
        return true;
    }

    @Override
    List<String> detalle(Efecto e) {
        return List.of(
                plugin.textos().crudo("detalle-vuelo", "&#8A8A8APuedes volar dentro de su alcance."),
                plugin.textos().crudo("detalle-vuelo-gracia", "&#8A8A8AAl salir tienes 5 segundos antes de caer."));
    }

    /** Supervivencia o aventura: los otros modos ya deciden el vuelo por su cuenta. */
    private static boolean nuestroModo(Player p) {
        GameMode m = p.getGameMode();
        return m == GameMode.SURVIVAL || m == GameMode.ADVENTURE;
    }

    @Override
    void aplicar(Player p, Efecto e) {
        UUID id = p.getUniqueId();
        gracia.remove(id);
        if (!nuestroModo(p)) {
            // En creativo o espectador el juego manda; si era nuestro, deja de serlo.
            plugin.registro().vuelo(id, false);
            return;
        }
        if (!p.getAllowFlight()) {
            p.setAllowFlight(true);
            plugin.registro().vuelo(id, true);
        }
        // Si ya podia volar y no esta apuntado, el vuelo es de otro: ni se apunta ni se toca.
    }

    @Override
    void quitar(Player p, Efecto e) {
        UUID id = p.getUniqueId();
        if (!plugin.registro().vuelos().contains(id)) return;
        if (!nuestroModo(p) || !p.getAllowFlight()) {
            // Nada que quitar: el juego u otro plugin ya se lo quito.
            plugin.registro().vuelo(id, false);
            return;
        }
        if (gracia.containsKey(id)) return;
        int mio = ++turno;
        gracia.put(id, mio);
        plugin.textos().manda(p, "vuelo-saliendo",
                "&#FFB627Saliste del alcance del Super Beacon: &fen 5 segundos &#FFB627dejas de volar.");
        Bukkit.getScheduler().runTaskLater(plugin.core(), () -> {
            Integer suyo = gracia.get(id);
            if (suyo == null || suyo != mio || plugin.detenido()) return;
            gracia.remove(id);
            Player q = Bukkit.getPlayer(id);
            if (q != null) quitarYa(q, true);
        }, GRACIA_TICKS);
    }

    /** Fuera vuelo ya, con caida lenta si esta en el aire. */
    void quitarYa(Player p, boolean avisar) {
        UUID id = p.getUniqueId();
        gracia.remove(id);
        plugin.registro().vuelo(id, false);
        if (!nuestroModo(p) || !p.getAllowFlight()) return;
        boolean enAire = p.isFlying() || p.getLocation().subtract(0, 0.2, 0).getBlock().isPassable();
        p.setFlying(false);
        p.setAllowFlight(false);
        if (enAire) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, CAIDA_LENTA_TICKS, 0, false, false, true));
        }
        if (avisar) {
            if (enAire) {
                plugin.textos().manda(p, "vuelo-perdido-aire",
                        "&7Ya no puedes volar. &fCaes despacio &7durante unos segundos.");
            } else {
                plugin.textos().manda(p, "vuelo-perdido", "&7Ya no puedes volar.");
            }
        }
    }

    /** Si tiene vuelo nuestro apuntado (de antes de un reinicio o de otra sesion). */
    boolean apuntado(Player p) {
        return plugin.registro().vuelos().contains(p.getUniqueId());
    }

    @Override
    void alSalir(Player p) {
        // Se queda apuntado a proposito (ver arriba); solo se olvida la gracia en marcha.
        gracia.remove(p.getUniqueId());
    }

    @Override
    void parar() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (apuntado(p)) quitarYa(p, false);
        }
        gracia.clear();
    }
}
