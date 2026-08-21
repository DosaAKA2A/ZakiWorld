package net.ederus.edm.tienda;

import net.ederus.edm.comun.Estilo;

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

    /**
     * El mensaje va como Component y no como String: serializarlo a texto plano
     * se comia el prefijo, los hex y las negritas de mensajes.yml, que es justo
     * lo que se configura ahi.
     */
    public record Resultado(boolean ok, net.kyori.adventure.text.Component mensaje,
                            int cantidad, double total) {
        static Resultado no(net.kyori.adventure.text.Component mensaje) {
            return new Resultado(false, mensaje, 0, 0);
        }
    }

    /**
     * Cuanto se puede comprar o vender AHORA MISMO y que es lo que lo limita.
     *
     * El motivo es una clave, no una frase: quien lo enseñe pone el texto. Sirve
     * para que la pantalla de cantidad no tenga que rehacer estas cuentas por su
     * cuenta y acabe enseñando un numero distinto del que se cobra.
     */
    public record Limite(int cantidad, String motivo) { }

    private final Catalogo catalogo;
    private final Topes topes;
    private final Registro registro;
    private final Economy economia;
    private final Mercado mercado;
    private Rotacion rotacion;
    private Compras compras;
    private Mensajes mensajes;

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
    public void mensajes(Mensajes m) { this.mensajes = m; }

    /** Texto para el jugador: sale de mensajes.yml, con su prefijo y su color. */
    private net.kyori.adventure.text.Component msg(String clave, String respaldo, String... p) {
        return mensajes == null ? Estilo.legado(respaldo) : mensajes.de(clave, respaldo, p);
    }
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
    /* Un ItemStack pelado por material, reutilizado. Pintar una pagina hace
     * ~1.000 de estas comparaciones y antes cada una creaba su propio stack.
     * Concurrente por si alguna vez se llama fuera del hilo principal: es un
     * cache de solo lectura y no cuesta nada dejarlo a prueba de eso. */
    private static final Map<Material, ItemStack> PLANTILLAS = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean esLimpio(ItemStack stack, Material material) {
        if (stack == null || stack.getType() != material) return false;
        return PLANTILLAS.computeIfAbsent(material, ItemStack::new).isSimilar(stack);
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

    // -------------------------------------------------- lo que costaria / daria

    /** Lo que costaria comprar esa cantidad ahora mismo. */
    public double totalCompraDe(Catalogo.Articulo art, int cantidad) {
        if (art == null || cantidad <= 0) return 0;
        return compraEfectiva(art) * cantidad;
    }

    /**
     * Lo que se pagaria por vender esa cantidad ahora mismo, con el precio
     * integrado a lo largo de la venta y el bonus de Demandas si toca.
     *
     * Lo usan la pantalla de cantidad, la tabla de /etienda mercado y la propia
     * venta. Es a proposito: si cada uno hiciera su cuenta, el dia que cambie la
     * formula uno de los tres se quedaria atras enseñando un numero mentiroso.
     */
    public double totalVentaDe(Catalogo.Articulo art, int cantidad) {
        if (art == null || cantidad <= 0) return 0;
        double total = mercado.totalVenta(art, cantidad, compraEfectiva(art));
        if (rotacion != null) {
            Rotacion.Trato t = rotacion.demanda(art.clave());
            if (t != null) {
                total *= t.factor();
                double techo = compraEfectiva(art) > 0
                        ? compraEfectiva(art) * mercado.margen() * cantidad : Double.MAX_VALUE;
                total = Math.min(total, techo);
            }
        }
        return total;
    }

    /**
     * Lo maximo que puede comprar y por que no mas.
     *
     * Mira los mismos frenos que comprar() y en el mismo orden, pero sin tocar
     * nada. No sustituye a los de comprar(): es la foto para enseñar, y el que
     * decide sigue siendo el que cobra.
     */
    public Limite maximoCompra(Player jugador, Catalogo.Articulo art) {
        if (art == null || !art.seCompra()) return new Limite(0, "no-se-compra");
        if (art.pideePermiso() && !jugador.hasPermission(art.permiso())) return new Limite(0, "permiso");
        if (construir(art, 1) == null) return new Limite(0, "roto");

        int cantidad = MAX_POR_OPERACION;
        String motivo = "operacion";

        if (compras != null && art.tieneLimiteJugador()) {
            int puede = compras.restante(jugador.getUniqueId(), art);
            if (puede < cantidad) { cantidad = puede; motivo = "personal"; }
        }
        if (rotacion != null) {
            Rotacion.Trato t = rotacion.oferta(art.clave());
            if (t != null && rotacion.restanteHoy(t) < cantidad) {
                cantidad = rotacion.restanteHoy(t);
                motivo = "oferta";
            }
        }
        int sitio = huecoLibre(jugador.getInventory(), art);
        if (sitio < cantidad) { cantidad = sitio; motivo = "espacio"; }

        double precio = compraEfectiva(art);
        if (precio > 0) {
            /* En double y comparando antes de convertir: el saldo de este
             * servidor se sale del int sin despeinarse. */
            double puedePagar = Math.floor(economia.getBalance(jugador) / precio);
            if (puedePagar < cantidad) { cantidad = (int) Math.max(0, puedePagar); motivo = "dinero"; }
        }
        return new Limite(Math.max(0, cantidad), motivo);
    }

    /** Lo maximo que puede vender y por que no mas. */
    public Limite maximoVenta(Player jugador, Catalogo.Articulo art) {
        if (art == null || !art.seVende()) return new Limite(0, "no-se-vende");

        int cantidad = MAX_POR_OPERACION;
        String motivo = "operacion";

        int llevas = contarLimpios(jugador.getInventory(), art.material());
        if (llevas < cantidad) { cantidad = llevas; motivo = "stock"; }

        int margen = topes.restante(jugador.getUniqueId(), art);
        if (margen < cantidad) { cantidad = margen; motivo = "tope"; }

        if (rotacion != null) {
            Rotacion.Trato t = rotacion.demanda(art.clave());
            if (t != null && rotacion.restanteHoy(t) < cantidad) {
                cantidad = rotacion.restanteHoy(t);
                motivo = "demanda";
            }
        }
        return new Limite(Math.max(0, cantidad), motivo);
    }

    // ------------------------------------------------------------------ vender

    public Resultado vender(Player jugador, Material material, int pedido) {
        Catalogo.Articulo art = catalogo.de(material);
        if (art == null) return Resultado.no(msg("no-esta", "Ese objeto no está en la tienda."));
        if (!art.seVende()) return Resultado.no(msg("no-se-vende", "La tienda no compra %item%.", "%item%", bonito(material)));
        if (pedido <= 0) return Resultado.no(Estilo.legado("&cLa cantidad tiene que ser mayor que cero."));

        int disponible = contarLimpios(jugador.getInventory(), material);
        if (disponible <= 0) {
            return Resultado.no(msg("nada-que-vender",
                    "No tienes %item% que la tienda acepte.", "%item%", bonito(material)));
        }

        int margen = topes.restante(jugador.getUniqueId(), art);
        if (margen <= 0) {
            long espera = topes.esperaMs(jugador.getUniqueId(), art);
            return Resultado.no(Estilo.legado("&cLlegaste al tope de " + art.topeVenta() + " "
                    + bonito(material) + ". Vuelve en " + duracion(espera) + "."));
        }

        int cantidad = Math.min(Math.min(pedido, disponible), Math.min(margen, MAX_POR_OPERACION));
        /* Tope diario de SERVIDOR de la seccion Demandas: sin el, cuatro
         * jugadores agotarian el precio inflado en la primera hora. */
        if (rotacion != null) {
            Rotacion.Trato t = rotacion.demanda(art.clave());
            if (t != null) {
                int quedaHoy = rotacion.restanteHoy(t);
                if (quedaHoy <= 0) {
                    return Resultado.no(msg("demanda-cubierta", "La demanda de %item% ya se cubrió hoy.", "%item%", bonito(material)));
                }
                cantidad = Math.min(cantidad, quedaHoy);
            }
        }

        // 1. los items fuera
        int quitados = quitarLimpios(jugador.getInventory(), material, cantidad);
        if (quitados <= 0) return Resultado.no(Estilo.legado("&cNo pude sacar los objetos del inventario."));

        // 2. el dinero despues
        /* El total se integra a lo largo de la venta, no se multiplica por el
         * precio de la primera unidad: vender de golpe tiene que pagar lo mismo
         * que vender a trozos. */
        double total = totalVentaDe(art, quitados);
        double unitario = total / quitados;
        EconomyResponse resp = economia.depositPlayer(jugador, total);
        if (!resp.transactionSuccess()) {
            entregar(jugador, art, quitados);                 // se devuelve TODO
            return Resultado.no(msg("banco-fallo", "El banco rechazó la operación: %motivo%",
                    "%motivo%", String.valueOf(resp.errorMessage)));
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
        String clave = aviso.isEmpty() ? "venta-hecha" : "venta-hecha-todo";
        return new Resultado(true, msg(clave,
                "Vendiste %cantidad% x %item% por %total%" + aviso,
                "%cantidad%", String.valueOf(quitados),
                "%item%", bonito(material),
                "%total%", fmt(total)), quitados, total);
    }

    // ----------------------------------------------------------------- comprar

    public Resultado comprar(Player jugador, Catalogo.Articulo art, int pedido) {
        if (art == null) return Resultado.no(msg("no-esta", "Ese objeto no está en la tienda."));
        if (!art.seCompra()) return Resultado.no(msg("no-se-compra", "La tienda no vende %item%.", "%item%", nombre(art)));
        if (pedido <= 0) return Resultado.no(Estilo.legado("&cLa cantidad tiene que ser mayor que cero."));

        /* El permiso y su mensaje vienen del propio articulo: los spawners de
         * combate piden group.level5 y traen escrito que decir si no lo tienes. */
        if (art.pideePermiso() && !jugador.hasPermission(art.permiso())) {
            String m = art.mensajePermiso();
            return Resultado.no(m != null && !m.isBlank()
                    ? Estilo.legado(m)
                    : msg("sin-permiso", "No tienes permiso para comprar %item%.", "%item%", nombre(art)));
        }

        /* Limite DE POR VIDA, no por dia: 'Limite personal: 6' son seis en toda
         * la partida, que es lo que significa en su tienda. */
        if (compras != null && art.tieneLimiteJugador()) {
            int puede = compras.restante(jugador.getUniqueId(), art);
            if (puede <= 0) {
                return Resultado.no(msg("maximo-alcanzado", "Ya tienes el máximo de %item% (%limite%).",
                        "%item%", nombre(art), "%limite%", String.valueOf(art.limiteJugador())));
            }
            pedido = Math.min(pedido, puede);
        }

        /* Se comprueba ANTES de cobrar que se puede construir: mejor no vender
         * que cobrar 75.000 por un spawner vacio. */
        if (construir(art, 1) == null) {
            return Resultado.no(Estilo.legado("&cNo pude preparar ese objeto. Avisa a un administrador."));
        }

        int cantidad = Math.min(pedido, MAX_POR_OPERACION);
        if (rotacion != null) {
            Rotacion.Trato t = rotacion.oferta(art.clave());
            if (t != null) {
                int quedaHoy = rotacion.restanteHoy(t);
                if (quedaHoy <= 0) {
                    return Resultado.no(msg("oferta-agotada", "La oferta de %item% se agotó hoy.", "%item%", nombre(art)));
                }
                cantidad = Math.min(cantidad, quedaHoy);
            }
        }
        int sitio = huecoLibre(jugador.getInventory(), art);
        if (sitio <= 0) return Resultado.no(msg("sin-espacio", "No te cabe nada más en el inventario."));
        if (sitio < cantidad) cantidad = sitio;

        double coste = compraEfectiva(art) * cantidad;
        if (!economia.has(jugador, coste)) {
            return Resultado.no(msg("sin-dinero", "Te faltan %falta%.",
                    "%falta%", fmt(coste - economia.getBalance(jugador))));
        }

        // 1. cobrar
        EconomyResponse resp = economia.withdrawPlayer(jugador, coste);
        if (!resp.transactionSuccess()) {
            return Resultado.no(msg("banco-fallo", "El banco rechazó la operación: %motivo%",
                    "%motivo%", String.valueOf(resp.errorMessage)));
        }

        // 2. entregar (lo que no quepa cae al suelo, nunca se pierde)
        if (!entregar(jugador, art, cantidad)) {
            economia.depositPlayer(jugador, coste);           // se devuelve el dinero
            return Resultado.no(Estilo.legado("&cNo pude entregarte el objeto; se te devolvió el dinero."));
        }

        if (rotacion != null && rotacion.oferta(art.clave()) != null) rotacion.anotar(art.clave(), cantidad);
        if (compras != null) compras.anotar(jugador.getUniqueId(), art, cantidad);
        registro.anotar("COMPRA", jugador.getName(), cantidad, art.clave(),
                compraEfectiva(art), coste, economia.getBalance(jugador));

        return new Resultado(true, msg("compra-hecha",
                "Compraste %cantidad% x %item% por %total%",
                "%cantidad%", String.valueOf(cantidad),
                "%item%", nombre(art),
                "%total%", fmt(coste)), cantidad, coste);
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
        if (vistos.isEmpty()) return Resultado.no(msg("nada-que-vender-todo", "No tienes nada que la tienda compre."));

        double total = 0;
        int piezas = 0, tipos = 0;
        for (Material m : vistos) {
            Resultado r = vender(jugador, m, MAX_POR_OPERACION);
            if (!r.ok()) continue;
            total += r.total();
            piezas += r.cantidad();
            tipos++;
        }
        if (tipos == 0) return Resultado.no(msg("nada-que-vender-todo", "No tienes nada que la tienda compre."));
        return new Resultado(true, msg("sellall-hecho",
                "Vendiste %cantidad% objetos de %tipos% tipos por %total%",
                "%cantidad%", String.valueOf(piezas),
                "%tipos%", String.valueOf(tipos),
                "%total%", fmt(total)), piezas, total);
    }

    // ------------------------------------------------------------------ varios

    public static String nombre(Catalogo.Articulo art) {
        /* "Spawner", no "Generador": Minecraft lo traduce asi pero en Ederus
         * nadie lo llama de esa forma y la categoria se llama Spawners. */
        if (art.spawner() == null) return bonito(art.material());
        return "Spawner de " + Nombres.deMob(art.spawner());
    }

    /** El nombre que lee el jugador, en espanol. Ver {@link Nombres}. */
    public static String bonito(Material material) { return Nombres.de(material); }

    public static String bonito(String crudo) { return Nombres.porDefecto(crudo); }

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
