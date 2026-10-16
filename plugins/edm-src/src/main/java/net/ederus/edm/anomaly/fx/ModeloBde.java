package net.ederus.edm.anomaly.fx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Modelo de block-display.com ya saneado (formato 2, ver docs/modelos-bde.md).
 *
 * Solo lee geometria, textos, texturas de cabeza y claves de animacion de un JSON
 * que viaja dentro del jar. Nunca ejecuta NBT ni comandos y nunca descarga nada.
 * Un modelo cargado es inmutable: se comparte entre todas sus instancias.
 */
public final class ModeloBde {

    /** Envolvente del modelo normalizado: radio horizontal y alturas del suelo y del techo. */
    public record Bounds(float radius, float minY, float maxY) {}

    public enum Tipo { BLOQUE, CABEZA, TEXTO }

    /** Una clave del editor: tiempo en ticks y posicion, rotacion (radianes, XYZ) y escala. */
    record Clave(float tiempo, Vector3f posicion, Vector3f rotacion, Vector3f escala) {}

    /** Grupo del editor. Su indice respeta el orden padre antes que hijo. */
    public static final class Grupo {
        private final int indice;
        private final String id;
        private final String nombre;
        private final int padre;
        private final Matrix4f matriz;
        private final Vector3f pivote;
        private final List<Clave> claves;

        private Grupo(int indice, String id, String nombre, int padre, Matrix4f matriz,
                      Vector3f pivote, List<Clave> claves) {
            this.indice = indice;
            this.id = id;
            this.nombre = nombre;
            this.padre = padre;
            this.matriz = matriz;
            this.pivote = pivote;
            this.claves = claves;
        }

        public String id() { return id; }
        public String nombre() { return nombre; }
        /** Indice del grupo padre en {@link ModeloBde#grupos()}, o -1 si cuelga del mundo. */
        public int padre() { return padre; }
        public boolean animado() { return !claves.isEmpty(); }
        int indice() { return indice; }

        /** Matriz local al padre en ese tiempo; sin animacion devuelve la matriz fija del editor. */
        Matrix4f local(float tiempo, boolean animar, Matrix4f destino) {
            if (!animar || claves.isEmpty()) return destino.set(matriz);
            Clave a = claves.getFirst();
            Clave b = a;
            for (Clave clave : claves) {
                b = clave;
                if (clave.tiempo() >= tiempo) break;
                a = clave;
            }
            float avance = b.tiempo() <= a.tiempo() ? 0
                    : Math.max(0, Math.min(1, (tiempo - a.tiempo()) / (b.tiempo() - a.tiempo())));
            Vector3f p = new Vector3f(a.posicion()).lerp(b.posicion(), avance);
            Vector3f r = new Vector3f(a.rotacion()).lerp(b.rotacion(), avance);
            Vector3f s = new Vector3f(a.escala()).lerp(b.escala(), avance);
            return destino.translation(p).rotateXYZ(r.x, r.y, r.z).scale(s)
                    .translate(-pivote.x, -pivote.y, -pivote.z);
        }
    }

    /** Pieza visible: un bloque, una cabeza o un texto corto. */
    public static final class Pieza {
        private final String id;
        private final int grupo;
        private final Tipo tipo;
        private final Matrix4f matriz;
        private final Display.Brightness brillo;
        private final Material material;
        private final String textura;
        private final String texto;
        private final TextColor color;
        private final int fondo;
        private final boolean sombra;
        private Vector3f centro = new Vector3f();
        private float angulo;

        private Pieza(String id, int grupo, Tipo tipo, Matrix4f matriz, Display.Brightness brillo,
                      Material material, String textura, String texto, TextColor color, int fondo, boolean sombra) {
            this.id = id;
            this.grupo = grupo;
            this.tipo = tipo;
            this.matriz = matriz;
            this.brillo = brillo;
            this.material = material;
            this.textura = textura;
            this.texto = texto;
            this.color = color;
            this.fondo = fondo;
            this.sombra = sombra;
        }

        public String id() { return id; }
        /** Indice del grupo en {@link ModeloBde#grupos()}, o -1 si cuelga del mundo. */
        public int grupo() { return grupo; }
        public Tipo tipo() { return tipo; }
        /** Brillo propio del editor, o null si no trae. */
        public Display.Brightness brillo() { return brillo; }
        public Material material() { return material; }
        public String textura() { return textura; }
        public String texto() { return texto; }
        public TextColor color() { return color; }
        public int fondo() { return fondo; }
        public boolean sombra() { return sombra; }
        Matrix4f matriz() { return matriz; }
        /** Centro de la pieza en el espacio del modelo, en el tiempo 0 y sin animar. */
        Vector3f centro() { return centro; }
        /** Angulo del centro alrededor del eje Y, de 0 a 2 PI. */
        float angulo() { return angulo; }

