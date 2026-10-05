package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * M3 · La venta a Oren: lo que vale lo que sacas de Calamity.
 *
 * Calamity 1.11 (rama venta-oren) · Dosa: "esa mecanica de la tasacion no me gusta, siento que los
 * usuarios no la entenderian". Antes, al cruzar la puerta, las Reliquias se vendian solas y las Esencias
 * pasaban al saldo sin que nadie lo viera. Ahora salir no vende nada: el jugador se lleva sus cosas y se
 * las vende a Oren, el mercader del spawn de Calamity, en su menu (MenuTasador), que las detecta en el
 * inventario y dice cuanto da cada una. Los numeros no cambian: la misma cuenta de antes (contar), los
 * mismos topes del dia, la misma caducidad y los mismos extras de las especiales. Lo que hacia la
 * Tasacion al cobrar lo hace ahora vender(): un solo Aduana.pagar(p, "tasacion", ...) por venta,
 * creditos de las IV (Sello, Fragmento de Guadana, Marca de Eco), tiradas de Llave del Caos, puntos de
 * clan, estadisticas (hitos y rankings), contratos de Reliquias y telemetria ("venta").
 *
 * Lo que sigue siendo de la SALIDA (alSalir, desde Hardcore.sacar con extraccion): la primera salida del
 * dia (sus Esencias de mas), los contratos ya cumplidos que se cobran al salir vivo, la telemetria "sale"
 * y la encuesta. La Racha de Codicia sube con la venta, una vez por entrada a Calamity (subeRacha): asi
 * no se puede subir saliendo y entrando con la misma Reliquia, y vender por partes tampoco la sube dos
 * veces.
 *
 * Una Reliquia con UUID solo paga si esta emitida en reliquias.log y no esta cobrada: la falsa y la
 * duplicada se quitan igual y se apuntan (la duplicada avisa al staff). Las I-II no llevan UUID; las
 * acota el tope diario (60 / 30). Calamity 1.12: Oren solo retira las I-II que paga (retirar); lo que
 * pasa del tope se queda en el inventario del jugador para venderlo otro dia. El exceso que aun se
 * anota es el de las ventas que no salen de un inventario (la de ausente y appraise).
 */
final class Tasacion {

    /** Lo que pagaria (o pago) una venta. */
    record Resumen(int esencias, long mobcoins, List<String> lineas) {
    }

    /** Una Reliquia leida del objeto (cantidad = unidades del monton). */
    record Pieza(int grado, String especial, String id, long nacio, int nivel, String minijefe, boolean valida,
                 int cantidad) {
    }

    /** Los numeros de la venta, de la config con los de serie (PLAN sec. 3.1). */
    record Valores(double[] esencias, long[] mc, int[] topeDia, Set<Integer> apilables, long caducaMillis,
                   int fragmentoNivel, double llaveCampana, double llaveLagrima, int primeraBase, int primeraSiTasa) {

        static Valores de(ConfigurationSection c) {
            double[] es = {0, c.getDouble("reliquias.grados.1.esencias", 0.2), c.getDouble("reliquias.grados.2.esencias", 1),
                    c.getDouble("reliquias.grados.3.esencias", 3), c.getDouble("reliquias.grados.4.esencias", 6)};
            long[] mc = {0, c.getLong("reliquias.grados.1.mobcoins", 5), c.getLong("reliquias.grados.2.mobcoins", 15),
                    c.getLong("reliquias.grados.3.mobcoins", 40), c.getLong("reliquias.grados.4.mobcoins", 100)};
            int[] tope = {0, c.getInt("reliquias.tope-dia.1", 60), c.getInt("reliquias.tope-dia.2", 30),
                    Integer.MAX_VALUE, Integer.MAX_VALUE};
            return new Valores(es, mc, tope, Reliquias.apilables(c),
                    Math.max(1, c.getInt("reliquias.caduca-dias", 14)) * 86_400_000L,
                    c.getInt("reliquias.especiales.campana-parca.fragmento-nivel-minimo", 40),
                    c.getDouble("reliquias.especiales.campana-parca.llave-caos-iv", 0.25),
                    c.getDouble("reliquias.especiales.lagrima-eco.llave-caos-iv", 0.20),
                    c.getInt("esencias.primera-extraccion-dia.base", 2),
                    c.getInt("esencias.primera-extraccion-dia.si-tasa", 1));
        }
    }

    /** El recuento de una venta, sin escribir nada. */
    static final class Cuenta {
        double esencias;
        long mc;
        /** Unidades pagadas por grado (indice 1-4). */
        final int[] porGrado = new int[5];
        /** I-II que pasaron el tope del dia, por grado. */
        final int[] exceso = new int[5];
        /** Apilables (sin UUID) pagadas, por grado: lo unico que cuenta para el tope del dia. */
        final int[] monton = new int[5];
        final List<String> cobrar = new ArrayList<>();
        final List<String> falsas = new ArrayList<>();
        final List<String> duplicadas = new ArrayList<>();
        final List<String> caducadas = new ArrayList<>();
        /** sello:<id>, fragmento, marca (en el orden en que salen). */
        final List<String> creditos = new ArrayList<>();
        /** Minijefes de los Sellos vendidos (anuncio P-W05). */
        final List<String> sellos = new ArrayList<>();
        /** [especial, probabilidad] de cada tirada de Llave del Caos. */
        final List<Object[]> llaves = new ArrayList<>();
        int validas;
        boolean dosOMas;

        int nulas() {
            return falsas.size() + duplicadas.size() + caducadas.size();
        }
    }

    /**
     * Un tipo de lo que Oren compra, tal como lo enseña su menu: todas las piezas con el mismo nombre
     * (grado, especial y minijefe) o las Esencias. cuenta: lo que valen esas piezas solas (para las I-II,
     * con lo que queda del tope de hoy); null en las Esencias, que valen una cada una.
     */
    record Grupo(String clave, String nombre, TextColor color, Material material, int grado, String especial,
                 int cantidad, Cuenta cuenta, long caducaPrimero) {

        boolean esencias() {
            return ESENCIAS.equals(clave);
        }

        /** Las I-II de este tipo que pasan del tope de hoy: Oren no las retira, se quedan contigo. */
        int quedan() {
            return cuenta == null ? 0 : cuenta.exceso[1] + cuenta.exceso[2];
        }

        /** Lo que Oren se lleva hoy de este tipo: lo que paga y lo que no vale nada (falsas, caducadas...). */
        int compra() {
            return Math.max(0, cantidad - quedan());
        }
    }

    /**
     * Lo que Oren le compraria ahora mismo: los tipos (las Reliquias de grado alto primero y las Esencias
     * al final), la cuenta de todo junto, el factor de la Racha y lo que se pagaria con el.
     */
    record Oferta(List<Grupo> grupos, Cuenta total, int esencias, double factor, int pagaEsencias, long pagaMc,
                  int ya1, int ya2) {

        boolean vacia() {
            return grupos.isEmpty();
        }

        int piezas() {
            int n = 0;
            for (Grupo g : grupos) n += g.cantidad();
            return n;
        }

        /** Las I-II que pasan del tope de hoy vendiendolo todo: se quedan en el inventario. */
        int quedan() {
            return total.exceso[1] + total.exceso[2];
        }

        /** Lo que Oren se lleva vendiendolo todo (las Esencias incluidas). */
        int compra() {
            return Math.max(0, piezas() - quedan());
        }
    }

    /** Clave del grupo de las Esencias fisicas. */
    static final String ESENCIAS = "esencias";

    private final Hardcore hc;
    private final SecureRandom azar = new SecureRandom();

    Tasacion(Hardcore hc) {
        this.hc = hc;
        Autotest.registrar("tasacion", this::autotest);
        Autotest.registrar("venta", this::autotestVenta);
        Subcomandos.staff().registrar("appraise",
                "appraise <player> <g1> <g2> <g3> [special:tier:N[:valid|:miniboss] ...] | appraise <player> reset: vende Reliquias virtuales",
                Subcomandos.PERMISO, this::comando, this::tab);
    }

    void parar() {
    }

    private boolean activas() {
        Reliquias rel = hc.reliquias();
        return rel != null && rel.activas();
    }

    // ------------------------------------------------------------------ la entrada y la salida

    /** Hardcore.alLlegar: empieza una entrada nueva (la Racha sube como mucho una vez en cada una). */
    void alEntrar(Player p) {
        hc.datos().set("expedicion." + p.getUniqueId(), String.valueOf(System.currentTimeMillis()));
        hc.marcarSucio();
    }

