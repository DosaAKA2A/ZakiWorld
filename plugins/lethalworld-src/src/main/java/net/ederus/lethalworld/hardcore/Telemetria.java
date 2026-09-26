package net.ederus.lethalworld.hardcore;

import net.ederus.lethalworld.MobsLethal;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * M10 · Telemetria: una linea JSON por suceso en plugins/LethalWorld/telemetria/AAAA-MM.jsonl.
 *
 * Es el instrumento del plan de recompensas (MED sec. 3): que se arriesga, que se saca, que
 * se pierde, que se quiere comprar y no se puede. La Bitacora es para personas; esto es
 * para el analizador de Python, asi que el formato es estable y lleva version (v).
 *
 * Por que una cola y un hilo propios: escribir a disco en el hilo principal en cada pago o
 * cada muerte es justo el tipo de pausa que se nota con veinte jugadores dentro. Los datos
 * se leen en el hilo principal (inventario, saldo, cordura: no se pueden leer desde otro) y
 * se encolan ya como texto; el hilo solo abre el fichero del mes y apunta. parar() espera a
 * que la cola se vacie: un reinicio no puede perder los sucesos de su ultimo segundo.
 *
 * Campos comunes de todas las lineas (MED sec. 3.2): t (ISO con zona de hardcore.zona), ev,
 * uuid, nombre, mundo, v. Los campos propios de cada suceso los pone quien lo emite; los de
 * entra, sale y muere los construye esta clase pidiendoselos a los servicios.
 *
 * suceso() no lanza nunca: un fallo de la telemetria no puede romper un pago ni una muerte.
 */
final class Telemetria implements Listener {

    /** Version del esquema de las lineas. Si cambia el significado de un campo, sube. */
    static final int VERSION = 1;

    private static final Set<String> COMUNES = Set.of("t", "ev", "uuid", "nombre", "mundo", "v");
    private static final Pattern FICHERO = Pattern.compile("(\\d{4}-\\d{2})\\.jsonl");
    /** La marca de clase de los mobs de Lethal World (comun, destacado, minijefe, estructura). */
    private static final NamespacedKey CLASE_MOB = new NamespacedKey("edm", "lethal_world_mob");

    private record Linea(String mes, String texto) {
    }

    private record Barrera(CountDownLatch hecho) {
    }

    private static final Object FIN = new Object();

    /** Lo que lleva hecho cada uno en la expedicion en curso (para sale). Solo hilo principal. */
    private static final class Sesion {
        final long inicio;
        int mobs, destacados, minijefes, cofres;

        Sesion(long inicio) {
            this.inicio = inicio;
        }
    }

    private final Hardcore hc;
    private final File carpeta;
    private final LinkedBlockingQueue<Object> cola = new LinkedBlockingQueue<>();
    private final Thread hilo;
    private final AtomicLong escritas = new AtomicLong();
    private final AtomicLong fallos = new AtomicLong();
    private final Map<UUID, Sesion> sesiones = new HashMap<>();
    private final StatsTelemetria stats;

    /* Estado del hilo escritor: solo lo toca el hilo (y parar() cuando el hilo ya murio). */
    private BufferedWriter escritor;
    private String mesAbierto;

    private volatile boolean avisado;
    private volatile boolean parado;
    private volatile String ultimoMes = "";

    Telemetria(Hardcore hc) {
        this.hc = hc;
        this.carpeta = new File(hc.plugin().getDataFolder(), "telemetria");
        int dias = Math.max(1, hc.cfg().getInt("telemetria.dias-a-conservar", 180));
        this.hilo = new Thread(() -> bucle(dias), "LethalWorld-telemetria");
        // Daemon no: la JVM no puede cortar el hilo con lineas a medio escribir.
        hilo.setDaemon(false);
        hilo.start();
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        this.stats = new StatsTelemetria(hc, carpeta);

        Autotest.registrar("telemetria", this::autotest);
        Autotest.registrar("censo", Telemetria::autotestCenso);
        Subcomandos.lw().registrar("telemetria", "telemetria: estado de la cola y fichero del mes", "ederus.mundos",
                (quien, args) -> estado(quien), null);
    }

    boolean activa() {
        return hc.cfg().getBoolean("telemetria.activa", true);
    }

    // ------------------------------------------------------------------ API

