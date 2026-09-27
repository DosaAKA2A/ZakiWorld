package net.ederus.lethalworld.hardcore;

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
import org.bukkit.persistence.PersistentDataType;

import java.util.regex.Pattern;

/**
 * El cuerpo que se ve de la PARCA de EDM (1.2.0): un Mannequin con la skin de la cuenta
 * parca.cuerpo.skin (Leonsaurusrex, sin capa) encima del esqueleto invisible que pelea, como
 * Rabby en EDM. Es lo mismo que PeleaParca.ponerCascara, sacado a su clase para la anomalia;
 * la reserva sigue con el suyo para no tocarla mas de lo justo.
 *
 * Los golpes que recibe el maniqui los pasa Parca.onDanoCascara al esqueleto, con el jugador
 * de verdad como autor (tambien las flechas: el tirador, no la flecha). Por eso NO se usa el
 * shell de BossFight en la anomalia: EDM le pasaria el golpe con la flecha como autor, y
 * Amenazas lo tiraria por no venir de un jugador.
 */
final class CuerpoNpc {

    /** Un nombre de cuenta de Minecraft. Lo que no case no se manda a Mojang (va en una URL). */
    private static final Pattern CUENTA = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final Hardcore hc;
    private final Parca.Ajustes a;
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
        this.hc = hc;
        this.a = a;
    }

    /** Si la config pide cuerpo de NPC y la cuenta es valida. */
    static boolean pedido(Parca.Ajustes a) {
        return a.cuerpoActivo && a.cuerpoSkin != null && CUENTA.matcher(a.cuerpoSkin).matches();
    }

    /**
     * Lo pone en l, encima de "dueno" (el esqueleto que pelea). Sale con el perfil sin
     * resolver (un instante con la skin de serie) y en cuanto Mojang contesta, fuera del hilo
     * principal, se le cambia la cara. False si no se pudo: quien llama viste el esqueleto.
     *
     * @param brillo color del contorno (el de la anomalia), o null sin brillo
     */
    boolean poner(LivingEntity dueno, Location l, NamedTextColor brillo) {
        perfil = Disguises.profileOfAccount(hc.plugin(), a.cuerpoSkin);
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
                try {
                    // La cuenta de la skin trae capa y a la Parca no le pega: todas las capas
                    // de la skin (chaqueta, mangas, sombrero) menos esa.
                    com.destroystokyo.paper.SkinParts.Mutable partes = com.destroystokyo.paper.SkinParts.allParts();
                    partes.setCapeEnabled(false);
                    m.setSkinParts(partes);
                } catch (Throwable ignorado) {
                    // Sin la API de capas se ve la capa: feo, pero la pelea sigue.
                }
                Compat.setAttribute(m, "scale", a.cuerpoEscala);
                // Sin probabilidad de soltarla: eso solo existe en los Mob (el maniqui no lo
                // es). Si alguien lo mata con /kill, Parca.onMuerte le vacia lo que suelte.
                m.getEquipment().setItemInMainHand(PeleaParca.guadana());
            });
        } catch (Throwable t) {
            mq = null;
            hc.plugin().getLogger().warning("[Calamity] No se pudo poner el cuerpo de la Parca: " + t);
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
        Disguises.resolveAccount(hc.plugin(), a.cuerpoSkin, this::reskin);
        return true;
    }

    /** Llega la skin resuelta (hilo principal). La pelea puede haber acabado ya: entonces nada. */
    private void reskin(ResolvableProfile resuelto) {
        if (resuelto == null) return;
        perfil = resuelto;
        if (mq != null && mq.isValid()) mq.setProfile(resuelto);
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

    /** El estremecimiento y el quejido de un golpe que ha entrado. */
    void dolor() {
        if (!valido()) return;
        mq.playHurtAnimation(0f);
        Compat.sound(mq.getWorld(), mq.getLocation(), "entity.wither_skeleton.hurt", 0.9f, 0.55f);
    }

    /** Agachado aturdida o cargando un salto, girando en las guadanas, de pie el resto. */
    void postura(Pose pose) {
        if (!valido()) return;
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
