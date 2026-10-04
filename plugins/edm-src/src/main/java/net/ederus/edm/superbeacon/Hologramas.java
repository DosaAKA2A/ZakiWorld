package net.ederus.edm.superbeacon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.destroystokyo.paper.profile.PlayerProfile;

import net.ederus.edm.comun.Estilo;
import net.kyori.adventure.text.Component;

/**
 * El holograma encima de cada Super Beacon, sin plugins de hologramas por medio:
 *
 *   - un TextDisplay pequeño (holograma.escala, 0,7 de serie) con tres lineas: el nombre
 *     del tipo en su color, de quien es ("de Dosa__" o "Clan [ABC] · líder Dosa__") y
 *     cuanto le queda ("Quedan 6 d 4 h", "Permanente" o "Apagado");
 *   - encima, un ItemDisplay con la cabeza del dueño (en los de clan, la del lider, que es
 *     su dueño) girando despacio. El giro va por interpolacion del cliente: cada GIRO_TICKS
 *     se le da un cuarto de vuelta mas y el cliente lo anima, sin teleports por tick.
 *
 * NO persistentes: no se guardan con el chunk. Se crean al cargar el chunk (o al arrancar)
 * y se van solos al descargarlo. Asi nunca quedan hologramas sueltos de una baliza que ya
 * no esta. Aun asi los dos llevan su marca (superbeacon:holograma) y al arrancar se barre
 * cualquiera que quede de un /reload a lo bruto.
 *
 * La cabeza sale con la piel del dueño si esta conectado; si no, se pide su perfil a
 * Mojang fuera del hilo principal y, mientras llega (o si no llega), va una cabeza
 * generica. Bedrock recibe el texto por Geyser como un nombre flotante; la cabeza puede
 * no verse alli, y no pasa nada.
 */
final class Hologramas {

    /** Cada cuanto se le da el siguiente cuarto de vuelta a las cabezas (y lo que tarda en girarlo). */
    static final int GIRO_TICKS = 50;
    /** Alto de una linea de TextDisplay a escala 1, en bloques. */
    static final double ALTO_LINEA = 0.25;
    static final float ESCALA_CABEZA = 0.45f;

    private record Holo(TextDisplay texto, ItemDisplay cabeza, int lineas) {
    }

    private final SuperBeaconPlugin plugin;
    private final NamespacedKey marca;
    private final Map<UUID, Holo> vivos = new HashMap<>();
    /** Perfiles con piel ya pedidos a Mojang, por jugador. */
    private final Map<UUID, PlayerProfile> perfiles = new HashMap<>();
    private final Set<UUID> pidiendo = new HashSet<>();
    private int paso;

    Hologramas(SuperBeaconPlugin plugin) {
        this.plugin = plugin;
        this.marca = new NamespacedKey(plugin, "holograma");
    }

