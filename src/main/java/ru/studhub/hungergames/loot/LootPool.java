package ru.studhub.hungergames.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import ru.studhub.hungergames.storage.JsonFiles;

public record LootPool(int minRolls, int maxRolls, List<Entry> entries) {
    public record Entry(ItemStack stack, int weight, int minCount, int maxCount) {}

    public static JsonObject emptyExample(int minRolls, int maxRolls) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        json.addProperty("minRolls", minRolls);
        json.addProperty("maxRolls", maxRolls);
        json.add("entries", new JsonArray());
        return json;
    }

    public static LootPool load(Path path, HolderLookup.Provider registries) throws IOException {
        try {
            JsonObject json = JsonFiles.read(path, JsonObject.class);
            if (json.get("schemaVersion").getAsInt() != 1) throw new IllegalArgumentException("Unsupported schemaVersion");
            int min = json.get("minRolls").getAsInt(), max = json.get("maxRolls").getAsInt();
            if (min < 1 || max < min || max > 27) throw new IllegalArgumentException("rolls must be between 1 and 27");
            List<Entry> entries = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("entries")) {
                JsonObject entry = element.getAsJsonObject();
                ItemStack stack = ItemStack.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), entry.get("stack"))
                        .getOrThrow(message -> new IllegalArgumentException("entries[" + entries.size() + "].stack: " + message));
                int weight = entry.get("weight").getAsInt();
                int low = entry.get("minCount").getAsInt(), high = entry.get("maxCount").getAsInt();
                if (stack.isEmpty() || weight < 1 || weight > 1_000_000 || low < 1 || high < low || high > stack.getMaxStackSize()) {
                    throw new IllegalArgumentException("Invalid weight/count at entries[" + entries.size() + "]");
                }
                entries.add(new Entry(stack.copyWithCount(1), weight, low, high));
            }
            return new LootPool(min, max, List.copyOf(entries));
        } catch (RuntimeException e) {
            throw new IOException(path.getFileName() + ": " + e.getMessage(), e);
        }
    }

    public List<ItemStack> roll(RandomGenerator random) {
        List<ItemStack> stacks = new ArrayList<>();
        int count = random.nextInt(minRolls, maxRolls + 1);
        for (int i = 0; i < count; i++) {
            Entry entry = WeightedPicker.pick(entries, Entry::weight, random);
            stacks.add(entry.stack.copyWithCount(random.nextInt(entry.minCount, entry.maxCount + 1)));
        }
        return stacks;
    }
}
