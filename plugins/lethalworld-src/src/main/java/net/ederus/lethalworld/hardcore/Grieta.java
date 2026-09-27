package net.ederus.lethalworld.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.kyori.adventure.text.Component;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * La Grieta (1.2.0): el AFK en el spawn de Calamity.
 *
 * En la zona spawn (una caja marcada con la vara, /lw hardcore define spawn, guardada como las
 * puertas en hardcore.puertas.spawn) la Huella no espera 10 minutos: con 5 (parca.spawn.minutos)
 * se abre una grieta bajo el que no se mueve, se lo traga y lo escupe lejos, en un sitio seguro
 * al azar a 600-1500 bloques del spawn; y alli le aparece la PARCA. El spawn no es sitio para
 * aparcar a nadie, y la PARCA en el spawn seria una emboscada para los que acaban de entrar.
 *
 * Los avisos previos salen igual que fuera (cinco, a la misma proporcion del limite: 150, 210,
 * 255, 270 y 285 s con 5 minutos) pero con su propio texto.
 *
 * El destino: un punto al azar en el anillo 600-1500 alrededor del centro de la caja, dentro
 * del borde del mundo; su chunk se carga de forma asincrona y se busca suelo firme (ni agua,
 * ni lava, ni magma, ni cactus, dos de aire encima). Hasta intentos puntos; si ninguno vale,
 * la PARCA viene donde esta, como fuera del spawn.
 *
 * El nucleo (umbral, dentro, punto) es estatico y sin Bukkit: el autotest "grieta" lo prueba.
 */
final class Grieta {

    /** hardcore.parca.spawn. */
    record Ajustes(boolean activa, int minutos, int distanciaMin, int distanciaMax, int intentos) {

        static Ajustes de(ConfigurationSection s) {
            if (s == null) s = new YamlConfiguration();
            int min = Math.max(16, s.getInt("distancia-min", 600));
            return new Ajustes(
                    s.getBoolean("activa", true),
                    Math.max(1, s.getInt("minutos", 5)),
                    min,
                    Math.max(min, s.getInt("distancia-max", 1500)),
                    Math.max(1, Math.min(40, s.getInt("intentos", 12))));
        }

        static Ajustes defecto() {
            return de(new YamlConfiguration());
        }
    }

    /** El limite de la Huella y sus cinco avisos para ese jugador; spawn = si es el de la Grieta. */
    record Umbral(int limite, int[] avisos, boolean spawn) {
    }

    /** Ticks que dura el desgarro antes de tragarse a nadie. */
    static final int TICKS_DESGARRO = 60;

    private final Hardcore hc;
    /** Quien esta siendo tragado ahora mismo (la Huella lo pide cada segundo). */
    private final Set<UUID> enCurso = new HashSet<>();
    private final List<BukkitTask> tareas = new ArrayList<>();
    private Ajustes ajustes;
    private long leidos;

    Grieta(Hardcore hc) {
        this.hc = hc;
        Autotest.registrar("grieta", Grieta::autotest);
    }

    Ajustes ajustes() {
        long ahora = System.currentTimeMillis();
        if (ajustes == null || ahora - leidos > 5_000) {
            ajustes = Ajustes.de(hc.cfg().getConfigurationSection("parca.spawn"));
            leidos = ahora;
        }
        return ajustes;
    }

    // ================================================================= nucleo

    /**
     * El umbral de la Huella: el normal fuera del spawn; dentro, minutos*60 (nunca mas que el
     * normal: el anillo de la Huella no mide mas) y los avisos a la misma proporcion, sin
     * repetirse y antes del limite.
     */
    static Umbral umbral(Huella.Ajustes h, Ajustes g, boolean dentro) {
        int normal = h.limite();
        if (!dentro || !g.activa()) return new Umbral(normal, h.avisos(), false);
        int limite = Math.max(60, Math.min(normal, g.minutos() * 60));
        double f = limite / (double) normal;
        int[] base = h.avisos();
        int[] av = new int[base.length];
        int antes = 0;
        for (int i = 0; i < base.length; i++) {
            int v = (int) Math.round(base[i] * f);
            v = Math.max(antes + 1, Math.min(limite - (base.length - i), v));
            av[i] = v;
            antes = v;
        }
        return new Umbral(limite, av, true);
    }

