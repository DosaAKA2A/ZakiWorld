package net.ederus.edm.tienda;

import net.ederus.edm.comun.EntradaChat;
import net.ederus.edm.comun.Estilo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Meter en la tienda un articulo que no estaba: "AÑADIENDO → Minerales".
 *
 * Se llega desde /shop edit, entrando en la categoria y pulsando el libro de
 * "Añadir artículo": la categoria la elige quien edita, no se adivina. Aqui se
 * dice QUE objeto (el que se lleva en la mano, o buscandolo por su nombre en
 * español) y a QUE precio, y al guardar la linea entra en precios.yml dentro de
 * esa categoria y el catalogo se recarga, asi que el articulo aparece en la
 * tienda al momento sin tocar ningun fichero a mano.
 *
 * TODO SE HACE CON EL CLIC IZQUIERDO. Un jugador de Bedrock no tiene mas que
 * ese (ver Plataforma), y el editor tiene que valer igual desde un movil.
 *
 * Las reglas son las del catalogo, comprobadas antes de escribir: la clave no
 * puede estar ya en otra categoria (seria comprar barato en una y vender caro
 * en otra), la venta va por debajo de la compra y una variante no se recompra.
 */
public final class EditorNuevo implements Listener {

    private static final int TAM = 45;                 // 5 filas: la ficha
    private static final int TAM_ELEGIR = 54;          // 6 filas: los candidatos

    private static final int RANURA_ITEM = 13;
    private static final int RANURA_MANO = 20;
    private static final int RANURA_BUSCAR = 24;
    private static final int RANURA_COMPRA = 29;
    private static final int RANURA_VENTA = 31;
    private static final int RANURA_TOPE = 33;
    private static final int RANURA_GUARDAR = 39;
    private static final int RANURA_VOLVER = 41;

    /** Cuantos candidatos caben en la pantalla de eleccion (5 filas de 9). */
    private static final int TOPE_CANDIDATOS = 45;
    private static final int RANURA_OTRA_BUSQUEDA = 48;
    private static final int RANURA_VOLVER_ELEGIR = 50;

    /** El articulo a medio escribir. Vive en la ventana: si se cierra, se va. */
    static final class Vista implements InventoryHolder {
        final String categoria;
        final int pagina;                  // la del menu, para volver donde estaba
        Material material;
        EntityType variante;               // solo los spawners la llevan
        double compra;
        double venta;
        int tope;
        /** No vacia = se esta eligiendo el objeto, no rellenando la ficha. */
        final List<Material> candidatos = new ArrayList<>();
        Inventory inv;

        Vista(String categoria, int pagina) {
            this.categoria = categoria;
            this.pagina = pagina;
        }

        boolean eligiendo() { return !candidatos.isEmpty(); }

        String clave() {
            if (material == null) return null;
            return material.name() + (variante != null ? ":" + variante.name() : "");
        }

        @Override public Inventory getInventory() { return inv; }
    }

    private final TiendaPlugin modulo;
    private final Catalogo catalogo;
    private final Secciones secciones;
    private final EntradaChat chat;

    public EditorNuevo(TiendaPlugin modulo, Catalogo catalogo, Secciones secciones, EntradaChat chat) {
        this.modulo = modulo;
        this.catalogo = catalogo;
        this.secciones = secciones;
        this.chat = chat;
    }

    // ------------------------------------------------------------------ abrir

    public void abrir(Player jugador, String categoria, int pagina) {
        Vista vista = new Vista(categoria, pagina);
        /* Lo que lleva en la mano ya viene puesto: quien echa en falta la
         * cubeta de agua la tiene cogida cuando abre esto. Si ya estuviera en
         * el catalogo no se precarga, para no enseñar de entrada un objeto que
         * no se va a poder guardar. */
        ItemStack mano = jugador.getInventory().getItemInMainHand();
        if (!mano.getType().isAir() && catalogo.de(mano.getType().name()) == null) {
            vista.material = mano.getType();
            vista.variante = varianteDe(mano);
        }
        pintar(jugador, vista);
    }

