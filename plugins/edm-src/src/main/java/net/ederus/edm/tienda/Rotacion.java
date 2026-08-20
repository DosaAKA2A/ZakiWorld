package net.ederus.edm.tienda;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ofertas y Demandas: las dos secciones que cambian solas cada dia.
 *
 *   OFERTAS  - baja el precio de COMPRA entre un 15% y un 65%.
 *   DEMANDAS - sube el precio de VENTA, para que haya otra via de ganar dinero.
 *
 * POR QUE NO PUEDEN SOLAPARSE, y como se garantiza:
 *
 * Si un item se abarata al comprar y encarece al vender a la vez, comprarlo y
 * venderlo da beneficio: dinero infinito. Aqui no puede pasar por construccion,
 * no por una comprobacion que se pueda olvidar:
 *
 *   Ofertas solo elige entre items que la tienda NO recompra (venta == 0).
 *   Demandas solo elige entre items que SI se venden (venta > 0).
 *
 * Los dos conjuntos son disjuntos por definicion. Ademas hace falta: en el
 * catalogo real hay cacao a compra 30 / venta 15, y un -65% lo dejaria en 10,50
 * comprando y 15 vendiendo. Con esta regla el cacao nunca entra en Ofertas.
 *
 * EL SORTEO ES DETERMINISTA: la semilla sale de la fecha. Reiniciar el servidor
 * no vuelve a sortear, y dos arranques del mismo dia dan la misma lista.
 */
public final class Rotacion {

    public record Trato(String clave, double factor, int topeDia) { }

    private final File fichero;

    private boolean activo = true;
    private int cuantasOfertas = 14;
    private int cuantasDemandas = 14;
    private double ofertaMin = 0.15, ofertaMax = 0.65;
    private double demandaMin = 0.25, demandaMax = 0.75;
    private int topeOferta = 512, topeDemanda = 512;
    private long semillaFija = 0;

    private LocalDate dia;
    private final Map<String, Trato> ofertas = new LinkedHashMap<>();
    private final Map<String, Trato> demandas = new LinkedHashMap<>();
    /** Lo movido hoy por item, para el tope diario de SERVIDOR. */
    private final Map<String, Integer> movidoHoy = new ConcurrentHashMap<>();
    private volatile boolean sucio;

    public Rotacion(File fichero) { this.fichero = fichero; }

    public void configurar(ConfigurationSection sec) {
        if (sec == null) return;
        activo = sec.getBoolean("activo", activo);
        cuantasOfertas = Math.max(1, Math.min(45, sec.getInt("cuantas-ofertas", cuantasOfertas)));
        cuantasDemandas = Math.max(1, Math.min(45, sec.getInt("cuantas-demandas", cuantasDemandas)));
        ofertaMin = sec.getDouble("descuento-min", ofertaMin);
        ofertaMax = sec.getDouble("descuento-max", ofertaMax);
        demandaMin = sec.getDouble("bonus-min", demandaMin);
        demandaMax = sec.getDouble("bonus-max", demandaMax);
        topeOferta = Math.max(1, sec.getInt("tope-diario-oferta", topeOferta));
        topeDemanda = Math.max(1, sec.getInt("tope-diario-demanda", topeDemanda));
        semillaFija = sec.getLong("semilla", semillaFija);
    }

    /** Sortea el dia de hoy si aun no estaba sorteado. */
    public synchronized void alDia(Catalogo catalogo) {
        if (!activo) return;
        LocalDate hoy = LocalDate.now();
        if (hoy.equals(dia)) return;

        dia = hoy;
        ofertas.clear();
        demandas.clear();
        movidoHoy.clear();
        sucio = true;

        List<Catalogo.Articulo> soloCompra = new ArrayList<>();
        List<Catalogo.Articulo> vendibles = new ArrayList<>();
        for (String clave : catalogo.claves()) {
            Catalogo.Articulo a = catalogo.de(clave);
            if (a == null) continue;
            /* La regla que hace imposible el arbitraje: quien entra en Ofertas
             * no puede venderse, y quien entra en Demandas no puede abaratarse. */
            if (a.seCompra() && !a.seVende()) soloCompra.add(a);
            else if (a.seVende()) vendibles.add(a);
        }

        Random azar = new Random(semilla(hoy));
        elegir(soloCompra, cuantasOfertas, azar, ofertaMin, ofertaMax, topeOferta, ofertas, true);
        elegir(vendibles, cuantasDemandas, azar, demandaMin, demandaMax, topeDemanda, demandas, false);
    }

