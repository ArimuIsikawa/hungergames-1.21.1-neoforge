package ru.studhub.hungergames.reward;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.random.RandomGenerator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import ru.studhub.hungergames.config.ConfigManager;

public final class RewardService {
    private RewardService() {}

    public static JsonArray prepare(MinecraftServer server, ConfigManager.Snapshot config, int players, RandomGenerator random) {
        List<ItemStack> reward = new java.util.ArrayList<>(config.rewards().roll(random));
        var bonus = config.settings().bonus;
        double chance = bonus.chances.getOrDefault(players, ru.studhub.hungergames.event.EventRules.bonusChance(players));
        if (random.nextDouble() < chance) reward.add(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(bonus.item)), bonus.count));
        JsonArray encoded = new JsonArray();
        for (ItemStack stack : reward) encoded.add(ItemStack.CODEC.encodeStart(server.registryAccess()
                .createSerializationContext(JsonOps.INSTANCE), stack).getOrThrow());
        return encoded;
    }

    public static void give(ServerPlayer player, JsonArray reward) {
        if (reward == null) return;
        for (JsonElement element : reward) {
            ItemStack stack = ItemStack.CODEC.parse(player.registryAccess().createSerializationContext(JsonOps.INSTANCE), element).getOrThrow();
            if (!player.getInventory().add(stack) && !stack.isEmpty()) {
                throw new IllegalStateException("Не удалось поместить награду в инвентарь " + player.getGameProfile().getName());
            }
        }
        player.inventoryMenu.broadcastChanges();
    }
}