    private String expedicion(UUID u) {
        return hc.datos().getString("expedicion." + u, "");
    }

    /**
     * Hardcore.sacar con extraccion (puerta o /calamity extract), antes del teleport. No vende nada: el
     * jugador se lleva lo que lleva. Paga la primera salida del dia, cobra los contratos que esperaban a
     * la salida, cierra la expedicion en la telemetria, abre la encuesta y, si se lleva algo sin vender,
     * le recuerda que Oren se lo compra.
     */
    void alSalir(Player p, String motivo) {
        UUID u = p.getUniqueId();
        String m = motivo == null ? "?" : motivo;
        String dia = hc.calendario() != null ? hc.calendario().dia() : "";
        Valores v = Valores.de(hc.cfg());

        List<ItemStack> lleva = activas() ? reliquiasEncima(p) : List.of();
        Cuenta k = cuentaSin(p, lleva);
        int[] porGrado = new int[5];
        Reliquias rel = hc.reliquias();
        if (rel != null) for (ItemStack it : lleva) porGrado[Math.max(1, Math.min(4, rel.grado(it)))] += it.getAmount();
        Saldo s = hc.saldo();
        int esencias = s == null ? 0 : s.encima(p);

        boolean primera = !dia.equals(hc.datos().getString("primera-extraccion." + u, ""));
        boolean vendio = dia.equals(hc.datos().getString("vendio-dia." + u, ""))
                && expedicion(u).equals(hc.datos().getString("vendio." + u, ""));
        int extra = primera ? primera(k.validas, vendio, v) : 0;
        int pagadas = 0;
        if (primera) {
            hc.datos().set("primera-extraccion." + u, dia);
            hc.marcarSucio();
            Aduana ad = hc.aduana();
            if (ad != null && extra > 0) {
                Aduana.Pago pago = ad.pagar(p, "tasacion", extra, 0, List.of(), "primera-salida:" + m);
                pagadas = pago == null ? 0 : pago.esencias();
            }
            if (pagadas > 0) {
                p.sendMessage(ComandoCalamity.mensaje(Component.text("Por ser tu primera salida del día, ganas ")
                        .append(Paleta.cifra(pagadas)).append(Component.text(pagadas == 1 ? " Esencia más." : " Esencias más."))));
            }
        }

        List<String> contratos = List.of();
        Contratos ct = hc.contratos();
        if (ct != null) contratos = hc.valor("contratos", () -> ct.cobrarEnTasacion(p), List.of());

        Telemetria te = hc.telemetria();
        if (te != null) {
            Map<String, Object> t = new LinkedHashMap<>();
            Map<String, Object> grados = new LinkedHashMap<>();
            for (int g = 1; g <= 4; g++) grados.put(String.valueOf(g), porGrado[g]);
            // "tasado" es lo que se SACA vivo (StatsTelemetria lo cuenta asi); se vende luego, con Oren.
            t.put("tasado", grados);
            t.put("esencias", pagadas);
            t.put("mc", 0);
            t.put("esencias_encima", esencias);
            t.put("primera", primera ? "si" : "no");
            t.put("contratos", contratos);
            hc.seguro("telemetria", () -> te.sale(p, m, t));
        }
        int piezas = esencias;
        for (int g = 1; g <= 4; g++) piezas += porGrado[g];
        if (piezas > 0) p.sendMessage(ComandoCalamity.mensaje(texto("salir-con-objetos",
                "Te llevas Reliquias o Esencias sin vender. Oren te las compra en el spawn de Calamity; fuera no se pueden guardar.")));
        Encuesta enc = hc.encuesta();
        if (enc != null) hc.seguro("encuesta", () -> enc.trasTasar(p));
    }

    /** Un texto de venta.mensajes, con el de serie si la config no lo trae. */
    String texto(String clave, String deSerie) {
        String t = hc.cfg().getString("venta.mensajes." + clave);
        return t == null || t.isBlank() ? deSerie : t;
    }

    // ------------------------------------------------------------------ vender

    /** Donde compra Oren: fuera de Calamity o en su spawn (como el Altar), nunca a mitad de expedicion. */
    boolean puedeVender(Player p) {
        return Marco.puedeAltar(hc, p);
    }

    /** Las Reliquias del inventario del jugador (tambien armadura y mano secundaria), sin el cursor. */
    List<ItemStack> reliquiasEncima(Player p) {
        Reliquias rel = hc.reliquias();
        List<ItemStack> out = new ArrayList<>();
        if (rel == null) return out;
        for (ItemStack it : p.getInventory().getContents()) if (rel.es(it)) out.add(it);
        return out;
    }

    /** La clave del grupo de una Reliquia: grado, especial y minijefe (lo que le da nombre). */
    static String clave(int grado, String especial, String minijefe) {
        return "r:" + Math.max(1, Math.min(4, grado)) + ":" + (especial == null ? "-" : especial) + ":"
                + (minijefe == null || minijefe.isBlank() ? "-" : minijefe.toLowerCase(Locale.ROOT));
    }

    /** El grupo de un objeto: el de su Reliquia, ESENCIAS o null si Oren no lo compra. */
    String clave(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        if (hc.items().esEsencia(it)) return ESENCIAS;
        Reliquias rel = hc.reliquias();
        if (rel == null || !rel.es(it)) return null;
        return clave(rel.grado(it), rel.especial(it), rel.minijefe(it));
    }

    /**
     * Lo que Oren compraria ahora: cada tipo con su cuenta y la de todo junto, con la Racha. Solo lee.
     * Las I-II de cada grupo se cuentan con el tope que queda hoy; el total, igual que una venta de todo.
     */
    Oferta oferta(Player p) {
        Reliquias rel = hc.reliquias();
        UUID u = p.getUniqueId();
        Valores v = Valores.de(hc.cfg());
        int[] ya = yaHoy(u);
        long ahora = System.currentTimeMillis();
        List<Grupo> grupos = new ArrayList<>();
        Cuenta total = new Cuenta();
        if (rel != null && activas()) {
            Reliquias.Registro reg = rel.registro();
            List<ItemStack> encima = new ArrayList<>(reliquiasEncima(p));
            encima.sort((a, b) -> Integer.compare(rel.grado(b), rel.grado(a)));
            Map<String, List<Pieza>> piezas = new LinkedHashMap<>();
            Map<String, ItemStack> muestra = new LinkedHashMap<>();
            Map<String, Long> caduca = new LinkedHashMap<>();
            List<Pieza> todas = new ArrayList<>();
            for (ItemStack it : encima) {
                String c = clave(it);
                Pieza pz = leer(rel, it);
                piezas.computeIfAbsent(c, x -> new ArrayList<>()).add(pz);
                muestra.putIfAbsent(c, it);
                if (pz.id() != null && pz.nacio() > 0) caduca.merge(c, pz.nacio() + v.caducaMillis(), Math::min);
                todas.add(pz);
            }
            for (Map.Entry<String, List<Pieza>> e : piezas.entrySet()) {
                ItemStack it = muestra.get(e.getKey());
                int g = Math.max(1, Math.min(4, rel.grado(it)));
                int n = 0;
                for (Pieza pz : e.getValue()) n += pz.cantidad();
                grupos.add(new Grupo(e.getKey(), rel.nombreDe(g, rel.especial(it), rel.minijefe(it)), Reliquias.color(g, rel.especial(it)),
                        it.getType(), g, rel.especial(it), n, contar(e.getValue(), reg, ya[1], ya[2], v, ahora),
                        caduca.getOrDefault(e.getKey(), 0L)));
            }
            total = contar(todas, reg, ya[1], ya[2], v, ahora);
        }
        Saldo s = hc.saldo();
        int esencias = s == null ? 0 : s.encima(p);
        if (esencias > 0) {
            grupos.add(new Grupo(ESENCIAS, "Esencia de Calamidad", Ficha.tono("esencia").fuerte(),
                    materialEsencia(p), 0, null, esencias, null, 0L));
        }
        double f = factor(p);
        return new Oferta(grupos, total, esencias, f, (int) Math.floor(total.esencias * f + 1e-9),
                Math.round(total.mc * f), ya[1], ya[2]);
    }

    private Material materialEsencia(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (hc.items().esEsencia(it)) return it.getType();
        return Material.GHAST_TEAR;
    }

