package net.ederus.calamity.hardcore;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.LodestoneTracker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calamity 1.12.2 · El Barometro: dice cuanto falta para el proximo cambio de clima y que clima trae al bioma
 * donde esta el jugador. NO toca el clima: solo lee el reloj de CicloClima (fase, cuando acaba, cual viene) y la
 * tabla por-bioma de Clima (que se ve en ese bioma cuando llueve en el ciclo).
 *
 *   "Barómetro · Despejado. Lluvia ácida en 4 min."
 *   "Barómetro · Tormenta. Escampa en 2 min."
 *   "Barómetro · Despejado. En este bioma no cambia el tiempo."   (un bioma sin clima propio ni temporal)
 *   "Barómetro · Aquí no marca nada."                              (fuera de Calamity o con el ciclo apagado)
 *
 * Se consulta con clic derecho (al aire o a un bloque, con cualquier mano: desde Bedrock el uso llega siempre
 * como clic al aire), con un enfriamiento corto (barometro.enfriamiento-segundos) en el que los clics se
 * ignoran sin decir nada. No se gasta. Es una herramienta, no una Reliquia: se puede sacar de Calamity y guardar
 * donde sea, y Oren no la compra (Tasacion solo compra lo marcado como Reliquia o Esencia).
 *
 * El objeto: una BRUJULA (COMPASS) con la marca lethal_world:barometro y un lodestone_tracker sin destino, que
 * hace que la aguja gire sin parar (la de una brujula de magnetita sin magnetita). Por que una brujula:
 *  - se ve como un instrumento de esfera con aguja, que es lo que es un barometro, en Java y en Bedrock (el
 *    componente item_model no llega a Bedrock: un objeto inerte con el modelo de otro se veria como papel);
 *  - el Reloj ya es el Talisman de Vigilia; el catalejo hace zoom en el cliente al usarlo, y eso no se corta
 *    desde el servidor; la brujula de recuperacion apunta a donde moriste, y en Calamity eso es tu Eco;
 *  - sin destino la aguja no apunta a nada (ni al spawn ni a una magnetita): no sirve para orientarse, y el
 *    lore lo cuenta como parte del objeto ("la aguja no busca el norte").
 * Lo que una brujula hace en vanilla y aqui se corta: atarse a una magnetita (se niega el uso del objeto),
 * entrar en un crafteo (mapas, brujula de recuperacion; tambien en el crafter) y vendersela a un cartografo
 * (no entra en la ventana de un aldeano).
 */
final class Barometro implements Listener {

    /** El id de /calamity give y de "dar:" en altar.trueques. */
    static final String OBJETO = "barometer";
    static final String NOMBRE = "Barómetro";
    static final int ENFRIAMIENTO = 3;

    private final Hardcore hc;
    /** El ultimo clic que conto, por jugador (millis): el enfriamiento. */
    private final Map<UUID, Long> ultimo = new HashMap<>();

    Barometro(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("barometro", () -> autotest(Ficha.cfg()));
    }

    void parar() {
        HandlerList.unregisterAll(this);
        ultimo.clear();
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = hc.cfg().getConfigurationSection("barometro");
        return s == null ? new YamlConfiguration() : s;
    }

    // ------------------------------------------------------------------ el objeto

