package net.ederus.edm.coinflip;

import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.Textos;

import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Las apuestas abiertas y, sobre todo, el dinero.
 *
 * Es la parte peligrosa del modulo, igual que el Motor lo es de la tienda: un
 * fallo aqui no rompe una partida, duplica dinero. Las reglas que no se tocan:
 *
 *  1. **Se cobra al PONER la apuesta, no al resolverla.** Si se cobrara al
 *     final, cualquiera podria apostar un millon, gastarselo y ganar sin haber
 *     puesto nada.
 *  2. **Si el pago al ganador falla, se devuelve a los dos.** Nunca se queda el
 *     dinero en el aire ni se paga dos veces.
 *  3. **Una apuesta se marca como tomada ANTES de tocar el dinero.** Dos clics
 *     casi a la vez sobre la misma mesa la aceptarian dos veces, y el creador
 *     habria cobrado una sola.
 *  4. **Todo lo que este abierto se devuelve al apagar Y al arrancar.** Lo de
 *     arrancar es por si el servidor se cayo de mala manera: el fichero
 *     sobrevive a la caida y el dinero vuelve solo.
 */
public final class Mesa {

    /** Lo que sale de crear, cancelar o aceptar. */
    public record Resultado(boolean ok, Component mensaje) {
        static Resultado no(Component m) { return new Resultado(false, m); }
    }

    /** Una partida ya resuelta y pagada. La animacion solo la representa. */
    public record Jugada(boolean ok, Component mensaje, Apuesta apuesta,
                         UUID ganador, String nombreGanador,
                         UUID perdedor, String nombrePerdedor,
                         double bote, double comision, double premio) {
        static Jugada no(Component m) {
            return new Jugada(false, m, null, null, null, null, null, 0, 0, 0);
        }
    }

    private final File fichero;
    private final Logger log;
    private final Textos textos;
    private final RegistroCf registro;
    private Economy economia;

    private final Map<Long, Apuesta> abiertas = new LinkedHashMap<>();
    private long siguienteId = 1;

    /* Ajustes */
    private double minima = 1000;
    private double maxima = 10_000_000;
    private double comisionPorCiento = 5;
    private int maximoAbiertas = 3;
    private int esperaSegundos = 5;
    private int caducidadMinutos = 30;
    private boolean retosActivos = true;
    private boolean cancelarAlSalir = true;

    private final Map<UUID, Long> ultimaVez = new LinkedHashMap<>();

    public Mesa(File fichero, Logger log, Textos textos, RegistroCf registro) {
        this.fichero = fichero;
        this.log = log;
        this.textos = textos;
        this.registro = registro;
    }

    public void economia(Economy e) { this.economia = e; }
    public boolean lista() { return economia != null; }

    /** El saldo, para las cabezas de los menus. 0 si aun no hay banco. */
    public double saldo(Player quien) {
        return economia == null ? 0 : economia.getBalance(quien);
    }

    public void configurar(ConfigurationSection sec) {
        if (sec == null) return;
        minima = Math.max(1, sec.getDouble("apuesta-minima", minima));
        maxima = Math.max(minima, sec.getDouble("apuesta-maxima", maxima));
        comisionPorCiento = Math.max(0, Math.min(100, sec.getDouble("comision", comisionPorCiento)));
        maximoAbiertas = Math.max(1, sec.getInt("maximo-abiertas", maximoAbiertas));
        esperaSegundos = Math.max(0, sec.getInt("espera-segundos", esperaSegundos));
        caducidadMinutos = Math.max(0, sec.getInt("caducidad-minutos", caducidadMinutos));
        cancelarAlSalir = sec.getBoolean("cancelar-al-salir", cancelarAlSalir);
        ConfigurationSection retos = sec.getConfigurationSection("retos");
        if (retos != null) retosActivos = retos.getBoolean("activos", retosActivos);
    }

    public double minima() { return minima; }
    public double maxima() { return maxima; }
    public double comisionPorCiento() { return comisionPorCiento; }
    public boolean retosActivos() { return retosActivos; }
    public boolean cancelarAlSalir() { return cancelarAlSalir; }

    // ------------------------------------------------------------------ leer

    public Apuesta de(long id) { return abiertas.get(id); }

    /** Todas, en el orden en que se pusieron. */
    public List<Apuesta> todas() { return new ArrayList<>(abiertas.values()); }

