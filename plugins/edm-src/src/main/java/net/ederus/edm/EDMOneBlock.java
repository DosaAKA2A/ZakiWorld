package net.ederus.edm;

import java.util.List;

import net.ederus.edm.biomas.BiomasPlugin;
import net.ederus.edm.boost.BoostPlugin;
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

    /** La version de EDO va aparte de la de EDM: pom (perfil oneblock), plugin.yml de resources-oneblock y aqui. */
    public static final String VERSION_EDO = "1.1.1";

    private String[] arte;

    @Override
    protected String nombre() {
        return "EDO";
    }

    @Override
    protected String lema() {
        return "Ederus OneBlock";
    }

    @Override
    protected String version() {
        return VERSION_EDO;
    }

    /** El arte de EDO vive en banner.txt (resources-oneblock), en braille, tal cual lo dio Dosa. */
    @Override
    protected String[] arte() {
        if (arte == null) {
            try (var in = getResource("banner.txt")) {
                arte = in == null ? new String[0]
                        : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\r?\\n");
            } catch (java.io.IOException e) {
                arte = new String[0];
            }
        }
        return arte;
    }

    @Override
    protected List<Module> modulosBase() {
        return List.of(
                new TiendaPlugin(this),
                new FlexPlugin(this),
                new BiomasPlugin(this),
                new BoostPlugin(this));
    }

    @Override
    protected void modulosOpcionales() {
        // Tooltip, misiones y bp no van en OneBlock.
    }
}
