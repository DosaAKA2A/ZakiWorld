package net.ederus.calamity.hardcore;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.ederus.edm.anomaly.core.Disguises;
import net.ederus.edm.anomaly.core.Glow;
import net.ederus.edm.comun.Compat;
import net.ederus.edm.comun.Fx;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * El cuerpo que se ve de la PARCA de EDM (Calamity 1.1.0): un Mannequin con la skin de la cuenta
 * parca.cuerpo.skin (Leonsaurusrex, sin capa) encima del esqueleto invisible que pelea, como
 * Rabby en EDM. Es lo mismo que PeleaParca.ponerCascara, sacado a su clase para la anomalia;
 * la reserva sigue con el suyo para no tocarla mas de lo justo.
 *
 * Calamity 1.8.0: tambien es el cuerpo de Ambush, con su skin, su tamano, su katana y su
 * quejido. Por eso la cuenta, la escala y lo que lleva en la mano ya no salen de Parca.Ajustes
 * sino del constructor; la Parca sigue llamando al de siempre. Ambush cambia de skin al pasar
 * a su fase 2 (cambiarSkin) y suelta y recoge la katana (empunar).
 *
 * Los golpes que recibe el maniqui los pasa Parca.onDanoCascara al esqueleto, con el jugador
 * de verdad como autor (tambien las flechas: el tirador, no la flecha). Por eso NO se usa el
 * shell de BossFight en la anomalia: EDM le pasaria el golpe con la flecha como autor, y
 * Amenazas lo tiraria por no venir de un jugador.
 */
final class CuerpoNpc {

    /** Un nombre de cuenta de Minecraft. Lo que no case no se manda a Mojang (va en una URL). */
    private static final Pattern CUENTA = Pattern.compile("[A-Za-z0-9_]{3,16}");

    /**
     * Las posturas que un Mannequin acepta (Paper 26.2, CraftMannequin.setPose): con cualquier
     * otra lanza IllegalArgumentException. SPIN_ATTACK (el giro del tridente) no esta: el giro del
     * tajo doble de Ambush se hace girando el cuerpo.
     */
    static final Set<Pose> POSTURAS = Set.of(Pose.STANDING, Pose.SNEAKING, Pose.SWIMMING, Pose.FALL_FLYING,
            Pose.SLEEPING);

    private final Hardcore hc;
    /** La cuenta cuya skin lleva ahora (Ambush la cambia en su fase 2). */
    private String skin;
    /** Tamano del cuerpo; 1 = un jugador. */
    private final double escala;
    /** Lo que lleva en la mano al nacer: la guadana de la Parca, la katana de Ambush. */
    private final ItemStack arma;
    private final String sonidoDolor;
    private Mannequin mq;
    /**
     * El perfil que DEBE llevar el maniqui ahora mismo. La resolucion de la skin llega por la
     * red y puede entrar antes o despues del reintento de los 2 ticks: el reintento pone
     * siempre este, el vigente, y no el que se capturo al nacer (el fallo que tuvo Rabby).
     */
    private ResolvableProfile perfil;
    private int pulsos;
    /** El brillo que lleva (se quita antes de retirarlo: el equipo del marcador se guarda en disco). */
    private boolean brilla;

    CuerpoNpc(Hardcore hc, Parca.Ajustes a) {
        this(hc, a.cuerpoSkin, a.cuerpoEscala, PeleaParca.guadana(), "entity.wither_skeleton.hurt");
    }

    /** Calamity 1.8.0: un cuerpo con su skin, su tamano, lo que lleva en la mano y su quejido. */
    CuerpoNpc(Hardcore hc, String skin, double escala, ItemStack arma, String sonidoDolor) {
        this.hc = hc;
        this.skin = skin;
        this.escala = escala;
        this.arma = arma;
        this.sonidoDolor = sonidoDolor;
    }

    /** Si la config pide cuerpo de NPC y la cuenta es valida. */
    static boolean pedido(Parca.Ajustes a) {
        return a.cuerpoActivo && cuentaValida(a.cuerpoSkin);
    }

