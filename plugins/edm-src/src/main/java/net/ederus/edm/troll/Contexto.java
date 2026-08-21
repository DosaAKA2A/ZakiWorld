package net.ederus.edm.troll;

import net.ederus.edm.comun.Estilo;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.IntConsumer;

/**
 * Lo que tiene a mano cada broma.
 *
 * Existe para que el catalogo sea legible: una broma tiene que caber en dos o
 * tres lineas y decir lo que hace sin ruido. Todo lo repetitivo (sonidos que
 * pueden no existir, tareas, cajas de bloques con su vuelta atras) esta aqui y
 * resuelto UNA vez.
 */
public final class Contexto {

    private static final Random AZAR = new Random();

    private final TrollPlugin modulo;
    private final Player admin;
    private final Player victima;
    private final Troll troll;
    private final int segundos;

    public Contexto(TrollPlugin modulo, Player admin, Player victima, Troll troll, int segundos) {
        this.modulo = modulo;
        this.admin = admin;
        this.victima = victima;
        this.troll = troll;
        this.segundos = segundos;
    }

    public Player v() { return victima; }
    public Troll troll() { return troll; }
    public Player a() { return admin; }
    public int segundos() { return segundos; }
    public Estados estados() { return modulo.estados(); }
    public TrollPlugin modulo() { return modulo; }
    public Location donde() { return victima.getLocation(); }
    public static Random azar() { return AZAR; }

    // ------------------------------------------------------------- lo basico

    /** Un mensaje a la victima, con codigos & y sin prefijo del modulo: la
     *  gracia de casi todas estas bromas es que parezcan del servidor. */
    public void chat(String texto) {
        victima.sendMessage(Estilo.legado(texto));
    }

    public void titulo(String arriba, String abajo) {
        victima.showTitle(net.kyori.adventure.title.Title.title(
                Estilo.legado(arriba), Estilo.legado(abajo)));
    }

    public void barra(String texto) {
        victima.sendActionBar(Estilo.legado(texto));
    }

    /**
     * Un sonido por su nombre. Si en esta version no existe, no pasa nada.
     * En Paper 26 los sonidos ya no son un enum y Sound.valueOf no existe.
     */
    public void sonido(String clave, float tono) {
        try {
            Sound s = Registry.SOUNDS.get(NamespacedKey.minecraft(clave.toLowerCase(Locale.ROOT)));
            if (s == null) s = Registry.SOUNDS.match(clave);
            if (s != null) victima.playSound(victima.getLocation(), s, 1f, tono);
        } catch (Exception e) {
            /* Un sonido que no existe no puede tumbar una broma. */
        }
    }

    public void particulas(Particle particula, int cuantas, double radio) {
        try {
            victima.getWorld().spawnParticle(particula, victima.getLocation().add(0, 1, 0),
                    cuantas, radio, radio, radio, 0.05);
        } catch (Exception e) {
            /* idem: una particula que cambio de nombre no rompe nada */
        }
    }

    public void efecto(PotionEffectType tipo, int seg, int nivel) {
        if (tipo == null) return;
        victima.addPotionEffect(new PotionEffect(tipo, Math.max(1, seg) * 20, Math.max(0, nivel), false, false));
    }

    public void quitarEfecto(PotionEffectType tipo) {
        if (tipo != null) victima.removePotionEffect(tipo);
    }

    public void empujar(double x, double y, double z) {
        victima.setVelocity(victima.getVelocity().add(new Vector(x, y, z)));
    }

    // ------------------------------------------------------------- el tiempo

    public void tras(int ticks, Runnable que) {
        Bukkit.getScheduler().runTaskLater(modulo.core(), que, Math.max(1, ticks));
    }

    /** Repite algo N veces cada X ticks, contando desde 0. */
    public void repetir(int cada, int veces, IntConsumer que) {
        for (int i = 0; i < veces; i++) {
            final int n = i;
            tras(1 + i * Math.max(1, cada), () -> {
                if (victima.isOnline()) que.accept(n);
            });
        }
    }

    // ------------------------------------------------------------ las caidas

    /**
     * Apunta la vuelta atras de ESTA broma, con su duracion.
     *
     * Es la unica forma correcta de dejar algo cambiado: lo que se apunta aqui
     * se deshace si o si, aunque el jugador se vaya o el servidor se apague.
     */
    public void alAcabar(Runnable deshacer) {
        estados().poner(victima, troll.id(), null, segundos, deshacer);
    }

