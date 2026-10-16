package ru.studhub.hungergames.player;

import com.google.gson.JsonArray;
import java.util.UUID;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.server.level.ServerPlayer;

public final class PlayerRecord {
    public enum Role { QUEUED, ALIVE, ELIMINATED, OBSERVER, RETURNING }

    public String uuid;
    public String name;
    public String token = UUID.randomUUID().toString();
    public Position origin;
    public String gameMode;
    public float health;
    public int food;
    public float saturation;
    public int experienceLevel;
    public float experienceProgress;
    public int totalExperience;
    public Role role;
    public boolean participant;
    public boolean enteredBattle;
    public JsonArray reward = new JsonArray();
    public JsonObject inventory;
    public JsonArray effects = new JsonArray();

    public static PlayerRecord capture(ServerPlayer player, boolean participant) {
        PlayerRecord record = new PlayerRecord();
        record.uuid = player.getStringUUID();
        record.name = player.getGameProfile().getName();
        record.origin = Position.of(player);
        record.gameMode = player.gameMode.getGameModeForPlayer().getName();
        record.health = player.getHealth();
        record.food = player.getFoodData().getFoodLevel();
        record.saturation = player.getFoodData().getSaturationLevel();
        record.experienceLevel = player.experienceLevel;
        record.experienceProgress = player.experienceProgress;
        record.totalExperience = player.totalExperience;
        record.participant = participant;
        for (MobEffectInstance effect : player.getActiveEffects()) record.effects.add(MobEffectInstance.CODEC
                .encodeStart(player.registryAccess().createSerializationContext(JsonOps.INSTANCE), effect).getOrThrow());
        if (!participant) record.inventory = PlayerInventory.snapshot(player);
        record.role = participant ? Role.QUEUED : Role.OBSERVER;
        return record;
    }
}