    /** Si ese nombre puede ser una cuenta de Minecraft (es lo unico que se manda a Mojang). */
    static boolean cuentaValida(String cuenta) {
        return cuenta != null && CUENTA.matcher(cuenta).matches();
    }

    /**
     * Lo pone en l, encima de "dueno" (el esqueleto que pelea). Sale con el perfil sin
     * resolver (un instante con la skin de serie) y en cuanto Mojang contesta, fuera del hilo
     * principal, se le cambia la cara. False si no se pudo: quien llama viste el esqueleto.
     *
     * @param brillo color del contorno (el de la anomalia), o null sin brillo
     */
    boolean poner(LivingEntity dueno, Location l, NamedTextColor brillo) {
        perfil = Disguises.profileOfAccount(hc.plugin(), skin);
        if (perfil == null || l.getWorld() == null) return false;
        try {
            mq = l.getWorld().spawn(l, Mannequin.class, m -> {
                m.getPersistentDataContainer().set(Marcas.CASCARA, PersistentDataType.STRING,
                        dueno.getUniqueId().toString());
                // Nada de esto se guarda: un reinicio no deja cuerpos sueltos por el mundo.
                m.setPersistent(false);
                m.setGravity(false);
                m.setCollidable(false);
                m.setSilent(true);
                m.setImmovable(true);
                // Sin nombre propio: el cartel "Nv. X" lo pone MinionManager sobre el esqueleto.
                m.setCustomNameVisible(false);
                try {
                    // El maniqui trae de serie una segunda linea "NPC" bajo el nombre. Fuera.
                    m.setDescription(Component.empty());
                } catch (Throwable ignorado) {
                    // Sin descripcion editable se ve la linea: feo, pero la pelea sigue.
                }
                m.setProfile(perfil);
                sinCapa(m);
                Compat.setAttribute(m, "scale", escala);
                // Sin probabilidad de soltarla: eso solo existe en los Mob (el maniqui no lo
                // es). Si alguien lo mata con /kill, Parca.onMuerte le vacia lo que suelte.
                if (arma != null) m.getEquipment().setItemInMainHand(arma.clone());
            });
        } catch (Throwable t) {
            mq = null;
            hc.plugin().getLogger().warning("[Calamity] No se pudo poner el cuerpo con la skin de " + skin + ": " + t);
        }
        if (mq == null || !mq.isValid()) {
            mq = null;
            return false;
        }
        pulsos = 0;
        if (brillo != null) {
            try {
                Glow.apply(mq, brillo);
                brilla = true;
            } catch (Throwable ignorado) {
                // Sin brillo se la ve igual: es un cuerpo de 3 bloques con guadana.
            }
        }
        // La skin de verdad sale de Mojang por la red: fuera del hilo principal y cacheada en
        // EDM (la segunda PARCA desde el arranque ya la tiene al momento).
        resolver(skin);
        return true;
    }

    /**
     * Todas las capas de la skin (chaqueta, mangas, sombrero) menos la capa: la cuenta de la
     * Parca trae una y no le pega. Tambien la usan las sombras de Ambush.
     */
    static void sinCapa(Mannequin m) {
        try {
            com.destroystokyo.paper.SkinParts.Mutable partes = com.destroystokyo.paper.SkinParts.allParts();
            partes.setCapeEnabled(false);
            m.setSkinParts(partes);
        } catch (Throwable ignorado) {
            // Sin la API de capas se ve la capa: feo, pero la pelea sigue.
        }
    }

    /**
     * Calamity 1.8.0: cambia de skin sin quitar el maniqui (la fase 2 de Ambush). El perfil sin
     * resolver sale al momento y el bueno en cuanto contesta Mojang, como al nacer.
     */
    void cambiarSkin(String cuenta) {
        if (!cuentaValida(cuenta) || cuenta.equals(skin)) return;
        skin = cuenta;
        ResolvableProfile nuevo = Disguises.profileOfAccount(hc.plugin(), cuenta);
        if (nuevo != null) {
            perfil = nuevo;
            if (valido()) mq.setProfile(nuevo);
        }
        resolver(cuenta);
    }

