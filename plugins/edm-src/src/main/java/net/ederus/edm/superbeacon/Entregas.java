package net.ederus.edm.superbeacon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Las mudanzas de un Super Beacon entre sus tres casas: objeto, bloque y pendiente.
 *
 * La regla que manda en todo este fichero: un id existe en UN solo sitio a la vez, y
 * nunca en ninguno. Para eso cada mudanza sigue el mismo orden:
 *
 *   1. se apunta el estado nuevo en el registro y se GUARDA data.yml;
 *   2. se toca el mundo (el bloque a aire, el holograma fuera, los menus cerrados);
 *   3. se da el objeto, y solo si ya esta en el inventario se borra el pendiente.
 *
 * Si el servidor cae entre 1 y 3, la baliza esta en data.yml como pendiente y le llega a
 * su dueño al volver. Al reves (dar primero y guardar despues) una caida regalaria una
 * copia o se comeria la baliza.
 *
 * Contra los dos clics en el mismo tick (romperla mientras otro le da a Recoger, dos
 * Recoger seguidos) hay un cerrojo por id y cada paso vuelve a mirar que la baliza siga
 * siendo la que era. Y al entregar, si el jugador ya tiene un objeto con ese id (una caida
 * del servidor le devolvio el inventario de antes), el pendiente se descarta en vez de
 * duplicarse.
 */
final class Entregas {

    private final SuperBeaconPlugin plugin;
    /** Ids con una mudanza en marcha. */
    private final Set<UUID> enCurso = new HashSet<>();
    /** A quien ya se le dijo en esta sesion que tiene pendientes, para no repetirlo cada 5 s. */
    private final Set<UUID> avisados = new HashSet<>();

    Entregas(SuperBeaconPlugin plugin) {
        this.plugin = plugin;
    }

    private Registro registro() {
        return plugin.registro();
    }

    /* ================================================================== give */

