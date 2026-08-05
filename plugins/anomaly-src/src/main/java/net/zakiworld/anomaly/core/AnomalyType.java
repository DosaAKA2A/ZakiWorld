package net.zakiworld.anomaly.core;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.zakiworld.anomaly.AnomalyPlugin;
import net.zakiworld.anomaly.boss.Ability;
import net.zakiworld.anomaly.boss.BossFight;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.List;

/**
 * La ficha de una anomalia. Es lo que el menu enseña y lo que el anuncio del chat
 * cuenta en el hover: que es, de donde viene y a que se enfrenta la gente.
 *
 * Para añadir una anomalia nueva basta con implementar esto y registrarla en
 * AnomalyRegistry; ni el menu ni el anuncio ni el reparto de botin hay que tocarlos.
 */
public interface AnomalyType {

    String id();

    /** Nombre propio, el que sale en la barra de jefe y en el anuncio. */
    String display();

    /** Color de marca de la anomalia; tiñe el nombre, las barras y los mensajes. */
    TextColor color();

    /**
     * El color del brillo del jefe: el contorno se ve a traves de las paredes y es
     * como se la reconoce de lejos.
     *
     * Tiene que ser un color con nombre porque Minecraft saca el brillo del equipo de
     * marcador, y los equipos solo admiten los dieciseis colores clasicos.
     *
     * Devolver null es una decision de diseno, no un descuido: esa anomalia no brilla
     * ni levanta pilar de luz. Solo se sabe donde esta por las coordenadas del anuncio,
     * y cuando llegas ya te tiene encima.
     */
    NamedTextColor glowColor();

    /**
     * Tierra, agua o viento. No es una etiqueta: decide en que terreno puede aparecer.
     */
    Element element();

    /** Icono del menu. */
    Material icon();

    /**
     * Icono del menu cuando un material a secas no basta: una cabeza con skin, por
     * ejemplo. Si devuelve null se usa icon(), que es lo normal.
     */
    default org.bukkit.inventory.ItemStack iconItem() {
        return null;
    }

    /** Una linea que resume que es, para la lista del menu. */
    String tagline();

    /** De donde viene. Son las lineas del hover del anuncio. */
    List<String> origin();

    /** Aviso de peligro: a que se enfrentan. Tambien va en el hover. */
    List<String> threat();

    /** Vida base antes de escalar por numero de jugadores. */
    double baseHealth();

    /** Radio recomendado de la arena, para el aviso de coordenadas. */
    default int arenaRadius() {
        return 20;
    }

    /**
     * Instancias nuevas de todas sus habilidades. Se llama una vez por combate y
     * tambien desde el menu para pintar la lista, asi que no debe guardar estado.
     */
    List<Ability> abilities();

    BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where);
}