    /** Pide la skin de esa cuenta; si cuando llega ya se lleva otra (cambio de fase), no se pone. */
    private void resolver(String cuenta) {
        Disguises.resolveAccount(hc.plugin(), cuenta, resuelto -> {
            if (cuenta.equals(skin)) reskin(resuelto);
        });
    }

    /** Llega la skin resuelta (hilo principal). La pelea puede haber acabado ya: entonces nada. */
    private void reskin(ResolvableProfile resuelto) {
        if (resuelto == null) return;
        perfil = resuelto;
        if (mq != null && mq.isValid()) mq.setProfile(resuelto);
    }

    /** El perfil que lleva ahora, para las copias visuales (las sombras de Ambush). Null sin maniqui. */
    ResolvableProfile perfil() {
        return perfil;
    }

    double escala() {
        return escala;
    }

    /**
     * Se pega a l (ya orientada por quien llama; el cabeceo se limita a +-30 para que no mire
     * al suelo ni al cielo). False si el maniqui ya no existe: quien llama vuelve al esqueleto.
     */
    boolean seguir(Location l) {
        if (mq == null) return false;
        if (!mq.isValid()) {
            mq = null;
            return false;
        }
        l.setPitch(Math.max(-30f, Math.min(30f, l.getPitch())));
        mq.teleport(l);
        // El primer paquete a veces llega sin la skin (BossFight.wearShell): otra vez a los 2 ticks.
        if (++pulsos == 2 && perfil != null) mq.setProfile(perfil);
        return true;
    }

    boolean valido() {
        return mq != null && mq.isValid();
    }

    /** El maniqui, o null. */
    Mannequin entidad() {
        return valido() ? mq : null;
    }

    boolean es(Entity e) {
        return e != null && mq != null && mq.getUniqueId().equals(e.getUniqueId());
    }

    /** Blande la guadana (el esqueleto que pega es invisible). */
    void blandir() {
        if (valido()) mq.swingMainHand();
    }

    /** El reves: la otra mano, para el segundo tajo de un combo. */
    void reves() {
        if (valido()) mq.swingOffHand();
    }

    /** Lo que lleva en la mano (Ambush: la katana, o nada mientras envaina). */
    void empunar(ItemStack item) {
        if (valido()) mq.getEquipment().setItemInMainHand(item == null ? null : item.clone());
    }

    /** El estremecimiento y el quejido de un golpe que ha entrado. */
    void dolor() {
        if (!valido()) return;
        mq.playHurtAnimation(0f);
        Compat.sound(mq.getWorld(), mq.getLocation(), sonidoDolor, 0.9f, 0.55f);
    }

    /**
     * Agachado aturdida o cargando un salto, tumbado en una acometida, de pie el resto. Una
     * postura que el maniqui no admite (fuera de POSTURAS) no se intenta: se queda como estaba.
     */
    void postura(Pose pose) {
        if (!valido() || pose == null || !POSTURAS.contains(pose)) return;
        try {
            mq.setPose(pose, pose != Pose.STANDING);
        } catch (Throwable ignorado) {
            // Una postura que el maniqui no admite: se queda de pie, sin mas.
        }
    }

    /** Lo suelta para la despedida (Parca.despedida): desde aqui ya no es suyo ni lo quita. */
    Mannequin soltar() {
        Mannequin m = entidad();
        quitarBrillo();
        mq = null;
        return m;
    }

    void quitar() {
        quitarBrillo();
        Fx.safeRemove(mq);
        mq = null;
    }

    private void quitarBrillo() {
        if (!brilla || mq == null) return;
        brilla = false;
        try {
            Glow.clear(mq);
        } catch (Throwable ignorado) {
            // Si EDM cambia Glow, la entrada del equipo se queda: la purga EDM al arrancar.
        }
    }
}
