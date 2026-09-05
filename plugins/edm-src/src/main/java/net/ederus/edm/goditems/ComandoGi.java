package net.ederus.edm.goditems;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.ederus.edm.comun.Estilo;
import net.kyori.adventure.text.Component;

/**
 * `/gi give|list|info|reload|trigger`.
 *
 * `trigger` es el que abre el modulo al resto del servidor: desde
 * ConditionalEvents, DeluxeMenus o una mision se puede lanzar el
 * comportamiento de un GodItem sin que el jugador haga ningun gesto.
 */
public final class ComandoGi implements TabExecutor {

    private static final String PERMISO = "ederus.goditems";

    private final GodItemsPlugin modulo;

    public ComandoGi(GodItemsPlugin modulo) {
        this.modulo = modulo;
    }

    @Override
    public boolean onCommand(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        if (!quien.hasPermission(PERMISO)) {
            quien.sendMessage(Estilo.legado("&cNo puedes."));
            return true;
        }
        if (args.length == 0) {
            /* Sin argumentos abre la interfaz, que es lo que se espera de un
             * plugin de items. La ayuda en texto queda para la consola y para
             * quien la pida a mano. */
            if (quien instanceof Player p) {
                this.modulo.menu().raiz(p, 0);
                return true;
            }
            ayuda(quien);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give", "dar" -> dar(quien, args);
            case "list", "lista" -> lista(quien);
            case "info" -> info(quien, args);
            case "reload", "recargar" -> recargar(quien);
            case "import", "importar" -> importar(quien, args);
            case "menu", "gui" -> {
                if (quien instanceof Player p) this.modulo.menu().raiz(p, 0);
                else quien.sendMessage(Estilo.legado("&cLa interfaz solo se abre dentro del juego."));
            }
            case "particulas", "particles" -> {
                if (quien instanceof Player p) this.modulo.menu().particulas(p);
                else particulasPorConsola(quien);
            }
            case "catalogo" -> catalogo(quien);
            case "ayuda", "help" -> ayuda(quien);
            case "trigger", "disparar" -> disparar(quien, args);
            default -> ayuda(quien);
        }
        return true;
    }

    /* ---------------------------------------------------------------- give */

    private void dar(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(Estilo.legado("&7Uso: &f/gi give <item> [jugador] [cantidad]"));
            return;
        }
        GodItem def = this.modulo.registro().porId(args[1]);
        if (def == null) {
            quien.sendMessage(Estilo.legado("&cNo hay ningun GodItem que se llame &f" + args[1] + "&c."));
            return;
        }
        Player destino;
        if (args.length >= 3) {
            destino = Bukkit.getPlayerExact(args[2]);
            if (destino == null) {
                quien.sendMessage(Estilo.legado("&c" + args[2] + " no esta conectado."));
                return;
            }
        } else if (quien instanceof Player p) {
            destino = p;
        } else {
            quien.sendMessage(Estilo.legado("&cDesde la consola hay que decir a quien: &f/gi give "
                    + def.id() + " <jugador>"));
            return;
        }
        int cantidad = args.length >= 4 ? (int) Numeros.decimal(args[3], 1) : 1;

