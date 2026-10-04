package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.dungeonloot.Boveda;
import net.ederus.edm.dungeonloot.BovedaAbiertaEvent;
import net.ederus.edm.dungeonloot.BovedaAbrirEvent;
import net.ederus.edm.dungeonloot.BovedaVaciadaEvent;
import net.ederus.edm.dungeonloot.DungeonLootPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Vault;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Calamity 1.11 · La Boveda Caida: un evento que cae del cielo.
 *
 * Cada cada-minutos (150, +-variacion 20 %), si hay al menos minimo-jugadores (3) que cuentan en
 * Calamity fuera del spawn, cae una boveda ominosa (caja calamity_caida de EDM) en un punto al azar
 * entre distancia-min y distancia-max del centro del spawn: suelo firme, sin agua ni lava, dentro del
 * borde, fuera de la zona spawn y de excluir-regiones. El sitio se busca como la Grieta: su chunk se
 * carga fuera del hilo principal y se prueba otro si no vale.
 *
 * La caida: un bloque de boveda que baja acelerando con un poco de humo, el golpe (una explosion
 * pequena, polvo de piedra y el sonido) y la boveda plantada. El aviso a todo Calamity dice el bioma,
 * la distancia redondeada a 100 y el rumbo, nunca las coordenadas. Durante haz-minutos (5) se ve un
 * haz: una columna de polvo violeta que el cliente pinta hasta 512 bloques (particulas "force"), y de
 * cerca un BlockDisplay alto (no persistente: se rehace si su chunk se vuelve a cargar).
 *
 * La guardia nace cuando llega el primero (a guardia.radio): una tanda de mobs del bioma alrededor y,
 * con guardia.minijefe, el minijefe de ese bioma (sin presa: no le cuenta el descanso de minijefe a
 * nadie). Asi no nacen mobs que nadie ve y que el retiro de MobsLethal se llevaria.
 *
 * Solo la abre el primero que llega con la Llave Ominosa: la apertura se apunta al cobrar la llave
 * (BovedaAbiertaEvent) y cualquier otro intento se corta antes (BovedaAbrirEvent). Al terminar de
 * soltar (BovedaVaciadaEvent), o a los vida-minutos (30) sin abrir, desaparece. Si su chunk no esta
 * cargado en ese momento, la boveda sigue apuntada en EDM (asi nadie la abre con una llave de las de
 * fabrica) y se quita en cuanto se cargue (por-quitar).
 *
 * Todo el estado va en hardcore-datos.yml (boveda-caida.*): un reinicio con una Boveda Caida activa
 * la deja donde esta, con su vida, su haz y su guardia como estuvieran.
 *
 * Tambien escucha la apertura de la Boveda de Ruinas (caja calamity_ruinas), que paga Ruinas.
 */
final class BovedaCaida implements Listener {

    static final String RUTA = "boveda-caida";

    /** Lo de serie del botin (hardcore.boveda-caida.botin). */
    static final List<Map<?, ?>> BOTIN_DE_SERIE = List.of(
            Map.of("objeto", "esencia", "prob", 1.0, "min", 8, "max", 14),
            Map.of("objeto", "reliquia-3", "prob", 0.80, "min", 1, "max", 2),
            Map.of("objeto", "reliquia-4", "prob", 0.25, "min", 1, "max", 1),
            Map.of("objeto", "cristal", "prob", 0.50, "min", 1, "max", 1),
            Map.of("objeto", "frasco-1", "prob", 0.50, "min", 1, "max", 2),
            Map.of("objeto", "tintura", "prob", 0.60, "min", 2, "max", 3),
            Map.of("objeto", "llave-umbral", "prob", 0.50, "min", 1, "max", 2));

    /** Como se le dice al jugador cada bioma de Panacea (boveda-caida.nombres-biomas los cambia). */
    static final Map<String, String> BIOMAS = Map.ofEntries(
            Map.entry("bamboo_valley", "el valle de bambú"),
            Map.entry("condemned_taiga", "la taiga condenada"),
            Map.entry("conure_conclave", "el cónclave"),
            Map.entry("creeper_dominion", "el dominio creeper"),
            Map.entry("crimson_organism", "el organismo carmesí"),
            Map.entry("honeybee_biome", "el colmenar"),
            Map.entry("horsetail_tropics", "el trópico"),
            Map.entry("hungering_jungle", "la jungla hambrienta"),
            Map.entry("polypore_plains", "las llanuras de hongos"),
            Map.entry("quicksand_springs", "los manantiales de arena"),
            Map.entry("ravenous_greenwood", "el bosque voraz"),
            Map.entry("sweltering_swamp", "el pantano sofocante"),
            Map.entry("wildflower_bog", "la ciénaga de flores"));

    private static final int COLOR_HAZ = 0xB58CFF;

