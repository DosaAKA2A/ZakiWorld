package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * M3 · Reliquias: crearlas, reconocerlas, llevar su registro y que no sirvan para otra cosa.
 *
 * Una Reliquia no vale nada dentro: es la promesa de un pago que solo se cumple si sales
 * vivo (Tasacion). Por eso todo lo que haria de ella otra cosa (colocarla, craftear, fundir,
 * encender la carga ignea del Sello) se corta aqui, y fuera de Calamity no existe.
 *
 * Dos clases de Reliquia (DIS M3 [alineado]):
 * - I y II "de monton": apilables, sin UUID, sin registro ni caducidad. Un dupe de estas lo
 *   acota el tope diario de la Tasacion (60 / 30), que sale mas barato que un UUID por
 *   Astilla y deja el inventario manejable.
 * - III, IV y TODAS las especiales: UUID propio, fecha de nacimiento y una linea en
 *   reliquias.log. La Tasacion solo paga un UUID que este emitido y que no se haya cobrado.
 *
 * reliquias.log es de solo anadir (E al emitir, C al cobrar), con flush tras cada linea: a
 * prueba de caidas y de forjas con NBT copiado. hardcore-datos.yml se vuelca cada minuto y
 * una caida entre medias dejaria cobrar dos veces la misma Reliquia.
 */
final class Reliquias implements Listener {

    static final String CAMPANA = "campana-parca";
    static final String LAGRIMA = "lagrima-eco";
    static final String SELLO = "sello-minijefe";
    static final String ECLIPSADA = "eclipsada";

    /** Ambar: el lore y los nombres altos. */
    static final TextColor AMBAR = TextColor.color(0xE8A33D);
    /** Del verde palido de Calamity al ambar segun el grado; la IV en el naranja de la Esencia. */
    private static final TextColor[] COLOR_GRADO = {
            AMBAR, TextColor.color(0x9FD6A0), TextColor.color(0xC4BD6E), AMBAR, TextColor.color(0xE8903C)};
    static final String[] ROMANO = {"", "I", "II", "III", "IV"};

    private final Hardcore hc;
    private final Registro registro;
    /**
     * Quien acaba de pasar por la Tasacion y va camino de la puerta: el cambio de mundo que
     * sigue al teleport no le borra nada (la Tasacion ya se lo ha quitado todo; si algo
     * quedara, se deshace en el siguiente inventario que abra fuera). Es la bandera de
     * extraccion de DIS M3.
     */
    private final Set<UUID> extrayendo = new HashSet<>();

    Reliquias(Hardcore hc) {
        this.hc = hc;
        long ventana = Math.max(1, hc.cfg().getInt("reliquias.caduca-dias", 14)) * 2L * 86_400_000L;
        this.registro = Registro.abrir(new File(hc.plugin().getDataFolder(), "reliquias.log"), ventana,
                hc.plugin().getLogger());
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("reliquias", this::autotest);
        // 1.10 (lores): la plantilla comun y el lore de cada objeto propio, con la config viva.
        Autotest.registrar("fichas", () -> Ficha.autotestObjetos(hc.cfg()));
        Subcomandos.lw().registrar("reliquia",
                "reliquia <1-4> [jugador] [especial[:N][:valida|:minijefe]]: emite una Reliquia (origen admin)",
                "ederus.mundos", this::comando, this::tab);
    }

    void parar() {
        registro.cerrar();
        extrayendo.clear();
    }

    Registro registro() {
        return registro;
    }

    boolean activas() {
        return hc.cfg().getBoolean("reliquias.activas", true);
    }

    // ------------------------------------------------------------------- crear