        /** Punto local que hace de centro: los bloques van de 0 a 1, cabezas y textos en el origen. */
        Vector3f centroLocal() {
            return tipo == Tipo.BLOQUE ? new Vector3f(.5f, .5f, .5f) : new Vector3f();
        }
    }

    private static final int MAX_PIEZAS = 2048;
    private static final int MAX_GRUPOS = 512;
    private static final int MAX_CLAVES = 256;
    private static final float MAX_TIEMPO = 72000;
    private static final float MAX_VALOR = 1000;
    private static final Pattern NOMBRE = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Map<String, ModeloBde> CACHE = new ConcurrentHashMap<>();

    private final String nombre;
    private final Bounds bounds;
    private final List<Grupo> grupos;
    private final List<Pieza> piezas;
    private final float periodo;

    private ModeloBde(String nombre, Bounds bounds, List<Grupo> grupos, List<Pieza> piezas) {
        this.nombre = nombre;
        this.bounds = bounds;
        this.grupos = grupos;
        this.piezas = piezas;
        float ultimo = 0;
        for (Grupo g : grupos) if (!g.claves.isEmpty()) ultimo = Math.max(ultimo, g.claves.getLast().tiempo());
        this.periodo = ultimo;
    }

    public String nombre() { return nombre; }
    public Bounds bounds() { return bounds; }
    public List<Pieza> piezas() { return piezas; }
    public List<Grupo> grupos() { return grupos; }
    /** Duracion del bucle de animacion en ticks (la ultima clave del modelo); 0 si es estatico. */
    public float periodo() { return periodo; }
    public boolean animado() { return periodo > 0; }

    /** Prepara una instancia sin crear entidades todavia (ver {@link Instancia#spawn()}). */
    public Instancia crear(Opciones opciones) {
        return new Instancia(this, opciones);
    }

    /** Crea la instancia y sus displays de una vez. */
    public Instancia instanciar(Opciones opciones) {
        return crear(opciones).spawn();
    }

    /** Igual que {@link #instanciar(Opciones)} pero con otra base. */
    public Instancia instanciar(Location base, Opciones opciones) {
        return crear(opciones.conBase(base)).spawn();
    }

    /**
     * Matriz de cada grupo en el espacio del modelo para ese tiempo. Los padres van antes
     * que los hijos, asi que basta una pasada.
     */
    Matrix4f[] matricesGrupos(float tiempo, boolean animar, Matrix4f[] destino) {
        Matrix4f local = new Matrix4f();
        for (Grupo g : grupos) {
            Matrix4f m = destino[g.indice];
            if (g.padre < 0) m.identity();
            else m.set(destino[g.padre]);
            m.mul(g.local(tiempo, animar, local));
        }
        return destino;
    }

    /** Tiempo del editor que toca en el tick t con la animacion en bucle. */
    float tiempoEnBucle(int t) {
        if (periodo <= 0) return 0;
        return (float) (((double) t % periodo + periodo) % periodo);
    }

    Matrix4f[] nuevasMatricesGrupos() {
        Matrix4f[] m = new Matrix4f[grupos.size()];
        for (int i = 0; i < m.length; i++) m[i] = new Matrix4f();
        return m;
    }

    // ------------------------------------------------------------------ carga

    /** Lee /models/&lt;nombre&gt;.json del jar una sola vez. Un fallo no queda en cache. */
    public static ModeloBde cargar(String nombre) {
        if (nombre == null || !NOMBRE.matcher(nombre).matches()) {
            throw new IllegalArgumentException("Nombre de modelo invalido: " + nombre);
        }
        ModeloBde guardado = CACHE.get(nombre);
        if (guardado != null) return guardado;
        ModeloBde nuevo = leerRecurso(nombre);
        ModeloBde previo = CACHE.putIfAbsent(nombre, nuevo);
        return previo != null ? previo : nuevo;
    }

    /** Vacia la cache (por ejemplo tras cambiar el jar en un entorno de pruebas). */
    public static void olvidarCache() {
        CACHE.clear();
    }

