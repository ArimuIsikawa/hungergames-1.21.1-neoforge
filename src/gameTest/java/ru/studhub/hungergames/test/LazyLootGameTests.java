package ru.studhub.hungergames.test;

import java.nio.file.Files;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ru.studhub.hungergames.arena.ArenaService;
import ru.studhub.hungergames.arena.LazyArenaLoot;
import ru.studhub.hungergames.config.HgSettings;
import ru.studhub.hungergames.loot.LootPool;
import ru.studhub.hungergames.player.Position;

@GameTestHolder("hungergames")
@PrefixGameTestTemplate(false)
public final class LazyLootGameTests {
    private static HgSettings fixture(ServerLevel level, BlockPos... positions) {
        for (BlockPos pos : positions) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        }
        BlockPos origin = positions[0];
        level.setBlock(origin.south(3).below(), Blocks.STONE.defaultBlockState(), 3);
        HgSettings settings = new HgSettings();
        settings.arenaDimension = level.dimension().location().toString();
        settings.corner1 = new HgSettings.Corner(origin.getX() - 4, 79, origin.getZ() - 4);
        settings.corner2 = new HgSettings.Corner(origin.getX() + 40, 90, origin.getZ() + 12);
        settings.lobby = new Position(settings.arenaDimension, origin.getX() + .5, 80, origin.getZ() + 3.5, 0, 0);
        return settings;
    }

    private static LootPool pool(GameTestHelper helper, String item) throws Exception {
        var path = Files.createTempFile("hg-lazy-test-", ".json");
        try {
            Files.writeString(path, """
                    {"schemaVersion":1,"minRolls":1,"maxRolls":1,"entries":[
                     {"stack":{"id":"minecraft:%s","count":1},"weight":1,"minCount":1,"maxCount":1}]}
                    """.formatted(item));
            return LootPool.load(path, helper.getLevel().registryAccess());
        } finally { Files.deleteIfExists(path); }
    }

    private static LazyArenaLoot service(GameTestHelper helper) {
        return new LazyArenaLoot(error -> helper.fail(error));
    }

    @GameTest(template = "empty")
    public static void untouchedChestsSurviveRounds(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos touched = new BlockPos(40004, 80, 40004), untouched = touched.east(4);
        HgSettings settings = fixture(level, touched, untouched);
        LazyArenaLoot loot = service(helper);
        helper.assertTrue(ArenaService.container(level, untouched).isEmpty(), "Loot generated before the match");
        loot.begin(level, settings, pool(helper, "diamond"), "first", new Random(1));
        loot.prepare(touched);
        loot.prepare(untouched);
        loot.markOpened(touched);
        ArenaService.container(level, touched).clearContent();
        loot.prepare(touched);
        helper.assertTrue(ArenaService.container(level, touched).isEmpty(), "Opened chest refilled within one match");
        loot.end();
        helper.assertTrue(!level.getBlockEntity(touched).getPersistentData().getBoolean(LazyArenaLoot.READY), "Opened flag did not reset");
        helper.assertTrue(level.getBlockEntity(untouched).getPersistentData().getBoolean(LazyArenaLoot.READY), "Untouched flag was reset");
        loot.begin(level, settings, pool(helper, "gold_ingot"), "second", new Random(2));
        loot.prepare(touched);
        loot.prepare(untouched);
        helper.assertTrue(ArenaService.container(level, touched).countItem(Items.GOLD_INGOT) == 1, "Opened chest missed next round's loot");
        helper.assertTrue(ArenaService.container(level, untouched).countItem(Items.DIAMOND) == 1, "Untouched chest was rerolled");
        helper.assertTrue(loot.filledCount() == 1, "Ready chest was regenerated");
        loot.end();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void largeArenaQueueAndOldDrops(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos first = new BlockPos(50004, 80, 50004), second = first.east(3), third = first.east(6);
        HgSettings settings = fixture(level, first, second, third);
        settings.corner2 = new HgSettings.Corner(settings.corner1.x() + 5999, 200, settings.corner1.z() + 5999);
        settings.containersPerTick = 1;
        settings.lootTimeBudgetMillis = 20;
        new ArenaService(level.getServer()).validate(settings, 0);
        LazyArenaLoot loot = service(helper);
        level.setChunkForced(first.getX() >> 4, first.getZ() >> 4, true);
        loot.begin(level, settings, pool(helper, "diamond"), "large", new Random(3));
        loot.loaded(level, new net.minecraft.world.level.ChunkPos(first));
        ItemEntity old = new ItemEntity(level, first.getX() + .5, 81, first.getZ() + .5, new ItemStack(Items.DIRT));
        ItemEntity fresh = new ItemEntity(level, first.getX() + .5, 81, first.getZ() + .5, new ItemStack(Items.EMERALD));
        loot.entityJoined(fresh, false);
        level.addFreshEntity(old);
        level.addFreshEntity(fresh);
        loot.tick();
        helper.assertTrue(loot.filledCount() <= 1, "Per-tick container limit was exceeded");
        helper.assertTrue(loot.pendingCount() < 100, "Lazy preparation enqueued the whole 6000-block arena");
        BlockPos distant = new BlockPos(settings.corner2.x() - 8, 80, settings.corner2.z() - 8);
        helper.assertTrue(!loot.prepare(distant) && level.getChunkSource().getChunkNow(distant.getX() >> 4, distant.getZ() >> 4) == null,
                "Lazy preparation forced an unvisited chunk to load");
        helper.onEachTick(loot::tick);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(loot.filledCount() == 3, "Queued chests were not filled"))
                .thenExecute(() -> {
                    helper.assertTrue(old.isRemoved() && !fresh.isRemoved(), "Chunk cleanup removed current loot or retained old loot");
                    helper.assertTrue(loot.entityJoined(fresh, true) && !loot.entityJoined(old, true), "Disk-loaded drops were not checked by match");
                    loot.end();
                    fresh.discard();
                    level.setChunkForced(first.getX() >> 4, first.getZ() >> 4, false);
                }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void doubleChestUnloadAndRestart(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos left = new BlockPos(60015, 80, 60004), right = left.east(), untouched = left.west(5);
        HgSettings settings = fixture(level, left, right, untouched);
        settings.corner1 = new HgSettings.Corner(60000, 79, 60000);
        level.setBlock(left, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT), 3);
        level.setBlock(right, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT), 3);
        LootPool diamonds = pool(helper, "diamond"), gold = pool(helper, "gold_ingot");
        LazyArenaLoot loot = service(helper);
        loot.begin(level, settings, diamonds, "unload-first", new Random(4));
        loot.prepare(left);
        loot.prepare(right);
        loot.prepare(untouched);
        helper.assertTrue(loot.filledCount() == 2 && ArenaService.container(level, right).countItem(Items.DIAMOND) == 1,
                "Double chest was filled twice through opposite halves");
        loot.markOpened(right);
        ArenaService.container(level, left).clearContent();
        int[] stage = {0};
        helper.onEachTick(() -> {
            if (level.getChunkSource().getChunkNow(left.getX() >> 4, left.getZ() >> 4) != null
                    || level.getChunkSource().getChunkNow(right.getX() >> 4, right.getZ() >> 4) != null) return;
            level.getChunkAt(left);
            level.getChunkAt(right);
            if (stage[0] == 0) {
                loot.prepare(right);
                helper.assertTrue(ArenaService.container(level, left).isEmpty(), "Reload duplicated looted double chest");
                helper.assertTrue(ArenaService.container(level, untouched).countItem(Items.DIAMOND) == 1, "Unload lost untouched loot");
                stage[0] = 1;
            } else {
                // Simulate a restart without an end call; the old opening marker is the deferred reset.
                String oldMarker = level.getBlockEntity(left).getPersistentData().getString(LazyArenaLoot.OPENED_EVENT);
                helper.assertTrue(oldMarker.equals("unload-first"), "Opened marker did not persist in the chunk");
                LazyArenaLoot restarted = service(helper);
                restarted.begin(level, settings, gold, "unload-second", new Random(5));
                restarted.prepare(right);
                restarted.prepare(untouched);
                helper.assertTrue(ArenaService.container(level, left).countItem(Items.GOLD_INGOT) == 1, "Deferred reset missed unloaded double chest");
                helper.assertTrue(ArenaService.container(level, untouched).countItem(Items.DIAMOND) == 1, "Restart rerolled untouched chest");
                helper.assertTrue(restarted.filledCount() == 1, "Restart refilled untouched or duplicate containers");
                restarted.end();
                loot.end();
                helper.succeed();
            }
        });
    }
}
