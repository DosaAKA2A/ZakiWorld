package net.ederus.lethalworld.hardcore;

import net.ederus.edm.comun.Compat;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * M2 punto 5 · El reparto de un minijefe (PLAN sec. 3.2).
 *
 * Antes lo cobraba todo el que daba el ultimo golpe, y eso premiaba robar el remate. Ahora:
 * - MobCoins fijas (mobs.mobcoins.minijefe, 80-120) al asesino: una sola via de MC.
 * - Esencias 1 + floor(N/25) (x f) a CADA participante con >= 10 % de su vida.
 * - Reliquia III 100 % y II 30 % al asesino.
 * - Sello de <minijefe> (IV) con un 10 % al MEJOR danador. Y piedad: cada muerte de ese
 *   tipo suma 1 a todo participante; con 8, el Sello le toca aunque no sea el mejor y la
 *   piedad vuelve a 0. El Sello es el freno del Manto (PLAN sec. 1.6): sin piedad, alguien
 *   con mala suerte podia matar treinta Heraldos sin ver uno.
 *
 * Los cinco tipos se reconocen por el id de su ficha de /esb (MinionManager.typeOf), que son
 * los nombres de fichero de Esbirros/lethal-world-minijefes.
 */
final class Minijefes {

    /** Los cinco de Calamity, con el nombre que se ve (el Sello lo lleva). */
    private static final Map<String, String> NOMBRES = new LinkedHashMap<>();

    static {
        NOMBRES.put("custodio-de-las-ruinas", "Custodio de las Ruinas");
        NOMBRES.put("centinela-de-toba", "Centinela de Toba");
        NOMBRES.put("matriarca-tejedora", "Matriarca Tejedora");
        NOMBRES.put("sanador-del-fango", "Sanador del Fango");
        NOMBRES.put("heraldo-carmes", "Heraldo Carmesí");
    }

    static final List<String> TIPOS = List.copyOf(NOMBRES.keySet());

    /** Lo que le toca a cada uno en una muerte. */
    record Parte(UUID jugador, double fraccion, boolean participa, boolean asesino, boolean mejor, int esencias,
                 long mc, List<Integer> grados, boolean sello, String porQue, int piedadAntes, int piedadDespues) {
    }

    /** Los numeros del reparto, de la config con sus valores de serie. */
    record Reglas(int base, int cadaNiveles, double participacion, Map<Integer, Double> drop, double selloProb,
                  int piedad, long mcMin, long mcMax) {

        static Reglas de(ConfigurationSection hardcore, ConfigurationSection mobs) {
            return new Reglas(hardcore.getInt("esencias.minijefe.base", 1),
                    Math.max(1, hardcore.getInt("esencias.minijefe.cada-niveles", 25)),
                    hardcore.getDouble("esencias.minijefe.participacion-minima", 0.10),
                    Grifo.probs(hardcore, "reliquias.drop.minijefe", Map.of(3, 1.0, 2, 0.30)),
                    hardcore.getDouble("reliquias.sello-minijefe.prob", 0.10),
                    Math.max(1, hardcore.getInt("reliquias.sello-minijefe.piedad", 8)),
                    mobs.getLong("mobcoins.minijefe.min", 80), mobs.getLong("mobcoins.minijefe.max", 120));
        }
    }

    private final Hardcore hc;
    private final SecureRandom azar = new SecureRandom();

    Minijefes(Hardcore hc) {
        this.hc = hc;
        Subcomandos.lw().registrar("minijefe",
                "minijefe muerte <tipo> <N> <jugador:fraccion>,...: simula un reparto (el primero es el asesino)",
                "ederus.mundos", this::comandoMuerte, this::tabMuerte);
        Subcomandos.lw().registrar("piedad", "piedad <jugador> [tipo] [n]: ver o poner la piedad de los Sellos",
                "ederus.mundos", this::comandoPiedad, args -> switch (args.length) {
                    case 2 -> Reliquias.conectados();
                    case 3 -> TIPOS;
                    default -> List.of();
                });
        PlaceholdersLethal.registrar("piedad", (jugador, tipo) -> {
            if (jugador == null || tipo == null || tipo.isBlank()) return "";
            return piedad(jugador.getUniqueId(), tipo) + "/" + Reglas.de(hc.cfg(), mobsCfg()).piedad();
        });
    }

    void parar() {
    }

