package net.ederus.lethalworld.hardcore;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.ederus.edm.anomaly.minions.MinionManager;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Un Eco (sec. 2): su registro y, cuando alguien anda cerca, su cuerpo.
 *
 * Los datos mandan y la entidad es una vista (sec. 2.7): todo lo que importa (quien era,
 * que lleva, cuanto le queda de vida, cuando caduca) vive en hardcore-datos.yml, en
 * ecos.<id>. El cuerpo se crea al acercarse alguien y se quita al quedarse solo, al
 * descargarse el chunk o al apagar; es NO persistente, asi que un reinicio o una recarga
 * del chunk nunca dejan dos cuerpos del mismo Eco (X10): el unico que vale es el que esta
 * en el mapa de Ecos, y cualquier otro con la marca se purga al cargar.
 *
 * La IA (objetivo, correa, Paso, Desesperacion, recomposicion) corre en segundo(), que llama
 * Ecos cada segundo; lo fino (particulas, telegraph del Paso, maniqui) en cadaDosTicks(),
 * colgado de la tarea unica de Amenazas.
 */
final class Eco {

    static final TextColor GRIS = TextColor.color(0x9AA7B8);

    // ------------------------------------------------------ registro (se guarda)
    final String id;
    UUID dueno;
    String nombre;
    String mundo;
    double x, y, z;
    long nacio, expira;
    /** N_E. */
    int nivel;
    /** Vida logica maxima (puede pasar de 1024: Amenazas la escala). */
    double vidaMax;
    double fraccion = 1;
    double armadura, dureza, dano;
    boolean esqueleto, escudo;
    final ItemStack[] equipo = new ItemStack[6];
    final List<ItemStack> reliquias = new ArrayList<>();
    final List<ItemStack> esencias = new ArrayList<>();
    int nEsencias;
    UUID asesino;
    String asesinoNombre;
    boolean porParca;
    int segundosDentro;
    boolean errante;
    /** Millis en que se deshace un errante por el tope (0 = no). */
    long desmorona;
    String skinValor, skinFirma;
    final List<String> frases = new ArrayList<>();
    /** Censo del muerto (MED sec. 5): caza valida y grado de la Lagrima. */
    double escalonMedio;
    int escalonMax, piezasMmo, piezasCalamity;
    long ultimoDano, durmio;
    /** Si ya se alzo una vez (la primera aparicion lleva el efecto de alzarse). */
    boolean alzado;
    /** Nacido de "eco prueba": en la Bitacora igual, pero sin mensajes a nadie. */
    boolean prueba;

    // ------------------------------------------------------- vista (memoria)
    /** Antes de esto no se alza (Rip reproduce su muerte en el sitio: sec. 2.7). */
    long alzarEn;
    LivingEntity cuerpo;
    Mannequin cascara;
    Runnable pelea;
    UUID ultimoAgresor;
    long ultimoAgresorEn;
    long ultimoGolpeDado;
    /** El objetivo que eligio la IA: el listener de Ecos cancela cualquier otro. */
    UUID elegido;
    long elegidoDesde;
    boolean volviendo;
    long volviendoDesde;
    double correaAlSoltar;
    long pasoListo;
    long pasoEn;
    Location pasoDestino;
    boolean desesperado;
    long desesperacionHasta;
    long escudoListo;
    boolean reconocido;
    long nadieDesde;
    long invulnerableHasta;
    boolean rojo;
    long proximaVoz;
    int pulso;
    Location ultimaPos;

    Eco(String id) {
        this.id = id;
    }

    // ------------------------------------------------------------ cuentas sec. 2.4

    /** N_E = min(100, N_muerte + extra-nivel (+ extra-nivel-si-parca)). */
    static int nivelEco(int nMuerte, boolean porParca, ConfigurationSection c) {
        int n = nMuerte + c.getInt("extra-nivel", 5) + (porParca ? c.getInt("extra-nivel-si-parca", 5) : 0);
        return Math.max(1, Math.min(100, n));
    }

    /** max(vida-minima, H x vida-multiplicador x (1 + vida-por-nivel x (N_E - 1))). */
    static double vidaLogica(double h, int n, ConfigurationSection c) {
        double v = h * c.getDouble("vida-multiplicador", 3.0) * (1 + c.getDouble("vida-por-nivel", 0.04) * (n - 1));
        return Math.max(c.getDouble("vida-minima", 60), v);
    }

