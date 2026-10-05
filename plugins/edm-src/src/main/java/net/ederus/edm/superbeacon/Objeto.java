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
 * objetos ya repartidos dejarian de reconocerse. superbeacon:lore es solo la huella del lore
 * con que se pinto, para repintar los viejos al entrar o al abrir un cofre (renovar).
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
    /** La huella del lore con que se pinto (ver renovar). No es parte de la ficha. */
    private final NamespacedKey kLore;

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
        this.kLore = new NamespacedKey(plugin, "lore");
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
        if (t != null) pdc.set(kLore, PersistentDataType.INTEGER, huella(f, t));
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

    /** El nombre del tipo en degradado de su color, sin cursiva ni negrita. */
    Component nombre(Ficha f, TipoBaliza t) {
        return Estilo.legado(LoreBaliza.nombre(t)).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * El lore (ver {@link LoreBaliza}): que es y para quien, su frase, sus efectos y su
     * alcance, de quien es y cuando vence, y como se usa. Con FECHA y no cuenta atras: un
     * lore no se repinta dentro de un inventario.
     */
    List<Component> lore(Ficha f, TipoBaliza t) {
        List<Component> out = new ArrayList<>();
        for (String l : lineas(f, t)) out.add(Estilo.legado(l));
        return out;
    }

    /** El lore en texto con codigos &, tal cual sale en el objeto. */
    List<String> lineas(Ficha f, TipoBaliza t) {
        SuperBeaconPlugin.TextosBaliza tx = plugin.textos();
        return new LoreBaliza(tx::crudo, plugin.zona()).lore(f, t, System.currentTimeMillis());
    }

    /* ============================================================== renovar */

    /** Huella del nombre y el lore que tocan ahora: si la del objeto no coincide, se repinta. */
    private int huella(Ficha f, TipoBaliza t) {
        return (LoreBaliza.nombre(t) + "\n" + String.join("\n", lineas(f, t))).hashCode();
    }

    /**
     * Repinta el nombre y el lore de un Super Beacon que se hizo con otros textos (una version
     * anterior, otro mensajes.yml, ya vencido...). El PDC no se toca. true si lo cambio.
     */
    boolean renovar(ItemStack it) {
        Ficha f = leer(it);
        if (f == null) return false;
        TipoBaliza t = plugin.tipo(f.tipo());
        if (t == null) return false;
        int h = huella(f, t);
        Integer ya = it.getPersistentDataContainer().get(kLore, PersistentDataType.INTEGER);
        if (ya != null && ya == h) return false;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return false;
        meta.displayName(nombre(f, t));
        meta.lore(lore(f, t));
        meta.getPersistentDataContainer().set(kLore, PersistentDataType.INTEGER, h);
        it.setItemMeta(meta);
        return true;
    }

    /** Repinta los Super Beacons de un inventario. Cuantos cambio. */
    int renovar(org.bukkit.inventory.Inventory inv) {
        if (inv == null) return 0;
        int n = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || id(it) == null) continue;
            if (renovar(it)) {
                inv.setItem(i, it);
                n++;
            }
        }
        return n;
    }
}
