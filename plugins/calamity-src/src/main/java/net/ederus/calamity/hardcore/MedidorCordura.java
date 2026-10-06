package net.ederus.calamity.hardcore;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Calamity 1.11 · La cordura en una BossBar, arriba de la pantalla (hardcore.cordura.pantalla: bossbar).
 *
 * Por que: la barra de accion la dibuja el cliente a una altura fija y, con corazones de absorcion
 * (manzana encantada, sets con vida extra), las filas de vida y armadura suben y la tapan. Desde el
 * servidor no se puede mover. La BossBar va arriba y no la tapa nada.
 *
 * Una barra por jugador, creada la primera vez y reutilizada: cada segundo solo se le cambia lo que
 * cambio (el progreso va por puntos enteros, asi que con el drenaje normal se toca una vez por minuto).
 * Es un objeto propio de Calamity: las BossBars de la PARCA, de Ambush o de otros plugins son otras y
 * el cliente las apila; esta no toca ninguna.
 *
 * Estilo NOTCHED_10: diez muescas, una cada 10 de cordura. Con el drenaje de serie (1 por minuto) cada
 * muesca son unos diez minutos de expedicion, y se lee de un vistazo cuanto queda sin mirar el numero.
 * Con 20 muescas (las veinte casillas de la barra de antes) quedaba demasiado cargada a ese tamano.
 *
 * Color por tramo, con los umbrales de Cordura.tramo: verde de 50 para arriba, amarillo de 25 a 50 y
 * rojo por debajo de 25. El titulo va sin negrita: "Cordura" en el color normal, la cifra en el del
 * tramo y, si hay contrato (Cordura.extra), "   ·   Mobs 6/10" en gris.
 *
 * Calamity 1.16.0 · Con un Farol de Tranquilidad encendido, entre la cifra y el contrato: "   ·   Tranquilidad 3:42",
 * en el jade del farol. La cifra de la cordura sigue siendo la de verdad (no se mueve mientras arde).
 */
final class MedidorCordura {

    static final BossBar.Overlay ESTILO = BossBar.Overlay.NOTCHED_10;

    static final TextColor VERDE = TextColor.color(0x7BD87B);
    static final TextColor AMARILLO = TextColor.color(0xE8D45C);
    static final TextColor ROJO = Paleta.AVISO;

    /** La barra de un jugador y lo ultimo que se le puso, para no tocarla si no cambia. */
    private static final class Pantalla {
        final BossBar barra;
        int puntos;
        Component titulo;

        Pantalla(BossBar barra, int puntos, Component titulo) {
            this.barra = barra;
            this.puntos = puntos;
            this.titulo = titulo;
        }
    }

    private final Map<UUID, Pantalla> pantallas = new HashMap<>();

    /** La ensena (la primera vez) o la pone al dia. Extra: lo que va detras (un contrato), o null. */
    void mostrar(Player p, double valor, Component extra) {
        mostrar(p, valor, -1, extra);
    }

    /** Lo mismo con los segundos del Farol de Tranquilidad que arde (-1 = ninguno). */
    void mostrar(Player p, double valor, int calma, Component extra) {
        int puntos = puntos(valor);
        Component titulo = titulo(puntos, calma, extra);
        Pantalla s = pantallas.get(p.getUniqueId());
        if (s == null) {
            BossBar b = BossBar.bossBar(titulo, progreso(puntos), color(puntos), ESTILO);
            pantallas.put(p.getUniqueId(), new Pantalla(b, puntos, titulo));
            p.showBossBar(b);
            return;
        }
        if (s.puntos != puntos) {
            BossBar.Color c = color(puntos);
            s.barra.progress(progreso(puntos));
            if (s.barra.color() != c) s.barra.color(c);
            s.puntos = puntos;
        }
        if (!titulo.equals(s.titulo)) {
            s.barra.name(titulo);
            s.titulo = titulo;
        }
    }

