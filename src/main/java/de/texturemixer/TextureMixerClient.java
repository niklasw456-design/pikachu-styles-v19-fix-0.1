package de.texturemixer;

import de.texturemixer.gui.MixerScreen;
import net.fabricmc.api.ClientModInitializer;
import de.texturemixer.net.Net;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TextureMixerClient implements ClientModInitializer {
    public static final String MOD_ID = "texturemixer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private boolean wasDown = false;

    @Override
    public void onInitializeClient() {
        MixerConfig.load();
        CrosshairHud.register();
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        // Mod-Erkennung: beim Beitritt dem Server melden, Liste der Mod-Spieler empfangen
        ClientPlayNetworking.registerGlobalReceiver(Net.UsersS2C.ID,
                (payload, context) -> NameIcon.setUsers(payload.users()));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (ClientPlayNetworking.canSend(Net.HelloC2S.ID)) ClientPlayNetworking.send(Net.HelloC2S.INSTANCE);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> NameIcon.clear());
    }

    private void onTick(MinecraftClient client) {
        int key = MixerConfig.get().openKey;
        if (key < 0 || client.getWindow() == null) {
            wasDown = false;
            return;
        }
        boolean down = GLFW.glfwGetKey(client.getWindow().getHandle(), key) == GLFW.GLFW_PRESS;
        if (down && !wasDown && client.currentScreen == null && client.player != null) {
            client.setScreen(new MixerScreen(null));
        }
        wasDown = down;
    }
}
