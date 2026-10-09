package net.ederus.edm.goditems;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import net.ederus.edm.comun.Bitacora;

/**
 * La bitacora de GodItems (plugins/EDM/logs/goditems-AAAA-MM-DD.log): lo que el staff
 * necesita para supervisar las armas con habilidad (la Masamune, el Venablo, el Epitafio).
 *
 * Cuatro clases de linea:
 *   activa   | jugador | item | activador | objetivo | golpe | acciones | mundo x y z
 *            cada vez que un activador de gesto se lanza de verdad (paso cooldown,
 *            probabilidad, usos y condiciones);
 *   accion   | jugador | item | ACCION | activador | objetivos con el daño que les quito | total | notas
 *            cada accion que hirio a alguien o dejo nota (curas, marcas). El daño es el que
 *            de verdad entro: se lee del evento en MONITOR, despues de armadura y de MythicLib;
 *   espera   | jugador | item | activador | faltan X s
 *            un intento rechazado por enfriamiento, agregado: la primera linea sale en el
 *            acto y el resto de esa ventana se resume en una sola al cerrarla;
 *   recarga  | jugador | item | activador | le faltaban X s | por ...
 *            una espera borrada por otra accion (el "cada muerte lo recarga").
 *
 * Los activadores de tick (EN_MANO, PUESTO...) no se anotan salvo que se pida: correrian
 * cada pocos ticks. Todo en el hilo principal.
 */
public final class BitacoraGi implements Listener {

    private final GodItemsPlugin modulo;
    private Bitacora bitacora;

    private boolean activa = true;
    private boolean deTick = false;
    private long ventanaMs = 30_000L;

    /** Las acciones en marcha, la de arriba la ultima (una accion puede disparar otro item). */
    private final Deque<Captura> pila = new ArrayDeque<>();
    private final AgregadorEsperas esperas = new AgregadorEsperas();

    private static final class Captura {
        final Ctx ctx;
        final String accion;
        final Map<String, Double> golpes = new LinkedHashMap<>();
        final List<String> notas = new ArrayList<>(2);

        Captura(Ctx ctx, String accion) {
            this.ctx = ctx;
            this.accion = accion;
        }
    }

    public BitacoraGi(GodItemsPlugin modulo) {
        this.modulo = modulo;
    }

    /** Lee goditems/config.yml > bitacora. */
    void configurar(org.bukkit.configuration.ConfigurationSection c) {
        this.activa = c == null || c.getBoolean("activa", true);
        this.deTick = c != null && c.getBoolean("activadores-de-tick", false);
        int s = c == null ? 30 : c.getInt("espera-segundos", 30);
        this.ventanaMs = Math.max(1, Math.min(3600, s)) * 1000L;
        if (this.bitacora == null) this.bitacora = this.modulo.core().bitacora("goditems");
    }

    private boolean anota(Activador act) {
        return this.activa && this.bitacora != null && (this.deTick || act == null || !act.esTick());
    }

    /* ============================================================= activa */

    /** El activador se lanzo: paso todo lo que podia frenarlo. */
    void activa(Ctx ctx, GodItem.Bloque bloque) {
        if (!anota(ctx.activador())) return;
        List<String> c = new ArrayList<>();
        c.add("activa");
        c.add(nombre(ctx.jugador()));
        c.add(item(ctx.definicion()));
        c.add(ctx.activador().name());
        if (ctx.objetivo() != null) c.add("objetivo " + nombre(ctx.objetivo()));
        if (ctx.evento() instanceof org.bukkit.event.entity.EntityDamageEvent && ctx.dano() > 0) {
            c.add("golpe " + Bitacora.num(ctx.dano()));
        }
        Set<String> acciones = new LinkedHashSet<>();
        nombresDe(bloque.pasos(), acciones);
        if (!acciones.isEmpty()) c.add("acciones " + String.join(", ", acciones));
        if (bloque.cooldown() > 0) c.add("espera " + segundos(bloque.cooldown()));
        c.add(donde(ctx.jugador().getLocation()));
        this.bitacora.anotar(c.toArray(new String[0]));
    }

