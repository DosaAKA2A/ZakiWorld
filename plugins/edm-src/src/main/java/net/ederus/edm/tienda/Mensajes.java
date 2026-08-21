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
    public void anunciarRotacion() {
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
}