    private static ModeloBde leerRecurso(String nombre) {
        InputStream entrada = ModeloBde.class.getResourceAsStream("/models/" + nombre + ".json");
        if (entrada == null) throw new IllegalStateException("Falta el modelo /models/" + nombre + ".json");
        try (Reader lector = new InputStreamReader(entrada, StandardCharsets.UTF_8)) {
            return leer(nombre, lector);
        } catch (IOException fallo) {
            throw new IllegalStateException("No se pudo leer el modelo " + nombre, fallo);
        } catch (RuntimeException fallo) {
            if (fallo instanceof IllegalArgumentException) {
                throw new IllegalArgumentException("Modelo " + nombre + " invalido: " + fallo.getMessage(), fallo);
            }
            throw new IllegalArgumentException("Modelo " + nombre + " mal formado: " + fallo, fallo);
        }
    }

    static ModeloBde leer(String nombre, Reader lector) {
        JsonElement raiz = JsonParser.parseReader(lector);
        if (!raiz.isJsonObject()) throw new IllegalArgumentException("la raiz no es un objeto");
        JsonObject doc = raiz.getAsJsonObject();
        if (!doc.has("format") || doc.get("format").getAsInt() != 2) {
            throw new IllegalArgumentException("se esperaba format 2");
        }
        Bounds bounds = leerBounds(objeto(doc, "bounds"));
        List<Grupo> grupos = leerGrupos(doc.has("groups") ? arreglo(doc, "groups") : new JsonArray());
        Map<String, Integer> indiceGrupo = new HashMap<>();
        for (Grupo g : grupos) indiceGrupo.put(g.id, g.indice);
        List<Pieza> piezas = leerPiezas(arreglo(doc, "pieces"), indiceGrupo);
        ModeloBde modelo = new ModeloBde(nombre, bounds, List.copyOf(grupos), List.copyOf(piezas));
        modelo.calcularCentros();
        return modelo;
    }

    private void calcularCentros() {
        Matrix4f[] g = matricesGrupos(0, false, nuevasMatricesGrupos());
        for (Pieza p : piezas) {
            Matrix4f m = p.grupo < 0 ? new Matrix4f(p.matriz) : new Matrix4f(g[p.grupo]).mul(p.matriz);
            p.centro = m.transformPosition(p.centroLocal());
            p.angulo = (float) ((Math.atan2(p.centro.z, p.centro.x) + 2 * Math.PI) % (2 * Math.PI));
        }
    }

    private static Bounds leerBounds(JsonObject b) {
        float radio = numero(b, "radius");
        float minY = numero(b, "minY");
        float maxY = numero(b, "maxY");
        if (radio <= 0 || maxY < minY) throw new IllegalArgumentException("bounds incoherentes");
        return new Bounds(radio, minY, maxY);
    }

    private static List<Grupo> leerGrupos(JsonArray datos) {
        if (datos.size() > MAX_GRUPOS) throw new IllegalArgumentException("demasiados grupos");
        Map<String, JsonObject> porId = new HashMap<>();
        List<String> orden = new ArrayList<>();
        for (JsonElement e : datos) {
            JsonObject g = e.getAsJsonObject();
            String id = texto(g, "id");
            if (id.isEmpty() || porId.put(id, g) != null) throw new IllegalArgumentException("id de grupo vacio o repetido: " + id);
            orden.add(id);
        }
        // Padres existentes y sin ciclos; luego orden padre antes que hijo.
        Map<String, String> padreDe = new HashMap<>();
        for (String id : orden) {
            JsonObject g = porId.get(id);
            String padre = g.has("parent") && !g.get("parent").isJsonNull() ? g.get("parent").getAsString() : null;
            if (padre != null && !porId.containsKey(padre)) throw new IllegalArgumentException("el grupo " + id + " tiene un padre que no existe");
            padreDe.put(id, padre);
        }
        for (String id : orden) {
            Set<String> vistos = new HashSet<>();
            for (String actual = id; actual != null; actual = padreDe.get(actual)) {
                if (!vistos.add(actual)) throw new IllegalArgumentException("ciclo de grupos en " + id);
            }
        }
        List<String> topologico = new ArrayList<>();
        Set<String> puestos = new HashSet<>();
        for (String id : orden) colocar(id, padreDe, puestos, topologico);

        Map<String, Integer> indice = new HashMap<>();
        List<Grupo> resultado = new ArrayList<>();
        for (String id : topologico) {
            JsonObject g = porId.get(id);
            String padre = padreDe.get(id);
            Vector3f pivote = g.has("pivot") && !g.get("pivot").isJsonNull() ? vector(g.getAsJsonArray("pivot")) : new Vector3f();
            List<Clave> claves = g.has("keys") && !g.get("keys").isJsonNull() ? leerClaves(g.getAsJsonArray("keys")) : List.of();
            String nombre = g.has("name") && !g.get("name").isJsonNull() ? g.get("name").getAsString() : id;
            int i = resultado.size();
            resultado.add(new Grupo(i, id, nombre, padre == null ? -1 : indice.get(padre),
                    matriz(arreglo(g, "matrix")), pivote, claves));
            indice.put(id, i);
        }
        return resultado;
    }

