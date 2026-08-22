package net.ederus.edm.tienda;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * El precio dinamico. GLOBAL: lo que vende todo el servidor hunde el precio para
 * todos, que es lo que obliga a rotar la economia.
 *
 * LA CURVA
 *
 *   precio = base x (suelo + (1 - suelo) x 2^(-V/H))
 *
 * V es lo vendido en todo el servidor y H cuanto hay que vender para quedarse a
 * mitad de camino del suelo. No es lineal a proposito: restar un % por venta
 * tiene un acanti lado al llegar al suelo y castiga igual al que vende 10 que al
 * que vende 10.000. Con la exponencial las primeras ventas casi no mueven el
 * precio y el castigo crece con el volumen.
 *
 * LA RECUPERACION es la misma operacion al reves: V decae solo con vida media
 * 'recuperacion'. Un solo mecanismo para las dos direcciones.
 *
 * H NO SE CONFIGURA 968 VECES. Se deriva del dinero: 'presupuesto' es cuanto
 * dinero hay que sacarle a un item para que su precio caiga a mitad de camino
 * del suelo, y H sale de dividirlo entre el precio base. Asi un item caro
 * aguanta pocas unidades y uno barato muchas, sin tocar nada a mano.
 */
public final class Mercado {

    /**
     * Presion acumulada por clave.
     *
     * 'vendido' es lo que habia justo DESPUES de la ultima venta y 'momento'
     * cuando fue. Antes se guardaba "lo que queda ahora mismo" y se reescribia
     * en cada lectura, lo que obligaba a que el olvido no tuviera memoria; con
     * el tope de recuperacion si la tiene, asi que hay que saber cuando fue la
     * ultima venta de verdad.
     */
    private static final class Estado {
        double vendido;
        long momento;
        Estado(double vendido, long momento) { this.vendido = vendido; this.momento = momento; }
    }

    private final Map<String, Estado> estados = new ConcurrentHashMap<>();
    private final File fichero;

    private boolean activo = true;
    private double suelo = 0.25;
    private long recuperacionMs = 6L * 3600_000L;
    /** Pase lo que pase, a las tantas horas de la ultima venta el precio esta
     *  como nuevo. 0 = sin tope (el olvido de siempre, que nunca llega a cero). */
    private long olvidoTotalMs = 24L * 3600_000L;
    private double presupuesto = 300_000d;
    private double margenVentaCompra = 0.60;
    private volatile boolean sucio;

    public Mercado(File fichero) { this.fichero = fichero; }

    public void configurar(ConfigurationSection sec) {
        if (sec == null) return;
        activo = sec.getBoolean("activo", activo);
        suelo = Math.min(1, Math.max(0.05, sec.getDouble("suelo", suelo)));
        recuperacionMs = Math.max(60_000L, Catalogo.leerDuracion(sec.getString("recuperacion", "6h")));
        String tope = sec.getString("recuperacion-total", "24h");
        if (tope == null || tope.isBlank() || tope.trim().equals("0")
                || tope.trim().equalsIgnoreCase("no") || tope.trim().equalsIgnoreCase("nunca")) {
            olvidoTotalMs = 0;
        } else {
            /* Nunca por debajo de la vida media: un tope mas corto que ella
             * deja una curva rarisima y no es lo que quiere nadie. */
            olvidoTotalMs = Math.max(recuperacionMs, Catalogo.leerDuracion(tope));
        }
        presupuesto = Math.max(1, sec.getDouble("presupuesto", presupuesto));
        margenVentaCompra = Math.min(0.95, Math.max(0.05, sec.getDouble("margen-venta-compra", margenVentaCompra)));
    }

    /** Cuantas unidades hunden el precio a mitad de camino del suelo. */
    private double mitad(Catalogo.Articulo art) {
        double base = art.venta() > 0 ? art.venta() : art.compra();
        if (base <= 0) return Double.MAX_VALUE;
        return Math.max(8, presupuesto / base);
    }

