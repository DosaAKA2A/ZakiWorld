package net.ederus.edm.superbeacon;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import io.papermc.paper.persistence.PersistentDataContainerView;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.keys.tags.DamageTypeTagKeys;
import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.menu.MenuUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * El Super Beacon como objeto: escribir su {@link Ficha} en el PDC y leerla.
 *
 * El PDC es la verdad; el nombre y el lore se generan a partir de el y del tipo cada vez
 * que se crea el objeto (al entregarlo o al recogerlo), asi que un cambio de nombre en el
 * config llega a los objetos nuevos sin tocar los viejos ni romperlos.
 *
 * Un objeto del mismo material sin nuestro PDC no es nuestro: un faro vanilla sigue siendo
 * un faro vanilla. Las claves son superbeacon:id, :tipo, :dueno, :dueno_nombre, :clan,
 * :vence, :efectos, :semana (solo el trofeo) y :version; no se renombran nunca, o los
 * objetos ya repartidos dejarian de reconocerse.
 */
final class Objeto {

    static final int VERSION = 1;

    private final SuperBeaconPlugin plugin;
    private final NamespacedKey kId;
    private final NamespacedKey kTipo;
    private final NamespacedKey kDueno;
    private final NamespacedKey kDuenoNombre;
    private final NamespacedKey kClan;
    private final NamespacedKey kVence;
    private final NamespacedKey kEfectos;
    private final NamespacedKey kSemana;
    private final NamespacedKey kVersion;

    Objeto(SuperBeaconPlugin plugin) {
        this.plugin = plugin;
        this.kId = new NamespacedKey(plugin, "id");
        this.kTipo = new NamespacedKey(plugin, "tipo");
        this.kDueno = new NamespacedKey(plugin, "dueno");
        this.kDuenoNombre = new NamespacedKey(plugin, "dueno_nombre");
        this.kClan = new NamespacedKey(plugin, "clan");
        this.kVence = new NamespacedKey(plugin, "vence");
        this.kEfectos = new NamespacedKey(plugin, "efectos");
        this.kSemana = new NamespacedKey(plugin, "semana");
        this.kVersion = new NamespacedKey(plugin, "version");
    }

    /* ================================================================== crear */