    /** Las que ese jugador puede coger: las abiertas de otros y los retos suyos. */
    public List<Apuesta> visiblesPara(UUID quien) {
        List<Apuesta> out = new ArrayList<>();
        for (Apuesta a : abiertas.values()) {
            if (a.esDe(quien)) { out.add(a); continue; }          // las suyas, para cancelar
            if (a.esReto() && !a.esPara(quien)) continue;          // un reto a otro no le incumbe
            out.add(a);
        }
        return out;
    }

    public List<Apuesta> deJugador(UUID quien) {
        List<Apuesta> out = new ArrayList<>();
        for (Apuesta a : abiertas.values()) if (a.esDe(quien)) out.add(a);
        return out;
    }

    public int cuantasAbiertas() { return abiertas.size(); }

    // ----------------------------------------------------------------- crear

    public Resultado crear(Player creador, double cantidad, Player retado) {
        if (economia == null) {
            return Resultado.no(textos.de("no-listo", "&#FF5C5CLas apuestas todavia estan arrancando."));
        }
        if (retado != null && !retosActivos) {
            return Resultado.no(textos.de("retos-apagados", "&#FF5C5CLos retos estan desactivados."));
        }
        if (retado != null && retado.getUniqueId().equals(creador.getUniqueId())) {
            return Resultado.no(textos.de("contra-ti", "&#FF5C5CNo puedes apostar contra ti mismo."));
        }
        if (Double.isNaN(cantidad) || Double.isInfinite(cantidad)) {
            return Resultado.no(textos.de("cantidad-mala", "&#FF5C5CEso no es una cantidad."));
        }
        /* Se redondea a dos decimales ANTES de comprobar nada: si no, un
         * 0.005 se cuela por el minimo y luego se cobra otra cosa. */
        cantidad = Math.round(cantidad * 100.0) / 100.0;
        if (cantidad < minima) {
            return Resultado.no(textos.de("minima", "&#FF5C5CLa apuesta minima es %minima%.",
                    "%minima%", Estilo.dinero(minima)));
        }
        if (cantidad > maxima) {
            return Resultado.no(textos.de("maxima", "&#FF5C5CLa apuesta maxima es %maxima%.",
                    "%maxima%", Estilo.dinero(maxima)));
        }
        if (deJugador(creador.getUniqueId()).size() >= maximoAbiertas) {
            return Resultado.no(textos.de("demasiadas", "&#FF5C5CYa tienes %cuantas% apuestas abiertas.",
                    "%cuantas%", String.valueOf(maximoAbiertas)));
        }
        long espera = esperaRestante(creador.getUniqueId());
        if (espera > 0) {
            return Resultado.no(textos.de("espera", "&#FF5C5CEspera %segundos%s.",
                    "%segundos%", String.valueOf((espera + 999) / 1000)));
        }
        if (!economia.has(creador, cantidad)) {
            return Resultado.no(textos.de("sin-dinero", "&#FF5C5CTe faltan %falta%.",
                    "%falta%", Estilo.dinero(cantidad - economia.getBalance(creador))));
        }

        /* El dinero sale AHORA. La apuesta no existe hasta que el banco dice
         * que si: al reves se podria poner una apuesta sin fondos. */
        EconomyResponse r = economia.withdrawPlayer(creador, cantidad);
        if (!r.transactionSuccess()) {
            return Resultado.no(textos.de("banco-fallo", "&#FF5C5CEl banco rechazo la operacion&8: &7%motivo%",
                    "%motivo%", String.valueOf(r.errorMessage)));
        }

        Apuesta a = new Apuesta(siguienteId++, creador.getUniqueId(), creador.getName(),
                retado == null ? null : retado.getUniqueId(),
                retado == null ? null : retado.getName(),
                cantidad, System.currentTimeMillis());
        abiertas.put(a.id(), a);
        ultimaVez.put(creador.getUniqueId(), System.currentTimeMillis());
        guardar();
        podarEsperas();
        registro.anotar("ABRE", a.nombreCreador(), a.id(), cantidad, 0,
                a.esReto() ? "reto a " + a.nombreRetado() : "mesa abierta");

        return new Resultado(true, textos.de(a.esReto() ? "creada-reto" : "creada",
                a.esReto() ? "&fRetaste a &x&D&7&F&3&F&F%rival% &fpor &#4FFF55%cantidad%"
                           : "&fApuesta de &#4FFF55%cantidad% &fpuesta. &7Numero %id%.",
                "%cantidad%", Estilo.dinero(cantidad),
                "%rival%", String.valueOf(a.nombreRetado()),
                "%id%", String.valueOf(a.id())));
    }

