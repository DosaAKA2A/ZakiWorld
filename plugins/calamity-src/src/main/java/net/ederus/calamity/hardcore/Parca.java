package net.ederus.calamity.hardcore;

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.security.SecureRandom;
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
 * DIS sec. 1 · La PARCA: el gestor. Recibe la llamada de la Huella, marca al grupo, invoca,
 * lleva el tope global, guarda lo pendiente (desconexion, puerta, reinicio), reparte el
 * botin por la Aduana y aplica las pasivas que no son de la pelea: la cosecha es suya,
 * la tierra segada, la presencia que acelera la cordura y el Cristal que tarda el doble.
 *
 * Cada pelea viva es un PeleaParca (una sola tarea de 2 ticks para todas, la de Amenazas).
 * Aqui esta el unico Listener de la familia: la muerte de la PARCA, los golpes que recibe
 * o da, sus objetivos, vehiculos de los marcados y la cosecha (mobs, pesca, bloques).
 *
 * Los numeros (vida, golpe, siega, reparto) son estaticos y puros: el autotest "parca"
 * los comprueba con los ejemplos de DIS sec. 1.6 sin invocar nada.
 */
final class Parca implements Listener {

    /** SecureRandom: el botin raro es dinero en potencia (regla de la casa). */
    private static final SecureRandom AZAR = new SecureRandom();

    /**
     * Lo que se le dice al que vuelve con una Parca pendiente: con la marca de la puerta (salio
     * y ha vuelto antes de marca-fuera-minutos) o porque la pelea se quedo a medias
     * (desconexion o reinicio).
     */
    private static final String TE_ESPERABA = "La Parca te estaba esperando.";
    private static final String SIGUE_LA_PELEA = "La pelea con la Parca sigue donde la dejaste.";

    /** Lo que se dice a quien este cerca cuando se va por cansancio (P-25): vuelve si la presa sale y entra. */
    static final String CANSADA = "La Parca se cansa y se va, pero puede volver a por su presa.";

    /** El subtitulo del cambio de fase de la anomalia (ParcaAnomalia): lo que trae la fase nueva. */
    static String subtituloFase(int f) {
        return switch (f) {
            case 2 -> "Llegan las plañideras.";
            case 3 -> "Aparece a tu espalda y hace llover almas.";
            default -> "Suenan las campanadas de la Sentencia.";
        };
    }

    /**
     * Lo que se le dice a cada marcado en la barra al cambiar de fase: que hacer. Aqui y no en
     * ParcaAnomalia porque tambien lo usa la reserva (PeleaParca), que tiene que cargar sin las
     * clases de EDM. La reserva tiene tres fases: su III es la de las campanadas (la IV de EDM).
     */
    static String consejoFase(int f) {
        return switch (f) {
            case 2 -> "Mata a las plañideras: mientras vivan, la Parca recibe menos daño.";
            case 3 -> "No te quedes quieto: las almas caen donde pisas.";
            default -> "Cuando suenen las campanadas, sal del anillo antes de la última.";
        };
    }

    private final Hardcore hc;
    private final List<ParcaViva> peleas = new ArrayList<>();
    /** Reapariciones programadas (pendiente, marca): se cancelan en parar(). */
    private final List<BukkitTask> tareas = new ArrayList<>();
    /** Quien acaba de morir por una habilidad de la PARCA: su mensaje de muerte es P-19. */
    private final Set<UUID> segadosPorElla = new HashSet<>();
    /** Cuerpos de PARCAs muertas deshaciendose en almas (despedida); parar() los retira ya. */
    private final List<Entity> despedidas = new ArrayList<>();
    /** Tierra segada en memoria (se guarda tambien en datos parca.segada). */
    private final List<Segada> segadas = new ArrayList<>();
    private Ajustes ajustes;
    private long ajustesLeidos;
    private int segundos;
    /** Calamity 1.1.0: el puente con las anomalias de EDM (la PARCA DIOS); null si EDM no trae las clases. */
    private final PuenteAnomalia anomalia;

    private record Segada(String mundo, double x, double z, long hasta) {
    }

    Parca(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        cargarSegadas();
        Autotest.registrar("parca", Parca::autotest);
        Subcomandos.lw().registrar("parca",
                "parca <jugador> [segundos] | info <jugador> | vida <0-1> | habilidad <nombre> | retirar [jugador]"
                        + " | prueba <x> <y> <z> [N] | anomalia",
                "ederus.mundos", this::comando, this::tab);
        // Calamity 1.1.0: la PARCA tambien es una anomalia DIOS de EDM (ParcaType/ParcaAnomalia).
        anomalia = PuenteAnomalia.crear(this);
    }

    // ================================================================= ajustes

    /**
     * hardcore.parca con los valores de DIS sec. 4 por defecto (el Test solo trae minutos y
     * avisos: el resto sale de aqui). Se relee cada 5 s.
     */
    static final class Ajustes {
        final boolean activa;
        final int maximoSimultaneas, extraNivel, repeticionesMaximo, ventanaRepeticionHoras;
        final double radioMarcaGrupo;
        final int quietoMarcaGrupo;
        final double vidaBase, vidaPorNivel, vidaPorRepeticion, vidaPorMarcado;
        final double golpeBase, golpePorNivel, danoPorRepeticion, topeRepeticion, topeGolpeFraccion;
        final double escala, velocidad;
        final int siegaEspera, siegaEsperaF3, siegaAviso;
        final double siegaAngulo, siegaRadio, siegaVida, siegaTope, siegaCordura;
        final int umbralEspera, umbralSinVision, umbralSinGolpear, umbralAviso;
        final double umbralDistancia;
        final int tironEspera, tironAviso;
        final double tironMin, tironMax, tironRompe, tironFuerza;
        final int planBase, planTope, planVidaTicks, planEspera, planAturdir;
        final double planVida, planVidaPorNivel, planCordura, planReduccion, planAturdidaExtra;
        /** Mini withers: tamano, orbita alrededor de la PARCA y lamento (cordura, no vida). */
        final double planEscala, planRadio, planAltura, planLamentoRadio;
        final int planLamento;
        final int campEspera, campToques, campCada;
        final double campRadio, campVida, campTope, campCordura, campExtra;
        final int furiaMinutos, duracionMaxima, atasco;
        final double presenciaRadio, presenciaFactor, radioCosecha, radioCosechaBloques, tierraSegadaRadio;
        final int tierraSegadaMinutos, marcaFuera, pendienteHoras, esperaDesconexion, reapareceSegundos;
        final int cristalSegundos;
        final double cristalRadio;
        final String cabezaTextura;
        /** Cuerpo de NPC (Mannequin con la skin de una cuenta) sobre el esqueleto invisible. */
        final boolean cuerpoActivo;
        final String cuerpoSkin;
        final double cuerpoEscala;
        final int horasEntreCobros, esenciasBase, esenciasCada, ayudanteBase, ayudanteCada;
        final double participacionMinima, participacionPresa;
        final int gradoIII, gradoIV;
        final double raroProbabilidad;
        final List<String> raroComandos;
        final int campanaSoloTop, grupoMaximo;
        final double grupoExtra;

        Ajustes(ConfigurationSection s) {
            if (s == null) s = new YamlConfiguration();
            activa = s.getBoolean("activa", true);
            maximoSimultaneas = s.getInt("maximo-simultaneas", 3);
            radioMarcaGrupo = s.getDouble("radio-marca-grupo", 24);
            // 1.8: la PARCA llega a los 5 minutos (parca.minutos): el grupo, a los 210 s (el 70 %, como antes 420 de 600).
            quietoMarcaGrupo = s.getInt("quieto-marca-grupo", 210);
            extraNivel = s.getInt("extra-nivel", 10);
            vidaBase = s.getDouble("vida-base", 400);
            vidaPorNivel = s.getDouble("vida-por-nivel", 0.10);
            vidaPorRepeticion = s.getDouble("vida-por-repeticion", 0.25);
            vidaPorMarcado = s.getDouble("vida-por-marcado", 0.5);
            // 1.8: de 8 a 11, "extremadamente dificil, sobre todo por su dano" (Dosa).
            golpeBase = s.getDouble("golpe-base", 11);
            golpePorNivel = s.getDouble("golpe-por-nivel", 0.04);
            danoPorRepeticion = s.getDouble("dano-por-repeticion", 0.5);
            topeRepeticion = s.getDouble("tope-repeticion", 3.0);
            repeticionesMaximo = s.getInt("repeticiones-maximo", 4);
            ventanaRepeticionHoras = s.getInt("ventana-repeticion-horas", 24);
            topeGolpeFraccion = s.getDouble("tope-golpe-fraccion", 0.08);
            escala = s.getDouble("escala", 1.35);
            velocidad = s.getDouble("velocidad", 0.28);
            siegaEspera = s.getInt("siega.espera", 6);
            siegaEsperaF3 = s.getInt("siega.espera-fase3", 5);
            siegaAngulo = s.getDouble("siega.angulo", 100);
            siegaRadio = s.getDouble("siega.radio", 5);
            // 1.1.1: avisos algo mas largos (30/24/24 ticks): se ven mas y se leen mejor.
            siegaAviso = Math.max(2, s.getInt("siega.aviso-ticks", 30));
            siegaVida = s.getDouble("siega.vida", 0.20);
            siegaTope = s.getDouble("siega.tope", 0.60);
            siegaCordura = s.getDouble("siega.cordura", 8);
            umbralEspera = s.getInt("umbral.espera", 8);
            umbralDistancia = s.getDouble("umbral.distancia", 12);
            umbralSinVision = s.getInt("umbral.sin-vision-segundos", 4);
            umbralSinGolpear = s.getInt("umbral.sin-golpear-segundos", 5);
            umbralAviso = Math.max(2, s.getInt("umbral.aviso-ticks", 24));
            tironEspera = s.getInt("tiron.espera", 12);
            tironMin = s.getDouble("tiron.distancia-minima", 6);
            tironMax = s.getDouble("tiron.distancia-maxima", 18);
            tironAviso = Math.max(2, s.getInt("tiron.aviso-ticks", 24));
            tironRompe = s.getDouble("tiron.rompe", 0.03);
            tironFuerza = s.getDouble("tiron.fuerza", 1.6);
            planBase = s.getInt("planideras.base", 3);
            planTope = s.getInt("planideras.tope", 6);
            planVida = s.getDouble("planideras.vida", 12);
            planVidaPorNivel = s.getDouble("planideras.vida-por-nivel", 0.05);
            planEscala = Math.max(0.15, Math.min(1.0, s.getDouble("planideras.escala", 0.35)));
            planRadio = Math.max(1.5, s.getDouble("planideras.radio", 3.0));
            planAltura = s.getDouble("planideras.altura", 1.8);
            planLamento = Math.max(1, s.getInt("planideras.lamento-segundos", 4));
            planLamentoRadio = s.getDouble("planideras.lamento-radio", 12);
            planCordura = s.getDouble("planideras.cordura", 3);
            planVidaTicks = s.getInt("planideras.vida-ticks", 800);
            planReduccion = s.getDouble("planideras.reduccion", 0.5);
            planEspera = s.getInt("planideras.espera", 30);
            planAturdir = s.getInt("planideras.aturdir-segundos", 3);
            planAturdidaExtra = s.getDouble("planideras.aturdida-extra", 0.25);
            campEspera = s.getInt("campanada.espera", 30);
            campToques = Math.max(1, s.getInt("campanada.toques", 5));
            campCada = Math.max(2, s.getInt("campanada.cada-ticks", 24));
            campRadio = s.getDouble("campanada.radio", 10);
            campVida = s.getDouble("campanada.vida", 0.50);
            campTope = s.getDouble("campanada.tope", 0.90);
            campCordura = s.getDouble("campanada.cordura", 25);
            campExtra = s.getDouble("campanada.dano-extra-recibido", 0.25);
            furiaMinutos = s.getInt("furia-minutos", 5);
            duracionMaxima = s.getInt("duracion-maxima-minutos", 12);
            atasco = s.getInt("atasco-segundos", 20);
            presenciaRadio = s.getDouble("presencia-radio", 16);
            presenciaFactor = s.getDouble("presencia-factor", 2.0);
            radioCosecha = s.getDouble("radio-cosecha", 48);
            radioCosechaBloques = s.getDouble("radio-cosecha-bloques", 16);
            tierraSegadaMinutos = s.getInt("tierra-segada-minutos", 30);
            tierraSegadaRadio = s.getDouble("tierra-segada-radio", 24);
            marcaFuera = s.getInt("marca-fuera-minutos", 30);
            pendienteHoras = s.getInt("pendiente-horas", 24);
            esperaDesconexion = s.getInt("espera-desconexion-segundos", 60);
            reapareceSegundos = s.getInt("reaparece-segundos", 5);
            cristalSegundos = s.getInt("cristal-segundos-marcado", 10);
            cristalRadio = s.getDouble("cristal-radio-marcado", 24);
            cabezaTextura = s.getString("cabeza-textura", "");
            cuerpoActivo = s.getBoolean("cuerpo.activo", true);
            cuerpoSkin = s.getString("cuerpo.skin", "Leonsaurusrex");
            // Calamity 1.1.0: un 20 % mas grande (1,4 -> 1,68); el esqueleto invisible se ajusta solo (escalaEsqueleto).
            cuerpoEscala = Math.max(0.5, Math.min(3.0, s.getDouble("cuerpo.escala", 1.68)));
            horasEntreCobros = s.getInt("botin.horas-entre-cobros", 24);
            participacionMinima = s.getDouble("botin.participacion-minima", 0.10);
            participacionPresa = s.getDouble("botin.participacion-presa", 0.25);
            esenciasBase = s.getInt("botin.esencias-base", 4);
            esenciasCada = Math.max(1, s.getInt("botin.esencias-cada-niveles", 10));
            ayudanteBase = s.getInt("botin.ayudante-esencias-base", 2);
            ayudanteCada = Math.max(1, s.getInt("botin.ayudante-esencias-cada-niveles", 20));
            List<Integer> grados = s.getIntegerList("botin.reliquia-grados");
            gradoIII = grados.size() >= 1 ? grados.get(0) : 25;
            gradoIV = grados.size() >= 2 ? grados.get(1) : 50;
            raroProbabilidad = s.getDouble("botin.raro.probabilidad", 0.0);
            raroComandos = s.getStringList("botin.raro.comandos");
            campanaSoloTop = s.getInt("campana.solo-top", 3);
            grupoExtra = s.getDouble("grupo.extra-por-participante", 0.25);
            grupoMaximo = s.getInt("grupo.maximo-extra", 4);
        }
    }

