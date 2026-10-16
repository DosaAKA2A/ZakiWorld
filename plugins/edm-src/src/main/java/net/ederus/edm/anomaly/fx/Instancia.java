package net.ederus.edm.anomaly.fx;

import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Un modelo puesto en el mundo. Todo corre en el hilo principal: el dueño llama a
 * {@link #tick(int)} una vez por tick con un contador que sube.
 *
 * Matriz de cada pieza = base(altura, yaw, pitch) * salida(t) * escala * giro(t) * cadena de
 * grupos * matriz local * crecimiento(t) * encogido de salida(t).
 *
 * Las entidades estan en la base (bajada lo que el modelo tenga por debajo de su origen, para
 * que la caja de recorte del cliente lo cubra entero). {@link #mover(Location)} las lleva a otra
 * base por teleport y las matrices no cambian: asi vuela un modelo sin recrear nada.
 */
public final class Instancia {

    /** Escala minima en vez de 0: una matriz singular no se descompone bien en el cliente. */
    private static final float ESCALA_MINIMA = 1e-3f;
    private static final float EPSILON = 1e-6f;
    /** Lo que se aleja (en radios del modelo) y lo que sube cada pieza con {@link #desmontar(int)}. */
    private static final float EMPUJE_SALIDA = 1.5f;
    private static final float SUBIDA_SALIDA = 0.6f;
    /** Vueltas (en radianes) que da cada pieza sobre si misma al salir disparada. */
    private static final float VOLTERETA_SALIDA = 5f;
    /** Ticks que tarda el giro extra en llegar a su velocidad. */
    private static final double RAMPA_GIRO = 20;

    private record Visible(Display entidad, ModeloBde.Pieza pieza, int indice, Matrix4f ultima) {}

    private final ModeloBde modelo;
    private final Opciones opciones;
    private final List<Visible> visibles = new ArrayList<>();
    private final Matrix4f[] grupos;
    private final Map<String, ItemStack> cabezas = new HashMap<>();
    /** Retardo de cada pieza al crecer, en el mismo orden que las piezas del modelo. */
    private final float[] demoras;
    /** Distancia horizontal de cada pieza al eje, en radios del modelo (para la onda de salida). */
    private final float[] radios;
    /** Eje fijo de la voltereta de cada pieza al desmontar. */
    private final Vector3f[] ejes;
    private final float duracionPieza;
    /** Lo que se bajan las entidades respecto a la base: la parte del modelo bajo su origen. */
    private final float bajada;

    /** Base actual del modelo, sin yaw ni pitch. Cambia con {@link #mover(Location)}. */
    private Location ancla;
    private float yaw;
    private float pitch;
    private float factor = 1;

    private boolean creada;
    private boolean retirada;
    private int ultimoTick;
    private int inicioSalida = -1;
    private int ticksSalida;
    private int finSalida;
    private float distanciaSalida;
    private float subidaSalida;
    private float retardoSalida;

    Instancia(ModeloBde modelo, Opciones opciones) {
        this.modelo = modelo;
        this.opciones = opciones;
        this.ancla = sinGiro(opciones.base());
        this.yaw = opciones.yaw();
        this.grupos = modelo.nuevasMatricesGrupos();
        ModeloBde.Bounds b = modelo.bounds();
        this.bajada = Math.max(0, -b.minY()) * opciones.escala() * opciones.escalaY();
        int crecer = opciones.ticksCrecimiento();
        // Como el circulo de Calamity: el 60 % del tiempo es el barrido angular, el resto lo que tarda cada pieza.
        this.duracionPieza = Math.max(1, crecer * 0.4f);
        List<ModeloBde.Pieza> piezas = modelo.piezas();
        this.demoras = new float[piezas.size()];
        this.radios = new float[piezas.size()];
        this.ejes = new Vector3f[piezas.size()];
        for (int i = 0; i < demoras.length; i++) {
            ModeloBde.Pieza p = piezas.get(i);
            demoras[i] = crecer <= 0 ? 0 : (float) (p.angulo() / (2 * Math.PI) * (crecer - duracionPieza));
            radios[i] = Math.min(1.5f, (float) Math.hypot(p.centro().x, p.centro().z) / b.radius());
            Vector3f eje = new Vector3f(ruido(i) - .5f, ruido(i + 3) - .5f, ruido(i + 7) - .5f);
            ejes[i] = eje.lengthSquared() < 1e-6f ? new Vector3f(0, 1, 0) : eje.normalize();
        }
    }

    public ModeloBde modelo() { return modelo; }
    public Opciones opciones() { return opciones; }

    /** Crea todos los displays en la base, ya con la matriz del tick 0. Llamarla una sola vez. */
    public Instancia spawn() {
        if (creada) throw new IllegalStateException("La instancia ya se creo");
        creada = true;
        World mundo = ancla.getWorld();
        ModeloBde.Bounds b = modelo.bounds();
        float escala = opciones.escala();
        float ancho = Math.max(4, 2 * b.radius() * escala * 1.5f);
        float alto = Math.max(4, (Math.max(0, b.maxY()) - Math.min(0, b.minY())) * escala * opciones.escalaY() * 1.5f
                + Math.abs(opciones.alturaExtra()));
        float alcance = Math.max(1.5f, (b.radius() * escala + 16) / 64);
        matricesDeGrupos(0);
        Location donde = posicionEntidades();
        List<ModeloBde.Pieza> piezas = modelo.piezas();
        for (int i = 0; i < piezas.size(); i++) {
            ModeloBde.Pieza pieza = piezas.get(i);
            int indice = i;
            Class<? extends Display> clase = switch (pieza.tipo()) {
                case BLOQUE -> BlockDisplay.class;
                case CABEZA, OBJETO -> ItemDisplay.class;
                case TEXTO -> TextDisplay.class;
            };
            mundo.spawn(donde.clone(), clase, d -> configurar(d, pieza, indice, ancho, alto, alcance));
        }
        return this;
    }

    private void configurar(Display d, ModeloBde.Pieza pieza, int indice, float ancho, float alto, float alcance) {
        Visible visible = new Visible(d, pieza, indice, new Matrix4f().zero());
        // Se registra antes de configurar: si algo falla despues, la entidad ya tiene dueño.
        visibles.add(visible);
        opciones.registrar().accept(d);
        d.setPersistent(false);
        d.setGravity(false);
        d.setInvulnerable(true);
        switch (d) {
            case BlockDisplay bloque -> {
                Material m = opciones.recolor().getOrDefault(pieza.material(), pieza.material());
                bloque.setBlock(m.createBlockData());
            }
            case ItemDisplay item -> {
                item.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                item.setItemStack(pieza.tipo() == ModeloBde.Tipo.OBJETO
                        ? new ItemStack(pieza.material()) : cabeza(pieza.textura()));
            }
            case TextDisplay texto -> {
                texto.text(Component.text(pieza.texto(), pieza.color()));
                texto.setBackgroundColor(Color.fromARGB(pieza.fondo()));
                texto.setDefaultBackground(false);
                texto.setShadowed(pieza.sombra());
                texto.setSeeThrough(false);
            }
            default -> { }
        }
        d.setBillboard(Display.Billboard.FIXED);
        d.setBrightness(brillo(pieza));
        d.setViewRange(alcance);
        d.setDisplayWidth(ancho);
        d.setDisplayHeight(alto);
        d.setTeleportDuration(2);
        d.setInterpolationDuration(2);
        d.setInterpolationDelay(0);
        enviar(visible, matriz(pieza, indice, 0));
    }

    private Display.Brightness brillo(ModeloBde.Pieza pieza) {
        if (opciones.brillo() != null) return opciones.brillo();
        if (pieza.brillo() != null) return pieza.brillo();
        return new Display.Brightness(15, 15);
    }

    private ItemStack cabeza(String textura) {
        ItemStack guardada = cabezas.get(textura);
        if (guardada == null) {
            guardada = new ItemStack(Material.PLAYER_HEAD);
            if (!(guardada.getItemMeta() instanceof SkullMeta meta)) {
                throw new IllegalStateException("SkullMeta no disponible para el modelo");
            }
            var perfil = Bukkit.createProfile(UUID.nameUUIDFromBytes(textura.getBytes(StandardCharsets.UTF_8)));
            perfil.setProperty(new ProfileProperty("textures", textura));
            meta.setPlayerProfile(perfil);
            guardada.setItemMeta(meta);
            cabezas.put(textura, guardada);
        }
        return guardada.clone();
    }

    /**
     * Mueve todas las piezas al estado del tick t. Devuelve false cuando la instancia ya no
     * existe (retirada o terminada la salida), y entonces el dueño puede soltarla.
     */
    public boolean tick(int t) {
        if (!creada || retirada) return false;
        ultimoTick = t;
        if (inicioSalida >= 0 && t - inicioSalida >= finSalida + 2) {
            // Dos ticks de margen para que el cliente termine de interpolar el encogido.
            retirar();
            return false;
        }
        matricesDeGrupos(t);
        for (Visible v : visibles) {
            if (v.entidad().isValid()) enviar(v, matriz(v.pieza(), v.indice(), t));
        }
        return true;
    }

    /**
     * Lleva el modelo a otra base: teleporta las entidades (el cliente las desliza en dos
     * ticks) y deja las matrices como estan. Para un modelo que vuela: moverlo y luego
     * llamar a {@link #tick(int)} en el mismo tick.
     */
    public void mover(Location nuevaBase) {
        Objects.requireNonNull(nuevaBase, "nuevaBase");
        if (!Objects.equals(nuevaBase.getWorld(), ancla.getWorld())) {
            throw new IllegalArgumentException("Una instancia no cambia de mundo");
        }
        ancla = sinGiro(nuevaBase);
        if (!creada || retirada) return;
        Location donde = posicionEntidades();
        for (Visible v : visibles) {
            Display d = v.entidad();
            if (d.isValid()) d.teleport(donde);
        }
    }

    /** Cambia el yaw del modelo entero (grados, sentido de Minecraft). Se aplica en el proximo tick. */
    public void girar(float yaw) {
        girar(yaw, pitch);
    }

    /**
     * Orienta el modelo entero: con yaw y pitch de Minecraft, el +Z del modelo apunta a donde
     * miraria una entidad con esa rotacion. Se aplica en el proximo tick.
     */
    public void girar(float yaw, float pitch) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("giro no finito");
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /**
     * Multiplica la escala del modelo entero alrededor de su base (un pulso, un impacto, nacer
     * creciendo). 1 lo deja como estaba. Se aplica en el proximo tick.
     */
    public void escalarTodo(float factor) {
        if (!Float.isFinite(factor) || factor < 0 || factor > 64) throw new IllegalArgumentException("factor fuera de rango");
        this.factor = Math.max(ESCALA_MINIMA, factor);
    }

    /** Arranca la salida: cada pieza se aleja del centro, sube un poco y encoge a 0 en esos ticks. */
    public void desmontar(int ticks) {
        float escala = opciones.escala();
        desmontar(ticks, EMPUJE_SALIDA * escala, SUBIDA_SALIDA * escala, 0);
    }

    /**
     * Salida disparada: cada pieza sale hacia fuera 'distancia' bloques en arco (sube hasta
     * 'subida'), dando vueltas sobre si misma y encogiendo a 0 en 'ticks'. Con 'retardoRadial'
     * mayor que 0, las piezas de fuera salen despues: ese numero de ticks por radio del modelo
     * (una onda de dentro a fuera). El giro extra se congela al empezar.
     */
    public void desmontar(int ticks, float distancia, float subida, float retardoRadial) {
        if (!creada || retirada || inicioSalida >= 0) return;
        if (ticks <= 0) {
            retirar();
            return;
        }
        if (!Float.isFinite(distancia) || !Float.isFinite(subida) || !Float.isFinite(retardoRadial) || retardoRadial < 0) {
            throw new IllegalArgumentException("salida no finita");
        }
        inicioSalida = ultimoTick;
        ticksSalida = ticks;
        distanciaSalida = distancia;
        subidaSalida = subida;
        retardoSalida = retardoRadial;
        float maximo = 0;
        for (float r : radios) maximo = Math.max(maximo, r);
        finSalida = ticks + (int) Math.ceil(retardoRadial * maximo);
    }

    public boolean desmontando() {
        return inicioSalida >= 0 && !retirada;
    }

    /** Borra todas las entidades ya. */
    public void retirar() {
        retirada = true;
        for (Visible v : visibles) {
            if (v.entidad().isValid()) v.entidad().remove();
        }
    }

    /** True mientras la instancia esta creada, sin retirar y con todas sus entidades vivas. */
    public boolean valida() {
        if (!creada || retirada || visibles.isEmpty()) return false;
        for (Visible v : visibles) if (!v.entidad().isValid()) return false;
        return true;
    }

    public List<Entity> entidades() {
        List<Entity> lista = new ArrayList<>(visibles.size());
        for (Visible v : visibles) lista.add(v.entidad());
        return lista;
    }

    /**
     * 1.84.3 · Donde esta ahora, en el mundo, el punto 'local' de cada pieza de ese material
     * (con la ultima matriz enviada). Para poner particulas sobre piezas sueltas, como la llama
     * de las velas. Las piezas ya encogidas (crecimiento o salida) no cuentan.
     */
    public List<Location> puntos(Material material, Vector3f local) {
        List<Location> lista = new ArrayList<>();
        if (!creada || retirada) return lista;
        Location donde = posicionEntidades();
        Vector3f v = new Vector3f();
        for (Visible vis : visibles) {
            if (vis.pieza().material() != material || !vis.entidad().isValid()) continue;
            Matrix4f m = vis.ultima();
            if (m.getScale(new Vector3f()).y < 0.05f * opciones.escala()) continue;
            m.transformPosition(local, v);
            lista.add(donde.clone().add(v.x, v.y, v.z));
        }
        return lista;
    }

    /** La base del modelo en el mundo con la altura extra: el centro del suelo o el centro, segun su ajuste. */
    public Location centro() {
        return ancla.clone().add(0, opciones.alturaExtra(), 0);
    }

    // ------------------------------------------------------------- matrices

    private Location posicionEntidades() {
        return ancla.clone().subtract(0, bajada, 0);
    }

    private void matricesDeGrupos(int t) {
        boolean animar = opciones.animacion() && modelo.animado();
        // Sin animacion, cada grupo animado se queda en su ultima clave.
        modelo.matricesGrupos(animar ? modelo.tiempoEnBucle(t) : modelo.reposo(), true, grupos);
    }

    private Matrix4f matriz(ModeloBde.Pieza pieza, int indice, int t) {
        float e = opciones.escala() * factor;
        float ey = e * opciones.escalaY();
        Matrix4f m = new Matrix4f()
                .translation(0, opciones.alturaExtra() + bajada, 0)
                .rotateY((float) Math.toRadians(-yaw));
        if (pitch != 0) m.rotateX((float) Math.toRadians(pitch));
        float giro = giro(t);

        float encogido = 1;
        if (inicioSalida >= 0) {
            float arranque = inicioSalida + retardoSalida * radios[indice];
            float u = Math.max(0, Math.min(1, (t - arranque) / ticksSalida));
            if (u > 0) {
                // Centro de la pieza ya girado y escalado: la salida se mide en bloques.
                Vector3f c = new Vector3f(pieza.centro()).rotateY(giro).mul(e, ey, e);
                float horizontal = (float) Math.hypot(c.x, c.z);
                float dx = horizontal > 1e-4f ? c.x / horizontal : 0;
                float dz = horizontal > 1e-4f ? c.z / horizontal : 0;
                float lejos = 1 - (1 - u) * (1 - u) * (1 - u);
                float arco = subidaSalida * (0.6f * (float) Math.sin(Math.PI * Math.min(1, u * 1.4f)) + 0.4f * lejos);
                m.translate(c.x + dx * distanciaSalida * lejos, c.y + arco, c.z + dz * distanciaSalida * lejos)
                        .rotate(u * VOLTERETA_SALIDA, ejes[indice])
                        .translate(-c.x, -c.y, -c.z);
                encogido = 1 - u;
            }
        }

        m.scale(e, ey, e);
        if (giro != 0) m.rotateY(giro);
        if (pieza.grupo() >= 0) m.mul(grupos[pieza.grupo()]);
        m.mul(pieza.matriz());

        float f = Math.max(ESCALA_MINIMA, crecimiento(indice, t) * encogido);
        if (f < 1) {
            Vector3f c = pieza.centroLocal();
            m.translate(c).scale(f).translate(-c.x, -c.y, -c.z);
        }
        return m;
    }

    /** Avance de crecimiento de una pieza: retardo por angulo y smoothstep. */
    private float crecimiento(int indice, int t) {
        int crecer = opciones.ticksCrecimiento();
        if (crecer <= 0) return 1;
        float avance = Math.max(0, Math.min(1, (t - demoras[indice]) / duracionPieza));
        return suave(avance);
    }

    /**
     * Giro extra acumulado. La velocidad arranca en 0 en el tick 0 y llega a la pedida en 20
     * ticks con una rampa coseno (integrada), como en la vista previa; al desmontar se congela.
     */
    private float giro(int t) {
        float porTick = opciones.giroPorTick();
        if (porTick == 0) return 0;
        double tiempo = Math.max(0, t);
        if (inicioSalida >= 0) tiempo = Math.min(tiempo, inicioSalida);
        double rampa = Math.min(RAMPA_GIRO, tiempo);
        double recorrido = tiempo - rampa / 2 - RAMPA_GIRO / (2 * Math.PI) * Math.sin(Math.PI * rampa / RAMPA_GIRO);
        // El cuaternion tiene periodo 4 PI: envolver ahi conserva su signo entre vueltas.
        return (float) ((porTick * recorrido) % (4 * Math.PI));
    }

    private static float suave(float x) {
        return x * x * (3 - 2 * x);
    }

    /** Ruido fijo en [0, 1) por pieza. */
    private static float ruido(int n) {
        double x = Math.sin(n * 127.1 + 311.7) * 43758.5453;
        return (float) (x - Math.floor(x));
    }

    private static Location sinGiro(Location l) {
        Location c = l.clone();
        c.setYaw(0);
        c.setPitch(0);
        return c;
    }

    private static void enviar(Visible v, Matrix4f matriz) {
        if (v.ultima().equals(matriz, EPSILON)) return;
        v.entidad().setInterpolationDelay(0);
        v.entidad().setTransformationMatrix(matriz);
        v.ultima().set(matriz);
    }
}