    private void pintar(Player jugador, Vista vista) {
        if (vista.eligiendo()) { pintarEleccion(jugador, vista); return; }

        Inventory inv = Bukkit.createInventory(vista, TAM,
                secciones.texto("titulo-anadiendo", "&x&F&F&9&E&3&D&lAÑADIENDO &8→ &x&D&7&F&3&F&F%categoria%",
                        "%categoria%", nombreCategoria(vista.categoria)));
        vista.inv = inv;

        inv.setItem(RANURA_ITEM, ficha(vista));

        inv.setItem(RANURA_MANO, decorar(new ItemStack(Material.LEAD),
                secciones.texto("nuevo-mano", "&#91F4FFLo que llevas en la mano"),
                List.of(Estilo.valor(enMano(jugador)),
                        Estilo.vacio(),
                        secciones.texto("nuevo-clic-coger", "&8▸ &7Clic para cogerlo"))));

        inv.setItem(RANURA_BUSCAR, decorar(new ItemStack(Material.COMPASS),
                secciones.texto("nuevo-buscar", "&#91F4FFBuscarlo por su nombre"),
                List.of(secciones.texto("nuevo-buscar-nota", "&7Vale el nombre en español o el identificador"),
                        Estilo.vacio(),
                        secciones.texto("nuevo-clic-escribir", "&8▸ &7Clic para escribirlo en el chat"))));

        inv.setItem(RANURA_COMPRA, decorar(new ItemStack(Material.GOLD_INGOT),
                secciones.texto("editor-compra", "&#91F4FFPrecio de compra"),
                List.of(Estilo.valor(vista.compra > 0 ? Estilo.dinero(vista.compra) : "No se compra"),
                        Estilo.vacio(),
                        secciones.texto("nuevo-clic-escribir", "&8▸ &7Clic para escribirlo en el chat"))));

        List<Component> loreVenta = new ArrayList<>();
        loreVenta.add(Estilo.valor(vista.venta > 0 ? Estilo.dinero(vista.venta) : "No se vende"));
        loreVenta.add(Estilo.vacio());
        if (vista.variante != null) {
            loreVenta.add(secciones.texto("editor-variante", "&8▸ &7Los spawners no se venden"));
        } else {
            loreVenta.add(secciones.texto("nuevo-clic-escribir", "&8▸ &7Clic para escribirlo en el chat"));
        }
        inv.setItem(RANURA_VENTA, decorar(new ItemStack(Material.EMERALD),
                secciones.texto("editor-venta", "&#FDFF66Precio de venta"), loreVenta));

        inv.setItem(RANURA_TOPE, decorar(new ItemStack(Material.HOPPER),
                secciones.texto("nuevo-tope", "&#FDFF66Tope de venta"),
                List.of(Estilo.valor(vista.tope > 0 ? vista.tope + " cada 24h" : "Sin tope"),
                        Estilo.vacio(),
                        secciones.texto("nuevo-clic-escribir", "&8▸ &7Clic para escribirlo en el chat"))));

        inv.setItem(RANURA_GUARDAR, guardarBoton(vista));

        inv.setItem(RANURA_VOLVER, decorar(new ItemStack(Material.BARRIER),
                secciones.texto("cantidad-volver", "&x&D&7&F&3&F&FVolver"), List.of()));

        rellenar(inv);
        jugador.openInventory(inv);
        secciones.sonar(jugador, "abrir-categoria");
    }

    /** El objeto elegido, o el hueco que dice que todavia no hay ninguno. */
    private ItemStack ficha(Vista vista) {
        if (vista.material == null) {
            return decorar(new ItemStack(Material.BARRIER),
                    secciones.texto("nuevo-sin-item", "&#FF5C5CTodavía no has elegido el objeto"),
                    List.of(secciones.texto("nuevo-sin-item-nota",
                            "&7Cógelo de la mano o búscalo por su nombre")));
        }
        List<Component> lore = new ArrayList<>();
        lore.add(Estilo.etiqueta("Categoría", Estilo.COMPRA));
        lore.add(Estilo.valor(nombreCategoria(vista.categoria)));
        lore.add(Estilo.etiqueta("Clave", Estilo.COMPRA));
        lore.add(Estilo.valor(vista.clave()));
        return decorar(new ItemStack(vista.material),
                secciones.texto("editor-nombre", "&x&D&7&F&3&F&F%item%",
                        "%item%", nombreDe(vista.material, vista.variante)), lore);
    }

