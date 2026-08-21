package net.ederus.edm.anomaly.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URL;
import java.util.UUID;

/**
 * Hacer que una criatura del plugin se vea como una PERSONA.
 *
 * Hay dos niveles y se usan los dos:
 *
 *  - Con LibsDisguises (Ederus lo tiene), la entidad se ve como un jugador entero, con
 *    su skin, sus brazos y su forma. Es lo que hace que Rabby parezca un vecino y que
 *    el Mimic sea de verdad la copia de alguien.
 *  - Sin el, queda el apano de siempre: la cabeza con la textura puesta como casco. Se
 *    nota que debajo hay un zombi, pero el plugin arranca igual.
 *
 * Todo el hook va por reflexion, como el de WorldGuard: si manana quitan el plugin,
 * esto no compila menos ni revienta, solo se ve peor.
 */
public final class Disguises {

    private Disguises() {
    }

    public static boolean available(Plugin plugin) {
        return plugin.getServer().getPluginManager().getPlugin("LibsDisguises") != null;
    }

    /**
     * Disfraza a la entidad de jugador.
     *
     * @param name nombre que se vera encima
     * @param skin skin a usar: el nombre de un jugador de verdad, o el valor base64 de
     *             la propiedad de texturas. Si es null se usa el propio nombre.
     * @return true si el disfraz se puso
     */
    public static boolean asPlayer(Plugin plugin, Entity entity, String name, String skin) {
        if (entity == null || !available(plugin)) return false;
        try {
            Class<?> playerDisguise = Class.forName("me.libraryaddict.disguise.disguisetypes.PlayerDisguise");
            Object disguise = playerDisguise.getConstructor(String.class).newInstance(name);
            if (skin != null && !skin.isBlank()) {
                playerDisguise.getMethod("setSkin", String.class).invoke(disguise, skin);
            }
            Class<?> base = Class.forName("me.libraryaddict.disguise.disguisetypes.Disguise");
            // Que el disfraz no se caiga solo al alejarse el jugador que lo mira.
            try {
                base.getMethod("setKeepDisguiseOnPlayerDeath", boolean.class).invoke(disguise, true);
            } catch (Throwable ignored) {
            }
            Class<?> api = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            api.getMethod("disguiseEntity", Entity.class, base).invoke(null, entity, disguise);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().info("No se pudo disfrazar de jugador (" + t.getClass().getSimpleName()
                    + "); se usara la cabeza con skin.");
            return false;
        }
    }

    /** Le quita el disfraz, si lo llevaba. */
    public static void clear(Plugin plugin, Entity entity) {
        if (entity == null || !available(plugin)) return;
        try {
            Class<?> api = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            api.getMethod("undisguiseToAll", Entity.class).invoke(null, entity);
        } catch (Throwable ignored) {
        }
    }

    /**
     * El perfil de una skin concreta, listo para ponerselo a un MANNEQUIN.
     *
     * Esta es la via buena: el maniqui es una entidad viva con forma de jugador y
     * perfil propio, asi que la skin es EXACTAMENTE la que se le pide, sin depender
     * de que haya LibsDisguises ni de que Mojang resuelva un nombre.
     *
     * @param hash el identificador de textura de Mojang (lo que va detras de
     *             textures.minecraft.net/texture/)
     */
    public static io.papermc.paper.datacomponent.item.ResolvableProfile profileOf(
            Plugin plugin, String hash, String name) {
        try {
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/"
                    + hash + "\"}}}";
            String value = java.util.Base64.getEncoder()
                    .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Las tres piezas, y las tres hacen falta:
            //
            //  - LA TEXTURA, que es la skin de verdad.
            //  - EL UUID, para que no se confunda con ninguna cuenta real.
            //  - EL NOMBRE, porque el cliente indexa las skins de los cuerpos de jugador
            //    por nombre: sin el se queda con la de por defecto aunque la textura
            //    viaje en el paquete. Es lo mismo que pasa con los servidores en modo
            //    offline, donde las skins se sirven sin firmar y se ven igual.
            //
            // Lo que NO se puede hacer es armar el perfil desde un PlayerProfile con
            // nombre: eso sale "dinamico", el servidor lo resuelve contra Mojang y se
            // queda con la skin del jugador que se llame asi. Por el builder, con la
            // propiedad ya puesta, el perfil es estatico y la textura sobrevive.
            var profile = io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile()
                    .uuid(UUID.nameUUIDFromBytes(("skin:" + hash).getBytes()))
                    .name(name)
                    .addProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", value))
                    .build();
            if (profile.dynamic()) {
                plugin.getLogger().warning("El perfil de la skin " + hash.substring(0, 8)
                        + " salio DINAMICO: se resolvera online y no se vera la textura pedida.");
            }
            return profile;
        } catch (Throwable t) {
            plugin.getLogger().warning("No se pudo construir el perfil de la skin " + hash + ": " + t);
            return null;
        }
    }

