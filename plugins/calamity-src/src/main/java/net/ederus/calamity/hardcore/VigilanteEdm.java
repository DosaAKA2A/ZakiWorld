package net.ederus.calamity.hardcore;

import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.boss.BossFight;
import net.ederus.edm.anomaly.core.ActiveAnomaly;
import net.ederus.edm.comun.Tags;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;

/**
 * Calamity 1.13.0 · El Vigilante abierto a mano desde /anomaly (start, here, test): una prueba sin presa
 * ni botin de Calamity, como AmbushEdm. La pelea es la de siempre (PeleaVigilante, que se mueve sola con
 * la tarea de Amenazas); esto solo le da a EDM el cuerpo que tiene que vigilar y le quita la eleccion de
 * habilidades. Si la pelea acaba sin morir (se va), se cierra la anomalia.
 */
final class VigilanteEdm extends BossFight {

    private final VigilanteType tipo;
    private PeleaVigilante pelea;
    private boolean cerrando;

    VigilanteEdm(AnomalyPlugin plugin, ActiveAnomaly event, Location arena, VigilanteType tipo) {
        super(plugin, event, arena);
        this.tipo = tipo;
        abilities.addAll(tipo.abilities());
    }

    @Override
    public String bossName() {
        return "Vigilante";
    }

    @Override
    public int phaseCount() {
        return 4;
    }

    /** Una sola barra, la suya (PeleaVigilante). */
    @Override
    public boolean usesOwnBars() {
        return true;
    }

    @Override
    public TextColor accent() {
        return Paleta.VIGILANTE;
    }

    @Override
    public int deathAnimationTicks() {
        return 30;
    }

    /** Las fases las lleva la pelea por su vida (Vigilante.faseDe); EDM no las cambia. */
    @Override
    protected boolean canChangePhase(int from, int to) {
        return false;
    }

    @Override
    public void spawn() {
        if (!tipo.vivo()) throw new IllegalStateException("Calamity no está en marcha");
        pelea = tipo.gestor().prueba(arena);
        if (pelea == null) throw new IllegalStateException("el spawn del Vigilante se ha cancelado (protección, zona spawn o chunk)");
        // La anomalia es el jinete (su vida, su nombre); la bestia es su montura.
        boss = pelea.jinete;
        Tags.markBoss(boss, VigilanteType.ID);
    }

    @Override
    protected void ambient() {
        busyFor(1);
        if (pelea == null || pelea.estado != PeleaVigilante.Estado.FIN || cerrando) return;
        cerrando = true;
        Hardcore hc = tipo.gestor().hc();
        hc.plugin().getServer().getScheduler().runTask(hc.plugin(), () -> {
            if (plugin.manager().current() == event) plugin.manager().stop(true);
        });
    }

    @Override
    protected void onPhaseChange(int from, int to) {
    }

    @Override
    public void onDeath() {
    }

    @Override
    public void cleanup() {
        if (pelea != null) pelea.limpiar();
        super.cleanup();
    }

    /** /anomaly test vi_...: suelta esa habilidad ya. */
    void forzar(PeleaVigilante.Habilidad h) {
        if (pelea != null) tipo.gestor().hc().seguro("vigilante", () -> pelea.forzar(h));
    }
}