    /**
     * Crea una Reliquia y, si lleva UUID, la apunta en reliquias.log. No la entrega: eso es
     * cosa de la Aduana (regla 7), que la mete en el inventario o la deja a los pies.
     *
     * @param especial null o campana-parca, lagrima-eco, sello-minijefe, eclipsada (acepta
     *                 los alias cortos: campana, lagrima, sello)
     * @param nivel    N de la PARCA (Campana) o del Eco (Lagrima); 0 si no aplica
     * @param minijefe id del minijefe del Sello (heraldo-carmes...)
     * @param valida   Lagrima de una caza valida (da Marca de Eco al tasar)
     */
    ItemStack crear(int grado, String origen, String especial, int nivel, String minijefe, boolean valida) {
        int g = Math.max(1, Math.min(4, grado));
        String esp = normalizarEspecial(especial);
        if (SELLO.equals(esp)) g = 4;
        ConfigurationSection c = hc.cfg();
        boolean apilable = esp == null && apilables(c).contains(g);
        String o = origen == null || origen.isBlank() ? "admin" : origen.trim().replace(' ', '-');

        ItemStack item = new ItemStack(material(c, g, esp));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(texto(nombre(c, g, esp, minijefe), COLOR_GRADO[g]));

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(Marcas.RELIQUIA, PersistentDataType.INTEGER, g);
        long ahora = System.currentTimeMillis();
        String id = null;
        if (!apilable) {
            // UUID.randomUUID tira de SecureRandom: el id no se puede adivinar para forjar otra.
            id = UUID.randomUUID().toString();
            pdc.set(Marcas.RELIQUIA_ID, PersistentDataType.STRING, id);
            pdc.set(Marcas.RELIQUIA_ORIGEN, PersistentDataType.STRING, o);
            pdc.set(Marcas.RELIQUIA_NACIO, PersistentDataType.LONG, ahora);
            if (esp != null) pdc.set(Marcas.RELIQUIA_ESPECIAL, PersistentDataType.STRING, esp);
            if (nivel > 0) pdc.set(Marcas.RELIQUIA_NIVEL, PersistentDataType.INTEGER, nivel);
            if (minijefe != null && !minijefe.isBlank()) {
                pdc.set(Marcas.RELIQUIA_MINIJEFE, PersistentDataType.STRING, minijefe.trim().toLowerCase(Locale.ROOT));
            }
            if (valida) pdc.set(Marcas.RELIQUIA_VALIDA, PersistentDataType.BYTE, (byte) 1);
        }
        // 1.10: la plantilla comun (Ficha). El origen solo en las que lo guardan (III, IV y especiales): en
        // las de monton partiria los montones (una Astilla de cofre no se apilaria con una de mob).
        meta.lore(ficha(c, g, esp, nivel, minijefe, valida, apilable ? null : o, !apilable,
                apilable ? null : fechaCorta(ahora + caducaMillis())).lore());
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        if (id != null) registro.emitida(id, g, o, ahora);
        return item;
    }

    long caducaMillis() {
        return Math.max(1, hc.cfg().getInt("reliquias.caduca-dias", 14)) * 86_400_000L;
    }

    private String fechaCorta(long millis) {
        Calendario cal = hc.calendario();
        ZoneId zona = cal != null ? cal.zona() : ZoneId.systemDefault();
        return DateTimeFormatter.ofPattern("dd/MM").format(Instant.ofEpochMilli(millis).atZone(zona));
    }

    private static Component texto(String s, TextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }

    // ------------------------------------------------------------------- lore

    /** El color del nombre de una Reliquia de ese grado: el acento de todo su lore. */
    static TextColor color(int grado) {
        return COLOR_GRADO[Math.max(1, Math.min(4, grado))];
    }

    /** La linea de historia de cada clase de Reliquia. */
    static String historia(int g, String esp) {
        if (esp != null) {
            return switch (esp) {
                case CAMPANA -> "Tañe sola cuando la Parca está cerca. Esta ya no tiene a quién avisar.";
                case LAGRIMA -> "Lo último que lloró un Eco antes de callar para siempre.";
                case SELLO -> "Lacre del minijefe caído. Todavía quema al tacto.";
                default -> "Nació a la sombra del Eclipse. Su brillo no es de este cielo.";
            };
        }
        return switch (g) {
            case 1 -> "Esquirla del umbral. Se quiebra cada vez que alguien no vuelve.";
            case 2 -> "Aún tararea la nana que Bracken cantaba antes de pudrirse.";
            case 3 -> "Resina que el bosque lloró sobre los que no volvieron.";
            default -> "Lleva algo atrapado dentro. A veces se mueve.";
        };
    }

    /**
     * De donde salio, dicho para el jugador ("Cayó del Heraldo Carmesí"), o null si no se sabe o no
     * importa (admin). Lo que guarda RELIQUIA_ORIGEN: mob, destacado, minijefe, cofre, eco, parca, eclipse.
     */
    static String origen(String origen, int nivel, String minijefe) {
        if (origen == null) return null;
        String nv = nivel > 0 ? " de nivel {" + nivel + "}" : "";
        return switch (origen) {
            case "mob" -> "La soltó una criatura" + (nivel > 0 ? nv : " de Calamity");
            case "destacado" -> "La soltó una criatura destacada" + nv;
            case "minijefe" -> minijefe == null || minijefe.isBlank() ? "Cayó de un minijefe" + nv
                    : "Cayó " + Forja.delMinijefe(minijefe);
            case "cofre" -> "Estaba en un cofre de Calamity";
            case "eco" -> "La soltó un Eco" + nv;
            case "parca" -> "Cayó de una Parca" + nv;
            case "eclipse" -> "Cayó durante un Eclipse";
            default -> null;
        };
    }

