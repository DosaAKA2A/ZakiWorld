package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.MobCoins;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * M1 · Aduana: el unico grifo de Calamity (DIS M1, regla 7 de DIS sec. 0.2).
 *
 * Todo lo que Calamity crea de valor (Esencias, MobCoins, Reliquias de premio) sale por
 * pagar(). Tener un solo sitio es lo que permite poner premios altos sin abrir un grifo:
 * aqui se aplican los topes, se apunta cada pago en la Bitacora ("me han robado" y "cuanto
 * produce Calamity por jugador y hora") y se manda a la telemetria.
 *
 * Orden de un pago (DIS M1, valores de PLAN sec. 3.3, que mandan):
 *   1. tope diario del tipo (topes-diarios): pasado, no paga nada (P-A05);
 *   2. tramos de MobCoins del dia (tramos-mc): hasta 1.500 x1 y por encima x0 (tope duro).
 *      Cuenta todo lo que la Aduana paga en MC ese dia. Al tocar el tope, P-A01 una vez;
 *   3. Fusible por jugador: pasados 500 MC en los ultimos 60 min, lo que siga de esa hora
 *      paga x0,25 (P-A02). 500/h es ~3,3 veces la granja de spawners del Survival;
 *   4. Fusible global: si todo Calamity crea mas de 6.000 MC en una hora, aviso al staff
 *      (no corta: puede ser un evento sano);
 *   5. entrega: dentro de un mundo hardcore las Esencias son objeto; fuera, o en un pago de
 *      fuera (tasacion, contratos, hito, ranking, caja, encuesta), van al saldo, salvo que quien
 *      paga pida objeto (1.10: el contrato cumplido dentro; ver DE_FUERA). Las MC por
 *      MobCoins.pagar; desconectado, a premios-pendientes (Entregas);
 *   6. Bitacora "pago | ..." y telemetria "pago".
 *
 * Validez entre cuentas (valida/motivoInvalida): para pagos entre dos jugadores (PvP, Eco
 * ajeno, ayudantes de PARCA...). Distintos, sin huella de IP en comun en 30 dias y los dos
 * con 10 h jugadas en el servidor. La huella es sha256(sal + IP) recortado: la IP nunca se
 * guarda en claro. Si mas de la mitad de los ultimos 20 que entraron comparten huella, es un
 * proxy (todo el mundo "tiene la misma IP") y la comparacion se apaga sola.
 */
final class Aduana {

    /** Lo que de verdad se entrego en un pago, despues de tramos, topes y Fusible. */
    record Pago(int esencias, long mc, long mcNoPagadas, double recorte, boolean topado) {
    }

    /**
     * Pagos que se cobran fuera aunque el jugador siga dentro (la Tasacion se hace antes del
     * teleport de salida): sus Esencias van al saldo, nunca como objeto.
     *
     * Calamity 1.10: la unica excepcion la pide quien paga (pagar con objetoSiDentro) y es el contrato
     * cumplido DENTRO de Calamity. Se cobra en el acto y sus Esencias llegan a la mano, como las de un
     * mob: si muere antes de salir, las pierde (lo aprobo Dosa: cobras al momento, pero te lo juegas
     * hasta la puerta). Sigue siendo tipo "contratos", con el mismo tope diario y la misma Bitacora y
     * telemetria. Los contratos de Reliquias, que se cobran en la Tasacion justo antes del teleport,
     * siguen yendo al saldo: como objeto, con el inventario lleno caerian al suelo de Calamity justo
     * cuando el jugador se va.
     */
    static final Set<String> DE_FUERA = Set.of("tasacion", "contratos", "hito", "ranking", "caja", "encuesta");

    /**
     * Topes diarios de serie (DIS sec. 4, aduana.topes-diarios) por si la config del servidor
     * no los trae: sin esto, un tipo nuevo como "sangre" (Sangre fresca, WP4) no tendria tope
     * en un config.yml viejo y pagaria cordura sin limite.
     */
    static final Map<String, Integer> TOPES_DE_SERIE = Map.of("parca", 1, "eco", 5, "sangre", 12, "contratos", 3,
            "cazas", 3, "encuesta", 2, "ambush", 1);

    private static final long HORA = 3600_000L;
    private static final long DIA = 24 * HORA;

    private final Hardcore hc;
    private final SecureRandom azar = new SecureRandom();

    /** MC pagadas por todo Calamity en la ultima hora: [millis, mc]. Solo en memoria (solo avisa). */
    private final ArrayDeque<long[]> global = new ArrayDeque<>();
    private long ultimoAvisoGlobal;
    /** Ultimo P-A02 por jugador, para no repetirlo en cada mob. */
    private final Map<UUID, Long> avisoFusible = new HashMap<>();
    /**
     * Revision 1.10 · Ultimo "las Esencias han caido a tus pies" por jugador: con el inventario lleno,
     * matando mobs, una linea cada AVISO_SUELO_MS y no una por mob.
     */
    private final Map<UUID, Long> avisoSuelo = new HashMap<>();
    private static final long AVISO_SUELO_MS = 10_000;
    private String diaAvisoProxy;
    private int segundos;

    Aduana(Hardcore hc) {
        this.hc = hc;
        sal();
        podar(System.currentTimeMillis());
        Subcomandos.staff().registrar("customs",
                "customs <player> [reset]: horas, huellas, topes y MC de hoy, Fusible; reset los pone a cero",
                Subcomandos.PERMISO, this::comando, args -> args.length == 2 ? Entregas.nombresConectados()
                        : args.length == 3 ? List.of("reset") : List.of());
        Autotest.registrar("aduana", this::autotest);
    }

    private ConfigurationSection conf() {
        ConfigurationSection s = hc.cfg().getConfigurationSection("aduana");
        return s == null ? new MemoryConfiguration() : s;
    }

    /** Las cuentas sobre hardcore-datos.yml de ahora (se pide cada vez: es un envoltorio). */
    private Cuentas cuentas() {
        return new Cuentas(hc.datos());
    }

    private Calendario cal() {
        Calendario c = hc.calendario();
        return c != null ? c : new Calendario(hc);
    }

    // ------------------------------------------------------------------ para el Tasador

    /** MobCoins que la Aduana le ha pagado hoy (lo que miran los tramos). Solo lee. */
    long mcHoy(UUID u) {
        String base = "aduana.dia." + u;
        return cal().dia(System.currentTimeMillis()).equals(hc.datos().getString(base + ".dia"))
                ? hc.datos().getLong(base + ".mc", 0) : 0;
    }

    /** Los tramos de MobCoins del dia, ordenados: {hasta, factor}. Con la Aduana apagada, ninguno. */
    List<double[]> tramos() {
        return conf().getBoolean("activo", true) ? Cuentas.listaTramos(conf()) : List.of();
    }

    // ------------------------------------------------------------------ pagar

    /** Un pago con la regla de siempre: las Esencias de un tipo de DE_FUERA van al saldo (comoObjeto). */
    Pago pagar(OfflinePlayer p, String tipo, int esencias, long mobcoins, List<ItemStack> reliquias, String motivo) {
        return pagar(p, tipo, esencias, mobcoins, reliquias, motivo, false);
    }

    /**
     * El pago, con objetoSiDentro (Calamity 1.10): aunque el tipo sea de DE_FUERA, si el jugador esta
     * conectado y dentro de un mundo hardcore, sus Esencias se le dan como objeto y lo que no quepa va
     * a sus pies (Suelo.dar), como en cualquier pago de dentro. Lo pide Contratos al cobrar un contrato
     * cumplido dentro. Topes, Bitacora y telemetria no cambian: son los del tipo.
     */
    Pago pagar(OfflinePlayer p, String tipo, int esencias, long mobcoins, List<ItemStack> reliquias, String motivo,
               boolean objetoSiDentro) {
        return pagar(p, tipo, esencias, mobcoins, reliquias, motivo, objetoSiDentro, null);
    }

    /**
     * 1.11 · Con 'recoger': si el jugador esta dentro, sus Esencias (como objeto) y sus Reliquias no van
     * a su inventario sino a esa lista, para que salgan del cofre o de la boveda que las paga (Dosa abrio
     * una boveda y no vio salir nada: se le habian metido en el bolsillo). Topes y registro, los de siempre.
     */
    Pago pagar(OfflinePlayer p, String tipo, int esencias, long mobcoins, List<ItemStack> reliquias, String motivo,
               boolean objetoSiDentro, List<ItemStack> recoger) {
        if (p == null) return new Pago(0, 0, Math.max(0, mobcoins), 0, true);
        String t = tipo == null ? "" : tipo.toLowerCase(Locale.ROOT);
        int e = Math.max(0, esencias);
        long mc = Math.max(0, mobcoins);
        List<ItemStack> rel = reliquias == null ? List.of() : reliquias;
        long ahora = System.currentTimeMillis();
        UUID u = p.getUniqueId();
        String nombre = p.getName() == null ? u.toString() : p.getName();

        Resultado r = cuentas().calcular(conf(), cal(), u, t, e, mc, ahora);
        Pago pago = r.pago();
        Player online = p.getPlayer();

        if (!r.tipoTopado()) {
            // 1.12: lo que se consigue dentro lleva la entrada (la Racha solo sube con lo de esta entrada).
            if (online != null && hc.esHardcore(online)) {
                String marca = Tasacion.marca(hc, u);
                for (ItemStack it : rel) Tasacion.marcarEntrada(it, marca);
            }
            entregar(p, online, t, pago, rel, objetoSiDentro, recoger);
            if (pago.mc() > 0) vigilarGlobal(pago.mc(), ahora);
        }
        if (online != null) {
            if (r.tipoTopado()) online.sendMessage(ComandoCalamity.mensaje("Hoy ya has cobrado el máximo por esto."));
            if (r.avisoTope()) {
                online.sendMessage(ComandoCalamity.mensaje(
                        "Hoy ya has llegado al tope de MobCoins de Calamity. Las Esencias se siguen pagando."));
            }
            if (r.avisoFusible()) {
                Long antes = avisoFusible.get(u);
                if (antes == null || ahora - antes > 10 * 60_000L) {
                    avisoFusible.put(u, ahora);
                    online.sendMessage(ComandoCalamity.mensaje(
                            "Has ganado muchas MobCoins en la última hora: durante un rato pagarán menos."));
                }
            }
        }
        /* Al minuto y no al momento: los pagos de mob llegan varios por segundo y el fichero
         * entero no se escribe en cada uno. Lo que es dinero guardado por nosotros (saldo,
         * premios pendientes) ya se guarda solo al moverse; aqui solo quedan los contadores
         * de topes, y perder un minuto de contador en una caida no regala nada que importe. */
        hc.marcarSucio();

        String ids = rel.isEmpty() || r.tipoTopado() ? "-" : idsReliquias(rel);
        hc.plugin().bitacora().anotar("pago", nombre, t.isEmpty() ? "-" : t, "e " + pago.esencias(), "mc " + pago.mc(),
                "r " + ids, "recorte " + net.ederus.edm.comun.Bitacora.num(pago.recorte()),
                (motivo == null || motivo.isBlank() ? "-" : motivo)
                        + (pago.mcNoPagadas() > 0 ? " | no-pagadas " + pago.mcNoPagadas() : "")
                        + (r.tipoTopado() ? " | tope-" + t : ""));
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("tipo", t);
        campos.put("esencias", pago.esencias());
        campos.put("mc", pago.mc());
        campos.put("mc_no_pagadas", pago.mcNoPagadas());
        campos.put("recorte", pago.recorte());
        campos.put("motivo", motivo == null ? "" : motivo);
        if (!rel.isEmpty()) campos.put("reliquias", rel.size());
        Telemetria tel = hc.telemetria();
        if (tel != null) hc.seguro("telemetria", () -> tel.suceso("pago", p, campos));
        return pago;
    }

    /**
     * DIS M1 punto 5: si las Esencias de un pago van como objeto (true) o al saldo (false). Dentro de un
     * mundo hardcore, objeto, salvo los tipos de DE_FUERA, que van al saldo aunque siga dentro; y esos
     * tambien como objeto si quien paga lo pide (objetoSiDentro, 1.10: el contrato cumplido dentro). Con
     * el saldo apagado (esencias.saldo: false) vuelve lo de antes: objeto a quien este conectado. A un
     * desconectado, nunca. Sin Bukkit: lo prueba el autotest.
     */
    static boolean comoObjeto(boolean conectado, boolean dentro, String tipo, boolean objetoSiDentro, boolean saldo) {
        if (!conectado) return false;
        if (dentro && (objetoSiDentro || !DE_FUERA.contains(tipo))) return true;
        return !saldo;
    }

    /** Esencias, MobCoins y Reliquias a su sitio (DIS M1 punto 5). */
    private void entregar(OfflinePlayer p, Player online, String tipo, Pago pago, List<ItemStack> reliquias,
                          boolean objetoSiDentro, List<ItemStack> recoger) {
        UUID u = p.getUniqueId();
        int e = pago.esencias();
        boolean aMano = recoger != null && online != null && hc.esHardcore(online);
        if (e > 0 && aMano) {
            for (int quedan = e; quedan > 0; quedan -= 64) recoger.add(hc.items().esencia(Math.min(64, quedan)));
        } else if (e > 0) {
            boolean objeto = comoObjeto(online != null, online != null && hc.esHardcore(online), tipo, objetoSiDentro,
                    hc.cfg().getBoolean("esencias.saldo", true));
            if (objeto) {
                boolean suelo = false;
                for (int quedan = e; quedan > 0; quedan -= 64) {
                    suelo |= Suelo.dar(hc.plugin(), online, hc.items().esencia(Math.min(64, quedan)));
                }
                // Revision 1.10: lo que no cabe cae a sus pies (a su nombre 10 s); que lo sepa.
                if (suelo) avisarSuelo(online);
            } else {
                Saldo s = hc.saldo();
                if (s != null) s.sumar(u, e, "pago:" + tipo);
                else hc.plugin().getLogger().warning("[Calamity] Sin Saldo: se pierden " + e + " Esencias de " + u);
            }
        }
        if (pago.mc() > 0) {
            if (online != null) {
                MobCoins.pagar(hc.plugin(), online, pago.mc());
            } else {
                Entregas en = hc.entregas();
                if (en != null) en.pendienteMc(u, pago.mc(), "pago:" + tipo);
            }
        }
        /* Rama venta-oren: las Reliquias salen de Calamity y se le venden a Oren, asi que a quien esta
         * conectado se le dan, dentro o fuera. A un desconectado (un Eco cerrado a su nombre por un admin,
         * un cazador que se ha ido) no se le pueden dar: se venden en el acto a su nombre y lo que valen
         * va a saldo y premios pendientes. Perderlas no: una Lagrima valida es la unica fuente de Marcas. */
        List<ItemStack> ausente = new ArrayList<>();
        for (ItemStack it : reliquias) {
            if (it == null || it.getType().isAir()) continue;
            if (aMano) recoger.add(it);
            else if (online != null) Suelo.dar(hc.plugin(), online, it);
            else ausente.add(it);
        }
        if (ausente.isEmpty()) return;
        Tasacion ta = hc.tasacion();
        if (ta != null) {
            hc.seguro("tasacion", () -> ta.tasarAusente(p, ausente, "pago:" + tipo));
        } else {
            hc.plugin().bitacora().anotar("pago", "reliquia-perdida", u.toString(), idsReliquias(ausente));
        }
    }

    /** Los UUID de las Reliquias (III, IV y especiales) o "g<grado>x<n>" para las apilables. */
    static String idsReliquias(List<ItemStack> reliquias) {
        List<String> out = new ArrayList<>();
        for (ItemStack it : reliquias) {
            if (it == null || !it.hasItemMeta()) continue;
            ItemMeta m = it.getItemMeta();
            String id = m.getPersistentDataContainer().get(Marcas.RELIQUIA_ID, PersistentDataType.STRING);
            if (id != null) {
                out.add(id);
                continue;
            }
            Integer g = m.getPersistentDataContainer().get(Marcas.RELIQUIA, PersistentDataType.INTEGER);
            out.add("g" + (g == null ? "?" : g) + "x" + it.getAmount());
        }
        return out.isEmpty() ? "-" : String.join(",", out);
    }

    private void vigilarGlobal(long mc, long ahora) {
        global.addLast(new long[]{ahora, mc});
        while (!global.isEmpty() && global.peekFirst()[0] < ahora - HORA) global.pollFirst();
        long total = 0;
        for (long[] x : global) total += x[1];
        long limite = conf().getLong("fusible.mc-global-hora", 6000);
        if (limite <= 0 || total <= limite || ahora - ultimoAvisoGlobal < HORA) return;
        ultimoAvisoGlobal = ahora;
        hc.plugin().bitacora().anotar("aduana", "fusible-global", total + " MC en la ultima hora", "limite " + limite);
        hc.plugin().getLogger().warning("[Calamity] Fusible global: " + total + " MC en la última hora (límite " + limite + ").");
        Component aviso = Paleta.aviso("Fusible global: Calamity ha pagado " + total
                + " MobCoins en la última hora. Mira la Bitácora.");
        for (Player s : Bukkit.getOnlinePlayers()) if (s.hasPermission(Subcomandos.PERMISO)) s.sendMessage(aviso);
    }

    // ---------------------------------------------------------------- validez

    boolean valida(OfflinePlayer a, OfflinePlayer b) {
        return motivoInvalida(a, b).isEmpty();
    }

    /**
     * Por que un pago entre a y b no vale: "" si vale; "sin-jugador", "misma-cuenta",
     * "huella" (comparten IP en huella-dias) u "horas" (alguno con menos de horas-minimas
     * jugadas). Para el mensaje, avisoInvalida(motivo).
     */
    String motivoInvalida(OfflinePlayer a, OfflinePlayer b) {
        if (a == null || b == null) return "sin-jugador";
        boolean exento = exento(a) || exento(b);
        return cuentas().motivo(conf(), a.getUniqueId(), b.getUniqueId(), horasJugadas(a), horasJugadas(b), exento,
                System.currentTimeMillis());
    }

    /** P-A03 (huella o misma cuenta) o P-A04 (horas). Null si el motivo es "". */
    Component avisoInvalida(String motivo) {
        if (motivo == null || motivo.isEmpty()) return null;
        if (motivo.equals("horas")) {
            return ComandoCalamity.mensaje("Esto no paga: cada uno necesita al menos "
                    + conf().getInt("horas-minimas", 10) + " h jugadas en el servidor.");
        }
        return ComandoCalamity.mensaje("Esto no paga: el otro jugador usa tu misma conexión.");
    }

    /**
     * Exento a mano de la comparacion de huella (/calamity exempt <player> customs on).
     * Antes era el permiso lethalworld.aduana.exento, que el comodin de LuckPerms daba a todo
     * el staff. Vale tambien desconectado.
     */
    private boolean exento(OfflinePlayer o) {
        return hc.exentos() != null && hc.exentos().aduana(o.getUniqueId());
    }

    /** Horas jugadas en el servidor (Statistic.PLAY_ONE_MINUTE va en ticks: 72.000 por hora). */
    static double horasJugadas(OfflinePlayer o) {
        try {
            Player p = o.getPlayer();
            int ticks = p != null ? p.getStatistic(Statistic.PLAY_ONE_MINUTE) : o.getStatistic(Statistic.PLAY_ONE_MINUTE);
            return ticks / 72000.0;
        } catch (Throwable t) {
            // Nunca entro al servidor o no hay fichero de estadisticas: cero horas.
            return 0;
        }
    }

    /** La huella de IP de un conectado (16 hex), o "" si no se sabe su IP. */
    String huella(Player p) {
        if (p == null) return "";
        InetSocketAddress dir = p.getAddress();
        if (dir == null || dir.getAddress() == null) return "";
        return hash(sal(), dir.getAddress().getHostAddress());
    }

    /** sha256(sal + ip) en hex, recortado a 16. La IP no sale de aqui. */
    static String hash(String sal, String ip) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((sal + ip).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /** La sal de las huellas: aleatoria, se crea una vez y se queda en aduana.sal. */
    private String sal() {
        String s = hc.datos().getString("aduana.sal");
        if (s == null || s.isBlank()) {
            byte[] b = new byte[16];
            azar.nextBytes(b);
            s = HexFormat.of().formatHex(b);
            hc.datos().set("aduana.sal", s);
            hc.guardarYa();
        }
        return s;
    }

    /** Al entrar a Calamity (Hardcore.meter): apunta su huella y mira si hay proxy. */
    void alEntrar(Player p) {
        String h = huella(p);
        if (h.isEmpty()) return;
        long ahora = System.currentTimeMillis();
        cuentas().apuntarEntrada(p.getUniqueId(), h, ahora);
        hc.marcarSucio();
        String modo = conf().getString("huella-ip", "auto");
        if ("auto".equalsIgnoreCase(modo) && cuentas().proxy()) {
            String hoy = cal().dia(ahora);
            if (!hoy.equals(diaAvisoProxy)) {
                diaAvisoProxy = hoy;
                hc.plugin().bitacora().anotar("aduana", "huella desactivada: IP compartida por proxy");
                hc.plugin().getLogger().warning("[Calamity] Aduana: huella de IP desactivada (IP compartida por proxy).");
            }
        }
    }

    /** Cada segundo desde Hardcore.tick: una vez por hora poda lo viejo. */
    void tick() {
        if (++segundos < 3600) return;
        segundos = 0;
        podar(System.currentTimeMillis());
    }

    /** Huellas de mas de huella-dias, contadores de dias pasados y ventanas del Fusible vencidas. */
    private void podar(long ahora) {
        long limite = ahora - conf().getLong("huella-dias", 30) * DIA;
        ConfigurationSection hs = hc.datos().getConfigurationSection("huellas");
        boolean cambio = false;
        if (hs != null) {
            for (String u : hs.getKeys(false)) {
                ConfigurationSection de = hs.getConfigurationSection(u);
                if (de == null) continue;
                for (String h : de.getKeys(false)) {
                    if (de.getLong(h, 0) < limite) {
                        de.set(h, null);
                        cambio = true;
                    }
                }
                if (de.getKeys(false).isEmpty()) hs.set(u, null);
            }
        }
        String hoy = cal().dia(ahora);
        ConfigurationSection dias = hc.datos().getConfigurationSection("aduana.dia");
        if (dias != null) {
            for (String u : dias.getKeys(false)) {
                if (!hoy.equals(dias.getString(u + ".dia"))) {
                    dias.set(u, null);
                    cambio = true;
                }
            }
        }
        ConfigurationSection horas = hc.datos().getConfigurationSection("aduana.hora");
        if (horas != null) {
            for (String u : horas.getKeys(false)) {
                if (Cuentas.sumaHora(horas, u, ahora) == 0) {
                    horas.set(u, null);
                    cambio = true;
                }
            }
        }
        avisoFusible.values().removeIf(t -> ahora - t > HORA);
        avisoSuelo.values().removeIf(t -> ahora - t > AVISO_SUELO_MS);
        if (cambio) hc.marcarSucio();
    }

    /** "No te caben: las Esencias han caido a tus pies.", una linea y como mucho una cada AVISO_SUELO_MS. */
    private void avisarSuelo(Player p) {
        long ahora = System.currentTimeMillis();
        Long antes = avisoSuelo.get(p.getUniqueId());
        if (antes != null && ahora - antes < AVISO_SUELO_MS) return;
        avisoSuelo.put(p.getUniqueId(), ahora);
        p.sendMessage(ComandoCalamity.mensaje("No te caben: las Esencias han caído a tus pies."));
    }

    void parar() {
        global.clear();
        avisoFusible.clear();
        avisoSuelo.clear();
        Suelo.parar();
    }

    // ---------------------------------------------------------------- comando

    private void comando(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(Component.text("Uso: /calamity customs <player> [reset]", Paleta.AVISO));
            return;
        }
        OfflinePlayer o = Entregas.buscar(args[1]);
        if (o == null) {
            quien.sendMessage(Component.text("No encuentro a ese jugador.", Paleta.AVISO));
            return;
        }
        UUID u = o.getUniqueId();
        long ahora = System.currentTimeMillis();
        ConfigurationSection c = conf();
        String nombre = o.getName() == null ? args[1] : o.getName();
        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            /* Contadores de hoy (MC, Esencias, topes por tipo) y la ventana del Fusible. Para el
             * staff tras un fallo que cobro de mas, y para repetir una prueba el mismo dia. Las
             * huellas no se tocan: son la defensa contra multicuentas, no un contador. */
            hc.datos().set("aduana.dia." + u, null);
            hc.datos().set("aduana.hora." + u, null);
            avisoFusible.remove(u);
            hc.guardarYa();
            hc.plugin().bitacora().anotar("aduana", "reset", nombre, quien.getName());
            quien.sendMessage(ComandoCalamity.mensaje("Topes de hoy y Fusible de " + nombre + " puestos a cero."));
            return;
        }
        quien.sendMessage(ComandoCalamity.mensaje(Component.text("Aduana de ").append(Component.text(nombre, Paleta.DETALLE))));
        quien.sendMessage(Component.text(String.format(Locale.ROOT, "  Horas jugadas: %.1f (pide %d para pagos entre jugadores)",
                horasJugadas(o), c.getInt("horas-minimas", 10)), Paleta.TENUE));
        int huellas = cuentas().huellasDe(u, ahora - c.getLong("huella-dias", 30) * DIA).size();
        quien.sendMessage(Component.text("  Huellas de IP en " + c.getLong("huella-dias", 30) + " días: " + huellas
                + "  ·  comparación " + (cuentas().huellaActiva(c) ? "encendida" : "apagada")
                + " (" + c.getString("huella-ip", "auto") + (cuentas().proxy() ? ", proxy detectado" : "") + ")",
                Paleta.TENUE));
        String hoy = cal().dia(ahora);
        String base = "aduana.dia." + u;
        boolean esHoy = hoy.equals(hc.datos().getString(base + ".dia"));
        long mcHoy = esHoy ? hc.datos().getLong(base + ".mc", 0) : 0;
        long eHoy = esHoy ? hc.datos().getLong(base + ".esencias", 0) : 0;
        quien.sendMessage(Component.text("  Hoy (" + hoy + "): " + mcHoy + " MC y " + eHoy + " Esencias; tope de MC "
                + Cuentas.topeTramos(c), Paleta.TENUE));
        ConfigurationSection tipos = esHoy ? hc.datos().getConfigurationSection(base + ".tipos") : null;
        if (tipos != null && !tipos.getKeys(false).isEmpty()) {
            List<String> partes = new ArrayList<>();
            for (String t : tipos.getKeys(false)) {
                int tope = c.getInt("topes-diarios." + t, -1);
                partes.add(t + " " + tipos.getInt(t) + (tope >= 0 ? "/" + tope : ""));
            }
            quien.sendMessage(Component.text("  Pagos de hoy: " + String.join(", ", partes), Paleta.TENUE));
        }
        ConfigurationSection hora = hc.datos().getConfigurationSection("aduana.hora");
        long enHora = hora == null ? 0 : Cuentas.sumaHora(hora, u.toString(), ahora);
        long lim = c.getLong("fusible.mc-jugador-hora", 500);
        quien.sendMessage(Component.text("  Fusible: " + enHora + " de " + lim + " MC en la última hora"
                + (enHora >= lim ? "  ·  SALTADO (x" + c.getDouble("fusible.recorte", 0.25) + ")" : ""), Paleta.TENUE));
    }

    // ------------------------------------------------------------------ nucleo

    /** Lo que sale de un calculo: el pago y los avisos que tocan. */
    record Resultado(Pago pago, boolean tipoTopado, boolean avisoTope, boolean avisoFusible) {
    }

    /**
     * Las cuentas de la Aduana sin Bukkit alrededor: leen y escriben sobre una seccion de datos
     * (hardcore-datos.yml o un yml en memoria en los autotest) y reciben la config, el
     * calendario y la hora. Asi se prueban tramos, Fusible, topes, huellas y proxy con UUID
     * sinteticos sin tocar a nadie.
     */
    static final class Cuentas {

        private final ConfigurationSection d;

        Cuentas(ConfigurationSection datos) {
            this.d = datos;
        }

        Resultado calcular(ConfigurationSection conf, Calendario cal, UUID u, String tipo, int esencias, long mc, long ahora) {
            String base = "aduana.dia." + u;
            String dia = cal.dia(ahora);
            if (!dia.equals(d.getString(base + ".dia"))) {
                d.set(base, null);
                d.set(base + ".dia", dia);
            }
            if (!conf.getBoolean("activo", true)) {
                apuntar(base, u, tipo, esencias, mc, ahora);
                return new Resultado(new Pago(esencias, mc, 0, 1.0, false), false, false, false);
            }
            int tope = conf.getInt("topes-diarios." + tipo, TOPES_DE_SERIE.getOrDefault(tipo, -1));
            int hechos = d.getInt(base + ".tipos." + tipo, 0);
            if (!tipo.isEmpty() && tope >= 0 && hechos >= tope) {
                return new Resultado(new Pago(0, 0, mc, 0, true), true, false, false);
            }
            long yaHoy = d.getLong(base + ".mc", 0);
            double trasTramos = tramos(yaHoy, mc, listaTramos(conf));
            ConfigurationSection horas = seccion("aduana.hora");
            long enHora = sumaHora(horas, u.toString(), ahora);
            double pagado = fusible(enHora, trasTramos, conf.getLong("fusible.mc-jugador-hora", 500),
                    conf.getDouble("fusible.recorte", 0.25));
            long paga = Math.max(0, (long) Math.floor(pagado + 1e-9));
            boolean cortaTramo = trasTramos < mc - 1e-9;
            boolean cortaFusible = pagado < trasTramos - 1e-9;
            boolean avisoTope = cortaTramo && !d.getBoolean(base + ".avisado-tope", false);
            if (avisoTope) d.set(base + ".avisado-tope", true);
            apuntar(base, u, tipo, esencias, paga, ahora);
            double recorte = mc > 0 ? (double) paga / mc : 1.0;
            return new Resultado(new Pago(esencias, paga, mc - paga, recorte, cortaTramo),
                    false, avisoTope, cortaFusible);
        }

        private void apuntar(String base, UUID u, String tipo, int esencias, long paga, long ahora) {
            d.set(base + ".mc", d.getLong(base + ".mc", 0) + paga);
            d.set(base + ".esencias", d.getLong(base + ".esencias", 0) + esencias);
            if (!tipo.isEmpty()) d.set(base + ".tipos." + tipo, d.getInt(base + ".tipos." + tipo, 0) + 1);
            if (paga > 0) {
                String r = "aduana.hora." + u;
                List<String> l = new ArrayList<>(d.getStringList(r));
                l.add(ahora + ":" + paga);
                d.set(r, l);
            }
        }

        private ConfigurationSection seccion(String ruta) {
            ConfigurationSection s = d.getConfigurationSection(ruta);
            return s == null ? d.createSection(ruta) : s;
        }

        /** MC pagadas en la ultima hora (y poda lo vencido de esa lista). */
        static long sumaHora(ConfigurationSection horas, String u, long ahora) {
            List<String> l = horas.getStringList(u);
            if (l.isEmpty()) return 0;
            long total = 0;
            List<String> vivas = new ArrayList<>();
            for (String x : l) {
                int dos = x.indexOf(':');
                if (dos <= 0) continue;
                try {
                    long t = Long.parseLong(x.substring(0, dos));
                    long mc = Long.parseLong(x.substring(dos + 1));
                    if (t >= ahora - HORA) {
                        total += mc;
                        vivas.add(x);
                    }
                } catch (NumberFormatException ignorado) {
                    // Una linea rota no puede parar los pagos: se descarta.
                }
            }
            if (vivas.size() != l.size()) horas.set(u, vivas.isEmpty() ? null : vivas);
            return total;
        }

        /** Los tramos de la config, ordenados por "hasta". Sin lista: 1.500 x1 y luego nada. */
        static List<double[]> listaTramos(ConfigurationSection conf) {
            List<double[]> out = new ArrayList<>();
            for (Map<?, ?> m : conf.getMapList("tramos-mc")) {
                Object h = m.get("hasta"), f = m.get("factor");
                if (h instanceof Number hn && f instanceof Number fn) {
                    out.add(new double[]{hn.doubleValue(), Math.max(0, fn.doubleValue())});
                }
            }
            if (out.isEmpty()) {
                out.add(new double[]{1500, 1.0});
                out.add(new double[]{999999, 0.0});
            }
            out.sort((a, b) -> Double.compare(a[0], b[0]));
            return out;
        }

        /** Hasta donde paga algo (el primer "hasta" tras el que el factor es 0), para el comando. */
        static String topeTramos(ConfigurationSection conf) {
            double ultimo = 0;
            for (double[] t : listaTramos(conf)) {
                if (t[1] <= 0) return String.valueOf((long) ultimo);
                ultimo = t[0];
            }
            return "sin tope";
        }

        /**
         * Cuanto paga mc estando ya en yaHoy: cada tramo aplica su factor al trozo que cae en
         * el. Por encima del ultimo "hasta", nada (PLAN sec. 3.3: "por encima, nada").
         */
        static double tramos(long yaHoy, long mc, List<double[]> tramos) {
            double pos = yaHoy, queda = mc, out = 0;
            for (double[] t : tramos) {
                if (queda <= 0) break;
                if (pos >= t[0]) continue;
                double coge = Math.min(queda, t[0] - pos);
                out += coge * t[1];
                pos += coge;
                queda -= coge;
            }
            return out;
        }

        /** El Fusible: lo que cabe hasta el limite de la hora paga entero y el resto x recorte. */
        static double fusible(long enHora, double mc, long limite, double recorte) {
            if (limite <= 0) return mc;
            if (enHora >= limite) return mc * recorte;
            double libre = limite - enHora;
            return mc <= libre ? mc : libre + (mc - libre) * recorte;
        }

        // ---------------------------------------------------------- huellas

        void apuntarEntrada(UUID u, String hash, long ahora) {
            d.set("huellas." + u + "." + hash, ahora);
            List<String> ult = new ArrayList<>(d.getStringList("aduana.ultimos"));
            ult.removeIf(x -> x.startsWith(u + ":"));
            ult.add(u + ":" + hash);
            while (ult.size() > 20) ult.remove(0);
            d.set("aduana.ultimos", ult);
        }

        /** Mas de la mitad de los ultimos 20 distintos con la misma huella = todos por un proxy. */
        boolean proxy() {
            Map<String, Integer> cuenta = new HashMap<>();
            int max = 0;
            for (String x : d.getStringList("aduana.ultimos")) {
                int dos = x.indexOf(':');
                if (dos < 0) continue;
                int n = cuenta.merge(x.substring(dos + 1), 1, Integer::sum);
                max = Math.max(max, n);
            }
            return max * 2 > 20;
        }

        boolean huellaActiva(ConfigurationSection conf) {
            String modo = conf.getString("huella-ip", "auto");
            if ("si".equalsIgnoreCase(modo) || "true".equalsIgnoreCase(modo)) return true;
            if ("no".equalsIgnoreCase(modo) || "false".equalsIgnoreCase(modo)) return false;
            return !proxy();
        }

        List<String> huellasDe(UUID u, long desde) {
            List<String> out = new ArrayList<>();
            ConfigurationSection s = d.getConfigurationSection("huellas." + u);
            if (s == null) return out;
            for (String h : s.getKeys(false)) if (s.getLong(h, 0) >= desde) out.add(h);
            return out;
        }

        String motivo(ConfigurationSection conf, UUID a, UUID b, double horasA, double horasB, boolean exento, long ahora) {
            if (a.equals(b)) return "misma-cuenta";
            if (!exento && huellaActiva(conf)) {
                long desde = ahora - conf.getLong("huella-dias", 30) * DIA;
                List<String> ha = huellasDe(a, desde);
                for (String h : huellasDe(b, desde)) if (ha.contains(h)) return "huella";
            }
            double min = conf.getDouble("horas-minimas", 10);
            if (horasA < min || horasB < min) return "horas";
            return "";
        }
    }

    // ------------------------------------------------------------------ pruebas

    /** WP1 aceptacion 1, en memoria: tramos, Fusible, topes, validez y proxy. */
    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        probarCuentas(h);
        h.ok("la prueba no toca hardcore-datos.yml", !hc.datos().isSet("aduana.dia." + Autotest.sintetico(41))
                && !hc.datos().isSet("huellas." + Autotest.sintetico(44)));
        return h.lineas();
    }

    /** El nucleo de la prueba, sin servidor (lo usa tambien la prueba de fuera del juego). */
    static void probarCuentas(Autotest.Hoja h) {
        YamlConfiguration datos = new YamlConfiguration();
        MemoryConfiguration c = new MemoryConfiguration();
        c.set("activo", true);
        c.set("horas-minimas", 10);
        c.set("huella-ip", "auto");
        c.set("huella-dias", 30);
        c.set("topes-diarios.parca", 1);
        c.set("fusible.mc-jugador-hora", 500);
        c.set("fusible.recorte", 0.25);
        c.set("tramos-mc", List.of(Map.of("hasta", 1500, "factor", 1.0), Map.of("hasta", 999999, "factor", 0.0)));
        Calendario cal = new Calendario(ZoneId.of("Europe/Madrid"));
        long t0 = Instant.parse("2026-09-26T10:00:00Z").toEpochMilli();
        Cuentas cu = new Cuentas(datos);

        // (1) Tope duro de 1.500 al dia: con 1.400 ya pagados, 200 pagan 100.
        UUID u1 = Autotest.sintetico(41);
        datos.set("aduana.dia." + u1 + ".dia", cal.dia(t0));
        datos.set("aduana.dia." + u1 + ".mc", 1400);
        Resultado r = cu.calcular(c, cal, u1, "mob", 0, 200, t0);
        h.igual("1400 pagados + 200 -> paga 100", 100L, r.pago().mc());
        h.igual("y 100 sin pagar", 100L, r.pago().mcNoPagadas());
        h.ok("P-A01 al tocar el tope", r.avisoTope());
        h.ok("topado por el tramo", r.pago().topado());
        r = cu.calcular(c, cal, u1, "mob", 0, 50, t0 + 1000);
        h.igual("pasado el tope no paga", 0L, r.pago().mc());
        h.ok("P-A01 solo una vez al dia", !r.avisoTope());
        r = cu.calcular(c, cal, u1, "mob", 0, 200, t0 + DIA);
        h.igual("al dia siguiente paga entero", 200L, r.pago().mc());

        // (2) Fusible: tras 500 MC en menos de 60 min, lo siguiente x0,25.
        UUID u2 = Autotest.sintetico(42);
        r = cu.calcular(c, cal, u2, "mob", 0, 500, t0);
        h.igual("500 de golpe pagan 500", 500L, r.pago().mc());
        r = cu.calcular(c, cal, u2, "mob", 0, 100, t0 + 10 * 60_000L);
        h.igual("tras 500 en la hora, 100 pagan 25", 25L, r.pago().mc());
        h.cerca("recorte x0,25", 0.25, r.pago().recorte(), 1e-9);
        h.ok("P-A02 con el Fusible", r.avisoFusible());
        r = cu.calcular(c, cal, u2, "mob", 0, 100, t0 + 61 * 60_000L);
        h.igual("pasada la hora vuelve a pagar entero", 100L, r.pago().mc());
        h.cerca("fusible parcial: 400 en la hora y 200 -> 100 + 25", 125, Cuentas.fusible(400, 200, 500, 0.25), 1e-9);

        // (3) Tope diario por tipo.
        UUID u3 = Autotest.sintetico(43);
        r = cu.calcular(c, cal, u3, "parca", 5, 0, t0);
        h.igual("primer pago de parca: 5 Esencias", 5, r.pago().esencias());
        r = cu.calcular(c, cal, u3, "parca", 5, 0, t0 + 1000);
        h.ok("segundo pago de parca: topado", r.tipoTopado());
        h.igual("y no paga Esencias", 0, r.pago().esencias());
        r = cu.calcular(c, cal, u3, "mob", 1, 0, t0 + 2000);
        h.igual("otro tipo sigue pagando", 1, r.pago().esencias());

        // Sangre fresca (Combate.sangreFresca): pagos de 0 y 0 que solo cuentan el tope de 12.
        // Sin la clave en la config vale el de serie.
        UUID u4 = Autotest.sintetico(47);
        boolean doce = true;
        for (int i = 0; i < 12; i++) {
            Resultado s = cu.calcular(c, cal, u4, "sangre", 0, 0, t0 + i);
            doce &= !s.tipoTopado() && !s.pago().topado();
        }
        h.ok("sangre: 12 al dia sin tope de serie en la config", doce);
        r = cu.calcular(c, cal, u4, "sangre", 0, 0, t0 + 100);
        h.ok("sangre: la 13.a topada", r.tipoTopado() && r.pago().topado());
        h.ok("sangre: al dia siguiente vuelve", !cu.calcular(c, cal, u4, "sangre", 0, 0, t0 + DIA).tipoTopado());

        // A donde van las Esencias (punto 5). 1.10: el contrato cumplido dentro pide objeto; la Tasacion, no.
        h.ok("entrega: un mob dentro, objeto", comoObjeto(true, true, "mob", false, true));
        h.ok("entrega: la tasacion dentro, al saldo", !comoObjeto(true, true, "tasacion", false, true));
        h.ok("entrega: contratos sin pedir objeto (los de la Tasacion), al saldo", !comoObjeto(true, true, "contratos", false, true));
        h.ok("entrega: contratos pidiendo objeto y dentro (cumplido dentro), objeto", comoObjeto(true, true, "contratos", true, true));
        h.ok("entrega: pedir objeto fuera de Calamity no sirve: al saldo", !comoObjeto(true, false, "contratos", true, true));
        h.ok("entrega: a un desconectado nunca objeto", !comoObjeto(false, true, "contratos", true, true));
        h.ok("entrega: con el saldo apagado, objeto a quien este", comoObjeto(true, false, "tasacion", false, false));

        // Tramos viejos (600 x1, 1.500 x0,5, resto x0,25), por si Dosa los vuelve a poner.
        List<double[]> viejos = List.of(new double[]{600, 1}, new double[]{1500, 0.5}, new double[]{999999, 0.25});
        h.cerca("tramos viejos: 500 + 200 -> 100 + 50", 150, Cuentas.tramos(500, 200, viejos), 1e-9);
        h.cerca("tramos por defecto sin lista", 100, Cuentas.tramos(1400, 200, Cuentas.listaTramos(new MemoryConfiguration())), 1e-9);

        // Huella: 16 hex, estable, sin la IP.
        String hA = hash("sal-a", "10.0.0.7");
        h.igual("huella de 16 hex", 16, hA.length());
        h.ok("huella en hex", hA.matches("[0-9a-f]{16}"));
        h.igual("misma sal e IP, misma huella", hA, hash("sal-a", "10.0.0.7"));
        h.ok("otra sal, otra huella", !hA.equals(hash("sal-b", "10.0.0.7")));
        h.ok("la huella no lleva la IP", !hA.contains("10.0.0.7"));

        // Validez entre cuentas().
        UUID a = Autotest.sintetico(44), b = Autotest.sintetico(45), x = Autotest.sintetico(46);
        cu.apuntarEntrada(a, "aaaa000000000001", t0);
        cu.apuntarEntrada(b, "aaaa000000000001", t0);
        cu.apuntarEntrada(x, "bbbb000000000002", t0);
        h.igual("misma huella -> no vale", "huella", cu.motivo(c, a, b, 20, 20, false, t0));
        h.igual("exento a mano si vale", "", cu.motivo(c, a, b, 20, 20, true, t0));
        h.igual("menos de 10 h -> no vale", "horas", cu.motivo(c, a, x, 20, 9.9, false, t0));
        h.igual("distintos y con horas -> vale", "", cu.motivo(c, a, x, 10, 12, false, t0));
        h.igual("la misma cuenta nunca", "misma-cuenta", cu.motivo(c, a, a, 20, 20, false, t0));
        h.igual("una huella de hace 31 dias ya no cuenta", "", cu.motivo(c, a, b, 20, 20, false, t0 + 31 * DIA));

        // Proxy: 10 de 20 con la misma huella sigue encendida; 11 de 20 la apaga.
        YamlConfiguration dp = new YamlConfiguration();
        Cuentas px = new Cuentas(dp);
        for (int i = 0; i < 20; i++) {
            px.apuntarEntrada(Autotest.sintetico(100 + i), i < 10 ? "cccc000000000000" : "dddd00000000000" + (i % 10), t0);
        }
        h.ok("10 de 20 iguales: no es proxy", !px.proxy() && px.huellaActiva(c));
        px.apuntarEntrada(Autotest.sintetico(100 + 10), "cccc000000000000", t0);
        h.ok("11 de 20 iguales: proxy, comparacion apagada", px.proxy() && !px.huellaActiva(c));
        h.igual("con proxy dos de la misma huella valen", "",
                px.motivo(c, Autotest.sintetico(100), Autotest.sintetico(101), 20, 20, false, t0));
        MemoryConfiguration forzada = new MemoryConfiguration();
        forzada.set("huella-ip", "si");
        h.ok("huella-ip: si la fuerza aunque haya proxy", px.huellaActiva(forzada));
        forzada.set("huella-ip", "no");
        h.ok("huella-ip: no la apaga", !cu.huellaActiva(forzada));
        h.igual("solo se guardan los 20 ultimos", 20, dp.getStringList("aduana.ultimos").size());
    }
}
