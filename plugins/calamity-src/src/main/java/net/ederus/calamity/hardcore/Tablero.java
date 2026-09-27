package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * M18 · Tablero (/calamity tablero y el boton de los rankings del Cazador): con poca gente
 * dentro, lo que hace que se encuentren.
 *
 * Menu de 36 con el marco de Calamity (Marco), solo clic izquierdo y un clic cada 500 ms. Cada
 * fila lleva su banda de color a los lados (1.3.1; antes un icono suelto en la columna 0 que se
 * confundia con un Eco mas). Fila 1, banda turquesa: los tablero.ecos (7) Ecos con mas Reliquias,
 * errantes incluidos (las Reliquias en el numero de la pila): dueno, nivel, bioma, distancia a la
 * llegada redondeada a tablero.redondeo (50) y horas que le quedan. Fila 2, banda morada: las
 * PARCAs vivas, a quien siguen y en que bioma. Abajo, los rankings de la semana si estan
 * abiertos. Sin coordenadas: el que quiera el botin, que lo busque.
 *
 * Lo que se pinta se calcula como mucho cada tablero.cache-segundos (30) y solo cuando alguien
 * lo abre: sin nadie mirando no cuesta nada. El bioma se pide con World.getBiome, que para un
 * chunk sin cargar pregunta al generador y no lo carga.
 */
final class Tablero implements Listener {

    static final TextColor VERDE = Paleta.DETALLE;
    static final TextColor AMBAR = TextColor.color(0xE8A33D);
    private static final int CABECERA = 4, ECOS = 9, PARCAS = 18, RANKINGS = 31;
    private static final long ESPERA_MS = 500;

    /** Marca de nuestro inventario: casilla -> que hace. */
    record Marca(Map<Integer, String> acciones) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    record LineaEco(String dueno, int nivel, int reliquias, Biome bioma, long distancia, long horas, boolean errante) {
    }

    record LineaParca(String presa, Biome bioma) {
    }

    private final Hardcore hc;
    private final Map<UUID, Long> ultimoClic = new HashMap<>();
    private final Set<BukkitTask> tareas = new HashSet<>();
    private List<LineaEco> ecos = List.of();
    private List<LineaParca> parcas = List.of();
    private long calculado;

    Tablero(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Subcomandos.calamity().registrar("tablero", "Ecos con botín y Parcas sueltas", "lethalworld.calamity",
                (quien, args) -> {
                    if (quien instanceof Player p) abrir(p);
                    else quien.sendMessage(ComandoCalamity.mensaje("Solo para jugadores."));
                }, null);
        Autotest.registrar("tablero", this::autotest);
    }

    boolean activo() {
        return hc.cfg().getBoolean("tablero.activo", false);
    }

    // ------------------------------------------------------------------ datos

    /** Redondeo al multiplo mas cercano (50 -> "a ~250"). Nunca 0: "a ~0" no dice nada. */
    static long redondear(double distancia, int paso) {
        if (paso <= 0) return Math.round(distancia);
        long r = Math.round(distancia / paso) * paso;
        return Math.max(paso, r);
    }

    /** Horas que le quedan, hacia arriba (con 20 minutos, "1 h"). */
    static long horasQuedan(long expira, long ahora) {
        long q = expira - ahora;
        return q <= 0 ? 0 : (q + 3_599_999L) / 3_600_000L;
    }

