package net.ederus.edm.anomaly.drops;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.ederus.edm.anomaly.core.AnomalyType;
import net.ederus.edm.anomaly.core.Compat;
import net.ederus.edm.anomaly.core.Tags;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Guarda y reparte el botin. Cada anomalia tiene su propia tabla en drops.yml,
 * y se escribe con la serializacion nativa de Bukkit para que los objetos de MMOItems
 * conserven su contenedor de datos y sigan siendo el mismo item al caer.
 *
 * El reparto es una EXPLOSION: al caer el jefe, todo el botin sale disparado de su
 * cuerpo en todas direcciones, como una pinata, para que recogerlo sea parte de la
 * pelea. Lo reservado (al mejor, a uno al azar) sale igual en la explosion pero con
 * dueno: nadie mas puede levantarlo del suelo.
 */
public final class DropStore implements Listener {

    private final net.ederus.edm.anomaly.AnomalyPlugin plugin;
    private final Map<String, DropTable> tables = new HashMap<>();
    private final Random random = new Random();
    private File file;

    public DropStore(net.ederus.edm.anomaly.AnomalyPlugin plugin) {
        this.plugin = plugin;
    }

    public DropTable table(String anomalyId) {
        return tables.computeIfAbsent(anomalyId, DropTable::new);
    }

    // ---------------------------------------------------------------------- disco