    /**
     * El perfil de una CUENTA DE VERDAD, por su nombre.
     *
     * Esta es la unica forma de que un maniqui lleve una skin concreta, y costo
     * averiguarlo: el cuerpo con forma de jugador NO pinta una textura suelta por mucho
     * que el dato sea correcto —comprobado con el NBT delante y con la URL de Mojang
     * devolviendo el PNG bueno—, pero SI pinta el perfil de una cuenta existente, porque
     * ese se resuelve firmado contra Mojang.
     *
     * Asi que la receta es: alguien se pone la skin en una cuenta, y aqui se apunta a
     * esa cuenta. El nombre que se ve encima del jefe es otra cosa y se pone aparte.
     */
    public static io.papermc.paper.datacomponent.item.ResolvableProfile profileOfAccount(
            Plugin plugin, String account) {
        try {
            return io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile()
                    .name(account)
                    .build();
        } catch (Throwable t) {
            plugin.getLogger().warning("No se pudo preparar el perfil de la cuenta " + account + ": " + t);
            return null;
        }
    }

    /**
     * Resuelve de verdad el perfil de una cuenta y lo entrega ya completo.
     *
     * ESTO ES LO QUE FALTABA. Un perfil "dinamico" no se resuelve solo: lleva el nombre
     * apuntado y nada mas, asi que el cliente, al no encontrar textura, pinta una de las
     * skins de serie. Hay que pedir la resolucion a mano —que sale a Mojang por la red,
     * y por eso va fuera del hilo principal— y solo entonces se tiene un perfil con la
     * textura firmada dentro.
     *
     * @param andThen se ejecuta EN EL HILO PRINCIPAL con el perfil ya resuelto
     */
    public static void resolveAccount(Plugin plugin, String account,
                                      java.util.function.Consumer<io.papermc.paper.datacomponent.item.ResolvableProfile> andThen) {
        var cached = CACHE.get(account.toLowerCase(java.util.Locale.ROOT));
        if (cached != null) {
            andThen.accept(cached);
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(net.ederus.edm.Module.dueno(plugin), () -> {
            var built = lookup(plugin, account);
            if (built == null) return;
            CACHE.put(account.toLowerCase(java.util.Locale.ROOT), built);
            plugin.getServer().getScheduler().runTask(net.ederus.edm.Module.dueno(plugin), () -> andThen.accept(built));
        });
    }

    /** Una skin resuelta se guarda: no hace falta preguntarle a Mojang en cada pelea. */
    private static final java.util.Map<String, io.papermc.paper.datacomponent.item.ResolvableProfile>
            CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Se vacia al apagar: es estatico, asi que si no, sobrevive al plugin y
     *  se lleva por delante su cargador de clases en un reload. */
    public static void limpiarCache() {
        CACHE.clear();
    }

    /**
     * Le pregunta a Mojang por la cuenta y arma el perfil con la textura FIRMADA.
     *
     * Se hace la consulta a mano en vez de fiarse de `resolve()` porque ese depende del
     * modo del servidor: en un servidor en modo offline devuelve el perfil vacio, sin
     * texturas, y entonces el cuerpo sale con una skin de serie. Dos peticiones: el
     * nombre da el uuid y el uuid da las propiedades, firma incluida.
     *
     * Va SIEMPRE fuera del hilo principal.
     */
    private static io.papermc.paper.datacomponent.item.ResolvableProfile lookup(Plugin plugin, String account) {
        try {
            var http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(8)).build();
            var gson = new com.google.gson.Gson();

            var idRes = http.send(java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("https://api.mojang.com/users/profiles/minecraft/" + account))
                            .timeout(java.time.Duration.ofSeconds(10)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (idRes.statusCode() != 200) {
                plugin.getLogger().warning("La cuenta " + account + " no existe o Mojang no responde ("
                        + idRes.statusCode() + "); el cuerpo se quedara con la skin de serie.");
                return null;
            }
            var idJson = gson.fromJson(idRes.body(), com.google.gson.JsonObject.class);
            String raw = idJson.get("id").getAsString();
            UUID uuid = UUID.fromString(raw.replaceFirst(
                    "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));

            var profRes = http.send(java.net.http.HttpRequest.newBuilder()
                            // unsigned=false trae la FIRMA de Mojang. Sin ella el perfil
                            // es autentico a medias, y hay clientes que solo se fian de
                            // los perfiles firmados para pintar un cuerpo de jugador.
                            .uri(java.net.URI.create(
                                    "https://sessionserver.mojang.com/session/minecraft/profile/"
                                            + raw + "?unsigned=false"))
                            .timeout(java.time.Duration.ofSeconds(10)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (profRes.statusCode() != 200) {
                plugin.getLogger().warning("Mojang no devolvio el perfil de " + account
                        + " (" + profRes.statusCode() + ").");
                return null;
            }
            var profJson = gson.fromJson(profRes.body(), com.google.gson.JsonObject.class);
            String name = profJson.get("name").getAsString();

            var builder = io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile()
                    .uuid(uuid).name(name);
            int found = 0;
            for (var el : profJson.getAsJsonArray("properties")) {
                var prop = el.getAsJsonObject();
                if (!"textures".equals(prop.get("name").getAsString())) continue;
                String value = prop.get("value").getAsString();
                String signature = prop.has("signature") ? prop.get("signature").getAsString() : null;
                builder.addProperty(signature == null
                        ? new com.destroystokyo.paper.profile.ProfileProperty("textures", value)
                        : new com.destroystokyo.paper.profile.ProfileProperty("textures", value, signature));
                found++;
            }
            if (found == 0) {
                plugin.getLogger().warning("La cuenta " + account + " no tiene skin puesta.");
                return null;
            }
            plugin.getLogger().info("Skin de " + account + " lista (uuid " + uuid + ").");
            return builder.build();
        } catch (Throwable t) {
            plugin.getLogger().warning("No se pudo consultar la skin de " + account + ": " + t);
            return null;
        }
    }

    /**
     * El perfil de un jugador de verdad, para copiarle la cara tal cual.
     *
     * Se deja DINAMICO —solo su uuid y su nombre— para que el servidor lo resuelva
     * firmado contra Mojang. Copiar la propiedad de texturas a mano no vale: el cuerpo
     * con forma de jugador no la pinta.
     */
    public static io.papermc.paper.datacomponent.item.ResolvableProfile profileOf(
            Plugin plugin, org.bukkit.entity.Player player) {
        try {
            var props = player.getPlayerProfile().getProperties();
            var builder = io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile()
                    .uuid(player.getUniqueId())
                    .name(player.getName());
            // Un jugador conectado YA trae sus texturas firmadas: el servidor se las
            // pidio a Mojang al entrar. Copiarlas es lo unico que hace falta, y ademas
            // asi la copia sale al instante. Sin ellas el perfil se queda en blanco y
            // la copia aparece con una skin de serie, que fue justo lo que paso.
            if (!props.isEmpty()) builder.addProperties(props);
            else plugin.getLogger().warning("El perfil de " + player.getName()
                    + " no trae texturas; la copia saldra con la skin de serie.");
            return builder.build();
        } catch (Throwable t) {
            plugin.getLogger().warning("No se pudo copiar el perfil de " + player.getName() + ": " + t);
            return null;
        }
    }

    /**
     * Una cabeza de jugador con una textura concreta puesta.
     *
     * @param hash el identificador de textura de Mojang (lo que va detras de
     *             textures.minecraft.net/texture/)
     */
    public static ItemStack head(Plugin plugin, String hash, String name) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        try {
            PlayerProfile profile = Bukkit.createPlayerProfile(
                    UUID.nameUUIDFromBytes(hash.getBytes()), name);
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(new URL("https://textures.minecraft.net/texture/" + hash));
            profile.setTextures(textures);
            if (item.getItemMeta() instanceof SkullMeta meta) {
                meta.setOwnerProfile(profile);
                item.setItemMeta(meta);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("No se pudo poner la textura " + hash + " en una cabeza: " + t);
        }
        return item;
    }
}
