package net.ederus.calamity.hardcore;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Calamity 1.14.1 · La tregua: "Si alguien vence a la Parca, esta no aparece durante 1 hora aunque se
 * queden AFK" (Dosa).
 *
 * Solo cuando la PARCA cae (Fin.VENCIDA, desde alMorir de PeleaParca y de ParcaAnomalia): sus marcados y
 * quien le hizo al menos parca.botin.participacion-minima de su vida logica (los mismos que salen en "han
 * derrotado a la Parca") pasan parca.tregua-minutos (60) sin ella. Cosechar a su presa o irse (retirada,
 * puerta, desconexion, cansada) no la dan: eso sigue dejando solo la gracia de siempre (gracia-minutos).
 *
 * Mientras dura, ninguna via la trae (todas preguntan Parca.enTregua, que es bloquea() de aqui):
 *  - la Huella no cuenta (Huella.exento): ni avisos, ni campana, ni la Grieta del spawn;
 *  - Parca.invocar no le trae una nueva ni le mete como marcado extra en la de otro, y el grupo de otra
 *    presa no le arrastra (Parca.marcaEnGrupo);
 *  - Grieta.abrir no se abre y lo pendiente de una pelea anterior no vuelve (Parca.reaparecer).
 * El staff puede saltarsela con /calamity reaper <player> [seconds]: la PARCA que fuerza llega igual, una
 * vez (saltar / gastarSalto). La abierta a mano desde /anomaly no persigue a nadie: no pasa por aqui.
 *
 * Se guarda la hora de fin en hardcore-datos (parca.tregua.<uuid>, con guardarYa al darla): sobrevive a
 * desconectarse, a salir y entrar de Calamity, a morir y a un reinicio. Nada de eso toca este mapa (lo
 * que vacia la Huella al entrar o salir es su Rastro y su graciaHasta, que son otra cosa). En memoria va
 * en mapas concurrentes porque el placeholder (%lethalworld_parca_tregua%) se puede leer desde otro hilo.
 *
 * El nucleo no toca Bukkit: el autotest "parca-tregua" lo prueba entero con UUID sinteticos y un
 * YamlConfiguration en memoria (nunca los datos reales).
 */
final class Tregua {

    /** Como acaba una PARCA para sus marcados: solo VENCIDA da tregua. */
    enum Fin { VENCIDA, COSECHA, SE_VA }

    /** Donde se guarda en hardcore-datos: parca.tregua.<uuid> = hora de fin (millis). */
    static final String RUTA = "parca.tregua";

    /**
     * A quien se le acaba fuera de Calamity (o desconectado) se le dice al volver a entrar, si es antes de
     * esto; despues se borra sin decir nada.
     */
    static final long OLVIDO_MS = 24 * 3_600_000L;

    /** Lo que se le dice a cada uno cuando se le acaba (o se la quita el staff). Sin campana: no es la Parca. */
    static final String FIN = "Terminó tu tregua: la Parca vuelve a buscarte.";

    private final Map<UUID, Long> fines = new ConcurrentHashMap<>();
    /** A quien el staff le ha forzado una PARCA en plena tregua: la siguiente llega igual. Solo en memoria. */
    private final Set<UUID> saltos = ConcurrentHashMap.newKeySet();

    // ================================================================== nucleo

    /** Si ese final da tregua: solo vencida, nunca en las de prueba (sin presa) ni con tregua-minutos en 0. */
    static boolean da(Fin como, boolean prueba, int minutos) {
        return como == Fin.VENCIDA && !prueba && minutos > 0;
    }