    public void load() {
        tables.clear();
        file = new File(plugin.getDataFolder(), "drops.yml");
        if (!file.exists()) {
            plugin.saveResource("drops.yml", false);
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("anomalias");
        if (root == null) return;

        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) continue;
            DropTable table = new DropTable(id);
            table.experience(sec.getInt("experiencia", 500));
            table.commands().addAll(sec.getStringList("comandos"));

            ConfigurationSection botin = sec.getConfigurationSection("botin");
            if (botin != null) {
                List<String> keys = new ArrayList<>(botin.getKeys(false));
                keys.sort(DropStore::compareNumericKeys);
                for (String key : keys) {
                    ConfigurationSection e = botin.getConfigurationSection(key);
                    if (e == null) continue;
                    ItemStack item = e.getItemStack("item");
                    if (item == null) continue;
                    DropEntry.Recipient to;
                    try {
                        to = DropEntry.Recipient.valueOf(e.getString("para", "TODOS"));
                    } catch (IllegalArgumentException ex) {
                        to = DropEntry.Recipient.TODOS;
                    }
                    DropEntry entry = new DropEntry(item, e.getDouble("probabilidad", 100), 1, 1, to);
                    entry.amount(e.getInt("cantidad-min", item.getAmount()), e.getInt("cantidad-max", item.getAmount()));
                    entry.chance(e.getDouble("probabilidad", 100));
                    entry.unique(e.getBoolean("unico", false));
                    table.entries().add(entry);
                }
                // Por si alguien edito el yml a mano y marco dos: solo puede haber uno.
                boolean seen = false;
                for (DropEntry e : table.entries()) {
                    if (!e.unique()) continue;
                    if (seen) e.unique(false);
                    seen = true;
                }
            }
            tables.put(id, table);
        }
        plugin.getLogger().info("Tablas de botin cargadas: " + tables.size());
    }

    private static int compareNumericKeys(String a, String b) {
        try {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        } catch (NumberFormatException ex) {
            return a.compareTo(b);
        }
    }

    public void save() {
        if (file == null) file = new File(plugin.getDataFolder(), "drops.yml");
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "Tablas de botin de Anomaly.",
                "Se edita desde el menu (/anomaly menu -> Botin), pero se puede tocar a mano.",
                "Cada objeto se guarda tal cual, con su NBT, asi que los items de MMOItems",
                "se pueden arrastrar directamente al menu y caen identicos.",
                "",
                "unico: true marca el objeto UNICO de la tabla (uno como mucho): sale",
                "       brillando en la explosion y el chat anuncia quien se lo llevo.",
                "",
                "comandos: se ejecutan desde la consola. %jugador% se sustituye por el nombre.",
                "          [mejor] -> solo para quien mas dano hizo. [35%] -> probabilidad por jugador.",
                "          Se combinan: [mejor] [25%] crates key give %jugador% legendary 1"));
        for (DropTable table : tables.values()) {
            String base = "anomalias." + table.anomalyId();
            yml.set(base + ".experiencia", table.experience());
            yml.set(base + ".comandos", table.commands());
            int i = 0;
            for (DropEntry e : table.entries()) {
                String p = base + ".botin." + i++;
                yml.set(p + ".item", e.item());
                yml.set(p + ".probabilidad", e.chance());
                yml.set(p + ".cantidad-min", e.min());
                yml.set(p + ".cantidad-max", e.max());
                yml.set(p + ".para", e.to().name());
                if (e.unique()) yml.set(p + ".unico", true);
            }
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "No se pudo guardar drops.yml", ex);
        }
    }

    // -------------------------------------------------------------------- reparto

    /** 36 stacks: mas que eso no es un premio, es un fallo de configuracion. */
    private static final int MAX_POR_ENTRADA = 2304;

    /**
     * Revienta la tabla de botin desde el cuerpo del jefe.
     *
     * @param damage dano acumulado por jugador; decide quien es "el mejor" y pondera los sorteos
     * @param where  el punto de la explosion: donde murio el jefe
     * @return un resumen legible de lo que cayo, para el log
     */
    public List<String> award(String anomalyId, Map<UUID, Double> damage, Location where) {
        DropTable table = tables.get(anomalyId);
        List<String> report = new ArrayList<>();
        if (table == null || damage.isEmpty()) return report;

        List<Player> participants = new ArrayList<>();
        for (UUID id : damage.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) participants.add(p);
        }
        if (participants.isEmpty()) return report;

        Player best = null;
        double bestDamage = -1;
        for (Player p : participants) {
            double d = damage.getOrDefault(p.getUniqueId(), 0.0);
            if (d > bestDamage) {
                bestDamage = d;
                best = p;
            }
        }

        boolean anyBurst = false;
        for (DropEntry entry : table.entries()) {
            if (random.nextDouble() * 100.0 > entry.chance()) continue;
            int amount = entry.min() + (entry.max() > entry.min() ? random.nextInt(entry.max() - entry.min() + 1) : 0);
            if (amount <= 0) continue;
            if (amount > MAX_POR_ENTRADA) {
                /* Un cero de mas en drops.yml no puede llenar el suelo de la
                 * arena con miles de items y tirar el tick del servidor. */
                plugin.getLogger().warning("El botin de " + anomalyId + " pedia " + amount + " x "
                        + entry.item().getType() + "; se recorta a " + MAX_POR_ENTRADA
                        + ". Revisa cantidad-max en drops.yml.");
                amount = MAX_POR_ENTRADA;
            }

            Player reserved = switch (entry.to()) {
                case TODOS -> null;
                case MEJOR -> best;
                case ALEATORIO -> weighted(participants, damage);
            };
            burst(where, entry, amount, reserved, anomalyId);
            anyBurst = true;
            report.add(amount + "x " + entry.item().getType()
                    + (entry.unique() ? " [UNICO]" : "")
                    + " -> " + entry.to().name()
                    + (reserved != null ? " (" + reserved.getName() + ")" : ""));
        }
        if (anyBurst && where.getWorld() != null) {
            // El estallido de la pinata: se oye y se ve que algo acaba de saltar por los aires.
            Compat.spawn(where.getWorld(), Compat.FIREWORK, where.clone().add(0, 1.2, 0), 24, 0.4, 0.5, 0.4, 0.08);
            Compat.sound(where.getWorld(), where, "entity.firework_rocket.large_blast", 0.9f, 0.8f);
            Compat.sound(where.getWorld(), where, "entity.item.pickup", 0.8f, 0.6f);
        }

        if (table.experience() > 0) {
            for (Player p : participants) p.giveExp(table.experience());
        }

        // El logro va por participar de verdad, no por dar el ultimo golpe.
        for (Player p : participants) {
            try {
                plugin.advancements().award(p, anomalyId);
            } catch (Throwable t) {
                plugin.getLogger().warning("No se pudo conceder el logro de " + anomalyId + ": " + t);
            }
        }

        for (String raw : table.commands()) {
            /*
             * Prefijos, combinables y en cualquier orden:
             *   [mejor]  solo corre para quien mas daño hizo
             *   [35%]    corre solo si sale el dado (una tirada POR JUGADOR,
             *            para que la suerte de uno no arrastre a los demas)
             * Sin prefijo de porcentaje el comando es seguro: 100%.
             */
            boolean onlyBest = false;
            double chance = 100.0;
            String cmd = raw.trim();
            boolean pelando = true;
            while (pelando) {
                pelando = false;
                String bajo = cmd.toLowerCase(java.util.Locale.ROOT);
                if (bajo.startsWith("[mejor]")) {
                    onlyBest = true;
                    cmd = cmd.substring("[mejor]".length()).trim();
                    pelando = true;
                    continue;
                }
                int cierre = cmd.indexOf("%]");
                if (cmd.startsWith("[") && cierre > 1) {
                    String num = cmd.substring(1, cierre);
                    boolean numerico = !num.isEmpty();
                    for (int k = 0; k < num.length(); k++) {
                        char ch = num.charAt(k);
                        if (!Character.isDigit(ch) && ch != '.') { numerico = false; break; }
                    }
                    if (numerico) {
                        try {
                            chance = Math.max(0.0, Math.min(100.0, Double.parseDouble(num)));
                        } catch (NumberFormatException ignored) {
                        }
                        cmd = cmd.substring(cierre + 2).trim();
                        pelando = true;
                    }
                }
            }
            if (cmd.isEmpty()) continue;
            List<Player> targets = onlyBest ? (best == null ? List.<Player>of() : List.of(best)) : participants;
            for (Player p : targets) {
                if (chance < 100.0 && random.nextDouble() * 100.0 >= chance) continue;
                String finalCmd = cmd.replace("%jugador%", p.getName()).replace("%player%", p.getName());
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Comando de botin fallido: " + finalCmd + " (" + t.getMessage() + ")");
                }
            }
        }
        return report;
    }

    /**
     * Revienta la tabla AQUI MISMO para verla: cada objeto sale una vez con su
     * cantidad maxima, sin duenos, sin experiencia, sin comandos y sin logros.
     * Es la forma de comprobar como queda la explosion sin matar a nadie.
     *
     * @return cuantas lineas de botin se tiraron
     */
    public int preview(String anomalyId, Location where) {
        DropTable table = tables.get(anomalyId);
        if (table == null || table.entries().isEmpty() || where.getWorld() == null) return 0;
        for (DropEntry entry : table.entries()) {
            burst(where, entry, Math.min(entry.max(), MAX_POR_ENTRADA), null, anomalyId);
        }
        Compat.spawn(where.getWorld(), Compat.FIREWORK, where.clone().add(0, 1.2, 0), 24, 0.4, 0.5, 0.4, 0.08);
        Compat.sound(where.getWorld(), where, "entity.firework_rocket.large_blast", 0.9f, 0.8f);
        Compat.sound(where.getWorld(), where, "entity.item.pickup", 0.8f, 0.6f);
        return table.entries().size();
    }

    private Player weighted(List<Player> players, Map<UUID, Double> damage) {
        double total = 0;
        for (Player p : players) total += Math.max(1, damage.getOrDefault(p.getUniqueId(), 0.0));
        double roll = random.nextDouble() * total;
        for (Player p : players) {
            roll -= Math.max(1, damage.getOrDefault(p.getUniqueId(), 0.0));
            if (roll <= 0) return p;
        }
        return players.get(players.size() - 1);
    }

    /**
     * Tira una linea de botin al mundo en varios montones que salen DISPARADOS en
     * direcciones distintas. Los items van invulnerables para que el fuego o las
     * explosiones que deje la propia pelea no se coman el premio.
     *
     * Un item con dueno solo lo puede recoger su dueno: la reserva se respeta aunque
     * este tirado en mitad de la rebatinga.
     */
    private void burst(Location where, DropEntry entry, int amount, Player reserved, String anomalyId) {
        World w = where.getWorld();
        if (w == null) return;
        Location from = where.clone().add(0, 1.6, 0);

        // El UNICO cae entero, un solo objeto brillando; lo demas se parte en montones
        // para que la explosion reparta de verdad por el suelo. Ningun monton puede
        // superar el stack del material: un item con 400 de cantidad no es legal.
        int stackSize = Math.max(1, entry.item().getMaxStackSize());
        int minPiles = (amount + stackSize - 1) / stackSize;
        int piles = entry.unique() ? Math.max(1, minPiles)
                : Math.max(minPiles, Math.min(3 + random.nextInt(3), amount));
        int per = amount / piles;
        int rest = amount % piles;
        for (int i = 0; i < piles; i++) {
            int n = per + (i < rest ? 1 : 0);
            if (n <= 0) continue;
            ItemStack copy = entry.item().clone();
            copy.setAmount(n);
            Item it = w.dropItem(from, copy);
            double angle = random.nextDouble() * Math.PI * 2;
            /* LLUVIA de botin de verdad: los montones salen DISPARADOS lejos del
             * cuerpo (entre ~3 y ~20 bloques), no gotean a sus pies. Un item con
             * empuje horizontal v recorre ~22*v bloques antes de frenar. */
            double push = 0.16 + random.nextDouble() * 0.75;
            it.setVelocity(new Vector(Math.cos(angle) * push,
                    0.40 + random.nextDouble() * 0.25, Math.sin(angle) * push));
            it.setInvulnerable(true);
            if (reserved != null) it.setOwner(reserved.getUniqueId());
            if (entry.unique()) {
                it.setGlowing(true);
                Tags.markUniqueDrop(it, anomalyId);
            }
        }
    }

    /**
     * El aviso del UNICO: cuando alguien levanta del suelo el objeto marcado, todo el
     * servidor se entera de quien fue. Es la gracia de marcarlo.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        String anomalyId = Tags.uniqueDropOf(e.getItem());
        if (anomalyId == null || !(e.getEntity() instanceof Player p)) return;
        AnomalyType type = plugin.registry().get(anomalyId);
        Component itemName = DropTable.nameOf(e.getItem().getItemStack());
        Component who = Component.text("✦ ", NamedTextColor.AQUA)
                .append(Component.text(p.getName(), NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(" se llevo el objeto ", NamedTextColor.GRAY))
                .append(Component.text("UNICO", NamedTextColor.AQUA, TextDecoration.BOLD))
                .append(Component.text(" de ", NamedTextColor.GRAY))
                .append(type == null
                        ? Component.text(anomalyId, NamedTextColor.WHITE)
                        : Component.text(type.display(), type.color(), TextDecoration.BOLD))
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(itemName.colorIfAbsent(NamedTextColor.AQUA));
        plugin.getServer().sendMessage(who);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            Compat.sound(online.getWorld(), online.getLocation(), "block.amethyst_block.resonate", 0.7f, 1.4f);
        }
    }
}
