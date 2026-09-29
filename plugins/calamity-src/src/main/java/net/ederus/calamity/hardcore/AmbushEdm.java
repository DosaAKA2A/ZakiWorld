package net.ederus.calamity.hardcore;

import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.boss.BossFight;
import net.ederus.edm.anomaly.core.ActiveAnomaly;
import net.ederus.edm.comun.Tags;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;

/**
 * Calamity 1.8.0 · Ambush abierta a mano desde /anomaly (start, here, test): una prueba sin presa
 * ni botin de Calamity. La pelea es la de siempre (PeleaAmbush, que se mueve sola con la tarea de
 * Amenazas); esto solo le da a EDM el cuerpo que tiene que vigilar y le quita la eleccion de
 * habilidades (las elige la pelea). Si la pelea acaba sin morir (se retira), se cierra la anomalia.
 */
final class AmbushEdm extends BossFight {

    private final AmbushType tipo;
    private PeleaAmbush pelea;
    private boolean cerrando;

    AmbushEdm(AnomalyPlugin plugin, ActiveAnomaly event, Location arena, AmbushType tipo) {
        super(plugin, event, arena);
        this.tipo = tipo;
        abilities.addAll(tipo.abilities());
    }

    @Override
    public String bossName() {
        return "Ambush";
    }

    @Override
    public int phaseCount() {
        return 1;
    }

    /** Una sola barra, la suya (PeleaAmbush). */
    @Override
    public boolean usesOwnBars() {
        return true;
    }

    @Override
    public TextColor accent() {
        return Paleta.AMBUSH;
    }

    /** Los 2 s de ceniza de su muerte. */
    @Override
    public int deathAnimationTicks() {
        return 50;
    }

    @Override
    public void spawn() {
        if (!tipo.vivo()) throw new IllegalStateException("Calamity no está en marcha");
        pelea = tipo.gestor().prueba(arena);
        if (pelea == null) throw new IllegalStateException("el spawn de Ambush se ha cancelado (protección, zona spawn o chunk)");
        boss = pelea.cuerpo;
        Tags.markBoss(boss, AmbushType.ID);
    }

    /** EDM no elige nada: la pelea se mueve sola. Si ya ha acabado sin morir, se cierra la anomalia. */
    @Override
    protected void ambient() {
        busyFor(1);
        if (pelea == null || pelea.estado != PeleaAmbush.Estado.FIN || cerrando) return;
        cerrando = true;
        Hardcore hc = tipo.gestor().hc();
        hc.plugin().getServer().getScheduler().runTask(hc.plugin(), () -> {
            if (plugin.manager().current() == event) plugin.manager().stop(true);
        });
    }

    @Override
    protected void onPhaseChange(int from, int to) {
    }

    /** La muerte que se ve la pone la pelea (el cuerpo de NPC cae y ceniza); EDM añade su destello. */
    @Override
    public void onDeath() {
    }

    @Override
    public void cleanup() {
        if (pelea != null) pelea.limpiar();
        super.cleanup();
    }

    /** /anomaly test am_...: suelta ese ataque ya. */
    void forzar(PeleaAmbush.Ataque x) {
        if (pelea != null) tipo.gestor().hc().seguro("ambush", () -> pelea.forzar(x));
    }
}
