package net.ederus.calamity.hardcore;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Calamity 1.16.0 · Los Faroles de Tranquilidad I y II. Dosa: "faroles temporales que se puedan comprar, que cuenten
 * el tiempo que van activos, ya sea en la mano secundaria o en la primaria. La idea es que te quite los efectos de la
 * baja cordura: no la reduce ni la sube. Solo mantiene la cordura y quita los efectos por el tiempo de duracion. Farol
 * de Tranquilidad I dura 5 minutos y el II 12".
 *
 * Como arde:
 *  - solo en una mano (la principal o la secundaria) de un jugador que cuenta, en Calamity y fuera de la zona spawn.
 *    Guardado (en el inventario, un cofre, el cofre ender) no arde: al volver a la mano sigue donde se quedo;
 *  - con un farol en cada mano solo arde uno: el que menos tiempo tiene (con el mismo, el de la principal). Asi se
 *    acaba uno antes de empezar el otro;
 *  - lo cuenta Hardcore.tick una vez por segundo (segundo()), antes del drenaje: el segundo que se cobra es el
 *    segundo que protege. El ultimo segundo tambien protege, y al acabarlo el farol se consume (sonido sobrio, una
 *    linea en el chat y en la barra, y un poco de humo).
 *
 * El tiempo vive en el propio objeto, en su durabilidad: max_damage = lo que arde entero (duracion-i, duracion-ii) y
 * damage = lo que ya ardio. No va en una marca del PDC porque el PDC (custom_data) viaja al cliente y, si cambia en la
 * mano, el cliente repite la animacion de sacar el objeto: con el reloj en el PDC el farol "parpadearia" en la mano
 * cada segundo. damage es el unico componente que el cliente no anima (ignoreSwapAnimation), y de paso Java pinta la
 * barra de durabilidad: lo que le queda de mecha. El lore dice los minutos que le quedan (redondeados hacia arriba) y
 * se reescribe solo cuando ese numero cambia, una vez por minuto mientras arde; el reloj exacto (3:42) va en la barra
 * de la cordura: "Cordura 20%   ·   Tranquilidad 3:42" (MedidorCordura.calma). Como el lore solo cambia mientras arde,
 * nunca se queda viejo.
 *
 * Lo que hace mientras arde (Cordura.tranquilo y Cordura.sentida):
 *  - la cordura se queda QUIETA: Cordura.sumar no la mueve ni para abajo (drenaje por tiempo, noche, oscuridad, bioma,
 *    clima, golpes, testigos, la siega de la Parca...) ni para arriba (la Sangre fresca y el Eco redimido no la dan, y
 *    el Frasco no se deja beber: no se gasta un trago que no haria nada);
 *  - se callan todos los efectos de la cordura baja, porque miran Cordura.sentida (la entera) y no la de verdad:
 *    Locura (pasos, respiracion, gritos, latido, lamento, susurros), Alucinaciones (figuras, carreras, sonidos y la
 *    tirada), la vineta roja, los mobs y niveles de mas por cordura, el Frenesi, el minijefe de cordura cero, la parte
 *    de cordura de la dificultad de las amenazas y los minutos "al limite" (lucidez y contratos);
 *  - al encenderse se corta lo que ya estaba en marcha (Sentidos.alTranquilizar: la racha de pasos, la figura, lo que
 *    quedaba en la cola de sonidos, y la nausea del aviso de tramo);
 *  - al apagarse todo vuelve tal cual: la cordura es la misma que tenia y cada efecto se rearma solo.
 * No toca a la Parca, al Vigilante ni al Eclipse (no son efectos de la cordura): siguen viniendo y pegando, y el latido
 * de la Parca cerca sigue sonando. La vineta del Eclipse y la del cielo rojo, tambien.
 *
 * No se coloca: el clic derecho a un bloque no usa el objeto (y se cancela el BlockPlaceEvent por si acaso). Si alguno
 * llegara a ponerse, en el mundo es un farol de cobre cualquiera: la Corrupcion mira el material, no la marca, y lo
 * trata como a cualquier otro. Tampoco entra en un crafteo, el yunque, la afiladora ni la herreria: con durabilidad,
 * dos faroles gastados se podrian "reparar" juntando su mecha.
 *
 * Se entrega como todo lo de Calamity (Entregas: ligado a su dueno, no se vende ni se cambia) y se pierde al morir
 * como cualquier otro objeto. Altar: farol-1 y farol-2 (pagina umbral). Staff: /calamity give lantern1|lantern2.
 */
