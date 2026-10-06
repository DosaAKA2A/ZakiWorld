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
 * La ficha del Vigilante en el catalogo de anomalias de EDM, como la de Ambush (AmbushType): clase
 * Monarca, elemento tierra y sin brillo (sin color de brillo EDM tampoco pinta su pilar de luz: viene de
 * la tierra y por sorpresa). Sale en /anomaly (menu, start, here, test) con sus habilidades: las de la
 * pareja montada, las del jinete a pie y las de la bestia suelta (1.14.1).
 *
 * El que sale bajo un jugador no pasa por EDM (EDM lleva una anomalia a la vez y aqui puede haber dos
 * Vigilantes). Abierto a mano desde /anomaly es una prueba (VigilanteEdm): sin presa ni botin de
 * Calamity, contra quien este cerca.
 *
 * Se registra al arrancar y se vuelve a mirar cada minuto (si EDM recarga su modulo, el catalogo nuevo
 * no lo trae). Sin el modulo de anomalias no pasa nada: el Vigilante sale igual.
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
                    + " (sale igual bajo los jugadores): " + t);
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

    /** Sin brillo: ni contorno de color ni pilar de luz en /anomaly. */
    @Override
    public NamedTextColor glowColor() {
        return null;
    }

    @Override
    public Element element() {
        return Element.TIERRA;
    }

    /** 1.14.1: la calabaza tallada del jinete sin cabeza (apagada, como la que lleva). */
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
        return "El jinete sin cabeza que sale de la tierra tras quien se aleja demasiado";
    }

    @Override
    public List<String> origin() {
        return List.of(
                "Pasados los mil bloques del spawn, abre",
                "tres cofres o mata un minijefe: la tierra",
                "late bajo tus pies, se oye una risa grave",
                "y algo sale escarbando.");
    }

    /** Con los umbrales de la config: bajo jinete.desmonte desmonta, bajo jinete.remonte vuelve a montar. */
    @Override
    public List<String> threat() {
        Vigilante.Ajustes a = gestor == null ? new Vigilante.Ajustes(new YamlConfiguration()) : gestor.ajustes();
        long d = Math.round(a.desmonteVida * 100), r = Math.round(a.remonteVida * 100);
        return List.of(
                "Anomalía Monarca: un jinete sin cabeza, con",
                "una maza, sobre un zoglin gigante. Montado,",
                "la bestia aplasta, embiste y se hunde bajo",
                "tus pies. Bajo el " + d + " % desmonta: salta",
                "sobre ti con la maza y lanza cabezas negras",
                "a quien huye, mientras la bestia embiste",
                "suelta. Bajo el " + r + " % vuelve a montar en furia.",
                "Golpear a la bestia también lo hiere a él.");
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
