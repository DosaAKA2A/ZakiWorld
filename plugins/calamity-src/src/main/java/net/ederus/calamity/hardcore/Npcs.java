package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Lo que abren los cinco NPCs de la antesala de Calamity (1.2.0): el Guardian del Umbral y el
 * Forjador (las dos paginas del Altar), el Tasador (saldo, tasacion y contratos del dia), el
 * Cronista (la historia y el tutorial, Cronista) y el Cazador (el Tablero y los rankings).
 *
 * Los NPCs los pone y los cuida el staff a mano con Citizens; Calamity no los crea ni depende
 * de Citizens. Cada uno lleva un comando de clic sin -p, que Citizens ejecuta como CONSOLA
 * y en el que cambia <p> por quien hizo clic (-l -r: los dos botones, tambien con mayusculas):
 *     /npc command add -l -r calamidad abrir <p> umbral
 * (y forja, tasador, cronista o cazador en los otros cuatro).
 *
 * La regla que no se negocia: el Altar no se abre a distancia. Vende el Cristal de Regreso,
 * que es la salida, y abierto dentro de Calamity romperia la extraccion. Por eso no hay un
 * comando de jugador que lo abra: /calamidad abrir pide ederus.mundos, que tienen la consola
 * (el clic del NPC) y el staff, y aqui se repiten las comprobaciones del bloque del Altar
 * (Altar.onTocar): altar encendido y el jugador fuera de Calamity. El menu, ademas, lo vuelve
 * a mirar en cada clic (MenuAltar.accion).
 */
final class Npcs implements Listener {

    /** Los cinco: el id que va en el comando de Citizens y como se llaman para el staff. */
    enum Tipo {
        UMBRAL("umbral", "el Guardián del Umbral"),
        FORJA("forja", "el Forjador"),
        TASADOR("tasador", "el Tasador"),
        CRONISTA("cronista", "el Cronista"),
        CAZADOR("cazador", "el Cazador");

        final String id;
        final String nombre;

        Tipo(String id, String nombre) {
            this.id = id;
            this.nombre = nombre;
        }

        /** Por su id, sin mirar mayusculas ni espacios; null si no es ninguno. */
        static Tipo de(String s) {
            if (s == null) return null;
            String a = s.trim().toLowerCase(Locale.ROOT);
            for (Tipo t : values()) if (t.id.equals(a)) return t;
            return null;
        }

        static List<String> ids() {
            List<String> out = new ArrayList<>();
            for (Tipo t : values()) out.add(t.id);
            return out;
        }
    }

    /**
     * Un clic cada tanto por jugador. Los dos botones llevan el mismo comando, y un clic
     * nervioso soltaria el texto del Tasador o del Cronista dos veces.
     */
    private static final long ESPERA_MS = 1000;

    private final Hardcore hc;
    private final Cronista cronista;
    private final Map<UUID, Long> ultimoClic = new HashMap<>();

    Npcs(Hardcore hc) {
        this.hc = hc;
        this.cronista = new Cronista(hc);
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Subcomandos.lw().registrar("abrir",
                "abrir <jugador> <" + String.join("|", Tipo.ids()) + ">: lo que abre cada NPC de la antesala"
                        + " (el clic de Citizens, como consola)",
                "ederus.mundos", this::comandoAbrir, args -> switch (args.length) {
                    case 2 -> Entregas.nombresConectados();
                    case 3 -> Tipo.ids();
                    default -> List.of();
                });
        Subcomandos.calamity().registrar("cronista", "[capítulo]: la historia de Calamity y cómo se juega",
                "lethalworld.calamity", cronista::comando, args -> args.length == 2 ? cronista.sugerencias() : List.of());
        Autotest.registrar("npcs", this::autotest);
    }

    void parar() {
        HandlerList.unregisterAll(this);
        ultimoClic.clear();
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        ultimoClic.remove(e.getPlayer().getUniqueId());
    }

    // ================================================================ abrir (el clic)

