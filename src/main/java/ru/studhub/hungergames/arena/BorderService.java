package ru.studhub.hungergames.arena;

import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.border.WorldBorder;
import ru.studhub.hungergames.config.HgSettings;

/** Keeps the arena border independent from the overworld's broadcast listeners. */
public final class BorderService {
    private final WorldBorder border = new WorldBorder();
    private ServerLevel level;
    private HgSettings.Border settings;
    private long startedAt;
    private boolean shrinking;

    public void start(ServerLevel level, HgSettings.Border settings, long now) {
        this.level = level;
        this.settings = settings;
        startedAt = now;
        shrinking = false;
        border.setCenter(settings.centerX, settings.centerZ);
        border.setSize(settings.initialSize);
        border.setDamagePerBlock(settings.damagePerBlock);
        border.setDamageSafeZone(settings.safeZone);
        border.setWarningBlocks(settings.warningBlocks);
        update(now);
    }

    public void update(long now) {
        if (level == null) return;
        if (!shrinking && now - startedAt >= settings.delaySeconds) {
            border.lerpSizeBetween(settings.initialSize, settings.finalSize, settings.shrinkDurationMillis());
            shrinking = true;
        }
        border.tick();
        WorldBorder actual = level.getWorldBorder();
        actual.setCenter(border.getCenterX(), border.getCenterZ());
        actual.setSize(border.getSize());
        actual.setDamagePerBlock(settings.damagePerBlock);
        actual.setDamageSafeZone(settings.safeZone);
        actual.setWarningBlocks(settings.warningBlocks);
        for (ServerPlayer player : level.players()) sync(player);
    }

    public void sync(ServerPlayer player) {
        if (level != null && player.serverLevel() == level) player.connection.send(new ClientboundInitializeBorderPacket(border));
    }

    public void reset() {
        if (level == null) return;
        level.getWorldBorder().applySettings(level.getServer().overworld().getWorldBorder().createSettings());
        for (ServerPlayer player : level.players()) player.connection.send(new ClientboundInitializeBorderPacket(level.getWorldBorder()));
        level = null;
    }
}
