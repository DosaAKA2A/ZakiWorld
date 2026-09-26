package net.ederus.lethalworld.hardcore;

import net.ederus.edm.anomaly.minions.MinionManager;
import net.ederus.lethalworld.MobsLethal;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * M7 · Parte de defuncion: al morir en Calamity, los ultimos golpes, la cordura, los niveles
 * de mas que llevaban los mobs y UNA linea de "por que" (DIS M7, mensajes P-D01 a P-D09).
 *
 * Existe para bajar la frustracion: quien entiende por que ha muerto vuelve a arriesgar
 * equipo (que es lo que mide el censo), y quien no lo entiende deja de entrar. Tambien es
 * la herramienta de prueba de todo lo demas: cada golpe gordo de la PARCA o del Eco se lee
 * aqui con su numero.
 *
 * El anillo guarda los golpes de cada jugador en Calamity (EntityDamageEvent en MONITOR) y
 * los del dano verdadero, que llegan por anotar() porque setHealth no genera evento. Vive
 * solo en memoria: se limpia al cerrar el parte, en el quit y al salir del mundo.
 */
final class ParteDefuncion implements Listener {

    /** La marca de clase que MobsLethal pone a sus mobs ("minijefe", "destacado"...). */
    private static final NamespacedKey CLASE_MOB = new NamespacedKey("edm", "lethal_world_mob");
    /** Donde MinionManager guarda el nombre de un mob adoptado (gson). Su clave es privada. */
    private static final NamespacedKey NOMBRE_ESBIRRO = new NamespacedKey("edm", "esbirro_nombre");

    /** Un golpe del anillo. cuando en millis; dano en puntos de vida (no corazones). */
    record Golpe(long cuando, String causa, String atacante, int nivel, double dano, List<String> marcas,
                 double cordura, String amenaza, String ecoDe, boolean deJugador, boolean minijefe) {
    }

    /**
     * Lo que se sabe de una muerte para elegir el "por que". Es un valor para que el orden
     * de DIS M7 se pruebe en memoria (autotest "parte") sin matar a nadie.
     *
     * @param parca     el golpe mortal (o el ultimo del anillo) es de una PARCA o una planidera
     * @param cordura   cordura al morir, 0-100
     * @param ecoDe     nombre del dueno del Eco que le mato, o null
     * @param asesino   nombre del jugador que le mato, o null
     * @param causa     causa vanilla del ultimo golpe (FALL, DROWNING, STARVATION...)
     * @param nivelMob  nivel del mob que le mato (0 = no era un mob con nivel)
     * @param nivelSuyo su nivel (MobsLethal.nivelBase: rango y poder, sin los extras de dentro)
     * @param cable     se desconecto con la etiqueta de combate (Combate.cable)
     */
    record Causas(boolean parca, double cordura, String ecoDe, String asesino, DamageCause causa,
                  int nivelMob, int nivelSuyo, boolean cable) {
    }

    /** El "por que" elegido: su id (P-D0x, para la Bitacora y la telemetria) y el texto. */
    record PorQue(String id, String texto) {
    }

    private record Pendiente(long cuando, List<Component> lineas) {
    }

    private final Hardcore hc;
    private final Map<UUID, ArrayDeque<Golpe>> anillos = new HashMap<>();
    /**
     * Partes de quien huyo por el cable: se le ensenan al volver, que es cuando puede leerlos.
     * Solo en memoria (un reinicio los pierde, y no pasa nada: es un aviso, no un pago).
     */
    private final Map<UUID, Pendiente> pendientes = new HashMap<>();

    ParteDefuncion(Hardcore hc) {
        this.hc = hc;
        hc.plugin().getServer().getPluginManager().registerEvents(this, hc.plugin());
        Autotest.registrar("parte", this::autotest);
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = hc.cfg().getConfigurationSection("parte-defuncion");
        return s == null ? new YamlConfiguration() : s;
    }

    private boolean activo() {
        return cfg().getBoolean("activo", true);
    }

    private int capacidad() {
        return Math.max(1, cfg().getInt("golpes", 8));
    }