    /** Se la quita (si la tenia). */
    void ocultar(Player p) {
        if (p == null) return;
        Pantalla s = pantallas.remove(p.getUniqueId());
        if (s != null) p.hideBossBar(s.barra);
    }

    /** Quien no esta en 'vistos' (salio del mundo, murio y reaparecio fuera, paso a espectador) la pierde. */
    void podar(Set<UUID> vistos) {
        if (pantallas.isEmpty()) return;
        for (UUID u : new ArrayList<>(pantallas.keySet())) {
            if (vistos.contains(u)) continue;
            quitar(u);
        }
    }

    /** Todas fuera (al parar el plugin o al pasar a la barra de accion). */
    void parar() {
        for (UUID u : new ArrayList<>(pantallas.keySet())) quitar(u);
        pantallas.clear();
    }

    boolean tiene(UUID u) {
        return pantallas.containsKey(u);
    }

    private void quitar(UUID u) {
        Pantalla s = pantallas.remove(u);
        if (s == null) return;
        Player p = Bukkit.getPlayer(u);
        if (p != null) p.hideBossBar(s.barra);
    }

    // ------------------------------------------------------------------ el nucleo

    /** "bossbar" (o cualquier otra cosa, o nada) = BossBar; solo "actionbar" vuelve a la de la 1.10. */
    static boolean enBossBar(String modo) {
        return modo == null || !modo.trim().equalsIgnoreCase("actionbar");
    }

    /** La cordura en puntos enteros de 0 a 100: lo que se ve y lo que decide si hay que tocar la barra. */
    static int puntos(double valor) {
        return (int) Math.max(0, Math.min(Cordura.MAXIMO, Math.round(valor)));
    }

    static float progreso(int puntos) {
        return Math.max(0f, Math.min(1f, (float) (puntos / Cordura.MAXIMO)));
    }

    /** Los tramos de Cordura.tramo: 3 y 4 verde, 2 amarillo, 1 y 0 rojo. */
    static BossBar.Color color(int puntos) {
        int t = Cordura.tramo(puntos);
        if (t >= 3) return BossBar.Color.GREEN;
        if (t == 2) return BossBar.Color.YELLOW;
        return BossBar.Color.RED;
    }

    static TextColor acento(int puntos) {
        return switch (color(puntos)) {
            case GREEN -> VERDE;
            case YELLOW -> AMARILLO;
            default -> ROJO;
        };
    }

    /** "Cordura 74%" y, si hay contrato, "   ·   Mobs 6/10" en gris (el texto, sin los colores de la barra de accion). */
    static Component titulo(int puntos, Component extra) {
        return titulo(puntos, -1, extra);
    }

    /**
     * Calamity 1.16.0 · Lo mismo con el Farol de Tranquilidad: calma = los segundos que le quedan (-1 = no arde) y va
     * entre la cifra y el contrato.
     */
    static Component titulo(int puntos, int calma, Component extra) {
        Component t = Component.text("Cordura ", Paleta.TEXTO)
                .append(Component.text(puntos + "%", acento(puntos)));
        if (calma >= 0) t = t.append(Component.text("   ·   ", Paleta.TENUE)).append(calma(calma));
        String mas = extra == null ? "" : PlainTextComponentSerializer.plainText().serialize(extra);
        if (mas.isBlank()) return t;
        String limpio = mas.strip();
        if (limpio.startsWith("·")) limpio = limpio.substring(1).strip();
        if (limpio.isEmpty()) return t;
        return t.append(Component.text("   ·   " + limpio, Paleta.TENUE));
    }

    /** "Tranquilidad 3:42", en el jade del farol (el tono medio de su familia: no compite con la cifra). */
    static Component calma(int segundos) {
        return Component.text(Faroles.BARRA + " " + Faroles.reloj(segundos), Ficha.tono("farol").medio());
    }

    // ------------------------------------------------------------------ autotest

    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        PlainTextComponentSerializer plano = PlainTextComponentSerializer.plainText();

