package net.ederus.lethalworld.hardcore;

import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.boss.Ability;
import net.ederus.edm.anomaly.boss.BossFight;
import net.ederus.edm.anomaly.core.ActiveAnomaly;
import net.ederus.edm.anomaly.core.AnomalyClass;
import net.ederus.edm.anomaly.core.AnomalyType;
import net.ederus.edm.anomaly.core.Element;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * La ficha de la PARCA en el catalogo de EDM (1.2.0): lo que /anomaly ensena en el menu y lo
 * que el anuncio cuenta en el hover. Clase DIOS de serie, como Alba.
 *
 * Se registra desde LethalWorld (PuenteAnomalia) y no desde EDM: la pelea necesita a Calamity
 * (la Huella, la Aduana, las amenazas) y EDM no sabe nada de eso. Por eso create() pregunta
 * al puente si hay un encargo de la Huella: con el, es la PARCA de un AFK; sin el, la abrio
 * alguien a mano con /anomaly y pelea sin presa, como la prueba.
 */
final class ParcaType implements AnomalyType {

    private final PuenteAnomalia puente;

    ParcaType(PuenteAnomalia puente) {
        this.puente = puente;
    }

    @Override
    public String id() {
        return ParcaAnomalia.ID;
    }

    @Override
    public String display() {
        return "PARCA";
    }

    @Override
    public TextColor color() {
        return Paleta.PARCA;
    }

    /**
     * Rojo: el color con nombre mas cerca del coral de la Paleta. El contorno lo lleva el
     * maniqui (CuerpoNpc), que es lo que se ve; el esqueleto de debajo va invisible.
     */
    @Override
    public NamedTextColor glowColor() {
        return NamedTextColor.RED;
    }

    @Override
    public Element element() {
        return Element.TIERRA;
    }

    @Override
    public Material icon() {
        return Material.NETHERITE_HOE;
    }

    @Override
    public AnomalyClass defaultClass() {
        return AnomalyClass.DIOS;
    }

    @Override
    public String tagline() {
        return "La que viene a por los que se quedan quietos";
    }

    @Override
    public List<String> origin() {
        return List.of(
                "Calamity no perdona a quien se queda quieto.",
                "Cada respiración de más deja una huella,",
                "y ella la sigue. No corre. Nunca corre.",
                "Pero siempre llega.");
    }

    @Override
    public List<String> threat() {
        return List.of(
                "DIOS: pelea a CUATRO fases, cada una con su luto.",
                "Siega, cadenas, plañideras, pasos que cruzan",
                "el umbral y una Sentencia que se cuenta en",
                "campanadas. Quien se quede quieto, cae.");
    }

    /**
     * Solo para el menu: la vida de verdad sale de la formula de Calamity (Parca.vidaLogica, por
     * nivel, repeticiones y marcados), no del ajuste de vida de /anomaly. Esto es la de N 60.
     */
    @Override
    public double baseHealth() {
        return Parca.vidaLogica(puente.gestor().ajustes(), 60, 0, 0);
    }

    @Override
    public int arenaRadius() {
        return 18;
    }

    /** Una Ability por tecnica. La accion la lanza a mano (/anomaly test): la eleccion normal es de la pelea. */
    @Override
    public List<Ability> abilities() {
        List<Ability> out = new ArrayList<>();
        for (HabilidadParca h : HabilidadParca.values()) {
            out.add(new Ability(h.id, h.nombre, h.descripcionMenu(), h.faseMenu(), h.espera, h.duracion, h.peso,
                    h.icono(), f -> {
                        if (f instanceof ParcaAnomalia pa) pa.forzarDesdeEdm(h);
                    }));
        }
        return out;
    }

    @Override
    public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        return new ParcaAnomalia(plugin, event, where, puente, puente.tomar());
    }
}
