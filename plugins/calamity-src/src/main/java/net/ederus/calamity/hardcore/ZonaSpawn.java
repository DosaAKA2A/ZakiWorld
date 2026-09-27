package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
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
 * El nucleo (Zona, enPoligono, elegir, deVara, fuera) es estatico y sin Bukkit: el autotest
 * "zona-spawn" lo prueba.
 */
final class ZonaSpawn implements Listener {

    /** De donde sale una zona. */
    static final String REGION = "region", VARA = "vara";
    /** Cada cuanto se vuelve a leer WorldGuard y la vara. */
    static final long CADA_MS = 5_000;

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
        hc.plugin().getLogger().info("[Calamity] Zona spawn de " + mundo + ": " + texto);
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

    /**
     * Una vez por segundo desde Hardcore.tick: fuera los mobs de Calamity que se hayan colado
     * (normalmente persiguiendo a alguien). Solo en mundos con alguien dentro: sin jugadores no
     * hay nada cargado que mirar.
     */
    void tick() {
        if (!retirarMobs()) return;
        for (World w : Bukkit.getWorlds()) {
            if (w.getPlayers().isEmpty()) continue;
            Zona z = de(w);
            if (z == null) continue;
            BoundingBox caja = new BoundingBox(z.x1(), z.y1(), z.z1(), z.x2() + 1, z.y2() + 1, z.z2() + 1);
            for (Entity e : w.getNearbyEntities(caja, ZonaSpawn::deCalamity)) {
                Location l = e.getLocation();
                if (z.dentro(l.getX(), l.getY(), l.getZ())) retirar(e);
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
        String clase = e.getPersistentDataContainer().get(MOB_LETHAL, PersistentDataType.STRING);
        hc.plugin().bitacora().anotar("spawn", "retirado", e.getType().getKey().getKey(), clase == null ? "?" : clase,
                l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
        e.remove();
    }

    /** Los mobs de Calamity no toman como objetivo a quien esta en la zona spawn. */
    @EventHandler(ignoreCancelled = true)
    public void onApuntar(EntityTargetLivingEntityEvent e) {
        if (!(e.getTarget() instanceof Player p) || !deCalamity(e.getEntity())) return;
        if (retirarMobs() && dentro(p.getLocation())) e.setCancelled(true);
    }

    void parar() {
        HandlerList.unregisterAll(this);
        zonas = Map.of();
        dicho.clear();
        fallosAvisados.clear();
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

    // ================================================================== autotest

    /** "zona-spawn": dentro/fuera de la caja de la region, el poligono, la prioridad y la salida del Eco. */
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
        return h.lineas();
    }
}
