package net.ederus.calamity.hardcore;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.ederus.edm.comun.Fx;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * M20 · Alucinaciones: con la cordura baja, Calamity te ensena y te hace oir cosas que no estan.
 *
 * Cada 20 s por jugador se tira el dado con la probabilidad de su tramo (por-minuto / 3) y sale
 * un tipo al azar de los que permite ese tramo: pasos, una puerta, una figura que solo ve el,
 * un siseo, un golpe fantasma, un susurro o una carrera. Es sonido y vista, nunca mecanica.
 *
 * Las leyes de legibilidad (DIS sec. 0.2) mandan aqui mas que en ningun sitio, porque lo
 * falso tiene que poder distinguirse de lo real sin leer un manual:
 *   1. Sin cartel: una figura nunca lleva "Nv. X" (lo real si).
 *   2. Sin campana: ninguna clave de sonido de aqui es de campana (el autotest lo mira).
 *   3. Sin dano: ni vida ni cordura, salvo -2 si le pegas a una figura.
 *   6. Con una PARCA viva no hay figuras ni carreras: la amenaza grande no se confunde.
 *
 * Las figuras son entidades de verdad pero invisibles para todos menos su dueno
 * (setVisibleByDefault(false) + showEntity), sin IA, invulnerables, sin colision, con la marca
 * lethal_world:amenaza = "alucinacion" (Amenazas les quita objetivos y teleports ajenos), no
 * persistentes, y se borran en el quit, al morir, al salir del mundo y al parar.
 */
final class Alucinaciones implements Listener {

    static final String PASOS = "pasos", PUERTA = "puerta", FIGURA = "figura", SISEO = "siseo",
            GOLPE = "golpe", SUSURRO = "susurro", CARRERA = "carrera";
    static final List<String> TIPOS = List.of(PASOS, PUERTA, FIGURA, SISEO, GOLPE, SUSURRO, CARRERA);

    /** Todas las claves de sonido que suenan aqui. Ley 2: ninguna puede ser de campana. */
    static final String S_PASO = "block.gravel.step", S_PUERTA = "block.wooden_door.open",
            S_COFRE = "block.chest.open", S_SISEO = "entity.creeper.primed", S_GOLPE = "entity.player.hurt";
    static final List<String> SONIDOS = List.of(S_PASO, S_PUERTA, S_COFRE, S_SISEO, S_GOLPE);

    static final String MARCA = "alucinacion";
    /** Cada cuanto se mueve lo que esta vivo: figuras, carreras y la cola de pasos. */
    private static final long CADA_TICKS = 2;

    /** Un sonido que tiene que sonar mas tarde (los pasos), relativo a donde este entonces. */
    private record Sonido(UUID jugador, long tick, double detras, String clave, float volumen, float tono) {
    }

    private static final class Figura {
        final UUID dueno;
        final LivingEntity cuerpo;
        final long nace;
        final boolean carrera;

        Figura(UUID dueno, LivingEntity cuerpo, long nace, boolean carrera) {
            this.dueno = dueno;
            this.cuerpo = cuerpo;
            this.nace = nace;
            this.carrera = carrera;
        }
    }

    private final Hardcore hc;
    private final SecureRandom azar = new SecureRandom();
    /** Dueno -> su figura (una a la vez: dos a la vez ya no asustan, estorban). */
    private final Map<UUID, Figura> figuras = new HashMap<>();
    private final List<Sonido> cola = new ArrayList<>();
    private BukkitTask tarea;
    private long ticks;

    Alucinaciones(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("alucinaciones", this::autotest);
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = hc.cfg().getConfigurationSection("alucinaciones");
        return s == null ? new YamlConfiguration() : s;
    }

    private boolean activo() {
        return cfg().getBoolean("activo", false);
    }

    private boolean encendido(String tipo) {
        return cfg().getBoolean("tipos." + tipo, true);
    }

    private List<Double> porMinuto() {
        List<Double> l = cfg().getDoubleList("por-minuto");
        return l.isEmpty() ? List.of(3.0, 2.0, 1.0, 0.5, 0.0) : l;
    }

