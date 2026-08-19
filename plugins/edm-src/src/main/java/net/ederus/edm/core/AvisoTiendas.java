package net.ederus.edm.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Avisa cuando las tiendas de MobCoins se renuevan.
 *
 * UltimateMobCoins no tiene anuncio propio, pero deja en su carpeta de datos el
 * sello de tiempo del PROXIMO refresco. Cuando ese sello cambia es que la tienda
 * acaba de rotar, asi que basta con vigilarlo y avisar. No hace falta adivinar la
 * hora ni programar nada a mano: si alguien fuerza un refresco, tambien se entera.
 */
public final class AvisoTiendas {

    /** El sello vive en una linea suelta 'refresh-time: 1786147090486'. */
    private static final Pattern SELLO = Pattern.compile("refresh-time:\\s*(\\d+)");

    private final EderusMain plugin;
    private BukkitTask tarea;

    private Path rutaDiaria;
    private Path rutaBoveda;
    private long ultimoDiaria = -1L;
    private long ultimoBoveda = -1L;
    private boolean primeraVuelta = true;

    public AvisoTiendas(EderusMain plugin) {
        this.plugin = plugin;
    }

    /** Arranca (o rearranca) la vigilancia con lo que diga el config. */
    public void iniciar() {
        detener();
        if (!plugin.getConfig().getBoolean("tiendas.activo", true)) {
            plugin.getLogger().info("Aviso de tiendas desactivado en el config.");
            return;
        }
        rutaDiaria = Path.of(plugin.getConfig().getString("tiendas.ruta-diaria",
                "plugins/UltimateMobCoins/data/rotating_shop-data.yml"));
        rutaBoveda = Path.of(plugin.getConfig().getString("tiendas.ruta-boveda",
                "plugins/UltimateMobCoins/data/shop_with_timer-data.yml"));

        int segundos = Math.max(5, plugin.getConfig().getInt("tiendas.revisar-cada-segundos", 30));

        // La primera vuelta solo toma la foto inicial: si no, avisaria en cada arranque.
        primeraVuelta = true;
        ultimoDiaria = -1L;
        ultimoBoveda = -1L;

        long ticks = segundos * 20L;
        tarea = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::revisar, ticks, ticks);
        plugin.getLogger().info("Aviso de tiendas activo | revisando cada " + segundos + "s");
    }

    public void detener() {
        if (tarea != null) {
            tarea.cancel();
            tarea = null;
        }
    }

    private void revisar() {
        long diaria = leerSello(rutaDiaria);
        long boveda = leerSello(rutaBoveda);

        if (primeraVuelta) {
            ultimoDiaria = diaria;
            ultimoBoveda = boveda;
            primeraVuelta = false;
            return;
        }

        if (diaria > 0 && ultimoDiaria > 0 && diaria != ultimoDiaria) {
            anunciar("tiendas.diaria", "Mercado del Dia", true);
        }
        if (boveda > 0 && ultimoBoveda > 0 && boveda != ultimoBoveda) {
            anunciar("tiendas.boveda", "La Boveda", true);
        }

        if (diaria > 0) ultimoDiaria = diaria;
        if (boveda > 0) ultimoBoveda = boveda;
    }

    /** Devuelve el sello del fichero, o -1 si no se puede leer. */
    private long leerSello(Path ruta) {
        if (ruta == null) return -1L;
        try {
            String texto = Files.readString(ruta, StandardCharsets.UTF_8);
            Matcher m = SELLO.matcher(texto);
            if (m.find()) return Long.parseLong(m.group(1));
        } catch (IOException | NumberFormatException e) {
            // El plugin reescribe el fichero de vez en cuando; una lectura fallida
            // suelta no es un problema, se reintenta en la siguiente vuelta.
        }
        return -1L;
    }

    /**
     * Lanza el aviso a mano, sin esperar a que rote la tienda. Es para /main aviso:
     * probar el mensaje sin tener que forzar un refresco y aguantar la espera.
     * No avisa a Discord, que una prueba no tiene por que salir del juego.
     *
     * @return false si esa tienda no tiene mensajes configurados.
     */
    public boolean probar(String cual) {
        boolean diaria = cual.equalsIgnoreCase("diaria");
        String base = diaria ? "tiendas.diaria" : "tiendas.boveda";
        if (plugin.getConfig().getStringList(base + ".chat").isEmpty()) return false;
        anunciar(base, diaria ? "Mercado del Dia" : "La Boveda", false);
        return true;
    }

    private void anunciar(String base, String queTienda, boolean avisarDiscord) {
        List<String> lineas = plugin.getConfig().getStringList(base + ".chat");
        if (lineas.isEmpty()) return;

        String sonido = plugin.getConfig().getString(base + ".sonido", "");
        String botonTexto = plugin.getConfig().getString(base + ".boton-texto", "");
        String botonCmd = plugin.getConfig().getString(base + ".boton-comando", "/mobcoins");
        String botonHover = plugin.getConfig().getString(base + ".boton-hover", "");

        // El aviso va SOLO por chat: nada de titulos en pantalla.
        // Todo lo que toca a jugadores va por el hilo principal.
        Bukkit.getScheduler().runTask(plugin, () -> {
            Component boton = botonTexto.isBlank() ? null : Component.text(color(botonTexto))
                    .clickEvent(ClickEvent.runCommand(botonCmd))
                    .hoverEvent(HoverEvent.showText(Component.text(color(
                            botonHover.isBlank() ? "Clic para abrir" : botonHover))));

            for (Player p : Bukkit.getOnlinePlayers()) {
                for (String cruda : lineas) {
                    // La linea que lleve %boton% se manda como componente pinchable.
                    if (boton != null && cruda.contains("%boton%")) {
                        String[] trozos = color(cruda).split("%boton%", -1);
                        p.sendMessage(Component.text(trozos[0])
                                .append(boton)
                                .append(Component.text(trozos.length > 1 ? trozos[1] : "")));
                    } else {
                        p.sendMessage(color(cruda));
                    }
                }
                if (!sonido.isBlank()) {
                    try {
                        p.playSound(p.getLocation(), sonido, 0.8f, 1.2f);
                    } catch (Exception e) {
                        // Un nombre de sonido invalido no debe tumbar el anuncio.
                    }
                }
            }

            for (String cruda : lineas) {
                Bukkit.getConsoleSender().sendMessage(color(cruda).replace("%boton%", ""));
            }
        });

        if (avisarDiscord) enviarWebhook(queTienda);
    }

    private static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    /** Aviso opcional a Discord. Si no hay URL configurada, no hace nada. */
    private void enviarWebhook(String queTienda) {
        String url = plugin.getConfig().getString("tiendas.webhook", "");
        if (url == null || url.isBlank()) return;

        String texto = plugin.getConfig().getString("tiendas.webhook-texto",
                "Se ha renovado la tienda de MobCoins: %tienda%").replace("%tienda%", queTienda);
        String cuerpo = "{\"content\":\"" + escapar(texto) + "\"}";

        try {
            HttpClient cliente = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest peticion = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpo, StandardCharsets.UTF_8))
                    .build();
            cliente.sendAsync(peticion, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(e -> {
                        plugin.getLogger().warning("No se pudo avisar a Discord: " + e.getMessage());
                        return null;
                    });
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("La URL del webhook no es valida: " + url);
        }
    }

    private static String escapar(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
