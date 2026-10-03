package net.ederus.calamity.hardcore;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Las marcas de Calamity en el PersistentDataContainer, todas "lethal_world:" (DIS sec. 8.4-sec. 8.5).
 *
 * El namespace se escribe a mano. Con la clave construida a partir del plugin saldria el
 * nombre del plugin en minusculas ("lethalworld:"), que no es lo que se lee en los mundos
 * ("lethal_world:calamity") ni lo que espera quien mire un item con /data. Las marcas que
 * ya circulan (frasco, cristal, esencia, vara, mobs) siguen en "edm:" y no estan aqui.
 *
 * Es publica porque MobsLethal (otro paquete) tiene que saltarse a las amenazas.
 */
public final class Marcas {

    public static final String NAMESPACE = "lethal_world";

    // ------------------------------------------------------------- entidades (sec. 8.4)
    /** STRING parca|planidera|eco|alucinacion|rondador: toda entidad nuestra. */
    public static final NamespacedKey AMENAZA = clave("amenaza");
    /** STRING uuid: a quien persigue una PARCA o una planidera. */
    public static final NamespacedKey PRESA = clave("presa");
    /** STRING idEco: el cuerpo del Eco. */
    public static final NamespacedKey ECO = clave("eco");
    /** STRING uuid: el dueno de un Eco. */
    public static final NamespacedKey ECO_DUENO = clave("eco_dueno");
    /** DOUBLE: dano recibido de jugadores (cuota de M2). */
    public static final NamespacedKey DANO_JUGADOR = clave("dano_jugador");
    /**
     * DOUBLE: vida logica de una amenaza con mas de 1024 (Amenazas.vidaLogica). Va en la
     * entidad para que un Eco que se descarga y vuelve siga sabiendo cuanta vida tiene.
     */
    public static final NamespacedKey VIDA_LOGICA = clave("vida_logica");
    /**
     * STRING uuid: el cuerpo visible (Mannequin con skin) de una amenaza, con el UUID de la
     * entidad que pelea debajo. No es una amenaza: no recibe dano propio, lo pasa a su dueno.
     */
    public static final NamespacedKey CASCARA = clave("cascara");

    // ------------------------------------------------------------------ items (sec. 8.5)
    /** BYTE 1: copia visual del equipo de un Eco. Se borra si aparece como item. */
    public static final NamespacedKey ECO_COPIA = clave("eco_copia");
    public static final NamespacedKey RELIQUIA = clave("reliquia");
    public static final NamespacedKey RELIQUIA_ID = clave("reliquia_id");
    public static final NamespacedKey RELIQUIA_ORIGEN = clave("reliquia_origen");
    public static final NamespacedKey RELIQUIA_NACIO = clave("reliquia_nacio");
    public static final NamespacedKey RELIQUIA_ESPECIAL = clave("reliquia_especial");
    public static final NamespacedKey RELIQUIA_NIVEL = clave("reliquia_nivel");
    public static final NamespacedKey RELIQUIA_MINIJEFE = clave("reliquia_minijefe");
    public static final NamespacedKey RELIQUIA_VALIDA = clave("reliquia_valida");
    public static final NamespacedKey TROFEO = clave("trofeo");
    public static final NamespacedKey PRESTADO = clave("prestado");
    public static final NamespacedKey LIGADO = clave("ligado");
    public static final NamespacedKey TALISMAN = clave("talisman");
    public static final NamespacedKey GRABADO = clave("grabado");
    public static final NamespacedKey SALVOCONDUCTO = clave("salvoconducto");
    /** BYTE 1: un Fragmento de Masamune (lo que deja Ambush a su presa; la Forja pide cinco). */
    public static final NamespacedKey FRAGMENTO_MASAMUNE = clave("fragmento_masamune");
    /**
     * STRING "uuid;dia;hueco;id": el pergamino de un contrato de Oren (Calamity 1.10, Pergaminos). Dice
     * de quien es, de que dia, que hueco y que contrato; la verdad sigue en contratos.<uuid>, y un papel
     * que no cuadra con ella es inerte y se borra.
     */
    public static final NamespacedKey PERGAMINO = clave("pergamino");

    private Marcas() {
    }

    private static NamespacedKey clave(String nombre) {
        return new NamespacedKey(NAMESPACE, nombre);
    }

    /** Todas, para el autotest (que ninguna se haya escrito con otro namespace). */
    static List<NamespacedKey> todas() {
        return List.of(AMENAZA, PRESA, ECO, ECO_DUENO, DANO_JUGADOR, VIDA_LOGICA, CASCARA, ECO_COPIA, RELIQUIA, RELIQUIA_ID,
                RELIQUIA_ORIGEN, RELIQUIA_NACIO, RELIQUIA_ESPECIAL, RELIQUIA_NIVEL, RELIQUIA_MINIJEFE,
                RELIQUIA_VALIDA, TROFEO, PRESTADO, LIGADO, TALISMAN, GRABADO, SALVOCONDUCTO, FRAGMENTO_MASAMUNE,
                PERGAMINO);
    }

    /** Si una entidad es nuestra (PARCA, planidera, Eco...). */
    public static boolean esAmenaza(Entity e) {
        return e != null && e.getPersistentDataContainer().has(AMENAZA, PersistentDataType.STRING);
    }

    /** El tipo de amenaza (parca, eco...) o null. */
    public static String amenaza(Entity e) {
        return e == null ? null : e.getPersistentDataContainer().get(AMENAZA, PersistentDataType.STRING);
    }

    /** Si un item lleva esa marca, sea del tipo que sea. */
    public static boolean tiene(ItemStack item, NamespacedKey clave) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(clave);
    }
}
