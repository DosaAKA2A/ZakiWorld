package net.ederus.edm.crates;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.phoenixplugins.phoenixcrates.api.crate.CrateType;
import com.phoenixplugins.phoenixcrates.api.crate.events.CratePreviewOpenEvent;
import com.phoenixplugins.phoenixcrates.api.reward.Reward;

import net.ederus.edm.comun.Estilo;
import net.ederus.edm.crates.Calidades.Caja;
import net.ederus.edm.crates.Calidades.Calidad;
import net.ederus.edm.crates.Calidades.Premio;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * La calidad y la probabilidad de cada premio en la vista previa de la caja.
 *
 * Se pinta SOBRE EL PAQUETE, como el modulo de tooltip: el menu de PhoenixCrates
 * no cambia y sus premios guardados tampoco, asi que lo que se ve siempre sale
 * del config de EDM y no se puede desfasar del sorteo. Ademas el menu se
 * refresca cada segundo: un lore escrito en el item de verdad se borraria.
 *
 * Como se sabe que premio es cada hueco: al abrir la vista previa (evento de
 * PhoenixCrates) se apunta la caja y se espera la ventana que abre a
 * continuacion; los items de esa ventana se reconocen por material y nombre,
 * que son los del premio. Solo se tocan los huecos de arriba (el menu), nunca el
 * inventario del jugador, aunque lleve el mismo objeto.
 *
 * Del lore guardado se quitan las lineas de calidad viejas (las de ExcellentCrates
 * y del generador: "◆ Raro · ...", "Probabilidad ...", "Dentro de la calidad ...")
 * para que no salgan dos calidades distintas.
 */
final class Vitrina implements Listener {

    /** Una vista previa abierta (o a punto de abrirse) por un jugador. */
    private static final class Vista {
        final String caja;
        final long creada = System.currentTimeMillis();
        /** material + nombre -> lineas que se anaden al lore de ese premio. */
        final Map<String, List<Component>> lineas;
        volatile int ventana = -1;
        volatile int huecosArriba = -1;

        Vista(String caja, Map<String, List<Component>> lineas) {
            this.caja = caja;
            this.lineas = lineas;
        }
    }

    private static final PlainTextComponentSerializer PLANO = PlainTextComponentSerializer.plainText();
    /** Lo que mide el inventario del jugador al final de cada ventana. */
    private static final int INVENTARIO_JUGADOR = 36;

    private final CratesPlugin modulo;
    private final Map<UUID, Vista> vistas = new ConcurrentHashMap<>();
    /** Por caja: material + nombre -> premio. Se rehace al recargar. */
    private final Map<String, Map<String, String>> premiosPorItem = new ConcurrentHashMap<>();
    private PacketAdapter escucha;

    Vitrina(CratesPlugin modulo) {
        this.modulo = modulo;
    }

    void encender() {
        this.modulo.getServer().getPluginManager().registerEvents(this, this.modulo);
        /* ProtocolLib pide el plugin de verdad, no el modulo (ver TooltipPlugin). */
        this.escucha = new PacketAdapter(this.modulo.core(), ListenerPriority.HIGHEST,
                PacketType.Play.Server.OPEN_WINDOW, PacketType.Play.Server.WINDOW_ITEMS,
                PacketType.Play.Server.SET_SLOT) {
            @Override
            public void onPacketSending(PacketEvent evento) {
                try {
                    alEnviar(evento);
                } catch (Throwable t) {
                    /* Un fallo aqui no puede cortar el paquete: se va el original. */
                    Vitrina.this.modulo.getLogger().warning("[crates] no se pudo pintar la vista previa: " + t);
                }
            }
        };
        ProtocolLibrary.getProtocolManager().addPacketListener(this.escucha);
    }

    void apagar() {
        if (this.escucha != null) {
            try {
                ProtocolLibrary.getProtocolManager().removePacketListener(this.escucha);
            } catch (Throwable ignored) {
                // ProtocolLib ya se fue: no queda nada que soltar.
            }
            this.escucha = null;
        }
        HandlerList.unregisterAll((Listener) this);
        this.vistas.clear();
    }

