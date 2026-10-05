package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Calamity 1.8.0 · Ambush: el samurai de los contratos de muerte.
 *
 * En la antesala, el NPC de la Sentencia (lo pone el staff con Citizens; su clic es
 * "calamity open <p> bounty", MenuSentencia) lista a quien este ahora en Calamity fuera del
 * spawn. Alguien paga el contrato de otro (ambush.contrato.esencias de su saldo) y a la presa le
 * llega un titulo, una linea en el chat y una cuenta atras en la barra: tiene SEGUNDOS para irse
 * por la puerta o con un Cristal de Regreso. Si sale de Calamity (o se desconecta, o muere) antes,
 * el contrato se consume sin mas. Si esta en la zona spawn, el minuto corre igual y Ambush la
 * espera fuera. Si se queda, Ambush (PeleaAmbush) aparece a DISTANCIA bloques.
 *
 * Reglas del contrato (motivo): la presa en Calamity y fuera del spawn, no contra uno mismo ni
 * contra quien comparte conexion, los dos con las horas minimas de la Aduana, un contrato por
 * presa cada HORAS_PRESA y como mucho CONTRATOS_DIA al dia por pagador. No hay contratos
 * pendientes: se pagan con la presa dentro o no se pagan. Quien paga no cobra nada.
 *
 * Al caer Ambush, la presa con PARTICIPACION_PRESA del dano o mas se lleva un Fragmento de
 * Masamune en fisico (ligado a ella, al inventario y, si no cabe, a sus pies; si muere antes de
 * salir, lo pierde como todo lo demas) y Esencias; quien la ayude con PARTICIPACION_AYUDA o mas,
 * Esencias. Los mismos topes y motivos que la Parca, por la Aduana (tipo "ambush"). La presa suma
 * la estadistica "ambush" (contratos vencidos) y la Sangre fresca. Con cinco Fragmentos, la Forja
 * de Vael da la Masamune; con otros cinco y la Masamune, la Crimson Masamune.
 *
 * En EDM esta registrada como anomalia Monarca (AmbushType): sale en /anomaly y se puede abrir a
 * mano como prueba, sin presa ni botin. La de un contrato no pasa por EDM: no ocupa su unico
 * hueco ni se anuncia a nadie.
 */
final class Ambush implements Listener {

    /** Lo que tiene la presa para irse. */
    static final int SEGUNDOS = 60;
    /** Un contrato por presa cada tanto. */
    static final long HORAS_PRESA = 24;
    /** Contratos que puede pagar cada uno al dia. */
    static final int CONTRATOS_DIA = 2;
    /** N = el nivel de Calamity de la presa + esto. */
    static final int EXTRA_NIVEL = 10;
    /** A cuantos bloques de la presa aparece. */
    static final double DISTANCIA = 12;
    /** Lo que dura como mucho la pelea antes de retirarse. */
    static final int MINUTOS = 8;
    static final double PARTICIPACION_PRESA = 0.25, PARTICIPACION_AYUDA = 0.10;
    static final int CADA_NIVELES_PRESA = 10, CADA_NIVELES_AYUDA = 20;
    /** Tras cobrar por un Ambush, tanto sin cobrar por otro (como la Parca). */
    static final long HORAS_ENTRE_COBROS = 24;
    /** Si en tanto no ha encontrado sitio para aparecer, el contrato se da por consumido. */
    private static final int INTENTOS = 30;
    private static final long HORA = 3_600_000L;

    /** hardcore.ambush, con los valores de serie. Se relee cada 5 s. */
    static final class Ajustes {
        final int precio;
        final double vidaBase, vidaPorNivel, golpeBase, golpePorNivel, topeGolpe, fase2;
        final int esenciasPresa, esenciasAyuda;
        final String skin1, skin2;
        /** La seccion de la que salen (con() la vuelve a leer). */
        private final ConfigurationSection fuente;
        /**
         * 1.8 · La dificultad de su presa (DificultadAmenaza): multiplica vida() y golpe() despues de
         * la formula por nivel. 1 en los de siempre; solo con() los cambia, en una copia.
         */
        private double multDano = 1, multVida = 1;

        Ajustes(ConfigurationSection s) {
            if (s == null) s = new YamlConfiguration();
            fuente = s;
            precio = Math.max(0, s.getInt("contrato.esencias", 24));
            vidaBase = s.getDouble("vida-base", 300);
            vidaPorNivel = s.getDouble("vida-por-nivel", 0.10);
            // 1.8: de 7 a 10, "extremadamente dificil, sobre todo por su dano" (Dosa).
            golpeBase = s.getDouble("golpe-base", 10);
            golpePorNivel = s.getDouble("golpe-por-nivel", 0.04);
            topeGolpe = Math.max(0, s.getDouble("tope-golpe-fraccion", 0.08));
            esenciasPresa = s.getInt("esencias-presa", 6);
            esenciasAyuda = s.getInt("esencias-ayuda", 2);
            skin1 = s.getString("skin-fase-1", "itGuts");
            skin2 = s.getString("skin-fase-2", "Nagazaki_Yakuza");
            fase2 = Math.max(0, Math.min(1, s.getDouble("fase-2-vida", 0.5)));
        }

        /** Una copia con la dificultad de la presa (NEUTRO o null: los mismos numeros). */
        Ajustes con(DificultadAmenaza.Resultado d) {
            Ajustes x = new Ajustes(fuente);
            if (d != null) {
                x.multDano = d.dano();
                x.multVida = d.vida();
            }
            return x;
        }
    }

    /** Un contrato vivo: de la cuenta atras hasta que Ambush cae o se va. Solo en memoria. */
    static final class Contrato {
        final UUID presa;
        final String presaNombre;
        /** Null si lo forzo el staff sin decir quien paga. */
        final UUID pagador;
        final String pagadorNombre;
        final long pagado;
        final boolean forzado;
        /** El proximo segundo de la cuenta atras que se ensena en la barra. */
        int marca = SEGUNDOS - 10;
        boolean avisoSpawn, avisoFuera;
        int intentos;
        PeleaAmbush pelea;
        /** 1.8 · Lo que endurece a Ambush segun estaba la presa al aparecer (DificultadAmenaza). */
        DificultadAmenaza.Resultado dificultad;

        Contrato(UUID presa, String presaNombre, UUID pagador, String pagadorNombre, long pagado, boolean forzado) {
            this.presa = presa;
            this.presaNombre = presaNombre;
            this.pagador = pagador;
            this.pagadorNombre = pagadorNombre;
            this.pagado = pagado;
            this.forzado = forzado;
        }
    }

    /** Lo que le toca a cada uno que le pego (motivo != null = no cobra). */
    record Cobro(UUID id, double fraccion, int esencias, boolean fragmento, String motivo) {
    }

    /** Que hace un contrato este segundo. */
    enum Paso { CUENTA, SALE, APARECE, ESPERA_FUERA }

    private final Hardcore hc;
    private final Map<UUID, Contrato> contratos = new LinkedHashMap<>();
    private final List<PeleaAmbush> peleas = new ArrayList<>();
    private final List<BukkitTask> tareas = new ArrayList<>();
    /** Quien acaba de recibir un golpe de Ambush a mano: su marchitamiento no entra. */
    private final Set<UUID> golpeados = new HashSet<>();
    private final MenuSentencia menu;
    private final BrilloCrimson brillo;
    /** Null si EDM no trae las clases de anomalias: entonces solo falta el registro en /anomaly. */
    private final AmbushType tipo;
    private Ajustes ajustes;
    private long ajustesLeidos;
    /**
     * 1.8 · Mientras nace el Ambush de un contrato, sus Ajustes con la dificultad de la presa:
     * PeleaAmbush toma los suyos de ajustes() al nacer y de ahi saca la vida y el golpe (vida() y
     * golpe()). Null el resto del tiempo.
     */
    private Ajustes naciendo;
    private int segundos;

