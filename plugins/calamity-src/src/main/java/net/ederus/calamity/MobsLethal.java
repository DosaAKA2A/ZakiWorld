package net.ederus.calamity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Creaking;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Explosive;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.core.Glow;
import net.ederus.edm.anomaly.minions.MinionAbility;
import net.ederus.edm.anomaly.minions.MinionCategory;
import net.ederus.edm.anomaly.minions.MinionManager;
import net.ederus.edm.anomaly.minions.MinionPresence;
import net.ederus.edm.anomaly.minions.MinionRegistry;
import net.ederus.edm.anomaly.minions.MinionType;
import net.ederus.edm.comun.Compat;
import net.ederus.calamity.hardcore.Apariciones;
import net.ederus.calamity.hardcore.Hardcore;
import net.ederus.calamity.hardcore.Marcas;
import net.ederus.calamity.hardcore.Paleta;
import net.ederus.lethalworld.LethalWorldPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Los mobs de Lethal World. En estos mundos no hay hostil sin nivel:
 *
 *  - Los que aparecen alrededor de cada jugador son tipos de esbirro (los de /esb, en su
 *    carpeta) segun el BIOMA, uno cada pocos segundos hasta el tope. El spawn natural de
 *    hostiles vanilla se cancela: si se sustituyera uno a uno, vanilla intenta tantos por
 *    tick que el tope no llega a frenarlos y salen en manada.
 *  - Los que ya traen las estructuras y los que salen de spawners se ADOPTAN: nivel, cartel
 *    y dano por nivel, sin cambiar la entidad y con su nombre. Los que se llaman como un
 *    minijefe reciben nivel extra y mucha vida.
 *  - Calamity 1.9.0: los especiales (mobs.especiales: creaking, ghast y guardian anciano) salen
 *    aparte, cada uno en su sitio (suelo con mas altura, aire o agua) y con su propio tope. Las
 *    reglas puras (tabla, especiales, busqueda de sitio) estan en hardcore.Apariciones.
 *  - Las estructuras que venian vacias tienen guarnicion: al acercarse un jugador aparecen
 *    unos cuantos del tipo que se les asigne, y vuelven un rato despues de caer.
 *  - Nivel = rango de rankup x A + poder de AuraSkills / B, del jugador mas cercano.
 *  - MobCoins: lo que ese mob paga en el Survival (tabla de UltimateMobCoins) x (1 + nivel / C)
 *    x multiplicador del mundo, y mas si es destacado o minijefe. Las paga EDM.
 *
 * Los tipos se siembran UNA vez en esbirros.yml (si no existen) y a partir de ahi se editan
 * desde /esb como los demas. Las tablas bioma -> tipos y estructura -> guarnicion viven en la
 * config de este plugin.
 */
public final class MobsLethal implements Listener {

    private final CalamityPlugin plugin;
    private final Random random = new Random();
    private final Set<UUID> vivos = new HashSet<>();
    private final Map<String, Double> baseMonedas = new HashMap<>();
    /** bioma -> lo que sale ahi (comunes y destacado aparte), leido de la config al arrancar. */
    private final Map<String, Apariciones.Tabla> tabla = new HashMap<>();
    /** Calamity 1.9.0: los mobs especiales (mobs.especiales), por su clave, leidos al arrancar. */
    private final Map<String, Apariciones.Especial> especiales = new java.util.LinkedHashMap<>();
    /** Calamity 1.9.0: clave del especial -> id de su ficha en /esb. */
    private final Map<String, String> fichaEspecial = new HashMap<>();
    /** estructura -> guarnicion, leido de la config al arrancar. */
    private final Map<String, Guarnicion> guarniciones = new HashMap<>();
    /** Cada estructura con guarnicion (mundo + centro) y sus mobs vivos. */
    private final Map<String, Set<UUID>> ocupadas = new HashMap<>();
    private final Map<String, Long> proximaGuarnicion = new HashMap<>();
    private final Map<UUID, String> puestoDe = new HashMap<>();
    private final NamespacedKey clave;
    /**
     * Calamity 1.7: los niveles de distancia al spawn con los que nacio el mob (texto, para que
     * las crias de Division lo hereden). Al morir sus MobCoins suben por ellos (mobcoinsDe).
     */
    private final NamespacedKey claveDistancia;
    /**
     * Calamity 1.9.0: la marca de un mob especial (su clave en mobs.especiales). La llevan tambien
     * sus bolas de fuego desde que salen, para que no rompan bloques aunque alguien las devuelva.
     */
    private final NamespacedKey claveEspecial;
    /**
     * Calamity 1.9.0: en una bola de fuego que un jugador ha devuelto, el ghast especial al que ya
     * ha golpeado. La misma bola le pega dos veces en el mismo tick (el impacto y su explosion) y
     * solo debe contar una (alDevolver).
     */
    private final NamespacedKey claveBolaDevuelta;
    private BukkitTask aparicion;
    private BukkitTask limpieza;
    /** Calamity 1.8.4: el cartel de los minijefes con su formato (null sin el modulo anomaly). */
    private CartelesMinijefe carteles;

    private record Guarnicion(String tipo, int minimo, int maximo) {
    }

    MobsLethal(CalamityPlugin plugin) {
        this.plugin = plugin;
        /* El namespace se escribe a mano y sigue siendo "edm" aunque esto ya no sea
         * un modulo de EDM: los mobs que hay AHORA en Calamity llevan esta marca en
         * su PersistentDataContainer. Con lethalworld:... el plugin dejaria de
         * reconocer a los suyos y los adoptaria otra vez desde cero. */
        this.clave = new NamespacedKey("edm", "lethal_world_mob");
        this.claveDistancia = new NamespacedKey("edm", "lethal_world_distancia");
        this.claveEspecial = new NamespacedKey(plugin, "especial");
        this.claveBolaDevuelta = new NamespacedKey(plugin, "bola_devuelta");
    }

    /** El modulo anomaly de EDM (los esbirros). Sin EDM o sin ese modulo, no hay mobs. */
    private AnomalyPlugin anomaly() {
        if (!(plugin.getServer().getPluginManager().getPlugin("EDM") instanceof EDMPlugin edm)) return null;
        return edm.modulo("anomaly") instanceof AnomalyPlugin a ? a : null;
    }

    /**
     * El gestor de esbirros de EDM (carteles "Nv. X", adoptar), o null sin EDM o sin su
     * modulo anomaly. Publico para las amenazas de Calamity: asi solo hay un sitio en
     * Lethal World que va a buscar EDM.
     */
    public MinionManager minionManager() {
        AnomalyPlugin a = anomaly();
        return a == null ? null : a.minionManager();
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("mobs");
        return s == null ? new YamlConfiguration() : s;
    }

