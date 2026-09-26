package net.ederus.lethalworld.hardcore;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.ederus.edm.comun.Bitacora;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * sec. 2 · Los Ecos: nacen de la foto del muerto, duermen y despiertan segun quien este cerca,
 * se cazan y pagan (o no) por la Aduana.
 *
 * Los datos mandan (sec. 2.7): ecos.<id> en hardcore-datos.yml es la verdad y se guarda en el
 * acto (guardarYa) al nacer y al morir, porque llevan objetos reales. La entidad es una vista
 * no persistente que se crea y se quita; el mapa porEntidad dice cual es la buena y todo lo
 * demas con la marca lethal_world:eco se purga (X10). Al morir, primero se borra el registro
 * y se guarda, y DESPUES se suelta el botin: una caida entre medias lo pierde, nunca lo
 * duplica (X11).
 *
 * Un solo Listener para toda la familia; lo barato primero (la marca en el PDC).
 */
final class Ecos implements Listener {

    private static final TextColor GRIS = Eco.GRIS;
    private static final TextColor BLANCO = NamedTextColor.WHITE;
    private static final String[] RUMBOS = {"norte", "noreste", "este", "sureste", "sur", "suroeste", "oeste", "noroeste"};

    private final Hardcore hc;
    /* SecureRandom: el trofeo es botin, y la casa no deja nada de botin a Random. */
    private final SecureRandom azar = new SecureRandom();
    /** idEco -> Eco. LinkedHashMap: el orden de nacimiento sirve para el tope global. */
    private final Map<String, Eco> ecos = new LinkedHashMap<>();
    /** UUID de la entidad (cuerpo o maniqui) -> idEco. Lo que no este aqui con la marca, se purga. */
    private final Map<UUID, String> porEntidad = new HashMap<>();
    /**
     * Mensajes para quien no podia leerlos (muerto en la pantalla de muerte, desconectado).
     * Solo en memoria: tras un reinicio se pierden, y lo importante ya esta en la Bitacora.
     */
    private final Map<UUID, List<Component>> pendientes = new HashMap<>();
    /** Ultimo aviso "Tu Eco · d m" de cada uno (cada 20 s). Se limpia en el quit. */
    private final Map<UUID, Long> ultimoAviso = new HashMap<>();
    /** P1 · voces: ultimas frases de chat en Calamity. Lo escribe el hilo del chat. */
    private final Map<UUID, Deque<Object[]>> chat = new ConcurrentHashMap<>();
    /** Botin en el suelo con dueno temporal (preferencia-segundos) y cuando se libera. */
    private final List<Object[]> soltados = new ArrayList<>();
    /**
     * Resumen para los placeholders (uuid -> {expira del mas nuevo, reliquias}). Se rehace en
     * el reloj: PlaceholderAPI puede preguntar desde otro hilo y no puede tocar el mapa vivo.
     */
    private volatile Map<UUID, long[]> resumen = Map.of();
    private int segundos;

    Ecos(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("eco", this::autotest);
        Subcomandos.lw().registrar("eco",
                "eco crear|lista|borrar|tp|prueba|despertar|matar: los Ecos (sec. 2)",
                "ederus.mundos", this::comando, this::tab);
        Subcomandos.calamity().registrar("eco", "tus Ecos: donde estan, nivel y lo que les queda",
                "lethalworld.calamity", this::comandoJugador, null);
        PlaceholdersLethal.registrar("eco", (jugador, resto) -> placeholder(jugador, false));
        PlaceholdersLethal.registrar("eco_reliquias", (jugador, resto) -> placeholder(jugador, true));
        cargar();
        purgarCargadas();
    }

    Hardcore hc() {
        return hc;
    }

    /** La seccion hardcore.eco (vacia si no esta: todo con su defecto en codigo). */
    static ConfigurationSection seccion(Hardcore hc) {
        ConfigurationSection s = hc.cfg().getConfigurationSection("eco");
        return s == null ? new YamlConfiguration() : s;
    }

    ConfigurationSection cfg() {
        return seccion(hc);
    }

    private boolean activo() {
        return cfg().getBoolean("activo", true);
    }

    // --------------------------------------------------------------- nacimiento

    /** Lo llama Hardcore.onMuerte con la foto, ya con el inventario borrado. */
    void programar(FotoMuerte foto) {
        if (foto == null || !activo()) return;
        ConfigurationSection c = cfg();
        if (!foto.mereceEco(c.getInt("minimo-piezas", 1))) {
            avisarLuego(foto.dueno, ComandoCalamity.mensaje("No queda nada de ti que merezca volver."));
            return;
        }
        if (foto.anclaje == null || foto.anclaje.getWorld() == null) return;
        Eco e = nacer(foto, false);
        int horas = (int) Math.round(c.getDouble("horas", 12));
        avisarLuego(e.dueno, ComandoCalamity.mensaje(Component.text("Tu Eco se ha levantado donde caíste. Lleva tu armadura, tu arma y ")
                .append(Component.text(e.nReliquias(), BLANCO))
                .append(Component.text(" reliquias. Dura " + horas + " h."))));
        if (e.porParca) avisarLuego(e.dueno, ComandoCalamity.mensaje("Lo que la Parca siega vuelve peor."));
        Player asesino = e.asesino == null ? null : Bukkit.getPlayer(e.asesino);
        if (asesino != null) asesino.sendMessage(ComandoCalamity.mensaje("Lo que llevaba se lo ha quedado su Eco."));
    }

