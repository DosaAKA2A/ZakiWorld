package net.ederus.edm.glow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;

import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.menu.MenuUtil;
import net.kyori.adventure.text.Component;

/**
 * /glow: los 16 colores, el arcoiris, el parpadeo y apagarlo. Con la forma de
 * los demas menus de Ederus (marco negro, lore gris, ultima linea amarilla con
 * lo que hace el clic, puerta para cerrar) y los mismos cubos de pintura que el
 * menu de colores del chat.
 *
 * Todo con clic izquierdo: en Bedrock no hay derecho ni shift. Ojo, Bedrock no
 * dibuja los contornos; alli el color solo se nota en el nombre.
 */
final class MenuBrillo implements Listener {

    private static final int ACTUAL = 4;
    private static final int[] COLORES = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 29, 30};
    private static final int ARCOIRIS = 32;
    private static final int PARPADEO = 33;
    private static final int CERRAR = 49;

    private static final class Vista implements InventoryHolder {
        final Map<Integer, Runnable> acciones = new HashMap<>();
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final GlowPlugin glow;

    MenuBrillo(GlowPlugin glow) {
        this.glow = glow;
    }

    void abrir(Player p) {
        Vista v = new Vista();
        v.inv = Bukkit.createInventory(v, 54, Estilo.titulo("EDERUS", glow.textos.crudo("titulo-menu", "Brillo")));
        for (int i = 0; i < 54; i++) v.inv.setItem(i, MenuUtil.pane());

        Almacen.Eleccion mia = glow.eleccion(p);
        v.inv.setItem(ACTUAL, actual(p, mia));
        v.acciones.put(ACTUAL, () -> {
            if (glow.eleccion(p) == null) return;
            glow.elegir(p, null);
            glow.di(p, "apagado", "&7Ya no brillas.");
            sonar(p, 0.8f);
            abrir(p);
        });

        Brillo[] todos = Brillo.values();
        for (int i = 0; i < todos.length && i < COLORES.length; i++) {
            Brillo b = todos[i];
            boolean puede = glow.puedeColor(p, b);
            boolean enUso = mia != null && b.clave.equals(mia.color());
            List<Component> lore = new ArrayList<>();
            lore.add(glow.textos.sin("muestra", "&7Así se ve tu contorno."));
            lore.add(Component.empty());
            lore.add(estado(puede, enUso, "clic-usar", "&e► Clic para usarlo"));
            Component nombre = Estilo.legado(GlowPlugin.pintado(b));
            v.inv.setItem(COLORES[i], MenuUtil.icon(icono(b.clave, Material.WHITE_WOOL), nombre, lore, enUso));
            v.acciones.put(COLORES[i], () -> {
                if (!glow.puedeColor(p, b)) {
                    glow.di(p, "sin-permiso", "&cTodavía no tienes ese brillo.");
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                    return;
                }
                Almacen.Eleccion antes = glow.eleccion(p);
                glow.elegir(p, new Almacen.Eleccion(b.clave, antes != null && antes.parpadeo()));
                glow.di(p, "puesto", "&7Ahora brillas en %color%&7.", "%color%", GlowPlugin.pintado(b));
                sonar(p, 1.2f);
                abrir(p);
            });
        }

        boolean arco = mia != null && mia.arcoiris();
        boolean puedeArco = glow.puedeArcoiris(p);
        v.inv.setItem(ARCOIRIS, MenuUtil.icon(icono("rainbow", Material.PRISMARINE_CRYSTALS),
                glow.textos.sin("arcoiris-nombre", "&fArcoíris"),
                List.of(glow.textos.sin("arcoiris-lore", "&7Cambia de color."), Component.empty(),
                        estado(puedeArco, arco, "clic-usar", "&e► Clic para usarlo")), arco));
        v.acciones.put(ARCOIRIS, () -> {
            if (!glow.puedeArcoiris(p)) {
                glow.di(p, "sin-permiso", "&cTodavía no tienes ese brillo.");
                return;
            }
            Almacen.Eleccion antes = glow.eleccion(p);
            glow.elegir(p, new Almacen.Eleccion("rainbow", antes != null && antes.parpadeo()));
            glow.di(p, "arcoiris", "&7Ahora brillas en arcoíris.");
            sonar(p, 1.4f);
            abrir(p);
        });

        boolean parp = mia != null && mia.parpadeo();
        boolean puedeParp = glow.puedeParpadeo(p);
        List<Component> loreParp = new ArrayList<>();
        loreParp.add(glow.textos.sin("parpadeo-lore", "&7Se enciende y se apaga."));
        loreParp.add(Component.empty());
        if (!puedeParp) loreParp.add(glow.textos.sin("bloqueado", "&cBloqueado"));
        else if (parp) {
            loreParp.add(glow.textos.sin("activado", "&9✔ Activado"));
            loreParp.add(glow.textos.sin("clic-desactivar", "&e► Clic para desactivarlo"));
        } else loreParp.add(glow.textos.sin("clic-activar", "&e► Clic para activarlo"));
        v.inv.setItem(PARPADEO, MenuUtil.icon(icono("parpadeo", Material.GLOW_INK_SAC),
                glow.textos.sin("parpadeo-nombre", "&fParpadeo"), loreParp, parp));
        v.acciones.put(PARPADEO, () -> {
            Almacen.Eleccion antes = glow.eleccion(p);
            if (!glow.puedeParpadeo(p)) {
                glow.di(p, "sin-permiso", "&cTodavía no tienes ese brillo.");
                return;
            }
            if (antes == null) {
                glow.di(p, "sin-brillo", "&7Primero elige un color.");
                return;
            }
            glow.elegir(p, new Almacen.Eleccion(antes.color(), !antes.parpadeo()));
            glow.di(p, antes.parpadeo() ? "parpadeo-off" : "parpadeo-on", "");
            sonar(p, 1f);
            abrir(p);
        });

        v.inv.setItem(CERRAR, MenuUtil.simple(Material.SPRUCE_DOOR, glow.textos.sin("cerrar", "&7Cerrar"), List.of()));
        v.acciones.put(CERRAR, p::closeInventory);
        p.openInventory(v.inv);
    }

    private ItemStack actual(Player p, Almacen.Eleccion mia) {
        List<Component> lore = new ArrayList<>();
        if (mia == null) {
            lore.add(glow.textos.sin("actual-ninguno", "&7No brillas."));
        } else {
            Brillo b = mia.brillo();
            String color = mia.arcoiris() ? glow.textos.crudo("arcoiris-nombre", "&fArcoíris")
                    : b == null ? "&f?" : GlowPlugin.pintado(b);
            lore.add(glow.textos.sin("actual-lore", "&7Brillas en %color%", "%color%", color));
            lore.add(Component.empty());
            lore.add(glow.textos.sin("clic-apagar", "&e► Clic para apagarlo"));
        }
        ItemStack cabeza = MenuUtil.simple(Material.PLAYER_HEAD, glow.textos.sin("actual", "&fTu brillo"), lore);
        if (cabeza.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(p);
            cabeza.setItemMeta(meta);
        }
        return cabeza;
    }

    private Component estado(boolean puede, boolean enUso, String clave, String respaldo) {
        if (!puede) return glow.textos.sin("bloqueado", "&cBloqueado");
        if (enUso) return glow.textos.sin("en-uso", "&9✔ En uso");
        return glow.textos.sin(clave, respaldo);
    }

    /** Un material, o head:<base64> para una cabeza con textura (glow/config.yml, iconos). */
    private ItemStack icono(String clave, Material respaldo) {
        String spec = glow.getConfig().getString("iconos." + clave, "");
        if (spec == null || spec.isBlank()) return new ItemStack(respaldo);
        if (spec.toLowerCase(Locale.ROOT).startsWith("head:")) {
            String b64 = spec.substring(5).trim();
            ItemStack item = new ItemStack(Material.PLAYER_HEAD);
            if (item.getItemMeta() instanceof SkullMeta meta) {
                PlayerProfile perfil = Bukkit.createProfile(UUID.nameUUIDFromBytes(b64.getBytes()), "glow");
                perfil.setProperty(new ProfileProperty("textures", b64));
                meta.setPlayerProfile(perfil);
                item.setItemMeta(meta);
            }
            return item;
        }
        Material m = Material.matchMaterial(spec);
        return new ItemStack(m != null && m.isItem() ? m : respaldo);
    }

    private static void sonar(Player p, float tono) {
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, tono);
    }

    @EventHandler
    public void alClic(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Vista v)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        Runnable r = v.acciones.get(e.getSlot());
        if (r != null) Bukkit.getScheduler().runTask(glow.core(), r);
    }

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Vista) e.setCancelled(true);
    }
}
