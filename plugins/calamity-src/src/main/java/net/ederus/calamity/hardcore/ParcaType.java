package net.ederus.calamity.hardcore;

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
 * La ficha de la PARCA en el catalogo de EDM (Calamity 1.1.0): lo que /anomaly ensena en el menu y lo
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
     * Null mientras pelea la de un AFK callada: EDM no le levanta el pilar de luz (el que la
     * persigue la ve igual, con su contorno).
     */
    @Override
    public NamedTextColor glowColor() {
        return puente.hayCallada() ? null : NamedTextColor.RED;
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
        return "La que viene a por quien se queda quieto";
    }

    @Override
    public List<String> origin() {
        return List.of(
                "Calamity no perdona a quien se queda quieto.",
                "Si pasas demasiado tiempo sin moverte,",
                "ella sigue tu rastro hasta encontrarte.");
    }

    @Override
    public List<String> threat() {
        return List.of(
                "Anomalía DIOS: una pelea en cuatro fases.",
                "Siega, cadenas, plañideras, apariciones",
                "a tu espalda y una Sentencia que se",
                "anuncia con campanadas. La Siega hace",
                "el doble de daño a quien se queda quieto.");
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
