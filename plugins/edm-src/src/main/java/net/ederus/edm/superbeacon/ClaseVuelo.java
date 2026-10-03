package net.ederus.edm.superbeacon;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
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
 * si esta en el aire, cae despacio hasta tocar suelo para que no se mate (ver
 * caerDespacio). Lo mismo cuando la baliza se recoge o vence (el jugador deja de recibir
 * el efecto). Al desconectarse en el aire no se le quita: se le quitaria en pleno vuelo y
 * al volver caeria; se queda apuntado y lo resuelve el ciclo cuando entre (en el suelo si
 * se le quita, ver alSalir). Al parar el modulo se quita a todos al momento, con caida
 * lenta.
 */
final class ClaseVuelo extends ClaseEfecto {

    static final long GRACIA_TICKS = 100L;
    /** Lo minimo de caida lenta al quedarse sin vuelo en el aire. */
    static final int CAIDA_LENTA_TICKS = 200;
    /** Lo maximo: del techo del mundo (320) al fondo (-64) se cae en unos 40 s. */
    static final int CAIDA_LENTA_MAX_TICKS = 1200;
    /** Velocidad maxima con caida lenta, en bloques por tick (gravedad 0,01 con rozamiento 0,98). */
    static final double VELOCIDAD_CAIDA_LENTA = 0.49;
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
    /** jugador -> hasta cuando (ms) se le renueva la caida lenta mientras siga en el aire. */
    private final Map<UUID, Long> cayendo = new HashMap<>();

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
        if (enAire) caerDespacio(p);
        if (avisar) {
            if (enAire) {
                plugin.textos().manda(p, "vuelo-perdido-aire",
                        "&7Ya no puedes volar. &fCaes despacio &7durante unos segundos.");
            } else {
                plugin.textos().manda(p, "vuelo-perdido", "&7Ya no puedes volar.");
            }
        }
    }

    /**
     * Caida lenta hasta el suelo. Con 10 s fijos no bastaba: el alcance no tiene techo, y
     * quien se queda sin vuelo a 250 bloques del suelo recorre unos 75 despacio y el resto
     * a plomo, y se mata. Ahora dura lo que tarda en bajar hasta el suelo que tiene debajo
     * (con margen), y vigilarCaidas() se la renueva mientras siga en el aire, por si cae
     * por un barranco. La duracion inicial ya cubre la bajada entera: si el servidor se
     * para a mitad, el efecto se guarda con el jugador y le sigue protegiendo al volver.
     */
    private void caerDespacio(Player p) {
        int ticks = Math.max(CAIDA_LENTA_TICKS, Math.min(CAIDA_LENTA_MAX_TICKS, ticksHastaElSuelo(p)));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, ticks, 0, false, false, true));
        cayendo.put(p.getUniqueId(), System.currentTimeMillis() + CAIDA_LENTA_MAX_TICKS * 50L);
    }

    /**
     * Lo que tarda en bajar con caida lenta hasta el primer bloque que le pare (o el agua),
     * mirando la columna de debajo; sin nada debajo, hasta el fondo del mundo. Se suman 5 s
     * por lo que tarda en coger velocidad y de margen. Solo se llama al quitar el vuelo.
     */
    static int ticksHastaElSuelo(Player p) {
        Location l = p.getLocation();
        World w = l.getWorld();
        if (w == null) return CAIDA_LENTA_MAX_TICKS;
        int x = l.getBlockX(), z = l.getBlockZ();
        int suelo = w.getMinHeight();
        for (int y = Math.min(l.getBlockY(), w.getMaxHeight() - 1); y >= w.getMinHeight(); y--) {
            Block b = w.getBlockAt(x, y, z);
            if (!b.isPassable() || b.isLiquid()) {
                suelo = y + 1;
                break;
            }
        }
        double altura = Math.max(0, l.getY() - suelo);
        return (int) Math.ceil(altura / VELOCIDAD_CAIDA_LENTA) + 100;
    }

    /**
     * Cada segundo: a quien se quedo sin vuelo en el aire se le renueva la caida lenta
     * mientras no toque suelo ni agua. Al aterrizar, al volver a volar, al cambiar a
     * creativo o pasado el tope se le deja de vigilar; lo que le quede de efecto se acaba
     * solo (no se le quita: podria ser de una pocion suya).
     */
    void vigilarCaidas() {
        if (cayendo.isEmpty()) return;
        long ahora = System.currentTimeMillis();
        cayendo.entrySet().removeIf(en -> {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || ahora > en.getValue() || !nuestroModo(p) || p.getAllowFlight()) return true;
            if (p.isOnGround() || p.isInWater() || p.isInLava()) return true;
            PotionEffect actual = p.getPotionEffect(PotionEffectType.SLOW_FALLING);
            if (actual == null || (!actual.isInfinite() && actual.getDuration() < 60)) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0, false, false, true));
            }
            return false;
        });
    }

    /** Si tiene vuelo nuestro apuntado (de antes de un reinicio o de otra sesion). */
    boolean apuntado(Player p) {
        return plugin.registro().vuelos().contains(p.getUniqueId());
    }

    @Override
    void alSalir(Player p) {
        UUID id = p.getUniqueId();
        gracia.remove(id);
        cayendo.remove(id);   // la caida lenta que tenga se guarda con el y le protege al volver
        // En el suelo se le quita ya: no hay caida posible, y asi no queda nadie con el vuelo
        // guardado en su ficha si el modulo no vuelve a arrancar (modulos.superbeacon: false).
        // En el aire se queda apuntado a proposito (ver arriba) y lo resuelve el ciclo al volver.
        if (apuntado(p) && nuestroModo(p) && p.getAllowFlight() && !p.isFlying() && p.isOnGround()) {
            p.setAllowFlight(false);
            plugin.registro().vuelo(id, false);
        }
    }

    @Override
    void parar() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (apuntado(p)) quitarYa(p, false);
        }
        gracia.clear();
        cayendo.clear();
    }
}
