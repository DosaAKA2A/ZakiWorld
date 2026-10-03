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
 * :vence, :efectos y :version; no se renombran nunca, o los objetos ya repartidos dejarian
 * de reconocerse.
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
        return new Ficha(id, tipo, uuid(pdc.get(kDueno, PersistentDataType.STRING)),
                pdc.get(kDuenoNombre, PersistentDataType.STRING), pdc.get(kClan, PersistentDataType.STRING),
                vence == null ? 0L : vence, elegidos);
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
     * El lore: lo que es, lo que da, a quien y hasta cuando. Con FECHA de vencimiento y
     * no cuenta atras: un lore no se repinta dentro de un inventario. Si ya vencio, dice
     * "Venció" y queda de recuerdo.
     */
    List<Component> lore(Ficha f, TipoBaliza t) {
        SuperBeaconPlugin.TextosBaliza tx = plugin.textos();
        List<Component> out = new ArrayList<>();
        if (t == null) {
            out.add(tx.linea("objeto-tipo-perdido", "&#FF5C5CSu tipo (%tipo%) ya no existe. Avisa al staff.",
                    "%tipo%", f.tipo()));
            return out;
        }
        for (String d : t.descripcion) out.add(Estilo.legado(d.contains("&") ? d : "&#8A8A8A" + d));
        if (!t.descripcion.isEmpty()) out.add(Estilo.vacio());

        if (!t.efectos.isEmpty()) {
            out.add(tx.linea("objeto-efectos", "&#D7F3FFEfectos"));
            List<Efecto> activos = t.activos(f.elegidos());
            for (Efecto e : t.efectos.values()) {
                boolean on = activos.contains(e);
                out.add(tx.linea(on ? "objeto-efecto-activo" : "objeto-efecto-inactivo",
                        on ? "&#5CFF7A✦ &f%efecto%" : "&#545454✦ &#8A8A8A%efecto%", "%efecto%", e.nombre()));
            }
            out.add(Estilo.vacio());
        }

        out.add(tx.linea("objeto-alcance", "&#545454▸ &#D7F3FFAlcance  &f%radio% bloques",
                "%radio%", String.valueOf(t.radio)));
        out.add(tx.linea("objeto-beneficia", "&#545454▸ &#D7F3FFBeneficia  &f%beneficia%",
                "%beneficia%", plugin.beneficiaTexto(t.beneficia)));
        if (f.ligada()) {
            out.add(tx.linea("objeto-dueno", "&#545454▸ &#D7F3FFDueño  &f%dueno%", "%dueno%", f.duenoTexto()));
        }
        if (f.clan() != null) {
            out.add(tx.linea("objeto-clan", "&#545454▸ &#D7F3FFClan  &f%clan%", "%clan%", f.clan()));
        }
        long ahora = System.currentTimeMillis();
        if (!f.caduca()) {
            out.add(tx.linea("objeto-permanente", "&#545454▸ &#D7F3FFDuración  &fpermanente"));
        } else if (f.vencida(ahora)) {
            out.add(tx.linea("objeto-vencio", "&#545454▸ &#D7F3FFVenció  &#FF5C5C%fecha%",
                    "%fecha%", Tiempo.fecha(f.vence(), plugin.zona())));
        } else {
            out.add(tx.linea("objeto-vence", "&#545454▸ &#D7F3FFVence  &f%fecha%",
                    "%fecha%", Tiempo.fecha(f.vence(), plugin.zona())));
        }

        out.add(Estilo.vacio());
        if (!f.ligada()) {
            out.add(tx.linea("objeto-sin-dueno", "&#8A8A8ASe vuelve tuyo al colocarlo por primera vez."));
        } else {
            out.add(tx.linea("objeto-ligado", "&#8A8A8ASolo su dueño puede colocarlo."));
        }
        if (t.fijo()) {
            out.add(tx.linea("objeto-todos", "&#8A8A8ATodos sus efectos van activos a la vez."));
        } else {
            out.add(tx.linea("objeto-elige", "&#8A8A8AElige %elegibles% efectos en su menú.",
                    "%elegibles%", String.valueOf(t.elegibles)));
        }
        out.add(tx.linea("objeto-uso", "&#8A8A8AColócalo y úsalo para abrir su menú."));
        return out;
    }
}
