package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.ederus.calamity.CalamityPlugin;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.MobCoins;
import net.ederus.lethalworld.LethalWorldPlugin;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Las reglas que solo existen en los mundos hardcore de Lethal World (hoy, Calamity).
 *
 * La idea del mundo: no se gana por equipo, se gana por saber cuando salir. La cordura
 * es un reloj que corre desde que entras, el bioma te va desgastando, morir cuesta
 * TODO lo que llevas encima y la unica forma de volver es el portal o un cristal que
 * hay que ganarse. Nada de esto se enciende fuera de esos mundos: cada listener
 * pregunta primero por el mundo, y con la lista vacia el plugin no hace nada.
 *
 * Lo que decide que un mundo es hardcore esta en la config (hardcore.mundos), no en el
 * codigo: manana Dosa puede montar otro mundo con las mismas reglas sin tocar Java.
 */
public final class Hardcore implements Listener {

    private final CalamityPlugin plugin;
    private final Cordura cordura = new Cordura();
    /** 1.7.1: todo lo que Calamity escribe en la barra de accion pasa por aqui. */
    private final BarraAccion barra;
    private final ItemsCalamity items;
    /* SecureRandom y no Random: aqui solo se elige minijefe y un desvio, pero la regla de
     * la casa para hardcore/ es que nada salga de Random (lo que da botin o dinero no puede
     * ser predecible) y asi la revision no tiene excepciones que recordar. */
    private final SecureRandom random = new SecureRandom();

    /** Quien esta canalizando el cristal: jugador -> donde estaba al empezar. */
    private final Map<UUID, Location> canalizando = new HashMap<>();
    /** Ultima vez (millis) que a cada jugador se le aplicaron los efectos de su bioma. */
    private final Map<UUID, Long> ultimoEfecto = new HashMap<>();
    /** Segundos que lleva canalizando el cristal cada uno. */
    private final Map<UUID, Integer> cuentaCristal = new HashMap<>();
    /** Calamity 1.11 · Tick del servidor del ultimo uso del Frasco o el Cristal de cada uno (onUsar). */
    private final Map<UUID, Integer> ultimoUso = new HashMap<>();
    /** Cuando murio cada uno dentro (millis), para la cuarentena de reentrada. */
    private final Map<UUID, Long> muertos = new HashMap<>();
    /** Minijefe -> a quien viene siguiendo. Ver marcarPresa(). */
    private final Map<UUID, UUID> presas = new HashMap<>();
    /** Calamity 1.10 · Minijefe -> desde cuando (millis) espera fuera de la zona spawn a su presa. Ver vigilarPresas(). */
    private final Map<UUID, Long> esperando = new HashMap<>();
    /**
     * Calamity 1.10 · Minijefe que espera -> quien le ha pegado desde fuera -> cuando (millis): a esos, y
     * solo durante ZonaSpawn.REPRESALIA_MS, les puede apuntar (onApuntarAlQueEspera). Se vacia con el
     * resto de la espera (soltarPresa, VUELVE, parar).
     */
    private final Map<UUID, Map<UUID, Long>> represalias = new HashMap<>();
    /** Calamity 1.10 · Minijefe sin presa viva sacado al borde -> desde cuando (millis). Ver vigilarSinPresa(). */
    private final Map<UUID, Long> sinPresa = new HashMap<>();
    /** Calamity 1.10 · La ultima llegada de un minijefe por cada jugador (millis), en hardcore-datos. Ver ultimoMinijefe(). */
    static final String RUTA_ULTIMO_MINIJEFE = "minijefe-ultimo";
    /** Quien acaba de morir dentro y todavia no ha reaparecido. Ver onReaparecer(). */
    private final java.util.Set<UUID> porReaparecer = new java.util.HashSet<>();
    /** 1.10: quien acaba de entrar por meter(); onCambiarMundo no repite su llegada. Dura un tick. */
    private final java.util.Set<UUID> recienMetidos = new java.util.HashSet<>();

    /*
     * Lo que el plugin ESCRIBE solo (horas acumuladas, tags entregados y la cordura de
     * quien se desconecto dentro) vive en datos.yml y no en config.yml. Mientras
     * estuvo en el config, el guardado de cada minuto volcaba el fichero ENTERO desde
     * memoria: si Dosa subia un config.yml por el panel con alguien dentro de
     * Calamity, a los pocos segundos se lo pisaba la version vieja.
     */
    private java.io.File archivoDatos;
    private YamlConfiguration datos = new YamlConfiguration();
    private boolean datosSucios;
    private int segundosSinGuardar;

    private BukkitTask reloj;
    private MenuHardcore menu;
    private VaraPortales vara;
    private int segundosManto;

    /** Ultimo sitio con suelo firme de cada uno (rescates, Eco). Se limpia en el quit. */
    private final Map<UUID, Location> ultimoSuelo = new HashMap<>();
    /** Ultimo aviso de fallo de cada modulo, para no llenar la consola a uno por segundo. */
    private final Map<String, Long> ultimoFallo = new HashMap<>();

    /*
     * Los modulos de Calamity (PLAN-IMPLEMENTACION sec. 4). Se crean en arrancar() en este
     * orden y se paran al reves; cada uno registra su propio listener en su constructor,
     * como MenuHardcore. Con hardcore.activo en false no existe ninguno.
     */
    private Telemetria telemetria;
    private Estadisticas estadisticas;
    private Calendario calendario;
    private Exentos exentos;
    /** Calamity 1.4: lo que hace cada pieza de MMOItems dentro de Calamity (desde la 1.6, de los efectos de equipo de GodItems). */
    private Equipo equipo;
    private Aduana aduana;
    private Saldo saldo;
    private Creditos creditos;
    private Monedero monedero;
    private Entregas entregas;
    private Reliquias reliquias;
    private Tasacion tasacion;
    private Grifo grifo;
    private Minijefes minijefes;
    private Cofres cofres;
    private Racha racha;
    private Combate combate;
    private Sellos sellos;
    private Ligado ligado;
    private Amenazas amenazas;
    private Huella huella;
    private Parca parca;
    /** Calamity 1.8.0: los contratos de la Sentencia y su samurai. Null con las reglas apagadas. */
    private Ambush ambush;
    private Ecos ecos;
    private ParteDefuncion parte;
    private Testigos testigos;
    private Sentidos sentidos;
    private ObjetosCalamity objetos;
    private Altar altar;
    private Horas horas;
    private Hitos hitos;
    private Kit kit;
    private Contratos contratos;
    private Rankings rankings;
    private Tablero tablero;
    private Encuesta encuesta;
    private Eclipse eclipse;
    /** Calamity 1.9.0: la lluvia acida de los biomas verdes y el cielo rojo. Null con las reglas apagadas. */
    private Clima clima;
    /** Calamity 1.7: los niveles por distancia al spawn y su aviso. */
    private Distancia distancia;
    private Npcs npcs;
    /** Calamity 1.11: cofres y Bovedas de Ruinas, la Boveda Caida y el ranking de clanes. */
    private Ruinas ruinas;
    private BovedaCaida bovedaCaida;
    private ClanesCalamity clanes;
    /** Calamity 1.2: la zona spawn (region de WorldGuard o caja de la vara). Null con las reglas apagadas. */
    private ZonaSpawn zona;
    /** Quien estaba en la zona spawn el segundo anterior, para notar cuando entra y cuando sale. */
    private final java.util.Set<UUID> enZona = new java.util.HashSet<>();

    public Hardcore(CalamityPlugin plugin) {
        this.plugin = plugin;
        this.items = new ItemsCalamity(plugin);
        this.barra = new BarraAccion(plugin);
        // Los destellos de la cordura se ven donde se pinta la barra: dentro y contando.
        barra.dentro(p -> esHardcore(p) && cuenta(p));
        cordura.salida(barra);
    }

    public Cordura cordura() {
        return cordura;
    }

    /** La barra de accion de Calamity, con el protocolo "ederus_actionbar" (BarraAccion). */
    public BarraAccion barra() {
        return barra;
    }

    public ItemsCalamity items() {
        return items;
    }

    public MenuHardcore menu() {
        return menu;
    }

    public VaraPortales vara() {
        return vara;
    }

    CalamityPlugin plugin() {
        return plugin;
    }

    Telemetria telemetria() { return telemetria; }
    Estadisticas estadisticas() { return estadisticas; }
    Calendario calendario() { return calendario; }
    /** Exenciones puestas a mano (parca, aduana); null con las reglas apagadas. */
    Exentos exentos() { return exentos; }
    /** Calamity 1.4 (1.6: via GodItems). Null con las reglas apagadas; mejor delEquipo() que esto. */
    Equipo equipo() { return equipo; }
    Aduana aduana() { return aduana; }
    Saldo saldo() { return saldo; }
    Creditos creditos() { return creditos; }
    Monedero monedero() { return monedero; }
    Entregas entregas() { return entregas; }
    Reliquias reliquias() { return reliquias; }
    Tasacion tasacion() { return tasacion; }
    /** Publico: lo llama MobsLethal.alMorir. Null con las reglas apagadas. */
    public Grifo grifo() { return grifo; }
    Minijefes minijefes() { return minijefes; }
    Cofres cofres() { return cofres; }
    Racha racha() { return racha; }
    Combate combate() { return combate; }
    Sellos sellos() { return sellos; }
    Ligado ligado() { return ligado; }
    Amenazas amenazas() { return amenazas; }
    Huella huella() { return huella; }
    Parca parca() { return parca; }
    Ambush ambush() { return ambush; }
    Ecos ecos() { return ecos; }
    ParteDefuncion parte() { return parte; }
    Testigos testigos() { return testigos; }
    Sentidos sentidos() { return sentidos; }
    ObjetosCalamity objetos() { return objetos; }
    Altar altar() { return altar; }
    Horas horas() { return horas; }
    Hitos hitos() { return hitos; }
    Kit kit() { return kit; }
    Contratos contratos() { return contratos; }
    Rankings rankings() { return rankings; }
    Tablero tablero() { return tablero; }
    Encuesta encuesta() { return encuesta; }
    Eclipse eclipse() { return eclipse; }
    /** Lo leen Vineta (el borde del cielo rojo) y ParteDefuncion (el nombre del golpe). */
    Clima clima() { return clima; }
    Distancia distancia() { return distancia; }
    /** Lo que abren los NPCs de la antesala (/calamity open) y el Cronista. */
    Npcs npcs() { return npcs; }
    ZonaSpawn zonaSpawn() { return zona; }
    Ruinas ruinas() { return ruinas; }
    ClanesCalamity clanes() { return clanes; }

