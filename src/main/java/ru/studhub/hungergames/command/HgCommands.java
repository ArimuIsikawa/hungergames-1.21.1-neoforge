package ru.studhub.hungergames.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import ru.studhub.hungergames.HungerGames;
import ru.studhub.hungergames.config.HgSettings;
import ru.studhub.hungergames.event.EventController;
import ru.studhub.hungergames.player.Position;

public final class HgCommands {
    private HgCommands() {}

    @FunctionalInterface
    private interface Action { void run(CommandContext<CommandSourceStack> context, EventController controller) throws Exception; }

    private static Command<CommandSourceStack> action(Action action) {
        return context -> {
            try {
                EventController controller = HungerGames.controller();
                if (controller == null) throw new IllegalStateException("Hunger Games недоступен. Проверьте серверный журнал.");
                action.run(context, controller);
                return 1;
            } catch (Exception e) {
                context.getSource().sendFailure(EventController.message(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                HungerGames.LOGGER.debug("HG command failed", e);
                return 0;
            }
        };
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hg")
                .executes(action((ctx, c) -> ctx.getSource().sendSuccess(() -> EventController.message("/hg invite, /hg leave, /hg spectator, /hg status"), false)))
                .then(Commands.literal("invite").executes(action((ctx, c) -> c.invite(ctx.getSource().getPlayerOrException()))))
                .then(Commands.literal("leave").executes(action((ctx, c) -> c.leave(ctx.getSource().getPlayerOrException()))))
                .then(Commands.literal("spectator").executes(action((ctx, c) -> c.spectator(ctx.getSource().getPlayerOrException()))))
                .then(Commands.literal("status").executes(action((ctx, c) -> ctx.getSource().sendSuccess(() -> EventController.message(c.status()), false))))
                .then(Commands.literal("tp_center").requires(source -> source.hasPermission(2))
                        .executes(action((ctx, c) -> c.teleportCenter(ctx.getSource().getPlayerOrException()))))
                .then(Commands.literal("tp_world").requires(source -> source.hasPermission(2))
                        .executes(action((ctx, c) -> c.teleportWorld(ctx.getSource().getPlayerOrException()))))
                .then(Commands.literal("create_event").requires(source -> source.hasPermission(2))
                        .executes(action((ctx, c) -> c.open(false))))
                .then(Commands.literal("start_event").requires(source -> source.hasPermission(2))
                        .executes(action((ctx, c) -> c.start(true))))
                .then(Commands.literal("cancel").requires(source -> source.hasPermission(2))
                        .executes(action((ctx, c) -> c.cancel(null))))
                .then(Commands.literal("reload").requires(source -> source.hasPermission(2))
                        .executes(action((ctx, c) -> {
                            c.reload();
                            ctx.getSource().sendSuccess(() -> EventController.message("Настройки и пулы загружены."), true);
                        })))
                .then(Commands.literal("scan_chests").requires(source -> source.hasPermission(2))
                        .executes(action((ctx, c) -> {
                            c.scanChests();
                            ctx.getSource().sendSuccess(() -> EventController.message("Поиск сундуков запущен."), true);
                        })))
                .then(Commands.literal("convert_minecarts").requires(source -> source.hasPermission(2))
                        .executes(action((ctx, c) -> c.migrateContainers())))
                .then(Commands.literal("set_lobby").requires(source -> source.hasPermission(2))
                        .executes(action((ctx, c) -> {
                            c.requireIdle();
                            ServerPlayer player = arenaPlayer(ctx, c);
                            c.settings().lobby = Position.of(player);
                            c.saveSettings();
                            ctx.getSource().sendSuccess(() -> EventController.message("Точка ожидания сохранена."), true);
                        })))
                .then(Commands.literal("set_spawn").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player_num", IntegerArgumentType.integer(1, 8))
                                .executes(action((ctx, c) -> {
                                    c.requireIdle();
                                    ServerPlayer player = arenaPlayer(ctx, c);
                                    int slot = IntegerArgumentType.getInteger(ctx, "player_num");
                                    c.settings().spawns.put(slot, Position.of(player));
                                    c.saveSettings();
                                    ctx.getSource().sendSuccess(() -> EventController.message("Точка появления №" + slot + " сохранена."), true);
                                }))))
                .then(Commands.literal("arena").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("pos1").executes(action((ctx, c) -> setCorner(ctx, c, true))))
                        .then(Commands.literal("pos2").executes(action((ctx, c) -> setCorner(ctx, c, false)))))
                .then(Commands.literal("border").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("set_center").executes(action((ctx, c) -> {
                            c.requireIdle();
                            ServerPlayer player = arenaPlayer(ctx, c);
                            c.settings().border.centerX = player.getX();
                            c.settings().border.centerZ = player.getZ();
                            c.saveSettings();
                            ctx.getSource().sendSuccess(() -> EventController.message("Центр границы сохранён."), true);
                        })))));
    }

    private static ServerPlayer arenaPlayer(CommandContext<CommandSourceStack> context, EventController controller) throws Exception {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (!controller.arenaLevel(player.level())) throw new IllegalStateException("Перейдите в измерение " + controller.settings().arenaDimension);
        return player;
    }

    private static void setCorner(CommandContext<CommandSourceStack> context, EventController controller, boolean first) throws Exception {
        controller.requireIdle();
        ServerPlayer player = arenaPlayer(context, controller);
        var pos = player.blockPosition();
        var corner = new HgSettings.Corner(pos.getX(), pos.getY(), pos.getZ());
        if (first) controller.settings().corner1 = corner; else controller.settings().corner2 = corner;
        controller.saveSettings();
        context.getSource().sendSuccess(() -> EventController.message("Угол области " + (first ? 1 : 2) + " сохранён."), true);
    }
}
