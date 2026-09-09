package net.ederus.edm.anomaly.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.edm.anomaly.AnomalyPlugin;
import net.ederus.edm.anomaly.boss.Ability;
import net.ederus.edm.anomaly.core.ActiveAnomaly;
import net.ederus.edm.anomaly.core.AnomalyType;
import net.ederus.edm.anomaly.core.Compat;
import net.ederus.edm.anomaly.core.Fx;
import net.ederus.edm.anomaly.drops.DropEntry;
import net.ederus.edm.anomaly.drops.DropTable;
import net.ederus.edm.anomaly.minions.MinionAbility;
import net.ederus.edm.anomaly.minions.MinionCategory;
import net.ederus.edm.anomaly.minions.MinionSpawner;
import net.ederus.edm.anomaly.minions.MinionType;
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

    private enum Screen {HUB, ANOMALIES, ABILITIES, DROPS, SETTINGS,
        MINION_CATEGORIES, CATEGORY_EDIT, MINIONS, MINION_EDIT, MINION_ABILITIES, SPAWNERS, SPAWNER_EDIT}

    private final AnomalyPlugin plugin;

    public Menus(AnomalyPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------- apertura

    public void openHub(Player player) {
        // Abrir el menu cancela el modo de marcar punto, si estaba puesto: es la
        // forma natural de decir "mejor no".
        plugin.spawnMarker().cancel(player);
        open(player, Screen.HUB, 0, plugin.selectedId(), false);
    }

    /**
     * La puerta de /esb: las CARPETAS de esbirros. Los jefes viven en /anomaly y la
     * tropa aqui; mezclarlos en un solo panel se hacia largo de recorrer.
     */
    public void openMinions(Player player) {
        plugin.spawnMarker().cancel(player);
        open(player, Screen.MINION_CATEGORIES, 0, "", false);
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
            case MINION_CATEGORIES -> "  Esbirros";
            case CATEGORY_EDIT -> "  Carpeta";
            case MINIONS -> {
                MinionCategory cat = plugin.minions().category(context);
                yield cat == null ? "  Esbirros" : "  " + cat.display();
            }
            case MINION_EDIT -> "  Esbirro";
            case MINION_ABILITIES -> "  Habilidades";
            case SPAWNERS -> "  Generadores";
            case SPAWNER_EDIT -> "  Generador";
        };
        return base.append(Component.text(tail, accent));
    }

    private TextColor accentOf(String context) {
        AnomalyType type = plugin.registry().get(context);
        if (type != null) return type.color();
        MinionType minion = minionOf(context);
        if (minion != null) return minion.color();
        MinionCategory cat = plugin.minions().category(context);
        return cat == null ? MenuUtil.GOLD : cat.color();
    }

    /** El esbirro detras de un contexto, venga como id o como id de tabla de botin. */
    private MinionType minionOf(String context) {
        if (context == null) return null;
        MinionType direct = plugin.minions().type(context);
        if (direct != null) return direct;
        if (context.startsWith("esbirro-")) return plugin.minions().type(context.substring("esbirro-".length()));
        return null;
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
            case MINION_CATEGORIES -> renderCategories(inv);
            case CATEGORY_EDIT -> renderCategoryEdit(inv, holder);
            case MINIONS -> renderMinions(inv, holder);
            case MINION_EDIT -> renderMinionEdit(inv, holder);
            case MINION_ABILITIES -> renderMinionAbilities(inv, holder);
            case SPAWNERS -> renderSpawners(inv, holder);
            case SPAWNER_EDIT -> renderSpawnerEdit(inv, holder);
        }

        if (holder.screen != Screen.HUB) {
            String backLabel = switch (holder.screen) {
                case MINION_CATEGORIES -> "◀ Cerrar";
                case CATEGORY_EDIT -> "◀ Volver a las carpetas";
                case MINIONS -> "◀ Volver a las carpetas";
                case MINION_EDIT -> "◀ Volver a la carpeta";
                case MINION_ABILITIES -> "◀ Volver a la ficha";
                case SPAWNERS -> "◀ Volver a la ficha";
                case SPAWNER_EDIT -> "◀ Volver a los generadores";
                case DROPS -> minionOf(holder.context) != null ? "◀ Volver a la ficha" : "◀ Volver al panel";
                default -> "◀ Volver al panel";
            };
            inv.setItem(SLOT_BACK, MenuUtil.simple(Material.ARROW,
                    Component.text(backLabel, NamedTextColor.YELLOW), List.of()));
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

        Location fixedSpawn = selected == null ? null : plugin.registry().spawnPoint(selected);
        inv.setItem(20, MenuUtil.icon(active ? Material.GRAY_DYE : Material.NETHER_STAR,
                MenuUtil.title("Iniciar anomalia", active ? MenuUtil.DIM : NamedTextColor.GREEN),
                List.of(
                        fixedSpawn == null
                                ? MenuUtil.line("Abre la anomalia elegida en un punto")
                                : MenuUtil.line("Abre la anomalia elegida en su punto"),
                        fixedSpawn == null
                                ? MenuUtil.line("valido del mapa y lo anuncia en el chat.")
                                : MenuUtil.line("marcado y lo anuncia en el chat."),
                        MenuUtil.blank(),
                        MenuUtil.field("Elegida", selected == null ? "ninguna" : selected.display(),
                                selected == null ? MenuUtil.DIM : selected.color()),
                        MenuUtil.field("Aparece", fixedSpawn == null ? "en un sitio aleatorio"
                                        : fixedSpawn.getBlockX() + " " + fixedSpawn.getBlockY() + " "
                                        + fixedSpawn.getBlockZ(),
                                fixedSpawn == null ? MenuUtil.SOFT : NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        active
                                ? Component.text("Ya hay una anomalia abierta.", NamedTextColor.RED)
                                : MenuUtil.action("Click para iniciar")),
                !active && selected != null));

        inv.setItem(15, spawnPointItem(selected, fixedSpawn));

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

    /**
     * El boton del punto de aparicion: marca un bloque como spawner de la anomalia
     * elegida, o la devuelve al modo aleatorio. Marcar SOLO marca; iniciar sigue
     * siendo cosa del boton Iniciar.
     */
    private ItemStack spawnPointItem(AnomalyType selected, Location fixed) {
        List<Component> lore = new ArrayList<>();
        lore.add(MenuUtil.line("Donde aparece la anomalia elegida al"));
        lore.add(MenuUtil.line("pulsar Iniciar: un bloque marcado por ti"));
        lore.add(MenuUtil.line("(tu coliseo) o un sitio aleatorio del mapa."));
        lore.add(MenuUtil.blank());
        if (selected == null) {
            lore.add(Component.text("Elige una anomalia primero.", MenuUtil.DIM));
        } else if (fixed == null) {
            lore.add(MenuUtil.field("Ahora", "aleatorio, lo busca el plugin", MenuUtil.SOFT));
            lore.add(MenuUtil.blank());
            lore.add(MenuUtil.action("Click: salir y marcar un bloque a golpe"));
        } else {
            lore.add(MenuUtil.field("Punto fijo", fixed.getBlockX() + " " + fixed.getBlockY() + " "
                    + fixed.getBlockZ(), NamedTextColor.WHITE));
            lore.add(MenuUtil.field("Mundo", fixed.getWorld() == null ? "?" : fixed.getWorld().getName(),
                    MenuUtil.SOFT));
            lore.add(MenuUtil.blank());
            lore.add(MenuUtil.action("Click: marcar un bloque nuevo"));
            lore.add(Component.text("► Click derecho: volver a aleatorio", NamedTextColor.YELLOW));
        }
        return MenuUtil.icon(fixed == null ? Material.COMPASS : Material.LODESTONE,
                MenuUtil.title("Punto de aparicion", fixed == null ? MenuUtil.GOLD : NamedTextColor.GREEN),
                lore, fixed != null);
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

            net.ederus.edm.anomaly.core.AnomalyClass clazz = plugin.registry().classOf(type);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(type.tagline(), MenuUtil.SOFT));
            lore.add(MenuUtil.blank());
            lore.add(Component.text("Clase  ", MenuUtil.LABEL)
                    .append(Component.text(clazz.display().toUpperCase(java.util.Locale.ROOT),
                            clazz.color(), TextDecoration.BOLD)));
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
            for (String s : plugin.registry().origin(type)) lore.add(Component.text(s, MenuUtil.SOFT));
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
            lore.add(Component.text("► Shift + izquierdo: " + (enabled ? "apagarla" : "activarla"),
                    NamedTextColor.GRAY));
            lore.add(Component.text("► Shift + derecho: cambiar de clase", NamedTextColor.GRAY));

            // Si la anomalia trae un icono con forma propia (una cabeza con skin), se
            // respeta; apagada siempre va en gris, que es lo que dice que esta apagada.
            org.bukkit.inventory.ItemStack custom = enabled ? type.iconItem() : null;
            inv.setItem(BODY[i], custom != null
                    ? MenuUtil.icon(custom,
                            MenuUtil.title(type.display(), type.color()), lore, chosen)
                    : MenuUtil.icon(enabled ? type.icon() : Material.GRAY_DYE,
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
        double times = plugin.registry().healthTimes(type);
        inv.setItem(47, MenuUtil.icon(Material.GOLDEN_APPLE,
                MenuUtil.title("Vida del jefe", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Vida base", (int) health + "  (x" + times + " de lo original)",
                                NamedTextColor.GREEN),
                        MenuUtil.field("Con 5 jugadores",
                                String.valueOf((int) plugin.registry().scaledHealth(type, 5)), NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.line("Sube un " + Math.round(plugin.settings().healthPerPlayer() * 100)
                                + "% por cada jugador de mas. Admite hasta"),
                        MenuUtil.line("x20 largos de la vida original del jefe."),
                        MenuUtil.line("Por encima de 1024 el resto se cobra bajandole"),
                        MenuUtil.line("el dano que recibe; para quien pelea es igual."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +500"),
                        Component.text("► Click derecho: -500", NamedTextColor.YELLOW),
                        Component.text("► Shift para pasos de 5000", NamedTextColor.GRAY)), times >= 2));

        double dmg = plugin.registry().damageMultiplier(type);
        inv.setItem(51, MenuUtil.icon(Material.IRON_SWORD,
                MenuUtil.title("Dano de las habilidades", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Multiplicador", "x" + dmg + "  (hasta x20)",
                                dmg > 1.0 ? NamedTextColor.RED
                                        : dmg < 1.0 ? NamedTextColor.GREEN : NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.line("Afecta a TODAS las habilidades de esta"),
                        MenuUtil.line("anomalia a la vez. 1.0 es lo de diseno."),
                        MenuUtil.line("No toca el golpe cuerpo a cuerpo normal."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +0.1"),
                        Component.text("► Click derecho: -0.1", NamedTextColor.YELLOW),
                        Component.text("► Shift para pasos de 1.0", NamedTextColor.GRAY)), dmg != 1.0));
    }

    // ---------------------------------------------------------------------- botin

    private void renderDrops(Inventory inv, Holder holder) {
        // La misma pantalla sirve para el botin de un jefe y el de un esbirro: el
        // contexto es el id de la tabla. En un esbirro no hay "para quien": el botin
        // cae al suelo como el de cualquier mob, asi que ese campo no se ensena.
        boolean minionTable = minionOf(holder.context) != null;
        DropTable table = plugin.drops().table(holder.context);

        for (int i = 0; i < BODY.length; i++) {
            DropEntry entry = table.get(i);
            if (entry == null) {
                inv.setItem(BODY[i], holder.placeMode ? emptySlotHint() : null);
                continue;
            }
            List<Component> lore = new ArrayList<>();
            if (entry.unique()) {
                lore.add(Component.text("✦ OBJETO UNICO", NamedTextColor.AQUA, TextDecoration.BOLD));
                lore.add(Component.text("   Brilla al caer y el chat anuncia quien se lo llevo.", MenuUtil.DIM));
            }
            lore.add(MenuUtil.field("Probabilidad", DropTable.trimChance(entry.chance()) + "%",
                    entry.chance() >= 100 ? NamedTextColor.GREEN : MenuUtil.LOOT));
            lore.add(MenuUtil.field("Cantidad", entry.amountLabel(), NamedTextColor.WHITE));
            if (!minionTable) {
                lore.add(MenuUtil.field("Para", entry.to().display(), NamedTextColor.AQUA));
                lore.add(Component.text("   " + entry.to().help(), MenuUtil.DIM));
            }
            lore.add(MenuUtil.blank());
            if (holder.placeMode) {
                lore.add(MenuUtil.action("Click para quitarlo de la tabla"));
                lore.add(Component.text("► Con un objeto en el cursor: lo reemplaza", NamedTextColor.GRAY));
                lore.add(Component.text("► Shift + click: marcarlo como UNICO", NamedTextColor.GRAY));
            } else {
                lore.add(MenuUtil.action("Click izquierdo: +5% de probabilidad"));
                lore.add(Component.text("► Click derecho: -5%", NamedTextColor.YELLOW));
                if (!minionTable) {
                    lore.add(Component.text("► Shift + izquierdo: cambiar a quien le toca", NamedTextColor.GRAY));
                }
                lore.add(Component.text("► Shift + derecho: cambiar la cantidad", NamedTextColor.GRAY));
                lore.add(Component.text("► Tecla F (o click central): marcarlo como UNICO", NamedTextColor.GRAY));
                lore.add(Component.text("► Tecla de tirar (Q): quitarlo", NamedTextColor.GRAY));
            }
            // La cantidad se ve desde el propio inventario: el stack pinta el numero
            // que cae de verdad (el maximo de la horquilla), no hay que leer el lore.
            ItemStack shown = entry.item().clone();
            shown.setAmount(Math.max(1, Math.min(64, entry.max())));
            inv.setItem(BODY[i], MenuUtil.decorate(shown, null, lore, entry.unique()));
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
                List.of(MenuUtil.line("Baja 100 puntos."),
                        Component.text("► Con shift: 1000", NamedTextColor.GRAY))));
        inv.setItem(49, MenuUtil.icon(Material.EXPERIENCE_BOTTLE,
                MenuUtil.title("Experiencia", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Da", table.experience() + " puntos", NamedTextColor.GREEN),
                        MenuUtil.line("A cada participante, aparte del botin."),
                        MenuUtil.line("Admite hasta 1.000.000 por jefe.")), false));
        inv.setItem(50, MenuUtil.simple(Material.GLOWSTONE_DUST,
                Component.text("+ Experiencia", NamedTextColor.GREEN),
                List.of(MenuUtil.line("Sube 100 puntos."),
                        Component.text("► Con shift: 1000", NamedTextColor.GRAY))));

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

    // ------------------------------------------------------------------- esbirros

    /**
     * La portada de /esb: las carpetas. Una mazmorra o un proposito por casilla,
     * con el icono que le haya puesto el admin.
     */
    private void renderCategories(Inventory inv) {
        List<MinionCategory> all = plugin.minions().categories();
        for (int i = 0; i < BODY.length; i++) {
            if (i >= all.size()) break;
            MinionCategory cat = all.get(i);
            List<MinionType> dentro = plugin.minions().typesOf(cat.id());
            int generadores = 0;
            for (MinionType t : dentro) generadores += plugin.minions().spawnersOf(t.id()).size();

            List<Component> lore = new ArrayList<>();
            lore.add(MenuUtil.field("Esbirros", String.valueOf(dentro.size()), NamedTextColor.WHITE));
            lore.add(MenuUtil.field("Generadores", String.valueOf(generadores), NamedTextColor.WHITE));
            if (!dentro.isEmpty()) {
                lore.add(MenuUtil.blank());
                for (int k = 0; k < Math.min(5, dentro.size()); k++) {
                    MinionType t = dentro.get(k);
                    lore.add(Component.text("· ", MenuUtil.DIM).append(Component.text(t.display(), t.color())));
                }
                if (dentro.size() > 5) {
                    lore.add(Component.text("  y " + (dentro.size() - 5) + " mas", MenuUtil.DIM));
                }
            }
            lore.add(MenuUtil.blank());
            lore.add(MenuUtil.action("Click para abrir la carpeta"));
            lore.add(MenuUtil.actionSecondary("Click derecho: icono, color y nombre"));

            inv.setItem(BODY[i], MenuUtil.icon(cat.icon(),
                    MenuUtil.title(cat.display(), cat.color()), lore, false));
        }

        inv.setItem(49, MenuUtil.icon(Material.WRITABLE_BOOK,
                MenuUtil.title("Crear carpeta", NamedTextColor.GREEN),
                List.of(
                        MenuUtil.line("Una mazmorra o un proposito nuevo:"),
                        MenuUtil.line("Mina, Cripta, Test, lo que sea."),
                        MenuUtil.line("Se cierra el menu y el nombre se"),
                        MenuUtil.line("escribe en el chat."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click para ponerle nombre")),
                true));
    }

    /** La ficha de una carpeta: como se llama, como se ve y que hay dentro. */
    private void renderCategoryEdit(Inventory inv, Holder holder) {
        MinionCategory cat = plugin.minions().category(holder.context);
        if (cat == null) return;
        List<MinionType> dentro = plugin.minions().typesOf(cat.id());

        inv.setItem(11, MenuUtil.icon(Material.NAME_TAG,
                MenuUtil.title("Renombrar", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Ahora", cat.display(), cat.color()),
                        MenuUtil.blank(),
                        MenuUtil.line("Se cierra el menu y el nombre nuevo"),
                        MenuUtil.line("se escribe en el chat."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click para renombrar")), false));

        List<Component> ficha = new ArrayList<>();
        ficha.add(MenuUtil.field("Esbirros", String.valueOf(dentro.size()), NamedTextColor.WHITE));
        ficha.add(MenuUtil.field("Icono", nombreBonitoMaterial(cat.icon()), MenuUtil.SOFT));
        inv.setItem(13, MenuUtil.icon(cat.icon(), MenuUtil.title(cat.display(), cat.color()), ficha, true));

        inv.setItem(15, MenuUtil.icon(cat.icon(),
                MenuUtil.title("Icono", MenuUtil.GOLD),
                List.of(
                        MenuUtil.line("Como se reconoce la carpeta de un"),
                        MenuUtil.line("vistazo. Vale cualquier objeto."),
                        MenuUtil.blank(),
                        MenuUtil.field("Ahora", nombreBonitoMaterial(cat.icon()), NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.action("Coge un objeto y clicka aqui con el"),
                        Component.text("► Tecla de tirar (Q): escribir el nombre", NamedTextColor.GRAY)), false));

        inv.setItem(20, MenuUtil.icon(Material.BRUSH,
                MenuUtil.title("Color", cat.color()),
                List.of(
                        Component.text("Asi se ve  ", MenuUtil.LABEL)
                                .append(Component.text(cat.display(), cat.color(), TextDecoration.BOLD)),
                        MenuUtil.blank(),
                        MenuUtil.line("El color del titulo en los menus."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: siguiente color"),
                        Component.text("► Click derecho: anterior", NamedTextColor.YELLOW)), false));

        inv.setItem(24, MenuUtil.icon(Material.SPAWNER,
                MenuUtil.title("Abrir la carpeta", NamedTextColor.LIGHT_PURPLE),
                List.of(
                        MenuUtil.line("Su tropa: crear esbirros, ajustarlos,"),
                        MenuUtil.line("plantar generadores y su botin."),
                        MenuUtil.blank(),
                        MenuUtil.field("Dentro", dentro.size() + " esbirro(s)", NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.action("Click para abrirla")), false));

        inv.setItem(31, MenuUtil.icon(cat.isGeneral() ? Material.GRAY_DYE : Material.BARRIER,
                MenuUtil.title("Borrar carpeta", cat.isGeneral() ? MenuUtil.DIM : NamedTextColor.RED),
                List.of(
                        cat.isGeneral()
                                ? MenuUtil.line("La carpeta general no se puede borrar:")
                                : MenuUtil.line("Quita la carpeta. Sus esbirros NO se"),
                        cat.isGeneral()
                                ? MenuUtil.line("es donde caen los esbirros sin sitio.")
                                : MenuUtil.line("borran: se mudan a Sin clasificar."),
                        MenuUtil.blank(),
                        cat.isGeneral() ? Component.text("No se puede.", MenuUtil.DIM)
                                : MenuUtil.action("Tecla de tirar (Q) dos veces: borrarla")), false));
    }

    /** El catalogo de una carpeta: su tropa, con la misma gramatica de siempre. */
    private void renderMinions(Inventory inv, Holder holder) {
        MinionCategory cat = plugin.minions().category(holder.context);
        List<MinionType> all = cat == null ? plugin.minions().types() : plugin.minions().typesOf(cat.id());
        for (int i = 0; i < BODY.length; i++) {
            if (i >= all.size()) break;
            MinionType type = all.get(i);
            List<MinionSpawner> spawners = plugin.minions().spawnersOf(type.id());

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(nombreBonito(type.entity()), MenuUtil.SOFT));
            lore.add(MenuUtil.blank());
            lore.add(MenuUtil.field("Vida", (int) type.baseHealth() + " a Nv. 1  ·  +"
                    + Math.round(type.healthGrowth() * 100) + "% por nivel", NamedTextColor.GREEN));
            lore.add(MenuUtil.field("Dano", "x" + trim(type.baseDamage()) + "  ·  +"
                    + Math.round(type.damageGrowth() * 100) + "% por nivel", NamedTextColor.RED));
            lore.add(MenuUtil.field("Generadores", String.valueOf(spawners.size()), NamedTextColor.WHITE));
            lore.add(MenuUtil.blank());
            lore.add(Component.text("SUELTA", MenuUtil.LOOT, TextDecoration.BOLD));
            lore.add(plugin.drops().table(type.dropTableId()).summaryLine(MenuUtil.LOOT));
            lore.add(MenuUtil.blank());
            lore.add(MenuUtil.action("Click para abrir su ficha"));
            lore.add(MenuUtil.actionSecondary("Click derecho: su botin"));
            lore.add(Component.text("► Tecla de tirar (Q) dos veces: borrarlo", NamedTextColor.GRAY));

            inv.setItem(BODY[i], MenuUtil.icon(type.icon(),
                    MenuUtil.title(type.display(), type.color()), lore, false));
        }

        inv.setItem(49, MenuUtil.icon(cat == null ? Material.WRITABLE_BOOK : cat.icon(),
                MenuUtil.title("Crear esbirro", NamedTextColor.GREEN),
                List.of(
                        MenuUtil.line("Un tipo nuevo de tropa. Se cierra el menu"),
                        MenuUtil.line("y el nombre se escribe en el chat."),
                        MenuUtil.blank(),
                        MenuUtil.field("Carpeta", cat == null ? "Sin clasificar" : cat.display(),
                                cat == null ? MenuUtil.SOFT : cat.color()),
                        MenuUtil.blank(),
                        MenuUtil.action("Click para ponerle nombre")),
                true));
    }

    /** La ficha de un esbirro: identidad, escalado, la vela y sus puertas. */
    private void renderMinionEdit(Inventory inv, Holder holder) {
        MinionType type = plugin.minions().type(holder.context);
        if (type == null) return;

        inv.setItem(10, MenuUtil.icon(type.icon(),
                MenuUtil.title("Criatura", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Ahora", nombreBonito(type.entity()), NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.line("El bicho de base. Su comportamiento es el"),
                        MenuUtil.line("de fabrica; el plugin le pone vida, dano"),
                        MenuUtil.line("y holograma segun el nivel."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: siguiente"),
                        Component.text("► Click derecho: anterior", NamedTextColor.YELLOW)), false));

        inv.setItem(11, MenuUtil.icon(Material.BRUSH,
                MenuUtil.title("Color del nombre", type.color()),
                List.of(
                        Component.text("Asi se ve  ", MenuUtil.LABEL)
                                .append(Component.text(type.display(), type.color(), TextDecoration.BOLD)),
                        MenuUtil.blank(),
                        MenuUtil.line("El color del holograma y de los menus."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: siguiente color"),
                        Component.text("► Click derecho: anterior", NamedTextColor.YELLOW)), false));

        inv.setItem(12, MenuUtil.icon(Material.NAME_TAG,
                MenuUtil.title("Renombrar", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Ahora", type.display(), type.color()),
                        MenuUtil.blank(),
                        MenuUtil.line("Se cierra el menu y el nombre nuevo"),
                        MenuUtil.line("se escribe en el chat."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click para renombrar")), false));

        List<Component> ficha = new ArrayList<>();
        ficha.add(Component.text(nombreBonito(type.entity()), MenuUtil.SOFT));
        ficha.add(MenuUtil.blank());
        ficha.add(Component.text("ASI ESCALA", NamedTextColor.WHITE, TextDecoration.BOLD));
        for (int nivel : escalones(type.wandMinLevel(), type.wandMaxLevel())) {
            ficha.add(Component.text("Nv. " + nivel + "  ", MenuUtil.LABEL)
                    .append(Component.text((int) type.healthAt(nivel) + " vida", NamedTextColor.GREEN))
                    .append(Component.text("  ·  ", MenuUtil.DIM))
                    .append(Component.text("x" + trim(type.damageAt(nivel)) + " dano", NamedTextColor.RED)));
        }
        ficha.add(MenuUtil.blank());
        ficha.add(MenuUtil.field("Generadores", String.valueOf(plugin.minions().spawnersOf(type.id()).size()),
                NamedTextColor.WHITE));
        inv.setItem(13, MenuUtil.icon(type.icon(), MenuUtil.title(type.display(), type.color()), ficha, true));

        inv.setItem(14, MenuUtil.icon(Material.GOLDEN_APPLE,
                MenuUtil.title("Vida base", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("A nivel 1", (int) type.baseHealth() + " puntos", NamedTextColor.GREEN),
                        MenuUtil.blank(),
                        MenuUtil.line("La vida con la que aparece un esbirro"),
                        MenuUtil.line("de nivel 1; el resto sale del crecimiento."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +5"),
                        Component.text("► Click derecho: -5", NamedTextColor.YELLOW),
                        Component.text("► Shift para pasos de 50", NamedTextColor.GRAY)), false));

        inv.setItem(15, MenuUtil.icon(Material.IRON_SWORD,
                MenuUtil.title("Dano base", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("A nivel 1", "x" + trim(type.baseDamage()) + " del golpe de fabrica",
                                NamedTextColor.RED),
                        MenuUtil.blank(),
                        MenuUtil.line("Multiplica lo que el bicho pegue de serie,"),
                        MenuUtil.line("valga garra, flecha o explosion."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +0.1"),
                        Component.text("► Click derecho: -0.1", NamedTextColor.YELLOW),
                        Component.text("► Shift para pasos de 1.0", NamedTextColor.GRAY)), false));

        inv.setItem(19, MenuUtil.icon(Material.GLOWSTONE_DUST,
                MenuUtil.title("Crecimiento de vida", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Por nivel", "+" + Math.round(type.healthGrowth() * 100) + "%",
                                NamedTextColor.GREEN),
                        MenuUtil.blank(),
                        MenuUtil.line("Cuanta vida gana por cada nivel por"),
                        MenuUtil.line("encima del 1, sobre la vida base."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +5%"),
                        Component.text("► Click derecho: -5%", NamedTextColor.YELLOW),
                        Component.text("► Shift para pasos de 25%", NamedTextColor.GRAY)), false));

        inv.setItem(20, MenuUtil.icon(Material.BLAZE_POWDER,
                MenuUtil.title("Crecimiento de dano", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Por nivel", "+" + Math.round(type.damageGrowth() * 100) + "%",
                                NamedTextColor.RED),
                        MenuUtil.blank(),
                        MenuUtil.line("Cuanto dano gana por cada nivel por"),
                        MenuUtil.line("encima del 1, sobre el dano base."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +5%"),
                        Component.text("► Click derecho: -5%", NamedTextColor.YELLOW),
                        Component.text("► Shift para pasos de 25%", NamedTextColor.GRAY)), false));

        inv.setItem(21, MenuUtil.icon(Material.OAK_SLAB,
                MenuUtil.title("Nivel minimo", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("La vela pone", "Nv. " + type.wandMinLevel(), NamedTextColor.GOLD),
                        MenuUtil.field("Rango entero", "Nv. " + rangoTexto(type.wandMinLevel(), type.wandMaxLevel()),
                                NamedTextColor.GOLD),
                        MenuUtil.blank(),
                        MenuUtil.line("El suelo del sorteo de nivel de los"),
                        MenuUtil.line("generadores que plante la proxima vela."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +1"),
                        Component.text("► Click derecho: -1", NamedTextColor.YELLOW),
                        Component.text("► Shift para pasos de 10", NamedTextColor.GRAY),
                        Component.text("► Tecla de tirar (Q): escribirlo, \"30-60\"", NamedTextColor.GRAY)), false));

        inv.setItem(22, MenuUtil.icon(Material.CANDLE,
                MenuUtil.title("Dame la vela", NamedTextColor.LIGHT_PURPLE),
                List.of(
                        MenuUtil.line("La herramienta de sembrar generadores:"),
                        MenuUtil.line("click derecho en un bloque y ahi queda."),
                        MenuUtil.line("No se gasta; sirve para toda una mazmorra."),
                        MenuUtil.blank(),
                        MenuUtil.field("Esbirro", type.display(), type.color()),
                        MenuUtil.field("Nivel", type.wandMinLevel() == type.wandMaxLevel()
                                ? String.valueOf(type.wandMinLevel())
                                : type.wandMinLevel() + " - " + type.wandMaxLevel(), NamedTextColor.GOLD),
                        MenuUtil.field("Reaparece", "cada " + type.wandIntervalSeconds() + "s",
                                NamedTextColor.WHITE),
                        MenuUtil.field("Tope", type.wandMaxAlive() + " vivos  ·  radio "
                                + type.wandActivationRadius(), NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.action("Click para recibirla")), true));

        inv.setItem(23, MenuUtil.icon(Material.STONE_SLAB,
                MenuUtil.title("Nivel maximo", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("La vela pone", "Nv. " + type.wandMaxLevel(), NamedTextColor.GOLD),
                        MenuUtil.field("Rango entero", "Nv. " + rangoTexto(type.wandMinLevel(), type.wandMaxLevel()),
                                NamedTextColor.GOLD),
                        MenuUtil.blank(),
                        MenuUtil.line("El techo del sorteo. Cada generador se"),
                        MenuUtil.line("puede retocar luego desde su lista."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +1"),
                        Component.text("► Click derecho: -1", NamedTextColor.YELLOW),
                        Component.text("► Shift para pasos de 10", NamedTextColor.GRAY),
                        Component.text("► Tecla de tirar (Q): escribirlo, \"30-60\"", NamedTextColor.GRAY)), false));

        inv.setItem(24, MenuUtil.icon(Material.CLOCK,
                MenuUtil.title("Intervalo", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("La vela pone", "cada " + type.wandIntervalSeconds() + "s",
                                NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.line("Cada cuanto repone tropa un generador,"),
                        MenuUtil.line("mientras no llegue a su tope de vivos."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +5s"),
                        Component.text("► Click derecho: -5s", NamedTextColor.YELLOW),
                        Component.text("► Shift para pasos de 30s", NamedTextColor.GRAY)), false));

        inv.setItem(25, MenuUtil.icon(Material.ARMOR_STAND,
                MenuUtil.title("Tope de vivos", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("La vela pone", type.wandMaxAlive() + " a la vez", NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.line("Cuantos puede tener vivos cada generador."),
                        MenuUtil.line("Al morir uno, el reloj repone el hueco."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +1"),
                        Component.text("► Click derecho: -1", NamedTextColor.YELLOW)), false));

        inv.setItem(28, MenuUtil.icon(Material.ENDER_EYE,
                MenuUtil.title("Radio de activacion", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("La vela pone", type.wandActivationRadius() + " bloques",
                                NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.line("El generador solo trabaja con un jugador"),
                        MenuUtil.line("dentro de este radio: una mazmorra vacia"),
                        MenuUtil.line("no acumula bichos."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +4"),
                        Component.text("► Click derecho: -4", NamedTextColor.YELLOW),
                        Component.text("► Shift para pasos de 16", NamedTextColor.GRAY)), false));

        MinionCategory suya = plugin.minions().categoryOf(type);
        inv.setItem(31, MenuUtil.icon(suya.icon(),
                MenuUtil.title("Carpeta", suya.color()),
                List.of(
                        MenuUtil.field("Ahora", suya.display(), suya.color()),
                        MenuUtil.blank(),
                        MenuUtil.line("En que mazmorra o proposito vive."),
                        MenuUtil.line("Cambiarla no toca nada de su pelea."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: siguiente carpeta"),
                        Component.text("► Click derecho: anterior", NamedTextColor.YELLOW)), false));

        inv.setItem(29, MenuUtil.icon(type.bold() ? Material.INK_SAC : Material.GLASS_BOTTLE,
                MenuUtil.title("Nombre en negrita", MenuUtil.GOLD),
                List.of(
                        MenuUtil.line("Como se lee su nombre en el cartel que"),
                        MenuUtil.line("lleva encima. De serie va en redonda."),
                        MenuUtil.blank(),
                        Component.text("Asi se ve  ", MenuUtil.LABEL).append(type.name()),
                        MenuUtil.field("Ahora", "", MenuUtil.SOFT).append(MenuUtil.state(type.bold())),
                        MenuUtil.blank(),
                        MenuUtil.action("Click para cambiar")), type.bold()));

        List<Component> habLore = new ArrayList<>();
        habLore.add(MenuUtil.line("Rasgos que lleva puestos siempre: no hay"));
        habLore.add(MenuUtil.line("fases ni enfriamientos, se notan peleando."));
        habLore.add(MenuUtil.blank());
        if (type.abilities().isEmpty()) {
            habLore.add(Component.text("Ninguna todavia.", MenuUtil.DIM));
        } else {
            for (MinionAbility a : type.abilities()) {
                habLore.add(Component.text("· ", MenuUtil.DIM)
                        .append(Component.text(a.display(), a.color(), TextDecoration.BOLD)));
            }
        }
        habLore.add(MenuUtil.blank());
        habLore.add(MenuUtil.action("Click para abrir el catalogo"));
        inv.setItem(16, MenuUtil.icon(Material.ENCHANTED_BOOK,
                MenuUtil.title("Habilidades", MenuUtil.GOLD), habLore, !type.abilities().isEmpty()));

        List<MinionSpawner> spawners = plugin.minions().spawnersOf(type.id());
        inv.setItem(30, MenuUtil.icon(Material.LODESTONE,
                MenuUtil.title("Generadores", MenuUtil.GOLD),
                List.of(
                        MenuUtil.line("Todos los puntos donde aparece este"),
                        MenuUtil.line("esbirro: donde estan, de que nivel salen,"),
                        MenuUtil.line("viajar alli, retocarlos o quitarlos."),
                        MenuUtil.blank(),
                        MenuUtil.field("Plantados", String.valueOf(spawners.size()), NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        spawners.isEmpty() ? Component.text("Planta el primero con la vela.", MenuUtil.DIM)
                                : MenuUtil.action("Click para ver la lista")), false));

        DropTable table = plugin.drops().table(type.dropTableId());
        inv.setItem(32, MenuUtil.icon(Material.CHEST,
                MenuUtil.title("Botin", MenuUtil.LOOT),
                List.of(
                        MenuUtil.line("Que suelta al morir, este al nivel que"),
                        MenuUtil.line("este. Si la tabla tiene algo, sustituye"),
                        MenuUtil.line("al botin de fabrica del bicho."),
                        MenuUtil.blank(),
                        MenuUtil.field("Objetos", table.entries().size() + " / " + DropTable.CAPACITY,
                                NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.action("Click para editar el botin")), false));

        inv.setItem(34, MenuUtil.icon(Material.EGG,
                MenuUtil.title("Invocar de prueba", NamedTextColor.AQUA),
                List.of(
                        MenuUtil.line("Hace aparecer UNO a tu lado, del nivel"),
                        MenuUtil.line("minimo de la vela, sin generador: para"),
                        MenuUtil.line("verlo y pegarle sin salir de la sala."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click para invocarlo")), false));
    }

    /** El catalogo de rasgos: uno por casilla, encendido o apagado. */
    private void renderMinionAbilities(Inventory inv, Holder holder) {
        MinionType type = plugin.minions().type(holder.context);
        if (type == null) return;
        MinionAbility[] all = MinionAbility.values();
        for (int i = 0; i < BODY.length && i < all.length; i++) {
            MinionAbility a = all[i];
            boolean on = type.has(a);
            List<Component> lore = new ArrayList<>();
            lore.addAll(MenuUtil.wrap(a.what(), 38, MenuUtil.SOFT));
            lore.add(MenuUtil.blank());
            lore.addAll(MenuUtil.wrap(a.why(), 38, MenuUtil.DIM));
            lore.add(MenuUtil.blank());
            lore.add(MenuUtil.field("Ahora", "", MenuUtil.SOFT).append(MenuUtil.state(on)));
            lore.add(MenuUtil.blank());
            lore.add(MenuUtil.action(on ? "Click para quitarsela" : "Click para dársela"));
            inv.setItem(BODY[i], MenuUtil.icon(on ? a.icon() : Material.GRAY_DYE,
                    MenuUtil.title(a.display(), on ? a.color() : MenuUtil.DIM), lore, on));
        }
        inv.setItem(49, MenuUtil.icon(type.icon(), MenuUtil.title(type.display(), type.color()),
                List.of(
                        MenuUtil.field("Con", type.abilities().size() + " de " + all.length + " habilidades",
                                NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.line("Se aplican a los que salgan a partir de"),
                        MenuUtil.line("ahora; los que ya estan vivos no cambian.")), false));
    }

    private void clickMinionAbilities(Player player, InventoryClickEvent event, Holder holder, int slot) {
        MinionType type = plugin.minions().type(holder.context);
        if (type == null) return;
        int index = indexOf(BODY, slot);
        if (index < 0 || index >= MinionAbility.values().length) return;
        MinionAbility a = MinionAbility.values()[index];
        boolean on = type.toggle(a);
        plugin.minions().save();
        click(player, on ? 1.5f : 0.8f);
        player.sendActionBar(Component.text(a.display() + "  ", MenuUtil.SOFT).append(MenuUtil.state(on)));
        render(event.getInventory(), player, holder);
    }

    /** La lista de generadores de un esbirro, paginada como las habilidades. */
    private void renderSpawners(Inventory inv, Holder holder) {
        MinionType type = plugin.minions().type(holder.context);
        if (type == null) return;
        List<MinionSpawner> spawners = plugin.minions().spawnersOf(type.id());
        int perPage = BODY.length;
        int pages = Math.max(1, (spawners.size() + perPage - 1) / perPage);
        int page = Math.floorMod(holder.page, pages);

        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= spawners.size()) break;
            MinionSpawner s = spawners.get(index);
            Location spot = s.spot();
            List<String> regions = spot == null ? List.of() : plugin.protection().regionNames(spot);
            int vivos = plugin.minionManager().aliveOf(s.id());

            List<Component> lore = new ArrayList<>();
            lore.add(MenuUtil.field("Mundo", s.worldName(), NamedTextColor.WHITE));
            lore.add(MenuUtil.field("Donde", s.x() + "  " + (s.y() + 1) + "  " + s.z(), NamedTextColor.WHITE));
            lore.add(MenuUtil.field("Region", regions.isEmpty() ? "ninguna" : String.join(", ", regions),
                    regions.isEmpty() ? MenuUtil.DIM : NamedTextColor.AQUA));
            lore.add(MenuUtil.blank());
            lore.add(MenuUtil.field("Nivel", s.levelLabel(), NamedTextColor.GOLD));
            lore.add(MenuUtil.field("Reaparece", "cada " + s.intervalSeconds() + "s", NamedTextColor.WHITE));
            lore.add(MenuUtil.field("Vivos", vivos + " de " + s.maxAlive(), NamedTextColor.WHITE));
            lore.add(MenuUtil.field("Estado", s.enabled() ? "activo" : "pausado",
                    s.enabled() ? NamedTextColor.GREEN : NamedTextColor.RED));
            lore.add(MenuUtil.blank());
            lore.add(MenuUtil.action("Click para viajar en frente"));
            lore.add(MenuUtil.actionSecondary("Click derecho: configurarlo"));
            lore.add(Component.text("► Tecla de tirar (Q) dos veces: quitarlo", NamedTextColor.GRAY));

            inv.setItem(BODY[i], MenuUtil.icon(s.enabled() ? Material.CANDLE : Material.GRAY_CANDLE,
                    MenuUtil.title("Generador " + s.id(), type.color()), lore, s.enabled() && vivos > 0));
        }

        inv.setItem(48, page > 0 ? MenuUtil.simple(Material.ARROW,
                Component.text("◀ Pagina anterior", NamedTextColor.YELLOW),
                List.of(Component.text("Pagina " + page + " de " + pages, MenuUtil.SOFT))) : MenuUtil.pane());
        inv.setItem(49, MenuUtil.icon(type.icon(), MenuUtil.title(type.display(), type.color()),
                List.of(
                        MenuUtil.field("Generadores", String.valueOf(spawners.size()), NamedTextColor.WHITE),
                        MenuUtil.field("Pagina", (page + 1) + " de " + pages, MenuUtil.SOFT),
                        MenuUtil.blank(),
                        MenuUtil.line("Cada generador guarda SU rango de nivel:"),
                        MenuUtil.line("el mismo esbirro puede ser 5-10 aqui"),
                        MenuUtil.line("y 20-30 en la sala del fondo.")), false));
        inv.setItem(50, page < pages - 1 ? MenuUtil.simple(Material.SPECTRAL_ARROW,
                Component.text("Pagina siguiente ▶", NamedTextColor.YELLOW),
                List.of(Component.text("Pagina " + (page + 2) + " de " + pages, MenuUtil.SOFT))) : MenuUtil.pane());
    }

    /** La ficha de un generador concreto: su nivel, su ritmo y sus acciones. */
    private void renderSpawnerEdit(Inventory inv, Holder holder) {
        MinionSpawner s = plugin.minions().spawner(holder.context);
        if (s == null) return;
        MinionType type = plugin.minions().type(s.typeId());
        if (type == null) return;
        Location spot = s.spot();
        List<String> regions = spot == null ? List.of() : plugin.protection().regionNames(spot);

        inv.setItem(13, MenuUtil.icon(Material.CANDLE,
                MenuUtil.title("Generador " + s.id(), type.color()),
                List.of(
                        MenuUtil.field("Esbirro", type.display(), type.color()),
                        MenuUtil.field("Mundo", s.worldName(), NamedTextColor.WHITE),
                        MenuUtil.field("Donde", s.x() + "  " + (s.y() + 1) + "  " + s.z(), NamedTextColor.WHITE),
                        MenuUtil.field("Region", regions.isEmpty() ? "ninguna" : String.join(", ", regions),
                                regions.isEmpty() ? MenuUtil.DIM : NamedTextColor.AQUA),
                        MenuUtil.field("Vivos ahora", plugin.minionManager().aliveOf(s.id()) + " de " + s.maxAlive(),
                                NamedTextColor.WHITE)), true));

        inv.setItem(19, MenuUtil.icon(Material.ENDER_EYE,
                MenuUtil.title("Radio de activacion", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Ahora", s.activationRadius() + " bloques", NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +4"),
                        Component.text("► Click derecho: -4", NamedTextColor.YELLOW),
                        Component.text("► Shift para pasos de 16", NamedTextColor.GRAY)), false));

        inv.setItem(20, MenuUtil.icon(Material.OAK_SLAB,
                MenuUtil.title("Nivel minimo", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Ahora", "Nv. " + s.minLevel(), NamedTextColor.GOLD),
                        MenuUtil.field("Rango entero", "Nv. " + rangoTexto(s.minLevel(), s.maxLevel()),
                                NamedTextColor.GOLD),
                        MenuUtil.blank(),
                        MenuUtil.line("Solo de ESTE generador; los demas"),
                        MenuUtil.line("puntos del esbirro no se tocan."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +1"),
                        Component.text("► Click derecho: -1", NamedTextColor.YELLOW),
                        Component.text("► Shift para pasos de 10", NamedTextColor.GRAY),
                        Component.text("► Tecla de tirar (Q): escribirlo, \"30-60\"", NamedTextColor.GRAY)), false));

        inv.setItem(21, MenuUtil.icon(Material.STONE_SLAB,
                MenuUtil.title("Nivel maximo", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Ahora", "Nv. " + s.maxLevel(), NamedTextColor.GOLD),
                        MenuUtil.field("Rango entero", "Nv. " + rangoTexto(s.minLevel(), s.maxLevel()),
                                NamedTextColor.GOLD),
                        MenuUtil.blank(),
                        MenuUtil.line("De aqui a ese suelo se sortea el nivel"),
                        MenuUtil.line("de cada esbirro que salga del punto."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +1"),
                        Component.text("► Click derecho: -1", NamedTextColor.YELLOW),
                        Component.text("► Shift para pasos de 10", NamedTextColor.GRAY),
                        Component.text("► Tecla de tirar (Q): escribirlo, \"30-60\"", NamedTextColor.GRAY)), false));

        inv.setItem(22, MenuUtil.icon(Material.ENDER_PEARL,
                MenuUtil.title("Viajar en frente", NamedTextColor.LIGHT_PURPLE),
                List.of(
                        MenuUtil.line("Te deja a un par de bloques del punto,"),
                        MenuUtil.line("mirando hacia el."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click para viajar")), false));

        inv.setItem(23, MenuUtil.icon(Material.CLOCK,
                MenuUtil.title("Intervalo", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Ahora", "cada " + s.intervalSeconds() + "s", NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +5s"),
                        Component.text("► Click derecho: -5s", NamedTextColor.YELLOW),
                        Component.text("► Shift para pasos de 30s", NamedTextColor.GRAY)), false));

        inv.setItem(24, MenuUtil.icon(Material.ARMOR_STAND,
                MenuUtil.title("Tope de vivos", MenuUtil.GOLD),
                List.of(
                        MenuUtil.field("Ahora", s.maxAlive() + " a la vez", NamedTextColor.WHITE),
                        MenuUtil.blank(),
                        MenuUtil.action("Click izquierdo: +1"),
                        Component.text("► Click derecho: -1", NamedTextColor.YELLOW)), false));

        inv.setItem(30, MenuUtil.icon(s.enabled() ? Material.LEVER : Material.GRAY_DYE,
                MenuUtil.title(s.enabled() ? "Activo" : "Pausado",
                        s.enabled() ? NamedTextColor.GREEN : NamedTextColor.RED),
                List.of(
                        MenuUtil.line("Un generador pausado no repone tropa,"),
                        MenuUtil.line("pero se queda plantado con su config."),
                        MenuUtil.blank(),
                        MenuUtil.field("Ahora", "", MenuUtil.SOFT).append(MenuUtil.state(s.enabled())),
                        MenuUtil.blank(),
                        MenuUtil.action("Click para cambiar")), s.enabled()));

        inv.setItem(32, MenuUtil.icon(Material.EGG,
                MenuUtil.title("Generar ahora", NamedTextColor.AQUA),
                List.of(
                        MenuUtil.line("Hace aparecer uno al momento, sin"),
                        MenuUtil.line("esperar el reloj (respeta el tope)."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click para generar")), false));

        inv.setItem(34, MenuUtil.icon(Material.BARRIER,
                MenuUtil.title("Quitar generador", NamedTextColor.RED),
                List.of(
                        MenuUtil.line("Lo arranca del suelo para siempre; los"),
                        MenuUtil.line("que ya esten vivos se quedan hasta morir."),
                        MenuUtil.blank(),
                        MenuUtil.action("Click DOS VECES para quitarlo")), false));
    }

    // Los nombres de EntityType vienen en mayusculas con guion bajo; para el menu
    // se leen mejor como "Piglin brute" que como PIGLIN_BRUTE.
    private static String nombreBonito(org.bukkit.entity.EntityType type) {
        String raw = type.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    /** IRON_ORE se lee mejor como "Iron ore" en el lore de un menu. */
    private static String nombreBonitoMaterial(Material material) {
        String raw = material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private static String trim(double v) {
        return DropTable.trimChance(v);
    }

    // ------------------------------------------------------- esbirros: los clicks

    /** Confirmaciones de borrado: el primer Q avisa, el segundo (en 5s) ejecuta. */
    private final java.util.Map<java.util.UUID, String> pendingDelete = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Long> pendingDeleteAt = new java.util.HashMap<>();

    private boolean confirmDelete(Player player, String what) {
        long now = System.currentTimeMillis();
        String prev = pendingDelete.get(player.getUniqueId());
        Long at = pendingDeleteAt.get(player.getUniqueId());
        if (what.equals(prev) && at != null && now - at < 5000) {
            pendingDelete.remove(player.getUniqueId());
            pendingDeleteAt.remove(player.getUniqueId());
            return true;
        }
        pendingDelete.put(player.getUniqueId(), what);
        pendingDeleteAt.put(player.getUniqueId(), now);
        Compat.sound(player.getWorld(), player.getLocation(), "block.note_block.hat", 0.8f, 0.7f);
        player.sendActionBar(Component.text("Vuelve a pulsar Q para confirmar el borrado.",
                NamedTextColor.RED));
        return false;
    }

    private void clickCategories(Player player, InventoryClickEvent event, Holder holder, int slot) {
        if (slot == 49) {
            click(player, 1.4f);
            beginInput(player, PendingInput.Kind.CARPETA_NUEVA, "",
                    "Escribe en el chat el nombre de la carpeta nueva.");
            return;
        }
        int index = indexOf(BODY, slot);
        if (index < 0) return;
        List<MinionCategory> all = plugin.minions().categories();
        if (index >= all.size()) return;
        MinionCategory cat = all.get(index);

        if (event.isRightClick()) {
            click(player, 1.1f);
            open(player, Screen.CATEGORY_EDIT, 0, cat.id(), false);
        } else {
            click(player, 1.2f);
            open(player, Screen.MINIONS, 0, cat.id(), false);
        }
    }

    private void clickCategoryEdit(Player player, InventoryClickEvent event, Holder holder, int slot) {
        MinionCategory cat = plugin.minions().category(holder.context);
        if (cat == null) return;
        boolean up = event.isLeftClick();

        switch (slot) {
            case 11 -> {
                click(player, 1.4f);
                beginInput(player, PendingInput.Kind.CARPETA_NOMBRE, cat.id(),
                        "Escribe en el chat el nombre nuevo de la carpeta.");
                return;
            }
            case 15 -> {
                // El icono se pone trayendo el objeto en el cursor; con Q se escribe.
                if (event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP) {
                    click(player, 1.4f);
                    beginInput(player, PendingInput.Kind.CARPETA_ICONO, cat.id(),
                            "Escribe en el chat el nombre del bloque u objeto, por ejemplo GOLD_ORE.");
                    return;
                }
                ItemStack cursor = event.getCursor();
                if (cursor == null || cursor.getType().isAir()) {
                    deny(player, "Coge antes un objeto con el raton y vuelve a clickar aqui.");
                    return;
                }
                cat.icon(cursor.getType());
                plugin.minions().save();
            }
            case 20 -> cat.cycleColor(up);
            case 24 -> {
                click(player, 1.2f);
                open(player, Screen.MINIONS, 0, cat.id(), false);
                return;
            }
            case 31 -> {
                if (cat.isGeneral()) {
                    deny(player, "La carpeta general no se puede borrar.");
                    return;
                }
                if (event.getClick() != ClickType.DROP && event.getClick() != ClickType.CONTROL_DROP) {
                    deny(player, "Para borrarla, pulsa la tecla de tirar (Q) dos veces.");
                    return;
                }
                if (!confirmDelete(player, "carpeta:" + cat.id())) return;
                int mudados = plugin.minions().typesOf(cat.id()).size();
                plugin.minions().deleteCategory(cat);
                click(player, 0.6f);
                player.sendMessage(plugin.prefix()
                        .append(Component.text("Carpeta borrada  ", NamedTextColor.RED))
                        .append(Component.text(cat.display(), cat.color(), TextDecoration.BOLD))
                        .append(Component.text(mudados == 0 ? "." : "  " + mudados
                                + " esbirro(s) se mudaron a Sin clasificar.", MenuUtil.SOFT)));
                open(player, Screen.MINION_CATEGORIES, 0, "", false);
                return;
            }
            default -> {
                return;
            }
        }
        plugin.minions().save();
        click(player, up ? 1.4f : 0.9f);
        render(event.getInventory(), player, holder);
    }

    private void clickMinions(Player player, InventoryClickEvent event, Holder holder, int slot) {
        if (slot == 49) {
            click(player, 1.4f);
            MinionCategory abierta = plugin.minions().category(holder.context);
            beginInput(player, PendingInput.Kind.TIPO_NUEVO,
                    abierta == null ? plugin.minions().general().id() : abierta.id(),
                    "Escribe en el chat el nombre del esbirro nuevo.");
            return;
        }
        int index = indexOf(BODY, slot);
        if (index < 0) return;
        MinionCategory abierta = plugin.minions().category(holder.context);
        List<MinionType> all = abierta == null
                ? plugin.minions().types() : plugin.minions().typesOf(abierta.id());
        if (index >= all.size()) return;
        MinionType type = all.get(index);

        if (event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP) {
            if (!confirmDelete(player, "tipo:" + type.id())) return;
            plugin.minions().deleteType(type);
            click(player, 0.6f);
            player.sendMessage(plugin.prefix()
                    .append(Component.text("Esbirro borrado  ", NamedTextColor.RED))
                    .append(Component.text(type.display(), type.color(), TextDecoration.BOLD))
                    .append(Component.text("  con sus generadores.", MenuUtil.SOFT)));
            render(event.getInventory(), player, holder);
        } else if (event.isRightClick()) {
            click(player, 1.1f);
            open(player, Screen.DROPS, 0, type.dropTableId(), true);
        } else {
            click(player, 1.2f);
            open(player, Screen.MINION_EDIT, 0, type.id(), false);
        }
    }

    private void clickMinionEdit(Player player, InventoryClickEvent event, Holder holder, int slot) {
        MinionType type = plugin.minions().type(holder.context);
        if (type == null) return;
        boolean up = event.isLeftClick();
        boolean shift = event.isShiftClick();

        // Q sobre cualquiera de los dos escalones: el rango entero se escribe en
        // el chat de una vez ("30-60"), que a clicks se hace eterno.
        if ((slot == 21 || slot == 23)
                && (event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP)) {
            click(player, 1.4f);
            beginRange(player, PendingInput.Kind.RANGO_TIPO, type.id(),
                    type.wandMinLevel(), type.wandMaxLevel());
            return;
        }

        switch (slot) {
            case 10 -> type.cycleEntity(up);
            case 11 -> type.cycleColor(up);
            case 12 -> {
                click(player, 1.4f);
                beginInput(player, PendingInput.Kind.TIPO_NOMBRE, type.id(),
                        "Escribe en el chat el nombre nuevo del esbirro.");
                return;
            }
            case 14 -> type.baseHealth(type.baseHealth() + (shift ? 50 : 5) * (up ? 1 : -1));
            case 15 -> type.baseDamage(type.baseDamage() + (shift ? 1.0 : 0.1) * (up ? 1 : -1));
            case 19 -> type.healthGrowth(type.healthGrowth() + (shift ? 0.25 : 0.05) * (up ? 1 : -1));
            case 20 -> type.damageGrowth(type.damageGrowth() + (shift ? 0.25 : 0.05) * (up ? 1 : -1));
            case 21 -> type.wandMinLevel(type.wandMinLevel() + (shift ? 10 : 1) * (up ? 1 : -1));
            case 22 -> {
                var leftover = player.getInventory().addItem(plugin.minionWand().create(type));
                if (!leftover.isEmpty()) {
                    deny(player, "No tienes hueco en el inventario.");
                    return;
                }
                Compat.sound(player.getWorld(), player.getLocation(), "block.amethyst_block.resonate", 0.8f, 1.3f);
                player.sendMessage(plugin.prefix()
                        .append(Component.text("Vela lista: ", NamedTextColor.GREEN))
                        .append(Component.text("click derecho en un bloque planta un generador de ", MenuUtil.SOFT))
                        .append(Component.text(type.display(), type.color(), TextDecoration.BOLD))
                        .append(Component.text("  Nv. " + type.wandMinLevel()
                                + (type.wandMaxLevel() > type.wandMinLevel() ? " - " + type.wandMaxLevel() : ""),
                                NamedTextColor.GOLD))
                        .append(Component.text(".", MenuUtil.SOFT)));
                return;
            }
            case 23 -> type.wandMaxLevel(type.wandMaxLevel() + (shift ? 10 : 1) * (up ? 1 : -1));
            case 24 -> type.wandIntervalSeconds(type.wandIntervalSeconds() + (shift ? 30 : 5) * (up ? 1 : -1));
            case 25 -> type.wandMaxAlive(type.wandMaxAlive() + (up ? 1 : -1));
            case 28 -> type.wandActivationRadius(type.wandActivationRadius() + (shift ? 16 : 4) * (up ? 1 : -1));
            case 29 -> {
                type.bold(!type.bold());
                plugin.minionManager().refreshHolos(type.id());
            }
            case 31 -> {
                List<MinionCategory> todas = plugin.minions().categories();
                if (todas.size() < 2) {
                    deny(player, "Solo hay una carpeta: crea otra desde /esb.");
                    return;
                }
                int at = 0;
                for (int i = 0; i < todas.size(); i++) {
                    if (todas.get(i).id().equals(type.categoryId())) at = i;
                }
                MinionCategory destino = todas.get(Math.floorMod(at + (up ? 1 : -1), todas.size()));
                type.categoryId(destino.id());
            }
            case 16 -> {
                click(player, 1.1f);
                open(player, Screen.MINION_ABILITIES, 0, type.id(), false);
                return;
            }
            case 30 -> {
                if (plugin.minions().spawnersOf(type.id()).isEmpty()) {
                    deny(player, "Este esbirro aun no tiene generadores: plantale uno con la vela.");
                    return;
                }
                click(player, 1.1f);
                open(player, Screen.SPAWNERS, 0, type.id(), false);
                return;
            }
            case 32 -> {
                click(player, 1.1f);
                open(player, Screen.DROPS, 0, type.dropTableId(), true);
                return;
            }
            case 34 -> {
                Location at = player.getLocation().add(player.getLocation().getDirection()
                        .setY(0).normalize().multiply(2.5));
                plugin.minionManager().spawnAt(type, type.wandMinLevel(), Fx.ground(at, 4), null);
                click(player, 1.6f);
                player.sendActionBar(Component.text("Ahi lo tienes: ", MenuUtil.SOFT)
                        .append(Component.text(type.display() + " Nv. " + type.wandMinLevel(),
                                type.color(), TextDecoration.BOLD)));
                return;
            }
            default -> {
                return;
            }
        }
        plugin.minions().save();
        click(player, up ? 1.4f : 0.9f);
        render(event.getInventory(), player, holder);
    }

    private void clickSpawners(Player player, InventoryClickEvent event, Holder holder, int slot) {
        MinionType type = plugin.minions().type(holder.context);
        if (type == null) return;
        List<MinionSpawner> spawners = plugin.minions().spawnersOf(type.id());
        int pages = Math.max(1, (spawners.size() + BODY.length - 1) / BODY.length);
        if (slot == 48 && holder.page > 0) {
            click(player, 1.0f);
            open(player, Screen.SPAWNERS, holder.page - 1, holder.context, false);
            return;
        }
        if (slot == 50 && holder.page < pages - 1) {
            click(player, 1.1f);
            open(player, Screen.SPAWNERS, holder.page + 1, holder.context, false);
            return;
        }
        int index = indexOf(BODY, slot);
        if (index < 0) return;
        int at = Math.floorMod(holder.page, pages) * BODY.length + index;
        if (at >= spawners.size()) return;
        MinionSpawner s = spawners.get(at);

        if (event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP) {
            if (!confirmDelete(player, "generador:" + s.id())) return;
            plugin.minions().deleteSpawner(s);
            click(player, 0.6f);
            player.sendMessage(plugin.prefix()
                    .append(Component.text("Generador quitado  ", NamedTextColor.RED))
                    .append(Component.text(s.id() + "  (" + s.x() + " " + (s.y() + 1) + " " + s.z() + ")",
                            NamedTextColor.WHITE)));
            render(event.getInventory(), player, holder);
        } else if (event.isRightClick()) {
            click(player, 1.2f);
            open(player, Screen.SPAWNER_EDIT, 0, s.id(), false);
        } else {
            travelToSpawner(player, s, type);
        }
    }

    private void clickSpawnerEdit(Player player, InventoryClickEvent event, Holder holder, int slot) {
        MinionSpawner s = plugin.minions().spawner(holder.context);
        if (s == null) return;
        MinionType type = plugin.minions().type(s.typeId());
        if (type == null) return;
        boolean up = event.isLeftClick();
        boolean shift = event.isShiftClick();

        if ((slot == 20 || slot == 21)
                && (event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP)) {
            click(player, 1.4f);
            beginRange(player, PendingInput.Kind.RANGO_GENERADOR, s.id(), s.minLevel(), s.maxLevel());
            return;
        }

        switch (slot) {
            case 19 -> s.activationRadius(s.activationRadius() + (shift ? 16 : 4) * (up ? 1 : -1));
            case 20 -> s.minLevel(s.minLevel() + (shift ? 10 : 1) * (up ? 1 : -1));
            case 21 -> s.maxLevel(s.maxLevel() + (shift ? 10 : 1) * (up ? 1 : -1));
            case 22 -> {
                travelToSpawner(player, s, type);
                return;
            }
            case 23 -> s.intervalSeconds(s.intervalSeconds() + (shift ? 30 : 5) * (up ? 1 : -1));
            case 24 -> s.maxAlive(s.maxAlive() + (up ? 1 : -1));
            case 30 -> s.enabled(!s.enabled());
            case 32 -> {
                if (!plugin.minionManager().forceSpawn(s)) {
                    deny(player, "No se pudo: tope de vivos alcanzado o el mundo no esta cargado.");
                    return;
                }
                click(player, 1.6f);
                render(event.getInventory(), player, holder);
                return;
            }
            case 34 -> {
                if (!confirmDelete(player, "generador:" + s.id())) return;
                plugin.minions().deleteSpawner(s);
                click(player, 0.6f);
                open(player, Screen.SPAWNERS, 0, s.typeId(), false);
                return;
            }
            default -> {
                return;
            }
        }
        plugin.minions().save();
        click(player, up ? 1.4f : 0.9f);
        render(event.getInventory(), player, holder);
    }

    /** Igual que viajar a la anomalia: cerca, en suelo firme y mirando al punto. */
    private void travelToSpawner(Player player, MinionSpawner s, MinionType type) {
        Location target = s.spot();
        if (target == null || target.getWorld() == null) {
            deny(player, "El mundo de ese generador no esta cargado.");
            return;
        }
        click(player, 1.5f);
        player.closeInventory();

        Location spot = null;
        for (int i = 0; i < 8 && spot == null; i++) {
            double a = Math.PI * 2 * i / 8.0;
            Location probe = Fx.ground(target.clone().add(Math.cos(a) * 2.5, 1, Math.sin(a) * 2.5), 5);
            Block floor = probe.getBlock().getRelative(0, -1, 0);
            if (!floor.getType().isSolid() || floor.isLiquid()) continue;
            if (probe.getBlock().getType().isSolid()) continue;
            if (probe.getBlock().getRelative(0, 1, 0).getType().isSolid()) continue;
            spot = probe;
        }
        if (spot == null) spot = target.clone();

        Vector look = target.toVector().subtract(spot.toVector());
        if (look.lengthSquared() > 0.01) spot.setDirection(look);
        player.teleport(spot);
        Compat.sound(player.getWorld(), spot, "entity.enderman.teleport", 0.9f, 1.1f);
        player.sendMessage(plugin.prefix()
                .append(Component.text("Te dejo frente al generador ", MenuUtil.SOFT))
                .append(Component.text(s.id(), type.color(), TextDecoration.BOLD))
                .append(Component.text(".", MenuUtil.SOFT)));
    }

    // ------------------------------------------------ esbirros: lo que se escribe

    /**
     * Lo que el menu esta esperando que el jugador escriba en el chat: el nombre de
     * un esbirro o de una carpeta, el icono de una carpeta o un rango de nivel. El
     * contexto es el id de lo que se esta tocando (carpeta, tipo o generador).
     */
    private record PendingInput(Kind kind, String context, long expiresAt) {
        enum Kind {TIPO_NUEVO, TIPO_NOMBRE, CARPETA_NUEVA, CARPETA_NOMBRE, CARPETA_ICONO,
            RANGO_TIPO, RANGO_GENERADOR}
    }

    private final java.util.Map<java.util.UUID, PendingInput> pendingName = new java.util.HashMap<>();

    /** Cierra el menu y espera una linea en el chat, con su propio aviso. */
    private void beginInput(Player player, PendingInput.Kind kind, String context, String aviso) {
        pendingName.put(player.getUniqueId(),
                new PendingInput(kind, context, System.currentTimeMillis() + 60_000));
        plugin.getServer().getScheduler().runTask(net.ederus.edm.Module.dueno(plugin), () -> {
            if (player.isOnline()) player.closeInventory();
        });
        player.sendMessage(plugin.prefix().append(Component.text(aviso, NamedTextColor.WHITE)));
        player.sendMessage(plugin.prefix()
                .append(Component.text("Nadie mas lo vera. Escribe \"cancelar\" para dejarlo estar.",
                        MenuUtil.SOFT)));
        Compat.sound(player.getWorld(), player.getLocation(), "block.note_block.pling", 0.7f, 1.6f);
    }

    /**
     * Cierra el menu y espera el rango en el chat. Vale "30-60", "30 60" o un
     * numero suelto (que deja el rango clavado en ese nivel).
     */
    private void beginRange(Player player, PendingInput.Kind kind, String context, int min, int max) {
        pendingName.put(player.getUniqueId(),
                new PendingInput(kind, context, System.currentTimeMillis() + 60_000));
        plugin.getServer().getScheduler().runTask(net.ederus.edm.Module.dueno(plugin), () -> {
            if (player.isOnline()) player.closeInventory();
        });
        player.sendMessage(plugin.prefix()
                .append(Component.text("Escribe en el chat de que nivel a que nivel salen, ",
                        NamedTextColor.WHITE))
                .append(Component.text("\"30-60\"", NamedTextColor.GOLD))
                .append(Component.text(".", NamedTextColor.WHITE)));
        player.sendMessage(plugin.prefix()
                .append(Component.text("Ahora esta en Nv. " + rangoTexto(min, max)
                        + ". Un numero suelto lo deja fijo en ese nivel; \"cancelar\" lo deja como esta.",
                        MenuUtil.SOFT)));
        Compat.sound(player.getWorld(), player.getLocation(), "block.note_block.pling", 0.7f, 1.6f);
    }

    /** "30 - 60", o "30" a secas cuando el rango es un solo nivel. */
    private static String rangoTexto(int min, int max) {
        return min == max ? String.valueOf(min) : min + " - " + max;
    }

    /** Los escalones que ensena la ficha: el suelo, el medio y el techo del rango. */
    private static int[] escalones(int min, int max) {
        if (min == max) return new int[]{min};
        int medio = min + (max - min) / 2;
        if (medio == min || medio == max) return new int[]{min, max};
        return new int[]{min, medio, max};
    }

    /**
     * Lee un rango escrito a mano: "30-60", "30 60", "30 a 60", "60-30" (se ordena
     * solo) o "45". Devuelve null si no hay ningun numero que valga.
     */
    private static int[] parseRange(String raw) {
        java.util.List<Integer> nums = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[0-9]{1,4}").matcher(raw);
        while (m.find() && nums.size() < 2) {
            try {
                nums.add(Integer.parseInt(m.group()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (nums.isEmpty()) return null;
        int a = clampLevel(nums.get(0));
        int b = clampLevel(nums.size() > 1 ? nums.get(1) : nums.get(0));
        return new int[]{Math.min(a, b), Math.max(a, b)};
    }

    private static int clampLevel(int v) {
        return Math.max(1, Math.min(1000, v));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onChatName(io.papermc.paper.event.player.AsyncChatEvent event) {
        PendingInput pending = pendingName.get(event.getPlayer().getUniqueId());
        if (pending == null) return;
        pendingName.remove(event.getPlayer().getUniqueId());
        event.setCancelled(true);
        if (System.currentTimeMillis() > pending.expiresAt()) return;

        String raw = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(event.message()).trim();
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(net.ederus.edm.Module.dueno(plugin), () -> {
            if (!player.isOnline()) return;
            if (raw.isEmpty() || raw.equalsIgnoreCase("cancelar")) {
                player.sendMessage(plugin.prefix().append(Component.text("Sin cambios.", MenuUtil.SOFT)));
                volver(player, pending);
                return;
            }
            String name = raw.length() > 32 ? raw.substring(0, 32) : raw;
            switch (pending.kind()) {
                case RANGO_TIPO, RANGO_GENERADOR -> applyRange(player, pending, raw);
                case TIPO_NUEVO -> {
                    MinionType created = plugin.minions().createType(name, pending.context());
                    MinionCategory cat = plugin.minions().categoryOf(created);
                    player.sendMessage(plugin.prefix()
                            .append(Component.text("Esbirro creado  ", NamedTextColor.GREEN))
                            .append(Component.text(created.display(), created.color(), TextDecoration.BOLD))
                            .append(Component.text("  en la carpeta ", MenuUtil.SOFT))
                            .append(Component.text(cat.display(), cat.color()))
                            .append(Component.text(". Ajusta su criatura, su escalado y su vela.",
                                    MenuUtil.SOFT)));
                    open(player, Screen.MINION_EDIT, 0, created.id(), false);
                }
                case TIPO_NOMBRE -> {
                    MinionType type = plugin.minions().type(pending.context());
                    if (type == null) return;
                    type.display(name);
                    plugin.minions().save();
                    player.sendMessage(plugin.prefix()
                            .append(Component.text("Renombrado a  ", NamedTextColor.GREEN))
                            .append(Component.text(name, type.color(), TextDecoration.BOLD)));
                    open(player, Screen.MINION_EDIT, 0, type.id(), false);
                }
                case CARPETA_NUEVA -> {
                    MinionCategory cat = plugin.minions().createCategory(name);
                    player.sendMessage(plugin.prefix()
                            .append(Component.text("Carpeta creada  ", NamedTextColor.GREEN))
                            .append(Component.text(cat.display(), cat.color(), TextDecoration.BOLD))
                            .append(Component.text("  Ponle icono y mete dentro su tropa.", MenuUtil.SOFT)));
                    open(player, Screen.CATEGORY_EDIT, 0, cat.id(), false);
                }
                case CARPETA_NOMBRE -> {
                    MinionCategory cat = plugin.minions().category(pending.context());
                    if (cat == null) return;
                    cat.display(name);
                    plugin.minions().save();
                    player.sendMessage(plugin.prefix()
                            .append(Component.text("Carpeta renombrada a  ", NamedTextColor.GREEN))
                            .append(Component.text(name, cat.color(), TextDecoration.BOLD)));
                    open(player, Screen.CATEGORY_EDIT, 0, cat.id(), false);
                }
                case CARPETA_ICONO -> {
                    MinionCategory cat = plugin.minions().category(pending.context());
                    if (cat == null) return;
                    Material material = Material.matchMaterial(raw.trim().replace(' ', '_'));
                    if (material == null || !material.isItem()) {
                        deny(player, "No conozco ningun objeto que se llame \"" + raw + "\".");
                        open(player, Screen.CATEGORY_EDIT, 0, cat.id(), false);
                        return;
                    }
                    cat.icon(material);
                    plugin.minions().save();
                    player.sendMessage(plugin.prefix()
                            .append(Component.text("Icono de  ", MenuUtil.SOFT))
                            .append(Component.text(cat.display(), cat.color(), TextDecoration.BOLD))
                            .append(Component.text(": " + nombreBonitoMaterial(material),
                                    NamedTextColor.GREEN)));
                    open(player, Screen.CATEGORY_EDIT, 0, cat.id(), false);
                }
            }
        });
    }

    /** Aplica el rango escrito al tipo o al generador y vuelve a su ficha. */
    private void applyRange(Player player, PendingInput pending, String raw) {
        int[] range = parseRange(raw);
        if (range == null) {
            deny(player, "No entendi ese rango. Escribelo como \"30-60\" y vuelve a intentarlo.");
            volver(player, pending);
            return;
        }
        if (pending.kind() == PendingInput.Kind.RANGO_TIPO) {
            MinionType type = plugin.minions().type(pending.context());
            if (type == null) return;
            type.wandLevels(range[0], range[1]);
            plugin.minions().save();
            player.sendMessage(plugin.prefix()
                    .append(Component.text("La vela plantara generadores de  ", NamedTextColor.GREEN))
                    .append(Component.text("Nv. " + rangoTexto(range[0], range[1]), NamedTextColor.GOLD))
                    .append(Component.text("  para ", MenuUtil.SOFT))
                    .append(type.name())
                    .append(Component.text(".", MenuUtil.SOFT)));
            player.sendMessage(plugin.prefix().append(Component.text(
                    "Los generadores ya plantados no se tocan: cada uno guarda su rango.", MenuUtil.SOFT)));
        } else {
            MinionSpawner s = plugin.minions().spawner(pending.context());
            if (s == null) return;
            s.levels(range[0], range[1]);
            plugin.minions().save();
            player.sendMessage(plugin.prefix()
                    .append(Component.text("El generador " + s.id() + " saca esbirros de  ",
                            NamedTextColor.GREEN))
                    .append(Component.text("Nv. " + rangoTexto(range[0], range[1]), NamedTextColor.GOLD))
                    .append(Component.text(".", MenuUtil.SOFT)));
        }
        Compat.sound(player.getWorld(), player.getLocation(), "block.amethyst_block.resonate", 0.7f, 1.4f);
        volver(player, pending);
    }

    /** Devuelve al jugador a la pantalla desde la que salio a escribir. */
    private void volver(Player player, PendingInput pending) {
        switch (pending.kind()) {
            case RANGO_TIPO, TIPO_NOMBRE -> open(player, Screen.MINION_EDIT, 0, pending.context(), false);
            case RANGO_GENERADOR -> open(player, Screen.SPAWNER_EDIT, 0, pending.context(), false);
            case TIPO_NUEVO -> open(player, Screen.MINIONS, 0, pending.context(), false);
            case CARPETA_NOMBRE, CARPETA_ICONO -> open(player, Screen.CATEGORY_EDIT, 0,
                    pending.context(), false);
            case CARPETA_NUEVA -> open(player, Screen.MINION_CATEGORIES, 0, "", false);
        }
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
                lore.add(MenuUtil.line("Al caer el jefe, TODO el botin explota de su"));
                lore.add(MenuUtil.line("cuerpo y sale disparado por el suelo. Lo"));
                lore.add(MenuUtil.line("reservado sale igual pero solo lo recoge"));
                lore.add(MenuUtil.line("su dueno. El UNICO cae brillando."));
                lore.add(MenuUtil.blank());
                lore.add(MenuUtil.line("Se guarda solo al cerrar el menu."));
            }
            case SETTINGS -> {
                lore.add(MenuUtil.line("Cada cambio se guarda al momento"));
                lore.add(MenuUtil.line("en config.yml."));
            }
            case MINIONS -> {
                lore.add(MenuUtil.line("La tropa de las mazmorras. Cada tipo se"));
                lore.add(MenuUtil.line("define una vez y se planta por el mapa"));
                lore.add(MenuUtil.line("con la vela, cada punto con su nivel."));
                lore.add(MenuUtil.blank());
                lore.add(MenuUtil.line("Todo se guarda al momento en esbirros.yml."));
            }
            case MINION_EDIT -> {
                lore.add(MenuUtil.line("La ficha del esbirro. Los ajustes de la"));
                lore.add(MenuUtil.line("VELA (nivel, ritmo, tope y radio) viajan"));
                lore.add(MenuUtil.line("grabados en cada vela que pidas: saca una,"));
                lore.add(MenuUtil.line("cambia el nivel y saca otra para tener"));
                lore.add(MenuUtil.line("dos siembras distintas del mismo bicho."));
            }
            case MINION_ABILITIES -> {
                lore.add(MenuUtil.line("Los rasgos de este esbirro. A diferencia"));
                lore.add(MenuUtil.line("de las habilidades de un jefe, no tienen"));
                lore.add(MenuUtil.line("fase ni enfriamiento: los lleva siempre."));
                lore.add(MenuUtil.blank());
                lore.add(MenuUtil.line("Cambiarlos no toca a los que ya estan"));
                lore.add(MenuUtil.line("vivos, solo a los que salgan luego."));
            }
            case SPAWNERS -> {
                lore.add(MenuUtil.line("Todos los puntos plantados de este"));
                lore.add(MenuUtil.line("esbirro, con su mundo y su region."));
                lore.add(MenuUtil.blank());
                lore.add(MenuUtil.line("Cada generador guarda su propio rango"));
                lore.add(MenuUtil.line("de nivel, su ritmo y su tope."));
            }
            case SPAWNER_EDIT -> {
                lore.add(MenuUtil.line("Este generador en concreto. Lo que"));
                lore.add(MenuUtil.line("cambies aqui no toca a los demas"));
                lore.add(MenuUtil.line("puntos del mismo esbirro."));
            }
        }
        return MenuUtil.icon(Material.BOOK, MenuUtil.title("Ayuda", MenuUtil.GOLD), lore, false);
    }

    // -------------------------------------------------------------------- escuchas

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;

        HumanEntity human = event.getWhoClicked();
        if (!(human instanceof Player player)) return;
        if (!plugin.mayUseGui(player)) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }

        int slot = event.getRawSlot();
        boolean inTop = slot >= 0 && slot < event.getInventory().getSize();
        boolean placingLoot = holder.screen == Screen.DROPS && holder.placeMode;

        // En el modo de colocar botin hay que DEJAR tocar el inventario propio, porque
        // si no el jugador no puede coger nada con el cursor y el menu parece congelado.
        // Todo lo demas sigue cancelado: el menu nunca se puede desmontar.
        if (inTop || !placingLoot || event.isShiftClick()) {
            event.setCancelled(true);
        }

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
                return;
            }
            click(player, 0.9f);
            // Cada pantalla vuelve a la que la abrio, no siempre al panel: la seccion
            // de esbirros es un pasillo (catalogo -> ficha -> generadores -> generador)
            // y perder el sitio a cada vuelta la haria inusable.
            MinionType asMinion = minionOf(holder.context);
            switch (holder.screen) {
                case MINION_CATEGORIES -> player.closeInventory();
                case CATEGORY_EDIT -> open(player, Screen.MINION_CATEGORIES, 0, "", false);
                case MINIONS -> open(player, Screen.MINION_CATEGORIES, 0, "", false);
                case MINION_EDIT -> {
                    MinionType t = plugin.minions().type(holder.context);
                    open(player, Screen.MINIONS, 0,
                            t == null ? "" : plugin.minions().categoryOf(t).id(), false);
                }
                case MINION_ABILITIES -> open(player, Screen.MINION_EDIT, 0, holder.context, false);
                case SPAWNERS -> open(player, Screen.MINION_EDIT, 0, holder.context, false);
                case SPAWNER_EDIT -> {
                    MinionSpawner s = plugin.minions().spawner(holder.context);
                    open(player, Screen.SPAWNERS, 0, s == null ? "" : s.typeId(), false);
                }
                case DROPS -> {
                    if (asMinion != null) {
                        plugin.drops().save();
                        open(player, Screen.MINION_EDIT, 0, asMinion.id(), false);
                    } else {
                        openHub(player);
                    }
                }
                default -> openHub(player);
            }
            return;
        }

        switch (holder.screen) {
            case HUB -> clickHub(player, event, slot);
            case ANOMALIES -> clickAnomalies(player, event, holder, slot);
            case ABILITIES -> clickAbilities(player, event, holder, slot);
            case DROPS -> clickDrops(player, event, holder, slot);
            case SETTINGS -> clickSettings(player, event, holder, slot);
            case MINION_CATEGORIES -> clickCategories(player, event, holder, slot);
            case CATEGORY_EDIT -> clickCategoryEdit(player, event, holder, slot);
            case MINIONS -> clickMinions(player, event, holder, slot);
            case MINION_EDIT -> clickMinionEdit(player, event, holder, slot);
            case MINION_ABILITIES -> clickMinionAbilities(player, event, holder, slot);
            case SPAWNERS -> clickSpawners(player, event, holder, slot);
            case SPAWNER_EDIT -> clickSpawnerEdit(player, event, holder, slot);
        }
    }

    private void clickHub(Player player, InventoryClickEvent event, int slot) {
        switch (slot) {
            case 15 -> {
                AnomalyType type = plugin.selected();
                if (type == null) {
                    deny(player, "Elige una anomalia primero.");
                    return;
                }
                if (event.isRightClick()) {
                    if (plugin.registry().spawnPoint(type) == null) {
                        deny(player, "Esa anomalia ya aparece en sitios aleatorios.");
                        return;
                    }
                    plugin.registry().clearSpawnPoint(type);
                    click(player, 0.8f);
                    player.sendMessage(plugin.prefix()
                            .append(Component.text(type.display(), type.color(), TextDecoration.BOLD))
                            .append(Component.text(" vuelve a aparecer en un sitio aleatorio.", MenuUtil.SOFT)));
                    render(event.getInventory(), player, (Holder) event.getInventory().getHolder());
                    return;
                }
                click(player, 1.4f);
                plugin.spawnMarker().begin(player, type);
            }
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
                player.sendMessage(plugin.prefix().append(Component.text(
                        plugin.registry().spawnPoint(type) != null
                                ? "Abriendo la anomalia en su punto marcado..."
                                : "Buscando un sitio libre para la anomalia...", MenuUtil.SOFT)));
                // El menu se queda abierto a proposito: en cuanto aparezca, el boton de
                // viajar esta justo arriba y se quiere poder usar sin volver a abrirlo.
                plugin.manager().start(type, ok -> {
                    if (!ok) {
                        player.sendMessage(plugin.prefix().append(Component.text(
                                "No se encontro ningun sitio valido. Prueba a bajar la distancia minima "
                                        + "o el margen de proteccion en Ajustes.", NamedTextColor.RED)));
                    }
                    if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() instanceof Holder h
                            && h.screen == Screen.HUB) {
                        render(player.getOpenInventory().getTopInventory(), player, h);
                        if (ok) {
                            player.sendMessage(plugin.prefix().append(Component.text(
                                    "Lista. Pulsa ", MenuUtil.SOFT))
                                    .append(Component.text("Ir a la anomalia", NamedTextColor.LIGHT_PURPLE,
                                            TextDecoration.BOLD))
                                    .append(Component.text(" para viajar.", MenuUtil.SOFT)));
                        }
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

        if (event.isShiftClick() && event.isRightClick()) {
            // Cicla Esbirro -> General -> Monarca y reordena el catalogo al momento.
            net.ederus.edm.anomaly.core.AnomalyClass next = plugin.registry().classOf(type).next();
            plugin.registry().setClass(type, next);
            click(player, 1.3f);
            player.sendActionBar(Component.text(type.display() + "  ", MenuUtil.SOFT)
                    .append(Component.text(next.display().toUpperCase(java.util.Locale.ROOT),
                            next.color(), TextDecoration.BOLD)));
        } else if (event.isShiftClick()) {
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
            int step = (event.isShiftClick() ? 5000 : 500) * (up ? 1 : -1);
            plugin.registry().setHealth(type, plugin.registry().health(type) + step);
            click(player, up ? 1.4f : 0.9f);
            player.sendActionBar(Component.text("Vida base de " + type.display() + "  ", MenuUtil.SOFT)
                    .append(Component.text((int) plugin.registry().health(type), NamedTextColor.GREEN,
                            TextDecoration.BOLD)));
            render(event.getInventory(), player, holder);
            return;
        }
        if (slot == 51) {
            double step = (event.isShiftClick() ? 1.0 : 0.1) * (up ? 1 : -1);
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
            int step = event.isShiftClick() ? 1000 : 100;
            table.experience(table.experience() + (slot == 50 ? step : -step));
            click(player, slot == 50 ? 1.4f : 0.8f);
            plugin.drops().save();
            render(event.getInventory(), player, holder);
            return;
        }

        int index = indexOf(BODY, slot);
        if (index < 0) return;

        if (holder.placeMode) {
            // Shift + click sobre un objeto de la tabla: marcarlo (o desmarcarlo) como
            // UNICO. Va en shift a proposito: es el unico gesto que Bedrock tambien tiene.
            if (event.isShiftClick() && table.get(index) != null) {
                markUnique(player, table, index);
                plugin.drops().save();
                render(event.getInventory(), player, holder);
                return;
            }
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
            if (event.getClick() == ClickType.SWAP_OFFHAND || event.getClick() == ClickType.MIDDLE) {
                markUnique(player, table, index);
            } else if (event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP) {
                table.remove(index);
                click(player, 0.7f);
            } else if (event.isShiftClick() && event.isLeftClick()) {
                if (minionOf(holder.context) != null) return; // en esbirros no hay "para quien"
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

    /** Marca o desmarca el UNICO de la tabla, con su sonido y su aviso. */
    private void markUnique(Player player, DropTable table, int index) {
        boolean marked = table.markUnique(index);
        click(player, marked ? 1.8f : 0.8f);
        if (marked) {
            Compat.sound(player.getWorld(), player.getLocation(), "block.amethyst_block.resonate", 0.8f, 1.5f);
            player.sendActionBar(Component.text("✦ ", NamedTextColor.AQUA)
                    .append(Component.text("OBJETO UNICO", NamedTextColor.AQUA, TextDecoration.BOLD))
                    .append(Component.text("  queda en super raro; ajusta el % si quieres", MenuUtil.SOFT)));
        } else {
            player.sendActionBar(Component.text("Ya no es el objeto unico.", MenuUtil.SOFT));
        }
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
