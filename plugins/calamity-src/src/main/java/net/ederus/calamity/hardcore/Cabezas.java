package net.ederus.calamity.hardcore;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Las cabezas de los menus de Calamity (1.7.4, para el ranking de Rhen): las de dibujo, con una
 * textura de Minecraft-Heads, y las de los jugadores con su skin.
 *
 * Las de dibujo: la config guarda la textura como Minecraft-Heads la da, en cualquiera de sus tres
 * formas (el hash de textures.minecraft.net, la URL entera o el "Value" en base64), y aqui se saca
 * el hash y se monta la propiedad textures. Si no hay hash valido sale el material de respaldo, y
 * el menu no se rompe por una textura mal pegada. (Compat.head de EDM, el de la cabeza de la
 * Parca, no sirve aqui: no acepta la URL ni dice si el texto es una textura, y el autotest tiene
 * que comprobar las de la config sin servidor.)
 *
 * Las de jugadores no pueden congelar el servidor: nada de ir a Mojang desde el hilo principal.
 *  - Conectado: su perfil de ahora, que ya lleva la skin (y se guarda para cuando se desconecte).
 *  - Desconectado con su perfil ya guardado: ese perfil.
 *  - Si no: la cabeza va solo con su UUID (setOwningPlayer: perfil "dinamico" desde Paper 1.21.9)
 *    y el cliente busca la skin. A la vez se pide el perfil completo con PlayerProfile.update(),
 *    que Paper hace en otro hilo; cuando llega se guarda y alLlegar cambia la cabeza en los menus
 *    abiertos. Ojo: getOfflinePlayer(u).getPlayerProfile() no sirve para la cabeza, porque trae
 *    nombre y UUID sin textura y Paper lo manda como perfil cerrado: saldria la cabeza de Steve.
 * Cada jugador se pide como mucho una vez cada REINTENTO_MS y nunca hay mas de EN_VUELO_MAX
 * peticiones a la vez, para no pasar del limite de Mojang con un top lleno de gente nueva.
 */
final class Cabezas {

    /** Donde viven las texturas de Minecraft: el cliente no acepta skins de otro sitio. */
    static final String URL = "http://textures.minecraft.net/texture/";
    /** Un hash de textura: hexadecimal. Mojang quita los ceros de delante, asi que no siempre son 64. */
    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{32,64}");
    private static final Pattern EN_URL = Pattern.compile("textures\\.minecraft\\.net/texture/([0-9a-fA-F]{32,64})");

    /** Cuanto se tarda en volver a pedir el perfil de un jugador (haya llegado o no). */
    private static final long REINTENTO_MS = 30 * 60_000L;
    /** Un perfil guardado se usa siempre, pero pasado esto se pide otra vez por si cambio la skin. */
    private static final long CADUCA_MS = 6 * 60 * 60_000L;
    private static final int EN_VUELO_MAX = 8;

    private record Guardado(PlayerProfile perfil, long cuando) {
    }

    private final Plugin plugin;
    private final Consumer<UUID> alLlegar;
    /** Las cabezas de dibujo ya hechas, por hash (solo en el hilo principal). */
    private final Map<String, ItemStack> dibujos = new HashMap<>();
    /** Perfiles con skin: los escribe el hilo de Paper y los lee el principal. */
    private final Map<UUID, Guardado> perfiles = new ConcurrentHashMap<>();
    /** Cuando se pidio cada perfil por ultima vez. */
    private final Map<UUID, Long> pedidos = new ConcurrentHashMap<>();
    private final AtomicInteger enVuelo = new AtomicInteger();
    private volatile boolean parado;

    /** alLlegar: lo que hay que hacer (en el hilo principal) cuando llega el perfil de un jugador. */
    Cabezas(Plugin plugin, Consumer<UUID> alLlegar) {
        this.plugin = plugin;
        this.alLlegar = alLlegar;
    }

    void parar() {
        parado = true;
        dibujos.clear();
        perfiles.clear();
        pedidos.clear();
    }

    // ------------------------------------------------------------------ texturas (sin Bukkit)