    /** clamp(D x dano-multiplicador x (1 + dano-por-nivel x (N_E - 1)), dano-minimo, dano-maximo). */
    static double dano(double d, int n, ConfigurationSection c) {
        double v = d * c.getDouble("dano-multiplicador", 0.8) * (1 + c.getDouble("dano-por-nivel", 0.02) * (n - 1));
        return Math.max(c.getDouble("dano-minimo", 3), Math.min(c.getDouble("dano-maximo", 30), v));
    }

    /** Esencias nuevas de una caza valida: esencias-base + floor(N_E / esencias-cada-niveles). */
    static int esenciasNuevas(int n, ConfigurationSection c) {
        int cada = Math.max(1, c.getInt("esencias-cada-niveles", 20));
        return Math.max(0, c.getInt("esencias-base", 1) + n / cada);
    }

    /**
     * Grado de la Lagrima por el escalon medio del censo (lagrima-por-escalon: escalon >= clave
     * -> grado). Sin escalon suficiente pero con una pieza MMOItems, II. 0 = ninguna.
     */
    static int gradoLagrima(double escalonMedio, int piezasMmo, ConfigurationSection c) {
        ConfigurationSection t = c.getConfigurationSection("lagrima-por-escalon");
        int grado = 0;
        double mejorClave = -1;
        if (t != null && !t.getKeys(false).isEmpty()) {
            for (String k : t.getKeys(false)) {
                double clave;
                try {
                    clave = Double.parseDouble(k);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (escalonMedio + 1e-9 >= clave && clave > mejorClave) {
                    mejorClave = clave;
                    grado = t.getInt(k);
                }
            }
        } else {
            if (escalonMedio >= 12) grado = 4;
            else if (escalonMedio >= 6) grado = 3;
            else if (escalonMedio >= 4) grado = 2;
        }
        if (grado <= 0 && piezasMmo > 0) grado = 2;
        return grado;
    }

    /**
     * Recomposicion (sec. 2.6): tras-segundos sin que nadie le pegue, recupera por-segundo de su
     * vida cada segundo. Dormido tambien: al despertar se suma por el tiempo pasado, asi que
     * quitarle vida en varias visitas no sirve (X27).
     */
    static double recomponer(double fraccion, long desde, long ultimoDano, long ahora, double tras, double porSegundo) {
        long inicio = Math.max(desde, ultimoDano + (long) (tras * 1000));
        if (ahora <= inicio) return fraccion;
        return Math.min(1, fraccion + porSegundo * (ahora - inicio) / 1000.0);
    }

    /** Piezas MMOItems del censo que cuentan: sin las prestadas ni las copias de un Eco. */
    static int piezasMmoValidas(Censo.Foto censo) {
        if (censo == null) return 0;
        if (censo.piezas() == null || censo.piezas().isEmpty()) return censo.piezasMmo();
        int n = 0;
        for (Censo.Pieza p : censo.piezas()) {
            if (p.mmo() == null || p.mmo().isBlank() || p.mmo().startsWith("VANILLA")) continue;
            Set<String> m = p.marcas() == null ? Set.of() : p.marcas();
            if (m.contains("prestado") || m.contains("copia_eco") || m.contains("eco_copia")) continue;
            n++;
        }
        return n;
    }

    /** Un Eco recien nacido de una foto (sin guardar: lo guarda Ecos). */
    static Eco deFoto(String id, FotoMuerte f, ConfigurationSection c, long ahora) {
        Eco e = new Eco(id);
        e.dueno = f.dueno;
        e.nombre = f.nombre;
        Location l = f.anclaje;
        e.mundo = l.getWorld().getKey().toString();
        e.x = l.getX();
        e.y = l.getY();
        e.z = l.getZ();
        e.nacio = ahora;
        e.expira = ahora + (long) (c.getDouble("horas", 12) * 3_600_000L);
        e.nivel = nivelEco(f.nivel, f.porParca, c);
        e.vidaMax = vidaLogica(f.vida, e.nivel, c);
        e.dano = dano(f.dano, e.nivel, c);
        // Topes de ESC: son atributos BASE del mob (las copias no aportan nada).
        e.armadura = Math.min(30, Math.max(0, f.armadura));
        e.dureza = Math.min(20, Math.max(0, f.dureza));
        e.esqueleto = f.arquero;
        e.escudo = f.escudo;
        System.arraycopy(f.equipo, 0, e.equipo, 0, 6);
        e.reliquias.addAll(f.reliquias);
        e.esencias.addAll(f.esencias);
        e.nEsencias = f.nEsencias;
        e.asesino = f.asesino;
        e.asesinoNombre = f.asesinoNombre;
        e.porParca = f.porParca;
        e.segundosDentro = f.segundosDentro;
        e.skinValor = f.skinValor;
        e.skinFirma = f.skinFirma;
        e.frases.addAll(f.frases);
        Censo.Foto censo = f.censo();
        e.escalonMedio = censo.escalonMedio();
        e.escalonMax = censo.escalonMax();
        e.piezasMmo = piezasMmoValidas(censo);
        e.piezasCalamity = censo.piezasCalamity();
        return e;
    }

    // ------------------------------------------------------------- persistencia

    void guardar(ConfigurationSection s) {
        s.set("dueno", dueno.toString());
        s.set("nombre", nombre);
        s.set("mundo", mundo);
        s.set("x", x);
        s.set("y", y);
        s.set("z", z);
        s.set("nacio", nacio);
        s.set("expira", expira);
        s.set("nivel", nivel);
        s.set("vida-max", vidaMax);
        s.set("fraccion", fraccion);
        s.set("armadura", armadura);
        s.set("dureza", dureza);
        s.set("dano", dano);
        s.set("base", esqueleto ? "SKELETON" : "ZOMBIE");
        s.set("escudo", escudo);
        s.set("equipo", null);
        for (int i = 0; i < 6; i++) {
            if (equipo[i] != null) s.set("equipo." + FotoMuerte.CASILLAS[i], base64(equipo[i]));
        }
        s.set("reliquias", base64(reliquias));
        s.set("esencias", nEsencias);
        s.set("esencias-items", base64(esencias));
        s.set("asesino", asesino == null ? null : asesino.toString());
        s.set("asesino-nombre", asesinoNombre);
        s.set("por-parca", porParca);
        s.set("segundos-dentro", segundosDentro);
        s.set("errante", errante);
        s.set("desmorona", desmorona > 0 ? desmorona : null);
        s.set("skin.valor", skinValor);
        s.set("skin.firma", skinFirma);
        s.set("frases", frases.isEmpty() ? null : new ArrayList<>(frases));
        s.set("censo.escalon-medio", escalonMedio);
        s.set("censo.escalon-max", escalonMax);
        s.set("censo.piezas-mmo", piezasMmo);
        s.set("censo.piezas-calamity", piezasCalamity);
        s.set("ultimo-dano", ultimoDano);
        s.set("durmio", durmio);
        s.set("alzado", alzado);
        s.set("prueba", prueba ? true : null);
    }

    /** Lee ecos.<id>. Lanza si el registro esta roto: Ecos lo avisa y NO lo borra (puede llevar botin). */
    static Eco cargar(String id, ConfigurationSection s) {
        Eco e = new Eco(id);
        e.dueno = UUID.fromString(s.getString("dueno", ""));
        e.nombre = s.getString("nombre", "?");
        e.mundo = s.getString("mundo", "");
        e.x = s.getDouble("x");
        e.y = s.getDouble("y");
        e.z = s.getDouble("z");
        e.nacio = s.getLong("nacio");
        e.expira = s.getLong("expira");
        e.nivel = s.getInt("nivel", 1);
        e.vidaMax = s.getDouble("vida-max", 60);
        e.fraccion = Math.max(0.01, Math.min(1, s.getDouble("fraccion", 1)));
        e.armadura = s.getDouble("armadura");
        e.dureza = s.getDouble("dureza");
        e.dano = s.getDouble("dano", 3);
        e.esqueleto = "SKELETON".equalsIgnoreCase(s.getString("base", "ZOMBIE"));
        e.escudo = s.getBoolean("escudo");
        for (int i = 0; i < 6; i++) {
            String b = s.getString("equipo." + FotoMuerte.CASILLAS[i]);
            if (b != null) e.equipo[i] = item(b);
        }
        for (String b : s.getStringList("reliquias")) e.reliquias.add(item(b));
        e.nEsencias = s.getInt("esencias");
        for (String b : s.getStringList("esencias-items")) e.esencias.add(item(b));
        String a = s.getString("asesino");
        e.asesino = a == null || a.isBlank() ? null : UUID.fromString(a);
        e.asesinoNombre = s.getString("asesino-nombre");
        e.porParca = s.getBoolean("por-parca");
        e.segundosDentro = s.getInt("segundos-dentro");
        e.errante = s.getBoolean("errante");
        e.desmorona = s.getLong("desmorona", 0);
        e.skinValor = s.getString("skin.valor");
        e.skinFirma = s.getString("skin.firma");
        e.frases.addAll(s.getStringList("frases"));
        e.escalonMedio = s.getDouble("censo.escalon-medio");
        e.escalonMax = s.getInt("censo.escalon-max");
        e.piezasMmo = s.getInt("censo.piezas-mmo");
        e.piezasCalamity = s.getInt("censo.piezas-calamity");
        e.ultimoDano = s.getLong("ultimo-dano");
        e.durmio = s.getLong("durmio");
        e.alzado = s.getBoolean("alzado");
        e.prueba = s.getBoolean("prueba");
        return e;
    }

    static String base64(ItemStack it) {
        return Base64.getEncoder().encodeToString(it.serializeAsBytes());
    }

    static List<String> base64(List<ItemStack> items) {
        List<String> out = new ArrayList<>();
        for (ItemStack it : items) if (it != null && !it.getType().isAir()) out.add(base64(it));
        return out;
    }

    static ItemStack item(String b64) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
    }