        if (def.enlazado()) {
            /* No es un fallo nuestro: ese item lo fabrica MMOItems y darlo
             * desde aqui seria fabricar una copia sin stats ni tier. */
            quien.sendMessage(Estilo.legado("&e" + def.id() + " es un GodItem ENLAZADO."
                    + " El item lo crea MMOItems: dalo con &f/mi give " + def.enlaceTipo()
                    + " " + def.enlaceId() + " &e y el comportamiento se le engancha solo."));
            return;
        }
        ItemStack item = this.modulo.fabricar(def, Math.max(1, cantidad));
        if (item == null) return;
        if (def.soloDueno()) this.modulo.ponerDueno(item, destino);
        for (ItemStack sobra : destino.getInventory().addItem(item).values()) {
            destino.getWorld().dropItemNaturally(destino.getLocation(), sobra);
        }
        quien.sendMessage(Estilo.legado("&aEntregado &f" + def.id() + " &aa &f" + destino.getName() + "&a."));
    }

    /* ---------------------------------------------------------------- list */

    private void lista(CommandSender quien) {
        if (this.modulo.registro().cuantos() == 0) {
            quien.sendMessage(Estilo.legado("&7No hay ningun GodItem cargado."
                    + " Pon un YAML en &fplugins/EDM/goditems/items/&7."));
            return;
        }
        quien.sendMessage(Estilo.cabecera("GODITEMS", this.modulo.registro().cuantos() + " cargados"));
        for (GodItem def : this.modulo.registro().todos()) {
            quien.sendMessage(Estilo.linea(def.id(),
                    (def.enlazado() ? "enlazado a " + def.enlace() : "nativo")
                            + "  ·  " + def.bloques().size() + " activadores",
                    Estilo.APAGADO));
        }
    }

    /* ---------------------------------------------------------------- info */

    private void info(CommandSender quien, String[] args) {
        GodItem def;
        if (args.length >= 2) {
            def = this.modulo.registro().porId(args[1]);
        } else if (quien instanceof Player p) {
            /* Sin argumentos, el de la mano: es lo que quiere el 90 % de las
             * veces quien escribe /gi info. */
            def = this.modulo.identidad().definicionDe(p.getInventory().getItemInMainHand());
            if (def == null) {
                String enlace = this.modulo.identidad().enlaceDe(p.getInventory().getItemInMainHand());
                quien.sendMessage(Estilo.legado(enlace == null
                        ? "&7Lo que llevas en la mano no es un GodItem."
                        : "&7Lo que llevas es &f" + enlace + " &7de MMOItems, pero ningun GodItem"
                                + " lo tiene enlazado."));
                return;
            }
        } else {
            quien.sendMessage(Estilo.legado("&7Uso: &f/gi info <item>"));
            return;
        }
        if (def == null) {
            quien.sendMessage(Estilo.legado("&cNo hay ningun GodItem que se llame &f" + args[1] + "&c."));
            return;
        }

        quien.sendMessage(Estilo.cabecera("GODITEM", def.id()));
        quien.sendMessage(Estilo.linea("Tipo",
                def.enlazado() ? "enlazado a " + def.enlace() : "nativo", Estilo.CLARO));
        if (!def.enlazado() && def.apariencia() != null) {
            quien.sendMessage(Estilo.linea("Material", def.apariencia().material().name(), Estilo.CLARO));
        }
        quien.sendMessage(Estilo.linea("Usos",
                (def.usos() < 0 ? "sin limite" : String.valueOf(def.usos()))
                        + (def.usosPorDia() < 0 ? "" : "  ·  " + def.usosPorDia() + " al dia"),
                Estilo.CLARO));
        if (!def.mundos().isEmpty()) {
            quien.sendMessage(Estilo.linea("Mundos", String.join(", ", def.mundos()), Estilo.CLARO));
        }
        if (def.exclusivo()) {
            quien.sendMessage(Estilo.nota("exclusivo: calla la habilidad del set en ese gesto"));
        }
        if (def.soloDueno()) {
            quien.sendMessage(Estilo.nota("solo responde a quien se lo dieron"));
        }
        for (var e : def.bloques().entrySet()) {
            GodItem.Bloque b = e.getValue();
            quien.sendMessage(Estilo.linea(e.getKey().name(),
                    b.pasos().size() + " acciones"
                            + (b.condiciones().isEmpty() ? "" : "  ·  " + b.condiciones().size() + " condiciones")
                            + (b.cooldown() <= 0 ? "" : "  ·  cd " + Numeros.reloj(b.cooldown()))
                            + (e.getKey().esTick() ? "  ·  cada " + Numeros.reloj(b.cada()) : ""),
                    Estilo.MARCA));
        }
    }

    /* -------------------------------------------------------------- reload */

    private void recargar(CommandSender quien) {
        String r = this.modulo.recargar();
        quien.sendMessage(Estilo.legado("&aGodItems recargado: &f" + r));
        if (!this.modulo.cargador().avisos().isEmpty()) {
            quien.sendMessage(Estilo.legado("&e" + this.modulo.cargador().avisos().size()
                    + " avisos. Miralos con &f/edm goditems avisos&e."));
        }
    }

    /* ------------------------------------------------------------ importar */

    /**
     * Trae un item de MMOItems. Sin argumentos abre el importador visual, que
     * es mas comodo que acordarse del TIPO.ID exacto entre cientos de items.
     */
    private void importar(CommandSender quien, String[] args) {
        if (!this.modulo.puente().hay()) {
            quien.sendMessage(Estilo.legado("&cMMOItems no esta instalado: no hay nada que importar."));
            return;
        }
        if (args.length < 2) {
            if (quien instanceof Player p) this.modulo.menu().importador(p, 0);
            else quien.sendMessage(Estilo.legado("&7Uso: &f/gi import <TIPO.ID>"));
            return;
        }
        String enlace = args[1];
        int punto = enlace.indexOf('.');
        if (punto <= 0 || punto == enlace.length() - 1) {
            quien.sendMessage(Estilo.legado("&cEscribelo como &fTIPO.ID&c, por ejemplo &fKATANA.FLOWING_KATANA&c."));
            return;
        }
        String tipo = enlace.substring(0, punto);
        String id = enlace.substring(punto + 1);
        if (!this.modulo.puente().existe(tipo, id)) {
            quien.sendMessage(Estilo.legado("&cMMOItems no tiene ningun &f" + tipo + "." + id + "&c."));
            return;
        }
        GodItem def = this.modulo.ficha().importar(tipo, id);
        if (def == null) {
            quien.sendMessage(Estilo.legado("&cNo se pudo crear la ficha."));
            return;
        }
        quien.sendMessage(Estilo.legado("&aImportado &f" + tipo + "." + id + " &acomo &f" + def.id() + "&a."));
        quien.sendMessage(Estilo.legado("&7Sus stats los sigue teniendo MMOItems; aqui se le pone el comportamiento."));
        if (quien instanceof Player p) this.modulo.menu().ficha(p, def.id());
    }

    /* ------------------------------------------------------------- trigger */

    private void disparar(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(Estilo.legado("&7Uso: &f/gi trigger <item> [jugador]"));
            return;
        }
        GodItem def = this.modulo.registro().porId(args[1]);
        if (def == null) {
            quien.sendMessage(Estilo.legado("&cNo hay ningun GodItem que se llame &f" + args[1] + "&c."));
            return;
        }
        Player destino = args.length >= 3 ? Bukkit.getPlayerExact(args[2])
                : (quien instanceof Player p ? p : null);
        if (destino == null) {
            quien.sendMessage(Estilo.legado("&cDi a quien: &f/gi trigger " + def.id() + " <jugador>"));
            return;
        }
        if (def.bloque(Activador.DISPARADOR) == null) {
            quien.sendMessage(Estilo.legado("&e" + def.id()
                    + " no tiene bloque DISPARADOR, asi que no hay nada que lanzar."));
            return;
        }
        boolean fue = this.modulo.disparadorManual(destino, def);
        quien.sendMessage(Estilo.legado(fue
                ? "&aLanzado &f" + def.id() + " &asobre &f" + destino.getName() + "&a."
                : "&7No se lanzo: cooldown, usos o alguna condicion lo pararon."));
    }

    /**
     * El catalogo, en numeros. Sirve para dos cosas: ver de un vistazo lo que
     * trae el modulo y, sobre todo, enterarse de si alguien añadio una accion al
     * motor y se olvido de declarar su ficha (esas no se pueden editar desde el
     * menu, solo escribiendolas a mano).
     */
    private void catalogo(CommandSender quien) {
        quien.sendMessage(Estilo.cabecera("GODITEMS", "catalogo"));
        quien.sendMessage(Estilo.linea("Acciones",
                Acciones.nombres().size() + " en el motor  ·  "
                        + Catalogo.cuantasAcciones() + " con ficha", Estilo.CLARO));
        quien.sendMessage(Estilo.linea("Condiciones",
                Condiciones.nombres().size() + " en el motor  ·  "
                        + Catalogo.cuantasCondiciones() + " con ficha", Estilo.CLARO));
        quien.sendMessage(Estilo.linea("Activadores",
                String.valueOf(Activador.values().length), Estilo.CLARO));
        quien.sendMessage(Estilo.linea("Particulas",
                Particulas.cuantas() + " en esta version", Estilo.CLARO));
        var faltan = Catalogo.sinFicha();
        if (faltan.isEmpty()) {
            quien.sendMessage(Estilo.nota("todas tienen ficha: el menu las sabe editar"));
        } else {
            quien.sendMessage(Estilo.nota("sin ficha (solo se editan a mano): "
                    + String.join(", ", faltan)));
        }
    }

    private void particulasPorConsola(CommandSender quien) {
        quien.sendMessage(Estilo.cabecera("PARTICULAS", Particulas.cuantas() + " en total"));
        for (String g : Particulas.gruposConAlgo()) {
            List<String> nombres = new ArrayList<>();
            for (Particulas.Info i : Particulas.grupo(g)) nombres.add(i.clave());
            quien.sendMessage(Estilo.linea(g, String.join(", ", nombres), Estilo.CLARO));
        }
    }

    private void ayuda(CommandSender quien) {
        quien.sendMessage(Estilo.cabecera("GODITEMS", "v1"));
        quien.sendMessage(Estilo.linea("/gi", "abre la interfaz", Estilo.CLARO));
        quien.sendMessage(Estilo.linea("/gi import [TIPO.ID]", "trae un item de MMOItems", Estilo.CLARO));
        quien.sendMessage(Estilo.linea("/gi list", "los items cargados", Estilo.CLARO));
        quien.sendMessage(Estilo.linea("/gi info [item]", "sin nombre, el de la mano", Estilo.CLARO));
        quien.sendMessage(Estilo.linea("/gi give <item> [jugador] [n]", "entrega un item nativo", Estilo.CLARO));
        quien.sendMessage(Estilo.linea("/gi trigger <item> [jugador]", "lanza su bloque DISPARADOR", Estilo.CLARO));
        quien.sendMessage(Estilo.linea("/gi particulas", "el catalogo de particulas", Estilo.CLARO));
        quien.sendMessage(Estilo.linea("/gi catalogo", "cuantas acciones y condiciones hay", Estilo.CLARO));
        quien.sendMessage(Estilo.linea("/gi reload", "vuelve a leer los YAML", Estilo.CLARO));
        quien.sendMessage(Component.empty());
        quien.sendMessage(Estilo.nota("los YAML viven en plugins/EDM/goditems/items/"));
    }

    /* ---------------------------------------------------------- tab-complete */

    @Override
    public List<String> onTabComplete(CommandSender quien, Command cmd, String etiqueta, String[] args) {
        List<String> out = new ArrayList<>();
        if (!quien.hasPermission(PERMISO)) return out;
        if (args.length == 1) {
            filtrar(out, args[0], List.of("give", "list", "info", "reload", "trigger", "import", "menu",
                    "particulas", "catalogo"));
        } else if (args.length == 2 && esDeItem(args[0])) {
            filtrar(out, args[1], this.modulo.registro().ids());
        } else if (args.length == 3 && esDeItem(args[0]) && !args[0].equalsIgnoreCase("info")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            filtrar(out, args[2], new ArrayList<>(out));
        }
        return out;
    }

    private static boolean esDeItem(String sub) {
        return sub.equalsIgnoreCase("give") || sub.equalsIgnoreCase("dar")
                || sub.equalsIgnoreCase("info")
                || sub.equalsIgnoreCase("trigger") || sub.equalsIgnoreCase("disparar");
    }

    private static void filtrar(List<String> out, String empieza, java.util.Collection<String> todos) {
        out.clear();
        String p = empieza.toLowerCase(Locale.ROOT);
        for (String s : todos) {
            if (s.toLowerCase(Locale.ROOT).startsWith(p)) out.add(s);
        }
    }
}