    private long ventanaMs() {
        return Math.max(1, cfg().getInt("segundos", 15)) * 1000L;
    }

    // ------------------------------------------------------------------ apuntar

    /**
     * Lo usa DanoVerdadero ANTES del golpe: setHealth no genera evento de dano y el parte no
     * lo veria. Si el golpe mata, onMuerte cierra el parte en el acto y ya esta apuntado.
     */
    void anotar(Player v, Entity fuente, double cantidad, String etiqueta) {
        if (v == null || !hc.esHardcore(v) || !activo()) return;
        String nombre = etiqueta == null || etiqueta.isBlank() ? "golpe" : etiqueta;
        List<String> marcas = new ArrayList<>();
        marcas.add("ignora armadura");
        // La etiqueta puede traer marcas detras de " · " ("Siega · quieto"): las pone la PARCA.
        String[] trozos = nombre.split(" · ");
        String habilidad = trozos[0].trim().toLowerCase(Locale.ROOT);
        for (int i = 1; i < trozos.length; i++) {
            String t = trozos[i].trim().toLowerCase(Locale.ROOT);
            if (t.equals("quieto")) marcas.add("x2 por quieto");
            else if (!t.isEmpty()) marcas.add(t);
        }
        // La Sentencia es la quinta campanada: quien lo lea tiene que saber de donde vino.
        // "juicio" era su nombre antes (el servidor tiene otro Juicio); se acepta por si acaso.
        if (habilidad.equals("juicio")) habilidad = "sentencia";
        if (habilidad.equals("sentencia")) marcas.add("campanada");
        apuntar(v, golpe(v, fuente, habilidad, cantidad, marcas));
    }

    /**
     * Todo golpe que de verdad entra a un jugador en Calamity. MONITOR: el dano ya es el
     * final, con la armadura, la penetracion y el x2 de caida puestos por Hardcore.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDano(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player v) || !hc.esHardcore(v) || !activo()) return;
        // El golpe letal del dano verdadero ya lo apunto anotar(): no se cuenta dos veces.
        if (DanoVerdadero.enCurso.contains(v.getUniqueId())) return;
        double dano = e.getFinalDamage();
        if (dano <= 0) return;
        Entity fuente = null;
        try {
            fuente = e.getDamageSource().getCausingEntity();
        } catch (Throwable sinFuente) {
            // Sin DamageSource: se queda en la causa.
        }
        List<String> marcas = new ArrayList<>();
        if (e.getCause() == DamageCause.FALL && hc.cfg().getDouble("dificultad.dano-caida", 2.0) > 1
                || e.getCause() == DamageCause.DROWNING && hc.cfg().getDouble("dificultad.dano-ahogo", 2.0) > 1) {
            marcas.add("x2 aquí");
        }
        apuntar(v, golpe(v, fuente, nombreCausa(e.getCause()), dano, marcas));
    }

    private void apuntar(Player v, Golpe g) {
        meter(anillos.computeIfAbsent(v.getUniqueId(), k -> new ArrayDeque<>()), g, capacidad());
    }

    /** El anillo: entra por el final y, lleno, se cae el mas viejo. */
    static void meter(ArrayDeque<Golpe> anillo, Golpe g, int capacidad) {
        anillo.addLast(g);
        while (anillo.size() > Math.max(1, capacidad)) anillo.removeFirst();
    }

    /** Los golpes de la ventana (los ultimos "segundos" antes de morir), del mas viejo al ultimo. */
    static List<Golpe> ventana(Iterable<Golpe> anillo, long ahora, long ventanaMs) {
        List<Golpe> out = new ArrayList<>();
        if (anillo == null) return out;
        for (Golpe g : anillo) if (ahora - g.cuando() <= ventanaMs) out.add(g);
        return out;
    }

