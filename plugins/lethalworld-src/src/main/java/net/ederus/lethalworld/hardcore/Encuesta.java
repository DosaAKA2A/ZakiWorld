package net.ederus.lethalworld.hardcore;

import net.ederus.edm.comun.EntradaChat;
import net.ederus.lethalworld.MobsLethal;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M19 · Encuesta y Voto del Botin (MED sec. 4): preguntar a los jugadores que quieren, con un
 * clic, justo cuando acaban de sacar algo de Calamity.
 *
 * Es la preferencia declarada del plan de recompensas: se contrasta con lo que arriesgan
 * (censo) y con lo que compran (trueques). Por eso el voto tiene filtros que en otra
 * encuesta sobrarian: uno por UUID y por huella de IP (alts), y solo de quien ha tasado
 * algo esa semana (que opine quien juega Calamity, no quien pasa por el spawn).
 *
 * Dos tipos de pregunta, las dos en hardcore.encuesta.preguntas:
 *  - la encuesta activa (encuestas-activa en datos): una a la vez, la abre y la cierra el
 *    staff; un voto por jugador en toda su vida; al cerrarla se escribe
 *    encuestas/<id>-resultados.yml.
 *  - el Voto del Botin (id "botin", fija: true): siempre abierta, un voto por semana, con el
 *    recuento total y el de cada semana para ver la tendencia.
 *
 * Tras una tasacion sale COMO MUCHO un menu (DIS M19 [alineado]): la encuesta si esta
 * pendiente, si no el Voto del Botin; cada uno como mucho una vez por semana, que un menu
 * que salta en cada extraccion se cierra sin leer. Las dos pagan 1 Esencia por la Aduana
 * (tipo "encuesta", tope diario 2).
 *
 * Los placeholders se contestan desde una foto en memoria (PlaceholderAPI puede preguntar
 * desde otro hilo y hardcore-datos.yml no se lee fuera del principal).
 */
final class Encuesta {

    static final String BOTIN = "botin";
    /** La respuesta libre por chat; solo en preguntas no fijas de hasta 5 opciones (cabe en la fila). */
    static final String OTRA = "otra";
    private static final int LARGO_OTRA = 60;

    record Opcion(String id, Material icono, String texto) {
    }

    record Pregunta(String id, String texto, boolean fija, List<Opcion> opciones) {

        boolean admiteOtra() {
            return !fija && opciones.size() <= 5;
        }

        boolean tiene(String opcion) {
            if (opcion == null) return false;
            if (OTRA.equals(opcion)) return admiteOtra();
            for (Opcion o : opciones) if (o.id().equals(opcion)) return true;
            return false;
        }

        String textoDe(String opcion) {
            if (OTRA.equals(opcion)) return "Otra cosa";
            for (Opcion o : opciones) if (o.id().equals(opcion)) return o.texto();
            return opcion;
        }
    }

    enum Voto { OK, YA, HUELLA, SIN_TASAR, CERRADA, OPCION }

    private final Hardcore hc;
    private final MenuEncuesta menu;
    private final Deseos deseos;
    private final EntradaChat chat;
    private final Set<BukkitTask> tareas = new HashSet<>();
    /** Quien ha tasado esta semana segun trasTasar (ademas de stats-semana.extracciones). */
    private final Set<UUID> tasaron = new HashSet<>();
    private String semanaTasaron = "";

    /* La foto para los placeholders (otro hilo). */
    private volatile String activaFoto;
    private volatile Set<UUID> votaronFoto = Set.of();
    private volatile Map<String, String> pctFoto = Map.of();

    Encuesta(Hardcore hc) {
        this.hc = hc;
        this.chat = new EntradaChat(hc.plugin());
        hc.plugin().getServer().getPluginManager().registerEvents(chat, hc.plugin());
        this.deseos = new Deseos(hc, this);
        this.menu = new MenuEncuesta(hc, this);

        Autotest.registrar("encuesta", this::autotest);
        Subcomandos.lw().registrar("encuesta",
                "encuesta [id] | abrir <id> | cerrar [botin] | simular <n>: recuento, abrir y cerrar la pregunta",
                "ederus.mundos", this::comandoStaff, this::tabStaff);
        Subcomandos.lw().registrar("voto", "voto: recuento del Voto del Botin (total y esta semana)", "ederus.mundos",
                (quien, args) -> recuento(quien, BOTIN), null);
        Subcomandos.calamity().registrar("encuesta", "contesta la pregunta de Calamity", "lethalworld.calamity",
                (quien, args) -> {
                    if (quien instanceof Player p) abrirPendiente(p);
                    else quien.sendMessage(Component.text("Solo desde el juego.", Paleta.AVISO));
                }, null);
        PlaceholdersLethal.registrar("encuesta", this::placeholder);
        refrescar();
    }

    boolean activo() {
        return hc.cfg().getBoolean("encuesta.activo", false);
    }

    Deseos deseos() {
        return deseos;
    }

    void parar() {
        for (BukkitTask t : tareas) t.cancel();
        tareas.clear();
        menu.parar();
        HandlerList.unregisterAll(chat);
        tasaron.clear();
    }

