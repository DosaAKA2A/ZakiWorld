package net.ederus.edm.superbeacon;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.block.BlockGrowEvent;

/**
 * cultivos: los cultivos del alcance crecen mas rapido.
 *
 * Cada cada-segundos se prueban hasta "intentos" columnas al azar dentro del circulo y, en
 * cada una, unos pocos bloques por encima y por debajo de la baliza (altura). Un cultivo
 * que no esta maduro sube una edad. El coste esta acotado por diseño: intentos x
 * (2 x altura + 1) lecturas de bloque como mucho, nunca el volumen entero, y nunca en un
 * chunk que no este ya cargado.
 *
 * Solo cultivos de verdad, por lista: "Ageable" tambien lo son el fuego, el kelp o las
 * enredaderas, y a esos subirles la edad los apaga o los para en vez de hacerlos crecer.
 * Se lanza BlockGrowEvent como en un crecimiento normal: una proteccion o un plugin de
 * cultivos que lo cancele, manda.
 */
final class ClaseCultivos extends ClaseEfecto {

    private static final Set<Material> CULTIVOS = materiales("WHEAT", "CARROTS", "POTATOES", "BEETROOTS",
            "NETHER_WART", "MELON_STEM", "PUMPKIN_STEM", "SWEET_BERRY_BUSH", "COCOA", "TORCHFLOWER_CROP");

    private static Set<Material> materiales(String... nombres) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String n : nombres) {
            Material m = Material.getMaterial(n);
            if (m != null) out.add(m);
        }
        return out;
    }

    static final class Riego extends Efecto {
        private final ClaseCultivos clase;
        final int intentos;
        final int cadaSegundos;
        final int altura;

        Riego(ClaseCultivos clase, String clave, String nombre, Material icono, int intentos, int cadaSegundos,
              int altura) {
            super(clave, nombre, icono);
            this.clase = clase;
            this.intentos = intentos;
            this.cadaSegundos = cadaSegundos;
            this.altura = altura;
        }

        @Override
        ClaseEfecto clase() {
            return clase;
        }

        @Override
        String grupo() {
            return "cultivos";
        }

        @Override
        double fuerza() {
            return intentos;
        }
    }

    /** baliza -> cuando le toca la siguiente pasada. */
    private final Map<UUID, Long> proxima = new HashMap<>();

    ClaseCultivos(SuperBeaconPlugin plugin) {
        super(plugin, "cultivos");
    }

    @Override
    Efecto leer(String clave, String nombre, Material icono, ConfigurationSection s, Consumer<String> error) {
        int intentos = acotar(s.getInt("intentos", 12), 1, 64, "intentos", error);
        int cada = acotar(s.getInt("cada-segundos", 5), 1, 600, "cada-segundos", error);
        int altura = acotar(s.getInt("altura", 4), 0, 16, "altura", error);
        return new Riego(this, clave, nombre, icono, intentos, cada, altura);
    }

    private static int acotar(int v, int min, int max, String campo, Consumer<String> error) {
        if (v >= min && v <= max) return v;
        int arreglado = Math.max(min, Math.min(max, v));
        error.accept(campo + ": " + v + " esta fuera de " + min + ".." + max + "; se usa " + arreglado);
        return arreglado;
    }

    @Override
    Material icono() {
        return Material.WHEAT;
    }

    @Override
    boolean porJugador() {
        return false;
    }

    @Override
    List<String> detalle(Efecto e) {
        return List.of(plugin.textos().crudo("detalle-cultivos", "&#8A8A8AHace crecer los cultivos de su alcance."));
    }

    @Override
    void enZona(Baliza b, TipoBaliza t, Efecto e, World w, long ahora) {
        Riego r = (Riego) e;
        Long cuando = proxima.get(b.id);
        if (cuando != null && ahora < cuando) return;
        proxima.put(b.id, ahora + r.cadaSegundos * 1000L);
        regar(b, t.radio, r, w);
    }

    /** Una pasada. Devuelve cuantos cultivos subieron una edad (lo usa el selftest). */
    int regar(Baliza b, int radio, Riego r, World w) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int min = Math.max(w.getMinHeight(), b.y - r.altura);
        int max = Math.min(w.getMaxHeight() - 1, b.y + r.altura);
        int crecidos = 0;
        for (int i = 0; i < r.intentos; i++) {
            // Uniforme en el disco: la raiz evita que el centro salga mas que el borde.
            double ang = rnd.nextDouble() * Math.PI * 2;
            double dist = radio * Math.sqrt(rnd.nextDouble());
            int x = (int) Math.floor(b.x + 0.5 + Math.cos(ang) * dist);
            int z = (int) Math.floor(b.z + 0.5 + Math.sin(ang) * dist);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = min; y <= max; y++) {
                Block bl = w.getBlockAt(x, y, z);
                if (!CULTIVOS.contains(bl.getType())) continue;
                if (crecer(bl)) crecidos++;
            }
        }
        return crecidos;
    }

    private static boolean crecer(Block bl) {
        if (!(bl.getBlockData() instanceof Ageable a) || a.getAge() >= a.getMaximumAge()) return false;
        Ageable nuevo = (Ageable) a.clone();
        nuevo.setAge(a.getAge() + 1);
        BlockState estado = bl.getState();
        estado.setBlockData(nuevo);
        BlockGrowEvent ev = new BlockGrowEvent(bl, estado);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return false;
        bl.setBlockData(ev.getNewState().getBlockData(), true);
        return true;
    }

    @Override
    void reindexar(Collection<Baliza> balizas) {
        // Fuera las que ya no estan, para que el mapa no crezca con balizas recogidas.
        Set<UUID> vivas = new HashSet<>();
        for (Baliza b : balizas) vivas.add(b.id);
        proxima.keySet().retainAll(vivas);
    }

    @Override
    void parar() {
        proxima.clear();
    }
}