    private Golpe golpe(Player v, Entity fuente, String causa, double dano, List<String> marcas) {
        Entity quien = autor(fuente);
        String atacante = null, amenaza = null, ecoDe = null;
        int nivel = 0;
        boolean jugador = false, minijefe = false;
        if (quien instanceof Player j) {
            atacante = j.getName();
            jugador = !j.equals(v);
        } else if (quien != null) {
            amenaza = Marcas.amenaza(quien);
            atacante = nombre(quien);
            nivel = nivelDe(quien);
            minijefe = "minijefe".equals(quien.getPersistentDataContainer().get(CLASE_MOB, PersistentDataType.STRING));
            if ("eco".equals(amenaza)) ecoDe = duenoEco(quien);
        }
        double cordura = hc.cordura().conoce(v) ? hc.cordura().valor(v) : Cordura.MAXIMO;
        return new Golpe(System.currentTimeMillis(), causa, atacante, nivel, dano, List.copyOf(marcas), cordura,
                amenaza, ecoDe, jugador, minijefe);
    }

    private static Entity autor(Entity fuente) {
        if (fuente instanceof Projectile pr && pr.getShooter() instanceof Entity tirador) return tirador;
        return fuente;
    }

    // -------------------------------------------------------------------- cerrar

    /** Desde Hardcore.onMuerte, lo primero: el inventario y la cordura aun estan. */
    void cerrar(PlayerDeathEvent e) {
        Player p = e.getEntity();
        ArrayDeque<Golpe> anillo = anillos.remove(p.getUniqueId());
        if (!activo() || !hc.esHardcore(p)) return;
        Entity asesino = null;
        try {
            asesino = e.getDamageSource().getCausingEntity();
        } catch (Throwable sinFuente) {
            // Se tira del anillo.
        }
        DamageCause causa = p.getLastDamageCause() == null ? null : p.getLastDamageCause().getCause();
        for (Component l : montar(p, anillo, asesino, causa, false)) p.sendMessage(l);
    }

    /**
     * P-D08: huyo por el cable. Lo llama Combate.cable en el quit, ANTES de vaciarle (la linea
     * del Eco cuenta lo que lleva). No puede leerlo ahora: se le guarda y se le ensena al volver.
     */
    void cable(Player p) {
        ArrayDeque<Golpe> anillo = anillos.remove(p.getUniqueId());
        if (!activo()) return;
        pendientes.put(p.getUniqueId(), new Pendiente(System.currentTimeMillis(), montar(p, anillo, null, null, true)));
    }

    /** Monta el parte, lo apunta en la Bitacora y la telemetria, y devuelve sus lineas. */
    private List<Component> montar(Player p, ArrayDeque<Golpe> anillo, Entity asesino, DamageCause causa, boolean cable) {
        long ahora = System.currentTimeMillis();
        List<Golpe> golpes = ventana(anillo, ahora, ventanaMs());
        Golpe ultimo = golpes.isEmpty() ? null : golpes.get(golpes.size() - 1);
        double cordura = hc.cordura().conoce(p) ? hc.cordura().valor(p) : Cordura.MAXIMO;
        PorQue porQue = porQue(causas(p, asesino, ultimo, causa, cordura, cable));

        List<Component> lineas = new ArrayList<>();
        lineas.add(ComandoCalamity.mensaje("Parte de defunción"));
        lineas.add(Component.text(" Cordura al morir: ", NamedTextColor.GRAY)
                .append(Component.text(Math.round(cordura) + " %", ComandoCalamity.ROJO))
                .append(Component.text("  ·  " + desglose(p), NamedTextColor.GRAY)));
        double total = 0;
        for (Golpe g : golpes) {
            total += g.dano();
            lineas.add(Component.text(" " + cifra(g.dano()) + "  ", ComandoCalamity.ROJO)
                    .append(Component.text(texto(g), NamedTextColor.GRAY)));
        }
        if (golpes.isEmpty()) {
            lineas.add(Component.text(" Nada te tocó en los últimos " + (ventanaMs() / 1000) + " s.", NamedTextColor.GRAY));
        }
        if (porQue != null) lineas.add(Component.text(" " + porQue.texto(), NamedTextColor.WHITE));
        String eco = lineaEco(p);
        if (eco != null) lineas.add(Component.text(" " + eco, NamedTextColor.WHITE));

        String id = porQue == null ? "-" : porQue.id();
        try {
            hc.plugin().bitacora().anotar("parte", p.getName(), "golpes " + golpes.size(),
                    "dano " + String.format(Locale.ROOT, "%.1f", total), "cordura " + Math.round(cordura),
                    "por " + id, cable ? "cable" : (causa == null ? "?" : causa.name().toLowerCase(Locale.ROOT)));
        } catch (Throwable sinBitacora) {
            // Apagando: el parte ya ha salido por chat.
        }
        Telemetria t = hc.telemetria();
        if (t != null) {
            Map<String, Object> campos = new LinkedHashMap<>();
            campos.put("golpes", golpes.size());
            campos.put("dano", Math.round(total * 10) / 10.0);
            campos.put("cordura", Math.round(cordura));
            campos.put("porque", id);
            campos.put("cable", cable);
            t.suceso("parte", p, campos);
        }
        return lineas;
    }

