package ru.studhub.hungergames.test.mixin;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.GameProfileCache;
import net.neoforged.fml.loading.FMLPaths;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla GameTest omits the profile cache, which claims mods require even before tests start. */
@Mixin(MinecraftServer.class)
public abstract class TestProfileCacheMixin {
    @Unique private GameProfileCache hungergames$testCache;

    @Inject(method = "getProfileCache", at = @At("HEAD"), cancellable = true)
    private void hungergames$profileCache(CallbackInfoReturnable<GameProfileCache> callback) {
        if ((Object) this instanceof GameTestServer) {
            if (hungergames$testCache == null) {
                hungergames$testCache = new GameProfileCache((names, lookup) -> {
                    for (String name : names) lookup.onProfileLookupSucceeded(new GameProfile(
                            UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name));
                }, FMLPaths.GAMEDIR.get().resolve("test-usercache.json").toFile());
            }
            callback.setReturnValue(hungergames$testCache);
        }
    }
}
