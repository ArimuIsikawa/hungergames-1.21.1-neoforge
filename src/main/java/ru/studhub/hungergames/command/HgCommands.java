package ru.studhub.hungergames.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import ru.studhub.hungergames.HungerGames;
import ru.studhub.hungergames.config.HgSettings;
import ru.studhub.hungergames.event.EventController;
import ru.studhub.hungergames.player.Position;
import ru.studhub.hungergames.storage.JsonFiles;

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
                        .executes(action((ctx, c) -> c.open(false)))
                        .then(Commands.argument("min", IntegerArgumentType.integer(2, HgSettings.PLAYER_LIMIT))
                                .then(Commands.argument("max", IntegerArgumentType.integer(2, HgSettings.PLAYER_LIMIT))
                                        .executes(action((ctx, c) -> {
                                            int minimum = IntegerArgumentType.getInteger(ctx, "min");
                                            int maximum = IntegerArgumentType.getInteger(ctx, "max");
                                            c.open(minimum, maximum);
                                            ctx.getSource().sendSuccess(() -> EventController.message(
                                                    "Набор открыт: от " + minimum + " до " + maximum + " участников."), true);
                                        })))))
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
                        .then(Commands.argument("player_num", IntegerArgumentType.integer(1, HgSettings.PLAYER_LIMIT))
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
                        .then(Commands.literal("set_center").executes(action(HgCommands::setBorderCenter))))
                .then(Commands.literal("zona").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("set")
                                .then(Commands.literal("center").executes(action(HgCommands::setBorderCenter))
                                        .then(Commands.argument("x", DoubleArgumentType.doubleArg(-29_000_000, 29_000_000))
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg(-29_000_000, 29_000_000))
                                                        .executes(action((ctx, c) -> setBorderCenter(ctx, c,
                                                                DoubleArgumentType.getDouble(ctx, "x"), DoubleArgumentType.getDouble(ctx, "z")))))))
                                .then(Commands.literal("speed")
                                        .then(Commands.argument("blocks_per_second", DoubleArgumentType.doubleArg(.001, 59_000_000))
                                                .executes(action((ctx, c) -> {
                                                    double speed = DoubleArgumentType.getDouble(ctx, "blocks_per_second");
                                                    saveBorder(c, next -> next.shrinkBlocksPerSecond = speed);
                                                    ctx.getSource().sendSuccess(() -> EventController.message(
                                                            "Скорость сужения зоны сохранена: сторона уменьшается на " + speed + " блоков/с."), true);
                                                }))))
                                .then(Commands.literal("size")
                                        .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(1, 59_000_000))
                                                .executes(action((ctx, c) -> {
                                                    double size = DoubleArgumentType.getDouble(ctx, "blocks");
                                                    saveBorder(c, next -> {
                                                        if (size < next.finalSize) throw new IllegalArgumentException(
                                                                "Размер зоны не может быть меньше конечного размера: " + next.finalSize);
                                                        next.initialSize = size;
                                                    });
                                                    ctx.getSource().sendSuccess(() -> EventController.message(
                                                            "Начальный размер зоны сохранён: " + size + " × " + size + " блоков."), true);
                                                })))))));
    }

    private static void saveBorder(EventController controller, Consumer<HgSettings.Border> edit) throws Exception {
        controller.requireIdle();
        HgSettings settings = controller.settings();
        HgSettings.Border previous = settings.border;
        HgSettings.Border next = JsonFiles.GSON.fromJson(JsonFiles.GSON.toJsonTree(previous), HgSettings.Border.class);
        edit.accept(next);
        settings.border = next;
        try { controller.saveSettings(); }
        catch (Exception error) { settings.border = previous; throw error; }
    }

    private static void setBorderCenter(CommandContext<CommandSourceStack> context, EventController controller) throws Exception {
        controller.requireIdle();
        ServerPlayer player = arenaPlayer(context, controller);
        setBorderCenter(context, controller, player.getX(), player.getZ());
    }

    private static void setBorderCenter(CommandContext<CommandSourceStack> context, EventController controller, double x, double z) throws Exception {
        saveBorder(controller, next -> { next.centerX = x; next.centerZ = z; });
        context.getSource().sendSuccess(() -> EventController.message("Центр зоны сохранён: X=" + x + ", Z=" + z + "."), true);
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
