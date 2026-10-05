package net.ederus.calamity.hardcore;

import net.ederus.edm.EDMPlugin;
import net.ederus.edm.superbeacon.SuperBeaconPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calamity 1.11 · El ranking semanal de clanes y el Trofeo de Temporada.
 *
 * El clan de un jugador sale de PlaceholderAPI (clanes.placeholder, %uclans_tag_color% de
 * UltimateClans) sin colores; vacio, "Sin clan", "N/A" o "-" (clanes.sin-clan), o un marcador sin
 * resolver, es que no tiene. La idea es la del Super Beacon de EDM (superbeacon/Clanes.java), que es
 * de su paquete y no se puede usar desde aqui.
 *
 * Puntos (clanes.pesos): lo que sus miembros SACAN VIVOS en la Tasacion (Esencias fisicas que llevan
 * y el valor en Esencias de las Reliquias que venden), los minijefes (peso x la parte del dano de
 * cada participante), las Parcas cobradas y las Bovedas Caidas abiertas. Cada miembro suma como
 * mucho tope-dia-miembro al dia (el dia de hardcore.zona): un clan grande no gana solo por ser mas.
 *
 * La temporada es la semana ISO de Calendario: cierra el lunes 00:00 en hardcore.zona. Al cerrar, el
 * clan con mas puntos gana (empate: el que llego antes a esos puntos). Antes de entregar nada se
 * guarda el historial y se ponen los puntos a cero: un fallo despues no repite el cierre. El trofeo:
 * por consola, trofeo.comando (superbeacon give %lider% trofeo 7 %tag%; con el lider desconectado,
 * el Super Beacon lo deja pendiente). Si el mismo clan gana dos semanas seguidas y su trofeo sigue
 * existiendo (colocado, pendiente o en el inventario de alguien conectado), se renueva en vez de dar
 * otro (SuperBeaconPlugin.renovar, EDM 1.78.1); si no se encuentra, se da uno nuevo.
 *
 * El lider: clanes.placeholder-lider (%uclans_leader%) leido sobre un miembro conectado cada vez que
 * suma. Si nunca se pudo leer (sin esa expansion), el miembro que mas puntos aporto esa semana.
 *
 * Se ve en el menu de Rhen (la categoria Clanes), en %lethalworld_clan_top_<n>_nombre|puntos% y
 * %lethalworld_clan_puntos% (el del clan de quien mira), y en un holograma opcional (uno solo).
 * Los placeholders leen una copia inmutable que se rehace cada 30 s en el hilo principal.
 */
final class ClanesCalamity {

    static final String RUTA = "clanes";
    static final List<String> SIN_CLAN_DE_SERIE = List.of("", "sin clan", "n/a", "-");
    static final Map<String, Integer> PESOS_DE_SERIE = Map.of("esencia", 1, "reliquia", 1, "minijefe", 30, "parca", 40,
            "boveda-caida", 60);

    /** Una fila del ranking: clave interna, tag como se ve, puntos, cuando sumo por ultima vez y su cara. */
    record Fila(String clave, String tag, long puntos, long ultimo, UUID cara) {
    }

    private record Visto(String clan, long hasta) {
    }

    private final Hardcore hc;
    private final Map<UUID, Visto> cache = new HashMap<>();
    /** Uuid -> clave de su clan, para %lethalworld_clan_puntos% desde otros hilos. */
    private final Map<UUID, String> clanDe = new ConcurrentHashMap<>();
    private volatile List<Fila> top = List.of();
    private Method setPlaceholders;
    private boolean buscado;
    private BukkitTask reloj;
    private TextDisplay holo;
    private int segundos;