    /** La espera entre apuestas solo vale unos segundos: pasada, la entrada
     *  sobra. Sin esto el mapa se queda con una linea por jugador para siempre. */
    private void podarEsperas() {
        long limite = System.currentTimeMillis() - Math.max(1, esperaSegundos) * 1000L;
        ultimaVez.values().removeIf(t -> t < limite);
    }

    private long esperaRestante(UUID quien) {
        Long ultima = ultimaVez.get(quien);
        if (ultima == null || esperaSegundos <= 0) return 0;
        return Math.max(0, ultima + esperaSegundos * 1000L - System.currentTimeMillis());
    }

    // -------------------------------------------------------------- cancelar

    public Resultado cancelar(Player quien, Apuesta a) {
        if (a == null || !abiertas.containsKey(a.id())) {
            return Resultado.no(textos.de("no-esta", "&#FF5C5CEsa apuesta ya no esta en la mesa."));
        }
        if (!a.esDe(quien.getUniqueId()) && !quien.hasPermission("ederus.coinflip.admin")) {
            return Resultado.no(textos.de("no-es-tuya", "&#FF5C5CEsa apuesta no es tuya."));
        }
        if (a.tomada()) {
            return Resultado.no(textos.de("ya-tomada", "&#FF5C5CEsa apuesta ya la esta cogiendo alguien."));
        }
        if (!devolver(a, "CANCELA")) {
            return Resultado.no(textos.de("no-listo", "&#FF5C5CLas apuestas todavia estan arrancando."));
        }
        return new Resultado(true, textos.de("cancelada",
                "&fApuesta cancelada. Se te devolvieron &#4FFF55%cantidad%",
                "%cantidad%", Estilo.dinero(a.cantidad())));
    }

    /** Saca la apuesta de la mesa y le devuelve el dinero al creador.
     *  @return false si no se pudo y la apuesta sigue puesta. */
    private boolean devolver(Apuesta a, String motivo) {
        /* Sin banco no se saca de la mesa: quitarla aqui borraria una apuesta
         * cuyo dinero ya esta retirado y no habria por donde recuperarlo.
         * Se queda puesta y se avisa; al apagar sale en el fichero. */
        if (economia == null) {
            log.severe("SIN BANCO: no puedo devolver " + a.cantidad() + " a " + a.nombreCreador()
                    + " (apuesta " + a.id() + "); la apuesta se queda en la mesa.");
            return false;
        }
        abiertas.remove(a.id());
        guardar();
        OfflinePlayer duenio = Bukkit.getOfflinePlayer(a.creador());
        EconomyResponse r = economia.depositPlayer(duenio, a.cantidad());
        if (!r.transactionSuccess()) {
            /* Esto es dinero de un jugador que se ha quedado en el aire: tiene
             * que salir en la consola si o si, no solo en el registro. */
            log.severe("NO PUDE DEVOLVER " + a.cantidad() + " a " + a.nombreCreador()
                    + " (apuesta " + a.id() + "): " + r.errorMessage);
        }
        registro.anotar(motivo, a.nombreCreador(), a.id(), a.cantidad(), 0, "devuelto");
        return true;
    }

    /** Las que se pasaron de tiempo. Se llama desde la tarea de mantenimiento. */
    public int caducar() {
        podarEsperas();
        if (caducidadMinutos <= 0) return 0;
        long tope = caducidadMinutos * 60_000L;
        List<Apuesta> viejas = new ArrayList<>();
        for (Apuesta a : abiertas.values()) if (!a.tomada() && a.edadMs() > tope) viejas.add(a);
        int n = 0;
        for (Apuesta a : viejas) {
            if (!devolver(a, "CADUCA")) continue;
            n++;
            Player p = Bukkit.getPlayer(a.creador());
            if (p != null) {
                textos.manda(p, "caducada", "&7Tu apuesta de %cantidad% caduco y se te devolvio.",
                        "%cantidad%", Estilo.dinero(a.cantidad()));
            }
        }
        return n;
    }

    /** Al salir del servidor se le devuelven las suyas: una mesa cuyo dueño no
     *  esta no se puede jugar, y dejarla ahi solo confunde. */
    public int cancelarDe(UUID quien) {
        int n = 0;
        /* Cuenta las que de verdad se devuelven, no las que tenia: una que
         * estuviera resolviendose no se toca, y decir que se devolvieron 3
         * cuando fueron 2 es la clase de linea que luego nadie sabe leer. */
        for (Apuesta a : deJugador(quien)) {
            if (a.tomada()) continue;
            if (devolver(a, "SALE")) n++;
        }
        return n;
    }

