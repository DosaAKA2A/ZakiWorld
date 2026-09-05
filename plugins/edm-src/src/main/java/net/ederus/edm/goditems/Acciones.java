package net.ederus.edm.goditems;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import net.ederus.edm.anomaly.core.Compat;
import net.ederus.edm.anomaly.core.Fx;
import net.ederus.edm.comun.Estilo;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

/**
 * El catalogo de acciones de GodItems.
 *
 * Todas siguen la misma forma: `ACCION @objetivo clave:valor ... texto`. Se
 * apoyan en la biblioteca de efectos que EDM ya tiene rodada en produccion
 * (`anomaly.core.Fx` y `anomaly.core.Compat`) en vez de reimplementar
 * particulas y sonidos, que es de donde salen las diferencias raras entre lo que
 * hace un jefe y lo que hace un item.
 */
public final class Acciones {

    private Acciones() { }

    private static final Map<String, Accion> CATALOGO = new LinkedHashMap<>();

    public static Accion buscar(String nombre) {
        return nombre == null ? null : CATALOGO.get(nombre.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    public static java.util.Set<String> nombres() {
        return CATALOGO.keySet();
    }

    private static void reg(String nombre, Accion a) {
        CATALOGO.put(nombre, a);
    }

    /** Igual, pero todo lo que va detras del objetivo es texto y no se parsea. */
    private static void regTexto(String nombre, Accion a) {
        CATALOGO.put(nombre, new Accion() {
            @Override
            public void correr(Ctx ctx, Args args) {
                a.correr(ctx, args);
            }

            @Override
            public boolean textoLibre() {
                return true;
            }
        });
    }

    static {
        vidaYDano();
        movimiento();
        efectos();
        visual();
        textos();
        sonido();
        mundo();
        juego();
        flujo();
    }

    /* ================================================================ vida */

    private static void vidaYDano() {
        reg("DANO", (ctx, a) -> {
            double n = a.d("cantidad", 1);
            for (LivingEntity e : Objetivos.vivos(ctx, a.selector() == null ? "@golpeado" : a.selector())) {
                e.damage(n, ctx.jugador());
            }
        });

        /* Pega sin mover. Se guarda la velocidad y se repone: el empuje lo
         * aplica damage() en el acto, asi que reponerla justo despues lo anula
         * sin tener que tocar el atributo de resistencia al empuje, que ademas
         * seria permanente. */
        reg("DANO_SIN_EMPUJE", (ctx, a) -> {
            double n = a.d("cantidad", 1);
            for (LivingEntity e : Objetivos.vivos(ctx, a.selector() == null ? "@golpeado" : a.selector())) {
                Vector antes = e.getVelocity();
                e.damage(n, ctx.jugador());
                e.setVelocity(antes);
            }
        });

        reg("DANO_PORCENTAJE", (ctx, a) -> {
            double pct = a.d("cantidad", 10) / 100.0;
            boolean deMax = !a.s("de", "max").equalsIgnoreCase("actual");
            for (LivingEntity e : Objetivos.vivos(ctx, a.selector() == null ? "@golpeado" : a.selector())) {
                double base = deMax ? Textos.maxVida(e) : e.getHealth();
                e.damage(Math.max(0, base * pct), ctx.jugador());
            }
        });

        reg("CURAR", (ctx, a) -> {
            double n = a.d("cantidad", 2);
            for (LivingEntity e : Objetivos.vivos(ctx, a.selector())) {
                e.setHealth(Math.max(0, Math.min(Textos.maxVida(e), e.getHealth() + n)));
            }
        });

        reg("CURAR_PORCENTAJE", (ctx, a) -> {
            double pct = a.d("cantidad", 10) / 100.0;
            for (LivingEntity e : Objetivos.vivos(ctx, a.selector())) {
                double max = Textos.maxVida(e);
                e.setHealth(Math.max(0, Math.min(max, e.getHealth() + max * pct)));
            }
        });

        reg("ABSORCION", (ctx, a) -> {
            double n = a.d("cantidad", 4);
            boolean sumar = a.b("sumar", false);
            for (LivingEntity e : Objetivos.vivos(ctx, a.selector())) {
                e.setAbsorptionAmount(Math.max(0, sumar ? e.getAbsorptionAmount() + n : n));
            }
        });

        reg("INVULNERABLE", (ctx, a) -> {
            int t = a.ticks("duracion", 40);
            for (Entity e : Objetivos.resolver(ctx, a.selector())) {
                e.setInvulnerable(true);
                ctx.modulo().core().getServer().getScheduler().runTaskLater(ctx.modulo().core(),
                        () -> { if (e.isValid()) e.setInvulnerable(false); }, Math.max(1, t));
            }
        });

        reg("SET_VIDA", (ctx, a) -> {
            double n = a.d("cantidad", 20);
            for (LivingEntity e : Objetivos.vivos(ctx, a.selector())) {
                e.setHealth(Math.max(0.5, Math.min(Textos.maxVida(e), n)));
            }
        });
    }

    /* ========================================================== movimiento */

    private static void movimiento() {
        reg("EMPUJAR", (ctx, a) -> empujon(ctx, a, 1));
        reg("ATRAER", (ctx, a) -> empujon(ctx, a, -1));

        reg("DASH", (ctx, a) -> {
            double f = a.d("fuerza", 1.4);
            double alto = a.d("alto", 0.25);
            for (Entity e : Objetivos.resolver(ctx, a.selector())) {
                Vector dir = e.getLocation().getDirection().normalize().multiply(f);
                dir.setY(Math.max(dir.getY(), alto));
                e.setVelocity(dir);
                if (e instanceof Player p) ctx.modulo().vuelo().prestar(ctx.modulo(), p, 60);
            }
        });

        reg("SALTO", (ctx, a) -> {
            double f = a.d("fuerza", 1.0);
            for (Entity e : Objetivos.resolver(ctx, a.selector())) {
                Vector v = e.getVelocity();
                v.setY(f);
                e.setVelocity(v);
                if (e instanceof Player p) ctx.modulo().vuelo().prestar(ctx.modulo(), p, 60);
            }
        });

        reg("LEVANTAR", (ctx, a) -> {
            double f = a.d("fuerza", 1.2);
            int prestamo = a.ticks("vuelo", 80);
            for (Entity e : Objetivos.resolver(ctx, a.selector())) {
                e.setVelocity(new Vector(0, f, 0));
                /* Sin esto el servidor ve a alguien subiendo sin permiso de
                 * vuelo y lo echa. Es el fallo clasico de estos plugins. */
                if (e instanceof Player p) ctx.modulo().vuelo().prestar(ctx.modulo(), p, prestamo);
            }
        });

        reg("TELETRANSPORTE", (ctx, a) -> {
            Location destino;
            if (a.tiene("x") || a.tiene("y") || a.tiene("z")) {
                World w = a.tiene("mundo")
                        ? ctx.modulo().core().getServer().getWorld(a.s("mundo", ""))
                        : ctx.lugar().getWorld();
                if (w == null) return;
                Location base = ctx.lugar();
                destino = new Location(w,
                        a.d("x", base.getX()), a.d("y", base.getY()), a.d("z", base.getZ()),
                        base.getYaw(), base.getPitch());
            } else {
                Entity ref = Objetivos.uno(ctx, a.selector() == null ? "@golpeado" : a.selector());
                if (ref == null) return;
                destino = ref.getLocation();
            }
            Player j = ctx.jugador();
            if (j != null) j.teleport(destino);
        });

        /* Adonde estas mirando. Se para en el bloque, no en la entidad: si se
         * parara en la entidad, apuntar a un mob te teletransportaria DENTRO de
         * el y te asfixiarias. */
        reg("TP_MIRADA", (ctx, a) -> {
            Player j = ctx.jugador();
            if (j == null) return;
            double alcance = a.d("alcance", 25);
            RayTraceResult r = j.getWorld().rayTraceBlocks(j.getEyeLocation(),
                    j.getEyeLocation().getDirection(), alcance, org.bukkit.FluidCollisionMode.NEVER, true);
            Location destino;
            if (r == null || r.getHitBlock() == null) {
                destino = j.getEyeLocation().add(j.getEyeLocation().getDirection().multiply(alcance));
            } else {
                destino = r.getHitBlock().getLocation().add(0.5, 1.05, 0.5);
                if (r.getHitBlockFace() != null) {
                    destino.add(r.getHitBlockFace().getDirection().multiply(0.4));
                }
            }
            destino.setYaw(j.getLocation().getYaw());
            destino.setPitch(j.getLocation().getPitch());
            j.teleport(destino);
        });

        /* Clavar en el sitio: lentitud a tope y salto imposible. Es la unica
         * forma limpia de inmovilizar sin pelearse con el movimiento del
         * cliente, que en Bedrock ademas se corrige solo y da tirones. */
        reg("ANCLAR", (ctx, a) -> {
            int t = a.ticks("duracion", 40);
            for (LivingEntity e : Objetivos.vivos(ctx, a.selector())) {
                Compat.apply(e, "slowness", t, 250);
                Compat.apply(e, "jump_boost", t, 128);
            }
        });
    }

    private static void empujon(Ctx ctx, Args a, int signo) {
        double f = a.d("fuerza", 1.0) * signo;
        double alto = a.d("alto", 0.3);
        Location centro = ctx.lugar();
        for (Entity e : Objetivos.resolver(ctx, a.selector() == null ? "@golpeado" : a.selector())) {
            Vector v = e.getLocation().toVector().subtract(centro.toVector());
            if (v.lengthSquared() < 0.01) v = ctx.jugador() == null
                    ? new Vector(0, 1, 0) : ctx.jugador().getLocation().getDirection();
            v = v.normalize().multiply(f);
            v.setY(alto * signo);
            e.setVelocity(v);
        }
    }

    /* ============================================================= efectos */

    private static void efectos() {
        reg("POCION", (ctx, a) -> {
            String tipo = a.s("tipo", a.texto());
            int dur = a.ticks("duracion", 100);
            int nivel = Math.max(1, a.i("nivel", 1));
            PotionEffectType t = Compat.effect(tipo.toLowerCase(Locale.ROOT));
            if (t == null) {
                ctx.modulo().getLogger().warning("[GodItems] Pocion desconocida: " + tipo);
                return;
            }
            for (LivingEntity e : Objetivos.vivos(ctx, a.selector())) {
                Compat.apply(e, tipo.toLowerCase(Locale.ROOT), dur, nivel - 1);
            }
        });

        reg("QUITAR_POCION", (ctx, a) -> {
            String tipo = a.s("tipo", a.texto());
            for (LivingEntity e : Objetivos.vivos(ctx, a.selector())) {
                if (tipo == null || tipo.isBlank() || tipo.equalsIgnoreCase("TODAS")) {
                    for (org.bukkit.potion.PotionEffect pe : new ArrayList<>(e.getActivePotionEffects())) {
                        e.removePotionEffect(pe.getType());
                    }
                    continue;
                }
                PotionEffectType t = Compat.effect(tipo.toLowerCase(Locale.ROOT));
                if (t != null) e.removePotionEffect(t);
            }
        });

        reg("FUEGO", (ctx, a) -> {
            int t = a.ticks("duracion", 60);
            for (Entity e : Objetivos.resolver(ctx, a.selector() == null ? "@golpeado" : a.selector())) {
                e.setFireTicks(t);
            }
        });

        reg("APAGAR", (ctx, a) -> {
            for (Entity e : Objetivos.resolver(ctx, a.selector())) e.setFireTicks(0);
        });

        reg("CONGELAR", (ctx, a) -> {
            int t = a.ticks("duracion", 140);
            for (Entity e : Objetivos.resolver(ctx, a.selector() == null ? "@golpeado" : a.selector())) {
                try {
                    e.setFreezeTicks(Math.max(e.getFreezeTicks(), t));
                } catch (Throwable ignored) {
                }
            }
        });

        reg("BRILLO", (ctx, a) -> {
            int t = a.ticks("duracion", 100);
            for (Entity e : Objetivos.resolver(ctx, a.selector())) {
                e.setGlowing(true);
                ctx.modulo().core().getServer().getScheduler().runTaskLater(ctx.modulo().core(),
                        () -> { if (e.isValid()) e.setGlowing(false); }, Math.max(1, t));
            }
        });
    }

    /* ============================================================== visual */

    private static void visual() {
        reg("PARTICULA", (ctx, a) -> {
            Location l = punto(ctx, a);
            if (l == null || l.getWorld() == null) return;
            pintar(ctx, a, l);
        });

        reg("ANILLO", (ctx, a) -> {
            Location c = punto(ctx, a);
            if (c == null || c.getWorld() == null) return;
            double r = a.d("radio", 3);
            int puntos = a.i("puntos", (int) Math.max(12, r * 12));
            Fx.ring(c, r, puntos, l -> pintar(ctx, a, l));
        });

        reg("ESFERA", (ctx, a) -> {
            Location c = punto(ctx, a);
            if (c == null || c.getWorld() == null) return;
            Fx.sphere(c, a.d("radio", 3), a.i("puntos", 80), l -> pintar(ctx, a, l));
        });

        reg("LINEA", (ctx, a) -> {
            Player j = ctx.jugador();
            Location desde = a.tiene("desde") && a.s("desde", "").equalsIgnoreCase("ojos") && j != null
                    ? j.getEyeLocation() : ctx.lugar();
            Entity destino = Objetivos.uno(ctx, a.selector() == null ? "@golpeado" : a.selector());
            if (desde == null || destino == null) return;
            Fx.beam(desde, destino.getLocation().add(0, destino.getHeight() / 2, 0),
                    Math.max(0.1, a.d("paso", 0.4)), l -> pintar(ctx, a, l));
        });

        reg("HELICE", (ctx, a) -> {
            Location c = punto(ctx, a);
            if (c == null || c.getWorld() == null) return;
            Fx.helix(c, a.d("radio", 1.2), a.d("alto", 3), a.i("puntos", 60), a.d("vueltas", 3),
                    l -> pintar(ctx, a, l));
        });

        reg("RAYO_BEACON", (ctx, a) -> {
            Location l = punto(ctx, a);
            if (l == null) return;
            Material funda = material(a.s("funda", "YELLOW_STAINED_GLASS"), Material.YELLOW_STAINED_GLASS);
            Material nucleo = material(a.s("nucleo", "OCHRE_FROGLIGHT"), Material.GLOWSTONE);
            Efectos.rayoBeacon(ctx.modulo(), l, a.ticks("duracion", 60), funda, nucleo, a.d("ancho", 0.6));
        });

        reg("ESPADA_CAIDA", (ctx, a) -> {
            Location l = punto(ctx, a);
            if (l == null) return;
            Efectos.espadaCaida(ctx.modulo(), l, a.i("cantidad", 12), a.d("radio", 5),
                    material(a.s("material", "NETHERITE_SWORD"), Material.IRON_SWORD),
                    a.d("dano", 0), a.ticks("clavadas", 60));
        });

        reg("CIELO", (ctx, a) -> {
            long hora = (long) a.d("hora", 18000);
            Efectos.cielo(ctx.modulo(), Objetivos.jugadores(ctx, a.selector() == null ? "@todos" : a.selector()),
                    hora, a.ticks("duracion", 100));
        });

        reg("RELAMPAGO", (ctx, a) -> {
            Location l = punto(ctx, a);
            if (l == null || l.getWorld() == null) return;
            if (a.b("dano", false)) l.getWorld().strikeLightning(l);
            else l.getWorld().strikeLightningEffect(l);
        });

        reg("EXPLOSION", (ctx, a) -> {
            Location l = punto(ctx, a);
            if (l == null) return;
            Efectos.explosion(ctx.modulo(), l, a.d("radio", 5), a.i("anillos", 3),
                    a.d("dano", 0), a.b("empuje", true), ctx.jugador());
        });
    }

    /** El punto al que apunta una accion visual, con su desplazamiento en alto. */
    private static Location punto(Ctx ctx, Args a) {
        Location l = Objetivos.lugar(ctx, a.selector());
        if (l == null) return null;
        return l.clone().add(a.d("dx", 0), a.d("alto", 0) + a.d("dy", 0), a.d("dz", 0));
    }

    /**
     * Pinta UN punto.
     *
     * Todas las visuales pasan por aqui, asi que todas entienden las mismas
     * claves y todas saben pasarle a la particula el dato que exige (color,
     * bloque, item, vibracion...). Antes solo se sabia pasar el color del
     * polvo: el resto salia con el dato por defecto y por eso un BLOCK pintaba
     * piedra pusieras lo que pusieras.
     */
    private static void pintar(Ctx ctx, Args a, Location l) {
        World w = l.getWorld();
        if (w == null) return;
        Particle p = Particulas.particula(a.s("tipo", "FLAME"));
        if (p == null) {
            ctx.modulo().avisoUnaVez("part." + a.s("tipo", "?"),
                    "Particula desconocida: " + a.s("tipo", "?") + " (mira /gi particulas)");
            return;
        }
        int cuantas = Math.max(1, Math.min(500, a.i("cantidad", 1)));
        double ox = a.d("ancho", 0.0);
        double oy = a.d("altura", ox);
        double oz = a.d("fondo", ox);
        double vel = a.d("velocidad", 0.0);

        /* VIBRATION y TRAIL viajan hacia algo. Por omision, hacia el objetivo
         * de la propia linea; si no hay, hacia arriba. */
        Entity hacia = null;
        if (a.tiene("hacia")) hacia = Objetivos.uno(ctx, a.s("hacia", "@golpeado"));
        else if (a.selector() != null) hacia = Objetivos.uno(ctx, a.selector());

        Object dato = Particulas.datos(p, a, l, hacia);
        if (dato == null) Compat.spawn(w, p, l, cuantas, ox, oy, oz, vel);
        else Compat.spawn(w, p, l, cuantas, ox, oy, oz, vel, dato);
    }

    private static Material material(String s, Material pordefecto) {
        if (s == null) return pordefecto;
        Material m = Material.matchMaterial(s.trim().toUpperCase(Locale.ROOT));
        return m == null ? pordefecto : m;
    }

    /* ============================================================== textos */

    private static void textos() {
        regTexto("MENSAJE", (ctx, a) -> {
            Component c = Estilo.legado(Textos.aplicar(ctx, a.texto()));
            for (Player p : Objetivos.jugadores(ctx, a.selector())) p.sendMessage(c);
        });

        regTexto("ACTIONBAR", (ctx, a) -> {
            Component c = Estilo.legado(Textos.aplicar(ctx, a.texto()));
            for (Player p : Objetivos.jugadores(ctx, a.selector())) p.sendActionBar(c);
        });

        /* "Titulo|Subtitulo": la barra vertical es el separador porque es lo
         * unico que no aparece en un texto de Minecraft por accidente. */
        reg("TITULO", (ctx, a) -> {
            String todo = Textos.aplicar(ctx, a.texto());
            String arriba = todo;
            String abajo = "";
            int barra = todo.indexOf('|');
            if (barra >= 0) {
                arriba = todo.substring(0, barra);
                abajo = todo.substring(barra + 1);
            }
            Title t = Title.title(Estilo.legado(arriba), Estilo.legado(abajo),
                    Title.Times.times(
                            java.time.Duration.ofMillis(a.ticks("entrada", 10) * 50L),
                            java.time.Duration.ofMillis(a.ticks("duracion", 40) * 50L),
                            java.time.Duration.ofMillis(a.ticks("salida", 10) * 50L)));
            for (Player p : Objetivos.jugadores(ctx, a.selector())) p.showTitle(t);
        });

        reg("BOSSBAR", (ctx, a) -> {
            BossBar.Color color;
            try {
                color = BossBar.Color.valueOf(a.s("color", "YELLOW").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                color = BossBar.Color.YELLOW;
            }
            BossBar barra = BossBar.bossBar(Estilo.legado(Textos.aplicar(ctx, a.texto())),
                    (float) Math.max(0, Math.min(1, a.d("progreso", 1.0))),
                    color, BossBar.Overlay.PROGRESS);
            List<Player> quienes = Objetivos.jugadores(ctx, a.selector());
            for (Player p : quienes) p.showBossBar(barra);
            int t = a.ticks("duracion", 60);
            ctx.modulo().core().getServer().getScheduler().runTaskLater(ctx.modulo().core(), () -> {
                for (Player p : quienes) p.hideBossBar(barra);
            }, Math.max(1, t));
        });
    }

    /* ============================================================== sonido */

    private static void sonido() {
        reg("SONIDO", (ctx, a) -> {
            String clave = a.s("sonido", a.texto());
            if (clave == null || clave.isBlank()) return;
            float vol = (float) a.d("volumen", 1.0);
            float tono = (float) a.d("tono", 1.0);
            for (Player p : Objetivos.jugadores(ctx, a.selector())) {
                p.playSound(p.getLocation(), clave, vol, tono);
            }
        });

        reg("SONIDO_GLOBAL", (ctx, a) -> {
            String clave = a.s("sonido", a.texto());
            Location l = punto(ctx, a);
            if (clave == null || clave.isBlank() || l == null || l.getWorld() == null) return;
            Compat.sound(l.getWorld(), l, clave, (float) a.d("volumen", 1.0), (float) a.d("tono", 1.0));
        });

        /*
         * Sonidos "custom" sin resource pack: se apilan varios de los que ya
         * trae el juego con tonos y retardos distintos. Es como se fabrica la
         * voz de Alba, y es la razon por la que este modulo no necesita pack.
         *
         *   SECUENCIA @yo sonidos:'entity.warden.roar|1.4|0.5|0, block.bell.use|1|0.4|6'
         */
        reg("SECUENCIA", (ctx, a) -> {
            String lista = a.s("sonidos", a.texto());
            if (lista == null || lista.isBlank()) return;
            Location l = punto(ctx, a);
            if (l == null || l.getWorld() == null) return;
            World w = l.getWorld();
            for (String trozo : lista.split(",")) {
                String[] partes = trozo.trim().split("\\|");
                if (partes.length == 0 || partes[0].isBlank()) continue;
                String clave = partes[0].trim();
                float vol = partes.length > 1 ? (float) Numeros.decimal(partes[1], 1) : 1f;
                float tono = partes.length > 2 ? (float) Numeros.decimal(partes[2], 1) : 1f;
                int retardo = partes.length > 3 ? Numeros.ticks(partes[3], 0) : 0;
                if (retardo <= 0) {
                    Compat.sound(w, l, clave, vol, tono);
                } else {
                    ctx.modulo().core().getServer().getScheduler().runTaskLater(ctx.modulo().core(),
                            () -> Compat.sound(w, l, clave, vol, tono), retardo);
                }
            }
        });
    }

    /* =============================================================== mundo */

    private static void mundo() {
        /* Un bloque que se pone y se quita solo. Nunca pisa nada que no sea
         * aire o liquido: un item que borra el suelo de una base es un item que
         * se retira a la semana. */
        reg("BLOQUE_TEMPORAL", (ctx, a) -> {
            Location l = punto(ctx, a);
            if (l == null || l.getWorld() == null) return;
            Material m = material(a.s("material", "GLASS"), Material.GLASS);
            int t = a.ticks("duracion", 60);
            int radio = Math.max(0, Math.min(6, a.i("radio", 0)));
            List<org.bukkit.block.Block> puestos = new ArrayList<>();
            List<org.bukkit.block.data.BlockData> antes = new ArrayList<>();
            for (int x = -radio; x <= radio; x++) {
                for (int y = -radio; y <= radio; y++) {
                    for (int z = -radio; z <= radio; z++) {
                        if (radio > 0 && x * x + y * y + z * z > radio * radio) continue;
                        org.bukkit.block.Block b = l.clone().add(x, y, z).getBlock();
                        if (!b.getType().isAir() && !b.isLiquid()) continue;
                        antes.add(b.getBlockData());
                        puestos.add(b);
                        b.setType(m, false);
                    }
                }
            }
            if (puestos.isEmpty()) return;
            ctx.modulo().core().getServer().getScheduler().runTaskLater(ctx.modulo().core(), () -> {
                for (int i = 0; i < puestos.size(); i++) {
                    org.bukkit.block.Block b = puestos.get(i);
                    if (b.getType() == m) b.setBlockData(antes.get(i), false);
                }
            }, Math.max(1, t));
        });

        reg("INVOCAR", (ctx, a) -> {
            Location l = punto(ctx, a);
            if (l == null || l.getWorld() == null) return;
            EntityType tipo;
            try {
                tipo = EntityType.valueOf(a.s("tipo", "ZOMBIE").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                ctx.modulo().getLogger().warning("[GodItems] Criatura desconocida: " + a.s("tipo", "?"));
                return;
            }
            int cuantos = Math.max(1, Math.min(20, a.i("cantidad", 1)));
            double radio = a.d("radio", 2);
            double vida = a.d("vida", -1);
            int duracion = a.ticks("duracion", 0);
            String nombre = a.s("nombre", null);
            for (int i = 0; i < cuantos; i++) {
                Location donde = l.clone().add(
                        (Math.random() - 0.5) * radio * 2, 0, (Math.random() - 0.5) * radio * 2);
                Entity e = l.getWorld().spawnEntity(donde, tipo);
                if (e instanceof LivingEntity le) {
                    if (vida > 0) {
                        Compat.setAttribute(le, "max_health", vida);
                        le.setHealth(Math.min(vida, Textos.maxVida(le)));
                    }
                    if (nombre != null) {
                        le.customName(Estilo.legado(Textos.aplicar(ctx, nombre)));
                        le.setCustomNameVisible(true);
                    }
                }
                if (duracion > 0) {
                    ctx.modulo().core().getServer().getScheduler().runTaskLater(ctx.modulo().core(),
                            () -> { if (e.isValid()) e.remove(); }, duracion);
                }
            }
        });

        reg("PROYECTIL", (ctx, a) -> {
            Player j = ctx.jugador();
            if (j == null) return;
            Class<? extends Projectile> clase = proyectil(a.s("tipo", "ARROW"));
            if (clase == null) {
                ctx.modulo().getLogger().warning("[GodItems] Proyectil desconocido: " + a.s("tipo", "?"));
                return;
            }
            Projectile p = j.launchProjectile(clase,
                    j.getEyeLocation().getDirection().multiply(a.d("velocidad", 1.6)));
            /* Se marca con el id del item para que PROYECTIL_IMPACTA sepa que
             * este proyectil sale de este GodItem y no de un arco cualquiera. */
            p.getPersistentDataContainer().set(ctx.modulo().identidad().clave(),
                    PersistentDataType.STRING, ctx.definicion().id());
            if (a.b("fuego", false)) p.setFireTicks(200);
        });
    }

    private static Class<? extends Projectile> proyectil(String s) {
        return switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "ARROW", "FLECHA" -> org.bukkit.entity.Arrow.class;
            case "SNOWBALL", "BOLA_NIEVE" -> org.bukkit.entity.Snowball.class;
            case "EGG", "HUEVO" -> org.bukkit.entity.Egg.class;
            case "ENDER_PEARL", "PERLA" -> org.bukkit.entity.EnderPearl.class;
            case "FIREBALL", "BOLA_FUEGO" -> org.bukkit.entity.Fireball.class;
            case "SMALL_FIREBALL" -> org.bukkit.entity.SmallFireball.class;
            case "DRAGON_FIREBALL" -> org.bukkit.entity.DragonFireball.class;
            case "WITHER_SKULL", "CALAVERA" -> org.bukkit.entity.WitherSkull.class;
            case "SHULKER_BULLET" -> org.bukkit.entity.ShulkerBullet.class;
            case "TRIDENT", "TRIDENTE" -> org.bukkit.entity.Trident.class;
            case "LLAMA_SPIT" -> org.bukkit.entity.LlamaSpit.class;
            case "POCION", "SPLASH_POTION" -> org.bukkit.entity.ThrownPotion.class;
            default -> null;
        };
    }

    /* =============================================================== juego */

    private static void juego() {
        reg("DAR_ITEM", (ctx, a) -> {
            Material m = material(a.s("material", "STONE"), null);
            if (m == null) return;
            int n = Math.max(1, a.i("cantidad", 1));
            ItemStack pila = new ItemStack(m, n);
            String nombre = a.s("nombre", null);
            if (nombre != null) {
                ItemMeta meta = pila.getItemMeta();
                if (meta != null) {
                    meta.displayName(Estilo.legado(Textos.aplicar(ctx, nombre)));
                    pila.setItemMeta(meta);
                }
            }
            for (Player p : Objetivos.jugadores(ctx, a.selector())) entregar(p, pila.clone());
        });

        reg("DAR_GODITEM", (ctx, a) -> {
            String id = GodItem.normalizar(a.s("id", a.texto()));
            GodItem def = ctx.modulo().registro().porId(id);
            if (def == null) {
                ctx.modulo().getLogger().warning("[GodItems] DAR_GODITEM: no existe '" + id + "'.");
                return;
            }
            ItemStack pila = ctx.modulo().fabricar(def, Math.max(1, a.i("cantidad", 1)));
            if (pila == null) return;
            for (Player p : Objetivos.jugadores(ctx, a.selector())) entregar(p, pila.clone());
        });

        reg("QUITAR_ITEM", (ctx, a) -> {
            Material m = material(a.s("material", ""), null);
            int n = Math.max(1, a.i("cantidad", 1));
            for (Player p : Objetivos.jugadores(ctx, a.selector())) {
                if (m != null) {
                    p.getInventory().removeItem(new ItemStack(m, n));
                } else if (a.tiene("goditem")) {
                    quitarGodItem(ctx, p, GodItem.normalizar(a.s("goditem", "")), n);
                }
            }
        });

        /*
         * La durabilidad solo se toca en items NATIVOS. En uno enlazado la
         * lleva MMOItems con su propio sistema (durabilidad custom en NBT);
         * escribir el Damageable de Bukkit encima le descuadra la cuenta y el
         * item se rompe cuando no toca.
         */
        reg("DURABILIDAD", (ctx, a) -> {
            if (ctx.definicion().enlazado()) {
                ctx.modulo().avisoUnaVez("dura." + ctx.definicion().id(),
                        ctx.definicion().id() + " es ENLAZADO: DURABILIDAD no se aplica."
                                + " La durabilidad de un item de MMOItems la lleva MMOItems.");
                return;
            }
            ItemStack item = ctx.item();
            if (item == null) return;
            ItemMeta meta = item.getItemMeta();
            if (!(meta instanceof Damageable d)) return;
            int cambio = a.i("cantidad", -1);
            int max = item.getType().getMaxDurability();
            if (max <= 0) return;
            int nuevo = Math.max(0, Math.min(max, d.getDamage() - cambio));
            d.setDamage(nuevo);
            item.setItemMeta(meta);
            if (nuevo >= max && a.b("romper", true)) {
                item.setAmount(0);
            }
        });

        reg("DINERO", (ctx, a) -> {
            double n = a.d("cantidad", 0);
            if (n == 0) return;
            var eco = ctx.modulo().economia();
            if (eco == null) {
                ctx.modulo().avisoUnaVez("vault", "DINERO no funciona: no hay economia de Vault enganchada.");
                return;
            }
            for (Player p : Objetivos.jugadores(ctx, a.selector())) {
                if (n > 0) eco.depositPlayer(p, n);
                else eco.withdrawPlayer(p, -n);
            }
        });

        reg("EXP", (ctx, a) -> {
            int n = a.i("cantidad", 0);
            boolean niveles = a.b("niveles", false);
            for (Player p : Objetivos.jugadores(ctx, a.selector())) {
                if (niveles) p.setLevel(Math.max(0, p.getLevel() + n));
                else p.giveExp(n);
            }
        });

        regTexto("COMANDO_CONSOLA", (ctx, a) -> {
            String linea = Textos.aplicar(ctx, a.texto());
            if (linea.isBlank()) return;
            ctx.modulo().core().getServer().dispatchCommand(
                    ctx.modulo().core().getServer().getConsoleSender(), linea);
        });

        regTexto("COMANDO_JUGADOR", (ctx, a) -> {
            for (Player p : Objetivos.jugadores(ctx, a.selector())) {
                String linea = Textos.aplicar(ctx.copia().objetivo(p), a.texto());
                if (linea.isBlank()) continue;
                p.performCommand(linea.startsWith("/") ? linea.substring(1) : linea);
            }
        });
    }

    private static void quitarGodItem(Ctx ctx, Player p, String id, int cuantos) {
        int quedan = cuantos;
        ItemStack[] contenido = p.getInventory().getContents();
        for (int i = 0; i < contenido.length && quedan > 0; i++) {
            ItemStack it = contenido[i];
            if (it == null) continue;
            if (!id.equals(ctx.modulo().identidad().idDe(it))) continue;
            int quita = Math.min(quedan, it.getAmount());
            it.setAmount(it.getAmount() - quita);
            quedan -= quita;
        }
    }

    private static void entregar(Player p, ItemStack pila) {
        Map<Integer, ItemStack> sobra = p.getInventory().addItem(pila);
        for (ItemStack s : sobra.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), s);
        }
    }

    /* =============================================================== flujo */

    private static void flujo() {
        /*
         * VARIABLE <nombre> <operacion> <valor>
         *   operaciones: poner, sumar, restar, multiplicar, borrar
         *   ambito:item (por defecto) o ambito:jugador, y va DELANTE del nombre.
         */
        reg("VARIABLE", (ctx, a) -> {
            List<String> p = a.palabras();
            if (p.isEmpty()) return;
            String nombre = p.get(0);
            String op = p.size() > 1 ? p.get(1).toLowerCase(Locale.ROOT) : "poner";
            String valor = p.size() > 2 ? String.join(" ", p.subList(2, p.size())) : "";
            String ambito = a.s("ambito", "item");
            String actual = ctx.modulo().variables().valor(ctx, ambito, nombre);

            switch (op) {
                case "poner", "set", "=" ->
                        ctx.modulo().variables().poner(ctx, ambito, nombre, Textos.aplicar(ctx, valor));
                case "sumar", "+" -> ctx.modulo().variables().poner(ctx, ambito, nombre,
                        numero(Numeros.decimal(actual, 0) + Numeros.decimal(valor, 0)));
                case "restar", "-" -> ctx.modulo().variables().poner(ctx, ambito, nombre,
                        numero(Numeros.decimal(actual, 0) - Numeros.decimal(valor, 0)));
                case "multiplicar", "*" -> ctx.modulo().variables().poner(ctx, ambito, nombre,
                        numero(Numeros.decimal(actual, 0) * Numeros.decimal(valor, 1)));
                case "borrar", "quitar" -> ctx.modulo().variables().poner(ctx, ambito, nombre, null);
                default -> ctx.modulo().getLogger().warning(
                        "[GodItems] VARIABLE: operacion desconocida '" + op + "'.");
            }
        });

        reg("CANCELAR_EVENTO", (ctx, a) -> ctx.cancelarEvento());

        /** Corta la lista aqui: lo que venga detras no se ejecuta. */
        reg("PARAR", (ctx, a) -> ctx.cancelar());

        reg("COOLDOWN_DE", (ctx, a) -> {
            Activador act = Activador.porNombre(a.s("activador", ctx.activador().name()));
            if (act == null) return;
            String id = a.tiene("item") ? GodItem.normalizar(a.s("item", "")) : ctx.definicion().id();
            int t = a.ticks("tiempo", 0);
            for (Player p : Objetivos.jugadores(ctx, a.selector())) {
                if (t <= 0) ctx.modulo().cooldowns().quitar(p, id, act);
                else ctx.modulo().cooldowns().poner(p, id, act, t, a.b("visible", true));
            }
        });

        reg("REPONER_USOS", (ctx, a) -> ctx.modulo().usos().reponer(ctx));
    }

    private static String numero(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }
}
