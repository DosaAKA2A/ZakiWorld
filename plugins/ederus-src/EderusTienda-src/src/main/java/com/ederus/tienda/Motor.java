package com.ederus.tienda;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Comprar y vender. Es la parte peligrosa del plugin: un fallo aqui no rompe una
 * partida, imprime dinero para 372 cuentas y eso no se revierte con un backup.
 *
 * Dos reglas que no se tocan:
 *
 *  1. PRIMERO se quitan los items, DESPUES se paga. Si el pago falla, se
 *     devuelven. Al reves, un error de Vault regalaria el dinero y el item.
 *  2. Solo se compran items "a pelo". El poder de Ederus son los MMOItems, y
 *     todos llevan datos dentro: si la tienda aceptara una espada de diamante
 *     legendaria como DIAMOND_SWORD, la destruiria pagando calderilla.
 */
public final class Motor {

    /** Techo por operacion, para que un /tienda vender 999999 no barra el inventario entero. */
    public static final int MAX_POR_OPERACION = 2304;   // 36 stacks

    public record Resultado(boolean ok, String mensaje, int cantidad, double total) {
        static Resultado no(String mensaje) { return new Resultado(false, mensaje, 0, 0); }
    }

    private final Catalogo catalogo;
    private final Topes topes;
    private final Registro registro;
    private final Economy economia;

    public Motor(Catalogo catalogo, Topes topes, Registro registro, Economy economia) {
        this.catalogo = catalogo;
        this.topes = topes;
        this.registro = registro;
        this.economia = economia;
    }

    /**
     * Un stack "a pelo": el mismo material sin nada encima.
     *
     * Se compara contra un ItemStack recien creado en vez de ir preguntando meta
     * por meta (nombre, lore, encantamientos, durabilidad, modelo, PDC...). Esa
     * lista siempre se queda corta cuando sale una version nueva; isSimilar()
     * compara los metadatos enteros y no se deja nada, incluido lo que guardan
     * MMOItems y compania.
     */
    static boolean esLimpio(ItemStack stack, Material material) {
        if (stack == null || stack.getType() != material) return false;
        return new ItemStack(material).isSimilar(stack);
    }

    static int contarLimpios(Inventory inv, Material material) {
        int n = 0;
        for (ItemStack s : inv.getStorageContents()) if (esLimpio(s, material)) n += s.getAmount();
        return n;
    }

    /** Quita hasta 'cantidad' y devuelve cuantos quito de verdad. */
    private static int quitarLimpios(Inventory inv, Material material, int cantidad) {
        int quedan = cantidad;
        ItemStack[] contenido = inv.getStorageContents();
        for (int i = 0; i < contenido.length && quedan > 0; i++) {
            ItemStack s = contenido[i];
            if (!esLimpio(s, material)) continue;
            int quita = Math.min(quedan, s.getAmount());
            if (quita >= s.getAmount()) contenido[i] = null;
            else s.setAmount(s.getAmount() - quita);
            quedan -= quita;
        }
        inv.setStorageContents(contenido);
        return cantidad - quedan;
    }

    /** Mete lo que quepa y lo que no, al suelo: nunca se pierde nada. */
    private static void entregar(Player jugador, Material material, int cantidad) {
        List<ItemStack> pilas = new ArrayList<>();
        int quedan = cantidad;
        int max = material.getMaxStackSize();
        while (quedan > 0) {
            int n = Math.min(max, quedan);
            pilas.add(new ItemStack(material, n));
            quedan -= n;
        }
        for (ItemStack pila : pilas) {
            Map<Integer, ItemStack> sobra = jugador.getInventory().addItem(pila);
            for (ItemStack s : sobra.values()) jugador.getWorld().dropItemNaturally(jugador.getLocation(), s);
        }
    }

