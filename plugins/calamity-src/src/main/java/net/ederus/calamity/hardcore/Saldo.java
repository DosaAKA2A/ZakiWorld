package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M31 · El saldo de Esencias fuera de Calamity: esencias.<uuid> (long) en hardcore-datos.yml.
 *
 * Por que un saldo y no el objeto (DIS sec. 0.3, PLAN sec. 2): una Esencia fisica fuera se
 * vende en /ah, entra en el cofre de otro y deja que el baltop compre el Manto sin pisar
 * Calamity. Dentro sigue siendo objeto (se pierde al morir, que es la gracia); fuera es un
 * numero que solo el Altar sabe gastar. Al extraer pasan al saldo (la Tasacion llama a
 * depositarFisicas) y, si aparece una fisica fuera por cualquier otra via (Esencias viejas,
 * salida por admin, /lw hardcore esencia), se deposita sola al entrar al servidor, al cambiar
 * de mundo o al abrir un inventario. No se destruye nada: se convierte.
 *
 * Segunda barrera (DIS M2, "Esencias vendibles"): la Esencia es una lagrima de ghast y la
 * lagrima tiene receta de pocion. Con la marca, ni se elabora ni entra en un alambique,
 * este donde este.
 *
 * Cada movimiento va a la Bitacora: saldo | <jugador> | +n|-n | <motivo> | <saldo nuevo>.
 * Es la respuesta a "me han quitado las Esencias".
 */
final class Saldo implements Listener {

    private final Hardcore hc;
    /** Solo en las pruebas: un yml en memoria en vez de hardcore-datos.yml. */
    private final YamlConfiguration prueba;

    Saldo(Hardcore hc) {
        this.hc = hc;
        this.prueba = null;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        PlaceholdersLethal.registrar("esencias",
                (jugador, resto) -> jugador == null ? "" : String.valueOf(de(jugador.getUniqueId())));
        PlaceholdersLethal.registrar("esencias_encima", (jugador, resto) -> encimaTexto(jugador));
        Autotest.registrar("saldo", this::autotest);
    }

    /** Para los autotest: sin listener, sin Bitacora y sin tocar los datos reales. */
    Saldo(YamlConfiguration memoria) {
        this.hc = null;
        this.prueba = memoria;
    }

    private YamlConfiguration datos() {
        return prueba != null ? prueba : hc.datos();
    }

    private static String ruta(UUID jugador) {
        return "esencias." + jugador;
    }

    long de(UUID jugador) {
        if (jugador == null) return 0;
        return Math.max(0, datos().getLong(ruta(jugador), 0));
    }