    /** /calamidad abrir <jugador> <id>. */
    private void comandoAbrir(CommandSender quien, String[] args) {
        Tipo t = args.length >= 3 ? Tipo.de(args[2]) : null;
        if (t == null) {
            quien.sendMessage(Component.text("Uso: /calamidad abrir <jugador> <" + String.join("|", Tipo.ids()) + ">",
                    Paleta.AVISO));
            return;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) {
            quien.sendMessage(Component.text("No encuentro a " + args[1] + " conectado.", Paleta.AVISO));
            return;
        }
        long ahora = System.currentTimeMillis();
        Long antes = ultimoClic.get(p.getUniqueId());
        if (antes != null && ahora - antes < ESPERA_MS) return;
        ultimoClic.put(p.getUniqueId(), ahora);
        String no = abrir(p, t);
        // A la consola no se le cuenta nada: cada clic en un NPC le escribiria una linea.
        if (quien instanceof ConsoleCommandSender || quien == p) return;
        quien.sendMessage(no == null ? ComandoCalamity.mensaje("Abierto " + t.nombre + " a " + p.getName() + ".")
                : Paleta.aviso(capital(t.nombre) + " no se abre a " + p.getName() + ": " + no + "."));
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Lo que hace cada NPC. Null si se abrio; si no, por que no (al jugador ya se le ha dicho,
     * con las mismas palabras que el bloque del Altar).
     */
    String abrir(Player p, Tipo t) {
        switch (t) {
            case UMBRAL, FORJA -> {
                Altar altar = hc.altar();
                if (altar == null || !altar.activo()) {
                    p.sendMessage(ComandoCalamity.mensaje("El altar está en silencio ahora mismo."));
                    return "el altar está apagado";
                }
                if (hc.esHardcore(p)) {
                    p.sendMessage(ComandoCalamity.mensaje("El altar no escucha desde ahí dentro."));
                    return "está dentro de Calamity";
                }
                altar.menu().abrir(p, t == Tipo.FORJA ? MenuAltar.FORJA : MenuAltar.UMBRAL);
            }
            case TASADOR -> tasador(p);
            case CRONISTA -> cronista.indice(p);
            case CAZADOR -> cazador(p);
        }
        return null;
    }

    /**
     * El Tasador: lo de /calamity saldo tal cual (saldo, creditos, premios pendientes), lo tasado
     * esta semana, si la primera salida de hoy aun paga, y los contratos del dia.
     *
     * La "ultima tasacion" no sale: no se guarda en ningun sitio (Tasacion paga y se lo dice al
     * jugador en el momento; lo unico que queda son las sumas de Estadisticas y la telemetria),
     * y no se inventa.
     */
    private void tasador(Player p) {
        UUID u = p.getUniqueId();
        if (!Subcomandos.calamity().ejecutar(p, new String[]{"saldo"})) {
            Saldo s = hc.saldo();
            if (s != null) p.sendMessage(s.avisoSaldo(u));
        }
        Estadisticas st = hc.estadisticas();
        if (st != null) {
            long e = st.semana(u, "tasado-esencias"), mc = st.semana(u, "tasado-mc");
            long rel = st.semana(u, "reliquias"), salidas = st.semana(u, "extracciones");
            if (e + mc + rel + salidas == 0) {
                p.sendMessage(Component.text("  Esta semana aún no has sacado nada que tasar.", Paleta.TENUE));
            } else {
                p.sendMessage(Component.text("  Tasado esta semana: ", Paleta.TEXTO).append(Paleta.cifra(Altar.miles(e)))
                        .append(Component.text(" Esencias y ", Paleta.TEXTO)).append(Paleta.cifra(Altar.miles(mc)))
                        .append(Component.text(" MobCoins, de ", Paleta.TEXTO)).append(Paleta.cifra(rel))
                        .append(Component.text(rel == 1 ? " Reliquia en " : " Reliquias en ", Paleta.TEXTO))
                        .append(Paleta.cifra(salidas)).append(Component.text(salidas == 1 ? " salida." : " salidas.", Paleta.TEXTO)));
            }
        }
        // La primera salida del dia: Tasacion apunta el dia en primera-extraccion.<uuid>.
        Tasacion.Valores v = Tasacion.Valores.de(hc.cfg());
        Calendario cal = hc.calendario();
        if (v.primeraBase() > 0 && cal != null) {
            boolean cobrada = cal.dia().equals(hc.datos().getString("primera-extraccion." + u, ""));
            p.sendMessage(cobrada ? Component.text("  Tu primera salida de hoy ya está cobrada.", Paleta.TENUE)
                    : Component.text("  Tu primera salida de hoy paga ", Paleta.TEXTO)
                    .append(Paleta.cifra("+" + v.primeraBase())).append(Component.text(" Esencias", Paleta.TEXTO))
                    .append(Component.text(v.primeraSiTasa() > 0 ? " (" : ".", Paleta.TEXTO))
                    .append(v.primeraSiTasa() > 0 ? Paleta.cifra("+" + v.primeraSiTasa()) : Component.empty())
                    .append(Component.text(v.primeraSiTasa() > 0 ? " si tasas alguna Reliquia)." : "", Paleta.TEXTO)));
        }
        Contratos con = hc.contratos();
        if (con != null) hc.seguro("contratos", () -> con.mostrar(p, p));
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "item.book.page_turn", 0.7f, 0.8f);
    }

