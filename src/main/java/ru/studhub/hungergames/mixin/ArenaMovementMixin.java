package ru.studhub.hungergames.mixin;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.studhub.hungergames.HungerGames;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ArenaMovementMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void hungergames$holdSpawn(ServerboundMovePlayerPacket packet, CallbackInfo callback) {
        // The vanilla handler reschedules network packets onto the server thread first.
        if (!player.server.isSameThread()) return;
        var controller = HungerGames.controller();
        var spawn = controller == null ? null : controller.frozenPosition(player);
        if (spawn == null || !packet.hasPosition()) return;
        double x = packet.getX(spawn.x()), y = packet.getY(spawn.y()), z = packet.getZ(spawn.z());
        float yaw = packet.getYRot(player.getYRot()), pitch = packet.getXRot(player.getXRot());
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        if (spawn.vector().distanceToSqr(x, y, z) > 1.0E-8) {
            player.connection.teleport(spawn.x(), spawn.y(), spawn.z(), yaw, pitch);
            callback.cancel();
        }
    }
}
