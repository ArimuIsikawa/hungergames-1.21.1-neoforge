package ru.studhub.hungergames.event;

import java.io.IOException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import ru.studhub.hungergames.HungerGames;
import ru.studhub.hungergames.arena.ArenaService;
import ru.studhub.hungergames.arena.BorderService;
import ru.studhub.hungergames.config.ConfigManager;
import ru.studhub.hungergames.config.HgSettings;
import ru.studhub.hungergames.player.PlayerInventory;
import ru.studhub.hungergames.player.PlayerRecord;
import ru.studhub.hungergames.player.PlayerRecord.Role;
import ru.studhub.hungergames.player.Position;
import ru.studhub.hungergames.reward.RewardService;
import ru.studhub.hungergames.storage.EventJournal;
import ru.studhub.hungergames.mixin.ServerPlayerAccess;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.effect.MobEffectInstance;

public final class EventController {
    public enum Phase { IDLE, RECRUITING, COUNTDOWN, PREPARING, BATTLE, FINISHING }
    private static final String RECEIPT = "hungergames_return_receipt";
    private final MinecraftServer server;
    private final ConfigManager configs = new ConfigManager();
    private final EventJournal journal;
    private final ArenaService arena;
    private final BorderService border = new BorderService();
    private final RandomGenerator random = RandomGenerator.getDefault();
    private volatile ConfigManager.Snapshot active;
    private volatile Phase phase = Phase.IDLE;
    private long ticks;
    private long seconds;
    private long registrationDeadline;
    private long countdownDeadline = -1;
    private long battleStarted;
    private String configError;
    private boolean stopped;
    private boolean forcedStart;
    private int transfers;

    public boolean transferring() { return transfers > 0; }
    public void transfer(Runnable action) {
        transfers++;
        try { action.run(); } finally { transfers--; }
    }

    public EventController(MinecraftServer server) throws IOException {
        this.server = server;
        journal = new EventJournal(server);
        arena = new ArenaService(server);
        try { reload(); }
        catch (IOException | RuntimeException e) {
            configError = e.getMessage();
            HungerGames.LOGGER.error("Hunger Games config unavailable; recovery remains enabled: {}", configError);
        }
    }

    public Phase phase() { return phase; }
    public HgSettings settings() { return active == null ? configs.snapshot().settings() : active.settings(); }
    public PlayerRecord record(ServerPlayer player) { return journal.data.players.get(player.getStringUUID()); }
    public boolean engaged(ServerPlayer player) { PlayerRecord r = record(player); return r != null && r.role != Role.RETURNING; }
    public boolean live(ServerPlayer player) { PlayerRecord r = record(player); return r != null && r.role == Role.ALIVE; }
    public boolean protectedLevel(Level level) {
        return phase != Phase.IDLE && active != null && level.dimension().location().toString().equals(active.settings().arenaDimension);
    }
    public boolean arenaLevel(Level level) {
        try { return level.dimension().location().toString().equals(settings().arenaDimension); }
        catch (RuntimeException e) { return false; }
    }

    public void reload() throws IOException {
        requireIdle();
        configs.reload(server);
        configError = null;
    }

    public void requireIdle() {
        if (phase != Phase.IDLE || arena.busy()) throw new IllegalStateException("Сначала завершите событие: /hg cancel");
    }

    public void saveSettings() throws IOException {
        requireIdle();
        configs.saveSettings();
    }

    public void open(boolean scheduled) {
        requireIdle();
        ConfigManager.Snapshot next = configs.snapshot();
        arena.validate(next.settings(), 0);
        active = next;
        journal.data.eventId = UUID.randomUUID().toString();
        journal.data.startingPlayers = 0;
        journal.save();
        registrationDeadline = seconds + settings().registrationSeconds;
        countdownDeadline = -1;
        phase = Phase.RECRUITING;
        broadcast(settings().announcement);
    }