    // ------------------------------------------------------------------- API

    /**
     * Lo llama la Tasacion al final (Tasacion.tasar). Un tick despues, ya fuera, abre como
     * mucho un menu: la encuesta pendiente o el Voto del Botin.
     */
    void trasTasar(Player p) {
        if (!activo()) return;
        String semana = semana();
        if (!semana.equals(semanaTasaron)) {
            tasaron.clear();
            semanaTasaron = semana;
        }
        tasaron.add(p.getUniqueId());
        tarea(() -> {
            if (!p.isOnline() || !activo()) return;
            String cual = queMostrar(p.getUniqueId(), semana);
            if (cual == null) return;
            Pregunta q = cual.equals(BOTIN) ? preguntas().get(BOTIN) : preguntas().get(activa());
            if (q == null) return;
            hc.datos().set("encuestas-mostrada." + p.getUniqueId() + "." + cual, semana);
            hc.marcarSucio();
            menu.abrir(p, q);
        }, 1L);
    }

    /** /calamity encuesta y el boton del Altar: la pendiente, si no el Botin, si no nada. */
    void abrirPendiente(Player p) {
        if (!activo()) {
            p.sendMessage(ComandoCalamity.mensaje("Ahora mismo no hay nada que votar."));
            return;
        }
        String a = activa();
        UUID u = p.getUniqueId();
        if (a != null && pendiente(hc.datos(), a, u)) {
            menu.abrir(p, preguntas().get(a));
            return;
        }
        abrirBotin(p);
    }

    /** El Voto del Botin (tambien desde el Altar). */
    void abrirBotin(Player p) {
        Pregunta b = preguntas().get(BOTIN);
        if (!activo() || b == null) {
            p.sendMessage(ComandoCalamity.mensaje("Ahora mismo no hay nada que votar."));
            return;
        }
        if (hc.datos().isSet(rutaYa(b, semana()) + p.getUniqueId())) {
            p.sendMessage(ComandoCalamity.mensaje("Ya has votado esta semana."));      // P-V02
            return;
        }
        menu.abrir(p, b);
    }

    /** Si ese jugador tiene la encuesta activa sin contestar. */
    boolean pendiente(UUID u) {
        String a = activa();
        return activo() && a != null && pendiente(hc.datos(), a, u);
    }

    // ------------------------------------------------------------------ votar

    /** Clic en una opcion del menu. texto: solo para "otra" (ya limpio). */
    void votar(Player p, Pregunta q, String opcion, String texto) {
        if (!activo()) {
            p.sendMessage(ComandoCalamity.mensaje("Ahora mismo no hay nada que votar."));
            return;
        }
        UUID u = p.getUniqueId();
        String huella = p.hasPermission("lethalworld.aduana.exento") ? "" : huella(p);
        Voto r = registrar(hc.datos(), q, activa(), semana(), opcion, u, huella, haTasado(u), rango(p), texto,
                System.currentTimeMillis());
        switch (r) {
            case OK -> {
                hc.marcarSucio();
                int premio = Math.max(0, hc.cfg().getInt("encuesta.premio-esencias", 1));
                Aduana a = hc.aduana();
                if (premio > 0 && a != null) {
                    hc.seguro("aduana", () -> a.pagar(p, "encuesta", premio, 0L, List.of(), "encuesta:" + q.id()));
                }
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", q.id());
                c.put("opcion", opcion);
                if (texto != null) c.put("texto", texto);
                telemetria(q.fija() ? "voto" : "encuesta", p, c);
                hc.plugin().bitacora().anotar("encuesta", "voto", p.getName(), q.id(), opcion);
                p.sendMessage(ComandoCalamity.mensaje("Gracias. Calamity toma nota."));   // P-V01
                refrescar();
            }
            case YA, HUELLA -> {
                if (r == Voto.HUELLA) hc.plugin().bitacora().anotar("encuesta", "repetido", p.getName(), q.id(), "huella");
                p.sendMessage(ComandoCalamity.mensaje("Ya has votado esta semana."));      // P-V02
            }
            case SIN_TASAR -> p.sendMessage(ComandoCalamity.mensaje(
                    "Vota cuando hayas sacado algo de Calamity esta semana."));
            case CERRADA -> p.sendMessage(ComandoCalamity.mensaje("Esa pregunta ya está cerrada."));
            case OPCION -> {
                // Un clic en una casilla vieja: no hay nada que contar.
            }
        }
    }

    /** "Otra cosa": se cierra el menu y se espera una linea por el chat (60 letras). */
    void pedirOtra(Player p, Pregunta q) {
        p.sendMessage(ComandoCalamity.mensaje("Escríbelo en el chat (60 letras como mucho). \"cancelar\" para salir."));
        chat.pedir(p, linea -> {
            String limpio = limpiar(linea);
            if (limpio.isEmpty()) {
                p.sendMessage(ComandoCalamity.mensaje("No he entendido nada. Vuelve a intentarlo con /calamity encuesta."));
                return;
            }
            votar(p, q, OTRA, limpio);
        }, () -> p.sendMessage(ComandoCalamity.mensaje("Sin respuesta. Puedes volver con /calamity encuesta.")));
    }

