package net.ederus.edm.goditems.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.Indyuce.mmoitems.api.Type;
import net.ederus.edm.comun.Estilo;
import net.ederus.edm.comun.Plataforma;
import net.ederus.edm.goditems.Activador;
import net.ederus.edm.goditems.GodItem;
import net.ederus.edm.goditems.GodItemsPlugin;
import net.ederus.edm.goditems.mmo.Plantillas;
import net.kyori.adventure.text.Component;

/**
 * La interfaz de GodItems.
 *
 * Seis pantallas: la lista, el importador (tipos e items de MMOItems), la ficha
 * de un item, el detalle de un activador y el menu de una linea suelta.
 *
 * TODO se hace con CLIC IZQUIERDO, y no es una simplificacion: desde Bedrock,
 * Geyser traduce cualquier toque a clic izquierdo y no existen ni el clic
 * derecho ni el shift-clic. Un editor que usara el clic derecho para borrar
 * seria un editor que la mitad del servidor no puede usar. Por eso borrar,
 * subir y bajar una linea tienen su propia pantalla en vez de un modificador.
 *
 * Los valores se piden por chat con la entrada compartida del nucleo: en un
 * menu de cofre no se puede escribir, y los yunques tienen limite de caracteres
 * y se comen los colores.
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

    private static final int R_ANTERIOR = 45;
    private static final int R_VOLVER = 49;
    private static final int R_SIGUIENTE = 53;
    private static final int R_ACCION_A = 47;
    private static final int R_ACCION_B = 51;

    public enum Pantalla { RAIZ, TIPOS, ITEMS_MMO, FICHA, ACTIVADOR, LINEA }

    /** La ventana abierta y todo lo que hace falta para volver a pintarla. */
    public static final class Vista implements InventoryHolder {
        final Pantalla pantalla;
        final int pagina;
        final String itemId;        // GodItem abierto
        final String tipoMmo;       // tipo de MMOItems en el importador
        final Activador activador;
        final String lista;         // "acciones" o "condiciones"
        final int indice;
        final Map<Integer, String> acciones = new HashMap<>();
        Inventory inv;

        Vista(Pantalla p, int pagina, String itemId, String tipoMmo, Activador act, String lista, int indice) {
            this.pantalla = p;
            this.pagina = pagina;
            this.itemId = itemId;
            this.tipoMmo = tipoMmo;
            this.activador = act;
            this.lista = lista;
            this.indice = indice;
        }

        @Override public Inventory getInventory() { return this.inv; }
    }

    private final GodItemsPlugin modulo;

    public MenuGi(GodItemsPlugin modulo) {
        this.modulo = modulo;
    }

    /* ============================================================== abrir */

    public void raiz(Player j, int pagina) {
        abrir(j, new Vista(Pantalla.RAIZ, pagina, null, null, null, null, -1));
    }

    public void ficha(Player j, String itemId) {
        abrir(j, new Vista(Pantalla.FICHA, 0, itemId, null, null, null, -1));
    }

    public void importador(Player j, int pagina) {
        abrir(j, new Vista(Pantalla.TIPOS, pagina, null, null, null, null, -1));
    }

    private void abrir(Player j, Vista v) {
        v.inv = Bukkit.createInventory(v, TAM, titulo(v));
        pintar(v);
        j.openInventory(v.inv);
    }

    private Component titulo(Vista v) {
        return switch (v.pantalla) {
            case RAIZ -> Estilo.titulo("GODITEMS", this.modulo.registro().cuantos() + " items");
            case TIPOS -> Estilo.titulo("IMPORTAR", "elige tipo");
            case ITEMS_MMO -> Estilo.titulo("IMPORTAR", v.tipoMmo);
            case FICHA -> Estilo.titulo("GODITEM", v.itemId);
            case ACTIVADOR -> Estilo.titulo(v.activador.name(), v.itemId);
            case LINEA -> Estilo.titulo("LINEA", v.lista + " " + (v.indice + 1));
        };
    }

    /* ============================================================= pintar */

    private void pintar(Vista v) {
        v.inv.clear();
        v.acciones.clear();
        switch (v.pantalla) {
            case RAIZ -> pintarRaiz(v);
            case TIPOS -> pintarTipos(v);
            case ITEMS_MMO -> pintarItemsMmo(v);
            case FICHA -> pintarFicha(v);
            case ACTIVADOR -> pintarActivador(v);
            case LINEA -> pintarLinea(v);
        }
    }

    private void pintarRaiz(Vista v) {
        List<GodItem> todos = new ArrayList<>(this.modulo.registro().todos());
        paginar(v, todos.size());
        int desde = v.pagina * POR_PAGINA;
        for (int i = 0; i < POR_PAGINA && desde + i < todos.size(); i++) {
            GodItem def = todos.get(desde + i);
            ItemStack icono = iconoDe(def);
            adorno(icono, def.id(), List.of(
                    def.enlazado() ? "&8enlazado a &f" + def.enlace() : "&8nativo",
                    "&8" + def.bloques().size() + " activadores",
                    "",
                    "&#91F4FF" + Estilo.FLECHA + " Abrir la ficha"));
            poner(v, RANURAS[i], icono, "abrir:" + def.id());
        }
        if (todos.isEmpty()) {
            poner(v, 22, adorno(new ItemStack(Material.BARRIER), "&cNo hay ningun GodItem",
                    List.of("&7Importa uno de MMOItems con el boton de abajo.")), null);
        }
        poner(v, R_ACCION_A, adorno(new ItemStack(Material.HOPPER), "&#4FFF55Importar de MMOItems",
                List.of("&7Elige un item que ya exista en MMOItems",
                        "&7y dale comportamiento sin tocar sus stats.")), "importar");
        poner(v, R_ACCION_B, adorno(new ItemStack(Material.CLOCK), "&#91F4FFRecargar",
                List.of("&7Vuelve a leer los YAML.")), "recargar");
    }

    private void pintarTipos(Vista v) {
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
            }
            if (icono == null || icono.getType().isAir()) icono = new ItemStack(Material.NAME_TAG);
            else icono = icono.clone();
            adorno(icono, "&f" + t.getName(), List.of(
                    "&8" + t.getId(),
                    "&8" + this.modulo.puente().itemsDe(t).size() + " items",
                    "",
                    "&#91F4FF" + Estilo.FLECHA + " Ver sus items"));
            poner(v, RANURAS[i], icono, "tipo:" + t.getId());
        }
        poner(v, R_VOLVER, volver(), "raiz");
    }

    private void pintarItemsMmo(Vista v) {
        Type t = this.modulo.puente().tipo(v.tipoMmo);
        List<String> ids = t == null ? List.of() : this.modulo.puente().itemsDe(t);
        paginar(v, ids.size());
        int desde = v.pagina * POR_PAGINA;
        for (int i = 0; i < POR_PAGINA && desde + i < ids.size(); i++) {
            String id = ids.get(desde + i);
            ItemStack icono = this.modulo.puente().construir(v.tipoMmo, id);
            if (icono == null || icono.getType().isAir()) icono = new ItemStack(Material.PAPER);
            else icono = icono.clone();
            boolean ya = this.modulo.registro().porEnlace(v.tipoMmo, id) != null;
            List<String> lore = new ArrayList<>();
            lore.add("&8" + v.tipoMmo + "." + id);
            lore.add("");
            lore.add(ya ? "&e" + Estilo.FLECHA + " Ya importado, abre su ficha"
                        : "&#4FFF55" + Estilo.FLECHA + " Importar a GodItems");
            /* El nombre NO se toca: es el del item de MMOItems y se enseña tal
             * cual, que es de lo que se trata. Solo se le añade el lore. */
            anadirLore(icono, lore);
            poner(v, RANURAS[i], icono, "importar:" + v.tipoMmo + ":" + id);
        }
        poner(v, R_VOLVER, volver(), "tipos");
    }

    private void pintarFicha(Vista v) {
        GodItem def = this.modulo.registro().porId(v.itemId);
        if (def == null) {
            poner(v, 22, adorno(new ItemStack(Material.BARRIER), "&cEse item ya no existe", List.of()), null);
            poner(v, R_VOLVER, volver(), "raiz");
            return;
        }

        poner(v, 4, iconoDe(def), null);

        if (def.enlazado()) {
            /* Lo de MMOItems. Al editarlo se escribe en SU yaml: no hay copia
             * nuestra, asi que los dos editores ven siempre lo mismo. */
            for (int i = 0; i < Plantillas.CAMPOS.size() && i < 14; i++) {
                Plantillas.Campo c = Plantillas.CAMPOS.get(i);
                String valor = this.modulo.plantillas().leer(def.enlaceTipo(), def.enlaceId(), c);
                poner(v, RANURAS[i], adorno(new ItemStack(materialDe(c)),
                        "&#D7F3FF" + c.etiqueta(),
                        List.of("&8de MMOItems",
                                "&7" + (valor == null || valor.isBlank() ? "sin poner" : recortar(valor, 40)),
                                "",
                                "&#91F4FF" + Estilo.FLECHA + " Cambiar",
                                "&8se escribe en " + def.enlaceTipo().toLowerCase(Locale.ROOT) + ".yml")),
                        "campo:" + c.id());
            }
        } else {
            poner(v, 13, adorno(new ItemStack(Material.PAPER), "&7Item nativo",
                    List.of("&7Su aspecto y sus stats se editan",
                            "&7en su propio YAML, no aqui.",
                            "&8" + (def.fichero() == null ? "?" : def.fichero().getName()))), null);
        }

        /* Los activadores, que es lo que GodItems si posee. */
        int fila = 14;
        int n = 0;
        for (Activador a : def.bloques().keySet()) {
            if (n >= 7) break;
            GodItem.Bloque b = def.bloque(a);
            poner(v, RANURAS[fila + n], adorno(new ItemStack(Material.REDSTONE_TORCH),
                    "&#FFD65C" + a.name(),
                    List.of("&8comportamiento de GodItems",
                            "&7" + b.pasos().size() + " acciones, "
                                    + b.condiciones().size() + " condiciones",
                            "",
                            "&#91F4FF" + Estilo.FLECHA + " Abrir")),
                    "act:" + a.name());
            n++;
        }

        poner(v, RANURAS[21], adorno(new ItemStack(Material.LEVER), "&#4FFF55Añadir activador",
                List.of("&7Se escribe su nombre por el chat.")), "act-nuevo");
        poner(v, RANURAS[22], adorno(new ItemStack(Material.EXPERIENCE_BOTTLE), "&#D7F3FFUsos",
                List.of("&7" + (def.usos() < 0 ? "sin limite" : String.valueOf(def.usos())),
                        "", "&#91F4FF" + Estilo.FLECHA + " Cambiar")), "gi:usos");
        poner(v, RANURAS[23], adorno(new ItemStack(Material.CLOCK), "&#D7F3FFUsos por dia",
                List.of("&7" + (def.usosPorDia() < 0 ? "sin limite" : String.valueOf(def.usosPorDia())),
                        "", "&#91F4FF" + Estilo.FLECHA + " Cambiar")), "gi:usos-por-dia");
        poner(v, RANURAS[24], interruptor("Exclusivo", def.exclusivo(),
                "Calla la habilidad del set en ese mismo gesto."), "gi:exclusivo");
        poner(v, RANURAS[25], interruptor("Solo su dueño", def.soloDueno(),
                "Solo responde a quien se lo dieron."), "gi:solo-dueno");
        poner(v, RANURAS[26], interruptor("Conservar al morir", def.conservarAlMorir(),
                "Se le devuelve al reaparecer."), "gi:conservar-al-morir");
        poner(v, RANURAS[27], adorno(new ItemStack(Material.WRITABLE_BOOK), "&#D7F3FFLineas de lore propias",
                List.of("&7" + def.loreExtra().size() + " lineas",
                        "&8se añaden al construir el item",
                        "", "&#91F4FF" + Estilo.FLECHA + " Cambiar")), "gi:lore-extra");

        poner(v, R_VOLVER, volver(), "raiz");
    }

    private void pintarActivador(Vista v) {
        GodItem def = this.modulo.registro().porId(v.itemId);
        if (def == null || v.activador == null || def.bloque(v.activador) == null) {
            poner(v, R_VOLVER, volver(), "ficha:" + v.itemId);
            return;
        }
        GodItem.Bloque b = def.bloque(v.activador);

        List<?> acciones = this.modulo.ficha().lineas(def, v.activador, "acciones");
        for (int i = 0; i < 14 && i < acciones.size(); i++) {
            Object linea = acciones.get(i);
            boolean bloque = !(linea instanceof String);
            poner(v, RANURAS[i], adorno(new ItemStack(bloque ? Material.REPEATER : Material.PAPER),
                    "&f" + (i + 1) + ". " + (bloque ? "bloque anidado" : recortar(String.valueOf(linea), 34)),
                    bloque
                        ? List.of("&8si / repetir", "&8se edita en el YAML, no aqui")
                        : List.of("&8" + recortar(String.valueOf(linea), 80),
                                  "", "&#91F4FF" + Estilo.FLECHA + " Editar, mover o borrar")),
                    bloque ? null : "linea:acciones:" + i);
        }

        List<?> condiciones = this.modulo.ficha().lineas(def, v.activador, "condiciones");
        for (int i = 0; i < 7 && i < condiciones.size(); i++) {
            poner(v, RANURAS[14 + i], adorno(new ItemStack(Material.COMPARATOR),
                    "&#FDFF66" + (i + 1) + ". " + recortar(String.valueOf(condiciones.get(i)), 30),
                    List.of("&8condicion",
                            "", "&#91F4FF" + Estilo.FLECHA + " Editar, mover o borrar")),
                    "linea:condiciones:" + i);
        }

        poner(v, RANURAS[21], adorno(new ItemStack(Material.PAPER), "&#4FFF55Añadir accion",
                List.of("&7Se escribe por el chat.",
                        "&8ej: DANO @golpeado cantidad:8")), "nueva:acciones");
        poner(v, RANURAS[22], adorno(new ItemStack(Material.COMPARATOR), "&#4FFF55Añadir condicion",
                List.of("&7Se escribe por el chat.",
                        "&8ej: AGACHADO")), "nueva:condiciones");
        poner(v, RANURAS[23], adorno(new ItemStack(Material.CLOCK), "&#D7F3FFEnfriamiento",
                List.of("&7" + (b.cooldown() <= 0 ? "ninguno"
                        : net.ederus.edm.goditems.Numeros.reloj(b.cooldown())),
                        "", "&#91F4FF" + Estilo.FLECHA + " Cambiar")), "bloque:cooldown");
        if (v.activador == Activador.SET_COMPLETO) {
            poner(v, RANURAS[24], adorno(new ItemStack(Material.IRON_CHESTPLATE), "&#D7F3FFPiezas del set",
                    List.of("&7" + (b.piezas() <= 0 ? "las que diga MMOItems" : String.valueOf(b.piezas())),
                            "", "&#91F4FF" + Estilo.FLECHA + " Cambiar")), "bloque:piezas");
        }
        if (v.activador.esTick()) {
            poner(v, RANURAS[25], adorno(new ItemStack(Material.REPEATER), "&#D7F3FFCada",
                    List.of("&7" + net.ederus.edm.goditems.Numeros.reloj(b.cada()),
                            "&8redondeado al repaso del modulo",
                            "", "&#91F4FF" + Estilo.FLECHA + " Cambiar")), "bloque:cada");
        }
        poner(v, RANURAS[27], adorno(new ItemStack(Material.BARRIER), "&cBorrar este activador",
                List.of("&7Se pide confirmacion.")), "act-borrar");

        poner(v, R_VOLVER, volver(), "ficha:" + v.itemId);
    }

    private void pintarLinea(Vista v) {
        GodItem def = this.modulo.registro().porId(v.itemId);
        if (def == null) return;
        List<?> lineas = this.modulo.ficha().lineas(def, v.activador, v.lista);
        String texto = v.indice >= 0 && v.indice < lineas.size()
                ? String.valueOf(lineas.get(v.indice)) : "";

        poner(v, 4, adorno(new ItemStack(Material.PAPER), "&f" + recortar(texto, 34),
                List.of("&8" + v.lista + " " + (v.indice + 1) + " de " + lineas.size())), null);
        poner(v, RANURAS[7], adorno(new ItemStack(Material.WRITABLE_BOOK), "&#91F4FFEditar",
                List.of("&7Se escribe la linea nueva por el chat.")), "linea-editar");
        poner(v, RANURAS[8], adorno(new ItemStack(Material.SPECTRAL_ARROW), "&#D7F3FFSubir",
                List.of("&7La mueve una posicion arriba.")), "linea-subir");
        poner(v, RANURAS[9], adorno(new ItemStack(Material.ARROW), "&#D7F3FFBajar",
                List.of("&7La mueve una posicion abajo.")), "linea-bajar");
        poner(v, RANURAS[10], adorno(new ItemStack(Material.BARRIER), "&cBorrar",
                List.of("&7La quita de la lista.")), "linea-borrar");
        poner(v, R_VOLVER, volver(), "act:" + v.activador.name());
    }

    /* ============================================================== clics */

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
        if (slot == R_ANTERIOR && v.inv.getItem(R_ANTERIOR) != null) {
            abrir(j, copiaConPagina(v, v.pagina - 1));
            return;
        }
        if (slot == R_SIGUIENTE && v.inv.getItem(R_SIGUIENTE) != null) {
            abrir(j, copiaConPagina(v, v.pagina + 1));
            return;
        }
        String accion = v.acciones.get(slot);
        if (accion == null) return;
        atender(j, v, accion);
    }

    private void atender(Player j, Vista v, String accion) {
        GodItem def = v.itemId == null ? null : this.modulo.registro().porId(v.itemId);

        if (accion.equals("raiz")) { raiz(j, 0); return; }
        if (accion.equals("tipos")) { importador(j, 0); return; }
        if (accion.equals("recargar")) {
            j.sendMessage(Estilo.legado("&aGodItems: &f" + this.modulo.recargar()));
            raiz(j, v.pagina);
            return;
        }
        if (accion.equals("importar")) { importador(j, 0); return; }
        if (accion.startsWith("abrir:")) { ficha(j, accion.substring(6)); return; }
        if (accion.startsWith("ficha:")) { ficha(j, accion.substring(6)); return; }
        if (accion.startsWith("tipo:")) {
            abrir(j, new Vista(Pantalla.ITEMS_MMO, 0, null, accion.substring(5), null, null, -1));
            return;
        }
        if (accion.startsWith("importar:")) {
            String[] p = accion.split(":", 3);
            GodItem ya = idPorEnlace(p[1], p[2]);
            if (ya != null) { ficha(j, ya.id()); return; }
            GodItem nuevo = this.modulo.ficha().importar(p[1], p[2]);
            if (nuevo == null) {
                j.sendMessage(Estilo.legado("&cNo se pudo importar " + p[1] + "." + p[2] + "."));
                return;
            }
            j.sendMessage(Estilo.legado("&aImportado &f" + p[1] + "." + p[2]
                    + " &acomo &f" + nuevo.id() + "&a."));
            ficha(j, nuevo.id());
            return;
        }
        if (def == null) return;

        if (accion.startsWith("act:")) {
            Activador a = Activador.porNombre(accion.substring(4));
            if (a != null) abrir(j, new Vista(Pantalla.ACTIVADOR, 0, def.id(), null, a, null, -1));
            return;
        }
        if (accion.startsWith("campo:")) { editarCampo(j, v, def, accion.substring(6)); return; }
        if (accion.startsWith("gi:")) { editarPropio(j, v, def, accion.substring(3)); return; }
        if (accion.startsWith("linea:")) {
            String[] p = accion.split(":", 3);
            abrir(j, new Vista(Pantalla.LINEA, 0, def.id(), null, v.activador, p[1],
                    Integer.parseInt(p[2])));
            return;
        }
        if (accion.startsWith("nueva:")) { nuevaLinea(j, v, def, accion.substring(6)); return; }
        if (accion.startsWith("bloque:")) { editarBloque(j, v, def, accion.substring(7)); return; }
        if (accion.equals("act-nuevo")) { nuevoActivador(j, def); return; }
        if (accion.equals("act-borrar")) {
            this.modulo.ficha().borrarActivador(def, v.activador);
            j.sendMessage(Estilo.legado("&7Activador &f" + v.activador.name() + " &7borrado."));
            ficha(j, def.id());
            return;
        }
        switch (accion) {
            case "linea-editar" -> editarLinea(j, v, def);
            case "linea-subir" -> moverLinea(j, v, def, -1);
            case "linea-bajar" -> moverLinea(j, v, def, 1);
            case "linea-borrar" -> {
                this.modulo.ficha().lineaBorrar(def, v.activador, v.lista, v.indice);
                abrir(j, new Vista(Pantalla.ACTIVADOR, 0, def.id(), null, v.activador, null, -1));
            }
            default -> { }
        }
    }

    /* ========================================================== ediciones */

    private void editarCampo(Player j, Vista v, GodItem def, String campoId) {
        Plantillas.Campo campo = Plantillas.campo(campoId);
        if (campo == null || !def.enlazado()) return;
        String actual = this.modulo.plantillas().leer(def.enlaceTipo(), def.enlaceId(), campo);
        preguntar(j, def,
                "&#D7F3FF" + campo.etiqueta() + " &8de " + def.enlace(),
                actual,
                campo.clase() == Plantillas.Clase.LISTA
                        ? "&8Separa las lineas con &f|&8. Acepta codigos &&."
                        : (campo.clase() == Plantillas.Clase.NUMERO
                                ? "&8Escribe un numero." : "&8Acepta codigos &&."),
                texto -> {
                    String fallo = this.modulo.plantillas().escribir(
                            def.enlaceTipo(), def.enlaceId(), campo, texto);
                    if (fallo != null) {
                        j.sendMessage(Estilo.legado("&c" + fallo));
                    } else {
                        j.sendMessage(Estilo.legado("&a" + campo.etiqueta() + " de &f" + def.enlace()
                                + " &acambiado. Lo veran igual &f/gi&a y &f/mi&a."));
                    }
                    ficha(j, def.id());
                },
                () -> ficha(j, def.id()));
    }

    private void editarPropio(Player j, Vista v, GodItem def, String clave) {
        /* Los interruptores no preguntan nada: se giran y ya. */
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
        preguntar(j, def, "&#D7F3FF" + clave, actual,
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
                        this.modulo.ficha().poner(def, clave,
                                (int) net.ederus.edm.goditems.Numeros.decimal(texto, -1));
                    }
                    ficha(j, def.id());
                },
                () -> ficha(j, def.id()));
    }

    private void girar(Player j, GodItem def, String clave, boolean actual) {
        this.modulo.ficha().poner(def, clave, !actual);
        ficha(j, def.id());
    }

    private void editarBloque(Player j, Vista v, GodItem def, String clave) {
        GodItem.Bloque b = def.bloque(v.activador);
        if (b == null) return;
        String actual = switch (clave) {
            case "cooldown" -> b.cooldown() + "t";
            case "piezas" -> String.valueOf(b.piezas());
            case "cada" -> b.cada() + "t";
            default -> "";
        };
        preguntar(j, def, "&#D7F3FF" + clave + " &8de " + v.activador.name(), actual,
                clave.equals("piezas") ? "&8Un numero. &f0 &8= las que diga MMOItems."
                        : "&8Tiempo: &f30s&8, &f2m&8, &f40t&8.",
                texto -> {
                    Object valor = clave.equals("piezas")
                            ? (int) net.ederus.edm.goditems.Numeros.decimal(texto, 0) : texto;
                    this.modulo.ficha().poner(def, "activadores." + v.activador.name() + "." + clave, valor);
                    abrir(j, new Vista(Pantalla.ACTIVADOR, 0, def.id(), null, v.activador, null, -1));
                },
                () -> abrir(j, new Vista(Pantalla.ACTIVADOR, 0, def.id(), null, v.activador, null, -1)));
    }

    private void nuevaLinea(Player j, Vista v, GodItem def, String lista) {
        preguntar(j, def, "&#4FFF55Nueva " + (lista.equals("acciones") ? "accion" : "condicion"), null,
                lista.equals("acciones")
                        ? "&8ej: &fEXPLOSION @yo radio:6 dano:10"
                        : "&8ej: &fVIDA menor 50% | &cTe falta vida",
                texto -> {
                    this.modulo.ficha().lineaPonerOAnadir(def, v.activador, lista, -1, texto);
                    abrir(j, new Vista(Pantalla.ACTIVADOR, 0, def.id(), null, v.activador, null, -1));
                },
                () -> abrir(j, new Vista(Pantalla.ACTIVADOR, 0, def.id(), null, v.activador, null, -1)));
    }

    private void editarLinea(Player j, Vista v, GodItem def) {
        List<?> lineas = this.modulo.ficha().lineas(def, v.activador, v.lista);
        String actual = v.indice < lineas.size() ? String.valueOf(lineas.get(v.indice)) : "";
        preguntar(j, def, "&#91F4FFEditar linea", actual, "&8Escribe la linea entera.",
                texto -> {
                    this.modulo.ficha().lineaPonerOAnadir(def, v.activador, v.lista, v.indice, texto);
                    abrir(j, new Vista(Pantalla.ACTIVADOR, 0, def.id(), null, v.activador, null, -1));
                },
                () -> abrir(j, new Vista(Pantalla.ACTIVADOR, 0, def.id(), null, v.activador, null, -1)));
    }

    private void moverLinea(Player j, Vista v, GodItem def, int salto) {
        this.modulo.ficha().lineaMover(def, v.activador, v.lista, v.indice, salto);
        abrir(j, new Vista(Pantalla.LINEA, 0, def.id(), null, v.activador, v.lista,
                Math.max(0, v.indice + salto)));
    }

    private void nuevoActivador(Player j, GodItem def) {
        StringBuilder ayuda = new StringBuilder();
        for (Activador a : Activador.values()) {
            if (def.bloque(a) != null) continue;
            if (ayuda.length() > 0) ayuda.append(", ");
            ayuda.append(a.name());
        }
        preguntar(j, def, "&#4FFF55Nuevo activador", null,
                "&8" + recortar(ayuda.toString(), 300),
                texto -> {
                    Activador a = Activador.porNombre(texto);
                    if (a == null) {
                        j.sendMessage(Estilo.legado("&cNo existe el activador &f" + texto + "&c."));
                    } else if (!this.modulo.ficha().anadirActivador(def, a)) {
                        j.sendMessage(Estilo.legado("&e" + a.name() + " ya estaba puesto."));
                    }
                    ficha(j, def.id());
                },
                () -> ficha(j, def.id()));
    }

    /**
     * Pide un valor por el chat.
     *
     * Se enseña el valor ACTUAL antes de pedir el nuevo: en un menu de cofre no
     * se puede escribir dentro de una casilla, asi que sin esto el jugador
     * tendria que acordarse de lo que habia.
     */
    private void preguntar(Player j, GodItem def, String que, String actual, String pista,
                           java.util.function.Consumer<String> alResponder, Runnable alCancelar) {
        j.sendMessage(Estilo.regla());
        j.sendMessage(Estilo.legado(que));
        if (actual != null && !actual.isBlank()) {
            j.sendMessage(Estilo.legado("&7Ahora: &f" + recortar(actual, 120)));
        }
        if (pista != null) j.sendMessage(Estilo.legado(pista));
        j.sendMessage(Estilo.legado("&7Escribelo en el chat, o &fcancelar&7 para dejarlo."));
        if (Plataforma.esBedrock(j)) {
            j.sendMessage(Estilo.legado("&8Desde Bedrock: abre el chat con el boton del teclado."));
        }
        j.sendMessage(Estilo.regla());
        this.modulo.core().chat().pedir(j, alResponder, alCancelar);
    }

    /* ============================================================= ayudas */

    private GodItem idPorEnlace(String tipo, String id) {
        String x = this.modulo.registro().porEnlace(tipo, id);
        return x == null ? null : this.modulo.registro().porId(x);
    }

    private Vista copiaConPagina(Vista v, int pagina) {
        return new Vista(v.pantalla, Math.max(0, pagina), v.itemId, v.tipoMmo,
                v.activador, v.lista, v.indice);
    }

    private void paginar(Vista v, int total) {
        if (v.pagina > 0) {
            poner(v, R_ANTERIOR, adorno(new ItemStack(Material.ARROW), "&#D7F3FFAnterior", List.of()), null);
        }
        if ((v.pagina + 1) * POR_PAGINA < total) {
            poner(v, R_SIGUIENTE, adorno(new ItemStack(Material.ARROW), "&#D7F3FFSiguiente", List.of()), null);
        }
    }

    private ItemStack volver() {
        return adorno(new ItemStack(Material.BARRIER), "&#D7F3FFVolver", List.of());
    }

    private ItemStack interruptor(String nombre, boolean puesto, String explica) {
        return adorno(new ItemStack(puesto ? Material.LIME_DYE : Material.GRAY_DYE),
                (puesto ? "&#4FFF55" : "&#545454") + nombre,
                List.of("&7" + explica, "", "&#91F4FF" + Estilo.FLECHA + " "
                        + (puesto ? "Quitar" : "Poner")));
    }

    /** El icono de un GodItem: el que fabrica MMOItems si es enlazado. */
    private ItemStack iconoDe(GodItem def) {
        if (def.enlazado()) {
            ItemStack it = this.modulo.puente().construir(def.enlaceTipo(), def.enlaceId());
            if (it != null && !it.getType().isAir()) return it.clone();
            return new ItemStack(Material.STRUCTURE_VOID);
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
            for (String s : lore) l.add(Estilo.legado(s));
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
}
