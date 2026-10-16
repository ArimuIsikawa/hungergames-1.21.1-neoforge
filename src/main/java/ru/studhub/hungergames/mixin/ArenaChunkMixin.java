package ru.studhub.hungergames.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import ru.studhub.hungergames.HungerGames;

@Mixin(LevelChunk.class)
public abstract class ArenaChunkMixin {
    @Shadow public abstract Level getLevel();

    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    private void hungergames$protect(BlockPos pos, BlockState state, boolean moving, CallbackInfoReturnable<BlockState> callback) {
        if (getLevel() instanceof ServerLevel && HungerGames.controller() != null
                && HungerGames.controller().protectedLevel(getLevel())) {
            var block = state.getBlock();
            if (getLevel().getBlockState(pos).getBlock() == block && (block instanceof DoorBlock || block instanceof TrapDoorBlock
                    || block instanceof ButtonBlock || block instanceof LeverBlock)) return;
            callback.setReturnValue(null);
        }
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"), cancellable = true)
    private void hungergames$protectContainer(BlockPos pos, CallbackInfo callback) {
        if (getLevel() instanceof ServerLevel && HungerGames.controller() != null
                && HungerGames.controller().protectedLevel(getLevel())) callback.cancel();
    }
}
