package net.ederus.edm.tienda;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Comprar y vender. Es la parte peligrosa del modulo: un fallo aqui no rompe una
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

    /** Techo por operacion, para que un /etienda vender 999999 no barra el inventario entero. */
    public static final int MAX_POR_OPERACION = 2304;   // 36 stacks

    public record Resultado(boolean ok, String mensaje, int cantidad, double total) {
        static Resultado no(String mensaje) { return new Resultado(false, mensaje, 0, 0); }
    }

    private final Catalogo catalogo;
    private final Topes topes;
    private final Registro registro;
    private final Economy economia;
    private final Mercado mercado;

    public Motor(Catalogo catalogo, Topes topes, Registro registro, Economy economia, Mercado mercado) {
        this.catalogo = catalogo;
        this.topes = topes;
        this.registro = registro;
        this.economia = economia;
        this.mercado = mercado;
    }

    public Mercado mercado() { return mercado; }

    /** Lo que cuesta comprar una unidad ahora mismo (aqui entrara Ofertas). */
    public double compraEfectiva(Catalogo.Articulo art) {
        return art.compra();
    }

    /** Lo que se paga por vender una unidad ahora mismo. */
    public double ventaEfectiva(Catalogo.Articulo art) {
        return mercado.ventaEfectiva(art, compraEfectiva(art));
    }

    /**
     * Un stack "a pelo": el mismo material sin nada encima.
     *
     * Se compara contra un ItemStack recien creado en vez de ir preguntando meta
     * por meta (nombre, lore, encantamientos, durabilidad, modelo, PDC...). Esa
     * lista siempre se queda corta cuando sale una version nueva; isSimilar()
     * compara los metadatos enteros y no se deja nada, incluido lo que guardan
     * MMOItems y compania. Tambien deja fuera un spawner con mob dentro, que es
     * lo que queremos: las variantes solo se compran.
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

    /**
     * Construye el item del articulo. Para un spawner, le mete su mob dentro:
     * sin esto la tienda entregaria un spawner vacio y el jugador habria pagado
     * 75.000 por un bloque inutil.
     */
    static ItemStack construir(Catalogo.Articulo art, int cantidad) {
        ItemStack pila = new ItemStack(art.material(), cantidad);
        if (art.spawner() == null) return pila;

        if (pila.getItemMeta() instanceof BlockStateMeta bsm
                && bsm.getBlockState() instanceof CreatureSpawner cs) {
            cs.setSpawnedType(art.spawner());
            bsm.setBlockState(cs);
            pila.setItemMeta(bsm);
            return pila;
        }
        /* Si la API cambia y esto deja de funcionar, mejor no entregar nada que
         * cobrar por un spawner vacio. Lo detecta comprar() y aborta el cobro. */
        return null;
    }

    /** Mete lo que quepa y lo que no, al suelo: nunca se pierde nada. */
    private static boolean entregar(Player jugador, Catalogo.Articulo art, int cantidad) {
        List<ItemStack> pilas = new ArrayList<>();
        int quedan = cantidad;
        int max = art.material().getMaxStackSize();
        while (quedan > 0) {
            int n = Math.min(max, quedan);
            ItemStack pila = construir(art, n);
            if (pila == null) return false;
            pilas.add(pila);
            quedan -= n;
        }
        for (ItemStack pila : pilas) {
            Map<Integer, ItemStack> sobra = jugador.getInventory().addItem(pila);
            for (ItemStack s : sobra.values()) jugador.getWorld().dropItemNaturally(jugador.getLocation(), s);
        }
        return true;
    }

    private static int huecoLibre(Inventory inv, Catalogo.Articulo art) {
        int max = art.material().getMaxStackSize();
        int sitio = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) sitio += max;
            /* Un spawner con mob no apila con otro de otro mob: solo cuentan los
             * huecos vacios. Para el resto, un stack a medias tambien sirve. */
            else if (art.spawner() == null && esLimpio(s, art.material())) sitio += Math.max(0, max - s.getAmount());
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
        double unitario = ventaEfectiva(art);
        double total = unitario * quitados;
        EconomyResponse resp = economia.depositPlayer(jugador, total);
        if (!resp.transactionSuccess()) {
            entregar(jugador, art, quitados);                 // se devuelve TODO
            return Resultado.no("El banco rechazo la operacion: " + resp.errorMessage);
        }

        topes.anotar(jugador.getUniqueId(), art, quitados);
        mercado.anotarVenta(art, quitados);
        registro.anotar("VENTA", jugador.getName(), quitados, art.clave(),
                unitario, total, economia.getBalance(jugador));
        /* Si el recorte contra la compra llego a actuar, queda constancia: es la
         * unica pista de que un precio se habia ido de rango. */
        if (mercado.recortado(art, compraEfectiva(art))) {
            registro.anotar("RECORTE", jugador.getName(), quitados, art.clave(),
                    unitario, total, economia.getBalance(jugador));
        }

        String aviso = "";
        if (quitados < pedido) {
            if (art.tieneTope() && quitados >= margen) aviso = " (tope alcanzado)";
            else if (quitados >= disponible) aviso = " (era todo lo que llevabas)";
        }
        return new Resultado(true, "Vendiste " + quitados + " x " + bonito(material)
                + " por " + fmt(total) + aviso, quitados, total);
    }

    // ----------------------------------------------------------------- comprar

    public Resultado comprar(Player jugador, Catalogo.Articulo art, int pedido) {
        if (art == null) return Resultado.no("Ese item no esta en la tienda.");
        if (!art.seCompra()) return Resultado.no("La tienda no vende " + nombre(art) + ".");
        if (pedido <= 0) return Resultado.no("La cantidad tiene que ser mayor que cero.");

        /* Se comprueba ANTES de cobrar que se puede construir: mejor no vender
         * que cobrar 75.000 por un spawner vacio. */
        if (construir(art, 1) == null) {
            return Resultado.no("No pude preparar ese item. Avisa a un administrador.");
        }

        int cantidad = Math.min(pedido, MAX_POR_OPERACION);
        int sitio = huecoLibre(jugador.getInventory(), art);
        if (sitio <= 0) return Resultado.no("No te cabe nada mas en el inventario.");
        if (sitio < cantidad) cantidad = sitio;

        double coste = compraEfectiva(art) * cantidad;
        if (!economia.has(jugador, coste)) {
            return Resultado.no("Te faltan " + fmt(coste - economia.getBalance(jugador)) + ".");
        }

        // 1. cobrar
        EconomyResponse resp = economia.withdrawPlayer(jugador, coste);
        if (!resp.transactionSuccess()) {
            return Resultado.no("El banco rechazo la operacion: " + resp.errorMessage);
        }

        // 2. entregar (lo que no quepa cae al suelo, nunca se pierde)
        if (!entregar(jugador, art, cantidad)) {
            economia.depositPlayer(jugador, coste);           // se devuelve el dinero
            return Resultado.no("No pude entregarte el item; se te devolvio el dinero.");
        }

        registro.anotar("COMPRA", jugador.getName(), cantidad, art.clave(),
                compraEfectiva(art), coste, economia.getBalance(jugador));

        return new Resultado(true, "Compraste " + cantidad + " x " + nombre(art)
                + " por " + fmt(coste), cantidad, coste);
    }

    // ------------------------------------------------------------------ varios

    public static String nombre(Catalogo.Articulo art) {
        if (art.spawner() == null) return bonito(art.material());
        return "Spawner de " + bonito(art.spawner().name());
    }

    public static String bonito(Material material) { return bonito(material.name()); }

    public static String bonito(String crudo) {
        String s = crudo.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
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
