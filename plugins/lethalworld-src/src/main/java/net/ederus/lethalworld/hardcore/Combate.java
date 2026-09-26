package net.ederus.lethalworld.hardcore;

import net.ederus.edm.comun.Bitacora;
import net.ederus.edm.comun.Compat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * M5 y M12 · La pelea en Calamity: etiqueta de combate, combat log, llegada protegida,
 * Frenesi y Sangre fresca (DIS M5, M12).
 *
 * Por que existe cada pieza:
 * - La etiqueta cierra las dos huidas baratas de una pelea: el Cristal (5 s quieto y fuera,
 *   X32) y el cable (desconectar y volver otro dia con todo, X17). Con etiqueta no se empieza
 *   un Cristal (Hardcore.empezarCristal) y desconectar cuenta como morir.
 * - La llegada protegida es para que acampar la puerta de entrada no sea la forma mas facil
 *   de cazar (X13): 20 s en los que ni das ni recibes de otros jugadores.
 * - Frenesi y Sangre fresca (P1, apagadas de serie) hacen que la cordura baja sea un riesgo
 *   con premio y que el PvP devuelva algo de cordura, no solo botin.
 *
 * Todo el estado vive en memoria (DIS sec. 8.3) salvo "cable.<uuid>", que tiene que
 * sobrevivir a un reinicio porque se cobra cuando el jugador vuelve.
 *
 * Los ganchos los llama Hardcore dentro de seguro(): onGolpe -> alGolpe y frenesiMob,
 * onFuegoAmigo -> alPvp, meter -> llegada, sacar -> alSalir, onSalir -> alDesconectar,
 * tick -> tick, empezarCristal -> enCombate. DanoVerdadero llama a etiquetar.
 */
final class Combate implements Listener {

    /*
     * La clase que MobsLethal pone a sus mobs ("edm:lethal_world_mob" = comun, destacado,
     * minijefe, estructura). La clave es de MobsLethal y alli es privada; aqui solo se lee
     * para reconocer a un minijefe (etiqueta y Sangre fresca). No es una marca de Calamity
     * y por eso no esta en Marcas.
     */
    private static final NamespacedKey CLASE_MOB = new NamespacedKey("edm", "lethal_world_mob");

    private final Hardcore hc;
    /** Etiquetas y llegadas: el reloj puro, sin Bukkit, para poder probarlo en memoria. */
    private final Relojes relojes = new Relojes();
    /** Sangre fresca cobrada: asesino -> victima -> cuando. Sobrevive al quit a proposito. */
    private final Sangre sangre = new Sangre();
    /** Quien esta en Frenesi ahora mismo: para el destello de entrada, una sola vez. */
    private final Set<UUID> enFrenesi = new HashSet<>();
    /** Ultimo jugador que pego a cada uno (el combat log y la Sangre se le apuntan a el). */
    private final Map<UUID, Golpe> ultimoAgresor = new HashMap<>();
    /** La victima, tomada antes de que onMuerte le borre el inventario y la cordura. */
    private final Map<UUID, Victima> victimas = new HashMap<>();

    /** Quien pego y cuando. */
    private record Golpe(UUID agresor, long cuando) {
    }

    /** Lo que decide si una muerte da Sangre fresca, leido antes de borrarlo todo. */
    record Victima(UUID asesino, int segundosDentro, int piezas, boolean arma) {
    }

    Combate(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("combate", this::autotest);
        Subcomandos.lw().registrar("combate",
                "combate <info|etiquetar|llegada|cable> <jugador> [borrar]: etiqueta, llegada y combat log",
                "ederus.mundos", this::comando, this::tab);
    }

    // ------------------------------------------------------------------ config

    private boolean activo() {
        return hc.cfg().getBoolean("combate.activo", true);
    }

    private int etiquetaSegundos() {
        return Math.max(1, hc.cfg().getInt("combate.etiqueta-segundos", 15));
    }

    private int llegadaSegundos() {
        return Math.max(0, hc.cfg().getInt("combate.llegada-segundos", 20));
    }

    private boolean frenesiActivo() {
        return hc.cfg().getBoolean("frenesi.activo", false);
    }

    private boolean sangreActiva() {
        return hc.cfg().getBoolean("sangre-fresca.activo", false);
    }

    // ------------------------------------------------------------ API publica

    /**
     * Da (o renueva) la etiqueta de combate. El destello sale solo al entrar en combate:
     * repetirlo cada golpe pisaria la barra de la cordura sin decir nada nuevo.
     */
    void etiquetar(Player p) {
        if (p == null || !activo() || !hc.esHardcore(p)) return;
        int s = etiquetaSegundos();
        if (relojes.etiquetar(p.getUniqueId(), System.currentTimeMillis(), s)) {
            hc.cordura().destello(p, Component.text("En combate · " + s + " s", ComandoCalamity.ROJO), 2);
        }
    }

