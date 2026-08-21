package net.ederus.edm.tienda;

import net.ederus.edm.comun.Estilo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.io.File;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * El aspecto del menu principal: en que hueco va cada categoria, como se llama,
 * de que color y con que cabeza.
 *
 * Todo salio de los sections/*.yml de EconomyShopGUI en produccion. No se
 * adivina nada: si la tienda de siempre pone Minerales en el hueco 12 y lo
 * llama "Minerales" en #00CDCD, aqui va igual.
 */
public final class Secciones {

    public record Seccion(String id, int ranura, String nombre, TextColor color, String cabeza) { }

    private static final String TEXTURAS = "https://textures.minecraft.net/texture/";

    private final Map<String, Seccion> porId = new LinkedHashMap<>();
    private final Map<String, ItemStack> cache = new HashMap<>();
    private ItemStack relleno;
    private final Map<String, String> sonidos = new HashMap<>();
    private final Map<String, String> textos = new HashMap<>();

    /** Entiende &7, &#RRGGBB y el &x&R&R&G&G&B&B de Spigot: los tres formatos
     *  que aparecen en los ficheros de EconomyShopGUI. */
    private static final LegacyComponentSerializer LEGADO = LegacyComponentSerializer.builder()
            .character('&').hexCharacter('#').hexColors()
            .useUnusualXRepeatedCharacterHexFormat().build();

    public void cargar(File fichero) {
        porId.clear();
        cache.clear();
        sonidos.clear();
        relleno = null;
        if (!fichero.exists()) return;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichero);

        ConfigurationSection secs = yml.getConfigurationSection("secciones");
        if (secs != null) {
            for (String id : secs.getKeys(false)) {
                ConfigurationSection s = secs.getConfigurationSection(id);
                if (s == null) continue;
                TextColor color = TextColor.fromHexString(s.getString("color", "#FFFFFF"));
                porId.put(id, new Seccion(id,
                        s.getInt("ranura", -1),
                        s.getString("nombre", id),
                        color != null ? color : NamedTextColor.WHITE,
                        s.getString("cabeza", "")));
            }
        }

        ConfigurationSection rel = yml.getConfigurationSection("relleno");
        if (rel != null) {
            Material m = Material.matchMaterial(rel.getString("material", "BLACK_STAINED_GLASS_PANE"));
            if (m != null) {
                relleno = new ItemStack(m);
                var meta = relleno.getItemMeta();
                if (meta != null) {
                    /* El nombre lleva codigos & del fichero original: se pasan tal
                     * cual por el serializador antiguo para no reescribirlos. */
                    meta.displayName(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacyAmpersand().deserialize(rel.getString("nombre", " "))
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                    relleno.setItemMeta(meta);
                }
            }
        }

        ConfigurationSection txt = yml.getConfigurationSection("textos");
        if (txt != null) for (String k : txt.getKeys(false)) textos.put(k, txt.getString(k, ""));

        ConfigurationSection son = yml.getConfigurationSection("sonidos");
        if (son != null) for (String k : son.getKeys(false)) sonidos.put(k, son.getString(k, ""));
    }

    /**
     * Un texto del fichero, con sus marcadores sustituidos. Si la clave no
     * existe devuelve el respaldo, para que quitar una linea del yml no deje
     * un hueco en blanco en el menu.
     */
    public Component texto(String clave, String respaldo, String... pares) {
        String s = textos.getOrDefault(clave, respaldo);
        if (s == null || s.isEmpty()) return Component.empty();
        for (int i = 0; i + 1 < pares.length; i += 2) s = s.replace(pares[i], pares[i + 1]);
        return LEGADO.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    public boolean tieneTexto(String clave) {
        String s = textos.get(clave);
        return s != null && !s.isEmpty();
    }

    public Seccion de(String id) { return porId.get(id); }
    public Iterable<Seccion> todas() { return porId.values(); }
    public int cuantas() { return porId.size(); }

    /** Panel de relleno, o null si no hay configurado. */
    public ItemStack relleno() { return relleno == null ? null : relleno.clone(); }

    /** El icono de la categoria; null si no se pudo construir. */
    public ItemStack icono(String id) {
        Seccion s = porId.get(id);
        if (s == null || s.cabeza() == null || s.cabeza().isBlank()) return null;

        ItemStack guardado = cache.get(id);
        if (guardado != null) return guardado.clone();

        ItemStack pila;
        if (s.cabeza().toUpperCase().startsWith("MAT:")) {
            Material m = Material.matchMaterial(s.cabeza().substring(4).trim());
            if (m == null) return null;
            pila = new ItemStack(m);
        } else {
            pila = cabeza(s.cabeza());
            if (pila == null) return null;
        }
        cache.put(id, pila);
        return pila.clone();
    }

    /** Una cabeza con la textura dada. El UUID sale del hash para que sea
     *  estable y no ensucie la cache de perfiles del servidor. */
    private static ItemStack cabeza(String hash) {
        try {
            ItemStack pila = new ItemStack(Material.PLAYER_HEAD);
            if (!(pila.getItemMeta() instanceof SkullMeta meta)) return null;
            PlayerProfile perfil = Bukkit.createPlayerProfile(
                    UUID.nameUUIDFromBytes(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            PlayerTextures texturas = perfil.getTextures();
            texturas.setSkin(URI.create(TEXTURAS + hash).toURL());
            perfil.setTextures(texturas);
            meta.setOwnerProfile(perfil);
            pila.setItemMeta(meta);
            return pila;
        } catch (Exception e) {
            /* Una textura mal copiada no puede tumbar el menu. */
            return null;
        }
    }

    /**
     * Suena lo mismo que suena hoy la tienda. Se busca en el registro y no con
     * Sound.valueOf: en Paper 26 los sonidos ya no son un enum y valueOf ha
     * desaparecido.
     */
    public void sonar(Player jugador, String cual) {
        String nombre = sonidos.get(cual);
        if (nombre == null || nombre.isBlank()) return;
        try {
            Sound s = Registry.SOUNDS.get(NamespacedKey.minecraft(
                    nombre.toLowerCase(java.util.Locale.ROOT).replace('_', '.')));
            if (s == null) s = Registry.SOUNDS.match(nombre);
            if (s != null) jugador.playSound(jugador.getLocation(), s, 1f, 1f);
        } catch (Exception e) {
            /* Un sonido mal escrito no puede impedir abrir la tienda. */
        }
    }

    public Component nombreDe(String id) {
        Seccion s = porId.get(id);
        if (s == null) return Estilo.texto(id, Estilo.CLARO);
        /* Si el nombre trae codigos de color, manda el fichero entero: asi se
         * puede escribir '&8→ &#80FF00Bloques' y olvidarse del campo color. */
        if (s.nombre().indexOf('&') >= 0) {
            return LEGADO.deserialize(s.nombre()).decoration(TextDecoration.ITALIC, false);
        }
        return Estilo.texto("→ ", NamedTextColor.DARK_GRAY)
                .append(Estilo.texto(s.nombre(), s.color()));
    }
}
