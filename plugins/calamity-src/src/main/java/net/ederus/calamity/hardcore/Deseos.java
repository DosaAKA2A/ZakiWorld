package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.menu.MenuUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * M19 · Lista de deseos (MED sec. 4.3): 5-7 recompensas candidatas y cada jugador marca como
 * mucho 3 (hardcore.deseos.votos-por-jugador).
 *
 * Por que un tope y no "vota todas las que quieras": con votos ilimitados todos marcan
 * todo y el recuento no ordena nada. Con tres, el cuarto obliga a quitar otro y sale una
 * jerarquia de verdad. Los votos siguen activos mientras el jugador no los quite, asi que
 * el recuento es "lo que la gente quiere ahora", no un historico.
 *
 * Las candidatas salen de hardcore.deseos.candidatas (<id>: {icono, nombre, lore}); si la
 * config no trae ninguna, las de serie de aqui. Los ids casan con catalogo.yml del
 * analizador (tools/calamity). Se abre con /calamity deseos y desde el Altar (WP3).
 * Datos: deseos-votos.<uuid> = [ids].
 */
final class Deseos {

    record Candidata(String id, Material icono, String nombre, List<String> lore) {
    }

    enum Resultado { PUESTO, QUITADO, LLENO, NO_EXISTE }

    private static final List<Candidata> DE_SERIE = List.of(
            new Candidata("manto", Material.NETHERITE_CHESTPLATE, "Pieza del Manto de Calamidad",
                    List.of("El set de los cinco minijefes.")),
            new Candidata("libro-legendary", Material.ENCHANTED_BOOK, "Libro LEGENDARY",
                    List.of("Un encantamiento de los buenos.")),
            new Candidata("encantamientos", Material.EXPERIENCE_BOTTLE, "Encantamiento sobre el tope",
                    List.of("Un nivel más de lo normal.")),
            new Candidata("mascota", Material.WOLF_SPAWN_EGG, "Mascota rara",
                    List.of("Que te acompañe algo que nadie tiene.")),
            new Candidata("rip", Material.SKELETON_SKULL, "Efecto RIP de Calamity",
                    List.of("Que se note cómo mueres.")),
            new Candidata("talisman", Material.AMETHYST_SHARD, "Talismán de cordura",
                    List.of("La cordura baja más despacio.")),
            new Candidata("llave-caos", Material.TRIAL_KEY, "Llave del Caos",
                    List.of("Una caja que solo da Calamity.")));

    private final Hardcore hc;
    private final Encuesta encuesta;

    Deseos(Hardcore hc, Encuesta encuesta) {
        this.hc = hc;
        this.encuesta = encuesta;
        Subcomandos.lw().registrar("deseos", "deseos: recuento de la lista de deseos", "ederus.mundos",
                (quien, args) -> recuento(quien), null);
        Subcomandos.calamity().registrar("deseos", "lo que quieres que dé Calamity (3 votos)", "lethalworld.calamity",
                (quien, args) -> {
                    if (quien instanceof Player p) abrir(p);
                    else quien.sendMessage(Component.text("Solo desde el juego.", Paleta.AVISO));
                }, null);
    }

    boolean activo() {
        return hc.cfg().getBoolean("deseos.activo", false);
    }

    int maximo() {
        return Math.max(1, hc.cfg().getInt("deseos.votos-por-jugador", 3));
    }

    List<Candidata> candidatas() {
        ConfigurationSection s = hc.cfg().getConfigurationSection("deseos.candidatas");
        List<Candidata> out = new ArrayList<>();
        if (s != null) {
            for (String id : s.getKeys(false)) {
                ConfigurationSection c = s.getConfigurationSection(id);
                if (c == null) continue;
                Material m = Material.matchMaterial(c.getString("icono", "PAPER"));
                out.add(new Candidata(id.toLowerCase(Locale.ROOT), m == null || !m.isItem() ? Material.PAPER : m,
                        c.getString("nombre", id), c.getStringList("lore")));
                if (out.size() == 7) break;          // lo que cabe en la fila
            }
        }
        return out.isEmpty() ? DE_SERIE : out;
    }

    private Set<String> ids() {
        Set<String> s = new LinkedHashSet<>();
        for (Candidata c : candidatas()) s.add(c.id());
        return s;
    }

    // ------------------------------------------------------------------- nucleo

