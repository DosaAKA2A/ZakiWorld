package net.ederus.edm.minas;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Una mina de prision: una caja del mundo que se rellena sola con una mezcla de
 * bloques y que cualquiera con acceso puede picar.
 *
 * Todo lo que se edita desde el menu vive aqui: la zona, la mezcla (en PARTES,
 * no en porcentajes, para que subir un bloque no obligue a bajar los demas), el
 * temporizador, el umbral de picado que adelanta el reinicio, el punto de salida
 * y el permiso de entrada. Lo vivo (cuanto se ha picado, cuando toca el proximo
 * reinicio) no se guarda: arranca de cero con el servidor.
 */
public final class Mina {

    private final String id;
    private String nombre;

    private String mundo;
    private int minX, minY, minZ, maxX, maxY, maxZ;
    private boolean conZona;

    /** Bloque -> partes. El porcentaje es partes / total. */
    private final LinkedHashMap<Material, Integer> partes = new LinkedHashMap<>();

    /** Segundos entre reinicios; 0 apaga el temporizador. */
    private int intervalo = 600;
    /** Porcentaje picado que adelanta el reinicio; 0 lo apaga. */
    private int umbral = 0;
    /** Permiso para entrar y picar; vacio = todos. */
    private String permiso = "";

    private String spawnMundo;
    private double sx, sy, sz;
    private float syaw, spitch;
    private boolean conSpawn;

    /* --- vivo --- */
    private long proximo;
    private long minados;
    private boolean reiniciando;
    private int ultimoAviso = -1;

    public Mina(String id, String nombre) {
        this.id = id;
        this.nombre = nombre;
    }

    /* ------------------------------------------------------------ identidad */

    public String id() {
        return id;
    }

    public String nombre() {
        return nombre;
    }

    public void nombre(String n) {
        this.nombre = n;
    }

    /**
     * El nombre como se ve: admite codigos & de color ("&cMINA PVP"). Sin color
     * propio sale en el ambar de las minas. El id no cambia nunca por esto.
     */
    public net.kyori.adventure.text.Component titulo() {
        return net.ederus.edm.comun.Estilo.legado(nombre).colorIfAbsent(MinasPlugin.MARCA);
    }

    /** El nombre sin codigos de color, para consola, bitacora y placeholders. */
    public String nombrePlano() {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(net.ederus.edm.comun.Estilo.legado(nombre));
    }

    /* ----------------------------------------------------------------- zona */

    public void zona(String mundo, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.mundo = mundo;
        minX = Math.min(x1, x2); maxX = Math.max(x1, x2);
        minY = Math.min(y1, y2); maxY = Math.max(y1, y2);
        minZ = Math.min(z1, z2); maxZ = Math.max(z1, z2);
        conZona = true;
        minados = 0;
    }

    public boolean conZona() {
        return conZona;
    }

    public String mundoNombre() {
        return mundo;
    }

