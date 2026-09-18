package net.ederus.edm.flex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.Poder;
import net.ederus.edm.comun.menu.MenuUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * El menu de /flex power: de donde sale el Poder de un jugador.
 *
 * En medio, lo que lleva puesto tal cual (casco, peto, pantalones y botas, con el
 * arma al lado): son copias solo para mirar, como en la vitrina. A la izquierda,
 * las cuatro cifras que salen de ese equipo, cada una con su peso, para que se
 * entienda que pieza sube que numero. A la derecha, lo que ha jugado: rango y
 * habilidades. Arriba, el cristal con el desglose entero y el total.
 *
 * Tambien aqui TODO clic se cancela: el equipo que se ve no es el equipo de
 * verdad, y de este menu no sale nada.
 */
public final class MenuPoder implements Listener {

    private static final int TAM = 54;

    private static final int SLOT_CRISTAL = 4;
    /** Casco, peto, pantalones y botas, en columna. */
    private static final int[] PIEZAS = {13, 22, 31, 40};
    private static final int SLOT_MANO = 24;
    private static final int SLOT_OTRA_MANO = 15;
    /** Armadura, dureza, vida de mas y daño de mas: lo que sale del equipo. */
    private static final int[] DEL_EQUIPO = {11, 20, 29, 38};
    private static final int SLOT_RANGO = 33;
    private static final int SLOT_AURASKILLS = 42;
    private static final int SLOT_ANUNCIAR = 49;

    private static final String[] SIN_PIEZA = {"Sin casco", "Sin peto", "Sin pantalones", "Sin botas"};

    private final FlexPlugin plugin;

    public MenuPoder(FlexPlugin plugin) {
        this.plugin = plugin;
    }

    /** De quien es el poder que se mira y si quien mira puede flexearlo. */
    private static final class Vista implements InventoryHolder {
        private final UUID dueno;
        private final boolean propia;
        private final long total;
        private Inventory inv;

