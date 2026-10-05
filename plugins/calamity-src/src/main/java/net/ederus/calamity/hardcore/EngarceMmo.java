package net.ederus.calamity.hardcore;

import io.lumine.mythic.lib.api.item.NBTItem;
import net.Indyuce.mmoitems.ItemStats;
import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.Type;
import net.Indyuce.mmoitems.api.interaction.GemStone;
import net.Indyuce.mmoitems.api.interaction.UseItem;
import net.Indyuce.mmoitems.api.item.mmoitem.LiveMMOItem;
import net.Indyuce.mmoitems.api.item.mmoitem.VolatileMMOItem;
import net.Indyuce.mmoitems.api.item.template.MMOItemTemplate;
import net.Indyuce.mmoitems.stat.data.GemSocketsData;
import net.Indyuce.mmoitems.stat.data.GemstoneData;
import net.Indyuce.mmoitems.util.MMOUtils;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * El Engarzador contra la API de MMOItems 6.10.1 (y MythicLib 1.7.1), la unica clase de
 * Calamity que la importa. NADIE la llama sin mirar antes PuenteMmo.disponible(): la JVM no
 * carga una clase hasta que se usa, asi que sin MMOItems en el servidor esto no se toca y
 * Calamity arranca igual (MMOItems sigue siendo softdepend).
 *
 * Que el objeto quede como con el engarce de MMOItems (arrastrar la gema sobre la pieza) no es
 * una imitacion: es el mismo codigo. Lo nativo (ItemUse.gemStonesAndItemStacks) hace
 * Type.toUseItem(jugador, gema) -> GemStone.applyOntoItem(pieza, tipo); aqui igual, con la
 * variante que no escribe en el chat (el mensaje lo pone el Engarzador). applyOntoItem mete la
 * gema en el hueco (GemSocketsData), suma sus stats al historial de la pieza (StatHistory, con
 * el UUID de la gema) y la reconstruye con el ItemStackBuilder de MMOItems.
 *
 * Quitar es lo que hace MMOItems con sus consumibles de desengarzar (RandomUnsocket, leido con
 * javap): MMOItem.removeGemStone(uuid, color), recalcular el historial de GEM_SOCKETS y
 * reconstruir. Al reconstruir, cada StatHistory purga las gemas que ya no estan en el hueco
 * (purgeGemstones), y asi se restan sus stats. La gema no se reconstruye: se rompe.
 *
 * El ItemStackBuilder hace un ItemStack nuevo: las marcas de otros plugins en el
 * PersistentDataContainer (lethal_world:ligado, que ata la pieza a su dueno) se perderian, y
 * con el engarce nativo se pierden. Aqui se copian de la pieza vieja a la nueva
 * (conservarMarcas), menos las de MMOItems y MythicLib, que manda la pieza nueva.
 */
final class EngarceMmo {

    private EngarceMmo() {
    }

    /** El nombre del hueco sin color en la config de MMOItems (gem-sockets.uncolored). */
    static String sinColor() {
        return GemSocketsData.getUncoloredGemSlot();
    }

    /** La ficha de un objeto de MMOItems (tipo, id, si es gema y su color, sus huecos); null si no lo es. */
    static Engarce.Ficha leer(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        NBTItem nbt = NBTItem.get(it);
        if (nbt == null || !nbt.hasType()) return null;
        Type t = Type.get(nbt.getType());
        if (t == null) return null;
        String id = nbt.getString("MMOITEMS_ITEM_ID");
        boolean gema = t.corresponds(Type.GEM_STONE);
        String color = gema ? nbt.getString(ItemStats.GEM_COLOR.getNBTPath()) : null;
        List<Engarce.Hueco> huecos = new ArrayList<>();
        VolatileMMOItem v = new VolatileMMOItem(nbt);
        if (v.hasData(ItemStats.GEM_SOCKETS) && v.getData(ItemStats.GEM_SOCKETS) instanceof GemSocketsData d) {
            List<GemstoneData> gemas = new ArrayList<>(d.getGemstones());
            gemas.sort(Comparator.comparing(g -> limpio(g.getName())));
            for (GemstoneData g : gemas) {
                String c = g.getSocketColor() == null ? sinColor() : g.getSocketColor();
                UUID u = g.getHistoricUUID();
                huecos.add(new Engarce.Hueco(c, limpio(g.getName()), g.getMMOItemType() + "." + g.getMMOItemID(),
                        u == null ? "" : u.toString()));
            }
            for (String s : d.getEmptySlots()) huecos.add(new Engarce.Hueco(s, null, null, null));
        }
        return new Engarce.Ficha(t.getId(), id == null ? "" : id, gema, color, huecos);
    }

