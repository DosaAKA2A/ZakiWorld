package net.ederus.edm.comun;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Preguntarle algo al jugador por el chat.
 *
 * Es lo que deja escribir una cantidad exacta o un texto de busqueda sin yunques
 * ni libros: se cierra el menu, se espera UNA linea y se vuelve a abrir donde
 * estaba.
 *
 * Tres cuidados que no son opcionales:
 *
 *  1. El evento de chat es ASINCRONO. Abrir un inventario desde ahi tumba el
 *     servidor, asi que la respuesta se atiende siempre con runTask.
 *  2. La pregunta CADUCA. Sin caducidad, el que se olvida se queda con la
 *     siguiente frase que escriba secuestrada, aunque sea media hora despues.
 *  3. La linea se cancela para que no salga por el chat publico: nadie quiere
 *     que su "128" lo lea el servidor entero.
 */
public final class EntradaChat implements Listener {

    /** Lo que se espera de un jugador y hasta cuando. */
    private record Pregunta(Consumer<String> respuesta, Runnable alCancelar, long caduca) { }

    private static final long VIDA_MS = 60_000L;

    /** Lo que escribe para salir sin hacer nada. */
    private static final String CANCELAR = "cancelar";

    private final Plugin plugin;
    private final Map<UUID, Pregunta> esperando = new ConcurrentHashMap<>();

    public EntradaChat(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Cierra lo que tenga abierto y espera una linea suya. */
    public void pedir(Player jugador, Consumer<String> respuesta, Runnable alCancelar) {
        esperando.put(jugador.getUniqueId(),
                new Pregunta(respuesta, alCancelar, System.currentTimeMillis() + VIDA_MS));
        /* El cierre va un tick despues a proposito: esto se llama desde dentro
         * de un clic de inventario, y cerrar la ventana en mitad de su propio
         * evento es la clase de cosa que deja el cursor con un item fantasma. */
        plugin.getServer().getScheduler().runTask(plugin, () -> jugador.closeInventory());
    }

    public void olvidar(Player jugador) {
        esperando.remove(jugador.getUniqueId());
    }

    /*
     * Paper dispara DOS eventos por cada linea de chat: el viejo
     * AsyncPlayerChatEvent (para los plugins que aun lo usan) y el nuevo
     * AsyncChatEvent. Y el viejo va PRIMERO. AlonsoChat, el chat de OneBlock,
     * escucha el viejo y manda la linea a todos con sendMessage; cuando llega
     * nuestro turno en el nuevo, ya la ha visto el servidor entero (2026-09-19,
     * un "31" de precio en el chat publico). Por eso se escucha en los dos: en
     * el viejo a LOWEST se cancela antes de que nadie la pinte (AlonsoChat si
     * mira isCancelled), y en el nuevo se hace lo mismo por si el servidor no
     * tiene ningun plugin del viejo.
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void alEscribirLegado(org.bukkit.event.player.AsyncPlayerChatEvent e) {
        if (!atender(e.getPlayer(), e.getMessage())) return;
        e.setCancelled(true);
        try {
            e.getRecipients().clear();
        } catch (Throwable ignored) {
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void alEscribir(AsyncChatEvent e) {
        if (!atender(e.getPlayer(), PlainTextComponentSerializer.plainText().serialize(e.message()))) return;
        e.setCancelled(true);
        /* Cancelar no basta: el plugin de chat del servidor pinta la linea igual
         * (escucha en su propia prioridad y no mira si esta cancelado). Sin
         * espectadores no hay a quien pintarsela, y en MONITOR se remata. Si el
         * conjunto no se dejara tocar, la respuesta tiene que llegar igual. */
        try {
            e.viewers().clear();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Si el jugador tenia una pregunta viva, se la queda esta linea: devuelve
     * true y programa la respuesta en el hilo principal. El primero de los dos
     * eventos que llegue se la lleva; el segundo ya no encuentra pregunta y la
     * deja pasar, pero alRematar la sigue cancelando porque quedo anotada.
     */
    private boolean atender(Player jugador, String mensaje) {
        Pregunta p = esperando.remove(jugador.getUniqueId());
        if (p == null) return respondidas.containsKey(jugador.getUniqueId());
        /* Caducada: la frase vuelve a ser suya y sale por el chat normal. */
        if (System.currentTimeMillis() > p.caduca()) return false;

        respondidas.put(jugador.getUniqueId(), System.currentTimeMillis());
        String texto = mensaje.trim();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!jugador.isOnline()) return;
            if (texto.isEmpty() || texto.equalsIgnoreCase(CANCELAR)) {
                if (p.alCancelar() != null) p.alCancelar().run();
                return;
            }
            p.respuesta().accept(texto);
        });
        return true;
    }

    /** Quien acaba de responder, para que ni un plugin que descancele el evento la muestre. */
    private final Map<UUID, Long> respondidas = new ConcurrentHashMap<>();

    /* Se quita del mapa en el evento NUEVO, que es el ultimo de los dos: si se
     * quitara en el viejo, el nuevo llegaria sin nota y la linea saldria. */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR)
    public void alRematarLegado(org.bukkit.event.player.AsyncPlayerChatEvent e) {
        Long cuando = respondidas.get(e.getPlayer().getUniqueId());
        if (cuando == null || System.currentTimeMillis() - cuando > 2000) return;
        e.setCancelled(true);
        try {
            e.getRecipients().clear();
        } catch (Throwable ignored) {
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alRematar(AsyncChatEvent e) {
        Long cuando = respondidas.remove(e.getPlayer().getUniqueId());
        if (cuando == null || System.currentTimeMillis() - cuando > 2000) return;
        e.setCancelled(true);
        try {
            e.viewers().clear();
        } catch (Throwable ignored) {
        }
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        esperando.remove(e.getPlayer().getUniqueId());
        respondidas.remove(e.getPlayer().getUniqueId());
    }
}