    public int devolverTodo(String motivo) {
        int n = 0;
        for (Apuesta a : todas()) { if (devolver(a, motivo)) n++; }
        return n;
    }

    // --------------------------------------------------------------- aceptar

    /**
     * Coge la apuesta, sortea y PAGA. La animacion viene despues y solo enseña
     * lo que ya paso: si se animara primero y se pagara al final, un reinicio a
     * mitad de la moneda dejaria el bote sin dueño.
     */
    public Jugada aceptar(Player quien, Apuesta a) {
        if (economia == null) {
            return Jugada.no(textos.de("no-listo", "&#FF5C5CLas apuestas todavia estan arrancando."));
        }
        if (a == null || !abiertas.containsKey(a.id())) {
            return Jugada.no(textos.de("no-esta", "&#FF5C5CEsa apuesta ya no esta en la mesa."));
        }
        if (a.esDe(quien.getUniqueId())) {
            return Jugada.no(textos.de("contra-ti", "&#FF5C5CNo puedes apostar contra ti mismo."));
        }
        if (a.esReto() && !a.esPara(quien.getUniqueId())) {
            return Jugada.no(textos.de("no-es-tu-reto", "&#FF5C5CEse reto no es para ti."));
        }
        Player creador = Bukkit.getPlayer(a.creador());
        if (creador == null || !creador.isOnline()) {
            return Jugada.no(textos.de("rival-fuera", "&#FF5C5C%rival% ya no esta conectado.",
                    "%rival%", a.nombreCreador()));
        }
        if (!economia.has(quien, a.cantidad())) {
            return Jugada.no(textos.de("sin-dinero", "&#FF5C5CTe faltan %falta%.",
                    "%falta%", Estilo.dinero(a.cantidad() - economia.getBalance(quien))));
        }

        /* Se reserva ANTES de tocar el banco: dos clics en el mismo tick sobre
         * la misma mesa la aceptarian dos veces. */
        if (!a.tomar()) {
            return Jugada.no(textos.de("ya-tomada", "&#FF5C5CEsa apuesta ya la esta cogiendo alguien."));
        }

        EconomyResponse cobro = economia.withdrawPlayer(quien, a.cantidad());
        if (!cobro.transactionSuccess()) {
            a.soltar();
            return Jugada.no(textos.de("banco-fallo", "&#FF5C5CEl banco rechazo la operacion&8: &7%motivo%",
                    "%motivo%", String.valueOf(cobro.errorMessage)));
        }
        abiertas.remove(a.id());
        guardar();

        boolean ganaCreador = Sorteo.caraOCruz();
        UUID ganador = ganaCreador ? a.creador() : quien.getUniqueId();
        UUID perdedor = ganaCreador ? quien.getUniqueId() : a.creador();
        String nombreGanador = ganaCreador ? a.nombreCreador() : quien.getName();
        String nombrePerdedor = ganaCreador ? quien.getName() : a.nombreCreador();

        double bote = a.cantidad() * 2;
        double comision = redondear(bote * comisionPorCiento / 100.0);
        double premio = redondear(bote - comision);

        EconomyResponse pago = economia.depositPlayer(Bukkit.getOfflinePlayer(ganador), premio);
        if (!pago.transactionSuccess()) {
            /* Se deshace entero: cada uno recupera lo suyo. Nadie gana, pero
             * nadie pierde, que es lo unico inaceptable. */
            economia.depositPlayer(creador, a.cantidad());
            economia.depositPlayer(quien, a.cantidad());
            log.severe("Pago fallido en la apuesta " + a.id() + " (" + pago.errorMessage
                    + "); se devolvio a " + a.nombreCreador() + " y a " + quien.getName());
            registro.anotar("ROTA", nombreGanador, a.id(), a.cantidad(), 0, "pago fallido, devuelto");
            return Jugada.no(textos.de("pago-fallo",
                    "&#FF5C5CNo se pudo pagar el bote. Se os devolvio a los dos."));
        }

        registro.anotar("GANA", nombreGanador, a.id(), a.cantidad(), premio,
                "contra " + nombrePerdedor + ", comision " + Estilo.dinero(comision));
        return new Jugada(true, Component.empty(), a, ganador, nombreGanador,
                perdedor, nombrePerdedor, bote, comision, premio);
    }