    Ajustes ajustes() {
        long ahora = System.currentTimeMillis();
        if (ajustes == null || ahora - ajustesLeidos > 5_000) {
            ajustes = new Ajustes(hc.cfg().getConfigurationSection("parca"));
            ajustesLeidos = ahora;
        }
        return ajustes;
    }

    Hardcore hc() {
        return hc;
    }

    // ============================================================ numeros (sec. 1.6)

    /** N = min(100, N0 + extra-nivel). */
    static int nivel(Ajustes a, int n0) {
        return Math.max(1, Math.min(100, n0 + a.extraNivel));
    }

    /** R = min(tope, 1 + dano-por-repeticion x r): cada repeticion pega +50 %. */
    static double factorR(Ajustes a, int r) {
        int rr = Math.max(0, Math.min(a.repeticionesMaximo, r));
        return Math.min(a.topeRepeticion, 1 + a.danoPorRepeticion * rr);
    }

    /** vidaLogica = 400 x (1 + 0,10 x (N-1)) x (1 + 0,25 x r) x (1 + 0,5 x M). */
    static double vidaLogica(Ajustes a, int n, int r, int m) {
        int rr = Math.max(0, Math.min(a.repeticionesMaximo, r));
        return a.vidaBase * (1 + a.vidaPorNivel * (n - 1)) * (1 + a.vidaPorRepeticion * rr)
                * (1 + a.vidaPorMarcado * Math.max(0, m));
    }

    /** golpe = 11 x (1 + 0,04 x (N-1)) x R, al atributo attack_damage (sin penetracion). */
    static double golpe(Ajustes a, int n, int r) {
        return a.golpeBase * (1 + a.golpePorNivel * (n - 1)) * factorR(a, r);
    }

    /**
     * 1.8 · Con la dificultad de su presa (DificultadAmenaza: lejos, poca cordura y mucho rato
     * dentro): el multiplicador va despues de la formula por nivel y de las repeticiones.
     */
    static double vidaLogica(Ajustes a, int n, int r, int m, double dificultad) {
        return vidaLogica(a, n, r, m) * dificultad;
    }

    static double golpe(Ajustes a, int n, int r, double dificultad) {
        return golpe(a, n, r) * dificultad;
    }

    /** Siega: fraccion de la vida maxima de la victima. x2 si esta quieta, con tope 0,60. */
    static double siegaFraccion(Ajustes a, double r, boolean quieta) {
        return Math.min(a.siegaTope, a.siegaVida * r * (quieta ? 2 : 1));
    }

    /**
     * Sentencia (5.a campanada; no "Juicio": el servidor ya tiene otro sistema con ese nombre).
     * Fraccion de la vida maxima, tope 0,90 (ley 5).
     */
    static double sentenciaFraccion(Ajustes a, double r) {
        return Math.min(a.campTope, a.campVida * r);
    }

    /**
     * Dureza en grupo (DIS sec. 1.6 [alineado]): cuando un jugador que no es la presa pasa a
     * ser participante y ya hay al menos dos, la PARCA sube; como mucho grupo.maximo-extra veces.
     */
    static boolean subeGrupo(int participantes, boolean nuevoEsPresa, int extras, Ajustes a) {
        return !nuevoEsPresa && participantes >= 2 && extras < a.grupoMaximo;
    }

    static int esenciasPresa(Ajustes a, int n) {
        return a.esenciasBase + n / a.esenciasCada;
    }

    static int esenciasAyudante(Ajustes a, int n) {
        return a.ayudanteBase + n / a.ayudanteCada;
    }

    /** Campana de la Parca: N < 25 -> II, 25-49 -> III, >= 50 -> IV. */
    static int gradoCampana(Ajustes a, int n) {
        if (n < a.gradoIII) return 2;
        if (n < a.gradoIV) return 3;
        return 4;
    }

    /** Lo que le toca a cada uno que le pego (grado 0 = sin Campana; motivo != null = no cobra). */
    record Cobro(UUID id, double fraccion, int esencias, int grado, String motivo) {
    }

    /**
     * El reparto de DIS sec. 1.10 [alineado], sin Bukkit.
     * - Ayudante: >= participacion-minima de la vida logica, valido con la presa (Aduana) y sin
     *   cobro en las ultimas horas-entre-cobros. Esencias de ayudante; Campana solo el top-3.
     * - Presa: solo si es su primera PARCA del dia (r = 0), >= participacion-presa ella sola, y
     *   solo Esencias (no se premia al que la provoca).
     * En orden de dano, de mas a menos.
     */
    static List<Cobro> repartir(Ajustes a, Map<UUID, Double> dano, double vida, UUID presa, int n, int r,
                                Predicate<UUID> yaCobro, Predicate<UUID> valida) {
        List<Map.Entry<UUID, Double>> orden = new ArrayList<>(dano.entrySet());
        orden.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
        List<Cobro> out = new ArrayList<>();
        int campanas = 0;
        for (Map.Entry<UUID, Double> e : orden) {
            UUID id = e.getKey();
            double f = vida <= 0 ? 0 : e.getValue() / vida;
            if (id.equals(presa)) {
                String motivo = f < a.participacionPresa ? "poco-dano"
                        : r > 0 ? "repeticion"
                        : yaCobro.test(id) ? "ya-cobro" : null;
                out.add(new Cobro(id, f, motivo == null ? esenciasPresa(a, n) : 0, 0, motivo));
                continue;
            }
            String motivo = f < a.participacionMinima ? "poco-dano"
                    : !valida.test(id) ? "invalida"
                    : yaCobro.test(id) ? "ya-cobro" : null;
            if (motivo != null) {
                out.add(new Cobro(id, f, 0, 0, motivo));
                continue;
            }
            int grado = campanas < a.campanaSoloTop ? gradoCampana(a, n) : 0;
            if (grado > 0) campanas++;
            out.add(new Cobro(id, f, esenciasAyudante(a, n), grado, null));
        }
        return out;
    }

    /**
     * 1.7.6: la linea de chat de quien le pego y no cobra (un motivo de repartir), con las cifras
     * de la config. porQueInvalida: el motivo de la Aduana ("horas", "huella"...) si es "invalida".
     * Null si el motivo no es ninguno de estos.
     */
    static String sinCobro(Ajustes a, String motivo, boolean presa, String porQueInvalida, int horasMinimas) {
        String porQue = switch (motivo == null ? "" : motivo) {
            case "poco-dano" -> "tu daño no llegó al mínimo ("
                    + Marco.porcentaje(presa ? a.participacionPresa : a.participacionMinima) + " de su vida).";
            case "repeticion" -> "ya te persiguió otra en las últimas " + a.ventanaRepeticionHoras + " h.";
            case "ya-cobro" -> "ya cobraste por otra en las últimas " + a.horasEntreCobros + " h.";
            case "invalida" -> "horas".equals(porQueInvalida)
                    ? "para cobrar ayudando a otro, cada uno necesita al menos " + horasMinimas + " h jugadas."
                    : "la presa usa tu misma conexión.";
            default -> null;
        };
        return porQue == null ? null : "Esta Parca no te paga: " + porQue;
    }

    // ================================================================= estado

    /** La pelea en la que ese jugador esta marcado (presa o extra), o null. */
    ParcaViva de(UUID jugador) {
        for (ParcaViva pe : peleas) if (pe.vivaParaJugadores() && pe.marcados().contains(jugador)) return pe;
        return null;
    }

    /** La pelea de esa entidad (el cuerpo de la PARCA), o null. */
    ParcaViva deCuerpo(Entity e) {
        if (e == null) return null;
        for (ParcaViva pe : peleas) if (pe.cuerpo() != null && pe.cuerpo().getUniqueId().equals(e.getUniqueId())) return pe;
        return null;
    }

