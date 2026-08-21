package net.ederus.edm.coinflip;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;
import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.Textos;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;

/**
 * Coinflip: dos jugadores ponen lo mismo y la moneda decide.
 *
 * Vive en el nucleo y no en un plugin aparte porque comparte con la tienda la
 * economia de Vault, la entrada por chat y el estilo, y porque un plugin de
 * apuestas suelto es una cosa mas que mantener al dia con la version de Paper.
 *
 * Lo unico que crea o destruye dinero aqui es la comision: el resto es dinero
 * que cambia de dueño. Con la comision al 5% el coinflip es un SUMIDERO, que en
 * un survival OP con inflacion es justo lo que interesa.
 */
public final class CoinflipPlugin extends Module {

    private static final int CONFIG_VERSION = 1;
    private static final int MENSAJES_VERSION = 1;

    private final Textos textos = new Textos();
    private RegistroCf registro;
    private Mesa mesa;
    private Animacion animacion;
    private MenuCoinflip menu;
    private Economy economia;
    private BukkitTask tareaMantenimiento;

    private double anuncioPublicoDesde = 0;

    public CoinflipPlugin(EDMPlugin core) {
        super(core, "coinflip", "EderusCoinflip");
    }

    @Override
    public void onEnable() {
        migrar("config.yml", CONFIG_VERSION);
        saveDefaultConfig();
        reloadConfig();
        migrar("mensajes.yml", MENSAJES_VERSION);
        textos.cargar(new File(getDataFolder(), "mensajes.yml"));

        registro = new RegistroCf(new File(getDataFolder(), "registro"), getLogger());
        mesa = new Mesa(new File(getDataFolder(), "mesa.yml"), getLogger(), textos, registro);
        animacion = new Animacion(core);
        menu = new MenuCoinflip(this, core.chat());
        aplicarAjustes();

        core.getServer().getPluginManager().registerEvents(menu, this);
        core.getServer().getPluginManager().registerEvents(animacion, this);
        core.getServer().getPluginManager().registerEvents(new Salidas(this), this);

        ComandoCoinflip comando = new ComandoCoinflip(this);
        for (String nombre : new String[]{"cf", "coinflip"}) {
            var cmd = core.getCommand(nombre);
            if (cmd != null) {
                cmd.setExecutor(comando);
                cmd.setTabCompleter(comando);
            } else {
                getLogger().warning("El comando /" + nombre + " no esta en el plugin.yml de EDM.");
            }
        }

        /* Vault se engancha en el primer tick y no aqui, por lo mismo que en la
         * tienda: el proveedor de economia se registra en SU onEnable y el orden
         * de carga entre plugins no esta garantizado. */
        core.getServer().getScheduler().runTask(core, () -> {
            if (!engancharVault()) {
                getLogger().severe("No hay economia de Vault. Las apuestas se quedan sin banco.");
                return;
            }
            mesa.economia(economia);
            /* Lo primero al tener banco: devolver lo que quedara de un arranque
             * anterior. Si el servidor se cayo con apuestas puestas, ese dinero
             * esta retirado y solo vive en ese fichero. */
            int devueltas = mesa.devolverLoQueQuedo();
            if (devueltas > 0) {
                getLogger().warning("Devueltas " + devueltas + " apuestas que quedaron del arranque anterior.");
            }
            getLogger().info("Apuestas listas | banco: " + economia.getName());
        });

        long cada = 20L * 20;
        tareaMantenimiento = core.getServer().getScheduler().runTaskTimer(core, () -> {
            int n = mesa.caducar();
            if (n > 0) getLogger().info("Caducadas " + n + " apuestas sin jugar.");
        }, cada, cada);

        getLogger().info("Coinflip activo | de " + Estilo.dinero(mesa.minima())
                + " a " + Estilo.dinero(mesa.maxima())
                + " | comision " + mesa.comisionPorCiento() + "%"
                + " | retos: " + (mesa.retosActivos() ? "si" : "no")
                + " | animacion: " + (animacion.activa() ? "si" : "no"));
    }

    @Override
    public void onDisable() {
        if (tareaMantenimiento != null) tareaMantenimiento.cancel();
        /* Al apagar se devuelve TODO. Una apuesta no sobrevive al reinicio a
         * proposito: el que la puso ya no esta delante y una mesa fantasma de
         * hace tres dias solo genera discusiones. El dinero si sobrevive. */
        if (mesa != null) {
            int n = mesa.devolverTodo("APAGA");
            if (n > 0) getLogger().info("Devueltas " + n + " apuestas abiertas al apagar.");
            mesa.guardar();
        }
        if (registro != null) registro.cerrar();
    }

