package net.ederus.lethalworld.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * La cordura de cada jugador dentro de Calamity, y la barra que la ensena.
 *
 * Es el reloj del mundo: entras con 100 y baja sola. Por debajo de la mitad el mundo
 * se pone serio, y en 0 viene a buscarte algo. No es una barra de vida: no se pierde
 * peleando (salvo golpes gordos), se pierde ESTANDO, asi que la unica forma de estirar
 * una expedicion es el Frasco de Calma o salir por el portal.
 *
 * La barra vive en la barra de accion porque es el unico sitio que se ve siempre sin
 * tapar nada. Los avisos que quieran pasar por ahi (las MobCoins) se cuelan como
 * destello temporal en vez de pisarla: ver destello().
 */
public final class Cordura {

    /** Cuanta cordura cabe. No es configurable: la barra y los umbrales cuentan con 100. */
    public static final double MAXIMO = 100;

    private static final int CASILLAS = 20;

    /** Lo que sabemos de un jugador dentro del mundo. */
    public static final class Estado {
        double valor = MAXIMO;
        /** Momento (millis) hasta el que la barra ensena otra cosa. Ver destello(). */
        long destelloHasta;
        Component destello;
        /** Ultimo tramo anunciado, para no repetir el aviso cada segundo. */
        int ultimoTramo = 4;
        /** Cuando salio el ultimo minijefe de cordura cero. */
        long ultimoMinijefe;
        /** Segundos acumulados dentro del mundo, para la dificultad que sube con el tiempo. */
        int segundosDentro;
    }

    private final Map<UUID, Estado> estados = new HashMap<>();

    public Estado estado(Player p) {
        return estados.computeIfAbsent(p.getUniqueId(), k -> new Estado());
    }

    public boolean conoce(Player p) {
        return estados.containsKey(p.getUniqueId());
    }

    public double valor(Player p) {
        return estado(p).valor;
    }

    public void valor(Player p, double v) {
        estado(p).valor = Math.max(0, Math.min(MAXIMO, v));
    }

    /** Suma (o resta) y devuelve lo que queda. */
    public double sumar(Player p, double delta) {
        Estado e = estado(p);
        e.valor = Math.max(0, Math.min(MAXIMO, e.valor + delta));
        return e.valor;
    }

    /** Vuelve a empezar: al entrar, al salir y al morir. */
    public void reiniciar(Player p) {
        Estado e = estado(p);
        e.valor = MAXIMO;
        e.ultimoTramo = 4;
        e.ultimoMinijefe = 0;
        e.segundosDentro = 0;
    }

    public void olvidar(Player p) {
        estados.remove(p.getUniqueId());
    }

    public Map<UUID, Estado> todos() {
        return estados;
    }

    /**
     * Pone un mensaje en la barra durante unos segundos, en vez de la cordura.
     *
     * Lo usan las MobCoins y los avisos del mundo: si escribieran en la barra por su
     * cuenta, las dos escrituras se pelearian cada tick y parpadearia.
     */
    public void destello(Player p, Component texto, int segundos) {
        Estado e = estado(p);
        e.destello = texto;
        e.destelloHasta = System.currentTimeMillis() + segundos * 1000L;
    }

    /** El tramo en el que esta: 4 entero, 3 mermado, 2 en rojo, 1 al limite, 0 vacio. */
    public static int tramo(double valor) {
        if (valor <= 0) return 0;
        if (valor < 25) return 1;
        if (valor < 50) return 2;
        if (valor < 75) return 3;
        return 4;
    }

    public static TextColor color(double valor) {
        return switch (tramo(valor)) {
            case 4 -> TextColor.color(0x7BD87B);
            case 3 -> TextColor.color(0xE8D45C);
            case 2 -> TextColor.color(0xE8903C);
            case 1 -> TextColor.color(0xD64545);
            default -> TextColor.color(0x8B1A1A);
        };
    }

    /** Dibuja la barra de este jugador en su barra de accion. */
    public void pintar(Player p) {
        Estado e = estado(p);
        if (e.destello != null && System.currentTimeMillis() < e.destelloHasta) {
            p.sendActionBar(e.destello);
            return;
        }
        e.destello = null;
        p.sendActionBar(barra(e.valor));
    }

    /** La barra tal cual se ve: veinte casillas, el numero detras. */
    public static Component barra(double valor) {
        int llenas = (int) Math.round(valor / MAXIMO * CASILLAS);
        TextColor tinta = color(valor);
        StringBuilder llena = new StringBuilder();
        StringBuilder vacia = new StringBuilder();
        for (int i = 0; i < CASILLAS; i++) {
            if (i < llenas) llena.append('▮');
            else vacia.append('▯');
        }
        return Component.text("Cordura ", NamedTextColor.GRAY)
                .append(Component.text(llena.toString(), tinta))
                .append(Component.text(vacia.toString(), TextColor.color(0x3A3A3A)))
                .append(Component.text("  " + (int) Math.round(valor) + "%", tinta));
    }
}