    private void calcular() {
        long ahora = System.currentTimeMillis();
        if (ahora - calculado < Math.max(1, hc.cfg().getInt("tablero.cache-segundos", 30)) * 1000L) return;
        calculado = ahora;

        int maxEcos = Math.max(0, Math.min(7, hc.cfg().getInt("tablero.ecos", 7)));
        int paso = hc.cfg().getInt("tablero.redondeo", 50);
        Location llegada = hc.punto("llegada");
        List<LineaEco> le = new ArrayList<>();
        Ecos gestor = hc.ecos();
        if (gestor != null) {
            List<Eco> vivos = new ArrayList<>(gestor.vivos());
            vivos.sort(Comparator.comparingInt(Eco::nReliquias).reversed().thenComparing(Comparator.comparingInt((Eco e) -> e.nivel).reversed()));
            for (Eco e : vivos) {
                if (le.size() >= maxEcos) break;
                Location l = e.anclaje();
                if (l == null || l.getWorld() == null) continue;
                Biome b = l.getWorld().getBiome(l.getBlockX(), l.getBlockY(), l.getBlockZ());
                long d = llegada != null && llegada.getWorld() == l.getWorld() ? redondear(llegada.distance(l), paso) : -1;
                le.add(new LineaEco(e.nombre == null ? "alguien" : e.nombre, e.nivel, e.nReliquias(), b, d,
                        horasQuedan(e.expira, ahora), e.errante));
            }
        }
        List<LineaParca> lp = new ArrayList<>();
        Parca parca = hc.parca();
        if (parca != null) {
            for (World w : hc.plugin().getServer().getWorlds()) {
                if (!hc.esHardcore(w)) continue;
                for (WitherSkeleton ws : w.getEntitiesByClass(WitherSkeleton.class)) {
                    if (lp.size() >= 7) break;
                    if (!"parca".equals(Marcas.amenaza(ws))) continue;
                    ParcaViva pe = parca.deCuerpo(ws);
                    if (pe == null || !pe.vivaParaJugadores()) continue;
                    Location l = ws.getLocation();
                    lp.add(new LineaParca(pe.presaNombre(), w.getBiome(l.getBlockX(), l.getBlockY(), l.getBlockZ())));
                }
            }
        }
        ecos = List.copyOf(le);
        parcas = List.copyOf(lp);
    }

    // ------------------------------------------------------------------ menu

    /** /calamity tablero y el boton de los rankings del Cazador. */
    void abrir(Player p) {
        if (!activo()) {
            p.sendMessage(ComandoCalamity.mensaje("El Tablero no está colgado ahora mismo."));
            return;
        }
        calcular();
        Marca m = new Marca(new HashMap<>());
        Inventory inv = hc.plugin().getServer().createInventory(m, 36, Marco.T_TABLERO.componente());
        int cada = Math.max(1, hc.cfg().getInt("tablero.cache-segundos", 30));
        inv.setItem(CABECERA, Marco.icono(Material.ITEM_FRAME, Component.text("Tablero de Calamity", Paleta.MARCA), List.of(
                Marco.texto("Quién guarda botín ahí dentro"), Marco.texto("y dónde siega la Parca."), Component.empty(),
                Marco.tenue("Sin coordenadas: búscalos."), Marco.tenue("Se actualiza cada " + cada + " s.")), false));
        inv.setItem(Marco.CERRAR, Marco.cerrar());
        m.acciones().put(Marco.CERRAR, "cerrar");

        Marco.ponerBanda(inv, ECOS, Marco.banda(Material.CYAN_STAINED_GLASS_PANE, "Ecos con botín",
                List.of("Los que más Reliquias guardan,", "errantes incluidos.")));
        List<ItemStack> le = new ArrayList<>();
        for (LineaEco e : ecos) {
            List<Component> lore = new ArrayList<>();
            lore.add(Marco.dato("Nivel", String.valueOf(e.nivel())));
            lore.add(Marco.dato("Reliquias", String.valueOf(e.reliquias())));
            lore.add(Component.translatable(e.bioma().translationKey(), Paleta.TEXTO));
            if (e.distancia() >= 0) lore.add(Marco.tenue("A ~" + e.distancia() + " bloques de la llegada"));
            lore.add(Marco.tenue(e.horas() <= 1 ? "Queda menos de 1 h" : "Quedan " + e.horas() + " h"));
            if (e.errante()) lore.add(Component.text("Errante", Paleta.AVISO));
            le.add(Marco.icono(new ItemStack(Material.ECHO_SHARD, Math.max(1, Math.min(64, e.reliquias()))),
                    Component.text("Eco de " + e.dueno(), Paleta.ECO), lore, e.reliquias() > 0));
        }
        if (le.isEmpty()) {
            le.add(Marco.icono(Material.GRAY_DYE, Component.text("Ningún Eco suelto", Paleta.TENUE),
                    List.of(Marco.tenue("Nadie ha dejado nada que buscar.")), false));
        }
        poner(inv, ECOS, le);

        Marco.ponerBanda(inv, PARCAS, Marco.banda(Material.PURPLE_STAINED_GLASS_PANE, "Parcas sueltas",
                List.of("Dónde siega ahora mismo", "y a quién sigue.")));
        List<ItemStack> lp = new ArrayList<>();
        for (LineaParca pa : parcas) {
            String titulo = pa.presa() == null ? "Una Parca siega" : "Una Parca siega cerca de " + pa.presa();
            lp.add(Marco.icono(Material.WITHER_SKELETON_SKULL, Component.text(titulo, Paleta.PARCA),
                    List.of(Component.translatable(pa.bioma().translationKey(), Paleta.TEXTO)), false));
        }
        if (lp.isEmpty()) {
            lp.add(Marco.icono(Material.GRAY_DYE, Component.text("Ninguna Parca", Paleta.TENUE),
                    List.of(Marco.tenue("Por ahora nadie se ha quedado quieto.")), false));
        }
        poner(inv, PARCAS, lp);

        Npcs n = hc.npcs();
        if (n != null && n.cazador().hay()) {
            inv.setItem(RANKINGS, Marco.boton(Material.GOLDEN_HELMET, "Rankings de la semana",
                    List.of("El podio de cada ranking."), "Clic para verlos", true));
            m.acciones().put(RANKINGS, "rankings");
        }
        Marco.rellenar(inv);
        p.openInventory(inv);
        Marco.sonar(p, "item.book.page_turn", 0.8f, 0.9f);
    }