    /** El boton de guardar dice lo que falta; no se queda mudo si no se puede. */
    private ItemStack guardarBoton(Vista vista) {
        String falta = queFalta(vista);
        if (falta != null) {
            return decorar(new ItemStack(Material.GRAY_DYE),
                    secciones.texto("nuevo-no-listo", "&8Guardar"),
                    List.of(Estilo.texto(falta, NamedTextColor.RED)));
        }
        return decorar(new ItemStack(Material.LIME_DYE),
                secciones.texto("nuevo-guardar", "&#4FFF55Guardar en %categoria%",
                        "%categoria%", nombreCategoria(vista.categoria)),
                List.of(Estilo.etiqueta("Compra", Estilo.COMPRA),
                        Estilo.valor(vista.compra > 0 ? Estilo.dinero(vista.compra) : "—"),
                        Estilo.etiqueta("Venta", Estilo.VENTA),
                        Estilo.valor(vista.venta > 0 ? Estilo.dinero(vista.venta) : "—"),
                        Estilo.vacio(),
                        secciones.texto("nuevo-clic-guardar", "&8▸ &7Clic para escribirlo en precios.yml")));
    }

    /** Lo que impide guardar, dicho en una linea, o null si ya se puede. */
    private String queFalta(Vista vista) {
        if (vista.material == null) return "Elige primero el objeto";
        if (vista.compra <= 0 && vista.venta <= 0) return "Pon al menos un precio";
        if (vista.compra > 0 && vista.venta > 0 && vista.venta >= vista.compra) {
            return "La venta tiene que quedar por debajo de la compra";
        }
        Catalogo.Articulo ya = catalogo.de(vista.clave());
        if (ya != null) return "Ya está en " + nombreCategoria(ya.categoria());
        return null;
    }

    // -------------------------------------------------------------- eleccion

    private void pintarEleccion(Player jugador, Vista vista) {
        Inventory inv = Bukkit.createInventory(vista, TAM_ELEGIR,
                secciones.texto("titulo-eligiendo",
                        "&x&F&F&9&E&3&D&lAÑADIENDO &8→ &x&D&7&F&3&F&FElige el objeto"));
        vista.inv = inv;

        for (int i = 0; i < vista.candidatos.size() && i < TOPE_CANDIDATOS; i++) {
            Material m = vista.candidatos.get(i);
            Catalogo.Articulo ya = catalogo.de(m.name());
            List<Component> lore = new ArrayList<>();
            lore.add(Estilo.texto(m.name(), Estilo.APAGADO));
            lore.add(Estilo.vacio());
            /* Los que ya estan se enseñan igual, pero diciendo donde: es la
             * respuesta a "por que no me sale el diamante en la lista". */
            if (ya != null) {
                lore.add(Estilo.texto("Ya está en " + nombreCategoria(ya.categoria()), NamedTextColor.RED));
            } else {
                lore.add(secciones.texto("nuevo-clic-elegir", "&#4FFF55▸ Clic para elegirlo"));
            }
            inv.setItem(i, decorar(new ItemStack(m),
                    secciones.texto("editor-nombre", "&x&D&7&F&3&F&F%item%",
                            "%item%", Motor.bonito(m)), lore));
        }

        inv.setItem(RANURA_OTRA_BUSQUEDA, decorar(new ItemStack(Material.COMPASS),
                secciones.texto("nuevo-otra-busqueda", "&#91F4FFBuscar otra cosa"), List.of()));
        inv.setItem(RANURA_VOLVER_ELEGIR, decorar(new ItemStack(Material.BARRIER),
                secciones.texto("cantidad-volver", "&x&D&7&F&3&F&FVolver"), List.of()));

        rellenar(inv);
        jugador.openInventory(inv);
        secciones.sonar(jugador, "cambiar-pagina");
    }