    /** Junta lo que decide el "por que" a partir del golpe mortal y del ultimo del anillo. */
    private Causas causas(Player p, Entity asesino, Golpe ultimo, DamageCause causa, double cordura, boolean cable) {
        Entity quien = autor(asesino);
        String amenaza = quien != null ? Marcas.amenaza(quien) : ultimo == null ? null : ultimo.amenaza();
        boolean parca = "parca".equals(amenaza) || "planidera".equals(amenaza);
        String ecoDe = null;
        if ("eco".equals(amenaza)) ecoDe = quien != null ? duenoEco(quien) : ultimo.ecoDe();
        String jugador = null;
        if (quien instanceof Player j && !j.equals(p)) jugador = j.getName();
        else if (quien == null && ultimo != null && ultimo.deJugador()) jugador = ultimo.atacante();
        int nivelMob = 0;
        if (quien != null && !(quien instanceof Player) && amenaza == null) nivelMob = nivelDe(quien);
        else if (quien == null && ultimo != null && ultimo.amenaza() == null && !ultimo.deJugador()) nivelMob = ultimo.nivel();
        MobsLethal mobs = hc.plugin().mobs();
        int suyo = mobs == null ? 0 : hc.valor("parte", () -> mobs.nivelBase(p), 0);
        return new Causas(parca, cordura, ecoDe, jugador, causa, nivelMob, suyo, cable);
    }

    /**
     * La linea de "por que": la PRIMERA que aplica en el orden de DIS M7 (P-D01 a P-D08).
     * P-D09 (el Eco) no compite: va debajo cuando hay Eco. Null si no aplica ninguna.
     */
    static PorQue porQue(Causas c) {
        if (c == null) return null;
        if (c.parca()) return new PorQue("P-D01", "Te quedaste en el mismo sitio más de 10 minutos.");
        if (c.cordura() <= 0) return new PorQue("P-D02", "Tu cordura llegó a 0: los grandes vienen a por ti.");
        if (c.ecoDe() != null) return new PorQue("P-D03", "Era el Eco de " + c.ecoDe() + ". Pega con su equipo.");
        if (c.asesino() != null) return new PorQue("P-D04", c.asesino() + " te ha matado. Lo tuyo lo guarda tu Eco.");
        if (c.causa() == DamageCause.FALL || c.causa() == DamageCause.DROWNING) {
            return new PorQue("P-D05", "Aquí las caídas y el agua hacen el doble.");
        }
        if (c.causa() == DamageCause.STARVATION) return new PorQue("P-D06", "Aquí el hambre va al doble.");
        if (c.nivelMob() > 0) {
            return new PorQue("P-D07", "Nv. " + c.nivelMob() + " contra tu nivel " + c.nivelSuyo()
                    + ". Cuanto más tiempo dentro, más nivel.");
        }
        if (c.cable()) return new PorQue("P-D08", "Huiste por el cable.");
        return null;
    }

