package net.ederus.calamity.hardcore;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.ederus.calamity.MobsLethal;
import net.ederus.edm.anomaly.minions.MinionManager;
import net.ederus.edm.comun.Compat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calamity 1.10 · El Reclamo: un cuerno que llama, cuando el jugador quiera, al minijefe del bioma
 * donde esta (hardcore.minijefes.por-bioma). Hasta ahora a un minijefe solo se le veia con la cordura
 * a cero, y el que buscaba un Sello concreto tenia que volverse loco en el bioma bueno y esperar.
 *
 * Antes de gastar nada se mira, en este orden (motivo), y si algo falla se dice por que:
 *  - que este en Calamity (un mundo hardcore) y fuera de su zona spawn;
 *  - que el bioma tenga minijefe (Minijefes.delBioma);
 *  - Ley 6: con la Parca detras no viene nadie; y tampoco si ya tiene un minijefe vivo detras;
 *  - el descanso del minijefe de cordura cero (Hardcore.minutosMinijefe desde Estado.ultimoMinijefe),
 *    que es compartido: el Reclamo lo pide y lo apunta. Ademas el Reclamo guarda su ultima llamada en
 *    hardcore-datos (reclamo.ultimo): el Estado se olvida al salir de Calamity y al desconectarse, y
 *    sin esto salir y volver a entrar era un Reclamo detras de otro;
 *  - el tope del dia por jugador (minijefes.reclamo.tope-dia, el dia de hardcore.zona).
 * Si pasa, se gasta uno y a los minijefes.reclamo.segundos (3) llega por la misma ruta que el de
 * cordura cero (Hardcore.traerMinijefe). En esa espera se mira cada segundo que siga conectado, vivo,
 * en el mismo mundo y fuera del spawn; si no, o si no encuentra sitio, o si entretanto le ha llegado
 * la Parca u otro minijefe, se le devuelve el Reclamo: a la mano si puede cogerlo, y si no
 * (desconectado, muerto) a sus premios pendientes (Entregas.devolver). Solo cuenta para el tope y el
 * descanso el Reclamo que trae minijefe de verdad.
 *
 * El uso vanilla del cuerno se cancela, y ademas el Reclamo nace sin instrumento
 * (ItemsCalamity.reclamo): ni suena dos veces ni le pone el enfriamiento del cuerno. Se usa con clic
 * derecho al aire o a un bloque (desde Bedrock llega por cualquiera de los dos) y en la mano
 * principal. Nace y se para con Minijefes.
 */
final class Reclamo implements Listener {

    /** Un clic cada medio segundo como mucho: Bedrock repite el uso mientras se mantiene pulsado. */
    private static final long ESPERA_MS = 500;
    /** Donde se apunta en hardcore-datos lo gastado hoy y la ultima llamada que trajo minijefe. */
    static final String RUTA_DIA = "reclamo.dia", RUTA_ULTIMO = "reclamo.ultimo";
    /** El cuerno "Call" (llamada) del vanilla: el que suena al usarlo. */
    private static final String SONIDO = "item.goat_horn.sound.5";

    /** Un Reclamo que esta sonando: lo gastado (para devolverlo), donde sono y cuanto le queda. */
    private static final class Llamada {
        final Player p;
        final String tipo;
        final String bioma;
        final ItemStack gastado;
        final World mundo;
        int quedan;
        BukkitTask tarea;

        Llamada(Player p, String tipo, String bioma, ItemStack gastado, int segundos) {
            this.p = p;
            this.tipo = tipo;
            this.bioma = bioma;
            this.gastado = gastado;
            this.mundo = p.getWorld();
            this.quedan = segundos;
        }
    }

    private final Hardcore hc;
    private final Map<UUID, Llamada> sonando = new HashMap<>();
    private final Map<UUID, Long> ultimoClic = new HashMap<>();
    /** La telemetria de los rechazos, una por jugador y motivo cada 30 s: el que insiste no llena el fichero. */
    private final Map<String, Long> ultimoRechazo = new HashMap<>();

    Reclamo(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("reclamo", this::autotest);
    }