    /** La pelea de un cuerpo que se ve (el maniqui con skin), o null. */
    ParcaViva deCascara(Entity e) {
        if (e == null) return null;
        for (ParcaViva pe : peleas) if (pe.esCascara(e)) return pe;
        return null;
    }

    /**
     * Calamity 1.8.5: si alguna PARCA le tiene puesto su cielo (lluvia o noche de sus fases). Clima
     * no toca entonces ni el clima ni la hora de ese jugador: la PARCA no los repone si se los pisan.
     */
    boolean cieloSobre(Player p) {
        if (p == null) return false;
        for (ParcaViva pe : peleas) if (pe.ambienteSobre(p.getUniqueId())) return true;
        return false;
    }

    int vivas() {
        int n = 0;
        for (ParcaViva pe : peleas) if (pe.vivaParaJugadores()) n++;
        return n;
    }

    /** La quita de la lista (la llama la pelea al acabar). */
    void olvidar(ParcaViva pe) {
        peleas.remove(pe);
    }

    // ============================================================ ganchos Hardcore

    /** Una vez por segundo, desde Hardcore.tick. Las peleas se mueven solas cada 2 ticks. */
    void tick() {
        peleas.removeIf(pe -> pe.estado() == ParcaViva.Estado.FIN);
        if (++segundos % 60 == 0) podarSegadas();
        // Si EDM recarga su modulo de anomalias, el catalogo nuevo no la trae: se vuelve a registrar.
        if (segundos % 60 == 0 && anomalia != null) hc.seguro("parca", anomalia::revisar);
    }

    /** Si hay una PARCA a presencia-radio de ese jugador. */
    boolean cerca(Player p) {
        double r = ajustes().presenciaRadio;
        for (ParcaViva pe : peleas) {
            if (!pe.vivaParaJugadores() || pe.cuerpo() == null || pe.cuerpo().getWorld() != p.getWorld()) continue;
            if (pe.cuerpo().getLocation().distanceSquared(p.getLocation()) <= r * r) return true;
        }
        return false;
    }

    /** Si hay alguna PARCA viva en el servidor (las alucinaciones no hacen figuras ni carreras con ella). */
    boolean hayViva() {
        for (ParcaViva pe : peleas) if (pe.vivaParaJugadores()) return true;
        return false;
    }

    /** Distancia a la PARCA viva mas cercana en su mundo; Double.MAX_VALUE si no hay (latido a <= 32). */
    double distancia(Player p) {
        double mejor = Double.MAX_VALUE;
        for (ParcaViva pe : peleas) {
            if (!pe.vivaParaJugadores() || pe.cuerpo().getWorld() != p.getWorld()) continue;
            mejor = Math.min(mejor, pe.cuerpo().getLocation().distanceSquared(p.getLocation()));
        }
        return mejor == Double.MAX_VALUE ? mejor : Math.sqrt(mejor);
    }

    /** Si ese jugador esta marcado por una PARCA viva (ley 6: sin minijefe de cordura). */
    boolean persigue(Player p) {
        return p != null && de(p.getUniqueId()) != null;
    }

    /**
     * La cosecha es suya (DIS sec. 1.8): a radio-cosecha de una PARCA viva o de uno de sus
     * marcados, o en tierra segada, nada suelta nada. Lo leen el Grifo (no paga) y la
     * muerte de mobs de aqui abajo (no suelta).
     */
    boolean cosechando(Location donde) {
        if (donde == null || donde.getWorld() == null) return false;
        Ajustes a = ajustes();
        double r2 = a.radioCosecha * a.radioCosecha;
        for (ParcaViva pe : peleas) {
            if (!pe.vivaParaJugadores() || pe.cuerpo() == null || pe.cuerpo().getWorld() != donde.getWorld()) continue;
            if (pe.cuerpo().getLocation().distanceSquared(donde) <= r2) return true;
            for (UUID id : pe.marcados()) {
                Player m = hc.plugin().getServer().getPlayer(id);
                if (m != null && m.getWorld() == donde.getWorld() && m.getLocation().distanceSquared(donde) <= r2) return true;
            }
        }
        long ahora = System.currentTimeMillis();
        String mundo = donde.getWorld().getKey().toString();
        for (Segada s : segadas) {
            if (s.hasta() <= ahora || !s.mundo().equals(mundo)) continue;
            double dx = s.x() - donde.getX(), dz = s.z() - donde.getZ();
            if (dx * dx + dz * dz <= a.tierraSegadaRadio * a.tierraSegadaRadio) return true;
        }
        return false;
    }

    /** Presencia (sec. 1.8): la cordura de los marcados y de quien este cerca baja x presencia-factor. */
    double factorDrenaje(Player p) {
        if (peleas.isEmpty()) return 1.0;
        return persigue(p) || cerca(p) ? ajustes().presenciaFactor : 1.0;
    }

    /** Segundos del Cristal: def, o cristal-segundos-marcado con ella a cristal-radio-marcado (P-31). */
    int segundosCristal(Player p, int def) {
        ParcaViva pe = de(p.getUniqueId());
        if (pe == null || pe.cuerpo() == null || pe.cuerpo().getWorld() != p.getWorld()) return def;
        Ajustes a = ajustes();
        if (pe.cuerpo().getLocation().distanceSquared(p.getLocation()) > a.cristalRadio * a.cristalRadio) return def;
        // P-31 lo pinta Hardcore en la cuenta del Cristal ("Con ella encima · Cristal · N s").
        return Math.max(def, a.cristalSegundos);
    }

    /**
     * Toda muerte dentro (Hardcore.onMuerte, antes de borrar nada). La huella se vacia; si
     * era presa, "cosecha" y se borra lo pendiente; si era un marcado extra, sale de la lista.
     */
    void alMorirPresa(Player p) {
        UUID id = p.getUniqueId();
        if (hc.huella() != null) hc.huella().reiniciar(p);
        // Quien muere lo pierde todo: tambien la cita pendiente con ella.
        borrarPendiente(id);
        ParcaViva pe = de(id);
        if (pe == null) return;
        boolean porElla = false;
        try {
            EntityDamageEvent ultimo = p.getLastDamageCause();
            Entity causa = ultimo == null ? null : ultimo.getDamageSource().getCausingEntity();
            porElla = causa != null && (pe.esCuerpo(causa) || pe.esPlanidera(causa));
        } catch (Throwable ignorado) {
            // Sin causa legible se trata como "por lo que sea": sin tierra segada.
        }
        if (id.equals(pe.presa())) {
            if (porElla) {
                segadosPorElla.add(id);
                segar(p.getLocation());
            }
            pe.cosecha(porElla);
        } else {
            pe.quitarMarcado(id, "muerto");
        }
    }

    /** Sale por la puerta, el Cristal o un admin: se retira y le espera marca-fuera-minutos (P-23). */
    void alSalir(Player p, String motivo) {
        ParcaViva pe = de(p.getUniqueId());
        if (pe == null) return;
        if (!p.getUniqueId().equals(pe.presa())) {
            pe.quitarMarcado(p.getUniqueId(), "salio");
            return;
        }
        Ajustes a = ajustes();
        long hasta = System.currentTimeMillis() + a.marcaFuera * 60_000L;
        guardarPendiente(pe, hasta, "salida");
        hc.datos().set("parca.marca." + p.getUniqueId(), hasta);
        hc.guardarYa();
        p.sendMessage(ComandoCalamity.mensaje("La Parca no te sigue fuera de Calamity, pero si vuelves en menos de "
                + a.marcaFuera + " min, te estará esperando."));
        pe.irse("salida:" + (motivo == null ? "?" : motivo), null);
    }

    /**
     * Entra por la puerta. Si la PARCA le esperaba (marca de la puerta o algo pendiente), vuelve
     * cuando acaba la llegada protegida (M5): a 24 bloques con marca (P-24), a 6 si no (P-22).
     */
    void alEntrar(Player p) {
        Pendiente pd = leerPendiente(p.getUniqueId());
        if (pd == null) return;
        boolean conMarca = hc.datos().getLong("parca.marca." + p.getUniqueId(), 0) > System.currentTimeMillis();
        volverFuera(p.getUniqueId(), conMarca ? 24 : 6, conMarca ? TE_ESPERABA : SIGUE_LA_PELEA);
    }

