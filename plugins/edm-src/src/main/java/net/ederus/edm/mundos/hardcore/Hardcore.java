package net.ederus.edm.mundos.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.ederus.edm.Module;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.MobCoins;
import net.ederus.edm.mundos.MundosPlugin;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
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
 * pregunta primero por el mundo, y con la lista vacia el modulo no hace nada.
 *
 * Lo que decide que un mundo es hardcore esta en la config (hardcore.mundos), no en el
 * codigo: manana Dosa puede montar otro mundo con las mismas reglas sin tocar Java.
 */
public final class Hardcore implements Listener {

    private final MundosPlugin modulo;
    private final Cordura cordura = new Cordura();
    private final ItemsCalamity items;
    private final Random random = new Random();

    /** Quien esta canalizando el cristal: jugador -> donde estaba al empezar. */
    private final Map<UUID, Location> canalizando = new HashMap<>();
    /** Ultima vez (millis) que a cada jugador se le aplicaron los efectos de su bioma. */
    private final Map<UUID, Long> ultimoEfecto = new HashMap<>();
    /** Segundos que lleva canalizando el cristal cada uno. */
    private final Map<UUID, Integer> cuentaCristal = new HashMap<>();

    private BukkitTask reloj;

    public Hardcore(MundosPlugin modulo) {
        this.modulo = modulo;
        this.items = new ItemsCalamity(modulo);
    }

    public Cordura cordura() {
        return cordura;
    }

    public ItemsCalamity items() {
        return items;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = modulo.getConfig().getConfigurationSection("hardcore");
        return s == null ? new YamlConfiguration() : s;
    }

    /** Si este mundo se rige por las reglas hardcore. */
    public boolean esHardcore(World w) {
        if (w == null || !MundosPlugin.esMundo(w)) return false;
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

    public void arrancar() {
        if (!cfg().getBoolean("activo", true)) {
            modulo.getLogger().info("[Calamity] Reglas hardcore apagadas en la config.");
            return;
        }
        modulo.getServer().getPluginManager().registerEvents(this, Module.dueno(modulo));
        // Un segundo justo: la cordura se cuenta en segundos y la barra tiene que
        // repintarse a ese ritmo o parpadea contra los avisos de otros plugins.
        reloj = modulo.getServer().getScheduler().runTaskTimer(
                Module.dueno(modulo), this::tick, 20L, 20L);

        // Las MobCoins pasan por la barra de la cordura en vez de pisarla.
        MobCoins.aviso((jugador, cantidad) -> {
            if (!esHardcore(jugador)) return false;
            cordura.destello(jugador, Component.text("+" + cantidad + " MobCoins", MobCoins.ORO), 2);
            return true;
        });
        modulo.getLogger().info("[Calamity] Reglas hardcore activas en: "
                + String.join(", ", cfg().getStringList("mundos")));
    }

    public void parar() {
        if (reloj != null) reloj.cancel();
        MobCoins.aviso(null);
        canalizando.clear();
    }

    // ----------------------------------------------------------------------- reloj

    private void tick() {
        for (World w : modulo.getServer().getWorlds()) {
            if (!esHardcore(w)) continue;
            for (Player p : w.getPlayers()) {
                if (!cuenta(p)) continue;
                Cordura.Estado e = cordura.estado(p);
                e.segundosDentro++;
                drenar(p, e);
                efectosDeBioma(p);
                cordura.pintar(p);
                vigilarCanalizacion(p);
                if (e.valor <= 0) minijefeSiTocaCordura(p, e);
            }
        }
        vigilarZonas();
        // Quien haya salido del mundo con una canalizacion a medias no se queda colgado.
        canalizando.keySet().removeIf(id -> {
            Player p = modulo.getServer().getPlayer(id);
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
        Location entrada = punto("entrada");
        Location llegada = punto("llegada");
        if (entrada != null && llegada != null) {
            for (Player p : entrada.getWorld().getPlayers()) {
                if (!cuenta(p) || esHardcore(p)) continue;
                if (dentroDe(p, entrada)) meter(p, llegada);
            }
        }
        Location vuelta = punto("puerta-salida");
        if (vuelta != null) {
            for (Player p : vuelta.getWorld().getPlayers()) {
                if (!cuenta(p) || !esHardcore(p)) continue;
                if (dentroDe(p, vuelta)) sacar(p, "Cruzas de vuelta.");
            }
        }
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
        modulo.getConfig().set(base + "mundo", donde.getWorld().getKey().toString());
        modulo.getConfig().set(base + "x", donde.getX());
        modulo.getConfig().set(base + "y", donde.getY());
        modulo.getConfig().set(base + "z", donde.getZ());
        modulo.getConfig().set(base + "yaw", donde.getYaw());
        modulo.getConfig().set(base + "pitch", donde.getPitch());
        modulo.saveConfig();
    }

    /** Busca un mundo por su clave completa (lethal_world:calamity) o por su nombre. */
    private World mundoDe(String id) {
        org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(id);
        World w = key == null ? null : modulo.getServer().getWorld(key);
        return w != null ? w : modulo.getServer().getWorld(id);
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
                modulo.getLogger().warning("[Calamity] Efecto desconocido en la config: " + partes[0]);
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

        LivingEntity mob = modulo.mobs() == null ? null
                : modulo.mobs().invocarMinijefe(p, id, distancia,
                        cfg().getDouble("minijefes.vida", 15),
                        cfg().getDouble("minijefes.dano", 4));
        if (mob == null) return;

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
        e.deathMessage(Component.text(p.getName() + " no volvió de Calamity.",
                TextColor.color(0x8B1A1A)));

        // El respawn lo decide el servidor; aqui solo nos aseguramos de que no
        // reaparezca dentro. Se hace un tick despues, cuando ya tiene cuerpo.
        modulo.getServer().getScheduler().runTaskLater(Module.dueno(modulo), () -> {
            if (p.isOnline() && esHardcore(p)) sacar(p, "Has muerto allí dentro.");
        }, 2L);
    }

    // --------------------------------------------------------------- entrar/salir

    /** El punto de vuelta: lo que diga la config, o el spawn del mundo principal. */
    public Location salida() {
        Location guardado = punto("salida");
        if (guardado != null) return guardado;
        List<World> mundos = modulo.getServer().getWorlds();
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
        if (oleada > 0 && modulo.mobs() != null) {
            modulo.getServer().getScheduler().runTaskLater(Module.dueno(modulo),
                    () -> modulo.mobs().oleada(p, oleada), 60L);
        }
    }

    @EventHandler
    public void onEntrar(PlayerJoinEvent e) {
        // Quien se desconecto dentro vuelve con la cordura que tenia: salir por las
        // malas no puede ser la forma barata de resetear el reloj.
        Player p = e.getPlayer();
        if (!esHardcore(p)) return;
        double guardada = modulo.getConfig().getDouble("hardcore.guardado." + p.getUniqueId(), -1);
        if (guardada >= 0) cordura.valor(p, guardada);
    }

    @EventHandler
    public void onSalir(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        canalizando.remove(p.getUniqueId());
        cuentaCristal.remove(p.getUniqueId());
        ultimoEfecto.remove(p.getUniqueId());
        if (esHardcore(p) && cordura.conoce(p)) {
            modulo.getConfig().set("hardcore.guardado." + p.getUniqueId(), cordura.valor(p));
            modulo.saveConfig();
        } else {
            modulo.getConfig().set("hardcore.guardado." + p.getUniqueId(), null);
        }
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
            p.getInventory().addItem(nuevo);
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
