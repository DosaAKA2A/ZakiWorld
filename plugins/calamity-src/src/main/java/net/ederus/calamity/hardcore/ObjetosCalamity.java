package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * M34 · Los objetos PDC de Calamity (Talisman de Vigilia, Grabado, Salvoconducto) y el aura
 * del [5] del Manto y de la Guadana (DIS sec. 0.3, M34; ESTUDIO sec. 4.4 b, d, h).
 *
 * Talisman: mientras haya uno en el inventario (cuenta uno), +3 de vida maxima con un
 * modificador TRANSITORIO propio (lethal_world:talisman) y el drenaje de cordura x0,80
 * dentro. Transitorio a proposito: no se guarda en los datos del jugador, asi que una caida
 * del servidor no puede dejar a nadie con +3 para siempre; al entrar se vuelve a calcular.
 * Se recalcula al entrar, al reaparecer, al cambiar de mundo, al recoger o soltar uno, al
 * cerrar un inventario y cada 5 s para los que estan dentro.
 *
 * Grabado: el nucleo (que se puede grabar, +1 nivel, uno por objeto, uno por semana) vive
 * aqui para poder probarlo en memoria; el boton y el menu de una fila son de la Forja.
 *
 * Salvoconducto: APAGADO de serie (salvoconducto.activo: false, DIS sec. 0.3): choca con "al
 * morir lo pierdes TODO" y espera el visto bueno de Dosa. Encendido, al morir aparta una
 * pieza (la elegida, o la de mayor escalon) antes de que Hardcore borre el inventario y la
 * devuelve al reaparecer. La pieza apartada va a hardcore-datos.yml en el acto (guardarYa):
 * una caida entre la muerte y el reaparecer no puede perderla.
 *
 * Al morir tambien se apuntan las piezas del Manto, del Hacha y de la Guadana que llevaba
 * (perdidas.<uuid>.<pieza>): es lo que abre la reposicion a mitad de precio en la Forja.
 *
 * Aura (sin Glow, DIS sec. 0.3): polvo ambar a los pies de quien lleva el [5] del Manto y
 * rojo de muerte a quien empuna la Guadana, 4 particulas cada 2 s, visible a 24 bloques.
 * Presumir dentro de Calamity tambien es delatarse.
 */
final class ObjetosCalamity implements Listener {

    static final TextColor AMBAR = TextColor.color(0xE8A33D);
    private static final TextColor PAPEL = TextColor.color(0xE8D9B0);
    private static final long ESPERA_MS = 500;
    private static final double RADIO_AURA = 24;

    /** Orden de las cinco casillas del Salvoconducto. */
    static final List<String> CASILLAS_SALVO = List.of("yelmo", "pechera", "grebas", "botas", "arma");
    /** A igualdad de escalon: pechera, arma, yelmo, grebas, botas (ESTUDIO sec. 4.4 h). */
    private static final int[] DESEMPATE = {1, 4, 0, 2, 3};

    /** Nivel al que puede llegar cada encantamiento con un Grabado (PLAN sec. 5.3). */
    private static final Map<String, Integer> TOPES_DEFECTO = Map.of(
            "sharpness", 7, "protection", 5, "efficiency", 6, "power", 6,
            "unbreaking", 4, "looting", 4, "fortune", 4);
    private static final Map<String, String> NOMBRES_ENC = Map.of(
            "sharpness", "Filo", "protection", "Protección", "efficiency", "Eficiencia", "power", "Poder",
            "unbreaking", "Irrompibilidad", "looting", "Saqueo", "fortune", "Fortuna");

    /** Menu de una fila para elegir la pieza del Salvoconducto. */
    record MarcaSalvo() implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final Hardcore hc;
    /** Quien lleva ahora un Talisman valido (el drenaje lo pregunta cada segundo). */
    private final Set<UUID> conTalisman = new HashSet<>();
    /** Segundos dentro por jugador, para repartir el recalculo (5 s) y el aura (2 s). */
    private final Map<UUID, Integer> segundos = new HashMap<>();
    private final Map<UUID, Long> ultimoClic = new HashMap<>();
    private final Set<BukkitTask> tareas = new HashSet<>();