    /**
     * El voto sobre unos datos (los reales o un yml de memoria en el autotest). No paga ni
     * avisa: solo decide y apunta.
     *
     * encuestas.<id>.votos.<opcion>, .ya.<uuid|huella>, .por-rango.<rango>.<opcion>,
     * .texto.<uuid>; el Botin ademas encuestas.botin.<semana>.votos y .ya por semana.
     */
    static Voto registrar(ConfigurationSection d, Pregunta q, String activa, String semana, String opcion, UUID u,
                          String huella, boolean haTasado, int rango, String texto, long ahora) {
        if (q == null || (!q.fija() && !q.id().equals(activa))) return Voto.CERRADA;
        if (!q.tiene(opcion)) return Voto.OPCION;
        String ya = rutaYa(q, semana);
        if (d.isSet(ya + u)) return Voto.YA;
        String h = limpiarClave(huella);
        if (!h.isEmpty() && d.isSet(ya + h)) return Voto.HUELLA;
        if (!haTasado) return Voto.SIN_TASAR;
        d.set(ya + u, ahora);
        if (!h.isEmpty()) d.set(ya + h, ahora);
        String base = "encuestas." + q.id();
        sumar(d, base + ".votos." + opcion);
        if (q.fija()) sumar(d, base + "." + semana + ".votos." + opcion);
        sumar(d, base + ".por-rango." + Math.max(0, rango) + "." + opcion);
        if (texto != null && !texto.isBlank()) d.set(base + ".texto." + u, texto);
        return Voto.OK;
    }

    private static String rutaYa(Pregunta q, String semana) {
        return q.fija() ? "encuestas." + q.id() + "." + semana + ".ya." : "encuestas." + q.id() + ".ya.";
    }

    private static void sumar(ConfigurationSection d, String ruta) {
        d.set(ruta, d.getInt(ruta, 0) + 1);
    }

