package ru.studhub.hungergames.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.studhub.hungergames.HungerGames;

@Mixin(Entity.class)
public abstract class ArenaEntityMovementMixin {
    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void hungergames$freezeMovement(MoverType type, Vec3 delta, CallbackInfo callback) {
        if ((Object) this instanceof ServerPlayer player && HungerGames.controller() != null
                && HungerGames.controller().frozenPosition(player) != null) callback.cancel();
    }
}