    /**
     * El hash de una textura tal como se pega en la config: el hash solo, la URL de
     * textures.minecraft.net o el "Value" en base64. Null si no es ninguna de las tres.
     */
    static String hash(String textura) {
        if (textura == null) return null;
        String t = textura.trim();
        if (t.isEmpty()) return null;
        if (HASH.matcher(t).matches()) return t.toLowerCase(Locale.ROOT);
        Matcher m = EN_URL.matcher(t);
        if (m.find()) return m.group(1).toLowerCase(Locale.ROOT);
        try {
            String limpio = t.replaceAll("\\s+", "");
            while (limpio.length() % 4 != 0) limpio += "=";
            Matcher enJson = EN_URL.matcher(new String(Base64.getDecoder().decode(limpio), StandardCharsets.UTF_8));
            if (enJson.find()) return enJson.group(1).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException noEsBase64) {
            // Ni hash, ni URL, ni base64: la textura no vale y sale el material de respaldo.
        }
        return null;
    }

    /** La propiedad textures de ese hash, en base64 (lo que Minecraft-Heads llama "Value"). */
    static String valor(String hash) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + URL + hash + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Un UUID fijo por textura: el cliente guarda la skin una vez y dos texturas no se pisan. */
    static UUID uuid(String hash) {
        return UUID.nameUUIDFromBytes(("calamity:cabeza:" + hash).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ objetos

    /** Una cabeza con esa textura; si la textura no vale (o el servidor no la acepta), el respaldo. */
    ItemStack conTextura(String textura, Material respaldo) {
        String h = hash(textura);
        if (h != null) {
            ItemStack base = dibujos.get(h);
            if (base == null) {
                base = dibujo(h);
                if (base != null) dibujos.put(h, base);
            }
            if (base != null) return base.clone();
        }
        return new ItemStack(respaldo(respaldo));
    }

    private static ItemStack dibujo(String hash) {
        try {
            ItemStack it = new ItemStack(Material.PLAYER_HEAD);
            if (!(it.getItemMeta() instanceof SkullMeta meta)) return null;
            PlayerProfile perfil = Bukkit.createProfile(uuid(hash));
            perfil.setProperty(new ProfileProperty("textures", valor(hash)));
            meta.setPlayerProfile(perfil);
            it.setItemMeta(meta);
            return it;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Material respaldo(Material m) {
        try {
            return m != null && m.isItem() ? m : Material.PAPER;
        } catch (Throwable t) {
            return Material.PAPER;
        }
    }

    /** La cabeza de un jugador con su skin, sin esperar a nadie (ver la cabecera). */
    ItemStack jugador(UUID u) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        if (u == null || !(it.getItemMeta() instanceof SkullMeta meta)) return it;
        try {
            Player conectado = Bukkit.getPlayer(u);
            Guardado g = perfiles.get(u);
            if (conectado != null) {
                PlayerProfile vivo = conectado.getPlayerProfile();
                meta.setPlayerProfile(vivo);
                if (vivo.hasTextures()) perfiles.put(u, new Guardado(vivo.clone(), System.currentTimeMillis()));
            } else if (g != null) {
                meta.setPlayerProfile(g.perfil());
                if (System.currentTimeMillis() - g.cuando() > CADUCA_MS) pedir(u);
            } else {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(u));
                pedir(u);
            }
            it.setItemMeta(meta);
        } catch (Throwable ignorado) {
            // Sin perfil sale una cabeza sin skin: mejor eso que un menu que no abre.
        }
        return it;
    }

    /** Pide a Paper el perfil completo de u, en su hilo; al llegar, alLlegar en el principal. */
    private void pedir(UUID u) {
        if (parado) return;
        long ahora = System.currentTimeMillis();
        Long antes = pedidos.get(u);
        if (antes != null && ahora - antes < REINTENTO_MS) return;
        if (enVuelo.get() >= EN_VUELO_MAX) return;
        pedidos.put(u, ahora);
        enVuelo.incrementAndGet();
        try {
            Bukkit.createProfile(u).update().whenComplete((perfil, error) -> {
                enVuelo.decrementAndGet();
                if (parado || error != null || perfil == null || !perfil.hasTextures()) return;
                perfiles.put(u, new Guardado(perfil, System.currentTimeMillis()));
                try {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!parado) alLlegar.accept(u);
                    });
                } catch (Throwable apagado) {
                    // El plugin se esta apagando: ya no hay menus que cambiar.
                }
            });
        } catch (Throwable t) {
            enVuelo.decrementAndGet();
        }
    }
}