    /** Si el bloque (x, y, z) cae en la caja [x1..x2] x [y1..y2] x [z1..z2] (bordes incluidos). */
    static boolean dentro(int x1, int y1, int z1, int x2, int y2, int z2, double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        return bx >= Math.min(x1, x2) && bx <= Math.max(x1, x2)
                && by >= Math.min(y1, y2) && by <= Math.max(y1, y2)
                && bz >= Math.min(z1, z2) && bz <= Math.max(z1, z2);
    }

    /**
     * Un punto del anillo [min, max] alrededor de (cx, cz), con u1 y u2 en [0, 1): el angulo y
     * la distancia (uniforme en area, no en radio: si no, se amontonan cerca del borde de dentro).
     */
    static double[] punto(double cx, double cz, double min, double max, double u1, double u2) {
        double ang = u1 * Math.PI * 2;
        double d = Math.sqrt(min * min + u2 * (max * max - min * min));
        return new double[]{cx + Math.cos(ang) * d, cz + Math.sin(ang) * d};
    }

    // =============================================================== servidor

    /** La caja del spawn, o null si no esta marcada. */
    private ConfigurationSection caja() {
        ConfigurationSection c = hc.plugin().getConfig().getConfigurationSection("hardcore.puertas.spawn");
        return c == null || !c.isSet("mundo") ? null : c;
    }

    /** Si esta dentro de la zona spawn (la misma cuenta que las puertas: VaraPortales.dentro). */
    boolean enSpawn(Player p) {
        return hc.vara() != null && caja() != null && hc.vara().dentro(p, "spawn");
    }

    Umbral umbral(Player p, Huella.Ajustes h) {
        Ajustes g = ajustes();
        return umbral(h, g, g.activa() && enSpawn(p));
    }

    /**
     * Los avisos previos dentro del spawn: los mismos cinco que fuera, con su texto. En el 3.o
     * la Huella le pinta su huella y en el 4.o le pone la campana (eso lo hace ella).
     */
    void aviso(Player p, int nivel, double radioAjeno) {
        switch (nivel) {
            case 1 -> {
                hc.cordura().destello(p, Component.text("El suelo del spawn late bajo tus pies.", Paleta.TEXTO), 3);
                p.playSound(p.getLocation(), "block.sculk_sensor.clicking", SoundCategory.HOSTILE, 0.6f, 0.5f);
            }
            case 2 -> {
                hc.cordura().destello(p, Component.text("Algo se agrieta debajo de ti.", Paleta.TEXTO), 3);
                p.playSound(p.getLocation(), "block.deepslate.break", SoundCategory.HOSTILE, 0.8f, 0.5f);
                p.playSound(p.getLocation(), "block.bell.resonate", SoundCategory.HOSTILE, 0.5f, 0.5f);
            }
            case 3 -> {
                p.sendMessage(ComandoCalamity.mensaje("En el spawn no se duerme. Muévete o la tierra te tragará."));
                p.playSound(p.getLocation(), "block.bell.use", SoundCategory.HOSTILE, 0.8f, 0.5f);
                Compat.spawn(p.getWorld(), Compat.REVERSE_PORTAL, p.getLocation().add(0, 0.2, 0), 20, 0.6, 0.1, 0.6, 0.02);
            }
            case 4 -> {
                p.showTitle(Paleta.titulo(Paleta.muerte("Muévete"), "Se abre una grieta",
                        Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(750)));
                Compat.sound(p.getWorld(), p.getLocation(), "block.end_portal_frame.fill",
                        (float) Math.max(1, radioAjeno / 16.0), 0.5f);
                Component ajeno = ComandoCalamity.mensaje("La tierra cruje bajo alguien que no se mueve.");
                for (Player o : Fx.viewersNear(p.getLocation(), radioAjeno)) {
                    if (!o.equals(p)) o.sendMessage(ajeno);
                }
            }
            default -> {
                // El 5.o: la cuenta ("La Grieta · N s") y las campanadas van en Huella.avisos.
            }
        }
    }