    Ambush(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        this.menu = new MenuSentencia(hc, this);
        this.brillo = new BrilloCrimson(hc);
        Autotest.registrar("ambush", Ambush::autotest);
        Subcomandos.staff().registrar("ambush",
                "ambush <target> [payer] | info: fuerza un contrato de Ambush sin cobrar (pruebas) o lista los que hay",
                Subcomandos.PERMISO, this::comando, this::tab);
        this.tipo = AmbushType.crear(this);
        podar();
    }

    Hardcore hc() {
        return hc;
    }

    MenuSentencia menu() {
        return menu;
    }

    Ajustes ajustes() {
        if (naciendo != null) return naciendo;
        long ahora = System.currentTimeMillis();
        if (ajustes == null || ahora - ajustesLeidos > 5_000) {
            ajustes = new Ajustes(hc.cfg().getConfigurationSection("ambush"));
            ajustesLeidos = ahora;
        }
        return ajustes;
    }

    /** La apunta la pelea al nacer (tambien la de prueba de EDM). */
    void registrar(PeleaAmbush pe) {
        if (pe != null && !peleas.contains(pe)) peleas.add(pe);
    }

    // ================================================================ numeros

    static int nivel(int n0) {
        return Math.max(1, Math.min(100, n0 + EXTRA_NIVEL));
    }

    /**
     * La de la Parca un poco por debajo: vida-base x (1 + vida-por-nivel x (N-1)), y encima la
     * dificultad de su presa (1.8, DificultadAmenaza; x1 sin ella).
     */
    static double vida(Ajustes a, int n) {
        return a.vidaBase * (1 + a.vidaPorNivel * (n - 1)) * a.multVida;
    }

    static double golpe(Ajustes a, int n) {
        return a.golpeBase * (1 + a.golpePorNivel * (n - 1)) * a.multDano;
    }

    static int esenciasPresa(Ajustes a, int n) {
        return a.esenciasPresa + n / CADA_NIVELES_PRESA;
    }

    static int esenciasAyuda(Ajustes a, int n) {
        return a.esenciasAyuda + n / CADA_NIVELES_AYUDA;
    }

    /**
     * Por que no se puede pagar el contrato de "pagador" contra "presa", o null si se puede. Sin
     * Bukkit: el autotest lo prueba entero.
     *
     * @param aduana el motivo de Aduana.motivoInvalida entre los dos ("", "misma-cuenta", "huella", "horas")
     */
    static String motivo(UUID pagador, UUID presa, boolean presaDentro, boolean presaEnSpawn, boolean activo,
                         String aduana, double horasPagador, double horasMinimas, ConfigurationSection datos,
                         String dia, long ahora, long saldo, int precio) {
        if (pagador.equals(presa) || "misma-cuenta".equals(aduana)) return "tu-mismo";
        if (!presaDentro) return "fuera";
        if (presaEnSpawn) return "spawn";
        if (activo) return "activo";
        if ("huella".equals(aduana)) return "conexion";
        if ("horas".equals(aduana)) return horasPagador < horasMinimas ? "horas-tuyas" : "horas-presa";
        long ultimo = datos.getLong("ambush.presas." + presa, 0);
        if (ultimo > 0 && ahora - ultimo < HORAS_PRESA * HORA) return "presa-24h";
        if (datos.getInt("ambush.pagos." + dia + "." + pagador, 0) >= CONTRATOS_DIA) return "dia";
        if (saldo < precio) return "esencias";
        return null;
    }

    /** Cobra el contrato y lo apunta (la presa, 24 h; el pagador, uno mas hoy). False si no le llega. */
    static boolean cobrar(ConfigurationSection datos, Saldo saldo, UUID pagador, UUID presa, int precio, long ahora, String dia) {
        if (saldo == null || !saldo.restar(pagador, precio, "ambush:contrato")) return false;
        datos.set("ambush.presas." + presa, ahora);
        String r = "ambush.pagos." + dia + "." + pagador;
        datos.set(r, datos.getInt(r, 0) + 1);
        return true;
    }

    /**
     * Que toca este segundo. Si la presa ha salido de Calamity (puerta, Cristal, desconexion o
     * muerte) antes del minuto, se consume y no aparece nada. En la zona spawn el minuto corre
     * igual; acabado, Ambush la espera fuera hasta que salga.
     */
    static Paso paso(long pagado, long ahora, boolean conectada, boolean dentro, boolean enSpawn) {
        if (!conectada || !dentro) return Paso.SALE;
        if (ahora - pagado < SEGUNDOS * 1000L) return Paso.CUENTA;
        return enSpawn ? Paso.ESPERA_FUERA : Paso.APARECE;
    }

    /** El segundo de la cuenta atras que se ensena despues de "queda": cada 10 s, y los ultimos 10, cada uno. */
    static int siguienteMarca(int queda) {
        return queda > 10 ? (queda - 1) / 10 * 10 : queda - 1;
    }

    /**
     * El reparto, sin Bukkit. La presa: con PARTICIPACION_PRESA del dano o mas y sin haber cobrado
     * por otro Ambush en HORAS_ENTRE_COBROS, el Fragmento y sus Esencias. Quien ayuda: con
     * PARTICIPACION_AYUDA o mas, valido con la presa en la Aduana y sin cobro reciente, Esencias. En
     * orden de dano, de mas a menos.
     */
    static List<Cobro> repartir(Ajustes a, Map<UUID, Double> dano, double vida, UUID presa, int n,
                                Predicate<UUID> yaCobro, Predicate<UUID> valida) {
        List<Map.Entry<UUID, Double>> orden = new ArrayList<>(dano.entrySet());
        orden.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
        List<Cobro> out = new ArrayList<>();
        for (Map.Entry<UUID, Double> e : orden) {
            UUID id = e.getKey();
            double f = vida <= 0 ? 0 : e.getValue() / vida;
            if (id.equals(presa)) {
                String motivo = f < PARTICIPACION_PRESA ? "poco-dano" : yaCobro.test(id) ? "ya-cobro" : null;
                out.add(new Cobro(id, f, motivo == null ? esenciasPresa(a, n) : 0, motivo == null, motivo));
                continue;
            }
            String motivo = f < PARTICIPACION_AYUDA ? "poco-dano"
                    : !valida.test(id) ? "invalida"
                    : yaCobro.test(id) ? "ya-cobro" : null;
            out.add(new Cobro(id, f, motivo == null ? esenciasAyuda(a, n) : 0, false, motivo));
        }
        return out;
    }

    /** La linea de chat de quien le pego y no cobra, o null si el motivo no es de estos. */
    static String sinCobro(String motivo, boolean presa, String porQueInvalida, int horasMinimas) {
        String porQue = switch (motivo == null ? "" : motivo) {
            case "poco-dano" -> "tu daño no llegó al mínimo (" + Marco.porcentaje(presa ? PARTICIPACION_PRESA : PARTICIPACION_AYUDA)
                    + " de su vida).";
            case "ya-cobro" -> "ya cobraste por otro en las últimas " + HORAS_ENTRE_COBROS + " h.";
            case "invalida" -> "horas".equals(porQueInvalida)
                    ? "para cobrar ayudando a otro, cada uno necesita al menos " + horasMinimas + " h jugadas."
                    : "la presa usa tu misma conexión.";
            default -> null;
        };
        return porQue == null ? null : "No cobras por Ambush: " + porQue;
    }

    // ================================================================ el contrato

    private String dia() {
        Calendario c = hc.calendario();
        return c != null ? c.dia() : new Calendario(hc).dia();
    }

    /** Si hay un contrato vivo contra ese jugador. */
    boolean tieneContrato(UUID presa) {
        return contratos.containsKey(presa);
    }

    /** Contratos que ese jugador puede pagar todavia hoy. */
    int restantesHoy(UUID pagador) {
        return Math.max(0, CONTRATOS_DIA - hc.datos().getInt("ambush.pagos." + dia() + "." + pagador, 0));
    }

