package net.ederus.lethalworld.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /lw hardcore stats [dias]: el resumen de la telemetria para el staff (MED sec. 3.4).
 *
 * Existe para contestar sin sacar el analizador de Python la pregunta de cada dia: que
 * entra, que sale vivo y que se pierde. Por objeto: entregado (recompensas y trueques),
 * extraido vivo (Reliquias tasadas y equipo con el que se sale), perdido al morir (lo que
 * llevaba el muerto) y fallido (clics del altar que no pudieron pagar: interes frustrado).
 *
 * Lee los .jsonl en un hilo aparte (un mes de sucesos pueden ser megas) y contesta en el
 * principal. Las lineas con "prueba": true no cuentan, igual que en el analizador.
 */
final class StatsTelemetria {

    private static final Pattern FICHERO = Pattern.compile("(\\d{4}-\\d{2})\\.jsonl");
    private static final int FILAS = 15;

    private final Hardcore hc;
    private final File carpeta;
    private final Set<BukkitTask> tareas = ConcurrentHashMap.newKeySet();

    StatsTelemetria(Hardcore hc, File carpeta) {
        this.hc = hc;
        this.carpeta = carpeta;
        Subcomandos.lw().registrar("stats", "stats [dias]: que se entrega, se saca vivo y se pierde (telemetria)",
                "ederus.mundos", this::comando, args -> args.length == 2 ? List.of("1", "7", "30") : List.of());
        Autotest.registrar("stats", StatsTelemetria::autotest);
    }

    void parar() {
        for (BukkitTask t : tareas) t.cancel();
        tareas.clear();
    }

    private void comando(CommandSender quien, String[] args) {
        int dias = 7;
        if (args.length >= 2) {
            try {
                dias = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                quien.sendMessage(Component.text("stats [dias]: los dias son un numero (1-180).", NamedTextColor.RED));
                return;
            }
        }
        final int d = Math.max(1, Math.min(180, dias));
        long desde = System.currentTimeMillis() - d * 86_400_000L;
        quien.sendMessage(Component.text("Leyendo la telemetria de " + d + " dias...", NamedTextColor.GRAY));
        BukkitTask[] propia = new BukkitTask[1];
        propia[0] = hc.plugin().getServer().getScheduler().runTaskAsynchronously(hc.plugin(), () -> {
            List<String> lineas;
            try {
                lineas = leer(carpeta, desde).lineas(d);
            } catch (Throwable t) {
                lineas = List.of("stats | no se pudo leer la telemetria: " + t);
            }
            final List<String> salida = lineas;
            if (propia[0] != null) tareas.remove(propia[0]);
            if (!hc.plugin().isEnabled()) return;
            BukkitTask[] vuelta = new BukkitTask[1];
            vuelta[0] = hc.plugin().getServer().getScheduler().runTask(hc.plugin(), () -> {
                if (vuelta[0] != null) tareas.remove(vuelta[0]);
                for (String l : salida) quien.sendMessage(Component.text(l, NamedTextColor.GRAY));
            });
            tareas.add(vuelta[0]);
        });
        tareas.add(propia[0]);
    }

    // ----------------------------------------------------------------- lectura

