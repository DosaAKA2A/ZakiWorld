package net.ederus.lethalworld.hardcore;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;

/**
 * Una PARCA viva, sea cual sea su cuerpo (1.2.0). Desde que la PARCA es una anomalia DIOS
 * de EDM hay dos: ParcaAnomalia (la de EDM, la de las cuatro fases) y PeleaParca (la de
 * reserva, la de siempre, que sale cuando EDM no esta o ya hay otra anomalia abierta).
 *
 * El gestor (Parca) solo habla con esto: la marca, la cosecha, lo pendiente, el botin por la
 * Aduana y la exencion funcionan igual con las dos, y ningun AFK se libra porque EDM este
 * ocupado con otra anomalia.
 */
interface ParcaViva {

    enum Estado { APARECE, PELEA, ESPERA, COSECHA, FIN }

    Estado estado();

    /** Sin presa: /lw hardcore parca prueba, o abierta a mano desde /anomaly. No paga ni deja pendiente. */
    boolean prueba();

    /** Null en las de prueba. */
    UUID presa();

    String presaNombre();

    /** Presa y marcados extra (DIS sec. 1.4.2). El conjunto vivo: el gestor lo consulta, no lo toca. */
    Set<UUID> marcados();

    /** La entidad que pelea (el esqueleto invisible con la vida logica de Amenazas). */
    LivingEntity cuerpo();

    int nivel();

    int repeticiones();

    /** M: marcados extra. */
    int extra();

    int fase();

    int extrasGrupo();

    /** "anomalia" (EDM) o "reserva" (PeleaParca), para la bitacora y el info. */
    String tipo();

    /** Viva a efectos de los jugadores (persigue, cosecha, presencia). */
    boolean vivaParaJugadores();

    /** Admite marcados extra (no mientras cosecha, espera o se va). */
    boolean aceptaMarcados();

    boolean hayMarcadoEnMundo();

    /** Vida logica maxima actual (para porcentajes). */
    double vidaFinal();

    boolean esCuerpo(Entity e);

    /** El cuerpo que se ve (el maniqui con skin). */
    boolean esCascara(Entity e);

    boolean esPlanidera(Entity e);

    /** Lo que multiplica el dano que recibe (planideras, aturdida, campanada...). */
    double factorRecibido();

    /** Un jugador le ha pegado. */
    void golpeadaPor(Player j);

    /** Ella ha golpeado a alguien: el reloj de "sin golpear" del Paso Umbral y del atasco. */
    void haGolpeado();

    /** El esqueleto que golpea es invisible: el tajo lo da el cuerpo que se ve. */
    void blandir();

    /** Le han hecho dano de verdad: el cuerpo que se ve se estremece. */
    void dolor();

    /** /lw hardcore parca vida: que mire ya si le toca cambiar de fase. */
    void revisarFase();

    /** /lw hardcore parca habilidad: suelta esa habilidad ya. Devuelve por que no, o null. */
    String forzar(String nombre, Player quien);

    void agregarMarcado(Player p);

    void quitarMarcado(UUID id, String motivo);

    /** La presa se desconecta sin etiqueta: se queda quieta un rato. */
    void esperar();

    /** Vuelve la presa mientras ella esperaba. */
    void reanudar();

    /** La presa ha muerto: 3 s quieta y se va sin botin. */
    void cosecha(boolean porElla);

    /** Ha caido: el botin lo reparte el gestor. */
    void alMorir();

    /** Se va sin botin. */
    void irse(String motivo, Component aviso);

    /** Retira todo lo suyo (idempotente). */
    void limpiar();
}
