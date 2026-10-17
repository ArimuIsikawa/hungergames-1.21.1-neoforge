package ru.studhub.hungergames.mixin;

import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.studhub.hungergames.HungerGames;

@Mixin(FoodData.class)
public abstract class ArenaFoodDataMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getDifficulty()Lnet/minecraft/world/Difficulty;"))
    private Difficulty hungergames$normalHunger(Level level, Player player) {
        var controller = HungerGames.controller();
        return controller != null && controller.arenaLevel(level) ? Difficulty.NORMAL : level.getDifficulty();
    }
}