    /**
     * Trae lo pendiente cuando se pueda: se mira cada segundo hasta que se le acabe la proteccion
     * de llegada (como mucho 60 s) y, 1.2, mientras la zona spawn no la deje venir (esta dentro,
     * o el unico sitio que hay cae dentro). Eso sin tope: mientras le dure lo pendiente, le espera.
     */
    private void volverFuera(UUID id, double distancia, String mensaje) {
        final int[] vueltas = {0};
        BukkitTask[] t = new BukkitTask[1];
        t[0] = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(), () -> {
            Player j = hc.plugin().getServer().getPlayer(id);
            if (j != null && hc.esHardcore(j)) {
                boolean protegido = hc.combate() != null && hc.valor("combate", () -> hc.combate().protegido(j), false);
                if (protegido && ++vueltas[0] <= 60) return;
                if (!hc.valor("parca", () -> reaparecer(j, distancia, mensaje), true)) return;
            }
            t[0].cancel();
            tareas.remove(t[0]);
        }, 20L, 20L);
        tareas.add(t[0]);
    }

    /**
     * 1.2 · Entra en la zona spawn con ella encima (Hardcore.vigilarSpawn). Ahi no entra: como en
     * la puerta, se va sin botin y le espera fuera marca-fuera-minutos con la vida y la fase que
     * tenia, y en cuanto sale de la zona vuelve a 24 bloques (P-24). Un marcado que no es la presa
     * solo sale de la lista.
     */
    void alEntrarSpawn(Player p) {
        ParcaViva pe = de(p.getUniqueId());
        if (pe == null || pe.prueba() || pe.estado() == ParcaViva.Estado.COSECHA) return;
        if (!p.getUniqueId().equals(pe.presa())) {
            pe.quitarMarcado(p.getUniqueId(), "spawn");
            return;
        }
        Ajustes a = ajustes();
        long hasta = System.currentTimeMillis() + a.marcaFuera * 60_000L;
        guardarPendiente(pe, hasta, "spawn");
        hc.datos().set("parca.marca." + p.getUniqueId(), hasta);
        hc.guardarYa();
        p.sendMessage(ComandoCalamity.mensaje("La Parca no entra en el spawn: te espera fuera."));
        pe.irse("spawn", null);
        volverFuera(p.getUniqueId(), 24, TE_ESPERABA);
    }

    /**
     * Se desconecta. Con etiqueta de combate es combat log (M5, lo resuelve Combate): la
     * PARCA se va sin botin y sin pendiente. Sin etiqueta, se queda espera-desconexion-segundos
     * quieta donde esta y se va guardando lo pendiente.
     */
    void alDesconectar(Player p) {
        ParcaViva pe = de(p.getUniqueId());
        /* El combat log llega aqui DESPUES de Combate.cable, que ya llamo a alMorirPresa: la
         * pelea esta en cosecha (o acabada) y no hay nada que esperar ni que dejar pendiente. */
        if (pe == null || pe.estado() == ParcaViva.Estado.COSECHA) return;
        if (!p.getUniqueId().equals(pe.presa())) {
            pe.quitarMarcado(p.getUniqueId(), "desconectado");
            return;
        }
        boolean combate = hc.combate() != null && hc.valor("combate", () -> hc.combate().enCombate(p), false);
        if (combate) {
            pe.irse("combat-log", null);
            return;
        }
        pe.esperar();
    }

    /** Vuelve al servidor: si ella seguia esperando, sigue; si hay algo pendiente y esta dentro, vuelve (P-22). */
    void alVolver(Player p) {
        if (!hc.esHardcore(p)) return;
        for (ParcaViva pe : peleas) {
            if (pe.estado() == ParcaViva.Estado.ESPERA && p.getUniqueId().equals(pe.presa())) {
                pe.reanudar();
                return;
            }
        }
        if (leerPendiente(p.getUniqueId()) == null) return;
        UUID id = p.getUniqueId();
        BukkitTask[] t = new BukkitTask[1];
        t[0] = hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(), () -> {
            tareas.remove(t[0]);
            Player j = hc.plugin().getServer().getPlayer(id);
            if (j != null && hc.esHardcore(j)) {
                // 1.2: si la zona spawn no la deja venir (vuelve dentro de ella), le espera a que salga.
                if (!hc.valor("parca", () -> reaparecer(j, 6, SIGUE_LA_PELEA), true)) {
                    volverFuera(id, 6, SIGUE_LA_PELEA);
                }
            }
        }, Math.max(1, ajustes().reapareceSegundos) * 20L);
        tareas.add(t[0]);
    }

    /**
     * Minijefes de cordura cero que persiguen a un marcado (ley 6): se retiran con humo (P-10).
     * No hace falta tocar el mapa de presas de Hardcore: con la entidad borrada, vigilarPresas
     * la suelta en el segundo siguiente.
     */
    void retirarMinijefes(Player m) {
        for (Entity e : m.getNearbyEntities(64, 32, 64)) {
            // Solo los que van a por el: Hardcore.vigilarPresas les pone el objetivo cada segundo.
            if (!(e instanceof Mob mob) || !Huella.esMinijefe(e) || !m.equals(mob.getTarget())) continue;
            Compat.spawn(e.getWorld(), Compat.LARGE_SMOKE, e.getLocation().add(0, 1, 0), 30, 0.5, 1, 0.5, 0.02);
            e.remove();
            hc.cordura().destello(m, Component.text("El minijefe que te seguía se aparta de la Parca.", Paleta.TEXTO), 3);
            hc.plugin().bitacora().anotar("parca", "minijefe-retirado", m.getName());
        }
    }

    void parar() {
        // Un reinicio no es culpa de la presa: lo que quedaba se guarda como la desconexion.
        long hasta = System.currentTimeMillis() + ajustes().pendienteHoras * 3_600_000L;
        for (ParcaViva pe : new ArrayList<>(peleas)) {
            if (!pe.prueba() && pe.vivaParaJugadores() && pe.estado() != ParcaViva.Estado.COSECHA) {
                hc.seguro("parca", () -> guardarPendiente(pe, hasta, "reinicio"));
            }
            hc.seguro("parca", pe::limpiar);
        }
        peleas.clear();
        for (BukkitTask t : tareas) t.cancel();
        tareas.clear();
        for (Entity e : despedidas) Fx.safeRemove(e);
        despedidas.clear();
        segadosPorElla.clear();
        if (anomalia != null) anomalia.parar();
        hc.marcarSucio();
        HandlerList.unregisterAll(this);
    }

    /**
     * La PARCA ha caido: el cuerpo que se ve se arrodilla y se deshace en almas en 1,5 s, en
     * vez de desaparecer de golpe. La tarea va en tareas (parar() la cancela y lo retira).
     */
    void despedida(Mannequin m) {
        try {
            m.setPose(Pose.SNEAKING, true);
        } catch (Throwable ignorado) {
            // Sin esa postura se deshace de pie: igual de claro.
        }
        despedidas.add(m);
        World w = m.getWorld();
        int[] t = {0};
        BukkitTask[] tarea = new BukkitTask[1];
        tarea[0] = hc.plugin().getServer().getScheduler().runTaskTimer(hc.plugin(), () -> {
            t[0] += 2;
            boolean fin = !m.isValid() || t[0] >= 30;
            if (m.isValid()) {
                Location l = m.getLocation();
                Compat.spawn(w, Compat.SOUL, l.clone().add(0, 0.8 + t[0] / 15.0, 0), 3, 0.3, 0.5, 0.3, 0.02);
                Compat.spawn(w, Compat.LARGE_SMOKE, l.clone().add(0, 0.3, 0), 2, 0.3, 0.1, 0.3, 0.01);
                if (fin) {
                    Compat.spawn(w, Compat.SOUL, l.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.05);
                    Compat.sound(w, l, "entity.allay.death", 1.2f, 0.5f);
                }
            }
            if (fin) {
                Fx.safeRemove(m);
                despedidas.remove(m);
                tarea[0].cancel();
                tareas.remove(tarea[0]);
            }
        }, 2L, 2L);
        tareas.add(tarea[0]);
    }

    // ================================================================== invocar

    /**
     * La Huella ha llegado al limite (sec. 1.4). True si ya tiene una PARCA encima (nueva o
     * entrando como marcado extra de una viva); false si no ha podido (tope global lleno,
     * apagada, spawn cancelado): quieto se queda arriba y se vuelve a intentar.
     */
    boolean invocar(Player p, int celdas, boolean vehiculo) {
        Ajustes a = ajustes();
        if (!a.activa || !hc.esHardcore(p)) return false;
        if (persigue(p)) return true;
        // 1.2: en la zona spawn no aparece nunca; alli el AFK es cosa de la Grieta (que la trae lejos).
        if (hc.enSpawn(p)) return false;
        // Otro que llega a 600 a <= 32 de una viva no trae otra: entra en esa con M+1.
        for (ParcaViva pe : peleas) {
            if (pe.prueba() || !pe.aceptaMarcados() || pe.cuerpo().getWorld() != p.getWorld()) continue;
            if (pe.cuerpo().getLocation().distanceSquared(p.getLocation()) <= 32 * 32) {
                pe.agregarMarcado(p);
                return true;
            }
        }
        if (vivas() >= a.maximoSimultaneas) return false;

        // Marcado en grupo: los que esten cerca y lleven quieto-marca-grupo tambien.
        List<Player> grupo = new ArrayList<>();
        grupo.add(p);
        for (Player o : p.getWorld().getPlayers()) {
            if (o.equals(p) || !hc.cuenta(o) || persigue(o) || exento(o) || hc.enSpawn(o)) continue;
            if (o.getLocation().distanceSquared(p.getLocation()) > a.radioMarcaGrupo * a.radioMarcaGrupo) continue;
            if (hc.huella() != null && hc.huella().quieto(o) >= a.quietoMarcaGrupo) grupo.add(o);
        }
        int r = repeticiones(p.getUniqueId());
        int n0 = 1;
        for (Player m : grupo) n0 = Math.max(n0, nivelCalamity(m));
        int n = nivel(a, n0);
        int m = grupo.size() - 1;
        Location sitio = sitioFuera(p, 6);
        if (sitio == null) return false;   // 1.2: detras y delante cae en la zona spawn; el segundo siguiente
        ParcaViva pe = nueva(a, p, grupo, n, r, m, sitio, 1.0, 1, DificultadAmenaza.foto(hc, p));
        if (pe == null) return false;
        registrar(pe);

        long ahora = System.currentTimeMillis();
        for (Player g : grupo) apuntarHistorial(g.getUniqueId(), ahora);
        hc.marcarSucio();
        Location l = pe.cuerpo().getLocation();
        hc.plugin().bitacora().anotar("parca", "llega", p.getName(), "N " + n, "r " + r, "M " + m,
                l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(), "celdas " + celdas,
                "vehiculo " + (vehiculo ? "si" : "no"), pe.tipo(), pe.dificultad().texto());
        telemetria("nace", pe, null, null);
        Component aviso = ComandoCalamity.mensaje(Component.text("Suena una campana. La Parca ha venido a por ")
                .append(Component.text(p.getName(), Paleta.DETALLE)).append(Component.text(".")));
        for (Player o : Fx.viewersNear(p.getLocation(), 128)) o.sendMessage(aviso);
        for (Player g : grupo) retirarMinijefes(g);
        return true;
    }

    /**
     * Calamity 1.1.0: la PARCA de EDM (anomalia DIOS) si EDM esta libre; si no (otra anomalia abierta,
     * sin el modulo, apagada en su menu o en parca.anomalia.activa), la de reserva de siempre.
     * Ningun AFK se libra porque EDM este ocupado.
     *
     * 1.8: foto = como estaba la presa al llamarla (DificultadAmenaza): lejos, poca cordura y mucho
     * rato dentro la hacen mas fuerte. La que vuelve de lo pendiente trae la suya.
     */
    private ParcaViva nueva(Ajustes a, Player p, List<Player> grupo, int n, int r, int m, Location sitio,
                            double fraccion, int fase, DificultadAmenaza.Foto foto) {
        DificultadAmenaza.Resultado dif = hc.valor("parca", () -> DificultadAmenaza.para(hc, foto), DificultadAmenaza.NEUTRO);
        if (anomalia != null) {
            PuenteAnomalia.Encargo e = new PuenteAnomalia.Encargo(p.getUniqueId(), p.getName(), grupo, n, r, m,
                    fraccion, fase, dif);
            ParcaViva pe = hc.valor("parca", () -> anomalia.abrir(e, sitio), null);
            if (pe != null) return pe;
        }
        return PeleaParca.crear(this, a, p.getUniqueId(), p.getName(), grupo, n, r, m, sitio, false, fraccion, fase, dif);
    }

    /** Apunta una pelea viva (la anomalia se apunta sola al nacer, tambien la abierta a mano). */
    void registrar(ParcaViva pe) {
        if (pe != null && !peleas.contains(pe)) peleas.add(pe);
    }

    /**
     * 1.2 · Donde aparece (sitioDetras) sin caer en la zona spawn: si detras queda la zona (acaba
     * de salir de ella, o esta en su borde mirando hacia fuera), delante. Null si las dos caen dentro.
     */
    private Location sitioFuera(Player p, double distancia) {
        Location sitio = sitioDetras(p, distancia);
        if (!hc.enSpawn(sitio)) return sitio;
        Location girado = p.getLocation();
        girado.setYaw(girado.getYaw() + 180);
        sitio = sitioDetras(girado, distancia);
        return hc.enSpawn(sitio) ? null : sitio;
    }

    /** Exento a mano de la PARCA (/lw hardcore exento; ningun permiso lo concede). */
    boolean exento(Player p) {
        return hc.exentos() != null && hc.exentos().parca(p.getUniqueId());
    }

    private int nivelCalamity(Player p) {
        if (hc.plugin().mobs() == null) return Math.max(1, hc.bonusNivel(p));
        return hc.plugin().mobs().nivelCalamity(p);
    }

    /** r: PARCAs de ese jugador en ventana-repeticion-horas, sin contar la que llega (poda al leer). */
    int repeticiones(UUID id) {
        Ajustes a = ajustes();
        String ruta = "parca.historial." + id;
        List<Long> lista = new ArrayList<>(hc.datos().getLongList(ruta));
        long desde = System.currentTimeMillis() - a.ventanaRepeticionHoras * 3_600_000L;
        boolean podado = lista.removeIf(t -> t < desde);
        if (podado) {
            hc.datos().set(ruta, lista.isEmpty() ? null : lista);
            hc.marcarSucio();
        }
        return Math.min(a.repeticionesMaximo, lista.size());
    }

    private void apuntarHistorial(UUID id, long ahora) {
        String ruta = "parca.historial." + id;
        List<Long> lista = new ArrayList<>(hc.datos().getLongList(ruta));
        lista.add(ahora);
        hc.datos().set(ruta, lista);
    }

    /**
     * Sitio a "distancia" bloques detras de donde mira (mirada invertida), a ras de suelo y con
     * hueco 1x1x3. Si no hay ninguno, justo detras aunque sea dentro de un bloque: no se asfixia
     * (Amenazas cancela todo dano que no sea de jugador) y el Paso Umbral la saca.
     */
    static Location sitioDetras(Player p, double distancia) {
        return sitioDetras(p.getLocation(), distancia);
    }

    /** Lo mismo desde un sitio y una mirada cualesquiera (1.2: sitioFuera la gira). */
    static Location sitioDetras(Location base, double distancia) {
        Vector atras = base.getDirection().setY(0);
        if (atras.lengthSquared() < 1e-4) atras = new Vector(0, 0, 1);
        atras.normalize().multiply(-1);
        for (double d = distancia; d >= 1; d -= 1) {
            Location c = Fx.ground(base.clone().add(atras.clone().multiply(d)), 6);
            if (Math.abs(c.getY() - base.getY()) <= 6 && libre(c, 3)) return mirando(c, base);
        }
        return mirando(base.clone().add(atras), base);
    }

    /** El hueco libre (pies y cabeza, con suelo) mas cercano a "centro" dentro de "radio"; null si no hay. */
    static Location huecoCerca(Location centro, int radio) {
        Location mejor = null;
        double mejorD = Double.MAX_VALUE;
        for (int dx = -radio; dx <= radio; dx++) {
            for (int dz = -radio; dz <= radio; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Location c = centro.getBlock().getLocation().add(dx + 0.5, dy, dz + 0.5);
                    double d = c.distanceSquared(centro);
                    if (d >= mejorD || d > radio * radio + 1) continue;
                    if (!libre(c, 2) || !c.clone().subtract(0, 1, 0).getBlock().getType().isSolid()) continue;
                    mejor = c;
                    mejorD = d;
                }
            }
        }
        return mejor;
    }

    static boolean libre(Location l, int alto) {
        for (int i = 0; i < alto; i++) {
            Block b = l.clone().add(0, i, 0).getBlock();
            if (!b.isPassable() || b.isLiquid()) return false;
        }
        return true;
    }

    private static Location mirando(Location desde, Location hacia) {
        Location l = desde.clone();
        Vector v = hacia.toVector().subtract(l.toVector());
        if (v.lengthSquared() > 1e-4) l.setDirection(v);
        return l;
    }

    // =============================================================== pendiente

    /**
     * Lo que se guarda de una PARCA que se fue a medias (datos parca.pendiente.<uuid>, sec. 8.1).
     * 1.8: foto, la de su dificultad (null en lo guardado antes de la 1.8: entonces, la de ahora).
     */
    record Pendiente(double fraccion, int fase, int nivel, int r, int m, long hasta, String motivo,
                     DificultadAmenaza.Foto foto) {
    }

    /** Sincrono (guardarYa): lo pide DIS sec. 8.1 al crearlo. */
    void guardarPendiente(ParcaViva pe, long hasta, String motivo) {
        if (pe.prueba() || pe.presa() == null || pe.cuerpo() == null) return;
        String base = "parca.pendiente." + pe.presa() + ".";
        hc.datos().set(base + "fraccion", Math.max(0.05, Amenazas.fraccion(pe.cuerpo())));
        hc.datos().set(base + "fase", pe.fase());
        hc.datos().set(base + "nivel", pe.nivel());
        hc.datos().set(base + "r", pe.repeticiones());
        hc.datos().set(base + "m", pe.extra());
        hc.datos().set(base + "hasta", hasta);
        hc.datos().set(base + "motivo", motivo);
        DificultadAmenaza.Foto f = pe.dificultad() == null ? null : pe.dificultad().foto();
        hc.datos().set(base + "dificultad", null);
        if (f != null) {
            hc.datos().set(base + "dificultad.bloques", Math.round(f.bloques()));
            hc.datos().set(base + "dificultad.cordura", f.cordura());
            hc.datos().set(base + "dificultad.segundos", f.segundosDentro());
        }
        hc.guardarYa();
    }

    Pendiente leerPendiente(UUID id) {
        ConfigurationSection s = hc.datos().getConfigurationSection("parca.pendiente." + id);
        if (s == null) return null;
        long hasta = s.getLong("hasta", 0);
        if (hasta <= System.currentTimeMillis()) {
            borrarPendiente(id);
            return null;
        }
        DificultadAmenaza.Foto foto = !s.isSet("dificultad.segundos") ? null
                : new DificultadAmenaza.Foto(s.getDouble("dificultad.bloques", 0),
                s.getDouble("dificultad.cordura", Cordura.MAXIMO), s.getInt("dificultad.segundos", 0));
        return new Pendiente(s.getDouble("fraccion", 1), s.getInt("fase", 1), s.getInt("nivel", 1),
                s.getInt("r", 0), s.getInt("m", 0), hasta, s.getString("motivo", "?"), foto);
    }

    void borrarPendiente(UUID id) {
        boolean habia = hc.datos().isSet("parca.pendiente." + id) || hc.datos().isSet("parca.marca." + id);
        if (!habia) return;
        hc.datos().set("parca.pendiente." + id, null);
        hc.datos().set("parca.marca." + id, null);
        hc.marcarSucio();
    }

    /**
     * Vuelve lo pendiente: a "distancia" bloques, con la vida, fase, N, r, M y dificultad que tenia.
     *
     * 1.2: devuelve false solo si no ha podido por la zona spawn (esta dentro, o detras y delante
     * cae dentro): entonces volverFuera lo intenta el segundo siguiente. Por lo demas (nada
     * pendiente, el tope global, apagada) true: como antes, no se insiste.
     */
    private boolean reaparecer(Player p, double distancia, String mensaje) {
        Pendiente pd = leerPendiente(p.getUniqueId());
        if (pd == null || persigue(p) || !p.isOnline() || !hc.esHardcore(p) || !hc.cuenta(p)) return true;
        if (hc.enSpawn(p)) return false;
        Ajustes a = ajustes();
        if (!a.activa || vivas() >= a.maximoSimultaneas) return true;   // lo pendiente sigue ahi
        Location sitio = sitioFuera(p, distancia);
        if (sitio == null) return false;
        // Con la dificultad con la que se fue: al volver, la cordura y los minutos dentro ya empiezan de cero.
        ParcaViva pe = nueva(a, p, List.of(p), pd.nivel(), pd.r(), pd.m(), sitio, pd.fraccion(), pd.fase(),
                pd.foto() != null ? pd.foto() : DificultadAmenaza.foto(hc, p));
        if (pe == null) return true;
        registrar(pe);
        borrarPendiente(p.getUniqueId());
        p.sendMessage(ComandoCalamity.mensaje(mensaje));
        Location l = pe.cuerpo().getLocation();
        hc.plugin().bitacora().anotar("parca", "vuelve", p.getName(), "N " + pd.nivel(), "r " + pd.r(), "M " + pd.m(),
                "vida " + Math.round(pd.fraccion() * 100) + " %", pd.motivo(),
                l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(), pe.tipo());
        retirarMinijefes(p);
        return true;
    }

    // ================================================================ segada

    /** Tierra segada (sec. 1.8): si mata a su presa, ese sitio no suelta nada tierra-segada-minutos. */
    private void segar(Location l) {
        Ajustes a = ajustes();
        if (a.tierraSegadaMinutos <= 0 || l.getWorld() == null) return;
        long hasta = System.currentTimeMillis() + a.tierraSegadaMinutos * 60_000L;
        segadas.add(new Segada(l.getWorld().getKey().toString(), l.getX(), l.getZ(), hasta));
        guardarSegadas();
    }

    private void cargarSegadas() {
        segadas.clear();
        long ahora = System.currentTimeMillis();
        for (Map<?, ?> m : hc.datos().getMapList("parca.segada")) {
            try {
                long hasta = ((Number) m.get("hasta")).longValue();
                if (hasta <= ahora) continue;
                segadas.add(new Segada(String.valueOf(m.get("mundo")), ((Number) m.get("x")).doubleValue(),
                        ((Number) m.get("z")).doubleValue(), hasta));
            } catch (Throwable ignorado) {
                // Una entrada mal escrita a mano se ignora: caduca sola en 30 min de todos modos.
            }
        }
    }

    private void podarSegadas() {
        long ahora = System.currentTimeMillis();
        if (segadas.removeIf(s -> s.hasta() <= ahora)) guardarSegadas();
    }

    private void guardarSegadas() {
        List<Map<String, Object>> lista = new ArrayList<>();
        for (Segada s : segadas) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mundo", s.mundo());
            m.put("x", s.x());
            m.put("z", s.z());
            m.put("hasta", s.hasta());
            lista.add(m);
        }
        hc.datos().set("parca.segada", lista.isEmpty() ? null : lista);
        hc.marcarSucio();
    }

    // ================================================================= botin

    /** La PARCA ha caido: reparto de sec. 1.10 por la Aduana, Bitacora y anuncio (P-20). */
    void pagar(ParcaViva pe, Map<UUID, Double> dano, double vida, long segundosPelea) {
        Ajustes a = ajustes();
        StringBuilder partes = new StringBuilder();
        for (Map.Entry<UUID, Double> e : dano.entrySet()) {
            if (partes.length() > 0) partes.append(",");
            partes.append(nombre(e.getKey())).append(":").append(Math.round(vida <= 0 ? 0 : e.getValue() / vida * 100)).append("%");
        }
        hc.plugin().bitacora().anotar("parca", "fin", pe.presaNombre(), "muerta", segundosPelea + " s", partes.toString());
        if (pe.prueba()) {
            hc.plugin().bitacora().anotar("parca", "botin", "-", "prueba", "sin botin");
            return;
        }
        long ahora = System.currentTimeMillis();
        OfflinePlayer presa = hc.plugin().getServer().getOfflinePlayer(pe.presa());
        List<Cobro> cobros = repartir(a, dano, vida, pe.presa(), pe.nivel(), pe.repeticiones(),
                id -> yaCobro(id, ahora, a),
                id -> hc.aduana() != null && hc.valor("aduana",
                        () -> hc.aduana().valida(hc.plugin().getServer().getOfflinePlayer(id), presa), false));

        List<String> nombres = new ArrayList<>();
        List<UUID> campanaPara = new ArrayList<>();
        boolean primeraDelDia = false;
        String hoy = hc.calendario() == null ? "" : hc.valor("calendario", () -> hc.calendario().dia(), "");
        for (Cobro c : cobros) {
            OfflinePlayer op = hc.plugin().getServer().getOfflinePlayer(c.id());
            Player online = op.getPlayer();
            boolean cuenta = c.id().equals(pe.presa()) ? c.fraccion() >= a.participacionPresa : c.fraccion() >= a.participacionMinima;
            if (cuenta) nombres.add(nombre(c.id()));
            if (c.motivo() != null) {
                hc.plugin().bitacora().anotar("parca", "botin", nombre(c.id()), "esencias 0", "reliquia -", c.motivo());
                if (online != null) {
                    // 1.7.6: todo el que no cobra lee por que. En "invalida", el mismo motivo que da la
                    // Aduana (horas o conexion compartida), dicho con claridad.
                    String porQue = !"invalida".equals(c.motivo()) || hc.aduana() == null ? ""
                            : hc.valor("aduana", () -> hc.aduana().motivoInvalida(op, presa), "");
                    String texto = sinCobro(a, c.motivo(), c.id().equals(pe.presa()), porQue,
                            hc.cfg().getInt("aduana.horas-minimas", 10));
                    if (texto != null) online.sendMessage(ComandoCalamity.mensaje(texto));
                }
                continue;
            }
            List<ItemStack> reliquias = new ArrayList<>();
            if (c.grado() > 0 && hc.reliquias() != null) {
                ItemStack campana = hc.valor("reliquias",
                        () -> hc.reliquias().crear(c.grado(), "parca", "campana-parca", pe.nivel(), null, false), null);
                if (campana != null) {
                    reliquias.add(campana);
                    campanaPara.add(c.id());
                }
            }
            // La fecha del cobro anterior dice si es su primera PARCA cobrada del dia (P-20 a todo el servidor).
            long antes = hc.datos().getLong("parca.cobro." + c.id(), 0);
            if (antes == 0 || hc.calendario() == null
                    || !hc.valor("calendario", () -> hc.calendario().dia(antes), "").equals(hoy)) {
                primeraDelDia = true;
            }
            Aduana.Pago pago = hc.aduana() == null ? null : hc.valor("aduana",
                    () -> hc.aduana().pagar(op, "parca", c.esencias(), 0L, reliquias, "parca N " + pe.nivel()), null);
            int pagadas = pago == null ? 0 : pago.esencias();
            hc.datos().set("parca.cobro." + c.id(), ahora);
            if (hc.estadisticas() != null) hc.seguro("estadisticas", () -> hc.estadisticas().sumar(c.id(), "parcas", 1));
            hc.plugin().bitacora().anotar("parca", "botin", nombre(c.id()),
                    "esencias " + pagadas + (pagadas != c.esencias() ? " (calculadas " + c.esencias() + ")" : ""),
                    "reliquia " + (reliquias.isEmpty() ? "-" : romano(c.grado())), c.id().equals(pe.presa()) ? "presa" : "ayudante");
            if (online != null) {
                // 1.7.6: si la Aduana no ha pagado por su tope diario, ya le ha dicho por que ("Hoy ya
                // has cobrado el maximo por esto."): no se anuncia un botin de +0 ni una Campana que no llega.
                if (pago == null || !pago.topado()) {
                    online.sendMessage(ComandoCalamity.mensaje(Component.text("Botín de la Parca: ")
                            .append(Paleta.cifra("+" + pagadas + (pagadas == 1 ? " Esencia" : " Esencias")))
                            .append(reliquias.isEmpty() ? Component.text(".")
                                    : Component.text(" y una ").append(Paleta.detalle("Campana de la Parca")).append(Component.text(".")))));
                }
                // Sangre fresca (M12): la cordura por la PARCA, con su tope de la Aduana.
                Combate cb = hc.combate();
                String idPelea = pe.cuerpo() == null ? String.valueOf(pe.presa()) : pe.cuerpo().getUniqueId().toString();
                if (cb != null) hc.seguro("combate", () -> cb.sangreFresca(online, "parca", "parca:" + idPelea));
            }
            raro(op, a);
        }
        hc.guardarYa();

        if (!nombres.isEmpty()) {
            String quienes = lista(nombres);
            Component anuncio = ComandoCalamity.mensaje(Component.text(quienes, Paleta.DETALLE)
                    .append(Component.text(nombres.size() == 1 ? " ha derrotado a la Parca." : " han derrotado a la Parca.")));
            for (Player o : hc.plugin().getServer().getOnlinePlayers()) {
                if (primeraDelDia || hc.esHardcore(o)) o.sendMessage(anuncio);
            }
        }
        telemetria("muere", pe, dano, campanaPara);
    }

    private boolean yaCobro(UUID id, long ahora, Ajustes a) {
        long ultimo = hc.datos().getLong("parca.cobro." + id, 0);
        return ultimo > 0 && ahora - ultimo < a.horasEntreCobros * 3_600_000L;
    }

    /** Botin raro (hueco del plan de recompensas; 0 de serie). Comandos con %player% validado. */
    private void raro(OfflinePlayer op, Ajustes a) {
        if (a.raroProbabilidad <= 0 || a.raroComandos.isEmpty() || op.getName() == null) return;
        if (AZAR.nextDouble() >= a.raroProbabilidad) return;
        String nombre = op.getName();
        // Un nombre raro no puede colar nada en un comando de consola.
        if (!nombre.matches("[A-Za-z0-9_]{1,16}")) return;
        for (String c : a.raroComandos) {
            String cmd = c.replace("%player%", nombre).replace("%jugador%", nombre);
            hc.plugin().getServer().dispatchCommand(hc.plugin().getServer().getConsoleSender(), cmd);
        }
        hc.plugin().bitacora().anotar("parca", "raro", nombre);
    }

    private String nombre(UUID id) {
        OfflinePlayer op = hc.plugin().getServer().getOfflinePlayer(id);
        return op.getName() == null ? id.toString().substring(0, 8) : op.getName();
    }

    private static String lista(List<String> n) {
        if (n.size() == 1) return n.get(0);
        return String.join(", ", n.subList(0, n.size() - 1)) + " y " + n.get(n.size() - 1);
    }

    static String romano(int g) {
        return switch (g) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> String.valueOf(g);
        };
    }

    /** Suceso "parca" de la telemetria (MED sec. 3): accion nace/muere/se-va/cosecha. */
    void telemetria(String accion, ParcaViva pe, Map<UUID, Double> dano, List<UUID> campanaPara) {
        Telemetria t = hc.telemetria();
        if (t == null || pe.prueba()) return;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("accion", accion);
        c.put("presa", String.valueOf(pe.presa()));
        c.put("N", pe.nivel());
        c.put("r", pe.repeticiones());
        List<Map<String, Object>> parts = new ArrayList<>();
        if (dano != null) {
            double vida = pe.vidaFinal();
            for (Map.Entry<UUID, Double> e : dano.entrySet()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("uuid", e.getKey().toString());
                x.put("dano_pct", vida <= 0 ? 0 : Math.round(e.getValue() / vida * 1000) / 10.0);
                parts.add(x);
            }
        }
        c.put("participantes", parts);
        c.put("bonus_grupo", pe.extrasGrupo());
        List<String> campanas = new ArrayList<>();
        if (campanaPara != null) for (UUID u : campanaPara) campanas.add(u.toString());
        c.put("campana_para", campanas);
        OfflinePlayer quien = pe.presa() == null ? null : hc.plugin().getServer().getOfflinePlayer(pe.presa());
        hc.seguro("telemetria", () -> t.suceso("parca", quien, c));
    }

    // =============================================================== listener

    /** Muere la PARCA: su botin. Muere un mob cualquiera donde ella cosecha: no suelta nada. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onMuerte(EntityDeathEvent e) {
        LivingEntity muerto = e.getEntity();
        if (!hc.esHardcore(muerto.getWorld())) return;
        if (muerto instanceof Player) return;
        if (muerto instanceof Mannequin && muerto.getPersistentDataContainer().has(Marcas.CASCARA, PersistentDataType.STRING)) {
            // El cuerpo que se ve, muerto a mano (/kill): ni guadana ni experiencia en el suelo.
            e.getDrops().clear();
            e.setDroppedExp(0);
            return;
        }
        if (Marcas.esAmenaza(muerto)) {
            ParcaViva pe = deCuerpo(muerto);
            if (pe != null) hc.seguro("parca", pe::alMorir);
            return;
        }
        if (peleas.isEmpty() && segadas.isEmpty()) return;
        if (cosechando(muerto.getLocation())) {
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
    }

    /** P-19: el mensaje de muerte de quien se llevo ella. Corre despues de Hardcore.onMuerte (misma prioridad, registrado despues). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMuerteJugador(PlayerDeathEvent e) {
        if (!segadosPorElla.remove(e.getEntity().getUniqueId())) return;
        e.deathMessage(Component.text(e.getEntity().getName() + " se quedó quieto en Calamity. La Parca se lo llevó.",
                Paleta.AVISO));
    }

    /**
     * Golpes: lo que recibe la PARCA (x0,5 con planideras, +25 % aturdida o tocando la
     * campanada; Amenazas escala y topa despues, en HIGHEST) y lo que da (el reloj del Paso
     * Umbral y del atasco). Las planideras ya no pegan: lloran (PeleaParca.lamentos).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGolpe(EntityDamageByEntityEvent e) {
        if (peleas.isEmpty() || !hc.esHardcore(e.getEntity().getWorld())) return;
        Entity victima = e.getEntity();
        Entity autor = e.getDamager();
        if (autor instanceof Projectile pr && pr.getShooter() instanceof Entity tirador) autor = tirador;

        ParcaViva recibe = Marcas.esAmenaza(victima) ? deCuerpo(victima) : null;
        if (recibe != null) {
            double f = recibe.factorRecibido();
            if (f != 1.0) e.setDamage(e.getDamage() * f);
            if (autor instanceof Player j) recibe.golpeadaPor(j);
            return;
        }
        if (!(victima instanceof Player v)) return;
        ParcaViva da = deCuerpo(autor);
        if (da != null) {
            da.haGolpeado();
            // El esqueleto que golpea es invisible: el tajo lo tiene que dar el cuerpo que se ve.
            da.blandir();
        }
    }

    /**
     * Las planideras son withers: nada de calaveras. Sin IA no disparan, pero si otro plugin
     * les devuelve la IA o las usa de tirador, el proyectil no sale.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDisparo(ProjectileLaunchEvent e) {
        if (peleas.isEmpty()) return;
        if (e.getEntity().getShooter() instanceof Entity t && "planidera".equals(Marcas.amenaza(t))) e.setCancelled(true);
    }

    /** Ni la explosion del wither (la de nacer invocado, o la que otro plugin le pida). */
    @EventHandler(ignoreCancelled = true)
    public void onExplotar(ExplosionPrimeEvent e) {
        if (!peleas.isEmpty() && "planidera".equals(Marcas.amenaza(e.getEntity()))) e.setCancelled(true);
    }

    /**
     * El cuerpo que se ve (Mannequin con skin) no recibe dano propio: el golpe de un jugador
     * pasa al esqueleto invisible con el mismo autor, y ahi Amenazas lo escala, lo topa y lo
     * apunta para el botin (y onGolpe le aplica las planideras o la campanada). Lo demas
     * (asfixia al subir del suelo, fuego, caidas, explosiones) se cancela sin mas. /kill pasa:
     * si un admin lo mata, la pelea sigue con el esqueleto visible.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDanoCascara(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Mannequin mq)) return;
        if (!mq.getPersistentDataContainer().has(Marcas.CASCARA, PersistentDataType.STRING)) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.KILL) return;
        e.setCancelled(true);
        Entity causa;
        try {
            causa = e.getDamageSource().getCausingEntity();
        } catch (Throwable t) {
            causa = null;
        }
        if (!(causa instanceof Player p)) return;
        ParcaViva pe = deCascara(mq);
        if (pe == null || pe.cuerpo() == null || !pe.cuerpo().isValid() || pe.cuerpo().isDead()) return;
        pe.cuerpo().damage(e.getDamage(), p);
    }

    /** Ni chapas, ni riendas, ni nada con clic derecho sobre el cuerpo que se ve. */
    @EventHandler(ignoreCancelled = true)
    public void onTocarCascara(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof Mannequin mq
                && mq.getPersistentDataContainer().has(Marcas.CASCARA, PersistentDataType.STRING)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTocarCascaraEn(PlayerInteractAtEntityEvent e) {
        onTocarCascara(e);
    }

    /** Dano de verdad a la PARCA (ya escalado y topado): el cuerpo que se ve se estremece. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDolor(EntityDamageEvent e) {
        if (peleas.isEmpty() || e.getFinalDamage() <= 0 || !Marcas.esAmenaza(e.getEntity())) return;
        ParcaViva pe = deCuerpo(e.getEntity());
        if (pe != null) pe.dolor();
    }

    /** Los demas pueden pegarle, pero no la desvian: solo apunta a sus marcados (salvo en prueba). */
    @EventHandler(ignoreCancelled = true)
    public void onObjetivo(EntityTargetLivingEntityEvent e) {
        if (peleas.isEmpty() || !Marcas.esAmenaza(e.getEntity())) return;
        ParcaViva pe = deCuerpo(e.getEntity());
        if (pe == null || pe.prueba() || e.getTarget() == null) return;
        if (!pe.marcados().contains(e.getTarget().getUniqueId()) && pe.hayMarcadoEnMundo()) e.setCancelled(true);
    }

    /** Un marcado no se sube a nada mientras viva (P-11). */
    @EventHandler(ignoreCancelled = true)
    public void onVehiculo(VehicleEnterEvent e) {
        if (peleas.isEmpty() || !(e.getEntered() instanceof Player p) || !hc.esHardcore(p)) return;
        if (!persigue(p)) return;
        e.setCancelled(true);
        hc.cordura().destello(p, Component.text("Mientras la Parca te persiga, no puedes subirte a nada.", Paleta.TEXTO), 2);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMontar(EntityMountEvent e) {
        if (peleas.isEmpty() || !(e.getEntity() instanceof Player p) || !hc.esHardcore(p)) return;
        if (!persigue(p)) return;
        e.setCancelled(true);
        hc.cordura().destello(p, Component.text("Mientras la Parca te persiga, no puedes subirte a nada.", Paleta.TEXTO), 2);
    }

    /** La cosecha es suya: lo que pesca un marcado no sale del agua. */
    @EventHandler(ignoreCancelled = true)
    public void onPescar(PlayerFishEvent e) {
        if (peleas.isEmpty() || e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        Player p = e.getPlayer();
        if (!hc.esHardcore(p) || !persigue(p)) return;
        if (e.getCaught() instanceof Item it) it.remove();
        e.setExpToDrop(0);
    }

    /** Y lo que rompe un marcado cerca de ella no suelta nada. */
    @EventHandler(ignoreCancelled = true)
    public void onRomper(BlockDropItemEvent e) {
        if (peleas.isEmpty()) return;
        Player p = e.getPlayer();
        if (!hc.esHardcore(p)) return;
        ParcaViva pe = de(p.getUniqueId());
        if (pe == null || pe.cuerpo() == null || pe.cuerpo().getWorld() != p.getWorld()) return;
        double r = ajustes().radioCosechaBloques;
        if (pe.cuerpo().getLocation().distanceSquared(e.getBlock().getLocation()) <= r * r) e.getItems().clear();
    }

    // ================================================================ comando

    /**
     * /lw hardcore parca ... (DIS sec. 6):
     *   parca <jugador> [segundos]    pone su quieto (el limite por defecto = la invoca ya)
     *   parca info <jugador>          lo que sabe la Huella y lo pendiente
     *   parca vida <0-1>              vida de la PARCA mas cercana (probar fases)
     *   parca habilidad <nombre>      la mas cercana suelta esa habilidad ya (ver los avisos)
     *   parca retirar [jugador]       una o todas, sin botin
     *   parca prueba <x> <y> <z> [N]  en el primer mundo hardcore, sin presa
     */
    private void comando(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(Component.text("Uso: /calamidad parca <jugador> [segundos] | info <jugador> | vida <0-1>"
                    + " | habilidad <nombre> | retirar [jugador] | prueba <x> <y> <z> [N] | anomalia", Paleta.AVISO));
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> info(quien, args);
            case "vida" -> vida(quien, args);
            case "habilidad" -> habilidad(quien, args);
            case "retirar" -> retirar(quien, args);
            case "prueba" -> prueba(quien, args);
            case "anomalia" -> decir(quien, anomalia == null ? "anomalia | EDM no trae las clases de anomalías: siempre sale la de reserva"
                    : anomalia.estado());
            default -> forzar(quien, args);
        }
    }

    private List<String> tab(String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 2) {
            out.addAll(List.of("info", "vida", "habilidad", "retirar", "prueba", "anomalia"));
            for (Player p : hc.plugin().getServer().getOnlinePlayers()) out.add(p.getName());
        } else if (args.length == 3 && (args[1].equalsIgnoreCase("info") || args[1].equalsIgnoreCase("retirar"))) {
            for (Player p : hc.plugin().getServer().getOnlinePlayers()) out.add(p.getName());
        } else if (args.length == 3 && args[1].equalsIgnoreCase("vida")) {
            out.addAll(List.of("0.5", "0.2"));
        } else if (args.length == 3 && args[1].equalsIgnoreCase("habilidad")) {
            out.addAll(HabilidadParca.nombres());
        }
        return out;
    }

    private void decir(CommandSender quien, String linea) {
        quien.sendMessage(Component.text(linea, Paleta.TENUE));
    }

    private void forzar(CommandSender quien, String[] args) {
        Player p = hc.plugin().getServer().getPlayerExact(args[1]);
        if (p == null) {
            quien.sendMessage(Component.text("No encuentro a ese jugador.", Paleta.AVISO));
            return;
        }
        if (!hc.esHardcore(p)) {
            quien.sendMessage(Component.text(p.getName() + " no está en un mundo hardcore.", Paleta.AVISO));
            return;
        }
        int limite = hc.huella() == null ? 600 : hc.huella().limite(p);
        int s;
        try {
            s = args.length > 2 ? Integer.parseInt(args[2]) : limite;
        } catch (NumberFormatException ex) {
            quien.sendMessage(Component.text("Segundos no válidos.", Paleta.AVISO));
            return;
        }
        if (hc.huella() == null) {
            quien.sendMessage(Component.text("La Huella no está en marcha.", Paleta.AVISO));
            return;
        }
        hc.huella().forzar(p, Math.max(0, s));
        decir(quien, "parca | " + p.getName() + " | quieto " + hc.huella().quieto(p) + " s"
                + (hc.huella().quieto(p) >= limite ? " | llega en el siguiente segundo" : ""));
        hc.plugin().bitacora().anotar("parca", "forzar", p.getName(), s + " s", quien.getName());
    }

    private void info(CommandSender quien, String[] args) {
        if (args.length < 3) {
            quien.sendMessage(Component.text("Uso: /calamidad parca info <jugador>", Paleta.AVISO));
            return;
        }
        OfflinePlayer op = hc.plugin().getServer().getOfflinePlayerIfCached(args[2]);
        Player p = hc.plugin().getServer().getPlayerExact(args[2]);
        if (op == null && p == null) {
            quien.sendMessage(Component.text("No encuentro a ese jugador.", Paleta.AVISO));
            return;
        }
        UUID id = p != null ? p.getUniqueId() : op.getUniqueId();
        String nombre = p != null ? p.getName() : op.getName();
        decir(quien, "parca | " + nombre + " | " + (p != null && hc.huella() != null ? hc.huella().info(p) : "desconectado"));
        Pendiente pd = leerPendiente(id);
        long marca = hc.datos().getLong("parca.marca." + id, 0);
        long ahora = System.currentTimeMillis();
        ParcaViva pe = de(id);
        decir(quien, "parca | " + nombre + " | r " + repeticiones(id)
                + " | persigue " + (pe == null ? "no" : (id.equals(pe.presa()) ? "presa" : "marcado") + " " + pe.tipo() + " N " + pe.nivel()
                + " fase " + pe.fase() + " vida " + Math.round(Amenazas.fraccion(pe.cuerpo()) * 100) + " %")
                + " | pendiente " + (pd == null ? "no" : pd.motivo() + " " + Math.round(pd.fraccion() * 100) + " % hasta "
                + (pd.hasta() - ahora) / 60_000 + " min")
                + " | marca " + (marca > ahora ? (marca - ahora) / 60_000 + " min" : "no")
                + " | cobro " + (yaCobro(id, ahora, ajustes()) ? "hecho" : "libre"));
        // 1.8: lo que la endurece (DificultadAmenaza), con la foto de su presa al aparecer.
        if (pe != null) decir(quien, "parca | " + nombre + " | dificultad | " + pe.dificultad().texto());
    }

    private ParcaViva masCercana(CommandSender quien) {
        ParcaViva mejor = null;
        double mejorD = Double.MAX_VALUE;
        for (ParcaViva pe : peleas) {
            if (!pe.vivaParaJugadores() || pe.cuerpo() == null) continue;
            double d = 0;
            if (quien instanceof Player p) {
                d = p.getWorld() == pe.cuerpo().getWorld() ? p.getLocation().distanceSquared(pe.cuerpo().getLocation())
                        : Double.MAX_VALUE / 2;
            }
            // Desde consola, la ultima invocada.
            if (d <= mejorD) {
                mejor = pe;
                mejorD = d;
            }
        }
        return mejor;
    }

    private void vida(CommandSender quien, String[] args) {
        double f;
        try {
            f = Double.parseDouble(args.length > 2 ? args[2] : "x");
        } catch (NumberFormatException ex) {
            quien.sendMessage(Component.text("Uso: /calamidad parca vida <0-1>", Paleta.AVISO));
            return;
        }
        ParcaViva pe = masCercana(quien);
        if (pe == null) {
            quien.sendMessage(Component.text("No hay ninguna Parca viva.", Paleta.AVISO));
            return;
        }
        hc.amenazas().ponerFraccion(pe.cuerpo(), Math.max(0.01, Math.min(1, f)));
        pe.revisarFase();
        decir(quien, "parca | vida " + Math.round(Amenazas.fraccion(pe.cuerpo()) * 100) + " % | fase " + pe.fase());
    }

    private void habilidad(CommandSender quien, String[] args) {
        ParcaViva pe = masCercana(quien);
        if (pe == null) {
            quien.sendMessage(Component.text("No hay ninguna Parca viva.", Paleta.AVISO));
            return;
        }
        String nombre = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        String no = pe.forzar(nombre, quien instanceof Player p ? p : null);
        if (no != null) {
            quien.sendMessage(Component.text("parca | habilidad | " + no, Paleta.AVISO));
            return;
        }
        decir(quien, "parca | habilidad | " + nombre + " | fase " + pe.fase());
        hc.plugin().bitacora().anotar("parca", "habilidad", nombre, quien.getName());
    }

    private void retirar(CommandSender quien, String[] args) {
        List<ParcaViva> cuales = new ArrayList<>();
        if (args.length > 2) {
            Player p = hc.plugin().getServer().getPlayerExact(args[2]);
            ParcaViva pe = p == null ? null : de(p.getUniqueId());
            if (pe == null) {
                quien.sendMessage(Component.text("Ese jugador no tiene ninguna Parca encima.", Paleta.AVISO));
                return;
            }
            cuales.add(pe);
        } else {
            for (ParcaViva pe : peleas) if (pe.vivaParaJugadores()) cuales.add(pe);
        }
        for (ParcaViva pe : cuales) pe.irse("admin:" + quien.getName(), null);
        decir(quien, "parca | retiradas " + cuales.size());
    }

    private void prueba(CommandSender quien, String[] args) {
        if (args.length < 5) {
            quien.sendMessage(Component.text("Uso: /calamidad parca prueba <x> <y> <z> [N]", Paleta.AVISO));
            return;
        }
        World w = null;
        for (String m : hc.mundos()) {
            for (World x : hc.plugin().getServer().getWorlds()) {
                if (hc.esHardcore(x) && x.getKey().getKey().equals(m)) w = x;
            }
            if (w != null) break;
        }
        if (w == null) {
            quien.sendMessage(Component.text("No hay ningún mundo hardcore cargado.", Paleta.AVISO));
            return;
        }
        double x, y, z;
        int n;
        try {
            x = Double.parseDouble(args[2]);
            y = Double.parseDouble(args[3]);
            z = Double.parseDouble(args[4]);
            n = args.length > 5 ? Integer.parseInt(args[5]) : 50;
        } catch (NumberFormatException ex) {
            quien.sendMessage(Component.text("Coordenadas o nivel no válidos.", Paleta.AVISO));
            return;
        }
        Ajustes a = ajustes();
        n = Math.max(1, Math.min(100, n));
        // A ras de suelo: unas coordenadas a mano suelen caer en el aire o dentro del terreno.
        Location sitio = Fx.ground(new Location(w, x, y, z), 40);
        // Sin presa: N tal cual (sin el extra-nivel, que ya lo pone quien prueba), r = 0, M = 0.
        ParcaViva pe = PeleaParca.crear(this, a, null, "prueba", List.of(), n, 0, 0, sitio, true, 1.0, 1,
                DificultadAmenaza.NEUTRO);
        if (pe == null) {
            quien.sendMessage(Component.text("parca | prueba | no ha salido (spawn cancelado o chunk sin cargar)", Paleta.AVISO));
            return;
        }
        peleas.add(pe);
        Location l = pe.cuerpo().getLocation();
        hc.plugin().bitacora().anotar("parca", "llega", "prueba", "N " + n, "r 0", "M 0",
                l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(), "celdas 0", "vehiculo no");
        decir(quien, "parca | prueba | N " + n + " | vida lógica " + Math.round(hc.amenazas().vidaLogicaMaxima(pe.cuerpo()))
                + " | escala " + Math.round(hc.amenazas().escala(pe.cuerpo()) * 1000) / 1000.0);
    }

    // =============================================================== autotest

    /** DIS sec. 1.6 y 1.10 con los valores por defecto (no la config del servidor). */
    static List<String> autotest() {
        Ajustes a = new Ajustes(new YamlConfiguration());
        Autotest.Hoja h = new Autotest.Hoja();
        h.cerca("N 14 -> vida 920", 920, vidaLogica(a, 14, 0, 0), 1e-6);
        h.cerca("N 14 -> golpe 16,72", 16.72, golpe(a, 14, 0), 1e-6);
        double v52 = vidaLogica(a, 52, 0, 0);
        h.cerca("N 52 -> vida 2440", 2440, v52, 1e-6);
        h.cerca("N 52 -> entidad 1024", 1024, Math.min(Amenazas.VIDA_MAXIMA_ENTIDAD, v52), 1e-9);
        h.cerca("N 52 -> escala 0,42", 0.42, Amenazas.escalaPara(v52), 0.005);
        h.cerca("N 100 -> vida 4360", 4360, vidaLogica(a, 100, 0, 0), 1e-6);
        h.cerca("N 100 -> golpe 54,56", 54.56, golpe(a, 100, 0), 1e-6);
        h.igual("nivel = N0 + 10, tope 100", List.of(14, 100), List.of(nivel(a, 4), nivel(a, 97)));
        h.ok("2.o participante (ayudante con la presa) -> sube", subeGrupo(2, false, 0, a));
        h.ok("1.er participante o la presa -> no sube", !subeGrupo(1, false, 0, a) && !subeGrupo(2, true, 0, a));
        h.ok("grupo como mucho 4 veces", !subeGrupo(6, false, 4, a));
        h.cerca("grupo x1,25 (N 14 -> 1150)", 1150, vidaLogica(a, 14, 0, 0) * (1 + a.grupoExtra), 1e-6);
        h.cerca("tope por golpe 8 % (N 52: 1000 -> 195,2)", 195.2, Amenazas.golpeLogico(1000, a.topeGolpeFraccion, v52), 1e-6);
        h.cerca("repeticion: r 1 -> vida x1,25 (1150)", 1150, vidaLogica(a, 14, 1, 0), 1e-6);
        h.cerca("repeticion: r 1 -> R 1,5 (golpe 25,08)", 25.08, golpe(a, 14, 1), 1e-6);
        h.cerca("R tope 3,0 (r 4 y r 9)", 6.0, factorR(a, 4) + factorR(a, 9), 1e-9);
        h.cerca("marcado extra -> vida x1,5", 1380, vidaLogica(a, 14, 0, 1), 1e-6);
        h.cerca("siega a 20 de vida: 4 (quieto 8)", 12, siegaFraccion(a, 1, false) * 20 + siegaFraccion(a, 1, true) * 20, 1e-9);
        h.cerca("siega tope 60 % con R 3 quieto", 0.60, siegaFraccion(a, 3, true), 1e-9);
        h.cerca("sentencia 50 %, tope 90 % con R 2", 1.40, sentenciaFraccion(a, 1) + sentenciaFraccion(a, 2), 1e-9);
        h.igual("esencias presa N 14/52/100", List.of(5, 9, 14),
                List.of(esenciasPresa(a, 14), esenciasPresa(a, 52), esenciasPresa(a, 100)));
        h.igual("esencias ayudante N 14/52/100", List.of(2, 4, 7),
                List.of(esenciasAyudante(a, 14), esenciasAyudante(a, 52), esenciasAyudante(a, 100)));
        h.igual("Campana II/III/IV por N", List.of(2, 3, 3, 4),
                List.of(gradoCampana(a, 14), gradoCampana(a, 25), gradoCampana(a, 49), gradoCampana(a, 52)));

        // Reparto: presa con 20 %, cuatro ayudantes validos, uno con 5 %, uno de su misma IP, uno que ya cobro.
        UUID presa = Autotest.sintetico(1);
        UUID a1 = Autotest.sintetico(2), a2 = Autotest.sintetico(3), a3 = Autotest.sintetico(4), a4 = Autotest.sintetico(5);
        UUID flojo = Autotest.sintetico(6), alt = Autotest.sintetico(7), repe = Autotest.sintetico(8);
        Map<UUID, Double> dano = new HashMap<>();
        double vida = 1000;
        dano.put(presa, 300.0);
        dano.put(a1, 200.0);
        dano.put(a2, 150.0);
        dano.put(a3, 120.0);
        dano.put(a4, 110.0);
        dano.put(flojo, 50.0);
        dano.put(alt, 400.0);
        dano.put(repe, 130.0);
        List<Cobro> c = repartir(a, dano, vida, presa, 52, 0, id -> id.equals(repe), id -> !id.equals(alt));
        Map<UUID, Cobro> por = new HashMap<>();
        for (Cobro x : c) por.put(x.id(), x);
        h.ok("presa r 0 con 30 % -> 9 E sin Campana", por.get(presa).motivo() == null && por.get(presa).esencias() == 9
                && por.get(presa).grado() == 0);
        h.ok("misma IP -> no cobra (invalida)", "invalida".equals(por.get(alt).motivo()));
        h.ok("5 % -> no cobra", "poco-dano".equals(por.get(flojo).motivo()));
        h.ok("ya cobro en 24 h -> no cobra", "ya-cobro".equals(por.get(repe).motivo()));
        h.ok("Campana IV solo al top-3 de ayudantes", por.get(a1).grado() == 4 && por.get(a2).grado() == 4
                && por.get(a3).grado() == 4 && por.get(a4).grado() == 0 && por.get(a4).esencias() == 4);
        List<Cobro> c2 = repartir(a, dano, vida, presa, 52, 1, id -> false, id -> true);
        h.ok("presa con r 1 -> sin Esencias", c2.stream().anyMatch(x -> x.id().equals(presa) && "repeticion".equals(x.motivo())));
        h.ok("orden por dano", c.get(0).id().equals(alt) && c.get(c.size() - 1).id().equals(flojo));
        // 1.7.6: quien no cobra lee el motivo, con las cifras de la config.
        h.igual("sin cobro: 5 % de ayudante", "Esta Parca no te paga: tu daño no llegó al mínimo (10 % de su vida).",
                sinCobro(a, por.get(flojo).motivo(), false, "", 10));
        h.igual("sin cobro: ya cobro en 24 h", "Esta Parca no te paga: ya cobraste por otra en las últimas 24 h.",
                sinCobro(a, por.get(repe).motivo(), false, "", 10));
        h.ok("sin cobro: cada motivo del reparto tiene su linea", java.util.stream.Stream.concat(c.stream(), c2.stream())
                .allMatch(x -> x.motivo() == null || sinCobro(a, x.motivo(), x.id().equals(presa), "", 10) != null));
        return h.lineas();
    }
}
