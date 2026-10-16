package ru.studhub.hungergames.storage;

import java.io.IOException;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

public final class PlayerSaveReceipt {
    private PlayerSaveReceipt() {}

    /** Vanilla logs save failures instead of throwing; verify the committed receipt before removing the journal. */
    public static void verify(MinecraftServer server, ServerPlayer player, String key, String token) {
        var path = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player.getStringUUID() + ".dat");
        try {
            var saved = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            if (!token.equals(saved.getCompound("NeoForgeData").getString(key))) {
                throw new IOException("Player save receipt was not committed");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Данные игрока не сохранены; запись возврата сохранена в журнале: " + player.getGameProfile().getName(), e);
        }
    }
}
