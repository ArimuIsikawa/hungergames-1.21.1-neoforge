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
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.RangedWrapper;
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

    public static void give(ServerPlayer player, JsonArray... groups) {
        List<ItemStack> stacks = new java.util.ArrayList<>();
        for (JsonArray group : groups) {
            if (group == null) continue;
            for (JsonElement element : group) {
                stacks.add(ItemStack.CODEC.parse(player.registryAccess().createSerializationContext(JsonOps.INSTANCE), element).getOrThrow());
            }
        }
        var inventory = new RangedWrapper(new InvWrapper(player.getInventory()), 0, player.getInventory().items.size());
        boolean overflow = false;
        for (ItemStack stack : stacks) {
            // Inventory.add may consume overflow in creative mode or report success for a partial insertion.
            ItemStack remaining = ItemHandlerHelper.insertItemStacked(inventory, stack, false);
            if (!remaining.isEmpty()) {
                var dropped = player.drop(remaining, false, false);
                if (dropped == null) throw new IllegalStateException("Не удалось вернуть предметы " + player.getGameProfile().getName());
                dropped.setTarget(player.getUUID());
                dropped.setNoPickUpDelay();
                overflow = true;
            }
        }
        player.inventoryMenu.broadcastChanges();
        if (overflow) player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "[HG] Инвентарь заполнен. Часть возвращённых предметов лежит рядом с вами."));
    }
}