    /** "heraldo-carmes" -> "Heraldo Carmesí"; uno que no conoce, con los guiones como espacios. */
    static String nombre(String tipo) {
        if (tipo == null || tipo.isBlank()) return "minijefe";
        String n = NOMBRES.get(tipo.toLowerCase(Locale.ROOT));
        if (n != null) return n;
        String t = tipo.replace('-', ' ');
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** El nombre para la Bitacora; uno que el servidor no conoce sale por su UUID. */
    static String nombreDe(OfflinePlayer op) {
        String n = op == null ? null : op.getName();
        return n != null ? n : (op == null ? "?" : op.getUniqueId().toString());
    }

    private ConfigurationSection mobsCfg() {
        ConfigurationSection s = hc.plugin().getConfig().getConfigurationSection("mobs");
        return s == null ? new YamlConfiguration() : s;
    }

    int piedad(UUID jugador, String tipo) {
        return hc.datos().getInt("piedad." + jugador + "." + tipo.toLowerCase(Locale.ROOT), 0);
    }

    private void piedad(UUID jugador, String tipo, int n) {
        hc.datos().set("piedad." + jugador + "." + tipo.toLowerCase(Locale.ROOT), Math.max(0, n));
        hc.marcarSucio();
    }

    // ------------------------------------------------------------------ reparto

    /**
     * Muerte real (la llama el Grifo). danos = lo que le quito cada jugador; se pasa a
     * fraccion de su vida maxima. El asesino va primero aunque no aparezca en el mapa.
     */
    void alMorir(LivingEntity mob, Player asesino, int nivel, String tipo, Map<UUID, Double> danos) {
        double vida = Math.max(1, Compat.getAttribute(mob, "max_health", Math.max(1, mob.getHealth())));
        LinkedHashMap<OfflinePlayer, Double> fr = new LinkedHashMap<>();
        if (asesino != null) fr.put(asesino, danos == null ? 0 : danos.getOrDefault(asesino.getUniqueId(), 0.0) / vida);
        if (danos != null) {
            for (Map.Entry<UUID, Double> d : danos.entrySet()) {
                if (asesino != null && d.getKey().equals(asesino.getUniqueId())) continue;
                fr.put(hc.plugin().getServer().getOfflinePlayer(d.getKey()), d.getValue() / vida);
            }
        }
        if (fr.isEmpty()) return;
        repartir(tipo, nivel, asesino, fr);
    }

    /** Para /lw hardcore minijefe muerte: el primero de fracciones es el asesino. */
    void simularMuerte(String tipo, int nivel, LinkedHashMap<OfflinePlayer, Double> fracciones) {
        if (fracciones == null || fracciones.isEmpty()) return;
        repartir(tipo, nivel, fracciones.keySet().iterator().next(), fracciones);
    }

    private void repartir(String tipo, int nivel, OfflinePlayer asesino, LinkedHashMap<OfflinePlayer, Double> fracciones) {
        String t = tipo == null || tipo.isBlank() ? null : tipo.toLowerCase(Locale.ROOT);
        Reglas r = Reglas.de(hc.cfg(), mobsCfg());
        Grifo grifo = hc.grifo();
        Map<UUID, OfflinePlayer> quien = new HashMap<>();
        LinkedHashMap<UUID, Double> fr = new LinkedHashMap<>();
        Map<UUID, Integer> piedadAntes = new HashMap<>();
        Map<UUID, Double> f = new HashMap<>();
        for (Map.Entry<OfflinePlayer, Double> e : fracciones.entrySet()) {
            UUID u = e.getKey().getUniqueId();
            quien.put(u, e.getKey());
            fr.put(u, e.getValue() == null ? 0 : e.getValue());
            if (t != null) piedadAntes.put(u, piedad(u, t));
            f.put(u, grifo == null ? 1.0 : grifo.f(u));
        }
        Eclipse ec = hc.eclipse();
        double eclipse = ec == null ? 1.0 : hc.valor("eclipse", ec::factorBotin, 1.0);
        List<Parte> partes = planificar(t, nivel, asesino == null ? null : asesino.getUniqueId(), fr, piedadAntes,
                f, eclipse, azar::nextDouble, r);

        Aduana ad = hc.aduana();
        Reliquias rel = hc.reliquias();
        boolean hayReliquias = rel != null && rel.activas();
        List<String> resumen = new ArrayList<>();
        for (Parte p : partes) {
            OfflinePlayer op = quien.get(p.jugador());
            resumen.add(nombreDe(op) + ":" + Math.round(p.fraccion() * 100) + "%");
            if (t != null && p.participa()) piedad(p.jugador(), t, p.piedadDespues());

            List<ItemStack> items = new ArrayList<>();
            if (hayReliquias) {
                for (int g : p.grados()) items.add(rel.crear(g, "minijefe", null, nivel, null, false));
                if (p.sello()) items.add(rel.crear(4, "minijefe", Reliquias.SELLO, nivel, t, false));
            }
            if (ad != null && (p.esencias() > 0 || p.mc() > 0 || !items.isEmpty())) {
                Aduana.Pago pago = ad.pagar(op, "minijefe", p.esencias(), p.mc(), items,
                        "minijefe " + (t == null ? "?" : t) + " N" + nivel);
                if (pago != null && grifo != null) {
                    grifo.apuntarEsencias(p.jugador(), pago.esencias());
                    grifo.destelloEsencias(op.getPlayer(), pago.esencias(), pago.mc());
                }
            }
            if (p.sello()) {
                hc.plugin().bitacora().anotar("minijefe", "sello", nombreDe(op), t, p.porQue(),
                        "piedad " + p.piedadAntes());
            }
            if (t != null && p.participa()) {
                Map<String, Object> campos = new LinkedHashMap<>();
                campos.put("minijefe", t);
                campos.put("valor", p.piedadDespues());
                campos.put("sello", p.sello() ? "si" : "no");
                campos.put("mejor_danador", p.mejor() ? "si" : "no");
                Telemetria te = hc.telemetria();
                if (te != null) hc.seguro("telemetria", () -> te.suceso("piedad", op, campos));
            }
            if (p.participa()) {
                Estadisticas st = hc.estadisticas();
                if (st != null) st.sumar(p.jugador(), "minijefes", 1);
                if (grifo != null) grifo.minijefeMuerto(p.jugador());
            }
            if (p.asesino() && op.getPlayer() != null) {
                Contratos ct = hc.contratos();
                if (ct != null) hc.seguro("contratos", () -> ct.progreso(op.getPlayer(), "minijefe", 1));
            }
        }
        hc.plugin().bitacora().anotar("minijefe", "muere", t == null ? "?" : t, "N " + nivel,
                String.join(",", resumen));
        hc.marcarSucio();
    }

    /**
     * El reparto sin Bukkit ni escrituras: quien cobra que. El orden de fracciones se respeta
     * (el asesino, primero). Las tiradas salen de azar en este orden: MC del asesino, sus
     * Reliquias por grado de mayor a menor, y la del Sello del mejor danador.
     */
    static List<Parte> planificar(String tipo, int nivel, UUID asesino, LinkedHashMap<UUID, Double> fracciones,
                                  Map<UUID, Integer> piedadAntes, Map<UUID, Double> f, double eclipse,
                                  DoubleSupplier azar, Reglas r) {
        UUID mejor = null;
        double top = -1;
        for (Map.Entry<UUID, Double> e : fracciones.entrySet()) {
            if (e.getValue() >= r.participacion() && e.getValue() > top) {
                top = e.getValue();
                mejor = e.getKey();
            }
        }
        int esBase = r.base() + nivel / r.cadaNiveles();
        List<Parte> out = new ArrayList<>();
        for (Map.Entry<UUID, Double> e : fracciones.entrySet()) {
            UUID u = e.getKey();
            double fu = f.getOrDefault(u, 1.0);
            boolean participa = e.getValue() >= r.participacion();
            boolean esAsesino = u.equals(asesino);
            boolean esMejor = u.equals(mejor);
            int esencias = participa ? (int) Math.round(esBase * fu * eclipse) : 0;

            long mc = 0;
            List<Integer> grados = new ArrayList<>();
            if (esAsesino) {
                long min = Math.min(r.mcMin(), r.mcMax()), max = Math.max(r.mcMin(), r.mcMax());
                mc = min + (long) Math.floor(azar.getAsDouble() * (max - min + 1));
                List<Integer> orden = new ArrayList<>(r.drop().keySet());
                orden.sort((a, b) -> b - a);
                for (int g : orden) {
                    if (azar.getAsDouble() < r.drop().get(g) * fu * eclipse) grados.add(g);
                }
            }

            int antes = piedadAntes.getOrDefault(u, 0);
            int despues = antes;
            boolean sello = false;
            String porQue = null;
            if (tipo != null && participa) {
                despues = antes + 1;
                if (esMejor && azar.getAsDouble() < r.selloProb() * fu * eclipse) {
                    sello = true;
                    porQue = "tirada";
                } else if (despues >= r.piedad()) {
                    sello = true;
                    porQue = "piedad";
                }
                if (sello) despues = 0;
            }
            out.add(new Parte(u, e.getValue(), participa, esAsesino, esMejor, esencias, mc, grados, sello, porQue,
                    antes, despues));
        }
        return out;
    }

    // ------------------------------------------------------------------ comandos

    /** minijefe muerte <tipo> <N> Dosa__:0.6,Otro:0.3,Tercero:0.05 */
    private void comandoMuerte(CommandSender quien, String[] args) {
        if (args.length < 5 || !args[1].equalsIgnoreCase("muerte")) {
            quien.sendMessage(ComandoCalamity.mensaje(
                    "Uso: /lw hardcore minijefe muerte <tipo> <N> <jugador:fraccion>,... (el primero es el asesino)"));
            return;
        }
        String tipo = args[2].toLowerCase(Locale.ROOT);
        int nivel;
        try {
            nivel = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            quien.sendMessage(ComandoCalamity.mensaje("El nivel tiene que ser un número."));
            return;
        }
        LinkedHashMap<OfflinePlayer, Double> fr = new LinkedHashMap<>();
        String lista = String.join(",", java.util.Arrays.copyOfRange(args, 4, args.length));
        for (String trozo : lista.split(",")) {
            if (trozo.isBlank()) continue;
            String[] kv = trozo.trim().split(":");
            if (kv.length != 2) {
                quien.sendMessage(ComandoCalamity.mensaje("No entiendo \"" + trozo + "\": jugador:fraccion."));
                return;
            }
            double v;
            try {
                v = Double.parseDouble(kv[1].replace("%", "").replace(',', '.'));
            } catch (NumberFormatException e) {
                quien.sendMessage(ComandoCalamity.mensaje("Fraccion rara en \"" + trozo + "\"."));
                return;
            }
            // 0.6 o 60: lo que pasa de 1 se lee como porcentaje.
            if (v > 1) v /= 100.0;
            OfflinePlayer op = Reliquias.jugador(kv[0]);
            if (op == null) {
                quien.sendMessage(ComandoCalamity.mensaje("No encuentro a " + kv[0] + "."));
                return;
            }
            fr.put(op, v);
        }
        if (fr.isEmpty()) {
            quien.sendMessage(ComandoCalamity.mensaje("Hace falta al menos un jugador."));
            return;
        }
        simularMuerte(tipo, nivel, fr);
        quien.sendMessage(ComandoCalamity.mensaje("Reparto de " + nombre(tipo) + " N" + nivel
                + " hecho: mira la Bitácora (pago | ... | minijefe)."));
    }

    private List<String> tabMuerte(String[] args) {
        return switch (args.length) {
            case 2 -> List.of("muerte");
            case 3 -> TIPOS;
            case 4 -> List.of("50");
            case 5 -> {
                List<String> out = new ArrayList<>();
                for (String n : Reliquias.conectados()) out.add(n + ":1.0");
                yield out;
            }
            default -> List.of();
        };
    }

    /** piedad <jugador> [tipo] [n] */
    private void comandoPiedad(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(ComandoCalamity.mensaje("Uso: /lw hardcore piedad <jugador> [tipo] [n]"));
            return;
        }
        OfflinePlayer op = Reliquias.jugador(args[1]);
        if (op == null) {
            quien.sendMessage(ComandoCalamity.mensaje("No encuentro a ese jugador."));
            return;
        }
        int tope = Reglas.de(hc.cfg(), mobsCfg()).piedad();
        if (args.length < 4) {
            List<String> tipos = args.length == 3 ? List.of(args[2].toLowerCase(Locale.ROOT)) : TIPOS;
            StringBuilder sb = new StringBuilder("Piedad de " + op.getName() + ":");
            for (String t : tipos) sb.append(" ").append(t).append(" ").append(piedad(op.getUniqueId(), t)).append("/").append(tope);
            quien.sendMessage(ComandoCalamity.mensaje(sb.toString()));
            return;
        }
        int n;
        try {
            n = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            quien.sendMessage(ComandoCalamity.mensaje("La piedad es un número."));
            return;
        }
        String t = args[2].toLowerCase(Locale.ROOT);
        piedad(op.getUniqueId(), t, n);
        hc.plugin().bitacora().anotar("minijefe", "piedad", op.getName(), t, String.valueOf(Math.max(0, n)),
                "admin " + quien.getName());
        quien.sendMessage(ComandoCalamity.mensaje("Piedad de " + op.getName() + " con " + nombre(t) + ": "
                + Math.max(0, n) + "/" + tope + "."));
    }

