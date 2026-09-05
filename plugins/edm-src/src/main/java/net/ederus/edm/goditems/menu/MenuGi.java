package net.ederus.edm.goditems.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.Indyuce.mmoitems.api.Type;
import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.Plataforma;
import net.ederus.edm.goditems.Accion;
import net.ederus.edm.goditems.Acciones;
import net.ederus.edm.goditems.Activador;
import net.ederus.edm.goditems.Args;
import net.ederus.edm.goditems.Catalogo;
import net.ederus.edm.goditems.Ctx;
import net.ederus.edm.goditems.GodItem;
import net.ederus.edm.goditems.GodItemsPlugin;
import net.ederus.edm.goditems.Linea;
import net.ederus.edm.goditems.Numeros;
import net.ederus.edm.goditems.Particulas;
import net.ederus.edm.goditems.mmo.Plantillas;
import net.kyori.adventure.text.Component;

/**
 * La interfaz de GodItems.
 *
 * LA REGLA QUE MANDA SOBRE TODAS LAS DEMAS: **todo se hace con clic izquierdo**.
 * Desde Bedrock, Geyser traduce cualquier toque a clic izquierdo y no existen ni
 * el clic derecho ni el shift-clic. Por eso lo que en otros plugins es un
 * modificador del clic (borrar, subir, bajar, alternar) aqui es una casilla o
 * una pantalla propia. No es una simplificacion: es que si no, media plantilla
 * del servidor no podria editar un item.
 *
 * La segunda regla: **no hay que recordar ninguna clave**. Antes, para poner una
 * accion habia que escribir `EXPLOSION @yo radio:6 dano:10` de memoria en el
 * chat. Ahora se elige la accion de un catalogo por grupos y cada argumento
 * tiene su casilla con su valor actual, su tipo y su ayuda; lo que se escribe
 * por chat es solo el valor suelto que no se puede elegir de una lista (un
 * numero, un texto). Esa es la diferencia entre esto y un bloc de notas.
 *
 * Lo que se guarda sigue siendo una LINEA DE TEXTO en el YAML. El editor no
 * inventa un formato nuevo: lee la linea, la reparte por casillas y la vuelve a
 * escribir (`Linea`). Asi el mismo item se puede seguir tocando a mano desde el
 * panel, que es como se arregla un servidor cuando no se puede entrar a jugar.
 */
public final class MenuGi implements Listener {

    private static final int TAM = 54;

    /** Las cuatro filas utiles, de 7 en 7, como el resto de menus de Ederus. */
    private static final int[] RANURAS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    private static final int POR_PAGINA = RANURAS.length;

    private static final int R_CABECERA = 4;
    private static final int R_ANTERIOR = 45;
    private static final int R_A = 47;
    private static final int R_B = 48;
    private static final int R_VOLVER = 49;
    private static final int R_C = 50;
    private static final int R_D = 51;
    private static final int R_SIGUIENTE = 53;

    /* Los colores de la casa. */
    private static final String AZUL = "&#91F4FF";
    private static final String CLARO = "&#D7F3FF";
    private static final String VERDE = "&#4FFF55";
    private static final String AMARILLO = "&#FDFF66";
    private static final String GRIS = "&#545454";

    public enum Pantalla {
        RAIZ, TIPOS, ITEMS_MMO,
        FICHA, ASPECTO,
        ACTIVADOR, LISTA, LINEA,
        ACT_GRUPOS, ACT_LISTA,
        CAT_GRUPOS, CAT_LISTA,
        EDITOR, ELEGIR,
        PART_GRUPOS, PART_LISTA,
        SON_FAMILIAS, SON_RAMAS, SON_LISTA,
        RESULTADOS
    }

    /**
     * Lo que el jugador esta editando ahora mismo, aparte de la ventana.
     *
     * Va fuera de la Vista porque la ventana se destruye y se vuelve a crear en
     * cada pantalla (paginar, entrar en un submenu, elegir un sonido) y el
     * borrador de la linea tiene que sobrevivir a todo eso. Si viviera en la
     * Vista, elegir una particula perderia los otros doce argumentos.
     */
    private static final class Sesion {
        String itemId;
        Activador act;
        String lista = "acciones";
        int indice = -1;              // -1 = linea nueva
        Catalogo.Accion accion;
        Catalogo.Cond cond;
        Linea borrador;
        String paramClave;            // el argumento que se esta eligiendo
        String sonidoPrevio;          // el que suena en el catalogo de sonidos
        List<String> resultados = List.of();
        String consulta = "";
        boolean buscandoAcciones = true;
    }

    /** La ventana abierta y lo justo para volver a pintarla. */
    public static final class Vista implements InventoryHolder {
        final Pantalla pantalla;
        final int pagina;
        final String a;
        final String b;
        final Map<Integer, String> acciones = new HashMap<>();
        Inventory inv;

        Vista(Pantalla p, int pagina, String a, String b) {
            this.pantalla = p;
            this.pagina = pagina;
            this.a = a;
            this.b = b;
        }

        @Override public Inventory getInventory() { return this.inv; }
    }

    private final GodItemsPlugin modulo;
    private final Map<UUID, Sesion> sesiones = new HashMap<>();

    public MenuGi(GodItemsPlugin modulo) {
        this.modulo = modulo;
    }

    private Sesion sesion(Player j) {
        return this.sesiones.computeIfAbsent(j.getUniqueId(), k -> new Sesion());
    }

    /* ================================================================ abrir */

    public void raiz(Player j, int pagina) {
        abrir(j, new Vista(Pantalla.RAIZ, pagina, null, null));
    }

    public void ficha(Player j, String itemId) {
        sesion(j).itemId = itemId;
        abrir(j, new Vista(Pantalla.FICHA, 0, itemId, null));
    }

    public void importador(Player j, int pagina) {
        abrir(j, new Vista(Pantalla.TIPOS, pagina, null, null));
    }

    /** El catalogo de particulas suelto, como consulta. Lo abre `/gi particulas`. */
    public void particulas(Player j) {
        Sesion s = sesion(j);
        s.borrador = null;
        s.paramClave = null;
        abrir(j, new Vista(Pantalla.PART_GRUPOS, 0, null, null));
    }

    private void abrir(Player j, Vista v) {
        v.inv = Bukkit.createInventory(v, TAM, titulo(j, v));
        pintar(j, v);
        j.openInventory(v.inv);
    }

    private Component titulo(Player j, Vista v) {
        Sesion s = sesion(j);
        return switch (v.pantalla) {
            case RAIZ -> Estilo.titulo("GODITEMS", this.modulo.registro().cuantos() + " items");
            case TIPOS -> Estilo.titulo("IMPORTAR", "elige tipo");
            case ITEMS_MMO -> Estilo.titulo("IMPORTAR", v.a);
            case FICHA -> Estilo.titulo("GODITEM", v.a);
            case ASPECTO -> Estilo.titulo("ASPECTO", v.a);
            case ACTIVADOR -> Estilo.titulo(s.act == null ? "ACTIVADOR" : s.act.name(), s.itemId);
            case LISTA -> Estilo.titulo(s.lista.toUpperCase(Locale.ROOT),
                    s.act == null ? "" : s.act.name());
            case LINEA -> Estilo.titulo("LINEA", (s.indice + 1) + " de " + s.lista);
            case ACT_GRUPOS -> Estilo.titulo("ACTIVADORES", "elige grupo");
            case ACT_LISTA -> Estilo.titulo("ACTIVADORES", v.a);
            case CAT_GRUPOS -> Estilo.titulo(s.lista.equals("acciones") ? "ACCIONES" : "CONDICIONES",
                    "elige grupo");
            case CAT_LISTA -> Estilo.titulo(s.lista.equals("acciones") ? "ACCIONES" : "CONDICIONES", v.a);
            case EDITOR -> Estilo.titulo("EDITOR", s.borrador == null ? "" : s.borrador.nombre());
            case ELEGIR -> Estilo.titulo("ELEGIR", v.b);
            case PART_GRUPOS -> Estilo.titulo("PARTICULAS", Particulas.cuantas() + " en total");
            case PART_LISTA -> Estilo.titulo("PARTICULAS", v.a);
            case SON_FAMILIAS -> Estilo.titulo("SONIDOS", Sonidos.cuantos() + " en total");
            case SON_RAMAS -> Estilo.titulo("SONIDOS", v.a);
            case SON_LISTA -> Estilo.titulo("SONIDOS", v.a + "." + v.b);
            case RESULTADOS -> Estilo.titulo("BUSCAR", s.consulta);
        };
    }

    /* =============================================================== pintar */

    private void pintar(Player j, Vista v) {
        v.inv.clear();
        v.acciones.clear();
        marco(v);
        switch (v.pantalla) {
            case RAIZ -> pintarRaiz(v);
            case TIPOS -> pintarTipos(v);
            case ITEMS_MMO -> pintarItemsMmo(v);
            case FICHA -> pintarFicha(v);
            case ASPECTO -> pintarAspecto(v);
            case ACTIVADOR -> pintarActivador(j, v);
            case LISTA -> pintarLista(j, v);
            case LINEA -> pintarLinea(j, v);
            case ACT_GRUPOS -> pintarActGrupos(j, v);
            case ACT_LISTA -> pintarActLista(j, v);
            case CAT_GRUPOS -> pintarCatGrupos(j, v);
            case CAT_LISTA -> pintarCatLista(j, v);
            case EDITOR -> pintarEditor(j, v);
            case ELEGIR -> pintarElegir(j, v);
            case PART_GRUPOS -> pintarPartGrupos(j, v);
            case PART_LISTA -> pintarPartLista(j, v);
            case SON_FAMILIAS -> pintarSonFamilias(v);
            case SON_RAMAS -> pintarSonRamas(v);
            case SON_LISTA -> pintarSonLista(j, v);
            case RESULTADOS -> pintarResultados(j, v);
        }
    }