    ObjetosCalamity(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("objetos", this::autotest);
        // Calamity 1.3.3: que cada objeto de MMOItems haga lo que dice su lore (autotest objetos-reales y
        // /calamity items stats). Sin estado propio: no hay nada que parar.
        new ObjetosReales(hc);
        // Los que ya estan conectados (recarga del plugin): que el Talisman valga desde ya.
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) talisman(p);
    }

    void parar() {
        for (BukkitTask t : tareas) t.cancel();
        tareas.clear();
        HandlerList.unregisterAll(this);
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) {
            quitarModificador(p);
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof MarcaSalvo) p.closeInventory();
        }
        conTalisman.clear();
        segundos.clear();
        ultimoClic.clear();
    }

    private void tarea(Runnable r, long ticks) {
        final BukkitTask[] t = new BukkitTask[1];
        t[0] = hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(), () -> {
            tareas.remove(t[0]);
            r.run();
        }, ticks);
        tareas.add(t[0]);
    }

    // =========================================================== ganchos de Hardcore

    /** Multiplicador del drenaje de cordura (Talisman: talisman.drenaje, 0,80). */
    double factorDrenaje(Player p) {
        if (p == null || !conTalisman.contains(p.getUniqueId())) return 1.0;
        return factorTalisman(true, hc.cfg().getDouble("talisman.drenaje", 0.80));
    }

    /** Una vez por segundo por jugador que cuenta dentro de Calamity. */
    void tick(Player p) {
        int s = segundos.merge(p.getUniqueId(), 1, Integer::sum);
        if (s % 5 == 0) talisman(p);
        if (s % 2 == 0) aura(p);
    }

    /**
     * Antes de que Hardcore borre el inventario: con el Salvoconducto encendido aparta la
     * pieza que se salva, y apunta lo del Manto que se pierde (reposicion).
     */
    void alMorir(Player p, PlayerDeathEvent e) {
        UUID u = p.getUniqueId();
        conTalisman.remove(u);
        quitarModificador(p);
        int salvada = hc.valor("objetos", () -> salvoconducto(p), -1);
        apuntarPerdidas(p, salvada);
    }

    /** Un tick despues de reaparecer: devuelve lo del Salvoconducto y recalcula el Talisman. */
    void alReaparecer(Player p) {
        devolverSalvado(p);
        talisman(p);
    }

    // ================================================================= Talisman

    /** Si un Talisman cuenta en esas casillas (uno sin ligar, o ligado a ese jugador). */
    static boolean llevaTalisman(ItemStack[] contenido, UUID jugador) {
        if (contenido == null) return false;
        for (ItemStack it : contenido) {
            if (!Marcas.tiene(it, Marcas.TALISMAN)) continue;
            UUID dueno = Ligado.duenoDe(it);
            if (dueno == null || dueno.equals(jugador)) return true;
        }
        return false;
    }

    /** El factor de drenaje con o sin Talisman (un valor de config fuera de (0, 1] cae a 0,80). */
    static double factorTalisman(boolean lleva, double configurado) {
        if (!lleva) return 1.0;
        return configurado > 0 && configurado <= 1 ? configurado : 0.80;
    }

    /** Recalcula el Talisman de un jugador: modificador de vida y drenaje. */
    void talisman(Player p) {
        if (p == null || !p.isOnline()) return;
        UUID u = p.getUniqueId();
        boolean lleva = !p.isDead() && llevaTalisman(p.getInventory().getContents(), u);
        if (lleva) conTalisman.add(u);
        else conTalisman.remove(u);
        AttributeInstance inst = vida(p);
        if (inst == null) return;
        AttributeModifier ya = inst.getModifier(Marcas.TALISMAN);
        double cuanto = Math.max(0, hc.cfg().getDouble("talisman.vida", 3));
        if (lleva && cuanto > 0) {
            if (ya != null && ya.getAmount() == cuanto) return;
            if (ya != null) inst.removeModifier(Marcas.TALISMAN);
            inst.addTransientModifier(new AttributeModifier(Marcas.TALISMAN, cuanto,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        } else if (ya != null) {
            quitarModificador(p);
        }
    }

    private static AttributeInstance vida(Player p) {
        Attribute a = Compat.attribute("max_health");
        return a == null ? null : p.getAttribute(a);
    }

    private static void quitarModificador(Player p) {
        AttributeInstance inst = vida(p);
        if (inst == null || inst.getModifier(Marcas.TALISMAN) == null) return;
        inst.removeModifier(Marcas.TALISMAN);
        // Sin el +3, la vida que sobraba se recorta ya (si no, se ve 23/20 hasta el siguiente golpe).
        if (!p.isDead() && p.getHealth() > inst.getValue()) p.setHealth(Math.max(0.5, inst.getValue()));
    }

    private void talismanLuego(Player p) {
        tarea(() -> hc.seguro("objetos", () -> talisman(p)), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntrar(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        talismanLuego(p);
        // Quien murio con el Salvoconducto y se fue sin reaparecer: se le devuelve al volver.
        if (hc.datos().isSet(rutaDevolver(p.getUniqueId()))) {
            tarea(() -> {
                if (p.isOnline() && !p.isDead()) hc.seguro("objetos", () -> devolverSalvado(p));
            }, 40L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCambiarMundo(PlayerChangedWorldEvent e) {
        hc.seguro("objetos", () -> talisman(e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRecoger(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && Marcas.tiene(e.getItem().getItemStack(), Marcas.TALISMAN)) talismanLuego(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSoltar(PlayerDropItemEvent e) {
        if (Marcas.tiene(e.getItemDrop().getItemStack(), Marcas.TALISMAN)) talismanLuego(e.getPlayer());
    }

    /** Guardarlo en un cofre, dejarlo en una mesa... todo pasa por cerrar un inventario. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCerrar(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) hc.seguro("objetos", () -> talisman(p));
    }

    /** Muertes fuera de Calamity (Hardcore solo llama a alReaparecer por las de dentro). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onReaparecer(PlayerRespawnEvent e) {
        talismanLuego(e.getPlayer());
    }

    @EventHandler
    public void onSalir(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        conTalisman.remove(u);
        segundos.remove(u);
        ultimoClic.remove(u);
        quitarModificador(p);
    }

    /**
     * Ninguno entra en una receta: ni los tres de aqui (el pedernal, el papel y el reloj tienen
     * varias) ni el Fragmento de Masamune (la chatarra de netherita da el lingote).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onReceta(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (Marcas.tiene(it, Marcas.TALISMAN) || Marcas.tiene(it, Marcas.GRABADO) || Marcas.tiene(it, Marcas.SALVOCONDUCTO)
                    || ItemsCalamity.esFragmentoMasamune(it)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    /** El Fragmento de Masamune tampoco se funde (horno, alto horno, ahumador u hoguera). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFundir(BlockCookEvent e) {
        if (ItemsCalamity.esFragmentoMasamune(e.getSource())) e.setCancelled(true);
    }

    // =================================================================== Aura

    /** Cuantas piezas distintas del Manto (4 de armadura y el Hacha) lleva puestas o en las manos. */
    int piezasManto(Player p) {
        if (!PuenteMmo.disponible()) return 0;
        List<String> ids = idsManto();
        PlayerInventory inv = p.getInventory();
        Set<String> vistos = new HashSet<>();
        for (ItemStack it : new ItemStack[]{inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots(),
                inv.getItemInMainHand(), inv.getItemInOffHand()}) {
            String id = PuenteMmo.enlace(it);
            if (id != null && ids.contains(id)) vistos.add(id);
        }
        return vistos.size();
    }

    private List<String> idsManto() {
        List<String> ids = hc.cfg().getStringList("hitos.manto-ids");
        if (!ids.isEmpty()) return ids;
        List<String> out = new ArrayList<>();
        Map<String, String> piezas = piezasForja();
        for (String k : List.of("yelmo", "coraza", "grebas", "soleretas", "hacha")) {
            String id = piezas.get(k);
            if (id != null && !id.isBlank()) out.add(id);
        }
        return out;
    }

    private boolean empunaGuadana(Player p) {
        String id = piezasForja().get("guadana");
        return id != null && id.equals(PuenteMmo.enlace(p.getInventory().getItemInMainHand()));
    }

    private void aura(Player p) {
        if (Compat.DUST == null || !PuenteMmo.disponible()) return;
        Color color;
        // El rojo claro de la Parca (Paleta): el rojo de muerte oscuro (#8B1A1A) apenas se veia de noche.
        if (empunaGuadana(p)) color = Color.fromRGB(Paleta.PARCA_HASTA);
        else if (piezasManto(p) >= 5) color = Color.fromRGB(0xE8A33D);
        else return;
        Particle.DustOptions polvo = new Particle.DustOptions(color, 0.8f);
        Location pies = p.getLocation().add(0, 0.1, 0);
        for (Player v : p.getWorld().getPlayers()) {
            if (v.getLocation().distanceSquared(pies) > RADIO_AURA * RADIO_AURA) continue;
            try {
                v.spawnParticle(Compat.DUST, pies, 4, 0.35, 0.05, 0.35, 0, polvo);
            } catch (Throwable ignorado) {
                return;
            }
        }
    }

    // ================================================================ Grabado

    /** Si el objeto lleva la marca de MMOItems (por MythicLib o, sin el, por su NBT). */
    static boolean esMmo(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        if (PuenteMmo.enlace(item) != null) return true;
        // Sin MythicLib (o si su API cambia) se mira el NBT del objeto tal cual: MMOItems
        // guarda MMOITEMS_ITEM_ID en custom_data, y eso sale en las dos formas de texto.
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return contieneMarcaMmo(() -> meta.getAsString()) || contieneMarcaMmo(() -> meta.getAsComponentString());
    }

    private static boolean contieneMarcaMmo(java.util.function.Supplier<String> texto) {
        try {
            String nbt = texto.get();
            return nbt != null && (nbt.contains("MMOITEMS_ITEM_ID") || nbt.contains("MMOITEMS_ITEM_TYPE"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Un encantamiento por su nombre de config (SHARPNESS) o su clave (minecraft:sharpness). */
    static Enchantment encantamiento(String nombre) {
        if (nombre == null || nombre.isBlank()) return null;
        String id = nombre.trim().toLowerCase(Locale.ROOT);
        NamespacedKey k = NamespacedKey.fromString(id.contains(":") ? id : "minecraft:" + id);
        if (k == null) return null;
        // El registro de Paper primero; el de Bukkit (deprecado) por si el de Paper no esta.
        try {
            Enchantment e = io.papermc.paper.registry.RegistryAccess.registryAccess()
                    .getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT).get(k);
            if (e != null) return e;
        } catch (Throwable ignorado) {
            // sigue con el de Bukkit
        }
        try {
            return Registry.ENCHANTMENT.get(k);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Hasta que nivel sube cada encantamiento: grabado.topes, o el de PLAN sec. 5.3, o el vanilla + 1. */
    static int tope(Enchantment e, ConfigurationSection topes) {
        String k = e.getKey().getKey();
        if (topes != null) {
            for (String c : topes.getKeys(false)) {
                if (c.equalsIgnoreCase(k)) return topes.getInt(c, e.getMaxLevel() + 1);
            }
        }
        return TOPES_DEFECTO.getOrDefault(k, e.getMaxLevel() + 1);
    }

    /** Los encantamientos del objeto que un Grabado puede subir: ya en su tope vanilla y por debajo del final. */
    static List<Enchantment> grabables(ItemStack item, List<Enchantment> permitidos, ConfigurationSection topes) {
        List<Enchantment> out = new ArrayList<>();
        if (item == null || item.getType().isAir()) return out;
        for (Enchantment e : permitidos) {
            if (e == null) continue;
            int nivel = item.getEnchantmentLevel(e);
            if (nivel >= e.getMaxLevel() && nivel < tope(e, topes)) out.add(e);
        }
        return out;
    }

    /**
     * Por que no se puede grabar ese objeto esta semana, o null si se puede: "vacio" (nada en
     * la mano), "mmo" (lleva la marca de MMOItems), "grabado" (ya tiene uno: uno por objeto),
     * "semana" (ya grabo por-semana veces, P-W09) o "encantamiento" (nada en su tope).
     */
    static String motivoNoGraba(ItemStack item, UUID jugador, ConfigurationSection datos, String semana, int porSemana,
                                List<Enchantment> permitidos, ConfigurationSection topes) {
        if (item == null || item.getType().isAir()) return "vacio";
        if (esMmo(item)) return "mmo";
        if (Marcas.tiene(item, Marcas.GRABADO) || Marcas.tiene(item, Marcas.RELIQUIA)) return "grabado";
        if (datos.getInt(rutaGrabados(semana, jugador), 0) >= Math.max(1, porSemana)) return "semana";
        if (grabables(item, permitidos, topes).isEmpty()) return "encantamiento";
        return null;
    }

    static String rutaGrabados(String semana, UUID jugador) {
        return "grabados." + semana + "." + jugador;
    }

    /**
     * Graba: +1 nivel del encantamiento elegido, lethal_world:grabado = id del Grabado gastado
     * y +1 a la cuenta semanal. Devuelve null si lo hizo o el motivo si no (sin tocar nada).
     * Ligar el objeto es cosa del que llama (Entregas.ligar, con su linea de lore).
     */
    static String grabar(ItemStack item, Enchantment e, String idGrabado, UUID jugador, ConfigurationSection datos,
                         String semana, int porSemana, List<Enchantment> permitidos, ConfigurationSection topes) {
        String motivo = motivoNoGraba(item, jugador, datos, semana, porSemana, permitidos, topes);
        if (motivo != null) return motivo;
        if (e == null || !grabables(item, permitidos, topes).contains(e)) return "encantamiento";
        int nivel = item.getEnchantmentLevel(e) + 1;
        item.addUnsafeEnchantment(e, nivel);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(Marcas.GRABADO, PersistentDataType.STRING,
                idGrabado == null ? UUID.randomUUID().toString() : idGrabado);
        item.setItemMeta(meta);
        String r = rutaGrabados(semana, jugador);
        datos.set(r, datos.getInt(r, 0) + 1);
        return null;
    }

    /** Los que sube un Grabado si la config no trae grabado.encantamientos (el lore del Grabado los nombra). */
    static final List<String> GRABABLES_DE_SERIE =
            List.of("SHARPNESS", "PROTECTION", "EFFICIENCY", "POWER", "UNBREAKING", "LOOTING", "FORTUNE");

    /** Los encantamientos de grabado.encantamientos (los de serie si la lista no esta). */
    List<Enchantment> permitidos() {
        List<String> nombres = hc.cfg().getStringList("grabado.encantamientos");
        if (nombres.isEmpty()) nombres = GRABABLES_DE_SERIE;
        List<Enchantment> out = new ArrayList<>();
        for (String n : nombres) {
            Enchantment e = encantamiento(n);
            if (e != null) out.add(e);
        }
        return out;
    }

    ConfigurationSection topes() {
        return hc.cfg().getConfigurationSection("grabado.topes");
    }

    int porSemana() {
        return Math.max(1, hc.cfg().getInt("grabado.por-semana", 1));
    }

    static String nombreEncantamiento(Enchantment e) {
        String k = e.getKey().getKey();
        String n = NOMBRES_ENC.get(k);
        if (n != null) return n;
        String t = k.replace('_', ' ');
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    static String romano(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    /** El mensaje de cada motivo de no poder grabar (P-W09 el de la semana). */
    static Component avisoGrabado(String motivo) {
        return ComandoCalamity.mensaje(switch (motivo == null ? "" : motivo) {
            case "vacio" -> "Coge en la mano lo que quieras grabar.";
            case "mmo" -> "El Grabado solo sirve en equipo sin MMOItems.";
            case "grabado" -> "Ese objeto ya está grabado: solo se puede grabar una vez.";
            case "semana" -> "Esta semana ya has grabado algo. El lunes podrás volver a grabar.";
            case "encantamiento" -> "Ese objeto no tiene ningún encantamiento al máximo que el Grabado pueda subir.";
            case "sin-grabado" -> "No llevas ningún Grabado de Calamidad.";
            default -> "Eso no se puede grabar.";
        });
    }

    /** P-W08: "Grabado hecho: <encantamiento> <nivel>. El objeto queda ligado a ti." */
    static Component avisoGrabadoHecho(Enchantment e, int nivel) {
        return ComandoCalamity.mensaje(Component.text("Grabado hecho: ")
                .append(Component.text(nombreEncantamiento(e) + " " + romano(nivel), Paleta.DETALLE))
                .append(Component.text(". El objeto queda ligado a ti.")));
    }

    /** La casilla del primer Grabado sin usar que puede gastar ese jugador, o -1. */
    static int casillaGrabado(ItemStack[] contenido, UUID jugador) {
        for (int i = 0; i < contenido.length; i++) {
            ItemStack it = contenido[i];
            if (it == null || it.getType() != Material.FLINT || !Marcas.tiene(it, Marcas.GRABADO)) continue;
            UUID dueno = Ligado.duenoDe(it);
            if (dueno == null || dueno.equals(jugador)) return i;
        }
        return -1;
    }

    // =========================================================== Salvoconducto

    private boolean salvoActivo() {
        return hc.cfg().getBoolean("salvoconducto.activo", false);
    }

    private static String rutaDevolver(UUID u) {
        return "salvoconducto.devolver." + u;
    }

    private static String rutaEleccion(UUID u) {
        return "salvoconducto.eleccion." + u;
    }

    /**
     * La pieza que salva el Salvoconducto: indice en CASILLAS_SALVO (yelmo, pechera, grebas,
     * botas, arma) o -1. Apagado o sin Salvoconducto, -1 siempre: no se aparta nada. Con una
     * eleccion valida, esa; si no, la de mayor escalon y, a igualdad, pechera, arma, yelmo,
     * grebas, botas. "vale" descarta lo que nunca se salva (Reliquias, Esencias, prestado...).
     */
    static int piezaQueSalva(boolean activo, boolean llevaSalvo, ItemStack[] cinco, String eleccion,
                             Predicate<ItemStack> vale, ToIntFunction<ItemStack> escalon) {
        if (!activo || !llevaSalvo || cinco == null || cinco.length < 5) return -1;
        int elegida = eleccion == null ? -1 : CASILLAS_SALVO.indexOf(eleccion);
        if (elegida >= 0 && vale.test(cinco[elegida])) return elegida;
        int mejor = -1, mejorEscalon = -1;
        for (int i : DESEMPATE) {
            if (!vale.test(cinco[i])) continue;
            int e = escalon.applyAsInt(cinco[i]);
            if (e > mejorEscalon) {
                mejor = i;
                mejorEscalon = e;
            }
        }
        return mejor;
    }

    /** Lo que nunca se salva: vacio, Reliquias, Esencias, prestado, copias del Eco, el propio papel, > escalon 17. */
    private boolean valeParaSalvar(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        if (Marcas.tiene(it, Marcas.PRESTADO) || Marcas.tiene(it, Marcas.ECO_COPIA) || Marcas.tiene(it, Marcas.SALVOCONDUCTO)) return false;
        if (hc.items().esEsencia(it)) return false;
        Reliquias r = hc.reliquias();
        if (Marcas.tiene(it, Marcas.RELIQUIA) || (r != null && r.es(it))) return false;
        return Censo.escalon(it) <= 17;
    }

    /** Aparta la pieza y gasta un Salvoconducto. Devuelve la casilla salvada (indice) o -1. */
    private int salvoconducto(Player p) {
        if (!salvoActivo()) return -1;
        PlayerInventory inv = p.getInventory();
        UUID u = p.getUniqueId();
        int papel = -1;
        ItemStack[] todo = inv.getContents();
        for (int i = 0; i < todo.length; i++) {
            if (!Marcas.tiene(todo[i], Marcas.SALVOCONDUCTO)) continue;
            UUID dueno = Ligado.duenoDe(todo[i]);
            if (dueno == null || dueno.equals(u)) {
                papel = i;
                break;
            }
        }
        ItemStack[] cinco = {inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots(), inv.getItemInMainHand()};
        int cual = piezaQueSalva(true, papel >= 0, cinco, hc.datos().getString(rutaEleccion(u)),
                this::valeParaSalvar, Censo::escalon);
        if (cual < 0) return -1;

        ItemStack pieza = cinco[cual].clone();
        switch (cual) {
            case 0 -> inv.setHelmet(null);
            case 1 -> inv.setChestplate(null);
            case 2 -> inv.setLeggings(null);
            case 3 -> inv.setBoots(null);
            default -> inv.setItemInMainHand(null);
        }
        ItemStack s = inv.getItem(papel);
        if (s != null) {
            if (s.getAmount() > 1) s.setAmount(s.getAmount() - 1);
            else inv.setItem(papel, null);
        }
        List<String> guardadas = new ArrayList<>(hc.datos().getStringList(rutaDevolver(u)));
        guardadas.add(Entregas.aTexto(pieza));
        hc.datos().set(rutaDevolver(u), guardadas);
        hc.datos().set(rutaEleccion(u), null);
        hc.guardarYa();
        hc.plugin().bitacora().anotar("salvoconducto", "aparta", p.getName(), CASILLAS_SALVO.get(cual), descripcion(pieza));
        return cual;
    }

    private static String descripcion(ItemStack it) {
        String mmo = PuenteMmo.enlace(it);
        return mmo != null ? mmo : it.getType().getKey().getKey();
    }

    /** P-Q01 al reaparecer: devuelve lo apartado (se borra de los datos antes de dar nada). */
    private void devolverSalvado(Player p) {
        UUID u = p.getUniqueId();
        List<String> guardadas = hc.datos().getStringList(rutaDevolver(u));
        if (guardadas.isEmpty()) return;
        hc.datos().set(rutaDevolver(u), null);
        hc.guardarYa();
        for (String s : guardadas) {
            ItemStack it = Entregas.deTexto(s);
            if (it == null) {
                hc.plugin().bitacora().anotar("salvoconducto", "fallo", p.getName(), "objeto ilegible");
                continue;
            }
            Suelo.dar(hc.plugin(), p, it);
            ItemMeta meta = it.getItemMeta();
            Component nombre = meta != null && meta.hasDisplayName() && meta.displayName() != null
                    ? meta.displayName() : Component.translatable(it.getType().translationKey());
            p.sendMessage(ComandoCalamity.mensaje(Component.text("El Salvoconducto te devuelve ")
                    .append(nombre.colorIfAbsent(Paleta.DETALLE))
                    .append(Component.text("."))));
            hc.plugin().bitacora().anotar("salvoconducto", "devuelve", p.getName(), descripcion(it));
        }
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "item.book.page_turn", 1.0f, 0.8f);
    }

    /** Clic derecho con el Salvoconducto en la mano: el menu de una fila para elegir la pieza. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUsar(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !e.getAction().isRightClick()) return;
        ItemStack mano = e.getItem();
        if (!Marcas.tiene(mano, Marcas.SALVOCONDUCTO)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!salvoActivo()) {
            p.sendMessage(ComandoCalamity.mensaje("El Salvoconducto está desactivado ahora mismo."));
            return;
        }
        abrirEleccion(p);
    }

    /**
     * El menu del Salvoconducto (27, como los demas de Calamity): arriba en el centro que es,
     * en la fila del medio las cinco casillas que puede salvar y abajo en el centro Cerrar. El
     * relleno es cristal negro sin globo. Las casillas se guardan por su id (yelmo, pechera,
     * grebas, botas, arma); aqui solo cambia como se llaman para el jugador.
     */
    static final int SALVO_INFO = 4, SALVO_PRIMERA = 11, SALVO_CERRAR = 22;

    private void abrirEleccion(Player p) {
        Inventory inv = hc.plugin().getServer().createInventory(new MarcaSalvo(), 27,
                Marco.T_SALVOCONDUCTO.componente());
        String elegida = hc.datos().getString(rutaEleccion(p.getUniqueId()));
        Material[] iconos = {Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS,
                Material.IRON_SWORD};
        for (int i = 0; i < 5; i++) {
            boolean es = CASILLAS_SALVO.get(i).equals(elegida);
            List<Component> lore = new ArrayList<>();
            lore.add(Marco.texto("Salva lo que lleves " + DONDE_SALVO[i]));
            lore.add(Marco.texto("cuando mueras."));
            lore.add(Component.empty());
            lore.add(es ? Marco.tiene("Es tu elección.") : Marco.accion("Clic para elegirla"));
            inv.setItem(SALVO_PRIMERA + i, Marco.icono(iconos[i], Component.text(NOMBRES_SALVO[i], es ? AMBAR : PAPEL), lore, es));
        }
        inv.setItem(SALVO_INFO, Marco.icono(Material.PAPER, Component.text("Salvoconducto del Insomne", PAPEL), List.of(
                Marco.texto("Elige qué pieza te devuelve"),
                Marco.texto("si mueres en Calamity."),
                Component.empty(),
                Marco.tenue("Si no eliges, salva la mejor."),
                Marco.tenue("Nunca salva Reliquias ni Esencias,"),
                Marco.tenue("ni el equipo prestado del kit,"),
                Marco.tenue("ni nada más fuerte que la Guadaña."),
                Marco.tenue("Se gasta al morir.")), false));
        inv.setItem(SALVO_CERRAR, Marco.cerrar());
        for (int s = 0; s < inv.getSize(); s++) {
            if (inv.getItem(s) == null) inv.setItem(s, Marco.cristal(Material.BLACK_STAINED_GLASS_PANE));
        }
        p.openInventory(inv);
    }

    /** Como se llama para el jugador cada casilla de CASILLAS_SALVO, en su orden. */
    private static final String[] NOMBRES_SALVO = {"El casco", "La pechera", "Los pantalones", "Las botas",
            "Lo que lleves en la mano"};
    private static final String[] DONDE_SALVO = {"en la cabeza", "en el pecho", "en las piernas", "en los pies",
            "en la mano"};

    @EventHandler
    public void onClicSalvo(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MarcaSalvo)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClick() != ClickType.LEFT) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getInventory().getSize()) return;
        long ahora = System.currentTimeMillis();
        Long antes = ultimoClic.get(p.getUniqueId());
        if (antes != null && ahora - antes < ESPERA_MS) return;
        ultimoClic.put(p.getUniqueId(), ahora);
        if (slot == SALVO_CERRAR) {
            cerrarSalvo(p);
            return;
        }
        int i = slot - SALVO_PRIMERA;
        if (i < 0 || i >= CASILLAS_SALVO.size()) return;
        String casilla = CASILLAS_SALVO.get(i);
        hc.datos().set(rutaEleccion(p.getUniqueId()), casilla);
        hc.marcarSucio();
        p.sendMessage(ComandoCalamity.mensaje("Si mueres en Calamity, el Salvoconducto te devolverá "
                + NOMBRES_SALVO[i].substring(0, 1).toLowerCase(Locale.ROOT) + NOMBRES_SALVO[i].substring(1) + "."));
        cerrarSalvo(p);
    }

    /** Cerrar en mitad del evento de clic deja objetos fantasma en el cursor: un tick despues. */
    private void cerrarSalvo(Player p) {
        tarea(() -> {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof MarcaSalvo) p.closeInventory();
        }, 1L);
    }

    @EventHandler
    public void onArrastrarSalvo(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof MarcaSalvo) e.setCancelled(true);
    }

    // ============================================================ reposicion

    /** Las piezas de la Forja (yelmo, coraza...): pieza -> "TIPO.ID" (forja.piezas o los de serie). */
    Map<String, String> piezasForja() {
        Map<String, String> out = new LinkedHashMap<>();
        ConfigurationSection s = hc.cfg().getConfigurationSection("forja.piezas");
        if (s != null) for (String k : s.getKeys(false)) out.put(k, s.getString(k, ""));
        if (out.isEmpty()) {
            out.put("yelmo", "CALAMITY.YELMO_DE_CALAMIDAD");
            out.put("coraza", "CALAMITY.CORAZA_DE_CALAMIDAD");
            out.put("grebas", "CALAMITY.GREBAS_DE_CALAMIDAD");
            out.put("soleretas", "CALAMITY.SOLERETAS_DE_CALAMIDAD");
            out.put("hacha", "CALAMITY_ARMAS.HACHA_DEL_HERALDO");
            out.put("mascara", "CALAMITY.MASCARA_DEL_ECO");
            out.put("filo", "CALAMITY_ARMAS.FILO_DEL_ECO");
            out.put("guadana", "CALAMITY_ARMAS.GUADANA_DE_LA_PARCA");
        }
        // El set del Vigilante cuenta aunque el forja.piezas del servidor no lo traiga: su id de la config si
        // lo lleva (con los de serie del jar detras) y, si no, el de Entregas.MMO_DEFECTO.
        for (String k : Forja.PIEZAS_VIGILANTE) {
            if (out.containsKey(k)) continue;
            String id = hc.cfg().getString("forja.piezas." + k);
            if (id == null || id.isBlank()) id = Entregas.MMO_DEFECTO.get(k);
            if (id != null) out.put(k, id);
        }
        return out;
    }

    /**
     * Apunta perdidas.<uuid>.<pieza> = ahora por cada pieza del Manto, el Hacha, la Guadana o el set
     * del Vigilante que llevaba puesta o en las manos (DIS M32: reposicion durante forja.reposicion-dias).
     * La Mascara y el Filo no tienen reposicion (PLAN sec. 4). La que salvo el Salvoconducto
     * ya no esta en el inventario, asi que no se apunta.
     */
    private void apuntarPerdidas(Player p, int salvada) {
        if (!PuenteMmo.disponible()) return;
        Map<String, String> piezas = piezasForja();
        PlayerInventory inv = p.getInventory();
        ItemStack[] llevaba = {inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots(),
                inv.getItemInMainHand(), inv.getItemInOffHand()};
        long ahora = System.currentTimeMillis();
        List<String> apuntadas = new ArrayList<>();
        for (ItemStack it : llevaba) {
            String id = PuenteMmo.enlace(it);
            if (id == null) continue;
            for (Map.Entry<String, String> e : piezas.entrySet()) {
                String pieza = e.getKey();
                if (!id.equals(e.getValue()) || pieza.equals("mascara") || pieza.equals("filo")) continue;
                hc.datos().set("perdidas." + p.getUniqueId() + "." + pieza, ahora);
                apuntadas.add(pieza);
            }
        }
        if (apuntadas.isEmpty()) return;
        hc.marcarSucio();
        hc.plugin().bitacora().anotar("forja", "perdida", p.getName(), String.join(",", apuntadas),
                salvada >= 0 ? "salvada " + CASILLAS_SALVO.get(salvada) : "-");
    }

    // ================================================================ pruebas

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        UUID u = Autotest.sintetico(301), otro = Autotest.sintetico(302);

        // Talisman: en un inventario sintetico, x0,80; sin el, x1; ligado a otro no cuenta.
        Entregas ent = hc.entregas();
        ItemStack talisman = ent != null ? ent.talisman() : objetoMarcado(Material.CLOCK, Marcas.TALISMAN);
        ItemStack[] inv = {new ItemStack(Material.BREAD), talisman, null};
        h.ok("talisman en el inventario cuenta", llevaTalisman(inv, u));
        h.cerca("talisman: drenaje x0,80", 0.80, factorTalisman(llevaTalisman(inv, u), 0.80), 1e-9);
        h.cerca("sin talisman: drenaje x1", 1.0,
                factorTalisman(llevaTalisman(new ItemStack[]{new ItemStack(Material.BREAD)}, u), 0.80), 1e-9);
        h.ok("talisman ligado a otro no cuenta", !llevaTalisman(new ItemStack[]{Ligado.ligar(talisman.clone(), otro)}, u));
        h.ok("talisman ligado a el si cuenta", llevaTalisman(new ItemStack[]{Ligado.ligar(talisman.clone(), u)}, u));
        h.cerca("config rara de drenaje -> 0,80", 0.80, factorTalisman(true, 3.0), 1e-9);

        // Grabado: Filo V -> VI en una espada de diamante, en memoria.
        YamlConfiguration datos = new YamlConfiguration();
        String semana = "2026-W39";
        List<Enchantment> permitidos = permitidos();
        Enchantment filo = encantamiento("SHARPNESS");
        h.ok("SHARPNESS se resuelve", filo != null);
        if (filo != null) {
            ItemStack espada = new ItemStack(Material.DIAMOND_SWORD);
            espada.addEnchantment(filo, 5);
            h.igual("espada con Filo V se puede grabar", null, motivoNoGraba(espada, u, datos, semana, 1, permitidos, null));
            h.igual("grabar Filo V", null, grabar(espada, filo, "id-prueba", u, datos, semana, 1, permitidos, null));
            h.igual("Filo V -> VI", 6, espada.getEnchantmentLevel(filo));
            h.igual("lleva lethal_world:grabado con el id del Grabado", "id-prueba",
                    espada.getItemMeta().getPersistentDataContainer().get(Marcas.GRABADO, PersistentDataType.STRING));
            h.igual("un grabado por objeto", "grabado", motivoNoGraba(espada, otro, datos, semana, 1, permitidos, null));

            ItemStack otra = new ItemStack(Material.DIAMOND_SWORD);
            otra.addEnchantment(filo, 5);
            h.igual("2.o grabado en la semana -> P-W09", "semana", grabar(otra, filo, "id-2", u, datos, semana, 1, permitidos, null));
            h.igual("y no sube nada", 5, otra.getEnchantmentLevel(filo));
            h.ok("P-W09 dice su texto", Hardcore.plano(avisoGrabado("semana")).contains("Esta semana ya has grabado algo."));
            h.igual("la semana siguiente vuelve a poder", null, motivoNoGraba(otra, u, datos, "2026-W40", 1, permitidos, null));

            ItemStack flojo = new ItemStack(Material.DIAMOND_SWORD);
            flojo.addEnchantment(filo, 4);
            h.igual("Filo IV no esta en su tope", "encantamiento", motivoNoGraba(flojo, otro, datos, semana, 1, permitidos, null));

            ItemStack mmo = null;
            try {
                mmo = Bukkit.getItemFactory().createItemStack(
                        "minecraft:diamond_sword[minecraft:custom_data={MMOITEMS_ITEM_TYPE:\"SWORD\",MMOITEMS_ITEM_ID:\"PRUEBA\"}]");
            } catch (Throwable t) {
                h.ok("crear un objeto con marca MMOItems: " + t, false);
            }
            if (mmo != null) {
                mmo.addUnsafeEnchantment(filo, 5);
                h.ok("objeto con marca MMOItems reconocido", esMmo(mmo));
                h.igual("objeto con marca MMOItems -> rechazado", "mmo", motivoNoGraba(mmo, otro, datos, semana, 1, permitidos, null));
            }
            h.ok("espada vanilla no es MMOItems", !esMmo(new ItemStack(Material.DIAMOND_SWORD)));
        }
        Enchantment prot = encantamiento("PROTECTION");
        if (prot != null) {
            ItemStack peto = new ItemStack(Material.DIAMOND_CHESTPLATE);
            peto.addEnchantment(prot, 4);
            h.igual("Proteccion IV sube a V", List.of(prot), grabables(peto, List.of(prot), null));
            peto.addUnsafeEnchantment(prot, 5);
            h.igual("Proteccion V ya no sube", List.of(), grabables(peto, List.of(prot), null));
        }
        h.ok("la prueba no toca hardcore-datos.yml", !hc.datos().isSet(rutaGrabados(semana, u)));
        h.igual("casilla de un Grabado en el inventario", 1,
                casillaGrabado(new ItemStack[]{new ItemStack(Material.FLINT), ent != null ? ent.grabado()
                        : objetoMarcado(Material.FLINT, Marcas.GRABADO)}, u));

        // Salvoconducto: apagado no aparta nada; encendido, la elegida o la de mayor escalon.
        ItemStack[] cinco = {new ItemStack(Material.IRON_HELMET), new ItemStack(Material.DIAMOND_CHESTPLATE),
                null, new ItemStack(Material.LEATHER_BOOTS), new ItemStack(Material.NETHERITE_SWORD)};
        Predicate<ItemStack> vale = it -> it != null && !it.getType().isAir();
        h.igual("Salvoconducto apagado: alMorir no aparta nada", -1,
                piezaQueSalva(false, true, cinco, null, vale, Censo::escalon));
        h.igual("sin Salvoconducto en el inventario no aparta nada", -1,
                piezaQueSalva(true, false, cinco, null, vale, Censo::escalon));
        h.igual("encendido: la de mayor escalon (la espada de netherita)", 4,
                piezaQueSalva(true, true, cinco, null, vale, Censo::escalon));
        h.igual("encendido con eleccion: el yelmo", 0,
                piezaQueSalva(true, true, cinco, "yelmo", vale, Censo::escalon));
        h.igual("eleccion en una casilla vacia: la de mayor escalon", 4,
                piezaQueSalva(true, true, cinco, "grebas", vale, Censo::escalon));
        h.igual("empate: pechera antes que arma", 1,
                piezaQueSalva(true, true, new ItemStack[]{null, new ItemStack(Material.DIAMOND_CHESTPLATE), null, null,
                        new ItemStack(Material.DIAMOND_SWORD)}, null, vale, Censo::escalon));
        return h.lineas();
    }

    private static ItemStack objetoMarcado(Material m, NamespacedKey marca) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(marca, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }
}