    /** Por que "pagador" no puede pagar ahora el contrato de "presa", o null si puede. */
    String motivo(Player pagador, OfflinePlayer presa) {
        Player p = presa.getPlayer();
        boolean dentro = p != null && p.isOnline() && hc.esHardcore(p) && hc.cuenta(p) && !p.isDead();
        boolean enSpawn = dentro && hc.enSpawn(p);
        Aduana ad = hc.aduana();
        String aduana = ad == null ? "" : hc.valor("aduana", () -> ad.motivoInvalida(pagador, presa), "");
        long saldo = hc.saldo() == null ? 0 : hc.saldo().de(pagador.getUniqueId());
        return motivo(pagador.getUniqueId(), presa.getUniqueId(), dentro, enSpawn, tieneContrato(presa.getUniqueId()), aduana,
                Aduana.horasJugadas(pagador), hc.cfg().getDouble("aduana.horas-minimas", 10), hc.datos(), dia(),
                System.currentTimeMillis(), saldo, ajustes().precio);
    }

    /** Lo que se lee de cada motivo (en el menu y en el chat). */
    String texto(String motivo, Player pagador) {
        int horas = hc.cfg().getInt("aduana.horas-minimas", 10);
        return switch (motivo == null ? "" : motivo) {
            case "tu-mismo" -> "No puedes pagar un contrato contra ti.";
            case "fuera" -> "Ya no está en Calamity.";
            case "spawn" -> "Está en el spawn: espera a que salga.";
            case "activo" -> "Ya tiene un contrato encima.";
            case "conexion" -> "Usa tu misma conexión.";
            case "horas-tuyas" -> "Necesitas " + horas + " h jugadas en el servidor.";
            case "horas-presa" -> "Aún no lleva " + horas + " h jugadas en el servidor.";
            case "presa-24h" -> "Ya tuvo un contrato hace menos de " + HORAS_PRESA + " h.";
            case "dia" -> "Hoy ya has pagado " + CONTRATOS_DIA + " contratos.";
            case "esencias" -> {
                long tiene = hc.saldo() == null || pagador == null ? 0 : hc.saldo().de(pagador.getUniqueId());
                long falta = Math.max(1, ajustes().precio - tiene);
                yield falta == 1 ? "Te falta 1 Esencia." : "Te faltan " + falta + " Esencias.";
            }
            default -> "Ahora no se puede.";
        };
    }

    /**
     * Paga el contrato de "pagador" contra "presa" (el segundo clic del menu). Revisa todo otra vez,
     * cobra, lo apunta y avisa. False si no se ha podido (y ya se le ha dicho por que).
     */
    boolean pagarContrato(Player pagador, Player presa) {
        String no = motivo(pagador, presa);
        if (no != null) {
            pagador.sendMessage(ComandoCalamity.mensaje(texto(no, pagador)));
            return false;
        }
        long ahora = System.currentTimeMillis();
        int precio = ajustes().precio;
        if (!cobrar(hc.datos(), hc.saldo(), pagador.getUniqueId(), presa.getUniqueId(), precio, ahora, dia())) {
            pagador.sendMessage(ComandoCalamity.mensaje(texto("esencias", pagador)));
            return false;
        }
        hc.guardarYa();
        Contrato c = new Contrato(presa.getUniqueId(), presa.getName(), pagador.getUniqueId(), pagador.getName(), ahora, false);
        contratos.put(c.presa, c);
        notificar(c, presa, pagador);
        hc.plugin().bitacora().anotar("ambush", "contrato", pagador.getName(), presa.getName(), "-" + precio + " E");
        return true;
    }

    /** A la presa: el titulo, un sonido grave, las dos salidas y la cuenta atras. A quien paga, que ya esta. */
    private void notificar(Contrato c, Player presa, Player pagador) {
        presa.showTitle(Paleta.titulo(Paleta.ambush("SENTENCIA"), "Alguien ha pagado por tu cabeza. Tienes 1 minuto.",
                Duration.ofMillis(300), Duration.ofMillis(3500), Duration.ofMillis(800)));
        presa.playSound(presa.getLocation(), "block.anvil.land", SoundCategory.HOSTILE, 0.9f, 0.5f);
        presa.playSound(presa.getLocation(), "entity.warden.heartbeat", SoundCategory.HOSTILE, 1.0f, 0.6f);
        presa.sendMessage(ComandoCalamity.mensaje("Usa un Cristal de Regreso o la puerta para irte. Si te quedas, Ambush vendrá por ti."));
        hc.barra().aviso(presa, textoCuenta(SEGUNDOS), 3);
        c.marca = siguienteMarca(SEGUNDOS);
        if (pagador != null) {
            pagador.sendMessage(ComandoCalamity.mensaje(Component.text("Contrato pagado. Ambush irá por ")
                    .append(Component.text(c.presaNombre, Paleta.DETALLE)).append(Component.text(" en 1 minuto."))));
        }
    }

    static Component textoCuenta(int segundos) {
        return Component.text("Ambush", Paleta.AMBUSH).append(Component.text(" llega en ", Paleta.TEXTO))
                .append(Component.text(segundos + " s", Paleta.CIFRA)).append(Component.text(".", Paleta.TEXTO));
    }

    // ================================================================ cada segundo

    /** Desde Hardcore.tick, una vez por segundo. */
    void tick() {
        segundos++;
        golpeados.clear();
        peleas.removeIf(pe -> pe.estado == PeleaAmbush.Estado.FIN);
        long ahora = System.currentTimeMillis();
        for (Contrato c : new ArrayList<>(contratos.values())) hc.seguro("ambush", () -> avanzar(c, ahora));
        // Si EDM recarga su modulo de anomalias, el catalogo nuevo no la trae: se vuelve a registrar.
        if (segundos % 60 == 0 && tipo != null) hc.seguro("ambush", tipo::revisar);
        if (segundos % 3600 == 0) hc.seguro("ambush", this::podar);
    }

    private void avanzar(Contrato c, long ahora) {
        if (c.pelea != null) {
            if (c.pelea.estado == PeleaAmbush.Estado.FIN) contratos.remove(c.presa);
            return;
        }
        Player p = hc.plugin().getServer().getPlayer(c.presa);
        boolean conectada = p != null && p.isOnline();
        boolean dentro = conectada && hc.esHardcore(p) && hc.cuenta(p) && !p.isDead();
        boolean enSpawn = dentro && hc.enSpawn(p);
        switch (paso(c.pagado, ahora, conectada, dentro, enSpawn)) {
            case SALE -> consumir(c, !conectada ? "desconexion" : p.isDead() ? "muerta" : "salio");
            case CUENTA -> cuentaAtras(c, p, ahora, enSpawn);
            case ESPERA_FUERA -> {
                if (!c.avisoFuera) {
                    c.avisoFuera = true;
                    hc.barra().aviso(p, Component.text("Ambush te espera fuera del spawn.", Paleta.AMBUSH), 3);
                }
            }
            case APARECE -> aparecer(c, p);
        }
    }

    private void cuentaAtras(Contrato c, Player p, long ahora, boolean enSpawn) {
        if (enSpawn && !c.avisoSpawn) {
            c.avisoSpawn = true;
            p.sendMessage(ComandoCalamity.mensaje("Ambush no entra en el spawn: te espera fuera."));
        }
        int queda = (int) Math.ceil((c.pagado + SEGUNDOS * 1000L - ahora) / 1000.0);
        if (queda <= 0 || queda > c.marca) return;
        hc.barra().aviso(p, textoCuenta(queda), queda > 10 ? 3 : 2);
        c.marca = siguienteMarca(queda);
    }

