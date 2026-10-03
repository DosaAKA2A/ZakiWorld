package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.kyori.adventure.text.Component;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
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
 * La Grieta (Calamity 1.1.0): el AFK en el spawn de Calamity.
 *
 * En la zona spawn (1.2: la de ZonaSpawn, la region de WorldGuard de hardcore.spawn.region o, sin
 * ella, la caja de la vara de hardcore.puertas.spawn) no viene la PARCA: a los parca.spawn.minutos (5)
 * se abre una grieta bajo el que no se mueve, se lo traga y lo escupe lejos, en un sitio seguro
 * al azar a 600-1500 bloques del spawn; y alli le aparece la PARCA. El spawn no es sitio para
 * aparcar a nadie, y la PARCA en el spawn seria una emboscada para los que acaban de entrar.
 *
 * Los avisos previos salen igual que fuera (cinco, a la misma proporcion del limite: 150, 210,
 * 255, 270 y 285 s con 5 minutos) pero con su propio texto.
 *
 * 1.8.2 · Los 5 minutos cuentan desde que se entra en el spawn (andando, volando o por
 * teletransporte) tras haber estado fuera al menos parca.minutos: la Huella vuelve a cero con sus
 * avisos. Al salir, la PARCA cuenta desde que sale. Quien vuelve antes sigue con lo que llevaba,
 * para que un vaiven por el borde no vuelva a cero a cada cruce. Dentro cuenta con su propia
 * ventana de parca.spawn.minutos aunque la PARCA de fuera tenga otra (Huella.Rastro.cambiarZona,
 * Huella.ajustesZona). Antes quien llegaba con la quietud de fuera veia abrirse la Grieta al momento.
 *
 * El destino: un punto al azar en el anillo 600-1500 alrededor del centro de la caja, dentro
 * del borde del mundo; su chunk se carga de forma asincrona y se busca suelo firme (ni agua,
 * ni lava, ni magma, ni cactus, dos de aire encima). Hasta intentos puntos; si ninguno vale (o el
 * teleport falla), 1.2: la PARCA ya no viene al spawn, que es zona segura; la grieta se vuelve a
 * abrir al cabo de PAUSA_MS. Si para entonces se ha movido fuera de la zona, viene donde este.
 *
 * 1.6.1 · Pescar en la zona spawn cuenta como estar activo ("mientras estoy pescando no deberia
 * aparecer ninguna grieta", Dosa): cada captura reinicia el reloj de la Grieta durante
 * pesca-gracia-segundos, y lanzar la caña tambien, pero solo si ha habido una captura en los
 * ultimos pesca-captura-minutos (un autoclicker que lanza y recoge sin sacar nada no la frena).
 * Fuera del spawn NO vale para la PARCA: alli una granja de pesca AFK sigue siendo AFK. La
 * regla vive en Huella.Rastro (pesca y pescaReinicia) y la prueba el autotest "grieta".
 *
 * El nucleo (umbral, dentro, punto) es estatico y sin Bukkit: el autotest "grieta" lo prueba.
 */
final class Grieta {

    /**
     * hardcore.parca.spawn.
     *
     * 1.6.1 · La pesca (pescaCuenta, pescaGraciaSegundos, pescaCapturaMinutos): ver Huella.Rastro.pesca.
     */
    record Ajustes(boolean activa, int minutos, int distanciaMin, int distanciaMax, int intentos,
                   boolean pescaCuenta, int pescaGraciaSegundos, int pescaCapturaMinutos) {

        static Ajustes de(ConfigurationSection s) {
            if (s == null) s = new YamlConfiguration();
            int min = Math.max(16, s.getInt("distancia-min", 600));
            return new Ajustes(
                    s.getBoolean("activa", true),
                    Math.max(1, s.getInt("minutos", 5)),
                    min,
                    Math.max(min, s.getInt("distancia-max", 1500)),
                    Math.max(1, Math.min(40, s.getInt("intentos", 12))),
                    s.getBoolean("pesca-cuenta", true),
                    Math.max(0, s.getInt("pesca-gracia-segundos", 90)),
                    Math.max(0, s.getInt("pesca-captura-minutos", 3)));
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
    /** 1.2: lo que se espera para volver a abrirla si no pudo llevarselo y sigue en el spawn. */
    static final long PAUSA_MS = 60_000;

    private final Hardcore hc;
    /** Quien esta siendo tragado ahora mismo (la Huella lo pide cada segundo). */
    private final Set<UUID> enCurso = new HashSet<>();
    /** 1.2: a quien no pudo llevarse, hasta cuando no se le vuelve a abrir (millis). */
    private final java.util.Map<UUID, Long> pausa = new java.util.HashMap<>();
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
     * El umbral de la Huella: el normal fuera del spawn; dentro, parca.spawn.minutos*60 y los
     * avisos a la misma proporcion, sin repetirse y antes del limite.
     *
     * 1.8.2: dentro ya no se recorta al de fuera. Antes era min(normal, minutos*60) porque el
     * anillo de la Huella no media mas, y en el SurvivalTest (parca.minutos 1) la Grieta salia al
     * minuto en vez de a los 5. Ahora la Huella cuenta el spawn con su propia ventana (Huella.ajustesZona).
     */
    static Umbral umbral(Huella.Ajustes h, Ajustes g, boolean dentro) {
        int normal = h.limite();
        if (!dentro || !g.activa()) return new Umbral(normal, h.avisos(), false);
        int limite = Math.max(60, g.minutos() * 60);
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

    /** Si esta dentro de la zona spawn (1.2: Hardcore.enSpawn, la misma para todo Calamity). */
    boolean enSpawn(Player p) {
        return hc.enSpawn(p);
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
                hc.cordura().destello(p, Component.text("Llevas mucho rato quieto. Si no te mueves, el suelo se abrirá.", Paleta.TEXTO), 3);
                p.playSound(p.getLocation(), "block.sculk_sensor.clicking", SoundCategory.HOSTILE, 0.6f, 0.5f);
            }
            case 2 -> {
                hc.cordura().destello(p, Component.text("El suelo empieza a agrietarse bajo tus pies: muévete.", Paleta.TEXTO), 3);
                p.playSound(p.getLocation(), "block.deepslate.break", SoundCategory.HOSTILE, 0.8f, 0.5f);
                p.playSound(p.getLocation(), "block.bell.resonate", SoundCategory.HOSTILE, 0.5f, 0.5f);
            }
            case 3 -> {
                p.sendMessage(ComandoCalamity.mensaje("Si sigues quieto en el spawn, una grieta te tragará y te dejará lejos, con la Parca detrás. Muévete."));
                p.playSound(p.getLocation(), "block.bell.use", SoundCategory.HOSTILE, 0.8f, 0.5f);
                Compat.spawn(p.getWorld(), Compat.REVERSE_PORTAL, p.getLocation().add(0, 0.2, 0), 20, 0.6, 0.1, 0.6, 0.02);
            }
            case 4 -> {
                p.showTitle(Paleta.titulo(Paleta.muerte("Muévete"), "Se va a abrir una grieta",
                        Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(750)));
                Compat.sound(p.getWorld(), p.getLocation(), "block.end_portal_frame.fill",
                        (float) Math.max(1, radioAjeno / 16.0), 0.5f);
                Component ajeno = ComandoCalamity.mensaje("El suelo cruje bajo alguien que lleva mucho rato sin moverse.");
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
     * abre ahora); false si la PARCA no puede venir (apagada o el tope global lleno) o si la ultima
     * no pudo llevarselo hace menos de PAUSA_MS: la Huella lo vuelve a pedir el segundo
     * siguiente, como fuera.
     */
    boolean abrir(Player p, int celdas) {
        UUID id = p.getUniqueId();
        if (enCurso.contains(id)) return true;
        Long hasta = pausa.get(id);
        if (hasta != null) {
            if (System.currentTimeMillis() < hasta) return false;
            pausa.remove(id);
        }
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
        double[] anillo = anillo(w, centro[0], centro[1], ajustes());
        Location[] destino = {null};
        boolean[] buscado = {false};
        buscar(w, centro[0], centro[1], anillo[0], anillo[1], 0, l -> {
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
            // Espera al destino como mucho 15 s; si no hay, no se lo lleva (ver fallo()).
            if (t[0] < TICKS_DESGARRO || (!buscado[0] && t[0] < 300)) return;
            terminar(tarea[0], id);
            if (destino[0] == null) {
                cerrar(w, boca);
                hc.seguro("parca", () -> fallo(j, celdas, "sin sitio seguro"));
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

    /**
     * 1.2 · La grieta no ha podido llevarselo (sin sitio seguro o teleport fallido). Si sigue en la
     * zona spawn, la PARCA no puede venir (ahi no aparece nunca): se vuelve a abrir en PAUSA_MS.
     * Si ya esta fuera, viene donde este, como antes.
     */
    private void fallo(Player j, int celdas, String porQue) {
        if (hc.enSpawn(j)) {
            pausa.put(j.getUniqueId(), System.currentTimeMillis() + PAUSA_MS);
            hc.plugin().bitacora().anotar("parca", "grieta", j.getName(), porQue, "reabre en " + PAUSA_MS / 1000 + " s");
            return;
        }
        hc.plugin().bitacora().anotar("parca", "grieta", j.getName(), porQue, "viene aqui");
        reintentar(j.getUniqueId(), celdas, 30);
    }

    /**
     * Las distancias que caben en el mundo: si el borde no deja llegar a distancia-max desde el
     * spawn, se queda en lo que deje (16 bloques antes del borde) y el minimo baja a la mitad de
     * eso. Mejor lejos dentro del mundo que ningun sitio.
     */
    static double[] anillo(double radioBorde, double desdeCentroBorde, Ajustes g) {
        double cabe = radioBorde - 16 - desdeCentroBorde;
        double max = Math.min(g.distanciaMax(), cabe);
        double min = Math.min(g.distanciaMin(), max / 2);
        return new double[]{Math.max(0, min), Math.max(0, max)};
    }

    private static double[] anillo(World w, double cx, double cz, Ajustes g) {
        org.bukkit.WorldBorder b = w.getWorldBorder();
        Location c = b.getCenter();
        return anillo(b.getSize() / 2, Math.hypot(cx - c.getX(), cz - c.getZ()), g);
    }

    /** Centro de la zona spawn de ese mundo (su caja), o donde esta si ese mundo no tiene. */
    private double[] centro(World w, Location boca) {
        ZonaSpawn zona = hc.zonaSpawn();
        ZonaSpawn.Zona z = zona == null ? null : zona.de(w);
        if (z == null) return new double[]{boca.getX(), boca.getZ()};
        return new double[]{z.centroX(), z.centroZ()};
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
                k.sendMessage(ComandoCalamity.mensaje("La grieta te ha escupido lejos del spawn. La Parca viene por ti."));
                hc.plugin().bitacora().anotar("parca", "grieta", k.getName(), "arrastrado",
                        destino.getBlockX() + " " + destino.getBlockY() + " " + destino.getBlockZ(),
                        Math.round(Math.hypot(destino.getX() - boca.getX(), destino.getZ() - boca.getZ())) + " bloques");
            } else {
                fallo(k, celdas, "teleport fallido");
                return;
            }
            reintentar(id, celdas, 30);
        }));
    }

    /**
     * La PARCA por el, ya. Si no puede (el tope global lleno), se reintenta cada segundo
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
    private void buscar(World w, double cx, double cz, double min, double max, int intento, Consumer<Location> listo) {
        Ajustes g = ajustes();
        if (intento >= g.intentos() || max < 32 || !hc.plugin().isEnabled()) {
            listo.accept(null);
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double[] xz = punto(cx, cz, min, max, r.nextDouble(), r.nextDouble());
        int x = (int) Math.floor(xz[0]), z = (int) Math.floor(xz[1]);
        if (!w.getWorldBorder().isInside(new Location(w, x + 0.5, w.getMinHeight() + 1, z + 0.5))) {
            buscar(w, cx, cz, min, max, intento + 1, listo);
            return;
        }
        w.getChunkAtAsync(x >> 4, z >> 4, true).whenComplete((chunk, error) -> enPrincipal(() -> {
            Location l = error == null && chunk != null ? sueloSeguro(w, x, z) : null;
            if (l != null) listo.accept(l);
            else buscar(w, cx, cz, min, max, intento + 1, listo);
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
        pausa.clear();
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
        h.igual("mas minutos que fuera -> los suyos (1.8.2: el spawn cuenta con su propia ventana)", 1800,
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
        double[] grande = anillo(30_000_000, 0, g);
        double[] chico = anillo(1000, 100, g);
        h.ok("borde grande: 600-1500 tal cual", grande[0] == 600 && grande[1] == 1500);
        h.ok("borde de 1000 con el spawn a 100 del centro: 442-884 (" + Math.round(chico[0]) + "-"
                + Math.round(chico[1]) + ")", chico[1] == 884 && chico[0] == 442);

        // 1.6.1 · Pesca en la zona spawn (en la zona de PremioPescao, quieto en la orilla).
        YamlConfiguration test = new YamlConfiguration();
        test.set("minutos", 1);
        test.set("avisos", List.of(20, 30, 40, 45, 50));
        Huella.Ajustes haTest = Huella.Ajustes.de(test);
        YamlConfiguration gTest = new YamlConfiguration();
        gTest.set("minutos", 1);
        Ajustes gt = Ajustes.de(gTest);
        h.ok("pesca: por defecto cuenta, 90 s de gracia, lanzar vale 3 min tras una captura",
                g.pescaCuenta() && g.pescaGraciaSegundos() == 90 && g.pescaCapturaMinutos() == 3);
        int[] ultima = {0};
        // Captura cada 20-40 s (y vuelve a lanzar al momento, con su mordida antes).
        java.util.function.IntUnaryOperator pescando = capturas(0, Integer.MAX_VALUE, ultima);
        int llega = simular(ha, g, true, 900, pescando);
        int llegaTest = simular(haTest, gt, true, 900, capturas(0, Integer.MAX_VALUE, ultima));
        h.ok("pescando en el spawn (captura cada 20-40 s) -> no llega en 15 min (defecto " + llega
                + ", Test " + llegaTest + ")", llega < 0 && llegaTest < 0);
        // Solo lanzando y recogiendo cada 6 s, sin sacar nada nunca: llega como si nada.
        java.util.function.IntUnaryOperator lanzando = s -> s % 6 == 0 ? LANZA : (s % 6 == 3 ? RECOGE : 0);
        int soloLanza = simular(ha, g, true, 900, lanzando);
        int soloLanzaTest = simular(haTest, gt, true, 900, lanzando);
        h.ok("solo lanzando, sin capturas -> llega a su hora (defecto " + soloLanza + " de " + spawn.limite()
                        + ", Test " + soloLanzaTest + " de 60)",
                soloLanza >= spawn.limite() - 5 && soloLanza <= spawn.limite() + 5
                        && soloLanzaTest >= 55 && soloLanzaTest <= 65);
        // 5 min pescando de verdad y luego solo lanzando: la gracia dura hasta 3 min tras la
        // ultima captura (+ 90 s) y a partir de ahi el reloj entero.
        int despues = simular(ha, g, true, 1500, capturas(0, 300, ultima));
        int desde = ultima[0] + g.pescaCapturaMinutos() * 60;
        int hasta = desde + g.pescaGraciaSegundos() + spawn.limite() + 5;
        h.ok("5 min capturando y luego solo lanzando -> llega pasado el margen (ultima captura " + ultima[0]
                + " s, llega " + despues + " s, entre " + desde + " y " + hasta + ")", despues > desde && despues <= hasta);
        // Fuera del spawn nada cambia: ni con la gracia recien renovada frena a la PARCA.
        int fueraPesca = simular(ha, g, false, 900, capturas(0, Integer.MAX_VALUE, ultima));
        int fueraNada = simular(ha, g, false, 900, s -> 0);
        h.ok("fuera del spawn pescando -> la PARCA llega igual (" + fueraPesca + " = " + fueraNada + ")",
                fueraPesca == fueraNada && fueraNada == fuera.limite());
        YamlConfiguration sinPesca = new YamlConfiguration();
        sinPesca.set("pesca-cuenta", false);
        int apagada2 = simular(ha, Ajustes.de(sinPesca), true, 900, capturas(0, Integer.MAX_VALUE, ultima));
        h.ok("pesca-cuenta false -> pescando en el spawn llega igual (" + apagada2 + ")",
                apagada2 >= spawn.limite() - 5 && apagada2 <= spawn.limite() + 5);

        // 1.8.2 · La Grieta cuenta sus 5 minutos desde que se entra en el spawn tras un rato fuera,
        // y la PARCA los suyos desde que se sale; un vaiven por el borde no vuelve a cero. Con los
        // ajustes del config.yml del jar (Parca a 5 min). Tramos: {1 = spawn / 0 = fuera, segundos},
        // quieto, o {.., .., 1} andando.
        YamlConfiguration jar = jar();
        h.igual("el config.yml del jar trae parca.spawn.minutos 5", 5,
                jar == null ? -1 : jar.getInt("hardcore.parca.spawn.minutos", -1));
        h.igual("y sin la clave en el config tambien son 5", 5, Ajustes.defecto().minutos());
        Huella.Ajustes hj = Huella.Ajustes.de(jar == null ? null : jar.getConfigurationSection("hardcore.parca"));
        Ajustes gj = Ajustes.de(jar == null ? null : jar.getConfigurationSection("hardcore.parca.spawn"));
        int[][] entra = tramos(hj, gj, true, new int[][]{{1, 30}, {0, 600, 1}, {0, 180}, {1, 400}});
        h.ok("vuelve al spawn tras 10 min andando fuera y 3 quieto -> empieza de cero y la Grieta espera sus 300 s"
                        + " (quieto al entrar " + entra[3][1] + ", llega a los " + entra[3][0] + " s)",
                entra[1][0] < 0 && entra[2][0] < 0 && entra[3][1] == 0 && entra[3][0] == 300);
        int[][] vuelve = tramos(hj, gj, true, new int[][]{{1, 200}, {0, 30}, {1, 400}});
        h.ok("sale 30 s y vuelve -> la Grieta sigue contando lo de antes (llega a los " + vuelve[2][0]
                + " s de volver, no a los 300)", vuelve[1][0] < 0 && vuelve[2][0] == 70);
        int[][] sale = tramos(hj, gj, true, new int[][]{{1, 250}, {0, 400}});
        h.ok("sale del spawn con 250 s quieto -> la Parca cuenta desde que sale (quieto al salir " + sale[1][1]
                + ", llega a los " + sale[1][0] + " s)", sale[0][0] < 0 && sale[1][1] == 0 && sale[1][0] == 300);
        int[][] agua = tramos(hj, gj, false, new int[][]{{0, 250}, {1, 400}});
        h.ok("llevado al spawn por el agua (sin teclas) no vuelve a cero: llega a los " + agua[1][0] + " s",
                agua[1][1] >= 245 && agua[1][0] > 0 && agua[1][0] <= 55);
        int[][] vaiven = new int[200][];
        for (int i = 0; i < vaiven.length; i++) vaiven[i] = new int[]{i % 2, 3};
        int llegaBorde = llegada(vaiven, tramos(hj, gj, false, vaiven));
        h.ok("un AFK al que el agua mete y saca del spawn no se libra (llega a los " + llegaBorde + " s)",
                llegaBorde > 0 && llegaBorde <= 305);
        int llegaTeclas = llegada(vaiven, tramos(hj, gj, true, vaiven));
        h.ok("ni uno con un vaiven W/S por el borde que cruza cada 3 s con teclas (llega a los " + llegaTeclas + " s)",
                llegaTeclas > 0 && llegaTeclas <= 305);
        int[][] lento = new int[8][];
        for (int i = 0; i < lento.length; i++) lento[i] = new int[]{1 - i % 2, 240};
        int llegaLento = llegada(lento, tramos(hj, gj, true, lento));
        h.ok("ni cruzando con teclas cada 4 min (llega a los " + llegaLento + " s)", llegaLento > 0 && llegaLento <= 490);
        // El SurvivalTest: parca.minutos 1 y parca.spawn de serie. La PARCA al minuto fuera, la Grieta a los 5.
        int[][] enTest = tramos(haTest, Ajustes.defecto(), true, new int[][]{{0, 120, 1}, {1, 400}, {0, 100}});
        h.ok("con parca.minutos 1 (Test) la Grieta sigue esperando 5 min (llega a los " + enTest[1][0]
                        + " s) y la Parca fuera, 1 min (" + enTest[2][0] + " s)",
                enTest[0][0] < 0 && enTest[1][0] == 300 && enTest[2][0] == 60);
        int[][] saleTest = tramos(haTest, Ajustes.defecto(), true, new int[][]{{1, 120}, {0, 100}});
        h.ok("en el Test, 2 min quieto en el spawn y sale -> la Parca no le llega al salir, sino al minuto ("
                + saleTest[1][0] + " s)", saleTest[0][0] < 0 && saleTest[1][0] == 60);
        int vaivenTest = llegada(vaiven, tramos(haTest, Ajustes.defecto(), true, vaiven));
        h.ok("en el Test, el vaiven con teclas por el borde tampoco se libra (llega a los " + vaivenTest + " s)",
                vaivenTest > 0 && vaivenTest <= 305);
        return h.lineas();
    }

    /** El config.yml que va dentro del jar, o null si no se puede leer. */
    static YamlConfiguration jar() {
        try (java.io.InputStream in = Grieta.class.getClassLoader().getResourceAsStream("config.yml")) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 1.8.2 · Un jugador que pasa por tramos {1 = zona spawn / 0 = fuera, segundos}, quieto (con
     * teclas si "propio", como un vaiven W/S; si no, llevado por el agua) o, con un tercer 1,
     * andando con teclas (4 bloques por segundo). Como en Huella.segundo: al cambiar de tramo,
     * Rastro.cambiarZona, y cada segundo con la ventana de su zona. Por tramo devuelve
     * {segundo del tramo en que llega a su limite o -1, quieto al empezar el tramo}.
     */
    private static int[][] tramos(Huella.Ajustes ha, Ajustes g, boolean propio, int[][] tramos) {
        Huella.Rastro r = new Huella.Rastro(ha.tamano());
        int[][] out = new int[tramos.length][];
        final long t0 = 1_000_000_000L;
        long ahora = t0;
        for (int i = 0; i < tramos.length; i++) {
            boolean dentro = tramos[i][0] == 1;
            boolean anda = tramos[i].length > 2 && tramos[i][2] == 1;
            r.cambiarZona(dentro, propio || anda, ha.limite(), g.activa());
            Umbral u = umbral(ha, g, dentro);
            Huella.Ajustes az = Huella.ajustesZona(ha, u);
            int llega = -1, alEmpezar = r.quieto;
            for (int s = 1; s <= tramos[i][1]; s++) {
                ahora += 1000L;
                double x = (dentro ? 15.5 : 815.5) + (anda ? s * 4 : 0);
                r.segundo(ahora, x, 65, 15.5, false, propio || anda, false, az);
                if (llega < 0 && r.quieto >= u.limite()) llega = s;
            }
            out[i] = new int[]{llega, alEmpezar};
        }
        return out;
    }

    /** El segundo, contado desde el principio de los tramos, en que llega; -1 si no llega. */
    private static int llegada(int[][] tramos, int[][] res) {
        for (int i = 0, antes = 0; i < res.length; antes += tramos[i][1], i++) {
            if (res[i][0] >= 0) return antes + res[i][0];
        }
        return -1;
    }

    /** Eventos de pesca de la simulacion. */
    private static final int LANZA = 1, PICA = 2, RECOGE = 3, CAPTURA = 4;

    /**
     * Capturas cada 20-40 s (al azar fijo) entre desde y hasta, con la mordida el segundo antes y
     * el lanzamiento el segundo despues; pasado "hasta", solo lanzar y recoger cada 6 s. Apunta
     * en ultima[0] el segundo de la ultima captura.
     */
    private static java.util.function.IntUnaryOperator capturas(int desde, int hasta, int[] ultima) {
        long[] semilla = {12345};
        int[] proxima = {desde + 20};
        ultima[0] = 0;
        return s -> {
            if (s <= hasta) {
                if (s == proxima[0] - 1) return PICA;
                if (s == proxima[0]) {
                    ultima[0] = s;
                    semilla[0] = semilla[0] * 6364136223846793005L + 1442695040888963407L;
                    proxima[0] = s + 20 + (int) ((semilla[0] >>> 33) % 21);
                    return CAPTURA;
                }
                return s == ultima[0] + 1 ? LANZA : 0;
            }
            return s % 6 == 0 ? LANZA : (s % 6 == 3 ? RECOGE : 0);
        };
    }

    /**
     * Un pescador quieto en la orilla durante "segundos": los eventos de pesca van a Rastro.pesca
     * y cada segundo pasa por pescaReinicia (con spawn) antes de la Huella. Devuelve el segundo
     * en que la Huella llega a su limite (la Grieta en el spawn, la PARCA fuera) o -1.
     */
    private static int simular(Huella.Ajustes ha, Ajustes g, boolean spawn, int segundos,
                               java.util.function.IntUnaryOperator eventos) {
        Umbral u = umbral(ha, g, spawn);
        // 1.8.2: con la ventana de la zona, como Huella.segundo.
        Huella.Ajustes az = Huella.ajustesZona(ha, u);
        Huella.Rastro r = new Huella.Rastro(az.tamano());
        int limite = u.limite();
        final long t0 = 1_000_000_000L;
        for (int s = 1; s <= segundos; s++) {
            long ahora = t0 + s * 1000L;
            int ev = eventos.applyAsInt(s);
            // Se apunta tambien fuera (el peor caso: pesco en el spawn y salio con la gracia puesta).
            if (ev != 0) r.pesca(ahora, ev == CAPTURA, g);
            if (!r.pescaReinicia(ahora, spawn && g.activa(), g)) {
                r.segundo(ahora, 280.5, 40, -245.5, false, false, false, az);
            }
            if (r.quieto >= limite) return s;
        }
        return -1;
    }
}
