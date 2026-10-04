package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Calamity 1.7.1: el UNICO sitio de Calamity que escribe en la barra de accion. Nadie mas
 * llama a sendActionBar: los destellos de la cordura, el aviso de la cuarentena y los de Ligado
 * pasan todos por aqui, para que el protocolo de abajo no se olvide en ninguno.
 *
 * Calamity 1.11: la cordura ya no se pinta aqui de fondo sino en una BossBar (MedidorCordura); la
 * barra de accion queda para los destellos cortos. Cordura.pintar llama cada segundo a repintar()
 * en vez de a fondo(): solo se repinta el destello que este en pantalla (la barra de accion se apaga
 * sola a los ~3 s) y, con limpiarAlAcabar, al terminar se borra con un texto vacio, porque ya no hay
 * una cordura que lo tape. Con hardcore.cordura.pantalla: actionbar todo sigue como en la 1.10.
 *
 * <h2>El protocolo "ederus_actionbar" (compartido con PremioPescao y el que venga)</h2>
 * <ol>
 *   <li>Cada plugin que ocupa la barra con algo puntual deja en el jugador un metadata de Bukkit
 *       con clave {@value #CLAVE} y valor {@code FixedMetadataValue(plugin, Long hastaMillis)}:
 *       el instante (System.currentTimeMillis()) hasta el que la barra es suya.</li>
 *   <li>Antes de mandar algo DE FONDO (la barra de cordura, que se repite cada segundo) se mira
 *       si otro plugin tiene una reserva vigente. Si la tiene, ese segundo no se manda; cuando
 *       caduca, el siguiente tick vuelve a pintar.</li>
 *   <li>Los avisos PUNTUALES (los destellos) ponen su propia reserva mientras duran, para que el
 *       fondo de los demas (el indicador de la pesca extrema) no los pise.</li>
 *   <li>Si un aviso puntual llega con una reserva ajena vigente, espera en cola a que termine,
 *       como mucho {@link #ESPERA_MAXIMA_MS} ms; despues se descarta. Solo cuenta el ultimo.</li>
 *   <li>La reserva propia nunca bloquea a Calamity: solo cuentan las de otros plugins.</li>
 * </ol>
 *
 * Sin nadie mas en la barra todo sale igual que antes: la cordura cada segundo, los destellos
 * encima mientras duran y con los mismos textos.
 *
 * El nucleo (aviso, repasar, fondo) no toca Bukkit: trabaja contra un {@link Destino}, y el
 * autotest "barra" lo prueba con uno de mentira y un reloj a mano.
 */
public final class BarraAccion {

    /** La clave del metadata compartido. No cambiarla sin cambiarla en PremioPescao. */
    public static final String CLAVE = "ederus_actionbar";

    /** Lo mas que espera un aviso puntual a que otro plugin suelte la barra. */
    static final long ESPERA_MAXIMA_MS = 5_000L;

    /** Cada cuanto se mira la cola de avisos (5 ticks = 0,25 s). */
    private static final long REPASO_TICKS = 5L;

    /** Lo que la barra necesita de un jugador: en el servidor, el Player y su metadata. */
    interface Destino {
        /** Hasta cuando (millis) reserva la barra OTRO plugin; 0 si nadie. */
        long reservaAjena();

        /** Pone (o renueva) la reserva de Calamity hasta ese instante. */
        void reservar(long hasta);

        /** Quita la reserva de Calamity. */
        void soltar();

        void enviar(Component texto);

        /** Si un aviso de la cordura se puede ver ahora (dentro de Calamity y contando). */
        boolean dentro();
    }

    /** Un aviso puntual: en cola (hasta == 0) o ya en pantalla hasta "hasta". */
    static final class Aviso {
        final Component texto;
        final long duracion;
        final long pedido;
        /** Los destellos de la cordura solo salen dentro de Calamity, como antes. */
        final boolean soloDentro;
        long hasta;

        Aviso(Component texto, long duracion, long pedido, boolean soloDentro) {
            this.texto = texto;
            this.duracion = duracion;
            this.pedido = pedido;
            this.soloDentro = soloDentro;
        }
    }

    private final Plugin plugin;
    private final Map<UUID, Aviso> avisos = new HashMap<>();
    private Predicate<Player> dentro = p -> true;
    private BukkitTask repaso;
    /** 1.11: borrar la barra cuando acaba un aviso propio (con la cordura en la BossBar no hay fondo que lo tape). */
    private boolean limpiar;

    /** Plugin null solo en el autotest: ahi se usan los metodos con Destino. */
    BarraAccion(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Quien ve los destellos de la cordura (lo pone Hardcore: dentro de Calamity y contando). */
    void dentro(Predicate<Player> quien) {
        dentro = quien == null ? p -> true : quien;
    }

    /** 1.11: si al acabar un aviso propio se borra la barra (lo pone Hardcore: true con la BossBar). */
    void limpiarAlAcabar(boolean si) {
        limpiar = si;
    }

    /** Arranca el repaso de la cola: los avisos que esperaban salen en cuanto la barra queda libre. */
    void arrancar() {
        parar();
        repaso = plugin.getServer().getScheduler().runTaskTimer(plugin, this::repasarTodos, REPASO_TICKS, REPASO_TICKS);
    }

    void parar() {
        if (repaso != null) repaso.cancel();
        repaso = null;
        if (plugin != null) {
            for (Player p : plugin.getServer().getOnlinePlayers()) p.removeMetadata(CLAVE, plugin);
        }
        avisos.clear();
    }

    // ------------------------------------------------------------------ con Bukkit

    /**
     * Algo de fondo que se repite (la barra de cordura, el "Aun no" de la cuarentena): sale
     * solo si ningun otro plugin tiene la barra. Si hay un aviso propio en pantalla, se
     * repinta el aviso en su lugar (lo que antes hacia el destello de la cordura).
     */
    public void fondo(Player p, Component texto) {
        if (p == null || texto == null) return;
        fondo(p.getUniqueId(), destino(p), texto, System.currentTimeMillis());
    }

    /**
     * 1.11 · Sin fondo (la cordura va en la BossBar): repinta el aviso propio que este en pantalla o
     * saca el que esperaba, y nada mas. Lo llama Cordura.pintar cada segundo.
     */
    void repintar(Player p) {
        if (p == null) return;
        repintar(p.getUniqueId(), destino(p), System.currentTimeMillis());
    }

    /** Un aviso puntual de "segundos": reserva la barra mientras dura, o espera su turno. */
    public void aviso(Player p, Component texto, int segundos) {
        aviso(p, texto, segundos, false);
    }

    /** Lo de la cordura (Cordura.destello): igual, pero solo se ve dentro de Calamity. */
    void aviso(Player p, Component texto, int segundos, boolean soloDentro) {
        if (p == null) return;
        aviso(p.getUniqueId(), destino(p), texto, segundos, soloDentro, System.currentTimeMillis());
    }

    /** Olvida el aviso de ese jugador y suelta la reserva (al salir de Calamity). */
    void olvidar(Player p) {
        if (p == null) return;
        olvidar(p.getUniqueId(), destino(p));
    }

    private void repasarTodos() {
        long ahora = System.currentTimeMillis();
        for (UUID u : new ArrayList<>(avisos.keySet())) {
            Player p = plugin.getServer().getPlayer(u);
            if (p == null) {
                avisos.remove(u);
                continue;
            }
            repasar(u, destino(p), ahora);
        }
    }

    private Destino destino(Player p) {
        return new Destino() {
            @Override
            public long reservaAjena() {
                List<Map.Entry<String, Long>> reservas = new ArrayList<>();
                for (MetadataValue v : p.getMetadata(CLAVE)) {
                    Plugin dueno = v.getOwningPlugin();
                    long hasta;
                    try {
                        hasta = v.asLong();
                    } catch (Throwable raro) {
                        continue;
                    }
                    reservas.add(Map.entry(dueno == null ? "" : dueno.getName(), hasta));
                }
                return BarraAccion.reservaAjena(reservas, plugin.getName());
            }

            @Override
            public void reservar(long hasta) {
                p.setMetadata(CLAVE, new FixedMetadataValue(plugin, hasta));
            }

            @Override
            public void soltar() {
                p.removeMetadata(CLAVE, plugin);
            }

            @Override
            public void enviar(Component texto) {
                p.sendActionBar(texto);
            }

            @Override
            public boolean dentro() {
                return dentro.test(p);
            }
        };
    }

    // ------------------------------------------------------------------ el nucleo

    /**
     * La reserva ajena mas tardia de una lista de (dueno, hasta). Las de Calamity no cuentan
     * (se compara por nombre para que un /reload no deje a Calamity bloqueandose a si mismo).
     */
    static long reservaAjena(List<Map.Entry<String, Long>> reservas, String yo) {
        long max = 0;
        for (Map.Entry<String, Long> r : reservas) {
            if (r.getKey().equals(yo) || r.getValue() == null) continue;
            max = Math.max(max, r.getValue());
        }
        return max;
    }

    void aviso(UUID u, Destino d, Component texto, int segundos, boolean soloDentro, long ahora) {
        Aviso antes = avisos.remove(u);
        // Si se pisa un aviso propio en pantalla, su reserva se va con el.
        if (antes != null && antes.hasta > ahora) d.soltar();
        if (texto == null) return;
        avisos.put(u, new Aviso(texto, Math.max(1, segundos) * 1000L, ahora, soloDentro));
        repasar(u, d, ahora);
    }

    /**
     * Mira el aviso de ese jugador: lo ensena si le toca, lo descarta si caduco o espero de mas.
     * Devuelve true si la barra es ahora de ese aviso (recien ensenado o ya en pantalla).
     */
    boolean repasar(UUID u, Destino d, long ahora) {
        Aviso a = avisos.get(u);
        if (a == null) return false;
        if (a.hasta > 0) {
            if (ahora < a.hasta) return true;
            avisos.remove(u);
            // 1.11: sin cordura de fondo, el aviso se borra al acabar (si la barra no es de otro plugin).
            if (limpiar && d.reservaAjena() <= ahora) d.enviar(Component.empty());
            return false;
        }
        if (ahora - a.pedido > ESPERA_MAXIMA_MS) {
            avisos.remove(u);
            return false;
        }
        if (d.reservaAjena() > ahora) return false;
        if (a.soloDentro && !d.dentro()) return false;
        a.hasta = ahora + a.duracion;
        d.reservar(a.hasta);
        d.enviar(a.texto);
        return true;
    }

    /** El fondo con el reloj a mano. Devuelve true si mando el texto de fondo. */
    boolean fondo(UUID u, Destino d, Component texto, long ahora) {
        Aviso a = avisos.get(u);
        if (a != null && a.hasta > 0 && ahora < a.hasta) {
            // Aviso propio en pantalla: se repinta el, como hacia el destello, salvo intrusos.
            if (d.reservaAjena() <= ahora) d.enviar(a.texto);
            return false;
        }
        if (repasar(u, d, ahora)) return false;
        if (d.reservaAjena() > ahora) return false;
        d.enviar(texto);
        return true;
    }

    /** El repintar con el reloj a mano. Devuelve true si la barra es ahora de un aviso propio. */
    boolean repintar(UUID u, Destino d, long ahora) {
        Aviso a = avisos.get(u);
        if (a != null && a.hasta > 0 && ahora < a.hasta) {
            if (d.reservaAjena() <= ahora) d.enviar(a.texto);
            return true;
        }
        return repasar(u, d, ahora);
    }

    void olvidar(UUID u, Destino d) {
        avisos.remove(u);
        d.soltar();
    }

    // ------------------------------------------------------------------ autotest

    /** Un jugador de mentira: apunta lo enviado y la reserva, y la ajena se pone a mano. */
    private static final class Falso implements Destino {
        final List<Component> enviados = new ArrayList<>();
        long ajena;
        long propia;
        boolean dentro = true;

        @Override
        public long reservaAjena() {
            return ajena;
        }

        @Override
        public void reservar(long hasta) {
            propia = hasta;
        }

        @Override
        public void soltar() {
            propia = 0;
        }

        @Override
        public void enviar(Component texto) {
            enviados.add(texto);
        }

        @Override
        public boolean dentro() {
            return dentro;
        }

        Component ultimo() {
            return enviados.isEmpty() ? null : enviados.get(enviados.size() - 1);
        }
    }

    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        long t0 = 1_790_000_000_000L;
        Component cordura = Component.text("Cordura 100%");
        Component pesca = Component.text("Pack Común · salió del agua");

        // 1. Sin reserva ajena, el fondo sale.
        BarraAccion b = new BarraAccion(null);
        UUID u = Autotest.sintetico(1);
        Falso f = new Falso();
        h.ok("sin reserva ajena: el fondo se envia", b.fondo(u, f, cordura, t0));
        h.igual("sin reserva ajena: sale la cordura", cordura, f.ultimo());
        h.ok("sin nadie mas: cada segundo se repinta igual", b.fondo(u, f, cordura, t0 + 1000)
                && b.fondo(u, f, cordura, t0 + 2000) && f.enviados.size() == 3);

        // 2. Con reserva ajena vigente, no sale.
        f = new Falso();
        f.ajena = t0 + 2500;
        h.ok("reserva ajena vigente: el fondo no se envia", !b.fondo(u, f, cordura, t0));
        h.ok("reserva ajena vigente: nada en la barra", !b.fondo(u, f, cordura, t0 + 1000) && f.enviados.isEmpty());
        h.ok("reserva ajena vigente: Calamity no reserva nada", f.propia == 0);

        // 3. Al expirar, vuelve a salir.
        h.ok("reserva ajena caducada: el fondo vuelve", b.fondo(u, f, cordura, t0 + 2500));
        h.igual("reserva ajena caducada: sale la cordura", List.of(cordura), f.enviados);

        // 4. La reserva propia no bloquea (ni la de otro Calamity tras un /reload).
        List<Map.Entry<String, Long>> solo = List.of(Map.entry("Calamity", t0 + 3000));
        h.igual("reserva propia: no cuenta como ajena", 0L, reservaAjena(solo, "Calamity"));
        List<Map.Entry<String, Long>> dos = List.of(Map.entry("Calamity", t0 + 9000), Map.entry("PremioPescao", t0 + 2500));
        h.igual("con propia y ajena: cuenta solo la ajena", t0 + 2500, reservaAjena(dos, "Calamity"));
        f = new Falso();
        b.aviso(u, f, pesca, 3, true, t0);
        h.igual("aviso propio: reserva la barra lo que dura", t0 + 3000, f.propia);
        f.ajena = reservaAjena(List.of(Map.entry("Calamity", f.propia)), "Calamity");
        b.fondo(u, f, cordura, t0 + 1000);
        h.igual("reserva propia: la barra la sigue pintando Calamity (el aviso)", pesca, f.ultimo());
        h.ok("reserva propia: al acabar el aviso vuelve la cordura", b.fondo(u, f, cordura, t0 + 3000)
                && cordura.equals(f.ultimo()));

        // 5. Un aviso puntual se encola y sale al terminar la reserva ajena. Si hay varios, el ultimo.
        b = new BarraAccion(null);
        f = new Falso();
        f.ajena = t0 + 2500;
        Component distancia = Component.text("Te alejas del spawn · mobs +10 niveles");
        Component otro = Component.text("Ya no estás en combate.");
        b.aviso(u, f, otro, 2, true, t0);
        b.aviso(u, f, distancia, 3, true, t0 + 200);
        h.ok("aviso con reserva ajena: no sale todavia", f.enviados.isEmpty() && f.propia == 0);
        h.ok("aviso en cola: el tick tampoco pinta la cordura", !b.fondo(u, f, cordura, t0 + 1000) && f.enviados.isEmpty());
        h.ok("aviso en cola: el repaso espera", !b.repasar(u, f, t0 + 2250) && f.enviados.isEmpty());
        h.ok("reserva ajena acabada: el aviso sale", b.repasar(u, f, t0 + 2500));
        h.igual("de los dos avisos en cola sale solo el ultimo", List.of(distancia), f.enviados);
        h.igual("el aviso reserva desde que sale, lo que dura", t0 + 2500 + 3000, f.propia);
        b.fondo(u, f, cordura, t0 + 3500);
        h.igual("mientras dura, el tick repinta el aviso", distancia, f.ultimo());
        b.fondo(u, f, cordura, t0 + 5500);
        h.igual("al acabar el aviso vuelve la cordura", cordura, f.ultimo());

        // 6. Un aviso que espera mas de 5 s se descarta.
        b = new BarraAccion(null);
        f = new Falso();
        f.ajena = t0 + 6000;
        b.aviso(u, f, distancia, 3, true, t0);
        h.ok("a los 5 s aun espera", !b.repasar(u, f, t0 + 5000) && f.enviados.isEmpty());
        h.ok("pasados 5 s se descarta", !b.repasar(u, f, t0 + 5001) && f.enviados.isEmpty());
        h.ok("descartado: al soltar la barra ya no sale", !b.repasar(u, f, t0 + 6000) && f.enviados.isEmpty());
        h.ok("descartado: vuelve la cordura", b.fondo(u, f, cordura, t0 + 6000) && List.of(cordura).equals(f.enviados));

        // Extra: el destello de la cordura no sale fuera de Calamity (como antes).
        b = new BarraAccion(null);
        f = new Falso();
        f.dentro = false;
        b.aviso(u, f, otro, 2, true, t0);
        h.ok("destello de cordura fuera de Calamity: no sale", f.enviados.isEmpty());
        b.aviso(u, f, otro, 2, false, t0);
        h.ok("aviso suelto (Ligado) fuera de Calamity: sale", List.of(otro).equals(f.enviados));
        b.olvidar(u, f);
        h.ok("olvidar suelta la reserva", f.propia == 0);

        // 1.11: con la cordura en la BossBar no hay fondo; repintar solo mantiene el destello.
        b = new BarraAccion(null);
        b.limpiarAlAcabar(true);
        f = new Falso();
        h.ok("bossbar: sin aviso, repintar no manda nada", !b.repintar(u, f, t0) && f.enviados.isEmpty());
        b.aviso(u, f, distancia, 3, true, t0);
        h.igual("bossbar: el destello sale", List.of(distancia), f.enviados);
        h.ok("bossbar: al segundo se repinta", b.repintar(u, f, t0 + 1000) && distancia.equals(f.ultimo())
                && f.enviados.size() == 2);
        h.ok("bossbar: nunca se pinta la cordura", f.enviados.stream().noneMatch(cordura::equals));
        h.ok("bossbar: al acabar se borra", !b.repintar(u, f, t0 + 3000)
                && Component.empty().equals(f.ultimo()) && f.enviados.size() == 3);
        h.ok("bossbar: despues ya no manda nada", !b.repintar(u, f, t0 + 4000) && f.enviados.size() == 3);
        f = new Falso();
        b.aviso(u, f, otro, 2, true, t0);
        f.ajena = t0 + 9000;
        h.ok("bossbar: con la barra de otro plugin no se repinta", b.repintar(u, f, t0 + 1000) && f.enviados.size() == 1);
        b.repasar(u, f, t0 + 2000);
        h.ok("bossbar: ni se borra lo del otro plugin al acabar", f.enviados.size() == 1);
        b = new BarraAccion(null);
        f = new Falso();
        b.aviso(u, f, otro, 2, true, t0);
        b.repasar(u, f, t0 + 2000);
        h.ok("actionbar (1.10): al acabar no se borra, lo tapa la cordura", f.enviados.size() == 1);
        f = new Falso();
        f.ajena = t0 + 2500;
        b.limpiarAlAcabar(true);
        b.aviso(u, f, distancia, 3, true, t0);
        h.ok("bossbar: aviso en cola, sale al soltar la otra", !b.repintar(u, f, t0 + 1000) && b.repintar(u, f, t0 + 2500)
                && List.of(distancia).equals(f.enviados));
        return h.lineas();
    }
}