    /** Enciende una marca para los listeners mientras dure la broma. */
    public void marcar(Estados.Marca marca) {
        marcar(marca, () -> { });
    }

    public void marcar(Estados.Marca marca, Runnable deshacer) {
        estados().poner(victima, troll.id() + ":" + marca, marca, segundos, deshacer);
    }

    /**
     * La red de seguridad de todas las bromas de altura.
     *
     * Subir a alguien y soltarlo lo mata, y matar no es una broma: es borrar
     * progreso. Con esto el susto es el mismo y el aterrizaje no cuesta nada.
     * Va con clave propia para que no la pise la marca de la broma en curso.
     */
    public void sinCaida(int seg) {
        estados().poner(victima, "red-de-caida", Estados.Marca.SIN_CAIDA, seg, () -> { });
    }

    /** Lo sube en el sitio, con la red puesta. */
    public void subir(double bloques, int segRed) {
        sinCaida(segRed);
        Location destino = victima.getLocation().add(0, bloques, 0);
        victima.teleport(destino);
    }

    // ------------------------------------------------------------ el terreno

    /**
     * Una caja alrededor de la victima y su vuelta atras.
     *
     * Devuelve el Runnable que repone EXACTAMENTE lo que habia, guardado como
     * BlockState: reponer "aire" borraria el suelo del que estuviera encerrado
     * dentro de su propia base.
     */
    public Runnable encerrar(Material material, int radio, boolean conTecho, boolean conSuelo) {
        List<BlockState> antes = new ArrayList<>();
        Location centro = victima.getLocation().getBlock().getLocation();
        int alto = 2;

        for (int x = -radio; x <= radio; x++) {
            for (int z = -radio; z <= radio; z++) {
                for (int y = -1; y <= alto; y++) {
                    boolean borde = Math.abs(x) == radio || Math.abs(z) == radio;
                    boolean techo = y == alto;
                    boolean suelo = y == -1;
                    if (!borde && !techo && !suelo) continue;
                    if (techo && !conTecho) continue;
                    if (suelo && !conSuelo) continue;

                    Block b = centro.clone().add(x, y, z).getBlock();
                    /* Solo se tapa lo que ya era aire o hierba: una broma no
                     * puede comerse el cofre de nadie. */
                    if (!b.getType().isAir() && !b.isPassable()) continue;
                    antes.add(b.getState());
                    b.setType(material, false);
                }
            }
        }
        return () -> antes.forEach(e -> e.update(true, false));
    }

    /** Rellena de un material los huecos de dentro (para enterrar o telarañas). */
    public Runnable rellenar(Material material, int radio, int alto) {
        List<BlockState> antes = new ArrayList<>();
        Location centro = victima.getLocation().getBlock().getLocation();
        for (int x = -radio; x <= radio; x++) {
            for (int z = -radio; z <= radio; z++) {
                for (int y = 0; y <= alto; y++) {
                    Block b = centro.clone().add(x, y, z).getBlock();
                    if (!b.getType().isAir()) continue;
                    antes.add(b.getState());
                    b.setType(material, false);
                }
            }
        }
        return () -> antes.forEach(e -> e.update(true, false));
    }

    // ---------------------------------------------------------- el inventario

    /** Mete algo SOLO en huecos vacios y devuelve como quitarlo. Nunca pisa
     *  nada de la victima: un item perdido de MMOItems no vuelve. */
    public Runnable meterEnHuecos(ItemStack modelo, int cuantos) {
        List<Integer> puestos = new ArrayList<>();
        var inv = victima.getInventory();
        for (int i = 0; i < inv.getStorageContents().length && puestos.size() < cuantos; i++) {
            ItemStack actual = inv.getItem(i);
            if (actual != null && !actual.getType().isAir()) continue;
            inv.setItem(i, modelo.clone());
            puestos.add(i);
        }
        return () -> {
            for (int i : puestos) {
                ItemStack ahi = victima.getInventory().getItem(i);
                /* Solo se quita si sigue siendo lo que pusimos: si el jugador
                 * movio cosas, se deja en paz. */
                if (ahi != null && ahi.isSimilar(modelo)) victima.getInventory().setItem(i, null);
            }
        };
    }
}
