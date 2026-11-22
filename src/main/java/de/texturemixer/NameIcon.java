package de.texturemixer;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Client-Seite: welche Spieler haben die Mod, und das Icon am Nametag. */
public final class NameIcon {
    private NameIcon() {
    }

    public static final String OFF = "off";
    public static final String BEFORE = "before";
    public static final String AFTER = "after";

    private static final Set<UUID> USERS = ConcurrentHashMap.newKeySet();
    private static Text icon;

    public static void setUsers(Iterable<UUID> users) {
        USERS.clear();
        for (UUID u : users) USERS.add(u);
    }

    public static void clear() {
        USERS.clear();
    }

    /** Nächster Zustand des Reglers: vor -> hinter -> aus -> vor. */
    public static String next(String mode) {
        if (BEFORE.equals(mode)) return AFTER;
        if (AFTER.equals(mode)) return OFF;
        return BEFORE;
    }

    public static String label(String mode) {
        if (BEFORE.equals(mode)) return "Icon: vor Name";
        if (AFTER.equals(mode)) return "Icon: hinter Name";
        return "Icon: aus";
    }

    private static Text icon() {
        if (icon == null) {
            String json = "{\"text\":\"\\uE000\",\"font\":\"texturemixer:icon\",\"color\":\"white\"}";
            icon = TextCodecs.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).result().orElse(Text.empty());
        }
        return icon;
    }

    private static boolean isModUser(UUID id) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.player.getUuid().equals(id)) return true; // ich selbst habe die Mod ja
        return USERS.contains(id);
    }

    /** Gibt den Namen mit Icon zurück (oder unverändert, wenn aus bzw. kein Mod-Spieler). */
    public static Text decorate(Text name, UUID player) {
        String mode = MixerConfig.get().nameIcon;
        if (mode == null || OFF.equals(mode) || !isModUser(player)) return name;
        if (name.getString().indexOf('\uE000') >= 0) return name; // schon dekoriert, nie doppelt
        Text sp = Text.literal(" ");
        if (BEFORE.equals(mode)) return Text.empty().append(icon()).append(sp).append(name);
        return Text.empty().append(name).append(sp).append(icon());
    }
}
