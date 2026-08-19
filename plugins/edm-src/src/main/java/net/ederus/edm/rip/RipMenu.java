/*
 * Decompiled with CFR 0.152.
 */
package net.ederus.edm.rip;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.edm.rip.Compat;
import net.ederus.edm.rip.Rarity;
import net.ederus.edm.rip.RipEffect;
import net.ederus.edm.rip.RipPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

public final class RipMenu
implements Listener {
    private static final int[] EFFECT_SLOTS = new int[]{18, 19, 20, 21, 22, 23, 24, 25, 26, 28, 29, 30, 31, 32, 33, 34};
    private static final int[] SEPARATORS = new int[]{9, 10, 11, 12, 13, 14, 15, 16, 17, 36, 37, 38, 39, 40, 41, 42, 43, 44};
    private static final int[] FRAME = new int[]{0, 1, 3, 5, 7, 8, 27, 35, 47, 51, 52};
    private static final int SLOT_TAB_KILL = 2;
    private static final int SLOT_PROFILE = 4;
    private static final int SLOT_TAB_DEATH = 6;
    private static final int SLOT_CLOSE = 45;
    private static final int SLOT_RANDOM = 46;
    private static final int SLOT_PREV = 48;
    private static final int SLOT_CLEAR = 49;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_LEGEND = 53;
    private static final TextColor KILL_COLOR = TextColor.color((int)0xFF5555);
    private static final TextColor DEATH_COLOR = TextColor.color((int)11829247);
    private static final TextColor GOLD = TextColor.color((int)16766822);
    private static final TextColor SOFT = TextColor.color((int)0x8A8A8A);
    private final RipPlugin plugin;

    public RipMenu(RipPlugin plugin) {
        this.plugin = plugin;
    }

    private static int pages(RipEffect.Type type) {
        return Math.max(1, (RipEffect.count(type) + EFFECT_SLOTS.length - 1) / EFFECT_SLOTS.length);
    }

    public void open(Player player, RipEffect.Type type) {
        this.open(player, type, 0);
    }

    public void open(Player player, RipEffect.Type type, int page) {
        Inventory inv;
        boolean kill = type == RipEffect.Type.KILL;
        int totalPages = RipMenu.pages(type);
        int safePage = Math.floorMod(page, totalPages);
        TextColor accent = kill ? KILL_COLOR : DEATH_COLOR;
        Holder holder = new Holder(type, safePage);
        Component title = ((TextComponent)((TextComponent)Component.text((String)"\u2726 ", (TextColor)accent).append((Component)Component.text((String)"RIP", (TextColor)NamedTextColor.WHITE, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}))).append((Component)Component.text((String)(kill ? "  \u2694 Kill" : "  \u2620 Muerte"), (TextColor)accent))).append((Component)Component.text((String)("  \u00b7 " + (safePage + 1) + "/" + totalPages), (TextColor)TextColor.color((int)0x707070)));
        holder.inventory = inv = Bukkit.createInventory((InventoryHolder)holder, (int)54, (Component)title);
        this.render(inv, player, type, safePage);
        player.openInventory(inv);
    }

    private void render(Inventory inv, Player player, RipEffect.Type type, int page) {
        boolean kill = type == RipEffect.Type.KILL;
        int totalPages = RipMenu.pages(type);
        ItemStack filler = this.pane();
        for (int i : SEPARATORS) {
            inv.setItem(i, filler);
        }
        for (int i : FRAME) {
            inv.setItem(i, filler);
        }
        inv.setItem(2, this.tabItem(RipEffect.Type.KILL, kill));
        inv.setItem(6, this.tabItem(RipEffect.Type.DEATH, !kill));
        inv.setItem(4, this.profileItem(player));
        List<RipEffect> effects = RipEffect.of(type);
        String selected = this.plugin.getChoice(player.getUniqueId(), type);
        for (int i = 0; i < EFFECT_SLOTS.length; ++i) {
            int index = page * EFFECT_SLOTS.length + i;
            if (index < effects.size()) {
                RipEffect e = effects.get(index);
                inv.setItem(EFFECT_SLOTS[i], this.effectItem(player, e, e.id().equalsIgnoreCase(selected)));
                continue;
            }
            inv.setItem(EFFECT_SLOTS[i], null);
        }
        inv.setItem(45, this.simple(Material.SPRUCE_DOOR, (Component)Component.text((String)"Cerrar", (TextColor)SOFT), List.of()));
        inv.setItem(46, this.randomItem(type, "random".equalsIgnoreCase(selected)));
        inv.setItem(48, page > 0 ? this.simple(Material.ARROW, (Component)Component.text((String)"\u25c0 Pagina anterior", (TextColor)NamedTextColor.YELLOW), List.<Component>of((Component)Component.text((String)("Pagina " + page + " de " + totalPages), (TextColor)SOFT).decoration(TextDecoration.ITALIC, false))) : filler);
        inv.setItem(49, this.simple(Material.BARRIER, (Component)Component.text((String)"Quitar efecto", (TextColor)NamedTextColor.RED), List.<Component>of((Component)Component.text((String)("Sin efecto de " + (kill ? "kill" : "muerte") + "."), (TextColor)SOFT).decoration(TextDecoration.ITALIC, false), (Component)Component.empty(), (Component)Component.text((String)"\u25ba Click para desactivar", (TextColor)NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))));
        inv.setItem(50, page < totalPages - 1 ? this.simple(Material.SPECTRAL_ARROW, (Component)Component.text((String)"Pagina siguiente \u25b6", (TextColor)NamedTextColor.YELLOW), List.<Component>of((Component)Component.text((String)("Pagina " + (page + 2) + " de " + totalPages), (TextColor)SOFT).decoration(TextDecoration.ITALIC, false))) : filler);
        inv.setItem(53, this.legendItem());
    }

    private ItemStack randomItem(RipEffect.Type type, boolean selected) {
        ItemStack item = this.plugin.heads().get("ui.random");
        if (item == null) {
            item = new ItemStack(Material.RECOVERY_COMPASS);
        }
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((String)"\u2684 Aleatorio", (TextColor)TextColor.color((int)7268320), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}).decoration(TextDecoration.ITALIC, false));
        ArrayList<Component> lore = new ArrayList<Component>();
        lore.add(Component.text((String)("El destino elige por ti: cada " + (type == RipEffect.Type.KILL ? "kill" : "muerte")), (TextColor)SOFT).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text((String)"suena uno de tus efectos desbloqueados.", (TextColor)SOFT).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        if (selected) {
            lore.add(Component.text((String)"\u2714 EQUIPADO", (TextColor)NamedTextColor.GREEN, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text((String)"\u25ba Click para quitar", (TextColor)NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text((String)"\u25ba Click para equipar", (TextColor)NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        if (selected && Compat.glow() != null) {
            meta.addEnchant(Compat.glow(), 1, true);
        }
        RipMenu.hideAll(meta);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack effectItem(Player player, RipEffect e, boolean selected) {
        boolean allowed = this.plugin.allows(player, e);
        boolean admin = this.plugin.isAdmin(player);
        Rarity r = e.rarity();
        ItemStack item = null;
        if (allowed) {
            item = this.plugin.heads().get(e.headKey());
        }
        if (item == null || !allowed) {
            item = new ItemStack(allowed ? e.fallbackIcon() : Material.GRAY_DYE);
        }
        ItemMeta meta = item.getItemMeta();
        meta.displayName(allowed ? Component.text((String)("\u2726 " + e.display()), (TextColor)r.color(), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}).decoration(TextDecoration.ITALIC, false) : ((TextComponent)Component.text((String)("\ud83d\udd12 " + e.display()), (TextColor)TextColor.color((int)0x555555)).decoration(TextDecoration.ITALIC, false)).decorate(TextDecoration.STRIKETHROUGH));
        ArrayList<Component> lore = new ArrayList<Component>();
        lore.add(r.label());
        lore.add(Component.empty());
        lore.add(Component.text((String)e.description(), (TextColor)SOFT).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(((TextComponent)Component.text((String)"⏱ Enfriamiento  ", (TextColor)TextColor.color((int)0x404040)).append((Component)Component.text((String)RipPlugin.formatSeconds(this.plugin.effectCooldownMillis(e)), (TextColor)NamedTextColor.AQUA))).decoration(TextDecoration.ITALIC, false));
        if (this.plugin.hidesBody(e)) {
            lore.add(((TextComponent)Component.text((String)"✖ Cuerpo  ", (TextColor)TextColor.color((int)0x404040)).append((Component)Component.text((String)"desaparece al instante", (TextColor)SOFT))).decoration(TextDecoration.ITALIC, false));
        }
        long left = this.plugin.cooldownRemaining(player.getUniqueId());
        if (left > 0L) {
            lore.add(((TextComponent)Component.text((String)"⏱ Disponible en  ", (TextColor)TextColor.color((int)0x404040)).append((Component)Component.text((String)RipPlugin.formatSeconds(left), (TextColor)NamedTextColor.RED))).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        if (admin) {
            lore.add(((TextComponent)Component.text((String)"\u2503 Permiso  ", (TextColor)TextColor.color((int)0x404040)).append((Component)Component.text((String)e.permission(), (TextColor)GOLD))).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
        }
        if (!allowed) {
            lore.add(Component.text((String)"\ud83d\udd12 BLOQUEADO", (TextColor)NamedTextColor.RED, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text((String)"Todavia no has desbloqueado este efecto.", (TextColor)TextColor.color((int)0x555555)).decoration(TextDecoration.ITALIC, false));
        } else if (selected) {
            lore.add(Component.text((String)"\u2714 EQUIPADO", (TextColor)NamedTextColor.GREEN, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text((String)"\u25ba Click para quitar", (TextColor)NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text((String)"\u25ba Click para equipar", (TextColor)NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        if (selected && allowed && Compat.glow() != null) {
            meta.addEnchant(Compat.glow(), 1, true);
        }
        RipMenu.hideAll(meta);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack tabItem(RipEffect.Type type, boolean active) {
        boolean kill = type == RipEffect.Type.KILL;
        TextColor accent = kill ? KILL_COLOR : DEATH_COLOR;
        ItemStack item = this.plugin.heads().get(kill ? "ui.tab_kill" : "ui.tab_death");
        if (item == null) {
            item = new ItemStack(kill ? Material.IRON_SWORD : Material.SKELETON_SKULL);
        }
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((String)(kill ? "\u2694 EFECTOS DE KILL" : "\u2620 EFECTOS DE MUERTE"), (TextColor)accent, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}).decoration(TextDecoration.ITALIC, false));
        ArrayList<Component> lore = new ArrayList<Component>();
        lore.add(Component.text((String)(kill ? "Se reproducen al matar a un jugador." : "Se reproducen cuando tu mueres."), (TextColor)SOFT).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text((String)(RipEffect.count(type) + " efectos \u00b7 " + RipMenu.pages(type) + " paginas"), (TextColor)TextColor.color((int)0x555555)).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(active ? Component.text((String)"\u2714 Pestana activa", (TextColor)NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false) : Component.text((String)"\u25ba Click para cambiar", (TextColor)NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        if (active && Compat.glow() != null) {
            meta.addEnchant(Compat.glow(), 1, true);
        }
        RipMenu.hideAll(meta);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack profileItem(Player player) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta) {
            SkullMeta skull = (SkullMeta)meta;
            skull.setOwningPlayer((OfflinePlayer)player);
        }
        meta.displayName(Component.text((String)player.getName(), (TextColor)GOLD, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}).decoration(TextDecoration.ITALIC, false));
        int unlocked = 0;
        int total = 0;
        for (RipEffect e : RipEffect.values()) {
            ++total;
            if (!this.plugin.allows(player, e)) continue;
            ++unlocked;
        }
        ArrayList<Component> lore = new ArrayList<Component>();
        lore.add(Component.text((String)"Equipado ahora", (TextColor)SOFT).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(this.equipped("\u2694 Kill    ", KILL_COLOR, player, RipEffect.Type.KILL));
        lore.add(this.equipped("\u2620 Muerte  ", DEATH_COLOR, player, RipEffect.Type.DEATH));
        lore.add(Component.empty());
        lore.add(((TextComponent)Component.text((String)"Desbloqueados  ", (TextColor)TextColor.color((int)0x404040)).append((Component)Component.text((String)(unlocked + " / " + total), (TextColor)(unlocked == total ? NamedTextColor.GREEN : GOLD), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}))).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        RipMenu.hideAll(meta);
        item.setItemMeta(meta);
        return item;
    }

    private Component equipped(String label, TextColor color, Player player, RipEffect.Type type) {
        RipEffect e;
        String choice = this.plugin.getChoice(player.getUniqueId(), type);
        TextComponent value = choice == null ? Component.text((String)"ninguno", (TextColor)TextColor.color((int)0x555555)) : ("random".equalsIgnoreCase(choice) ? Component.text((String)"\u2684 Aleatorio", (TextColor)TextColor.color((int)7268320)) : ((e = RipEffect.byId(type, choice)) == null ? Component.text((String)"ninguno", (TextColor)TextColor.color((int)0x555555)) : Component.text((String)e.display(), (TextColor)e.rarity().color())));
        return ((TextComponent)Component.text((String)label, (TextColor)color).append((Component)value)).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack legendItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((String)"Calidades", (TextColor)GOLD, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}).decoration(TextDecoration.ITALIC, false));
        ArrayList<Component> lore = new ArrayList<Component>();
        lore.add(Component.empty());
        for (Rarity r : Rarity.values()) {
            lore.add(r.label());
        }
        lore.add(Component.empty());
        lore.add(Component.text((String)"A mayor calidad, mas espectacular", (TextColor)SOFT).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text((String)"y mas exclusivo el efecto.", (TextColor)SOFT).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        RipMenu.hideAll(meta);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack pane() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName((Component)Component.empty());
        RipMenu.hideAll(meta);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack simple(Material mat, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        RipMenu.hideAll(meta);
        item.setItemMeta(meta);
        return item;
    }

    private static void hideAll(ItemMeta meta) {
        meta.addItemFlags(ItemFlag.values());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder inventoryHolder = event.getInventory().getHolder();
        if (!(inventoryHolder instanceof Holder)) {
            return;
        }
        Holder holder = (Holder)inventoryHolder;
        event.setCancelled(true);
        HumanEntity humanEntity = event.getWhoClicked();
        if (!(humanEntity instanceof Player)) {
            return;
        }
        Player player = (Player)humanEntity;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) {
            return;
        }
        RipEffect.Type type = holder.type;
        int page = holder.page;
        if (slot == 2 && type != RipEffect.Type.KILL) {
            this.plugin.click(player, 1.2f);
            this.open(player, RipEffect.Type.KILL, 0);
            return;
        }
        if (slot == 6 && type != RipEffect.Type.DEATH) {
            this.plugin.click(player, 0.8f);
            this.open(player, RipEffect.Type.DEATH, 0);
            return;
        }
        if (slot == 45) {
            player.closeInventory();
            return;
        }
        if (slot == 48 && page > 0) {
            this.plugin.click(player, 1.0f);
            this.open(player, type, page - 1);
            return;
        }
        if (slot == 50 && page < RipMenu.pages(type) - 1) {
            this.plugin.click(player, 1.1f);
            this.open(player, type, page + 1);
            return;
        }
        if (slot == 46) {
            String current = this.plugin.getChoice(player.getUniqueId(), type);
            if ("random".equalsIgnoreCase(current)) {
                this.plugin.setChoice(player.getUniqueId(), type, null);
                this.plugin.click(player, 0.7f);
                player.sendMessage(this.plugin.prefix().append((Component)Component.text((String)"Modo aleatorio desactivado.", (TextColor)SOFT)));
            } else {
                this.plugin.setChoice(player.getUniqueId(), type, "random");
                Compat.sound(player.getWorld(), player.getLocation(), "entity.player.levelup", 0.7f, 1.3f);
                player.sendMessage(this.plugin.prefix().append((Component)Component.text((String)"Equipado  ", (TextColor)NamedTextColor.GREEN)).append((Component)Component.text((String)"\u2684 Aleatorio", (TextColor)TextColor.color((int)7268320), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD})).append((Component)Component.text((String)("  cada " + (type == RipEffect.Type.KILL ? "kill" : "muerte") + " sera una sorpresa"), (TextColor)TextColor.color((int)0x555555))));
            }
            this.render(event.getInventory(), player, type, page);
            return;
        }
        if (slot == 49) {
            this.plugin.setChoice(player.getUniqueId(), type, null);
            this.plugin.click(player, 0.7f);
            player.sendMessage(this.plugin.prefix().append((Component)Component.text((String)("Efecto de " + type.lower() + " desactivado."), (TextColor)SOFT)));
            this.render(event.getInventory(), player, type, page);
            return;
        }
        int index = RipMenu.indexOf(slot);
        if (index < 0) {
            return;
        }
        int effectIndex = page * EFFECT_SLOTS.length + index;
        List<RipEffect> effects = RipEffect.of(type);
        if (effectIndex >= effects.size()) {
            return;
        }
        RipEffect effect = effects.get(effectIndex);
        if (!this.plugin.allows(player, effect)) {
            Compat.sound(player.getWorld(), player.getLocation(), "entity.villager.no", 1.0f, 0.8f);
            Component msg = this.plugin.isAdmin(player) ? this.plugin.prefix().append((Component)Component.text((String)"Efecto bloqueado. Requiere ", (TextColor)NamedTextColor.RED)).append((Component)Component.text((String)effect.permission(), (TextColor)GOLD)) : this.plugin.prefix().append((Component)Component.text((String)"Todavia no has desbloqueado este efecto.", (TextColor)NamedTextColor.RED));
            player.sendMessage(msg);
            return;
        }
        String current = this.plugin.getChoice(player.getUniqueId(), type);
        if (effect.id().equalsIgnoreCase(current)) {
            this.plugin.setChoice(player.getUniqueId(), type, null);
            this.plugin.click(player, 0.7f);
            player.sendMessage(this.plugin.prefix().append((Component)Component.text((String)(effect.display() + " desequipado."), (TextColor)SOFT)));
        } else {
            this.plugin.setChoice(player.getUniqueId(), type, effect.id());
            Compat.sound(player.getWorld(), player.getLocation(), "entity.player.levelup", 0.7f, 1.6f);
            player.sendMessage(this.plugin.prefix().append((Component)Component.text((String)"Equipado  ", (TextColor)NamedTextColor.GREEN)).append((Component)Component.text((String)effect.display(), (TextColor)effect.rarity().color(), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD})).append((Component)Component.text((String)("  " + effect.rarity().display().toLowerCase()), (TextColor)TextColor.color((int)0x555555))));
        }
        this.render(event.getInventory(), player, type, page);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private static int indexOf(int slot) {
        for (int i = 0; i < EFFECT_SLOTS.length; ++i) {
            if (EFFECT_SLOTS[i] != slot) continue;
            return i;
        }
        return -1;
    }

    private static final class Holder
    implements InventoryHolder {
        final RipEffect.Type type;
        final int page;
        Inventory inventory;

        Holder(RipEffect.Type type, int page) {
            this.type = type;
            this.page = page;
        }

        public Inventory getInventory() {
            return this.inventory;
        }
    }
}