    /**
     * Que fraccion de lo vendido sigue pesando despues de 'transcurrido'.
     *
     * 1 recien vendido, 0 cuando ya se olvido del todo. La forma es la de
     * siempre —la mitad cada 'recuperacion'— pero desplazada para que toque
     * CERO exacto al llegar al tope, en vez de acercarse sin llegar nunca.
     * Sin el desplazamiento, tras una venta muy gorda el precio seguia por
     * debajo dos y tres dias despues.
     */
    private double queda(long transcurrido) {
        if (transcurrido <= 0) return 1;
        double vidas = (double) transcurrido / recuperacionMs;
        if (olvidoTotalMs <= 0) return Math.pow(2, -vidas);
        if (transcurrido >= olvidoTotalMs) return 0;
        double resto = Math.pow(2, -(double) olvidoTotalMs / recuperacionMs);
        return (Math.pow(2, -vidas) - resto) / (1 - resto);
    }

    /** Lo vendido que "queda", ya descontado el olvido por el paso del tiempo.
     *  Es una LECTURA: no toca el estado salvo para tirar lo ya olvidado. */
    private double presion(String clave) {
        Estado e = estados.get(clave);
        if (e == null) return 0;
        double v;
        synchronized (e) {
            v = e.vendido * queda(System.currentTimeMillis() - e.momento);
        }
        if (v < 0.01) {
            estados.remove(clave);
            sucio = true;
            return 0;
        }
        return v;
    }

    /** La presion actual de ese articulo, para cotizar un tramo que empieza
     *  donde acabaria otro. Es la misma lectura que usa el precio. */
    public double presionActual(String clave) { return presion(clave); }

    /** 1.0 = precio intacto; 'suelo' = todo lo bajo que puede llegar. */
    public double multiplicador(Catalogo.Articulo art) {
        if (!activo || !art.seVende()) return 1;
        double v = presion(art.clave());
        if (v <= 0) return 1;
        return suelo + (1 - suelo) * Math.pow(2, -v / mitad(art));
    }

    /**
     * El precio de venta que se paga de verdad.
     *
     * El recorte contra la compra NO es negociable: si la venta se acerca al
     * precio de compra, comprar y vender el mismo item da beneficio y eso es
     * dinero infinito. Se aplica sobre la compra EFECTIVA (la que pagaria el
     * jugador ahora mismo, con Ofertas incluidas), no sobre la del fichero.
     */
    public double ventaEfectiva(Catalogo.Articulo art, double compraEfectiva) {
        double precio = art.venta() * multiplicador(art);
        if (compraEfectiva > 0) {
            double techo = compraEfectiva * margenVentaCompra;
            if (precio > techo) return techo;
        }
        return precio;
    }

    public boolean recortado(Catalogo.Articulo art, double compraEfectiva) {
        return compraEfectiva > 0
                && art.venta() * multiplicador(art) > compraEfectiva * margenVentaCompra;
    }

    /** Se llama DESPUES de una venta cobrada. */
    public void anotarVenta(Catalogo.Articulo art, int cantidad) {
        if (!activo || cantidad <= 0) return;
        long ahora = System.currentTimeMillis();
        estados.compute(art.clave(), (k, e) -> {
            if (e == null) return new Estado(cantidad, ahora);
            synchronized (e) {
                /* Se suma sobre lo que QUEDA ahora, no sobre lo que habia en la
                 * venta anterior: las horas de en medio ya se olvidaron. */
                e.vendido = e.vendido * queda(ahora - e.momento) + cantidad;
                e.momento = ahora;
            }
            return e;
        });
        sucio = true;
    }

    /** Cuanto ha caido, en porcentaje, para enseñarlo en el menu. */
    public int caidaPorCiento(Catalogo.Articulo art) {
        return (int) Math.round((1 - multiplicador(art)) * 100);
    }