    void arrancar() {
        AnomalyPlugin a = anomaly();
        if (a == null || a.minions() == null || a.minionManager() == null) {
            plugin.getLogger().warning("[Lethal World] El módulo anomaly (esbirros) no está activo: sin mobs de Lethal World.");
            return;
        }
        sembrar(a.minions());
        cargarTabla();
        cargarEspeciales(a.minions());
        cargarGuarniciones();
        cargarMonedas();
        a.minionManager().heredable(clave);
        a.minionManager().heredable(claveDistancia);
        // Si alguien le pone Division a un especial en /esb, sus crias siguen siendo especiales.
        a.minionManager().heredable(claveEspecial);
        // Antes de mirar "activos": con los mobs apagados, las reglas hardcore siguen invocando minijefes.
        carteles = new CartelesMinijefe(plugin, a);
        plugin.getServer().getPluginManager().registerEvents(carteles, plugin);
        if (!cfg().getBoolean("activos", true)) {
            plugin.getLogger().info("[Lethal World] Mobs de Lethal World apagados en la config.");
            return;
        }
        if (!cfg().getBoolean("adoptados.ignorar-mythicmobs", true)) {
            plugin.getLogger().warning("[Lethal World] Los MythicMobs SÍ se adoptan"
                    + " (mobs.adoptados.ignorar-mythicmobs está en false): escalan dos veces.");
        } else if (PuenteMythicMobs.disponible()) {
            plugin.getLogger().info("[Lethal World] MythicMobs detectado. Los MythicMobs no se adoptan.");
        } else {
            plugin.getLogger().info("[Lethal World] MythicMobs no está instalado.");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        long cada = Math.max(10, cfg().getLong("cada-ticks", 40));
        aparicion = plugin.getServer().getScheduler().runTaskTimer(plugin, this::ciclo, cada, cada);
        limpieza = plugin.getServer().getScheduler().runTaskTimer(plugin, this::retirarLejanos, 100L, 100L);
    }

    void parar() {
        if (aparicion != null) aparicion.cancel();
        if (limpieza != null) limpieza.cancel();
        if (carteles != null) carteles.parar();
        for (UUID id : vivos) {
            Entity e = plugin.getServer().getEntity(id);
            if (e != null) e.remove();
        }
        vivos.clear();
        ocupadas.clear();
        puestoDe.clear();
    }

    // ------------------------------------------------------------------ aparicion

    private void ciclo() {
        AnomalyPlugin a = anomaly();
        if (a == null) return;
        MinionManager mm = a.minionManager();
        int tope = cfg().getInt("tope-por-jugador", 6);
        double radioConteo = cfg().getDouble("radio-conteo", 48);
        double radioAdopcion = cfg().getDouble("radio-adopcion", 40);
        int min = cfg().getInt("distancia-minima", 20), max = cfg().getInt("distancia-maxima", 40);

        for (World w : plugin.getServer().getWorlds()) {
            if (!LethalWorldPlugin.esMundo(w)) continue;
            for (Player p : w.getPlayers()) {
                if (!cuenta(p)) continue;
                adoptarCerca(mm, p, radioAdopcion);
                guarnecer(p);
                // Calamity 1.2: a quien esta en la zona spawn no le sale nada alrededor.
                if (zonaSegura(p.getLocation())) continue;
                int topeDelJugador = tope + (plugin.hardcore() == null ? 0 : plugin.hardcore().bonusTope(p));
                int alrededor = cerca(p, radioConteo);
                // 1.9.0: los especiales van aparte y con su propio tope, para que salgan aunque el
                // normal este lleno (lo esta casi siempre). Mientras viven cuentan dentro de el.
                if (intentarEspecial(p, min, max)) alrededor++;
                if (alrededor >= topeDelJugador) continue;
                Location sitio = sitio(p, min, max);
                if (sitio != null) invocar(p, sitio);
            }
        }
    }

    /** Un mob del bioma de ese sitio, con el nivel del jugador. Null si el bioma no tiene tabla. */
    private LivingEntity invocar(Player p, Location sitio) {
        Apariciones.Tabla t = tabla.get(sitio.getBlock().getBiome().getKey().asString());
        if (t == null) return null;
        Apariciones.Eleccion sale = t.elegir(random.nextDouble(), cfg().getDouble("probabilidad-destacado", 0.05),
                random.nextInt(t.comunes().size()));
        if (!cabe(sitio, sale.id())) return null;
        return invocarTipo(p, sale.id(), sale.destacado(), sitio);
    }

    /**
     * Calamity 1.9.0: sitio() deja dos bloques libres, y los que pasan de dos de alto (el ravager, o
     * un creaking que alguien meta en la tabla) nacian con la cabeza dentro del bloque de arriba. Si
     * al que ha salido no le cabe, este ciclo no sale nada.
     */
    private boolean cabe(Location sitio, String id) {
        AnomalyPlugin a = anomaly();
        MinionType tipo = a == null ? null : a.minions().type(id);
        int alto = tipo == null ? 2 : Apariciones.altoDe(tipo.entity());
        for (int h = 2; h < alto; h++) {
            if (!sitio.clone().add(0, h, 0).getBlock().isPassable()) return false;
        }
        return true;
    }

    private LivingEntity invocarTipo(Player p, String id, boolean destacado, Location sitio) {
        // Calamity 1.2: el unico sitio por el que nace un mob de Lethal World (ciclo, oleada,
        // minijefe y guarnicion), asi que la zona spawn se cierra aqui una vez para todos.
        if (zonaSegura(sitio)) return null;
        AnomalyPlugin a = anomaly();
        if (a == null) return null;
        MinionType tipo = a.minions().type(id);
        if (tipo == null) return null;
        int distancia = distanciaDe(p);
        LivingEntity mob = a.minionManager().spawnAt(tipo, nivelPara(p, destacado, distancia), sitio, null);
        if (mob == null) return null;
        mob.getPersistentDataContainer().set(clave, PersistentDataType.STRING, destacado ? "destacado" : "comun");
        apuntarDistancia(mob, distancia);
        sinQuemarse(mob);
        vivos.add(mob.getUniqueId());
        return mob;
    }

    /**
     * Un minijefe de verdad: el tipo que se diga, con la vida y el dano multiplicados.
     *
     * Lo llaman las reglas hardcore cuando a alguien se le acaba la cordura. No es un
     * esbirro grande: con los multiplicadores de serie aguanta como un jefe pequeno y
     * pega como para matar de dos golpes, que es justo lo que se busca.
     */
    public LivingEntity invocarMinijefe(Player p, String id, double distancia,
                                        double multiplicadorVida, double multiplicadorDano) {
        Location sitio = sitio(p, (int) Math.max(8, distancia - 8), (int) Math.max(12, distancia));
        if (sitio == null) sitio = p.getLocation().add(
                (random.nextDouble() - 0.5) * distancia, 0, (random.nextDouble() - 0.5) * distancia);

        LivingEntity mob = invocarTipo(p, id, true, sitio);
        if (mob == null) return null;

        double vida = Compat.getAttribute(mob, "max_health", 20) * Math.max(1, multiplicadorVida);
        Compat.setAttribute(mob, "max_health", Math.min(1024, vida));
        mob.setHealth(Math.min(1024, vida));
        Compat.setAttribute(mob, "attack_damage",
                Compat.getAttribute(mob, "attack_damage", 3) * Math.max(1, multiplicadorDano));
        mob.getPersistentDataContainer().set(clave, PersistentDataType.STRING, "minijefe");
        double escala = escalaDe(id);
        if (escala > 1) Compat.setAttribute(mob, "scale", Math.min(2.0, escala));

        /* Calamity 1.8.4: el nombre ya no se toca en el customName. EDM no le pone ninguno a sus
         * esbirros (lo que se ve encima es su cartel), asi que el rojo que se le daba aqui no llegaba
         * a verse y el aviso decia "un minijefe". El cartel lo repinta CartelesMinijefe, ya mismo para
         * que el primer tick no salga con el de la ficha; el aviso lo pone nombreMinijefe. */
        if (carteles != null) carteles.repintar();
        return mob;
    }

    /**
     * Calamity 1.8.4 · El nombre del minijefe para el aviso "Ha venido a por ti": Paleta.minijefe con
     * el nombre de su ficha de /esb y su nivel detras (" · Nv. 45"). El cartel usa la misma funcion.
     */
    public Component nombreMinijefe(LivingEntity mob) {
        MinionManager mm = minionManager();
        MinionType t = mm == null ? null : mm.typeOf(mob);
        return net.ederus.calamity.hardcore.Paleta.minijefe(t == null ? null : t.display(),
                mm == null ? 0 : mm.levelOf(mob));
    }

    /** La escala con la que se planta cada minijefe, por su nombre. 1 si no es de los cinco. */
    private double escalaDe(String id) {
        AnomalyPlugin a = anomaly();
        MinionType t = a == null ? null : a.minions().type(id);
        if (t == null) return 1;
        for (Minijefe m : MINIJEFES) {
            if (m.nombre().equals(t.display())) return m.escala();
        }
        return 1;
    }

    /** Una tanda de mobs del bioma alrededor de un jugador, para la oleada de entrada. */
    public void oleada(Player p, int cuantos) {
        // Calamity 1.2: si la llegada cae en la zona spawn, ahi no recibe nadie a nadie.
        if (zonaSegura(p.getLocation())) return;
        for (int i = 0; i < cuantos; i++) {
            Location sitio = sitio(p, 12, 26);
            if (sitio != null) invocar(p, sitio);
        }
    }

    private static void sinQuemarse(LivingEntity mob) {
        if (mob instanceof Zombie z) z.setShouldBurnInDay(false);
        if (mob instanceof AbstractSkeleton s) s.setShouldBurnInDay(false);
        if (mob instanceof Phantom ph) ph.setShouldBurnInDay(false);
    }

    private int cerca(Player p, double radio) {
        int n = 0;
        double r2 = radio * radio;
        for (UUID id : vivos) {
            Entity e = plugin.getServer().getEntity(id);
            if (e != null && e.getWorld().equals(p.getWorld()) && e.getLocation().distanceSquared(p.getLocation()) <= r2) n++;
        }
        return n;
    }

    /**
     * Un punto de suelo firme, fuera del agua, a distancia del jugador y en un chunk ya
     * cargado, o null. Publico: la PARCA y el Eco buscan sitio igual que los mobs.
     */
    public Location sitio(Player p, int min, int max) {
        return sitio(p, min, max, 2);
    }

    /**
     * Lo mismo con 'alto' bloques libres encima (sin agua ni lava) en vez de dos. Calamity 1.9.0:
     * el Crujidor Palido (creaking) mide 2,7 y con dos nacia con la cabeza dentro de un bloque.
     */
    public Location sitio(Player p, int min, int max, int alto) {
        World w = p.getWorld();
        Apariciones.Sondeo mundo = sondeo(w);
        for (int intento = 0; intento < 4; intento++) {
            int[] xz = puntoAlrededor(p, min, max);
            int x = xz[0], z = xz[1];
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (!Apariciones.suelo(mundo, x, y, z, alto)) continue;
            if (Math.abs(y - p.getLocation().getBlockY()) > 24) continue;
            return new Location(w, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }

    /**
     * Calamity 1.9.0 · Un sitio en el aire para un volador (el ghast): a distancia del jugador, entre
     * alturaMin y alturaMax bloques sobre lo mas alto de la columna (copas de los arboles incluidas),
     * con un cubo de aire de 'lado' bloques alrededor. En el suelo, con dos de aire, un ghast (4 x 4 x 4)
     * se asfixia o se queda atascado entre los arboles.
     */
    private Location sitioAire(Player p, int min, int max, int alturaMin, int alturaMax, int lado) {
        World w = p.getWorld();
        Apariciones.Sondeo mundo = sondeo(w);
        for (int intento = 0; intento < 6; intento++) {
            int[] xz = puntoAlrededor(p, min, max);
            int x = xz[0], z = xz[1];
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int suelo = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
            // Con el jugador en una cueva, un ghast en el cielo ni le ve ni pinta nada.
            if (Math.abs(suelo - p.getLocation().getBlockY()) > 24) continue;
            int altura = alturaMin + random.nextInt(Math.max(1, alturaMax - alturaMin + 1));
            if (suelo + altura + lado >= w.getMaxHeight()) continue;
            Integer y = Apariciones.aire(mundo, x, suelo, z, altura, lado);
            if (y != null) return new Location(w, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    /**
     * Calamity 1.9.0 · Un sitio en el agua para un nadador (el guardian anciano): una columna que sea
     * agua desde arriba al menos 'profundidad' bloques y un cubo de agua de 'lado' bloques bajo la
     * superficie. En Panacea no hay oceanos ni rios como bioma: el agua son lagos de cualquier bioma
     * (todo lo que queda por debajo de y=43), asi que se mira el bloque y no el bioma. Mas intentos
     * que en tierra, porque la mayoria de los puntos al azar caen en seco.
     */
    private Location sitioAgua(Player p, int min, int max, int profundidad, int lado) {
        World w = p.getWorld();
        Apariciones.Sondeo mundo = sondeo(w);
        for (int intento = 0; intento < 8; intento++) {
            int[] xz = puntoAlrededor(p, min, max);
            int x = xz[0], z = xz[1];
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            // Este heightmap cuenta los fluidos: lo mas alto de la columna es la superficie del lago.
            int superficie = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
            if (Math.abs(superficie - p.getLocation().getBlockY()) > 24) continue;
            Integer y = Apariciones.agua(mundo, x, superficie, z, profundidad, lado);
            if (y != null) return new Location(w, x + lado / 2.0, y, z + lado / 2.0);
        }
        return null;
    }

    /** Un punto al azar a entre min y max bloques del jugador en horizontal, como {x, z}. */
    private int[] puntoAlrededor(Player p, int min, int max) {
        double ang = random.nextDouble() * Math.PI * 2;
        double d = min + random.nextDouble() * Math.max(1, max - min);
        int x = p.getLocation().getBlockX() + (int) Math.round(Math.cos(ang) * d);
        int z = p.getLocation().getBlockZ() + (int) Math.round(Math.sin(ang) * d);
        return new int[]{x, z};
    }

    /**
     * El mundo visto como Apariciones.Celda, bloque a bloque. Un bloque de un chunk sin cargar
     * cuenta como solido: leerlo lo cargaria de golpe, y un cubo de 5 al borde de un chunk puede
     * asomar al de al lado.
     */
    private static Apariciones.Sondeo sondeo(World w) {
        return (x, y, z) -> w.isChunkLoaded(x >> 4, z >> 4) ? celda(w.getBlockAt(x, y, z)) : Apariciones.Celda.SOLIDO;
    }

    private static Apariciones.Celda celda(Block b) {
        Material m = b.getType();
        if (m == Material.LAVA) return Apariciones.Celda.LAVA;
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) {
            return Apariciones.Celda.AGUA;
        }
        if (b.isEmpty()) return Apariciones.Celda.AIRE;
        if (m.isSolid()) return Apariciones.Celda.SOLIDO;
        return b.isPassable() && !b.isLiquid() ? Apariciones.Celda.PASABLE : Apariciones.Celda.SOLIDO;
    }

    // ------------------------------------------------------------ mobs especiales

    /**
     * Calamity 1.9.0 · Los especiales que tocan en el bioma del jugador, en orden al azar: cada uno
     * tira su dado y, si sale y aun no llega a su tope, se le busca sitio segun su entorno. Primero
     * el bicho y luego el sitio, porque un ghast no cabe donde un zombi y un guardian solo nada en
     * el agua. Sale uno como mucho por jugador y ciclo; true si ha salido.
     */
    private boolean intentarEspecial(Player p, int min, int max) {
        if (especiales.isEmpty()) return false;
        NamespacedKey aqui = p.getLocation().getBlock().getBiome().getKey();
        List<Apariciones.Especial> lista = Apariciones.candidatos(List.copyOf(especiales.values()),
                aqui.getKey(), aqui.asString(), tabla.containsKey(aqui.asString()));
        if (lista.isEmpty()) return false;
        Collections.shuffle(lista, random);
        for (Apariciones.Especial e : lista) {
            if (random.nextDouble() >= e.probabilidad()) continue;
            if (especialesCerca(p, e) >= e.tope()) continue;
            Location sitio = switch (e.entorno()) {
                case SUELO -> sitio(p, min, max, e.hueco());
                case AIRE -> sitioAire(p, min, max, e.alturaMinima(), e.alturaMaxima(), e.hueco());
                case AGUA -> sitioAgua(p, min, max, e.profundidad(), e.hueco());
            };
            if (sitio == null) continue;
            // El sitio cae a 20-40 bloques: puede ser ya otro bioma, y ahi no le toca.
            NamespacedKey alli = sitio.getBlock().getBiome().getKey();
            if (!e.valeEn(alli.getKey(), alli.asString(), tabla.containsKey(alli.asString()))) continue;
            if (invocarEspecial(p, e, sitio) != null) return true;
        }
        return false;
    }

    /**
     * Un especial en ese sitio: nace por invocarTipo como un destacado (nivel de destacado, marca
     * "destacado" para que el Grifo le pague MobCoins y XP de destacado, zona segura y lista de
     * vivos) y ademas lleva la marca de especial con su clave.
     */
    private LivingEntity invocarEspecial(Player p, Apariciones.Especial e, Location sitio) {
        AnomalyPlugin a = anomaly();
        String id = fichaEspecial.get(e.clave());
        MinionType tipo = a == null || id == null ? null : a.minions().type(id);
        // Si alguien le ha cambiado el bicho en /esb desde el arranque, no se planta un zombi en el aire.
        if (tipo == null || tipo.entity() != e.entidad()) return null;
        LivingEntity mob = invocarTipo(p, id, true, sitio);
        if (mob == null) return null;
        mob.getPersistentDataContainer().set(claveEspecial, PersistentDataType.STRING, e.clave());
        if (mob instanceof Creaking c) {
            /* Comprobado en Paper 26.1.2 (Creaking.hurtServer y tick): sin corazon (getHome null) es un
             * mob normal que se puede matar, y solo se deshace solo si tiene uno. spawnEntity nunca se
             * lo pone; si algun dia naciera con corazon seria inmortal, y la API no deja quitarselo. */
            if (c.getHome() != null) {
                vivos.remove(mob.getUniqueId());
                mob.remove();
                return null;
            }
            c.activate(p);
        }
        // Un ghast solo se fija en jugadores a menos de 4 bloques de su altura (Ghast.registerGoals), y
        // este nace de 8 a 16 por encima del suelo: sin darle el objetivo flotaria sin disparar.
        // Ese objetivo puesto a mano no se vuelve a elegir, asi que ZonaSpawn.onApuntar nunca lo
        // corta: si el jugador se mete en la zona spawn lo retiran alDisparar y retirarLejanos.
        if (mob instanceof Ghast g) g.setTarget(p);
        return mob;
    }

    /** Cuantos de ese especial hay vivos a menos de su radio-tope del jugador. */
    private int especialesCerca(Player p, Apariciones.Especial e) {
        int n = 0;
        double r2 = e.radioTope() * e.radioTope();
        for (UUID id : vivos) {
            Entity en = plugin.getServer().getEntity(id);
            if (en == null || !en.getWorld().equals(p.getWorld())) continue;
            if (!e.clave().equals(en.getPersistentDataContainer().get(claveEspecial, PersistentDataType.STRING))) continue;
            if (en.getLocation().distanceSquared(p.getLocation()) <= r2) n++;
        }
        return n;
    }

    /** Si ese mob o ese proyectil es de un especial: por su propia marca o por la de quien lo disparo. */
    private boolean deEspecial(Entity en) {
        if (en == null) return false;
        if (en.getPersistentDataContainer().has(claveEspecial, PersistentDataType.STRING)) return true;
        return en instanceof Projectile pr && pr.getShooter() instanceof LivingEntity tirador
                && tirador.getPersistentDataContainer().has(claveEspecial, PersistentDataType.STRING);
    }

    /**
     * Calamity 1.9.0 · Las bolas de fuego de un especial (el Ghast Carmesi) se marcan al salir y no
     * prenden fuego. La marca va en la bola: si un jugador la devuelve de un golpe, el tirador pasa
     * a ser el, y sin ella volveria a romper bloques.
     *
     * Y ninguna sale hacia la zona spawn. El ghast tiene el objetivo puesto a mano y no lo suelta
     * aunque el jugador entre en el spawn; desde fuera le seguiria tirando bolas (hasta 64 bloques,
     * con IGNEO encima). Como con los minijefes (Hardcore.vigilarPresas), a la zona spawn no le
     * sigue: la bola no sale y el especial se retira en humo.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alDisparar(ProjectileLaunchEvent e) {
        Projectile bola = e.getEntity();
        if (!(bola.getShooter() instanceof LivingEntity tirador)) return;
        String marca = tirador.getPersistentDataContainer().get(claveEspecial, PersistentDataType.STRING);
        if (marca == null) return;
        Player enSpawn = objetivoEnSpawn(tirador);
        if (enSpawn != null) {
            e.setCancelled(true);
            ((Mob) tirador).setTarget(null);
            // Al tick siguiente: ahora esta en mitad de su propio turno de IA.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!tirador.isValid()) return;
                vivos.remove(tirador.getUniqueId());
                puestoDe.remove(tirador.getUniqueId());
                retirarEnHumo(tirador, enSpawn);
            });
            return;
        }
        bola.getPersistentDataContainer().set(claveEspecial, PersistentDataType.STRING, marca);
        if (bola instanceof Explosive ex) ex.setIsIncendiary(false);
    }

    /**
     * El jugador al que persigue ese especial si esta en la zona spawn, o null. Solo mira a los
     * especiales: los demas mobs de Calamity eligen objetivo por su cuenta y ZonaSpawn.onApuntar
     * ya se lo cancela.
     */
    private Player objetivoEnSpawn(Entity e) {
        if (!(e instanceof Mob m) || !e.getPersistentDataContainer().has(claveEspecial, PersistentDataType.STRING)) return null;
        return m.getTarget() instanceof Player p && zonaSegura(p.getLocation()) ? p : null;
    }

    /**
     * Se deshace en humo y ceniza, como los mobs que se cuelan en el spawn (ZonaSpawn.retirar):
     * sin muerte, asi que no paga nada. Quien lo llama lo quita de vivos.
     */
    private void retirarEnHumo(Entity e, Player objetivo) {
        World w = e.getWorld();
        Location l = e.getLocation();
        Compat.spawn(w, Compat.LARGE_SMOKE, l.clone().add(0, 1, 0), 30, 0.8, 1, 0.8, 0.02);
        Compat.spawn(w, Compat.ASH, l.clone().add(0, 1, 0), 24, 0.8, 1, 0.8, 0.02);
        Compat.sound(w, l, "block.fire.extinguish", 0.6f, 1.2f);
        String marca = e.getPersistentDataContainer().get(claveEspecial, PersistentDataType.STRING);
        plugin.bitacora().anotar("spawn", "especial-retirado", marca == null ? "?" : marca, objetivo.getName(),
                l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
        e.remove();
    }

    /**
     * Calamity 1.9.0 · Una bola devuelta no mata al Ghast Carmesi de un golpe.
     *
     * Ghast.hurtServer (Paper 26.1.2) cambia el golpe de una bola grande que le devuelve un jugador
     * por 1000 fijos: caia de una sola bola fuera del nivel que fuera (a nivel 100 tiene 872 de
     * vida) y pagaba MobCoins y XP de destacado sin pelea. El 1000 pasa por este evento, asi que
     * aqui se cambia por la parte de su vida de bola-devuelta (el 15 %). La condicion es la misma
     * que la de vanilla (isReflectedFireball): la bola como golpe directo y un jugador detras.
     *
     * La misma bola le pega dos veces en el mismo tick, el impacto y su explosion, y las dos
     * valdrian 1000; la segunda se anula para que cada bola devuelta cuente una sola vez.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alDevolver(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Ghast g)) return;
        String marca = g.getPersistentDataContainer().get(claveEspecial, PersistentDataType.STRING);
        if (marca == null) return;
        DamageSource ds = e.getDamageSource();
        if (!(ds.getDirectEntity() instanceof LargeFireball bola) || !(ds.getCausingEntity() instanceof Player)) return;
        PersistentDataContainer pdc = bola.getPersistentDataContainer();
        String quien = g.getUniqueId().toString();
        if (quien.equals(pdc.get(claveBolaDevuelta, PersistentDataType.STRING))) {
            e.setCancelled(true);
            return;
        }
        pdc.set(claveBolaDevuelta, PersistentDataType.STRING, quien);
        Apariciones.Especial esp = especiales.get(marca);
        double fraccion = esp == null ? Apariciones.BOLA_DEVUELTA : esp.bolaDevuelta();
        e.setDamage(Apariciones.golpeDevuelto(Compat.getAttribute(g, "max_health", g.getHealth()), fraccion));
    }

    /** La explosion de algo de un especial no prende fuego (Paper avisa aqui justo antes de explotar). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alCebar(ExplosionPrimeEvent e) {
        if (deEspecial(e.getEntity())) e.setFire(false);
    }

    /**
     * Ni rompe bloques: la explosion sigue haciendo su dano a quien pille (y EDM lo escala por
     * nivel), pero el terreno de Calamity no se llena de crateres de ghast.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alExplotar(EntityExplodeEvent e) {
        if (!deEspecial(e.getEntity())) return;
        e.blockList().clear();
        e.setYield(0);
    }

    /**
     * Calamity 1.9.0 · La Fatiga minera del Guardian Anciano. En vanilla dura 5 minutos y llega a 50
     * bloques: con uno escondido en un lago te quedabas sin picar mucho despues de dejarlo atras.
     * Aqui dura fatiga-minera-segundos (60). Vanilla la renueva cada minuto mientras sigues cerca, asi
     * que aprieta igual mientras esta y se pasa enseguida cuando te alejas o lo matas.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alFatigar(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getCause() != EntityPotionEffectEvent.Cause.ATTACK) return;
        PotionEffect nueva = e.getNewEffect();
        if (nueva == null || !PotionEffectType.MINING_FATIGUE.equals(nueva.getType())) return;
        int tope = fatigaCerca(p) * 20;
        if (tope <= 0 || nueva.getDuration() <= tope) return;
        e.setCancelled(true);
        p.addPotionEffect(nueva.withDuration(tope));
    }

    /**
     * Los segundos de Fatiga del especial que la reparte, si hay uno a los 50 bloques del guardian
     * anciano vanilla (el mas largo si hay varios). 0 si no hay ninguno: la Fatiga viene de otro
     * sitio y no se toca.
     */
    private int fatigaCerca(Player p) {
        int segundos = 0;
        for (UUID id : vivos) {
            Entity en = plugin.getServer().getEntity(id);
            if (en == null || !en.getWorld().equals(p.getWorld())) continue;
            if (en.getLocation().distanceSquared(p.getLocation()) > 52 * 52) continue;
            String marca = en.getPersistentDataContainer().get(claveEspecial, PersistentDataType.STRING);
            Apariciones.Especial esp = marca == null ? null : especiales.get(marca);
            if (esp != null && esp.fatigaSegundos() > 0) segundos = Math.max(segundos, esp.fatigaSegundos());
        }
        return segundos;
    }

    /**
     * Lee mobs.biomas: por bioma, sus comunes (los que sean) y su destacado aparte. Calamity 1.9.0:
     * antes iba todo en una lista por posicion y un tercer comun se tomaba por el destacado.
     */
    private void cargarTabla() {
        tabla.clear();
        ConfigurationSection s = cfg().getConfigurationSection("biomas");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection b = s.getConfigurationSection(k);
            if (b == null || b.getString("bioma") == null) continue;
            Apariciones.Tabla t = Apariciones.Tabla.de(b.getStringList("comunes"), b.getString("destacado"));
            if (t != null) tabla.put(b.getString("bioma"), t);
        }
    }

    // ------------------------------------------------------ spawn natural y adopcion

    /**
     * Hostiles vanilla en un mundo de Lethal World. Los del spawn natural (y las patrullas)
     * se cancelan donde el bioma tiene mobs propios; los de spawners, divisiones y demas se
     * adoptan. Lo que invoca EDM o un comando (CUSTOM, COMMAND) no se toca.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alAparecer(CreatureSpawnEvent e) {
        LivingEntity mob = e.getEntity();
        if (!(mob instanceof Enemy) || !LethalWorldPlugin.esMundo(mob.getWorld())) return;
        switch (e.getSpawnReason()) {
            case CUSTOM, COMMAND, SPAWNER_EGG, DEFAULT, BUCKET -> {
                return;
            }
            case NATURAL, PATROL -> {
                Location donde = e.getLocation();
                if (tabla.containsKey(donde.getBlock().getBiome().getKey().asString())) {
                    e.setCancelled(true);
                    return;
                }
            }
            default -> {
            }
        }
        // Calamity 1.2: en la zona spawn no nace ningun hostil por su cuenta (spawners, refuerzos,
        // patrullas...). Lo que invoca un plugin o el staff ya ha salido arriba.
        if (zonaSegura(e.getLocation())) {
            e.setCancelled(true);
            return;
        }
        if (esAjeno(mob)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            AnomalyPlugin a = anomaly();
            Player p = masCercano(mob.getLocation(), 128);
            if (a != null && p != null && mob.isValid()) adoptar(a.minionManager(), mob, p);
        });
    }

    /**
     * Si un jugador cuenta para los mobs: los suyos aparecen, los de las estructuras se
     * adoptan con su nivel y el tope se mide contra el. El espectador nunca cuenta; el
     * creativo tampoco, salvo que se encienda en la config para probar volando.
     */
    private boolean cuenta(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR) return false;
        return p.getGameMode() != GameMode.CREATIVE || cfg().getBoolean("contar-creativo", false);
    }

    /**
     * Calamity 1.2: la zona spawn de un mundo hardcore (Hardcore.enSpawn, la region de WorldGuard o
     * la caja de la vara). Ahi no nace ni se adopta ningun mob de Lethal World. Fuera de los
     * mundos hardcore siempre es false.
     */
    private boolean zonaSegura(Location l) {
        Hardcore hc = plugin.hardcore();
        return hc != null && hc.enSpawn(l);
    }

    private Player masCercano(Location donde, double radio) {
        Player mejor = null;
        double d2 = radio * radio;
        for (Player p : donde.getWorld().getPlayers()) {
            if (!cuenta(p)) continue;
            double d = p.getLocation().distanceSquared(donde);
            if (d <= d2) {
                d2 = d;
                mejor = p;
            }
        }
        return mejor;
    }

    /** Los hostiles de alrededor sin nivel (los de las estructuras) se adoptan; a los ya adoptados se les repone el cartel. */
    private void adoptarCerca(MinionManager mm, Player p, double radio) {
        for (LivingEntity mob : p.getLocation().getNearbyLivingEntities(radio)) {
            if (!(mob instanceof Enemy) || mm.isMinion(mob) || mob.isDead()) continue;
            // Las amenazas de Calamity (PARCA, Eco) ya traen sus numeros: adoptarlas les
            // reescribiria vida, nombre y dano (MT sec. 0 D).
            if (Marcas.esAmenaza(mob)) continue;
            if (esAjeno(mob)) continue;
            // Calamity 1.2: lo que ya esta en la zona spawn no se hace nuestro (no se toca lo ajeno).
            if (zonaSegura(mob.getLocation())) continue;
            if (mm.adoptado(mob)) mm.reescoltar(mob);
            else adoptar(mm, mob, p);
        }
    }

    /**
     * Mobs que no son nuestros y que no se tocan: los de MythicMobs.
     *
     * Ya vienen con la dificultad que se les puso a mano al crearlos; si ademas se
     * adoptan, se les reescribe la vida y el dano por nivel y salen imposibles. Los
     * suyos aparecen con reason CUSTOM, asi que el evento no los ve, pero el barrido
     * de adoptarCerca si: aqui es donde se paran.
     */
    private boolean esAjeno(LivingEntity mob) {
        if (!cfg().getBoolean("adoptados.ignorar-mythicmobs", true)) return false;
        return PuenteMythicMobs.esMythicMob(mob);
    }

    private void adoptar(MinionManager mm, LivingEntity mob, Player p) {
        /* Ni el jefe de una anomalia, ni su cuerpo visible ni su tropa: son Enemy y
         * pasaban por aqui como un zombi de estructura. Adoptarlos les reescribia la
         * vida (de 3400 reescalada a la de un adoptado de nivel 20) y RAIZ moria en
         * tres golpes y saltaba a la ultima fase. Lo vio Dosa peleando en Calamity. */
        if (net.ederus.edm.comun.Tags.isOurs(mob)) return;
        // Doble cerrojo con adoptarCerca: alAparecer tambien llega aqui.
        if (Marcas.esAmenaza(mob)) return;
        if (esAjeno(mob)) return;
        if (mm.isMinion(mob) || mm.adoptado(mob) || mob.isInvulnerable()) return;
        ConfigurationSection s = cfg().getConfigurationSection("adoptados");
        if (s == null) s = new YamlConfiguration();
        Component nombre = mob.customName();
        String plano = nombre == null ? "" : PlainTextComponentSerializer.plainText().serialize(nombre);
        boolean minijefe = nombre != null && cfg().getStringList("minijefes.nombres").contains(plano);

        int distancia = distanciaDe(p);
        int nivel = nivelPara(p, false, distancia);
        double vida, dano;
        if (minijefe) {
            nivel = Math.min(cfg().getInt("nivel.maximo", 100), nivel + cfg().getInt("minijefes.extra-nivel", 10));
            vida = cfg().getDouble("minijefes.vida-base", 400) * (1 + cfg().getDouble("minijefes.vida-por-nivel", 0.10) * (nivel - 1));
            dano = cfg().getDouble("minijefes.dano-base", 1.5) * (1 + cfg().getDouble("minijefes.dano-por-nivel", 0.05) * (nivel - 1));
            nombre = nombre.color(NamedTextColor.RED);
        } else {
            double original = Compat.getAttribute(mob, "max_health", mob.getHealth());
            vida = Math.max(original, s.getDouble("vida-minima", 20)) * (1 + s.getDouble("vida-por-nivel", 0.08) * (nivel - 1));
            dano = 1 + s.getDouble("dano-por-nivel", 0.04) * (nivel - 1);
            if (nombre == null) nombre = Component.translatable(mob.getType().translationKey());
        }

        // El nombre pasa al cartel: dejarlo en el mob lo pintaria dos veces. Quitarlo haria
        // que vanilla lo pudiera despawnear, asi que se fija a mano.
        if (mob.customName() != null) {
            mob.customName(null);
            mob.setCustomNameVisible(false);
            mob.setRemoveWhenFarAway(false);
        }
        double escalaMax = cfg().getDouble("escala-maxima", 2.0);
        if (Compat.getAttribute(mob, "scale", 1.0) > escalaMax) Compat.setAttribute(mob, "scale", escalaMax);
        Compat.setAttribute(mob, "max_health", vida);
        mob.setHealth(Math.min(vida, Compat.getAttribute(mob, "max_health", vida)));
        sinQuemarse(mob);
        mob.getPersistentDataContainer().set(clave, PersistentDataType.STRING, minijefe ? "minijefe" : "estructura");
        apuntarDistancia(mob, distancia);
        mm.adoptar(mob, nivel, nombre, dano);
    }

    // ---------------------------------------------------------------- guarniciones

    private void cargarGuarniciones() {
        guarniciones.clear();
        ConfigurationSection s = cfg().getConfigurationSection("guarniciones");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection g = s.getConfigurationSection(k);
            if (g == null || g.getString("estructura") == null || g.getString("tipo") == null) continue;
            int min = Math.max(1, g.getInt("minimo", 2));
            guarniciones.put(g.getString("estructura"), new Guarnicion(g.getString("tipo"), min, Math.max(min, g.getInt("maximo", min))));
        }
    }

    /** Las estructuras con guarnicion que tiene cerca el jugador reciben su tropa si les toca. */
    private void guarnecer(Player p) {
        if (guarniciones.isEmpty()) return;
        double radio = cfg().getDouble("guarnicion-radio", 40);
        long ahora = System.currentTimeMillis();
        Chunk c = p.getLocation().getChunk();
        World w = p.getWorld();
        Set<String> vistos = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!w.isChunkLoaded(c.getX() + dx, c.getZ() + dz)) continue;
                for (GeneratedStructure gs : w.getChunkAt(c.getX() + dx, c.getZ() + dz).getStructures()) {
                    Guarnicion g = guarniciones.get(gs.getStructure().getKey().asString());
                    if (g == null) continue;
                    BoundingBox caja = gs.getBoundingBox();
                    String puesto = w.getName() + "@" + (int) caja.getCenterX() + "," + (int) caja.getCenterY() + "," + (int) caja.getCenterZ();
                    if (!vistos.add(puesto)) continue;
                    if (p.getLocation().toVector().distanceSquared(caja.getCenter()) > radio * radio + caja.getWidthX() * caja.getWidthZ()) continue;
                    Set<UUID> tropa = ocupadas.computeIfAbsent(puesto, k -> new HashSet<>());
                    tropa.removeIf(id -> {
                        Entity e = plugin.getServer().getEntity(id);
                        return e == null || !e.isValid();
                    });
                    if (!tropa.isEmpty() || ahora < proximaGuarnicion.getOrDefault(puesto, 0L)) continue;
                    int n = g.minimo() + random.nextInt(g.maximo() - g.minimo() + 1);
                    for (int i = 0; i < n; i++) {
                        Location sitio = sitioEn(w, caja);
                        if (sitio == null) continue;
                        LivingEntity mob = invocarTipo(p, g.tipo(), false, sitio);
                        if (mob == null) continue;
                        tropa.add(mob.getUniqueId());
                        puestoDe.put(mob.getUniqueId(), puesto);
                    }
                }
            }
        }
    }

