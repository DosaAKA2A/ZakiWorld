package net.ederus.calamity.hardcore;

import net.ederus.edm.EDMPlugin;
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
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Calamity 1.8.0 · La ficha de Ambush en el catalogo de anomalias de EDM, como la de la Parca
 * (ParcaType, PuenteAnomalia), pero de clase Monarca: sale en /anomaly (menu, start, here, test) con
 * sus tres ataques.
 *
 * La de un contrato no pasa por EDM (Ambush la mueve siempre por su cuenta: EDM solo lleva una
 * anomalia a la vez y un contrato no puede esperar a que se libere, ni anunciarse). Abierta a mano
 * desde /anomaly es una prueba (AmbushEdm): sin presa ni botin de Calamity, contra quien este cerca.
 *
 * Se registra al arrancar y se vuelve a mirar cada minuto (si EDM recarga su modulo, el catalogo
 * nuevo no la trae). Si EDM no tiene el modulo de anomalias, no pasa nada: los contratos funcionan
 * igual.
 */
final class AmbushType implements AnomalyType {

    static final String ID = "ambush";

    private final Ambush gestor;
    /** False desde que Calamity se para: una abierta a mano ya no puede nacer. */
    private boolean vivo = true;

    private AmbushType(Ambush gestor) {
        this.gestor = gestor;
    }

    /** La crea y la registra; null si EDM no trae las clases de anomalias. */
    static AmbushType crear(Ambush gestor) {
        try {
            AmbushType t = new AmbushType(gestor);
            t.revisar();
            return t;
        } catch (Throwable t) {
            gestor.hc().plugin().getLogger().warning("[Calamity] Ambush no se puede registrar como anomalía de EDM"
                    + " (los contratos funcionan igual): " + t);
            return null;
        }
    }

    Ambush gestor() {
        return gestor;
    }

    boolean vivo() {
        return vivo;
    }

    void parar() {
        vivo = false;
    }

    /** El modulo de anomalias de EDM en marcha, o null. */
    AnomalyPlugin modulo() {
        Plugin p = gestor.hc().plugin().getServer().getPluginManager().getPlugin("EDM");
        if (!(p instanceof EDMPlugin edm) || !edm.isEnabled()) return null;
        if (!(edm.modulo("anomaly") instanceof AnomalyPlugin a)) return null;
        return a.registry() != null && a.manager() != null ? a : null;
    }

    /** Que este en el catalogo (al arrancar y cada minuto, desde Ambush.tick). */
    void revisar() {
        if (!vivo) return;
        AnomalyPlugin a = modulo();
        if (a == null || a.registry().get(ID) == this) return;
        a.registry().register(this);
        gestor.hc().plugin().getLogger().info("[Calamity] Ambush, registrada en EDM como anomalía "
                + a.registry().classOf(this).display() + ".");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String display() {
        return "Ambush";
    }

    @Override
    public TextColor color() {
        return Paleta.AMBUSH;
    }

    /** Sin brillo ni pilar de luz: una emboscada no se ve venir. */
    @Override
    public NamedTextColor glowColor() {
        return null;
    }

    @Override
    public Element element() {
        return Element.TIERRA;
    }

    @Override
    public Material icon() {
        return Material.NETHERITE_SWORD;
    }

    @Override
    public AnomalyClass defaultClass() {
        return AnomalyClass.MONARCA;
    }

    @Override
    public String tagline() {
        return "El samurái que cobra los contratos de la Sentencia";
    }

    @Override
    public List<String> origin() {
        return List.of(
                "Alguien paga en la Sentencia por la",
                "cabeza de otro. Si la presa no sale de",
                "Calamity en un minuto, Ambush va a por ella.");
    }

    @Override
    public List<String> threat() {
        return List.of(
                "Anomalía Monarca: un duelo en dos fases.",
                "Acometida, tajo doble, paso sombra e",
                "iaijutsu. En la segunda se transforma,",
                "va más rápido y suma los mil cortes y las",
                "Sombras del clan: clones que te atraviesan.");
    }

    /** Solo para el menu: la vida de verdad sale de la formula de Calamity (Ambush.vida). Esta es la de N 60. */
    @Override
    public double baseHealth() {
        return Ambush.vida(gestor.ajustes(), 60);
    }

    @Override
    public int arenaRadius() {
        return 16;
    }

    /**
     * Una Ability por ataque. La eleccion normal es de la pelea; esto es para el menu y /anomaly test.
     * Fase 0 (cualquiera) para los de las dos fases; mil cortes y las Sombras del clan, la 2 (la ultima).
     */
    @Override
    public List<Ability> abilities() {
        List<Ability> out = new ArrayList<>();
        for (PeleaAmbush.Ataque x : PeleaAmbush.Ataque.values()) {
            int fase = x.fase > 1 ? x.fase : 0;
            out.add(new Ability(x.id, x.nombre, x.descripcion, fase, x.espera, x.duracion, x.peso, x.icono, f -> {
                if (f instanceof AmbushEdm e) e.forzar(x);
            }));
        }
        return out;
    }

    @Override
    public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        return new AmbushEdm(plugin, event, where, this);
    }
}