    // ----------------------------------------------------------------- autotest

    /** Pruebas del reparto; las corre el autotest "grifo" (el modulo que espera probar.py). */
    static void probar(Autotest.Hoja h) {
        Reglas r = Reglas.de(new YamlConfiguration(), new YamlConfiguration());
        UUID dosa = Autotest.sintetico(1), otro = Autotest.sintetico(2), tercero = Autotest.sintetico(3);
        LinkedHashMap<UUID, Double> fr = new LinkedHashMap<>();
        fr.put(dosa, 0.6);
        fr.put(otro, 0.3);
        fr.put(tercero, 0.05);
        DoubleSupplier nunca = () -> 0.999_999;
        List<Parte> p = planificar("heraldo-carmes", 50, dosa, fr, Map.of(), Map.of(), 1.0, nunca, r);
        h.igual("minijefe N50: Esencias del asesino", 3, p.get(0).esencias());
        h.ok("minijefe: MC del asesino entre 80 y 120", p.get(0).mc() >= 80 && p.get(0).mc() <= 120);
        h.igual("minijefe: Esencias de Otro (30 %)", 3, p.get(1).esencias());
        h.igual("minijefe: Otro no cobra MC", 0L, p.get(1).mc());
        h.ok("minijefe: Tercero (5 %) no cobra nada", !p.get(2).participa() && p.get(2).esencias() == 0
                && p.get(2).mc() == 0 && !p.get(2).sello());
        h.ok("minijefe: la III es segura para el asesino", p.get(0).grados().contains(3));
        h.ok("minijefe: sin suerte no hay II ni Sello", !p.get(0).grados().contains(2) && !p.get(0).sello());
        h.igual("minijefe: piedad +1 a Dosa", 1, p.get(0).piedadDespues());
        h.igual("minijefe: piedad +1 a Otro", 1, p.get(1).piedadDespues());
        h.igual("minijefe: Tercero sin piedad", 0, p.get(2).piedadDespues());
        h.ok("minijefe: Dosa es el mejor danador", p.get(0).mejor() && !p.get(1).mejor());

        List<Parte> q = planificar("heraldo-carmes", 50, dosa, fr, Map.of(otro, 7), Map.of(), 1.0, nunca, r);
        h.ok("piedad 7 + esta = 8: el Sello va a Otro", q.get(1).sello() && "piedad".equals(q.get(1).porQue()));
        h.igual("y su piedad vuelve a 0", 0, q.get(1).piedadDespues());
        h.ok("el mejor danador sin suerte no lo tiene", !q.get(0).sello());

        DoubleSupplier siempre = () -> 0.0;
        List<Parte> s = planificar("heraldo-carmes", 50, dosa, fr, Map.of(), Map.of(), 1.0, siempre, r);
        h.ok("con suerte el Sello va al mejor danador", s.get(0).sello() && "tirada".equals(s.get(0).porQue()));
        h.igual("con suerte, III y II", List.of(3, 2), s.get(0).grados());
        h.igual("con suerte, MC al minimo", 80L, s.get(0).mc());

        List<Parte> sinTipo = planificar(null, 50, dosa, fr, Map.of(), Map.of(), 1.0, siempre, r);
        h.ok("sin tipo no hay Sello ni piedad", !sinTipo.get(0).sello() && sinTipo.get(0).piedadDespues() == 0);
        List<Parte> n100 = planificar("heraldo-carmes", 100, dosa, fr, Map.of(), Map.of(dosa, 0.2), 1.0, nunca, r);
        h.igual("N100 con f 0,2: 5 x 0,2 = 1 Esencia", 1, n100.get(0).esencias());
        h.igual("N100 con f 1 para Otro: 5 Esencias", 5, n100.get(1).esencias());
        h.igual("nombre del Heraldo", "Heraldo Carmesí", nombre("heraldo-carmes"));
        h.igual("nombre de uno que no conoce", "Rey de prueba", nombre("rey-de-prueba"));
    }
}
