package net.ederus.edm.troll;

import net.ederus.edm.troll.Troll.Familia;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * El catalogo de bromas.
 *
 * Los NOMBRES de la lista salen de mirar que trae TrollBoss, que es una lista de
 * ideas y no es de nadie. El codigo es nuestro entero: por eso EDM no hereda su
 * GPL y por eso cada broma sabe deshacerse, cosa que ninguna libreria de fuera
 * nos iba a dar.
 *
 * Tres reglas al escribir una broma:
 *
 *  1. **Si dura un rato, se apunta en Estados con su vuelta atras.** Un jugador
 *     congelado para siempre porque el servidor se reinicio no tiene gracia.
 *  2. **Si toca el mundo, se guarda lo que habia.** Nunca se repone "aire".
 *  3. **Si toca el inventario, solo se usan huecos VACIOS.** Un item de MMOItems
 *     perdido no vuelve con un backup sin tirar tambien lo de los demas.
 */
public final class Trolls {

    /** La piel de MHF_Herobrine (Steve con los ojos blancos), firmada por Mojang. */
    private static final io.papermc.paper.datacomponent.item.ResolvableProfile HEROBRINE =
            io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile()
                    .name("Herobrine")
                    .addProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures",
                            "ewogICJ0aW1lc3RhbXAiIDogMTc5MjYxNTYwMjUzOCwKICAicHJvZmlsZUlkIiA6ICI5NTg2ZTVhYjE1N2E0NjU4YWQ4MGIwNzU1MmE5Y2E2MyIsCiAgInByb2ZpbGVOYW1lIiA6ICJNSEZfSGVyb2JyaW5lIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzJjNjVlZDI4MjljODNlMTE5YTgwZGZiMjIyMTY0NDNlODc4ZWYxMDY0OWM0YTM1NGY3NGJmNDVhZDA2YmMxYTciCiAgICB9CiAgfQp9",
                            "VS8Gko3zgOe2yalml0lozOw7azUa3/ZG01zaT2P5HEDjNj1jNcIlXv0gvVNX+E1Ula3LvsckOVt0JHszXeMPGX+zI9UQnmEoarSbCiYNM44HOFdfX5FowACNMleuyyW7U7VBcHeMRQKNQeGVFoaIbtgFWIKMLO7tLC+Ml12j4W9TKZsuA1edT03r490xaGURnUDGV+R6s61hWGPxNT0smRMxtYfdxcQ4lDU5fseUARwaD65gfoILShLh7itDoH6W3HmoksUqDf4eMR93PmLMEec1Nj0/qcryQoGu/N6okJ5vXWk/giiix9NQC8Rfga8ZlT7ikW28cUDfLxzZwDbbMY5yp+y1BAdZkOr2haf4Zj1Jjrx1OuXjk9hhD35Zk06oCltu+rSDTMCk5fKHK5IodwjQnVNvOvpCtQ66lmTLRKc4qeFw4GwI6LdlH//U9cjZbmJJuwz0y9K0kw4V348uPBQiQhKgsS0xrQ2Ca7qgEsCPSaOrhIdlfD6erbe4Qyi+g16qbrypmTcUdcO9TBVDJ6YT3RbJLjTddySyQjJgRFxd0V8Nv8d70y/g2sEeoM0wBMlrXv2NYNrEWOGocJsWjLmyOUClBgbhLM37LiJ1PQg+cXfvSJ5u3h0m2c09dYDKHLT3XN+5AvXBjQCvrVOXUNJ/z8FvWlkequ4bhVzbcp8="))
                    .build();

    private Trolls() { }

    public static Map<String, Troll> catalogo() {
        Map<String, Troll> m = new LinkedHashMap<>();
        sustos(m);
        movimiento(m);
        titere(m);
        inventario(m);
        mentiras(m);
        mundo(m);
        destructivas(m);
        return m;
    }

    /**
     * Pone la escala y la devuelve a 1 al terminar.
     *
     * El valor se guarda ANTES de tocarlo, por si a la victima ya se la habia
     * escalado por otra via: al acabar vuelve a lo que tuviera, no a 1 a ciegas.
     */
    private static void escala(Contexto c, double cuanto) {
        double antes = net.ederus.edm.comun.Compat.getAttribute(c.v(), "scale", 1.0);
        net.ederus.edm.comun.Compat.setAttribute(c.v(), "scale", cuanto);
        c.alAcabar(() -> net.ederus.edm.comun.Compat.setAttribute(c.v(), "scale", antes));
    }

    private static void pon(Map<String, Troll> m, Troll t) { m.put(t.id(), t); }

    /** El primer suelo firme con dos huecos de aire encima, de 4 arriba a 6 abajo. Null si no hay. */
    private static Location suelo(Location l) {
        for (int dy = 4; dy >= -6; dy--) {
            var b = l.getWorld().getBlockAt(l.getBlockX(), l.getBlockY() + dy, l.getBlockZ());
            if (b.getType().isSolid() && b.getRelative(0, 1, 0).isPassable() && b.getRelative(0, 2, 0).isPassable()) {
                return b.getLocation().add(0.5, 1, 0.5);
            }
        }
        return null;
    }

    // ------------------------------------------------------------- SUSTOS

    private static void sustos(Map<String, Troll> m) {
        pon(m, Troll.de("rayo", "Rayo", "Un rayo encima que no hace nada",
                Material.LIGHTNING_ROD, Familia.SUSTO, c ->
                /* strikeLightningEffect: el trueno y el fogonazo SIN el fuego ni
                 * el daño. El de verdad quema el suelo y mata. */
                c.v().getWorld().strikeLightningEffect(c.donde())));

        pon(m, Troll.de("explosion", "Explosión", "Suena y revienta, pero no rompe nada",
                Material.TNT, Familia.SUSTO, c -> {
            c.sonido("entity.generic.explode", 1f);
            c.particulas(Particle.EXPLOSION, 3, 1.5);
            c.empujar(0, 0.45, 0);
            c.sinCaida(8);
        }));

        pon(m, Troll.de("creeper", "Creeper detras", "Un creeper aparece, sisea y desaparece",
                Material.CREEPER_HEAD, Familia.SUSTO, c -> {
            Location detras = c.donde().add(c.donde().getDirection().multiply(-2));
            var bicho = c.v().getWorld().spawn(detras, org.bukkit.entity.Creeper.class, cr -> {
                /* Un creeper de verdad enciende la mecha en 30 ticks y aqui
                 * estaba 40: el susto se quedaba a medio segundo de abrirle un
                 * agujero a la base de alguien. Sin IA no se acerca, y con radio
                 * 0 no revienta nada ni aunque llegara a encenderse. */
                cr.setAI(false);
                cr.setExplosionRadius(0);
                cr.setInvulnerable(true);
                cr.setSilent(false);
            });
            c.sonido("entity.creeper.primed", 1f);
            c.bichoTemporal(bicho, 40);
        }));

        pon(m, Troll.temporal("herobrine", "Herobrine", "Aparece a tu espalda; si te giras a mirarlo, se esfuma",
                Material.PLAYER_HEAD, Familia.SUSTO, 30, c -> {
            /* A la espalda, en horizontal y en el suelo. Pasos y respiracion
             * suenan DESDE donde esta, para que la victima se gire; en cuanto
             * lo mira (o se le acerca) se esfuma. Solo lo ve la victima. */
            Location ojo = c.donde();
            org.bukkit.util.Vector atras = ojo.getDirection().setY(0);
            if (atras.lengthSquared() < 1.0E-4) atras = new org.bukkit.util.Vector(0, 0, 1);
            atras.normalize().multiply(-5);
            Location sitio = suelo(ojo.clone().add(atras));
            if (sitio == null) {
                c.barra("&7No hay sitio para Herobrine detrás");
                return;
            }
            Location base = sitio.clone();
            var plugin = c.modulo().core();
            var hb = c.v().getWorld().spawn(sitio, org.bukkit.entity.Mannequin.class, mq -> {
                mq.setVisibleByDefault(false);
                mq.setProfile(HEROBRINE);
                mq.setDescription(null);
                mq.setImmovable(true);
                mq.setInvulnerable(true);
                mq.setGravity(false);
                mq.setSilent(true);
                mq.setAI(false);
                mq.setCollidable(false);
                mq.setPersistent(false);
            });
            c.v().showEntity(plugin, hb);
            c.bichoTemporal(hb, c.segundos() * 20);
            int[] tick = { 0 };
            org.bukkit.scheduler.BukkitTask[] tarea = { null };
            Bukkit.getScheduler().runTaskTimer(plugin, t -> {
                tarea[0] = t;
                Player v = c.v();
                if (!hb.isValid() || !v.isOnline() || !v.getWorld().equals(hb.getWorld())) {
                    t.cancel();
                    return;
                }
                tick[0]++;
                // Le mira (cono de ~25 grados y sin pared en medio) o se le acerca: fuera
                Location ojos = v.getEyeLocation();
                org.bukkit.util.Vector haciaEl = base.clone().add(0, 1.6, 0).toVector().subtract(ojos.toVector());
                boolean loMira = haciaEl.clone().normalize().dot(ojos.getDirection()) > 0.9 && v.hasLineOfSight(hb);
                if (loMira || v.getLocation().distanceSquared(base) < 2.5 * 2.5) {
                    hb.getWorld().spawnParticle(Particle.LARGE_SMOKE, base.clone().add(0, 1, 0), 30, 0.3, 0.8, 0.3, 0.02);
                    v.playSound(base, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 0.5f);
                    hb.remove();
                    t.cancel();
                    return;
                }
                // Pasos detras y, de vez en cuando, una respiracion: que se gire
                if (tick[0] % 8 == 0) v.playSound(base, org.bukkit.Sound.BLOCK_GRAVEL_STEP, 0.9f, 0.8f);
                if (tick[0] % 60 == 20) v.playSound(base, org.bukkit.Sound.ENTITY_PLAYER_BREATH, 1f, 0.6f);
                if (tick[0] == 30) v.playSound(base, org.bukkit.Sound.AMBIENT_CAVE, 1f, 0.8f);
                // Tiembla en su sitio, mirando siempre a la victima
                var azar = Contexto.azar();
                Location tiembla = base.clone().add((azar.nextDouble() - 0.5) * 0.12, 0, (azar.nextDouble() - 0.5) * 0.12);
                tiembla.setDirection(ojos.toVector().subtract(tiembla.clone().add(0, 1.6, 0).toVector()));
                hb.teleport(tiembla);
            }, 1L, 1L);
            c.alAcabar(() -> { if (tarea[0] != null) tarea[0].cancel(); });
        }));

        pon(m, Troll.de("calamares", "Lluvia de calamares", "Caen calamares del cielo",
                Material.INK_SAC, Familia.SUSTO, c -> c.repetir(4, 12, i -> {
            Location arriba = c.donde().add(Contexto.azar().nextInt(7) - 3, 8, Contexto.azar().nextInt(7) - 3);
            var calamar = c.v().getWorld().spawnEntity(arriba, EntityType.SQUID);
            c.bichoTemporal(calamar, 120);
        })));

        pon(m, Troll.de("sparta", "Esto es Ederus", "Una patada de las de pelicula",
                Material.IRON_BOOTS, Familia.SUSTO, c -> {
            c.titulo("&x&0&0&8&3&F&D&lESTO ES EDERUS", "");
            c.sonido("entity.player.attack.knockback", 1f);
            var mirada = c.donde().getDirection().multiply(-2.2).setY(0.6);
            c.v().setVelocity(mirada);
            c.sinCaida(12);
        }));

        pon(m, Troll.temporal("drogado", "Drogado", "Todo da vueltas durante un rato",
                Material.FERMENTED_SPIDER_EYE, Familia.SUSTO, 20, c -> {
            c.efecto(PotionEffectType.NAUSEA, c.segundos(), 1);
            c.efecto(PotionEffectType.SLOWNESS, c.segundos(), 0);
            c.marcar(Estados.Marca.INVERTIDO, () -> c.quitarEfecto(PotionEffectType.NAUSEA));
        }));

        pon(m, Troll.temporal("infectado", "Infectado", "Suena y se ve como si algo fuera mal",
                Material.ROTTEN_FLESH, Familia.SUSTO, 15, c -> {
            c.efecto(PotionEffectType.HUNGER, c.segundos(), 0);
            c.repetir(20, Math.max(1, c.segundos() / 2), i -> {
                c.sonido("entity.zombie.ambient", 0.8f);
                c.particulas(Particle.ITEM_SLIME, 20, 0.8);
            });
        }));

        pon(m, Troll.de("gritos", "Gritos", "Le llena el chat de ruido",
                Material.NOTE_BLOCK, Familia.SUSTO, c -> c.repetir(6, 14, i -> {
            c.chat("&7[&cAVISO&7] &fAlgo se mueve cerca de ti...");
            c.sonido("entity.enderman.stare", 1f);
        })));

        pon(m, Troll.temporal("ciego", "A oscuras", "Se le apaga la luz",
                Material.BLACK_CONCRETE, Familia.SUSTO, 10, c ->
                c.efecto(PotionEffectType.BLINDNESS, c.segundos(), 0)));

        pon(m, Troll.de("lag", "Tiron", "Una nube de particulas que le da un tiron",
                Material.COBWEB, Familia.SUSTO, c -> {
            /* NO es el "crash" de TrollBoss: tirar el cliente de alguien a
             * proposito es otra cosa. Esto solo le da un tiron y se pasa. */
            c.particulas(Particle.EXPLOSION_EMITTER, 200, 3);
            c.sonido("block.beacon.deactivate", 0.5f);
        }));
    }

    // --------------------------------------------------------- MOVIMIENTO

    private static void movimiento(Map<String, Troll> m) {
        /*
         * Las dos de escala tocan el atributo 'scale', que cambia el TAMANO de
         * verdad: la caja de golpe, el alcance y la altura de los ojos van con
         * el. Por eso son temporales y se deshacen solas dejando la escala en 1:
         * un jugador que se quede a 0.35 no cabe por su propia puerta ni alcanza
         * a pegarle a nada.
         */
        pon(m, Troll.temporal("gigante", "Gigante", "Se hace enorme durante un rato",
                Material.PISTON, Familia.MOVIMIENTO, 25, c -> {
            escala(c, 2.6);
            c.sonido("entity.ravager.roar", 0.7f);
            c.titulo("&c&lGIGANTE", "&7no cabes por la puerta");
        }));

        pon(m, Troll.temporal("enano", "Enano", "Se hace diminuto durante un rato",
                Material.BUCKET, Familia.MOVIMIENTO, 25, c -> {
            escala(c, 0.35);
            c.sonido("entity.silverfish.ambient", 1.8f);
            c.titulo("&b&lENANO", "&7desde aqui abajo todo es enorme");
        }));

        pon(m, Troll.de("lanzar", "Por los aires", "Un empujon hacia arriba",
                Material.FIREWORK_ROCKET, Familia.MOVIMIENTO, c -> {
            c.empujar(0, 2.2, 0);
            c.sinCaida(15);
            c.sonido("entity.firework_rocket.launch", 1f);
        }));

        pon(m, Troll.de("cielo", "Al cielo", "Aparece muy arriba y baja solo",
                Material.ELYTRA, Familia.MOVIMIENTO, c -> c.subir(60, 20)));

        pon(m, Troll.de("caidalibre", "Caída libre", "Muy arriba del todo",
                Material.FEATHER, Familia.MOVIMIENTO, c -> {
            c.subir(120, 30);
            c.titulo("&c&lARRIBA", "&7mira abajo");
        }));

        pon(m, Troll.temporal("congelar", "Congelado", "No se puede mover",
                Material.ICE, Familia.MOVIMIENTO, 15, c -> {
            c.marcar(Estados.Marca.CONGELADO);
            c.barra("&bNo te puedes mover");
        }));

        pon(m, Troll.temporal("pegado", "Pegado al suelo", "Se mueve, pero apenas",
                Material.SLIME_BLOCK, Familia.MOVIMIENTO, 15, c -> {
            c.efecto(PotionEffectType.SLOWNESS, c.segundos(), 5);
            c.efecto(PotionEffectType.JUMP_BOOST, c.segundos(), 128);
            c.alAcabar(() -> {
                c.quitarEfecto(PotionEffectType.SLOWNESS);
                c.quitarEfecto(PotionEffectType.JUMP_BOOST);
            });
        }));

        pon(m, Troll.de("empujar", "Empujon", "Sale disparado hacia un lado",
                Material.PISTON, Familia.MOVIMIENTO, c -> {
            c.empujar(Contexto.azar().nextDouble() * 2 - 1, 0.6, Contexto.azar().nextDouble() * 2 - 1);
            c.sinCaida(8);
        }));

        pon(m, Troll.temporal("correr", "Corre, Forrest", "No puede parar de correr",
                Material.LEATHER_BOOTS, Familia.MOVIMIENTO, 12, c -> {
            c.efecto(PotionEffectType.SPEED, c.segundos(), 3);
            c.marcar(Estados.Marca.CORRIENDO, () -> c.quitarEfecto(PotionEffectType.SPEED));
        }));

        pon(m, Troll.de("girar", "Media vuelta", "Se da la vuelta de golpe",
                Material.COMPASS, Familia.MOVIMIENTO, c -> {
            Location l = c.donde();
            l.setYaw(l.getYaw() + 180);
            c.v().teleport(l);
        }));

        pon(m, Troll.de("tpaleatorio", "A saber donde", "Aparece cerca, pero no donde estaba",
                Material.ENDER_PEARL, Familia.MOVIMIENTO, c -> {
            Location destino = sitioSeguro(c.donde(), 40);
            c.v().teleport(destino);
            c.sonido("entity.enderman.teleport", 1f);
        }));

        pon(m, Troll.de("tpfalso", "Ida y vuelta", "Se lo llevan y lo devuelven",
                Material.ENDER_EYE, Familia.MOVIMIENTO, c -> {
            Location vuelve = c.donde().clone();
            c.v().teleport(c.a().getLocation());
            c.sonido("entity.enderman.teleport", 1f);
            c.tras(60, () -> {
                if (c.v().isOnline()) {
                    c.v().teleport(vuelve);
                    c.sonido("entity.enderman.teleport", 1f);
                }
            });
        }));

        pon(m, Troll.de("cambiazo", "Cambiazo", "Cambia el sitio contigo",
                Material.ENDER_CHEST, Familia.MOVIMIENTO, c -> {
            Location suya = c.donde().clone();
            Location mia = c.a().getLocation().clone();
            c.v().teleport(mia);
            c.a().teleport(suya);
            c.sonido("entity.enderman.teleport", 1f);
        }));

        pon(m, Troll.de("abducir", "Abduccion", "Algo se lo lleva hacia arriba",
                Material.BEACON, Familia.MOVIMIENTO, c -> {
            c.sinCaida(25);
            c.efecto(PotionEffectType.LEVITATION, 6, 2);
            c.sonido("block.beacon.activate", 1.5f);
            c.repetir(3, 40, i -> c.particulas(Particle.END_ROD, 12, 0.6));
            c.tras(140, () -> {
                if (c.v().isOnline()) {
                    c.quitarEfecto(PotionEffectType.LEVITATION);
                    c.titulo("&a&lTE HAN DEVUELTO", "&7de nada");
                }
            });
        }));
    }

    private static void titere(Map<String, Troll> m) {
        pon(m, Troll.temporal("titere", "Títere", "Copia cada paso y cada giro tuyo durante 5 segundos",
                Material.LEAD, Familia.MOVIMIENTO, 5, c -> {
            Player admin = c.a();
            if (admin == null || admin.equals(c.v())) {
                c.chat("&7El títere necesita que lo controle un jugador.");
                return;
            }
            /* Sus teclas no hacen nada (marca TITERE en Escuchas) y cada tick se
             * le mueve lo mismo que se ha movido el admin, en su sitio, con la
             * misma mirada. Nada de seguir al admin: copia, no persigue. */
            c.marcar(Estados.Marca.TITERE);
            c.sinCaida(c.segundos() + 5);
            c.barra("&#B57BFFAlguien te está controlando...");
            c.sonido("entity.evoker.prepare_summon", 1.4f);
            Location[] antes = { admin.getLocation() };
            org.bukkit.scheduler.BukkitTask[] tarea = { null };
            Bukkit.getScheduler().runTaskTimer(c.modulo().core(), t -> {
                tarea[0] = t;
                if (!admin.isOnline() || !c.v().isOnline()) {
                    t.cancel();
                    return;
                }
                Location ahora = admin.getLocation();
                org.bukkit.util.Vector paso = admin.getWorld().equals(antes[0].getWorld())
                        ? ahora.toVector().subtract(antes[0].toVector()) : new org.bukkit.util.Vector();
                antes[0] = ahora;
                if (paso.lengthSquared() > 4) paso.zero(); // un tp del admin no se copia
                Location destino = c.v().getLocation().add(paso);
                // Contra una pared no se mete: se queda donde esta y solo gira
                if (!destino.getBlock().isPassable() || !destino.clone().add(0, 1, 0).getBlock().isPassable()) {
                    destino = c.v().getLocation();
                }
                destino.setYaw(ahora.getYaw());
                destino.setPitch(ahora.getPitch());
                c.v().teleport(destino);
            }, 1L, 1L);
            c.alAcabar(() -> {
                if (tarea[0] != null) tarea[0].cancel();
                if (c.v().isOnline()) c.barra("&7Vuelves a moverte tú");
            });
        }));
    }

    // --------------------------------------------------------- INVENTARIO

    private static void inventario(Map<String, Troll> m) {
        pon(m, Troll.temporal("basura", "Basura", "Le llena los huecos vacios de porqueria",
                Material.DEAD_BUSH, Familia.INVENTARIO, 60, c -> {
            ItemStack basura = nombrado(Material.DEAD_BUSH, "&8Basura");
            c.alAcabar(c.meterEnHuecos(basura, 12));
        }));

        pon(m, Troll.temporal("patata", "Todo patatas", "Lo que rompe suelta además una patata",
                Material.POTATO, Familia.INVENTARIO, 45, c -> c.marcar(Estados.Marca.PATATA)));

        pon(m, Troll.temporal("calabaza", "Calabaza", "Le pone una calabaza en la cabeza",
                Material.CARVED_PUMPKIN, Familia.INVENTARIO, 30, c -> {
            ItemStack antes = c.v().getInventory().getHelmet();
            ItemStack calabaza = new ItemStack(Material.CARVED_PUMPKIN);
            c.v().getInventory().setHelmet(calabaza);
            /* El casco que llevaba se guarda y se repone: si era un MMOItems,
             * tirarlo al suelo seria perderlo. Y solo se repone si SIGUE la
             * calabaza puesta: si se la quito y se puso otra cosa en esos 30 s,
             * reponer a ciegas le borraba el casco nuevo, que es justo lo
             * destructivo que estas bromas no pueden hacer. */
            c.alAcabar(() -> {
                if (!c.v().isOnline()) return;
                ItemStack ahora = c.v().getInventory().getHelmet();
                if (ahora != null && ahora.isSimilar(calabaza)) c.v().getInventory().setHelmet(antes);
            });
        }));

        pon(m, Troll.temporal("sinminar", "Sin picar", "No puede romper bloques",
                Material.WOODEN_PICKAXE, Familia.INVENTARIO, 20, c -> {
            c.marcar(Estados.Marca.SIN_MINAR);
            c.efecto(PotionEffectType.MINING_FATIGUE, c.segundos(), 2);
        }));

        pon(m, Troll.temporal("sinrecoger", "Manos de mantequilla", "No puede recoger nada del suelo",
                Material.BUCKET, Familia.INVENTARIO, 30, c -> c.marcar(Estados.Marca.SIN_RECOGER)));

        pon(m, Troll.temporal("novato", "Modo novato", "Lento, debil y torpe",
                Material.WOODEN_SWORD, Familia.INVENTARIO, 25, c -> {
            c.efecto(PotionEffectType.SLOWNESS, c.segundos(), 1);
            c.efecto(PotionEffectType.WEAKNESS, c.segundos(), 1);
            c.efecto(PotionEffectType.MINING_FATIGUE, c.segundos(), 1);
        }));

        pon(m, Troll.temporal("hambre", "Hambre", "Se queda sin comida un rato",
                Material.BREAD, Familia.INVENTARIO, 25, c -> {
            int antes = c.v().getFoodLevel();
            c.v().setFoodLevel(0);
            c.alAcabar(() -> { if (c.v().isOnline()) c.v().setFoodLevel(antes); });
        }));

        pon(m, Troll.de("famoso", "Que popular", "Corazones y bichos que le siguen",
                Material.POPPY, Familia.INVENTARIO, c -> {
            c.repetir(5, 20, i -> c.particulas(Particle.HEART, 8, 1));
            c.sonido("entity.villager.celebrate", 1f);
        }));

        pon(m, Troll.temporal("letras", "Mensaje en el inventario", "Le escribe algo en los huecos vacios",
                Material.WHITE_WOOL, Familia.INVENTARIO, 60, c -> {
            ItemStack lana = nombrado(Material.RED_WOOL, "&cH&fO&cL&fA");
            c.alAcabar(c.meterEnHuecos(lana, 9));
        }));
    }

    // ----------------------------------------------------------- MENTIRAS

    private static void mentiras(Map<String, Troll> m) {
        pon(m, Troll.de("falsoop", "Falso OP", "Le dice que ya es operador",
                Material.COMMAND_BLOCK, Familia.FALSO, c ->
                c.chat("&7Ahora eres operador del servidor.")));

        pon(m, Troll.de("falsodeop", "Falso DEOP", "Le dice que le han quitado el OP",
                Material.BARRIER, Familia.FALSO, c ->
                c.chat("&7Ya no eres operador del servidor.")));

        pon(m, Troll.de("falsoreinicio", "Falso reinicio", "Cuenta atrás para un reinicio que no existe",
                Material.CLOCK, Familia.FALSO, c -> {
            int[] pasos = {30, 20, 10, 5, 4, 3, 2, 1};
            for (int i = 0; i < pasos.length; i++) {
                final int seg = pasos[i];
                c.tras(20 + i * 20, () -> {
                    if (!c.v().isOnline()) return;
                    c.chat("&c[Servidor] &fReiniciando en &c" + seg + " &fsegundos...");
                    c.sonido("block.note_block.pling", 0.5f);
                });
            }
            c.tras(20 + pasos.length * 20, () ->  {
                if (c.v().isOnline()) c.chat("&c[Servidor] &fEra broma.");
            });
        }));

        pon(m, Troll.temporal("falsaexpulsion", "Falsa expulsion", "Ve la pantalla de expulsado y no lo esta",
                Material.IRON_DOOR, Familia.FALSO, 4, c -> {
            c.titulo("&c&lDESCONECTADO", "&7Has sido expulsado del servidor");
            c.marcar(Estados.Marca.CONGELADO, () -> c.titulo("&a&lEra broma", ""));
        }));

        pon(m, Troll.de("falsobaneo", "Falso baneo", "Le dice que esta baneado",
                Material.NETHERITE_SWORD, Familia.FALSO, c -> {
            c.chat("&c[Servidor] &fHas sido baneado permanentemente.");
            c.chat("&7Motivo: &fnada, es una broma.");
            c.sonido("entity.wither.spawn", 1.4f);
        }));

        pon(m, Troll.de("falsotutorial", "Falso tutorial", "Consejos que no sirven para nada",
                Material.BOOK, Familia.FALSO, c -> {
            String[] consejos = {
                "&e[Consejo] &fLos diamantes salen más si picas mirando arriba.",
                "&e[Consejo] &fSi tiras tu espada al agua sale encantada.",
                "&e[Consejo] &fLos creepers no explotan si les hablas bien.",
                "&e[Consejo] &fEscribe /gamemode 1 para ver mejor de noche."
            };
            for (int i = 0; i < consejos.length; i++) {
                final String texto = consejos[i];
                c.tras(20 + i * 30, () -> { if (c.v().isOnline()) c.chat(texto); });
            }
        }));

        pon(m, Troll.de("falsologro", "Falso logro", "Un logro que no existe",
                Material.GOLDEN_APPLE, Familia.FALSO, c -> {
            c.chat("&7[&aLogro conseguido&7] &fMe han trolleado");
            c.sonido("ui.toast.challenge_complete", 1f);
        }));

        pon(m, Troll.temporal("callar", "Sin hablar", "No le sale nada por el chat",
                Material.STRUCTURE_VOID, Familia.FALSO, 30, c -> {
            c.marcar(Estados.Marca.MUDO);
            /* No se le avisa: la gracia es que crea que el chat va mal. */
        }));

        pon(m, Troll.de("falsamuerte", "Falsa muerte", "Ve su propio mensaje de muerte",
                Material.SKELETON_SKULL, Familia.FALSO, c -> {
            c.chat("&7" + c.v().getName() + " murio de forma humillante");
            c.sonido("entity.player.death", 1f);
        }));
    }

    // -------------------------------------------------------------- MUNDO

    private static void mundo(Map<String, Troll> m) {
        pon(m, Troll.temporal("atrapar", "Encerrado en cristal", "Una caja de cristal a su alrededor",
                Material.GLASS, Familia.MUNDO, 20, c -> {
            c.alAcabar(c.encerrar(Material.GLASS, 1, true, false));
            c.sonido("block.glass.place", 1f);
        }));

        pon(m, Troll.temporal("telarana", "Telarañas", "Se queda pegado en telarañas",
                Material.COBWEB, Familia.MUNDO, 15, c -> {
            c.alAcabar(c.rellenar(Material.COBWEB, 1, 2));
        }));

        pon(m, Troll.temporal("enterrar", "Enterrado", "Una caja de tierra, sin taparle la cabeza",
                Material.DIRT, Familia.MUNDO, 20, c -> {
            c.alAcabar(c.encerrar(Material.DIRT, 1, true, false));
        }));

        pon(m, Troll.de("yunques", "Yunques", "Caen yunques al lado, no encima",
                Material.ANVIL, Familia.MUNDO, c -> c.repetir(8, 8, i -> {
            /* Al LADO a proposito: un yunque en la cabeza mata, y matar no es
             * una broma. El susto es el mismo. */
            Location al = c.donde().add(Contexto.azar().nextInt(5) - 2, 12, Contexto.azar().nextInt(5) - 2);
            if (al.getBlock().getLocation().distance(c.donde()) < 1.2) return;
            var yunque = c.v().getWorld().spawnFallingBlock(al, Material.ANVIL.createBlockData());
            /* Al posarse NO deja bloque ni suelta item, y no hace daño. Antes
             * quedaban yunques de verdad clavados en el terreno, que es dejar
             * el mundo tocado por una broma que se supone reversible. */
            yunque.setCancelDrop(true);
            yunque.setHurtEntities(false);
            c.bichoTemporal(yunque, 60);
        })));

        pon(m, Troll.temporal("borde", "El mundo se encoge", "Le aparece el borde del mundo encima",
                Material.RED_STAINED_GLASS, Familia.MUNDO, 20, c -> {
            /* El borde es SOLO para el: Paper deja darle uno propio al jugador,
             * asi que el mundo de verdad no se toca. */
            var borde = Bukkit.createWorldBorder();
            borde.setCenter(c.donde());
            borde.setSize(64);
            borde.setSize(6, c.segundos());
            c.v().setWorldBorder(borde);
            c.alAcabar(() -> { if (c.v().isOnline()) c.v().setWorldBorder(null); });
        }));
    }

    // -------------------------------------------------------- DESTRUCTIVAS

    /**
     * Las que borran progreso. Piden confirmacion y el permiso
     * ederus.troll.destructivo aparte, y quedan en el registro con nombre.
     */
    private static void destructivas(Map<String, Troll> m) {
        pon(m, Troll.destructiva("matar", "Matarlo", "Lo mata. Pierde lo que lleve encima",
                Material.NETHERITE_AXE, Familia.MUNDO, c -> c.v().setHealth(0)));

        pon(m, Troll.destructiva("herir", "Hacerle daño", "Le quita la mitad de la vida",
                Material.IRON_SWORD, Familia.MUNDO, c ->
                c.v().damage(Math.max(1, c.v().getHealth() / 2))));

        pon(m, Troll.destructiva("quemar", "Prenderle fuego", "Arde 5 segundos",
                Material.FLINT_AND_STEEL, Familia.MUNDO, c -> c.v().setFireTicks(100)));

        pon(m, Troll.destructiva("tirarinventario", "Tirarle el inventario", "Todo al suelo",
                Material.HOPPER, Familia.INVENTARIO, c -> {
            var inv = c.v().getInventory();
            for (ItemStack s : inv.getStorageContents()) {
                if (s == null || s.getType().isAir()) continue;
                c.v().getWorld().dropItemNaturally(c.donde(), s);
            }
            inv.setStorageContents(new ItemStack[inv.getStorageContents().length]);
        }));

        pon(m, Troll.destructiva("vacio", "Al vacio", "Lo tira fuera del mundo. Lo mata",
                Material.OBSIDIAN, Familia.MOVIMIENTO, c ->
                c.v().teleport(c.donde().clone().subtract(0, 300, 0))));

        pon(m, Troll.destructiva("pisotear", "Pisotear el campo", "Convierte los cultivos de alrededor en tierra",
                Material.FARMLAND, Familia.MUNDO, c -> {
            Location centro = c.donde();
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    var b = centro.clone().add(x, -1, z).getBlock();
                    if (b.getType() == Material.FARMLAND) b.setType(Material.DIRT);
                }
            }
        }));
    }

    // ------------------------------------------------------------- ayudas

    private static ItemStack nombrado(Material material, String nombre) {
        ItemStack pila = new ItemStack(material);
        ItemMeta meta = pila.getItemMeta();
        if (meta != null) {
            meta.displayName(net.ederus.edm.comun.Estilo.legado(nombre));
            pila.setItemMeta(meta);
        }
        return pila;
    }

    /**
     * Un sitio cerca donde no se muera al aparecer: mismo mundo, con suelo y
     * con dos bloques de aire. Sin esto, "aparece por ahi" acaba siendo "aparece
     * dentro de la roca" y eso ya no es una broma.
     */
    private static Location sitioSeguro(Location desde, int radio) {
        for (int intento = 0; intento < 24; intento++) {
            int dx = Contexto.azar().nextInt(radio * 2 + 1) - radio;
            int dz = Contexto.azar().nextInt(radio * 2 + 1) - radio;
            Location alto = desde.getWorld().getHighestBlockAt(
                    desde.getBlockX() + dx, desde.getBlockZ() + dz).getLocation().add(0.5, 1, 0.5);
            if (alto.getBlock().isPassable() && alto.clone().add(0, 1, 0).getBlock().isPassable()) {
                alto.setYaw(desde.getYaw());
                alto.setPitch(desde.getPitch());
                return alto;
            }
        }
        return desde;
    }

    /** Las que puede elegir la broma "al azar": nunca una destructiva. */
    public static List<Troll> sorteables(Map<String, Troll> catalogo) {
        List<Troll> out = new ArrayList<>();
        for (Troll t : catalogo.values()) if (!t.destructivo()) out.add(t);
        return out;
    }

    /** Alguien en creativo o volando se sale de casi todas: conviene avisar. */
    public static boolean raro(Player v) {
        return v.getGameMode() == GameMode.CREATIVE || v.getGameMode() == GameMode.SPECTATOR;
    }
}