        Vista(UUID dueno, boolean propia, long total) {
            this.dueno = dueno;
            this.propia = propia;
            this.total = total;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    /* ------------------------------------------------------------------ abrir */

    public void abrir(Player quien, Player de) {
        Poder.Desglose d = Poder.calcular(plugin, de);
        plugin.registroPoder().anotar(de);
        boolean propia = quien.equals(de);
        Vista vista = new Vista(de.getUniqueId(), propia, Math.round(d.total()));
        vista.inv = Bukkit.createInventory(vista, TAM,
                MenuFlex.titulo(propia ? "Tu Poder" : "Poder de " + de.getName()));
        pintar(vista, de, d);
        quien.openInventory(vista.inv);
    }

    private void pintar(Vista vista, Player de, Poder.Desglose d) {
        Inventory inv = vista.inv;
        for (int i = 0; i < TAM; i++) inv.setItem(i, MenuUtil.pane());

        // El cristal: el desglose entero, que es lo que antes salia por el chat.
        List<Component> lore = new ArrayList<>();
        lore.add(fila("Rango de rankup", d.rango(), peso("por-rango", 200)));
        lore.add(fila("AuraSkills", d.auraskills(), peso("por-auraskills", 20)));
        lore.add(fila("Armadura", d.armadura(), peso("por-armadura", 80)));
        lore.add(fila("Dureza", d.dureza(), peso("por-dureza", 120)));
        lore.add(fila("Vida de más", d.vida(), peso("por-vida", 40)));
        lore.add(fila("Daño de más", d.dano(), peso("por-dano", 60)));
        lore.add(Estilo.vacio());
        lore.add(Estilo.texto(cifra(d.total()), FlexPlugin.MAGENTA_CLARO)
                .append(Estilo.texto("  de Poder", Estilo.APAGADO)));
        inv.setItem(SLOT_CRISTAL, MenuUtil.icon(Material.END_CRYSTAL,
                MenuUtil.title("Poder de " + de.getName(), FlexPlugin.MARCA), lore, true));

        // Lo que lleva puesto, tal cual: su nombre y su lore ya cuentan lo que dan.
        ItemStack[] piezas = {
                de.getInventory().getHelmet(), de.getInventory().getChestplate(),
                de.getInventory().getLeggings(), de.getInventory().getBoots()};
        for (int i = 0; i < PIEZAS.length; i++) {
            inv.setItem(PIEZAS[i], copia(piezas[i], SIN_PIEZA[i]));
        }
        inv.setItem(SLOT_MANO, copia(de.getInventory().getItemInMainHand(), "Mano vacía"));
        ItemStack otra = de.getInventory().getItemInOffHand();
        if (otra != null && !otra.getType().isAir()) inv.setItem(SLOT_OTRA_MANO, otra.clone());

        // Las cuatro cifras del equipo, al lado de las piezas de donde salen.
        inv.setItem(DEL_EQUIPO[0], dato(Material.SHIELD, "Armadura", d.armadura(),
                peso("por-armadura", 80), "puntos",
                "La suma de las cuatro piezas. Cada",
                "punto de armadura vale lo mismo, sea",
                "de MMOItems o vanilla encantada."));
        inv.setItem(DEL_EQUIPO[1], dato(Material.ANVIL, "Dureza", d.dureza(),
                peso("por-dureza", 120), "puntos",
                "Lo que aguanta ese equipo frente a",
                "golpes fuertes. Sube con diamante,",
                "netherite y los sets del servidor."));
        inv.setItem(DEL_EQUIPO[2], dato(Material.GOLDEN_APPLE, "Vida de más", d.vida(),
                peso("por-vida", 40), "de vida",
                "Solo lo que pasa de los diez corazones",
                "de siempre: la vida extra que dan las",
                "piezas y los encantamientos."));
        inv.setItem(DEL_EQUIPO[3], dato(Material.BLAZE_POWDER, "Daño de más", d.dano(),
                peso("por-dano", 60), "de daño",
                "El golpe por encima del puño, con lo",
                "que llevas en la mano ahora mismo."));

        // Lo que ha jugado: no depende de lo que lleve puesto.
        inv.setItem(SLOT_RANGO, dato(Material.EMERALD, "Rango de rankup", d.rango(),
                peso("por-rango", 200), "de rango",
                "El camino que llevas andado en /rankup."));
        inv.setItem(SLOT_AURASKILLS, dato(Material.EXPERIENCE_BOTTLE, "AuraSkills", d.auraskills(),
                peso("por-auraskills", 20), "de poder",
                "La suma de tus habilidades en /skills."));

        if (vista.propia) {
            inv.setItem(SLOT_ANUNCIAR, MenuUtil.icon(Material.GOAT_HORN,
                    MenuUtil.title("Flexear en el chat", FlexPlugin.MARCA),
                    List.of(
                            Estilo.texto("Una línea para todo el servidor con tu", Estilo.APAGADO),
                            Estilo.texto("cifra de Poder, y nada más.", Estilo.APAGADO),
                            Estilo.vacio(),
                            Estilo.accion("Clic para flexearlo", FlexPlugin.MARCA)), false));
        }
    }

    /** Una fila del desglose del cristal: la parte, su valor y lo que aporta. */
    private static Component fila(String etiqueta, double valor, double peso) {
        return Estilo.texto(Estilo.FLECHA + " ", Estilo.APAGADO)
                .append(Estilo.texto(etiqueta, FlexPlugin.MAGENTA_CLARO))
                .append(Estilo.texto("  " + valorCorto(valor), NamedTextColor.WHITE))
                .append(Estilo.texto("  " + cifra(valor * peso), Estilo.APAGADO));
    }

    /** Un dato con su peso y lo que suma, y debajo de donde sale. */
    private static ItemStack dato(Material material, String etiqueta, double valor, double peso,
                                  String unidad, String... explica) {
        List<Component> lore = new ArrayList<>();
        lore.add(Estilo.texto(Estilo.FLECHA + " ", Estilo.APAGADO)
                .append(Estilo.texto(valorCorto(valor), NamedTextColor.WHITE))
                .append(Estilo.texto(" " + unidad + "  ×" + valorCorto(peso), Estilo.APAGADO)));
        lore.add(Estilo.texto(Estilo.FLECHA + " ", Estilo.APAGADO)
                .append(Estilo.texto(cifra(valor * peso), FlexPlugin.MAGENTA_CLARO))
                .append(Estilo.texto(" de Poder", Estilo.APAGADO)));
        lore.add(Estilo.vacio());
        for (String l : explica) lore.add(Estilo.texto(l, Estilo.APAGADO));
        return MenuUtil.icon(material, MenuUtil.title(etiqueta, FlexPlugin.MARCA), lore, false);
    }

    /** La pieza tal cual, o un hueco apagado si no lleva nada ahi. */
    private static ItemStack copia(ItemStack pieza, String siNoHay) {
        if (pieza == null || pieza.getType().isAir()) {
            return MenuUtil.simple(Material.GRAY_STAINED_GLASS_PANE,
                    Estilo.texto(siNoHay, Estilo.APAGADO), List.of());
        }
        return pieza.clone();
    }

    private double peso(String clave, double def) {
        return Poder.peso(plugin, clave, def);
    }

    /** "4.144": con punto de miles y sin decimales, como se presume una cifra. */
    public static String cifra(double d) {
        return String.format(Locale.US, "%,.0f", d).replace(',', '.');
    }

    /** "9" o "8.5": sin decimales cuando no hacen falta. */
    private static String valorCorto(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.US, "%.1f", d);
    }

    /* ------------------------------------------------------------------ clics */

    @EventHandler
    public void alHacerClic(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Vista v)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (!v.propia || !p.getUniqueId().equals(v.dueno)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        if (e.getSlot() == SLOT_ANUNCIAR) {
            p.closeInventory();
            plugin.anunciarPoder(p, v.total);
        }
    }

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Vista) e.setCancelled(true);
    }
}