    private static double redondear(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    /**
     * Lee la cantidad como la escribe la gente: 50000, 50.000, 50k, 1.5m.
     *
     * Existe porque en un servidor donde se manejan millones nadie escribe los
     * ceros uno a uno, y un "1.000.000" interpretado como 1.0 seria un desastre
     * silencioso: la apuesta saldria, solo que de un euro.
     *
     * Devuelve NaN si no hay manera de leerlo. El punto y la coma cuentan como
     * separador de miles salvo que sean el ultimo y les sigan una o dos cifras,
     * que es como se escriben los decimales en los dos formatos.
     */
    public static double leerCantidad(String texto) {
        if (texto == null) return Double.NaN;
        String t = texto.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        if (t.isEmpty()) return Double.NaN;

        double multiplicador = 1;
        char ultima = t.charAt(t.length() - 1);
        if (ultima == 'k' || ultima == 'm' || ultima == 'b') {
            multiplicador = switch (ultima) {
                case 'k' -> 1_000d;
                case 'm' -> 1_000_000d;
                default -> 1_000_000_000d;
            };
            t = t.substring(0, t.length() - 1);
            if (t.isEmpty()) return Double.NaN;
        }

        int corte = Math.max(t.lastIndexOf('.'), t.lastIndexOf(','));
        String decimales = "";
        if (corte >= 0) {
            String cola = t.substring(corte + 1);
            if (cola.length() >= 1 && cola.length() <= 2 && cola.chars().allMatch(Character::isDigit)) {
                decimales = cola;
                t = t.substring(0, corte);
            }
        }
        String entero = t.replace(".", "").replace(",", "");
        if (entero.isEmpty() || !entero.chars().allMatch(Character::isDigit)) return Double.NaN;
        try {
            double v = Double.parseDouble(decimales.isEmpty() ? entero : entero + "." + decimales);
            return v * multiplicador;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // ------------------------------------------------------------ en disco

    /**
     * Guarda las abiertas. Se llama en CADA cambio de la mesa, no solo al
     * apagar: el fichero es la unica copia del dinero que ya esta retirado, y
     * si solo se escribiera al apagar quedaria siempre vacio (al apagar se
     * devuelve todo antes de guardar) y una caida a lo bruto se llevaria por
     * delante lo apostado.
     */
    public void guardar() {
        YamlConfiguration yml = new YamlConfiguration();
        List<Map<String, Object>> lista = new ArrayList<>();
        for (Apuesta a : abiertas.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("creador", a.creador().toString());
            m.put("nombre", a.nombreCreador());
            m.put("cantidad", a.cantidad());
            if (a.esReto()) {
                m.put("retado", a.retado().toString());
                m.put("nombre-retado", a.nombreRetado());
            }
            lista.add(m);
        }
        yml.set("abiertas", lista);
        try {
            yml.save(fichero);
        } catch (IOException e) {
            log.severe("No pude guardar las apuestas abiertas: " + e.getMessage());
        }
    }

    /**
     * Devuelve lo que hubiera quedado de un arranque anterior.
     *
     * Las apuestas NO sobreviven a un reinicio a proposito: el jugador que la
     * puso ya no esta delante y una mesa fantasma de hace tres dias solo genera
     * discusiones. Lo que si sobrevive es su dinero.
     */
    public int devolverLoQueQuedo() {
        if (!fichero.exists() || economia == null) return 0;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichero);
        List<?> lista = yml.getList("abiertas");
        int n = 0;
        if (lista != null) {
            for (Object o : lista) {
                if (!(o instanceof Map<?, ?> m)) continue;
                try {
                    UUID duenio = UUID.fromString(String.valueOf(m.get("creador")));
                    double cantidad = Double.parseDouble(String.valueOf(m.get("cantidad")));
                    String nombre = String.valueOf(m.get("nombre"));
                    if (cantidad <= 0) continue;
                    EconomyResponse r = economia.depositPlayer(Bukkit.getOfflinePlayer(duenio), cantidad);
                    if (r.transactionSuccess()) {
                        registro.anotar("REPONE", nombre, -1, cantidad, 0, "apuesta abierta al reiniciar");
                        n++;
                    } else {
                        log.severe("No pude devolver " + cantidad + " a " + nombre + ": " + r.errorMessage);
                    }
                } catch (RuntimeException e) {
                    log.warning("Apuesta guardada ilegible: " + e.getMessage());
                }
            }
        }
        /* Se vacia SIEMPRE, aunque algo fallara: dejarlo lleno devolveria otra
         * vez lo mismo en el siguiente arranque, y eso si que imprime dinero. */
        yml.set("abiertas", new ArrayList<>());
        try {
            yml.save(fichero);
        } catch (IOException e) {
            log.severe("No pude vaciar el fichero de apuestas: " + e.getMessage());
        }
        return n;
    }
}