    /**
     * El lore de una Reliquia con la plantilla comun (Ficha): grado en estrellas, historia, origen, lo que
     * vale al salir vivo (reliquias.grados.N de la config viva), lo que da de mas y para que sirve (los
     * especiales, de la Tasacion y de altar.trueques), si es pieza unica y cuando caduca. Pura: la prueba
     * el autotest "fichas".
     *
     * @param origen null para no decirlo (las de monton, que no lo guardan)
     * @param caduca "18/10" o null (las de monton no caducan)
     */
    static Ficha ficha(ConfigurationSection c, int grado, String esp, int nivel, String minijefe, boolean valida,
                       String origen, boolean unica, String caduca) {
        int g = Math.max(1, Math.min(4, grado));
        boolean masculino = SELLO.equals(esp);
        Ficha f = new Ficha(color(g)).tipo("Reliquia · Grado " + ROMANO[g] + " " + Ficha.estrellas(g)).filete()
                .historia(historia(g, esp));
        f.texto(origen(origen, nivel, minijefe));
        f.filete().etiqueta(unica ? "Valor al salir vivo" : "Valor de cada una al salir vivo");
        f.dato(Ficha.valor(c.getDouble("reliquias.grados." + g + ".esencias", ESENCIAS_SERIE[g]),
                c.getLong("reliquias.grados." + g + ".mobcoins", MC_SERIE[g])));

        int minimo = c.getInt("reliquias.especiales.campana-parca.fragmento-nivel-minimo", 40);
        if (CAMPANA.equals(esp)) {
            boolean fragmento = nivel >= minimo;
            if (fragmento) f.dato("+ {1} Fragmento de Guadaña");
            if (g == 4) f.dato("+ " + Ficha.probabilidad(c.getDouble("reliquias.especiales.campana-parca.llave-caos-iv", 0.25))
                    + " de ganar una Llave del Caos");
            if (fragmento) {
                usos(f, Ficha.usosDeCredito(c, "fragmento"), "Fragmento", "Fragmentos");
            } else {
                f.nota("Solo la de una Parca de nivel {" + minimo + "} o más da un Fragmento de Guadaña.");
            }
        } else if (LAGRIMA.equals(esp)) {
            boolean marca = valida || g == 4;
            if (marca) f.dato("+ {1} Marca de Eco (máximo {" + c.getInt("eco.marcas.dia", 2) + "} al día)");
            if (g == 4) f.dato("+ " + Ficha.probabilidad(c.getDouble("reliquias.especiales.lagrima-eco.llave-caos-iv", 0.20))
                    + " de ganar una Llave del Caos");
            if (marca) usos(f, Ficha.usosDeCredito(c, "marca"), "Marca", "Marcas");
            else f.nota("Solo la de una caza válida da Marca de Eco.");
        } else if (SELLO.equals(esp)) {
            List<Ficha.Uso> u = minijefe == null || minijefe.isBlank() ? List.of()
                    : Ficha.usosDeCredito(c, "sello:" + minijefe.trim().toLowerCase(Locale.ROOT));
            if (u.isEmpty()) {
                f.etiqueta("Para qué sirve").dato("Vale como crédito en la Forja de Vael.");
            } else {
                f.etiqueta("Desbloquea en la Forja de Vael");
                for (Ficha.Uso x : u) f.dato("{" + x.da() + "}");
            }
        }

        if (unica) f.nota("Pieza única · no se apila");
        else f.nota("Se pagan hasta {" + c.getInt("reliquias.tope-dia." + g, g == 1 ? 60 : 30) + "} al día");
        if (caduca != null) f.nota("Caduca el {" + caduca + "}");
        return f.filete()
                .accion((masculino ? "Se vende solo" : "Se vende sola") + " al salir de Calamity por la puerta o con un Cristal.")
                .nota(masculino ? "Si mueres antes de salir, lo pierdes." : "Si mueres antes de salir, la pierdes.");
    }

    /** "Para qué sirve" y una linea por pieza: " 7 Fragmentos · Guadaña de la Parca". */
    private static void usos(Ficha f, List<Ficha.Uso> usos, String uno, String varios) {
        if (usos.isEmpty()) return;
        f.etiqueta("Para qué sirve");
        for (Ficha.Uso u : usos) f.dato(Ficha.cantidad(u.cantidad(), uno, varios) + " · " + u.da());
    }

