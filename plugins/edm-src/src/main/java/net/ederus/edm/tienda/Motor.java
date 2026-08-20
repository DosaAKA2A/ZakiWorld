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
    private Rotacion rotacion;
    private Compras compras;

    public Motor(Catalogo catalogo, Topes topes, Registro registro, Economy economia, Mercado mercado) {
        this.catalogo = catalogo;
        this.topes = topes;
        this.registro = registro;
        this.economia = economia;
        this.mercado = mercado;
    }

    public Mercado mercado() { return mercado; }

    /** El saldo del jugador, para la cabeza del menu. */
    public double saldo(Player jugador) { return economia.getBalance(jugador); }

    public void rotacion(Rotacion r) { this.rotacion = r; }
    public void compras(Compras c) { this.compras = c; }
    public Compras compras() { return compras; }
    public Rotacion rotacion() { return rotacion; }

    /** Lo que cuesta comprar una unidad ahora mismo, con la oferta del dia. */
    public double compraEfectiva(Catalogo.Articulo art) {
        if (rotacion == null) return art.compra();
        Rotacion.Trato t = rotacion.oferta(art.clave());
        return t == null ? art.compra() : art.compra() * t.factor();
    }

    /**
     * Lo que se paga por vender una unidad ahora mismo: precio dinamico, mas el
     * bonus de Demandas si toca, y SIEMPRE recortado contra la compra efectiva.
     * El recorte va el ultimo a proposito: es la ultima palabra.
     */
    public double ventaEfectiva(Catalogo.Articulo art) {
        double compra = compraEfectiva(art);
        double precio = mercado.ventaEfectiva(art, compra);
        if (rotacion != null) {
            Rotacion.Trato t = rotacion.demanda(art.clave());
            if (t != null) {
                precio *= t.factor();
                if (compra > 0) precio = Math.min(precio, compra * mercado.margen());
            }
        }
        return precio;
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
        /* Tope diario de SERVIDOR de la seccion Demandas: sin el, cuatro
         * jugadores agotarian el precio inflado en la primera hora. */
        if (rotacion != null) {
            Rotacion.Trato t = rotacion.demanda(art.clave());
            if (t != null) {
                int quedaHoy = rotacion.restanteHoy(t);
                if (quedaHoy <= 0) {
                    return Resultado.no("La demanda de " + bonito(material)
                            + " ya se cubrio hoy. Manana rota.");
                }
                cantidad = Math.min(cantidad, quedaHoy);
            }
        }

        // 1. los items fuera
        int quitados = quitarLimpios(jugador.getInventory(), material, cantidad);
        if (quitados <= 0) return Resultado.no("No pude sacar los items del inventario.");

        // 2. el dinero despues
        /* El total se integra a lo largo de la venta, no se multiplica por el
         * precio de la primera unidad: vender de golpe tiene que pagar lo mismo
         * que vender a trozos. */
        double total = mercado.totalVenta(art, quitados, compraEfectiva(art));
        if (rotacion != null) {
            Rotacion.Trato t = rotacion.demanda(art.clave());
            if (t != null) {
                total *= t.factor();
                double techo = compraEfectiva(art) > 0
                        ? compraEfectiva(art) * mercado.margen() * quitados : Double.MAX_VALUE;
                total = Math.min(total, techo);
            }
        }
        double unitario = total / quitados;
        EconomyResponse resp = economia.depositPlayer(jugador, total);
        if (!resp.transactionSuccess()) {
            entregar(jugador, art, quitados);                 // se devuelve TODO
            return Resultado.no("El banco rechazo la operacion: " + resp.errorMessage);
        }

        topes.anotar(jugador.getUniqueId(), art, quitados);
        mercado.anotarVenta(art, quitados);
        if (rotacion != null && rotacion.demanda(art.clave()) != null) rotacion.anotar(art.clave(), quitados);
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

        /* El permiso y su mensaje vienen del propio articulo: los spawners de
         * combate piden group.level5 y traen escrito que decir si no lo tienes. */
        if (art.pideePermiso() && !jugador.hasPermission(art.permiso())) {
            String m = art.mensajePermiso();
            return Resultado.no(m != null && !m.isBlank()
                    ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(Estilo.legado(m))
                    : "No tienes permiso para comprar " + nombre(art) + ".");
        }

        /* Limite DE POR VIDA, no por dia: 'Limite personal: 6' son seis en toda
         * la partida, que es lo que significa en su tienda. */
        if (compras != null && art.tieneLimiteJugador()) {
            int puede = compras.restante(jugador.getUniqueId(), art);
            if (puede <= 0) {
                return Resultado.no("Ya tienes el maximo de " + nombre(art)
                        + " (" + art.limiteJugador() + ").");
            }
            pedido = Math.min(pedido, puede);
        }

        /* Se comprueba ANTES de cobrar que se puede construir: mejor no vender
         * que cobrar 75.000 por un spawner vacio. */
        if (construir(art, 1) == null) {
            return Resultado.no("No pude preparar ese item. Avisa a un administrador.");
        }

        int cantidad = Math.min(pedido, MAX_POR_OPERACION);
        if (rotacion != null) {
            Rotacion.Trato t = rotacion.oferta(art.clave());
            if (t != null) {
                int quedaHoy = rotacion.restanteHoy(t);
                if (quedaHoy <= 0) {
                    return Resultado.no("La oferta de " + nombre(art) + " se agoto hoy. Manana rota.");
                }
                cantidad = Math.min(cantidad, quedaHoy);
            }
        }
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

        if (rotacion != null && rotacion.oferta(art.clave()) != null) rotacion.anotar(art.clave(), cantidad);
        if (compras != null) compras.anotar(jugador.getUniqueId(), art, cantidad);
        registro.anotar("COMPRA", jugador.getName(), cantidad, art.clave(),
                compraEfectiva(art), coste, economia.getBalance(jugador));

        return new Resultado(true, "Compraste " + cantidad + " x " + nombre(art)
                + " por " + fmt(coste), cantidad, coste);
    }

    /**
     * Vende TODO lo vendible que lleve encima. Es el /sellall de siempre: la
     * gente lo usa a diario y sin el la tienda se siente un paso atras.
     * Va item a item por el camino normal, asi que respeta el precio dinamico,
     * el recorte contra la compra y el registro.
     */
    public Resultado venderTodo(Player jugador) {
        java.util.Set<Material> vistos = new java.util.LinkedHashSet<>();
        for (ItemStack s : jugador.getInventory().getStorageContents()) {
            if (s == null || s.getType().isAir()) continue;
            Catalogo.Articulo a = catalogo.de(s.getType());
            if (a != null && a.seVende() && esLimpio(s, s.getType())) vistos.add(s.getType());
        }
        if (vistos.isEmpty()) return Resultado.no("No llevas nada que la tienda compre.");

        double total = 0;
        int piezas = 0, tipos = 0;
        for (Material m : vistos) {
            Resultado r = vender(jugador, m, MAX_POR_OPERACION);
            if (!r.ok()) continue;
            total += r.total();
            piezas += r.cantidad();
            tipos++;
        }
        if (tipos == 0) return Resultado.no("No se pudo vender nada.");
        return new Resultado(true, "Vendiste " + piezas + " objetos de " + tipos
                + (tipos == 1 ? " tipo" : " tipos") + " por " + fmt(total), piezas, total);
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
