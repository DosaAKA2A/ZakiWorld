package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * La cordura de cada jugador dentro de Calamity, y la barra que la ensena.
 *
 * Es el reloj del mundo: entras con 100 y baja sola. Por debajo de la mitad el mundo
 * se pone serio, y en 0 viene a buscarte algo. No es una barra de vida: no se pierde
 * peleando (salvo golpes gordos), se pierde ESTANDO, asi que la unica forma de estirar
 * una expedicion es el Frasco de Calma o salir por el portal.
 *
 * Calamity 1.11: la barra va en una BossBar arriba de la pantalla (MedidorCordura), porque en la
 * barra de accion la tapaban las filas de vida y armadura en cuanto habia corazones de absorcion.
 * La barra de accion queda solo para los destellos cortos (destello(): las MobCoins, los avisos del
 * mundo). Con hardcore.cordura.pantalla: actionbar vuelve lo de la 1.10: la barra en la barra de
 * accion y los destellos encima mientras duran.
 *
 * 1.7.1: ni la barra ni los destellos escriben en la barra de accion por su cuenta; pasan
 * por BarraAccion, que respeta la reserva "ederus_actionbar" de otros plugins (la pesca).
 *
 * Calamity 1.16.0 · Con un Farol de Tranquilidad ardiendo en sus manos (Faroles) la cordura se queda QUIETA: sumar()
 * no la mueve en ningun sentido (ni el drenaje, ni los golpes, ni el Frasco). Y lo que reacciona a la cordura baja
 * (Locura, Alucinaciones, la vineta, los mobs y niveles de mas, el Frenesi, el minijefe de cordura cero) mira
 * sentida(), que con el farol encendido es la cordura entera. Al apagarse, valor() es el de antes y todo sigue igual.
 */
public final class Cordura {

    /** Cuanta cordura cabe. No es configurable: la barra y los umbrales cuentan con 100. */
    public static final double MAXIMO = 100;

    private static final int CASILLAS = 20;

    /** Lo que sabemos de un jugador dentro del mundo. */
    public static final class Estado {
        double valor = MAXIMO;
        /** Ultimo tramo anunciado, para no repetir el aviso cada segundo. */
        int ultimoTramo = 4;
        /** Cuando salio el ultimo minijefe de cordura cero. */
        long ultimoMinijefe;
        /** Segundos acumulados dentro del mundo, para la dificultad que sube con el tiempo. */
        int segundosDentro;
    }

    private final Map<UUID, Estado> estados = new HashMap<>();
    /** Por donde sale todo a la barra de accion (1.7.1). La pone Hardcore; null = no se pinta. */
    private BarraAccion salida;
    /** Quien no pierde cordura ahora mismo (1.2: el que esta en la zona spawn). Lo pone Hardcore. */
    private Predicate<Player> aSalvo = p -> false;
    /**
     * Calamity 1.10: lo que va a la derecha de la barra, en la misma linea (el contrato de Contratos:
     * "Mobs 6/10"). Null = nada. Lo pone Contratos; tiene que ser barato y no fallar: corre cada segundo.
     */
    private Function<Player, Component> extra = p -> null;
    /** Calamity 1.11: la BossBar de cada jugador. Se usa con pantalla = bossbar (la de serie). */
    private final MedidorCordura medidor = new MedidorCordura();
    /** Calamity 1.11: true = BossBar; false = la barra de accion de la 1.10. Lo pone Hardcore cada segundo. */
    private boolean enBossBar = true;
    /**
     * Calamity 1.16.0 · Los segundos que le quedan al Farol de Tranquilidad que arde en sus manos este segundo, o -1 si
     * no arde ninguno (Faroles.restante). Lo pone Hardcore; tiene que ser barato: lo mira cada sumar().
     */
    private ToIntFunction<Player> farol = p -> -1;

    /**
     * Calamity 1.2: a quien no se le resta cordura, venga de donde venga (drenaje, golpes, testigos,
     * la PARCA, pegarle a una alucinacion...). Lo que sube, sube: el Frasco sigue valiendo. Null = nadie.
     */
    void aSalvo(Predicate<Player> quien) {
        aSalvo = quien == null ? p -> false : quien;
    }

    /** Calamity 1.16.0 · De donde sale el Farol de Tranquilidad de cada uno (null = de nadie). */
    void farol(ToIntFunction<Player> f) {
        farol = f == null ? p -> -1 : f;
    }

    /** Calamity 1.16.0 · Si le arde un Farol de Tranquilidad: su cordura no se mueve y los efectos de la baja callan. */
    public boolean tranquilo(Player p) {
        return p != null && farol.applyAsInt(p) >= 0;
    }

    /**
     * Calamity 1.16.0 · La cordura que sienten los efectos de la cordura baja: la de verdad o, con un Farol de
     * Tranquilidad encendido, la entera (como si estuviera bien). La de verdad no cambia: la ensena la barra.
     */
    public double sentida(Player p) {
        return tranquilo(p) ? MAXIMO : valor(p);
    }

    void salida(BarraAccion barra) {
        salida = barra;
    }