    private boolean engancharVault() {
        if (core.getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp =
                core.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economia = rsp.getProvider();
        return economia != null;
    }

    private void aplicarAjustes() {
        mesa.configurar(getConfig().getConfigurationSection("apuestas"));
        animacion.configurar(getConfig().getConfigurationSection("animacion"));
        anuncioPublicoDesde = Math.max(0, getConfig().getDouble("anuncio-publico-desde", 0));
    }

    @Override
    public String recargar() {
        reloadConfig();
        textos.cargar(new File(getDataFolder(), "mensajes.yml"));
        aplicarAjustes();
        return mesa.cuantasAbiertas() + " apuestas en la mesa | comision "
                + mesa.comisionPorCiento() + "%";
    }

    // ----------------------------------------------------------------- jugar

    /**
     * El camino que comparten el menu y el comando: coger la apuesta, cobrar,
     * sortear, pagar y DESPUES enseñar la moneda.
     */
    public void jugar(Player quien, Apuesta a) {
        Mesa.Jugada j = mesa.aceptar(quien, a);
        if (!j.ok()) {
            quien.sendMessage(j.mensaje());
            return;
        }
        Player creador = Bukkit.getPlayer(j.apuesta().creador());

        /* Todo lo visual se hace un tick despues: esto puede venir de dentro de
         * un clic de inventario, y abrir y cerrar ventanas en mitad de su propio
         * evento es la clase de cosa que deja el cursor con un item fantasma. */
        core.getServer().getScheduler().runTask(core, () -> {
            if (animacion.activa() && creador != null && creador.isOnline() && quien.isOnline()) {
                animacion.jugar(j, creador, quien);
                core.getServer().getScheduler().runTaskLater(core,
                        () -> contar(j, creador, quien), animacion.ticksHastaResultado());
            } else {
                contar(j, creador, quien);
            }
        });
    }

    /** El resultado por chat, cuando la moneda ya ha caido. */
    private void contar(Mesa.Jugada j, Player creador, Player aceptante) {
        String cantidad = Estilo.dinero(j.apuesta().cantidad());
        String premio = Estilo.dinero(j.premio());

        if (creador != null && creador.isOnline()) {
            mandarResultado(creador, j, cantidad, premio);
        }
        if (aceptante != null && aceptante.isOnline()) {
            mandarResultado(aceptante, j, cantidad, premio);
        }

        if (anuncioPublicoDesde > 0 && j.premio() >= anuncioPublicoDesde) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.equals(creador) || p.equals(aceptante)) continue;
                textos.manda(p, "anuncio-publico",
                        "&x&D&7&F&3&F&F%ganador% &fle gano &#4FFF55%premio% &fa &x&D&7&F&3&F&F%perdedor% &7en el coinflip.",
                        "%ganador%", j.nombreGanador(), "%perdedor%", j.nombrePerdedor(),
                        "%premio%", premio);
            }
        }
    }

    private void mandarResultado(Player p, Mesa.Jugada j, String cantidad, String premio) {
        boolean gano = p.getUniqueId().equals(j.ganador());
        if (gano) {
            textos.manda(p, "ganaste", "&fGanaste &#4FFF55%premio% &fcontra &x&D&7&F&3&F&F%rival%",
                    "%premio%", premio, "%rival%", j.nombrePerdedor(),
                    "%comision%", Estilo.dinero(j.comision()));
        } else {
            textos.manda(p, "perdiste", "&fPerdiste &#FF5C5C%cantidad% &fcontra &x&D&7&F&3&F&F%rival%",
                    "%cantidad%", cantidad, "%rival%", j.nombreGanador());
        }
    }

    // ---------------------------------------------------------------- acceso

    public Mesa mesa() { return mesa; }
    public Textos textos() { return textos; }
    public MenuCoinflip menu() { return menu; }
    public Animacion animacion() { return animacion; }

    /**
     * Igual que en la tienda: saveResource(false) NO sobrescribe, asi que un
     * fichero de una version anterior se quedaria para siempre. Se detecta por
     * su 'version', se aparta el viejo con su fecha y se escribe el nuevo.
     */
    private void migrar(String nombre, int esperada) {
        File destino = new File(getDataFolder(), nombre);
        if (!destino.exists()) { saveResource(nombre, false); return; }

        int suya = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(destino).getInt("version", 1);
        if (suya >= esperada) return;

        String base = nombre.replace(".yml", "");
        File aparte = new File(getDataFolder(), base + "-v" + suya + "-" + java.time.LocalDate.now() + ".yml");
        if (destino.renameTo(aparte)) {
            saveResource(nombre, false);
            getLogger().warning(nombre + " era de la version " + suya + " y se puso al dia. "
                    + "El tuyo quedo en " + aparte.getName() + " por si le habias cambiado algo.");
        } else {
            getLogger().severe("No pude apartar el " + nombre + " viejo; puede quedar desactualizado.");
        }
    }
}
