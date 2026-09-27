package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.MobCoins;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M31 · /lw hardcore dar: la entrega unica de premios de Calamity (DIS M31, PLAN cabecera).
 *
 * Todo lo que Calamity da FUERA (Altar, Caja del Caos, Mercado, hitos, rankings, contratos)
 * pasa por aqui, por comando o desde otro modulo, y aqui se hace lo que no puede olvidarse:
 *   - el objeto sale LIGADO a su dueno (lethal_world:ligado = uuid, M30): no se vende ni se pasa;
 *   - los topes se respetan: 4 Llaves del Caos por semana y jugador (lo que sobra, 10 E cada
 *     una) y 2 libros LEGENDARY al mes en todo el servidor (el resto, 20 E);
 *   - Esencias y creditos van al saldo aunque el jugador no este; los objetos a un
 *     desconectado, o a quien esta dentro de Calamity (moriria con ellos), esperan en
 *     premios-pendientes y se entregan al entrar o al salir de Calamity;
 *   - todo va a la Bitacora ("entrega | ...", "llave | ...", "libro | ...") y a la telemetria
 *     ("recompensa", "caja-libro").
 *
 * Los objetos de MMOItems (Tintura, Gema, Ascua y las piezas de la Forja) se crean con
 * PuenteMmo.crear("TIPO.ID") con los ids de entregas.mmo y forja.piezas. Sin MMOItems no se
 * entrega: P-M07 a quien lo pidio y "entrega | fallo", y dar() devuelve false para que el
 * que cobro (el Altar) lo devuelva.
 */
final class Entregas implements Listener {

    /** Nombres que se aceptan en un comando de consola: Java y Bedrock (Geyser antepone un punto). */
    static final Pattern NOMBRE_VALIDO = Pattern.compile("[A-Za-z0-9_.]{1,20}");

    private static final TextColor VERDE_PALIDO = TextColor.color(0x9FD6A0);
    private static final TextColor AMBAR = TextColor.color(0xE8A33D);
    private static final TextColor PAPEL = TextColor.color(0xE8D9B0);

    /** Los objetos que entiende dar (ademas de credito:<tipo>, credito-caja:<tipo> y forja:<pieza>). */
    static final List<String> OBJETOS = List.of("esencia", "frasco", "frasco-1", "cristal", "tintura", "gema", "ascua",
            "talisman", "grabado", "salvoconducto", "libro", "llave", "llave-hito");

    private final Hardcore hc;
    private final Set<BukkitTask> tareas = new HashSet<>();

    Entregas(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Subcomandos.lw().registrar("dar", "dar <objeto> <jugador> [n] [origen]: entrega un premio ligado (M31)",
                "ederus.mundos", this::comandoDar, this::tabDar);
        Subcomandos.lw().registrar("saldo", "saldo <jugador> [+n|-n]: ver o ajustar el saldo de Esencias",
                "ederus.mundos", this::comandoSaldo, args -> args.length == 2 ? nombresConectados() : List.of());
        Subcomandos.lw().registrar("creditos", "creditos <jugador> [tipo +n|-n]: ver o ajustar creditos",
                "ederus.mundos", this::comandoCreditos, args -> switch (args.length) {
                    case 2 -> nombresConectados();
                    case 3 -> List.of("sello:", Creditos.ERRANTE, "fragmento", "marca");
                    case 4 -> List.of("+1", "-1");
                    default -> List.of();
                });
        Subcomandos.lw().registrar("mc", "mc <jugador> <n>: saldo de MobCoins de prueba (monedero.modo: prueba)",
                "ederus.mundos", this::comandoMc, args -> args.length == 2 ? nombresConectados() : List.of());
        Subcomandos.calamity().registrar("saldo", "tu saldo de Esencias y tus créditos", "lethalworld.calamity",
                this::comandoMiSaldo, null);
        Autotest.registrar("entregas", this::autotest);
    }

    // ------------------------------------------------------------------ dar

