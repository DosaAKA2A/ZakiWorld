package net.ederus.edm.troll;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.Module;
import net.ederus.edm.comun.Textos;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bromas de admin.
 *
 * La lista de ideas se mira de TrollBoss (que es GPL): el codigo es nuestro
 * entero, asi que EDM no hereda su licencia. Y de paso se arregla lo que a esos
 * plugins les falta siempre, que es la vuelta atras: aqui TODA broma temporal
 * sabe deshacerse y se deshace sola aunque el servidor se reinicie.
 *
 * Dos cosas que no se tocan, porque el servidor esta abierto y con gente:
 *
 *  1. **Lo que borra progreso pide confirmacion y permiso aparte.** En un
 *     survival OP el equipo de MMOItems ES el progreso; un clic de mas de un
 *     moderador no se arregla con un backup sin tirar el rato de todos.
 *  2. **Todo queda en el registro con nombre y apellidos.** Si alguien se queja,
 *     hay un fichero que dice quien, a quien y que.
 */
public final class TrollPlugin extends Module {

    private static final int CONFIG_VERSION = 1;
    private static final int MENSAJES_VERSION = 1;

    /** Lo que espera un segundo clic. */
    private record Pendiente(String troll, UUID victima, long caduca) { }

    private final Textos textos = new Textos();
    private final Map<String, Troll> catalogo = Trolls.catalogo();
    private final Map<UUID, Pendiente> confirmaciones = new LinkedHashMap<>();

    private Estados estados;
    private MenuTroll menu;
    private RegistroTroll registro;
    private BukkitTask tarea;

    private int segundosConfirmar = 15;
    private boolean avisarALaVictima = false;

    public TrollPlugin(EDMPlugin core) {
        super(core, "troll", "EderusTroll");
    }