    /**
     * Todos los saldos por encima de 0, para el top de Esencias (1.3.2): Rankings los copia en su
     * tarea. Solo en el hilo principal, que lee hardcore-datos.yml.
     */
    Map<UUID, Long> todos() {
        Map<UUID, Long> out = new HashMap<>();
        ConfigurationSection s = datos().getConfigurationSection("esencias");
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            UUID u;
            try {
                u = UUID.fromString(k);
            } catch (IllegalArgumentException ignorado) {
                continue;
            }
            long n = s.getLong(k, 0);
            if (n > 0) out.put(u, n);
        }
        return out;
    }

    /** Suma n (> 0) al saldo. Se guarda al momento: es dinero y una caida no puede perderlo. */
    void sumar(UUID jugador, long n, String motivo) {
        if (jugador == null || n <= 0) return;
        long nuevo = de(jugador) + n;
        datos().set(ruta(jugador), nuevo);
        apuntar(jugador, "+" + n, motivo, nuevo);
    }

    /** False si no le llega: nunca deja el saldo en negativo. */
    boolean restar(UUID jugador, long n, String motivo) {
        if (jugador == null || n < 0) return false;
        if (n == 0) return true;
        long hay = de(jugador);
        if (hay < n) return false;
        long nuevo = hay - n;
        datos().set(ruta(jugador), nuevo);
        apuntar(jugador, "-" + n, motivo, nuevo);
        return true;
    }

    private void apuntar(UUID jugador, String cambio, String motivo, long nuevo) {
        if (hc == null) return;
        hc.guardarYa();
        hc.plugin().bitacora().anotar("saldo", nombre(jugador), cambio, motivo == null ? "-" : motivo,
                String.valueOf(nuevo));
    }

    /** El nombre para la Bitacora; el UUID si nunca entro al servidor. */
    static String nombre(UUID jugador) {
        OfflinePlayer o = Bukkit.getOfflinePlayer(jugador);
        String n = o.getName();
        return n == null ? jugador.toString() : n;
    }

    // ------------------------------------------------------------- las fisicas

    /** Esencias fisicas que lleva encima (inventario entero, mano secundaria incluida, y cursor). */
    int encima(Player p) {
        if (p == null || hc == null) return 0;
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (hc.items().esEsencia(it)) n += it.getAmount();
        }
        ItemStack cursor = p.getItemOnCursor();
        if (hc.items().esEsencia(cursor)) n += cursor.getAmount();
        return n;
    }

    /**
     * Pasa al saldo las Esencias fisicas que lleve encima y las quita. Devuelve cuantas.
     * No avisa al jugador: lo llaman la Tasacion (tiene su propio mensaje), el boton
     * Depositar del Altar (P-M09) y el deposito solo de fuera (P-M12), y cada uno dice lo suyo.
     */
    int depositarFisicas(Player p) {
        if (p == null || hc == null) return 0;
        PlayerInventory inv = p.getInventory();
        int n = 0;
        ItemStack[] cont = inv.getContents();
        for (int i = 0; i < cont.length; i++) {
            if (hc.items().esEsencia(cont[i])) {
                n += cont[i].getAmount();
                inv.setItem(i, null);
            }
        }
        ItemStack cursor = p.getItemOnCursor();
        if (hc.items().esEsencia(cursor)) {
            n += cursor.getAmount();
            p.setItemOnCursor(null);
        }
        if (n <= 0) return 0;
        sumar(p.getUniqueId(), n, "deposito");
        hc.plugin().bitacora().anotar("esencia", "deposito", p.getName(), String.valueOf(n));
        return n;
    }

    /** P-M09 (boton Depositar del Altar): "Has depositado <n> Esencias. Saldo: <s>." */
    Component avisoDeposito(Player p, int n) {
        return ComandoCalamity.mensaje(Component.text("Has depositado ")
                .append(Component.text(String.valueOf(n), Paleta.CIFRA))
                .append(Component.text(" Esencias. Saldo: "))
                .append(Component.text(String.valueOf(de(p.getUniqueId())), Paleta.CIFRA))
                .append(Component.text(".")));
    }

    /** P-M08: "Saldo: <n> Esencias." */
    Component avisoSaldo(UUID jugador) {
        return ComandoCalamity.mensaje(Component.text("Saldo: ")
                .append(Component.text(String.valueOf(de(jugador)), Paleta.CIFRA))
                .append(Component.text(" Esencias.")));
    }

    private String encimaTexto(OfflinePlayer jugador) {
        // PlaceholderAPI puede preguntar desde otro hilo: solo se lee, y solo de un conectado.
        Player p = jugador == null ? null : jugador.getPlayer();
        return p == null ? "0" : String.valueOf(encima(p));
    }

    /** Fuera de Calamity y con el saldo encendido, lo fisico se convierte en saldo. */
    private void depositoSolo(Player p) {
        if (p == null || !p.isOnline() || hc.esHardcore(p)) return;
        if (!hc.cfg().getBoolean("esencias.saldo", true)) return;
        int n = depositarFisicas(p);
        if (n > 0) {
            p.sendMessage(ComandoCalamity.mensaje(
                    Component.text("Fuera de Calamity las Esencias no pesan: van a tu saldo (")
                            .append(Component.text("+" + n, Paleta.CIFRA))
                            .append(Component.text(")."))));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntrar(PlayerJoinEvent e) {
        hc.seguro("saldo", () -> depositoSolo(e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCambiarMundo(PlayerChangedWorldEvent e) {
        hc.seguro("saldo", () -> depositoSolo(e.getPlayer()));
    }

    /*
     * Al abrir cualquier inventario fuera: cubre el cofre, la mesa, el alambique y los menus
     * de tiendas y subastas justo antes de que la Esencia pueda entrar en ellos.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAbrir(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p) hc.seguro("saldo", () -> depositoSolo(p));
    }

    /** Ni receta con una Esencia (la lagrima tiene recetas), dentro o fuera. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepararReceta(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (hc.items().esEsencia(it)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    /** Ni alambique: el ingrediente con la marca no elabora. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onElaborar(BrewEvent e) {
        if (hc.items().esEsencia(e.getContents().getIngredient())) e.setCancelled(true);
    }

    /** Y no se deja poner en el alambique (clic, mayusculas desde abajo o tecla numerica). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onColocar(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getType() != InventoryType.BREWING) return;
        ItemStack mueve = e.isShiftClick() ? e.getCurrentItem() : e.getCursor();
        if (e.getClick().isKeyboardClick() && e.getHotbarButton() >= 0) {
            mueve = e.getWhoClicked().getInventory().getItem(e.getHotbarButton());
        }
        if (hc.items().esEsencia(mueve)) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ pruebas

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        probarNucleo(h);
        h.ok("la prueba no toca hardcore-datos.yml", !hc.datos().isSet("esencias." + Autotest.sintetico(11)));
        h.igual("placeholder esencias sin jugador", "", PlaceholdersLethal.resolver(null, "esencias"));
        h.igual("placeholder esencias_encima sin jugador", "0", PlaceholdersLethal.resolver(null, "esencias_encima"));
        return h.lineas();
    }

    static void probarNucleo(Autotest.Hoja h) {
        YamlConfiguration memoria = new YamlConfiguration();
        Saldo s = new Saldo(memoria);
        UUID u = Autotest.sintetico(11);
        h.igual("saldo de alguien nuevo", 0L, s.de(u));
        s.sumar(u, 10, "prueba");
        h.igual("sumar 10", 10L, s.de(u));
        s.sumar(u, -5, "prueba");
        h.igual("sumar negativo no hace nada", 10L, s.de(u));
        h.ok("restar 4 con 10", s.restar(u, 4, "prueba"));
        h.igual("quedan 6", 6L, s.de(u));
        h.ok("restar 7 con 6 falla", !s.restar(u, 7, "prueba"));
        h.igual("y no descuenta", 6L, s.de(u));
        h.ok("restar 0 siempre vale", s.restar(u, 0, "prueba"));
        h.ok("restar negativo no vale", !s.restar(u, -1, "prueba"));
        h.igual("se guarda en esencias.<uuid>", 6L, memoria.getLong("esencias." + u));
        UUID vacio = Autotest.sintetico(12);
        s.sumar(vacio, 3, "prueba");
        s.restar(vacio, 3, "prueba");
        memoria.set("esencias.no-es-un-uuid", 99);
        h.igual("todos: los saldos > 0, sin claves raras", Map.of(u, 6L), s.todos());
    }

    void parar() {
    }
}