    /**
     * Entrega n de un objeto a un jugador. True si se entrego (o quedo pendiente, o se
     * sustituyo por Esencias segun las reglas); false si no se pudo y no se dio nada.
     *
     * @param quien  a quien se le cuenta un fallo (P-M07); null = a la consola
     * @param origen de donde sale (dar:consola, altar:frasco, caja, hito:h48...). Si empieza
     *               por "caja", los creditos cuentan como de caja (exigen horas activas)
     */
    boolean dar(CommandSender quien, String objeto, OfflinePlayer a, int n, String origen) {
        String o = objeto == null ? "" : objeto.trim().toLowerCase(Locale.ROOT);
        String org = origen == null || origen.isBlank() ? "dar" : origen;
        if (a == null || n <= 0 || o.isEmpty()) {
            fallo(quien, o, a, "faltan datos", org);
            return false;
        }
        UUID u = a.getUniqueId();

        if (o.equals("esencia") || o.equals("esencias")) {
            Saldo s = hc.saldo();
            if (s == null) {
                fallo(quien, o, a, "sin saldo", org);
                return false;
            }
            s.sumar(u, n, org);
            recompensa(a, "esencia", n, org, false);
            return true;
        }
        if (o.startsWith("credito:") || o.startsWith("credito-caja:")) {
            String tipo = o.substring(o.indexOf(':') + 1);
            Creditos c = hc.creditos();
            if (tipo.isEmpty() || c == null) {
                fallo(quien, o, a, tipo.isEmpty() ? "credito sin tipo" : "sin creditos", org);
                return false;
            }
            boolean deCaja = o.startsWith("credito-caja:") || org.toLowerCase(Locale.ROOT).startsWith("caja");
            c.sumar(u, tipo, n, org, deCaja);
            recompensa(a, "credito:" + tipo, n, org, false);
            return true;
        }
        switch (o) {
            case "llave", "llave-hito" -> {
                int pedidas = n;
                int dadas = llave(a, n, org, o.equals("llave"));
                if (dadas < 0) {
                    fallo(quien, o, a, "el comando de la llave falla", org);
                    return false;
                }
                recompensa(a, o, pedidas, org, false);
                return true;
            }
            case "libro" -> {
                for (int i = 0; i < n; i++) {
                    if (!libro(a, org)) {
                        fallo(quien, o, a, "el comando del libro falla", org);
                        return false;
                    }
                }
                recompensa(a, o, n, org, false);
                return true;
            }
            case "salvoconducto" -> {
                if (!hc.cfg().getBoolean("salvoconducto.activo", false)) {
                    // Apagado hasta el visto bueno de Dosa (DIS sec. 0.3): lo que lo daria paga Esencias.
                    int e = hc.cfg().getInt("salvoconducto.sustituto-esencias", 10) * n;
                    Saldo s = hc.saldo();
                    if (s == null) {
                        fallo(quien, o, a, "sin saldo", org);
                        return false;
                    }
                    s.sumar(u, e, "salvoconducto-sustituto:" + org);
                    recompensa(a, "salvoconducto-sustituto", n, org, false);
                    return true;
                }
            }
            default -> {
            }
        }

        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ItemStack it = crear(o);
            if (it == null) {
                String motivo = motivoSinObjeto(o);
                fallo(quien, o, a, motivo, org);
                return false;
            }
            items.add(ligar(it, u));
        }
        String donde = entregarObjetos(a, o, items, org);
        hc.plugin().bitacora().anotar("entrega", "ok", o, nombre(a), String.valueOf(n), org, donde);
        recompensa(a, o, n, org, true);
        return true;
    }

    /** El objeto de una entrega (sin ligar), o null si no existe o MMOItems no lo sabe hacer. */
    ItemStack crear(String objeto) {
        String o = objeto.toLowerCase(Locale.ROOT);
        return switch (o) {
            case "frasco" -> hc.items().frasco(hc.cfg().getInt("frasco.usos", 3));
            case "frasco-1" -> hc.items().frasco(1);
            case "cristal" -> hc.items().cristal();
            case "talisman" -> talisman();
            case "grabado" -> grabado();
            case "salvoconducto" -> salvoconducto();
            default -> {
                String id = idMmo(o);
                yield id == null ? null : PuenteMmo.crear(id);
            }
        };
    }

    /** "TIPO.ID" de MMOItems para tintura/gema/ascua (entregas.mmo) o una pieza (forja.piezas). */
    String idMmo(String objeto) {
        String o = objeto.startsWith("forja:") ? objeto.substring(6) : objeto;
        String id = hc.cfg().getString("entregas.mmo." + o);
        if (id == null || id.isBlank()) id = hc.cfg().getString("forja.piezas." + o);
        if (id == null || id.isBlank()) id = MMO_DEFECTO.get(o);
        return id == null || id.isBlank() ? null : id;
    }

    /** Los ids de DIS sec. 4, por si la config del servidor aun no los tiene. */
    private static final Map<String, String> MMO_DEFECTO = Map.ofEntries(
            Map.entry("tintura", "CONSUMABLE.TINTURA_DE_CENIZA"),
            Map.entry("gema", "GEM_STONE.GEMA_DE_CALAMIDAD"),
            Map.entry("ascua", "CONSUMABLE.ASCUA_DE_CALAMIDAD"),
            Map.entry("yelmo", "ARMOR.YELMO_DE_CALAMIDAD"),
            Map.entry("coraza", "ARMOR.CORAZA_DE_CALAMIDAD"),
            Map.entry("grebas", "ARMOR.GREBAS_DE_CALAMIDAD"),
            Map.entry("soleretas", "ARMOR.SOLERETAS_DE_CALAMIDAD"),
            Map.entry("hacha", "AXE.HACHA_DEL_HERALDO"),
            Map.entry("mascara", "ARMOR.MASCARA_DEL_ECO"),
            Map.entry("filo", "SWORD.FILO_DEL_ECO"),
            Map.entry("guadana", "SWORD.GUADANA_DE_LA_PARCA"));

    private String motivoSinObjeto(String o) {
        String id = idMmo(o);
        if (id == null) return "objeto desconocido";
        if (!PuenteMmo.disponible()) return "sin MMOItems";
        return "MMOItems no tiene " + id;
    }

    /**
     * Mete los objetos donde tocan y dice donde: "inventario", "suelo" (no cabia, P-M06) o
     * "pendiente" (desconectado o dentro de Calamity).
     */
    private String entregarObjetos(OfflinePlayer a, String objeto, List<ItemStack> items, String origen) {
        Player p = a.getPlayer();
        if (p == null || !p.isOnline() || hc.esHardcore(p)) {
            for (ItemStack it : items) guardarPendiente(a.getUniqueId(), "item", aTexto(it), objeto, origen);
            hc.guardarYa();
            if (p != null && p.isOnline()) {
                p.sendMessage(ComandoCalamity.mensaje(Component.text("Te espera fuera: ")
                        .append(Component.text(objeto + (items.size() > 1 ? " x" + items.size() : ""), Paleta.DETALLE))
                        .append(Component.text("."))));
            }
            return "pendiente";
        }
        boolean suelo = false;
        for (ItemStack it : items) suelo |= Suelo.dar(hc.plugin(), p, it);
        if (suelo) p.sendMessage(ComandoCalamity.mensaje("No te cabía: lo tienes a tus pies."));
        return suelo ? "suelo" : "inventario";
    }

    private void fallo(CommandSender quien, String objeto, OfflinePlayer a, String motivo, String origen) {
        String nombre = a == null ? "?" : nombre(a);
        Component msg = ComandoCalamity.mensaje(Component.text("No se pudo entregar ", Paleta.AVISO)
                .append(Component.text(objeto.isEmpty() ? "?" : objeto, Paleta.DETALLE))
                .append(Component.text(" a ", Paleta.AVISO))
                .append(Component.text(nombre, Paleta.DETALLE))
                .append(Component.text(": " + motivo + ".", Paleta.AVISO)));
        (quien == null ? Bukkit.getConsoleSender() : quien).sendMessage(msg);
        hc.plugin().bitacora().anotar("entrega", "fallo", objeto.isEmpty() ? "?" : objeto, motivo, nombre, origen);
    }

    private void recompensa(OfflinePlayer a, String objeto, int n, String origen, boolean ligado) {
        Telemetria tel = hc.telemetria();
        if (tel == null) return;
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("origen", origen);
        campos.put("objeto", objeto);
        campos.put("cantidad", n);
        campos.put("ligado", ligado ? "si" : "no");
        hc.seguro("telemetria", () -> tel.suceso("recompensa", a, campos));
    }

    // ------------------------------------------------------------------ llaves

    /**
     * Llaves del Caos por el comando de llave-caos.comando. Con tope (llave), como mucho
     * llave-caos.tope-semana (4) por semana y jugador sumando todas las fuentes, y cada una
     * que sobra paga llave-caos.sobrante-esencias (10) al saldo. Sin tope (llave-hito), las
     * que se pidan y sin contar (PLAN sec. 3.5: hitos y cajas legendary/cajaepica).
     *
     * @return llaves entregadas de verdad (0 si el tope estaba lleno), o -1 si el comando fallo
     */
    int llave(OfflinePlayer p, int n, String origen, boolean conTope) {
        if (p == null || n <= 0) return 0;
        UUID u = p.getUniqueId();
        String nombre = nombre(p);
        Calendario cal = hc.calendario() != null ? hc.calendario() : new Calendario(hc);
        String ruta = "llaves-semana." + cal.semana() + "." + u;
        int tope = hc.cfg().getInt("llave-caos.tope-semana", 4);
        int usadas = hc.datos().getInt(ruta, 0);
        int[] reparto = repartoLlaves(usadas, n, tope, conTope);
        int dar = reparto[0], sobran = reparto[1];
        if (dar > 0) {
            String plantilla = hc.cfg().getString("llave-caos.comando", "crates key give %jugador% caos %n%");
            if (!comando(plantilla, nombre, dar)) {
                hc.plugin().bitacora().anotar("llave", nombre, "fallo", "comando", "pedidas " + n, origen);
                return -1;
            }
            if (conTope) hc.datos().set(ruta, usadas + dar);
        }
        if (sobran > 0) {
            int e = sobran * hc.cfg().getInt("llave-caos.sobrante-esencias", 10);
            Saldo s = hc.saldo();
            if (s != null) s.sumar(u, e, "llave-sobrante:" + origen);
        }
        hc.guardarYa();
        String estado = !conTope ? "sin-tope" : sobran > 0 ? "tasa:tope" : "tasa:ok";
        hc.plugin().bitacora().anotar("llave", nombre, String.valueOf(dar), estado, "pedidas " + n,
                "sobran " + sobran, conTope ? "semana " + (usadas + dar) + "/" + tope : "fuera del tope", origen);
        return dar;
    }

    /** Llaves del Caos que aun le caben esta semana (el Altar mira esto ANTES de cobrar). */
    int llavesLibres(UUID u) {
        Calendario cal = hc.calendario() != null ? hc.calendario() : new Calendario(hc);
        int tope = hc.cfg().getInt("llave-caos.tope-semana", 4);
        return Math.max(0, tope - hc.datos().getInt("llaves-semana." + cal.semana() + "." + u, 0));
    }

    /** {llaves que se dan, llaves que sobran}. Sin tope se dan todas. */
    static int[] repartoLlaves(int usadas, int n, int tope, boolean conTope) {
        if (n <= 0) return new int[]{0, 0};
        if (!conTope) return new int[]{n, 0};
        int dar = Math.max(0, Math.min(n, tope - usadas));
        return new int[]{dar, n - dar};
    }

    // ------------------------------------------------------------------ libros

    /**
     * Un libro LEGENDARY de la Caja del Caos (PLAN sec. 7.1): como mucho caja.libro-tope-mes (2)
     * al mes en TODO el servidor; pasado, caja.libro-sustituto-esencias (20) al saldo.
     * False solo si el comando falla.
     */
    private boolean libro(OfflinePlayer a, String origen) {
        Calendario cal = hc.calendario() != null ? hc.calendario() : new Calendario(hc);
        String ruta = "libros-caja." + cal.mes();
        int tope = hc.cfg().getInt("caja.libro-tope-mes", 2);
        int total = hc.datos().getInt(ruta, 0);
        String nombre = nombre(a);
        String resultado;
        if (libroCabe(total, tope)) {
            String plantilla = hc.cfg().getString("caja.libro-comando", "ae giverandombook %jugador% LEGENDARY");
            if (!comando(plantilla, nombre, 1)) {
                hc.plugin().bitacora().anotar("libro", "fallo", nombre, origen);
                return false;
            }
            total++;
            hc.datos().set(ruta, total);
            hc.guardarYa();
            resultado = "entregado";
        } else {
            Saldo s = hc.saldo();
            if (s != null) s.sumar(a.getUniqueId(), hc.cfg().getInt("caja.libro-sustituto-esencias", 20), "libro-sustituto:" + origen);
            resultado = "sustituido";
        }
        hc.plugin().bitacora().anotar("libro", resultado, nombre, "mes " + total + "/" + tope, origen);
        Telemetria tel = hc.telemetria();
        if (tel != null) {
            Map<String, Object> campos = new LinkedHashMap<>();
            campos.put("resultado", resultado);
            campos.put("mes_total", total);
            hc.seguro("telemetria", () -> tel.suceso("caja-libro", a, campos));
        }
        return true;
    }

    static boolean libroCabe(int totalMes, int tope) {
        return totalMes < tope;
    }

    /**
     * Un comando de consola de la config con %jugador% y %n%. El nombre se valida antes: de
     * un nombre raro en un comando de consola puede salir otro comando.
     */
    private boolean comando(String plantilla, String jugador, int n) {
        if (plantilla == null || plantilla.isBlank() || jugador == null || !NOMBRE_VALIDO.matcher(jugador).matches()) {
            return false;
        }
        String cmd = plantilla.replace("%jugador%", jugador).replace("%n%", String.valueOf(n)).trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        try {
            return hc.plugin().getServer().dispatchCommand(hc.plugin().getServer().getConsoleSender(), cmd);
        } catch (Throwable t) {
            hc.plugin().getLogger().warning("[Calamity] Fallo el comando de entrega \"" + cmd + "\": " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------ ligar

    /**
     * Liga un objeto a su dueno (M30). La marca la escribe Ligado.ligar, que es quien la hace
     * cumplir (un solo sitio y un solo formato); aqui se anade la linea del lore, una vez,
     * para que se sepa por que no se deja vender.
     */
    ItemStack ligar(ItemStack item, UUID dueno) {
        if (item == null || item.getType().isAir() || dueno == null || item.getItemMeta() == null) return item;
        boolean ya = Ligado.duenoDe(item) != null;
        Ligado.ligar(item, dueno);
        if (!ya) {
            ItemMeta meta = item.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.text("Ligado a " + nombreDe(dueno) + ".", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ------------------------------------------------ objetos PDC de LethalWorld (M34)

    /**
     * Talisman de Vigilia (PLAN sec. 5.3): CLOCK con brillo y lethal_world:talisman. Lo que hace
     * (+3 de vida y -20 % de drenaje) lo aplica ObjetosCalamity (WP3), que lo reconoce por la marca.
     */
    ItemStack talisman() {
        return objeto(Material.CLOCK, "Talismán de Vigilia", VERDE_PALIDO,
                List.of("No te deja dormir. Esa es la idea.", "Allí dentro, el que cierra los ojos no los vuelve a abrir."),
                List.of("+" + hc.cfg().getInt("talisman.vida", 3) + " de vida máxima.",
                        "La cordura baja un " + Math.round((1 - hc.cfg().getDouble("talisman.drenaje", 0.80)) * 100)
                                + " % más despacio dentro.",
                        "Cuenta uno aunque lleves varios."),
                Marcas.TALISMAN, null);
    }

    /**
     * Grabado de Calamidad: FLINT con brillo y lethal_world:grabado = uuid propio (el objeto
     * grabado recibira el mismo uuid, DIS sec. 8.5). Se aplica en la Forja (WP3).
     */
    ItemStack grabado() {
        return objeto(Material.FLINT, "Grabado de Calamidad", AMBAR,
                List.of("Grabado con la uña de algo que no quería morir.", "El acero recuerda. Y aprieta un poco más."),
                List.of("+1 nivel sobre el tope vanilla a un encantamiento.", "Solo equipo sin MMOItems. Se aplica en la Forja."),
                Marcas.GRABADO, UUID.randomUUID().toString());
    }

    /** Salvoconducto del Insomne: PAPER con brillo y lethal_world:salvoconducto (M34, apagado de serie). */
    ItemStack salvoconducto() {
        return objeto(Material.PAPER, "Salvoconducto del Insomne", PAPEL,
                List.of("Firmado por alguien que tampoco dormía.", "Te devuelve una cosa. Elige bien lo que llevas."),
                List.of("Al morir dentro, conservas una pieza.", "Clic derecho con él en la mano para elegirla."),
                Marcas.SALVOCONDUCTO, null);
    }

    private static ItemStack objeto(Material m, String nombre, TextColor color, List<String> historia, List<String> efecto,
                                    org.bukkit.NamespacedKey marca, String valor) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(Component.text(nombre, color).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String l : historia) lore.add(Component.text(l, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        for (String l : efecto) lore.add(Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Botín de Calamity", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        if (valor == null) meta.getPersistentDataContainer().set(marca, PersistentDataType.BYTE, (byte) 1);
        else meta.getPersistentDataContainer().set(marca, PersistentDataType.STRING, valor);
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------ pendientes

    /** MobCoins que la Aduana no pudo pagar a un desconectado: se pagan al entrar. */
    void pendienteMc(UUID u, long mc, String origen) {
        if (u == null || mc <= 0) return;
        guardarPendiente(u, "mc", String.valueOf(mc), "mobcoins", origen);
        hc.guardarYa();
        hc.plugin().bitacora().anotar("entrega", "pendiente", "mobcoins", nombreDe(u), String.valueOf(mc), origen);
    }

    private void guardarPendiente(UUID u, String tipo, String dato, String objeto, String origen) {
        String ruta = "premios-pendientes." + u;
        List<Map<?, ?>> lista = new ArrayList<>(hc.datos().getMapList(ruta));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", tipo);
        m.put("dato", dato);
        m.put("objeto", objeto);
        m.put("origen", origen == null ? "" : origen);
        m.put("t", System.currentTimeMillis());
        lista.add(m);
        hc.datos().set(ruta, lista);
    }

    /**
     * Entrega lo que le esperaba: al conectarse o al salir de Calamity (dentro no, que lo
     * perderia al morir). Se borra de los datos ANTES de dar nada y se guarda: una caida a
     * mitad puede perder un premio, nunca duplicarlo.
     */
    void pendientes(Player p) {
        if (p == null || !p.isOnline() || hc.esHardcore(p)) return;
        String ruta = "premios-pendientes." + p.getUniqueId();
        List<Map<?, ?>> lista = hc.datos().getMapList(ruta);
        if (lista.isEmpty()) return;
        hc.datos().set(ruta, null);
        hc.guardarYa();
        List<String> dados = new ArrayList<>();
        boolean suelo = false;
        for (Map<?, ?> m : lista) {
            String tipo = String.valueOf(m.get("tipo"));
            String dato = String.valueOf(m.get("dato"));
            String objeto = String.valueOf(m.get("objeto"));
            String origen = String.valueOf(m.get("origen"));
            try {
                if (tipo.equals("mc")) {
                    long mc = Long.parseLong(dato);
                    MobCoins.pagar(hc.plugin(), p, mc);
                    dados.add(mc + " MobCoins");
                } else {
                    ItemStack it = deTexto(dato);
                    if (it == null) throw new IllegalStateException("objeto ilegible");
                    suelo |= Suelo.dar(hc.plugin(), p, it);
                    dados.add(objeto);
                }
                hc.plugin().bitacora().anotar("entrega", "pendiente-entregado", objeto, p.getName(), dato.length() > 40 ? "item" : dato, origen);
            } catch (Throwable t) {
                hc.plugin().bitacora().anotar("entrega", "pendiente-fallo", objeto, p.getName(), origen, String.valueOf(t.getMessage()));
                hc.plugin().getLogger().warning("[Calamity] No se pudo entregar un premio pendiente de " + p.getName() + ": " + t);
            }
        }
        if (!dados.isEmpty()) {
            p.sendMessage(ComandoCalamity.mensaje(Component.text("Te esperaban premios: ")
                    .append(Component.text(String.join(", ", dados), Paleta.DETALLE))
                    .append(Component.text("."))));
        }
        if (suelo) p.sendMessage(ComandoCalamity.mensaje("No te cabía: lo tienes a tus pies."));
    }

    int cuantosPendientes(UUID u) {
        return hc.datos().getMapList("premios-pendientes." + u).size();
    }

    static String aTexto(ItemStack it) {
        return Base64.getEncoder().encodeToString(it.serializeAsBytes());
    }

    static ItemStack deTexto(String s) {
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
        } catch (Throwable t) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntrar(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (cuantosPendientes(p.getUniqueId()) == 0) return;
        // Un segundo despues: que el mensaje no se pierda entre los del join.
        final BukkitTask[] t = new BukkitTask[1];
        t[0] = hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(), () -> {
            tareas.remove(t[0]);
            hc.seguro("entregas", () -> pendientes(p));
        }, 20L);
        tareas.add(t[0]);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCambiarMundo(PlayerChangedWorldEvent e) {
        hc.seguro("entregas", () -> pendientes(e.getPlayer()));
    }

    void parar() {
        for (BukkitTask t : tareas) t.cancel();
        tareas.clear();
        Suelo.parar();
    }

    // ------------------------------------------------------------------ comandos

    /** Un jugador por nombre: conectado, o que haya entrado alguna vez (cache del servidor), o por UUID. */
    static OfflinePlayer buscar(String nombre) {
        if (nombre == null || nombre.isBlank()) return null;
        Player p = Bukkit.getPlayerExact(nombre);
        if (p != null) return p;
        try {
            return Bukkit.getOfflinePlayer(UUID.fromString(nombre));
        } catch (IllegalArgumentException noEsUuid) {
            // No es un UUID: se busca por nombre.
        }
        if (!NOMBRE_VALIDO.matcher(nombre).matches()) return null;
        return Bukkit.getOfflinePlayerIfCached(nombre);
    }

    static List<String> nombresConectados() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        return out;
    }

    static String nombre(OfflinePlayer o) {
        return o.getName() == null ? o.getUniqueId().toString() : o.getName();
    }

    private static String nombreDe(UUID u) {
        return Saldo.nombre(u);
    }

    private static String origenDe(CommandSender quien) {
        return "dar:" + (quien instanceof Player p ? p.getName() : "consola");
    }

    private void comandoDar(CommandSender quien, String[] args) {
        if (args.length < 3) {
            quien.sendMessage(Component.text("Uso: /lw hardcore dar <objeto> <jugador> [n] [origen]", Paleta.AVISO));
            quien.sendMessage(Component.text("Objetos: " + String.join(", ", OBJETOS)
                    + ", credito:<tipo>, credito-caja:<tipo>, forja:<pieza>", Paleta.TENUE));
            return;
        }
        OfflinePlayer a = buscar(args[2]);
        if (a == null) {
            quien.sendMessage(Component.text("No encuentro a ese jugador.", Paleta.AVISO));
            return;
        }
        int n = 1;
        if (args.length >= 4) {
            try {
                n = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                quien.sendMessage(Component.text("Eso no es un número.", Paleta.AVISO));
                return;
            }
        }
        if (n < 1 || n > 1000) {
            quien.sendMessage(Component.text("La cantidad va de 1 a 1000.", Paleta.AVISO));
            return;
        }
        String origen = args.length >= 5 ? args[4].toLowerCase(Locale.ROOT) : origenDe(quien);
        if (dar(quien, args[1], a, n, origen)) {
            quien.sendMessage(Component.text("Entregado: " + args[1].toLowerCase(Locale.ROOT) + " x" + n + " a "
                    + nombre(a) + ".", Paleta.BIEN));
        }
    }

    private List<String> tabDar(String[] args) {
        return switch (args.length) {
            case 2 -> {
                List<String> op = new ArrayList<>(OBJETOS);
                op.add("credito:");
                op.add("credito-caja:");
                for (String k : List.of("yelmo", "coraza", "grebas", "soleretas", "hacha", "mascara", "filo", "guadana")) {
                    op.add("forja:" + k);
                }
                yield op;
            }
            case 3 -> nombresConectados();
            case 4 -> List.of("1", "2", "5", "10");
            default -> List.of();
        };
    }

    private void comandoSaldo(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(Component.text("Uso: /lw hardcore saldo <jugador> [+n|-n]", Paleta.AVISO));
            return;
        }
        OfflinePlayer a = buscar(args[1]);
        Saldo s = hc.saldo();
        if (a == null || s == null) {
            quien.sendMessage(Component.text("No encuentro a ese jugador.", Paleta.AVISO));
            return;
        }
        UUID u = a.getUniqueId();
        if (args.length >= 3) {
            long n;
            try {
                n = Long.parseLong(args[2].startsWith("+") ? args[2].substring(1) : args[2]);
            } catch (NumberFormatException e) {
                quien.sendMessage(Component.text("Eso no es un número: +n o -n.", Paleta.AVISO));
                return;
            }
            String motivo = "admin:" + (quien instanceof Player p ? p.getName() : "consola");
            if (n > 0) s.sumar(u, n, motivo);
            else if (n < 0 && !s.restar(u, -n, motivo)) {
                quien.sendMessage(Component.text("No le llega: tiene " + s.de(u) + ".", Paleta.AVISO));
                return;
            }
        }
        quien.sendMessage(ComandoCalamity.mensaje(Component.text("Saldo de " + nombre(a) + ": ")
                .append(Component.text(String.valueOf(s.de(u)), Paleta.CIFRA))
                .append(Component.text(" Esencias."))));
    }

    private void comandoCreditos(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(Component.text("Uso: /lw hardcore creditos <jugador> [tipo +n|-n]", Paleta.AVISO));
            return;
        }
        OfflinePlayer a = buscar(args[1]);
        Creditos c = hc.creditos();
        if (a == null || c == null) {
            quien.sendMessage(Component.text("No encuentro a ese jugador.", Paleta.AVISO));
            return;
        }
        UUID u = a.getUniqueId();
        if (args.length >= 4) {
            int n;
            try {
                n = Integer.parseInt(args[3].startsWith("+") ? args[3].substring(1) : args[3]);
            } catch (NumberFormatException e) {
                quien.sendMessage(Component.text("Eso no es un número: +n o -n.", Paleta.AVISO));
                return;
            }
            c.sumar(u, args[2], n, "admin:" + (quien instanceof Player p ? p.getName() : "consola"), false);
        } else if (args.length == 3) {
            quien.sendMessage(Component.text("Falta la cantidad: creditos <jugador> <tipo> +n|-n", Paleta.AVISO));
            return;
        }
        Map<String, Integer> todos = c.todos(u);
        quien.sendMessage(ComandoCalamity.mensaje(Component.text("Créditos de " + nombre(a) + ":")));
        if (todos.isEmpty()) {
            quien.sendMessage(Component.text("  ninguno", Paleta.TENUE));
            return;
        }
        for (Map.Entry<String, Integer> e : todos.entrySet()) {
            int caja = c.deCaja(u, e.getKey());
            quien.sendMessage(Component.text("  " + e.getKey() + ": ", Paleta.TENUE)
                    .append(Component.text(String.valueOf(e.getValue()), Paleta.CIFRA))
                    .append(Component.text((caja > 0 ? "  (" + caja + " de caja)" : "")
                            + (c.canjeable(u, e.getKey()) ? "" : "  · aún no se canjea"), Paleta.TENUE)));
        }
    }

    private void comandoMc(CommandSender quien, String[] args) {
        if (args.length < 3) {
            quien.sendMessage(Component.text("Uso: /lw hardcore mc <jugador> <n>", Paleta.AVISO));
            return;
        }
        OfflinePlayer a = buscar(args[1]);
        Monedero m = hc.monedero();
        if (a == null || m == null) {
            quien.sendMessage(Component.text("No encuentro a ese jugador.", Paleta.AVISO));
            return;
        }
        long n;
        try {
            n = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            quien.sendMessage(Component.text("Eso no es un número.", Paleta.AVISO));
            return;
        }
        m.ponerPrueba(a.getUniqueId(), n);
        hc.plugin().bitacora().anotar("monedero", "prueba", nombre(a), String.valueOf(Math.max(0, n)));
        boolean prueba = "prueba".equalsIgnoreCase(hc.cfg().getString("monedero.modo", "real"));
        quien.sendMessage(Component.text("MobCoins de prueba de " + nombre(a) + ": " + Math.max(0, n)
                + (prueba ? "." : "  (ojo: monedero.modo no es prueba, no se usan)"), prueba ? Paleta.BIEN : Paleta.CIFRA));
    }

    /** /calamity saldo: P-M08 y los creditos, con lo que aun no se puede canjear. */
    private void comandoMiSaldo(CommandSender quien, String[] args) {
        if (!(quien instanceof Player p)) {
            quien.sendMessage(Component.text("Solo para jugadores: /lw hardcore saldo <jugador>.", Paleta.AVISO));
            return;
        }
        UUID u = p.getUniqueId();
        Saldo s = hc.saldo();
        if (s != null) p.sendMessage(s.avisoSaldo(u));
        Creditos c = hc.creditos();
        if (c != null) {
            for (Map.Entry<String, Integer> e : c.todos(u).entrySet()) {
                p.sendMessage(Component.text("  " + nombreCredito(e.getKey()) + ": ", Paleta.TEXTO)
                        .append(Component.text(String.valueOf(e.getValue()), Paleta.CIFRA))
                        .append(Component.text(c.canjeable(u, e.getKey()) ? ""
                                : "  · se canjea con " + Math.round(c.horasPedidas()) + " h activas", Paleta.TENUE)));
            }
        }
        int pend = cuantosPendientes(u);
        if (pend > 0) {
            p.sendMessage(Component.text("  Te esperan ", Paleta.TEXTO).append(Paleta.cifra(pend))
                    .append(Component.text(" premios fuera de Calamity.", Paleta.TEXTO)));
        }
    }

    private static String nombreCredito(String tipo) {
        if (tipo.startsWith("sello:")) return "Sello de " + tipo.substring(6);
        return switch (tipo) {
            case Creditos.ERRANTE -> "Sello Errante";
            case "fragmento" -> "Fragmentos de Guadaña";
            case "marca" -> "Marcas de Eco";
            default -> tipo;
        };
    }

    // ------------------------------------------------------------------ pruebas

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        int[] r = repartoLlaves(0, 5, 4, true);
        h.igual("5 llaves con el tope vacio: 4 y sobra 1", "4/1", r[0] + "/" + r[1]);
        r = repartoLlaves(3, 2, 4, true);
        h.igual("con 3 usadas, 2 llaves: 1 y sobra 1", "1/1", r[0] + "/" + r[1]);
        r = repartoLlaves(4, 2, 4, true);
        h.igual("tope lleno: 0 y sobran 2", "0/2", r[0] + "/" + r[1]);
        r = repartoLlaves(4, 2, 4, false);
        h.igual("llave-hito ignora el tope", "2/0", r[0] + "/" + r[1]);
        h.ok("libros: 0 y 1 caben con tope 2", libroCabe(0, 2) && libroCabe(1, 2));
        h.ok("libros: el 3.o no cabe", !libroCabe(2, 2));

        UUID u = Autotest.sintetico(51);
        ItemStack t = ligar(talisman(), u);
        h.igual("ligar pone el uuid", u.toString(),
                t.getItemMeta().getPersistentDataContainer().get(Marcas.LIGADO, PersistentDataType.STRING));
        int lineas = t.getItemMeta().lore().size();
        ligar(t, u);
        h.igual("ligar dos veces no repite la linea", lineas, t.getItemMeta().lore().size());
        h.ok("talisman con su marca", Marcas.tiene(t, Marcas.TALISMAN));
        h.ok("talisman sin cursiva",
                t.getItemMeta().displayName().decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE);
        ItemStack g1 = grabado(), g2 = grabado();
        String id1 = g1.getItemMeta().getPersistentDataContainer().get(Marcas.GRABADO, PersistentDataType.STRING);
        String id2 = g2.getItemMeta().getPersistentDataContainer().get(Marcas.GRABADO, PersistentDataType.STRING);
        h.ok("cada grabado con su uuid", id1 != null && id2 != null && !id1.equals(id2));
        h.ok("salvoconducto con su marca", Marcas.tiene(salvoconducto(), Marcas.SALVOCONDUCTO));
        ItemStack f = ligar(hc.items().frasco(3), u);
        ItemStack vuelta = deTexto(aTexto(f));
        h.ok("un pendiente vuelve igual (frasco ligado)", vuelta != null && vuelta.isSimilar(f));
        h.igual("tintura de entregas.mmo", "CONSUMABLE.TINTURA_DE_CENIZA", idMmo("tintura"));
        h.igual("pieza de la Forja", "ARMOR.YELMO_DE_CALAMIDAD", idMmo("forja:yelmo"));
        h.igual("objeto que no existe", null, idMmo("espada-de-madera"));
        boolean hayMmo = PuenteMmo.disponible();
        h.igual("sin MMOItems no se crea la tintura", hayMmo, crear("tintura") != null);
        if (!hayMmo) h.igual("motivo sin MMOItems", "sin MMOItems", motivoSinObjeto("tintura"));
        h.igual("nombre con espacio no vale para comandos", false, NOMBRE_VALIDO.matcher("a b").matches());
        h.igual("nombre con punto y coma no vale", false, NOMBRE_VALIDO.matcher("x;op").matches());
        h.igual("nombre de Bedrock vale", true, NOMBRE_VALIDO.matcher(".Sain_01").matches());
        h.ok("dar registrado", Subcomandos.lw().nombres(null).contains("dar"));
        h.ok("/calamity saldo registrado", Subcomandos.calamity().nombres(null).contains("saldo"));
        h.ok("la prueba no deja pendientes", !hc.datos().isSet("premios-pendientes." + u));
        return h.lineas();
    }
}