    /** Un Barometro nuevo (sin ligar: lo liga Entregas). */
    static ItemStack crear() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Ficha.tono("barometro").nombre(NOMBRE));
            meta.lore(ficha(Ficha.cfg()).lore());
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(Marcas.BAROMETRO, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        try {
            // Sin destino: la aguja gira sola y la brujula no se puede usar para orientarse.
            item.setData(DataComponentTypes.LODESTONE_TRACKER, LodestoneTracker.lodestoneTracker(null, false));
        } catch (Throwable sinApi) {
            // Sin la API de componentes es una brujula normal: apunta al spawn del mundo, nada mas.
        }
        return item;
    }

    /** Si es un Barometro (por la marca: da igual como se llame). */
    static boolean es(ItemStack it) {
        return Marcas.tiene(it, Marcas.BAROMETRO);
    }

    /** El lore, con la plantilla comun. Pura: la prueba el autotest "fichas". */
    static Ficha ficha(ConfigurationSection c) {
        int espera = enfriamiento(c);
        Ficha f = new Ficha(Ficha.tono("barometro")).cabecera("Instrumento", "Clima", 0)
                .historia("La aguja no busca el norte: tiembla con el cielo de Calamity.")
                .seccion("Al consultarlo")
                .dato("El tiempo que hace en tu bioma.")
                .dato("Qué viene después y cuánto falta.");
        if (espera > 0) f.dato("Se puede mirar cada {" + espera + "} s.");
        return f.accion("Clic derecho para consultarlo.")
                .hueco().nota("Solo marca en Calamity.").nota("No se gasta.");
    }

    /** El mismo Barometro con el nombre y el lore de hoy (o null si ya los lleva). Conserva la linea de ligado. */
    static ItemStack renovado(ItemStack it) {
        if (!es(it)) return null;
        return Ficha.renovar(it, Ficha.tono("barometro").nombre(NOMBRE), ficha(Ficha.cfg()).lore());
    }

    static int enfriamiento(ConfigurationSection c) {
        return Math.max(0, Math.min(60, c.getInt("barometro.enfriamiento-segundos", ENFRIAMIENTO)));
    }

    // ------------------------------------------------------------------ el uso

    /**
     * Sin ignoreCancelled, como el Frasco y el Reclamo: el clic al aire llega ya cancelado. Se niega siempre el uso
     * vanilla (atarla a una magnetita) y, en la mano que lo lleva, el bloque: el clic es del Barometro.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onUsar(PlayerInteractEvent e) {
        if (!e.getAction().isRightClick()) return;
        if (!es(e.getItem())) return;
        e.setUseItemInHand(Event.Result.DENY);
        e.setUseInteractedBlock(Event.Result.DENY);
        Player p = e.getPlayer();
        long ahora = System.currentTimeMillis();
        if (!aTiempo(ultimo.get(p.getUniqueId()), ahora, enfriamiento(hc.cfg()) * 1000L)) return;
        ultimo.put(p.getUniqueId(), ahora);
        hc.seguro("barometro", () -> consultar(p, ahora));
    }

    private void consultar(Player p, long ahora) {
        CicloClima.Lectura l = null;
        Clima.Tipo tipo = Clima.Tipo.NINGUNO;
        Clima clima = hc.clima();
        if (clima != null && hc.esHardcore(p)) {
            l = clima.ciclo().lectura(p.getWorld());
            tipo = clima.tipoAhora(p);
        }
        Component linea = linea(cfg(), l, tipo, ahora);
        ConfigurationSection c = cfg();
        if (c.getBoolean("chat", true)) p.sendMessage(linea);
        if (c.getBoolean("barra", true)) hc.cordura().destello(p, linea, 4);
        Marco.sonar(p, "item.spyglass.use", 0.6f, 1.3f);
    }

    @EventHandler
    public void onSalir(PlayerQuitEvent e) {
        ultimo.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ bloqueos de la brujula

    @EventHandler
    public void onCraftear(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (es(it)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    /** El crafter (autocrafteo con tolvas) no pasa por PrepareItemCraftEvent. */
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

    /** El cartografo compra brujulas: un Barometro no entra en la ventana de un aldeano ni en la de un crafter. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMeter(InventoryClickEvent e) {
        if (!noEntra(e.getView().getTopInventory().getType())) return;
        if (es(Sellos.entraArriba(e))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArrastrar(InventoryDragEvent e) {
        if (noEntra(e.getView().getTopInventory().getType()) && es(e.getOldCursor()) && Sellos.tocaArriba(e)) {
            e.setCancelled(true);
        }
    }

    static boolean noEntra(InventoryType t) {
        return t == InventoryType.MERCHANT || t == InventoryType.CRAFTER;
    }

    // ------------------------------------------------------------------ el nucleo (puro)

    /** Si cuenta un clic: el primero, o pasado el enfriamiento desde el ultimo que conto. */
    static boolean aTiempo(Long antes, long ahora, long esperaMs) {
        return antes == null || ahora - antes >= esperaMs || ahora < antes;
    }

    /**
     * Como se llama lo que se ve en ese bioma en esa fase, o null si nada (despejado, o un bioma sin clima). El
     * temporal (los biomas de Panacea sin clima propio) distingue lluvia y tormenta; los demas son su clima en las
     * dos fases (la tormenta no les cambia lo que se ve).
     */
    static String clima(ConfigurationSection b, String fase, Clima.Tipo tipo) {
        if (!CicloClima.llueveEnFase(fase) || tipo == null || tipo == Clima.Tipo.NINGUNO) return null;
        return switch (tipo) {
            case ACIDA -> texto(b, "lluvia-acida", "Lluvia ácida");
            case ROJO -> texto(b, "cielo-rojo", "Cielo rojo");
            case ESPORAS -> texto(b, "esporas", "Esporas");
            case POLEN -> texto(b, "polinizacion", "Polinización");
            case CENIZA -> texto(b, "ceniza", "Ceniza");
            default -> CicloClima.TORMENTA.equals(fase) ? texto(b, "tormenta", "Tormenta") : texto(b, "lluvia", "Lluvia");
        };
    }

    private static String texto(ConfigurationSection b, String clave, String serie) {
        String s = b == null ? null : b.getString("textos." + clave);
        return s == null || s.isBlank() ? serie : s;
    }

    /**
     * La linea entera: "Barómetro · " y lo que marca. l null = fuera de Calamity (o sin reloj). Los nombres de
     * clima van en el color de su clima, el tiempo en el de las cifras, lo demas en el normal.
     */
    static Component linea(ConfigurationSection b, CicloClima.Lectura l, Clima.Tipo tipo, long ahora) {
        Paleta.Tono tono = Ficha.tono("barometro");
        TextComponent.Builder out = Component.text()
                .append(Component.text(NOMBRE, tono.medio()))
                .append(Component.text(" · ", Paleta.SEPARADOR));
        Clima.Tipo t = tipo == null ? Clima.Tipo.NINGUNO : tipo;
        if (l == null) {
            out.append(pintar(texto(b, "fuera", "Aquí no marca nada."), Map.of()));
        } else if (t == Clima.Tipo.NINGUNO) {
            out.append(pintar(texto(b, "sin-clima", "{ahora}. En este bioma no cambia el tiempo."),
                    Map.of("ahora", Component.text(texto(b, "despejado", "Despejado"), Paleta.TEXTO))));
        } else {
            TextColor color = Clima.color(t);
            String ahoraN = clima(b, l.fase(), t);
            Component ahoraC = ahoraN == null ? Component.text(texto(b, "despejado", "Despejado"), Paleta.TEXTO)
                    : Component.text(ahoraN, color);
            Component tiempo = Component.text(CicloClima.restante(l.hasta() - ahora), Paleta.CIFRA);
            if (ahoraN == null) {
                String prox = clima(b, l.proxima(), t);
                if (prox == null) prox = texto(b, "lluvia", "Lluvia");
                out.append(pintar(texto(b, "llega", "{ahora}. {proximo} en {tiempo}."),
                        Map.of("ahora", ahoraC, "proximo", Component.text(prox, color), "tiempo", tiempo)));
            } else {
                out.append(pintar(texto(b, "escampa", "{ahora}. Escampa en {tiempo}."),
                        Map.of("ahora", ahoraC, "tiempo", tiempo)));
            }
        }
        return out.build().decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }

    /**
     * Una plantilla con {huecos}: cada hueco por su trozo ya pintado y lo demas en el color normal. Un hueco que no
     * esta en "valores" se deja escrito tal cual. Una plantilla que empieza por un hueco en minuscula ("{proximo}
     * en...") lo pone con mayuscula.
     */
    static Component pintar(String plantilla, Map<String, Component> valores) {
        TextComponent.Builder out = Component.text();
        StringBuilder suelto = new StringBuilder();
        int i = 0;
        while (i < plantilla.length()) {
            char ch = plantilla.charAt(i);
            int fin = ch == '{' ? plantilla.indexOf('}', i) : -1;
            if (fin > i) {
                String clave = plantilla.substring(i + 1, fin);
                Component v = valores.get(clave);
                if (v != null) {
                    if (suelto.length() > 0) out.append(Component.text(suelto.toString(), Paleta.TEXTO));
                    suelto.setLength(0);
                    out.append(v);
                    i = fin + 1;
                    continue;
                }
            }
            suelto.append(ch);
            i++;
        }
        if (suelto.length() > 0) out.append(Component.text(suelto.toString(), Paleta.TEXTO));
        return out.build();
    }

    // ------------------------------------------------------------------ autotest

    static List<String> autotest(ConfigurationSection hardcore) {
        Autotest.Hoja h = new Autotest.Hoja();
        ConfigurationSection b = hardcore == null ? null : hardcore.getConfigurationSection("barometro");
        PlainTextComponentSerializer plano = PlainTextComponentSerializer.plainText();
        long ahora = 1_000_000_000L;
        java.util.function.BiFunction<CicloClima.Lectura, Clima.Tipo, String> leer =
                (l, t) -> plano.serialize(linea(b, l, t, ahora));
        CicloClima.Lectura despejado = new CicloClima.Lectura(CicloClima.DESPEJADO, ahora + 3 * 60_000L + 5_000, CicloClima.LLUVIA);
        CicloClima.Lectura antesTormenta = new CicloClima.Lectura(CicloClima.DESPEJADO, ahora + 45_000, CicloClima.TORMENTA);
        CicloClima.Lectura lluvia = new CicloClima.Lectura(CicloClima.LLUVIA, ahora + 2 * 60_000L, CicloClima.DESPEJADO);
        CicloClima.Lectura tormenta = new CicloClima.Lectura(CicloClima.TORMENTA, ahora + 90_000, CicloClima.DESPEJADO);

        h.igual("despejado en un bioma verde: viene lluvia acida", "Barómetro · Despejado. Lluvia ácida en 4 min.",
                leer.apply(despejado, Clima.Tipo.ACIDA));
        h.igual("despejado en el temporal: viene lluvia", "Barómetro · Despejado. Lluvia en 4 min.",
                leer.apply(despejado, Clima.Tipo.GENERICO));
        h.igual("despejado en el temporal, la proxima es tormenta", "Barómetro · Despejado. Tormenta en 45 s.",
                leer.apply(antesTormenta, Clima.Tipo.GENERICO));
        h.igual("tormenta en el temporal: escampa", "Barómetro · Tormenta. Escampa en 2 min.",
                leer.apply(tormenta, Clima.Tipo.GENERICO));
        h.igual("lluvia en el temporal", "Barómetro · Lluvia. Escampa en 2 min.", leer.apply(lluvia, Clima.Tipo.GENERICO));
        h.igual("lluvia en el carmesi: el cielo rojo", "Barómetro · Cielo rojo. Escampa en 2 min.", leer.apply(lluvia, Clima.Tipo.ROJO));
        h.igual("tormenta en el bioma verde: sigue siendo lluvia acida", "Barómetro · Lluvia ácida. Escampa en 2 min.",
                leer.apply(tormenta, Clima.Tipo.ACIDA));
        h.igual("antes de una tormenta en la taiga condenada: ceniza", "Barómetro · Despejado. Ceniza en 45 s.",
                leer.apply(antesTormenta, Clima.Tipo.CENIZA));
        h.igual("esporas", "Barómetro · Despejado. Esporas en 4 min.", leer.apply(despejado, Clima.Tipo.ESPORAS));
        h.igual("polinizacion", "Barómetro · Polinización. Escampa en 2 min.", leer.apply(lluvia, Clima.Tipo.POLEN));
        h.igual("bioma sin clima: no cambia", "Barómetro · Despejado. En este bioma no cambia el tiempo.",
                leer.apply(lluvia, Clima.Tipo.NINGUNO));
        h.igual("bioma sin clima (null)", "Barómetro · Despejado. En este bioma no cambia el tiempo.", leer.apply(despejado, null));
        h.igual("fuera de Calamity", "Barómetro · Aquí no marca nada.", leer.apply(null, Clima.Tipo.ACIDA));
        h.igual("la fase ya acabo y el reloj aun no ha pasado: 0 s", "Barómetro · Lluvia. Escampa en 0 s.",
                leer.apply(new CicloClima.Lectura(CicloClima.LLUVIA, ahora - 500, CicloClima.DESPEJADO), Clima.Tipo.GENERICO));
        Component c = linea(b, despejado, Clima.Tipo.ACIDA, ahora);
        h.ok("el tiempo en el color de las cifras", tieneColor(c, "4 min", Paleta.CIFRA));
        h.ok("el clima que viene en su color", tieneColor(c, "Lluvia ácida", Clima.color(Clima.Tipo.ACIDA)));
        h.ok("sin negrita", Ficha.faltas(c, List.of()).isEmpty());
        YamlConfiguration otra = new YamlConfiguration();
        otra.set("textos.escampa", "{ahora}: escampa en {tiempo}.");
        otra.set("textos.lluvia", "Aguacero");
        h.igual("los textos salen de la config", "Barómetro · Aguacero: escampa en 2 min.",
                plano.serialize(linea(otra, lluvia, Clima.Tipo.GENERICO, ahora)));
        h.igual("un hueco que no existe se queda escrito", "x {nada} y", plano.serialize(pintar("x {nada} y", Map.of())));

        // Enfriamiento.
        h.ok("el primer clic cuenta", aTiempo(null, ahora, 3000));
        h.ok("a los 2 s no", !aTiempo(ahora - 2000, ahora, 3000));
        h.ok("a los 3 s si", aTiempo(ahora - 3000, ahora, 3000));
        h.ok("enfriamiento 0: siempre", aTiempo(ahora, ahora, 0));
        h.ok("reloj hacia atras: cuenta", aTiempo(ahora + 5000, ahora, 3000));
        YamlConfiguration cfg = new YamlConfiguration();
        h.igual("enfriamiento de serie: 3 s", 3, enfriamiento(cfg));
        cfg.set("barometro.enfriamiento-segundos", 500);
        h.igual("enfriamiento: como mucho 60 s", 60, enfriamiento(cfg));
        if (hardcore != null && hardcore.isSet("barometro.enfriamiento-segundos")) {
            h.igual("jar: enfriamiento-segundos", ENFRIAMIENTO, hardcore.getInt("barometro.enfriamiento-segundos"));
        }

        // El lore.
        List<String> lore = ficha(hardcore == null ? new YamlConfiguration() : hardcore).lineas();
        h.ok("lore: dice que no se gasta y donde marca", lore.contains("No se gasta.") && lore.contains("Solo marca en Calamity."));
        h.ok("lore: el enfriamiento", lore.contains(" Se puede mirar cada " + enfriamiento(hardcore == null ? cfg : hardcore) + " s."));
        h.igual("lore: lineas de 38 como mucho", List.of(), Ficha.largas(lore));
        h.igual("nombre y lore sin negrita ni rayas", List.of(), Ficha.faltas(Ficha.tono("barometro").nombre(NOMBRE),
                ficha(new YamlConfiguration()).lore()));
        h.ok("marca propia en lethal_world", Marcas.BAROMETRO.getNamespace().equals(Marcas.NAMESPACE)
                && Marcas.BAROMETRO.getKey().equals("barometro"));
        return h.lineas();
    }

    private static boolean tieneColor(Component c, String texto, TextColor color) {
        if (c instanceof TextComponent t && t.content().equals(texto) && color.equals(t.color())) return true;
        for (Component h : c.children()) if (tieneColor(h, texto, color)) return true;
        return false;
    }
}
