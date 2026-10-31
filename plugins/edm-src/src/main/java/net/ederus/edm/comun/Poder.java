package net.ederus.edm.comun;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * El PODER de un jugador: un solo numero que dice lo fuerte que es ahora mismo.
 *
 * Hasta hoy el nivel de los mobs de Lethal World salia solo del rango de rankup y del
 * poder de AuraSkills, o sea del TIEMPO jugado. Eso deja fuera lo que de verdad decide
 * una pelea: el equipo que llevas puesto. Un jugador de rango 3 con el Manto de
 * Calamidad aplasta a uno de rango 9 con hierro, y los mobs no se enteraban.
 *
 * Aqui se suma todo: rango, habilidades y lo que llevas encima. Y se lee del jugador
 * por ATRIBUTOS de vanilla (armadura, dureza, vida, dano), no por la API de MMOItems:
 * asi cuenta igual una pieza de MMOItems, una vanilla encantada o lo que venga manana,
 * porque todas acaban moviendo los mismos atributos.
 *
 * Los pesos estan en la config para poder afinarlo sin recompilar.
 */
public final class Poder {

    /** El desglose, para poder ENSENAR de donde sale cada punto y no solo el total. */
    public record Desglose(int rango, double auraskills, double armadura, double dureza,
                           double vida, double dano, double total) {

        public String linea(String etiqueta, double valor, double peso) {
            return String.format(Locale.US, "%s %.1f x%.1f = %.0f", etiqueta, valor, peso, valor * peso);
        }
    }

    private Poder() {
    }

