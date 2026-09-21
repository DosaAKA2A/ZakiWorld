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

import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
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
        combate();
        movimiento();
        efectos();
        visual();
        textos();
        sonido();
        mundo();
        mundoDos();
        juego();
        flujo();
    }

    /* ============================================================= combate */

    private static void combate() {
        /*
         * ROBAR_VIDA cura un porcentaje del daño DE ESE GOLPE, no una cantidad
         * fija: asi escala solo con el arma y con las stats de MMOItems, que es
         * lo que espera quien pone un robo de vida en una espada.
         *
         * El daño lo trae el contexto. En CRITICO es el daño final (esa escucha
         * va en MONITOR, con las cuentas ya hechas); en GOLPEAR es el que haya
         * en el evento en ese momento.
         */
        reg("ROBAR_VIDA", (ctx, a) -> {
            Player j = ctx.jugador();
            if (j == null) return;
            double pct = a.d("cantidad", a.d("porcentaje", 20)) / 100.0;
            double cura = ctx.dano() * pct;
            if (cura <= 0) return;
            for (LivingEntity e : Objetivos.vivos(ctx, a.selector())) {
                e.setHealth(Math.max(0, Math.min(Textos.maxVida(e), e.getHealth() + cura)));
            }
        });

        /*
         * MULTIPLICAR_DANO toca el daño DEL EVENTO. Solo tiene sentido en los
         * activadores que traen uno (GOLPEAR, GOLPEAR_JUGADOR, RECIBIR_GOLPE,
         * CAER); en los demas no hay nada que multiplicar y se avisa una vez.
         *
         * Va sobre el daño que haya ya en el evento, o sea DESPUES de que
         * MMOItems haya puesto el suyo: multiplica el golpe real, no el base.
         */
        reg("MULTIPLICAR_DANO", (ctx, a) -> {
            double x = a.d("cantidad", a.d("factor", 1.5));
            if (!(ctx.evento() instanceof org.bukkit.event.entity.EntityDamageEvent d)) {
                ctx.modulo().avisoUnaVez("muldano." + ctx.definicion().id(),
                        ctx.definicion().id() + ": MULTIPLICAR_DANO en " + ctx.activador()
                                + " no hace nada, ese activador no trae ningun golpe."
                                + " Ponlo en GOLPEAR, RECIBIR_GOLPE o CAER.");
                return;
            }
            d.setDamage(Math.max(0, d.getDamage() * x));
            ctx.dano(d.getFinalDamage());
        });

        /*
         * IGNORAR_ARMADURA baja la vida a pelo: ni armadura, ni encantamientos,
         * ni resistencias. Por eso no pasa por damage(), que es donde se aplican
         * todas esas reducciones. La animacion de golpe se pide aparte para que
         * el jugador vea que le ha dolido.
         */
        reg("IGNORAR_ARMADURA", (ctx, a) -> {
            double n = a.d("cantidad", 4);
            if (n <= 0) return;
            for (LivingEntity e : Objetivos.vivos(ctx,
                    a.selector() == null ? "@golpeado" : a.selector())) {
                double absorbe = Math.min(e.getAbsorptionAmount(), n);
                e.setAbsorptionAmount(e.getAbsorptionAmount() - absorbe);
                double resto = n - absorbe;
                if (resto > 0) e.setHealth(Math.max(0, e.getHealth() - resto));
                e.playEffect(org.bukkit.EntityEffect.HURT);
            }
        });

        /*
         * ATURDIR: quieto, sin saltar y sin poder pegar. Se hace con efectos de
         * pocion extremos y no tocando el movimiento, por lo mismo que ANCLAR:
         * el cliente corrige el movimiento por su cuenta y en Bedrock da tirones.
         * Se quita solo porque los efectos caducan; no hay nada que limpiar.
         */
        reg("ATURDIR", (ctx, a) -> {
            int t = a.ticks("duracion", 40);
            for (LivingEntity e : Objetivos.vivos(ctx,
                    a.selector() == null ? "@golpeado" : a.selector())) {
                Compat.apply(e, "slowness", t, 250);
                Compat.apply(e, "jump_boost", t, 128);
                Compat.apply(e, "weakness", t, 10);
                Compat.apply(e, "mining_fatigue", t, 10);
            }
        });

        /*
         * DESARMAR le tira el arma al suelo con dueño.
         *
         * El dueño es la VICTIMA, no quien desarma: si no, desarmar seria robar,
         * y un item que roba armas de MMOItems se retira a la semana. Con el
         * dueño puesto, nadie mas puede recogerla mientras dure.
         */
        reg("DESARMAR", (ctx, a) -> {
            int t = a.ticks("duracion", 60);
            for (LivingEntity e : Objetivos.vivos(ctx,
                    a.selector() == null ? "@golpeado" : a.selector())) {
                var equipo = e.getEquipment();
                if (equipo == null) continue;
                ItemStack arma = equipo.getItemInMainHand();
                if (arma == null || arma.getType().isAir()) continue;
                equipo.setItemInMainHand(null);
                org.bukkit.entity.Item suelo = e.getWorld().dropItemNaturally(
                        e.getLocation().add(0, 0.5, 0), arma.clone());
                suelo.setPickupDelay(Math.max(10, Math.min(t, 6000)));
                try {
                    suelo.setOwner(e.getUniqueId());
                } catch (Throwable ignored) {
                    /* Sin setOwner solo queda el retardo de recogida. */
                }
            }
        });

        /* Brillo + una marca que dura lo mismo, para la condicion OBJETIVO_MARCADO. */
        reg("MARCAR", (ctx, a) -> {
            int t = a.ticks("duracion", 100);
            for (Entity e : Objetivos.resolver(ctx,
                    a.selector() == null ? "@golpeado" : a.selector())) {
                ctx.modulo().combate().marcar(e, t);
                e.setGlowing(true);
                ctx.modulo().core().getServer().getScheduler().runTaskLater(ctx.modulo().core(),
                        () -> { if (e.isValid()) e.setGlowing(false); }, Math.max(1, t));
            }
        });

        /*
         * CADENA: un relampago que salta de enemigo en enemigo.
         *
         * Cada salto busca el vivo mas cercano al ANTERIOR, no al portador: por
         * eso la cadena se aleja y dibuja un camino en vez de quedarse pegada a
         * ti. El daño baja un poco en cada salto para que valga la pena buscar
         * el grupo y no confiar en que la cadena mate sola.
         */
        reg("CADENA", (ctx, a) -> {
            Location origen = Objetivos.lugar(ctx,
                    a.selector() == null ? "@golpeado" : a.selector());
            if (origen == null || origen.getWorld() == null) return;
            double radio = Math.max(1, Math.min(32, a.d("radio", 6)));
            int saltos = Math.max(1, Math.min(20, a.i("saltos", 3)));
            double dano = a.d("dano", 4);
            double merma = a.d("merma", 0.15);
            boolean rayo = a.b("rayo", true);

            java.util.Set<java.util.UUID> tocados = new java.util.HashSet<>();
            Entity primero = Objetivos.uno(ctx, a.selector() == null ? "@golpeado" : a.selector());
            if (primero != null) tocados.add(primero.getUniqueId());
            if (ctx.jugador() != null) tocados.add(ctx.jugador().getUniqueId());

            Location desde = origen;
            for (int i = 0; i < saltos; i++) {
                LivingEntity siguiente = masCercano(ctx, desde, radio, tocados);
                if (siguiente == null) break;
                tocados.add(siguiente.getUniqueId());
                Location hasta = siguiente.getLocation().add(0, siguiente.getHeight() / 2, 0);
                Fx.beam(desde.clone().add(0, 1, 0), hasta, 0.4, l -> pintar(ctx, a, l));
                if (rayo) hasta.getWorld().strikeLightningEffect(siguiente.getLocation());
                double golpe = dano * Math.pow(1 - merma, i);
                if (golpe > 0) siguiente.damage(golpe, ctx.jugador());
                desde = hasta;
            }
        });

        /*
         * REBOTE: el proyectil que acaba de impactar sale otra vez hacia otro
         * enemigo cercano.
         *
         * No se reutiliza la flecha porque en el impacto ya esta clavada o
         * muerta segun el tipo: se lanza una nueva del mismo tipo desde el punto
         * del golpe, marcada con el mismo GodItem, asi el rebote conserva el
         * comportamiento del item. `rebotes` corta la cadena infinita.
         */
        reg("REBOTE", (ctx, a) -> {
            Player j = ctx.jugador();
            if (j == null) return;
            if (!(ctx.evento() instanceof org.bukkit.event.entity.ProjectileHitEvent ph)) {
                ctx.modulo().avisoUnaVez("rebote." + ctx.definicion().id(),
                        ctx.definicion().id() + ": REBOTE solo vale en los activadores de"
                                + " proyectil (PROYECTIL_IMPACTA y sus dos subtipos).");
                return;
            }
            Projectile viejo = ph.getEntity();
            NamespacedKeyRebote.limitar(ctx, viejo, a.i("rebotes", 2));
            if (NamespacedKeyRebote.agotado(ctx, viejo)) return;

            Location punto = viejo.getLocation();
            java.util.Set<java.util.UUID> fuera = new java.util.HashSet<>();
            fuera.add(j.getUniqueId());
            if (ph.getHitEntity() != null) fuera.add(ph.getHitEntity().getUniqueId());
            LivingEntity destino = masCercano(ctx, punto, Math.max(1, a.d("radio", 8)), fuera);
            if (destino == null) return;

            Vector dir = destino.getLocation().add(0, destino.getHeight() / 2, 0)
                    .toVector().subtract(punto.toVector()).normalize()
                    .multiply(a.d("velocidad", 1.6));
            Projectile nuevo = j.launchProjectile(viejo.getClass(), dir);
            nuevo.teleport(punto);
            nuevo.setVelocity(dir);
            nuevo.getPersistentDataContainer().set(ctx.modulo().identidad().clave(),
                    PersistentDataType.STRING, ctx.definicion().id());
            NamespacedKeyRebote.heredar(ctx, viejo, nuevo);
        });

        reg("COMBO_RESET", (ctx, a) -> ctx.modulo().combate().reiniciarCombo(ctx.jugador()));
    }

    /** El vivo mas cercano a un punto que no este ya en la lista de tocados. */
    private static LivingEntity masCercano(Ctx ctx, Location desde, double radio,
                                           java.util.Set<java.util.UUID> fuera) {
        if (desde.getWorld() == null) return null;
        LivingEntity mejor = null;
        double corta = Double.MAX_VALUE;
        for (Entity e : desde.getWorld().getNearbyEntities(desde, radio, radio, radio)) {
            if (!(e instanceof LivingEntity le) || le.isDead()) continue;
            if (fuera.contains(e.getUniqueId())) continue;
            double d = e.getLocation().distanceSquared(desde);
            if (d < corta) {
                corta = d;
                mejor = le;
            }
        }
        return mejor;
    }

    /**
     * La cuenta de rebotes que le queda a un proyectil.
     *
     * Vive en el propio proyectil y no en un mapa del plugin porque el proyectil
     * es lo unico que sobrevive de un rebote al siguiente: en un mapa habria que
     * limpiarlo a mano cada vez que uno se pierde por un barranco.
     */
    private static final class NamespacedKeyRebote {

        private static org.bukkit.NamespacedKey clave(Ctx ctx) {
            return new org.bukkit.NamespacedKey(ctx.modulo(), "rebotes");
        }

        static void limitar(Ctx ctx, Projectile p, int tope) {
            var pdc = p.getPersistentDataContainer();
            if (pdc.has(clave(ctx), PersistentDataType.INTEGER)) return;
            pdc.set(clave(ctx), PersistentDataType.INTEGER, Math.max(0, tope));
        }

        static boolean agotado(Ctx ctx, Projectile p) {
            Integer n = p.getPersistentDataContainer().get(clave(ctx), PersistentDataType.INTEGER);
            return n != null && n <= 0;
        }

        static void heredar(Ctx ctx, Projectile viejo, Projectile nuevo) {
            Integer n = viejo.getPersistentDataContainer().get(clave(ctx), PersistentDataType.INTEGER);
            nuevo.getPersistentDataContainer().set(clave(ctx), PersistentDataType.INTEGER,
                    Math.max(0, (n == null ? 1 : n) - 1));
        }
    }

    /* ================================================================ vida */

    private static void vidaYDano() {
        reg("DAÑO", (ctx, a) -> {
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
                ctx.modulo().getLogger().warning("[GodItems] Poción desconocida: " + tipo);
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

        /*
         * REINICIAR_COOLDOWN es COOLDOWN_DE con tiempo 0, mas el caso que aquel
         * no cubre: `todos`, que limpia de golpe todos los enfriamientos del
         * jugador. Es lo que quiere un item de "tu siguiente habilidad es gratis".
         */
        reg("REINICIAR_COOLDOWN", (ctx, a) -> {
            String quien = a.s("activador", a.texto().trim());
            for (Player p : Objetivos.jugadores(ctx, a.selector())) {
                if (quien.isBlank() || quien.equalsIgnoreCase("todos")
                        || quien.equalsIgnoreCase("all")) {
                    ctx.modulo().cooldowns().quitarTodo(p);
                    continue;
                }
                Activador act = Activador.porNombre(quien);
                if (act == null) {
                    ctx.modulo().avisoUnaVez("recd." + quien,
                            "REINICIAR_COOLDOWN: no hay ningun activador '" + quien + "'.");
                    return;
                }
                String id = a.tiene("item") ? GodItem.normalizar(a.s("item", ""))
                        : ctx.definicion().id();
                ctx.modulo().cooldowns().quitar(p, id, act);
            }
        });

        /*
         * VARIABLE_OBJETIVO es VARIABLE pero escribiendo en el OTRO. Solo existe
         * el ambito de jugador: una variable "de item" del objetivo no significa
         * nada, porque el item que corre es el tuyo, no el suyo.
         */
        reg("VARIABLE_OBJETIVO", (ctx, a) -> {
            List<String> p = a.palabras();
            if (p.isEmpty()) return;
            String nombre = p.get(0);
            String op = p.size() > 1 ? p.get(1).toLowerCase(Locale.ROOT) : "poner";
            String valor = p.size() > 2 ? String.join(" ", p.subList(2, p.size())) : "";
            for (Player destino : Objetivos.jugadores(ctx,
                    a.selector() == null ? "@golpeado" : a.selector())) {
                String actual = ctx.modulo().variables().deJugador(destino, nombre);
                switch (op) {
                    case "poner", "set", "=" -> ctx.modulo().variables()
                            .ponerJugador(destino, nombre, Textos.aplicar(ctx, valor));
                    case "sumar", "+" -> ctx.modulo().variables().ponerJugador(destino, nombre,
                            numero(Numeros.decimal(actual, 0) + Numeros.decimal(valor, 0)));
                    case "restar", "-" -> ctx.modulo().variables().ponerJugador(destino, nombre,
                            numero(Numeros.decimal(actual, 0) - Numeros.decimal(valor, 0)));
                    case "multiplicar", "*" -> ctx.modulo().variables().ponerJugador(destino, nombre,
                            numero(Numeros.decimal(actual, 0) * Numeros.decimal(valor, 1)));
                    case "borrar", "quitar" -> ctx.modulo().variables()
                            .ponerJugador(destino, nombre, null);
                    default -> ctx.modulo().getLogger().warning(
                            "[GodItems] VARIABLE_OBJETIVO: operacion desconocida '" + op + "'.");
                }
            }
        });
    }

    /* =============================================================== mundo 2 */

    private static void mundoDos() {
        /*
         * CLIMA y HORA cambian el mundo DE VERDAD, no solo lo que ve el cliente
         * (eso es CIELO). Por eso llevan duracion: un item que deja el mundo en
         * tormenta para siempre es un item que acaba desactivado.
         */
        reg("CLIMA", (ctx, a) -> {
            Location l = ctx.lugar();
            if (l == null || l.getWorld() == null) return;
            World w = l.getWorld();
            String que = a.s("clima", a.texto().trim()).toLowerCase(Locale.ROOT);
            int dur = a.ticks("duracion", 0);
            boolean lluvia = que.startsWith("lluv") || que.startsWith("rain");
            boolean tormenta = que.startsWith("torm") || que.startsWith("thun");
            w.setStorm(lluvia || tormenta);
            w.setThundering(tormenta);
            if (dur > 0) {
                w.setWeatherDuration(dur);
                if (tormenta) w.setThunderDuration(dur);
            }
        });

        reg("HORA", (ctx, a) -> {
            Location l = ctx.lugar();
            if (l == null || l.getWorld() == null) return;
            String que = a.s("hora", a.texto().trim()).toLowerCase(Locale.ROOT);
            long hora;
            if (que.startsWith("dia") || que.startsWith("día") || que.startsWith("day")) hora = 1000;
            else if (que.startsWith("noche") || que.startsWith("night")) hora = 13000;
            else hora = (long) Numeros.decimal(que, 1000);
            l.getWorld().setTime(hora);
        });

        /* Tira el item al suelo en el punto, sin pasar por el inventario de nadie. */
        reg("SOLTAR_ITEM", (ctx, a) -> {
            Location l = punto(ctx, a);
            if (l == null || l.getWorld() == null) return;
            Material m = material(a.s("material", a.texto().trim()), null);
            if (m == null || m.isAir()) return;
            int n = Math.max(1, Math.min(256, a.i("cantidad", 1)));
            l.getWorld().dropItemNaturally(l, new ItemStack(m, n));
        });

        /*
         * DUPLICAR_DROPS vale en ROMPER_BLOQUE y en MATAR, que son los dos
         * unicos sitios donde hay un boton de drops que tocar. En la muerte se
         * duplica la lista del evento; en el bloque no se puede (el evento no la
         * expone), asi que se suelta otra tanda a mano en el mismo punto.
         */
        reg("DUPLICAR_DROPS", (ctx, a) -> {
            int veces = Math.max(1, Math.min(8, a.i("veces", 1)));
            if (ctx.evento() instanceof org.bukkit.event.entity.EntityDeathEvent muerte) {
                List<ItemStack> copia = new ArrayList<>(muerte.getDrops());
                for (int i = 0; i < veces; i++) {
                    for (ItemStack it : copia) {
                        if (it != null && !it.getType().isAir()) muerte.getDrops().add(it.clone());
                    }
                }
                return;
            }
            if (ctx.evento() instanceof org.bukkit.event.block.BlockBreakEvent roto) {
                Location donde = roto.getBlock().getLocation().add(0.5, 0.5, 0.5);
                ItemStack mano = ctx.item() == null ? new ItemStack(Material.AIR) : ctx.item();
                for (int i = 0; i < veces; i++) {
                    for (ItemStack it : roto.getBlock().getDrops(mano)) {
                        if (it != null && !it.getType().isAir()) {
                            donde.getWorld().dropItemNaturally(donde, it.clone());
                        }
                    }
                }
                return;
            }
            ctx.modulo().avisoUnaVez("dup." + ctx.definicion().id(),
                    ctx.definicion().id() + ": DUPLICAR_DROPS solo hace algo en ROMPER_BLOQUE"
                            + " y en MATAR; en " + ctx.activador() + " no hay drops que duplicar.");
        });
    }

    private static String numero(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }
}