final class Faroles implements Listener {

    /** Los ids de /calamity give y de "dar:" en altar.trueques. */
    static final String OBJETO_I = "lantern1", OBJETO_II = "lantern2";
    static final String NOMBRE_I = "Farol de Tranquilidad I", NOMBRE_II = "Farol de Tranquilidad II";
    /** Lo que arde cada uno de serie, en segundos (hardcore.farol.duracion-i y duracion-ii). */
    static final int DURACION_I = 300, DURACION_II = 720;
    /**
     * Los de la captura de Dosa: el I es el farol de cobre expuesto (el pardo rosado) y el II el de cobre (el naranja),
     * los dos con la llama verde. El oxidado no: es la Lagrima de Eco (Reliquias).
     */
    static final Material MATERIAL_I = Material.EXPOSED_COPPER_LANTERN, MATERIAL_II = Material.COPPER_LANTERN;
    /** Lo que se lee en la barra de la cordura mientras arde: "Tranquilidad 3:42". */
    static final String BARRA = "Tranquilidad";
    static final String SON_ENCIENDE = "block.copper_bulb.turn_on", SON_APAGA = "block.candle.extinguish";
    /** Entre dos avisos de "no se puede colocar" al mismo jugador. */
    private static final long AVISO_MS = 5000;

    /** En que mano arde este segundo. */
    enum Mano { PRINCIPAL, SECUNDARIA, NINGUNA }

    /** Lo que deja un segundo de mecha: cuanto ha ardido ya, cuanto le queda, si se acabo y si toca reescribir el lore. */
    record Paso(int usados, int restante, boolean consumido, boolean renovarLore) {
    }

    private final Hardcore hc;
    /** Quien tiene un farol ardiendo este segundo -> los segundos que le quedan despues de este (0: era el ultimo). */
    private final Map<UUID, Integer> encendidos = new HashMap<>();
    /** El ultimo aviso de "no se puede colocar" a cada jugador (millis). */
    private final Map<UUID, Long> avisos = new HashMap<>();

    Faroles(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("farol", () -> autotest(Ficha.cfg(), this));
    }

    void parar() {
        HandlerList.unregisterAll(this);
        encendidos.clear();
        avisos.clear();
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = hc.cfg().getConfigurationSection("farol");
        return s == null ? new YamlConfiguration() : s;
    }

    private boolean activo() {
        return cfg().getBoolean("activo", true);
    }

    // ------------------------------------------------------------------ lo que preguntan los demas

    /**
     * Los segundos que le quedan al farol que le arde este segundo, o -1 si no le arde ninguno. Es lo que mira
     * Cordura.tranquilo (en cada sumar: tiene que ser barato) y lo que pinta la barra.
     */
    int restante(Player p) {
        if (p == null) return -1;
        Integer r = encendidos.get(p.getUniqueId());
        return r == null ? -1 : r;
    }

    /** Hardcore.tick, al acabar: quien no ha pasado por segundo() (salio, murio, se desconecto) ya no tiene farol. */
    void podar(Set<UUID> vistos) {
        if (!encendidos.isEmpty()) encendidos.keySet().retainAll(vistos);
    }

    // ------------------------------------------------------------------ el segundo

    /**
     * Una vez por segundo por jugador que cuenta en Calamity (Hardcore.tick), ANTES del drenaje y de los sentidos.
     * Quema un segundo del farol que lleva en la mano y dice si este segundo le arde. En la zona spawn no arde ni
     * gasta (alli la cordura ya no baja).
     */
    boolean segundo(Player p, boolean spawn) {
        UUID u = p.getUniqueId();
        boolean antes = encendidos.containsKey(u);
        int[] nivel = {0};
        int queda = spawn || p.isDead() || !activo() ? -1 : quemar(p, nivel);
        if (queda < 0) {
            encendidos.remove(u);
            return false;
        }
        encendidos.put(u, queda);
        if (!antes) hc.seguro("farol", () -> alEncender(p, nivel[0]));
        if (queda == 0) hc.seguro("farol", () -> alConsumir(p, nivel[0]));
        return true;
    }

