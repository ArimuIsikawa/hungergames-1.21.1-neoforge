package ru.studhub.hungergames.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.studhub.hungergames.HungerGames;

@Mixin(ServerPlayer.class)
public abstract class ArenaPlayerMixin {
    @Inject(method = "canHarmPlayer", at = @At("HEAD"), cancellable = true)
    private void hungergames$pvp(Player other, CallbackInfoReturnable<Boolean> callback) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        var controller = HungerGames.controller();
        if (other instanceof ServerPlayer target && controller != null && controller.protectedLevel(self.level())
                && self.level() == target.level() && controller.live(self) && controller.live(target)) callback.setReturnValue(true);
    }
}