    public World mundo() {
        return mundo == null ? null : Bukkit.getWorld(mundo);
    }

    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }

    public long volumen() {
        if (!conZona) return 0;
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public String medidas() {
        if (!conZona) return "sin zona";
        return (maxX - minX + 1) + "x" + (maxY - minY + 1) + "x" + (maxZ - minZ + 1);
    }

    public boolean contiene(String mundo, int x, int y, int z) {
        return conZona && this.mundo.equals(mundo)
                && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contiene(Location l) {
        return l != null && l.getWorld() != null
                && contiene(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    /** Dentro de la caja agrandada `radio` bloques por cada lado: para los avisos. */
    public boolean cerca(Location l, int radio) {
        if (!conZona || l == null || l.getWorld() == null || !l.getWorld().getName().equals(mundo)) return false;
        return l.getBlockX() >= minX - radio && l.getBlockX() <= maxX + radio
                && l.getBlockY() >= minY - radio && l.getBlockY() <= maxY + radio
                && l.getBlockZ() >= minZ - radio && l.getBlockZ() <= maxZ + radio;
    }

    /* --------------------------------------------------------------- mezcla */

    public Map<Material, Integer> partes() {
        return partes;
    }

    public int partesTotal() {
        int t = 0;
        for (int v : partes.values()) t += v;
        return t;
    }

    public double porcentaje(Material m) {
        int t = partesTotal();
        Integer v = partes.get(m);
        return t == 0 || v == null ? 0 : v * 100.0 / t;
    }

    public void poner(Material m, int p) {
        partes.put(m, Math.max(1, p));
    }

    public void quitar(Material m) {
        partes.remove(m);
    }

    /** El bloque con mas partes: es la cara de la mina en los menus. */
    public Material icono() {
        Material mejor = Material.STONE;
        int max = -1;
        for (Map.Entry<Material, Integer> e : partes.entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                mejor = e.getKey();
            }
        }
        return mejor;
    }

    /* ------------------------------------------------------------- reinicio */

    public int intervalo() {
        return intervalo;
    }

    public void intervalo(int s) {
        this.intervalo = Math.max(0, s);
        // El reloj arranca de nuevo con el valor nuevo, no con el resto del viejo.
        proximo = intervalo > 0 ? System.currentTimeMillis() + intervalo * 1000L : 0;
        ultimoAviso = -1;
    }

    public int umbral() {
        return umbral;
    }

    public void umbral(int u) {
        this.umbral = Math.max(0, Math.min(100, u));
    }

    public long proximo() {
        return proximo;
    }

    public void proximo(long ms) {
        this.proximo = ms;
        this.ultimoAviso = -1;
    }

    /** Segundos hasta el proximo reinicio, o -1 si no hay ninguno programado. */
    public int segundos() {
        if (proximo <= 0) return -1;
        return (int) Math.max(0, Math.ceil((proximo - System.currentTimeMillis()) / 1000.0));
    }

    /** "4:12", o "-" si no hay reinicio programado. */
    public String cuentaAtras() {
        int s = segundos();
        if (s < 0) return "-";
        return (s / 60) + ":" + String.format(Locale.US, "%02d", s % 60);
    }

    public int ultimoAviso() {
        return ultimoAviso;
    }

    public void ultimoAviso(int s) {
        this.ultimoAviso = s;
    }

    public long minados() {
        return minados;
    }

    public void minado() {
        minados++;
    }

    public void minados(long v) {
        this.minados = v;
    }

    public double porcentajeMinado() {
        long v = volumen();
        return v == 0 ? 0 : Math.min(100.0, minados * 100.0 / v);
    }

    public boolean reiniciando() {
        return reiniciando;
    }

    public void reiniciando(boolean r) {
        this.reiniciando = r;
    }

    /* -------------------------------------------------------------- permiso */

    public String permiso() {
        return permiso;
    }

    public void permiso(String p) {
        this.permiso = p == null ? "" : p.trim();
    }

    /* ---------------------------------------------------------------- spawn */

    public boolean conSpawn() {
        return conSpawn;
    }

    public void spawn(Location l) {
        if (l == null || l.getWorld() == null) {
            conSpawn = false;
            return;
        }
        spawnMundo = l.getWorld().getName();
        sx = l.getX(); sy = l.getY(); sz = l.getZ();
        syaw = l.getYaw(); spitch = l.getPitch();
        conSpawn = true;
    }

    public Location spawn() {
        if (!conSpawn) return null;
        World w = Bukkit.getWorld(spawnMundo);
        return w == null ? null : new Location(w, sx, sy, sz, syaw, spitch);
    }

    /**
     * A donde se lleva a quien viaja a la mina o a quien hay que sacar de ella: el
     * punto de salida si lo hay; si no, encima del centro de la caja.
     */
    public Location salida() {
        Location s = spawn();
        if (s != null) return s;
        World w = mundo();
        if (w == null || !conZona) return null;
        return new Location(w, (minX + maxX) / 2.0 + 0.5, maxY + 1, (minZ + maxZ) / 2.0 + 0.5);
    }

    public String spawnTexto() {
        if (!conSpawn) return "encima de la mina";
        return spawnMundo + " " + (int) sx + " " + (int) sy + " " + (int) sz;
    }

    /* -------------------------------------------------------------- guardar */

    void guardarEn(org.bukkit.configuration.ConfigurationSection s) {
        s.set("nombre", nombre);
        if (conZona) {
            s.set("mundo", mundo);
            s.set("zona.x1", minX); s.set("zona.y1", minY); s.set("zona.z1", minZ);
            s.set("zona.x2", maxX); s.set("zona.y2", maxY); s.set("zona.z2", maxZ);
        }
        for (Map.Entry<Material, Integer> e : partes.entrySet()) {
            s.set("bloques." + e.getKey().name(), e.getValue());
        }
        s.set("intervalo", intervalo);
        s.set("umbral", umbral);
        s.set("permiso", permiso.isEmpty() ? null : permiso);
        if (conSpawn) {
            s.set("spawn.mundo", spawnMundo);
            s.set("spawn.x", sx); s.set("spawn.y", sy); s.set("spawn.z", sz);
            s.set("spawn.yaw", (double) syaw); s.set("spawn.pitch", (double) spitch);
        }
    }

    static Mina leerDe(String id, org.bukkit.configuration.ConfigurationSection s) {
        Mina m = new Mina(id, s.getString("nombre", id));
        if (s.contains("zona.x1")) {
            m.zona(s.getString("mundo", "world"),
                    s.getInt("zona.x1"), s.getInt("zona.y1"), s.getInt("zona.z1"),
                    s.getInt("zona.x2"), s.getInt("zona.y2"), s.getInt("zona.z2"));
        }
        org.bukkit.configuration.ConfigurationSection b = s.getConfigurationSection("bloques");
        if (b != null) {
            for (String k : b.getKeys(false)) {
                Material mat = Material.matchMaterial(k);
                if (mat != null && mat.isBlock()) m.partes.put(mat, Math.max(1, b.getInt(k, 1)));
            }
        }
        m.intervalo = Math.max(0, s.getInt("intervalo", 600));
        m.umbral = Math.max(0, Math.min(100, s.getInt("umbral", 0)));
        m.permiso = s.getString("permiso", "");
        if (s.contains("spawn.mundo")) {
            m.spawnMundo = s.getString("spawn.mundo");
            m.sx = s.getDouble("spawn.x"); m.sy = s.getDouble("spawn.y"); m.sz = s.getDouble("spawn.z");
            m.syaw = (float) s.getDouble("spawn.yaw"); m.spitch = (float) s.getDouble("spawn.pitch");
            m.conSpawn = true;
        }
        if (m.intervalo > 0) m.proximo = System.currentTimeMillis() + m.intervalo * 1000L;
        return m;
    }
}
