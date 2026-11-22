package de.texturemixer.gui;

import de.texturemixer.MixerConfig;

/**
 * Gemeinsames Farbschema für alle Menüs: Hell und Dunkel (Gold bleibt der Akzent).
 * Beim Umschalten blendet jede Farbe weich zwischen beiden Paletten über ({@link #blend()}),
 * der Zustand wird in der Config gespeichert.
 *
 * Jeder Bildschirm ruft in render() einmal {@link #update()} auf, danach liefern die Farb-Methoden
 * den aktuellen (ggf. gerade überblendenden) Wert.
 */
public final class Theme {
    private Theme() {
    }

    /** 0 = hell, 1 = dunkel (animiert). */
    private static float t = -1f;
    private static long lastNs = 0;

    // ---------- Zustand ----------

    public static boolean dark() {
        return MixerConfig.get().darkMode;
    }

    public static void toggle() {
        MixerConfig cfg = MixerConfig.get();
        cfg.darkMode = !cfg.darkMode;
        cfg.save();
    }

    /** Aktueller Überblend-Wert: 0 = hell, 1 = dunkel. */
    public static float blend() {
        if (t < 0f) t = dark() ? 1f : 0f;
        return t;
    }

    /** Pro Bild einmal aufrufen: führt den Überblend-Wert bildratenunabhängig nach. */
    public static void update() {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        float target = dark() ? 1f : 0f;
        float cur = blend();
        cur += (target - cur) * (1f - (float) Math.exp(-dt * 9f));
        if (Math.abs(target - cur) < 0.004f) cur = target;
        t = cur;
    }

    // ---------- Farben ----------

    public static int bg() {
        return m(0xFAFFFFFF, 0xFA15140F);
    }

    public static int header() {
        return m(0xFFFFFFFF, 0xFF1C1A14);
    }

    public static int border() {
        return m(0xFFE3E0D4, 0xFF33302A);
    }

    public static int accent() {
        return 0xFFFFC400;
    }

    /** Gold-Fläche der Auswahl (hell: zartes Gelb, dunkel: tiefes Gold-Braun). */
    public static int accentFill() {
        return m(0xFFFFF0B8, 0xFF4B3C0C);
    }

    /** Text in Akzentfarbe (hell: Dunkelgold, dunkel: helles Gold). */
    public static int accentText() {
        return m(0xFF8A6200, 0xFFFFD54A);
    }

    /** Text auf einer vollen Gold-Fläche (immer dunkel). */
    public static int onAccent() {
        return 0xFF1C1B17;
    }

    public static int card() {
        return m(0xFFFFFFFF, 0xFF201E18);
    }

    public static int cardHover() {
        return m(0xFFFFF7DB, 0xFF2E2A1A);
    }

    public static int hoverBorder() {
        return m(0xFFE9C34A, 0xFF9A7C1E);
    }

    /** Hintergrund hinter Vorschaubildern. */
    public static int preview() {
        return m(0xFFEDEBE3, 0xFF2B2922);
    }

    public static int previewTop() {
        return m(0xFFF4F2EA, 0xFF34322A);
    }

    public static int previewBottom() {
        return m(0xFFDCD8CB, 0xFF211F19);
    }

    public static int text() {
        return m(0xFF1C1B17, 0xFFF2EFE4);
    }

    public static int muted() {
        return m(0xFF8C8872, 0xFF9C977F);
    }

    /** Gedämpfter Text für Platzhalter und leere Karten. */
    public static int faint() {
        return m(0xFF9A9684, 0xFF6C6858);
    }

    public static int danger() {
        return m(0xFFC0392B, 0xFFFF6F5E);
    }

    public static int dangerFill() {
        return m(0xFFFDE8E5, 0xFF47201B);
    }

    public static int dangerFillHover() {
        return m(0xFFF9CFCA, 0xFF63291F);
    }

    /** Eingabefelder (Suche, Namen). */
    public static int field() {
        return m(0xFFF6F4EC, 0xFF26241D);
    }

    public static int fieldFocus() {
        return m(0xFFFFFFFF, 0xFF302D23);
    }

    /** Normale Schaltfläche. */
    public static int button() {
        return m(0xFFFFFFFF, 0xFF292720);
    }

    public static int disabledFill() {
        return m(0xFFF4F2EA, 0xFF1B1A15);
    }

    public static int disabledText() {
        return m(0xFFB8B39C, 0xFF5B5849);
    }

    public static int scrollbar() {
        return m(0xFFD2CCB0, 0xFF5F5839);
    }

    /** Zeilen-Hover in Listen und die drei Punkte. */
    public static int rowHover() {
        return m(0xFFF1EDDA, 0xFF332F1F);
    }

    public static int soundBarIdle() {
        return m(0xFFCFC8A8, 0xFF5C573F);
    }

    public static int soundBarHover() {
        return m(0xFFE2B92C, 0xFFB38F1E);
    }

    /** Leichter Schleier über der Welt hinter dem Menü. */
    public static int scrim() {
        return m(0x30FFFFFF, 0x78000000);
    }

    /** Schimmer der Ladeplatzhalter. */
    public static int shimmer() {
        return m(0xFFFFFFFF, 0xFF5A5538);
    }

    /** Schatten unter Karten und Fenster. */
    public static int shadow() {
        return m(0xFF000000, 0xFF000000);
    }

    // ---------- Helfer ----------

    private static int m(int light, int dark) {
        return mix(light, dark, blend());
    }

    /** Mischt zwei ARGB-Farben (auch die Transparenz). */
    public static int mix(int c1, int c2, float f) {
        f = f < 0 ? 0 : Math.min(f, 1);
        if (f <= 0f) return c1;
        if (f >= 1f) return c2;
        int r = 0;
        for (int sh = 24; sh >= 0; sh -= 8) {
            int x = (c1 >>> sh) & 0xFF;
            int y = (c2 >>> sh) & 0xFF;
            r |= (Math.round(x + (y - x) * f) & 0xFF) << sh;
        }
        return r;
    }
}