    ConfigurationSection cfg() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("hardcore");
        return s == null ? new YamlConfiguration() : s;
    }

    /** Si este mundo se rige por las reglas hardcore. */
    public boolean esHardcore(World w) {
        if (w == null || !LethalWorldPlugin.esMundo(w)) return false;
        return cfg().getStringList("mundos").contains(w.getKey().getKey());
    }

    public boolean esHardcore(Player p) {
        return p != null && esHardcore(p.getWorld());
    }

    /**
     * Calamity 1.2: si ese sitio cae en la zona spawn de su mundo hardcore (ZonaSpawn: la region de
     * WorldGuard, o la caja de la vara). Dentro Calamity no hace nada salvo la Grieta: ni mobs, ni
     * cordura que baje, ni alucinaciones, ni PARCA. Publico: MobsLethal no invoca ahi.
     */
    public boolean enSpawn(Location l) {
        return zona != null && l != null && zona.dentro(l);
    }

    public boolean enSpawn(Player p) {
        return p != null && enSpawn(p.getLocation());
    }

    /** La zona spawn en uso en cada mundo hardcore, para /calamity status. */
    public String describirSpawn() {
        return zona == null ? "sin zona (las reglas no han arrancado)" : valor("zona-spawn", zona::describir, "?");
    }

    /** Un jugador cuenta para las reglas si esta jugando de verdad. */
    boolean cuenta(Player p) {
        return p.getGameMode() != GameMode.SPECTATOR
                && (p.getGameMode() != GameMode.CREATIVE || cfg().getBoolean("contar-creativo", false));
    }

    // ------------------------------------------------------------------ ciclo vida

    /** Si las reglas estan en marcha. Con hardcore.activo en false no hay panel ni vara. */
    public boolean activo() {
        return reloj != null;
    }

    public void arrancar() {
        if (!cfg().getBoolean("activo", true)) {
            plugin.getLogger().info("[Calamity] Reglas hardcore apagadas en la config.");
            return;
        }
        cargarDatos();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        menu = new MenuHardcore(plugin);
        vara = new VaraPortales(plugin);
        crearModulos();
        // En la zona spawn la cordura no baja por nada (drenaje, sustos, testigos, la PARCA...).
        cordura.aSalvo(this::enSpawn);
        // Un segundo justo: la cordura se cuenta en segundos y la barra tiene que
        // repintarse a ese ritmo o parpadea contra los avisos de otros plugins.
        reloj = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::tick, 20L, 20L);
        // 1.7.1: los avisos que esperan a que otro plugin suelte la barra salen en cuanto se suelta.
        barra.arrancar();

        // Las MobCoins pasan por la barra de la cordura en vez de pisarla.
        MobCoins.aviso((jugador, cantidad) -> {
            if (!esHardcore(jugador)) return false;
            cordura.destello(jugador, Component.text("+" + cantidad + " MobCoins", MobCoins.ORO), 2);
            return true;
        });
        plugin.getLogger().info("[Calamity] Reglas hardcore activas en: "
                + String.join(", ", cfg().getStringList("mundos")));
    }

    public void parar() {
        if (reloj != null) reloj.cancel();
        reloj = null;
        barra.parar();
        // 1.11: ninguna BossBar de la cordura se queda colgada.
        cordura.pararPantalla();
        MobCoins.aviso(null);
        canalizando.clear();
        // 1.10: sin reloj nadie espera a nadie; al volver a arrancar, el que siga ahi empieza de cero.
        esperando.clear();
        represalias.clear();
        sinPresa.clear();
        recienMetidos.clear();
        pararModulos();
        // Al apagar no hay PlayerQuitEvent que valga: la cordura de los que siguen
        // dentro se apunta aqui, o un reinicio del servidor se la devolveria entera.
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (esHardcore(p) && cordura.conoce(p)) {
                datos.set("guardado." + p.getUniqueId(), cordura.valor(p));
                datosSucios = true;
            }
        }
        guardarDatos();
    }

    /**
     * Crea los modulos en el orden de PLAN-IMPLEMENTACION sec. 4. Si uno revienta al nacer se
     * queda en null y se avisa: sus ganchos fallan dentro de seguro() y el resto sigue.
     */
    private void crearModulos() {
        // Lo de WP0 primero: /calamity selftest y el placeholder de la cordura. Los
        // modulos registran sus pruebas, subcomandos y placeholders al nacer, debajo.
        Autotest.instalar(this);
        Autotest.registrar("barra", BarraAccion::autotest);
        // 1.11: la BossBar de la cordura.
        Autotest.registrar("medidor-cordura", MedidorCordura::autotest);
        Autotest.registrar("dificultad-amenazas", DificultadAmenaza::autotest);
        Autotest.registrar("fragmentos", FragmentosMasamune::autotest);
        // 1.9.0: la tabla de biomas y los mobs especiales (las usa MobsLethal, que no ve Autotest).
        Autotest.registrar("apariciones", Apariciones::autotest);
        // 1.11: la salida completa a mano, para el staff (en ingles, como todos los comandos nuevos).
        Subcomandos.staff().registrar("extract", "extract <player>: lo saca de Calamity como si cruzara la puerta, con Tasación",
                Subcomandos.PERMISO, this::comandoExtract, args -> args.length == 2 ? Entregas.nombresConectados() : List.of());
        PlaceholdersLethal.registrar("cordura", (jugador, resto) -> corduraTexto(jugador));
        // Lo primero: la Grieta, las amenazas y los mobs preguntan por ella desde que nacen.
        zona = crear("zona-spawn", () -> new ZonaSpawn(this));
        distancia = crear("distancia", () -> new Distancia(this));
        telemetria = crear("telemetria", () -> new Telemetria(this));
        estadisticas = crear("estadisticas", () -> new Estadisticas(this));
        calendario = crear("calendario", () -> new Calendario(this));
        // Antes que la Aduana y la Huella, que la consultan (solo lee datos: no para nada).
        exentos = crear("exentos", () -> new Exentos(this));
        // 1.4: antes de todo lo que pregunta por el equipo (drenaje, Aduana, Grifo, Cofres...).
        equipo = crear("equipo", () -> new Equipo(this));
        aduana = crear("aduana", () -> new Aduana(this));
        saldo = crear("saldo", () -> new Saldo(this));
        creditos = crear("creditos", () -> new Creditos(this));
        monedero = crear("monedero", () -> new Monedero(this));
        entregas = crear("entregas", () -> new Entregas(this));
        reliquias = crear("reliquias", () -> new Reliquias(this));
        tasacion = crear("tasacion", () -> new Tasacion(this));
        grifo = crear("grifo", () -> new Grifo(this));
        minijefes = crear("minijefes", () -> new Minijefes(this));
        cofres = crear("cofres", () -> new Cofres(this));
        racha = crear("racha", () -> new Racha(this));
        combate = crear("combate", () -> new Combate(this));
        sellos = crear("sellos", () -> new Sellos(this));
        ligado = crear("ligado", () -> new Ligado(this));
        amenazas = crear("amenazas", () -> new Amenazas(this));
        huella = crear("huella", () -> new Huella(this));
        parca = crear("parca", () -> new Parca(this));
        // 1.8.0: despues de la Parca, cuyo cuerpo de NPC (CuerpoNpc) y listener de cascaras usa.
        ambush = crear("ambush", () -> new Ambush(this));
        ecos = crear("ecos", () -> new Ecos(this));
        parte = crear("parte", () -> new ParteDefuncion(this));
        testigos = crear("testigos", () -> new Testigos(this));
        sentidos = crear("sentidos", () -> new Sentidos(this));
        objetos = crear("objetos", () -> new ObjetosCalamity(this));
        altar = crear("altar", () -> new Altar(this));
        horas = crear("horas", () -> new Horas(this));
        hitos = crear("hitos", () -> new Hitos(this));
        kit = crear("kit", () -> new Kit(this));
        contratos = crear("contratos", () -> new Contratos(this));
        rankings = crear("rankings", () -> new Rankings(this));
        tablero = crear("tablero", () -> new Tablero(this));
        encuesta = crear("encuesta", () -> new Encuesta(this));
        eclipse = crear("eclipse", () -> new Eclipse(this));
        // 1.9.0: despues del Eclipse y de la Parca, a los que pregunta si el cielo es suyo.
        clima = crear("clima", () -> new Clima(this));
        // Calamity 1.11: los cofres y las Bovedas de Ruinas, la Boveda Caida y el ranking de clanes.
        ruinas = crear("ruinas", () -> new Ruinas(this));
        bovedaCaida = crear("boveda-caida", () -> new BovedaCaida(this));
        clanes = crear("clanes", () -> new ClanesCalamity(this));
        // Lo ultimo: los NPCs de la antesala solo abren lo que ya existe (Altar, Tablero...).
        npcs = crear("npcs", () -> new Npcs(this));
    }

    private <T> T crear(String modulo, Supplier<T> nuevo) {
        try {
            return nuevo.get();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "[Calamity] El módulo " + modulo + " no arranca", t);
            return null;
        }
    }

    /** Al reves de como nacieron: los de arriba usan a los de abajo mientras se paran. */
    private void pararModulos() {
        if (npcs != null) seguro("npcs", () -> npcs.parar());
        if (clanes != null) seguro("clanes", () -> clanes.parar());
        if (bovedaCaida != null) seguro("boveda-caida", () -> bovedaCaida.parar());
        if (ruinas != null) seguro("ruinas", () -> ruinas.parar());
        clanes = null;
        bovedaCaida = null;
        ruinas = null;
        if (clima != null) seguro("clima", () -> clima.parar());
        clima = null;
        if (eclipse != null) seguro("eclipse", () -> eclipse.parar());
        if (encuesta != null) seguro("encuesta", () -> encuesta.parar());
        if (tablero != null) seguro("tablero", () -> tablero.parar());
        if (rankings != null) seguro("rankings", () -> rankings.parar());
        if (contratos != null) seguro("contratos", () -> contratos.parar());
        if (kit != null) seguro("kit", () -> kit.parar());
        if (hitos != null) seguro("hitos", () -> hitos.parar());
        if (horas != null) seguro("horas", () -> horas.parar());
        if (altar != null) seguro("altar", () -> altar.parar());
        if (objetos != null) seguro("objetos", () -> objetos.parar());
        if (sentidos != null) seguro("sentidos", () -> sentidos.parar());
        if (testigos != null) seguro("testigos", () -> testigos.parar());
        if (parte != null) seguro("parte", () -> parte.parar());
        if (ecos != null) seguro("ecos", () -> ecos.parar());
        if (ambush != null) seguro("ambush", () -> ambush.parar());
        if (parca != null) seguro("parca", () -> parca.parar());
        if (huella != null) seguro("huella", () -> huella.parar());
        if (amenazas != null) seguro("amenazas", () -> amenazas.parar());
        if (ligado != null) seguro("ligado", () -> ligado.parar());
        if (sellos != null) seguro("sellos", () -> sellos.parar());
        if (combate != null) seguro("combate", () -> combate.parar());
        if (racha != null) seguro("racha", () -> racha.parar());
        if (cofres != null) seguro("cofres", () -> cofres.parar());
        if (minijefes != null) seguro("minijefes", () -> minijefes.parar());
        if (grifo != null) seguro("grifo", () -> grifo.parar());
        if (tasacion != null) seguro("tasacion", () -> tasacion.parar());
        if (reliquias != null) seguro("reliquias", () -> reliquias.parar());
        if (entregas != null) seguro("entregas", () -> entregas.parar());
        if (monedero != null) seguro("monedero", () -> monedero.parar());
        if (creditos != null) seguro("creditos", () -> creditos.parar());
        if (saldo != null) seguro("saldo", () -> saldo.parar());
        if (aduana != null) seguro("aduana", () -> aduana.parar());
        if (equipo != null) seguro("equipo", () -> equipo.parar());
        if (calendario != null) seguro("calendario", () -> calendario.parar());
        if (estadisticas != null) seguro("estadisticas", () -> estadisticas.parar());
        if (telemetria != null) seguro("telemetria", () -> telemetria.parar());
        if (zona != null) seguro("zona-spawn", () -> zona.parar());
        zona = null;
        distancia = null;
        enZona.clear();
        cordura.aSalvo(null);
        Subcomandos.staff().vaciar();
        Subcomandos.jugador().vaciar();
        Autotest.vaciar();
    }

    /**
     * %lethalworld_cordura%: la cordura redondeada, vacia fuera de Calamity (DIS sec. 7).
     *
     * PlaceholderAPI puede preguntar desde otro hilo: se lee el mapa de estados sin crear
     * nada (cordura.valor() crearia la entrada, y eso no se hace fuera del hilo principal).
     */
    private String corduraTexto(org.bukkit.OfflinePlayer jugador) {
        Player p = jugador == null ? null : jugador.getPlayer();
        if (p == null || !esHardcore(p)) return "";
        Cordura.Estado e = cordura.todos().get(p.getUniqueId());
        return e == null ? "" : String.valueOf(Math.round(e.valor));
    }

    /**
     * Corre un gancho de otro modulo sin dejar que su fallo tumbe lo demas.
     *
     * Calamity son una docena de modulos que se mezclan en una noche. Si uno revienta
     * dentro del reloj, sin esto se para la cordura de todos; y si revienta en onMuerte,
     * el inventario no se borra y el muerto sale con todo. El aviso sale una vez por minuto
     * y modulo, con la traza, para que se vea sin inundar la consola.
     */
    void seguro(String modulo, Runnable gancho) {
        try {
            gancho.run();
        } catch (Throwable t) {
            avisarFallo(modulo, t);
        }
    }

    /** Lo mismo que seguro() para los ganchos que devuelven algo: con fallo, el defecto. */
    <T> T valor(String modulo, Supplier<T> gancho, T def) {
        try {
            T v = gancho.get();
            return v == null ? def : v;
        } catch (Throwable t) {
            avisarFallo(modulo, t);
            return def;
        }
    }

    private void avisarFallo(String modulo, Throwable t) {
        long ahora = System.currentTimeMillis();
        Long antes = ultimoFallo.get(modulo);
        if (antes != null && ahora - antes < 60_000) return;
        ultimoFallo.put(modulo, ahora);
        plugin.getLogger().log(Level.WARNING, "[Calamity] Fallo en el módulo " + modulo, t);
    }

    // ---------------------------------------------------------------------- equipo

    /**
     * Calamity 1.4: lo que el equipo de ese jugador (GodItems, calamity.*) pone en ese efecto, ya topado. 0 sin
     * equipo, sin el modulo o si falla: con 0 cada regla hace exactamente lo de siempre.
     */
    double delEquipo(Player p, Equipo.Efecto efecto) {
        Equipo eq = equipo;
        return eq == null || p == null ? 0 : valor("equipo", () -> eq.valor(p, efecto), 0.0);
    }

    /**
     * Calamity 1.4: n Esencias de un mob, un minijefe o un cofre con el esencias-bonus de su equipo. Se
     * llama ANTES de Aduana.pagar, que topa el total como siempre; la Tasacion no pasa por aqui.
     */
    int esenciasDelEquipo(Player p, int n) {
        Equipo eq = equipo;
        // Solo dentro: un participante de un minijefe que ya ha salido cobra lo suyo, sin el equipo.
        if (eq == null || p == null || n <= 0 || !esHardcore(p)) return n;
        return valor("equipo", () -> eq.esencias(p, n), n);
    }

    /** /calamity reload dice de donde sale el equipo (GodItems). Devuelve el resumen, o null con las reglas apagadas. */
    public String recargarEquipo() {
        Equipo eq = equipo;
        return eq == null ? null : valor("equipo", eq::cargar, null);
    }

    // ----------------------------------------------------------------------- datos

    /**
     * hardcore-datos.yml en memoria, para los modulos. Lo que se escriba aqui tiene que ir
     * seguido de marcarSucio() (se vuelca en el minuto) o de guardarYa() si hay objetos en
     * juego (Eco, pagos): una caida entre medias no puede ni perderlos ni duplicarlos.
     */
    public YamlConfiguration datos() {
        return datos;
    }

    public void marcarSucio() {
        datosSucios = true;
    }

    public void guardarYa() {
        datosSucios = true;
        guardarDatos();
    }

    /**
     * Lee datos.yml y, la primera vez, se trae lo que las versiones de antes dejaron
     * dentro del config.yml (hardcore.tiempo, hardcore.tag-entregado y
     * hardcore.guardado). Es el unico momento en que esto guarda el config, y es
     * seguro: acaba de leerse del disco, no hay edicion de nadie que pisar.
     */
    private void cargarDatos() {
        archivoDatos = new java.io.File(plugin.getDataFolder(), "hardcore-datos.yml");
        datos = YamlConfiguration.loadConfiguration(archivoDatos);

        boolean migrado = false;
        for (String seccion : List.of("tiempo", "tag-entregado", "guardado")) {
            ConfigurationSection vieja = plugin.getConfig().getConfigurationSection("hardcore." + seccion);
            if (vieja == null) continue;
            for (String clave : vieja.getKeys(false)) {
                // Lo de datos.yml manda: si ya estaba, es mas nuevo que lo del config.
                if (!datos.isSet(seccion + "." + clave)) {
                    datos.set(seccion + "." + clave, vieja.get(clave));
                }
            }
            if (plugin.getConfig().isSet("hardcore." + seccion)) {
                plugin.getConfig().set("hardcore." + seccion, null);
                migrado = true;
            }
        }
        if (migrado) {
            datosSucios = true;
            guardarDatos();
            plugin.saveConfig();
            plugin.getLogger().info("[Calamity] Horas, tags y cordura guardada pasan a hardcore-datos.yml.");
        }
    }

    /* 1.8.1: aqui hubo una migracion automatica (migrarParcaCincoMinutos) que ponia la Parca a
     * 5 minutos y guardaba con plugin.saveConfig(). En el SurvivalTest reescribio config.yml
     * entero con solo unas pocas secciones (1.529 lineas -> 109) y se perdio el resto. Se retiro:
     * Calamity NUNCA reescribe la config del servidor; los valores se cambian a mano (regla de
     * Dosa: "no se regenera ni se reescribe el fichero completo"). */

    private void guardarDatos() {
        if (!datosSucios || archivoDatos == null) return;
        try {
            datos.save(archivoDatos);
            datosSucios = false;
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("[Calamity] No se pudo guardar hardcore-datos.yml: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------------- reloj

    private void tick() {
        java.util.Set<UUID> vistos = new java.util.HashSet<>();
        // 1.11: BossBar (de serie) o la barra de accion de la 1.10. Se lee cada segundo: vale tras un reload.
        boolean bossbar = MedidorCordura.enBossBar(cfg().getString("cordura.pantalla", "actionbar"));
        cordura.pantalla(bossbar);
        barra.limpiarAlAcabar(bossbar);
        for (World w : plugin.getServer().getWorlds()) {
            if (!esHardcore(w)) continue;
            for (Player p : w.getPlayers()) {
                if (!cuenta(p)) {
                    // 1.11: quien no cuenta (creativo, vanish) ve el mismo clima, sin dano: en el spawn solo el cielo rojo y,
                    // fuera, el clima de su bioma (1.12: el mundo nunca llueve; todo lo pinta Clima).
                    if (clima != null) seguro("clima", () -> clima.soloVista(p, enSpawn(p)));
                    continue;
                }
                Cordura.Estado e = cordura.estado(p);
                e.segundosDentro++;
                vistos.add(p.getUniqueId());
                /* 1.2: en la zona spawn solo sigue lo que no va contra el: la Huella (que alli es la
                 * Grieta), la barra, el Cristal, las horas, el suelo, el aviso de su Eco, la etiqueta
                 * de combate y sus objetos. Ni drenaje, ni bioma, ni niebla, ni minijefe, ni sentidos. */
                boolean spawn = vigilarSpawn(p);
                seguro("huella", () -> huella.segundo(p));
                if (!spawn) {
                    drenar(p, e);
                    efectosDeBioma(p);
                    // 1.9.0/1.12: el clima de su bioma mientras dura la lluvia del ciclo, fuera del spawn.
                    if (clima != null) seguro("clima", () -> clima.segundo(p));
                } else if (clima != null) {
                    // 1.11: en el spawn no hay clima, salvo el cielo de sangre del bioma rojo.
                    seguro("clima", () -> clima.enSpawn(p));
                }
                // 1.7: el aviso de franja de distancia, antes de pintar para que salga ya.
                if (distancia != null) seguro("distancia", () -> distancia.segundo(p, spawn));
                cordura.pintar(p);
                vigilarCanalizacion(p);
                if (!spawn) nieblaDeNoche(p);
                contarTiempo(p);
                if (!spawn && e.valor <= 0) minijefeSiTocaCordura(p, e);
                apuntarSuelo(p);
                if (!spawn) seguro("sentidos", () -> sentidos.latido(p));
                seguro("ecos", () -> ecos.avisoDistancia(p));
                seguro("combate", () -> combate.tick(p));
                seguro("objetos", () -> objetos.tick(p));
            }
        }
        // Quien ya no esta dentro (salio, murio, se desconecto) deja de contar como "en el spawn".
        enZona.retainAll(vistos);
        // 1.11: la BossBar de la cordura solo la tiene quien se acaba de pintar (dentro y contando).
        cordura.podarPantalla(vistos);
        if (distancia != null) distancia.podar(vistos);
        if (zona != null) seguro("zona-spawn", () -> zona.tick());
        vigilarZonas();
        vigilarPresas();
        // Revision 1.10: los minijefes sin presa que se sacaron al borde (despues de zona.tick, que los saca).
        seguro("zona-spawn", this::vigilarSinPresa);
        seguro("parca", () -> parca.tick());
        if (ambush != null) seguro("ambush", () -> ambush.tick());
        seguro("ecos", () -> ecos.tick());
        seguro("aduana", () -> aduana.tick());
        seguro("eclipse", () -> eclipse.tick());
        // Quien no ha pasado por clima.segundo (spawn, espectador, fuera del mundo) recupera su cielo.
        if (clima != null) seguro("clima", () -> clima.tick());
        if (++segundosManto >= 30) {
            segundosManto = 0;
            seguro("hitos", () -> hitos.tickManto());
        }
        // Una vez por minuto, y solo si algo cambio: nadie dentro, nada que escribir.
        if (++segundosSinGuardar >= 60) {
            segundosSinGuardar = 0;
            guardarDatos();
        }
        // Quien haya salido del mundo con una canalizacion a medias no se queda colgado.
        canalizando.keySet().removeIf(id -> {
            Player p = plugin.getServer().getPlayer(id);
            boolean fuera = p == null || !p.isOnline() || !esHardcore(p);
            if (fuera) cuentaCristal.remove(id);
            return fuera;
        });
    }

    /**
     * 1.2 · Si esta en la zona spawn, y lo que pasa al cruzar el borde. Al entrar se le apagan la
     * vineta y las alucinaciones y la PARCA que le perseguia se va a esperarle fuera
     * (Parca.alEntrarSpawn); en la barra se le dice, para que sepa por que la cordura se para.
     */
    private boolean vigilarSpawn(Player p) {
        boolean dentro = enSpawn(p);
        UUID u = p.getUniqueId();
        if (dentro && enZona.add(u)) {
            seguro("sentidos", () -> sentidos.alEntrarSpawn(p));
            seguro("parca", () -> parca.alEntrarSpawn(p));
            // 1.11: los premios pendientes se entregan solos en la zona spawn (Entregas.recibeYa), como fuera.
            if (entregas != null) seguro("entregas", () -> entregas.pendientes(p));
            cordura.destello(p, Component.text("Estás en el spawn", Paleta.DETALLE)
                    .append(Component.text(": aquí la cordura no baja.", Paleta.TEXTO)), 3);
        } else if (!dentro && enZona.remove(u)) {
            cordura.destello(p, Component.text("Sales del spawn", Paleta.TENUE)
                    .append(Component.text(": la cordura vuelve a bajar.", Paleta.TEXTO)), 2);
        }
        return dentro;
    }

    /**
     * Los dos portales: el de fuera mete y el de dentro saca.
     *
     * No son bloques de portal de verdad (eso obliga a tocar el mundo y a pelearse con
     * WorldGuard): son PUNTOS con radio que Dosa marca donde quiera construir la
     * puerta. Se miran una vez por segundo, que para caminar hacia una puerta sobra y
     * no cuesta lo que costaria un listener de movimiento.
     */
    private void vigilarZonas() {
        Location llegada = punto("llegada");
        if (llegada != null && vara != null) {
            for (World w : plugin.getServer().getWorlds()) {
                if (esHardcore(w)) continue;
                for (Player p : w.getPlayers()) {
                    if (!cuenta(p) || !vara.dentro(p, "entrada")) continue;
                    long espera = cuarentenaRestante(p);
                    if (espera > 0) {
                        barra.fondo(p, Component.text(
                                "Aún no puedes volver a entrar. Espera " + (espera / 60_000 + 1) + " min.", Paleta.AVISO));
                        continue;
                    }
                    meter(p, llegada);
                }
            }
        }
        if (vara == null) return;
        for (World w : plugin.getServer().getWorlds()) {
            if (!esHardcore(w)) continue;
            for (Player p : w.getPlayers()) {
                if (cuenta(p) && vara.dentro(p, "salida")) sacar(p, "puerta", true);
            }
        }
    }

    /**
     * Apunta donde pisa suelo firme. Sirve para devolver a alguien (o a una amenaza que se
     * cae del mapa) a un sitio donde se pueda estar, sin buscarlo en el momento.
     */
    private void apuntarSuelo(Player p) {
        if (p.isInsideVehicle() || p.isInWater() || p.isFlying() || p.isGliding()) return;
        org.bukkit.block.Block bajo = p.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN);
        if (!bajo.getType().isSolid()) return;
        ultimoSuelo.put(p.getUniqueId(), p.getLocation().clone());
    }

    /** El ultimo suelo firme que piso ese jugador dentro, o null. */
    public Location ultimoSuelo(Player p) {
        Location l = ultimoSuelo.get(p.getUniqueId());
        return l == null ? null : l.clone();
    }

    /**
     * Lo que le queda de castigo por haber muerto dentro, en millis. 0 = puede entrar.
     *
     * Existe para que morir duela mas alla del inventario: sin esto, la muerte era
     * volver a entrar y seguir. Apagada de serie (muerte.cuarentena-minutos: 0).
     */
    public long cuarentenaRestante(Player p) {
        int minutos = cfg().getInt("muerte.cuarentena-minutos", 0);
        if (minutos <= 0) return 0;
        Long murio = muertos.get(p.getUniqueId());
        if (murio == null) return 0;
        long queda = murio + minutos * 60_000L - System.currentTimeMillis();
        if (queda <= 0) {
            muertos.remove(p.getUniqueId());
            return 0;
        }
        return queda;
    }

    private boolean dentroDe(Player p, Location centro) {
        double r = cfg().getDouble("radio-zonas", 3);
        return p.getWorld() == centro.getWorld()
                && p.getLocation().distanceSquared(centro) <= r * r;
    }

    /** Un punto guardado en la config, o null si no esta puesto o su mundo no carga. */
    public Location punto(String nombre) {
        ConfigurationSection s = cfg().getConfigurationSection(nombre);
        if (s == null || !s.isSet("mundo")) return null;
        World w = mundoDe(s.getString("mundo", ""));
        if (w == null) return null;
        return new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    /** Guarda un punto donde este el jugador. Lo usa /calamity define. */
    public void punto(String nombre, Location donde) {
        String base = "hardcore." + nombre + ".";
        plugin.getConfig().set(base + "mundo", donde.getWorld().getKey().toString());
        plugin.getConfig().set(base + "x", donde.getX());
        plugin.getConfig().set(base + "y", donde.getY());
        plugin.getConfig().set(base + "z", donde.getZ());
        plugin.getConfig().set(base + "yaw", donde.getYaw());
        plugin.getConfig().set(base + "pitch", donde.getPitch());
        plugin.saveConfig();
    }

    /** Busca un mundo por su clave completa (lethal_world:calamity) o por su nombre. */
    private World mundoDe(String id) {
        org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(id);
        World w = key == null ? null : plugin.getServer().getWorld(key);
        return w != null ? w : plugin.getServer().getWorld(id);
    }

    /**
     * Baja la cordura un poco cada segundo. Lo que la acelera: la noche, la oscuridad
     * y el bioma en el que estes. La cuenta va por minuto en la config porque asi se
     * razona ("cien minutos de expedicion"), y aqui se reparte entre los 60 segundos.
     */
    private void drenar(Player p, Cordura.Estado e) {
        double porMinuto = cfg().getDouble("cordura.por-minuto", 1.0);
        double factor = 1;

        if (esNoche(p)) factor *= cfg().getDouble("cordura.factor-noche", 2.0);
        if (p.getLocation().getBlock().getLightLevel() < 4) {
            factor *= cfg().getDouble("cordura.factor-oscuridad", 2.0);
        }
        factor *= drenajeDelBioma(p);
        // La PARCA cerca, el Talisman de Vigilia y el Eclipse: cada uno pone su factor.
        factor *= valor("parca", () -> parca.factorDrenaje(p), 1.0);
        factor *= valor("objetos", () -> objetos.factorDrenaje(p), 1.0);
        factor *= valor("eclipse", () -> eclipse.factorCordura(), 1.0);
        // 1.4: y el equipo (cordura-drenaje) quita su parte de todo lo anterior.
        factor = Equipo.menos(factor, delEquipo(p, Equipo.Efecto.CORDURA_DRENAJE));

        double antes = e.valor;
        cordura.sumar(p, -(porMinuto * factor) / 60.0);
        anunciarTramo(p, e, antes);
    }

    /** De noche en su mundo, o durante un Eclipse (que es de noche para todo lo que cuenta). */
    public boolean esNoche(Player p) {
        long hora = p.getWorld().getTime();
        if (hora >= 13000 && hora <= 23000) return true;
        return eclipse != null && valor("eclipse", () -> eclipse.activo(), false);
    }

    /** El multiplicador de drenaje que le pone su bioma, 1 si no tiene nada dicho. */
    private double drenajeDelBioma(Player p) {
        ConfigurationSection b = biomaDe(p);
        return b == null ? 1 : b.getDouble("cordura", 1.0);
    }

    private ConfigurationSection biomaDe(Player p) {
        ConfigurationSection biomas = cfg().getConfigurationSection("biomas");
        if (biomas == null) return null;
        String clave = p.getLocation().getBlock().getBiome().getKey().getKey();
        return biomas.getConfigurationSection(clave);
    }

    /**
     * Los efectos negativos del bioma. Se renuevan cada pocos segundos con duracion
     * corta: si se pusieran largos, saldrias del bioma y seguirias arrastrandolos, y
     * lo que se quiere es que el castigo sea del SITIO, no de haber pasado por el.
     */
    private void efectosDeBioma(Player p) {
        int cada = Math.max(1, cfg().getInt("biomas-cada-segundos", 3));
        long ahora = System.currentTimeMillis();
        Long ultimo = ultimoEfecto.get(p.getUniqueId());
        if (ultimo != null && ahora - ultimo < cada * 1000L) return;
        ultimoEfecto.put(p.getUniqueId(), ahora);

        ConfigurationSection b = biomaDe(p);
        if (b == null) return;
        for (String linea : b.getStringList("efectos")) {
            // Formato: EFECTO[:nivel][:segundos]  ->  HUNGER:1  ·  SLOW:0:6
            String[] partes = linea.split(":");
            PotionEffectType tipo = efecto(partes[0]);
            if (tipo == null) {
                plugin.getLogger().warning("[Calamity] Efecto desconocido en la config: " + partes[0]);
                continue;
            }
            int nivel = partes.length > 1 ? parse(partes[1], 0) : 0;
            int segundos = partes.length > 2 ? parse(partes[2], cada + 2) : cada + 2;
            p.addPotionEffect(new PotionEffect(tipo, segundos * 20, nivel, true, false, false));
        }
        if (b.getBoolean("congelacion", false)) {
            p.setFreezeTicks(Math.min(p.getMaxFreezeTicks(), p.getFreezeTicks() + cada * 20));
        }
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Busca el efecto por su nombre del registro, tolerando los nombres viejos. */
    private static PotionEffectType efecto(String nombre) {
        String id = nombre.trim().toLowerCase(Locale.ROOT);
        org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(
                id.contains(":") ? id : "minecraft:" + id);
        if (key != null) {
            PotionEffectType t = org.bukkit.Registry.EFFECT.get(key);
            if (t != null) return t;
        }
        return null;
    }

    /** Avisa al cruzar un escalon hacia abajo, una sola vez por escalon. */
    private void anunciarTramo(Player p, Cordura.Estado e, double antes) {
        int ahora = Cordura.tramo(e.valor);
        if (ahora >= e.ultimoTramo) {
            e.ultimoTramo = ahora;
            return;
        }
        e.ultimoTramo = ahora;
        /* Cada escalon dice lo que cambia de verdad (bonusTope y bonusNivel; el minijefe de
         * minijefeSiTocaCordura). Las frases de antes ("Algo te sigue con la mirada", "Las voces no
         * callan") insinuaban cosas que no pasan: las alucinaciones van apagadas de serie. */
        String texto = switch (ahora) {
            case 3 -> "Tu cordura baja de 75. A la mitad, los mobs se endurecen.";
            case 2 -> "Tu cordura baja de 50: salen más mobs y más fuertes.";
            case 1 -> "Tu cordura baja de 25: los mobs son todavía más fuertes.";
            default -> "Te has quedado sin cordura: salen aún más mobs y un minijefe puede venir por ti.";
        };
        p.sendMessage(Component.text(texto, Cordura.color(e.valor)));
        Compat.sound(p.getWorld(), p.getLocation(),
                ahora == 0 ? "entity.warden.roar" : "ambient.cave", 1.0f, ahora == 0 ? 0.6f : 0.5f);
        if (ahora <= 1) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 120, 0, true, false, false));
        }
    }

    // ------------------------------------------------------------------ minijefes

    /**
     * Con la cordura a cero viene a buscarte el minijefe del bioma donde estas.
     *
     * Calamity 1.10: cada uno de los cinco vive en sus biomas (hardcore.minijefes.por-bioma,
     * Minijefes.elegir). En un bioma sin dueno, o sin tabla, viene uno al azar de minijefes.tipos,
     * como hasta la 1.9. No sale uno por segundo: hay un descanso (minutosMinijefe) entre
     * apariciones para que quedarse a cero sea una condena, no una granja de jefes. Es el mismo
     * descanso que pide el Reclamo, que trae al suyo por la misma ruta (traerMinijefe), y se mira
     * contra la ultima llegada guardada (ultimoMinijefe): salir y volver, reconectar o morir no lo borran.
     */
    private void minijefeSiTocaCordura(Player p, Cordura.Estado e) {
        // Ley 6: una amenaza grande a la vez. Con la PARCA encima no viene nadie mas.
        if (valor("parca", () -> parca.persigue(p), false)) return;
        long ahora = System.currentTimeMillis();
        if (Reclamo.faltanMinutos(ultimoMinijefe(p), ahora, minutosMinijefe()) > 0) return;

        String id = Minijefes.elegir(Minijefes.bioma(p.getLocation()), Minijefes.porBioma(plugin.getConfig()),
                cfg().getStringList("minijefes.tipos"), random::nextInt);
        if (id == null) return;
        // Si no encuentra sitio no apunta nada: lo vuelve a intentar al segundo siguiente.
        traerMinijefe(p, id, e);
    }

    /**
     * Calamity 1.10 · Trae ese minijefe por ese jugador: lo invoca (MobsLethal.invocarMinijefe), lo
     * marca como presa, apunta el descanso en su Estado y en hardcore-datos (apuntarUltimoMinijefe) y
     * le avisa. Es la ruta del de cordura cero y la del Reclamo. Null si no ha encontrado sitio, y
     * entonces no apunta nada.
     */
    LivingEntity traerMinijefe(Player p, String tipo, Cordura.Estado e) {
        if (plugin.mobs() == null || tipo == null) return null;
        LivingEntity mob = plugin.mobs().invocarMinijefe(p, tipo, cfg().getDouble("minijefes.distancia", 30),
                cfg().getDouble("minijefes.vida", 15), cfg().getDouble("minijefes.dano", 4));
        if (mob == null) return null;
        marcarPresa(mob, p);

        long ahora = System.currentTimeMillis();
        e.ultimoMinijefe = ahora;
        apuntarUltimoMinijefe(p.getUniqueId(), ahora);
        // 1.8.4: el mismo nombre que su cartel (Paleta.minijefe), con el nivel detras. Antes se leia el
        // customName, que EDM no pone, y el aviso siempre decia "un minijefe".
        p.sendMessage(Component.text("Ha venido por ti: ", Paleta.AVISO).append(plugin.mobs().nombreMinijefe(mob)));
        Compat.sound(p.getWorld(), p.getLocation(), "entity.wither.spawn", 1.0f, 0.6f);
        return mob;
    }

    /**
     * Calamity 1.10 · La ultima vez que vino un minijefe por ese jugador (cordura cero o Reclamo), o 0: la
     * de su Estado o la guardada en hardcore-datos, la mas reciente. La guardada es la que manda: el
     * Estado se olvida al salir de Calamity, al desconectarse y al morir, y con solo el, salir y volver
     * era un minijefe detras de otro. La miran el de cordura cero y el Reclamo.
     */
    long ultimoMinijefe(Player p) {
        return ultimoMinijefe(cordura.estado(p).ultimoMinijefe,
                datos.getLong(RUTA_ULTIMO_MINIJEFE + "." + p.getUniqueId(), 0));
    }

    /** La mas reciente de las dos (0 = ninguna). Sin Bukkit: lo prueba el autotest del Reclamo. */
    static long ultimoMinijefe(long enMemoria, long guardado) {
        return Math.max(enMemoria, guardado);
    }

    /**
     * La ultima llegada, a hardcore-datos (se escribe en el minuto, o al parar). De paso se borran las de
     * hace mas de un dia: ningun descanso entre minijefes dura tanto.
     */
    private void apuntarUltimoMinijefe(UUID u, long ahora) {
        ConfigurationSection s = datos.getConfigurationSection(RUTA_ULTIMO_MINIJEFE);
        if (s != null) {
            for (String k : s.getKeys(false)) if (ahora - s.getLong(k, 0) > 86_400_000L) s.set(k, null);
        }
        datos.set(RUTA_ULTIMO_MINIJEFE + "." + u, ahora);
        datosSucios = true;
    }

    /** Minutos entre dos minijefes del mismo jugador: minijefes.cada-minutos, o los del Eclipse si hay uno. */
    int minutosMinijefe() {
        int def = cfg().getInt("minijefes.cada-minutos", 10);
        Eclipse ec = eclipse;
        return ec == null ? def : valor("eclipse", () -> ec.minutosMinijefe(def), def);
    }

    /**
     * Revision 1.10 · Si ese tipo de /esb es uno de los minijefes de Calamity: los de
     * hardcore.minijefes.tipos y los de hardcore.minijefes.por-bioma (Minijefes.tipoConocido). Publico y
     * sin las reglas en marcha: lo usa CartelesMinijefe, de otro paquete, para saber a que carteles poner
     * el nombre de Calamity.
     */
    public static boolean esTipoMinijefe(org.bukkit.configuration.Configuration raiz, String tipo) {
        if (raiz == null || tipo == null) return false;
        return Minijefes.tipoConocido(raiz.getStringList("hardcore.minijefes.tipos"), Minijefes.porBioma(raiz), tipo);
    }

    /**
     * Calamity 1.10 · Si algun minijefe vivo viene ya por ese jugador (marcarPresa). Lo pregunta el
     * Reclamo: uno detras de otro no. Los que ya no existen no cuentan (vigilarPresas los poda).
     */
    boolean tieneMinijefe(Player p) {
        if (p == null) return false;
        for (Map.Entry<UUID, UUID> e : presas.entrySet()) {
            if (!e.getValue().equals(p.getUniqueId())) continue;
            Entity mob = plugin.getServer().getEntity(e.getKey());
            if (mob != null && mob.isValid()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ dificultad

    /**
     * Mobs de mas alrededor de un jugador: segun lo ida que tenga la cabeza y, desde la 1.7,
     * +1 por cada dificultad.mobs-extra-cada-minutos (15) de la sesion, hasta mobs-extra-tope (4).
     */
    public int bonusTope(Player p) {
        if (!esHardcore(p)) return 0;
        return mobsExtraCordura(p) + mobsExtraTiempo(p);
    }

    /** La parte de bonusTope que pone la cordura (por debajo de 50 y a cero). */
    int mobsExtraCordura(Player p) {
        double v = cordura.valor(p);
        if (v >= 50) return 0;
        return v <= 0 ? cfg().getInt("cordura.mobs-extra-vacio", 6)
                : cfg().getInt("cordura.mobs-extra", 4);
    }

    /** La parte de bonusTope que pone el tiempo dentro (mismo contador de sesion que el nivel). */
    int mobsExtraTiempo(Player p) {
        return Distancia.mobsExtraTiempo(cordura.estado(p).segundosDentro,
                cfg().getInt("dificultad.mobs-extra-cada-minutos", 15), cfg().getInt("dificultad.mobs-extra-tope", 4));
    }

    /**
     * Niveles de mas para los mobs que salgan alrededor de ese jugador: cordura, minutos dentro,
     * Racha y Eclipse. La distancia al spawn NO va aqui (ver bonusDistancia): esta suma la usan
     * tambien la PARCA y el Eco, y esos no cambian con la distancia.
     */
    public int bonusNivel(Player p) {
        if (!esHardcore(p)) return 0;
        int extra = 0;
        double v = cordura.valor(p);
        if (v < 25) extra += cfg().getInt("cordura.nivel-extra-critico", 20);
        else if (v < 50) extra += cfg().getInt("cordura.nivel-extra", 10);

        // Y sube con los minutos que lleves dentro: quedarse es cada vez peor idea.
        extra += Distancia.nivelPorMinutos(cordura.estado(p).segundosDentro,
                cfg().getInt("dificultad.nivel-cada-minutos", 3));
        // La Racha de Codicia y el Eclipse suben el nivel encima de todo lo anterior.
        if (activo()) {
            extra += valor("racha", () -> racha.niveles(p), 0);
            extra += valor("eclipse", () -> eclipse.nivelesExtra(), 0);
        }
        return extra;
    }

    /**
     * 1.7 · Niveles de mas por lo lejos que este ese jugador del borde de la zona spawn
     * (hardcore.distancia). Lo suma MobsLethal.nivelPara a los esbirros, adoptados, guarniciones
     * y minijefes que salen para el; la PARCA y el Eco no lo ven. 0 fuera de Calamity.
     */
    public int bonusDistancia(Player p) {
        if (distancia == null || !esHardcore(p)) return 0;
        return valor("distancia", () -> distancia.niveles(p), 0);
    }

    /** Bloques del jugador al borde de la zona spawn (o al spawn del mundo), para /calamity level y el parte. */
    public double bloquesAlSpawn(Player p) {
        if (distancia == null || p == null) return 0;
        return valor("distancia", () -> distancia.bloques(p.getLocation()), 0.0);
    }

    /**
     * Para /calamity level: de donde salen los niveles y los mobs de mas de Calamity para ese
     * jugador ahora mismo, en pares {que, cuanto}. Vacio si no esta en un mundo hardcore.
     */
    public List<String[]> desgloseNivel(Player p) {
        if (!esHardcore(p)) return List.of();
        List<String[]> l = new ArrayList<>();
        double v = cordura.valor(p);
        int porCordura = v < 25 ? cfg().getInt("cordura.nivel-extra-critico", 20)
                : v < 50 ? cfg().getInt("cordura.nivel-extra", 10) : 0;
        l.add(new String[]{"cordura +" + porCordura, "cordura " + Math.round(v) + " %"});
        int segundos = cordura.estado(p).segundosDentro;
        int cada = cfg().getInt("dificultad.nivel-cada-minutos", 3);
        l.add(new String[]{"minutos +" + Distancia.nivelPorMinutos(segundos, cada),
                (segundos / 60) + " min en Calamity" + (cada > 0 ? ", +1 cada " + cada + " min" : ", apagado")});
        Distancia.Ajustes a = distancia == null ? null : distancia.ajustes();
        long bloques = Math.round(bloquesAlSpawn(p));
        String como = a == null ? "sin módulo" : !a.activa() ? "apagada"
                : enSpawn(p) ? "en la zona del spawn"
                : Distancia.miles(bloques) + " bloques del borde del spawn, +1 cada " + a.bloquesPorNivel()
                        + ", tope " + a.tope();
        l.add(new String[]{"distancia +" + bonusDistancia(p), como});
        l.add(new String[]{"racha +" + valor("racha", () -> racha.niveles(p), 0), "Racha de Codicia"});
        l.add(new String[]{"eclipse +" + valor("eclipse", () -> eclipse.nivelesExtra(), 0), "Eclipse"});
        int cadaMobs = cfg().getInt("dificultad.mobs-extra-cada-minutos", 15);
        l.add(new String[]{"mobs +" + mobsExtraTiempo(p) + " por tiempo",
                cadaMobs > 0 ? "+1 cada " + cadaMobs + " min, tope " + cfg().getInt("dificultad.mobs-extra-tope", 4)
                        : "apagado"});
        l.add(new String[]{"mobs +" + mobsExtraCordura(p) + " por cordura", "por debajo del 50 %"});
        return l;
    }

    /** hardcore.distancia.mobcoins-por-nivel: lo que sube cada nivel de distancia las MobCoins (0,01 = 1 %). */
    public double mobcoinsPorNivelDistancia() {
        return cfg().getDouble("distancia.mobcoins-por-nivel", 0.01);
    }

    /** Sin camas: aqui no se salta la noche ni se pone punto de reaparicion. */
    @EventHandler(ignoreCancelled = true)
    public void onCama(PlayerBedEnterEvent e) {
        if (!esHardcore(e.getPlayer())) return;
        if (!cfg().getBoolean("dificultad.sin-camas", true)) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(Component.text("En Calamity no se puede dormir.", Paleta.AVISO));
    }

    /** Sin regeneracion natural: se cura con pociones y comida, no esperando. */
    @EventHandler(ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player p) || !esHardcore(p)) return;
        if (!cfg().getBoolean("dificultad.sin-regeneracion", true)) return;
        if (e.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED
                || e.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN) {
            e.setCancelled(true);
        }
    }

    /**
     * Los mobs de alli ignoran parte de la armadura.
     *
     * Se hace subiendo el dano final en vez de tocando el atributo de armadura del
     * jugador: asi el equipo sigue valiendo para todo lo demas (caidas, otros mundos)
     * y no hay que devolver nada al salir.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGolpe(EntityDamageByEntityEvent e) {
        if (!esHardcore(e.getEntity().getWorld())) return;
        Entity quien = autor(e.getDamager());
        boolean deAmenaza = Marcas.esAmenaza(e.getDamager()) || Marcas.esAmenaza(quien);
        if (e.getEntity() instanceof Player p) {
            /* Ni la PARCA ni el Eco llevan la penetracion: sus numeros ya salen hechos (los
             * del Eco, del equipo del muerto) y un 30 % encima los descuadraria. Tampoco el
             * golpe letal del dano verdadero, que ya es exacto y no pasa por armadura. */
            double penetracion = cfg().getDouble("dificultad.penetracion-armadura", 0.30);
            if (penetracion > 0 && e.getDamager() instanceof LivingEntity && !(e.getDamager() instanceof Player)
                    && !deAmenaza && !DanoVerdadero.enCurso.contains(p.getUniqueId())) {
                e.setDamage(e.getDamage() * (1 + penetracion));
            }
            // Un golpe fuerte tambien cuesta cordura: el susto se paga.
            double porGolpe = cfg().getDouble("cordura.por-golpe", 5);
            if (porGolpe > 0 && e.getFinalDamage() >= p.getHealth() * 0.25) {
                cordura.sumar(p, -porGolpe);
            }
            // El Cristal se corta con un golpe de verdad: el de un jugador o una amenaza.
            // Un zombi cualquiera no: si no, con un mob pegado nunca se podria salir. Con la
            // PARCA encima, cualquier golpe (sec. 1.8): huir de ella no es cosa de 10 s quieto.
            if (canalizando.containsKey(p.getUniqueId())
                    && (deAmenaza || quien instanceof Player || valor("parca", () -> parca.persigue(p), false))) {
                cortarCristal(p, "Un golpe ha apagado el Cristal.");
            }
        }
        seguro("combate", () -> combate.alGolpe(e));
        seguro("huella", () -> huella.alGolpe(e));
        seguro("combate", () -> combate.frenesiMob(e));
    }

    /** Quien de verdad pega: el que dispara, si es un proyectil. */
    private static Entity autor(Entity danador) {
        if (danador instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Entity fuente) {
            return fuente;
        }
        return danador;
    }

    /**
     * Las horas que lleva cada uno en Calamity, sumadas de verdad.
     *
     * El contador de la sesion (segundosDentro) sirve para que los mobs suban de nivel
     * mientras estas dentro, pero se va al salir. Este es el otro: se guarda en la
     * config y no se reinicia nunca, porque es lo que se premia con el tag.
     */
    private void contarTiempo(Player p) {
        String ruta = "tiempo." + p.getUniqueId();
        long ahora = datos.getLong(ruta, 0) + 1;
        datos.set(ruta, ahora);
        datosSucios = true;
        // A disco va una vez por minuto (ver tick), no cada segundo: es un contador,
        // no un pago, y guardar 60 veces por minuto por jugador no lo merece.
        entregarTag(p, ahora);
        // Las horas ACTIVAS (M33) van aparte: estas siguen contando como siempre para el
        // tag de hoy, y aquellas solo suman el minuto en que se ha movido.
        seguro("horas", () -> horas.segundo(p));
    }

    /** Horas acumuladas de un jugador en los mundos hardcore. */
    public double horasDe(Player p) {
        return datos.getLong("tiempo." + p.getUniqueId(), 0) / 3600.0;
    }

    /**
     * El tag de las veinticuatro horas.
     *
     * El plugin Tags decide quien puede ponerse cada etiqueta por un PERMISO, asi que
     * entregarla es darle ese permiso por LuckPerms; la etiqueta en si vive en
     * plugins/Tags/tags.yml y no la toca nadie desde aqui.
     */
    private void entregarTag(Player p, long segundos) {
        ConfigurationSection t = cfg().getConfigurationSection("tag");
        if (t == null || !t.getBoolean("activo", true)) return;
        long pide = (long) (t.getDouble("horas", 24) * 3600);
        if (segundos < pide) return;

        String yaEsta = "tag-entregado." + p.getUniqueId();
        if (datos.getBoolean(yaEsta, false)) return;
        datos.set(yaEsta, true);
        datosSucios = true;
        guardarDatos();

        String comando = t.getString("comando", "lp user %jugador% permission set insomne.badge.unlocked true");
        try {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                    comando.replace("%jugador%", p.getName()));
        } catch (Throwable e) {
            plugin.getLogger().warning("[Calamity] No se pudo entregar el tag a " + p.getName() + ": " + e);
            return;
        }
        String nombre = t.getString("nombre", "[INSOMNE]");
        // Las horas que pide la config (tag.horas), no un 24 escrito a mano.
        String horas = Marco.numero(t.getDouble("horas", 24)) + " horas";
        // Un hito que se gana una vez: aqui si va un titulo.
        p.showTitle(net.kyori.adventure.title.Title.title(
                Paleta.calido(nombre),
                Component.text(horas + " en Calamity", Paleta.TEXTO),
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(300),
                        java.time.Duration.ofMillis(2600),
                        java.time.Duration.ofMillis(700))));
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "ui.toast.challenge_complete", 1.0f, 1.0f);
        // El tag en negrita a proposito: es una marca que se gana (sale asi en el chat y en
        // /tags), la unica excepcion a "negrita solo en la marca" junto a Calamity.
        plugin.getServer().broadcast(Paleta.mensaje(Component.text(p.getName(), Paleta.DETALLE)
                .append(Component.text(" lleva " + horas + " en Calamity y se ha ganado ", Paleta.TEXTO))
                .append(Component.text(nombre, Paleta.MARCA, TextDecoration.BOLD))
                .append(Component.text(".", Paleta.TEXTO))));
        plugin.getLogger().info("[Calamity] Tag entregado a " + p.getName() + ".");
    }

    /**
     * De noche el bosque se cierra.
     *
     * Un bioma de Lethal Biomes se pinta sobre una ZONA, y Calamity es infinito: no hay
     * forma de repintar el mundo entero. Asi que la niebla se hace por jugador, con el
     * efecto de oscuridad, que es lo que de verdad cierra la vista en vanilla.
     */
    private void nieblaDeNoche(Player p) {
        if (!cfg().getBoolean("dificultad.niebla-de-noche", true)) return;
        // esNoche y no la hora: un Eclipse de dia tambien trae la ceniza y las rachas.
        if (!esNoche(p)) return;

        // 1.4: el equipo (niebla) quita parte de la ceniza y de la oscuridad; sin equipo, 0.
        double menosNiebla = delEquipo(p, Equipo.Efecto.NIEBLA);

        // La niebla: ceniza densa alrededor. Esto es lo que se ve SIEMPRE de noche,
        // y no quita visibilidad: cierra el aire, que es lo que se buscaba.
        int ceniza = Equipo.particulasNiebla(14, menosNiebla);
        if (ceniza > 0) Compat.spawn(p.getWorld(), Compat.ASH, p.getEyeLocation(), ceniza, 4.0, 3.0, 4.0, 0.004);

        /* La oscuridad va a RACHAS, no continua.
         *
         * El efecto DARKNESS de vanilla no es niebla: es el apagon del warden, y
         * puesto todo el rato deja la pantalla negra y el mundo injugable (Dosa lo
         * probo y no veia nada). Asi que se usa como lo que funciona: un golpe corto
         * cada tanto, "la niebla se cierra un momento". En 0 no hay oscuridad
         * ninguna y la noche queda solo con la ceniza. */
        int cada = cfg().getInt("dificultad.niebla-oscuridad-cada-segundos", 45);
        int dura = cfg().getInt("dificultad.niebla-oscuridad-segundos", 3);
        if (cada <= 0 || dura <= 0) return;
        if (cordura.estado(p).segundosDentro % cada != 0) return;
        int ticks = Equipo.ticksOscuridad(dura, menosNiebla);
        if (ticks <= 0) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, ticks, 0, true, false, false));
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "ambient.cave", 0.7f, 0.5f);
    }

    /**
     * Los minijefes no sueltan a su presa: la siguen aunque cambie de bioma, y solo se
     * acaba cuando cae uno de los dos. Sin esto bastaba con andar veinte bloques.
     *
     * Calamity 1.10: tampoco la sueltan porque se meta en la zona spawn. Hasta la 1.9 se retiraban en
     * humo en cuanto ella entraba, y la plaza era la forma de quitarse un minijefe de encima sin pelear
     * (Dosa: "que esto no pase con minijefes"). Ahora la esperan fuera; el paso de cada segundo lo
     * decide ZonaSpawn.espera:
     *  - mientras ella esta dentro no buscan pelea: solo pueden apuntar a quien les pego desde fuera hace
     *    menos de ZonaSpawn.REPRESALIA_MS (onApuntarAlQueEspera); cualquier otro objetivo, ella al
     *    entrar incluida, se les quita. Y no les hace dano lo que no trae un ser vivo detras, ni arden
     *    (onDanoEnEspera);
     *  - se quedan cerca del sitio por donde ella saldria (ZonaSpawn.acercarAlBorde), nunca junto a
     *    ella: distancia-maxima no cuenta mientras esperan;
     *  - si ella sale, vuelven por ella con lo de siempre; si pasan espera-segundos seguidos, se van sin
     *    dejar nada; y si ella se desconecta o se va de Calamity, tambien: suelto junto a la plaza seria
     *    un jefe de guardia para el siguiente que saliera (antes ni habria llegado a estar ahi).
     * Desde dentro no se les puede pegar (ZonaSpawn.onDanoDesdeDentro). Con spawn.minijefes.esperan en
     * false, como hasta la 1.9.
     */
    private void vigilarPresas() {
        ZonaSpawn z = zona;
        long ahora = System.currentTimeMillis();
        for (UUID idMob : new ArrayList<>(presas.keySet())) {
            org.bukkit.entity.Entity e = plugin.getServer().getEntity(idMob);
            if (!(e instanceof org.bukkit.entity.Mob mob) || !mob.isValid()) {
                soltarPresa(idMob);
                continue;
            }
            UUID idPresa = presas.get(idMob);
            Player presa = plugin.getServer().getPlayer(idPresa);
            if (presa == null || !presa.isOnline() || !esHardcore(presa)) {
                // 1.10: el que la esperaba en el borde ya no espera a nadie (ver arriba).
                if (esperando.containsKey(idMob)) {
                    retirarMinijefe(idMob, mob, "spawn", "minijefe-se-va",
                            presa == null ? idPresa.toString() : presa.getName(), "sin presa");
                } else {
                    soltarPresa(idMob);
                }
                continue;
            }
            Long desde = esperando.get(idMob);
            ZonaSpawn.Espera paso = ZonaSpawn.espera(enSpawn(presa), desde != null, z != null && z.minijefesEsperan(),
                    desde == null ? 0 : ahora - desde, z == null ? 0 : z.esperaSegundos());
            switch (paso) {
                case SE_RETIRA -> {
                    // 1.2: a la zona spawn no le sigue: se retira en humo, como ante la PARCA (P-10).
                    // 1.10: solo con spawn.minijefes.esperan en false.
                    retirarMinijefe(idMob, mob, "spawn", "minijefe-retirado", presa.getName());
                    continue;
                }
                case EMPIEZA, ESPERA -> {
                    if (paso == ZonaSpawn.Espera.EMPIEZA) {
                        esperando.put(idMob, ahora);
                        cordura.destello(presa, Component.text().append(nombreMinijefe(mob))
                                .append(Component.text(" te espera fuera del spawn.", Paleta.TEXTO)).build(), 4);
                        plugin.bitacora().anotar("spawn", "minijefe-espera", presa.getName());
                    }
                    // Revision 1.10: no busca pelea. Solo conserva el objetivo si es una represalia; a
                    // cualquier otro (ella al entrar, quien pase por la puerta) se le suelta.
                    LivingEntity objetivo = mob.getTarget();
                    if (objetivo != null && !puedeApuntar(idMob, objetivo, ahora)) mob.setTarget(null);
                    podarRepresalias(idMob, ahora);
                    if (mob.getFireTicks() > 0) mob.setFireTicks(0);
                    seguro("zona-spawn", () -> z.acercarAlBorde(mob, presa));
                    continue;
                }
                case SE_CANSA -> {
                    Component nombre = nombreMinijefe(mob);
                    retirarMinijefe(idMob, mob, "spawn", "minijefe-se-va", presa.getName(),
                            "espera " + (ahora - desde) / 1000 + " s");
                    presa.sendMessage(ComandoCalamity.mensaje(Component.text().append(nombre)
                            .append(Component.text(" se ha cansado de esperar y se ha ido.")).build()));
                    continue;
                }
                case VUELVE -> {
                    esperando.remove(idMob);
                    represalias.remove(idMob);
                    // Ley 6: si la PARCA la esperaba fuera y ha vuelto antes que este segundo, el minijefe se
                    // va. Parca.retirarMinijefes solo ve a los que la tienen de objetivo, y este no la tenia.
                    if (valor("parca", () -> parca.persigue(presa), false)) {
                        retirarMinijefe(idMob, mob, "spawn", "minijefe-se-va", presa.getName(), "parca");
                        continue;
                    }
                    cordura.destello(presa, Component.text().append(nombreMinijefe(mob))
                            .append(Component.text(" vuelve por ti.", Paleta.AVISO)).build(), 3);
                }
                case SIGUE -> {
                    // Lo de siempre, aqui debajo.
                }
            }
            mob.setTarget(presa);
            // Si se aleja demasiado, el minijefe reaparece cerca: no se le escapa.
            double lejos = cfg().getDouble("minijefes.distancia-maxima", 60);
            if (mob.getWorld() == presa.getWorld()
                    && mob.getLocation().distanceSquared(presa.getLocation()) > lejos * lejos) {
                mob.teleport(presa.getLocation().add(
                        (random.nextDouble() - 0.5) * 16, 0, (random.nextDouble() - 0.5) * 16));
                Compat.spawn(mob.getWorld(), Compat.SMOKE, mob.getLocation(), 20, 0.5, 1.0, 0.5, 0.02);
            }
        }
    }

    /**
     * Apunta la muerte para la cuarentena de reentrada. Tambien la llama Combate.cable: huir
     * por el cable cuenta como morir, y sin esto se podia volver a entrar al momento.
     */
    void marcarMuerto(UUID u) {
        muertos.put(u, System.currentTimeMillis());
    }

    /** Apunta que ese minijefe viene por ese jugador y no lo suelta. */
    public void marcarPresa(org.bukkit.entity.Entity minijefe, Player presa) {
        presas.put(minijefe.getUniqueId(), presa.getUniqueId());
    }

    /**
     * Calamity 1.10 · La presa de ese minijefe (marcarPresa) si esta conectada, o null. Solo lectura:
     * la usa ZonaSpawn.sacar para sacarlo por el lado de ella.
     */
    Player presaDe(UUID idMob) {
        UUID id = presas.get(idMob);
        return id == null ? null : plugin.getServer().getPlayer(id);
    }

    /**
     * Calamity 1.10 · Olvida a ese minijefe: ni a quien sigue, ni si la estaba esperando, ni a quien le
     * pego mientras. El reloj de sin presa (sinPresa) no: lo lleva justo el que se queda sin presa, y lo
     * poda vigilarSinPresa.
     */
    private void soltarPresa(UUID idMob) {
        presas.remove(idMob);
        esperando.remove(idMob);
        represalias.remove(idMob);
    }

    /**
     * Calamity 1.10 · Un minijefe que se va (de cordura cero o sin presa en el borde): en humo y sin
     * muerte (no paga ni suelta nada), olvidado del todo y con su linea en la Bitacora.
     */
    private void retirarMinijefe(UUID idMob, org.bukkit.entity.Mob mob, String... bitacora) {
        soltarPresa(idMob);
        sinPresa.remove(idMob);
        Compat.spawn(mob.getWorld(), Compat.LARGE_SMOKE, mob.getLocation().add(0, 1, 0), 30, 0.5, 1, 0.5, 0.02);
        mob.remove();
        plugin.bitacora().anotar(bitacora);
    }

    /**
     * Revision 1.10 · Si el minijefe que espera puede apuntar a ese objetivo: solo como represalia contra
     * quien le pego desde fuera hace poco (ZonaSpawn.represalia).
     */
    private boolean puedeApuntar(UUID idMob, Entity objetivo, long ahora) {
        Map<UUID, Long> golpes = represalias.get(idMob);
        return ZonaSpawn.represalia(golpes == null ? null : golpes.get(objetivo.getUniqueId()), ahora,
                enSpawn(objetivo.getLocation()));
    }

    /** Los golpes que ya no dan derecho a represalia se olvidan (una vez por segundo, desde vigilarPresas). */
    private void podarRepresalias(UUID idMob, long ahora) {
        Map<UUID, Long> golpes = represalias.get(idMob);
        if (golpes == null) return;
        golpes.values().removeIf(t -> ahora - t > ZonaSpawn.REPRESALIA_MS);
        if (golpes.isEmpty()) represalias.remove(idMob);
    }

    /**
     * Revision 1.10 · Lo llama ZonaSpawn.sacar con cada minijefe que saca al borde: si no tiene presa viva
     * (mato a su presa, ella se fue sin que el la esperase, o es de guarnicion), desde la primera vez lleva
     * el reloj de espera (vigilarSinPresa). Las siguientes no lo reinician.
     */
    void sacadoAlBorde(Entity mob) {
        if (mob == null || presaViva(mob.getUniqueId()) != null) return;
        sinPresa.putIfAbsent(mob.getUniqueId(), System.currentTimeMillis());
    }

    /** La presa de ese minijefe si esta viva y en Calamity (conectada, sin morir y en un mundo hardcore), o null. */
    private Player presaViva(UUID idMob) {
        Player p = presaDe(idMob);
        return p != null && p.isOnline() && !p.isDead() && esHardcore(p) ? p : null;
    }

    /**
     * Revision 1.10 · Una vez por segundo, los minijefes sin presa que se sacaron al borde (sacadoAlBorde).
     * Si cumplen spawn.minijefes.espera-segundos pegados a la zona se van en humo sin dejar nada, como el
     * que se cansa de esperar; si vuelven a tener presa viva o se alejan de la franja
     * (ZonaSpawn.FRANJA_SIN_PRESA), el reloj se olvida. Sin esto, el que mataba a su presa junto a la plaza
     * (o uno de guarnicion que se colaba) se quedaba de guardia en la puerta mientras el chunk siguiera
     * cargado: los esbirros de EDM no se despawnean solos. Mientras tanto no arde (onDanoEnEspera).
     */
    private void vigilarSinPresa() {
        if (sinPresa.isEmpty()) return;
        ZonaSpawn z = zona;
        long ahora = System.currentTimeMillis();
        int limite = z == null ? 0 : z.esperaSegundos();
        for (UUID idMob : new ArrayList<>(sinPresa.keySet())) {
            Entity e = plugin.getServer().getEntity(idMob);
            ZonaSpawn.Zona caja = z == null || e == null ? null : z.de(e.getWorld());
            if (!(e instanceof org.bukkit.entity.Mob mob) || !mob.isValid() || caja == null) {
                sinPresa.remove(idMob);
                continue;
            }
            Location l = mob.getLocation();
            long desde = sinPresa.get(idMob);
            switch (ZonaSpawn.sinPresa(presaViva(idMob) != null, ZonaSpawn.alejado(caja, l.getX(), l.getZ()),
                    ahora - desde, limite)) {
                case OLVIDA -> sinPresa.remove(idMob);
                case SE_VA -> retirarMinijefe(idMob, mob, "spawn", "minijefe-se-va", mob.getType().getKey().getKey(),
                        "sin presa", "espera " + (ahora - desde) / 1000 + " s",
                        l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
                case SIGUE -> {
                    if (mob.getFireTicks() > 0) mob.setFireTicks(0);
                }
            }
        }
    }

    /**
     * Revision 1.10 · El minijefe que espera a su presa no busca pelea (vigilarPresas): no se le deja
     * apuntar a nadie salvo, como represalia, a quien le pego desde fuera hace menos de
     * ZonaSpawn.REPRESALIA_MS. Sin esto atacaba a cualquiera que saliera por la puerta: espera-segundos de
     * jefe de guardia, y con el Reclamo a voluntad. Quien le pego puede ser un jugador o lo que le ataca
     * por el (un lobo, un golem): sin represalia contra ellos, un lobo lo mataria sin respuesta y su dueno
     * cobraria como asesino sin riesgo. EntityTargetEvent recibe tambien EntityTargetLivingEntityEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onApuntarAlQueEspera(org.bukkit.event.entity.EntityTargetEvent e) {
        if (esperando.isEmpty() || e.getTarget() == null || !esperando.containsKey(e.getEntity().getUniqueId())) return;
        if (!puedeApuntar(e.getEntity().getUniqueId(), e.getTarget(), System.currentTimeMillis())) e.setCancelled(true);
    }

    /** Revision 1.10 · Quien le pega de verdad desde fuera al que espera (MONITOR: el golpe ha entrado): su represalia. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGolpeAlQueEspera(EntityDamageEvent e) {
        if (esperando.isEmpty() || !esperando.containsKey(e.getEntity().getUniqueId())) return;
        LivingEntity quien = ZonaSpawn.causanteVivo(e);
        if (quien == null || quien.equals(e.getEntity()) || enSpawn(quien.getLocation())) return;
        represalias.computeIfAbsent(e.getEntity().getUniqueId(), k -> new HashMap<>())
                .put(quien.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * Revision 1.10 · Al minijefe que espera a su presa, y al sin presa del borde, no se le hace el dano que
     * no trae un ser vivo detras (ZonaSpawn.bloqueaDanoEnEspera: lava, fuego, caidas, asfixia, cactus, un
     * dispensador, TNT sin dueno), y si arde se le apaga. Por que: desde dentro de la zona no se le puede
     * pegar, pero si poner lava o fuego (o activar un dispensador) en un bloque de fuera, y quien ya le
     * habia hecho su parte fuera lo remataba sin ningun riesgo y cobraba el reparto.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDanoEnEspera(EntityDamageEvent e) {
        if (esperando.isEmpty() && sinPresa.isEmpty()) return;
        UUID id = e.getEntity().getUniqueId();
        if (!esperando.containsKey(id) && !sinPresa.containsKey(id)) return;
        if (!ZonaSpawn.bloqueaDanoEnEspera(e.getCause(), ZonaSpawn.causanteVivo(e) != null)) return;
        e.setCancelled(true);
        if (e.getEntity().getFireTicks() > 0) e.getEntity().setFireTicks(0);
    }

    /** Calamity 1.10 · El nombre de un minijefe en los avisos, el mismo de su cartel (MobsLethal.nombreMinijefe). */
    private Component nombreMinijefe(LivingEntity mob) {
        Component porDefecto = Paleta.minijefe(null, 0);
        return plugin.mobs() == null ? porDefecto : valor("minijefes", () -> plugin.mobs().nombreMinijefe(mob), porDefecto);
    }

    /** Los mobs de este mundo pueden recoger lo que se cae al suelo. */
    @EventHandler(ignoreCancelled = true)
    public void onAparecer(CreatureSpawnEvent e) {
        if (!esHardcore(e.getEntity().getWorld())) return;
        // Las amenazas llevan la marca desde el consumer del spawn y no recogen nada: un Eco
        // que se equipa lo del suelo cambiaria sus numeros. Esto corre DESPUES del consumer.
        if (Marcas.esAmenaza(e.getEntity())) return;
        if (!cfg().getBoolean("dificultad.mobs-recogen", true)) return;
        e.getEntity().setCanPickupItems(true);
    }

    /** Hambre al doble: comer deja de ser un tramite. */
    @EventHandler(ignoreCancelled = true)
    public void onHambre(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p) || !esHardcore(p)) return;
        double factor = cfg().getDouble("dificultad.hambre", 2.0);
        if (factor <= 1) return;
        int antes = p.getFoodLevel();
        if (e.getFoodLevel() >= antes) return;
        // Solo se dobla lo que se PIERDE; comer sigue dando lo que da. El equipo (1.4, hambre) quita parte
        // del extra; sin equipo, Equipo.hambre es la cuenta de siempre.
        double menos = delEquipo(p, Equipo.Efecto.HAMBRE);
        e.setFoodLevel(Equipo.hambre(antes, e.getFoodLevel(), factor, menos,
                menos == 0 || equipo == null ? null : equipo.restoHambre(p)));
    }

    /** La comida cruda sienta peor aqui: veneno y hambre encima. */
    @EventHandler(ignoreCancelled = true)
    public void onComer(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (!esHardcore(p)) return;
        int segundos = cfg().getInt("dificultad.veneno-comida-cruda", 8);
        if (segundos <= 0) return;
        boolean cruda = switch (e.getItem().getType()) {
            case CHICKEN, BEEF, PORKCHOP, MUTTON, RABBIT, COD, SALMON, ROTTEN_FLESH -> true;
            default -> false;
        };
        if (!cruda) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, segundos * 20, 1, true, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, segundos * 20, 1, true, false, true));
        p.sendMessage(Component.text("Eso estaba crudo y te ha sentado mal.", Paleta.AVISO));
    }

    /**
     * Caidas y ahogos al doble.
     *
     * Va aparte de onGolpe porque esas dos no vienen de ninguna entidad: son del
     * entorno, y el entorno tambien mata aqui.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntorno(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !esHardcore(p)) return;
        double factor = switch (e.getCause()) {
            // 1.4: el equipo (caidas) quita parte del extra de las caidas; el ahogo no lo toca.
            case FALL -> Equipo.castigo(cfg().getDouble("dificultad.dano-caida", 2.0), delEquipo(p, Equipo.Efecto.CAIDAS));
            case DROWNING -> cfg().getDouble("dificultad.dano-ahogo", 2.0);
            default -> 1;
        };
        if (factor > 1) e.setDamage(e.getDamage() * factor);
    }

    /** El equipo se gasta al doble: alli nada dura. */
    @EventHandler(ignoreCancelled = true)
    public void onDurabilidad(PlayerItemDamageEvent e) {
        if (!esHardcore(e.getPlayer())) return;
        double factor = cfg().getDouble("dificultad.durabilidad", 2.0);
        if (factor <= 1) return;
        // 1.4: el equipo (durabilidad) quita parte del extra; sin equipo, Equipo.durabilidad es el ceil de siempre.
        Player p = e.getPlayer();
        double menos = delEquipo(p, Equipo.Efecto.DURABILIDAD);
        e.setDamage(Equipo.durabilidad(e.getDamage(), factor, menos,
                menos == 0 || equipo == null ? null : equipo.restoDurabilidad(p)));
    }

    /**
     * El totem no salva: se gasta igual y te mueres.
     *
     * Es deliberadamente cruel, y por eso se avisa por chat: si desapareciera sin
     * decir nada pareceria un fallo del servidor.
     */
    @EventHandler(ignoreCancelled = true)
    public void onTotem(EntityResurrectEvent e) {
        if (!(e.getEntity() instanceof Player p) || !esHardcore(p)) return;
        if (!cfg().getBoolean("dificultad.sin-totem", true)) return;
        e.setCancelled(true);
        p.sendMessage(Component.text("El tótem se deshace sin salvarte.", Paleta.AVISO));
        Compat.spawn(p.getWorld(), Compat.ASH, p.getLocation().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0.03);
    }

    /** Aqui los mobs SI recogen lo que se te cae, y se lo quedan. */
    @EventHandler(ignoreCancelled = true)
    public void onRecoger(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player) return;
        if (!esHardcore(e.getEntity().getWorld())) return;
        LivingEntity mob = e.getEntity();
        /* Reliquias, Esencias y copias del Eco no las coge ningun mob, este o no la regla
         * de recoger: el mob que se las lleva las destruye al despawnear, y la copia visual
         * de un Eco no puede acabar en ningun inventario. Las amenazas tampoco cogen nada. */
        ItemStack cogido = e.getItem().getItemStack();
        if (Marcas.esAmenaza(mob) || Marcas.tiene(cogido, Marcas.ECO_COPIA) || items.esEsencia(cogido)
                || valor("reliquias", () -> reliquias.es(cogido), false)) {
            e.setCancelled(true);
            return;
        }
        if (!cfg().getBoolean("dificultad.mobs-recogen", true)) return;

        // Ni los jefes de las anomalias ni su tropa: el permiso de recoger se da al
        // aparecer, cuando todavia no llevan su marca, asi que se les niega aqui. Un
        // jefe que se equipa la espada que se le cayo a alguien pega y se ve distinto.
        if (net.ederus.edm.comun.Tags.isOurs(mob)) {
            e.setCancelled(true);
            return;
        }

        /* Vanilla vuelve PERSISTENTE al mob que recoge algo, y un persistente no
         * despawnea nunca: con esta regla puesta, cada zombi que pisaba carne podrida
         * se quedaba en el mundo para siempre y Calamity se iba llenando con las horas.
         * La marca la pone el juego DESPUES de este evento, por eso se deshace un tick
         * mas tarde, y solo a los que antes si podian despawnear. */
        if (mob.getRemoveWhenFarAway()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (mob.isValid()) mob.setRemoveWhenFarAway(true);
            });
        }
        Compat.spawn(mob.getWorld(), Compat.ANGRY_VILLAGER,
                mob.getLocation().add(0, 1.4, 0), 3, 0.2, 0.2, 0.2, 0);
    }

    /** Los cofres de las estructuras salen VACIOS: el botin se mata, no se encuentra. */
    @EventHandler(ignoreCancelled = true)
    public void onBotinDeCofre(LootGenerateEvent e) {
        if (!esHardcore(e.getWorld())) return;
        if (cfg().getBoolean("dificultad.cofres-vacios", true)) {
            e.setLoot(java.util.Collections.emptyList());
            return;
        }
        seguro("cofres", () -> cofres.alGenerar(e));
    }

    /** Fuego amigo: aqui os podeis matar entre vosotros. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFuegoAmigo(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victima) || !esHardcore(victima)) return;
        if (!cfg().getBoolean("dificultad.fuego-amigo", true)) return;
        Entity quien = autor(e.getDamager());
        // Solo se devuelve el golpe de OTRO jugador: lo demas que lo decidan las
        // protecciones normales del servidor.
        if (quien instanceof Player agresor && !agresor.equals(victima)) {
            // 1.2: en la zona spawn Calamity no descancela nada: manda la proteccion del servidor
            // (la region de WorldGuard del spawn niega el PvP).
            if (enSpawn(victima) || enSpawn(agresor)) return;
            if (e.isCancelled()) e.setCancelled(false);
            // Llegada protegida, Frenesi, Sangre fresca y Eclipse: despues de descancelar,
            // para que puedan volver a cancelar o cambiar el dano con la ultima palabra.
            seguro("combate", () -> combate.alPvp(e));
        }
    }

    // ---------------------------------------------------------------------- muerte

    /**
     * Morir aqui cuesta todo: el inventario y la experiencia se BORRAN (no caen al
     * suelo, para que no haya carrera de vuelta al cadaver) y se sale del mundo.
     *
     * Las tumbas de AxGraves hay que apagarlas por su config (disabled-worlds); esto
     * vacia la lista de drops igualmente, asi que aunque la tumba se cree sale vacia.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMuerte(PlayerDeathEvent e) {
        // Una muerte que otro plugin cancelo no es una muerte: ni parte, ni foto, ni Eco.
        if (e.isCancelled()) return;
        Player p = e.getEntity();
        if (!esHardcore(p)) return;
        boolean pierde = cfg().getBoolean("muerte.lo-pierde-todo", true);

        /* Todo lo que tiene que ver el equipo o la cordura va ANTES de borrar el inventario
         * y de reiniciar la cordura (MT sec. 4): es el unico momento en que aun estan. Cada
         * gancho va en seguro(): si uno falla, el inventario se borra igual, que un fallo
         * que deja salir al muerto con todo es un duplicador. */
        seguro("parte", () -> parte.cerrar(e));
        FotoMuerte foto = pierde ? valor("eco", () -> FotoMuerte.de(p, this), null) : null;
        if (pierde) seguro("objetos", () -> objetos.alMorir(p, e));
        seguro("parca", () -> parca.alMorirPresa(p));
        seguro("testigos", () -> testigos.alMorir(p));
        seguro("racha", () -> racha.alMorir(p));
        seguro("telemetria", () -> telemetria.muere(p, foto));
        seguro("estadisticas", () -> estadisticas.sumar(p.getUniqueId(), "muertes", 1));
        anotarMuerte(p, e);
        if (!pierde) return;

        e.getDrops().clear();
        e.setDroppedExp(0);
        e.setKeepInventory(false);
        e.setKeepLevel(false);
        e.setNewExp(0);
        e.setNewLevel(0);
        e.setNewTotalExp(0);
        p.getInventory().clear();

        cordura.reiniciar(p);
        marcarMuerto(p.getUniqueId());
        // Quien cae ante un Eco lo dice (P-E12); cualquier otra muerte, la frase de siempre.
        Component deEco = ecos == null ? null : valor("eco", () -> ecos.mensajeMuerte(p), null);
        e.deathMessage(deEco != null ? deEco : Component.text(p.getName() + " no volvió de Calamity.",
                Paleta.AVISO));

        // A donde reaparece se decide en onReaparecer, que es cuando vuelve a tener
        // cuerpo: dos ticks despues de morir sigue en la pantalla de muerte, y a un
        // muerto no se le puede teletransportar.
        porReaparecer.add(p.getUniqueId());

        // El Eco nace de la foto, ya con el inventario borrado: lo que lleva es copia.
        if (foto != null) seguro("eco", () -> ecos.programar(foto));
    }

    /** La muerte a la Bitacora: es la unica respuesta a "me han robado". */
    private void anotarMuerte(Player p, PlayerDeathEvent e) {
        try {
            Location l = p.getLocation();
            String causa;
            try {
                causa = e.getDamageSource().getDamageType().getKey().getKey();
            } catch (Throwable sinTipo) {
                causa = "?";
            }
            Entity quien = e.getDamageSource().getCausingEntity();
            plugin.bitacora().anotar("muerte", p.getName(),
                    l.getWorld().getKey().getKey() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(),
                    causa + (quien == null ? "" : " por " + (quien instanceof Player j ? j.getName()
                            : quien.getType().getKey().getKey())),
                    "cordura " + Math.round(cordura.valor(p)),
                    "dentro " + cordura.estado(p).segundosDentro + " s");
        } catch (Throwable t) {
            avisarFallo("bitacora", t);
        }
    }

    /**
     * Quien murio dentro no reaparece dentro.
     *
     * Casi siempre el servidor ya lo manda fuera (sin cama, al spawn del mundo
     * principal), pero con "sin-camas" apagada desde el panel alguien puede tener la
     * cama en Calamity, y entonces morir era volver a aparecer alli con las manos
     * vacias. Si el punto de reaparicion cae en un mundo hardcore, se cambia por la
     * salida. Va en HIGHEST para tener la ultima palabra sobre otros plugins de spawn.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onReaparecer(org.bukkit.event.player.PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!porReaparecer.remove(p.getUniqueId())) return;
        if (esHardcore(e.getRespawnLocation().getWorld())) {
            Location fuera = salida();
            if (fuera != null) e.setRespawnLocation(fuera);
        }
        // Solo llega aqui quien lo ha perdido todo (onMuerte no lo apunta con lo-pierde-todo apagado).
        p.sendMessage(Component.text("Has muerto en Calamity y has perdido lo que llevabas.", Paleta.TEXTO));
        // Un tick despues ya tiene cuerpo: mensaje del Eco y lo que devuelva el Salvoconducto.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !activo()) return;
            seguro("eco", () -> ecos.alReaparecer(p));
            seguro("objetos", () -> objetos.alReaparecer(p));
        }, 1L);
    }

    // --------------------------------------------------------------- entrar/salir

    /** El punto de vuelta: lo que diga la config, o el spawn del mundo principal. */
    public Location salida() {
        Location guardado = punto("salida");
        if (guardado != null) return guardado;
        List<World> mundos = plugin.getServer().getWorlds();
        return mundos.isEmpty() ? null : mundos.get(0).getSpawnLocation();
    }

    /**
     * Saca a un jugador del mundo hardcore y le devuelve la cordura entera.
     *
     * @param motivo     clave corta: "puerta", "cristal", "admin" (la reciben tasar y la PARCA)
     * @param extraccion true si sale VIVO por su pie (puerta o Cristal): solo entonces se
     *                   tasa lo que lleva. Los comandos de admin pasan false.
     */
    public void sacar(Player p, String motivo, boolean extraccion) {
        Location destino = salida();
        if (destino == null) return;
        if (activo()) {
            if (extraccion) {
                // Salir ya no vende nada (rama venta-oren): se lleva lo que lleva y se lo vende a Oren.
                // Antes del teleport: la primera salida del dia, contratos, telemetria y encuesta.
                seguro("tasacion", () -> tasacion.alSalir(p, motivo));
                seguro("estadisticas", () -> {
                    estadisticas.sumar(p.getUniqueId(), "extracciones", 1);
                    estadisticas.maximo(p.getUniqueId(), "expedicion-max-seg", cordura.estado(p).segundosDentro);
                });
            } else if (!"cable".equals(motivo)) {
                // Una salida sin tasar (admin) tambien cierra la expedicion en la telemetria. El
                // cable no: ya conto como "muere".
                seguro("telemetria", () -> telemetria.sale(p, motivo, Map.of()));
            }
            seguro("huella", () -> huella.reiniciar(p));
            seguro("parca", () -> parca.alSalir(p, motivo));
            seguro("kit", () -> kit.borrarPrestado(p));
            // 1.10: los pergaminos de los contratos tampoco salen (los de la Tasacion ya se rompieron al cobrarlos).
            seguro("contratos", () -> contratos.borrarPergaminos(p));
            seguro("combate", () -> combate.alSalir(p));
        }
        p.teleport(destino);
        cordura.reiniciar(p);
        // Fuera ya no hay destello que valga: se olvida y la barra se limpia (si es de Calamity).
        barra.olvidar(p);
        barra.fondo(p, Component.empty());
        String texto = switch (motivo == null ? "" : motivo) {
            case "puerta" -> "Has salido de Calamity.";
            case "cristal" -> "El Cristal de Regreso te saca de Calamity.";
            case "admin" -> "El staff te ha sacado de Calamity.";
            default -> null;
        };
        if (texto != null) {
            p.sendMessage(Component.text(texto, Paleta.TEXTO));
        }
        Compat.soundPlayers(destino.getWorld(), destino, "block.amethyst_block.resonate", 1.0f, 0.8f);
    }

    /**
     * Calamity 1.11 · /calamity extract <player>: la salida completa a mano. Es la de la puerta
     * (sacar con extraccion): Tasacion de lo que lleva (y con ella los contratos de la Tasacion, la
     * Racha y la telemetria "sale" con motivo "admin"), pergaminos y Frasco prestado fuera, la PARCA
     * le espera como a cualquiera que sale, y cordura entera. Para sacar a alguien atascado o en
     * pruebas sin que pierda lo que lleva. Si estaba canalizando un Cristal, se le apaga (no lo gasta).
     */
    private void comandoExtract(org.bukkit.command.CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(Component.text("Uso: /calamity extract <player>", Paleta.AVISO));
            return;
        }
        Player p = plugin.getServer().getPlayerExact(args[1]);
        if (p == null) {
            quien.sendMessage(Component.text("No encuentro a ese jugador conectado.", Paleta.AVISO));
            return;
        }
        if (!esHardcore(p)) {
            quien.sendMessage(Component.text(p.getName() + " no está en Calamity.", Paleta.AVISO));
            return;
        }
        if (p.isDead()) {
            quien.sendMessage(Component.text(p.getName() + " está muerto: sale al reaparecer.", Paleta.AVISO));
            return;
        }
        if (salida() == null) {
            quien.sendMessage(Component.text("No hay punto de salida: márcalo con /calamity define return.", Paleta.AVISO));
            return;
        }
        canalizando.remove(p.getUniqueId());
        cuentaCristal.remove(p.getUniqueId());
        String staff = quien instanceof Player s ? s.getName() : "consola";
        plugin.bitacora().anotar("extract", p.getName(), "por " + staff);
        sacar(p, "admin", true);
        if (esHardcore(p)) {
            // Otro plugin (uno de combate, por ejemplo) ha frenado el teleport: la Tasacion ya se hizo.
            quien.sendMessage(Component.text("Se ha procesado la salida de " + p.getName() + ", pero algo ha frenado el teleport: "
                    + "sigue en Calamity.", Paleta.AVISO));
            return;
        }
        quien.sendMessage(Component.text(p.getName() + " ha salido de Calamity con lo que llevaba.", Paleta.BIEN));
    }

    /** Mete a un jugador por la puerta: el teleport y la llegada (alLlegar). */
    public void meter(Player p, Location destino) {
        if (destino == null) return;
        // 1.10: la marca va ANTES del teleport, porque el cambio de mundo salta dentro de el
        // (onCambiarMundo, que la consume) y la llegada ya la hace esta llamada. Si el teleport no
        // cambia de mundo o falla, se quita sola al tick siguiente.
        UUID u = p.getUniqueId();
        recienMetidos.add(u);
        plugin.getServer().getScheduler().runTask(plugin, () -> recienMetidos.remove(u));
        // Si el teleport no llega a Calamity (otro plugin lo cancela, como uno de combate, o lo
        // desvia), no hay llegada: sin esto se repetia cada segundo en la puerta, fuera de Calamity
        // (aviso, oleada, "entra" en la telemetria y los pergaminos de los contratos en la mano).
        if (!p.teleport(destino) || !esHardcore(p)) {
            recienMetidos.remove(u);
            return;
        }
        alLlegar(p);
    }

    /**
     * Lo que pasa al llegar a Calamity, venga por la puerta (meter) o por cualquier otra via: un /warp,
     * un /tp o el portal de otro plugin (onCambiarMundo, Calamity 1.10). Antes solo corria con la
     * puerta, y quien entraba por /warp se quedaba sin contratos, sin huella en la Aduana y sin "entra"
     * en la telemetria. La oleada de bienvenida es una de las reglas de dificultad: el mundo no te deja
     * llegar y mirar, te recibe con algo encima.
     */
    void alLlegar(Player p) {
        Location destino = p.getLocation();
        cordura.reiniciar(p);
        p.sendMessage(Paleta.mensaje("Si mueres en Calamity, pierdes todo lo que llevas encima."));
        Compat.sound(destino.getWorld(), destino, "ambient.cave", 1.2f, 0.5f);
        if (activo()) {
            seguro("huella", () -> huella.reiniciar(p));
            seguro("combate", () -> combate.llegada(p));
            seguro("aduana", () -> aduana.alEntrar(p));
            seguro("ecos", () -> ecos.alEntrar(p));
            seguro("parca", () -> parca.alEntrar(p));
            seguro("contratos", () -> contratos.alEntrar(p));
            seguro("altar", () -> altar.alEntrar(p));
            seguro("eclipse", () -> eclipse.alEntrar(p));
            seguro("telemetria", () -> telemetria.entra(p));
            seguro("tasacion", () -> tasacion.alEntrar(p));
        }

        int oleada = cfg().getInt("dificultad.oleada-de-entrada", 5);
        if (oleada > 0 && plugin.mobs() != null) {
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> plugin.mobs().oleada(p, oleada), 60L);
        }
    }

    /**
     * Calamity 1.10 · La llegada por otra via que no es la puerta: de un mundo que no es hardcore a uno
     * que si, contando (como la puerta: ni espectadores ni creativos) y sin haber pasado por meter().
     * Quien se conecta estando dentro no cambia de mundo: sigue su expedicion (onEntrar).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCambiarMundo(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        // 1.11: fuera de Calamity la BossBar de la cordura se quita ya, sin esperar al segundo.
        if (!esHardcore(p)) cordura.ocultarPantalla(p);
        // El cambio de mundo de la puerta consume su marca: la llegada ya la hace meter().
        if (recienMetidos.remove(p.getUniqueId()) || esHardcore(e.getFrom()) || !esHardcore(p) || !cuenta(p)) return;
        alLlegar(p);
    }

    @EventHandler
    public void onEntrar(PlayerJoinEvent e) {
        // Quien se desconecto dentro vuelve con la cordura que tenia: salir por las
        // malas no puede ser la forma barata de resetear el reloj.
        Player p = e.getPlayer();
        // Cada modulo mira el mundo por su cuenta: el aviso de un Eco vivo tambien le
        // interesa a quien entra al servidor fuera de Calamity.
        seguro("huella", () -> huella.restaurar(p));
        seguro("parca", () -> parca.alVolver(p));
        seguro("ecos", () -> ecos.alVolver(p));
        if (!esHardcore(p)) return;
        double guardada = datos.getDouble("guardado." + p.getUniqueId(), -1);
        if (guardada >= 0) cordura.valor(p, guardada);
    }

    @EventHandler
    public void onSalir(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        // El combat log lo primero: todavia esta en el mundo y con su inventario.
        seguro("combate", () -> combate.alDesconectar(e));
        seguro("huella", () -> huella.aparcar(p));
        seguro("parca", () -> parca.alDesconectar(p));
        ultimoSuelo.remove(p.getUniqueId());
        canalizando.remove(p.getUniqueId());
        cuentaCristal.remove(p.getUniqueId());
        ultimoEfecto.remove(p.getUniqueId());
        ultimoUso.remove(p.getUniqueId());
        String ruta = "guardado." + p.getUniqueId();
        if (esHardcore(p) && cordura.conoce(p)) {
            datos.set(ruta, cordura.valor(p));
            datosSucios = true;
            guardarDatos();
        } else if (datos.isSet(ruta)) {
            datos.set(ruta, null);
            datosSucios = true;
        }
        // porReaparecer NO se toca: quien se desconecta en la pantalla de muerte
        // reaparece al volver, y ahi sigue haciendo falta saber que murio dentro.
        cordura.olvidar(p);
    }

    /**
     * Dentro no valen los atajos: ni /spawn, ni /home, ni /tpa. Se sale por el portal
     * o con el cristal, que es lo que hace que el mundo de miedo.
     */
    @EventHandler(ignoreCancelled = true)
    public void onComando(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!esHardcore(p) || p.hasPermission("ederus.mundos")) return;
        String cmd = e.getMessage().toLowerCase(Locale.ROOT).split(" ")[0];
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
        if (!cfg().getStringList("comandos-prohibidos").contains(cmd)) return;
        // Rama venta-oren: el cofre ender y los vaults los lleva Sellos, que deja pasar al staff con su permiso.
        String raiz = cmd;
        if (sellos != null && Sellos.exento(p) && valor("sellos", () -> sellos.comandoDeAlmacen(raiz), false)) return;
        e.setCancelled(true);
        p.sendMessage(Component.text("En Calamity no puedes usar ese comando. Para salir, usa la puerta de salida o un Cristal de Regreso.",
                Paleta.AVISO));
    }

    // ----------------------------------------------------------------- los objetos

    /**
     * Calamity 1.11 · Sin ignoreCancelled: Paper entrega el clic al aire (RIGHT_CLICK_AIR) ya marcado
     * como cancelado, porque no hay bloque que usar (useInteractedBlock = DENY), y con ignoreCancelled
     * el Frasco y el Cristal solo respondian apuntando a un bloque. Bedrock manda SIEMPRE el uso de un
     * objeto como clic al aire. Lo que cuenta es si alguien ha negado el uso del OBJETO (una proteccion
     * que cancela el clic entero lo niega tambien): solo entonces no se hace nada.
     *
     * Un uso por tick y jugador: si llegan el clic al bloque y el del aire en el mismo tick (Paper ya
     * los junta, pero por si un cliente los manda sueltos), solo cuenta el primero.
     */
    @EventHandler
    public void onUsar(PlayerInteractEvent e) {
        if (e.useItemInHand() == org.bukkit.event.Event.Result.DENY) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!e.getAction().isRightClick()) return;
        Player p = e.getPlayer();
        ItemStack mano = e.getItem();
        if (mano == null || (!items.esFrasco(mano) && !items.esCristal(mano))) return;
        int tick = plugin.getServer().getCurrentTick();
        Integer antes = ultimoUso.put(p.getUniqueId(), tick);
        if (antes != null && antes == tick) {
            e.setCancelled(true);
            return;
        }

        if (items.esFrasco(mano)) {
            e.setCancelled(true);
            beber(p, mano);
            return;
        }
        if (items.esCristal(mano)) {
            e.setCancelled(true);
            empezarCristal(p);
        }
    }

    /** Un trago del frasco: sube la cordura y gasta un uso. Al quedarse a cero, botella vacia. */
    private void beber(Player p, ItemStack frasco) {
        if (!esHardcore(p)) {
            p.sendMessage(Component.text("El Frasco de Calma solo funciona en Calamity.", Paleta.TEXTO));
            return;
        }
        int quedan = items.tragos(frasco);
        if (quedan <= 0) {
            p.sendMessage(Component.text("El Frasco de Calma está vacío. Recárgalo en el Altar.",
                    Paleta.TEXTO));
            Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.glass.break", 0.6f, 1.4f);
            return;
        }
        double sube = cfg().getDouble("frasco.cordura", 40);
        cordura.sumar(p, sube);
        cordura.estado(p).ultimoTramo = Cordura.tramo(cordura.valor(p));

        // El mismo frasco con un trago menos: si era prestado o estaba ligado, lo sigue estando.
        ItemStack nuevo = items.conTragos(frasco, quedan - 1);
        if (frasco.getAmount() > 1) {
            frasco.setAmount(frasco.getAmount() - 1);
            for (ItemStack sobra : p.getInventory().addItem(nuevo).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), sobra);
            }
        } else {
            p.getInventory().setItemInMainHand(nuevo);
        }
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "entity.generic.drink", 1.0f, 1.1f);
        Compat.spawn(p.getWorld(), Compat.HEART, p.getLocation().add(0, 1, 0), 20,
                0.4, 0.6, 0.4, 0.02);
        cordura.destello(p, Component.text("+" + (int) sube + " de cordura", ItemsCalamity.VERDE), 2);
    }

    /** El cristal no es instantaneo: hay que aguantar quieto, y un golpe lo corta. */
    private void empezarCristal(Player p) {
        if (!esHardcore(p)) {
            p.sendMessage(Component.text("El Cristal de Regreso solo funciona en Calamity.",
                    Paleta.TEXTO));
            return;
        }
        if (canalizando.containsKey(p.getUniqueId())) return;
        // P-C01: con la etiqueta de combate no se empieza. Si no, el Cristal era la forma
        // de huir de cualquier pelea a cinco segundos.
        if (valor("combate", () -> combate.enCombate(p), false)) {
            cordura.destello(p, Component.text("En combate no puedes usar el Cristal.", Paleta.AVISO), 2);
            return;
        }
        canalizando.put(p.getUniqueId(), p.getLocation().clone());
        p.sendMessage(Component.text("El Cristal empieza a resonar. No te muevas.",
                ItemsCalamity.MORADO));
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.amethyst_block.chime", 1.0f, 0.7f);
    }

    /** Un tick por segundo del cristal: comprueba que siga quieto y, al final, lo saca. */
    private void vigilarCanalizacion(Player p) {
        Location inicio = canalizando.get(p.getUniqueId());
        if (inicio == null) return;

        if (inicio.getWorld() != p.getWorld() || inicio.distanceSquared(p.getLocation()) > 4) {
            canalizando.remove(p.getUniqueId());
            cuentaCristal.remove(p.getUniqueId());
            p.sendMessage(Component.text("Te has movido y el Cristal se ha apagado.", Paleta.AVISO));
            Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.amethyst_block.break", 0.8f, 0.8f);
            return;
        }
        Compat.spawn(p.getWorld(), Compat.WITCH, p.getLocation().add(0, 1, 0), 12, 0.3, 0.6, 0.3, 0.01);

        int def = cfg().getInt("cristal.segundos", 5);
        // Con la PARCA cerca el Cristal tarda mas (cristal-segundos-marcado).
        int segundos = valor("parca", () -> parca.segundosCristal(p, def), def);
        int llevados = cuentaCristal.merge(p.getUniqueId(), 1, Integer::sum);
        if (llevados < segundos) {
            // P-31: si tarda mas es por ella, y se dice en la misma barra que la cuenta.
            String texto = "Cristal · " + (segundos - llevados) + " s" + (segundos > def ? " · tarda más con la Parca cerca" : "");
            cordura.destello(p, Component.text(texto, ItemsCalamity.MORADO), 2);
            Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.amethyst_block.chime", 0.7f,
                    1.0f + llevados * 0.1f);
            return;
        }

        canalizando.remove(p.getUniqueId());
        cuentaCristal.remove(p.getUniqueId());
        if (!gastarCristal(p)) return;
        volverAlSpawn(p);
    }

    /**
     * 1.11 · Dosa (2026-10-04): el Cristal ya no saca de Calamity, devuelve al spawn de Calamity (el punto
     * llegada). Para salir de verdad, con la Tasacion, hay que cruzar el portal de salida del spawn. Sin
     * llegada marcada en un mundo de Calamity, saca como antes, para no dejar a nadie sin salida.
     */
    private void volverAlSpawn(Player p) {
        Location destino = punto("llegada");
        if (destino == null || destino.getWorld() == null || !esHardcore(destino.getWorld())) {
            sacar(p, "cristal", true);
            return;
        }
        p.teleport(destino);
        p.sendMessage(Component.text("El Cristal de Regreso te devuelve al spawn de Calamity. Para salir, cruza el portal.",
                Paleta.TEXTO));
        Compat.soundPlayers(destino.getWorld(), destino, "block.amethyst_block.resonate", 1.0f, 0.8f);
    }

    /** Si esta canalizando un Cristal: la Huella no cuenta esos segundos quieto. */
    boolean canalizando(Player p) {
        return p != null && canalizando.containsKey(p.getUniqueId());
    }

    /** Corta una canalizacion a medias, con el aviso que se diga. */
    private void cortarCristal(Player p, String aviso) {
        canalizando.remove(p.getUniqueId());
        cuentaCristal.remove(p.getUniqueId());
        p.sendMessage(Component.text(aviso, Paleta.AVISO));
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.amethyst_block.break", 0.8f, 0.8f);
    }

    /** Quita un cristal del inventario. False si ya no lo lleva (lo tiro a mitad). */
    private boolean gastarCristal(Player p) {
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (!items.esCristal(it)) continue;
            if (it.getAmount() > 1) it.setAmount(it.getAmount() - 1);
            else p.getInventory().setItem(i, null);
            return true;
        }
        p.sendMessage(Component.text("Ya no llevas ningún Cristal de Regreso.", Paleta.AVISO));
        return false;
    }

    // ------------------------------------------------------------------- utilidad

    /** Nombre plano de una entidad, para los mensajes. */
    public static String plano(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** La lista de mundos hardcore, para el comando. */
    public List<String> mundos() {
        return new ArrayList<>(cfg().getStringList("mundos"));
    }
}