    /**
     * Quema un segundo del farol de su mano (el que toque, elegir()). Devuelve lo que le queda (0 = se acaba de
     * consumir y ya no esta) o -1 si no lleva ninguno que arda. En nivel[0] deja el nivel del que ardio.
     */
    private int quemar(Player p, int[] nivel) {
        PlayerInventory inv = p.getInventory();
        ItemStack principal = inv.getItemInMainHand(), secundaria = inv.getItemInOffHand();
        UUID u = p.getUniqueId();
        Mano m = elegir(arde(principal, u), arde(secundaria, u));
        if (m == Mano.NINGUNA) return -1;
        ItemStack it = m == Mano.PRINCIPAL ? principal : secundaria;
        nivel[0] = nivel(it);
        Paso paso = paso(usados(it), total(it));
        if (paso.consumido()) {
            if (m == Mano.PRINCIPAL) inv.setItemInMainHand(null);
            else inv.setItemInOffHand(null);
            return 0;
        }
        ItemStack nuevo = it.clone();
        nuevo.setData(DataComponentTypes.DAMAGE, paso.usados());
        if (paso.renovarLore()) {
            ItemStack r = renovado(nuevo);
            if (r != null) nuevo = r;
        }
        if (m == Mano.PRINCIPAL) inv.setItemInMainHand(nuevo);
        else inv.setItemInOffHand(nuevo);
        return paso.restante();
    }

    /** Lo que le queda a ese objeto si es un farol que puede arder en manos de u; -1 si no (no es farol o es de otro). */
    private static int arde(ItemStack it, UUID u) {
        if (!es(it)) return -1;
        if (Ligado.ajeno(it, u) != null) return -1;
        return restante(it);
    }

    /** Se enciende (al sacarlo, o al salir del spawn con el en la mano): fuera lo que la locura tenia en marcha. */
    private void alEncender(Player p, int nivel) {
        Sentidos s = hc.sentidos();
        if (s != null) hc.seguro("sentidos", () -> s.alTranquilizar(p));
        quitarNausea(p);
        hc.cordura().destello(p, linea(nivel, texto(cfg(), "enciende", "Tu mente se aquieta.")), 3);
        Marco.sonar(p, SON_ENCIENDE, 0.6f, 1.0f);
    }

    /** El aviso de tramo (Hardcore.anunciarTramo) pone 6 s de nausea al bajar de 25: con el farol, fuera. */
    private static void quitarNausea(Player p) {
        PotionEffect n = p.getPotionEffect(PotionEffectType.NAUSEA);
        if (n != null && n.isAmbient() && !n.hasParticles() && n.getDuration() <= 120) p.removePotionEffect(PotionEffectType.NAUSEA);
    }