    /** Lee los meses que tocan al periodo y agrega. Seguro en cualquier hilo. */
    static Informe leer(File carpeta, long desde) throws IOException {
        Informe inf = new Informe();
        File[] fs = carpeta.listFiles();
        if (fs == null) return inf;
        Arrays.sort(fs);
        YearMonth primero = YearMonth.from(OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(desde), ZoneOffset.UTC))
                .minusMonths(1);
        for (File f : fs) {
            Matcher m = FICHERO.matcher(f.getName());
            if (!m.matches()) continue;
            try {
                if (YearMonth.parse(m.group(1)).isBefore(primero)) continue;
            } catch (Throwable t) {
                continue;
            }
            try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                String l;
                while ((l = r.readLine()) != null) inf.linea(l, desde);
            }
        }
        return inf;
    }

    /** Los contadores de un periodo. */
    static final class Informe {
        long sucesos, malas, entradas, salidas, muertes, esencias, mc, mcNoPagadas;
        double sumaEntra, sumaMuere;
        long censosEntra, censosMuere;
        final Set<String> jugadores = new HashSet<>();
        final Map<String, Long> motivos = new TreeMap<>();
        /** objeto -> [entregado, extraido, perdido, fallido] */
        final Map<String, long[]> objetos = new LinkedHashMap<>();

        void linea(String l, long desde) {
            if (l == null || l.isBlank()) return;
            Map<String, Object> m = Jsonl.objeto(l);
            if (m == null) {
                malas++;
                return;
            }
            if (Boolean.TRUE.equals(m.get("prueba"))) return;
            try {
                if (OffsetDateTime.parse(String.valueOf(m.get("t"))).toInstant().toEpochMilli() < desde) return;
            } catch (Throwable t) {
                malas++;
                return;
            }
            sucesos++;
            Object u = m.get("uuid");
            if (u instanceof String s && !s.isEmpty()) jugadores.add(s);
            String ev = String.valueOf(m.get("ev"));
            switch (ev) {
                case "entra" -> {
                    entradas++;
                    double e = escalonMedio(m.get("censo"));
                    if (e >= 0) {
                        sumaEntra += e;
                        censosEntra++;
                    }
                }
                case "sale" -> {
                    salidas++;
                    motivos.merge(String.valueOf(m.getOrDefault("motivo", "?")), 1L, Long::sum);
                    porGrado(m.get("tasado"), 1);
                    piezas(m.get("censo_salida"), 1);
                }
                case "muere" -> {
                    muertes++;
                    porGrado(m.get("reliquias"), 2);
                    piezas(m.get("censo"), 2);
                    double e = escalonMedio(m.get("censo"));
                    if (e >= 0) {
                        sumaMuere += e;
                        censosMuere++;
                    }
                }
                case "pago" -> {
                    esencias += num(m.get("esencias"));
                    mc += num(m.get("mc"));
                    mcNoPagadas += num(m.get("mc_no_pagadas"));
                }
                case "recompensa" -> sumar(String.valueOf(m.get("objeto")), 0, Math.max(1, num(m.get("cantidad"))));
                case "trueque" -> {
                    if (!"fallo".equals(m.get("entrega"))) sumar("trueque:" + m.get("id"), 0, 1);
                }
                case "trueque-fallido" -> sumar("trueque:" + m.get("id"), 3, 1);
                default -> {
                    // El resto de sucesos solo cuenta en el total.
                }
            }
        }

        private void sumar(String objeto, int columna, long n) {
            if (objeto == null || objeto.equals("null") || n <= 0) return;
            objetos.computeIfAbsent(objeto, k -> new long[4])[columna] += n;
        }

        private void porGrado(Object mapa, int columna) {
            if (!(mapa instanceof Map<?, ?> m)) return;
            for (Map.Entry<?, ?> e : m.entrySet()) sumar("reliquia-" + e.getKey(), columna, num(e.getValue()));
        }

        private void piezas(Object censo, int columna) {
            if (!(censo instanceof Map<?, ?> c) || !(c.get("piezas") instanceof List<?> l)) return;
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> p)) continue;
                // Lo prestado y las copias no se sacan ni se pierden de verdad.
                if (p.get("marcas") instanceof List<?> marcas && (marcas.contains("prestado") || marcas.contains("copia_eco"))) {
                    continue;
                }
                sumar(String.valueOf(p.get("mmo")), columna, 1);
            }
        }

        private static double escalonMedio(Object censo) {
            if (censo instanceof Map<?, ?> c && c.get("escalon_medio") instanceof Number n) return n.doubleValue();
            return -1;
        }

        private static long num(Object o) {
            return o instanceof Number n ? n.longValue() : 0;
        }

        List<String> lineas(int dias) {
            List<String> out = new ArrayList<>();
            out.add("stats | " + dias + " días | " + sucesos + " sucesos | " + jugadores.size() + " jugadores"
                    + (malas > 0 ? " | " + malas + " líneas ilegibles" : ""));
            long cierres = salidas + muertes;
            StringBuilder mot = new StringBuilder();
            for (Map.Entry<String, Long> e : motivos.entrySet()) {
                if (mot.length() > 0) mot.append(", ");
                mot.append(e.getKey()).append(' ').append(e.getValue());
            }
            out.add("entradas " + entradas + " · salidas " + salidas + (mot.length() > 0 ? " (" + mot + ")" : "")
                    + " · muertes " + muertes + " · extracción "
                    + (cierres == 0 ? "-" : Math.round(salidas * 100.0 / cierres) + " %"));
            out.add("pagos " + esencias + " E · " + mc + " MC · recortadas " + mcNoPagadas + " MC"
                    + (mc + mcNoPagadas > 0 ? " (" + Math.round(mcNoPagadas * 100.0 / (mc + mcNoPagadas)) + " %)" : ""));
            out.add("escalón medio arriesgado al entrar " + media(sumaEntra, censosEntra)
                    + " · al morir " + media(sumaMuere, censosMuere));
            if (objetos.isEmpty()) {
                out.add("sin objetos en el periodo");
                return out;
            }
            out.add("objeto · entregado · extraído · perdido · fallido");
            List<Map.Entry<String, long[]>> filas = new ArrayList<>(objetos.entrySet());
            filas.sort((a, b) -> Long.compare(total(b.getValue()), total(a.getValue())));
            for (int i = 0; i < filas.size() && i < FILAS; i++) {
                long[] v = filas.get(i).getValue();
                out.add("  " + filas.get(i).getKey() + " · " + v[0] + " · " + v[1] + " · " + v[2] + " · " + v[3]);
            }
            if (filas.size() > FILAS) out.add("  ... y " + (filas.size() - FILAS) + " más");
            return out;
        }

        private static long total(long[] v) {
            return v[0] + v[1] + v[2] + v[3];
        }

        private static String media(double suma, long n) {
            return n == 0 ? "-" : String.format(Locale.ROOT, "%.1f", suma / n);
        }
    }

    // ---------------------------------------------------------------- autotest

    /** El agregado con lineas escritas a mano: sin tocar ficheros. */
    private static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        Informe inf = new Informe();
        String t = "2026-09-26T21:00:00+02:00";
        long desde = OffsetDateTime.parse("2026-09-20T00:00:00+02:00").toInstant().toEpochMilli();
        String comun = "\"t\":\"" + t + "\",\"uuid\":\"u1\",\"nombre\":\"A\",\"mundo\":\"calamity\",\"v\":1";
        inf.linea("{" + comun + ",\"ev\":\"entra\",\"censo\":{\"escalon_medio\":4.0,\"piezas\":[]}}", desde);
        inf.linea("{" + comun + ",\"ev\":\"sale\",\"motivo\":\"puerta\",\"tasado\":{\"1\":3,\"2\":1,\"3\":0,\"4\":0},"
                + "\"censo_salida\":{\"escalon_medio\":4.0,\"piezas\":[{\"mmo\":\"VANILLA:DIAMOND_SWORD\",\"marcas\":[]},"
                + "{\"mmo\":\"VANILLA:IRON_BOOTS\",\"marcas\":[\"prestado\"]}]}}", desde);
        inf.linea("{" + comun + ",\"ev\":\"muere\",\"reliquias\":{\"1\":2},\"censo\":{\"escalon_medio\":6.0,"
                + "\"piezas\":[{\"mmo\":\"VANILLA:DIAMOND_SWORD\",\"marcas\":[]}]}}", desde);
        inf.linea("{" + comun + ",\"ev\":\"pago\",\"esencias\":5,\"mc\":80,\"mc_no_pagadas\":20}", desde);
        inf.linea("{" + comun + ",\"ev\":\"recompensa\",\"objeto\":\"llave\",\"cantidad\":2}", desde);
        inf.linea("{" + comun + ",\"ev\":\"trueque-fallido\",\"id\":\"frasco\",\"motivo\":\"esencias\"}", desde);
        inf.linea("{" + comun + ",\"ev\":\"pago\",\"esencias\":99,\"prueba\":true}", desde);
        inf.linea("{\"t\":\"2026-09-01T00:00:00+02:00\",\"ev\":\"pago\",\"esencias\":77}", desde);
        inf.linea("{roto", desde);
        h.igual("sucesos del periodo (sin prueba ni viejos)", 6L, inf.sucesos);
        h.igual("linea rota contada como ilegible", 1L, inf.malas);
        h.igual("esencias pagadas", 5L, inf.esencias);
        h.igual("MC recortadas", 20L, inf.mcNoPagadas);
        h.igual("reliquia-1 extraidas", 3L, inf.objetos.get("reliquia-1")[1]);
        h.igual("reliquia-1 perdidas", 2L, inf.objetos.get("reliquia-1")[2]);
        h.igual("espada extraida", 1L, inf.objetos.get("VANILLA:DIAMOND_SWORD")[1]);
        h.igual("espada perdida", 1L, inf.objetos.get("VANILLA:DIAMOND_SWORD")[2]);
        h.ok("lo prestado no cuenta", !inf.objetos.containsKey("VANILLA:IRON_BOOTS"));
        h.igual("llaves entregadas", 2L, inf.objetos.get("llave")[0]);
        h.igual("trueque fallido", 1L, inf.objetos.get("trueque:frasco")[3]);
        h.ok("resumen con extraccion 50 %", String.join("\n", inf.lineas(7)).contains("extracción 50 %"));
        return h.lineas();
    }
}
