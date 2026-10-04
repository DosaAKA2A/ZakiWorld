package net.ederus.edm.superbeacon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * Y dos frenos, sin depender de ningun plugin:
 *   - combate: quien da o recibe un golpe PvP pierde nuestro vuelo durante
 *     vuelo.sin-vuelo-en-combate-segundos (cada golpe renueva la cuenta), con caida segura;
 *   - respeto: si OTRO plugin le quita el vuelo que le dimos (una region sin vuelo, un plugin
 *     de combate, /fly) mientras sigue en el alcance, no se lo devolvemos hasta pasados
 *     vuelo.reintento-segundos. Lo que le quita el propio juego (reaparecer, cambiar de modo)
 *     no cuenta: eso se le devuelve en el siguiente ciclo.
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
    /** jugador -> hasta cuando (ms) no puede tener nuestro vuelo por pelear. Sobrevive a salir y entrar. */
    private final Map<UUID, Long> combate = new HashMap<>();
    /** jugador -> hasta cuando (ms) no se le devuelve: otro plugin se lo quito. */
    private final Map<UUID, Long> reintento = new HashMap<>();
    /** Apuntados a los que el propio juego les quito el vuelo (reaparecer, cambiar de modo). */
    private final Set<UUID> reseteados = new HashSet<>();
    private int combateSegundos = 15;
    private int reintentoSegundos = 30;

    ClaseVuelo(SuperBeaconPlugin plugin) {
        super(plugin, "vuelo");
    }

    /** vuelo.sin-vuelo-en-combate-segundos y vuelo.reintento-segundos del config; 0 apaga cada uno. */
    void configurar(int combateSegundos, int reintentoSegundos) {
        this.combateSegundos = Math.max(0, combateSegundos);
        this.reintentoSegundos = Math.max(0, reintentoSegundos);
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
    String que(Efecto e) {
        String s = plugin.textos().crudo("detalle-vuelo",
                "Puedes volar dentro de su alcance. Al salir tienes 5 segundos antes de caer despacio.");
        if (combateSegundos > 0) {
            s += " " + plugin.textos().crudo("detalle-vuelo-combate",
                    "Si peleas con otro jugador, pierdes el vuelo %segundos% segundos.")
                    .replace("%segundos%", String.valueOf(combateSegundos));
        }
        return s;
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
        boolean delJuego = reseteados.remove(id);
        if (!nuestroModo(p)) {
            // En creativo o espectador el juego manda; si era nuestro, deja de serlo.
            plugin.registro().vuelo(id, false);
            return;
        }
        long ahora = System.currentTimeMillis();
        boolean apuntado = plugin.registro().vuelos().contains(id);
        if (apuntado && !p.getAllowFlight()) {
            // Se lo quitaron sin pasar por aqui. El juego, se le devuelve; otro plugin, se respeta.
            if (quitadoPorOtro(true, false, delJuego)) {
                long hasta = marca(ahora, reintentoSegundos);
                if (hasta > 0) reintento.put(id, hasta);
            }
            plugin.registro().vuelo(id, false);
            // Si le pillo en el aire (al entrar, un cambio de modo, otro plugin), que no se mate:
            // la caida lenta tambien borra lo que ya llevara cayendo.
            if (enElAire(p)) caerDespacio(p);
        }
        if (bloqueado(ahora, combate.get(id)) || bloqueado(ahora, reintento.get(id))) return;
        if (!p.getAllowFlight()) {
            p.setAllowFlight(true);
            plugin.registro().vuelo(id, true);
        }
        // Si ya podia volar y no esta apuntado, el vuelo es de otro: ni se apunta ni se toca.
    }

    /**
     * Pelea PvP: sin nuestro vuelo durante vuelo.sin-vuelo-en-combate-segundos (cada golpe
     * renueva la cuenta). Si volaba con el nuestro, se le quita ya, con caida segura.
     */
    void combatir(Player p) {
        long hasta = marca(System.currentTimeMillis(), combateSegundos);
        if (hasta <= 0) return;
        combate.put(p.getUniqueId(), hasta);
        if (apuntado(p) && nuestroModo(p) && p.getAllowFlight()) {
            quitarYa(p, false);
            plugin.textos().manda(p, "vuelo-combate",
                    "&#FFB627En combate: &fsin vuelo del Super Beacon &#FFB627durante %segundos% segundos.",
                    "%segundos%", String.valueOf(combateSegundos));
        }
    }

    /** El juego le quito el vuelo (reaparecer, cambiar de modo): no es otro plugin, no se espera. */
    void reseteadoPorElJuego(Player p) {
        if (apuntado(p)) reseteados.add(p.getUniqueId());
    }

    /** Cada 5 s: fuera las marcas ya cumplidas. */
    void podar(long ahora) {
        combate.values().removeIf(h -> !bloqueado(ahora, h));
        reintento.values().removeIf(h -> !bloqueado(ahora, h));
    }

    /* ------------------------------------------- reglas puras (selftest) */

    /** Hasta cuando dura una marca de esos segundos; 0 si esta apagada (0 segundos o menos). */
    static long marca(long ahora, int segundos) {
        return segundos <= 0 ? 0L : ahora + segundos * 1000L;
    }

    /** Si una marca sigue en pie. */
    static boolean bloqueado(long ahora, Long hasta) {
        return hasta != null && hasta > ahora;
    }

    /** Era nuestro, ya no puede volar y no fue el juego: se lo quito otro plugin. */
    static boolean quitadoPorOtro(boolean apuntado, boolean puedeVolar, boolean reseteoDelJuego) {
        return apuntado && !puedeVolar && !reseteoDelJuego;
    }

    @Override
    void quitar(Player p, Efecto e) {
        UUID id = p.getUniqueId();
        reseteados.remove(id);
        if (!plugin.registro().vuelos().contains(id)) return;
        if (!nuestroModo(p) || !p.getAllowFlight()) {
            // Nada que quitar: el juego u otro plugin ya se lo quito. Si fue en el aire, caida segura.
            plugin.registro().vuelo(id, false);
            if (nuestroModo(p) && enElAire(p)) caerDespacio(p);
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

    /** Sin suelo debajo (y sin volar: a quien se lo quitaron ya no vuela). */
    private static boolean enElAire(Player p) {
        return !p.isOnGround() && p.getLocation().subtract(0, 0.2, 0).getBlock().isPassable();
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
        reseteados.remove(id);
        // La marca de combate (y la de reintento) se quedan: salir y entrar no las borra.
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
        combate.clear();
        reintento.clear();
        reseteados.clear();
    }
}
