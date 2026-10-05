package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * M24 · Las hogueras de calma (DIS sec. M24, aprobadas por Dosa el 2026-10-06).
 *
 * Clic derecho con una Esencia en la mano sobre una SOUL_CAMPFIRE encendida en Calamity la consagra
 * (1 Esencia). Mientras arde (4 min): a radio-calma (6) la cordura no baja y se regenera vida despacio,
 * y a radio-apariciones (16) no nace nada. Luego se apaga de verdad (lit=false) y su zona queda
 * quemada 30 min. No se puede consagrar a menos de 48 de otra activa o quemada ni de las puertas, y
 * cada jugador solo una cada 20 min. Nunca es un santuario: la Huella cuenta x1,5 a radio-calma (la
 * calma atrae a la PARCA), y si la PARCA ya viene por alguien la hoguera no le ampara.
 *
 * Bedrock (Geyser): tocar el bloque llega como RIGHT_CLICK_BLOCK con la mano principal, igual que en
 * Java. La Esencia solo se gasta si la consagracion sale; si no, se dice por que en la barra de accion.
 *
 * El nucleo (Nucleo, Ajustes) no toca Bukkit: el autotest "hogueras" prueba las distancias, la pausa,
 * la duracion, la quemada, los radios y la Huella sin jugadores. Aqui arriba queda lo que habla con
 * el servidor: el clic, el bloque, las particulas, el cartel y el guardado.
 *
 * Reinicios: las activas y quemadas y las pausas viven en memoria y se copian en hardcore-datos.yml
 * (hogueras.*). Al parar, cada hoguera activa se apaga y pasa a quemada. Si el servidor se cae con una
 * encendida, al arrancar se apaga en cuanto su mundo este cargado y su zona queda quemada como si
 * hubiera acabado sola. El cartel (TextDisplay) no es persistente: una caida no lo deja en el mundo.
 *
 * Coste: un recorrido por las hogueras activas (pocas) por segundo y jugador, y otro por hoguera.
 */
final class Hogueras implements Listener {

    private static final String RUTA = "hogueras";

    private final Hardcore hc;
    final Nucleo nucleo = new Nucleo();
    /** Hoguera activa (clave) -> su cartel encima. */
    private final Map<String, TextDisplay> carteles = new HashMap<>();
    /** Quien estaba al amparo de una hoguera el segundo anterior (y de cual), para los avisos al entrar y salir. */
    private final Map<UUID, String> amparados = new HashMap<>();
    /** Quien ha pasado por segundo() este segundo; los demas pierden el amparo (y su regeneracion). */
    private final Set<UUID> vistos = new HashSet<>();
    /** Hogueras que quedaron encendidas por una caida: se apagan cuando su mundo este cargado. */
    private final List<Punto> porApagar = new ArrayList<>();
    /** Un clic por tick y jugador (Bedrock puede mandar el toque dos veces). */
    private final Map<UUID, Integer> ultimoClic = new HashMap<>();
    private int segundos;

    Hogueras(Hardcore hc) {
        this.hc = hc;
        cargar();
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("hogueras", Hogueras::autotest);
        Subcomandos.staff().registrar("campfire",
                "campfire <info|clear|reset <player>>: hogueras de calma (ver, apagar todas, quitar la pausa de 20 min)",
                Subcomandos.PERMISO, this::comando, args -> {
                    if (args.length == 2) return List.of("info", "clear", "reset");
                    if (args.length == 3 && "reset".equalsIgnoreCase(args[1])) return Entregas.nombresConectados();
                    return List.of();
                });
    }

    // ================================================================== nucleo

    /** Lo que se lee de hardcore.hogueras, con los valores de DIS M24 si falta. */
    record Ajustes(boolean activa, int coste, double distanciaOtra, double distanciaPuertas, int pausaMinutos,
                   int duracionSegundos, int quemadaMinutos, double radioCalma, double radioApariciones,
                   double factorHuella, boolean regeneracion, int regeneracionNivel, boolean particulas,
                   boolean cartel) {

        static Ajustes de(ConfigurationSection s) {
            if (s == null) s = new YamlConfiguration();
            return new Ajustes(
                    s.getBoolean("activa", true),
                    Math.max(1, s.getInt("coste-esencias", 1)),
                    Math.max(0, s.getDouble("distancia-otra", 48)),
                    Math.max(0, s.getDouble("distancia-puertas", 48)),
                    Math.max(0, s.getInt("pausa-minutos", 20)),
                    Math.max(10, (int) Math.round(s.getDouble("duracion-minutos", 4) * 60)),
                    Math.max(0, s.getInt("quemada-minutos", 30)),
                    Math.max(1, s.getDouble("radio-calma", 6)),
                    Math.max(0, s.getDouble("radio-apariciones", 16)),
                    Math.max(1, s.getDouble("factor-huella", 1.5)),
                    s.getBoolean("regeneracion", true),
                    Math.max(1, Math.min(3, s.getInt("regeneracion-nivel", 1))),
                    s.getBoolean("particulas", true),
                    s.getBoolean("cartel", true));
        }

        static Ajustes defecto() {
            return de(null);
        }

        long duracionMs() {
            return duracionSegundos * 1000L;
        }

        long quemadaMs() {
            return quemadaMinutos * 60_000L;
        }

        long pausaMs() {
            return pausaMinutos * 60_000L;
        }
    }

