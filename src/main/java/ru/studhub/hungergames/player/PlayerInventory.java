package ru.studhub.hungergames.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import ru.studhub.hungergames.compat.CuriosInventory;

public final class PlayerInventory {
    private PlayerInventory() {}

    public static String describe(ServerPlayer player) {
        List<String> occupied = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) occupied.add("inventory[" + slot + "]=" + stack);
        }
        if (!player.containerMenu.getCarried().isEmpty()) occupied.add("cursor=" + player.containerMenu.getCarried());
        for (int slot = 0; slot <= 4; slot++) if (player.inventoryMenu.getSlot(slot).hasItem()) occupied.add("craft[" + slot + "]=" + player.inventoryMenu.getSlot(slot).getItem());
        CuriosInventory.visitNamed(player, (name, handler) -> {
            for (int slot = 0; slot < handler.getSlots(); slot++) if (!handler.getStackInSlot(slot).isEmpty()) occupied.add(name + "[" + slot + "]=" + handler.getStackInSlot(slot));
        });
        return occupied.toString();
    }

    public static JsonObject snapshot(ServerPlayer player) {
        JsonObject snapshot = new JsonObject();
        JsonArray vanilla = new JsonArray();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            vanilla.add(ItemStack.OPTIONAL_CODEC.encodeStart(player.registryAccess().createSerializationContext(JsonOps.INSTANCE),
                    player.getInventory().getItem(i)).getOrThrow());
        }
        snapshot.add("vanilla", vanilla);
        JsonObject curios = new JsonObject();
        CuriosInventory.visitNamed(player, (name, handler) -> {
            JsonArray items = new JsonArray();
            for (int slot = 0; slot < handler.getSlots(); slot++) items.add(ItemStack.OPTIONAL_CODEC.encodeStart(
                    player.registryAccess().createSerializationContext(JsonOps.INSTANCE), handler.getStackInSlot(slot)).getOrThrow());
            curios.add(name, items);
        });
        snapshot.add("curios", curios);
        return snapshot;
    }

    public static void restoreSnapshot(ServerPlayer player, JsonObject snapshot) {
        if (snapshot == null) throw new IllegalStateException("Нет снимка вещей наблюдателя; журнал сохранён.");
        Map<String, IItemHandlerModifiable> handlers = new LinkedHashMap<>();
        CuriosInventory.visitNamed(player, handlers::put);
        List<ItemStack> vanilla = decode(player, snapshot.getAsJsonArray("vanilla"));
        Map<String, List<ItemStack>> accessories = new LinkedHashMap<>();
        for (var entry : snapshot.getAsJsonObject("curios").entrySet()) {
            List<ItemStack> items = decode(player, entry.getValue().getAsJsonArray());
            IItemHandlerModifiable handler = handlers.get(entry.getKey());
            if (handler == null || items.size() > handler.getSlots()) {
                if (items.stream().anyMatch(stack -> !stack.isEmpty())) throw new IllegalStateException("Изменились слоты Curios: " + entry.getKey());
            } else accessories.put(entry.getKey(), items);
        }
        if (vanilla.size() != player.getInventory().getContainerSize()) throw new IllegalStateException("Изменился размер инвентаря наблюдателя");
        clear(player);
        for (int slot = 0; slot < vanilla.size(); slot++) player.getInventory().setItem(slot, vanilla.get(slot));
        accessories.forEach((name, items) -> {
            for (int slot = 0; slot < items.size(); slot++) handlers.get(name).setStackInSlot(slot, items.get(slot));
        });
        player.inventoryMenu.broadcastChanges();
    }

    private static List<ItemStack> decode(ServerPlayer player, JsonArray items) {
        List<ItemStack> decoded = new ArrayList<>();
        for (JsonElement item : items) decoded.add(ItemStack.OPTIONAL_CODEC.parse(
                player.registryAccess().createSerializationContext(JsonOps.INSTANCE), item).getOrThrow());
        return decoded;
    }

    public static boolean empty(ServerPlayer player) {
        if (!player.getInventory().isEmpty() || !player.containerMenu.getCarried().isEmpty()
                || !player.inventoryMenu.getCarried().isEmpty()) return false;
        for (int slot = 1; slot <= 4; slot++) if (player.inventoryMenu.getSlot(slot).hasItem()) return false;
        boolean[] empty = {true};
        CuriosInventory.visit(player, handler -> {
            for (int i = 0; i < handler.getSlots(); i++) if (!handler.getStackInSlot(i).isEmpty()) empty[0] = false;
        });
        return empty[0];
    }

    public static void clear(ServerPlayer player) {
        player.closeContainer();
        player.getInventory().clearContent();
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        for (int slot = 0; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        drainCurios(player);
        player.inventoryMenu.broadcastChanges();
    }

    public static void drop(ServerPlayer player) {
        player.closeContainer();
        player.getInventory().dropAll();
        for (ItemStack stack : drainCurios(player)) player.drop(stack, true, false);
        player.inventoryMenu.broadcastChanges();
    }

    private static List<ItemStack> drainCurios(ServerPlayer player) {
        List<ItemStack> contents = new ArrayList<>();
        CuriosInventory.visit(player, handler -> {
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack stack = handler.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    contents.add(stack.copy());
                    handler.setStackInSlot(i, ItemStack.EMPTY);
                }
            }
        });
        return contents;
    }
}
