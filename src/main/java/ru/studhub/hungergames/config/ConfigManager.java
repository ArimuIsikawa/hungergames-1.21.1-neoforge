package ru.studhub.hungergames.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;
import ru.studhub.hungergames.loot.LootPool;
import ru.studhub.hungergames.storage.JsonFiles;

public final class ConfigManager {
    public final Path directory = FMLPaths.CONFIGDIR.get().resolve("hungergames");
    private Snapshot current;
    public record Snapshot(HgSettings settings, LootPool chests, LootPool rewards) {}

    public void reload(MinecraftServer server) throws IOException {
        createIfMissing("settings.json", new HgSettings());
        createIfMissing("chest_loot.json", LootPool.emptyExample(3, 7));
        createIfMissing("rewards.json", LootPool.emptyExample(1, 1));
        HgSettings settings = JsonFiles.read(directory.resolve("settings.json"), HgSettings.class);
        try {
            settings.validate();
            if (!BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(settings.bonus.item))
                    || settings.bonus.item.equals("minecraft:air")
                    || settings.bonus.count > new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(settings.bonus.item))).getMaxStackSize()) {
                throw new IllegalArgumentException("Unknown bonus item: " + settings.bonus.item);
            }
        } catch (RuntimeException e) {
            throw new IOException("settings.json: " + e.getMessage(), e);
        }
        LootPool chests = LootPool.load(directory.resolve("chest_loot.json"), server.registryAccess());
        LootPool rewards = LootPool.load(directory.resolve("rewards.json"), server.registryAccess());
        current = new Snapshot(settings, chests, rewards);
    }

    private void createIfMissing(String name, Object value) throws IOException {
        Path path = directory.resolve(name);
        if (!Files.exists(path)) JsonFiles.write(path, value);
    }

    public Snapshot snapshot() {
        if (current == null) throw new IllegalStateException("Конфигурация не загружена. Исправьте JSON и выполните /hg reload.");
        return current;
    }

    public void saveSettings() throws IOException {
        snapshot().settings.validate();
        JsonFiles.write(directory.resolve("settings.json"), snapshot().settings);
    }
}