    /** Una hoguera (activa o quemada): el bloque, quien la consagro y hasta cuando dura ese estado. */
    record Punto(String mundo, int x, int y, int z, UUID dueno, long hasta) {

        String clave() {
            return mundo + ";" + x + ";" + y + ";" + z;
        }

        /** Distancia desde el centro del bloque; infinita en otro mundo. */
        double distancia(String m, double px, double py, double pz) {
            if (!mundo.equals(m)) return Double.MAX_VALUE;
            double dx = x + 0.5 - px, dy = y + 0.5 - py, dz = z + 0.5 - pz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        Punto hasta(long otro) {
            return new Punto(mundo, x, y, z, dueno, otro);
        }

        /** Para hardcore-datos.yml: mundo;x;y;z;uuid;hasta. */
        String linea() {
            return clave() + ";" + (dueno == null ? "-" : dueno) + ";" + hasta;
        }

        static Punto de(String linea) {
            if (linea == null) return null;
            String[] p = linea.split(";");
            if (p.length != 6) return null;
            try {
                UUID u = "-".equals(p[4]) ? null : UUID.fromString(p[4]);
                return new Punto(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), u,
                        Long.parseLong(p[5]));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** Por que no se puede consagrar. */
    enum Fallo { YA_CONSAGRADA, ACTIVA_CERCA, QUEMADA_CERCA, PUERTAS, PAUSA }

    /** El estado de todas las hogueras, sin Bukkit. Solo hilo principal. */
    static final class Nucleo {
        final Map<String, Punto> activas = new LinkedHashMap<>();
        final List<Punto> quemadas = new ArrayList<>();
        /** Jugador -> cuando consagro la ultima (millis). */
        final Map<UUID, Long> ultima = new HashMap<>();

        /** Null si se puede consagrar esa hoguera; si no, el primer motivo que lo impide. */
        Fallo puede(Ajustes a, String mundo, int x, int y, int z, UUID quien, double distanciaPuertas, long ahora) {
            podar(ahora);
            Punto aqui = new Punto(mundo, x, y, z, quien, 0);
            if (activas.containsKey(aqui.clave())) return Fallo.YA_CONSAGRADA;
            double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
            for (Punto q : quemadas) if (q.distancia(mundo, cx, cy, cz) < a.distanciaOtra()) return Fallo.QUEMADA_CERCA;
            for (Punto o : activas.values()) if (o.distancia(mundo, cx, cy, cz) < a.distanciaOtra()) return Fallo.ACTIVA_CERCA;
            if (distanciaPuertas < a.distanciaPuertas()) return Fallo.PUERTAS;
            if (pausaRestante(a, quien, ahora) > 0) return Fallo.PAUSA;
            return null;
        }

        /** Lo que le falta a ese jugador para poder consagrar otra (millis), 0 si puede. */
        long pausaRestante(Ajustes a, UUID quien, long ahora) {
            Long antes = quien == null ? null : ultima.get(quien);
            if (antes == null) return 0;
            long queda = antes + a.pausaMs() - ahora;
            if (queda <= 0) {
                ultima.remove(quien);
                return 0;
            }
            return queda;
        }

        Punto consagrar(Ajustes a, String mundo, int x, int y, int z, UUID quien, long ahora) {
            Punto p = new Punto(mundo, x, y, z, quien, ahora + a.duracionMs());
            activas.put(p.clave(), p);
            if (quien != null) ultima.put(quien, ahora);
            return p;
        }

        /** Saca las activas que ya han acabado, las pasa a quemadas y las devuelve (para apagarlas). */
        List<Punto> caducadas(Ajustes a, long ahora) {
            List<Punto> fuera = new ArrayList<>();
            Iterator<Punto> it = activas.values().iterator();
            while (it.hasNext()) {
                Punto p = it.next();
                if (ahora < p.hasta()) continue;
                it.remove();
                fuera.add(p);
                quemar(a, p, p.hasta());
            }
            podar(ahora);
            return fuera;
        }

        /** Acaba una antes de tiempo (la han roto o apagado): pasa a quemada desde ahora. */
        Punto apagar(Ajustes a, String clave, long ahora) {
            Punto p = activas.remove(clave);
            if (p != null) quemar(a, p, ahora);
            return p;
        }

        void quemar(Ajustes a, Punto p, long desde) {
            quemadas.add(p.hasta(desde + a.quemadaMs()));
        }

        void podar(long ahora) {
            quemadas.removeIf(q -> ahora >= q.hasta());
        }

        /** La activa que ampara ese sitio (a radio o menos), o null. */
        Punto dentro(String mundo, double x, double y, double z, double radio) {
            Punto mejor = null;
            double d = Double.MAX_VALUE;
            for (Punto p : activas.values()) {
                double e = p.distancia(mundo, x, y, z);
                if (e <= radio && e < d) {
                    d = e;
                    mejor = p;
                }
            }
            return mejor;
        }
    }

    /** Los segundos quieto que ve la Huella: x factor dentro de la calma (la calma atrae a la PARCA). */
    static int quietoEfectivo(int quieto, double factor) {
        if (factor <= 1 || quieto <= 0) return quieto;
        return (int) Math.min(Integer.MAX_VALUE, Math.round(quieto * factor));
    }

    /**
     * Si la regeneracion que lleva alguien es la de la hoguera (y se puede renovar o quitar). Lo de otra
     * fuente (pocion, baliza, manzana dorada) no se toca ni se le suma nada: sin apilar.
     */
    static boolean esDeLaHoguera(int amplificador, int duracionTicks, boolean ambiente, Ajustes a) {
        return ambiente && amplificador == a.regeneracionNivel() - 1 && duracionTicks <= TICKS_REGENERACION;
    }

    /**
     * Lo que dura cada dosis de regeneracion: 100 ticks, multiplo de los 50 del nivel I, para que cada dosis
     * cure lo mismo que la pocion (no se renueva a medias: renovar cada segundo curaria mas de la cuenta).
     */
    static final int TICKS_REGENERACION = 100;

    // ================================================================== Bukkit

    Ajustes ajustes() {
        return Ajustes.de(hc.cfg().getConfigurationSection("hogueras"));
    }

    /** La activa que ampara a ese jugador (radio-calma), o null. */
    private Punto calmaDe(Location l, Ajustes a) {
        if (l == null || l.getWorld() == null || nucleo.activas.isEmpty()) return null;
        return nucleo.dentro(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), a.radioCalma());
    }

    /**
     * Si la cordura de ese jugador no baja por la hoguera. La PARCA que ya viene por el no se detiene:
     * a quien persigue, la hoguera no le ampara.
     */
    boolean amparo(Player p) {
        if (p == null || nucleo.activas.isEmpty()) return false;
        Ajustes a = ajustes();
        if (!a.activa() || calmaDe(p.getLocation(), a) == null) return false;
        return !persigue(p);
    }

    private boolean persigue(Player p) {
        return hc.parca() != null && hc.valor("parca", () -> hc.parca().persigue(p), false);
    }

    /** Si en ese sitio no nace nada (radio-apariciones de una activa). */
    boolean sinApariciones(Location l) {
        if (l == null || l.getWorld() == null || nucleo.activas.isEmpty()) return false;
        Ajustes a = ajustes();
        return a.activa() && nucleo.dentro(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
                a.radioApariciones()) != null;
    }

    /** Lo que multiplica la Huella de ese jugador: factor-huella a radio-calma de una activa, 1 si no. */
    double factorHuella(Player p) {
        if (p == null || nucleo.activas.isEmpty()) return 1;
        Ajustes a = ajustes();
        return a.activa() && calmaDe(p.getLocation(), a) != null ? a.factorHuella() : 1;
    }

    // ------------------------------------------------------------------ el clic

    /**
     * Clic derecho (o toque en Bedrock) con una Esencia en la mano principal sobre una fogata de almas.
     * HIGH y sin ignoreCancelled: si una proteccion ha negado el uso del bloque se dice y no se gasta nada.
     * A partir de aqui el clic es de la hoguera: ni vanilla ni otro modulo hacen nada con la Esencia.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void alTocar(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.SOUL_CAMPFIRE || !hc.esHardcore(b.getWorld())) return;
        Player p = e.getPlayer();
        ItemStack mano = p.getInventory().getItemInMainHand();
        if (hc.items() == null || !hc.items().esEsencia(mano)) return;
        Ajustes a = ajustes();
        if (!a.activa()) return;
        boolean protegido = e.useInteractedBlock() == Event.Result.DENY;
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        int tick = hc.plugin().getServer().getCurrentTick();
        Integer antes = ultimoClic.put(p.getUniqueId(), tick);
        if (antes != null && antes == tick) return;
        if (protegido) {
            decir(p, Component.text("Aquí no se puede consagrar una hoguera.", Paleta.TEXTO));
            return;
        }
        consagrar(p, b, a);
    }

    private void consagrar(Player p, Block b, Ajustes a) {
        if (!(b.getBlockData() instanceof Lightable luz) || !luz.isLit()) {
            decir(p, Component.text("La hoguera tiene que estar encendida.", Paleta.TEXTO));
            return;
        }
        Location centro = b.getLocation().add(0.5, 0.5, 0.5);
        if (hc.enSpawn(centro)) {
            decir(p, Component.text("En el spawn no hace falta: aquí la cordura ya no baja.", Paleta.TEXTO));
            return;
        }
        long ahora = System.currentTimeMillis();
        Fallo f = nucleo.puede(a, b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), p.getUniqueId(),
                distanciaPuertas(centro), ahora);
        if (f != null) {
            decir(p, motivo(f, a, nucleo.pausaRestante(a, p.getUniqueId(), ahora)));
            return;
        }
        boolean gratis = p.getGameMode() == GameMode.CREATIVE;
        ItemStack mano = p.getInventory().getItemInMainHand();
        if (!gratis) {
            if (!hc.items().esEsencia(mano) || mano.getAmount() < a.coste()) {
                decir(p, Component.text("Necesitas ", Paleta.TEXTO).append(Component.text(a.coste() + " Esencias", Paleta.CIFRA))
                        .append(Component.text(" en la mano.", Paleta.TEXTO)));
                return;
            }
            mano.setAmount(mano.getAmount() - a.coste());
            p.getInventory().setItemInMainHand(mano.getAmount() <= 0 ? null : mano);
        }
        Punto h = nucleo.consagrar(a, b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), p.getUniqueId(), ahora);
        guardar(true);
        // P-G01.
        p.sendMessage(ComandoCalamity.mensaje("La hoguera arde. Aquí el miedo espera, pero no se va."));
        World w = b.getWorld();
        Compat.sound(w, centro, "block.respawn_anchor.charge", 1.0f, 0.7f);
        Compat.sound(w, centro, "particle.soul_escape", 1.2f, 0.9f);
        w.spawnParticle(Particle.SOUL, centro.clone().add(0, 0.4, 0), 14, 0.35, 0.4, 0.35, 0.03);
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, centro.clone().add(0, 0.3, 0), 10, 0.2, 0.2, 0.2, 0.04);
        anillo(w, h, a, 1.0);
        hc.plugin().bitacora().anotar("hogueras", "consagra", p.getName(), h.clave(), gratis ? "creativo" : a.coste() + " esencias");
    }

    /** La distancia a la puerta mas cercana (la caja de la salida y la de entrada, y el punto de llegada). */
    private double distanciaPuertas(Location l) {
        double d = Double.MAX_VALUE;
        VaraPortales v = hc.vara();
        if (v != null) {
            d = Math.min(d, v.distancia(l, "salida"));
            d = Math.min(d, v.distancia(l, "entrada"));
        }
        Location llegada = hc.punto("llegada");
        if (llegada != null && llegada.getWorld() == l.getWorld()) d = Math.min(d, llegada.distance(l));
        return d;
    }

    private static Component motivo(Fallo f, Ajustes a, long pausa) {
        return switch (f) {
            case YA_CONSAGRADA -> Component.text("Esta hoguera ya está consagrada.", Paleta.TEXTO);
            // P-G03.
            case QUEMADA_CERCA -> Component.text("Esta tierra ya ardió. Busca otra.", Paleta.TEXTO);
            case ACTIVA_CERCA -> Component.text("Ya arde otra hoguera cerca. Aléjate ", Paleta.TEXTO)
                    .append(Component.text(Math.round(a.distanciaOtra()) + " bloques", Paleta.CIFRA))
                    .append(Component.text(".", Paleta.TEXTO));
            case PUERTAS -> Component.text("Muy cerca de la puerta. Aléjate ", Paleta.TEXTO)
                    .append(Component.text(Math.round(a.distanciaPuertas()) + " bloques", Paleta.CIFRA))
                    .append(Component.text(".", Paleta.TEXTO));
            case PAUSA -> Component.text("Aún no puedes consagrar otra. Espera ", Paleta.TEXTO)
                    .append(Component.text((pausa / 60_000 + 1) + " min", Paleta.CIFRA))
                    .append(Component.text(".", Paleta.TEXTO));
        };
    }

    /** Lo que se le dice al que toca: en la barra de accion (por BarraAccion, con su reserva). */
    private void decir(Player p, Component texto) {
        hc.cordura().destello(p, texto, 3);
        p.playSound(p.getLocation(), "block.fire.extinguish", 0.4f, 1.6f);
    }

    // ------------------------------------------------------------------ cada segundo

    /** Por jugador que cuenta dentro, desde Hardcore.tick: avisos al entrar y salir y la regeneracion. */
    void segundo(Player p, boolean spawn) {
        vistos.add(p.getUniqueId());
        if (nucleo.activas.isEmpty() && !amparados.containsKey(p.getUniqueId())) return;
        Ajustes a = ajustes();
        Punto cual = spawn || !a.activa() ? null : calmaDe(p.getLocation(), a);
        boolean dentro = cual != null;
        boolean parca = dentro && persigue(p);
        UUID u = p.getUniqueId();
        String antes = dentro ? amparados.put(u, cual.clave()) : amparados.remove(u);
        if (dentro && antes == null) {
            hc.cordura().destello(p, parca
                    ? Component.text("La hoguera no te ampara", Paleta.ALMA).append(Component.text(": la Parca ya viene por ti.", Paleta.TEXTO))
                    : Component.text("Hoguera de calma", Paleta.ALMA).append(Component.text(": aquí la cordura no baja.", Paleta.TEXTO)), 3);
        } else if (!dentro && antes != null) {
            quitarRegeneracion(p, a);
            // Si la que le amparaba se ha apagado ya lo dijo P-G02; esto es solo para quien se aleja.
            if (!spawn && nucleo.activas.containsKey(antes)) hc.cordura().destello(p, Component.text("Te alejas de la hoguera", Paleta.TENUE)
                    .append(Component.text(": la cordura vuelve a bajar.", Paleta.TEXTO)), 2);
        }
        if (!dentro) return;
        if (parca || !a.regeneracion()) {
            quitarRegeneracion(p, a);
            return;
        }
        PotionEffect actual = p.getPotionEffect(PotionEffectType.REGENERATION);
        // Sin apilar: con regeneracion de otra fuente no se hace nada; la propia se pone al acabar.
        if (actual != null) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, TICKS_REGENERACION,
                a.regeneracionNivel() - 1, true, false, true));
    }

