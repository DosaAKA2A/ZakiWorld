/*
 * Decompiled with CFR 0.152.
 */
package net.zakiworld.rip;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.zakiworld.rip.Compat;
import net.zakiworld.rip.EffectRunner;
import net.zakiworld.rip.HeadCache;
import net.zakiworld.rip.MannequinHook;
import net.zakiworld.rip.Rarity;
import net.zakiworld.rip.RipCommand;
import net.zakiworld.rip.RipEffect;
import net.zakiworld.rip.RipMenu;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class RipPlugin
extends JavaPlugin
implements Listener {
    private static final String VERSION = "3.1.9";
    private final Map<UUID, String> killChoice = new ConcurrentHashMap<UUID, String>();
    private final Map<UUID, String> deathChoice = new ConcurrentHashMap<UUID, String>();
    private final Map<UUID, Long> busyUntil = new ConcurrentHashMap<UUID, Long>();
    private final Set<UUID> hiddenBodies = ConcurrentHashMap.newKeySet();
    private Set<RipEffect> hideBody = EnumSet.noneOf(RipEffect.class);
    private Map<RipEffect, Long> cooldownOverrides = new EnumMap<RipEffect, Long>(RipEffect.class);
    private final Deque<Long> runningUntil = new ArrayDeque<Long>();
    private int maxConcurrent;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private File selFile;
    private FileConfiguration selConfig;
    private EffectRunner runner;
    private RipMenu menu;
    private HeadCache heads;
    private Component prefix;
    private long cooldownMillis;

    public void onEnable() {
        this.saveDefaultConfig();
        this.mergeMissingConfigSections();
        this.reloadSettings();
        this.heads = new HeadCache(this);
        this.heads.load();
        this.runner = new EffectRunner(this);
        this.menu = new RipMenu(this);
        this.loadSelections();
        Bukkit.getPluginManager().registerEvents((Listener)this, (Plugin)this);
        Bukkit.getPluginManager().registerEvents((Listener)this.menu, (Plugin)this);
        RipCommand command = new RipCommand(this);
        this.getCommand("rip").setExecutor((CommandExecutor)command);
        this.getCommand("rip").setTabCompleter((TabCompleter)command);
        Bukkit.getScheduler().runTaskTimerAsynchronously((Plugin)this, () -> {
            if (this.dirty.compareAndSet(true, false)) {
                this.saveSelections();
            }
        }, 600L, 600L);
        Bukkit.getScheduler().runTaskTimer((Plugin)this, this::revealStrays, 100L, 100L);
        this.banner();
    }

    public void onDisable() {
        this.revealAll();
        if (this.runner != null) {
            this.runner.cancelAll();
        }
        if (this.selConfig != null) {
            this.saveSelections();
        }
    }

    /*
     * Va por el ConsoleCommandSender y no por el logger para que salga en color
     * y sin el prefijo [Rip] machacando cada linea del dibujo.
     */
    private void banner() {
        String v = this.getServer().getBukkitVersion();
        int missing = Compat.missingParticles();
        String cloneMode = MannequinHook.available() ? "Mannequin (skin completa)" : "ArmorStand (cabeza)";
        CommandSender console = Bukkit.getConsoleSender();
        String[] art = new String[]{
            "       ___                       ___",
            "      /\\  \\                     /\\  \\",
            "     /::\\  \\       ___         /::\\  \\",
            "    /:/\\:\\__\\     /\\__\\       /:/\\:\\__\\",
            "   /:/ /:/  /    /:/__/      /:/ /:/  /",
            "  /:/_/:/__/___ /::\\  \\     /:/_/:/  /",
            "  \\:\\/:::::/  / \\/\\:\\  \\__  \\:\\/:/  /",
            "   \\::/~~/~~~~   ~~\\:\\/\\__\\  \\::/__/",
            "    \\:\\~~\\          \\::/  /   \\:\\  \\",
            "     \\:\\__\\         /:/  /     \\:\\__\\",
            "      \\/__/         \\/__/       \\/__/"};
        TextColor rule = TextColor.color((int)0x6E0000);
        console.sendMessage((Component)Component.empty());
        console.sendMessage((Component)Component.text((String)"  ============================================", (TextColor)rule));
        console.sendMessage((Component)Component.empty());
        for (int i = 0; i < art.length; ++i) {
            float t = (float)i / (float)(art.length - 1);
            int r = Math.round(255.0f + -96.0f * t);
            int g = Math.round(90.0f + -90.0f * t);
            console.sendMessage((Component)Component.text((String)art[i], (TextColor)TextColor.color((int)r, (int)g, (int)g), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}));
        }
        console.sendMessage((Component)Component.empty());
        console.sendMessage(((TextComponent)((TextComponent)Component.text((String)"      R I P  ", (TextColor)TextColor.color((int)0xFF3030), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}).append((Component)Component.text((String)("v" + VERSION), (TextColor)TextColor.color((int)0xFFD966), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}))).append((Component)Component.text((String)"   por ", (TextColor)TextColor.color((int)0x707070)))).append((Component)Component.text((String)"Dosa e Iris Studio", (TextColor)TextColor.color((int)0xFFFFFF))));
        console.sendMessage((Component)Component.empty());
        this.bannerRow(console, "Efectos", RipEffect.count(RipEffect.Type.KILL) + " kill  \u00b7  " + RipEffect.count(RipEffect.Type.DEATH) + " muerte  \u00b7  " + Rarity.values().length + " calidades");
        this.bannerRow(console, "Cabezas", this.heads.loaded() + " cargadas" + (this.heads.failed() > 0 ? "  \u00b7  " + this.heads.failed() + " con fallo" : ""));
        this.bannerRow(console, "Clones", cloneMode);
        this.bannerRow(console, "Servidor", v + (missing > 0 ? "  \u00b7  " + missing + " particulas no disponibles" : ""));
        console.sendMessage((Component)Component.empty());
        console.sendMessage((Component)Component.text((String)"  ============================================", (TextColor)rule));
        console.sendMessage((Component)Component.empty());
    }

    private void bannerRow(CommandSender console, String label, String value) {
        console.sendMessage(((TextComponent)Component.text((String)"      \u00bb ", (TextColor)TextColor.color((int)0xFF3030)).append((Component)Component.text((String)String.format("%-10s", label), (TextColor)TextColor.color((int)0x909090)))).append((Component)Component.text((String)value, (TextColor)TextColor.color((int)0xE0E0E0))));
    }

    /*
     * saveDefaultConfig() no toca un config.yml que ya existe, asi que al
     * actualizar el plugin las claves nuevas no aparecerian nunca en el archivo
     * del servidor y no habria nada que editar. Aqui se anaden las que falten,
     * con sus comentarios y sin tocar lo que el usuario ya tenga puesto.
     */
    private void mergeMissingConfigSections() {
        File file = new File(this.getDataFolder(), "config.yml");
        if (!file.exists()) {
            return;
        }
        List<String> defaults = this.defaultConfigLines();
        if (defaults.isEmpty()) {
            return;
        }
        ArrayList<String> added = new ArrayList<String>();
        StringBuilder append = new StringBuilder();
        ArrayList<String> lead = new ArrayList<String>();
        int i = 0;
        while (i < defaults.size()) {
            String line = defaults.get(i);
            if (line.isBlank() || line.startsWith("#")) {
                lead.add(line);
                ++i;
                continue;
            }
            if (line.startsWith(" ") || line.startsWith("\t") || line.startsWith("-")) {
                lead.clear();
                ++i;
                continue;
            }
            int colon = line.indexOf(58);
            String key = colon > 0 ? line.substring(0, colon).trim() : "";
            ArrayList<String> block = new ArrayList<String>(lead);
            block.add(line);
            lead.clear();
            int j = i + 1;
            while (j < defaults.size()) {
                String next = defaults.get(j);
                if (next.isBlank() || !next.startsWith(" ") && !next.startsWith("\t") && !next.startsWith("-")) break;
                block.add(next);
                ++j;
            }
            i = j;
            if (key.isEmpty() || this.getConfig().isSet(key)) continue;
            added.add(key);
            for (String out : block) {
                append.append(out).append('\n');
            }
        }
        if (added.isEmpty()) {
            return;
        }
        try {
            String current = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            StringBuilder merged = new StringBuilder(current);
            if (!current.endsWith("\n")) {
                merged.append('\n');
            }
            merged.append('\n').append(append);
            Files.write(file.toPath(), merged.toString().getBytes(StandardCharsets.UTF_8), new java.nio.file.OpenOption[0]);
            this.reloadConfig();
            this.getLogger().info("config.yml actualizado con las claves nuevas: " + String.join((CharSequence)", ", added));
        }
        catch (Throwable ex) {
            this.getLogger().log(Level.WARNING, "No se pudo anadir las claves nuevas a config.yml; se usan los valores de fabrica", ex);
        }
    }

    private List<String> defaultConfigLines() {
        ArrayList<String> out = new ArrayList<String>();
        try (InputStream in = this.getResource("config.yml");){
            if (in == null) {
                List<String> list = out;
                return list;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                out.add(line);
            }
        }
        catch (Throwable ex) {
            this.getLogger().log(Level.WARNING, "No se pudo leer el config.yml de fabrica", ex);
        }
        return out;
    }

    private void reloadSettings() {
        this.cooldownMillis = Math.max(0L, this.getConfig().getLong("cooldown-ms", 250L));
        this.maxConcurrent = Math.max(0, this.getConfig().getInt("max-animaciones-simultaneas", 3));
        this.hideBody = this.readHideBody();
        this.cooldownOverrides = this.readCooldowns();
        this.applyRarities();
        this.prefix = ((TextComponent)Component.text((String)"\u2726 ", (TextColor)TextColor.color((int)0xFF5555)).append((Component)Component.text((String)"RIP ", (TextColor)TextColor.color((int)0xFFFFFF), (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}))).append((Component)Component.text((String)"\u2502 ", (TextColor)TextColor.color((int)0x404040)));
    }

    private Set<RipEffect> readHideBody() {
        EnumSet<RipEffect> set = EnumSet.noneOf(RipEffect.class);
        if (!this.getConfig().isSet("ocultar-cuerpo")) {
            for (RipEffect e : RipEffect.values()) {
                if (!e.hidesBodyByDefault()) continue;
                set.add(e);
            }
            return set;
        }
        for (String raw : this.getConfig().getStringList("ocultar-cuerpo")) {
            if (raw == null) continue;
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            int dot = entry.indexOf(46);
            if (dot <= 0 || dot == entry.length() - 1) {
                this.getLogger().warning("ocultar-cuerpo: entrada invalida '" + raw + "', se esperaba kill.<id> o death.<id>");
                continue;
            }
            String typeName = entry.substring(0, dot);
            RipEffect.Type type = typeName.equals("kill") ? RipEffect.Type.KILL : (typeName.equals("death") ? RipEffect.Type.DEATH : null);
            RipEffect effect = type == null ? null : RipEffect.byId(type, entry.substring(dot + 1));
            if (effect == null) {
                this.getLogger().warning("ocultar-cuerpo: efecto desconocido '" + raw + "'");
                continue;
            }
            set.add(effect);
        }
        return set;
    }

    public boolean hidesBody(RipEffect effect) {
        return effect != null && this.hideBody.contains(effect);
    }

    /*
     * calidades:
     *   kill:
     *     swordfall: LEGENDARIO
     * Se anida por tipo a proposito: una clave suelta "kill.swordfall" la
     * partiria Bukkit por el punto y no se encontraria nunca.
     */
    /*
     * enfriamientos:
     *   kill:
     *     swordfall: 9.5
     * En segundos. Mismo anidado por tipo que "calidades", y por el mismo
     * motivo: Bukkit partiria una clave "kill.swordfall" por el punto.
     */
    private Map<RipEffect, Long> readCooldowns() {
        EnumMap<RipEffect, Long> out = new EnumMap<RipEffect, Long>(RipEffect.class);
        ConfigurationSection root = this.getConfig().getConfigurationSection("enfriamientos");
        if (root == null) {
            return out;
        }
        for (RipEffect.Type type : RipEffect.Type.values()) {
            ConfigurationSection section = root.getConfigurationSection(type.lower());
            if (section == null) continue;
            for (String id : section.getKeys(false)) {
                RipEffect effect = RipEffect.byId(type, id);
                if (effect == null) {
                    this.getLogger().warning("enfriamientos: efecto desconocido '" + type.lower() + "." + id + "'");
                    continue;
                }
                if (!section.isDouble(id) && !section.isInt(id) && !section.isLong(id)) {
                    this.getLogger().warning("enfriamientos: valor no numerico en " + type.lower() + "." + id + "; se deja el de fabrica");
                    continue;
                }
                double seconds = section.getDouble(id);
                if (seconds < 0.0 || seconds > 600.0) {
                    this.getLogger().warning("enfriamientos: " + type.lower() + "." + id + " fuera de rango (" + seconds + "); se admite de 0 a 600 segundos");
                    continue;
                }
                long millis = Math.round(seconds * 1000.0);
                if (millis == effect.defaultCooldownMillis()) continue;
                out.put(effect, Long.valueOf(millis));
                if (millis >= effect.animationMillis()) continue;
                this.getLogger().warning("enfriamientos: " + type.lower() + "." + id + " en " + seconds + "s es menos de lo que dura su animacion (" + RipPlugin.formatSeconds(effect.animationMillis()) + "); podran solaparse dos animaciones del mismo jugador");
            }
        }
        if (!out.isEmpty()) {
            this.getLogger().info("enfriamientos: " + out.size() + " efectos ajustados desde config.yml");
        }
        return out;
    }

    private void applyRarities() {
        EnumMap<RipEffect, Rarity> overrides = new EnumMap<RipEffect, Rarity>(RipEffect.class);
        ConfigurationSection root = this.getConfig().getConfigurationSection("calidades");
        if (root == null) {
            RipEffect.applyRarityOverrides(overrides);
            return;
        }
        for (RipEffect.Type type : RipEffect.Type.values()) {
            ConfigurationSection section = root.getConfigurationSection(type.lower());
            if (section == null) continue;
            for (String id : section.getKeys(false)) {
                RipEffect effect = RipEffect.byId(type, id);
                if (effect == null) {
                    this.getLogger().warning("calidades: efecto desconocido '" + type.lower() + "." + id + "'");
                    continue;
                }
                String value = section.getString(id);
                Rarity rarity = null;
                if (value != null) {
                    try {
                        rarity = Rarity.valueOf(value.trim().toUpperCase(Locale.ROOT));
                    }
                    catch (IllegalArgumentException illegalArgumentException) {
                        // empty catch block
                    }
                }
                if (rarity == null) {
                    this.getLogger().warning("calidades: calidad desconocida '" + value + "' en " + type.lower() + "." + id + "; se deja " + effect.defaultRarity().name());
                    continue;
                }
                if (rarity == effect.defaultRarity()) continue;
                overrides.put(effect, rarity);
            }
        }
        RipEffect.applyRarityOverrides(overrides);
        if (!overrides.isEmpty()) {
            this.getLogger().info("calidades: " + overrides.size() + " efectos reasignados desde config.yml");
        }
    }

    /*
     * Esconder al muerto del resto de clientes en el mismo tick de la muerte
     * evita que se vea la caida de lado de vanilla por debajo de la animacion.
     * Se vuelve a mostrar al reaparecer; el barrido de revealStrays() es la red
     * de seguridad para que nadie se quede invisible.
     */
    private void hideBody(Player victim) {
        if (!this.hiddenBodies.add(victim.getUniqueId())) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals((Object)victim)) continue;
            try {
                p.hidePlayer((Plugin)this, victim);
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
    }

    private void revealBody(Player victim) {
        if (!this.hiddenBodies.remove(victim.getUniqueId())) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals((Object)victim)) continue;
            try {
                p.showPlayer((Plugin)this, victim);
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
    }

    private void revealStrays() {
        if (this.hiddenBodies.isEmpty()) {
            return;
        }
        for (UUID id : new ArrayList<UUID>(this.hiddenBodies)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                this.hiddenBodies.remove(id);
                continue;
            }
            if (p.isDead()) continue;
            this.revealBody(p);
        }
    }

    private void revealAll() {
        for (UUID id : new ArrayList<UUID>(this.hiddenBodies)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                this.hiddenBodies.remove(id);
                continue;
            }
            this.revealBody(p);
        }
        this.hiddenBodies.clear();
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        this.revealBody(event.getPlayer());
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.hiddenBodies.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        for (UUID id : this.hiddenBodies) {
            Player dead = Bukkit.getPlayer(id);
            if (dead == null || dead.equals((Object)joined)) continue;
            try {
                joined.hidePlayer((Plugin)this, dead);
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
    }

    public void reloadAll() {
        this.reloadConfig();
        this.reloadSettings();
        this.heads.load();
        this.runner.cancelAll();
    }

    public RipMenu menu() {
        return this.menu;
    }

    public EffectRunner runner() {
        return this.runner;
    }

    public HeadCache heads() {
        return this.heads;
    }

    public Component prefix() {
        return this.prefix;
    }

    public static String version() {
        return VERSION;
    }

    public boolean allows(Player p, RipEffect e) {
        return p.hasPermission("rip.*") || p.hasPermission(e.permission());
    }

    public boolean isAdmin(Player p) {
        return p.hasPermission("rip.admin");
    }

    public void click(Player p, float pitch) {
        Compat.sound(p.getWorld(), p.getLocation(), "ui.button.click", 0.6f, pitch);
    }

    public RipEffect resolveEffect(Player p, RipEffect.Type type) {
        String choice = this.getChoice(p.getUniqueId(), type);
        if (choice == null) {
            return null;
        }
        if ("random".equalsIgnoreCase(choice)) {
            ArrayList<RipEffect> pool = new ArrayList<RipEffect>();
            for (RipEffect e : RipEffect.of(type)) {
                if (!this.allows(p, e)) continue;
                pool.add(e);
            }
            return pool.isEmpty() ? null : (RipEffect)((Object)pool.get(ThreadLocalRandom.current().nextInt(pool.size())));
        }
        RipEffect e = RipEffect.byId(type, choice);
        return e != null && this.allows(p, e) ? e : null;
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        RipEffect kill;
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        boolean hide = false;
        RipEffect death = this.resolveEffect(victim, RipEffect.Type.DEATH);
        if (death != null && this.claim(victim.getUniqueId(), death)) {
            this.safePlay(death, victim.getLocation(), killer, victim);
            hide |= this.hidesBody(death);
        }
        if (killer != null && !killer.equals((Object)victim) && (kill = this.resolveEffect(killer, RipEffect.Type.KILL)) != null && this.claim(killer.getUniqueId(), kill)) {
            this.safePlay(kill, victim.getLocation(), killer, victim);
            hide |= this.hidesBody(kill);
        }
        if (hide) {
            this.hideBody(victim);
        }
    }

    @EventHandler(ignoreCancelled=true)
    public void onFxDealDamage(EntityDamageByEntityEvent event) {
        if (this.runner != null && this.runner.isFxEntity(event.getDamager())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled=true)
    public void onFxTakeDamage(EntityDamageEvent event) {
        if (this.runner != null && this.runner.isFxEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled=true)
    public void onFxInteract(PlayerInteractEntityEvent event) {
        if (this.runner != null && this.runner.isFxEntity(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    private void safePlay(RipEffect effect, Location loc, Player killer, Player victim) {
        try {
            this.runner.play(effect, loc, killer, victim);
        }
        catch (Throwable ex) {
            this.getLogger().log(Level.WARNING, "Fallo al reproducir el efecto " + effect.id(), ex);
        }
    }

    /*
     * De fabrica el enfriamiento es lo que dura la animacion, asi que un
     * jugador nunca puede tener dos corriendo a la vez. config.yml puede
     * cambiarlo efecto por efecto; cooldown-ms sigue siendo el minimo global.
     */
    public long effectCooldownMillis(RipEffect effect) {
        if (effect == null) {
            return this.cooldownMillis;
        }
        Long override = this.cooldownOverrides.get(effect);
        return Math.max(this.cooldownMillis, override != null ? override.longValue() : effect.defaultCooldownMillis());
    }

    public long cooldownRemaining(UUID uuid) {
        Long until = this.busyUntil.get(uuid);
        if (until == null) {
            return 0L;
        }
        long left = until - System.currentTimeMillis();
        return left > 0L ? left : 0L;
    }

    /*
     * Un jugador no puede lanzar otra animacion hasta que se le acabe el
     * enfriamiento: si mata a cinco de golpe, solo suena la primera. El limite
     * global es aparte y cuenta animaciones de cualquiera a la vez, que es lo
     * unico que frena a cinco victimas distintas muriendo en el mismo tick.
     */
    public boolean claim(UUID uuid, RipEffect effect) {
        long now = System.currentTimeMillis();
        Long until = this.busyUntil.get(uuid);
        if (until != null && now < until) {
            return false;
        }
        if (!this.claimGlobalSlot(now, effect)) {
            return false;
        }
        this.busyUntil.put(uuid, now + this.effectCooldownMillis(effect));
        return true;
    }

    private synchronized boolean claimGlobalSlot(long now, RipEffect effect) {
        this.runningUntil.removeIf(end -> end.longValue() <= now);
        if (this.maxConcurrent > 0 && this.runningUntil.size() >= this.maxConcurrent) {
            return false;
        }
        this.runningUntil.addLast(Long.valueOf(now + Math.max(50L, effect.defaultCooldownMillis())));
        return true;
    }

    public int runningAnimations() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Long end : this.runningUntil) {
            if (end.longValue() <= now) continue;
            ++n;
        }
        return n;
    }

    public static String formatSeconds(long millis) {
        long tenths = (millis + 50L) / 100L;
        return tenths % 10L == 0L ? tenths / 10L + "s" : tenths / 10L + "," + tenths % 10L + "s";
    }

    public String getChoice(UUID uuid, RipEffect.Type type) {
        return (type == RipEffect.Type.KILL ? this.killChoice : this.deathChoice).get(uuid);
    }

    public void setChoice(UUID uuid, RipEffect.Type type, String effectId) {
        Map<UUID, String> map = type == RipEffect.Type.KILL ? this.killChoice : this.deathChoice;
        Map<UUID, String> map2 = map;
        if (effectId == null) {
            map.remove(uuid);
        } else {
            map.put(uuid, effectId.toLowerCase(Locale.ROOT));
        }
        this.dirty.set(true);
    }

    private void loadSelections() {
        this.selFile = new File(this.getDataFolder(), "selections.yml");
        if (!this.selFile.exists()) {
            this.selFile.getParentFile().mkdirs();
            try {
                this.selFile.createNewFile();
            }
            catch (IOException iOException) {
                // empty catch block
            }
        }
        this.selConfig = YamlConfiguration.loadConfiguration((File)this.selFile);
        this.killChoice.clear();
        this.deathChoice.clear();
        this.read("kill", this.killChoice, RipEffect.Type.KILL);
        this.read("death", this.deathChoice, RipEffect.Type.DEATH);
    }

    private void read(String section, Map<UUID, String> into, RipEffect.Type type) {
        if (!this.selConfig.isConfigurationSection(section)) {
            return;
        }
        for (String key : this.selConfig.getConfigurationSection(section).getKeys(false)) {
            String value = this.selConfig.getString(section + "." + key);
            boolean valid = value != null && ("random".equalsIgnoreCase(value) || RipEffect.byId(type, value) != null);
            boolean bl = valid;
            if (!valid) continue;
            try {
                into.put(UUID.fromString(key), value.toLowerCase(Locale.ROOT));
            }
            catch (IllegalArgumentException illegalArgumentException) {}
        }
    }

    private synchronized void saveSelections() {
        this.selConfig.set("kill", null);
        this.selConfig.set("death", null);
        for (Map.Entry<UUID, String> e : this.killChoice.entrySet()) {
            this.selConfig.set("kill." + String.valueOf(e.getKey()), (Object)e.getValue());
        }
        for (Map.Entry<UUID, String> e : this.deathChoice.entrySet()) {
            this.selConfig.set("death." + String.valueOf(e.getKey()), (Object)e.getValue());
        }
        try {
            this.selConfig.save(this.selFile);
        }
        catch (IOException ex) {
            this.getLogger().log(Level.WARNING, "No se pudo guardar selections.yml", ex);
        }
    }
}

