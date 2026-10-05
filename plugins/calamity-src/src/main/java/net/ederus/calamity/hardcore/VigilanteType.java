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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Calamity 1.13.0 · La ficha del Vigilante en el catalogo de anomalias de EDM, como la de Ambush
 * (AmbushType): clase Monarca, elemento tierra y brillo amarillo. Sale en /anomaly (menu, start, here,
 * test) con sus doce habilidades.
 *
 * El que cae sobre un jugador no pasa por EDM (EDM lleva una anomalia a la vez y aqui puede haber dos
 * Vigilantes). Abierto a mano desde /anomaly es una prueba (VigilanteEdm): sin presa ni botin de
 * Calamity, contra quien este cerca.
 *
 * Se registra al arrancar y se vuelve a mirar cada minuto (si EDM recarga su modulo, el catalogo nuevo
 * no lo trae). Sin el modulo de anomalias no pasa nada: el Vigilante cae igual.
 */
final class VigilanteType implements AnomalyType {

    static final String ID = "vigilante";

    private final Vigilante gestor;
    private boolean vivo = true;

    private VigilanteType(Vigilante gestor) {
        this.gestor = gestor;
    }

    /** La crea y la registra; null si EDM no trae las clases de anomalias. */
    static VigilanteType crear(Vigilante gestor) {
        try {
            VigilanteType t = new VigilanteType(gestor);
            t.revisar();
            return t;
        } catch (Throwable t) {
            gestor.hc().plugin().getLogger().warning("[Calamity] El Vigilante no se puede registrar como anomalía de EDM"
                    + " (cae igual sobre los jugadores): " + t);
            return null;
        }
    }

    Vigilante gestor() {
        return gestor;
    }

    boolean vivo() {
        return vivo;
    }

    void parar() {
        vivo = false;
    }

    AnomalyPlugin modulo() {
        Plugin p = gestor.hc().plugin().getServer().getPluginManager().getPlugin("EDM");
        if (!(p instanceof EDMPlugin edm) || !edm.isEnabled()) return null;
        if (!(edm.modulo("anomaly") instanceof AnomalyPlugin a)) return null;
        return a.registry() != null && a.manager() != null ? a : null;
    }

    void revisar() {
        if (!vivo) return;
        AnomalyPlugin a = modulo();
        if (a == null || a.registry().get(ID) == this) return;
        a.registry().register(this);
        gestor.hc().plugin().getLogger().info("[Calamity] El Vigilante, registrado en EDM como anomalía "
                + a.registry().classOf(this).display() + ".");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String display() {
        return "Vigilante";
    }

    @Override
    public TextColor color() {
        return Paleta.VIGILANTE;
    }

    @Override
    public NamedTextColor glowColor() {
        return NamedTextColor.YELLOW;
    }

    @Override
    public Element element() {
        return Element.TIERRA;
    }

    @Override
    public Material icon() {
        return Material.CARVED_PUMPKIN;
    }

    @Override
    public AnomalyClass defaultClass() {
        return AnomalyClass.MONARCA;
    }

    @Override
    public String tagline() {
        return "El gólem que cae del cielo sobre quien se aleja demasiado";
    }

    @Override
    public List<String> origin() {
        return List.of(
                "Pasados los mil bloques del spawn, abre",
                "tres cofres o mata un minijefe: una luz",
                "amarilla te mira desde lo alto y cae.");
    }

    @Override
    public List<String> threat() {
        return List.of(
                "Anomalía Monarca: una pelea en cuatro fases.",
                "Si su ojo te ve dos segundos, te marca y",
                "sus golpes van a ti. Cúbrete con el terreno",
                "o con sus pilares, rompe sus núcleos y sal",
                "del círculo de la Sentencia.");
    }

    /** Solo para el menu: la vida de verdad sale de la escala de Calamity. Esta es la de N 60 solo. */
    @Override
    public double baseHealth() {
        return Vigilante.escala(gestor.ajustes(), 52, 1, 0, DificultadAmenaza.NEUTRO).vida();
    }

    @Override
    public int arenaRadius() {
        return 20;
    }

    /** Una Ability por habilidad. La eleccion normal es de la pelea; esto es para el menu y /anomaly test. */
    @Override
    public List<Ability> abilities() {
        Vigilante.Ajustes a = gestor == null ? new Vigilante.Ajustes(new YamlConfiguration()) : gestor.ajustes();
        List<Ability> out = new ArrayList<>();
        for (PeleaVigilante.Habilidad h : PeleaVigilante.Habilidad.values()) {
            int fase = h.faseDesde > 1 ? h.faseDesde : 0;
            out.add(new Ability(h.id, h.nombre, h.descripcion, fase, a.hab(h).espera(), h.duracion, Math.max(1, h.peso), h.icono, f -> {
                if (f instanceof VigilanteEdm e) e.forzar(h);
            }));
        }
        return out;
    }

    @Override
    public BossFight create(AnomalyPlugin plugin, ActiveAnomaly event, Location where) {
        return new VigilanteEdm(plugin, event, where, this);
    }
}