    private static int huecoLibre(Inventory inv, Material material) {
        int max = material.getMaxStackSize();
        int sitio = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) sitio += max;
            else if (esLimpio(s, material)) sitio += Math.max(0, max - s.getAmount());
        }
        return sitio;
    }

    // ------------------------------------------------------------------ vender

    public Resultado vender(Player jugador, Material material, int pedido) {
        Catalogo.Articulo art = catalogo.de(material);
        if (art == null) return Resultado.no("Ese item no esta en la tienda.");
        if (!art.seVende()) return Resultado.no("La tienda no compra " + bonito(material) + ".");
        if (pedido <= 0) return Resultado.no("La cantidad tiene que ser mayor que cero.");

        int disponible = contarLimpios(jugador.getInventory(), material);
        if (disponible <= 0) {
            return Resultado.no("No llevas " + bonito(material)
                    + " que la tienda acepte (los items con nombre, encantados o de MMOItems no valen).");
        }

        int margen = topes.restante(jugador.getUniqueId(), art);
        if (margen <= 0) {
            long espera = topes.esperaMs(jugador.getUniqueId(), art);
            return Resultado.no("Llegaste al tope de " + art.topeVenta() + " " + bonito(material)
                    + ". Vuelve en " + duracion(espera) + ".");
        }

        int cantidad = Math.min(Math.min(pedido, disponible), Math.min(margen, MAX_POR_OPERACION));

        // 1. los items fuera
        int quitados = quitarLimpios(jugador.getInventory(), material, cantidad);
        if (quitados <= 0) return Resultado.no("No pude sacar los items del inventario.");

        // 2. el dinero despues
        double total = art.venta() * quitados;
        EconomyResponse resp = economia.depositPlayer(jugador, total);
        if (!resp.transactionSuccess()) {
            entregar(jugador, material, quitados);            // se devuelve TODO
            return Resultado.no("El banco rechazo la operacion: " + resp.errorMessage);
        }

        topes.anotar(jugador.getUniqueId(), art, quitados);
        registro.anotar("VENTA", jugador.getName(), quitados, material.name(),
                art.venta(), total, economia.getBalance(jugador));

        String aviso = "";
        if (quitados < pedido) {
            if (quitados >= margen && art.tieneTope()) aviso = " (tope alcanzado)";
            else if (quitados >= disponible) aviso = " (era todo lo que llevabas)";
        }
        return new Resultado(true, "Vendiste " + quitados + " x " + bonito(material)
                + " por " + fmt(total) + aviso, quitados, total);
    }

    // ----------------------------------------------------------------- comprar

    public Resultado comprar(Player jugador, Material material, int pedido) {
        Catalogo.Articulo art = catalogo.de(material);
        if (art == null) return Resultado.no("Ese item no esta en la tienda.");
        if (!art.seCompra()) return Resultado.no("La tienda no vende " + bonito(material) + ".");
        if (pedido <= 0) return Resultado.no("La cantidad tiene que ser mayor que cero.");

        int cantidad = Math.min(pedido, MAX_POR_OPERACION);
        int sitio = huecoLibre(jugador.getInventory(), material);
        if (sitio <= 0) return Resultado.no("No te cabe nada mas en el inventario.");
        if (sitio < cantidad) cantidad = sitio;

        double coste = art.compra() * cantidad;
        if (!economia.has(jugador, coste)) {
            return Resultado.no("Te faltan " + fmt(coste - economia.getBalance(jugador)) + ".");
        }

        // 1. cobrar
        EconomyResponse resp = economia.withdrawPlayer(jugador, coste);
        if (!resp.transactionSuccess()) {
            return Resultado.no("El banco rechazo la operacion: " + resp.errorMessage);
        }

        // 2. entregar (lo que no quepa cae al suelo, nunca se pierde)
        entregar(jugador, material, cantidad);

        registro.anotar("COMPRA", jugador.getName(), cantidad, material.name(),
                art.compra(), coste, economia.getBalance(jugador));

        return new Resultado(true, "Compraste " + cantidad + " x " + bonito(material)
                + " por " + fmt(coste), cantidad, coste);
    }

    // ------------------------------------------------------------------ varios

    public static String bonito(Material material) {
        String s = material.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static String fmt(double d) {
        return String.format(java.util.Locale.US, "%,.2f", d);
    }

    public static String duracion(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60;
        if (h > 0) return m > 0 ? h + "h " + m + "m" : h + "h";
        if (m > 0) return m + "m";
        return Math.max(1, s) + "s";
    }
}