    /** Los valores de serie de la Tasacion (Tasacion.Valores), por si la config no trae el grado. */
    private static final double[] ESENCIAS_SERIE = {0, 0.2, 1, 3, 6};
    private static final long[] MC_SERIE = {0, 5, 15, 40, 100};

    static Set<Integer> apilables(ConfigurationSection c) {
        if (!c.isList("reliquias.apilables")) return Set.of(1, 2);
        Set<Integer> s = new HashSet<>();
        for (Object o : c.getList("reliquias.apilables", List.of())) {
            try {
                s.add(Integer.parseInt(String.valueOf(o).trim()));
            } catch (NumberFormatException ignorado) {
                // Un valor raro en la lista no hace apilable a nada.
            }
        }
        return s;
    }

    private static Material material(ConfigurationSection c, int g, String esp) {
        String def = switch (esp == null ? "" : esp) {
            case CAMPANA -> "BELL";
            case LAGRIMA -> "ECHO_SHARD";
            case SELLO -> "FIRE_CHARGE";
            case ECLIPSADA -> "RESIN_CLUMP";
            default -> switch (g) {
                case 1 -> "PRISMARINE_SHARD";
                case 2 -> "DISC_FRAGMENT_5";
                default -> "RESIN_CLUMP";
            };
        };
        String ruta = esp != null ? "reliquias.especiales." + esp + ".material" : "reliquias.grados." + g + ".material";
        Material m = Material.matchMaterial(c.getString(ruta, def));
        if (m == null || !m.isItem() || m.isAir()) m = Material.matchMaterial(def);
        return m == null ? Material.PRISMARINE_SHARD : m;
    }

    private static String nombre(ConfigurationSection c, int g, String esp, String minijefe) {
        if (esp == null) {
            String def = switch (g) {
                case 1 -> "Astilla del Umbral";
                case 2 -> "Fragmento de Nana";
                case 3 -> "Ámbar Coagulado";
                default -> "Ámbar Mayor";
            };
            return conTildes(c.getString("reliquias.grados." + g + ".nombre", def), def);
        }
        String def = switch (esp) {
            case CAMPANA -> "Campana de la Parca";
            case LAGRIMA -> "Lágrima de Eco";
            case SELLO -> "Sello de %minijefe%";
            default -> "Reliquia Eclipsada";
        };
        String n = conTildes(c.getString("reliquias.especiales." + esp + ".nombre", def), def);
        // "Sello de %minijefe%" se lee "Sello del Heraldo Carmesí" (o "de la Matriarca Tejedora").
        return n.replace("de %minijefe%", Forja.delMinijefe(minijefe)).replace("%minijefe%", Minijefes.nombre(minijefe));
    }

    /**
     * El config.yml de la casa va sin tildes; el objeto las lleva. Si lo que pone la config
     * es el texto de serie sin tildes, se usa el de serie con ellas; si Dosa lo cambia, manda
     * lo suyo.
     */
    static String conTildes(String config, String deSerie) {
        if (config == null) return deSerie;
        String plano = Normalizer.normalize(deSerie, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return config.equals(plano) ? deSerie : config;
    }

    /** "Ámbar Coagulado", "Campana de la Parca"... el nombre que lleva el objeto, sin colores. */
    String nombreDe(int grado, String especial, String minijefe) {
        return nombre(hc.cfg(), Math.max(1, Math.min(4, grado)), normalizarEspecial(especial), minijefe);
    }

    // ---------------------------------------------------------------- reconocer

    boolean es(ItemStack item) {
        return Marcas.tiene(item, Marcas.RELIQUIA);
    }

    int grado(ItemStack item) {
        Integer g = leer(item, Marcas.RELIQUIA, PersistentDataType.INTEGER);
        return g == null ? 0 : g;
    }

    String especial(ItemStack item) {
        return leer(item, Marcas.RELIQUIA_ESPECIAL, PersistentDataType.STRING);
    }

    String id(ItemStack item) {
        return leer(item, Marcas.RELIQUIA_ID, PersistentDataType.STRING);
    }

    long nacio(ItemStack item) {
        Long n = leer(item, Marcas.RELIQUIA_NACIO, PersistentDataType.LONG);
        return n == null ? 0 : n;
    }

    int nivel(ItemStack item) {
        Integer n = leer(item, Marcas.RELIQUIA_NIVEL, PersistentDataType.INTEGER);
        return n == null ? 0 : n;
    }

    String minijefe(ItemStack item) {
        return leer(item, Marcas.RELIQUIA_MINIJEFE, PersistentDataType.STRING);
    }

    boolean valida(ItemStack item) {
        return Marcas.tiene(item, Marcas.RELIQUIA_VALIDA);
    }

    /** Si ya paso su caducidad. Las apilables (sin UUID ni fecha) no caducan. */
    boolean caducada(ItemStack item, long ahora) {
        long n = nacio(item);
        if (n <= 0 || id(item) == null) return false;
        return ahora - n >= caducaMillis();
    }

    private static <T> T leer(ItemStack item, NamespacedKey clave, PersistentDataType<?, T> tipo) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(clave, tipo);
    }

