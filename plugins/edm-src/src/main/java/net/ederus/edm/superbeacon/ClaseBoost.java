package net.ederus.edm.superbeacon;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import net.ederus.edm.boost.BoostApi;
import net.ederus.edm.boost.BoostPlugin;
import net.ederus.edm.boost.Tipo;

/**
 * boost: un multiplicador de area para los boosts del modulo boost (exp, skill_exp,
 * pesca, mobcoins). Minions se deja fuera en esta version: multiplica objetos, y un
 * multiplicador que va pegado a un SITIO es justo el que se puede explotar con recolectores.
 *
 * No multiplica nada por su cuenta. Se apunta como fuente de zona en {@link BoostApi} y el
 * modulo boost hace lo de siempre: el MAYOR entre el personal, el global y la zona,
 * recortado al tope de cada tipo. Nunca el producto: un Super Beacon de MobCoins x1,5 y un
 * boost comprado de x2 dan x2, no x3.
 *
 * Aqui solo se guarda lo que da cada zona a cada jugador, en un mapa concurrente: los
 * placeholders y algun plugin preguntan fuera del hilo principal.
 *
 * Si el modulo boost esta apagado (modulos.boost: false), estos efectos salen en el menu
 * como no disponibles y no se aplican; el modulo avisa una vez en la consola al arrancar.
 */
final class ClaseBoost extends ClaseEfecto {

    static final String FUENTE = "superbeacon";

    static final class Multi extends Efecto {
        private final ClaseBoost clase;
        final Tipo tipo;
        final double multiplicador;

        Multi(ClaseBoost clase, String clave, String nombre, Material icono, Tipo tipo, double multiplicador) {
            super(clave, nombre, icono);
            this.clase = clase;
            this.tipo = tipo;
            this.multiplicador = multiplicador;
        }

        @Override
        ClaseEfecto clase() {
            return clase;
        }

        @Override
        String grupo() {
            return "boost:" + tipo.id();
        }

        @Override
        double fuerza() {
            return multiplicador;
        }
    }

    /** jugador -> lo que le dan las zonas. Cada valor es un mapa que no se toca: se sustituye. */
    private final Map<UUID, Map<Tipo, Double>> zonas = new ConcurrentHashMap<>();

    ClaseBoost(SuperBeaconPlugin plugin) {
        super(plugin, "boost");
    }

    @Override
    Efecto leer(String clave, String nombre, Material icono, ConfigurationSection s, Consumer<String> error) {
        String texto = s.getString("boost", "").trim();
        Tipo tipo = Tipo.de(texto);
        if (tipo == null) {
            error.accept("boost: '" + texto + "' no es un boost (exp, skill_exp, pesca, mobcoins); se salta");
            return null;
        }
        if (tipo == Tipo.MINIONS) {
            error.accept("boost: minions no va en los Super Beacon (multiplica objetos); se salta");
            return null;
        }
        double mult = s.getDouble("multiplicador", 0);
        if (!(mult > 1.0) || Double.isInfinite(mult)) {
            error.accept("multiplicador: tiene que ser mayor que 1; se salta");
            return null;
        }
        return new Multi(this, clave, nombre, icono, tipo, mult);
    }

    @Override
    Material icono() {
        return Material.EXPERIENCE_BOTTLE;
    }

    @Override
    boolean porJugador() {
        return true;
    }

    @Override
    String falta(Efecto e) {
        Tipo tipo = ((Multi) e).tipo;
        BoostPlugin boost = plugin.boost();
        if (boost == null) return plugin.textos().crudo("falta-boosts", "boosts apagados");
        if (!boost.habilitado(tipo)) return plugin.textos().crudo("falta-boost-desactivado", "desactivado en boost/config.yml");
        if (!boost.disponible(tipo)) {
            return plugin.textos().crudo("falta-plugin", "falta %plugin%").replace("%plugin%", boost.faltaPara(tipo));
        }
        return null;
    }

    @Override
    List<String> detalle(Efecto e) {
        Multi m = (Multi) e;
        return List.of(
                plugin.textos().crudo("detalle-boost", "&#8A8A8A%boost% x%multiplicador% en su alcance.")
                        .replace("%boost%", m.tipo.nombre())
                        .replace("%multiplicador%", Numeros.decimal(m.multiplicador)),
                plugin.textos().crudo("detalle-boost-mayor", "&#8A8A8ANo se suma con otros boosts: se aplica el mayor."));
    }

    @Override
    void arrancar() {
        BoostApi.registrarZona(FUENTE, this::en);
    }

    /** Lo que pregunta el modulo boost: lo que la zona da a ese jugador para ese tipo. */
    double en(Player p, Tipo tipo) {
        Map<Tipo, Double> suyos = zonas.get(p.getUniqueId());
        if (suyos == null) return 1.0;
        Double v = suyos.get(tipo);
        return v == null ? 1.0 : v;
    }

    @Override
    void aplicar(Player p, Efecto e) {
        Multi m = (Multi) e;
        Map<Tipo, Double> antes = zonas.get(p.getUniqueId());
        if (antes != null && Double.valueOf(m.multiplicador).equals(antes.get(m.tipo))) return;
        Map<Tipo, Double> nuevo = antes == null ? new EnumMap<>(Tipo.class) : new EnumMap<>(antes);
        nuevo.put(m.tipo, m.multiplicador);
        zonas.put(p.getUniqueId(), java.util.Collections.unmodifiableMap(nuevo));
    }

    @Override
    void quitar(Player p, Efecto e) {
        Multi m = (Multi) e;
        Map<Tipo, Double> antes = zonas.get(p.getUniqueId());
        if (antes == null || !antes.containsKey(m.tipo)) return;
        if (antes.size() == 1) {
            zonas.remove(p.getUniqueId());
            return;
        }
        Map<Tipo, Double> nuevo = new EnumMap<>(antes);
        nuevo.remove(m.tipo);
        zonas.put(p.getUniqueId(), java.util.Collections.unmodifiableMap(nuevo));
    }

    @Override
    void alSalir(Player p) {
        zonas.remove(p.getUniqueId());
    }

    @Override
    void parar() {
        BoostApi.quitarZona(FUENTE);
        zonas.clear();
    }
}