    /**
     * Rango de rankup por PlaceholderAPI. 0 si no se puede leer.
     *
     * Vive aqui y no en el modulo que lo use: el Poder es del JUGADOR, no de un
     * mundo ni de una vitrina, y cualquier modulo tiene que poder preguntarlo sin
     * depender de que otro este cargado.
     */
    public static int rango(org.bukkit.plugin.Plugin plugin, Player p) {
        try {
            String marcador = cfg(plugin).getString("placeholder-rango", "%notranks_rank_number%");
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Object r = papi.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class)
                    .invoke(null, p, marcador);
            return Integer.parseInt(String.valueOf(r).replaceAll("[^0-9]", ""));
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Lo que suman de media los criticos de MythicLib a cada golpe: prob x (multiplicador - 1).
     * CRITICAL_STRIKE_POWER ya trae la base (200 = x2) mas lo que den los items (+1.8 = +1,8 %).
     * La prob se corta en el tope de MythicLib (80 %). 0 si MythicLib no esta.
     */
    public static double critico(Player p) {
        try {
            io.lumine.mythic.lib.api.stat.StatMap stats =
                    io.lumine.mythic.lib.api.player.MMOPlayerData.get(p).getStatMap();
            double prob = Math.min(80, Math.max(0, stats.getStat("CRITICAL_STRIKE_CHANCE"))) / 100.0;
            double mult = Math.max(100, stats.getStat("CRITICAL_STRIKE_POWER")) / 100.0;
            return prob * (mult - 1);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Poder de AuraSkills (suma de sus habilidades) por su API. 0 si no esta. */
    public static int auraskills(Player p) {
        try {
            Class<?> api = Class.forName("dev.aurelium.auraskills.api.AuraSkillsApi");
            Object inst = api.getMethod("get").invoke(null);
            Object user = inst.getClass().getMethod("getUser", java.util.UUID.class)
                    .invoke(inst, p.getUniqueId());
            if (user == null) return 0;
            return (int) user.getClass().getMethod("getPowerLevel").invoke(user);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** El poder de un jugador leyendo el rango y AuraSkills por su cuenta. */
    public static Desglose calcular(org.bukkit.plugin.Plugin plugin, Player p) {
        return calcular(plugin, p, rango(plugin, p), auraskills(p));
    }

    /** El peso de una parte del Poder, el mismo que usa calcular(). */
    public static double peso(org.bukkit.plugin.Plugin plugin, String clave, double def) {
        return cfg(plugin).getDouble(clave, def);
    }

    private static ConfigurationSection cfg(org.bukkit.plugin.Plugin plugin) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("poder");
        return s == null ? new YamlConfiguration() : s;
    }

    /**
     * Calcula el poder de un jugador.
     *
     * De la vida y del dano se cuenta solo lo que pasa de lo normal (20 de vida, 1 de
     * dano a mano vacia): si no, todo el mundo empezaria con puntos de regalo y el
     * numero no distinguiria a nadie.
     */
    public static Desglose calcular(org.bukkit.plugin.Plugin plugin, Player p,
                                    int rango, double auraskills) {
        ConfigurationSection c = cfg(plugin);

        double armadura = Compat.getAttribute(p, "armor", 0);
        double dureza = Compat.getAttribute(p, "armor_toughness", 0);
        double vida = Math.max(0, Compat.getAttribute(p, "max_health", 20) - 20);
        // El dano cuenta con los criticos de MythicLib: un golpe medio vale
        // dano x (1 + prob x (multiplicador - 1)). Con 30 % de critico a x2 pega un 30 % mas.
        // El arma de la mano: una espada por su atributo; un arco por lo que pega su
        // flecha tensada del todo y un tridente con daño de lanzamiento por ese daño
        // (EDM 1.91.0). Sin esto un arco contaba como pegar con el puño.
        double golpe = danoArma(p) * (1 + critico(p));
        double dano = Math.max(0, golpe - 1);

        /* Los mismos numeros que el config de serie, A PROPOSITO: en un servidor que
         * ya tenia flex/config.yml (el Survival) el bloque poder: no se escribe solo,
         * y con los pesos viejos aqui la cifra saldria veinte veces mas baja sin que
         * nadie entendiera por que. */
        double total = rango * c.getDouble("por-rango", 200)
                + auraskills * c.getDouble("por-auraskills", 20)
                + armadura * c.getDouble("por-armadura", 80)
                + dureza * c.getDouble("por-dureza", 120)
                + vida * c.getDouble("por-vida", 40)
                + dano * c.getDouble("por-dano", 60);

        return new Desglose(rango, auraskills, armadura, dureza, vida, dano, total);
    }

    /* ============================================================ armas */

    /** La clave de GodItems con el daño del tridente lanzado (goditems/equipo/, por pieza). */
    public static final String CLAVE_TRIDENTE = "distancia.tridente-lanzado";

    /**
     * El golpe base (sin criticos) del arma de la mano principal: el atributo de
     * daño para lo cuerpo a cuerpo, la flecha tensada del todo para arcos y
     * ballestas, y el daño de lanzamiento para un tridente que lo tenga en GodItems.
     */
    public static double danoArma(Player p) {
        org.bukkit.inventory.ItemStack mano = p.getInventory().getItemInMainHand();
        org.bukkit.Material m = mano.getType();
        if (m == org.bukkit.Material.TRIDENT) {
            double lanzado = tridenteLanzado(mano);
            if (lanzado > 0) return lanzado;
        }
        if (m == org.bukkit.Material.BOW || m == org.bukkit.Material.CROSSBOW) return danoDistancia(p, mano);
        return Compat.getAttribute(p, "attack_damage", 1);
    }

    /** El daño de lanzamiento que GodItems le da a ese tridente, o 0. */
    public static double tridenteLanzado(org.bukkit.inventory.ItemStack item) {
        try {
            String id = net.ederus.edm.goditems.api.EquipoApi.identidad(item);
            if (id == null) return 0;
            Double d = net.ederus.edm.goditems.api.EquipoApi.efectosDePieza(id, "distancia.").get(CLAVE_TRIDENTE);
            return d == null ? 0 : d;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Lo que pega la flecha de un arco o ballesta tensado del todo.
     *
     * Uno de MMOItems dispara con el ATTACK_DAMAGE del tirador (el de MythicLib,
     * que incluye el attack-damage del arco) y MythicLib lo multiplica por Poder:
     * x(1,25 + 0,25 x nivel) si el arco lo lleva. Uno de vanilla dispara una
     * flecha de 2 por su velocidad (3 el arco, 3,15 la ballesta), redondeando
     * hacia arriba; Poder le suma 0,5 por nivel mas 0,5.
     */
    public static double danoDistancia(Player p, org.bukkit.inventory.ItemStack arco) {
        double mmo = danoMmo(arco);
        if (mmo > 0) return Math.max(mmo, ataqueMythic(p)) * factorPoder(arco);
        double base = 2;
        int poder = nivelPoder(arco);
        if (poder > 0) base += 0.5 * poder + 0.5;
        double velocidad = arco.getType() == org.bukkit.Material.CROSSBOW ? 3.15 : 3.0;
        return Math.ceil(base * velocidad);
    }

    /** El multiplicador de Poder que MythicLib aplica a las flechas de MMOItems: 1 sin Poder. */
    public static double factorPoder(org.bukkit.inventory.ItemStack arco) {
        int n = nivelPoder(arco);
        return n > 0 ? 1.25 + 0.25 * n : 1;
    }

    private static int nivelPoder(org.bukkit.inventory.ItemStack item) {
        try {
            return item == null ? 0 : item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.POWER);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** El ATTACK_DAMAGE del jugador para MythicLib, o 0 sin MythicLib. */
    private static double ataqueMythic(Player p) {
        try {
            return io.lumine.mythic.lib.api.player.MMOPlayerData.get(p).getStatMap().getStat("ATTACK_DAMAGE");
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * La stat attack-damage de un item de MMOItems, o 0. Primero por MythicLib, que
     * es lo que no se rompe cuando cambian sus etiquetas; si no esta, a mano por el
     * PDC de su namespace (la clave acaba en attack_damage).
     */
    public static double danoMmo(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType().isAir()) return 0;
        try {
            io.lumine.mythic.lib.api.item.NBTItem nbt = io.lumine.mythic.lib.api.item.NBTItem.get(item);
            if (nbt.hasTag("MMOITEMS_ATTACK_DAMAGE")) return nbt.getDouble("MMOITEMS_ATTACK_DAMAGE");
            return 0;
        } catch (Throwable t) {
            // Sin MythicLib: al PDC.
        }
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0;
        var pdc = meta.getPersistentDataContainer();
        for (var k : pdc.getKeys()) {
            if (!k.getNamespace().equalsIgnoreCase("mmoitems")) continue;
            if (!k.getKey().toLowerCase(Locale.ROOT).endsWith("attack_damage")) continue;
            try {
                Double d = pdc.get(k, org.bukkit.persistence.PersistentDataType.DOUBLE);
                if (d != null) return d;
            } catch (Throwable ignored) {
                // Puede estar guardado como texto.
            }
            try {
                String s = pdc.get(k, org.bukkit.persistence.PersistentDataType.STRING);
                if (s != null) return Double.parseDouble(s.trim());
            } catch (Throwable ignored) {
                // No era un numero.
            }
        }
        return 0;
    }

}