    /** Las cosas de una fila, centradas entre sus bandas (como en la Forja). */
    private static void poner(Inventory inv, int base, List<ItemStack> cosas) {
        int[] cols = Marco.columnas(Math.min(Marco.COLUMNAS, cosas.size()));
        for (int i = 0; i < cols.length; i++) inv.setItem(base + cols[i], cosas.get(i));
    }

    @EventHandler
    public void alClic(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Marca m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClick() != ClickType.LEFT) return;
        String accion = m.acciones().get(e.getRawSlot());
        if (accion == null) return;
        long ahora = System.currentTimeMillis();
        Long antes = ultimoClic.get(p.getUniqueId());
        if (antes != null && ahora - antes < ESPERA_MS) return;
        ultimoClic.put(p.getUniqueId(), ahora);
        // Cerrar o abrir otro menu dentro del evento de clic deja objetos fantasma en el cursor:
        // un tick despues.
        final BukkitTask[] t = new BukkitTask[1];
        t[0] = hc.plugin().getServer().getScheduler().runTask(hc.plugin(), () -> {
            tareas.remove(t[0]);
            if (accion.equals("rankings")) {
                Npcs n = hc.npcs();
                if (n != null) hc.seguro("cazador", () -> n.cazador().abrir(p));
            } else if (p.getOpenInventory().getTopInventory().getHolder() instanceof Marca) {
                p.closeInventory();
            }
        });
        tareas.add(t[0]);
    }

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Marca) e.setCancelled(true);
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        ultimoClic.remove(e.getPlayer().getUniqueId());
    }

    void parar() {
        HandlerList.unregisterAll(this);
        for (BukkitTask t : tareas) t.cancel();
        tareas.clear();
        // Un menu abierto de un modulo parado se quedaria sin nadie que atienda sus clics.
        for (Player p : hc.plugin().getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Marca) p.closeInventory();
        }
        ultimoClic.clear();
    }

    // ------------------------------------------------------------------ autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        h.igual("234 bloques -> ~250", 250L, redondear(234, 50));
        h.igual("224 bloques -> ~200", 200L, redondear(224, 50));
        h.igual("10 bloques -> ~50 (nunca 0)", 50L, redondear(10, 50));
        long ahora = 1_800_000_000_000L;
        h.igual("20 minutos -> 1 h", 1L, horasQuedan(ahora + 20 * 60_000L, ahora));
        h.igual("caducado -> 0", 0L, horasQuedan(ahora - 1, ahora));
        h.igual("11 h y 1 min -> 12 h", 12L, horasQuedan(ahora + 11 * 3_600_000L + 60_000L, ahora));
        h.ok("/calamity tablero registrado", Subcomandos.calamity().nombres(null).contains("tablero"));
        return h.lineas();
    }
}