    private static void quitarRegeneracion(Player p, Ajustes a) {
        PotionEffect actual = p.getPotionEffect(PotionEffectType.REGENERATION);
        if (actual != null && esDeLaHoguera(actual.getAmplifier(), actual.getDuration(), actual.isAmbient(), a)) {
            p.removePotionEffect(PotionEffectType.REGENERATION);
        }
    }

    /** Una vez por segundo, desde Hardcore.tick: caducar, apagar, particulas, carteles y ceniza. */
    void tick() {
        segundos++;
        Ajustes a = ajustes();
        long ahora = System.currentTimeMillis();
        // Quien ya no ha pasado por segundo() (salio, murio, se desconecto, espectador) pierde el amparo.
        ultimoClic.clear();
        for (Iterator<UUID> it = amparados.keySet().iterator(); it.hasNext(); ) {
            UUID u = it.next();
            if (vistos.contains(u)) continue;
            it.remove();
            Player p = hc.plugin().getServer().getPlayer(u);
            if (p != null) quitarRegeneracion(p, a);
        }
        vistos.clear();
        apagarPendientes();

        boolean cambio = false;
        for (Punto p : nucleo.caducadas(a, ahora)) {
            apagarBloque(p, true);
            cambio = true;
        }
        // Rota o apagada a mano (agua, pala) antes de tiempo: se acaba ya y la zona queda quemada.
        for (Punto p : new ArrayList<>(nucleo.activas.values())) {
            Block b = bloque(p, false);
            if (b == null) continue;
            if (b.getType() == Material.SOUL_CAMPFIRE && b.getBlockData() instanceof Lightable l && l.isLit()) continue;
            nucleo.apagar(a, p.clave(), ahora);
            apagarBloque(p, true);
            cambio = true;
        }
        if (cambio) guardar(false);
        for (Punto p : nucleo.activas.values()) pintar(p, a, ahora);
        // Carteles de hogueras que ya no estan.
        carteles.entrySet().removeIf(en -> {
            if (nucleo.activas.containsKey(en.getKey())) return false;
            if (en.getValue() != null && en.getValue().isValid()) en.getValue().remove();
            return true;
        });
        if (a.particulas() && segundos % 3 == 0) {
            for (Punto q : nucleo.quemadas) ceniza(q);
        }
    }

