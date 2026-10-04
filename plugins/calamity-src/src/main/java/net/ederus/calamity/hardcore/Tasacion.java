package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * M3 · La Tasacion: lo que vale salir vivo.
 *
 * Al cruzar la puerta o terminar un Cristal (Hardcore.sacar con extraccion), antes del
 * teleport, se quitan TODAS las Reliquias que lleve (inventario, cursor, rejilla de
 * crafteo) y se pagan en UN solo Aduana.pagar(p, "tasacion", ...): las Reliquias validas x
 * la Racha, mas la primera extraccion del dia. Un solo pago para que la Bitacora tenga una
 * linea por salida y los topes de la Aduana vean la salida entera, no trozos.
 *
 * Aparte del pago (no son Esencias ni MobCoins nuevas): creditos de las IV (Sello, Fragmento
 * de Guadana, Marca de Eco), tiradas de Llave del Caos (Entregas.llave, con su tope), las
 * Esencias fisicas que lleve pasan al saldo (Saldo.depositarFisicas), y al final los
 * contratos cumplidos (se pagan ellos por la Aduana, tipo "contratos") y la encuesta.
 *
 * Una Reliquia con UUID solo paga si esta emitida en reliquias.log y no esta cobrada: la
 * falsa y la duplicada se quitan igual y se apuntan (la duplicada avisa al staff). Las I-II
 * no llevan UUID; las acota el tope diario (60 / 30) y lo que pasa se anota como exceso.
 */
final class Tasacion {

    /** Lo que pagaria (o pago) una tasacion. */
    record Resumen(int esencias, long mobcoins, List<String> lineas) {
    }

    /** Una Reliquia leida del objeto (cantidad = unidades del monton). */
    record Pieza(int grado, String especial, String id, long nacio, int nivel, String minijefe, boolean valida,
                 int cantidad) {
    }

    /** Los numeros de la Tasacion, de la config con los de serie (PLAN sec. 3.1). */
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

    /** El recuento de una tasacion, sin escribir nada. */
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
        /** Minijefes de los Sellos que salen vivos (anuncio P-W05). */
        final List<String> sellos = new ArrayList<>();
        /** [especial, probabilidad] de cada tirada de Llave del Caos. */
        final List<Object[]> llaves = new ArrayList<>();
        int validas;
        boolean dosOMas;

