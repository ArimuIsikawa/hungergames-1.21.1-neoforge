package ru.studhub.hungergames.acceptance;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.Logger;
import ru.studhub.hungergames.HungerGames;
import ru.studhub.hungergames.arena.ArenaService;
import ru.studhub.hungergames.event.EventController;
import ru.studhub.hungergames.player.Position;

/** Explicit console-only fixture. Never creates or replaces blocks in the supplied arena. */
@Mod("hungergames_acceptance")
public final class DedicatedAcceptance {
    private static final Logger LOG = LogUtils.getLogger();
    private final List<UUID> ids = new ArrayList<>();
    private final List<Position> origins = new ArrayList<>();
    private MinecraftServer server;
    private EventController controller;
    private ServerLevel arena;
    private int stage = -1;
    private int ticks;
    private int stageStarted;
    private double originalBorder;

    public DedicatedAcceptance() {
        NeoForge.EVENT_BUS.addListener(this::commands);
        NeoForge.EVENT_BUS.addListener(this::tick);
        new TaczLootAcceptance();
    }

    private void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hg_acceptance")
                .requires(source -> source.getEntity() == null && source.hasPermission(4)
                        && Boolean.getBoolean("hungergames.acceptance"))
                .executes(ctx -> {
                    try {
                        check(stage < 0, "Acceptance is already running");
                        server = ctx.getSource().getServer();
                        check(server.getPlayerCount() == 0, "Disconnect real clients before acceptance");
                        controller = HungerGames.controller();
                        controller.requireIdle();
                        check(controller.status().contains("готово"), "Configure the actual map and temporary diamond pools first");
                        originalBorder = server.overworld().getWorldBorder().getSize();
                        controller.open(false);
                        for (int i = 0; i < 8; i++) controller.invite(bot());
                        check(controller.phase() == EventController.Phase.COUNTDOWN, "Eight players did not start countdown");
                        // Exercise the real operator command from inside the queue.
                        server.getCommands().performPrefixedCommand(player(0).createCommandSourceStack().withPermission(2), "hg start_event");
                        // Nested Minecraft commands execute after the current command context completes.
                        next(0);
                    } catch (Exception e) { fail(e); }
                    return 1;
                }));
    }

    private ServerPlayer bot() {
        var profile = new GameProfile(UUID.randomUUID(), "HG_Test" + ids.size());
        var cookie = CommonListenerCookie.createInitial(profile, false);
        var player = new ServerPlayer(server, server.overworld(), profile, cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);
        ids.add(profile.getId());
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        var guide = server.getAdvancements().get(ResourceLocation.parse("parcool:grant_parcool_guide"));
        if (guide != null) for (String criterion : player.getAdvancements().getOrStartProgress(guide).getRemainingCriteria()) {
            player.getAdvancements().award(guide, criterion);
        }
        player.getInventory().clearContent();
        player.hasChangedDimension();
        origins.add(Position.of(player));
        return player;
    }

    private ServerPlayer player(int index) { return server.getPlayerList().getPlayer(ids.get(index)); }
    private void next(int value) { stage = value; stageStarted = ticks; LOG.info("HG_ACCEPTANCE stage={}", stage); }
    private static void check(boolean value, String error) { if (!value) throw new IllegalStateException(error); }

    private void tick(ServerTickEvent.Post event) {
        ticks++;
        if (stage < 0) return;
        try {
            check(ticks - stageStarted < 6000, "Timed out in stage " + stage + ": " + controller.status());
            if (stage == 0 && controller.phase() == EventController.Phase.BATTLE) {
                arena = player(0).serverLevel();
                for (int i = 0; i < 8; i++) {
                    check(controller.live(player(i)), "Participant is not alive: " + i);
                    player(i).hasChangedDimension();
                    player(i).getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 2));
                }
                check(player(0).canHarmPlayer(player(1)), "Arena PvP is disabled");
                float health = player(0).getHealth();
                player(0).hurt(arena.damageSources().playerAttack(player(1)), 5);
                check(player(0).getHealth() < health, "Full modpack blocked actual player damage");
                BlockPos floor = player(1).blockPosition().below();
                var before = arena.getBlockState(floor);
                check(!before.isAir(), "Configured spawn is unsupported");
                check(!arena.setBlock(floor, Blocks.AIR.defaultBlockState(), 3), "Arena allowed block destruction");
                check(arena.getBlockState(floor).equals(before), "Arena floor changed");
                var index = JsonParser.parseString(Files.readString(server.getWorldPath(LevelResource.ROOT)
                        .resolve("data/hungergames/chests.json"))).getAsJsonArray();
                check(!index.isEmpty(), "No actual map containers were scanned");
                for (var element : index) {
                    var p = element.getAsJsonObject();
                    var container = ArenaService.container(arena, new BlockPos(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()));
                    check(container != null && container.countItem(Items.DIAMOND) == 1, "Map container was not refilled exactly once: " + p);
                }
                LOG.info("HG_ACCEPTANCE actual map containers verified: {}", index.size());
                ServerPlayer observer = bot();
                observer.getInventory().setItem(0, new ItemStack(Items.EMERALD, 17));
                controller.spectator(observer);
                check(observer.isSpectator() && observer.getInventory().isEmpty(), "Observer retained accessible inventory");
                controller.leave(observer);
                check(observer.getInventory().countItem(Items.EMERALD) == 17, "Observer lost personal items on leave");
                ServerPlayer finalObserver = bot();
                finalObserver.getInventory().setItem(0, new ItemStack(Items.EMERALD, 23));
                controller.spectator(finalObserver);
                player(0).hurt(arena.damageSources().genericKill(), 1000);
                next(1);
            } else if (stage == 1 && ticks - stageStarted >= 40) {
                check(player(0).isSpectator(), "Dead participant was not respawned as spectator");
                ServerPlayer disconnected = player(1);
                server.getPlayerList().remove(disconnected);
                check(controller.record(disconnected) == null, "Disconnected participant was not eliminated and restored");
                check(disconnected.getInventory().countItem(Items.GOLD_INGOT) == 0, "Disconnected participant retained battle loot");
                for (int i = 2; i < 7; i++) controller.leave(player(i));
                int dropped = arena.getEntitiesOfClass(ItemEntity.class, new AABB(-161, -64, 100, 164, 200, 425))
                        .stream().filter(e -> e.getItem().is(Items.GOLD_INGOT)).mapToInt(e -> e.getItem().getCount()).sum();
                check(dropped >= 14, "Defeated players did not drop their battle items: " + dropped);
                check(arena.getWorldBorder().getSize() < controller.settings().border.initialSize, "Arena border did not shrink");
                next(2);
            } else if (stage == 2 && controller.phase() == EventController.Phase.IDLE) {
                check(server.overworld().getWorldBorder().getSize() == originalBorder, "Overworld border changed");
                for (int i = 0; i < ids.size(); i++) {
                    if (player(i) == null) continue;
                    ServerPlayer player = player(i);
                    check(controller.record(player) == null, "Return journal is not empty: " + i);
                    check(Position.of(player).dimension().equals(origins.get(i).dimension()), "Player did not return to origin dimension");
                    Position origin = origins.get(i);
                    check(player.position().distanceToSqr(origin.x(), origin.y(), origin.z()) < 1, "Player did not return to origin coordinates");
                    check(player.getInventory().countItem(Items.GOLD_INGOT) == 0, "Player exported battle loot");
                }
                check(player(7).getInventory().countItem(Items.DIAMOND) == 1, "Winner did not receive one reward");
                check(player(7).getInventory().countItem(Items.NETHER_STAR) == 1, "Eight-player bonus was not guaranteed");
                controller.restore(player(7));
                check(player(7).getInventory().countItem(Items.DIAMOND) == 1, "Reward duplicated during repeated return");
                check(player(9).getInventory().countItem(Items.EMERALD) == 23, "Observer lost inventory at match end");
                cleanup();
                controller.start(true);
                next(3);
            } else if (stage == 3 && controller.phase() == EventController.Phase.IDLE) {
                LOG.info("HG_ACCEPTANCE PASS: actual map, eight players, force start, PvP, death, logout, leave, observers, loot, return, reward, bonus, zero-player start");
                stage = -1;
            }
        } catch (Exception e) { fail(e); }
    }

    private void cleanup() {
        for (UUID id : ids) {
            var player = server.getPlayerList().getPlayer(id);
            if (player != null) server.getPlayerList().remove(player);
        }
        ids.clear();
        origins.clear();
    }

    private void fail(Exception error) {
        stage = -1;
        LOG.error("HG_ACCEPTANCE FAIL", error);
        if (controller != null) try { controller.cancel("Acceptance test cleanup"); } catch (Exception e) { LOG.error("HG_ACCEPTANCE cancel failed", e); }
        if (server != null) cleanup();
    }
}