    /** Al parar: lo que estuviera sonando se devuelve (Entregas aun esta en pie: se para despues). */
    void parar() {
        HandlerList.unregisterAll(this);
        for (Llamada ll : new ArrayList<>(sonando.values())) {
            terminar(ll);
            devolver(ll, "parada", false);
        }
        sonando.clear();
        ultimoClic.clear();
        ultimoRechazo.clear();
    }

    private boolean activo() {
        return hc.cfg().getBoolean("minijefes.reclamo.activo", true);
    }

    private int topeDia() {
        return hc.cfg().getInt("minijefes.reclamo.tope-dia", 6);
    }

    private int segundos() {
        return Math.max(0, Math.min(30, hc.cfg().getInt("minijefes.reclamo.segundos", 3)));
    }

    private Calendario calendario() {
        return hc.calendario() != null ? hc.calendario() : new Calendario(hc);
    }

    // ------------------------------------------------------------------ reglas (puras)

    /**
     * Por que no responde ahora el Reclamo, o null si responde. Sin Bukkit: el autotest lo prueba con
     * numeros. ultimo: millis del ultimo minijefe (0 = ninguno). topeDia 0 o menos = sin tope.
     */
    static String motivo(boolean activo, boolean enSpawn, String tipo, boolean parca, boolean conMinijefe,
                         long ultimo, long ahora, int cadaMinutos, int usadosHoy, int topeDia) {
        if (!activo) return "apagado";
        if (enSpawn) return "spawn";
        if (tipo == null) return "bioma";
        if (parca) return "parca";
        if (conMinijefe) return "presa";
        if (faltanMinutos(ultimo, ahora, cadaMinutos) > 0) return "descanso";
        if (topeDia > 0 && usadosHoy >= topeDia) return "tope";
        return null;
    }

    /** Minutos de descanso que le quedan, redondeando hacia arriba (con 30 s, 1). 0 si ya puede. */
    static long faltanMinutos(long ultimo, long ahora, int cadaMinutos) {
        if (ultimo <= 0 || cadaMinutos <= 0) return 0;
        long queda = ultimo + cadaMinutos * 60_000L - ahora;
        return queda <= 0 ? 0 : (queda + 59_999L) / 60_000L;
    }

    /** Lo que se le dice por cada motivo de motivo(). */
    static String aviso(String motivo, long faltan, int tope) {
        return switch (motivo == null ? "" : motivo) {
            case "apagado" -> "El Reclamo está desactivado ahora mismo.";
            case "spawn" -> "Aquí no responde nadie. Aléjate del spawn.";
            case "bioma" -> "En este bioma no vive ningún minijefe.";
            case "parca" -> "Con la Parca detrás de ti, ningún minijefe responde.";
            case "presa" -> "Ya tienes un minijefe detrás. Acaba con él primero.";
            case "descanso" -> "Aún es pronto para otro minijefe: faltan " + faltan + " min.";
            case "tope" -> "Hoy ya has hecho sonar " + tope + (tope == 1 ? " Reclamo" : " Reclamos")
                    + ". Mañana podrás volver a usarlo.";
            default -> "Ahora no responde nadie.";
        };
    }

    // ------------------------------------------------------------------ uso