    /**
     * El Cazador: las tablas de la semana en el chat (si el ranking esta encendido) y el
     * Tablero, que es donde estan los Ecos con botin y las PARCAs sueltas.
     */
    private void cazador(Player p) {
        UUID u = p.getUniqueId();
        Rankings r = hc.rankings();
        if (r != null && r.activo()) {
            p.sendMessage(ComandoCalamity.mensaje(Component.text("Tablas de la semana. ")
                    .append(Component.text("Se pagan el lunes.", Paleta.TENUE))));
            for (Map.Entry<Rankings.Tabla, List<Rankings.Fila>> e : r.podioSemana(3).entrySet()) {
                Component linea = Component.text("  " + e.getKey().nombre() + ": ", Paleta.TEXTO);
                if (e.getValue().isEmpty()) linea = linea.append(Component.text("nadie todavía", Paleta.TENUE));
                int puesto = 1;
                for (Rankings.Fila f : e.getValue()) {
                    if (puesto > 1) linea = linea.append(Component.text(" · ", Paleta.SEPARADOR));
                    linea = linea.append(Component.text(puesto++ + ". ", Paleta.TENUE))
                            .append(Component.text(f.nombre(), f.jugador().equals(u) ? Paleta.BIEN : Paleta.DETALLE))
                            .append(Component.text(" " + valorRanking(e.getKey().estadistica(), f.valor()), Paleta.CIFRA));
                }
                p.sendMessage(linea);
            }
            Estadisticas st = hc.estadisticas();
            int minimo = hc.cfg().getInt("ranking.minimo-extracciones", 3);
            long llevas = st == null ? 0 : st.semana(u, "extracciones");
            p.sendMessage(Component.text("  Para cobrar hacen falta ", Paleta.TENUE).append(Paleta.cifra(minimo))
                    .append(Component.text(" salidas en la semana; llevas ", Paleta.TENUE)).append(Paleta.cifra(llevas))
                    .append(Component.text(".", Paleta.TENUE)));
        }
        Tablero tab = hc.tablero();
        if (tab != null) hc.seguro("tablero", () -> tab.abrir(p));
        else p.sendMessage(ComandoCalamity.mensaje("El Tablero no está colgado ahora mismo."));
    }

    /** Un valor de ranking como se lee: MobCoins con miles, la expedicion en horas y minutos. */
    static String valorRanking(String estadistica, long v) {
        return switch (estadistica) {
            case "tasado-mc" -> Altar.miles(v) + " MC";
            case "expedicion-max-seg" -> v >= 3600 ? v / 3600 + " h " + String.format(Locale.ROOT, "%02d", (v % 3600) / 60) + " min"
                    : v >= 60 ? v / 60 + " min" : v + " s";
            default -> Altar.miles(v);
        };
    }

    // ================================================================ autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();

        h.igual("id umbral", Tipo.UMBRAL, Tipo.de("umbral"));
        h.igual("id sin mayusculas ni espacios", Tipo.FORJA, Tipo.de("  FORJA "));
        h.igual("id que no existe", null, Tipo.de("altar"));
        h.igual("id null", null, Tipo.de(null));
        h.igual("los cinco ids", List.of("umbral", "forja", "tasador", "cronista", "cazador"), Tipo.ids());

        h.igual("ranking en MobCoins", "1.234 MC", valorRanking("tasado-mc", 1234));
        h.igual("ranking de una expedicion larga", "1 h 05 min", valorRanking("expedicion-max-seg", 3900));
        h.igual("ranking de una expedicion corta", "12 min", valorRanking("expedicion-max-seg", 750));
        h.igual("ranking de parcas", "3", valorRanking("parcas", 3));

