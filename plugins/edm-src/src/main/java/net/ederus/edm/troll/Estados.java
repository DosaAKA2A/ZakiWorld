package net.ederus.edm.troll;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Lo que esta activo sobre cada jugador y, sobre todo, como se quita.
 *
 * Es la pieza que hace que este modulo sea seguro. Una broma que dura un rato
 * (congelado, mudo, sin minar, encerrado en cristal) deja al jugador en un
 * estado que NO es el suyo, y si el plugin se recarga, el servidor se reinicia o
 * el admin se despista, ese jugador se queda asi para siempre.
 *
 * Por eso todo lo que se pone aqui trae su propio "deshacer" y se ejecuta si o
 * si: al acabarse el tiempo, al desconectarse el jugador y al apagar el modulo.
 * Si una broma no sabe deshacerse, no es temporal: es destructiva y va por el
 * otro camino.
 *
 * Cada efecto se guarda con SU clave (el id de la broma), no con la marca: dos
 * bromas distintas pueden dejar dos vueltas atras pendientes a la vez y una no
 * puede pisar la de la otra.
 */
public final class Estados {

    /** Lo que miran los listeners. Varias bromas pueden compartir marca. */
    public enum Marca {
        CONGELADO,      // no se puede mover del sitio
        MUDO,           // no le sale nada por el chat
        SIN_MINAR,      // no puede romper bloques
        CORRIENDO,      // se le empuja hacia delante
        SIN_RECOGER,    // no puede recoger del suelo
        PATATA,         // lo que rompe suelta ademas una patata
        SIN_CAIDA,      // no recibe daño de caida (la red de las bromas de altura)
        INVERTIDO       // se le da la vuelta cada poco
    }

    private record Activo(String clave, Marca marca, long hasta, Runnable deshacer) { }

    private final Plugin plugin;
    private final Logger log;
    /*
     * Concurrente y no LinkedHashMap: la marca MUDO se consulta desde
     * AsyncChatEvent, que corre en OTRO hilo, mientras el hilo principal pone,
     * quita y repasa bromas. Leer un LinkedHashMap mientras se reestructura es
     * la clase de cosa que se cuelga una vez cada mil y no hay quien la repita.
     */
    private final Map<UUID, Map<String, Activo>> porJugador = new java.util.concurrent.ConcurrentHashMap<>();

    public Estados(Plugin plugin, Logger log) {
        this.plugin = plugin;
        this.log = log;
    }

    /**
     * Apunta un efecto con su vuelta atras. 'clave' identifica ESTE efecto (el
     * id de la broma); si se repite la misma broma sobre el mismo jugador, se
     * deshace la anterior antes, que si no quedan dos a medias.
     *
     * 'marca' puede ser null: hay bromas que no necesitan que ningun listener
     * las mire, solo que alguien se acuerde de deshacerlas.
     */
    public void poner(Player quien, String clave, Marca marca, int segundos, Runnable deshacer) {
        quitar(quien.getUniqueId(), clave);
        long hasta = System.currentTimeMillis() + Math.max(1, segundos) * 1000L;
        porJugador.computeIfAbsent(quien.getUniqueId(), k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(clave, new Activo(clave, marca, hasta, deshacer));
    }

    public boolean tiene(Player quien, Marca marca) {
        Map<String, Activo> suyas = porJugador.get(quien.getUniqueId());
        if (suyas == null) return false;
        for (Activo a : suyas.values()) if (a.marca() == marca) return true;
        return false;
    }

    /** Lo que le queda a la mas larga de esa marca, en segundos. */
    public long restante(Player quien, Marca marca) {
        Map<String, Activo> suyas = porJugador.get(quien.getUniqueId());
        if (suyas == null) return 0;
        long max = 0;
        for (Activo a : suyas.values()) {
            if (a.marca() == marca) max = Math.max(max, a.hasta() - System.currentTimeMillis());
        }
        return Math.max(0, max / 1000);
    }

    /** Las bromas que tiene encima ahora mismo, por su clave. */
    public List<String> activas(UUID quien) {
        Map<String, Activo> suyas = porJugador.get(quien);
        return suyas == null ? List.of() : new ArrayList<>(suyas.keySet());
    }

    public void quitar(UUID quien, String clave) {
        Map<String, Activo> suyas = porJugador.get(quien);
        if (suyas == null) return;
        Activo a = suyas.remove(clave);
        if (suyas.isEmpty()) porJugador.remove(quien);
        ejecutar(a);
    }

    /** Todo lo de un jugador. Es lo que se llama al desconectarse y en /troll deshacer. */
    public int quitarTodo(UUID quien) {
        Map<String, Activo> suyas = porJugador.remove(quien);
        if (suyas == null) return 0;
        suyas.values().forEach(this::ejecutar);
        return suyas.size();
    }

    /** Todo lo de todos. Al apagar el modulo, sin excepciones. */
    public int quitarTodo() {
        int n = 0;
        for (Map<String, Activo> suyas : new java.util.ArrayList<>(porJugador.values())) {
            suyas.values().forEach(this::ejecutar);
            n += suyas.size();
        }
        porJugador.clear();
        return n;
    }

    public int cuantosJugadores() { return porJugador.size(); }

    /**
     * El deshacer NO puede tumbar nada.
     *
     * Si una vuelta atras revienta (el jugador ya no esta, el trozo de mundo ya
     * no esta cargado), lo que no puede pasar es que se lleve por delante las de
     * los demas: se anota y se sigue con la siguiente.
     */
    private void ejecutar(Activo a) {
        if (a == null || a.deshacer() == null) return;
        try {
            a.deshacer().run();
        } catch (Throwable t) {
            log.warning("Fallo deshaciendo la broma " + a.clave() + ": " + t);
        }
    }

    /** Caduca lo que se haya pasado de tiempo. Lo llama una tarea cada segundo. */
    public void repasar() {
        long ahora = System.currentTimeMillis();
        for (var entrada : new java.util.ArrayList<>(porJugador.entrySet())) {
            Map<String, Activo> suyas = porJugador.get(entrada.getKey());
            if (suyas == null) continue;
            for (Activo a : new ArrayList<>(suyas.values())) {
                if (a.hasta() > ahora) continue;
                /* Se deshace igual aunque el jugador ya no este: la vuelta atras
                 * no depende de el (un cubo de cristal hay que quitarlo). */
                ejecutar(suyas.remove(a.clave()));
            }
            if (suyas.isEmpty()) porJugador.remove(entrada.getKey());
        }
    }

    public Plugin plugin() { return plugin; }

    /** Solo para el aviso de arranque y los mensajes de admin. */
    public static Player conectado(UUID quien) { return Bukkit.getPlayer(quien); }
}
