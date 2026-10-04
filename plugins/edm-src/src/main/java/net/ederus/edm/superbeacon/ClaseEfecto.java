package net.ederus.edm.superbeacon;

import java.util.Collection;
import java.util.function.Consumer;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Una clase de efecto: pocion, atributo, boost, vuelo, cultivos, sin-mobs.
 *
 * Es el punto de extension del modulo. Una clase nueva es una subclase de esta y una
 * linea en {@link SuperBeaconPlugin#registrarClases()}; nada mas cambia. Cada clase:
 *   - lee sus parametros del config y dice que esta mal (leer);
 *   - dice si se aplica a cada JUGADOR que alcanza la baliza o a la ZONA entera;
 *   - y, segun eso, sabe ponerse y quitarse (aplicar/quitar) o actuar sobre la zona
 *     (enZona), con su propio estado por jugador si lo necesita.
 *
 * El motor (ver {@link Motor}) no sabe nada de pociones ni de atributos: junta los
 * efectos de las balizas que alcanzan a cada jugador, se queda con el mayor de cada grupo
 * y llama a aplicar() con el ganador y a quitar() con lo que dejo de recibir.
 *
 * Todo se llama en el hilo principal.
 */
abstract class ClaseEfecto {

    protected final SuperBeaconPlugin plugin;
    private final String id;

    protected ClaseEfecto(SuperBeaconPlugin plugin, String id) {
        this.plugin = plugin;
        this.id = id;
    }

    /** Lo que se escribe en tipo: del config ("pocion"). */
    final String id() {
        return id;
    }

    /**
     * Lee un efecto de esta clase. Lo que este mal se cuenta por error (ya lleva delante
     * de que tipo y efecto es); null si el efecto no se puede usar de ninguna forma.
     */
    abstract Efecto leer(String clave, String nombre, Material icono, ConfigurationSection s, Consumer<String> error);

    /** El icono si el config no trae uno o trae uno que no existe. */
    abstract Material icono();

    /** true: se aplica a cada jugador que alcanza. false: actua sobre la zona entera. */
    abstract boolean porJugador();

    /** Por que ese efecto no se puede usar ahora mismo, o null. */
    String falta(Efecto e) {
        return null;
    }

    /**
     * Lo que ganas y donde, en una frase para el menu (sin color: el menu le pone el suyo y
     * la parte en lineas). Se genera con los datos del efecto, asi vale para cualquier
     * efecto del config y no solo para los de serie.
     */
    String que(Efecto e) {
        return "";
    }

    /** En que seccion va en el lore: Presentacion.VIDA, MOVIMIENTO o BOOST. */
    int seccion(Efecto e) {
        return Presentacion.MOVIMIENTO;
    }

    /* ---------------------------------------------------- las de jugador */

    /** Lo recibe (o lo sigue recibiendo): cada 2 s mientras este en el alcance. Idempotente. */
    void aplicar(Player p, Efecto e) {
    }

    /** Dejo de recibirlo: salio del alcance, se recogio la baliza, vencio... */
    void quitar(Player p, Efecto e) {
    }

    /** Se desconecto: lo que la clase tenga suyo del jugador. */
    void alSalir(Player p) {
    }

    /* ------------------------------------------------------ las de zona */

    /** Cada segundo, por cada baliza activa con este efecto y con su chunk cargado. */
    void enZona(Baliza b, TipoBaliza t, Efecto e, World w, long ahora) {
    }

    /** Las balizas cambiaron (se puso, se quito, se eligio otro efecto, se recargo). */
    void reindexar(Collection<Baliza> balizas) {
    }

    /* -------------------------------------------------------------- vida */

    void arrancar() {
    }

    /** El modulo se para: quitar a todos lo que esta clase les dio. */
    void parar() {
    }
}