    private boolean parcaViva() {
        Parca parca = hc.parca();
        return parca != null && hc.valor("parca", parca::hayViva, false);
    }

    // ------------------------------------------------------------------ la tirada

    /** Cada 20 s por jugador que cuenta, desde Sentidos.latido. */
    void tirada(Player p, int tramo) {
        if (!activo() || !hc.esHardcore(p)) return;
        double prob = probabilidad(porMinuto(), tramo);
        if (prob <= 0 || azar.nextDouble() >= prob) return;
        boolean parca = parcaViva();
        List<Testigos.Muerto> muertos = candidatos(p);
        List<String> tipos = new ArrayList<>(permitidos(tramo, parca, !muertos.isEmpty(), this::encendido));
        // Una figura a la vez por jugador.
        if (figuras.containsKey(p.getUniqueId())) {
            tipos.remove(FIGURA);
            tipos.remove(CARRERA);
        }
        if (tipos.isEmpty()) return;
        String tipo = tipos.get(azar.nextInt(tipos.size()));
        if (!lanzar(p, tipo, muertos)) return;
        try {
            hc.plugin().bitacora().anotar("alucinacion", tipo, p.getName(), "tramo " + tramo);
        } catch (Throwable sinBitacora) {
            // Apagando.
        }
        Telemetria t = hc.telemetria();
        if (t != null) {
            Map<String, Object> campos = new LinkedHashMap<>();
            campos.put("tipo", tipo);
            campos.put("tramo", tramo);
            t.suceso("alucinacion", p, campos);
        }
    }

