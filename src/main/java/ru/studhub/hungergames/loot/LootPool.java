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

public record LootPool(int minRolls, int maxRolls, List<Entry> entries, List<StackRoll> guaranteed, List<LootPool> pools) {
    public LootPool(int minRolls, int maxRolls, List<Entry> entries) {
        this(minRolls, maxRolls, entries, List.of(), List.of());
    }
    public record StackRoll(ItemStack stack, int minCount, int maxCount) {
        private ItemStack roll(RandomGenerator random) {
            return stack.copyWithCount(random.nextInt(minCount, maxCount + 1));
        }
    }

    public record Entry(ItemStack stack, int weight, int minCount, int maxCount, List<StackRoll> companions) {}

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
            return readPool(json, registries, 0);
        } catch (RuntimeException e) {
            throw new IOException(path.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static LootPool readPool(JsonObject json, HolderLookup.Provider registries, int depth) {
        if (depth > 8) throw new IllegalArgumentException("Too many nested pools");
        int min = json.get("minRolls").getAsInt(), max = json.get("maxRolls").getAsInt();
        if (min < 1 || max < min || max > 27) throw new IllegalArgumentException("rolls must be between 1 and 27");
        List<Entry> entries = new ArrayList<>();
        for (JsonElement element : json.has("entries") ? json.getAsJsonArray("entries") : new JsonArray()) {
            JsonObject entry = element.getAsJsonObject();
            String label = "entries[" + entries.size() + "]";
            StackRoll primary = readStack(entry, registries, label);
            int weight = entry.get("weight").getAsInt();
            if (weight < 1 || weight > 1_000_000) throw new IllegalArgumentException("Invalid weight at " + label);
            List<StackRoll> companions = new ArrayList<>();
            if (entry.has("companions")) {
                for (JsonElement companion : entry.getAsJsonArray("companions")) {
                    if (companions.size() >= 26) throw new IllegalArgumentException("Too many companions at " + label);
                    companions.add(readStack(companion.getAsJsonObject(), registries, label + ".companions[" + companions.size() + "]"));
                }
            }
            entries.add(new Entry(primary.stack, weight, primary.minCount, primary.maxCount, List.copyOf(companions)));
        }
        List<StackRoll> guaranteed = new ArrayList<>();
        if (json.has("guaranteed")) for (JsonElement item : json.getAsJsonArray("guaranteed")) {
            guaranteed.add(readStack(item.getAsJsonObject(), registries, "guaranteed[" + guaranteed.size() + "]"));
        }
        List<LootPool> pools = new ArrayList<>();
        if (json.has("pools")) for (JsonElement pool : json.getAsJsonArray("pools")) {
            pools.add(readPool(pool.getAsJsonObject(), registries, depth + 1));
        }
        LootPool result = new LootPool(min, max, List.copyOf(entries), List.copyOf(guaranteed), List.copyOf(pools));
        if (result.maxStacks() > 27) throw new IllegalArgumentException("Combined loot must not exceed 27 container slots");
        return result;
    }

    public boolean isEmpty() { return entries.isEmpty() && guaranteed.isEmpty() && pools.stream().allMatch(LootPool::isEmpty); }

    public int maxStacks() {
        return maxRolls * entries.stream().mapToInt(e -> 1 + e.companions.size()).max().orElse(0)
                + guaranteed.size() + pools.stream().mapToInt(LootPool::maxStacks).sum();
    }

    private static StackRoll readStack(JsonObject json, HolderLookup.Provider registries, String label) {
        ItemStack stack = ItemStack.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), json.get("stack"))
                .getOrThrow(message -> new IllegalArgumentException(label + ".stack: " + message));
        int low = json.get("minCount").getAsInt(), high = json.get("maxCount").getAsInt();
        if (stack.isEmpty() || low < 1 || high < low || high > stack.getMaxStackSize()) {
            throw new IllegalArgumentException("Invalid count at " + label);
        }
        return new StackRoll(stack.copyWithCount(1), low, high);
    }

    public List<ItemStack> roll(RandomGenerator random) {
        List<ItemStack> stacks = new ArrayList<>();
        for (StackRoll item : guaranteed) stacks.add(item.roll(random));
        int count = entries.isEmpty() ? 0 : random.nextInt(minRolls, maxRolls + 1);
        for (int i = 0; i < count; i++) {
            Entry entry = WeightedPicker.pick(entries, Entry::weight, random);
            stacks.add(entry.stack.copyWithCount(random.nextInt(entry.minCount, entry.maxCount + 1)));
            // Companions belong to this selection and never take part in an independent weighted roll.
            for (StackRoll companion : entry.companions) stacks.add(companion.roll(random));
        }
        for (LootPool pool : pools) stacks.addAll(pool.roll(random));
        return stacks;
    }
}
