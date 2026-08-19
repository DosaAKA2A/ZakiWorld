/*
 * Decompiled with CFR 0.152.
 */
package net.ederus.edm.rip;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public enum Rarity {
    COMUN("Comun", 0xB0B0B0, 1, 10),
    POCO_COMUN("Poco Comun", 5635962, 2, 20),
    RARO("Raro", 5220863, 3, 40),
    MITICO("Mitico", 11829247, 4, 75),
    LEGENDARIO("Legendario", 16758048, 5, 120),
    INMORTAL("Inmortal", 16723349, 6, 180);

    private final String display;
    private final TextColor color;
    private final int level;
    private final int cooldownSeconds;

    private Rarity(String display, int rgb, int level, int cooldownSeconds) {
        this.display = display;
        this.color = TextColor.color((int)rgb);
        this.level = level;
        this.cooldownSeconds = cooldownSeconds;
    }

    /*
     * Enfriamiento de fabrica de los efectos de esta calidad, en segundos.
     * Cuanto mas rara, mas se hace esperar. Se puede cambiar efecto por efecto
     * en config.yml.
     */
    public int cooldownSeconds() {
        return this.cooldownSeconds;
    }

    public String display() {
        return this.display;
    }

    public TextColor color() {
        return this.color;
    }

    public int level() {
        return this.level;
    }

    public Component bar() {
        TextComponent out = Component.empty();
        int max = Math.max(5, this.level);
        for (int i = 1; i <= max; ++i) {
            out = (TextComponent)out.append((Component)Component.text((String)(i <= this.level ? "\u25c6" : "\u25c7"), (TextColor)(i <= this.level ? this.color : TextColor.color((int)0x404040))));
        }
        return out.decoration(TextDecoration.ITALIC, false);
    }

    public Component label() {
        return this.bar().append((Component)Component.text((String)("  " + this.display.toUpperCase()), (TextColor)this.color, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD})).decoration(TextDecoration.ITALIC, false);
    }
}