    /** False si no se pudo (sitio malo para una figura): la tirada se pierde sin avisar. */
    private boolean lanzar(Player p, String tipo, List<Testigos.Muerto> muertos) {
        UUID u = p.getUniqueId();
        switch (tipo) {
            case PASOS -> {
                // Tres pasos a 4 bloques detras, cada 8 ticks: se oyen acercarse y no hay nadie.
                for (int i = 0; i < 3; i++) cola.add(new Sonido(u, ticks + i * 8L, 4, S_PASO, 0.9f, 0.9f));
                asegurarTarea();
                return true;
            }
            case PUERTA -> {
                Vector dir = new Vector(1, 0, 0).rotateAroundY(azar.nextDouble() * Math.PI * 2);
                Location l = p.getLocation().add(dir.multiply(6 + azar.nextDouble() * 4));
                p.playSound(l, azar.nextBoolean() ? S_PUERTA : S_COFRE, 1f, 0.8f + azar.nextFloat() * 0.3f);
                return true;
            }
            case SISEO -> {
                p.playSound(detras(p, 2), S_SISEO, 1f, 0.9f);
                return true;
            }
            case GOLPE -> {
                // sendHurtAnimation y no playHurtAnimation: el sacudon es solo suyo, los demas
                // no le ven ponerse rojo por algo que no existe.
                p.sendHurtAnimation(azar.nextFloat() * 360f);
                p.playSound(p.getLocation(), S_GOLPE, 1f, 1f);
                return true;
            }
            case SUSURRO -> {
                if (muertos.isEmpty()) return false;
                Testigos.Muerto m = muertos.get(azar.nextInt(muertos.size()));
                p.sendMessage(susurro(m.nombre(), System.currentTimeMillis() - m.cuando()));
                return true;
            }
            case FIGURA -> {
                return figura(p, muertos, false);
            }
            case CARRERA -> {
                return figura(p, muertos, true);
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * El susurro (1.2). Antes era "susurro · <nombre>: vuelve" en gris, y Dosa lo leyo como un
     * mensaje de chat de un jugador cualquiera del servidor. Ahora dice que es una voz, de quien y
     * desde cuando esta muerto, y no tiene la forma "nombre: texto" de un chat: empieza por la
     * calavera, el nombre va en el gris azulado del Eco (no en el verde de los jugadores) y lo que
     * dice va entre comillas, en el turquesa de las almas. Sin cursiva, como todo Calamity: el
     * gris en cursiva es justo como se ve un /msg de verdad.
     */
    static Component susurro(String nombre, long haceMs) {
        return Component.text()
                .append(Component.text("☠ ", Paleta.ECO))
                .append(Component.text("Oyes la voz de ", Paleta.TENUE))
                .append(Component.text(nombre, Paleta.ECO))
                .append(Component.text(", que murió en Calamity " + hace(haceMs) + ": ", Paleta.TENUE))
                .append(Component.text("«vuelve…»", Paleta.ALMA))
                .build();
    }

    /** "hace un momento", "hace 12 min", "hace 3 h" (hacia abajo: 2 h 59 min son 2 h). */
    static String hace(long ms) {
        long min = Math.max(0, ms) / 60_000;
        if (min < 1) return "hace un momento";
        if (min < 60) return "hace " + min + " min";
        return "hace " + min / 60 + " h";
    }

    // ----------------------------------------------------------------- figuras

    /**
     * Una figura a 20-35 bloques, entre 40 y 70 grados de su mirada (se ve con el rabillo del
     * ojo). La carrera sale delante, a 14-18, y viene corriendo.
     */
    private boolean figura(Player p, List<Testigos.Muerto> muertos, boolean carrera) {
        Location ojo = p.getLocation();
        Vector mirada = ojo.getDirection().setY(0);
        if (mirada.lengthSquared() < 1e-6) mirada = new Vector(0, 0, 1);
        Vector off = carrera ? desvio(mirada, (azar.nextDouble() * 60) - 30, 14 + azar.nextDouble() * 4)
                : posicionFigura(mirada, azar);
        Location l = ojo.clone().add(off);
        World w = l.getWorld();
        if (w == null || !w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return false;
        l.setY(ojo.getY() + 4);
        l = Fx.ground(l, 12);
        // Un acantilado o una cueva: una figura flotando o enterrada no asusta, delata.
        if (Math.abs(l.getY() - ojo.getY()) > 10) return false;
        // 1.2: en la zona spawn no aparece nada, tampoco lo que solo ve el.
        if (hc.enSpawn(l)) return false;
        l.setDirection(ojo.toVector().subtract(l.toVector()));

        LivingEntity cuerpo = null;
        // La cara va con el cuerpo del Eco (eco.cuerpo-jugador): si el Eco no la lleva, una
        // figura con cara ensenaria algo que lo real no tiene.
        if (hc.cfg().getBoolean("eco.cuerpo-jugador", false)) {
            cuerpo = maniqui(l, muertos);
        }
        if (cuerpo == null) {
            cuerpo = w.spawn(l, WitherSkeleton.class, ws -> {
                preparar(ws);
                EntityEquipment eq = ws.getEquipment();
                if (eq != null) eq.clear();
            });
        }
        if (!cuerpo.isValid()) return false;
        p.showEntity(hc.plugin(), cuerpo);
        figuras.put(p.getUniqueId(), new Figura(p.getUniqueId(), cuerpo, ticks, carrera));
        asegurarTarea();
        return true;
    }

    /** P1: con cuerpo-jugador, un maniqui con la cara de un muerto de las ultimas 24 h. */
    private LivingEntity maniqui(Location l, List<Testigos.Muerto> muertos) {
        List<Testigos.Muerto> conCara = new ArrayList<>();
        for (Testigos.Muerto m : muertos) if (m.skinValor() != null) conCara.add(m);
        if (conCara.isEmpty()) return null;
        Testigos.Muerto m = conCara.get(azar.nextInt(conCara.size()));
        try {
            ResolvableProfile perfil = ResolvableProfile.resolvableProfile()
                    .uuid(m.id()).name(m.nombre().length() > 16 ? m.nombre().substring(0, 16) : m.nombre())
                    .addProperty(new ProfileProperty("textures", m.skinValor(), m.skinFirma()))
                    .build();
            return l.getWorld().spawn(l, Mannequin.class, mq -> {
                preparar(mq);
                mq.setImmovable(true);
                mq.setDescription(Component.empty());
                mq.setProfile(perfil);
            });
        } catch (Throwable t) {
            // Sin maniquies (API cambiada): sale el esqueleto de siempre.
            return null;
        }
    }

    /** Lo comun a toda figura. Va en el consumer del spawn: antes de que nadie la vea. */
    private void preparar(LivingEntity e) {
        e.setVisibleByDefault(false);
        e.setPersistent(false);
        e.setInvulnerable(true);
        e.setSilent(true);
        e.setCollidable(false);
        e.setCanPickupItems(false);
        e.setAI(false);
        e.getPersistentDataContainer().set(Marcas.AMENAZA, PersistentDataType.STRING, MARCA);
    }

    private void asegurarTarea() {
        if (tarea != null) return;
        tarea = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(),
                () -> hc.seguro("alucinaciones", this::cadaDosTicks), CADA_TICKS, CADA_TICKS);
    }

    private void cadaDosTicks() {
        ticks += CADA_TICKS;
        for (Iterator<Sonido> it = cola.iterator(); it.hasNext(); ) {
            Sonido s = it.next();
            if (s.tick() > ticks) continue;
            it.remove();
            Player p = Bukkit.getPlayer(s.jugador());
            if (p != null && hc.esHardcore(p)) p.playSound(detras(p, s.detras()), s.clave(), s.volumen(), s.tono());
        }
        boolean parca = parcaViva();
        for (Iterator<Figura> it = figuras.values().iterator(); it.hasNext(); ) {
            Figura f = it.next();
            Player p = Bukkit.getPlayer(f.dueno);
            if (p == null || !f.cuerpo.isValid() || p.getWorld() != f.cuerpo.getWorld()) {
                it.remove();
                deshacer(f, null);
                continue;
            }
            long vida = ticks - f.nace;
            double d = p.getLocation().distance(f.cuerpo.getLocation());
            boolean fin;
            if (parca) fin = true;                               // ley 6
            else if (f.carrera) fin = d <= 2 || vida >= 160;    // se deshace a 2 bloques (o a los 8 s)
            else fin = vida >= 400 || d < 12 || deFrente(p.getEyeLocation().getDirection(),
                    f.cuerpo.getLocation().add(0, 1.2, 0).toVector().subtract(p.getEyeLocation().toVector()));
            if (fin) {
                it.remove();
                deshacer(f, p);
            } else if (f.carrera) {
                correr(f, p, d);
            }
        }
        if (cola.isEmpty() && figuras.isEmpty() && tarea != null) {
            tarea.cancel();
            tarea = null;
        }
    }

    /** Un bloque por cada 2 ticks hacia el (10 bloques/s), pegada al suelo y mirandole. */
    private void correr(Figura f, Player p, double d) {
        Location desde = f.cuerpo.getLocation();
        Vector paso = p.getLocation().toVector().subtract(desde.toVector()).setY(0);
        if (paso.lengthSquared() < 1e-6) return;
        paso.normalize().multiply(Math.min(1.0, Math.max(0, d - 1.5)));
        Location a = desde.clone().add(paso);
        a.setY(desde.getY() + 2);
        a = Fx.ground(a, 6);
        a.setDirection(p.getLocation().toVector().subtract(a.toVector()));
        // Amenazas cancela cualquier teleport de una entidad marcada que no pase por el.
        Amenazas am = hc.amenazas();
        if (am != null) am.teleportar(f.cuerpo, a);
        else f.cuerpo.teleport(a);
    }

    /** Se deshace en humo (solo lo ve su dueno) y desaparece. */
    private static void deshacer(Figura f, Player p) {
        if (p != null && f.cuerpo.isValid()) {
            p.spawnParticle(Particle.SMOKE, f.cuerpo.getLocation().add(0, 1, 0), 30, 0.3, 0.8, 0.3, 0.01);
        }
        if (f.cuerpo.isValid()) f.cuerpo.remove();
    }

    // ------------------------------------------------------------ pegarle a nada

    /** Pegarle a una figura: se deshace, P-L01 y -coste-pegar de cordura. Ley 3. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPegar(PrePlayerAttackEntityEvent e) {
        Entity x = e.getAttacked();
        if (!MARCA.equals(Marcas.amenaza(x))) return;
        e.setCancelled(true);
        pegada(e.getPlayer(), x);
    }

    /** Una flecha la atraviesa: la de su dueno cuenta como golpe; la de otro, ni la ve. */
    @EventHandler(ignoreCancelled = true)
    public void onFlecha(ProjectileHitEvent e) {
        Entity x = e.getHitEntity();
        if (x == null || !MARCA.equals(Marcas.amenaza(x))) return;
        e.setCancelled(true);
        if (e.getEntity().getShooter() instanceof Player p) pegada(p, x);
    }

    private void pegada(Player p, Entity x) {
        Figura f = figuras.get(p.getUniqueId());
        if (f == null || f.cuerpo != x) {
            // Una figura sin dueno vivo (reinicio a medias): fuera sin mas.
            boolean deAlguien = false;
            for (Figura o : figuras.values()) deAlguien |= o.cuerpo == x;
            if (!deAlguien) x.remove();
            return;
        }
        figuras.remove(p.getUniqueId());
        deshacer(f, p);
        hc.cordura().destello(p, Component.text("No había nada.", Paleta.TEXTO), 2);
        double coste = cfg().getDouble("coste-pegar", 2);
        if (coste > 0 && hc.esHardcore(p)) hc.cordura().sumar(p, -coste);
        try {
            hc.plugin().bitacora().anotar("alucinacion", "golpe", p.getName(), "cordura -" + coste);
        } catch (Throwable sinBitacora) {
            // Apagando.
        }
        Telemetria t = hc.telemetria();
        if (t != null) {
            Map<String, Object> campos = new LinkedHashMap<>();
            campos.put("carrera", f.carrera);
            campos.put("cordura", -coste);
            t.suceso("alucinacion-golpe", p, campos);
        }
    }

    // -------------------------------------------------------------- limpieza

    /** Muere, sale del mundo o se desconecta: fuera su figura y sus pasos pendientes. */
    void olvidar(Player p) {
        UUID u = p.getUniqueId();
        Figura f = figuras.remove(u);
        if (f != null) deshacer(f, null);
        cola.removeIf(s -> s.jugador().equals(u));
    }

    void parar() {
        if (tarea != null) tarea.cancel();
        tarea = null;
        for (Figura f : figuras.values()) deshacer(f, null);
        figuras.clear();
        cola.clear();
    }

    // ----------------------------------------------------------------- calculos

    /** Los muertos de las ultimas 24 h que no son el ni estan conectados (DIS M20). */
    private List<Testigos.Muerto> candidatos(Player p) {
        Testigos t = hc.testigos();
        if (t == null) return List.of();
        Set<UUID> conectados = new HashSet<>();
        for (Player o : Bukkit.getOnlinePlayers()) conectados.add(o.getUniqueId());
        return candidatos(t.recientes(), conectados, p.getUniqueId(), System.currentTimeMillis());
    }

    static List<Testigos.Muerto> candidatos(List<Testigos.Muerto> recientes, Set<UUID> conectados, UUID yo, long ahora) {
        List<Testigos.Muerto> out = new ArrayList<>();
        for (Testigos.Muerto m : recientes) {
            if (m.id().equals(yo) || conectados.contains(m.id()) || ahora - m.cuando() > Testigos.DIA_MS) continue;
            out.add(m);
        }
        return out;
    }

    /** Desde que tramo sale cada tipo: sale en ese tramo y en todos los de debajo. */
    static int desde(String tipo) {
        return switch (tipo) {
            case PASOS, PUERTA -> 3;
            case FIGURA -> 2;
            case SISEO, GOLPE, SUSURRO -> 1;
            default -> 0;
        };
    }

    /** Los tipos que pueden salir: por tramo, sin figuras ni carreras con PARCA, sin susurro sin muertos. */
    static List<String> permitidos(int tramo, boolean parcaViva, boolean hayMuerto, Predicate<String> encendido) {
        List<String> out = new ArrayList<>();
        for (String t : TIPOS) {
            if (tramo > desde(t)) continue;
            if (parcaViva && (t.equals(FIGURA) || t.equals(CARRERA))) continue;
            if (t.equals(SUSURRO) && !hayMuerto) continue;
            if (encendido != null && !encendido.test(t)) continue;
            out.add(t);
        }
        return out;
    }

    /** La config va por minuto y se tira cada 20 s: un tercio, entre 0 y 1. */
    static double probabilidad(List<Double> porMinuto, int tramo) {
        if (porMinuto == null || tramo < 0 || tramo >= porMinuto.size()) return 0;
        return Math.max(0, Math.min(1, porMinuto.get(tramo) / 3.0));
    }

    /** A 20-35 bloques y entre 40 y 70 grados de la mirada, a un lado o al otro. */
    static Vector posicionFigura(Vector mirada, SecureRandom r) {
        double grados = 40 + r.nextDouble() * 30;
        return desvio(mirada, r.nextBoolean() ? grados : -grados, 20 + r.nextDouble() * 15);
    }

    static Vector desvio(Vector mirada, double grados, double distancia) {
        Vector plana = mirada.clone().setY(0);
        if (plana.lengthSquared() < 1e-9) plana = new Vector(0, 0, 1);
        return plana.normalize().rotateAroundY(Math.toRadians(grados)).multiply(distancia);
    }

    /** Si la esta mirando de frente (producto escalar > 0,97): entonces se deshace. */
    static boolean deFrente(Vector mirada, Vector hacia) {
        if (mirada.lengthSquared() < 1e-9 || hacia.lengthSquared() < 1e-9) return false;
        return mirada.clone().normalize().dot(hacia.clone().normalize()) > 0.97;
    }

    private static Location detras(Player p, double bloques) {
        Location l = p.getLocation();
        Vector atras = l.getDirection().setY(0);
        if (atras.lengthSquared() < 1e-6) atras = new Vector(0, 0, 1);
        return l.clone().add(atras.normalize().multiply(-bloques));
    }

    // ------------------------------------------------------------------ autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        Predicate<String> todos = t -> true;
        h.igual("tramo 4: nada", List.of(), permitidos(4, false, true, todos));
        h.igual("tramo 3: pasos y puerta", List.of(PASOS, PUERTA), permitidos(3, false, true, todos));
        h.igual("tramo 2: y figura", List.of(PASOS, PUERTA, FIGURA), permitidos(2, false, true, todos));
        h.igual("tramo 1: y siseo, golpe y susurro", List.of(PASOS, PUERTA, FIGURA, SISEO, GOLPE, SUSURRO),
                permitidos(1, false, true, todos));
        h.igual("tramo 0: todo, con la carrera", TIPOS, permitidos(0, false, true, todos));
        List<String> conParca = permitidos(0, true, true, todos);
        h.ok("con PARCA viva no hay figuras", !conParca.contains(FIGURA));
        h.ok("con PARCA viva no hay carreras", !conParca.contains(CARRERA));
        h.igual("con PARCA viva queda el resto", 5, conParca.size());
        h.ok("con PARCA viva en tramo 2 no hay figura", !permitidos(2, true, true, todos).contains(FIGURA));
        h.ok("sin muertos recientes no hay susurro", !permitidos(0, false, false, todos).contains(SUSURRO));
        h.ok("un tipo apagado en la config no sale", !permitidos(0, false, true, t -> !t.equals(GOLPE)).contains(GOLPE));

        List<Double> def = List.of(3.0, 2.0, 1.0, 0.5, 0.0);
        h.cerca("tramo 0: 3/min = 1 cada tirada", 1.0, probabilidad(def, 0), 1e-9);
        h.cerca("tramo 1: 2/min", 2 / 3.0, probabilidad(def, 1), 1e-9);
        h.cerca("tramo 2: 1/min", 1 / 3.0, probabilidad(def, 2), 1e-9);
        h.cerca("tramo 3: 0,5/min", 0.5 / 3.0, probabilidad(def, 3), 1e-9);
        h.cerca("tramo 4: nunca", 0, probabilidad(def, 4), 1e-9);
        h.cerca("tramo fuera de la lista", 0, probabilidad(def, 7), 1e-9);
        h.cerca("mas de 3/min se queda en 1", 1.0, probabilidad(List.of(9.0), 0), 1e-9);

        // Ley 2: ninguna clave de sonido de las alucinaciones es de campana.
        for (String s : SONIDOS) h.ok("sin campana: " + s, !s.contains("bell"));
        h.igual("hay un sonido por cada cosa que suena", 5, SONIDOS.size());

        SecureRandom r = new SecureRandom();
        Vector mirada = new Vector(0.3, -0.4, 0.8);
        boolean angulos = true, distancias = true;
        for (int i = 0; i < 300; i++) {
            Vector v = posicionFigura(mirada, r);
            double a = Math.toDegrees(mirada.clone().setY(0).angle(v));
            double d = v.length();
            angulos &= a >= 40 - 1e-6 && a <= 70 + 1e-6;
            distancias &= d >= 20 - 1e-6 && d <= 35 + 1e-6 && Math.abs(v.getY()) < 1e-9;
        }
        h.ok("figura entre 40 y 70 grados de la mirada (300 tiradas)", angulos);
        h.ok("figura a 20-35 bloques y a su altura (300 tiradas)", distancias);
        h.ok("mirarla de frente la deshace", deFrente(new Vector(0, 0, 1), new Vector(0.1, 0, 1)));
        h.ok("de reojo no", !deFrente(new Vector(0, 0, 1), new Vector(1, 0, 1)));

        long ahora = 50_000_000L;
        UUID yo = Autotest.sintetico(1), ana = Autotest.sintetico(2), beto = Autotest.sintetico(3),
                viejo = Autotest.sintetico(4);
        List<Testigos.Muerto> rec = List.of(
                new Testigos.Muerto(yo, "Yo", ahora - 1000, null, null),
                new Testigos.Muerto(ana, "Ana", ahora - 1000, null, null),
                new Testigos.Muerto(beto, "Beto", ahora - 1000, null, null),
                new Testigos.Muerto(viejo, "Viejo", ahora - Testigos.DIA_MS - 1, null, null));
        List<Testigos.Muerto> c = candidatos(rec, Set.of(beto), yo, ahora);
        h.igual("susurro: ni el mismo, ni conectados, ni de hace mas de 24 h", 1, c.size());
        h.igual("susurro de Ana", "Ana", c.isEmpty() ? null : c.get(0).nombre());
        h.igual("sin nadie, sin susurro", 0, candidatos(List.of(), Set.of(), yo, ahora).size());

        // 1.2: el susurro se entiende (la voz de un muerto de Calamity, y desde cuando) y no parece un chat.
        String s = Hardcore.plano(susurro("AuthenticShadow", 2 * 3_600_000L + 59 * 60_000L));
        h.igual("susurro: el texto", "☠ Oyes la voz de AuthenticShadow, que murió en Calamity hace 2 h: «vuelve…»", s);
        h.ok("susurro: ni empieza por el nombre ni lleva \"nombre:\" (no parece un chat)",
                !s.startsWith("AuthenticShadow") && !s.contains("AuthenticShadow:"));
        h.igual("hace: menos de un minuto", "hace un momento", hace(30_000));
        h.igual("hace: minutos, hacia abajo", "hace 12 min", hace(12 * 60_000L + 59_000));
        h.igual("hace: horas, hacia abajo", "hace 23 h", hace(23 * 3_600_000L + 59 * 60_000L));
        return h.lineas();
    }
}