    @Override
    public void onEnable() {
        migrar("config.yml", CONFIG_VERSION);
        saveDefaultConfig();
        reloadConfig();
        migrar("mensajes.yml", MENSAJES_VERSION);
        textos.cargar(new File(getDataFolder(), "mensajes.yml"));

        estados = new Estados(core, getLogger());
        registro = new RegistroTroll(new File(getDataFolder(), "registro"), getLogger());
        menu = new MenuTroll(this);
        aplicarAjustes();

        core.getServer().getPluginManager().registerEvents(menu, this);
        core.getServer().getPluginManager().registerEvents(new Escuchas(this), this);

        ComandoTroll comando = new ComandoTroll(this);
        for (String nombre : new String[]{"troll", "bromas"}) {
            var cmd = core.getCommand(nombre);
            if (cmd != null) {
                cmd.setExecutor(comando);
                cmd.setTabCompleter(comando);
            } else {
                getLogger().warning("El comando /" + nombre + " no esta en el plugin.yml de EDM.");
            }
        }

        /* Cada segundo: caducar lo que toque y mover a los mareados. Una sola
         * tarea para todo el modulo; una por broma seria lo que hace que estos
         * plugins pesen. */
        tarea = core.getServer().getScheduler().runTaskTimer(core, () -> {
            estados.repasar();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!estados.tiene(p, Estados.Marca.INVERTIDO)) continue;
                var l = p.getLocation();
                l.setYaw(l.getYaw() + 25 + Contexto.azar().nextInt(30));
                p.teleport(l);
            }
        }, 20L, 20L);

        getLogger().info("Bromas activas | " + catalogo.size() + " en el catalogo ("
                + Trolls.sorteables(catalogo).size() + " sin riesgo)"
                + " | confirmar: " + segundosConfirmar + "s");
    }

    @Override
    public void onDisable() {
        if (tarea != null) tarea.cancel();
        /* Al apagar se deshace TODO. Si no, el que se quedo encerrado en cristal
         * sigue encerrado despues del reinicio y sin nadie que lo sepa. */
        if (estados != null) {
            int n = estados.quitarTodo();
            if (n > 0) getLogger().info("Deshechas " + n + " bromas al apagar.");
        }
        if (registro != null) registro.cerrar();
    }

    private void aplicarAjustes() {
        segundosConfirmar = Math.max(3, getConfig().getInt("segundos-para-confirmar", 15));
        avisarALaVictima = getConfig().getBoolean("avisar-a-la-victima", false);
    }

    @Override
    public String recargar() {
        reloadConfig();
        textos.cargar(new File(getDataFolder(), "mensajes.yml"));
        aplicarAjustes();
        return catalogo.size() + " bromas | " + estados.cuantosJugadores() + " jugadores con algo encima";
    }

    // ----------------------------------------------------------------- lanzar

    /**
     * Hace la broma, o pide confirmacion si borra progreso.
     *
     * El segundo clic tiene que ser sobre LA MISMA broma y LA MISMA victima:
     * confirmar "a secas" acabaria confirmando lo que no era.
     */
    public void lanzar(Player admin, Player victima, Troll t, boolean yaConfirmado) {
        if (!puede(admin, t)) {
            textos.manda(admin, "sin-permiso", "&#FF5C5CNo puedes usar esa broma.");
            return;
        }
        if (inmune(victima)) {
            textos.manda(admin, "inmune", "&#FF5C5C%jugador% esta a salvo de las bromas.",
                    "%jugador%", victima.getName());
            return;
        }
        if (victima.equals(admin) && !getConfig().getBoolean("permitir-a-uno-mismo", true)) {
            textos.manda(admin, "contra-ti", "&#FF5C5CNo puedes gastarte bromas a ti mismo.");
            return;
        }

        if (t.destructivo() && !yaConfirmado && !confirmado(admin, t, victima)) {
            textos.manda(admin, "confirmar",
                    "&#FF5C5C%broma% &fle borra progreso a &x&D&7&F&3&F&F%jugador%&f. "
                            + "Vuelve a pulsarla en %segundos%s para confirmar.",
                    "%broma%", t.nombre(), "%jugador%", victima.getName(),
                    "%segundos%", String.valueOf(segundosConfirmar));
            return;
        }

        int segundos = getConfig().getInt("duraciones." + t.id(), t.segundos());
        try {
            t.accion().aplicar(new Contexto(this, admin, victima, t, segundos));
        } catch (Throwable ex) {
            /* Una broma rota no puede tumbar el tick ni dejar a nadie a medias. */
            getLogger().warning("La broma " + t.id() + " fallo: " + ex);
            estados.quitarTodo(victima.getUniqueId());
            textos.manda(admin, "fallo", "&#FF5C5CEsa broma fallo. Se deshizo lo que hubiera.");
            return;
        }

        registro.anotar(admin.getName(), victima.getName(), t.id(), t.destructivo(), segundos);
        textos.manda(admin, "hecha", "&fLe hiciste &x&D&7&F&3&F&F%broma% &fa &x&D&7&F&3&F&F%jugador%",
                "%broma%", t.nombre(), "%jugador%", victima.getName());
        if (avisarALaVictima) {
            textos.manda(victima, "te-la-hicieron", "&7Alguien te acaba de gastar una broma.");
        }
        if (Trolls.raro(victima)) {
            textos.manda(admin, "en-creativo", "&7Ojo: esta en creativo o espectador, puede no notarse.");
        }
    }

    /** true si este admin ya la habia pulsado hace poco sobre esta victima. */
    private boolean confirmado(Player admin, Troll t, Player victima) {
        Pendiente p = confirmaciones.get(admin.getUniqueId());
        boolean vale = p != null
                && p.troll().equals(t.id())
                && p.victima().equals(victima.getUniqueId())
                && p.caduca() > System.currentTimeMillis();
        if (vale) {
            confirmaciones.remove(admin.getUniqueId());
            return true;
        }
        confirmaciones.put(admin.getUniqueId(), new Pendiente(t.id(), victima.getUniqueId(),
                System.currentTimeMillis() + segundosConfirmar * 1000L));
        return false;
    }

    // ---------------------------------------------------------------- permisos

    /** ederus.troll.usar para todas, mas la suya, mas la de destructivas. */
    public boolean puede(Player admin, Troll t) {
        if (!admin.hasPermission("ederus.troll.usar")) return false;
        if (t.destructivo() && !admin.hasPermission("ederus.troll.destructivo")) return false;
        /* El permiso por broma solo se exige si el servidor lo ha configurado
         * asi; por omision, quien puede usar bromas puede usarlas todas. */
        if (getConfig().getBoolean("permiso-por-broma", false) && !admin.hasPermission(t.permiso())) {
            return false;
        }
        return true;
    }

    /** Al que lleva ederus.troll.inmune no se le puede tocar. */
    public boolean inmune(Player victima) {
        return victima.hasPermission("ederus.troll.inmune");
    }

    // ----------------------------------------------------------------- acceso

    public Map<String, Troll> catalogo() { return catalogo; }
    public Estados estados() { return estados; }
    public MenuTroll menu() { return menu; }
    public Textos textos() { return textos; }
    public RegistroTroll registro() { return registro; }

    public List<Troll> sorteables() { return Trolls.sorteables(catalogo); }

    /** Igual que en la tienda: saveResource(false) no sobrescribe, asi que un
     *  fichero viejo se quedaria para siempre. */
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
                    + "El tuyo quedo en " + aparte.getName() + ".");
        } else {
            getLogger().severe("No pude apartar el " + nombre + " viejo.");
        }
    }
}
