package net.ederus.edm;

import java.util.List;

import net.ederus.edm.biomas.BiomasPlugin;
import net.ederus.edm.flex.FlexPlugin;
import net.ederus.edm.tienda.TiendaPlugin;

/*
 * El nucleo recortado para el servidor OneBlock: la tienda, la vitrina y los
 * biomas, y nada mas. Se compila con el perfil `oneblock` de Maven, que cambia
 * el plugin.yml (este main, solo los comandos de esos tres modulos), el
 * config.yml y el catalogo de precios por los de src/main/resources-oneblock.
 *
 * El resto de modulos (Rip, Anomaly, GodItems, minas...) dan por hecho MMOItems,
 * LibsDisguises y compania, que en OneBlock no estan: no se arrancan y sus
 * comandos ni se registran.
 */
public class EDMOneBlock extends EDMPlugin {

    @Override
    protected List<Module> modulosBase() {
        return List.of(
                new TiendaPlugin(this),
                new FlexPlugin(this),
                new BiomasPlugin(this));
    }

    @Override
    protected void modulosOpcionales() {
        // Tooltip, misiones y bp no van en OneBlock.
    }
}