    // ------------------------------------------------------------------ consultas

    boolean tieneBotin() {
        return !reliquias.isEmpty() || nEsencias > 0;
    }

    /** Reliquias que lleva (las I-II se apilan: cuenta unidades). */
    int nReliquias() {
        int n = 0;
        for (ItemStack r : reliquias) n += r.getAmount();
        return n;
    }

    /** Los UUID de sus Reliquias para la Bitacora (las I-II no llevan: "g<grado>x<n>"). */
    String idsReliquias() {
        if (reliquias.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (ItemStack r : reliquias) {
            if (!sb.isEmpty()) sb.append(',');
            ItemMeta m = r.hasItemMeta() ? r.getItemMeta() : null;
            String rid = m == null ? null : m.getPersistentDataContainer().get(Marcas.RELIQUIA_ID, PersistentDataType.STRING);
            if (rid != null) {
                sb.append(rid);
            } else {
                Integer g = m == null ? null : m.getPersistentDataContainer().get(Marcas.RELIQUIA, PersistentDataType.INTEGER);
                sb.append('g').append(g == null ? "?" : g).append('x').append(r.getAmount());
            }
        }
        return sb.toString();
    }

    /** Los materiales de lo que lleva puesto, para la Bitacora. */
    String piezasTexto() {
        StringBuilder sb = new StringBuilder();
        for (ItemStack it : equipo) {
            if (it == null) continue;
            if (!sb.isEmpty()) sb.append(',');
            sb.append(it.getType().name().toLowerCase(java.util.Locale.ROOT));
        }
        return sb.isEmpty() ? "-" : sb.toString();
    }

    World world() {
        NamespacedKey k = NamespacedKey.fromString(mundo);
        World w = k == null ? null : org.bukkit.Bukkit.getWorld(k);
        return w != null ? w : org.bukkit.Bukkit.getWorld(mundo);
    }

    /** Su anclaje, o null si su mundo no esta cargado. */
    Location anclaje() {
        World w = world();
        return w == null ? null : new Location(w, x, y, z);
    }

    boolean despierto() {
        return cuerpo != null && cuerpo.isValid() && !cuerpo.isDead();
    }

    Component nombre(boolean enRojo) {
        int k = nReliquias();
        String texto = "Eco de " + nombre + (k > 0 ? " · " + k + (k == 1 ? " reliquia" : " reliquias") : "");
        return Component.text(texto, enRojo ? ComandoCalamity.ROJO : GRIS);
    }

    // --------------------------------------------------------------------- vista

    /**
     * Crea el cuerpo en el anclaje con la vida que tenia (y lo recompuesto mientras dormia).
     * False si no se puede (mundo o chunk sin cargar, spawn cancelado) o si ya estaba.
     */
    boolean despertar(Ecos g, boolean alzamiento) {
        if (despierto()) return false;
        Location l = anclaje();
        if (l == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return false;
        Hardcore hc = g.hc();
        ConfigurationSection c = g.cfg();
        long ahora = System.currentTimeMillis();
        if (durmio > 0) {
            fraccion = recomponer(fraccion, durmio, ultimoDano, ahora,
                    c.getDouble("recompone.tras-segundos", 15), c.getDouble("recompone.por-segundo", 0.02));
        }
        boolean conCascara = c.getBoolean("cuerpo-jugador", false) && skinValor != null;
        double vidaEntidad = Math.min(Amenazas.VIDA_MAXIMA_ENTIDAD, vidaMax);
        double frac = Math.max(0.01, Math.min(1, fraccion));
        Class<? extends Mob> tipo = esqueleto ? Skeleton.class : Zombie.class;
        Mob m = hc.amenazas().invocar(tipo, l, "eco", nivel, nombre(false), e -> {
            e.getPersistentDataContainer().set(Marcas.ECO, PersistentDataType.STRING, id);
            e.getPersistentDataContainer().set(Marcas.ECO_DUENO, PersistentDataType.STRING, dueno.toString());
            e.getPersistentDataContainer().set(Marcas.VIDA_LOGICA, PersistentDataType.DOUBLE, vidaMax);
            e.setSilent(true);   // suena a jugador, no a zombi: los sonidos van a mano
            if (e instanceof Zombie zz) {
                zz.setAdult();
                zz.setShouldBurnInDay(false);
                Compat.setAttribute(zz, "spawn_reinforcements", 0);
            }
            if (e instanceof AbstractSkeleton sk) sk.setShouldBurnInDay(false);
            Compat.setAttribute(e, "max_health", vidaEntidad);
            e.setHealth(Math.max(0.5, vidaEntidad * frac));
            Compat.setAttribute(e, "attack_damage", dano);
            Compat.setAttribute(e, "armor", armadura);
            Compat.setAttribute(e, "armor_toughness", dureza);
            Compat.setAttribute(e, "movement_speed", c.getDouble("velocidad", 0.26));
            Compat.setAttribute(e, "knockback_resistance", 0.5);
            Compat.setAttribute(e, "follow_range", 32);
            if (conCascara) e.setInvisible(true);
            else vestir(e.getEquipment());
        });
        if (m == null) return false;
        cuerpo = m;
        durmio = 0;
        nadieDesde = ahora;
        elegido = null;
        volviendo = false;
        desesperado = false;
        desesperacionHasta = 0;
        pasoEn = 0;
        pasoListo = ahora + 3000;
        ultimaPos = l.clone();
        rojo = false;
        if (conCascara) ponerCascara(g, l);
        pelea = () -> cadaDosTicks(g);
        hc.amenazas().registrarPelea(pelea);

        if (alzamiento) {
            alzado = true;
            // 2 s invulnerable mientras se levanta: no se le mata en el suelo antes de verlo.
            m.setInvulnerable(true);
            invulnerableHasta = ahora + 2000;
            World w = l.getWorld();
            Fx.helix(l.clone(), 0.7, 2.2, 36, 2, p -> Compat.spawn(w, Compat.SOUL, p, 1, 0, 0, 0, 0));
            Compat.sound(w, l, "block.respawn_anchor.set_spawn", 1.2f, 0.6f);
            Compat.sound(w, l, "entity.zombie_villager.cure", 0.8f, 0.5f);
        }
        return true;
    }

    /** Le pone las copias; drop chance 0 ya lo puso Amenazas.invocar. */
    private void vestir(EntityEquipment eq) {
        if (eq == null) return;
        eq.setHelmet(clon(equipo[FotoMuerte.HEAD]));
        eq.setChestplate(clon(equipo[FotoMuerte.CHEST]));
        eq.setLeggings(clon(equipo[FotoMuerte.LEGS]));
        eq.setBoots(clon(equipo[FotoMuerte.FEET]));
        ItemStack mano = clon(equipo[FotoMuerte.HAND]);
        // Un esqueleto solo dispara con arco: la ballesta se le pone como arco con su nombre.
        if (esqueleto && mano != null && mano.getType() == Material.CROSSBOW) mano = mano.withType(Material.BOW);
        eq.setItemInMainHand(mano);
        eq.setItemInOffHand(clon(equipo[FotoMuerte.OFF_HAND]));
    }

    private static ItemStack clon(ItemStack it) {
        return it == null ? null : it.clone();
    }

    /**
     * P1 · cuerpo de jugador (cuerpo-jugador, apagado de serie): un Mannequin con su skin
     * encima del zombi invisible, teletransportado cada 2 ticks (MT sec. 5.2). Si algo falla,
     * el zombi se vuelve visible y se viste: el Eco nunca se queda sin cuerpo.
     */
    private void ponerCascara(Ecos g, Location l) {
        try {
            ResolvableProfile perfil = ResolvableProfile.resolvableProfile()
                    .uuid(dueno).name(nombre.length() > 16 ? nombre.substring(0, 16) : nombre)
                    .addProperty(new ProfileProperty("textures", skinValor, skinFirma))
                    .build();
            cascara = l.getWorld().spawn(l, Mannequin.class, mq -> {
                mq.setPersistent(false);
                mq.setGravity(false);
                mq.setCollidable(false);
                mq.setSilent(true);
                mq.setImmovable(true);
                mq.setDescription(Component.empty());
                mq.getPersistentDataContainer().set(Marcas.ECO, PersistentDataType.STRING, id);
                mq.setProfile(perfil);
                vestir(mq.getEquipment());
            });
            g.vista(cascara, id);
        } catch (Throwable t) {
            cascara = null;
            if (cuerpo != null) {
                cuerpo.setInvisible(false);
                vestir(cuerpo.getEquipment());
            }
        }
    }

    /** Quita el cuerpo (y el maniqui) guardando la fraccion de vida. */
    void quitarVista(Ecos g) {
        quitarVista(g, true);
    }

    /**
     * @param retirar false cuando el chunk se esta descargando: la entidad no es persistente y
     *                se va sola con el; quitarla dentro de su propio evento de descarga no hace falta.
     */
    void quitarVista(Ecos g, boolean retirar) {
        if (cuerpo != null && cuerpo.isValid() && !cuerpo.isDead()) fraccion = Amenazas.fraccion(cuerpo);
        if (cuerpo != null) {
            g.olvidarVista(cuerpo);
            if (retirar && cuerpo.isValid()) cuerpo.remove();
        }
        if (cascara != null) {
            g.olvidarVista(cascara);
            if (retirar && cascara.isValid()) cascara.remove();
        }
        if (pelea != null) g.hc().amenazas().quitarPelea(pelea);
        cuerpo = null;
        cascara = null;
        pelea = null;
        elegido = null;
    }

    /** Cada 2 ticks mientras esta despierto: presencia, maniqui, telegraph del Paso. */
    private void cadaDosTicks(Ecos g) {
        if (!despierto()) {
            if (pelea != null) g.hc().amenazas().quitarPelea(pelea);
            return;
        }
        pulso++;
        LivingEntity e = cuerpo;
        World w = e.getWorld();
        Location pos = e.getLocation();
        long ahora = System.currentTimeMillis();
        if (invulnerableHasta > 0 && ahora >= invulnerableHasta) {
            invulnerableHasta = 0;
            e.setInvulnerable(false);
        }
        if (pulso % 5 == 0) {
            Compat.spawn(w, Compat.SOUL, pos.clone().add(0, 1.0, 0), 1, 0.3, 0.5, 0.3, 0.01);
            Compat.spawn(w, Compat.DUST, pos.clone().add(0, 1.0, 0), 3, 0.3, 0.6, 0.3, 0,
                    Compat.dust(0x9AA7B8, 1.0f));
        }
        if (cascara != null) {
            if (cascara.isValid()) {
                cascara.teleport(pos);
                // El primer paquete a veces llega sin la skin (BossFight.java:937-940).
                if (pulso == 1) cascara.setProfile(cascara.getProfile());
            } else {
                cascara = null;
                e.setInvisible(false);
                vestir(e.getEquipment());
            }
        }
        if (pasoEn > 0 && pasoDestino != null) {
            if (ahora < pasoEn) {
                Fx.telegraph(w, pasoDestino, 1.0, 0x9AA7B8);
            } else {
                pasoEn = 0;
                g.hc().amenazas().teleportar(e, pasoDestino);
                Compat.spawn(w, Compat.SOUL, pasoDestino.clone().add(0, 1, 0), 12, 0.3, 0.8, 0.3, 0.02);
                Compat.sound(w, pasoDestino, "entity.enderman.teleport", 0.8f, 0.6f);
            }
        }
    }

    /**
     * La IA de un segundo (sec. 2.6). Devuelve false si tiene que dormirse (nadie cerca durante
     * segundos-para-dormir).
     */
    boolean segundo(Ecos g, long ahora) {
        LivingEntity e = cuerpo;
        if (!(e instanceof Mob mob)) return false;
        Hardcore hc = g.hc();
        ConfigurationSection c = g.cfg();
        Location pos = e.getLocation();
        Location ancla = anclaje();
        if (ancla == null) return false;
        fraccion = Amenazas.fraccion(e);
        List<Player> cuentan = g.cuentanEn(e.getWorld());

        // --- dormir: nadie que cuente a radio-dormir durante segundos-para-dormir
        double rDormir = c.getDouble("radio-dormir", 64);
        boolean alguien = false;
        for (Player p : cuentan) {
            if (p.getLocation().distanceSquared(pos) <= rDormir * rDormir) {
                alguien = true;
                break;
            }
        }
        if (alguien) nadieDesde = ahora;
        else if (ahora - nadieDesde >= c.getInt("segundos-para-dormir", 30) * 1000L) return false;

        // --- se recompone si nadie le pega
        if (ahora - ultimoDano >= c.getDouble("recompone.tras-segundos", 15) * 1000 && fraccion < 1) {
            double max = Compat.getAttribute(e, "max_health", e.getHealth());
            e.setHealth(Math.min(max, e.getHealth() + max * c.getDouble("recompone.por-segundo", 0.02)));
            fraccion = Amenazas.fraccion(e);
        }

        // --- Desesperacion: una vez por vida, al bajar del umbral
        double extra = c.getDouble("desesperacion.extra", 0.20);
        if (!desesperado && fraccion < c.getDouble("desesperacion.umbral", 0.30)) {
            desesperado = true;
            desesperacionHasta = ahora + c.getInt("desesperacion.segundos", 8) * 1000L;
            Compat.setAttribute(e, "movement_speed", c.getDouble("velocidad", 0.26) * (1 + extra));
            Compat.setAttribute(e, "attack_damage", dano * (1 + extra));
            Compat.sound(e.getWorld(), pos, "entity.allay.death", 1.2f, 0.5f);
            Compat.spawn(e.getWorld(), Compat.SOUL_FIRE_FLAME, pos.clone().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0.03);
        } else if (desesperacionHasta > 0 && ahora >= desesperacionHasta) {
            desesperacionHasta = 0;
            Compat.setAttribute(e, "movement_speed", c.getDouble("velocidad", 0.26));
            Compat.setAttribute(e, "attack_damage", dano);
        }

        // --- dueno, asesino y reconocimiento
        Player duenoP = errante ? null : g.jugador(dueno, cuentan);
        Player asesinoP = asesino == null ? null : g.jugador(asesino, cuentan);
        double buscaA = c.getDouble("busca-a", 64);
        boolean cercaRojo = (duenoP != null && duenoP.getLocation().distanceSquared(pos) <= 24 * 24)
                || (asesinoP != null && asesinoP.getLocation().distanceSquared(pos) <= 24 * 24);
        if (cercaRojo != rojo) {
            rojo = cercaRojo;
            ponerNombre(hc);
        }
        if (duenoP != null && !reconocido && duenoP.getLocation().distanceSquared(pos) <= 24 * 24) {
            reconocido = true;
            duenoP.playSound(pos, "entity.player.death", 1.0f, 1.0f);
            duenoP.showTitle(Title.title(Component.empty(), Component.text("Te reconoce.", ComandoCalamity.ROJO),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2000), Duration.ofMillis(700))));
            hc.cordura().sumar(duenoP, -c.getDouble("reconocer-cordura", 5));
        }

        // --- pasos: suena a jugador
        if (ultimaPos != null && ultimaPos.getWorld() == pos.getWorld() && ultimaPos.distanceSquared(pos) > 0.09) {
            Compat.sound(e.getWorld(), pos, "block.sculk.step", 0.8f, 0.6f);
        }
        ultimaPos = pos.clone();

        // --- P1 · voces
        g.voz(this, pos, cuentan, ahora);

        // --- correa: volviendo a casa no persigue a nadie
        double dist = pos.distance(ancla);
        if (volviendo) {
            if (dist <= 3) {
                volviendo = false;
            } else if (ahora - volviendoDesde > 10_000 || dist > correaAlSoltar + c.getDouble("correa-maxima", 48)) {
                volviendo = false;
                Compat.spawn(e.getWorld(), Compat.SMOKE, pos.clone().add(0, 1, 0), 20, 0.4, 0.8, 0.4, 0.02);
                hc.amenazas().teleportar(e, ancla);
                Compat.spawn(e.getWorld(), Compat.SMOKE, ancla.clone().add(0, 1, 0), 20, 0.4, 0.8, 0.4, 0.02);
                return true;
            } else {
                apuntar(mob, null, ahora);
                mob.getPathfinder().moveTo(ancla);
                return true;
            }
        }

        // --- objetivo: dueno, asesino, ultimo agresor, el mas cercano con vista
        Player objetivo = null;
        if (duenoP != null && !g.protegido(duenoP) && duenoP.getLocation().distanceSquared(ancla) <= buscaA * buscaA) {
            objetivo = duenoP;
        } else if (asesinoP != null && !g.protegido(asesinoP)
                && asesinoP.getLocation().distanceSquared(ancla) <= buscaA * buscaA) {
            objetivo = asesinoP;
        }
        boolean presa = objetivo != null;
        if (objetivo == null && ultimoAgresor != null && ahora - ultimoAgresorEn <= 10_000) {
            Player ag = g.jugador(ultimoAgresor, cuentan);
            if (ag != null && !g.protegido(ag)) objetivo = ag;
        }
        if (objetivo == null) {
            double r = c.getDouble("cercano-radio", 24);
            double mejor = r * r;
            for (Player p : cuentan) {
                double d2 = p.getLocation().distanceSquared(pos);
                if (d2 <= mejor && !g.protegido(p) && e.hasLineOfSight(p)) {
                    mejor = d2;
                    objetivo = p;
                }
            }
        }
        double correa = presa ? c.getDouble("correa-presa", 64) : c.getDouble("correa", 32);
        if (dist > correa) {
            volviendo = true;
            volviendoDesde = ahora;
            correaAlSoltar = correa;
            apuntar(mob, null, ahora);
            mob.getPathfinder().moveTo(ancla);
            return true;
        }
        apuntar(mob, objetivo, ahora);

        // --- Paso del Eco: con objetivo cerca y sin poder golpearle (pilar, caja, agua)
        if (objetivo != null && pasoEn == 0 && ahora >= pasoListo
                && objetivo.getLocation().distanceSquared(pos) <= 24 * 24
                && ahora - Math.max(ultimoGolpeDado, elegidoDesde) >= c.getInt("paso.sin-golpear-segundos", 6) * 1000L) {
            pasoDestino = junto(objetivo, pos);
            pasoEn = ahora + 1000;
            pasoListo = ahora + 1000 + c.getInt("paso.espera", 10) * 1000L;
            ultimoGolpeDado = ahora;
        }
        return true;
    }

