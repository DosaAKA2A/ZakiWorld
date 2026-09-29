package net.ederus.calamity.hardcore;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * sec. 2.3 · La foto de un muerto en Calamity: lo que su Eco va a llevar y como va a pegar.
 *
 * Se toma en Hardcore.onMuerte ANTES de borrar el inventario y de reiniciar la cordura: es el
 * unico instante en que aun existen el equipo, las Reliquias, el nivel (que sube con la
 * locura y con los minutos dentro) y las stats que MythicLib le vuelca al jugador. Despues de
 * la foto, el inventario se borra: lo que el Eco "lleva" son COPIAS visuales sin stats (ningun
 * plugin puede soltar un objeto real desde el cuerpo) y lo que suelta al morir sale de aqui.
 *
 * Es un valor: se crea una vez y Ecos lo convierte en su registro de hardcore-datos.yml.
 */
@SuppressWarnings("UnstableApiUsage")
final class FotoMuerte {

    /** Orden de las casillas en equipo[]: el mismo que se guarda en ecos.<id>.equipo. */
    static final String[] CASILLAS = {"HEAD", "CHEST", "LEGS", "FEET", "HAND", "OFF_HAND"};
    static final int HEAD = 0, CHEST = 1, LEGS = 2, FEET = 3, HAND = 4, OFF_HAND = 5;

    UUID dueno;
    String nombre;
    /** Donde nace: a ras de suelo, o su ultimo suelo firme, y lejos de las puertas. */
    Location anclaje;
    /** Copias visuales (sin encantamientos, sin MMOItems, sin atributos). Huecos = null. */
    final ItemStack[] equipo = new ItemStack[6];
    /** Arco o ballesta en la mano: su Eco dispara, y lo que pega sale del arma (sin ella, pelea a mano). */
    boolean arquero;
    /**
     * La clave del bioma donde murio (bracken:panacea/condemned_taiga, minecraft:swamp...): de ella
     * sale la variante de esqueleto de su Eco (eco.cuerpo.por-bioma). Null si no se sabe.
     */
    String bioma;
    boolean escudo;
    /** Piezas reales entre armadura y arma: decide si merece Eco (minimo-piezas). */
    int piezas;
    /** Stats del jugador al morir, sin los modificadores "bracken" (MT sec. 6.2). */
    double vida, armadura, dureza, dano;
    /** N_C al morir, antes de cordura.reiniciar: morir loco deja un Eco peor. */
    int nivel;
    boolean porParca;
    UUID asesino;
    String asesinoNombre;
    int segundosDentro;
    /** Las Reliquias reales (con su UUID: se tasan al salir quien las recoja). */
    final List<ItemStack> reliquias = new ArrayList<>();
    /** Las Esencias que hereda el Eco (la mitad, floor), como items reales. */
    final List<ItemStack> esencias = new ArrayList<>();
    int nEsencias;
    String skinValor, skinFirma;
    final List<String> frases = new ArrayList<>();
    private Censo.Foto censo;

    FotoMuerte() {
    }

    /** La foto de verdad: con su botin. La llama Hardcore.onMuerte. */
    static FotoMuerte de(Player p, Hardcore hc) {
        return de(p, hc, true);
    }

