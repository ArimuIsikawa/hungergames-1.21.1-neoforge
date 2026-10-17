package ru.studhub.hungergames.arena;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import ru.studhub.hungergames.mixin.LevelDataAccess;
import ru.studhub.hungergames.mixin.ServerLevelDataAccess;

/** Dimension-local data: vanilla normally shares difficulty, time and rules with the overworld. */
public final class ArenaEnvironment {
    private final MinecraftServer server;
    private ServerLevel level;
    private ArenaData data;

    public ArenaEnvironment(MinecraftServer server) { this.server = server; }

    public void configure(ServerLevel target) {
        if (level == target && data != null) return;
        close();
        if (target == null) return;
        ServerLevelData original = (ServerLevelData) target.getLevelData();
        // Also support controller reconstruction during recovery without nesting wrappers.
        if (original instanceof ArenaData previous) original = previous.original;
        level = target;
        data = new ArenaData(server.getWorldData(), original);
        install(level, data);
        tick();
    }

    private static void install(ServerLevel level, ServerLevelData data) {
        ((LevelDataAccess) level).hungergames$setLevelData(data);
        ((ServerLevelDataAccess) level).hungergames$setServerLevelData(data);
    }

    public void tick() {
        if (level == null) return;
        data.freezeRules();
        level.setRainLevel(0);
        level.setThunderLevel(0);
        // Existing mobs may have loaded before the controller was installed.
        List<Mob> mobs = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) if (entity instanceof Mob mob) mobs.add(mob);
        mobs.forEach(Entity::discard);
        for (ServerPlayer player : level.players()) sync(player);
    }

    public void sync(ServerPlayer player) {
        ServerLevel current = player.serverLevel();
        player.connection.send(new ClientboundChangeDifficultyPacket(current.getDifficulty(), current.getLevelData().isDifficultyLocked()));
        player.connection.send(new ClientboundSetTimePacket(current.getGameTime(), current.getDayTime(),
                current.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
        if (current == level) {
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0));
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0));
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0));
        }
    }

    public void close() {
        if (level != null && level.getLevelData() == data) install(level, data.original);
        level = null;
        data = null;
    }

    private static final class ArenaData extends DerivedLevelData {
        private final ServerLevelData original;
        private final GameRules rules;

        private ArenaData(WorldData world, ServerLevelData original) {
            super(world, original);
            this.original = original;
            rules = original.getGameRules().copy();
            freezeRules();
        }

        private void freezeRules() {
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        }

        @Override public GameRules getGameRules() { return rules; }
        @Override public Difficulty getDifficulty() { return Difficulty.PEACEFUL; }
        @Override public boolean isDifficultyLocked() { return true; }
        @Override public long getDayTime() { return 6000L; }
        @Override public void setDayTime(long time) {}
        @Override public float getDayTimeFraction() { return 0; }
        @Override public void setDayTimeFraction(float fraction) {}
        @Override public float getDayTimePerTick() { return 0; }
        @Override public void setDayTimePerTick(float rate) {}
        @Override public boolean isRaining() { return false; }
        @Override public boolean isThundering() { return false; }
    }
}
