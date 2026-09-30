package net.ederus.edm.goditems.equipo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;

import net.ederus.edm.goditems.equipo.Calculo.AtributoTotal;
import net.ederus.edm.goditems.equipo.Calculo.Hueco;
import net.ederus.edm.goditems.equipo.Calculo.Resultado;
import net.ederus.edm.goditems.equipo.Definicion.Clave;
import net.ederus.edm.goditems.equipo.Definicion.Config;
import net.ederus.edm.goditems.equipo.Definicion.Conjunto;
import net.ederus.edm.goditems.equipo.Definicion.Efectos;
import net.ederus.edm.goditems.equipo.Definicion.Escalon;
import net.ederus.edm.goditems.equipo.Definicion.Pieza;
import net.ederus.edm.goditems.equipo.Definicion.Pocion;

/**
 * /gi equipo <jugador>: que lleva, que cuenta (y por que no), que suma cada
 * pieza, como va cada set y el total con sus topes. En texto con codigos &:
 * lo mismo sirve para la consola, para Java y para Bedrock, y es lo que
 * devuelve EquipoApi.informe a Calamity y a PremioPescao.
 */
public final class Informe {

    private Informe() { }

    public static List<String> lineas(Equipo equipo, Player p, String prefijo) {
        Config c = equipo.config();
        Redaccion red = equipo.redaccion();
        Resultado r = equipo.resultado(p);
        List<String> out = new ArrayList<>();
        out.add("&7Equipo de &f" + p.getName() + (prefijo.isEmpty() ? "" : " &8(" + prefijo + "*)")
                + " &8· &7" + equipo.resumen());
        List<Hueco> huecos = r.huecos().isEmpty() ? vacios() : r.huecos();
        for (Hueco h : huecos) out.add(linea(c, red, h, prefijo));
        if (r.carnada() != null) {
            String ef = efectos(c, red, r.carnada().efectos(), prefijo);
            out.add("  &7Carnada: &f" + r.carnada().nombre() + " &8(" + r.carnadaUsos() + " pescas) &8· "
                    + (ef.isEmpty() ? "&7sin efectos" : ef));
        } else {
            var marca = Calculo.carnadaDe(Calculo.canaEnUso(Calculo.equipoDe(p)));
            if (marca != null) out.add("  &7Carnada: &e'" + marca.getKey() + "' no está en equipo/ &8· no cuenta");
        }
        for (Conjunto s : c.sets()) {
            Integer n = r.piezasPorSet().get(s.id());
            if (n == null || n == 0) continue;
            int total = s.total() > 0 ? s.total()
                    : (s.setMmo() != null ? Math.max(equipo.tamanoMmo(s.setMmo()), n) : s.piezas().size());
            List<Escalon> act = r.activos().getOrDefault(s.id(), List.of());
            StringBuilder b = new StringBuilder("  &7Set &f" + s.nombre() + " &b" + n + "/" + total + " &8· ");
            if (act.isEmpty()) {
                b.append("&7sin escalón aún (el primero con ").append(s.minimo()).append(")");
            } else {
                List<String> ns = new ArrayList<>();
                for (Escalon e : act) ns.add(String.valueOf(e.necesita()));
                b.append("&7escalones activos: &f").append(String.join(", ", ns));
                String ef = efectos(c, red, sumaDe(act), prefijo);
                if (!ef.isEmpty()) b.append(" &8· ").append(ef);
            }
            out.add(b.toString());
        }
        boolean algo = false;
        for (Map.Entry<String, Double> e : r.bruto().entrySet()) {
            if (!e.getKey().startsWith(prefijo)) continue;
            if (!algo) out.add("  &7Total, con los topes:");
            algo = true;
            Clave k = c.claves().get(e.getKey());
            double v = r.de(e.getKey());
            String tope = k == null ? " &8(clave sin declarar: sin tope)"
                    : k.tope() == null ? "" : " &8(tope " + Redaccion.valor(k, k.tope())
                    + (Math.abs(e.getValue() - v) > 1e-9 ? ", sin tope sería " + Redaccion.valor(k, e.getValue()) : "") + ")";
            out.add("    &b" + Redaccion.efecto(c, e.getKey(), v) + " &8· " + e.getKey() + tope);
        }
        if (prefijo.isEmpty()) {
            for (Pocion x : r.pociones()) {
                if (!algo) out.add("  &7Total, con los topes:");
                algo = true;
                out.add("    &b" + red.nombrePocion(x.tipo()) + " " + Redaccion.romano(x.nivel() + 1) + " &8· poción");
            }
            for (AtributoTotal a : r.atributos()) {
                if (!algo) out.add("  &7Total, con los topes:");
                algo = true;
                out.add("    &b" + red.linea(new Definicion.Atributo(a.atributo(), a.clave(), a.valor(), a.operacion()))
                        .replaceAll("&[0-9a-fk-or]", "").replace("· ", "") + " &8· atributo");
            }
        }
        if (!algo) out.add("  &7Total: nada" + (prefijo.isEmpty() ? "." : " de " + prefijo + "*."));
        for (String a : c.avisos()) out.add("  &eequipo/: " + a);
        return out;
    }