    boolean enCombate(Player p) {
        return p != null && activo() && relojes.enCombate(p.getUniqueId(), System.currentTimeMillis());
    }

    /** Llegada protegida: la usan tambien los Ecos (objetivo), la PARCA y la Huella. */
    boolean protegido(Player p) {
        return p != null && activo() && relojes.protegido(p.getUniqueId(), System.currentTimeMillis());
    }

    /** Recien metido por la puerta (Hardcore.meter): empieza la llegada protegida. */
    void llegada(Player p) {
        if (!activo()) return;
        UUID u = p.getUniqueId();
        relojes.olvidar(u);
        ultimoAgresor.remove(u);
        relojes.llegada(u, System.currentTimeMillis(), llegadaSegundos());
    }

    /**
     * Un segundo de reloj por jugador que cuenta dentro: fin de etiqueta, fin de llegada
     * (P-C04), la chispa de la llegada y el Frenesi.
     */
    void tick(Player p) {
        UUID u = p.getUniqueId();
        long ahora = System.currentTimeMillis();
        if (relojes.acabaCombate(u, ahora)) {
            hc.cordura().destello(p, Component.text("Fuera de combate", NamedTextColor.GRAY), 2);
        }
        if (relojes.acabaLlegada(u, ahora)) {
            hc.cordura().destello(p, Component.text("Ya te ven.", ComandoCalamity.ROJO), 2);
        } else if (relojes.protegido(u, ahora)) {
            // Suave y poca: que los demas vean que acaba de llegar, no un faro.
            Compat.spawn(p.getWorld(), Particle.END_ROD, p.getLocation().add(0, 1, 0), 3, 0.3, 0.5, 0.3, 0.01);
        }

        if (!frenesiActivo() || !hc.cordura().conoce(p)) {
            enFrenesi.remove(u);
            return;
        }
        double umbral = hc.cfg().getDouble("frenesi.umbral", 25);
        if (hc.cordura().valor(p) < umbral) {
            if (enFrenesi.add(u)) {
                hc.cordura().destello(p, Component.text("Frenesí. Pegas más. Te pegan más.", ComandoCalamity.ROJO), 3);
            }
            // Cada 2 s y lo ven todos: el frenesi es un aviso para los demas, no un secreto.
            if (hc.cordura().estado(p).segundosDentro % 2 == 0) {
                Compat.spawn(p.getWorld(), Particle.DAMAGE_INDICATOR, p.getLocation().add(0, 2.3, 0), 2, 0.2, 0.1, 0.2, 0.0);
            }
        } else {
            enFrenesi.remove(u);
        }
    }

    /**
     * onGolpe (HIGH): etiqueta por golpes con una amenaza (PARCA, Eco, planidera) o con un
     * minijefe, en las dos direcciones. El PvP lo etiqueta alPvp, que lo ve despues de que
     * Hardcore lo descancele y despues de la llegada protegida: aqui podria estar cancelado
     * por otro plugin y aun asi pasar, o al reves.
     */
    void alGolpe(EntityDamageByEntityEvent e) {
        if (!activo()) return;
        Entity victima = e.getEntity();
        Entity autor = autor(e.getDamager());
        if (victima instanceof Player v && autor instanceof Player a) {
            // Sin fuego amigo Hardcore no llama a alPvp: si el golpe llega aqui es que el
            // servidor lo deja pasar, y la pelea es igual de real.
            if (!hc.cfg().getBoolean("dificultad.fuego-amigo", true) && !v.equals(a)) {
                etiquetar(a);
                etiquetar(v);
            }
            return;
        }
        if (victima instanceof Player v && (peligro(autor) || peligro(e.getDamager()))) etiquetar(v);
        if (autor instanceof Player a && peligro(victima)) etiquetar(a);
    }