    private final Hardcore hc;
    private final SecureRandom azar = new SecureRandom();
    private BukkitTask reloj;
    private final Set<BukkitTask> tareas = new HashSet<>();
    private BlockDisplay haz;
    /** Los chunks que se tienen cargados durante una caida: {mundo (2 longs), x, z}. */
    private final List<long[]> tickets = new ArrayList<>();
    private boolean cayendo;
    private int segundos;

    BovedaCaida(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        String cajas = PuenteBovedas.asegurar();
        hc.plugin().getLogger().info("[Calamity] Bóvedas de EDM: " + (cajas == null
                ? "el módulo dungeonloot no está (EDM 1.78.1 o superior): sin Bóvedas de Ruinas ni Caídas." : cajas));
        Subcomandos.lw().registrar("vault", "vault <drop [random]|info|clear>: la Bóveda Caída (forzar, ver, quitar)",
                "ederus.mundos", this::comando, args -> args.length == 2 ? List.of("drop", "info", "clear")
                        : args.length == 3 && args[1].equalsIgnoreCase("drop") ? List.of("random") : List.of());
        Autotest.registrar("boveda-caida", BovedaCaida::autotest);
        reloj = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(), () -> hc.seguro("boveda-caida", this::segundo), 60L, 20L);
    }

    void parar() {
        HandlerList.unregisterAll(this);
        if (reloj != null) reloj.cancel();
        reloj = null;
        for (BukkitTask t : tareas) t.cancel();
        tareas.clear();
        quitarHaz();
        for (long[] k : tickets) {
            World w = hc.plugin().getServer().getWorld(new UUID(k[0], k[1]));
            if (w != null) w.removePluginChunkTicket((int) k[2], (int) k[3], hc.plugin());
        }
        tickets.clear();
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = hc.cfg().getConfigurationSection(RUTA);
        return s == null ? new YamlConfiguration() : s;
    }

    private boolean activo() {
        return cfg().getBoolean("activo", true);
    }

    private YamlConfiguration datos() {
        return hc.datos();
    }

    private boolean hayActiva() {
        return datos().isSet(RUTA + ".activa.mundo");
    }

    // ------------------------------------------------------------------ el reloj

    private void segundo() {
        segundos++;
        long ahora = System.currentTimeMillis();
        quitarPendientes();
        if (hayActiva()) {
            vigilarActiva(ahora);
            return;
        }
        if (!activo() || cayendo) return;
        long proxima = datos().getLong(RUTA + ".proxima", 0);
        if (proxima <= 0) {
            programar(ahora);
            return;
        }
        if (ahora < proxima || segundos % 10 != 0) return;
        if (fueraDelSpawn().size() < Math.max(0, cfg().getInt("minimo-jugadores", 3))) return;
        lanzar(null, "reloj");
    }

    private void programar(long desde) {
        long cada = Math.max(5, cfg().getLong("cada-minutos", 150)) * 60_000L;
        double v = Math.max(0, Math.min(0.9, cfg().getDouble("variacion", 0.20)));
        long proxima = desde + Math.round(cada * (1 - v + azar.nextDouble() * 2 * v));
        datos().set(RUTA + ".proxima", proxima);
        hc.marcarSucio();
    }

    /** Los que cuentan en Calamity y estan fuera del spawn. */
    private List<Player> fueraDelSpawn() {
        List<Player> out = new ArrayList<>();
        for (World w : hc.plugin().getServer().getWorlds()) {
            if (!hc.esHardcore(w)) continue;
            for (Player p : w.getPlayers()) if (hc.cuenta(p) && !hc.enSpawn(p)) out.add(p);
        }
        return out;
    }

    private Location sitioActivo() {
        String mundo = datos().getString(RUTA + ".activa.mundo");
        World w = mundo == null ? null : hc.plugin().getServer().getWorld(mundo);
        if (w == null) return null;
        return new Location(w, datos().getInt(RUTA + ".activa.x"), datos().getInt(RUTA + ".activa.y"),
                datos().getInt(RUTA + ".activa.z"));
    }

    private void vigilarActiva(long ahora) {
        Location l = sitioActivo();
        if (l == null) return;
        boolean abierta = datos().getBoolean(RUTA + ".activa.abierta", false);
        if (!abierta && ahora >= datos().getLong(RUTA + ".activa.vence", 0)) {
            retirarActiva("caduca");
            return;
        }
        // Abierta y sin BovedaVaciadaEvent (un reinicio o un /calamidad reload a mitad de soltar, o EDM que
        // corto la apertura): sin esto se quedaba activa para siempre y no volvia a caer ninguna. EDM suelta
        // todo en unos segundos; pasado un minuto, se quita igual. Sin abierta-en (datos de antes), ya.
        if (abierta && ahora - datos().getLong(RUTA + ".activa.abierta-en", 0) >= 60_000L) {
            retirarActiva("abierta");
            return;
        }
        long hazHasta = datos().getLong(RUTA + ".activa.haz-hasta", 0);
        if (ahora < hazHasta) {
            if (segundos % 2 == 0) columna(l);
            if (segundos % 5 == 0) asegurarHaz(l);
        } else {
            quitarHaz();
        }
        if (!abierta && !datos().getBoolean(RUTA + ".activa.guardia", false) && cfg().getBoolean("guardia.activo", true)) {
            double r = Math.max(8, cfg().getDouble("guardia.radio", 40));
            for (Player p : l.getWorld().getPlayers()) {
                if (!hc.cuenta(p)) continue;
                double dx = p.getLocation().getX() - l.getX(), dz = p.getLocation().getZ() - l.getZ();
                if (dx * dx + dz * dz > r * r) continue;
                guardia(p, l);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ la caida

    /** Busca sitio y la deja caer. cerca: con un jugador, a su alrededor; null, en el anillo del spawn. */
    void lanzar(Player cerca, String por) {
        if (cayendo || hayActiva()) return;
        if (PuenteBovedas.modulo() == null) {
            hc.plugin().getLogger().warning("[Calamity] Bóveda Caída: sin el módulo dungeonloot de EDM no hay bóveda.");
            programar(System.currentTimeMillis());
            return;
        }
        cayendo = true;
        Consumer<Location> listo = sitio -> {
            if (sitio == null) {
                cayendo = false;
                hc.plugin().bitacora().anotar("boveda-caida", "sin-sitio", por);
                // Se vuelve a intentar en 10 minutos, no en otro intervalo entero.
                datos().set(RUTA + ".proxima", System.currentTimeMillis() + 10 * 60_000L);
                hc.marcarSucio();
                return;
            }
            caer(sitio, por);
        };
        if (cerca != null) {
            listo.accept(sitioCerca(cerca));
            return;
        }
        World w = mundoCalamity();
        if (w == null) {
            listo.accept(null);
            return;
        }
        double[] c = centro(w);
        buscar(w, c[0], c[1], Math.max(64, cfg().getDouble("distancia-min", 600)),
                Math.max(cfg().getDouble("distancia-min", 600) + 32, cfg().getDouble("distancia-max", 1500)), 0, listo);
    }

    private World mundoCalamity() {
        List<Player> fuera = fueraDelSpawn();
        if (!fuera.isEmpty()) return fuera.get(0).getWorld();
        for (World w : hc.plugin().getServer().getWorlds()) if (hc.esHardcore(w)) return w;
        return null;
    }

    /** El centro de la zona spawn, o el spawn del mundo. */
    private double[] centro(World w) {
        ZonaSpawn zs = hc.zonaSpawn();
        ZonaSpawn.Zona z = zs == null ? null : zs.de(w);
        if (z != null) return new double[]{(z.x1() + z.x2()) / 2.0, (z.z1() + z.z2()) / 2.0};
        Location s = w.getSpawnLocation();
        return new double[]{s.getX(), s.getZ()};
    }

    private void buscar(World w, double cx, double cz, double min, double max, int intento, Consumer<Location> listo) {
        if (intento >= Math.max(1, cfg().getInt("intentos", 8)) || !hc.plugin().isEnabled()) {
            listo.accept(null);
            return;
        }
        double ang = azar.nextDouble() * Math.PI * 2, d = min + azar.nextDouble() * (max - min);
        int x = (int) Math.floor(cx + Math.cos(ang) * d), z = (int) Math.floor(cz + Math.sin(ang) * d);
        if (!w.getWorldBorder().isInside(new Location(w, x + 0.5, w.getMinHeight() + 1, z + 0.5))) {
            buscar(w, cx, cz, min, max, intento + 1, listo);
            return;
        }
        w.getChunkAtAsync(x >> 4, z >> 4, true).whenComplete((chunk, error) -> enPrincipal(() -> {
            Location l = error == null && chunk != null ? suelo(w, x, z) : null;
            if (l != null) listo.accept(l);
            else buscar(w, cx, cz, min, max, intento + 1, listo);
        }));
    }

    private void enPrincipal(Runnable r) {
        if (hc.plugin().getServer().isPrimaryThread()) {
            hc.seguro("boveda-caida", r);
        } else if (hc.plugin().isEnabled()) {
            hc.plugin().getServer().getScheduler().runTask(hc.plugin(), () -> hc.seguro("boveda-caida", r));
        }
    }

    /** A 15-35 bloques de un jugador, en chunks ya cargados. */
    private Location sitioCerca(Player p) {
        World w = p.getWorld();
        for (int i = 0; i < 16; i++) {
            double ang = azar.nextDouble() * Math.PI * 2, d = 15 + azar.nextDouble() * 20;
            int x = (int) Math.floor(p.getLocation().getX() + Math.cos(ang) * d);
            int z = (int) Math.floor(p.getLocation().getZ() + Math.sin(ang) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Location l = suelo(w, x, z);
            if (l != null) return l;
        }
        return null;
    }

    /**
     * El sitio de la boveda en la columna (x, z): encima del bloque mas alto que para el movimiento
     * (sin hojas), que tiene que ser solido y no liquido, con dos de aire encima, sin lava al lado,
     * fuera del spawn y de las regiones vetadas. null si no vale.
     */
    private Location suelo(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y <= w.getMinHeight() + 2 || y >= w.getMaxHeight() - 4) return null;
        Block abajo = w.getBlockAt(x, y, z);
        if (!abajo.getType().isSolid() || abajo.isLiquid() || abajo.getType() == Material.MAGMA_BLOCK) return null;
        Block sitio = abajo.getRelative(0, 1, 0), arriba = abajo.getRelative(0, 2, 0);
        if (!(sitio.getType().isAir() || (sitio.isReplaceable() && !sitio.isLiquid()))) return null;
        if (!arriba.isPassable() || arriba.isLiquid() || sitio.isLiquid()) return null;
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Material m = sitio.getRelative(d[0], 0, d[1]).getType();
            if (m == Material.LAVA || m == Material.WATER) return null;
        }
        Location l = sitio.getLocation();
        if (hc.enSpawn(l)) return null;
        List<String> regiones = cfg().isList("excluir-regiones") ? cfg().getStringList("excluir-regiones") : Ruinas.EXCLUIR_DE_SERIE;
        for (String r : regiones) if (RegionesWg.dentro(w, r, x, l.getBlockY(), z)) return null;
        return l;
    }

    /** La animacion: baja, golpea y queda plantada. */
    private void caer(Location sitio, String por) {
        World w = sitio.getWorld();
        int cx = sitio.getBlockX() >> 4, cz = sitio.getBlockZ() >> 4;
        w.addPluginChunkTicket(cx, cz, hc.plugin());
        long[] ticket = {w.getUID().getMostSignificantBits(), w.getUID().getLeastSignificantBits(), cx, cz};
        tickets.add(ticket);
        Location base = sitio.clone().add(0.5, 0, 0.5);
        double alto = 42;
        BlockDisplay bloque;
        try {
            BlockData vista = Material.VAULT.createBlockData();
            if (vista instanceof Vault v) v.setOminous(true);
            bloque = w.spawn(base.clone().add(0, alto, 0), BlockDisplay.class, e -> {
                e.setBlock(vista);
                e.setPersistent(false);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setTeleportDuration(2);
                e.setTransformation(new Transformation(new Vector3f(-0.5f, 0f, -0.5f), new Quaternionf(),
                        new Vector3f(1f, 1f, 1f), new Quaternionf()));
            });
        } catch (Throwable t) {
            bloque = null;
        }
        Compat.sound(w, base.clone().add(0, alto, 0), "entity.blaze.shoot", 1.0f, 0.5f);
        final BlockDisplay caja = bloque;
        final int pasos = 20;
        final int[] paso = {0};
        final BukkitTask[] t = new BukkitTask[1];
        t[0] = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(), () -> {
            paso[0]++;
            double f = Math.min(1, paso[0] / (double) pasos);
            Location ahora = base.clone().add(0, alto * (1 - f * f), 0);
            if (caja != null && caja.isValid()) caja.teleport(ahora);
            if (paso[0] % 2 == 0) w.spawnParticle(Particle.LARGE_SMOKE, ahora.clone().add(0, 1.2, 0), 2, 0.15, 0.2, 0.15, 0.01);
            if (paso[0] < pasos) return;
            t[0].cancel();
            tareas.remove(t[0]);
            if (caja != null) caja.remove();
            hc.seguro("boveda-caida", () -> impacto(sitio, por));
            BukkitTask suelta = hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(),
                    () -> {
                        w.removePluginChunkTicket(cx, cz, hc.plugin());
                        tickets.remove(ticket);
                    }, 100L);
            tareas.add(suelta);
        }, 1L, 2L);
        tareas.add(t[0]);
    }

    private void impacto(Location sitio, String por) {
        cayendo = false;
        World w = sitio.getWorld();
        Location centro = sitio.clone().add(0.5, 0.5, 0.5);
        w.spawnParticle(Particle.EXPLOSION, centro, 1, 0, 0, 0, 0);
        w.spawnParticle(Particle.BLOCK, centro, 40, 1.2, 0.3, 1.2, 0.1, Material.DEEPSLATE.createBlockData());
        w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, centro, 6, 0.8, 0.2, 0.8, 0.01);
        Compat.sound(w, centro, "entity.generic.explode", 1.4f, 0.7f);
        Compat.sound(w, centro, "block.vault.place", 1.0f, 0.8f);

        DungeonLootPlugin d = PuenteBovedas.modulo();
        Boveda b = d == null ? null : d.plantar(PuenteBovedas.CAJA_CAIDA, sitio.getBlock());
        if (b == null) {
            hc.plugin().bitacora().anotar("boveda-caida", "fallo", "no se pudo plantar", sitio.getBlockX() + " " + sitio.getBlockY() + " " + sitio.getBlockZ());
            programar(System.currentTimeMillis());
            return;
        }
        long ahora = System.currentTimeMillis();
        String bioma = Minijefes.bioma(sitio);
        String base = RUTA + ".activa.";
        datos().set(base + "mundo", w.getName());
        datos().set(base + "x", sitio.getBlockX());
        datos().set(base + "y", sitio.getBlockY());
        datos().set(base + "z", sitio.getBlockZ());
        datos().set(base + "cae", ahora);
        datos().set(base + "vence", ahora + Math.max(1, cfg().getLong("vida-minutos", 30)) * 60_000L);
        datos().set(base + "haz-hasta", ahora + Math.max(0, cfg().getLong("haz-minutos", 5)) * 60_000L);
        datos().set(base + "abierta", false);
        datos().set(base + "guardia", false);
        datos().set(base + "bioma", bioma);
        datos().set(RUTA + ".proxima", null);
        hc.guardarYa();
        asegurarHaz(sitio);
        columna(sitio);

        double[] c = centro(w);
        double dist = Math.hypot(sitio.getX() - c[0], sitio.getZ() - c[1]);
        int redondo = redondear(dist);
        String donde = nombreBioma(bioma, cfg().getConfigurationSection("nombres-biomas"));
        String rumbo = rumbo(sitio.getX() - c[0], sitio.getZ() - c[1]);
        Component aviso = ComandoCalamity.mensaje(Component.text("Una ")
                .append(Paleta.detalle("Bóveda Caída"))
                .append(Component.text(" se estrelló en " + donde + ", unos "))
                .append(Paleta.cifra(Altar.miles(redondo)))
                .append(Component.text(" bloques al " + rumbo + " del spawn.")));
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) {
            if (!hc.esHardcore(p)) continue;
            p.sendMessage(aviso);
            Compat.soundPlayers(p.getWorld(), p.getLocation(), "entity.generic.explode", 0.35f, 0.5f);
        }
        hc.plugin().bitacora().anotar("boveda-caida", "cae", w.getName(), sitio.getBlockX() + " " + sitio.getBlockY() + " " + sitio.getBlockZ(),
                bioma, Math.round(dist) + " bloques", rumbo, por);
        Telemetria te = hc.telemetria();
        if (te != null) {
            Map<String, Object> campos = new LinkedHashMap<>();
            campos.put("por", por);
            campos.put("bioma", bioma);
            campos.put("distancia", Math.round(dist));
            campos.put("jugadores_fuera", fueraDelSpawn().size());
            hc.seguro("telemetria", () -> te.suceso("boveda-caida", null, campos));
        }
    }

    // ------------------------------------------------------------------ el haz

    /** La columna de polvo: cada 4 bloques hasta 120 de alto, visible hasta 512 (force). */
    private void columna(Location l) {
        World w = l.getWorld();
        Particle.DustOptions polvo = new Particle.DustOptions(Color.fromRGB(COLOR_HAZ), 2.2f);
        for (int y = 2; y <= 120; y += 4) {
            w.spawnParticle(Particle.DUST, l.getX() + 0.5, l.getY() + y, l.getZ() + 0.5, 1, 0.05, 0.4, 0.05, 0, polvo, true);
        }
    }

    /** El BlockDisplay alto, si su chunk esta cargado y no esta ya. */
    private void asegurarHaz(Location l) {
        World w = l.getWorld();
        if (!w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return;
        if (haz != null && haz.isValid()) return;
        try {
            haz = w.spawn(l.clone().add(0.5, 1, 0.5), BlockDisplay.class, e -> {
                e.setBlock(Material.PURPLE_STAINED_GLASS.createBlockData());
                e.setBrightness(new Display.Brightness(15, 15));
                e.setShadowRadius(0f);
                e.setPersistent(false);
                e.setViewRange(8f);
                e.setTransformation(new Transformation(new Vector3f(-0.2f, 0f, -0.2f), new Quaternionf(),
                        new Vector3f(0.4f, 200f, 0.4f), new Quaternionf()));
            });
        } catch (Throwable t) {
            haz = null;
        }
    }

    private void quitarHaz() {
        if (haz != null && haz.isValid()) haz.remove();
        haz = null;
    }

    // ------------------------------------------------------------------ la guardia

    private void guardia(Player p, Location l) {
        datos().set(RUTA + ".activa.guardia", true);
        hc.marcarSucio();
        int mobs = 0;
        if (hc.plugin().mobs() != null) {
            mobs = hc.valor("boveda-caida", () -> hc.plugin().mobs().guardia(p, l.clone().add(0.5, 0, 0.5),
                    Math.max(0, cfg().getInt("guardia.mobs", 6)), Math.max(4, cfg().getInt("guardia.distancia", 10))), 0);
        }
        String jefe = "-";
        if (hc.plugin().mobs() != null && azar.nextDouble() < cfg().getDouble("guardia.minijefe", 0.25)) {
            List<String> tipos = hc.cfg().getStringList("minijefes.tipos");
            String tipo = Minijefes.elegir(Minijefes.bioma(l), Minijefes.porBioma(hc.plugin().getConfig()), tipos, azar::nextInt);
            if (tipo != null) {
                LivingEntity mj = hc.valor("boveda-caida", () -> hc.plugin().mobs().invocarMinijefe(p, tipo,
                        hc.cfg().getDouble("minijefes.distancia", 30), hc.cfg().getDouble("minijefes.vida", 15),
                        hc.cfg().getDouble("minijefes.dano", 4)), null);
                if (mj != null) jefe = tipo;
            }
        }
        if (mobs > 0 || !"-".equals(jefe)) {
            hc.cordura().destello(p, Component.text("Algo custodia la Bóveda Caída.", Paleta.AVISO), 3);
        }
        hc.plugin().bitacora().anotar("boveda-caida", "guardia", p.getName(), "mobs " + mobs, "minijefe " + jefe);
    }

    // ------------------------------------------------------------------ quitarla

    /** Quita la activa (abierta o caducada) y programa la siguiente. */
    private void retirarActiva(String motivo) {
        Location l = sitioActivo();
        quitarHaz();
        if (l != null) quitarBloque(l, true);
        hc.plugin().bitacora().anotar("boveda-caida", "se-va", motivo,
                datos().getString(RUTA + ".activa.abierta-por", "-"));
        datos().set(RUTA + ".activa", null);
        programar(System.currentTimeMillis());
        hc.guardarYa();
    }

    /** Quita el bloque y su boveda de EDM; con el chunk sin cargar, la deja en por-quitar. */
    private void quitarBloque(Location l, boolean efecto) {
        World w = l.getWorld();
        if (!w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) {
            List<String> pq = new ArrayList<>(datos().getStringList(RUTA + ".por-quitar"));
            String k = w.getName() + "," + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
            if (!pq.contains(k)) pq.add(k);
            datos().set(RUTA + ".por-quitar", pq);
            hc.marcarSucio();
            return;
        }
        Block b = l.getBlock();
        DungeonLootPlugin d = PuenteBovedas.modulo();
        Boveda bv = d == null ? null : d.bovedaEn(b);
        if (bv != null) d.retirar(bv, true);
        else if (b.getType() == Material.VAULT) b.setType(Material.AIR, false);
        if (efecto) {
            Location c = l.clone().add(0.5, 0.5, 0.5);
            w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, c, 8, 0.3, 0.3, 0.3, 0.02);
            w.spawnParticle(Particle.REVERSE_PORTAL, c, 20, 0.4, 0.4, 0.4, 0.05);
            Compat.sound(w, c, "block.vault.deactivate", 1.0f, 0.8f);
        }
    }

    /** Las que se quedaron en un chunk sin cargar: en cuanto se carga, fuera. */
    private void quitarPendientes() {
        List<String> pq = datos().getStringList(RUTA + ".por-quitar");
        if (pq.isEmpty()) return;
        List<String> quedan = new ArrayList<>();
        for (String k : pq) {
            String[] t = k.split(",");
            World w = t.length == 4 ? hc.plugin().getServer().getWorld(t[0]) : null;
            if (w == null) continue;
            int x, y, z;
            try {
                x = Integer.parseInt(t[1]);
                y = Integer.parseInt(t[2]);
                z = Integer.parseInt(t[3]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                quedan.add(k);
                continue;
            }
            quitarBloque(new Location(w, x, y, z), false);
            hc.plugin().bitacora().anotar("boveda-caida", "quitada-tarde", k);
        }
        if (quedan.size() != pq.size()) {
            datos().set(RUTA + ".por-quitar", quedan.isEmpty() ? null : quedan);
            hc.marcarSucio();
        }
    }

    private boolean esActiva(Block b) {
        Location l = sitioActivo();
        return l != null && l.getWorld().equals(b.getWorld()) && l.getBlockX() == b.getX() && l.getBlockY() == b.getY()
                && l.getBlockZ() == b.getZ();
    }

    // ------------------------------------------------------------------ eventos de EDM

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void alIntentar(BovedaAbrirEvent e) {
        if (!PuenteBovedas.CAJA_CAIDA.equals(e.caja().id())) return;
        Block b = e.bloque();
        if (!esActiva(b)) {
            // Una que quedo de antes (por-quitar) o una puesta a mano con esta caja: no se abre.
            e.setCancelled(true);
            e.motivo(ComandoCalamity.mensaje("Esta Bóveda Caída ya se apagó."));
            return;
        }
        if (datos().getBoolean(RUTA + ".activa.abierta", false)) {
            e.setCancelled(true);
            e.motivo(ComandoCalamity.mensaje("Otro jugador ya abrió esta Bóveda Caída."));
            return;
        }
        if (System.currentTimeMillis() >= datos().getLong(RUTA + ".activa.vence", Long.MAX_VALUE)) {
            e.setCancelled(true);
            e.motivo(ComandoCalamity.mensaje("Esta Bóveda Caída ya se apagó."));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void alAbrir(BovedaAbiertaEvent e) {
        String caja = e.caja().id();
        if (PuenteBovedas.CAJA_RUINAS.equals(caja)) {
            Ruinas r = hc.ruinas();
            if (r != null) hc.seguro("ruinas", () -> r.alAbrirBoveda(e));
            return;
        }
        if (!PuenteBovedas.CAJA_CAIDA.equals(caja) || !esActiva(e.bloque())) return;
        Player p = e.jugador();
        // Lo primero, y guardado: desde aqui nadie mas la abre, pase lo que pase con el botin.
        datos().set(RUTA + ".activa.abierta", true);
        datos().set(RUTA + ".activa.abierta-por", p.getName());
        datos().set(RUTA + ".activa.abierta-en", System.currentTimeMillis());
        hc.guardarYa();
        ConfigurationSection c = cfg();
        List<BotinCalamity.Tirada> t = BotinCalamity.tirar(BotinCalamity.filas(c, "botin", BOTIN_DE_SERIE), 0, 0, 0,
                1, azar::nextDouble);
        BotinCalamity.Entrega en = BotinCalamity.entregar(hc, p, t, "boveda", "boveda-caida");
        e.premio().addAll(en.objetos());
        Estadisticas st = hc.estadisticas();
        if (st != null) hc.seguro("estadisticas", () -> st.sumar(p.getUniqueId(), "bovedas-caidas", 1));
        ClanesCalamity cl = hc.clanes();
        if (cl != null) hc.seguro("clanes", () -> cl.sumar(p, "boveda-caida", 1));
        Component aviso = ComandoCalamity.mensaje(Component.text(p.getName(), Paleta.DETALLE)
                .append(Component.text(" abrió la Bóveda Caída.")));
        for (Player o : hc.plugin().getServer().getOnlinePlayers()) if (hc.esHardcore(o)) o.sendMessage(aviso);
        hc.plugin().bitacora().anotar("boveda-caida", "abre", p.getName(), BotinCalamity.texto(en.resumen()),
                "e " + en.esencias(), "r " + en.reliquias());
        Telemetria te = hc.telemetria();
        if (te != null) {
            Map<String, Object> campos = new LinkedHashMap<>();
            campos.put("objetos", en.resumen());
            campos.put("esencias", en.esencias());
            campos.put("reliquias", en.reliquias());
            campos.put("segundos", (System.currentTimeMillis() - datos().getLong(RUTA + ".activa.cae", 0)) / 1000);
            hc.seguro("telemetria", () -> te.suceso("boveda-caida-abre", p, campos));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alVaciar(BovedaVaciadaEvent e) {
        if (!PuenteBovedas.CAJA_CAIDA.equals(e.caja().id()) || !esActiva(e.bloque())) return;
        BukkitTask t = hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(),
                () -> hc.seguro("boveda-caida", () -> {
                    if (hayActiva() && datos().getBoolean(RUTA + ".activa.abierta", false)) retirarActiva("abierta");
                }), 30L);
        tareas.add(t);
    }

    // ------------------------------------------------------------------ comando

    private void comando(CommandSender quien, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "info";
        switch (sub) {
            case "drop" -> {
                if (hayActiva() || cayendo) {
                    quien.sendMessage(ComandoCalamity.mensaje("Ya hay una Bóveda Caída activa. Quítala con /calamidad vault clear."));
                    return;
                }
                boolean alAzar = args.length > 2 && args[2].equalsIgnoreCase("random");
                Player cerca = !alAzar && quien instanceof Player p && hc.esHardcore(p) ? p : null;
                quien.sendMessage(ComandoCalamity.mensaje(cerca != null ? "Buscando sitio cerca de ti..." : "Buscando sitio en el anillo del spawn..."));
                lanzar(cerca, "comando:" + quien.getName());
            }
            case "clear" -> {
                if (!hayActiva()) {
                    quien.sendMessage(ComandoCalamity.mensaje("No hay ninguna Bóveda Caída activa."));
                    return;
                }
                retirarActiva("comando:" + quien.getName());
                quien.sendMessage(ComandoCalamity.mensaje("Bóveda Caída quitada."));
            }
            default -> info(quien);
        }
    }

    private void info(CommandSender quien) {
        long ahora = System.currentTimeMillis();
        quien.sendMessage(ComandoCalamity.mensaje(Component.text("Bóveda Caída: ")
                .append(Paleta.detalle(activo() ? "activa" : "apagada en el config"))
                .append(Component.text(PuenteBovedas.modulo() == null ? " (sin dungeonloot de EDM)" : ""))));
        if (hayActiva()) {
            Location l = sitioActivo();
            String donde = l == null ? "?" : l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
            long vence = datos().getLong(RUTA + ".activa.vence", 0);
            quien.sendMessage(Component.text("  Caída en " + donde + " · " + datos().getString(RUTA + ".activa.bioma", "?"), Paleta.TENUE));
            quien.sendMessage(Component.text("  " + (datos().getBoolean(RUTA + ".activa.abierta", false)
                    ? "abierta por " + datos().getString(RUTA + ".activa.abierta-por", "?")
                    : "sin abrir, se va en " + minutos(vence - ahora)) + " · guardia "
                    + (datos().getBoolean(RUTA + ".activa.guardia", false) ? "ya salió" : "esperando"), Paleta.TENUE));
        } else {
            long prox = datos().getLong(RUTA + ".proxima", 0);
            quien.sendMessage(Component.text("  Ninguna activa. Próxima: " + (prox <= 0 ? "sin programar" : "en " + minutos(prox - ahora))
                    + " si hay " + cfg().getInt("minimo-jugadores", 3) + " fuera del spawn (ahora " + fueraDelSpawn().size() + ").", Paleta.TENUE));
        }
        int pq = datos().getStringList(RUTA + ".por-quitar").size();
        if (pq > 0) quien.sendMessage(Component.text("  Por quitar cuando se cargue su chunk: " + pq, Paleta.TENUE));
        Ruinas r = hc.ruinas();
        if (r != null) {
            int[] c = r.cuentas();
            quien.sendMessage(Component.text("  Ruinas con algo: " + c[0] + " · cofres " + c[1] + " · Bóvedas de Ruinas " + c[2], Paleta.TENUE));
        }
    }

    private static String minutos(long ms) {
        long m = Math.max(0, (ms + 59_999) / 60_000);
        return m >= 60 ? (m / 60) + " h " + (m % 60) + " min" : m + " min";
    }

    // ------------------------------------------------------------------ puro (autotest)

    /** A la centena mas cercana, y como poco 100. */
    static int redondear(double bloques) {
        return (int) Math.max(100, Math.round(bloques / 100.0) * 100);
    }

    /** El rumbo desde el spawn: norte es -Z y este +X, como en Minecraft. */
    static String rumbo(double dx, double dz) {
        double grados = Math.toDegrees(Math.atan2(dx, -dz));
        if (grados < 0) grados += 360;
        String[] r = {"norte", "noreste", "este", "sureste", "sur", "suroeste", "oeste", "noroeste"};
        return r[(int) Math.floor((grados + 22.5) / 45) % 8];
    }

    /** "el valle de bambú": el del config (nombres-biomas), el de serie, o el id con espacios. */
    static String nombreBioma(String bioma, ConfigurationSection nombres) {
        String k = bioma == null ? "" : Clima.clave(bioma);
        int barra = k.lastIndexOf('/');
        String corto = barra >= 0 ? k.substring(barra + 1) : k;
        if (nombres != null) {
            String s = nombres.getString(corto);
            if (s != null && !s.isBlank()) return s.trim();
        }
        String n = BIOMAS.get(corto);
        if (n != null) return n;
        return corto.isEmpty() ? "un lugar sin nombre" : "el bioma " + corto.replace('_', ' ');
    }

    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        h.igual("rumbo norte", "norte", rumbo(0, -100));
        h.igual("rumbo este", "este", rumbo(100, 0));
        h.igual("rumbo sur", "sur", rumbo(0, 100));
        h.igual("rumbo oeste", "oeste", rumbo(-100, 0));
        h.igual("rumbo noreste", "noreste", rumbo(100, -100));
        h.igual("rumbo suroeste", "suroeste", rumbo(-80, 90));
        h.igual("redondeo 849 -> 800", 800, redondear(849));
        h.igual("redondeo 850 -> 900", 900, redondear(850));
        h.igual("redondeo minimo 100", 100, redondear(20));
        h.igual("bioma con nombre", "el valle de bambú", nombreBioma("bracken:panacea/bamboo_valley", null));
        YamlConfiguration n = new YamlConfiguration();
        n.set("bamboo_valley", "el bambú");
        h.igual("bioma con nombre del config", "el bambú", nombreBioma("bracken:panacea/bamboo_valley", n));
        h.igual("bioma desconocido", "el bioma deep dark", nombreBioma("minecraft:deep_dark", null));
        h.igual("filas de serie del botin", 7, BOTIN_DE_SERIE.size());
        return h.lineas();
    }
}