    /** Los nombres de accion de una lista, entrando en SI y REPETIR, sin repetir. */
    static void nombresDe(List<Paso> pasos, Set<String> out) {
        if (pasos == null) return;
        for (Paso p : pasos) {
            switch (p) {
                case Paso.Simple s -> out.add(s.args().nombre());
                case Paso.Espera e -> { }
                case Paso.Si si -> {
                    nombresDe(si.entonces(), out);
                    nombresDe(si.siNo(), out);
                }
                case Paso.Repetir r -> nombresDe(r.pasos(), out);
            }
        }
    }

    /* ============================================================ acciones */

    /** Motor: va a correr una accion. Siempre en pareja con {@link #despues()}. */
    void antes(Ctx ctx, String accion) {
        this.pila.push(new Captura(ctx, accion));
    }

    /** Motor: la accion acabo. Si hirio a alguien o dejo nota, una linea. */
    void despues() {
        Captura c = this.pila.poll();
        if (c == null || (c.golpes.isEmpty() && c.notas.isEmpty()) || !anota(c.ctx.activador())) return;
        List<String> l = new ArrayList<>();
        l.add("accion");
        l.add(nombre(c.ctx.jugador()));
        l.add(item(c.ctx.definicion()));
        l.add(c.accion);
        l.add(c.ctx.activador().name());
        if (!c.golpes.isEmpty()) {
            double total = 0;
            List<String> partes = new ArrayList<>();
            for (Map.Entry<String, Double> e : c.golpes.entrySet()) {
                partes.add(e.getKey() + " " + Bitacora.num(e.getValue()));
                total += e.getValue();
            }
            l.add("dano a " + String.join(", ", partes));
            if (c.golpes.size() > 1) l.add("total " + Bitacora.num(total));
        }
        l.addAll(c.notas);
        this.bitacora.anotar(l.toArray(new String[0]));
    }

    /** Una nota para la linea de la accion en marcha (una cura, una marca). Sin accion en marcha, linea suelta. */
    public void nota(Ctx ctx, String texto) {
        Captura c = this.pila.peek();
        if (c != null && c.ctx == ctx) {
            c.notas.add(texto);
            return;
        }
        if (ctx == null || !anota(ctx.activador())) return;
        this.bitacora.anotar("accion", nombre(ctx.jugador()), item(ctx.definicion()), ctx.activador().name(), texto);
    }

    /**
     * El daño que de verdad entra, ya con armadura y con lo que haga MythicLib: lo hecho por
     * el jugador de una accion en marcha (directo o con un proyectil suyo) se apunta a esa accion.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alDanar(EntityDamageByEntityEvent e) {
        if (this.pila.isEmpty()) return;
        Entity atacante = e.getDamager();
        if (atacante instanceof Projectile pr && pr.getShooter() instanceof Entity tirador) atacante = tirador;
        if (!(atacante instanceof Player)) return;
        for (Captura c : this.pila) {
            if (c.ctx.jugador() != null && c.ctx.jugador().getUniqueId().equals(atacante.getUniqueId())) {
                c.golpes.merge(nombre(e.getEntity()), Math.max(0, e.getFinalDamage()), Double::sum);
                return;
            }
        }
    }

    /* ============================================================== esperas */

    /** Un intento rechazado por enfriamiento: la primera de la ventana en el acto, el resto resumido. */
    void espera(Player j, GodItem def, Activador act, int ticks) {
        if (!anota(act) || j == null || def == null) return;
        String clave = j.getUniqueId() + "#" + def.id() + "#" + act.name();
        String[] cabecera = {"espera", j.getName(), item(def), act.name()};
        for (String[] l : this.esperas.registrar(clave, cabecera, ticks, System.currentTimeMillis(), this.ventanaMs)) {
            this.bitacora.anotar(l);
        }
    }

    /** Cada pocos ticks: cierra las ventanas pasadas y escribe su resumen. */
    void repasar() {
        if (this.bitacora == null) return;
        for (String[] l : this.esperas.cerrar(System.currentTimeMillis(), this.ventanaMs)) {
            if (this.activa) this.bitacora.anotar(l);
        }
    }

    /** Al apagar: lo que quede pendiente de resumir, ya. */
    void vaciar() {
        if (this.bitacora == null) return;
        for (String[] l : this.esperas.cerrar(Long.MAX_VALUE / 2, this.ventanaMs)) {
            if (this.activa) this.bitacora.anotar(l);
        }
        this.pila.clear();
    }