    /**
     * /superbeacon give: crea una baliza nueva (id nuevo) y se la da, o la deja pendiente
     * si no esta o no tiene hueco. Contesta con una linea clara para el log de la consola.
     */
    void dar(CommandSender quien, String nombre, TipoBaliza t, Double dias, String clan) {
        Player p = Bukkit.getPlayerExact(nombre);
        UUID uuid = null;
        String nombreReal = nombre;
        if (p != null) {
            uuid = p.getUniqueId();
            nombreReal = p.getName();
        } else {
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(nombre);
            if (op != null) {
                uuid = op.getUniqueId();
                if (op.getName() != null) nombreReal = op.getName();
            }
        }
        long ahora = System.currentTimeMillis();
        double d = dias != null ? dias : t.duracionDias;
        long vence = d > 0 ? ahora + Math.max(1000L, Math.round(d * Tiempo.DIA)) : 0L;
        // Transferible: sin dueño hasta que alguien la coloque. Si no, de quien la recibe.
        Ficha f = new Ficha(UUID.randomUUID(), t.id, t.transferible ? null : uuid,
                t.transferible ? null : nombreReal, clan, vence, List.of());
        Pendiente pe = new Pendiente(f, t.bloque, uuid, nombreReal, "give", ahora);
        registro().pendiente(pe);
        registro().guardar();

        String venceTxt = vence == 0 ? plugin.textos().crudo("permanente", "Permanente")
                : "vence " + Tiempo.fecha(vence, plugin.zona());
        String clanTxt = clan == null ? "" : " · clan " + clan;
        plugin.anotar("give", f.id().toString(), t.id, nombreReal, venceTxt, clan == null ? "-" : clan,
                "por " + quien.getName());

        boolean conectado = p != null;
        if (conectado && entregar(p, pe, false)) {
            plugin.textos().manda(quien, "give-entregado",
                    "&fEntregado %nombre% &fa &#D7F3FF%jugador%&f. &7id %id% · %vence%%clan%",
                    "%nombre%", t.nombre, "%jugador%", nombreReal, "%id%", f.id().toString().substring(0, 8),
                    "%vence%", venceTxt, "%clan%", clanTxt);
            plugin.textos().manda(p, "entregado", "&fRecibiste %nombre%&f.", "%nombre%", t.nombre);
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.2f);
            return;
        }
        String motivo = conectado ? plugin.textos().crudo("motivo-lleno", "no tiene espacio")
                : plugin.textos().crudo("motivo-desconectado", "no está conectado");
        plugin.textos().manda(quien, "give-pendiente",
                "&f%nombre% &fpara &#D7F3FF%jugador% &7queda pendiente (%motivo%). id %id% · %vence%%clan%",
                "%nombre%", t.nombre, "%jugador%", nombreReal, "%motivo%", motivo,
                "%id%", f.id().toString().substring(0, 8), "%vence%", venceTxt, "%clan%", clanTxt);
        if (conectado) avisarPendientes(p, true);
    }

    /* ============================================================== entregar */

    /**
     * Da un pendiente a ese jugador si cabe; true si quedo en su inventario. Antes de dar
     * mira que la baliza no este ya colocada ni en su inventario: en los dos casos el
     * pendiente sobra y se descarta (nunca dos copias del mismo id).
     */
    boolean entregar(Player p, Pendiente pe, boolean avisar) {
        UUID id = pe.ficha.id();
        if (registro().pendiente(id) != pe) return false;
        if (registro().porId(id) != null) {
            descartar(pe, "ya esta colocada");
            return false;
        }
        if (loTiene(p, id)) {
            descartar(pe, "ya la tenia " + p.getName() + " en el inventario");
            return false;
        }
        if (p.getInventory().firstEmpty() == -1) return false;
        Ficha f = pe.ficha;
        if (f.dueno() == null && f.duenoNombre() != null && f.duenoNombre().equalsIgnoreCase(p.getName())) {
            f = f.conDueno(p.getUniqueId(), p.getName());   // entregada por nombre: ya sabemos su UUID
        }
        ItemStack it = plugin.objeto().crear(f, pe.material);
        Map<Integer, ItemStack> sobra = p.getInventory().addItem(it);
        if (!sobra.isEmpty()) return false;
        registro().quitarPendiente(id);
        registro().guardar();
        plugin.anotar("entregada", id.toString(), f.tipo(), p.getName(), pe.motivo);
        if (avisar) {
            plugin.textos().manda(p, "entregado", "&fRecibiste %nombre%&f.", "%nombre%", nombreDe(f.tipo()));
        }
        return true;
    }

    private void descartar(Pendiente pe, String motivo) {
        registro().quitarPendiente(pe.ficha.id());
        registro().guardar();
        plugin.anotar("pendiente-descartado", pe.ficha.id().toString(), pe.ficha.tipo(), pe.paraTexto(), motivo);
    }

    /** Tiene en el inventario (o en la mano, o puesto) un objeto con ese id. */
    boolean loTiene(Player p, UUID id) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (id.equals(plugin.objeto().id(it))) return true;
        }
        return false;
    }

    /** Todo lo suyo que quepa. Al entrar, con /superbeacon y en la revision. */
    void entregarTodo(Player p) {
        for (Pendiente pe : registro().pendientesDe(p)) entregar(p, pe, true);
        avisarPendientes(p, false);
    }

    /** Cada 5 s: lo pendiente de los que estan conectados. */
    void repartir() {
        if (registro().pendientes().isEmpty()) return;
        Set<UUID> tocados = new HashSet<>();
        for (Pendiente pe : new ArrayList<>(registro().pendientes())) {
            Player p = pe.para != null ? Bukkit.getPlayer(pe.para)
                    : pe.paraNombre != null ? Bukkit.getPlayerExact(pe.paraNombre) : null;
            if (p == null) continue;
            entregar(p, pe, true);
            tocados.add(p.getUniqueId());
        }
        for (UUID u : tocados) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) avisarPendientes(p, false);
        }
    }

    /** Le dice cuantos le esperan, una vez por sesion (o siempre, si forzar). */
    void avisarPendientes(Player p, boolean forzar) {
        int n = registro().pendientesDe(p).size();
        if (n == 0) {
            avisados.remove(p.getUniqueId());
            return;
        }
        if (!forzar && !avisados.add(p.getUniqueId())) return;
        avisados.add(p.getUniqueId());
        plugin.textos().manda(p, "pendientes",
                "&7Tienes &f%n% &7Super Beacon(s) esperándote. &7Libera espacio en el inventario para recibirlos.",
                "%n%", String.valueOf(n));
    }

    void olvidarAviso(UUID jugador) {
        avisados.remove(jugador);
    }

    /* ================================================================ colocar */

    /**
     * Si ese jugador puede colocar esa ficha ahi. Si no, se lo explica y devuelve false.
     * Se llama en BlockPlaceEvent (HIGH, despues de las protecciones).
     *
     * reemplazado es lo que habia en ese bloque antes de colocar: si el registro aun tiene
     * ahi una baliza vieja (su bloque desaparecio y la revision de 5 s no la vio todavia),
     * se da por desaparecida ahora, que si no la nueva no se podria apuntar en su sitio.
     */
    boolean puedeColocar(Player p, Ficha f, Block donde, Material reemplazado) {
        Baliza vieja = registro().en(donde);
        if (vieja != null && reemplazado != vieja.material) desaparecida(vieja, reemplazado);
        if (registro().en(donde) != null) return false;

        TipoBaliza t = plugin.tipo(f.tipo());
        if (t == null) {
            plugin.textos().manda(p, "colocar-tipo",
                    "&#FF5C5CEste Super Beacon es de un tipo que ya no existe (&f%tipo%&#FF5C5C). &7Avisa al staff.",
                    "%tipo%", f.tipo());
            return false;
        }
        if (plugin.mundoExcluido(donde.getWorld().getName())) {
            plugin.textos().manda(p, "colocar-mundo", "&#FF5C5CEn este mundo no se pueden colocar Super Beacons.");
            return false;
        }
        if (plugin.enMina(donde)) {
            // Las minas dejan picar lo que sea aunque otro lo cancele (minas/Guardia, HIGHEST):
            // ahi dentro cualquiera sacaria un faro vanilla de la baliza de otro.
            plugin.textos().manda(p, "colocar-mina", "&#FF5C5CNo se pueden colocar Super Beacons dentro de una mina.");
            return false;
        }
        Baliza ya = registro().porId(f.id());
        if (ya != null) {
            plugin.textos().manda(p, "colocar-repetido", "&#FF5C5CEste Super Beacon ya está colocado en otro lugar.");
            plugin.anotar("repetida", f.id().toString(), f.tipo(), p.getName(), "ya colocada en " + ya.donde());
            return false;
        }
        boolean admin = plugin.esAdmin(p);
        Pendiente pe = registro().pendiente(f.id());
        if (pe != null && !pe.esPara(p) && !admin) {
            plugin.textos().manda(p, "colocar-reservado", "&#FF5C5CEste Super Beacon está reservado para otro jugador.");
            plugin.anotar("reservada", f.id().toString(), f.tipo(), p.getName(), "pendiente para " + pe.paraTexto());
            return false;
        }
        if (!admin && !esSuDueno(f, p)) {
            plugin.textos().manda(p, "colocar-ajeno",
                    "&#FF5C5CEste Super Beacon es de &f%dueno%&#FF5C5C: solo su dueño puede colocarlo.",
                    "%dueno%", f.duenoTexto());
            return false;
        }
        if (f.vencida(System.currentTimeMillis()) && t.alCaducar == TipoBaliza.AlCaducar.DESTRUIR) {
            plugin.textos().manda(p, "colocar-vencido", "&#FF5C5CEste Super Beacon venció el &f%fecha%&#FF5C5C.",
                    "%fecha%", Tiempo.fecha(f.vence(), plugin.zona()));
            return false;
        }
        int max = plugin.maximoPorJugador();
        if (!admin && t.cuentaEnElMaximo && max > 0) {
            UUID dueno = f.dueno() != null ? f.dueno() : p.getUniqueId();
            int tiene = registro().cuantasDe(dueno, b -> {
                TipoBaliza tb = plugin.tipo(b.tipo);
                return tb != null && tb.cuentaEnElMaximo;
            });
            if (tiene >= max) {
                plugin.textos().manda(p, "colocar-maximo",
                        "&#FF5C5CYa tienes &f%max% &#FF5C5CSuper Beacons colocados. &7Recoge uno antes de poner otro.",
                        "%max%", String.valueOf(max));
                return false;
            }
        }
        return true;
    }

    private static boolean esSuDueno(Ficha f, Player p) {
        if (!f.ligada()) return true;
        if (f.dueno() != null) return f.dueno().equals(p.getUniqueId());
        return f.duenoNombre().equalsIgnoreCase(p.getName());
    }

    /**
     * La coloca de verdad: se llama en BlockPlaceEvent MONITOR, cuando ya nadie la va a
     * cancelar. Fija el dueño si no lo tenia (transferible: pasa a ser de quien la coloca)
     * y el clan del dueño si el tipo es de clan y no venia fijado.
     */
    void colocar(Player p, Ficha f, Block bl) {
        TipoBaliza t = plugin.tipo(f.tipo());
        if (t == null || registro().porId(f.id()) != null) return;
        long ahora = System.currentTimeMillis();

        UUID dueno;
        String duenoNombre;
        if (f.dueno() != null) {
            dueno = f.dueno();
            duenoNombre = f.duenoNombre() != null ? f.duenoNombre()
                    : dueno.equals(p.getUniqueId()) ? p.getName() : null;
        } else if (f.duenoNombre() != null && !f.duenoNombre().equalsIgnoreCase(p.getName())) {
            // El staff coloca una ligada por nombre a quien aun no entro: se queda con ese
            // nombre y el UUID se rellena cuando entre (Registro.ligar).
            dueno = null;
            duenoNombre = f.duenoNombre();
        } else {
            dueno = p.getUniqueId();
            duenoNombre = p.getName();
        }
        String clan = f.clan();
        if (clan == null && t.beneficia == TipoBaliza.Beneficia.CLAN && dueno != null) clan = plugin.clanes().de(dueno);

        Ficha fija = new Ficha(f.id(), f.tipo(), dueno, duenoNombre, clan, f.vence(), plugin.normalizados(f.elegidos(), t));
        Baliza b = new Baliza(fija, bl.getWorld().getName(), bl.getX(), bl.getY(), bl.getZ(), bl.getType(), ahora);
        if (!registro().poner(b)) {
            // No deberia pasar (se comprobo en HIGH). Si pasa, la baliza no se pierde: vuelve
            // como pendiente y el bloque, que ya no tiene baliza detras, se quita.
            registro().pendiente(new Pendiente(fija, b.material, dueno, duenoNombre, "colocacion-fallida", ahora));
            registro().guardar();
            plugin.getLogger().warning("[SuperBeacon] No se pudo apuntar el Super Beacon " + b.idCorto() + " en "
                    + b.donde() + "; vuelve a su dueño como pendiente.");
            plugin.anotar("colocacion-fallida", b.id.toString(), b.tipo, b.duenoTexto(), b.donde());
            Bukkit.getScheduler().runTask(plugin.core(), () -> {
                if (registro().en(bl) == null && bl.getType() == b.material) bl.setType(Material.AIR);
                Player d = dueno == null ? null : Bukkit.getPlayer(dueno);
                Pendiente pe = registro().pendiente(b.id);
                if (d != null && pe != null) entregar(d, pe, true);
            });
            return;
        }
        Pendiente pe = registro().quitarPendiente(f.id());
        if (pe != null) {
            plugin.anotar("pendiente-descartado", f.id().toString(), f.tipo(), pe.paraTexto(), "se coloco el objeto");
        }
        registro().guardar();
        plugin.motor().reindexar();
        // Un tick despues: el bloque ya esta puesto del todo y el holograma no estorba al evento.
        Bukkit.getScheduler().runTask(plugin.core(), () -> {
            if (registro().porId(b.id) == b) plugin.hologramas().crear(b);
        });

        plugin.textos().manda(p, "colocado", "&fColocaste %nombre%&f. &7Alcance de &f%radio% &7bloques.",
                "%nombre%", t.nombre, "%radio%", String.valueOf(t.radio));
        if (!t.fijo() && b.elegidos.isEmpty()) {
            plugin.textos().manda(p, "colocado-elige", "&7Úsalo para elegir sus &f%elegibles% &7efectos.",
                    "%elegibles%", String.valueOf(t.elegibles));
        }
        bl.getWorld().playSound(bl.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.0f);
        plugin.anotar("colocada", b.id.toString(), b.tipo, b.duenoTexto(), b.donde(), "por " + p.getName());
    }

    /* ================================================================ recoger */

    /**
     * Recoger (menu) o picar el bloque: el objeto vuelve al inventario de quien la recoge
     * con todo su estado. Si no le cabe, no se recoge. true si se recogio.
     */
    boolean recoger(Player quien, Baliza b) {
        if (!enCurso.add(b.id)) return false;
        try {
            if (registro().porId(b.id) != b) {
                plugin.textos().manda(quien, "ya-no-esta", "&#FF5C5CEse Super Beacon ya no está ahí.");
                return false;
            }
            if (quien.getInventory().firstEmpty() == -1) {
                plugin.textos().manda(quien, "sin-espacio",
                        "&#FF5C5CNo tienes espacio en el inventario. &7Libera una casilla e inténtalo de nuevo.");
                quien.playSound(quien.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                return false;
            }
            // 1. a disco antes de tocar nada
            registro().quitar(b);
            Pendiente pe = new Pendiente(b.ficha(), b.material, quien.getUniqueId(), quien.getName(), "recogida",
                    System.currentTimeMillis());
            registro().pendiente(pe);
            registro().guardar();
            // 2. fuera del mundo
            sacarDelMundo(b);
            // 3. a su inventario
            boolean dado = entregar(quien, pe, false);
            String nombre = nombreDe(b.tipo);
            if (dado) {
                plugin.textos().manda(quien, "recogido",
                        "&fRecogiste %nombre%&f. &7Conserva sus efectos, su dueño y su vencimiento.", "%nombre%", nombre);
            } else {
                plugin.textos().manda(quien, "devuelto-pendiente",
                        "&7Tu %nombre% &7te espera: te llega cuando tengas una casilla libre.", "%nombre%", nombre);
            }
            quien.playSound(quien.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1f);
            plugin.anotar("recogida", b.id.toString(), b.tipo, b.duenoTexto(), b.donde(), "por " + quien.getName());
            return true;
        } finally {
            enCurso.remove(b.id);
        }
    }

    /* ======================================================= quitar del mundo */

    /**
     * /superbeacon remove: fuera del mundo y de vuelta a su dueño (pendiente si no esta o
     * no le cabe). Este si carga el chunk si hace falta: es una orden puntual del staff, y
     * dejar el bloque puesto sin baliza detras regalaria un faro vanilla.
     */
    void retirar(CommandSender quien, Baliza b) {
        if (!enCurso.add(b.id)) return;
        try {
            if (registro().porId(b.id) != b) return;
            Pendiente pe = devolver(b, "retirada");
            boolean mundo = Bukkit.getWorld(b.mundo) != null;
            sacarDelMundo(b);
            Player d = b.dueno == null ? null : Bukkit.getPlayer(b.dueno);
            boolean dado = d != null && entregar(d, pe, false);
            if (d != null) avisarDevuelta(d, b, dado);
            String destino = dado
                    ? plugin.textos().crudo("retirado-devuelto", "Volvió al inventario de %dueno%.")
                    : plugin.textos().crudo("retirado-pendiente", "Le espera a %dueno% hasta que se conecte o tenga espacio.");
            plugin.textos().manda(quien, "retirado", "&fRetirado %nombre% &fde &7%donde%&f. &7%destino%",
                    "%nombre%", nombreDe(b.tipo), "%donde%", b.donde(),
                    "%destino%", destino.replace("%dueno%", b.duenoTexto()));
            if (!mundo) {
                plugin.textos().manda(quien, "retirado-sin-mundo",
                        "&#FFB627El mundo &f%mundo% &#FFB627no está cargado: su bloque se queda ahí, sin efectos.",
                        "%mundo%", b.mundo);
            }
            plugin.anotar("retirada", b.id.toString(), b.tipo, b.duenoTexto(), b.donde(), "por " + quien.getName(),
                    dado ? "devuelta" : "pendiente");
        } finally {
            enCurso.remove(b.id);
        }
    }

    /**
     * Su bloque ya no esta (WorldEdit, otro plugin, una caida...) y no lo quitamos
     * nosotros: la baliza pasa a pendiente de devolver a su dueño. Una baliza comprada no
     * se pierde nunca por algo que no controlamos.
     */
    void desaparecida(Baliza b, Material encontrado) {
        if (!enCurso.add(b.id)) return;
        try {
            if (registro().porId(b.id) != b) return;
            TipoBaliza t = plugin.tipo(b.tipo);
            if (t != null && t.alCaducar == TipoBaliza.AlCaducar.DESTRUIR && b.vencida(System.currentTimeMillis())) {
                // Vencida y de las que se destruyen: no hay nada que devolver.
                registro().quitar(b);
                registro().guardar();
                limpiarRastro(b);
                plugin.anotar("vencida-destruida", b.id.toString(), b.tipo, b.duenoTexto(), b.donde(), "ya no estaba");
                return;
            }
            Pendiente pe = devolver(b, "desaparecida");
            limpiarRastro(b);
            plugin.getLogger().warning("[SuperBeacon] El bloque del Super Beacon " + b.idCorto() + " (" + b.tipo
                    + ", de " + b.duenoTexto() + ") en " + b.donde() + " ya no esta (hay " + encontrado
                    + "). Pasa a pendiente de devolver a su dueño.");
            plugin.anotar("desaparecida", b.id.toString(), b.tipo, b.duenoTexto(), b.donde(), "habia " + encontrado);
            Player d = b.dueno == null ? null : Bukkit.getPlayer(b.dueno);
            if (d != null) avisarDevuelta(d, b, entregar(d, pe, false));
        } finally {
            enCurso.remove(b.id);
        }
    }

    /** Vencida y de las que se destruyen, con su chunk cargado: fuera del mundo y aviso al dueño. */
    void destruir(Baliza b) {
        if (!enCurso.add(b.id)) return;
        try {
            if (registro().porId(b.id) != b) return;
            registro().quitar(b);
            registro().guardar();
            sacarDelMundo(b);
            Player d = b.dueno == null ? null : Bukkit.getPlayer(b.dueno);
            if (d != null) {
                plugin.textos().manda(d, "vencido-destruido", "&7Tu %nombre% &7venció y desapareció.",
                        "%nombre%", nombreDe(b.tipo));
            }
            plugin.anotar("vencida-destruida", b.id.toString(), b.tipo, b.duenoTexto(), b.donde());
        } finally {
            enCurso.remove(b.id);
        }
    }

    /** Del registro a pendiente de su dueño, guardado ya. */
    private Pendiente devolver(Baliza b, String motivo) {
        registro().quitar(b);
        Pendiente pe = new Pendiente(b.ficha(), b.material, b.dueno, b.duenoNombre, motivo, System.currentTimeMillis());
        registro().pendiente(pe);
        registro().guardar();
        return pe;
    }

    private void avisarDevuelta(Player d, Baliza b, boolean dado) {
        String nombre = nombreDe(b.tipo);
        if (dado) {
            plugin.textos().manda(d, "devuelto", "&7Tu %nombre% &7volvió a tu inventario.", "%nombre%", nombre);
        } else {
            plugin.textos().manda(d, "devuelto-pendiente",
                    "&7Tu %nombre% &7te espera: te llega cuando tengas una casilla libre.", "%nombre%", nombre);
        }
    }

    /** Holograma fuera, menus cerrados, indices al dia y el bloque a aire si sigue siendo el suyo. */
    private void sacarDelMundo(Baliza b) {
        limpiarRastro(b);
        World w = Bukkit.getWorld(b.mundo);
        if (w == null) return;
        Block bl = w.getBlockAt(b.x, b.y, b.z);
        if (bl.getType() == b.material) bl.setType(Material.AIR);
    }

    private void limpiarRastro(Baliza b) {
        plugin.hologramas().quitar(b.id);
        plugin.menu().cerrar(b.id);
        plugin.motor().reindexar();
    }

    private String nombreDe(String tipo) {
        TipoBaliza t = plugin.tipo(tipo);
        return t != null ? t.nombre : "&#D7F3FFSuper Beacon";
    }
}