    /**
     * Apunta un suceso. Campos comunes aqui; campos propios en el mapa (sus claves no pueden
     * pisar t, ev, uuid, nombre, mundo ni v). quien puede ser null (suceso sin jugador).
     */
    void suceso(String ev, OfflinePlayer quien, Map<String, Object> campos) {
        try {
            if (!activa() || ev == null) return;
            UUID u = quien == null ? null : quien.getUniqueId();
            String nombre = quien == null ? null : quien.getName();
            Player p = quien == null ? null : quien.getPlayer();
            String mundo = p == null ? "" : p.getWorld().getKey().getKey();
            encolar(ev, u, nombre, mundo, campos);
        } catch (Throwable t) {
            avisar("no se pudo apuntar el suceso " + ev, t);
        }
    }

    /** Al cruzar la puerta de entrada (Hardcore.meter, ya dentro y con la cordura llena). */
    void entra(Player p) {
        sesiones.put(p.getUniqueId(), new Sesion(System.currentTimeMillis()));
        Map<String, Object> c = new LinkedHashMap<>();
        MobsLethal mobs = hc.plugin().mobs();
        c.put("rango", mobs == null ? 0 : hc.valor("telemetria", () -> mobs.rango(p), 0));
        c.put("poder", mobs == null ? 0 : hc.valor("telemetria", () -> mobs.poder(p), 0));
        c.put("N", nivel(p));
        c.put("censo", Censo.de(p).json());
        c.put("esencias_saldo", saldo(p));
        Encima e = encima(p);
        c.put("esencias_encima", e.esencias);
        c.put("reliquias", e.reliquias);
        c.put("frascos", e.frascos);
        c.put("cristales", e.cristales);
        // El Tributo de entrada es P2: se deja el campo a 0 para que el esquema no cambie el dia que llegue.
        c.put("tributo", 0);
        suceso("entra", p, c);
    }