    public void invite(ServerPlayer player) {
        if ((phase != Phase.RECRUITING && phase != Phase.COUNTDOWN) || seconds >= registrationDeadline) {
            throw new IllegalStateException("Набор участников сейчас закрыт.");
        }
        if (record(player) != null) throw new IllegalStateException("Вы уже зарегистрированы или ожидаете возврата.");
        if (queued().size() >= settings().maxPlayers) throw new IllegalStateException("Все места заняты.");
        if (arenaLevel(player.level())) throw new IllegalStateException("Для участия вернитесь из измерения арены.");
        if (!player.isAlive()) throw new IllegalStateException("Сначала возродитесь.");
        if (!PlayerInventory.empty(player)) throw new IllegalStateException("Ваш инвентарь не пустой. Освободите также броню, вторую руку и аксессуары.");
        PlayerRecord record = PlayerRecord.capture(player, true);
        journal.data.players.put(record.uuid, record);
        journal.save();
        player.closeContainer();
        player.setGameMode(GameType.ADVENTURE);
        settings().lobby.teleport(player);
        player.sendSystemMessage(message("Вы в очереди. Выйти: /hg leave"));
        announceCount();
        updateCountdown();
    }

    private List<PlayerRecord> queued() { return journal.data.players.values().stream().filter(r -> r.role == Role.QUEUED).toList(); }
    private List<PlayerRecord> alive() { return journal.data.players.values().stream().filter(r -> r.role == Role.ALIVE).toList(); }

    private void updateCountdown() {
        long previous = countdownDeadline;
        countdownDeadline = EventRules.countdown(countdownDeadline, seconds, queued().size(), settings().minPlayers,
                settings().maxPlayers, settings().countdownSeconds, settings().fullCountdownSeconds);
        phase = countdownDeadline < 0 ? Phase.RECRUITING : Phase.COUNTDOWN;
        if (previous != countdownDeadline) {
            if (countdownDeadline < 0) broadcast("Недостаточно участников. Отсчёт сброшен.");
            else broadcast("До начала боя: " + (countdownDeadline - seconds) + " сек.");
        }
    }

    public void start(boolean forced) {
        ConfigManager.Snapshot candidate = active;
        if (phase == Phase.IDLE && forced) {
            requireIdle();
            candidate = configs.snapshot();
        } else if (phase != Phase.RECRUITING && phase != Phase.COUNTDOWN) {
            throw new IllegalStateException("Бой уже идёт или арена подготавливается.");
        }
        int count = queued().size();
        if (!forced && count < candidate.settings().minPlayers) throw new IllegalStateException("Недостаточно участников.");
        arena.validate(candidate.settings(), count);
        if (candidate.chests().entries().isEmpty() || candidate.rewards().entries().isEmpty()) {
            throw new IllegalStateException("Заполните chest_loot.json и rewards.json, затем /hg reload (вне события).");
        }
        for (PlayerRecord record : queued()) {
            ServerPlayer player = online(record);
            if (player == null || !player.isAlive() || !PlayerInventory.empty(player)) {
                throw new IllegalStateException("Участник не готов к бою: " + record.name);
            }
        }
        if (phase == Phase.IDLE) journal.data.eventId = UUID.randomUUID().toString();
        active = candidate;
        journal.data.startingPlayers = count;
        journal.save();
        forcedStart = forced;
        phase = Phase.PREPARING;
        broadcast("Подготовка арены. Участников: " + count + (forced ? " (принудительный старт)." : "."));
        arena.scan(settings(), active.chests(), random, error -> {
            if (error != null) { cancel(error); return; }
            try { beginBattle(); }
            catch (RuntimeException e) { HungerGames.LOGGER.error("Cannot start battle", e); cancel(e.getMessage()); }
        });
    }