    /** Se acabo el minuto con la presa fuera del spawn: Ambush aparece a DISTANCIA de ella. */
    private void aparecer(Contrato c, Player p) {
        Location sitio = sitio(p);
        PeleaAmbush pe = null;
        if (sitio != null) {
            int n = nivel(nivelCalamity(p));
            // De espaldas a la presa: mirando de ella hacia donde aparece.
            float deEspaldas = PeleaAmbush.yaw(p.getLocation(), sitio);
            // 1.8: mas fuerte cuanto mas lejos, con menos cordura y mas rato dentro este la presa.
            c.dificultad = hc.valor("ambush", () -> DificultadAmenaza.para(hc, DificultadAmenaza.foto(hc, p)),
                    DificultadAmenaza.NEUTRO);
            naciendo = ajustes().con(c.dificultad);
            try {
                pe = PeleaAmbush.crear(this, c, n, sitio, deEspaldas);
            } finally {
                naciendo = null;
            }
        }
        if (pe == null) {
            if (++c.intentos >= INTENTOS) consumir(c, "sin-sitio");
            return;
        }
        c.pelea = pe;
        Location l = pe.cuerpo.getLocation();
        hc.plugin().bitacora().anotar("ambush", "llega", c.presaNombre, "N " + pe.nivel,
                l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(), c.forzado ? "forzado" : "pagado por " + c.pagadorNombre,
                c.dificultad.texto());
    }

    /** Donde aparece: a DISTANCIA por detras de la presa o, si ahi queda la zona spawn, por delante. Null si no hay. */
    private Location sitio(Player p) {
        Location s = Parca.sitioDetras(p, DISTANCIA);
        if (!hc.enSpawn(s)) return s;
        Location girado = p.getLocation();
        girado.setYaw(girado.getYaw() + 180);
        s = Parca.sitioDetras(girado, DISTANCIA);
        return hc.enSpawn(s) ? null : s;
    }

    private int nivelCalamity(Player p) {
        if (hc.plugin().mobs() == null) return Math.max(1, hc.bonusNivel(p));
        return hc.plugin().mobs().nivelCalamity(p);
    }

    /** El contrato se acaba sin Ambush (la presa se fue antes, o no hubo sitio): sin mas. */
    private void consumir(Contrato c, String motivo) {
        contratos.remove(c.presa);
        hc.plugin().bitacora().anotar("ambush", "consumido", c.presaNombre, motivo);
    }

    // ================================================================ el botin

    /** Ambush ha caido: el reparto, por la Aduana, y el Fragmento para la presa. */
    void botin(PeleaAmbush pe, Map<UUID, Double> dano, double vida, long segundosPelea) {
        StringBuilder partes = new StringBuilder();
        for (Map.Entry<UUID, Double> e : dano.entrySet()) {
            if (partes.length() > 0) partes.append(",");
            partes.append(Saldo.nombre(e.getKey())).append(":").append(Math.round(vida <= 0 ? 0 : e.getValue() / vida * 100)).append("%");
        }
        hc.plugin().bitacora().anotar("ambush", "fin", pe.presaNombre, "muerto", segundosPelea + " s", partes.toString());
        if (pe.prueba) {
            hc.plugin().bitacora().anotar("ambush", "botin", "-", "prueba", "sin botin");
            return;
        }
        Ajustes a = ajustes();
        long ahora = System.currentTimeMillis();
        OfflinePlayer presa = hc.plugin().getServer().getOfflinePlayer(pe.presa);
        Aduana ad = hc.aduana();
        List<Cobro> cobros = repartir(a, dano, vida, pe.presa, pe.nivel, id -> yaCobro(id, ahora),
                id -> ad != null && hc.valor("aduana", () -> ad.valida(hc.plugin().getServer().getOfflinePlayer(id), presa), false));
        String idPelea = pe.cuerpo == null ? String.valueOf(pe.presa) : pe.cuerpo.getUniqueId().toString();
        for (Cobro c : cobros) {
            OfflinePlayer op = hc.plugin().getServer().getOfflinePlayer(c.id());
            Player online = op.getPlayer();
            boolean esPresa = c.id().equals(pe.presa);
            // La estadistica "ambush": contratos vencidos por su presa (con su parte del dano).
            if (esPresa && c.fraccion() >= PARTICIPACION_PRESA && hc.estadisticas() != null) {
                hc.seguro("estadisticas", () -> hc.estadisticas().sumar(c.id(), "ambush", 1));
            }
            if (c.motivo() != null) {
                hc.plugin().bitacora().anotar("ambush", "botin", Saldo.nombre(c.id()), "esencias 0", "fragmento no", c.motivo());
                if (online != null) {
                    String porQue = !"invalida".equals(c.motivo()) || ad == null ? ""
                            : hc.valor("aduana", () -> ad.motivoInvalida(op, presa), "");
                    String texto = sinCobro(c.motivo(), esPresa, porQue, hc.cfg().getInt("aduana.horas-minimas", 10));
                    if (texto != null) online.sendMessage(ComandoCalamity.mensaje(texto));
                }
                continue;
            }
            Aduana.Pago pago = ad == null ? null : hc.valor("aduana",
                    () -> ad.pagar(op, "ambush", c.esencias(), 0L, List.of(), "ambush N " + pe.nivel), null);
            int pagadas = pago == null || pago.topado() ? 0 : pago.esencias();
            // El Fragmento de Masamune, en fisico: al inventario y, si no cabe, a sus pies.
            String fragmento = hc.valor("ambush", () -> darFragmento(c, online == null ? null : mochila(online)), null);
            if ("pendiente".equals(fragmento)) fragmentoPendiente(op);
            hc.datos().set("ambush.cobro." + c.id(), ahora);
            hc.plugin().bitacora().anotar("ambush", "botin", Saldo.nombre(c.id()), "esencias " + pagadas,
                    "fragmento " + (fragmento == null ? "no" : fragmento), esPresa ? "presa" : "ayudante");
            if (online == null) continue;
            Component linea = mensajeBotin(esPresa, pagadas, fragmento != null);
            if (linea != null) online.sendMessage(linea);
            if ("suelo".equals(fragmento)) online.sendMessage(ComandoCalamity.mensaje("No te cabía en el inventario: lo tienes a tus pies."));
            if (esPresa) {
                // Sangre fresca (M12): la cordura de la victoria, con su tope de la Aduana.
                Combate cb = hc.combate();
                if (cb != null) hc.seguro("combate", () -> cb.sangreFresca(online, "ambush", "ambush:" + idPelea));
            }
        }
        hc.guardarYa();
    }

    private boolean yaCobro(UUID id, long ahora) {
        long ultimo = hc.datos().getLong("ambush.cobro." + id, 0);
        return ultimo > 0 && ahora - ultimo < HORAS_ENTRE_COBROS * HORA;
    }

    /** El inventario de quien se lleva el Fragmento: en el juego, el suyo; en el autotest, uno en memoria. */
    interface Mochila {
        /** Mete n Fragmentos y devuelve cuantos no caben. */
        int meter(int n);

        /** Deja n a sus pies (solo para el unos segundos, como Suelo). */
        void soltar(int n);
    }

    /**
     * El Fragmento de Masamune de un Cobro, en fisico. Null si no le toca (solo le toca a la presa
     * que cobra); "pendiente" si no hay mochila (no esta conectada: espera en los premios); si no,
     * "inventario", o "suelo" si no le cabia y ha quedado a sus pies.
     */
    static String darFragmento(Cobro c, Mochila m) {
        if (c == null || !c.fragmento()) return null;
        if (m == null) return "pendiente";
        int sobran = m.meter(1);
        if (sobran <= 0) return "inventario";
        m.soltar(sobran);
        return "suelo";
    }

    /** La mochila de verdad: su inventario (addItem) y, lo que no quepa, a sus pies a su nombre. */
    private Mochila mochila(Player p) {
        UUID u = p.getUniqueId();
        return new Mochila() {
            @Override
            public int meter(int n) {
                int sobran = 0;
                for (ItemStack it : fragmentos(u, n)) {
                    for (ItemStack s : p.getInventory().addItem(it).values()) sobran += s.getAmount();
                }
                return sobran;
            }

            @Override
            public void soltar(int n) {
                for (ItemStack it : fragmentos(u, n)) Suelo.soltar(hc.plugin(), p, it);
            }
        };
    }