    /** Registra un Eco nuevo: sustituye al anterior del dueno, guarda en el acto y respeta el tope. */
    private Eco nacer(FotoMuerte foto, boolean prueba) {
        ConfigurationSection c = cfg();
        long ahora = System.currentTimeMillis();
        Eco e = Eco.deFoto(nuevoId(), foto, c, ahora);
        e.prueba = prueba;
        e.alzarEn = prueba ? ahora : ahora + c.getInt("segundos-para-alzarse", 8) * 1000L;
        sustituirViejos(e.dueno);
        ecos.put(e.id, e);
        guardar(e);
        topeGlobal(e);
        hc.guardarYa();
        Location l = e.anclaje();
        anotar("nace", e.nombre, "id " + e.id, "N_E " + e.nivel, "vida " + Bitacora.num(e.vidaMax),
                "dano " + Bitacora.dec(e.dano), "piezas " + e.piezasTexto(), "reliquias " + e.idsReliquias(),
                "esencias " + e.nEsencias, "asesino " + (e.asesinoNombre == null ? "-" : e.asesinoNombre),
                l == null ? "-" : l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
        Map<String, Object> t = campos(e, "nace");
        t.put("vida", e.vidaMax);
        t.put("dano", e.dano);
        t.put("por_parca", e.porParca);
        telemetria(Bukkit.getOfflinePlayer(e.dueno), t);
        return e;
    }

    private String nuevoId() {
        String id;
        do {
            id = String.format("%08x", azar.nextInt());
        } while (ecos.containsKey(id) || hc.datos().isSet("ecos." + id));
        return id;
    }

    /**
     * Un Eco por jugador sin borrar botin (P1 sec. 3.6): el viejo sin Reliquias ni Esencias se
     * deshace; el que lleva algo pasa a errante (deja de buscarle, conserva botin y caducidad).
     * Con demasiados errantes, el mas viejo se desmorona en desmoronar-minutos con aviso.
     * Nunca desaparece un botin por algo que haga su dueno (X12).
     */
    private void sustituirViejos(UUID dueno) {
        ConfigurationSection c = cfg();
        for (Eco v : new ArrayList<>(ecos.values())) {
            if (!v.dueno.equals(dueno) || v.errante) continue;
            if (!v.tieneBotin()) {
                deshacer(v, "sustituido", null);
                if (!v.prueba) avisarLuego(dueno, ComandoCalamity.mensaje("Tu Eco anterior se deshace."));
            } else {
                v.errante = true;
                v.elegido = null;
                guardar(v);
                anotar("errante", v.id);
                if (!v.prueba) avisarLuego(dueno, ComandoCalamity.mensaje("Tu Eco anterior vaga sin ti. Lo que llevaba, sigue ahí."));
            }
        }
        List<Eco> errantes = new ArrayList<>();
        for (Eco v : ecos.values()) if (v.dueno.equals(dueno) && v.errante && v.desmorona == 0) errantes.add(v);
        errantes.sort(Comparator.comparingLong(v -> v.nacio));
        int max = Math.max(0, c.getInt("errantes-por-jugador", 2));
        // El nuevo aun no esta en el mapa: cuenta como el que "hace falta".
        for (int i = 0; errantes.size() - i > max; i++) desmoronarEn(errantes.get(i));
    }

    /** Tope global: fuera el mas viejo sin botin; si todos llevan algo, el errante mas viejo con aviso. */
    private void topeGlobal(Eco nuevo) {
        int max = Math.max(1, cfg().getInt("maximo-global", 40));
        int vivos = 0;
        for (Eco v : ecos.values()) if (v.desmorona == 0) vivos++;
        while (vivos > max) {
            Eco victima = null;
            for (Eco v : ecos.values()) {
                if (v == nuevo || v.tieneBotin() || v.desmorona > 0) continue;
                if (victima == null || v.nacio < victima.nacio) victima = v;
            }
            if (victima != null) {
                deshacer(victima, "sustituido", "tope");
            } else {
                for (Eco v : ecos.values()) {
                    if (v == nuevo || !v.errante || v.desmorona > 0) continue;
                    if (victima == null || v.nacio < victima.nacio) victima = v;
                }
                if (victima == null) return;
                desmoronarEn(victima);
            }
            vivos--;
        }
    }

    private void desmoronarEn(Eco v) {
        v.desmorona = System.currentTimeMillis() + cfg().getInt("desmoronar-minutos", 30) * 60_000L;
        guardar(v);
        hc.marcarSucio();
        anotar("desmorona", v.id, "en " + cfg().getInt("desmoronar-minutos", 30) + " min");
        if (!v.prueba) {
            aTodoCalamity(ComandoCalamity.mensaje("El Eco errante de " + v.nombre + " se desmorona. Quedan "
                    + cfg().getInt("desmoronar-minutos", 30) + " minutos."));
        }
    }

    // --------------------------------------------------------------------- reloj

    /** Cada segundo: caducidad, despertar, IA de los despiertos, senal de los latentes. */
    void tick() {
        segundos++;
        long ahora = System.currentTimeMillis();
        ConfigurationSection c = cfg();
        int vistos = 0;
        for (Eco e : new ArrayList<>(ecos.values())) {
            if (++vistos > Math.max(40, c.getInt("maximo-global", 40) + 10)) break;
            if (ahora >= e.expira) {
                caducar(e);
                continue;
            }
            if (e.desmorona > 0 && ahora >= e.desmorona) {
                deshacer(e, "desmorona", "hecho");
                continue;
            }
            if (e.despierto()) {
                if (!e.segundo(this, ahora)) dormir(e, "sola");
                else if (segundos % 30 == 0) apuntarVida(e);
                continue;
            }
            if (e.cuerpo != null) {
                // Se fue sin avisar (chunk descargado, /lw hardcore amenazas limpiar): duerme con la ultima vida vista.
                dormir(e, "perdido");
            }
            if (!activo() || ahora < e.alzarEn) continue;
            Location l = e.anclaje();
            if (l == null) continue;
            List<Player> cuentan = cuentanEn(l.getWorld());
            double rDesp = c.getDouble("radio-despertar", 32), rSenal = c.getDouble("senal-radio", 48);
            Player cerca = null;
            boolean senal = false;
            for (Player p : cuentan) {
                double d2 = p.getLocation().distanceSquared(l);
                if (d2 <= rDesp * rDesp) cerca = p;
                if (d2 <= rSenal * rSenal) senal = true;
            }
            if (cerca != null) despertar(e, cerca.getName());
            else if (senal && segundos % 5 == 0) Compat.spawn(l.getWorld(), Compat.SOUL, l.clone().add(0, 0.8, 0), 10, 0.4, 0.6, 0.4, 0.02);
        }
        liberarSoltados(ahora);
        rehacerResumen();
    }

    /** Crea el cuerpo; la primera vez, con el alzamiento (titulo, chat, testigos). */
    private boolean despertar(Eco e, String quien) {
        boolean alzamiento = !e.alzado;
        if (!e.despertar(this, alzamiento)) return false;
        vista(e.cuerpo, e.id);
        e.ponerNombre(hc);
        guardar(e);
        hc.marcarSucio();
        anotar("despierta", e.id, quien);
        if (alzamiento) {
            Location l = e.cuerpo.getLocation();
            double r = cfg().getDouble("radio-despertar", 32);
            for (Player p : l.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(l) > r * r) continue;
                p.showTitle(Title.title(Component.text("ECO", GRIS), Component.text("de " + e.nombre, NamedTextColor.GRAY)));
                p.sendMessage(Component.text("Algo se levanta donde cayó " + e.nombre + ".", NamedTextColor.GRAY));
            }
            Testigos t = hc.testigos();
            if (t != null) hc.seguro("testigos", () -> t.alAlzarEco(l.clone()));
        }
        return true;
    }

    private void dormir(Eco e, String porque) {
        e.quitarVista(this, !"chunk".equals(porque));
        e.durmio = System.currentTimeMillis();
        e.elegido = null;
        e.volviendo = false;
        apuntarVida(e);
        anotar("duerme", e.id, "vida " + Bitacora.dec(e.fraccion), porque);
    }

    private void apuntarVida(Eco e) {
        String b = "ecos." + e.id;
        if (!hc.datos().isSet(b)) return;
        if (e.despierto()) e.fraccion = Amenazas.fraccion(e.cuerpo);
        hc.datos().set(b + ".fraccion", e.fraccion);
        hc.datos().set(b + ".durmio", e.durmio);
        hc.datos().set(b + ".ultimo-dano", e.ultimoDano);
        hc.marcarSucio();
    }

