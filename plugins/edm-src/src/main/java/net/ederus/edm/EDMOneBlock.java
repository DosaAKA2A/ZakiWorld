package net.ederus.edm;

import java.util.List;

import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.biomas.BiomasPlugin;
import net.ederus.edm.boost.BoostPlugin;
import net.ederus.edm.flex.FlexPlugin;
import net.ederus.edm.tienda.TiendaPlugin;

/*
 * El nucleo recortado para el servidor OneBlock: la tienda, la vitrina, los
 * biomas, los boosts y Anomaly. Se compila con el perfil `oneblock` de Maven,
 * que cambia el plugin.yml (este main, solo los comandos de estos modulos), el
 * config.yml y el catalogo de precios por los de src/main/resources-oneblock.
 *
 * Anomaly va desde EDO 1.4.0 porque EderusDimensions puebla sus dimensiones
 * con los Esbirros (anomaly/minions). Sus dependencias externas son todas
 * opcionales: WorldGuard y LibsDisguises se buscan por reflexion y solo si
 * estan instalados, MobCoins y ServerVariables van por comando de consola (si
 * faltan, el comando falla solo) y los biomas se piden a BiomasPlugin.activo()
 * mirando que no sea null. MMOItems solo sale en textos.
 *
 * El resto de modulos (Rip, GodItems, minas...) dan por hecho MMOItems y
 * compania, que en OneBlock no estan: no se arrancan y sus comandos ni se
 * registran.
 */
public class EDMOneBlock extends EDMPlugin {

    /** La version de EDO va aparte de la de EDM: pom (perfil oneblock), plugin.yml de resources-oneblock y aqui. */
    public static final String VERSION_EDO = "1.4.0";

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
        // Anomaly el primero, como en EDMPlugin.modulosBase(): su guardian de la
        // arena tiene que registrar sus listeners antes que nadie (ver AnomalyPlugin).
        return List.of(
                new AnomalyPlugin(this),
                new TiendaPlugin(this),
                new FlexPlugin(this),
                new BiomasPlugin(this),
                new BoostPlugin(this));
    }

    @Override
    protected void modulosOpcionales() {
        // Tooltip, misiones y bp no van en OneBlock. El brillo si, y como en EDS
        // le pide el color del equipo a TAB.
        if (getServer().getPluginManager().getPlugin("TAB") != null) {
            arrancar(new net.ederus.edm.glow.GlowPlugin(this));
        } else {
            getLogger().info("TAB no esta instalado: el modulo de brillo queda apagado.");
        }
    }
}