    /** n Fragmentos de Masamune ligados a u (por Entregas, que pone tambien la linea del lore). */
    private List<ItemStack> fragmentos(UUID u, int n) {
        Entregas e = hc.entregas();
        if (e != null) return e.fragmentos(u, n);
        return List.of(Ligado.ligar(ItemsCalamity.fragmentoMasamune(n), u));
    }

    /** Si la presa no esta (no deberia: si se va, Ambush se va con ella), su Fragmento la espera en los premios. */
    private void fragmentoPendiente(OfflinePlayer op) {
        Entregas e = hc.entregas();
        if (e != null) hc.seguro("entregas", () -> e.dar(null, FragmentosMasamune.OBJETO, op, 1, "ambush"));
    }

    /** "Has vencido a Ambush: +8 Esencias y un Fragmento de Masamune." / "Ambush ha caído: +3 Esencias." */
    static Component mensajeBotin(boolean presa, int esencias, boolean fragmento) {
        Component esen = Paleta.cifra("+" + esencias + (esencias == 1 ? " Esencia" : " Esencias"));
        if (presa) {
            Component cuerpo = Component.text("Has vencido a Ambush: ");
            if (esencias > 0) cuerpo = cuerpo.append(esen);
            if (fragmento) {
                cuerpo = cuerpo.append(Component.text(esencias > 0 ? " y " : "")).append(Paleta.detalle("un Fragmento de Masamune"));
            }
            return esencias <= 0 && !fragmento ? null : ComandoCalamity.mensaje(cuerpo.append(Component.text(".")));
        }
        // Si la Aduana ya no le paga hoy, ella se lo ha dicho: no se anuncia un botin de +0.
        if (esencias <= 0) return null;
        return ComandoCalamity.mensaje(Component.text("Ambush ha caído: ").append(esen).append(Component.text(".")));
    }

