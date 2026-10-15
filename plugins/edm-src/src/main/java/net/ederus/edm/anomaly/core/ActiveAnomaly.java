package net.ederus.edm.anomaly.core;

import net.ederus.edm.anomaly.boss.BossFight;
import net.ederus.edm.anomaly.boss.PhaseBars;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Una anomalia viva: donde esta, quien la pelea y cuanto lleva abierta. */
public final class ActiveAnomaly {

    /** En que punto del ciclo esta el evento. */
    public enum State {
        ABRIENDO, ACTIVA, CERRANDO
    }

    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final AnomalyType type;
    private final Location where;
    private final long startedAt = System.currentTimeMillis();

    /** Dano acumulado por jugador. Decide el botin y quien se lleva el "mejor". */
    private final Map<UUID, Double> damage = new LinkedHashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    private BossFight fight;
    private PhaseBars bars;
    private State state = State.ABRIENDO;

    public ActiveAnomaly(AnomalyType type, Location where) {
        this.type = type;
        this.where = where.clone();
    }

    public String id() {
        return id;
    }

    public AnomalyType type() {
        return type;
    }

    public String typeId() {
        return type.id();
    }

    public Location where() {
        return where.clone();
    }

    public long elapsedSeconds() {
        return (System.currentTimeMillis() - startedAt) / 1000L;
    }

    public State state() {
        return state;
    }

    public void state(State state) {
        this.state = state;
    }

    public BossFight fight() {
        return fight;
    }

    public void fight(BossFight fight) {
        this.fight = fight;
    }

    public PhaseBars bars() {
        return bars;
    }

    public void bars(PhaseBars bars) {
        this.bars = bars;
    }

    public Map<UUID, Double> damage() {
        return damage;
    }

    public void addDamage(Player p, double amount) {
        if (amount <= 0) return;
        damage.merge(p.getUniqueId(), amount, Double::sum);
        names.put(p.getUniqueId(), p.getName());
    }

    public String nameOf(UUID uuid) {
        return names.getOrDefault(uuid, "?");
    }

    public int participants() {
        return damage.size();
    }

    /*
     * Cuando le pego la anomalia por ultima vez a cada jugador (milisegundos). Lo
     * apunta BossFight#hit y lo lee el mensaje de muerte: los golpes sin entidad
     * (p.damage sin fuente, setHealth) no dejan rastro de quien fue, y sin esto el
     * chat decia que el jugador "murio" a secas.
     */
    private final Map<UUID, Long> lastHit = new HashMap<>();

    public void markHit(Player p) {
        if (p != null) lastHit.put(p.getUniqueId(), System.currentTimeMillis());
    }

    /** Si la anomalia le pego a ese jugador hace como mucho ms milisegundos. */
    public boolean hitWithin(Player p, long ms) {
        Long at = p == null ? null : lastHit.get(p.getUniqueId());
        return at != null && System.currentTimeMillis() - at <= ms;
    }

    /*
     * Lo mismo para lo que mata por setHealth (la Mirada de ROTTEN). Va aparte porque
     * esa muerte no deja causa nueva: el ultimo dano registrado puede ser el golpe de
     * otro mob de hace un rato, y sin esto se le atribuiria a el.
     */
    private final Map<UUID, Long> lastKill = new HashMap<>();

    public void markKill(Player p) {
        if (p != null) lastKill.put(p.getUniqueId(), System.currentTimeMillis());
        markHit(p);
    }

    public boolean killWithin(Player p, long ms) {
        Long at = p == null ? null : lastKill.get(p.getUniqueId());
        return at != null && System.currentTimeMillis() - at <= ms;
    }

}