    /**
     * Pone o quita un deseo. Los votos a candidatas que ya no existen se caen solos (Dosa
     * puede cambiar la lista sin dejar a nadie con un hueco que no puede quitar).
     */
    static Resultado alternar(ConfigurationSection d, UUID u, String id, int maximo, Set<String> validas) {
        if (id == null || !validas.contains(id)) return Resultado.NO_EXISTE;
        String ruta = "deseos-votos." + u;
        List<String> l = new ArrayList<>(d.getStringList(ruta));
        l.retainAll(validas);
        Resultado r;
        if (l.remove(id)) {
            r = Resultado.QUITADO;
        } else if (l.size() >= maximo) {
            return Resultado.LLENO;
        } else {
            l.add(id);
            r = Resultado.PUESTO;
        }
        d.set(ruta, l.isEmpty() ? null : l);
        return r;
    }

    static Map<String, Integer> recuento(ConfigurationSection d, Collection<String> validas) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String id : validas) out.put(id, 0);
        ConfigurationSection s = d.getConfigurationSection("deseos-votos");
        if (s == null) return out;
        for (String u : s.getKeys(false)) {
            for (String id : s.getStringList(u)) out.computeIfPresent(id, (k, v) -> v + 1);
        }
        return out;
    }

    // --------------------------------------------------------------------- menu

    void abrir(Player p) {
        if (!activo()) {
            p.sendMessage(ComandoCalamity.mensaje("La lista de deseos está cerrada ahora mismo."));
            return;
        }
        Inventory inv = hc.plugin().getServer().createInventory(new MenuEncuesta.Marca(null, true), 9,
                Paleta.ventanaCalamity("deseos"));
        pintar(inv, p);
        p.openInventory(inv);
        Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.amethyst_block.chime", 0.8f, 1.1f);
    }

    private void pintar(Inventory inv, Player p) {
        List<Candidata> cs = candidatas();
        Set<String> validas = ids();
        List<String> mios = new ArrayList<>(hc.datos().getStringList("deseos-votos." + p.getUniqueId()));
        mios.retainAll(validas);
        Map<String, Integer> cuenta = recuento(hc.datos(), validas);
        int max = maximo();

        inv.setItem(0, MenuUtil.icon(Material.NETHER_STAR, Component.text("Lo que quieres de Calamity", MenuEncuesta.VERDE),
                List.of(MenuUtil.line("Marca hasta " + max + "."),
                        MenuUtil.line("Para cambiar uno, quítalo antes."),
                        MenuUtil.blank(),
                        MenuUtil.field("Tus deseos", mios.size() + "/" + max, MenuEncuesta.AMBAR)), false));
        for (int i = 0; i < cs.size() && i < 7; i++) {
            Candidata c = cs.get(i);
            boolean mio = mios.contains(c.id());
            List<Component> lore = new ArrayList<>();
            for (String l : c.lore()) lore.add(MenuUtil.line(l));
            lore.add(MenuUtil.blank());
            lore.add(MenuUtil.field("Lo quieren", String.valueOf(cuenta.getOrDefault(c.id(), 0)), MenuEncuesta.AMBAR));
            lore.add(MenuUtil.blank());
            lore.add(MenuUtil.line(mio ? "Clic: ya no lo quiero." : "Clic: lo quiero."));
            inv.setItem(1 + i, MenuUtil.icon(c.icono(), Component.text(c.nombre(), mio ? MenuEncuesta.AMBAR : MenuEncuesta.VERDE),
                    lore, mio));
        }
        for (int s = 1 + Math.min(7, cs.size()); s < MenuEncuesta.CERRAR; s++) inv.setItem(s, MenuUtil.pane());
        inv.setItem(MenuEncuesta.CERRAR, MenuUtil.icon(Material.BARRIER,
                Component.text("Cerrar", Paleta.AVISO), List.of(MenuUtil.line("Tus deseos se quedan.")), false));
    }

    /** Lo llama MenuEncuesta (ya filtrado: clic izquierdo, 500 ms, casilla de arriba). */
    void alClic(Player p, int slot, Inventory inv) {
        if (slot == MenuEncuesta.CERRAR) {
            encuesta.tarea(() -> {
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof MenuEncuesta.Marca) p.closeInventory();
            }, 1L);
            return;
        }
        if (!activo()) return;
        List<Candidata> cs = candidatas();
        int i = slot - 1;
        if (i < 0 || i >= cs.size() || i >= 7) return;
        String id = cs.get(i).id();
        Resultado r = alternar(hc.datos(), p.getUniqueId(), id, maximo(), ids());
        switch (r) {
            case PUESTO, QUITADO -> {
                hc.marcarSucio();
                boolean puesto = r == Resultado.PUESTO;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", id);
                c.put("opcion", puesto ? "si" : "no");
                encuesta.telemetria("deseo", p, c);
                hc.plugin().bitacora().anotar("deseo", p.getName(), id, puesto ? "si" : "no");
                Compat.soundPlayers(p.getWorld(), p.getLocation(), "block.amethyst_block.resonate", 0.7f, puesto ? 1.4f : 0.8f);
                pintar(inv, p);
            }
            case LLENO -> p.sendMessage(ComandoCalamity.mensaje("Ya tienes " + maximo() + " deseos. Quita uno para cambiarlo."));
            case NO_EXISTE -> {
                // La lista cambio con el menu abierto: se repinta y ya.
                pintar(inv, p);
            }
        }
    }

    private void recuento(CommandSender quien) {
        Map<String, Integer> r = recuento(hc.datos(), ids());
        int total = 0;
        for (int v : r.values()) total += v;
        quien.sendMessage(Component.text("deseos | " + total + " votos activos | " + (activo() ? "abierta" : "deseos.activo: false"),
                Paleta.BIEN));
        List<Map.Entry<String, Integer>> filas = new ArrayList<>(r.entrySet());
        filas.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map<String, String> nombres = new LinkedHashMap<>();
        for (Candidata c : candidatas()) nombres.put(c.id(), c.nombre());
        for (Map.Entry<String, Integer> e : filas) {
            long pct = total == 0 ? 0 : Math.round(e.getValue() * 100.0 / total);
            quien.sendMessage(Component.text("  " + e.getKey() + " · " + nombres.get(e.getKey()) + " · " + e.getValue()
                    + " · " + pct + " %", Paleta.TENUE));
        }
    }

    // ---------------------------------------------------------------- autotest

    List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        YamlConfiguration d = new YamlConfiguration();
        Set<String> validas = new LinkedHashSet<>(List.of("manto", "libro-legendary", "rip", "llave-caos", "talisman"));
        UUID a = Autotest.sintetico(40), b = Autotest.sintetico(41);
        h.igual("deseos: 1.o", Resultado.PUESTO, alternar(d, a, "manto", 3, validas));
        h.igual("deseos: 2.o", Resultado.PUESTO, alternar(d, a, "rip", 3, validas));
        h.igual("deseos: 3.o", Resultado.PUESTO, alternar(d, a, "llave-caos", 3, validas));
        h.igual("deseos: el 4.o obliga a quitar otro", Resultado.LLENO, alternar(d, a, "talisman", 3, validas));
        h.igual("deseos: quitar uno", Resultado.QUITADO, alternar(d, a, "rip", 3, validas));
        h.igual("deseos: ahora si entra el 4.o", Resultado.PUESTO, alternar(d, a, "talisman", 3, validas));
        h.igual("deseos: una candidata que no existe", Resultado.NO_EXISTE, alternar(d, a, "nada", 3, validas));
        alternar(d, b, "manto", 3, validas);
        Map<String, Integer> r = recuento(d, validas);
        h.igual("deseos: manto lo quieren 2", 2, r.get("manto"));
        h.igual("deseos: rip lo quieren 0", 0, r.get("rip"));
        h.igual("deseos: 4 votos activos", 4, r.values().stream().mapToInt(Integer::intValue).sum());
        Set<String> sinManto = new LinkedHashSet<>(validas);
        sinManto.remove("manto");
        h.igual("deseos: quitar una candidata libera el hueco", Resultado.PUESTO, alternar(d, a, "rip", 3, sinManto));
        h.igual("deseos: el ultimo quitado borra la entrada", Resultado.QUITADO, alternar(d, b, "manto", 3, validas));
        h.ok("deseos: sin votos no queda la clave", !d.isSet("deseos-votos." + b));
        h.ok("deseos: de serie hay entre 5 y 7", DE_SERIE.size() >= 5 && DE_SERIE.size() <= 7);
        h.ok("deseos: la config da entre 1 y 7", !candidatas().isEmpty() && candidatas().size() <= 7);
        return h.lineas();
    }
}
