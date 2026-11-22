package de.texturemixer.gui;

import net.minecraft.client.gui.DrawContext;

/**
 * Selbst gezeichnete 16x16-Symbole für die Oberflächen-Reiter (Herzen, Hunger, Hotbar, ...),
 * damit das Symbol wirklich zur Kategorie passt (es gibt dafür keine passenden Minecraft-Items).
 * Gezeichnet wird nur mit Rechtecken, unabhängig von Packs.
 */
final class TabIcons {
    private TabIcons() {
    }

    private static final String[] HEART = {
            ".DD..DD.",
            "DRWDDRRD",
            "DRRRRRRD",
            "DRRRRRRD",
            ".DRRRRD.",
            "..DRRD..",
            "...DD...",
    };
    private static final int[] HEART_COLORS = {0xFF2A0508, 0xFFE8202A, 0xFFFFB4B8};

    private static final String[] DRUMSTICK = {
            "...DDDD.",
            "..DLBBBD",
            "..DBBBBD",
            "..DBBBBD",
            ".DWDBBD.",
            "DWWD.DD.",
            "DWD.....",
            ".D......",
    };
    private static final int[] DRUMSTICK_COLORS = {0xFF3B1F0B, 0xFFE09A4A, 0xFFB5651D, 0xFFF2EBDD};

    /** @return true, wenn für diese Kategorie ein eigenes Symbol gezeichnet wurde */
    static boolean draw(DrawContext c, String id, int x, int y) {
        switch (id) {
            case "hearts" -> pixels(c, x, y, HEART, "DRW", HEART_COLORS);
            case "hunger" -> pixels(c, x, y, DRUMSTICK, "DLBW", DRUMSTICK_COLORS);
            case "hotbar" -> hotbar(c, x, y);
            case "hotbar_selection" -> selection(c, x, y);
            case "inventory" -> inventory(c, x, y);
            case "xp_bar" -> xpBar(c, x, y);
            case "crosshair" -> crosshair(c, x, y);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Pixelbild mit Faktor 2, mittig im 16x16-Feld. */
    private static void pixels(DrawContext c, int x, int y, String[] rows, String keys, int[] colors) {
        int w = rows[0].length() * 2;
        int h = rows.length * 2;
        int ox = x + (16 - w) / 2;
        int oy = y + (16 - h) / 2;
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            int col = 0;
            while (col < row.length()) {
                char ch = row.charAt(col);
                int end = col;
                while (end < row.length() && row.charAt(end) == ch) end++;
                int k = keys.indexOf(ch);
                if (k >= 0) {
                    c.fill(ox + col * 2, oy + r * 2, ox + end * 2, oy + r * 2 + 2, colors[k]);
                }
                col = end;
            }
        }
    }

    private static void frame(DrawContext c, int x1, int y1, int x2, int y2, int t, int color) {
        c.fill(x1, y1, x2, y1 + t, color);
        c.fill(x1, y2 - t, x2, y2, color);
        c.fill(x1, y1 + t, x1 + t, y2 - t, color);
        c.fill(x2 - t, y1 + t, x2, y2 - t, color);
    }

    /** Leiste mit drei Slots. */
    private static void hotbar(DrawContext c, int x, int y) {
        c.fill(x, y + 4, x + 16, y + 12, 0xFF111111);
        c.fill(x + 1, y + 5, x + 15, y + 11, 0xFF2E2E2E);
        for (int i = 0; i < 3; i++) {
            int sx = x + 1 + i * 5;
            c.fill(sx, y + 6, sx + 4, y + 10, 0xFF5C5C5C);
        }
    }

    /** Heller Auswahlrahmen. */
    private static void selection(DrawContext c, int x, int y) {
        frame(c, x + 1, y + 1, x + 15, y + 15, 1, 0xFF111111);
        frame(c, x + 2, y + 2, x + 14, y + 14, 2, 0xFFFFFFFF);
    }

    /** Kleines Inventar-Fenster mit 3x3 Slots. */
    private static void inventory(DrawContext c, int x, int y) {
        int ox = x;
        int oy = y;
        c.fill(ox, oy, ox + 15, oy + 15, 0xFF111111);
        c.fill(ox + 1, oy + 1, ox + 14, oy + 14, 0xFFC6C6C6);
        c.fill(ox + 1, oy + 1, ox + 14, oy + 2, 0xFFFFFFFF);
        c.fill(ox + 1, oy + 1, ox + 2, oy + 14, 0xFFFFFFFF);
        c.fill(ox + 1, oy + 13, ox + 14, oy + 14, 0xFF555555);
        c.fill(ox + 13, oy + 1, ox + 14, oy + 14, 0xFF555555);
        for (int r = 0; r < 3; r++) {
            for (int q = 0; q < 3; q++) {
                int sx = ox + 3 + q * 4;
                int sy = oy + 3 + r * 4;
                c.fill(sx, sy, sx + 3, sy + 3, 0xFF8B8B8B);
            }
        }
    }

    /** Waagerechte Leiste, links grün gefüllt. */
    private static void xpBar(DrawContext c, int x, int y) {
        c.fill(x, y + 5, x + 16, y + 11, 0xFF111111);
        c.fill(x + 1, y + 6, x + 15, y + 10, 0xFF4A4A4A);
        c.fill(x + 1, y + 6, x + 10, y + 10, 0xFF63E020);
        c.fill(x + 1, y + 6, x + 10, y + 7, 0xFFB4FF7A);
    }

    /** Fadenkreuz-Plus mit dunklem Rand. */
    private static void crosshair(DrawContext c, int x, int y) {
        c.fill(x + 1, y + 6, x + 15, y + 10, 0xFF111111);
        c.fill(x + 6, y + 1, x + 10, y + 15, 0xFF111111);
        c.fill(x + 2, y + 7, x + 14, y + 9, 0xFFFFFFFF);
        c.fill(x + 7, y + 2, x + 9, y + 14, 0xFFFFFFFF);
    }
}