    /**
     * onFuegoAmigo (HIGHEST, ya descancelado): llegada protegida, dano PvP y etiqueta.
     * Orden del dano (DIS M12): base x frenesi(agresor) x frenesi(victima) x eclipse.
     */
    void alPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player v) || !(autor(e.getDamager()) instanceof Player a)) return;
        if (a.equals(v)) return;
        long ahora = System.currentTimeMillis();
        if (activo()) {
            if (relojes.protegido(v.getUniqueId(), ahora)) {
                e.setCancelled(true);
                return;
            }
            // Quien llega y pega elige pelear: pierde la proteccion y su golpe entra.
            if (relojes.protegido(a.getUniqueId(), ahora)) {
                relojes.perderLlegada(a.getUniqueId());
                hc.cordura().destello(a, Component.text("Ya te ven.", ComandoCalamity.ROJO), 2);
            }
        }
        // El dano verdadero ya es exacto (ley 5): ni frenesi ni eclipse encima.
        if (!DanoVerdadero.enCurso.contains(v.getUniqueId())) {
            Eclipse ec = hc.eclipse();
            double eclipse = ec == null ? 1.0 : hc.valor("eclipse", ec::factorPvp, 1.0);
            double f = factorPvp(frenesi(a, "dano-hecho"), frenesi(v, "dano-recibido"), eclipse);
            if (f != 1.0) e.setDamage(e.getDamage() * f);
        }
        if (!activo()) return;
        etiquetar(a);
        etiquetar(v);
        ultimoAgresor.put(v.getUniqueId(), new Golpe(a.getUniqueId(), ahora));
    }

    /**
     * onGolpe (HIGH): Frenesi contra y de todo lo que no sea otro jugador (el PvP va por
     * alPvp). Nunca sobre el golpe letal del dano verdadero.
     */
    void frenesiMob(EntityDamageByEntityEvent e) {
        if (!frenesiActivo()) return;
        Entity victima = e.getEntity();
        Entity autor = autor(e.getDamager());
        if (victima instanceof Player && autor instanceof Player) return;
        if (DanoVerdadero.enCurso.contains(victima.getUniqueId())) return;
        double f = 1.0;
        if (victima instanceof Player v && autor instanceof LivingEntity) f *= frenesi(v, "dano-recibido");
        if (autor instanceof Player a && victima instanceof LivingEntity) f *= frenesi(a, "dano-hecho");
        if (f != 1.0) e.setDamage(e.getDamage() * f);
    }

    /**
     * El combat log (M5). Lo llama Hardcore.onSalir lo primero, cuando el jugador aun esta
     * en el mundo y con su inventario.
     */
    void alDesconectar(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        try {
            boolean etiqueta = relojes.enCombate(u, System.currentTimeMillis());
            if (activo() && hc.cfg().getBoolean("combate.combat-log", true) && hc.esHardcore(p) && hc.cuenta(p)
                    && esCombatLog(etiqueta, e.getReason(), hc.cfg().getBoolean("combate.contar-timeout", true))) {
                cable(p);
            }
        } finally {
            olvidar(u);
        }
    }

    /** Sale de Calamity (puerta, Cristal, admin): fuera no hay pelea que recordar. */
    void alSalir(Player p) {
        olvidar(p.getUniqueId());
    }

    void parar() {
        relojes.combateHasta.clear();
        relojes.llegadaHasta.clear();
        enFrenesi.clear();
        ultimoAgresor.clear();
        victimas.clear();
    }

    private void olvidar(UUID u) {
        relojes.olvidar(u);
        enFrenesi.remove(u);
        ultimoAgresor.remove(u);
        victimas.remove(u);
    }

    // ------------------------------------------------------------- combat log

    /**
     * Huyo por el cable: cuenta como una muerte (foto, Eco, inventario borrado) a manos del
     * ultimo jugador que le pego. Lo demas (sacarlo, reiniciar la cordura, la racha y el
     * mensaje) se hace cuando vuelve, que es cuando tiene cuerpo para verlo.
     */
    private void cable(Player p) {
        UUID u = p.getUniqueId();
        Location l = p.getLocation();
        Player asesino = asesinoReciente(u);
        Victima victima = victima(p, asesino);

        // La foto ANTES de borrar nada, igual que en onMuerte: el Eco lleva lo que llevaba.
        FotoMuerte foto = hc.valor("eco", () -> FotoMuerte.de(p, hc), null);
        Parca parca = hc.parca();
        if (parca != null) hc.seguro("parca", () -> parca.alMorirPresa(p));
        Testigos testigos = hc.testigos();
        if (testigos != null) hc.seguro("testigos", () -> testigos.alMorir(p));
        Telemetria tel = hc.telemetria();
        if (tel != null) hc.seguro("telemetria", () -> tel.muere(p, foto));
        Estadisticas st = hc.estadisticas();
        if (st != null) hc.seguro("estadisticas", () -> st.sumar(u, "muertes", 1));

        vaciar(p);
        // Sincrono: lo que llevaba ya no existe y el Eco sale de la foto. Si el servidor
        // cae ahora, al volver tiene que seguir constando que huyo.
        hc.datos().set("cable." + u, System.currentTimeMillis());
        hc.guardarYa();

        hc.plugin().bitacora().anotar("combate", "cable", p.getName(),
                l.getWorld().getKey().getKey() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(),
                asesino == null ? "sin agresor" : "por " + asesino.getName(),
                "cordura " + Math.round(hc.cordura().conoce(p) ? hc.cordura().valor(p) : 0),
                "dentro " + victima.segundosDentro() + " s");

        Component aviso = ComandoCalamity.mensaje(Component.text(p.getName(), NamedTextColor.WHITE)
                .append(Component.text(" intentó huir por el cable.")));
        for (World w : hc.plugin().getServer().getWorlds()) {
            if (!hc.esHardcore(w)) continue;
            for (Player otro : w.getPlayers()) if (!otro.equals(p)) otro.sendMessage(aviso);
        }

        if (asesino != null && !asesino.equals(p)) sangrePvp(asesino, p, victima);
        if (foto != null) {
            Ecos ecos = hc.ecos();
            if (ecos != null) hc.seguro("eco", () -> ecos.programar(foto));
        }
    }

    /**
     * Vacia todo lo que el jugador podria conservar al desconectarse. El cursor y la mesa de
     * 2x2 los devuelve el servidor al inventario DESPUES del evento de salida (al cerrar la
     * ventana): si no se vacian aqui, vuelven al guardarse el jugador.
     */
    private static void vaciar(Player p) {
        p.setItemOnCursor(null);
        Inventory arriba = p.getOpenInventory().getTopInventory();
        if (ventanaPropia(arriba.getType())) arriba.clear();
        p.getInventory().clear();
        p.setLevel(0);
        p.setExp(0);
        p.setTotalExperience(0);
    }

    /** Ventanas cuyo contenido es del jugador y vuelve a su inventario al cerrarlas. */
    private static boolean ventanaPropia(InventoryType t) {
        return switch (t) {
            case CRAFTING, WORKBENCH, ANVIL, GRINDSTONE, SMITHING, ENCHANTING, CARTOGRAPHY, LOOM, STONECUTTER,
                 MERCHANT -> true;
            default -> false;
        };
    }

    /** Vuelve quien huyo por el cable: fuera, cordura entera, racha a 0 y P-C02. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVolver(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!hc.datos().isSet("cable." + p.getUniqueId())) return;
        // Un tick despues: en el propio evento de entrada el teleport no siempre se respeta,
        // y Hardcore.onEntrar ya le ha devuelto la cordura guardada que aqui se anula.
        hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(), () -> {
            if (p.isOnline() && hc.activo()) hc.seguro("combate", () -> volverDelCable(p));
        }, 1L);
    }

    private void volverDelCable(Player p) {
        String ruta = "cable." + p.getUniqueId();
        if (!hc.datos().isSet(ruta)) return;
        hc.datos().set(ruta, null);
        hc.marcarSucio();
        if (hc.esHardcore(p)) hc.sacar(p, "cable", false);
        Racha racha = hc.racha();
        if (racha != null) hc.seguro("racha", () -> racha.alMorir(p));
        p.sendMessage(ComandoCalamity.mensaje("Huiste por el cable. Calamity se lo cobró."));
        hc.plugin().bitacora().anotar("combate", "cable-vuelta", p.getName());
    }

    /**
     * Si una salida cuenta como combat log. Kick del staff y caida del servidor no cuentan
     * (no es huir); el timeout si, salvo que contar-timeout lo apague: es la regla de todos
     * los juegos de extraccion y la unica forma de que tirar del router no salga gratis.
     */
    static boolean esCombatLog(boolean enCombate, PlayerQuitEvent.QuitReason motivo, boolean contarTimeout) {
        if (!enCombate || motivo == null) return false;
        return motivo == PlayerQuitEvent.QuitReason.DISCONNECTED
                || (contarTimeout && motivo == PlayerQuitEvent.QuitReason.TIMED_OUT);
    }

    /** El ultimo jugador que le pego, si fue dentro de la etiqueta y sigue conectado. */
    private Player asesinoReciente(UUID victima) {
        Golpe g = ultimoAgresor.get(victima);
        if (g == null || System.currentTimeMillis() - g.cuando() > etiquetaSegundos() * 1000L) return null;
        return hc.plugin().getServer().getPlayer(g.agresor());
    }

    // ---------------------------------------------------------- Sangre fresca

    /**
     * La victima se lee en HIGH porque Hardcore.onMuerte (HIGHEST) le borra el inventario
     * y le reinicia la cordura; se cobra en MONITOR, cuando ya se sabe que la muerte vale.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMuerteAntes(PlayerDeathEvent e) {
        Player v = e.getEntity();
        if (!sangreActiva() || !hc.esHardcore(v)) return;
        Player asesino = v.getKiller();
        if (asesino == null) asesino = asesinoReciente(v.getUniqueId());
        if (asesino == null || asesino.equals(v)) return;
        victimas.put(v.getUniqueId(), victima(v, asesino));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMuerteDespues(PlayerDeathEvent e) {
        Player v = e.getEntity();
        Victima victima = victimas.remove(v.getUniqueId());
        ultimoAgresor.remove(v.getUniqueId());
        if (victima == null || e.isCancelled()) return;
        Player asesino = hc.plugin().getServer().getPlayer(victima.asesino());
        if (asesino != null) sangrePvp(asesino, v, victima);
    }

    /** Ecos ajenos y minijefes (la PARCA la paga WP5 con sangreFresca(p, "parca", ...)). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMuerteMob(EntityDeathEvent e) {
        LivingEntity muerto = e.getEntity();
        if (muerto instanceof Player || !sangreActiva() || !hc.esHardcore(muerto.getWorld())) return;
        Player asesino = muerto.getKiller();
        if (asesino == null) return;
        String amenaza = Marcas.amenaza(muerto);
        if ("eco".equals(amenaza)) {
            String dueno = muerto.getPersistentDataContainer().get(Marcas.ECO_DUENO, PersistentDataType.STRING);
            if (dueno == null || dueno.equals(asesino.getUniqueId().toString())) return;   // el tuyo no calma
            OfflinePlayer d = offline(dueno);
            if (d == null || !valida(asesino, d)) return;
            String id = muerto.getPersistentDataContainer().get(Marcas.ECO, PersistentDataType.STRING);
            sangreFresca(asesino, "eco", "eco:" + (id == null ? dueno : id));
        } else if (amenaza == null && esMinijefe(muerto)) {
            sangreFresca(asesino, "minijefe", muerto.getUniqueId().toString());
        }
    }

    /** Matar a otro jugador valido (DIS M12): 5 min dentro y 2 piezas o un arma. */
    private void sangrePvp(Player asesino, Player victima, Victima v) {
        int minutos = hc.cfg().getInt("sangre-fresca.minutos-dentro-victima", 5);
        int piezas = hc.cfg().getInt("sangre-fresca.piezas-minimas", 2);
        if (!victimaValida(v.segundosDentro(), v.piezas(), v.arma(), minutos, piezas)) return;
        if (!valida(asesino, victima)) return;
        sangreFresca(asesino, "pvp", victima.getUniqueId().toString());
    }

    /**
     * Cordura por sangre (P-F02). Del paquete para la PARCA (tipo "parca", al participante
     * que cobra). Pasa por la Aduana con el tipo "sangre" (tope diario 12) y como mucho una
     * vez por victima cada minutos-misma-victima.
     *
     * @param tipo    pvp | eco | minijefe | parca (la clave de sangre-fresca con la cordura)
     * @param victima identificador estable de la victima (uuid del jugador, "eco:<id>"...)
     */
    void sangreFresca(Player asesino, String tipo, String victima) {
        if (asesino == null || !sangreActiva() || !hc.esHardcore(asesino)) return;
        int n = hc.cfg().getInt("sangre-fresca." + tipo, switch (tipo) {
            case "pvp" -> 20;
            case "eco" -> 10;
            case "minijefe" -> 15;
            case "parca" -> 40;
            default -> 0;
        });
        if (n <= 0) return;
        long ahora = System.currentTimeMillis();
        long ventana = Math.max(0, hc.cfg().getLong("sangre-fresca.minutos-misma-victima", 30)) * 60_000L;
        UUID u = asesino.getUniqueId();
        if (!sangre.puede(u, victima, ahora, ventana)) return;
        Aduana aduana = hc.aduana();
        if (aduana == null) return;
        // Sin esencias ni MobCoins: la Aduana solo lleva la cuenta del tope diario.
        Aduana.Pago pago = hc.valor("aduana", () -> aduana.pagar(asesino, "sangre", 0, 0, List.of(), tipo), null);
        if (pago == null || pago.topado()) return;
        sangre.apuntar(u, victima, ahora, ventana);
        hc.cordura().sumar(asesino, n);
        hc.cordura().destello(asesino, Component.text("+" + n + " de cordura · la sangre calma", ItemsCalamity.VERDE), 2);
        hc.plugin().bitacora().anotar("combate", "sangre", asesino.getName(), "+" + n, tipo, victima);
    }

    private boolean valida(OfflinePlayer a, OfflinePlayer b) {
        Aduana aduana = hc.aduana();
        return aduana != null && hc.valor("aduana", () -> aduana.valida(a, b), false);
    }

    private OfflinePlayer offline(String uuid) {
        try {
            return hc.plugin().getServer().getOfflinePlayer(UUID.fromString(uuid));
        } catch (IllegalArgumentException malo) {
            return null;
        }
    }

    /** Lo que decide la Sangre fresca, leido del jugador tal como esta ahora. */
    private Victima victima(Player p, Player asesino) {
        int segundos = hc.cordura().conoce(p) ? hc.cordura().estado(p).segundosDentro : 0;
        int piezas = 0;
        for (ItemStack it : p.getInventory().getArmorContents()) {
            if (it != null && !it.getType().isAir()) piezas++;
        }
        boolean arma = esArma(p.getInventory().getItemInMainHand().getType());
        return new Victima(asesino == null ? null : asesino.getUniqueId(), segundos, piezas, arma);
    }

    // ------------------------------------------------------------ Ecos y llegada

    /**
     * Los Ecos no toman como objetivo a quien acaba de llegar (M5, X13): un Eco plantado en
     * la llegada no puede matar a nadie nada mas entrar.
     */
    @EventHandler(ignoreCancelled = true)
    public void onApuntar(EntityTargetLivingEntityEvent e) {
        if (!(e.getTarget() instanceof Player p) || !"eco".equals(Marcas.amenaza(e.getEntity()))) return;
        if (protegido(p)) e.setCancelled(true);
    }

    // ------------------------------------------------------------ utilidades

    /** Frenesi de un jugador para "dano-hecho" o "dano-recibido"; 1 si no aplica. */
    private double frenesi(Player p, String clave) {
        if (!frenesiActivo() || !hc.esHardcore(p) || !hc.cordura().conoce(p)) return 1.0;
        return factorFrenesi(hc.cordura().valor(p), hc.cfg().getDouble("frenesi.umbral", 25),
                hc.cfg().getDouble("frenesi." + clave, 0.15));
    }

    /** Cordura por debajo del umbral: 1 + extra. En el umbral justo, nada (DIS: "< 25"). */
    static double factorFrenesi(double cordura, double umbral, double extra) {
        return cordura < umbral ? 1.0 + extra : 1.0;
    }

    static double factorPvp(double frenesiAgresor, double frenesiVictima, double eclipse) {
        return frenesiAgresor * frenesiVictima * eclipse;
    }

    static boolean victimaValida(int segundosDentro, int piezas, boolean arma, int minutosMinimos, int piezasMinimas) {
        return segundosDentro >= minutosMinimos * 60 && (piezas >= piezasMinimas || arma);
    }

    static boolean esArma(Material m) {
        if (m == null) return false;
        String n = m.name();
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_SPEAR")
                || m == Material.BOW || m == Material.CROSSBOW || m == Material.TRIDENT || m == Material.MACE;
    }

    /** Una amenaza de verdad (las alucinaciones no pegan, ley 3) o un minijefe. */
    private static boolean peligro(Entity e) {
        if (e == null) return false;
        String a = Marcas.amenaza(e);
        if (a != null) return !"alucinacion".equals(a);
        return esMinijefe(e);
    }

    private static boolean esMinijefe(Entity e) {
        return e instanceof LivingEntity
                && "minijefe".equals(e.getPersistentDataContainer().get(CLASE_MOB, PersistentDataType.STRING));
    }

    /** Quien de verdad pega: el que dispara, si es un proyectil. */
    private static Entity autor(Entity danador) {
        if (danador instanceof Projectile pr && pr.getShooter() instanceof Entity fuente) return fuente;
        return danador;
    }

    // --------------------------------------------------------------- relojes

    /**
     * Etiquetas y llegadas con el reloj como parametro: el mismo codigo sirve para el juego
     * (System.currentTimeMillis) y para el autotest (instantes inventados, sin esperar).
     */
    static final class Relojes {

        final Map<UUID, Long> combateHasta = new HashMap<>();
        final Map<UUID, Long> llegadaHasta = new HashMap<>();

        /** @return true si no estaba en combate (entra ahora). */
        boolean etiquetar(UUID u, long ahora, int segundos) {
            boolean estaba = enCombate(u, ahora);
            combateHasta.put(u, ahora + segundos * 1000L);
            return !estaba;
        }

        boolean enCombate(UUID u, long ahora) {
            Long h = combateHasta.get(u);
            return h != null && ahora < h;
        }

        /** @return true si la etiqueta acaba de caducar (y la quita). */
        boolean acabaCombate(UUID u, long ahora) {
            Long h = combateHasta.get(u);
            if (h == null || ahora < h) return false;
            combateHasta.remove(u);
            return true;
        }

        void llegada(UUID u, long ahora, int segundos) {
            if (segundos <= 0) llegadaHasta.remove(u);
            else llegadaHasta.put(u, ahora + segundos * 1000L);
        }

        boolean protegido(UUID u, long ahora) {
            Long h = llegadaHasta.get(u);
            return h != null && ahora < h;
        }

        boolean acabaLlegada(UUID u, long ahora) {
            Long h = llegadaHasta.get(u);
            if (h == null || ahora < h) return false;
            llegadaHasta.remove(u);
            return true;
        }

        void perderLlegada(UUID u) {
            llegadaHasta.remove(u);
        }

        void olvidar(UUID u) {
            combateHasta.remove(u);
            llegadaHasta.remove(u);
        }
    }

    /** Sangre fresca cobrada por asesino y victima, para la ventana de 30 min. */
    static final class Sangre {

        private final Map<UUID, Map<String, Long>> cobros = new HashMap<>();

        boolean puede(UUID asesino, String victima, long ahora, long ventana) {
            Map<String, Long> m = cobros.get(asesino);
            Long antes = m == null ? null : m.get(victima);
            return antes == null || ahora - antes >= ventana;
        }

        void apuntar(UUID asesino, String victima, long ahora, long ventana) {
            Map<String, Long> m = cobros.computeIfAbsent(asesino, k -> new HashMap<>());
            // Lo caducado se va aqui: sin esto el mapa creceria toda la vida del servidor.
            for (Iterator<Long> it = m.values().iterator(); it.hasNext(); ) {
                if (ahora - it.next() >= ventana) it.remove();
            }
            m.put(victima, ahora);
        }
    }

    // --------------------------------------------------------------- comando

    private void comando(CommandSender quien, String[] args) {
        if (args.length < 3) {
            quien.sendMessage(ComandoCalamity.mensaje(
                    "Uso: /lw hardcore combate <info|etiquetar|llegada|cable> <jugador> [borrar]"));
            return;
        }
        String accion = args[1].toLowerCase(Locale.ROOT);
        if (accion.equals("cable")) {
            OfflinePlayer o = hc.plugin().getServer().getOfflinePlayerIfCached(args[2]);
            if (o == null) {
                quien.sendMessage(ComandoCalamity.mensaje("No encuentro a ese jugador."));
                return;
            }
            String ruta = "cable." + o.getUniqueId();
            long cuando = hc.datos().getLong(ruta, 0);
            if (args.length > 3 && args[3].equalsIgnoreCase("borrar")) {
                hc.datos().set(ruta, null);
                hc.marcarSucio();
                quien.sendMessage(ComandoCalamity.mensaje("Combat log de " + o.getName() + " borrado."));
                return;
            }
            quien.sendMessage(ComandoCalamity.mensaje(cuando <= 0 ? o.getName() + " no tiene combat log pendiente."
                    : o.getName() + " huyó por el cable hace "
                    + ((System.currentTimeMillis() - cuando) / 60_000) + " min."));
            return;
        }
        Player p = hc.plugin().getServer().getPlayerExact(args[2]);
        if (p == null) {
            quien.sendMessage(ComandoCalamity.mensaje("No encuentro a ese jugador."));
            return;
        }
        long ahora = System.currentTimeMillis();
        switch (accion) {
            case "etiquetar" -> {
                etiquetar(p);
                quien.sendMessage(ComandoCalamity.mensaje(enCombate(p) ? p.getName() + " en combate."
                        : "No se puede: fuera de Calamity o combate apagado."));
            }
            case "llegada" -> {
                llegada(p);
                quien.sendMessage(ComandoCalamity.mensaje(protegido(p) ? p.getName() + " con llegada protegida."
                        : "No se puede: combate apagado o llegada-segundos a 0."));
            }
            case "info" -> {
                Long c = relojes.combateHasta.get(p.getUniqueId());
                Long l = relojes.llegadaHasta.get(p.getUniqueId());
                Golpe g = ultimoAgresor.get(p.getUniqueId());
                quien.sendMessage(ComandoCalamity.mensaje(p.getName()
                        + " · combate " + (c == null || c <= ahora ? "no" : ((c - ahora) / 1000 + 1) + " s")
                        + " · llegada " + (l == null || l <= ahora ? "no" : ((l - ahora) / 1000 + 1) + " s")
                        + " · frenesí " + (enFrenesi.contains(p.getUniqueId()) ? "sí" : "no")
                        + " (x" + Bitacora.dec(frenesi(p, "dano-hecho")) + ")"
                        + " · último agresor " + (g == null ? "nadie" : nombre(g.agresor()))
                        + " · cable " + (hc.datos().isSet("cable." + p.getUniqueId()) ? "pendiente" : "no")));
            }
            default -> quien.sendMessage(ComandoCalamity.mensaje("Acciones: info, etiquetar, llegada, cable."));
        }
    }

    private String nombre(UUID u) {
        OfflinePlayer o = hc.plugin().getServer().getOfflinePlayer(u);
        return o.getName() == null ? u.toString() : o.getName();
    }

    private List<String> tab(String[] args) {
        if (args.length == 2) return List.of("info", "etiquetar", "llegada", "cable");
        if (args.length == 3) {
            List<String> nombres = new ArrayList<>();
            for (Player p : hc.plugin().getServer().getOnlinePlayers()) nombres.add(p.getName());
            return nombres;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("cable")) return List.of("borrar");
        return List.of();
    }

    // --------------------------------------------------------------- autotest

    /** PLAN WP4 aceptacion 3, todo en memoria con UUID sinteticos. */
    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        Relojes r = new Relojes();
        UUID a = Autotest.sintetico(41), b = Autotest.sintetico(42);
        long t0 = 1_800_000_000_000L;

        h.ok("sin etiquetar no esta en combate", !r.enCombate(a, t0));
        h.ok("etiquetar la primera vez avisa", r.etiquetar(a, t0, 15));
        h.ok("en combate a los 14,999 s", r.enCombate(a, t0 + 14_999));
        h.ok("fuera de combate a los 15 s", !r.enCombate(a, t0 + 15_000));
        h.ok("renovar en combate no vuelve a avisar", !r.etiquetar(a, t0 + 5_000, 15));
        h.ok("renovar alarga la etiqueta", r.enCombate(a, t0 + 19_999));
        h.ok("la etiqueta de uno no toca a otro", !r.enCombate(b, t0 + 1_000));
        h.ok("acabaCombate no salta antes de tiempo", !r.acabaCombate(a, t0 + 19_999));
        h.ok("acabaCombate salta una vez al caducar", r.acabaCombate(a, t0 + 20_000));
        h.ok("y solo una", !r.acabaCombate(a, t0 + 21_000));

        h.ok("DISCONNECTED con etiqueta es combat log",
                esCombatLog(true, PlayerQuitEvent.QuitReason.DISCONNECTED, true));
        h.ok("KICKED con etiqueta no es combat log",
                !esCombatLog(true, PlayerQuitEvent.QuitReason.KICKED, true));
        h.ok("TIMED_OUT con etiqueta cuenta con contar-timeout",
                esCombatLog(true, PlayerQuitEvent.QuitReason.TIMED_OUT, true));
        h.ok("TIMED_OUT no cuenta sin contar-timeout",
                !esCombatLog(true, PlayerQuitEvent.QuitReason.TIMED_OUT, false));
        h.ok("ERRONEOUS_STATE no es combat log",
                !esCombatLog(true, PlayerQuitEvent.QuitReason.ERRONEOUS_STATE, true));
        h.ok("DISCONNECTED sin etiqueta no es combat log",
                !esCombatLog(false, PlayerQuitEvent.QuitReason.DISCONNECTED, true));

        r.llegada(b, t0, 20);
        h.ok("llegada protegida a los 19,999 s", r.protegido(b, t0 + 19_999));
        h.ok("llegada acabada a los 20 s", !r.protegido(b, t0 + 20_000));
        h.ok("acabaLlegada salta al caducar (P-C04)", r.acabaLlegada(b, t0 + 20_000));
        r.llegada(b, t0, 20);
        r.perderLlegada(b);
        h.ok("pegar a un jugador quita la llegada", !r.protegido(b, t0 + 1_000));
        r.llegada(b, t0, 0);
        h.ok("llegada-segundos 0 no protege", !r.protegido(b, t0));

        h.cerca("frenesi con cordura 24 y umbral 25", 1.15, factorFrenesi(24, 25, 0.15), 1e-9);
        h.cerca("sin frenesi con cordura 25", 1.0, factorFrenesi(25, 25, 0.15), 1e-9);
        h.cerca("sin frenesi con cordura 100", 1.0, factorFrenesi(100, 25, 0.15), 1e-9);
        h.cerca("PvP: base x frenesi(a) x frenesi(v) x eclipse", 1.15 * 1.15 * 1.25,
                factorPvp(1.15, 1.15, 1.25), 1e-9);
        h.cerca("PvP sin nada es x1", 1.0, factorPvp(1, 1, 1), 1e-9);

        h.ok("victima valida: 5 min y 2 piezas", victimaValida(300, 2, false, 5, 2));
        h.ok("victima valida: 5 min y un arma", victimaValida(300, 0, true, 5, 2));
        h.ok("victima no valida: desnuda", !victimaValida(3600, 1, false, 5, 2));
        h.ok("victima no valida: 4 min 59 s", !victimaValida(299, 4, true, 5, 2));
        h.ok("una espada es arma", esArma(Material.NETHERITE_SWORD));
        h.ok("un hacha es arma", esArma(Material.IRON_AXE));
        h.ok("un pico no es arma", !esArma(Material.DIAMOND_PICKAXE));

        Sangre s = new Sangre();
        long media = 30 * 60_000L;
        h.ok("sangre: primera vez por esa victima", s.puede(a, "v1", t0, media));
        s.apuntar(a, "v1", t0, media);
        h.ok("sangre: la misma victima a los 29 min no", !s.puede(a, "v1", t0 + 29 * 60_000L, media));
        h.ok("sangre: otra victima si", s.puede(a, "v2", t0 + 60_000L, media));
        h.ok("sangre: otro asesino si", s.puede(b, "v1", t0 + 60_000L, media));
        h.ok("sangre: la misma victima a los 30 min si", s.puede(a, "v1", t0 + media, media));

        h.igual("config: etiqueta de 15 s", 15, hc.cfg().getInt("combate.etiqueta-segundos", 15));
        h.igual("config: llegada de 20 s", 20, hc.cfg().getInt("combate.llegada-segundos", 20));
        h.ok("autotest no deja relojes de UUID sinteticos en el modulo",
                !relojes.combateHasta.containsKey(a) && !relojes.llegadaHasta.containsKey(b));
        h.ok("autotest no escribe cable en hardcore-datos.yml", !hc.datos().isSet("cable." + a));
        h.ok("/lw hardcore combate registrado", Subcomandos.lw().nombres(null).contains("combate"));
        return h.lineas();
    }
}
