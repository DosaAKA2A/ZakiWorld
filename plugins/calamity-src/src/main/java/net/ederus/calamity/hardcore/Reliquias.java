package net.ederus.calamity.hardcore;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
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
import org.bukkit.entity.Allay;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
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
 * Una Reliquia no vale nada dentro: es la promesa de un pago que solo se cumple si la llevas a
 * Oren, el mercader del spawn de Calamity (Tasacion.vender, MenuTasador). Por eso todo lo que haria
 * de ella otra cosa (colocarla, craftear, fundir, encender la carga ignea del Sello) se corta aqui.
 *
 * Rama venta-oren: antes "fuera de Calamity no existia" (se deshacia al cambiar de mundo, porque la
 * Tasacion ya la habia vendido en la puerta). Ahora sale contigo y se vende a Oren; lo que impide
 * guardarla, venderla en /ah o pasarla por un cofre fuera de Calamity lo hace Sellos.
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

    /** Ambar: los avisos de chat sobre Reliquias (no el lore: cada Reliquia tiene su Tono). */
    static final TextColor AMBAR = TextColor.color(0xE8A33D);
    static final String[] ROMANO = {"", "I", "II", "III", "IV"};

    private final Hardcore hc;
    private final Registro registro;

    Reliquias(Hardcore hc) {
        this.hc = hc;
        long ventana = Math.max(1, hc.cfg().getInt("reliquias.caduca-dias", 14)) * 2L * 86_400_000L;
        this.registro = Registro.abrir(new File(hc.plugin().getDataFolder(), "reliquias.log"), ventana,
                hc.plugin().getLogger());
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("reliquias", this::autotest);
        // 1.10 (lores): la plantilla comun y el lore de cada objeto propio, con la config viva.
        Autotest.registrar("fichas", () -> Ficha.autotestObjetos(hc.cfg()));
        Subcomandos.staff().registrar("relic",
                "relic <1-4> [player] [special[:N][:valid|:miniboss]]: emite una Reliquia (origen admin)",
                Subcomandos.PERMISO, this::comando, this::tab);
    }

    void parar() {
        registro.cerrar();
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
        meta.displayName(tono(g, esp).nombre(nombre(c, g, esp, minijefe)));

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
        // Sin la cancion del disco ni el material de adorno del ladrillo, igual que una renovada (desactivar).
        desactivar(item);
        ponerModelo(item, modelo(c, g, esp));
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

    // ------------------------------------------------------------------- lore

    /**
     * Rama lore-items · El tono de una Reliquia: el de su grado (I turquesa, II azul, III violeta, IV ambar)
     * o el de su especial (Campana carmesi, Lagrima celeste, Sello el de los minijefes, Eclipsada magenta).
     */
    static Paleta.Tono tono(int grado, String esp) {
        if (esp != null) {
            return Ficha.tono(switch (esp) {
                case CAMPANA -> "campana";
                case LAGRIMA -> "lagrima";
                case SELLO -> "sello";
                default -> "eclipsada";
            });
        }
        return Ficha.tono("grado-" + Math.max(1, Math.min(4, grado)));
    }

    /** El color de una Reliquia en los menus (el de Oren): el tono fuerte de su familia. */
    static TextColor color(int grado, String esp) {
        return tono(grado, esp).fuerte();
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
            case 1 -> "Se quiebra cada vez que alguien no vuelve.";
            case 2 -> "Aún tararea la nana que Bracken cantaba antes de pudrirse.";
            case 3 -> "Resina que el bosque lloró sobre los que no volvieron.";
            default -> "Lleva algo atrapado dentro. A veces se mueve.";
        };
    }

    /**
     * De donde salio, dicho para el jugador ("Cayó del Heraldo Carmesí."), o null si no se sabe o no
     * importa (admin). Lo que guarda RELIQUIA_ORIGEN: mob, destacado, minijefe, cofre, eco, parca, eclipse.
     */
    static String origen(String origen, int nivel, String minijefe) {
        if (origen == null) return null;
        String nv = nivel > 0 ? " de nivel {" + nivel + "}" : "";
        String s = switch (origen) {
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
        return s == null ? null : s + ".";
    }

    /**
     * El lore de una Reliquia con la plantilla comun (Ficha): grado en estrellas, historia, lo que paga Oren
     * (reliquias.grados.N de la config viva), lo que da de mas y para que sirve (los especiales, de la
     * Tasacion y de altar.trueques), de donde salio, si es pieza unica y cuando caduca. Pura: la prueba el
     * autotest "fichas".
     *
     * @param origen null para no decirlo (las de monton, que no lo guardan)
     * @param caduca "18/10" o null (las de monton no caducan)
     */
    static Ficha ficha(ConfigurationSection c, int grado, String esp, int nivel, String minijefe, boolean valida,
                       String origen, boolean unica, String caduca) {
        int g = Math.max(1, Math.min(4, grado));
        boolean masculino = SELLO.equals(esp);
        Ficha f = new Ficha(tono(g, esp)).cabecera(esp == null ? "Reliquia" : "Reliquia especial", "Grado " + ROMANO[g], g)
                .historia(historia(g, esp))
                .seccion("Se vende a Oren, en el spawn");
        double es = c.getDouble("reliquias.grados." + g + ".esencias", ESENCIAS_SERIE[g]);
        long mc = c.getLong("reliquias.grados." + g + ".mobcoins", MC_SERIE[g]);
        if (unica) {
            f.dato(Ficha.valor(es, mc));
        } else {
            // Las de monton, por unidad: "5 MobCoins cada una", "1 Esencia por cada 5" y el tope del dia.
            if (mc > 0) f.dato(Ficha.cantidad(mc, "MobCoin", "MobCoins") + " cada una");
            String e = Ficha.esencias(es);
            if (e != null) f.dato(e.contains("por cada") ? e : e + " cada una");
            f.dato("Hasta {" + c.getInt("reliquias.tope-dia." + g, g == 1 ? 60 : 30) + "} al día");
        }

        String falta = null;
        int minimo = c.getInt("reliquias.especiales.campana-parca.fragmento-nivel-minimo", 40);
        if (CAMPANA.equals(esp)) {
            boolean fragmento = nivel >= minimo;
            if (fragmento) f.dato("+ {1} <Fragmento de Guadaña>");
            if (g == 4) f.dato(Ficha.probabilidad(c.getDouble("reliquias.especiales.campana-parca.llave-caos-iv", 0.25))
                    + " de una <Llave del Caos>");
            if (fragmento) usos(f, Ficha.usosDeCredito(c, "fragmento"), "Fragmento", "Fragmentos");
            else falta = "Solo la de una Parca de nivel {" + minimo + "} o más da un Fragmento de Guadaña.";
        } else if (LAGRIMA.equals(esp)) {
            boolean marca = valida || g == 4;
            if (marca) {
                f.dato("+ {1} <Marca de Eco>");
                f.dato("Máximo {" + c.getInt("eco.marcas.dia", 2) + "} Marcas al día");
            }
            if (g == 4) f.dato(Ficha.probabilidad(c.getDouble("reliquias.especiales.lagrima-eco.llave-caos-iv", 0.20))
                    + " de una <Llave del Caos>");
            if (marca) usos(f, Ficha.usosDeCredito(c, "marca"), "Marca", "Marcas");
            else falta = "Solo la de una caza válida da Marca de Eco.";
        } else if (SELLO.equals(esp)) {
            List<Ficha.Uso> u = minijefe == null || minijefe.isBlank() ? List.of()
                    : Ficha.usosDeCredito(c, "sello:" + minijefe.trim().toLowerCase(Locale.ROOT));
            if (u.isEmpty()) {
                f.seccion("Para qué sirve").dato("Vale como crédito en la Forja de Vael.");
            } else {
                f.seccion("Desbloquea en la Forja de Vael");
                for (Ficha.Uso x : u) f.dato("<" + x.da() + ">");
            }
        }

        f.hueco().nota(falta).nota(origen(origen, nivel, minijefe));
        if (unica) f.nota(caduca != null ? "Pieza única. Caduca el {" + caduca + "}." : "Pieza única: no se apila.");
        return f.nota(masculino ? "Si mueres sin venderlo, lo pierdes." : "Si mueres sin venderla, la pierdes.")
                .nota("Fuera de Calamity no se puede guardar.");
    }

    /** "◆ Para qué sirve" y una linea por pieza: " 7 Fragmentos · Guadaña de la Parca". */
    private static void usos(Ficha f, List<Ficha.Uso> usos, String uno, String varios) {
        if (usos.isEmpty()) return;
        f.seccion("Para qué sirve");
        for (Ficha.Uso u : usos) f.dato(Ficha.cantidad(u.cantidad(), uno, varios) + " · <" + u.da() + ">");
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

    /**
     * El material de serie de cada Reliquia. Rama lore-items (Dosa): la Astilla es una sandia reluciente, el
     * Fragmento de Nana un propagulo de mangle, el Ambar Coagulado un ladrillo de resina, el Ambar Mayor la
     * plantilla del adorno Rayo, la Eclipsada el disco Lava Chicken y la Lagrima un farol de cobre oxidado.
     * Todos tienen un uso vanilla (pociones, plantarse, crafteos, herreria, tocadiscos, colocarse) que se
     * corta en los bloqueos de abajo.
     *
     * Calamity 1.12.1 · El Ambar Mayor ya no ES la plantilla: la plantilla traia de serie "Smithing Template /
     * Applies to: Armor / Ingredients: Ingots and Crystals" en el tooltip y eso no se quita con componentes.
     * Ahora es PAPEL con el modelo de la plantilla (modelo(): componente minecraft:item_model), y se ve igual.
     * Papel porque es lo mas inerte que hay: no se coloca, no se come, no arde en el horno, no tiene texto
     * propio y no hace nada con clic derecho; solo sirve en crafteos, en la mesa de cartografia y en los
     * tratos de aldeano, y las tres cosas ya estan cortadas para cualquier Reliquia (onCraftear, onCrafter y
     * las ventanas de gasta()).
     */
    static String materialDeSerie(int g, String esp) {
        return switch (esp == null ? "" : esp) {
            case CAMPANA -> "BELL";
            case LAGRIMA -> "OXIDIZED_COPPER_LANTERN";
            case SELLO -> "FIRE_CHARGE";
            case ECLIPSADA -> "MUSIC_DISC_LAVA_CHICKEN";
            default -> switch (g) {
                case 1 -> "GLISTERING_MELON_SLICE";
                case 2 -> "MANGROVE_PROPAGULE";
                case 3 -> "RESIN_BRICK";
                default -> "PAPER";
            };
        };
    }

    /**
     * Calamity 1.12.1 · El modelo de serie (componente minecraft:item_model) de cada Reliquia, o null si se ve
     * como su material. Solo el Ambar Mayor: papel con la cara de la plantilla del adorno Rayo.
     */
    static String modeloDeSerie(int g, String esp) {
        return esp == null && g == 4 ? "minecraft:bolt_armor_trim_smithing_template" : null;
    }

    /**
     * El modelo de una Reliquia: el de la config (reliquias.grados.N.modelo, reliquias.especiales.X.modelo; vacio
     * es "sin modelo") o, si no lo pone y el material es el de serie, el de serie. Con otro material puesto a
     * mano y sin modelo, ninguno: que se vea lo que Dosa eligio. Un id sin espacio de nombres va a minecraft:.
     */
    static String modelo(ConfigurationSection c, int g, String esp) {
        String ruta = (esp != null ? "reliquias.especiales." + esp : "reliquias.grados." + g) + ".modelo";
        String m;
        if (c.isSet(ruta)) {
            m = c.getString(ruta, "");
        } else {
            Material def = Material.matchMaterial(materialDeSerie(g, esp));
            m = material(c, g, esp) == def ? modeloDeSerie(g, esp) : null;
        }
        if (m == null || m.isBlank()) return null;
        m = m.trim().toLowerCase(Locale.ROOT);
        if (!m.contains(":")) m = "minecraft:" + m;
        return m.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? m : null;
    }

    /** Le pone (o le quita, con null) el modelo a un objeto. Sin la API de componentes, nada. */
    static void ponerModelo(ItemStack it, String modelo) {
        try {
            if (modelo == null) it.resetData(DataComponentTypes.ITEM_MODEL);
            else it.setData(DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key(modelo));
        } catch (Throwable sinApi) {
            // Se ve como su material.
        }
    }

    /** El modelo que lleva puesto un objeto, o null si el de su material. */
    static String modeloDe(ItemStack it) {
        try {
            if (!it.isDataOverridden(DataComponentTypes.ITEM_MODEL)) return null;
            net.kyori.adventure.key.Key k = it.getData(DataComponentTypes.ITEM_MODEL);
            return k == null ? null : k.asString();
        } catch (Throwable sinApi) {
            return null;
        }
    }

    /** Los materiales que pone una config (grados 1-4, Campana, Lagrima, Sello, Eclipsada), para el autotest. */
    static List<String> materialesDeSerie(ConfigurationSection c) {
        List<String> out = new ArrayList<>();
        for (int g = 1; g <= 4; g++) out.add(c.getString("reliquias.grados." + g + ".material", "?"));
        for (String e : List.of(CAMPANA, LAGRIMA, SELLO, ECLIPSADA)) out.add(c.getString("reliquias.especiales." + e + ".material", "?"));
        return out;
    }

    /**
     * Los materiales de serie de antes: el de antes de la rama lore-items (materialViejo) y, para el Ambar
     * Mayor, la plantilla del adorno Rayo de la 1.11/1.12.0. Un config.yml instalado que ponga uno de estos
     * se lee como el de hoy.
     */
    static boolean esMaterialViejo(int g, String esp, String puesto) {
        if (puesto == null) return false;
        String p = puesto.trim();
        if (p.equalsIgnoreCase(materialViejo(g, esp))) return true;
        return esp == null && g == 4 && p.equalsIgnoreCase("BOLT_ARMOR_TRIM_SMITHING_TEMPLATE");
    }

    /** El material de serie de antes de la rama lore-items: el que traen las Reliquias que ya circulan. */
    static String materialViejo(int g, String esp) {
        return switch (esp == null ? "" : esp) {
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
    }

    /**
     * El material de una Reliquia: el de la config (reliquias.grados.N.material, reliquias.especiales.X.material)
     * o el de serie. Un config.yml ya instalado conserva los materiales de antes (Bukkit no los cambia): si lo
     * que pone es el material de serie VIEJO, vale el nuevo, como conTildes con los nombres. Si Dosa pone otro,
     * manda lo suyo.
     */
    static Material material(ConfigurationSection c, int g, String esp) {
        String def = materialDeSerie(g, esp);
        String ruta = esp != null ? "reliquias.especiales." + esp + ".material" : "reliquias.grados." + g + ".material";
        String puesto = c.getString(ruta, def);
        if (esMaterialViejo(g, esp, puesto)) puesto = def;
        Material m = puesto == null ? null : Material.matchMaterial(puesto.trim());
        if (m == null || !m.isItem() || m.isAir()) m = Material.matchMaterial(def);
        return m == null ? Material.GLISTERING_MELON_SLICE : m;
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
            // "valid" es lo que se escribe; "valida" (antes de la 1.12) sigue valiendo en lo guardado.
            if (t[i].equals("valid") || t[i].equals("valida")) {
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
     * ignea ni de material en NINGUN sitio, dentro o fuera de Calamity (desde la rama venta-oren
     * sale contigo). La comprobacion barata es la marca del objeto.
     */

    /**
     * Ninguna se coloca ni se planta: la Campana es una campana, el propagulo de la Nana se planta en barro o
     * tierra (y se lo comeria el suelo) y el farol de la Lagrima se cuelga. BlockMultiPlaceEvent tambien pasa.
     */
    @EventHandler(ignoreCancelled = true)
    public void onColocar(BlockPlaceEvent e) {
        if (es(e.getItemInHand())) e.setCancelled(true);
    }

    /**
     * El Sello es una carga ignea: con clic derecho prenderia fuego. Se niega el USO del
     * objeto y no el clic entero: con una Astilla en la mano se tiene que poder abrir un cofre.
     *
     * Rama lore-items: los bloques que se quedan lo que tienes en la mano al hacerles clic (la compostadora
     * se come el propagulo, el tocadiscos el disco, la maceta planta el propagulo) tampoco lo reciben: con
     * una Reliquia en la mano no se usan.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onUsar(PlayerInteractEvent e) {
        if (!e.getAction().isRightClick()) return;
        if (!es(e.getItem())) return;
        e.setUseItemInHand(Event.Result.DENY);
        if (e.getClickedBlock() != null && meteria(e.getClickedBlock())) e.setUseInteractedBlock(Event.Result.DENY);
    }

    /**
     * Si un clic con la Reliquia en la mano la meteria en ese bloque. Sacar lo que ya tiene si se puede: el
     * disco del tocadiscos, la flor de la maceta y el polvo de hueso de la compostadora llena (con algo en la
     * mano que no entra, vanilla hace lo mismo que con la mano vacia).
     */
    static boolean meteria(org.bukkit.block.Block b) {
        Material m = b.getType();
        if (!seLaQueda(m)) return false;
        if (m.name().startsWith("POTTED_")) return false;
        if (m == Material.JUKEBOX) return !(b.getState(false) instanceof org.bukkit.block.Jukebox j && j.hasRecord());
        if (m == Material.COMPOSTER) {
            return !(b.getBlockData() instanceof org.bukkit.block.data.Levelled l && l.getLevel() >= l.getMaximumLevel());
        }
        return true;
    }

    /** Bloques que se quedan (o gastan) el objeto de la mano al hacerles clic derecho. */
    static boolean seLaQueda(Material m) {
        if (m == null) return false;
        return m == Material.COMPOSTER || m == Material.JUKEBOX || m == Material.FLOWER_POT
                || m.name().startsWith("POTTED_") || m == Material.DECORATED_POT || m.name().endsWith("_SHELF");
    }

    /** La maceta tiene su propio evento en Paper: plantar el propagulo de la Nana en ella, no. */
    @EventHandler(ignoreCancelled = true)
    public void onMaceta(PlayerFlowerPotManipulateEvent e) {
        if (e.isPlacing() && es(e.getItem())) e.setCancelled(true);
    }

    /**
     * Un allay se queda lo que le das y luego recoge del suelo todo lo que se le parezca: ni una Reliquia ni una
     * Esencia, en ningun mundo y tampoco el staff (Sellos lo corta solo con su sello y no al staff). Con los
     * animales no hace falta nada: ningun material de Reliquia es comida, y montar o sentar a uno con una en la
     * mano tiene que funcionar.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAllay(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Allay)) return;
        if (valioso(e.getPlayer().getInventory().getItem(e.getHand()))) e.setCancelled(true);
    }

    /**
     * Ninguna entidad que no sea un jugador recoge una Reliquia o una Esencia del suelo, dentro ni fuera de
     * Calamity: piglins (la sandia y la campana les encantan), zorros, allays, mobs que recogen cosas, tolvas
     * aparte (eso es Sellos). Dentro ya lo cortaba Hardcore.onRecoger; fuera solo con el sello de fuera.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRecogerMob(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player) return;
        if (valioso(e.getItem().getItemStack())) e.setCancelled(true);
    }

    /** Reliquia o Esencia. */
    private boolean valioso(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        if (es(it)) return true;
        ItemsCalamity items = hc.items();
        return items != null && items.esEsencia(it);
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

    /** El crafter (autocrafteo con tolvas) no pasa por PrepareItemCraftEvent: la plantilla se duplicaria ahi. */
    @EventHandler(ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent e) {
        if (e.getBlock().getState(false) instanceof org.bukkit.block.Crafter cr && hayReliquia(cr.getInventory())) {
            e.setCancelled(true);
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

    /** El propagulo de la Nana arde como un brote: no se quema de combustible. */
    @EventHandler(ignoreCancelled = true)
    public void onArder(FurnaceBurnEvent e) {
        if (es(e.getFuel())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDestilar(BrewEvent e) {
        if (hayReliquia(e.getContents())) e.setCancelled(true);
    }

    /** Un dispensador no la dispara, no la planta ni la pone en ningun sitio. */
    @EventHandler(ignoreCancelled = true)
    public void onDispensar(BlockDispenseEvent e) {
        if (es(e.getItem())) e.setCancelled(true);
    }

    /**
     * Las ventanas que transforman o se quedan lo que les metes (soporte de pociones, hornos, herreria, yunque,
     * telar, crafter, compostadora, tocadiscos, aldeanos...): una Reliquia no entra, ni por clic, ni con
     * mayusculas, ni con la tecla numerica. Sellos ya lo corta a los jugadores, pero el staff se lo salta y el
     * sello se puede apagar: esto no, porque no es guardarla sino gastarla.
     */
    static boolean gasta(InventoryType t) {
        return t != null && Gastan.TIPOS.contains(t);
    }

    /** Aparte, para que cargar Reliquias no cargue InventoryType (fuera del servidor no tiene registros). */
    private static final class Gastan {
        static final Set<InventoryType> TIPOS = java.util.EnumSet.of(InventoryType.BREWING, InventoryType.FURNACE,
                InventoryType.BLAST_FURNACE, InventoryType.SMOKER, InventoryType.SMITHING, InventoryType.ANVIL,
                InventoryType.GRINDSTONE, InventoryType.LOOM, InventoryType.STONECUTTER, InventoryType.CARTOGRAPHY,
                InventoryType.ENCHANTING, InventoryType.BEACON, InventoryType.CRAFTER, InventoryType.COMPOSTER,
                InventoryType.JUKEBOX, InventoryType.MERCHANT);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMeter(InventoryClickEvent e) {
        if (!gasta(e.getView().getTopInventory().getType())) return;
        if (es(Sellos.entraArriba(e))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArrastrar(InventoryDragEvent e) {
        if (gasta(e.getView().getTopInventory().getType()) && es(e.getOldCursor()) && Sellos.tocaArriba(e)) {
            e.setCancelled(true);
        }
    }

    /** Tolvas y soltadores: nunca a una de esas (el disco al tocadiscos, el propagulo a la compostadora...). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTolva(InventoryMoveItemEvent e) {
        if (gasta(e.getDestination().getType()) && es(e.getItem())) e.setCancelled(true);
    }

    private boolean hayReliquia(Inventory inv) {
        for (ItemStack it : inv.getContents()) if (es(it)) return true;
        return false;
    }

    /**
     * Lo que el material trae de serie y una Reliquia no debe hacer, quitado del objeto: el disco sin
     * cancion (el tocadiscos no lo acepta), el ladrillo de resina sin material de adorno (la herreria no lo
     * usa). Lo llaman crear y renovar, asi una renovada y una nueva tienen los mismos componentes y se apilan.
     */
    static void desactivar(ItemStack it) {
        try {
            it.unsetData(DataComponentTypes.JUKEBOX_PLAYABLE);
            it.unsetData(DataComponentTypes.PROVIDES_TRIM_MATERIAL);
        } catch (Throwable sinApi) {
            // Sin la API de componentes quedan los eventos de arriba.
        }
    }

    /** Si aun lleva algo de lo que desactivar quita (una creada antes de que crear lo llamara). */
    static boolean conUsos(ItemStack it) {
        try {
            return it.hasData(DataComponentTypes.JUKEBOX_PLAYABLE) || it.hasData(DataComponentTypes.PROVIDES_TRIM_MATERIAL);
        } catch (Throwable sinApi) {
            return false;
        }
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

    // ------------------------------------------------------- fuera de Calamity

    /**
     * Una Reliquia con la plantilla de hoy, leyendo lo que guarda su marca, o null si ya esta al dia. Rama
     * venta-oren: las que ya circulaban decian "se vende sola al salir"; con esto dicen lo de ahora y se
     * apilan con las nuevas.
     *
     * Rama lore-items: ademas del lore, el nombre (con el degradado de su Tono) y el MATERIAL. Una Astilla
     * de prismarina pasa a sandia reluciente, una Nana de fragmento de disco a propagulo, etc. withType copia
     * todos los datos del objeto (marca, id, origen, nacimiento, nivel, brillo) y la cantidad; luego se le
     * quita lo que el material nuevo trae de serie (desactivar), igual que a una recien creada, asi que una
     * Astilla renovada y una nueva son el mismo objeto y se apilan.
     */
    ItemStack renovada(ItemStack it) {
        if (!es(it)) return null;
        ConfigurationSection c = hc.cfg();
        int g = Math.max(1, Math.min(4, grado(it)));
        String esp = especial(it);
        String id = id(it);
        boolean apilable = id == null;
        long n = nacio(it);
        Material quiere = material(c, g, esp);
        String modelo = modelo(c, g, esp);
        List<Component> lore = ficha(c, g, esp, nivel(it), minijefe(it), valida(it),
                apilable ? null : leer(it, Marcas.RELIQUIA_ORIGEN, PersistentDataType.STRING), !apilable,
                apilable || n <= 0 ? null : fechaCorta(n + caducaMillis())).lore();
        Component nombre = tono(g, esp).nombre(nombre(c, g, esp, minijefe(it)));
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return null;
        if (it.getType() == quiere && !conUsos(it) && java.util.Objects.equals(modelo, modeloDe(it))
                && Ficha.iguales(lore, meta.lore()) && Ficha.igual(nombre, meta.displayName())) return null;
        ItemStack r = it.getType() == quiere ? it.clone() : it.withType(quiere);
        r.editMeta(m -> {
            m.displayName(nombre);
            m.lore(lore);
        });
        desactivar(r);
        ponerModelo(r, modelo);
        return r;
    }

    /**
     * Los objetos de Calamity de su inventario (armadura y mano izquierda incluidas), con el nombre, el lore
     * y el material de hoy: Reliquias, Esencias, Frascos, Cristales, Fragmentos de Masamune, Reclamos,
     * Talismanes, Grabados, Salvoconductos, llaves de boveda y cabezas de Eco. Al entrar al servidor, al
     * cambiar de mundo y al abrir el menu de Oren.
     */
    void renovarInventario(Player p) {
        if (p == null) return;
        ItemsCalamity items = hc.items();
        Entregas en = hc.entregas();
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir() || !it.hasItemMeta()) continue;
            ItemStack r = renovada(it);
            if (r == null && items != null) r = items.renovado(it);
            if (r == null && en != null) r = en.renovado(it);
            if (r == null) r = PuenteBovedas.renovada(it);
            if (r == null) r = Ecos.trofeoRenovado(it);
            if (r != null) inv.setItem(i, r);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntrarServidor(PlayerJoinEvent e) {
        hc.seguro("reliquias", () -> renovarInventario(e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCambiarMundo(PlayerChangedWorldEvent e) {
        hc.seguro("reliquias", () -> renovarInventario(e.getPlayer()));
    }

    /*
     * Rama venta-oren: aqui vivia "fuera no existen" (deshacerFuera al cambiar de mundo, al entrar al
     * servidor y al abrir un inventario fuera). Ya no: la Reliquia sale contigo y se le vende a Oren.
     * Que no se guarde ni se venda fuera de Calamity lo vigila Sellos.
     */

    // ----------------------------------------------------------------- comando

    /** /calamity relic <1-4> [player] [special]: la emite (origen admin) y la entrega la Aduana. */
    private void comando(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(ComandoCalamity.mensaje("Uso: /calamity relic <1-4> [player] [special]"));
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
                        "No conozco ese especial. Valen: campana[:N], lagrima[:N][:valid], sello:<miniboss>, eclipsada o mayor."));
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
            List<String> op = new ArrayList<>(List.of("campana:50", "lagrima:50:valid", "eclipsada", "mayor"));
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
        h.igual("material de la I", Material.GLISTERING_MELON_SLICE, material(vacia, 1, null));
        h.igual("material de la II", Material.MANGROVE_PROPAGULE, material(vacia, 2, null));
        h.igual("material de la III", Material.RESIN_BRICK, material(vacia, 3, null));
        h.igual("material de la IV: papel", Material.PAPER, material(vacia, 4, null));
        h.igual("modelo de la IV: la plantilla del adorno Rayo", "minecraft:bolt_armor_trim_smithing_template", modelo(vacia, 4, null));
        h.igual("las demas sin modelo", null, modelo(vacia, 3, null));
        h.igual("la Campana sin modelo", null, modelo(vacia, 4, CAMPANA));
        h.igual("material de la Campana", Material.BELL, material(vacia, 4, CAMPANA));
        h.igual("material de la Lagrima", Material.OXIDIZED_COPPER_LANTERN, material(vacia, 4, LAGRIMA));
        h.igual("material del Sello", Material.FIRE_CHARGE, material(vacia, 4, SELLO));
        h.igual("material de la Eclipsada", Material.MUSIC_DISC_LAVA_CHICKEN, material(vacia, 3, ECLIPSADA));
        // Un config.yml ya instalado trae los materiales de antes: valen los nuevos; uno puesto a mano, manda.
        YamlConfiguration vieja = new YamlConfiguration();
        vieja.set("reliquias.grados.1.material", "PRISMARINE_SHARD");
        vieja.set("reliquias.grados.4.material", "RESIN_CLUMP");
        vieja.set("reliquias.especiales.lagrima-eco.material", "ECHO_SHARD");
        vieja.set("reliquias.grados.2.material", "AMETHYST_SHARD");
        h.igual("config vieja: la I pasa a la sandia", Material.GLISTERING_MELON_SLICE, material(vieja, 1, null));
        h.igual("config vieja: la IV pasa al papel", Material.PAPER, material(vieja, 4, null));
        YamlConfiguration v112 = new YamlConfiguration();
        v112.set("reliquias.grados.4.material", "BOLT_ARMOR_TRIM_SMITHING_TEMPLATE");
        h.igual("config de la 1.12.0 (plantilla, sin modelo): papel", Material.PAPER, material(v112, 4, null));
        h.igual("config de la 1.12.0: con el modelo de la plantilla", "minecraft:bolt_armor_trim_smithing_template", modelo(v112, 4, null));
        YamlConfiguration otra = new YamlConfiguration();
        otra.set("reliquias.grados.4.material", "AMETHYST_SHARD");
        h.igual("otro material a mano y sin modelo: sin modelo", null, modelo(otra, 4, null));
        otra.set("reliquias.grados.4.modelo", "echo_shard");
        h.igual("modelo sin espacio de nombres: minecraft:", "minecraft:echo_shard", modelo(otra, 4, null));
        otra.set("reliquias.grados.4.modelo", "");
        h.igual("modelo vacio: ninguno", null, modelo(otra, 4, null));
        otra.set("reliquias.especiales.eclipsada.modelo", "minecraft:music_disc_5");
        h.igual("una especial tambien acepta modelo", "minecraft:music_disc_5", modelo(otra, 3, ECLIPSADA));
        otra.set("reliquias.grados.3.modelo", "Mal Modelo!");
        h.igual("modelo que no es un id: ninguno", null, modelo(otra, 3, null));
        h.igual("config de serie: el modelo de la IV", "minecraft:bolt_armor_trim_smithing_template",
                Ficha.deSerie().getString("reliquias.grados.4.modelo"));
        h.igual("config vieja: la Lagrima pasa al farol", Material.OXIDIZED_COPPER_LANTERN, material(vieja, 4, LAGRIMA));
        h.igual("config con otro material: manda", Material.AMETHYST_SHARD, material(vieja, 2, null));
        h.igual("config de serie: los materiales nuevos", List.of("GLISTERING_MELON_SLICE", "MANGROVE_PROPAGULE",
                "RESIN_BRICK", "PAPER", "BELL", "OXIDIZED_COPPER_LANTERN", "FIRE_CHARGE",
                "MUSIC_DISC_LAVA_CHICKEN"), materialesDeSerie(Ficha.deSerie()));
        h.ok("bloques que se quedan la mano: compostadora, tocadiscos y maceta", seLaQueda(Material.COMPOSTER)
                && seLaQueda(Material.JUKEBOX) && seLaQueda(Material.FLOWER_POT) && seLaQueda(Material.POTTED_MANGROVE_PROPAGULE)
                && !seLaQueda(Material.CHEST));
        h.ok("ventanas que gastan: soporte, herreria, crafter, tocadiscos y compostadora",
                gasta(InventoryType.BREWING) && gasta(InventoryType.SMITHING) && gasta(InventoryType.CRAFTER)
                        && gasta(InventoryType.JUKEBOX) && gasta(InventoryType.COMPOSTER) && gasta(InventoryType.FURNACE)
                        && !gasta(InventoryType.CHEST));
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
        h.ok("la I sale sandia", astilla.getType() == Material.GLISTERING_MELON_SLICE);
        // Una Astilla de antes (prismarina, nombre y lore viejos) se renueva: material nuevo, mismos datos, se apila.
        ItemStack vieja1 = astilla.withType(Material.PRISMARINE_SHARD);
        vieja1.setAmount(7);
        vieja1.editMeta(mm -> {
            mm.displayName(Component.text("Astilla del Umbral"));
            mm.lore(List.of(Component.text("────────"), Component.text("Se vende sola al salir")));
        });
        ItemStack nueva1 = renovada(vieja1);
        h.ok("renovada: pasa a sandia y conserva cantidad y marca", nueva1 != null && nueva1.getType() == Material.GLISTERING_MELON_SLICE
                && nueva1.getAmount() == 7 && grado(nueva1) == 1);
        h.ok("renovada: se apila con una nueva", nueva1 != null && nueva1.isSimilar(astilla));
        h.igual("una al dia no se renueva", null, renovada(astilla));
        // Una Eclipsada de antes (ambar de resina, con su id), hecha a mano: crear() la apuntaria en reliquias.log.
        String idFalso = UUID.randomUUID().toString();
        ItemStack ambarViejo = astilla.withType(Material.RESIN_CLUMP);
        ambarViejo.editMeta(mm -> {
            mm.getPersistentDataContainer().set(Marcas.RELIQUIA, PersistentDataType.INTEGER, 3);
            mm.getPersistentDataContainer().set(Marcas.RELIQUIA_ID, PersistentDataType.STRING, idFalso);
            mm.getPersistentDataContainer().set(Marcas.RELIQUIA_ESPECIAL, PersistentDataType.STRING, ECLIPSADA);
            mm.getPersistentDataContainer().set(Marcas.RELIQUIA_NACIO, PersistentDataType.LONG, System.currentTimeMillis());
        });
        ItemStack ambarNuevo = renovada(ambarViejo);
        h.ok("una especial de antes pasa al disco, sin cancion y con su id", ambarNuevo != null
                && idFalso.equals(id(ambarNuevo)) && ambarNuevo.getType() == Material.MUSIC_DISC_LAVA_CHICKEN
                && !ambarNuevo.hasData(DataComponentTypes.JUKEBOX_PLAYABLE) && ECLIPSADA.equals(especial(ambarNuevo)));
        ItemStack disco = new ItemStack(Material.MUSIC_DISC_LAVA_CHICKEN), ladrillo = new ItemStack(Material.RESIN_BRICK);
        boolean antes = conUsos(disco) && conUsos(ladrillo);
        desactivar(disco);
        desactivar(ladrillo);
        h.ok("desactivar: el disco sin cancion y el ladrillo sin adorno", antes && !conUsos(disco) && !conUsos(ladrillo)
                && !conUsos(astilla));
        h.ok("lore sin rayas ni negrita", Ficha.faltas(astilla.getItemMeta().lore()).isEmpty());
        // Calamity 1.12.1 · Un Ambar Mayor de la 1.12.0 (la plantilla) pasa a papel con el modelo, conserva sus
        // datos y no se vuelve a renovar; dos papeles con el mismo modelo apilan.
        ItemStack plantilla = astilla.withType(Material.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE);
        plantilla.setAmount(2);
        plantilla.editMeta(mm -> mm.getPersistentDataContainer().set(Marcas.RELIQUIA, PersistentDataType.INTEGER, 4));
        ItemStack mayor = renovada(plantilla);
        h.ok("Ambar Mayor de la 1.12.0: pasa a papel con el modelo de la plantilla y conserva cantidad y grado",
                mayor != null && mayor.getType() == Material.PAPER && mayor.getAmount() == 2 && grado(mayor) == 4
                        && "minecraft:bolt_armor_trim_smithing_template".equals(modeloDe(mayor)));
        h.ok("Ambar Mayor renovado: ya no se vuelve a renovar", mayor != null && renovada(mayor) == null);
        ItemStack papel = new ItemStack(Material.PAPER), papel2 = new ItemStack(Material.PAPER);
        ponerModelo(papel, "minecraft:bolt_armor_trim_smithing_template");
        ponerModelo(papel2, "minecraft:bolt_armor_trim_smithing_template");
        h.ok("dos papeles con el mismo modelo apilan; sin modelo no", papel.isSimilar(papel2)
                && !papel.isSimilar(new ItemStack(Material.PAPER)));
        // Los demas objetos que ya circulan: una Esencia y una cabeza de Eco con el nombre y el lore de la 1.10.
        ItemsCalamity items = hc.items();
        if (items != null) {
            ItemStack esencia = items.esencia(3), viejaE = esencia.clone();
            viejaE.editMeta(mm -> {
                mm.displayName(Component.text("Esencia de Calamidad", TextColor.color(0xE8903C)));
                mm.lore(List.of(Component.text("Moneda de Calamity"), Component.text("────────")));
            });
            ItemStack renovadaE = items.renovado(viejaE);
            h.ok("Esencia de antes: renovada, se apila con una nueva", renovadaE != null && renovadaE.isSimilar(esencia)
                    && renovadaE.getAmount() == 3);
            h.igual("Esencia al dia: no se toca", null, items.renovado(esencia));
            ItemStack frasco = Ligado.ligar(items.frasco(2), Autotest.sintetico(52));
            frasco.editMeta(mm -> {
                List<Component> l = new ArrayList<>(List.of(Component.text("────")));
                l.add(Component.text("Ligado a Dosa · no se vende ni se cambia"));
                mm.lore(l);
            });
            ItemStack frascoNuevo = items.renovado(frasco);
            h.ok("Frasco de antes: lore de hoy, conserva tragos y la linea de ligado", frascoNuevo != null
                    && items.tragos(frascoNuevo) == 2 && "Dosa".equals(Ficha.ligadoDe(frascoNuevo.getItemMeta().lore()))
                    && Ficha.faltas(frascoNuevo.getItemMeta().lore()).isEmpty());
            // Beber y recargar: el mismo frasco con otros tragos, sin perder lo prestado ni el ligado.
            UUID duenoPrueba = Autotest.sintetico(53);
            ItemStack delKit = Kit.prestar(Ligado.ligar(items.frasco(3), duenoPrueba));
            delKit.setAmount(2);
            ItemStack bebido = items.conTragos(delKit, 2), lleno = items.conTragos(bebido, 3);
            List<Component> lb = bebido.getItemMeta().lore();
            h.ok("frasco bebido: un trago menos, una unidad, sigue prestado y ligado", items.tragos(bebido) == 2
                    && bebido.getAmount() == 1 && Kit.esPrestado(bebido) && duenoPrueba.equals(Ligado.duenoDe(bebido))
                    && lb != null && "Se deshace al salir de Calamity.".equals(Hardcore.plano(lb.get(lb.size() - 1))));
            h.ok("frasco recargado: lleno, sigue prestado y ligado", items.tragos(lleno) == 3 && Kit.esPrestado(lleno)
                    && duenoPrueba.equals(Ligado.duenoDe(lleno)) && delKit.getAmount() == 2);
        }
        ItemStack cabeza = new ItemStack(Material.PLAYER_HEAD);
        cabeza.editMeta(mm -> {
            mm.displayName(Component.text("Cabeza de Otro"));
            mm.lore(List.of(Component.text("Trofeo · Eco derrotado"), Component.text("────"),
                    Component.text("Lo derrotó Dosa"), Component.text("El 04/10/2026")));
            mm.getPersistentDataContainer().set(Marcas.TROFEO, PersistentDataType.BYTE, (byte) 1);
        });
        ItemStack cabezaNueva = Ecos.trofeoRenovado(cabeza);
        h.ok("cabeza de Eco de antes: lore de hoy con cazador y fecha", cabezaNueva != null
                && cabezaNueva.getItemMeta().lore().stream().anyMatch(c -> Hardcore.plano(c).equals(" Dosa, el 04/10/2026"))
                && Ecos.trofeoRenovado(cabezaNueva) == null);
        h.ok("una espada no es Reliquia", !es(new ItemStack(Material.DIAMOND_SWORD)));
        h.igual("grado de lo que no es Reliquia", 0, grado(new ItemStack(Material.STONE)));
        return h.lineas();
    }
}