        // Las cifras del Cronista: por secciones, listas por posicion y por id, decimales.
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(String.join("\n",
                    "parca: {minutos: 10, spawn: {minutos: 5}}",
                    "cordura: {factor-noche: 2.0, por-minuto: 1.5}",
                    "reliquias: {grados: {1: {esencias: 0.2}, 4: {mobcoins: 100}}}",
                    "aduana: {tramos-mc: [{hasta: 1500, factor: 1.0}, {hasta: 999999, factor: 0.0}]}",
                    "altar: {trueques: [{id: cristal, esencias: 16}, {id: guadana, creditos: 7}]}",
                    "eclipse: {horario: ['18:00', '22:00']}"));
        } catch (InvalidConfigurationException e) {
            h.ok("yaml de prueba: " + e.getMessage(), false);
        }
        h.igual("cifra entera", "Quieto 10 min", Cronista.resolver("Quieto {parca.minutos} min", y));
        h.igual("cifra anidada", "5", Cronista.resolver("{parca.spawn.minutos}", y));
        h.igual("decimal redondo sin coma", "x2", Cronista.resolver("x{cordura.factor-noche}", y));
        h.igual("decimal con coma", "1,5", Cronista.resolver("{cordura.por-minuto}", y));
        h.igual("clave numerica", "0,2", Cronista.resolver("{reliquias.grados.1.esencias}", y));
        h.igual("lista por posicion, con miles", "1.500", Cronista.resolver("{aduana.tramos-mc.0.hasta}", y));
        h.igual("lista por id", "16 y 7", Cronista.resolver("{altar.trueques.cristal.esencias} y {altar.trueques.GUADANA.creditos}", y));
        h.igual("lista de textos", "18:00, 22:00", Cronista.resolver("{eclipse.horario}", y));
        h.igual("ruta que no existe se queda", "{parca.nada} y {altar.trueques.9.esencias}",
                Cronista.resolver("{parca.nada} y {altar.trueques.9.esencias}", y));
        h.igual("una seccion entera no se pinta", "{parca.spawn}", Cronista.resolver("{parca.spawn}", y));
        h.igual("sinValor las encuentra", List.of("{parca.nada}"), Cronista.sinValor("{parca.minutos} {parca.nada}", y));

        // Los capitulos de serie (el config.yml del jar), resueltos contra el hardcore: del jar.
        ConfigurationSection fabrica = Cronista.fabrica(hc.plugin());
        ConfigurationSection hardcoreFabrica = Cronista.seccion(fabrica, "hardcore");
        List<Cronista.Capitulo> serie = Cronista.leer(Cronista.seccion(fabrica, "hardcore." + Cronista.RUTA));
        List<String> ids = new ArrayList<>();
        for (Cronista.Capitulo c : serie) ids.add(c.id());
        h.igual("capitulos de serie", List.of("calamity", "cordura", "parca", "eco", "esencias", "altar"), ids);
        for (Cronista.Capitulo c : serie) {
            h.ok("capitulo " + c.id() + " con titulo y texto", !c.titulo().isBlank() && !c.texto().isEmpty());
            for (String t : c.texto()) {
                List<String> sin = Cronista.sinValor(t, hardcoreFabrica);
                h.ok("capitulo " + c.id() + ": todas sus cifras salen de la config" + (sin.isEmpty() ? "" : " (falta " + sin + ")"),
                        sin.isEmpty());
                String r = Cronista.resolver(t, hardcoreFabrica);
                h.ok("capitulo " + c.id() + ": linea corta (" + r.length() + ")", r.length() <= 130);
            }
        }
        // Los de la config viva (los que lee de verdad /calamity cronista), si Dosa ha puesto los suyos.
        for (Cronista.Capitulo c : cronista.capitulos()) {
            for (String t : c.texto()) {
                List<String> sin = Cronista.sinValor(t, hc.cfg());
                h.ok("config: capitulo " + c.id() + " sin rutas rotas" + (sin.isEmpty() ? "" : " (" + sin + ")"), sin.isEmpty());
            }
        }
        h.igual("buscar por numero", 1, Cronista.buscar(serie, "2"));
        h.igual("buscar por id", 2, Cronista.buscar(serie, "PARCA"));
        h.igual("buscar fuera de rango", -1, Cronista.buscar(serie, "99"));
        h.igual("buscar cero", -1, Cronista.buscar(serie, "0"));

        h.ok("/calamidad abrir registrado", Subcomandos.lw().nombres(null).contains("abrir"));
        h.ok("/calamity cronista registrado", Subcomandos.calamity().nombres(null).contains("cronista"));
        h.igual("tab de abrir: los cinco", Tipo.ids(), Subcomandos.lw().tab(null, new String[]{"abrir", "Dosa__", ""}));
        return h.lineas();
    }
}