    /**
     * Al extraer (lo llama la Tasacion, antes del teleport). tasado: lo que pago la tasacion;
     * las claves "1".."4" (o un mapa "tasado") son Reliquias por grado y van al objeto
     * tasado; las demas (esencias, mc...) salen tal cual como campos del suceso.
     */
    void sale(Player p, String motivo, Map<String, Object> tasado) {
        Sesion s = sesiones.remove(p.getUniqueId());
        Censo.Foto salida = Censo.de(p);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("motivo", motivo == null ? "" : motivo);
        c.put("minutos", minutos(p, s));
        Racha r = hc.racha();
        c.put("racha", r == null ? 0 : hc.valor("racha", () -> r.de(p.getUniqueId()), 0));
        c.put("racha_tope", rachaTope(salida));
        Map<String, Object> porGrado = new LinkedHashMap<>();
        for (int g = 1; g <= 4; g++) porGrado.put(String.valueOf(g), 0L);
        Map<String, Object> resto = new LinkedHashMap<>();
        if (tasado != null) {
            for (Map.Entry<String, Object> e : tasado.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (k.length() == 1 && k.charAt(0) >= '1' && k.charAt(0) <= '4') {
                    porGrado.put(k, e.getValue());
                } else if (k.equals("tasado") && e.getValue() instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> g : m.entrySet()) porGrado.put(String.valueOf(g.getKey()), g.getValue());
                } else if (!COMUNES.contains(k) && !c.containsKey(k)) {
                    resto.put(k, e.getValue());
                }
            }
        }
        c.put("tasado", porGrado);
        c.put("esencias", resto.getOrDefault("esencias", 0));
        c.put("mc", resto.getOrDefault("mc", 0));
        for (Map.Entry<String, Object> e : resto.entrySet()) c.putIfAbsent(e.getKey(), e.getValue());
        c.put("mobs", s == null ? 0 : s.mobs);
        c.put("destacados", s == null ? 0 : s.destacados);
        c.put("minijefes", s == null ? 0 : s.minijefes);
        c.put("cofres", s == null ? 0 : s.cofres);
        c.put("censo_salida", salida.json());
        suceso("sale", p, c);
    }

    /** En onMuerte, antes de borrar el inventario y de reiniciar la cordura. */
    void muere(Player p, FotoMuerte foto) {
        Sesion s = sesiones.remove(p.getUniqueId());
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("causa", causa(p));
        c.put("minutos", minutos(p, s));
        c.put("cordura", Math.round(hc.cordura().valor(p)));
        c.put("N", nivel(p));
        Censo.Foto f = foto == null ? null : foto.censo();
        c.put("censo", (f != null ? f : Censo.de(p)).json());
        Encima e = encima(p);
        c.put("reliquias", e.reliquias);
        c.put("esencias_encima", e.esencias);
        c.put("mobs", s == null ? 0 : s.mobs);
        suceso("muere", p, c);
    }

    void parar() {
        HandlerList.unregisterAll(this);
        stats.parar();
        cola.offer(FIN);
        try {
            hilo.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (hilo.isAlive()) {
            hc.plugin().getLogger().warning("[Calamity] La telemetria no termino de escribir en 10 s; quedan "
                    + cola.size() + " sucesos en cola.");
        } else {
            // Lo que llego despues del FIN (otro modulo parando): se escribe aqui, ya sin hilo.
            List<Object> resto = new ArrayList<>();
            cola.drainTo(resto);
            escribirLote(resto);
            cerrarEscritor();
        }
        parado = true;
        sesiones.clear();
    }

    // -------------------------------------------------------- cola y fichero

    /** Encola una linea ya montada. Devuelve el mes del fichero al que va (AAAA-MM). */
    private String encolar(String ev, UUID uuid, String nombre, String mundo, Map<String, Object> campos) {
        ZonedDateTime ahora = ZonedDateTime.now(zona()).truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", ahora.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        m.put("ev", ev);
        m.put("uuid", uuid == null ? "" : uuid.toString());
        m.put("nombre", nombre == null ? "" : nombre);
        m.put("mundo", mundo == null ? "" : mundo);
        m.put("v", VERSION);
        if (campos != null) {
            for (Map.Entry<String, Object> e : campos.entrySet()) {
                if (e.getKey() != null && !COMUNES.contains(e.getKey())) m.put(e.getKey(), e.getValue());
            }
        }
        String mes = YearMonth.from(ahora).toString();
        ultimoMes = mes;
        String linea = Jsonl.escribir(m);
        if (parado || !hilo.isAlive()) {
            // Sin hilo (apagado o muerto por un error raro) se escribe aqui mismo: es una linea.
            escribirLote(List.of(new Linea(mes, linea)));
            cerrarEscritor();
        } else {
            cola.offer(new Linea(mes, linea));
        }
        return mes;
    }

    /** Espera a que lo encolado hasta ahora este en disco. False si no le da tiempo. */
    boolean vaciar(long milis) {
        if (parado || !hilo.isAlive()) return cola.isEmpty();
        CountDownLatch l = new CountDownLatch(1);
        cola.offer(new Barrera(l));
        try {
            return l.await(milis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    int enCola() {
        int n = 0;
        for (Object o : cola) if (o instanceof Linea) n++;
        return n;
    }

    private void bucle(int dias) {
        try {
            podar(dias);
        } catch (Throwable t) {
            avisar("no se pudieron podar los ficheros viejos", t);
        }
        List<Object> lote = new ArrayList<>();
        boolean fin = false;
        while (!fin) {
            lote.clear();
            try {
                lote.add(cola.take());
            } catch (InterruptedException e) {
                break;
            }
            cola.drainTo(lote, 1000);
            for (Object o : lote) if (o == FIN) fin = true;
            escribirLote(lote);
        }
        // Lo que se colo entre el FIN y ahora.
        lote.clear();
        cola.drainTo(lote);
        escribirLote(lote);
        cerrarEscritor();
    }

    /** Escribe un lote en orden y avisa a las barreras cuando lo anterior ya esta en disco. */
    private void escribirLote(List<Object> lote) {
        List<CountDownLatch> barreras = new ArrayList<>();
        for (Object o : lote) {
            if (o instanceof Barrera b) {
                barreras.add(b.hecho());
            } else if (o instanceof Linea l) {
                try {
                    if (escritor == null || !l.mes().equals(mesAbierto)) {
                        cerrarEscritor();
                        if (!carpeta.isDirectory() && !carpeta.mkdirs()) throw new IOException("no se puede crear " + carpeta);
                        escritor = Files.newBufferedWriter(new File(carpeta, l.mes() + ".jsonl").toPath(),
                                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        mesAbierto = l.mes();
                    }
                    escritor.write(l.texto());
                    escritor.write('\n');
                    escritas.incrementAndGet();
                } catch (IOException e) {
                    fallos.incrementAndGet();
                    avisar("no se pudo escribir en telemetria/" + l.mes() + ".jsonl", e);
                    cerrarEscritor();
                }
            }
        }
        if (escritor != null) {
            try {
                escritor.flush();
            } catch (IOException e) {
                fallos.incrementAndGet();
                avisar("no se pudo volcar la telemetria", e);
                cerrarEscritor();
            }
        }
        for (CountDownLatch l : barreras) l.countDown();
    }

    private void cerrarEscritor() {
        if (escritor != null) {
            try {
                escritor.close();
            } catch (IOException ignorado) {
                // Cerrar un fichero que ya fallo no tiene arreglo; la siguiente linea lo reabre.
            }
        }
        escritor = null;
        mesAbierto = null;
    }

    /** Borra los meses cuyo ultimo dia queda a mas de dias-a-conservar (MED sec. 3.1). */
    private void podar(int dias) {
        File[] fs = carpeta.listFiles();
        if (fs == null) return;
        LocalDate limite = LocalDate.now(zona()).minusDays(dias);
        for (File f : fs) {
            var m = FICHERO.matcher(f.getName());
            if (!m.matches()) continue;
            try {
                if (YearMonth.parse(m.group(1)).atEndOfMonth().isBefore(limite) && f.delete()) {
                    hc.plugin().getLogger().info("[Calamity] Telemetria: borrado " + f.getName() + " (mas de " + dias + " dias).");
                }
            } catch (Throwable ignorado) {
                // Un nombre raro que casa con el patron no merece parar la poda del resto.
            }
        }
    }

    /** Un aviso por arranque: si el disco falla, fallara mil veces seguidas. */
    private void avisar(String que, Throwable t) {
        if (avisado) return;
        avisado = true;
        hc.plugin().getLogger().log(Level.WARNING, "[Calamity] Telemetria: " + que + " (se sigue; no se repite el aviso)", t);
    }

    private ZoneId zona() {
        Calendario c = hc.calendario();
        try {
            return c != null ? c.zona() : ZoneId.systemDefault();
        } catch (Throwable t) {
            return ZoneId.systemDefault();
        }
    }

    private void estado(CommandSender quien) {
        String mes = ultimoMes.isEmpty() ? YearMonth.now(zona()).toString() : ultimoMes;
        quien.sendMessage(Component.text("cola " + enCola() + " · telemetria/" + mes + ".jsonl", NamedTextColor.GREEN));
        quien.sendMessage(Component.text((activa() ? "activa" : "apagada") + " · escritas " + escritas.get()
                + " · fallos " + fallos.get() + " · hilo " + (hilo.isAlive() ? "vivo" : "parado")
                + " · expediciones abiertas " + sesiones.size(), NamedTextColor.GRAY));
    }

    // --------------------------------------------------- datos de los sucesos

    /** Lo que lleva encima que importa a la telemetria. */
    private static final class Encima {
        long esencias, frascos, cristales;
        final Map<String, Object> reliquias = new LinkedHashMap<>();
    }

    private Encima encima(Player p) {
        Encima e = new Encima();
        long[] porGrado = new long[5];
        ItemsCalamity items = hc.items();
        Reliquias rel = hc.reliquias();
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || it.getType().isAir()) continue;
            int n = it.getAmount();
            if (items.esEsencia(it)) e.esencias += n;
            else if (items.esFrasco(it)) e.frascos += n;
            else if (items.esCristal(it)) e.cristales += n;
            else if (rel != null && hc.valor("reliquias", () -> rel.es(it), false)) {
                int g = hc.valor("reliquias", () -> rel.grado(it), 0);
                if (g >= 1 && g <= 4) porGrado[g] += n;
            }
        }
        for (int g = 1; g <= 4; g++) e.reliquias.put(String.valueOf(g), porGrado[g]);
        return e;
    }

    private long saldo(Player p) {
        Saldo s = hc.saldo();
        return s == null ? 0 : hc.valor("saldo", () -> s.de(p.getUniqueId()), 0L);
    }

    private int nivel(Player p) {
        MobsLethal mobs = hc.plugin().mobs();
        return mobs == null ? 0 : hc.valor("telemetria", () -> mobs.nivelCalamity(p), 0);
    }

    /** Minutos de la expedicion: el contador de la cordura o, si es mayor, el reloj de la sesion. */
    private long minutos(Player p, Sesion s) {
        long seg = hc.cordura().estado(p).segundosDentro;
        if (s != null) seg = Math.max(seg, (System.currentTimeMillis() - s.inicio) / 1000);
        return seg / 60;
    }

    /** 5 o 7 (PLAN sec. 3.1): 7 si sale con >= equipo-piezas de escalon >= equipo-escalon. */
    private int rachaTope(Censo.Foto f) {
        int max = hc.cfg().getInt("racha.maximo", 5);
        int conEquipo = hc.cfg().getInt("racha.maximo-con-equipo", 7);
        int piezas = hc.cfg().getInt("racha.equipo-piezas", 3);
        int escalon = hc.cfg().getInt("racha.equipo-escalon", 12);
        return f.piezasConEscalon(escalon) >= piezas ? conEquipo : max;
    }

    /** mob:<tipo> | pvp:<uuid> | parca | eco:<dueno> | entorno:<causa> (MED sec. 3.3). */
    static String causa(Player p) {
        EntityDamageEvent ultimo = p.getLastDamageCause();
        if (ultimo == null) return "entorno:desconocida";
        Entity fuente = null;
        try {
            fuente = ultimo.getDamageSource().getCausingEntity();
            if (fuente == null) fuente = ultimo.getDamageSource().getDirectEntity();
        } catch (Throwable ignorado) {
            // Sin DamageSource (API vieja) queda la causa del evento.
        }
        if (fuente instanceof Player j) return "pvp:" + j.getUniqueId();
        if (fuente != null) {
            String am = Marcas.amenaza(fuente);
            if ("parca".equals(am)) return "parca";
            if ("eco".equals(am)) {
                String dueno = fuente.getPersistentDataContainer().get(Marcas.ECO_DUENO, PersistentDataType.STRING);
                return "eco:" + (dueno == null ? "?" : dueno);
            }
            if (fuente instanceof LivingEntity) return "mob:" + fuente.getType().getKey().getKey();
        }
        String tipo;
        try {
            tipo = ultimo.getDamageSource().getDamageType().getKey().getKey();
        } catch (Throwable sinTipo) {
            tipo = ultimo.getCause().name().toLowerCase(java.util.Locale.ROOT);
        }
        return "entorno:" + tipo;
    }

    // ------------------------------------------------ contadores de la sesion

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alMorirMob(EntityDeathEvent e) {
        LivingEntity mob = e.getEntity();
        if (!hc.esHardcore(mob.getWorld())) return;
        if (mob instanceof Player || Marcas.esAmenaza(mob)) return;
        Player asesino = mob.getKiller();
        if (asesino == null) return;
        Sesion s = sesiones.get(asesino.getUniqueId());
        if (s == null) return;
        String clase = mob.getPersistentDataContainer().get(CLASE_MOB, PersistentDataType.STRING);
        if ("minijefe".equals(clase)) {
            s.minijefes++;
            return;
        }
        s.mobs++;
        if ("destacado".equals(clase)) s.destacados++;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alGenerarCofre(LootGenerateEvent e) {
        if (!hc.esHardcore(e.getWorld())) return;
        if (e.getInventoryHolder() == null || !(e.getEntity() instanceof Player p)) return;
        Sesion s = sesiones.get(p.getUniqueId());
        if (s != null) s.cofres++;
    }

    /** Quien sale del servidor fuera de Calamity no tiene expedicion abierta que guardar. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void alSalir(PlayerQuitEvent e) {
        if (!hc.esHardcore(e.getPlayer())) sesiones.remove(e.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- autotest

    /**
     * MED sec. 3.4: escribe 50 sucesos con "prueba": true (el analizador los ignora), espera a
     * que esten en disco y los relee del fichero del mes. Es la unica prueba que escribe en
     * un fichero real, a proposito: lo que se prueba es justo la escritura.
     */
    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();

        Map<String, Object> raro = new LinkedHashMap<>();
        raro.put("texto", "Ñandú \"entre comillas\"\nsalto \\ barra \u0001 fin");
        raro.put("n", 3);
        raro.put("d", 0.25);
        raro.put("entero", 4.0);
        raro.put("lista", List.of(1, "a", true));
        raro.put("nulo", null);
        String j = Jsonl.escribir(raro);
        Map<String, Object> vuelta = Jsonl.objeto(j);
        h.ok("el JSON es una sola linea", !j.contains("\n") && !j.contains("\r"));
        h.ok("el JSON se relee", vuelta != null);
        if (vuelta != null) {
            h.igual("texto con comillas, saltos y control ida y vuelta", raro.get("texto"), vuelta.get("texto"));
            h.igual("entero", 3L, vuelta.get("n"));
            h.igual("decimal", 0.25, vuelta.get("d"));
            h.igual("decimal entero sigue siendo decimal", 4.0, vuelta.get("entero"));
            h.igual("lista", List.of(1L, "a", true), vuelta.get("lista"));
            h.ok("null", vuelta.containsKey("nulo") && vuelta.get("nulo") == null);
        }
        h.ok("un JSON roto se detecta", Jsonl.objeto("{\"a\":") == null && Jsonl.objeto("{\"a\":1}x") == null);

        String lote = UUID.randomUUID().toString().substring(0, 8);
        String[] evs = {"entra", "sale", "muere", "pago", "trueque-fallido"};
        String mes = null;
        for (int i = 0; i < 50; i++) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("prueba", true);
            c.put("lote", lote);
            c.put("n", i);
            c.put("uuid", "no-pisa");                   // un campo propio no puede pisar uno comun
            if (i == 0) c.put("censo", Censo.agregar(List.of(Censo.pieza("mano", "NETHERITE_SWORD", null, null, 2, Set.of()))));
            mes = encolar(evs[i % evs.length], Autotest.sintetico(i % 5 + 1), "prueba-" + (i % 5 + 1), "calamity", c);
        }
        h.ok("la cola se vacia en menos de 5 s", vaciar(5000));
        h.igual("cola 0 tras vaciar", 0, enCola());

        File f = new File(carpeta, mes + ".jsonl");
        List<String> lineas;
        try {
            lineas = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            h.ok("leer " + f.getName() + ": " + e, false);
            return h.lineas();
        }
        int validas = 0, conComunes = 0, ordenadas = 0, previa = -1;
        boolean uuidRespetado = true, tLegible = true, censoAnidado = false;
        String marca = "\"lote\":\"" + lote + "\"";
        for (String l : lineas) {
            if (!l.contains(marca)) continue;
            Map<String, Object> m = Jsonl.objeto(l);
            if (m == null) continue;
            validas++;
            if (m.keySet().containsAll(COMUNES) && Long.valueOf(VERSION).equals(m.get("v"))
                    && Boolean.TRUE.equals(m.get("prueba"))) conComunes++;
            if ("no-pisa".equals(m.get("uuid"))) uuidRespetado = false;
            try {
                OffsetDateTime.parse(String.valueOf(m.get("t")));
            } catch (Throwable t) {
                tLegible = false;
            }
            int n = ((Number) m.getOrDefault("n", -1L)).intValue();
            if (n > previa) ordenadas++;
            previa = n;
            if (m.get("censo") instanceof Map<?, ?> cm && cm.get("piezas") instanceof List<?> pl && pl.size() == 1) {
                censoAnidado = true;
            }
        }
        h.igual("50 lineas JSON validas en telemetria/" + mes + ".jsonl", 50, validas);
        h.igual("las 50 con t, ev, uuid, nombre, mundo, v y prueba", 50, conComunes);
        h.igual("en el orden en que se apuntaron", 50, ordenadas);
        h.ok("un campo propio no pisa uuid", uuidRespetado);
        h.ok("t se lee como fecha ISO con zona", tLegible);
        h.ok("el censo va anidado con sus piezas", censoAnidado);
        return h.lineas();
    }

    /** MED sec. 5 con items de memoria y un tier sintetico (sin MMOItems en el Test). */
    private static List<String> autotestCenso() {
        Autotest.Hoja h = new Autotest.Hoja();
        h.igual("DIAMOND_CHESTPLATE escalon 4", 4, Censo.escalon(new ItemStack(Material.DIAMOND_CHESTPLATE)));
        h.igual("NETHERITE_SWORD escalon 6", 6, Censo.escalon(new ItemStack(Material.NETHERITE_SWORD)));
        h.igual("LEATHER_BOOTS escalon 0", 0, Censo.escalon(new ItemStack(Material.LEATHER_BOOTS)));
        h.igual("IRON_HELMET escalon 2", 2, Censo.escalon(new ItemStack(Material.IRON_HELMET)));
        h.igual("GOLDEN_SWORD escalon 1", 1, Censo.escalon(new ItemStack(Material.GOLDEN_SWORD)));
        h.igual("null escalon 0", 0, Censo.escalon((ItemStack) null));

        ItemStack botas = marcado(new ItemStack(Material.IRON_BOOTS), Marcas.PRESTADO);
        ItemStack copia = marcado(new ItemStack(Material.NETHERITE_HELMET), Marcas.ECO_COPIA);
        ItemStack[] seis = {copia, new ItemStack(Material.DIAMOND_CHESTPLATE), null, botas,
                new ItemStack(Material.NETHERITE_SWORD), new ItemStack(Material.TORCH)};
        Censo.Foto f = Censo.de(seis);
        h.igual("piezas: yelmo, pechera, botas y espada (la antorcha no es equipo)", 4, f.piezas().size());
        h.cerca("escalon medio sin prestado ni copia_eco = (4 + 6) / 2", 5.0, f.escalonMedio(), 1e-9);
        h.igual("escalon maximo", 6, f.escalonMax());
        boolean hayMmo = PuenteMmo.disponible();
        if (!hayMmo) {
            h.igual("sin MMOItems mmo = VANILLA:<material>", "VANILLA:DIAMOND_CHESTPLATE", f.piezas().get(1).mmo());
            h.igual("sin MMOItems ninguna pieza mmo", 0, f.piezasMmo());
        }
        h.igual("marca prestado en las botas", Set.of("prestado"), f.piezas().get(2).marcas());
        h.igual("marca copia_eco en el yelmo", Set.of("copia_eco"), f.piezas().get(0).marcas());
        h.igual("casilla de la espada", "mano", f.piezas().get(3).casilla());

        Censo.Pieza manto = Censo.pieza("pechera", "NETHERITE_CHESTPLATE", "ARMOR.CORAZA_DE_CALAMIDAD", "CALAMIDAD", 3, Set.of());
        Censo.Pieza parca = Censo.pieza("mano", "NETHERITE_SWORD", "SWORD.GUADANA_DE_LA_PARCA", "parca", 1, Set.of());
        Censo.Pieza anomalia = Censo.pieza("yelmo", "NETHERITE_HELMET", "ANOMALIA.X", "KEEPER", 0, Set.of());
        Censo.Pieza nuevo = Censo.pieza("botas", "DIAMOND_BOOTS", "ARMOR.NUEVO", "TIER_QUE_NO_EXISTE", 0, Set.of());
        h.igual("tier CALAMIDAD sintetico = 16", 16, manto.escalon());
        h.igual("tier en minusculas se normaliza (PARCA 17)", 17, parca.escalon());
        h.igual("set de anomalia = 18", 18, anomalia.escalon());
        h.igual("tier desconocido cae al material (diamante 4)", 4, nuevo.escalon());
        Censo.Foto g = Censo.agregar(List.of(manto, parca, anomalia, nuevo));
        h.igual("piezas_calamity cuenta 16 y 17, no 18", 2, g.piezasCalamity());
        h.igual("piezas_mmo", 4, g.piezasMmo());
        h.igual("escalon_max", 18, g.escalonMax());
        h.cerca("escalon_medio", (16 + 17 + 18 + 4) / 4.0, g.escalonMedio(), 0.01);
        h.igual("piezas de escalon >= 12 (tope de racha)", 3, g.piezasConEscalon(12));

        Censo.Foto vacia = Censo.de(new ItemStack[6]);
        h.igual("sin nada: medio 0", 0.0, vacia.escalonMedio());
        h.igual("sin nada: 0 piezas", 0, vacia.piezas().size());
        h.sinExcepcion("de(null) no revienta", () -> Censo.de((ItemStack[]) null));

        Map<String, Object> json = f.json();
        h.ok("json con escalon_medio, escalon_max, piezas_mmo, piezas_calamity y piezas",
                json.keySet().containsAll(List.of("escalon_medio", "escalon_max", "piezas_mmo", "piezas_calamity", "piezas")));
        Map<String, Object> vuelta = Jsonl.objeto(Jsonl.escribir(json));
        h.ok("el censo en JSON se relee con sus 4 piezas",
                vuelta != null && vuelta.get("piezas") instanceof List<?> l && l.size() == 4);
        return h.lineas();
    }

    private static ItemStack marcado(ItemStack it, NamespacedKey marca) {
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(marca, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
        }
        return it;
    }
}
