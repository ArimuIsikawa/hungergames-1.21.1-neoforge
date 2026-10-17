package ru.studhub.hungergames.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLevel.class)
public interface ServerLevelDataAccess {
    @Mutable
    @Accessor("serverLevelData")
    void hungergames$setServerLevelData(ServerLevelData data);
}
