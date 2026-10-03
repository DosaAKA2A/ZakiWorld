package net.ederus.edm.superbeacon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import net.ederus.edm.comun.Estilo;
import net.kyori.adventure.text.Component;

/**
 * El holograma encima de cada Super Beacon: un TextDisplay propio, sin plugins de
 * hologramas por medio.
 *
 * NO persistente: no se guarda con el chunk. Se crea al cargar el chunk (o al arrancar) y
 * se va solo al descargarlo. Asi nunca quedan hologramas sueltos en el mundo de una
 * baliza que ya no esta, que es lo que pasa con los que se guardan. Aun asi llevan su
 * marca (superbeacon:holograma) y al arrancar se barre cualquiera que quede de un /reload
 * a lo bruto.
 *
 * Fondo transparente y sombra, como los hologramas de siempre; brillo fijo para que de
 * noche se lea igual. Se ve desde holograma.distancia bloques. Bedrock los recibe por
 * Geyser como un nombre flotante.
 */
final class Hologramas {

    private final SuperBeaconPlugin plugin;
    private final NamespacedKey marca;
    private final Map<UUID, TextDisplay> vivos = new HashMap<>();

    Hologramas(SuperBeaconPlugin plugin) {
        this.plugin = plugin;
        this.marca = new NamespacedKey(plugin, "holograma");
    }

    /** Quita cualquier holograma nuestro que siga en el mundo (de antes de un reinicio en caliente). */
    int barrerHuerfanos() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            for (TextDisplay d : w.getEntitiesByClass(TextDisplay.class)) {
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
        Location l = new Location(w, b.x + 0.5, b.y + plugin.holoAltura(), b.z + 0.5);
        Component texto = texto(b);
        float rango = (float) Math.max(0.1, plugin.holoDistancia() / 64.0);
        TextDisplay d = w.spawn(l, TextDisplay.class, e -> {
            e.setPersistent(false);
            e.getPersistentDataContainer().set(marca, PersistentDataType.STRING, b.id.toString());
            e.setBillboard(Display.Billboard.CENTER);
            e.setViewRange(rango);
            e.setShadowed(true);
            e.setSeeThrough(false);
            e.setDefaultBackground(false);
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.setLineWidth(250);
            e.setBrightness(new Display.Brightness(15, 15));
            e.text(texto);
        });
        vivos.put(b.id, d);
    }

    void quitar(UUID baliza) {
        TextDisplay d = vivos.remove(baliza);
        if (d != null) d.remove();
    }

    /** Le pone el texto de ahora; si no existe y el chunk esta cargado, lo crea. */
    void refrescar(Baliza b) {
        TextDisplay d = vivos.get(b.id);
        if (d == null || !d.isValid()) {
            crear(b);
            return;
        }
        d.text(texto(b));
    }

    /** Cada refresco-segundos: el tiempo que queda cambia aunque nadie toque nada. */
    void refrescarTodos() {
        for (Baliza b : new ArrayList<>(plugin.registro().todas())) {
            World w = Bukkit.getWorld(b.mundo);
            if (w == null || !w.isChunkLoaded(b.x >> 4, b.z >> 4)) {
                // Chunk descargado: su holograma se fue con el; se olvida la referencia.
                TextDisplay d = vivos.remove(b.id);
                if (d != null && d.isValid()) d.remove();
                continue;
            }
            refrescar(b);
        }
        // Referencias de balizas que ya no estan (no deberia quedar ninguna).
        vivos.keySet().removeIf(id -> {
            if (plugin.registro().porId(id) != null) return false;
            TextDisplay d = vivos.get(id);
            if (d != null) d.remove();
            return true;
        });
    }

    void quitarTodos() {
        for (TextDisplay d : vivos.values()) d.remove();
        vivos.clear();
    }

    /**
     * Las lineas de mensajes.yml (holograma) con sus marcadores. Una linea con %restante%
     * solo sale si la baliza caduca (o ya se apago); una que se queda vacia no sale.
     */
    Component texto(Baliza b) {
        SuperBeaconPlugin.TextosBaliza tx = plugin.textos();
        TipoBaliza t = plugin.tipo(b.tipo);
        long ahora = System.currentTimeMillis();
        String nombre = t != null ? t.nombre : "&7Super Beacon (" + b.tipo + ")";
        boolean caduca = b.vence > 0;
        String restante = plugin.restante(b, ahora);
        boolean tipoClan = t != null && t.beneficia == TipoBaliza.Beneficia.CLAN;
        // El clan del dueño solo se pregunta (PlaceholderAPI) si el tipo es de clan.
        String clan = b.clan != null ? b.clan : tipoClan ? plugin.motor().clanDe(b) : null;
        boolean deClan = tipoClan && clan != null;
        String de = deClan
                ? tx.crudo("holograma-de-clan", "del clan %clan%").replace("%clan%", clan)
                : tx.crudo("holograma-de-dueno", "de %dueno%").replace("%dueno%", b.duenoTexto());
        StringBuilder efectos = new StringBuilder();
        if (t != null) {
            for (Efecto e : plugin.motor().activos(b, t)) {
                if (efectos.length() > 0) efectos.append(", ");
                efectos.append(e.nombrePlano());
            }
        }

        List<String> lineas = tx.lista("holograma", List.of("%nombre%", "&#8A8A8A%de%", "&#D7F3FF%restante%"));
        Component out = null;
        for (String linea : lineas) {
            if (linea.contains("%restante%") && !caduca) continue;
            String s = linea.replace("%nombre%", nombre)
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
