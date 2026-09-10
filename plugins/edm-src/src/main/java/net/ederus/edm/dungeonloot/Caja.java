package net.ederus.edm.dungeonloot;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.ederus.edm.anomaly.drops.DropEntry;
import net.ederus.edm.anomaly.drops.DropTable;
import net.ederus.edm.comun.Estilo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Una caja de mazmorra: la boveda que se planta en el mapa, la llave que la abre
 * y lo que suelta al abrirse.
 *
 * Son las boveda del Trial Chamber, con su animacion de verdad, pero el botin lo
 * decide EDM y no el juego. Hay dos tipos, los mismos dos que trae Minecraft: la
 * comun y la ominosa, que es la buena.
 *
 * El objeto UNICO va aparte de la lista normal: es la pieza rara de la caja, la
 * que justifica seguir abriendola.
 */
public final class Caja {

    public enum Tipo {
        COMUN("Bóveda común", Material.VAULT, Material.TRIAL_KEY, false, 0x91F4FF),
        OMINOSA("Bóveda ominosa", Material.VAULT, Material.OMINOUS_TRIAL_KEY, true, 0x0083FD);

        private final String display;
        private final Material block;
        private final Material key;
        private final boolean ominous;
        private final int color;

        Tipo(String display, Material block, Material key, boolean ominous, int color) {
            this.display = display;
            this.block = block;
            this.key = key;
            this.ominous = ominous;
            this.color = color;
        }

        public String display() {
            return display;
        }

        public Material block() {
            return block;
        }

        public Material key() {
            return key;
        }

        public boolean ominous() {
            return ominous;
        }

        public TextColor color() {
            return TextColor.color(color);
        }
    }

    /** El azul claro de Ederus, para los datos del lore. */
    private static final TextColor CLARO = Estilo.CLARO;

    private final String id;
    private String display;
    private Tipo tipo = Tipo.COMUN;
    private int color = 0xD7F3FF;

    /** El botin corriente. Reutiliza la tabla de las anomalias: mismo modelo, mismo editor. */
    private final DropTable tabla;

    /** La pieza rara, en su propio hueco. null = la caja todavia no tiene una. */
    private DropEntry unico;

    /** Cuantos objetos de la lista salen en cada apertura. */
    private int tiradas = 3;

    /** Nombre propio de la llave; vacio = se compone con el de la caja. */
    private String nombreLlave = "";

    public Caja(String id, String display) {
        this.id = id;
        this.display = display;
        this.tabla = new DropTable("caja-" + id);
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public void display(String display) {
        this.display = display;
    }

    public Tipo tipo() {
        return tipo;
    }

    public void tipo(Tipo tipo) {
        this.tipo = tipo;
    }

    public TextColor color() {
        return TextColor.color(color);
    }

    public int colorRgb() {
        return color;
    }

    public void colorRgb(int rgb) {
        this.color = rgb;
    }

    public DropTable tabla() {
        return tabla;
    }

    public DropEntry unico() {
        return unico;
    }

    public void unico(DropEntry entry) {
        this.unico = entry;
        if (entry != null) entry.unique(true);
    }

    public int tiradas() {
        return tiradas;
    }

    public void tiradas(int n) {
        this.tiradas = Math.max(1, Math.min(9, n));
    }

    public String nombreLlave() {
        return nombreLlave;
    }

    public void nombreLlave(String s) {
        this.nombreLlave = s == null ? "" : s;
    }

    public Component nombre() {
        return Estilo.texto(display, color());
    }

    public boolean lista() {
        return !tabla.entries().isEmpty() || unico != null;
    }

    /* ------------------------------------------------------------- los objetos */

    /**
     * La llave de esta caja.
     *
     * Lleva la marca de la caja en su contenedor de datos, y es la marca —no el
     * material— lo que se comprueba al abrir: una llave vanilla del Trial Chamber,
     * o la de otra caja, no abre nada aqui.
     */
    public ItemStack llave(NamespacedKey marca, int cantidad) {
        ItemStack item = new ItemStack(tipo.key(), Math.max(1, Math.min(64, cantidad)));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        String nombre = nombreLlave.isEmpty() ? "Llave de " + display : nombreLlave;
        meta.displayName(Estilo.texto(nombre, color()));

        List<Component> lore = new ArrayList<>();
        lore.add(Estilo.texto(tipo.display(), tipo.color()));
        lore.add(Estilo.vacio());
        lore.add(Estilo.linea("Abre", display, CLARO));
        lore.add(Estilo.linea("Se gasta", "al usarla", Estilo.APAGADO));
        if (unico != null) {
            lore.add(Estilo.vacio());
            lore.add(Estilo.texto(" " + Estilo.FLECHA + " ", Estilo.APAGADO)
                    .append(Estilo.texto("Puede salir ", Estilo.APAGADO))
                    .append(DropTable.nameOf(unico.item()).colorIfAbsent(CLARO)
                            .decoration(TextDecoration.ITALIC, false)));
        }
        lore.add(Estilo.vacio());
        lore.add(Estilo.texto("Clic derecho sobre la bóveda", Estilo.APAGADO));

        meta.lore(lore);
        meta.getPersistentDataContainer().set(marca, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    /** El bloque colocable. Misma marca, para saber que caja plantar al ponerlo. */
    public ItemStack bloque(NamespacedKey marca, int cantidad) {
        ItemStack item = new ItemStack(tipo.block(), Math.max(1, Math.min(64, cantidad)));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(Estilo.texto(display, color()));

        List<Component> lore = new ArrayList<>();
        lore.add(Estilo.texto(tipo.display(), tipo.color()));
        lore.add(Estilo.vacio());
        lore.add(Estilo.linea("Se abre con", "Llave de " + display, CLARO));
        lore.add(Estilo.linea("Por apertura", tiradas + " objeto(s)", CLARO));
        lore.add(Estilo.vacio());
        lore.add(Estilo.texto("Colócala donde quieras, las veces", Estilo.APAGADO));
        lore.add(Estilo.texto("que quieras.", Estilo.APAGADO));

        meta.lore(lore);
        meta.getPersistentDataContainer().set(marca, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }
}
