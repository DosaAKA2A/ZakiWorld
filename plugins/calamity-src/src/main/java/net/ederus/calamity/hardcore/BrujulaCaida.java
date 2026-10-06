package net.ederus.calamity.hardcore;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.LodestoneTracker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calamity 1.16.0 · La Brujula de la Caida: una brujula que apunta al punto EXACTO de la Boveda Caida que sigue
 * cerrada, como una brujula apunta al norte. Sin boveda (ninguna cayo, ya la abrieron o se desvanecio) la aguja gira
 * sin rumbo; fuera de Calamity tambien (la boveda esta en otra dimension).
 *
 * Como: es una BRUJULA (COMPASS) con la marca lethal_world:brujula_caida y un lodestone_tracker sin magnetita
 * (tracked = false, asi no hace falta ningun bloque de magnetita ni se borra si no lo hay) con la posicion de la boveda,
 * o sin posicion para que gire. Al cambiar ese componente en la mano el cliente repite la animacion de sacarla, asi que
 * solo se toca cuando cambia el destino: al caer la boveda, al abrirse o al irse (BovedaCaida llama a repasarTodos), y
 * a las que llegan nuevas al inventario (un repaso cada 2 s de los que estan en Calamity, y uno ya al entrar al mundo,
 * al conectarse o al recoger una del suelo). El repaso solo reescribe las que apuntan a otro sitio.
 *
 * Clic derecho (al aire o a un bloque, con cualquier mano): cuanto le queda a la boveda ("La bóveda se desvanece en
 * 22 min."), o que no hay ninguna, con un enfriamiento como el del Barometro. No se gasta.
 *
 * Lo que una brujula hace en vanilla y aqui se corta, como en el Barometro: atarse a una magnetita (se niega el uso),
 * entrar en un crafteo (tambien en el crafter) y vendersela a un cartografo. Sin brillo: con lodestone_tracker Java le
 * pondria el de la brujula de magnetita, y se apaga con enchantment_glint_override = false.
 *
 * Bedrock (Geyser): Geyser traduce una brujula con lodestone_tracker a la brujula de magnetita de Bedrock y le responde
 * la posicion cuando el cliente la pide. Sin posicion, gira. Ver el riesgo en el informe de la 1.16.0: la dimension
 * de Calamity tiene que llegarle a Bedrock como el overworld para que apunte (si no, gira), y en Bedrock la brujula de
 * magnetita brilla siempre.
 */
final class BrujulaCaida implements Listener {

    /** El id de /calamity give y de "dar:" en altar.trueques. */
    static final String OBJETO = "fallcompass";
    static final String NOMBRE = "Brújula de la Caída";
    static final int ENFRIAMIENTO = 3;
    /** Cada cuanto se repasan las brujulas de los que estan en Calamity. */
    private static final long REPASO_TICKS = 40L;

    /** Lo que dice el clic derecho. */
    enum Estado { CERRADA, ABIERTA, NINGUNA }

    /** Donde esta la Boveda Caida que sigue cerrada: mundo (su nombre), bloque y cuando se desvanece (0 = no se sabe). */
    record Objetivo(String mundo, int x, int y, int z, long vence) {
    }

    private final Hardcore hc;
    /** El ultimo clic que conto, por jugador (millis): el enfriamiento. */
    private final Map<UUID, Long> ultimo = new HashMap<>();
    private BukkitTask reloj;

    BrujulaCaida(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("brujula-caida", () -> autotest(Ficha.cfg()));
        reloj = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(),
                () -> hc.seguro("brujula-caida", this::repasarTodos), REPASO_TICKS, REPASO_TICKS);
    }

    void parar() {
        HandlerList.unregisterAll(this);
        if (reloj != null) reloj.cancel();
        reloj = null;
        ultimo.clear();
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = hc.cfg().getConfigurationSection("brujula-caida");
        return s == null ? new YamlConfiguration() : s;
    }

    // ------------------------------------------------------------------ el objeto

    /** Una Brujula de la Caida nueva, girando (sin ligar: lo liga Entregas). El primer repaso le pone el rumbo. */
    static ItemStack crear() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        meta.displayName(Ficha.tono("brujula-caida").nombre(NOMBRE));
        meta.lore(ficha(Ficha.cfg()).lore());
        // Sin brillo: el lodestone_tracker se lo pondria (la brujula de magnetita brilla en Java).
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(Marcas.BRUJULA_CAIDA, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        apuntar(item, null);
        return item;
    }

    /** Si es una Brujula de la Caida (por la marca). */
    static boolean es(ItemStack it) {
        return it != null && it.getType() == Material.COMPASS && Marcas.tiene(it, Marcas.BRUJULA_CAIDA);
    }

    /** El lore, con la plantilla comun. Puro: lo prueba el autotest "fichas". */
    static Ficha ficha(ConfigurationSection c) {
        return new Ficha(Ficha.tono("brujula-caida")).cabecera("Instrumento", "Bóveda Caída", 0)
                .historia("La aguja olvidó el norte: solo busca lo que cae del cielo.")
                .seccion("Mientras haya una bóveda")
                .dato("La aguja apunta a la <Bóveda Caída>.")
                .dato("Si no hay ninguna, gira sin rumbo.")
                .accion("Clic derecho para saber cuánto le queda.")
                .hueco().nota("Solo marca en Calamity.").nota("No se gasta.");
    }

    /** La misma brujula con el nombre y el lore de hoy (o null si ya los lleva). Conserva el ligado y el rumbo. */
    static ItemStack renovado(ItemStack it) {
        if (!es(it)) return null;
        return Ficha.renovar(it, Ficha.tono("brujula-caida").nombre(NOMBRE), ficha(Ficha.cfg()).lore());
    }

    /** Le pone el rumbo (null = gira sin rumbo). Cambia el objeto en el sitio. */
    static void apuntar(ItemStack it, Location destino) {
        it.setData(DataComponentTypes.LODESTONE_TRACKER, LodestoneTracker.lodestoneTracker(destino, false));
    }

    /** A donde apunta ahora (null = gira). */
    static Location rumbo(ItemStack it) {
        LodestoneTracker t = it.getData(DataComponentTypes.LODESTONE_TRACKER);
        return t == null ? null : t.location();
    }

    // ------------------------------------------------------------------ el repaso

    /** El destino de ahora: la Boveda Caida cerrada (en su mundo), o null. */
    private Location destino() {
        Objetivo o = objetivo(hc.datos(), System.currentTimeMillis());
        if (o == null) return null;
        World w = hc.plugin().getServer().getWorld(o.mundo());
        return w == null ? null : new Location(w, o.x(), o.y(), o.z());
    }

    /** Todas las de los que estan en Calamity, al rumbo de ahora. Lo llaman el reloj (cada 2 s) y BovedaCaida. */
    void repasarTodos() {
        Location d = destino();
        for (World w : hc.plugin().getServer().getWorlds()) {
            if (!hc.esHardcore(w)) continue;
            for (Player p : w.getPlayers()) repasar(p, d);
        }
    }

    /** Las de ese jugador que apuntan a otro sitio, al destino. Solo dentro de Calamity: fuera giran solas. */
    private void repasar(Player p, Location destino) {
        if (p == null || !p.isOnline() || !hc.esHardcore(p)) return;
        PlayerInventory inv = p.getInventory();
        ItemStack[] todo = inv.getContents();
        for (int i = 0; i < todo.length; i++) {
            ItemStack it = todo[i];
            if (!es(it) || mismo(rumbo(it), destino)) continue;
            ItemStack nueva = it.clone();
            apuntar(nueva, destino);
            inv.setItem(i, nueva);
        }
    }

    /** Un tick despues (el inventario ya esta puesto): las suyas, al rumbo de ahora. */
    private void luego(Player p) {
        hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(),
                () -> hc.seguro("brujula-caida", () -> repasar(p, destino())), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntrar(PlayerJoinEvent e) {
        luego(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCambiarMundo(PlayerChangedWorldEvent e) {
        luego(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRecoger(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && es(e.getItem().getItemStack())) luego(p);
    }

    // ------------------------------------------------------------------ el uso

    /**
     * Sin ignoreCancelled, como el Barometro: el clic al aire llega ya cancelado. Se niega siempre el uso vanilla
     * (atarla a una magnetita) y el bloque: el clic es de la brujula.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onUsar(PlayerInteractEvent e) {
        if (!e.getAction().isRightClick() || !es(e.getItem())) return;
        e.setUseItemInHand(Event.Result.DENY);
        e.setUseInteractedBlock(Event.Result.DENY);
        Player p = e.getPlayer();
        long ahora = System.currentTimeMillis();
        if (!Barometro.aTiempo(ultimo.get(p.getUniqueId()), ahora, enfriamiento(hc.cfg()) * 1000L)) return;
        ultimo.put(p.getUniqueId(), ahora);
        hc.seguro("brujula-caida", () -> consultar(p, ahora));
    }

    private void consultar(Player p, long ahora) {
        ConfigurationSection c = cfg();
        Component l = linea(c, hc.esHardcore(p), estado(hc.datos(), ahora), objetivo(hc.datos(), ahora), ahora);
        if (c.getBoolean("chat", true)) p.sendMessage(l);
        if (c.getBoolean("barra", true)) {
            if (hc.esHardcore(p)) hc.cordura().destello(p, l, 4);
            else hc.barra().aviso(p, l, 4);
        }
        Marco.sonar(p, "item.lodestone_compass.lock", 0.5f, 1.4f);
        // Por si la tenia sin rumbo: que apunte ya.
        repasar(p, destino());
    }

    @EventHandler
    public void onSalir(PlayerQuitEvent e) {
        ultimo.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ bloqueos de la brujula (como el Barometro)

    @EventHandler
    public void onCraftear(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (es(it)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent e) {
        if (!(e.getBlock().getState(false) instanceof org.bukkit.block.Crafter cr)) return;
        for (ItemStack it : cr.getInventory().getContents()) {
            if (es(it)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** El cartografo compra brujulas: esta no entra en la ventana de un aldeano ni en la de un crafter. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMeter(InventoryClickEvent e) {
        if (!Barometro.noEntra(e.getView().getTopInventory().getType())) return;
        if (es(Sellos.entraArriba(e))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArrastrar(InventoryDragEvent e) {
        if (Barometro.noEntra(e.getView().getTopInventory().getType()) && es(e.getOldCursor()) && Sellos.tocaArriba(e)) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ el nucleo (puro)

    static int enfriamiento(ConfigurationSection hardcore) {
        return Math.max(0, Math.min(60, hardcore.getInt("brujula-caida.enfriamiento-segundos", ENFRIAMIENTO)));
    }

    /**
     * La Boveda Caida a la que apuntar segun hardcore-datos (boveda-caida.activa, lo que escribe BovedaCaida), o null:
     * no hay ninguna, ya la abrieron o ya paso su hora (vence; 0 = datos de antes sin hora: vale).
     */
    static Objetivo objetivo(ConfigurationSection datos, long ahora) {
        if (estado(datos, ahora) != Estado.CERRADA) return null;
        String base = BovedaCaida.RUTA + ".activa.";
        return new Objetivo(datos.getString(base + "mundo"), datos.getInt(base + "x"), datos.getInt(base + "y"),
                datos.getInt(base + "z"), datos.getLong(base + "vence", 0));
    }

    static Estado estado(ConfigurationSection datos, long ahora) {
        if (datos == null) return Estado.NINGUNA;
        String base = BovedaCaida.RUTA + ".activa.";
        String mundo = datos.getString(base + "mundo");
        if (mundo == null || mundo.isBlank()) return Estado.NINGUNA;
        if (datos.getBoolean(base + "abierta", false)) return Estado.ABIERTA;
        long vence = datos.getLong(base + "vence", 0);
        return vence > 0 && ahora >= vence ? Estado.NINGUNA : Estado.CERRADA;
    }

    /** Si dos rumbos son el mismo: los dos sin rumbo, o el mismo bloque del mismo mundo. */
    static boolean mismo(Location a, Location b) {
        if (a == null || b == null) return a == b;
        return java.util.Objects.equals(a.getWorld(), b.getWorld()) && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private static String texto(ConfigurationSection c, String clave, String serie) {
        String s = c == null ? null : c.getString("textos." + clave);
        return s == null || s.isBlank() ? serie : s;
    }

    /** La linea del clic derecho: "Brújula de la Caída · " y lo que marca. {tiempo}: "22 min", "45 s". */
    static Component linea(ConfigurationSection c, boolean enCalamity, Estado estado, Objetivo o, long ahora) {
        TextComponent.Builder out = Component.text()
                .append(Component.text(NOMBRE, Ficha.tono("brujula-caida").medio()))
                .append(Component.text(" · ", Paleta.SEPARADOR));
        if (!enCalamity) {
            out.append(Component.text(texto(c, "fuera", "Aquí la aguja no encuentra nada."), Paleta.TEXTO));
        } else if (estado == Estado.CERRADA && o != null && o.vence() > 0) {
            out.append(Barometro.pintar(texto(c, "desvanece", "La bóveda se desvanece en {tiempo}."),
                    Map.of("tiempo", Component.text(CicloClima.restante(o.vence() - ahora), Paleta.CIFRA))));
        } else if (estado == Estado.CERRADA) {
            out.append(Component.text(texto(c, "sin-hora", "La aguja apunta a la bóveda."), Paleta.TEXTO));
        } else if (estado == Estado.ABIERTA) {
            out.append(Component.text(texto(c, "abierta", "Alguien ya abrió la bóveda."), Paleta.TEXTO));
        } else {
            out.append(Component.text(texto(c, "sin-boveda", "No ha caído ninguna bóveda."), Paleta.TEXTO));
        }
        return out.build().decoration(TextDecoration.ITALIC, false);
    }

    // ------------------------------------------------------------------ autotest

    /** "fallcompass": lo puro siempre (tambien con el arnes) y, con servidor, la brujula de verdad. */
    static List<String> autotest(ConfigurationSection hardcore) {
        Autotest.Hoja h = new Autotest.Hoja();
        ConfigurationSection c = hardcore == null ? null : hardcore.getConfigurationSection("brujula-caida");
        PlainTextComponentSerializer plano = PlainTextComponentSerializer.plainText();
        long ahora = 1_000_000_000L;
        String base = BovedaCaida.RUTA + ".activa.";

        // Sin boveda: gira.
        YamlConfiguration d = new YamlConfiguration();
        h.ok("sin boveda: sin objetivo (gira)", objetivo(d, ahora) == null && estado(d, ahora) == Estado.NINGUNA);
        h.ok("sin datos: gira", objetivo(null, ahora) == null);
        // Cae: lo que escribe BovedaCaida.impacto.
        d.set(base + "mundo", "calamity");
        d.set(base + "x", 812);
        d.set(base + "y", 71);
        d.set(base + "z", -1430);
        d.set(base + "vence", ahora + 22 * 60_000L - 30_000L);
        d.set(base + "abierta", false);
        Objetivo o = objetivo(d, ahora);
        h.igual("al caer: apunta al bloque exacto", new Objetivo("calamity", 812, 71, -1430, ahora + 22 * 60_000L - 30_000L), o);
        h.igual("clic: cuanto le queda", "Brújula de la Caída · La bóveda se desvanece en 22 min.",
                plano.serialize(linea(c, true, estado(d, ahora), o, ahora)));
        h.igual("clic: el ultimo minuto en segundos", "Brújula de la Caída · La bóveda se desvanece en 45 s.",
                plano.serialize(linea(c, true, Estado.CERRADA, new Objetivo("calamity", 0, 0, 0, ahora + 45_000), ahora)));
        // Se abre: gira.
        d.set(base + "abierta", true);
        h.ok("al abrirse: sin objetivo (gira)", objetivo(d, ahora) == null && estado(d, ahora) == Estado.ABIERTA);
        h.igual("clic con la boveda abierta", "Brújula de la Caída · Alguien ya abrió la bóveda.",
                plano.serialize(linea(c, true, estado(d, ahora), null, ahora)));
        // Se desvanece sin abrir: gira (desde su hora, aunque BovedaCaida tarde un segundo en quitarla).
        d.set(base + "abierta", false);
        h.ok("a su hora: sin objetivo (gira)", objetivo(d, ahora + 22 * 60_000L) == null);
        d.set(BovedaCaida.RUTA + ".activa", null);
        h.ok("retirada: sin objetivo (gira)", objetivo(d, ahora) == null);
        h.igual("clic sin boveda", "Brújula de la Caída · No ha caído ninguna bóveda.",
                plano.serialize(linea(c, true, estado(d, ahora), null, ahora)));
        h.igual("clic fuera de Calamity", "Brújula de la Caída · Aquí la aguja no encuentra nada.",
                plano.serialize(linea(c, false, Estado.CERRADA, o, ahora)));
        YamlConfiguration vieja = new YamlConfiguration();
        vieja.set(base + "mundo", "calamity");
        h.ok("datos de antes sin hora: apunta igual", objetivo(vieja, ahora) != null);

        // Solo se reescribe la que apunta a otro sitio.
        Location a = new Location(null, 812.5, 71, -1429.8), b = new Location(null, 812, 71.9, -1430);
        h.ok("mismo bloque: no se toca", mismo(a, b));
        h.ok("otro bloque: se reescribe", !mismo(a, new Location(null, 813, 71, -1430)));
        h.ok("las dos girando: no se toca", mismo(null, null));
        h.ok("girando y con boveda: se reescribe", !mismo(null, a) && !mismo(a, null));

        YamlConfiguration cfg = new YamlConfiguration();
        h.igual("enfriamiento de serie: 3 s", 3, enfriamiento(cfg));
        cfg.set("brujula-caida.enfriamiento-segundos", 500);
        h.igual("enfriamiento: como mucho 60 s", 60, enfriamiento(cfg));
        List<String> lore = ficha(new YamlConfiguration()).lineas();
        h.ok("lore: apunta a la Boveda Caida y no se gasta", lore.contains(" La aguja apunta a la Bóveda Caída.")
                && lore.contains("No se gasta."));
        h.igual("lore: lineas de 38 como mucho", List.of(), Ficha.largas(lore));
        h.igual("nombre y lore sin negrita ni rayas", List.of(), Ficha.faltas(Ficha.tono("brujula-caida").nombre(NOMBRE),
                ficha(new YamlConfiguration()).lore()));
        h.ok("marca propia en lethal_world", Marcas.BRUJULA_CAIDA.getNamespace().equals(Marcas.NAMESPACE));

        if (Bukkit.getServer() != null) {
            ItemStack br = crear();
            h.ok("give fallcompass: brujula con su marca", br != null && br.getType() == Material.COMPASS && es(br));
            h.ok("nueva: gira sin rumbo", br != null && br.hasData(DataComponentTypes.LODESTONE_TRACKER) && rumbo(br) == null);
            h.ok("sin brillo", br != null && Boolean.FALSE.equals(br.getItemMeta().getEnchantmentGlintOverride()));
            World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (br != null && w != null) {
                Location donde = new Location(w, 812, 71, -1430);
                apuntar(br, donde);
                h.ok("al caer: apunta a la boveda", mismo(rumbo(br), donde));
                h.ok("sin magnetita (tracked = false)", !br.getData(DataComponentTypes.LODESTONE_TRACKER).tracked());
                apuntar(br, null);
                h.ok("al abrirse o irse: vuelve a girar", rumbo(br) == null);
            }
        }
        return h.lineas();
    }
}