    /** "mobs +20 niveles (cordura) +6 (30 min dentro)": el bonusNivel de Hardcore, por partes. */
    private String desglose(Player p) {
        double v = hc.cordura().conoce(p) ? hc.cordura().valor(p) : Cordura.MAXIMO;
        int porCordura = v < 25 ? hc.cfg().getInt("cordura.nivel-extra-critico", 20)
                : v < 50 ? hc.cfg().getInt("cordura.nivel-extra", 10) : 0;
        int segundos = hc.cordura().conoce(p) ? hc.cordura().estado(p).segundosDentro : 0;
        int cada = hc.cfg().getInt("dificultad.nivel-cada-minutos", 5);
        Racha racha = hc.racha();
        Eclipse eclipse = hc.eclipse();
        int deRacha = racha == null ? 0 : hc.valor("racha", () -> racha.niveles(p), 0);
        int deEclipse = eclipse == null ? 0 : hc.valor("eclipse", eclipse::nivelesExtra, 0);
        return desglose(porCordura, segundos, cada, deRacha, deEclipse);
    }

    static String desglose(int porCordura, int segundosDentro, int cadaMinutos, int racha, int eclipse) {
        List<String> partes = new ArrayList<>();
        if (porCordura > 0) partes.add("+" + porCordura + " (cordura)");
        int porMinutos = cadaMinutos > 0 ? segundosDentro / (cadaMinutos * 60) : 0;
        if (porMinutos > 0) partes.add("+" + porMinutos + " (" + (segundosDentro / 60) + " min dentro)");
        if (racha > 0) partes.add("+" + racha + " (racha)");
        if (eclipse > 0) partes.add("+" + eclipse + " (eclipse)");
        if (partes.isEmpty()) return "mobs sin niveles de más";
        // "niveles" solo en la primera: "+20 niveles (cordura) +6 (30 min dentro)".
        partes.set(0, partes.get(0).replaceFirst(" \\(", " niveles ("));
        return "mobs " + String.join(" ", partes);
    }

    /** P-D09, si va a nacer un Eco: la misma regla que Ecos (minimo de piezas o una Reliquia). */
    private String lineaEco(Player p) {
        ConfigurationSection eco = hc.cfg().getConfigurationSection("eco");
        boolean ecoActivo = eco == null || eco.getBoolean("activo", true);
        if (!ecoActivo || hc.ecos() == null || !hc.cfg().getBoolean("muerte.lo-pierde-todo", true)) return null;
        int minimo = eco == null ? 1 : eco.getInt("minimo-piezas", 1);
        PlayerInventory inv = p.getInventory();
        int piezas = 0;
        for (ItemStack it : inv.getArmorContents()) if (it != null && !it.getType().isAir()) piezas++;
        // El arma cuenta una vez: la de la mano o la mejor de la barra, como en FotoMuerte.
        boolean arma = FotoMuerte.esArma(inv.getItemInMainHand().getType());
        for (int i = 0; i <= 8 && !arma; i++) {
            ItemStack it = inv.getItem(i);
            arma = it != null && FotoMuerte.esArma(it.getType());
        }
        if (arma) piezas++;
        int reliquias = 0;
        Reliquias rel = hc.reliquias();
        if (rel != null) {
            for (ItemStack it : inv.getContents()) {
                if (it == null || it.getType().isAir()) continue;
                if (hc.valor("reliquias", () -> rel.es(it), false)) reliquias += it.getAmount();
            }
        }
        if (piezas < Math.max(0, minimo) && reliquias == 0) return null;
        return lineaEco(piezas, reliquias);
    }

    static String lineaEco(int piezas, int reliquias) {
        return "Tu Eco se alza donde caíste, con " + piezas + " piezas y " + reliquias + " reliquias.";
    }

    // ------------------------------------------------------------------ textos

    /** "Parca Nv. 52 · siega (ignora armadura, x2 por quieto)" o "caída (x2 aquí)". */
    static String texto(Golpe g) {
        StringBuilder sb = new StringBuilder();
        if (g.atacante() != null) {
            sb.append(g.atacante());
            if (g.nivel() > 0) sb.append(" Nv. ").append(g.nivel());
            sb.append(" · ");
        }
        sb.append(g.causa());
        if (!g.marcas().isEmpty()) sb.append(" (").append(String.join(", ", g.marcas())).append(')');
        return sb.toString();
    }

    /** "-18.2": un decimal, con punto, sea cual sea el idioma del servidor. */
    static String cifra(double dano) {
        return String.format(Locale.ROOT, "-%.1f", Math.max(0, dano));
    }