    /** Caduca a las horas: sus Reliquias se pierden (Bitacora eco | caduca). */
    private void caducar(Eco e) {
        if (e.despierto()) {
            Location l = e.cuerpo.getLocation();
            Fx.shockwave(l.getWorld(), l, 3, Compat.ASH, 24);
            for (Player p : l.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(l) <= 32 * 32) {
                    p.sendMessage(Component.text("El Eco de " + e.nombre + " se deshace.", NamedTextColor.GRAY));
                }
            }
        }
        quitar(e);
        anotar("caduca", e.id, "reliquias perdidas " + e.idsReliquias());
        telemetria(Bukkit.getOfflinePlayer(e.dueno), campos(e, "caduca"));
        if (!e.prueba) avisar(e.dueno, ComandoCalamity.mensaje("Tu Eco se ha deshecho. Lo que llevaba, se lo queda Calamity."));
    }

    /** Fuera sin botin (sustituido, desmoronado, borrado por un admin). */
    private void deshacer(Eco e, String suceso, String detalle) {
        if (e.despierto()) {
            Location l = e.cuerpo.getLocation();
            Fx.shockwave(l.getWorld(), l, 2.5, Compat.ASH, 18);
        }
        quitar(e);
        if (detalle == null) anotar(suceso, e.id, "reliquias perdidas " + e.idsReliquias());
        else anotar(suceso, e.id, detalle, "reliquias perdidas " + e.idsReliquias());
    }

    /** Lo borra del mapa y de los datos, y guarda en el acto. */
    private void quitar(Eco e) {
        e.quitarVista(this);
        ecos.remove(e.id);
        hc.datos().set("ecos." + e.id, null);
        hc.guardarYa();
    }

    private void guardar(Eco e) {
        hc.datos().set("ecos." + e.id, null);
        e.guardar(hc.datos().createSection("ecos." + e.id));
    }

    private void cargar() {
        ConfigurationSection s = hc.datos().getConfigurationSection("ecos");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            try {
                Eco e = Eco.cargar(id, s.getConfigurationSection(id));
                e.alzarEn = 0;
                ecos.put(id, e);
            } catch (Throwable t) {
                // No se borra: puede llevar Reliquias de alguien. Se avisa y se deja para mirarlo a mano.
                hc.plugin().getLogger().log(Level.WARNING, "[Calamity] Eco " + id + " con el registro roto; se deja como esta", t);
            }
        }
    }

    /** Copia de los Ecos registrados, en orden de nacimiento (para el Tablero, WP9). */
    List<Eco> vivos() {
        return new ArrayList<>(ecos.values());
    }

    // ---------------------------------------------------------------- vistas

    void vista(Entity entidad, String id) {
        if (entidad != null) porEntidad.put(entidad.getUniqueId(), id);
    }

    void olvidarVista(Entity entidad) {
        if (entidad != null) porEntidad.remove(entidad.getUniqueId());
    }

    /** Al arrancar: nada con la marca del Eco puede estar vivo sin que lo sepamos. */
    private void purgarCargadas() {
        for (World w : hc.plugin().getServer().getWorlds()) {
            if (!hc.esHardcore(w)) continue;
            for (Entity en : w.getEntities()) purgar(en);
        }
    }

    private boolean purgar(Entity en) {
        String id = en.getPersistentDataContainer().get(Marcas.ECO, PersistentDataType.STRING);
        if (id == null) return false;
        if (id.equals(porEntidad.get(en.getUniqueId()))) return false;
        en.remove();
        return true;
    }

    // ---------------------------------------------------------------- muerte

    /**
     * La muerte del Eco (sec. 2.7-2.8). Orden fijo: fuera del registro y a disco, y despues el
     * botin. killer null (lo mato /kill o algo que no es un jugador): no suelta nada.
     */
    private void morir(Eco e, OfflinePlayer killer, Location donde, boolean forzarValida) {
        quitar(e);
        ConfigurationSection c = cfg();
        long ahora = System.currentTimeMillis();
        Player killerP = killer == null ? null : killer.getPlayer();
        String killerNombre = killer == null ? "-" : (killer.getName() == null ? killer.getUniqueId().toString() : killer.getName());
        if (killer == null) {
            anotar("muere", e.id, e.nombre, "-", "reliquias " + e.idsReliquias(), "esencias " + e.nEsencias, "pagado no:sin-asesino");
            Map<String, Object> t = campos(e, "muere");
            t.put("valida", false);
            t.put("motivo", "sin-asesino");
            telemetria(Bukkit.getOfflinePlayer(e.dueno), t);
            return;
        }
        boolean propio = killer.getUniqueId().equals(e.dueno);
        // Lo que llevaba el muerto: al suelo, reservado un rato para quien lo mato.
        List<ItemStack> botin = new ArrayList<>(e.reliquias);
        botin.addAll(e.esencias);
        soltar(donde, botin, killer.getUniqueId(), c.getInt("preferencia-segundos", 10));

        if (propio) {
            redimido(e, killer, killerP);
            anotar("muere", e.id, e.nombre, killerNombre, "reliquias " + e.idsReliquias(), "esencias " + e.nEsencias, "pagado no:dueno");
            return;
        }

        String motivo = forzarValida ? null : motivoCaza(e, killer, ahora);
        int grado = 0, pagadas = 0;
        if (motivo == null) {
            int nuevas = Eco.esenciasNuevas(e.nivel, c);
            grado = Eco.gradoLagrima(e.escalonMedio, e.piezasMmo, c);
            int g = grado;
            ItemStack lagrima = g <= 0 ? null
                    : hc.valor("reliquias", () -> hc.reliquias().crear(g, "eco", "lagrima-eco", e.nivel, null, true), null);
            List<ItemStack> rel = lagrima == null ? List.of() : List.of(lagrima);
            Aduana.Pago pago = hc.valor("aduana", () -> hc.aduana().pagar(killer, "eco", nuevas, 0, rel, "eco " + e.id), null);
            pagadas = pago == null ? 0 : pago.esencias();
            apuntarCobro(hc.datos(), killer.getUniqueId(), e.dueno, dia(), ahora);
            hc.seguro("estadisticas", () -> {
                hc.estadisticas().sumar(killer.getUniqueId(), "cazas-validas", 1);
                hc.estadisticas().sumar(killer.getUniqueId(), "ecos-cerrados", 1);
            });
            hc.guardarYa();
            if (killerP != null) {
                Component m = Component.text("El Eco te deja +").append(Component.text(pagadas, BLANCO))
                        .append(Component.text(" Esencias" + (lagrima != null ? ", y una Lágrima de Eco." : ".")));
                killerP.sendMessage(ComandoCalamity.mensaje(m));
            }
        } else if (killerP != null) {
            killerP.sendMessage(ComandoCalamity.mensaje(textoMotivo(motivo)));
        }
        // Trofeo: cosmetico, puede salir de Calamity.
        if (azar.nextDouble() < c.getDouble("trofeo-probabilidad", 0.25)) {
            soltar(donde, List.of(trofeo(e, killerNombre)), killer.getUniqueId(), c.getInt("preferencia-segundos", 10));
        }
        anotar("muere", e.id, e.nombre, killerNombre, "reliquias " + e.idsReliquias(), "esencias " + e.nEsencias,
                "pagado " + (motivo == null ? "si" : "no:" + motivo));
        Map<String, Object> t = campos(e, "muere");
        t.put("valida", motivo == null);
        t.put("motivo", motivo == null ? "" : motivo);
        t.put("lagrima", grado);
        t.put("esencias_pagadas", pagadas);
        t.put("cazador", killer.getUniqueId().toString());
        telemetria(killer, t);
        if (!e.prueba) {
            aTodoCalamity(ComandoCalamity.mensaje(Component.text(killerNombre, BLANCO)
                    .append(Component.text(" ha cerrado el Eco de "))
                    .append(Component.text(e.nombre, BLANCO)).append(Component.text("."))));
            avisar(e.dueno, ComandoCalamity.mensaje(Component.text(killerNombre, BLANCO)
                    .append(Component.text(" ha cerrado tu Eco y se ha llevado lo que llevabas."))));
        }
    }

    /** El dueno mata a su Eco: recupera lo suyo, cordura y (una vez por semana) una Marca. Sin Esencias nuevas ni Lagrima. */
    private void redimido(Eco e, OfflinePlayer dueno, Player duenoP) {
        ConfigurationSection c = cfg();
        if (duenoP != null && hc.esHardcore(duenoP)) hc.cordura().sumar(duenoP, c.getDouble("cordura-dueno", 30));
        hc.seguro("estadisticas", () -> hc.estadisticas().sumar(dueno.getUniqueId(), "ecos-redimidos", 1));
        if (duenoP != null) duenoP.sendMessage(ComandoCalamity.mensaje("Te has redimido."));
        anotar("redimido", e.id);
        // Es un credito y no dinero: no abre granja (morir y matarte a ti mismo no paga nada mas).
        if (e.nivel >= c.getInt("marcas.propio.nivel-minimo", 40) && equipoReal(e.escalonMedio, e.piezasMmo, c)
                && marcaPropia(hc.datos(), dueno.getUniqueId(), semana())) {
            hc.seguro("creditos", () -> hc.creditos().sumar(dueno.getUniqueId(), "marca", 1, "eco-propio", false));
            if (duenoP != null) duenoP.sendMessage(ComandoCalamity.mensaje("Tu propio Eco te deja una Marca de Eco."));
        }
        hc.guardarYa();
        Map<String, Object> t = campos(e, "redimido");
        t.put("valida", false);
        t.put("motivo", "dueno");
        telemetria(dueno, t);
    }

    /** Por que una caza no paga (null = paga). Aduana primero, luego las reglas del plan (PLAN sec. 3.2). */
    private String motivoCaza(Eco e, OfflinePlayer killer, long ahora) {
        OfflinePlayer dueno = Bukkit.getOfflinePlayer(e.dueno);
        boolean valida = hc.valor("aduana", () -> hc.aduana().valida(killer, dueno), false);
        if (!valida) {
            String m = hc.valor("aduana", () -> hc.aduana().motivoInvalida(killer, dueno), "aduana");
            return m == null || m.isBlank() ? "aduana" : m;
        }
        long par = hc.datos().getLong("eco-pares." + killer.getUniqueId() + "." + e.dueno, 0);
        return motivoCaza(cfg(), e.escalonMedio, e.piezasMmo, e.segundosDentro, e.nReliquias(), par, ahora,
                cobrosHoy(hc.datos(), killer.getUniqueId(), dia()));
    }

    /**
     * Las reglas de caza valida que no son de la Aduana (sec. 2.8 puntos 2-5). Estatica para el
     * autotest. Devuelve null si vale, o equipo | minutos | par | cobros.
     */
    static String motivoCaza(ConfigurationSection c, double escalonMedio, int piezasMmo, int segundosDentro,
                             int reliquias, long ultimoPar, long ahora, int cobrosHoy) {
        if (!equipoReal(escalonMedio, piezasMmo, c)) return "equipo";
        int minutos = c.getInt("caza-valida.minutos-minimos", 15);
        int oReliquias = c.getInt("caza-valida.o-reliquias", 1);
        if (segundosDentro < minutos * 60 && !(oReliquias > 0 && reliquias >= oReliquias)) return "minutos";
        long parDias = c.getLong("caza-valida.par-dias", 7);
        if (ultimoPar > 0 && ahora - ultimoPar < parDias * 86_400_000L) return "par";
        if (cobrosHoy >= c.getInt("cobros-dia", 5)) return "cobros";
        return null;
    }

    /** Equipo real (punto 2): escalon medio minimo, o una pieza MMOItems (sin prestadas ni copias). */
    static boolean equipoReal(double escalonMedio, int piezasMmo, ConfigurationSection c) {
        return escalonMedio + 1e-9 >= c.getDouble("caza-valida.escalon-medio-minimo", 4)
                || (c.getBoolean("caza-valida.o-pieza-mmoitems", true) && piezasMmo > 0);
    }

    private static Component textoMotivo(String motivo) {
        String m = motivo.toLowerCase(Locale.ROOT);
        if (m.contains("huella") || m.contains("ip") || m.contains("misma")) {
            return Component.text("Esa muerte no cuenta: Calamity reconoce a los tuyos.");
        }
        if (m.contains("hora")) return Component.text("Calamity aún no te conoce lo bastante.");
        if (m.equals("cobros")) return Component.text("Por hoy, esto ya no paga más.");
        String porque = switch (m) {
            case "equipo" -> "llevaba poco encima";
            case "minutos" -> "apenas había entrado";
            case "par" -> "ya cerraste un Eco suyo hace poco";
            default -> "Calamity no lo reconoce";
        };
        return Component.text("Ese Eco no paga: " + porque + ".");
    }

    // ----------------------------------------------------- cobros, pares y Marcas

    static int cobrosHoy(ConfigurationSection d, UUID cazador, String dia) {
        String b = "eco-cobros." + cazador;
        return dia.equals(d.getString(b + ".dia")) ? d.getInt(b + ".total") : 0;
    }

    /** Apunta la caza cobrada: el total del dia (cobros-dia) y el par (par-dias). */
    static void apuntarCobro(ConfigurationSection d, UUID cazador, UUID dueno, String dia, long ahora) {
        String b = "eco-cobros." + cazador;
        if (!dia.equals(d.getString(b + ".dia"))) {
            d.set(b, null);
            d.set(b + ".dia", dia);
        }
        d.set(b + ".total", d.getInt(b + ".total") + 1);
        d.set(b + ".por-dueno." + dueno, d.getInt(b + ".por-dueno." + dueno) + 1);
        d.set("eco-pares." + cazador + "." + dueno, ahora);
    }

    /**
     * Si toca Marca de Eco por una Lagrima tasada (marcas.dia / marcas.semana) y, si toca, la
     * apunta. Estatica para el autotest. marcas.<uuid> = {dia, hoy, semana, n-semana, propio-semana}.
     */
    static boolean marca(ConfigurationSection d, UUID u, String dia, String semana, int topeDia, int topeSemana) {
        String b = "marcas." + u;
        int hoy = dia.equals(d.getString(b + ".dia")) ? d.getInt(b + ".hoy") : 0;
        int sem = semana.equals(d.getString(b + ".semana")) ? d.getInt(b + ".n-semana") : 0;
        if (hoy >= topeDia || sem >= topeSemana) return false;
        d.set(b + ".dia", dia);
        d.set(b + ".hoy", hoy + 1);
        d.set(b + ".semana", semana);
        d.set(b + ".n-semana", sem + 1);
        return true;
    }

    /** La Marca del Eco propio: una por semana (marcas.propio.semanal). */
    static boolean marcaPropia(ConfigurationSection d, UUID u, String semana) {
        String b = "marcas." + u + ".propio-semana";
        if (semana.equals(d.getString(b))) return false;
        d.set(b, semana);
        return true;
    }

    /**
     * Para la Tasacion (WP2): al tasar una Lagrima con reliquia_valida, si al cazador aun le
     * toca Marca de Eco hoy y esta semana. Si devuelve true ya la ha apuntado en los topes; el
     * credito lo suma quien tasa (creditos.sumar(uuid, "marca", 1, ...)).
     */
    boolean marcaPermitida(UUID cazador) {
        ConfigurationSection c = cfg();
        boolean si = marca(hc.datos(), cazador, dia(), semana(), c.getInt("marcas.dia", 2), c.getInt("marcas.semana", 8));
        if (si) hc.marcarSucio();
        return si;
    }

    private String dia() {
        Calendario cal = hc.calendario();
        return cal == null ? java.time.LocalDate.now().toString() : cal.dia();
    }

    private String semana() {
        Calendario cal = hc.calendario();
        return cal == null ? "?" : cal.semana();
    }

    // ------------------------------------------------------------------ botin

    /** Al suelo, sin que lo coja un mob, reservado "segundos" para quien lo mato. */
    private void soltar(Location donde, List<ItemStack> items, UUID reservado, int segundos) {
        if (donde == null || donde.getWorld() == null || items.isEmpty()) return;
        donde.getChunk();   // cargado: si no, el item nace en un chunk que nadie mira
        long hasta = System.currentTimeMillis() + Math.max(0, segundos) * 1000L;
        for (ItemStack it : items) {
            if (it == null || it.getType().isAir()) continue;
            Item suelto = donde.getWorld().dropItemNaturally(donde, it.clone());
            suelto.setCanMobPickup(false);
            if (reservado != null && segundos > 0) {
                suelto.setOwner(reservado);
                soltados.add(new Object[]{suelto, hasta});
            }
        }
    }

    private void liberarSoltados(long ahora) {
        soltados.removeIf(s -> {
            Item it = (Item) s[0];
            if (!it.isValid()) return true;
            if (ahora < (long) s[1]) return false;
            it.setOwner(null);
            return true;
        });
    }

    /** Cabeza de <Nombre>: cosmetica, con su cara si la tenemos. */
    private ItemStack trofeo(Eco e, String killerNombre) {
        ItemStack h = new ItemStack(Material.PLAYER_HEAD);
        String fecha = DateTimeFormatter.ofPattern("dd/MM/yyyy")
                .withZone(hc.calendario() == null ? java.time.ZoneId.systemDefault() : hc.calendario().zona())
                .format(Instant.now());
        h.editMeta(SkullMeta.class, meta -> {
            try {
                com.destroystokyo.paper.profile.PlayerProfile perfil = Bukkit.createProfile(e.dueno, e.nombre);
                if (e.skinValor != null) {
                    perfil.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", e.skinValor, e.skinFirma));
                }
                meta.setPlayerProfile(perfil);
            } catch (Throwable t) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(e.dueno));
            }
            meta.displayName(Component.text("Cabeza de " + e.nombre, GRIS).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Eco cerrado por " + killerNombre + " · " + fecha, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(Marcas.TROFEO, PersistentDataType.BYTE, (byte) 1);
        });
        return h;
    }

    // --------------------------------------------------------------- listener

    /** La muerte del cuerpo. MONITOR: Amenazas ya vacio los drops en HIGHEST; el botin sale de los datos. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMuerteEco(EntityDeathEvent ev) {
        LivingEntity en = ev.getEntity();
        String id = en.getPersistentDataContainer().get(Marcas.ECO, PersistentDataType.STRING);
        if (id == null) return;
        Eco e = ecos.get(id);
        // Solo vale el cuerpo que conocemos: un duplicado que se colara no paga nada.
        if (e == null || e.cuerpo == null || !e.cuerpo.getUniqueId().equals(en.getUniqueId())) return;
        Player killer = en.getKiller();
        Location donde = en.getLocation();
        hc.seguro("ecos", () -> morir(e, killer, donde, false));
    }

    /** Escudo: 25 % de parar un golpe cuerpo a cuerpo de frente, como mucho uno cada 3 s. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEscudo(EntityDamageByEntityEvent ev) {
        Eco e = ecoDe(ev.getEntity());
        if (e == null || !e.escudo || !(ev.getDamager() instanceof Player p)) return;
        long ahora = System.currentTimeMillis();
        if (ahora < e.escudoListo) return;
        LivingEntity cuerpo = (LivingEntity) ev.getEntity();
        org.bukkit.util.Vector mira = cuerpo.getLocation().getDirection().setY(0);
        org.bukkit.util.Vector hacia = p.getLocation().toVector().subtract(cuerpo.getLocation().toVector()).setY(0);
        if (mira.lengthSquared() < 1e-6 || hacia.lengthSquared() < 1e-6 || mira.normalize().dot(hacia.normalize()) <= 0) return;
        if (azar.nextDouble() >= cfg().getDouble("escudo.probabilidad", 0.25)) return;
        e.escudoListo = ahora + cfg().getInt("escudo.espera-segundos", 3) * 1000L;
        ev.setCancelled(true);
        Compat.sound(cuerpo.getWorld(), cuerpo.getLocation(), "item.shield.block", 1f, 1f);
    }

    /** Quien le pega (ultimo agresor, recomposicion) y el sonido de jugador herido. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDanoEco(EntityDamageEvent ev) {
        Eco e = ecoDe(ev.getEntity());
        if (e == null) return;
        Entity causa;
        try {
            causa = ev.getDamageSource().getCausingEntity();
        } catch (Throwable t) {
            causa = null;
        }
        if (!(causa instanceof Player p)) return;
        e.ultimoAgresor = p.getUniqueId();
        e.ultimoAgresorEn = System.currentTimeMillis();
        e.ultimoDano = e.ultimoAgresorEn;
        Compat.soundPlayers(ev.getEntity().getWorld(), ev.getEntity().getLocation(), "entity.player.hurt", 1f, 0.8f);
    }

    /** El Eco pega (a mano o con flecha): cuenta para el Paso y suena a golpe de jugador. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGolpeDelEco(EntityDamageByEntityEvent ev) {
        if (!(ev.getEntity() instanceof Player)) return;
        Entity pega = ev.getDamager();
        if (pega instanceof Projectile pr && pr.getShooter() instanceof Entity tirador) pega = tirador;
        Eco e = ecoDe(pega);
        if (e == null) return;
        e.ultimoGolpeDado = System.currentTimeMillis();
        if (!(ev.getDamager() instanceof Projectile)) {
            Compat.soundPlayers(pega.getWorld(), pega.getLocation(), "entity.player.attack.strong", 1f, 1f);
        }
    }

    /** El esqueleto: la flecha pega lo que dice su foto, y no se puede recoger. */
    @EventHandler(ignoreCancelled = true)
    public void onDisparo(EntityShootBowEvent ev) {
        Eco e = ecoDe(ev.getEntity());
        if (e == null || !(ev.getProjectile() instanceof AbstractArrow flecha)) return;
        double quiere = e.dano * (e.desesperacionHasta > 0 ? 1 + cfg().getDouble("desesperacion.extra", 0.20) : 1);
        // El dano de una flecha es su base por la velocidad: se divide para que llegue "quiere".
        double v = Math.max(0.5, flecha.getVelocity().length());
        flecha.setDamage(quiere / v);
        flecha.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
    }

    /** Solo el objetivo que eligio la IA (nunca uno en llegada protegida, nunca volviendo a casa). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onObjetivo(EntityTargetEvent ev) {
        Eco e = ecoDe(ev.getEntity());
        if (e == null || ev.getTarget() == null) return;
        if (e.volviendo || e.elegido == null || !e.elegido.equals(ev.getTarget().getUniqueId())) ev.setCancelled(true);
    }

    /** P1 · el maniqui no recibe dano: el golpe de un jugador pasa al cuerpo, con el mismo autor. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDanoCascara(EntityDamageEvent ev) {
        if (!(ev.getEntity() instanceof Mannequin mq)) return;
        String id = mq.getPersistentDataContainer().get(Marcas.ECO, PersistentDataType.STRING);
        if (id == null) return;
        ev.setCancelled(true);
        Eco e = ecos.get(id);
        Entity causa;
        try {
            causa = ev.getDamageSource().getCausingEntity();
        } catch (Throwable t) {
            causa = null;
        }
        if (e != null && e.despierto() && causa instanceof Player p) e.cuerpo.damage(ev.getDamage(), p);
    }

    /** Al cargar entidades: fuera todo lo que lleve la marca y no sea la vista buena (X10). */
    @EventHandler
    public void onCargar(EntitiesLoadEvent ev) {
        if (!hc.esHardcore(ev.getWorld())) return;
        for (Entity en : ev.getEntities()) purgar(en);
    }

    /** Chunk que se descarga con un Eco despierto: se duerme (no persiste, no queda nada). */
    @EventHandler
    public void onDescargar(EntitiesUnloadEvent ev) {
        if (!hc.esHardcore(ev.getWorld())) return;
        for (Entity en : ev.getEntities()) {
            String id = porEntidad.get(en.getUniqueId());
            if (id == null) continue;
            Eco e = ecos.get(id);
            if (e != null && e.cuerpo != null && e.cuerpo.getUniqueId().equals(en.getUniqueId())) {
                hc.seguro("ecos", () -> dormir(e, "chunk"));
            }
        }
    }

    @EventHandler
    public void onSalir(PlayerQuitEvent ev) {
        ultimoAviso.remove(ev.getPlayer().getUniqueId());
        chat.remove(ev.getPlayer().getUniqueId());
    }

    /**
     * P1 · voces: las ultimas 3 frases de chat de cada uno dentro de Calamity. Llega en el hilo
     * del chat: solo se escribe en un mapa concurrente y se lee la config (sin tocar el mundo).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent ev) {
        if (!cfg().getBoolean("voces.activo", false)) return;
        Player p = ev.getPlayer();
        if (!hc.esHardcore(p.getWorld())) return;
        String texto = PlainTextComponentSerializer.plainText().serialize(ev.message()).trim();
        if (texto.isEmpty()) return;
        if (texto.length() > 80) texto = texto.substring(0, 80);
        Deque<Object[]> d = chat.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        synchronized (d) {
            d.addLast(new Object[]{System.currentTimeMillis(), texto});
            while (d.size() > 3) d.removeFirst();
        }
    }

    /** Las frases para la foto (P1): las de los ultimos voces.minutos, si las voces estan puestas. */
    List<String> frases(Player p) {
        if (!cfg().getBoolean("voces.activo", false)) return List.of();
        Deque<Object[]> d = chat.get(p.getUniqueId());
        if (d == null) return List.of();
        long desde = System.currentTimeMillis() - cfg().getInt("voces.minutos", 10) * 60_000L;
        List<String> out = new ArrayList<>();
        synchronized (d) {
            for (Object[] f : d) if ((long) f[0] >= desde) out.add((String) f[1]);
        }
        return out;
    }

    /** P1 · el Eco repite una de sus frases cada 20-40 s a quien este a voces.radio. */
    void voz(Eco e, Location pos, List<Player> cuentan, long ahora) {
        ConfigurationSection c = cfg();
        if (e.frases.isEmpty() || !c.getBoolean("voces.activo", false)) return;
        if (e.proximaVoz == 0) e.proximaVoz = ahora + c.getInt("voces.cada-min-segundos", 20) * 1000L;
        if (ahora < e.proximaVoz) return;
        int min = c.getInt("voces.cada-min-segundos", 20), max = Math.max(min, c.getInt("voces.cada-max-segundos", 40));
        e.proximaVoz = ahora + (min + azar.nextInt(max - min + 1)) * 1000L;
        double r = c.getDouble("voces.radio", 24);
        String frase = e.frases.get(azar.nextInt(e.frases.size()));
        Component m = Component.text("Eco de " + e.nombre + ": " + frase, NamedTextColor.DARK_GRAY);
        for (Player p : cuentan) if (p.getLocation().distanceSquared(pos) <= r * r) p.sendMessage(m);
    }

    /** El Eco de una entidad (cuerpo vivo conocido), o null. Lo barato primero: la marca. */
    private Eco ecoDe(Entity en) {
        if (en == null) return null;
        String id = en.getPersistentDataContainer().get(Marcas.ECO, PersistentDataType.STRING);
        if (id == null) return null;
        Eco e = ecos.get(id);
        return e != null && e.cuerpo != null && e.cuerpo.getUniqueId().equals(en.getUniqueId()) ? e : null;
    }

    /**
     * P-E12: el mensaje de muerte de quien cae ante un Eco, o null si no fue un Eco. Para
     * Hardcore.onMuerte (gancho pedido al coordinador: sustituye a "no volvio de Calamity").
     */
    Component mensajeMuerte(Player victima) {
        EntityDamageEvent ult = victima.getLastDamageCause();
        if (ult == null) return null;
        Entity causa = null;
        try {
            causa = ult.getDamageSource().getCausingEntity();
        } catch (Throwable ignorado) {
            // se mira quien pego
        }
        if (causa == null && ult instanceof EntityDamageByEntityEvent ee) {
            causa = ee.getDamager();
            if (causa instanceof Projectile pr && pr.getShooter() instanceof Entity t) causa = t;
        }
        Eco e = ecoDe(causa);
        if (e == null) return null;
        return Component.text(victima.getName() + " cayó ante el Eco de " + e.nombre + ".", ComandoCalamity.ROJO);
    }

    // ------------------------------------------------------------ jugadores

    /** Los jugadores de ese mundo que cuentan para las reglas (sin espectadores ni creativos). */
    List<Player> cuentanEn(World w) {
        List<Player> out = new ArrayList<>();
        if (w == null || !hc.esHardcore(w)) return out;
        for (Player p : w.getPlayers()) if (!p.isDead() && hc.cuenta(p)) out.add(p);
        return out;
    }

    Player jugador(UUID u, List<Player> entre) {
        if (u == null) return null;
        for (Player p : entre) if (p.getUniqueId().equals(u)) return p;
        return null;
    }

    /** Llegada protegida (M5): el Eco no la toca. */
    boolean protegido(Player p) {
        return hc.valor("combate", () -> hc.combate().protegido(p), false);
    }

    /** Aviso "Tu Eco · d m" cada 20 s a aviso-dueno-radio (lo llama el reloj por jugador). */
    void avisoDistancia(Player p) {
        if (ecos.isEmpty() || !activo()) return;
        long ahora = System.currentTimeMillis();
        Long antes = ultimoAviso.get(p.getUniqueId());
        if (antes != null && ahora - antes < 20_000) return;
        double r = cfg().getDouble("aviso-dueno-radio", 150);
        double mejor = r * r;
        Location cerca = null;
        for (Eco e : ecos.values()) {
            if (!e.dueno.equals(p.getUniqueId())) continue;
            Location l = e.despierto() ? e.cuerpo.getLocation() : e.anclaje();
            if (l == null || l.getWorld() != p.getWorld()) continue;
            double d2 = l.distanceSquared(p.getLocation());
            if (d2 <= mejor) {
                mejor = d2;
                cerca = l;
            }
        }
        if (cerca == null) return;
        ultimoAviso.put(p.getUniqueId(), ahora);
        hc.cordura().destello(p, Component.text("Tu Eco · " + Math.round(Math.sqrt(mejor)) + " m", GRIS), 2);
    }

    /** Al entrar a Calamity: donde esta cada Eco suyo (rumbo en 8 puntos). */
    void alEntrar(Player p) {
        if (!activo()) return;
        int n = 0;
        for (Eco e : ecos.values()) {
            if (!e.dueno.equals(p.getUniqueId())) continue;
            Location l = e.anclaje();
            if (l == null || l.getWorld() != p.getWorld()) continue;
            double dx = l.getX() - p.getLocation().getX(), dz = l.getZ() - p.getLocation().getZ();
            long d = Math.round(Math.sqrt(dx * dx + dz * dz));
            p.sendMessage(ComandoCalamity.mensaje(Component.text("Tu Eco sigue en pie, a ")
                    .append(Component.text(d, BLANCO)).append(Component.text(" bloques hacia el " + rumbo(dx, dz) + "."))));
            if (++n >= 3) break;
        }
    }

    /** Al conectarse (en cualquier mundo): lo que se le dijo estando fuera, y si entra ya dentro, sus Ecos. */
    void alVolver(Player p) {
        entregarPendientes(p);
        if (hc.esHardcore(p)) alEntrar(p);
    }

    /** Un tick despues de reaparecer: P-E01/P-E02, P-27, errante o sustituido. */
    void alReaparecer(Player p) {
        entregarPendientes(p);
    }

    /** Rumbo en 8 puntos (norte = -Z, este = +X). */
    static String rumbo(double dx, double dz) {
        double ang = Math.toDegrees(Math.atan2(dx, -dz));
        int i = (int) Math.floorMod(Math.round(ang / 45.0), 8);
        return RUMBOS[i];
    }

    private void avisar(UUID u, Component m) {
        Player p = Bukkit.getPlayer(u);
        if (p != null && p.isOnline() && !p.isDead()) p.sendMessage(m);
        else avisarLuego(u, m);
    }

    private void avisarLuego(UUID u, Component m) {
        if (u == null) return;
        List<Component> l = pendientes.computeIfAbsent(u, k -> new ArrayList<>());
        if (l.size() < 10) l.add(m);
    }

    private void entregarPendientes(Player p) {
        List<Component> l = pendientes.remove(p.getUniqueId());
        if (l != null) for (Component m : l) p.sendMessage(m);
    }

    private void aTodoCalamity(Component m) {
        for (World w : hc.plugin().getServer().getWorlds()) {
            if (!hc.esHardcore(w)) continue;
            for (Player p : w.getPlayers()) p.sendMessage(m);
        }
    }

    // ------------------------------------------------------------- registro

    private void anotar(String... campos) {
        try {
            String[] todo = new String[campos.length + 1];
            todo[0] = "eco";
            System.arraycopy(campos, 0, todo, 1, campos.length);
            hc.plugin().bitacora().anotar(todo);
        } catch (Throwable ignorado) {
            // Sin Bitacora no se para el Eco: lo importante ya esta en hardcore-datos.yml.
        }
    }

    private Map<String, Object> campos(Eco e, String accion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accion", accion);
        m.put("id", e.id);
        m.put("dueno", e.dueno.toString());
        m.put("nombre", e.nombre);
        m.put("n_e", e.nivel);
        m.put("reliquias", e.nReliquias());
        m.put("esencias", e.nEsencias);
        m.put("escalon_medio", e.escalonMedio);
        m.put("piezas_mmo", e.piezasMmo);
        if (e.prueba) m.put("prueba", true);
        return m;
    }

    private void telemetria(OfflinePlayer quien, Map<String, Object> campos) {
        Telemetria t = hc.telemetria();
        if (t != null) hc.seguro("telemetria", () -> t.suceso("eco", quien, campos));
    }

    // ------------------------------------------------------------ placeholders

    private void rehacerResumen() {
        Map<UUID, long[]> r = new HashMap<>();
        for (Eco e : ecos.values()) {
            long[] v = r.get(e.dueno);
            if (v == null || e.nacio > v[2]) r.put(e.dueno, new long[]{e.expira, e.nReliquias(), e.nacio});
        }
        resumen = r;
    }

    /** %lethalworld_eco% (horas y minutos del mas nuevo, o —) y %lethalworld_eco_reliquias%. */
    private String placeholder(OfflinePlayer jugador, boolean reliquias) {
        if (jugador == null) return "";
        long[] v = resumen.get(jugador.getUniqueId());
        if (v == null) return reliquias ? "0" : "—";
        if (reliquias) return String.valueOf(v[1]);
        long min = Math.max(0, (v[0] - System.currentTimeMillis()) / 60_000);
        return (min / 60) + " h " + (min % 60) + " min";
    }

    // ------------------------------------------------------------------ comandos

    private List<String> tab(String[] args) {
        if (args.length == 2) return List.of("crear", "lista", "borrar", "tp", "prueba", "despertar", "matar");
        if (args.length == 3) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("crear")) {
                List<String> n = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) n.add(p.getName());
                return n;
            }
            if (List.of("borrar", "tp", "despertar", "matar").contains(sub)) return new ArrayList<>(ecos.keySet());
        }
        if (args.length == 5 && args[1].equalsIgnoreCase("matar")) return List.of("--forzar-valida");
        return List.of();
    }

    private void comando(CommandSender quien, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "lista";
        switch (sub) {
            case "lista" -> lista(quien);
            case "crear" -> crear(quien, args);
            case "borrar" -> borrar(quien, args);
            case "tp" -> tp(quien, args);
            case "prueba" -> prueba(quien, args);
            case "despertar" -> despertarCmd(quien, args);
            case "matar" -> matar(quien, args);
            default -> quien.sendMessage(Component.text(
                    "Uso: /lw hardcore eco crear|lista|borrar|tp|prueba|despertar|matar", NamedTextColor.RED));
        }
    }

    private void decir(CommandSender quien, String texto) {
        quien.sendMessage(Component.text(texto, NamedTextColor.GRAY));
    }

    private void lista(CommandSender quien) {
        if (ecos.isEmpty()) {
            decir(quien, "eco | ninguno");
            return;
        }
        long ahora = System.currentTimeMillis();
        for (Eco e : ecos.values()) {
            String estado = (e.despierto() ? "despierto" : "latente") + (e.errante ? " errante" : "")
                    + (e.desmorona > 0 ? " desmoronando" : "");
            String dist = "";
            Location l = e.anclaje();
            if (quien instanceof Player p && l != null && l.getWorld() == p.getWorld()) {
                dist = " | " + Math.round(l.distance(p.getLocation())) + " m";
            }
            decir(quien, "eco | " + e.id + " | " + e.nombre + " | " + estado + " | N_E " + e.nivel
                    + " | " + Bitacora.dec(Math.max(0, e.expira - ahora) / 3_600_000.0) + " h | reliquias " + e.nReliquias()
                    + " | esencias " + e.nEsencias + " | " + Math.round(e.x) + " " + Math.round(e.y) + " " + Math.round(e.z) + dist);
        }
    }

    /** Eco con lo que lleva puesto, sin matarlo (sin Reliquias ni Esencias: no duplica). */
    private void crear(CommandSender quien, String[] args) {
        if (args.length < 3) {
            decir(quien, "Uso: /lw hardcore eco crear <jugador>");
            return;
        }
        Player p = Bukkit.getPlayerExact(args[2]);
        if (p == null) {
            decir(quien, "No encuentro a ese jugador.");
            return;
        }
        if (!hc.esHardcore(p)) {
            decir(quien, "eco | " + p.getName() + " no está en un mundo hardcore.");
            return;
        }
        FotoMuerte f = FotoMuerte.de(p, hc, false);
        Eco e = nacer(f, true);
        e.prueba = false;
        guardar(e);
        hc.guardarYa();
        decir(quien, "eco | creado | " + e.id + " | N_E " + e.nivel + " | vida " + Bitacora.num(e.vidaMax)
                + " | dano " + Bitacora.dec(e.dano));
    }

    private void borrar(CommandSender quien, String[] args) {
        if (args.length < 3) {
            decir(quien, "Uso: /lw hardcore eco borrar <id|jugador>");
            return;
        }
        List<Eco> fuera = new ArrayList<>();
        Eco porId = ecos.get(args[2]);
        if (porId != null) fuera.add(porId);
        else for (Eco e : ecos.values()) if (e.nombre.equalsIgnoreCase(args[2])) fuera.add(e);
        if (fuera.isEmpty()) {
            decir(quien, "eco | " + args[2] + " no existe");
            return;
        }
        for (Eco e : fuera) deshacer(e, "borrado", quien.getName());
        decir(quien, "eco | borrados " + fuera.size());
    }

    private void tp(CommandSender quien, String[] args) {
        if (!(quien instanceof Player p)) {
            decir(quien, "Solo desde el juego.");
            return;
        }
        Eco e = args.length >= 3 ? ecos.get(args[2]) : null;
        Location l = e == null ? null : e.anclaje();
        if (l == null) {
            decir(quien, "eco | " + (args.length >= 3 ? args[2] : "?") + " no existe o su mundo no está cargado");
            return;
        }
        p.teleport(l.clone().add(0, 0.5, 0));
    }

    /**
     * eco prueba <nombre> <x> <y> <z> [N] [escalon]: un Eco sin jugador, en el primer mundo
     * hardcore, con equipo vanilla del escalon (cuero 0 ... netherita 6) y el censo que se
     * le diga. 20 min "dentro" para que la regla de minutos no estorbe al probar la caza.
     */
    private void prueba(CommandSender quien, String[] args) {
        if (args.length < 6) {
            decir(quien, "Uso: /lw hardcore eco prueba <nombre> <x> <y> <z> [N] [escalon]");
            return;
        }
        World w = primerMundo();
        if (w == null) {
            decir(quien, "No hay ningun mundo hardcore cargado.");
            return;
        }
        String nombre = args[2];
        double x, y, z;
        int n, escalon;
        try {
            x = Double.parseDouble(args[3]);
            y = Double.parseDouble(args[4]);
            z = Double.parseDouble(args[5]);
            n = args.length > 6 ? Integer.parseInt(args[6]) : 20;
            escalon = args.length > 7 ? Integer.parseInt(args[7]) : 4;
        } catch (NumberFormatException ex) {
            decir(quien, "Coordenadas, nivel o escalon no validos.");
            return;
        }
        OfflinePlayer cache = Bukkit.getOfflinePlayerIfCached(nombre);
        UUID u = cache != null ? cache.getUniqueId()
                : UUID.nameUUIDFromBytes(("OfflinePlayer:" + nombre).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        FotoMuerte f = fotoDePrueba(u, nombre, new Location(w, x, y, z), n, escalon);
        Eco e = nacer(f, true);
        decir(quien, "eco | prueba | " + e.id + " | N_E " + e.nivel + " | vida " + Bitacora.num(e.vidaMax)
                + " | dano " + Bitacora.dec(e.dano) + " | escalon " + escalon);
    }

    /** Foto sintetica de "eco prueba" (y del autotest): equipo del escalon y censo a medida. */
    @SuppressWarnings("deprecation")
    static FotoMuerte fotoDePrueba(UUID u, String nombre, Location donde, int n, int escalon) {
        FotoMuerte f = new FotoMuerte();
        f.dueno = u;
        f.nombre = nombre;
        f.anclaje = donde;
        String pre = escalon >= 6 ? "NETHERITE" : escalon >= 4 ? "DIAMOND" : escalon >= 2 ? "IRON" : escalon >= 1 ? "CHAINMAIL" : "LEATHER";
        String arma = escalon >= 6 ? "NETHERITE" : escalon >= 4 ? "DIAMOND" : escalon >= 2 ? "IRON" : escalon >= 1 ? "STONE" : "WOODEN";
        String[] piezas = {"_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS"};
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        double a = 0, t = 0;
        for (int i = 0; i < 4; i++) {
            Material m = Material.matchMaterial(pre + piezas[i]);
            if (m == null) continue;
            f.equipo[i] = FotoMuerte.copiaVisual(new ItemStack(m));
            f.piezas++;
            try {
                for (AttributeModifier mod : m.getDefaultAttributeModifiers(slots[i]).get(Compat.attribute("armor"))) a += mod.getAmount();
                for (AttributeModifier mod : m.getDefaultAttributeModifiers(slots[i]).get(Compat.attribute("armor_toughness"))) t += mod.getAmount();
            } catch (Throwable ignorado) {
                // sin modificadores de fabrica: 0
            }
        }
        Material espada = Material.matchMaterial(arma + "_SWORD");
        if (espada != null) {
            f.equipo[FotoMuerte.HAND] = FotoMuerte.copiaVisual(new ItemStack(espada));
            f.piezas++;
            f.dano = FotoMuerte.danoItem(new ItemStack(espada));
        } else {
            f.dano = 1;
        }
        f.vida = 20;
        f.armadura = a;
        f.dureza = t;
        f.nivel = Math.max(1, n);
        f.segundosDentro = 20 * 60;
        f.censo(new Censo.Foto(List.of(), escalon, escalon, 0, 0));
        return f;
    }

    private World primerMundo() {
        for (String m : hc.mundos()) {
            for (World x : hc.plugin().getServer().getWorlds()) {
                if (hc.esHardcore(x) && x.getKey().getKey().equals(m)) return x;
            }
        }
        return null;
    }

    private void despertarCmd(CommandSender quien, String[] args) {
        Eco e = args.length >= 3 ? ecos.get(args[2]) : null;
        if (e == null) {
            decir(quien, "eco | " + (args.length >= 3 ? args[2] : "?") + " no existe");
            return;
        }
        if (e.despierto()) {
            decir(quien, "eco | " + e.id + " | ya despierto");
            return;
        }
        Location l = e.anclaje();
        if (l == null) {
            decir(quien, "eco | " + e.id + " | su mundo no esta cargado");
            return;
        }
        l.getChunk();
        if (despertar(e, quien.getName())) decir(quien, "eco | " + e.id + " | despierto");
        else decir(quien, "eco | " + e.id + " | no ha salido (spawn cancelado)");
    }

    /**
     * eco matar <id> [killer] [--forzar-valida]: lo mata como si lo hubiera matado ese jugador
     * (conectado o no). Sin killer, sin botin. Con --forzar-valida paga aunque la caza no valga.
     */
    private void matar(CommandSender quien, String[] args) {
        Eco e = args.length >= 3 ? ecos.get(args[2]) : null;
        if (e == null) {
            decir(quien, "eco | " + (args.length >= 3 ? args[2] : "?") + " no existe");
            return;
        }
        boolean forzar = false;
        OfflinePlayer killer = null;
        for (int i = 3; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--forzar-valida")) {
                forzar = true;
                continue;
            }
            Player on = Bukkit.getPlayerExact(args[i]);
            killer = on != null ? on : Bukkit.getOfflinePlayerIfCached(args[i]);
            if (killer == null) {
                decir(quien, "No encuentro a ese jugador.");
                return;
            }
        }
        Location donde = e.despierto() ? e.cuerpo.getLocation() : e.anclaje();
        morir(e, killer, donde, forzar);
        decir(quien, "eco | " + e.id + " | muerto");
    }

    /** /calamity eco: tus Ecos, con bioma, sitio, nivel, reliquias y lo que les queda. */
    private void comandoJugador(CommandSender quien, String[] args) {
        if (!(quien instanceof Player p)) {
            decir(quien, "Solo desde el juego.");
            return;
        }
        long ahora = System.currentTimeMillis();
        int n = 0;
        for (Eco e : ecos.values()) {
            if (!e.dueno.equals(p.getUniqueId())) continue;
            n++;
            Location l = e.anclaje();
            // El bioma solo si el chunk esta cargado: un comando de jugador no carga chunks lejanos.
            Component bioma = l == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)
                    ? Component.text("lejos") : Component.translatable(l.getBlock().getBiome().translationKey());
            long min = Math.max(0, (e.expira - ahora) / 60_000);
            p.sendMessage(ComandoCalamity.mensaje(Component.text(e.errante ? "Eco errante · " : "Tu Eco · ")
                    .append(bioma.color(BLANCO))
                    .append(Component.text(" · "))
                    .append(Component.text(Math.round(e.x) + " " + Math.round(e.y) + " " + Math.round(e.z), BLANCO))
                    .append(Component.text(" · Nv. " + e.nivel + " · " + e.nReliquias() + " reliquias · quedan "
                            + (min / 60) + " h " + (min % 60) + " min"))));
        }
        if (n == 0) p.sendMessage(ComandoCalamity.mensaje("No tienes ningún Eco en pie."));
    }

    // --------------------------------------------------------------------- parar

    /** Duerme a los despiertos (guardando su vida), suelta el botin reservado y guarda. */
    void parar() {
        for (Eco e : ecos.values()) {
            if (e.cuerpo != null) {
                e.quitarVista(this);
                e.durmio = System.currentTimeMillis();
                apuntarVida(e);
            }
        }
        for (Object[] s : soltados) {
            Item it = (Item) s[0];
            if (it.isValid()) it.setOwner(null);
        }
        soltados.clear();
        porEntidad.clear();
        hc.guardarYa();
        HandlerList.unregisterAll(this);
    }

    // --------------------------------------------------------------- autotest

    /**
     * /lw hardcore autotest eco: las cuentas de sec. 2.4 y las reglas de caza valida con datos
     * sinteticos (UUID 00..0N, YamlConfiguration en memoria, config vacia = los defectos).
     */
    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        YamlConfiguration c = new YamlConfiguration();   // defectos del codigo = DIS sec. 4

        // --- sec. 2.4 (tabla de ejemplos)
        int nNovato = Eco.nivelEco(13, false, c);
        h.igual("novato N_E", 18, nNovato)
                .igual("novato vida", 101L, Math.round(Eco.vidaLogica(20, nNovato, c)))
                .cerca("novato dano", 6.4, Eco.dano(6, nNovato, c), 0.05)
                .igual("medio vida", 408L, Math.round(Eco.vidaLogica(40, 61, c)))
                .cerca("medio dano", 17.6, Eco.dano(10, 61, c), 0.05)
                .igual("veterano N_E", 100, Eco.nivelEco(123, false, c))
                .igual("veterano vida", 893L, Math.round(Eco.vidaLogica(60, 100, c)))
                .cerca("veterano dano con tope", 30, Eco.dano(21, 100, c), 1e-9)
                .igual("parca +5", 50, Eco.nivelEco(40, true, c))
                .cerca("vida minima", 60, Eco.vidaLogica(2, 1, c), 1e-9)
                .cerca("dano minimo", 3, Eco.dano(1, 1, c), 1e-9)
                .igual("esencias N 18", 1, Eco.esenciasNuevas(18, c))
                .igual("esencias N 45", 3, Eco.esenciasNuevas(45, c))
                .igual("esencias N 61", 4, Eco.esenciasNuevas(61, c))
                .igual("esencias N 100", 6, Eco.esenciasNuevas(100, c))
                .igual("heredadas 7 x 0.5", 3, FotoMuerte.heredadas(7, 0.5));

        // --- caza valida: equipo (escalon medio o pieza MMOItems)
        long t0 = 1_790_000_000_000L;
        h.igual("escalon 3 sin MMOItems no paga", "equipo", motivoCaza(c, 3, 0, 1200, 0, 0, t0, 0))
                .igual("escalon 3 con MMOItems paga", null, motivoCaza(c, 3, 1, 1200, 0, 0, t0, 0))
                .igual("escalon 3 con MMOItems -> Lagrima II", 2, Eco.gradoLagrima(3, 1, c))
                .igual("escalon 6 paga", null, motivoCaza(c, 6, 0, 1200, 0, 0, t0, 0))
                .igual("escalon 4.5 -> Lagrima II", 2, Eco.gradoLagrima(4.5, 0, c))
                .igual("escalon 6 -> Lagrima III", 3, Eco.gradoLagrima(6, 0, c))
                .igual("escalon 11 -> Lagrima III", 3, Eco.gradoLagrima(11, 0, c))
                .igual("escalon 12 -> Lagrima IV", 4, Eco.gradoLagrima(12, 0, c))
                .igual("escalon 15 -> Lagrima IV", 4, Eco.gradoLagrima(15, 0, c))
                .igual("escalon 3 sin nada -> sin Lagrima", 0, Eco.gradoLagrima(3, 0, c));
        // --- minutos o Reliquias
        h.igual("10 min y sin reliquias no paga", "minutos", motivoCaza(c, 6, 0, 600, 0, 0, t0, 0))
                .igual("10 min con 1 reliquia paga", null, motivoCaza(c, 6, 0, 600, 1, 0, t0, 0));

        // --- par cazador-dueno 7 dias y 5 cobros al dia (datos en memoria)
        YamlConfiguration d = new YamlConfiguration();
        UUID caz = Autotest.sintetico(1), dueno = Autotest.sintetico(2);
        apuntarCobro(d, caz, dueno, "2026-09-26", t0);
        long par = d.getLong("eco-pares." + caz + "." + dueno);
        h.igual("par apuntado", t0, par)
                .igual("par repetido a 3 dias no paga", "par", motivoCaza(c, 6, 0, 1200, 0, par, t0 + 3 * 86_400_000L, 0))
                .igual("par a 8 dias paga", null, motivoCaza(c, 6, 0, 1200, 0, par, t0 + 8 * 86_400_000L, 0));
        for (int i = 3; i <= 6; i++) apuntarCobro(d, caz, Autotest.sintetico(i), "2026-09-26", t0 + i);
        int cobros = cobrosHoy(d, caz, "2026-09-26");
        h.igual("5 cobros hoy", 5, cobros)
                .igual("6.a caza del dia no paga", "cobros", motivoCaza(c, 6, 0, 1200, 0, 0, t0, cobros))
                .igual("al dia siguiente se reinicia", 0, cobrosHoy(d, caz, "2026-09-27"));

        // --- Marcas de Eco: 2 al dia, 8 a la semana; la propia, 1 a la semana
        UUID m = Autotest.sintetico(7);
        h.ok("marca 1.a del dia", marca(d, m, "2026-09-21", "2026-W39", 2, 8))
                .ok("marca 2.a del dia", marca(d, m, "2026-09-21", "2026-W39", 2, 8))
                .ok("marca 3.a del dia sin credito", !marca(d, m, "2026-09-21", "2026-W39", 2, 8));
        int dadas = 2;
        for (int dia = 22; dia <= 25; dia++) {
            for (int k = 0; k < 2; k++) if (marca(d, m, "2026-09-" + dia, "2026-W39", 2, 8)) dadas++;
        }
        h.igual("8 marcas en la semana", 8, dadas)
                .ok("9.a de la semana sin credito", !marca(d, m, "2026-09-26", "2026-W39", 2, 8))
                .ok("semana nueva, marca", marca(d, m, "2026-09-28", "2026-W40", 2, 8))
                .ok("marca propia 1.a", marcaPropia(d, m, "2026-W39"))
                .ok("marca propia 2.a misma semana no", !marcaPropia(d, m, "2026-W39"))
                .ok("marca propia semana nueva", marcaPropia(d, m, "2026-W40"))
                .ok("propio N_E 45 con equipo real", equipoReal(6, 0, c) && 45 >= c.getInt("marcas.propio.nivel-minimo", 40));

        // --- recomposicion, rumbo, stats sin bracken
        h.cerca("recompone 10 s dormido", 0.7, Eco.recomponer(0.5, t0, t0 - 20_000, t0 + 10_000, 15, 0.02), 1e-9)
                .cerca("no recompone con golpe reciente", 0.5, Eco.recomponer(0.5, t0, t0 + 5_000, t0 + 10_000, 15, 0.02), 1e-9)
                .igual("rumbo norte", "norte", rumbo(0, -10))
                .igual("rumbo este", "este", rumbo(10, 0))
                .igual("rumbo suroeste", "suroeste", rumbo(-7, 7))
                .igual("rumbo noroeste", "noroeste", rumbo(-5, -5));
        List<AttributeModifier> mods = List.of(
                new AttributeModifier(new org.bukkit.NamespacedKey("autotest", "armadura"), 15, AttributeModifier.Operation.ADD_NUMBER),
                new AttributeModifier(new org.bukkit.NamespacedKey("bracken", "panacea_armor"), -0.6, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        h.cerca("sin bracken", 15, FotoMuerte.componer(0, mods), 1e-9);
        List<AttributeModifier> otros = List.of(mods.get(0),
                new AttributeModifier(new org.bukkit.NamespacedKey("autotest", "menos"), -0.6, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        h.cerca("otro namespace si cuenta", 6, FotoMuerte.componer(0, otros), 1e-9);

        // --- copias visuales y arma
        h.cerca("espada de hierro D 6", 6, FotoMuerte.danoItem(new ItemStack(Material.IRON_SWORD)), 1e-9);
        h.sinExcepcion("copia visual", () -> {
            ItemStack real = new ItemStack(Material.DIAMOND_SWORD);
            Enchantment filo = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft("sharpness"));
            if (filo != null) real.addUnsafeEnchantment(filo, 5);
            real.editMeta(mt -> mt.displayName(Component.text("Filo de prueba")));
            ItemStack copia = FotoMuerte.copiaVisual(real);
            if (!copia.getEnchantments().isEmpty()) throw new IllegalStateException("copia con encantamientos");
            if (!Marcas.tiene(copia, Marcas.ECO_COPIA)) throw new IllegalStateException("copia sin eco_copia");
            var atr = copia.getData(io.papermc.paper.datacomponent.DataComponentTypes.ATTRIBUTE_MODIFIERS);
            if (atr == null || !atr.modifiers().isEmpty()) throw new IllegalStateException("atributos no vacios");
            if (filo != null && !Boolean.TRUE.equals(copia.getData(io.papermc.paper.datacomponent.DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE))) {
                throw new IllegalStateException("sin brillo");
            }
        });

        // --- registro: ida y vuelta por YAML (en memoria)
        World w = primerMundo();
        if (w == null && !hc.plugin().getServer().getWorlds().isEmpty()) w = hc.plugin().getServer().getWorlds().get(0);
        if (w != null) {
            World mundo = w;
            h.sinExcepcion("registro ida y vuelta", () -> {
                FotoMuerte f = fotoDePrueba(Autotest.sintetico(8), "Prueba", new Location(mundo, 1, 2, 3), 40, 6);
                Eco e = Eco.deFoto("00000001", f, c, t0);
                YamlConfiguration y = new YamlConfiguration();
                e.guardar(y.createSection("ecos.00000001"));
                Eco v = Eco.cargar("00000001", y.getConfigurationSection("ecos.00000001"));
                if (v.nivel != 45 || Math.abs(v.vidaMax - e.vidaMax) > 1e-9 || Math.abs(v.dano - e.dano) > 1e-9
                        || v.escalonMedio != 6 || v.equipo[FotoMuerte.HAND] == null
                        || v.equipo[FotoMuerte.HAND].getType() != Material.NETHERITE_SWORD
                        || !v.dueno.equals(Autotest.sintetico(8)) || v.expira != t0 + 12 * 3_600_000L) {
                    throw new IllegalStateException("el registro no vuelve igual");
                }
            });
        } else {
            h.ok("registro ida y vuelta (sin mundos)", true);
        }
        return h.lineas();
    }
}