    /* ============================================================== recargas */

    /**
     * REINICIAR_COOLDOWN / COOLDOWN_DE a 0: la espera de un item que se borra (la Crimson al
     * matar). quedaban son los ticks que le faltaban; 0 = no estaba en espera. act null = todas.
     */
    public void recarga(Ctx ctx, Player p, String itemId, Activador act, int quedaban) {
        if (ctx == null || !anota(ctx.activador()) || p == null) return;
        GodItem def = itemId == null ? null : this.modulo.registro().porId(itemId);
        this.bitacora.anotar("recarga", p.getName(), def != null ? item(def) : itemId == null ? "-" : itemId,
                act == null ? "todas las esperas" : act.name(),
                act == null ? "-" : quedaban > 0 ? "le faltaban " + segundos(quedaban) : "no estaba en espera",
                "por " + ctx.activador().name() + " de " + ctx.definicion().id());
    }

    /** SENTENCIA al cerrarse (Sentencias): sentencia | autor | item | lo que paso. */
    void sentencia(String autor, String item, List<String> resto) {
        if (!this.activa || this.bitacora == null) return;
        GodItem def = item == null ? null : this.modulo.registro().porId(item);
        List<String> l = new ArrayList<>();
        l.add("sentencia");
        l.add(autor == null ? "-" : autor);
        l.add(def != null ? item(def) : item == null ? "-" : item);
        l.addAll(resto);
        this.bitacora.anotar(l.toArray(new String[0]));
    }

    /* ================================================================ textos */

    /** "MASAMUNE (MMO CALAMITY_ARMAS.MASAMUNE)" o solo el id si es nativo. */
    static String item(GodItem def) {
        if (def == null) return "-";
        return def.enlazado() ? def.id() + " (MMO " + def.enlace() + ")" : def.id();
    }

    /** Un jugador por su nombre; un mob por su tipo y, si lo tiene, su nombre visible. */
    public static String nombre(Entity e) {
        if (e == null) return "-";
        if (e instanceof Player p) return p.getName();
        String tipo = e.getType().name().toLowerCase(Locale.ROOT);
        var cn = e.customName();
        if (cn == null) return tipo;
        String n = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(cn);
        return n.isBlank() ? tipo : tipo + " \"" + n + "\"";
    }