    // ------------------------------------------------------------------ clics

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Vista) e.setCancelled(true);
    }

    @EventHandler
    public void alPulsar(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Vista vista)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player jugador)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        /* Igual que en el editor de precios: el permiso se mira en cada clic,
         * no solo al abrir la ventana. */
        if (!jugador.hasPermission("ederus.tienda.admin")) { jugador.closeInventory(); return; }

        if (vista.eligiendo()) { clicEnEleccion(jugador, vista, e.getSlot()); return; }

        switch (e.getSlot()) {
            case RANURA_MANO -> coger(jugador, vista);
            case RANURA_BUSCAR -> preguntarObjeto(jugador, vista);
            case RANURA_COMPRA -> preguntarPrecio(jugador, vista, true);
            case RANURA_VENTA -> {
                if (vista.variante != null) { secciones.sonar(jugador, "error"); return; }
                preguntarPrecio(jugador, vista, false);
            }
            case RANURA_TOPE -> preguntarTope(jugador, vista);
            case RANURA_GUARDAR -> guardar(jugador, vista);
            case RANURA_VOLVER -> { secciones.sonar(jugador, "volver"); volver(jugador, vista); }
            default -> { /* el borde no hace nada */ }
        }
    }

    private void clicEnEleccion(Player jugador, Vista vista, int ranura) {
        if (ranura == RANURA_VOLVER_ELEGIR) {
            secciones.sonar(jugador, "volver");
            vista.candidatos.clear();
            pintar(jugador, vista);
            return;
        }
        if (ranura == RANURA_OTRA_BUSQUEDA) { preguntarObjeto(jugador, vista); return; }
        if (ranura < 0 || ranura >= vista.candidatos.size() || ranura >= TOPE_CANDIDATOS) return;

        Material m = vista.candidatos.get(ranura);
        if (yaEsta(jugador, m)) return;
        vista.material = m;
        vista.variante = null;
        vista.candidatos.clear();
        secciones.sonar(jugador, "elegir-item");
        pintar(jugador, vista);
    }

    /** Avisa y devuelve true si ese material ya tiene precio en otra categoria. */
    private boolean yaEsta(Player jugador, Material m) {
        Catalogo.Articulo ya = catalogo.de(m.name());
        if (ya == null) return false;
        Mensajes msg = modulo.mensajes();
        if (msg != null) msg.manda(jugador, "nuevo-repetido",
                "&#FF5C5C%item% ya está en la tienda&8, &7dentro de %categoria%",
                "%item%", Motor.bonito(m), "%categoria%", nombreCategoria(ya.categoria()));
        secciones.sonar(jugador, "error");
        return true;
    }

    private void coger(Player jugador, Vista vista) {
        ItemStack mano = jugador.getInventory().getItemInMainHand();
        Mensajes m = modulo.mensajes();
        if (mano.getType().isAir()) {
            if (m != null) m.manda(jugador, "nuevo-mano-vacia", "&#FF5C5CNo llevas nada en la mano.");
            secciones.sonar(jugador, "error");
            return;
        }
        vista.material = mano.getType();
        /* Un spawner con bicho dentro se coge con su bicho: esa es la clave que
         * usa el catalogo (SPAWNER:PIG) y la que hace que la tienda entregue el
         * spawner lleno en vez de uno vacio. Y una variante no se recompra. */
        vista.variante = varianteDe(mano);
        if (vista.variante != null) vista.venta = 0;
        secciones.sonar(jugador, "elegir-item");
        pintar(jugador, vista);
    }

    private void preguntarObjeto(Player jugador, Vista vista) {
        Mensajes m = modulo.mensajes();
        if (m != null) m.manda(jugador, "nuevo-pide-objeto",
                "&fEscribe el nombre del objeto. &7Escribe cancelar para dejarlo.");
        chat.pedir(jugador, texto -> {
            List<Material> hallados = Nombres.buscar(texto, TOPE_CANDIDATOS);
            if (hallados.isEmpty()) {
                if (m != null) m.manda(jugador, "nuevo-sin-resultados",
                        "&#FF5C5CNo encontré ningún objeto con &7%texto%", "%texto%", texto);
                secciones.sonar(jugador, "error");
                pintar(jugador, vista);
                return;
            }
            /* Con uno solo no se hace elegir: se pone y punto. */
            if (hallados.size() == 1) {
                Material unico = hallados.get(0);
                if (!yaEsta(jugador, unico)) {
                    vista.material = unico;
                    vista.variante = null;
                    secciones.sonar(jugador, "elegir-item");
                }
                pintar(jugador, vista);
                return;
            }
            vista.candidatos.clear();
            vista.candidatos.addAll(hallados);
            pintar(jugador, vista);
        }, () -> { vista.candidatos.clear(); pintar(jugador, vista); });
    }

    private void preguntarPrecio(Player jugador, Vista vista, boolean compra) {
        Mensajes m = modulo.mensajes();
        if (m != null) m.manda(jugador, compra ? "editor-pide-compra" : "editor-pide-venta",
                compra ? "&fEscribe el precio de compra. &7Escribe cancelar para dejarlo."
                       : "&fEscribe el precio de venta. &7Escribe cancelar para dejarlo.");
        chat.pedir(jugador, texto -> {
            Double n = EditorPrecio.leerPrecio(texto);
            if (n == null) {
                if (m != null) m.manda(jugador, "editor-mal-numero",
                        "&#FF5C5CEso no es un precio&8: &7%texto%", "%texto%", texto);
                secciones.sonar(jugador, "error");
            } else {
                if (compra) vista.compra = n; else vista.venta = n;
                secciones.sonar(jugador, "elegir-item");
            }
            pintar(jugador, vista);
        }, () -> pintar(jugador, vista));
    }

    private void preguntarTope(Player jugador, Vista vista) {
        Mensajes m = modulo.mensajes();
        if (m != null) m.manda(jugador, "nuevo-pide-tope",
                "&fEscribe cuántas unidades se pueden vender cada 24h. &7Un 0 lo deja sin tope.");
        chat.pedir(jugador, texto -> {
            Double n = EditorPrecio.leerPrecio(texto);
            if (n == null || n != Math.rint(n) || n > Integer.MAX_VALUE) {
                if (m != null) m.manda(jugador, "nuevo-mal-tope",
                        "&#FF5C5CEso no es un número de unidades&8: &7%texto%", "%texto%", texto);
                secciones.sonar(jugador, "error");
            } else {
                vista.tope = (int) (double) n;
                secciones.sonar(jugador, "elegir-item");
            }
            pintar(jugador, vista);
        }, () -> pintar(jugador, vista));
    }

    private void guardar(Player jugador, Vista vista) {
        Mensajes m = modulo.mensajes();
        String falta = queFalta(vista);
        if (falta != null) {
            if (m != null) m.manda(jugador, "nuevo-no-guardado", "&#FF5C5C%motivo%", "%motivo%", falta + ".");
            secciones.sonar(jugador, "error");
            return;
        }
        String fallo = modulo.anadirArticulo(vista.categoria, vista.material, vista.variante,
                vista.compra, vista.venta, vista.tope);
        if (fallo != null) {
            if (m != null) m.manda(jugador, "nuevo-no-guardado", "&#FF5C5C%motivo%", "%motivo%", fallo);
            secciones.sonar(jugador, "error");
            pintar(jugador, vista);
            return;
        }
        if (m != null) m.manda(jugador, "nuevo-guardado",
                "&x&D&7&F&3&F&F%item% &7entra en &f%categoria%&7: compra &f%compra%&7, venta &f%venta%",
                "%item%", nombreDe(vista.material, vista.variante),
                "%categoria%", nombreCategoria(vista.categoria),
                "%compra%", vista.compra > 0 ? Estilo.dinero(vista.compra) : "—",
                "%venta%", vista.venta > 0 ? Estilo.dinero(vista.venta) : "—");
        secciones.sonar(jugador, "elegir-item");
        /* Se vuelve a la categoria: el articulo nuevo ya esta ahi dentro y verlo
         * en su sitio es la unica confirmacion que vale. */
        volver(jugador, vista);
    }

    private void volver(Player jugador, Vista vista) {
        MenuTienda menu = modulo.menu();
        if (menu == null) { jugador.closeInventory(); return; }
        menu.abrirCategoria(jugador, vista.categoria, vista.pagina, true);
    }

    // ---------------------------------------------------------------- adornos

    /** El bicho de un spawner cogido de la mano, o null si no lleva ninguno. */
    private static EntityType varianteDe(ItemStack pila) {
        if (pila == null || pila.getType() != Material.SPAWNER) return null;
        if (pila.getItemMeta() instanceof BlockStateMeta bsm
                && bsm.getBlockState() instanceof CreatureSpawner cs) {
            return cs.getSpawnedType();
        }
        return null;
    }

    private static String nombreDe(Material material, EntityType variante) {
        if (material == null) return "—";
        if (variante == null) return Motor.bonito(material);
        return "Spawner de " + Nombres.deMob(variante);
    }

    private String enMano(Player jugador) {
        ItemStack mano = jugador.getInventory().getItemInMainHand();
        if (mano.getType().isAir()) return "No llevas nada";
        return nombreDe(mano.getType(), varianteDe(mano));
    }

    /** El nombre que se lee en el menu; si no lo tiene, el id del fichero. */
    private String nombreCategoria(String id) {
        String n = secciones.nombrePlano(id);
        return n != null ? n : id;
    }

    private void rellenar(Inventory inv) {
        ItemStack panel = secciones.relleno();
        if (panel == null) return;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack actual = inv.getItem(i);
            if (actual == null || actual.getType().isAir()) inv.setItem(i, panel.clone());
        }
    }

    private static ItemStack decorar(ItemStack pila, Component titulo, List<Component> lore) {
        ItemMeta meta = pila.getItemMeta();
        if (meta != null) {
            meta.displayName(titulo.decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            pila.setItemMeta(meta);
        }
        return pila;
    }
}
