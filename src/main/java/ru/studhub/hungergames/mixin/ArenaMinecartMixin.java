package ru.studhub.hungergames.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.studhub.hungergames.HungerGames;

@Mixin(VehicleEntity.class)
public abstract class ArenaMinecartMixin {
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void hungergames$protectChestCart(DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback) {
        var controller = HungerGames.controller();
        if ((Object) this instanceof MinecartChest cart && controller != null && controller.protectedLevel(cart.level())) {
            callback.setReturnValue(false);
        }
    }
}