    /** Las I y II apilables ya vendidas hoy ([_, I, II]). */
    int[] yaHoy(UUID u) {
        String dia = hc.calendario() != null ? hc.calendario().dia() : "";
        String ruta = "tasacion-dia." + u;
        boolean mismoDia = dia.equals(hc.datos().getString(ruta + ".dia", ""));
        return new int[]{0, mismoDia ? hc.datos().getInt(ruta + ".1", 0) : 0, mismoDia ? hc.datos().getInt(ruta + ".2", 0) : 0};
    }

    /** El multiplicador de la Racha de Codicia que se le aplicaria ahora (1 si esta apagada). */
    double factor(Player p) {
        Racha racha = hc.racha();
        if (racha == null) return 1.0;
        Censo.Foto foto = hc.valor("censo", () -> Censo.de(p), null);
        return racha.factor(p.getUniqueId(), foto);
    }

    /** La cuenta de unas Reliquias sin vender nada (la salida la usa para la primera del dia). */
    private Cuenta cuentaSin(Player p, List<ItemStack> items) {
        Reliquias rel = hc.reliquias();
        if (rel == null) return new Cuenta();
        List<Pieza> piezas = new ArrayList<>();
        for (ItemStack it : items) if (rel.es(it)) piezas.add(leer(rel, it));
        int[] ya = yaHoy(p.getUniqueId());
        return contar(piezas, rel.registro(), ya[1], ya[2], Valores.de(hc.cfg()), System.currentTimeMillis());
    }

    /**
     * Vende a Oren lo de ese grupo (clave) o todo (clave null): quita las piezas del inventario, las
     * Esencias van al saldo y las Reliquias se pagan como siempre. Devuelve lo pagado, o null si no se
     * pudo (fuera de sitio o nada que vender); el aviso al jugador lo da ella misma.
     */
    Resumen vender(Player p, String clave) {
        if (!puedeVender(p)) {
            p.sendMessage(ComandoCalamity.mensaje(texto("solo-en-el-spawn",
                    "Oren solo compra en el spawn de Calamity o fuera de Calamity.")));
            return null;
        }
        Reliquias rel = hc.reliquias();
        List<ItemStack> quitadas = new ArrayList<>();
        int quedan = 0;
        if (rel != null && activas() && !ESENCIAS.equals(clave)) {
            // Calamity 1.12: solo se retira lo que Oren paga. Las I-II que pasan del tope de hoy se quedan
            // en su hueco (si un monton pasa a medias, se parte); lo que no vale nada si se lo queda Oren.
            PlayerInventory inv = p.getInventory();
            ItemStack[] c = inv.getContents();
            List<Integer> huecos = new ArrayList<>();
            List<Pieza> piezas = new ArrayList<>();
            for (int i = 0; i < c.length; i++) {
                if (!rel.es(c[i])) continue;
                if (clave != null && !clave.equals(clave(c[i]))) continue;
                huecos.add(i);
                piezas.add(leer(rel, c[i]));
            }
            int[] ya = yaHoy(p.getUniqueId());
            int[] tomar = retirar(piezas, Valores.de(hc.cfg()), ya[1], ya[2]);
            for (int k = 0; k < huecos.size(); k++) {
                int i = huecos.get(k);
                ItemStack it = c[i];
                int n = tomar[k], hay = it.getAmount();
                quedan += hay - n;
                if (n <= 0) continue;
                if (n >= hay) {
                    quitadas.add(it);
                    inv.setItem(i, null);
                } else {
                    ItemStack parte = it.clone();
                    parte.setAmount(n);
                    quitadas.add(parte);
                    ItemStack resto = it.clone();
                    resto.setAmount(hay - n);
                    inv.setItem(i, resto);
                }
            }
        }
        int fisicas = 0;
        if (clave == null || ESENCIAS.equals(clave)) {
            Saldo s = hc.saldo();
            if (s != null) fisicas = hc.valor("saldo", () -> s.depositarFisicas(p), 0);
        }
        if (quitadas.isEmpty() && fisicas == 0) {
            p.sendMessage(ComandoCalamity.mensaje(quedan > 0 ? topeLleno(quedan)
                    : texto("nada-que-vender", "No llevas nada que Oren te compre.")));
            return null;
        }
        hc.plugin().bitacora().anotar("venta", p.getName(), clave == null ? "todo" : clave, Aduana.idsReliquias(quitadas),
                "esencias " + fisicas);
        Resumen r = procesar(p, p, quitadas, fisicas, "oren", true, true);
        if (fisicas > 0) {
            Saldo s = hc.saldo();
            if (s != null) p.sendMessage(s.avisoDeposito(p, fisicas));
        }
        if (quedan > 0) p.sendMessage(ComandoCalamity.mensaje(topeLleno(quedan)));
        return r;
    }

    /** "Hoy Oren ya no compra más Reliquias de grado I y II: te quedas 10. Mañana te las compra." */
    String topeLleno(int quedan) {
        return topeLleno(texto("tope-se-quedan", TOPE_SE_QUEDAN), quedan);
    }

    static final String TOPE_SE_QUEDAN =
            "Hoy Oren ya no compra más Reliquias de grado I y II: te quedas {n}. Mañana te las compra.";

    static String topeLleno(String plantilla, int quedan) {
        return plantilla.replace("{n}", Altar.miles(quedan));
    }

