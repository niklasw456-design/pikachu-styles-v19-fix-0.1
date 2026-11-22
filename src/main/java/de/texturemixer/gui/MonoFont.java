package de.texturemixer.gui;

import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Die Monospace-Schrift des Menüs (assets/texturemixer/font/mono.json + mono.ttf, DejaVu Sans Mono).
 * Das ist die einzige Stelle, die die Schrift-API von Minecraft anfasst: Alle Menü-Texte laufen über {@link #text(String)}.
 */
public final class MonoFont {
    public static final Identifier ID = Identifier.of("texturemixer", "mono");
    public static final Style STYLE = Style.EMPTY.withFont(new StyleSpriteSource.Font(ID));

    private MonoFont() {}

    public static Text text(String s) {
        return Text.literal(s).setStyle(STYLE);
    }
}