    /**
     * Quienes la vencieron: sus marcados al caer (la presa y los extra que siguen en la pelea, le pegaran o
     * no: eran a quienes perseguia) y quien le hizo al menos "minimo" de su vida logica, como el reparto.
     */
    static Set<UUID> quienes(Collection<UUID> marcados, Map<UUID, Double> dano, double vida, double minimo) {
        Set<UUID> out = new LinkedHashSet<>();
        if (marcados != null) for (UUID id : marcados) if (id != null) out.add(id);
        if (dano != null && vida > 0) {
            for (Map.Entry<UUID, Double> e : dano.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue() / vida >= minimo) out.add(e.getKey());
            }
        }
        return out;
    }

    /** "una hora", "2 horas", "un minuto", "90 minutos": lo que dura, para el aviso. */
    static String plazo(int minutos) {
        if (minutos == 60) return "una hora";
        if (minutos > 60 && minutos % 60 == 0) return (minutos / 60) + " horas";
        if (minutos == 1) return "un minuto";
        return minutos + " minutos";
    }

    /** Lo que queda, redondeado hacia arriba: "42 min", "1 h", "1 h 15 min". */
    static String quedan(long ms) {
        long min = Math.max(1, (Math.max(0, ms) + 59_999) / 60_000);
        if (min < 60) return min + " min";
        return (min / 60) + " h" + (min % 60 == 0 ? "" : " " + (min % 60) + " min");
    }

    /** El aviso al vencerla. */
    static String textoVencida(int minutos) {
        return "Venciste a la Parca: no volverá por ti durante " + plazo(minutos) + ".";
    }

    /** El aviso cuando el staff la pone a mano. */
    static String textoPuesta(int minutos) {
        return "La Parca no volverá por ti durante " + plazo(minutos) + ".";
    }

    /** El recordatorio al entrar en Calamity (o al conectarse dentro) con la tregua en marcha. */
    static String textoSigue(long ms) {
        return "Sigues en tregua con la Parca: quedan " + quedan(ms) + ".";
    }

    // ================================================================== estado

    /** Hora de fin (millis), o 0 si no tiene ninguna apuntada. */
    long fin(UUID id) {
        Long f = id == null ? null : fines.get(id);
        return f == null ? 0 : f;
    }

    boolean activa(UUID id, long ahora) {
        return fin(id) > ahora;
    }

    /** La puerta de todas las vias: en tregua y sin el salto del staff. */
    boolean bloquea(UUID id, long ahora) {
        return activa(id, ahora) && !saltos.contains(id);
    }

    long restante(UUID id, long ahora) {
        return Math.max(0, fin(id) - ahora);
    }

    /** Se la pone (o alarga, o acorta) hasta "fin". Un salto del staff pendiente se olvida. */
    void poner(UUID id, long fin) {
        if (id == null) return;
        fines.put(id, fin);
        saltos.remove(id);
    }

    /** Se la quita. True si tenia alguna apuntada. */
    boolean quitar(UUID id) {
        if (id == null) return false;
        saltos.remove(id);
        return fines.remove(id) != null;
    }

    /** /calamity reaper <player>: la siguiente PARCA llega aunque siga en tregua. */
    void saltar(UUID id) {
        if (id != null) saltos.add(id);
    }

    boolean salta(UUID id) {
        return id != null && saltos.contains(id);
    }

    /** La PARCA forzada ya ha llegado: la tregua vuelve a valer. True si habia salto. */
    boolean gastarSalto(UUID id) {
        return id != null && saltos.remove(id);
    }

    boolean vacia() {
        return fines.isEmpty();
    }

    int tamano() {
        return fines.size();
    }

    /**
     * Las que se han acabado y se pueden avisar ya (puedeAvisar: conectado y dentro de Calamity): salen de
     * la lista y se devuelven para decirles FIN, una vez. Las de quien no esta se quedan hasta que vuelva,
     * como mucho OLVIDO_MS; pasado eso se borran sin aviso.
     */
    List<UUID> acabadas(long ahora, Predicate<UUID> puedeAvisar) {
        List<UUID> avisar = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : fines.entrySet()) {
            long f = e.getValue();
            if (f > ahora) continue;
            UUID id = e.getKey();
            if (puedeAvisar.test(id)) {
                avisar.add(id);
            } else if (ahora - f <= OLVIDO_MS) {
                continue;
            }
            fines.remove(id);
            saltos.remove(id);
        }
        return avisar;
    }

    // ============================================================== guardado

    /** Lee parca.tregua de hardcore-datos (al arrancar). Los saltos del staff no se guardan: empiezan vacios. */
    void cargar(ConfigurationSection datos) {
        fines.clear();
        saltos.clear();
        ConfigurationSection s = datos == null ? null : datos.getConfigurationSection(RUTA);
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                long f = s.getLong(k, 0);
                if (f > 0) fines.put(UUID.fromString(k), f);
            } catch (IllegalArgumentException ignorado) {
                // Una clave escrita a mano que no es un UUID se ignora.
            }
        }
    }

    /** Escribe parca.tregua entera (son pocas): quien llama decide si marcarSucio o guardarYa. */
    void guardar(ConfigurationSection datos) {
        if (datos == null) return;
        datos.set(RUTA, null);
        for (Map.Entry<UUID, Long> e : fines.entrySet()) datos.set(RUTA + "." + e.getKey(), e.getValue());
    }

    // ============================================================== autotest

    /**
     * "parca-tregua": solo al vencer (no al cosechar ni al irse, ni en las de prueba), a quien (marcados y
     * dano minimo), que la Huella no cuenta ni avisa en toda la hora y si despues, que el grupo de otra presa
     * no le arrastra, el salto del staff, que sobrevive a guardarse y leerse (reinicio, salir y entrar), el
     * aviso de fin una sola vez y los textos.
     */
    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        Parca.Ajustes a = new Parca.Ajustes(new YamlConfiguration());
        final long t0 = 1_790_000_000_000L;
        final long minuto = 60_000L;
        UUID presa = Autotest.sintetico(1), marcado = Autotest.sintetico(2), ayudante = Autotest.sintetico(3);
        UUID flojo = Autotest.sintetico(4), ajeno = Autotest.sintetico(5);

        // 1. Solo al vencerla.
        h.igual("de serie: tregua-minutos 60", 60, a.treguaMinutos);
        h.ok("vencida -> tregua", da(Fin.VENCIDA, false, a.treguaMinutos));
        h.ok("cosecha (muere la presa) -> sin tregua", !da(Fin.COSECHA, false, a.treguaMinutos));
        h.ok("se va (puerta, desconexion, cansada, retirada) -> sin tregua", !da(Fin.SE_VA, false, a.treguaMinutos));
        h.ok("la de prueba (sin presa) -> sin tregua", !da(Fin.VENCIDA, true, a.treguaMinutos));
        h.ok("tregua-minutos 0 -> sin tregua", !da(Fin.VENCIDA, false, 0));

        // 2. A quien: sus marcados aunque no le pegaran, y quien hizo al menos participacion-minima.
        Map<UUID, Double> dano = new HashMap<>();
        dano.put(presa, 300.0);
        dano.put(ayudante, 150.0);
        dano.put(flojo, 50.0);
        Set<UUID> q = quienes(Set.of(presa, marcado), dano, 1000, a.participacionMinima);
        h.ok("presa, marcado sin dano y ayudante con 15 % -> tregua", q.containsAll(List.of(presa, marcado, ayudante)));
        h.ok("ayudante con 5 % (menos de participacion-minima) -> sin tregua", !q.contains(flojo) && q.size() == 3);
        h.ok("sin vida legible -> solo los marcados", quienes(Set.of(presa), dano, 0, a.participacionMinima).equals(Set.of(presa)));

        Tregua t = new Tregua();
        long fin = t0 + a.treguaMinutos * minuto;
        for (UUID id : q) t.poner(id, fin);

        // 3. La Huella: quieto en un bloque toda la tregua no suma nada (ni avisos ni campana: avisos()
        //    no corre), y al acabar cuenta desde cero y llega a los minutos de la Huella, no antes.
        Huella.Ajustes ha = Huella.Ajustes.defecto();
        Huella.Rastro r = new Huella.Rastro(ha.tamano());
        int maxEnTregua = 0;
        long llega = -1;
        for (int s = 1; s <= a.treguaMinutos * 60 + ha.limite() + 60; s++) {
            long ahora = t0 + s * 1000L;
            if (!t.bloquea(presa, ahora)) r.segundo(ahora, 10.5, 64, 10.5, false, false, false, ha);
            if (t.activa(presa, ahora)) maxEnTregua = Math.max(maxEnTregua, r.quieto);
            if (llega < 0 && r.quieto >= ha.limite()) llega = ahora;
        }
        // Segundos contados desde que acabo (el de fin incluido) hasta que la llamaria.
        long contados = llega < 0 ? -1 : (llega - fin) / 1000 + 1;
        h.ok("AFK toda la tregua -> la Huella no cuenta (max " + maxEnTregua + " s, primer aviso " + ha.avisos()[0] + ")",
                maxEnTregua == 0);
        h.ok("al acabar la tregua vuelve a contar desde cero: la llamaria a los " + contados + " s (limite "
                + ha.limite() + ")", contados == ha.limite());

        // 4. Las demas vias preguntan lo mismo (bloquea): invocar, marcado extra, Grieta, lo pendiente.
        h.ok("a mitad de tregua: bloquea", t.bloquea(marcado, t0 + 30 * minuto) && t.bloquea(ayudante, t0 + 30 * minuto));
        h.ok("al minuto 61: ya no", !t.bloquea(marcado, t0 + 61 * minuto));
        h.ok("quien no la vencio: nunca", !t.bloquea(flojo, t0 + minuto) && !t.bloquea(ajeno, t0 + minuto));
        h.ok("grupo de otra presa: en tregua no entra aunque lleve quieto de sobra",
                !Parca.marcaEnGrupo(a, a.quietoMarcaGrupo + 100, true) && Parca.marcaEnGrupo(a, a.quietoMarcaGrupo, false)
                        && !Parca.marcaEnGrupo(a, a.quietoMarcaGrupo - 5, false));

        // 5. El salto del staff: la PARCA forzada llega una vez y la tregua sigue.
        Tregua st = new Tregua();
        st.poner(presa, fin);
        st.saltar(presa);
        boolean conSalto = !st.bloquea(presa, t0 + minuto);
        boolean gastado = st.gastarSalto(presa);
        h.ok("reaper <player>: salta la tregua una vez y despues vuelve a valer",
                conSalto && gastado && st.bloquea(presa, t0 + minuto) && st.activa(presa, t0 + minuto));
        st.saltar(presa);
        st.poner(presa, fin + minuto);
        h.ok("poner otra tregua olvida el salto pendiente", st.bloquea(presa, t0 + minuto));

        // 6. Sobrevive a guardarse y leerse: reinicio, salir y entrar, morir (nada de eso toca este mapa).
        YamlConfiguration datos = new YamlConfiguration();
        datos.set("parca.cobro." + presa, t0);
        t.saltar(presa);
        t.guardar(datos);
        Tregua leida = new Tregua();
        try {
            YamlConfiguration disco = new YamlConfiguration();
            disco.loadFromString(datos.saveToString());
            leida.cargar(disco);
        } catch (InvalidConfigurationException ex) {
            h.ok("guardar y leer: " + ex.getMessage(), false);
        }
        h.ok("tras un reinicio sigue: misma hora de fin para los tres", leida.tamano() == 3
                && leida.fin(presa) == fin && leida.fin(marcado) == fin && leida.fin(ayudante) == fin);
        h.ok("guardar no pisa lo demas de parca", datos.getLong("parca.cobro." + presa, 0) == t0);
        h.ok("el salto del staff no se guarda", !leida.salta(presa) && leida.bloquea(presa, t0 + minuto));

        // 7. El aviso de fin: una vez, a quien esta dentro; al de fuera, al volver; pasado un dia, nada.
        Set<UUID> dentro = Set.of(presa);
        h.ok("antes de acabar no avisa a nadie", leida.acabadas(fin - 1, dentro::contains).isEmpty());
        List<UUID> primero = leida.acabadas(fin + 1000, dentro::contains);
        h.igual("al acabar, el aviso a quien esta dentro", List.of(presa), primero);
        h.ok("solo una vez", leida.acabadas(fin + 2000, dentro::contains).isEmpty() && leida.fin(presa) == 0);
        h.ok("el que estaba fuera lo guarda para cuando vuelva", leida.fin(marcado) == fin);
        h.igual("vuelve a entrar: se le avisa", List.of(marcado), leida.acabadas(fin + 3 * minuto, Set.of(marcado)::contains));
        h.ok("pasado un dia fuera: se borra sin aviso",
                leida.acabadas(fin + OLVIDO_MS + 1, x -> false).isEmpty() && leida.vacia());

        // 8. Los textos.
        h.igual("aviso al vencer", "Venciste a la Parca: no volverá por ti durante una hora.", textoVencida(60));
        h.igual("aviso de fin", "Terminó tu tregua: la Parca vuelve a buscarte.", FIN);
        h.igual("plazos", List.of("un minuto", "90 minutos", "2 horas"), List.of(plazo(1), plazo(90), plazo(120)));
        h.igual("lo que queda", List.of("1 min", "42 min", "1 h", "1 h 15 min"),
                List.of(quedan(30_000), quedan(42 * minuto), quedan(60 * minuto), quedan(75 * minuto)));
        h.igual("recordatorio al entrar", "Sigues en tregua con la Parca: quedan 42 min.", textoSigue(42 * minuto - 5_000));
        return h.lineas();
    }
}