    /**
     * Clic derecho con un Reclamo. Sin ignoreCancelled: un clic al aire llega ya cancelado (no hay
     * bloque que usar) y es justo el que manda Bedrock al usar un objeto.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUsar(PlayerInteractEvent e) {
        if (!e.getAction().isRightClick()) return;
        if (!ItemsCalamity.esReclamo(e.getItem())) return;
        // El cuerno vanilla no suena ni pone su enfriamiento, con la mano que sea.
        e.setUseItemInHand(Event.Result.DENY);
        Player p = e.getPlayer();
        if (e.getHand() != EquipmentSlot.HAND) {
            if (aTiempo(p)) decir(p, "Lleva el Reclamo en la mano principal para hacerlo sonar.");
            return;
        }
        // Como el Frasco y el Cristal: con el Reclamo en la mano el clic es suyo, el bloque no se usa.
        e.setUseInteractedBlock(Event.Result.DENY);
        if (!aTiempo(p)) return;
        hc.seguro("reclamo", () -> usar(p));
    }

    private boolean aTiempo(Player p) {
        long ahora = System.currentTimeMillis();
        Long antes = ultimoClic.get(p.getUniqueId());
        if (antes != null && ahora - antes < ESPERA_MS) return false;
        ultimoClic.put(p.getUniqueId(), ahora);
        return true;
    }

    /** Mira las reglas y, si responde, gasta uno y empieza la cuenta. */
    private void usar(Player p) {
        if (!hc.esHardcore(p)) {
            p.sendMessage(Component.text("El Reclamo solo funciona en Calamity.", Paleta.TEXTO));
            return;
        }
        ItemStack mano = p.getInventory().getItemInMainHand();
        if (!ItemsCalamity.esReclamo(mano)) return;
        // Ligado (M30): en manos de otro no sirve.
        UUID dueno = Ligado.duenoDe(mano);
        if (dueno != null && !dueno.equals(p.getUniqueId())) {
            p.sendMessage(ComandoCalamity.mensaje(Component.text("Ese Reclamo está ligado a ")
                    .append(Component.text(Saldo.nombre(dueno), Paleta.DETALLE))
                    .append(Component.text(": a ti no te sirve."))));
            return;
        }
        if (sonando.containsKey(p.getUniqueId())) {
            decir(p, "Tu Reclamo ya está sonando.");
            return;
        }
        String bioma = Minijefes.bioma(p.getLocation());
        String tipo = Minijefes.delBioma(bioma, Minijefes.porBioma(hc.plugin().getConfig()),
                hc.cfg().getStringList("minijefes.tipos"));
        long ahora = System.currentTimeMillis();
        long ultimo = ultimoMinijefe(p);
        int cada = hc.minutosMinijefe();
        int usados = usadosHoy(p.getUniqueId(), calendario().dia(ahora));
        int tope = topeDia();
        String motivo = motivo(activo(), hc.enSpawn(p), tipo, conParca(p), hc.tieneMinijefe(p), ultimo, ahora, cada,
                usados, tope);
        if (motivo != null) {
            decir(p, aviso(motivo, faltanMinutos(ultimo, ahora, cada), tope));
            Marco.sonidoNo(p);
            rechazo(p, motivo, tipo, bioma);
            return;
        }

        // Se gasta uno ya; si al final no viene nadie, se devuelve tal cual.
        ItemStack uno = mano.clone();
        uno.setAmount(1);
        if (mano.getAmount() > 1) {
            mano.setAmount(mano.getAmount() - 1);
            p.getInventory().setItemInMainHand(mano);
        } else {
            p.getInventory().setItemInMainHand(null);
        }
        Llamada ll = new Llamada(p, tipo, bioma, uno, segundos());
        sonando.put(p.getUniqueId(), ll);
        try {
            Compat.soundPlayers(p.getWorld(), p.getLocation(), SONIDO, 4.0f, 0.9f);
            p.sendMessage(Paleta.minijefe(Minijefes.nombre(tipo), 0)
                    .append(Component.text(" ha oído tu Reclamo.", Paleta.AVISO)));
            hc.plugin().bitacora().anotar("reclamo", "suena", p.getName(), tipo, clave(bioma), "hoy " + usados + "/" + tope);
            if (ll.quedan <= 0) {
                terminar(ll);
                llegar(ll);
                return;
            }
            ll.tarea = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(),
                    () -> hc.seguro("reclamo", () -> latido(ll)), 20L, 20L);
        } catch (RuntimeException fallo) {
            // Ya esta gastado: pase lo que pase, no se pierde.
            if (sonando.get(p.getUniqueId()) == ll) {
                terminar(ll);
                devolver(ll, "fallo", true);
            }
            throw fallo;
        }
    }

    private boolean conParca(Player p) {
        Parca parca = hc.parca();
        return parca != null && hc.valor("parca", () -> parca.persigue(p), false);
    }

    /** El ultimo minijefe de ese jugador: el del Estado (cordura cero o Reclamo) o el ultimo Reclamo guardado. */
    private long ultimoMinijefe(Player p) {
        long enMemoria = hc.cordura().estado(p).ultimoMinijefe;
        return Math.max(enMemoria, hc.datos().getLong(RUTA_ULTIMO + "." + p.getUniqueId(), 0));
    }

    int usadosHoy(UUID u, String dia) {
        return hc.datos().getInt(RUTA_DIA + "." + dia + "." + u, 0);
    }

    /** Uno mas hoy y la hora de esta llamada. De paso se borran los dias pasados y las horas de hace mas de un dia. */
    private int apuntar(UUID u, long ahora) {
        YamlConfiguration d = hc.datos();
        String dia = calendario().dia(ahora);
        ConfigurationSection dias = d.getConfigurationSection(RUTA_DIA);
        if (dias != null) for (String k : dias.getKeys(false)) if (!k.equals(dia)) dias.set(k, null);
        ConfigurationSection ultimos = d.getConfigurationSection(RUTA_ULTIMO);
        if (ultimos != null) {
            for (String k : ultimos.getKeys(false)) if (ahora - ultimos.getLong(k, 0) > 86_400_000L) ultimos.set(k, null);
        }
        int usados = usadosHoy(u, dia) + 1;
        d.set(RUTA_DIA + "." + dia + "." + u, usados);
        d.set(RUTA_ULTIMO + "." + u, ahora);
        return usados;
    }

    // ------------------------------------------------------------------ la espera

    /** Una vez por segundo mientras suena: si ya no puede venir, se devuelve; al acabar la cuenta, llega. */
    private void latido(Llamada ll) {
        if (sonando.get(ll.p.getUniqueId()) != ll) {
            if (ll.tarea != null) ll.tarea.cancel();
            return;
        }
        String fuera = fuera(ll);
        if (fuera != null) {
            terminar(ll);
            devolver(ll, fuera, true);
            return;
        }
        if (--ll.quedan > 0) return;
        terminar(ll);
        llegar(ll);
    }

    /** Por que ya no puede venir por el: desconectado, muerto, fuera del mundo o en el spawn. Null si sigue. */
    private String fuera(Llamada ll) {
        Player p = ll.p;
        if (!p.isOnline()) return "desconecta";
        if (p.isDead()) return "muere";
        if (p.getWorld() != ll.mundo || !hc.esHardcore(p)) return "sale";
        if (hc.enSpawn(p)) return "spawn";
        return null;
    }

    private void terminar(Llamada ll) {
        if (ll.tarea != null) ll.tarea.cancel();
        sonando.remove(ll.p.getUniqueId(), ll);
    }

    /**
     * Acaba la cuenta: se vuelve a mirar lo que ha podido cambiar en estos segundos (la Parca, otro
     * minijefe) y se trae al minijefe por la ruta de siempre. Si no encuentra sitio, se devuelve.
     */
    private void llegar(Llamada ll) {
        Player p = ll.p;
        String fuera = fuera(ll);
        if (fuera != null) {
            devolver(ll, fuera, true);
            return;
        }
        if (conParca(p)) {
            devolver(ll, "parca", true);
            return;
        }
        if (hc.tieneMinijefe(p)) {
            devolver(ll, "presa", true);
            return;
        }
        // Protegido: si invocar revienta (EDM), el Reclamo ya gastado vuelve igual que sin sitio.
        LivingEntity mob = hc.valor("reclamo", () -> hc.traerMinijefe(p, ll.tipo, hc.cordura().estado(p)), null);
        if (mob == null) {
            devolver(ll, "sin-sitio", true);
            return;
        }
        long ahora = System.currentTimeMillis();
        int usados = apuntar(p.getUniqueId(), ahora);
        hc.guardarYa();
        int nivel = nivel(mob);
        hc.plugin().bitacora().anotar("reclamo", "invocado", p.getName(), ll.tipo, clave(ll.bioma), "N " + nivel,
                "hoy " + usados + "/" + topeDia());
        telemetria(p, ll.tipo, ll.bioma, "invocado", null, usados, nivel);
    }

    private int nivel(LivingEntity mob) {
        MobsLethal mobs = hc.plugin().mobs();
        MinionManager mm = mobs == null ? null : mobs.minionManager();
        return mm == null ? 0 : hc.valor("reclamo", () -> mm.levelOf(mob), 0);
    }

    /**
     * Le devuelve el Reclamo gastado: a la mano (o a sus pies) si esta conectado y vivo; si no, o si se
     * esta desconectando, a sus premios pendientes (Entregas.devolver, que ademas le dice cuando lo
     * recibira), que se entregan al salir de Calamity o al volver a entrar al servidor.
     */
    private void devolver(Llamada ll, String motivo, boolean avisar) {
        Player p = ll.p;
        String porQue = switch (motivo) {
            case "sin-sitio" -> "No ha encontrado por dónde llegar hasta ti";
            case "spawn" -> "En el spawn no responde nadie";
            case "sale" -> "Has salido de Calamity antes de que llegara";
            case "parca" -> "Con la Parca detrás de ti, no viene";
            case "presa" -> "Ya tienes un minijefe detrás";
            case "muere" -> "Has muerto antes de que llegara";
            default -> "No ha venido nadie";
        };
        String donde;
        if (p.isOnline() && !p.isDead() && !"desconecta".equals(motivo)) {
            boolean suelo = Suelo.dar(hc.plugin(), p, ll.gastado.clone());
            donde = suelo ? "suelo" : "inventario";
            if (avisar) {
                p.sendMessage(ComandoCalamity.mensaje(porQue + (suelo ? ": recuperas el Reclamo, a tus pies (no te cabía)."
                        : ": recuperas el Reclamo.")));
            }
        } else {
            // Primero el porque; luego Entregas dice cuando lo recibira.
            if (avisar && p.isOnline()) p.sendMessage(ComandoCalamity.mensaje(porQue + "."));
            Entregas ent = hc.entregas();
            if (ent != null) {
                ent.devolver(p, List.of(ll.gastado.clone()), "devolucion:reclamo");
                donde = "pendiente";
            } else {
                donde = "perdido";
            }
        }
        hc.plugin().bitacora().anotar("reclamo", "devuelto", p.getName(), ll.tipo, motivo, donde);
        telemetria(p, ll.tipo, ll.bioma, "devuelto", motivo, -1, 0);
    }

    /** Quien se va con un Reclamo sonando lo recupera en sus pendientes (se va: no se le invoca nada). */
    @EventHandler
    public void onSalir(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        ultimoClic.remove(u);
        Llamada ll = sonando.get(u);
        if (ll == null) return;
        terminar(ll);
        hc.seguro("reclamo", () -> devolver(ll, "desconecta", false));
    }

    // ------------------------------------------------------------------ avisos y registro

    /** En la barra de accion si esta dentro y cuenta (alli se pinta la cordura); si no, al chat. */
    private void decir(Player p, String texto) {
        if (hc.esHardcore(p) && hc.cuenta(p)) hc.cordura().destello(p, Component.text(texto, Paleta.AVISO), 3);
        else p.sendMessage(ComandoCalamity.mensaje(texto));
    }

    private static String clave(String bioma) {
        return bioma == null || bioma.isBlank() ? "" : Clima.clave(bioma);
    }

    private void rechazo(Player p, String motivo, String tipo, String bioma) {
        String k = p.getUniqueId() + "|" + motivo;
        long ahora = System.currentTimeMillis();
        Long antes = ultimoRechazo.get(k);
        if (antes != null && ahora - antes < 30_000L) return;
        if (ultimoRechazo.size() > 512) ultimoRechazo.values().removeIf(t -> ahora - t >= 30_000L);
        ultimoRechazo.put(k, ahora);
        telemetria(p, tipo, bioma, "rechazado", motivo, -1, 0);
    }

    /** Suceso "reclamo": tipo, bioma, resultado (invocado, devuelto o rechazado) y el porque. */
    private void telemetria(Player p, String tipo, String bioma, String resultado, String motivo, int hoy, int nivel) {
        Telemetria te = hc.telemetria();
        if (te == null) return;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("tipo", tipo == null ? "" : tipo);
        c.put("bioma", clave(bioma));
        c.put("resultado", resultado);
        if (motivo != null) c.put("motivo", motivo);
        if (hoy >= 0) c.put("hoy", hoy);
        if (nivel > 0) c.put("nivel", nivel);
        hc.seguro("telemetria", () -> te.suceso("reclamo", p, c));
    }

    // ------------------------------------------------------------------ autotest

    /** /calamidad autotest reclamo: las reglas (descanso, tope, orden) y el objeto, sin jugadores. */
    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        long ahora = 1_790_000_000_000L;
        String t = "custodio-de-las-ruinas";
        h.igual("con todo en regla responde", null, motivo(true, false, t, false, false, 0, ahora, 10, 0, 6));
        h.igual("apagado (reclamo.activo: false)", "apagado", motivo(false, false, t, false, false, 0, ahora, 10, 0, 6));
        h.igual("en el spawn no responde nadie", "spawn", motivo(true, true, t, false, false, 0, ahora, 10, 0, 6));
        h.igual("en un bioma sin minijefe", "bioma", motivo(true, false, null, false, false, 0, ahora, 10, 0, 6));
        h.igual("Ley 6: con la Parca detras", "parca", motivo(true, false, t, true, false, 0, ahora, 10, 0, 6));
        h.igual("con un minijefe vivo detras", "presa", motivo(true, false, t, false, true, 0, ahora, 10, 0, 6));
        h.igual("descanso: hace 3 min, con 10", "descanso", motivo(true, false, t, false, false, ahora - 180_000L, ahora, 10, 0, 6));
        h.igual("descanso: faltan 7 min", 7L, faltanMinutos(ahora - 180_000L, ahora, 10));
        h.igual("descanso: con 30 s de sobra se dice 1 min", 1L, faltanMinutos(ahora - 570_000L, ahora, 10));
        h.igual("descanso cumplido a los 10 min", null, motivo(true, false, t, false, false, ahora - 600_000L, ahora, 10, 0, 6));
        h.igual("sin minijefe antes no hay descanso", 0L, faltanMinutos(0, ahora, 10));
        h.igual("cada-minutos 0: sin descanso", 0L, faltanMinutos(ahora - 1_000L, ahora, 0));
        h.igual("tope: 6 de 6 hoy", "tope", motivo(true, false, t, false, false, 0, ahora, 10, 6, 6));
        h.igual("tope: con 5 de 6 todavia responde", null, motivo(true, false, t, false, false, 0, ahora, 10, 5, 6));
        h.igual("tope-dia 0: sin tope", null, motivo(true, false, t, false, false, 0, ahora, 10, 99, 0));
        h.igual("el spawn se dice antes que todo lo demas", "spawn", motivo(true, true, null, true, true, ahora, ahora, 10, 9, 6));
        h.igual("el descanso se dice antes que el tope", "descanso", motivo(true, false, t, false, false, ahora, ahora, 10, 9, 6));
        h.ok("aviso del descanso con sus minutos", aviso("descanso", 7, 6).contains("faltan 7 min"));
        h.ok("aviso del tope con el tope", aviso("tope", 0, 6).contains("6 Reclamos"));
        h.igual("aviso del spawn", "Aquí no responde nadie. Aléjate del spawn.", aviso("spawn", 0, 6));
        h.igual("aviso sin minijefe", "En este bioma no vive ningún minijefe.", aviso("bioma", 0, 6));

        ItemStack r = ItemsCalamity.reclamo();
        h.ok("el Reclamo lleva su marca", ItemsCalamity.esReclamo(r));
        h.igual("el Reclamo es un cuerno de cabra", Material.GOAT_HORN, r.getType());
        h.ok("el Reclamo no lleva instrumento (el cuerno vanilla no suena)", !r.hasData(DataComponentTypes.INSTRUMENT));
        h.igual("se llama Reclamo", "Reclamo", Hardcore.plano(r.getItemMeta().displayName()));
        h.ok("nombre sin cursiva", r.getItemMeta().displayName().decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE);
        h.ok("un cuerno cualquiera no es un Reclamo", !ItemsCalamity.esReclamo(new ItemStack(Material.GOAT_HORN)));
        h.ok("Entregas sabe dar reclamo", Entregas.OBJETOS.contains("reclamo"));
        Entregas ent = hc.entregas();
        if (ent != null) {
            ItemStack l = ent.ligar(ent.crear("reclamo"), Autotest.sintetico(611));
            h.ok("ligado sigue siendo un Reclamo, sin instrumento", ItemsCalamity.esReclamo(l)
                    && !l.hasData(DataComponentTypes.INSTRUMENT));
            ItemStack vuelta = Entregas.deTexto(Entregas.aTexto(l));
            h.ok("en premios pendientes vuelve igual", vuelta != null && vuelta.isSimilar(l)
                    && !vuelta.hasData(DataComponentTypes.INSTRUMENT));
        }
        h.ok("la prueba no toca hardcore-datos.yml", !hc.datos().isSet(RUTA_ULTIMO + "." + Autotest.sintetico(611)));
        return h.lineas();
    }
}
