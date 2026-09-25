package net.ederus.edm.glow;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.InternalStructure;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;

/**
 * EL UNICO SITIO DEL MODULO QUE HABLA CON PROTOCOLLIB.
 *
 * El cliente pinta el contorno con el color del equipo del marcador en el que
 * esta el jugador. Esos equipos son de TAB (uno por jugador, "@Dosa__A") y TAB
 * no deja que nadie mas meta a un jugador en otro equipo: por eso FancyGlow y
 * EderusGlow no cambiaban el color, su log lo dice ("Blocked attempt to add
 * player ... into team eg_AQUA").
 *
 * Aqui no se toca ningun equipo. Se deja a TAB mandar los suyos y, justo al
 * salir hacia el cliente, se cambia el campo color del paquete del equipo de
 * quien brilla. TAB sigue ordenando la lista y poniendo prefijos como siempre.
 * Como el nick sobre la cabeza lo dibuja UnlimitedNameTags y no el equipo, el
 * color nuevo solo se nota en el brillo.
 *
 * Para poder repintar al momento (cambiar de color, el arcoiris) se guarda el
 * ultimo paquete de cada equipo tal y como lo mando TAB, con su color de origen,
 * y se reenvia con el color que toque. Los reenvios salen SIN pasar por los
 * escuchas (filtros apagados) para que no se tomen por paquetes de TAB.
 */
final class Equipos extends PacketAdapter {

    private static final int CREAR = 0;
    private static final int QUITAR = 1;
    private static final int CAMBIAR = 2;
    private static final int ENTRAN = 3;
    private static final int SALEN = 4;

    /** Nombre del jugador (minusculas) -> el color que debe tener su contorno ahora. */
    private final Map<String, EnumWrappers.ChatFormatting> activos = new ConcurrentHashMap<>();
    /** Nombre del jugador (minusculas) -> su equipo. */
    private final Map<String, String> equipoDe = new ConcurrentHashMap<>();
    /** Equipo -> sus miembros (minusculas). */
    private final Map<String, Set<String>> miembros = new ConcurrentHashMap<>();
    /** Equipo -> el ultimo paquete de TAB con parametros, y el color que traia. */
    private final Map<String, PacketContainer> ultimo = new ConcurrentHashMap<>();
    private final Map<String, EnumWrappers.ChatFormatting> colorOriginal = new ConcurrentHashMap<>();

    Equipos(Plugin plugin) {
        super(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.SCOREBOARD_TEAM);
    }

    void registrar() {
        ProtocolLibrary.getProtocolManager().addPacketListener(this);
    }

    void soltar() {
        ProtocolLibrary.getProtocolManager().removePacketListener(this);
    }

    /* ---------- lo que decide el modulo ---------- */

    /** El contorno de este jugador debe verse de este color (null = el suyo de siempre). */
    void fijar(Player p, EnumWrappers.ChatFormatting color) {
        String n = p.getName().toLowerCase(Locale.ROOT);
        if (color == null) activos.remove(n);
        else activos.put(n, color);
        repintar(n);
    }

    boolean conoceEquipo(Player p) {
        return equipoDe.containsKey(p.getName().toLowerCase(Locale.ROOT));
    }

    /** Reenvia a todos el equipo de este jugador con el color que toque ahora. */
    private void repintar(String nombre) {
        String equipo = equipoDe.get(nombre);
        if (equipo == null) return;
        PacketContainer base = ultimo.get(equipo);
        if (base == null) return;
        EnumWrappers.ChatFormatting color = activos.getOrDefault(nombre, colorOriginal.get(equipo));
        if (color == null) return;
        PacketContainer p = base.deepClone();
        p.getIntegers().write(0, CAMBIAR);
        if (!pintar(p, color)) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            try {
                ProtocolLibrary.getProtocolManager().sendServerPacket(viewer, p, false);
            } catch (RuntimeException e) {
                // Un jugador que se va a mitad del envio: no pasa nada.
            }
        }
    }

    /* ---------- lo que sale hacia los clientes ---------- */

    @Override
    public void onPacketSending(PacketEvent e) {
        if (e.isPlayerTemporary()) return;
        PacketContainer p = e.getPacket();
        int modo;
        String equipo;
        try {
            modo = p.getIntegers().read(0);
            equipo = p.getStrings().read(0);
        } catch (RuntimeException ex) {
            return;
        }
        switch (modo) {
            case QUITAR -> olvidar(equipo);
            case CREAR -> {
                entran(equipo, jugadores(p));
                recordar(equipo, p);
            }
            case CAMBIAR -> recordar(equipo, p);
            case ENTRAN -> entran(equipo, jugadores(p));
            case SALEN -> salen(equipo, jugadores(p));
            default -> {
            }
        }
        if (modo != CREAR && modo != CAMBIAR) return;

        EnumWrappers.ChatFormatting color = colorDelEquipo(equipo);
        if (color == null) return;
        /* Se pinta una copia: el paquete original puede ser el mismo objeto que
         * TAB le manda al siguiente jugador o que guarda para despues. */
        PacketContainer copia = p.deepClone();
        if (pintar(copia, color)) e.setPacket(copia);
    }

    private EnumWrappers.ChatFormatting colorDelEquipo(String equipo) {
        Set<String> dentro = miembros.get(equipo);
        if (dentro == null) return null;
        for (String n : dentro) {
            EnumWrappers.ChatFormatting c = activos.get(n);
            if (c != null) return c;
        }
        return null;
    }

    private void recordar(String equipo, PacketContainer p) {
        EnumWrappers.ChatFormatting color = leerColor(p);
        if (color == null) return;
        ultimo.put(equipo, p.deepClone());
        colorOriginal.put(equipo, color);
    }

    private void olvidar(String equipo) {
        Set<String> dentro = miembros.remove(equipo);
        if (dentro != null) for (String n : dentro) equipoDe.remove(n, equipo);
        ultimo.remove(equipo);
        colorOriginal.remove(equipo);
    }

    private void entran(String equipo, Collection<String> nombres) {
        if (nombres == null) return;
        Set<String> dentro = miembros.computeIfAbsent(equipo, k -> ConcurrentHashMap.newKeySet());
        for (String n : nombres) {
            String k = n.toLowerCase(Locale.ROOT);
            dentro.add(k);
            equipoDe.put(k, equipo);
        }
    }

    private void salen(String equipo, Collection<String> nombres) {
        if (nombres == null) return;
        Set<String> dentro = miembros.get(equipo);
        for (String n : nombres) {
            String k = n.toLowerCase(Locale.ROOT);
            if (dentro != null) dentro.remove(k);
            equipoDe.remove(k, equipo);
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> jugadores(PacketContainer p) {
        try {
            return (Collection<String>) p.getSpecificModifier(Collection.class).read(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static EnumWrappers.ChatFormatting leerColor(PacketContainer p) {
        try {
            Optional<InternalStructure> params = p.getOptionalStructures().read(0);
            if (params == null || params.isEmpty()) return null;
            return params.get()
                    .getEnumModifier(EnumWrappers.ChatFormatting.class, EnumWrappers.getChatFormattingClass())
                    .read(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean pintar(PacketContainer p, EnumWrappers.ChatFormatting color) {
        try {
            Optional<InternalStructure> params = p.getOptionalStructures().read(0);
            if (params == null || params.isEmpty()) return false;
            InternalStructure s = params.get();
            s.getEnumModifier(EnumWrappers.ChatFormatting.class, EnumWrappers.getChatFormattingClass()).write(0, color);
            p.getOptionalStructures().write(0, Optional.of(s));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