    /** La huella de la Aduana es texto libre para el YAML: sin puntos ni espacios. */
    private static String limpiarClave(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9_-]", "");
    }

    static boolean pendiente(ConfigurationSection d, String activa, UUID u) {
        return activa != null && !d.isSet("encuestas." + activa + ".ya." + u);
    }

    /**
     * Que menu toca tras tasar: la encuesta si esta pendiente y no se le ha ensenado esta
     * semana; si no, el Botin si no ha votado ni se le ha ensenado esta semana; si no, nada.
     */
    static String queMostrar(boolean encuestaPendiente, boolean encuestaVistaSemana, boolean hayBotin,
                             boolean botinVotadoSemana, boolean botinVistoSemana) {
        if (encuestaPendiente && !encuestaVistaSemana) return "encuesta";
        if (hayBotin && !botinVotadoSemana && !botinVistoSemana) return BOTIN;
        return null;
    }

    private String queMostrar(UUID u, String semana) {
        ConfigurationSection d = hc.datos();
        String a = activa();
        Pregunta b = preguntas().get(BOTIN);
        String vista = "encuestas-mostrada." + u + ".";
        return queMostrar(a != null && pendiente(d, a, u), semana.equals(d.getString(vista + "encuesta")),
                b != null, b != null && d.isSet(rutaYa(b, semana) + u), semana.equals(d.getString(vista + BOTIN)));
    }

    /** Porcentajes de una pregunta (0-100, un decimal), en el orden de sus opciones. */
    static Map<String, Double> porcentajes(ConfigurationSection d, Pregunta q, String semana) {
        String base = "encuestas." + q.id() + (semana == null ? "" : "." + semana) + ".votos.";
        List<String> ids = new ArrayList<>();
        for (Opcion o : q.opciones()) ids.add(o.id());
        if (q.admiteOtra()) ids.add(OTRA);
        long total = 0;
        for (String o : ids) total += d.getInt(base + o, 0);
        Map<String, Double> out = new LinkedHashMap<>();
        for (String o : ids) {
            int v = d.getInt(base + o, 0);
            out.put(o, total == 0 ? 0.0 : Math.round(v * 1000.0 / total) / 10.0);
        }
        return out;
    }

    static int total(ConfigurationSection d, Pregunta q, String semana) {
        String base = "encuestas." + q.id() + (semana == null ? "" : "." + semana) + ".votos";
        ConfigurationSection s = d.getConfigurationSection(base);
        if (s == null) return 0;
        int t = 0;
        for (String k : s.getKeys(false)) t += s.getInt(k, 0);
        return t;
    }

    /** El fichero de resultados (MED sec. 4.1), sin escribirlo. */
    static YamlConfiguration resultados(ConfigurationSection d, Pregunta q, String cerrada) {
        YamlConfiguration y = new YamlConfiguration();
        String base = "encuestas." + q.id();
        y.set("id", q.id());
        y.set("pregunta", q.texto());
        y.set("abierta", d.getString(base + ".abierta", ""));
        y.set("cerrada", cerrada);
        int total = total(d, q, null);
        y.set("total", total);
        Map<String, Double> pct = porcentajes(d, q, null);
        for (Map.Entry<String, Double> e : pct.entrySet()) {
            y.set("opciones." + e.getKey() + ".texto", q.textoDe(e.getKey()));
            y.set("opciones." + e.getKey() + ".votos", d.getInt(base + ".votos." + e.getKey(), 0));
            y.set("opciones." + e.getKey() + ".pct", e.getValue());
        }
        ConfigurationSection rangos = d.getConfigurationSection(base + ".por-rango");
        if (rangos != null) {
            for (String r : rangos.getKeys(false)) {
                ConfigurationSection s = rangos.getConfigurationSection(r);
                if (s == null) continue;
                for (String o : s.getKeys(false)) y.set("por_rango." + r + "." + o, s.getInt(o, 0));
            }
        }
        ConfigurationSection textos = d.getConfigurationSection(base + ".texto");
        if (textos != null) {
            List<String> otras = new ArrayList<>();
            for (String k : textos.getKeys(false)) otras.add(textos.getString(k, ""));
            y.set("otras", otras);
        }
        if (q.fija()) {
            ConfigurationSection s = d.getConfigurationSection(base);
            if (s != null) {
                for (String k : s.getKeys(false)) {
                    if (!k.matches("\\d{4}-W\\d{2}")) continue;
                    y.set("por_semana." + k + ".total", total(d, q, k));
                    for (Map.Entry<String, Double> e : porcentajes(d, q, k).entrySet()) {
                        y.set("por_semana." + k + ".pct." + e.getKey(), e.getValue());
                    }
                }
            }
        }
        return y;
    }

    // ---------------------------------------------------------------- staff

    private void comandoStaff(CommandSender quien, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "" -> {
                String a = activa();
                if (a == null) {
                    quien.sendMessage(Component.text("encuesta | ninguna abierta | preguntas: "
                            + String.join(", ", preguntas().keySet()) + (activo() ? "" : " | encuesta.activo: false"),
                            Paleta.TENUE));
                } else {
                    recuento(quien, a);
                }
            }
            case "abrir" -> abrir(quien, args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "");
            case "cerrar" -> cerrar(quien, args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : null);
            case "simular" -> simular(quien, args.length >= 3 ? args[2] : "10");
            default -> recuento(quien, sub);
        }
    }

    private List<String> tabStaff(String[] args) {
        if (args.length == 2) {
            List<String> op = new ArrayList<>(List.of("abrir", "cerrar", "simular"));
            op.addAll(preguntas().keySet());
            return op;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("abrir")) {
            List<String> op = new ArrayList<>();
            for (Pregunta q : preguntas().values()) if (!q.fija()) op.add(q.id());
            return op;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("cerrar")) return List.of(BOTIN);
        return List.of();
    }

    private void abrir(CommandSender quien, String id) {
        Pregunta q = preguntas().get(id);
        if (q == null || q.fija()) {
            quien.sendMessage(Component.text("encuesta abrir <id>: una de " + ids(false) + ".", Paleta.AVISO));
            return;
        }
        String antes = activa();
        if (id.equals(antes)) {
            quien.sendMessage(Component.text("encuesta | " + id + " | ya estaba abierta", Paleta.TENUE));
            return;
        }
        // Una a la vez: la que hubiera se cierra con su fichero, que no se pierda el recuento.
        if (antes != null) cerrar(quien, null);
        hc.datos().set("encuestas-activa", id);
        if (!hc.datos().isSet("encuestas." + id + ".abierta")) hc.datos().set("encuestas." + id + ".abierta", dia());
        hc.datos().set("encuestas." + id + ".cerrada", null);
        hc.guardarYa();
        hc.plugin().bitacora().anotar("encuesta", "abrir", id);
        refrescar();
        quien.sendMessage(Component.text("encuesta | " + id + " | abierta" + (activo() ? "" : " (encuesta.activo: false, nadie la vera)"),
                Paleta.BIEN));
    }

    /** Cierra la activa (o escribe el fichero del Botin, que no se cierra nunca). */
    private void cerrar(CommandSender quien, String id) {
        boolean botin = BOTIN.equals(id);
        String cual = botin ? BOTIN : activa();
        if (cual == null) {
            quien.sendMessage(Component.text("encuesta | no hay ninguna abierta", Paleta.AVISO));
            return;
        }
        Pregunta q = preguntas().get(cual);
        if (q == null) {
            // La pregunta se quito de la config con la encuesta abierta: se cierra igual.
            q = new Pregunta(cual, cual, false, List.of());
        }
        String hoy = dia();
        YamlConfiguration y = resultados(hc.datos(), q, botin ? null : hoy);
        File carpeta = new File(hc.plugin().getDataFolder(), "encuestas");
        File f = new File(carpeta, cual + "-resultados.yml");
        try {
            if (!carpeta.isDirectory() && !carpeta.mkdirs()) throw new IOException("no se puede crear " + carpeta);
            y.save(f);
        } catch (IOException e) {
            quien.sendMessage(Component.text("encuesta | " + cual + " | no se pudo escribir " + f.getName() + ": " + e.getMessage(),
                    Paleta.AVISO));
            return;
        }
        if (!botin) {
            hc.datos().set("encuestas." + cual + ".cerrada", hoy);
            hc.datos().set("encuestas-activa", null);
            hc.guardarYa();
        }
        int total = y.getInt("total");
        hc.plugin().bitacora().anotar("encuesta", botin ? "resultados" : "cerrar", cual, "total " + total);
        refrescar();
        quien.sendMessage(Component.text("encuesta | " + cual + " | " + (botin ? "resultados" : "cerrada") + " | total "
                + total + " | encuestas/" + f.getName(), Paleta.BIEN));
    }

    /**
     * Solo para el servidor de pruebas: n votos de UUID sinteticos en la encuesta activa,
     * con la ultima huella repetida (se tiene que contar n - 1). Asi se prueba el cierre y
     * el fichero de resultados sin jugadores. Quedan apuntados en encuestas.<id>.simulados.
     */
    private void simular(CommandSender quien, String cuantos) {
        String a = activa();
        Pregunta q = a == null ? null : preguntas().get(a);
        if (q == null) {
            quien.sendMessage(Component.text("encuesta simular: abre antes una encuesta.", Paleta.AVISO));
            return;
        }
        int n;
        try {
            n = Math.max(1, Math.min(200, Integer.parseInt(cuantos)));
        } catch (NumberFormatException e) {
            quien.sendMessage(Component.text("encuesta simular <n>: n es un numero.", Paleta.AVISO));
            return;
        }
        int bien = 0, huella = 0;
        long ahora = System.currentTimeMillis();
        for (int i = 1; i <= n; i++) {
            String h = "sim" + (i == n && n > 1 ? 1 : i);
            String op = q.opciones().get((i - 1) % q.opciones().size()).id();
            Voto r = registrar(hc.datos(), q, a, semana(), op, Autotest.sintetico(900_000 + i), h, true, i % 10, null, ahora);
            if (r == Voto.OK) bien++;
            else if (r == Voto.HUELLA) huella++;
        }
        hc.datos().set("encuestas." + a + ".simulados", hc.datos().getInt("encuestas." + a + ".simulados", 0) + bien);
        hc.guardarYa();
        hc.plugin().bitacora().anotar("encuesta", "simular", a, n + " intentos", bien + " contados", huella + " huella repetida");
        refrescar();
        quien.sendMessage(Component.text("encuesta | " + a + " | simular | " + n + " intentos | " + bien + " contados | "
                + huella + " huella repetida", Paleta.BIEN));
    }

    private void recuento(CommandSender quien, String id) {
        Pregunta q = preguntas().get(id);
        if (q == null) {
            quien.sendMessage(Component.text("encuesta | no existe \"" + id + "\" | hay: " + ids(true), Paleta.AVISO));
            return;
        }
        ConfigurationSection d = hc.datos();
        boolean abierta = q.fija() || q.id().equals(activa());
        quien.sendMessage(Component.text("encuesta | " + q.id() + " | " + q.texto() + " | total " + total(d, q, null)
                + (q.fija() ? " | siempre abierta" : abierta ? " | abierta " + d.getString("encuestas." + q.id() + ".abierta", "?")
                : " | cerrada " + d.getString("encuestas." + q.id() + ".cerrada", "-")), Paleta.BIEN));
        linea(quien, d, q, null);
        if (q.fija()) {
            String s = semana();
            quien.sendMessage(Component.text("esta semana (" + s + "): total " + total(d, q, s), Paleta.TENUE));
            linea(quien, d, q, s);
        }
        ConfigurationSection textos = d.getConfigurationSection("encuestas." + q.id() + ".texto");
        if (textos != null && !textos.getKeys(false).isEmpty()) {
            quien.sendMessage(Component.text("  " + textos.getKeys(false).size() + " respuestas libres (en el fichero de resultados)",
                    Paleta.TENUE));
        }
    }

    private void linea(CommandSender quien, ConfigurationSection d, Pregunta q, String semana) {
        String base = "encuestas." + q.id() + (semana == null ? "" : "." + semana) + ".votos.";
        for (Map.Entry<String, Double> e : porcentajes(d, q, semana).entrySet()) {
            quien.sendMessage(Component.text("  " + e.getKey() + " · " + q.textoDe(e.getKey()) + " · "
                    + d.getInt(base + e.getKey(), 0) + " · " + e.getValue() + " %", Paleta.TENUE));
        }
    }

    // ------------------------------------------------------------- utilidades

    String activa() {
        String a = hc.datos().getString("encuestas-activa");
        return a == null || a.isBlank() ? null : a;
    }

    /** Las preguntas de la config; si no hay ninguna, las de MED sec. 4.2 y el Botin. */
    Map<String, Pregunta> preguntas() {
        ConfigurationSection s = hc.cfg().getConfigurationSection("encuesta.preguntas");
        Map<String, Pregunta> out = new LinkedHashMap<>();
        if (s != null) {
            for (String id : s.getKeys(false)) {
                ConfigurationSection p = s.getConfigurationSection(id);
                if (p == null) continue;
                List<Opcion> ops = new ArrayList<>();
                ConfigurationSection o = p.getConfigurationSection("opciones");
                if (o != null) {
                    for (String op : o.getKeys(false)) {
                        String nombre = o.getString(op + ".icono", "PAPER");
                        Material m = Material.matchMaterial(nombre == null ? "PAPER" : nombre);
                        ops.add(new Opcion(op.toLowerCase(Locale.ROOT), m == null || !m.isItem() ? Material.PAPER : m,
                                o.getString(op + ".texto", op)));
                    }
                }
                if (ops.isEmpty()) continue;
                String k = id.toLowerCase(Locale.ROOT);
                out.put(k, new Pregunta(k, p.getString("texto", id), p.getBoolean("fija", k.equals(BOTIN)),
                        Collections.unmodifiableList(ops)));
            }
        }
        return out.isEmpty() ? PREDETERMINADAS : out;
    }

    private String ids(boolean conFijas) {
        List<String> l = new ArrayList<>();
        for (Pregunta q : preguntas().values()) if (conFijas || !q.fija()) l.add(q.id());
        return String.join(", ", l);
    }

    private String semana() {
        Calendario c = hc.calendario();
        return c != null ? c.semana() : new Calendario(hc).semana();
    }

    private String dia() {
        Calendario c = hc.calendario();
        return c != null ? c.dia() : new Calendario(hc).dia();
    }

    private boolean haTasado(UUID u) {
        if (semana().equals(semanaTasaron) && tasaron.contains(u)) return true;
        Estadisticas st = hc.estadisticas();
        return st != null && hc.valor("estadisticas", () -> st.semana(u, "extracciones"), 0L) > 0;
    }

    private String huella(Player p) {
        Aduana a = hc.aduana();
        return a == null ? "" : hc.valor("aduana", () -> a.huella(p), "");
    }

    private int rango(Player p) {
        MobsLethal m = hc.plugin().mobs();
        return m == null ? 0 : hc.valor("encuesta", () -> m.rango(p), 0);
    }

    void telemetria(String ev, Player p, Map<String, Object> campos) {
        Telemetria t = hc.telemetria();
        if (t != null) t.suceso(ev, p, campos);
    }

    /** Sin colores, sin codigos y a 60 letras: lo que escriben acaba en un YAML y en un informe. */
    static String limpiar(String s) {
        if (s == null) return "";
        String l = s.replaceAll("[^\\p{L}\\p{N} .,;:¿?¡!()'%+-]", "").replaceAll("\\s+", " ").trim();
        return l.length() > LARGO_OTRA ? l.substring(0, LARGO_OTRA).trim() : l;
    }

    /** Una tarea de un solo uso a nombre del plugin, cancelada en parar() si aun no corrio. */
    void tarea(Runnable r, long ticks) {
        BukkitTask[] propia = new BukkitTask[1];
        propia[0] = hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(), () -> {
            tareas.remove(propia[0]);
            hc.seguro("encuesta", r);
        }, ticks);
        tareas.add(propia[0]);
    }

    /** Rehace la foto de los placeholders. Hilo principal. */
    void refrescar() {
        ConfigurationSection d = hc.datos();
        String a = activa();
        Set<UUID> votaron = new HashSet<>();
        if (a != null) {
            ConfigurationSection ya = d.getConfigurationSection("encuestas." + a + ".ya");
            if (ya != null) {
                for (String k : ya.getKeys(false)) {
                    try {
                        votaron.add(UUID.fromString(k));
                    } catch (IllegalArgumentException esHuella) {
                        // Las huellas van en la misma lista; no son jugadores.
                    }
                }
            }
        }
        Map<String, String> pct = new ConcurrentHashMap<>();
        for (Pregunta q : preguntas().values()) {
            for (Map.Entry<String, Double> e : porcentajes(d, q, null).entrySet()) {
                pct.put(q.id() + "_" + e.getKey(), String.valueOf(Math.round(e.getValue())));
            }
        }
        activaFoto = a;
        votaronFoto = Set.copyOf(votaron);
        pctFoto = pct;
    }

    /** %lethalworld_encuesta_pendiente% (si/no) y %lethalworld_encuesta_<id>_<opcion>% (porcentaje). */
    private String placeholder(OfflinePlayer jugador, String resto) {
        if (resto == null || resto.isEmpty()) {
            String a = activaFoto;
            return a == null ? "" : a;
        }
        String r = resto.toLowerCase(Locale.ROOT);
        if (r.equals("pendiente")) {
            if (jugador == null || activaFoto == null || !activo()) return "no";
            return votaronFoto.contains(jugador.getUniqueId()) ? "no" : "si";
        }
        String v = pctFoto.get(r);
        return v == null ? "" : v;
    }

    // --------------------------------------------------- preguntas de serie

    private static final Map<String, Pregunta> PREDETERMINADAS = new LinkedHashMap<>();

    static {
        pre("freno", "¿Qué te frena de entrar a Calamity?", false,
                "perder", "IRON_CHESTPLATE", "Perder el equipo", "premio", "CHEST", "No sé qué gano",
                "dificil", "WITHER_SKELETON_SKULL", "Es demasiado difícil", "tiempo", "CLOCK", "No tengo tiempo",
                "solo", "PLAYER_HEAD", "Entro solo y me aburro");
        pre("salvar", "Si solo pudieras salvar una cosa, ¿cuál?", false,
                "casco", "NETHERITE_HELMET", "El casco", "pechera", "NETHERITE_CHESTPLATE", "La pechera",
                "arma", "NETHERITE_SWORD", "El arma", "reliquias", "RESIN_CLUMP", "Mis Reliquias",
                "nada", "BARRIER", "Nada, entro desnudo");
        pre("premio", "¿Qué te importa más de un premio?", false,
                "fuerza", "DIAMOND_SWORD", "Que me haga más fuerte", "verse", "GLOW_INK_SAC", "Que se vea",
                "raro", "AMETHYST_SHARD", "Que sea raro", "ahorro", "HOPPER", "Que me ahorre tiempo");
        pre("sesion", "¿Cuánto tiempo seguido juegas en Calamity?", false,
                "15", "CLOCK", "Menos de 15 min", "30", "CLOCK", "15-30 min", "60", "CLOCK", "30-60 min",
                "mas", "CLOCK", "Más de 1 h");
        pre("camino", "¿Sabes cuánto te falta para tu próxima pieza del Manto?", false,
                "si", "COMPASS", "Sí, lo miro en el altar", "mas-o-menos", "MAP", "Más o menos",
                "no", "BARRIER", "No", "no-quiero", "GRAY_DYE", "No voy a por el Manto");
        pre(BOTIN, "¿Qué quieres que dé Calamity?", true,
                "piezas-manto", "NETHERITE_CHESTPLATE", "Piezas del Manto", "libros-legendary", "ENCHANTED_BOOK", "Libros LEGENDARY",
                "encantamientos", "EXPERIENCE_BOTTLE", "Encantamientos sobre el tope", "mascotas", "WOLF_SPAWN_EGG", "Mascotas raras",
                "rip", "SKELETON_SKULL", "Efectos RIP", "talismanes-gemas", "EMERALD", "Talismanes y gemas",
                "llaves", "TRIAL_KEY", "Llaves de caja");
    }

    private static void pre(String id, String texto, boolean fija, String... trios) {
        List<Opcion> ops = new ArrayList<>();
        for (int i = 0; i + 2 < trios.length; i += 3) {
            Material m = Material.matchMaterial(trios[i + 1]);
            ops.add(new Opcion(trios[i], m == null ? Material.PAPER : m, trios[i + 2]));
        }
        PREDETERMINADAS.put(id, new Pregunta(id, texto, fija, List.copyOf(ops)));
    }

    // ---------------------------------------------------------------- autotest

    /** MED sec. 4 sobre un yml de memoria: votos, huellas, semanas, resultados, que menu sale. */
    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        YamlConfiguration d = new YamlConfiguration();
        Pregunta freno = PREDETERMINADAS.get("freno");
        Pregunta botin = PREDETERMINADAS.get(BOTIN);
        String w39 = "2026-W39", w40 = "2026-W40";
        long ahora = 1_790_000_000_000L;

        int contados = 0, porHuella = 0;
        for (int i = 1; i <= 10; i++) {
            String huella = i == 10 ? "h3" : "h" + i;          // el 10.o repite la huella del 3.o
            String op = freno.opciones().get(i % freno.opciones().size()).id();
            Voto r = registrar(d, freno, "freno", w39, op, Autotest.sintetico(i), huella, true, i % 3, null, ahora);
            if (r == Voto.OK) contados++;
            else if (r == Voto.HUELLA) porHuella++;
        }
        h.igual("10 votos con una huella repetida cuentan 9", 9, contados);
        h.igual("el repetido sale por huella", 1, porHuella);
        h.igual("total de la encuesta", 9, total(d, freno, null));
        h.igual("mismo UUID otra vez", Voto.YA,
                registrar(d, freno, "freno", w39, "perder", Autotest.sintetico(1), "otra-huella", true, 0, null, ahora));
        h.igual("opcion que no existe", Voto.OPCION,
                registrar(d, freno, "freno", w39, "no-existe", Autotest.sintetico(20), "h20", true, 0, null, ahora));
        h.igual("sin tasar esta semana no vota", Voto.SIN_TASAR,
                registrar(d, freno, "freno", w39, "perder", Autotest.sintetico(21), "h21", false, 0, null, ahora));
        h.igual("una pregunta que no es la activa esta cerrada", Voto.CERRADA,
                registrar(d, freno, "salvar", w39, "perder", Autotest.sintetico(22), "h22", true, 0, null, ahora));
        h.ok("el rechazado sin tasar no deja rastro", !d.isSet("encuestas.freno.ya." + Autotest.sintetico(21)));
        h.ok("sin huella (exento) cuenta solo el UUID",
                registrar(d, freno, "freno", w39, "tiempo", Autotest.sintetico(23), "", true, 0, null, ahora) == Voto.OK);
        h.ok("una huella con puntos no rompe el YAML",
                registrar(d, freno, "freno", w39, "solo", Autotest.sintetico(24), "a.b c", true, 0, null, ahora) == Voto.OK
                        && d.isSet("encuestas.freno.ya.abc"));
        h.ok("\"otra\" con texto", registrar(d, freno, "freno", w39, OTRA, Autotest.sintetico(25), "h25", true, 0,
                "Más jefes", ahora) == Voto.OK && "Más jefes".equals(d.getString("encuestas.freno.texto." + Autotest.sintetico(25))));
        h.igual("el Botin (7 opciones) no admite \"otra\"", Voto.OPCION,
                registrar(d, botin, null, w39, OTRA, Autotest.sintetico(26), "h26", true, 0, "x", ahora));

        YamlConfiguration res = resultados(d, freno, "2026-10-10");
        h.igual("resultados: total", 12, res.getInt("total"));
        double suma = 0;
        ConfigurationSection ops = res.getConfigurationSection("opciones");
        if (ops != null) for (String k : ops.getKeys(false)) suma += ops.getDouble(k + ".pct");
        h.cerca("resultados: los pct suman 100", 100.0, suma, 0.5);
        h.ok("resultados: pregunta, cerrada, por_rango y otras",
                "¿Qué te frena de entrar a Calamity?".equals(res.getString("pregunta"))
                        && "2026-10-10".equals(res.getString("cerrada")) && res.isSet("por_rango")
                        && res.getStringList("otras").contains("Más jefes"));

        YamlConfiguration solo9 = new YamlConfiguration();
        for (int i = 1; i <= 10; i++) {
            registrar(solo9, freno, "freno", w39, "perder", Autotest.sintetico(i), i == 10 ? "h3" : "h" + i, true, 0, null, ahora);
        }
        YamlConfiguration r9 = resultados(solo9, freno, "2026-10-10");
        h.igual("fichero de resultados de la prueba del plan: total 9", 9, r9.getInt("total"));
        h.cerca("y pct 100 en la unica opcion votada", 100.0, r9.getDouble("opciones.perder.pct"), 1e-9);

        h.ok("pendiente para quien no ha votado", pendiente(d, "freno", Autotest.sintetico(50)));
        h.ok("no pendiente para quien ya voto", !pendiente(d, "freno", Autotest.sintetico(1)));
        h.ok("sin encuesta activa nada esta pendiente", !pendiente(d, null, Autotest.sintetico(50)));

        UUID u = Autotest.sintetico(30);
        h.igual("Botin: voto de la semana 39", Voto.OK, registrar(d, botin, null, w39, "llaves", u, "hb", true, 0, null, ahora));
        h.igual("Botin: segundo voto en la misma semana", Voto.YA, registrar(d, botin, null, w39, "rip", u, "hb2", true, 0, null, ahora));
        h.igual("Botin: la semana 40 vuelve a votar", Voto.OK, registrar(d, botin, null, w40, "rip", u, "hb", true, 0, null, ahora));
        h.igual("Botin: total 2", 2, total(d, botin, null));
        h.igual("Botin: semana 39 con 1", 1, total(d, botin, w39));
        h.cerca("Botin: llaves 50 % del total", 50.0, porcentajes(d, botin, null).get("llaves"), 1e-9);
        YamlConfiguration rb = resultados(d, botin, null);
        h.ok("Botin: resultados por semana", rb.getInt("por_semana." + w39 + ".total") == 1
                && rb.getInt("por_semana." + w40 + ".total") == 1);

        h.igual("tras tasar: encuesta pendiente y no vista -> encuesta", "encuesta", queMostrar(true, false, true, false, false));
        h.igual("encuesta ya vista esta semana -> Botin", BOTIN, queMostrar(true, true, true, false, false));
        h.igual("sin encuesta pendiente -> Botin", BOTIN, queMostrar(false, false, true, false, false));
        h.igual("Botin ya votado -> nada", null, queMostrar(false, false, true, true, false));
        h.igual("Botin ya visto -> nada", null, queMostrar(false, false, true, false, true));
        h.igual("sin Botin en la config -> nada", null, queMostrar(false, false, false, false, false));

        h.igual("limpiar texto libre", "Hola que tal", limpiar("  Hola que" + '\n' + '\t' + " tal" + (char) 0xA7 + " "));
        h.igual("limpiar corta a 60", 60, limpiar("x".repeat(80)).length());
        h.igual("placeholder de una opcion", "", placeholder(null, "no_existe_nunca"));
        h.igual("placeholder pendiente sin jugador", "no", placeholder(null, "pendiente"));

        h.ok("preguntas: freno, salvar, premio, sesion, camino y botin",
                preguntas().keySet().containsAll(List.of("freno", "salvar", "premio", "sesion", "camino", BOTIN)));
        h.ok("el Botin es fija y tiene 7 opciones", preguntas().get(BOTIN) != null && preguntas().get(BOTIN).fija()
                && preguntas().get(BOTIN).opciones().size() == 7);
        h.ok("autotest sin tocar hardcore-datos.yml", !hc.datos().isSet("encuestas.freno.ya." + Autotest.sintetico(1)));

        h.lineas().addAll(deseos.autotest());
        return h.lineas();
    }
}