        int nulas() {
            return falsas.size() + duplicadas.size() + caducadas.size();
        }
    }

    private final Hardcore hc;
    private final SecureRandom azar = new SecureRandom();

    Tasacion(Hardcore hc) {
        this.hc = hc;
        Autotest.registrar("tasacion", this::autotest);
        Subcomandos.lw().registrar("tasar",
                "tasar <jugador> <g1> <g2> <g3> [especial:grado:N[:valida|:minijefe] ...] | tasar <jugador> reset: vende Reliquias virtuales",
                "ederus.mundos", this::comando, this::tab);
    }

    void parar() {
    }

    // ------------------------------------------------------------------ tasar

    /** Hardcore.sacar(p, motivo, true), antes del teleport. */
    void tasar(Player p, String motivo) {
        List<ItemStack> recogidas = new ArrayList<>();
        Reliquias rel = hc.reliquias();
        if (rel != null && rel.activas()) {
            quitar(p.getInventory(), rel, recogidas);
            if (rel.es(p.getItemOnCursor())) {
                recogidas.add(p.getItemOnCursor());
                p.setItemOnCursor(null);
            }
            // La rejilla 2x2 del inventario propio: si la dejo ahi al cruzar, tambien cuenta.
            Inventory arriba = p.getOpenInventory().getTopInventory();
            if (arriba.getType() == InventoryType.CRAFTING) quitar(arriba, rel, recogidas);
            rel.marcarExtraccion(p);
        }
        procesar(p, p, recogidas, motivo == null ? "?" : motivo, true, true);
    }

    /**
     * Reliquias que la Aduana tiene que dar a quien no puede llevarlas: desconectado o fuera de
     * Calamity (un Eco cerrado con "eco matar ... <jugador>", un cazador que se ha ido). Fuera
     * no existen, asi que darlas seria perderlas: se tasan en el acto a su nombre y lo que valen
     * va al saldo y a premios pendientes. Sin la primera salida del dia, que no ha salido.
     */
    Resumen tasarAusente(OfflinePlayer p, List<ItemStack> reliquias, String motivo) {
        hc.plugin().bitacora().anotar("tasacion", "ausente", Minijefes.nombreDe(p), Aduana.idsReliquias(reliquias),
                motivo == null ? "-" : motivo);
        // Sin jugador: ni mensajes, ni censo, ni deposito, ni contratos, ni encuesta; no ha salido.
        return procesar(p, null, reliquias, motivo == null ? "ausente" : motivo, true, false);
    }

    private static void quitar(Inventory inv, Reliquias rel, List<ItemStack> a) {
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) {
            if (!rel.es(c[i])) continue;
            a.add(c[i]);
            inv.setItem(i, null);
        }
    }

    /** Lo que pagaria una tasacion con estas Reliquias ahora mismo. No escribe nada. */
    Resumen simular(OfflinePlayer p, List<ItemStack> reliquias) {
        return procesar(p, null, reliquias == null ? List.of() : reliquias, "simulacion", false, true);
    }

    /**
     * El cuerpo de la tasacion. online = el jugador conectado (mensajes, censo, saldo fisico,
     * contratos, encuesta, telemetria); null en la tasacion de prueba del comando.
     * esSalida: false en la tasacion de ausente, que no es una salida: sin primera del dia y
     * sin Racha (ni su factor ni subirla), que premian salir vivo.
     */
    private Resumen procesar(OfflinePlayer op, Player online, List<ItemStack> items, String motivo, boolean real,
                             boolean esSalida) {
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
        boolean mismoDia = dia.equals(hc.datos().getString(rutaDia + ".dia", ""));
        int ya1 = mismoDia ? hc.datos().getInt(rutaDia + ".1", 0) : 0;
        int ya2 = mismoDia ? hc.datos().getInt(rutaDia + ".2", 0) : 0;
        Cuenta k = contar(piezas, reg, ya1, ya2, v, ahora);

        Censo.Foto salida = online == null ? null : hc.valor("censo", () -> Censo.de(online), null);
        Racha racha = hc.racha();
        int r = racha == null ? 0 : racha.de(u);
        double factor = racha == null || !esSalida ? 1.0 : racha.factor(u, salida);
        int esencias = (int) Math.floor(k.esencias * factor + 1e-9);
        long mc = Math.round(k.mc * factor);
        boolean primera = esSalida && !dia.equals(hc.datos().getString("primera-extraccion." + u, ""));
        int extra = primera ? primera(k.validas, v) : 0;

        List<String> lineas = new ArrayList<>();
        lineas.add("reliquias " + k.porGrado[1] + "/" + k.porGrado[2] + "/" + k.porGrado[3] + "/" + k.porGrado[4]
                + " (I/II/III/IV) -> " + esencias + " E y " + mc + " MC" + (factor > 1 ? " (racha x" + num(factor) + ")" : ""));
        if (extra > 0) lineas.add("primera salida del dia: +" + extra + " E");
        if (k.exceso[1] + k.exceso[2] > 0) lineas.add("exceso del tope diario: " + k.exceso[1] + " I, " + k.exceso[2] + " II");
        if (k.nulas() > 0) lineas.add("sin valor: " + k.falsas.size() + " falsas, " + k.duplicadas.size()
                + " duplicadas, " + k.caducadas.size() + " caducadas");
        if (!k.creditos.isEmpty()) lineas.add("creditos: " + String.join(", ", k.creditos));
        if (!real) return new Resumen(esencias + extra, mc, lineas);

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
            hc.datos().set(rutaDia + ".1", ya1 + k.monton[1]);
            hc.datos().set(rutaDia + ".2", ya2 + k.monton[2]);
        }
        if (primera) hc.datos().set("primera-extraccion." + u, dia);
        hc.marcarSucio();

        // 1.11: cuantas Esencias fisicas saca, para los puntos de su clan.
        int[] fisicas = {0};
        if (online != null) {
            Saldo s = hc.saldo();
            if (s != null) hc.seguro("saldo", () -> fisicas[0] = s.depositarFisicas(online));
        }

        Aduana ad = hc.aduana();
        Aduana.Pago pago = null;
        if (ad != null && (esencias + extra > 0 || mc > 0)) {
            pago = ad.pagar(op, "tasacion", esencias + extra, mc, List.of(), motivo);
        }
        int pagadasE = pago == null ? 0 : pago.esencias();
        long pagadasMc = pago == null ? 0 : pago.mc();
        // Calamity 1.11: lo que saca vivo suma para su clan (las Esencias que lleva y lo que valen sus Reliquias,
        // sin el extra de la primera salida del dia). Solo en una salida de verdad.
        ClanesCalamity clanes = hc.clanes();
        if (online != null && esSalida && clanes != null) {
            int deReliquias = Math.max(0, pagadasE - Math.min(pagadasE, extra));
            hc.seguro("clanes", () -> clanes.alTasar(online, fisicas[0], deReliquias));
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
            // La linea de la Tasacion, aparte de la que ponga Creditos: dice de que salida salio.
            bit.anotar("tasacion", "credito", nombre, tipo + " +1");
            if (online != null) online.sendMessage(ComandoCalamity.mensaje(Component.text("Con la venta has ganado ")
                    .append(Component.text(nombreCredito(tipo), Paleta.DETALLE)).append(Component.text("."))));
        }
        for (String mj : k.sellos) {
            hc.plugin().getServer().broadcast(ComandoCalamity.mensaje(Component.text(nombre, Paleta.DETALLE)
                    .append(Component.text(" ha salido de Calamity con el "))
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

        int rachaNueva = r;
        if (k.dosOMas && racha != null && esSalida) rachaNueva = racha.subir(u, online, salida);

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
                t.put("tasado", porGrado);
                t.put("esencias", pagadasE);
                t.put("mc", pagadasMc);
                t.put("mc_no_pagadas", pago == null ? 0 : pago.mcNoPagadas());
                t.put("racha", rachaNueva);
                t.put("racha_tope", racha == null ? 5 : racha.tope(salida));
                t.put("primera", primera ? "si" : "no");
                t.put("exceso", k.exceso[1] + k.exceso[2]);
                t.put("sin_valor", k.nulas());
                t.put("creditos", ganados);
                t.put("contratos", contratos);
                // mobs, destacados, minijefes y cofres los cuenta la propia Telemetria en su
                // sesion (los mismos para "sale" y "muere"): aqui no se mandan.
                hc.seguro("telemetria", () -> te.sale(online, motivo, t));
            }
            // Si la Aduana recortara Esencias, el recorte sale primero de la primera salida.
            int deLaPrimera = Math.min(pagadasE, extra);
            avisar(online, k, pagadasE - deLaPrimera, pagadasMc, factor, deLaPrimera);
            Encuesta enc = hc.encuesta();
            if (enc != null) hc.seguro("encuesta", () -> enc.trasTasar(online));
        }
        return new Resumen(pagadasE, pagadasMc, lineas);
    }

    /**
     * P-R01 y P-W06: "Has vendido 7 Reliquias (5 de grado I y 2 de grado II) por 3 Esencias y
     * 55 MobCoins." Numeros y nombres en su color (DIS sec. 5).
     */
    private void avisar(Player p, Cuenta k, int esencias, long mc, double factor, int extra) {
        int vendidas = 0;
        List<String> grados = new ArrayList<>();
        for (int g = 1; g <= 4; g++) {
            if (k.porGrado[g] <= 0) continue;
            vendidas += k.porGrado[g];
            grados.add(k.porGrado[g] + " de grado " + Reliquias.ROMANO[g]);
        }
        if (vendidas > 0) {
            Component c = Component.text("Has vendido ").append(cifra(vendidas))
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
            p.sendMessage(ComandoCalamity.mensaje(k.nulas() == 1 ? "Una Reliquia no valía nada (caducada o no válida)."
                    : k.nulas() + " Reliquias no valían nada (caducadas o no válidas)."));
        }
        int exceso = k.exceso[1] + k.exceso[2];
        if (exceso > 0) {
            p.sendMessage(ComandoCalamity.mensaje("Has llegado al tope diario de Reliquias de grado I y II: "
                    + (exceso == 1 ? "una no ha pagado nada." : exceso + " no han pagado nada.")));
        }
        if (extra > 0) {
            p.sendMessage(ComandoCalamity.mensaje(Component.text("Por ser tu primera salida del día, ganas ")
                    .append(cifra(extra)).append(Component.text(extra == 1 ? " Esencia más." : " Esencias más."))));
        }
    }

    private static int primerGrado(Cuenta k) {
        for (int g = 1; g <= 4; g++) if (k.porGrado[g] > 0) return g;
        return 1;
    }

    /** "a", "a y b", "a, b y c". */
    private static String lista(List<String> cosas) {
        if (cosas.size() <= 1) return cosas.isEmpty() ? "" : cosas.get(0);
        return String.join(", ", cosas.subList(0, cosas.size() - 1)) + " y " + cosas.get(cosas.size() - 1);
    }

    private static Component cifra(Object o) {
        return Paleta.cifra(o);
    }

    /** El credito ganado, con su articulo: "un Sello del Heraldo Carmesí", "una Marca de Eco". */
    private static String nombreCredito(String tipo) {
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

    /** +base, y +si-tasa si alguna Reliquia valio. */
    static int primera(int validas, Valores v) {
        return v.primeraBase() + (validas > 0 ? v.primeraSiTasa() : 0);
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
     * Reliquias (las III y especiales quedan emitidas en reliquias.log) y las tasa de verdad
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
                    "Uso: /calamidad tasar <jugador> <g1> <g2> <g3> [especial:grado:N[:valida|:minijefe] ...]"));
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
                        + "». Ejemplos: campana:3:45, lagrima:4:60:valida, sello:4:heraldo-carmes."));
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
        Resumen r = procesar(op, null, items, "tasar-admin", true, true);
        quien.sendMessage(ComandoCalamity.mensaje("Venta de " + Minijefes.nombreDe(op) + ": " + r.esencias()
                + " Esencias y " + r.mobcoins() + " MobCoins pagadas."));
        for (String l : r.lineas()) quien.sendMessage(Component.text("  " + l, Paleta.TENUE));
    }

    private List<String> tab(String[] args) {
        if (args.length == 2) return Reliquias.conectados();
        if (args.length == 3) return List.of("0", "1", "5", "reset");
        if (args.length <= 5) return List.of("0", "1", "5");
        List<String> op = new ArrayList<>(List.of("campana:3:45", "campana:4:52", "lagrima:4:60:valida", "lagrima:3:30",
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

        // Aceptacion 1: 5 I, 1 II, 1 III -> 5 E y 80 MC; con la primera del dia, 8 E.
        Cuenta a = contar(List.of(pieza(1, 5), pieza(2, 1), uuid(3, ambar, null, 0, null, false, ahora)), reg, 0, 0, v, ahora);
        h.igual("5 I + 1 II + 1 III: Esencias", 5, (int) Math.floor(a.esencias + 1e-9));
        h.igual("5 I + 1 II + 1 III: MC", 80L, a.mc);
        h.igual("primera del dia tasando algo", 3, primera(a.validas, v));
        h.igual("primera del dia sin tasar nada", 2, primera(0, v));
        h.igual("la III se marca para cobrar", List.of(ambar), a.cobrar);
        h.ok("con II o mas sube la racha", a.dosOMas);

        // Aceptacion 2: 70 I -> 60 pagadas (12 E, 300 MC) y 10 de exceso.
        Cuenta b = contar(List.of(pieza(1, 70)), reg, 0, 0, v, ahora);
        h.igual("70 I: Esencias", 12, (int) Math.floor(b.esencias + 1e-9));
        h.igual("70 I: MC", 300L, b.mc);
        h.igual("70 I: exceso", 10, b.exceso[1]);
        h.ok("solo I no sube la racha", !b.dosOMas);
        Cuenta b2 = contar(List.of(pieza(1, 70)), reg, 10, 0, v, ahora);
        h.igual("70 I con 10 ya tasadas hoy: pagan 50", 50, b2.porGrado[1]);
        Cuenta b3 = contar(List.of(pieza(2, 40)), reg, 0, 25, v, ahora);
        h.igual("40 II con 25 hoy: pagan 5", 5, b3.porGrado[2]);
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
        h.ok("el mismo UUID dos veces en una salida: una paga, otra duplicada",
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

    private static Pieza pieza(int grado, int cantidad) {
        return new Pieza(grado, null, null, 0, 0, null, false, cantidad);
    }

    private static Pieza uuid(int grado, String id, String especial, int nivel, String minijefe, boolean valida, long nacio) {
        return new Pieza(grado, especial, id, nacio, nivel, minijefe, valida, 1);
    }
}
