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
import java.util.UUID;

/**
 * Un modelo puesto en el mundo. Todo corre en el hilo principal: el dueño llama a
 * {@link #tick(int)} una vez por tick con un contador que sube.
 *
 * Matriz de cada pieza = base(altura, yaw, escala) * giro(t) * salida(t) * cadena de grupos
 * * matriz local * crecimiento(t) * encogido de salida(t).
 */
public final class Instancia {

    /** Escala minima en vez de 0: una matriz singular no se descompone bien en el cliente. */
    private static final float ESCALA_MINIMA = 1e-3f;
    private static final float EPSILON = 1e-6f;
    /** Cuanto se aleja (en radios del modelo) y cuanto sube cada pieza al desmontar. */
    private static final float EMPUJE_SALIDA = 1.5f;
    private static final float SUBIDA_SALIDA = 0.6f;

    private record Visible(Display entidad, ModeloBde.Pieza pieza, int indice, Matrix4f ultima) {}

    private final ModeloBde modelo;
    private final Opciones opciones;
    private final Location ancla;
    private final List<Visible> visibles = new ArrayList<>();
    private final Matrix4f[] grupos;
    private final Map<String, ItemStack> cabezas = new HashMap<>();
    /** Retardo de cada pieza al crecer, en el mismo orden que las piezas del modelo. */
    private final float[] demoras;
    private final float duracionPieza;

    private boolean creada;
    private boolean retirada;
    private int ultimoTick;
    private int inicioSalida = -1;
    private int ticksSalida;

    Instancia(ModeloBde modelo, Opciones opciones) {
        this.modelo = modelo;
        this.opciones = opciones;
        Location base = opciones.base();
        base.setYaw(0);
        base.setPitch(0);
        this.ancla = base;
        this.grupos = modelo.nuevasMatricesGrupos();
        int crecer = opciones.ticksCrecimiento();
        // Como el circulo de Calamity: el 60 % del tiempo es el barrido angular, el resto lo que tarda cada pieza.
        this.duracionPieza = Math.max(1, crecer * 0.4f);
        List<ModeloBde.Pieza> piezas = modelo.piezas();
        this.demoras = new float[piezas.size()];
        for (int i = 0; i < demoras.length; i++) {
            demoras[i] = crecer <= 0 ? 0 : (float) (piezas.get(i).angulo() / (2 * Math.PI) * (crecer - duracionPieza));
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
        float alto = Math.max(4, (Math.max(0, b.maxY()) - Math.min(0, b.minY())) * escala * 1.5f
                + Math.abs(opciones.alturaExtra()));
        float alcance = Math.max(1.5f, (b.radius() * escala + 16) / 64);
        matricesDeGrupos(0);
        List<ModeloBde.Pieza> piezas = modelo.piezas();
        for (int i = 0; i < piezas.size(); i++) {
            ModeloBde.Pieza pieza = piezas.get(i);
            int indice = i;
            Class<? extends Display> clase = switch (pieza.tipo()) {
                case BLOQUE -> BlockDisplay.class;
                case CABEZA -> ItemDisplay.class;
                case TEXTO -> TextDisplay.class;
            };
            mundo.spawn(ancla.clone(), clase, d -> configurar(d, pieza, indice, ancho, alto, alcance));
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
                item.setItemStack(cabeza(pieza.textura()));
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
        if (inicioSalida >= 0 && t - inicioSalida >= ticksSalida + 2) {
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

    /** Arranca la salida: cada pieza se aleja del centro, sube un poco y encoge a 0 en esos ticks. */
    public void desmontar(int ticks) {
        if (!creada || retirada || inicioSalida >= 0) return;
        if (ticks <= 0) {
            retirar();
            return;
        }
        inicioSalida = ultimoTick;
        ticksSalida = ticks;
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

    /** Centro del suelo del modelo en el mundo, con la altura extra. */
    public Location centro() {
        return ancla.clone().add(0, opciones.alturaExtra(), 0);
    }

    // ------------------------------------------------------------- matrices

    private void matricesDeGrupos(int t) {
        boolean animar = opciones.animacion() && modelo.animado();
        modelo.matricesGrupos(animar ? modelo.tiempoEnBucle(t) : 0, animar, grupos);
    }

    private Matrix4f matriz(ModeloBde.Pieza pieza, int indice, int t) {
        Matrix4f m = new Matrix4f()
                .translation(0, opciones.alturaExtra(), 0)
                .rotateY((float) Math.toRadians(-opciones.yaw()))
                .scale(opciones.escala());
        float giro = giro(t);
        if (giro != 0) m.rotateY(giro);

        float encogido = 1;
        if (inicioSalida >= 0) {
            float p = suave(Math.max(0, Math.min(1, (t - inicioSalida) / (float) ticksSalida)));
            Vector3f c = pieza.centro();
            float horizontal = (float) Math.hypot(c.x, c.z);
            float dx = horizontal > 1e-4f ? c.x / horizontal : 0;
            float dz = horizontal > 1e-4f ? c.z / horizontal : 0;
            m.translate(dx * EMPUJE_SALIDA * p, SUBIDA_SALIDA * p, dz * EMPUJE_SALIDA * p);
            encogido = 1 - p;
        }

        if (pieza.grupo() >= 0) m.mul(grupos[pieza.grupo()]);
        m.mul(pieza.matriz());

        float factor = Math.max(ESCALA_MINIMA, crecimiento(indice, t) * encogido);
        if (factor < 1) {
            Vector3f c = pieza.centroLocal();
            m.translate(c).scale(factor).translate(-c.x, -c.y, -c.z);
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
     * Giro extra acumulado. Con crecimiento la velocidad arranca en 0 al terminar de crecer
     * y llega a la pedida con una rampa coseno (integrada), como el circulo de Calamity.
     */
    private float giro(int t) {
        float porTick = opciones.giroPorTick();
        if (porTick == 0) return 0;
        int crecer = opciones.ticksCrecimiento();
        double recorrido;
        if (crecer <= 0) {
            recorrido = Math.max(0, t);
        } else {
            double rampaTotal = Math.max(1, crecer / 2.0);
            double tiempo = Math.max(0, t - crecer);
            double rampa = Math.min(rampaTotal, tiempo);
            recorrido = tiempo - rampa / 2 - rampaTotal / (2 * Math.PI) * Math.sin(Math.PI * rampa / rampaTotal);
        }
        // El cuaternion tiene periodo 4 PI: envolver ahi conserva su signo entre vueltas.
        return (float) ((porTick * recorrido) % (4 * Math.PI));
    }

    private static float suave(float x) {
        return x * x * (3 - 2 * x);
    }

    private static void enviar(Visible v, Matrix4f matriz) {
        if (v.ultima().equals(matriz, EPSILON)) return;
        v.entidad().setInterpolationDelay(0);
        v.entidad().setTransformationMatrix(matriz);
        v.ultima().set(matriz);
    }
}
