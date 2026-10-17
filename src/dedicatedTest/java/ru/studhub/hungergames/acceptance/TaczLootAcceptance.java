package ru.studhub.hungergames.acceptance;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import ru.studhub.hungergames.HungerGames;
import ru.studhub.hungergames.arena.ArenaService;
import ru.studhub.hungergames.loot.LootPool;

/** Checks the deployed pool against the installed TaCZ API and the actual map's containers. */
final class TaczLootAcceptance {
    private static final Logger LOG = LogUtils.getLogger();
    private ArenaService scanning;
    private Method gunAdapter;
    private Method gunId;
    private Method gunMode;
    private Method ammoAdapter;
    private Method ammoMatches;
    private Method gunIndex;

    TaczLootAcceptance() {
        NeoForge.EVENT_BUS.addListener(this::commands);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::stop);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hg_loot_acceptance")
                .requires(source -> source.getEntity() == null && source.hasPermission(4)
                        && Boolean.getBoolean("hungergames.acceptance"))
                .executes(ctx -> {
                    try { run(ctx.getSource().getServer()); }
                    catch (Exception error) { LOG.error("HG_LOOT_ACCEPTANCE FAIL", error); }
                    return 1;
                }));
    }

    private void run(MinecraftServer server) throws Exception {
        check(scanning == null, "Loot acceptance is already running");
        check(server.getPlayerCount() == 0, "Disconnect real clients before loot acceptance");
        var controller = HungerGames.controller();
        controller.requireIdle();
        var gunApi = Class.forName("com.tacz.guns.api.item.IGun");
        var ammoApi = Class.forName("com.tacz.guns.api.item.IAmmo");
        gunAdapter = gunApi.getMethod("getIGunOrNull", ItemStack.class);
        gunId = gunApi.getMethod("getGunId", ItemStack.class);
        gunMode = gunApi.getMethod("getFireMode", ItemStack.class);
        ammoAdapter = ammoApi.getMethod("getIAmmoOrNull", ItemStack.class);
        ammoMatches = ammoApi.getMethod("isAmmoOfGun", ItemStack.class, ItemStack.class);
        gunIndex = Class.forName("com.tacz.guns.api.TimelessAPI").getMethod("getCommonGunIndex", ResourceLocation.class);
        var pool = LootPool.load(FMLPaths.CONFIGDIR.get().resolve("hungergames/chest_loot.json"), server.registryAccess());
        var random = new Random(821793);
        Set<String> gunIds = new HashSet<>();
        boolean armor = false, food = false, apple = false;
        for (var entry : pool.entries()) {
            check(!entry.stack().is(Items.ENCHANTED_GOLDEN_APPLE), "Enchanted golden apple was added");
            if (entry.stack().getItem() instanceof net.minecraft.world.item.ArmorItem) armor = true;
            if (entry.stack().has(net.minecraft.core.component.DataComponents.FOOD)) food = true;
            if (entry.stack().is(Items.GOLDEN_APPLE)) apple = true;
            var selected = new LootPool(1, 1, List.of(entry)).roll(random);
            verify(selected);
            Object gun = gunAdapter.invoke(null, entry.stack());
            if (gun != null) {
                gunIds.add(gunId.invoke(gun, entry.stack()).toString());
                check(!entry.companions().isEmpty(), "Gun has no guaranteed ammunition companion");
            }
        }
        check(gunIds.size() == 28 && armor && food && apple, "Expected preset with 28 guns, armor, food and ordinary golden apples");
        for (int i = 0; i < 10_000; i++) {
            var loot = pool.roll(random);
            check(loot.size() <= 27, "Complete loot selection does not fit in a chest");
            verify(loot);
        }
        LOG.info("HG_LOOT_ACCEPTANCE verified 28 registered guns, their native fire modes, matching guaranteed ammo, and 10000 selections");
        var service = new ArenaService(server);
        scanning = service;
        try {
            service.scan(controller.settings(), pool, random, error -> {
                try {
                    check(error == null, "Arena fill failed: " + error);
                    var index = JsonParser.parseString(Files.readString(server.getWorldPath(LevelResource.ROOT)
                            .resolve("data/hungergames/chests.json"))).getAsJsonArray();
                    check(index.size() == 44, "Expected all 44 actual map containers");
                    int guns = 0;
                    var level = service.level(controller.settings());
                    for (var element : index) {
                        var pos = element.getAsJsonObject();
                        var chest = ArenaService.container(level, new BlockPos(pos.get("x").getAsInt(), pos.get("y").getAsInt(), pos.get("z").getAsInt()));
                        check(chest != null, "Map container could not be opened");
                        List<ItemStack> loot = new ArrayList<>();
                        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                            var stack = chest.getItem(slot);
                            if (!stack.isEmpty()) loot.add(stack);
                        }
                        check(!loot.isEmpty(), "Map container was not filled");
                        guns += verify(loot);
                    }
                    LOG.info("HG_LOOT_ACCEPTANCE PASS: 28 gun entries, 10000 selections, 44 actual containers, {} spawned guns with matching ammo, no Barrett/AWM", guns);
                } catch (Exception failure) { LOG.error("HG_LOOT_ACCEPTANCE FAIL", failure); }
                finally { service.release(); scanning = null; }
            });
        } catch (Exception failure) {
            service.release(); scanning = null;
            throw failure;
        }
    }

    private int verify(List<ItemStack> loot) throws Exception {
        int guns = 0;
        for (ItemStack stack : loot) {
            check(!stack.is(Items.ENCHANTED_GOLDEN_APPLE), "Enchanted golden apple found");
            Object gun = gunAdapter.invoke(null, stack);
            if (gun == null) continue;
            guns++;
            ResourceLocation id = (ResourceLocation) gunId.invoke(gun, stack);
            String name = id.getPath().toLowerCase(java.util.Locale.ROOT);
            check(!Set.of("m95", "m107", "ai_awp", "awm").contains(name)
                    && !name.contains("barrett") && !name.contains("barret"), "Forbidden gun: " + id);
            var index = (Optional<?>) gunIndex.invoke(null, id);
            check(index.isPresent(), "Gun is missing from the installed pack: " + id);
            Object data = index.get().getClass().getMethod("getGunData").invoke(index.get());
            var modes = (List<?>) data.getClass().getMethod("getFireModeSet").invoke(data);
            check(modes.contains(gunMode.invoke(gun, stack)), "Gun has invalid fire mode: " + id);
            boolean hasAmmo = false;
            for (ItemStack companion : loot) {
                Object ammo = ammoAdapter.invoke(null, companion);
                // TaCZ's API takes (gunStack, ammoStack), even on the ammo adapter.
                if (ammo != null && (boolean) ammoMatches.invoke(ammo, stack, companion) && companion.getCount() > 0) {
                    hasAmmo = true;
                    break;
                }
            }
            check(hasAmmo, "Gun has no matching ammunition in the same container: " + id);
        }
        return guns;
    }

    private void tick(ServerTickEvent.Post event) {
        if (scanning == null) return;
        try { scanning.tick(); }
        catch (Exception error) {
            LOG.error("HG_LOOT_ACCEPTANCE FAIL", error);
            scanning.release(); scanning = null;
        }
    }

    private void stop(ServerStoppingEvent event) {
        if (scanning != null) { scanning.release(); scanning = null; }
    }
}