    private long semilla(LocalDate hoy) {
        return semillaFija != 0 ? semillaFija * 31 + hoy.toEpochDay() : hoy.toEpochDay() * 0x9E3779B97F4A7C15L;
    }

    private static void elegir(List<Catalogo.Articulo> fuente, int cuantos, Random azar,
                               double min, double max, int tope,
                               Map<String, Trato> destino, boolean descuento) {
        if (fuente.isEmpty()) return;
        List<Catalogo.Articulo> copia = new ArrayList<>(fuente);
        java.util.Collections.shuffle(copia, azar);
        for (int i = 0; i < Math.min(cuantos, copia.size()); i++) {
            Catalogo.Articulo a = copia.get(i);
            double p = min + azar.nextDouble() * Math.max(0, max - min);
            /* Descuento resta y bonus suma: el factor se guarda ya listo para
             * multiplicar, para que el motor no tenga que saber cual es cual. */
            double factor = descuento ? 1 - p : 1 + p;
            destino.put(a.clave(), new Trato(a.clave(), factor, tope));
        }
    }

    public Trato oferta(String clave) { return activo ? ofertas.get(clave) : null; }
    public Trato demanda(String clave) { return activo ? demandas.get(clave) : null; }
    public Iterable<Trato> ofertas() { return ofertas.values(); }
    public Iterable<Trato> demandas() { return demandas.values(); }
    public boolean activo() { return activo; }
    public LocalDate dia() { return dia; }

    /** Lo que queda hoy de ese trato en TODO el servidor. */
    public int restanteHoy(Trato t) {
        if (t == null) return Integer.MAX_VALUE;
        return Math.max(0, t.topeDia() - movidoHoy.getOrDefault(t.clave(), 0));
    }

    public void anotar(String clave, int cantidad) {
        if (cantidad <= 0) return;
        movidoHoy.merge(clave, cantidad, Integer::sum);
        sucio = true;
    }

    /** Milisegundos hasta la siguiente rotacion (medianoche). */
    public static long hastaManana() {
        var ahora = java.time.LocalDateTime.now();
        var manana = ahora.toLocalDate().plusDays(1).atStartOfDay();
        return java.time.Duration.between(ahora, manana).toMillis();
    }

    // ---------------------------------------------------------- persistencia

    public void cargar(Catalogo catalogo) {
        if (fichero.exists()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichero);
            String d = yml.getString("dia", "");
            ConfigurationSection mov = yml.getConfigurationSection("movido");
            /* Solo se recupera lo movido si es del MISMO dia: si no, el tope
             * diario se arrastraria de ayer. */
            if (!d.isBlank() && d.equals(LocalDate.now().toString()) && mov != null) {
                for (String k : mov.getKeys(false)) movidoHoy.put(k, mov.getInt(k, 0));
            }
        }
        alDia(catalogo);   // el sorteo se rehace igual: es determinista por fecha
        sucio = false;
    }

    public void guardar() {
        if (!sucio) return;
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("dia", dia == null ? "" : dia.toString());
        movidoHoy.forEach((k, v) -> yml.set("movido." + k, v));
        try {
            File padre = fichero.getParentFile();
            if (padre != null) padre.mkdirs();
            yml.save(fichero);
            sucio = false;
        } catch (IOException e) {
            throw new IllegalStateException("no pude guardar la rotacion: " + e.getMessage(), e);
        }
    }
}
