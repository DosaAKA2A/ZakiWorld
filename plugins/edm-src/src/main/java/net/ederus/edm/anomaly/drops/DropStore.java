package net.ederus.edm.anomaly.drops;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

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
 */
public final class DropStore {

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
                    table.entries().add(entry);
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
                "comandos: se ejecutan desde la consola. %jugador% se sustituye por el nombre.",
                "          si el comando empieza por [mejor] solo se ejecuta para quien mas dano hizo."));
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
            }
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "No se pudo guardar drops.yml", ex);
        }
    }

    // -------------------------------------------------------------------- reparto

    /**
     * Reparte la tabla entre los participantes.
     *
     * @param damage dano acumulado por jugador; decide quien es "el mejor" y pondera los sorteos
     * @param where  donde tirar al suelo lo que no quepa en el inventario
     * @return un resumen legible de lo que se dio, para el log y para el chat
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

        for (DropEntry entry : table.entries()) {
            if (random.nextDouble() * 100.0 > entry.chance()) continue;
            int amount = entry.min() + (entry.max() > entry.min() ? random.nextInt(entry.max() - entry.min() + 1) : 0);
            if (amount <= 0) continue;

            List<Player> targets = switch (entry.to()) {
                case TODOS -> participants;
                case MEJOR -> best == null ? List.of() : List.of(best);
                case ALEATORIO -> List.of(weighted(participants, damage));
            };
            for (Player p : targets) {
                if (p == null) continue;
                give(p, entry.item(), amount, where);
            }
            report.add(amount + "x " + entry.item().getType() + " -> " + entry.to().name());
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
            boolean onlyBest = raw.toLowerCase(java.util.Locale.ROOT).startsWith("[mejor]");
            String cmd = onlyBest ? raw.substring("[mejor]".length()).trim() : raw.trim();
            if (cmd.isEmpty()) continue;
            List<Player> targets = onlyBest ? (best == null ? List.<Player>of() : List.of(best)) : participants;
            for (Player p : targets) {
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

    /** Mete el objeto en el inventario y lo que no quepa lo deja en el suelo, nunca lo pierde. */
    private void give(Player p, ItemStack template, int amount, Location where) {
        int left = amount;
        int stackSize = Math.max(1, template.getType().getMaxStackSize());
        while (left > 0) {
            int chunk = Math.min(left, stackSize);
            ItemStack copy = template.clone();
            copy.setAmount(chunk);
            Map<Integer, ItemStack> overflow = p.getInventory().addItem(copy);
            for (ItemStack rest : overflow.values()) {
                Location drop = p.isOnline() ? p.getLocation() : where;
                if (drop != null && drop.getWorld() != null) drop.getWorld().dropItemNaturally(drop, rest);
            }
            left -= chunk;
        }
    }
}