    void olvidarCajas() {
        this.premiosPorItem.clear();
        this.vistas.clear();
    }

    /* ------------------------------------------------------------------ */
    /* Hilo principal: que caja mira cada uno                              */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void alAbrirVista(CratePreviewOpenEvent e) {
        Player p = e.getPlayer();
        Caja caja = this.modulo.calidades().caja(e.getCrateType().getIdentifier());
        if (caja == null) {
            this.vistas.remove(p.getUniqueId());
            return;
        }
        Map<String, String> indice = this.premiosPorItem.computeIfAbsent(caja.id(),
                k -> indice(e.getCrateType()));
        this.vistas.put(p.getUniqueId(), new Vista(caja.id(), lineas(p, caja, e.getCrateType(), indice)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void alCerrar(InventoryCloseEvent e) {
        Vista v = this.vistas.get(e.getPlayer().getUniqueId());
        /* Solo si la que se cierra es ya la vista previa: al abrirla desde otro
         * menu, Bukkit cierra primero el anterior y eso no la debe borrar. */
        if (v != null && v.ventana != -1) this.vistas.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        this.vistas.remove(e.getPlayer().getUniqueId());
    }

    /** material + nombre de cada premio de la caja. Los repetidos no se pintan. */
    private Map<String, String> indice(CrateType tipo) {
        Map<String, String> fuera = new HashMap<>();
        Set<String> repetidos = new HashSet<>();
        for (Reward r : tipo.getRegisteredRewards()) {
            if (r.getDisplayItem() == null) continue;
            String clave;
            try {
                clave = clave(r.getDisplayItem().build());
            } catch (Throwable t) {
                continue;
            }
            if (clave == null) continue;
            if (fuera.putIfAbsent(clave, r.getIdentifier()) != null) repetidos.add(clave);
        }
        for (String k : repetidos) {
            this.modulo.getLogger().warning("[crates] En " + tipo.getIdentifier() + " hay varios premios con el mismo objeto y nombre ("
                    + k + "): su calidad no se pinta en la vista previa.");
            fuera.remove(k);
        }
        return fuera;
    }

    /** Las lineas de cada premio para este jugador, ya montadas. */
    private Map<String, List<Component>> lineas(Player p, Caja caja, CrateType tipo, Map<String, String> indice) {
        Calidades c = this.modulo.calidades();
        Map<String, Double> prob = Sorteo.probabilidades(caja, CratesPlugin.encendidos(tipo).keySet());
        Map<String, List<Component>> fuera = new HashMap<>();
        for (Map.Entry<String, String> e : indice.entrySet()) {
            Premio premio = caja.premio(e.getValue());
            Double x = premio == null ? null : prob.get(premio.id());
            if (x == null) continue;
            Calidad q = c.calidad(premio.calidad());
            List<Component> l = new ArrayList<>();
            l.add(Estilo.legado(c.lore().linea()
                    .replace("%color%", q.color())
                    .replace("%calidad%", q.nombre())
                    .replace("%probabilidad%", Sorteo.porcentaje(x))));
            if (q.unaVez()) {
                boolean tiene = this.modulo.unicos().tiene(p.getUniqueId(), caja.id(), premio.id());
                l.add(Estilo.legado(tiene ? c.lore().yaObtenido() : c.lore().unaVez()));
            }
            fuera.put(e.getKey(), l);
        }
        return fuera;
    }

    /* ------------------------------------------------------------------ */
    /* Hilo de red: los paquetes                                           */
    /* ------------------------------------------------------------------ */

    private void alEnviar(PacketEvent evento) {
        Vista v = this.vistas.get(evento.getPlayer().getUniqueId());
        if (v == null) return;
        PacketContainer p = evento.getPacket();
        PacketType tipo = evento.getPacketType();

        if (tipo == PacketType.Play.Server.OPEN_WINDOW) {
            if (v.ventana == -1 && System.currentTimeMillis() - v.creada < 3000) {
                v.ventana = p.getIntegers().read(0);
            } else if (v.ventana != -1) {
                /* Abrio otra ventana sin cerrar la vista (otro menu encima). */
                this.vistas.remove(evento.getPlayer().getUniqueId(), v);
            }
            return;
        }
        if (v.ventana == -1) return;

        if (tipo == PacketType.Play.Server.WINDOW_ITEMS) {
            if (p.getIntegers().read(0) != v.ventana) return;
            List<ItemStack> items = p.getItemListModifier().read(0);
            if (items == null) return;
            int arriba = Math.max(0, items.size() - INVENTARIO_JUGADOR);
            v.huecosArriba = arriba;
            List<ItemStack> copia = null;
            for (int i = 0; i < arriba; i++) {
                ItemStack nuevo = pintar(v, items.get(i));
                if (nuevo == null) continue;
                if (copia == null) copia = new ArrayList<>(items);
                copia.set(i, nuevo);
            }
            if (copia != null) {
                PacketContainer clon = p.deepClone();
                clon.getItemListModifier().write(0, copia);
                evento.setPacket(clon);
            }
            return;
        }

        if (tipo == PacketType.Play.Server.SET_SLOT) {
            var ints = p.getIntegers();
            if (ints.size() < 2 || ints.read(0) != v.ventana) return;
            /* containerId, stateId y el hueco; segun la version el hueco es int o short. */
            int hueco = ints.size() >= 3 ? ints.read(2) : p.getShorts().size() > 0 ? p.getShorts().read(0) : -1;
            if (v.huecosArriba < 0 || hueco < 0 || hueco >= v.huecosArriba) return;
            ItemStack nuevo = pintar(v, p.getItemModifier().read(0));
            if (nuevo != null) {
                PacketContainer clon = p.deepClone();
                clon.getItemModifier().write(0, nuevo);
                evento.setPacket(clon);
            }
        }
    }

    /** Una copia del item con la calidad en el lore, o null si no es un premio. */
    private ItemStack pintar(Vista v, ItemStack item) {
        String clave = clave(item);
        if (clave == null) return null;
        List<Component> extra = v.lineas.get(clave);
        if (extra == null) return null;

        ItemStack copia = item.clone();
        ItemMeta meta = copia.getItemMeta();
        if (meta == null) return null;
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.removeIf(this::esLineaDeCalidad);
        while (!lore.isEmpty() && PLANO.serialize(lore.get(lore.size() - 1)).isBlank()) {
            lore.remove(lore.size() - 1);
        }
        if (!lore.isEmpty()) lore.add(Component.empty());
        lore.addAll(extra);
        meta.lore(lore);
        copia.setItemMeta(meta);
        return copia;
    }

    /** Las lineas de calidad que ya traia el lore guardado, de sistemas anteriores. */
    private boolean esLineaDeCalidad(Component linea) {
        String s = PLANO.serialize(linea).trim();
        if (s.startsWith("Probabilidad") || s.startsWith("Dentro de la calidad") || s.startsWith("Your chance")) {
            return true;
        }
        if (s.startsWith("◆ ")) {
            String resto = s.substring(2);
            for (Calidad q : this.modulo.calidades().calidades()) {
                if (resto.startsWith(q.nombre())) return true;
            }
            for (String viejo : VIEJAS) {
                if (resto.startsWith(viejo)) return true;
            }
        }
        Calidades.Lore l = this.modulo.calidades().lore();
        String unaVez = PLANO.serialize(Estilo.legado(l.unaVez())).trim();
        String yaObtenido = PLANO.serialize(Estilo.legado(l.yaObtenido())).trim();
        return s.equals(unaVez) || s.equals(yaObtenido);
    }

    /** Nombres de calidad que han existido en los lores guardados. */
    private static final List<String> VIEJAS = List.of(
            "Común", "Raro", "Épico", "Mítico", "Legendario", "Único", "Inmortal");

    /** material + nombre visible sin formato; null si el item no tiene nombre. */
    private static String clave(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        Component nombre = meta.hasDisplayName() ? meta.displayName()
                : meta.hasItemName() ? meta.itemName() : null;
        if (nombre == null) return null;
        String plano = PLANO.serialize(nombre).trim();
        return plano.isEmpty() ? null : item.getType().name() + "|" + plano;
    }
}