    private void beginBattle() {
        List<PlayerRecord> players = queued();
        if (!forcedStart && players.size() < settings().minPlayers) throw new IllegalStateException("Участников стало меньше минимума во время подготовки");
        // Revalidate after the incremental scan: players may have left while it was running.
        for (PlayerRecord record : players) {
            ServerPlayer player = online(record);
            if (player == null) throw new IllegalStateException("Участник покинул подготовку: " + record.name);
            if (!PlayerInventory.empty(player)) {
                HungerGames.LOGGER.warn("Inventory changed during preparation for {}: {}", record.name, PlayerInventory.describe(player));
                throw new IllegalStateException("Участник получил предметы во время подготовки: " + record.name);
            }
        }
        journal.data.startingPlayers = players.size();
        for (PlayerRecord record : players) {
            record.role = Role.ALIVE;
            record.enteredBattle = true;
        }
        journal.save();
        phase = Phase.BATTLE;
        battleStarted = seconds;
        border.start(arena.level(settings()), settings().border, seconds);
        int slot = 1;
        for (PlayerRecord record : players) {
            ServerPlayer player = online(record);
            settings().spawns.get(slot++).teleport(player);
            player.setGameMode(GameType.ADVENTURE);
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5);
            player.removeAllEffects();
            player.clearFire();
            player.invulnerableTime = 0;
            ((ServerPlayerAccess) player).hungergames$setSpawnInvulnerableTime(0);
            border.sync(player);
        }
        broadcast(settings().battleMessage);
    }

    public void spectator(ServerPlayer player) {
        if (phase != Phase.BATTLE) throw new IllegalStateException("Сейчас нет активного боя.");
        if (record(player) != null) throw new IllegalStateException("Вы уже участвуете в событии.");
        if (arenaLevel(player.level())) throw new IllegalStateException("Сначала вернитесь из измерения арены.");
        if (!settings().spawns.containsKey(1)) throw new IllegalStateException("Не задана точка наблюдения: /hg set_spawn 1");
        player.closeContainer();
        PlayerRecord record = PlayerRecord.capture(player, false);
        journal.data.players.put(record.uuid, record);
        journal.save();
        PlayerInventory.clear(player);
        player.setGameMode(GameType.SPECTATOR);
        settings().spawns.get(1).teleport(player);
        border.sync(player);
        player.sendSystemMessage(message("Режим наблюдения. Ваши вещи сохраняются. Выход: /hg leave"));
    }

    public void leave(ServerPlayer player) {
        PlayerRecord record = record(player);
        if (record == null) throw new IllegalStateException("Вы не участвуете в событии.");
        if (record.role == Role.ALIVE) eliminate(player, true);
        else {
            record.role = Role.RETURNING;
            journal.save();
            restore(player);
        }
        if (phase == Phase.RECRUITING || phase == Phase.COUNTDOWN) { updateCountdown(); announceCount(); }
    }

    public void eliminate(ServerPlayer player, boolean leave) {
        PlayerRecord record = record(player);
        if (record == null || record.role != Role.ALIVE) return;
        PlayerInventory.drop(player);
        record.role = leave ? Role.RETURNING : Role.ELIMINATED;
        journal.save();
        broadcast(record.name + " выбывает. Осталось: " + alive().size());
        if (leave) restore(player);
    }

    public void logout(ServerPlayer player) {
        PlayerRecord record = record(player);
        if (record == null) return;
        if (record.role == Role.ALIVE) eliminate(player, true);
        else if (record.role != Role.RETURNING) {
            record.role = Role.RETURNING;
            journal.save();
            if (player.isAlive()) restore(player);
        }
        if (phase == Phase.RECRUITING || phase == Phase.COUNTDOWN) updateCountdown();
    }

    public void respawn(ServerPlayer player) {
        PlayerRecord record = record(player);
        if (record == null) return;
        if (record.role == Role.RETURNING) restore(player);
        else if (record.role == Role.ELIMINATED && phase == Phase.BATTLE) {
            player.setGameMode(GameType.SPECTATOR);
            settings().spawns.get(1).teleport(player);
            border.sync(player);
            player.sendSystemMessage(message("Вы выбыли и наблюдаете за боем. Выйти: /hg leave"));
        }
    }

    public void tick() {
        if (stopped) return;
        ticks++;
        arena.tick();
        if (ticks % 20 != 0) return;
        seconds++;
        for (PlayerRecord record : List.copyOf(journal.data.players.values())) {
            ServerPlayer player = online(record);
            if (player == null) continue;
            if (record.role == Role.RETURNING && player.isAlive()) {
                try { restore(player); }
                catch (RuntimeException e) { HungerGames.LOGGER.error("Player recovery failed for {}", record.uuid, e); }
            } else if (!player.isAlive() && (record.role == Role.ELIMINATED || record.role == Role.RETURNING)) {
                player.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            }
        }
        if (configError != null) return;
        HgSettings settings = settings();
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(settings.scheduleZone));
        if (EventRules.scheduleDue(now, LocalTime.parse(settings.scheduleTime), settings.registrationSeconds, journal.data.lastScheduledDate)) {
            journal.data.lastScheduledDate = now.toLocalDate().toString();
            journal.save();
            if (phase == Phase.IDLE && !arena.busy()) {
                try { open(true); }
                catch (RuntimeException e) { HungerGames.LOGGER.warn("Scheduled event not opened: {}", e.getMessage()); }
            }
        }
        if (phase == Phase.RECRUITING || phase == Phase.COUNTDOWN) {
            if (seconds >= registrationDeadline && queued().size() < settings.minPlayers) { cancel("Набор закрыт: недостаточно участников."); return; }
            if (countdownDeadline >= 0) {
                long remaining = countdownDeadline - seconds;
                if (remaining <= 0) {
                    try { start(false); }
                    catch (RuntimeException e) { cancel("Старт отменён: " + e.getMessage()); }
                } else if (remaining == 60 || remaining == 30 || remaining == 15 || remaining <= 5) broadcast("До боя: " + remaining + " сек.");
            }
        } else if (phase == Phase.BATTLE) {
            border.update(seconds);
            if (alive().size() <= 1) finish();
            else if (settings.maxMatchSeconds > 0 && seconds - battleStarted >= settings.maxMatchSeconds) cancel("Истекло время матча. Победителя нет.");
        }
    }

    private void finish() {
        List<PlayerRecord> survivors = alive();
        PlayerRecord winner = survivors.isEmpty() ? null : survivors.getFirst();
        if (winner != null) winner.reward = RewardService.prepare(server, active, journal.data.startingPlayers, random);
        phase = Phase.FINISHING;
        for (PlayerRecord record : journal.data.players.values()) record.role = Role.RETURNING;
        journal.save();
        broadcast(winner == null ? "Бой завершён без победителя." : settings().winnerMessage.replace("{player}", winner.name));
        cleanup();
    }

    public void cancel(String reason) {
        if (phase == Phase.IDLE && !arena.busy()) throw new IllegalStateException("Активного события нет.");
        phase = Phase.FINISHING;
        for (PlayerRecord record : journal.data.players.values()) record.role = Role.RETURNING;
        journal.save();
        broadcast(reason == null ? "Событие отменено администратором." : reason);
        cleanup();
    }

    private void cleanup() {
        arena.release();
        border.reset();
        for (PlayerRecord record : List.copyOf(journal.data.players.values())) {
            ServerPlayer player = online(record);
            if (player != null && player.isAlive()) {
                try { restore(player); }
                catch (RuntimeException e) { HungerGames.LOGGER.error("Cannot return {}", record.name, e); }
            }
        }
        phase = Phase.IDLE;
        active = null;
        countdownDeadline = -1;
    }

    public void restore(ServerPlayer player) {
        PlayerRecord record = record(player);
        if (record == null || record.role != Role.RETURNING || !player.isAlive()) return;
        if (!record.token.equals(player.getPersistentData().getString(RECEIPT))) {
            Position destination = record.origin;
            ServerLevel target = destination.level(server);
            if (target != null) target.getChunkAt(destination.block());
            if (target == null || arenaLevel(target) || !target.noCollision(new AABB(destination.x() - .3, destination.y(), destination.z() - .3,
                    destination.x() + .3, destination.y() + 1.8, destination.z() + .3))) {
                BlockPos spawn = server.overworld().getSharedSpawnPos();
                destination = new Position(Level.OVERWORLD.location().toString(), spawn.getX() + .5, spawn.getY() + .1, spawn.getZ() + .5, 0, 0);
            }
            if (record.participant) {
                if (record.enteredBattle) PlayerInventory.clear(player);
            } else PlayerInventory.restoreSnapshot(player, record.inventory);
            destination.teleport(player);
            player.setGameMode(GameType.byName(record.gameMode, GameType.SURVIVAL));
            player.getFoodData().setFoodLevel(record.food);
            player.getFoodData().setSaturation(record.saturation);
            player.clearFire();
            player.removeAllEffects();
            if (record.effects != null) for (var effect : record.effects) player.addEffect(MobEffectInstance.CODEC.parse(
                    player.registryAccess().createSerializationContext(JsonOps.INSTANCE), effect).getOrThrow());
            player.setHealth(Math.clamp(record.health, 1, player.getMaxHealth()));
            if (record.participant) {
                player.experienceLevel = record.experienceLevel;
                player.experienceProgress = record.experienceProgress;
                player.totalExperience = record.totalExperience;
                player.setExperienceLevels(record.experienceLevel);
                player.setExperiencePoints((int) (record.experienceProgress * player.getXpNeededForNextLevel()));
                RewardService.give(player, record.reward);
            }
            player.getPersistentData().putString(RECEIPT, record.token);
        }
        server.getPlayerList().saveAll();
        ru.studhub.hungergames.storage.PlayerSaveReceipt.verify(server, player, RECEIPT, record.token);
        journal.data.players.remove(record.uuid);
        journal.save();
        player.connection.send(new ClientboundInitializeBorderPacket(player.serverLevel().getWorldBorder()));
        player.sendSystemMessage(message("Вы вернулись с события." + (record.reward != null && !record.reward.isEmpty() ? " Награда выдана." : "")));
    }

    public void syncBorder(ServerPlayer player) { border.sync(player); }

    public void scanChests() {
        requireIdle();
        arena.scan(settings(), null, random, error -> {
            arena.release();
            broadcast(error == null ? "Поиск завершён. Сундуков: " + arena.chestCount() : error);
        });
    }

    public void stop() {
        if (phase != Phase.IDLE || arena.busy()) cancel("Событие завершено: сервер останавливается.");
        journal.save();
        stopped = true;
    }

    public String status() {
        String readiness = "готово";
        if (configError == null) {
            try {
                arena.validate(settings(), settings().maxPlayers);
                var snapshot = active == null ? configs.snapshot() : active;
                if (snapshot.chests().entries().isEmpty() || snapshot.rewards().entries().isEmpty()) readiness = "заполните JSON-пулы";
            } catch (RuntimeException e) { readiness = e.getMessage(); }
        } else readiness = configError;
        return "Состояние: " + phase + "; в очереди: " + queued().size() + "; живых: " + alive().size()
                + "; сундуков: " + arena.chestCount() + "; ожидают возврата: "
                + journal.data.players.values().stream().filter(r -> r.role == Role.RETURNING).count()
                + "; готовность: " + readiness;
    }

    private ServerPlayer online(PlayerRecord record) { return server.getPlayerList().getPlayer(UUID.fromString(record.uuid)); }
    private void announceCount() { broadcast("Участников: " + queued().size() + "/" + settings().maxPlayers); }
    public void broadcast(String text) { server.getPlayerList().broadcastSystemMessage(message(text), false); }
    public static Component message(String text) { return Component.literal("[HG] " + text); }
}
