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
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

@GameTestHolder("hungergames")
@PrefixGameTestTemplate(false)
public final class EventGameTests {
    private static final BlockPos CHEST = new BlockPos(0, 80, 0);
    private static final BlockPos BARREL = new BlockPos(-4, 80, 5);
    private static final BlockPos FURNACE = new BlockPos(0, 80, 7);
    private static final BlockPos CRAFTING = new BlockPos(0, 80, 9);
    private static MinecartChest cart;
    private static final BlockPos CONVERTED = new BlockPos(8, 80, 8);

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void eventLifecycle(GameTestHelper helper) throws Exception {
        EventController controller = HungerGames.controller();
        helper.assertTrue(controller != null, "Controller did not start");
        if (controller.phase() != EventController.Phase.IDLE) controller.cancel(null);
        ServerLevel arena = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("hungergames:arena")));
        helper.assertTrue(arena != null, "Server-only arena dimension is missing");
        verifyEnvironment(helper, arena);
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
            arena.setBlock(new BlockPos(x, 79, z), Blocks.STONE.defaultBlockState(), 3);
            // The isolated GameTest world is reused between runs.
            for (int y = 80; y <= 82; y++) arena.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        arena.setBlock(CHEST, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT).setValue(ChestBlock.FACING, Direction.SOUTH), 3);
        arena.setBlock(CHEST.west(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.RIGHT).setValue(ChestBlock.FACING, Direction.SOUTH), 3);
        arena.setBlock(BARREL, Blocks.BARREL.defaultBlockState(), 3);
        arena.setBlock(FURNACE, Blocks.FURNACE.defaultBlockState(), 3);
        arena.setBlock(CRAFTING, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        for (var entity : arena.getEntitiesOfClass(MinecartChest.class, new net.minecraft.world.phys.AABB(-12, 79, -12, 13, 91, 13))) entity.discard();
        cart = new MinecartChest(arena, 8.5, 80, 8.5);
        cart.setNoGravity(true);
        cart.setItem(0, new ItemStack(Items.DIRT, 64));
        ((net.minecraft.world.Container) arena.getBlockEntity(BARREL)).setItem(0, new ItemStack(Items.DIRT, 9));
        helper.assertTrue(arena.addFreshEntity(cart), "Could not add chest minecart fixture");
        if (ModList.get().isLoaded("ironchest")) arena.setBlock(new BlockPos(0, 80, 3),
                BuiltInRegistries.BLOCK.get(ResourceLocation.parse("ironchest:iron_chest")).defaultBlockState(), 3);
        HgSettings settings = controller.settings();
        settings.minPlayers = 16;
        settings.maxPlayers = 32;
        settings.bonus.chances.clear();
        settings.corner1 = new HgSettings.Corner(-12, 79, -12);
        settings.corner2 = new HgSettings.Corner(12, 90, 12);
        settings.lobby = new Position("hungergames:arena", 0.5, 80, 5.5, 0, 0);
        settings.border.initialSize = 64;
        settings.border.finalSize = 8;
        settings.border.delaySeconds = 0;
        settings.border.shrinkSeconds = 60;
        // Keep real-clock scheduling out of deterministic lifecycle tests.
        settings.scheduleTime = java.time.LocalTime.now(java.time.ZoneId.of(settings.scheduleZone))
                .plusHours(12).withSecond(0).withNano(0).toString();
        settings.spawns.clear();
        for (int i = 1; i <= 32; i++) settings.spawns.put(i,
                new Position("hungergames:arena", -9.5 + ((i - 1) % 8) * 3, 80, -9.5 + ((i - 1) / 8) * 3, 0, 0));
        controller.saveSettings();
        var directory = FMLPaths.CONFIGDIR.get().resolve("hungergames");
        String pool = "{\"schemaVersion\":1,\"minRolls\":1,\"maxRolls\":1,\"entries\":[{\"stack\":{\"id\":\"minecraft:diamond\",\"count\":1},\"weight\":1,\"minCount\":1,\"maxCount\":1}]}";
        JsonFiles.write(directory.resolve("chest_loot.json"), JsonParser.parseString(pool));
        JsonFiles.write(directory.resolve("rewards.json"), JsonParser.parseString(pool));
        controller.reload();
        controller.migrateContainers();
        helper.startSequence().thenWaitUntil(() -> {
                    boolean idle;
                    try { controller.requireIdle(); idle = true; }
                    catch (IllegalStateException waiting) { idle = false; }
                    helper.assertTrue(idle && arena.getEntity(cart.getUUID()) == null
                            && arena.getBlockState(CONVERTED).is(Blocks.CHEST), "Minecart conversion has not completed");
                })
                .thenExecute(() -> {
                    helper.assertTrue(ArenaService.container(arena, CONVERTED).countItem(Items.DIRT) == 64, "Migration lost minecart contents");
                    helper.assertTrue(ArenaService.container(arena, BARREL).isEmpty(), "Migration did not clear excluded barrels");
                    ArenaService.container(arena, BARREL).setItem(0, new ItemStack(Items.EMERALD, 17));
                    helper.assertTrue(!ArenaService.allowed(arena, BARREL, settings), "Barrels are included in event loot");
                    verifyCenterCommand(helper, controller, arena);
                    helper.getLevel().getServer().getCommands().performPrefixedCommand(
                            helper.getLevel().getServer().createCommandSourceStack(), "hg start_event");
                    helper.assertTrue(controller.phase() == EventController.Phase.PREPARING, "Zero-player forced start was rejected");
                    prepared(helper, controller, EventController.Phase.IDLE, () -> solo(helper, controller, arena));
                });
    }

    private static void prepared(GameTestHelper helper, EventController controller, EventController.Phase expected, Runnable action) {
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(controller.phase() == expected,
                "Waiting for phase " + expected + "; actual=" + controller.phase())).thenExecute(action);
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

    private static void verifyCenterCommand(GameTestHelper helper, EventController controller, ServerLevel arena) {
        ServerPlayer admin = player(helper);
        Position origin = Position.of(admin);
        admin.setGameMode(GameType.CREATIVE);
        admin.setYRot(135);
        admin.setXRot(25);
        admin.getInventory().setItem(0, new ItemStack(Items.EMERALD, 11));
        var dispatcher = admin.server.getCommands().getDispatcher();
        try {
            boolean denied = false;
            try { dispatcher.execute("hg tp_center", admin.createCommandSourceStack().withPermission(1)); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { denied = true; }
            helper.assertTrue(denied && admin.serverLevel() == helper.getLevel(), "Non-operator used tp_center");
            helper.assertTrue(dispatcher.execute("hg tp_center", admin.createCommandSourceStack().withPermission(2)) == 1,
                    "Operator could not use tp_center from another dimension");
            assertAtCenter(helper, controller, admin, arena);
            helper.assertTrue(admin.getYRot() == 135 && admin.getXRot() == 25, "Center teleport changed looking direction");
            new Position("hungergames:arena", 9.5, 80, 9.5, 135, 25).teleport(admin);
            dispatcher.execute("hg tp_center", admin.createCommandSourceStack().withPermission(2));
            assertAtCenter(helper, controller, admin, arena);
            controller.open(false);
            origin.teleport(admin);
            dispatcher.execute("hg tp_center", admin.createCommandSourceStack().withPermission(2));
            assertAtCenter(helper, controller, admin, arena);
            helper.assertTrue(controller.phase() == EventController.Phase.RECRUITING, "Admin teleport changed event phase");
            boolean returnDenied = false;
            try { dispatcher.execute("hg tp_world", admin.createCommandSourceStack().withPermission(1)); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { returnDenied = true; }
            helper.assertTrue(returnDenied && admin.serverLevel() == arena, "Non-operator used tp_world");
            helper.assertTrue(dispatcher.execute("hg tp_world", admin.createCommandSourceStack().withPermission(2)) == 1
                    && admin.serverLevel() == helper.getLevel(), "Return command did not reach the overworld during an event");
            dispatcher.execute("hg tp_center", admin.createCommandSourceStack().withPermission(2));
            var profile = admin.getGameProfile();
            admin.server.getPlayerList().remove(admin);
            admin = reconnect(helper, profile);
            helper.assertTrue(admin.serverLevel() == helper.getLevel(), "Login from saved arena position did not force overworld return");
            controller.cancel(null);
            helper.assertTrue(admin.getInventory().countItem(Items.EMERALD) == 11 && admin.gameMode.getGameModeForPlayer() == GameType.CREATIVE,
                    "Admin teleport changed inventory or game mode");
            helper.assertTrue(controller.record(admin) == null, "Admin teleport registered the player in the event");
            origin.teleport(admin);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) { throw new IllegalStateException(error); }
        finally { admin.server.getPlayerList().remove(admin); }
    }

    private static ServerPlayer reconnect(GameTestHelper helper, GameProfile profile) {
        var cookie = CommonListenerCookie.createInitial(profile, false);
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static void assertAtCenter(GameTestHelper helper, EventController controller, ServerPlayer player, ServerLevel arena) {
        helper.assertTrue(player.serverLevel() == arena && player.getX() == controller.settings().border.centerX
                && player.getZ() == controller.settings().border.centerZ, "Admin did not arrive at the arena center");
        helper.assertTrue(arena.noCollision(player.getBoundingBox()) && player.getY() < 90, "Center teleport chose an obstructed or unrelated surface");
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
        verifyLobbyTimer(helper, controller, player);
        controller.start(true);
        prepared(helper, controller, EventController.Phase.WARMUP, () -> {
            verifyFrozenSpawn(helper, controller, player);
            helper.runAfterDelay(80, () -> helper.assertTrue(controller.phase() == EventController.Phase.WARMUP,
                    "Warmup ended before five seconds"));
        });
        prepared(helper, controller, EventController.Phase.FINISHING, () -> {
            helper.assertTrue(player.serverLevel() == arena && player.getInventory().countItem(Items.DIAMOND) == 0,
                    "Winner was returned or paid before congratulations delay");
            controller.restore(player);
            helper.assertTrue(player.serverLevel() == arena, "Automatic recovery bypassed victory delay");
            helper.runAfterDelay(80, () -> helper.assertTrue(player.serverLevel() == arena && controller.phase() == EventController.Phase.FINISHING,
                    "Winner returned before five seconds"));
        });
        prepared(helper, controller, EventController.Phase.IDLE, () -> {
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
        prepared(helper, controller, EventController.Phase.BATTLE, () -> {
            helper.assertTrue(controller.phase() == EventController.Phase.BATTLE, "Forced " + count + "-player match did not begin");

            if (count == 2) {
                var original = players.get(0).position();
                players.get(0).move(net.minecraft.world.entity.MoverType.PLAYER, new Vec3(0, 0, 0.25));
                helper.assertTrue(controller.frozenPosition(players.get(0)) == null && players.get(0).position().distanceToSqr(original) > 0,
                        "Player remained frozen after battle started");
                verifyHunger(helper, players.get(0));
                verifyInteractions(helper, arena, players.get(0));
            }
            helper.assertTrue(players.get(0).canHarmPlayer(players.get(1)), "Arena PvP is blocked by global/team PvP rules");
            helper.assertTrue(!arena.setBlock(new BlockPos(2, 79, 2), Blocks.AIR.defaultBlockState(), 3), "Arena destruction was not blocked");
            helper.assertTrue(arena.getBlockState(new BlockPos(2, 79, 2)).is(Blocks.STONE), "Arena block was lost");
            helper.assertTrue(ArenaService.container(arena, CHEST).countItem(Items.DIAMOND) == 1, "Chest not refilled");
            helper.assertTrue(ArenaService.container(arena, CONVERTED).countItem(Items.DIAMOND) == 1, "Converted chest not filled");
            helper.assertTrue(ArenaService.container(arena, BARREL).countItem(Items.EMERALD) == 17, "Excluded barrel was refilled");
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
            helper.getLevel().getServer().getCommands().performPrefixedCommand(observer.createCommandSourceStack().withPermission(2), "hg tp_world");
            helper.assertTrue(observer.getInventory().countItem(Items.EMERALD) == 17, "Observer lost personal items");
            helper.assertTrue(observer.serverLevel() == helper.getLevel(), "Observer return command left player in arena");
            helper.getLevel().getServer().getPlayerList().remove(observer);
            players.getFirst().getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 2));
            if (count == 2) helper.getLevel().getServer().getPlayerList().remove(players.getFirst());
            else helper.getLevel().getServer().getCommands().performPrefixedCommand(
                    players.getFirst().createCommandSourceStack().withPermission(2), "hg tp_world");
            prepared(helper, controller, count == 2 ? EventController.Phase.IDLE : EventController.Phase.BATTLE, () -> {
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
                else thirtyTwoPlayers(helper, controller, arena);
            });
        });
    }

    private static void verifyEnvironment(GameTestHelper helper, ServerLevel arena) {
        helper.assertTrue(arena.getDifficulty() == Difficulty.PEACEFUL, "Arena difficulty is not peaceful");
        helper.assertTrue(arena.getDayTime() == 6000 && !arena.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT), "Arena sun is not frozen at noon");
        helper.assertTrue(!arena.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING), "Arena mob spawning is enabled");
        helper.assertTrue(arena.getGameRules() != helper.getLevel().getGameRules(), "Arena shares mutable game rules with overworld");
        helper.assertTrue(helper.getLevel().getGameRules().getBoolean(GameRules.RULE_DAYLIGHT), "Overworld daylight cycle was disabled");
        helper.assertTrue(helper.getLevel().getDifficulty() == helper.getLevel().getServer().getWorldData().getDifficulty(), "Overworld difficulty changed");
        var cow = EntityType.COW.create(arena);
        var zombie = EntityType.ZOMBIE.create(arena);
        helper.assertTrue(cow != null && !arena.addFreshEntity(cow), "Passive mobs can enter arena");
        helper.assertTrue(zombie != null && !arena.addFreshEntity(zombie), "Hostile mobs can enter arena");
        var stand = new ArmorStand(arena, 0, 80, 0);
        helper.assertTrue(arena.addFreshEntity(stand), "Map decoration was blocked with mobs");
        stand.discard();
    }

    private static void verifyLobbyTimer(GameTestHelper helper, EventController controller, ServerPlayer player) {
        try {
            var field = EventController.class.getDeclaredField("lobbyTimer");
            field.setAccessible(true);
            var timer = (net.minecraft.server.level.ServerBossEvent) field.get(controller);
            helper.assertTrue(timer.getPlayers().contains(player) && timer.getName().getString().contains("15:00"),
                    "Queued player did not receive recruitment timer");
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private static void verifyFrozenSpawn(GameTestHelper helper, EventController controller, ServerPlayer player) {
        try {
            var spawn = controller.frozenPosition(player);
            helper.assertTrue(spawn != null && !controller.live(player), "Warmup participant is already in combat");
            var original = player.position();
            player.move(net.minecraft.world.entity.MoverType.PLAYER, new Vec3(1, 1, 1));
            helper.assertTrue(player.position().distanceToSqr(original) < 1.0E-8, "Warmup physics movement was allowed");
            var teleport = ServerGamePacketListenerImpl.class.getDeclaredField("awaitingTeleport");
            teleport.setAccessible(true);
            player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(teleport.getInt(player.connection)));
            player.connection.handleMovePlayer(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                    spawn.x() + 2, spawn.y() + 1, spawn.z() + 2, 90, 25, true));
            helper.assertTrue(player.position().distanceToSqr(spawn.vector()) < 1.0E-8 && player.getYRot() == 90 && player.getXRot() == 25,
                    "Warmup packet either moved the player or blocked looking around");
            helper.assertTrue(!player.hurt(player.damageSources().generic(), 1), "Warmup player can take damage");
            player.server.getCommands().getDispatcher().execute("hg tp_center", player.createCommandSourceStack().withPermission(2));
            helper.assertTrue(player.getX() == controller.settings().border.centerX && player.getZ() == controller.settings().border.centerZ
                    && controller.frozenPosition(player).vector().distanceToSqr(player.position()) < 1.0E-8,
                    "Center teleport during warmup did not update the frozen position");
        } catch (ReflectiveOperationException | com.mojang.brigadier.exceptions.CommandSyntaxException error) { throw new IllegalStateException(error); }
    }

    private static void verifyHunger(GameTestHelper helper, ServerPlayer player) {
        player.setHealth(10);
        player.getFoodData().setFoodLevel(5);
        player.getFoodData().setSaturation(0);
        int originalTick = player.tickCount;
        for (int i = 0; i < 100; i++) {
            player.tickCount = originalTick + i;
            player.aiStep();
            player.getFoodData().tick(player);
        }
        helper.assertTrue(player.getHealth() == 10 && player.getFoodData().getSaturationLevel() == 0
                && player.getFoodData().getFoodLevel() == 5, "Peaceful difficulty gave free health or food in the arena");
        player.getFoodData().addExhaustion(5);
        player.getFoodData().tick(player);
        helper.assertTrue(player.getFoodData().getFoodLevel() == 4, "Arena hunger does not decrease from exhaustion");
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5);
        for (int i = 0; i < 10; i++) player.getFoodData().tick(player);
        helper.assertTrue(player.getHealth() > 10, "Food-based regeneration was disabled");
        player.tickCount = originalTick;
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5);
    }

    private static void verifyInteractions(GameTestHelper helper, ServerLevel arena, ServerPlayer player) {
        try {
            List<ItemStack> heldItems = new ArrayList<>(List.of(ItemStack.EMPTY));
            if (ModList.get().isLoaded("tacz")) {
                var gun = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("tacz:modern_kinetic_gun")));
                var tag = new CompoundTag();
                tag.putString("GunId", "tacz:m4a1");
                tag.putString("GunFireMode", "AUTO");
                gun.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                heldItems.add(gun);
            }
            for (ItemStack held : heldItems) for (BlockPos pos : List.of(CHEST, CONVERTED, BARREL, FURNACE, CRAFTING)) {
                player.teleportTo(arena, pos.getX() + .5, pos.getY(), pos.getZ() + 1.5, 0, 0);
                // Embedded connections have no client; acknowledge the teleport before using the real packet handler.
                var teleport = ServerGamePacketListenerImpl.class.getDeclaredField("awaitingTeleport");
                teleport.setAccessible(true);
                player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(teleport.getInt(player.connection)));
                player.setItemInHand(InteractionHand.MAIN_HAND, held.copy());
                player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false), 0));
                helper.assertTrue(player.containerMenu != player.inventoryMenu, "Block menu did not open: " + pos + ", held=" + held);
                if (pos.equals(CHEST) || pos.equals(CONVERTED)) {
                    helper.assertTrue(!arena.getBlockEntity(pos).getPersistentData().getString(
                            ru.studhub.hungergames.arena.LazyArenaLoot.OPENED_EVENT).isEmpty(), "Actual menu opening did not mark the chest");
                }
                if (pos.equals(CHEST) || pos.equals(CONVERTED) || pos.equals(BARREL)) helper.assertTrue(player.containerMenu instanceof ChestMenu, "Wrong storage menu");
                if (pos.equals(BARREL)) helper.assertTrue(arena.getBlockState(pos).getValue(BarrelBlock.OPEN), "Barrel OPEN state update blocked");
                if (pos.equals(FURNACE)) helper.assertTrue(player.containerMenu instanceof FurnaceMenu, "Furnace menu did not open");
                if (pos.equals(CRAFTING)) helper.assertTrue(player.containerMenu instanceof CraftingMenu, "Crafting menu did not open");
                player.closeContainer();
            }
            player.getInventory().clearContent();
            var furnace = (FurnaceBlockEntity) arena.getBlockEntity(FURNACE);
            furnace.setItem(0, new ItemStack(Items.RAW_IRON));
            furnace.setItem(1, new ItemStack(Items.COAL));
            for (int i = 0; i < 205; i++) AbstractFurnaceBlockEntity.serverTick(arena, FURNACE, arena.getBlockState(FURNACE), furnace);
            helper.assertTrue(furnace.getItem(2).is(Items.IRON_INGOT), "Protected furnace cannot smelt");
            helper.assertTrue(arena.getBlockState(FURNACE).getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT), "Furnace LIT state update blocked");
            verifyEnvironment(helper, arena);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static void thirtyTwoPlayers(GameTestHelper helper, EventController controller, ServerLevel arena) {
        List<ServerPlayer> players = new ArrayList<>();
        controller.settings().countdownSeconds = 3;
        controller.settings().fullCountdownSeconds = 1;
        controller.open(false);
        for (int i = 0; i < 32; i++) {
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
            if (i == 14) helper.assertTrue(controller.phase() == EventController.Phase.RECRUITING, "Fifteen players started countdown");
            if (i == 15) {
                helper.assertTrue(controller.phase() == EventController.Phase.COUNTDOWN, "Sixteen players did not start countdown");
                controller.leave(player);
                helper.assertTrue(controller.phase() == EventController.Phase.RECRUITING, "Falling below sixteen did not reset countdown");
                controller.invite(player);
            }
        }
        ServerPlayer extra = player(helper);
        boolean full = false;
        try { controller.invite(extra); } catch (IllegalStateException expected) { full = true; }
        helper.assertTrue(full && controller.record(extra) == null, "Thirty-third participant was admitted");
        helper.getLevel().getServer().getPlayerList().remove(extra);
        prepared(helper, controller, EventController.Phase.BATTLE, () -> {
            helper.assertTrue(controller.phase() == EventController.Phase.BATTLE, "Full lobby did not automatically start");
            // Embedded test connections have no client to acknowledge dimension teleports.
            players.forEach(ServerPlayer::hasChangedDimension);
            helper.assertTrue(players.getFirst().hurt(arena.damageSources().generic(), 1), "Start unexpectedly grants invulnerability");
            players.getFirst().getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 3));
            players.getFirst().hurt(arena.damageSources().genericKill(), Float.MAX_VALUE);
            helper.runAfterDelay(40, () -> {
                ServerPlayer dead = helper.getLevel().getServer().getPlayerList().getPlayer(players.getFirst().getUUID());
                helper.assertTrue(dead != null && dead.gameMode.getGameModeForPlayer() == GameType.SPECTATOR, "Death did not respawn player as spectator");
                helper.assertTrue(controller.phase() == EventController.Phase.BATTLE, "Death prematurely ended thirty-two-player match");
                controller.leave(dead);
                for (int i = 1; i < 31; i++) controller.leave(players.get(i));
                prepared(helper, controller, EventController.Phase.IDLE, () -> {
                    ServerPlayer winner = players.get(31);
                    helper.assertTrue(controller.phase() == EventController.Phase.IDLE, "Thirty-two-player match did not finish");
                    helper.assertTrue(winner.getInventory().countItem(Items.DIAMOND) == 1, "Main reward missing");
                    helper.assertTrue(winner.getInventory().countItem(Items.NETHER_STAR) == 1, "Guaranteed thirty-two-player bonus missing");
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
            restarted.login(observer);
            restarted.restore(observer);
            helper.assertTrue(observer.serverLevel() == helper.getLevel(), "Journal recovery left player in arena");
            helper.assertTrue(observer.getInventory().countItem(Items.EMERALD) == 23, "Journal recovery lost or duplicated spectator items");
            helper.getLevel().getServer().getPlayerList().remove(observer);
            reconnectWinner(helper, restarted, arena);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void reconnectWinner(GameTestHelper helper, EventController controller, ServerLevel arena) {
        ServerPlayer winner = player(helper);
        controller.open(false);
        controller.invite(winner);
        controller.start(true);
        prepared(helper, controller, EventController.Phase.FINISHING, () -> {
            helper.assertTrue(winner.serverLevel() == arena, "Winner did not enter return delay");
            var profile = winner.getGameProfile();
            winner.server.getPlayerList().remove(winner);
            ServerPlayer reconnected = reconnect(helper, profile);
            helper.assertTrue(reconnected.serverLevel() == helper.getLevel(), "Reconnecting winner remained in arena until the delay ended");
            helper.assertTrue(reconnected.getInventory().countItem(Items.DIAMOND) == 1 && controller.record(reconnected) == null,
                    "Winner login lost or duplicated reward");
            controller.cancel(null);
            helper.getLevel().getServer().getPlayerList().remove(reconnected);
            helper.succeed();
        });
    }
}
