package de.texturemixer.render;

/**
 * Auflösung der berechneten 3D-Vorschaubilder in Pixeln pro Bild. Wird vom Menü passend zur Anzeigegröße
 * gesetzt (GUI-Maßstab), damit die Bilder 1:1 auf den Bildschirm kommen und glatt aussehen.
 */
public final class RenderRes {
    private static volatile int frame = 128;

    private RenderRes() {}

    public static int frame() {
        return frame;
    }

    public static void set(int pixels) {
        frame = Math.max(64, Math.min(192, pixels));
    }
}
