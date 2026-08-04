package net.zakiworld.anomaly.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.zakiworld.anomaly.AnomalyPlugin;
import net.zakiworld.anomaly.boss.Ability;
import net.zakiworld.anomaly.core.ActiveAnomaly;
import net.zakiworld.anomaly.core.AnomalyType;
import net.zakiworld.anomaly.core.Compat;
import net.zakiworld.anomaly.core.Fx;
import net.zakiworld.anomaly.drops.DropEntry;
import net.zakiworld.anomaly.drops.DropTable;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Todos los menus del plugin en una sola pantalla de mando.
 *
 * Pensados con el mismo criterio que el menu de Rip: marco negro, una sola idea por
 * casilla y la ultima linea del lore diciendo siempre que hace el clic. Nada de
 * pantallas donde haya que adivinar.
 */
public final class Menus implements Listener {

    /** Las 21 casillas utiles del cuerpo, iguales en todas las pantallas de rejilla. */
    private static final int[] BODY = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};

    private static final int[] FRAME = {
            0, 1, 2, 3, 5, 6, 7, 8,
            9, 17, 18, 26, 27, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44};

    private static final int SLOT_STATUS = 4;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_HELP = 53;

    private enum Screen {HUB, ANOMALIES, ABILITIES, DROPS, SETTINGS}

    private final AnomalyPlugin plugin;

    public Menus(AnomalyPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------- apertura

    public void openHub(Player player) {
        open(player, Screen.HUB, 0, plugin.selectedId(), false);
    }

    private void open(Player player, Screen screen, int page, String context, boolean placeMode) {
        Holder holder = new Holder(screen, page, context);
        holder.placeMode = placeMode;
        Inventory inv = Bukkit.createInventory(holder, 54, titleOf(screen, context, placeMode));
        holder.inventory = inv;
        render(inv, player, holder);
        player.openInventory(inv);
    }

    private Component titleOf(Screen screen, String context, boolean placeMode) {
        TextColor accent = accentOf(context);
        Component base = Component.text("✦ ", accent)
                .append(Component.text("ANOMALY", NamedTextColor.WHITE, TextDecoration.BOLD));
        String tail = switch (screen) {
            case HUB -> "  Panel";
            case ANOMALIES -> "  Anomalias";
            case ABILITIES -> "  Habilidades";
            case DROPS -> placeMode ? "  Botin · colocar" : "  Botin · ajustar";
            case SETTINGS -> "  Ajustes";
        };
        return base.append(Component.text(tail, accent));
    }

    private TextColor accentOf(String context) {
        AnomalyType type = plugin.registry().get(context);
        return type == null ? MenuUtil.GOLD : type.color();
    }

    // -------------------------------------------------------------------- dibujado

    private void render(Inventory inv, Player player, Holder holder) {
        inv.clear();
        MenuUtil.frame(inv, FRAME);
        inv.setItem(SLOT_STATUS, statusItem());
        for (int i = 45; i <= 53; i++) inv.setItem(i, MenuUtil.pane());

        switch (holder.screen) {
            case HUB -> renderHub(inv);
            case ANOMALIES -> renderAnomalies(inv);
            case ABILITIES -> renderAbilities(inv, holder);
            case DROPS -> renderDrops(inv, holder);
            case SETTINGS -> renderSettings(inv);
        }

        if (holder.screen != Screen.HUB) {
            inv.setItem(SLOT_BACK, MenuUtil.simple(Material.ARROW,
                    Component.text("◀ Volver al panel", NamedTextColor.YELLOW), List.of()));
        } else {
            inv.setItem(SLOT_BACK, MenuUtil.simple(Material.SPRUCE_DOOR,
                    Component.text("Cerrar", MenuUtil.SOFT), List.of()));
        }
        inv.setItem(SLOT_HELP, helpItem(holder.screen));
    }

    // ------------------------------------------------------------------------ hub

    private void renderHub(Inventory inv) {
        AnomalyType selected = plugin.selected();
        boolean active = plugin.manager().active();
        ActiveAnomaly live = plugin.manager().current();

        List<Component> tpLore = new ArrayList<>();
        if (live == null) {
            tpLore.add(MenuUtil.line("Te lleva junto a la anomalia abierta."));
            tpLore.add(MenuUtil.blank());
            tpLore.add(Component.text("No hay ninguna abierta ahora mismo.", MenuUtil.DIM));
        } else {
            tpLore.add(MenuUtil.line("Te deja a unos bloques del jefe,"));
            tpLore.add(MenuUtil.line("en suelo firme y mirando hacia el."));
            tpLore.add(MenuUtil.blank());
            tpLore.add(MenuUtil.field("Anomalia", live.type().display(), live.type().color()));
            tpLore.add(MenuUtil.field("Coordenadas", live.where().getBlockX() + "  "
                    + live.where().getBlockY() + "  " + live.where().getBlockZ(), NamedTextColor.WHITE));
            tpLore.add(MenuUtil.blank());
            tpLore.add(MenuUtil.action("Click para viajar alli"));
        }
        inv.setItem(13, MenuUtil.icon(live == null ? Material.GRAY_DYE : Material.ENDER_PEARL,
                MenuUtil.title("Ir a la anomalia", live == null ? MenuUtil.DIM : NamedTextColor.LIGHT_PURPLE),
                tpLore, live != null));

        inv.setItem(20, MenuUtil.icon(active ? Material.GRAY_DYE : Material.NETHER_STAR,
                MenuUtil.title("Iniciar anomalia", active ? MenuUtil.DIM : NamedTextColor.GREEN),
                List.of(
                        MenuUtil.line("Abre la anomalia elegida en un punto"),
                        MenuUtil.line("valido del mapa y lo anuncia en el chat."),
                        MenuUtil.blank(),
                        MenuUtil.field("Elegida", selected == null ? "ninguna" : selected.display(),
                                selected == null ? MenuUtil.DIM : selected.color()),
                        MenuUtil.blank(),
                        active
                                ? Component.text("Ya hay una anomalia abierta.", NamedTextColor.RED)
                                : MenuUtil.action("Click para iniciar")),
                !active && selected != null));

        inv.setItem(22, MenuUtil.icon(selected == null ? Material.BARRIER : selected.icon(),
                MenuUtil.title("Elegir anomalia", MenuUtil.GOLD),
                List.of(
                        MenuUtil.line("El catalogo de anomalias del servidor."),
                        MenuUtil.blank(),
                        MenuUtil.field("Disponibles", plugin.registry().enabled().size() + " de "
                                + plugin.registry().all().size(), NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.action("Click para abrir el catalogo")),
                false));

        DropTable table = selected == null ? null : plugin.drops().table(selected.id());
        inv.setItem(24, MenuUtil.icon(Material.CHEST,
                MenuUtil.title("Botin", MenuUtil.LOOT),
                List.of(
                        MenuUtil.line("Que suelta la anomalia al caer."),
                        MenuUtil.line("Se coloca arrastrando el objeto real,"),
                        MenuUtil.line("asi valen los items de MMOItems."),
                        MenuUtil.blank(),
                        table == null
                                ? Component.text("Elige una anomalia primero.", MenuUtil.DIM)
                                : MenuUtil.field("Objetos", table.entries().size() + " / " + DropTable.CAPACITY,
                                NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        table == null ? Component.text("Sin anomalia elegida.", MenuUtil.DIM)
                                : MenuUtil.action("Click para editar el botin")),
                false));

        inv.setItem(29, MenuUtil.icon(Material.COMPARATOR,
                MenuUtil.title("Ajustes", MenuUtil.GOLD),
                List.of(
                        MenuUtil.line("Distancias, tiempos, escalado por"),
                        MenuUtil.line("jugadores y anomalias automaticas."),
                        MenuUtil.blank(),
                        MenuUtil.field("Automaticas", plugin.settings().autoEnabled()
                                ? ("cada " + plugin.settings().autoIntervalMinutes() + " min") : "apagadas",
                                plugin.settings().autoEnabled() ? NamedTextColor.GREEN : MenuUtil.DIM),
                        MenuUtil.blank(),
                        MenuUtil.action("Click para abrir")),
                false));

        inv.setItem(31, MenuUtil.icon(active ? Material.BARRIER : Material.GRAY_DYE,
                MenuUtil.title("Detener anomalia", active ? NamedTextColor.RED : MenuUtil.DIM),
                List.of(
                        MenuUtil.line("Cierra la anomalia abierta y borra el jefe,"),
                        MenuUtil.line("sus esbirros y toda la decoracion."),
                        MenuUtil.line("No reparte botin."),
                        MenuUtil.blank(),
                        active ? MenuUtil.action("Click para cerrarla")
                                : Component.text("No hay ninguna abierta.", MenuUtil.DIM)),
                false));

        inv.setItem(33, MenuUtil.icon(Material.SPYGLASS,
                MenuUtil.title("Estado en vivo", NamedTextColor.AQUA),
                liveLore(), false));
    }

    private List<Component> liveLore() {
        List<Component> lore = new ArrayList<>();
        ActiveAnomaly ev = plugin.manager().current();
        if (ev == null) {
            lore.add(Component.text("No hay ninguna anomalia abierta.", MenuUtil.DIM));
            if (plugin.manager().searching()) {
                lore.add(MenuUtil.blank());
                lore.add(Component.text("Buscando sitio...", NamedTextColor.YELLOW));
            }
            return lore;
        }
        lore.add(MenuUtil.field("Anomalia", ev.type().display(), ev.type().color()));
        lore.add(MenuUtil.field("Donde", ev.where().getBlockX() + " " + ev.where().getBlockY()
                + " " + ev.where().getBlockZ(), NamedTextColor.WHITE));
        lore.add(MenuUtil.field("Mundo", ev.where().getWorld() == null ? "?" : ev.where().getWorld().getName(),
                MenuUtil.SOFT));
        if (ev.fight() != null) {
            lore.add(MenuUtil.field("Fase", MenuUtil.romanPhase(ev.fight().phase()),
                    MenuUtil.phaseColor(ev.fight().phase())));
            lore.add(MenuUtil.field("Vida", ((int) (ev.fight().healthFraction() * 100)) + "%",
                    NamedTextColor.GREEN));
        }
        lore.add(MenuUtil.field("Peleando", String.valueOf(ev.participants()), NamedTextColor.WHITE));
        lore.add(MenuUtil.field("Abierta", ev.elapsedSeconds() + "s", MenuUtil.SOFT));
        return lore;
    }

    private ItemStack statusItem() {
        ActiveAnomaly ev = plugin.manager().current();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Panel de anomalias de Ederus", MenuUtil.SOFT));
        lore.add(MenuUtil.blank());
        lore.add(MenuUtil.field("Estado", ev == null ? "en calma" : "ANOMALIA ABIERTA",
                ev == null ? MenuUtil.SOFT : NamedTextColor.RED));
        lore.add(MenuUtil.field("Catalogo", plugin.registry().all().size() + " anomalias", NamedTextColor.WHITE));
        lore.add(MenuUtil.field("Protecciones", plugin.protection().hasWorldGuard()
                ? "WorldGuard enganchado" : "solo heuristica",
                plugin.protection().hasWorldGuard() ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        return MenuUtil.icon(ev == null ? Material.ENDER_EYE : Material.END_CRYSTAL,
                MenuUtil.title("ANOMALY", MenuUtil.GOLD), lore, ev != null);
    }

    // ------------------------------------------------------------------ anomalias

    private void renderAnomalies(Inventory inv) {
        List<AnomalyType> all = plugin.registry().all();
        String selected = plugin.selectedId();
        for (int i = 0; i < BODY.length; i++) {
            if (i >= all.size()) break;
            AnomalyType type = all.get(i);
            boolean enabled = plugin.registry().isEnabled(type);
            boolean chosen = type.id().equals(selected);

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(type.tagline(), MenuUtil.SOFT));
            lore.add(MenuUtil.blank());
            lore.add(Component.text("Elemento  ", MenuUtil.LABEL)
                    .append(Component.text(type.element().display(), type.element().color(), TextDecoration.BOLD))
                    .append(Component.text("   " + type.element().terrain(), MenuUtil.DIM)));
            lore.add(type.glowColor() == null
                    ? Component.text("Brillo  ", MenuUtil.LABEL)
                            .append(Component.text("ninguno, aparece por sorpresa", MenuUtil.DIM))
                    : Component.text("Brillo  ", MenuUtil.LABEL)
                            .append(Component.text("■ " + type.glowColor().toString(), type.glowColor())));
            lore.add(MenuUtil.blank());
            lore.add(Component.text("DE DONDE VIENE", NamedTextColor.WHITE, TextDecoration.BOLD));
            for (String s : type.origin()) lore.add(Component.text(s, MenuUtil.SOFT));
            lore.add(MenuUtil.blank());
            lore.add(MenuUtil.field("Vida base", String.valueOf((int) plugin.registry().health(type)),
                    NamedTextColor.GREEN));
            lore.add(MenuUtil.field("Habilidades", String.valueOf(type.abilities().size()), NamedTextColor.WHITE));
            lore.add(MenuUtil.field("Estado", enabled ? "activa" : "apagada",
                    enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
            lore.add(MenuUtil.blank());
            lore.add(Component.text("SUELTA", MenuUtil.LOOT, TextDecoration.BOLD));
            lore.add(plugin.drops().table(type.id()).summaryLine(MenuUtil.LOOT));
            lore.add(MenuUtil.blank());
            lore.add(chosen ? Component.text("✔ ELEGIDA", NamedTextColor.GREEN, TextDecoration.BOLD)
                    : MenuUtil.action("Click para elegirla"));
            lore.add(MenuUtil.actionSecondary("Click derecho: habilidades y vida del jefe"));
            lore.add(Component.text("► Shift + click: " + (enabled ? "apagarla" : "activarla"),
                    NamedTextColor.GRAY));

            inv.setItem(BODY[i], MenuUtil.icon(enabled ? type.icon() : Material.GRAY_DYE,
                    MenuUtil.title(type.display(), enabled ? type.color() : MenuUtil.DIM), lore, chosen));
        }
    }

    // ---------------------------------------------------------------- habilidades

    private void renderAbilities(Inventory inv, Holder holder) {
        AnomalyType type = plugin.registry().get(holder.context);
        if (type == null) return;
        List<Ability> abilities = type.abilities();
        int perPage = BODY.length;
        int pages = Math.max(1, (abilities.size() + perPage - 1) / perPage);
        int page = Math.floorMod(holder.page, pages);

        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= abilities.size()) break;
            Ability a = abilities.get(index);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("FASE " + MenuUtil.romanPhase(a.phase()),
                    MenuUtil.phaseColor(a.phase()), TextDecoration.BOLD));
            lore.add(MenuUtil.blank());
            lore.addAll(MenuUtil.wrap(a.description(), 38, MenuUtil.SOFT));
            lore.add(MenuUtil.blank());
            lore.add(MenuUtil.field("Dura", MenuUtil.seconds(a.castTicks()), NamedTextColor.AQUA));
            lore.add(MenuUtil.field("Enfriamiento", MenuUtil.seconds(a.cooldownTicks()), NamedTextColor.AQUA));
            lore.add(MenuUtil.field("Peso", String.valueOf(a.weight()), MenuUtil.SOFT));
            inv.setItem(BODY[i], MenuUtil.icon(a.icon(),
                    MenuUtil.title(a.display(), type.color()), lore, false));
        }

        inv.setItem(48, page > 0 ? MenuUtil.simple(Material.ARROW,
                Component.text("◀ Pagina anterior", NamedTextColor.YELLOW),
                List.of(Component.text("Pagina " + page + " de " + pages, MenuUtil.SOFT))) : MenuUtil.pane());
        inv.setItem(49, MenuUtil.icon(type.icon(), MenuUtil.title(type.display(), type.color()),
                List.of(
                        MenuUtil.field("Habilidades", String.valueOf(abilities.size()), NamedTextColor.WHITE),
                        MenuUtil.field("Elemento", type.element().display(), type.element().color()),
                        MenuUtil.field("Pagina", (page + 1) + " de " + pages, MenuUtil.SOFT)), false));
        inv.setItem(50, page < pages - 1 ? MenuUtil.simple(Material.SPECTRAL_ARROW,
                Component.text("Pagina siguiente ▶", NamedTextColor.YELLOW),
                List.of(Component.text("Pagina " + (page + 2) + " de " + pages, MenuUtil.SOFT))) : MenuUtil.pane());

        // Los dos ajustes de ESTA anomalia. Una casilla cada uno, con izquierda para
        // subir y derecha para bajar, igual que en la pantalla de Ajustes.
        double health = plugin.registry().health(type);
        inv.setItem(47, MenuUtil.icon(Material.GOLDEN_APPLE,
                MenuUtil.title("Vida del jefe", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Vida base", String.valueOf((int) health), NamedTextColor.GREEN),
                        MenuUtil.field("Con 5 jugadores",
                                String.valueOf((int) plugin.registry().scaledHealth(type, 5)), NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.line("Sube un " + Math.round(plugin.settings().healthPerPlayer() * 100)
                                + "% por cada jugador de mas."),
                        MenuUtil.line("Por encima de 1024 el resto se cobra bajandole"),
                        MenuUtil.line("el dano que recibe; para quien pelea es igual."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +100"),
                        Component.text("► Click derecho: -100", NamedTextColor.YELLOW),
                        Component.text("► Shift para pasos de 500", NamedTextColor.GRAY)), false));

        double dmg = plugin.registry().damageMultiplier(type);
        inv.setItem(51, MenuUtil.icon(Material.IRON_SWORD,
                MenuUtil.title("Dano de las habilidades", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Multiplicador", "x" + dmg,
                                dmg > 1.0 ? NamedTextColor.RED
                                        : dmg < 1.0 ? NamedTextColor.GREEN : NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.line("Afecta a TODAS las habilidades de esta"),
                        MenuUtil.line("anomalia a la vez. 1.0 es lo de diseno."),
                        MenuUtil.line("No toca el golpe cuerpo a cuerpo normal."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +0.1"),
                        Component.text("► Click derecho: -0.1", NamedTextColor.YELLOW),
                        Component.text("► Shift para pasos de 0.5", NamedTextColor.GRAY)), dmg != 1.0));
    }

    // ---------------------------------------------------------------------- botin

    private void renderDrops(Inventory inv, Holder holder) {
        AnomalyType type = plugin.registry().get(holder.context);
        if (type == null) return;
        DropTable table = plugin.drops().table(type.id());

        for (int i = 0; i < BODY.length; i++) {
            DropEntry entry = table.get(i);
            if (entry == null) {
                inv.setItem(BODY[i], holder.placeMode ? emptySlotHint() : null);
                continue;
            }
            List<Component> lore = new ArrayList<>();
            lore.add(MenuUtil.field("Probabilidad", DropTable.trimChance(entry.chance()) + "%",
                    entry.chance() >= 100 ? NamedTextColor.GREEN : MenuUtil.LOOT));
            lore.add(MenuUtil.field("Cantidad", entry.amountLabel(), NamedTextColor.WHITE));
            lore.add(MenuUtil.field("Para", entry.to().display(), NamedTextColor.AQUA));
            lore.add(Component.text("   " + entry.to().help(), MenuUtil.DIM));
            lore.add(MenuUtil.blank());
            if (holder.placeMode) {
                lore.add(MenuUtil.action("Click para quitarlo de la tabla"));
                lore.add(Component.text("► Con un objeto en el cursor: lo reemplaza", NamedTextColor.GRAY));
            } else {
                lore.add(MenuUtil.action("Click izquierdo: +5% de probabilidad"));
                lore.add(Component.text("► Click derecho: -5%", NamedTextColor.YELLOW));
                lore.add(Component.text("► Shift + izquierdo: cambiar a quien le toca", NamedTextColor.GRAY));
                lore.add(Component.text("► Shift + derecho: cambiar la cantidad", NamedTextColor.GRAY));
                lore.add(Component.text("► Tecla de tirar (Q): quitarlo", NamedTextColor.GRAY));
            }
            inv.setItem(BODY[i], MenuUtil.decorate(entry.item(), null, lore, false));
        }

        inv.setItem(46, MenuUtil.icon(holder.placeMode ? Material.HOPPER : Material.COMPARATOR,
                MenuUtil.title(holder.placeMode ? "Modo colocar" : "Modo ajustar",
                        holder.placeMode ? NamedTextColor.GREEN : NamedTextColor.AQUA),
                List.of(
                        MenuUtil.line(holder.placeMode
                                ? "Pon el objeto en el cursor y click en una casilla."
                                : "Ajusta probabilidad, cantidad y destinatario."),
                        MenuUtil.line(holder.placeMode
                                ? "Se guarda una COPIA: no pierdes tu objeto."
                                : "El objeto ya colocado no se toca."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click para cambiar de modo")), false));

        inv.setItem(48, MenuUtil.simple(Material.REDSTONE,
                Component.text("− Experiencia", NamedTextColor.RED),
                List.of(MenuUtil.line("Baja 100 puntos."))));
        inv.setItem(49, MenuUtil.icon(Material.EXPERIENCE_BOTTLE,
                MenuUtil.title("Experiencia", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Da", table.experience() + " puntos", NamedTextColor.GREEN),
                        MenuUtil.line("A cada participante, aparte del botin.")), false));
        inv.setItem(50, MenuUtil.simple(Material.GLOWSTONE_DUST,
                Component.text("+ Experiencia", NamedTextColor.GREEN),
                List.of(MenuUtil.line("Sube 100 puntos."))));

        List<Component> cmdLore = new ArrayList<>();
        cmdLore.add(MenuUtil.line("Comandos que corre la consola al caer el jefe."));
        cmdLore.add(MenuUtil.line("Se editan en drops.yml; %jugador% es el nombre."));
        cmdLore.add(MenuUtil.blank());
        if (table.commands().isEmpty()) {
            cmdLore.add(Component.text("Ninguno configurado.", MenuUtil.DIM));
        } else {
            for (String c : table.commands()) {
                cmdLore.add(Component.text("· " + (c.length() > 40 ? c.substring(0, 38) + "..." : c), MenuUtil.SOFT));
            }
        }
        inv.setItem(52, MenuUtil.icon(Material.COMMAND_BLOCK,
                MenuUtil.title("Comandos de recompensa", MenuUtil.GOLD), cmdLore, false));
    }

    private ItemStack emptySlotHint() {
        return MenuUtil.simple(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                Component.text("Casilla libre", MenuUtil.DIM),
                List.of(MenuUtil.line("Trae un objeto en el cursor y haz click"),
                        MenuUtil.line("para copiarlo a la tabla de botin.")));
    }

    // -------------------------------------------------------------------- ajustes

    private void renderSettings(Inventory inv) {
        inv.setItem(10, toggle(Material.CLOCK, "Anomalias automaticas", "automatico.activo", false,
                "Abre una anomalia sola cada cierto tiempo."));
        inv.setItem(11, number(Material.REPEATER, "Intervalo", "automatico.intervalo-minutos", 90,
                " min", "Cada cuanto se intenta abrir una."));
        inv.setItem(12, number(Material.PLAYER_HEAD, "Jugadores minimos", "automatico.jugadores-minimos", 2,
                "", "Por debajo de esto no se abre ninguna."));
        inv.setItem(14, number(Material.COMPASS, "Distancia minima", "general.distancia-minima", 200,
                " bloques", "Lo mas cerca que puede salir de un jugador."));
        inv.setItem(15, number(Material.RECOVERY_COMPASS, "Distancia maxima", "general.distancia-maxima", 1200,
                " bloques", "Lo mas lejos que puede salir."));
        inv.setItem(16, number(Material.BEACON, "Lejos del spawn", "general.distancia-minima-spawn", 300,
                " bloques", "Radio del spawn donde nunca aparecera."));

        inv.setItem(19, toggle(Material.OAK_DOOR, "Evitar bases sin claim", "general.evitar-bases", true,
                "Descarta sitios con cofres, camas u hornos cerca."));
        inv.setItem(20, number(Material.IRON_BARS, "Margen de proteccion", "general.margen-proteccion", 24,
                " bloques", "Distancia de respeto al borde de un claim."));
        inv.setItem(21, number(Material.GRASS_BLOCK, "Desnivel maximo", "general.desnivel-maximo", 4,
                " bloques", "Cuanto puede subir o bajar el terreno."));
        inv.setItem(23, number(Material.CLOCK, "Limite de combate", "combate.minutos-limite", 15,
                " min", "Si nadie la mata, se cierra sola."));
        inv.setItem(24, number(Material.TARGET, "Radio de participacion", "combate.radio-participacion", 64,
                " bloques", "Quien entra aqui ve la barra y cuenta para el botin."));
        inv.setItem(25, percent(Material.GOLDEN_APPLE, "Vida extra por jugador", "combate.vida-extra-por-jugador",
                0.15, "Cuanto sube la vida del jefe por cada jugador de mas."));

        inv.setItem(28, toggle(Material.PISTON, "Permitir empuje", "combate.permitir-empuje", true,
                "Si se apaga, ninguna habilidad movera a nadie."));
        inv.setItem(29, toggle(Material.PAPER, "Anuncio en el chat", "anuncio.activo", true,
                "El aviso con el hover y las coordenadas."));
        inv.setItem(30, toggle(Material.NOTE_BLOCK, "Sonido del anuncio", "anuncio.sonido", true,
                "Suena a todo el servidor al abrirse."));
        inv.setItem(31, toggle(Material.PAINTING, "Titulo en pantalla", "anuncio.titulo", true,
                "El cartel grande al abrirse la anomalia."));
        inv.setItem(32, number(Material.MAP, "Precision de coordenadas", "anuncio.precision-coordenadas", 1,
                " bloques", "Redondea el punto anunciado para dar margen."));
        inv.setItem(33, toggle(Material.BEACON, "Pilar de luz", "anuncio.pilar-de-luz", true,
                "Una columna del color de la anomalia sobre el jefe."));
    }

    private ItemStack toggle(Material material, String name, String path, boolean def, String help) {
        boolean value = plugin.settings().rawBool(path, def);
        return MenuUtil.icon(material, MenuUtil.title(name, MenuUtil.GOLD),
                List.of(
                        MenuUtil.line(help),
                        MenuUtil.blank(),
                        MenuUtil.field("Ahora", "", MenuUtil.SOFT).append(MenuUtil.state(value)),
                        MenuUtil.blank(),
                        MenuUtil.action("Click para cambiar")), value);
    }

    private ItemStack number(Material material, String name, String path, int def, String unit, String help) {
        int value = plugin.settings().rawInt(path, def);
        return MenuUtil.icon(material, MenuUtil.title(name, MenuUtil.GOLD),
                List.of(
                        MenuUtil.line(help),
                        MenuUtil.blank(),
                        MenuUtil.field("Ahora", value + unit, NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: subir"),
                        Component.text("► Click derecho: bajar", NamedTextColor.YELLOW),
                        Component.text("► Shift para pasos de 10", NamedTextColor.GRAY)), false);
    }

    private ItemStack percent(Material material, String name, String path, double def, String help) {
        double value = plugin.settings().raw(path, def);
        return MenuUtil.icon(material, MenuUtil.title(name, MenuUtil.GOLD),
                List.of(
                        MenuUtil.line(help),
                        MenuUtil.blank(),
                        MenuUtil.field("Ahora", "+" + Math.round(value * 100) + "% por jugador", NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: subir 5%"),
                        Component.text("► Click derecho: bajar 5%", NamedTextColor.YELLOW)), false);
    }

    private ItemStack helpItem(Screen screen) {
        List<Component> lore = new ArrayList<>();
        switch (screen) {
            case HUB -> {
                lore.add(MenuUtil.line("Todo el plugin se maneja desde aqui."));
                lore.add(MenuUtil.blank());
                lore.add(MenuUtil.field("Permiso", "anomaly.gui", MenuUtil.GOLD));
                lore.add(MenuUtil.line("Solo operadores o quien lo tenga."));
            }
            case ANOMALIES -> {
                lore.add(MenuUtil.line("Elige cual se abre al pulsar Iniciar"));
                lore.add(MenuUtil.line("y apaga las que no quieras que salgan solas."));
                lore.add(MenuUtil.blank());
                lore.add(MenuUtil.line("La vida del jefe se ajusta en su ficha:"));
                lore.add(MenuUtil.line("click derecho sobre la anomalia."));
            }
            case ABILITIES -> {
                lore.add(MenuUtil.line("Todo lo que sabe hacer esta anomalia,"));
                lore.add(MenuUtil.line("y abajo su vida base y el multiplicador"));
                lore.add(MenuUtil.line("de dano de todas sus habilidades."));
                lore.add(MenuUtil.line("Cada habilidad avisa antes de golpear:"));
                lore.add(MenuUtil.line("la marca en el suelo es la senal."));
            }
            case DROPS -> {
                lore.add(MenuUtil.line("El botin se guarda con el objeto entero,"));
                lore.add(MenuUtil.line("con su NBT, asi que los items de MMOItems"));
                lore.add(MenuUtil.line("caen exactamente igual que el original."));
                lore.add(MenuUtil.blank());
                lore.add(MenuUtil.line("Se guarda solo al cerrar el menu."));
            }
            case SETTINGS -> {
                lore.add(MenuUtil.line("Cada cambio se guarda al momento"));
                lore.add(MenuUtil.line("en config.yml."));
            }
        }
        return MenuUtil.icon(Material.BOOK, MenuUtil.title("Ayuda", MenuUtil.GOLD), lore, false);
    }

    // -------------------------------------------------------------------- escuchas

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);

        HumanEntity human = event.getWhoClicked();
        if (!(human instanceof Player player)) return;
        if (!plugin.mayUseGui(player)) {
            player.closeInventory();
            return;
        }

        int slot = event.getRawSlot();
        boolean inTop = slot >= 0 && slot < event.getInventory().getSize();

        // Colocar botin: copiar desde el inventario del jugador con shift.
        if (holder.screen == Screen.DROPS && holder.placeMode && !inTop && event.isShiftClick()) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && !clicked.getType().isAir()) {
                DropTable table = plugin.drops().table(holder.context);
                if (table.add(clicked)) {
                    click(player, 1.4f);
                    render(event.getInventory(), player, holder);
                } else {
                    deny(player, "La tabla ya esta llena.");
                }
            }
            return;
        }
        if (!inTop) return;

        if (slot == SLOT_BACK) {
            if (holder.screen == Screen.HUB) {
                player.closeInventory();
            } else {
                click(player, 0.9f);
                openHub(player);
            }
            return;
        }

        switch (holder.screen) {
            case HUB -> clickHub(player, slot);
            case ANOMALIES -> clickAnomalies(player, event, holder, slot);
            case ABILITIES -> clickAbilities(player, event, holder, slot);
            case DROPS -> clickDrops(player, event, holder, slot);
            case SETTINGS -> clickSettings(player, event, holder, slot);
        }
    }

    private void clickHub(Player player, int slot) {
        switch (slot) {
            case 13 -> {
                ActiveAnomaly live = plugin.manager().current();
                if (live == null) {
                    deny(player, "No hay ninguna anomalia abierta.");
                    return;
                }
                click(player, 1.5f);
                player.closeInventory();
                travelTo(player, live);
            }
            case 20 -> {
                AnomalyType type = plugin.selected();
                if (type == null) {
                    deny(player, "Elige una anomalia primero.");
                    return;
                }
                if (plugin.manager().active()) {
                    deny(player, "Ya hay una anomalia abierta.");
                    return;
                }
                click(player, 1.6f);
                player.closeInventory();
                player.sendMessage(plugin.prefix().append(
                        Component.text("Buscando un sitio libre para la anomalia...", MenuUtil.SOFT)));
                plugin.manager().start(type, ok -> {
                    if (!ok) {
                        player.sendMessage(plugin.prefix().append(Component.text(
                                "No se encontro ningun sitio valido. Prueba a bajar la distancia minima "
                                        + "o el margen de proteccion en Ajustes.", NamedTextColor.RED)));
                    }
                });
            }
            case 22 -> {
                click(player, 1.1f);
                open(player, Screen.ANOMALIES, 0, plugin.selectedId(), false);
            }
            case 24 -> {
                AnomalyType type = plugin.selected();
                if (type == null) {
                    deny(player, "Elige una anomalia primero.");
                    return;
                }
                click(player, 1.1f);
                open(player, Screen.DROPS, 0, type.id(), true);
            }
            case 29 -> {
                click(player, 1.1f);
                open(player, Screen.SETTINGS, 0, plugin.selectedId(), false);
            }
            case 31 -> {
                if (!plugin.manager().active()) {
                    deny(player, "No hay ninguna anomalia abierta.");
                    return;
                }
                click(player, 0.7f);
                plugin.manager().stop(false);
                openHub(player);
            }
            case 33 -> {
                click(player, 1.2f);
                openHub(player);
            }
            default -> {
            }
        }
    }

    private void clickAnomalies(Player player, InventoryClickEvent event, Holder holder, int slot) {
        int index = indexOf(BODY, slot);
        if (index < 0) return;
        List<AnomalyType> all = plugin.registry().all();
        if (index >= all.size()) return;
        AnomalyType type = all.get(index);

        if (event.isShiftClick()) {
            plugin.registry().setEnabled(type, !plugin.registry().isEnabled(type));
            click(player, plugin.registry().isEnabled(type) ? 1.5f : 0.7f);
        } else if (event.isRightClick()) {
            click(player, 1.2f);
            open(player, Screen.ABILITIES, 0, type.id(), false);
            return;
        } else {
            plugin.selectedId(type.id());
            click(player, 1.6f);
            player.sendMessage(plugin.prefix()
                    .append(Component.text("Anomalia elegida  ", NamedTextColor.GREEN))
                    .append(Component.text(type.display(), type.color(), TextDecoration.BOLD)));
        }
        render(event.getInventory(), player, holder);
    }

    private void clickAbilities(Player player, InventoryClickEvent event, Holder holder, int slot) {
        AnomalyType type = plugin.registry().get(holder.context);
        if (type == null) return;
        int pages = Math.max(1, (type.abilities().size() + BODY.length - 1) / BODY.length);
        if (slot == 48 && holder.page > 0) {
            click(player, 1.0f);
            open(player, Screen.ABILITIES, holder.page - 1, holder.context, false);
            return;
        }
        if (slot == 50 && holder.page < pages - 1) {
            click(player, 1.1f);
            open(player, Screen.ABILITIES, holder.page + 1, holder.context, false);
            return;
        }
        boolean up = event.isLeftClick();
        if (slot == 47) {
            int step = (event.isShiftClick() ? 500 : 100) * (up ? 1 : -1);
            plugin.registry().setHealth(type, plugin.registry().health(type) + step);
            click(player, up ? 1.4f : 0.9f);
            player.sendActionBar(Component.text("Vida base de " + type.display() + "  ", MenuUtil.SOFT)
                    .append(Component.text((int) plugin.registry().health(type), NamedTextColor.GREEN,
                            TextDecoration.BOLD)));
            render(event.getInventory(), player, holder);
            return;
        }
        if (slot == 51) {
            double step = (event.isShiftClick() ? 0.5 : 0.1) * (up ? 1 : -1);
            plugin.registry().setDamageMultiplier(type, plugin.registry().damageMultiplier(type) + step);
            click(player, up ? 1.4f : 0.9f);
            player.sendActionBar(Component.text("Dano de " + type.display() + "  ", MenuUtil.SOFT)
                    .append(Component.text("x" + plugin.registry().damageMultiplier(type),
                            NamedTextColor.GOLD, TextDecoration.BOLD)));
            render(event.getInventory(), player, holder);
        }
    }

    private void clickDrops(Player player, InventoryClickEvent event, Holder holder, int slot) {
        DropTable table = plugin.drops().table(holder.context);

        if (slot == 46) {
            click(player, 1.2f);
            open(player, Screen.DROPS, 0, holder.context, !holder.placeMode);
            return;
        }
        if (slot == 48 || slot == 50) {
            table.experience(table.experience() + (slot == 50 ? 100 : -100));
            click(player, slot == 50 ? 1.4f : 0.8f);
            plugin.drops().save();
            render(event.getInventory(), player, holder);
            return;
        }

        int index = indexOf(BODY, slot);
        if (index < 0) return;

        if (holder.placeMode) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                // Se guarda una COPIA: el objeto del cursor sigue siendo del jugador.
                ItemStack copy = cursor.clone();
                copy.setAmount(1);
                DropEntry existing = table.get(index);
                if (existing != null) {
                    existing.item(copy);
                    existing.amount(cursor.getAmount(), cursor.getAmount());
                } else if (!table.add(copy)) {
                    deny(player, "La tabla ya esta llena.");
                    return;
                }
                click(player, 1.5f);
            } else {
                DropEntry existing = table.get(index);
                if (existing == null) return;
                table.remove(index);
                click(player, 0.7f);
            }
        } else {
            DropEntry entry = table.get(index);
            if (entry == null) return;
            if (event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP) {
                table.remove(index);
                click(player, 0.7f);
            } else if (event.isShiftClick() && event.isLeftClick()) {
                entry.cycleRecipient();
                click(player, 1.3f);
            } else if (event.isShiftClick() && event.isRightClick()) {
                cycleAmount(entry);
                click(player, 1.3f);
            } else if (event.isLeftClick()) {
                entry.chance(entry.chance() + 5);
                click(player, 1.5f);
            } else if (event.isRightClick()) {
                entry.chance(entry.chance() - 5);
                click(player, 0.9f);
            }
        }
        plugin.drops().save();
        render(event.getInventory(), player, holder);
    }

    private static final int[][] AMOUNTS = {{1, 1}, {1, 2}, {1, 3}, {2, 4}, {3, 6}, {4, 8}, {8, 16}, {16, 32}, {32, 64}};

    private void cycleAmount(DropEntry entry) {
        int current = 0;
        for (int i = 0; i < AMOUNTS.length; i++) {
            if (AMOUNTS[i][0] == entry.min() && AMOUNTS[i][1] == entry.max()) {
                current = i;
                break;
            }
        }
        int[] next = AMOUNTS[(current + 1) % AMOUNTS.length];
        entry.amount(next[0], next[1]);
    }

    private void clickSettings(Player player, InventoryClickEvent event, Holder holder, int slot) {
        int step = event.isShiftClick() ? 10 : 1;
        boolean up = event.isLeftClick();
        switch (slot) {
            case 10 -> {
                plugin.settings().toggle("automatico.activo", false);
                plugin.manager().restartScheduler();
            }
            case 11 -> plugin.settings().bumpInt("automatico.intervalo-minutos", up ? step * 5 : -step * 5, 5, 1440, 90);
            case 12 -> plugin.settings().bumpInt("automatico.jugadores-minimos", up ? step : -step, 0, 100, 2);
            case 14 -> plugin.settings().bumpInt("general.distancia-minima", up ? step * 25 : -step * 25, 0, 20000, 200);
            case 15 -> plugin.settings().bumpInt("general.distancia-maxima", up ? step * 25 : -step * 25, 32, 60000, 1200);
            case 16 -> plugin.settings().bumpInt("general.distancia-minima-spawn", up ? step * 25 : -step * 25, 0, 20000, 300);
            case 19 -> plugin.settings().toggle("general.evitar-bases", true);
            case 20 -> plugin.settings().bumpInt("general.margen-proteccion", up ? step * 4 : -step * 4, 0, 256, 24);
            case 21 -> plugin.settings().bumpInt("general.desnivel-maximo", up ? step : -step, 1, 32, 4);
            case 23 -> plugin.settings().bumpInt("combate.minutos-limite", up ? step : -step, 1, 180, 15);
            case 24 -> plugin.settings().bumpInt("combate.radio-participacion", up ? step * 8 : -step * 8, 16, 256, 64);
            case 25 -> plugin.settings().bump("combate.vida-extra-por-jugador", up ? 0.05 : -0.05, 0, 3, 0.15);
            case 28 -> plugin.settings().toggle("combate.permitir-empuje", true);
            case 29 -> plugin.settings().toggle("anuncio.activo", true);
            case 30 -> plugin.settings().toggle("anuncio.sonido", true);
            case 31 -> plugin.settings().toggle("anuncio.titulo", true);
            case 32 -> plugin.settings().bumpInt("anuncio.precision-coordenadas", up ? step : -step, 1, 256, 1);
            case 33 -> plugin.settings().toggle("anuncio.pilar-de-luz", true);
            default -> {
                return;
            }
        }
        click(player, up ? 1.4f : 0.9f);
        render(event.getInventory(), player, holder);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder holder && holder.screen == Screen.DROPS) {
            plugin.drops().save();
        }
    }

    // ------------------------------------------------------------------ utilidades

    /**
     * Deja al jugador a unos bloques del jefe, en suelo firme y mirandolo.
     *
     * No se le teletransporta encima a proposito: caer dentro del alcance de un jefe
     * que ya esta peleando es una muerte gratis, y ademas se perderia la entrada.
     */
    private void travelTo(Player player, ActiveAnomaly live) {
        Location target = live.fight() != null && live.fight().alive()
                ? live.fight().loc() : live.where();
        if (target.getWorld() == null) {
            deny(player, "El mundo de la anomalia ya no esta cargado.");
            return;
        }

        Location spot = null;
        for (int i = 0; i < 8 && spot == null; i++) {
            double a = Math.PI * 2 * i / 8.0;
            Location probe = Fx.ground(target.clone().add(Math.cos(a) * 10, 2, Math.sin(a) * 10), 6);
            Block floor = probe.getBlock().getRelative(0, -1, 0);
            if (!floor.getType().isSolid() || floor.isLiquid()) continue;
            if (probe.getBlock().getType().isSolid()) continue;
            if (probe.getBlock().getRelative(0, 1, 0).getType().isSolid()) continue;
            spot = probe;
        }
        if (spot == null) spot = target.clone().add(0, 1, 0);

        // Que mire hacia el jefe, para no aparecer de espaldas al combate.
        Vector look = target.toVector().subtract(spot.toVector());
        if (look.lengthSquared() > 0.01) spot.setDirection(look);

        player.teleport(spot);
        Compat.sound(player.getWorld(), spot, "entity.enderman.teleport", 0.9f, 1.1f);
        player.sendMessage(plugin.prefix()
                .append(Component.text("Te dejo junto a ", MenuUtil.SOFT))
                .append(Component.text(live.type().display(), live.type().color(), TextDecoration.BOLD))
                .append(Component.text(".", MenuUtil.SOFT)));
    }

    private static int indexOf(int[] slots, int slot) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) return i;
        }
        return -1;
    }

    private void click(Player player, float pitch) {
        Compat.sound(player.getWorld(), player.getLocation(), "ui.button.click", 0.55f, pitch);
    }

    private void deny(Player player, String reason) {
        Compat.sound(player.getWorld(), player.getLocation(), "entity.villager.no", 0.8f, 0.9f);
        player.sendMessage(plugin.prefix().append(Component.text(reason, NamedTextColor.RED)));
    }

    /** Identifica el menu y guarda en que pantalla y contexto esta. */
    private static final class Holder implements InventoryHolder {
        final Screen screen;
        final int page;
        final String context;
        boolean placeMode;
        Inventory inventory;

        Holder(Screen screen, int page, String context) {
            this.screen = screen;
            this.page = page;
            this.context = context;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