    /**
     * La muerte que se ve: el cuerpo de NPC muere como cualquier otro (se tumba y se deshace) y
     * durante 2 s cae ceniza donde estaba, con el lamento del wither muy bajo.
     */
    void caer(Mannequin m, Location l) {
        World w = l.getWorld();
        if (w == null) return;
        Compat.sound(w, l, "entity.wither.death", 0.35f, 1.0f);
        if (m != null && m.isValid()) {
            try {
                m.setImmovable(false);
                m.setHealth(0);
            } catch (Throwable t) {
                Fx.safeRemove(m);
            }
        }
        int[] t = {0};
        BukkitTask[] tarea = new BukkitTask[1];
        tarea[0] = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(), () -> {
            t[0] += 2;
            Compat.spawn(w, Compat.ASH, l.clone().add(0, 2.4, 0), 12, 0.9, 0.3, 0.9, 0.01);
            if (t[0] >= 40) {
                // Por si algo no le dejo morir: nunca se queda un cuerpo suelto.
                if (m != null) Fx.safeRemove(m);
                tarea[0].cancel();
                tareas.remove(tarea[0]);
            }
        }, 1L, 2L);
        tareas.add(tarea[0]);
    }

    // ================================================================ EDM (a mano)

    /**
     * La de /anomaly (AmbushEdm): sin presa ni botin, junto al punto de EDM (detras de quien este
     * encima, si hay alguien) y con el nivel de los que esten cerca (+10), o 50. Null si no sale.
     */
    PeleaAmbush prueba(Location arena) {
        Player encima = Fx.nearest(arena, 4);
        Location sitio = encima != null ? Parca.sitioDetras(encima, 6) : Fx.ground(arena.clone(), 12);
        int n0 = 0;
        for (Player p : Fx.playersNear(sitio, 64)) n0 = Math.max(n0, nivelCalamity(p));
        int n = n0 > 0 ? nivel(n0) : 50;
        Player cerca = Fx.nearest(sitio, 32);
        float deEspaldas = cerca == null ? sitio.getYaw() : PeleaAmbush.yaw(cerca.getLocation(), sitio);
        PeleaAmbush pe = PeleaAmbush.crear(this, null, n, sitio, deEspaldas);
        if (pe != null) {
            hc.plugin().bitacora().anotar("ambush", "prueba", "N " + n,
                    sitio.getBlockX() + " " + sitio.getBlockY() + " " + sitio.getBlockZ(), "anomalia a mano");
        }
        return pe;
    }

    // ================================================================ listener

    private PeleaAmbush deCuerpo(Entity e) {
        if (e == null) return null;
        for (PeleaAmbush pe : peleas) if (pe.estado != PeleaAmbush.Estado.FIN && pe.esCuerpo(e)) return pe;
        return null;
    }

    /** La pelea de un cuerpo que se ve o de una de sus sombras o clones, o null. */
    private PeleaAmbush deCascara(Entity e) {
        if (e == null) return null;
        for (PeleaAmbush pe : peleas) {
            if (pe.estado != PeleaAmbush.Estado.FIN && (pe.esCascara(e) || pe.esSombra(e) || pe.esClon(e))) return pe;
        }
        return null;
    }

    private PeleaAmbush dePresa(UUID id) {
        Contrato c = contratos.get(id);
        return c == null ? null : c.pelea;
    }

    /** Cae Ambush: su botin. Y los maniquies con marca de cascara no sueltan nada (tambien fuera de Calamity). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onMuerte(EntityDeathEvent e) {
        LivingEntity muerto = e.getEntity();
        if (muerto instanceof Mannequin && muerto.getPersistentDataContainer().has(Marcas.CASCARA, PersistentDataType.STRING)) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            return;
        }
        if (peleas.isEmpty() || !Marcas.esAmenaza(muerto)) return;
        PeleaAmbush pe = deCuerpo(muerto);
        if (pe != null) hc.seguro("ambush", pe::alMorir);
    }

    /**
     * Golpes a Ambush: desde la zona spawn no entran (esperando fuera no se le cansa a flechazos); los
     * de quien no es su presa, le hacen responderle. Y los suyos: el reloj del atasco y el brazo.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGolpe(EntityDamageByEntityEvent e) {
        if (peleas.isEmpty()) return;
        Entity victima = e.getEntity();
        Entity autor = e.getDamager();
        if (autor instanceof Projectile pr && pr.getShooter() instanceof Entity tirador) autor = tirador;
        PeleaAmbush recibe = Marcas.esAmenaza(victima) ? deCuerpo(victima) : null;
        if (recibe != null) {
            if (autor instanceof Player j) {
                if (hc.enSpawn(j)) {
                    e.setCancelled(true);
                    return;
                }
                recibe.golpeadaPor(j);
            }
            return;
        }
        if (victima instanceof Player) {
            PeleaAmbush da = deCuerpo(e.getDamager());
            if (da != null) da.haGolpeado();
        }
    }

    /** El golpe a mano de Ambush entro: el marchitamiento del esqueleto que lleva dentro no. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGolpeHecho(EntityDamageByEntityEvent e) {
        if (peleas.isEmpty() || !(e.getEntity() instanceof Player v)) return;
        if (deCuerpo(e.getDamager()) != null) golpeados.add(v.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEfecto(EntityPotionEffectEvent e) {
        if (golpeados.isEmpty() || e.getCause() != EntityPotionEffectEvent.Cause.ATTACK) return;
        PotionEffect nuevo = e.getNewEffect();
        if (nuevo == null || !PotionEffectType.WITHER.equals(nuevo.getType())) return;
        if (golpeados.remove(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    /** Dano de verdad a Ambush: el cuerpo que se ve se estremece. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDolor(EntityDamageEvent e) {
        if (peleas.isEmpty() || e.getFinalDamage() <= 0 || !Marcas.esAmenaza(e.getEntity())) return;
        PeleaAmbush pe = deCuerpo(e.getEntity());
        if (pe != null) pe.dolor();
    }

    /** Solo apunta a su objetivo de ahora (su presa, o quien le acaba de pegar). */
    @EventHandler(ignoreCancelled = true)
    public void onObjetivo(EntityTargetLivingEntityEvent e) {
        if (peleas.isEmpty() || e.getTarget() == null || !Marcas.esAmenaza(e.getEntity())) return;
        PeleaAmbush pe = deCuerpo(e.getEntity());
        if (pe != null && !pe.puedeApuntar(e.getTarget())) e.setCancelled(true);
    }

    /**
     * El cuerpo que se ve, sus sombras y sus clones no reciben dano propio: el golpe de un jugador al
     * cuerpo se le pasa al esqueleto que pelea, con el mismo autor (como hace la Parca), y el golpe
     * de un jugador a un clon de las Sombras del clan lo disipa. Sin ignoreCancelled: el listener de
     * la Parca, con la misma prioridad, puede haberlo cancelado ya sin pasarlo.
     *
     * Revision 1.10: por eso mismo un golpe desde dentro de la zona spawn llegaba aqui y disipaba el
     * clon; ahora se mira a mano con la regla de ZonaSpawn (golpeDesdeDentro). Al cuerpo no hace falta:
     * su golpe pasa por damage() y ese segundo golpe ya lo cancela ZonaSpawn.onDanoDesdeDentro.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onDanoCascara(EntityDamageEvent e) {
        if (peleas.isEmpty() || !(e.getEntity() instanceof Mannequin mq)) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.KILL) return;
        PeleaAmbush pe = deCascara(mq);
        if (pe == null) return;
        e.setCancelled(true);
        Entity causa;
        try {
            causa = e.getDamageSource().getCausingEntity();
        } catch (Throwable t) {
            causa = null;
        }
        if (!pe.esCascara(mq)) {
            if (causa instanceof Player jugador && pe.esClon(mq) && !golpeaDesdeDentro(jugador)) pe.disiparClon(mq);
            return;
        }
        if (!(causa instanceof Player p)) return;
        LivingEntity c = pe.cuerpo;
        if (c == null || !c.isValid() || c.isDead()) return;
        c.damage(e.getDamage(), p);
    }

    /** Si ese jugador pega desde dentro de la zona spawn con sin-dano-desde-dentro encendido (y se le avisa). */
    private boolean golpeaDesdeDentro(Player p) {
        ZonaSpawn z = hc.zonaSpawn();
        return z != null && hc.valor("zona-spawn", () -> z.golpeDesdeDentro(p), false);
    }

    /** La presa muere: si Ambush ya estaba, se va (el contrato se ha cumplido); si no, se consume. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMuerteJugador(PlayerDeathEvent e) {
        if (e.isCancelled()) return;
        UUID id = e.getEntity().getUniqueId();
        Contrato c = contratos.get(id);
        if (c == null) return;
        if (c.pelea != null) hc.seguro("ambush", () -> c.pelea.irse("cumplido", null));
        else consumir(c, "muerta");
    }

    /** Se descarga el mundo de una pelea: Ambush se va y se lleva todo lo suyo (clones, sombras y hojas). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDescargaMundo(WorldUnloadEvent e) {
        if (peleas.isEmpty()) return;
        for (PeleaAmbush pe : new ArrayList<>(peleas)) {
            if (pe.estado != PeleaAmbush.Estado.FIN && pe.enMundo(e.getWorld())) hc.seguro("ambush", () -> pe.irse("mundo", null));
        }
    }

    /** La presa se desconecta: el contrato se consume y, si Ambush ya estaba, se va. */
    @EventHandler
    public void onSalir(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        golpeados.remove(id);
        Contrato c = contratos.get(id);
        if (c == null) return;
        if (c.pelea != null) hc.seguro("ambush", () -> c.pelea.irse("desconexion", null));
        else consumir(c, "desconexion");
    }

    // ================================================================ comando

    /**
     * /calamity ambush <target> [payer]: un contrato sin cobrar ni contar para las reglas (para
     * probar). /calamity ambush info: los contratos vivos y como van.
     */
    private void comando(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(Component.text("Uso: /calamity ambush <target> [payer] | info", Paleta.AVISO));
            return;
        }
        if (args[1].equalsIgnoreCase("info")) {
            info(quien);
            return;
        }
        Player p = hc.plugin().getServer().getPlayerExact(args[1]);
        if (p == null) {
            quien.sendMessage(Component.text("No encuentro a " + args[1] + " conectado.", Paleta.AVISO));
            return;
        }
        if (!hc.esHardcore(p) || !hc.cuenta(p) || p.isDead()) {
            quien.sendMessage(Component.text(p.getName() + " no está jugando en Calamity.", Paleta.AVISO));
            return;
        }
        if (tieneContrato(p.getUniqueId())) {
            quien.sendMessage(Component.text(p.getName() + " ya tiene un contrato encima.", Paleta.AVISO));
            return;
        }
        Player pagador = null;
        if (args.length > 2) {
            pagador = hc.plugin().getServer().getPlayerExact(args[2]);
            if (pagador == null) {
                quien.sendMessage(Component.text("No encuentro a " + args[2] + " conectado.", Paleta.AVISO));
                return;
            }
        }
        Contrato c = new Contrato(p.getUniqueId(), p.getName(), pagador == null ? null : pagador.getUniqueId(),
                pagador == null ? null : pagador.getName(), System.currentTimeMillis(), true);
        contratos.put(c.presa, c);
        notificar(c, p, pagador);
        hc.plugin().bitacora().anotar("ambush", "contrato", "forzado", p.getName(), pagador == null ? "-" : pagador.getName(),
                quien.getName());
        decir(quien, "ambush | contrato contra " + p.getName() + " sin cobrar | Ambush llega en " + SEGUNDOS + " s");
    }

    private void info(CommandSender quien) {
        long ahora = System.currentTimeMillis();
        int n = 0;
        for (Contrato c : contratos.values()) {
            n++;
            String estado;
            if (c.pelea != null) {
                estado = c.pelea.estadoTexto();
            } else {
                long queda = (c.pagado + SEGUNDOS * 1000L - ahora + 999) / 1000;
                estado = queda > 0 ? "cuenta atrás " + queda + " s" : "espera fuera del spawn";
            }
            decir(quien, "ambush | " + c.presaNombre + " | " + (c.forzado ? "forzado" : "pagado")
                    + (c.pagadorNombre == null ? "" : " por " + c.pagadorNombre) + " | " + estado
                    + (c.pelea != null && c.dificultad != null ? " | " + c.dificultad.texto() : ""));
        }
        for (PeleaAmbush pe : peleas) {
            if (!pe.prueba || pe.estado == PeleaAmbush.Estado.FIN) continue;
            n++;
            decir(quien, "ambush | prueba de /anomaly | " + pe.estadoTexto());
        }
        if (n == 0) decir(quien, "ambush | no hay ningún contrato activo");
    }

    private List<String> tab(String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 2) {
            out.add("info");
            out.addAll(Entregas.nombresConectados());
        } else if (args.length == 3 && !args[1].equalsIgnoreCase("info")) {
            out.addAll(Entregas.nombresConectados());
        }
        return out;
    }

    private void decir(CommandSender quien, String linea) {
        quien.sendMessage(Component.text(linea, Paleta.TENUE));
    }

    // ================================================================ datos y parar

    /** Lo viejo de hardcore-datos.yml: los pagos de otros dias y los contratos y cobros de hace mas de 24 h. */
    private void podar() {
        long ahora = System.currentTimeMillis();
        String hoy = dia();
        boolean cambio = false;
        ConfigurationSection pagos = hc.datos().getConfigurationSection("ambush.pagos");
        if (pagos != null) {
            for (String d : pagos.getKeys(false)) {
                if (d.equals(hoy)) continue;
                pagos.set(d, null);
                cambio = true;
            }
        }
        for (String ruta : List.of("ambush.presas", "ambush.cobro")) {
            ConfigurationSection s = hc.datos().getConfigurationSection(ruta);
            if (s == null) continue;
            for (String k : s.getKeys(false)) {
                if (ahora - s.getLong(k, 0) < 24 * HORA) continue;
                s.set(k, null);
                cambio = true;
            }
        }
        if (cambio) hc.marcarSucio();
    }

    /** Un reinicio acaba los contratos (no hay pendientes): se retira todo lo que haya en el mundo. */
    void parar() {
        for (PeleaAmbush pe : new ArrayList<>(peleas)) hc.seguro("ambush", pe::limpiar);
        peleas.clear();
        contratos.clear();
        for (BukkitTask t : tareas) t.cancel();
        tareas.clear();
        golpeados.clear();
        if (tipo != null) tipo.parar();
        menu.parar();
        brillo.parar();
        HandlerList.unregisterAll(this);
    }

    // ================================================================ autotest

    /** El config.yml del jar (el de serie), o null si no se encuentra. */
    static YamlConfiguration configDelJar() {
        try (InputStream in = Ambush.class.getClassLoader().getResourceAsStream("config.yml")) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * "ambush", sin servidor: las reglas del contrato, lo que cobra, que salir antes del minuto no
     * trae nada, el reparto, la vida y el golpe por nivel, las katanas en la Forja, la fase 2 y que
     * cada ataque acaba de pie y sin hojas ni sombras.
     */
    static List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        Ajustes a = new Ajustes(new YamlConfiguration());

        // ---- La vida y el golpe por nivel (la formula de la Parca, algo por debajo).
        h.igual("nivel = el de la presa + 10, tope 100", List.of(11, 24, 100), List.of(nivel(1), nivel(14), nivel(95)));
        h.cerca("vida N 1 = 300", 300, vida(a, 1), 1e-9);
        h.cerca("vida N 14 = 690", 690, vida(a, 14), 1e-9);
        h.cerca("vida N 100 = 3.270", 3270, vida(a, 100), 1e-9);
        h.cerca("golpe N 14 = 15,2", 15.2, golpe(a, 14), 1e-9);
        h.cerca("golpe N 100 = 49,6", 49.6, golpe(a, 100), 1e-9);
        Parca.Ajustes pa = new Parca.Ajustes(new YamlConfiguration());
        h.ok("por debajo de la Parca en vida y golpe (N 50)", vida(a, 50) < Parca.vidaLogica(pa, 50, 0, 0)
                && golpe(a, 50) < Parca.golpe(pa, 50, 0));
        h.cerca("tope por golpe de serie 8 %", 0.08, a.topeGolpe, 1e-9);

        // ---- Las reglas del contrato.
        YamlConfiguration d = new YamlConfiguration();
        Saldo saldo = new Saldo(d);
        UUID pagador = Autotest.sintetico(501), presa = Autotest.sintetico(502), otra = Autotest.sintetico(503);
        UUID tercera = Autotest.sintetico(504), otroPagador = Autotest.sintetico(505);
        long t0 = 1_790_000_000_000L;
        String hoy = "2026-09-29", manana = "2026-09-30";
        h.igual("de serie cuesta 24 Esencias", 24, a.precio);
        h.igual("la presa no está en Calamity", "fuera",
                motivo(pagador, presa, false, false, false, "", 20, 10, d, hoy, t0, 100, 24));
        h.igual("la presa está en el spawn", "spawn",
                motivo(pagador, presa, true, true, false, "", 20, 10, d, hoy, t0, 100, 24));
        h.igual("contra uno mismo", "tu-mismo", motivo(pagador, pagador, true, false, false, "", 20, 10, d, hoy, t0, 100, 24));
        h.igual("la misma cuenta para la Aduana", "tu-mismo",
                motivo(pagador, presa, true, false, false, "misma-cuenta", 20, 10, d, hoy, t0, 100, 24));
        h.igual("con la misma conexión", "conexion",
                motivo(pagador, presa, true, false, false, "huella", 20, 10, d, hoy, t0, 100, 24));
        h.igual("sin horas el que paga", "horas-tuyas",
                motivo(pagador, presa, true, false, false, "horas", 4, 10, d, hoy, t0, 100, 24));
        h.igual("sin horas la presa", "horas-presa",
                motivo(pagador, presa, true, false, false, "horas", 20, 10, d, hoy, t0, 100, 24));
        h.igual("ya tiene uno encima", "activo", motivo(pagador, presa, true, false, true, "", 20, 10, d, hoy, t0, 100, 24));
        h.igual("sin saldo", "esencias", motivo(pagador, presa, true, false, false, "", 20, 10, d, hoy, t0, 23, 24));
        h.igual("con todo, se puede", null, motivo(pagador, presa, true, false, false, "", 20, 10, d, hoy, t0, 100, 24));
        saldo.sumar(pagador, 100, "prueba");
        h.ok("cobra el contrato", cobrar(d, saldo, pagador, presa, a.precio, t0, hoy));
        h.igual("cobra 24 Esencias del saldo del que paga", 76L, saldo.de(pagador));
        saldo.sumar(otroPagador, 100, "prueba");
        h.igual("un contrato por presa cada 24 h (otro que paga a las 23 h)", "presa-24h",
                motivo(otroPagador, presa, true, false, false, "", 20, 10, d, hoy, t0 + 23 * HORA, 100, 24));
        h.igual("a las 24 h ya se puede", null,
                motivo(otroPagador, presa, true, false, false, "", 20, 10, d, manana, t0 + 24 * HORA, 100, 24));
        h.ok("el segundo del día", cobrar(d, saldo, pagador, otra, a.precio, t0 + 1000, hoy));
        h.igual("el tercero del mismo día no", "dia",
                motivo(pagador, tercera, true, false, false, "", 20, 10, d, hoy, t0 + 2000, 100, 24));
        h.igual("al día siguiente vuelve a poder", null,
                motivo(pagador, tercera, true, false, false, "", 20, 10, d, manana, t0 + 2000, 100, 24));
        YamlConfiguration vacio = new YamlConfiguration();
        Saldo pobre = new Saldo(vacio);
        h.ok("sin saldo no cobra ni apunta nada", !cobrar(vacio, pobre, pagador, presa, a.precio, t0, hoy)
                && !vacio.isSet("ambush.presas." + presa) && !vacio.isSet("ambush.pagos." + hoy + "." + pagador));

        // ---- El minuto: si sale antes, no aparece nada.
        h.igual("a los 30 s sigue la cuenta", Paso.CUENTA, paso(t0, t0 + 30_000, true, true, false));
        h.igual("sale por la puerta o con el Cristal antes del minuto: no aparece", Paso.SALE,
                paso(t0, t0 + 30_000, true, false, false));
        h.igual("se desconecta antes del minuto: no aparece", Paso.SALE, paso(t0, t0 + 59_000, false, false, false));
        h.igual("en el spawn el minuto corre igual", Paso.CUENTA, paso(t0, t0 + 59_000, true, true, true));
        h.igual("acabado el minuto en el spawn, le espera fuera", Paso.ESPERA_FUERA, paso(t0, t0 + 60_000, true, true, true));
        h.igual("acabado el minuto fuera del spawn, aparece", Paso.APARECE, paso(t0, t0 + 60_000, true, true, false));
        List<Integer> marcas = new ArrayList<>();
        for (int m = siguienteMarca(60); m > 0; m = siguienteMarca(m)) marcas.add(m);
        h.igual("la cuenta atrás: cada 10 s y los 10 últimos, cada uno",
                List.of(50, 40, 30, 20, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1), marcas);

        // ---- El reparto: el Fragmento solo para la presa con el 25 % o mas.
        UUID p0 = Autotest.sintetico(531), a1 = Autotest.sintetico(532), flojo = Autotest.sintetico(533);
        UUID alt = Autotest.sintetico(534), repe = Autotest.sintetico(535);
        Map<UUID, Double> dano = new HashMap<>();
        dano.put(p0, 300.0);
        dano.put(a1, 150.0);
        dano.put(flojo, 50.0);
        dano.put(alt, 200.0);
        dano.put(repe, 120.0);
        Map<UUID, Cobro> por = new HashMap<>();
        for (Cobro c : repartir(a, dano, 1000, p0, 34, id -> id.equals(repe), id -> !id.equals(alt))) por.put(c.id(), c);
        h.ok("la presa con el 30 %: Fragmento y 6 + 3 = 9 Esencias", por.get(p0).motivo() == null && por.get(p0).fragmento()
                && por.get(p0).esencias() == 9);
        h.ok("quien ayuda con el 15 %: 2 + 1 = 3 Esencias y sin Fragmento", por.get(a1).motivo() == null
                && !por.get(a1).fragmento() && por.get(a1).esencias() == 3);
        h.igual("quien ayuda con el 5 % no cobra", "poco-dano", por.get(flojo).motivo());
        h.igual("con la misma conexión que la presa no cobra", "invalida", por.get(alt).motivo());
        h.igual("quien ya cobró en 24 h no cobra", "ya-cobro", por.get(repe).motivo());
        boolean soloPresa = true;
        for (Cobro c : por.values()) soloPresa &= !c.fragmento() || c.id().equals(p0);
        h.ok("nadie más que la presa se lleva el Fragmento", soloPresa);
        Map<UUID, Double> poco = new HashMap<>(dano);
        poco.put(p0, 200.0);
        Cobro corta = null;
        for (Cobro c : repartir(a, poco, 1000, p0, 34, id -> false, id -> true)) if (c.id().equals(p0)) corta = c;
        h.ok("la presa con el 20 %: ni Fragmento ni Esencias", corta != null && "poco-dano".equals(corta.motivo())
                && !corta.fragmento() && corta.esencias() == 0);
        h.igual("Esencias de la presa a N 14/52/100", List.of(7, 11, 16),
                List.of(esenciasPresa(a, 14), esenciasPresa(a, 52), esenciasPresa(a, 100)));
        h.igual("Esencias de quien ayuda a N 14/52/100", List.of(2, 4, 7),
                List.of(esenciasAyuda(a, 14), esenciasAyuda(a, 52), esenciasAyuda(a, 100)));
        h.igual("sin cobro: la presa con poco daño", "No cobras por Ambush: tu daño no llegó al mínimo (25 % de su vida).",
                sinCobro("poco-dano", true, "", 10));

        // ---- Las katanas en la Forja, con sus precios (los de serie y los del config.yml del jar).
        Map<String, Altar.Trueque> serie = new HashMap<>();
        for (Altar.Trueque t : Altar.leer(Altar.DEFECTO)) serie.put(t.id(), t);
        katanas(h, "de serie", serie);
        YamlConfiguration jar = configDelJar();
        if (jar == null) {
            h.ok("config.yml del jar encontrado", false);
        } else {
            Map<String, Altar.Trueque> delJar = new HashMap<>();
            for (Altar.Trueque t : Altar.leer(jar.getMapList("hardcore.altar.trueques"))) delJar.put(t.id(), t);
            katanas(h, "config.yml", delJar);
            h.igual("config.yml: la Masamune de MMOItems", "CALAMITY_ARMAS.MASAMUNE", jar.getString("hardcore.forja.piezas.masamune"));
            h.igual("config.yml: la Crimson de MMOItems", "CALAMITY_ARMAS.CRIMSON_MASAMUNE",
                    jar.getString("hardcore.forja.piezas.crimson"));
        }
        h.igual("la Forja sigue en una hoja", 1,
                Marco.hojas(MenuAltar.sitios(MenuAltar.FORJA, Altar.leer(Altar.DEFECTO), false)));
        // La Crimson pide entregar la Masamune y 5 Fragmentos: sin la Masamune no se cobra nada; con
        // todo, la Forja se queda la Masamune y los Fragmentos.
        Altar.Trueque crimson = serie.get("crimson-masamune");
        if (crimson != null) {
            Altar.CajaPrueba c = new Altar.CajaPrueba();
            UUID u = Autotest.sintetico(541);
            String frag = u + ":" + FragmentosMasamune.OBJETO;
            c.saldo.sumar(u, 200, "prueba");
            c.encima.put(frag, 10);
            c.mc.put(u, 20_000L);
            Altar.Resultado r = comprarEn(c, crimson, u);
            h.ok("Crimson sin la Masamune encima: no se cobra nada", "objeto".equals(r.motivo()) && !r.devuelto()
                    && c.saldo.de(u) == 200 && c.cuantos(u, FragmentosMasamune.OBJETO) == 10 && c.mc(u) == 20_000L);
            c.encima.put(u + ":masamune", 1);
            r = comprarEn(c, crimson, u);
            h.ok("con la Masamune: se forja y la Masamune se entrega", r.ok() && !c.lleva(u, "masamune")
                    && c.entregados.contains("forja:crimsonx1"));
            h.ok("cobra 96 Esencias, 5 Fragmentos y 8.000 MobCoins", c.saldo.de(u) == 104
                    && c.cuantos(u, FragmentosMasamune.OBJETO) == 5 && c.mc(u) == 12_000L);
            c.encima.put(u + ":masamune", 1);
            c.saldo.sumar(u, 96, "prueba");
            c.encima.put(frag, 10);
            c.mc.put(u, 8_000L);
            c.entregar = false;
            r = comprarEn(c, crimson, u);
            h.ok("si la entrega falla, le devuelve la Masamune y lo demás", r.devuelto() && c.lleva(u, "masamune")
                    && c.saldo.de(u) == 200 && c.cuantos(u, FragmentosMasamune.OBJETO) == 10 && c.mc(u) == 8_000L);
        }

        // ---- La fase 2: al 50 % y no antes, y una sola vez.
        h.ok("al 51 % sigue en la fase 1", !PeleaAmbush.tocaFase2(0.51, a.fase2, 1));
        h.ok("al 50 % pasa a la fase 2", PeleaAmbush.tocaFase2(0.50, a.fase2, 1));
        h.ok("ya en la fase 2 no vuelve a saltar", !PeleaAmbush.tocaFase2(0.30, a.fase2, 2));
        h.igual("las dos skins de serie", List.of("itGuts", "Nagazaki_Yakuza"), List.of(a.skin1, a.skin2));

        // ---- Cada ataque acaba de pie y sin hojas ni sombras, tambien cortado a medias.
        PeleaAmbush.autotestAnimaciones(h);
        return h.lineas();
    }

    /** Que las dos katanas esten en esos trueques, en la Forja y con sus precios (los Fragmentos, en fisico). */
    static void katanas(Autotest.Hoja h, String de, Map<String, Altar.Trueque> ts) {
        Altar.Trueque m = ts.get("masamune"), c = ts.get("crimson-masamune");
        h.ok(de + ": Masamune en la Forja por 5 Fragmentos de Masamune, 64 Esencias y 5.000 MobCoins", m != null
                && "forja".equals(m.pagina()) && m.credito() == null && m.esencias() == 64 && m.mobcoins() == 5000
                && "forja:masamune".equals(m.da())
                && List.of(new Altar.Entrega(FragmentosMasamune.OBJETO, 5)).equals(m.entregar()));
        h.ok(de + ": Crimson Masamune por la Masamune, 5 Fragmentos, 96 Esencias y 8.000 MobCoins", c != null
                && "forja".equals(c.pagina()) && c.credito() == null && c.esencias() == 96 && c.mobcoins() == 8000
                && "forja:crimson".equals(c.da())
                && List.of(new Altar.Entrega("masamune", 1), new Altar.Entrega(FragmentosMasamune.OBJETO, 5)).equals(c.entregar()));
    }

    private static Altar.Resultado comprarEn(Altar.CajaPrueba c, Altar.Trueque t, UUID u) {
        Altar.Resultado[] r = new Altar.Resultado[1];
        Altar.comprar(c, t, u, "prueba", x -> r[0] = x);
        return r[0];
    }
}