    /** Pone (o quita) el objetivo que eligio la IA; el listener deja pasar solo ese. */
    private void apuntar(Mob mob, Player objetivo, long ahora) {
        UUID nuevo = objetivo == null ? null : objetivo.getUniqueId();
        if (java.util.Objects.equals(nuevo, elegido) && (objetivo == null || objetivo.equals(mob.getTarget()))) return;
        if (!java.util.Objects.equals(nuevo, elegido)) elegidoDesde = ahora;
        elegido = nuevo;
        mob.setTarget(objetivo);
    }

    /** A 2 bloques del objetivo, del lado del Eco, si ahi se puede estar; si no, en su sitio. */
    private static Location junto(Player objetivo, Location desde) {
        Location o = objetivo.getLocation();
        org.bukkit.util.Vector v = desde.toVector().subtract(o.toVector()).setY(0);
        if (v.lengthSquared() > 1e-4) {
            Location l = o.clone().add(v.normalize().multiply(2));
            if (l.getBlock().isPassable() && l.clone().add(0, 1, 0).getBlock().isPassable()) return l;
        }
        return o.clone();
    }

    /** El cartel "Nv. X": en rojo con el dueno o el asesino a 24. */
    void ponerNombre(Hardcore hc) {
        if (!despierto()) return;
        MinionManager mm = hc.plugin().mobs() == null ? null : hc.plugin().mobs().minionManager();
        Component n = nombre(rojo);
        if (mm != null) {
            try {
                mm.adoptar(cuerpo, Math.max(1, nivel), n, 1.0);
                return;
            } catch (Throwable ignorado) {
                // Sin adoptar se pone el nombre a pelo.
            }
        }
        cuerpo.customName(n.append(Component.text(" Nv. " + nivel, NamedTextColor.GRAY)));
        cuerpo.setCustomNameVisible(true);
    }
}
