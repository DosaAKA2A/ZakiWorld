package net.ederus.edm.superbeacon;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;

/**
 * Un Super Beacon colocado en el mundo: su {@link Ficha} y el sitio donde esta.
 *
 * El dueño, el clan fijado (el del give) y la caducidad no cambian mientras esta colocado
 * y viajan con el objeto al recogerlo. Lo que se toca en caliente: la eleccion de efectos
 * (su menu), el UUID del dueño si se entrego por nombre a quien aun no habia entrado (se
 * rellena cuando entra), su nombre si se lo cambio, y la cache del clan de su dueño.
 *
 * El material se guarda tal cual quedo en el mundo, no el del tipo: si manana alguien
 * cambia el bloque de un tipo en el config, las balizas que ya estaban siguen siendo de
 * su material y la revision no las da por desaparecidas.
 */
final class Baliza {

    final UUID id;
    final String tipo;
    UUID dueno;
    /** Se pone al dia al entrar su dueño (Registro.ligar): con UUID estable, el nombre puede cambiar. */
    String duenoNombre;
    /** El clan fijado en el give (el trofeo): manda siempre y viaja con el objeto. null si no hay. */
    final String clan;
    /**
     * Sin clan fijado, el que beneficia es el clan ACTUAL de su dueño (ver Motor.clanDe). Esto
     * es solo su cache para cuando no esta conectado: se guarda en data.yml y NUNCA se escribe
     * en el objeto, asi que si el dueño cambia de clan, su baliza cambia con el.
     */
    String clanDueno;
    /** 1.78.1: ya no es final; la renueva SuperBeaconPlugin.renovar (el trofeo del clan que repite). */
    long vence;
    final LinkedHashSet<String> elegidos;

    final String mundo;
    final int x;
    final int y;
    final int z;
    final Material material;
    final long colocada;

    /** Ya se le dijo al dueño que vencio (se guarda: un reinicio no repite el aviso). */
    boolean avisoVencida;

    /* --- de paso, no se guardan --- */

    /** Los efectos en marcha, calculados para este tipo; se rehacen si cambia el tipo o la eleccion. */
    List<Efecto> cacheActivos;
    TipoBaliza cacheTipo;
    /** Cuando toca la siguiente particula del tipo, si trae. */
    long proximaParticula;
    /** Ya se vio vencida en esta sesion (se repinto el holograma y se anoto). */
    boolean vistaVencida;

    Baliza(Ficha f, String mundo, int x, int y, int z, Material material, long colocada) {
        this.id = f.id();
        this.tipo = f.tipo();
        this.dueno = f.dueno();
        this.duenoNombre = f.duenoNombre();
        this.clan = f.clan();
        this.vence = f.vence();
        this.elegidos = new LinkedHashSet<>(f.elegidos());
        this.mundo = mundo;
        this.x = x;
        this.y = y;
        this.z = z;
        this.material = material;
        this.colocada = colocada;
    }

    /** Lo que se escribe en el objeto al recogerla o devolverla. */
    Ficha ficha() {
        return new Ficha(id, tipo, dueno, duenoNombre, clan, vence, List.copyOf(elegidos));
    }

    boolean vencida(long ahora) {
        return vence > 0 && ahora >= vence;
    }

    boolean esDe(UUID jugador) {
        return dueno != null && dueno.equals(jugador);
    }

    /**
     * Si ese punto esta en su alcance: un circulo de radio bloques alrededor del bloque y,
     * en vertical, desde radio bloques por debajo hasta el cielo, como el haz de un faro.
     * Sin techo a proposito: con vuelo, un techo tiraria al jugador al subir.
     */
    boolean dentro(double px, double py, double pz, int radio) {
        if (py < y - radio) return false;
        double dx = px - (x + 0.5), dz = pz - (z + 0.5);
        return dx * dx + dz * dz <= (double) radio * radio;
    }

    boolean dentro(Location l, int radio) {
        return l.getWorld() != null && l.getWorld().getName().equals(mundo)
                && dentro(l.getX(), l.getY(), l.getZ(), radio);
    }

    /** Los efectos cambiaron (menu) o el tipo se recargo: que se rehagan. */
    void olvidarCache() {
        cacheActivos = null;
        cacheTipo = null;
    }

    String idCorto() {
        return id.toString().substring(0, 8);
    }

    String donde() {
        return mundo + " " + x + " " + y + " " + z;
    }

    String duenoTexto() {
        if (duenoNombre != null) return duenoNombre;
        return dueno == null ? "-" : dueno.toString().substring(0, 8);
    }
}
