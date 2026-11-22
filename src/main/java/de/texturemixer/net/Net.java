package de.texturemixer.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Die zwei Pakete, mit denen Spieler mit Pikachu Styles einander erkennen. */
public final class Net {
    private Net() {
    }

    /** Client -> Server: "ich habe die Mod". */
    public record HelloC2S() implements CustomPayload {
        public static final CustomPayload.Id<HelloC2S> ID = new CustomPayload.Id<>(Identifier.of("texturemixer", "hello"));
        public static final HelloC2S INSTANCE = new HelloC2S();
        public static final PacketCodec<PacketByteBuf, HelloC2S> CODEC = PacketCodec.unit(INSTANCE);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> Client: alle Spieler (UUIDs), die gerade die Mod haben. */
    public record UsersS2C(List<UUID> users) implements CustomPayload {
        public static final CustomPayload.Id<UsersS2C> ID = new CustomPayload.Id<>(Identifier.of("texturemixer", "users"));
        public static final PacketCodec<PacketByteBuf, UsersS2C> CODEC = PacketCodec.of(
                (value, buf) -> {
                    buf.writeVarInt(value.users().size());
                    for (UUID u : value.users()) buf.writeUuid(u);
                },
                buf -> {
                    int n = Math.min(buf.readVarInt(), 4096);
                    List<UUID> l = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) l.add(buf.readUuid());
                    return new UsersS2C(l);
                });

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