    /**
     * El objeto de esa ficha. El material es el del tipo; si el tipo ya no existe, el que
     * se pase (el que tenia en el mundo), para no perder la baliza por un config.
     */
    ItemStack crear(Ficha f, Material respaldo) {
        TipoBaliza t = plugin.tipo(f.tipo());
        Material m = t != null ? t.bloque : (respaldo != null && respaldo.isItem() ? respaldo : Material.BEACON);
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        meta.displayName(nombre(f, t));
        meta.lore(lore(f, t));
        meta.setEnchantmentGlintOverride(true);
        // Uno por casilla: que un clic central en creativo no fabrique pilas de 64 con el mismo id.
        meta.setMaxStackSize(1);
        try {
            // Que la lava o el fuego no se lleven una baliza comprada tirada al suelo.
            meta.setDamageResistantTypes(RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE)
                    .getTag(DamageTypeTagKeys.IS_FIRE));
        } catch (Throwable ignorado) {
            // version sin el componente o sin la etiqueta: sin mas
        }
        MenuUtil.hideAll(meta);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(kId, PersistentDataType.STRING, f.id().toString());
        pdc.set(kTipo, PersistentDataType.STRING, f.tipo());
        if (f.dueno() != null) pdc.set(kDueno, PersistentDataType.STRING, f.dueno().toString());
        if (f.duenoNombre() != null) pdc.set(kDuenoNombre, PersistentDataType.STRING, f.duenoNombre());
        if (f.clan() != null) pdc.set(kClan, PersistentDataType.STRING, f.clan());
        pdc.set(kVence, PersistentDataType.LONG, f.vence());
        pdc.set(kEfectos, PersistentDataType.STRING, String.join(",", f.elegidos()));
        if (f.semana() > 0) pdc.set(kSemana, PersistentDataType.LONG, f.semana());
        pdc.set(kVersion, PersistentDataType.INTEGER, VERSION);
        it.setItemMeta(meta);
        return it;
    }

    /* =================================================================== leer */

    /** El id de la baliza que es ese objeto, o null si no es un Super Beacon. Barato. */
    UUID id(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        PersistentDataContainerView pdc = it.getPersistentDataContainer();
        return uuid(pdc.get(kId, PersistentDataType.STRING));
    }

    /** Su ficha entera, o null si no es un Super Beacon (o esta tan roto que no se lee). */
    Ficha leer(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        PersistentDataContainerView pdc = it.getPersistentDataContainer();
        UUID id = uuid(pdc.get(kId, PersistentDataType.STRING));
        String tipo = pdc.get(kTipo, PersistentDataType.STRING);
        if (id == null || tipo == null) return null;
        String efectos = pdc.getOrDefault(kEfectos, PersistentDataType.STRING, "");
        List<String> elegidos = new ArrayList<>();
        for (String e : efectos.split(",")) {
            if (!e.isBlank()) elegidos.add(e.trim());
        }
        Long vence = pdc.getOrDefault(kVence, PersistentDataType.LONG, 0L);
        Long semana = pdc.getOrDefault(kSemana, PersistentDataType.LONG, 0L);
        return new Ficha(id, tipo, uuid(pdc.get(kDueno, PersistentDataType.STRING)),
                pdc.get(kDuenoNombre, PersistentDataType.STRING), pdc.get(kClan, PersistentDataType.STRING),
                vence == null ? 0L : vence, elegidos, semana == null ? 0L : semana);
    }

    private static UUID uuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /* ========================================================= nombre y lore */

    /** El nombre del tipo tal cual el config, sin cursiva (la negrita solo si el config la trae). */
    Component nombre(Ficha f, TipoBaliza t) {
        String n = t != null ? t.nombre : "&#D7F3FFSuper Beacon";
        return Estilo.legado(n).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * El lore, en cuatro bloques separados por una raya:
     *   - bajo el nombre, que es: el clan y la semana ganada (el trofeo) o la frase corta de
     *     su descripcion ("Para tu base");
     *   - a quien da y en cuantos bloques, y sus efectos agrupados (vida y defensa, movimiento
     *     y mineria, boosts). Si se eligen, el elegido con ● y el resto con ○;
     *   - de quien es (Dueño, o Líder si es de clan) y cuando vence, con su dia de la semana;
     *   - como se usa, en dos lineas.
     * El color del tipo es el unico acento. Con FECHA y no cuenta atras: un lore no se
     * repinta dentro de un inventario.
     */
    List<Component> lore(Ficha f, TipoBaliza t) {
        SuperBeaconPlugin.TextosBaliza tx = plugin.textos();
        List<Component> out = new ArrayList<>();
        if (t == null) {
            out.add(tx.linea("objeto-tipo-perdido", "&#FF5C5CSu tipo (%tipo%) ya no existe. Avisa al staff.",
                    "%tipo%", f.tipo()));
            return out;
        }
        long ahora = System.currentTimeMillis();
        String ac = Presentacion.hex(t.color());
        boolean deClan = t.beneficia == TipoBaliza.Beneficia.CLAN;

        // 1. Que es.
        List<String> sub = subtitulo(t, f.clan(), f.semana());
        for (int i = 0; i < sub.size(); i++) {
            out.add(tx.linea(i == 0 ? "lore-subtitulo" : "lore-subtitulo-sigue",
                    i == 0 ? "%acento%◆ &#C4C4C4%texto%" : "  &#C4C4C4%texto%", "%acento%", ac, "%texto%", sub.get(i)));
        }
        if (!sub.isEmpty()) out.add(raya(tx));

        // 2. A quien, donde y que.
        String elige = t.fijo() ? "" : tx.crudo("lore-elige", ". Elige %elegibles%")
                .replace("%elegibles%", String.valueOf(t.elegibles));
        String para = switch (t.beneficia) {
            case CLAN -> "lore-para-clan";
            case TODOS -> "lore-para-todos";
            default -> "lore-para-dueno";
        };
        String paraRespaldo = switch (t.beneficia) {
            case CLAN -> "&#C4C4C4A tu clan, en %radio% bloques%elige%:";
            case TODOS -> "&#C4C4C4Para todos, en %radio% bloques%elige%:";
            default -> "&#C4C4C4Para ti, en %radio% bloques%elige%:";
        };
        out.add(tx.linea(para, paraRespaldo, "%radio%", String.valueOf(t.radio), "%elige%", elige, "%acento%", ac));
        List<Efecto> activos = t.activos(f.elegidos());
        for (Efecto e : Presentacion.agrupados(t.efectos.values())) {
            String nombre = e.nombrePlano();
            if (t.fijo()) {
                out.add(tx.linea("lore-efecto", " %acento%%simbolo% &f%efecto%", "%acento%", ac,
                        "%simbolo%", Presentacion.simbolo(Presentacion.seccion(e)), "%efecto%", nombre));
            } else if (activos.contains(e)) {
                out.add(tx.linea("lore-efecto-elegido", " %acento%● &f%efecto%", "%acento%", ac, "%efecto%", nombre));
            } else {
                out.add(tx.linea("lore-efecto-libre", " &#4E4E4E○ &#8A8A8A%efecto%", "%acento%", ac, "%efecto%", nombre));
            }
        }
        out.add(raya(tx));

        // 3. De quien y hasta cuando.
        if (f.ligada()) {
            out.add(tx.linea(deClan ? "lore-lider" : "lore-dueno",
                    deClan ? "&#8A8A8ALíder · &f%dueno%" : "&#8A8A8ADueño · &f%dueno%", "%dueno%", f.duenoTexto()));
        } else {
            out.add(tx.linea(deClan ? "lore-lider-libre" : "lore-dueno-libre",
                    deClan ? "&#8A8A8ALíder · &fquien lo coloque primero" : "&#8A8A8ADueño · &fquien lo coloque primero"));
        }
        if (!f.caduca()) {
            out.add(tx.linea("lore-permanente", "&#8A8A8ADuración · &fpermanente"));
        } else {
            String fecha = Presentacion.fecha(f.vence(), plugin.zona(), ahora);
            out.add(f.vencida(ahora)
                    ? tx.linea("lore-vencio", "&#8A8A8AVenció · &f%fecha%", "%fecha%", fecha)
                    : tx.linea("lore-vence", "&#8A8A8AVence · &f%fecha%", "%fecha%", fecha));
        }
        out.add(raya(tx));

        // 4. Como se usa: dos lineas como mucho.
        out.add(tx.linea("lore-uso", "&#8A8A8AColócalo y úsalo para abrir su menú."));
        if (!f.ligada()) {
            out.add(tx.linea("lore-regla-libre", "&#8A8A8ASe vuelve tuyo al colocarlo."));
        } else if (deClan) {
            out.add(tx.linea("lore-regla-lider", "&#8A8A8ASolo el líder lo coloca o lo recoge."));
        } else {
            out.add(tx.linea("lore-regla-dueno", "&#8A8A8ASolo su dueño lo coloca o lo recoge."));
        }
        return out;
    }

    private static Component raya(SuperBeaconPlugin.TextosBaliza tx) {
        return tx.linea("lore-raya", Presentacion.OSCURO + Presentacion.RAYA);
    }

    /**
     * La frase bajo el nombre, ya partida: "Clan [ABC] · semana del 28/09 al 04/10" si tiene
     * clan fijado o semana ganada; si no, la primera linea de su descripcion sin el punto
     * ("Para tu base"). Vacia si no hay nada que decir.
     */
    List<String> subtitulo(TipoBaliza t, String clan, long semana) {
        SuperBeaconPlugin.TextosBaliza tx = plugin.textos();
        List<String> partes = new ArrayList<>();
        if (clan != null) partes.add(tx.crudo("lore-clan", "Clan [%clan%]").replace("%clan%", clan));
        if (semana > 0) {
            String[] s = Presentacion.semana(semana);
            partes.add(tx.crudo("lore-semana", "semana %desde% al %hasta%")
                    .replace("%desde%", s[0]).replace("%hasta%", s[1]));
        }
        String texto;
        if (!partes.isEmpty()) {
            // Si no cabe en una linea, se corta por el punto medio, no a mitad de la fecha.
            String junto = Presentacion.mayuscula(String.join(" · ", partes));
            if (Presentacion.largo(junto) <= Presentacion.ANCHO - 2 || partes.size() == 1) {
                return Presentacion.partir(junto, Presentacion.ANCHO - 2);
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < partes.size(); i++) {
                String p = i == 0 ? Presentacion.mayuscula(partes.get(i)) : partes.get(i);
                out.add(i < partes.size() - 1 ? p + " ·" : p);
            }
            return out;
        } else if (!t.descripcion.isEmpty() && !t.descripcion.get(0).isBlank()) {
            texto = Presentacion.sinPunto(Presentacion.plano(t.descripcion.get(0)));
        } else {
            return List.of();
        }
        return Presentacion.partir(texto, Presentacion.ANCHO - 2);
    }
}