    /**
     * El marco: las dos filas de cristal.
     *
     * No es adorno. Sin el, un menu de cofre a medio llenar deja huecos negros
     * en los que el jugador intenta meter items, y desde Bedrock ademas no se
     * distingue donde acaba el menu y donde empieza su inventario.
     */
    private void marco(Vista v) {
        ItemStack pane = adorno(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), " ", List.of());
        for (int i = 0; i < 9; i++) v.inv.setItem(i, pane.clone());
        for (int i = 45; i < 54; i++) v.inv.setItem(i, pane.clone());
        for (int i : new int[]{9, 17, 18, 26, 27, 35, 36, 44}) v.inv.setItem(i, pane.clone());
    }

    private void cabecera(Vista v, Material m, String nombre, List<String> lore) {
        v.inv.setItem(R_CABECERA, adorno(new ItemStack(m), nombre, lore));
    }

    /* ---------------------------------------------------------------- raiz */

    private void pintarRaiz(Vista v) {
        List<GodItem> todos = new ArrayList<>(this.modulo.registro().todos());
        paginar(v, todos.size());
        cabecera(v, Material.NETHER_STAR, "&#0083FD&lGODITEMS", List.of(
                "&7" + todos.size() + " items definidos",
                "&8" + Catalogo.cuantasAcciones() + " acciones, "
                        + Catalogo.cuantasCondiciones() + " condiciones",
                "&8" + Activador.values().length + " activadores",
                "&8" + Particulas.cuantas() + " particulas, " + Sonidos.cuantos() + " sonidos"));

        int desde = v.pagina * POR_PAGINA;
        for (int i = 0; i < POR_PAGINA && desde + i < todos.size(); i++) {
            GodItem def = todos.get(desde + i);
            ItemStack icono = iconoDe(def);
            int acciones = 0;
            for (GodItem.Bloque b : def.bloques().values()) acciones += b.pasos().size();
            adorno(icono, "&f" + def.id(), List.of(
                    def.enlazado() ? "&8enlazado a &f" + def.enlace() : "&8nativo",
                    "&7" + def.bloques().size() + " activadores, " + acciones + " acciones",
                    "",
                    AZUL + Estilo.FLECHA + " Abrir la ficha"));
            poner(v, RANURAS[i], icono, "abrir:" + def.id());
        }
        if (todos.isEmpty()) {
            poner(v, 22, adorno(new ItemStack(Material.BARRIER), "&cNo hay ningun GodItem",
                    List.of("&7Importa uno de MMOItems o crea uno nativo",
                            "&7con los botones de abajo.")), null);
        }

        poner(v, R_A, adorno(new ItemStack(Material.HOPPER), VERDE + "Importar de MMOItems",
                List.of("&7Elige un item que ya exista en MMOItems",
                        "&7y dale comportamiento sin tocar sus stats.",
                        "&8Obligatorio si lleva set, tier o stats.")), "importar");
        poner(v, R_B, adorno(new ItemStack(Material.ANVIL), VERDE + "Crear uno nativo",
                List.of("&7Lo fabricamos nosotros: cetros, llaves,",
                        "&7consumibles... cosas sueltas.",
                        "&8Un nativo NO puede ir en un set de MMOItems.")), "crear");
        poner(v, R_C, adorno(new ItemStack(Material.FIREWORK_STAR), AZUL + "Catalogo de particulas",
                List.of("&7Las " + Particulas.cuantas() + " de esta version,",
                        "&7por grupos y con lo que pide cada una.")), "particulas");
        poner(v, R_D, adorno(new ItemStack(Material.CLOCK), AZUL + "Recargar",
                List.of("&7Vuelve a leer los YAML.")), "recargar");
    }

    /* --------------------------------------------------------- importador */

    private void pintarTipos(Vista v) {
        cabecera(v, Material.HOPPER, VERDE + "Importar de MMOItems",
                List.of("&7El item lo sigue fabricando MMOItems.",
                        "&7Aqui solo se le pone el comportamiento."));
        if (!this.modulo.puente().hay()) {
            poner(v, 22, adorno(new ItemStack(Material.BARRIER), "&cMMOItems no esta instalado",
                    List.of("&7Sin el no hay nada que importar.")), null);
            poner(v, R_VOLVER, volver(), "raiz");
            return;
        }
        List<Type> tipos = new ArrayList<>();
        for (Type t : this.modulo.puente().tipos()) {
            if (!this.modulo.puente().itemsDe(t).isEmpty()) tipos.add(t);
        }
        paginar(v, tipos.size());
        int desde = v.pagina * POR_PAGINA;
        for (int i = 0; i < POR_PAGINA && desde + i < tipos.size(); i++) {
            Type t = tipos.get(desde + i);
            ItemStack icono = null;
            try {
                icono = t.getItem();
            } catch (Throwable ignored) {
                // algunos tipos no traen icono; se usa una etiqueta
            }
            if (icono == null || icono.getType().isAir()) icono = new ItemStack(Material.NAME_TAG);
            else icono = icono.clone();
            adorno(icono, "&f" + t.getName(), List.of(
                    "&8" + t.getId(),
                    "&7" + this.modulo.puente().itemsDe(t).size() + " items",
                    "",
                    AZUL + Estilo.FLECHA + " Ver sus items"));
            poner(v, RANURAS[i], icono, "tipo:" + t.getId());
        }
        poner(v, R_VOLVER, volver(), "raiz");
    }

    private void pintarItemsMmo(Vista v) {
        cabecera(v, Material.NAME_TAG, VERDE + "Items de " + v.a, List.of());
        Type t = this.modulo.puente().tipo(v.a);
        List<String> ids = t == null ? List.of() : this.modulo.puente().itemsDe(t);
        paginar(v, ids.size());
        int desde = v.pagina * POR_PAGINA;
        for (int i = 0; i < POR_PAGINA && desde + i < ids.size(); i++) {
            String id = ids.get(desde + i);
            ItemStack icono = this.modulo.puente().construir(v.a, id);
            if (icono == null || icono.getType().isAir()) icono = new ItemStack(Material.PAPER);
            else icono = icono.clone();
            boolean ya = this.modulo.registro().porEnlace(v.a, id) != null;
            /* El nombre NO se toca: es el del item de MMOItems y se enseña tal
             * cual, que es de lo que se trata. Solo se le añade el lore. */
            anadirLore(icono, List.of("&8" + v.a + "." + id, "",
                    ya ? AMARILLO + Estilo.FLECHA + " Ya importado, abre su ficha"
                       : VERDE + Estilo.FLECHA + " Importar a GodItems"));
            poner(v, RANURAS[i], icono, "importar:" + v.a + ":" + id);
        }
        poner(v, R_VOLVER, volver(), "tipos");
    }

    /* --------------------------------------------------------------- ficha */

    private void pintarFicha(Vista v) {
        GodItem def = this.modulo.registro().porId(v.a);
        if (def == null) {
            poner(v, 22, adorno(new ItemStack(Material.BARRIER), "&cEse item ya no existe", List.of()), null);
            poner(v, R_VOLVER, volver(), "raiz");
            return;
        }
        v.inv.setItem(R_CABECERA, iconoDe(def));

        poner(v, 10, adorno(new ItemStack(def.enlazado() ? Material.ENCHANTED_BOOK : Material.ITEM_FRAME),
                CLARO + "Aspecto y stats",
                List.of(def.enlazado()
                            ? "&8los lleva MMOItems; se editan desde aqui igual"
                            : "&8material, nombre, lore, brillo...",
                        "", AZUL + Estilo.FLECHA + " Abrir")), "aspecto");
        poner(v, 11, adorno(new ItemStack(Material.EXPERIENCE_BOTTLE), CLARO + "Usos",
                List.of("&7" + (def.usos() < 0 ? "sin limite" : String.valueOf(def.usos())),
                        "&8" + (def.enlazado() ? "se cuentan en el jugador, no en el item"
                                               : "se cuentan en el propio item"),
                        "", AZUL + Estilo.FLECHA + " Cambiar")), "gi:usos");
        poner(v, 12, adorno(new ItemStack(Material.CLOCK), CLARO + "Usos por dia",
                List.of("&7" + (def.usosPorDia() < 0 ? "sin limite" : String.valueOf(def.usosPorDia())),
                        "", AZUL + Estilo.FLECHA + " Cambiar")), "gi:usos-por-dia");
        poner(v, 13, interruptor("Exclusivo", def.exclusivo(),
                "Calla la habilidad del set en ese mismo gesto."), "gi:exclusivo");
        poner(v, 14, interruptor("Solo su dueño", def.soloDueno(),
                "Solo responde a quien se lo dieron."), "gi:solo-dueno");
        poner(v, 15, interruptor("Conservar al morir", def.conservarAlMorir(),
                "Se le devuelve al reaparecer."), "gi:conservar-al-morir");
        poner(v, 16, adorno(new ItemStack(Material.WRITABLE_BOOK), CLARO + "Lineas de lore propias",
                List.of("&7" + def.loreExtra().size() + " lineas",
                        "&8se añaden al construir el item",
                        "", AZUL + Estilo.FLECHA + " Cambiar")), "gi:lore-extra");

        /* Los activadores, que es lo que GodItems posee de verdad. */
        List<Activador> activadores = new ArrayList<>(def.bloques().keySet());
        int[] huecos = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (int i = 0; i < huecos.length && i < activadores.size(); i++) {
            Activador a = activadores.get(i);
            GodItem.Bloque b = def.bloque(a);
            Catalogo.FichaActivador f = Catalogo.ficha(a);
            poner(v, huecos[i], adorno(new ItemStack(f.icono()), AMARILLO + a.name(),
                    List.of("&8" + f.descripcion(),
                            "&7" + b.pasos().size() + " acciones, "
                                    + b.condiciones().size() + " condiciones",
                            b.cooldown() > 0 ? "&8enfriamiento " + Numeros.reloj(b.cooldown())
                                             : "&8sin enfriamiento",
                            "", AZUL + Estilo.FLECHA + " Abrir")), "act:" + a.name());
        }
        if (activadores.isEmpty()) {
            poner(v, 22, adorno(new ItemStack(Material.GRAY_DYE), "&7Sin activadores",
                    List.of("&7No hara nada hasta que le pongas uno.")), null);
        }

        poner(v, 37, adorno(new ItemStack(Material.LEVER), VERDE + "Añadir activador",
                List.of("&7Se elige de una lista, con lo que hace cada uno.")), "act-nuevo");
        poner(v, 38, adorno(new ItemStack(Material.CHEST), CLARO + "Damelo",
                List.of("&7Te pone una copia en el inventario.")), "dame");
        poner(v, 39, adorno(new ItemStack(Material.BOOK), CLARO + "Duplicar",
                List.of("&7Copia entera con otro id.",
                        "&8el enlace no se copia: dos GodItems",
                        "&8sobre el mismo item de MMOItems chocan")), "duplicar");
        poner(v, 41, adorno(new ItemStack(Material.PAPER), GRIS + "Fichero",
                List.of("&8" + (def.fichero() == null ? "?" : def.fichero().getName()),
                        "&8plugins/EDM/goditems/items/")), null);
        poner(v, 43, adorno(new ItemStack(Material.BARRIER), "&cBorrar este GodItem",
                List.of("&7El YAML no se pierde: se renombra a &f.borrado&7.",
                        "", "&cSe pide confirmacion.")), "gi-borrar");

        poner(v, R_VOLVER, volver(), "raiz");
    }

    private void pintarAspecto(Vista v) {
        GodItem def = this.modulo.registro().porId(v.a);
        if (def == null) { poner(v, R_VOLVER, volver(), "raiz"); return; }
        v.inv.setItem(R_CABECERA, iconoDe(def));

        if (def.enlazado()) {
            cabecera(v, Material.ENCHANTED_BOOK, CLARO + "Aspecto y stats",
                    List.of("&8los datos son de &f" + def.enlace(),
                            "&8se escriben en su YAML de MMOItems:",
                            "&8lo veran igual &f/gi&8 y &f/mi"));
            for (int i = 0; i < Plantillas.CAMPOS.size() && i < POR_PAGINA; i++) {
                Plantillas.Campo c = Plantillas.CAMPOS.get(i);
                String valor = this.modulo.plantillas().leer(def.enlaceTipo(), def.enlaceId(), c);
                poner(v, RANURAS[i], adorno(new ItemStack(materialDe(c)), CLARO + c.etiqueta(),
                        List.of("&8de MMOItems",
                                "&7" + (valor == null || valor.isBlank() ? "sin poner" : recortar(valor, 40)),
                                "", AZUL + Estilo.FLECHA + " Cambiar")),
                        "campo:" + c.id());
            }
        } else {
            cabecera(v, Material.ITEM_FRAME, CLARO + "Aspecto",
                    List.of("&8item nativo: lo fabricamos nosotros"));
            var ap = def.apariencia();
            poner(v, 10, adorno(new ItemStack(ap == null ? Material.STICK : ap.material()),
                    CLARO + "Material", List.of("&7" + (ap == null ? "?" : ap.material().name()),
                            "", AZUL + Estilo.FLECHA + " Cambiar",
                            "&8puedes copiar el de tu mano")), "item:material");
            poner(v, 11, adorno(new ItemStack(Material.NAME_TAG), CLARO + "Nombre",
                    List.of("&7" + (ap == null || ap.nombre() == null ? "sin poner"
                            : recortar(ap.nombre(), 40)),
                            "", AZUL + Estilo.FLECHA + " Cambiar")), "item:nombre");
            poner(v, 12, adorno(new ItemStack(Material.WRITABLE_BOOK), CLARO + "Lore",
                    List.of("&7" + (ap == null ? 0 : ap.lore().size()) + " lineas",
                            "", AZUL + Estilo.FLECHA + " Cambiar")), "item:lore");
            poner(v, 13, interruptor("Brillo", ap != null && ap.brillo(),
                    "Encantado sin encantamientos."), "item:brillo");
            poner(v, 14, interruptor("Irrompible", ap != null && ap.irrompible(),
                    "No pierde durabilidad."), "item:irrompible");
            poner(v, 15, adorno(new ItemStack(Material.PAINTING), CLARO + "Custom model data",
                    List.of("&7" + (ap == null || ap.modelo() == null ? "sin poner"
                            : String.valueOf(ap.modelo())),
                            "&8solo sirve con resource pack",
                            "", AZUL + Estilo.FLECHA + " Cambiar")), "item:modelo");
            poner(v, 16, adorno(new ItemStack(Material.LEATHER_CHESTPLATE), CLARO + "Color del cuero",
                    List.of("&7" + (ap == null || ap.color() == null ? "sin poner" : ap.color()),
                            "", AZUL + Estilo.FLECHA + " Cambiar")), "item:color");
            poner(v, 19, adorno(new ItemStack(Material.PLAYER_HEAD), CLARO + "Textura de cabeza",
                    List.of("&7" + (ap == null || ap.cabeza() == null ? "sin poner"
                            : recortar(ap.cabeza(), 28)),
                            "&8solo si el material es PLAYER_HEAD",
                            "", AZUL + Estilo.FLECHA + " Cambiar")), "item:cabeza");
            poner(v, 20, adorno(new ItemStack(Material.ENCHANTED_BOOK), CLARO + "Encantamientos",
                    List.of("&7" + (ap == null ? 0 : ap.encantos().size()) + " puestos",
                            "&8se escriben &fnombre:nivel&8 separados por coma",
                            "", AZUL + Estilo.FLECHA + " Cambiar")), "item:encantos");
            poner(v, 21, adorno(new ItemStack(Material.PAPER), CLARO + "Cantidad por entrega",
                    List.of("&7" + (ap == null ? 1 : ap.cantidad()),
                            "", AZUL + Estilo.FLECHA + " Cambiar")), "item:cantidad");
        }
        poner(v, R_VOLVER, volver(), "ficha");
    }

    /* ---------------------------------------------------------- activador */

    private void pintarActivador(Player j, Vista v) {
        Sesion s = sesion(j);
        GodItem def = this.modulo.registro().porId(s.itemId);
        if (def == null || s.act == null || def.bloque(s.act) == null) {
            poner(v, R_VOLVER, volver(), "ficha");
            return;
        }
        GodItem.Bloque b = def.bloque(s.act);
        Catalogo.FichaActivador f = Catalogo.ficha(s.act);
        cabecera(v, f.icono(), AMARILLO + s.act.name(), List.of("&8" + f.descripcion()));

        poner(v, 10, adorno(new ItemStack(Material.CLOCK), CLARO + "Enfriamiento",
                List.of("&7" + (b.cooldown() <= 0 ? "ninguno" : Numeros.reloj(b.cooldown())),
                        "", AZUL + Estilo.FLECHA + " Cambiar")), "bloque:cooldown");
        poner(v, 11, interruptor("Cuenta atras visible", b.cuentaAtras(),
                "En la actionbar mientras enfria."), "bloque:cuenta-atras");
        poner(v, 12, adorno(new ItemStack(Material.PAPER), CLARO + "Mensaje de enfriamiento",
                List.of("&7" + (b.mensajeCooldown() == null ? "el general del modulo"
                        : recortar(b.mensajeCooldown(), 34)),
                        "&8%tiempo% se sustituye",
                        "", AZUL + Estilo.FLECHA + " Cambiar")), "bloque:mensaje-cooldown");
        poner(v, 13, adorno(new ItemStack(Material.GOLD_NUGGET), CLARO + "Probabilidad",
                List.of("&7" + b.probabilidad() + " %",
                        "", AZUL + Estilo.FLECHA + " Cambiar")), "bloque:probabilidad");
        poner(v, 14, adorno(new ItemStack(Material.EXPERIENCE_BOTTLE), CLARO + "Usos que gasta",
                List.of("&7" + b.gastaUsos(), "&80 = no gasta ninguno",
                        "", AZUL + Estilo.FLECHA + " Cambiar")), "bloque:gasta-usos");
        if (s.act.esTick()) {
            poner(v, 15, adorno(new ItemStack(Material.REPEATER), CLARO + "Cada",
                    List.of("&7" + Numeros.reloj(b.cada()),
                            "&8redondeado al repaso del modulo",
                            "", AZUL + Estilo.FLECHA + " Cambiar")), "bloque:cada");
        }
        if (s.act.esDeConjunto()) {
            poner(v, 16, adorno(new ItemStack(Material.IRON_CHESTPLATE), CLARO + "Piezas del set",
                    List.of("&7" + (b.piezas() <= 0 ? "las que diga MMOItems" : String.valueOf(b.piezas())),
                            "", AZUL + Estilo.FLECHA + " Cambiar")), "bloque:piezas");
        }

        poner(v, 29, adorno(new ItemStack(Material.PAPER), VERDE + "Acciones (" + b.pasos().size() + ")",
                List.of("&7Lo que hace, en orden.",
                        vistazo(this.modulo.ficha().lineas(def, s.act, "acciones")),
                        "", AZUL + Estilo.FLECHA + " Abrir la lista")), "lista:acciones");
        poner(v, 33, adorno(new ItemStack(Material.COMPARATOR),
                AMARILLO + "Condiciones (" + b.condiciones().size() + ")",
                List.of("&7Se tienen que cumplir TODAS.",
                        vistazo(this.modulo.ficha().lineas(def, s.act, "condiciones")),
                        "", AZUL + Estilo.FLECHA + " Abrir la lista")), "lista:condiciones");

        poner(v, R_A, adorno(new ItemStack(Material.BLAZE_POWDER), VERDE + "Probar ahora",
                List.of("&7Lo dispara sobre ti, saltandose el enfriamiento.",
                        "&8las condiciones SI se comprueban")), "probar-activador");
        poner(v, R_D, adorno(new ItemStack(Material.BARRIER), "&cBorrar este activador",
                List.of("&7Se pide confirmacion.")), "act-borrar");
        poner(v, R_VOLVER, volver(), "ficha");
    }

    /* ------------------------------------------------------ lista de lineas */

    private void pintarLista(Player j, Vista v) {
        Sesion s = sesion(j);
        GodItem def = this.modulo.registro().porId(s.itemId);
        if (def == null || s.act == null) { poner(v, R_VOLVER, volver(), "ficha"); return; }
        boolean acciones = s.lista.equals("acciones");
        List<?> lineas = this.modulo.ficha().lineas(def, s.act, s.lista);
        paginar(v, lineas.size());
        cabecera(v, acciones ? Material.PAPER : Material.COMPARATOR,
                (acciones ? VERDE : AMARILLO) + (acciones ? "Acciones" : "Condiciones"),
                List.of("&8" + s.act.name() + " de " + def.id(),
                        "&7" + lineas.size() + " lineas",
                        acciones ? "&8se ejecutan de arriba abajo"
                                 : "&8se tienen que cumplir todas"));

        int desde = v.pagina * POR_PAGINA;
        for (int i = 0; i < POR_PAGINA && desde + i < lineas.size(); i++) {
            int idx = desde + i;
            Object linea = lineas.get(idx);
            if (!(linea instanceof String texto)) {
                poner(v, RANURAS[i], adorno(new ItemStack(Material.REPEATER),
                        "&f" + (idx + 1) + ". bloque anidado",
                        List.of("&8si / repetir",
                                "&8se edita en el YAML, no aqui:",
                                "&8tocarlo por indice se lo carga entero")), null);
                continue;
            }
            String nombre = Linea.nombreDe(texto);
            Material icono;
            String desc;
            if (acciones) {
                Catalogo.Accion ca = Catalogo.accion(nombre);
                icono = ca == null ? Material.PAPER : ca.icono();
                desc = ca == null ? "&csin ficha en el catalogo" : "&8" + ca.descripcion();
            } else {
                Catalogo.Cond cc = Catalogo.condicion(nombre);
                icono = cc == null ? Material.COMPARATOR : cc.icono();
                desc = cc == null ? "&csin ficha en el catalogo" : "&8" + cc.descripcion();
            }
            poner(v, RANURAS[i], adorno(new ItemStack(icono),
                    "&f" + (idx + 1) + ". " + (acciones ? VERDE : AMARILLO) + nombre,
                    List.of(desc, "&7" + recortar(texto, 46), "",
                            AZUL + Estilo.FLECHA + " Editar, mover o borrar")),
                    "linea:" + idx);
        }
        if (lineas.isEmpty()) {
            poner(v, 22, adorno(new ItemStack(Material.GRAY_DYE), "&7Vacio",
                    List.of("&7Añade la primera con el boton verde.")), null);
        }

        poner(v, R_A, adorno(new ItemStack(Material.LIME_DYE),
                VERDE + "Añadir " + (acciones ? "accion" : "condicion"),
                List.of("&7Se elige de un catalogo por grupos,",
                        "&7no hay que escribir nada de memoria.")), "nueva");
        poner(v, R_VOLVER, volver(), "activador");
    }

    private void pintarLinea(Player j, Vista v) {
        Sesion s = sesion(j);
        GodItem def = this.modulo.registro().porId(s.itemId);
        if (def == null) { poner(v, R_VOLVER, volver(), "ficha"); return; }
        List<?> lineas = this.modulo.ficha().lineas(def, s.act, s.lista);
        String texto = s.indice >= 0 && s.indice < lineas.size()
                ? String.valueOf(lineas.get(s.indice)) : "";
        boolean acciones = s.lista.equals("acciones");
        String nombre = Linea.nombreDe(texto);
        boolean conFicha = acciones ? Catalogo.accion(nombre) != null
                                    : Catalogo.condicion(nombre) != null;

        cabecera(v, acciones ? Material.PAPER : Material.COMPARATOR,
                "&f" + (s.indice + 1) + ". " + nombre,
                List.of("&7" + recortar(texto, 46),
                        "&8" + s.lista + " " + (s.indice + 1) + " de " + lineas.size()));

        poner(v, 11, adorno(new ItemStack(conFicha ? Material.CRAFTING_TABLE : Material.BARRIER),
                (conFicha ? VERDE : GRIS) + "Editor por casillas",
                conFicha ? List.of("&7Un argumento por casilla, con su ayuda.",
                                   "", AZUL + Estilo.FLECHA + " Abrir")
                         : List.of("&cEsta linea no tiene ficha en el catalogo.",
                                   "&7Editala como texto.")),
                conFicha ? "linea-editor" : null);
        poner(v, 12, adorno(new ItemStack(Material.WRITABLE_BOOK), CLARO + "Editar como texto",
                List.of("&7Se escribe la linea entera por el chat.")), "linea-texto");
        poner(v, 13, adorno(new ItemStack(Material.SPECTRAL_ARROW), CLARO + "Subir",
                List.of("&7La mueve una posicion arriba.")), "linea-subir");
        poner(v, 14, adorno(new ItemStack(Material.ARROW), CLARO + "Bajar",
                List.of("&7La mueve una posicion abajo.")), "linea-bajar");
        poner(v, 15, adorno(new ItemStack(Material.BOOK), CLARO + "Duplicar",
                List.of("&7Mete una copia al final de la lista.")), "linea-duplicar");
        if (acciones) {
            poner(v, 16, adorno(new ItemStack(Material.BLAZE_POWDER), VERDE + "Probar esta linea",
                    List.of("&7La ejecuta sobre ti ahora mismo.",
                            "&8sin condiciones ni enfriamiento")), "linea-probar");
        }
        poner(v, 31, adorno(new ItemStack(Material.BARRIER), "&cBorrar la linea",
                List.of("&7La quita de la lista.")), "linea-borrar");
        poner(v, R_VOLVER, volver(), "lista");
    }

    /* -------------------------------------------- catalogo de activadores */

    private void pintarActGrupos(Player j, Vista v) {
        GodItem def = this.modulo.registro().porId(sesion(j).itemId);
        cabecera(v, Material.LEVER, VERDE + "Añadir activador",
                List.of("&7Que hace saltar el comportamiento."));
        int i = 0;
        for (String g : Catalogo.GRUPOS_ACTIVADOR) {
            List<Catalogo.FichaActivador> l = Catalogo.activadoresDe(g);
            if (l.isEmpty()) continue;
            int libres = 0;
            for (Catalogo.FichaActivador f : l) {
                if (def == null || def.bloque(f.activador()) == null) libres++;
            }
            poner(v, RANURAS[i++], adorno(new ItemStack(l.get(0).icono()), AMARILLO + g,
                    List.of("&7" + l.size() + " activadores",
                            libres == 0 ? "&8todos puestos ya" : "&8" + libres + " sin poner",
                            "", AZUL + Estilo.FLECHA + " Ver")), "actgrupo:" + g);
        }
        poner(v, R_VOLVER, volver(), "ficha");
    }

    private void pintarActLista(Player j, Vista v) {
        GodItem def = this.modulo.registro().porId(sesion(j).itemId);
        cabecera(v, Material.LEVER, AMARILLO + v.a, List.of("&7Elige uno."));
        List<Catalogo.FichaActivador> l = Catalogo.activadoresDe(v.a);
        paginar(v, l.size());
        int desde = v.pagina * POR_PAGINA;
        for (int i = 0; i < POR_PAGINA && desde + i < l.size(); i++) {
            Catalogo.FichaActivador f = l.get(desde + i);
            boolean ya = def != null && def.bloque(f.activador()) != null;
            poner(v, RANURAS[i], adorno(new ItemStack(ya ? Material.GRAY_DYE : f.icono()),
                    (ya ? GRIS : AMARILLO) + f.activador().name(),
                    List.of("&8" + f.descripcion(), "",
                            ya ? "&8Ya lo tiene puesto" : VERDE + Estilo.FLECHA + " Añadirlo")),
                    ya ? null : "act-poner:" + f.activador().name());
        }
        poner(v, R_VOLVER, volver(), "act-grupos");
    }

    /* ------------------------------ catalogo de acciones y de condiciones */

    private void pintarCatGrupos(Player j, Vista v) {
        Sesion s = sesion(j);
        boolean acciones = s.lista.equals("acciones");
        cabecera(v, acciones ? Material.PAPER : Material.COMPARATOR,
                (acciones ? VERDE : AMARILLO) + (acciones ? "Catalogo de acciones"
                                                          : "Catalogo de condiciones"),
                List.of("&7" + (acciones ? Catalogo.cuantasAcciones() : Catalogo.cuantasCondiciones())
                        + " en total, por grupos"));
        int i = 0;
        for (Catalogo.Grupo g : acciones ? Catalogo.GRUPOS_ACCION : Catalogo.GRUPOS_CONDICION) {
            int n = acciones ? Catalogo.accionesDe(g.nombre()).size()
                             : Catalogo.condicionesDe(g.nombre()).size();
            poner(v, RANURAS[i++], adorno(new ItemStack(g.icono()), CLARO + g.nombre(),
                    List.of("&8" + g.descripcion(), "&7" + n + " disponibles",
                            "", AZUL + Estilo.FLECHA + " Ver")), "catgrupo:" + g.nombre());
        }
        poner(v, R_A, adorno(new ItemStack(Material.SPYGLASS), AZUL + "Buscar",
                List.of("&7Escribe un trozo del nombre por el chat.")), "cat-buscar");
        poner(v, R_VOLVER, volver(), "lista");
    }

    private void pintarCatLista(Player j, Vista v) {
        Sesion s = sesion(j);
        cabecera(v, Material.BOOK, CLARO + v.a, List.of("&7Elige una."));
        if (s.lista.equals("acciones")) {
            List<Catalogo.Accion> l = Catalogo.accionesDe(v.a);
            paginar(v, l.size());
            pintarAcciones(v, l);
        } else {
            List<Catalogo.Cond> l = Catalogo.condicionesDe(v.a);
            paginar(v, l.size());
            pintarCondiciones(v, l);
        }
        poner(v, R_VOLVER, volver(), "cat-grupos");
    }

    private void pintarResultados(Player j, Vista v) {
        Sesion s = sesion(j);
        cabecera(v, Material.SPYGLASS, AZUL + "Resultados de \"" + s.consulta + "\"",
                List.of("&7" + s.resultados.size() + " encontrados"));
        if (s.buscandoAcciones) {
            List<Catalogo.Accion> l = new ArrayList<>();
            for (String n : s.resultados) {
                Catalogo.Accion a = Catalogo.accion(n);
                if (a != null) l.add(a);
            }
            paginar(v, l.size());
            pintarAcciones(v, l);
        } else {
            List<Catalogo.Cond> l = new ArrayList<>();
            for (String n : s.resultados) {
                Catalogo.Cond c = Catalogo.condicion(n);
                if (c != null) l.add(c);
            }
            paginar(v, l.size());
            pintarCondiciones(v, l);
        }
        poner(v, R_VOLVER, volver(), "cat-grupos");
    }

    private void pintarAcciones(Vista v, List<Catalogo.Accion> l) {
        int desde = v.pagina * POR_PAGINA;
        for (int i = 0; i < POR_PAGINA && desde + i < l.size(); i++) {
            Catalogo.Accion a = l.get(desde + i);
            List<String> lore = new ArrayList<>();
            lore.add("&8" + a.descripcion());
            if (a.llevaSelector()) lore.add("&7objetivo por omision: &f" + a.selectorPorDefecto());
            if (a.llevaTexto()) lore.add("&7lleva texto: &f" + a.etiquetaTexto());
            lore.add("&7" + a.params().size() + " argumentos");
            lore.add("");
            lore.add(VERDE + Estilo.FLECHA + " Añadirla y configurarla");
            poner(v, RANURAS[i], adorno(new ItemStack(a.icono()), VERDE + a.nombre(), lore),
                    "accion:" + a.nombre());
        }
    }

    private void pintarCondiciones(Vista v, List<Catalogo.Cond> l) {
        int desde = v.pagina * POR_PAGINA;
        for (int i = 0; i < POR_PAGINA && desde + i < l.size(); i++) {
            Catalogo.Cond c = l.get(desde + i);
            List<String> lore = new ArrayList<>();
            lore.add("&8" + c.descripcion());
            lore.add("&7forma: &f" + switch (c.forma()) {
                case BANDERA -> "se cumple o no";
                case COMPARACION -> "comparacion";
                case VALOR -> "un valor";
            });
            lore.add("");
            lore.add(AMARILLO + Estilo.FLECHA + " Añadirla y configurarla");
            poner(v, RANURAS[i], adorno(new ItemStack(c.icono()), AMARILLO + c.nombre(), lore),
                    "cond:" + c.nombre());
        }
    }

    /* -------------------------------------------------------------- editor */

    /** Un renglon del editor: una casilla con su valor y su forma de cambiarlo. */
    private record Campo(String id, Material icono, String etiqueta, String valor,
                         String ayuda, String pista, boolean puesto) { }

    private List<Campo> campos(Sesion s) {
        List<Campo> out = new ArrayList<>();
        Linea l = s.borrador;
        if (s.accion != null) {
            Catalogo.Accion a = s.accion;
            if (a.llevaSelector()) {
                out.add(new Campo("selector", Material.ENDER_EYE, "Objetivo",
                        l.selector() == null ? "sin poner" : l.selector(),
                        "A quien apunta la accion.", "gira a la siguiente opcion", true));
            }
            if (a.llevaTexto()) {
                out.add(new Campo("texto", Material.WRITABLE_BOOK, a.etiquetaTexto(),
                        l.texto().isBlank() ? "sin poner" : l.texto(),
                        a.ayudaTexto(), "se escribe por chat", !l.texto().isBlank()));
            }
            for (Catalogo.Param p : a.params()) out.add(campoDe(l, p));
        } else if (s.cond != null) {
            Catalogo.Cond c = s.cond;
            out.add(new Campo("negada", l.negada() ? Material.REDSTONE_TORCH : Material.LEVER,
                    "Invertida", l.negada() ? "si: se cumple cuando NO pasa" : "no",
                    "Un '!' delante de la condicion.", "gira", l.negada()));
            if (c.llevaSelector()) {
                out.add(new Campo("selector", Material.ENDER_EYE, "Sobre quien",
                        l.selector() == null ? "el portador" : l.selector(),
                        "Vacio = el que lleva el item.", "gira", l.selector() != null));
            }
            if (c.forma() == Catalogo.Forma.COMPARACION) {
                if (Catalogo.llevaSujeto(c)) {
                    out.add(new Campo("sujeto", Material.NAME_TAG, "Que se compara",
                            l.sujeto().isBlank() ? "sin poner" : l.sujeto(),
                            c.nombre().equals("VARIABLE") ? "El nombre de la variable."
                                    : "El placeholder, con sus %.",
                            "se escribe por chat", !l.sujeto().isBlank()));
                }
                out.add(new Campo("operador", Material.COMPARATOR, "Operador", l.operador(),
                        "Como se compara.", "gira", true));
            }
            if (c.forma() != Catalogo.Forma.BANDERA) {
                out.add(new Campo("valor", Material.PAPER, c.etiquetaValor(),
                        l.valor().isBlank() ? "sin poner" : l.valor(),
                        c.ayudaValor(), pistaDe(c.claseValor()), !l.valor().isBlank()));
            }
            for (Catalogo.Param p : c.params()) out.add(campoDe(l, p));
            out.add(new Campo("mensaje", Material.OAK_SIGN, "Mensaje si no se cumple",
                    l.mensaje().isBlank() ? "ninguno" : l.mensaje(),
                    "Lo que se le dice al jugador. Acepta codigos &.",
                    "se escribe por chat", !l.mensaje().isBlank()));
        }
        return out;
    }

    private Campo campoDe(Linea l, Catalogo.Param p) {
        String valor = l.valorDe(p);
        return new Campo("param:" + p.clave(), p.icono(), p.etiqueta(),
                valor == null || valor.isBlank() ? "sin poner" : valor,
                p.ayuda(), pistaDe(p.clase()), l.puesto(p));
    }

    private static String pistaDe(Catalogo.Clase c) {
        if (c == null) return "se escribe por chat";
        return switch (c) {
            case BOOL -> "gira";
            case OPCIONES -> "gira entre las opciones";
            case PARTICULA -> "abre el catalogo de particulas";
            case SONIDO -> "abre el catalogo de sonidos";
            case POCION -> "abre la lista de efectos";
            case CRIATURA -> "abre la lista de criaturas";
            case PROYECTIL -> "abre la lista de proyectiles";
            case ACTIVADOR -> "abre la lista de activadores";
            case GODITEM -> "abre la lista de GodItems";
            case MATERIAL, BLOQUE -> "chat, o copia el de tu mano";
            case COLOR -> "abre la paleta";
            case TICKS -> "por chat: 40t, 3s, 2m";
            default -> "se escribe por chat";
        };
    }

    private void pintarEditor(Player j, Vista v) {
        Sesion s = sesion(j);
        if (s.borrador == null) { poner(v, R_VOLVER, volver(), "lista"); return; }
        boolean acciones = s.accion != null;
        String linea = acciones ? s.borrador.escribirAccion(s.accion)
                                : s.borrador.escribirCondicion(s.cond);
        String desc = acciones ? s.accion.descripcion() : s.cond.descripcion();

        List<String> loreCab = new ArrayList<>();
        loreCab.add("&8" + desc);
        loreCab.add("");
        loreCab.add("&7Asi queda la linea:");
        for (String trozo : partir(linea, 46)) loreCab.add("&f" + trozo);
        loreCab.add("");
        loreCab.add(s.indice < 0 ? "&8se añadira al final"
                                 : "&8reemplaza la linea " + (s.indice + 1));
        cabecera(v, acciones ? s.accion.icono() : s.cond.icono(),
                (acciones ? VERDE : AMARILLO) + s.borrador.nombre(), loreCab);

        List<Campo> campos = campos(s);
        paginar(v, campos.size());
        int desde = v.pagina * POR_PAGINA;
        for (int i = 0; i < POR_PAGINA && desde + i < campos.size(); i++) {
            Campo c = campos.get(desde + i);
            List<String> lore = new ArrayList<>();
            if (c.ayuda() != null && !c.ayuda().isBlank()) lore.add("&8" + c.ayuda());
            lore.add((c.puesto() ? "&f" : "&7") + recortar(c.valor(), 40));
            lore.add("");
            lore.add(AZUL + Estilo.FLECHA + " " + mayuscula(c.pista()));
            poner(v, RANURAS[i], adorno(new ItemStack(c.icono()),
                    (c.puesto() ? CLARO : GRIS) + c.etiqueta(), lore), "campo-ed:" + c.id());
        }

        poner(v, R_A, adorno(new ItemStack(Material.LIME_DYE), VERDE + "Guardar",
                List.of("&7Escribe la linea en el YAML y recarga.")), "ed-guardar");
        if (acciones) {
            poner(v, R_B, adorno(new ItemStack(Material.BLAZE_POWDER), AZUL + "Probar",
                    List.of("&7La ejecuta sobre ti sin guardarla.")), "ed-probar");
        }
        poner(v, R_D, adorno(new ItemStack(Material.BARRIER), "&cDescartar",
                List.of("&7Vuelve sin guardar nada.")), "ed-descartar");
        poner(v, R_VOLVER, volver(), "ed-descartar");
    }

    /* ------------------------------------------------------------- elegir */

    /** Una opcion de la pantalla generica de elegir. */
    private record Opcion(String valor, String etiqueta, Material icono, List<String> lore) { }

    private List<Opcion> opciones(Catalogo.Clase clase, List<String> fijas) {
        List<Opcion> out = new ArrayList<>();
        switch (clase) {
            case OPCIONES, PROYECTIL -> {
                for (String o : fijas) out.add(new Opcion(o, o, Material.PAPER, List.of()));
            }
            case POCION -> {
                out.add(new Opcion("TODAS", "TODAS", Material.MILK_BUCKET,
                        List.of("&8quita todos los efectos")));
                try {
                    Registry.POTION_EFFECT_TYPE.keyStream()
                            .filter(k -> NamespacedKey.MINECRAFT.equals(k.getNamespace()))
                            .map(NamespacedKey::getKey)
                            .sorted()
                            .forEach(k -> out.add(new Opcion(k, k, Material.POTION, List.of())));
                } catch (Throwable ignored) {
                    // sin registro no hay lista: queda el boton de escribirlo
                }
            }
            case CRIATURA -> {
                for (EntityType t : EntityType.values()) {
                    if (!t.isSpawnable() || !t.isAlive()) continue;
                    Material huevo = Material.matchMaterial(t.name() + "_SPAWN_EGG");
                    out.add(new Opcion(t.name(), t.name(),
                            huevo == null ? Material.EGG : huevo, List.of()));
                }
                out.sort((a, b) -> a.etiqueta().compareTo(b.etiqueta()));
            }
            case ACTIVADOR -> {
                for (Activador a : Activador.values()) {
                    out.add(new Opcion(a.name(), a.name(), Catalogo.ficha(a).icono(),
                            List.of("&8" + Catalogo.ficha(a).descripcion())));
                }
            }
            case GODITEM -> {
                for (GodItem d : this.modulo.registro().todos()) {
                    out.add(new Opcion(d.id(), d.id(), Material.NETHER_STAR,
                            List.of(d.enlazado() ? "&8enlazado a " + d.enlace() : "&8nativo")));
                }
            }
            case COLOR -> {
                String[][] paleta = {
                    {"#FFFFFF", "Blanco", "WHITE_DYE"}, {"#AAAAAA", "Gris claro", "LIGHT_GRAY_DYE"},
                    {"#555555", "Gris", "GRAY_DYE"}, {"#000000", "Negro", "BLACK_DYE"},
                    {"#FF5555", "Rojo", "RED_DYE"}, {"#FFAA00", "Naranja", "ORANGE_DYE"},
                    {"#FFFF55", "Amarillo", "YELLOW_DYE"}, {"#55FF55", "Verde claro", "LIME_DYE"},
                    {"#00AA00", "Verde", "GREEN_DYE"}, {"#55FFFF", "Cian", "LIGHT_BLUE_DYE"},
                    {"#0083FD", "Azul de Ederus", "BLUE_DYE"}, {"#5555FF", "Azul", "LAPIS_LAZULI"},
                    {"#AA00AA", "Morado", "PURPLE_DYE"}, {"#FF55FF", "Rosa", "MAGENTA_DYE"},
                    {"#B07040", "Marron", "BROWN_DYE"}, {"#00AAAA", "Turquesa", "CYAN_DYE"},
                };
                for (String[] c : paleta) {
                    Material m = Material.matchMaterial(c[2]);
                    out.add(new Opcion(c[0], c[1], m == null ? Material.WHITE_DYE : m,
                            List.of("&8" + c[0])));
                }
            }
            default -> { }
        }
        return out;
    }

    private void pintarElegir(Player j, Vista v) {
        Sesion s = sesion(j);
        Catalogo.Clase clase;
        try {
            clase = Catalogo.Clase.valueOf(v.a);
        } catch (IllegalArgumentException e) {
            poner(v, R_VOLVER, volver(), "editor");
            return;
        }
        List<String> fijas = List.of();
        if (s.paramClave != null) {
            Catalogo.Param p = s.accion != null ? s.accion.param(s.paramClave)
                    : (s.cond != null ? s.cond.param(s.paramClave) : null);
            if (p != null) fijas = p.opciones();
        }
        List<Opcion> l = opciones(clase, fijas);
        paginar(v, l.size());
        cabecera(v, Material.CHEST, CLARO + "Elegir " + v.b,
                List.of("&7" + l.size() + " opciones",
                        "&8o escribelo tu con el boton de abajo"));

        int desde = v.pagina * POR_PAGINA;
        for (int i = 0; i < POR_PAGINA && desde + i < l.size(); i++) {
            Opcion o = l.get(desde + i);
            List<String> lore = new ArrayList<>(o.lore());
            lore.add("");
            lore.add(VERDE + Estilo.FLECHA + " Usar este");
            poner(v, RANURAS[i], adorno(new ItemStack(o.icono()), CLARO + o.etiqueta(), lore),
                    "elegido:" + o.valor());
        }
        poner(v, R_A, adorno(new ItemStack(Material.WRITABLE_BOOK), AZUL + "Escribirlo por chat",
                List.of("&7Por si lo que buscas no esta en la lista.")), "elegir-chat");
        poner(v, R_D, adorno(new ItemStack(Material.GRAY_DYE), GRIS + "Dejarlo vacio",
                List.of("&7Quita el valor y usa el de por defecto.")), "elegir-vacio");
        poner(v, R_VOLVER, volver(), "editor");
    }

    /* --------------------------------------------------------- particulas */

    private void pintarPartGrupos(Player j, Vista v) {
        cabecera(v, Material.FIREWORK_STAR, AZUL + "Particulas",
                List.of("&7" + Particulas.cuantas() + " en esta version del servidor",
                        "&8la lista sale del propio servidor,",
                        "&8asi que nunca se queda vieja"));
        int i = 0;
        for (String g : Particulas.gruposConAlgo()) {
            List<Particulas.Info> l = Particulas.grupo(g);
            poner(v, RANURAS[i++], adorno(new ItemStack(l.get(0).icono()), CLARO + g,
                    List.of("&7" + l.size() + " particulas",
                            "", AZUL + Estilo.FLECHA + " Ver")), "partgrupo:" + g);
        }
        poner(v, R_VOLVER, volver(), sesion(j).borrador == null ? "raiz" : "editor");
    }

    private void pintarPartLista(Player j, Vista v) {
        List<Particulas.Info> l = Particulas.grupo(v.a);
        paginar(v, l.size());
        boolean eligiendo = sesion(j).borrador != null;
        cabecera(v, Material.FIREWORK_STAR, CLARO + v.a,
                List.of("&7" + l.size() + " particulas",
                        eligiendo ? "&8elige una" : "&8un clic la pinta delante de ti"));
        int desde = v.pagina * POR_PAGINA;
        for (int i = 0; i < POR_PAGINA && desde + i < l.size(); i++) {
            Particulas.Info p = l.get(desde + i);
            poner(v, RANURAS[i], adorno(new ItemStack(p.icono()), CLARO + p.nombre(),
                    List.of("&8" + p.clave(),
                            p.pideDato().isEmpty() ? "&7no pide datos" : "&7pide: &f" + p.pideDato(),
                            "",
                            eligiendo ? VERDE + Estilo.FLECHA + " Usar esta"
                                      : AZUL + Estilo.FLECHA + " Verla aqui mismo")),
                    "particula:" + p.yaml());
        }
        poner(v, R_VOLVER, volver(), "part-grupos");
    }

    /* ------------------------------------------------------------ sonidos */

    private void pintarSonFamilias(Vista v) {
        cabecera(v, Material.JUKEBOX, AZUL + "Sonidos",
                List.of("&7" + Sonidos.cuantos() + " en el servidor",
                        "&8un clic los suena; el boton verde los guarda"));
        List<String> fam = Sonidos.familias();
        paginar(v, fam.size());
        int desde = v.pagina * POR_PAGINA;
        for (int i = 0; i < POR_PAGINA && desde + i < fam.size(); i++) {
            String f = fam.get(desde + i);
            poner(v, RANURAS[i], adorno(new ItemStack(Sonidos.icono(f)), CLARO + f,
                    List.of("&7" + Sonidos.cuantosEn(f) + " sonidos",
                            "", AZUL + Estilo.FLECHA + " Ver")), "sonfam:" + f);
        }
        poner(v, R_A, adorno(new ItemStack(Material.SPYGLASS), AZUL + "Buscar",
                List.of("&7Escribe un trozo de la clave por el chat.")), "son-buscar");
        poner(v, R_VOLVER, volver(), "editor");
    }

    private void pintarSonRamas(Vista v) {
        List<String> ramas = Sonidos.ramas(v.a);
        paginar(v, ramas.size());
        cabecera(v, Sonidos.icono(v.a), CLARO + v.a, List.of("&7" + ramas.size() + " ramas"));
        int desde = v.pagina * POR_PAGINA;
        for (int i = 0; i < POR_PAGINA && desde + i < ramas.size(); i++) {
            String r = ramas.get(desde + i);
            poner(v, RANURAS[i], adorno(new ItemStack(Material.NOTE_BLOCK), CLARO + r,
                    List.of("&7" + Sonidos.sonidos(v.a, r).size() + " sonidos",
                            "", AZUL + Estilo.FLECHA + " Ver")), "sonrama:" + r);
        }
        poner(v, R_VOLVER, volver(), "son-familias");
    }

    private void pintarSonLista(Player j, Vista v) {
        Sesion s = sesion(j);
        List<String> l = Sonidos.sonidos(v.a, v.b);
        paginar(v, l.size());
        cabecera(v, Material.JUKEBOX, CLARO + v.a + "." + v.b,
                List.of("&7" + l.size() + " sonidos",
                        "&7Sonando: &f" + (s.sonidoPrevio == null ? "ninguno" : s.sonidoPrevio),
                        "&8un clic lo suena, no lo guarda"));
        int desde = v.pagina * POR_PAGINA;
        for (int i = 0; i < POR_PAGINA && desde + i < l.size(); i++) {
            String clave = l.get(desde + i);
            boolean elegido = clave.equals(s.sonidoPrevio);
            String corto = clave.substring(clave.lastIndexOf('.') + 1);
            poner(v, RANURAS[i], adorno(new ItemStack(elegido ? Material.MUSIC_DISC_PIGSTEP
                            : Material.NOTE_BLOCK),
                    (elegido ? VERDE : CLARO) + corto,
                    List.of("&8" + clave, "", AZUL + Estilo.FLECHA + " Escucharlo")),
                    "sonido:" + clave);
        }
        if (s.sonidoPrevio != null && s.borrador != null) {
            poner(v, R_A, adorno(new ItemStack(Material.LIME_DYE), VERDE + "Usar este sonido",
                    List.of("&f" + s.sonidoPrevio)), "son-confirmar");
        }
        poner(v, R_VOLVER, volver(), "son-ramas");
    }

    /* ================================================================ clics */

    @EventHandler
    public void alArrastrar(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Vista) e.setCancelled(true);
    }

    @EventHandler
    public void alPulsar(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Vista v)) return;
        /* Se cancela SIEMPRE, incluidos los clics en el inventario de abajo:
         * un shift-clic desde ahi mete items en el menu. */
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player j)) return;
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(v.inv)) return;

        int slot = e.getRawSlot();
        if (slot == R_ANTERIOR && v.acciones.containsKey(R_ANTERIOR)) {
            abrir(j, new Vista(v.pantalla, Math.max(0, v.pagina - 1), v.a, v.b));
            return;
        }
        if (slot == R_SIGUIENTE && v.acciones.containsKey(R_SIGUIENTE)) {
            abrir(j, new Vista(v.pantalla, v.pagina + 1, v.a, v.b));
            return;
        }
        String accion = v.acciones.get(slot);
        if (accion == null || accion.equals("pagina")) return;
        try {
            atender(j, v, accion);
        } catch (Throwable t) {
            j.sendMessage(Estilo.legado("&cAlgo fallo en el menu: " + t));
            this.modulo.getLogger().warning("[GodItems] menu: " + accion + " -> " + t);
            if (this.modulo.detalle()) t.printStackTrace();
        }
    }

    private void atender(Player j, Vista v, String accion) {
        Sesion s = sesion(j);

        /* --- navegacion suelta --- */
        switch (accion) {
            case "raiz" -> { raiz(j, 0); return; }
            case "tipos", "importar" -> { importador(j, 0); return; }
            case "particulas" -> { particulas(j); return; }
            case "recargar" -> {
                j.sendMessage(Estilo.legado("&aGodItems: &f" + this.modulo.recargar()));
                raiz(j, v.pagina);
                return;
            }
            case "ficha" -> { ficha(j, s.itemId); return; }
            case "aspecto" -> { abrir(j, new Vista(Pantalla.ASPECTO, 0, s.itemId, null)); return; }
            case "activador" -> { abrir(j, new Vista(Pantalla.ACTIVADOR, 0, null, null)); return; }
            case "lista" -> { abrir(j, new Vista(Pantalla.LISTA, 0, null, null)); return; }
            case "act-grupos" -> { abrir(j, new Vista(Pantalla.ACT_GRUPOS, 0, null, null)); return; }
            case "cat-grupos" -> { abrir(j, new Vista(Pantalla.CAT_GRUPOS, 0, null, null)); return; }
            case "editor" -> { abrir(j, new Vista(Pantalla.EDITOR, 0, null, null)); return; }
            case "part-grupos" -> { abrir(j, new Vista(Pantalla.PART_GRUPOS, 0, null, null)); return; }
            case "son-familias" -> { abrir(j, new Vista(Pantalla.SON_FAMILIAS, 0, null, null)); return; }
            case "son-ramas" -> { abrir(j, new Vista(Pantalla.SON_RAMAS, 0, v.a, null)); return; }
            case "ed-descartar" -> {
                s.borrador = null;
                s.accion = null;
                s.cond = null;
                abrir(j, new Vista(Pantalla.LISTA, 0, null, null));
                return;
            }
            default -> { }
        }

        if (accion.startsWith("abrir:")) { ficha(j, accion.substring(6)); return; }
        if (accion.startsWith("tipo:")) {
            abrir(j, new Vista(Pantalla.ITEMS_MMO, 0, accion.substring(5), null));
            return;
        }
        if (accion.startsWith("importar:")) { importar(j, accion); return; }
        if (accion.equals("crear")) { crearNativo(j); return; }

        /* Las pantallas de consulta (particulas y sonidos) valen sin item. */
        if (accion.startsWith("partgrupo:")) {
            abrir(j, new Vista(Pantalla.PART_LISTA, 0, accion.substring(10), null));
            return;
        }
        if (accion.startsWith("particula:")) {
            String p = accion.substring(10);
            if (s.borrador == null) previsualizarParticula(j, p);
            else aplicarElegido(j, p);
            return;
        }
        if (accion.startsWith("sonfam:")) {
            abrir(j, new Vista(Pantalla.SON_RAMAS, 0, accion.substring(7), null));
            return;
        }
        if (accion.startsWith("sonrama:")) {
            abrir(j, new Vista(Pantalla.SON_LISTA, 0, v.a, accion.substring(8)));
            return;
        }
        if (accion.startsWith("sonido:")) {
            s.sonidoPrevio = accion.substring(7);
            try {
                j.playSound(j.getLocation(), s.sonidoPrevio, 1.0f, 1.0f);
            } catch (Throwable ignored) {
                // una clave que el cliente no conoce simplemente no suena
            }
            abrir(j, new Vista(Pantalla.SON_LISTA, v.pagina, v.a, v.b));
            return;
        }
        if (accion.equals("son-buscar")) { buscarSonido(j); return; }
        if (accion.equals("son-confirmar")) {
            if (s.sonidoPrevio != null) aplicarElegido(j, s.sonidoPrevio);
            return;
        }

        /* --- todo lo demas necesita un item abierto --- */
        GodItem def = s.itemId == null ? null : this.modulo.registro().porId(s.itemId);
        if (def == null) { raiz(j, 0); return; }

        if (accion.startsWith("campo:")) { editarCampoMmo(j, def, accion.substring(6)); return; }
        if (accion.startsWith("gi:")) { editarPropio(j, def, accion.substring(3)); return; }
        if (accion.startsWith("item:")) { editarAspecto(j, def, accion.substring(5)); return; }
        if (accion.startsWith("act:")) {
            s.act = Activador.porNombre(accion.substring(4));
            abrir(j, new Vista(Pantalla.ACTIVADOR, 0, null, null));
            return;
        }
        if (accion.startsWith("lista:")) {
            s.lista = accion.substring(6);
            abrir(j, new Vista(Pantalla.LISTA, 0, null, null));
            return;
        }
        if (accion.startsWith("linea:")) {
            s.indice = Integer.parseInt(accion.substring(6));
            abrir(j, new Vista(Pantalla.LINEA, 0, null, null));
            return;
        }
        if (accion.startsWith("bloque:")) { editarBloque(j, def, accion.substring(7)); return; }
        if (accion.startsWith("actgrupo:")) {
            abrir(j, new Vista(Pantalla.ACT_LISTA, 0, accion.substring(9), null));
            return;
        }
        if (accion.startsWith("act-poner:")) {
            Activador a = Activador.porNombre(accion.substring(10));
            if (a != null && this.modulo.ficha().anadirActivador(def, a)) {
                s.act = a;
                j.sendMessage(Estilo.legado("&aActivador &f" + a.name() + " &aañadido."));
                abrir(j, new Vista(Pantalla.ACTIVADOR, 0, null, null));
            } else {
                ficha(j, def.id());
            }
            return;
        }
        if (accion.startsWith("catgrupo:")) {
            abrir(j, new Vista(Pantalla.CAT_LISTA, 0, accion.substring(9), null));
            return;
        }
        if (accion.startsWith("accion:")) {
            Catalogo.Accion a = Catalogo.accion(accion.substring(7));
            if (a == null) return;
            s.accion = a;
            s.cond = null;
            s.borrador = Linea.nuevaAccion(a);
            s.indice = -1;
            abrir(j, new Vista(Pantalla.EDITOR, 0, null, null));
            return;
        }
        if (accion.startsWith("cond:")) {
            Catalogo.Cond c = Catalogo.condicion(accion.substring(5));
            if (c == null) return;
            s.cond = c;
            s.accion = null;
            s.borrador = Linea.nuevaCondicion(c);
            s.indice = -1;
            abrir(j, new Vista(Pantalla.EDITOR, 0, null, null));
            return;
        }
        if (accion.startsWith("campo-ed:")) { tocarCampo(j, v, accion.substring(9)); return; }
        if (accion.startsWith("elegido:")) { aplicarElegido(j, accion.substring(8)); return; }

        switch (accion) {
            case "act-nuevo" -> abrir(j, new Vista(Pantalla.ACT_GRUPOS, 0, null, null));
            case "act-borrar" -> confirmar(j,
                    "borrar el activador " + (s.act == null ? "?" : s.act.name()),
                    () -> {
                        this.modulo.ficha().borrarActivador(def, s.act);
                        j.sendMessage(Estilo.legado("&7Activador borrado."));
                        ficha(j, def.id());
                    },
                    () -> abrir(j, new Vista(Pantalla.ACTIVADOR, 0, null, null)));
            case "probar-activador" -> probarActivador(j, def);
            case "dame" -> {
                ItemStack it = this.modulo.fabricar(def, 1);
                if (it == null) {
                    j.sendMessage(Estilo.legado("&cNo se pudo fabricar ese item."));
                } else {
                    this.modulo.ponerDueno(it, j);
                    for (ItemStack sobra : j.getInventory().addItem(it).values()) {
                        j.getWorld().dropItemNaturally(j.getLocation(), sobra);
                    }
                    j.sendMessage(Estilo.legado("&aAhi lo tienes."));
                }
                ficha(j, def.id());
            }
            case "duplicar" -> duplicar(j, def);
            case "gi-borrar" -> confirmar(j, "borrar el GodItem " + def.id(),
                    () -> {
                        String nombre = def.fichero() == null ? "?" : def.fichero().getName();
                        if (this.modulo.ficha().borrar(def)) {
                            j.sendMessage(Estilo.legado("&7Borrado. El YAML quedo como &f"
                                    + nombre + ".borrado&7."));
                        } else {
                            j.sendMessage(Estilo.legado("&cNo se pudo borrar."));
                        }
                        raiz(j, 0);
                    },
                    () -> ficha(j, def.id()));
            case "nueva" -> {
                s.indice = -1;
                abrir(j, new Vista(Pantalla.CAT_GRUPOS, 0, null, null));
            }
            case "cat-buscar" -> buscarCatalogo(j);
            case "linea-editor" -> abrirEditorDeLinea(j, def);
            case "linea-texto" -> editarLineaTexto(j, def);
            case "linea-subir" -> {
                this.modulo.ficha().lineaMover(def, s.act, s.lista, s.indice, -1);
                s.indice = Math.max(0, s.indice - 1);
                abrir(j, new Vista(Pantalla.LINEA, 0, null, null));
            }
            case "linea-bajar" -> {
                int tope = this.modulo.ficha().lineas(def, s.act, s.lista).size() - 1;
                this.modulo.ficha().lineaMover(def, s.act, s.lista, s.indice, 1);
                s.indice = Math.min(tope, s.indice + 1);
                abrir(j, new Vista(Pantalla.LINEA, 0, null, null));
            }
            case "linea-duplicar" -> {
                List<?> lineas = this.modulo.ficha().lineas(def, s.act, s.lista);
                if (s.indice >= 0 && s.indice < lineas.size()
                        && lineas.get(s.indice) instanceof String texto) {
                    this.modulo.ficha().lineaPonerOAnadir(def, s.act, s.lista, -1, texto);
                    j.sendMessage(Estilo.legado("&aCopia añadida al final."));
                }
                abrir(j, new Vista(Pantalla.LISTA, 0, null, null));
            }
            case "linea-borrar" -> confirmar(j, "borrar esa linea",
                    () -> {
                        this.modulo.ficha().lineaBorrar(def, s.act, s.lista, s.indice);
                        abrir(j, new Vista(Pantalla.LISTA, 0, null, null));
                    },
                    () -> abrir(j, new Vista(Pantalla.LINEA, 0, null, null)));
            case "linea-probar" -> {
                List<?> lineas = this.modulo.ficha().lineas(def, s.act, s.lista);
                if (s.indice >= 0 && s.indice < lineas.size()
                        && lineas.get(s.indice) instanceof String texto) {
                    probarLinea(j, def, texto);
                }
                abrir(j, new Vista(Pantalla.LINEA, 0, null, null));
            }
            case "ed-guardar" -> guardarBorrador(j, def);
            case "ed-probar" -> {
                if (s.accion != null && s.borrador != null) {
                    probarLinea(j, def, s.borrador.escribirAccion(s.accion));
                }
                abrir(j, new Vista(Pantalla.EDITOR, v.pagina, null, null));
            }
            case "elegir-chat" -> pedirValorSuelto(j);
            case "elegir-vacio" -> aplicarElegido(j, "");
            default -> { }
        }
    }

    /* ============================================================ acciones */

    private void importar(Player j, String accion) {
        String[] p = accion.split(":", 3);
        String ya = this.modulo.registro().porEnlace(p[1], p[2]);
        if (ya != null) { ficha(j, ya); return; }
        GodItem nuevo = this.modulo.ficha().importar(p[1], p[2]);
        if (nuevo == null) {
            j.sendMessage(Estilo.legado("&cNo se pudo importar " + p[1] + "." + p[2] + "."));
            return;
        }
        j.sendMessage(Estilo.legado("&aImportado &f" + p[1] + "." + p[2]
                + " &acomo &f" + nuevo.id() + "&a."));
        ficha(j, nuevo.id());
    }

    private void crearNativo(Player j) {
        ItemStack mano = j.getInventory().getItemInMainHand();
        Material m = mano == null || mano.getType().isAir() ? Material.STICK : mano.getType();
        preguntar(j, VERDE + "Id del GodItem nuevo", null,
                "&8Sin espacios. Ej: &fCETRO_DEL_ALBA\n"
                        + "&8Se creara con el material &f" + m.name() + "&8 (el de tu mano).",
                texto -> {
                    GodItem nuevo = this.modulo.ficha().crearNativo(texto, m);
                    if (nuevo == null) {
                        j.sendMessage(Estilo.legado("&cEse id ya existe o no vale."));
                        raiz(j, 0);
                        return;
                    }
                    j.sendMessage(Estilo.legado("&aCreado &f" + nuevo.id() + "&a."));
                    ficha(j, nuevo.id());
                },
                () -> raiz(j, 0));
    }

    private void duplicar(Player j, GodItem def) {
        preguntar(j, CLARO + "Id de la copia", null, "&8Sin espacios.",
                texto -> {
                    GodItem nuevo = this.modulo.ficha().duplicar(def, texto);
                    if (nuevo == null) {
                        j.sendMessage(Estilo.legado("&cEse id ya existe o no vale."));
                        ficha(j, def.id());
                        return;
                    }
                    j.sendMessage(Estilo.legado("&aCopiado como &f" + nuevo.id() + "&a."));
                    ficha(j, nuevo.id());
                },
                () -> ficha(j, def.id()));
    }

    private void editarCampoMmo(Player j, GodItem def, String campoId) {
        Plantillas.Campo campo = Plantillas.campo(campoId);
        if (campo == null || !def.enlazado()) return;
        String actual = this.modulo.plantillas().leer(def.enlaceTipo(), def.enlaceId(), campo);
        Runnable volver = () -> abrir(j, new Vista(Pantalla.ASPECTO, 0, def.id(), null));
        preguntar(j, CLARO + campo.etiqueta() + " &8de " + def.enlace(), actual,
                switch (campo.clase()) {
                    case LISTA -> "&8Separa las lineas con &f|&8. Acepta codigos &&.";
                    case NUMERO -> "&8Escribe un numero.";
                    default -> "&8Acepta codigos &&.";
                },
                texto -> {
                    String fallo = this.modulo.plantillas().escribir(
                            def.enlaceTipo(), def.enlaceId(), campo, texto);
                    j.sendMessage(Estilo.legado(fallo != null ? "&c" + fallo
                            : "&a" + campo.etiqueta() + " de &f" + def.enlace()
                              + " &acambiado. Lo veran igual &f/gi&a y &f/mi&a."));
                    volver.run();
                }, volver);
    }

    private void editarPropio(Player j, GodItem def, String clave) {
        switch (clave) {
            case "exclusivo" -> { girar(j, def, "exclusivo", def.exclusivo()); return; }
            case "solo-dueno" -> { girar(j, def, "solo-dueno", def.soloDueno()); return; }
            case "conservar-al-morir" -> {
                girar(j, def, "conservar-al-morir", def.conservarAlMorir());
                return;
            }
            default -> { }
        }
        String actual = switch (clave) {
            case "usos" -> String.valueOf(def.usos());
            case "usos-por-dia" -> String.valueOf(def.usosPorDia());
            case "lore-extra" -> String.join(" | ", def.loreExtra());
            default -> "";
        };
        preguntar(j, CLARO + clave, actual,
                clave.equals("lore-extra")
                        ? "&8Separa las lineas con &f|&8. Vacio: escribe &fninguna&8."
                        : "&8Un numero. &f-1 &8es sin limite.",
                texto -> {
                    if (clave.equals("lore-extra")) {
                        List<String> lineas = new ArrayList<>();
                        if (!texto.equalsIgnoreCase("ninguna")) {
                            for (String t : texto.split("\\|")) lineas.add(t.trim());
                        }
                        this.modulo.ficha().poner(def, "lore-extra", lineas);
                    } else {
                        this.modulo.ficha().poner(def, clave, (int) Numeros.decimal(texto, -1));
                    }
                    ficha(j, def.id());
                },
                () -> ficha(j, def.id()));
    }

    private void editarAspecto(Player j, GodItem def, String clave) {
        if (def.enlazado()) return;
        var ap = def.apariencia();
        Runnable volver = () -> abrir(j, new Vista(Pantalla.ASPECTO, 0, def.id(), null));

        switch (clave) {
            case "brillo" -> {
                this.modulo.ficha().poner(def, "item.brillo", ap == null || !ap.brillo());
                volver.run();
                return;
            }
            case "irrompible" -> {
                this.modulo.ficha().poner(def, "item.irrompible", ap == null || !ap.irrompible());
                volver.run();
                return;
            }
            case "material" -> {
                ItemStack mano = j.getInventory().getItemInMainHand();
                String enMano = mano == null || mano.getType().isAir() ? null : mano.getType().name();
                preguntar(j, CLARO + "Material", ap == null ? null : ap.material().name(),
                        enMano == null ? "&8Ej: &fBLAZE_ROD"
                                : "&8Escribe &fmano&8 para copiar el tuyo (&f" + enMano + "&8).",
                        texto -> {
                            String valor = texto.equalsIgnoreCase("mano") && enMano != null
                                    ? enMano : texto.trim().toUpperCase(Locale.ROOT);
                            if (Material.matchMaterial(valor) == null) {
                                j.sendMessage(Estilo.legado("&cNo existe el material &f" + valor + "&c."));
                            } else {
                                this.modulo.ficha().poner(def, "item.material", valor);
                            }
                            volver.run();
                        }, volver);
                return;
            }
            default -> { }
        }

        String actual = switch (clave) {
            case "nombre" -> ap == null ? "" : ap.nombre();
            case "lore" -> ap == null ? "" : String.join(" | ", ap.lore());
            case "modelo" -> ap == null || ap.modelo() == null ? "" : String.valueOf(ap.modelo());
            case "color" -> ap == null ? "" : ap.color();
            case "cabeza" -> ap == null ? "" : ap.cabeza();
            case "cantidad" -> ap == null ? "1" : String.valueOf(ap.cantidad());
            case "encantos" -> {
                if (ap == null) yield "";
                List<String> l = new ArrayList<>();
                for (Map.Entry<String, Integer> en : ap.encantos().entrySet()) {
                    l.add(en.getKey() + ":" + en.getValue());
                }
                yield String.join(", ", l);
            }
            default -> "";
        };
        String pista = switch (clave) {
            case "lore" -> "&8Separa las lineas con &f|&8. &fninguna&8 lo vacia.";
            case "modelo", "cantidad" -> "&8Un numero. &fninguna&8 lo quita.";
            case "color" -> "&8Formato &f#RRGGBB&8. &fninguna&8 lo quita.";
            case "cabeza" -> "&8La textura base64 o el nombre. &fninguna&8 lo quita.";
            case "encantos" -> "&8Pares &fnombre:nivel&8 separados por coma. &fninguna&8 los quita.";
            default -> "&8Acepta codigos &&. &fninguna&8 lo quita.";
        };
        preguntar(j, CLARO + clave, actual, pista,
                texto -> {
                    boolean vacio = texto.equalsIgnoreCase("ninguna");
                    switch (clave) {
                        case "lore" -> {
                            List<String> l = new ArrayList<>();
                            if (!vacio) {
                                for (String t : texto.split("\\|")) l.add(t.trim());
                            }
                            this.modulo.ficha().poner(def, "item.lore", l);
                        }
                        case "modelo" -> this.modulo.ficha().poner(def, "item.modelo",
                                vacio ? null : (int) Numeros.decimal(texto, 0));
                        case "cantidad" -> this.modulo.ficha().poner(def, "item.cantidad",
                                vacio ? 1 : Math.max(1, (int) Numeros.decimal(texto, 1)));
                        case "encantos" -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            if (!vacio) {
                                for (String t : texto.split(",")) {
                                    String[] p = t.trim().split(":");
                                    if (p.length < 1 || p[0].isBlank()) continue;
                                    m.put(p[0].trim().toLowerCase(Locale.ROOT),
                                            p.length > 1 ? (int) Numeros.decimal(p[1], 1) : 1);
                                }
                            }
                            this.modulo.ficha().poner(def, "item.encantos", m.isEmpty() ? null : m);
                        }
                        default -> this.modulo.ficha().poner(def, "item." + clave,
                                vacio ? null : texto);
                    }
                    volver.run();
                }, volver);
    }

    private void girar(Player j, GodItem def, String clave, boolean actual) {
        this.modulo.ficha().poner(def, clave, !actual);
        ficha(j, def.id());
    }

    private void editarBloque(Player j, GodItem def, String clave) {
        Sesion s = sesion(j);
        GodItem.Bloque b = def.bloque(s.act);
        if (b == null) return;
        Runnable volver = () -> abrir(j, new Vista(Pantalla.ACTIVADOR, 0, null, null));
        String ruta = "activadores." + s.act.name() + "." + clave;

        if (clave.equals("cuenta-atras")) {
            this.modulo.ficha().poner(def, ruta, !b.cuentaAtras());
            volver.run();
            return;
        }
        String actual = switch (clave) {
            case "cooldown" -> b.cooldown() + "t";
            case "piezas" -> String.valueOf(b.piezas());
            case "cada" -> b.cada() + "t";
            case "probabilidad" -> String.valueOf(b.probabilidad());
            case "gasta-usos" -> String.valueOf(b.gastaUsos());
            case "mensaje-cooldown" -> b.mensajeCooldown();
            default -> "";
        };
        String pista = switch (clave) {
            case "piezas" -> "&8Un numero. &f0 &8= las que diga MMOItems.";
            case "probabilidad" -> "&8De 0 a 100.";
            case "gasta-usos" -> "&8Un numero. &f0 &8= no gasta.";
            case "mensaje-cooldown" -> "&8Acepta &&. &f%tiempo%&8 se sustituye. "
                    + "&fninguna&8 usa el general.";
            default -> "&8Tiempo: &f30s&8, &f2m&8, &f40t&8.";
        };
        preguntar(j, CLARO + clave + " &8de " + s.act.name(), actual, pista,
                texto -> {
                    Object valor = switch (clave) {
                        case "piezas", "gasta-usos" -> (int) Numeros.decimal(texto, 0);
                        case "probabilidad" -> Numeros.decimal(texto, 100);
                        case "mensaje-cooldown" -> texto.equalsIgnoreCase("ninguna") ? null : texto;
                        default -> texto;
                    };
                    this.modulo.ficha().poner(def, ruta, valor);
                    volver.run();
                }, volver);
    }

    /* ------------------------------------------------------ editor de linea */

    private void abrirEditorDeLinea(Player j, GodItem def) {
        Sesion s = sesion(j);
        List<?> lineas = this.modulo.ficha().lineas(def, s.act, s.lista);
        if (s.indice < 0 || s.indice >= lineas.size()
                || !(lineas.get(s.indice) instanceof String texto)) return;
        String nombre = Linea.nombreDe(texto);
        if (s.lista.equals("acciones")) {
            Catalogo.Accion a = Catalogo.accion(nombre);
            if (a == null) return;
            s.accion = a;
            s.cond = null;
            s.borrador = Linea.deAccion(a, texto);
        } else {
            Catalogo.Cond c = Catalogo.condicion(nombre);
            if (c == null) return;
            s.cond = c;
            s.accion = null;
            s.borrador = Linea.deCondicion(c, texto);
        }
        abrir(j, new Vista(Pantalla.EDITOR, 0, null, null));
    }

    private void editarLineaTexto(Player j, GodItem def) {
        Sesion s = sesion(j);
        List<?> lineas = this.modulo.ficha().lineas(def, s.act, s.lista);
        String actual = s.indice >= 0 && s.indice < lineas.size()
                ? String.valueOf(lineas.get(s.indice)) : "";
        preguntar(j, AZUL + "Editar la linea entera", actual, "&8Escribela tal cual va al YAML.",
                texto -> {
                    this.modulo.ficha().lineaPonerOAnadir(def, s.act, s.lista, s.indice, texto);
                    abrir(j, new Vista(Pantalla.LISTA, 0, null, null));
                },
                () -> abrir(j, new Vista(Pantalla.LINEA, 0, null, null)));
    }

    /** Un clic en una casilla del editor: gira, abre una lista o pide por chat. */
    private void tocarCampo(Player j, Vista v, String id) {
        Sesion s = sesion(j);
        if (s.borrador == null) return;
        Linea l = s.borrador;

        switch (id) {
            case "selector" -> {
                l.girarSelector();
                abrir(j, new Vista(Pantalla.EDITOR, v.pagina, null, null));
                return;
            }
            case "negada" -> {
                l.girarNegada();
                abrir(j, new Vista(Pantalla.EDITOR, v.pagina, null, null));
                return;
            }
            case "operador" -> {
                l.girarOperador();
                abrir(j, new Vista(Pantalla.EDITOR, v.pagina, null, null));
                return;
            }
            case "texto" -> {
                pedirTexto(j, v, s.accion.etiquetaTexto(), s.accion.ayudaTexto(), l.texto(), l::texto);
                return;
            }
            case "sujeto" -> {
                pedirTexto(j, v, "Que se compara", s.cond.ayudaValor(), l.sujeto(), l::sujeto);
                return;
            }
            case "mensaje" -> {
                pedirTexto(j, v, "Mensaje si no se cumple", "Acepta codigos &.",
                        l.mensaje(), l::mensaje);
                return;
            }
            case "valor" -> {
                Catalogo.Clase clase = s.cond.claseValor();
                if (esDeLista(clase)) {
                    s.paramClave = null;
                    abrir(j, new Vista(Pantalla.ELEGIR, 0, clase.name(), s.cond.etiquetaValor()));
                } else {
                    pedirTexto(j, v, s.cond.etiquetaValor(), s.cond.ayudaValor(), l.valor(), l::valor);
                }
                return;
            }
            default -> { }
        }

        if (!id.startsWith("param:")) return;
        String clave = id.substring(6);
        Catalogo.Param p = s.accion != null ? s.accion.param(clave) : s.cond.param(clave);
        if (p == null) return;
        s.paramClave = clave;

        switch (p.clase()) {
            case BOOL -> {
                boolean ahora = "true".equalsIgnoreCase(l.valorDe(p));
                l.poner(clave, String.valueOf(!ahora));
                abrir(j, new Vista(Pantalla.EDITOR, v.pagina, null, null));
            }
            case OPCIONES -> {
                List<String> ops = p.opciones();
                if (ops.isEmpty()) return;
                int i = ops.indexOf(l.valorDe(p));
                l.poner(clave, ops.get((i + 1 + ops.size()) % ops.size()));
                abrir(j, new Vista(Pantalla.EDITOR, v.pagina, null, null));
            }
            case PARTICULA -> abrir(j, new Vista(Pantalla.PART_GRUPOS, 0, null, null));
            case SONIDO -> abrir(j, new Vista(Pantalla.SON_FAMILIAS, 0, null, null));
            case POCION, CRIATURA, PROYECTIL, ACTIVADOR, GODITEM, COLOR ->
                    abrir(j, new Vista(Pantalla.ELEGIR, 0, p.clase().name(), p.etiqueta()));
            case MATERIAL, BLOQUE -> pedirMaterial(j, v, p);
            default -> pedirTexto(j, v, p.etiqueta(), p.ayuda(), l.valorDe(p),
                    t -> l.poner(clave, t));
        }
    }

    private static boolean esDeLista(Catalogo.Clase c) {
        return c == Catalogo.Clase.POCION || c == Catalogo.Clase.CRIATURA
                || c == Catalogo.Clase.PROYECTIL || c == Catalogo.Clase.ACTIVADOR
                || c == Catalogo.Clase.GODITEM || c == Catalogo.Clase.COLOR
                || c == Catalogo.Clase.OPCIONES;
    }

    /** Guarda lo elegido en la casilla que estuviera abierta y vuelve al editor. */
    private void aplicarElegido(Player j, String valor) {
        Sesion s = sesion(j);
        if (s.borrador == null) { raiz(j, 0); return; }
        if (s.paramClave == null) {
            /* Era el valor suelto de una condicion, no un `clave:valor`. */
            s.borrador.valor(valor);
        } else {
            s.borrador.poner(s.paramClave, valor);
        }
        s.paramClave = null;
        abrir(j, new Vista(Pantalla.EDITOR, 0, null, null));
    }

    private void pedirValorSuelto(Player j) {
        preguntar(j, CLARO + "Escribe el valor", null, "&8Tal cual va a la linea.",
                texto -> aplicarElegido(j, texto),
                () -> abrir(j, new Vista(Pantalla.EDITOR, 0, null, null)));
    }

    private void pedirTexto(Player j, Vista v, String etiqueta, String ayuda, String actual,
                            java.util.function.Consumer<String> guardar) {
        Runnable volver = () -> abrir(j, new Vista(Pantalla.EDITOR, v.pagina, null, null));
        preguntar(j, CLARO + etiqueta, actual,
                (ayuda == null || ayuda.isBlank() ? "" : "&8" + ayuda + "\n")
                        + "&8Escribe &fninguna&8 para dejarlo vacio.",
                texto -> {
                    guardar.accept(texto.equalsIgnoreCase("ninguna") ? "" : texto);
                    volver.run();
                }, volver);
    }

    private void pedirMaterial(Player j, Vista v, Catalogo.Param p) {
        Sesion s = sesion(j);
        ItemStack mano = j.getInventory().getItemInMainHand();
        String enMano = mano == null || mano.getType().isAir() ? null : mano.getType().name();
        Runnable volver = () -> abrir(j, new Vista(Pantalla.EDITOR, v.pagina, null, null));
        preguntar(j, CLARO + p.etiqueta(), s.borrador.valorDe(p),
                (enMano == null ? "&8Escribe el material."
                        : "&8Escribe &fmano&8 para copiar el tuyo (&f" + enMano + "&8).")
                        + "\n&8O &fninguna&8 para dejarlo vacio.",
                texto -> {
                    String valor;
                    if (texto.equalsIgnoreCase("ninguna")) {
                        valor = "";
                    } else if (texto.equalsIgnoreCase("mano") && enMano != null) {
                        valor = enMano;
                    } else {
                        valor = texto.trim().toUpperCase(Locale.ROOT);
                        Material m = Material.matchMaterial(valor);
                        if (m == null) {
                            j.sendMessage(Estilo.legado("&cNo existe el material &f" + valor + "&c."));
                            volver.run();
                            return;
                        }
                        if (p.clase() == Catalogo.Clase.BLOQUE && !m.isBlock()) {
                            j.sendMessage(Estilo.legado("&f" + valor + " &cno es un bloque."));
                            volver.run();
                            return;
                        }
                    }
                    s.borrador.poner(p.clave(), valor);
                    volver.run();
                }, volver);
    }

    private void guardarBorrador(Player j, GodItem def) {
        Sesion s = sesion(j);
        if (s.borrador == null) return;
        boolean acciones = s.accion != null;
        String linea = acciones ? s.borrador.escribirAccion(s.accion)
                                : s.borrador.escribirCondicion(s.cond);
        this.modulo.ficha().lineaPonerOAnadir(def, s.act, s.lista, s.indice, linea);
        j.sendMessage(Estilo.legado("&aGuardado: &f" + linea));
        s.borrador = null;
        s.accion = null;
        s.cond = null;
        abrir(j, new Vista(Pantalla.LISTA, 0, null, null));
    }

    /* -------------------------------------------------------------- probar */

    /**
     * Ejecuta UNA accion sobre el jugador, sin condiciones ni enfriamiento.
     *
     * Es el boton que faltaba: hasta ahora, para ver si una explosion quedaba
     * bien habia que guardar, salir del menu, coger el item y pulsarlo.
     */
    private void probarLinea(Player j, GodItem def, String linea) {
        Args args = Args.de(linea);
        Accion a = Acciones.buscar(args.nombre());
        if (a == null) {
            j.sendMessage(Estilo.legado("&cEl motor no conoce la accion &f" + args.nombre() + "&c."));
            return;
        }
        if (a.textoLibre()) args = Args.deTextoLibre(linea);
        Ctx ctx = new Ctx(this.modulo, j, def, Activador.DISPARADOR,
                j.getInventory().getItemInMainHand(), EquipmentSlot.HAND, null)
                .objetivo(j).lugar(j.getLocation());
        try {
            a.correr(ctx, args);
            j.sendMessage(Estilo.legado("&8probado: &7" + recortar(linea, 60)));
        } catch (Throwable t) {
            j.sendMessage(Estilo.legado("&cFallo al probarla: " + t));
        }
    }

    private void probarActivador(Player j, GodItem def) {
        Sesion s = sesion(j);
        if (s.act == null) return;
        /* Se le quita el enfriamiento antes: probar dos veces seguidas y que la
         * segunda no haga nada parece un fallo del item cuando no lo es. */
        this.modulo.cooldowns().quitar(j, def.id(), s.act);
        boolean fue = this.modulo.disparar(j, j.getInventory().getItemInMainHand(), def, s.act,
                null, EquipmentSlot.HAND, j, j.getLocation());
        j.sendMessage(Estilo.legado(fue
                ? "&aDisparado &f" + s.act.name() + "&a."
                : "&eNo se disparo: alguna condicion no se cumple, o no tiene acciones."));
    }

    private void previsualizarParticula(Player j, String nombre) {
        org.bukkit.Particle p = Particulas.particula(nombre);
        if (p == null) return;
        Particulas.Info info = Particulas.info(nombre.toLowerCase(Locale.ROOT));
        j.closeInventory();
        Args a = Args.de("PARTICULA");
        org.bukkit.Location donde = j.getEyeLocation()
                .add(j.getEyeLocation().getDirection().multiply(2.5));
        Object dato = Particulas.datos(p, a, donde, j);
        if (dato == null) {
            net.ederus.edm.anomaly.core.Compat.spawn(j.getWorld(), p, donde, 30, 0.4, 0.4, 0.4, 0.02);
        } else {
            net.ederus.edm.anomaly.core.Compat.spawn(j.getWorld(), p, donde, 30, 0.4, 0.4, 0.4, 0.02, dato);
        }
        j.sendMessage(Estilo.legado("&7Pintadas 30 de &f" + (info == null ? nombre : info.nombre())
                + " &7delante de ti. &8" + p.name()
                + (info != null && !info.pideDato().isEmpty() ? " — pide: " + info.pideDato() : "")));
    }

    /* ------------------------------------------------------------- buscar */

    private void buscarCatalogo(Player j) {
        Sesion s = sesion(j);
        boolean acciones = s.lista.equals("acciones");
        preguntar(j, AZUL + "Buscar " + (acciones ? "accion" : "condicion"), null,
                "&8Un trozo del nombre o de la descripcion.",
                texto -> {
                    s.consulta = texto;
                    s.buscandoAcciones = acciones;
                    List<String> nombres = new ArrayList<>();
                    if (acciones) {
                        for (Catalogo.Accion a : Catalogo.buscarAcciones(texto)) nombres.add(a.nombre());
                    } else {
                        for (Catalogo.Cond c : Catalogo.buscarCondiciones(texto)) nombres.add(c.nombre());
                    }
                    s.resultados = nombres;
                    if (nombres.isEmpty()) {
                        j.sendMessage(Estilo.legado("&7Nada con &f" + texto + "&7."));
                        abrir(j, new Vista(Pantalla.CAT_GRUPOS, 0, null, null));
                        return;
                    }
                    abrir(j, new Vista(Pantalla.RESULTADOS, 0, null, null));
                },
                () -> abrir(j, new Vista(Pantalla.CAT_GRUPOS, 0, null, null)));
    }

    private void buscarSonido(Player j) {
        preguntar(j, AZUL + "Buscar sonido", null, "&8Un trozo de la clave. Ej: &fwarden",
                texto -> {
                    List<String> l = Sonidos.buscar(texto, 40);
                    if (l.isEmpty()) {
                        j.sendMessage(Estilo.legado("&7Ningun sonido con &f" + texto + "&7."));
                    } else {
                        j.sendMessage(Estilo.legado("&7" + l.size() + " sonidos con &f" + texto + "&7:"));
                        for (String c : l) j.sendMessage(Estilo.legado("&8 " + Estilo.FLECHA + " &f" + c));
                        j.sendMessage(Estilo.legado("&8Copialo con el boton de escribirlo por chat."));
                    }
                    abrir(j, new Vista(Pantalla.SON_FAMILIAS, 0, null, null));
                },
                () -> abrir(j, new Vista(Pantalla.SON_FAMILIAS, 0, null, null)));
    }

    /* =============================================================== ayudas */

    /**
     * Pide un valor por el chat.
     *
     * Se enseña el valor ACTUAL antes de pedir el nuevo: en un menu de cofre no
     * se puede escribir dentro de una casilla, asi que sin esto el jugador
     * tendria que acordarse de lo que habia.
     */
    private void preguntar(Player j, String que, String actual, String pista,
                           java.util.function.Consumer<String> alResponder, Runnable alCancelar) {
        j.sendMessage(Estilo.regla());
        j.sendMessage(Estilo.legado(que));
        if (actual != null && !actual.isBlank()) {
            j.sendMessage(Estilo.legado("&7Ahora: &f" + recortar(actual, 120)));
        }
        if (pista != null) {
            for (String linea : pista.split("\n")) {
                if (!linea.isBlank()) j.sendMessage(Estilo.legado(linea));
            }
        }
        j.sendMessage(Estilo.legado("&7Escribelo en el chat, o &fcancelar&7 para dejarlo."));
        if (Plataforma.esBedrock(j)) {
            j.sendMessage(Estilo.legado("&8Desde Bedrock: abre el chat con el boton del teclado."));
        }
        j.sendMessage(Estilo.regla());
        this.modulo.core().chat().pedir(j, alResponder, alCancelar);
    }

    /**
     * Confirmacion de lo que no se deshace con otro clic.
     *
     * Va por chat y no con una pantalla de "si / no" porque desde Bedrock un
     * menu de confirmacion en el mismo sitio donde estaba el boton se pulsa
     * solo: el dedo ya venia bajando.
     */
    private void confirmar(Player j, String que, Runnable si, Runnable no) {
        j.sendMessage(Estilo.regla());
        j.sendMessage(Estilo.legado("&cVas a " + que + "."));
        j.sendMessage(Estilo.legado("&7Escribe &fsi&7 para confirmarlo, o &fcancelar&7."));
        j.sendMessage(Estilo.regla());
        j.closeInventory();
        this.modulo.core().chat().pedir(j, texto -> {
            String t = texto.trim();
            if (t.equalsIgnoreCase("si") || t.equalsIgnoreCase("sí")) si.run();
            else no.run();
        }, no);
    }

    private void paginar(Vista v, int total) {
        int paginas = Math.max(1, (total + POR_PAGINA - 1) / POR_PAGINA);
        if (v.pagina > 0) {
            poner(v, R_ANTERIOR, adorno(new ItemStack(Material.ARROW), CLARO + "Anterior",
                    List.of("&8pagina " + v.pagina + " de " + paginas)), "pagina");
        }
        if ((v.pagina + 1) * POR_PAGINA < total) {
            poner(v, R_SIGUIENTE, adorno(new ItemStack(Material.ARROW), CLARO + "Siguiente",
                    List.of("&8pagina " + (v.pagina + 2) + " de " + paginas)), "pagina");
        }
    }

    private ItemStack volver() {
        return adorno(new ItemStack(Material.BARRIER), CLARO + "Volver", List.of());
    }

    private ItemStack interruptor(String nombre, boolean puesto, String explica) {
        return adorno(new ItemStack(puesto ? Material.LIME_DYE : Material.GRAY_DYE),
                (puesto ? VERDE : GRIS) + nombre,
                List.of("&7" + explica, "&7" + (puesto ? "puesto" : "quitado"), "",
                        AZUL + Estilo.FLECHA + " " + (puesto ? "Quitar" : "Poner")));
    }

    /** El icono de un GodItem: el que fabrica MMOItems si es enlazado. */
    private ItemStack iconoDe(GodItem def) {
        if (def.enlazado()) {
            ItemStack it = this.modulo.puente().construir(def.enlaceTipo(), def.enlaceId());
            if (it != null && !it.getType().isAir()) return it.clone();
            return adorno(new ItemStack(Material.STRUCTURE_VOID), "&c" + def.id(),
                    List.of("&8" + def.enlace() + " ya no existe en MMOItems"));
        }
        ItemStack it = this.modulo.fabricar(def, 1);
        return it == null ? new ItemStack(Material.PAPER) : it;
    }

    private static Material materialDe(Plantillas.Campo c) {
        return switch (c.id()) {
            case "nombre" -> Material.NAME_TAG;
            case "lore" -> Material.WRITABLE_BOOK;
            case "material" -> Material.STONE;
            case "tier" -> Material.NETHER_STAR;
            case "set" -> Material.IRON_CHESTPLATE;
            case "durabilidad" -> Material.ANVIL;
            case "dano" -> Material.IRON_SWORD;
            case "velocidad" -> Material.SUGAR;
            case "critico", "poder-critico" -> Material.BLAZE_POWDER;
            case "armadura" -> Material.SHIELD;
            case "vida" -> Material.GOLDEN_APPLE;
            case "nivel" -> Material.EXPERIENCE_BOTTLE;
            default -> Material.PAPER;
        };
    }

    /** La primera linea de una lista, para el boton que la abre. */
    private static String vistazo(List<?> lineas) {
        if (lineas.isEmpty()) return "&8vacia";
        return "&8" + recortar(String.valueOf(lineas.get(0)), 40)
                + (lineas.size() > 1 ? " &8..." : "");
    }

    private void poner(Vista v, int ranura, ItemStack item, String accion) {
        v.inv.setItem(ranura, item);
        if (accion != null) v.acciones.put(ranura, accion);
    }

    private static ItemStack adorno(ItemStack item, String nombre, List<String> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        if (nombre != null) meta.displayName(Estilo.legado(nombre));
        if (lore != null && !lore.isEmpty()) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) {
                if (s == null) continue;
                l.add(Estilo.legado(s));
            }
            meta.lore(l);
        }
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    /** Como adorno pero conservando el nombre que ya trae (items de MMOItems). */
    private static void anadirLore(ItemStack item, List<String> extra) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        List<Component> l = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        for (String s : extra) l.add(Estilo.legado(s));
        meta.lore(l);
        item.setItemMeta(meta);
    }

    private static String recortar(String s, int max) {
        if (s == null) return "";
        String limpio = s.replace('\n', ' ');
        return limpio.length() <= max ? limpio : limpio.substring(0, max - 1) + "…";
    }

    /** Parte un texto largo en varias lineas de lore, sin cortar palabras a lo bruto. */
    private static List<String> partir(String s, int ancho) {
        List<String> out = new ArrayList<>();
        String resto = s == null ? "" : s.trim();
        while (resto.length() > ancho) {
            int corte = resto.lastIndexOf(' ', ancho);
            if (corte <= 0) corte = ancho;
            out.add(resto.substring(0, corte));
            resto = resto.substring(corte).trim();
        }
        out.add(resto);
        return out;
    }

    private static String mayuscula(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
