/*
 * Decompiled with CFR 0.152.
 */
package net.ederus.edm.rip;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.ederus.edm.rip.Compat;
import net.ederus.edm.rip.RipEffect;
import net.ederus.edm.rip.RipPlugin;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

public final class HeadCache {
    private final RipPlugin plugin;
    private final Map<String, ItemStack> cache = new HashMap<String, ItemStack>();
    private int loaded;
    private int failed;

    public HeadCache(RipPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        this.cache.clear();
        this.loaded = 0;
        this.failed = 0;
        File file = new File(this.plugin.getDataFolder(), "heads.yml");
        if (!file.exists()) {
            this.plugin.saveResource("heads.yml", false);
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration((File)file);
        InputStream in = this.plugin.getResource("heads.yml");
        if (in != null) {
            yml.setDefaults((Configuration)YamlConfiguration.loadConfiguration((Reader)new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
        for (RipEffect e : RipEffect.values()) {
            this.put(e.headKey(), yml.getString(e.headKey()));
        }
        this.put("ui.tab_kill", yml.getString("ui.tab_kill"));
        this.put("ui.tab_death", yml.getString("ui.tab_death"));
        this.put("ui.random", yml.getString("ui.random"));
    }

    private void put(String key, String hash) {
        if (hash == null || hash.isBlank()) {
            return;
        }
        ItemStack head = Compat.head(hash);
        if (head == null) {
            ++this.failed;
            this.plugin.getLogger().warning("Textura de cabeza invalida para '" + key + "', se usara el item de respaldo.");
            return;
        }
        this.cache.put(key, head);
        ++this.loaded;
    }

    public ItemStack get(String key) {
        ItemStack head = this.cache.get(key);
        return head == null ? null : head.clone();
    }

    public int loaded() {
        return this.loaded;
    }

    public int failed() {
        return this.failed;
    }
}