    private void alConsumir(Player p, int nivel) {
        Component l = linea(nivel, texto(cfg(), "apaga", "Se consumió. Tu cordura vuelve a moverse."));
        p.sendMessage(l);
        hc.cordura().destello(p, l, 4);
        Marco.sonar(p, SON_APAGA, 1.0f, 0.8f);
        Location mano = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(0.6)).add(0, -0.4, 0);
        p.getWorld().spawnParticle(Particle.SMOKE, mano, 6, 0.08, 0.08, 0.08, 0.01);
        hc.plugin().bitacora().anotar("farol", "consumido", p.getName(), nivel == 2 ? "II" : "I");
    }

    // ------------------------------------------------------------------ no se coloca, no se repara

    /**
     * El clic derecho a un bloque con un farol no usa el objeto: asi ni se intenta colocar (y el cliente no ve un farol
     * fantasma que se borra). El bloque si se usa (abrir una puerta o un cofre con el en la mano sigue valiendo).
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onUsarBloque(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || !es(e.getItem())) return;
        e.setUseItemInHand(Event.Result.DENY);
        Block b = e.getClickedBlock();
        if (b != null && !b.getType().isInteractable()) avisarNoColocar(e.getPlayer());
    }

    /** Por si algo lo deja llegar hasta aqui. LOWEST: la Corrupcion (MONITOR) ni lo ve. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPoner(BlockPlaceEvent e) {
        if (!es(e.getItemInHand())) return;
        e.setCancelled(true);
        avisarNoColocar(e.getPlayer());
    }

    private void avisarNoColocar(Player p) {
        long ahora = System.currentTimeMillis();
        Long antes = avisos.get(p.getUniqueId());
        if (antes != null && ahora - antes < AVISO_MS) return;
        avisos.put(p.getUniqueId(), ahora);
        Component t = Component.text(texto(cfg(), "no-colocar", "El farol no se puede colocar."), Paleta.TEXTO);
        if (hc.esHardcore(p)) hc.cordura().destello(p, t, 2);
        else hc.barra().aviso(p, t, 2);
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

    /** Con durabilidad, el yunque y la afiladora juntarian la mecha de dos faroles: nada. */
    @EventHandler
    public void onYunque(PrepareAnvilEvent e) {
        for (ItemStack it : e.getInventory().getContents()) {
            if (es(it)) {
                e.setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onAfiladora(PrepareGrindstoneEvent e) {
        for (ItemStack it : e.getInventory().getContents()) {
            if (es(it)) {
                e.setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onHerreria(PrepareSmithingEvent e) {
        for (ItemStack it : e.getInventory().getContents()) {
            if (es(it)) {
                e.setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onSalir(PlayerQuitEvent e) {
        encendidos.remove(e.getPlayer().getUniqueId());
        avisos.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ el objeto

    /** Un farol nuevo de ese nivel (1 o 2), entero y sin ligar (lo liga Entregas). Null si el nivel no existe. */
    static ItemStack crear(int nivel) {
        if (nivel != 1 && nivel != 2) return null;
        ConfigurationSection c = seccion(Ficha.cfg());
        int total = duracion(c, nivel);
        ItemStack item = new ItemStack(material(c, nivel));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        meta.displayName(nombre(nivel));
        meta.lore(ficha(nivel, total, total).lore());
        // Sin brillo: es un farol, no un objeto encantado.
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(Marcas.FAROL, PersistentDataType.INTEGER, nivel);
        item.setItemMeta(meta);
        // Uno por casilla (el reloj es de cada uno) y la mecha en la durabilidad.
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        item.setData(DataComponentTypes.MAX_DAMAGE, total);
        item.setData(DataComponentTypes.DAMAGE, 0);
        return item;
    }

    /** El objeto de una entrega (lantern1, lantern2), o null si no es un farol. */
    static ItemStack crear(String objeto) {
        if (OBJETO_I.equalsIgnoreCase(objeto)) return crear(1);
        if (OBJETO_II.equalsIgnoreCase(objeto)) return crear(2);
        return null;
    }

    /** Si es un Farol de Tranquilidad (por la marca: da igual el material o el nombre). */
    static boolean es(ItemStack it) {
        return nivel(it) > 0;
    }

    /** 1 o 2; 0 si no es un farol. */
    static int nivel(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return 0;
        Integer n = it.getItemMeta().getPersistentDataContainer().get(Marcas.FAROL, PersistentDataType.INTEGER);
        return n == null || (n != 1 && n != 2) ? 0 : n;
    }

    /** Lo que arde entero (max_damage); sin el componente, lo de serie de su nivel. */
    static int total(ItemStack it) {
        Integer m = it.getData(DataComponentTypes.MAX_DAMAGE);
        return m != null && m > 0 ? m : (nivel(it) == 2 ? DURACION_II : DURACION_I);
    }

    /** Lo que ya ardio (damage). */
    static int usados(ItemStack it) {
        Integer d = it.getData(DataComponentTypes.DAMAGE);
        return d == null ? 0 : Math.max(0, d);
    }

    static int restante(ItemStack it) {
        return Math.max(0, total(it) - usados(it));
    }

    static Component nombre(int nivel) {
        return Ficha.tono("farol").nombre(nivel == 2 ? NOMBRE_II : NOMBRE_I);
    }

    /** El mismo farol con el nombre y el lore de hoy y de lo que le queda (o null si ya los lleva). Conserva el ligado. */
    static ItemStack renovado(ItemStack it) {
        int nivel = nivel(it);
        if (nivel == 0) return null;
        return Ficha.renovar(it, nombre(nivel), ficha(nivel, restante(it), total(it)).lore());
    }

    /** El lore, con la plantilla comun. Puro: lo prueba el autotest "fichas". */
    static Ficha ficha(int nivel, int restante, int total) {
        int queda = minutos(restante), de = Math.max(1, minutos(total));
        return new Ficha(Ficha.tono("farol")).cabecera("Farol", "Cordura", 0)
                .historia("Su llama verde no da calor: acalla lo que la mente inventa.")
                .seccion("Mientras arde")
                .dato("Tu cordura no baja ni sube.")
                .dato("Calla la locura y sus visiones.")
                .dato("Sin mobs ni minijefe por cordura.")
                .seccion(queda == 1 ? "Le queda" : "Le quedan", "{" + queda + "} de {" + de + "} min")
                .dato("Solo arde en tu mano, en Calamity y fuera del spawn.")
                .dato("Si lo guardas, se detiene.")
                .accion("Llévalo en una mano para encenderlo.")
                .hueco().nota("No aleja a la Parca ni al Vigilante.")
                .nota("Se consume al apagarse. No se coloca.");
    }

    // ------------------------------------------------------------------ el nucleo (puro)

    /** hardcore.farol, o una vacia. */
    static ConfigurationSection seccion(ConfigurationSection hardcore) {
        ConfigurationSection s = hardcore == null ? null : hardcore.getConfigurationSection("farol");
        return s == null ? new YamlConfiguration() : s;
    }

    /** Lo que arde un farol nuevo de ese nivel, en segundos: de 10 s a 2 h. */
    static int duracion(ConfigurationSection farol, int nivel) {
        int def = nivel == 2 ? DURACION_II : DURACION_I;
        int s = farol == null ? def : farol.getInt(nivel == 2 ? "duracion-ii" : "duracion-i", def);
        return Math.max(10, Math.min(7200, s));
    }

    /** El material de un farol nuevo de ese nivel: el de la config si es un objeto, si no el de serie. */
    static Material material(ConfigurationSection farol, int nivel) {
        Material def = nivel == 2 ? MATERIAL_II : MATERIAL_I;
        String n = farol == null ? null : farol.getString(nivel == 2 ? "material-ii" : "material-i");
        Material m = n == null ? null : Material.matchMaterial(n.trim());
        return m != null && m.isItem() && !m.isAir() ? m : def;
    }

    static String texto(ConfigurationSection farol, String clave, String serie) {
        String s = farol == null ? null : farol.getString("textos." + clave);
        return s == null || s.isBlank() ? serie : s;
    }

    /**
     * Cual arde con lo que hay en cada mano (lo que le queda a cada una; -1 = ahi no hay un farol que pueda arder). Con
     * dos, el que menos tiempo tiene; con el mismo, el de la principal.
     */
    static Mano elegir(int principal, int secundaria) {
        boolean p = principal >= 0, s = secundaria >= 0;
        if (p && s) return secundaria < principal ? Mano.SECUNDARIA : Mano.PRINCIPAL;
        if (p) return Mano.PRINCIPAL;
        return s ? Mano.SECUNDARIA : Mano.NINGUNA;
    }

    /** Un segundo de mecha: el lore se reescribe solo cuando cambian los minutos que dice (redondeados arriba). */
    static Paso paso(int usados, int total) {
        int u = Math.max(0, usados) + 1;
        int r = Math.max(0, total - u);
        boolean fin = r <= 0;
        return new Paso(u, r, fin, !fin && r % 60 == 0);
    }

    /** Los minutos que dice el lore: hacia arriba (4:01 son 5; 0:30, 1). */
    static int minutos(int segundos) {
        return Math.max(0, (segundos + 59) / 60);
    }

    /** "3:42" (y "12:00"). */
    static String reloj(int segundos) {
        int s = Math.max(0, segundos);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** "Farol de Tranquilidad I · " y el texto: el nombre en el tono medio de la familia, el resto en el normal. */
    static Component linea(int nivel, String texto) {
        return Component.text()
                .append(Component.text(nivel == 2 ? NOMBRE_II : NOMBRE_I, Ficha.tono("farol").medio()))
                .append(Component.text(" · ", Paleta.SEPARADOR))
                .append(Component.text(texto, Paleta.TEXTO))
                .build().decoration(TextDecoration.ITALIC, false);
    }

    // ------------------------------------------------------------------ autotest

    /**
     * "lantern": lo puro siempre (fuera del servidor, con el arnes) y, con servidor, el objeto de verdad. Con un Faroles
     * vivo (el comando), tambien el bloqueo de colocar con eventos de mentira.
     */
    static List<String> autotest(ConfigurationSection hardcore, Faroles vivo) {
        Autotest.Hoja h = new Autotest.Hoja();
        ConfigurationSection f = seccion(hardcore);
        PlainTextComponentSerializer plano = PlainTextComponentSerializer.plainText();

        // Los de la captura, y ninguno el de la Lagrima.
        h.igual("el I es el farol de cobre expuesto", Material.EXPOSED_COPPER_LANTERN, MATERIAL_I);
        h.igual("el II es el farol de cobre", Material.COPPER_LANTERN, MATERIAL_II);
        h.ok("ninguno es el oxidado (la Lagrima)", MATERIAL_I != Material.OXIDIZED_COPPER_LANTERN
                && MATERIAL_II != Material.OXIDIZED_COPPER_LANTERN);
        YamlConfiguration vacia = new YamlConfiguration();
        h.igual("de serie: el I arde 5 min", 300, duracion(vacia, 1));
        h.igual("de serie: el II arde 12 min", 720, duracion(vacia, 2));
        h.igual("material de serie del I", MATERIAL_I, material(vacia, 1));
        YamlConfiguration rara = new YamlConfiguration();
        rara.set("duracion-i", 1);
        rara.set("duracion-ii", 999_999);
        rara.set("material-i", "NO_EXISTE");
        h.igual("duracion: al menos 10 s", 10, duracion(rara, 1));
        h.igual("duracion: como mucho 2 h", 7200, duracion(rara, 2));
        h.igual("material que no existe: el de serie", MATERIAL_I, material(rara, 1));
        if (f.isSet("duracion-i")) h.igual("jar: duracion-i", DURACION_I, f.getInt("duracion-i"));
        if (f.isSet("duracion-ii")) h.igual("jar: duracion-ii", DURACION_II, f.getInt("duracion-ii"));

        // Solo arde en una mano: cual.
        h.igual("solo en la principal", Mano.PRINCIPAL, elegir(120, -1));
        h.igual("solo en la secundaria", Mano.SECUNDARIA, elegir(-1, 300));
        h.igual("en ninguna mano (guardado): no arde", Mano.NINGUNA, elegir(-1, -1));
        h.igual("dos faroles: arde el que menos tiene (secundaria)", Mano.SECUNDARIA, elegir(700, 40));
        h.igual("dos faroles: arde el que menos tiene (principal)", Mano.PRINCIPAL, elegir(40, 700));
        h.igual("dos iguales: la principal", Mano.PRINCIPAL, elegir(300, 300));

        // La mecha: cada segundo en la mano, uno menos; guardado, nada. Se consume al llegar a 0.
        int usados = 0;
        for (int i = 0; i < 10; i++) usados = paso(usados, 300).usados();
        h.igual("10 s en la mano: le quedan 290", 290, 300 - usados);
        int guardado = usados;
        h.igual("guardado no arde: el objeto sigue con 290", 290, 300 - guardado);
        for (int i = 0; i < 5; i++) usados = paso(usados, 300).usados();
        h.igual("al volver a la mano sigue: 285", 285, 300 - usados);
        Paso ultimo = paso(299, 300);
        h.ok("el ultimo segundo lo consume", ultimo.consumido() && ultimo.restante() == 0);
        h.ok("el penultimo no", !paso(298, 300).consumido());
        h.ok("uno sin mecha se consume al primer segundo", paso(300, 300).consumido());
        int consumidos = 0, segundos = 0;
        for (int u = 0; u < 300; u++) {
            segundos++;
            if (paso(u, 300).consumido()) consumidos++;
        }
        h.igual("el I protege 300 segundos y se consume una vez", "300/1", segundos + "/" + consumidos);
        int renuevos = 0;
        for (int u = 0; u < 720; u++) if (paso(u, 720).renovarLore()) renuevos++;
        h.igual("el II reescribe el lore una vez por minuto (11 veces: el ultimo se consume)", 11, renuevos);
        h.ok("el lore se reescribe al pasar de 4:01 a 4:00", paso(59, 300).renovarLore() && !paso(58, 300).renovarLore());
        h.igual("minutos del lore: 4:01 son 5", 5, minutos(241));
        h.igual("minutos del lore: 4:00 son 4", 4, minutos(240));
        h.igual("minutos del lore: 0:30 es 1", 1, minutos(30));
        h.igual("reloj de la barra", "3:42", reloj(222));
        h.igual("reloj de la barra: 12:00", "12:00", reloj(720));
        h.igual("reloj de la barra: 0:05", "0:05", reloj(5));

        // La cordura congelada: ni baja ni sube; sentida() es la entera.
        Cordura c = new Cordura();
        Player p = jugador(Autotest.sintetico(1601));
        boolean[] arde = {true};
        c.farol(x -> arde[0] ? 120 : -1);
        c.valor(p, 20);
        h.cerca("con farol: el drenaje no la baja", 20, c.sumar(p, -1.0 / 60), 1e-9);
        h.cerca("con farol: un golpe no la baja", 20, c.sumar(p, -5), 1e-9);
        h.cerca("con farol: tampoco sube", 20, c.sumar(p, 40), 1e-9);
        h.ok("con farol: tranquilo", c.tranquilo(p));
        h.cerca("con farol: los efectos la sienten entera", Cordura.MAXIMO, c.sentida(p), 1e-9);
        h.cerca("con farol: la de verdad sigue siendo 20", 20, c.valor(p), 1e-9);
        arde[0] = false;
        h.cerca("sin farol: vuelve a bajar", 15, c.sumar(p, -5), 1e-9);
        h.cerca("sin farol: los efectos sienten la de verdad", 15, c.sentida(p), 1e-9);
        h.ok("sin farol: no tranquilo", !c.tranquilo(p));

        // Lo que se calla con la cordura sentida entera (y lo que no es de la cordura, que sigue).
        double baja = 3, llena = Cordura.MAXIMO;
        Locura.Ajustes loc = Locura.Ajustes.de(hardcore == null ? null : hardcore.getConfigurationSection("locura"));
        h.ok("Locura: con 3 de cordura habria sustos", !Locura.sinLocura(loc, baja, false, false, false)
                && !Locura.permitidos(loc, baja).isEmpty());
        h.ok("Locura: con el farol, nada (pasos, respiracion, gritos, latido, lamento, susurros)",
                Locura.sinLocura(loc, llena, false, false, false) && Locura.permitidos(loc, llena).isEmpty());
        int tramo = Cordura.tramo(llena);
        int latidos = 0;
        for (int s = 1; s <= 10; s++) if (Sentidos.volumenLatido(tramo, false, s) > 0) latidos++;
        h.igual("latido de la cordura: callado", 0, latidos);
        h.cerca("latido de la Parca cerca: sigue (no es de la cordura)", 0.7, Sentidos.volumenLatido(tramo, true, 10), 1e-6);
        List<Double> porMinuto = List.of(3.0, 2.0, 1.0, 0.5, 0.0);
        h.cerca("Alucinaciones: probabilidad 0", 0, Alucinaciones.probabilidad(porMinuto, tramo), 1e-9);
        h.ok("Alucinaciones: con 3 de cordura si habria", Alucinaciones.probabilidad(porMinuto, Cordura.tramo(baja)) > 0);
        List<Double> vin = List.of(0.85, 0.6, 0.35, 0.0, 0.0);
        h.cerca("vineta de la cordura: nada", 0, Vineta.intensidad(vin, tramo, false, 0.2), 1e-9);
        h.cerca("vineta del Eclipse: sigue (no es de la cordura)", 0.2, Vineta.intensidad(vin, tramo, true, 0.2), 1e-9);
        h.igual("mobs de mas por cordura: 0", 0, Hardcore.mobsExtraPorCordura(llena, 4, 6));
        h.igual("mobs de mas a 0 de cordura sin farol: 6", 6, Hardcore.mobsExtraPorCordura(0, 4, 6));
        h.igual("niveles de mas por cordura: 0", 0, Hardcore.nivelPorCordura(llena, 10, 20));
        h.igual("niveles de mas con 3 sin farol: 20", 20, Hardcore.nivelPorCordura(baja, 10, 20));
        h.cerca("Frenesi: x1", 1.0, Combate.factorFrenesi(llena, 25, 0.15), 1e-9);
        DificultadAmenaza.Ajustes dif = DificultadAmenaza.Ajustes.de(null);
        h.cerca("dificultad de las amenazas: la cordura no suma",
                DificultadAmenaza.calcular(new DificultadAmenaza.Foto(0, llena, 0), dif).dano(), 1.0, 1e-9);

        // Lo que se ve.
        h.igual("barra con farol", "Cordura 20%   ·   Tranquilidad 3:42", plano.serialize(MedidorCordura.titulo(20, 222, null)));
        h.igual("aviso al consumirse", "Farol de Tranquilidad II · Se consumió. Tu cordura vuelve a moverse.",
                plano.serialize(linea(2, texto(f, "apaga", "Se consumió. Tu cordura vuelve a moverse."))));
        List<String> lore = ficha(1, 300, 300).lineas();
        h.ok("lore: le quedan 5 de 5 min", lore.contains("◆ Le quedan 5 de 5 min"));
        h.ok("lore: a 0:40, en singular", ficha(2, 40, 720).lineas().contains("◆ Le queda 1 de 12 min"));
        h.ok("lore: dice que no se coloca y que no aleja a la Parca",
                lore.contains("Se consume al apagarse. No se coloca.") && lore.contains("No aleja a la Parca ni al Vigilante."));
        h.igual("lore: lineas de 38 como mucho", List.of(), Ficha.largas(lore));
        h.igual("nombre y lore sin negrita, rayas ni cursiva", List.of(), Ficha.faltas(nombre(2), ficha(2, 1, 720).lore()));
        h.ok("marcas en lethal_world", Marcas.FAROL.getNamespace().equals(Marcas.NAMESPACE) && Marcas.FAROL.getKey().equals("farol"));

        // Con servidor: el objeto de verdad.
        if (Bukkit.getServer() != null) {
            ItemStack uno = crear(1), dos = crear(2);
            h.ok("give lantern1: farol de cobre expuesto con su marca", uno != null && uno.getType() == MATERIAL_I && nivel(uno) == 1);
            h.ok("give lantern2: farol de cobre con su marca", dos != null && dos.getType() == MATERIAL_II && nivel(dos) == 2);
            h.ok("ids de give", crear(OBJETO_I) != null && crear(OBJETO_II) != null && crear("lantern3") == null);
            if (uno != null) {
                h.igual("uno por casilla", 1, uno.getMaxStackSize());
                h.igual("mecha entera del I", 300, restante(uno));
                h.ok("sin brillo", Boolean.FALSE.equals(uno.getItemMeta().getEnchantmentGlintOverride()));
                ItemStack gastado = uno.clone();
                gastado.setData(DataComponentTypes.DAMAGE, paso(usados(gastado), total(gastado)).usados());
                ItemStack copia = gastado.clone();
                h.igual("el tiempo va en el objeto (sobrevive a copiarlo)", 299, restante(copia));
                h.ok("sigue siendo un farol", es(copia) && nivel(copia) == 1);
                ItemStack casi = uno.clone();
                casi.setData(DataComponentTypes.DAMAGE, 299);
                h.ok("con 1 s, el siguiente segundo lo consume", paso(usados(casi), total(casi)).consumido());
                ItemStack r = renovado(casi);
                h.ok("renovado a 0:01: el lore dice 1 min", r != null && ficha(1, 1, 300).lineas().stream()
                        .anyMatch(l -> l.equals("◆ Le queda 1 de 5 min")));
                h.ok("renovar no le quita la mecha", r != null && restante(r) == 1);
            }
            if (vivo != null && uno != null) {
                Player q = jugador(Autotest.sintetico(1602));
                Block b = falso(Block.class);
                BlockState antes = falso(BlockState.class);
                BlockPlaceEvent ev = new BlockPlaceEvent(b, antes, b, uno, q, true, EquipmentSlot.HAND);
                vivo.avisos.put(q.getUniqueId(), System.currentTimeMillis());
                vivo.onPoner(ev);
                h.ok("no se puede colocar (BlockPlaceEvent cancelado)", ev.isCancelled());
                ItemStack piedra = new ItemStack(Material.STONE);
                BlockPlaceEvent otra = new BlockPlaceEvent(b, antes, b, piedra, q, true, EquipmentSlot.HAND);
                vivo.onPoner(otra);
                h.ok("otro bloque si se coloca", !otra.isCancelled());
                PlayerInteractEvent clic = new PlayerInteractEvent(q, Action.RIGHT_CLICK_BLOCK, uno, null, BlockFace.UP,
                        EquipmentSlot.OFF_HAND);
                vivo.onUsarBloque(clic);
                h.ok("clic derecho a un bloque con el farol: no usa el objeto", clic.useItemInHand() == Event.Result.DENY);
                vivo.avisos.remove(q.getUniqueId());
            }
        }
        return h.lineas();
    }

    /** Un jugador de mentira con ese uuid (lo demas devuelve vacio): Cordura solo le pregunta el uuid. */
    static Player jugador(UUID u) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, m, args) -> {
            if (m.getName().equals("getUniqueId")) return u;
            if (m.getName().equals("getName")) return "farol-prueba";
            if (m.getName().equals("equals")) return proxy == args[0];
            if (m.getName().equals("hashCode")) return u.hashCode();
            if (m.getName().equals("toString")) return "jugador de prueba";
            return vacio(m.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T falso(Class<T> tipo) {
        return (T) Proxy.newProxyInstance(tipo.getClassLoader(), new Class<?>[]{tipo}, (proxy, m, args) -> {
            if (m.getName().equals("equals")) return proxy == args[0];
            if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (m.getName().equals("toString")) return "falso " + tipo.getSimpleName();
            return vacio(m.getReturnType());
        });
    }

    private static Object vacio(Class<?> r) {
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == double.class) return 0.0;
        if (r == float.class) return 0f;
        if (r == short.class) return (short) 0;
        if (r == byte.class) return (byte) 0;
        if (r == char.class) return (char) 0;
        return null;
    }
}