    /** Un nombre de MMOItems sin codigos de color (&a, §a, <#FFAA00>, <red>). */
    static String limpio(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)[&§][0-9a-fk-orx]", "").replaceAll("<[^>]{1,40}>", "").trim();
    }

    /**
     * Engarza una gema (se usa solo una aunque llegue un monton) en la pieza, como MMOItems.
     * El jugador hace falta porque la API lo pide: el evento ApplyGemStoneEvent y los
     * requisitos de la gema (nivel, clase) van por el.
     */
    static Engarce.Resultado engarzar(Player p, ItemStack pieza, ItemStack gema) {
        NBTItem nGema = NBTItem.get(gema.asOne());
        Type tGema = nGema == null || !nGema.hasType() ? null : Type.get(nGema.getType());
        if (tGema == null) return new Engarce.Resultado(Engarce.Estado.NADA, pieza, "MMOItems no reconoce la gema");
        UseItem uso = tGema.toUseItem(p, nGema);
        GemStone piedra = uso instanceof GemStone g ? g : tGema.corresponds(Type.GEM_STONE) ? new GemStone(p, nGema) : null;
        if (piedra == null) return new Engarce.Resultado(Engarce.Estado.NADA, pieza, "MMOItems no la trata como gema");
        if (!piedra.checkItemRequirements()) return new Engarce.Resultado(Engarce.Estado.NADA, pieza, "no cumples lo que pide la gema");

        NBTItem nPieza = NBTItem.get(pieza);
        Type tPieza = nPieza == null || !nPieza.hasType() ? null : Type.get(nPieza.getType());
        if (tPieza == null) return new Engarce.Resultado(Engarce.Estado.NADA, pieza, "MMOItems no reconoce la pieza");
        GemStone.ApplyResult r = piedra.applyOntoItem(new LiveMMOItem(nPieza), tPieza, MMOUtils.getDisplayName(pieza), true, true);
        if (r == null || r.getType() == GemStone.ResultType.NONE) {
            return new Engarce.Resultado(Engarce.Estado.NADA, pieza, "MMOItems no la deja engarzar ahí");
        }
        if (r.getType() == GemStone.ResultType.FAILURE) return new Engarce.Resultado(Engarce.Estado.ROTA, pieza, null);
        ItemStack nueva = r.getResult();
        if (nueva == null || nueva.getType().isAir()) return new Engarce.Resultado(Engarce.Estado.NADA, pieza, "MMOItems no devolvió la pieza");
        conservarMarcas(pieza, nueva);
        Ligado.copiarLineaLigado(pieza, nueva);
        nueva.setAmount(Math.max(1, pieza.getAmount()));
        return new Engarce.Resultado(Engarce.Estado.HECHO, nueva, null);
    }

    /**
     * Quita la gema de ese UUID (Hueco.uuid) y deja su hueco libre. La gema no se devuelve: se
     * rompe. Si al releer la pieza la gema sigue ahi, no se cambia nada.
     */
    static Engarce.Resultado quitar(ItemStack pieza, String uuid) {
        UUID u;
        try {
            u = UUID.fromString(uuid);
        } catch (RuntimeException mala) {
            return new Engarce.Resultado(Engarce.Estado.NADA, pieza, "esa gema es de una versión vieja de MMOItems y no se puede quitar");
        }
        NBTItem n = NBTItem.get(pieza);
        if (n == null || !n.hasType()) return new Engarce.Resultado(Engarce.Estado.NADA, pieza, "MMOItems no reconoce la pieza");
        LiveMMOItem mmo = new LiveMMOItem(n);
        if (!mmo.hasData(ItemStats.GEM_SOCKETS) || !(mmo.getData(ItemStats.GEM_SOCKETS) instanceof GemSocketsData d)) {
            return new Engarce.Resultado(Engarce.Estado.NADA, pieza, "la pieza no tiene huecos");
        }
        GemstoneData g = null;
        for (GemstoneData x : d.getGemstones()) if (u.equals(x.getHistoricUUID())) g = x;
        if (g == null) return new Engarce.Resultado(Engarce.Estado.NADA, pieza, "esa gema ya no está en la pieza");
        String color = g.getSocketColor() != null ? g.getSocketColor() : GemSocketsData.getUncoloredGemSlot();
        mmo.removeGemStone(g.getHistoricUUID(), color);
        mmo.setData(ItemStats.GEM_SOCKETS, mmo.computeStatHistory(ItemStats.GEM_SOCKETS).recalculate(mmo.getUpgradeLevel()));
        ItemStack nueva = mmo.newBuilder().build();
        if (nueva == null || nueva.getType().isAir()) return new Engarce.Resultado(Engarce.Estado.NADA, pieza, "MMOItems no devolvió la pieza");
        Engarce.Ficha f = leer(nueva);
        if (f == null) return new Engarce.Resultado(Engarce.Estado.NADA, pieza, "la pieza nueva no se lee");
        for (Engarce.Hueco h : f.huecos()) {
            if (uuid.equals(h.uuid())) return new Engarce.Resultado(Engarce.Estado.NADA, pieza, "MMOItems no ha soltado la gema");
        }
        conservarMarcas(pieza, nueva);
        Ligado.copiarLineaLigado(pieza, nueva);
        nueva.setAmount(Math.max(1, pieza.getAmount()));
        return new Engarce.Resultado(Engarce.Estado.HECHO, nueva, null);
    }

    /**
     * Copia a la pieza nueva las marcas del PersistentDataContainer de la vieja que la nueva no
     * trae (lethal_world:ligado y las de otros plugins). Las de MMOItems y MythicLib no: esas
     * las decide la pieza nueva.
     */
    static void conservarMarcas(ItemStack vieja, ItemStack nueva) {
        if (vieja == null || nueva == null) return;
        ItemMeta mv = vieja.getItemMeta();
        if (mv == null || mv.getPersistentDataContainer().isEmpty()) return;
        ItemStack papel = new ItemStack(Material.PAPER);
        ItemMeta mp = papel.getItemMeta();
        if (mp == null) return;
        PersistentDataContainer tmp = mp.getPersistentDataContainer();
        mv.getPersistentDataContainer().copyTo(tmp, true);
        for (NamespacedKey k : new ArrayList<>(tmp.getKeys())) {
            String ns = k.getNamespace().toLowerCase(Locale.ROOT);
            if (ns.equals("mmoitems") || ns.equals("mythiclib")) tmp.remove(k);
        }
        if (tmp.isEmpty()) return;
        nueva.editPersistentDataContainer(pdc -> tmp.copyTo(pdc, false));
    }

    /** Un objeto de MMOItems a partir de TIPO.ID (el icono de una gema engarzada); null si no existe. */
    static ItemStack crear(String enlace) {
        return PuenteMmo.crear(enlace);
    }

    /** Para el autotest: el TIPO.ID de una gema de MMOItems que no sea de esos tipos, o null si no hay ninguna. */
    static String gemaAjena(Set<String> tiposCalamity) {
        for (Type t : MMOItems.plugin.getTypes().getAll()) {
            if (!t.corresponds(Type.GEM_STONE) || tiposCalamity.contains(t.getId().toUpperCase(Locale.ROOT))) continue;
            for (MMOItemTemplate tpl : MMOItems.plugin.getTemplates().getTemplates(t)) {
                String enlace = t.getId() + "." + tpl.getId();
                if (PuenteMmo.crear(enlace) != null) return enlace;
            }
        }
        return null;
    }
}