    /**
     * @param conBotin false para "eco crear" (admin, sin matarle): el Eco lleva su equipo pero
     *                 no sus Reliquias ni Esencias, que el jugador sigue teniendo. Si no, el
     *                 comando seria un duplicador.
     */
    static FotoMuerte de(Player p, Hardcore hc, boolean conBotin) {
        ConfigurationSection c = Ecos.seccion(hc);
        FotoMuerte f = new FotoMuerte();
        f.dueno = p.getUniqueId();
        f.nombre = p.getName();
        PlayerInventory inv = p.getInventory();

        // --- equipo: getArmorContents va de botas a casco (MT sec. 4)
        ItemStack[] armadura = inv.getArmorContents();
        ItemStack casco = pieza(armadura, 3), pechera = pieza(armadura, 2), grebas = pieza(armadura, 1),
                botas = pieza(armadura, 0);
        ItemStack mano = pieza(inv.getItemInMainHand());
        ItemStack arma = null;
        boolean armaEnMano = false;
        if (mano != null && (esArma(mano.getType()) || tieneModDano(mano))) {
            arma = mano;
            armaEnMano = true;
        } else {
            // Si no la tenia en la mano, la de la barra rapida que mas pegue: el Eco no sale
            // con las manos vacias porque el ultimo segundo llevaba una antorcha.
            double mejor = 0;
            for (int i = 0; i <= 8; i++) {
                ItemStack it = pieza(inv.getItem(i));
                if (it == null || !(esArma(it.getType()) || tieneModDano(it))) continue;
                double d = danoItem(it);
                if (d > mejor) {
                    mejor = d;
                    arma = it;
                }
            }
        }
        ItemStack segunda = pieza(inv.getItemInOffHand());
        ItemStack escudo = segunda != null && segunda.getType() == Material.SHIELD ? segunda : null;

        ItemStack[] reales = {casco, pechera, grebas, botas, arma, escudo};
        for (int i = 0; i < 6; i++) {
            if (reales[i] == null) continue;
            f.equipo[i] = copiaVisual(reales[i]);
            if (i <= HAND) f.piezas++;
        }
        // Sin casco, su cara: el Eco se reconoce de lejos (rama !shelled de Mimic.dressAs).
        if (casco == null) f.equipo[HEAD] = cabeza(p);
        f.arquero = arma != null && (arma.getType() == Material.BOW || arma.getType() == Material.CROSSBOW);
        f.escudo = escudo != null;

        // --- stats del jugador, que ya traen MMOItems y sets (MythicLib los vuelca al jugador)
        f.vida = sinBracken(p, "max_health");
        f.armadura = sinBracken(p, "armor");
        f.dureza = sinBracken(p, "armor_toughness");
        if (arma == null || (armaEnMano && !f.arquero)) f.dano = sinBracken(p, "attack_damage");
        else f.dano = danoItem(arma);

        f.nivel = hc.plugin().mobs() == null ? 1 : hc.plugin().mobs().nivelCalamity(p);
        f.porParca = porParca(p);
        Player killer = p.getKiller();
        if (killer != null && !killer.equals(p)) {
            f.asesino = killer.getUniqueId();
            f.asesinoNombre = killer.getName();
        }
        f.segundosDentro = hc.cordura().estado(p).segundosDentro;

        // --- botin: Reliquias enteras y la mitad de las Esencias (la otra mitad se pierde)
        if (conBotin) {
            List<ItemStack> esenciasEncima = new ArrayList<>();
            int total = 0;
            for (ItemStack it : inv.getContents()) {
                if (it == null || it.getType().isAir()) continue;
                ItemStack visto = it;
                if (hc.valor("reliquias", () -> hc.reliquias().es(visto), false)) {
                    f.reliquias.add(it.clone());
                } else if (hc.items().esEsencia(it)) {
                    esenciasEncima.add(it.clone());
                    total += it.getAmount();
                }
            }
            f.nEsencias = heredadas(total, c.getDouble("esencias-heredadas", 0.5));
            int quedan = f.nEsencias;
            for (ItemStack it : esenciasEncima) {
                if (quedan <= 0) break;
                int n = Math.min(quedan, it.getAmount());
                it.setAmount(n);
                f.esencias.add(it);
                quedan -= n;
            }
        }

        // --- lo que hace falta para rehacer su cara tras un reinicio, y sus frases (P1)
        try {
            for (ProfileProperty pp : p.getPlayerProfile().getProperties()) {
                if ("textures".equals(pp.getName())) {
                    f.skinValor = pp.getValue();
                    f.skinFirma = pp.getSignature();
                }
            }
        } catch (Throwable sinPerfil) {
            // Sin texturas el Eco lleva su cabeza por UUID y el maniqui (P1) no sale.
        }
        Ecos ecos = hc.ecos();
        if (ecos != null) f.frases.addAll(ecos.frases(p));

        f.censo = hc.valor("censo", () -> Censo.de(reales), null);
        f.anclaje = anclar(p, hc, c);
        // El bioma de donde murio. Caido al vacio no estaba en ningun bioma: vale el de su anclaje (su ultimo suelo).
        Location muerte = p.getLocation();
        boolean vacio = muerte.getWorld() == null || muerte.getY() < muerte.getWorld().getMinHeight();
        f.bioma = claveBioma(vacio ? f.anclaje : muerte);
        return f;
    }

