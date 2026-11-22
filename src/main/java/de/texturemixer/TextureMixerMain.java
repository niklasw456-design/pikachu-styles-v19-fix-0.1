package de.texturemixer;

import de.texturemixer.net.Net;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Läuft auf Client UND Server (auch im Einzelspieler/LAN). Der Server merkt sich, wer die Mod hat,
 * und schickt allen Mod-Spielern die Liste. Ohne die Mod auf dem Server sieht man nur das eigene Icon.
 */
public class TextureMixerMain implements ModInitializer {
    private static final Set<UUID> MOD_USERS = ConcurrentHashMap.newKeySet();

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(Net.HelloC2S.ID, Net.HelloC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(Net.UsersS2C.ID, Net.UsersS2C.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(Net.HelloC2S.ID, (payload, context) -> {
            MOD_USERS.add(context.player().getUuid());
            broadcast(context.server(), null);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity leaving = handler.getPlayer();
            if (MOD_USERS.remove(leaving.getUuid())) broadcast(server, leaving);
        });
    }

    private static void broadcast(MinecraftServer server, ServerPlayerEntity skip) {
        Net.UsersS2C packet = new Net.UsersS2C(new ArrayList<>(MOD_USERS));
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        for (ServerPlayerEntity p : players) {
            if (p == skip) continue;
            if (ServerPlayNetworking.canSend(p, Net.UsersS2C.ID)) ServerPlayNetworking.send(p, packet);
        }
    }
}
