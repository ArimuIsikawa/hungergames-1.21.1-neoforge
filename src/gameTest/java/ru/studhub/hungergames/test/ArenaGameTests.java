package ru.studhub.hungergames.test;

import java.nio.file.Files;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ru.studhub.hungergames.arena.ArenaService;
import ru.studhub.hungergames.config.HgSettings;
import ru.studhub.hungergames.loot.LootPool;
import ru.studhub.hungergames.player.Position;

@GameTestHolder("hungergames")
@PrefixGameTestTemplate(false)
public final class ArenaGameTests {
    @GameTest(template = "empty", timeoutTicks = 800)
    public static void streamingChunks(GameTestHelper helper) throws Exception {
        // Separate from both the event arena fixture and the GameTest area's forced chunks.
        ServerLevel level = helper.getLevel();
        BlockPos first = new BlockPos(20484, 80, 20484);
        BlockPos last = first.offset(16 * 23, 0, 0);
        BlockPos left = new BlockPos(20495, 80, 20488), right = left.east();
        for (BlockPos pos : new BlockPos[] {first, last, left, right, first.south(3)}) {
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
        level.setBlock(first, Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(last, Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(left, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.LEFT), 3);
        level.setBlock(right, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.RIGHT), 3);
        helper.assertTrue(ArenaService.container(level, left).getContainerSize() == 54, "Cross-chunk double chest fixture is invalid");

        HgSettings settings = new HgSettings();
        settings.arenaDimension = level.dimension().location().toString();
        settings.corner1 = new HgSettings.Corner(20480, 79, 20480);
        settings.corner2 = new HgSettings.Corner(20480 + 16 * 24 - 1, 90, 20495);
        settings.lobby = new Position(settings.arenaDimension, first.getX() + .5, 80, first.getZ() + 3.5, 0, 0);
        settings.chunksPerTick = 2;
        LootPool pool = pool(helper, 1), overflowingPool = pool(helper, 3);
        ArenaService service = new ArenaService(level.getServer());
        ChunkPos firstChunk = new ChunkPos(first), rightChunk = new ChunkPos(right);
        level.setChunkForced(firstChunk.x, firstChunk.z, true);
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<String> failure = new AtomicReference<>();
        int[] stage = {0};
        service.scan(settings, pool, new Random(4821), error -> {
            helper.assertTrue(error == null, "Streaming scan failed: " + error);
            helper.assertTrue(scanTickets(level) == 0, "Scan callback retained chunk tickets");
            completed.set(true);
        });
        helper.onEachTick(() -> {
            try {
                service.tick();
                helper.assertTrue(scanTickets(level) <= settings.chunksPerTick, "Scan accumulated tickets outside the current batch");
                if (stage[0] == 0 && completed.get()) {
                    helper.assertTrue(service.chestCount() == 3, "Cross-chunk double chest was counted more than once");
                    helper.assertTrue(hasTicket(level, firstChunk, TicketType.FORCED), "Scan removed the administrator's forced-chunk ticket");
                    level.setChunkForced(firstChunk.x, firstChunk.z, false);
                    stage[0] = 1;
                } else if (stage[0] == 1 && level.getChunkSource().getChunkNow(firstChunk.x, firstChunk.z) == null
                        && level.getChunkSource().getChunkNow(rightChunk.x, rightChunk.z) == null) {
                    // Loading again must read saved contents, including the half in the adjacent chunk.
                    for (BlockPos pos : new BlockPos[] {first, last, left}) {
                        level.getChunkAt(pos);
                        var contents = ArenaService.container(level, pos);
                        helper.assertTrue(contents.countItem(Items.BOW) == 1 && contents.countItem(Items.ARROW) == 12,
                                "Filled chest contents were lost or duplicated after unload/reload at " + pos);
                    }
                    verifyCancellation(helper, service, settings, pool, level);
                    level.setBlock(first, Blocks.HOPPER.defaultBlockState(), 3);
                    settings.containerBlocks = java.util.List.of("minecraft:hopper");
                    service.scan(settings, overflowingPool, new Random(18), failure::set);
                    stage[0] = 2;
                } else if (stage[0] == 2 && failure.get() != null) {
                    helper.assertTrue(failure.get().contains("Слишком много предметов"), "Unexpected fill failure: " + failure.get());
                    helper.assertTrue(!service.busy() && scanTickets(level) == 0, "Failed fill retained chunk tickets");
                    helper.succeed();
                }
            } catch (RuntimeException | Error error) {
                service.release();
                level.setChunkForced(firstChunk.x, firstChunk.z, false);
                throw error;
            }
        });
    }

    private static void verifyCancellation(GameTestHelper helper, ArenaService service, HgSettings settings,
                                           LootPool pool, ServerLevel level) {
        AtomicBoolean callback = new AtomicBoolean();
        service.scan(settings, pool, new Random(19), error -> callback.set(true));
        service.tick();
        helper.assertTrue(service.busy(), "Cancellation fixture completed too soon");
        service.release();
        helper.assertTrue(!service.busy() && scanTickets(level) == 0 && !callback.get(), "Cancelled scan retained tickets or called its callback");
    }

    private static LootPool pool(GameTestHelper helper, int rolls) throws Exception {
        var path = Files.createTempFile("hg-streaming-test-", ".json");
        try {
            Files.writeString(path, """
                    {"schemaVersion":1,"minRolls":%d,"maxRolls":%d,"entries":[
                     {"stack":{"id":"minecraft:bow","count":1},"weight":1,"minCount":1,"maxCount":1,
                      "companions":[{"stack":{"id":"minecraft:arrow","count":1},"minCount":12,"maxCount":12}]}]}
                    """.formatted(rolls, rolls));
            return LootPool.load(path, helper.getLevel().registryAccess());
        } finally { Files.deleteIfExists(path); }
    }

    private static Map<?, ?> tickets(ServerLevel level) {
        try {
            var field = DistanceManager.class.getDeclaredField("tickets");
            field.setAccessible(true);
            return (Map<?, ?>) field.get(level.getChunkSource().chunkMap.getDistanceManager());
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private static int scanTickets(ServerLevel level) {
        int count = 0;
        for (Object value : tickets(level).values()) for (Object ticket : (Iterable<?>) value) {
            if (((Ticket<?>) ticket).getType().toString().equals("hungergames_scan")) count++;
        }
        return count;
    }

    private static boolean hasTicket(ServerLevel level, ChunkPos pos, TicketType<?> type) {
        Object values = tickets(level).get(pos.toLong());
        if (values != null) for (Object ticket : (Iterable<?>) values) if (((Ticket<?>) ticket).getType() == type) return true;
        return false;
    }
}
