package ru.studhub.hungergames.test;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ru.studhub.hungergames.HungerGames;
import ru.studhub.hungergames.arena.ArenaService;
import ru.studhub.hungergames.config.HgSettings;
import ru.studhub.hungergames.event.EventController;
import ru.studhub.hungergames.player.Position;
import ru.studhub.hungergames.storage.JsonFiles;
import ru.studhub.hungergames.compat.CuriosInventory;
import ru.studhub.hungergames.player.PlayerInventory;
import ru.studhub.hungergames.player.PlayerRecord;
import ru.studhub.hungergames.storage.EventJournal;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.world.level.GameType;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import io.netty.channel.embedded.EmbeddedChannel;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

@GameTestHolder("hungergames")
@PrefixGameTestTemplate(false)
public final class EventGameTests {
    private static final BlockPos CHEST = new BlockPos(0, 80, 0);

    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void eventLifecycle(GameTestHelper helper) throws Exception {
        EventController controller = HungerGames.controller();
        helper.assertTrue(controller != null, "Controller did not start");
        ServerLevel arena = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("hungergames:arena")));
        helper.assertTrue(arena != null, "Server-only arena dimension is missing");
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) arena.setBlock(new BlockPos(x, 79, z), Blocks.STONE.defaultBlockState(), 3);
        arena.setBlock(CHEST, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT).setValue(ChestBlock.FACING, Direction.SOUTH), 3);
        arena.setBlock(CHEST.west(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.RIGHT).setValue(ChestBlock.FACING, Direction.SOUTH), 3);
        if (ModList.get().isLoaded("ironchest")) arena.setBlock(new BlockPos(0, 80, 3),
                BuiltInRegistries.BLOCK.get(ResourceLocation.parse("ironchest:iron_chest")).defaultBlockState(), 3);
        HgSettings settings = controller.settings();
        settings.corner1 = new HgSettings.Corner(-12, 79, -12);
        settings.corner2 = new HgSettings.Corner(12, 90, 12);
        settings.lobby = new Position("hungergames:arena", 0.5, 80, 5.5, 0, 0);
        settings.border.initialSize = 64;
        settings.border.finalSize = 8;
        settings.border.delaySeconds = 0;
        settings.border.shrinkSeconds = 60;
        // Keep real-clock scheduling out of deterministic lifecycle tests.
        settings.scheduleTime = "00:00";
        for (int i = 1; i <= 8; i++) settings.spawns.put(i, new Position("hungergames:arena", i + .5, 80, .5, 0, 0));
        controller.saveSettings();
        var directory = FMLPaths.CONFIGDIR.get().resolve("hungergames");
        String pool = "{\"schemaVersion\":1,\"minRolls\":1,\"maxRolls\":1,\"entries\":[{\"stack\":{\"id\":\"minecraft:diamond\",\"count\":1},\"weight\":1,\"minCount\":1,\"maxCount\":1}]}";
        JsonFiles.write(directory.resolve("chest_loot.json"), JsonParser.parseString(pool));
        JsonFiles.write(directory.resolve("rewards.json"), JsonParser.parseString(pool));
        controller.reload();
        // Command registration and zero-player administrative start.
        int result = helper.getLevel().getServer().getCommands().getDispatcher().execute("hg start_event",
                helper.getLevel().getServer().createCommandSourceStack());
        helper.assertTrue(result == 1 && controller.phase() == EventController.Phase.PREPARING, "Zero-player forced start was rejected");
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(controller.phase() == EventController.Phase.IDLE, "Empty match created a lingering event");
            solo(helper, controller, arena);
        });
    }

    private static ServerPlayer player(GameTestHelper helper) {
        var profile = new GameProfile(UUID.randomUUID(), "HG_" + UUID.randomUUID().toString().substring(0, 8));
        var cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.teleportTo(helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 1)).getX() + .5,
                helper.absolutePos(new BlockPos(1, 2, 1)).getY(), helper.absolutePos(new BlockPos(1, 2, 1)).getZ() + .5, 0, 0);
        // Model a regular player who has already received and put away ParCool's first-login guide.
        var guide = helper.getLevel().getServer().getAdvancements().get(ResourceLocation.parse("parcool:grant_parcool_guide"));
        if (guide != null) {
            for (String criterion : player.getAdvancements().getOrStartProgress(guide).getRemainingCriteria()) player.getAdvancements().award(guide, criterion);
        }
        player.getInventory().clearContent();
        player.hasChangedDimension();
        return player;
    }

    private static void solo(GameTestHelper helper, EventController controller, ServerLevel arena) {
        ServerPlayer player = player(helper);
        Position origin = Position.of(player);
        player.getInventory().setItem(0, new ItemStack(Items.DIRT));
        controller.open(false);
        boolean rejected = false;
        try { controller.invite(player); } catch (IllegalStateException e) { rejected = true; }
        helper.assertTrue(rejected, "Nonempty inventory was admitted");
        player.getInventory().clearContent();
        controller.invite(player);
        controller.start(true);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(controller.phase() == EventController.Phase.IDLE, "Solo forced match did not finish");
            helper.assertTrue(player.level().dimension().location().toString().equals(origin.dimension()), "Winner not returned");
            helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 1, "Winner did not receive main reward exactly once");
            controller.restore(player);
            helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 1, "Reward duplicated on repeated recovery");
            helper.getLevel().getServer().getPlayerList().remove(player);
            multiple(helper, controller, arena, 2);
        });
    }

    private static void multiple(GameTestHelper helper, EventController controller, ServerLevel arena, int count) {
        List<ServerPlayer> players = new ArrayList<>();
        controller.open(false);
        for (int i = 0; i < count; i++) {
            ServerPlayer player = player(helper);
            players.add(player);
            controller.invite(player);
        }
        double overworldBorder = helper.getLevel().getWorldBorder().getSize();
        if (count == 2) helper.getLevel().getServer().getCommands().performPrefixedCommand(
                players.getFirst().createCommandSourceStack().withPermission(2), "hg start_event");
        else controller.start(true);
        helper.runAfterDelay(6, () -> {
            helper.assertTrue(controller.phase() == EventController.Phase.BATTLE, "Forced " + count + "-player match did not begin");
            helper.assertTrue(players.get(0).canHarmPlayer(players.get(1)), "Arena PvP is blocked by global/team PvP rules");
            helper.assertTrue(!arena.setBlock(new BlockPos(2, 79, 2), Blocks.AIR.defaultBlockState(), 3), "Arena destruction was not blocked");
            helper.assertTrue(arena.getBlockState(new BlockPos(2, 79, 2)).is(Blocks.STONE), "Arena block was lost");
            helper.assertTrue(ArenaService.container(arena, CHEST).countItem(Items.DIAMOND) == 1, "Chest not refilled");
            if (ModList.get().isLoaded("ironchest")) {
                var iron = ArenaService.container(arena, new BlockPos(0, 80, 3));
                helper.assertTrue(iron != null && iron.countItem(Items.DIAMOND) == 1, "Iron Chest not refilled");
            }
            boolean rejected = false;
            try { controller.start(true); } catch (IllegalStateException e) { rejected = true; }
            helper.assertTrue(rejected && controller.phase() == EventController.Phase.BATTLE, "Repeated forced start interrupted active battle");
            arena.explode(null, -8.5, 80, -8.5, 2, net.minecraft.world.level.Level.ExplosionInteraction.BLOCK);
            helper.assertTrue(arena.getBlockState(new BlockPos(-9, 79, -9)).is(Blocks.STONE), "Explosion damaged arena");
            helper.assertTrue(helper.getLevel().getWorldBorder().getSize() == overworldBorder, "Overworld border was changed");
            ServerPlayer observer = player(helper);
            observer.getInventory().setItem(0, new ItemStack(Items.EMERALD, 17));
            controller.spectator(observer);
            controller.leave(observer);
            helper.assertTrue(observer.getInventory().countItem(Items.EMERALD) == 17, "Observer lost personal items");
            helper.getLevel().getServer().getPlayerList().remove(observer);
            players.getFirst().getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 2));
            if (count == 2) helper.getLevel().getServer().getPlayerList().remove(players.getFirst());
            else controller.leave(players.getFirst());
            helper.runAfterDelay(40, () -> {
                if (count == 2) {
                    helper.assertTrue(controller.phase() == EventController.Phase.IDLE, "Disconnect did not eliminate participant");
                    helper.assertTrue(players.get(1).getInventory().countItem(Items.DIAMOND) == 1, "Disconnect survivor not rewarded");
                } else {
                    helper.assertTrue(controller.phase() == EventController.Phase.BATTLE, "Leave ended match with two survivors");
                    controller.cancel(null);
                }
                for (ServerPlayer player : players) if (helper.getLevel().getServer().getPlayerList().getPlayer(player.getUUID()) != null) {
                    helper.assertTrue(player.serverLevel() == helper.getLevel(), "Participant not returned after match");
                    helper.getLevel().getServer().getPlayerList().remove(player);
                }
                if (count == 2) multiple(helper, controller, arena, 3);
                else eightPlayers(helper, controller, arena);
            });
        });
    }

    private static void eightPlayers(GameTestHelper helper, EventController controller, ServerLevel arena) {
        List<ServerPlayer> players = new ArrayList<>();
        controller.settings().countdownSeconds = 3;
        controller.settings().fullCountdownSeconds = 1;
        controller.open(false);
        for (int i = 0; i < 8; i++) {
            ServerPlayer player = player(helper);
            players.add(player);
            if (i == 0 && ModList.get().isLoaded("curios")) {
                Map<String, IItemHandlerModifiable> handlers = new LinkedHashMap<>();
                CuriosInventory.visitNamed(player, handlers::put);
                IItemHandlerModifiable cosmetic = handlers.get("hg_test:cosmetic");
                helper.assertTrue(cosmetic != null && cosmetic.getSlots() > 0, "Curios test slot was not loaded");
                cosmetic.setStackInSlot(0, new ItemStack(Items.DIRT));
                helper.assertFalse(PlayerInventory.empty(player), "Cosmetic accessory bypassed empty-inventory check");
                cosmetic.setStackInSlot(0, ItemStack.EMPTY);
            }
            controller.invite(player);
            if (i == 3) helper.assertTrue(controller.phase() == EventController.Phase.COUNTDOWN, "Four players did not start countdown");
        }
        helper.runAfterDelay(30, () -> {
            helper.assertTrue(controller.phase() == EventController.Phase.BATTLE, "Full lobby did not automatically start");
            // Embedded test connections have no client to acknowledge dimension teleports.
            players.forEach(ServerPlayer::hasChangedDimension);
            helper.assertTrue(players.getFirst().hurt(arena.damageSources().generic(), 1), "Start unexpectedly grants invulnerability");
            players.getFirst().getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 3));
            players.getFirst().hurt(arena.damageSources().genericKill(), Float.MAX_VALUE);
            helper.runAfterDelay(40, () -> {
                ServerPlayer dead = helper.getLevel().getServer().getPlayerList().getPlayer(players.getFirst().getUUID());
                helper.assertTrue(dead != null && dead.gameMode.getGameModeForPlayer() == GameType.SPECTATOR, "Death did not respawn player as spectator");
                helper.assertTrue(controller.phase() == EventController.Phase.BATTLE, "Death prematurely ended eight-player match");
                controller.leave(dead);
                for (int i = 1; i < 7; i++) controller.leave(players.get(i));
                helper.runAfterDelay(40, () -> {
                    ServerPlayer winner = players.get(7);
                    helper.assertTrue(controller.phase() == EventController.Phase.IDLE, "Eight-player match did not finish");
                    helper.assertTrue(winner.getInventory().countItem(Items.DIAMOND) == 1, "Main reward missing");
                    helper.assertTrue(winner.getInventory().countItem(Items.NETHER_STAR) == 1, "Guaranteed eight-player bonus missing");
                    for (ServerPlayer player : players) {
                        ServerPlayer current = helper.getLevel().getServer().getPlayerList().getPlayer(player.getUUID());
                        if (current != null) helper.getLevel().getServer().getPlayerList().remove(current);
                    }
                    recoverJournal(helper, arena);
                });
            });
        });
    }

    private static void recoverJournal(GameTestHelper helper, ServerLevel arena) {
        try {
            ServerPlayer observer = player(helper);
            observer.getInventory().setItem(0, new ItemStack(Items.EMERALD, 23));
            PlayerRecord saved = PlayerRecord.capture(observer, false);
            saved.role = PlayerRecord.Role.OBSERVER;
            EventJournal.Data data = new EventJournal.Data();
            data.players.put(saved.uuid, saved);
            var path = helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/hungergames/event.json");
            JsonFiles.write(path, data);
            PlayerInventory.clear(observer);
            observer.setGameMode(GameType.SPECTATOR);
            new Position("hungergames:arena", 2.5, 80, .5, 0, 0).teleport(observer);
            HungerGames.start(helper.getLevel().getServer());
            EventController restarted = HungerGames.controller();
            helper.assertTrue(restarted.record(observer).role == PlayerRecord.Role.RETURNING, "Restart did not schedule recovery");
            restarted.restore(observer);
            restarted.restore(observer);
            helper.assertTrue(observer.serverLevel() == helper.getLevel(), "Journal recovery left player in arena");
            helper.assertTrue(observer.getInventory().countItem(Items.EMERALD) == 23, "Journal recovery lost or duplicated spectator items");
            helper.getLevel().getServer().getPlayerList().remove(observer);
            helper.succeed();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
