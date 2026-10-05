package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * M11 · Racha de Codicia (P1, apagada de serie).
 *
 * Cada salida que tasa al menos una Reliquia de grado II o mas suma 1 (tope 5; 7 si sales con
 * equipo de verdad). Rama venta-oren: ahora suma la primera venta a Oren con grado II o mas de cada
 * entrada a Calamity (Tasacion.subeRacha), y el factor se aplica a lo que le vendes. Lo tasado se multiplica por 1 + 0,10 x racha y morir la pone a 0. La
 * codicia tambien se paga dentro: cada punto sube 2 niveles a los mobs (Hardcore.bonusNivel),
 * y el Eco nace con esos niveles. Premia volver, que es lo que se quiere medir.
 *
 * Tasar una Astilla no mantiene la racha: pide grado II para que no se sostenga con lo que
 * cae de cualquier mob.
 */
final class Racha {

    private static final String RUTA = "racha.";

    private final Hardcore hc;

    Racha(Hardcore hc) {
        this.hc = hc;
        Autotest.registrar("racha", this::autotest);
        PlaceholdersLethal.registrar("racha", (jugador, resto) -> jugador == null ? "" : String.valueOf(de(jugador.getUniqueId())));
        Subcomandos.staff().registrar("streak", "streak <player> [n]: ver o poner la Racha de Codicia", Subcomandos.PERMISO,
                (quien, args) -> {
                    OfflinePlayer op = args.length >= 2 ? Reliquias.jugador(args[1]) : null;
                    if (op == null) {
                        quien.sendMessage(ComandoCalamity.mensaje("Uso: /calamity streak <player> [n]"));
                        return;
                    }
                    if (args.length >= 3) {
                        int n;
                        try {
                            n = Integer.parseInt(args[2]);
                        } catch (NumberFormatException e) {
                            quien.sendMessage(ComandoCalamity.mensaje("La racha tiene que ser un número."));
                            return;
                        }
                        poner(op, n);
                        hc.plugin().bitacora().anotar("racha", "admin", Minijefes.nombreDe(op), String.valueOf(Math.max(0, n)),
                                quien.getName());
                    }
                    quien.sendMessage(ComandoCalamity.mensaje("Racha de " + Minijefes.nombreDe(op) + ": "
                            + de(op.getUniqueId()) + (activa() ? "." : " (la Racha está apagada en la config).")));
                },
                args -> args.length == 2 ? Reliquias.conectados() : List.of());
    }

    void parar() {
    }

    boolean activa() {
        return hc.cfg().getBoolean("racha.activa", false);
    }

    int de(UUID jugador) {
        return jugador == null ? 0 : Math.max(0, hc.datos().getInt(RUTA + jugador, 0));
    }

    /** Niveles de mas para los mobs de ese jugador (se suman en Hardcore.bonusNivel). */
    int niveles(Player p) {
        if (!activa()) return 0;
        return hc.cfg().getInt("racha.niveles-por-punto", 2) * de(p.getUniqueId());
    }

    /** Multiplicador del botin de la tasacion; salida = censo del equipo al salir. */
    double factor(Player p, Censo.Foto salida) {
        return factor(p.getUniqueId(), salida);
    }

    /** Lo mismo por UUID: la tasacion de prueba (/calamity appraise) no tiene jugador conectado. */
    double factor(UUID jugador, Censo.Foto salida) {
        if (!activa()) return 1.0;
        return factor(de(jugador), tope(salida), hc.cfg().getDouble("racha.por-punto", 0.10));
    }

    static double factor(int racha, int tope, double porPunto) {
        return 1.0 + porPunto * Math.max(0, Math.min(racha, tope));
    }

    /** El tope que pone el equipo de salida: 7 con equipo de verdad, 5 si no. */
    int tope(Censo.Foto salida) {
        ConfigurationSection c = hc.cfg();
        return tope(salida, c.getInt("racha.maximo", 5), c.getInt("racha.maximo-con-equipo", 7),
                c.getInt("racha.equipo-piezas", 3), c.getInt("racha.equipo-escalon", 12));
    }