    /**
     * Cuantas unidades de cada pieza se lleva Oren (mismo orden que piezas). Las I-II apilables, solo
     * las que caben en lo que queda del tope de hoy (en el orden del inventario); todo lo demas entero:
     * las III-IV y especiales que pagan, y lo que no vale nada (falsas, caducadas, duplicadas), que se
     * retira sin pagar como siempre. Pura: lo mira el autotest. Usa la misma regla de apilable que contar.
     */
    static int[] retirar(List<Pieza> piezas, Valores v, int ya1, int ya2) {
        int[] ya = {0, ya1, ya2, 0, 0};
        int[] libre = new int[5];
        for (int g = 1; g <= 4; g++) {
            libre[g] = v.topeDia()[g] == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, v.topeDia()[g] - ya[g]);
        }
        int[] out = new int[piezas.size()];
        for (int i = 0; i < piezas.size(); i++) {
            Pieza p = piezas.get(i);
            int g = Math.max(1, Math.min(4, p.grado()));
            if (p.id() == null && p.especial() == null && v.apilables().contains(g)) {
                int n = Math.min(p.cantidad(), libre[g]);
                if (libre[g] != Integer.MAX_VALUE) libre[g] -= n;
                out[i] = n;
            } else {
                out[i] = p.cantidad();
            }
        }
        return out;
    }

    /**
     * Reliquias que la Aduana tiene que dar a quien no esta conectado (un Eco cerrado con "eco matar ...
     * <jugador>", un cazador que se ha ido). No se le pueden dar en mano, asi que se venden en el acto a su
     * nombre, sin Racha, y lo que valen va al saldo y a premios pendientes.
     */
    Resumen tasarAusente(OfflinePlayer p, List<ItemStack> reliquias, String motivo) {
        hc.plugin().bitacora().anotar("tasacion", "ausente", Minijefes.nombreDe(p), Aduana.idsReliquias(reliquias),
                motivo == null ? "-" : motivo);
        return procesar(p, null, reliquias, 0, motivo == null ? "ausente" : motivo, true, false);
    }

    /** Lo que pagaria una venta con estas Reliquias ahora mismo. No escribe nada. */
    Resumen simular(OfflinePlayer p, List<ItemStack> reliquias) {
        return procesar(p, null, reliquias == null ? List.of() : reliquias, 0, "simulacion", false, true);
    }

    /**
     * El cuerpo de la venta. online = el jugador conectado (mensajes, contratos, clanes, telemetria);
     * null en la de ausente y en la de prueba del comando. conRacha: false en la de ausente, que no es
     * una venta suya (ni el factor de la Racha ni subirla). fisicas: Esencias que ya paso al saldo.
     */
    private Resumen procesar(OfflinePlayer op, Player online, List<ItemStack> items, int fisicas, String motivo,
                             boolean real, boolean conRacha) {
        UUID u = op.getUniqueId();
        String nombre = Minijefes.nombreDe(op);
        ConfigurationSection c = hc.cfg();
        Valores v = Valores.de(c);
        Reliquias rel = hc.reliquias();
        Reliquias.Registro reg = rel != null ? rel.registro() : Reliquias.Registro.enMemoria();
        String dia = hc.calendario() != null ? hc.calendario().dia() : "";
        long ahora = System.currentTimeMillis();

        List<Pieza> piezas = new ArrayList<>();
        if (rel != null) for (ItemStack it : items) if (rel.es(it)) piezas.add(leer(rel, it));
        String rutaDia = "tasacion-dia." + u;
        int[] ya = yaHoy(u);
        Cuenta k = contar(piezas, reg, ya[1], ya[2], v, ahora);

        Censo.Foto foto = online == null ? null : hc.valor("censo", () -> Censo.de(online), null);
        Racha racha = hc.racha();
        double factor = racha == null || !conRacha ? 1.0 : racha.factor(u, foto);
        int esencias = (int) Math.floor(k.esencias * factor + 1e-9);
        long mc = Math.round(k.mc * factor);

        List<String> lineas = new ArrayList<>();
        lineas.add("reliquias " + k.porGrado[1] + "/" + k.porGrado[2] + "/" + k.porGrado[3] + "/" + k.porGrado[4]
                + " (I/II/III/IV) -> " + esencias + " E y " + mc + " MC" + (factor > 1 ? " (racha x" + num(factor) + ")" : ""));
        if (k.exceso[1] + k.exceso[2] > 0) lineas.add("exceso del tope diario: " + k.exceso[1] + " I, " + k.exceso[2] + " II");
        if (k.nulas() > 0) lineas.add("sin valor: " + k.falsas.size() + " falsas, " + k.duplicadas.size()
                + " duplicadas, " + k.caducadas.size() + " caducadas");
        if (!k.creditos.isEmpty()) lineas.add("creditos: " + String.join(", ", k.creditos));
        if (!real) return new Resumen(esencias, mc, lineas);

        // --- a partir de aqui, escribe: primero lo que cierra el dupe (C en reliquias.log).
        for (String id : k.cobrar) reg.cobrada(id, nombre, ahora);
        var bit = hc.plugin().bitacora();
        for (String id : k.falsas) bit.anotar("reliquia", "falsa", nombre, id);
        for (String id : k.duplicadas) {
            bit.anotar("reliquia", "duplicada", nombre, id);
            hc.plugin().getServer().broadcast(Paleta.aviso(
                    "Reliquia duplicada en la venta de " + nombre + " (" + id + ")."), "ederus.mundos");
        }
        for (String id : k.caducadas) bit.anotar("reliquia", "caducada", nombre, id);
        for (int g = 1; g <= 2; g++) if (k.exceso[g] > 0) bit.anotar("reliquia", "exceso", nombre, String.valueOf(g), String.valueOf(k.exceso[g]));
        // Solo las apilables: una Campana II o una Lagrima II llevan UUID y no gastan el tope.
        if (k.monton[1] > 0 || k.monton[2] > 0) {
            hc.datos().set(rutaDia + ".dia", dia);
            hc.datos().set(rutaDia + ".1", ya[1] + k.monton[1]);
            hc.datos().set(rutaDia + ".2", ya[2] + k.monton[2]);
        }
        // Para la primera salida del dia: si en esta entrada ya vendio alguna Reliquia que valia.
        if (k.validas > 0 && online != null) {
            hc.datos().set("vendio." + u, expedicion(u));
            hc.datos().set("vendio-dia." + u, dia);
        }
        hc.marcarSucio();

        Aduana ad = hc.aduana();
        Aduana.Pago pago = null;
        if (ad != null && (esencias > 0 || mc > 0)) pago = ad.pagar(op, "tasacion", esencias, mc, List.of(), motivo);
        int pagadasE = pago == null ? 0 : pago.esencias();
        long pagadasMc = pago == null ? 0 : pago.mc();
        // Calamity 1.11: lo que vende suma para su clan (las Esencias que ingresa y lo que valen sus Reliquias).
        ClanesCalamity clanes = hc.clanes();
        if (online != null && conRacha && clanes != null) {
            hc.seguro("clanes", () -> clanes.alTasar(online, fisicas, pagadasE));
        }

        // Creditos de las IV (la Marca con su tope de dia y semana).
        Creditos cr = hc.creditos();
        List<String> ganados = new ArrayList<>();
        for (String tipo : k.creditos) {
            if (tipo.equals("marca") && !marcaPermitida(u, dia)) {
                bit.anotar("credito", "tope-marcas", nombre, "marca");
                continue;
            }
            if (cr != null) cr.sumar(u, tipo, 1, "tasacion", false);
            ganados.add(tipo);
            bit.anotar("tasacion", "credito", nombre, tipo + " +1");
            if (online != null) online.sendMessage(ComandoCalamity.mensaje(Component.text("Con la venta has ganado ")
                    .append(Component.text(nombreCredito(tipo), Paleta.DETALLE)).append(Component.text("."))));
        }
        for (String mj : k.sellos) {
            hc.plugin().getServer().broadcast(ComandoCalamity.mensaje(Component.text(nombre, Paleta.DETALLE)
                    .append(Component.text(" ha vendido a Oren el "))
                    .append(Component.text("Sello " + Forja.delMinijefe(mj), Paleta.DETALLE))
                    .append(Component.text("."))));
        }

        // Llaves del Caos: una tirada por Campana IV y por Lagrima IV. Entregas pone el tope.
        Entregas en = hc.entregas();
        for (Object[] ll : k.llaves) {
            boolean sale = azar.nextDouble() < (double) ll[1];
            bit.anotar("llave", "tasacion", sale ? "si" : "no", nombre, String.valueOf(ll[0]));
            if (sale && en != null) en.llave(op, 1, "tasacion", true);
        }

        // La Racha: una vez por entrada a Calamity, con una venta que lleve grado II o mas.
        int rachaNueva = racha == null ? 0 : racha.de(u);
        if (racha != null && conRacha && online != null) {
            String exp = expedicion(u);
            if (subeRacha(exp, hc.datos().getString("racha-venta." + u), k.dosOMas)) {
                rachaNueva = racha.subir(u, online, foto);
                hc.datos().set("racha-venta." + u, exp);
                hc.marcarSucio();
            }
        }

        Estadisticas st = hc.estadisticas();
        if (st != null) {
            st.sumar(u, "tasado-mc", pagadasMc);
            st.sumar(u, "tasado-esencias", pagadasE);
            st.sumar(u, "reliquias", k.validas);
        }

        List<String> contratos = List.of();
        Contratos ct = hc.contratos();
        if (online != null && ct != null) {
            int dosMas = k.porGrado[2] + k.porGrado[3] + k.porGrado[4];
            if (dosMas > 0) hc.seguro("contratos", () -> ct.progreso(online, "tasa-ii", dosMas));
            contratos = hc.valor("contratos", () -> ct.cobrarEnTasacion(online), List.of());
        }

        if (online != null) {
            Telemetria te = hc.telemetria();
            if (te != null) {
                Map<String, Object> t = new LinkedHashMap<>();
                Map<String, Object> porGrado = new LinkedHashMap<>();
                for (int g = 1; g <= 4; g++) porGrado.put(String.valueOf(g), k.porGrado[g]);
                t.put("vendido", porGrado);
                t.put("esencias", pagadasE);
                t.put("mc", pagadasMc);
                t.put("mc_no_pagadas", pago == null ? 0 : pago.mcNoPagadas());
                t.put("esencias_fisicas", fisicas);
                t.put("racha", rachaNueva);
                t.put("exceso", k.exceso[1] + k.exceso[2]);
                t.put("sin_valor", k.nulas());
                t.put("creditos", ganados);
                t.put("contratos", contratos);
                t.put("donde", hc.esHardcore(online) ? "spawn" : "fuera");
                hc.seguro("telemetria", () -> te.suceso("venta", online, t));
            }
            avisar(online, k, pagadasE, pagadasMc, factor);
        }
        return new Resumen(pagadasE, pagadasMc, lineas);
    }

    /** "Le has vendido a Oren 7 Reliquias (5 de grado I y 2 de grado II) por 3 Esencias y 55 MobCoins." */
    private void avisar(Player p, Cuenta k, int esencias, long mc, double factor) {
        int vendidas = 0;
        List<String> grados = new ArrayList<>();
        for (int g = 1; g <= 4; g++) {
            if (k.porGrado[g] <= 0) continue;
            vendidas += k.porGrado[g];
            grados.add(k.porGrado[g] + " de grado " + Reliquias.ROMANO[g]);
        }
        if (vendidas > 0) {
            Component c = Component.text("Le has vendido a Oren ").append(cifra(vendidas))
                    .append(Component.text(vendidas == 1 ? " Reliquia" : " Reliquias"));
            if (grados.size() == 1) c = c.append(Component.text(" de grado " + Reliquias.ROMANO[primerGrado(k)]));
            else c = c.append(Component.text(" (" + lista(grados) + ")"));
            c = c.append(Component.text(" por ")).append(cifra(Altar.miles(esencias)))
                    .append(Component.text(esencias == 1 ? " Esencia y " : " Esencias y "))
                    .append(cifra(Altar.miles(mc))).append(Component.text(" MobCoins"));
            if (factor > 1) {
                c = c.append(Component.text(", con la Racha de Codicia a ×"))
                        .append(cifra(num(factor).replace('.', ',')));
            }
            p.sendMessage(ComandoCalamity.mensaje(c.append(Component.text("."))));
        }
        if (k.nulas() > 0) {
            p.sendMessage(ComandoCalamity.mensaje(k.nulas() == 1
                    ? "Una Reliquia no valía nada (caducada, falsa o duplicada): Oren se la ha quedado sin pagar."
                    : k.nulas() + " Reliquias no valían nada (caducadas, falsas o duplicadas): Oren se las ha quedado sin pagar."));
        }
        int exceso = k.exceso[1] + k.exceso[2];
        if (exceso > 0) {
            p.sendMessage(ComandoCalamity.mensaje("Has llegado al tope diario de Reliquias de grado I y II: "
                    + (exceso == 1 ? "una no ha pagado nada." : exceso + " no han pagado nada.")));
        }
    }

    private static int primerGrado(Cuenta k) {
        for (int g = 1; g <= 4; g++) if (k.porGrado[g] > 0) return g;
        return 1;
    }

    /** "a", "a y b", "a, b y c". */
    static String lista(List<String> cosas) {
        if (cosas.size() <= 1) return cosas.isEmpty() ? "" : cosas.get(0);
        return String.join(", ", cosas.subList(0, cosas.size() - 1)) + " y " + cosas.get(cosas.size() - 1);
    }

    private static Component cifra(Object o) {
        return Paleta.cifra(o);
    }

    /** El credito ganado, con su articulo: "un Sello del Heraldo Carmesí", "una Marca de Eco". */
    static String nombreCredito(String tipo) {
        if (tipo.startsWith("sello:")) return "un Sello " + Forja.delMinijefe(tipo.substring(6));
        return switch (tipo) {
            case "fragmento" -> "un Fragmento de Guadaña";
            case "marca" -> "una Marca de Eco";
            default -> tipo;
        };
    }

    private String semana() {
        return hc.calendario() != null ? hc.calendario().semana() : "";
    }

    /** "1.2", "1.5", "1": como se lee un multiplicador. */
    static String num(double v) {
        String s = String.format(Locale.US, "%.2f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    // ------------------------------------------------------------- recuento puro

    Pieza leer(Reliquias rel, ItemStack it) {
        return new Pieza(rel.grado(it), rel.especial(it), rel.id(it), rel.nacio(it), rel.nivel(it), rel.minijefe(it),
                rel.valida(it), Math.max(1, it.getAmount()));
    }

    /**
     * La primera salida del dia: +base, y +si-tasa si sale con alguna Reliquia que vale o si en esta misma
     * entrada ya le vendio alguna a Oren (antes, "si alguna Reliquia valio en la Tasacion").
     */
    static int primera(int validasEncima, boolean vendioEnEstaEntrada, Valores v) {
        return v.primeraBase() + (validasEncima > 0 || vendioEnEstaEntrada ? v.primeraSiTasa() : 0);
    }

    /** Si una venta sube la Racha: con grado II o mas y si en esta entrada aun no la subio ninguna. */
    static boolean subeRacha(String entrada, String yaSubioEn, boolean dosOMas) {
        return dosOMas && !(entrada == null ? "" : entrada).equals(yaSubioEn);
    }

    /**
     * Cuenta lo que vale cada pieza. Solo lee el registro: marcar las cobradas es del que
     * llama (y solo si de verdad paga).
     */
    static Cuenta contar(List<Pieza> piezas, Reliquias.Registro reg, int ya1, int ya2, Valores v, long ahora) {
        Cuenta k = new Cuenta();
        int[] monton = new int[5];
        Set<String> vistas = new HashSet<>();
        for (Pieza p : piezas) {
            int g = Math.max(1, Math.min(4, p.grado()));
            if (p.id() == null) {
                if (p.especial() == null && v.apilables().contains(g)) {
                    monton[g] += p.cantidad();
                } else {
                    // Un III o un especial sin UUID no lo ha hecho el plugin: NBT a mano.
                    for (int i = 0; i < p.cantidad(); i++) k.falsas.add("sin-id");
                }
                continue;
            }
            for (int i = 0; i < p.cantidad(); i++) {
                if (p.nacio() > 0 && ahora - p.nacio() >= v.caducaMillis()) {
                    k.caducadas.add(p.id());
                } else if (!reg.emitida(p.id())) {
                    k.falsas.add(p.id());
                } else if (reg.cobrada(p.id()) || !vistas.add(p.id())) {
                    k.duplicadas.add(p.id());
                } else {
                    k.cobrar.add(p.id());
                    k.esencias += v.esencias()[g];
                    k.mc += v.mc()[g];
                    k.porGrado[g]++;
                    k.validas++;
                    if (g >= 2) k.dosOMas = true;
                    extras(k, p, g, v);
                }
            }
        }
        int[] ya = {0, ya1, ya2, 0, 0};
        for (int g = 1; g <= 4; g++) {
            if (monton[g] == 0) continue;
            int libre = Math.max(0, v.topeDia()[g] == Integer.MAX_VALUE ? Integer.MAX_VALUE : v.topeDia()[g] - ya[g]);
            int paga = Math.min(monton[g], libre);
            k.exceso[g] += monton[g] - paga;
            k.esencias += paga * v.esencias()[g];
            k.mc += paga * v.mc()[g];
            k.porGrado[g] += paga;
            k.monton[g] += paga;
            k.validas += paga;
            if (paga > 0 && g >= 2) k.dosOMas = true;
        }
        return k;
    }

    private static void extras(Cuenta k, Pieza p, int g, Valores v) {
        String esp = p.especial();
        if (Reliquias.CAMPANA.equals(esp)) {
            // Cualquier Campana de una PARCA de N >= 40 da Fragmento (tambien la III: PLAN 3.1).
            if (p.nivel() >= v.fragmentoNivel()) k.creditos.add("fragmento");
            if (g == 4) k.llaves.add(new Object[]{Reliquias.CAMPANA, v.llaveCampana()});
        } else if (Reliquias.LAGRIMA.equals(esp)) {
            if (g == 4 || p.valida()) k.creditos.add("marca");
            if (g == 4) k.llaves.add(new Object[]{Reliquias.LAGRIMA, v.llaveLagrima()});
        } else if (Reliquias.SELLO.equals(esp) && p.minijefe() != null && !p.minijefe().isBlank()) {
            k.creditos.add("sello:" + p.minijefe());
            k.sellos.add(p.minijefe());
        }
    }

    /**
     * Los extras de una cuenta dichos para el jugador, sin repetir: "+ 2 Fragmentos de Guadaña",
     * "+ 25 % de ganar una Llave del Caos" (con cuantas tiradas, si son varias). Puro: lo pinta el menu
     * de Oren y lo mira el autotest.
     */
    static List<String> extrasTexto(Cuenta k) {
        Map<String, Integer> creditos = new LinkedHashMap<>();
        for (String c : k.creditos) creditos.merge(c, 1, Integer::sum);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : creditos.entrySet()) {
            out.add("+ " + MenuAltar.creditoLinea(e.getKey(), e.getValue()));
        }
        Map<String, int[]> llaves = new LinkedHashMap<>();
        for (Object[] ll : k.llaves) llaves.computeIfAbsent(Marco.porcentaje((double) ll[1]), x -> new int[1])[0]++;
        for (Map.Entry<String, int[]> e : llaves.entrySet()) {
            int n = e.getValue()[0];
            out.add("+ " + e.getKey() + " de ganar una Llave del Caos" + (n > 1 ? " (" + n + " tiradas)" : ""));
        }
        return out;
    }

    /**
     * Tope de Marcas de Eco (2 al dia, 8 a la semana). UNA sola cuenta: la de Ecos
     * (marcas.<uuid>, que tambien lleva la Marca del Eco propio). Si toca, ya queda apuntada.
     * Sin Ecos en marcha, la misma regla estatica sobre los mismos datos.
     */
    private boolean marcaPermitida(UUID u, String dia) {
        Ecos ecos = hc.ecos();
        if (ecos != null) return hc.valor("ecos", () -> ecos.marcaPermitida(u), false);
        ConfigurationSection c = hc.cfg();
        boolean si = Ecos.marca(hc.datos(), u, dia, semana(), c.getInt("eco.marcas.dia", 2),
                c.getInt("eco.marcas.semana", 8));
        if (si) hc.marcarSucio();
        return si;
    }

    // ----------------------------------------------------------------- comando

    /**
     * tasar <jugador> <g1> <g2> <g3> [especial:grado:N[:valida|:minijefe] ...]: crea las
     * Reliquias (las III y especiales quedan emitidas en reliquias.log) y las vende de verdad
     * a su nombre, sin tocar su inventario. Paga, escribe la C y cuenta los topes del dia.
     */
    private void comando(CommandSender quien, String[] args) {
        if (args.length == 3 && args[2].equalsIgnoreCase("reset")) {
            // Solo el tope diario de I-II: para repetir una prueba el mismo dia. La primera
            // salida del dia no se toca (eso lo pagaria dos veces).
            OfflinePlayer op = Reliquias.jugador(args[1]);
            if (op == null) {
                quien.sendMessage(ComandoCalamity.mensaje("No encuentro a ese jugador."));
                return;
            }
            hc.datos().set("tasacion-dia." + op.getUniqueId(), null);
            hc.marcarSucio();
            hc.plugin().bitacora().anotar("tasacion", "reset-tope", Minijefes.nombreDe(op), quien.getName());
            quien.sendMessage(ComandoCalamity.mensaje("Tope diario de Astillas y Fragmentos de "
                    + Minijefes.nombreDe(op) + " puesto a cero."));
            return;
        }
        if (args.length < 5) {
            quien.sendMessage(ComandoCalamity.mensaje(
                    "Uso: /calamity appraise <player> <g1> <g2> <g3> [special:tier:N[:valid|:miniboss] ...]"));
            return;
        }
        Reliquias rel = hc.reliquias();
        if (rel == null) {
            quien.sendMessage(ComandoCalamity.mensaje("El módulo de Reliquias no está en marcha."));
            return;
        }
        OfflinePlayer op = Reliquias.jugador(args[1]);
        if (op == null) {
            quien.sendMessage(ComandoCalamity.mensaje("No encuentro a ese jugador."));
            return;
        }
        int[] n = new int[4];
        for (int g = 1; g <= 3; g++) {
            try {
                n[g] = Math.max(0, Integer.parseInt(args[1 + g]));
            } catch (NumberFormatException e) {
                quien.sendMessage(ComandoCalamity.mensaje("g1, g2 y g3 tienen que ser cantidades, y «" + args[1 + g] + "» no lo es."));
                return;
            }
        }
        List<Reliquias.Espec> especiales = new ArrayList<>();
        for (int i = 5; i < args.length; i++) {
            Reliquias.Espec e = Reliquias.espec(args[i], true, 4);
            if (e == null) {
                quien.sendMessage(ComandoCalamity.mensaje("No entiendo «" + args[i]
                        + "». Ejemplos: campana:3:45, lagrima:4:60:valid, sello:4:heraldo-carmes."));
                return;
            }
            especiales.add(e);
        }
        List<ItemStack> items = new ArrayList<>();
        for (int g = 1; g <= 2; g++) {
            // Las apilables en montones de 64 como mucho, que es lo que cabe en un hueco.
            for (int quedan = n[g]; quedan > 0; quedan -= 64) {
                ItemStack r = rel.crear(g, "admin", null, 0, null, false);
                r.setAmount(Math.min(64, quedan));
                items.add(r);
            }
        }
        for (int i = 0; i < n[3]; i++) items.add(rel.crear(3, "admin", null, 0, null, false));
        for (Reliquias.Espec e : especiales) {
            items.add(rel.crear(e.grado(), "admin", e.especial(), e.nivel(), e.minijefe(), e.valida()));
        }
        Resumen r = procesar(op, null, items, 0, "tasar-admin", true, true);
        quien.sendMessage(ComandoCalamity.mensaje("Venta de " + Minijefes.nombreDe(op) + ": " + r.esencias()
                + " Esencias y " + r.mobcoins() + " MobCoins pagadas."));
        for (String l : r.lineas()) quien.sendMessage(Component.text("  " + l, Paleta.TENUE));
    }

    private List<String> tab(String[] args) {
        if (args.length == 2) return Reliquias.conectados();
        if (args.length == 3) return List.of("0", "1", "5", "reset");
        if (args.length <= 5) return List.of("0", "1", "5");
        List<String> op = new ArrayList<>(List.of("campana:3:45", "campana:4:52", "lagrima:4:60:valid", "lagrima:3:30",
                "eclipsada:3", "mayor"));
        for (String t : Minijefes.TIPOS) op.add("sello:4:" + t);
        return op;
    }

    // ----------------------------------------------------------------- autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        Valores v = Valores.de(new YamlConfiguration());
        long ahora = System.currentTimeMillis();
        Reliquias.Registro reg = Reliquias.Registro.enMemoria();
        String ambar = UUID.randomUUID().toString();
        reg.emitida(ambar, 3, "prueba", ahora);

        // Aceptacion 1: 5 I, 1 II, 1 III -> 5 E y 80 MC; la primera del dia, 2 + 1.
        Cuenta a = contar(List.of(pieza(1, 5), pieza(2, 1), uuid(3, ambar, null, 0, null, false, ahora)), reg, 0, 0, v, ahora);
        h.igual("5 I + 1 II + 1 III: Esencias", 5, (int) Math.floor(a.esencias + 1e-9));
        h.igual("5 I + 1 II + 1 III: MC", 80L, a.mc);
        h.igual("primera del dia saliendo con algo que vale", 3, primera(a.validas, false, v));
        h.igual("primera del dia sin nada encima ni vendido", 2, primera(0, false, v));
        h.igual("primera del dia habiendo vendido ya en esta entrada", 3, primera(0, true, v));
        h.igual("la III se marca para cobrar", List.of(ambar), a.cobrar);
        h.ok("con II o mas sube la racha", a.dosOMas);

        // Aceptacion 2: 70 I -> 60 pagadas (12 E, 300 MC) y 10 de exceso.
        Cuenta b = contar(List.of(pieza(1, 70)), reg, 0, 0, v, ahora);
        h.igual("70 I: Esencias", 12, (int) Math.floor(b.esencias + 1e-9));
        h.igual("70 I: MC", 300L, b.mc);
        h.igual("70 I: exceso", 10, b.exceso[1]);
        h.ok("solo I no sube la racha", !b.dosOMas);
        Cuenta b2 = contar(List.of(pieza(1, 70)), reg, 10, 0, v, ahora);
        h.igual("70 I con 10 ya vendidas hoy: pagan 50", 50, b2.porGrado[1]);
        Cuenta b3 = contar(List.of(pieza(2, 40)), reg, 0, 25, v, ahora);
        h.igual("40 II con 25 hoy: pagan 5", 5, b3.porGrado[2]);
        // Calamity 1.12: Oren solo se lleva lo que paga; el exceso se queda en el inventario.
        h.igual("70 I a Oren: se lleva 60", 60, retirar(List.of(pieza(1, 70)), v, 0, 0)[0]);
        h.igual("40 II con 25 hoy a Oren: se lleva 5", 5, retirar(List.of(pieza(2, 40)), v, 0, 25)[0]);
        String cam2 = UUID.randomUUID().toString();
        reg.emitida(cam2, 2, "prueba", ahora);
        Cuenta b4 = contar(List.of(uuid(2, cam2, Reliquias.CAMPANA, 20, null, false, ahora)), reg, 0, 30, v, ahora);
        h.ok("una Campana II no gasta ni respeta el tope de las II", b4.porGrado[2] == 1 && b4.monton[2] == 0);

        // Aceptacion 3: Campana III N45, Lagrima IV N60 valida, Sello IV del Heraldo.
        String cam = UUID.randomUUID().toString(), lag = UUID.randomUUID().toString(), sel = UUID.randomUUID().toString();
        reg.emitida(cam, 3, "prueba", ahora);
        reg.emitida(lag, 4, "prueba", ahora);
        reg.emitida(sel, 4, "prueba", ahora);
        Cuenta c = contar(List.of(uuid(3, cam, Reliquias.CAMPANA, 45, null, false, ahora),
                uuid(4, lag, Reliquias.LAGRIMA, 60, null, true, ahora),
                uuid(4, sel, Reliquias.SELLO, 50, "heraldo-carmes", false, ahora)), reg, 0, 0, v, ahora);
        h.igual("especiales: Esencias", 15, (int) Math.floor(c.esencias + 1e-9));
        h.igual("especiales: MC", 240L, c.mc);
        h.igual("especiales: creditos", List.of("fragmento", "marca", "sello:heraldo-carmes"), c.creditos);
        h.igual("una sola tirada de llave (la Lagrima IV)", 1, c.llaves.size());
        h.igual("la tirada es de la Lagrima", Reliquias.LAGRIMA, c.llaves.get(0)[0]);
        h.igual("anuncio del Sello", List.of("heraldo-carmes"), c.sellos);
        Cuenta c2 = contar(List.of(uuid(4, cam, Reliquias.CAMPANA, 39, null, false, ahora)), reg, 0, 0, v, ahora);
        h.ok("Campana IV de N 39: llave si, Fragmento no", c2.creditos.isEmpty() && c2.llaves.size() == 1);
        Cuenta c3 = contar(List.of(uuid(3, lag, Reliquias.LAGRIMA, 30, null, false, ahora)), reg, 0, 0, v, ahora);
        h.ok("Lagrima III sin caza valida: ni Marca ni llave", c3.creditos.isEmpty() && c3.llaves.isEmpty());

        // Aceptacion 4: duplicada y falsa.
        reg.cobrada(ambar, "prueba", ahora);
        Cuenta d = contar(List.of(uuid(3, ambar, null, 0, null, false, ahora)), reg, 0, 0, v, ahora);
        h.ok("UUID ya cobrado: duplicada y no paga", d.duplicadas.contains(ambar) && d.esencias == 0 && d.mc == 0);
        String nadie = UUID.randomUUID().toString();
        Cuenta f = contar(List.of(uuid(4, nadie, null, 0, null, false, ahora)), reg, 0, 0, v, ahora);
        h.ok("UUID no emitido: falsa y no paga", f.falsas.contains(nadie) && f.esencias == 0 && f.mc == 0);
        String doble = UUID.randomUUID().toString();
        reg.emitida(doble, 3, "prueba", ahora);
        Cuenta g = contar(List.of(uuid(3, doble, null, 0, null, false, ahora), uuid(3, doble, null, 0, null, false, ahora)),
                reg, 0, 0, v, ahora);
        h.ok("el mismo UUID dos veces en una venta: una paga, otra duplicada",
                g.porGrado[3] == 1 && g.duplicadas.size() == 1);
        String vieja = UUID.randomUUID().toString();
        reg.emitida(vieja, 3, "prueba", ahora);
        Cuenta cad = contar(List.of(uuid(3, vieja, null, 0, null, false, ahora - v.caducaMillis() - 1)), reg, 0, 0, v, ahora);
        h.ok("caducada: no paga", cad.caducadas.contains(vieja) && cad.mc == 0);
        Cuenta sinId = contar(List.of(new Pieza(3, null, null, 0, 0, null, false, 1)), reg, 0, 0, v, ahora);
        h.ok("una III sin UUID es falsa", sinId.falsas.size() == 1 && sinId.mc == 0);

        // Tope de Marcas de Eco: 2 al dia, 8 a la semana (la cuenta es la de Ecos).
        YamlConfiguration datos = new YamlConfiguration();
        UUID u = Autotest.sintetico(1);
        h.ok("1.a Marca del dia", Ecos.marca(datos, u, "2026-09-26", "2026-W39", 2, 8));
        h.ok("2.a Marca del dia", Ecos.marca(datos, u, "2026-09-26", "2026-W39", 2, 8));
        h.ok("3.a Marca del dia: sin credito", !Ecos.marca(datos, u, "2026-09-26", "2026-W39", 2, 8));
        boolean semanaLlena = true;
        for (int dia = 27; dia <= 29; dia++) {
            semanaLlena &= Ecos.marca(datos, u, "2026-09-" + dia, "2026-W39", 2, 8);
            semanaLlena &= Ecos.marca(datos, u, "2026-09-" + dia, "2026-W39", 2, 8);
        }
        h.ok("hasta 8 en la semana", semanaLlena);
        h.ok("la 9.a de la semana: sin credito", !Ecos.marca(datos, u, "2026-09-30", "2026-W39", 2, 8));
        h.ok("semana nueva: vuelve", Ecos.marca(datos, u, "2026-10-05", "2026-W41", 2, 8));
        h.ok("las pruebas no tocan hardcore-datos.yml", !hc.datos().isSet("marcas." + u));
        h.igual("num 1.2", "1.2", num(1.2));
        h.igual("num 1", "1", num(1.0));
        return h.lineas();
    }

    /**
     * La venta a Oren (rama venta-oren): grupos, topes por partes, la Racha una vez por entrada, los
     * extras dichos para el jugador, que reconoce lo que compra y que ningun texto diga ya que se vende
     * solo al salir.
     */
    private List<String> autotestVenta() {
        Autotest.Hoja h = new Autotest.Hoja();
        ventaPura(h, hc.cfg());

        // Lo que reconoce como suyo: Reliquias y Esencias, por su marca.
        Reliquias rel = hc.reliquias();
        if (rel != null) {
            ItemStack astilla = rel.crear(1, "prueba", null, 0, null, false);
            h.igual("reconoce una Astilla", clave(1, null, null), clave(astilla));
            ItemStack falsa = new ItemStack(Material.PRISMARINE_SHARD);
            ItemMeta meta = falsa.getItemMeta();
            meta.getPersistentDataContainer().set(Marcas.RELIQUIA, PersistentDataType.INTEGER, 2);
            falsa.setItemMeta(meta);
            h.igual("una Reliquia hecha a mano tambien se detecta (y luego no paga)", clave(2, null, null), clave(falsa));
        }
        h.igual("reconoce una Esencia", ESENCIAS, clave(hc.items().esencia(3)));
        h.igual("una piedra no es de Oren", null, clave(new ItemStack(Material.STONE)));
        return h.lineas();
    }

    /**
     * Lo de la venta que no necesita objetos ni servidor (se puede correr fuera): grupos, topes, lo que
     * Oren retira, la Racha, los extras y los textos de la config.
     */
    static void ventaPura(Autotest.Hoja h, ConfigurationSection cfg) {
        Valores v = Valores.de(new YamlConfiguration());
        long ahora = System.currentTimeMillis();
        Reliquias.Registro reg = Reliquias.Registro.enMemoria();

        // Grupos: el nombre lo dan grado, especial y minijefe.
        h.igual("clave de una Astilla", "r:1:-:-", clave(1, null, null));
        h.igual("clave de un Sello", "r:4:sello-minijefe:heraldo-carmes", clave(4, Reliquias.SELLO, "Heraldo-Carmes"));
        h.ok("dos Sellos de minijefes distintos son dos grupos",
                !clave(4, Reliquias.SELLO, "heraldo-carmes").equals(clave(4, Reliquias.SELLO, "custodio-de-las-ruinas")));
        h.ok("el grado fuera de rango se acota", clave(9, null, null).equals(clave(4, null, null)));

        // Vender por partes no se salta el tope: la segunda venta del dia ve lo que ya vendio la primera.
        Cuenta primeraVenta = contar(List.of(pieza(1, 40)), reg, 0, 0, v, ahora);
        Cuenta segunda = contar(List.of(pieza(1, 40)), reg, primeraVenta.monton[1], 0, v, ahora);
        h.igual("tope: 40 I y luego 40 I pagan 40 + 20", 20, segunda.porGrado[1]);
        h.igual("tope: y el resto es exceso", 20, segunda.exceso[1]);
        Cuenta todo = contar(List.of(pieza(1, 80)), reg, 0, 0, v, ahora);
        h.igual("tope: vender todo de golpe paga lo mismo", primeraVenta.porGrado[1] + segunda.porGrado[1], todo.porGrado[1]);

        // La Racha: una vez por entrada, solo con grado II o mas.
        h.ok("racha: primera venta con una II en la entrada sube", subeRacha("100", null, true));
        h.ok("racha: segunda venta en la misma entrada no sube", !subeRacha("100", "100", true));
        h.ok("racha: entrada nueva vuelve a subir", subeRacha("200", "100", true));
        h.ok("racha: solo Astillas no la sube", !subeRacha("200", "100", false));
        h.ok("racha: sin entrada apuntada sube una vez", subeRacha("", null, true) && !subeRacha("", "", true));

        // Extras, como se leen en el menu.
        String cam = UUID.randomUUID().toString(), lag = UUID.randomUUID().toString();
        reg.emitida(cam, 4, "prueba", ahora);
        reg.emitida(lag, 4, "prueba", ahora);
        Cuenta e = contar(List.of(uuid(4, cam, Reliquias.CAMPANA, 50, null, false, ahora),
                uuid(4, lag, Reliquias.LAGRIMA, 60, null, true, ahora)), reg, 0, 0, v, ahora);
        List<String> ex = extrasTexto(e);
        h.ok("extras: Fragmento de Guadaña", ex.contains("+ 1 Fragmento de Guadaña"));
        h.ok("extras: Marca de Eco", ex.contains("+ 1 Marca de Eco"));
        h.igual("extras: las dos tiradas de llave", 2L, ex.stream().filter(x -> x.contains("Llave del Caos")).count());
        h.igual("extras: nada sin especiales", List.of(), extrasTexto(contar(List.of(pieza(1, 3)), reg, 0, 0, v, ahora)));

        // Calamity 1.12: Oren solo retira lo que paga. Lo que pasa del tope de hoy se queda contigo.
        int[] t = retirar(List.of(pieza(1, 64), pieza(1, 6)), v, 10, 0);
        h.igual("retirar: 64 + 6 I con 10 ya vendidas: se lleva 50 del primero y deja el segundo", "50,0", t[0] + "," + t[1]);
        h.igual("retirar: tope de las II lleno: no se lleva ninguna", 0, retirar(List.of(pieza(2, 40)), v, 0, 30)[0]);
        h.igual("retirar: sin tope gastado se lo lleva todo", 12, retirar(List.of(pieza(2, 12)), v, 0, 0)[0]);
        h.igual("retirar: el tope de las I no toca a las II", 7, retirar(List.of(pieza(1, 5), pieza(2, 7)), v, 60, 0)[1]);
        String tres = UUID.randomUUID().toString(), camII = UUID.randomUUID().toString();
        reg.emitida(tres, 3, "prueba", ahora);
        reg.emitida(camII, 2, "prueba", ahora);
        List<Pieza> mezcla = List.of(pieza(1, 70), uuid(3, tres, null, 0, null, false, ahora),
                uuid(2, camII, Reliquias.CAMPANA, 20, null, false, ahora), new Pieza(3, null, null, 0, 0, null, false, 1),
                uuid(4, UUID.randomUUID().toString(), null, 0, null, false, ahora));
        int[] m = retirar(mezcla, v, 0, 30);
        h.igual("retirar: las I hasta el tope; la III, la Campana II, la falsa y la no emitida enteras", "60,1,1,1,1",
                m[0] + "," + m[1] + "," + m[2] + "," + m[3] + "," + m[4]);
        List<Pieza> llevadas = new ArrayList<>();
        for (int i = 0; i < mezcla.size(); i++) {
            Pieza pz = mezcla.get(i);
            if (m[i] > 0) llevadas.add(new Pieza(pz.grado(), pz.especial(), pz.id(), pz.nacio(), pz.nivel(), pz.minijefe(),
                    pz.valida(), m[i]));
        }
        Cuenta todoJunto = contar(mezcla, reg, 0, 30, v, ahora), soloLlevadas = contar(llevadas, reg, 0, 30, v, ahora);
        h.ok("retirar: lo que se lleva paga lo mismo y ya no tiene exceso",
                soloLlevadas.exceso[1] + soloLlevadas.exceso[2] == 0 && soloLlevadas.mc == todoJunto.mc
                        && soloLlevadas.esencias == todoJunto.esencias);
        h.igual("retirar: las que no valen nada se retiran igual (sin pagar)", 2, soloLlevadas.nulas());

        // Lo que enseña el menu: cuantas compra hoy y cuantas se quedan contigo.
        Grupo astillas = new Grupo(clave(1, null, null), "Astilla", null, Material.PRISMARINE_SHARD, 1, null, 70,
                contar(List.of(pieza(1, 70)), reg, 0, 0, v, ahora), 0L);
        h.igual("grupo: de 70 I compra 60", 60, astillas.compra());
        h.igual("grupo: y se quedan 10", 10, astillas.quedan());
        Grupo falsas = new Grupo(clave(3, null, null), "Ámbar", null, Material.RESIN_CLUMP, 3, null, 1,
                contar(List.of(new Pieza(3, null, null, 0, 0, null, false, 1)), reg, 0, 0, v, ahora), 0L);
        h.ok("grupo: una falsa se la lleva (sin pagar) y no se queda", falsas.compra() == 1 && falsas.quedan() == 0);
        Grupo esencias = new Grupo(ESENCIAS, "Esencia", null, Material.GHAST_TEAR, 0, null, 5, null, 0L);
        h.ok("grupo: las Esencias, todas", esencias.compra() == 5 && esencias.quedan() == 0);
        Oferta oferta = new Oferta(List.of(astillas, esencias), contar(List.of(pieza(1, 70)), reg, 0, 0, v, ahora), 5, 1.0,
                12, 300, 0, 0);
        h.ok("vender todo: compra 65 de 75 y te quedas 10", oferta.piezas() == 75 && oferta.compra() == 65 && oferta.quedan() == 10);
        h.ok("aviso de tope lleno con la cifra", topeLleno(TOPE_SE_QUEDAN, 10).contains("te quedas 10")
                && !topeLleno(TOPE_SE_QUEDAN, 10).contains("{n}"));
        String plantilla = cfg.getString("venta.mensajes.tope-se-quedan");
        h.ok("venta.mensajes.tope-se-quedan lleva {n} (o no esta y va el de serie)", plantilla == null || plantilla.contains("{n}"));

        // Ningun texto dice ya que se vende solo al salir.
        List<String> textos = new ArrayList<>();
        for (int gr = 1; gr <= 4; gr++) {
            for (String esp : new String[]{null, Reliquias.CAMPANA, Reliquias.LAGRIMA, Reliquias.SELLO, Reliquias.ECLIPSADA}) {
                textos.add(String.join(" ", Reliquias.ficha(cfg, gr, esp, 50, "heraldo-carmes", true, "mob", gr >= 3,
                        "18/10").lineas()));
            }
        }
        textos.add(String.join(" ", ItemsCalamity.fichaEsencia().lineas()));
        ConfigurationSection caps = cfg.getConfigurationSection("cronista.capitulos");
        if (caps != null) for (String cap : caps.getKeys(false)) textos.addAll(caps.getStringList(cap + ".texto"));
        ConfigurationSection pool = cfg.getConfigurationSection("contratos.pool");
        if (pool != null) for (String id : pool.getKeys(false)) textos.add(pool.getString(id + ".texto", ""));
        List<String> malos = new ArrayList<>();
        for (String x : textos) if (vendeSolo(x)) malos.add(x);
        h.igual("ningun lore, capitulo ni contrato dice que se vende solo al salir", List.of(), malos);
        h.ok("el detector de textos viejos funciona", vendeSolo("Se vende sola al salir de Calamity")
                && vendeSolo("Al salir vivo, tus Reliquias se venden solas") && !vendeSolo("Véndesela a Oren."));
    }

    /** Si un texto aun dice que lo de Calamity se vende (o pasa al saldo) solo al salir. */
    static boolean vendeSolo(String t) {
        if (t == null) return false;
        String s = Normalizer.normalize(t.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ");
        return s.contains("se vende sol") || s.contains("se venden sol") || s.contains("pasan a tu saldo")
                || s.contains("pasa a tu saldo") || s.contains("valor al salir") || s.contains("cada reliquia que sacas vivo");
    }

    private static Pieza pieza(int grado, int cantidad) {
        return new Pieza(grado, null, null, 0, 0, null, false, cantidad);
    }

    private static Pieza uuid(int grado, String id, String especial, int nivel, String minijefe, boolean valida, long nacio) {
        return new Pieza(grado, especial, id, nacio, nivel, minijefe, valida, 1);
    }
}