    private static void colocar(String id, Map<String, String> padreDe, Set<String> puestos, List<String> salida) {
        if (puestos.contains(id)) return;
        String padre = padreDe.get(id);
        if (padre != null) colocar(padre, padreDe, puestos, salida);
        puestos.add(id);
        salida.add(id);
    }

    private static List<Clave> leerClaves(JsonArray datos) {
        if (datos.size() > MAX_CLAVES) throw new IllegalArgumentException("demasiadas claves");
        List<Clave> claves = new ArrayList<>();
        for (JsonElement e : datos) {
            JsonObject c = e.getAsJsonObject();
            float tiempo = numero(c, "time");
            if (tiempo < 0 || tiempo > MAX_TIEMPO || (!claves.isEmpty() && tiempo <= claves.getLast().tiempo())) {
                throw new IllegalArgumentException("claves fuera de orden o de rango");
            }
            claves.add(new Clave(tiempo, vector(arreglo(c, "position")), vector(arreglo(c, "rotation")),
                    vector(arreglo(c, "scale"))));
        }
        return Collections.unmodifiableList(claves);
    }

    private static List<Pieza> leerPiezas(JsonArray datos, Map<String, Integer> indiceGrupo) {
        if (datos.isEmpty() || datos.size() > MAX_PIEZAS) throw new IllegalArgumentException("numero de piezas fuera de rango");
        Set<String> ids = new HashSet<>();
        List<Pieza> piezas = new ArrayList<>();
        for (JsonElement e : datos) {
            JsonObject p = e.getAsJsonObject();
            String id = texto(p, "id");
            if (id.isEmpty() || !ids.add(id)) throw new IllegalArgumentException("id de pieza vacio o repetido: " + id);
            int grupo = -1;
            if (p.has("group") && !p.get("group").isJsonNull()) {
                Integer i = indiceGrupo.get(p.get("group").getAsString());
                if (i == null) throw new IllegalArgumentException("la pieza " + id + " usa un grupo que no existe");
                grupo = i;
            }
            Matrix4f matriz = matriz(arreglo(p, "matrix"));
            Display.Brightness brillo = null;
            if (p.has("brightness") && !p.get("brightness").isJsonNull()) {
                JsonObject luz = p.getAsJsonObject("brightness");
                int cielo = luz.get("sky").getAsInt();
                int bloque = luz.get("block").getAsInt();
                if (cielo < 0 || cielo > 15 || bloque < 0 || bloque > 15) throw new IllegalArgumentException("brillo fuera de 0-15");
                brillo = new Display.Brightness(bloque, cielo);
            }
            String kind = texto(p, "kind");
            switch (kind) {
                case "block" -> piezas.add(new Pieza(id, grupo, Tipo.BLOQUE, matriz, brillo,
                        bloque(texto(p, "material")), null, null, null, 0, false));
                case "head" -> piezas.add(new Pieza(id, grupo, Tipo.CABEZA, matriz, brillo,
                        Material.PLAYER_HEAD, textura(texto(p, "texture")), null, null, 0, false));
                case "text" -> {
                    String t = texto(p, "text");
                    int largo = t.codePointCount(0, t.length());
                    if (largo < 1 || largo > 8) throw new IllegalArgumentException("texto de 1 a 8 caracteres");
                    if (t.codePoints().anyMatch(cp -> Character.isISOControl(cp) || cp == '§')) {
                        throw new IllegalArgumentException("texto con caracteres de control o de formato");
                    }
                    String hex = p.has("color") && !p.get("color").isJsonNull() ? p.get("color").getAsString() : "#FFFFFF";
                    if (!COLOR.matcher(hex).matches()) throw new IllegalArgumentException("color de texto invalido: " + hex);
                    int fondo = p.has("background") && !p.get("background").isJsonNull() ? p.get("background").getAsInt() : 0;
                    boolean sombra = p.has("shadow") && !p.get("shadow").isJsonNull() && p.get("shadow").getAsBoolean();
                    piezas.add(new Pieza(id, grupo, Tipo.TEXTO, matriz, brillo, null, null, t,
                            TextColor.fromHexString(hex), fondo, sombra));
                }
                default -> throw new IllegalArgumentException("kind desconocido: " + kind);
            }
        }
        return piezas;
    }

    /** Material de bloque existente y visible; tambien valida los destinos del recolor. */
    static Material bloque(String nombre) {
        Material m = Material.getMaterial(nombre);
        if (m == null || !m.isBlock() || m.isAir()) throw new IllegalArgumentException("material de bloque invalido: " + nombre);
        return m;
    }