    /** "campana" -> campana-parca, "lagrima" -> lagrima-eco... null si no es un especial. */
    static String normalizarEspecial(String s) {
        if (s == null) return null;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "campana", "campana-parca", "campana-de-la-parca" -> CAMPANA;
            case "lagrima", "lagrima-eco", "lagrima-de-eco" -> LAGRIMA;
            case "sello", "sello-minijefe" -> SELLO;
            case "eclipsada", "reliquia-eclipsada" -> ECLIPSADA;
            default -> null;
        };
    }

    /** Lo que se escribe en un comando: especial, grado, nivel, minijefe y si es valida. */
    record Espec(String especial, int grado, int nivel, String minijefe, boolean valida) {
    }

    /**
     * Lee "campana:3:45", "lagrima:4:60:valida", "sello:4:heraldo-carmes" (conGrado) o
     * "campana:45", "sello:heraldo-carmes", "mayor" (sin grado: va aparte). Detras del grado,
     * un numero es el nivel, "valida" marca la caza valida y lo demas es el minijefe.
     * Null si el especial no se reconoce.
     */
    static Espec espec(String texto, boolean conGrado, int gradoDefecto) {
        if (texto == null || texto.isBlank()) return null;
        String[] t = texto.trim().toLowerCase(Locale.ROOT).split(":");
        String esp = normalizarEspecial(t[0]);
        boolean mayor = t[0].equals("mayor") || t[0].equals("ambar-mayor");
        if (esp == null && !mayor) return null;
        int grado = mayor ? 4 : gradoDefecto;
        int desde = 1;
        if (conGrado && t.length > 1) {
            try {
                grado = Integer.parseInt(t[1]);
                desde = 2;
            } catch (NumberFormatException e) {
                // "sello:heraldo-carmes" sin grado: lo que va detras no es un numero.
            }
        }
        if (SELLO.equals(esp)) grado = 4;
        int nivel = 0;
        String minijefe = null;
        boolean valida = false;
        for (int i = desde; i < t.length; i++) {
            if (t[i].isBlank()) continue;
            if (t[i].equals("valida")) {
                valida = true;
                continue;
            }
            try {
                nivel = Integer.parseInt(t[i]);
            } catch (NumberFormatException e) {
                minijefe = t[i];
            }
        }
        return new Espec(esp, Math.max(1, Math.min(4, grado)), nivel, minijefe, valida);
    }

    // ---------------------------------------------------------------- bloqueos

    /*
     * Los bloqueos no preguntan el mundo: una Reliquia no puede servir de campana, de carga
     * ignea ni de material en NINGUN sitio (fuera se deshace, pero entre el teleport y el
     * siguiente inventario hay un momento). La comprobacion barata es la marca del objeto.
     */

    /** La Campana es una campana y el Ambar se coloca: no se pone ninguna. */
    @EventHandler(ignoreCancelled = true)
    public void onColocar(BlockPlaceEvent e) {
        if (es(e.getItemInHand())) e.setCancelled(true);
    }

    /**
     * El Sello es una carga ignea: con clic derecho prenderia fuego. Se niega el USO del
     * objeto y no el clic entero: con una Astilla en la mano se tiene que poder abrir un cofre.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onUsar(PlayerInteractEvent e) {
        if (!e.getAction().isRightClick()) return;
        if (es(e.getItem())) e.setUseItemInHand(Event.Result.DENY);
    }

    @EventHandler
    public void onCraftear(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (es(it)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onYunque(PrepareAnvilEvent e) {
        if (hayReliquia(e.getInventory())) e.setResult(null);
    }

    @EventHandler
    public void onHerreria(PrepareSmithingEvent e) {
        if (hayReliquia(e.getInventory())) e.setResult(null);
    }

    @EventHandler
    public void onAfilar(PrepareGrindstoneEvent e) {
        if (hayReliquia(e.getInventory())) e.setResult(null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFundir(FurnaceSmeltEvent e) {
        if (es(e.getSource())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDestilar(BrewEvent e) {
        if (hayReliquia(e.getContents())) e.setCancelled(true);
    }

    private boolean hayReliquia(Inventory inv) {
        for (ItemStack it : inv.getContents()) if (es(it)) return true;
        return false;
    }

    /** Caducidad: una Reliquia pasada de fecha se deshace al tocarla en un inventario. */
    @EventHandler(ignoreCancelled = true)
    public void onTocar(InventoryClickEvent e) {
        ItemStack it = e.getCurrentItem();
        if (!es(it) || !caducada(it, System.currentTimeMillis())) return;
        String id = id(it);
        e.setCancelled(true);
        e.setCurrentItem(null);
        HumanEntity quien = e.getWhoClicked();
        quien.sendMessage(ComandoCalamity.mensaje("Esa Reliquia había caducado y se ha deshecho."));
        hc.plugin().bitacora().anotar("reliquia", "caducada", quien.getName(), String.valueOf(id));
    }

    // ------------------------------------------------------- fuera no existen

    /** La llama la Tasacion justo antes del teleport de salida (sacar con extraccion). */
    void marcarExtraccion(Player p) {
        extrayendo.add(p.getUniqueId());
    }

    @EventHandler
    public void onCambiarMundo(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (!hc.esHardcore(e.getFrom()) || hc.esHardcore(p)) return;
        if (extrayendo.remove(p.getUniqueId())) return;
        deshacerFuera(p, null);
    }

    @EventHandler
    public void onEntrar(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        extrayendo.remove(p.getUniqueId());
        if (hc.esHardcore(p)) return;
        deshacerFuera(p, null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAbrir(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || hc.esHardcore(p)) return;
        deshacerFuera(p, e.getInventory());
    }

    /**
     * Borra las Reliquias que alguien lleve fuera de Calamity (y las del inventario que abre).
     * Pasa con la salida de un admin (sacar sin extraccion), con un cofre que se lleno dentro
     * y se abre desde fuera, o con cualquier via que no sea la puerta o el Cristal.
     */
    private void deshacerFuera(Player p, Inventory abierto) {
        if (!activas()) return;
        int n = quitarTodas(p.getInventory());
        if (es(p.getItemOnCursor())) {
            n += p.getItemOnCursor().getAmount();
            p.setItemOnCursor(null);
        }
        if (abierto != null && abierto != p.getInventory()) n += quitarTodas(abierto);
        if (n <= 0) return;
        p.sendMessage(ComandoCalamity.mensaje("Las Reliquias no existen fuera de Calamity: las que llevabas se han deshecho."));
        hc.plugin().bitacora().anotar("reliquia", "perdida", p.getName(), String.valueOf(n),
                p.getWorld().getKey().getKey());
    }

    private int quitarTodas(Inventory inv) {
        int n = 0;
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) {
            if (!es(c[i])) continue;
            n += c[i].getAmount();
            inv.setItem(i, null);
        }
        return n;
    }

    // ----------------------------------------------------------------- comando

    /** /lw hardcore reliquia <1-4> [jugador] [especial]: la emite (origen admin) y la entrega la Aduana. */
    private void comando(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(ComandoCalamity.mensaje("Uso: /calamidad reliquia <1-4> [jugador] [especial]"));
            return;
        }
        int grado;
        try {
            grado = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            grado = 0;
        }
        if (grado < 1 || grado > 4) {
            quien.sendMessage(ComandoCalamity.mensaje("El grado va de 1 a 4."));
            return;
        }
        OfflinePlayer a = args.length >= 3 ? jugador(args[2]) : (quien instanceof Player yo ? yo : null);
        if (a == null) {
            quien.sendMessage(ComandoCalamity.mensaje("No encuentro a ese jugador."));
            return;
        }
        Espec esp = null;
        if (args.length >= 4) {
            esp = espec(args[3], false, grado);
            if (esp == null) {
                quien.sendMessage(ComandoCalamity.mensaje(
                        "No conozco ese especial. Valen: campana[:N], lagrima[:N][:valida], sello:<minijefe>, eclipsada o mayor."));
                return;
            }
        }
        Aduana ad = hc.aduana();
        if (ad == null) {
            quien.sendMessage(ComandoCalamity.mensaje("La Aduana no está en marcha: no se entrega nada."));
            return;
        }
        ItemStack r = esp == null ? crear(grado, "admin", null, 0, null, false)
                : crear(esp.grado(), "admin", esp.especial(), esp.nivel(), esp.minijefe(), esp.valida());
        ad.pagar(a, "admin", 0, 0, List.of(r), "reliquia admin");
        quien.sendMessage(ComandoCalamity.mensaje("Reliquia de grado " + ROMANO[grado(r)]
                + (especial(r) == null ? "" : " (" + especial(r) + ")") + " para " + a.getName()
                + (id(r) == null ? "." : ", id " + id(r) + ".")));
    }

    private List<String> tab(String[] args) {
        if (args.length == 2) return List.of("1", "2", "3", "4");
        if (args.length == 3) return conectados();
        if (args.length == 4) {
            List<String> op = new ArrayList<>(List.of("campana:50", "lagrima:50:valida", "eclipsada", "mayor"));
            for (String id : Minijefes.TIPOS) op.add("sello:" + id);
            return op;
        }
        return List.of();
    }

    static List<String> conectados() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        return out;
    }

    /** Conectado primero; si no, el que el servidor ya conozca por su nombre. */
    @SuppressWarnings("deprecation")
    static OfflinePlayer jugador(String nombre) {
        if (nombre == null || nombre.isBlank()) return null;
        Player p = Bukkit.getPlayerExact(nombre);
        if (p != null) return p;
        OfflinePlayer o = Bukkit.getOfflinePlayerIfCached(nombre);
        if (o != null) return o;
        /* Un nombre que el servidor no ha visto nunca (las pruebas del reparto con "Otro"):
         * getOfflinePlayer lo resuelve a un UUID fijo. Solo lo usan comandos de staff. */
        return Bukkit.getOfflinePlayer(nombre);
    }

    // --------------------------------------------------------------- registro

    /**
     * reliquias.log: "E <uuid> <grado> <origen> <millis>" al emitir y "C <uuid> <jugador> <millis>"
     * al cobrar. Solo se anaden lineas, con flush en cada una. Al arrancar se leen las de la
     * ventana (caduca-dias x 2) y se reescribe el fichero sin las viejas: una Reliquia de hace
     * mas de dos caducidades ya no se puede tasar, asi que su linea no sirve de nada.
     */
    static final class Registro {

        private final Set<String> emitidas = new HashSet<>();
        private final Set<String> cobradas = new HashSet<>();
        private final Logger log;
        private BufferedWriter salida;
        private boolean avisado;

        private Registro(Logger log) {
            this.log = log;
        }

        /** Sin fichero: para los autotest. */
        static Registro enMemoria() {
            return new Registro(null);
        }

        static Registro abrir(File archivo, long ventanaMillis, Logger log) {
            Registro r = new Registro(log);
            long desde = System.currentTimeMillis() - ventanaMillis;
            if (archivo.isFile()) {
                try {
                    List<String> quedan = new ArrayList<>();
                    int viejas = 0;
                    for (String l : Files.readAllLines(archivo.toPath(), StandardCharsets.UTF_8)) {
                        String[] t = l.trim().split(" ");
                        if (t.length < 4) continue;
                        long millis;
                        try {
                            millis = Long.parseLong(t[t.length - 1]);
                        } catch (NumberFormatException e) {
                            continue;
                        }
                        if (millis < desde) {
                            viejas++;
                            continue;
                        }
                        if (t[0].equals("E")) r.emitidas.add(t[1]);
                        else if (t[0].equals("C")) r.cobradas.add(t[1]);
                        else continue;
                        quedan.add(l.trim());
                    }
                    if (viejas > 0) {
                        File tmp = new File(archivo.getParentFile(), archivo.getName() + ".tmp");
                        Files.write(tmp.toPath(), quedan, StandardCharsets.UTF_8);
                        Files.move(tmp.toPath(), archivo.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    if (log != null) log.warning("[Calamity] No se pudo leer reliquias.log: " + e.getMessage());
                }
            }
            try {
                if (archivo.getParentFile() != null) archivo.getParentFile().mkdirs();
                r.salida = Files.newBufferedWriter(archivo.toPath(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                if (log != null) log.warning("[Calamity] No se pudo abrir reliquias.log: " + e.getMessage());
            }
            return r;
        }

        boolean emitida(String id) {
            return id != null && emitidas.contains(id);
        }

        boolean cobrada(String id) {
            return id != null && cobradas.contains(id);
        }

        void emitida(String id, int grado, String origen, long millis) {
            emitidas.add(id);
            escribir("E " + id + " " + grado + " " + origen + " " + millis);
        }

        void cobrada(String id, String jugador, long millis) {
            cobradas.add(id);
            escribir("C " + id + " " + (jugador == null || jugador.isBlank() ? "?" : jugador) + " " + millis);
        }

        private void escribir(String linea) {
            if (salida == null) return;
            try {
                salida.write(linea);
                salida.newLine();
                salida.flush();
            } catch (IOException e) {
                if (!avisado && log != null) log.warning("[Calamity] No se pudo escribir en reliquias.log: " + e.getMessage());
                avisado = true;
            }
        }

        void cerrar() {
            if (salida == null) return;
            try {
                salida.close();
            } catch (IOException ignorado) {
                // Cerrando: todo lo escrito ya tuvo su flush linea a linea.
            }
            salida = null;
        }
    }

    // ----------------------------------------------------------------- autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        ConfigurationSection vacia = new YamlConfiguration();
        h.igual("apilables de serie", Set.of(1, 2), apilables(vacia));
        h.igual("material de la I", Material.PRISMARINE_SHARD, material(vacia, 1, null));
        h.igual("material de la II", Material.DISC_FRAGMENT_5, material(vacia, 2, null));
        h.igual("material de la III", Material.RESIN_CLUMP, material(vacia, 3, null));
        h.igual("material de la Campana", Material.BELL, material(vacia, 4, CAMPANA));
        h.igual("material de la Lagrima", Material.ECHO_SHARD, material(vacia, 4, LAGRIMA));
        h.igual("material del Sello", Material.FIRE_CHARGE, material(vacia, 4, SELLO));
        h.igual("nombre del Sello", "Sello del Heraldo Carmesí", nombre(vacia, 4, SELLO, "heraldo-carmes"));
        h.igual("nombre del Sello de la Matriarca", "Sello de la Matriarca Tejedora", nombre(vacia, 4, SELLO, "matriarca-tejedora"));
        h.igual("nombre del IV sin especial", "Ámbar Mayor", nombre(vacia, 4, null, null));
        h.igual("nombre de la config sin tildes", "Ámbar Coagulado", conTildes("Ambar Coagulado", "Ámbar Coagulado"));
        h.igual("nombre cambiado en la config manda", "Ambar Raro", conTildes("Ambar Raro", "Ámbar Coagulado"));

        Espec a = espec("campana:3:45", true, 1);
        h.ok("espec campana:3:45", a != null && CAMPANA.equals(a.especial()) && a.grado() == 3 && a.nivel() == 45);
        Espec b = espec("lagrima:4:60:valida", true, 1);
        h.ok("espec lagrima:4:60:valida", b != null && LAGRIMA.equals(b.especial()) && b.grado() == 4
                && b.nivel() == 60 && b.valida());
        Espec s = espec("sello:4:heraldo-carmes", true, 1);
        h.ok("espec sello:4:heraldo-carmes", s != null && SELLO.equals(s.especial()) && s.grado() == 4
                && "heraldo-carmes".equals(s.minijefe()));
        Espec s2 = espec("sello:heraldo-carmes", true, 2);
        h.ok("espec sello sin grado es IV", s2 != null && s2.grado() == 4 && "heraldo-carmes".equals(s2.minijefe()));
        h.igual("espec desconocido", null, espec("patata:3", true, 1));
        Espec m = espec("mayor", false, 1);
        h.ok("espec mayor = IV sin especial", m != null && m.especial() == null && m.grado() == 4);

        // El registro en memoria, sin tocar reliquias.log.
        Registro r = Registro.enMemoria();
        String id = UUID.randomUUID().toString();
        h.ok("registro vacio no conoce el id", !r.emitida(id) && !r.cobrada(id));
        r.emitida(id, 3, "prueba", 1L);
        h.ok("emitida", r.emitida(id) && !r.cobrada(id));
        r.cobrada(id, "prueba", 2L);
        h.ok("cobrada", r.cobrada(id));
        h.ok("el registro de prueba no llega al real", !registro.emitida(id));

        // Solo se crea una I: es apilable, no lleva UUID y no deja linea en reliquias.log.
        ItemStack astilla = crear(1, "prueba", null, 0, null, false);
        h.ok("la I es Reliquia", es(astilla));
        h.igual("grado de la I", 1, grado(astilla));
        h.igual("la I no lleva UUID", null, id(astilla));
        h.ok("dos I apilan aunque vengan de sitios distintos", astilla.isSimilar(crear(1, "otra", null, 0, null, false)));
        h.ok("la I no caduca", !caducada(astilla, Long.MAX_VALUE));
        h.ok("lore sin cursiva", astilla.getItemMeta().lore() != null
                && astilla.getItemMeta().lore().stream().allMatch(c -> c.decoration(TextDecoration.ITALIC)
                == TextDecoration.State.FALSE || c.equals(Component.empty())));
        h.ok("una espada no es Reliquia", !es(new ItemStack(Material.DIAMOND_SWORD)));
        h.igual("grado de lo que no es Reliquia", 0, grado(new ItemStack(Material.STONE)));
        return h.lineas();
    }
}