    /** La clave del bioma de un sitio (minecraft:swamp, bracken:panacea/wildflower_bog...), o null. No carga chunks. */
    static String claveBioma(Location l) {
        if (l == null || l.getWorld() == null) return null;
        try {
            return l.getWorld().getBiome(l.getBlockX(), l.getBlockY(), l.getBlockZ()).getKey().asString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** La foto del equipo (Censo, M10): decide la caza valida y el grado de la Lagrima. */
    Censo.Foto censo() {
        return censo == null ? new Censo.Foto(List.of(), 0, 0, 0, 0) : censo;
    }

    void censo(Censo.Foto foto) {
        this.censo = foto;
    }

    /** Si deja Eco (sec. 2.2): minimo-piezas entre armadura y arma, o una Reliquia, o una Esencia. */
    boolean mereceEco(int minimoPiezas) {
        return piezas >= Math.max(0, minimoPiezas) || !reliquias.isEmpty() || nEsencias > 0;
    }

    /** floor(total x fraccion): la otra parte se pierde (sumidero, y pasar Esencias a un alt muriendo sale caro). */
    static int heredadas(int total, double fraccion) {
        return (int) Math.floor(Math.max(0, total) * Math.max(0, Math.min(1, fraccion)) + 1e-9);
    }

    // ------------------------------------------------------------------ piezas

    private static ItemStack pieza(ItemStack[] arr, int i) {
        return arr == null || i >= arr.length ? null : pieza(arr[i]);
    }

    private static ItemStack pieza(ItemStack it) {
        return it == null || it.getType().isAir() ? null : it;
    }

    static boolean esArma(Material m) {
        String n = m.name();
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_SPEAR") || m == Material.MACE
                || m == Material.TRIDENT || m == Material.BOW || m == Material.CROSSBOW;
    }

    private static boolean tieneModDano(ItemStack it) {
        if (PuenteMmo.stat(it, "ATTACK_DAMAGE") > 0) return true;
        Attribute a = Compat.attribute("attack_damage");
        if (a == null || !it.hasItemMeta()) return false;
        ItemMeta meta = it.getItemMeta();
        Collection<AttributeModifier> mods = meta.hasAttributeModifiers() ? meta.getAttributeModifiers(a) : null;
        return mods != null && !mods.isEmpty();
    }

    /**
     * Lo que pega un arma que no estaba en la mano: la stat de MMOItems si la tiene (el item
     * de MMOItems pierde los modificadores de fabrica, MT sec. 6.1), si no 1 + sus
     * modificadores attack_damage (los suyos o los de fabrica del material). El arco no tiene
     * attack_damage: se cuenta como la flecha vanilla a tope (6) con su Poder.
     */
    @SuppressWarnings("deprecation")
    static double danoItem(ItemStack it) {
        double mmo = PuenteMmo.stat(it, "ATTACK_DAMAGE");
        if (mmo > 0) return mmo;
        Material m = it.getType();
        if (m == Material.BOW || m == Material.CROSSBOW) {
            Enchantment power = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft("power"));
            int poder = power == null ? 0 : it.getEnchantmentLevel(power);
            return 6 * (poder > 0 ? 1 + 0.25 * (poder + 1) : 1);
        }
        Attribute a = Compat.attribute("attack_damage");
        if (a == null) return 1;
        Collection<AttributeModifier> mods = null;
        if (it.hasItemMeta() && it.getItemMeta().hasAttributeModifiers()) mods = it.getItemMeta().getAttributeModifiers(a);
        if (mods == null || mods.isEmpty()) {
            try {
                mods = m.getDefaultAttributeModifiers(EquipmentSlot.HAND).get(a);
            } catch (Throwable t) {
                mods = List.of();
            }
        }
        double suma = 0;
        for (AttributeModifier mod : mods) {
            if (mod.getOperation() == AttributeModifier.Operation.ADD_NUMBER) suma += mod.getAmount();
        }
        return 1 + suma;
    }

    /**
     * Copia visual (P1 sec. 3.2): mismo material, nombre, tinte, trim y brillo si estaba
     * encantada; sin encantamientos, sin datos de MMOItems y con ATTRIBUTE_MODIFIERS vacio
     * EXPLICITO (si no, el material le daria al mob su armadura o su dano de fabrica, y los
     * numeros del Eco ya salen exactos de la foto). Marca eco_copia: si alguna vez aparece
     * como item, Amenazas la borra.
     */
    static ItemStack copiaVisual(ItemStack real) {
        ItemStack c = new ItemStack(real.getType());
        c.editMeta(meta -> meta.getPersistentDataContainer().set(Marcas.ECO_COPIA, PersistentDataType.BYTE, (byte) 1));
        copiar(real, c, DataComponentTypes.CUSTOM_NAME);
        copiar(real, c, DataComponentTypes.ITEM_NAME);
        copiar(real, c, DataComponentTypes.DYED_COLOR);
        copiar(real, c, DataComponentTypes.TRIM);
        copiar(real, c, DataComponentTypes.PROFILE);
        boolean encantada = !real.getEnchantments().isEmpty()
                || Boolean.TRUE.equals(real.getData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE));
        if (encantada) c.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        c.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes().build());
        return c;
    }

    private static <T> void copiar(ItemStack de, ItemStack a, DataComponentType.Valued<T> tipo) {
        try {
            T v = de.getData(tipo);
            if (v != null) a.setData(tipo, v);
        } catch (Throwable ignorado) {
            // Un componente que ese material no admite: la copia sale sin el.
        }
    }

    /** Su cabeza, con las texturas del perfil (van dentro del item y sobreviven al reinicio). */
    private static ItemStack cabeza(Player p) {
        ItemStack h = new ItemStack(Material.PLAYER_HEAD);
        h.editMeta(SkullMeta.class, meta -> {
            try {
                meta.setPlayerProfile(p.getPlayerProfile());
            } catch (Throwable t) {
                meta.setOwningPlayer(p);
            }
            meta.getPersistentDataContainer().set(Marcas.ECO_COPIA, PersistentDataType.BYTE, (byte) 1);
        });
        h.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes().build());
        return h;
    }

    // ------------------------------------------------------------------- stats

    /** El valor de un atributo del jugador sin los modificadores del datapack Bracken. */
    static double sinBracken(LivingEntity p, String clave) {
        Attribute a = Compat.attribute(clave);
        AttributeInstance ai = a == null ? null : p.getAttribute(a);
        if (ai == null) return 0;
        return componer(ai.getBaseValue(), ai.getModifiers());
    }

    /**
     * La cuenta vanilla de un atributo (suma, luego multiplicador sobre la base, luego
     * multiplicadores del total) saltandose el namespace "bracken": en Panacea el datapack le
     * quita un 60 % de armadura al jugador, y un Eco nacido ahi saldria de papel.
     */
    static double componer(double base, Collection<AttributeModifier> mods) {
        double suma = 0, escalar = 0, total = 1;
        for (AttributeModifier m : mods) {
            if (m.getKey() != null && "bracken".equals(m.getKey().getNamespace())) continue;
            switch (m.getOperation()) {
                case ADD_NUMBER -> suma += m.getAmount();
                case ADD_SCALAR -> escalar += m.getAmount();
                case MULTIPLY_SCALAR_1 -> total *= 1 + m.getAmount();
            }
        }
        return Math.max(0, (base + suma) * (1 + escalar) * total);
    }

    /** Si el ultimo golpe se lo dio una PARCA (o algo que ella disparo). */
    private static boolean porParca(Player p) {
        EntityDamageEvent ult = p.getLastDamageCause();
        if (ult == null) return false;
        Entity causa = null;
        try {
            causa = ult.getDamageSource().getCausingEntity();
        } catch (Throwable ignorado) {
            // Sin DamageSource se mira quien pego.
        }
        if (causa == null && ult instanceof EntityDamageByEntityEvent ee) {
            causa = ee.getDamager();
            if (causa instanceof Projectile pr && pr.getShooter() instanceof Entity tirador) causa = tirador;
        }
        return "parca".equals(Marcas.amenaza(causa));
    }

    // ----------------------------------------------------------------- anclaje

    /**
     * Donde murio, a ras de suelo; si murio en el vacio, en lava o dentro de un bloque, su
     * ultimo suelo firme (tambien en el agua: un Eco en el fondo de un lago no se pelea). Y nunca a menos de distancia-puertas de las puertas ni de la
     * llegada: si no, morir en la llegada planta un Eco guardian (X13).
     *
     * 1.2: y nunca dentro de la zona spawn. Quien muere alli (una caida, un /kill, un borde sin
     * WorldGuard) deja el Eco en el sitio seguro mas cercano de fuera, a distancia-puertas del borde:
     * es lo que ya se hace con las puertas, y asi no se pierde lo que llevaba (un Eco que no nace
     * se lo llevaria todo) ni queda un guardian dentro del sitio donde nadie puede pelear.
     */
    private static Location anclar(Player p, Hardcore hc, ConfigurationSection c) {
        Location l = p.getLocation().clone();
        World w = l.getWorld();
        boolean malSitio = w == null || l.getY() < w.getMinHeight() + 1
                || l.getBlock().isLiquid()
                || l.getBlock().getType().isSolid()
                || l.clone().add(0, 1, 0).getBlock().getType().isSolid();
        Location suelo = hc.ultimoSuelo(p);
        if (malSitio && suelo != null && suelo.getWorld() == w) l = suelo;
        else if (w != null && !malSitio) l = Fx.ground(l, 24);
        double margen = c.getDouble("distancia-puertas", 24);
        l = alejarDePuertas(hc, fueraDelSpawn(hc, l, margen), margen);
        // Si apartarse de una puerta lo ha vuelto a meter en la zona, manda el spawn.
        return fueraDelSpawn(hc, l, margen);
    }

    /**
     * 1.2 · Si el sitio cae en la zona spawn, el punto mas cercano fuera: por el lado mas proximo,
     * a "margen" bloques del borde (ZonaSpawn.fuera) y a ras de suelo. Si ahi hay roca (murio en
     * una cueva bajo el spawn: la region de WorldGuard llega hasta abajo), a la superficie.
     */
    static Location fueraDelSpawn(Hardcore hc, Location l, double margen) {
        ZonaSpawn zona = hc.zonaSpawn();
        double[] xz = zona == null || l == null ? null : zona.fueraDe(l, margen);
        if (xz == null) return l;
        World w = l.getWorld();
        Location g = Fx.ground(new Location(w, xz[0], l.getY() + 4, xz[1], l.getYaw(), l.getPitch()), 32);
        if (Parca.libre(g, 2) || w.hasCeiling()) return g;
        g.setY(w.getHighestBlockYAt(g.getBlockX(), g.getBlockZ(), org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1);
        return g;
    }

    /**
     * Empuja el sitio en linea recta hasta quedar a "minimo" de la puerta de entrada, la de
     * salida y la llegada. La direccion sale de la llegada si esta en ese mundo, o de un
     * gradiente de la distancia a las cajas (VaraPortales sabe medir, no donde esta el centro).
     */
    static Location alejarDePuertas(Hardcore hc, Location l, double minimo) {
        if (l == null || l.getWorld() == null || minimo <= 0 || distanciaPuertas(hc, l) >= minimo) return l;
        org.bukkit.util.Vector dir = null;
        Location llegada = hc.punto("llegada");
        if (llegada != null && llegada.getWorld() == l.getWorld() && llegada.distance(l) < minimo) {
            dir = l.toVector().subtract(llegada.toVector()).setY(0);
        }
        if (dir == null || dir.lengthSquared() < 1e-6) {
            double base = distanciaPuertas(hc, l);
            double gx = distanciaPuertas(hc, l.clone().add(1, 0, 0)) - base;
            double gz = distanciaPuertas(hc, l.clone().add(0, 0, 1)) - base;
            dir = new org.bukkit.util.Vector(gx, 0, gz);
        }
        if (dir.lengthSquared() < 1e-6) dir = new org.bukkit.util.Vector(1, 0, 0);
        dir.normalize();
        Location out = l.clone();
        for (int paso = 0; paso < 128 && distanciaPuertas(hc, out) < minimo; paso++) out.add(dir);
        return Fx.ground(out.add(0, 4, 0), 32);
    }

    private static double distanciaPuertas(Hardcore hc, Location l) {
        double d = Double.MAX_VALUE;
        VaraPortales vara = hc.vara();
        if (vara != null) {
            d = Math.min(d, vara.distancia(l, "entrada"));
            d = Math.min(d, vara.distancia(l, "salida"));
        }
        Location llegada = hc.punto("llegada");
        if (llegada != null && llegada.getWorld() == l.getWorld()) d = Math.min(d, llegada.distance(l));
        return d;
    }
}
