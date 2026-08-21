package net.ederus.edm.tienda;

import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.Textos;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lo que la tienda dice por el chat.
 *
 * El grueso lo hace {@link Textos}: cargar el yml, el prefijo, los marcadores y
 * el {sin-prefijo}. Aqui solo queda lo que es de la tienda y de nadie mas, el
 * aviso de rotacion del mercado. EconomyShopGUI no tiene prefijo propio en su
 * fichero de idioma: el convenio (EDERUS », LAG »...) viene de los otros
 * plugins del servidor, no de el.
 */
public final class Mensajes extends Textos {

    private ConfigurationSection rotacion;

    @Override
    protected void alCargar(YamlConfiguration yml) {
        rotacion = yml.getConfigurationSection("rotacion");
    }

    // ------------------------------------------------------ aviso de rotacion

    public boolean avisoActivo() {
        return rotacion != null && rotacion.getBoolean("activo", true);
    }

    /**
     * Anuncia que el mercado ha rotado. Mismo formato que el aviso de las
     * tiendas de MobCoins del modulo core: bloque de chat con su raya, el
     * titulo centrado y un boton pinchable.
     */
    public void anunciarRotacion(Rotacion rot, Catalogo catalogo) {
        /* 'activo: false' apaga el aviso ENTERO, chat y Discord. El
         * /etienda webhook llama a aDiscord por su cuenta y si sale, porque es
         * una prueba a mano y ahi manda quien la pide. */
        if (!avisoActivo()) return;
        porChat();
        aDiscord(rot, catalogo);
    }

    private void porChat() {
        if (!avisoActivo()) return;

        List<String> lineas = rotacion.getStringList("chat");
        if (lineas.isEmpty()) return;

        Component boton = Estilo.legado(rotacion.getString("boton-texto", "&bABRIR"))
                .clickEvent(ClickEvent.runCommand(rotacion.getString("boton-comando", "/shop")))
                .hoverEvent(HoverEvent.showText(
                        Estilo.legado(rotacion.getString("boton-hover", "&7Clic para abrir"))));

        List<Component> bloque = new ArrayList<>();
        for (String l : lineas) {
            if (l.contains("%boton%")) {
                /* La linea del boton se parte para poder pegar el componente
                 * pinchable en medio: si se serializara entero perderia el clic. */
                String[] trozos = l.split("%boton%", 2);
                bloque.add(Estilo.legado(trozos[0]).append(boton)
                        .append(Estilo.legado(trozos.length > 1 ? trozos[1] : "")));
            } else {
                bloque.add(Estilo.legado(l).decoration(TextDecoration.ITALIC, false));
            }
        }

        Sound sonido = null;
        String nombreSonido = rotacion.getString("sonido", "");
        if (!nombreSonido.isBlank()) {
            try {
                sonido = Registry.SOUNDS.get(NamespacedKey.minecraft(
                        nombreSonido.toLowerCase(Locale.ROOT).replace('_', '.')));
                if (sonido == null) sonido = Registry.SOUNDS.match(nombreSonido);
            } catch (Exception e) { /* un sonido mal escrito no impide el aviso */ }
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            bloque.forEach(p::sendMessage);
            if (sonido != null) p.playSound(p.getLocation(), sonido, 1f, 1f);
        }
        bloque.forEach(Bukkit.getConsoleSender()::sendMessage);
    }

    // ------------------------------------------------------------------ discord

    /** La URL del canal, o vacio si no se ha puesto ninguna. */
    public String webhook() {
        return rotacion == null ? "" : rotacion.getString("webhook", "");
    }

    public boolean hayWebhook() {
        return net.ederus.edm.comun.Webhook.configurada(webhook());
    }

    /**
     * Manda a Discord lo que le toco hoy al Mercado.
     *
     * No es el mismo texto que el del chat: alli hay codigos de color y un
     * bloque centrado a pixel que en Discord no significa nada. Aqui va la
     * lista de verdad, que es lo que la gente quiere ver desde el movil.
     */
    public void aDiscord(Rotacion rot, Catalogo catalogo) {
        String url = webhook();
        if (!net.ederus.edm.comun.Webhook.configurada(url)) return;

        List<String> partes = new ArrayList<>();
        String intro = rotacion.getString("webhook-texto", "Las ofertas y la demanda del día han cambiado.");
        if (intro != null && !intro.isBlank()) { partes.add(intro); partes.add(""); }

        int tope = Math.max(1, rotacion.getInt("webhook-cuantos", 10));
        pinta(partes, "**Ofertas del día** — más baratas al comprar",
                rot == null ? List.of() : rot.ofertas(), catalogo, tope, true);
        pinta(partes, "**Demanda del día** — se paga más al vender",
                rot == null ? List.of() : rot.demandas(), catalogo, tope, false);

        net.ederus.edm.comun.Webhook.aviso(url,
                rotacion.getString("webhook-titulo", "El Mercado se renovó"),
                net.ederus.edm.comun.Webhook.lineas(partes),
                0x0083FD,
                rotacion.getString("webhook-pie", "Vuelve a cambiar a medianoche · /shop"),
                registro);
    }

    private static void pinta(List<String> partes, String titulo, List<Rotacion.Trato> tratos,
                              Catalogo catalogo, int tope, boolean descuento) {
        if (tratos.isEmpty()) return;
        partes.add(titulo);
        int puestos = 0;
        for (Rotacion.Trato t : tratos) {
            Catalogo.Articulo a = catalogo == null ? null : catalogo.de(t.clave());
            if (a == null) continue;
            if (puestos >= tope) {
                partes.add("· y " + (tratos.size() - puestos) + " más");
                break;
            }
            int pc = (int) Math.round(Math.abs(1 - t.factor()) * 100);
            partes.add("· " + Motor.nombre(a) + "  " + (descuento ? "−" : "+") + pc + "%");
            puestos++;
        }
        partes.add("");
    }

    /** Para que los fallos de Discord salgan en la consola del servidor. */
    private java.util.logging.Logger registro;

    public void registro(java.util.logging.Logger log) { this.registro = log; }
}
