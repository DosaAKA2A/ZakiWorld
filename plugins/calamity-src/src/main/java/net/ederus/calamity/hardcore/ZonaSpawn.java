package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Calamity 1.2 · La zona spawn: donde Calamity no hace nada salvo la Grieta.
 *
 * Dosa: "que en ella no aparezcan mobs ni nada; lo unico que puede aparecer es la grieta si
 * alguien se queda AFK". Hasta la 1.1.0 la unica zona que conocia Calamity era la caja de la
 * vara (hardcore.puertas.spawn), y solo para la Grieta; el spawn de verdad lo protegia
 * WorldGuard (region "calamity" en calamity2: mob-spawning deny, pvp deny, invincible) sin que
 * Calamity lo supiera, y dentro seguian corriendo su ciclo de mobs, la cordura, las
 * alucinaciones y la PARCA.
 *
 * De donde sale, por mundo hardcore y en este orden:
 *   1. la region de WorldGuard que diga hardcore.spawn.region (cuboide o poligono), si
 *      WorldGuard esta y ese mundo la tiene;
 *   2. la caja de la vara (/calamidad define spawn), si es de ese mundo;
 *   3. ninguna: ese mundo no tiene spawn seguro.
 * WorldGuard se lee por reflexion (no hay jar suyo en _toolchain/libs) y solo al refrescar: cada
 * CADA_MS se vuelve a mirar, asi que un /rg redefine o un /calamidad define spawn se nota sin
 * reiniciar, y lo que se pregunta en cada evento (dentro()) es una busqueda en un mapa y seis
 * comparaciones.
 *
 * Tambien retira a los mobs de Calamity que se cuelan (hardcore.spawn.retirar-mobs): una vez por
 * segundo, en humo, y sin tocar a nadie mas. Ni NPCs (Citizens), ni mobs sin la marca de
 * MobsLethal, ni las amenazas: la PARCA y el Eco tienen su propia salida (Parca.alEntrarSpawn,
 * Eco.segundo). Y esos mismos mobs no toman como objetivo a nadie que este dentro.
 *
 * Calamity 1.10 · Los minijefes ya no se borran por la zona (hardcore.spawn.minijefes). Dosa:
 * "tengo miedo de que quien arrastre a un mob dentro de la zona del spawn lo mate
 * instantaneamente; que esto no pase con minijefes". Hasta la 1.9, meter un minijefe en la plaza,
 * o meterse en ella con uno detras, lo deshacia en humo: era la forma de quitarselo de encima sin
 * pelear. Ahora el que se cuela se saca al borde (alColarse, sacar): por el lado de su objetivo o de
 * su presa si estan fuera, y si no por el mas cercano a el (referencia), para que el que cruza la
 * plaza persiguiendo a alguien no rebote en ella. Y el que persigue a alguien que entra le espera
 * fuera (Hardcore.vigilarPresas, con espera y acercarAlBorde). Los demas mobs se siguen retirando
 * como siempre, y los especiales de MobsLethal tienen su propia retirada.
 *
 * Y desde dentro no se hace dano a lo de Calamity (hardcore.spawn.sin-dano-desde-dentro): sus mobs
 * no pueden apuntar a quien esta en la zona, asi que pegarles desde ella (a mano, con arco, tridente
 * o pociones) era una pelea en la que solo pegaba uno; con un minijefe esperando en el borde, ademas,
 * se le podria matar sin riesgo. Igual con las amenazas. El PvP no se toca aqui: lo decide
 * WorldGuard (y Hardcore.onFuegoAmigo).
 *
 * El nucleo (Zona, enPoligono, elegir, deVara, fuera, alColarse, referencia, alBorde, espera,
 * seCansa, lejos) es estatico y sin Bukkit: el autotest "zona-spawn" lo prueba.
 */
final class ZonaSpawn implements Listener {

    /** De donde sale una zona. */
    static final String REGION = "region", VARA = "vara";
    /** Cada cuanto se vuelve a leer WorldGuard y la vara. */
    static final long CADA_MS = 5_000;
    /** Calamity 1.10 · A cuantos bloques del borde se deja a un minijefe que se ha colado (sacar). */
    static final double MARGEN_SACAR = 3;
    /** Calamity 1.10 · A cuantos bloques del borde espera un minijefe a su presa (acercarAlBorde). */
    static final double MARGEN_ESPERA = 4;
    /** Calamity 1.10 · Como mucho un "Desde el spawn no puedes atacar" cada tanto a cada uno. */
    static final long AVISO_ATAQUE_MS = 3_000;

    /** Calamity 1.10 · Lo que se hace con un mob de Calamity que esta dentro de la zona (alColarse). */
    enum Accion {
        /** Se deshace en humo, como siempre (retirar). */
        RETIRAR,
        /** Se le lleva al sitio de fuera mas cercano (sacar). */
        SACAR
    }

    /**
     * Calamity 1.10 · Lo que toca cada segundo a un minijefe de cordura cero segun donde este su
     * presa (espera; lo hace Hardcore.vigilarPresas).
     */
    enum Espera {
        /** Ella esta fuera y el no la esperaba: lo de siempre, objetivo y distancia maxima. */
        SIGUE,
        /** Ella acaba de entrar: deja de perseguirla, se queda en el borde y se le avisa. */
        EMPIEZA,
        /** Ella sigue dentro: el sigue en el borde, sin objetivo forzado. */
        ESPERA,
        /** Ha esperado mas de espera-segundos seguidos: se va sin dejar nada. */
        SE_CANSA,
        /** Ella ha salido: vuelve por ella con lo de siempre. */
        VUELVE,
        /** spawn.minijefes.esperan en false: se retira en humo en cuanto ella entra, como hasta la 1.9. */
        SE_RETIRA
    }

    /*
     * La clase que MobsLethal pone a sus mobs ("edm:lethal_world_mob" = comun, destacado,
     * minijefe, estructura). Es la que dice que un mob es de Calamity: los demas no se tocan.
     */
    private static final NamespacedKey MOB_LETHAL = new NamespacedKey("edm", "lethal_world_mob");

    /**
     * Una zona ya leida. La caja va en bloques con los bordes incluidos, como WorldGuard y la
     * vara. Con px/pz es un poligono de WorldGuard: la caja es la suya y ademas hay que caer
     * dentro del poligono.
     */
    record Zona(String origen, String nombre, String mundo, int x1, int y1, int z1, int x2, int y2, int z2,
                int[] px, int[] pz) {

        /** Una caja con las esquinas en cualquier orden. */
        static Zona caja(String origen, String nombre, String mundo, int ax, int ay, int az, int bx, int by, int bz) {
            return new Zona(origen, nombre, mundo, Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
                    Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz), null, null);
        }