    /**
     * Lo que se paga por vender 'cantidad' unidades AHORA, integrando el precio
     * a lo largo de la venta.
     *
     * Sin esto, vender 1200 de golpe se cobraria al precio de la primera unidad
     * y pagaria mas que vender 12 veces 100: la devaluacion no morderia justo en
     * el caso para el que existe, y ademas premiaria al que acumula. Con la
     * integral, partir la venta da exactamente lo mismo.
     *
     * La curva es base x (suelo + (1-suelo) x 2^(-v/H)), asi que su integral
     * entre V y V+n tiene forma cerrada y no hace falta sumar unidad a unidad.
     */
    public double totalVenta(Catalogo.Articulo art, int cantidad, double compraEfectiva) {
        if (cantidad <= 0) return 0;
        double techo = compraEfectiva > 0 ? compraEfectiva * margenVentaCompra : Double.MAX_VALUE;
        if (!activo || !art.seVende()) return Math.min(art.venta(), techo) * cantidad;

        double v = presion(art.clave());
        double h = mitad(art);
        double ln2 = Math.log(2);
        double parteFija = suelo * cantidad;
        double parteQueCae = (1 - suelo) * (h / ln2)
                * (Math.pow(2, -v / h) - Math.pow(2, -(v + cantidad) / h));
        double total = art.venta() * (parteFija + parteQueCae);

        /* El recorte contra la compra se aplica al precio medio resultante: si
         * hiciera falta, ninguna unidad puede haberse pagado por encima. */
        return Math.min(total, techo * cantidad);
    }

    /** Como totalVenta pero partiendo de una presion inventada, para poder
     *  enseñar en una tabla que pasaria con el mercado ya cargado. */
    public double totalVentaDesde(Catalogo.Articulo art, int cantidad, double compraEfectiva, double presionPrevia) {
        if (cantidad <= 0) return 0;
        /* Igual que totalVenta: con el mercado apagado el precio es el del
         * fichero, no cero. Devolver cero aqui hacia que la tabla de
         * /etienda mercado dijera que no se paga nada. */
        double techoFijo = compraEfectiva > 0 ? compraEfectiva * margenVentaCompra : Double.MAX_VALUE;
        if (!activo || !art.seVende()) return Math.min(art.venta(), techoFijo) * cantidad;
        double h = mitad(art), ln2 = Math.log(2);
        double total = art.venta() * (suelo * cantidad + (1 - suelo) * (h / ln2)
                * (Math.pow(2, -presionPrevia / h) - Math.pow(2, -(presionPrevia + cantidad) / h)));
        double techo = compraEfectiva > 0 ? compraEfectiva * margenVentaCompra * cantidad : Double.MAX_VALUE;
        return Math.min(total, techo);
    }

    /** Que precio tendria si el servidor vendiera 'extra' unidades mas. Solo
     *  calcula: no toca el estado. Sirve para afinar los numeros sin probarlos
     *  en produccion. */
    public double simular(Catalogo.Articulo art, double extra) {
        if (!activo || !art.seVende()) return art.venta();
        double v = presion(art.clave()) + Math.max(0, extra);
        double mult = suelo + (1 - suelo) * Math.pow(2, -v / mitad(art));
        double precio = art.venta() * mult;
        double techo = art.compra() > 0 ? art.compra() * margenVentaCompra : Double.MAX_VALUE;
        return Math.min(precio, techo);
    }

    public boolean activo() { return activo; }
    public double suelo() { return suelo; }
    /** Horas hasta que un item vuelve a su precio de siempre. 0 = sin tope. */
    public long olvidoTotalMs() { return olvidoTotalMs; }
    public double margen() { return margenVentaCompra; }
    public int itemsMovidos() { return estados.size(); }

    // ---------------------------------------------------------- persistencia

    public void cargar() {
        estados.clear();
        if (!fichero.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichero);
        for (String clave : yml.getKeys(false)) {
            double v = yml.getDouble(clave + ".vendido", 0);
            long m = yml.getLong(clave + ".momento", 0);
            if (v > 0 && m > 0) estados.put(clave, new Estado(v, m));
        }
        sucio = false;
    }

    public void guardar() {
        if (!sucio) return;
        YamlConfiguration yml = new YamlConfiguration();
        estados.forEach((clave, e) -> {
            synchronized (e) {
                /* Lo ya olvidado no se escribe: si no, el fichero se queda con
                 * lineas de items que hace dias que nadie vende. */
                if (e.vendido * queda(System.currentTimeMillis() - e.momento) < 0.01) return;
                yml.set(clave + ".vendido", Math.round(e.vendido * 100) / 100d);
                yml.set(clave + ".momento", e.momento);
            }
        });
        try {
            File padre = fichero.getParentFile();
            if (padre != null) padre.mkdirs();
            yml.save(fichero);
            sucio = false;
        } catch (IOException ex) {
            throw new IllegalStateException("no pude guardar el mercado: " + ex.getMessage(), ex);
        }
    }
}