    /**
     * La Huella ha llegado al limite dentro del spawn. True si la grieta ya esta abierta (o se
     * abre ahora); false si la PARCA no puede venir (apagada o el tope global lleno): la Huella
     * lo vuelve a pedir el segundo siguiente, como fuera.
     */
    boolean abrir(Player p, int celdas) {
        UUID id = p.getUniqueId();
        if (enCurso.contains(id)) return true;
        Parca parca = hc.parca();
        if (parca == null) return false;
        Parca.Ajustes a = parca.ajustes();
        if (!a.activa || parca.vivas() >= a.maximoSimultaneas || parca.persigue(p)) return false;
        enCurso.add(id);
        World w = p.getWorld();
        Location boca = p.getLocation().clone();
        double[] centro = centro(w, boca);
        hc.plugin().bitacora().anotar("parca", "grieta", p.getName(),
                boca.getBlockX() + " " + boca.getBlockY() + " " + boca.getBlockZ(), "celdas " + celdas);
        Component ajeno = ComandoCalamity.mensaje(Component.text("Se abre una grieta bajo ")
                .append(Component.text(p.getName(), Paleta.DETALLE)).append(Component.text(".")));
        for (Player o : Fx.viewersNear(boca, 48)) if (!o.equals(p)) o.sendMessage(ajeno);

        // El destino se busca ya, mientras dura el desgarro.
        Location[] destino = {null};
        boolean[] buscado = {false};
        buscar(w, centro[0], centro[1], 0, l -> {
            destino[0] = l;
            buscado[0] = true;
        });
        int[] t = {0};
        BukkitTask[] tarea = new BukkitTask[1];
        tarea[0] = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(), () -> {
            Player j = hc.plugin().getServer().getPlayer(id);
            boolean sigue = j != null && j.isOnline() && !j.isDead() && j.getWorld() == w && hc.esHardcore(j) && hc.cuenta(j);
            if (!sigue) {
                terminar(tarea[0], id);
                return;
            }
            hc.seguro("parca", () -> desgarro(w, boca, j, t[0]));
            t[0] += 2;
            // Espera al destino como mucho 15 s; si no hay, la PARCA viene aqui mismo.
            if (t[0] < TICKS_DESGARRO || (!buscado[0] && t[0] < 300)) return;
            terminar(tarea[0], id);
            if (destino[0] == null) {
                hc.plugin().bitacora().anotar("parca", "grieta", j.getName(), "sin sitio seguro", "viene aqui");
                cerrar(w, boca);
                hc.seguro("parca", () -> reintentar(id, celdas, 30));
                return;
            }
            hc.seguro("parca", () -> arrastrar(j, boca, destino[0], celdas));
        }, 1L, 2L);
        tareas.add(tarea[0]);
        return true;
    }

    private void terminar(BukkitTask t, UUID id) {
        if (t != null) t.cancel();
        tareas.remove(t);
        enCurso.remove(id);
    }

    /** Centro de la caja del spawn en ese mundo, o donde esta si la caja es de otro mundo. */
    private double[] centro(World w, Location boca) {
        ConfigurationSection c = caja();
        NamespacedKey k = c == null ? null : NamespacedKey.fromString(c.getString("mundo", ""));
        if (c == null || k == null || !k.equals(w.getKey())) return new double[]{boca.getX(), boca.getZ()};
        return new double[]{(c.getInt("x1") + c.getInt("x2") + 1) / 2.0, (c.getInt("z1") + c.getInt("z2") + 1) / 2.0};
    }

    /**
     * El desgarro, cada 2 ticks durante 3 s: una rasgadura vertical que se abre bajo el jugador
     * (portal hacia dentro, almas de sculk, humo), sonidos graves que suben y, al principio, el
     * titulo y la Lentitud que lo sujeta.
     */
    private void desgarro(World w, Location boca, Player p, int t) {
        if (t == 0) {
            p.showTitle(Paleta.titulo(Paleta.muerte("Grieta"), "Te quedaste quieto en el spawn.",
                    Duration.ofMillis(200), Duration.ofMillis(2600), Duration.ofMillis(600)));
            Compat.apply(p, "slowness", TICKS_DESGARRO + 10, 3);
            Compat.sound(w, boca, "block.end_portal.spawn", 1.2f, 0.5f);
            Compat.sound(w, boca, "entity.warden.emerge", 1.5f, 0.6f);
        }
        if (t == 20) Compat.sound(w, boca, "block.respawn_anchor.charge", 1.5f, 0.5f);
        if (t == 40) Compat.sound(w, boca, "block.portal.trigger", 0.7f, 0.6f);
        double k = Math.min(1, t / (double) TICKS_DESGARRO);
        double ancho = 0.3 + 1.2 * k, alto = 2.8;
        // La rasgadura: una elipse vertical que mira a donde miraba el jugador.
        org.bukkit.util.Vector lado = boca.getDirection().setY(0);
        if (lado.lengthSquared() < 1e-4) lado = new org.bukkit.util.Vector(1, 0, 0);
        lado = new org.bukkit.util.Vector(-lado.getZ(), 0, lado.getX()).normalize();
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI * 2 / 16;
            Location l = boca.clone().add(0, alto / 2 + Math.sin(a) * alto / 2, 0).add(lado.clone().multiply(Math.cos(a) * ancho));
            Compat.spawn(w, Compat.REVERSE_PORTAL, l, 1, 0.02, 0.02, 0.02, 0.01);
        }
        Compat.spawn(w, Compat.PORTAL, boca.clone().add(0, 1.2, 0), 14, 0.8 * ancho, 1.0, 0.8 * ancho, 0.6);
        Compat.spawn(w, Compat.SCULK_SOUL, boca.clone().add(0, 0.1, 0), 3, ancho, 0.05, ancho, 0.02);
        if (t % 6 == 0) Compat.spawn(w, Compat.LARGE_SMOKE, boca.clone().add(0, 0.2, 0), 3, ancho, 0.1, ancho, 0.01);
    }

    private static void cerrar(World w, Location boca) {
        Compat.spawn(w, Compat.REVERSE_PORTAL, boca.clone().add(0, 1.2, 0), 60, 0.4, 1.0, 0.4, 0.2);
        Compat.spawn(w, Compat.SCULK_SOUL, boca.clone().add(0, 0.5, 0), 20, 0.5, 0.5, 0.5, 0.05);
        Compat.sound(w, boca, "entity.enderman.teleport", 1.2f, 0.5f);
        Compat.sound(w, boca, "block.respawn_anchor.deplete", 1.2f, 0.5f);
    }

    /** Se lo traga: se cierra aqui, aparece alla (chunk ya cargado) y alla le llega la PARCA. */
    private void arrastrar(Player j, Location boca, Location destino, int celdas) {
        World w = boca.getWorld();
        cerrar(w, boca);
        UUID id = j.getUniqueId();
        j.teleportAsync(destino, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, error) -> enPrincipal(() -> {
            Player k = hc.plugin().getServer().getPlayer(id);
            if (k == null || !k.isOnline()) return;
            boolean llego = error == null && Boolean.TRUE.equals(ok);
            if (llego) {
                World d = destino.getWorld();
                Compat.spawn(d, Compat.REVERSE_PORTAL, destino.clone().add(0, 1.2, 0), 60, 0.5, 1.0, 0.5, 0.2);
                Compat.spawn(d, Compat.SCULK_SOUL, destino.clone().add(0, 0.3, 0), 20, 0.6, 0.2, 0.6, 0.03);
                Compat.sound(d, destino, "block.portal.travel", 0.4f, 0.5f);
                Compat.apply(k, "darkness", 40, 0);
                k.sendMessage(ComandoCalamity.mensaje("La grieta te escupe lejos del spawn. Algo te ha seguido."));
                hc.plugin().bitacora().anotar("parca", "grieta", k.getName(), "arrastrado",
                        destino.getBlockX() + " " + destino.getBlockY() + " " + destino.getBlockZ(),
                        Math.round(Math.hypot(destino.getX() - boca.getX(), destino.getZ() - boca.getZ())) + " bloques");
            } else {
                hc.plugin().bitacora().anotar("parca", "grieta", k.getName(), "teleport fallido", "viene aqui");
            }
            reintentar(id, celdas, 30);
        }));
    }

    /**
     * La PARCA a por el, ya. Si no puede (el tope global lleno), se reintenta cada segundo
     * hasta "veces": la Huella no cuenta nada mientras tanto porque ya esta fuera del spawn.
     */
    private void reintentar(UUID id, int celdas, int veces) {
        Player j = hc.plugin().getServer().getPlayer(id);
        Parca parca = hc.parca();
        if (j == null || !j.isOnline() || parca == null || !hc.esHardcore(j) || !hc.cuenta(j)) return;
        boolean vino = hc.valor("parca", () -> parca.persigue(j) || parca.invocar(j, celdas, false), false);
        if (vino || veces <= 1 || !hc.plugin().isEnabled()) return;
        BukkitTask[] t = new BukkitTask[1];
        t[0] = hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(), () -> {
            tareas.remove(t[0]);
            reintentar(id, celdas, veces - 1);
        }, 20L);
        tareas.add(t[0]);
    }

    /**
     * Un sitio seguro al azar en el anillo, con su chunk cargado fuera del hilo principal. Si el
     * punto no vale (agua, lava, fuera del borde) se prueba otro, hasta intentos.
     */
    private void buscar(World w, double cx, double cz, int intento, Consumer<Location> listo) {
        Ajustes g = ajustes();
        if (intento >= g.intentos() || !hc.plugin().isEnabled()) {
            listo.accept(null);
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double[] xz = punto(cx, cz, g.distanciaMin(), g.distanciaMax(), r.nextDouble(), r.nextDouble());
        int x = (int) Math.floor(xz[0]), z = (int) Math.floor(xz[1]);
        if (!w.getWorldBorder().isInside(new Location(w, x + 0.5, w.getMinHeight() + 1, z + 0.5))) {
            buscar(w, cx, cz, intento + 1, listo);
            return;
        }
        w.getChunkAtAsync(x >> 4, z >> 4, true).whenComplete((chunk, error) -> enPrincipal(() -> {
            Location l = error == null && chunk != null ? sueloSeguro(w, x, z) : null;
            if (l != null) listo.accept(l);
            else buscar(w, cx, cz, intento + 1, listo);
        }));
    }

    private void enPrincipal(Runnable r) {
        if (hc.plugin().getServer().isPrimaryThread()) {
            hc.seguro("parca", r);
        } else if (hc.plugin().isEnabled()) {
            hc.plugin().getServer().getScheduler().runTask(hc.plugin(), () -> hc.seguro("parca", r));
        }
    }

    /**
     * Suelo firme en la columna (x, z) del chunk ya cargado: el bloque mas alto que para el
     * movimiento (sin hojas) si el mundo no tiene techo; con techo, el primero bajo el techo. Ni
     * liquidos, ni bloques que hacen dano, dos de aire encima y nada de lava al lado.
     */
    private static Location sueloSeguro(World w, int x, int z) {
        int min = w.getMinHeight() + 2, max = w.getMaxHeight() - 3;
        Block b = null;
        if (!w.hasCeiling()) {
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (y >= min && y <= max) b = w.getBlockAt(x, y, z);
            if (b != null && !seguro(b)) b = null;
        } else {
            boolean bajoTecho = false;
            for (int y = max; y >= min; y--) {
                Block c = w.getBlockAt(x, y, z);
                if (!bajoTecho) {
                    if (c.getType().isSolid()) bajoTecho = true;
                    continue;
                }
                if (seguro(c)) {
                    b = c;
                    break;
                }
            }
        }
        if (b == null) return null;
        Location l = new Location(w, x + 0.5, b.getY() + 1, z + 0.5);
        l.setYaw(ThreadLocalRandom.current().nextFloat() * 360f);
        return l;
    }

    private static boolean seguro(Block b) {
        Material m = b.getType();
        if (!m.isSolid() || b.isLiquid() || peligroso(m)) return false;
        Block pies = b.getRelative(BlockFace.UP), cabeza = pies.getRelative(BlockFace.UP);
        if (!pies.isPassable() || pies.isLiquid() || !cabeza.isPassable() || cabeza.isLiquid()) return false;
        if (peligroso(pies.getType()) || peligroso(cabeza.getType())) return false;
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (pies.getRelative(f).getType() == Material.LAVA || b.getRelative(f).getType() == Material.LAVA) return false;
        }
        return true;
    }

    private static boolean peligroso(Material m) {
        String n = m.name();
        return m == Material.LAVA || m == Material.WATER || m == Material.MAGMA_BLOCK || m == Material.CACTUS
                || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE || m == Material.FIRE
                || m == Material.SOUL_FIRE || m == Material.POWDER_SNOW || m == Material.SWEET_BERRY_BUSH
                || m == Material.POINTED_DRIPSTONE || m == Material.WITHER_ROSE || m == Material.COBWEB
                || n.endsWith("_LEAVES") || n.contains("PRESSURE_PLATE");
    }

    void parar() {
        for (BukkitTask t : tareas) t.cancel();
        tareas.clear();
        enCurso.clear();
    }

    // ================================================================ autotest

    /** "grieta": dentro/fuera de la caja, el umbral de 5 min con sus avisos y las distancias del destino. */
    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        Huella.Ajustes ha = Huella.Ajustes.defecto();
        Ajustes g = Ajustes.defecto();
        h.ok("dentro de la caja (esquina, centro y borde de arriba)",
                dentro(10, 60, 10, 20, 70, 20, 10.2, 60.0, 10.9) && dentro(20, 70, 20, 10, 60, 10, 15, 65, 15)
                        && dentro(10, 60, 10, 20, 70, 20, 20.99, 70.5, 20.99));
        h.ok("fuera de la caja (un bloque mas alla, debajo, otra esquina)",
                !dentro(10, 60, 10, 20, 70, 20, 21.0, 65, 15) && !dentro(10, 60, 10, 20, 70, 20, 15, 59.9, 15)
                        && !dentro(10, 60, 10, 20, 70, 20, 9.99, 65, 21));
        Umbral fuera = umbral(ha, g, false);
        Umbral spawn = umbral(ha, g, true);
        h.igual("fuera: 10 min", 600, fuera.limite());
        h.igual("en el spawn: 5 min", 300, spawn.limite());
        h.ok("en el spawn es de la Grieta; fuera no", spawn.spawn() && !fuera.spawn());
        h.igual("avisos en el spawn a la mitad", List.of(150, 210, 255, 270, 285),
                List.of(spawn.avisos()[0], spawn.avisos()[1], spawn.avisos()[2], spawn.avisos()[3], spawn.avisos()[4]));
        h.igual("avisos fuera, los de siempre", List.of(300, 420, 510, 540, 570),
                List.of(fuera.avisos()[0], fuera.avisos()[1], fuera.avisos()[2], fuera.avisos()[3], fuera.avisos()[4]));
        YamlConfiguration apagada = new YamlConfiguration();
        apagada.set("activa", false);
        h.igual("parca.spawn.activa false -> 10 min tambien dentro", 600, umbral(ha, Ajustes.de(apagada), true).limite());
        YamlConfiguration larga = new YamlConfiguration();
        larga.set("minutos", 30);
        h.igual("mas minutos que fuera -> se queda en los de fuera (la Huella no mide mas)", 600,
                umbral(ha, Ajustes.de(larga), true).limite());
        // 5 min en la Huella: 60 muestras quieto dentro de la caja llega; 59, no.
        Huella.Rastro r = new Huella.Rastro(ha.tamano());
        final long t0 = 1_000_000_000L;
        int a295 = -1;
        for (int s = 1; s <= 300; s++) {
            r.segundo(t0 + s * 1000L, 15.5, 65, 15.5, false, false, false, ha);
            if (s == 295) a295 = r.quieto;
        }
        h.ok("quieto 300 s en el spawn -> llega (295 s: " + a295 + ", 300 s: " + r.quieto + ")",
                r.quieto >= spawn.limite() && a295 < spawn.limite() && r.quieto < fuera.limite());
        double minD = Double.MAX_VALUE, maxD = 0;
        int cerca = 0;
        ThreadLocalRandom azar = ThreadLocalRandom.current();
        for (int i = 0; i < 2000; i++) {
            double[] p = punto(100, -50, g.distanciaMin(), g.distanciaMax(), azar.nextDouble(), azar.nextDouble());
            double d = Math.hypot(p[0] - 100, p[1] + 50);
            minD = Math.min(minD, d);
            maxD = Math.max(maxD, d);
            if (d < (g.distanciaMin() + g.distanciaMax()) / 2.0) cerca++;
        }
        h.ok("destino siempre entre 600 y 1500 (" + Math.round(minD) + "-" + Math.round(maxD) + ")",
                minD >= 600 - 1e-6 && maxD <= 1500 + 1e-6);
        h.ok("repartido por area: menos de la mitad en la mitad de dentro (" + cerca + "/2000)", cerca < 1000);
        h.cerca("u 0,0 -> 600 justos al este", 700, punto(100, 0, 600, 1500, 0, 0)[0], 1e-9);
        h.cerca("u 0,1 -> 1500 justos", 1500, Math.hypot(punto(0, 0, 600, 1500, 0.3, 1)[0], punto(0, 0, 600, 1500, 0.3, 1)[1]), 1e-6);
        return h.lineas();
    }
}