        /** Si el bloque de (x, y, z) cae dentro. */
        boolean dentro(double x, double y, double z) {
            int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
            if (bx < x1 || bx > x2 || by < y1 || by > y2 || bz < z1 || bz > z2) return false;
            return px == null || enPoligono(px, pz, bx, bz);
        }

        double centroX() {
            return (x1 + x2 + 1) / 2.0;
        }

        double centroZ() {
            return (z1 + z2 + 1) / 2.0;
        }

        /** Lo que sale en /calamidad: de donde sale, en que mundo y sus esquinas. */
        String describir() {
            String esquinas = x1 + " " + y1 + " " + z1 + "  a  " + x2 + " " + y2 + " " + z2;
            if (REGION.equals(origen)) {
                return "región WorldGuard «" + nombre + "» en " + mundo + " · " + esquinas
                        + (px == null ? "" : " (polígono de " + px.length + " puntos)");
            }
            return "caja de la vara en " + mundo + " · " + esquinas;
        }
    }

    private final Hardcore hc;
    /** Mundo -> su zona. Se rehace entera al refrescar; volatile por si alguien pregunta desde otro hilo. */
    private volatile Map<UUID, Zona> zonas = Map.of();
    private long leidas;
    /** Lo ultimo que se dijo en consola de cada mundo: solo se avisa cuando cambia. */
    private final Map<String, String> dicho = new HashMap<>();
    /** Fallos al leer WorldGuard ya avisados (uno por texto, no cada 5 s). */
    private final java.util.Set<String> fallosAvisados = new java.util.HashSet<>();
    /** Calamity 1.10 · Ultimo "Desde el spawn no puedes atacar" a cada uno (millis). Se poda en tick(). */
    private final Map<UUID, Long> avisosAtaque = new HashMap<>();

    ZonaSpawn(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("zona-spawn", ZonaSpawn::autotest);
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = hc.cfg().getConfigurationSection("spawn");
        return s == null ? new YamlConfiguration() : s;
    }

    private boolean retirarMobs() {
        return cfg().getBoolean("retirar-mobs", true);
    }

    /*
     * Calamity 1.10 · Las claves nuevas de spawn. El servidor conserva su config.yml de antes (no se
     * reescribe nunca), asi que el valor de serie de cada una va aqui igual que en el del jar.
     */

    /** spawn.minijefes.esperan: los minijefes se sacan al borde y esperan, en vez de retirarse. */
    boolean minijefesEsperan() {
        return cfg().getBoolean("minijefes.esperan", true);
    }

    /** spawn.minijefes.espera-segundos: lo que aguanta esperando antes de irse (0 = no se cansa). */
    int esperaSegundos() {
        return cfg().getInt("minijefes.espera-segundos", 300);
    }

    /** spawn.minijefes.correa: cuanto puede alejarse el que espera del sitio de salida (0 = sin correa). */
    private double correa() {
        return cfg().getDouble("minijefes.correa", 32);
    }

    /** spawn.sin-dano-desde-dentro: desde la zona no se dana a los mobs de Calamity ni a las amenazas. */
    private boolean sinDanoDesdeDentro() {
        return cfg().getBoolean("sin-dano-desde-dentro", true);
    }

    // ================================================================== consultas

    /** La zona de ese mundo, o null si no tiene (o no es hardcore). */
    Zona de(World w) {
        if (w == null) return null;
        refrescar(false);
        return zonas.get(w.getUID());
    }

    /** Si ese sitio cae en la zona spawn de su mundo. */
    boolean dentro(Location l) {
        if (l == null || l.getWorld() == null) return false;
        Zona z = de(l.getWorld());
        return z != null && z.dentro(l.getX(), l.getY(), l.getZ());
    }

    /**
     * Si ese sitio cae dentro, el punto (x, z) mas cercano fuera de la zona, a "margen" bloques de
     * su borde; si no, null. Lo usa el Eco para no nacer dentro (FotoMuerte.anclar).
     */
    double[] fueraDe(Location l, double margen) {
        if (!dentro(l)) return null;
        return fuera(de(l.getWorld()), l.getX(), l.getZ(), margen);
    }

    /** Para /calamidad: la zona de cada mundo hardcore, o por que no hay. Relee en el acto. */
    String describir() {
        refrescar(true);
        boolean hayWg = worldGuard() != null;
        String region = nombreRegion();
        List<String> partes = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            if (!hc.esHardcore(w)) continue;
            Zona z = zonas.get(w.getUID());
            if (z != null) {
                partes.add(z.describir());
                continue;
            }
            String porQue = region.isEmpty() ? "sin región configurada"
                    : !hayWg ? "WorldGuard no está" : "no tiene la región «" + region + "»";
            partes.add(w.getKey().getKey() + ": sin zona (" + porQue + " y no hay caja de la vara)");
        }
        return partes.isEmpty() ? "sin zona (no hay ningún mundo hardcore cargado)" : String.join("  |  ", partes);
    }

    // ================================================================== refresco

    private String nombreRegion() {
        String r = cfg().getString("region", "calamity");
        return r == null ? "" : r.trim();
    }

    /**
     * Vuelve a leer la zona de cada mundo hardcore si hace mas de CADA_MS (o ya, si "ya"). Solo en
     * el hilo principal: WorldGuard y el config no se tocan desde otro, y quien pregunte desde alli
     * se queda con lo ultimo leido.
     */
    private void refrescar(boolean ya) {
        if (!Bukkit.isPrimaryThread()) return;
        long ahora = System.currentTimeMillis();
        if (!ya && leidas != 0 && ahora - leidas < CADA_MS) return;
        leidas = ahora;
        String region = nombreRegion();
        ConfigurationSection vara = hc.cfg().getConfigurationSection("puertas.spawn");
        Map<UUID, Zona> nuevas = new HashMap<>();
        for (World w : Bukkit.getWorlds()) {
            if (!hc.esHardcore(w)) continue;
            String corto = w.getKey().getKey();
            Zona z = elegir(region.isEmpty() ? null : region(w, region, corto),
                    deVara(vara, w.getKey().toString(), corto));
            if (z != null) nuevas.put(w.getUID(), z);
            anunciar(corto, z);
        }
        zonas = nuevas;
    }

    /** Una linea en consola cuando la zona de un mundo cambia (al arrancar, tras un /rg redefine...). */
    private void anunciar(String mundo, Zona z) {
        String texto = z == null ? "sin zona" : z.describir();
        if (texto.equals(dicho.put(mundo, texto))) return;
        hc.plugin().getLogger().info("[Calamity] Zona del spawn de " + mundo + ": " + texto);
    }

    // ============================================================ WorldGuard (reflexion)

    private static Plugin worldGuard() {
        Plugin wg = Bukkit.getPluginManager().getPlugin("WorldGuard");
        return wg == null || !wg.isEnabled() ? null : wg;
    }

    /**
     * La region "nombre" de WorldGuard en ese mundo, o null: sin WorldGuard, sin regiones en ese
     * mundo, sin esa region, la global, o si algo de la API no esta donde se esperaba (se avisa
     * una vez y se sigue con la vara).
     *
     * Los metodos se piden a los tipos publicos de la API (WorldGuardPlatform, RegionContainer,
     * RegionManager, ProtectedRegion) y no a la clase de cada objeto: las implementaciones pueden
     * no ser publicas y entonces invoke() fallaria.
     */
    private Zona region(World w, String nombre, String mundo) {
        Plugin wg = worldGuard();
        if (wg == null) return null;
        try {
            ClassLoader cl = wg.getClass().getClassLoader();
            Class<?> cWg = Class.forName("com.sk89q.worldguard.WorldGuard", true, cl);
            Object instancia = cWg.getMethod("getInstance").invoke(null);
            Object plataforma = cWg.getMethod("getPlatform").invoke(instancia);
            Object contenedor = Class.forName("com.sk89q.worldguard.internal.platform.WorldGuardPlatform", true, cl)
                    .getMethod("getRegionContainer").invoke(plataforma);
            Class<?> cMundoWe = Class.forName("com.sk89q.worldedit.world.World", true, cl);
            Object mundoWe = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, cl)
                    .getMethod("adapt", World.class).invoke(null, w);
            Object gestor = Class.forName("com.sk89q.worldguard.protection.regions.RegionContainer", true, cl)
                    .getMethod("get", cMundoWe).invoke(contenedor, mundoWe);
            if (gestor == null) return null;
            Object r = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager", true, cl)
                    .getMethod("getRegion", String.class).invoke(gestor, nombre);
            if (r == null) return null;
            Class<?> cRegion = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion", true, cl);
            String tipo = String.valueOf(cRegion.getMethod("getType").invoke(r)).toUpperCase(Locale.ROOT);
            if (tipo.contains("GLOBAL")) return null;
            Object min = cRegion.getMethod("getMinimumPoint").invoke(r);
            Object max = cRegion.getMethod("getMaximumPoint").invoke(r);
            Zona caja = Zona.caja(REGION, nombre, mundo, eje(min, "x"), eje(min, "y"), eje(min, "z"),
                    eje(max, "x"), eje(max, "y"), eje(max, "z"));
            if (!tipo.contains("POLYGON")) return caja;
            List<?> puntos = (List<?>) cRegion.getMethod("getPoints").invoke(r);
            int[] px = new int[puntos.size()], pz = new int[puntos.size()];
            for (int i = 0; i < puntos.size(); i++) {
                px[i] = eje(puntos.get(i), "x");
                pz[i] = eje(puntos.get(i), "z");
            }
            return px.length < 3 ? caja : new Zona(REGION, nombre, mundo, caja.x1(), caja.y1(), caja.z1(),
                    caja.x2(), caja.y2(), caja.z2(), px, pz);
        } catch (Throwable t) {
            String texto = t.getClass().getSimpleName() + ": " + t.getMessage();
            if (fallosAvisados.add(texto)) {
                hc.plugin().getLogger().warning("[Calamity] No se pudo leer la región «" + nombre + "» de WorldGuard en "
                        + mundo + " (" + texto + "). Se usa la caja de la vara.");
            }
            return null;
        }
    }

    /**
     * Una coordenada de un BlockVector3 o BlockVector2 de WorldEdit. En 7.3 son records (x(), y(),
     * z()); antes, getBlockX()/getX(). Se prueba por ese orden.
     */
    private static int eje(Object v, String eje) throws ReflectiveOperationException {
        String may = eje.toUpperCase(Locale.ROOT);
        for (String n : new String[]{eje, "getBlock" + may, "get" + may}) {
            try {
                Object r = v.getClass().getMethod(n).invoke(v);
                if (r instanceof Number num) return (int) Math.floor(num.doubleValue());
            } catch (NoSuchMethodException siguiente) {
                // El nombre de la otra version de WorldEdit.
            }
        }
        throw new NoSuchMethodException(v.getClass().getName() + "." + eje + "()");
    }

    // ================================================================== retirada

    /** Un mob de Calamity de verdad: con la marca de MobsLethal, sin ser amenaza ni NPC. */
    static boolean deCalamity(Entity e) {
        return e instanceof LivingEntity && !(e instanceof Player)
                && e.getPersistentDataContainer().has(MOB_LETHAL, PersistentDataType.STRING)
                && !Marcas.esAmenaza(e) && !e.hasMetadata("NPC");
    }

    /** La clase que MobsLethal le puso (comun, destacado, minijefe, estructura), o null. */
    private static String clase(Entity e) {
        return e.getPersistentDataContainer().get(MOB_LETHAL, PersistentDataType.STRING);
    }

    /**
     * Una vez por segundo desde Hardcore.tick: fuera los mobs de Calamity que se hayan colado
     * (normalmente persiguiendo a alguien). Solo en mundos con alguien dentro: sin jugadores no
     * hay nada cargado que mirar. 1.10: los minijefes no se retiran, se sacan al borde (alColarse).
     */
    void tick() {
        if (!avisosAtaque.isEmpty()) {
            long ahora = System.currentTimeMillis();
            avisosAtaque.values().removeIf(t -> ahora - t >= AVISO_ATAQUE_MS);
        }
        if (!retirarMobs()) return;
        boolean esperan = minijefesEsperan();
        for (World w : Bukkit.getWorlds()) {
            if (w.getPlayers().isEmpty()) continue;
            Zona z = de(w);
            if (z == null) continue;
            BoundingBox caja = new BoundingBox(z.x1(), z.y1(), z.z1(), z.x2() + 1, z.y2() + 1, z.z2() + 1);
            for (Entity e : w.getNearbyEntities(caja, ZonaSpawn::deCalamity)) {
                Location l = e.getLocation();
                if (!z.dentro(l.getX(), l.getY(), l.getZ())) continue;
                if (alColarse(clase(e), esperan) == Accion.SACAR) sacar(e, z);
                else retirar(e);
            }
        }
    }

    /** Se deshace en humo y ceniza, sin estruendo: el spawn tiene que seguir siendo tranquilo. */
    private void retirar(Entity e) {
        World w = e.getWorld();
        Location l = e.getLocation();
        Compat.spawn(w, Compat.LARGE_SMOKE, l.clone().add(0, 0.8, 0), 12, 0.3, 0.6, 0.3, 0.01);
        Compat.spawn(w, Compat.ASH, l.clone().add(0, 1, 0), 24, 0.4, 0.8, 0.4, 0.02);
        Compat.sound(w, l, "block.fire.extinguish", 0.4f, 1.4f);
        String clase = clase(e);
        hc.plugin().bitacora().anotar("spawn", "retirado", e.getType().getKey().getKey(), clase == null ? "?" : clase,
                bloque(l));
        e.remove();
    }

    /**
     * Calamity 1.10 · Un minijefe que se ha colado no se borra ni suelta a su presa: se le lleva fuera,
     * a MARGEN_SACAR bloques del borde y a ras de suelo (sitioFuera), con un poco de humo y sin sonido.
     *
     * Por que lado sale lo decide referencia(): el de su objetivo si es un jugador de ese mundo que
     * esta fuera; si no, el de su presa (Hardcore.presaDe) si esta fuera; si no, el borde mas cercano a
     * el. Asi el que cruza la plaza persiguiendo a alguien sale por el lado de ese alguien y sigue con
     * lo suyo. Sacado por el borde mas cercano a el, salia por donde habia entrado, volvia a entrar al
     * segundo siguiente y rebotaba en la plaza cada segundo. La referencia se lleva antes al borde de
     * la caja (alBorde): sale en el borde de ese lado, no encima de quien persigue. El suelo se busca
     * desde la altura de la referencia. Si apuntaba a alguien de dentro, lo suelta (mover).
     *
     * Si no se le puede mover (un teleport que falla), lo de antes: un minijefe dentro de la zona no
     * puede quedarse, porque desde dentro nadie puede pegarle (onDanoDesdeDentro).
     */
    private void sacar(Entity e, Zona z) {
        World w = e.getWorld();
        Location desde = e.getLocation();
        double[] objetivo = e instanceof Mob m && m.getTarget() instanceof Player t && t.getWorld() == w
                ? xyz(t.getLocation()) : null;
        Player presa = hc.presaDe(e.getUniqueId());
        double[] dePresa = presa != null && presa.getWorld() == w ? xyz(presa.getLocation()) : null;
        double[] ref = referencia(z, objetivo, dePresa, xyz(desde));
        double[] borde = alBorde(z, ref[0], ref[2]);
        Location cerca = new Location(w, borde[0], ref[1], borde[1], desde.getYaw(), 0f);
        Location a = sitioFuera(z, cerca, MARGEN_SACAR, e.getHeight());
        if (!mover(e, a)) {
            retirar(e);
            return;
        }
        hc.plugin().bitacora().anotar("spawn", "minijefe-sacado", e.getType().getKey().getKey(), bloque(desde), bloque(a));
    }

    /**
     * Calamity 1.10 · El minijefe que espera a su presa (Hardcore.vigilarPresas) no se aleja del sitio
     * por donde ella saldria: el de fuera mas cercano a ella, a MARGEN_ESPERA bloques del borde. Si
     * esta a mas de spawn.minijefes.correa bloques de ahi (se fue tras alguien de fuera que le pego, o
     * ella ha cruzado la plaza y ahora saldria por otro lado), se le trae como en sacar. Nunca junto a
     * ella: mientras espera, distancia-maxima no cuenta.
     *
     * La correa se mide en horizontal: con un spawn en alto, el suelo de fuera queda muy por debajo de
     * ella y, contando la altura, se le traeria al mismo sitio cada segundo.
     */
    void acercarAlBorde(Mob mob, Player presa) {
        Zona z = de(presa.getWorld());
        if (z == null || mob.getWorld() != presa.getWorld()) return;
        Location p = presa.getLocation();
        double[] xz = fuera(z, p.getX(), p.getZ(), MARGEN_ESPERA);
        Location m = mob.getLocation();
        if (!lejos(m.getX() - xz[0], m.getZ() - xz[1], correa())) return;
        mover(mob, sitioFuera(z, p, MARGEN_ESPERA, mob.getHeight()));
    }

    /**
     * Calamity 1.10 · Donde dejar a un mob que se saca de la zona: el punto de fuera mas cercano a
     * "cerca" (fuera, a "margen" bloques del borde), a ras de suelo desde su altura y con hueco para
     * todo su cuerpo; si ahi no cabe (roca, agua), encima de lo mas alto de esa columna. Es la cuenta
     * de FotoMuerte.fueraDelSpawn (donde nace el Eco de quien muere dentro) con el alto del mob en vez
     * de dos bloques: un minijefe a escala 2 mide casi cuatro, y con dos podia quedar con la cabeza
     * dentro de un bloque.
     */
    static Location sitioFuera(Zona z, Location cerca, double margen, double alto) {
        World w = cerca.getWorld();
        double[] xz = fuera(z, cerca.getX(), cerca.getZ(), margen);
        Location g = Fx.ground(new Location(w, xz[0], cerca.getY() + 4, xz[1], cerca.getYaw(), 0f), 32);
        if (Parca.libre(g, Math.max(2, (int) Math.ceil(alto))) || w.hasCeiling()) return g;
        g.setY(w.getHighestBlockYAt(g.getBlockX(), g.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1);
        return g;
    }

    /**
     * Lleva un mob a "a" con un poco de humo donde estaba y donde aparece: sin sonido, que el spawn
     * tiene que seguir siendo tranquilo. Si apuntaba a alguien de dentro lo suelta (dentro no se
     * apunta a nadie, onApuntar), o volveria a entrar tras el. False si el teleport no ha podido.
     * Teleport a secas, como el resto de Calamity: las TeleportFlag.EntityState estan obsoletas
     * desde Paper 1.21.10 y marcadas para desaparecer.
     */
    private boolean mover(Entity e, Location a) {
        Location desde = e.getLocation();
        if (!e.teleport(a)) return false;
        Compat.spawn(desde.getWorld(), Compat.LARGE_SMOKE, desde.add(0, 1, 0), 10, 0.3, 0.6, 0.3, 0.01);
        Compat.spawn(a.getWorld(), Compat.LARGE_SMOKE, a.clone().add(0, 1, 0), 10, 0.3, 0.6, 0.3, 0.01);
        if (e instanceof Mob m && m.getTarget() instanceof Player t && dentro(t.getLocation())) m.setTarget(null);
        return true;
    }

    /** "x y z" del bloque, para la Bitacora. */
    private static String bloque(Location l) {
        return l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    /** {x, y, z} de un sitio, para el nucleo (referencia). */
    private static double[] xyz(Location l) {
        return new double[]{l.getX(), l.getY(), l.getZ()};
    }

    /** Los mobs de Calamity no toman como objetivo a quien esta en la zona spawn. */
    @EventHandler(ignoreCancelled = true)
    public void onApuntar(EntityTargetLivingEntityEvent e) {
        if (!(e.getTarget() instanceof Player p) || !deCalamity(e.getEntity())) return;
        if (retirarMobs() && dentro(p.getLocation())) e.setCancelled(true);
    }

    // ===================================================== sin dano desde dentro (1.10)

    /**
     * Calamity 1.10 · Lo que no se puede danar desde la zona: los mobs de Calamity (deCalamity) y las
     * amenazas (PARCA, Eco, Ambush, planideras...). Ni jugadores ni NPCs de Citizens: el PvP lo
     * deciden WorldGuard y Hardcore.onFuegoAmigo, y un mob sin la marca de MobsLethal no es nuestro.
     * Las marcas primero: esto se pregunta en cada golpe del servidor, y el metadato de Citizens
     * cuesta mas que mirar el PersistentDataContainer.
     */
    static boolean protegido(Entity e) {
        if (e == null || e instanceof Player) return false;
        return (deCalamity(e) || Marcas.esAmenaza(e)) && !e.hasMetadata("NPC");
    }

    /**
     * protegido() con la config de ahora: los mobs de Calamity, solo con retirar-mobs en true. En false
     * se quedan dentro y SI apuntan a quien esta en la zona (onApuntar no los frena), y sin poder
     * pegarles desde ella quien estuviera dentro no podria defenderse. Las amenazas, siempre: su salida
     * del spawn es suya (Parca.alEntrarSpawn, Eco, Ambush) y no depende de retirar-mobs.
     */
    private boolean protegidoAhora(Entity e) {
        return protegido(e) && (Marcas.esAmenaza(e) || retirarMobs());
    }

    /**
     * Quien esta detras de lo que pega: el que dispara un proyectil (flecha, tridente, pocion), el que
     * lanzo la nube de una pocion persistente o el que encendio la TNT. Va aparte de Hardcore.autor,
     * que solo mira proyectiles, a proposito: aquel decide quien cuenta como agresor en el PvP
     * (onFuegoAmigo) y ampliarlo cambiaria el PvP, que aqui no se toca.
     */
    static Entity autor(Entity danador) {
        if (danador instanceof Projectile pr && pr.getShooter() instanceof Entity tirador) return tirador;
        if (danador instanceof AreaEffectCloud nube && nube.getSource() instanceof Entity fuente) return fuente;
        if (danador instanceof TNTPrimed tnt && tnt.getSource() != null) return tnt.getSource();
        return danador;
    }

    /**
     * El jugador que hace ese dano, o null. Primero la fuente del dano, que Paper resuelve igual para
     * flechas, nubes, TNT y cristales del End; si no trae a nadie, autor().
     */
    private static Player responsable(EntityDamageByEntityEvent e) {
        Entity causa;
        try {
            causa = e.getDamageSource().getCausingEntity();
        } catch (Throwable sinFuente) {
            causa = null;
        }
        if (causa == null) causa = autor(e.getDamager());
        return causa instanceof Player p ? p : null;
    }

    /**
     * Calamity 1.10 · Desde dentro de la zona no se dana a lo de Calamity (protegidoAhora), este donde
     * este. Sus mobs no pueden apuntar a quien esta dentro (onApuntar), asi que pegarles desde ahi era una
     * pelea en la que solo pegaba uno; y con un minijefe esperando en el borde (Hardcore.vigilarPresas)
     * se le podria matar sin riesgo. Cuenta donde esta quien pega, no la victima.
     *
     * En LOW, antes de que nadie apunte el golpe (Combate, Huella, Amenazas, el reparto). La flecha se
     * borra: con el golpe cancelado rebotaria. El tridente no, que es el arma de alguien.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDanoDesdeDentro(EntityDamageByEntityEvent e) {
        if (!protegidoAhora(e.getEntity())) return;
        Player p = responsable(e);
        if (p == null || !sinDanoDesdeDentro() || !dentro(p.getLocation())) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof AbstractArrow flecha && !(flecha instanceof Trident)) flecha.remove();
        avisarAtaque(p);
    }

    /**
     * Calamity 1.10 · La pocion arrojadiza que tira alguien desde dentro no le hace nada a lo de
     * Calamity: ni el dano de la de dano ni el veneno o la lentitud de las demas. A los demas (el
     * mismo, otros jugadores, mobs ajenos) les llega como siempre.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPocion(PotionSplashEvent e) {
        if (!(e.getPotion().getShooter() instanceof Player p) || !sinDanoDesdeDentro() || !dentro(p.getLocation())) {
            return;
        }
        boolean alguno = false;
        for (LivingEntity le : e.getAffectedEntities()) {
            if (!protegidoAhora(le)) continue;
            e.setIntensity(le, 0);
            alguno = true;
        }
        if (alguno) avisarAtaque(p);
    }

    /**
     * Calamity 1.10 · Y la nube de una persistente: mientras quien la lanzo siga dentro, no se le
     * aplica a lo de Calamity, ni el dano ni los demas efectos, aunque haya caido fuera. Con solo
     * onDanoDesdeDentro se paraba el dano, pero el veneno o la debilidad seguian entrando.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onNube(AreaEffectCloudApplyEvent e) {
        if (!(e.getEntity().getSource() instanceof Player p) || !sinDanoDesdeDentro() || !dentro(p.getLocation())) {
            return;
        }
        if (e.getAffectedEntities().removeIf(this::protegidoAhora)) avisarAtaque(p);
    }

    /** "Desde el spawn no puedes atacar." en su barra de accion, como mucho uno cada AVISO_ATAQUE_MS. */
    private void avisarAtaque(Player p) {
        long ahora = System.currentTimeMillis();
        Long antes = avisosAtaque.get(p.getUniqueId());
        if (antes != null && ahora - antes < AVISO_ATAQUE_MS) return;
        avisosAtaque.put(p.getUniqueId(), ahora);
        hc.cordura().destello(p, Component.text("Desde el spawn no puedes atacar.", Paleta.AVISO), 2);
    }

    void parar() {
        HandlerList.unregisterAll(this);
        zonas = Map.of();
        dicho.clear();
        fallosAvisados.clear();
        avisosAtaque.clear();
    }

    // ==================================================================== nucleo

    /** Prioridad: la region de WorldGuard; si no, la caja de la vara; si no, ninguna. */
    static Zona elegir(Zona region, Zona vara) {
        return region != null ? region : vara;
    }

    /**
     * La caja de la vara (hardcore.puertas.spawn) si es de ese mundo, o null. El mundo se guarda
     * con su clave entera (lethal_world:calamity2), pero se acepta tambien el nombre corto.
     */
    static Zona deVara(ConfigurationSection c, String claveMundo, String corto) {
        if (c == null || !c.isSet("mundo")) return null;
        String m = c.getString("mundo", "");
        if (!m.equalsIgnoreCase(claveMundo) && !m.equalsIgnoreCase(corto)) return null;
        return Zona.caja(VARA, "vara", corto, c.getInt("x1"), c.getInt("y1"), c.getInt("z1"),
                c.getInt("x2"), c.getInt("y2"), c.getInt("z2"));
    }

    /**
     * Si el bloque (x, z) cae en el poligono, con el borde y los vertices dentro. Es la misma cuenta
     * que ProtectedPolygonalRegion.contains de WorldGuard, para que Calamity y WorldGuard no
     * discutan por un bloque del borde.
     */
    static boolean enPoligono(int[] px, int[] pz, int x, int z) {
        int n = px.length;
        if (n < 3) return false;
        boolean dentro = false;
        int xAntes = px[n - 1], zAntes = pz[n - 1];
        for (int i = 0; i < n; i++) {
            int xNuevo = px[i], zNuevo = pz[i];
            if (xNuevo == x && zNuevo == z) return true;
            int x1, z1, x2, z2;
            if (xNuevo > xAntes) {
                x1 = xAntes;
                x2 = xNuevo;
                z1 = zAntes;
                z2 = zNuevo;
            } else {
                x1 = xNuevo;
                x2 = xAntes;
                z1 = zNuevo;
                z2 = zAntes;
            }
            if (x1 <= x && x <= x2) {
                long cruz = ((long) z - z1) * (x2 - x1) - ((long) z2 - z1) * (x - x1);
                if (cruz == 0) {
                    if ((z1 <= z) == (z <= z2)) return true;
                } else if (cruz < 0 && x1 != x) {
                    dentro = !dentro;
                }
            }
            xAntes = xNuevo;
            zAntes = zNuevo;
        }
        return dentro;
    }

    /**
     * El punto (x, z) fuera de la caja de la zona mas cercano a uno de dentro: por el lado mas
     * proximo, a "margen" bloques del borde y en el centro del bloque. Con un poligono se sale de
     * su caja, que queda fuera seguro. Un punto que ya esta fuera de la caja se devuelve igual.
     */
    static double[] fuera(Zona zona, double x, double z, double margen) {
        if (zona == null || x < zona.x1() || x >= zona.x2() + 1 || z < zona.z1() || z >= zona.z2() + 1) {
            return new double[]{x, z};
        }
        double m = Math.max(0, margen);
        double oeste = x - zona.x1(), este = zona.x2() + 1 - x, norte = z - zona.z1(), sur = zona.z2() + 1 - z;
        double menor = Math.min(Math.min(oeste, este), Math.min(norte, sur));
        if (menor == oeste) return new double[]{zona.x1() - m - 0.5, z};
        if (menor == este) return new double[]{zona.x2() + 1 + m + 0.5, z};
        if (menor == norte) return new double[]{x, zona.z1() - m - 0.5};
        return new double[]{x, zona.z2() + 1 + m + 0.5};
    }

    /**
     * Calamity 1.10 · Que se hace con un mob de Calamity que esta dentro de la zona, por la clase que le
     * puso MobsLethal: un minijefe se saca al borde (con spawn.minijefes.esperan); todo lo demas, y los
     * minijefes con esa opcion en false, se retira en humo como siempre.
     */
    static Accion alColarse(String clase, boolean minijefesEsperan) {
        return minijefesEsperan && "minijefe".equals(clase) ? Accion.SACAR : Accion.RETIRAR;
    }

    /**
     * Calamity 1.10 · El punto {x, y, z} por cuyo lado se saca a un minijefe colado (sacar), por este
     * orden: su objetivo, si esta fuera de la zona; si no, su presa, si esta fuera; si no, el mismo.
     * Null en objetivo o en presa = no hay, o no es un jugador de ese mundo (lo filtra quien llama).
     * Devuelve el mismo array que elige, con su altura: el suelo se busca desde ella.
     */
    static double[] referencia(Zona zona, double[] objetivo, double[] presa, double[] propio) {
        if (zona == null) return propio;
        if (objetivo != null && !zona.dentro(objetivo[0], objetivo[1], objetivo[2])) return objetivo;
        if (presa != null && !zona.dentro(presa[0], presa[1], presa[2])) return presa;
        return propio;
    }

    /**
     * Calamity 1.10 · El (x, z) de una referencia llevado al borde mas cercano de la caja, justo por
     * dentro, para que fuera() lo saque por ese lado. fuera() devuelve tal cual un punto que ya esta
     * fuera de la caja, y sin esto el minijefe apareceria encima de quien persigue en vez de en el
     * borde de su lado. Un punto de dentro de la caja se queda como esta.
     */
    static double[] alBorde(Zona zona, double x, double z) {
        double casi = 1e-3;
        double bx = x < zona.x1() ? zona.x1() : x >= zona.x2() + 1 ? zona.x2() + 1 - casi : x;
        double bz = z < zona.z1() ? zona.z1() : z >= zona.z2() + 1 ? zona.z2() + 1 - casi : z;
        return new double[]{bx, bz};
    }

    /**
     * Calamity 1.10 · El paso de cada segundo de un minijefe de cordura cero (Hardcore.vigilarPresas),
     * segun si su presa esta dentro de la zona, si ya la esperaba, spawn.minijefes.esperan, cuanto lleva
     * esperando y cuanto aguanta (espera-segundos). Que ella salga manda sobre todo lo demas: el que la
     * esperaba vuelve por ella aunque la opcion se haya apagado entretanto.
     */
    static Espera espera(boolean presaDentro, boolean esperaba, boolean esperan, long esperadoMs, int limiteSegundos) {
        if (!presaDentro) return esperaba ? Espera.VUELVE : Espera.SIGUE;
        if (!esperan) return Espera.SE_RETIRA;
        if (!esperaba) return Espera.EMPIEZA;
        return seCansa(esperadoMs, limiteSegundos) ? Espera.SE_CANSA : Espera.ESPERA;
    }

    /** Calamity 1.10 · Si ya ha esperado de mas: MAS de limiteSegundos seguidos. Con 0 o menos, nunca. */
    static boolean seCansa(long esperadoMs, int limiteSegundos) {
        return limiteSegundos > 0 && esperadoMs > limiteSegundos * 1000L;
    }

    /**
     * Calamity 1.10 · Si el que espera se ha ido mas alla de la correa: dx y dz hasta el sitio por donde
     * saldria su presa, en horizontal. Con la correa a 0 o menos, nunca (se queda donde este).
     */
    static boolean lejos(double dx, double dz, double correa) {
        return correa > 0 && dx * dx + dz * dz > correa * correa;
    }

    // ================================================================== autotest

    /**
     * "zona-spawn": dentro/fuera de la caja de la region, el poligono, la prioridad y la salida del Eco.
     * 1.10: que se hace con cada clase que se cuela, por que lado sale el minijefe colado, la espera de
     * los minijefes y su correa.
     */
    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        // La region de verdad del spawn de calamity2 (215 1 -278 a 318 382 -174), con las esquinas al reves.
        Zona r = Zona.caja(REGION, "calamity", "calamity2", 318, 382, -174, 215, 1, -278);
        h.ok("esquinas ordenadas", r.x1() == 215 && r.y1() == 1 && r.z1() == -278 && r.x2() == 318 && r.y2() == 382
                && r.z2() == -174);
        h.ok("dentro: esquina de abajo, centro y esquina de arriba (318,9 382,9 -174,1)",
                r.dentro(215.0, 1.0, -278.0) && r.dentro(266.5, 70, -226.5) && r.dentro(318.9, 382.9, -174.1));
        h.ok("fuera: un bloque al este, al oeste, debajo, encima, al sur y al norte",
                !r.dentro(319.0, 70, -226) && !r.dentro(214.99, 70, -226) && !r.dentro(266, 0.5, -226)
                        && !r.dentro(266, 383.0, -226) && !r.dentro(266, 70, -173.0) && !r.dentro(266, 70, -278.01));
        h.cerca("centro x", 267.0, r.centroX(), 1e-9);
        h.cerca("centro z", -225.5, r.centroZ(), 1e-9);

        // Un poligono en L: (0,0) (10,0) (10,4) (4,4) (4,10) (0,10).
        int[] px = {0, 10, 10, 4, 4, 0}, pz = {0, 0, 4, 4, 10, 10};
        h.ok("poligono: dentro de las dos patas", enPoligono(px, pz, 8, 2) && enPoligono(px, pz, 2, 8));
        h.ok("poligono: fuera en el hueco de la L", !enPoligono(px, pz, 7, 7));
        h.ok("poligono: vertice y borde cuentan dentro", enPoligono(px, pz, 10, 4) && enPoligono(px, pz, 4, 7)
                && enPoligono(px, pz, 5, 0));
        h.ok("poligono: fuera del todo", !enPoligono(px, pz, 11, 2) && !enPoligono(px, pz, -1, 5));
        Zona l = new Zona(REGION, "l", "calamity2", 0, 60, 0, 10, 70, 10, px, pz);
        h.ok("zona poligono: la caja no basta (hueco de la L fuera, pata dentro)",
                !l.dentro(7.5, 65, 7.5) && l.dentro(2.5, 65, 8.5) && !l.dentro(2.5, 71, 8.5));

        Zona v = Zona.caja(VARA, "vara", "calamity2", 0, 60, 0, 20, 80, 20);
        h.ok("prioridad: region antes que la vara", elegir(r, v) == r);
        h.ok("prioridad: sin region, la vara", elegir(null, v) == v);
        h.ok("prioridad: sin nada, sin zona", elegir(null, null) == null);

        YamlConfiguration c = new YamlConfiguration();
        c.set("mundo", "lethal_world:calamity2");
        c.set("x1", 5);
        c.set("y1", 60);
        c.set("z1", 7);
        c.set("x2", 1);
        c.set("y2", 70);
        c.set("z2", 9);
        Zona dv = deVara(c, "lethal_world:calamity2", "calamity2");
        h.ok("vara de ese mundo (clave entera)", dv != null && VARA.equals(dv.origen()) && dv.x1() == 1 && dv.x2() == 5);
        YamlConfiguration corta = new YamlConfiguration();
        corta.set("mundo", "calamity2");
        h.ok("vara guardada con el nombre corto tambien", deVara(corta, "lethal_world:calamity2", "calamity2") != null);
        h.ok("vara de otro mundo: no", deVara(c, "lethal_world:calamity", "calamity") == null);
        h.ok("sin vara marcada: no", deVara(new YamlConfiguration(), "lethal_world:calamity2", "calamity2") == null);

        double[] oeste = fuera(r, 220.5, -226.5, 24);
        h.ok("el Eco sale por el lado mas cercano (oeste) a 24 del borde (" + oeste[0] + ")",
                oeste[0] == 215 - 24 - 0.5 && oeste[1] == -226.5 && !r.dentro(oeste[0], 70, oeste[1]));
        double[] sur = fuera(r, 266.5, -176.5, 24);
        h.ok("por el sur si es el mas cercano (" + sur[1] + ")", sur[1] == -174 + 1 + 24 + 0.5 && sur[0] == 266.5);
        double[] ya = fuera(r, 400, 0, 24);
        h.ok("un punto de fuera se queda donde esta", ya[0] == 400 && ya[1] == 0);

        // --- 1.10: lo que se cuela en la zona, por clase (alColarse).
        h.igual("se cuela un minijefe: se saca", Accion.SACAR, alColarse("minijefe", true));
        h.igual("se cuela un comun: se retira", Accion.RETIRAR, alColarse("comun", true));
        h.igual("se cuela un destacado (o un especial): se retira", Accion.RETIRAR, alColarse("destacado", true));
        h.igual("se cuela uno de estructura: se retira", Accion.RETIRAR, alColarse("estructura", true));
        h.igual("sin clase: se retira", Accion.RETIRAR, alColarse(null, true));
        h.igual("minijefe con esperan en false: como antes, se retira", Accion.RETIRAR, alColarse("minijefe", false));

        // Donde quedan: el que se cuela a MARGEN_SACAR del borde, el que espera a MARGEN_ESPERA. Fuera siempre.
        double[] sacado = fuera(r, 216.5, -200.5, MARGEN_SACAR);
        h.ok("el minijefe colado sale por el oeste a 3 del borde (" + sacado[0] + ")",
                sacado[0] == 215 - 3 - 0.5 && sacado[1] == -200.5 && !r.dentro(sacado[0], 70, sacado[1]));
        double[] salida = fuera(r, 300.5, -177.5, MARGEN_ESPERA);
        h.ok("espera a su presa (junto al sur) a 4 del borde (" + salida[1] + ")",
                salida[1] == -174 + 1 + 4 + 0.5 && salida[0] == 300.5 && !r.dentro(salida[0], 70, salida[1]));

        // --- 1.10: por que lado sale el minijefe colado (referencia, alBorde). El rebote.
        double[] propio = {216.5, 70, -226.5};          // dentro, pegado al borde oeste
        double[] objetivoFuera = {340.5, 95, -226.5};   // fuera, al este
        double[] presaFuera = {200.5, 64, -226.5};      // fuera, al oeste
        double[] enLaPlaza = {266.5, 70, -226.5};       // dentro
        h.ok("referencia: su objetivo de fuera antes que su presa",
                referencia(r, objetivoFuera, presaFuera, propio) == objetivoFuera);
        h.ok("referencia: objetivo dentro, su presa de fuera", referencia(r, enLaPlaza, presaFuera, propio) == presaFuera);
        h.ok("referencia: sin objetivo, su presa de fuera", referencia(r, null, presaFuera, propio) == presaFuera);
        h.ok("referencia: objetivo de fuera sin presa", referencia(r, objetivoFuera, null, propio) == objetivoFuera);
        h.ok("referencia: los dos dentro, el mismo", referencia(r, enLaPlaza, enLaPlaza, propio) == propio);
        h.ok("referencia: sin nadie, el mismo", referencia(r, null, null, propio) == propio);
        h.ok("referencia: sin zona, el mismo", referencia(null, objetivoFuera, presaFuera, propio) == propio);
        h.cerca("el suelo se busca desde la altura de la referencia (la del objetivo)", 95,
                referencia(r, objetivoFuera, presaFuera, propio)[1], 1e-9);

        // Pegado al oeste y persiguiendo a alguien que esta fuera al este: sin nadie fuera sale por el
        // oeste (por donde entro, y volvia a entrar); tras el, por el este, en el borde y no encima de el.
        double[] suyo = alBorde(r, propio[0], propio[2]);
        double[] porSuLado = fuera(r, suyo[0], suyo[1], MARGEN_SACAR);
        h.ok("sin nadie fuera: por su borde mas cercano, el oeste (" + porSuLado[0] + ")",
                porSuLado[0] == 215 - 3 - 0.5 && porSuLado[1] == -226.5);
        double[] delObjetivo = alBorde(r, objetivoFuera[0], objetivoFuera[2]);
        double[] porElOtro = fuera(r, delObjetivo[0], delObjetivo[1], MARGEN_SACAR);
        h.ok("tras alguien del este: sale por el este a 3 del borde (" + porElOtro[0] + "), no encima de el",
                porElOtro[0] == 318 + 1 + 3 + 0.5 && porElOtro[1] == -226.5 && !r.dentro(porElOtro[0], 70, porElOtro[1]));
        double[] igual = alBorde(r, 266.5, -226.5);
        h.ok("alBorde deja igual un punto de dentro", igual[0] == 266.5 && igual[1] == -226.5);
        double[] esquina = alBorde(r, 330, -290);
        double[] porLaEsquina = fuera(r, esquina[0], esquina[1], MARGEN_SACAR);
        h.ok("objetivo en diagonal (noreste): sale junto a esa esquina (" + porLaEsquina[0] + " " + porLaEsquina[1] + ")",
                !r.dentro(porLaEsquina[0], 70, porLaEsquina[1]) && porLaEsquina[0] > 300 && porLaEsquina[1] < -270);

        // --- 1.10: la espera de un minijefe de cordura cero (Hardcore.vigilarPresas).
        h.igual("presa fuera y no la esperaba: sigue", Espera.SIGUE, espera(false, false, true, 0, 300));
        h.igual("presa que entra: empieza a esperar", Espera.EMPIEZA, espera(true, false, true, 0, 300));
        h.igual("presa dentro a los 120 s: espera", Espera.ESPERA, espera(true, true, true, 120_000, 300));
        h.igual("a los 300 s justos aun espera", Espera.ESPERA, espera(true, true, true, 300_000, 300));
        h.igual("pasados los 300 s: se cansa", Espera.SE_CANSA, espera(true, true, true, 301_000, 300));
        h.igual("presa que sale: vuelve", Espera.VUELVE, espera(false, true, true, 200_000, 300));
        h.igual("sale con esperan ya apagado: vuelve igual", Espera.VUELVE, espera(false, true, false, 0, 300));
        h.igual("esperan en false: se retira al entrar ella, como antes", Espera.SE_RETIRA,
                espera(true, false, false, 0, 300));
        h.igual("esperan apagado a media espera: se retira", Espera.SE_RETIRA, espera(true, true, false, 60_000, 300));
        h.igual("espera-segundos 0: no se cansa nunca (10 h)", Espera.ESPERA, espera(true, true, true, 36_000_000, 0));
        h.ok("seCansa: mas de, no igual; 0 y negativo nunca", !seCansa(300_000, 300) && seCansa(300_001, 300)
                && !seCansa(Long.MAX_VALUE, 0) && !seCansa(Long.MAX_VALUE, -5));

        // --- 1.10: la correa del que espera, en horizontal.
        h.ok("correa 32: a 20,20 (28 bloques) no se le trae", !lejos(20, 20, 32));
        h.ok("correa 32: a 30,-20 (36 bloques) si", lejos(30, -20, 32));
        h.ok("correa 32: a 32 justos no", !lejos(32, 0, 32));
        h.ok("correa 0: nunca", !lejos(500, 500, 0));
        return h.lineas();
    }
}
