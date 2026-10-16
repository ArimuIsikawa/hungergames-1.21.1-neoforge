package ru.studhub.hungergames.player;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public record Position(String dimension, double x, double y, double z, float yaw, float pitch) {
    public static Position of(ServerPlayer player) {
        return new Position(player.level().dimension().location().toString(), player.getX(), player.getY(),
                player.getZ(), player.getYRot(), player.getXRot());
    }

    public ServerLevel level(MinecraftServer server) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)));
    }

    public BlockPos block() { return BlockPos.containing(x, y, z); }
    public Vec3 vector() { return new Vec3(x, y, z); }

    public void validate() {
        ResourceLocation.parse(dimension);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 29_999_984 || Math.abs(z) > 29_999_984
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Invalid position: " + this);
        }
    }

    public void teleport(ServerPlayer player) {
        ServerLevel target = level(player.server);
        if (target == null) throw new IllegalStateException("Измерение не найдено: " + dimension);
        player.stopRiding();
        var controller = ru.studhub.hungergames.HungerGames.controller();
        if (controller != null) controller.transfer(() -> player.teleportTo(target, x, y, z, yaw, pitch));
        else player.teleportTo(target, x, y, z, yaw, pitch);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
    }
}