    /** Quita cualquier holograma o cabeza nuestra que siga en el mundo (de antes de un reinicio en caliente). */
    int barrerHuerfanos() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Display d : w.getEntitiesByClass(Display.class)) {
                if (!(d instanceof TextDisplay) && !(d instanceof ItemDisplay)) continue;
                if (d.getPersistentDataContainer().has(marca, PersistentDataType.STRING)) {
                    d.remove();
                    n++;
                }
            }
        }
        return n;
    }

    /** Lo crea (o lo rehace) si el chunk de la baliza esta cargado. Nunca carga chunks. */
    void crear(Baliza b) {
        quitar(b.id);
        World w = Bukkit.getWorld(b.mundo);
        if (w == null || !w.isChunkLoaded(b.x >> 4, b.z >> 4)) return;
        float escala = plugin.holoEscala();
        Location l = new Location(w, b.x + 0.5, b.y + plugin.holoAltura(), b.z + 0.5);
        Component texto = texto(b);
        int lineas = lineas(texto);
        float rango = (float) Math.max(0.1, plugin.holoDistancia() / 64.0);
        TextDisplay d = w.spawn(l, TextDisplay.class, e -> {
            marcar(e, b);
            e.setBillboard(Display.Billboard.CENTER);
            e.setViewRange(rango);
            e.setShadowed(true);
            e.setSeeThrough(false);
            e.setDefaultBackground(false);
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.setLineWidth(250);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(escala, escala, escala), new Quaternionf()));
            e.text(texto);
        });
        ItemDisplay cabeza = null;
        if (plugin.holoCabeza()) {
            cabeza = w.spawn(sitioCabeza(l, lineas, escala), ItemDisplay.class, e -> {
                marcar(e, b);
                e.setItemStack(cabeza(b));
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                e.setBillboard(Display.Billboard.FIXED);
                e.setViewRange(rango);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setTransformation(giro(paso));
            });
        }
        vivos.put(b.id, new Holo(d, cabeza, lineas));
    }

    private void marcar(Entity e, Baliza b) {
        e.setPersistent(false);
        e.getPersistentDataContainer().set(marca, PersistentDataType.STRING, b.id.toString());
    }

    /** Justo encima de la ultima linea de texto, con aire para la cabeza. */
    static Location sitioCabeza(Location texto, int lineas, float escala) {
        return texto.clone().add(0, lineas * ALTO_LINEA * escala + ESCALA_CABEZA * 0.5 + 0.12, 0);
    }

    /** Cuantas lineas tiene el texto (los saltos + 1). */
    private static int lineas(Component c) {
        String plano = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
        return (int) plano.chars().filter(ch -> ch == '\n').count() + 1;
    }

    /** El giro n-esimo: un cuarto de vuelta por paso, alrededor del eje vertical. */
    private static Transformation giro(int n) {
        float angulo = (float) (Math.PI / 2.0 * (n % 4));
        return new Transformation(new Vector3f(), new Quaternionf().rotateY(angulo),
                new Vector3f(ESCALA_CABEZA, ESCALA_CABEZA, ESCALA_CABEZA), new Quaternionf());
    }

    /**
     * Cada GIRO_TICKS: el siguiente cuarto de vuelta, que el cliente anima en GIRO_TICKS.
     * Un cuarto (90 grados) y no mas: la interpolacion va por el camino corto, y con mas de
     * media vuelta giraria al reves.
     */
    void girar() {
        if (vivos.isEmpty()) return;
        paso = (paso + 1) % 4;
        Transformation t = giro(paso);
        for (Holo h : vivos.values()) {
            ItemDisplay c = h.cabeza();
            if (c == null || !c.isValid()) continue;
            c.setInterpolationDelay(0);
            c.setInterpolationDuration(GIRO_TICKS);
            c.setTransformation(t);
        }
    }

    /** La cabeza del dueño con su piel si ya se tiene; si no, una generica y se pide la piel. */
    private ItemStack cabeza(Baliza b) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        if (b.dueno == null) return it;
        PlayerProfile perfil = perfil(b);
        if (perfil == null) return it;
        if (it.getItemMeta() instanceof SkullMeta meta) {
            meta.setPlayerProfile(perfil);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** El perfil con piel del dueño, o null si aun no se tiene (y entonces se pide). */
    private PlayerProfile perfil(Baliza b) {
        Player p = Bukkit.getPlayer(b.dueno);
        if (p != null) {
            PlayerProfile vivo = p.getPlayerProfile();
            if (vivo.hasTextures()) {
                perfiles.put(b.dueno, vivo);
                return vivo;
            }
        }
        PlayerProfile ya = perfiles.get(b.dueno);
        if (ya != null) return ya;
        UUID dueno = b.dueno;
        if (pidiendo.add(dueno)) {
            PlayerProfile pedir = Bukkit.createProfile(dueno, b.duenoNombre);
            pedir.update().whenComplete((hecho, error) -> Bukkit.getScheduler().runTask(plugin.core(), () -> {
                pidiendo.remove(dueno);
                if (error != null || hecho == null || !hecho.hasTextures() || plugin.detenido()) return;
                perfiles.put(dueno, hecho);
                ponerCabezas(dueno);
            }));
        }
        return null;
    }

    /** Llego la piel de ese jugador: se la pone a las cabezas de sus balizas. */
    private void ponerCabezas(UUID dueno) {
        for (Map.Entry<UUID, Holo> en : vivos.entrySet()) {
            Baliza b = plugin.registro().porId(en.getKey());
            ItemDisplay c = en.getValue().cabeza();
            if (b == null || c == null || !c.isValid() || !dueno.equals(b.dueno)) continue;
            c.setItemStack(cabeza(b));
        }
    }

    void quitar(UUID baliza) {
        Holo h = vivos.remove(baliza);
        if (h != null) quitar(h);
    }

    private static void quitar(Holo h) {
        if (h.texto() != null) h.texto().remove();
        if (h.cabeza() != null) h.cabeza().remove();
    }

    /** Le pone el texto de ahora; si no existe (o le falta la cabeza) y el chunk esta cargado, lo crea. */
    void refrescar(Baliza b) {
        Holo h = vivos.get(b.id);
        if (h == null || h.texto() == null || !h.texto().isValid()
                || (plugin.holoCabeza() && (h.cabeza() == null || !h.cabeza().isValid()))) {
            crear(b);
            return;
        }
        Component texto = texto(b);
        int lineas = lineas(texto);
        h.texto().text(texto);
        if (lineas != h.lineas()) {
            // Cambio el numero de lineas: la cabeza sube o baja con el texto.
            if (h.cabeza() != null) {
                h.cabeza().teleport(sitioCabeza(h.texto().getLocation(), lineas, plugin.holoEscala()));
            }
            vivos.put(b.id, new Holo(h.texto(), h.cabeza(), lineas));
        }
    }

    /** Cada refresco-segundos: el tiempo que queda cambia aunque nadie toque nada. */
    void refrescarTodos() {
        for (Baliza b : new ArrayList<>(plugin.registro().todas())) {
            World w = Bukkit.getWorld(b.mundo);
            if (w == null || !w.isChunkLoaded(b.x >> 4, b.z >> 4)) {
                // Chunk descargado: su holograma se fue con el; se olvida la referencia.
                quitar(b.id);
                continue;
            }
            refrescar(b);
        }
        // Referencias de balizas que ya no estan (no deberia quedar ninguna).
        vivos.keySet().removeIf(id -> {
            if (plugin.registro().porId(id) != null) return false;
            Holo h = vivos.get(id);
            if (h != null) quitar(h);
            return true;
        });
    }

    void quitarTodos() {
        for (Holo h : vivos.values()) quitar(h);
        vivos.clear();
    }

    /**
     * Las lineas de mensajes.yml (holograma) con sus marcadores; una que se queda vacia no
     * sale. %restante% es "Quedan 6 d 4 h", "Permanente" o "Apagado".
     */
    Component texto(Baliza b) {
        SuperBeaconPlugin.TextosBaliza tx = plugin.textos();
        TipoBaliza t = plugin.tipo(b.tipo);
        long ahora = System.currentTimeMillis();
        String nombre = t != null ? t.nombre : "&7Super Beacon (" + b.tipo + ")";
        String ac = Presentacion.hex(t != null ? t.color() : 0xD7F3FF);
        String restante = plugin.restante(b, ahora);
        boolean tipoClan = t != null && t.beneficia == TipoBaliza.Beneficia.CLAN;
        // El clan del dueño solo se pregunta (PlaceholderAPI) si el tipo es de clan.
        String clan = b.clan != null ? b.clan : tipoClan ? plugin.motor().clanDe(b) : null;
        String de;
        if (tipoClan && clan != null) {
            de = tx.crudo("holograma-de-clan", "&#8A8A8AClan &f[%clan%] &#8A8A8A· líder &f%dueno%");
        } else if (tipoClan) {
            de = tx.crudo("holograma-de-lider", "&#8A8A8Alíder &f%dueno%");
        } else {
            de = tx.crudo("holograma-de-dueno", "&#8A8A8Ade &f%dueno%");
        }
        de = de.replace("%clan%", clan == null ? "" : clan).replace("%dueno%", b.duenoTexto());
        StringBuilder efectos = new StringBuilder();
        if (t != null) {
            for (Efecto e : plugin.motor().activos(b, t)) {
                if (efectos.length() > 0) efectos.append(", ");
                efectos.append(e.nombrePlano());
            }
        }

        List<String> lineas = tx.lista("holograma", List.of("%nombre%", "%de%", "&#C4C4C4%restante%"));
        Component out = null;
        for (String linea : lineas) {
            String s = linea.replace("%nombre%", nombre)
                    .replace("%acento%", ac)
                    .replace("%dueno%", b.duenoTexto())
                    .replace("%clan%", clan == null ? "" : clan)
                    .replace("%de%", de)
                    .replace("%restante%", restante)
                    .replace("%efectos%", efectos.toString());
            if (LectorTipos.plano(s).isBlank()) continue;
            Component c = Estilo.legado(s);
            out = out == null ? c : out.append(Component.newline()).append(c);
        }
        return out == null ? Estilo.legado(nombre) : out;
    }
}