    /** Las llamas que cambian mientras arde, el anillo de su radio y el cartel con lo que le queda. */
    private void pintar(Punto h, Ajustes a, long ahora) {
        Block b = bloque(h, false);
        if (b == null) return;
        World w = b.getWorld();
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        if (!hayCerca(w, c, 32)) {
            quitarCartel(h.clave());
            return;
        }
        long queda = Math.max(0, h.hasta() - ahora);
        double t = Math.max(0, Math.min(1, queda / (double) a.duracionMs()));
        if (a.particulas()) {
            // Llamas de alma que se van apagando: de cuatro a una, y humo los ultimos 20 s.
            int llamas = 1 + (int) Math.round(3 * t);
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, c.clone().add(0, 0.35, 0), llamas, 0.18, 0.15, 0.18, 0.012);
            if (segundos % 2 == 0) w.spawnParticle(Particle.SOUL, c.clone().add(0, 0.6, 0), 1, 0.2, 0.1, 0.2, 0.02);
            if (queda <= 20_000) w.spawnParticle(Particle.SMOKE, c.clone().add(0, 0.6, 0), 2, 0.15, 0.1, 0.15, 0.01);
            if (segundos % 4 == 0) anillo(w, h, a, t);
        }
        if (a.cartel()) cartel(h, c, queda);
        else quitarCartel(h.clave());
    }

    /** El borde del radio de calma, del color del alma a ceniza segun se gasta. Pocas motas. */
    private void anillo(World w, Punto h, Ajustes a, double t) {
        if (!a.particulas()) return;
        Color color = mezcla(Paleta.CENIZA, Paleta.ALMA, t);
        Particle.DustOptions polvo = new Particle.DustOptions(color, 0.9f);
        int puntos = 24;
        double r = a.radioCalma();
        for (int i = 0; i < puntos; i++) {
            double ang = (Math.PI * 2 * i) / puntos;
            Location l = new Location(w, h.x() + 0.5 + Math.cos(ang) * r, h.y() + 0.2, h.z() + 0.5 + Math.sin(ang) * r);
            w.spawnParticle(Particle.DUST, l, 1, 0, 0.05, 0, 0, polvo);
        }
    }

    private static Color mezcla(TextColor desde, TextColor hasta, double t) {
        int r = (int) Math.round(desde.red() + (hasta.red() - desde.red()) * t);
        int g = (int) Math.round(desde.green() + (hasta.green() - desde.green()) * t);
        int bl = (int) Math.round(desde.blue() + (hasta.blue() - desde.blue()) * t);
        return Color.fromRGB(r, g, bl);
    }

    /** El cartel pequeno encima: "Hoguera de calma" y la cuenta atras. Solo lo ve quien esta cerca. */
    private void cartel(Punto h, Location c, long queda) {
        TextDisplay d = carteles.get(h.clave());
        if (d == null || !d.isValid()) {
            d = c.getWorld().spawn(c.clone().add(0, 1.1, 0), TextDisplay.class, e -> {
                e.setPersistent(false);
                e.setBillboard(Display.Billboard.CENTER);
                e.setSeeThrough(false);
                e.setShadowed(true);
                e.setViewRange(0.3f);
                e.setLineWidth(160);
                e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            });
            carteles.put(h.clave(), d);
        }
        d.text(Component.text("Hoguera de calma", Paleta.ALMA).append(Component.newline())
                .append(Component.text(reloj(queda), Paleta.CIFRA)));
    }

    private void quitarCartel(String clave) {
        TextDisplay d = carteles.remove(clave);
        if (d != null && d.isValid()) d.remove();
    }

    /** 3:05 */
    static String reloj(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    /** Ceniza de vez en cuando sobre la tierra quemada, si hay alguien cerca para verla. */
    private void ceniza(Punto q) {
        Block b = bloque(q, false);
        if (b == null) return;
        World w = b.getWorld();
        Location c = b.getLocation().add(0.5, 0.8, 0.5);
        if (!hayCerca(w, c, 32)) return;
        w.spawnParticle(Particle.ASH, c, 8, 2.5, 0.6, 2.5, 0.01);
        w.spawnParticle(Particle.WHITE_ASH, c, 3, 1.5, 0.4, 1.5, 0.01);
        if (segundos % 9 == 0) w.spawnParticle(Particle.SMOKE, c, 2, 0.1, 0.1, 0.1, 0.01);
    }

    private static boolean hayCerca(World w, Location c, double r) {
        double r2 = r * r;
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(c) <= r2) return true;
        return false;
    }

    /** El bloque de una hoguera; null si su mundo no esta, o su chunk no esta cargado y no se pide cargarlo. */
    private Block bloque(Punto h, boolean cargar) {
        World w = hc.plugin().getServer().getWorld(h.mundo());
        if (w == null) return null;
        if (!cargar && !w.isChunkLoaded(h.x() >> 4, h.z() >> 4)) return null;
        return w.getBlockAt(h.x(), h.y(), h.z());
    }

    /**
     * La apaga de verdad (lit=false) si sigue siendo una fogata de almas encendida, quita su cartel y, con
     * aviso, suena y dice P-G02 a quien este a radio-apariciones. Carga el chunk si hace falta: una vez,
     * al acabar, para que no quede encendida para siempre en un chunk descargado.
     */
    private void apagarBloque(Punto h, boolean aviso) {
        quitarCartel(h.clave());
        Block b = bloque(h, true);
        if (b == null) return;
        if (b.getType() == Material.SOUL_CAMPFIRE && b.getBlockData() instanceof Lightable l && l.isLit()) {
            l.setLit(false);
            b.setBlockData(l, false);
        }
        if (!aviso) return;
        World w = b.getWorld();
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        Compat.sound(w, c, "block.fire.extinguish", 1.0f, 0.7f);
        Compat.sound(w, c, "block.respawn_anchor.deplete", 0.8f, 0.8f);
        w.spawnParticle(Particle.LARGE_SMOKE, c.clone().add(0, 0.3, 0), 10, 0.25, 0.3, 0.25, 0.02);
        w.spawnParticle(Particle.ASH, c.clone().add(0, 0.5, 0), 20, 1.2, 0.5, 1.2, 0.01);
        double r = Math.max(ajustes().radioApariciones(), ajustes().radioCalma());
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(c) > r * r) continue;
            // P-G02.
            hc.cordura().destello(p, Component.text("La hoguera se apaga.", Paleta.CENIZA), 2);
        }
    }

    /** Las que dejo encendidas una caida: se apagan en cuanto su mundo esta cargado. */
    private void apagarPendientes() {
        if (porApagar.isEmpty()) return;
        for (Iterator<Punto> it = porApagar.iterator(); it.hasNext(); ) {
            Punto p = it.next();
            if (hc.plugin().getServer().getWorld(p.mundo()) == null) continue;
            apagarBloque(p, false);
            it.remove();
        }
        guardar(false);
    }

    // ------------------------------------------------------------------ guardado

    /**
     * Lee hogueras.* de hardcore-datos.yml. Lo que quedo "activo" es de una caida: no vuelve a arder, se
     * apaga en cuanto se pueda y su zona queda quemada como si hubiera acabado sola.
     */
    private void cargar() {
        YamlConfiguration d = hc.datos();
        Ajustes a = ajustes();
        long ahora = System.currentTimeMillis();
        for (String s : d.getStringList(RUTA + ".activas")) {
            Punto p = Punto.de(s);
            if (p == null) continue;
            porApagar.add(p);
            nucleo.quemar(a, p, Math.min(p.hasta(), ahora));
        }
        for (String s : d.getStringList(RUTA + ".por-apagar")) {
            Punto p = Punto.de(s);
            if (p != null) porApagar.add(p);
        }
        for (String s : d.getStringList(RUTA + ".quemadas")) {
            Punto p = Punto.de(s);
            if (p != null) nucleo.quemadas.add(p);
        }
        ConfigurationSection pausas = d.getConfigurationSection(RUTA + ".pausas");
        if (pausas != null) {
            for (String k : pausas.getKeys(false)) {
                try {
                    nucleo.ultima.put(UUID.fromString(k), pausas.getLong(k));
                } catch (IllegalArgumentException ignorada) {
                    // Una clave rota no tumba el resto.
                }
            }
        }
        nucleo.podar(ahora);
        if (!porApagar.isEmpty()) {
            hc.plugin().getLogger().info("[Calamity] Hogueras de calma: " + porApagar.size()
                    + " quedaron encendidas de antes; se apagan y su zona queda quemada.");
        }
        guardar(false);
    }

    /** Copia el estado en hardcore-datos.yml (ya, o con el guardado del minuto). */
    private void guardar(boolean ya) {
        YamlConfiguration d = hc.datos();
        long ahora = System.currentTimeMillis();
        nucleo.podar(ahora);
        d.set(RUTA + ".activas", nucleo.activas.values().stream().map(Punto::linea).toList());
        d.set(RUTA + ".por-apagar", porApagar.isEmpty() ? null : porApagar.stream().map(Punto::linea).toList());
        d.set(RUTA + ".quemadas", nucleo.quemadas.stream().map(Punto::linea).toList());
        d.set(RUTA + ".pausas", null);
        Ajustes a = ajustes();
        for (Map.Entry<UUID, Long> e : new ArrayList<>(nucleo.ultima.entrySet())) {
            if (nucleo.pausaRestante(a, e.getKey(), ahora) > 0) d.set(RUTA + ".pausas." + e.getKey(), e.getValue());
        }
        if (ya) hc.guardarYa();
        else hc.marcarSucio();
    }

    /** Al parar las reglas: ninguna se queda ardiendo ni con cartel; las activas pasan a quemadas. */
    void parar() {
        HandlerList.unregisterAll(this);
        Ajustes a = ajustes();
        long ahora = System.currentTimeMillis();
        for (Punto p : new ArrayList<>(nucleo.activas.values())) {
            nucleo.apagar(a, p.clave(), ahora);
            apagarBloque(p, false);
        }
        for (TextDisplay d : carteles.values()) if (d != null && d.isValid()) d.remove();
        carteles.clear();
        for (UUID u : amparados.keySet()) {
            Player p = hc.plugin().getServer().getPlayer(u);
            if (p != null) quitarRegeneracion(p, a);
        }
        amparados.clear();
        vistos.clear();
        guardar(false);
    }

    // ------------------------------------------------------------------ comando

    private void comando(CommandSender quien, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "info";
        Ajustes a = ajustes();
        long ahora = System.currentTimeMillis();
        switch (sub) {
            case "clear" -> {
                int n = nucleo.activas.size();
                for (Punto p : new ArrayList<>(nucleo.activas.values())) {
                    nucleo.activas.remove(p.clave());
                    apagarBloque(p, true);
                }
                int q = nucleo.quemadas.size();
                nucleo.quemadas.clear();
                nucleo.ultima.clear();
                guardar(true);
                quien.sendMessage(ComandoCalamity.mensaje("Hogueras apagadas: " + n + ". Zonas quemadas borradas: " + q
                        + ". Pausas de los jugadores borradas."));
            }
            case "reset" -> {
                if (args.length < 3) {
                    quien.sendMessage(ComandoCalamity.mensaje("Uso: /calamity campfire reset <player>"));
                    return;
                }
                Player p = hc.plugin().getServer().getPlayerExact(args[2]);
                if (p == null) {
                    quien.sendMessage(ComandoCalamity.mensaje("No está conectado: " + args[2]));
                    return;
                }
                nucleo.ultima.remove(p.getUniqueId());
                guardar(false);
                quien.sendMessage(ComandoCalamity.mensaje(p.getName() + " ya puede consagrar otra hoguera."));
            }
            default -> {
                nucleo.podar(ahora);
                quien.sendMessage(ComandoCalamity.mensaje("Hogueras de calma: " + (a.activa() ? "activas" : "apagadas en la config")
                        + ". Arden " + nucleo.activas.size() + ", quemadas " + nucleo.quemadas.size()
                        + ", jugadores en pausa " + nucleo.ultima.size() + "."));
                for (Punto p : nucleo.activas.values()) {
                    quien.sendMessage(Component.text("  arde  " + p.mundo() + " " + p.x() + " " + p.y() + " " + p.z()
                            + "  queda " + reloj(p.hasta() - ahora) + "  de " + nombre(p.dueno()), Paleta.TENUE));
                }
                for (Punto p : nucleo.quemadas) {
                    quien.sendMessage(Component.text("  quemada  " + p.mundo() + " " + p.x() + " " + p.y() + " " + p.z()
                            + "  queda " + reloj(p.hasta() - ahora), Paleta.TENUE));
                }
            }
        }
    }

    private String nombre(UUID u) {
        if (u == null) return "?";
        String n = hc.plugin().getServer().getOfflinePlayer(u).getName();
        return n == null ? u.toString() : n;
    }

    // ------------------------------------------------------------------ autotest

    /**
     * Sin jugadores ni mundo: distancias (48 a otra activa o quemada y a las puertas), la pausa de 20 min por
     * jugador, la duracion de 4 min, la quemada de 30 min, los radios 6 y 16, la Huella x1,5, la
     * regeneracion sin apilar y el guardado de una linea. Con los valores de DIS M24, no los de la config.
     */
    static List<String> autotest() {
        Ajustes a = Ajustes.defecto();
        Autotest.Hoja h = new Autotest.Hoja();
        UUID u1 = Autotest.sintetico(1), u2 = Autotest.sintetico(2), u3 = Autotest.sintetico(3);
        final long t0 = 1_000_000_000L;
        final long min = 60_000L;
        final String m = "calamity";

        h.igual("valores de serie: 48 / 48 / 20 min / 4 min / 30 min / 6 / 16 / x1,5",
                "48.0/48.0/20/240/30/6.0/16.0/1.5",
                a.distanciaOtra() + "/" + a.distanciaPuertas() + "/" + a.pausaMinutos() + "/" + a.duracionSegundos()
                        + "/" + a.quemadaMinutos() + "/" + a.radioCalma() + "/" + a.radioApariciones() + "/" + a.factorHuella());

        // Distancia a otra activa: 47 no, 48 si (de centro a centro).
        Nucleo n = new Nucleo();
        h.igual("la primera se consagra", null, n.puede(a, m, 0, 64, 0, u1, 1000, t0));
        n.consagrar(a, m, 0, 64, 0, u1, t0);
        h.igual("la misma otra vez", Fallo.YA_CONSAGRADA, n.puede(a, m, 0, 64, 0, u2, 1000, t0));
        h.igual("otra activa a 47", Fallo.ACTIVA_CERCA, n.puede(a, m, 47, 64, 0, u2, 1000, t0));
        h.igual("otra activa a 48", null, n.puede(a, m, 48, 64, 0, u2, 1000, t0));
        h.igual("a 47 pero en otro mundo", null, n.puede(a, "otro", 47, 64, 0, u2, 1000, t0));

        // Puertas: 47,9 no, 48 si.
        h.igual("puerta a 47,9", Fallo.PUERTAS, n.puede(a, m, 500, 64, 0, u2, 47.9, t0));
        h.igual("puerta a 48", null, n.puede(a, m, 500, 64, 0, u2, 48, t0));

        // Pausa de 20 min por jugador (lejos de todo).
        h.igual("el mismo jugador a los 19 min", Fallo.PAUSA, n.puede(a, m, 1000, 64, 0, u1, 1000, t0 + 19 * min));
        h.ok("le quedan 60 s a los 19 min", n.pausaRestante(a, u1, t0 + 19 * min) == min);
        h.igual("el mismo jugador a los 20 min", null, n.puede(a, m, 1000, 64, 0, u1, 1000, t0 + 20 * min));
        h.igual("otro jugador no tiene pausa", null, n.puede(a, m, 1000, 64, 0, u3, 1000, t0 + min));

        // Radios: calma a 6 (no a 6,01), nada nace a 16 (no a 16,01). Del centro del bloque.
        h.ok("calma a 6", n.dentro(m, 0.5 + 6, 64.5, 0.5, a.radioCalma()) != null);
        h.ok("sin calma a 6,01", n.dentro(m, 0.5 + 6.01, 64.5, 0.5, a.radioCalma()) == null);
        h.ok("nada nace a 16", n.dentro(m, 0.5, 64.5, 0.5 + 16, a.radioApariciones()) != null);
        h.ok("se nace a 16,01", n.dentro(m, 0.5, 64.5, 0.5 + 16.01, a.radioApariciones()) == null);

        // Duracion 4 min: a los 3:59,999 sigue; a los 4:00 se apaga y queda quemada.
        h.ok("a 3:59 sigue ardiendo", n.caducadas(a, t0 + 4 * min - 1).isEmpty() && n.activas.size() == 1);
        List<Punto> fuera = n.caducadas(a, t0 + 4 * min);
        h.ok("a 4:00 se apaga (" + fuera.size() + ") y queda quemada (" + n.quemadas.size() + ")",
                fuera.size() == 1 && n.activas.isEmpty() && n.quemadas.size() == 1);
        h.ok("apagada ya no ampara", n.dentro(m, 0.5, 64.5, 0.5, a.radioCalma()) == null);

        // Quemada 30 min (desde que se apaga): a 47 no se puede ni a los 29 min; a los 30 si.
        long apagada = t0 + 4 * min;
        h.igual("quemada: otra a 10 a los 29 min", Fallo.QUEMADA_CERCA, n.puede(a, m, 10, 64, 0, u2, 1000, apagada + 29 * min));
        h.igual("quemada: la misma a los 29 min", Fallo.QUEMADA_CERCA, n.puede(a, m, 0, 64, 0, u2, 1000, apagada + 29 * min));
        h.igual("quemada: a 48 si", null, n.puede(a, m, 48, 64, 0, u2, 1000, apagada + 29 * min));
        h.igual("quemada: a los 30 min ya se puede", null, n.puede(a, m, 10, 64, 0, u2, 1000, apagada + 30 * min));
        h.ok("quemada podada a los 30 min", n.quemadas.isEmpty());

        // Rota o apagada a mano: quemada desde ese momento.
        Nucleo r = new Nucleo();
        Punto p = r.consagrar(a, m, 0, 64, 0, u1, t0);
        r.apagar(a, p.clave(), t0 + min);
        h.ok("rota al minuto: quemada hasta el 31", r.activas.isEmpty() && r.quemadas.size() == 1
                && r.quemadas.get(0).hasta() == t0 + 31 * min);

        // La Huella x1,5 dentro: con la PARCA a 300 s, llega a los 200 s quieto.
        h.igual("huella x1,5: 200 s cuentan 300", 300, quietoEfectivo(200, a.factorHuella()));
        h.igual("huella fuera: 200 s cuentan 200", 200, quietoEfectivo(200, 1));
        h.ok("huella x1,5: a 199 s aun no llega a 300", quietoEfectivo(199, a.factorHuella()) < 300);

        // Regeneracion sin apilar: solo se toca la propia (ambiente, nivel I, <= 100 ticks).
        h.ok("regeneracion propia se reconoce", esDeLaHoguera(0, TICKS_REGENERACION, true, a));
        h.ok("pocion de regeneracion no se toca", !esDeLaHoguera(0, 900, false, a));
        h.ok("baliza (nivel II) no se toca", !esDeLaHoguera(1, 80, true, a));

        // Una linea de hardcore-datos.yml y vuelta.
        Punto ida = new Punto(m, -12, 70, 340, u2, t0 + 5);
        h.igual("guardado de una linea", ida, Punto.de(ida.linea()));
        h.ok("una linea rota no revienta", Punto.de("calamity;1;2") == null && Punto.de("a;b;c;d;e;f") == null);

        // Sin seccion en la config: valores de serie y activa.
        h.ok("sin seccion: activa", Ajustes.de(null).activa());
        h.igual("reloj 4:00", "4:00", reloj(4 * min));
        h.igual("reloj 0:05 (redondea hacia arriba)", "0:05", reloj(4_001));
        return h.lineas();
    }
}