    private static String textura(String valor) {
        if (valor.isEmpty() || valor.length() > 4096) throw new IllegalArgumentException("textura vacia o enorme");
        URI uri;
        try {
            String json = new String(Base64.getDecoder().decode(valor), StandardCharsets.UTF_8);
            JsonObject perfil = JsonParser.parseString(json).getAsJsonObject();
            uri = URI.create(perfil.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString());
        } catch (RuntimeException fallo) {
            throw new IllegalArgumentException("textura base64 invalida", fallo);
        }
        String esquema = uri.getScheme();
        if (!"textures.minecraft.net".equals(uri.getHost()) || !("http".equals(esquema) || "https".equals(esquema))) {
            throw new IllegalArgumentException("textura fuera de textures.minecraft.net");
        }
        return valor;
    }

    // --------------------------------------------------------------- ayudas

    /** BDE exporta por filas; JOML las lee por columnas. */
    private static Matrix4f matriz(JsonArray datos) {
        if (datos.size() != 16) throw new IllegalArgumentException("matriz de " + datos.size() + " valores");
        float[] v = new float[16];
        for (int i = 0; i < 16; i++) v[i] = finito(datos.get(i).getAsFloat());
        if (v[12] != 0 || v[13] != 0 || v[14] != 0 || v[15] != 1) throw new IllegalArgumentException("matriz no afin");
        return new Matrix4f().set(v).transpose();
    }

    private static Vector3f vector(JsonArray datos) {
        if (datos.size() != 3) throw new IllegalArgumentException("vector de " + datos.size() + " valores");
        return new Vector3f(finito(datos.get(0).getAsFloat()), finito(datos.get(1).getAsFloat()),
                finito(datos.get(2).getAsFloat()));
    }

    private static float numero(JsonObject o, String clave) {
        if (!o.has(clave) || o.get(clave).isJsonNull()) throw new IllegalArgumentException("falta " + clave);
        return finito(o.get(clave).getAsFloat());
    }

    private static float finito(float valor) {
        if (!Float.isFinite(valor) || Math.abs(valor) > MAX_VALOR) throw new IllegalArgumentException("numero no finito o mayor que 1000");
        return valor;
    }

    private static String texto(JsonObject o, String clave) {
        if (!o.has(clave) || !o.get(clave).isJsonPrimitive()) throw new IllegalArgumentException("falta " + clave);
        return o.get(clave).getAsString();
    }

    private static JsonObject objeto(JsonObject o, String clave) {
        if (!o.has(clave) || !o.get(clave).isJsonObject()) throw new IllegalArgumentException("falta " + clave);
        return o.getAsJsonObject(clave);
    }

    private static JsonArray arreglo(JsonObject o, String clave) {
        if (!o.has(clave) || !o.get(clave).isJsonArray()) throw new IllegalArgumentException("falta " + clave);
        return o.getAsJsonArray(clave);
    }

    // ------------------------------------------------------------ autoprueba

    /**
     * Carga la muestra del jar, recorre un ciclo completo de su animacion y deja en el log
     * cuantas piezas y grupos valido. No crea entidades: sirve en el arranque.
     */
    public static boolean autoprueba(Logger log) {
        try {
            ModeloBde m = cargar("muestra-prueba");
            int bloques = 0, cabezas = 0, textos = 0, animados = 0;
            for (Pieza p : m.piezas) {
                switch (p.tipo) {
                    case BLOQUE -> bloques++;
                    case CABEZA -> cabezas++;
                    case TEXTO -> textos++;
                }
            }
            for (Grupo g : m.grupos) if (g.animado()) animados++;
            Matrix4f[] g = m.nuevasMatricesGrupos();
            int ultimo = (int) Math.ceil(m.periodo);
            for (int t = 0; t <= ultimo; t++) {
                m.matricesGrupos(m.tiempoEnBucle(t), true, g);
                for (Matrix4f x : g) {
                    if (!x.isFinite()) throw new IllegalStateException("matriz no finita en el tick " + t);
                }
            }
            log.info("Modelos BDE: muestra-prueba valida, " + m.piezas.size() + " pieza(s) (" + bloques
                    + " bloque(s), " + cabezas + " cabeza(s), " + textos + " texto(s)), " + m.grupos.size()
                    + " grupo(s) (" + animados + " animado(s)), ciclo de " + ultimo + " tick(s).");
            return true;
        } catch (RuntimeException fallo) {
            log.warning("Modelos BDE: la muestra no paso la autoprueba: " + fallo.getMessage());
            return false;
        }
    }
}
