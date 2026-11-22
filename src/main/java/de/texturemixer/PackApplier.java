package de.texturemixer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Erzeugt das Ausgabe-Pack aus der aktuellen Auswahl, aktiviert es ganz oben und lädt die Ressourcen neu. */
public final class PackApplier {
    private PackApplier() {}

    /** Ein Hintergrund-Thread, damit mehrere Anwendungen nacheinander laufen und nie gleichzeitig. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "texturemixer-apply");
        t.setDaemon(true);
        return t;
    });

    /**
     * Wie apply(), aber das Ausgabe-Pack wird im Hintergrund gebaut. Nur das Aktivieren und Neuladen
     * läuft danach im Spiel-Thread. So bleibt das Spiel beim Schließen des Menüs flüssig.
     */
    public static void applyAsync() {
        MinecraftClient client = MinecraftClient.getInstance();
        Map<String, String> selections = new HashMap<>(MixerConfig.get().selections);
        WORKER.execute(() -> {
            int copied;
            try {
                copied = PackScanner.buildOutput(selections, PackScanner.scan());
            } catch (IOException e) {
                TextureMixerClient.LOGGER.error("Ausgabe-Pack konnte nicht erstellt werden", e);
                return;
            } finally {
                PackScanner.closeCaches();
            }
            final int n = copied;
            client.execute(() -> activate(client, n));
        });
    }

    public static void apply() {
        MinecraftClient client = MinecraftClient.getInstance();
        int copied;
        try {
            copied = PackScanner.buildOutput(MixerConfig.get().selections, PackScanner.scan());
        } catch (IOException e) {
            TextureMixerClient.LOGGER.error("Ausgabe-Pack konnte nicht erstellt werden", e);
            return;
        } finally {
            PackScanner.closeCaches(); // geöffnete Zip-Dateien freigeben, das Spiel lädt sie gleich selbst
        }
        activate(client, copied);
    }

    private static void activate(MinecraftClient client, int copied) {
        ResourcePackManager manager = client.getResourcePackManager();
        manager.scanPacks();
        String id = "file/" + PackScanner.OUTPUT_NAME;

        List<String> before = new ArrayList<>(client.options.resourcePacks);
        manager.disable(id);
        if (copied > 0) manager.enable(id);

        // schreibt options.txt und lädt neu, falls sich die Liste geändert hat
        client.options.refreshResourcePacks(manager);
        if (before.equals(client.options.resourcePacks)) {
            client.reloadResources();
        }
    }
}