    ClanesCalamity(Hardcore hc) {
        this.hc = hc;
        PlaceholdersLethal.registrar("clan_top", this::placeholderTop);
        PlaceholdersLethal.registrar("clan_puntos", (j, r) -> {
            if (j == null) return "";
            String k = clanDe.get(j.getUniqueId());
            if (k == null) return "";
            for (Fila f : top) if (f.clave().equals(k)) return String.valueOf(f.puntos());
            return "0";
        });
        Subcomandos.staff().registrar("clans", "clans [close|holo here|holo off]: ranking semanal de clanes; close lo cierra ya",
                Subcomandos.PERMISO, this::comando, args -> args.length == 2 ? List.of("close", "holo")
                        : args.length == 3 && args[1].equalsIgnoreCase("holo") ? List.of("here", "off") : List.of());
        Autotest.registrar("clanes", ClanesCalamity::autotest);
        reloj = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(), () -> hc.seguro("clanes", this::ciclo), 80L, 20L);
    }

    void parar() {
        if (reloj != null) reloj.cancel();
        reloj = null;
        if (holo != null && holo.isValid()) holo.remove();
        holo = null;
        cache.clear();
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = hc.cfg().getConfigurationSection(RUTA);
        return s == null ? new YamlConfiguration() : s;
    }

    boolean activo() {
        return cfg().getBoolean("activo", true);
    }

    private Calendario cal() {
        return hc.calendario() != null ? hc.calendario() : new Calendario(hc);
    }

    private YamlConfiguration datos() {
        return hc.datos();
    }

    // ------------------------------------------------------------------ el clan (PlaceholderAPI)

    /** El tag del clan de ese jugador, sin colores; null si no tiene o no se sabe. Hilo principal. */
    String clan(OfflinePlayer p) {
        if (p == null) return null;
        long ahora = System.currentTimeMillis();
        Visto v = cache.get(p.getUniqueId());
        if (v != null && v.hasta() > ahora) return v.clan();
        String bruto = papi(p, cfg().getString("placeholder", "%uclans_tag_color%"));
        List<String> sin = cfg().isList("sin-clan") ? cfg().getStringList("sin-clan") : SIN_CLAN_DE_SERIE;
        String limpio = limpiar(bruto);
        String clan = esSinClan(limpio, sin) ? null : limpio;
        cache.put(p.getUniqueId(), new Visto(clan, ahora + 30_000L));
        if (clan == null) clanDe.remove(p.getUniqueId());
        else clanDe.put(p.getUniqueId(), clave(clan));
        return clan;
    }

    private String lider(OfflinePlayer miembro) {
        String l = limpiar(papi(miembro, cfg().getString("placeholder-lider", "%uclans_leader%")));
        return l.isBlank() || l.contains("%") || !Entregas.NOMBRE_VALIDO.matcher(l).matches() ? null : l;
    }

    private String papi(OfflinePlayer p, String marcador) {
        Method m = metodo();
        if (m == null || marcador == null || marcador.isBlank()) return "";
        try {
            Object r = m.invoke(null, p, marcador);
            return r == null ? "" : r.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private Method metodo() {
        if (setPlaceholders != null) return setPlaceholders;
        if (buscado) return null;
        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) return null;
        buscado = true;
        try {
            Class<?> c = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, papi.getClass().getClassLoader());
            setPlaceholders = c.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
        } catch (Throwable t) {
            hc.plugin().getLogger().warning("[Calamity] Clanes: PlaceholderAPI esta, pero sin setPlaceholders: " + t);
        }
        return setPlaceholders;
    }

    // ------------------------------------------------------------------ sumar

    /** Suma por una fuente (clanes.pesos): cantidad x su peso, con el tope diario del miembro. */
    void sumar(OfflinePlayer p, String fuente, double cantidad) {
        if (!activo() || p == null || cantidad <= 0) return;
        revisarSemana();
        String clan = clan(p);
        if (clan == null) return;
        int peso = cfg().isSet("pesos." + fuente) ? cfg().getInt("pesos." + fuente) : PESOS_DE_SERIE.getOrDefault(fuente, 0);
        int pedidos = (int) Math.floor(peso * cantidad + 1e-9);
        if (pedidos <= 0) return;
        UUID u = p.getUniqueId();
        String dia = cal().dia();
        String rd = RUTA + ".dia." + u;
        int hoy = dia.equals(datos().getString(rd + ".dia", "")) ? datos().getInt(rd + ".n", 0) : 0;
        int pts = conTope(pedidos, hoy, cfg().getInt("tope-dia-miembro", 300));
        if (pts <= 0) {
            hc.plugin().bitacora().anotar("clan", "tope", clan, nombre(p), fuente, "pedidos " + pedidos);
            return;
        }
        datos().set(rd + ".dia", dia);
        datos().set(rd + ".n", hoy + pts);
        String k = clave(clan);
        String base = RUTA + ".puntos." + k;
        datos().set(base + ".tag", clan);
        datos().set(base + ".puntos", datos().getLong(base + ".puntos", 0) + pts);
        datos().set(base + ".t", System.currentTimeMillis());
        datos().set(base + ".aportes." + u, datos().getLong(base + ".aportes." + u, 0) + pts);
        if (p.isOnline()) {
            String l = lider(p);
            if (l != null) datos().set(base + ".lider", l);
        }
        hc.marcarSucio();
        hc.plugin().bitacora().anotar("clan", "suma", clan, nombre(p), "+" + pts, fuente);
        Telemetria te = hc.telemetria();
        if (te != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("clan", clan);
            c.put("fuente", fuente);
            c.put("puntos", pts);
            c.put("pedidos", pedidos);
            hc.seguro("telemetria", () -> te.suceso("clan-puntos", p, c));
        }
    }

    /** La Tasacion: Esencias fisicas que saco y lo que valen en Esencias las Reliquias vendidas. */
    void alTasar(Player p, int esenciasFisicas, int esenciasDeReliquias) {
        if (esenciasFisicas > 0) sumar(p, "esencia", esenciasFisicas);
        if (esenciasDeReliquias > 0) sumar(p, "reliquia", esenciasDeReliquias);
    }

    // ------------------------------------------------------------------ la semana

    private void ciclo() {
        segundos++;
        if (segundos % 30 != 1) return;
        if (activo()) revisarSemana();
        rehacerTop();
        long ahora = System.currentTimeMillis();
        cache.values().removeIf(v -> v.hasta() <= ahora);
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) clan(p);
        holograma();
    }

    /** Si la semana guardada ya no es la de hoy, se cierra (el lunes 00:00 en hardcore.zona). */
    private void revisarSemana() {
        String hoy = cal().semana();
        String guardada = datos().getString(RUTA + ".semana", null);
        if (guardada == null) {
            datos().set(RUTA + ".semana", hoy);
            hc.marcarSucio();
            return;
        }
        if (guardada.equals(hoy)) return;
        // Primero se apunta la semana nueva: si el cierre fallara a medias, no se repite.
        datos().set(RUTA + ".semana", hoy);
        cerrar(guardada, "lunes");
    }

    /** La clasificacion de ahora, ordenada (puntos, y antes el que llego antes). */
    List<Fila> clasificacion() {
        ConfigurationSection s = datos().getConfigurationSection(RUTA + ".puntos");
        List<Fila> out = new ArrayList<>();
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            long pts = s.getLong(k + ".puntos", 0);
            if (pts <= 0) continue;
            UUID cara = null;
            long mas = -1;
            ConfigurationSection ap = s.getConfigurationSection(k + ".aportes");
            if (ap != null) {
                for (String u : ap.getKeys(false)) {
                    long v = ap.getLong(u);
                    if (v > mas) {
                        try {
                            cara = UUID.fromString(u);
                            mas = v;
                        } catch (IllegalArgumentException ignorado) {
                            // una linea rota no tumba el ranking
                        }
                    }
                }
            }
            out.add(new Fila(k, s.getString(k + ".tag", k), pts, s.getLong(k + ".t", 0), cara));
        }
        ordenar(out);
        return out;
    }

    static void ordenar(List<Fila> filas) {
        filas.sort(Comparator.comparingLong(Fila::puntos).reversed().thenComparingLong(Fila::ultimo)
                .thenComparing(Fila::clave));
    }

    private void rehacerTop() {
        top = List.copyOf(clasificacion());
    }

    /**
     * Cierra una semana: el ganador, el historial y los puntos a cero (guardado), y despues el trofeo
     * y el anuncio. Devuelve la fila ganadora o null si nadie llego al minimo.
     */
    Fila cerrar(String semana, String motivo) {
        List<Fila> filas = clasificacion();
        String base = RUTA + ".historial." + semana;
        long minimo = Math.max(1, cfg().getLong("minimo-puntos", 1));
        Fila g = filas.isEmpty() || filas.get(0).puntos() < minimo ? null : filas.get(0);
        List<String> resumen = new ArrayList<>();
        for (int i = 0; i < Math.min(10, filas.size()); i++) resumen.add(filas.get(i).tag() + ":" + filas.get(i).puntos());
        datos().set(base + ".top", resumen);
        datos().set(base + ".motivo", motivo);
        String anterior = datos().getString(RUTA + ".ultimo-ganador", null);
        String lider = null;
        if (g != null) {
            lider = datos().getString(RUTA + ".puntos." + g.clave() + ".lider", null);
            if (lider == null && g.cara() != null) lider = Bukkit.getOfflinePlayer(g.cara()).getName();
            datos().set(base + ".ganador", g.tag());
            datos().set(base + ".puntos", g.puntos());
            datos().set(base + ".lider", lider);
            datos().set(RUTA + ".ultimo-ganador", g.clave());
        }
        datos().set(RUTA + ".puntos", null);
        datos().set(RUTA + ".dia", null);
        hc.guardarYa();
        rehacerTop();
        if (g == null) {
            hc.plugin().bitacora().anotar("clan", "cierre", semana, motivo, "sin ganador");
            return null;
        }
        String accion = entregarTrofeo(g, lider, g.clave().equals(anterior));
        datos().set(base + ".accion", accion);
        hc.guardarYa();
        boolean renovado = accion.startsWith("renovado");
        Component anuncio = ComandoCalamity.mensaje(Component.text("El clan ")
                .append(Paleta.detalle("[" + g.tag() + "]"))
                .append(Component.text(renovado ? " gana otra vez la temporada de Calamity y renueva su Trofeo de Temporada."
                        : " gana la temporada de Calamity y se lleva el Trofeo de Temporada.")));
        hc.plugin().getServer().broadcast(anuncio);
        hc.plugin().bitacora().anotar("clan", "cierre", semana, motivo, g.tag(), g.puntos() + " pts", "lider " + lider, accion);
        Telemetria te = hc.telemetria();
        if (te != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("semana", semana);
            c.put("ganador", g.tag());
            c.put("puntos", g.puntos());
            c.put("accion", accion);
            c.put("clanes", filas.size());
            hc.seguro("telemetria", () -> te.suceso("clan-cierre", null, c));
        }
        return g;
    }

    /** Renueva el trofeo del clan que repite o da uno nuevo. Devuelve que se hizo, para el historial. */
    private String entregarTrofeo(Fila g, String lider, boolean repite) {
        String tipo = cfg().getString("trofeo.tipo", "trofeo");
        double dias = cfg().getDouble("trofeo.dias", 7);
        if (repite && cfg().getBoolean("trofeo.renovar", true)) {
            SuperBeaconPlugin sb = superBeacon();
            int n = sb == null ? 0 : hc.valor("clanes", () -> sb.renovar(g.tag(), tipo, dias, "calamity:clanes"), 0);
            if (n > 0) return "renovado x" + n;
        }
        if (lider == null) {
            hc.plugin().getLogger().warning("[Calamity] Clanes: el clan " + g.tag() + " gana, pero no se sabe su líder. Dale el trofeo a mano.");
            return "sin-lider";
        }
        String cmd = cfg().getString("trofeo.comando", "superbeacon give %lider% %tipo% %dias% %tag%")
                .replace("%lider%", lider).replace("%tipo%", tipo).replace("%dias%", numero(dias)).replace("%tag%", g.tag()).trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        boolean ok;
        try {
            ok = hc.plugin().getServer().dispatchCommand(hc.plugin().getServer().getConsoleSender(), cmd);
        } catch (Throwable t) {
            ok = false;
        }
        return (ok ? "dado" : "fallo") + " a " + lider;
    }

    private static SuperBeaconPlugin superBeacon() {
        try {
            if (!(Bukkit.getPluginManager().getPlugin("EDM") instanceof EDMPlugin edm) || !edm.isEnabled()) return null;
            return edm.modulo("superbeacon") instanceof SuperBeaconPlugin s ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ verlo

    /** La copia para el menu y los placeholders (cualquier hilo). */
    List<Fila> top() {
        return top;
    }

    /** La clave del clan de ese jugador (lo ultimo visto), o null. Cualquier hilo. */
    String claveDe(UUID u) {
        return u == null ? null : clanDe.get(u);
    }

    /** %lethalworld_clan_top_<n>_nombre|puntos%. */
    private String placeholderTop(OfflinePlayer j, String resto) {
        String[] t = resto == null ? new String[0] : resto.split("_", 2);
        if (t.length != 2) return null;
        int n;
        try {
            n = Integer.parseInt(t[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        List<Fila> l = top;
        Fila f = n >= 1 && n <= l.size() ? l.get(n - 1) : null;
        return switch (t[1].toLowerCase(Locale.ROOT)) {
            case "nombre" -> f == null ? "-" : f.tag();
            case "puntos" -> f == null ? "0" : String.valueOf(f.puntos());
            default -> null;
        };
    }

    /** El holograma (uno solo): TextDisplay no persistente, se rehace si su chunk se carga. */
    private void holograma() {
        ConfigurationSection h = cfg().getConfigurationSection("holograma");
        String mundo = datos().getString(RUTA + ".holograma.mundo", null);
        if (h == null || !h.getBoolean("activo", false) || mundo == null) {
            if (holo != null && holo.isValid()) holo.remove();
            holo = null;
            return;
        }
        World w = hc.plugin().getServer().getWorld(mundo);
        if (w == null) return;
        Location l = new Location(w, datos().getDouble(RUTA + ".holograma.x"), datos().getDouble(RUTA + ".holograma.y"),
                datos().getDouble(RUTA + ".holograma.z"));
        if (!w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return;
        Component texto = textoHolograma(Math.max(1, Math.min(10, h.getInt("top", 5))));
        if (holo == null || !holo.isValid()) {
            holo = w.spawn(l, TextDisplay.class, e -> {
                e.setPersistent(false);
                e.setBillboard(Display.Billboard.CENTER);
                e.setSeeThrough(false);
                e.setShadowed(false);
                e.setLineWidth(220);
            });
        }
        holo.text(texto);
    }

    private Component textoHolograma(int n) {
        Component c = Component.text("Clanes de la semana", Paleta.MARCA);
        List<Fila> l = top;
        if (l.isEmpty()) return c.append(Component.newline()).append(Component.text("Nadie ha sumado todavía", Paleta.TENUE));
        for (int i = 0; i < Math.min(n, l.size()); i++) {
            c = c.append(Component.newline()).append(Component.text((i + 1) + ".º ", MenuCazador.colorPuesto(i + 1)))
                    .append(Component.text("[" + l.get(i).tag() + "]  ", Paleta.TEXTO))
                    .append(Component.text(Altar.miles(l.get(i).puntos()), Paleta.CIFRA));
        }
        return c;
    }

    // ------------------------------------------------------------------ comando

    private void comando(CommandSender quien, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "close" -> {
                String semana = cal().semana();
                Fila g = cerrar(semana + "-manual-" + System.currentTimeMillis() / 1000, "comando:" + quien.getName());
                quien.sendMessage(ComandoCalamity.mensaje(g == null ? "Temporada cerrada sin ganador: nadie tenía puntos."
                        : "Temporada cerrada. Gana [" + g.tag() + "] con " + g.puntos() + " puntos."));
            }
            case "holo" -> {
                boolean aqui = args.length > 2 && args[2].equalsIgnoreCase("here");
                if (aqui && quien instanceof Player p) {
                    datos().set(RUTA + ".holograma.mundo", p.getWorld().getName());
                    datos().set(RUTA + ".holograma.x", p.getLocation().getX());
                    datos().set(RUTA + ".holograma.y", p.getLocation().getY() + 2);
                    datos().set(RUTA + ".holograma.z", p.getLocation().getZ());
                    if (holo != null && holo.isValid()) holo.remove();
                    holo = null;
                    hc.guardarYa();
                    quien.sendMessage(ComandoCalamity.mensaje("Holograma de clanes aquí (con clanes.holograma.activo en true)."));
                } else {
                    datos().set(RUTA + ".holograma", null);
                    if (holo != null && holo.isValid()) holo.remove();
                    holo = null;
                    hc.guardarYa();
                    quien.sendMessage(ComandoCalamity.mensaje("Holograma de clanes quitado."));
                }
            }
            default -> {
                rehacerTop();
                List<Fila> l = top;
                quien.sendMessage(ComandoCalamity.mensaje("Clanes, semana " + cal().semana() + (activo() ? "" : " (apagado en el config)")
                        + ": " + l.size() + " con puntos."));
                for (int i = 0; i < Math.min(10, l.size()); i++) {
                    Fila f = l.get(i);
                    String lid = datos().getString(RUTA + ".puntos." + f.clave() + ".lider", "?");
                    quien.sendMessage(Component.text("  " + (i + 1) + ". [" + f.tag() + "] " + f.puntos() + " · líder " + lid, Paleta.TENUE));
                }
                String ult = datos().getString(RUTA + ".ultimo-ganador", null);
                if (ult != null) quien.sendMessage(Component.text("  Último ganador: " + ult, Paleta.TENUE));
            }
        }
    }

    // ------------------------------------------------------------------ puro (autotest)

    /** Sin colores ni formato, como Clanes.limpiar del Super Beacon. */
    static String limpiar(String bruto) {
        if (bruto == null) return "";
        return bruto.replaceAll("(?i)[§&]x([§&][0-9a-f]){6}", "")
                .replaceAll("(?i)[§&]#[0-9a-f]{6}", "")
                .replaceAll("(?i)<#[0-9a-f]{6}>", "")
                .replaceAll("(?i)</?(?:[a-z_]+|#[0-9a-f]{6})(?::[^>]*)?>", "")
                .replaceAll("(?i)[§&][0-9a-fk-or]", "")
                .trim();
    }

    static boolean esSinClan(String limpio, Collection<String> sin) {
        if (limpio == null || limpio.isBlank() || limpio.contains("%")) return true;
        Set<String> s = new HashSet<>();
        for (String x : sin) s.add(limpiar(x).toLowerCase(Locale.ROOT));
        return s.contains(limpio.toLowerCase(Locale.ROOT));
    }

    /** La clave de un clan en hardcore-datos: minusculas y solo a-z, 0-9, _ y - (un punto partiria la ruta). */
    static String clave(String tag) {
        String k = limpiar(tag).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return k.isEmpty() ? "_" : k;
    }

    /** Los puntos que entran con el tope del dia: pedidos, sin pasar de tope - hoy (tope 0 = sin tope). */
    static int conTope(int pedidos, int hoy, int tope) {
        if (pedidos <= 0) return 0;
        if (tope <= 0) return pedidos;
        return Math.max(0, Math.min(pedidos, tope - hoy));
    }

    private static String numero(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String nombre(OfflinePlayer p) {
        return p.getName() == null ? p.getUniqueId().toString().substring(0, 8) : p.getName();
    }

    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        h.igual("tag sin colores", "ABC", limpiar("&#FF0000A&lB&rC"));
        h.igual("tag con minimessage", "XYZ", limpiar("<#00FF00>XYZ</#00FF00>"));
        h.ok("vacio es sin clan", esSinClan("", SIN_CLAN_DE_SERIE));
        h.ok("'Sin clan' es sin clan", esSinClan("Sin Clan", SIN_CLAN_DE_SERIE));
        h.ok("'N/A' es sin clan", esSinClan("N/A", SIN_CLAN_DE_SERIE));
        h.ok("un marcador sin resolver es sin clan", esSinClan("%uclans_tag_color%", SIN_CLAN_DE_SERIE));
        h.ok("un tag de verdad no", !esSinClan("ABC", SIN_CLAN_DE_SERIE));
        h.igual("clave sin puntos ni mayusculas", "a_b_c", clave("A.B C"));
        h.igual("tope: entra entero", 30, conTope(30, 100, 300));
        h.igual("tope: entra lo que queda", 20, conTope(30, 280, 300));
        h.igual("tope: lleno, nada", 0, conTope(30, 300, 300));
        h.igual("tope 0 = sin tope", 30, conTope(30, 5000, 0));
        List<Fila> f = new ArrayList<>(List.of(new Fila("b", "B", 50, 2000, null), new Fila("a", "A", 80, 3000, null),
                new Fila("c", "C", 50, 1000, null)));
        ordenar(f);
        List<String> orden = new ArrayList<>();
        for (Fila x : f) orden.add(x.clave());
        h.igual("orden: mas puntos primero; empate, el que llego antes", List.of("a", "c", "b"), orden);
        h.igual("pesos de serie", 60, PESOS_DE_SERIE.get("boveda-caida"));
        return h.lineas();
    }
}
