package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * La Bendicion de Dios dentro de Calamity (pedido de Dosa, 2026-10-06: "que se imponga sobre las reglas de
 * Calamity"). Es el holywhitescroll de AdvancedEnchantments: un objeto bendecido no se pierde al morir, y la
 * bendicion se gasta con esa muerte.
 *
 * Como lo hace AE 9.24.13 (KeepOnDeathEffect, leido en el jar):
 *   - aplicarla le pone al objeto la marca advancedenchantments:holywhitescrolled (PDC, STRING "true") y una
 *     linea de lore, items.holywhitescroll.settings.lore-display (en Ederus "☀ Bendición de Dios ☀");
 *   - en PlayerDeathEvent HIGH saca de lo que suelta cada objeto con la marca, le quita marca y linea (salvo
 *     keep-after-death: true) y lo guarda EN MEMORIA hasta PlayerRespawnEvent HIGH, donde se lo da.
 *
 * Dentro de Calamity eso ya pasaba antes que Hardcore.onMuerte (HIGHEST), pero dejaba grietas: la pieza
 * bendecida que se llevaba puesta seguia en la casilla de armadura (AE solo la quita de lo que suelta), asi que
 * el Salvoconducto podia salvarla otra vez y la reposicion de la Forja la apuntaba como perdida (dos copias);
 * el Eco la copiaba como si la hubiera perdido; lo prestado del kit, las Reliquias y las Esencias salian
 * bendecidas; huir por el cable la perdia, y un reinicio entre morir y reaparecer tambien.
 *
 * Por eso aqui lo hace Calamity, en LOW (antes que AE) y solo en sus mundos con muerte.lo-pierde-todo:
 *   - lo bendecido que se salva sale del inventario y de lo que suelta (AE ya no ve nada que hacer), y en
 *     MONITOR, si la muerte no se cancelo, se le gasta la bendicion como haria AE y se guarda en
 *     hardcore-datos.yml (bendicion.devolver.<uuid>). Se devuelve al reaparecer o al volver a entrar;
 *   - lo que en Calamity nunca se salva (prestado, copias del Eco, pergaminos de Maren, Reliquias y Esencias:
 *     lo de la Aduana) pierde la bendicion y corre la suerte de todo lo demas. Tampoco se deja bendecir;
 *   - con muerte.bendicion: false nada se salva y todo pierde la bendicion (como "lo pierde todo" a secas).
 * Como el inventario ya no tiene lo salvado cuando Hardcore.onMuerte hace la foto, el Eco, el Salvoconducto, la
 * reposicion de la Forja y la telemetria ven solo lo que de verdad se pierde. Nada se duplica: lo salvado sale
 * del inventario y de lo que suelta en el mismo paso, y se borra de los datos antes de devolverlo.
 *
 * La Corrupcion no entra: la bendicion protege de morir, y lo que se pudre con un cofre se pierde igual.
 *
 * Staff: /calamity selftest blessing.
 */
final class Bendicion implements Listener {

    /** La marca de AE en el objeto bendecido (PDCHandler: NamespacedKey del plugin, en minusculas). */
    static final NamespacedKey BENDECIDO = new NamespacedKey("advancedenchantments", "holywhitescrolled");
    /** La del pergamino sin aplicar: lo que se arrastra encima del objeto. */
    static final NamespacedKey PERGAMINO_AE = new NamespacedKey("advancedenchantments", "holywhitescroll");

    /** La linea de AE si su config no dice otra (sin colores). */
    static final String LINEA_AE_DE_SERIE = "*HOLY* PROTECTED";
    /** La de Ederus, por si no se puede leer la config de AE. */
    static final String LINEA_EDERUS = "☀ Bendición de Dios ☀";

    /** Que le pasa a un objeto al morir en Calamity. */
    enum Destino {
        /** No esta bendecido: lo de siempre. */
        NADA,
        /** Se salva: sale del inventario y se devuelve sin la bendicion. */
        SALVA,
        /** Pierde la bendicion y corre la suerte de todo lo demas. */
        GASTA
    }

    /** Lo apartado de un muerto: la casilla de donde salio (-1: solo estaba en lo que suelta) y el objeto. */
    record Apartado(int casilla, ItemStack objeto) {
    }

    /** Lo que sale de repartir: lo salvado, en orden, y la descripcion de lo que perdio la bendicion. */
    record Reparto(List<Apartado> salvados, List<String> gastados) {
    }

    /** El pergamino y el objeto de un clic, antes de que AE haga nada (para deshacerlo). */
    private record Aplicacion(int casilla, ItemStack pergamino, ItemStack objeto, String clase) {
    }

    private final Hardcore hc;
    /** Lo apartado entre LOW y MONITOR de una misma muerte (mismo tick). */
    private final Map<UUID, List<Apartado>> enCurso = new HashMap<>();
    private final Map<UUID, Aplicacion> aplicando = new HashMap<>();
    private YamlConfiguration configAe;
    private long leidaAe = -1;

    Bendicion(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("bendicion", this::autotest);
    }

    void parar() {
        HandlerList.unregisterAll(this);
        // Una parada a mitad de una muerte no deja nada colgado: lo apartado va a los datos.
        for (Map.Entry<UUID, List<Apartado>> e : enCurso.entrySet()) {
            Player p = hc.plugin().getServer().getPlayer(e.getKey());
            if (p != null) guardar(p, e.getValue(), "parada");
        }
        enCurso.clear();
        aplicando.clear();
    }

    boolean activa() {
        return hc.cfg().getBoolean("muerte.bendicion", true);
    }

    // ================================================================== nucleo puro

    private static final Pattern COLORES = Pattern.compile(
            "\\{#[0-9a-fA-F]{6}}|<#[0-9a-fA-F]{6}>|&#[0-9a-fA-F]{6}|[&§][0-9a-fk-orxA-FK-ORX]|</?[a-zA-Z#][^>]*>");

    /** El texto sin codigos de color (los de AE {#rrggbb}, los & y §, y las etiquetas <...>). */
    static String plano(String texto) {
        return texto == null ? "" : COLORES.matcher(texto).replaceAll("").trim();
    }

    /** Si una linea de lore (ya plana) es la de la bendicion, como compara AE: sin colores y sin mayusculas. */
    static boolean esLinea(String lineaPlana, List<String> buscadas) {
        String l = plano(lineaPlana);
        if (l.isEmpty()) return false;
        for (String b : buscadas) if (!b.isEmpty() && l.equalsIgnoreCase(plano(b))) return true;
        return false;
    }

    static Destino destino(boolean bendecido, boolean activa, String clase) {
        if (!bendecido) return Destino.NADA;
        return activa && clase == null ? Destino.SALVA : Destino.GASTA;
    }

    /** El indice del primero de la lista que case con x, o -1. */
    static <T> int casar(List<T> lista, T x, BiPredicate<T, T> igual) {
        for (int i = 0; i < lista.size(); i++) if (igual.test(lista.get(i), x)) return i;
        return -1;
    }

    // ============================================================ objetos (servidor)

    static boolean bendecido(ItemStack it) {
        return Marcas.tiene(it, BENDECIDO);
    }

    static boolean esPergaminoAe(ItemStack it) {
        return Marcas.tiene(it, PERGAMINO_AE);
    }

    /**
     * Lo que en Calamity nunca se salva, aunque este bendecido: "prestado" (Kit), "eco" (copia del Eco),
     * "pergamino" (contrato de Maren), "reliquia" y "esencia" (lo que pasa por la Aduana), o null.
     */
    String clase(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        if (Marcas.tiene(it, Marcas.PRESTADO)) return "prestado";
        if (Marcas.tiene(it, Marcas.ECO_COPIA)) return "eco";
        if (Marcas.tiene(it, Marcas.PERGAMINO)) return "pergamino";
        Reliquias r = hc.reliquias();
        if (Marcas.tiene(it, Marcas.RELIQUIA) || (r != null && hc.valor("reliquias", () -> r.es(it), false))) return "reliquia";
        if (hc.items().esEsencia(it)) return "esencia";
        return null;
    }

    /** Le quita la bendicion como AE: la marca y toda linea de lore que sea la suya. Cambia el objeto. */
    static void quitar(ItemStack it, List<String> lineas) {
        if (it == null || it.getType().isAir()) return;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().remove(BENDECIDO);
        List<Component> lore = meta.lore();
        if (lore != null) {
            List<Component> queda = new ArrayList<>();
            for (Component c : lore) {
                if (!esLinea(PlainTextComponentSerializer.plainText().serialize(c), lineas)) queda.add(c);
            }
            if (queda.size() != lore.size()) meta.lore(queda.isEmpty() ? null : queda);
        }
        it.setItemMeta(meta);
    }

    /**
     * El reparto de una muerte, sin tocar al jugador: contenido es el inventario entero (lo cambia en el sitio:
     * lo salvado queda en null y lo que gasta, sin bendicion) y drops lo que suelta (tambien en el sitio: lo
     * salvado sale y lo que gasta pierde la bendicion). Lo que suelta y no estaba en el inventario (la mesa del
     * Engarzador) tambien se salva, con casilla -1. Con drops vacio (keepInventory) manda el inventario.
     */
    static Reparto repartir(ItemStack[] contenido, List<ItemStack> drops, boolean activa,
                            Function<ItemStack, String> clase, Consumer<ItemStack> quitar) {
        List<Apartado> salvados = new ArrayList<>();
        List<String> gastados = new ArrayList<>();
        List<ItemStack> porCasar = new ArrayList<>();
        for (int i = 0; contenido != null && i < contenido.length; i++) {
            ItemStack it = contenido[i];
            if (!bendecido(it)) continue;
            String c = clase.apply(it);
            if (destino(true, activa, c) == Destino.SALVA) {
                salvados.add(new Apartado(i, it.clone()));
                porCasar.add(it.clone());
                contenido[i] = null;
            } else {
                ItemStack sin = it.clone();
                quitar.accept(sin);
                contenido[i] = sin;
                gastados.add((c == null ? "apagada" : c) + " " + descripcion(it));
            }
        }
        if (drops != null) {
            for (Iterator<ItemStack> i = drops.iterator(); i.hasNext(); ) {
                ItemStack it = i.next();
                if (!bendecido(it)) continue;
                if (destino(true, activa, clase.apply(it)) == Destino.GASTA) {
                    quitar.accept(it);
                    continue;
                }
                int k = casar(porCasar, it, (a, b) -> a.isSimilar(b) && a.getAmount() == b.getAmount());
                if (k >= 0) porCasar.remove(k);
                else salvados.add(new Apartado(-1, it.clone()));
                i.remove();
            }
        }
        return new Reparto(salvados, gastados);
    }

    static String descripcion(ItemStack it) {
        String mmo = PuenteMmo.enlace(it);
        return mmo != null ? mmo : it.getType().getKey().getKey();
    }

    // ===================================================================== AE

    /** La config de AE (se relee si cambia en disco), o null sin AE. */
    private ConfigurationSection configAe() {
        try {
            Plugin ae = Bukkit.getPluginManager().getPlugin("AdvancedEnchantments");
            if (ae == null) return null;
            File f = new File(ae.getDataFolder(), "config.yml");
            if (!f.isFile()) return null;
            if (configAe == null || f.lastModified() != leidaAe) {
                configAe = YamlConfiguration.loadConfiguration(f);
                leidaAe = f.lastModified();
            }
            return configAe;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Las lineas que puede llevar un objeto bendecido: la de la config de AE, la de serie y la de Ederus. */
    List<String> lineas() {
        List<String> out = new ArrayList<>();
        ConfigurationSection c = configAe();
        if (c != null) {
            String l = c.getString("items.holywhitescroll.settings.lore-display");
            if (l != null && !l.isBlank()) out.add(l);
        }
        out.add(LINEA_AE_DE_SERIE);
        out.add(LINEA_EDERUS);
        return out;
    }

    /** keep-after-death de AE: con true, la bendicion no se gasta al morir (ni fuera ni aqui). */
    boolean seQueda() {
        ConfigurationSection c = configAe();
        return c != null && c.getBoolean("items.holywhitescroll.settings.keep-after-death", false);
    }

    // ================================================================== la muerte

    /** LOW: antes que AE (HIGH) y que Hardcore.onMuerte (HIGHEST), despues del Engarzador (LOWEST). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void alMorir(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!hc.activo() || !hc.esHardcore(p) || !hc.cfg().getBoolean("muerte.lo-pierde-todo", true)) return;
        hc.seguro("bendicion", () -> apartar(p, e));
    }

    private void apartar(Player p, PlayerDeathEvent e) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contenido = inv.getContents();
        List<String> lineas = lineas();
        Reparto r = repartir(contenido, e.getDrops(), activa(), this::clase, it -> quitar(it, lineas));
        if (r.salvados().isEmpty() && r.gastados().isEmpty()) return;
        inv.setContents(contenido);
        for (String g : r.gastados()) hc.plugin().bitacora().anotar("bendicion", "gasta", p.getName(), g);
        if (!r.salvados().isEmpty()) enCurso.put(p.getUniqueId(), r.salvados());
    }

    /** MONITOR: con la muerte ya decidida se gasta la bendicion y se guarda; si alguien la cancelo, se devuelve. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void trasMorir(PlayerDeathEvent e) {
        Player p = e.getEntity();
        List<Apartado> salvados = enCurso.remove(p.getUniqueId());
        if (salvados == null) return;
        hc.seguro("bendicion", () -> {
            if (e.isCancelled()) reponer(p, salvados);
            else guardar(p, salvados, "muerte");
        });
    }

    /**
     * Desde Combate.cable, ANTES de la foto del Eco: huir por el cable cuenta como morir, tambien para la
     * bendicion. Lo que se salva se guarda ya (el jugador se esta yendo) y se devuelve al volver.
     */
    void alHuir(Player p) {
        if (!hc.cfg().getBoolean("muerte.lo-pierde-todo", true)) return;
        PlayerInventory inv = p.getInventory();
        ItemStack[] contenido = inv.getContents();
        List<String> lineas = lineas();
        Reparto r = repartir(contenido, null, activa(), this::clase, it -> quitar(it, lineas));
        if (r.salvados().isEmpty() && r.gastados().isEmpty()) return;
        inv.setContents(contenido);
        for (String g : r.gastados()) hc.plugin().bitacora().anotar("bendicion", "gasta", p.getName(), g);
        if (!r.salvados().isEmpty()) guardar(p, r.salvados(), "cable");
    }

    private static String ruta(UUID u) {
        return "bendicion.devolver." + u;
    }

    /** Gasta la bendicion de lo salvado (salvo keep-after-death) y lo apunta para devolverlo. */
    private void guardar(Player p, List<Apartado> salvados, String motivo) {
        boolean seQueda = seQueda();
        List<String> lineas = lineas();
        List<String> guardadas = new ArrayList<>(hc.datos().getStringList(ruta(p.getUniqueId())));
        List<String> nombres = new ArrayList<>();
        for (Apartado a : salvados) {
            ItemStack it = a.objeto().clone();
            if (!seQueda) quitar(it, lineas);
            guardadas.add(Entregas.aTexto(it));
            nombres.add(descripcion(it));
        }
        hc.datos().set(ruta(p.getUniqueId()), guardadas);
        hc.guardarYa();
        hc.plugin().bitacora().anotar("bendicion", "guarda", p.getName(), motivo, String.valueOf(salvados.size()),
                String.join(",", nombres));
    }

    /** Una muerte cancelada: lo apartado vuelve tal cual (con su bendicion) a su casilla, o a donde quepa. */
    private void reponer(Player p, List<Apartado> salvados) {
        PlayerInventory inv = p.getInventory();
        for (Apartado a : salvados) {
            ItemStack ya = a.casilla() >= 0 && a.casilla() < inv.getSize() ? inv.getItem(a.casilla()) : null;
            if (a.casilla() >= 0 && a.casilla() < inv.getSize() && (ya == null || ya.getType().isAir())) {
                inv.setItem(a.casilla(), a.objeto());
            } else {
                Suelo.dar(hc.plugin(), p, a.objeto());
            }
        }
        hc.plugin().bitacora().anotar("bendicion", "repone", p.getName(), String.valueOf(salvados.size()));
    }

    /** Devuelve lo guardado (se borra de los datos antes de dar nada). */
    void devolver(Player p) {
        String ruta = ruta(p.getUniqueId());
        List<String> guardadas = hc.datos().getStringList(ruta);
        if (guardadas.isEmpty()) return;
        hc.datos().set(ruta, null);
        hc.guardarYa();
        for (String s : guardadas) {
            ItemStack it = Entregas.deTexto(s);
            if (it == null) {
                hc.plugin().bitacora().anotar("bendicion", "fallo", p.getName(), "objeto ilegible");
                continue;
            }
            Suelo.dar(hc.plugin(), p, it);
            ItemMeta meta = it.getItemMeta();
            Component nombre = meta != null && meta.hasDisplayName() && meta.displayName() != null
                    ? meta.displayName() : Component.translatable(it.getType().translationKey());
            p.sendMessage(ComandoCalamity.mensaje(Component.text("La Bendición de Dios te devuelve ")
                    .append(nombre.colorIfAbsent(Paleta.DETALLE))
                    .append(Component.text("."))));
            hc.plugin().bitacora().anotar("bendicion", "devuelve", p.getName(), descripcion(it));
        }
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.amethyst_block.chime", 1.0f, 1.2f);
    }

    /** Al reaparecer (Hardcore.onReaparecer ya le ha sacado de Calamity): dos ticks, con cuerpo y fuera. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onReaparecer(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!hc.datos().isSet(ruta(p.getUniqueId()))) return;
        hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(), () -> {
            if (p.isOnline() && !p.isDead()) hc.seguro("bendicion", () -> devolver(p));
        }, 2L);
    }

    /** Quien murio y se fue sin reaparecer, o huyo por el cable: al volver (despues de que Combate le saque). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntrar(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!hc.datos().isSet(ruta(p.getUniqueId()))) return;
        hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(), () -> {
            if (p.isOnline() && !p.isDead()) hc.seguro("bendicion", () -> devolver(p));
        }, 40L);
    }

    // ============================================================ aplicarla

    /** Si ese clic pondria la bendicion en algo que en Calamity no se salva nunca. */
    static boolean bloquear(ItemStack cursor, ItemStack objeto, Function<ItemStack, String> clase) {
        return esPergaminoAe(cursor) && objeto != null && !objeto.getType().isAir() && !bendecido(objeto)
                && clase.apply(objeto) != null;
    }

    /**
     * AE la aplica en un InventoryClickEvent que no respeta la cancelacion (lo mira en NORMAL y cancela el
     * suyo). Aqui se apunta antes (LOWEST) y, si AE la puso en algo que no se salva, se deshace en MONITOR:
     * el objeto vuelve sin bendecir y el pergamino al cursor, entero.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void antesDeAplicar(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack cursor = e.getCursor();
        ItemStack objeto = e.getCurrentItem();
        if (!esPergaminoAe(cursor)) return;
        String c = hc.valor("bendicion", () -> bloquear(cursor, objeto, this::clase) ? clase(objeto) : null, null);
        if (c == null) return;
        aplicando.put(p.getUniqueId(), new Aplicacion(e.getRawSlot(), cursor.clone(), objeto.clone(), c));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void trasAplicar(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Aplicacion a = aplicando.remove(p.getUniqueId());
        if (a == null || a.casilla() != e.getRawSlot() || !bendecido(e.getCurrentItem())) return;
        e.setCancelled(true);
        e.setCurrentItem(a.objeto());
        e.getView().setCursor(a.pergamino());
        p.sendMessage(ComandoCalamity.mensaje(switch (a.clase()) {
            case "prestado" -> "La Bendición de Dios no sirve en lo prestado del kit: se deshace al salir de Calamity.";
            case "reliquia", "esencia" -> "La Bendición de Dios no protege Reliquias ni Esencias.";
            default -> "La Bendición de Dios no se puede poner ahí.";
        }));
        hc.plugin().bitacora().anotar("bendicion", "rechaza", p.getName(), a.clase(), descripcion(a.objeto()));
    }

    // ================================================================== pruebas

    /** Lo que se puede probar sin servidor (lo corre tambien el arnes). */
    static List<String> autotestPuro() {
        Autotest.Hoja h = new Autotest.Hoja();
        h.igual("marca de AE en el objeto", "advancedenchantments:holywhitescrolled", BENDECIDO.toString());
        h.igual("marca de AE en el pergamino", "advancedenchantments:holywhitescroll", PERGAMINO_AE.toString());
        h.igual("linea de Ederus sin colores (formato de AE)", "☀ Bendición de Dios ☀",
                plano("{#FFD54A}☀ {#FFC870}Bendición de Dios {#FFD54A}☀"));
        h.igual("linea de serie de AE sin colores", LINEA_AE_DE_SERIE, plano("&e&l*&f&lHOLY&e&l* &f&lPROTECTED"));
        h.igual("tambien con § y hex de Minecraft", "☀ Bendición de Dios ☀",
                plano("§x§F§F§D§5§4§A☀ §x§F§F§C§8§7§0Bendición de Dios §x§F§F§D§5§4§A☀"));
        List<String> buscadas = List.of("{#FFD54A}☀ {#FFC870}Bendición de Dios {#FFD54A}☀", LINEA_AE_DE_SERIE);
        h.ok("la linea se reconoce aunque cambien mayusculas", esLinea("☀ BENDICIÓN DE DIOS ☀", buscadas));
        h.ok("una linea que solo la nombra no es la suya", !esLinea("Se gasta como la Bendición de Dios", buscadas));
        h.ok("una linea vacia nunca es la suya", !esLinea("", buscadas));

        h.igual("sin bendecir: nada", Destino.NADA, destino(false, true, null));
        h.igual("bendecido: se salva", Destino.SALVA, destino(true, true, null));
        for (String c : List.of("prestado", "eco", "pergamino", "reliquia", "esencia")) {
            h.igual("bendecido y " + c + ": pierde la bendicion", Destino.GASTA, destino(true, true, c));
        }
        h.igual("muerte.bendicion apagada: pierde la bendicion", Destino.GASTA, destino(true, false, null));

        List<String> lista = new ArrayList<>(List.of("espada", "casco", "espada"));
        h.igual("casar: el primero igual", 0, casar(lista, "espada", String::equals));
        h.igual("casar: ninguno", -1, casar(lista, "botas", String::equals));
        return h.lineas();
    }

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        for (String l : autotestPuro()) h.ok(l.startsWith("OK ") ? l.substring(3) : l, l.startsWith("OK "));

        List<String> lineas = List.of("{#FFD54A}☀ {#FFC870}Bendición de Dios {#FFD54A}☀", LINEA_AE_DE_SERIE);
        ItemStack espada = bendecida(Material.DIAMOND_SWORD);
        h.ok("objeto con la marca de AE: bendecido", bendecido(espada));
        h.ok("objeto sin marca: no", !bendecido(new ItemStack(Material.DIAMOND_SWORD)));
        ItemStack copia = espada.clone();
        quitar(copia, lineas);
        h.ok("gastarla quita la marca", !bendecido(copia));
        List<Component> lore = copia.getItemMeta().lore();
        h.igual("y su linea, dejando el resto del lore", List.of("Filo de prueba"),
                lore == null ? List.of() : lore.stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList());
        h.igual("gastada, la siguiente muerte ya no la salva", Destino.NADA, destino(bendecido(copia), true, null));

        ItemStack prestada = Kit.prestar(bendecida(Material.IRON_CHESTPLATE));
        h.igual("clase: lo prestado del kit", "prestado", clase(prestada));
        h.igual("clase: una espada normal no tiene", null, clase(espada));
        h.ok("bloquea la bendicion en lo prestado",
                bloquear(pergamino(), Kit.prestar(new ItemStack(Material.IRON_HELMET)), this::clase));
        h.ok("no bloquea en una espada normal", !bloquear(pergamino(), new ItemStack(Material.DIAMOND_SWORD), this::clase));
        h.ok("sin el pergamino en el cursor no bloquea nada",
                !bloquear(new ItemStack(Material.PAPER), Kit.prestar(new ItemStack(Material.IRON_HELMET)), this::clase));

        // Una muerte sintetica: inventario de 41 casillas (36 + armadura + mano izquierda) y lo que suelta.
        ItemStack[] inv = new ItemStack[41];
        inv[0] = espada.clone();
        inv[1] = new ItemStack(Material.BREAD, 12);
        inv[2] = prestada.clone();
        inv[38] = bendecida(Material.NETHERITE_CHESTPLATE);
        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack it : inv) if (it != null) drops.add(it.clone());
        drops.add(bendecida(Material.NETHERITE_HOE));   // la mesa del Engarzador: solo en lo que suelta
        Reparto r = repartir(inv, drops, true, this::clase, it -> quitar(it, lineas));
        h.igual("se salvan la espada, la pechera puesta y lo de la mesa", 3, r.salvados().size());
        h.igual("la espada sale de su casilla", null, inv[0]);
        h.igual("la pechera sale de la armadura (el Eco y el Salvoconducto ya no la ven)", null, inv[38]);
        h.ok("lo de la mesa se salva con casilla -1", r.salvados().stream().anyMatch(a -> a.casilla() == -1));
        h.ok("el pan se queda donde estaba", inv[1] != null && inv[1].getAmount() == 12);
        h.ok("lo prestado se queda, sin bendicion", inv[2] != null && !bendecido(inv[2]) && Kit.esPrestado(inv[2]));
        h.igual("lo gastado se apunta", 1, r.gastados().size());
        h.ok("en lo que suelta no queda nada bendecido", drops.stream().noneMatch(Bendicion::bendecido));
        h.igual("en lo que suelta siguen el pan y lo prestado", 2, drops.size());

        ItemStack[] inv2 = {bendecida(Material.DIAMOND_SWORD), null};
        Reparto r2 = repartir(inv2, new ArrayList<>(), true, this::clase, it -> quitar(it, lineas));
        h.igual("con keepInventory (sin drops) manda el inventario", 1, r2.salvados().size());
        ItemStack[] inv3 = {bendecida(Material.DIAMOND_SWORD)};
        Reparto r3 = repartir(inv3, null, false, this::clase, it -> quitar(it, lineas));
        h.ok("muerte.bendicion apagada: nada se salva y pierde la bendicion",
                r3.salvados().isEmpty() && inv3[0] != null && !bendecido(inv3[0]));

        ItemStack ida = r.salvados().get(0).objeto().clone();
        quitar(ida, lineas);
        ItemStack vuelta = Entregas.deTexto(Entregas.aTexto(ida));
        h.ok("lo guardado vuelve igual de los datos", vuelta != null && vuelta.isSimilar(ida));
        h.ok("y vuelve sin la bendicion", vuelta != null && !bendecido(vuelta));
        h.ok("la prueba no toca hardcore-datos.yml", !hc.datos().isSet(ruta(Autotest.sintetico(1))));
        h.ok("la config de AE: linea leida o de serie", !lineas().isEmpty());
        conAe(h);
        return h.lineas();
    }

    /**
     * Con AE cargado, contra su propia API (por reflexion: AE no es dependencia de Calamity): que reconozca como
     * suya la marca de aqui, que su forma de gastarla deje lo mismo que quitar() y que su pergamino de verdad se
     * reconozca. La linea es la de su config, en plano (AE compara sin colores).
     */
    private void conAe(Autotest.Hoja h) {
        Plugin ae = Bukkit.getPluginManager().getPlugin("AdvancedEnchantments");
        if (ae == null || !ae.isEnabled()) {
            h.ok("sin AE en este servidor: lo de AE no se prueba aqui", true);
            return;
        }
        try {
            ClassLoader cl = ae.getClass().getClassLoader();
            Class<?> api = Class.forName("net.advancedplugins.ae.api.AEAPI", true, cl);
            List<String> lineas = lineas();
            String linea = plano(lineas.get(0));
            h.ok("AE reconoce como suya la marca de aqui (AEAPI.hasHolyWhiteScroll)",
                    (Boolean) api.getMethod("hasHolyWhiteScroll", ItemStack.class).invoke(null, bendecida(Material.DIAMOND_SWORD, linea)));
            ItemStack porAe = (ItemStack) api.getMethod("removeHolyWhiteScroll", ItemStack.class)
                    .invoke(null, bendecida(Material.DIAMOND_SWORD, linea));
            ItemStack porAqui = bendecida(Material.DIAMOND_SWORD, linea);
            quitar(porAqui, lineas);
            h.ok("gastada por AE, aqui ya no cuenta como bendecida", !bendecido(porAe));
            h.igual("gastarla aqui deja el mismo lore que AE", lorePlano(porAe), lorePlano(porAqui));
            Class<?> hws = Class.forName("net.advancedplugins.ae.items.HolyWhiteScroll", true, cl);
            ItemStack real = (ItemStack) hws.getMethod("get", int.class).invoke(null, 1);
            Component nombre = real.getItemMeta() == null ? null : real.getItemMeta().displayName();
            h.ok("el pergamino de verdad de AE se reconoce ("
                    + (nombre == null ? real.getType().name() : PlainTextComponentSerializer.plainText().serialize(nombre)) + ")",
                    esPergaminoAe(real));
            h.ok("y no cuenta como objeto bendecido", !bendecido(real));
        } catch (Throwable t) {
            h.ok("AE: su API no responde como en 9.24.13: " + t, false);
        }
    }

    private static List<String> lorePlano(ItemStack it) {
        List<Component> lore = it == null || it.getItemMeta() == null ? null : it.getItemMeta().lore();
        return lore == null ? List.of() : lore.stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList();
    }

    /** Un objeto con la bendicion puesta como la pone AE (marca STRING "true" y su linea al final del lore). */
    private static ItemStack bendecida(Material m) {
        return bendecida(m, null);
    }

    private static ItemStack bendecida(Material m, String lineaPlana) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(BENDECIDO, PersistentDataType.STRING, "true");
        meta.lore(List.of(Component.text("Filo de prueba"), lineaPlana != null ? Component.text(lineaPlana)
                : net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                        .deserialize("§x§F§F§D§5§4§A☀ §x§F§F§C§8§7§0Bendición de Dios §x§F§F§D§5§4§A☀")));
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack pergamino() {
        ItemStack it = new ItemStack(Material.MOJANG_BANNER_PATTERN);
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(PERGAMINO_AE, PersistentDataType.STRING, "true");
        it.setItemMeta(meta);
        return it;
    }
}