    /** Suelo con dos de aire encima dentro de la caja de la estructura. */
    private Location sitioEn(World w, BoundingBox caja) {
        for (int intento = 0; intento < 12; intento++) {
            int x = (int) Math.floor(caja.getMinX() + 1 + random.nextDouble() * Math.max(1, caja.getWidthX() - 2));
            int z = (int) Math.floor(caja.getMinZ() + 1 + random.nextDouble() * Math.max(1, caja.getWidthZ() - 2));
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int techo = Math.min((int) caja.getMaxY(), w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES));
            for (int y = techo; y >= (int) caja.getMinY(); y--) {
                Block suelo = w.getBlockAt(x, y, z);
                if (!suelo.getType().isSolid()) continue;
                Block pies = w.getBlockAt(x, y + 1, z), cabeza = w.getBlockAt(x, y + 2, z);
                if (pies.isPassable() && cabeza.isPassable() && !pies.isLiquid() && !cabeza.isLiquid()) {
                    return new Location(w, x + 0.5, y + 1, z + 0.5);
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------- nivel

    /**
     * Nivel de un mob que sale para ese jugador: el nivel base con su variacion al azar,
     * mas el destacado y lo que sume Calamity. Publico: lo necesita el Eco (MT sec. 0 A).
     */
    public int nivelPara(Player p, boolean destacado) {
        return nivelPara(p, destacado, distanciaDe(p));
    }

    /**
     * Lo mismo con los niveles de distancia ya medidos (Hardcore.bonusDistancia): quien crea el
     * mob los mide una vez y los apunta tambien en su PDC, para cobrar con ellos al morir.
     */
    private int nivelPara(Player p, boolean destacado, int distancia) {
        ConfigurationSection n = cfg().getConfigurationSection("nivel");
        if (n == null) n = new YamlConfiguration();
        double base = nivelBase(p);
        double variacion = n.getDouble("variacion", 0.10);
        base *= 1 + (random.nextDouble() * 2 - 1) * variacion;
        if (destacado) base += n.getInt("extra-destacado", 5);
        // En los mundos hardcore la cordura y los minutos dentro suben el nivel.
        if (plugin.hardcore() != null) base += plugin.hardcore().bonusNivel(p);
        // 1.7: y lo lejos que este del spawn. Solo aqui: la PARCA y el Eco no pasan por nivelPara.
        base += Math.max(0, distancia);
        return (int) Math.max(1, Math.min(n.getInt("maximo", 100), Math.round(base)));
    }

    /** Niveles de distancia al spawn de ese jugador (0 fuera de Calamity). */
    private int distanciaDe(Player p) {
        Hardcore hc = plugin.hardcore();
        return hc == null ? 0 : hc.bonusDistancia(p);
    }

    private void apuntarDistancia(LivingEntity mob, int distancia) {
        if (distancia > 0) mob.getPersistentDataContainer().set(claveDistancia, PersistentDataType.STRING, String.valueOf(distancia));
    }

    /** Los niveles de distancia con los que nacio ese mob (0 si no lleva la marca). */
    public int distanciaDe(LivingEntity mob) {
        String v = mob == null ? null : mob.getPersistentDataContainer().get(claveDistancia, PersistentDataType.STRING);
        if (v == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * El nivel de un jugador SIN azar: rango x por-rango + poder / poder-por-nivel.
     *
     * La PARCA y el Eco fijan su nivel con esto (DIS sec. 0.4): con la variacion de nivelPara
     * dos llamadas seguidas daban numeros distintos y el mismo Eco saldria con otro nivel
     * al despertar que al nacer.
     */
    public int nivelBase(Player p) {
        return nivelBase(rango(p), poder(p));
    }

    /** La formula de nivelBase con los numeros ya leidos. Para el autotest y para /lw level. */
    public int nivelBase(int rango, int poder) {
        ConfigurationSection n = cfg().getConfigurationSection("nivel");
        if (n == null) n = new YamlConfiguration();
        double base = rango * n.getDouble("por-rango", 2.0)
                + poder / Math.max(1.0, n.getDouble("poder-por-nivel", 20.0));
        return (int) Math.round(base);
    }

    /**
     * N_C(p) de DIS: el nivel de Calamity de ese jugador, sin azar. nivelBase mas lo que
     * suman la cordura, los minutos dentro, la Racha y el Eclipse (Hardcore.bonusNivel),
     * entre 1 y el maximo.
     */
    public int nivelCalamity(Player p) {
        // Sin la distancia (Hardcore.bonusDistancia): lo usan la PARCA y la foto del Eco.
        int maximo = cfg().getInt("nivel.maximo", 100);
        int extra = plugin.hardcore() == null ? 0 : plugin.hardcore().bonusNivel(p);
        return Math.max(1, Math.min(maximo, nivelBase(p) + extra));
    }

    /** Lo que responde PlaceholderAPI al marcador del rango, tal cual. "" si no se puede leer. */
    String rangoCrudo(Player p) {
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Object r = papi.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class)
                    .invoke(null, p, cfg().getString("nivel.placeholder-rango", "%notranks_rank_number%"));
            return String.valueOf(r);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Rango de rankup (fork de NotRanks) por PlaceholderAPI. 0 si no se puede leer. */
    public int rango(Player p) {
        try {
            return Integer.parseInt(rangoCrudo(p).replaceAll("[^0-9]", ""));
        } catch (NumberFormatException t) {
            return 0;
        }
    }

    /** Poder de AuraSkills (suma de habilidades) por su API. 0 si no esta. */
    public int poder(Player p) {
        try {
            Class<?> api = Class.forName("dev.aurelium.auraskills.api.AuraSkillsApi");
            Object inst = api.getMethod("get").invoke(null);
            Object user = inst.getClass().getMethod("getUser", UUID.class).invoke(inst, p.getUniqueId());
            if (user == null) return 0;
            return (int) user.getClass().getMethod("getPowerLevel").invoke(user);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------- mobcoins

    /** Valor esperado por muerte de cada mob en la tabla de UltimateMobCoins del Survival. */
    private void cargarMonedas() {
        baseMonedas.clear();
        File f = new File(plugin.getServer().getPluginsFolder(), "UltimateMobCoins/mobcoins.yml");
        if (!f.isFile()) {
            plugin.getLogger().warning("[Lethal World] No encuentro UltimateMobCoins/mobcoins.yml: los mobs pagan la base mínima.");
            return;
        }
        ConfigurationSection s = YamlConfiguration.loadConfiguration(f).getConfigurationSection("mobCoinDrops");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            double chance = s.getDouble(k + ".chance", 0) / 100.0;
            String cant = s.getString(k + ".amount", "0");
            double media;
            try {
                String[] p = cant.split("-");
                media = p.length == 2 ? (Double.parseDouble(p[0].trim()) + Double.parseDouble(p[1].trim())) / 2 : Double.parseDouble(cant.trim());
            } catch (NumberFormatException e) {
                media = 0;
            }
            baseMonedas.put(k.toLowerCase(Locale.ROOT), chance * media);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alMorir(EntityDeathEvent e) {
        LivingEntity mob = e.getEntity();
        String clase = mob.getPersistentDataContainer().get(clave, PersistentDataType.STRING);
        if (clase == null) return;
        /* 1.9.0 · Los especiales no sueltan su botin vanilla (Dosa): la esponja y la plantilla de
         * marea del Guardian Anciano o las lagrimas del ghast, que se confundirian con las Esencias,
         * serian una fuente de objetos aparte en un mundo de cofres vacios. Se vacia antes del Grifo,
         * que despues anade lo suyo. */
        if (mob.getPersistentDataContainer().has(claveEspecial, PersistentDataType.STRING)) e.getDrops().clear();
        vivos.remove(mob.getUniqueId());
        String puesto = puestoDe.remove(mob.getUniqueId());
        if (puesto != null) {
            proximaGuarnicion.put(puesto, System.currentTimeMillis() + cfg().getLong("guarnicion-reaparece-minutos", 20) * 60_000L);
        }
        // Los minijefes de antes de quitar el contorno aun lo llevan en el marcador.
        if ("minijefe".equals(clase)) Glow.clear(mob);
        Player asesino = mob.getKiller();

        /* En Calamity lo que da un mob lo decide el Grifo (DIS M2): cosecha de la PARCA,
         * spawners, cuota de jugador, MobCoins por la Aduana, Esencias y Reliquias. Aqui ya
         * no se paga nada: si se pagara tambien, cada muerte cobraria dos veces. El Grifo
         * recibe tambien las muertes sin asesino (el minijefe reparte por dano). */
        Hardcore hc = plugin.hardcore();
        if (hc != null && hc.activo() && hc.grifo() != null && hc.esHardcore(mob.getWorld())) {
            hc.grifo().alMorir(e, asesino, clase);
            return;
        }
        if (asesino == null) return;
        pagarComoHoy(e, asesino, clase);
    }

    /**
     * Lo que pagaba un mob de Lethal World hasta la 1.1.0: la tabla de UltimateMobCoins
     * por nivel y por clase, por MobCoins de EDM. Sigue siendo el pago fuera de
     * Calamity; dentro solo lo usa el Grifo mientras sea el esqueleto de WP0.
     * 1.7.6: por pagarPorBaja, con el boost de MobCoins de quien lo mata (EDM 1.74.0).
     */
    public void pagarComoHoy(EntityDeathEvent e, Player asesino, String clase) {
        LivingEntity mob = e.getEntity();
        AnomalyPlugin a = anomaly();
        int nivel = a == null ? 1 : Math.max(1, a.minionManager().levelOf(mob));
        long pago = mobcoinsDe(mob, clase, nivel);
        net.ederus.edm.comun.MobCoins.pagarPorBaja(plugin, asesino, pago);
    }

    /**
     * MobCoins de un mob: lo que paga en el Survival x (1 + nivel / divisor) x mundo x clase
     * x (1 + niveles de distancia x hardcore.distancia.mobcoins-por-nivel). Sin azar.
     * La distancia es la que el mob apunto al nacer, no la de quien lo mata; con nivel 0 (la via
     * CERRADO del Grifo: jaulas y mobs sin dano del jugador, que pagan la tabla sin nivel) tampoco
     * cuenta la distancia. El minijefe con multiplicador-minijefe a 0 (config de la 1.1.0: su pago es
     * el fijo mobs.mobcoins.minijefe, que reparte el Grifo) vale aqui el punto medio del
     * fijo, para que fuera de Calamity no pase a pagar cero.
     */
    public long mobcoinsDe(LivingEntity mob, String marca, int nivel) {
        ConfigurationSection m = cfg().getConfigurationSection("mobcoins");
        if (m == null) m = new YamlConfiguration();
        if ("minijefe".equals(marca) && m.getDouble("multiplicador-minijefe", 10.0) <= 0) {
            return Math.round((m.getDouble("minijefe.min", 80) + m.getDouble("minijefe.max", 120)) / 2.0);
        }
        double base = Math.max(m.getDouble("base-minima", 0.5),
                baseMonedas.getOrDefault(mob.getType().getKey().getKey(), 0.0));
        double extra = switch (marca == null ? "" : marca) {
            case "destacado" -> m.getDouble("multiplicador-destacado", 3.0);
            case "minijefe" -> m.getDouble("multiplicador-minijefe", 10.0);
            default -> 1.0;
        };
        int distancia = nivel > 0 ? distanciaDe(mob) : 0;
        double porNivel = plugin.hardcore() == null ? 0 : plugin.hardcore().mobcoinsPorNivelDistancia();
        return net.ederus.calamity.hardcore.Distancia.mobcoins(base, nivel, m.getDouble("nivel-divisor", 20),
                m.getDouble("multiplicador-mundo", 1.5), extra, distancia, porNivel);
    }

    private void retirarLejanos() {
        double r = cfg().getDouble("retirar-a", 96);
        double r2 = r * r;
        for (var it = vivos.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            Entity e = plugin.getServer().getEntity(id);
            if (e == null || !e.isValid()) {
                it.remove();
                puestoDe.remove(id);
                continue;
            }
            // Calamity 1.9.0: un especial cuya presa esta en la zona spawn no se queda fuera
            // esperandola (el ghast la tiene fijada a mano y el guardian la alcanza con su rayo).
            Player enSpawn = objetivoEnSpawn(e);
            if (enSpawn != null) {
                retirarEnHumo(e, enSpawn);
                it.remove();
                puestoDe.remove(id);
                continue;
            }
            boolean alguien = false;
            for (Player p : e.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(e.getLocation()) <= r2) {
                    alguien = true;
                    break;
                }
            }
            if (!alguien) {
                e.remove();
                it.remove();
                puestoDe.remove(id);
            }
        }
    }

    // ---------------------------------------------------------------------- siembra

    private record Semilla(String nombre, EntityType tipo, boolean destacado, int color, MinionAbility... habilidades) {
    }

    private record Bioma(String id, Semilla comun1, Semilla comun2, Semilla destacado) {
    }

    private static Semilla c(String n, EntityType t, int color, MinionAbility... h) {
        return new Semilla(n, t, false, color, h);
    }

    private static Semilla d(String n, EntityType t, int color, MinionAbility... h) {
        return new Semilla(n, t, true, color, h);
    }

    /**
     * Los cinco minijefes de Calamity.
     *
     * No son esbirros grandes: son la recompensa del mundo hardcore, lo que viene a
     * buscarte cuando se te acaba la cordura. La vida y el dano de verdad se los pone
     * el multiplicador de la config (hardcore.minijefes), no estos numeros: aqui solo
     * se define QUE son y como se ven, para que Dosa pueda tocarlos desde /esb.
     */
    private record Minijefe(String nombre, EntityType tipo, int color, double escala,
                            MinionAbility... habilidades) {
        /* La escala se aplica al invocarlo (Compat "scale"), no en la presencia:
         * el tamano es cosa de la entidad concreta, no del tipo guardado. */
    }

    private static final List<Minijefe> MINIJEFES = List.of(
            new Minijefe("Custodio de las Ruinas", EntityType.IRON_GOLEM, 0x9BA7A0, 1.4,
                    MinionAbility.ACORAZADO, MinionAbility.ALARMA, MinionAbility.ESPINAS),
            new Minijefe("Matriarca Tejedora", EntityType.CAVE_SPIDER, 0x6B4A6B, 1.8,
                    MinionAbility.VENENOSO, MinionAbility.DIVISION, MinionAbility.AGIL),
            new Minijefe("Heraldo Carmesí", EntityType.RAVAGER, 0xC23B3B, 1.3,
                    MinionAbility.BERSERK, MinionAbility.IGNEO),
            new Minijefe("Sanador del Fango", EntityType.EVOKER, 0x6E8C5A, 1.2,
                    MinionAbility.CURANDERO, MinionAbility.ALARMA, MinionAbility.FLECHA_HELADA),
            new Minijefe("Centinela de Toba", EntityType.WITHER_SKELETON, 0x7A7268, 1.5,
                    MinionAbility.FLECHA_PESADA, MinionAbility.ACORAZADO, MinionAbility.BERSERK));

    private static final List<Bioma> PANACEA = List.of(
            new Bioma("honeybee_biome", c("Apicultor Picado", EntityType.ZOMBIE, 0xE8B923, MinionAbility.VENENOSO),
                    c("Abejorro Asesino", EntityType.VEX, 0xF2C200, MinionAbility.AGIL),
                    d("Reina de la Colmena", EntityType.WITCH, 0xFFB000, MinionAbility.CURANDERO)),
            new Bioma("horsetail_tropics", c("Cazador Tropical", EntityType.SKELETON, 0x5E8C31, MinionAbility.FLECHA_PESADA),
                    c("Araña de Helecho", EntityType.SPIDER, 0x4F7942, MinionAbility.VENENOSO),
                    d("Tótem Selvático", EntityType.VINDICATOR, 0x8B5A2B, MinionAbility.BERSERK)),
            new Bioma("quicksand_springs", c("Ahogado de Arena", EntityType.HUSK, 0xC2A36B, MinionAbility.ACORAZADO),
                    c("Escorpión del Manantial", EntityType.CAVE_SPIDER, 0xB08D57, MinionAbility.AGIL),
                    d("Jabalí Hundido", EntityType.HOGLIN, 0x9C6B3C, MinionAbility.BERSERK)),
            new Bioma("condemned_taiga", c("Leñador Condenado", EntityType.VINDICATOR, 0x4B3621),
                    c("Espectro de Pino", EntityType.STRAY, 0x7FA3A8, MinionAbility.FLECHA_HELADA),
                    d("Profeta Condenado", EntityType.EVOKER, 0x3B4A3F)),
            new Bioma("polypore_plains", c("Hongo Andante", EntityType.SLIME, 0xA0522D, MinionAbility.DIVISION),
                    c("Recolector Micótico", EntityType.WITCH, 0x8E6E53, MinionAbility.VENENOSO),
                    d("Guardián de Esporas", EntityType.PIGLIN_BRUTE, 0x6D4C41, MinionAbility.ACORAZADO)),
            new Bioma("wildflower_bog", c("Espantapájaros", EntityType.SKELETON, 0xC9A66B, MinionAbility.FLECHA_PESADA),
                    c("Sanguijuela", EntityType.SILVERFISH, 0x556B2F, MinionAbility.AGIL),
                    d("Bruja de la Ciénaga", EntityType.WITCH, 0x7B9F35, MinionAbility.CURANDERO)),
            new Bioma("ravenous_greenwood", c("Devorador", EntityType.ZOMBIE, 0x2E5E1E, MinionAbility.BERSERK),
                    c("Acechador Verde", EntityType.SPIDER, 0x3C8D2F, MinionAbility.AGIL),
                    d("Raíz Voraz", EntityType.RAVAGER, 0x3E2B1D, MinionAbility.BERSERK)),
            new Bioma("sweltering_swamp", c("Piglin del Fango", EntityType.PIGLIN, 0x6B5B3E),
                    c("Creeper Sofocante", EntityType.CREEPER, 0x8FBC5A, MinionAbility.IGNEO),
                    d("Bruto del Pantano", EntityType.PIGLIN_BRUTE, 0x5C4A2E, MinionAbility.ACORAZADO)),
            new Bioma("creeper_dominion", c("Creeper Dominado", EntityType.CREEPER, 0x5DAA3C, MinionAbility.AGIL),
                    c("Lodo Explosivo", EntityType.SLIME, 0x6BBF3A, MinionAbility.DIVISION),
                    d("Creeper Soberano", EntityType.CREEPER, 0x2F7D32, MinionAbility.ACORAZADO, MinionAbility.ALARMA)),
            new Bioma("crimson_organism", c("Carne Carmesí", EntityType.ZOGLIN, 0xB3202A, MinionAbility.BERSERK),
                    c("Espora Roja", EntityType.MAGMA_CUBE, 0xD7263D, MinionAbility.DIVISION),
                    d("Corazón Carmesí", EntityType.RAVAGER, 0x8B0000, MinionAbility.IGNEO, MinionAbility.CURANDERO)),
            new Bioma("bamboo_valley", c("Guerrero de Bambú", EntityType.VINDICATOR, 0x7BA05B, MinionAbility.AGIL),
                    c("Arquero de Bambú", EntityType.PILLAGER, 0x9CB86F, MinionAbility.FLECHA_PESADA),
                    d("Maestro del Valle", EntityType.VINDICATOR, 0x4E7A2A, MinionAbility.BERSERK, MinionAbility.ACORAZADO)),
            new Bioma("hungering_jungle", c("Caníbal de la Jungla", EntityType.ZOMBIE, 0x6B3E26, MinionAbility.BERSERK),
                    c("Tejedora", EntityType.SPIDER, 0x4A2F1F, MinionAbility.VENENOSO),
                    d("Ídolo Hambriento", EntityType.RAVAGER, 0x5B3A1E, MinionAbility.BERSERK)),
            new Bioma("conure_conclave", c("Cotorra Fantasma", EntityType.PHANTOM, 0x3FBF7F, MinionAbility.AGIL),
                    c("Chamán del Cónclave", EntityType.WITCH, 0x2E8B57),
                    d("Gran Guacamayo", EntityType.PHANTOM, 0xFF4500, MinionAbility.ALARMA)));

    /** Estructuras vacias de Panacea y la tropa que las guarda. */
    private record Puesto(String estructura, String tipo, int minimo, int maximo) {
    }

    private static final List<Puesto> GUARNICIONES = List.of(
            new Puesto("bracken:dweller_drill", "Leñador Condenado", 3, 4),
            new Puesto("bracken:outlander_tent", "Espantapájaros", 2, 3),
            new Puesto("bracken:panacea_hut", "Caníbal de la Jungla", 2, 3));

    /** Crea la carpeta y los tipos que falten, y las tablas si no hay. Nunca pisa lo editado. */
    private void sembrar(MinionRegistry reg) {
        String nombreCarpeta = "Lethal World · Panacea";
        MinionCategory carpeta = null;
        for (MinionCategory c : reg.categories()) {
            if (c.display().equals(nombreCarpeta)) carpeta = c;
        }
        if (carpeta == null) {
            carpeta = reg.createCategory(nombreCarpeta);
            carpeta.icon(Material.MOSS_BLOCK);
            carpeta.colorRgb(0xE0664A);
        }

        ConfigurationSection biomas = plugin.getConfig().getConfigurationSection("mobs.biomas");
        boolean escribirTabla = biomas == null || biomas.getKeys(false).isEmpty();
        boolean guardar = escribirTabla;
        int creados = 0;
        for (Bioma b : PANACEA) {
            List<String> ids = new ArrayList<>();
            for (Semilla s : List.of(b.comun1(), b.comun2(), b.destacado())) {
                MinionType t = buscar(reg, s.nombre());
                if (t == null) {
                    t = reg.createType(s.nombre(), carpeta.id());
                    configurar(t, s);
                    creados++;
                }
                ids.add(t.id());
            }
            if (escribirTabla) {
                String ruta = "mobs.biomas.panacea_" + b.id();
                plugin.getConfig().set(ruta + ".bioma", "bracken:panacea/" + b.id());
                plugin.getConfig().set(ruta + ".comunes", List.of(ids.get(0), ids.get(1)));
                plugin.getConfig().set(ruta + ".destacado", ids.get(2));
            }
        }
        guardar |= sembrarMinijefes(reg);

        ConfigurationSection gs = plugin.getConfig().getConfigurationSection("mobs.guarniciones");
        if (gs == null || gs.getKeys(false).isEmpty()) {
            for (Puesto g : GUARNICIONES) {
                MinionType t = buscar(reg, g.tipo());
                if (t == null) continue;
                String ruta = "mobs.guarniciones." + g.estructura().substring(g.estructura().indexOf(':') + 1);
                plugin.getConfig().set(ruta + ".estructura", g.estructura());
                plugin.getConfig().set(ruta + ".tipo", t.id());
                plugin.getConfig().set(ruta + ".minimo", g.minimo());
                plugin.getConfig().set(ruta + ".maximo", g.maximo());
                guardar = true;
            }
        }
        // Sin contorno: con varios destacados a la vez brillaban demasiado. Se quita una sola
        // vez a los ya sembrados; si alguien se lo vuelve a poner en /esb, se respeta.
        if (!plugin.getConfig().getBoolean("mobs.migraciones.sin-contorno")) {
            boolean quitado = false;
            for (Bioma b : PANACEA) {
                MinionType t = buscar(reg, b.destacado().nombre());
                if (t != null && t.presence().outline() != null) {
                    t.presence().outline(null);
                    quitado = true;
                }
            }
            if (quitado) reg.save();
            plugin.getConfig().set("mobs.migraciones.sin-contorno", true);
            guardar = true;
        }
        if (plugin.getConfig().getStringList("mobs.minijefes.nombres").isEmpty()) {
            plugin.getConfig().set("mobs.minijefes.nombres", List.of("Creeper Gigatón", "Ventiarbusto Latente"));
            guardar = true;
        }
        if (creados > 0) reg.save();
        if (guardar) plugin.saveConfig();
        plugin.getLogger().info("[Lethal World] Mobs: " + PANACEA.size() * 3 + " tipos en /esb (" + creados + " nuevos), "
                + GUARNICIONES.size() + " estructuras con guarnición.");
    }

    private static MinionType buscar(MinionRegistry reg, String nombre) {
        for (MinionType t : reg.types()) if (t.display().equals(nombre)) return t;
        return null;
    }

    /**
     * Calamity 1.9.0 · Lee mobs.especiales y deja lista la ficha de /esb de cada uno, en la carpeta
     * "Lethal World · Especiales". La ficha se busca por su nombre visible (el id lo inventa EDM a
     * partir del nombre) y, si falta, se crea con su color, su aura de destacado y sus habilidades;
     * a partir de ahi el aspecto, las habilidades y el botin se tocan en /esb.
     *
     * La entidad, la vida y el dano, en cambio, los manda la config y se copian a la ficha en cada
     * arranque: EDM le pisa la vida vanilla al bicho con la de su ficha (un guardian anciano con la
     * vida de un zombi no asusta a nadie), y desde /esb no se puede volver a elegir un creaking.
     * La seccion se lee sin crearla (Apariciones.seccion) y solo se guarda el registro de EDM, nunca
     * el config del servidor.
     */
    private void cargarEspeciales(MinionRegistry reg) {
        especiales.clear();
        fichaEspecial.clear();
        ConfigurationSection s = Apariciones.seccion(plugin.getConfig(), "mobs.especiales");
        if (s == null || !s.getBoolean("activos", true)) {
            plugin.getLogger().info("[Lethal World] Mobs especiales apagados.");
            return;
        }
        List<String> avisos = new ArrayList<>();
        List<Apariciones.Especial> leidos = Apariciones.leer(s, avisos);
        for (String aviso : avisos) plugin.getLogger().warning("[Lethal World] mobs.especiales." + aviso);
        if (leidos.isEmpty()) return;

        MinionCategory carpeta = carpeta(reg, "Lethal World · Especiales", Material.PALE_OAK_LOG, Paleta.CARPETA_ESPECIALES);
        int creados = 0;
        boolean cambios = false;
        for (Apariciones.Especial e : leidos) {
            MinionType t = buscar(reg, e.nombre());
            if (t == null) {
                t = reg.createType(e.nombre(), carpeta.id());
                int color = Apariciones.colorDe(e.entidad());
                t.colorRgb(color);
                for (String h : e.habilidades()) {
                    MinionAbility m = habilidad(h);
                    if (m == null) {
                        plugin.getLogger().warning("[Lethal World] mobs.especiales." + e.clave()
                                + ": la habilidad '" + h + "' no existe en EDM.");
                    } else if (!t.has(m)) {
                        t.toggle(m);
                    }
                }
                MinionPresence look = t.presence();
                look.featured(true);
                look.auraName("DUST");
                look.auraColor(color);
                creados++;
            }
            cambios |= ajustar(t, e);
            especiales.put(e.clave(), e);
            fichaEspecial.put(e.clave(), t.id());
        }
        if (creados > 0 || cambios) reg.save();
        plugin.getLogger().info("[Lethal World] Mobs especiales: " + String.join(", ", nombres(leidos))
                + " (" + creados + " fichas nuevas en /esb).");
    }

    private static List<String> nombres(List<Apariciones.Especial> l) {
        List<String> out = new ArrayList<>();
        for (Apariciones.Especial e : l) out.add(e.nombre());
        return out;
    }

    /** La carpeta de /esb con ese nombre; si no esta, se crea con su icono y su color. */
    private static MinionCategory carpeta(MinionRegistry reg, String nombre, Material icono, int color) {
        for (MinionCategory c : reg.categories()) if (c.display().equals(nombre)) return c;
        MinionCategory c = reg.createCategory(nombre);
        c.icon(icono);
        c.colorRgb(color);
        return c;
    }

    /** Una habilidad de los esbirros de EDM por su nombre (ESPINAS, igneo...), o null. */
    private static MinionAbility habilidad(String nombre) {
        try {
            return MinionAbility.valueOf(nombre.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Copia a la ficha la entidad, la vida y el dano de la config. true si ha cambiado algo. */
    private static boolean ajustar(MinionType t, Apariciones.Especial e) {
        boolean cambio = false;
        if (t.entity() != e.entidad()) {
            t.entity(e.entidad());
            cambio = true;
        }
        if (distinto(t.baseHealth(), e.vidaBase())) {
            double antes = t.baseHealth();
            t.baseHealth(e.vidaBase());
            cambio |= distinto(antes, t.baseHealth());
        }
        if (distinto(t.healthGrowth(), e.vidaPorNivel())) {
            double antes = t.healthGrowth();
            t.healthGrowth(e.vidaPorNivel());
            cambio |= distinto(antes, t.healthGrowth());
        }
        if (distinto(t.baseDamage(), e.danoBase())) {
            double antes = t.baseDamage();
            t.baseDamage(e.danoBase());
            cambio |= distinto(antes, t.baseDamage());
        }
        if (distinto(t.damageGrowth(), e.danoPorNivel())) {
            double antes = t.damageGrowth();
            t.damageGrowth(e.danoPorNivel());
            cambio |= distinto(antes, t.damageGrowth());
        }
        return cambio;
    }

    private static boolean distinto(double a, double b) {
        return Math.abs(a - b) > 1e-9;
    }

    /**
     * Crea los cinco minijefes en su propia carpeta y apunta sus ids en la config.
     *
     * Los ids los genera el registro a partir del nombre, asi que no se pueden
     * escribir a mano en el config de fabrica: se escriben aqui la primera vez y a
     * partir de ahi Dosa los edita en /esb como cualquier otro.
     */
    private boolean sembrarMinijefes(MinionRegistry reg) {
        String nombreCarpeta = "Lethal World · Minijefes";
        MinionCategory carpeta = null;
        for (MinionCategory c : reg.categories()) {
            if (c.display().equals(nombreCarpeta)) carpeta = c;
        }
        if (carpeta == null) {
            carpeta = reg.createCategory(nombreCarpeta);
            carpeta.icon(Material.WITHER_SKELETON_SKULL);
            carpeta.colorRgb(0x8B1A1A);
        }

        List<String> ids = new ArrayList<>();
        for (Minijefe m : MINIJEFES) {
            MinionType t = buscar(reg, m.nombre());
            if (t == null) {
                t = reg.createType(m.nombre(), carpeta.id());
                t.entity(m.tipo());
                t.colorRgb(m.color());
                // 1.8.4: sin negrita. El cartel lo repinta CartelesMinijefe, pero la ficha nueva ya nace sin ella.
                t.baseHealth(120);
                t.healthGrowth(0.12);
                t.baseDamage(1.6);
                t.damageGrowth(0.06);
                t.mobcoins(150, 400);
                for (MinionAbility h : m.habilidades()) if (!t.has(h)) t.toggle(h);
                MinionPresence look = t.presence();
                look.featured(true);
                look.auraName("DUST");
                look.auraColor(m.color());
            }
            ids.add(t.id());
        }

        List<String> yaPuestos = plugin.getConfig().getStringList("hardcore.minijefes.tipos");
        if (yaPuestos.isEmpty() || reg.type(yaPuestos.get(0)) == null) {
            plugin.getConfig().set("hardcore.minijefes.tipos", ids);
            return true;
        }
        return false;
    }

    private static void configurar(MinionType t, Semilla s) {
        t.entity(s.tipo());
        t.colorRgb(s.color());
        for (MinionAbility h : s.habilidades()) if (!t.has(h)) t.toggle(h);
        MinionPresence look = t.presence();
        if (s.destacado()) {
            t.bold(true);
            t.baseHealth(80);
            t.healthGrowth(0.10);
            t.baseDamage(1.3);
            t.damageGrowth(0.05);
            look.featured(true);
            look.auraName("DUST");
            look.auraColor(s.color());
        } else {
            t.baseHealth(24);
            t.healthGrowth(0.08);
            t.baseDamage(1.0);
            t.damageGrowth(0.04);
        }
        boolean humanoide = switch (s.tipo()) {
            case ZOMBIE, HUSK, SKELETON, STRAY, VINDICATOR, PILLAGER, PIGLIN, PIGLIN_BRUTE, EVOKER -> true;
            default -> false;
        };
        if (humanoide) {
            look.gear().put(MinionPresence.Slot.PECHERA, Material.LEATHER_CHESTPLATE);
            look.gear().put(MinionPresence.Slot.BOTAS, Material.LEATHER_BOOTS);
            if (s.destacado()) look.gear().put(MinionPresence.Slot.CASCO, Material.LEATHER_HELMET);
            look.gearColor(s.color());
        }
    }
}
