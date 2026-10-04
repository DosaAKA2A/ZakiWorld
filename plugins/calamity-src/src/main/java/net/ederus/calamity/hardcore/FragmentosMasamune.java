package net.ederus.calamity.hardcore;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntPredicate;

/**
 * Los Fragmentos de Masamune, en fisico (Dosa: "me gustaria que los entregue en fisico, al que
 * recibio la sentencia"). Antes eran un credito ("masamune"); ahora son un objeto de Calamity:
 *
 *  - el objeto (ItemsCalamity.fragmentoMasamune): chatarra de netherita con la marca
 *    lethal_world:fragmento_masamune, apilable, sin recetas ni horno (ObjetosCalamity); sale
 *    ligado a su dueno (Entregas), asi que no se vende ni se cambia;
 *  - al caer Ambush, la presa que cobra se lleva uno al inventario o, si no cabe, a sus pies
 *    (Ambush.darFragmento). Si muere en Calamity lo pierde como todo lo demas: no es una Reliquia
 *    y el Eco no lo guarda;
 *  - la Forja de Vael los pide en el inventario y se los queda (altar.trueques, entregar);
 *  - los creditos "masamune" que aun tenga alguien se le dan en fisico al entrar
 *    (Entregas.convertirMasamune, con convertir de aqui) y el credito queda a cero.
 *
 * Aqui van los nombres, la conversion sin Bukkit y la prueba "fragmentos".
 */
final class FragmentosMasamune {

    /** Como se pide en /calamidad dar y en entregar de altar.trueques. */
    static final String OBJETO = "fragmento-masamune";
    /** El credito de antes, que ya no se usa (se convierte al entrar). */
    static final String CREDITO_VIEJO = "masamune";
    /** El nombre del objeto. */
    static final String NOMBRE = "Fragmento de Masamune";

    private FragmentosMasamune() {
    }

    /** "1 Fragmento de Masamune", "5 Fragmentos de Masamune". */
    static String nombre(int n) {
        return n == 1 ? "1 Fragmento de Masamune" : n + " Fragmentos de Masamune";
    }

    /**
     * Pasa a objetos los Fragmentos del credito de antes: quita el credito entero, llama a "dar" con
     * cuantos eran y, si dar falla (o revienta), se lo deja como estaba. Devuelve cuantos se dieron:
     * 0 si no tenia ninguno y -1 si no se pudieron dar.
     */
    static int convertir(Creditos cr, UUID u, IntPredicate dar) {
        if (cr == null || u == null) return 0;
        int n = cr.de(u, CREDITO_VIEJO);
        if (n <= 0) return 0;
        int caja = cr.deCaja(u, CREDITO_VIEJO);
        cr.sumar(u, CREDITO_VIEJO, -n, "conversion:objeto", false);
        boolean ok;
        try {
            ok = dar.test(n);
        } catch (Throwable t) {
            ok = false;
        }
        if (ok) return n;
        if (n - caja > 0) cr.sumar(u, CREDITO_VIEJO, n - caja, "conversion:fallo", false);
        if (caja > 0) cr.sumar(u, CREDITO_VIEJO, caja, "conversion:fallo", true);
        return -1;
    }

    /**
     * El aviso de la conversion: "Tus 3 Fragmentos de Masamune ya no se guardan en tu saldo: ahora son
     * objetos y los tienes en el inventario." donde: inventario, suelo o pendiente (dentro de Calamity).
     */
    static Component avisoConversion(int n, String donde) {
        boolean uno = n == 1;
        Component cuerpo = uno
                ? Component.text("Tu ").append(Paleta.detalle(NOMBRE))
                .append(Component.text(" ya no se guarda en tu saldo: ahora es un objeto y "))
                : Component.text("Tus ").append(Paleta.cifra(n)).append(Component.text(" "))
                .append(Paleta.detalle("Fragmentos de Masamune"))
                .append(Component.text(" ya no se guardan en tu saldo: ahora son objetos y "));
        String fin = switch (donde == null ? "" : donde) {
            case "suelo" -> uno ? "lo tienes a tus pies, porque no te cabía en el inventario."
                    : "los tienes a tus pies, porque no te cabían en el inventario.";
            case "pendiente" -> uno ? "lo recibirás cuando salgas de Calamity." : "los recibirás cuando salgas de Calamity.";
            default -> uno ? "lo tienes en el inventario." : "los tienes en el inventario.";
        };
        return ComandoCalamity.mensaje(cuerpo.append(Component.text(fin)));
    }

