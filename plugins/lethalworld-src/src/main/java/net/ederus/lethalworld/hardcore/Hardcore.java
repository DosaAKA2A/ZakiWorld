package net.ederus.lethalworld.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

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

    private final LethalWorldPlugin plugin;
    private final Cordura cordura = new Cordura();
    private final ItemsCalamity items;
    private final Random random = new Random();

    /** Quien esta canalizando el cristal: jugador -> donde estaba al empezar. */
    private final Map<UUID, Location> canalizando = new HashMap<>();
    /** Ultima vez (millis) que a cada jugador se le aplicaron los efectos de su bioma. */
    private final Map<UUID, Long> ultimoEfecto = new HashMap<>();
    /** Segundos que lleva canalizando el cristal cada uno. */
    private final Map<UUID, Integer> cuentaCristal = new HashMap<>();
    /** Cuando murio cada uno dentro (millis), para la cuarentena de reentrada. */
    private final Map<UUID, Long> muertos = new HashMap<>();
    /** Minijefe -> a quien viene siguiendo. Ver marcarPresa(). */
    private final Map<UUID, UUID> presas = new HashMap<>();
    /** Quien acaba de morir dentro y todavia no ha reaparecido. Ver onReaparecer(). */
    private final java.util.Set<UUID> porReaparecer = new java.util.HashSet<>();

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

    public Hardcore(LethalWorldPlugin plugin) {
        this.plugin = plugin;
        this.items = new ItemsCalamity(plugin);
    }

    public Cordura cordura() {
        return cordura;
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

    private ConfigurationSection cfg() {
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

    /** Un jugador cuenta para las reglas si esta jugando de verdad. */
    private boolean cuenta(Player p) {
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
        // Un segundo justo: la cordura se cuenta en segundos y la barra tiene que
        // repintarse a ese ritmo o parpadea contra los avisos de otros plugins.
        reloj = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::tick, 20L, 20L);

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
        MobCoins.aviso(null);
        canalizando.clear();
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

    // ----------------------------------------------------------------------- datos

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
        for (World w : plugin.getServer().getWorlds()) {
            if (!esHardcore(w)) continue;
            for (Player p : w.getPlayers()) {
                if (!cuenta(p)) continue;
                Cordura.Estado e = cordura.estado(p);
                e.segundosDentro++;
                drenar(p, e);
                efectosDeBioma(p);
                cordura.pintar(p);
                vigilarCanalizacion(p);
                nieblaDeNoche(p);
                contarTiempo(p);
                if (e.valor <= 0) minijefeSiTocaCordura(p, e);
            }
        }
        vigilarZonas();
        vigilarPresas();
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
                        p.sendActionBar(Component.text(
                                "Aún no. Vuelve en " + (espera / 60_000 + 1) + " min.", NamedTextColor.RED));
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
                if (cuenta(p) && vara.dentro(p, "salida")) sacar(p, "Cruzas de vuelta.");
            }
        }
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

    /** Guarda un punto donde este el jugador. Lo usa /lw hardcore. */
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

        long hora = p.getWorld().getTime();
        if (hora >= 13000 && hora <= 23000) factor *= cfg().getDouble("cordura.factor-noche", 2.0);
        if (p.getLocation().getBlock().getLightLevel() < 4) {
            factor *= cfg().getDouble("cordura.factor-oscuridad", 2.0);
        }
        factor *= drenajeDelBioma(p);

        double antes = e.valor;
        cordura.sumar(p, -(porMinuto * factor) / 60.0);
        anunciarTramo(p, e, antes);
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
        String texto = switch (ahora) {
            case 3 -> "Algo te sigue con la mirada.";
            case 2 -> "Ya no estás solo aquí.";
            case 1 -> "Las voces no callan. Vete.";
            default -> "Te encontraron.";
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
     * Con la cordura a cero viene a buscarte uno de los grandes.
     *
     * No sale uno por segundo: hay un descanso configurado entre apariciones para que
     * quedarse a cero sea una condena, no una granja de jefes.
     */
    private void minijefeSiTocaCordura(Player p, Cordura.Estado e) {
        int cada = cfg().getInt("minijefes.cada-minutos", 10);
        long ahora = System.currentTimeMillis();
        if (e.ultimoMinijefe != 0 && ahora - e.ultimoMinijefe < cada * 60_000L) return;

        List<String> tipos = cfg().getStringList("minijefes.tipos");
        if (tipos.isEmpty()) return;
        String id = tipos.get(random.nextInt(tipos.size()));
        double distancia = cfg().getDouble("minijefes.distancia", 30);

        LivingEntity mob = plugin.mobs() == null ? null
                : plugin.mobs().invocarMinijefe(p, id, distancia,
                        cfg().getDouble("minijefes.vida", 15),
                        cfg().getDouble("minijefes.dano", 4));
        if (mob == null) return;
        marcarPresa(mob, p);

        e.ultimoMinijefe = ahora;
        Component nombre = mob.customName() == null
                ? Component.text("Algo") : mob.customName();
        p.sendMessage(Component.text("Ha venido a por ti: ", NamedTextColor.DARK_RED).append(nombre));
        Compat.sound(p.getWorld(), p.getLocation(), "entity.wither.spawn", 1.0f, 0.6f);
    }

    // ------------------------------------------------------------------ dificultad

    /** Mobs de mas alrededor de un jugador segun lo ida que tenga la cabeza. */
    public int bonusTope(Player p) {
        if (!esHardcore(p)) return 0;
        double v = cordura.valor(p);
        if (v >= 50) return 0;
        return v <= 0 ? cfg().getInt("cordura.mobs-extra-vacio", 6)
                : cfg().getInt("cordura.mobs-extra", 4);
    }

    /** Niveles de mas para los mobs que salgan alrededor de ese jugador. */
    public int bonusNivel(Player p) {
        if (!esHardcore(p)) return 0;
        int extra = 0;
        double v = cordura.valor(p);
        if (v < 25) extra += cfg().getInt("cordura.nivel-extra-critico", 20);
        else if (v < 50) extra += cfg().getInt("cordura.nivel-extra", 10);

        // Y sube con los minutos que lleves dentro: quedarse es cada vez peor idea.
        int porMinutos = cfg().getInt("dificultad.nivel-cada-minutos", 5);
        if (porMinutos > 0) extra += cordura.estado(p).segundosDentro / (porMinutos * 60);
        return extra;
    }

    /** Sin camas: aqui no se salta la noche ni se pone punto de reaparicion. */
    @EventHandler(ignoreCancelled = true)
    public void onCama(PlayerBedEnterEvent e) {
        if (!esHardcore(e.getPlayer())) return;
        if (!cfg().getBoolean("dificultad.sin-camas", true)) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(Component.text("Aquí no se duerme.", NamedTextColor.RED));
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
        if (!(e.getEntity() instanceof Player p) || !esHardcore(p)) return;
        double penetracion = cfg().getDouble("dificultad.penetracion-armadura", 0.30);
        if (penetracion > 0 && e.getDamager() instanceof LivingEntity && !(e.getDamager() instanceof Player)) {
            e.setDamage(e.getDamage() * (1 + penetracion));
        }
        // Un golpe fuerte tambien cuesta cordura: el susto se paga.
        double porGolpe = cfg().getDouble("cordura.por-golpe", 5);
        if (porGolpe > 0 && e.getFinalDamage() >= p.getHealth() * 0.25) {
            cordura.sumar(p, -porGolpe);
        }
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
    }

    /** Horas acumuladas de un jugador en los mundos hardcore. */
    public double horasDe(Player p) {
        return datos.getLong("tiempo." + p.getUniqueId(), 0) / 3600.0;
    }

    /**
     * El tag de las veinticuatro horas.
     *
     * AlonsoTags decide quien puede ponerse cada etiqueta por un PERMISO, asi que
     * entregarla es darle ese permiso por LuckPerms; la etiqueta en si vive en el
     * tags.yml de AlonsoTags y no la toca nadie desde aqui.
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
        p.showTitle(net.kyori.adventure.title.Title.title(
                Component.text(nombre, TextColor.color(0x9FD6A0)),
                Component.text("Veinticuatro horas ahí dentro", NamedTextColor.GRAY),
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(300),
                        java.time.Duration.ofMillis(2600),
                        java.time.Duration.ofMillis(700))));
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "ui.toast.challenge_complete", 1.0f, 1.0f);
        plugin.getServer().broadcast(Component.text(p.getName(), TextColor.color(0x9FD6A0))
                .append(Component.text(" lleva 24 horas en Calamity y se ha ganado ", NamedTextColor.GRAY))
                .append(Component.text(nombre, TextColor.color(0x9FD6A0), TextDecoration.BOLD))
                .append(Component.text(".", NamedTextColor.GRAY)));
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
        long hora = p.getWorld().getTime();
        if (hora < 13000 || hora > 23000) return;

        // La niebla: ceniza densa alrededor. Esto es lo que se ve SIEMPRE de noche,
        // y no quita visibilidad: cierra el aire, que es lo que se buscaba.
        Compat.spawn(p.getWorld(), Compat.ASH, p.getEyeLocation(), 14, 4.0, 3.0, 4.0, 0.004);

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
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, dura * 20, 0, true, false, false));
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "ambient.cave", 0.7f, 0.5f);
    }

    /**
     * Los minijefes no sueltan a su presa: la siguen aunque cambie de bioma, y solo se
     * acaba cuando cae uno de los dos. Sin esto bastaba con andar veinte bloques.
     */
    private void vigilarPresas() {
        for (UUID idMob : new ArrayList<>(presas.keySet())) {
            org.bukkit.entity.Entity e = plugin.getServer().getEntity(idMob);
            if (!(e instanceof org.bukkit.entity.Mob mob) || !mob.isValid()) {
                presas.remove(idMob);
                continue;
            }
            Player presa = plugin.getServer().getPlayer(presas.get(idMob));
            if (presa == null || !presa.isOnline() || !esHardcore(presa)) {
                presas.remove(idMob);
                continue;
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

    /** Apunta que ese minijefe viene a por ese jugador y no lo suelta. */
    public void marcarPresa(org.bukkit.entity.Entity minijefe, Player presa) {
        presas.put(minijefe.getUniqueId(), presa.getUniqueId());
    }

    /** Los mobs de este mundo pueden recoger lo que se cae al suelo. */
    @EventHandler(ignoreCancelled = true)
    public void onAparecer(CreatureSpawnEvent e) {
        if (!esHardcore(e.getEntity().getWorld())) return;
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
        // Solo se dobla lo que se PIERDE; comer sigue dando lo que da.
        e.setFoodLevel((int) Math.max(0, antes - (antes - e.getFoodLevel()) * factor));
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
        p.sendMessage(Component.text("Eso estaba crudo.", NamedTextColor.DARK_GREEN));
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
            case FALL -> cfg().getDouble("dificultad.dano-caida", 2.0);
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
        if (factor > 1) e.setDamage((int) Math.ceil(e.getDamage() * factor));
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
        p.sendMessage(Component.text("El tótem se deshace sin salvarte.", NamedTextColor.DARK_RED));
        Compat.spawn(p.getWorld(), Compat.ASH, p.getLocation().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0.03);
    }

    /** Aqui los mobs SI recogen lo que se te cae, y se lo quedan. */
    @EventHandler(ignoreCancelled = true)
    public void onRecoger(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player) return;
        if (!esHardcore(e.getEntity().getWorld())) return;
        if (!cfg().getBoolean("dificultad.mobs-recogen", true)) return;
        LivingEntity mob = e.getEntity();

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
        if (!cfg().getBoolean("dificultad.cofres-vacios", true)) return;
        e.setLoot(java.util.Collections.emptyList());
    }

    /** Fuego amigo: aqui os podeis matar entre vosotros. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFuegoAmigo(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victima) || !esHardcore(victima)) return;
        if (!cfg().getBoolean("dificultad.fuego-amigo", true)) return;
        Entity quien = e.getDamager();
        if (quien instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Entity fuente) {
            quien = fuente;
        }
        // Solo se devuelve el golpe de OTRO jugador: lo demas que lo decidan las
        // protecciones normales del servidor.
        if (quien instanceof Player agresor && !agresor.equals(victima) && e.isCancelled()) {
            e.setCancelled(false);
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
        Player p = e.getEntity();
        if (!esHardcore(p)) return;
        if (!cfg().getBoolean("muerte.lo-pierde-todo", true)) return;

        e.getDrops().clear();
        e.setDroppedExp(0);
        e.setKeepInventory(false);
        e.setKeepLevel(false);
        e.setNewExp(0);
        e.setNewLevel(0);
        e.setNewTotalExp(0);
        p.getInventory().clear();

        cordura.reiniciar(p);
        muertos.put(p.getUniqueId(), System.currentTimeMillis());
        e.deathMessage(Component.text(p.getName() + " no volvió de Calamity.",
                TextColor.color(0x8B1A1A)));

        // A donde reaparece se decide en onReaparecer, que es cuando vuelve a tener
        // cuerpo: dos ticks despues de morir sigue en la pantalla de muerte, y a un
        // muerto no se le puede teletransportar.
        porReaparecer.add(p.getUniqueId());
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
        p.sendMessage(Component.text("Has muerto allí dentro.", NamedTextColor.GRAY));
    }

    // --------------------------------------------------------------- entrar/salir

    /** El punto de vuelta: lo que diga la config, o el spawn del mundo principal. */
    public Location salida() {
        Location guardado = punto("salida");
        if (guardado != null) return guardado;
        List<World> mundos = plugin.getServer().getWorlds();
        return mundos.isEmpty() ? null : mundos.get(0).getSpawnLocation();
    }

    /** Saca a un jugador del mundo hardcore y le devuelve la cordura entera. */
    public void sacar(Player p, String motivo) {
        Location destino = salida();
        if (destino == null) return;
        p.teleport(destino);
        cordura.reiniciar(p);
        p.sendActionBar(Component.empty());
        if (motivo != null && !motivo.isEmpty()) {
            p.sendMessage(Component.text(motivo, NamedTextColor.GRAY));
        }
        Compat.soundPlayers(destino.getWorld(), destino, "block.amethyst_block.resonate", 1.0f, 0.8f);
    }

    /**
     * Mete a un jugador. La oleada de bienvenida es una de las reglas de dificultad:
     * el mundo no te deja llegar y mirar, te recibe con algo encima.
     */
    public void meter(Player p, Location destino) {
        if (destino == null) return;
        p.teleport(destino);
        cordura.reiniciar(p);
        p.sendMessage(Component.text("Calamity", TextColor.color(0x8B1A1A))
                .append(Component.text("  ·  Lo que traigas, lo pierdes al morir.", NamedTextColor.GRAY)));
        Compat.sound(destino.getWorld(), destino, "ambient.cave", 1.2f, 0.5f);

        int oleada = cfg().getInt("dificultad.oleada-de-entrada", 5);
        if (oleada > 0 && plugin.mobs() != null) {
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> plugin.mobs().oleada(p, oleada), 60L);
        }
    }

    @EventHandler
    public void onEntrar(PlayerJoinEvent e) {
        // Quien se desconecto dentro vuelve con la cordura que tenia: salir por las
        // malas no puede ser la forma barata de resetear el reloj.
        Player p = e.getPlayer();
        if (!esHardcore(p)) return;
        double guardada = datos.getDouble("guardado." + p.getUniqueId(), -1);
        if (guardada >= 0) cordura.valor(p, guardada);
    }

    @EventHandler
    public void onSalir(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        canalizando.remove(p.getUniqueId());
        cuentaCristal.remove(p.getUniqueId());
        ultimoEfecto.remove(p.getUniqueId());
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
        e.setCancelled(true);
        p.sendMessage(Component.text("Aquí no. Se sale por el portal o con un Cristal de Regreso.",
                NamedTextColor.RED));
    }

    // ----------------------------------------------------------------- los objetos

    @EventHandler(ignoreCancelled = true)
    public void onUsar(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!e.getAction().isRightClick()) return;
        Player p = e.getPlayer();
        ItemStack mano = e.getItem();
        if (mano == null) return;

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
            p.sendMessage(Component.text("Fuera de Calamity no hace nada.", NamedTextColor.GRAY));
            return;
        }
        int quedan = items.tragos(frasco);
        if (quedan <= 0) {
            p.sendMessage(Component.text("El frasco está vacío. Recárgalo en el altar del spawn.",
                    NamedTextColor.GRAY));
            Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.glass.break", 0.6f, 1.4f);
            return;
        }
        double sube = cfg().getDouble("frasco.cordura", 40);
        cordura.sumar(p, sube);
        cordura.estado(p).ultimoTramo = Cordura.tramo(cordura.valor(p));

        ItemStack nuevo = items.frasco(quedan - 1);
        nuevo.setAmount(1);
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
            p.sendMessage(Component.text("El cristal solo funciona dentro de Calamity.",
                    NamedTextColor.GRAY));
            return;
        }
        if (canalizando.containsKey(p.getUniqueId())) return;
        canalizando.put(p.getUniqueId(), p.getLocation().clone());
        p.sendMessage(Component.text("El cristal empieza a resonar. No te muevas.",
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
            p.sendMessage(Component.text("Te has movido: el cristal se apaga.", NamedTextColor.RED));
            Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.amethyst_block.break", 0.8f, 0.8f);
            return;
        }
        Compat.spawn(p.getWorld(), Compat.WITCH, p.getLocation().add(0, 1, 0), 12, 0.3, 0.6, 0.3, 0.01);

        int segundos = cfg().getInt("cristal.segundos", 5);
        int llevados = cuentaCristal.merge(p.getUniqueId(), 1, Integer::sum);
        if (llevados < segundos) {
            cordura.destello(p, Component.text("Cristal · " + (segundos - llevados) + " s",
                    ItemsCalamity.MORADO), 2);
            Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.amethyst_block.chime", 0.7f,
                    1.0f + llevados * 0.1f);
            return;
        }

        canalizando.remove(p.getUniqueId());
        cuentaCristal.remove(p.getUniqueId());
        if (!gastarCristal(p)) return;
        sacar(p, "El cristal te devuelve al spawn.");
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
        p.sendMessage(Component.text("Ya no llevas ningún cristal.", NamedTextColor.RED));
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