    static String nombreCausa(DamageCause c) {
        if (c == null) return "algo";
        return switch (c.name()) {
            case "ENTITY_ATTACK" -> "golpe";
            case "ENTITY_SWEEP_ATTACK" -> "barrido";
            case "PROJECTILE" -> "disparo";
            case "FALL" -> "caída";
            case "DROWNING" -> "ahogo";
            case "STARVATION" -> "hambre";
            case "FIRE", "FIRE_TICK", "CAMPFIRE" -> "fuego";
            case "LAVA" -> "lava";
            case "HOT_FLOOR" -> "magma";
            case "VOID" -> "vacío";
            case "POISON" -> "veneno";
            case "WITHER" -> "wither";
            case "MAGIC" -> "magia";
            case "ENTITY_EXPLOSION", "BLOCK_EXPLOSION" -> "explosión";
            case "SUFFOCATION" -> "asfixia";
            case "FREEZE" -> "frío";
            case "CONTACT" -> "pinchos";
            case "THORNS" -> "espinas";
            case "LIGHTNING" -> "rayo";
            case "FALLING_BLOCK" -> "bloque que cae";
            case "SONIC_BOOM" -> "estallido sónico";
            case "CRAMMING" -> "aplastado";
            case "FLY_INTO_WALL" -> "choque";
            case "DRAGON_BREATH" -> "aliento";
            case "WORLD_BORDER" -> "borde";
            case "KILL" -> "orden de muerte";
            default -> c.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    /** Nombre plano de lo que pega: el de su cartel si es un esbirro, su nombre o su tipo. */
    private String nombre(Entity e) {
        MinionManager mm = minionManager();
        if (mm != null) {
            try {
                if (mm.typeOf(e) != null) return Hardcore.plano(mm.typeOf(e).name());
                String gson = e.getPersistentDataContainer().get(NOMBRE_ESBIRRO, PersistentDataType.STRING);
                if (gson != null) return Hardcore.plano(GsonComponentSerializer.gson().deserialize(gson));
            } catch (Throwable t) {
                // Si EDM cambia, se cae al nombre visible.
            }
        }
        if (e.customName() != null) {
            // Sin EDM, Amenazas pone " Nv. X" en el propio nombre: fuera, que el nivel va aparte.
            return Hardcore.plano(e.customName()).replaceAll(" Nv\\. \\d+$", "");
        }
        return tipo(e.getType().getKey().getKey());
    }

    /** Nivel del cartel "Nv. X" de MinionManager; 0 si no lleva (ley 1: lo real lleva nivel). */
    private int nivelDe(Entity e) {
        MinionManager mm = minionManager();
        if (mm == null) return 0;
        try {
            if (!mm.adoptado(e) && !mm.isMinion(e)) return 0;
            return Math.max(1, mm.levelOf(e));
        } catch (Throwable t) {
            return 0;
        }
    }

    private MinionManager minionManager() {
        MobsLethal mobs = hc.plugin().mobs();
        return mobs == null ? null : mobs.minionManager();
    }

    private static String duenoEco(Entity e) {
        String id = e.getPersistentDataContainer().get(Marcas.ECO_DUENO, PersistentDataType.STRING);
        if (id == null) return "alguien";
        try {
            OfflinePlayer o = Bukkit.getOfflinePlayer(UUID.fromString(id));
            return o.getName() == null ? "alguien" : o.getName();
        } catch (IllegalArgumentException malo) {
            return "alguien";
        }
    }

    /** Los mobs que mas matan, en espanol; el resto con su id de Minecraft. */
    static String tipo(String id) {
        return switch (id) {
            case "zombie" -> "Zombi";
            case "husk" -> "Zombi momificado";
            case "drowned" -> "Ahogado";
            case "zombie_villager" -> "Aldeano zombi";
            case "skeleton" -> "Esqueleto";
            case "stray" -> "Esqueleto glacial";
            case "bogged" -> "Esqueleto pantanoso";
            case "wither_skeleton" -> "Esqueleto wither";
            case "spider" -> "Araña";
            case "cave_spider" -> "Araña de cueva";
            case "creeper" -> "Creeper";
            case "witch" -> "Bruja";
            case "enderman" -> "Enderman";
            case "phantom" -> "Phantom";
            case "slime" -> "Slime";
            case "pillager" -> "Saqueador";
            case "vindicator" -> "Vindicador";
            case "evoker" -> "Invocador";
            case "ravager" -> "Devastador";
            case "vex" -> "Vex";
            case "warden" -> "Warden";
            case "creaking" -> "Crujidor";
            case "breeze" -> "Breeze";
            case "wolf" -> "Lobo";
            default -> id.replace('_', ' ');
        };
    }

    // ---------------------------------------------------------------- limpieza

    /** Quit en MONITOR: despues de Combate.alDesconectar, que puede llamar a cable(). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSalir(PlayerQuitEvent e) {
        anillos.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onCambiarMundo(PlayerChangedWorldEvent e) {
        if (hc.esHardcore(e.getFrom())) anillos.remove(e.getPlayer().getUniqueId());
    }

    /** El parte del cable, al volver, detras del aviso de Combate. Caduca a las 24 h. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onVolver(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Pendiente pe = pendientes.remove(p.getUniqueId());
        if (pe == null || System.currentTimeMillis() - pe.cuando() > 24 * 3_600_000L) return;
        hc.plugin().getServer().getScheduler().runTaskLater(hc.plugin(), () -> {
            if (!p.isOnline()) return;
            for (Component l : pe.lineas()) p.sendMessage(l);
        }, 40L);
    }

    void parar() {
        anillos.clear();
        pendientes.clear();
    }

    // ------------------------------------------------------------------ autotest

    private List<String> autotest() {
        Autotest.Hoja h = new Autotest.Hoja();
        long t0 = 1_000_000L;

        // Anillo de 8: entran 10, quedan los 8 ultimos en orden.
        ArrayDeque<Golpe> a = new ArrayDeque<>();
        for (int i = 0; i < 10; i++) meter(a, golpeDePrueba(t0 + i * 1000L, i + 1), 8);
        h.igual("anillo de 8 con 10 golpes", 8, a.size());
        h.igual("se cae el mas viejo", 3.0, a.peekFirst().dano());
        h.igual("el ultimo queda al final", 10.0, a.peekLast().dano());
        ArrayDeque<Golpe> uno = new ArrayDeque<>();
        meter(uno, golpeDePrueba(t0, 1), 0);
        meter(uno, golpeDePrueba(t0, 2), 0);
        h.igual("capacidad 0 se trata como 1", 1, uno.size());

        // Ventana de 15 s: de los golpes a -20, -16, -15, -3 y 0 s entran los tres ultimos.
        long muerte = t0 + 60_000L;
        ArrayDeque<Golpe> b = new ArrayDeque<>();
        for (long hace : new long[]{20_000, 16_000, 15_000, 3_000, 0}) {
            meter(b, golpeDePrueba(muerte - hace, hace / 1000.0), 8);
        }
        List<Golpe> v = ventana(b, muerte, 15_000L);
        h.igual("ventana de 15 s", 3, v.size());
        h.igual("la ventana empieza en el golpe de hace 15 s", 15.0, v.get(0).dano());
        h.igual("la ventana acaba en el golpe mortal", 0.0, v.get(v.size() - 1).dano());
        h.igual("ventana de un anillo vacio", 0, ventana(null, muerte, 15_000L).size());

        // El "por que": la primera que aplica en el orden de DIS M7.
        h.igual("PARCA gana a todo", "P-D01",
                id(porQue(new Causas(true, 0, "Ana", "Beto", DamageCause.FALL, 40, 12, true))));
        h.igual("cordura 0 antes que Eco, jugador y caida", "P-D02",
                id(porQue(new Causas(false, 0, "Ana", "Beto", DamageCause.FALL, 40, 12, false))));
        h.igual("Eco antes que jugador", "P-D03",
                id(porQue(new Causas(false, 30, "Ana", "Beto", DamageCause.ENTITY_ATTACK, 0, 12, false))));
        h.igual("texto del Eco", "Era el Eco de Ana. Pega con su equipo.",
                porQue(new Causas(false, 30, "Ana", null, null, 0, 12, false)).texto());
        h.igual("jugador antes que caida", "P-D04",
                id(porQue(new Causas(false, 30, null, "Beto", DamageCause.FALL, 0, 12, false))));
        h.igual("texto del jugador", "Beto te ha matado. Lo tuyo lo guarda tu Eco.",
                porQue(new Causas(false, 30, null, "Beto", null, 0, 12, false)).texto());
        h.igual("caida antes que hambre y nivel", "P-D05",
                id(porQue(new Causas(false, 30, null, null, DamageCause.FALL, 40, 12, false))));
        h.igual("ahogo", "P-D05", id(porQue(new Causas(false, 30, null, null, DamageCause.DROWNING, 0, 12, false))));
        h.igual("hambre antes que nivel", "P-D06",
                id(porQue(new Causas(false, 30, null, null, DamageCause.STARVATION, 40, 12, false))));
        h.igual("mob con nivel", "P-D07",
                id(porQue(new Causas(false, 30, null, null, DamageCause.ENTITY_ATTACK, 40, 12, false))));
        h.igual("texto del nivel", "Nv. 40 contra tu nivel 12. Cuanto más tiempo dentro, más nivel.",
                porQue(new Causas(false, 30, null, null, DamageCause.ENTITY_ATTACK, 40, 12, false)).texto());
        h.igual("cable", "P-D08", id(porQue(new Causas(false, 30, null, null, null, 0, 12, true))));
        h.igual("texto del cable", "Huiste por el cable.",
                porQue(new Causas(false, 30, null, null, null, 0, 12, true)).texto());
        h.igual("cable pierde contra un jugador", "P-D04",
                id(porQue(new Causas(false, 30, null, "Beto", null, 0, 12, true))));
        h.igual("nada aplica (vacio)", null, id(porQue(new Causas(false, 30, null, null, DamageCause.VOID, 0, 12, false))));
        h.igual("cordura 0,4 no es 0", null, id(porQue(new Causas(false, 0.4, null, null, DamageCause.VOID, 0, 12, false))));

        // Desglose de niveles y textos de las lineas.
        h.igual("desglose del ejemplo de DIS M7", "mobs +20 niveles (cordura) +6 (30 min dentro)",
                desglose(20, 1800, 5, 0, 0));
        h.igual("desglose sin nada", "mobs sin niveles de más", desglose(0, 200, 5, 0, 0));
        h.igual("desglose con racha y eclipse", "mobs +3 niveles (racha) +10 (eclipse)", desglose(0, 60, 5, 3, 10));
        h.igual("desglose sin nivel por minutos", "mobs +10 niveles (cordura)", desglose(10, 1800, 0, 0, 0));
        h.igual("cifra", "-18.2", cifra(18.2));
        h.igual("cifra redondea a un decimal", "-4.0", cifra(3.96));
        h.igual("linea de la siega", "Parca Nv. 52 · siega (ignora armadura, x2 por quieto)",
                texto(new Golpe(t0, "siega", "Parca", 52, 12, List.of("ignora armadura", "x2 por quieto"), 12,
                        "parca", null, false, false)));
        h.igual("linea de caida", "caída (x2 aquí)", texto(new Golpe(t0, nombreCausa(DamageCause.FALL), null, 0, 4,
                List.of("x2 aquí"), 12, null, null, false, false)));
        h.igual("linea del Eco del ejemplo", "Tu Eco se alza donde caíste, con 4 piezas y 2 reliquias.", lineaEco(4, 2));
        h.igual("nombre de un zombi", "Zombi", tipo("zombie"));
        h.ok("el autotest no escribe en hardcore-datos.yml", !hc.datos().isSet("parte"));
        return h.lineas();
    }

    private static String id(PorQue p) {
        return p == null ? null : p.id();
    }

    private static Golpe golpeDePrueba(long cuando, double dano) {
        return new Golpe(cuando, "golpe", "Zombi", 10, dano, List.of(), 50, null, null, false, false);
    }
}