    // ================================================================ autotest

    /** Una mochila en memoria para el reparto: cuantos caben, y lo que entra y lo que cae. */
    private static final class MochilaPrueba implements Ambush.Mochila {
        int caben, dentro, suelo;

        MochilaPrueba(int caben) {
            this.caben = caben;
        }

        @Override
        public int meter(int n) {
            int entra = Math.min(n, caben);
            caben -= entra;
            dentro += entra;
            return n - entra;
        }

        @Override
        public void soltar(int n) {
            suelo += n;
        }
    }

    private static Altar.Resultado comprar(Altar.CajaPrueba c, Altar.Trueque t, UUID u) {
        Altar.Resultado[] r = new Altar.Resultado[1];
        Altar.comprar(c, t, u, "prueba", x -> r[0] = x);
        return r[0];
    }

    /** Los trueques de un trozo de config (hardcore.altar.trueques), por id. */
    private static Map<String, Altar.Trueque> trueques(String yml) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(yml);
        } catch (InvalidConfigurationException e) {
            return Map.of();
        }
        Map<String, Altar.Trueque> out = new HashMap<>();
        for (Altar.Trueque t : Altar.leer(y.getMapList("trueques"))) out.put(t.id(), t);
        return out;
    }

    /**
     * "fragmentos": el reparto fisico (a la presa y no a quien ayuda; con el inventario lleno, al
     * suelo), la conversion de los creditos, los dos trueques con el formato nuevo de entregar
     * (faltan, se consumen cinco, nada si faltan Esencias) y el formato viejo. Con servidor, ademas,
     * el objeto: que se crea, se reconoce aunque lo renombren, se apila y no es una Reliquia.
     */
    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();

        // ---- El reparto fisico: el Fragmento a la presa que cobra, a nadie mas.
        Ambush.Ajustes a = new Ambush.Ajustes(new YamlConfiguration());
        UUID presa = Autotest.sintetico(601), ayuda = Autotest.sintetico(602), otra = Autotest.sintetico(603);
        Map<UUID, Double> dano = new HashMap<>();
        dano.put(presa, 400.0);
        dano.put(ayuda, 300.0);
        dano.put(otra, 150.0);
        Map<UUID, MochilaPrueba> mochilas = new HashMap<>();
        Map<UUID, String> donde = new HashMap<>();
        for (Ambush.Cobro c : Ambush.repartir(a, dano, 1000, presa, 30, id -> false, id -> true)) {
            MochilaPrueba m = new MochilaPrueba(36 * 64);
            mochilas.put(c.id(), m);
            donde.put(c.id(), Ambush.darFragmento(c, m));
        }
        h.igual("la presa se lleva un Fragmento al inventario", "inventario", donde.get(presa));
        h.igual("y es uno", 1, mochilas.get(presa).dentro);
        h.ok("quien ayuda no se lleva Fragmento", donde.get(ayuda) == null && donde.get(otra) == null
                && mochilas.get(ayuda).dentro + mochilas.get(ayuda).suelo + mochilas.get(otra).dentro + mochilas.get(otra).suelo == 0);
        MochilaPrueba llena = new MochilaPrueba(0);
        Ambush.Cobro suyo = null;
        for (Ambush.Cobro c : Ambush.repartir(a, dano, 1000, presa, 30, id -> false, id -> true)) if (c.id().equals(presa)) suyo = c;
        h.igual("con el inventario lleno cae a sus pies", "suelo", Ambush.darFragmento(suyo, llena));
        h.ok("uno al suelo y ninguno dentro", llena.suelo == 1 && llena.dentro == 0);
        h.igual("la presa desconectada: la espera en los premios", "pendiente", Ambush.darFragmento(suyo, null));
        Map<UUID, Double> poco = new HashMap<>(dano);
        poco.put(presa, 100.0);
        Ambush.Cobro corta = null;
        for (Ambush.Cobro c : Ambush.repartir(a, poco, 1000, presa, 30, id -> false, id -> true)) if (c.id().equals(presa)) corta = c;
        MochilaPrueba sinNada = new MochilaPrueba(64);
        h.ok("la presa con poco daño no se lleva nada", Ambush.darFragmento(corta, sinNada) == null && sinNada.dentro == 0);
        h.igual("el mensaje de la presa", "Has vencido a Ambush: +9 Esencias y un Fragmento de Masamune.",
                sinPrefijo(Ambush.mensajeBotin(true, 9, true)));
        h.igual("el mensaje sin Esencias (la Aduana ya no paga hoy)", "Has vencido a Ambush: un Fragmento de Masamune.",
                sinPrefijo(Ambush.mensajeBotin(true, 0, true)));

        // ---- La conversion de los creditos al entrar: se dan en fisico y el credito queda a cero.
        YamlConfiguration memoria = new YamlConfiguration();
        Creditos cr = new Creditos(memoria, u -> 0.0);
        UUID u = Autotest.sintetico(611);
        cr.sumar(u, CREDITO_VIEJO, 2, "prueba", false);
        cr.sumar(u, CREDITO_VIEJO, 1, "prueba", true);
        cr.sumar(u, "marca", 4, "prueba", false);
        int[] dados = {0};
        h.igual("convierte los 3 creditos", 3, convertir(cr, u, n -> {
            dados[0] += n;
            return true;
        }));
        h.ok("se dan 3 Fragmentos y el credito queda a cero", dados[0] == 3 && cr.de(u, CREDITO_VIEJO) == 0
                && cr.deCaja(u, CREDITO_VIEJO) == 0 && !cr.todos(u).containsKey(CREDITO_VIEJO));
        h.igual("los demas creditos, intactos", 4, cr.de(u, "marca"));
        h.igual("la segunda vez no hay nada que convertir", 0, convertir(cr, u, n -> {
            dados[0] += n;
            return true;
        }));
        h.igual("y no da nada mas", 3, dados[0]);
        UUID v = Autotest.sintetico(612);
        cr.sumar(v, CREDITO_VIEJO, 2, "prueba", false);
        cr.sumar(v, CREDITO_VIEJO, 1, "prueba", true);
        h.igual("si no se pueden dar: -1", -1, convertir(cr, v, n -> false));
        h.ok("y le quedan sus 3 creditos (1 de caja)", cr.de(v, CREDITO_VIEJO) == 3 && cr.deCaja(v, CREDITO_VIEJO) == 1);
        h.igual("si dar revienta: -1", -1, convertir(cr, v, n -> {
            throw new IllegalStateException("prueba");
        }));
        h.igual("y siguen los 3", 3, cr.de(v, CREDITO_VIEJO));
        h.igual("aviso de uno, en el inventario",
                "Tu Fragmento de Masamune ya no se guarda en tu saldo: ahora es un objeto y lo tienes en el inventario.",
                sinPrefijo(avisoConversion(1, "inventario")));
        h.igual("aviso de varios, dentro de Calamity",
                "Tus 3 Fragmentos de Masamune ya no se guardan en tu saldo: ahora son objetos y los recibirás cuando salgas de Calamity.",
                sinPrefijo(avisoConversion(3, "pendiente")));

        // ---- Los trueques, con el formato nuevo de entregar. En bloque, una clave por linea, como en el
        // config.yml del SurvivalTest (el formato en linea del config.yml del jar lo mira "ambush").
        Map<String, Altar.Trueque> nuevos = trueques("""
                trueques:
                  - id: masamune
                    pagina: forja
                    icono: NETHERITE_SWORD
                    esencias: 64
                    mobcoins: 5000
                    entregar:
                      - objeto: fragmento-masamune
                        cantidad: 5
                    da: forja:masamune
                  - id: crimson-masamune
                    pagina: forja
                    icono: COPPER_SWORD
                    esencias: 96
                    mobcoins: 8000
                    entregar:
                      - objeto: masamune
                        cantidad: 1
                      - objeto: fragmento-masamune
                        cantidad: 5
                    da: forja:crimson
                """);
        Ambush.katanas(h, "formato nuevo", nuevos);
        Altar.Trueque masamune = nuevos.get("masamune"), crimson = nuevos.get("crimson-masamune");
        if (masamune != null && crimson != null) {
            h.igual("la Masamune va con la Guadaña en la Forja", "guadana", MenuAltar.grupoDe(masamune));
            Altar.CajaPrueba c = new Altar.CajaPrueba();
            UUID j = Autotest.sintetico(621);
            String frag = j + ":" + OBJETO;
            c.saldo.sumar(j, 64, "prueba");
            c.mc.put(j, 5000L);
            c.encima.put(frag, 3);
            Altar.Resultado r = comprar(c, masamune, j);
            h.ok("con 3 Fragmentos: faltan 2", "objeto".equals(r.motivo()) && !r.devuelto()
                    && r.faltan() instanceof Altar.Falta f && f.faltan() == 2 && f.tiene() == 3 && f.pide() == 5);
            h.ok("y no se consume nada", c.cuantos(j, OBJETO) == 3 && c.saldo.de(j) == 64 && c.mc(j) == 5000L
                    && c.entregados.isEmpty());
            h.igual("lo que se le dice", "Te faltan 2 Fragmentos de Masamune: llevas 3 de 5.",
                    sinPrefijo(Altar.avisoObjeto((Altar.Falta) r.faltan())));
            h.igual("la linea del menu cuando faltan", "✘ Fragmentos de Masamune: llevas 3 de 5",
                    Hardcore.plano(MenuAltar.entregaLinea(new Altar.Entrega(OBJETO, 5), 3)));
            c.encima.put(frag, 7);
            c.saldo.restar(j, 1, "prueba");
            r = comprar(c, masamune, j);
            h.ok("con 7 Fragmentos y sin Esencias: esencias", "esencias".equals(r.motivo()) && !r.devuelto());
            h.ok("y no se consume ningún Fragmento", c.cuantos(j, OBJETO) == 7 && c.mc(j) == 5000L && c.entregados.isEmpty());
            h.igual("la linea del menu cuando los lleva", "✔ 5 Fragmentos de Masamune  (los entregas)",
                    Hardcore.plano(MenuAltar.entregaLinea(new Altar.Entrega(OBJETO, 5), 7)));
            c.saldo.sumar(j, 1, "prueba");
            r = comprar(c, masamune, j);
            h.ok("con 7 Fragmentos, 64 Esencias y 5.000 MobCoins se forja", r.ok() && c.entregados.contains("forja:masamunex1"));
            h.ok("se consumen 5: le quedan 2", c.cuantos(j, OBJETO) == 2 && c.saldo.de(j) == 0 && c.mc(j) == 0L);

            // La Crimson: la Masamune y 5 Fragmentos; si la entrega falla, vuelve todo.
            c.encima.put(j + ":masamune", 1);
            c.encima.put(frag, 5);
            c.saldo.sumar(j, 96, "prueba");
            c.mc.put(j, 8000L);
            c.entregar = false;
            r = comprar(c, crimson, j);
            h.ok("Crimson con la entrega fallida: le devuelve la Masamune y los 5 Fragmentos", r.devuelto()
                    && c.cuantos(j, "masamune") == 1 && c.cuantos(j, OBJETO) == 5 && c.saldo.de(j) == 96 && c.mc(j) == 8000L);
            c.entregar = true;
            r = comprar(c, crimson, j);
            h.ok("Crimson: se forja y se queda la Masamune y los 5 Fragmentos", r.ok() && c.cuantos(j, "masamune") == 0
                    && c.cuantos(j, OBJETO) == 0 && c.saldo.de(j) == 0 && c.mc(j) == 0L && c.entregados.contains("forja:crimsonx1"));
            c.encima.put(frag, 5);
            c.saldo.sumar(j, 96, "prueba");
            c.mc.put(j, 8000L);
            r = comprar(c, crimson, j);
            h.ok("Crimson sin la Masamune: no se cobra nada", "objeto".equals(r.motivo()) && !r.devuelto()
                    && r.faltan() instanceof Altar.Falta f && "masamune".equals(f.objeto())
                    && c.cuantos(j, OBJETO) == 5 && c.saldo.de(j) == 96 && c.mc(j) == 8000L);
        } else {
            h.ok("los dos trueques del formato nuevo se leen", false);
        }

        // ---- El formato viejo: entregar: masamune (una pieza) y el credito "masamune" de antes.
        Map<String, Altar.Trueque> viejos = trueques("""
                trueques:
                  - {id: crimson-vieja, pagina: forja, icono: COPPER_SWORD, esencias: 10, entregar: masamune, da: "forja:crimson"}
                  - {id: masamune-vieja, pagina: forja, icono: NETHERITE_SWORD, esencias: 64, mobcoins: 5000, credito: "masamune", creditos: 5, da: "forja:masamune"}
                """);
        Altar.Trueque vieja = viejos.get("crimson-vieja");
        h.igual("entregar: masamune se sigue leyendo", List.of(new Altar.Entrega("masamune", 1)),
                vieja == null ? null : vieja.entregar());
        if (vieja != null) {
            Altar.CajaPrueba c = new Altar.CajaPrueba();
            UUID j = Autotest.sintetico(631);
            c.saldo.sumar(j, 10, "prueba");
            Altar.Resultado r = comprar(c, vieja, j);
            h.igual("sin la Masamune: objeto", "objeto", r.motivo());
            c.encima.put(j + ":masamune", 1);
            r = comprar(c, vieja, j);
            h.ok("con ella se forja y la entrega", r.ok() && c.cuantos(j, "masamune") == 0 && c.saldo.de(j) == 0);
        }
        Altar.Trueque credito = viejos.get("masamune-vieja");
        h.ok("el credito masamune de una config vieja pide los 5 Fragmentos en fisico", credito != null && credito.credito() == null
                && List.of(new Altar.Entrega(OBJETO, 5)).equals(credito.entregar()));
        h.igual("un objeto repetido suma", List.of(new Altar.Entrega(OBJETO, 7)),
                Altar.entregas(List.of(Map.of("objeto", OBJETO, "cantidad", 5), Map.of("objeto", OBJETO, "cantidad", 2))));
        h.igual("dar lo entiende", true, Entregas.OBJETOS.contains(OBJETO));

        // ---- El objeto (solo con servidor: sin el no se pueden crear objetos).
        if (Bukkit.getServer() != null) objeto(h);
        return h.lineas();
    }

    /** El objeto de verdad: se crea, se reconoce, se apila y no es una Reliquia (el Eco no lo guarda). */
    private static void objeto(Autotest.Hoja h) {
        ItemStack f = ItemsCalamity.fragmentoMasamune(3);
        h.igual("es chatarra de netherita", Material.NETHERITE_SCRAP, f.getType());
        h.igual("sale con la cantidad pedida", 3, f.getAmount());
        h.ok("se reconoce por su marca", ItemsCalamity.esFragmentoMasamune(f));
        ItemMeta meta = f.getItemMeta();
        h.igual("se llama Fragmento de Masamune", NOMBRE, meta == null ? null : Hardcore.plano(meta.displayName()));
        List<String> lore = new ArrayList<>();
        if (meta != null && meta.lore() != null) for (Component l : meta.lore()) lore.add(Hardcore.plano(l));
        // 1.10 (lores): la plantilla comun, con lo que pide la Forja leido de altar.trueques.
        h.igual("su lore es el de la plantilla", ItemsCalamity.fichaFragmento(Ficha.cfg()).lineas(), lore);
        h.igual("se apila hasta 64", 64, f.getMaxStackSize());
        ItemStack renombrado = f.clone();
        ItemMeta rm = renombrado.getItemMeta();
        rm.displayName(Component.text("Chatarra"));
        renombrado.setItemMeta(rm);
        h.ok("renombrado se sigue reconociendo", ItemsCalamity.esFragmentoMasamune(renombrado));
        h.ok("la chatarra de netherita normal no es un Fragmento", !ItemsCalamity.esFragmentoMasamune(new ItemStack(Material.NETHERITE_SCRAP)));
        h.ok("no es una Reliquia: ni el Eco lo guarda ni el Mercader lo compra", !Marcas.tiene(f, Marcas.RELIQUIA));
        UUID u = Autotest.sintetico(641);
        ItemStack a = Ligado.ligar(ItemsCalamity.fragmentoMasamune(1), u), b = Ligado.ligar(ItemsCalamity.fragmentoMasamune(1), u);
        h.ok("ligado sigue siendo un Fragmento", ItemsCalamity.esFragmentoMasamune(a) && u.equals(Ligado.duenoDe(a)));
        h.ok("dos ligados al mismo dueño se apilan", a.isSimilar(b));
    }

    /** El texto de un mensaje de Calamity sin su prefijo ("... » "). */
    private static String sinPrefijo(Component c) {
        String s = Hardcore.plano(c);
        String sin = Hardcore.plano(ComandoCalamity.mensaje(""));
        return s.startsWith(sin) ? s.substring(sin.length()) : s;
    }
}