    static String donde(Location l) {
        if (l == null || l.getWorld() == null) return "-";
        return l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    /** 170 ticks -> "8.5 s". */
    static String segundos(int ticks) {
        return Bitacora.num(Math.max(0, ticks) / 20.0) + " s";
    }

    /** La vida que le queda a un objetivo, con la absorcion: para medir lo que quita un golpe a pelo. */
    public static double vida(LivingEntity e) {
        return e == null ? 0 : e.getHealth() + e.getAbsorptionAmount();
    }

    /* ======================================================= el agregador */

    /**
     * Agrupa los intentos en espera por jugador, item y activador: la primera de cada ventana
     * se escribe en el acto; las demas solo se cuentan, y al cerrarse la ventana sale UNA
     * linea con cuantas fueron. Sin Bukkit, para el selftest.
     */
    static final class AgregadorEsperas {

        private static final class Ventana {
            final long desde;
            final String[] cabecera;
            int callados;
            int ultimoTicks;

            Ventana(long desde, String[] cabecera) {
                this.desde = desde;
                this.cabecera = cabecera;
            }
        }

        private final Map<String, Ventana> ventanas = new HashMap<>();

        /** Lo que hay que escribir ahora por este intento (puede ser nada). */
        List<String[]> registrar(String clave, String[] cabecera, int ticks, long ahora, long ventanaMs) {
            List<String[]> out = new ArrayList<>(2);
            Ventana v = this.ventanas.get(clave);
            if (v != null && ahora - v.desde < ventanaMs) {
                v.callados++;
                v.ultimoTicks = ticks;
                return out;
            }
            if (v != null && v.callados > 0) out.add(resumen(v, ventanaMs));
            this.ventanas.put(clave, new Ventana(ahora, cabecera));
            out.add(linea(cabecera, "faltan " + segundos(ticks)));
            return out;
        }

        /** Cierra las ventanas pasadas; devuelve el resumen de las que callaron algo. */
        List<String[]> cerrar(long ahora, long ventanaMs) {
            List<String[]> out = new ArrayList<>();
            for (Iterator<Ventana> it = this.ventanas.values().iterator(); it.hasNext();) {
                Ventana v = it.next();
                if (ahora - v.desde < ventanaMs) continue;
                if (v.callados > 0) out.add(resumen(v, ventanaMs));
                it.remove();
            }
            return out;
        }

        int abiertas() {
            return this.ventanas.size();
        }

        private static String[] resumen(Ventana v, long ventanaMs) {
            return linea(v.cabecera, v.callados + " intento(s) mas en " + (ventanaMs / 1000) + " s",
                    "el ultimo con faltan " + segundos(v.ultimoTicks));
        }

        private static String[] linea(String[] cabecera, String... resto) {
            String[] l = new String[cabecera.length + resto.length];
            System.arraycopy(cabecera, 0, l, 0, cabecera.length);
            System.arraycopy(resto, 0, l, cabecera.length, resto.length);
            return l;
        }
    }

    /* ============================================================ selftest */

    /** Logica pura de la bitacora, para /gi selftest. Lineas "&aOK ..." o "&cFALLO ...". */
    static List<String> autotest() {
        List<String> r = new ArrayList<>();
        java.util.function.BiConsumer<String, Boolean> ok = (que, bien) ->
                r.add(bien ? "&aOK &7bitacora: " + que : "&cFALLO &fbitacora: " + que);
        AgregadorEsperas a = new AgregadorEsperas();
        String[] cab = {"espera", "Dosa__", "MASAMUNE", "CLIC_DERECHO"};
        List<String[]> l1 = a.registrar("k", cab, 100, 0, 30_000);
        ok.accept("el primer intento sale en el acto", l1.size() == 1
                && String.join("|", l1.get(0)).equals("espera|Dosa__|MASAMUNE|CLIC_DERECHO|faltan 5 s"));
        ok.accept("los siguientes de la ventana se callan", a.registrar("k", cab, 80, 1_000, 30_000).isEmpty()
                && a.registrar("k", cab, 60, 2_000, 30_000).isEmpty());
        ok.accept("otro item tiene su propia ventana", a.registrar("k2", cab, 20, 2_000, 30_000).size() == 1);
        ok.accept("la ventana abierta no se cierra antes de tiempo", a.cerrar(29_999, 30_000).isEmpty());
        List<String[]> c = a.cerrar(30_000, 30_000);
        ok.accept("al cerrarla, un resumen con cuantos se callaron", c.size() == 1
                && String.join("|", c.get(0)).equals("espera|Dosa__|MASAMUNE|CLIC_DERECHO|2 intento(s) mas en 30 s"
                        + "|el ultimo con faltan 3 s"));
        ok.accept("la de otro item, abierta mas tarde, sigue abierta", a.abiertas() == 1);
        ok.accept("y se cierra sin linea si no callo nada", a.cerrar(32_000, 30_000).isEmpty() && a.abiertas() == 0);
        a.registrar("k", cab, 100, 40_000, 30_000);
        a.registrar("k", cab, 90, 41_000, 30_000);
        List<String[]> tarde = a.registrar("k", cab, 10, 80_000, 30_000);
        ok.accept("si la ventana vencio sin repaso, el resumen sale antes de la linea nueva", tarde.size() == 2
                && tarde.get(0)[4].startsWith("1 intento") && tarde.get(1)[4].equals("faltan 0.5 s"));
        ok.accept("segundos con decimales solo si hacen falta", segundos(170).equals("8.5 s") && segundos(240).equals("12 s"));
        Set<String> nombres = new LinkedHashSet<>();
        nombresDe(List.of(new Paso.Simple(Args.de("DANO_DEL_GOLPE porcentaje:50"), null, ""),
                new Paso.Espera(5),
                new Paso.Repetir(2, 0, List.of(new Paso.Simple(Args.de("ATRAVESAR"), null, ""),
                        new Paso.Simple(Args.de("DANO_DEL_GOLPE"), null, "")))), nombres);
        ok.accept("las acciones de un bloque, sin repetir y entrando en REPETIR",
                String.join(",", nombres).equals("DANO_DEL_GOLPE,ATRAVESAR"));
        return r;
    }
}
