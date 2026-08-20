package net.ederus.edm.tienda;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lo que la tienda dice por el chat.
 *
 * Todo sale de mensajes.yml para que se pueda reescribir sin recompilar, y con
 * un prefijo delante siguiendo el convenio del resto del servidor
 * (EDERUS », LAG »...). EconomyShopGUI no tiene prefijo propio en su fichero de
 * idioma: el convenio viene de los otros plugins, no de el.
 */
public final class Mensajes {

    /** Marca una linea que debe salir SIN el prefijo. */
    private static final String SIN_PREFIJO = "{sin-prefijo}";

    private final Map<String, String> textos = new HashMap<>();
    private String prefijo = "";
    private ConfigurationSection rotacion;

    public void cargar(File fichero) {
        textos.clear();
        prefijo = "";
        rotacion = null;
        if (!fichero.exists()) return;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichero);
        prefijo = yml.getString("prefijo", "");
        rotacion = yml.getConfigurationSection("rotacion");
        for (String k : yml.getKeys(false)) {
            if (yml.isString(k)) textos.put(k, yml.getString(k, ""));
        }
    }

    /** El texto con sus marcadores puestos, ya con prefijo. */
    public Component de(String clave, String respaldo, String... pares) {
        String s = textos.getOrDefault(clave, respaldo);
        if (s == null || s.isEmpty()) return Component.empty();
        for (int i = 0; i + 1 < pares.length; i += 2) {
            s = s.replace(pares[i], pares[i + 1] == null ? "" : pares[i + 1]);
        }
        boolean sinPrefijo = s.startsWith(SIN_PREFIJO);
        if (sinPrefijo) s = s.substring(SIN_PREFIJO.length());
        return Estilo.legado((sinPrefijo ? "" : prefijo) + s);
    }

    public void manda(CommandSender a, String clave, String respaldo, String... pares) {
        Component c = de(clave, respaldo, pares);
        if (!Component.empty().equals(c)) a.sendMessage(c);
    }

    /** Texto plano, para los sitios que aun devuelven String. */
    public String plano(String clave, String respaldo, String... pares) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(de(clave, respaldo, pares));
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