        h.ok("de serie: bossbar", enBossBar(null) && enBossBar("bossbar") && enBossBar(" BossBar "));
        h.ok("actionbar: la barra de la 1.10", !enBossBar("actionbar") && !enBossBar("ActionBar"));
        h.ok("un valor raro no apaga la bossbar", enBossBar("otra-cosa"));

        h.igual("puntos: redondeo", 74, puntos(73.6));
        h.igual("puntos: tope arriba", 100, puntos(140));
        h.igual("puntos: tope abajo", 0, puntos(-3));
        h.cerca("progreso 74", 0.74, progreso(74), 1e-6);
        h.cerca("progreso 0", 0.0, progreso(0), 1e-9);
        h.cerca("progreso 100", 1.0, progreso(100), 1e-9);
        h.igual("drenaje de un segundo (1/60): mismos puntos, la barra no se toca", puntos(73.98), puntos(73.98 - 1.0 / 60));

        h.igual("100: verde", BossBar.Color.GREEN, color(100));
        h.igual("50: verde (mismo umbral que tramo)", BossBar.Color.GREEN, color(50));
        h.igual("49: amarillo", BossBar.Color.YELLOW, color(49));
        h.igual("25: amarillo", BossBar.Color.YELLOW, color(25));
        h.igual("24: rojo", BossBar.Color.RED, color(24));
        h.igual("0: rojo", BossBar.Color.RED, color(0));
        h.igual("acento del verde", VERDE, acento(80));
        h.igual("acento del rojo", ROJO, acento(10));

        h.igual("titulo sin contrato", "Cordura 74%", plano.serialize(titulo(74, null)));
        Component contrato = Component.text("   ·   ", Paleta.SEPARADOR).append(Component.text("Mobs ", Paleta.DETALLE))
                .append(Component.text("6/10", Paleta.CIFRA));
        h.igual("titulo con contrato", "Cordura 74%   ·   Mobs 6/10", plano.serialize(titulo(74, contrato)));
        h.igual("contrato sin separador: se le pone", "Cordura 74%   ·   Mobs 6/10",
                plano.serialize(titulo(74, Component.text("Mobs 6/10"))));
        h.igual("contrato vacio: como sin contrato", "Cordura 74%", plano.serialize(titulo(74, Component.empty())));
        Component t = titulo(30, contrato);
        h.ok("el titulo no lleva negrita", !t.hasDecoration(net.kyori.adventure.text.format.TextDecoration.BOLD)
                && t.children().stream().noneMatch(c -> c.hasDecoration(net.kyori.adventure.text.format.TextDecoration.BOLD)));
        h.igual("tres colores como mucho: normal, acento y gris", 3, (int) java.util.stream.Stream.concat(
                java.util.stream.Stream.of(t), t.children().stream()).map(Component::color).filter(java.util.Objects::nonNull)
                .distinct().count());
        h.igual("la cifra va en el color del tramo", AMARILLO, t.children().get(0).color());
        // 1.16.0: el Farol de Tranquilidad, entre la cifra y el contrato.
        h.igual("con farol", "Cordura 20%   ·   Tranquilidad 3:42", plano.serialize(titulo(20, 222, null)));
        h.igual("con farol y contrato", "Cordura 20%   ·   Tranquilidad 0:05   ·   Mobs 6/10",
                plano.serialize(titulo(20, 5, contrato)));
        h.igual("sin farol (-1): como siempre", "Cordura 20%", plano.serialize(titulo(20, -1, null)));
        h.igual("el farol en su jade", Ficha.tono("farol").medio(), calma(60).color());
        h.ok("mismos datos, mismo titulo (no se reenvia)", titulo(74, contrato).equals(titulo(74, contrato)));
        h.ok("estilo con muescas", ESTILO == BossBar.Overlay.NOTCHED_10);
        return h.lineas();
    }
}