    private static List<Hueco> vacios() {
        List<Hueco> out = new ArrayList<>();
        for (EquipmentSlot s : Calculo.HUECOS) out.add(new Hueco(s, null, null, null, false, null));
        return out;
    }

    private static String linea(Config c, Redaccion red, Hueco h, String prefijo) {
        String l = "  &7" + nombreHueco(h.slot()) + " ";
        if (h.item() == null) return l + "&8vacío";
        if (h.id() == null) return l + "&f" + h.item().getType().getKey().getKey() + " &8· " + h.porQueNo();
        l += "&f" + h.id();
        if (!h.cuenta()) return l + " &8· &eno cuenta: " + h.porQueNo();
        Pieza p = c.piezas().get(h.id());
        List<String> sets = new ArrayList<>();
        for (Conjunto s : c.sets()) if (s.tiene(h.id(), h.setMmo())) sets.add(s.nombre());
        if (p == null && sets.isEmpty()) return l + " &8· no está en equipo/";
        String ef = p == null ? "" : efectos(c, red, p.efectos(), prefijo);
        return l + " &8· " + (ef.isEmpty() ? "&7sin efectos propios" : ef)
                + (sets.isEmpty() ? "" : " &8· set " + String.join(", ", sets));
    }

    /** "+1,5% cajas, -8% peligro..." */
    private static String efectos(Config c, Redaccion red, Efectos ef, String prefijo) {
        List<String> partes = new ArrayList<>();
        for (Map.Entry<String, Double> e : ef.claves().entrySet()) {
            if (e.getKey().startsWith(prefijo)) partes.add("&b" + Redaccion.efecto(c, e.getKey(), e.getValue()));
        }
        if (prefijo.isEmpty()) {
            for (Pocion x : ef.pociones()) partes.add("&b" + red.nombrePocion(x.tipo()) + " " + Redaccion.romano(x.nivel() + 1));
            for (Definicion.Atributo a : ef.atributos()) {
                partes.add("&b" + red.linea(a).replaceAll("&[0-9a-fk-or]", "").replace("· ", "").trim());
            }
        }
        return String.join("&8, ", partes);
    }

    private static Efectos sumaDe(List<Escalon> es) {
        java.util.Map<String, Double> m = new java.util.LinkedHashMap<>();
        List<Pocion> ps = new ArrayList<>();
        List<Definicion.Atributo> as = new ArrayList<>();
        for (Escalon e : es) {
            e.efectos().claves().forEach((k, v) -> m.merge(k, v, Double::sum));
            ps.addAll(e.efectos().pociones());
            as.addAll(e.efectos().atributos());
        }
        return new Efectos(m, ps, as);
    }

    static String nombreHueco(EquipmentSlot s) {
        return switch (s) {
            case HEAD -> "Cabeza:";
            case CHEST -> "Pecho:";
            case LEGS -> "Piernas:";
            case FEET -> "Pies:";
            case HAND -> "Mano:";
            case OFF_HAND -> "Otra mano:";
            default -> s.name() + ":";
        };
    }
}