    /** Calamity 1.10: lo que se pega a la derecha de la barra (null = nada). */
    void extra(Function<Player, Component> f) {
        extra = f == null ? p -> null : f;
    }

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

    /**
     * Suma (o resta) y devuelve lo que queda. En la zona spawn no resta (aSalvo). Con un Farol de Tranquilidad
     * encendido no hace nada, ni para abajo ni para arriba (1.16.0): la cordura se queda quieta.
     */
    public double sumar(Player p, double delta) {
        Estado e = estado(p);
        if (delta != 0 && tranquilo(p)) return e.valor;
        if (delta < 0 && aSalvo.test(p)) return e.valor;
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
        medidor.ocultar(p);
    }

    /**
     * Calamity 1.11: donde se pinta (hardcore.cordura.pantalla, lo lee Hardcore cada segundo). Al pasar
     * a la barra de accion se quitan todas las BossBars; al volver, el siguiente pintar() las crea.
     */
    void pantalla(boolean bossbar) {
        if (enBossBar && !bossbar) medidor.parar();
        enBossBar = bossbar;
    }

    boolean enBossBar() {
        return enBossBar;
    }

    /** Quita la BossBar a quien no este en 'vistos' (los que se han pintado este segundo). */
    void podarPantalla(java.util.Set<UUID> vistos) {
        medidor.podar(vistos);
    }

    /** Quita la BossBar a ese jugador (al cambiar a un mundo que no es hardcore). */
    void ocultarPantalla(Player p) {
        medidor.ocultar(p);
    }

    /** Quita todas las BossBars (al parar las reglas). */
    void pararPantalla() {
        medidor.parar();
    }

    public Map<UUID, Estado> todos() {
        return estados;
    }

    /**
     * Pone un mensaje corto en la barra de accion durante unos segundos (con la cordura en la barra
     * de accion, en vez de ella; con la BossBar, solo, y al acabar se borra).
     *
     * Lo usan las MobCoins y los avisos del mundo: si escribieran en la barra por su
     * cuenta, las escrituras se pelearian cada tick y parpadearia.
     *
     * 1.7.1: es un aviso puntual de BarraAccion. Reserva la barra mientras dura y, si otro
     * plugin la tiene reservada (la pesca), espera su turno hasta 5 s. Solo cuenta el ultimo.
     */
    public void destello(Player p, Component texto, int segundos) {
        if (salida == null) return;
        // Lo que llegue sin color sale en el normal de la Paleta: el gris de antes se perdia.
        salida.aviso(p, texto == null ? null : texto.colorIfAbsent(Paleta.TEXTO), segundos, true);
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
            // Vacia: rojo claro de aviso (el rojo de muerte oscuro no se leia en la barra).
            default -> Paleta.AVISO;
        };
    }

    /**
     * Pinta la cordura de este jugador; lo llama Hardcore una vez por segundo a quien esta dentro y cuenta.
     *
     * Con la BossBar (1.11, la de serie): la pone al dia (MedidorCordura solo toca lo que cambio) y en
     * la barra de accion solo se repinta el destello que este en pantalla, para que no se apague antes
     * de tiempo. Muerto (en la pantalla de muerte) no se le ensena.
     *
     * Con pantalla: actionbar, lo de la 1.10: la barra en su barra de accion (o el destello que toque).
     * Es fondo: si otro plugin tiene la barra reservada, este segundo no se pinta (BarraAccion). Lleva
     * detras, en la misma linea, lo que diga extra (un contrato): un solo envio, sin parpadeo.
     */
    public void pintar(Player p) {
        Estado e = estado(p);
        // 1.16.0: con un Farol de Tranquilidad, "Tranquilidad 3:42" detras de la cifra, en la misma linea.
        int calma = farol.applyAsInt(p);
        if (enBossBar) {
            if (p.isDead()) medidor.ocultar(p);
            else medidor.mostrar(p, e.valor, calma, extra.apply(p));
            if (salida != null) salida.repintar(p);
            return;
        }
        if (salida == null) return;
        Component c = barra(e.valor);
        if (calma >= 0) c = c.append(Component.text("  ·  ", Paleta.SEPARADOR)).append(MedidorCordura.calma(calma));
        Component mas = extra.apply(p);
        salida.fondo(p, mas == null ? c : c.append(mas));
    }

    /** La barra tal cual se ve en la barra de accion (pantalla: actionbar): veinte casillas, el numero detras. */
    public static Component barra(double valor) {
        int llenas = (int) Math.round(valor / MAXIMO * CASILLAS);
        TextColor tinta = color(valor);
        StringBuilder llena = new StringBuilder();
        StringBuilder vacia = new StringBuilder();
        for (int i = 0; i < CASILLAS; i++) {
            if (i < llenas) llena.append('▮');
            else vacia.append('▯');
        }
        return Component.text("Cordura ", Paleta.TENUE)
                .append(Component.text(llena.toString(), tinta))
                .append(Component.text(vacia.toString(), TextColor.color(0x3A3A3A)))
                .append(Component.text("  " + (int) Math.round(valor) + "%", tinta));
    }
}