    /**
     * Equipo de verdad = equipo-piezas piezas de escalon >= equipo-escalon que no sean
     * prestadas (Kit de Expedicion). Con equipo prestado la racha alta saldria gratis.
     */
    static int tope(Censo.Foto salida, int maximo, int conEquipo, int piezas, int escalon) {
        if (salida == null || salida.piezas() == null) return maximo;
        int buenas = 0;
        for (Censo.Pieza p : salida.piezas()) {
            if (p == null || p.escalon() < escalon) continue;
            if (prestada(p.marcas())) continue;
            buenas++;
        }
        return buenas >= piezas ? Math.max(maximo, conEquipo) : maximo;
    }

    private static boolean prestada(Set<String> marcas) {
        if (marcas == null) return false;
        for (String m : marcas) {
            String t = m == null ? "" : m.toLowerCase(Locale.ROOT);
            if (t.equals("prestado") || t.endsWith(":prestado")) return true;
        }
        return false;
    }

    /**
     * Una salida que taso grado II o mas: +1 hasta el tope. Devuelve la racha nueva.
     * p puede ser null (tasacion de prueba): entonces no hay destello.
     */
    int subir(UUID jugador, Player p, Censo.Foto salida) {
        int antes = de(jugador);
        if (!activa()) return antes;
        int nueva = Math.min(antes + 1, tope(salida));
        if (nueva == antes) return antes;
        hc.datos().set(RUTA + jugador, nueva);
        hc.marcarSucio();
        Estadisticas st = hc.estadisticas();
        if (st != null) st.maximo(jugador, "racha-max", nueva);
        if (p != null) {
            double f = factor(nueva, nueva, hc.cfg().getDouble("racha.por-punto", 0.10));
            hc.cordura().destello(p, Component.text("Racha de Codicia ×" + Tasacion.num(f).replace('.', ','), Reliquias.AMBAR), 2);
        }
        return nueva;
    }

    /** Morir la pone a cero (P-K02). */
    void alMorir(Player p) {
        UUID u = p.getUniqueId();
        int antes = de(u);
        if (antes <= 0) return;
        hc.datos().set(RUTA + u, null);
        hc.marcarSucio();
        hc.plugin().bitacora().anotar("racha", "pierde", p.getName(), String.valueOf(antes));
        p.sendMessage(ComandoCalamity.mensaje("Has muerto: tu Racha de Codicia vuelve a cero."));
    }

    /** Para el admin y las pruebas del coordinador: poner la racha a mano. */
    void poner(OfflinePlayer op, int n) {
        hc.datos().set(RUTA + op.getUniqueId(), n <= 0 ? null : n);
        hc.marcarSucio();
    }

    // ----------------------------------------------------------------- autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        h.cerca("racha 0 = x1", 1.0, factor(0, 5, 0.10), 1e-9);
        h.cerca("racha 2 = x1,2", 1.2, factor(2, 5, 0.10), 1e-9);
        h.cerca("racha 7 con tope 5 = x1,5", 1.5, factor(7, 5, 0.10), 1e-9);
        h.cerca("racha 7 con tope 7 = x1,7", 1.7, factor(7, 7, 0.10), 1e-9);
        h.igual("sin censo, tope 5", 5, tope(null, 5, 7, 3, 12));
        h.igual("tres piezas de escalon 12, tope 7", 7, tope(foto(12, 12, 12), 5, 7, 3, 12));
        h.igual("dos piezas de escalon 12, tope 5", 5, tope(foto(12, 12, 6), 5, 7, 3, 12));
        h.igual("una prestada no cuenta", 5, tope(new Censo.Foto(List.of(pieza(12, false), pieza(13, false),
                pieza(16, true)), 13.6, 16, 3, 1), 5, 7, 3, 12));
        h.ok("el placeholder sin jugador no revienta", "".equals(PlaceholdersLethal.resolver(null, "racha")));
        h.ok("racha apagada de serie", !new YamlConfiguration().getBoolean("racha.activa", false));
        return h.lineas();
    }

    private static Censo.Foto foto(int... escalones) {
        List<Censo.Pieza> ps = new java.util.ArrayList<>();
        for (int e : escalones) ps.add(pieza(e, false));
        return new Censo.Foto(ps, 0, 0, ps.size(), 0);
    }

    private static Censo.Pieza pieza(int escalon, boolean prestada) {
        return new Censo.Pieza("CHEST", "NETHERITE_CHESTPLATE", "ARMOR.PRUEBA", "PRUEBA", escalon, 0,
                prestada ? Set.of("prestado") : Set.of());
    }
}
