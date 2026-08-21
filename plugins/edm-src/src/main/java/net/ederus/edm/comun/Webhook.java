package net.ederus.edm.comun;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

/**
 * Mandar un aviso a Discord.
 *
 * Vive aqui y no dentro de un modulo porque lo usan el core (las tiendas de
 * MobCoins) y la tienda (la rotacion del Mercado), y dos copias de esto acaban
 * divergiendo en el detalle tonto: una escapa las comillas y la otra no.
 *
 * TRES REGLAS:
 *
 *  1. NUNCA bloquea. Se manda con sendAsync y nadie espera la respuesta: si
 *     Discord esta caido o tarda cinco segundos, el servidor ni se entera. Un
 *     aviso es un adorno; que se pierda uno no puede costar un tiron.
 *  2. UN SOLO cliente para todo el plugin. Cada HttpClient se lleva su hilo
 *     selector y su pool, y no se cierran solos.
 *  3. La URL NO se escribe en el log. Es una credencial: quien la tenga puede
 *     publicar en ese canal.
 */
public final class Webhook {

    /** Discord corta los embeds ahi; se recorta antes para no comerse un 400. */
    private static final int MAX_TITULO = 256;
    private static final int MAX_CUERPO = 4000;

    private static volatile HttpClient cliente;

    private Webhook() { }

    private static HttpClient cliente() {
        HttpClient c = cliente;
        if (c == null) {
            synchronized (Webhook.class) {
                c = cliente;
                if (c == null) {
                    c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                    cliente = c;
                }
            }
        }
        return c;
    }

    public static boolean configurada(String url) {
        return url != null && !url.isBlank() && url.startsWith("https://");
    }

    /** Un mensaje de texto pelado. */
    public static void texto(String url, String contenido, Logger log) {
        if (!configurada(url) || contenido == null || contenido.isBlank()) return;
        mandar(url, "{\"content\":\"" + escapar(recortar(contenido, MAX_CUERPO)) + "\"}", log);
    }

    /**
     * Un aviso con formato: titulo, cuerpo y una raya de color al lado.
     *
     * @param color el de la raya, en RGB (0x0083FD es el azul de Ederus)
     * @param pie   la linea pequena de abajo, o null
     */
    public static void aviso(String url, String titulo, String cuerpo, int color, String pie, Logger log) {
        if (!configurada(url)) return;
        StringBuilder j = new StringBuilder("{\"embeds\":[{");
        j.append("\"title\":\"").append(escapar(recortar(titulo, MAX_TITULO))).append('"');
        if (cuerpo != null && !cuerpo.isBlank()) {
            j.append(",\"description\":\"").append(escapar(recortar(cuerpo, MAX_CUERPO))).append('"');
        }
        j.append(",\"color\":").append(color & 0xFFFFFF);
        if (pie != null && !pie.isBlank()) {
            j.append(",\"footer\":{\"text\":\"").append(escapar(recortar(pie, MAX_TITULO))).append("\"}");
        }
        j.append("}]}");
        mandar(url, j.toString(), log);
    }

    /** Junta las lineas con salto, saltandose las vacias. */
    public static String lineas(List<String> partes) {
        StringBuilder sb = new StringBuilder();
        for (String p : partes) {
            if (p == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(p);
        }
        return sb.toString();
    }

    private static void mandar(String url, String cuerpo, Logger log) {
        try {
            HttpRequest peticion = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpo, StandardCharsets.UTF_8))
                    .build();
            cliente().sendAsync(peticion, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(r -> {
                        /* 204 es lo normal. Un 4xx suele ser la URL mal copiada o
                         * el webhook borrado, y conviene saberlo. */
                        if (r.statusCode() >= 400 && log != null) {
                            log.warning("Discord rechazo el aviso (HTTP " + r.statusCode()
                                    + "). Revisa la URL del webhook.");
                        }
                    })
                    .exceptionally(e -> {
                        if (log != null) log.warning("No se pudo avisar a Discord: " + e.getMessage());
                        return null;
                    });
        } catch (IllegalArgumentException e) {
            /* Sin imprimir la URL: es una credencial. */
            if (log != null) log.warning("La URL del webhook no es valida.");
        }
    }

    private static String recortar(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** Lo minimo para que un texto quepa dentro de una cadena JSON. */
    public static String escapar(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
